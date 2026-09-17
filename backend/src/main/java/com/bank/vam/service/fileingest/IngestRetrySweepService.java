package com.bank.vam.service.fileingest;

import com.bank.vam.config.FileIngestProperties;
import com.bank.vam.entity.fileingest.FormatSignature;
import com.bank.vam.entity.fileingest.IngestJob;
import com.bank.vam.entity.fileingest.IngestStage;
import com.bank.vam.repository.fileingest.FormatSignatureRepository;
import com.bank.vam.repository.fileingest.IngestJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Resumes a job left in AWAITING_TRANSFORM once the separate file-ingest-agent-service worker has
 * done its job — this app never calls that worker directly, so this sweep is the only thing that
 * notices its work finished. Mirrors BalanceRefreshService's "find stuck/pending items and retry"
 * shape exactly (same @Scheduled(fixedRateString=...) + @Async style, same scan-and-tally logging).
 */
@Service
public class IngestRetrySweepService {

    private static final Logger log = LoggerFactory.getLogger(IngestRetrySweepService.class);

    private final IngestJobRepository ingestJobRepository;
    private final FormatSignatureRepository formatSignatureRepository;
    private final GeneratedTransformRunner transformRunner;
    private final IngestOrchestrator orchestrator;
    private final Path workspaceRoot;
    private final Duration staleAfter;

    public IngestRetrySweepService(IngestJobRepository ingestJobRepository,
                                    FormatSignatureRepository formatSignatureRepository,
                                    GeneratedTransformRunner transformRunner,
                                    IngestOrchestrator orchestrator,
                                    FileIngestProperties properties) {
        this.ingestJobRepository = ingestJobRepository;
        this.formatSignatureRepository = formatSignatureRepository;
        this.transformRunner = transformRunner;
        this.orchestrator = orchestrator;
        this.workspaceRoot = Path.of(properties.getWorkspaceDir());
        this.staleAfter = Duration.ofMinutes(properties.getStaleAfterMinutes());
    }

    @Scheduled(fixedRateString = "#{${vam.fileingest.retry-cadence-minutes:2} * 60 * 1000}")
    @Async("taskExecutor")
    public void resumeAwaitingTransformJobs() {
        List<IngestJob> waiting = ingestJobRepository.findByStage(IngestStage.AWAITING_TRANSFORM);
        int attempted = 0;
        int resumed = 0;
        int stillWaiting = 0;
        int failed = 0;
        int reescalated = 0;
        for (IngestJob job : waiting) {
            attempted++;
            if (job.getFormatSignatureId() == null) {
                // No agent-worker outcome ever landed on this row (e.g. a stage manually reset
                // without also setting formatSignatureId) -- without this, such a job sits here
                // forever, silently skipped by every sweep cycle.
                if (Duration.between(job.getUpdatedAt(), Instant.now()).compareTo(staleAfter) > 0) {
                    orchestrator.escalateToAgent(job, "Still awaiting a transform after "
                            + staleAfter.toMinutes() + " minute(s) -- re-filing a ticket.");
                    reescalated++;
                } else {
                    stillWaiting++;
                }
                continue;
            }
            Optional<FormatSignature> signature = formatSignatureRepository.findById(job.getFormatSignatureId());
            if (signature.isEmpty() || signature.get().getTransformRef() == null) {
                stillWaiting++;
                continue;
            }
            try {
                Path sourceFile = workspaceRoot.resolve(job.getWorkspacePath());
                TransformOutput output = transformRunner.run(signature.get(), sourceFile);
                orchestrator.runPipelineFrom(job, output);
                resumed++;
            } catch (Exception e) {
                // Confirmed live: this used to only log + count, leaving the job in
                // AWAITING_TRANSFORM forever -- every sweep cycle (every retryCadenceMinutes)
                // retried the identical failing operation with zero customer-visible signal, no
                // BLOCKED transition, no timeline event. formatSignatureId/transformRef are left
                // untouched, so resetting the stage back to AWAITING_TRANSFORM safely retries.
                log.error("Failed to resume ingest job {} after transform became available", job.getId(), e);
                orchestrator.blockJob(job, "Failed to resume after a transform became available: " + e.getMessage());
                failed++;
            }
        }
        if (attempted > 0) {
            log.info("Ingest retry sweep: attempted={}, resumed={}, stillWaiting={}, failed={}, reescalated={}",
                    attempted, resumed, stillWaiting, failed, reescalated);
        }
    }
}
