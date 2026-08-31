package com.bank.vam.forecast.engine;

import com.bank.vam.forecast.domain.ForecastAdjustment;
import com.bank.vam.forecast.domain.ForecastCategory;
import com.bank.vam.forecast.domain.ForecastLine;
import com.bank.vam.forecast.domain.enums.ForecastSource;
import com.bank.vam.forecast.repository.ForecastCategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * T6 — Manual-overlay engine. Re-emits treasurer adjustments from the most
 * recent prior {@code COMPLETED} run as forecast lines on the new run.
 *
 * <p>This is the carry-forward mechanism — it preserves treasurer intent
 * across re-generations so overlays don't have to be re-keyed every time the
 * orchestrator fires.
 *
 * <p>Routing semantics differ from PATTERN/AGING:
 * <ul>
 *   <li>{@link #supports(ForecastCategory)} returns {@code true} for the
 *       conventional MANUAL-default categories (CAPEX / FINANCING /
 *       MANUAL_OTHER) so the orchestrator includes those in this engine's
 *       per-engine context.</li>
 *   <li>{@link #generate(ForecastContext)} ignores {@code ctx.categories()}
 *       and instead iterates {@code ctx.carryForward()} — adjustments can
 *       have been entered against ANY category, and we re-emit them
 *       verbatim. The category embedded on the adjustment is reused.</li>
 * </ul>
 *
 * <p>Each emitted line is tagged {@link ForecastSource#MANUAL} and carries a
 * {@code sourceRef} back to the originating adjustment id so a downstream
 * roll-up can deduplicate or audit-trail.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ManualOverlayEngine implements ForecastEngine {

    private final ForecastCategoryRepository categoryRepository;

    @Override
    public ForecastSource type() {
        return ForecastSource.MANUAL;
    }

    @Override
    public boolean supports(ForecastCategory category) {
        return category != null && category.getDefaultEngine() == ForecastSource.MANUAL;
    }

    @Override
    public List<ForecastLine> generate(ForecastContext ctx) {
        List<ForecastAdjustment> carry = ctx.carryForward();
        if (carry == null || carry.isEmpty()) {
            log.debug("ManualOverlayEngine: no carry-forward adjustments for corporate {}", ctx.corporateId());
            return List.of();
        }
        // ForecastAdjustment stores categoryId as a bare UUID; ForecastLine
        // requires a managed ForecastCategory reference. We cache resolved
        // references per categoryId so a run with many adjustments referencing
        // the same category does a single lookup.
        Map<UUID, ForecastCategory> categoryCache = new HashMap<>();
        // Pre-warm with the orchestrator-supplied categories (covers the
        // MANUAL-default categories this engine declared via supports()).
        for (ForecastCategory c : ctx.categories()) {
            categoryCache.put(c.getId(), c);
        }

        List<ForecastLine> out = new ArrayList<>(carry.size());
        int skipped = 0;
        for (ForecastAdjustment adj : carry) {
            // Adjustments outside the new horizon are dropped — they refer to
            // value dates that the new run doesn't cover.
            if (adj.getValueDate().isBefore(ctx.horizonStart())
                || adj.getValueDate().isAfter(ctx.horizonEnd())) {
                skipped++;
                continue;
            }
            ForecastCategory category = categoryCache.computeIfAbsent(
                adj.getCategoryId(),
                id -> categoryRepository.findById(id).orElse(null)
            );
            if (category == null) {
                log.warn("ManualOverlayEngine: adjustment {} references missing category {} — skipping",
                    adj.getId(), adj.getCategoryId());
                skipped++;
                continue;
            }
            out.add(ForecastLine.builder()
                .run(ctx.run())
                .valueDate(adj.getValueDate())
                .entityId(adj.getEntityId())
                .currency(adj.getCurrency())
                .category(category)
                .amountMid(adj.getDeltaAmount())
                .source(ForecastSource.MANUAL)
                .sourceRef("adjustment:" + adj.getId())
                .build());
        }
        log.debug("ManualOverlayEngine emitted {} carry-forward lines ({} dropped as out-of-horizon/missing-cat) for corporate {}",
            out.size(), skipped, ctx.corporateId());
        return out;
    }
}
