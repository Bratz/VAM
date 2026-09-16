package com.bank.vam.fileingest.orchestrate;

import com.bank.vam.fileingest.agent.AnalysisAgentClient;
import com.bank.vam.fileingest.config.IngestProperties;
import com.bank.vam.fileingest.entity.IngestDomain;
import com.bank.vam.fileingest.entity.IngestJob;
import com.bank.vam.fileingest.events.TimelineEventPublisher;
import com.bank.vam.fileingest.jira.IngestTicketService;
import com.bank.vam.fileingest.jira.JiraClient;
import com.bank.vam.fileingest.repository.IngestJobRepository;
import com.bank.vam.fileingest.signature.FormatSignatureService;
import com.bank.vam.fileingest.transform.TransformGenerationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wires the real IngestTicketService (the writer) to the real
 * IngestTriageOrchestrator.extractJobId (the parser) — the exact pairing that was broken until
 * now, since IngestTicketService wrote "Ingest job id: " and the orchestrator looked for
 * "INGEST_JOB_ID: ". IngestTriageOrchestratorTest never caught this because it hand-builds Issue
 * objects with the correct string directly, bypassing IngestTicketService entirely.
 */
class JobIdMarkerRoundTripTest {

    private final JiraClient jiraClient = mock(JiraClient.class);
    private final IngestTicketService ticketService = new IngestTicketService(jiraClient);
    private final IngestTriageOrchestrator orchestrator = new IngestTriageOrchestrator(
            jiraClient, mock(IngestJobRepository.class), mock(AnalysisAgentClient.class),
            mock(TransformGenerationService.class), mock(FormatSignatureService.class),
            mock(TimelineEventPublisher.class), new IngestProperties(null, null, null, "/tmp/workspace"));

    @Test
    void theRealWriterAndTheRealParserAgreeOnTheJobIdMarker() {
        UUID jobId = UUID.randomUUID();
        IngestJob job = new IngestJob();
        job.setId(jobId);
        job.setCustomerId("DEMO-CUSTOMER-1");
        job.setDomain(IngestDomain.RECEIVABLES);
        job.setOriginalFilename("source.csv");
        when(jiraClient.createIssue(any(), any(), any())).thenReturn("KAN-1");

        ticketService.fileTicket(job);

        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(jiraClient).createIssue(any(), description.capture(), any());

        assertThat(orchestrator.extractJobId(description.getValue())).isEqualTo(jobId);
    }
}
