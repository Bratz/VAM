package com.bank.vam.defectfix.jira;

import com.bank.vam.defectfix.detect.DetectedDefect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class JiraTicketService {

    private static final Logger log = LoggerFactory.getLogger(JiraTicketService.class);

    // How many consecutive bot-authored commits (no human commit in between) are tolerated before
    // a newly-introduced defect gets escalated straight to a human instead of auto-processed —
    // see fileIfNew. Not exposed as config (yet): a fixed, conservative ceiling is enough to catch
    // "the bot is chasing its own tail" without adding a knob nobody's asked to tune.
    private static final int MAX_CHAIN_DEPTH = 3;

    private final JiraClient jiraClient;

    public JiraTicketService(JiraClient jiraClient) {
        this.jiraClient = jiraClient;
    }

    /**
     * Files a ticket for this defect unless one already exists (same signature = same defect).
     * chainDepth is 0 for a defect caused by a human commit; > 0 means it only appeared after that
     * many consecutive automated fix commits with no human commit since — at MAX_CHAIN_DEPTH, the
     * ticket is escalated straight to Blocked instead of left in To Do for automatic pickup, so the
     * coding agent can't keep "fixing" one bot-introduced regression into the next indefinitely.
     */
    public void fileIfNew(DetectedDefect defect, int prNumber, String sourceBranch, int chainDepth) {
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

        if (chainDepth >= MAX_CHAIN_DEPTH) {
            jiraClient.addComment(key, "This defect appeared after " + chainDepth + " consecutive automated "
                    + "fix commits on this branch with no human commit in between — escalating instead of "
                    + "attempting another automated fix, to avoid the pipeline chasing its own regressions.");
            jiraClient.addLabel(key, "needs-human");
            jiraClient.transitionTo(key, "Blocked");
            log.warn("Filed {} for {} but escalated straight to Blocked — chain depth {} >= cap {}",
                    key, defect.signature(), chainDepth, MAX_CHAIN_DEPTH);
            return;
        }
        log.info("Filed {} for {}", key, defect.signature());
    }
}
