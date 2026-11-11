package com.bank.vam.collections;

import com.bank.vam.collections.CollectionLine.Status;
import com.bank.vam.dto.TransactionDto;
import com.bank.vam.dto.receivables.ReceivablesDto.RecordPaymentRequest;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.entity.receivables.Receivable.ReceivableStatus;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.receivables.ReceivableRepository;
import com.bank.vam.service.TransactionService;
import com.bank.vam.service.receivables.ReceivablesService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CollectionSettlementServiceTest {

    private final CollectionLineRepository lines = mock(CollectionLineRepository.class);
    private final CollectionRunRepository runs = mock(CollectionRunRepository.class);
    private final CustomerMandateRepository mandates = mock(CustomerMandateRepository.class);
    private final CollectionRunService runService = mock(CollectionRunService.class);
    private final TransactionService transactions = mock(TransactionService.class);
    private final ReceivablesService receivablesService = mock(ReceivablesService.class);
    private final ReceivableRepository receivables = mock(ReceivableRepository.class);
    private final VirtualAccountRepository accounts = mock(VirtualAccountRepository.class);
    private final com.bank.vam.fx.deal.FxPaymentService fx = mock(com.bank.vam.fx.deal.FxPaymentService.class);
    private final CollectionSettlementService service = new CollectionSettlementService(lines, runs, mandates, runService,
            transactions, receivablesService, receivables, accounts, fx, mock(com.bank.vam.repository.TransactionRepository.class),
            mock(PlatformTransactionManager.class));

    private static final LocalDate FRIDAY = LocalDate.of(2026, 11, 13);

    private final CollectionLine line = new CollectionLine();
    private final VirtualAccount account = VirtualAccount.builder().currentBalance(new BigDecimal("1000"))
            .availableBalance(new BigDecimal("1000")).heldBalance(BigDecimal.ZERO).build();
    private final Receivable invoice = new Receivable();
    private final CustomerMandate oneOff = new CustomerMandate();

    CollectionSettlementServiceTest() {
        line.setId(UUID.randomUUID());
        line.setRunId(UUID.randomUUID());
        line.setCorporateId(UUID.randomUUID());
        line.setReceivableId(UUID.randomUUID());
        line.setCreditorVaId(UUID.randomUUID());
        line.setMandateId(UUID.randomUUID());
        line.setAmount(new BigDecimal("100.00"));
        line.setCurrency("AED");
        line.setCollectionDate(FRIDAY);
        line.setEndToEndId("DD-1");
        line.setStatus(Status.SENT);
        invoice.setStatus(ReceivableStatus.OPEN);
        invoice.setOutstandingAmount(new BigDecimal("100.00"));
        oneOff.setMandateType(CustomerMandate.Type.ONE_OFF);
        oneOff.setStatus(CustomerMandate.Status.ACTIVE);

        when(lines.findById(line.getId())).thenReturn(Optional.of(line));
        when(lines.dueToRelease(any())).thenReturn(List.of());
        when(accounts.findById(line.getCreditorVaId())).thenReturn(Optional.of(account));
        when(receivables.findById(line.getReceivableId())).thenReturn(Optional.of(invoice));
        when(mandates.findById(line.getMandateId())).thenReturn(Optional.of(oneOff));
        when(runService.holdDays(line.getCorporateId())).thenReturn(5);
        when(transactions.processCollection(any())).thenAnswer(i -> {
            account.credit(new BigDecimal("98.00")); // what processCollection does to the target: gross less a 2.00 fee
            return TransactionDto.CollectionResponse.builder().netAmount(new BigDecimal("98.00"))
                    .referenceNumber("COL-1").transactionId(UUID.randomUUID()).build();
        });
    }

    @Test
    void settlesOnItsDateHoldsTheNetAndPaysTheInvoice() {
        when(lines.dueToSettle(FRIDAY)).thenReturn(List.of(line.getId()));

        CollectionSettlementService.Result r = service.process(FRIDAY);

        assertEquals(1, r.settled());
        assertEquals(Status.SETTLED, line.getStatus());
        assertEquals(CollectionSettlementService.SIMULATED, line.getReason()); // settled by date, not by a clearing answer
        assertEquals(new BigDecimal("98.00"), line.getCreditedAmount());
        assertEquals(LocalDate.of(2026, 11, 20), line.getHoldUntil()); // 5 business days after Friday
        assertEquals(new BigDecimal("98.00"), account.getHeldBalance());
        assertEquals(new BigDecimal("1000.00"), account.getAvailableBalance()); // credited and held: no change yet
        ArgumentCaptor<RecordPaymentRequest> paid = ArgumentCaptor.forClass(RecordPaymentRequest.class);
        verify(receivablesService).recordPayment(eq(line.getReceivableId()), paid.capture());
        assertEquals(new BigDecimal("100.00"), paid.getValue().getAmount()); // the invoice is paid gross
        assertEquals(CustomerMandate.Status.USED, oneOff.getStatus());
    }

    @Test
    void aCoboDebitCreditsTheTreasurysAccountAndAppliesThePaymentWithThatBankCredit() {
        // the run put the invoice's account (the treasury's collection account) on a COBO line: settlement credits it
        invoice.setIsCobo(true);
        UUID credited = UUID.randomUUID();
        when(transactions.processCollection(any())).thenReturn(TransactionDto.CollectionResponse.builder()
                .netAmount(new BigDecimal("100.00")).referenceNumber("COL-9").transactionId(credited).build());
        when(lines.dueToSettle(FRIDAY)).thenReturn(List.of(line.getId()));

        assertEquals(1, service.process(FRIDAY).settled());

        ArgumentCaptor<TransactionDto.CollectionRequest> req = ArgumentCaptor.forClass(TransactionDto.CollectionRequest.class);
        verify(transactions).processCollection(req.capture());
        assertEquals(line.getCreditorVaId(), req.getValue().getTargetVaId());
        ArgumentCaptor<RecordPaymentRequest> paid = ArgumentCaptor.forClass(RecordPaymentRequest.class);
        verify(receivablesService, times(1)).recordPayment(eq(line.getReceivableId()), paid.capture());
        assertEquals(credited, paid.getValue().getTransactionId()); // applyPayment posts the in-house credit against it
        assertEquals("DIRECT_DEBIT", paid.getValue().getMatchType());
        assertEquals(new BigDecimal("100.00"), account.getHeldBalance()); // the cash on the treasury's account is held
    }

    @Test
    void releaseMakesTheHeldMoneyAvailable() {
        line.setStatus(Status.SETTLED);
        line.setCreditedAmount(new BigDecimal("98.00"));
        account.credit(new BigDecimal("98.00"));
        account.hold(new BigDecimal("98.00"));
        when(lines.dueToSettle(any())).thenReturn(List.of());
        when(lines.dueToRelease(FRIDAY)).thenReturn(List.of(line.getId()));

        assertEquals(1, service.process(FRIDAY).released());

        assertEquals(Status.AVAILABLE, line.getStatus());
        assertEquals(0, account.getHeldBalance().signum());
        assertEquals(new BigDecimal("1098.00"), account.getAvailableBalance());
    }

    @Test
    void anInvoicePaidMeanwhileIsNotCollected() {
        invoice.setStatus(ReceivableStatus.PAID);
        invoice.setOutstandingAmount(BigDecimal.ZERO);
        when(lines.dueToSettle(FRIDAY)).thenReturn(List.of(line.getId()));

        CollectionSettlementService.Result r = service.process(FRIDAY);

        assertEquals(1, r.notCollected());
        assertEquals(Status.FAILED, line.getStatus());
        assertTrue(line.getReason().startsWith("Not collected: the invoice is PAID"), line.getReason());
        verify(transactions, never()).processCollection(any());
    }

    @Test
    void aFailedCreditStaysSentWithTheReasonForTheNextRun() {
        when(lines.dueToSettle(FRIDAY)).thenReturn(List.of(line.getId()));
        when(transactions.processCollection(any())).thenThrow(new IllegalStateException("No settlement account for AED"));

        CollectionSettlementService.Result r = service.process(FRIDAY);

        assertEquals(1, r.failed());
        assertEquals(Status.SENT, line.getStatus());
        assertEquals("Not settled yet: No settlement account for AED", line.getReason());
    }
}
