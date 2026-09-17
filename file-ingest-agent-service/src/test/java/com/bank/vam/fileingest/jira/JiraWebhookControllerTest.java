package com.bank.vam.fileingest.jira;

import com.bank.vam.fileingest.config.IngestProperties;
import com.bank.vam.fileingest.orchestrate.IngestTriageOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Regression coverage for the fix that closes the "ticket filed after boot sat there forever"
 * gap: confirmed live that a real upload's ticket never got picked up because
 * IngestTriageOrchestrator only ever reacted to ApplicationReadyEvent. This is the webhook half
 * of the fix (the scheduled poll is the other, tested separately) -- Jira notifies this endpoint
 * the moment a ticket is filed instead of waiting up to a full poll cadence.
 */
class JiraWebhookControllerTest {

    private final IngestTriageOrchestrator orchestrator = mock(IngestTriageOrchestrator.class);

    private JiraWebhookController controllerWithSecret(String secret) {
        IngestProperties properties = new IngestProperties(
                new IngestProperties.Jira("https://example.atlassian.net", "e@example.com", "token", "KAN", secret),
                new IngestProperties.Anthropic("key", "claude-sonnet-5"),
                new IngestProperties.Agent(10, 1),
                "/tmp/workspace");
        return new JiraWebhookController(properties, orchestrator);
    }

    @Test
    void aMatchingTokenTriggersProcessing() {
        ResponseEntity<String> response = controllerWithSecret("s3cret").handle("s3cret");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(orchestrator).tryStartProcessing();
    }

    @Test
    void aWrongTokenIsRejectedWithoutTriggeringAnything() {
        ResponseEntity<String> response = controllerWithSecret("s3cret").handle("guessed");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(orchestrator, never()).tryStartProcessing();
    }

    @Test
    void aBlankConfiguredSecretAlwaysRejects() {
        ResponseEntity<String> response = controllerWithSecret("").handle("");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(orchestrator, never()).tryStartProcessing();
    }
}
