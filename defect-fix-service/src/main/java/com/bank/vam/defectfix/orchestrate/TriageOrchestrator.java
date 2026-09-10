package com.bank.vam.defectfix.orchestrate;

import com.bank.vam.defectfix.agent.CodingAgentClient;
import com.bank.vam.defectfix.config.PipelineProperties;
import com.bank.vam.defectfix.detect.DefectDetectionService.Stack;
import com.bank.vam.defectfix.detect.DefectSignature;
import com.bank.vam.defectfix.gate.TestGateRunner;
import com.bank.vam.defectfix.gate.TestGateRunner.GateResult;
import com.bank.vam.defectfix.git.GitWorktreeManager;
import com.bank.vam.defectfix.github.GitHubPullRequestClient;
import com.bank.vam.defectfix.jira.JiraClient;
import com.bank.vam.defectfix.jira.JiraClient.Issue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
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
    }

    /**
     * Call after filing a new ticket (or on any other signal that the backlog might have work).
     * No-op if already busy — the busy worker will pick up the new ticket itself once it's free.
     * Runs async so the caller (the GitHub webhook handler) isn't blocked for the ticket's whole
     * fix-and-test cycle, which can run long.
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
        String branch = "fix/" + issue.key().toLowerCase() + "-" + slug(issue.summary());
        String taskBrief = "Fix the following defect in vam-portal:\n\n" + issue.summary() + "\n\n" + issue.description();

        try {
            // Throws (caught below, escalating with a clear message instead of getting stuck
            // retrying the same ticket forever) for any ticket filed before this signature-
            // tracking fix landed — there's nothing to safely gate on for those.
            DefectSignature targetDefect = extractSignature(issue.description());
            jiraClient.transitionTo(issue.key(), "In Progress");
            worktreeManager.ensureBaseRepoReady();
            Path workDir = worktreeManager.createWorktree(branch);
            testGateRunner.prepare(stack, workDir);

            GateResult lastResult = null;
            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                String prompt = attempt == 0 ? taskBrief
                        : taskBrief + "\n\nYour previous attempt did not pass the test gate. Output:\n" + lastResult.output();
                codingAgentClient.runFix(workDir, prompt);
                lastResult = testGateRunner.runGate(stack, workDir, targetDefect);
                if (lastResult.passed()) {
                    onSuccess(issue, workDir, branch);
                    return;
                }
                log.info("{} attempt {} failed the test gate", issue.key(), attempt + 1);
            }
            onExhausted(issue, workDir, branch, lastResult);
        } catch (Exception e) {
            log.error("Unexpected failure processing {}", issue.key(), e);
            jiraClient.addComment(issue.key(), "Pipeline error while processing this ticket: " + e.getMessage());
            jiraClient.addLabel(issue.key(), "needs-human");
            safeTransition(issue.key(), "Blocked");
        }
    }

    private void onSuccess(Issue issue, Path workDir, String branch) throws Exception {
        boolean pushed = worktreeManager.commitAndPush(workDir, branch, "Fix " + issue.key() + ": " + issue.summary());
        if (!pushed) {
            jiraClient.addComment(issue.key(), "Test gate passed but the coding agent made no file changes — nothing to open a PR for. Needs human review.");
            jiraClient.addLabel(issue.key(), "needs-human");
            safeTransition(issue.key(), "Blocked");
            return;
        }
        String prUrl = pullRequestClient.createPullRequest(branch, "main",
                "Fix " + issue.key() + ": " + issue.summary(),
                "Resolves " + issue.key() + ".\n\n" + issue.summary());
        jiraClient.addComment(issue.key(), "Fix verified and PR opened: " + prUrl);
        jiraClient.transitionTo(issue.key(), "In Review");
        worktreeManager.removeWorktree(workDir, branch);
    }

    private void onExhausted(Issue issue, Path workDir, String branch, GateResult lastResult) {
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

    /** Pulls the "source|key" line JiraTicketService embeds in every ticket it files back out. */
    private DefectSignature extractSignature(String description) {
        for (String line : description.split("\\R")) {
            if (line.startsWith(SIGNATURE_MARKER)) {
                return DefectSignature.parse(line.substring(SIGNATURE_MARKER.length()).strip());
            }
        }
        throw new IllegalStateException("No " + SIGNATURE_MARKER.strip() + " line in this ticket's description "
                + "— likely filed before signature tracking was added");
    }

    private String slug(String text) {
        String slug = text.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.length() > 40 ? slug.substring(0, 40) : slug;
    }
}
