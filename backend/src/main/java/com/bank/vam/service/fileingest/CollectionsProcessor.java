package com.bank.vam.service.fileingest;

import com.bank.vam.collections.CollectionIntake;
import com.bank.vam.collections.CollectionLine;
import com.bank.vam.collections.CollectionRun;
import com.bank.vam.collections.CollectionRunService;
import com.bank.vam.entity.fileingest.RowStatus;
import com.bank.vam.entity.fileingest.StagedTransaction;
import com.bank.vam.repository.fileingest.StagedTransactionRepository;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Live-processing stage for COLLECTIONS: every READY row becomes an invoice to collect -- the invoice its
 * {@code remittanceInformation} names, or one raised from its debtor, amount and account (see {@link CollectionIntake})
 * -- and the file's invoices go out as one collection run: direct debit where a mandate allows, Request to Pay
 * otherwise. A row is PROCESSED when its invoice was sent for collection, FAILED with the reason when it could not
 * be resolved or the run left it out.
 */
@Component
public class CollectionsProcessor implements DomainProcessor {

    private final StagedTransactionRepository repository;
    private final ReceivableInvoiceProcessor invoices;
    private final CollectionIntake intake;
    private final CollectionRunService runs;

    public CollectionsProcessor(StagedTransactionRepository repository, ReceivableInvoiceProcessor invoices,
                                CollectionIntake intake, CollectionRunService runs) {
        this.repository = repository;
        this.invoices = invoices;
        this.intake = intake;
        this.runs = runs;
    }

    @Override
    public void process(UUID ingestJobId, List<TransformedRow> rows) {
        UUID corporateId = invoices.resolveCorporateId(ingestJobId);
        Map<Integer, TransformedRow> byRowNumber = rows.stream().collect(Collectors.toMap(TransformedRow::sourceRowNumber, Function.identity()));
        List<StagedTransaction> ready = repository.findByIngestJobIdAndStatus(ingestJobId, RowStatus.READY);
        if (ready.isEmpty()) return;
        if (corporateId == null) {
            ready.forEach(s -> fail(s, "The upload is not linked to a corporate"));
            return;
        }

        List<CollectionIntake.IntakeLine> lines = ready.stream().map(s -> {
            TransformedRow r = byRowNumber.get(s.getSourceRowNumber());
            return new CollectionIntake.IntakeLine(r.remittanceInformation(), r.debtorName(), r.debtorAccount(), r.viban(),
                    r.amount(), r.currency(), null, null);
        }).toList();
        CollectionIntake.Resolution res = intake.resolveAll(corporateId, lines);
        for (Map.Entry<Integer, String> e : res.reasons().entrySet()) fail(ready.get(e.getKey() - 1), e.getValue());
        if (res.invoices().isEmpty()) return;

        CollectionRunService.RunDetail run;
        try {
            run = runs.submit(corporateId, new ArrayList<>(res.invoices().values()), CollectionRun.Source.FILE);
        } catch (RuntimeException e) {
            res.invoices().keySet().forEach(n -> fail(ready.get(n - 1), DomainProcessor.failureReason(e)));
            return;
        }
        Map<UUID, CollectionLine> byInvoice = run.lines().stream().collect(Collectors.toMap(CollectionLine::getReceivableId, Function.identity(), (a, b) -> a));
        for (Map.Entry<Integer, UUID> e : res.invoices().entrySet()) {
            StagedTransaction staged = ready.get(e.getKey() - 1);
            CollectionLine line = byInvoice.get(e.getValue());
            if (line == null || line.getStatus() == CollectionLine.Status.EXCLUDED || line.getStatus() == CollectionLine.Status.FAILED) {
                fail(staged, line == null ? "Not in the collection run" : line.getReason());
                continue;
            }
            staged.setStatus(RowStatus.PROCESSED);
            staged.setProcessedEntityId(e.getValue());
            staged.setReason(run.run().runRef() + ": " + (line.getStatus() == CollectionLine.Status.SENT
                    ? "direct debit on " + line.getCollectionDate() + " under " + line.getMandateReference()
                    : "Request to Pay sent" + (line.getReason() != null ? " (" + line.getReason() + ")" : "")));
            repository.save(staged);
        }
    }

    private void fail(StagedTransaction staged, String reason) {
        staged.setStatus(RowStatus.FAILED);
        staged.setReason(reason);
        repository.save(staged);
    }
}
