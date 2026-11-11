package com.bank.vam.service.receivables;

import com.bank.vam.dto.receivables.ReceivablesDto.CreateInvoiceRequest;
import com.bank.vam.dto.receivables.ReceivablesDto.InvoiceResponse;
import com.bank.vam.dto.receivables.ReceivablesDto.PublicInvoiceResponse;
import com.bank.vam.dto.viban.VibanDto.VibanCreateRequest;
import com.bank.vam.dto.viban.VibanDto.VibanResponse;
import com.bank.vam.entity.Corporate;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.CorporateRepository;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.receivables.ReceivableRepository;
import com.bank.vam.repository.receivables.UnmatchedPaymentRepository;
import com.bank.vam.dto.receivables.ReceivablesDto.MatchPaymentRequest;
import com.bank.vam.dto.receivables.ReceivablesDto.ReleaseEscrowRequest;
import com.bank.vam.repository.treasury.ExceptionTransactionRepository;
import com.bank.vam.repository.viban.VibanRepository;
import com.bank.vam.service.TransactionService;
import com.bank.vam.service.treasury.SettlementVaResolverService;
import com.bank.vam.service.viban.VibanService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Regression coverage for wiring createInvoice()'s "Generate VIBAN" option to the real
 * VibanService instead of a fabricated, never-persisted string, and for the real payment-link
 * token/URL every invoice now gets.
 */
class ReceivablesServiceTest {

