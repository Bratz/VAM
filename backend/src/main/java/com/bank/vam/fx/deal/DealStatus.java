package com.bank.vam.fx.deal;

import java.util.EnumSet;
import java.util.Set;

/**
 * Life of a booked deal. BOOKED is momentary: a spot moves straight to PENDING_SETTLEMENT and a forward or
 * window to ACTIVE. Whether the corporate has confirmed the trade is a separate fact (see
 * {@link ConfirmationStatus}), because a deal is live from execution and the confirmation is a control on it.
 */
public enum DealStatus {
    BOOKED,
    ACTIVE,
    PENDING_SETTLEMENT,
    /** A window forward with at least one drawdown taken. */
    PARTIALLY_DRAWN,
    /** Settlement was tried on the value date and could not be completed; it is retried. */
    SETTLEMENT_FAILED,
    SETTLED,
    CLOSED_OUT,
    ROLLED,
    /** Cancelled by the bank after a dispute. */
    CANCELLED;

    /** Where a deal in this status may go next. */
    public Set<DealStatus> next() {
        return switch (this) {
            case BOOKED -> EnumSet.of(ACTIVE, PENDING_SETTLEMENT);
            case ACTIVE -> EnumSet.of(PARTIALLY_DRAWN, SETTLED, SETTLEMENT_FAILED, CLOSED_OUT, ROLLED, CANCELLED);
            case PARTIALLY_DRAWN -> EnumSet.of(SETTLED, SETTLEMENT_FAILED, CLOSED_OUT, ROLLED);
            case PENDING_SETTLEMENT -> EnumSet.of(SETTLED, SETTLEMENT_FAILED, CANCELLED);
            case SETTLEMENT_FAILED -> EnumSet.of(SETTLED, CLOSED_OUT, ROLLED);
            case SETTLED, CLOSED_OUT, ROLLED, CANCELLED -> EnumSet.noneOf(DealStatus.class);
        };
    }

    /** Open deals count against the credit limit and as hedges. */
    public static final Set<DealStatus> OPEN =
            EnumSet.of(BOOKED, ACTIVE, PENDING_SETTLEMENT, PARTIALLY_DRAWN, SETTLEMENT_FAILED);
}
