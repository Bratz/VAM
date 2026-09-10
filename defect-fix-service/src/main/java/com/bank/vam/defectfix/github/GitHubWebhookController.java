package com.bank.vam.defectfix.github;

import com.bank.vam.defectfix.config.PipelineProperties;
import com.bank.vam.defectfix.detect.DefectDetectionService;
import com.bank.vam.defectfix.detect.DefectDetectionService.Stack;
import com.bank.vam.defectfix.jira.JiraClient;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
public class GitHubWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookController.class);

    // Matches the git identity the coding agent commits under (see defect-fix-service/Dockerfile's
    // `git config --system user.email`). Loop-prevention guard: the bot pushes its fix straight
    // back onto the PR branch, which re-triggers pull_request CI, which re-fires this webhook.
    // Dedup (JiraClient.existsWithLabel) stops the SAME defect being re-ticketed, but if a fix
    // ever incidentally introduces a different new warning, that's a genuinely new signature and
    // dedup wouldn't catch it — every CI-autofix reference implementation checked (Claude Code's
    // own GitHub Actions guide, OpenAI's Codex autofix cookbook) calls this out as a required
    // guard, not an edge case.
    private static final String BOT_COMMIT_EMAIL = "defect-fix-bot@vam-portal.local";

    private final PipelineProperties.GitHub config;
    private final DefectDetectionService detectionService;
    private final JiraClient jiraClient;
    private final Pattern issueKeyPattern;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GitHubWebhookController(PipelineProperties properties, DefectDetectionService detectionService,
                                    JiraClient jiraClient) {
        this.config = properties.github();
        this.detectionService = detectionService;
        this.jiraClient = jiraClient;
        this.issueKeyPattern = Pattern.compile("\\b" + Pattern.quote(properties.jira().projectKey()) + "-\\d+\\b");
    }

    @PostMapping("/webhooks/github")
    public ResponseEntity<String> handle(@RequestBody byte[] rawBody,
                                          @RequestHeader("X-Hub-Signature-256") String signatureHeader,
                                          @RequestHeader("X-GitHub-Event") String eventType) {
        if (!isValidSignature(rawBody, signatureHeader)) {
            log.warn("Rejected GitHub webhook: bad signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("invalid JSON");
        }

        if ("pull_request".equals(eventType)) {
            return handlePullRequestEvent(payload);
        }
        if (!"workflow_run".equals(eventType)) {
            return ResponseEntity.ok("ignored: not workflow_run or pull_request");
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
        String commitAuthorEmail = run.path("head_commit").path("author").path("email").asText();
        if (BOT_COMMIT_EMAIL.equals(commitAuthorEmail)) {
            return ResponseEntity.ok("ignored: bot's own commit (loop-prevention guard)");
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
        String headBranch = run.path("head_branch").asText();
        int prNumber = pr.path("number").asInt();

        try {
            detectionService.handleFailedPrRun(stack, baseSha, headSha, headBranch, prNumber);
        } catch (Exception e) {
            log.error("Failed to process workflow_run for PR #{}", prNumber, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("processing failed");
        }
        return ResponseEntity.ok("processed");
    }

    /**
     * Closes the loop the coding-agent path leaves open: on success it only ever gets a ticket to
     * "In Review" (see TriageOrchestrator.onSuccess) and waits for a human to merge the PR. Reads
     * the "Resolves KEY." footer GitHubPullRequestClient.ensureIssueLinked stamped onto the PR body
     * back out and transitions that ticket to Done — no Jira search needed. Requires the webhook's
     * subscribed events to include "Pull requests", not just "Workflow runs".
     */
    private ResponseEntity<String> handlePullRequestEvent(JsonNode payload) {
        if (!"closed".equals(payload.path("action").asText())) {
            return ResponseEntity.ok("ignored: not closed");
        }
        JsonNode pr = payload.path("pull_request");
        if (!pr.path("merged").asBoolean(false)) {
            return ResponseEntity.ok("ignored: closed without merging");
        }
        Matcher matcher = issueKeyPattern.matcher(pr.path("body").asText(""));
        if (!matcher.find()) {
            return ResponseEntity.ok("ignored: no linked ticket in PR body");
        }
        String issueKey = matcher.group();
        try {
            jiraClient.transitionTo(issueKey, "Done");
            log.info("PR #{} merged — transitioned {} to Done", pr.path("number").asInt(), issueKey);
        } catch (Exception e) {
            log.error("Failed to close {} after PR merge", issueKey, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("transition failed");
        }
        return ResponseEntity.ok("closed " + issueKey);
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
