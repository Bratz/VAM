package com.bank.vam.forecast.engine;

import com.bank.vam.forecast.domain.ForecastAdjustment;
import com.bank.vam.forecast.domain.ForecastCategory;
import com.bank.vam.forecast.domain.ForecastRun;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Inputs to a forecast run, passed verbatim to every {@link ForecastEngine}.
 *
 * <p>Engines query the underlying domain tables filtered by {@code entityIds}
 * and the inclusive {@code [horizonStart, horizonEnd]} window. The
 * {@code runDate} anchor lets engines compute DSO/DPO look-backs
 * deterministically — production callers pass {@code LocalDate.now()};
 * tests inject a fixed value.
 *
 * <p>Sprint 1 T7 widened this context (additively) with:
 * <ul>
 *   <li>{@code run} — the persisted {@link ForecastRun} this generation
 *       belongs to. Engines attach it to every emitted {@link
 *       com.bank.vam.forecast.domain.ForecastLine} via {@code setRun(...)}.</li>
 *   <li>{@code categories} — the per-engine category subset (the orchestrator
 *       pre-filters via {@link ForecastEngine#supports(ForecastCategory)} so
 *       engines do NOT need to re-filter). Engines look up categories by
 *       {@code code} from this list to set {@code line.category}.</li>
 *   <li>{@code carryForward} — adjustments from the most-recent prior
 *       {@code COMPLETED} run for this corporate. Empty on first run.
 *       The {@code ManualOverlayEngine} re-emits these verbatim as lines
 *       on the new run; other engines ignore the field.</li>
 * </ul>
 *
 * <p>This is a Java record — immutable by construction. Any future enrichment
 * (scenario rules, base currency, etc.) is additive only — never renames.
 */
public record ForecastContext(
        ForecastRun run,
        UUID corporateId,
        Set<UUID> entityIds,
        List<ForecastCategory> categories,
        List<ForecastAdjustment> carryForward,
        LocalDate horizonStart,
        LocalDate horizonEnd,
        LocalDate runDate
) {
}
