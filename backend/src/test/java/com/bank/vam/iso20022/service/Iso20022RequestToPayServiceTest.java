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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Coverage for pain.013 generation and pain.014 status ingestion -- Request to Pay, the layer
 * that notifies a debtor about a Receivable raised via the file-ingest/single-invoice paths.
 */
class Iso20022RequestToPayServiceTest {

    private final ReceivableRepository receivableRepository = mock(ReceivableRepository.class);
    private final PartyRepository partyRepository = mock(PartyRepository.class);
    private final Iso20022XmlBuilder xmlBuilder = new Iso20022XmlBuilder();

    private final com.bank.vam.repository.VirtualAccountRepository accounts = mock(com.bank.vam.repository.VirtualAccountRepository.class);
    private final com.bank.vam.service.viban.VibanService vibans = mock(com.bank.vam.service.viban.VibanService.class);
    private final com.bank.vam.outbox.OutboxEmailRepository outbox = mock(com.bank.vam.outbox.OutboxEmailRepository.class);
    private final Iso20022RequestToPayService service =
            new Iso20022RequestToPayService(receivableRepository, partyRepository, xmlBuilder, accounts, vibans,
                    mock(org.springframework.transaction.PlatformTransactionManager.class), outbox,
                    mock(com.bank.vam.repository.CorporateRepository.class));

    {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "frontendBaseUrl", "https://portal.example");
    }

    /** An invoice with no VIBAN or link gets both; the link travels in the pain.013, which is kept on the invoice. */
    @Test
    void requestToPayGivesTheInvoiceAPayLinkAndAVibanAndKeepsTheMessage() {
        Receivable r = receivable();
        r.setViban(null);
        UUID vaId = UUID.randomUUID();
        r.setVirtualAccountId(vaId);
        com.bank.vam.entity.VirtualAccount va = com.bank.vam.entity.VirtualAccount.builder().programId(UUID.randomUUID()).build();
        va.setId(vaId);
        when(accounts.findById(vaId)).thenReturn(Optional.of(va));
        UUID vibanId = UUID.randomUUID();
        when(vibans.createViban(any(), any())).thenReturn(com.bank.vam.dto.viban.VibanDto.VibanResponse.builder()
                .id(vibanId).viban("AE070331234567890123456").build());
        when(receivableRepository.findById(RECEIVABLE_ID)).thenReturn(Optional.of(r));
        when(partyRepository.findById(PARTY_ID)).thenReturn(Optional.of(debtor()));

        RequestToPayResponse response = service.sendRequestToPay(RECEIVABLE_ID);

        assertNotNull(r.getPaymentLinkToken());
        assertEquals("AE070331234567890123456", r.getViban());
        assertEquals(vibanId, r.getPrimaryVibanId());
        assertTrue(response.getPain013Xml().contains("<IBAN>AE070331234567890123456</IBAN>"), response.getPain013Xml());
        assertTrue(response.getPain013Xml().contains("Pay online: https://portal.example/?page=pay-invoice&amp;token=" + r.getPaymentLinkToken()));
        assertEquals(response.getPain013Xml(), r.getRequestToPayXml());
        assertEquals(response.getPain013Xml(), service.lastRequest(RECEIVABLE_ID));

        // the customer has no email address: the email is kept, marked NO_ADDRESS
        org.mockito.ArgumentCaptor<com.bank.vam.outbox.OutboxEmail> mail = org.mockito.ArgumentCaptor.forClass(com.bank.vam.outbox.OutboxEmail.class);
        verify(outbox).save(mail.capture());
        assertEquals("NO_ADDRESS", mail.getValue().getStatus());
        assertEquals("https://portal.example/?page=pay-invoice&token=" + r.getPaymentLinkToken(), mail.getValue().getLink());
        assertTrue(mail.getValue().getBody().contains("Or transfer AED 750.00 to IBAN AE070331234567890123456, quoting INV-2026-0001"), mail.getValue().getBody());
    }

    @Test
    void theRequestIsEmailedToTheCustomersAddress() {
        Receivable r = receivable();
        Party p = debtor();
        p.setContactEmail("ap@acme.example");
        when(receivableRepository.findById(RECEIVABLE_ID)).thenReturn(Optional.of(r));
        when(partyRepository.findById(PARTY_ID)).thenReturn(Optional.of(p));

        service.sendRequestToPay(RECEIVABLE_ID);

        org.mockito.ArgumentCaptor<com.bank.vam.outbox.OutboxEmail> mail = org.mockito.ArgumentCaptor.forClass(com.bank.vam.outbox.OutboxEmail.class);
        verify(outbox).save(mail.capture());
        assertEquals("ap@acme.example", mail.getValue().getToAddress());
        assertEquals("SENT", mail.getValue().getStatus());
        assertTrue(mail.getValue().getSubject().startsWith("Payment request: INV-2026-0001 · AED 750.00"), mail.getValue().getSubject());
    }

    /** "Send again" on a paid invoice is refused, and nothing is emailed. */
    @Test
    void aPaidInvoiceIsNotRequestedAgain() {
        Receivable r = receivable();
        r.setOutstandingAmount(BigDecimal.ZERO);
        when(receivableRepository.findById(RECEIVABLE_ID)).thenReturn(Optional.of(r));

        assertThrows(com.bank.vam.exception.BusinessException.class, () -> service.sendRequestToPay(RECEIVABLE_ID));
        verifyNoInteractions(outbox);
    }

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