    private final VirtualAccountRepository virtualAccountRepository = mock(VirtualAccountRepository.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final VibanRepository vibanRepository = mock(VibanRepository.class);
    private final ReceivableRepository receivableRepository = mock(ReceivableRepository.class);
    private final VibanService vibanService = mock(VibanService.class);
    private final TransactionService transactionService = mock(TransactionService.class);
    private final SettlementVaResolverService settlementVaResolver = mock(SettlementVaResolverService.class);
    private final ExceptionTransactionRepository exceptionTransactionRepository = mock(ExceptionTransactionRepository.class);
    private final CorporateRepository corporateRepository = mock(CorporateRepository.class);

    private final CoboEnrolmentService coboEnrolments = mock(CoboEnrolmentService.class);
    private final com.bank.vam.outbox.OutboxEmailRepository outbox = mock(com.bank.vam.outbox.OutboxEmailRepository.class);
    private final UnmatchedPaymentRepository unmatchedPaymentRepository = mock(UnmatchedPaymentRepository.class);
    private final ReconciliationService reconciliationService = mock(ReconciliationService.class);

    private final ReceivablesService service = new ReceivablesService(
            virtualAccountRepository, transactionRepository, vibanRepository, receivableRepository,
            vibanService, corporateRepository, transactionService, settlementVaResolver,
            exceptionTransactionRepository, coboEnrolments, outbox, unmatchedPaymentRepository, reconciliationService);

    {
        ReflectionTestUtils.setField(service, "frontendBaseUrl", "http://test.local");
    }

    private static final UUID CORPORATE_ID = UUID.randomUUID();
    private static final UUID TARGET_VA_ID = UUID.randomUUID();
    private static final UUID PROGRAM_ID = UUID.randomUUID();

    private CreateInvoiceRequest.CreateInvoiceRequestBuilder baseRequest() {
        return CreateInvoiceRequest.builder()
                .customerName("Acme Corp")
                .amount(BigDecimal.valueOf(500))
                .currencyCode("AED")
                .dueDate(LocalDate.now().plusDays(30));
    }

    /** Simulates Hibernate assigning the id on first save -- createInvoice() now saves the
     * Receivable before generating its VIBAN specifically so a real id exists to embed. */
    private void stubSaveAssignsId() {
        when(receivableRepository.save(any())).thenAnswer(inv -> {
            Receivable r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });
    }

    @Test
    void generateVibanWithATargetAccountCallsTheRealVibanService() {
        VirtualAccount va = VirtualAccount.builder().vaNumber("VA-1").programId(PROGRAM_ID).build();
        va.setId(TARGET_VA_ID);
        when(virtualAccountRepository.findById(TARGET_VA_ID)).thenReturn(Optional.of(va));
        stubSaveAssignsId();

        UUID realVibanId = UUID.randomUUID();
        when(vibanService.createViban(eq(PROGRAM_ID), any(VibanCreateRequest.class)))
                .thenReturn(VibanResponse.builder().id(realVibanId).viban("AE-REAL-VIBAN-123").build());

        InvoiceResponse response = service.createInvoice(CORPORATE_ID, baseRequest()
                .createViban(true)
                .targetVaId(TARGET_VA_ID)
                .build());

        assertEquals("AE-REAL-VIBAN-123", response.getViban());
        assertEquals(realVibanId, response.getVibanId());

        // createViban(programId, request), not the createInvoiceViban(...) helper: that helper's
        // fixed parameter list has no room for customerName, which is why every invoice-created
        // VIBAN used to show a blank customer in the VIBANs tab.
        ArgumentCaptor<VibanCreateRequest> vibanRequest = ArgumentCaptor.forClass(VibanCreateRequest.class);
        verify(vibanService).createViban(eq(PROGRAM_ID), vibanRequest.capture());
        VibanCreateRequest sent = vibanRequest.getValue();

        assertEquals(TARGET_VA_ID, sent.getVirtualAccountId());
        assertEquals(BigDecimal.valueOf(500), sent.getExpectedAmount());
        // referenceId must be the receivable's own real id (a UUID string) --
        // ReconciliationService.attemptDirectMatch() parses it back as one.
        assertDoesNotThrow(() -> UUID.fromString(sent.getReferenceId()));
        assertEquals(response.getId().toString(), sent.getReferenceId());
        // The whole reason this call moved off the helper:
        assertEquals("Acme Corp", sent.getCustomerName());
    }

    @Test
    void generateVibanWithoutATargetAccountThrowsInsteadOfFabricatingData() {
        stubSaveAssignsId();
        CreateInvoiceRequest request = baseRequest().createViban(true).build();

        assertThrows(BusinessException.class, () -> service.createInvoice(CORPORATE_ID, request));
        verify(vibanService, never()).createViban(any(), any());
    }

    @Test
    void noVibanRequestedLeavesVibanFieldsNull() {
        stubSaveAssignsId();

        InvoiceResponse response = service.createInvoice(CORPORATE_ID, baseRequest().build());

        assertNull(response.getViban());
        assertNull(response.getVibanId());
        verify(vibanService, never()).createViban(any(), any());
    }

    @Test
    void everyInvoiceGetsARealPaymentLinkRegardlessOfViban() {
        stubSaveAssignsId();
        ArgumentCaptor<Receivable> saved = ArgumentCaptor.forClass(Receivable.class);

        InvoiceResponse response = service.createInvoice(CORPORATE_ID, baseRequest().build());

        verify(receivableRepository, atLeastOnce()).save(saved.capture());
        assertNotNull(saved.getValue().getPaymentLinkToken());
        assertTrue(response.getPaymentLink().startsWith("http://test.local/?page=pay-invoice&token="));
        assertTrue(response.getPaymentLink().endsWith(saved.getValue().getPaymentLinkToken()));
    }

    /** What the form collects is stored: its number, date, terms, reference, email, VAT and the rest as metadata. */
    @Test
    void theInvoiceKeepsWhatTheFormCollected() {
        stubSaveAssignsId();
        LocalDate issued = LocalDate.now().minusDays(2);

        InvoiceResponse response = service.createInvoice(CORPORATE_ID, baseRequest()
                .invoiceNumber(" INV-TYPED-1 ").issueDate(issued).paymentTermsDays(30)
                .externalReference("PO-77").customerEmail("ap@acme.example")
                .taxAmount(new BigDecimal("23.81")).discountAmount(BigDecimal.ONE)
                .notes("Thank you").metadata("{\"lineItems\":[]}").build());

        ArgumentCaptor<Receivable> saved = ArgumentCaptor.forClass(Receivable.class);
        verify(receivableRepository, atLeastOnce()).save(saved.capture());
        Receivable r = saved.getValue();
        assertEquals("INV-TYPED-1", response.getInvoiceNumber());
        assertEquals(issued, r.getIssueDate());
        assertEquals(30, r.getPaymentTermsDays());
        assertEquals("PO-77", r.getExternalReference());
        assertEquals("ap@acme.example", r.getCustomerEmail());
        assertEquals(new BigDecimal("23.81"), r.getTaxAmount());
        assertEquals(BigDecimal.valueOf(500), r.getNetAmount());
        assertEquals("Thank you", r.getNotes());
        assertEquals("{\"lineItems\":[]}", r.getMetadata());
        assertEquals(Receivable.ReceivableStatus.OPEN, r.getStatus());
    }

    @Test
    void aTypedNumberAlreadyUsedByTheCorporateIsRefused() {
        UUID corporateId = UUID.randomUUID();
        when(receivableRepository.existsByCorporateIdAndReceivableNumber(corporateId, "INV-1")).thenReturn(true);

        assertThrows(BusinessException.class,
                () -> service.createInvoice(corporateId, baseRequest().invoiceNumber("INV-1").build()));
        verify(receivableRepository, never()).save(any());
    }

    /** A draft is stored as DRAFT and nothing goes out: no email. */
    @Test
    void aDraftIsKeptAsADraftAndNotSent() {
        stubSaveAssignsId();

        service.createInvoice(CORPORATE_ID, baseRequest().draft(true).sendInvoiceEmail(true).build());

        ArgumentCaptor<Receivable> saved = ArgumentCaptor.forClass(Receivable.class);
        verify(receivableRepository, atLeastOnce()).save(saved.capture());
        assertEquals(Receivable.ReceivableStatus.DRAFT, saved.getValue().getStatus());
        verifyNoInteractions(outbox);
    }

    // --- COBO by enrolment (spec D3, D5, R5, R6) ---

    private static final UUID SUBSIDIARY = UUID.randomUUID();
    private static final UUID TREASURY = UUID.randomUUID();
    private static final UUID TREASURY_VA = UUID.randomUUID();
    private static final UUID ENROLMENT = UUID.randomUUID();
    private static final UUID IHB_ACCOUNT = UUID.randomUUID();

    /** SUB-X is enrolled for AED: collected by TC-1 into TREASURY_VA. */
    private void enrolSubsidiaryForAed() {
        when(coboEnrolments.active(eq(SUBSIDIARY), eq("AED"), any())).thenReturn(Optional.of(
            new CoboEnrolmentService.EnrolmentView(ENROLMENT, CORPORATE_ID, SUBSIDIARY, "SUB-X", "Sub X LLC", "AED",
                TREASURY, "TC-1", "Treasury Centre LLC", TREASURY_VA, "VA-T-AED", "Treasury collections",
                IHB_ACCOUNT, "VA-X-IHB", "AED", LocalDate.now(), null, true, "tester", null)));
        VirtualAccount treasuryVa = VirtualAccount.builder().vaNumber("VA-T-AED").programId(PROGRAM_ID).build();
        treasuryVa.setId(TREASURY_VA);
        when(virtualAccountRepository.findById(TREASURY_VA)).thenReturn(Optional.of(treasuryVa));
        when(vibanService.createViban(eq(PROGRAM_ID), any(VibanCreateRequest.class)))
            .thenReturn(VibanResponse.builder().id(UUID.randomUUID()).viban("AE-T-VIBAN-1").build());
    }

    private Receivable lastSaved() {
        ArgumentCaptor<Receivable> saved = ArgumentCaptor.forClass(Receivable.class);
        verify(receivableRepository, atLeastOnce()).save(saved.capture());
        return saved.getValue();
    }

    @Test
    void anEnrolledSubsidiarysInvoiceIsCollectedByTreasuryIntoTheEnrolmentAccountWithItsVibanThere() {
        stubSaveAssignsId();
        enrolSubsidiaryForAed();

        // The client's account choice and lack of a VIBAN request don't matter: the enrolment decides
        InvoiceResponse response = service.createInvoice(CORPORATE_ID, baseRequest().owningEntityId(SUBSIDIARY)
            .targetVaId(TARGET_VA_ID).customerEmail("ap@acme.example").sendInvoiceEmail(true).build());

        Receivable r = lastSaved();
        assertTrue(r.getIsCobo());
        assertEquals(Receivable.CollectionRoute.COBO, r.getCollectionRoute());
        assertEquals(TREASURY, r.getCoboCollectorEntityId());
        assertEquals("TC-1", r.getCoboCollectorEntityCode());
        assertEquals(Receivable.CoboRequestStatus.APPROVED, r.getCoboRequestStatus());
        assertEquals(ENROLMENT, r.getCoboRequestId());
        assertEquals(TREASURY_VA, r.getVirtualAccountId());
        assertEquals(Receivable.ReceivableStatus.OPEN, r.getStatus());

        ArgumentCaptor<VibanCreateRequest> viban = ArgumentCaptor.forClass(VibanCreateRequest.class);
        verify(vibanService).createViban(eq(PROGRAM_ID), viban.capture());
        assertEquals(TREASURY_VA, viban.getValue().getVirtualAccountId());
        assertEquals("Treasury Centre LLC re: Sub X LLC", viban.getValue().getCustomerName());
        assertEquals("AE-T-VIBAN-1", response.getViban());

        ArgumentCaptor<com.bank.vam.outbox.OutboxEmail> mail = ArgumentCaptor.forClass(com.bank.vam.outbox.OutboxEmail.class);
        verify(outbox).save(mail.capture());
        assertTrue(mail.getValue().getBody().contains("to IBAN AE-T-VIBAN-1 (Treasury Centre LLC re: Sub X LLC)"));
        assertTrue(mail.getValue().getBody().contains("Pay online: http://test.local/?page=pay-invoice&token="));
    }

    @Test
    void collectDirectlyOptsTheInvoiceOutOfCobo() {
        stubSaveAssignsId();
        enrolSubsidiaryForAed();

        service.createInvoice(CORPORATE_ID, baseRequest().owningEntityId(SUBSIDIARY).targetVaId(TARGET_VA_ID)
            .collectDirectly(true).build());

        Receivable r = lastSaved();
        assertFalse(r.getIsCobo());
        assertEquals(Receivable.CollectionRoute.DIRECT, r.getCollectionRoute());
        assertNull(r.getCoboCollectorEntityId());
        assertEquals(TARGET_VA_ID, r.getVirtualAccountId());
        verify(coboEnrolments, never()).active(any(), any(), any());
        verify(vibanService, never()).createViban(any(), any());
    }

    @Test
    void anInvoiceInACurrencyTheSubsidiaryIsNotEnrolledForIsCollectedDirectly() {
        stubSaveAssignsId();
        enrolSubsidiaryForAed();

        service.createInvoice(CORPORATE_ID, baseRequest().owningEntityId(SUBSIDIARY).currencyCode("USD")
            .targetVaId(TARGET_VA_ID).build());

        Receivable r = lastSaved();
        assertFalse(r.getIsCobo());
        assertEquals(TARGET_VA_ID, r.getVirtualAccountId());
        verify(coboEnrolments).active(eq(SUBSIDIARY), eq("USD"), any());
        verify(vibanService, never()).createViban(any(), any());
    }

    /** It used to answer with four made-up demo invoices, the same under every such corporate. */
    @Test
    void aCorporateWithNoInvoicesGetsNone() {
        UUID corporateId = UUID.randomUUID();
        when(receivableRepository.findByCorporateId(corporateId)).thenReturn(java.util.List.of());

        assertTrue(service.getAllInvoices(corporateId, null, null).isEmpty());
    }

    @Test
    void issuingADraftOpensIt_andOnlyADraftWithAnAmount() {
        Receivable draft = Receivable.builder().receivableNumber("INV-D").status(Receivable.ReceivableStatus.DRAFT)
                .netAmount(BigDecimal.TEN).grossAmount(BigDecimal.TEN).outstandingAmount(BigDecimal.TEN)
                .paidAmount(BigDecimal.ZERO).currencyCode("AED").build();
        draft.setId(UUID.randomUUID());
        when(receivableRepository.findById(draft.getId())).thenReturn(Optional.of(draft));
        when(receivableRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertEquals("OPEN", service.issueDraft(draft.getId()).getStatus());
        // Issued now: a second issue is refused.
        assertThrows(BusinessException.class, () -> service.issueDraft(draft.getId()));

        Receivable empty = Receivable.builder().receivableNumber("INV-E").status(Receivable.ReceivableStatus.DRAFT)
                .netAmount(BigDecimal.ZERO).build();
        empty.setId(UUID.randomUUID());
        when(receivableRepository.findById(empty.getId())).thenReturn(Optional.of(empty));
        assertThrows(BusinessException.class, () -> service.issueDraft(empty.getId()));
    }

    @Test
    void publicLookupByTokenReturnsOnlyPayerFacingFields() {
        UUID corporateId = UUID.randomUUID();
        Receivable r = Receivable.builder()
                .receivableNumber("INV-2026-0001")
                .corporateId(corporateId)
                .grossAmount(BigDecimal.valueOf(500))
                .outstandingAmount(BigDecimal.valueOf(500))
                .currencyCode("AED")
                .dueDate(LocalDate.now().plusDays(30))
                .status(Receivable.ReceivableStatus.OPEN)
                .viban("GB830410000000000000660643")
                .paymentLinkToken("real-token")
                .build();
        when(receivableRepository.findByPaymentLinkToken("real-token")).thenReturn(Optional.of(r));

        Corporate corporate = new Corporate();
        corporate.setLegalName("Test Multinational Corp");
        when(corporateRepository.findById(corporateId)).thenReturn(Optional.of(corporate));

        PublicInvoiceResponse response = service.getPublicInvoiceByToken("real-token").orElseThrow();

        assertEquals("INV-2026-0001", response.getReceivableNumber());
        assertEquals("Test Multinational Corp", response.getCorporateName());
        assertEquals("GB830410000000000000660643", response.getViban());
        assertEquals(BigDecimal.valueOf(500), response.getOutstandingAmount());
    }

    @Test
    void publicLookupWithUnknownTokenReturnsEmpty() {
        when(receivableRepository.findByPaymentLinkToken("no-such-token")).thenReturn(Optional.empty());

        assertTrue(service.getPublicInvoiceByToken("no-such-token").isEmpty());
    }

    @Test
    void anInvoiceWithoutACorporateIsRefusedNotFiledUnderADemoOne() {
        BusinessException e = assertThrows(BusinessException.class, () -> service.createInvoice(null, baseRequest().build()));
        assertEquals("corporateId is required", e.getMessage());
        verify(receivableRepository, never()).save(any());
    }

    /** Nothing recorded, nothing shown: no demo orders, POS rows or unmatched payments. */
    @Test
    void emptySourcesListEmpty() {
        when(unmatchedPaymentRepository.findAll()).thenReturn(java.util.List.of());

        assertTrue(service.getUnmatchedPayments(CORPORATE_ID).isEmpty());
        assertTrue(service.getAllEcommerceOrders(CORPORATE_ID, null, null).isEmpty());
        assertTrue(service.getPlatformStats(CORPORATE_ID).isEmpty());
        assertTrue(service.getAllPosCollections(CORPORATE_ID, null, null).isEmpty());
        assertTrue(service.getMerchantSummaries(CORPORATE_ID).isEmpty());
        assertEquals(0, service.getPosStats(CORPORATE_ID).getTransactionCount());
    }

    @Test
    void escrowReleaseWithNoOrdersIsRefused() {
        assertThrows(BusinessException.class,
                () -> service.releaseEscrow(UUID.randomUUID(), new ReleaseEscrowRequest()));
    }

    @Test
    void aMatchTheLedgerRefusesIsReportedNotFaked() {
        UUID paymentId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        when(reconciliationService.manualMatch(paymentId, invoiceId, "portal")).thenReturn(
                ReconciliationService.ReconciliationResult.builder().success(false).errorMessage("Unmatched payment not found").build());
        MatchPaymentRequest request = new MatchPaymentRequest();
        request.setInvoiceId(invoiceId);

        BusinessException e = assertThrows(BusinessException.class, () -> service.matchPayment(paymentId, request));
        assertEquals("Unmatched payment not found", e.getMessage());
    }

    @Test
    void aCoboInvoiceKeepsTheIhbAccountItsEnrolmentSettlesOn() {
        stubSaveAssignsId();
        enrolSubsidiaryForAed();

        service.createInvoice(CORPORATE_ID, baseRequest().owningEntityId(SUBSIDIARY).targetVaId(TARGET_VA_ID).build());

        assertEquals(IHB_ACCOUNT, lastSaved().getCoboIhbAccountId());
    }

    /** ihb-fx 3.1: enrolled for EUR, settled on the AED IHB account; T collects into its AED account. */
    @Test
    void aEurInvoiceOfASubsidiarySettledInAedGetsItsEurVibanOnTreasurysAedAccount() {
        stubSaveAssignsId();
        enrolSubsidiaryForAed();
        when(coboEnrolments.active(eq(SUBSIDIARY), eq("EUR"), any())).thenReturn(Optional.of(
            new CoboEnrolmentService.EnrolmentView(ENROLMENT, CORPORATE_ID, SUBSIDIARY, "SUB-X", "Sub X LLC", "EUR",
                TREASURY, "TC-1", "Treasury Centre LLC", TREASURY_VA, "VA-T-AED", "Treasury collections",
                IHB_ACCOUNT, "VA-X-IHB", "AED", LocalDate.now(), null, true, "tester", null)));

        service.createInvoice(CORPORATE_ID, baseRequest().owningEntityId(SUBSIDIARY).currencyCode("EUR")
            .amount(new BigDecimal("1000")).targetVaId(TARGET_VA_ID).build());

        Receivable r = lastSaved();
        assertTrue(r.getIsCobo());
        assertEquals("EUR", r.getCurrencyCode());
        assertEquals(TREASURY_VA, r.getVirtualAccountId());
        assertEquals(IHB_ACCOUNT, r.getCoboIhbAccountId());
        ArgumentCaptor<VibanCreateRequest> viban = ArgumentCaptor.forClass(VibanCreateRequest.class);
        verify(vibanService).createViban(eq(PROGRAM_ID), viban.capture());
        assertEquals(TREASURY_VA, viban.getValue().getVirtualAccountId());
        assertEquals("EUR", viban.getValue().getCurrencyCode());
        assertEquals(0, new BigDecimal("1000").compareTo(viban.getValue().getExpectedAmount()));
    }
}
