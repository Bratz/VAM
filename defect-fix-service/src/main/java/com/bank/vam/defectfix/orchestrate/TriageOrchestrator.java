package com.bank.vam.defectfix.orchestrate;

import com.bank.vam.defectfix.agent.CodingAgentClient;
import com.bank.vam.defectfix.agent.CodingAgentClient.AgentRunResult;
import com.bank.vam.defectfix.config.PipelineProperties;
import com.bank.vam.defectfix.detect.DefectDetectionService.Stack;
import com.bank.vam.defectfix.detect.DefectSignature;
import com.bank.vam.defectfix.gate.TestGateRunner;
import com.bank.vam.defectfix.gate.TestGateRunner.GateResult;
import com.bank.vam.defectfix.git.GitWorktreeManager;
import com.bank.vam.defectfix.github.GitHubPullRequestClient;
import com.bank.vam.defectfix.jira.JiraClient;
import com.bank.vam.defectfix.jira.JiraClient.Issue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single-instance, single-concurrency worker: picks the oldest open ticket,
 * runs it through the coding agent + test gate loop, and either opens a PR
 * or escalates to a human, then moves on to the next ticket until the
 * backlog is empty.
 *
 * ponytail: the concurrency-1 gate is an in-process AtomicBoolean, not a
 * distributed lock — correct as long as this service runs as a single
 * instance (the design calls for exactly one container, not a scaled-out
 * deployment). Upgrade path if that ever changes: move the gate into Jira
 * itself (atomic transition attempt as the lock) or a real distributed lock.
 */
