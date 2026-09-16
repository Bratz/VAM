package com.bank.vam.service.fileingest;

import com.bank.vam.entity.fileingest.IngestDomain;
import com.bank.vam.entity.fileingest.IngestJob;
import com.bank.vam.entity.fileingest.IngestStage;
import com.bank.vam.entity.fileingest.ReconciliationResult;
import com.bank.vam.entity.fileingest.RowStatus;
import com.bank.vam.entity.fileingest.StagedTransaction;
import com.bank.vam.repository.fileingest.IngestJobRepository;
import com.bank.vam.repository.fileingest.StagedTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * The per-job sequencer, one state machine per job, run synchronously right
 * after upload. Receivables tries its one hand-written CSV shape first
 * (cheap, no agent call); Payables/Payments have no such fast path and, like
 * any unrecognized Receivables shape, escalate straight to the ticket-and-
 * wait path (see the design doc's "Revised architecture").
 */
@Component
public class IngestOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(IngestOrchestrator.class);

    private final ReceivablesCsvTransform receivablesCsvTransform;
    private final StagingDryRunRunner stagingDryRunRunner;
    private final ReconciliationCheckRunner reconciliationCheckRunner;
    private final RowQuarantineService rowQuarantineService;
    private final ReceivablesProcessor receivablesProcessor;
    private final PayablesProcessor payablesProcessor;
    private final PaymentsProcessor paymentsProcessor;
    private final IngestJobRepository ingestJobRepository;
    private final StagedTransactionRepository stagedTransactionRepository;
    private final TimelineEventPublisher timelinePublisher;
    private final FileIngestTicketService ticketService;
    private final Path workspaceRoot;

    public IngestOrchestrator(ReceivablesCsvTransform receivablesCsvTransform,
                               StagingDryRunRunner stagingDryRunRunner,
                               ReconciliationCheckRunner reconciliationCheckRunner,
                               RowQuarantineService rowQuarantineService,
                               ReceivablesProcessor receivablesProcessor,
                               PayablesProcessor payablesProcessor,
                               PaymentsProcessor paymentsProcessor,
                               IngestJobRepository ingestJobRepository,
                               StagedTransactionRepository stagedTransactionRepository,
                               TimelineEventPublisher timelinePublisher,
                               FileIngestTicketService ticketService,
                               com.bank.vam.config.FileIngestProperties properties) {
        this.receivablesCsvTransform = receivablesCsvTransform;
        this.stagingDryRunRunner = stagingDryRunRunner;
        this.reconciliationCheckRunner = reconciliationCheckRunner;
        this.rowQuarantineService = rowQuarantineService;
        this.receivablesProcessor = receivablesProcessor;
        this.payablesProcessor = payablesProcessor;
        this.paymentsProcessor = paymentsProcessor;
        this.ingestJobRepository = ingestJobRepository;
        this.stagedTransactionRepository = stagedTransactionRepository;
        this.timelinePublisher = timelinePublisher;
        this.ticketService = ticketService;
        this.workspaceRoot = Path.of(properties.getWorkspaceDir());
    }

    public void runJob(UUID ingestJobId) {
        IngestJob job = ingestJobRepository.findById(ingestJobId)
                .orElseThrow(() -> new IllegalStateException("No ingest job " + ingestJobId));
        Path sourceFile = workspaceRoot.resolve(job.getWorkspacePath());

        TransformOutput output;
        if (job.getDomain() == IngestDomain.RECEIVABLES) {
            try {
                output = receivablesCsvTransform.transform(sourceFile);
            } catch (IllegalArgumentException unrecognizedFormat) {
                escalateToAgent(job, unrecognizedFormat.getMessage());
                return;
            }
        } else {
            escalateToAgent(job, "No hand-written transform exists for " + job.getDomain() + " yet.");
            return;
        }

        runPipelineFrom(job, output);
    }

    /** Continues from an already-known TransformOutput — shared by the known-shape path above
     * and IngestRetrySweepService once the agent worker has produced a transform. */
    void runPipelineFrom(IngestJob job, TransformOutput output) {
        UUID ingestJobId = job.getId();
        stagingDryRunRunner.stage(ingestJobId, output);
        job.setStage(IngestStage.STAGED);
        ingestJobRepository.save(job);
        timelinePublisher.emit(ingestJobId, IngestStage.STAGED, "COMPLETE",
                output.transformedRowCount() + " row(s) staged.");

        ReconciliationResult reconciliation = reconciliationCheckRunner.check(ingestJobId, output);
        if (!reconciliation.isWithinTolerance()) {
            String reason = "Reconciliation mismatch: source total %s (%d rows) vs. transformed total %s (%d rows), delta %s"
                    .formatted(reconciliation.getSourceControlTotal(), reconciliation.getSourceRowCount(),
                            reconciliation.getTransformedControlTotal(), reconciliation.getTransformedRowCount(),
                            reconciliation.getDelta());
            blockJob(job, reason);
            return;
        }

        rowQuarantineService.quarantineInvalidRows(ingestJobId);

        job.setStage(IngestStage.PROCESSING);
        ingestJobRepository.save(job);
        timelinePublisher.emit(ingestJobId, IngestStage.PROCESSING, "STARTED", null);

        DomainProcessor processor = switch (job.getDomain()) {
            case RECEIVABLES -> receivablesProcessor;
            case PAYABLES -> payablesProcessor;
            case PAYMENTS -> paymentsProcessor;
        };
        processor.process(ingestJobId, output.rows());

        List<StagedTransaction> allRows = stagedTransactionRepository.findByIngestJobId(ingestJobId);
        long processed = allRows.stream().filter(r -> r.getStatus() == RowStatus.PROCESSED).count();
        long quarantined = allRows.stream().filter(r -> r.getStatus() == RowStatus.QUARANTINED).count();
        long failed = allRows.stream().filter(r -> r.getStatus() == RowStatus.FAILED).count();

        job.setStage(IngestStage.DONE);
        ingestJobRepository.save(job);
        String summary = "%d processed, %d quarantined, %d failed (of %d total)."
                .formatted(processed, quarantined, failed, allRows.size());
        timelinePublisher.emit(ingestJobId, IngestStage.DONE, "COMPLETE", summary);
    }

    /** Unrecognized format: file a ticket for the separate agent worker and stop — no analysis,
     * no coding agent call happens in this app. IngestRetrySweepService resumes the job once the
     * worker sets formatSignatureId (and that signature's transformRef) on the shared row. */
    // Package-private, not private: IngestRetrySweepService also calls this to re-file a ticket
    // for a job that's gone stale waiting on a formatSignatureId that never arrived.
    void escalateToAgent(IngestJob job, String reason) {
        // Deliberately doesn't claim SIGNATURE_NEW here — this app has no way to know whether the
        // shape is genuinely new or already cached (that determination happens later, inside the
        // agent worker, which is the only side that ever computes a signature hash). Claiming
        // SIGNATURE_NEW now and having the worker later emit SIGNATURE_MATCHED would read as a
        // contradiction on the customer-facing timeline.
        try {
            String ticketKey = ticketService.fileTicket(job, reason);
            job.setJiraTicketKey(ticketKey);
            job.setStage(IngestStage.AWAITING_TRANSFORM);
            ingestJobRepository.save(job);
            timelinePublisher.emit(job.getId(), IngestStage.AWAITING_TRANSFORM, "COMPLETE",
                    "Unrecognized format — ticket " + ticketKey + " filed, waiting for a transform.");
        } catch (Exception e) {
            log.error("Failed to file ticket for job {}", job.getId(), e);
            blockJob(job, "Unrecognized format, and filing a ticket for it failed: " + e.getMessage());
        }
    }

    private void blockJob(IngestJob job, String reason) {
        job.setStage(IngestStage.BLOCKED);
        job.setBlockedReason(reason);
        ingestJobRepository.save(job);
        timelinePublisher.emit(job.getId(), IngestStage.BLOCKED, "COMPLETE", reason);
    }
}
