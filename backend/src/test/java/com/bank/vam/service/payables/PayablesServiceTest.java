package com.bank.vam.service.payables;

import com.bank.vam.config.MarketProfileProperties;
import com.bank.vam.dto.payables.PayablesDto.RecordPaymentRequest;
import com.bank.vam.entity.payables.Payable;
import com.bank.vam.entity.payables.Payable.PayableStatus;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.treasury.PaymentRequest;
import com.bank.vam.dto.payables.PayablesDto.PaymentExecutionRequest;
import org.mockito.ArgumentCaptor;
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
    private final com.bank.vam.service.treasury.FxRateService fxRateService = mock(com.bank.vam.service.treasury.FxRateService.class);

    private final PayablesService service = new PayablesService(
            payableRepository, partyRepository, virtualAccountRepository, transactionRepository,
            paymentRequestRepository, transactionService, marketProfile, nettingService,
            legalEntityRepository, poboExecutionService, mock(com.bank.vam.fx.deal.FxPaymentService.class),
            mock(com.bank.vam.repository.tax.ChargeConfigurationRepository.class),
            mock(com.bank.vam.repository.receivables.ReceivableRepository.class), fxRateService);

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

    /** A draft (or one awaiting approval) is not owed yet, so it cannot be netted. */
    @Test
    void aPayableNotApprovedCannotBeNetted() {
        when(payableRepository.findById(PAYABLE_ID)).thenReturn(Optional.of(payable(PayableStatus.DRAFT)));
        var request = new com.bank.vam.dto.payables.PayablesDto.AddToNettingRequest();
        request.setPayableIds(java.util.List.of(PAYABLE_ID));
        request.setNettingCycleId(UUID.randomUUID());

        var response = service.addToNettingCycle(request);

        assertEquals(0, response.getSuccessCount());
        assertFalse(response.getResults().get(0).getSuccess());
        verifyNoInteractions(nettingService);
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

    /**
     * A payment request must not be left SUBMITTED once the money has moved.
     *
     * <p>executePayment set the request to SUBMITTED *after* creating the debit transaction and then
     * never touched it again, so SUBMITTED was the terminal state for every payment request ever made
     * -- all three rows in the live database read SUBMITTED, with transactionRef and completedAt null,
     * while each of their payables was PAID in full. markCompleted, markProcessing and markRejected on
     * the entity had no callers at all.
     */
    @Test
    void executePaymentLeavesTheRequestCompletedNotSubmitted() {
        stubSave();
        Payable p = payable(PayableStatus.APPROVED);
        UUID sourceVaId = UUID.randomUUID();
        p.setVirtualAccountId(sourceVaId);
        p.setOwningEntityName("Test Entity Ltd"); // pain.001 debtor name
        when(payableRepository.findById(PAYABLE_ID)).thenReturn(Optional.of(p));

        VirtualAccount sourceVa = VirtualAccount.builder()
                .vaNumber("VA-SRC-1").currencyCode("AED")
                .currentBalance(BigDecimal.valueOf(5000))
                .availableBalance(BigDecimal.valueOf(5000))
                .heldBalance(BigDecimal.ZERO)
                .build();
        sourceVa.setId(sourceVaId);
        when(virtualAccountRepository.findById(sourceVaId)).thenReturn(Optional.of(sourceVa));

        when(paymentRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Transaction debit = Transaction.builder().referenceNumber("PMT-TEST-1").build();
        debit.setId(UUID.randomUUID());
        when(transactionService.makePayment(any())).thenReturn(debit);
        when(marketProfile.getActiveHomeBankBic()).thenReturn("HBUKGB4BXXX"); // pain.001 debtor agent

        service.executePayment(PaymentExecutionRequest.builder()
                .payableId(PAYABLE_ID)
                .executedBy("tester")
                .build());

        ArgumentCaptor<PaymentRequest> saved = ArgumentCaptor.forClass(PaymentRequest.class);
        verify(paymentRequestRepository, atLeastOnce()).save(saved.capture());
        PaymentRequest last = saved.getAllValues().get(saved.getAllValues().size() - 1);

        assertEquals(PaymentRequest.PaymentRequestStatus.COMPLETED, last.getStatus());
        // markCompleted also fills the two fields that were null on every live row
        assertEquals("PMT-TEST-1", last.getTransactionRef());
        assertNotNull(last.getCompletedAt());
    }

    /** No corporate = refused, never every corporate's payables. */
    @Test
    void searchWithoutCorporateIsRefused() {
        var request = com.bank.vam.dto.payables.PayablesDto.PayableSearchRequest.builder().page(0).size(20).build();

        assertThrows(com.bank.vam.exception.BusinessException.class, () -> service.searchPayables(request));
        verify(payableRepository, never()).findAll(any(org.springframework.data.domain.Pageable.class));
    }

    /** Stats convert each payable into the stated currency instead of adding USD to AED. */
    @Test
    void statsConvertForeignPayablesIntoTheStatedCurrency() {
        UUID corp = UUID.randomUUID();
        Payable aed = payable(PayableStatus.APPROVED);
        aed.setCurrencyCode("AED");
        Payable usd = payable(PayableStatus.APPROVED);
        usd.setCurrencyCode("USD");
        when(payableRepository.findByCorporateIdOrderByCreatedAtDesc(corp)).thenReturn(java.util.List.of(aed, usd));
        when(marketProfile.getDefaultCurrency()).thenReturn("AED");
        when(fxRateService.convert(any(), eq("AED"), eq("AED"))).thenAnswer(inv -> inv.getArgument(0));
        when(fxRateService.convert(any(), eq("USD"), eq("AED")))
                .thenAnswer(inv -> ((BigDecimal) inv.getArgument(0)).multiply(new BigDecimal("3.6725")));

        var stats = service.getStats(corp);

        assertEquals(0, new BigDecimal("4672.5").compareTo(stats.getTotalAmount()));
        assertEquals("AED", stats.getCurrencyCode());
    }

    // ---- Create Payable page: saves what it shows ----

    private static final UUID CORP = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID TREASURY = UUID.randomUUID();

    private com.bank.vam.dto.payables.PayablesDto.CreatePayableRequest.CreatePayableRequestBuilder fullRequest() {
        return com.bank.vam.dto.payables.PayablesDto.CreatePayableRequest.builder()
                .corporateId(CORP)
                .invoiceNumber("INV-9")
                .grossAmount(new BigDecimal("1050.00"))      // typed amount, VAT-inclusive
                .taxAmount(new BigDecimal("50.00"))          // the 5% VAT inside it
                .currencyCode("AED")
                .invoiceDate(LocalDate.of(2026, 11, 1))
                .dueDate(LocalDate.of(2026, 12, 1))
                .paymentTermsDays(30)
                .partyId(UUID.randomUUID())
                .vendorName("Acme")
                .partyBankAccountId(UUID.randomUUID())
                .virtualAccountId(UUID.randomUUID())
                .owningEntityId(OWNER).owningEntityCode("SUB1").owningEntityName("Sub One")
                .paymentChannel("SCHEDULED")
                .scheduledDate(LocalDate.of(2026, 11, 28))
                .paymentPriority("HIGH")
                .externalReference("PO-1 / GRN-2")
                .vendorReference("CON-3")
                .notes("check the GRN")
                .isPobo(true).paymentViaEntityId(TREASURY).paymentViaEntityCode("TC");
    }

    @Test
    void saveDraftStoresAnIncompleteDraftAsDraft() {
        stubSave();
        var request = com.bank.vam.dto.payables.PayablesDto.CreatePayableRequest.builder()
                .corporateId(CORP).status("DRAFT").build();

        var response = service.createPayable(request);

        assertEquals("DRAFT", response.getStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(response.getNetAmount()));
    }

    @Test
    void noStatusMeansDraftNotApproved() {
        stubSave();
        var response = service.createPayable(fullRequest().build());
        assertEquals("DRAFT", response.getStatus());
    }

    @Test
    void submitStoresPendingApprovalWithEverythingTheFormSent() {
        stubSave();
        var request = fullRequest().status("PENDING_APPROVAL").build();

        service.createPayable(request);

        ArgumentCaptor<Payable> saved = ArgumentCaptor.forClass(Payable.class);
        verify(payableRepository).save(saved.capture());
        Payable p = saved.getValue();
        assertEquals(PayableStatus.PENDING_APPROVAL, p.getStatus());
        // VAT not counted twice: owed = the typed amount; the VAT inside it is recorded.
        assertEquals(0, new BigDecimal("1050.00").compareTo(p.getNetAmount()));
        assertEquals(0, new BigDecimal("1050.00").compareTo(p.getOutstandingAmount()));
        assertEquals(0, new BigDecimal("50.00").compareTo(p.getTaxAmount()));
        assertEquals(request.getPartyBankAccountId(), p.getPartyBankAccountId());
        assertEquals("check the GRN", p.getNotes());
        assertEquals(Payable.PaymentPriority.HIGH, p.getPaymentPriority());
        assertEquals("PO-1 / GRN-2", p.getExternalReference());
        assertEquals("CON-3", p.getVendorReference());
        assertEquals(30, p.getPaymentTermsDays());
        assertEquals(LocalDate.of(2026, 11, 28), p.getScheduledDate());
        assertEquals(LocalDate.of(2026, 12, 1), p.getDueDate());
        assertTrue(p.getIsPobo());
        assertEquals(TREASURY, p.getPaymentViaEntityId());
        assertEquals("TC", p.getPaymentViaEntityCode());
        assertEquals("Sub One", p.getBehalfOfEntity());
        // Stays DIRECT so the POBO request / treasury approval workflow can pick it up after approval.
        assertEquals(Payable.PaymentRoute.DIRECT, p.getPaymentRoute());
        assertFalse(p.getIsIntercompany());
    }

    @Test
    void submitRefusesAnIncompletePayable() {
        var request = fullRequest().status("PENDING_APPROVAL").virtualAccountId(null).paymentViaEntityId(null).build();

        var e = assertThrows(com.bank.vam.exception.BusinessException.class, () -> service.createPayable(request));
        assertTrue(e.getMessage().contains("source account"));
        assertTrue(e.getMessage().contains("paying entity"));
        verify(payableRepository, never()).save(any());
    }

    @Test
    void createCannotSkipApproval() {
        var request = fullRequest().status("APPROVED").build();
        assertThrows(com.bank.vam.exception.BusinessException.class, () -> service.createPayable(request));
        verify(payableRepository, never()).save(any());
    }

    @Test
    void editingADraftAndSubmittingKeepsTheTypedAmountOwed() {
        stubSave();
        Payable draft = payable(PayableStatus.DRAFT);
        draft.setInvoiceNumber("INV-9");
        draft.setVendorName("Acme");
        draft.setGrossAmount(BigDecimal.ZERO);
        when(payableRepository.findById(PAYABLE_ID)).thenReturn(Optional.of(draft));
        UUID va = UUID.randomUUID();

        var response = service.updatePayable(PAYABLE_ID, com.bank.vam.dto.payables.PayablesDto.UpdatePayableRequest.builder()
                .status("PENDING_APPROVAL")
                .grossAmount(new BigDecimal("1050.00")).taxAmount(new BigDecimal("50.00"))
                .dueDate(LocalDate.of(2026, 12, 1))
                .virtualAccountId(va)
                .vendorReference("CON-3")
                .isPobo(false)
                .paymentChannel("DIRECT")
                .build());

        assertEquals("PENDING_APPROVAL", response.getStatus());
        assertEquals(0, new BigDecimal("1050.00").compareTo(response.getNetAmount()));
        assertEquals(va, draft.getVirtualAccountId());
        assertEquals("CON-3", draft.getVendorReference());
        assertFalse(draft.getIsPobo());
    }

    @Test
    void approvalNotesDoNotOverwriteTheCreatorsNotes() {
        stubSave();
        Payable pending = payable(PayableStatus.PENDING_APPROVAL);
        pending.setNotes("check the GRN");
        when(payableRepository.findById(PAYABLE_ID)).thenReturn(Optional.of(pending));

        service.approvePayable(PAYABLE_ID, "approver1", "ok to pay");

        assertEquals("check the GRN\nok to pay", pending.getNotes());
    }
}
