package com.bank.vam.service.simulator;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.simulator.SimulatorScenario;
import com.bank.vam.entity.treasury.NotionalPool;
import com.bank.vam.entity.treasury.PoolMember;
import com.bank.vam.entity.treasury.SweepRule;
import com.bank.vam.entity.treasury.SweepRuleSource;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.simulator.SimulatorScenarioRepository;
import com.bank.vam.repository.treasury.NotionalPoolRepository;
import com.bank.vam.repository.treasury.SweepRuleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Captures the corporate's CURRENT live Shadow VAs + Sweep Rules + notional
 * Pools into {@code simulator_scenarios.snapshot_payload} — the frozen diff
 * baseline.
 *
 * <p><b>Sandbox isolation:</b> reads live config; writes ONLY the sandbox
 * {@code simulator_scenarios} row. No live operational table is mutated.
 *
 * <p><b>Freeze-on-first:</b> the baseline is captured once and NOT re-taken
 * on subsequent Diff opens (so a treasurer can analyse, walk away, and return
 * weeks later to the diff vs. the world as it was). {@code force=true} (the
 * explicit "Refresh snapshot" action) re-captures.
 *
 * <p>Physical-account ids are resolved here (server-side) so the pure
 * frontend {@code diffEngine} matches by <i>what a rule does</i>
 * (source-physical SET + target physical), not by entity ids.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScenarioSnapshotService {

    private final SimulatorScenarioRepository scenarioRepository;
    private final VirtualAccountRepository vaRepository;
    private final SweepRuleRepository sweepRuleRepository;
    private final NotionalPoolRepository notionalPoolRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public SimulatorScenario captureSnapshot(UUID scenarioId, boolean force) {
        SimulatorScenario scenario = scenarioRepository.findById(scenarioId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Simulator scenario not found: " + scenarioId));

        String existing = scenario.getSnapshotPayload();
        boolean alreadyCaptured = existing != null
                && !existing.isBlank()
                && !"{}".equals(existing.trim());
        if (!force && alreadyCaptured) {
            // Frozen baseline — return as-is.
            return scenario;
        }

        UUID corporateId = scenario.getCorporateId();

        // Live Shadow VAs (PHYSICAL_MIRROR) for the corporate.
        List<VirtualAccount> shadows = vaRepository.findByCorporateIdAndAccountCategory(
                corporateId, VirtualAccount.AccountCategory.PHYSICAL_MIRROR);

        Map<UUID, UUID> vaToPhys = new HashMap<>();
        List<Map<String, Object>> shadowJson = new ArrayList<>();
        for (VirtualAccount va : shadows) {
            if (va.getPhysicalAccountId() == null) {
                continue;
            }
            vaToPhys.put(va.getId(), va.getPhysicalAccountId());
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", va.getId().toString());
            s.put("vaName", va.getVaName());
            s.put("physicalAccountId", va.getPhysicalAccountId().toString());
            s.put("currencyCode", va.getCurrencyCode());
            s.put("bankCode", va.getBankSwift());
            shadowJson.add(s);
        }

        // Live ACTIVE Sweep Rules whose target + ≥1 source resolve to a
        // shadow's physical account (i.e. the shadow-level sweeps the
        // Simulator models). Others are irrelevant to the diff.
        List<Map<String, Object>> ruleJson = new ArrayList<>();
        for (SweepRule r : sweepRuleRepository.findByStatus(SweepRule.SweepStatus.ACTIVE)) {
            UUID tgtPhys = vaToPhys.get(r.getTargetAccountId());
            if (tgtPhys == null) {
                continue;
            }
            List<String> srcPhys = new ArrayList<>();
            for (SweepRuleSource src : r.getSourceAccounts()) {
                UUID p = vaToPhys.get(src.getAccountId());
                if (p != null) {
                    srcPhys.add(p.toString());
                }
            }
            if (srcPhys.isEmpty()) {
                continue;
            }
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("id", r.getId().toString());
            rm.put("ruleName", r.getRuleName());
            rm.put("sweepType", r.getSweepType() != null ? r.getSweepType().name() : null);
            rm.put("frequency", r.getFrequency() != null ? r.getFrequency().name() : null);
            rm.put("executionTime",
                    r.getExecutionTime() != null ? r.getExecutionTime().toString() : null);
            rm.put("sourcePhysicalAccountIds", srcPhys);
            rm.put("targetPhysicalAccountId", tgtPhys.toString());
            ruleJson.add(rm);
        }

        // Live ACTIVE notional pools whose membership intersects this
        // corporate's shadow set. A PoolMember.accountId IS a VA id, and
        // vaToPhys already holds exactly this corporate's home-bank
        // PHYSICAL_MIRROR VAs — so member ∩ corporate-shadows needs no
        // pool.corporateId (mirrors how the rule filter resolves via
        // vaToPhys). Physical-account ids are resolved here so the pure
        // frontend diffEngine matches a pool by its membership SET.
        List<Map<String, Object>> poolJson = new ArrayList<>();
        for (NotionalPool pool : notionalPoolRepository.findAllActiveWithMembers()) {
            List<String> memberPhys = new ArrayList<>();
            for (PoolMember m : pool.getMembers()) {
                UUID phys = vaToPhys.get(m.getAccountId());
                if (phys != null) {
                    memberPhys.add(phys.toString());
                }
            }
            if (memberPhys.isEmpty()) {
                continue;
            }
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("id", pool.getId().toString());
            pm.put("poolName", pool.getPoolName());
            pm.put("poolReference", pool.getPoolReference());
            pm.put("poolCurrency", pool.getPoolCurrency());
            pm.put("interestRate", pool.getInterestRate());
            pm.put("memberPhysicalAccountIds", memberPhys);
            poolJson.add(pm);
        }

        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("takenAt", now.toString());
        snapshot.put("shadows", shadowJson);
        snapshot.put("rules", ruleJson);
        snapshot.put("pools", poolJson);

        try {
            scenario.setSnapshotPayload(objectMapper.writeValueAsString(snapshot));
        } catch (Exception e) {
            throw new BusinessException("Failed to serialise snapshot: " + e.getMessage());
        }
        scenario.setSnapshotTakenAt(now);
        SimulatorScenario saved = scenarioRepository.save(scenario);
        log.info("Simulator snapshot captured for scenario {} — {} shadows, {} rules, {} pools (force={})",
                scenarioId, shadowJson.size(), ruleJson.size(), poolJson.size(), force);
        return saved;
    }
}
