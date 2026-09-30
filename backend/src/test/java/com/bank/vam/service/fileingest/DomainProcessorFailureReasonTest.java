package com.bank.vam.service.fileingest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A FAILED row's reason is the only account a user gets of why their line did not post, and all
 * four DomainProcessors used to write e.getMessage() into it unfiltered. Two ways that produced
 * something useless: a null message (blank cell), and a wrapper whose own message hides the cause.
 */
class DomainProcessorFailureReasonTest {

    @Test
    void fallsBackToTheClassNameWhenThereIsNoMessage() {
        assertEquals("NullPointerException",
                DomainProcessor.failureReason(new NullPointerException()));
    }

    @Test
    void fallsBackToTheClassNameWhenTheMessageIsBlank() {
        assertEquals("IllegalStateException",
                DomainProcessor.failureReason(new IllegalStateException("   ")));
    }

    @Test
    void keepsAPlainMessageAsIs() {
        assertEquals("Creditor account not found",
                DomainProcessor.failureReason(new IllegalArgumentException("Creditor account not found")));
    }

    @Test
    void appendsTheCauseWhenAWrapperHidesIt() {
        Exception wrapped = new IllegalStateException("Posting failed",
                new IllegalArgumentException("VIBAN AE07 0331 not found"));
        String reason = DomainProcessor.failureReason(wrapped);
        assertTrue(reason.contains("Posting failed"), reason);
        assertTrue(reason.contains("VIBAN AE07 0331 not found"), reason);
    }

    @Test
    void doesNotRepeatACauseAlreadyQuotedInTheMessage() {
        Exception wrapped = new IllegalStateException("VIBAN not found",
                new IllegalArgumentException("VIBAN not found"));
        assertEquals("VIBAN not found", DomainProcessor.failureReason(wrapped));
    }

    @Test
    void namesTheClassAndStillSurfacesTheCauseWhenTheWrapperHasNoMessage() {
        Exception wrapped = new IllegalStateException(new IllegalArgumentException("Currency mismatch"));
        String reason = DomainProcessor.failureReason(wrapped);
        assertTrue(reason.contains("Currency mismatch"), reason);
    }
}
