package com.bank.vam.entity.fileingest;

/** Per-row outcome inside a staged upload — the quarantine mechanism: one
 * bad row never blocks its siblings. */
public enum RowStatus {
    STAGED,
    READY,
    QUARANTINED,
    PROCESSED,
    FAILED
}
