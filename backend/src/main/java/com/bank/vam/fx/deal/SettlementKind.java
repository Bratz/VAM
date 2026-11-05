package com.bank.vam.fx.deal;

public enum SettlementKind {
    /** The deal's value date: the remaining legs are exchanged. */
    MATURITY,
    /** A tranche of a window forward the corporate chose to draw. */
    DRAWDOWN,
    /** The cash difference when a deal is closed out or rolled early. */
    CLOSE_OUT
}
