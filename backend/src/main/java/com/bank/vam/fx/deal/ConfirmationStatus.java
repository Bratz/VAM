package com.bank.vam.fx.deal;

/**
 * The bank back office sends a confirmation after every trade; the corporate checks it against what it
 * agreed and either affirms or disputes it. Until it is affirmed the deal cannot settle.
 */
public enum ConfirmationStatus {
    /** Sent to the corporate, waiting for it to check and answer. */
    ISSUED,
    AFFIRMED,
    /** The corporate says the confirmation does not match the trade; the bank back office resolves it. */
    DISPUTED
}
