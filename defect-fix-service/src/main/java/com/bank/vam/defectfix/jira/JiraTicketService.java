package com.bank.vam.defectfix.jira;

import com.bank.vam.defectfix.detect.DetectedDefect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class JiraTicketService {

    private static final Logger log = LoggerFactory.getLogger(JiraTicketService.class);

    private final JiraClient jiraClient;

    public JiraTicketService(JiraClient jiraClient) {
        this.jiraClient = jiraClient;
    }

    /** Files a ticket for this defect unless one already exists (same signature = same defect). */
    public void fileIfNew(DetectedDefect defect, int prNumber) {
        String dedupLabel = defect.signature().asLabel();
        if (jiraClient.existsWithLabel(dedupLabel)) {
            log.debug("Skipping {} — already ticketed ({})", defect.signature(), dedupLabel);
            return;
        }
        // The DEFECT_SIGNATURE line is machine-readable: TriageOrchestrator parses it back out of
        // the ticket description to know exactly which defect the test gate needs to see resolved
        // (as opposed to "does the whole project's lint/test command exit 0", which is unwinnable
        // on a codebase that already has pre-existing, unrelated warnings/failures).
        String description = defect.details() + "\n\nFirst detected on PR #" + prNumber + "."
                + "\n\nDEFECT_SIGNATURE: " + defect.signature().source() + "|" + defect.signature().key();
        String stackLabel = defect.signature().source().startsWith("frontend") ? "stack-frontend" : "stack-backend";
        String key = jiraClient.createIssue(defect.summary(), description, dedupLabel, stackLabel);
        log.info("Filed {} for {}", key, defect.signature());
    }
}
