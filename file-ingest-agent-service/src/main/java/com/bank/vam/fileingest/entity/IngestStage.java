package com.bank.vam.fileingest.entity;

/** One value per pipeline stage in the design doc's stage table. Terminal
 * states are DONE and BLOCKED; every other value is a step on the way. */
public enum IngestStage {
    RECEIVED,
    ANALYZED,
    SIGNATURE_MATCHED,
    SIGNATURE_NEW,
    CODING_AGENT_RUNNING,
    TEST_GATE,
    STAGED,
    PROCESSING,
    DONE,
    BLOCKED
}
