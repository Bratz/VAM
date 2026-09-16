package com.bank.vam.service.fileingest;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.fileingest.RowStatus;
import com.bank.vam.entity.fileingest.StagedTransaction;
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

    public PaymentsProcessor(StagedTransactionRepository repository, VirtualAccountService virtualAccountService,
                              Iso20022OutwardPaymentService outwardPaymentService) {
        this.repository = repository;
        this.virtualAccountService = virtualAccountService;
        this.outwardPaymentService = outwardPaymentService;
    }

    @Override
    public void process(UUID ingestJobId, List<TransformedRow> rows) {
        Map<Integer, TransformedRow> byRowNumber = rows.stream()
                .collect(Collectors.toMap(TransformedRow::sourceRowNumber, Function.identity()));

        List<StagedTransaction> readyRows = repository.findByIngestJobIdAndStatus(ingestJobId, RowStatus.READY);
        for (StagedTransaction staged : readyRows) {
            TransformedRow row = byRowNumber.get(staged.getSourceRowNumber());
            try {
                VirtualAccount sourceVa = virtualAccountService.getByViban(row.viban());
                OutwardPaymentRequest request = OutwardPaymentRequest.builder()
                        .sourceVaId(sourceVa.getId())
                        .amount(row.amount())
                        .currency(row.currency())
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
                staged.setReason(e.getMessage());
            }
            repository.save(staged);
        }
    }
}
