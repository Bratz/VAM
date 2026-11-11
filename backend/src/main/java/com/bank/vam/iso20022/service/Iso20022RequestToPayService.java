package com.bank.vam.iso20022.service;

import com.bank.vam.entity.party.Party;
import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.entity.receivables.Receivable.RequestToPayStatus;
import com.bank.vam.iso20022.dto.Iso20022PaymentDto.RequestToPayResponse;
import com.bank.vam.iso20022.dto.Iso20022PaymentDto.RequestToPayStatusResult;
import com.bank.vam.iso20022.util.Iso20022XmlBuilder;
import com.bank.vam.repository.party.PartyRepository;
import com.bank.vam.repository.receivables.ReceivableRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * ISO 20022 Request to Pay: builds and "sends" a pain.013 (CreditorPaymentActivationRequest) for
 * a raised Receivable, and ingests the debtor side's pain.014 status report.
 *
 * <p>Like every other outbound ISO 20022 message in this codebase (pain.001, pain.002 --
 * confirmed by reading Iso20022OutwardPaymentService/Iso20022StatusService), sendRequestToPay()
 * generates and returns/stores XML -- there is no real transport to a counterparty bank or RTP
 * scheme anywhere in this app, and building one is out of scope here.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class Iso20022RequestToPayService {

    private final ReceivableRepository receivableRepository;
    private final PartyRepository partyRepository;
    private final Iso20022XmlBuilder xmlBuilder;
    private final com.bank.vam.repository.VirtualAccountRepository accounts;
    private final com.bank.vam.service.viban.VibanService vibans;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;
    private final com.bank.vam.outbox.OutboxEmailRepository outbox;
    private final com.bank.vam.repository.CorporateRepository corporates;

    @org.springframework.beans.factory.annotation.Value("${app.frontend-base-url}")
    private String frontendBaseUrl;

    /**
     * The Request to Pay as the email the payer would get. There is no mail server: it is kept in the outbox
     * (SENT, simulated), or marked NO_ADDRESS when neither the invoice nor the customer has an email address.
     */
    /** The creditor's real name: the corporate's legal name, else the owning entity's; refused rather than invented. */
    private String creditorName(Receivable r) {
        return Optional.ofNullable(r.getCorporateId()).flatMap(corporates::findById).map(c -> c.getLegalName())
                .filter(n -> !n.isBlank())
                .or(() -> Optional.ofNullable(r.getOwningEntityName()).filter(n -> !n.isBlank()))
                .orElseThrow(() -> new com.bank.vam.exception.BusinessException("Not available: Request to Pay for "
                        + r.getReceivableNumber() + " — no creditor name is recorded for the corporate or owning entity"));
    }

    private com.bank.vam.outbox.OutboxEmail email(Receivable r, Party debtor, String link, String endToEndId, String creditor) {
        String to = r.getCustomerEmail() != null && !r.getCustomerEmail().isBlank() ? r.getCustomerEmail().trim()
                : debtor.getContactEmail() != null && !debtor.getContactEmail().isBlank() ? debtor.getContactEmail().trim() : null;
        String amount = r.getCurrencyCode() + " " + new java.text.DecimalFormat("#,##0.00").format(r.getOutstandingAmount());
        StringBuilder body = new StringBuilder()
                .append("Dear ").append(debtor.getLegalName()).append(",\n\n")
                .append(creditor).append(" requests payment of ").append(amount).append(" for invoice ").append(r.getReceivableNumber())
                .append(r.getDescription() != null ? " (" + r.getDescription() + ")" : "")
                .append(r.getDueDate() != null ? ", due on " + r.getDueDate() : "").append(".\n\n")
                .append("Pay online: ").append(link).append("\n\n");
        if (r.getViban() != null) {
            body.append("Or transfer ").append(amount).append(" to IBAN ").append(r.getViban())
                    .append(", quoting ").append(r.getReceivableNumber()).append(" as the reference.\n\n");
        }
        body.append("This payment request was sent as ISO 20022 pain.013, reference ").append(endToEndId).append(".");

        com.bank.vam.outbox.OutboxEmail e = new com.bank.vam.outbox.OutboxEmail();
        e.setCorporateId(r.getCorporateId());
        e.setToAddress(to);
        e.setToName(debtor.getLegalName());
        e.setSubject("Payment request: " + r.getReceivableNumber() + " · " + amount + " · " + creditor);
        e.setBody(body.toString());
        e.setRelatedType("REQUEST_TO_PAY");
        e.setRelatedId(r.getId());
        e.setRelatedRef(r.getReceivableNumber());
        e.setLink(link);
        e.setStatus(to != null ? "SENT" : "NO_ADDRESS");
        return e;
    }

    @Transactional
    public RequestToPayResponse sendRequestToPay(UUID receivableId) {
        Receivable receivable = receivableRepository.findById(receivableId)
                .orElseThrow(() -> new RuntimeException("Receivable not found: " + receivableId));
        if (receivable.getCustomerPartyId() == null) {
            throw new RuntimeException("Receivable " + receivableId + " has no linked customer Party");
        }
        if (receivable.getOutstandingAmount() == null || receivable.getOutstandingAmount().signum() <= 0) {
            throw new com.bank.vam.exception.BusinessException(receivable.getReceivableNumber() + " is already paid; there is nothing to request");
        }
        Party debtor = partyRepository.findById(receivable.getCustomerPartyId())
                .orElseThrow(() -> new RuntimeException("Receivable " + receivableId + " has no linked customer Party"));
        String creditor = creditorName(receivable);

        String endToEndId = "R2P-" + UUID.randomUUID();
        // What the payer needs to pay it: a "pay this invoice" page, and a virtual IBAN so the payment matches
        // the invoice by itself. Both are created here if the invoice has none.
        if (receivable.getPaymentLinkToken() == null) {
            receivable.setPaymentLinkToken(UUID.randomUUID().toString());
        }
        ensureViban(receivable);
        String link = frontendBaseUrl + "/?page=pay-invoice&token=" + receivable.getPaymentLinkToken();
        String xml = xmlBuilder.buildPain013(receivable, debtor, endToEndId, link, creditor);
        receivable.setRequestToPayXml(xml);
        outbox.save(email(receivable, debtor, link, endToEndId, creditor));

        receivable.setRequestToPayStatus(RequestToPayStatus.SENT);
        receivable.setRequestToPayMessageId(endToEndId);
        receivable.setRequestToPaySentAt(LocalDateTime.now());
        receivableRepository.save(receivable);

        log.info("Sent pain.013 Request to Pay for receivable {} ({}), endToEndId={}",
                receivableId, receivable.getReceivableNumber(), endToEndId);

        return RequestToPayResponse.builder()
                .receivableId(receivableId)
                .status(RequestToPayStatus.SENT.name())
                .messageId(endToEndId)
                .pain013Xml(xml)
                .build();
    }

    /**
     * Gives the invoice its own VIBAN on its collection account, as invoice creation does when asked, so a payment
     * made against the request matches the invoice automatically. In its own transaction: a program that cannot
     * issue a VIBAN still gets its Request to Pay, just without one.
     */
    private void ensureViban(Receivable receivable) {
        if (receivable.hasLinkedViban() || receivable.getVirtualAccountId() == null) return;
        try {
            org.springframework.transaction.support.TransactionTemplate own = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
            own.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            com.bank.vam.dto.viban.VibanDto.VibanResponse created = own.execute(t -> {
                com.bank.vam.entity.VirtualAccount va = accounts.findById(receivable.getVirtualAccountId()).orElseThrow();
                return vibans.createViban(va.getProgramId(), com.bank.vam.dto.viban.VibanDto.VibanCreateRequest.builder()
                        .virtualAccountId(va.getId())
                        .vibanType(com.bank.vam.entity.viban.Viban.VibanType.INVOICE)
                        .referenceType(com.bank.vam.entity.viban.Viban.REF_TYPE_INVOICE)
                        .referenceId(receivable.getId().toString())
                        .expectedAmount(receivable.getOutstandingAmount())
                        .currencyCode(receivable.getCurrencyCode())
                        .validUntil(receivable.getDueDate() != null && receivable.getDueDate().isAfter(java.time.LocalDate.now())
                                ? receivable.getDueDate().plusDays(30).atStartOfDay() : LocalDateTime.now().plusDays(30))
                        .singleUse(false)
                        .customerName(receivable.getCustomerName())
                        .build());
            });
            receivable.setViban(created.getViban());
            receivable.setPrimaryVibanId(created.getId());
        } catch (RuntimeException e) {
            log.warn("No VIBAN for {}: {}", receivable.getReceivableNumber(), e.getMessage());
        }
    }

    /** The last pain.013 sent for this invoice. */
    @Transactional(readOnly = true)
    public String lastRequest(UUID receivableId) {
        Receivable r = receivableRepository.findById(receivableId)
                .orElseThrow(() -> new com.bank.vam.exception.ResourceNotFoundException("Receivable not found: " + receivableId));
        if (r.getRequestToPayXml() == null) {
            throw new com.bank.vam.exception.ResourceNotFoundException("No Request to Pay has been kept for " + r.getReceivableNumber());
        }
        return r.getRequestToPayXml();
    }

    @Transactional
    public RequestToPayStatusResult receiveStatusReport(String pain014Xml) {
        try {
            Document doc = parseXml(pain014Xml);
            String originalEndToEndId = getElementText(doc, "OrgnlEndToEndId");
            String txSts = getElementText(doc, "TxSts");
            String reason = getNestedText(doc, "StsRsnInf", "AddtlInf");

            if (originalEndToEndId == null || txSts == null) {
                return RequestToPayStatusResult.builder().matched(false)
                        .message("pain.014 missing OrgnlEndToEndId or TxSts").build();
            }

            Receivable receivable = receivableRepository.findByRequestToPayMessageId(originalEndToEndId).orElse(null);
            if (receivable == null) {
                log.warn("pain.014 status report referenced unknown EndToEndId {}", originalEndToEndId);
                return RequestToPayStatusResult.builder().matched(false)
                        .message("No receivable found for EndToEndId " + originalEndToEndId).build();
            }

            RequestToPayStatus newStatus = "ACCP".equals(txSts) ? RequestToPayStatus.ACCEPTED : RequestToPayStatus.REJECTED;
            receivable.setRequestToPayStatus(newStatus);
            receivable.setRequestToPayRespondedAt(LocalDateTime.now());
            if (newStatus == RequestToPayStatus.REJECTED) {
                receivable.setRequestToPayRejectReason(reason);
            }
            receivableRepository.save(receivable);

            return RequestToPayStatusResult.builder().matched(true)
                    .receivableId(receivable.getId())
                    .status(newStatus.name())
                    .message("Receivable " + receivable.getReceivableNumber() + " updated to " + newStatus)
                    .build();
        } catch (Exception e) {
            log.error("Failed to process pain.014 status report: ", e);
            return RequestToPayStatusResult.builder().matched(false)
                    .message("Failed to parse pain.014: " + e.getMessage()).build();
        }
    }

    // ========================================================================
    // XML PARSING HELPERS -- duplicated from Iso20022InwardPaymentService's own private helpers
    // rather than extracted to a shared util, matching that class's existing convention (its
    // helpers aren't shared through the mapper or Iso20022XmlBuilder for pacs.008/camt.054 either).
    // ========================================================================

    private Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private String getElementText(Document doc, String tagName) {
        NodeList nodes = doc.getElementsByTagName(tagName);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent().trim() : null;
    }

    private String getNestedText(Document doc, String parentTag, String childTag) {
        NodeList parents = doc.getElementsByTagName(parentTag);
        if (parents.getLength() == 0) return null;
        Element parent = (Element) parents.item(0);
        NodeList children = parent.getElementsByTagName(childTag);
        return children.getLength() > 0 ? children.item(0).getTextContent().trim() : null;
    }
}
