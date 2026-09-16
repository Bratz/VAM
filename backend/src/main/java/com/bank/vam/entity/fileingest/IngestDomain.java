package com.bank.vam.entity.fileingest;

/** Which existing backend domain an upload is destined for — see
 * tasks/file-ingest-pipeline-design.md's "Per-domain processing" section
 * for how each one differs. */
public enum IngestDomain {
    RECEIVABLES,
    PAYABLES,
    PAYMENTS
}
