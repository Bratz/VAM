package com.bank.vam.fx.deal;

/**
 * Life of a quote request. The simulated dealer prices instantly, so REQUESTED is only ever seen in
 * the event log; a human dealer would leave a request in it until priced.
 */
public enum QuoteStatus {
    REQUESTED,
    QUOTED,
    ACCEPTED,
    /** The validity timer ran out before the client accepted. */
    EXPIRED,
    /** The dealer declined to quote, for example because the corporate is out of limit. */
    REJECTED,
    CANCELLED
}
