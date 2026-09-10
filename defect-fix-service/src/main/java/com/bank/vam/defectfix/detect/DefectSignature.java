package com.bank.vam.defectfix.detect;

/**
 * Identity of a defect for dedup/diffing purposes only (does NOT include the
 * human-readable message, which can shift slightly run-to-run for the same
 * underlying issue). Two runs "have the same defect" iff source+key match.
 */
public record DefectSignature(String source, String key) {

    /** Short, Jira-label-safe form: "sig:<hash>". Labels are alphanumeric + a few symbols. */
    public String asLabel() {
        return "sig:" + Integer.toHexString((source + ":" + key).hashCode());
    }
}
