package com.bank.vam.service.fileingest;

import com.bank.vam.config.FileIngestProperties;
import com.bank.vam.entity.fileingest.FormatSignature;
import com.bank.vam.entity.fileingest.IngestDomain;
import com.bank.vam.entity.fileingest.IngestJob;
import com.bank.vam.entity.fileingest.IngestStage;
import com.bank.vam.repository.fileingest.FormatSignatureRepository;
import com.bank.vam.repository.fileingest.IngestJobRepository;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Regression test for a real production bug: a job manually reset to AWAITING_TRANSFORM without
 * also setting formatSignatureId (e.g. KAN-14's fix, which only patched the stage column) sat
 * forever, silently skipped by every sweep cycle -- resumeAwaitingTransformJobs only ever checked
 * formatSignatureId == null and moved on, with no path back out.
 */
class IngestRetrySweepServiceTest {

    private final IngestJobRepository ingestJobRepository = mock(IngestJobRepository.class);
    private final FormatSignatureRepository formatSignatureRepository = mock(FormatSignatureRepository.class);
    private final GeneratedTransformRunner transformRunner = mock(GeneratedTransformRunner.class);
    private final IngestOrchestrator orchestrator = mock(IngestOrchestrator.class);

    private IngestRetrySweepService sweepWith(int staleAfterMinutes) {
        FileIngestProperties properties = new FileIngestProperties();
        properties.setWorkspaceDir("/tmp/file-ingest-workspace");
        properties.setStaleAfterMinutes(staleAfterMinutes);
        return new IngestRetrySweepService(ingestJobRepository, formatSignatureRepository,
                transformRunner, orchestrator, properties);
    }

    private IngestJob jobUpdatedAt(Instant updatedAt) {
        IngestJob job = new IngestJob();
        job.setId(UUID.randomUUID());
        job.setCustomerId("DEMO-CUSTOMER-1");
        job.setDomain(IngestDomain.RECEIVABLES);
        job.setStage(IngestStage.AWAITING_TRANSFORM);
        job.setUpdatedAt(updatedAt);
        return job;
    }

    @Test
    void aJobWithNoFormatSignatureIdIsReescalatedOnceItGoesStale() {
        IngestJob stuck = jobUpdatedAt(Instant.now().minus(2, ChronoUnit.HOURS));
        when(ingestJobRepository.findByStage(IngestStage.AWAITING_TRANSFORM)).thenReturn(List.of(stuck));

        sweepWith(60).resumeAwaitingTransformJobs();

        verify(orchestrator).escalateToAgent(eq(stuck), any());
    }

    @Test
    void aRecentJobWithNoFormatSignatureIdIsLeftAloneInsteadOfSpammingNewTickets() {
        IngestJob recent = jobUpdatedAt(Instant.now().minus(5, ChronoUnit.MINUTES));
        when(ingestJobRepository.findByStage(IngestStage.AWAITING_TRANSFORM)).thenReturn(List.of(recent));

        sweepWith(60).resumeAwaitingTransformJobs();

        verify(orchestrator, never()).escalateToAgent(any(), any());
    }

    /**
     * Regression test for a real production bug: confirmed live that a job sat forever at
     * "posting your transactions" with no visible error -- resumeAwaitingTransformJobs's catch
     * only logged + counted a failure, never transitioning the job to BLOCKED or emitting a
     * timeline event, so the identical failing operation retried silently every sweep cycle with
     * zero customer-visible signal.
     */
    @Test
    void aResumeFailureBlocksTheJobInsteadOfRetryingSilentlyForever() throws Exception {
        IngestJob job = jobUpdatedAt(Instant.now());
        job.setWorkspacePath(job.getId() + "/source.csv");
        UUID signatureId = UUID.randomUUID();
        job.setFormatSignatureId(signatureId);

        FormatSignature signature = new FormatSignature();
        signature.setId(signatureId);
        signature.setTransformRef("commitsha789");

        when(ingestJobRepository.findByStage(IngestStage.AWAITING_TRANSFORM)).thenReturn(List.of(job));
        when(formatSignatureRepository.findById(signatureId)).thenReturn(Optional.of(signature));
        when(transformRunner.run(eq(signature), any(Path.class))).thenThrow(new RuntimeException("mvn compile failed"));

        sweepWith(60).resumeAwaitingTransformJobs();

        verify(orchestrator).blockJob(eq(job), any());
        verify(orchestrator, never()).runPipelineFrom(any(), any());
    }
}
