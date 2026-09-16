package com.bank.vam.fileingest.entity;

/** One value per pipeline stage in the design doc's stage table. Terminal
 * states are DONE and BLOCKED; every other value is a step on the way.
 *
 * <p>Written by two different processes sharing one row (see
 * backend's own copy of this enum, com.bank.vam.entity.fileingest.IngestStage,
 * and tasks/file-ingest-pipeline-design.md's "Revised architecture"): this
 * service never writes AWAITING_TRANSFORM/STAGED/PROCESSING (backend does),
 * but still needs every value backend can write represented here too —
 * Hibernate has to deserialize whatever's actually in the column regardless
 * of which process wrote it. Confirmed live: a value existing only in
 * backend's copy crashed this service's own startup (IngestTriageOrchestrator
 * .onStartup -> findById on a row already staged in that state) the moment a
 * real ticket reached it — keep the two enums in sync when either changes. */
public enum IngestStage {
    RECEIVED,
    ANALYZED,
    SIGNATURE_MATCHED,
    SIGNATURE_NEW,
    CODING_AGENT_RUNNING,
    TEST_GATE,
    AWAITING_TRANSFORM,
    STAGED,
    PROCESSING,
    DONE,
    BLOCKED
}
