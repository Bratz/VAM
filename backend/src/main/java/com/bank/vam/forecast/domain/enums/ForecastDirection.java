package com.bank.vam.forecast.domain.enums;

/**
 * Cash-flow direction of a {@code ForecastCategory}.
 */
public enum ForecastDirection {
    /** Inflow (receivable, deposit, IC settle-in, etc). */
    IN,
    /** Outflow (payable, fee, IC settle-out, etc). */
    OUT
}
