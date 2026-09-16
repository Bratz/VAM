package com.bank.vam.service.fileingest;

import com.bank.vam.entity.fileingest.RowStatus;
import com.bank.vam.entity.fileingest.StagedTransaction;
import com.bank.vam.iso20022.dto.Iso20022PaymentDto.InwardPaymentRequest;
import com.bank.vam.iso20022.dto.Iso20022PaymentDto.InwardPaymentResponse;
import com.bank.vam.iso20022.service.Iso20022InwardPaymentService;
import com.bank.vam.repository.fileingest.StagedTransactionRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Live-processing stage for Receivables: for every READY row, calls the real
 * Iso20022InwardPaymentService in-process (same JVM now — no more HTTP to
 * itself) exactly the way parseCamt054() would have. A row failing here is
 * FAILED, not a hard stop for its siblings — same "don't block the whole
 * file" rule as quarantine.
 */
@Component
public class ReceivablesProcessor implements DomainProcessor {

    private final StagedTransactionRepository repository;
    private final Iso20022InwardPaymentService inwardPaymentService;

    public ReceivablesProcessor(StagedTransactionRepository repository, Iso20022InwardPaymentService inwardPaymentService) {
        this.repository = repository;
        this.inwardPaymentService = inwardPaymentService;
    }

    @Override
    public void process(UUID ingestJobId, List<TransformedRow> rows) {
        Map<Integer, TransformedRow> byRowNumber = rows.stream()
                .collect(Collectors.toMap(TransformedRow::sourceRowNumber, Function.identity()));

        List<StagedTransaction> readyRows = repository.findByIngestJobIdAndStatus(ingestJobId, RowStatus.READY);
        for (StagedTransaction staged : readyRows) {
            TransformedRow row = byRowNumber.get(staged.getSourceRowNumber());
            try {
                InwardPaymentRequest request = InwardPaymentRequest.builder()
                        .amount(row.amount())
                        .currency(row.currency())
                        .creditorAccount(row.viban())
                        .debtorName(row.debtorName())
                        .debtorAccount(row.debtorAccount())
                        .remittanceInfo(row.remittanceInformation())
                        .structuredRef(row.endToEndId())
                        .channel("FILE-INGEST")
                        .build();
                InwardPaymentResponse response = inwardPaymentService.processInwardPayment(request);
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
