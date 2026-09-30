package com.bank.vam.service.fileingest;

import java.util.List;
import java.util.UUID;

/** What every domain's live-processing stage looks like: take the rows already staged and
 * reconciled, post each READY one to the real domain service, and mark it PROCESSED or FAILED
 * without blocking its siblings. */
public interface DomainProcessor {

    void process(UUID ingestJobId, List<TransformedRow> rows);

    /**
     * The row's {@code reason} is the only account a user ever gets of why their line failed, so it
     * must not come back empty. {@code e.getMessage()} is null for a whole family of exceptions --
     * NullPointerException being the obvious one -- and all four processors used to write it
     * straight into the column, turning those into a FAILED row with a blank explanation.
     * Falls back to the class name, and appends the cause when the message came from a wrapper.
     */
    static String failureReason(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        Throwable cause = e.getCause();
        if (cause != null && cause.getMessage() != null && !message.contains(cause.getMessage())) {
            message = message + " (caused by: " + cause.getMessage() + ")";
        }
        return message;
    }
}
