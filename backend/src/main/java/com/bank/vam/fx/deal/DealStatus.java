package com.bank.vam.fx.deal;

import java.util.EnumSet;
import java.util.Set;

/**
 * Life of a booked deal. Spot goes BOOKED, CONFIRMED, PENDING_SETTLEMENT; a forward or window goes
 * BOOKED, CONFIRMED, ACTIVE. Maturity, settlement, drawdown, roll and close-out come with the next phase.
 */
public enum DealStatus {
    BOOKED,
    CONFIRMED,
    ACTIVE,
    PENDING_SETTLEMENT;

    /** Where a deal in this status may go next. */
    public Set<DealStatus> next() {
        return switch (this) {
            case BOOKED -> EnumSet.of(CONFIRMED);
            case CONFIRMED -> EnumSet.of(ACTIVE, PENDING_SETTLEMENT);
            case ACTIVE, PENDING_SETTLEMENT -> EnumSet.noneOf(DealStatus.class);
        };
    }

    /** Open deals count against the credit limit and as hedges. */
    public static final Set<DealStatus> OPEN = EnumSet.allOf(DealStatus.class);
}
