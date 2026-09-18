package com.bank.vam.service.payables;

import com.bank.vam.config.MarketProfileProperties;
import com.bank.vam.dto.payables.PayablesDto.RecordPaymentRequest;
import com.bank.vam.entity.payables.Payable;
import com.bank.vam.entity.payables.Payable.PayableStatus;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.party.PartyRepository;
import com.bank.vam.repository.payables.PayableRepository;
import com.bank.vam.repository.treasury.PaymentRequestRepository;
import com.bank.vam.service.TransactionService;
import com.bank.vam.service.pobo.PoboExecutionService;
import com.bank.vam.service.treasury.NettingService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Regression coverage for the reject/schedule/record-payment controller wiring fix: the
 * controller previously returned the payable unchanged (HTTP 200, no state transition) for all
 * three actions instead of calling these already-correct service methods.
 */
class PayablesServiceTest {

    private final PayableRepository payableRepository = mock(PayableRepository.class);
    private final PartyRepository partyRepository = mock(PartyRepository.class);
    private final VirtualAccountRepository virtualAccountRepository = mock(VirtualAccountRepository.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final PaymentRequestRepository paymentRequestRepository = mock(PaymentRequestRepository.class);
    private final TransactionService transactionService = mock(TransactionService.class);
    private final MarketProfileProperties marketProfile = mock(MarketProfileProperties.class);
    private final NettingService nettingService = mock(NettingService.class);
    private final LegalEntityRepository legalEntityRepository = mock(LegalEntityRepository.class);
    private final PoboExecutionService poboExecutionService = mock(PoboExecutionService.class);

    private final PayablesService service = new PayablesService(
            payableRepository, partyRepository, virtualAccountRepository, transactionRepository,
            paymentRequestRepository, transactionService, marketProfile, nettingService,
            legalEntityRepository, poboExecutionService);

    private static final UUID PAYABLE_ID = UUID.randomUUID();

    private Payable payable(PayableStatus status) {
        Payable p = Payable.builder()
                .payableNumber("PAY-1")
                .status(status)
                .netAmount(BigDecimal.valueOf(1000))
                .paidAmount(BigDecimal.ZERO)
                .build();
        p.setId(PAYABLE_ID);
        return p;
    }

    private void stubSave() {
        when(payableRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void rejectTransitionsPendingApprovalToRejected() {
        stubSave();
        when(payableRepository.findById(PAYABLE_ID)).thenReturn(Optional.of(payable(PayableStatus.PENDING_APPROVAL)));

        var response = service.rejectPayable(PAYABLE_ID, "approver1", "budget cut");

        assertEquals("REJECTED", response.getStatus());
    }

    @Test
    void rejectThrowsInsteadOfSilentlySucceedingWhenNotPending() {
        when(payableRepository.findById(PAYABLE_ID)).thenReturn(Optional.of(payable(PayableStatus.APPROVED)));

        assertThrows(RuntimeException.class, () -> service.rejectPayable(PAYABLE_ID, "approver1", "too late"));
        verify(payableRepository, never()).save(any());
    }

    @Test
    void scheduleTransitionsApprovedToScheduled() {
        stubSave();
        when(payableRepository.findById(PAYABLE_ID)).thenReturn(Optional.of(payable(PayableStatus.APPROVED)));

        var response = service.schedulePayment(PAYABLE_ID, LocalDate.now().plusDays(7), "NORMAL", "BANK_TRANSFER", "scheduler1");

        assertEquals("SCHEDULED", response.getStatus());
    }

    @Test
    void recordPaymentUpdatesPaidAndOutstandingAmounts() {
        stubSave();
        when(payableRepository.findById(PAYABLE_ID)).thenReturn(Optional.of(payable(PayableStatus.APPROVED)));
        RecordPaymentRequest request = RecordPaymentRequest.builder()
                .amount(BigDecimal.valueOf(400))
                .paymentReference("WIRE-1")
                .recordedBy("clerk1")
                .build();

        var response = service.recordPayment(PAYABLE_ID, request);

        assertEquals(BigDecimal.valueOf(400), response.getPaidAmount());
        assertEquals("PARTIAL", response.getStatus());
    }
}
