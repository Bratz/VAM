package com.bank.vam.entity.fileingest;

/** Which existing backend domain an upload is destined for — see
 * tasks/file-ingest-pipeline-design.md's "Per-domain processing" section
 * for how each one differs. */
public enum IngestDomain {
    /** Post money that has already arrived (credits a VA). */
    RECEIVABLES,
    /** Raise a new Receivable/invoice awaiting payment -- the opposite direction from
     * RECEIVABLES: initiates a collection request instead of recording one already collected. */
    RECEIVABLES_INVOICE,
    PAYABLES,
    PAYMENTS,
    /** Collect invoices: each row names an invoice (remittanceInformation) or is raised as one from its debtor,
     * amount and account; the file goes out as one collection run (direct debit / Request to Pay). */
    COLLECTIONS
}
