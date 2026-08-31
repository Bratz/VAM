package com.bank.vam.forecast.engine;

import com.bank.vam.forecast.domain.ForecastCategory;
import com.bank.vam.forecast.domain.ForecastLine;
import com.bank.vam.forecast.domain.enums.ForecastSource;

import java.util.List;

/**
 * Contract every forecast engine implements.
 *
 * <p>The orchestrator (T7) discovers all {@link ForecastEngine} beans via
 * Spring, filters them with {@link #supports(ForecastCategory)} against the
 * requested categories, runs them in turn, and persists the concatenated
 * lines under a {@code ForecastRun}.
 *
 * <p>Implementations must:
 * <ul>
 *   <li>page through the underlying domain tables (never load all rows),</li>
 *   <li>emit amounts in the source row's own currency (no FX conversion —
 *       the orchestrator does roll-up),</li>
 *   <li>clip {@link ForecastLine#getValueDate()} to {@code ctx.horizonEnd()}.</li>
 * </ul>
 */
public interface ForecastEngine {

    /**
     * Identifies which engine produced a line — matches
     * {@link ForecastLine#getSource()} on every line this engine emits.
     */
    ForecastSource type();

    /** Whether this engine produces lines for the given category. */
    boolean supports(ForecastCategory category);

    /** Produce all forecast lines for the supported categories within the context's horizon. */
    List<ForecastLine> generate(ForecastContext ctx);
}
