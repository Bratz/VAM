package com.bank.vam.forecast.domain.enums;

/**
 * Engine / origin that produced a {@code ForecastLine}.
 */
public enum ForecastSource {
    /** Statistical pattern engine (weekly/monthly seasonality, day-of-week). */
    PATTERN,
    /** Aging-bucket projection from receivables/payables. */
    AGING,
    /** Machine-learning model output. */
    ML,
    /** Driver-based / what-if calculated line. */
    DRIVER,
    /** Manually entered by a user. */
    MANUAL
}
