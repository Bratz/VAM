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
    public void fileIfNew(DetectedDefect defect, int prNumber, String sourceBranch) {
        String dedupLabel = defect.signature().asLabel();
        if (jiraClient.existsWithLabel(dedupLabel)) {
            log.debug("Skipping {} — already ticketed ({})", defect.signature(), dedupLabel);
            return;
        }
        // DEFECT_SIGNATURE and SOURCE_BRANCH are both machine-readable: TriageOrchestrator parses
        // them back out of the ticket description. DEFECT_SIGNATURE says which defect the test
        // gate needs to see resolved (not "does the whole project's lint/test command exit 0",
        // which is unwinnable on a codebase with pre-existing, unrelated warnings/failures).
        // SOURCE_BRANCH says which branch the coding agent's worktree needs to be based on — the
        // defective code lives ONLY on the PR branch that failed CI, never on main (a PR that
        // fails CI is, by definition, not merged), so branching the fix worktree from origin/main
        // would leave the agent unable to see the actual bug at all. Confirmed live: without this,
        // "fixes" were the agent fabricating something plausible from the ticket text alone,
        // because the file it needed to fix genuinely didn't exist in its worktree.
        String description = defect.details() + "\n\nFirst detected on PR #" + prNumber + "."
                + "\n\nDEFECT_SIGNATURE: " + defect.signature().source() + "|" + defect.signature().key()
                + "\n\nSOURCE_BRANCH: " + sourceBranch;
        String stackLabel = defect.signature().source().startsWith("frontend") ? "stack-frontend" : "stack-backend";
        String key = jiraClient.createIssue(defect.summary(), description, dedupLabel, stackLabel);
        log.info("Filed {} for {}", key, defect.signature());
    }
}
