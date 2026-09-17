package com.bank.vam.service.receivables;

import com.bank.vam.dto.receivables.ReceivablesDto.CreateInvoiceRequest;
import com.bank.vam.dto.receivables.ReceivablesDto.InvoiceResponse;
import com.bank.vam.dto.receivables.ReceivablesDto.PublicInvoiceResponse;
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

    private final ReceivablesService service = new ReceivablesService(
            virtualAccountRepository, transactionRepository, vibanRepository, receivableRepository,
            vibanService, corporateRepository, feePostingService, transactionService, settlementVaResolver,
            exceptionTransactionRepository);

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
        when(vibanService.createInvoiceViban(eq(PROGRAM_ID), eq(TARGET_VA_ID), any(), any(), any()))
                .thenReturn(VibanResponse.builder().id(realVibanId).viban("AE-REAL-VIBAN-123").build());

        InvoiceResponse response = service.createInvoice(null, baseRequest()
                .createViban(true)
                .targetVaId(TARGET_VA_ID)
                .build());

        assertEquals("AE-REAL-VIBAN-123", response.getViban());
        assertEquals(realVibanId, response.getVibanId());

        // createInvoiceViban's 3rd arg must be the receivable's own real id (a UUID string) --
        // ReconciliationService.attemptDirectMatch() parses it back as one.
        ArgumentCaptor<String> invoiceIdArg = ArgumentCaptor.forClass(String.class);
        verify(vibanService).createInvoiceViban(eq(PROGRAM_ID), eq(TARGET_VA_ID), invoiceIdArg.capture(), eq(BigDecimal.valueOf(500)), any());
        assertDoesNotThrow(() -> UUID.fromString(invoiceIdArg.getValue()));
        assertEquals(response.getId().toString(), invoiceIdArg.getValue());
    }

    @Test
    void generateVibanWithoutATargetAccountThrowsInsteadOfFabricatingData() {
        stubSaveAssignsId();
        CreateInvoiceRequest request = baseRequest().createViban(true).build();

        assertThrows(BusinessException.class, () -> service.createInvoice(null, request));
        verify(vibanService, never()).createInvoiceViban(any(), any(), any(), any(), any());
    }

    @Test
    void noVibanRequestedLeavesVibanFieldsNull() {
        stubSaveAssignsId();

        InvoiceResponse response = service.createInvoice(null, baseRequest().build());

        assertNull(response.getViban());
        assertNull(response.getVibanId());
        verify(vibanService, never()).createInvoiceViban(any(), any(), any(), any(), any());
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
