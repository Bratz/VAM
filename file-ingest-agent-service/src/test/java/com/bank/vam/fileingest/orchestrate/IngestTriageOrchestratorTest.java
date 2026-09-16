package com.bank.vam.fileingest.orchestrate;

import com.bank.vam.fileingest.agent.AnalysisAgentClient;
import com.bank.vam.fileingest.agent.FileStructureProfile;
import com.bank.vam.fileingest.config.IngestProperties;
import com.bank.vam.fileingest.entity.FormatSignature;
import com.bank.vam.fileingest.entity.IngestDomain;
import com.bank.vam.fileingest.entity.IngestJob;
import com.bank.vam.fileingest.entity.IngestStage;
import com.bank.vam.fileingest.events.TimelineEventPublisher;
import com.bank.vam.fileingest.jira.JiraClient;
import com.bank.vam.fileingest.jira.JiraClient.Issue;
import com.bank.vam.fileingest.repository.IngestJobRepository;
import com.bank.vam.fileingest.signature.FormatSignatureService;
import com.bank.vam.fileingest.transform.TransformGenerationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Every collaborator here is mocked (no live Anthropic/Jira calls, same style already used for
 * PayablesProcessorTest/PaymentsProcessorTest in the now-retired process/ package) — this proves
 * the orchestration logic itself: ticket -> job lookup, cache hit/miss branching, and setting
 * formatSignatureId on the shared row, independent of what a real ticket or a real model call
 * would return.
 */
class IngestTriageOrchestratorTest {

    private final JiraClient jiraClient = mock(JiraClient.class);
    private final IngestJobRepository ingestJobRepository = mock(IngestJobRepository.class);
    private final AnalysisAgentClient analysisAgentClient = mock(AnalysisAgentClient.class);
    private final TransformGenerationService transformGenerationService = mock(TransformGenerationService.class);
    private final FormatSignatureService formatSignatureService = mock(FormatSignatureService.class);
    private final TimelineEventPublisher timelinePublisher = mock(TimelineEventPublisher.class);

    private final IngestTriageOrchestrator orchestrator = new IngestTriageOrchestrator(
            jiraClient, ingestJobRepository, analysisAgentClient, transformGenerationService,
            formatSignatureService, timelinePublisher, new IngestProperties(null, null, null, "/tmp/workspace"));

    private IngestJob jobWith(UUID id) {
        IngestJob job = new IngestJob();
        job.setId(id);
        job.setCustomerId("DEMO-CUSTOMER-1");
        job.setDomain(IngestDomain.RECEIVABLES);
        job.setWorkspacePath(id + "/source.csv");
        return job;
    }

    private FileStructureProfile aProfile() {
        return new FileStructureProfile(List.of("amount", "currency"), ",", "amount", null);
    }

    @Test
    void cacheHitSetsFormatSignatureIdAndClosesTheTicketWithoutRunningTheCodingAgent() {
        UUID jobId = UUID.randomUUID();
        Issue issue = new Issue("KAN-1", "summary", "INGEST_JOB_ID: " + jobId);
        when(jiraClient.findOldestOpenTicket()).thenReturn(Optional.of(issue)).thenReturn(Optional.empty());
        when(ingestJobRepository.findById(jobId)).thenReturn(Optional.of(jobWith(jobId)));
        when(analysisAgentClient.analyze(any(), any()))
                .thenReturn(new AnalysisAgentClient.AnalysisResult(true, aProfile(), null));
        FormatSignature existing = new FormatSignature();
        existing.setId(UUID.randomUUID());
        existing.setTransformRef("abc123def456");
        when(formatSignatureService.lookup(eq("DEMO-CUSTOMER-1"), eq(IngestDomain.RECEIVABLES), any()))
                .thenReturn(Optional.of(existing));

        orchestrator.tryStartProcessing();

        verify(transformGenerationService, never()).generateAndRun(any(), any(), any(), any());
        ArgumentCaptor<IngestJob> savedJob = ArgumentCaptor.forClass(IngestJob.class);
        verify(ingestJobRepository, atLeastOnce()).save(savedJob.capture());
        assertThat(savedJob.getValue().getFormatSignatureId()).isEqualTo(existing.getId());
        verify(jiraClient).transitionTo("KAN-1", "Done");
        verify(timelinePublisher).emit(eq(jobId), eq(IngestStage.SIGNATURE_MATCHED), eq("COMPLETE"), any());
    }

    @Test
    void cacheMissRunsTheCodingAgentThenClosesTheTicketOnSuccess() {
        UUID jobId = UUID.randomUUID();
        Issue issue = new Issue("KAN-2", "summary", "INGEST_JOB_ID: " + jobId);
        when(jiraClient.findOldestOpenTicket()).thenReturn(Optional.of(issue)).thenReturn(Optional.empty());
        when(ingestJobRepository.findById(jobId)).thenReturn(Optional.of(jobWith(jobId)));
        when(analysisAgentClient.analyze(any(), any()))
                .thenReturn(new AnalysisAgentClient.AnalysisResult(true, aProfile(), null));
        when(formatSignatureService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        when(transformGenerationService.generateAndRun(any(), any(), any(), any()))
                .thenReturn(new TransformGenerationService.GenerationResult(true, "commitsha123", null));
        FormatSignature nowRecorded = new FormatSignature();
        nowRecorded.setId(UUID.randomUUID());
        nowRecorded.setTransformRef("commitsha123");
        // First lookup (before generation) is the miss above; the post-generation re-lookup finds it.
        when(formatSignatureService.lookup(eq("DEMO-CUSTOMER-1"), eq(IngestDomain.RECEIVABLES), any()))
                .thenReturn(Optional.empty(), Optional.of(nowRecorded));

        orchestrator.tryStartProcessing();

        verify(transformGenerationService).generateAndRun(any(), any(), any(), any());
        verify(jiraClient).transitionTo("KAN-2", "Done");
    }

    @Test
    void ticketWithNoJobIdMarkerIsEscalatedInsteadOfLoopingForever() {
        Issue issue = new Issue("KAN-3", "summary", "no marker here");
        when(jiraClient.findOldestOpenTicket()).thenReturn(Optional.of(issue)).thenReturn(Optional.empty());

        orchestrator.tryStartProcessing();

        verify(jiraClient).addLabel("KAN-3", "needs-human");
        verify(jiraClient).transitionTo("KAN-3", "Blocked");
        verify(ingestJobRepository, never()).findById(any());
    }
}
