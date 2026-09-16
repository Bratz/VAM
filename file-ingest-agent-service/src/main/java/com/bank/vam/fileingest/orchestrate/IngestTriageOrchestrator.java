package com.bank.vam.fileingest.orchestrate;

import com.bank.vam.fileingest.agent.AnalysisAgentClient;
import com.bank.vam.fileingest.agent.FileStructureProfile;
import com.bank.vam.fileingest.config.IngestProperties;
import com.bank.vam.fileingest.entity.FormatSignature;
import com.bank.vam.fileingest.entity.IngestJob;
import com.bank.vam.fileingest.entity.IngestStage;
import com.bank.vam.fileingest.events.TimelineEventPublisher;
import com.bank.vam.fileingest.jira.JiraClient;
import com.bank.vam.fileingest.jira.JiraClient.Issue;
import com.bank.vam.fileingest.repository.IngestJobRepository;
import com.bank.vam.fileingest.signature.FormatSignatureService;
import com.bank.vam.fileingest.transform.TransformGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single-instance, single-concurrency worker: picks the oldest open file-ingest ticket, profiles
 * the file, resolves a transform (cache hit, or the full generate/test/merge loop on a miss), and
 * either closes the ticket or escalates to a human — a real structural mirror of
 * defect-fix-service's TriageOrchestrator (same {@code ApplicationReadyEvent} + async drain-loop
 * shape, same {@code AtomicBoolean busy} guard; it does NOT poll on a schedule).
 *
 * <p>This is one half of the fully decoupled design (see tasks/file-ingest-pipeline-design.md):
 * backend never calls this service directly, and this service never calls backend back. The two
 * things connecting them are this ticket (found by its {@code INGEST_JOB_ID:} marker line, the
 * same convention defect-fix-service uses for its own markers) and the shared {@code ingest_job}/
 * {@code format_signature}/{@code timeline_event} rows both processes read and write directly.
 */
