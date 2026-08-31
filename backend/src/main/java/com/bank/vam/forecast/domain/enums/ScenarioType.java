package com.bank.vam.forecast.domain.enums;

/**
 * Kind of {@code ForecastScenario} attached to a run.
 */
public enum ScenarioType {
    /** Baseline scenario — outputs the engines' as-is forecast. */
    BASE,
    /** Optimistic overlay. */
    UPSIDE,
    /** Pessimistic / stress overlay. */
    DOWNSIDE,
    /** User-defined what-if (rules applied via {@code rules} JSON). */
    WHATIF
}
