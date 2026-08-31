package com.bank.vam.service.payables;

import com.bank.vam.dto.TransactionDto;
import com.bank.vam.dto.payables.PayablesDto.*;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.party.Party;
import com.bank.vam.entity.payables.Payable;
import com.bank.vam.entity.payables.Payable.*;
import com.bank.vam.entity.treasury.PaymentRequest;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.party.PartyRepository;
import com.bank.vam.repository.payables.PayableRepository;
import com.bank.vam.repository.treasury.PaymentRequestRepository;
import com.bank.vam.service.TransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * PayablesService - Phase 2 Enhanced with POBO, Intercompany & Netting Support
 * 
 * Provides business logic for:
 * - Payable CRUD with entity context
 * - POBO workflow (request → treasury approval → execution)
 * - Intercompany payable management
 * - Netting cycle integration
 * - Statistics and reporting
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayablesService {

    private final PayableRepository payableRepository;
    private final PartyRepository partyRepository;
    private final VirtualAccountRepository virtualAccountRepository;
    private final TransactionRepository transactionRepository;
    private final PaymentRequestRepository paymentRequestRepository;
    private final TransactionService transactionService;
    private final com.bank.vam.config.MarketProfileProperties marketProfile;

    // TODO: Inject these when available
    // private final LegalEntityRepository legalEntityRepository;
    // private final NettingService nettingService;
    // private final IhbUnifiedService ihbService;
    // private final IntercompanyRechargeService rechargeService;

    // ========================================================================
    // CRUD OPERATIONS
    // ========================================================================

    @Transactional
    public PayableResponse createPayable(CreatePayableRequest request) {
        log.info("Creating payable for corporate: {}, owning entity: {}", 
            request.getCorporateId(), request.getOwningEntityId());

        // Generate payable number
        String payableNumber = generatePayableNumber(request.getCorporateId());

        // Build payable entity
        Payable payable = Payable.builder()
            .payableNumber(payableNumber)
            .corporateId(request.getCorporateId())
            .programId(request.getProgramId())
            .virtualAccountId(request.getVirtualAccountId())
            // Phase 2: Entity context
            .owningEntityId(request.getOwningEntityId())
            .owningEntityCode(request.getOwningEntityCode())
            .owningEntityName(request.getOwningEntityName())
            // Phase 2: Party integration
            .partyId(request.getPartyId())
            .partyBankAccountId(request.getPartyBankAccountId())
            // Vendor info (legacy)
            .vendorId(request.getVendorId())
            .vendorName(request.getVendorName())
            .vendorAccount(request.getVendorAccount())
            .vendorBank(request.getVendorBank())
            .vendorBankCode(request.getVendorBankCode())
            .vendorReference(request.getVendorReference())
            // Invoice details
            .invoiceNumber(request.getInvoiceNumber())
            .externalReference(request.getExternalReference())
            .payableType(request.getPayableType() != null ? 
                PayableType.valueOf(request.getPayableType()) : PayableType.INVOICE)
            // Amounts
            .currencyCode(request.getCurrencyCode() != null ? request.getCurrencyCode() : marketProfile.getDefaultCurrency())
            .grossAmount(request.getGrossAmount())
            .discountAmount(request.getDiscountAmount() != null ? request.getDiscountAmount() : BigDecimal.ZERO)
            .taxAmount(request.getTaxAmount() != null ? request.getTaxAmount() : BigDecimal.ZERO)
            .withholdingTax(request.getWithholdingTax() != null ? request.getWithholdingTax() : BigDecimal.ZERO)
            // Dates
            .invoiceDate(request.getInvoiceDate() != null ? request.getInvoiceDate() : LocalDate.now())
            .receivedDate(request.getReceivedDate() != null ? request.getReceivedDate() : LocalDate.now())
            .dueDate(request.getDueDate())
            .paymentTermsDays(request.getPaymentTermsDays())
            // Hierarchy
            .hierarchyNodeId(request.getHierarchyNodeId())
            .hierarchyPath(request.getHierarchyPath())
            // Phase 2: Intercompany
            .isIntercompany(request.getIsIntercompany() != null ? request.getIsIntercompany() : false)
            .counterpartyEntityId(request.getCounterpartyEntityId())
            .counterpartyEntityCode(request.getCounterpartyEntityCode())
            .counterpartyEntityName(request.getCounterpartyEntityName())
            // Phase 2: Payment route
            .paymentRoute(request.getPaymentRoute() != null ? 
                PaymentRoute.valueOf(request.getPaymentRoute()) : PaymentRoute.DIRECT)
            // Scheduling
            .scheduledDate(request.getScheduledDate())
            .paymentPriority(request.getPaymentPriority() != null ? 
                PaymentPriority.valueOf(request.getPaymentPriority()) : PaymentPriority.NORMAL)
            .paymentMethod(request.getPaymentMethod() != null ? 
                PaymentMethod.valueOf(request.getPaymentMethod()) : null)
            .paymentChannel(request.getPaymentChannel() != null ? 
                PaymentChannel.valueOf(request.getPaymentChannel()) : null)
            // Approval
            .approvalRequired(request.getApprovalRequired() != null ? request.getApprovalRequired() : true)
            .approvalLevel(request.getApprovalLevel() != null ? request.getApprovalLevel() : 1)
            // Status - Single stage: go directly to APPROVED (ready for payment)
            .status(PayableStatus.APPROVED)
            .paymentStatus(PaymentStatus.UNPAID)
            .nettingStatus(NettingStatus.NOT_INCLUDED)
            // Metadata
            .description(request.getDescription())
            .notes(request.getNotes())
            .createdBy(request.getCreatedBy())
            .build();

        // Calculate net amount
        BigDecimal netAmount = request.getGrossAmount()
            .subtract(payable.getDiscountAmount())
            .add(payable.getTaxAmount())
            .subtract(payable.getWithholdingTax());
        payable.setNetAmount(netAmount);
        payable.setOutstandingAmount(netAmount);

        // Auto-set netting eligible for IC payables
        if (Boolean.TRUE.equals(payable.getIsIntercompany())) {
            payable.setNettingEligible(true);
            payable.setPaymentRoute(PaymentRoute.INTERCOMPANY);
            payable.setPayableType(PayableType.INTERCOMPANY);
        }

        // Validate party if provided
        if (request.getPartyId() != null) {
            enrichFromParty(payable, request.getPartyId());
        }

        Payable saved = payableRepository.save(payable);
        log.info("Created payable: {} for entity: {}", saved.getPayableNumber(), saved.getOwningEntityCode());

        return mapToResponse(saved);
    }

    @Transactional(readOnly = true)
    public PayableResponse getPayable(UUID payableId) {
        Payable payable = payableRepository.findById(payableId)
            .orElseThrow(() -> new RuntimeException("Payable not found: " + payableId));
        return mapToResponse(payable);
    }

    @Transactional(readOnly = true)
    public PayableListResponse getPayablesByEntity(UUID owningEntityId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Payable> payablePage = payableRepository.findByOwningEntityId(owningEntityId, pageable);
        return mapToListResponse(payablePage);
    }

    @Transactional(readOnly = true)
    public PayableListResponse searchPayables(PayableSearchRequest request) {
        Pageable pageable = PageRequest.of(
            request.getPage(), 
            request.getSize(),
            Sort.by(Sort.Direction.fromString(request.getSortOrder() != null ? request.getSortOrder() : "desc"),
                    request.getSortBy() != null ? request.getSortBy() : "createdAt")
        );

        try {
            // If no corporate ID, return all payables
            if (request.getCorporateId() == null) {
                log.info("No corporate ID provided, returning all payables");
                Page<Payable> payablePage = payableRepository.findAll(pageable);
                return mapToListResponse(payablePage);
            }
            
            // Try the complex search first
            Page<Payable> payablePage = payableRepository.searchPayables(
                request.getCorporateId(),
                request.getOwningEntityId(),
                request.getStatus() != null ? PayableStatus.valueOf(request.getStatus()) : null,
                request.getPaymentRoute() != null ? PaymentRoute.valueOf(request.getPaymentRoute()) : null,
                request.getIsIntercompany(),
                request.getNettingEligible(),
                request.getSearchTerm(),
                pageable
            );
            return mapToListResponse(payablePage);
        } catch (Exception e) {
            log.warn("Complex search failed, falling back to findAll: {}", e.getMessage());
            // Ultimate fallback - just get all payables
            Page<Payable> payablePage = payableRepository.findAll(pageable);
            return mapToListResponse(payablePage);
        }
    }

    // ========================================================================
    // PHASE 2: POBO WORKFLOW
    // ========================================================================

    /**
     * Request POBO for one or more payables.
     * Subsidiary requests treasury to pay on their behalf.
     */
    @Transactional
    public PoboBatchRequestResponse requestPobo(PoboRequestRequest request) {
        log.info("POBO request for {} payables, paying entity: {}", 
            request.getPayableIds().size(), request.getPayingEntityId());

        List<PoboRequestResponse> results = new ArrayList<>();
        int successCount = 0;
        int failedCount = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;
        String currencyCode = "AED";

        for (UUID payableId : request.getPayableIds()) {
            try {
                Payable payable = payableRepository.findById(payableId)
                    .orElseThrow(() -> new RuntimeException("Payable not found: " + payableId));

                // Validate POBO eligibility
                if (!payable.canRequestPobo()) {
                    results.add(PoboRequestResponse.builder()
                        .payableId(payableId)
                        .payableNumber(payable.getPayableNumber())
                        .success(false)
                        .message("Payable not eligible for POBO: status=" + payable.getStatus())
                        .build());
                    failedCount++;
                    continue;
                }

                // Validate party is POBO-eligible
                if (payable.getPartyId() != null) {
                    Party party = partyRepository.findById(payable.getPartyId()).orElse(null);
                    if (party != null && !party.canReceivePoboPayment()) {
                        results.add(PoboRequestResponse.builder()
                            .payableId(payableId)
                            .payableNumber(payable.getPayableNumber())
                            .success(false)
                            .message("Vendor not eligible for POBO payments")
                            .build());
                        failedCount++;
                        continue;
                    }
                }

                // Request POBO
                payable.requestPobo(
                    request.getPayingEntityId(),
                    request.getPayingEntityCode(),
                    request.getRequestedBy()
                );

                payableRepository.save(payable);

                results.add(PoboRequestResponse.builder()
                    .payableId(payableId)
                    .payableNumber(payable.getPayableNumber())
                    .poboRequestStatus(payable.getPoboRequestStatus().name())
                    .requestedAt(payable.getPoboRequestedAt())
                    .success(true)
                    .message("POBO requested successfully")
                    .build());

                successCount++;
                totalAmount = totalAmount.add(payable.getNetAmount());
                currencyCode = payable.getCurrencyCode();

            } catch (Exception e) {
                log.error("Failed to request POBO for payable: {}", payableId, e);
                results.add(PoboRequestResponse.builder()
                    .payableId(payableId)
                    .success(false)
                    .message(e.getMessage())
                    .build());
                failedCount++;
            }
        }

        log.info("POBO request completed: {} success, {} failed", successCount, failedCount);

        return PoboBatchRequestResponse.builder()
            .results(results)
            .successCount(successCount)
            .failedCount(failedCount)
            .totalAmount(totalAmount)
            .currencyCode(currencyCode)
            .build();
    }

    /**
     * Treasury approves or rejects POBO request.
     */
    @Transactional
    public PayableResponse actionPoboRequest(PoboApprovalRequest request) {
        Payable payable = payableRepository.findById(request.getPayableId())
            .orElseThrow(() -> new RuntimeException("Payable not found: " + request.getPayableId()));

        if (payable.getPoboRequestStatus() != PoboRequestStatus.PENDING_TREASURY_APPROVAL) {
            throw new RuntimeException("Payable not pending POBO approval: " + payable.getPoboRequestStatus());
        }

        if (Boolean.TRUE.equals(request.getApproved())) {
            payable.approvePobo(request.getActionedBy());
            log.info("POBO approved for payable: {}", payable.getPayableNumber());
        } else {
            payable.rejectPobo(request.getActionedBy(), request.getRejectionReason());
            log.info("POBO rejected for payable: {}", payable.getPayableNumber());
        }

        Payable saved = payableRepository.save(payable);
        return mapToResponse(saved);
    }

    /**
     * Batch approve/reject POBO requests.
     */
    @Transactional
    public BatchOperationResponse batchActionPoboRequests(PoboBatchApprovalRequest request) {
        List<UUID> successIds = new ArrayList<>();
        List<BatchOperationError> errors = new ArrayList<>();

        for (UUID payableId : request.getPayableIds()) {
            try {
                PoboApprovalRequest approvalRequest = PoboApprovalRequest.builder()
                    .payableId(payableId)
                    .approved(request.getApproved())
                    .actionedBy(request.getActionedBy())
                    .rejectionReason(request.getRejectionReason())
                    .build();
                
                actionPoboRequest(approvalRequest);
                successIds.add(payableId);
            } catch (Exception e) {
                errors.add(BatchOperationError.builder()
                    .payableId(payableId)
                    .errorCode("POBO_ACTION_FAILED")
                    .errorMessage(e.getMessage())
                    .build());
            }
        }

        return BatchOperationResponse.builder()
            .successIds(successIds)
            .errors(errors)
            .successCount(successIds.size())
            .failedCount(errors.size())
            .build();
    }

    /**
     * Get POBO preview with charges and IHB loan estimate.
     */
    @Transactional(readOnly = true)
    public PoboPreviewResponse getPoboPreview(PoboPreviewRequest request) {
        List<Payable> payables = payableRepository.findAllById(request.getPayableIds());
        
        if (payables.isEmpty()) {
            throw new RuntimeException("No payables found");
        }

        BigDecimal totalAmount = payables.stream()
            .map(Payable::getNetAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        String currencyCode = payables.get(0).getCurrencyCode();

        // Calculate charges (simplified - would integrate with TaxChargeService)
        List<PoboChargeBreakdown> charges = new ArrayList<>();
        BigDecimal poboFee = totalAmount.multiply(new BigDecimal("0.005")).setScale(2, RoundingMode.HALF_UP);
        charges.add(PoboChargeBreakdown.builder()
            .chargeCode("POBO-FEE")
            .chargeName("POBO Service Fee")
            .chargeType("PERCENTAGE")
            .calculatedAmount(poboFee)
            .waived(false)
            .build());

        BigDecimal totalCharges = poboFee;
        BigDecimal netPayment = totalAmount.add(totalCharges);

        // IHB loan preview (simplified - would integrate with IhbUnifiedService)
        BigDecimal interestRate = new BigDecimal("5.5"); // Annual rate
        BigDecimal dailyInterest = totalAmount.multiply(interestRate)
            .divide(new BigDecimal("36500"), 4, RoundingMode.HALF_UP);
        BigDecimal monthlyInterest = dailyInterest.multiply(new BigDecimal("30")).setScale(2, RoundingMode.HALF_UP);

        PoboIhbLoanPreview ihbPreview = PoboIhbLoanPreview.builder()
            .principalAmount(totalAmount)
            .interestRate(interestRate)
            .estimatedDailyInterest(dailyInterest)
            .estimatedMonthlyInterest(monthlyInterest)
            .tenor("ON_DEMAND")
            .build();

        // Validation
        List<String> warnings = new ArrayList<>();
        if (totalAmount.compareTo(new BigDecimal("1000000")) > 0) {
            warnings.add("Large payment amount - additional approval may be required");
        }

        return PoboPreviewResponse.builder()
            .payingEntityId(request.getPayingEntityId())
            .payingEntityCode("TREASURY") // Would lookup from LegalEntity
            .payingEntityName("Corporate Treasury")
            .behalfEntityId(request.getBehalfEntityId())
            .behalfEntityCode(payables.get(0).getOwningEntityCode())
            .behalfEntityName(payables.get(0).getOwningEntityName())
            .payableCount(payables.size())
            .totalPaymentAmount(totalAmount)
            .currencyCode(currencyCode)
            .charges(charges)
            .totalCharges(totalCharges)
            .netPaymentAmount(netPayment)
            .ihbLoanPreview(ihbPreview)
            .warnings(warnings)
            .isValid(true)
            .build();
    }

    /**
     * Execute POBO payments.
     */
    @Transactional
    public PoboExecuteResponse executePobo(PoboExecuteRequest request) {
        log.info("Executing POBO for {} payables", request.getPayableIds().size());

        String batchRef = "POBO-" + System.currentTimeMillis();
        List<PoboExecutionResult> results = new ArrayList<>();
        int successCount = 0;
        int failedCount = 0;
        BigDecimal totalExecuted = BigDecimal.ZERO;
        String currencyCode = "AED";

        // In production, would create IHB loan and recharge here
        UUID ihbLoanId = UUID.randomUUID(); // Placeholder
        UUID rechargeId = UUID.randomUUID(); // Placeholder

        for (UUID payableId : request.getPayableIds()) {
            try {
                Payable payable = payableRepository.findById(payableId)
                    .orElseThrow(() -> new RuntimeException("Payable not found"));

                if (payable.getPoboRequestStatus() != PoboRequestStatus.APPROVED) {
                    throw new RuntimeException("Payable not approved for POBO");
                }

                String txnRef = batchRef + "-" + (successCount + 1);
                payable.markPoboExecuted(txnRef, ihbLoanId, rechargeId);
                payableRepository.save(payable);

                results.add(PoboExecutionResult.builder()
                    .payableId(payableId)
                    .payableNumber(payable.getPayableNumber())
                    .transactionRef(txnRef)
                    .amount(payable.getNetAmount())
                    .success(true)
                    .build());

                successCount++;
                totalExecuted = totalExecuted.add(payable.getNetAmount());
                currencyCode = payable.getCurrencyCode();

            } catch (Exception e) {
                log.error("POBO execution failed for payable: {}", payableId, e);
                results.add(PoboExecutionResult.builder()
                    .payableId(payableId)
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build());
                failedCount++;
            }
        }

        log.info("POBO execution completed: {} success, {} failed", successCount, failedCount);

        return PoboExecuteResponse.builder()
            .batchTransactionRef(batchRef)
            .results(results)
            .successCount(successCount)
            .failedCount(failedCount)
            .totalExecutedAmount(totalExecuted)
            .currencyCode(currencyCode)
            .ihbLoanId(ihbLoanId)
            .rechargeId(rechargeId)
            .executedAt(LocalDateTime.now())
            .build();
    }

    /**
     * Get POBO requests pending treasury approval.
     */
    @Transactional(readOnly = true)
    public List<PayableResponse> getPoboPendingApproval() {
        List<Payable> payables = payableRepository.findPoboPendingTreasuryApproval();
        return payables.stream().map(this::mapToResponse).collect(Collectors.toList());
    }

    // ========================================================================
    // PHASE 1: EXECUTE PAYMENT (DIRECT - Uses TransactionService.makePayment)
    // ========================================================================

    /**
     * Execute direct payment for a payable using the standard transaction flow.
     *
     * This follows the proper transaction architecture:
     * - Source VA → Settlement VA → Shadow VA → CBS (for real money movement)
     * - Proper 4-leg accounting
     * - Fee calculation and posting
     * - Shadow VA triggers CBS integration
     *
     * Flow:
     * 1. Validate payable is in APPROVED status
     * 2. Validate source VA exists
     * 3. Create PaymentRequest record for tracking
     * 4. Use TransactionService.makePayment for proper transaction flow
     * 5. Generate pain.001 XML for payment gateway
     * 6. Update payable status to PAID
     *
     * @param request Payment execution request
     * @return Response with transaction ID and pain.001 reference
     */
    @Transactional
    public PaymentExecutionResponse executePayment(PaymentExecutionRequest request) {
        log.info("Executing payment for payable: {}", request.getPayableId());

        Payable payable = payableRepository.findById(request.getPayableId())
            .orElseThrow(() -> new RuntimeException("Payable not found: " + request.getPayableId()));

        // Validate payable can be paid
        if (!payable.canBePaid()) {
            throw new RuntimeException("Payable cannot be paid. Status: " + payable.getStatus() +
                ", Payment Status: " + payable.getPaymentStatus());
        }

        // Validate source VA exists
        if (payable.getVirtualAccountId() == null) {
            throw new RuntimeException("Payable has no source Virtual Account assigned. " +
                "Please select a source account when creating the payable.");
        }

        VirtualAccount sourceVa = virtualAccountRepository.findById(payable.getVirtualAccountId())
            .orElseThrow(() -> new RuntimeException("Source VA not found: " + payable.getVirtualAccountId()));

        // Generate payment reference
        String paymentRef = "PMT-" + System.currentTimeMillis();
        String pain001MsgId = "PAIN001-" + paymentRef;

        // Mark payable as being processed
        payable.setStatus(PayableStatus.PROCESSING);
        UUID batchId = UUID.randomUUID();
        payable.setPaymentBatchId(batchId);
        payable.setPaymentChannel(request.getPaymentChannel() != null ?
            PaymentChannel.valueOf(request.getPaymentChannel()) : PaymentChannel.DIRECT);
        payable.setPaymentMethod(request.getPaymentMethod() != null ?
            PaymentMethod.valueOf(request.getPaymentMethod()) : PaymentMethod.BANK_TRANSFER);

        // 1. Create PaymentRequest record for tracking
        PaymentRequest paymentRequest = createPaymentRequest(payable, paymentRef, pain001MsgId, request);
        PaymentRequest savedPaymentRequest = paymentRequestRepository.save(paymentRequest);
        log.info("Created PaymentRequest: {} for payable: {}", savedPaymentRequest.getId(), payable.getPayableNumber());

        // 2. Execute payment via TransactionService
        // =====================================================================
        // ROUTING LOGIC:
        // - If source VA is an IHB Current Account (ihbParticipant=true):
        //   → Use POBO flow: IHB Current Account → Treasury Settlement VA → Shadow VA → CBS
        //   → This is Pay-On-Behalf-Of: Treasury pays on behalf of subsidiary
        // - Otherwise:
        //   → Use regular payment flow: Source VA → Settlement VA → Shadow VA → CBS
        // =====================================================================
        Transaction debitTxn;

        if (Boolean.TRUE.equals(sourceVa.getIhbParticipant())) {
            // IHB Current Account: Route through POBO flow
            log.info("Source VA {} is IHB participant - routing through POBO flow (6-leg: {})",
                sourceVa.getVaNumber(), sourceVa.isConfiguredFor6LegPobo());

            // Resolve Treasury's Settlement VA (the payer in POBO)
            UUID treasurySettlementVaId = sourceVa.getTreasuryPoolVaId();
            if (treasurySettlementVaId == null) {
                throw new RuntimeException("IHB Current Account " + sourceVa.getVaNumber() +
                    " has no Treasury Settlement VA configured. Cannot execute POBO payment.");
            }

            VirtualAccount treasurySettlementVa = virtualAccountRepository.findById(treasurySettlementVaId)
                .orElseThrow(() -> new RuntimeException("Treasury Settlement VA not found: " + treasurySettlementVaId));

            // Build POBO request
            TransactionDto.PoboPaymentRequest poboRequest = TransactionDto.PoboPaymentRequest.builder()
                .ownerVaId(sourceVa.getId())           // IHB Current Account (subsidiary bears cost)
                .payerVaId(treasurySettlementVa.getId()) // Treasury Settlement VA (makes payment)
                .amount(payable.getNetAmount())
                .valueDate(payable.getScheduledDate() != null ? payable.getScheduledDate() : LocalDate.now())
                .description("POBO Payment for " + (payable.getInvoiceNumber() != null ?
                    "Invoice: " + payable.getInvoiceNumber() : payable.getPayableNumber()))
                .channel(mapPaymentChannel(payable.getPaymentChannel()))
                .beneficiaryName(payable.getVendorName())
                .beneficiaryAccount(payable.getVendorAccount())
                .beneficiaryBankSwift(payable.getVendorBankCode())
                .beneficiaryBankName(payable.getVendorBank())
                .build();

            // Use 6-leg flow if configured, otherwise fall back to 4-leg
            if (sourceVa.isConfiguredFor6LegPobo()) {
                log.info("Using IHB 6-leg POBO flow (Mirror Account Model) for {}", sourceVa.getVaNumber());
                debitTxn = transactionService.makeIhb6LegPoboPayment(poboRequest);
            } else {
                log.info("Using standard 4-leg POBO flow for {} (6-leg not configured)", sourceVa.getVaNumber());
                debitTxn = transactionService.makePoboPayment(poboRequest);
            }

            // Update payable with POBO tracking
            payable.setPaymentRoute(PaymentRoute.POBO);
            payable.setPoboTransactionRef(debitTxn.getReferenceNumber());

            log.info("POBO payment executed: Owner={}, Payer={}, Amount={}, TxnRef={}, 6-leg={}",
                sourceVa.getVaNumber(), treasurySettlementVa.getVaNumber(),
                payable.getNetAmount(), debitTxn.getReferenceNumber(), sourceVa.isConfiguredFor6LegPobo());
        } else {
            // Regular payment flow
            TransactionDto.PaymentRequest paymentTxnRequest = TransactionDto.PaymentRequest.builder()
                .fromVaId(sourceVa.getId())
                .amount(payable.getNetAmount())
                .valueDate(payable.getScheduledDate() != null ? payable.getScheduledDate() : LocalDate.now())
                .description("Payment for " + (payable.getInvoiceNumber() != null ?
                    "Invoice: " + payable.getInvoiceNumber() : payable.getPayableNumber()))
                .channel(mapPaymentChannel(payable.getPaymentChannel()))
                .beneficiaryName(payable.getVendorName())
                .beneficiaryAccount(payable.getVendorAccount())
                .beneficiaryBankSwift(payable.getVendorBankCode())
                .beneficiaryBankName(payable.getVendorBank())
                .build();

            debitTxn = transactionService.makePayment(paymentTxnRequest);
        }
        log.info("Transaction created via TransactionService: {} for VA: {}",
            debitTxn.getReferenceNumber(), sourceVa.getVaNumber());

        // Link transaction to payment request
        savedPaymentRequest.setTransactionId(debitTxn.getId());
        paymentRequestRepository.save(savedPaymentRequest);

        // 3. Generate pain.001 XML
        String pain001Xml = generatePain001ForPayable(payable, pain001MsgId, request);

        // Update PaymentRequest with XML
        savedPaymentRequest.setPain001Xml(pain001Xml);
        savedPaymentRequest.setStatus(PaymentRequest.PaymentRequestStatus.SUBMITTED);
        paymentRequestRepository.save(savedPaymentRequest);

        // 4. Mark payable as paid
        payable.recordPayment(payable.getNetAmount());
        payable.setUpdatedBy(request.getExecutedBy());
        payableRepository.save(payable);

        // Reload source VA to get updated balance
        sourceVa = virtualAccountRepository.findById(sourceVa.getId()).orElse(sourceVa);

        log.info("Payment executed for payable: {} with ref: {}, transaction: {}",
            payable.getPayableNumber(), paymentRef, debitTxn.getId());

        return PaymentExecutionResponse.builder()
            .payableId(payable.getId())
            .payableNumber(payable.getPayableNumber())
            .paymentReference(debitTxn.getReferenceNumber())
            .paymentRequestId(savedPaymentRequest.getId())
            .transactionId(debitTxn.getId())
            .pain001MessageId(pain001MsgId)
            .pain001Xml(pain001Xml)
            .amount(payable.getNetAmount())
            .currencyCode(payable.getCurrencyCode())
            .sourceVaId(sourceVa.getId())
            .sourceVaNumber(sourceVa.getVaNumber())
            .balanceAfter(sourceVa.getCurrentBalance())
            .status("EXECUTED")
            .executedAt(LocalDateTime.now())
            .executedBy(request.getExecutedBy())
            .build();
    }

    /**
     * Map Payable PaymentChannel to TransactionService channel string.
     * PaymentChannel in Payable is: DIRECT, BATCH, SCHEDULED, IMMEDIATE
     * TransactionService expects: SWIFT, RTGS, SEPA, ACH, LOCAL_CLEARING
     */
    private String mapPaymentChannel(PaymentChannel channel) {
        if (channel == null) return "LOCAL_CLEARING";
        // Payable.PaymentChannel is about timing (DIRECT, BATCH, SCHEDULED, IMMEDIATE)
        // For actual payment routing, we default to LOCAL_CLEARING
        // In a full implementation, PaymentMethod (BANK_TRANSFER, SWIFT, etc.) would drive this
        switch (channel) {
            case DIRECT:
            case BATCH:
            case SCHEDULED:
            case IMMEDIATE:
            default:
                return "LOCAL_CLEARING";
        }
    }

    /**
     * Create a PaymentRequest entity for tracking the payment lifecycle.
     */
    private PaymentRequest createPaymentRequest(Payable payable, String paymentRef, String pain001MsgId,
                                                 PaymentExecutionRequest request) {
        return PaymentRequest.builder()
            .requestNumber(paymentRef)
            .payableId(payable.getId())
            .payableNumber(payable.getPayableNumber())
            .corporateId(payable.getCorporateId())
            .owningEntityId(payable.getOwningEntityId())
            .owningEntityCode(payable.getOwningEntityCode())
            .sourceVaId(payable.getVirtualAccountId())
            .beneficiaryName(payable.getVendorName())
            .beneficiaryAccount(payable.getVendorAccount())
            .beneficiaryBank(payable.getVendorBank())
            .beneficiaryBankCode(payable.getVendorBankCode())
            .amount(payable.getNetAmount())
            .currencyCode(payable.getCurrencyCode())
            .paymentChannel(request.getPaymentChannel() != null ? request.getPaymentChannel() : "BANK_TRANSFER")
            .paymentMethod(request.getPaymentMethod() != null ? request.getPaymentMethod() : "WIRE")
            .pain001MessageId(pain001MsgId)
            .remittanceInfo(payable.getInvoiceNumber() != null ?
                "Invoice: " + payable.getInvoiceNumber() : payable.getDescription())
            .requestedDate(LocalDate.now())
            .valueDate(payable.getScheduledDate() != null ? payable.getScheduledDate() : LocalDate.now())
            .priority(payable.getPaymentPriority() != null ? payable.getPaymentPriority().name() : "NORMAL")
            .status(PaymentRequest.PaymentRequestStatus.PENDING)
            .createdBy(request.getExecutedBy())
            .createdAt(LocalDateTime.now())
            .build();
    }

    /**
     * Execute batch payment for multiple payables.
     */
    @Transactional
    public BatchPaymentExecutionResponse executeBatchPayment(BatchPaymentExecutionRequest request) {
        log.info("Executing batch payment for {} payables", request.getPayableIds().size());

        String batchRef = "BATCH-" + System.currentTimeMillis();
        List<PaymentExecutionResponse> results = new ArrayList<>();
        int successCount = 0;
        int failedCount = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;
        String currencyCode = "AED";

        for (UUID payableId : request.getPayableIds()) {
            try {
                PaymentExecutionRequest execRequest = PaymentExecutionRequest.builder()
                    .payableId(payableId)
                    .paymentChannel(request.getPaymentChannel())
                    .paymentMethod(request.getPaymentMethod())
                    .executedBy(request.getExecutedBy())
                    .build();

                PaymentExecutionResponse result = executePayment(execRequest);
                results.add(result);
                successCount++;
                totalAmount = totalAmount.add(result.getAmount());
                currencyCode = result.getCurrencyCode();

            } catch (Exception e) {
                log.error("Failed to execute payment for payable: {}", payableId, e);
                results.add(PaymentExecutionResponse.builder()
                    .payableId(payableId)
                    .status("FAILED")
                    .errorMessage(e.getMessage())
                    .build());
                failedCount++;
            }
        }

        log.info("Batch payment completed: {} success, {} failed", successCount, failedCount);

        return BatchPaymentExecutionResponse.builder()
            .batchReference(batchRef)
            .results(results)
            .successCount(successCount)
            .failedCount(failedCount)
            .totalAmount(totalAmount)
            .currencyCode(currencyCode)
            .executedAt(LocalDateTime.now())
            .build();
    }

    /**
     * Generate pain.001 (CustomerCreditTransferInitiation) XML for a payable.
     * ISO 20022 pain.001.001.09 format.
     */
    private String generatePain001ForPayable(Payable payable, String msgId, PaymentExecutionRequest request) {
        LocalDateTime now = LocalDateTime.now();
        String creationDateTime = now.toString().replace("T", " ").substring(0, 19);

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.001.001.09\">\n");
        xml.append("  <CstmrCdtTrfInitn>\n");

        // Group Header (GrpHdr)
        xml.append("    <GrpHdr>\n");
        xml.append("      <MsgId>").append(msgId).append("</MsgId>\n");
        xml.append("      <CreDtTm>").append(creationDateTime).append("</CreDtTm>\n");
        xml.append("      <NbOfTxs>1</NbOfTxs>\n");
        xml.append("      <CtrlSum>").append(payable.getNetAmount().setScale(2, RoundingMode.HALF_UP)).append("</CtrlSum>\n");
        xml.append("      <InitgPty>\n");
        xml.append("        <Nm>").append(escapeXml(payable.getOwningEntityName() != null ?
            payable.getOwningEntityName() : "Corporate")).append("</Nm>\n");
        xml.append("        <Id>\n");
        xml.append("          <OrgId>\n");
        xml.append("            <Othr>\n");
        xml.append("              <Id>").append(payable.getOwningEntityCode() != null ?
            payable.getOwningEntityCode() : "CORP").append("</Id>\n");
        xml.append("            </Othr>\n");
        xml.append("          </OrgId>\n");
        xml.append("        </Id>\n");
        xml.append("      </InitgPty>\n");
        xml.append("    </GrpHdr>\n");

        // Payment Information (PmtInf)
        xml.append("    <PmtInf>\n");
        xml.append("      <PmtInfId>").append(payable.getPayableNumber()).append("</PmtInfId>\n");
        xml.append("      <PmtMtd>TRF</PmtMtd>\n"); // Transfer
        xml.append("      <BtchBookg>false</BtchBookg>\n");
        xml.append("      <NbOfTxs>1</NbOfTxs>\n");
        xml.append("      <CtrlSum>").append(payable.getNetAmount().setScale(2, RoundingMode.HALF_UP)).append("</CtrlSum>\n");

        // Payment Type Information
        xml.append("      <PmtTpInf>\n");
        xml.append("        <InstrPrty>").append(payable.getPaymentPriority() == PaymentPriority.HIGH ?
            "HIGH" : "NORM").append("</InstrPrty>\n");
        xml.append("        <SvcLvl>\n");
        xml.append("          <Cd>SEPA</Cd>\n");
        xml.append("        </SvcLvl>\n");
        xml.append("      </PmtTpInf>\n");

        // Requested Execution Date
        xml.append("      <ReqdExctnDt>\n");
        xml.append("        <Dt>").append(payable.getScheduledDate() != null ?
            payable.getScheduledDate() : LocalDate.now()).append("</Dt>\n");
        xml.append("      </ReqdExctnDt>\n");

        // Debtor (Payer)
        xml.append("      <Dbtr>\n");
        xml.append("        <Nm>").append(escapeXml(payable.getOwningEntityName() != null ?
            payable.getOwningEntityName() : "Corporate")).append("</Nm>\n");
        xml.append("      </Dbtr>\n");

        // Debtor Account (Source VA)
        xml.append("      <DbtrAcct>\n");
        xml.append("        <Id>\n");
        xml.append("          <Othr>\n");
        xml.append("            <Id>").append(payable.getVirtualAccountId() != null ?
            payable.getVirtualAccountId().toString() : "VA-DEFAULT").append("</Id>\n");
        xml.append("          </Othr>\n");
        xml.append("        </Id>\n");
        xml.append("        <Ccy>").append(payable.getCurrencyCode()).append("</Ccy>\n");
        xml.append("      </DbtrAcct>\n");

        // Debtor Agent (Bank)
        xml.append("      <DbtrAgt>\n");
        xml.append("        <FinInstnId>\n");
        xml.append("          <BICFI>BANKAEXX</BICFI>\n"); // Placeholder BIC
        xml.append("        </FinInstnId>\n");
        xml.append("      </DbtrAgt>\n");

        // Credit Transfer Transaction Information (CdtTrfTxInf)
        xml.append("      <CdtTrfTxInf>\n");

        // Payment ID
        xml.append("        <PmtId>\n");
        xml.append("          <InstrId>").append(payable.getPayableNumber()).append("-INSTR</InstrId>\n");
        xml.append("          <EndToEndId>").append(payable.getPayableNumber()).append("</EndToEndId>\n");
        xml.append("        </PmtId>\n");

        // Amount
        xml.append("        <Amt>\n");
        xml.append("          <InstdAmt Ccy=\"").append(payable.getCurrencyCode()).append("\">")
            .append(payable.getNetAmount().setScale(2, RoundingMode.HALF_UP)).append("</InstdAmt>\n");
        xml.append("        </Amt>\n");

        // Creditor Agent (Vendor's Bank)
        xml.append("        <CdtrAgt>\n");
        xml.append("        <FinInstnId>\n");
        if (payable.getVendorBankCode() != null && !payable.getVendorBankCode().isEmpty()) {
            xml.append("            <BICFI>").append(payable.getVendorBankCode()).append("</BICFI>\n");
        } else {
            xml.append("            <Nm>").append(escapeXml(payable.getVendorBank() != null ?
                payable.getVendorBank() : "Unknown Bank")).append("</Nm>\n");
        }
        xml.append("          </FinInstnId>\n");
        xml.append("        </CdtrAgt>\n");

        // Creditor (Vendor)
        xml.append("        <Cdtr>\n");
        xml.append("          <Nm>").append(escapeXml(payable.getVendorName() != null ?
            payable.getVendorName() : "Vendor")).append("</Nm>\n");
        xml.append("        </Cdtr>\n");

        // Creditor Account (Vendor's Account)
        xml.append("        <CdtrAcct>\n");
        xml.append("          <Id>\n");
        if (payable.getVendorAccount() != null && payable.getVendorAccount().length() > 15) {
            xml.append("            <IBAN>").append(payable.getVendorAccount()).append("</IBAN>\n");
        } else {
            xml.append("            <Othr>\n");
            xml.append("              <Id>").append(payable.getVendorAccount() != null ?
                payable.getVendorAccount() : "UNKNOWN").append("</Id>\n");
            xml.append("            </Othr>\n");
        }
        xml.append("          </Id>\n");
        xml.append("        </CdtrAcct>\n");

        // Remittance Information
        xml.append("        <RmtInf>\n");
        xml.append("          <Ustrd>").append(escapeXml(
            payable.getInvoiceNumber() != null ? "Invoice: " + payable.getInvoiceNumber() :
            (payable.getDescription() != null ? payable.getDescription() : "Payment")
        )).append("</Ustrd>\n");
        xml.append("        </RmtInf>\n");

        xml.append("      </CdtTrfTxInf>\n");
        xml.append("    </PmtInf>\n");
        xml.append("  </CstmrCdtTrfInitn>\n");
        xml.append("</Document>");

        return xml.toString();
    }

    /**
     * Escape XML special characters.
     */
    private String escapeXml(String input) {
        if (input == null) return "";
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }

    // ========================================================================
    // PHASE 2: INTERCOMPANY OPERATIONS
    // ========================================================================

    /**
     * Create an intercompany payable.
     */
    @Transactional
    public IntercompanyPayableResponse createIntercompanyPayable(CreateIntercompanyPayableRequest request) {
        log.info("Creating IC payable: {} -> {}", 
            request.getOwningEntityCode(), request.getCounterpartyEntityCode());

        Payable payable = Payable.createIntercompanyPayable(
            request.getCorporateId(),
            request.getOwningEntityId(),
            request.getOwningEntityCode(),
            request.getOwningEntityName(),
            request.getCounterpartyEntityId(),
            request.getCounterpartyEntityCode(),
            request.getCounterpartyEntityName(),
            request.getPartyId(),
            request.getDescription(),
            request.getAmount(),
            request.getCurrencyCode()
        );

        payable.setDueDate(request.getDueDate());
        payable.setExternalReference(request.getExternalReference());
        payable.setPayableNumber(generatePayableNumber(request.getCorporateId()));
        payable.setCreatedBy(request.getCreatedBy());

        // Link to related receivable if provided
        if (request.getRelatedReceivableId() != null) {
            payable.setCounterpartyReceivableId(request.getRelatedReceivableId());
        }

        Payable saved = payableRepository.save(payable);
        log.info("Created IC payable: {}", saved.getPayableNumber());

        return mapToIcResponse(saved);
    }

    /**
     * Get intercompany payables for an entity.
     */
    @Transactional(readOnly = true)
    public List<IntercompanyPayableResponse> getIntercompanyPayablesByEntity(UUID owningEntityId) {
        List<Payable> payables = payableRepository.findByOwningEntityIdAndIsIntercompanyTrue(owningEntityId);
        return payables.stream().map(this::mapToIcResponse).collect(Collectors.toList());
    }

    /**
     * Get intercompany position between two entities.
     */
    @Transactional(readOnly = true)
    public IntercompanyPositionResponse getIntercompanyPosition(UUID entityId, UUID counterpartyId) {
        BigDecimal payablesTotal = payableRepository.sumIntercompanyExposure(entityId, counterpartyId);
        // Would also get receivables from ReceivableRepository
        BigDecimal receivablesTotal = BigDecimal.ZERO; // Placeholder

        return IntercompanyPositionResponse.builder()
            .entityId(entityId)
            .counterpartyEntityId(counterpartyId)
            .payablesTotal(payablesTotal)
            .receivablesTotal(receivablesTotal)
            .netPosition(payablesTotal.subtract(receivablesTotal))
            .currencyCode("AED")
            .build();
    }

    // ========================================================================
    // PHASE 2: NETTING INTEGRATION
    // ========================================================================

    /**
     * Get payables eligible for netting.
     */
    @Transactional(readOnly = true)
    public NettingEligiblePayablesResponse getNettingEligiblePayables(UUID owningEntityId) {
        List<Payable> payables;
        if (owningEntityId != null) {
            payables = payableRepository.findNettingEligibleByOwningEntity(owningEntityId);
        } else {
            payables = payableRepository.findNettingEligibleNotInCycle();
        }

        BigDecimal totalAmount = payables.stream()
            .map(Payable::getNetAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Group by owning entity
        java.util.Map<String, BigDecimal> byOwningEntity = payables.stream()
            .collect(Collectors.groupingBy(
                p -> p.getOwningEntityCode() != null ? p.getOwningEntityCode() : "UNKNOWN",
                Collectors.reducing(BigDecimal.ZERO, Payable::getNetAmount, BigDecimal::add)
            ));

        // Group by counterparty
        java.util.Map<String, BigDecimal> byCounterparty = payables.stream()
            .filter(p -> p.getCounterpartyEntityCode() != null)
            .collect(Collectors.groupingBy(
                Payable::getCounterpartyEntityCode,
                Collectors.reducing(BigDecimal.ZERO, Payable::getNetAmount, BigDecimal::add)
            ));

        return NettingEligiblePayablesResponse.builder()
            .payables(payables.stream().map(this::mapToResponse).collect(Collectors.toList()))
            .totalAmount(totalAmount)
            .currencyCode("AED")
            .count(payables.size())
            .byOwningEntity(byOwningEntity)
            .byCounterparty(byCounterparty)
            .build();
    }

    /**
     * Add payables to a netting cycle.
     */
    @Transactional
    public AddToNettingResponse addToNettingCycle(AddToNettingRequest request) {
        log.info("Adding {} payables to netting cycle: {}", 
            request.getPayableIds().size(), request.getNettingCycleId());

        List<NettingAddResult> results = new ArrayList<>();
        int successCount = 0;
        int failedCount = 0;
        BigDecimal totalAdded = BigDecimal.ZERO;

        for (UUID payableId : request.getPayableIds()) {
            try {
                Payable payable = payableRepository.findById(payableId)
                    .orElseThrow(() -> new RuntimeException("Payable not found"));

                if (!payable.canAddToNetting()) {
                    throw new RuntimeException("Payable not eligible for netting");
                }

                UUID entryId = UUID.randomUUID(); // Would come from NettingService
                String cycleRef = "NET-" + request.getNettingCycleId().toString().substring(0, 8);
                
                payable.addToNettingCycle(request.getNettingCycleId(), cycleRef, entryId);
                payableRepository.save(payable);

                results.add(NettingAddResult.builder()
                    .payableId(payableId)
                    .payableNumber(payable.getPayableNumber())
                    .nettingEntryId(entryId)
                    .success(true)
                    .build());

                successCount++;
                totalAdded = totalAdded.add(payable.getNetAmount());

            } catch (Exception e) {
                log.error("Failed to add payable to netting: {}", payableId, e);
                results.add(NettingAddResult.builder()
                    .payableId(payableId)
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build());
                failedCount++;
            }
        }

        return AddToNettingResponse.builder()
            .nettingCycleId(request.getNettingCycleId())
            .nettingCycleRef("NET-" + request.getNettingCycleId().toString().substring(0, 8))
            .results(results)
            .successCount(successCount)
            .failedCount(failedCount)
            .totalAmountAdded(totalAdded)
            .build();
    }

    /**
     * Remove payables from netting cycle.
     */
    @Transactional
    public int removeFromNettingCycle(UUID nettingCycleId) {
        return payableRepository.removeFromNettingCycle(nettingCycleId);
    }

    /**
     * Mark payables as settled via netting.
     */
    @Transactional
    public int markNettingSettled(UUID nettingCycleId, String settlementRef) {
        return payableRepository.markNettingSettled(nettingCycleId, settlementRef, LocalDateTime.now());
    }

    // ========================================================================
    // APPROVAL WORKFLOW
    // ========================================================================

    /**
     * Submit a draft payable for approval.
     */
    @Transactional
    public PayableResponse submitForApproval(UUID payableId, String submittedBy) {
        log.info("Submitting payable {} for approval by {}", payableId, submittedBy);

        Payable payable = payableRepository.findById(payableId)
            .orElseThrow(() -> new RuntimeException("Payable not found: " + payableId));

        if (payable.getStatus() != PayableStatus.DRAFT) {
            throw new RuntimeException("Only draft payables can be submitted for approval. Current status: " + payable.getStatus());
        }

        payable.setStatus(PayableStatus.PENDING_APPROVAL);
        payable.setUpdatedBy(submittedBy);
        payable.setUpdatedAt(LocalDateTime.now());

        Payable saved = payableRepository.save(payable);
        log.info("Payable {} submitted for approval", saved.getPayableNumber());

        return mapToResponse(saved);
    }

    /**
     * Approve a pending payable.
     */
    @Transactional
    public PayableResponse approvePayable(UUID payableId, String approvedBy, String notes) {
        log.info("Approving payable {} by {}", payableId, approvedBy);

        Payable payable = payableRepository.findById(payableId)
            .orElseThrow(() -> new RuntimeException("Payable not found: " + payableId));

        if (payable.getStatus() != PayableStatus.PENDING_APPROVAL) {
            throw new RuntimeException("Only pending payables can be approved. Current status: " + payable.getStatus());
        }

        payable.setStatus(PayableStatus.APPROVED);
        payable.setApprovedBy(approvedBy);
        payable.setApprovedAt(LocalDateTime.now());
        if (notes != null && !notes.isEmpty()) {
            payable.setNotes(notes);
        }
        payable.setUpdatedBy(approvedBy);
        payable.setUpdatedAt(LocalDateTime.now());

        Payable saved = payableRepository.save(payable);
        log.info("Payable {} approved by {}", saved.getPayableNumber(), approvedBy);

        return mapToResponse(saved);
    }

    /**
     * Reject a pending payable.
     */
    @Transactional
    public PayableResponse rejectPayable(UUID payableId, String rejectedBy, String rejectionReason) {
        log.info("Rejecting payable {} by {} - reason: {}", payableId, rejectedBy, rejectionReason);

        Payable payable = payableRepository.findById(payableId)
            .orElseThrow(() -> new RuntimeException("Payable not found: " + payableId));

        if (payable.getStatus() != PayableStatus.PENDING_APPROVAL) {
            throw new RuntimeException("Only pending payables can be rejected. Current status: " + payable.getStatus());
        }

        payable.setStatus(PayableStatus.REJECTED);
        payable.setRejectionReason(rejectionReason);
        payable.setUpdatedBy(rejectedBy);
        payable.setUpdatedAt(LocalDateTime.now());

        Payable saved = payableRepository.save(payable);
        log.info("Payable {} rejected by {}", saved.getPayableNumber(), rejectedBy);

        return mapToResponse(saved);
    }

    /**
     * Schedule a payment for an approved payable.
     */
    @Transactional
    public PayableResponse schedulePayment(UUID payableId, LocalDate scheduledDate, String paymentPriority, String paymentMethod, String scheduledBy) {
        log.info("Scheduling payment for payable {} on {} by {}", payableId, scheduledDate, scheduledBy);

        Payable payable = payableRepository.findById(payableId)
            .orElseThrow(() -> new RuntimeException("Payable not found: " + payableId));

        if (payable.getStatus() != PayableStatus.APPROVED) {
            throw new RuntimeException("Only approved payables can be scheduled. Current status: " + payable.getStatus());
        }

        payable.setStatus(PayableStatus.SCHEDULED);
        payable.setScheduledDate(scheduledDate);
        if (paymentPriority != null) {
            payable.setPaymentPriority(PaymentPriority.valueOf(paymentPriority));
        }
        if (paymentMethod != null) {
            payable.setPaymentMethod(PaymentMethod.valueOf(paymentMethod));
        }
        payable.setUpdatedBy(scheduledBy);
        payable.setUpdatedAt(LocalDateTime.now());

        Payable saved = payableRepository.save(payable);
        log.info("Payable {} scheduled for payment on {}", saved.getPayableNumber(), scheduledDate);

        return mapToResponse(saved);
    }

    // ========================================================================
    // STATISTICS
    // ========================================================================

    @Transactional(readOnly = true)
    public PayableStatsResponse getStats(UUID corporateId) {
        // This would use the repository stats methods
        // Simplified implementation
        List<Payable> payables = payableRepository.findByCorporateIdOrderByCreatedAtDesc(corporateId);

        long totalCount = payables.size();
        long pendingCount = payables.stream().filter(p -> 
            p.getStatus() == PayableStatus.PENDING_APPROVAL || 
            p.getStatus() == PayableStatus.PENDING_POBO).count();
        long approvedCount = payables.stream().filter(p -> 
            p.getStatus() == PayableStatus.APPROVED || 
            p.getStatus() == PayableStatus.POBO_APPROVED).count();
        long paidCount = payables.stream().filter(p -> 
            p.getStatus() == PayableStatus.PAID || 
            p.getStatus() == PayableStatus.NETTED).count();
        long overdueCount = payables.stream().filter(Payable::isOverdue).count();
        long intercompanyCount = payables.stream().filter(p -> 
            Boolean.TRUE.equals(p.getIsIntercompany())).count();
        long poboCount = payables.stream().filter(p -> 
            p.getPaymentRoute() == PaymentRoute.POBO).count();

        BigDecimal totalAmount = payables.stream()
            .map(Payable::getNetAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal outstandingAmount = payables.stream()
            .filter(p -> p.getStatus() != PayableStatus.PAID && 
                        p.getStatus() != PayableStatus.CANCELLED &&
                        p.getStatus() != PayableStatus.NETTED)
            .map(Payable::getOutstandingAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        return PayableStatsResponse.builder()
            .totalCount(totalCount)
            .pendingCount(pendingCount)
            .approvedCount(approvedCount)
            .paidCount(paidCount)
            .overdueCount(overdueCount)
            .intercompanyCount(intercompanyCount)
            .poboRequestedCount(poboCount)
            .totalAmount(totalAmount)
            .outstandingAmount(outstandingAmount)
            .currencyCode("AED")
            .build();
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private String generatePayableNumber(UUID corporateId) {
        return "PAY-" + System.currentTimeMillis() + "-" + 
            corporateId.toString().substring(0, 4).toUpperCase();
    }

    private void enrichFromParty(Payable payable, UUID partyId) {
        partyRepository.findById(partyId).ifPresent(party -> {
            if (payable.getVendorName() == null) {
                payable.setVendorName(party.getLegalName());
            }
            payable.setVendorId(party.getId());
            
            // Set netting eligibility from party
            if (party.canParticipateInNetting()) {
                payable.setNettingEligible(true);
            }
            
            // Set intercompany from party
            if (party.isGroupEntity()) {
                payable.setIsIntercompany(true);
                payable.setCounterpartyEntityId(party.getLinkedLegalEntityId());
                payable.setCounterpartyEntityCode(party.getLinkedLegalEntityCode());
                payable.setNettingEligible(true);
                payable.setPaymentRoute(PaymentRoute.INTERCOMPANY);
            }
        });
    }

    private PayableResponse mapToResponse(Payable p) {
        return PayableResponse.builder()
            .id(p.getId())
            .payableNumber(p.getPayableNumber())
            .externalReference(p.getExternalReference())
            .invoiceNumber(p.getInvoiceNumber())
            .payableType(p.getPayableType() != null ? p.getPayableType().name() : null)
            .corporateId(p.getCorporateId())
            .programId(p.getProgramId())
            .virtualAccountId(p.getVirtualAccountId())
            // Entity context
            .owningEntityId(p.getOwningEntityId())
            .owningEntityCode(p.getOwningEntityCode())
            .owningEntityName(p.getOwningEntityName())
            // Party
            .partyId(p.getPartyId())
            .partyBankAccountId(p.getPartyBankAccountId())
            // Vendor
            .vendorId(p.getVendorId())
            .vendorName(p.getVendorName())
            .vendorAccount(p.getVendorAccount())
            .vendorBank(p.getVendorBank())
            // Amounts
            .currencyCode(p.getCurrencyCode())
            .grossAmount(p.getGrossAmount())
            .discountAmount(p.getDiscountAmount())
            .taxAmount(p.getTaxAmount())
            .withholdingTax(p.getWithholdingTax())
            .netAmount(p.getNetAmount())
            .paidAmount(p.getPaidAmount())
            .outstandingAmount(p.getOutstandingAmount())
            // Dates
            .invoiceDate(p.getInvoiceDate())
            .receivedDate(p.getReceivedDate())
            .dueDate(p.getDueDate())
            .paymentTermsDays(p.getPaymentTermsDays())
            .daysUntilDue(p.getDaysUntilDue())
            .agingBucket(p.getAgingBucket())
            // Status
            .status(p.getStatus() != null ? p.getStatus().name() : null)
            .paymentStatus(p.getPaymentStatus() != null ? p.getPaymentStatus().name() : null)
            .isOverdue(p.isOverdue())
            // Payment route
            .paymentRoute(p.getPaymentRoute() != null ? p.getPaymentRoute().name() : null)
            .paymentViaEntityId(p.getPaymentViaEntityId())
            .paymentViaEntityCode(p.getPaymentViaEntityCode())
            .paymentRouteDescription(p.getPaymentRouteDescription())
            // POBO
            .poboRequestId(p.getPoboRequestId())
            .poboRequestStatus(p.getPoboRequestStatus() != null ? p.getPoboRequestStatus().name() : null)
            .poboTransactionRef(p.getPoboTransactionRef())
            .poboIhbLoanId(p.getPoboIhbLoanId())
            .poboRechargeId(p.getPoboRechargeId())
            .poboRequestedAt(p.getPoboRequestedAt())
            .poboRequestedBy(p.getPoboRequestedBy())
            .poboActionedAt(p.getPoboActionedAt())
            .poboActionedBy(p.getPoboActionedBy())
            // Intercompany
            .isIntercompany(p.getIsIntercompany())
            .counterpartyEntityId(p.getCounterpartyEntityId())
            .counterpartyEntityCode(p.getCounterpartyEntityCode())
            .counterpartyEntityName(p.getCounterpartyEntityName())
            .counterpartyReceivableId(p.getCounterpartyReceivableId())
            // Netting
            .nettingEligible(p.getNettingEligible())
            .nettingCycleId(p.getNettingCycleId())
            .nettingCycleRef(p.getNettingCycleRef())
            .nettingEntryId(p.getNettingEntryId())
            .nettingStatus(p.getNettingStatus() != null ? p.getNettingStatus().name() : null)
            .nettingSettlementRef(p.getNettingSettlementRef())
            .nettingSettledAt(p.getNettingSettledAt())
            // Approval
            .approvalRequired(p.getApprovalRequired())
            .approvalLevel(p.getApprovalLevel())
            .approvedBy(p.getApprovedBy())
            .approvedAt(p.getApprovedAt())
            .rejectionReason(p.getRejectionReason())
            // Scheduling
            .scheduledDate(p.getScheduledDate())
            .paymentPriority(p.getPaymentPriority() != null ? p.getPaymentPriority().name() : null)
            .paymentMethod(p.getPaymentMethod() != null ? p.getPaymentMethod().name() : null)
            .paymentChannel(p.getPaymentChannel() != null ? p.getPaymentChannel().name() : null)
            .paymentBatchId(p.getPaymentBatchId())
            // Hierarchy
            .hierarchyNodeId(p.getHierarchyNodeId())
            .hierarchyPath(p.getHierarchyPath())
            // Metadata
            .description(p.getDescription())
            .notes(p.getNotes())
            .hasInvoiceDocument(p.getHasInvoiceDocument())
            .documentCount(p.getDocumentCount())
            // Audit
            .createdBy(p.getCreatedBy())
            .createdAt(p.getCreatedAt())
            .updatedBy(p.getUpdatedBy())
            .updatedAt(p.getUpdatedAt())
            // Computed flags
            .canBePaid(p.canBePaid())
            .canRequestPobo(p.canRequestPobo())
            .canAddToNetting(p.canAddToNetting())
            .build();
    }

    private IntercompanyPayableResponse mapToIcResponse(Payable p) {
        return IntercompanyPayableResponse.builder()
            .id(p.getId())
            .payableNumber(p.getPayableNumber())
            .owningEntityId(p.getOwningEntityId())
            .owningEntityCode(p.getOwningEntityCode())
            .owningEntityName(p.getOwningEntityName())
            .counterpartyEntityId(p.getCounterpartyEntityId())
            .counterpartyEntityCode(p.getCounterpartyEntityCode())
            .counterpartyEntityName(p.getCounterpartyEntityName())
            .amount(p.getNetAmount())
            .currencyCode(p.getCurrencyCode())
            .dueDate(p.getDueDate())
            .status(p.getStatus() != null ? p.getStatus().name() : null)
            .description(p.getDescription())
            .counterpartyReceivableId(p.getCounterpartyReceivableId())
            .nettingStatus(p.getNettingStatus() != null ? p.getNettingStatus().name() : null)
            .nettingCycleId(p.getNettingCycleId())
            .nettingCycleRef(p.getNettingCycleRef())
            .createdAt(p.getCreatedAt())
            .build();
    }

    private PayableListResponse mapToListResponse(Page<Payable> page) {
        return PayableListResponse.builder()
            .payables(page.getContent().stream().map(this::mapToResponse).collect(Collectors.toList()))
            .page(page.getNumber())
            .size(page.getSize())
            .totalElements(page.getTotalElements())
            .totalPages(page.getTotalPages())
            .hasNext(page.hasNext())
            .hasPrevious(page.hasPrevious())
            .build();
    }
}