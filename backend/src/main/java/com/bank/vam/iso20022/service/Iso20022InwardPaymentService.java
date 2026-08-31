package com.bank.vam.iso20022.service;

import com.bank.vam.dto.TransactionDto;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.viban.Viban;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.iso20022.dto.Iso20022PaymentDto.*;
import com.bank.vam.iso20022.mapper.Iso20022MessageMapper;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.viban.VibanRepository;
import com.bank.vam.service.TransactionService;
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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * ISO 20022 Inward Payment Service.
 *
 * Handles incoming payments (credits) via:
 * - pacs.008 (FI-to-FI Customer Credit Transfer)
 * - camt.054 (Bank-to-Customer Debit/Credit Notification)
 *
 * Integrates with existing VIBAN routing for automatic credit posting.
 * Uses 4-leg accounting via TransactionService.processCollection() for proper reconciliation.
 *
 * Flow:
 * 1. Parse ISO 20022 message (pacs.008 or camt.054)
 * 2. Extract VIBAN from creditor account
 * 3. Lookup VIBAN -> get Virtual Account
 * 4. Delegate to TransactionService.processCollection() for 4-leg accounting:
 *    CBS → Shadow VA → Settlement VA → Target VA
 * 5. Update VIBAN usage stats
 * 6. Return response with balance changes and reconciliation status
 *
 * The 4-leg accounting flow ensures proper reconciliation between:
 * - Physical Account (CBS) via Shadow VA
 * - Settlement VA for clearing
 * - Target VA for final credit
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class Iso20022InwardPaymentService {

    private final VibanRepository vibanRepository;
    private final VirtualAccountRepository virtualAccountRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionService transactionService;
    private final Iso20022MessageMapper messageMapper;

    // ========================================================================
    // PROCESS INWARD PAYMENT (Main Entry Point)
    // ========================================================================

    /**
     * Process inward payment from DTO request.
     * This is the main entry point for ISO 20022 inward payments.
     */
    @Transactional
    public InwardPaymentResponse processInwardPayment(InwardPaymentRequest request) {
        long startTime = System.currentTimeMillis();

        log.info("Processing ISO 20022 inward payment: amount={} {}, creditorAccount={}",
                request.getAmount(), request.getCurrency(), request.getCreditorAccount());

        try {
            // 1. Validate request
            validateRequest(request);

            // 2. Lookup VIBAN or VA by number
            // The creditorAccount can be either a VIBAN or a VA number
            String accountNumber = extractViban(request.getCreditorAccount());

            Viban viban = null;
            VirtualAccount va = null;
            String vibanNumber = null;
            UUID matchedReceivableId = null;

            // First try to find as VIBAN
            var vibanOpt = vibanRepository.findByViban(accountNumber);
            if (vibanOpt.isPresent()) {
                viban = vibanOpt.get();
                vibanNumber = accountNumber;

                if (!viban.canAcceptPayment()) {
                    return buildErrorResponse(request, startTime, "AC04", "VIBAN cannot accept payment: " + viban.getStatus());
                }

                // Get Virtual Account from VIBAN
                va = virtualAccountRepository.findById(viban.getVirtualAccountId())
                        .orElseThrow(() -> new BusinessException("Virtual account not found for VIBAN"));

                // Validate amount against VIBAN constraints
                if (!viban.isAmountInBounds(request.getAmount())) {
                    return buildErrorResponse(request, startTime, "AM02", "Payment amount out of allowed bounds");
                }

                // Try to parse matchedReceivableId from VIBAN's referenceId
                if (viban.getReferenceId() != null) {
                    try {
                        matchedReceivableId = UUID.fromString(viban.getReferenceId());
                    } catch (IllegalArgumentException e) {
                        log.debug("VIBAN referenceId is not a UUID: {}", viban.getReferenceId());
                    }
                }

                log.info("Found VIBAN {} -> VA {}", vibanNumber, va.getVaNumber());
            } else {
                // Try to find as VA number
                var vaOpt = virtualAccountRepository.findByVaNumber(accountNumber);
                if (vaOpt.isPresent()) {
                    va = vaOpt.get();
                    log.info("Found VA directly by number: {}", va.getVaNumber());
                } else {
                    // Neither VIBAN nor VA found
                    return buildErrorResponse(request, startTime, "AC04",
                        "Account not found: " + accountNumber + " (tried as VIBAN and VA number)");
                }
            }

            if (!va.isActive()) {
                return buildErrorResponse(request, startTime, "AC04", "Virtual account is not active");
            }

            // 3. Build CollectionRequest for 4-leg accounting via TransactionService
            // This delegates to TransactionService.processCollection() which creates:
            // CBS → Shadow VA → Settlement VA → Target VA (4 balanced entries)

            TransactionDto.CollectionRequest collectionRequest = TransactionDto.CollectionRequest.builder()
                    .targetVaId(va.getId())
                    .amount(request.getAmount())
                    .currencyCode(request.getCurrency())
                    .valueDate(LocalDate.now())
                    .remitterName(request.getDebtorName())
                    .remitterAccount(request.getDebtorAccount())
                    .bankReference(request.getMessageId())
                    .viban(vibanNumber)
                    .endToEndId(request.getEndToEndId())
                    .remittanceInfo(request.getRemittanceInfo())
                    .invoiceReference(request.getStructuredRef())
                    .matchedReceivableId(matchedReceivableId)
                    .channel(request.getChannel() != null ? request.getChannel() : "ISO20022")
                    .externalReference(request.getInstructionId())
                    .build();

            // 4. Process collection using 4-leg accounting
            TransactionDto.CollectionResponse collectionResponse = transactionService.processCollection(collectionRequest);

            // 5. Update VIBAN usage stats (only if routed via VIBAN)
            if (viban != null) {
                viban.recordPayment(request.getAmount());
                vibanRepository.save(viban);
            }

            // 6. Map CollectionResponse to InwardPaymentResponse
            long processingTime = System.currentTimeMillis() - startTime;
            log.info("ISO 20022 inward payment completed with 4-leg accounting: txn={}, va={}, viban={}, amount={} {}, " +
                    "grossAmount={}, netAmount={}, fee={}, time={}ms",
                    collectionResponse.getReferenceNumber(), va.getVaNumber(), vibanNumber,
                    request.getAmount(), request.getCurrency(),
                    collectionResponse.getGrossAmount(), collectionResponse.getNetAmount(),
                    collectionResponse.getFeeAmount(), processingTime);

            // Determine if auto-reconciled based on match status
            boolean autoReconciled = "AUTO".equals(collectionResponse.getMatchStatus()) ||
                    "PARTIAL".equals(collectionResponse.getMatchStatus());

            return InwardPaymentResponse.builder()
                    .success(true)
                    .statusCode("ACCP")
                    .statusReason("Collection processed with 4-leg accounting")
                    .transactionReference(collectionResponse.getReferenceNumber())
                    .transactionId(collectionResponse.getTransactionId())
                    .virtualAccountId(va.getId())
                    .vaNumber(va.getVaNumber())
                    .vibanId(viban != null ? viban.getId() : null)
                    .viban(vibanNumber)
                    .balanceBefore(collectionResponse.getTargetBalanceBefore())
                    .balanceAfter(collectionResponse.getTargetBalanceAfter())
                    .autoReconciled(autoReconciled)
                    .processingTimeMs(processingTime)
                    .build();

        } catch (BusinessException e) {
            log.warn("ISO 20022 inward payment failed: {}", e.getMessage());
            return buildErrorResponse(request, startTime, "RJCT", e.getMessage());
        } catch (Exception e) {
            log.error("ISO 20022 inward payment error: ", e);
            return buildErrorResponse(request, startTime, "TECH", "Technical error: " + e.getMessage());
        }
    }

    // ========================================================================
    // PARSE pacs.008 MESSAGE
    // ========================================================================

    /**
     * Parse pacs.008 XML and convert to InwardPaymentRequest.
     * Uses standard Java XML parsing instead of Prowide library.
     */
    public InwardPaymentRequest parsePacs008(String xml) {
        try {
            Document doc = parseXml(xml);

            // Get Group Header
            String msgId = getElementText(doc, "MsgId");

            // Get Credit Transfer Transaction Info
            NodeList cdtTrfTxInfList = doc.getElementsByTagName("CdtTrfTxInf");
            if (cdtTrfTxInfList.getLength() == 0) {
                throw new BusinessException("No CdtTrfTxInf found in pacs.008");
            }

            Element cdtTrfTxInf = (Element) cdtTrfTxInfList.item(0);

            var request = InwardPaymentRequest.builder()
                    .messageId(msgId)
                    .creationDateTime(LocalDateTime.now())
                    .channel("PACS008")
                    .rawXml(xml);

            // Payment ID
            String instrId = getElementTextFromParent(cdtTrfTxInf, "InstrId");
            String endToEndId = getElementTextFromParent(cdtTrfTxInf, "EndToEndId");
            request.instructionId(instrId);
            request.endToEndId(endToEndId);

            // Amount - try IntrBkSttlmAmt first
            Element amtElement = getFirstElement(cdtTrfTxInf, "IntrBkSttlmAmt");
            if (amtElement != null) {
                request.amount(new BigDecimal(amtElement.getTextContent().trim()));
                request.currency(amtElement.getAttribute("Ccy"));
            }

            // Debtor (sender)
            String debtorName = getNestedText(cdtTrfTxInf, "Dbtr", "Nm");
            String debtorIban = getNestedText(cdtTrfTxInf, "DbtrAcct", "IBAN");
            String debtorBic = getNestedText(cdtTrfTxInf, "DbtrAgt", "BICFI");
            request.debtorName(debtorName);
            request.debtorAccount(debtorIban);
            request.debtorBic(debtorBic);

            // Creditor (receiver) - this should be the VIBAN
            String creditorIban = getNestedText(cdtTrfTxInf, "CdtrAcct", "IBAN");
            String creditorName = getNestedText(cdtTrfTxInf, "Cdtr", "Nm");
            request.creditorAccount(creditorIban);
            request.creditorName(creditorName);

            // Remittance Info
            String ustrd = getNestedText(cdtTrfTxInf, "RmtInf", "Ustrd");
            String ref = getDeepNestedText(cdtTrfTxInf, "RmtInf", "Strd", "CdtrRefInf", "Ref");
            request.remittanceInfo(ustrd);
            request.structuredRef(ref);

            return request.build();

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse pacs.008: ", e);
            throw new BusinessException("Failed to parse pacs.008 message: " + e.getMessage());
        }
    }

    // ========================================================================
    // PARSE camt.054 MESSAGE (Credit Notification)
    // ========================================================================

    /**
     * Parse camt.054 XML and convert to InwardPaymentRequest.
     * Uses standard Java XML parsing instead of Prowide library.
     */
    public InwardPaymentRequest parseCamt054(String xml) {
        try {
            Document doc = parseXml(xml);

            // Get Group Header
            String msgId = getElementText(doc, "MsgId");

            // Get Notification
            NodeList ntfctnList = doc.getElementsByTagName("Ntfctn");
            if (ntfctnList.getLength() == 0) {
                throw new BusinessException("No Ntfctn found in camt.054");
            }

            Element ntfctn = (Element) ntfctnList.item(0);

            // Get entries and find credit entry
            NodeList ntryList = ntfctn.getElementsByTagName("Ntry");
            Element creditEntry = null;

            for (int i = 0; i < ntryList.getLength(); i++) {
                Element ntry = (Element) ntryList.item(i);
                String cdtDbtInd = getElementTextFromParent(ntry, "CdtDbtInd");
                if ("CRDT".equals(cdtDbtInd)) {
                    creditEntry = ntry;
                    break;
                }
            }

            if (creditEntry == null) {
                throw new BusinessException("No credit entry found in camt.054");
            }

            var request = InwardPaymentRequest.builder()
                    .messageId(msgId)
                    .creationDateTime(LocalDateTime.now())
                    .channel("CAMT054")
                    .rawXml(xml);

            // Amount
            Element amtElement = getFirstElement(creditEntry, "Amt");
            if (amtElement != null) {
                request.amount(new BigDecimal(amtElement.getTextContent().trim()));
                request.currency(amtElement.getAttribute("Ccy"));
            }

            // Account (VIBAN)
            String acctIban = getNestedText(ntfctn, "Acct", "IBAN");
            request.creditorAccount(acctIban);

            // Entry details
            Element ntryDtls = getFirstElement(creditEntry, "NtryDtls");
            if (ntryDtls != null) {
                Element txDtls = getFirstElement(ntryDtls, "TxDtls");
                if (txDtls != null) {
                    // References
                    String instrId = getNestedText(txDtls, "Refs", "InstrId");
                    String endToEndId = getNestedText(txDtls, "Refs", "EndToEndId");
                    request.instructionId(instrId);
                    request.endToEndId(endToEndId);

                    // Related parties - Debtor
                    String debtorName = getDeepNestedText(txDtls, "RltdPties", "Dbtr", "Pty", "Nm");
                    String debtorAcct = getDeepNestedText(txDtls, "RltdPties", "DbtrAcct", "Id", "IBAN");
                    request.debtorName(debtorName);
                    request.debtorAccount(debtorAcct);

                    // Remittance info
                    String ustrd = getNestedText(txDtls, "RmtInf", "Ustrd");
                    request.remittanceInfo(ustrd);
                }
            }

            return request.build();

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse camt.054: ", e);
            throw new BusinessException("Failed to parse camt.054 message: " + e.getMessage());
        }
    }

    // ========================================================================
    // XML PARSING HELPERS
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

    private String getElementTextFromParent(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent().trim() : null;
    }

    private Element getFirstElement(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        return nodes.getLength() > 0 ? (Element) nodes.item(0) : null;
    }

    private String getNestedText(Element parent, String parentTag, String childTag) {
        Element nested = getFirstElement(parent, parentTag);
        if (nested != null) {
            return getElementTextFromParent(nested, childTag);
        }
        return null;
    }

    private String getDeepNestedText(Element parent, String... tags) {
        Element current = parent;
        for (int i = 0; i < tags.length - 1; i++) {
            current = getFirstElement(current, tags[i]);
            if (current == null) return null;
        }
        return getElementTextFromParent(current, tags[tags.length - 1]);
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    private void validateRequest(InwardPaymentRequest request) {
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Invalid amount: must be positive");
        }
        if (request.getCurrency() == null || request.getCurrency().length() != 3) {
            throw new BusinessException("Invalid currency code");
        }
        if (request.getCreditorAccount() == null || request.getCreditorAccount().isBlank()) {
            throw new BusinessException("Creditor account (VIBAN) is required");
        }
    }

    private String extractViban(String creditorAccount) {
        // Remove spaces and normalize
        return creditorAccount.replaceAll("\\s+", "").toUpperCase();
    }

    private InwardPaymentResponse buildErrorResponse(InwardPaymentRequest request, long startTime,
                                                      String errorCode, String errorMessage) {
        return InwardPaymentResponse.builder()
                .success(false)
                .statusCode("RJCT")
                .statusReason(errorMessage)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .processingTimeMs(System.currentTimeMillis() - startTime)
                .build();
    }
}
