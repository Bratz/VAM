package com.bank.vam.forecast.engine;

import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.entity.receivables.Receivable.ReceivableStatus;
import com.bank.vam.forecast.domain.ForecastCategory;
import com.bank.vam.forecast.domain.ForecastLine;
import com.bank.vam.forecast.domain.enums.ForecastSource;
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
 * balances by projecting their collection date.
 *
 * <p>Routing: claims categories whose {@link ForecastCategory#getDefaultEngine()}
 * is {@link ForecastSource#AGING}. Sprint 1 emits AR-only; AP aging is a
 * Sprint 2 extension (would need a parallel {@code PayableForecastRepository}
 * query and the {@code AP_DISBURSEMENTS} category).
 *
 * <p>Projection rule (Sprint 1 — deliberately simple):
 * <ul>
 *   <li>{@link ReceivableStatus#OPEN} / {@link ReceivableStatus#PARTIAL} →
 *       projected date is {@code dueDate}, clipped into the horizon (a past
 *       due date shifts forward to {@code today}).</li>
 *   <li>{@link ReceivableStatus#OVERDUE} → projected date is
 *       {@code today + 7d} (single-bucket aging shift). Sprint 2 will
 *       introduce real per-bucket DSO lookbacks.</li>
 * </ul>
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
    private static final Set<ReceivableStatus> OPEN_STATUSES = EnumSet.of(
        ReceivableStatus.OPEN,
        ReceivableStatus.PARTIAL,
        ReceivableStatus.OVERDUE
    );

    private final ReceivableForecastRepository receivableRepository;

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
        ForecastCategory arCategory = ctx.categories().stream()
            .filter(c -> "AR_COLLECTIONS".equals(c.getCode()))
            .findFirst()
            .orElse(null);
        if (arCategory == null) {
            log.debug("AgingEngine: AR_COLLECTIONS category not in scope; emitting 0 lines");
            return List.of();
        }

        List<Receivable> openAR = receivableRepository.findOpenForAging(entityIds, OPEN_STATUSES);
        List<ForecastLine> out = new ArrayList<>(openAR.size());
        int skipped = 0;

        for (Receivable r : openAR) {
            LocalDate projected = projectedCollectionDate(r, ctx.runDate());
            if (projected == null) {
                skipped++;
                continue;
            }
            // Clip to horizon — projected dates outside [horizonStart, horizonEnd] are dropped.
            if (projected.isBefore(ctx.horizonStart()) || projected.isAfter(ctx.horizonEnd())) {
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
        log.debug("AgingEngine emitted {} lines ({} candidates clipped/skipped) for corporate {}",
            out.size(), skipped, ctx.corporateId());
        return out;
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
}
