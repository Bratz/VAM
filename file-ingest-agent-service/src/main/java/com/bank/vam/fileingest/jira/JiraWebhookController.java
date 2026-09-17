package com.bank.vam.fileingest.jira;

import com.bank.vam.fileingest.config.IngestProperties;
import com.bank.vam.fileingest.orchestrate.IngestTriageOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives Jira Cloud's own "Issue Created" webhook (configured in Jira's admin console —
 * Settings > System > WebHooks, filtered to this project's file-ingest-pipeline label) so a
 * ticket filed while this service is already running gets picked up immediately instead of
 * waiting for {@link IngestTriageOrchestrator}'s own poll (see its class javadoc's "Corrected
 * design note" — the poll stays as the safety net if this webhook is ever misconfigured, removed,
 * or a delivery is dropped).
 *
 * <p>Unlike {@code GitHubWebhookController} in defect-fix-service, the payload is never parsed —
 * this is purely a wake-up ping, since {@link IngestTriageOrchestrator#tryStartProcessing()}
 * re-reads the actual ticket from Jira itself regardless of what triggered it. Jira Cloud's
 * classic WebHooks feature also has no HMAC signing like GitHub's, so the shared secret is a
 * query param embedded directly in the webhook URL configured in Jira instead of a signed header.
 */
@RestController
public class JiraWebhookController {

    private static final Logger log = LoggerFactory.getLogger(JiraWebhookController.class);

    private final String expectedToken;
    private final IngestTriageOrchestrator orchestrator;

    public JiraWebhookController(IngestProperties properties, IngestTriageOrchestrator orchestrator) {
        this.expectedToken = properties.jira().webhookSecret();
        this.orchestrator = orchestrator;
    }

    @PostMapping("/webhooks/jira")
    public ResponseEntity<String> handle(@RequestParam(value = "token", required = false) String token) {
        if (expectedToken == null || expectedToken.isBlank() || !expectedToken.equals(token)) {
            log.warn("Rejected Jira webhook: missing or bad token");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        orchestrator.tryStartProcessing();
        return ResponseEntity.ok("triggered");
    }
}
