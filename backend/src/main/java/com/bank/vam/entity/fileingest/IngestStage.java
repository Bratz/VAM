package com.bank.vam.entity.fileingest;

/**
 * One value per pipeline stage. Written by two different processes sharing
 * this one row (see tasks/file-ingest-pipeline-design.md's "Revised
 * architecture"): this app writes RECEIVED/AWAITING_TRANSFORM/STAGED/
 * PROCESSING/DONE; the separate agent worker writes ANALYZED/SIGNATURE_NEW/
 * SIGNATURE_MATCHED/CODING_AGENT_RUNNING/TEST_GATE once it picks up a ticket.
 * Terminal states are DONE and BLOCKED.
 */
public enum IngestStage {
    RECEIVED,
    ANALYZED,
    SIGNATURE_MATCHED,
    SIGNATURE_NEW,
    CODING_AGENT_RUNNING,
    TEST_GATE,
    /** Ticket filed for an unrecognized format; waiting for the agent worker
     * to set format_signature_id (and that signature's transform_ref) so
     * IngestRetrySweepService can resume this job. */
    AWAITING_TRANSFORM,
    STAGED,
    PROCESSING,
    DONE,
    BLOCKED
}
