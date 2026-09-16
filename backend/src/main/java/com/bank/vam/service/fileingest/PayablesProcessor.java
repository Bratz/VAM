package com.bank.vam.service.fileingest;

import com.bank.vam.dto.payables.PayablesDto.CreatePayableRequest;
import com.bank.vam.dto.payables.PayablesDto.PayableResponse;
import com.bank.vam.dto.payables.PayablesDto.PaymentExecutionRequest;
import com.bank.vam.dto.payables.PayablesDto.PaymentExecutionResponse;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.fileingest.RowStatus;
import com.bank.vam.entity.fileingest.StagedTransaction;
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
 * generic {@link TransformedRow} shape (built for Receivables' debtor/
 * creditor language) reinterpreted for an outbound vendor payment:
 * <ul>
 *   <li>{@code viban} — the SOURCE Virtual Account paying the vendor.
 *   <li>{@code debtorName}/{@code debtorAccount} — reused to carry the
 *       VENDOR's name/account, the "other party" in this row.
 *   <li>{@code remittanceInfo} — the payable's description.
 *   <li>{@code reference} — the invoice number.
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

    public PayablesProcessor(StagedTransactionRepository repository, VirtualAccountService virtualAccountService,
                              PayablesService payablesService) {
        this.repository = repository;
        this.virtualAccountService = virtualAccountService;
        this.payablesService = payablesService;
    }

    @Override
    public void process(UUID ingestJobId, List<TransformedRow> rows) {
        Map<Integer, TransformedRow> byRowNumber = rows.stream()
                .collect(Collectors.toMap(TransformedRow::sourceRowNumber, Function.identity()));

        List<StagedTransaction> readyRows = repository.findByIngestJobIdAndStatus(ingestJobId, RowStatus.READY);
        for (StagedTransaction staged : readyRows) {
            TransformedRow row = byRowNumber.get(staged.getSourceRowNumber());
            try {
                processOne(staged, row);
            } catch (Exception e) {
                fail(staged, e.getMessage());
            }
            repository.save(staged);
        }
    }

    private void processOne(StagedTransaction staged, TransformedRow row) {
        VirtualAccount sourceVa = virtualAccountService.getByViban(row.viban());

        CreatePayableRequest createRequest = CreatePayableRequest.builder()
                .invoiceNumber(row.reference())
                .grossAmount(row.amount())
                .currencyCode(row.currency())
                .dueDate(LocalDate.now().plusDays(30))
                .virtualAccountId(sourceVa.getId())
                .vendorName(row.debtorName())
                .vendorAccount(row.debtorAccount())
                .description(row.remittanceInfo())
                .build();
        PayableResponse created = payablesService.createPayable(createRequest);

        payablesService.submitForApproval(created.getId(), ACTOR);
        payablesService.approvePayable(created.getId(), ACTOR, "Auto-approved by file-ingest pipeline");

        PaymentExecutionRequest executionRequest = PaymentExecutionRequest.builder()
                .payableId(created.getId())
                .paymentChannel("BANK_TRANSFER")
                .paymentMethod("WIRE")
                .executedBy(ACTOR)
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
