package com.bank.vam.service.receivables;

import com.bank.vam.dto.TransactionDto;
import com.bank.vam.dto.receivables.ReceivablesDto.*;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.Transaction.MovementType;
import com.bank.vam.entity.Transaction.TransactionStatus;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.VaStatus;
import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.entity.treasury.ExceptionTransaction;
import com.bank.vam.entity.treasury.ExceptionTransaction.ExceptionType;
import com.bank.vam.entity.treasury.ExceptionTransaction.ExceptionStatus;
import com.bank.vam.entity.viban.Viban;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.receivables.ReceivableRepository;
import com.bank.vam.repository.treasury.ExceptionTransactionRepository;
import com.bank.vam.repository.viban.VibanRepository;
import com.bank.vam.service.TransactionService;
import com.bank.vam.service.treasury.FeePostingService;
import com.bank.vam.service.treasury.SettlementVaResolverService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ReceivablesService - Enhanced with shared FeePostingService integration.
 * 
 * Phase 3 Enhancements:
 * - Refactored to use shared FeePostingService for standardized fee posting
 * - Collection processing with fee posting to Settlement VA
 * - COBO (Collect-On-Behalf-Of) with higher fee rates
 * - Payment matching with matching fee
 * - Return payment with return processing fee
 * - Escrow release with escrow service fee
 * 
 * Integration Pattern (per Integration Guide):
 * 1. Calculate fee (percentage or flat rate)
 * 2. Execute main operation (credit target VA)
 * 3. Post fee to Settlement VA using FeePostingService
 * 
 * Backward Compatible:
 * - All existing demo data methods preserved
 * - New methods added for real entity integration
 * - Graceful fallback when repositories not available
 * 
 * MVC Pattern:
 * - Entity: Receivable, ReceivablePayment, UnmatchedPayment, Viban, Transaction
 * - Repository: ReceivableRepository, VibanRepository, TransactionRepository
 * - Service: ReceivablesService (this class), FeePostingService (shared)
 * - Controller: ReceivablesController
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

    // Shared fee posting service (standardized across all domain services)
    private final FeePostingService feePostingService;

    // Transaction service for proper 4-leg accounting
    private final TransactionService transactionService;

    // Exception handling - for routing unmatched/invalid collections
    private final SettlementVaResolverService settlementVaResolver;
    private final ExceptionTransactionRepository exceptionTransactionRepository;

    // ========================================================================
    // CONFIGURATION - Fee Rates
    // ========================================================================
    
    private static final BigDecimal COLLECTION_FEE_PERCENT = new BigDecimal("0.001");      // 0.1%
    private static final BigDecimal COLLECTION_FEE_NON_VIBAN = new BigDecimal("0.0015");   // 0.15% (non-VIBAN)
    private static final BigDecimal COBO_FEE_PERCENT = new BigDecimal("0.0015");           // 0.15%
    private static final BigDecimal ESCROW_FEE_PERCENT = new BigDecimal("0.005");          // 0.5%
    private static final BigDecimal MATCHING_FEE_FLAT = new BigDecimal("2.00");            // AED 2 per match
    private static final BigDecimal RETURN_FEE_FLAT = new BigDecimal("10.00");             // AED 10 per return

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

        // If no receivables found in DB, fall back to demo data for development
        if (invoices.isEmpty()) {
            log.info("No receivables in database, returning demo invoices");
            invoices = buildDemoInvoices();
            if (status != null && !status.isEmpty()) {
                invoices = invoices.stream()
                    .filter(i -> status.equalsIgnoreCase(i.getStatus()))
                    .collect(Collectors.toList());
            }
        }

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
            .build();
    }

    /**
     * Backward compatible method - calls new method with null legalEntityId.
     */
    public List<InvoiceResponse> getAllInvoices(UUID corporateId, String status) {
        return getAllInvoices(corporateId, null, status);
    }

    public InvoiceResponse getInvoice(UUID invoiceId) {
        return buildDemoInvoices().stream()
            .filter(i -> i.getId().toString().contains("1"))
            .findFirst()
            .orElse(buildDemoInvoices().get(0));
    }

    @Transactional
    public InvoiceResponse createInvoice(UUID corporateIdFromHeader, CreateInvoiceRequest request) {
        // Determine corporateId: prefer header, fallback to request body, then default
        UUID effectiveCorporateId = corporateIdFromHeader;
        if (effectiveCorporateId == null && request.getCorporateId() != null) {
            effectiveCorporateId = request.getCorporateId();
            log.info("Using corporateId from request body: {}", effectiveCorporateId);
        }
        if (effectiveCorporateId == null) {
            effectiveCorporateId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000"); // Demo corporate
            log.warn("No corporateId provided in header or body, using demo corporate: {}", effectiveCorporateId);
        } else {
            log.info("Creating invoice with corporateId: {}", effectiveCorporateId);
        }

        // Determine owningEntityId from request body
        UUID owningEntityId = request.getOwningEntityId();
        String owningEntityCode = request.getOwningEntityCode();

        if (owningEntityId != null) {
            log.info("Using owningEntityId from request body: {}, code: {}", owningEntityId, owningEntityCode);
        } else {
            log.warn("No owningEntityId provided in request body - receivable will have null owningEntityId");
        }

        String invoiceNumber = "INV-" + LocalDate.now().getYear() + "-" + String.format("%04d", System.currentTimeMillis() % 10000);

        // Generate VIBAN if requested
        String vibanNumber = null;
        UUID vibanId = null;
        String paymentLink = null;

        if (Boolean.TRUE.equals(request.getCreateViban()) || Boolean.TRUE.equals(request.getGenerateViban())) {
            vibanNumber = "AE150410CORP" + String.format("%011d", System.currentTimeMillis() % 100000000000L);
            vibanId = UUID.randomUUID();
            paymentLink = "https://pay.bank.com/v/" + vibanNumber.substring(vibanNumber.length() - 8);
            log.info("Generated VIBAN {} for invoice {}", vibanNumber, invoiceNumber);
        }

        // Create and persist the Receivable entity
        Receivable receivable = Receivable.builder()
            .receivableNumber(invoiceNumber)
            .corporateId(effectiveCorporateId)
            .owningEntityId(owningEntityId)
            .owningEntityCode(owningEntityCode)
            .customerPartyId(request.getCustomerId())
            .customerName(request.getCustomerName())
            .virtualAccountId(request.getTargetVaId())
            .viban(vibanNumber)
            .primaryVibanId(vibanId)
            .issueDate(LocalDate.now())
            .dueDate(request.getDueDate())
            .grossAmount(request.getAmount())
            .netAmount(request.getAmount())
            .outstandingAmount(request.getAmount())
            .paidAmount(BigDecimal.ZERO)
            .currencyCode(request.getCurrencyCode() != null ? request.getCurrencyCode() : "AED")
            .description(request.getDescription())
            .status(Receivable.ReceivableStatus.OPEN)
            .collectionRoute(Receivable.CollectionRoute.DIRECT)
            .allowPartialPayment(Boolean.TRUE.equals(request.getAllowPartialPayment()))
            .allowOverpayment(Boolean.TRUE.equals(request.getAllowOverpayment()))
            .hierarchyNodeId(request.getHierarchyNodeId())
            .createdBy("system")
            .build();

        Receivable savedReceivable = receivableRepository.save(receivable);
        log.info("Created receivable {} with ID {}", invoiceNumber, savedReceivable.getId());

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

    public InvoiceResponse recordPayment(UUID invoiceId, RecordPaymentRequest request) {
        InvoiceResponse invoice = getInvoice(invoiceId);
        
        BigDecimal newPaidAmount = invoice.getPaidAmount().add(request.getAmount());
        String newStatus = newPaidAmount.compareTo(invoice.getAmount()) >= 0 ? "PAID" : "PARTIAL";
        
        List<MatchedPaymentResponse> payments = new ArrayList<>(invoice.getMatchedPayments());
        payments.add(MatchedPaymentResponse.builder()
            .paymentRef(request.getPaymentReference())
            .amount(request.getAmount())
            .matchDate(LocalDateTime.now())
            .matchType(request.getMatchType())
            .confidence(request.getMatchType().equals("AUTO") ? 95 : null)
            .vibanId(request.getVibanId())
            .autoMatched("AUTO".equals(request.getMatchType()))
            .build());
        
        return InvoiceResponse.builder()
            .id(invoice.getId())
            .invoiceNumber(invoice.getInvoiceNumber())
            .customerName(invoice.getCustomerName())
            .customerId(invoice.getCustomerId())
            .customerVaNumber(invoice.getCustomerVaNumber())
            .invoiceDate(invoice.getInvoiceDate())
            .dueDate(invoice.getDueDate())
            .amount(invoice.getAmount())
            .paidAmount(newPaidAmount)
            .outstandingAmount(invoice.getAmount().subtract(newPaidAmount))
            .currencyCode(invoice.getCurrencyCode())
            .status(newStatus)
            .matchedPayments(payments)
            .build();
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
            .currencyCode(request.getCurrencyCode() != null ? request.getCurrencyCode() : "AED")
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

    /**
     * Build demo response when no real VA is available.
     */
    private CollectionResponse buildDemoCollectionResponse(ProcessCollectionRequest request) {
        boolean viaViban = request.getViban() != null;
        BigDecimal fee = calculateCollectionFee(request.getAmount(), viaViban);
        BigDecimal netAmount = request.getAmount().subtract(fee);
        String correlationId = "COL-DEMO-" + System.currentTimeMillis();

        log.info("Processing collection in demo mode: {} (net: {}, fee: {})",
            request.getAmount(), netAmount, fee);

        return CollectionResponse.builder()
            .transactionReference("TRF-" + correlationId)
            .correlationId(correlationId)
            .grossAmount(request.getAmount())
            .fee(fee)
            .netAmount(netAmount)
            .targetVaId(request.getTargetVaId())
            .targetVaNumber("VA-DEMO-001")
            .targetVaBalance(netAmount)
            .matchStatus(viaViban ? "VIBAN_ROUTED" : "UNMATCHED")
            .processedAt(LocalDateTime.now())
            .build();
    }

    /**
     * Process COBO (Collect-On-Behalf-Of) with higher service fee.
     * Uses shared FeePostingService for standardized fee posting.
     */
    @Transactional
    public CoboCollectionResponse processCoboCollection(UUID corporateId, CoboCollectionRequest request) {
        log.info("Processing COBO collection on behalf of: {}", request.getOnBehalfOfEntity());
        
        // 1. Calculate COBO fee (higher than regular collection)
        BigDecimal fee = calculateCoboFee(request.getAmount());
        BigDecimal netAmount = request.getAmount().subtract(fee);
        
        String correlationId = "COBO-" + System.currentTimeMillis();
        String transactionRef = "TRF-" + correlationId;
        UUID settlementVaId = null;
        String settlementVaNumber = null;
        BigDecimal targetVaBalance = netAmount;
        boolean feePosted = false;
        
        // Process with real entities if available
        VirtualAccount targetVa = null;
        if (request.getTargetVaId() != null && virtualAccountRepository != null) {
            targetVa = virtualAccountRepository.findById(request.getTargetVaId()).orElse(null);
        }
        
        if (targetVa != null && targetVa.getStatus() == VaStatus.ACTIVE) {
            BigDecimal balanceBefore = targetVa.getCurrentBalance();
            
            // 2. Credit target VA with net amount
            targetVa.credit(netAmount);
            virtualAccountRepository.save(targetVa);
            targetVaBalance = targetVa.getCurrentBalance();
            
            // 3. Record COBO transaction
            Transaction coboTxn = Transaction.builder()
                .referenceNumber(Transaction.generateReference(MovementType.ROBO_CREDIT))
                .movementType(MovementType.ROBO_CREDIT)
                .corporateId(targetVa.getCorporateId())
                .vaId(targetVa.getId())
                .physicalAccountId(targetVa.getPhysicalAccountId())
                .programId(targetVa.getProgramId())
                .amount(netAmount)
                .currencyCode(request.getCurrencyCode() != null ? request.getCurrencyCode() : "AED")
                .balanceBefore(balanceBefore)
                .balanceAfter(targetVa.getCurrentBalance())
                .correlationId(correlationId)
                .feeAmount(fee)
                .remitterName(request.getPayerName())
                .remitterAccount(request.getPayerAccount())
                .description("COBO: " + request.getDescription())
                .externalReference(request.getExternalReference())
                .isRobo(true)
                .behalfOfEntity(request.getOnBehalfOfEntity())
                .behalfOfVaId(request.getOnBehalfOfVaId())
                .status(TransactionStatus.COMPLETED)
                .transactionDate(LocalDateTime.now())
                .valueDate(LocalDate.now())
                .channel("COBO")
                .build();
            
            coboTxn = transactionRepository.save(coboTxn);
            transactionRef = coboTxn.getReferenceNumber();
            
            // 4. Post COBO fee to Settlement VA using SHARED FeePostingService
            if (fee.compareTo(BigDecimal.ZERO) > 0 && feePostingService != null) {
                try {
                    FeePostingService.FeePostingResult feeResult = feePostingService.postFee(
                        targetVa.getId(),
                        fee,
                        "COBO_SERVICE_FEE",
                        coboTxn.getId(),
                        "COBO service fee - on behalf of " + request.getOnBehalfOfEntity()
                    );
                    
                    if ("POSTED".equals(feeResult.getStatus())) {
                        settlementVaId = feeResult.getSettlementVaId();
                        settlementVaNumber = feeResult.getSettlementVaNumber();
                        feePosted = true;
                        
                        if (feeResult.isExceptionFallback()) {
                            log.warn("COBO fee {} posted to Exception VA (no Settlement VA found)", fee);
                        } else {
                            log.info("COBO fee {} posted to Settlement VA {}", fee, settlementVaNumber);
                        }
                    }
                } catch (Exception e) {
                    log.error("Failed to post COBO fee: {}", e.getMessage());
                }
            }
            
            log.info("COBO collection processed: {} for {} on behalf of {} (fee: {}, feePosted: {})", 
                transactionRef, netAmount, request.getOnBehalfOfEntity(), fee, feePosted);
        } else {
            log.info("Processing COBO in demo mode: {} on behalf of {}", 
                netAmount, request.getOnBehalfOfEntity());
        }
        
        return CoboCollectionResponse.builder()
            .transactionReference(transactionRef)
            .grossAmount(request.getAmount())
            .fee(fee)
            .netAmount(netAmount)
            .targetVaId(request.getTargetVaId())
            .targetVaNumber(targetVa != null ? targetVa.getVaNumber() : "VA-DEMO-001")
            .targetVaBalance(targetVaBalance)
            .onBehalfOfEntity(request.getOnBehalfOfEntity())
            .onBehalfOfVaId(request.getOnBehalfOfVaId())
            .settlementVaId(settlementVaId)
            .settlementVaNumber(settlementVaNumber)
            .processedAt(LocalDateTime.now())
            .build();
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

    /**
     * Create a published payment VIBAN linked to a receivable.
     * This enhances the existing createPaymentViban to support published VA flow.
     */
    @Transactional
    public PaymentVibanResponse createPublishedPaymentViban(UUID corporateId, CreatePublishedVibanRequest request) {
        log.info("Creating published payment VIBAN for receivable: {}", request.getReceivableId());

        // Find or create VIBAN linked to receivable
        String viban = "AE150410COLL" + String.format("%011d", System.currentTimeMillis() % 100000000000L);
        String reference = "PV-" + LocalDate.now().getYear() + "-" + String.format("%05d", System.currentTimeMillis() % 100000);

        // If target VA is specified, check if it's published
        VirtualAccount targetVa = null;
        if (request.getTargetVaId() != null) {
            targetVa = virtualAccountRepository.findById(request.getTargetVaId()).orElse(null);
            if (targetVa != null && targetVa.isPublished()) {
                // Use VA's existing VIBAN
                viban = targetVa.getViban();
            }
        }

        int expiresInHours = request.getExpiresInHours() != null ? request.getExpiresInHours() : 168;

        return PaymentVibanResponse.builder()
            .id(UUID.randomUUID())
            .virtualIban(viban)
            .reference(reference)
            .customerName(request.getCustomerName())
            .expectedAmount(request.getExpectedAmount())
            .receivedAmount(BigDecimal.ZERO)
            .currency(request.getCurrency())
            .status("ACTIVE")
            .createdAt(LocalDateTime.now())
            .expiresAt(LocalDateTime.now().plusHours(expiresInHours))
            .purpose(request.getPurpose())
            .paymentLink("https://pay.bank.com/v/" + viban.substring(Math.max(0, viban.length() - 8)))
            .linkedReceivableId(request.getReceivableId())
            .autoReconcile(true)
            .isPublished(targetVa != null && targetVa.isPublished())
            .targetVaId(request.getTargetVaId())
            .targetVaNumber(targetVa != null ? targetVa.getVaNumber() : null)
            .build();
    }

    // ========================================================================
    // PAYMENT MATCHING - Using Shared FeePostingService
    // ========================================================================

    /**
     * Match unmatched payment to receivable with matching fee.
     * Uses shared FeePostingService for standardized fee posting.
     */
    @Transactional
    public MatchPaymentResponse matchPaymentEnhanced(UUID unmatchedPaymentId, MatchPaymentRequest request) {
        log.info("Matching payment {} to invoice {}", unmatchedPaymentId, request.getInvoiceId());
        
        // Get unmatched payment details (demo data)
        UnmatchedPaymentResponse unmatched = buildDemoUnmatchedPayments().stream()
            .filter(u -> u.getId().equals(unmatchedPaymentId))
            .findFirst()
            .orElse(buildDemoUnmatchedPayments().get(0));
        
        // Get invoice details (demo data)
        InvoiceResponse invoice = buildDemoInvoices().stream()
            .filter(i -> i.getId().equals(request.getInvoiceId()))
            .findFirst()
            .orElse(buildDemoInvoices().get(0));
        
        BigDecimal matchingFee = MATCHING_FEE_FLAT;
        
        // Post matching fee using shared FeePostingService
        if (request.getTargetVaId() != null && feePostingService != null) {
            try {
                FeePostingService.FeePostingResult feeResult = feePostingService.postFee(
                    request.getTargetVaId(),
                    matchingFee,
                    "PAYMENT_MATCHING_FEE",
                    unmatchedPaymentId,
                    "Payment matching fee - " + unmatched.getPaymentReference() + " to " + invoice.getInvoiceNumber()
                );
                
                if ("POSTED".equals(feeResult.getStatus())) {
                    log.info("Matching fee {} posted to Settlement VA {}", matchingFee, feeResult.getSettlementVaNumber());
                }
            } catch (Exception e) {
                log.error("Failed to post matching fee: {}", e.getMessage());
            }
        }
        
        log.info("Payment {} matched to invoice {} (fee: {})", 
            unmatchedPaymentId, invoice.getInvoiceNumber(), matchingFee);
        
        return MatchPaymentResponse.builder()
            .paymentId(unmatchedPaymentId)
            .paymentReference(unmatched.getPaymentReference())
            .invoiceId(request.getInvoiceId())
            .invoiceNumber(invoice.getInvoiceNumber())
            .matchedAmount(unmatched.getAmount())
            .receivableStatus("PAID")
            .outstandingAmount(BigDecimal.ZERO)
            .matchingFee(matchingFee)
            .matchedAt(LocalDateTime.now())
            .build();
    }

    /**
     * Return unmatched payment with return processing fee.
     * Uses shared FeePostingService for standardized fee posting.
     */
    @Transactional
    public ReturnPaymentResponse returnPaymentEnhanced(UUID unmatchedPaymentId, ReturnPaymentRequest request) {
        log.info("Returning payment {}: {}", unmatchedPaymentId, request.getReason());
        
        UnmatchedPaymentResponse unmatched = buildDemoUnmatchedPayments().stream()
            .filter(u -> u.getId().equals(unmatchedPaymentId))
            .findFirst()
            .orElse(buildDemoUnmatchedPayments().get(0));
        
        BigDecimal returnFee = RETURN_FEE_FLAT;
        String returnRef = "RTN-" + System.currentTimeMillis();
        
        // Post return fee using shared FeePostingService
        if (request.getSourceVaId() != null && feePostingService != null) {
            try {
                FeePostingService.FeePostingResult feeResult = feePostingService.postFee(
                    request.getSourceVaId(),
                    returnFee,
                    "PAYMENT_RETURN_FEE",
                    unmatchedPaymentId,
                    "Payment return processing fee - " + unmatched.getPaymentReference()
                );
                
                if ("POSTED".equals(feeResult.getStatus())) {
                    log.info("Return fee {} posted to Settlement VA {}", returnFee, feeResult.getSettlementVaNumber());
                }
            } catch (Exception e) {
                log.error("Failed to post return fee: {}", e.getMessage());
            }
        }
        
        log.info("Payment {} returned: {} (fee: {})", unmatchedPaymentId, request.getReason(), returnFee);
        
        return ReturnPaymentResponse.builder()
            .paymentId(unmatchedPaymentId)
            .paymentReference(unmatched.getPaymentReference())
            .returnReference(returnRef)
            .amount(unmatched.getAmount())
            .returnFee(returnFee)
            .reason(request.getReason())
            .returnedAt(LocalDateTime.now())
            .build();
    }

    // ========================================================================
    // ESCROW RELEASE - Using Shared FeePostingService
    // ========================================================================

    /**
     * Release escrow with escrow service fee.
     * Uses shared FeePostingService for standardized fee posting.
     */
    @Transactional
    public EscrowReleaseResponse releaseEscrowEnhanced(UUID escrowId, ReleaseEscrowRequest request) {
        log.info("Releasing escrow {}: {}", escrowId, request.getReason());
        
        BigDecimal releaseAmount = request.getAmount() != null ? request.getAmount() : 
            request.getReleaseAmount() != null ? request.getReleaseAmount() : 
            BigDecimal.valueOf(5000); // Demo default
        
        // 1. Calculate escrow fee
        BigDecimal escrowFee = calculateEscrowFee(releaseAmount);
        BigDecimal netAmount = releaseAmount.subtract(escrowFee);
        
        String correlationId = "ESC-" + System.currentTimeMillis();
        String releaseRef = "REL-" + correlationId;
        UUID settlementVaId = null;
        String settlementVaNumber = null;
        BigDecimal targetVaBalance = netAmount;
        boolean feePosted = false;
        
        // Process with real entities if available
        VirtualAccount escrowVa = null;
        VirtualAccount targetVa = null;
        
        if (request.getEscrowVaId() != null && virtualAccountRepository != null) {
            escrowVa = virtualAccountRepository.findById(request.getEscrowVaId()).orElse(null);
        }
        if (request.getTargetVaId() != null && virtualAccountRepository != null) {
            targetVa = virtualAccountRepository.findById(request.getTargetVaId()).orElse(null);
        }
        
        if (escrowVa != null && targetVa != null && 
            escrowVa.getStatus() == VaStatus.ACTIVE && targetVa.getStatus() == VaStatus.ACTIVE) {
            
            // 2. Debit escrow VA
            BigDecimal escrowBalanceBefore = escrowVa.getCurrentBalance();
            escrowVa.debit(releaseAmount);
            virtualAccountRepository.save(escrowVa);
            
            // 3. Credit target VA with net amount
            BigDecimal targetBalanceBefore = targetVa.getCurrentBalance();
            targetVa.credit(netAmount);
            virtualAccountRepository.save(targetVa);
            targetVaBalance = targetVa.getCurrentBalance();
            
            // 4. Record transactions
            Transaction debitTxn = Transaction.builder()
                .referenceNumber(Transaction.generateReference(MovementType.TRANSFER_OUT))
                .movementType(MovementType.TRANSFER_OUT)
                .corporateId(escrowVa.getCorporateId())
                .vaId(escrowVa.getId())
                .physicalAccountId(escrowVa.getPhysicalAccountId())
                .amount(releaseAmount)
                .currencyCode(escrowVa.getCurrencyCode())
                .balanceBefore(escrowBalanceBefore)
                .balanceAfter(escrowVa.getCurrentBalance())
                .correlationId(correlationId)
                .counterpartyVaId(targetVa.getId())
                .description("Escrow release: " + request.getReason())
                .status(TransactionStatus.COMPLETED)
                .transactionDate(LocalDateTime.now())
                .valueDate(LocalDate.now())
                .channel("ESCROW")
                .build();
            transactionRepository.save(debitTxn);
            
            Transaction creditTxn = Transaction.builder()
                .referenceNumber(Transaction.generateReference(MovementType.TRANSFER_IN))
                .movementType(MovementType.TRANSFER_IN)
                .corporateId(targetVa.getCorporateId())
                .vaId(targetVa.getId())
                .physicalAccountId(targetVa.getPhysicalAccountId())
                .amount(netAmount)
                .currencyCode(targetVa.getCurrencyCode())
                .balanceBefore(targetBalanceBefore)
                .balanceAfter(targetVa.getCurrentBalance())
                .correlationId(correlationId)
                .feeAmount(escrowFee)
                .counterpartyVaId(escrowVa.getId())
                .description("Escrow release from: " + escrowVa.getVaNumber())
                .status(TransactionStatus.COMPLETED)
                .transactionDate(LocalDateTime.now())
                .valueDate(LocalDate.now())
                .channel("ESCROW")
                .build();
            transactionRepository.save(creditTxn);
            releaseRef = creditTxn.getReferenceNumber();
            
            // 5. Post escrow fee to Settlement VA using SHARED FeePostingService
            if (escrowFee.compareTo(BigDecimal.ZERO) > 0 && feePostingService != null) {
                try {
                    FeePostingService.FeePostingResult feeResult = feePostingService.postFee(
                        targetVa.getId(),
                        escrowFee,
                        "ESCROW_RELEASE_FEE",
                        creditTxn.getId(),
                        "Escrow release fee - " + releaseRef
                    );
                    
                    if ("POSTED".equals(feeResult.getStatus())) {
                        settlementVaId = feeResult.getSettlementVaId();
                        settlementVaNumber = feeResult.getSettlementVaNumber();
                        feePosted = true;
                        
                        if (feeResult.isExceptionFallback()) {
                            log.warn("Escrow fee {} posted to Exception VA (no Settlement VA found)", escrowFee);
                        } else {
                            log.info("Escrow fee {} posted to Settlement VA {}", escrowFee, settlementVaNumber);
                        }
                    }
                } catch (Exception e) {
                    log.error("Failed to post escrow fee: {}", e.getMessage());
                }
            }
            
            log.info("Escrow released: {} (net: {}, fee: {}, feePosted: {})", 
                releaseAmount, netAmount, escrowFee, feePosted);
        } else {
            log.info("Processing escrow release in demo mode: {} (net: {}, fee: {})", 
                releaseAmount, netAmount, escrowFee);
        }
        
        return EscrowReleaseResponse.builder()
            .escrowId(escrowId)
            .releaseReference(releaseRef)
            .grossAmount(releaseAmount)
            .fee(escrowFee)
            .netAmount(netAmount)
            .targetVaId(request.getTargetVaId())
            .targetVaNumber(targetVa != null ? targetVa.getVaNumber() : "VA-DEMO-001")
            .targetVaBalance(targetVaBalance)
            .settlementVaId(settlementVaId)
            .settlementVaNumber(settlementVaNumber)
            .reason(request.getReason())
            .releasedAt(LocalDateTime.now())
            .build();
    }

    // ========================================================================
    // FEE CALCULATION
    // ========================================================================

    /**
     * Calculate collection fee based on amount and routing method.
     * 
     * Fee Structure:
     * - Via VIBAN: 0.1% (incentivizes VIBAN usage for auto-reconciliation)
     * - Non-VIBAN: 0.15%
     * 
     * @param amount Collection amount
     * @param viaViban Whether collection was routed via VIBAN
     * @return Calculated fee
     */
    private BigDecimal calculateCollectionFee(BigDecimal amount, boolean viaViban) {
        BigDecimal rate = viaViban ? COLLECTION_FEE_PERCENT : COLLECTION_FEE_NON_VIBAN;
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculate COBO service fee.
     * 
     * Fee Structure:
     * - 0.15% of collection amount
     * 
     * @param amount Collection amount
     * @return Calculated COBO fee
     */
    private BigDecimal calculateCoboFee(BigDecimal amount) {
        return amount.multiply(COBO_FEE_PERCENT).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculate escrow release fee.
     * 
     * Fee Structure:
     * - 0.5% of release amount (escrow management service)
     * 
     * @param amount Release amount
     * @return Calculated escrow fee
     */
    private BigDecimal calculateEscrowFee(BigDecimal amount) {
        return amount.multiply(ESCROW_FEE_PERCENT).setScale(2, RoundingMode.HALF_UP);
    }

    // ========================================================================
    // E-COMMERCE ORDERS (Existing - Preserved for backward compatibility)
    // ========================================================================

    public List<EcommerceOrderResponse> getAllEcommerceOrders(UUID corporateId, String platform, String escrowStatus) {
        List<EcommerceOrderResponse> orders = buildDemoEcommerceOrders();
        
        if (platform != null && !platform.isEmpty() && !"all".equals(platform)) {
            orders = orders.stream()
                .filter(o -> platform.equalsIgnoreCase(o.getPlatform()))
                .collect(Collectors.toList());
        }
        
        if (escrowStatus != null && !escrowStatus.isEmpty() && !"all".equals(escrowStatus)) {
            orders = orders.stream()
                .filter(o -> escrowStatus.equalsIgnoreCase(o.getEscrowStatus()))
                .collect(Collectors.toList());
        }
        
        return orders;
    }

    public List<EcommercePlatformStats> getPlatformStats(UUID corporateId) {
        List<EcommerceOrderResponse> orders = getAllEcommerceOrders(corporateId, null, null);
        
        Map<String, List<EcommerceOrderResponse>> byPlatform = orders.stream()
            .collect(Collectors.groupingBy(EcommerceOrderResponse::getPlatform));
        
        return byPlatform.entrySet().stream()
            .map(e -> {
                List<EcommerceOrderResponse> platformOrders = e.getValue();
                return EcommercePlatformStats.builder()
                    .platform(e.getKey())
                    .platformLogo(e.getKey().contains("Amazon") ? "🛒" : "🌙")
                    .orderCount(platformOrders.size())
                    .collected(platformOrders.stream()
                        .filter(o -> "RECEIVED".equals(o.getPaymentStatus()))
                        .map(EcommerceOrderResponse::getNetAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                    .inEscrow(platformOrders.stream()
                        .filter(o -> "HELD".equals(o.getEscrowStatus()))
                        .map(EcommerceOrderResponse::getNetAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                    .pending(platformOrders.stream()
                        .filter(o -> "PENDING".equals(o.getPaymentStatus()))
                        .map(EcommerceOrderResponse::getNetAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                    .build();
            })
            .collect(Collectors.toList());
    }

    public EcommerceOrderResponse releaseEscrow(UUID orderId, ReleaseEscrowRequest request) {
        log.info("Releasing escrow for order {}: {}", orderId, request.getReason());
        
        EcommerceOrderResponse order = buildDemoEcommerceOrders().get(0);
        return EcommerceOrderResponse.builder()
            .id(orderId)
            .platform(order.getPlatform())
            .platformLogo(order.getPlatformLogo())
            .platformOrderId(order.getPlatformOrderId())
            .orderDate(order.getOrderDate())
            .productName(order.getProductName())
            .quantity(order.getQuantity())
            .buyerName(order.getBuyerName())
            .buyerCity(order.getBuyerCity())
            .orderAmount(order.getOrderAmount())
            .platformFee(order.getPlatformFee())
            .netAmount(order.getNetAmount())
            .currency(order.getCurrency())
            .paymentStatus("RECEIVED")
            .escrowStatus("RELEASED")
            .deliveryStatus(order.getDeliveryStatus())
            .paymentReceivedAt(order.getPaymentReceivedAt())
            .deliveredAt(order.getDeliveredAt())
            .releasedAt(LocalDateTime.now())
            .build();
    }

    // ========================================================================
    // PAYMENT VIBANS (Existing - Preserved for backward compatibility)
    // ========================================================================

    public List<PaymentVibanResponse> getAllPaymentVibans(UUID corporateId, String status) {
        List<PaymentVibanResponse> vibans = buildDemoPaymentVibans();
        
        if (status != null && !status.isEmpty() && !"all".equals(status)) {
            vibans = vibans.stream()
                .filter(v -> status.equalsIgnoreCase(v.getStatus()))
                .collect(Collectors.toList());
        }
        
        return vibans;
    }

    public PaymentVibanResponse createPaymentViban(UUID corporateId, CreatePaymentVibanRequest request) {
        String viban = "AE150410CORP" + String.format("%011d", System.currentTimeMillis() % 100000000000L);
        String reference = "VIBAN-" + LocalDate.now().getYear() + "-" + String.format("%03d", System.currentTimeMillis() % 1000);
        
        int expiresInHours = request.getExpiresInHours() != null ? request.getExpiresInHours() : 168;
        
        return PaymentVibanResponse.builder()
            .id(UUID.randomUUID())
            .virtualIban(viban)
            .reference(reference)
            .customerName(request.getCustomerName())
            .expectedAmount(request.getExpectedAmount())
            .receivedAmount(BigDecimal.ZERO)
            .currency(request.getCurrency())
            .status("ACTIVE")
            .createdAt(LocalDateTime.now())
            .expiresAt(LocalDateTime.now().plusHours(expiresInHours))
            .purpose(request.getPurpose())
            .paymentLink("https://pay.bank.com/v/" + viban.substring(viban.length() - 8))
            .linkedReceivableId(request.getReceivableId())
            .autoReconcile(request.getAutoReconcile())
            .build();
    }

    public PaymentVibanResponse cancelPaymentViban(UUID vibanId) {
        PaymentVibanResponse viban = buildDemoPaymentVibans().get(1);
        return PaymentVibanResponse.builder()
            .id(vibanId)
            .virtualIban(viban.getVirtualIban())
            .reference(viban.getReference())
            .customerName(viban.getCustomerName())
            .expectedAmount(viban.getExpectedAmount())
            .receivedAmount(viban.getReceivedAmount())
            .currency(viban.getCurrency())
            .status("CANCELLED")
            .createdAt(viban.getCreatedAt())
            .expiresAt(viban.getExpiresAt())
            .purpose(viban.getPurpose())
            .paymentLink(null)
            .build();
    }

    // ========================================================================
    // UNMATCHED PAYMENTS (Existing - with backward compatible wrappers)
    // ========================================================================

    public List<UnmatchedPaymentResponse> getUnmatchedPayments(UUID corporateId) {
        return buildDemoUnmatchedPayments();
    }

    public UnmatchedPaymentResponse matchPayment(UUID paymentId, MatchPaymentRequest request) {
        log.info("Matching payment {} to invoice {}", paymentId, request.getInvoiceId());
        
        UnmatchedPaymentResponse payment = buildDemoUnmatchedPayments().get(0);
        return UnmatchedPaymentResponse.builder()
            .id(paymentId)
            .paymentReference(payment.getPaymentReference())
            .senderName(payment.getSenderName())
            .senderAccount(payment.getSenderAccount())
            .amount(payment.getAmount())
            .currencyCode(payment.getCurrencyCode())
            .receivedDate(payment.getReceivedDate())
            .vaNumber(payment.getVaNumber())
            .vaName(payment.getVaName())
            .remittanceInfo(payment.getRemittanceInfo())
            .suggestedMatches(Collections.emptyList())
            .status("MATCHED")
            .build();
    }

    public UnmatchedPaymentResponse returnPayment(UUID paymentId, ReturnPaymentRequest request) {
        log.info("Returning payment {}: {}", paymentId, request.getReason());
        
        UnmatchedPaymentResponse payment = buildDemoUnmatchedPayments().get(1);
        return UnmatchedPaymentResponse.builder()
            .id(paymentId)
            .paymentReference(payment.getPaymentReference())
            .senderName(payment.getSenderName())
            .senderAccount(payment.getSenderAccount())
            .amount(payment.getAmount())
            .currencyCode(payment.getCurrencyCode())
            .receivedDate(payment.getReceivedDate())
            .vaNumber(payment.getVaNumber())
            .vaName(payment.getVaName())
            .remittanceInfo(payment.getRemittanceInfo())
            .suggestedMatches(Collections.emptyList())
            .status("RETURNED")
            .build();
    }

    public UnmatchedPaymentResponse escalatePayment(UUID paymentId) {
        log.info("Escalating payment {}", paymentId);
        
        UnmatchedPaymentResponse payment = buildDemoUnmatchedPayments().get(1);
        return UnmatchedPaymentResponse.builder()
            .id(paymentId)
            .paymentReference(payment.getPaymentReference())
            .senderName(payment.getSenderName())
            .senderAccount(payment.getSenderAccount())
            .amount(payment.getAmount())
            .currencyCode(payment.getCurrencyCode())
            .receivedDate(payment.getReceivedDate())
            .vaNumber(payment.getVaNumber())
            .vaName(payment.getVaName())
            .remittanceInfo(payment.getRemittanceInfo())
            .suggestedMatches(Collections.emptyList())
            .status("ESCALATED")
            .build();
    }

    // ========================================================================
    // POS COLLECTIONS (Existing - Preserved for backward compatibility)
    // ========================================================================

    public List<PosCollectionResponse> getAllPosCollections(UUID corporateId, String status, String merchantId) {
        List<PosCollectionResponse> collections = buildDemoPosCollections();
        
        if (status != null && !status.isEmpty() && !"all".equals(status)) {
            collections = collections.stream()
                .filter(c -> status.equalsIgnoreCase(c.getStatus()))
                .collect(Collectors.toList());
        }
        
        if (merchantId != null && !merchantId.isEmpty()) {
            collections = collections.stream()
                .filter(c -> merchantId.equals(c.getMerchantId()))
                .collect(Collectors.toList());
        }
        
        return collections;
    }

    public PosStatsResponse getPosStats(UUID corporateId) {
        List<PosCollectionResponse> collections = getAllPosCollections(corporateId, null, null);
        
        BigDecimal total = collections.stream()
            .map(PosCollectionResponse::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal today = collections.stream()
            .filter(c -> c.getTransactionDate().toLocalDate().equals(LocalDate.now()) || 
                        c.getTransactionDate().toLocalDate().equals(LocalDate.of(2024, 2, 12)))
            .map(PosCollectionResponse::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal pending = collections.stream()
            .filter(c -> "PENDING".equals(c.getSettlementStatus()))
            .map(PosCollectionResponse::getNetAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal settled = collections.stream()
            .filter(c -> "SETTLED".equals(c.getSettlementStatus()))
            .map(PosCollectionResponse::getNetAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        long completed = collections.stream().filter(c -> "COMPLETED".equals(c.getStatus())).count();
        double successRate = collections.isEmpty() ? 0 : (completed * 100.0 / collections.size());
        
        BigDecimal avgTicket = collections.isEmpty() ? BigDecimal.ZERO : 
            total.divide(BigDecimal.valueOf(collections.size()), 2, RoundingMode.HALF_UP);
        
        return PosStatsResponse.builder()
            .totalCollections(total)
            .todayCollections(today)
            .pendingSettlement(pending)
            .settledAmount(settled)
            .transactionCount(collections.size())
            .todayTransactions((int) collections.stream()
                .filter(c -> c.getTransactionDate().toLocalDate().equals(LocalDate.now()) ||
                            c.getTransactionDate().toLocalDate().equals(LocalDate.of(2024, 2, 12)))
                .count())
            .successRate(successRate)
            .averageTicket(avgTicket)
            .build();
    }

    public List<MerchantSummary> getMerchantSummaries(UUID corporateId) {
        List<PosCollectionResponse> collections = getAllPosCollections(corporateId, null, null);
        
        Map<String, List<PosCollectionResponse>> byMerchant = collections.stream()
            .collect(Collectors.groupingBy(PosCollectionResponse::getMerchantId));
        
        return byMerchant.entrySet().stream()
            .map(e -> {
                List<PosCollectionResponse> merchantTxns = e.getValue();
                PosCollectionResponse first = merchantTxns.get(0);
                return MerchantSummary.builder()
                    .merchantId(e.getKey())
                    .merchantName(first.getMerchantName())
                    .category("Retail")
                    .transactionCount(merchantTxns.size())
                    .totalVolume(merchantTxns.stream()
                        .map(PosCollectionResponse::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                    .pendingSettlement(merchantTxns.stream()
                        .filter(t -> "PENDING".equals(t.getSettlementStatus()))
                        .map(PosCollectionResponse::getNetAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                    .status("ACTIVE")
                    .build();
            })
            .collect(Collectors.toList());
    }

    // ========================================================================
    // DEMO DATA BUILDERS (Preserved for backward compatibility)
    // ========================================================================

    private List<InvoiceResponse> buildDemoInvoices() {
        return Arrays.asList(
            InvoiceResponse.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .invoiceNumber("INV-2024-0156")
                .customerName("Emirates Steel Industries")
                .customerId(UUID.randomUUID())
                .customerVaNumber("VA-AR-001")
                .invoiceDate(LocalDate.of(2024, 1, 15))
                .dueDate(LocalDate.of(2024, 2, 15))
                .amount(BigDecimal.valueOf(125000))
                .paidAmount(BigDecimal.valueOf(125000))
                .outstandingAmount(BigDecimal.ZERO)
                .currencyCode("AED")
                .status("PAID")
                .matchedPayments(Arrays.asList(
                    MatchedPaymentResponse.builder()
                        .paymentRef("PMT-89234")
                        .amount(BigDecimal.valueOf(125000))
                        .matchDate(LocalDateTime.of(2024, 2, 10, 14, 30))
                        .matchType("AUTO")
                        .confidence(98)
                        .autoMatched(true)
                        .build()
                ))
                .build(),
            InvoiceResponse.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000002"))
                .invoiceNumber("INV-2024-0157")
                .customerName("Al Futtaim Group")
                .customerId(UUID.randomUUID())
                .customerVaNumber("VA-AR-002")
                .invoiceDate(LocalDate.of(2024, 1, 20))
                .dueDate(LocalDate.of(2024, 2, 20))
                .amount(BigDecimal.valueOf(87500))
                .paidAmount(BigDecimal.valueOf(50000))
                .outstandingAmount(BigDecimal.valueOf(37500))
                .currencyCode("AED")
                .status("PARTIAL")
                .matchedPayments(Arrays.asList(
                    MatchedPaymentResponse.builder()
                        .paymentRef("PMT-89567")
                        .amount(BigDecimal.valueOf(50000))
                        .matchDate(LocalDateTime.of(2024, 2, 12, 10, 15))
                        .matchType("MANUAL")
                        .autoMatched(false)
                        .build()
                ))
                .build(),
            InvoiceResponse.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000003"))
                .invoiceNumber("INV-2024-0158")
                .customerName("Majid Al Futtaim")
                .customerId(UUID.randomUUID())
                .customerVaNumber("VA-AR-003")
                .invoiceDate(LocalDate.of(2024, 1, 25))
                .dueDate(LocalDate.of(2024, 2, 10))
                .amount(BigDecimal.valueOf(45000))
                .paidAmount(BigDecimal.ZERO)
                .outstandingAmount(BigDecimal.valueOf(45000))
                .currencyCode("AED")
                .status("OVERDUE")
                .isOverdue(true)
                .daysOverdue(5)
                .matchedPayments(Collections.emptyList())
                .build(),
            InvoiceResponse.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000004"))
                .invoiceNumber("INV-2024-0159")
                .customerName("Emirates Steel Industries")
                .customerId(UUID.randomUUID())
                .customerVaNumber("VA-AR-001")
                .invoiceDate(LocalDate.of(2024, 2, 1))
                .dueDate(LocalDate.of(2024, 3, 1))
                .amount(BigDecimal.valueOf(230000))
                .paidAmount(BigDecimal.ZERO)
                .outstandingAmount(BigDecimal.valueOf(230000))
                .currencyCode("AED")
                .status("OPEN")
                .matchedPayments(Collections.emptyList())
                .build()
        );
    }

    private List<EcommerceOrderResponse> buildDemoEcommerceOrders() {
        return Arrays.asList(
            EcommerceOrderResponse.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000011"))
                .platform("Amazon.ae")
                .platformLogo("🛒")
                .platformOrderId("AMZ-408-2847592")
                .orderDate(LocalDateTime.of(2024, 2, 12, 10, 30))
                .productName("Samsung Galaxy S24 Ultra 256GB")
                .quantity(1)
                .buyerName("Ahmed M.")
                .buyerCity("Dubai")
                .orderAmount(BigDecimal.valueOf(4299.00))
                .platformFee(BigDecimal.valueOf(429.90))
                .netAmount(BigDecimal.valueOf(3869.10))
                .currency("AED")
                .paymentStatus("RECEIVED")
                .escrowStatus("HELD")
                .deliveryStatus("IN_TRANSIT")
                .paymentReceivedAt(LocalDateTime.of(2024, 2, 12, 10, 35))
                .deliveredAt(null)
                .releasedAt(null)
                .build(),
            EcommerceOrderResponse.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000012"))
                .platform("Noon")
                .platformLogo("🌙")
                .platformOrderId("NOON-N12847362")
                .orderDate(LocalDateTime.of(2024, 2, 12, 9, 15))
                .productName("Apple MacBook Air M3 13\"")
                .quantity(1)
                .buyerName("Sara K.")
                .buyerCity("Abu Dhabi")
                .orderAmount(BigDecimal.valueOf(5499.00))
                .platformFee(BigDecimal.valueOf(384.93))
                .netAmount(BigDecimal.valueOf(5114.07))
                .currency("AED")
                .paymentStatus("RECEIVED")
                .escrowStatus("RELEASED")
                .deliveryStatus("DELIVERED")
                .paymentReceivedAt(LocalDateTime.of(2024, 2, 12, 9, 20))
                .deliveredAt(LocalDateTime.of(2024, 2, 12, 16, 30))
                .releasedAt(LocalDateTime.of(2024, 2, 12, 17, 0))
                .build(),
            EcommerceOrderResponse.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000013"))
                .platform("Amazon.ae")
                .platformLogo("🛒")
                .platformOrderId("AMZ-408-2847601")
                .orderDate(LocalDateTime.of(2024, 2, 12, 14, 20))
                .productName("Sony WH-1000XM5 Headphones")
                .quantity(2)
                .buyerName("Mohammed A.")
                .buyerCity("Sharjah")
                .orderAmount(BigDecimal.valueOf(2598.00))
                .platformFee(BigDecimal.valueOf(259.80))
                .netAmount(BigDecimal.valueOf(2338.20))
                .currency("AED")
                .paymentStatus("PENDING")
                .escrowStatus("AWAITING_PAYMENT")
                .deliveryStatus("PENDING")
                .paymentReceivedAt(null)
                .deliveredAt(null)
                .releasedAt(null)
                .build(),
            EcommerceOrderResponse.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000014"))
                .platform("Noon")
                .platformLogo("🌙")
                .platformOrderId("NOON-N12847401")
                .orderDate(LocalDateTime.of(2024, 2, 11, 16, 45))
                .productName("iPad Pro 12.9\" M2 256GB")
                .quantity(1)
                .buyerName("Fatima H.")
                .buyerCity("Dubai")
                .orderAmount(BigDecimal.valueOf(4899.00))
                .platformFee(BigDecimal.valueOf(342.93))
                .netAmount(BigDecimal.valueOf(4556.07))
                .currency("AED")
                .paymentStatus("RECEIVED")
                .escrowStatus("DISPUTED")
                .deliveryStatus("DELIVERED")
                .paymentReceivedAt(LocalDateTime.of(2024, 2, 11, 16, 50))
                .deliveredAt(LocalDateTime.of(2024, 2, 12, 10, 0))
                .releasedAt(null)
                .build()
        );
    }

    private List<PaymentVibanResponse> buildDemoPaymentVibans() {
        return Arrays.asList(
            PaymentVibanResponse.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000021"))
                .virtualIban("AE150410CORP00001234567")
                .reference("VIBAN-2024-001")
                .customerName("Al Futtaim Group")
                .expectedAmount(BigDecimal.valueOf(75000))
                .receivedAmount(BigDecimal.valueOf(75000))
                .currency("AED")
                .status("PAID")
                .createdAt(LocalDateTime.of(2024, 2, 10, 8, 0))
                .expiresAt(LocalDateTime.of(2024, 2, 17, 8, 0))
                .purpose("Q1 Subscription Payment")
                .paymentLink("https://pay.bank.com/v/1234567")
                .autoReconcile(true)
                .build(),
            PaymentVibanResponse.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000022"))
                .virtualIban("AE150410CORP00001234568")
                .reference("VIBAN-2024-002")
                .customerName("Majid Al Futtaim")
                .expectedAmount(BigDecimal.valueOf(120000))
                .receivedAmount(BigDecimal.ZERO)
                .currency("AED")
                .status("ACTIVE")
                .createdAt(LocalDateTime.of(2024, 2, 12, 10, 0))
                .expiresAt(LocalDateTime.now().plusDays(5))
                .purpose("Service Agreement Payment")
                .paymentLink("https://pay.bank.com/v/1234568")
                .autoReconcile(true)
                .build(),
            PaymentVibanResponse.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000023"))
                .virtualIban("AE150410CORP00001234569")
                .reference("VIBAN-2024-003")
                .customerName("Dubai Holdings")
                .expectedAmount(BigDecimal.valueOf(250000))
                .receivedAmount(BigDecimal.ZERO)
                .currency("AED")
                .status("EXPIRED")
                .createdAt(LocalDateTime.of(2024, 2, 1, 9, 0))
                .expiresAt(LocalDateTime.of(2024, 2, 8, 9, 0))
                .purpose("Project Milestone Payment")
                .paymentLink(null)
                .autoReconcile(false)
                .build()
        );
    }

    private List<UnmatchedPaymentResponse> buildDemoUnmatchedPayments() {
        return Arrays.asList(
            UnmatchedPaymentResponse.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000031"))
                .paymentReference("TRF-2024-78234")
                .senderName("Emirates Trading")
                .senderAccount("AE12 3456 7890 1234")
                .amount(BigDecimal.valueOf(45000))
                .currencyCode("AED")
                .receivedDate(LocalDateTime.of(2024, 2, 12, 9, 30))
                .vaNumber("VA001001")
                .vaName("Receivables Pool")
                .remittanceInfo("Payment for Feb invoice")
                .suggestedMatches(Arrays.asList(
                    SuggestedMatchResponse.builder()
                        .invoiceNumber("INV-2024-0158")
                        .customerName("Majid Al Futtaim")
                        .invoiceAmount(BigDecimal.valueOf(45000))
                        .confidence(92)
                        .matchReason("Amount match + sender pattern")
                        .receivableId(UUID.fromString("00000000-0000-0000-0000-000000000003"))
                        .outstandingAmount(BigDecimal.valueOf(45000))
                        .build()
                ))
                .status("PENDING")
                .ageInDays(1)
                .urgency("MEDIUM")
                .build(),
            UnmatchedPaymentResponse.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000032"))
                .paymentReference("TRF-2024-78456")
                .senderName("Unknown Corp Ltd")
                .senderAccount("AE98 7654 3210 9876")
                .amount(BigDecimal.valueOf(12500))
                .currencyCode("AED")
                .receivedDate(LocalDateTime.of(2024, 2, 12, 11, 15))
                .vaNumber("VA001002")
                .vaName("Collections Account")
                .remittanceInfo("Order payment")
                .suggestedMatches(Collections.emptyList())
                .status("PENDING")
                .ageInDays(1)
                .urgency("LOW")
                .build()
        );
    }

    private List<PosCollectionResponse> buildDemoPosCollections() {
        String[] merchants = {"MER100001", "MER100002", "MER100003"};
        String[] merchantNames = {"Premium Retail Store", "Electronics Hub", "Fashion Outlet"};
        String[] paymentMethods = {"CARD", "CARD", "WALLET", "CARD"};
        String[] cardTypes = {"VISA", "MASTERCARD", "AMEX", "VISA"};
        String[] statuses = {"COMPLETED", "COMPLETED", "COMPLETED", "PENDING"};
        String[] settlementStatuses = {"SETTLED", "SETTLED", "PENDING", "PENDING"};
        
        List<PosCollectionResponse> collections = new ArrayList<>();
        
        for (int i = 0; i < 12; i++) {
            BigDecimal amount = BigDecimal.valueOf(150 + (i * 75));
            BigDecimal commission = amount.multiply(BigDecimal.valueOf(0.02)).setScale(2, RoundingMode.HALF_UP);
            int hour = 9 + (i % 8);
            int minute = (15 + i * 5) % 60;
            
            collections.add(PosCollectionResponse.builder()
                .id(UUID.randomUUID())
                .transactionRef("TXN" + String.format("%012d", 202402120001L + i))
                .merchantId(merchants[i % 3])
                .merchantName(merchantNames[i % 3])
                .terminalId("TRM" + String.format("%03d", (i % 5) + 1))
                .amount(amount)
                .currencyCode("AED")
                .paymentMethod(paymentMethods[i % 4])
                .cardType(paymentMethods[i % 4].equals("CARD") ? cardTypes[i % 4] : null)
                .cardLastFour(paymentMethods[i % 4].equals("CARD") ? String.format("%04d", 1000 + i) : null)
                .status(statuses[i % 4])
                .settlementStatus(settlementStatuses[i % 4])
                .transactionDate(LocalDateTime.of(2024, 2, 12, hour, minute, 0))
                .settlementDate(settlementStatuses[i % 4].equals("SETTLED") ? LocalDate.of(2024, 2, 12) : null)
                .commissionAmount(commission)
                .netAmount(amount.subtract(commission))
                .build());
        }
        
        return collections;
    }
}