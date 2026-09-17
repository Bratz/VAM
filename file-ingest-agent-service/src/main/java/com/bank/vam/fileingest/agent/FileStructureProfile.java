package com.bank.vam.fileingest.agent;

import java.util.List;

/**
 * What the analysis agent's submit_profile tool call produces. Feeds the coding agent's task
 * brief (TransformGenerationService.buildTaskBrief) and, once a transform is recorded, is
 * serialized onto FormatSignature.analysisProfileJson (a DB column, not a filesystem artifact —
 * see FormatSignatureService.recordTransform) so backend's GeneratedTransformRunner can
 * independently re-verify reconciliation against the raw file instead of trusting the generated
 * transform's own counts.
 */
public record FileStructureProfile(
        List<String> columns,
        String delimiter,
        String controlTotalColumn,
        String notes
) {
}
