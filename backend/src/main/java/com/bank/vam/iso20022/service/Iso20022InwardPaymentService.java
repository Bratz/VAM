package com.bank.vam.iso20022.service;

import com.bank.vam.dto.TransactionDto;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.receivables.InwardFxHold;
import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.entity.viban.Viban;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.fx.deal.FxPaymentService;
import com.bank.vam.fx.deal.Side;
import com.bank.vam.repository.receivables.InwardFxHoldRepository;
import com.bank.vam.repository.receivables.ReceivableRepository;
import com.bank.vam.iso20022.dto.Iso20022PaymentDto.*;
import com.bank.vam.iso20022.mapper.Iso20022MessageMapper;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.viban.VibanRepository;
import com.bank.vam.service.TransactionService;
import com.bank.vam.service.receivables.ReconciliationService;
import com.bank.vam.service.treasury.FxRateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
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
import java.util.List;
import java.util.Optional;
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
    private final ReconciliationService reconciliationService;
    private final FxPaymentService fxPayments;
    private final FxRateService fxRates;
    private final ReceivableRepository receivableRepository;
    private final InwardFxHoldRepository fxHolds;

    // ========================================================================
    // PROCESS INWARD PAYMENT (Main Entry Point)
    // ========================================================================

    /**
     * Process inward payment from DTO request.
     * This is the main entry point for ISO 20022 inward payments.
     */
    @Transactional
    public InwardPaymentResponse processInwardPayment(InwardPaymentRequest request) {
        return process(request, null);
    }

    /** The corporate's inward credits held for a negotiated rate (ihb-fx D8), oldest first. */
    @Transactional(readOnly = true)
    public List<InwardFxHold> heldForFx(UUID corporateId) {
        return fxHolds.findByCorporateIdAndStatusOrderByCreatedAtAsc(corporateId, InwardFxHold.AWAITING_FX);
    }

    /**
     * Posts a held credit (ihb-fx D8) through the normal inward path, converted at the firm quote treasury accepted:
     * convert on the quote, the 4-leg collection, settleIncoming, reconciliation (invoice paid, subsidiary credited).
     * All or nothing in this transaction: a quote that expired, or any refusal, leaves the credit held.
     */
    @Transactional
    public InwardPaymentResponse postHeld(UUID holdId, UUID fxQuoteId) {
        InwardFxHold h = fxHolds.lockById(holdId).orElseThrow(() -> new ResourceNotFoundException("Held credit not found: " + holdId));
        if (!InwardFxHold.AWAITING_FX.equals(h.getStatus())) {
            throw new BusinessException("FX_HOLD_NOT_WAITING", "This credit is already " + h.getStatus().toLowerCase()
                + (h.getTransactionRef() != null ? " (" + h.getTransactionRef() + ")" : ""));
        }
        if (fxQuoteId == null) {
            throw new BusinessException("FX_HOLD_NEEDS_QUOTE", "Accept a firm quote to post this credit");
        }
        InwardPaymentResponse r = process(h.toRequest(), fxQuoteId);
        if (!r.isSuccess() || r.getTransactionId() == null) {
            throw new BusinessException("FX_HOLD_NOT_POSTED", "Not posted: " + r.getStatusReason());
        }
        h.setStatus(InwardFxHold.POSTED);
        h.setTransactionId(r.getTransactionId());
        h.setTransactionRef(r.getTransactionReference());
        h.setPostedAt(LocalDateTime.now());
        fxHolds.save(h);
        return r;
    }

    /**
     * Treasury will not convert a held credit (ihb-fx D8): it goes back to the payer. Marked RETURNED with the reason,
     * who and when; nothing was posted, so nothing is reversed. ponytail: no outgoing return message (pacs.004) in v1;
     * the bank returns the funds by hand from this record. Locked, so it cannot be posted and returned at once.
     */
    @Transactional
    public InwardFxHold returnHeld(UUID holdId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("FX_HOLD_RETURN_REASON", "Say why the credit is returned to the payer");
        }
        InwardFxHold h = fxHolds.lockById(holdId).orElseThrow(() -> new ResourceNotFoundException("Held credit not found: " + holdId));
        if (!InwardFxHold.AWAITING_FX.equals(h.getStatus())) {
            throw new BusinessException("FX_HOLD_NOT_WAITING", "This credit is already " + h.getStatus().toLowerCase());
        }
        h.setStatus(InwardFxHold.RETURNED);
        h.setReturnReason(reason.trim());
        h.setReturnedBy(com.bank.vam.config.CurrentUser.name());
        h.setReturnedAt(LocalDateTime.now());
        return fxHolds.save(h);
    }

    /** {@code fxQuoteId}: posting a held credit at treasury's firm quote; null for a credit as it arrives. */
    private InwardPaymentResponse process(InwardPaymentRequest request, UUID fxQuoteId) {
        long startTime = System.currentTimeMillis();

        log.info("Processing ISO 20022 inward payment: amount={} {}, creditorAccount={}",
                request.getAmount(), request.getCurrency(), request.getCreditorAccount());

        try {
            // 1. Validate request
            validateRequest(request);
            request.setCurrency(request.getCurrency().toUpperCase());

            // 2. Lookup VIBAN or VA by number
            // The creditorAccount can be either a VIBAN or a VA number
            String accountNumber = extractViban(request.getCreditorAccount());

            Viban viban = null;
            VirtualAccount va = null;
            String vibanNumber = null;

            // First try to find as VIBAN
            var vibanOpt = vibanRepository.findByViban(accountNumber);
            if (vibanOpt.isPresent()) {
                viban = vibanOpt.get();
                vibanNumber = accountNumber;

                if (!viban.canReceiveFunds()) {
                    return buildErrorResponse(request, startTime, "AC04", "VIBAN cannot accept payment: " + viban.getStatus());
                }

                // Get Virtual Account from VIBAN
                va = virtualAccountRepository.findById(viban.getVirtualAccountId())
                        .orElseThrow(() -> new BusinessException("Virtual account not found for VIBAN"));

                // Validate amount against VIBAN constraints
                // A paid VIBAN's bounds were for the invoice it settled; a late payment is taken whatever its amount.
                // Bounds are in the VIBAN's currency (a COBO invoice's may differ from treasury's account, ihb-fx);
                // a payment in another one is compared at the indicative rate (nothing is booked before it is accepted).
                if (viban.canAcceptPayment() && !viban.isAmountInBounds(inVibanCurrency(request, viban, va, null))) {
                    return buildErrorResponse(request, startTime, "AM02", "Payment amount out of allowed bounds");
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

            // 2b. Idempotency guard: a re-delivered message or a re-uploaded bulk file must not
            // post the same payment twice. Keyed on endToEndId + target account + gross amount/currency
            // (not endToEndId alone) so a coincidentally-reused ID against a different payment
            // still processes normally. processCollection stores the endToEndId on the credited
            // leg, which carries the gross (rows booked before 2026-11-09 carried the net, so amount
            // + fee matches those). NOTPROVIDED is
            // the ISO placeholder for "no id", so it never identifies a payment.
            if (request.getEndToEndId() != null && !request.getEndToEndId().isBlank()
                    && !"NOTPROVIDED".equalsIgnoreCase(request.getEndToEndId())) {
                Optional<Transaction> duplicate = transactionRepository.findAllByEndToEndIdAndVaId(request.getEndToEndId(), va.getId())
                        .stream()
                        .filter(t -> ((request.getAmount().compareTo(t.getAmount()) == 0
                                        || request.getAmount().compareTo(t.getAmount().add(
                                                t.getFeeAmount() != null ? t.getFeeAmount() : BigDecimal.ZERO)) == 0)
                                    && request.getCurrency().equalsIgnoreCase(t.getCurrencyCode()))
                                // A converted credit is booked in the account's currency; it keeps what was sent.
                                || (t.getInstructedAmount() != null && request.getAmount().compareTo(t.getInstructedAmount()) == 0
                                    && request.getCurrency().equalsIgnoreCase(t.getInstructedCurrency())))
                        .findFirst();
                if (duplicate.isPresent()) {
                    return buildDuplicateResponse(request, startTime, duplicate.get(), va, vibanNumber, viban);
                }
                // A re-delivered credit that is held for FX answers with that hold, not a second one.
                Optional<InwardFxHold> held = fxQuoteId != null ? Optional.empty()
                        : fxHolds.findByVirtualAccountIdAndEndToEndId(va.getId(), request.getEndToEndId()).stream()
                            .filter(h -> InwardFxHold.AWAITING_FX.equals(h.getStatus()) && request.getAmount().compareTo(h.getAmount()) == 0
                                && request.getCurrency().equalsIgnoreCase(h.getCurrency()))
                            .findFirst();
                if (held.isPresent()) {
                    return buildHeldResponse(startTime, held.get(), va, "Duplicate payment - already held awaiting FX");
                }
            }

            // ihb-fx D8: a credit to a COBO invoice's VIBAN that needs a negotiated rate (what T's deals leave is above
            // the live-price limit) is accepted and held, never bounced: PDNG, nothing posted, until treasury accepts a
            // quote (postHeld). Asked before converting: needsQuote books nothing and cannot doom this transaction (a
            // line refusal, D6, is an ordinary RJCT below). ponytail: COBO VIBANs only (spec scope); any other
            // account keeps today's refusal from convertIncoming (FX_COLLECTION_NEEDS_DEAL).
            if (fxQuoteId == null && viban != null) {
                Receivable invoice = coboInvoice(viban);
                String why = invoice == null ? null : fxPayments.needsQuote(va, request.getCurrency(), request.getAmount(), Side.SELL);
                if (why != null) {
                    InwardFxHold h = InwardFxHold.of(request);
                    h.setCorporateId(va.getCorporateId());
                    h.setVirtualAccountId(va.getId());
                    h.setVaNumber(va.getVaNumber());
                    h.setCollectorEntityId(va.getOwningEntityId());
                    h.setSettleCurrency(va.getCurrencyCode());
                    h.setVibanId(viban.getId());
                    h.setViban(vibanNumber);
                    h.setReceivableId(invoice.getId());
                    h.setReceivableNumber(invoice.getReceivableNumber());
                    h.setSubsidiaryCode(invoice.getOwningEntityCode());
                    h.setReason(why);
                    log.info("Inward credit {} {} to COBO VIBAN {} held for a negotiated rate: {}", request.getCurrency(),
                            request.getAmount(), vibanNumber, why);
                    return buildHeldResponse(startTime, fxHolds.save(h), va, "Accepted, awaiting FX: " + why
                            + "; treasury negotiates the rate and posts it");
                }
            }

            // 3. Build CollectionRequest for 4-leg accounting via TransactionService
            // This delegates to TransactionService.processCollection() which creates:
            // CBS → Shadow VA → Settlement VA → Target VA (4 balanced entries)

            // A payment in another currency than the account's is converted on credit, as direct debits are: the
            // account owner's deals selling that currency first, then the live price (refused if neither can).
            // Before this, a USD 1,000 credit to an AED account was booked as AED 1,000.
            FxPaymentService.Conversion fx = fxQuoteId == null ? fxPayments.convertIncoming(va, request.getCurrency(), request.getAmount())
                    : fxPayments.convertIncoming(va, request.getCurrency(), request.getAmount(), fxQuoteId);
            BigDecimal credited = fx != null ? fx.debitAmount() : request.getAmount();
            String creditCcy = fx != null ? fx.debitCurrency() : request.getCurrency();

            TransactionDto.CollectionRequest collectionRequest = TransactionDto.CollectionRequest.builder()
                    .targetVaId(va.getId())
                    .amount(credited)
                    .currencyCode(creditCcy)
                    .valueDate(LocalDate.now())
                    .remitterName(request.getDebtorName())
                    .remitterAccount(request.getDebtorAccount())
                    .bankReference(request.getMessageId())
                    .viban(vibanNumber)
                    .endToEndId(request.getEndToEndId())
                    .remittanceInfo(request.getRemittanceInfo())
                    .invoiceReference(request.getStructuredRef())
                    .channel(request.getChannel() != null ? request.getChannel() : "ISO20022")
                    .externalReference(request.getInstructionId())
                    .build();

            // 4. Process collection using 4-leg accounting
            TransactionDto.CollectionResponse collectionResponse = transactionService.processCollection(collectionRequest);
            if (fx != null) {
                fxPayments.settleIncoming(fx, transactionRepository.findById(collectionResponse.getTransactionId()).orElseThrow(), va);
            }

            // 5. Update VIBAN usage stats (only if routed via VIBAN)
            if (viban != null) {
                viban.recordPayment(inVibanCurrency(request, viban, va, credited));
                vibanRepository.save(viban);
            }

            // 5b. Attempt real reconciliation against an open Receivable -- this is what actually
            // closes the loop so a real VIBAN payment marks its invoice PAID/PARTIAL, not just the
            // ledger posting above. ReconciliationService requires a real vibanId (its first step
            // is a lookup by id), so this only runs for VIBAN-routed payments -- a payment routed
            // directly by VA number has no VIBAN to reconcile against, same ceiling as before.
            // Known, accepted interaction: ReconciliationService.attemptAutoReconcile's DIRECT
            // match tier internally calls its own viban.recordPayment() a second time; step 5
            // above is kept as the one place VIBAN stats reliably update for every match tier, so
            // a DIRECT match double-counts VIBAN stats specifically. Not fixed here -- see the
            // build plan's note; it's a pre-existing inconsistency inside ReconciliationService
            // itself, not something this wiring introduces.
            ReconciliationService.ReconciliationResult reconciliation = null;
            if (viban != null) {
                reconciliation = reconciliationService.attemptAutoReconcile(
                        collectionResponse.getTransactionId(), viban.getId(), credited,
                        creditCcy, request.getDebtorName(), request.getDebtorAccount(),
                        request.getRemittanceInfo());
            }

            // 6. Map CollectionResponse to InwardPaymentResponse
            long processingTime = System.currentTimeMillis() - startTime;
            log.info("ISO 20022 inward payment completed with 4-leg accounting: txn={}, va={}, viban={}, amount={} {}, " +
                    "grossAmount={}, netAmount={}, fee={}, time={}ms",
                    collectionResponse.getReferenceNumber(), va.getVaNumber(), vibanNumber,
                    request.getAmount(), request.getCurrency(),
                    collectionResponse.getGrossAmount(), collectionResponse.getNetAmount(),
                    collectionResponse.getFeeAmount(), processingTime);

            boolean autoReconciled = reconciliation != null && reconciliation.isSuccess();

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
                    .reconciledReferenceType(reconciliation != null && reconciliation.getMatchType() != null
                            ? reconciliation.getMatchType().name() : null)
                    .reconciledReferenceId(reconciliation != null && reconciliation.getReceivableId() != null
                            ? reconciliation.getReceivableId().toString() : null)
                    .processingTimeMs(processingTime)
                    .build();

        } catch (BusinessException e) {
            log.warn("ISO 20022 inward payment failed: {}", e.getMessage());
            rethrowIfTransactionDoomed(e);
            return buildErrorResponse(request, startTime, "RJCT", e.getMessage());
        } catch (Exception e) {
            log.error("ISO 20022 inward payment error: ", e);
            rethrowIfTransactionDoomed(e);
            return buildErrorResponse(request, startTime, "TECH", "Technical error: " + e.getMessage());
        }
    }

    /**
     * Swallowing an exception here does not un-doom the transaction. This method is
     * {@code @Transactional} and calls two proxied {@code @Transactional} collaborators --
     * {@code TransactionService.processCollection} and
     * {@code ReconciliationService.attemptAutoReconcile}. When one of those throws, its own
     * interceptor marks the shared physical transaction rollback-only on the way out. Returning a
     * tidy failure response after that is a lie: this method's own commit then fails with
     * UnexpectedRollbackException, the caller sees "Transaction silently rolled back because it
     * has been marked as rollback-only", and the real cause survives only in the log above.
     *
     * <p>Confirmed live: every row of ingest job KAN-31 failed with exactly that text, so the
     * file-ingest UI showed three identical meaningless reasons for whatever actually went wrong.
     *
     * <p>So when the transaction is already doomed, rethrow and let the real exception be what the
     * caller sees. A failure that never touched an inner proxy -- validateRequest rejecting the
     * payload, an unknown creditor account -- leaves the transaction clean and still returns the
     * structured RJCT/TECH response the REST controller renders, unchanged.
     */
    private void rethrowIfTransactionDoomed(Exception cause) {
        TransactionStatus status;
        try {
            status = TransactionAspectSupport.currentTransactionStatus();
        } catch (NoTransactionException noTransaction) {
            return; // called outside a transaction (tests, direct instantiation): response is committable
        }
        if (!status.isRollbackOnly()) {
            return;
        }
        log.error("Inward payment transaction is rollback-only; rethrowing the real cause instead of"
                + " returning a response that cannot commit");
        if (cause instanceof RuntimeException runtime) {
            throw runtime;
        }
        throw new IllegalStateException(cause.getMessage(), cause);
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
     * Parse camt.054 XML into one InwardPaymentRequest per credited transaction: every Ntfctn,
     * every CRDT Ntry, and every TxDtls inside a batched entry (a bulk collection is typically one
     * Ntry carrying N TxDtls). Each line is routed by its own CdtrAcct IBAN when present (the
     * VIBAN), else by the notification's Acct. A batched line with no amount of its own is
     * returned without one so it fails validation alone, rather than posting the batch total.
     */
    public List<InwardPaymentRequest> parseCamt054(String xml) {
        try {
            Document doc = parseXml(xml);
            String msgId = getElementText(doc, "MsgId");

            NodeList ntfctnList = doc.getElementsByTagName("Ntfctn");
            if (ntfctnList.getLength() == 0) {
                throw new BusinessException("No Ntfctn found in camt.054");
            }

            List<InwardPaymentRequest> requests = new java.util.ArrayList<>();
            for (int n = 0; n < ntfctnList.getLength(); n++) {
                Element ntfctn = (Element) ntfctnList.item(n);
                String acctIban = getNestedText(ntfctn, "Acct", "IBAN");

                for (Element ntry : children(ntfctn, "Ntry")) {
                    Element ind = child(ntry, "CdtDbtInd");
                    if (ind == null || !"CRDT".equals(ind.getTextContent().trim())) continue;

                    List<Element> txs = new java.util.ArrayList<>();
                    for (Element dtls : children(ntry, "NtryDtls")) txs.addAll(children(dtls, "TxDtls"));
                    Element entryAmt = child(ntry, "Amt");
                    if (txs.isEmpty()) {
                        requests.add(camt054Line(xml, msgId, acctIban, entryAmt, null));
                    } else {
                        for (Element tx : txs) {
                            Element amt = txs.size() == 1 ? entryAmt : txAmount(tx);
                            requests.add(camt054Line(xml, msgId, acctIban, amt, tx));
                        }
                    }
                }
            }

            if (requests.isEmpty()) {
                throw new BusinessException("No credit entry found in camt.054");
            }
            return requests;

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse camt.054: ", e);
            throw new BusinessException("Failed to parse camt.054 message: " + e.getMessage());
        }
    }

    private InwardPaymentRequest camt054Line(String xml, String msgId, String acctIban, Element amt, Element tx) {
        var request = InwardPaymentRequest.builder()
                .messageId(msgId)
                .creationDateTime(LocalDateTime.now())
                .channel("CAMT054")
                .rawXml(xml)
                .creditorAccount(acctIban);
        if (amt != null) {
            request.amount(new BigDecimal(amt.getTextContent().trim()));
            request.currency(amt.getAttribute("Ccy"));
        }
        if (tx != null) {
            request.instructionId(getNestedText(tx, "Refs", "InstrId"));
            request.endToEndId(getNestedText(tx, "Refs", "EndToEndId"));
            request.debtorName(getDeepNestedText(tx, "RltdPties", "Dbtr", "Pty", "Nm"));
            request.debtorAccount(getDeepNestedText(tx, "RltdPties", "DbtrAcct", "Id", "IBAN"));
            request.remittanceInfo(getNestedText(tx, "RmtInf", "Ustrd"));
            String cdtrIban = getDeepNestedText(tx, "RltdPties", "CdtrAcct", "Id", "IBAN");
            if (cdtrIban != null && !cdtrIban.isBlank()) request.creditorAccount(cdtrIban);
        }
        return request.build();
    }

    /** A batched line's own amount: TxDtls/Amt (camt.054.001.08) or AmtDtls/TxAmt/Amt (.001.02). */
    private Element txAmount(Element tx) {
        Element amt = child(tx, "Amt");
        if (amt != null) return amt;
        Element dtls = child(tx, "AmtDtls");
        Element txAmt = dtls == null ? null : child(dtls, "TxAmt");
        return txAmt == null ? null : child(txAmt, "Amt");
    }

    private List<Element> children(Element parent, String tagName) {
        List<Element> out = new java.util.ArrayList<>();
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element e && tagName.equals(e.getTagName())) out.add(e);
        }
        return out;
    }

    private Element child(Element parent, String tagName) {
        List<Element> found = children(parent, tagName);
        return found.isEmpty() ? null : found.get(0);
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

    /**
     * The payment in the VIBAN's currency (the account's when it names none): as sent when in it, as credited to
     * {@code va} when that is it ({@code credited} null before the conversion), else at the indicative rate; books nothing.
     */
    private BigDecimal inVibanCurrency(InwardPaymentRequest request, Viban viban, VirtualAccount va, BigDecimal credited) {
        String ccy = viban.getCurrencyCode() != null ? viban.getCurrencyCode() : va.getCurrencyCode();
        if (ccy.equals(request.getCurrency())) return request.getAmount();
        if (credited != null && ccy.equals(va.getCurrencyCode())) return credited;
        return fxRates.convert(request.getAmount(), request.getCurrency(), ccy);
    }

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

    /** The COBO invoice behind a VIBAN (the VIBAN's invoice reference, else the invoice linked to it), or null. */
    private Receivable coboInvoice(Viban viban) {
        Optional<Receivable> r = Optional.empty();
        if (("INVOICE".equals(viban.getReferenceType()) || "RECEIVABLE".equals(viban.getReferenceType())) && viban.getReferenceId() != null) {
            try {
                r = receivableRepository.findById(UUID.fromString(viban.getReferenceId()));
            } catch (IllegalArgumentException notAnId) {
                // not an invoice id: fall back to the link below
            }
        }
        if (r.isEmpty()) {
            r = receivableRepository.findByPrimaryVibanId(viban.getId());
        }
        return r.filter(x -> Boolean.TRUE.equals(x.getIsCobo()) && x.getCoboCollectorEntityId() != null).orElse(null);
    }

    /** PDNG: accepted, held for a negotiated rate (ihb-fx D8); nothing posted, so the balance is unchanged. */
    private InwardPaymentResponse buildHeldResponse(long startTime, InwardFxHold h, VirtualAccount va, String reason) {
        return InwardPaymentResponse.builder()
                .success(true)
                .statusCode("PDNG")
                .statusReason(reason)
                .fxHoldId(h.getId())
                .virtualAccountId(va.getId())
                .vaNumber(va.getVaNumber())
                .vibanId(h.getVibanId())
                .viban(h.getViban())
                .balanceBefore(va.getCurrentBalance())
                .balanceAfter(va.getCurrentBalance())
                .processingTimeMs(System.currentTimeMillis() - startTime)
                .build();
    }

    /** No new ledger entries here on purpose — this IS the idempotency guard's no-op path. */
    private InwardPaymentResponse buildDuplicateResponse(InwardPaymentRequest request, long startTime,
                                                          Transaction existing, VirtualAccount va,
                                                          String vibanNumber, Viban viban) {
        log.info("Duplicate inward payment suppressed: endToEndId={}, va={}, existingTxn={}",
                request.getEndToEndId(), va.getVaNumber(), existing.getId());
        return InwardPaymentResponse.builder()
                .success(true)
                .statusCode("ACCP")
                .statusReason("Duplicate payment - endToEndId already processed")
                .transactionReference(existing.getReferenceNumber())
                .transactionId(existing.getId())
                .virtualAccountId(va.getId())
                .vaNumber(va.getVaNumber())
                .vibanId(viban != null ? viban.getId() : null)
                .viban(vibanNumber)
                .balanceBefore(va.getCurrentBalance())
                .balanceAfter(va.getCurrentBalance())
                .autoReconciled(false)
                .processingTimeMs(System.currentTimeMillis() - startTime)
                .build();
    }
}
