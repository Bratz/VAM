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

    /** Inverse of the "source|key" form embedded in a ticket's description (see JiraTicketService). */
    public static DefectSignature parse(String sourcePipeKey) {
        int separator = sourcePipeKey.indexOf('|');
        if (separator < 0) {
            throw new IllegalArgumentException("Not a \"source|key\" defect signature: " + sourcePipeKey);
        }
        return new DefectSignature(sourcePipeKey.substring(0, separator), sourcePipeKey.substring(separator + 1));
    }
}
