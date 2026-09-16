package com.bank.vam.service.fileingest;

import com.bank.vam.entity.fileingest.ReconciliationResult;
import com.bank.vam.repository.fileingest.ReconciliationResultRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Compares a control total extracted from the source file against what the
 * transform actually produced, before anything is allowed to leave STAGED
 * for PROCESSING.
 */
@Component
public class ReconciliationCheckRunner {

    /** Rounding-only slack — not a business tolerance for dropped/extra rows, just BigDecimal
     * scale noise. Any real mismatch (a dropped row, a misread amount) is far larger than this
     * and will still fail. */
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private final ReconciliationResultRepository repository;

    public ReconciliationCheckRunner(ReconciliationResultRepository repository) {
        this.repository = repository;
    }

    public ReconciliationResult check(UUID ingestJobId, TransformOutput output) {
        BigDecimal delta = output.sourceControlTotal()
                .subtract(output.transformedControlTotal())
                .abs()
                .setScale(4, RoundingMode.HALF_UP);
        boolean withinTolerance = delta.compareTo(TOLERANCE) <= 0
                && output.sourceRowCount() == output.transformedRowCount();

        ReconciliationResult result = new ReconciliationResult();
        result.setIngestJobId(ingestJobId);
        result.setSourceRowCount(output.sourceRowCount());
        result.setTransformedRowCount(output.transformedRowCount());
        result.setSourceControlTotal(output.sourceControlTotal());
        result.setTransformedControlTotal(output.transformedControlTotal());
        result.setDelta(delta);
        result.setWithinTolerance(withinTolerance);
        return repository.save(result);
    }
}
