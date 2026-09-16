package com.bank.vam.fileingest.jira;

import com.bank.vam.fileingest.entity.IngestJob;
import com.bank.vam.fileingest.orchestrate.IngestTriageOrchestrator;
import org.springframework.stereotype.Service;

/**
 * Files one Jira ticket per upload. Unlike defect-fix-service's
 * JiraTicketService, there's no dedup-by-signature logic here — an
 * IngestJob's own id is its identity, so every upload simply gets a ticket.
 */
@Service
public class IngestTicketService {

    private final JiraClient jiraClient;

    public IngestTicketService(JiraClient jiraClient) {
        this.jiraClient = jiraClient;
    }

    /** Files a ticket for a newly-received upload and returns its key. */
    public String fileTicket(IngestJob job) {
        String summary = "File ingest: %s / %s / %s".formatted(
                job.getCustomerId(), job.getDomain(), job.getOriginalFilename());
        String description = """
                Customer: %s
                Domain: %s
                File: %s
                %s%s

                This ticket tracks the file-ingestion pipeline for this upload — \
                see tasks/file-ingest-pipeline-design.md for the stage list. It \
                will move through statuses automatically; no action is needed \
                unless it reaches Blocked.
                """.formatted(job.getCustomerId(), job.getDomain(), job.getOriginalFilename(),
                        IngestTriageOrchestrator.JOB_ID_MARKER, job.getId());

        String domainLabel = "domain-" + job.getDomain().name().toLowerCase();
        return jiraClient.createIssue(summary, description, domainLabel);
    }
}
