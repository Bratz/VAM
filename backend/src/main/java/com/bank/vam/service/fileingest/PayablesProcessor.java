package com.bank.vam.service.fileingest;

import com.bank.vam.dto.payables.PayablesDto.CreatePayableRequest;
import com.bank.vam.dto.payables.PayablesDto.PayableResponse;
import com.bank.vam.dto.payables.PayablesDto.PaymentExecutionRequest;
import com.bank.vam.dto.payables.PayablesDto.PaymentExecutionResponse;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.fileingest.RowStatus;
import com.bank.vam.entity.fileingest.StagedTransaction;
import com.bank.vam.entity.payables.Payable;
import com.bank.vam.fx.deal.FxPaymentService;
import com.bank.vam.repository.fileingest.StagedTransactionRepository;
import com.bank.vam.service.VirtualAccountService;
import com.bank.vam.service.payables.PayablesService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Live-processing stage for Payables. Unlike Receivables, there is no
 * existing inbound file format this maps onto — every field below is the
 * generic {@link TransformedRow} shape, correctly interpreted for an
 * outbound vendor payment (the VA is the debtor here, not the creditor):
 * <ul>
 *   <li>{@code viban} — the SOURCE Virtual Account paying the vendor (the debtor).
 *   <li>{@code creditorName}/{@code creditorAccount} — the VENDOR's name/account.
 *   <li>{@code remittanceInformation} — the payable's description.
 *   <li>{@code endToEndId} — the invoice number.
 * </ul>
 * Per row, this walks the real {@code Payable} state machine in-process —
 * no human clicks submit/approve, but nothing bypasses those steps either:
 * create (DRAFT) -> submit (PENDING_APPROVAL) -> approve (APPROVED) ->
 * execute (PROCESSING, real transaction).
 *
 * <p>ponytail: dueDate defaults to +30 days — TransformedRow has no date
 * field of its own, and nothing downstream (submit/approve/execute) gates
 * on it.
 */
@Component
public class PayablesProcessor implements DomainProcessor {

    private static final String ACTOR = "file-ingest";

    private final StagedTransactionRepository repository;
    private final VirtualAccountService virtualAccountService;
    private final PayablesService payablesService;
    private final IngestFx ingestFx;

    public PayablesProcessor(StagedTransactionRepository repository, VirtualAccountService virtualAccountService,
                              PayablesService payablesService, IngestFx ingestFx) {
        this.repository = repository;
        this.virtualAccountService = virtualAccountService;
        this.payablesService = payablesService;
        this.ingestFx = ingestFx;
    }

    @Override
    public void process(UUID ingestJobId, List<TransformedRow> rows) {
        Map<Integer, TransformedRow> byRowNumber = rows.stream()
                .collect(Collectors.toMap(TransformedRow::sourceRowNumber, Function.identity()));

        List<StagedTransaction> readyRows = repository.findByIngestJobIdAndStatus(ingestJobId, RowStatus.READY);
        Map<UUID, FxPaymentService.BatchFx> fxPlan = ingestFx.plan(readyRows, byRowNumber);
        for (StagedTransaction staged : readyRows) {
            TransformedRow row = byRowNumber.get(staged.getSourceRowNumber());
            FxPaymentService.BatchFx fx = ingestFx.of(fxPlan, staged);
            if (fx.instruction() == null) {
                // Held before the payable is created, so a release creates it once, with its FX.
                ingestFx.hold(staged, row, fx.held());
                repository.save(staged);
                continue;
            }
            try {
                processOne(staged, row, fx.instruction());
            } catch (Exception e) {
                fail(staged, DomainProcessor.failureReason(e));
            }
            repository.save(staged);
        }
    }

    private void processOne(StagedTransaction staged, TransformedRow row, FxPaymentService.Instruction fx) {
        VirtualAccount sourceVa = virtualAccountService.getByViban(row.viban());

        CreatePayableRequest createRequest = CreatePayableRequest.builder()
                // The payable belongs to the corporate and entity of the account it is paid from; without them it was
                // stored with no corporate and so appeared in no corporate's payables list.
                .corporateId(sourceVa.getCorporateId())
                .owningEntityId(sourceVa.getOwningEntityId())
                .invoiceNumber(row.endToEndId())
                .grossAmount(row.amount())
                .currencyCode(row.currency())
                .dueDate(LocalDate.now().plusDays(30))
                .virtualAccountId(sourceVa.getId())
                .vendorName(row.creditorName())
                .vendorAccount(row.creditorAccount())
                .description(row.remittanceInformation())
                .build();
        PayableResponse created = payablesService.createPayable(createRequest);

        payablesService.submitForApproval(created.getId(), ACTOR);
        payablesService.approvePayable(created.getId(), ACTOR, "Auto-approved by file-ingest pipeline");

        PaymentExecutionRequest executionRequest = PaymentExecutionRequest.builder()
                .payableId(created.getId())
                // Payable.PaymentChannel / PaymentMethod names: a file is a batch, paid by bank transfer.
                // ("BANK_TRANSFER" as channel and "WIRE" as method threw in valueOf, so no ingested row paid.)
                .paymentChannel(Payable.PaymentChannel.BATCH.name())
                .paymentMethod(Payable.PaymentMethod.BANK_TRANSFER.name())
                .executedBy(ACTOR)
                // No one is at the file to choose: the deals planned over the file, else the live price.
                .fxDeals(fx.draws())
                .fxAtMarket(fx.atMarket() ? Boolean.TRUE : null)
                .build();
        PaymentExecutionResponse executed = payablesService.executePayment(executionRequest);

        if (!"EXECUTED".equals(executed.getStatus())) {
            fail(staged, executed.getErrorMessage() != null ? executed.getErrorMessage() : "Execution status: " + executed.getStatus());
            return;
        }
        staged.setStatus(RowStatus.PROCESSED);
        staged.setProcessedEntityId(created.getId());
        staged.setReason(null);
    }

    private void fail(StagedTransaction staged, String reason) {
        staged.setStatus(RowStatus.FAILED);
        staged.setReason(reason);
    }
}
