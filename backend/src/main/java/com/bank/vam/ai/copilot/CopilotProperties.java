package com.bank.vam.ai.copilot;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Treasury Copilot feature configuration. Bound from {@code vam.ai.copilot.*}.
 *
 * <p>This prototype is <b>stub-only</b> — there is no LLM connection. The
 * {@code IntentRouter} (added in P3) handles intent matching, and the response
 * is composed deterministically from templates. These properties exist to
 * make the feature flag-able and to size the streaming illusion.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "vam.ai.copilot")
public class CopilotProperties {

    /** Master kill-switch. When false, controller responds 503. */
    private boolean enabled = true;

    /**
     * Delay between SSE token chunks in milliseconds. The composed response
     * is fully ready before streaming begins; this delay just creates the
     * "live typing" illusion. 30ms feels alive, 50ms feels deliberate.
     */
    private int streamTokenDelayMs = 30;

    /**
     * How long an unconfirmed action proposal remains valid before it expires.
     * After expiry the action card cannot be confirmed and the user must
     * re-issue the request.
     */
    private int actionProposalTtlMinutes = 10;

    private final Conversation conversation = new Conversation();

    @Getter
    @Setter
    public static class Conversation {
        /**
         * Days to retain conversations. {@code 0} = keep forever (prototype default).
         * A cleanup job will be added before promoting to v1.
         */
        private int retentionDays = 0;

        /**
         * How many prior messages to include when building model context.
         * Not used by the stub (each turn is independent), but reserved for
         * the future LLM swap.
         */
        private int messageHistoryWindow = 20;
    }
}
