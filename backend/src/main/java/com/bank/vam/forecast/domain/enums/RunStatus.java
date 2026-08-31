package com.bank.vam.forecast.domain.enums;

/**
 * Lifecycle state of a {@code ForecastRun}.
 */
public enum RunStatus {
    /** Engines are generating lines; run is not yet readable. */
    RUNNING,
    /** Generation finished cleanly; lines are queryable. */
    COMPLETED,
    /** Generation aborted; partial / no lines. */
    FAILED
}
