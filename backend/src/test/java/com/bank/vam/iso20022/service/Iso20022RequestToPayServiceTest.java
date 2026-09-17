package com.bank.vam.iso20022.service;

import com.bank.vam.entity.party.Party;
import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.entity.receivables.Receivable.RequestToPayStatus;
import com.bank.vam.iso20022.dto.Iso20022PaymentDto.RequestToPayResponse;
import com.bank.vam.iso20022.dto.Iso20022PaymentDto.RequestToPayStatusResult;
import com.bank.vam.iso20022.util.Iso20022XmlBuilder;
import com.bank.vam.repository.party.PartyRepository;
import com.bank.vam.repository.receivables.ReceivableRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Coverage for pain.013 generation and pain.014 status ingestion -- Request to Pay, the layer
 * that notifies a debtor about a Receivable raised via the file-ingest/single-invoice paths.
 */
class Iso20022RequestToPayServiceTest {

    private final ReceivableRepository receivableRepository = mock(ReceivableRepository.class);
    private final PartyRepository partyRepository = mock(PartyRepository.class);
    private final Iso20022XmlBuilder xmlBuilder = new Iso20022XmlBuilder();

    private final Iso20022RequestToPayService service =
            new Iso20022RequestToPayService(receivableRepository, partyRepository, xmlBuilder);

    private static final UUID RECEIVABLE_ID = UUID.randomUUID();
    private static final UUID PARTY_ID = UUID.randomUUID();

    private Receivable receivable() {
        Receivable r = Receivable.builder()
                .receivableNumber("INV-2026-0001")
                .customerPartyId(PARTY_ID)
                .description("August services")
                .dueDate(LocalDate.now().plusDays(30))
                .viban("VIBAN-R2P-1")
                .currencyCode("AED")
                .outstandingAmount(BigDecimal.valueOf(750))
                .owningEntityName("Test Corp")
                .build();
        r.setId(RECEIVABLE_ID);
        return r;
    }

    private Party debtor() {
        Party p = Party.builder().legalName("Acme Corp").build();
        p.setId(PARTY_ID);
        return p;
    }

    @Test
    void sendRequestToPayGeneratesPain013AndMarksSent() {
        when(receivableRepository.findById(RECEIVABLE_ID)).thenReturn(Optional.of(receivable()));
        when(partyRepository.findById(PARTY_ID)).thenReturn(Optional.of(debtor()));

        RequestToPayResponse response = service.sendRequestToPay(RECEIVABLE_ID);

        assertEquals("SENT", response.getStatus());
        assertNotNull(response.getMessageId());
        assertTrue(response.getPain013Xml().contains("Acme Corp"));
        assertTrue(response.getPain013Xml().contains("750"));
        assertTrue(response.getPain013Xml().contains("AED"));
        assertTrue(response.getPain013Xml().contains("INV-2026-0001"));

        ArgumentCaptorHelper.verifySaved(receivableRepository, RequestToPayStatus.SENT, response.getMessageId());
    }

    @Test
    void acceptedStatusReportUpdatesTheMatchingReceivable() {
        Receivable r = receivable();
        r.setRequestToPayMessageId("R2P-123");
        when(receivableRepository.findByRequestToPayMessageId("R2P-123")).thenReturn(Optional.of(r));

        String pain014 = pain014Xml("R2P-123", "ACCP", null);
        RequestToPayStatusResult result = service.receiveStatusReport(pain014);

        assertTrue(result.isMatched());
        assertEquals("ACCEPTED", result.getStatus());
        assertEquals(RequestToPayStatus.ACCEPTED, r.getRequestToPayStatus());
        assertNotNull(r.getRequestToPayRespondedAt());
    }

    @Test
    void rejectedStatusReportStoresTheReason() {
        Receivable r = receivable();
        r.setRequestToPayMessageId("R2P-456");
        when(receivableRepository.findByRequestToPayMessageId("R2P-456")).thenReturn(Optional.of(r));

        String pain014 = pain014Xml("R2P-456", "RJCT", "Insufficient funds");
        RequestToPayStatusResult result = service.receiveStatusReport(pain014);

        assertTrue(result.isMatched());
        assertEquals("REJECTED", result.getStatus());
        assertEquals(RequestToPayStatus.REJECTED, r.getRequestToPayStatus());
        assertEquals("Insufficient funds", r.getRequestToPayRejectReason());
    }

    @Test
    void unknownEndToEndIdIsHandledWithoutThrowing() {
        when(receivableRepository.findByRequestToPayMessageId("R2P-UNKNOWN")).thenReturn(Optional.empty());

        RequestToPayStatusResult result = service.receiveStatusReport(pain014Xml("R2P-UNKNOWN", "ACCP", null));

        assertFalse(result.isMatched());
        verify(receivableRepository, never()).save(any());
    }

    private String pain014Xml(String originalEndToEndId, String txSts, String reason) {
        return "<?xml version=\"1.0\"?>\n"
                + "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.014.001.07\">\n"
                + "  <CdtrPmtActvtnReqStsRpt>\n"
                + "    <OrgnlPmtInfAndSts>\n"
                + "      <TxInfAndSts>\n"
                + "        <OrgnlEndToEndId>" + originalEndToEndId + "</OrgnlEndToEndId>\n"
                + "        <TxSts>" + txSts + "</TxSts>\n"
                + (reason != null
                        ? "        <StsRsnInf><AddtlInf>" + reason + "</AddtlInf></StsRsnInf>\n"
                        : "")
                + "      </TxInfAndSts>\n"
                + "    </OrgnlPmtInfAndSts>\n"
                + "  </CdtrPmtActvtnReqStsRpt>\n"
                + "</Document>";
    }

    /** Small local helper so the "happy path" test above stays readable. */
    private static final class ArgumentCaptorHelper {
        static void verifySaved(ReceivableRepository repo, RequestToPayStatus expectedStatus, String expectedMessageId) {
            verify(repo).save(argThat(r -> r.getRequestToPayStatus() == expectedStatus
                    && expectedMessageId.equals(r.getRequestToPayMessageId())));
        }
    }
}
