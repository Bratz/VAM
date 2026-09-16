package com.bank.vam.fileingest.entity;

/** Which existing backend domain an upload is destined for — see the design
 * doc's "Per-domain processing" section for how each one differs. */
public enum IngestDomain {
    RECEIVABLES,
    PAYABLES,
    PAYMENTS
}
