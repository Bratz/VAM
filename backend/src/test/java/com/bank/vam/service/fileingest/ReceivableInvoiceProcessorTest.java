package com.bank.vam.service.fileingest;

import com.bank.vam.dto.party.PartyDto.CreatePartyRequest;
import com.bank.vam.dto.party.PartyDto.PartyResponse;
import com.bank.vam.dto.receivables.ReceivablesDto.CreateInvoiceRequest;
import com.bank.vam.dto.receivables.ReceivablesDto.InvoiceResponse;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.fileingest.IngestJob;
import com.bank.vam.entity.fileingest.RowStatus;
import com.bank.vam.entity.fileingest.StagedTransaction;
import com.bank.vam.entity.party.Party;
import com.bank.vam.repository.fileingest.IngestJobRepository;
import com.bank.vam.repository.fileingest.StagedTransactionRepository;
import com.bank.vam.repository.party.PartyRepository;
import com.bank.vam.service.VirtualAccountService;
import com.bank.vam.service.party.PartyService;
import com.bank.vam.service.receivables.ReceivablesService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Regression coverage for the "raise an invoice in bulk" direction (RECEIVABLES_INVOICE) --
 * opposite of ReceivablesProcessor, which posts a payment that already happened.
 */
class ReceivableInvoiceProcessorTest {

    private final StagedTransactionRepository repository = mock(StagedTransactionRepository.class);
    private final IngestJobRepository ingestJobRepository = mock(IngestJobRepository.class);
    private final VirtualAccountService virtualAccountService = mock(VirtualAccountService.class);
    private final ReceivablesService receivablesService = mock(ReceivablesService.class);
    private final PartyRepository partyRepository = mock(PartyRepository.class);
    private final PartyService partyService = mock(PartyService.class);

    private final ReceivableInvoiceProcessor processor = new ReceivableInvoiceProcessor(
            repository, ingestJobRepository, virtualAccountService, receivablesService, partyRepository, partyService);

    private static final UUID JOB_ID = UUID.randomUUID();
    private static final UUID VA_ID = UUID.randomUUID();

    private IngestJob job() {
        IngestJob job = new IngestJob();
        job.setId(JOB_ID);
        job.setCustomerId(UUID.randomUUID().toString());
        return job;
    }

    private TransformedRow row() {
        return new TransformedRow(1, BigDecimal.valueOf(500), "AED", "VIBAN-INV-1",
                "Acme Corp", "ACME-ACCT-1", null, null, "August invoice", "INV-REF-1");
    }

    private StagedTransaction stageOneReadyRow() {
        when(ingestJobRepository.findById(JOB_ID)).thenReturn(Optional.of(job()));
        StagedTransaction staged = new StagedTransaction();
        staged.setSourceRowNumber(1);
        staged.setStatus(RowStatus.READY);
        when(repository.findByIngestJobIdAndStatus(JOB_ID, RowStatus.READY)).thenReturn(List.of(staged));

        VirtualAccount va = VirtualAccount.builder().vaNumber("VA-1").build();
        va.setId(VA_ID);
        when(virtualAccountService.getByViban("VIBAN-INV-1")).thenReturn(va);
        return staged;
    }

    @Test
    void readyRowWithResolvableVibanRaisesAnInvoice() {
        StagedTransaction staged = stageOneReadyRow();
        when(partyRepository.findByCorporateIdAndLegalNameIgnoreCase(any(), any())).thenReturn(Optional.empty());
        when(partyService.createParty(any(), any())).thenReturn(PartyResponse.builder().id(UUID.randomUUID()).build());

        UUID receivableId = UUID.randomUUID();
        when(receivablesService.createInvoice(any(), any())).thenReturn(
                InvoiceResponse.builder().id(receivableId).build());

        processor.process(JOB_ID, List.of(row()));

        ArgumentCaptor<CreateInvoiceRequest> captor = ArgumentCaptor.forClass(CreateInvoiceRequest.class);
        verify(receivablesService).createInvoice(any(), captor.capture());
        CreateInvoiceRequest sent = captor.getValue();
        assertEquals("Acme Corp", sent.getCustomerName());
        assertEquals(VA_ID, sent.getTargetVaId());
        assertEquals(BigDecimal.valueOf(500), sent.getAmount());
        assertEquals("AED", sent.getCurrencyCode());

        assertEquals(RowStatus.PROCESSED, staged.getStatus());
        assertEquals(receivableId, staged.getProcessedEntityId());
        assertNull(staged.getReason());
    }

    @Test
    void unresolvableVibanFailsTheRowWithoutRaisingAnInvoice() {
        when(ingestJobRepository.findById(JOB_ID)).thenReturn(Optional.of(job()));

        StagedTransaction staged = new StagedTransaction();
        staged.setSourceRowNumber(1);
        staged.setStatus(RowStatus.READY);
        when(repository.findByIngestJobIdAndStatus(JOB_ID, RowStatus.READY)).thenReturn(List.of(staged));

        when(virtualAccountService.getByViban("VIBAN-INV-1")).thenThrow(new RuntimeException("Virtual account not found"));

        processor.process(JOB_ID, List.of(row()));

        assertEquals(RowStatus.FAILED, staged.getStatus());
        assertNotNull(staged.getReason());
        verify(receivablesService, never()).createInvoice(any(), any());
    }

    @Test
    void existingCustomerPartyIsReusedNotRecreated() {
        stageOneReadyRow();
        UUID existingPartyId = UUID.randomUUID();
        Party existing = Party.builder().legalName("Acme Corp").build();
        existing.setId(existingPartyId);
        when(partyRepository.findByCorporateIdAndLegalNameIgnoreCase(any(), eq("Acme Corp")))
                .thenReturn(Optional.of(existing));
        when(receivablesService.createInvoice(any(), any())).thenReturn(
                InvoiceResponse.builder().id(UUID.randomUUID()).build());

        processor.process(JOB_ID, List.of(row()));

        ArgumentCaptor<CreateInvoiceRequest> captor = ArgumentCaptor.forClass(CreateInvoiceRequest.class);
        verify(receivablesService).createInvoice(any(), captor.capture());
        assertEquals(existingPartyId, captor.getValue().getCustomerId());
        verify(partyService, never()).createParty(any(), any());
    }

    @Test
    void unknownDebtorIsAutoOnboardedAsAPendingKycParty() {
        stageOneReadyRow();
        when(partyRepository.findByCorporateIdAndLegalNameIgnoreCase(any(), any())).thenReturn(Optional.empty());
        UUID newPartyId = UUID.randomUUID();
        when(partyService.createParty(any(), any())).thenReturn(PartyResponse.builder().id(newPartyId).build());
        when(receivablesService.createInvoice(any(), any())).thenReturn(
                InvoiceResponse.builder().id(UUID.randomUUID()).build());

        processor.process(JOB_ID, List.of(row()));

        ArgumentCaptor<CreatePartyRequest> partyCaptor = ArgumentCaptor.forClass(CreatePartyRequest.class);
        verify(partyService).createParty(any(), partyCaptor.capture());
        assertEquals("Acme Corp", partyCaptor.getValue().getLegalName());
        assertTrue(partyCaptor.getValue().getRoles().contains("CUSTOMER"));

        ArgumentCaptor<CreateInvoiceRequest> invoiceCaptor = ArgumentCaptor.forClass(CreateInvoiceRequest.class);
        verify(receivablesService).createInvoice(any(), invoiceCaptor.capture());
        assertEquals(newPartyId, invoiceCaptor.getValue().getCustomerId());
    }
}
