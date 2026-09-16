package com.bank.vam.service.fileingest;

import java.util.List;

// ponytail: duplicated from file-ingest-agent-service's FileStructureProfile — same
// copy-and-differentiate convention already used for JiraClient in that service; the two
// modules take no Java dependency on each other, only on FormatSignature.analysisProfileJson's
// shape (see FormatSignatureService.recordTransform, the only writer).
/** What GeneratedTransformRunner needs from the agent worker's analysis to independently
 * re-verify a transform's reconciliation, instead of trusting its own reported totals. */
public record AnalysisProfile(
        List<String> columns,
        String delimiter,
        String controlTotalColumn,
        String notes
) {
}
