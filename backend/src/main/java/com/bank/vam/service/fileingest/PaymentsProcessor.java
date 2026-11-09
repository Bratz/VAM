package com.bank.vam.service.fileingest;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.fileingest.RowStatus;
import com.bank.vam.entity.fileingest.StagedTransaction;
import com.bank.vam.fx.deal.FxPaymentService;
import com.bank.vam.iso20022.dto.Iso20022PaymentDto.OutwardPaymentRequest;
import com.bank.vam.iso20022.dto.Iso20022PaymentDto.OutwardPaymentResponse;
import com.bank.vam.iso20022.service.Iso20022OutwardPaymentService;
import com.bank.vam.repository.fileingest.StagedTransactionRepository;
import com.bank.vam.service.VirtualAccountService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Live-processing stage for Payments — same debtor/creditor convention as
 * {@link PayablesProcessor}, but simpler: no state machine to walk, just a
 * direct outward payment per row via the real Iso20022OutwardPaymentService.
 * {@code viban} is the source VA paying out (the debtor); {@code creditorName}/
 * {@code creditorAccount} carry the beneficiary.
 */
@Component
public class PaymentsProcessor implements DomainProcessor {

    private final StagedTransactionRepository repository;
    private final VirtualAccountService virtualAccountService;
    private final Iso20022OutwardPaymentService outwardPaymentService;
    private final IngestFx ingestFx;

    public PaymentsProcessor(StagedTransactionRepository repository, VirtualAccountService virtualAccountService,
                              Iso20022OutwardPaymentService outwardPaymentService, IngestFx ingestFx) {
        this.repository = repository;
        this.virtualAccountService = virtualAccountService;
        this.outwardPaymentService = outwardPaymentService;
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
                ingestFx.hold(staged, row, fx.held());
                repository.save(staged);
                continue;
            }
            try {
                VirtualAccount sourceVa = virtualAccountService.getByViban(row.viban());
                OutwardPaymentRequest request = OutwardPaymentRequest.builder()
                        .sourceVaId(sourceVa.getId())
                        .amount(row.amount())
                        .currency(row.currency())
                        // No one is at the file to choose: the deals planned over the file, else the live price.
                        .fxDeals(fx.instruction().draws())
                        .fxAtMarket(fx.instruction().atMarket() ? Boolean.TRUE : null)
                        .creditorName(row.creditorName())
                        .creditorAccount(row.creditorAccount())
                        .remittanceInfo(row.remittanceInformation())
                        .structuredRef(row.endToEndId())
                        .build();
                OutwardPaymentResponse response = outwardPaymentService.processOutwardPayment(request);
                if (response.isSuccess()) {
                    staged.setStatus(RowStatus.PROCESSED);
                    staged.setProcessedEntityId(response.getTransactionId());
                    staged.setReason(null);
                } else {
                    staged.setStatus(RowStatus.FAILED);
                    staged.setReason(response.getErrorMessage());
                }
            } catch (Exception e) {
                staged.setStatus(RowStatus.FAILED);
                staged.setReason(DomainProcessor.failureReason(e));
            }
            repository.save(staged);
        }
    }
}
