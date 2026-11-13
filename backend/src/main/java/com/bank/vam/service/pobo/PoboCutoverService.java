package com.bank.vam.service.pobo;

import com.bank.vam.config.CurrentUser;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.MirrorAccountType;
import com.bank.vam.entity.payables.Payable;
import com.bank.vam.entity.payables.Payable.PayableStatus;
import com.bank.vam.entity.payables.Payable.PaymentRoute;
import com.bank.vam.entity.payables.Payable.PoboRequestStatus;
import com.bank.vam.entity.pobo.IntercompanyRecharge;
import com.bank.vam.entity.pobo.IntercompanyRecharge.RechargeStatus;
import com.bank.vam.entity.treasury.SweepRule;
import com.bank.vam.entity.treasury.SweepRuleSource;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.payables.PayableRepository;
import com.bank.vam.repository.pobo.IntercompanyRechargeRepository;
import com.bank.vam.repository.treasury.SweepRuleRepository;
import com.bank.vam.service.treasury.IhbMirrorService;
import com.bank.vam.service.treasury.SweepService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Cut-over of a corporate to POBO by enrolment, and the one-off repair (spec docs/specs/pobo-in-house-bank.md D8, D9).
 * Preview first (dry run changes nothing); re-running after a real run changes nothing:
 * (a) payables stuck in the retired request/approval flow go back to Approved, to be routed at payment by the enrolment;
 * (b) treasury's mirrors set to the R10 rule from each IHB current position;
 * (c) per-payment POBO recharges closed as settled by the IHB position;
 * (d) IHB credit use set from the balance; (e) sweep rules on in-house accounts stopped;
 * (f) report only: past self-sweeps of IHB current accounts that added cash to a treasury account.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PoboCutoverService {

    /** Marks a recharge closed by the cut-over: settled through the IHB current position (D8). */
    public static final String SETTLED_BY_IHB = "IHB-POSITION-CUTOVER";

    private final PayableRepository payables;
    private final PoboEnrolmentService enrolments;
    private final VirtualAccountRepository accounts;
    private final IhbMirrorService ihbMirrors;
    private final IntercompanyRechargeRepository recharges;
    private final LegalEntityRepository legalEntities;
    private final SweepRuleRepository sweepRules;
    private final TransactionRepository ledger;

    /** One thing and what happens (dry run) or happened to it. */
    public record Item(String key, String subject, String sub, BigDecimal amount, String currency, String detail) {
    }

    /** {@code pastSelfSweeps} is information only: the cut-over never changes it. */
    public record Report(UUID corporateId, boolean dryRun, List<Item> payables, List<Item> mirrors, List<Item> recharges,
                         List<Item> creditLimits, List<Item> sweepRules, List<Item> pastSelfSweeps) {
        public int changes() {
            return payables.size() + mirrors.size() + recharges.size() + creditLimits.size() + sweepRules.size();
        }
    }

    @Transactional
    public Report cutover(UUID corporateId, boolean dryRun) {
        if (corporateId == null) throw new BusinessException("Choose the corporate");
        List<VirtualAccount> corporateVas = accounts.findByCorporateId(corporateId);
        List<VirtualAccount> ihbCurrents = corporateVas.stream()
            .filter(va -> va.getMirrorAccountType() == MirrorAccountType.IHB_CURRENT || va.isIhbParticipant())
            .sorted(Comparator.comparing(VirtualAccount::getVaNumber, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();

        Report report = new Report(corporateId, dryRun, payables(corporateId, dryRun), new ArrayList<>(),
            closePoboRecords(corporateId, dryRun), new ArrayList<>(), sweepRules(corporateId, corporateVas, dryRun),
            pastSelfSweeps(ihbCurrents));
        for (VirtualAccount va : ihbCurrents) {
            creditUse(va, dryRun).ifPresent(report.creditLimits()::add); // before the mirrors: their sync also sets it
            report.mirrors().addAll(mirrors(va, dryRun));
        }
        if (!dryRun) {
            log.info("POBO cut-over for corporate {}: {} payables, {} mirrors, {} recharges, {} credit limits, {} sweep rules",
                corporateId, report.payables().size(), report.mirrors().size(), report.recharges().size(),
                report.creditLimits().size(), report.sweepRules().size());
        }
        return report;
    }

    // (a) ---------------------------------------------------------------------------------------------------------

    private List<Item> payables(UUID corporateId, boolean dryRun) {
        List<Item> out = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (PayableStatus status : List.of(PayableStatus.PENDING_POBO, PayableStatus.POBO_APPROVED)) {
            for (Payable p : payables.findByCorporateIdAndStatus(corporateId, status)) {
                String was = status == PayableStatus.PENDING_POBO ? "Pending POBO" : "POBO approved";
                if (!dryRun) {
                    p.setStatus(p.getPaidAmount() != null && p.getPaidAmount().signum() > 0 ? PayableStatus.PARTIAL : PayableStatus.APPROVED);
                    p.setPaymentRoute(PaymentRoute.DIRECT);
                    p.setPoboRequestStatus(PoboRequestStatus.NOT_REQUESTED);
                    p.setPoboRequestId(null);
                    p.setIsPobo(false);
                    p.setPaymentViaEntityId(null);
                    p.setPaymentViaEntityCode(null);
                    p.setBehalfOfEntity(null);
                    p.setBehalfOfVaId(null);
                    p.setUpdatedBy("POBO cut-over by " + CurrentUser.name());
                    payables.save(p);
                }
                out.add(new Item(p.getId().toString(), p.getPayableNumber(),
                    (p.getOwningEntityName() != null ? p.getOwningEntityName() : p.getOwningEntityCode())
                        + (p.getVendorName() != null ? " to " + p.getVendorName() : ""),
                    p.getNetAmount(), p.getCurrencyCode(), was + " -> Approved; " + howItWillPay(p, today)));
            }
        }
        return out;
    }

    /** What the enrolment rule (spec R4-R6) will do when the payable is paid; decided again on the payment date. */
    private String howItWillPay(Payable p, LocalDate day) {
        if (Boolean.TRUE.equals(p.getIsIntercompany())) return "intercompany, paid by its own route";
        if (!Boolean.TRUE.equals(p.getPayDirectly())) {
            try {
                Optional<PoboEnrolmentService.Route> route = enrolments.route(p.getOwningEntityId(), p.getCurrencyCode(), day);
                if (route.isPresent()) {
                    return "will pay on behalf via " + route.get().payer().getEntityCode() + " from "
                        + route.get().paymentVa().getVaNumber() + ", booked to " + route.get().ihbCurrent().getVaNumber();
                }
            } catch (BusinessException e) {
                return "enrolled, but " + e.getMessage();
            }
        }
        String from = Optional.ofNullable(p.getVirtualAccountId()).flatMap(accounts::findById).map(VirtualAccount::getVaNumber)
            .map(n -> " from " + n).orElse(" (choose its account before paying)");
        String why = enrolments.notEnrolledReason(p.getOwningEntityId(), p.getCurrencyCode(), day);
        return "will pay directly" + from + (why != null ? " (" + why + ")" : "");
    }

    // (b) ---------------------------------------------------------------------------------------------------------

    private List<Item> mirrors(VirtualAccount ihbCurrent, boolean dryRun) {
        BigDecimal position = nz(ihbCurrent.getCurrentBalance());
        List<Item> out = new ArrayList<>();
        mirror(ihbCurrent, ihbCurrent.getIcReceivableVaId(), "IC receivable", position.negate().max(BigDecimal.ZERO), position, out);
        mirror(ihbCurrent, ihbCurrent.getIcPayableVaId(), "IC payable", position.max(BigDecimal.ZERO), position, out);
        if (!dryRun && !out.isEmpty()) {
            ihbMirrors.syncIhbMirrors(ihbCurrent, "POBO cut-over repair (D8)", "POBO-CUTOVER-" + ihbCurrent.getVaNumber());
        }
        return out;
    }

    private void mirror(VirtualAccount ihbCurrent, UUID mirrorId, String label, BigDecimal target, BigDecimal position, List<Item> out) {
        if (mirrorId == null) return;
        VirtualAccount m = accounts.findById(mirrorId).orElse(null);
        if (m == null) return;
        BigDecimal before = nz(m.getCurrentBalance());
        if (before.compareTo(target) == 0) return;
        out.add(new Item(m.getId().toString(), m.getVaNumber(), label + " for " + owner(ihbCurrent), target, m.getCurrencyCode(),
            label + " " + plain(before) + " -> " + plain(target) + " to follow " + ihbCurrent.getVaNumber() + "'s position "
                + plain(position) + " (spec R10)"));
    }

    // (c) ---------------------------------------------------------------------------------------------------------

    private List<Item> closePoboRecords(UUID corporateId, boolean dryRun) {
        Set<UUID> entities = legalEntities.findByCorporateIdOrderByHierarchyPath(corporateId).stream()
            .map(e -> e.getId()).collect(Collectors.toSet());
        List<Item> out = new ArrayList<>();
        // ponytail: scans every recharge (it carries no corporate); query by entity if they grow.
        for (IntercompanyRecharge r : recharges.findAll()) {
            if (r.getRechargeType() != IntercompanyRecharge.RechargeType.POBO_PAYMENT) continue;
            if (r.getStatus() == RechargeStatus.SETTLED || r.getStatus() == RechargeStatus.CANCELLED) continue;
            if (!entities.contains(r.getPayerEntityId()) && !entities.contains(r.getBehalfEntityId())) continue;
            String was = String.valueOf(r.getStatus());
            if (!dryRun) {
                r.settle(IntercompanyRecharge.SettlementMethod.IHB_DEPOSIT_OFFSET, SETTLED_BY_IHB);
                recharges.save(r);
            }
            out.add(new Item(r.getId().toString(), r.getRechargeReference(), r.getPayerEntityCode() + " paid for " + r.getBehalfEntityCode(),
                r.getTotalRecharge(), r.getCurrencyCode(), "POBO recharge " + was + " -> settled by the IHB position: the payment is "
                    + "already on " + r.getBehalfEntityCode() + "'s IHB current account, so netting and IC settlement no longer count it"));
        }
        return out;
    }

    // (d) ---------------------------------------------------------------------------------------------------------

    private Optional<Item> creditUse(VirtualAccount ihbCurrent, boolean dryRun) {
        BigDecimal usedBefore = ihbCurrent.getCreditLimitUtilized();
        BigDecimal availableBefore = ihbCurrent.getCreditLimitAvailable();
        VirtualAccount probe = dryRun ? VirtualAccount.builder().currentBalance(ihbCurrent.getCurrentBalance())
            .effectiveCreditLimit(ihbCurrent.getEffectiveCreditLimit()).creditLimitUtilized(usedBefore)
            .creditLimitAvailable(availableBefore).build() : ihbCurrent;
        if (!probe.followIhbCreditUse()) return Optional.empty();
        if (!dryRun) accounts.save(ihbCurrent);
        return Optional.of(new Item(ihbCurrent.getId().toString(), ihbCurrent.getVaNumber(), owner(ihbCurrent),
            probe.getCreditLimitAvailable(), ihbCurrent.getCurrencyCode(),
            "Credit used " + plain(nz(usedBefore)) + " -> " + plain(probe.getCreditLimitUtilized()) + ", available "
                + (availableBefore != null ? plain(availableBefore) : "not set") + " -> " + plain(probe.getCreditLimitAvailable())
                + " (limit " + plain(nz(ihbCurrent.getEffectiveCreditLimit())) + ", balance " + plain(nz(ihbCurrent.getCurrentBalance())) + ")"));
    }

    // (e) ---------------------------------------------------------------------------------------------------------

    private List<Item> sweepRules(UUID corporateId, List<VirtualAccount> corporateVas, boolean dryRun) {
        Map<UUID, VirtualAccount> inHouse = corporateVas.stream().filter(SweepService::isInHouse)
            .collect(Collectors.toMap(VirtualAccount::getId, Function.identity()));
        List<Item> out = new ArrayList<>();
        for (SweepRule rule : sweepRules.findByCorporateIdOrUnset(corporateId)) {
            if (rule.getStatus() == SweepRule.SweepStatus.DISABLED || rule.getSourceAccounts() == null) continue;
            List<SweepRuleSource> bad = rule.getSourceAccounts().stream().filter(s -> inHouse.containsKey(s.getAccountId())).toList();
            if (bad.isEmpty()) continue;
            String accountsText = bad.stream().map(SweepRuleSource::getAccountNumber).collect(Collectors.joining(", "));
            boolean all = bad.size() == rule.getSourceAccounts().size();
            if (!dryRun) {
                if (all) rule.setStatus(SweepRule.SweepStatus.DISABLED);
                else rule.getSourceAccounts().removeAll(bad);
                sweepRules.save(rule);
            }
            out.add(new Item(rule.getId().toString(), rule.getRuleReference(), rule.getRuleName(), null, rule.getCurrencyCode(), all
                ? "Rule disabled: it sweeps " + accountsText + ", an in-house account (a position with treasury, not cash)"
                : accountsText + " taken out of the rule (in-house, not cash); its " + (rule.getSourceAccounts().size() - (dryRun ? bad.size() : 0))
                    + " other source(s) still sweep"));
        }
        return out;
    }

    // (f) ---------------------------------------------------------------------------------------------------------

    /**
     * Past self-sweeps, report only: an execution swept an IHB current account (SWEEP_OUT to a treasury account) and
     * credited it straight back by the intercompany leg (SWEEPIC, same execution reference), so the treasury account
     * gained that cash from nothing. Per IHB current account and treasury account.
     */
    private List<Item> pastSelfSweeps(List<VirtualAccount> ihbCurrents) {
        List<Item> out = new ArrayList<>();
        for (VirtualAccount va : ihbCurrents) {
            List<Transaction> rows = ledger.findByVaId(va.getId());
            Set<String> selfSwept = rows.stream()
                .filter(t -> t.getReferenceNumber() != null && t.getReferenceNumber().startsWith("SWEEPIC") && t.getExternalReference() != null)
                .map(Transaction::getExternalReference).collect(Collectors.toSet());
            Map<UUID, List<Transaction>> byTarget = rows.stream()
                .filter(t -> t.getMovementType() == Transaction.MovementType.SWEEP_OUT && selfSwept.contains(t.getExternalReference())
                    && t.getCounterpartyVaId() != null)
                .collect(Collectors.groupingBy(Transaction::getCounterpartyVaId, LinkedHashMap::new, Collectors.toList()));
            byTarget.forEach((targetId, sweeps) -> {
                String target = accounts.findById(targetId).map(VirtualAccount::getVaNumber).orElse(targetId.toString());
                BigDecimal total = sweeps.stream().map(t -> nz(t.getAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
                List<String> refs = sweeps.stream().map(Transaction::getExternalReference).sorted().toList();
                LocalDateTime first = sweeps.stream().map(Transaction::getTransactionDate).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
                LocalDateTime last = sweeps.stream().map(Transaction::getTransactionDate).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
                out.add(new Item(va.getId() + ":" + targetId, va.getVaNumber(), "to " + target, total, va.getCurrencyCode(),
                    sweeps.size() + " self-sweep(s)" + (first != null ? " " + first.toLocalDate() + " to " + last.toLocalDate() : "")
                        + " added " + plain(total) + " " + va.getCurrencyCode() + " to " + target + " (executions "
                        + String.join(", ", refs.size() > 5 ? List.of(refs.get(0), "...", refs.get(refs.size() - 1)) : refs)
                        + "). Not changed: decide whether to correct it"));
            });
        }
        return out;
    }

    private static String owner(VirtualAccount va) {
        return va.getOwningEntityCode() != null ? va.getOwningEntityCode() : va.getVaNumber();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
