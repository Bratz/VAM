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
import com.bank.vam.repository.treasury.ExceptionTransactionRepository;
import com.bank.vam.repository.viban.VibanRepository;
import com.bank.vam.service.TransactionService;
import com.bank.vam.service.treasury.FeePostingService;
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
    private final FeePostingService feePostingService = mock(FeePostingService.class);
    private final TransactionService transactionService = mock(TransactionService.class);
    private final SettlementVaResolverService settlementVaResolver = mock(SettlementVaResolverService.class);
    private final ExceptionTransactionRepository exceptionTransactionRepository = mock(ExceptionTransactionRepository.class);
    private final CorporateRepository corporateRepository = mock(CorporateRepository.class);

    private final CoboReceivableService coboReceivables = mock(CoboReceivableService.class);
    private final com.bank.vam.outbox.OutboxEmailRepository outbox = mock(com.bank.vam.outbox.OutboxEmailRepository.class);

    private final ReceivablesService service = new ReceivablesService(
            virtualAccountRepository, transactionRepository, vibanRepository, receivableRepository,
            vibanService, corporateRepository, feePostingService, transactionService, settlementVaResolver,
            exceptionTransactionRepository, coboReceivables, outbox);

    {
        ReflectionTestUtils.setField(service, "frontendBaseUrl", "http://test.local");
    }

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

        InvoiceResponse response = service.createInvoice(null, baseRequest()
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

        assertThrows(BusinessException.class, () -> service.createInvoice(null, request));
        verify(vibanService, never()).createViban(any(), any());
    }

    @Test
    void noVibanRequestedLeavesVibanFieldsNull() {
        stubSaveAssignsId();

        InvoiceResponse response = service.createInvoice(null, baseRequest().build());

        assertNull(response.getViban());
        assertNull(response.getVibanId());
        verify(vibanService, never()).createViban(any(), any());
    }

    @Test
    void everyInvoiceGetsARealPaymentLinkRegardlessOfViban() {
        stubSaveAssignsId();
        ArgumentCaptor<Receivable> saved = ArgumentCaptor.forClass(Receivable.class);

        InvoiceResponse response = service.createInvoice(null, baseRequest().build());

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

        InvoiceResponse response = service.createInvoice(null, baseRequest()
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

    /** A draft is stored as DRAFT and nothing goes out: no COBO request, no email. */
    @Test
    void aDraftIsKeptAsADraftAndNotSent() {
        stubSaveAssignsId();

        service.createInvoice(null, baseRequest().draft(true).sendInvoiceEmail(true)
                .coboCollectorEntityId(UUID.randomUUID()).build());

        ArgumentCaptor<Receivable> saved = ArgumentCaptor.forClass(Receivable.class);
        verify(receivableRepository, atLeastOnce()).save(saved.capture());
        assertEquals(Receivable.ReceivableStatus.DRAFT, saved.getValue().getStatus());
        verifyNoInteractions(coboReceivables, outbox);
    }

    @Test
    void collectOnBehalfIsSubmittedAndTheInvoiceIsEmailed() {
        stubSaveAssignsId();
        UUID treasury = UUID.randomUUID();
        when(coboReceivables.submitForCobo(any(), eq(treasury), eq("portal"))).thenAnswer(inv -> {
            Receivable r = Receivable.builder().receivableNumber("INV-X").currencyCode("AED")
                    .netAmount(BigDecimal.valueOf(500)).customerEmail("ap@acme.example").build();
            r.setId(inv.getArgument(0));
            return r;
        });

        service.createInvoice(null, baseRequest().coboCollectorEntityId(treasury).sendInvoiceEmail(true).build());

        verify(coboReceivables).submitForCobo(any(), eq(treasury), eq("portal"));
        ArgumentCaptor<com.bank.vam.outbox.OutboxEmail> mail = ArgumentCaptor.forClass(com.bank.vam.outbox.OutboxEmail.class);
        verify(outbox).save(mail.capture());
        assertEquals("SENT", mail.getValue().getStatus());
        assertEquals("INVOICE", mail.getValue().getRelatedType());
        assertTrue(mail.getValue().getBody().contains("Pay online: http://test.local/?page=pay-invoice&token="));
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
}
