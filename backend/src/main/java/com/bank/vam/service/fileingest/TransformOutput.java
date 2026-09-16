package com.bank.vam.service.fileingest;

import java.math.BigDecimal;
import java.util.List;

/**
 * What any transform (hand-written, or agent-generated and run via
 * GeneratedTransformRunner) must produce: the rows themselves, plus the
 * control totals ReconciliationCheckRunner compares against the source file.
 */
public record TransformOutput(
        int sourceRowCount,
        BigDecimal sourceControlTotal,
        List<TransformedRow> rows
) {
    public int transformedRowCount() {
        return rows.size();
    }

    public BigDecimal transformedControlTotal() {
        return rows.stream().map(TransformedRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
