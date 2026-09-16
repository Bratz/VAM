package com.bank.vam.service.fileingest;

import com.bank.vam.entity.fileingest.StagedTransaction;
import com.bank.vam.repository.fileingest.StagedTransactionRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Writes one StagedTransaction per transformed row — a dry run against a
 * staging area before touching live tables, modeled on
 * CoboReceivableService.previewCoboCollection's write-nothing-yet shape,
 * except here the "preview" itself is durable so per-row quarantine has
 * something to attach to.
 */
@Component
public class StagingDryRunRunner {

    private final StagedTransactionRepository repository;

    public StagingDryRunRunner(StagedTransactionRepository repository) {
        this.repository = repository;
    }

    public List<StagedTransaction> stage(UUID ingestJobId, TransformOutput output) {
        return output.rows().stream().map(row -> stageOne(ingestJobId, row)).toList();
    }

    private StagedTransaction stageOne(UUID ingestJobId, TransformedRow row) {
        StagedTransaction staged = new StagedTransaction();
        staged.setIngestJobId(ingestJobId);
        staged.setSourceRowNumber(row.sourceRowNumber());
        staged.setAmount(row.amount());
        staged.setCurrency(row.currency());
        staged.setTargetAccountReference(row.viban());
        return repository.save(staged);
    }
}
