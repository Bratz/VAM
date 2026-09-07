package com.bank.vam.entity.treasury;

/**
 * Lifecycle state of a {@link SweepRun}. Mirrors
 * {@code com.bank.vam.forecast.domain.enums.RunStatus} — same 3-state shape,
 * kept as a separate type so the treasury/sweep bounded context doesn't
 * depend on the forecast module.
 */
public enum SweepRunStatus {
    /** Sources are being processed; run is not yet finished. */
    RUNNING,
    /** All sources processed; counts are final. */
    COMPLETED,
    /** Run aborted before completion (unexpected exception outside the
     *  per-source safety net). */
    FAILED
}
