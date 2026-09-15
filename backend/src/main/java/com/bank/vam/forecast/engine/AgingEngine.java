package com.bank.vam.forecast.engine;

import com.bank.vam.entity.payables.Payable;
import com.bank.vam.entity.payables.Payable.PayableStatus;
import com.bank.vam.entity.payables.Payable.PayableType;
import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.entity.receivables.Receivable.ReceivableStatus;
import com.bank.vam.forecast.domain.ForecastCategory;
import com.bank.vam.forecast.domain.ForecastLine;
import com.bank.vam.forecast.domain.enums.ForecastSource;
import com.bank.vam.forecast.repository.PayableForecastRepository;
import com.bank.vam.forecast.repository.ReceivableForecastRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * T5 — Aging engine. Generates forecast lines for open accounts-receivable
 * and accounts-payable balances by projecting their collection/payment date.
 *
 * <p>Routing: claims categories whose {@link ForecastCategory#getDefaultEngine()}
 * is {@link ForecastSource#AGING} — today that's {@code AR_COLLECTIONS} and
 * {@code AP_DISBURSEMENTS}.
 *
 * <p>AR projection rule (Sprint 1 — deliberately simple):
 * <ul>
 *   <li>{@link ReceivableStatus#OPEN} / {@link ReceivableStatus#PARTIAL} →
 *       projected date is {@code dueDate}, clipped into the horizon (a past
 *       due date shifts forward to {@code today}).</li>
 *   <li>{@link ReceivableStatus#OVERDUE} → projected date is
 *       {@code today + 7d} (single-bucket aging shift). Sprint 2 will
 *       introduce real per-bucket DSO lookbacks.</li>
 * </ul>
 *
 * <p>AP projection is simpler — {@link Payable} has no stored OVERDUE status
 * (it's a computed property, {@code isOverdue()}), so every open row just
 * projects to {@code dueDate}, clipped forward to {@code today} if already
 * past due. "Open" mirrors {@code PatternEngine}'s own closed-status set
 * (PAID/CANCELLED/REJECTED/NETTED) for consistency.
 *
 * Lines whose projected value date falls outside the horizon are dropped.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgingEngine implements ForecastEngine {

    /** Sprint-1 aging-shift constant for OVERDUE balances (calendar days). */
    private static final int OVERDUE_SHIFT_DAYS = 7;

    /**
     * Statuses the aging engine treats as "in-flight" — receivables that
     * have not yet been resolved one way or another. The set is passed to
     * the repository query (rather than hard-coded in JPQL) so
     * fully-qualified enum literals don't trip the Hibernate validator at
     * startup.
     */
    private static final Set<ReceivableStatus> AR_OPEN_STATUSES = EnumSet.of(
        ReceivableStatus.OPEN,
        ReceivableStatus.PARTIAL,
        ReceivableStatus.OVERDUE
    );

    /** PayableType values the AP side of this engine treats as aging-like (mirrors PatternEngine's exclusion list). */
    private static final Set<PayableType> AP_AGING_TYPES = EnumSet.of(
        PayableType.INVOICE,
        PayableType.EXPENSE,
        PayableType.SUBSCRIPTION,
        PayableType.REFUND
    );

    /** Same closed-status semantic PatternEngine already established for Payables. */
    private static final Set<PayableStatus> AP_CLOSED_STATUSES = EnumSet.of(
        PayableStatus.PAID,
        PayableStatus.CANCELLED,
        PayableStatus.REJECTED,
        PayableStatus.NETTED
    );

    private final ReceivableForecastRepository receivableRepository;
    private final PayableForecastRepository payableRepository;

    @Override
    public ForecastSource type() {
        return ForecastSource.AGING;
    }

    @Override
    public boolean supports(ForecastCategory category) {
        return category != null && category.getDefaultEngine() == ForecastSource.AGING;
    }

    @Override
    public List<ForecastLine> generate(ForecastContext ctx) {
        Set<UUID> entityIds = ctx.entityIds();
        if (entityIds == null || entityIds.isEmpty()) {
            log.debug("AgingEngine: no entities in scope; emitting 0 lines");
            return List.of();
        }

        List<ForecastLine> out = new ArrayList<>();
        out.addAll(generateReceivableLines(ctx, entityIds));
        out.addAll(generatePayableLines(ctx, entityIds));
        return out;
    }

    private List<ForecastLine> generateReceivableLines(ForecastContext ctx, Set<UUID> entityIds) {
        ForecastCategory arCategory = findCategory(ctx, "AR_COLLECTIONS");
        if (arCategory == null) {
            log.debug("AgingEngine: AR_COLLECTIONS category not in scope; emitting 0 AR lines");
            return List.of();
        }

        List<Receivable> openAR = receivableRepository.findOpenForAging(entityIds, AR_OPEN_STATUSES);
        List<ForecastLine> out = new ArrayList<>(openAR.size());
        int skipped = 0;

        for (Receivable r : openAR) {
            LocalDate projected = projectedCollectionDate(r, ctx.runDate());
            // Clip to horizon — projected dates outside [horizonStart, horizonEnd] are dropped.
            if (projected == null || projected.isBefore(ctx.horizonStart()) || projected.isAfter(ctx.horizonEnd())) {
                skipped++;
                continue;
            }
            out.add(ForecastLine.builder()
                .run(ctx.run())
                .valueDate(projected)
                .entityId(r.getOwningEntityId())
                .currency(r.getCurrencyCode())
                .category(arCategory)
                .amountMid(r.getOutstandingAmount())
                .source(ForecastSource.AGING)
                .sourceRef("receivable:" + r.getId())
                .build());
        }
        log.debug("AgingEngine emitted {} AR lines ({} candidates clipped/skipped) for corporate {}",
            out.size(), skipped, ctx.corporateId());
        return out;
    }

    private List<ForecastLine> generatePayableLines(ForecastContext ctx, Set<UUID> entityIds) {
        ForecastCategory apCategory = findCategory(ctx, "AP_DISBURSEMENTS");
        if (apCategory == null) {
            log.debug("AgingEngine: AP_DISBURSEMENTS category not in scope; emitting 0 AP lines");
            return List.of();
        }

        List<Payable> openAP = payableRepository.findOpenForAging(entityIds, AP_AGING_TYPES, AP_CLOSED_STATUSES);
        List<ForecastLine> out = new ArrayList<>(openAP.size());
        int skipped = 0;

        for (Payable p : openAP) {
            LocalDate projected = projectedPaymentDate(p, ctx.runDate());
            if (projected == null || projected.isBefore(ctx.horizonStart()) || projected.isAfter(ctx.horizonEnd())) {
                skipped++;
                continue;
            }
            out.add(ForecastLine.builder()
                .run(ctx.run())
                .valueDate(projected)
                .entityId(p.getOwningEntityId())
                .currency(p.getCurrencyCode())
                .category(apCategory)
                .amountMid(p.getOutstandingAmount())
                .source(ForecastSource.AGING)
                .sourceRef("payable:" + p.getId())
                .build());
        }
        log.debug("AgingEngine emitted {} AP lines ({} candidates clipped/skipped) for corporate {}",
            out.size(), skipped, ctx.corporateId());
        return out;
    }

    private ForecastCategory findCategory(ForecastContext ctx, String code) {
        return ctx.categories().stream()
            .filter(c -> code.equals(c.getCode()))
            .findFirst()
            .orElse(null);
    }

    /**
     * Projected collection date. OPEN/PARTIAL → dueDate (forwarded to
     * {@code runDate} if it's already past); OVERDUE → {@code runDate + 7d}.
     * Returns {@code null} if dueDate is missing on a non-OVERDUE row (cannot
     * project without it).
     */
    private LocalDate projectedCollectionDate(Receivable r, LocalDate today) {
        ReceivableStatus status = r.getStatus();
        if (status == ReceivableStatus.OVERDUE) {
            return today.plusDays(OVERDUE_SHIFT_DAYS);
        }
        LocalDate due = r.getDueDate();
        if (due == null) {
            return null;
        }
        return due.isBefore(today) ? today : due;
    }

    /**
     * Projected payment date. No OVERDUE status exists on {@link Payable}
     * (it's the computed {@code isOverdue()}), so every open row just
     * projects to {@code dueDate}, forwarded to {@code today} if already
     * past due. Returns {@code null} if {@code dueDate} is missing.
     */
    private LocalDate projectedPaymentDate(Payable p, LocalDate today) {
        LocalDate due = p.getDueDate();
        if (due == null) {
            return null;
        }
        return due.isBefore(today) ? today : due;
    }
}