@Service
public class IngestTriageOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(IngestTriageOrchestrator.class);

    /** Public so IngestTicketService can write the exact same literal into every ticket it files,
     * instead of two independently-typed strings drifting apart (as they had, undetected, until
     * this was fixed — see JobIdMarkerRoundTripTest). */
    public static final String JOB_ID_MARKER = "INGEST_JOB_ID: ";

    private final JiraClient jiraClient;
    private final IngestJobRepository ingestJobRepository;
    private final AnalysisAgentClient analysisAgentClient;
    private final TransformGenerationService transformGenerationService;
    private final FormatSignatureService formatSignatureService;
    private final TimelineEventPublisher timelinePublisher;
    private final Path workspaceRoot;
    private final AtomicBoolean busy = new AtomicBoolean(false);

    public IngestTriageOrchestrator(JiraClient jiraClient,
                                     IngestJobRepository ingestJobRepository,
                                     AnalysisAgentClient analysisAgentClient,
                                     TransformGenerationService transformGenerationService,
                                     FormatSignatureService formatSignatureService,
                                     TimelineEventPublisher timelinePublisher,
                                     IngestProperties properties) {
        this.jiraClient = jiraClient;
        this.ingestJobRepository = ingestJobRepository;
        this.analysisAgentClient = analysisAgentClient;
        this.transformGenerationService = transformGenerationService;
        this.formatSignatureService = formatSignatureService;
        this.timelinePublisher = timelinePublisher;
        this.workspaceRoot = Path.of(properties.workspaceDir());
    }

    /** A ticket left sitting in To Do (service restarted mid-backlog) won't otherwise get picked
     * up again until the next new ticket is filed — check once on every boot. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        tryStartProcessing();
    }

    /** No-op if already busy — the busy worker will pick up the new ticket itself once it's free. */
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
        UUID jobId;
        try {
            jobId = extractJobId(issue.description());
        } catch (Exception e) {
            log.error("Ticket {} has no usable INGEST_JOB_ID marker — escalating instead of looping on it forever", issue.key(), e);
            safeEscalate(issue.key(), "Could not find an INGEST_JOB_ID marker in this ticket's description.");
            return;
        }

        // Deliberately its own try/catch, not folded into the one below: a lookup failure here
        // (e.g. this service's own IngestStage enum missing a value backend wrote — confirmed
        // live, crashed this service's entire startup via onStartup -> tryStartProcessing, since
        // that call chain doesn't actually go through the @Async proxy for a same-class
        // self-invocation and so runs synchronously) must never propagate past processTicket for
        // ANY ticket, or one bad row takes down the whole service, not just this one ticket.
        Optional<IngestJob> jobOpt;
        try {
            jobOpt = ingestJobRepository.findById(jobId);
        } catch (Exception e) {
            log.error("Could not look up ingest job {} for ticket {}", jobId, issue.key(), e);
            io.sentry.Sentry.captureException(e);
            safeEscalate(issue.key(), "Database error looking up ingest job " + jobId + ": " + e.getMessage());
            return;
        }
        if (jobOpt.isEmpty()) {
            log.error("Ticket {} references ingest job {} which no longer exists", issue.key(), jobId);
            safeEscalate(issue.key(), "Referenced ingest job " + jobId + " no longer exists.");
            return;
        }
        IngestJob job = jobOpt.get();

        log.info("Starting {} for ingest job {}", issue.key(), job.getId());
        safeTransition(issue.key(), "In Progress");

        try {
            resolveTransform(issue, job);
        } catch (Exception e) {
            log.error("Unexpected failure processing {}", issue.key(), e);
            io.sentry.Sentry.captureException(e);
            onFailure(issue, job, "Pipeline error: " + e.getMessage());
        }
    }

    private void resolveTransform(Issue issue, IngestJob job) throws Exception {
        Path sourceFile = workspaceRoot.resolve(job.getWorkspacePath());
        String fileName = Path.of(job.getWorkspacePath()).getFileName().toString();
        Path jobDir = sourceFile.getParent();

        AnalysisAgentClient.AnalysisResult analysisResult = analysisAgentClient.analyze(jobDir, fileName);
        if (!analysisResult.succeeded()) {
            onFailure(issue, job, "Analysis failed: " + analysisResult.failureReason());
            return;
        }
        FileStructureProfile profile = analysisResult.profile();
        timelinePublisher.emit(job.getId(), IngestStage.ANALYZED, "COMPLETE",
                "%d column(s), delimiter \"%s\"".formatted(profile.columns().size(), profile.delimiter()));

        Optional<FormatSignature> cached = formatSignatureService.lookup(job.getCustomerId(), job.getDomain(), profile);
        FormatSignature signature;
        if (cached.isPresent() && cached.get().getTransformRef() != null) {
            signature = cached.get();
            timelinePublisher.emit(job.getId(), IngestStage.SIGNATURE_MATCHED, "COMPLETE",
                    "Reusing the transform from commit " + signature.getTransformRef().substring(0, 12) + ".");
        } else {
            timelinePublisher.emit(job.getId(), IngestStage.CODING_AGENT_RUNNING, "STARTED", null);
            TransformGenerationService.GenerationResult result =
                    transformGenerationService.generateAndRun(job, sourceFile, fileName, profile);
            if (!result.succeeded()) {
                timelinePublisher.emit(job.getId(), IngestStage.TEST_GATE, "FAILED", result.failureReason());
                onFailure(issue, job, "Coding agent could not produce a working transform: " + result.failureReason());
                return;
            }
            timelinePublisher.emit(job.getId(), IngestStage.TEST_GATE, "COMPLETE",
                    "Generated transform passed its test gate and was merged into transform-handlers.");
            signature = formatSignatureService.lookup(job.getCustomerId(), job.getDomain(), profile)
                    .orElseThrow(() -> new IllegalStateException("recordTransform just ran but the signature isn't findable"));
        }

        job.setFormatSignatureId(signature.getId());
        // Explicit, not conditional: confirmed live that leaving this unset silently strands the
        // job forever once it does. backend's IngestRetrySweepService only ever queries
        // findByStage(AWAITING_TRANSFORM) -- if this job previously failed once (stage now
        // BLOCKED) before a later retry succeeded, that BLOCKED value would otherwise sit here
        // permanently even though a working transform now exists, since nothing else resets it.
        job.setStage(IngestStage.AWAITING_TRANSFORM);
        ingestJobRepository.save(job);

        safeComment(issue.key(), "Transform ready (commit " + signature.getTransformRef()
                + "). backend's retry sweep will resume this job automatically.");
        safeTransition(issue.key(), "Done");
    }

    private void onFailure(Issue issue, IngestJob job, String reason) {
        job.setStage(IngestStage.BLOCKED);
        job.setBlockedReason(reason);
        ingestJobRepository.save(job);
        timelinePublisher.emit(job.getId(), IngestStage.BLOCKED, "COMPLETE", reason);
        safeEscalate(issue.key(), reason);
    }

    private void safeEscalate(String issueKey, String reason) {
        safeComment(issueKey, "File-ingest agent worker could not resolve this: " + reason);
        try {
            jiraClient.addLabel(issueKey, "needs-human");
        } catch (Exception e) {
            log.warn("Could not label {} needs-human", issueKey, e);
        }
        safeTransition(issueKey, "Blocked");
    }

    private void safeComment(String issueKey, String text) {
        try {
            jiraClient.addComment(issueKey, text);
        } catch (Exception e) {
            log.warn("Could not comment on {}", issueKey, e);
        }
    }

    private void safeTransition(String issueKey, String status) {
        try {
            jiraClient.transitionTo(issueKey, status);
        } catch (Exception e) {
            log.error("Could not transition {} to {}", issueKey, status, e);
        }
    }

    /** Pulls the "INGEST_JOB_ID: <uuid>" line FileIngestTicketService embeds in every ticket it files.
     * Package-private so JobIdMarkerRoundTripTest can call it directly against a real ticket description. */
    UUID extractJobId(String description) {
        for (String line : description.split("\\R")) {
            if (line.startsWith(JOB_ID_MARKER)) {
                return UUID.fromString(line.substring(JOB_ID_MARKER.length()).strip());
            }
        }
        throw new IllegalStateException("No \"" + JOB_ID_MARKER.strip() + "\" line in this ticket's description");
    }
}
