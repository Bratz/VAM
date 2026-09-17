package com.bank.vam.iso20022.service;

import com.bank.vam.dto.TransactionDto;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.viban.Viban;
import com.bank.vam.iso20022.dto.Iso20022PaymentDto.InwardPaymentRequest;
import com.bank.vam.iso20022.dto.Iso20022PaymentDto.InwardPaymentResponse;
import com.bank.vam.iso20022.mapper.Iso20022MessageMapper;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.viban.VibanRepository;
import com.bank.vam.service.TransactionService;
import com.bank.vam.service.receivables.ReconciliationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Regression coverage for the idempotency guard: a re-delivered pacs.008/camt.054 message or a
 * re-uploaded bulk-ingest row must not post the same 4-leg collection twice. Also covers wiring
 * to ReconciliationService -- a VIBAN-routed payment must attempt real reconciliation so its
 * matching Receivable actually gets marked paid, not just the ledger posting.
 */
class Iso20022InwardPaymentServiceTest {

    private final VibanRepository vibanRepository = mock(VibanRepository.class);
    private final VirtualAccountRepository virtualAccountRepository = mock(VirtualAccountRepository.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final TransactionService transactionService = mock(TransactionService.class);
    private final Iso20022MessageMapper messageMapper = mock(Iso20022MessageMapper.class);
    private final ReconciliationService reconciliationService = mock(ReconciliationService.class);

    private final Iso20022InwardPaymentService service = new Iso20022InwardPaymentService(
            vibanRepository, virtualAccountRepository, transactionRepository, transactionService, messageMapper,
            reconciliationService);

    private static final UUID VA_ID = UUID.randomUUID();

    private VirtualAccount activeVa() {
        VirtualAccount va = VirtualAccount.builder()
                .vaNumber("VA-1000")
                .status(VirtualAccount.VaStatus.ACTIVE)
                .currentBalance(BigDecimal.valueOf(500))
                .build();
        va.setId(VA_ID);
        return va;
    }

    private InwardPaymentRequest request(String endToEndId, BigDecimal amount) {
        return InwardPaymentRequest.builder()
                .amount(amount)
                .currency("AED")
                .creditorAccount("VA-1000")
                .endToEndId(endToEndId)
                .build();
    }

    private void stubAccountLookup() {
        when(vibanRepository.findByViban(any())).thenReturn(Optional.empty());
        when(virtualAccountRepository.findByVaNumber("VA-1000")).thenReturn(Optional.of(activeVa()));
    }

    private void stubSuccessfulCollection() {
        when(transactionService.processCollection(any())).thenReturn(TransactionDto.CollectionResponse.builder()
                .transactionId(UUID.randomUUID())
                .referenceNumber("REF-1")
                .targetBalanceBefore(BigDecimal.valueOf(500))
                .targetBalanceAfter(BigDecimal.valueOf(600))
                .build());
    }

    @Test
    void firstCallProcessesNormally() {
        stubAccountLookup();
        stubSuccessfulCollection();
        when(transactionRepository.findAllByCorrelationId("E2E-1")).thenReturn(List.of());

        InwardPaymentResponse response = service.processInwardPayment(request("E2E-1", BigDecimal.valueOf(100)));

        assertTrue(response.isSuccess());
        verify(transactionService, times(1)).processCollection(any());
    }

    @Test
    void repeatedEndToEndIdSameAccountAndAmountIsSuppressedAsDuplicate() {
        stubAccountLookup();

        Transaction existing = Transaction.builder()
                .vaId(VA_ID)
                .amount(BigDecimal.valueOf(100))
                .currencyCode("AED")
                .referenceNumber("REF-1")
                .build();
        existing.setId(UUID.randomUUID());
        when(transactionRepository.findAllByCorrelationId("E2E-1")).thenReturn(List.of(existing));

        InwardPaymentResponse response = service.processInwardPayment(request("E2E-1", BigDecimal.valueOf(100)));

        assertTrue(response.isSuccess());
        assertEquals("REF-1", response.getTransactionReference());
        verify(transactionService, never()).processCollection(any());
    }

    @Test
    void repeatedEndToEndIdDifferentAmountIsNotTreatedAsDuplicate() {
        stubAccountLookup();
        stubSuccessfulCollection();

        Transaction existing = Transaction.builder()
                .vaId(VA_ID)
                .amount(BigDecimal.valueOf(100))
                .currencyCode("AED")
                .referenceNumber("REF-1")
                .build();
        existing.setId(UUID.randomUUID());
        when(transactionRepository.findAllByCorrelationId("E2E-1")).thenReturn(List.of(existing));

        // Same endToEndId, different amount than the existing transaction.
        InwardPaymentResponse response = service.processInwardPayment(request("E2E-1", BigDecimal.valueOf(250)));

        assertTrue(response.isSuccess());
        verify(transactionService, times(1)).processCollection(any());
    }

    @Test
    void blankEndToEndIdSkipsTheDuplicateLookupEntirely() {
        stubAccountLookup();
        stubSuccessfulCollection();

        service.processInwardPayment(request(null, BigDecimal.valueOf(100)));

        verify(transactionRepository, never()).findAllByCorrelationId(any());
        verify(transactionService, times(1)).processCollection(any());
    }

    private static final UUID VIBAN_ID = UUID.randomUUID();
    private static final UUID RECEIVABLE_ID = UUID.randomUUID();

    private Viban activeViban() {
        Viban viban = Viban.builder()
                .viban("GB830410000000000000660643")
                .virtualAccountId(VA_ID)
                .status(Viban.STATUS_ACTIVE)
                .build();
        viban.setId(VIBAN_ID);
        return viban;
    }

    private InwardPaymentRequest vibanRequest(BigDecimal amount) {
        return InwardPaymentRequest.builder()
                .amount(amount)
                .currency("AED")
                .creditorAccount("GB830410000000000000660643")
                .debtorName("Acme Corp")
                .debtorAccount("DEBTOR-ACCT-1")
                .remittanceInfo("Invoice payment")
                .build();
    }

    @Test
    void vibanRoutedPaymentAttemptsRealReconciliation() {
        when(vibanRepository.findByViban("GB830410000000000000660643")).thenReturn(Optional.of(activeViban()));
        when(virtualAccountRepository.findById(VA_ID)).thenReturn(Optional.of(activeVa()));
        stubSuccessfulCollection();

        UUID transactionId = UUID.randomUUID();
        when(transactionService.processCollection(any())).thenReturn(TransactionDto.CollectionResponse.builder()
                .transactionId(transactionId)
                .referenceNumber("REF-2")
                .targetBalanceBefore(BigDecimal.valueOf(500))
                .targetBalanceAfter(BigDecimal.valueOf(600))
                .build());
        when(reconciliationService.attemptAutoReconcile(eq(transactionId), eq(VIBAN_ID), eq(BigDecimal.valueOf(100)),
                eq("AED"), eq("Acme Corp"), eq("DEBTOR-ACCT-1"), eq("Invoice payment")))
                .thenReturn(ReconciliationService.ReconciliationResult.builder()
                        .success(true)
                        .matchType(ReconciliationService.MatchType.DIRECT)
                        .receivableId(RECEIVABLE_ID)
                        .build());

        InwardPaymentResponse response = service.processInwardPayment(vibanRequest(BigDecimal.valueOf(100)));

        assertTrue(response.isSuccess());
        assertTrue(response.isAutoReconciled());
        assertEquals("DIRECT", response.getReconciledReferenceType());
        assertEquals(RECEIVABLE_ID.toString(), response.getReconciledReferenceId());
        verify(reconciliationService).attemptAutoReconcile(eq(transactionId), eq(VIBAN_ID), any(), any(), any(), any(), any());
    }

    @Test
    void vaNumberRoutedPaymentNeverAttemptsReconciliation() {
        stubAccountLookup();
        stubSuccessfulCollection();

        service.processInwardPayment(request("E2E-VA-DIRECT", BigDecimal.valueOf(100)));

        verify(reconciliationService, never()).attemptAutoReconcile(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void unmatchedReconciliationLeavesResponseSuccessfulButNotAutoReconciled() {
        when(vibanRepository.findByViban("GB830410000000000000660643")).thenReturn(Optional.of(activeViban()));
        when(virtualAccountRepository.findById(VA_ID)).thenReturn(Optional.of(activeVa()));
        stubSuccessfulCollection();
        when(reconciliationService.attemptAutoReconcile(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ReconciliationService.ReconciliationResult.builder()
                        .success(false)
                        .matchType(ReconciliationService.MatchType.NONE)
                        .build());

        InwardPaymentResponse response = service.processInwardPayment(vibanRequest(BigDecimal.valueOf(100)));

        assertTrue(response.isSuccess());
        assertFalse(response.isAutoReconciled());
        assertNull(response.getReconciledReferenceId());
    }
}
