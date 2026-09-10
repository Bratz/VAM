package com.bank.vam.defectfix.github;

import com.bank.vam.defectfix.config.PipelineProperties;
import com.bank.vam.defectfix.detect.DefectDetectionService;
import com.bank.vam.defectfix.detect.DefectDetectionService.Stack;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

@RestController
public class GitHubWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookController.class);

    private final PipelineProperties.GitHub config;
    private final DefectDetectionService detectionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GitHubWebhookController(PipelineProperties properties, DefectDetectionService detectionService) {
        this.config = properties.github();
        this.detectionService = detectionService;
    }

    @PostMapping("/webhooks/github")
    public ResponseEntity<String> handle(@RequestBody byte[] rawBody,
                                          @RequestHeader("X-Hub-Signature-256") String signatureHeader,
                                          @RequestHeader("X-GitHub-Event") String eventType) {
        if (!isValidSignature(rawBody, signatureHeader)) {
            log.warn("Rejected GitHub webhook: bad signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!"workflow_run".equals(eventType)) {
            return ResponseEntity.ok("ignored: not workflow_run");
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("invalid JSON");
        }

        JsonNode run = payload.path("workflow_run");
        if (!"completed".equals(payload.path("action").asText())) {
            return ResponseEntity.ok("ignored: not completed");
        }
        if (!"failure".equals(run.path("conclusion").asText())) {
            return ResponseEntity.ok("ignored: not a failure");
        }
        if (!"pull_request".equals(run.path("event").asText())) {
            return ResponseEntity.ok("ignored: not PR-triggered (push runs only seed baseline artifacts)");
        }

        Stack stack = switch (run.path("name").asText()) {
            case "Frontend CI" -> Stack.FRONTEND;
            case "Backend CI" -> Stack.BACKEND;
            default -> null;
        };
        if (stack == null) {
            return ResponseEntity.ok("ignored: unrecognized workflow name " + run.path("name").asText());
        }

        JsonNode pr = run.path("pull_requests").isEmpty() ? null : run.path("pull_requests").get(0);
        if (pr == null) {
            // ponytail: happens for fork-sourced PRs, where GitHub doesn't populate pull_requests on
            // workflow_run. Not expected for this same-repo-only pipeline; upgrade path if it ever is:
            // resolve the PR via the search API using head_sha instead.
            log.warn("workflow_run had no pull_requests entry — skipping (head_sha={})", run.path("head_sha").asText());
            return ResponseEntity.ok("ignored: no PR context");
        }

        String baseSha = pr.path("base").path("sha").asText();
        String headSha = run.path("head_sha").asText();
        int prNumber = pr.path("number").asInt();

        try {
            detectionService.handleFailedPrRun(stack, baseSha, headSha, prNumber);
        } catch (Exception e) {
            log.error("Failed to process workflow_run for PR #{}", prNumber, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("processing failed");
        }
        return ResponseEntity.ok("processed");
    }

    private boolean isValidSignature(byte[] body, String signatureHeader) {
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(config.webhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String computed = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
            return constantTimeEquals(computed, signatureHeader);
        } catch (Exception e) {
            log.error("Signature verification error", e);
            return false;
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
