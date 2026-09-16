package com.bank.vam.service.fileingest;

import com.bank.vam.entity.fileingest.IngestJob;
import org.springframework.stereotype.Service;

/**
 * Files one Jira ticket when this app hits a file shape it doesn't recognize
 * — not on every upload (see IngestOrchestrator: the known Receivables shape
 * never touches this). The {@code INGEST_JOB_ID:} marker line is how the
 * separate file-ingest-agent-service worker (polling this same Jira project
 * on its own, per tasks/file-ingest-pipeline-design.md) finds its way back
 * to the shared {@code ingest_job} row — the only two things connecting the
 * two services are this ticket and that row.
 */
@Service
public class FileIngestTicketService {

    private final FileIngestJiraClient jiraClient;

    public FileIngestTicketService(FileIngestJiraClient jiraClient) {
        this.jiraClient = jiraClient;
    }

    /** Files a ticket for an unrecognized-format job and returns its key. */
    public String fileTicket(IngestJob job, String reason) {
        String summary = "File ingest: unrecognized format — %s / %s / %s".formatted(
                job.getCustomerId(), job.getDomain(), job.getOriginalFilename());
        String description = """
                INGEST_JOB_ID: %s

                Customer: %s
                Domain: %s
                File: %s
                Reason: %s

                This app (backend) couldn't process this file with its known shape and
                needs a transform built for it. Pick this ticket up, profile the file,
                write/test/merge a transform into transform-handlers, then set
                format_signature_id on the ingest_job row (id above) and close this
                ticket — backend's retry sweep resumes the job automatically once it
                sees a transform_ref. See tasks/file-ingest-pipeline-design.md.
                """.formatted(job.getId(), job.getCustomerId(), job.getDomain(), job.getOriginalFilename(), reason);

        String domainLabel = "domain-" + job.getDomain().name().toLowerCase();
        return jiraClient.createIssue(summary, description, domainLabel, "needs-transform");
    }
}
