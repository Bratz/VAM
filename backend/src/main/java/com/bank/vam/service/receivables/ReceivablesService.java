package com.bank.vam.service.receivables;

import com.bank.vam.dto.TransactionDto;
import com.bank.vam.dto.receivables.ReceivablesDto.*;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.Transaction.MovementType;
import com.bank.vam.entity.Transaction.TransactionStatus;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.VaStatus;
import com.bank.vam.entity.Corporate;
import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.entity.treasury.ExceptionTransaction;
import com.bank.vam.entity.treasury.ExceptionTransaction.ExceptionType;
import com.bank.vam.entity.treasury.ExceptionTransaction.ExceptionStatus;
import com.bank.vam.entity.viban.Viban;
import com.bank.vam.dto.viban.VibanDto.VibanResponse;
import com.bank.vam.dto.viban.VibanDto.VibanCreateRequest;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.CorporateRepository;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.receivables.ReceivableRepository;
import com.bank.vam.repository.receivables.UnmatchedPaymentRepository;
import com.bank.vam.entity.receivables.UnmatchedPayment;
import com.bank.vam.repository.treasury.ExceptionTransactionRepository;
import com.bank.vam.repository.viban.VibanRepository;
import com.bank.vam.service.TransactionService;
import com.bank.vam.service.treasury.SettlementVaResolverService;
import com.bank.vam.service.viban.VibanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ReceivablesService - invoices, payment VIBANs, collections and unmatched payments, read from
 * and written to what the platform actually records. Marketplace orders and POS collections have
 * no source yet and list empty; unmatched payments are resolved through {@link ReconciliationService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceivablesService {

    // Core dependencies
    private final VirtualAccountRepository virtualAccountRepository;
    private final TransactionRepository transactionRepository;
    private final VibanRepository vibanRepository;
    private final ReceivableRepository receivableRepository;
    private final VibanService vibanService;
    private final CorporateRepository corporateRepository;

    @Value("${app.frontend-base-url}")
    private String frontendBaseUrl;

    // Transaction service for proper 4-leg accounting
    private final TransactionService transactionService;

    // Exception handling - for routing unmatched/invalid collections
    private final SettlementVaResolverService settlementVaResolver;
    private final ExceptionTransactionRepository exceptionTransactionRepository;

    private final CoboEnrolmentService coboEnrolments;
    private final com.bank.vam.outbox.OutboxEmailRepository outbox;
    private final UnmatchedPaymentRepository unmatchedPaymentRepository;
    private final ReconciliationService reconciliationService;

    // ========================================================================
    // STATS
    // ========================================================================

    public ReceivablesStatsResponse getStats(UUID corporateId) {
        List<InvoiceResponse> invoices = getAllInvoices(corporateId, null);
        List<EcommerceOrderResponse> orders = getAllEcommerceOrders(corporateId, null, null);
        List<PaymentVibanResponse> vibans = getAllPaymentVibans(corporateId, null);
        List<UnmatchedPaymentResponse> unmatched = getUnmatchedPayments(corporateId);
        List<PosCollectionResponse> posCollections = getAllPosCollections(corporateId, null, null);

        BigDecimal invoiceTotal = invoices.stream().map(InvoiceResponse::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal invoiceCollected = invoices.stream().map(InvoiceResponse::getPaidAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal ecomTotal = orders.stream().map(EcommerceOrderResponse::getNetAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal ecomReleased = orders.stream()
            .filter(o -> "RELEASED".equals(o.getEscrowStatus()))
            .map(EcommerceOrderResponse::getNetAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal ecomInEscrow = orders.stream()
            .filter(o -> "HELD".equals(o.getEscrowStatus()))
            .map(EcommerceOrderResponse::getNetAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal vibanTotal = vibans.stream().map(PaymentVibanResponse::getExpectedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal vibanCollected = vibans.stream().map(PaymentVibanResponse::getReceivedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal posTotal = posCollections.stream().map(PosCollectionResponse::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal posSettled = posCollections.stream()
            .filter(p -> "SETTLED".equals(p.getSettlementStatus()))
            .map(PosCollectionResponse::getNetAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal posPending = posCollections.stream()
            .filter(p -> "PENDING".equals(p.getSettlementStatus()))
            .map(PosCollectionResponse::getNetAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        int pendingCount = (int) invoices.stream().filter(i -> "OPEN".equals(i.getStatus()) || "PARTIAL".equals(i.getStatus())).count()
            + (int) orders.stream().filter(o -> "PENDING".equals(o.getPaymentStatus())).count()
            + (int) vibans.stream().filter(v -> "ACTIVE".equals(v.getStatus())).count();

        // Calculate outstanding and overdue
        BigDecimal outstanding = invoices.stream()
            .filter(i -> !"PAID".equals(i.getStatus()) && !"CANCELLED".equals(i.getStatus()))
            .map(i -> i.getAmount().subtract(i.getPaidAmount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal overdueAmount = invoices.stream()
            .filter(i -> "OVERDUE".equals(i.getStatus()))
            .map(i -> i.getAmount().subtract(i.getPaidAmount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal unmatchedAmount = unmatched.stream()
            .filter(u -> "PENDING".equals(u.getStatus()))
            .map(UnmatchedPaymentResponse::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        return ReceivablesStatsResponse.builder()
            .totalReceivables(invoiceTotal.add(ecomTotal).add(vibanTotal).add(posTotal))
            .collected(invoiceCollected.add(ecomReleased).add(vibanCollected).add(posSettled))
            .inEscrow(ecomInEscrow)
            .outstanding(outstanding)
            .overdueAmount(overdueAmount)
            .unmatchedAmount(unmatchedAmount)
            .pendingCount(pendingCount)
            .invoiceTotal(invoiceTotal)
            .invoiceCollected(invoiceCollected)
            .ecommerceTotal(ecomTotal)
            .ecommerceReleased(ecomReleased)
            .ecommerceInEscrow(ecomInEscrow)
            .vibanTotal(vibanTotal)
            .vibanCollected(vibanCollected)
            .posTotal(posTotal)
            .posSettled(posSettled)
            .posPendingSettlement(posPending)
            .invoiceCount(invoices.size())
            .ecommerceOrderCount(orders.size())
            .vibanCount(vibans.size())
            .unmatchedPaymentCount((int) unmatched.stream().filter(u -> "PENDING".equals(u.getStatus())).count())
            .posTransactionCount(posCollections.size())
            .build();
    }

    // ========================================================================
    // INVOICES (Existing - Preserved for backward compatibility)
    // ========================================================================

    public List<InvoiceResponse> getAllInvoices(UUID corporateId, UUID legalEntityId, String status) {
        log.info("Getting all invoices - corporateId: {}, legalEntityId: {}, status: {}", corporateId, legalEntityId, status);

        List<Receivable> receivables;

        // Query from database based on filters
        if (corporateId != null && legalEntityId != null) {
            // Both corporate and legal entity specified
            receivables = receivableRepository.findByCorporateId(corporateId).stream()
                .filter(r -> legalEntityId.equals(r.getOwningEntityId()))
                .collect(Collectors.toList());
            log.info("Filtered by corporateId AND legalEntityId, found {} receivables", receivables.size());
        } else if (corporateId != null) {
            // Only corporate specified
            receivables = receivableRepository.findByCorporateId(corporateId);
            log.info("Filtered by corporateId only, found {} receivables", receivables.size());
        } else if (legalEntityId != null) {
            // Only legal entity specified
            receivables = receivableRepository.findByOwningEntityId(legalEntityId);
            log.info("Filtered by legalEntityId only, found {} receivables", receivables.size());
        } else {
            // No filters - get all (limited for safety)
            receivables = receivableRepository.findAll();
            log.info("No filters, found {} receivables", receivables.size());
        }

        // Filter by status if provided
        if (status != null && !status.isEmpty()) {
            try {
                Receivable.ReceivableStatus statusEnum = Receivable.ReceivableStatus.valueOf(status.toUpperCase());
                receivables = receivables.stream()
                    .filter(r -> r.getStatus() == statusEnum)
                    .collect(Collectors.toList());
                log.info("Filtered by status {}, {} receivables remaining", status, receivables.size());
            } catch (IllegalArgumentException e) {
                log.warn("Invalid status filter: {}", status);
            }
        }

        // Convert to InvoiceResponse
        List<InvoiceResponse> invoices = receivables.stream()
            .map(this::mapReceivableToInvoiceResponse)
            .collect(Collectors.toList());

        log.info("Mapped {} receivables to invoices", invoices.size());
        for (InvoiceResponse inv : invoices) {
            log.info("Invoice: id={}, number={}, amount={}, status={}, corporateId=N/A (not in DTO)",
                inv.getId(), inv.getInvoiceNumber(), inv.getAmount(), inv.getStatus());
        }

        // No demo fallback: a corporate with no invoices sees none. The old fallback showed the same four made-up
        // invoices (INV-2024-0156..0159, ids 00000000-...) under every corporate without any.
        log.info("Returning {} invoices", invoices.size());
        return invoices;
    }

    /**
     * Map a Receivable entity to InvoiceResponse DTO.
     */
    private InvoiceResponse mapReceivableToInvoiceResponse(Receivable r) {
        return InvoiceResponse.builder()
            .id(r.getId())
            .invoiceNumber(r.getReceivableNumber())
            .customerName(r.getCustomerName())
            .customerId(r.getCustomerPartyId())
            .customerVaNumber(r.getViban())
            .invoiceDate(r.getIssueDate())
            .dueDate(r.getDueDate())
            .amount(r.getNetAmount())
            .paidAmount(r.getPaidAmount() != null ? r.getPaidAmount() : java.math.BigDecimal.ZERO)
            .outstandingAmount(r.getOutstandingAmount() != null ? r.getOutstandingAmount() : r.getNetAmount())
            .currencyCode(r.getCurrencyCode())
            .status(r.getStatus() != null ? r.getStatus().name() : "OPEN")
            .description(r.getDescription())
            .vibanId(r.getPrimaryVibanId())
            .vibanNumber(r.getViban())
            .viban(r.getViban())
            .autoReconcile(r.getAutoReconcile())
            .allowPartialPayment(r.getAllowPartialPayment())
            .hierarchyNodeId(r.getHierarchyNodeId())
            .hierarchyPath(r.getHierarchyPath())
            .createdAt(r.getCreatedAt())
            // Phase 3 fields for frontend mapping
            .owningEntityId(r.getOwningEntityId())
            .owningEntityCode(r.getOwningEntityCode())
            .owningEntityName(r.getOwningEntityName())
            .isIntercompany(r.getIsIntercompany())
            .intercompanyEntityId(r.getIntercompanyEntityId())
            .intercompanyEntityCode(r.getIntercompanyEntityCode())
            .intercompanyEntityName(r.getIntercompanyEntityName())
            .isCobo(r.getIsCobo())
            .coboCollectorEntityId(r.getCoboCollectorEntityId())
            .coboCollectorEntityCode(r.getCoboCollectorEntityCode())
            .coboCollectorEntityName(r.getCoboCollectorEntityName())
            .coboRequestStatus(r.getCoboRequestStatus() != null ? r.getCoboRequestStatus().name() : "NOT_REQUESTED")
            .nettingEligible(r.getNettingEligible())
            .nettingStatus(r.getNettingStatus() != null ? r.getNettingStatus().name() : "NOT_INCLUDED")
            .collectionRoute(r.getCollectionRoute() != null ? r.getCollectionRoute().name() : "DIRECT")
            .matchedPayments(Collections.emptyList())
            .build();
    }

    /**
     * Backward compatible method - calls new method with null legalEntityId.
     */
    public List<InvoiceResponse> getAllInvoices(UUID corporateId, String status) {
        return getAllInvoices(corporateId, null, status);
    }

    public InvoiceResponse getInvoice(UUID invoiceId) {
        return receivableRepository.findById(invoiceId)
            .map(this::mapReceivableToInvoiceResponse)
            .orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + invoiceId));
    }

    @Transactional
    public InvoiceResponse createInvoice(UUID corporateIdFromHeader, CreateInvoiceRequest request) {
        // Determine corporateId: prefer header, fallback to request body
UUID effectiveCorporateId = corporateIdFromHeader;
        if (effectiveCorporateId == null && request.getCorporateId() != null) {
            effectiveCorporateId = request.getCorporateId();
            log.info("Using corporateId from request body: {}", effectiveCorporateId);
        }
        if (effectiveCorporateId == null) {
            throw new BusinessException("corporateId is required");
        }

        // Determine owningEntityId from request body
        UUID owningEntityId = request.getOwningEntityId();
        String owningEntityCode = request.getOwningEntityCode();

        if (owningEntityId != null) {
            log.info("Using owningEntityId from request body: {}, code: {}", owningEntityId, owningEntityCode);
        } else {
            log.warn("No owningEntityId provided in request body - receivable will have null owningEntityId");
        }

        // The number the user typed, when there is one; it must not repeat within the corporate.
        String typed = request.getInvoiceNumber() == null ? "" : request.getInvoiceNumber().trim();
        if (!typed.isEmpty() && receivableRepository.existsByCorporateIdAndReceivableNumber(effectiveCorporateId, typed)) {
            throw new BusinessException("Invoice number " + typed + " is already used; choose another");
        }
        String invoiceNumber = !typed.isEmpty() ? typed
                : "INV-" + LocalDate.now().getYear() + "-" + String.format("%04d", System.currentTimeMillis() % 10000);
        boolean draft = Boolean.TRUE.equals(request.getDraft());
        String currency = request.getCurrencyCode() != null ? request.getCurrencyCode() : "AED";
        // Collected on behalf when the owning entity is enrolled for this currency, unless the user chose to collect
        // directly (spec D3, D5). The enrolment decides the collector and the account, not the client.
        CoboEnrolmentService.EnrolmentView cobo = Boolean.TRUE.equals(request.getCollectDirectly()) ? null
                : coboEnrolments.active(owningEntityId, currency, LocalDate.now()).orElse(null);
        UUID targetVaId = cobo != null ? cobo.collectionVaId() : request.getTargetVaId();
        // The invoice sits where its collection account sits in the hierarchy.
        VirtualAccount collectionVa = targetVaId == null ? null
                : virtualAccountRepository.findById(targetVaId).orElse(null);

        // Real, shareable "pay this invoice" link -- unconditional (unlike VIBAN generation, this
        // costs nothing and needs no target account) -- see PublicReceivablesController.
        String paymentLinkToken = UUID.randomUUID().toString();
        String paymentLink = frontendBaseUrl + "/?page=pay-invoice&token=" + paymentLinkToken;

        // Save first, without VIBAN fields, so the real DB-generated id exists to embed as the
        // VIBAN's referenceId below -- ReconciliationService.attemptDirectMatch() expects that to
        // parse as this receivable's real id, not the human-readable invoice number. (Assigning
        // the id ourselves pre-save and trusting Hibernate's UUID generator not to overwrite it
        // would be one save cheaper, but isn't a documented guarantee -- this way doesn't need it.)
        Receivable receivable = Receivable.builder()
            .receivableNumber(invoiceNumber)
            .corporateId(effectiveCorporateId)
            .owningEntityId(owningEntityId)
            .owningEntityCode(cobo != null ? cobo.subsidiaryCode() : owningEntityCode)
            .owningEntityName(cobo != null ? cobo.subsidiaryName() : null)
            .customerPartyId(request.getCustomerId())
            .customerName(request.getCustomerName())
            .virtualAccountId(targetVaId)
            .paymentLinkToken(paymentLinkToken)
            .issueDate(request.getIssueDate() != null ? request.getIssueDate() : LocalDate.now())
            .dueDate(request.getDueDate())
            .paymentTermsDays(request.getPaymentTermsDays())
            .externalReference(blankToNull(request.getExternalReference()))
            .customerEmail(blankToNull(request.getCustomerEmail()))
            .grossAmount(request.getAmount())
            .netAmount(request.getAmount())
            .discountAmount(request.getDiscountAmount() != null ? request.getDiscountAmount() : BigDecimal.ZERO)
            .taxAmount(request.getTaxAmount() != null ? request.getTaxAmount() : BigDecimal.ZERO)
            .outstandingAmount(request.getAmount())
            .paidAmount(BigDecimal.ZERO)
            .currencyCode(currency)
            .description(request.getDescription())
            .notes(blankToNull(request.getNotes()))
            .metadata(blankToNull(request.getMetadata()))
            .status(draft ? Receivable.ReceivableStatus.DRAFT : Receivable.ReceivableStatus.OPEN)
            .collectionRoute(cobo != null ? Receivable.CollectionRoute.COBO : Receivable.CollectionRoute.DIRECT)
            // COBO by enrolment: no approval step. APPROVED = collected by treasury, outstanding; applyPayment moves it
            // to COLLECTED when paid. cobo_request_id keeps the enrolment that decided it.
            .isCobo(cobo != null)
            .coboCollectorEntityId(cobo != null ? cobo.collectorEntityId() : null)
            .coboCollectorEntityCode(cobo != null ? cobo.collectorCode() : null)
            .coboCollectorEntityName(cobo != null ? cobo.collectorName() : null)
            .coboRequestId(cobo != null ? cobo.id() : null)
            .coboRequestStatus(cobo != null ? Receivable.CoboRequestStatus.APPROVED : null)
            .allowPartialPayment(Boolean.TRUE.equals(request.getAllowPartialPayment()))
            .allowOverpayment(Boolean.TRUE.equals(request.getAllowOverpayment()))
            .hierarchyNodeId(collectionVa != null && collectionVa.getHierarchyNodeId() != null
                    ? collectionVa.getHierarchyNodeId() : request.getHierarchyNodeId())
            .createdBy("system")
            .build();

        Receivable savedReceivable = receivableRepository.save(receivable);
        log.info("Created receivable {} with ID {}", invoiceNumber, savedReceivable.getId());

        // Generate VIBAN if requested -- a REAL one via VibanService, persisted to the real Viban
        // table so it can actually be matched by a later inward payment (the previous fabricated
        // string here never was: it wasn't saved anywhere, so it could never receive money).
        String vibanNumber = null;
        UUID vibanId = null;

        // A COBO invoice always gets one: on the treasury's pooled collection account the VIBAN is what identifies it.
        if (cobo != null || Boolean.TRUE.equals(request.getCreateViban()) || Boolean.TRUE.equals(request.getGenerateViban())) {
            if (targetVaId == null) {
                throw new BusinessException("A collection account (targetVaId) is required to generate a VIBAN");
            }
            VirtualAccount targetVa = virtualAccountRepository.findById(targetVaId)
                    .orElseThrow(() -> new ResourceNotFoundException("Virtual account not found: " + targetVaId));
            // Not vibanService.createInvoiceViban() -- that helper's fixed parameter list has no
            // room for customerName, which left every invoice-created VIBAN showing a blank
            // customer in the VIBANs tab. validUntil also falls back to 30 days out rather than
            // null when the invoice has no due date, so the tab doesn't show "Invalid Date".
            VibanResponse createdViban = vibanService.createViban(targetVa.getProgramId(), VibanCreateRequest.builder()
                    .virtualAccountId(targetVa.getId())
                    .vibanType(Viban.VibanType.INVOICE)
                    .referenceType(Viban.REF_TYPE_INVOICE)
                    .referenceId(savedReceivable.getId().toString())
                    .expectedAmount(request.getAmount())
                    .currencyCode(savedReceivable.getCurrencyCode())
                    .validUntil(request.getDueDate() != null
                        ? request.getDueDate().atStartOfDay() : LocalDateTime.now().plusDays(30))
                    .singleUse(false)
                    .customerName(savedReceivable.vibanName())
                    .build());
            vibanNumber = createdViban.getViban();
            vibanId = createdViban.getId();
            log.info("Generated real VIBAN {} for invoice {}", vibanNumber, invoiceNumber);

            savedReceivable.setViban(vibanNumber);
            savedReceivable.setPrimaryVibanId(vibanId);
            savedReceivable = receivableRepository.save(savedReceivable);
        }

        if (!draft && Boolean.TRUE.equals(request.getSendInvoiceEmail())) {
            outbox.save(invoiceEmail(savedReceivable, paymentLink));
        }

        return InvoiceResponse.builder()
            .id(savedReceivable.getId())
            .invoiceNumber(savedReceivable.getReceivableNumber())
            .customerName(savedReceivable.getCustomerName())
            .customerId(savedReceivable.getCustomerPartyId())
            .customerVaNumber(request.getCustomerVaNumber())
            .invoiceDate(savedReceivable.getIssueDate())
            .dueDate(savedReceivable.getDueDate())
            .amount(savedReceivable.getGrossAmount())
            .paidAmount(savedReceivable.getPaidAmount())
            .outstandingAmount(savedReceivable.getOutstandingAmount())
            .currencyCode(savedReceivable.getCurrencyCode())
            .status(savedReceivable.getStatus().name())
            .description(savedReceivable.getDescription())
            .vibanId(vibanId)
            .vibanNumber(vibanNumber)
            .viban(vibanNumber)
            .paymentLink(paymentLink)
            .autoReconcile(request.getAutoReconcile())
            .allowPartialPayment(savedReceivable.getAllowPartialPayment())
            .matchedPayments(Collections.emptyList())
            .createdAt(savedReceivable.getCreatedAt())
            .build();
    }

    /** A draft becomes a real invoice: OPEN, owed, and collectable. */
    @Transactional
    public InvoiceResponse issueDraft(UUID invoiceId) {
        Receivable r = receivableRepository.findById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + invoiceId));
        if (r.getStatus() != Receivable.ReceivableStatus.DRAFT) {
            throw new BusinessException(r.getReceivableNumber() + " is not a draft (" + r.getStatus() + ")");
        }
        if (r.getNetAmount() == null || r.getNetAmount().signum() <= 0) {
            throw new BusinessException(r.getReceivableNumber() + " has no amount; a draft needs one to be issued");
        }
        r.setStatus(Receivable.ReceivableStatus.OPEN);
        return mapReceivableToInvoiceResponse(receivableRepository.save(r));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * The invoice as the email the customer would get. There is no mail server: it is kept in the outbox,
     * or marked NO_ADDRESS when the invoice has no email address.
     */
    private com.bank.vam.outbox.OutboxEmail invoiceEmail(Receivable r, String payLink) {
        java.text.DecimalFormat money = new java.text.DecimalFormat("#,##0.00");
        String creditor = corporateRepository.findById(r.getCorporateId()).map(Corporate::getLegalName).orElse("Your supplier");
        String amount = r.getCurrencyCode() + " " + money.format(r.getNetAmount());
        StringBuilder body = new StringBuilder()
                .append("Dear ").append(r.getCustomerName() != null ? r.getCustomerName() : "customer").append(",\n\n")
                .append(creditor).append(" has issued invoice ").append(r.getReceivableNumber()).append(" for ").append(amount)
                .append(r.getTaxAmount() != null && r.getTaxAmount().signum() > 0
                        ? " (including VAT " + r.getCurrencyCode() + " " + money.format(r.getTaxAmount()) + ")" : "")
                .append(r.getDueDate() != null ? ", due on " + r.getDueDate() : "").append(".\n");
        if (r.getDescription() != null) body.append(r.getDescription()).append("\n");
        if (r.getExternalReference() != null) body.append("Your reference: ").append(r.getExternalReference()).append("\n");
        if (r.getNotes() != null) body.append("\n").append(r.getNotes()).append("\n");
        body.append("\nPay online: ").append(payLink).append("\n");
        if (r.getViban() != null) {
            body.append("Or transfer ").append(amount).append(" to IBAN ").append(r.getViban())
                    .append(r.coboAccountName() != null ? " (" + r.coboAccountName() + ")" : "")
                    .append(", quoting ").append(r.getReceivableNumber()).append(".\n");
        }
        com.bank.vam.outbox.OutboxEmail e = new com.bank.vam.outbox.OutboxEmail();
        e.setCorporateId(r.getCorporateId());
        e.setToAddress(r.getCustomerEmail());
        e.setToName(r.getCustomerName());
        e.setSubject("Invoice " + r.getReceivableNumber() + " \u00b7 " + amount + " \u00b7 " + creditor);
        e.setBody(body.toString());
        e.setRelatedType("INVOICE");
        e.setRelatedId(r.getId());
        e.setRelatedRef(r.getReceivableNumber());
        e.setLink(payLink);
        e.setStatus(r.getCustomerEmail() != null ? "SENT" : "NO_ADDRESS");
        return e;
    }

    /** For the public, unauthenticated "pay this invoice" page (PublicReceivablesController) --
     * deliberately returns only what a payer needs to see, never customer/internal-ID fields. */
    public Optional<PublicInvoiceResponse> getPublicInvoiceByToken(String token) {
        return receivableRepository.findByPaymentLinkToken(token).map(r -> {
            String corporateName = corporateRepository.findById(r.getCorporateId())
                    .map(Corporate::getLegalName).orElse(null);
            return PublicInvoiceResponse.builder()
                    .receivableNumber(r.getReceivableNumber())
                    .corporateName(corporateName)
                    .amount(r.getGrossAmount())
                    .outstandingAmount(r.getOutstandingAmount())
                    .currencyCode(r.getCurrencyCode())
                    .dueDate(r.getDueDate())
                    .description(r.getDescription())
                    .status(r.getStatus().name())
                    .viban(r.getViban())
                    .accountName(r.coboAccountName())
                    .build();
        });
    }

    @Transactional
    public InvoiceResponse recordPayment(UUID invoiceId, RecordPaymentRequest request) {
        Receivable receivable = receivableRepository.findById(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + invoiceId));

        // Through the one application point: a COBO invoice is also credited in-house to its subsidiary
        reconciliationService.applyPayment(receivable, request.getAmount(), request.getAmount(),
            request.getTransactionId(), request.getMatchType() != null ? request.getMatchType() : "RECORDED");
        log.info("Recorded payment {} of {} against invoice {}, new status {}",
            request.getPaymentReference(), request.getAmount(), receivable.getReceivableNumber(), receivable.getStatus());

        InvoiceResponse response = mapReceivableToInvoiceResponse(receivable);
        response.setMatchedPayments(List.of(MatchedPaymentResponse.builder()
            .paymentRef(request.getPaymentReference())
            .amount(request.getAmount())
            .matchDate(LocalDateTime.now())
            .matchType(request.getMatchType())
            .confidence(request.getMatchType().equals("AUTO") ? 95 : null)
            .vibanId(request.getVibanId())
            .autoMatched("AUTO".equals(request.getMatchType()))
            .build()));
        return response;
    }

    // ========================================================================
    // COLLECTION PROCESSING - Delegated to TransactionService for 4-leg accounting
    // ========================================================================

    /**
     * Process incoming collection with proper 4-leg accounting via TransactionService.
     *
     * NEW FLOW (v5.3 - 4-leg accounting via TransactionService):
     * CBS → Shadow VA → Settlement VA → Target VA
     *
     * This method now delegates to TransactionService.processCollection() which provides:
     * 1. Proper 4-leg accounting (symmetric opposite of makePayment)
     * 2. Shadow VA receives CBS funds first (mirrors physical account)
     * 3. Settlement VA clears the transaction (nets to zero)
     * 4. Target VA receives net amount after fee
     * 5. Fee flows to Settlement VA using standard fee posting
     *
     * VIBAN routing still provides:
     * - Auto-matching to receivables
     * - Lower collection fee
     *
     * @param request Collection details
     * @return Collection response with fee breakdown
     */
    @Transactional
    public CollectionResponse processCollection(ProcessCollectionRequest request) {
        log.info("Processing collection via TransactionService: {} from {}",
            request.getAmount(), request.getSenderName());

        // Pre-process: Determine auto-match info from VIBAN
        UUID matchedReceivableId = null;
        String matchedInvoiceNumber = null;
        UUID resolvedTargetVaId = request.getTargetVaId();
        boolean vibanNotFound = false;
        boolean vibanNotAssigned = false;

        // Try VIBAN routing first for auto-matching
        if (request.getViban() != null && vibanRepository != null) {
            Optional<Viban> vibanOpt = vibanRepository.findByViban(request.getViban());
            if (vibanOpt.isPresent()) {
                Viban viban = vibanOpt.get();

                // Check if VIBAN is assigned to a VA
                if (viban.getVirtualAccountId() != null) {
                    resolvedTargetVaId = viban.getVirtualAccountId();
                } else {
                    // VIBAN exists but not assigned to any VA
                    vibanNotAssigned = true;
                    log.warn("VIBAN {} found but not assigned to any VA", request.getViban());
                }

                // Check for linked receivable via referenceType/referenceId (auto-match)
                if (viban.getReferenceType() != null && viban.getReferenceId() != null) {
                    if ("INVOICE".equals(viban.getReferenceType()) || "ORDER".equals(viban.getReferenceType())) {
                        try {
                            matchedReceivableId = UUID.fromString(viban.getReferenceId());
                        } catch (IllegalArgumentException e) {
                            matchedInvoiceNumber = viban.getReferenceId();
                        }
                        if (matchedInvoiceNumber == null) {
                            matchedInvoiceNumber = viban.getReferenceDescription() != null ?
                                viban.getReferenceDescription() : viban.getReferenceId();
                        }
                        log.info("Auto-matched via VIBAN {} to {} {}",
                            request.getViban(), viban.getReferenceType(), viban.getReferenceId());
                    }
                }

                // Update VIBAN usage statistics (even if not assigned, track the attempt)
                updateVibanUsage(viban, request.getAmount());
            } else {
                // VIBAN provided but not found in system
                vibanNotFound = true;
                log.warn("VIBAN {} not found in system - will route to Exception VA", request.getViban());
            }
        }

        // =====================================================================
        // EXCEPTION ROUTING: Handle invalid VIBAN or missing target VA
        // =====================================================================

        // Case 1: VIBAN provided but not found - route to Exception VA
        if (vibanNotFound) {
            return routeToExceptionVa(request, ExceptionType.INVALID_VIBAN,
                "VIBAN not found: " + request.getViban());
        }

        // Case 2: VIBAN found but not assigned to any VA - route to Exception VA
        if (vibanNotAssigned && resolvedTargetVaId == null) {
            return routeToExceptionVa(request, ExceptionType.INVALID_VIBAN,
                "VIBAN exists but not assigned: " + request.getViban());
        }

        // Case 3: No VIBAN and no target VA provided - route to Exception VA
        if (resolvedTargetVaId == null) {
            log.warn("No target VA resolved for collection - routing to Exception VA");
            return routeToExceptionVa(request, ExceptionType.UNMATCHED_PAYMENT,
                "No target VA specified and no VIBAN routing available");
        }

        // Case 4: Target VA provided but not found or inactive
        Optional<VirtualAccount> targetVaOpt = virtualAccountRepository.findById(resolvedTargetVaId);
        if (targetVaOpt.isEmpty()) {
            log.warn("Target VA {} not found - routing to Exception VA", resolvedTargetVaId);
            return routeToExceptionVa(request, ExceptionType.UNMATCHED_PAYMENT,
                "Target VA not found: " + resolvedTargetVaId);
        }

        VirtualAccount targetVa = targetVaOpt.get();
        if (targetVa.getStatus() != VaStatus.ACTIVE) {
            log.warn("Target VA {} is not active (status={}) - routing to Exception VA",
                targetVa.getVaNumber(), targetVa.getStatus());
            return routeToExceptionVa(request, ExceptionType.UNMATCHED_PAYMENT,
                "Target VA inactive: " + targetVa.getVaNumber() + " (status=" + targetVa.getStatus() + ")");
        }

        // =====================================================================
        // NORMAL PROCESSING: Valid target VA found
        // =====================================================================

        // Build request for TransactionService
        TransactionDto.CollectionRequest txnRequest = TransactionDto.CollectionRequest.builder()
            .targetVaId(resolvedTargetVaId)
            .amount(request.getAmount())
            .currencyCode(request.getCurrencyCode()) // none given: the account's currency (was "AED" for any account)
            .remitterName(request.getSenderName())
            .remitterAccount(request.getSenderAccount())
            .viban(request.getViban())
            .bankReference(request.getBankReference())
            .remittanceInfo(request.getRemittanceInfo())
            .matchedReceivableId(matchedReceivableId)
            .invoiceReference(matchedInvoiceNumber)
            .channel("INCOMING")
            .build();

        // Delegate to TransactionService for proper 4-leg accounting
        TransactionDto.CollectionResponse txnResult = transactionService.processCollection(txnRequest);

        log.info("Collection processed via TransactionService: ref={}, gross={}, fee={}, net={}, correlation={}",
            txnResult.getReferenceNumber(), txnResult.getGrossAmount(),
            txnResult.getFeeAmount(), txnResult.getNetAmount(), txnResult.getCorrelationId());

        // Map TransactionService response to ReceivablesService response
        return CollectionResponse.builder()
            .transactionReference(txnResult.getReferenceNumber())
            .correlationId(txnResult.getCorrelationId())
            .grossAmount(txnResult.getGrossAmount())
            .fee(txnResult.getFeeAmount())
            .netAmount(txnResult.getNetAmount())
            .targetVaId(txnResult.getTargetVaId())
            .targetVaNumber(txnResult.getTargetVaNumber())
            .targetVaBalance(txnResult.getTargetBalanceAfter())
            .matchStatus(txnResult.getMatchStatus())
            .matchedReceivableId(txnResult.getMatchedReceivableId())
            .matchedInvoiceNumber(txnResult.getMatchedInvoiceNumber())
            .settlementVaId(null)  // Settlement VA handling is now internal to TransactionService
            .settlementVaNumber(null)
            .shadowVaId(txnResult.getShadowVaId())
            .shadowVaNumber(txnResult.getShadowVaNumber())
            .processedAt(txnResult.getTransactionDate())
            .build();
    }

    /**
     * Route collection to Exception VA when target cannot be determined.
     *
     * This handles:
     * - Invalid/unknown VIBAN
     * - VIBAN not assigned to any VA
     * - Missing target VA
     * - Inactive target VA
     *
     * The amount is parked in Exception VA for manual resolution by Treasury team.
     */
    private CollectionResponse routeToExceptionVa(ProcessCollectionRequest request,
                                                   ExceptionType exceptionType,
                                                   String reason) {
        log.info("Routing collection to Exception VA: type={}, reason={}, amount={}, remitter={}",
            exceptionType, reason, request.getAmount(), request.getSenderName());

        String currency = request.getCurrencyCode() != null ? request.getCurrencyCode() : "AED";
        String correlationId = "EXC-" + System.currentTimeMillis();

        // Try to resolve Exception VA
        // We need a program context - try to get from request or use a default corporate exception VA
        VirtualAccount exceptionVa = null;

        // If we have a target VA ID (even if invalid), try to find exception VA for that program
        if (request.getTargetVaId() != null) {
            Optional<VirtualAccount> targetVaOpt = virtualAccountRepository.findById(request.getTargetVaId());
            if (targetVaOpt.isPresent()) {
                try {
                    exceptionVa = settlementVaResolver.resolveOrCreateExceptionVa(targetVaOpt.get());
                } catch (Exception e) {
                    log.warn("Failed to resolve Exception VA from target: {}", e.getMessage());
                }
            }
        }

        // Try to find a corporate-level exception VA if we still don't have one
        if (exceptionVa == null) {
            // Find any active Exception VA for this currency
            List<VirtualAccount> exceptionVas = virtualAccountRepository.findByCurrencyCodeAndAccountCategory(
                currency, VirtualAccount.AccountCategory.EXCEPTION);
            if (!exceptionVas.isEmpty()) {
                exceptionVa = exceptionVas.stream()
                    .filter(va -> va.getStatus() == VaStatus.ACTIVE)
                    .findFirst()
                    .orElse(exceptionVas.get(0));
            }
        }

        // If still no Exception VA found, this is a critical error
        if (exceptionVa == null) {
            log.error("CRITICAL: No Exception VA available for currency {}. Collection cannot be processed.", currency);
            throw new IllegalStateException("No Exception VA configured for currency: " + currency +
                ". Please configure an Exception VA before processing collections.");
        }

        log.info("Resolved Exception VA: {} for parking unmatched collection", exceptionVa.getVaNumber());

        // Credit the Exception VA
        BigDecimal balanceBefore = exceptionVa.getCurrentBalance();
        exceptionVa.credit(request.getAmount());
        virtualAccountRepository.save(exceptionVa);

        // Create transaction for Exception VA credit
        Transaction exceptionTxn = Transaction.builder()
            .referenceNumber(Transaction.generateReference(MovementType.EXCEPTION_CREDIT))
            .movementType(MovementType.EXCEPTION_CREDIT)
            .corporateId(exceptionVa.getCorporateId())
            .vaId(exceptionVa.getId())
            .physicalAccountId(exceptionVa.getPhysicalAccountId())
            .programId(exceptionVa.getProgramId())
            .amount(request.getAmount())
            .currencyCode(currency)
            .balanceBefore(balanceBefore)
            .balanceAfter(exceptionVa.getCurrentBalance())
            .correlationId(correlationId)
            .viban(request.getViban())
            .routedViaViban(request.getViban() != null)
            .remitterName(request.getSenderName())
            .remitterAccount(request.getSenderAccount())
            .description("Exception: " + reason + " - from " + request.getSenderName())
            .externalReference(request.getBankReference())
            .isRobo(true)
            .status(TransactionStatus.COMPLETED)
            .transactionDate(LocalDateTime.now())
            .valueDate(LocalDate.now())
            .channel("EXCEPTION")
            .build();

        exceptionTxn = transactionRepository.save(exceptionTxn);

        // Create Exception Transaction record for treasury team resolution
        ExceptionTransaction exception = ExceptionTransaction.builder()
            .exceptionNumber(generateExceptionNumber())
            .exceptionType(exceptionType)
            .programId(exceptionVa.getProgramId())
            .exceptionVaId(exceptionVa.getId())
            .originalTransactionId(exceptionTxn.getId())
            .amount(request.getAmount())
            .currencyCode(currency)
            .valueDate(LocalDate.now())
            .bankReference(request.getBankReference())
            .remitterName(request.getSenderName())
            .remitterAccount(request.getSenderAccount())
            .remitterReference(request.getViban())
            .remitterInfo(request.getSenderName() + " - " + (request.getRemittanceInfo() != null ? request.getRemittanceInfo() : ""))
            .description(reason)
            .notes("VIBAN: " + request.getViban() + ", Remittance: " + request.getRemittanceInfo())
            .status(ExceptionStatus.OPEN)
            .correlationId(correlationId)
            .build();

        exception = exceptionTransactionRepository.save(exception);

        log.info("Created exception record {} for {} {} - parked in Exception VA {}",
            exception.getExceptionNumber(), request.getAmount(), currency, exceptionVa.getVaNumber());

        // Return response indicating exception routing
        return CollectionResponse.builder()
            .transactionReference(exceptionTxn.getReferenceNumber())
            .correlationId(correlationId)
            .grossAmount(request.getAmount())
            .fee(BigDecimal.ZERO)  // No fee for exception routing
            .netAmount(request.getAmount())
            .targetVaId(exceptionVa.getId())
            .targetVaNumber(exceptionVa.getVaNumber())
            .targetVaBalance(exceptionVa.getCurrentBalance())
            .matchStatus("EXCEPTION_" + exceptionType.name())
            .settlementVaId(null)
            .settlementVaNumber(null)
            .shadowVaId(null)
            .shadowVaNumber(null)
            .processedAt(LocalDateTime.now())
            .build();
    }

    /**
     * Generate unique exception number.
     */
    private String generateExceptionNumber() {
        return "EXC-" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd").format(LocalDate.now())
            + "-" + String.format("%06d", (int) (Math.random() * 1000000));
    }

    /**
     * Update VIBAN usage statistics after collection.
     */
    private void updateVibanUsage(Viban viban, BigDecimal amount) {
        try {
            viban.setTimesUsed((viban.getTimesUsed() != null ? viban.getTimesUsed() : 0) + 1);
            viban.setTotalAmountReceived(
                (viban.getTotalAmountReceived() != null ? viban.getTotalAmountReceived() : BigDecimal.ZERO)
                    .add(amount)
            );
            viban.setLastUsedAt(LocalDateTime.now());
            vibanRepository.save(viban);
            log.debug("Updated VIBAN {} usage: timesUsed={}, totalAmount={}",
                viban.getViban(), viban.getTimesUsed(), viban.getTotalAmountReceived());
        } catch (Exception e) {
            log.warn("Failed to update VIBAN usage statistics: {}", e.getMessage());
        }
    }

    // ========================================================================
    // PHASE 1: CAMT.054 PROCESSING (Bank Credit Notification)
    // ========================================================================

    /**
     * Process incoming camt.054 notification (Bank to Customer Debit/Credit Notification).
     * This simulates receiving external payment notifications for published VAs with VIBANs.
     *
     * Flow:
     * 1. Parse camt.054 notification (VIBAN routing)
     * 2. Lookup published VA by VIBAN
     * 3. Process collection (credit VA, deduct fees)
     * 4. Auto-match to receivable if linked
     * 5. Return processing result
     *
     * @param notification Camt054 notification data
     * @return Processing result
     */
    @Transactional
    public Camt054ProcessingResponse processCamt054(Camt054NotificationRequest notification) {
        log.info("Processing camt.054 notification: {} entries", notification.getEntries().size());

        List<Camt054EntryResult> results = new ArrayList<>();
        int successCount = 0;
        int failedCount = 0;
        BigDecimal totalCredited = BigDecimal.ZERO;
        BigDecimal totalFees = BigDecimal.ZERO;

        for (Camt054Entry entry : notification.getEntries()) {
            try {
                // Find target VA by VIBAN
                VirtualAccount targetVa = null;
                if (entry.getViban() != null && vibanRepository != null) {
                    Optional<Viban> vibanOpt = vibanRepository.findByViban(entry.getViban());
                    if (vibanOpt.isPresent()) {
                        targetVa = virtualAccountRepository.findById(vibanOpt.get().getVirtualAccountId()).orElse(null);
                    }
                }

                // Fallback: try direct VIBAN lookup on VA
                if (targetVa == null && entry.getViban() != null) {
                    targetVa = virtualAccountRepository.findByViban(entry.getViban()).orElse(null);
                }

                if (targetVa == null) {
                    results.add(Camt054EntryResult.builder()
                        .entryReference(entry.getEntryReference())
                        .status("FAILED")
                        .errorMessage("VIBAN not found: " + entry.getViban())
                        .build());
                    failedCount++;
                    continue;
                }

                // Validate VA can receive external payments
                if (!targetVa.canReceiveExternalPayments()) {
                    results.add(Camt054EntryResult.builder()
                        .entryReference(entry.getEntryReference())
                        .viban(entry.getViban())
                        .status("FAILED")
                        .errorMessage("VA not published or inactive: " + targetVa.getVaNumber())
                        .build());
                    failedCount++;
                    continue;
                }

                // Process as collection
                ProcessCollectionRequest collectionRequest = ProcessCollectionRequest.builder()
                    .targetVaId(targetVa.getId())
                    .amount(entry.getAmount())
                    .senderName(entry.getDebtor())
                    .senderAccount(entry.getDebtorAccount())
                    .bankReference(entry.getEntryReference())
                    .viban(entry.getViban())
                    .remittanceInfo(entry.getRemittanceInfo())
                    .build();

                CollectionResponse collectionResult = processCollection(collectionRequest);

                results.add(Camt054EntryResult.builder()
                    .entryReference(entry.getEntryReference())
                    .viban(entry.getViban())
                    .transactionReference(collectionResult.getTransactionReference())
                    .grossAmount(entry.getAmount())
                    .fee(collectionResult.getFee())
                    .netAmount(collectionResult.getNetAmount())
                    .targetVaId(targetVa.getId())
                    .targetVaNumber(targetVa.getVaNumber())
                    .matchStatus(collectionResult.getMatchStatus())
                    .matchedReceivableId(collectionResult.getMatchedReceivableId())
                    .status("PROCESSED")
                    .processedAt(LocalDateTime.now())
                    .build());

                successCount++;
                totalCredited = totalCredited.add(collectionResult.getNetAmount());
                totalFees = totalFees.add(collectionResult.getFee());

            } catch (Exception e) {
                log.error("Failed to process camt.054 entry: {}", entry.getEntryReference(), e);
                results.add(Camt054EntryResult.builder()
                    .entryReference(entry.getEntryReference())
                    .viban(entry.getViban())
                    .status("FAILED")
                    .errorMessage(e.getMessage())
                    .build());
                failedCount++;
            }
        }

        log.info("Camt.054 processing completed: {} success, {} failed", successCount, failedCount);

        return Camt054ProcessingResponse.builder()
            .notificationId(notification.getNotificationId())
            .results(results)
            .successCount(successCount)
            .failedCount(failedCount)
            .totalCreditedAmount(totalCredited)
            .totalFees(totalFees)
            .currencyCode(notification.getCurrencyCode())
            .processedAt(LocalDateTime.now())
            .build();
    }

    // ========================================================================
    // E-COMMERCE ORDERS (Existing - Preserved for backward compatibility)
    // ========================================================================

    // ponytail: nothing records marketplace orders (platform, buyer, escrow state) -- the real
    // collection-account credits live in EcommerceService -- so there are none to list.
    public List<EcommerceOrderResponse> getAllEcommerceOrders(UUID corporateId, String platform, String escrowStatus) {
        return List.of();
    }

    public List<EcommercePlatformStats> getPlatformStats(UUID corporateId) {
        return List.of();
    }

    public EcommerceOrderResponse releaseEscrow(UUID orderId, ReleaseEscrowRequest request) {
        throw new BusinessException("Not available: marketplace escrow release \u2014 no marketplace orders are recorded, "
            + "so there is no escrow held for order " + orderId);
    }

    // ========================================================================
    // PAYMENT VIBANS (Existing - Preserved for backward compatibility)
    // ========================================================================

    /**
     * A "Payment VIBAN" is just an invoice-linked {@link Viban} -- the same real VIBAN a
     * receivable gets at creation time via {@code createInvoice}'s generateViban flag. Listed
     * here by walking the corporate's receivables rather than querying vibanRepository directly,
     * since Viban itself carries no corporateId (only virtualAccountId) and Receivable already
     * has the corporate-scoped lookup this method needs.
     */
    public List<PaymentVibanResponse> getAllPaymentVibans(UUID corporateId, String status) {
        List<Receivable> receivables = corporateId != null
            ? receivableRepository.findByCorporateId(corporateId)
            : receivableRepository.findAll();

        List<PaymentVibanResponse> vibans = receivables.stream()
            .filter(r -> r.getPrimaryVibanId() != null)
            .map(r -> vibanRepository.findById(r.getPrimaryVibanId()).map(v -> toPaymentVibanResponse(v, r)))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .collect(Collectors.toList());

        if (status != null && !status.isEmpty() && !"all".equalsIgnoreCase(status)) {
            vibans = vibans.stream()
                .filter(v -> status.equalsIgnoreCase(v.getStatus()))
                .collect(Collectors.toList());
        }

        return vibans;
    }

    /**
     * Issues a real VIBAN via VibanService against either the explicitly given
     * virtualAccountId, or -- for the common case of "generate a VIBAN for this receivable" --
     * the receivable's own collection VA. Never fabricates one: with neither a usable VA nor a
     * receivable to resolve one from, this fails loudly instead of returning a string nobody
     * could ever pay into.
     */
    @Transactional
    public PaymentVibanResponse createPaymentViban(UUID corporateId, CreatePaymentVibanRequest request) {
        Receivable receivable = request.getReceivableId() != null
            ? receivableRepository.findById(request.getReceivableId())
                .orElseThrow(() -> new ResourceNotFoundException("Receivable not found: " + request.getReceivableId()))
            : null;

        UUID virtualAccountId = request.getVirtualAccountId() != null
            ? request.getVirtualAccountId()
            : (receivable != null ? receivable.getVirtualAccountId() : null);
        if (virtualAccountId == null) {
            throw new BusinessException(receivable != null
                ? "Receivable " + receivable.getReceivableNumber() + " has no collection Virtual Account assigned -- cannot generate a VIBAN against it."
                : "A virtualAccountId or a receivableId with a collection VA is required to generate a VIBAN.");
        }

        VirtualAccount va = virtualAccountRepository.findById(virtualAccountId)
            .orElseThrow(() -> new ResourceNotFoundException("Virtual account not found: " + virtualAccountId));
        UUID programId = request.getProgramId() != null ? request.getProgramId() : va.getProgramId();

        int expiresInHours = request.getExpiresInHours() != null ? request.getExpiresInHours() : 168;

        // Reuse the receivable's existing pay-invoice link if it already has one, rather than
        // fabricating a second, unrelated URL for the same invoice.
        String paymentLink = null;
        if (receivable != null) {
            if (receivable.getPaymentLinkToken() == null) {
                receivable.setPaymentLinkToken(UUID.randomUUID().toString());
                receivable = receivableRepository.save(receivable);
            }
            paymentLink = frontendBaseUrl + "/?page=pay-invoice&token=" + receivable.getPaymentLinkToken();
        }

        VibanResponse created = vibanService.createViban(programId, VibanCreateRequest.builder()
            .virtualAccountId(virtualAccountId)
            .vibanType(Viban.VibanType.INVOICE)
            .referenceType(receivable != null ? Viban.REF_TYPE_INVOICE : request.getReceivableType())
            .referenceId(receivable != null ? receivable.getId().toString() : null)
            .expectedAmount(request.getExpectedAmount())
            .amountTolerancePercent(request.getAmountTolerancePercent())
            .minAmount(request.getMinAmount())
            .maxAmount(request.getMaxAmount())
            .currencyCode(request.getCurrency())
            .validUntil(LocalDateTime.now().plusHours(expiresInHours))
            .singleUse(false)
            .customerName(request.getCustomerName())
            .purpose(request.getPurpose())
            .build());

        if (receivable != null) {
            receivable.setPrimaryVibanId(created.getId());
            receivable.setViban(created.getViban());
            receivableRepository.save(receivable);
        }

        return toPaymentVibanResponse(created, paymentLink, receivable);
    }

    public PaymentVibanResponse cancelPaymentViban(UUID vibanId) {
        VibanResponse cancelled = vibanService.updateStatus(vibanId, Viban.STATUS_CANCELLED);
        Receivable receivable = receivableRepository.findByPrimaryVibanId(vibanId).orElse(null);
        return toPaymentVibanResponse(cancelled, null, receivable);
    }

    private PaymentVibanResponse toPaymentVibanResponse(Viban v, Receivable r) {
        String paymentLink = r != null && r.getPaymentLinkToken() != null
            ? frontendBaseUrl + "/?page=pay-invoice&token=" + r.getPaymentLinkToken()
            : null;
        return PaymentVibanResponse.builder()
            .id(v.getId())
            .virtualIban(v.getViban())
            .reference(r != null ? r.getReceivableNumber() : v.getReferenceId())
            .customerName(v.getCustomerName())
            .expectedAmount(v.getExpectedAmount())
            .receivedAmount(v.getTotalAmountReceived() != null ? v.getTotalAmountReceived() : BigDecimal.ZERO)
            .currency(v.getCurrencyCode())
            .status(v.getStatus())
            .createdAt(v.getCreatedAt())
            .expiresAt(v.getValidUntil())
            .purpose(v.getPurpose())
            .paymentLink(paymentLink)
            .linkedReceivableId(r != null ? r.getId() : null)
            .linkedReceivableNumber(r != null ? r.getReceivableNumber() : null)
            .autoReconcile(r != null ? r.getAutoReconcile() : null)
            .build();
    }

    private PaymentVibanResponse toPaymentVibanResponse(VibanResponse v, String paymentLink, Receivable r) {
        return PaymentVibanResponse.builder()
            .id(v.getId())
            .virtualIban(v.getViban())
            .reference(r != null ? r.getReceivableNumber() : v.getReferenceId())
            .customerName(v.getCustomerName())
            .expectedAmount(v.getExpectedAmount())
            .receivedAmount(v.getTotalAmountReceived() != null ? v.getTotalAmountReceived() : BigDecimal.ZERO)
            .currency(v.getCurrencyCode())
            .status(v.getStatus())
            .createdAt(v.getCreatedAt())
            .expiresAt(v.getValidUntil())
            .purpose(v.getPurpose())
            .paymentLink(paymentLink)
            .linkedReceivableId(r != null ? r.getId() : null)
            .linkedReceivableNumber(r != null ? r.getReceivableNumber() : null)
            .autoReconcile(r != null ? r.getAutoReconcile() : null)
            .build();
    }

    // ========================================================================
    // UNMATCHED PAYMENTS (Existing - with backward compatible wrappers)
    // ========================================================================

    public List<UnmatchedPaymentResponse> getUnmatchedPayments(UUID corporateId) {
        // ponytail: an unmatched payment knows only its VA, so the corporate is read through it (one lookup per row)
        List<UnmatchedPaymentResponse> out = new ArrayList<>();
        for (UnmatchedPayment u : unmatchedPaymentRepository.findAll()) {
            VirtualAccount va = accountOf(u);
            if (corporateId == null || (va != null && corporateId.equals(va.getCorporateId()))) {
                out.add(toUnmatchedResponse(u, va));
            }
        }
        return out;
    }

    public UnmatchedPaymentResponse matchPayment(UUID paymentId, MatchPaymentRequest request) {
        UUID receivableId = request.getInvoiceId() != null ? request.getInvoiceId() : request.getReceivableId();
        if (receivableId == null) {
            throw new BusinessException("invoiceId is required to match a payment");
        }
        return resolved(paymentId, reconciliationService.manualMatch(paymentId, receivableId,
            request.getMatchedBy() != null ? request.getMatchedBy() : "portal"));
    }

    public UnmatchedPaymentResponse returnPayment(UUID paymentId, ReturnPaymentRequest request) {
        return resolved(paymentId, reconciliationService.returnPayment(paymentId, request.getReason(),
            request.getReturnedBy() != null ? request.getReturnedBy() : "portal"));
    }

    public UnmatchedPaymentResponse escalatePayment(UUID paymentId) {
        return resolved(paymentId, reconciliationService.escalatePayment(paymentId, null, null));
    }

    private UnmatchedPaymentResponse resolved(UUID paymentId, ReconciliationService.ReconciliationResult result) {
        if (!result.isSuccess()) {
            throw new BusinessException(result.getErrorMessage());
        }
        UnmatchedPayment u = unmatchedPaymentRepository.findById(paymentId)
            .orElseThrow(() -> new ResourceNotFoundException("Unmatched payment not found: " + paymentId));
        return toUnmatchedResponse(u, accountOf(u));
    }

    private VirtualAccount accountOf(UnmatchedPayment u) {
        return u.getVirtualAccountId() == null ? null
            : virtualAccountRepository.findById(u.getVirtualAccountId()).orElse(null);
    }

    private UnmatchedPaymentResponse toUnmatchedResponse(UnmatchedPayment u, VirtualAccount va) {
        return UnmatchedPaymentResponse.builder()
            .id(u.getId())
            .paymentReference(u.getPaymentReference())
            .senderName(u.getPayerName())
            .senderAccount(u.getPayerAccount())
            .amount(u.getAmount())
            .currencyCode(u.getCurrencyCode())
            .receivedDate(u.getPaymentDate())
            .vaNumber(va != null ? va.getVaNumber() : null)
            .vaName(va != null ? va.getVaName() : null)
            .remittanceInfo(u.getRemittanceInfo())
            .suggestedMatches(List.of())
            .status(u.getStatus() != null ? u.getStatus().name() : null)
            .vibanId(u.getVibanId())
            .targetVaId(u.getVirtualAccountId())
            .matchAttempts(u.getMatchAttempts())
            .lastMatchAttemptAt(u.getLastMatchAttemptAt())
            .escalated(u.getStatus() == UnmatchedPayment.UnmatchedStatus.ESCALATED)
            .escalatedTo(u.getEscalatedTo())
            .escalatedAt(u.getEscalatedAt())
            .escalationReason(u.getEscalationReason())
            .build();
    }

    // ========================================================================
    // POS COLLECTIONS (Existing - Preserved for backward compatibility)
    // ========================================================================

    // ponytail: no acquiring feed records POS/terminal transactions (va_movements' merchant columns
    // stay empty -- see EcommerceService), so there is nothing to list or total yet.
    public List<PosCollectionResponse> getAllPosCollections(UUID corporateId, String status, String merchantId) {
        return List.of();
    }

    public PosStatsResponse getPosStats(UUID corporateId) {
        return PosStatsResponse.builder()
            .totalCollections(BigDecimal.ZERO)
            .todayCollections(BigDecimal.ZERO)
            .pendingSettlement(BigDecimal.ZERO)
            .settledAmount(BigDecimal.ZERO)
            .transactionCount(0)
            .todayTransactions(0)
            .averageTicket(BigDecimal.ZERO)
            .build();
    }

    public List<MerchantSummary> getMerchantSummaries(UUID corporateId) {
        return List.of();
    }

}