@Service
public class TriageOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TriageOrchestrator.class);

    private final JiraClient jiraClient;
    private final GitWorktreeManager worktreeManager;
    private final CodingAgentClient codingAgentClient;
    private final TestGateRunner testGateRunner;
    private final GitHubPullRequestClient pullRequestClient;
    private final int maxRetries;
    private final Path trajectoriesDir;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicBoolean busy = new AtomicBoolean(false);

    public TriageOrchestrator(JiraClient jiraClient,
                               GitWorktreeManager worktreeManager,
                               CodingAgentClient codingAgentClient,
                               TestGateRunner testGateRunner,
                               GitHubPullRequestClient pullRequestClient,
                               PipelineProperties properties) {
        this.jiraClient = jiraClient;
        this.worktreeManager = worktreeManager;
        this.codingAgentClient = codingAgentClient;
        this.testGateRunner = testGateRunner;
        this.pullRequestClient = pullRequestClient;
        this.maxRetries = properties.agent().maxRetries();
        this.trajectoriesDir = Path.of(properties.agent().workspaceDir()).resolve("trajectories");
    }

    /**
     * A ticket left sitting in To Do (service restarted mid-backlog, or a manual Jira edit) won't
     * otherwise get picked up again until the next NEW defect is detected — check once on every
     * boot so a redeploy naturally re-kicks the queue instead of leaving it stalled.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        tryStartProcessing();
    }

    /**
     * Call after filing a new ticket (or on any other signal that the backlog might have work).
     * No-op if already busy — the busy worker will pick up the new ticket itself once it's free.
     * Runs async so the caller (the GitHub webhook handler, or the startup listener above) isn't
     * blocked for the ticket's whole fix-and-test cycle, which can run long.
     */
    @Async
    public void tryStartProcessing() {
        if (!busy.compareAndSet(false, true)) {
            log.debug("Already processing a ticket — new work will be picked up when it finishes");
            return;
        }
        try {
            Issue next;
            while ((next = jiraClient.findOldestOpenTicket().orElse(null)) != null) {
                processTicket(next);
            }
        } finally {
            busy.set(false);
        }
    }

    private void processTicket(Issue issue) {
        log.info("Starting {}: {}", issue.key(), issue.summary());
        Stack stack = "stack-backend".equals(issue.stackLabel()) ? Stack.BACKEND : Stack.FRONTEND;
        String taskBrief = "Fix the following defect in vam-portal:\n\n" + issue.summary() + "\n\n" + issue.description();
        String startedAt = Instant.now().toString();
        List<TicketTrajectory.AttemptRecord> attemptRecords = new ArrayList<>();
        DefectSignature targetDefect = null;
        String sourceBranch = null;

        try {
            // Both throw (caught below, escalating with a clear message instead of getting stuck
            // retrying the same ticket forever) for any ticket filed before signature/branch
            // tracking was added — there's nothing to safely act on for those.
            targetDefect = extractMarker(issue.description(), SIGNATURE_MARKER, DefectSignature::parse);
            sourceBranch = extractMarker(issue.description(), SOURCE_BRANCH_MARKER, s -> s);

            jiraClient.transitionTo(issue.key(), "In Progress");
            worktreeManager.ensureBaseRepoReady();
            Path workDir = worktreeManager.createWorktree(sourceBranch);
            testGateRunner.prepare(stack, workDir);

            GateResult lastResult = null;
            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                String prompt = attempt == 0 ? taskBrief
                        : taskBrief + "\n\nYour previous attempt did not pass the test gate. Output:\n" + lastResult.output();
                AgentRunResult agentResult = codingAgentClient.runFix(workDir, prompt);
                lastResult = testGateRunner.runGate(stack, workDir, targetDefect);
                attemptRecords.add(new TicketTrajectory.AttemptRecord(attempt + 1, agentResult.stoppedNaturally(),
                        agentResult.finalMessage(), agentResult.trajectory(), lastResult.passed(), lastResult.output()));
                if (lastResult.passed()) {
                    String prUrl = onSuccess(issue, workDir, sourceBranch);
                    writeTrajectory(issue, stack, targetDefect, sourceBranch, startedAt, attemptRecords,
                            prUrl != null ? "success" : "no-changes", prUrl, null);
                    return;
                }
                log.info("{} attempt {} failed the test gate", issue.key(), attempt + 1);
            }
            onExhausted(issue, workDir, lastResult);
            writeTrajectory(issue, stack, targetDefect, sourceBranch, startedAt, attemptRecords, "exhausted", null, null);
        } catch (Exception e) {
            log.error("Unexpected failure processing {}", issue.key(), e);
            jiraClient.addComment(issue.key(), "Pipeline error while processing this ticket: " + e.getMessage());
            jiraClient.addLabel(issue.key(), "needs-human");
            safeTransition(issue.key(), "Blocked");
            writeTrajectory(issue, stack, targetDefect, sourceBranch, startedAt, attemptRecords, "error", null, e.getMessage());
        }
    }

    /** @return the linked PR's URL, or null if the agent made no changes (a different terminal state, not a real success). */
    private String onSuccess(Issue issue, Path workDir, String branch) throws Exception {
        boolean pushed = worktreeManager.commitAndPush(workDir, branch, "Fix " + issue.key() + ": " + issue.summary());
        if (!pushed) {
            jiraClient.addComment(issue.key(), "Test gate passed but the coding agent made no file changes — nothing to push. Needs human review.");
            jiraClient.addLabel(issue.key(), "needs-human");
            safeTransition(issue.key(), "Blocked");
            return null;
        }
        // The fix was pushed straight back onto the PR's own branch (see createWorktree), so its
        // existing open PR is the one to link — not a new one. Falls back to creating a fresh PR
        // only if that original one is somehow gone (closed/branch deleted) by the time this runs.
        String prUrl = pullRequestClient.findExistingPullRequestUrl(branch)
                .orElseGet(() -> pullRequestClient.createPullRequest(branch, "main",
                        "Fix " + issue.key() + ": " + issue.summary(),
                        "Resolves " + issue.key() + ".\n\n" + issue.summary()));
        // The reused-existing-PR path (the common case) never sets a body linking the ticket —
        // this is what the pull_request-merged webhook reads back out to auto-close the ticket.
        pullRequestClient.ensureIssueLinked(branch, issue.key());
        jiraClient.addComment(issue.key(), "Fix verified and pushed: " + prUrl);
        jiraClient.transitionTo(issue.key(), "In Review");
        worktreeManager.removeWorktree(workDir);
        return prUrl;
    }

    /** Writes the full run as a structured JSON artifact (SWE-agent/OpenHands-style trajectory), not just log lines. */
    private void writeTrajectory(Issue issue, Stack stack, DefectSignature targetDefect, String sourceBranch,
                                  String startedAt, List<TicketTrajectory.AttemptRecord> attempts,
                                  String outcome, String prUrl, String errorMessage) {
        TicketTrajectory trajectory = new TicketTrajectory(
                issue.key(), stack.name(),
                targetDefect == null ? null : targetDefect.source() + "|" + targetDefect.key(),
                sourceBranch, startedAt, attempts, outcome, prUrl, errorMessage, Instant.now().toString());
        try {
            Files.createDirectories(trajectoriesDir);
            Path file = trajectoriesDir.resolve(issue.key() + "-" + System.currentTimeMillis() + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), trajectory);
            log.info("Wrote trajectory for {} to {}", issue.key(), file);
        } catch (IOException e) {
            log.warn("Failed to write trajectory for {}", issue.key(), e);
        }
    }

    private void onExhausted(Issue issue, Path workDir, GateResult lastResult) {
        jiraClient.addComment(issue.key(), "Exhausted " + (maxRetries + 1) + " attempt(s). Last test gate output:\n" + lastResult.output());
        jiraClient.addLabel(issue.key(), "needs-human");
        safeTransition(issue.key(), "Blocked");
        // Deliberately NOT removing the worktree here — left at workDir for a human to inspect
        // the agent's last (failed) attempt. Cleaned up when the ticket is eventually closed.
        log.info("{} escalated to a human — worktree left at {}", issue.key(), workDir);
    }

    private void safeTransition(String issueKey, String status) {
        try {
            jiraClient.transitionTo(issueKey, status);
        } catch (Exception e) {
            log.error("Could not transition {} to {}", issueKey, status, e);
        }
    }

    private static final String SIGNATURE_MARKER = "DEFECT_SIGNATURE: ";
    private static final String SOURCE_BRANCH_MARKER = "SOURCE_BRANCH: ";

    /** Pulls a "MARKER: value" line JiraTicketService embeds in every ticket it files back out. */
    private <T> T extractMarker(String description, String marker, java.util.function.Function<String, T> parse) {
        for (String line : description.split("\\R")) {
            if (line.startsWith(marker)) {
                return parse.apply(line.substring(marker.length()).strip());
            }
        }
        throw new IllegalStateException("No \"" + marker.strip() + "\" line in this ticket's description "
                + "— likely filed before that tracking was added");
    }
}
