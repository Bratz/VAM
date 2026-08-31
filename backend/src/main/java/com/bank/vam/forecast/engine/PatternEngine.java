package com.bank.vam.forecast.engine;

import com.bank.vam.entity.payables.Payable;
import com.bank.vam.entity.payables.Payable.PayableStatus;
import com.bank.vam.entity.payables.Payable.PayableType;
import com.bank.vam.forecast.domain.ForecastCategory;
import com.bank.vam.forecast.domain.ForecastLine;
import com.bank.vam.forecast.domain.enums.ForecastSource;
import com.bank.vam.forecast.repository.PayableForecastRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * T4 — Pattern engine. Generates forecast lines from scheduled outflows
 * (payroll, tax, rent, intercompany) by reading open {@link Payable} rows
 * with pattern-relevant types.
 *
 * <p>Routing: claims categories whose {@link ForecastCategory#getDefaultEngine()}
 * is {@link ForecastSource#PATTERN}. Each open payable in the horizon emits
 * exactly one line at the payable's {@code dueDate}.
 *
 * <p>Type mapping (PayableType → ForecastCategory.code):
 * <ul>
 *   <li>{@link PayableType#SALARY}        → {@code PAYROLL}</li>
 *   <li>{@link PayableType#TAX}           → {@code TAX}</li>
 *   <li>{@link PayableType#UTILITY}       → {@code RENT}  (closest seeded category for recurring opex)</li>
 *   <li>{@link PayableType#INTERCOMPANY}  → {@code INTERCOMPANY_OUT}</li>
 *   <li>{@link PayableType#OTHER}         → {@code RENT}  (fallback for unclassified recurring outflows)</li>
 * </ul>
 *
 * Other PayableType values (INVOICE, EXPENSE, SUBSCRIPTION, REFUND) flow
 * through the AGING engine and are intentionally not consumed here.
 *
 * <p>This engine does not yet read historical seasonality / day-of-week
 * patterns — that's Sprint 2 work. Sprint 1 ships the simpler
 * "one open payable → one forecast line at dueDate" projection so the
 * orchestrator + DoD test are unblocked.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PatternEngine implements ForecastEngine {

    /** PayableType values this engine harvests, mapped to the category code. */
    private static final Map<PayableType, String> TYPE_TO_CATEGORY_CODE;
    static {
        Map<PayableType, String> m = new EnumMap<>(PayableType.class);
        m.put(PayableType.SALARY, "PAYROLL");
        m.put(PayableType.TAX, "TAX");
        m.put(PayableType.UTILITY, "RENT");
        m.put(PayableType.INTERCOMPANY, "INTERCOMPANY_OUT");
        m.put(PayableType.OTHER, "RENT");
        TYPE_TO_CATEGORY_CODE = Map.copyOf(m);
    }

    /**
     * Payable statuses considered "closed" — already-settled rows that must
     * not be forecast again. Passed to the repository query (rather than
     * hard-coded in JPQL) so fully-qualified enum literals don't trip the
     * Hibernate validator at startup.
     */
    private static final Set<PayableStatus> CLOSED_STATUSES = EnumSet.of(
        PayableStatus.PAID,
        PayableStatus.CANCELLED,
        PayableStatus.REJECTED,
        PayableStatus.NETTED
    );

    private final PayableForecastRepository payableRepository;

    @Override
    public ForecastSource type() {
        return ForecastSource.PATTERN;
    }

    @Override
    public boolean supports(ForecastCategory category) {
        return category != null && category.getDefaultEngine() == ForecastSource.PATTERN;
    }

    @Override
    public List<ForecastLine> generate(ForecastContext ctx) {
        Set<UUID> entityIds = ctx.entityIds();
        if (entityIds == null || entityIds.isEmpty()) {
            log.debug("PatternEngine: no entities in scope; emitting 0 lines");
            return List.of();
        }

        // Index the categories the orchestrator handed us by code so we can
        // resolve PayableType → ForecastCategory in O(1).
        Map<String, ForecastCategory> categoryByCode = new java.util.HashMap<>();
        for (ForecastCategory c : ctx.categories()) {
            categoryByCode.put(c.getCode(), c);
        }

        List<Payable> candidates = payableRepository.findPatternCandidates(
            entityIds,
            TYPE_TO_CATEGORY_CODE.keySet(),
            CLOSED_STATUSES,
            ctx.horizonStart(),
            ctx.horizonEnd()
        );

        List<ForecastLine> out = new ArrayList<>(candidates.size());
        int skipped = 0;
        for (Payable p : candidates) {
            String categoryCode = TYPE_TO_CATEGORY_CODE.get(p.getPayableType());
            ForecastCategory category = categoryByCode.get(categoryCode);
            if (category == null) {
                // The orchestrator filtered categories by supports(); a
                // PATTERN payable whose target category isn't in scope
                // (e.g. seed missing) is silently skipped, not a failure.
                skipped++;
                continue;
            }
            BigDecimal amount = p.getOutstandingAmount();
            if (amount == null || amount.signum() <= 0) {
                amount = p.getNetAmount();
            }
            if (amount == null || amount.signum() <= 0) {
                skipped++;
                continue;
            }

            out.add(ForecastLine.builder()
                .run(ctx.run())
                .valueDate(p.getDueDate())
                .entityId(p.getOwningEntityId())
                .currency(p.getCurrencyCode())
                .category(category)
                .amountMid(amount)
                .source(ForecastSource.PATTERN)
                .sourceRef("payable:" + p.getId())
                .build());
        }
        log.debug("PatternEngine emitted {} lines ({} candidates skipped) for corporate {}",
            out.size(), skipped, ctx.corporateId());
        return out;
    }
}
