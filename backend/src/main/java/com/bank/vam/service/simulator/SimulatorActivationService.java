package com.bank.vam.service.simulator;

import com.bank.vam.dto.treasury.NotionalPoolDto;
import com.bank.vam.dto.treasury.SweepRuleDto;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.simulator.SimulatorScenario;
import com.bank.vam.entity.treasury.NotionalPool;
import com.bank.vam.entity.treasury.PoolMember;
import com.bank.vam.entity.treasury.SweepRule;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.simulator.SimulatorScenarioRepository;
import com.bank.vam.repository.treasury.NotionalPoolRepository;
import com.bank.vam.service.treasury.NotionalPoolService;
import com.bank.vam.service.treasury.SweepService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The ONE sandbox→live writer. Single-user (no co-approver): the treasurer
 * proposes AND activates, gated by {@code simulator.v3.activation} and an
 * explicit client-side confirm. The most auditable surface in the app.
 *
 * <p><b>Atomic + fail-closed.</b> The whole activation is one transaction:
 * status flips READY→PROPOSED→ACTIVATED and every live Sweep Rule is created
 * within it; ANY problem throws → the transaction rolls back (status reverts
 * to READY, no partial live writes) and {@link #recordActivationFailure} (a
 * separate REQUIRES_NEW tx) persists the audited reason.
 *
 * <p><b>Scope (single-user V1):</b> creates live Sweep Rules AND notional
 * Pools over Shadow VAs that ALREADY exist for the proposed physical
 * accounts. It does NOT auto-provision new Shadow VAs (that needs hierarchy
 * context — parent AGGREGATION + program — the sandbox does not capture;
 * guessing it would corrupt the live tree). If a proposed shadow has no
 * live mirror, activation fails closed with a precise message. MODIFY/RETIRE
 * of live rules and pools is deferred (the Diff surfaces them). Pools are
 * created via {@link NotionalPoolService#createPool} in the SAME transaction
 * (atomic + fail-closed), ADD-only, home-bank members only (the live
 * assertPoolEligible guard). These limits are deliberate on a money-movement
 * surface — see tasks/todo.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimulatorActivationService {

    private final SimulatorScenarioRepository scenarioRepository;
    private final VirtualAccountRepository vaRepository;
    private final SweepService sweepService;
    private final NotionalPoolService notionalPoolService;
    private final NotionalPoolRepository notionalPoolRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public SimulatorScenario activate(
            UUID scenarioId,
            String notes,
            LocalDateTime scheduledAt,
            String activatedBy) {

        SimulatorScenario scenario = scenarioRepository.findById(scenarioId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Simulator scenario not found: " + scenarioId));

        if (scenario.getStatus() != SimulatorScenario.ScenarioStatus.READY) {
            throw new BusinessException(
                    "Scenario must be READY to activate (current: "
                            + scenario.getStatus() + ").");
        }

        // Audited intent — rolled back atomically if anything below fails.
        scenario.setStatus(SimulatorScenario.ScenarioStatus.PROPOSED);
        scenario.setProposedBy(activatedBy);
        scenario.setProposalNotes(notes);
        scenario.setScheduledActivationAt(scheduledAt);
        scenario.setActivationError(null);
        scenarioRepository.save(scenario);

        JsonNode payload;
        try {
            payload = objectMapper.readTree(
                    scenario.getProposedPayload() == null
                            ? "{}"
                            : scenario.getProposedPayload());
        } catch (Exception e) {
            throw new BusinessException(
                    "Proposed structure is unreadable: " + e.getMessage());
        }

        JsonNode shadows = payload.path("shadows");
        JsonNode rules = payload.path("rules");
        JsonNode pools = payload.path("pools");
        boolean hasRules = rules.isArray() && !rules.isEmpty();
        boolean hasPools = pools.isArray() && !pools.isEmpty();
        if (!hasRules && !hasPools) {
            throw new BusinessException(
                    "Nothing to activate: the scenario has no sweep rules "
                            + "or notional pools.");
        }

        // localId → physicalAccountId (from proposed shadows).
        Map<String, UUID> localToPhys = new HashMap<>();
        for (JsonNode s : shadows) {
            String localId = s.path("localId").asText(null);
            String pa = s.path("physicalAccountId").asText(null);
            if (localId != null && pa != null) {
                localToPhys.put(localId, UUID.fromString(pa));
            }
        }

        // physicalAccountId → live Shadow VA (must already exist; fail closed).
        Map<UUID, VirtualAccount> physToVa = new HashMap<>();
        for (UUID pa : localToPhys.values()) {
            if (physToVa.containsKey(pa)) {
                continue;
            }
            VirtualAccount va = vaRepository.findByLinkedPhysicalAccountId(pa)
                    .orElseThrow(() -> new BusinessException(
                            "No live Shadow VA exists for physical account "
                                    + pa + ". Provision the mirror before "
                                    + "activating (single-user V1 does not "
                                    + "auto-create shadows)."));
            physToVa.put(pa, va);
        }

        int created = 0;
        for (JsonNode r : rules) {
            created += createLiveRule(r, localToPhys, physToVa);
        }

        // Notional pools — created in the SAME transaction as the rules
        // (atomic + fail-closed). createPool is @Transactional and JOINS
        // this tx; assertPoolEligible inside it is the home-bank safety net
        // (a non-home-bank member ⇒ BusinessException ⇒ whole rollback).
        int poolsCreated = 0;
        if (hasPools) {
            List<Set<UUID>> liveActiveSets = liveActivePoolPhysSets();
            for (JsonNode p : pools) {
                poolsCreated +=
                        createLivePool(p, localToPhys, physToVa, liveActiveSets);
            }
        }

        scenario.setStatus(SimulatorScenario.ScenarioStatus.ACTIVATED);
        scenario.setActivatedBy(activatedBy);
        scenario.setActivatedAt(LocalDateTime.now());
        scenario.setActivationError(null);
        SimulatorScenario saved = scenarioRepository.save(scenario);
        log.info("Simulator scenario {} ACTIVATED by {} — {} live sweep "
                        + "rule(s), {} notional pool(s) created.",
                scenarioId, activatedBy, created, poolsCreated);
        return saved;
    }

    private int createLiveRule(
            JsonNode r,
            Map<String, UUID> localToPhys,
            Map<UUID, VirtualAccount> physToVa) {

        String targetLocal = r.path("targetLocalId").asText(null);
        UUID targetPhys = targetLocal == null ? null : localToPhys.get(targetLocal);
        VirtualAccount targetVa = targetPhys == null ? null : physToVa.get(targetPhys);
        if (targetVa == null) {
            throw new BusinessException(
                    "Rule '" + r.path("ruleName").asText("?")
                            + "' has an unresolved target.");
        }

        List<SweepRuleDto.SourceAccountRequest> sources = new ArrayList<>();
        for (JsonNode src : r.path("sourceLocalIds")) {
            UUID phys = localToPhys.get(src.asText());
            VirtualAccount sva = phys == null ? null : physToVa.get(phys);
            if (sva == null) {
                throw new BusinessException(
                        "Rule '" + r.path("ruleName").asText("?")
                                + "' has an unresolved source.");
            }
            SweepRuleDto.SourceAccountRequest s =
                    new SweepRuleDto.SourceAccountRequest();
            s.setAccountId(sva.getId());
            s.setAccountNumber(sva.getVaNumber());
            s.setCurrencyCode(sva.getCurrencyCode());
            sources.add(s);
        }
        if (sources.isEmpty()) {
            throw new BusinessException(
                    "Rule '" + r.path("ruleName").asText("?")
                            + "' has no resolvable sources.");
        }

        SweepRuleDto.CreateRequest req = new SweepRuleDto.CreateRequest();
        req.setRuleName(r.path("ruleName").asText("Simulator rule"));
        req.setSweepType(parseType(r.path("sweepType").asText(null)));
        req.setFrequency(parseFreq(r.path("frequency").asText(null)));
        req.setExecutionTime(parseTime(r.path("executionTime").asText(null)));
        req.setTargetAccountId(targetVa.getId());
        req.setTargetAccountNumber(targetVa.getVaNumber());
        req.setCurrencyCode(targetVa.getCurrencyCode());
        if (r.hasNonNull("targetAmount")) {
            req.setTargetAmount(r.get("targetAmount").decimalValue());
        }
        if (r.hasNonNull("thresholdMin")) {
            req.setThresholdMin(r.get("thresholdMin").decimalValue());
        }
        if (r.hasNonNull("thresholdMax")) {
            req.setThresholdMax(r.get("thresholdMax").decimalValue());
        }
        if (r.hasNonNull("percentage")) {
            req.setPercentage(r.get("percentage").decimalValue());
        }
        req.setSourceAccounts(sources);

        sweepService.createRule(req);
        return 1;
    }

    /**
     * Create ONE live notional pool from a proposed-pool JSON node, in the
     * caller's transaction. ADD-only: if a live ACTIVE pool already covers
     * the exact same member physical-account set this fails closed
     * (MODIFY/RETIRE of live pools is deferred — same documented stance as
     * sweep rules; the Diff surfaces it).
     */
    private int createLivePool(
            JsonNode p,
            Map<String, UUID> localToPhys,
            Map<UUID, VirtualAccount> physToVa,
            List<Set<UUID>> liveActiveSets) {

        String poolName = p.path("poolName").asText("Simulator pool");
        JsonNode memberIds = p.path("memberLocalIds");
        if (!memberIds.isArray() || memberIds.size() < 2) {
            throw new BusinessException(
                    "Notional pool '" + poolName
                            + "' needs at least two members.");
        }

        List<NotionalPoolDto.MemberRequest> members = new ArrayList<>();
        Set<UUID> physSet = new HashSet<>();
        for (JsonNode mid : memberIds) {
            String localId = mid.asText(null);
            UUID phys = localId == null ? null : localToPhys.get(localId);
            VirtualAccount va = phys == null ? null : physToVa.get(phys);
            if (va == null) {
                throw new BusinessException(
                        "Notional pool '" + poolName
                                + "' has an unresolved member — provision the"
                                + " mirror before activating.");
            }
            physSet.add(phys);
            NotionalPoolDto.MemberRequest mr =
                    new NotionalPoolDto.MemberRequest();
            mr.setAccountId(va.getId());
            mr.setAccountNumber(va.getVaNumber());
            members.add(mr);
        }

        // ADD-only conflict guard (mirrors the rules' deferred MODIFY/RETIRE).
        for (Set<UUID> live : liveActiveSets) {
            if (live.equals(physSet)) {
                throw new BusinessException(
                        "A live notional pool already covers these accounts."
                                + " Modifying or retiring live pools via the"
                                + " simulator is deferred (the Diff surfaces"
                                + " it).");
            }
        }

        NotionalPoolDto.CreateRequest req =
                new NotionalPoolDto.CreateRequest();
        req.setPoolName(poolName);
        String ccy = p.path("poolCurrency").asText(null);
        if (ccy != null && !ccy.isBlank()) {
            req.setPoolCurrency(ccy);
        }
        if (p.hasNonNull("poolRatePct")) {
            req.setInterestRate(p.get("poolRatePct").decimalValue());
        } else {
            req.setInterestRate(BigDecimal.ZERO);
        }
        if (p.hasNonNull("targetBalance")) {
            req.setTargetBalance(p.get("targetBalance").decimalValue());
        }
        req.setInterestCalculationMethod(
                parsePoolCalcMethod(
                        p.path("interestCalcMethod").asText(null)));
        // allocationMethod left null → NotionalPoolService defaults
        // CONTRIBUTION_PERCENT (the simulator does not model allocation).
        req.setMembers(members);

        // @Transactional (REQUIRED) — joins activate()'s tx: ANY failure
        // (incl. assertPoolEligible) rolls the whole activation back.
        notionalPoolService.createPool(req);
        return 1;
    }

    /**
     * Map the simulator's free-text calc-method label onto a REAL
     * {@link NotionalPool.InterestCalculationMethod} constant (never guess an
     * enum — see lessons L8). Unknown / blank ⇒ the live default
     * DAILY_AVERAGE. The live interest math is method-independent
     * (rate ÷ 36500); this is disclosed metadata only.
     */
    private NotionalPool.InterestCalculationMethod parsePoolCalcMethod(
            String v) {
        if (v == null) {
            return NotionalPool.InterestCalculationMethod.DAILY_AVERAGE;
        }
        switch (v) {
            case "MONTH_END_BALANCE":
            case "MONTH_END":
                return NotionalPool.InterestCalculationMethod.MONTH_END;
            case "TIER_BASED":
                return NotionalPool.InterestCalculationMethod.TIER_BASED;
            default:
                return NotionalPool.InterestCalculationMethod.DAILY_AVERAGE;
        }
    }

    /**
     * Member physical-account SETs of every live ACTIVE notional pool — the
     * ADD-only conflict basis (a PoolMember.accountId is a VA id; resolve to
     * its physical-account id, the same key the Diff matches on).
     */
    private List<Set<UUID>> liveActivePoolPhysSets() {
        List<Set<UUID>> out = new ArrayList<>();
        for (NotionalPool pool
                : notionalPoolRepository.findAllActiveWithMembers()) {
            Set<UUID> set = new HashSet<>();
            for (PoolMember m : pool.getMembers()) {
                if (m.getAccountId() == null) {
                    continue;
                }
                VirtualAccount va =
                        vaRepository.findById(m.getAccountId()).orElse(null);
                if (va != null && va.getPhysicalAccountId() != null) {
                    set.add(va.getPhysicalAccountId());
                }
            }
            if (!set.isEmpty()) {
                out.add(set);
            }
        }
        return out;
    }

    private SweepRule.SweepType parseType(String v) {
        if (v == null) {
            throw new BusinessException("Rule is missing a sweep type.");
        }
        try {
            return SweepRule.SweepType.valueOf(v);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Unsupported sweep type: " + v);
        }
    }

    /** ON_DEMAND has no live equivalent — fail closed (no silent remap). */
    private SweepRule.SweepFrequency parseFreq(String v) {
        if (v == null) {
            throw new BusinessException("Rule is missing a frequency.");
        }
        try {
            return SweepRule.SweepFrequency.valueOf(v);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                    "Frequency '" + v + "' has no live equivalent — choose "
                            + "DAILY / WEEKLY / MONTHLY before activating.");
        }
    }

    private LocalTime parseTime(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(v);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Persist the failure reason AFTER the main transaction rolled back
     * (so it survives). Status is left READY (the rollback already reverted
     * the PROPOSED flip).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordActivationFailure(UUID scenarioId, String message) {
        scenarioRepository.findById(scenarioId).ifPresent(s -> {
            s.setStatus(SimulatorScenario.ScenarioStatus.READY);
            s.setActivationError(
                    message == null ? "Activation failed" : message);
            scenarioRepository.save(s);
        });
    }
}
