package com.bank.vam.service.fileingest;

import com.bank.vam.entity.fileingest.RowStatus;
import com.bank.vam.entity.fileingest.StagedTransaction;
import com.bank.vam.iso20022.dto.Iso20022PaymentDto.InwardPaymentRequest;
import com.bank.vam.iso20022.dto.Iso20022PaymentDto.InwardPaymentResponse;
import com.bank.vam.iso20022.service.Iso20022InwardPaymentService;
import com.bank.vam.repository.fileingest.StagedTransactionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Regression test for a field-mapping bug: this processor was passing a row's endToEndId into
 * InwardPaymentRequest.structuredRef instead of .endToEndId, so bulk-ingested transactions never
 * got Transaction.correlationId populated -- silently defeating the idempotency guard in
 * Iso20022InwardPaymentService for every bulk upload.
 */
class ReceivablesProcessorTest {

    private final StagedTransactionRepository repository = mock(StagedTransactionRepository.class);
    private final Iso20022InwardPaymentService inwardPaymentService = mock(Iso20022InwardPaymentService.class);
    private final ReceivablesProcessor processor = new ReceivablesProcessor(repository, inwardPaymentService);

    @Test
    void endToEndIdFromTheRowIsPassedAsEndToEndIdNotStructuredRef() {
        UUID jobId = UUID.randomUUID();
        TransformedRow row = new TransformedRow(1, BigDecimal.valueOf(100), "AED", "VIBAN-1",
                null, null, null, null, "some remittance info", "E2E-ROW-1");

        StagedTransaction staged = new StagedTransaction();
        staged.setSourceRowNumber(1);
        staged.setStatus(RowStatus.READY);
        when(repository.findByIngestJobIdAndStatus(jobId, RowStatus.READY)).thenReturn(List.of(staged));
        when(inwardPaymentService.processInwardPayment(any())).thenReturn(
                InwardPaymentResponse.builder().success(true).transactionId(UUID.randomUUID()).build());

        processor.process(jobId, List.of(row));

        ArgumentCaptor<InwardPaymentRequest> captor = ArgumentCaptor.forClass(InwardPaymentRequest.class);
        verify(inwardPaymentService).processInwardPayment(captor.capture());
        assertEquals("E2E-ROW-1", captor.getValue().getEndToEndId());
        assertNull(captor.getValue().getStructuredRef());
    }
}
