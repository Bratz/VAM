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

    @Transactional
    public RequestToPayResponse sendRequestToPay(UUID receivableId) {
        Receivable receivable = receivableRepository.findById(receivableId)
                .orElseThrow(() -> new RuntimeException("Receivable not found: " + receivableId));
        if (receivable.getCustomerPartyId() == null) {
            throw new RuntimeException("Receivable " + receivableId + " has no linked customer Party");
        }
        Party debtor = partyRepository.findById(receivable.getCustomerPartyId())
                .orElseThrow(() -> new RuntimeException("Receivable " + receivableId + " has no linked customer Party"));

        String endToEndId = "R2P-" + UUID.randomUUID();
        String xml = xmlBuilder.buildPain013(receivable, debtor, endToEndId);

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
