package com.bank.vam.entity.fileingest;

/** Per-row outcome inside a staged upload — the quarantine mechanism: one
 * bad row never blocks its siblings. */
public enum RowStatus {
    STAGED,
    READY,
    QUARANTINED,
    PROCESSED,
    /** A payment in another currency the FX deals could not cover; paid once treasury books FX and releases it. */
    AWAITING_FX,
    FAILED
}
