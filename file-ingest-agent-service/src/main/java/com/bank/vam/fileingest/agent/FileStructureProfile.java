package com.bank.vam.fileingest.agent;

import java.util.List;

/**
 * What the analysis agent's submit_profile tool call produces (build order
 * step 3). Persisted to {@code analysis.json} in the job's workspace
 * directory — a filesystem artifact, not a DB row, matching the design
 * doc's "Data model" split (this is debugging/audit material, not something
 * queried transactionally the way staged_transaction is).
 *
 * <p>Not yet consumed to drive anything (step 4's coding agent is what
 * would read this to write a transform); step 3's job is only to prove the
 * agent can produce a usable profile against a real uploaded file.
 */
public record FileStructureProfile(
        List<String> columns,
        String delimiter,
        String controlTotalColumn,
        String notes
) {
}
