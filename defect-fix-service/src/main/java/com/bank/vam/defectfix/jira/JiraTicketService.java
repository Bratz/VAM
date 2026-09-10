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
        String description = defect.details() + "\n\nFirst detected on PR #" + prNumber + ".";
        String key = jiraClient.createIssue(defect.summary(), description, dedupLabel);
        log.info("Filed {} for {}", key, defect.signature());
    }
}
