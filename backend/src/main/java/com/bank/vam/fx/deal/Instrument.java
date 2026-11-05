package com.bank.vam.fx.deal;

/** What is being traded. FX swaps and NDFs are not offered yet. */
public enum Instrument {
    SPOT,
    FORWARD,
    /** A forward the client may draw down on any date inside a window (a time-option forward). */
    WINDOW
}
