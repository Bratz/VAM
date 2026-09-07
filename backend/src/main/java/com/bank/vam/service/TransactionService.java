package com.bank.vam.service;

import com.bank.vam.dto.TransactionDto;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.Transaction.TransactionCategory;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.AccountCategory;
import com.bank.vam.entity.VirtualAccount.VaStatus;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.payables.Payable;
import com.bank.vam.entity.tax.ChargeConfiguration;
import com.bank.vam.entity.treasury.ExceptionTransaction;
import com.bank.vam.entity.intercompany.IntercompanyTransaction;
import com.bank.vam.entity.pobo.IntercompanyRecharge;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.exception.SettlementVaException;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.payables.PayableRepository;
import com.bank.vam.repository.tax.ChargeConfigurationRepository;
import com.bank.vam.repository.intercompany.IntercompanyTransactionRepository;
import com.bank.vam.repository.pobo.IntercompanyRechargeRepository;
import com.bank.vam.service.treasury.BalanceAggregationServiceEnhanced;
import com.bank.vam.service.treasury.FeePostingService;
import com.bank.vam.service.treasury.FundsAvailabilityService;
import com.bank.vam.service.treasury.FundsAvailabilityService.FundsCheckResult;
import com.bank.vam.service.treasury.SettlementVaResolverService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.data.domain.Sort;

/**
 * TransactionService - Pure Virtual Architecture v5.3
 *
 * OVERVIEW:
 * =========
 * Handles all transaction operations including:
 * - VA to VA transfers (same program, cross program, intercompany)
 * - Outbound payments via Shadow VA to CBS
 * - Inbound collections via Shadow VA from CBS (NEW in v5.3)
 * - POBO (Pay-On-Behalf-Of) operations
 * - ROBO (Receive-On-Behalf-Of) via VIBAN routing
 * - Fee calculation and posting to Settlement VA
 *
 * KEY FEATURES (v5.3):
 * ====================
 * 1. SHADOW VA SUPPORT FOR OUTBOUND PAYMENTS
 *    - Outbound payments: Source VA → Settlement VA → Shadow VA → CBS
 *    - Shadow VA mirrors Physical Account balance
 *    - Links to CBS integration for real money movement
 *
 * 2. SHADOW VA SUPPORT FOR INBOUND COLLECTIONS (NEW v5.3)
 *    - Inbound collections: CBS → Shadow VA → Settlement VA → Target VA
 *    - Symmetric opposite of outbound payment flow
 *    - Shadow VA receives CBS funds first (mirrors physical account)
 *    - Proper 4-leg accounting for reconciliation
 *
 * 3. FEE INCOME TO SETTLEMENT VA (v5.1 behavior preserved)
 *    - Fees go to Settlement VA (NOT Treasury Center VA)
 *    - Proper internal cost allocation via Settlement VA
 *    - Consolidated fee tracking per program
 *
 * 4. POBO (PAY-ON-BEHALF-OF) SUPPORT
 *    - Treasury Center pays on behalf of subsidiaries
 *    - Uses designatedPayerVaId for routing
 *    - Proper audit trail with behalfOfEntity tracking
 *
 * 5. DYNAMIC FEE CALCULATION
 *    - All fees loaded from ChargeConfiguration
 *    - No hardcoded fee amounts
 *    - Supports waiver logic for intercompany
 *    - VIBAN collections get lower fee rates
 *
 * ACCOUNTING FLOWS (v5.3.1 - Corrected):
 * ======================================
 *
 * INTERNAL TRANSFER (VA to VA via Settlement):
 * | Entry | Account        | Debit  | Credit | Balance | Type         |
 * |-------|----------------|--------|--------|---------|--------------|
 * | 1     | Source VA      | 10,000 |        | -10,000 | TRANSFER_OUT |
 * | 2     | Settlement VA  |        | 10,000 | +10,000 | TRANSFER_IN  |
 * | 3     | Settlement VA  | 10,000 |        | -10,000 | TRANSFER_OUT |
 * | 4     | Destination VA |        | 10,000 | +10,000 | TRANSFER_IN  |
 * Totals: Debits=20,000 Credits=20,000 ✓ BALANCED
 * Settlement VA: +10,000 -10,000 = 0 (pass-through)
 *
 * CFO VIEW (excludes Settlement VA):
 * | Account        | Net Effect |
 * |----------------|------------|
 * | Source VA      | -10,000    |
 * | Destination VA | +10,000    |
 *
 * OUTBOUND PAYMENT (VA to External via Shadow):
 * | Entry | Account       | Debit  | Credit | Balance | Type   |
 * |-------|---------------|--------|--------|---------|--------|
 * | 1     | Source VA     | 10,000 |        | -10,000 | DEBIT  |
 * | 2     | Settlement VA |        | 10,000 | +10,000 | CREDIT |
 * | 3     | Settlement VA | 10,000 |        | -10,000 | DEBIT  |
 * | 4     | Shadow VA     | 10,000 |        | -10,000 | DEBIT  |
 * Note: Shadow VA DEBIT reflects money leaving physical account to external world
 * Totals: Debits=30,000 Credits=10,000 (imbalanced internally - external recipient gets 10,000)
 * Settlement VA: +10,000 -10,000 = 0 (pass-through)
 *
 * CFO VIEW (excludes Shadow VA & Settlement VA):
 * | Account   | Net Effect |
 * |-----------|------------|
 * | Source VA | -10,000    |
 *
 * INBOUND COLLECTION (External to VA via Shadow) - v5.3:
 * | Entry | Account       | Debit | Credit | Balance | Type         |
 * |-------|---------------|-------|--------|---------|--------------|
 * | 1     | Shadow VA     |       | 10,000 | +10,000 | ROBO_CREDIT  |
 * | 2     | Settlement VA |       | 10,000 | +10,000 | TRANSFER_IN  |
 * | 3     | Settlement VA |10,000 |        | -10,000 | TRANSFER_OUT |
 * | 4     | Target VA     |       |  9,975 | +9,975  | ROBO_CREDIT  |
 * | 5     | Target VA     |    25 |        |    -25  | FEE          |
 * | 6     | Settlement VA |       |     25 |    +25  | FEE_CREDIT   |
 * Note: Shadow VA CREDIT reflects money entering physical account from external world
 * Totals: Debits=10,025 Credits=30,000 (imbalanced internally - external sender provides 10,000)
 * Settlement VA: +10,000 -10,000 +25 = +25 (fee income)
 *
 * CFO VIEW (excludes Shadow VA & Settlement VA):
 * | Account   | Net Effect |
 * |-----------|------------|
 * | Target VA | +9,950     | (received 9,975, paid 25 fee)
 *
 * POBO PAYMENT (Treasury pays for Subsidiary):
 * | Entry | Account       | Debit  | Credit | Balance | Type         |
 * |-------|---------------|--------|--------|---------|--------------|
 * | 1     | Subsidiary VA | 10,000 |        | -10,000 | POBO_DEBIT   |
 * | 2     | Treasury VA   |        | 10,000 | +10,000 | TRANSFER_IN  |
 * | 3     | Treasury VA   | 10,000 |        | -10,000 | POBO_DEBIT   |
 * | 4     | Shadow VA     | 10,000 |        | -10,000 | DEBIT        |
 * behalfOfEntity = Subsidiary's Legal Entity
 *
 * CFO VIEW:
 * | Account       | Net Effect |
 * |---------------|------------|
 * | Subsidiary VA | -10,000    | (cost borne by subsidiary)
 * | Treasury VA   |     0      | (pass-through payer)
 *
 * FEES (all scenarios):
 * | Entry | Account       | Debit | Credit | Type       |
 * |-------|---------------|-------|--------|------------|
 * | 1     | Source VA     | fee   |        | FEE        |
 * | 2     | Settlement VA |       | fee    | FEE_CREDIT |
 * Fees stay with Settlement VA for consolidated tracking
 *
 * IMPORTANT - CFO VIEW vs AUDIT TRAIL:
 * ====================================
 * - CFO VIEW (default): Only shows Operating VA entries (business impact)
 * - AUDIT TRAIL (includeInternal=true): Shows all entries including Shadow VA & Settlement VA
 * - Shadow VA entries are EXCLUDED by default (omnibus account for CBS reconciliation)
 * - Settlement VA entries are EXCLUDED by default (internal clearing, nets to zero)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final VirtualAccountRepository virtualAccountRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final ChargeConfigurationRepository chargeConfigRepository;
    private final PayableRepository payableRepository;
    private final FeePostingService feePostingService;
    private final FundsAvailabilityService fundsAvailabilityService;
    private final SettlementVaResolverService settlementVaResolver;
    private final BalanceAggregationServiceEnhanced balanceAggregationService;
    private final ObjectMapper objectMapper;
    private final IntercompanyTransactionRepository intercompanyTransactionRepository;
    private final IntercompanyRechargeRepository intercompanyRechargeRepository;

    // ========================================================================
    // CHARGE CODES (loaded from ChargeConfiguration - no hardcoded values)
    // ========================================================================
    
    // Transfer fees
    private static final String CHARGE_SAME_PROGRAM_TRANSFER = "SAME_PROGRAM_TRANSFER_FEE";
    private static final String CHARGE_CROSS_PROGRAM_TRANSFER = "CROSS_PROGRAM_TRANSFER_FEE";
    private static final String CHARGE_INTERCOMPANY_TRANSFER = "INTERCOMPANY_TRANSFER_FEE";
    private static final String CHARGE_CROSS_BORDER = "CROSS_BORDER_INTERCOMPANY_FEE";
    private static final String CHARGE_FX_CONVERSION = "FX_CONVERSION_FEE";
    private static final String CHARGE_TREASURY_SERVICE = "TREASURY_SERVICE_FEE";
    
    // Payment fees
    private static final String CHARGE_OUTBOUND_PAYMENT = "OUTBOUND_PAYMENT_FEE";
    private static final String CHARGE_SWIFT_PAYMENT = "SWIFT_PAYMENT_FEE";
    private static final String CHARGE_RTGS_PAYMENT = "RTGS_PAYMENT_FEE";
    private static final String CHARGE_SEPA_PAYMENT = "SEPA_PAYMENT_FEE";
    
    // POBO fees
    private static final String CHARGE_POBO_FEE = "POBO_FEE";

    // Collection fees (inbound)
    private static final String CHARGE_INBOUND_COLLECTION = "INBOUND_COLLECTION_FEE";
    private static final String CHARGE_VIBAN_COLLECTION = "VIBAN_COLLECTION_FEE";
    private static final String CHARGE_ROBO_COLLECTION = "ROBO_COLLECTION_FEE";

    // ========================================================================
    // READ OPERATIONS
    // ========================================================================

    @Transactional(readOnly = true)
    public Transaction getById(UUID id) {
        return transactionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + id));
    }

    @Transactional(readOnly = true)
    public Transaction getByReference(String referenceNumber) {
        return transactionRepository.findByReferenceNumber(referenceNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + referenceNumber));
    }

    @Transactional(readOnly = true)
    public Page<Transaction> getByVaId(UUID vaId, Pageable pageable) {
        return transactionRepository.findByVaId(vaId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Transaction> getByCorporateId(UUID corporateId, Pageable pageable) {
        return transactionRepository.findByCorporateId(corporateId, pageable);
    }

    @Transactional(readOnly = true)
    public List<Transaction> getRecentByVaId(UUID vaId, int limit) {
        return transactionRepository.findRecentByVaId(vaId, PageRequest.of(0, limit)).getContent();
    }

    @Transactional(readOnly = true)
    public List<Transaction> getByCorrelationId(String correlationId) {
        // findByCorrelationId returns Optional, so we convert to a list
        // For full correlation chain, we need to search by correlationId field
        return transactionRepository.findAllByCorrelationId(correlationId);
    }


    // ============================================================================
// ADD THESE METHODS TO TransactionService.java
// Location: Add after getByCorrelationId method (around line 178)
// ============================================================================


    // ========================================================================
    // LIST & SEARCH OPERATIONS (NEW - REQUIRED FOR FRONTEND)
    // ========================================================================

    /**
     * Get all transactions with pagination.
     * Used by TransactionController for GET /transactions without filters.
     */
    @Transactional(readOnly = true)
    public Page<Transaction> getAll(Pageable pageable) {
        log.debug("Getting all transactions, page={}, size={}", pageable.getPageNumber(), pageable.getPageSize());
        return transactionRepository.findAll(pageable);
    }

    /**
     * Get transactions by movement type.
     * Used by TransactionController for GET /transactions?movementType=POBO_DEBIT
     */
    @Transactional(readOnly = true)
    public Page<Transaction> getByMovementType(Transaction.MovementType movementType, Pageable pageable) {
        log.debug("Getting transactions by movementType={}, page={}", movementType, pageable.getPageNumber());
        return transactionRepository.findByMovementType(movementType, pageable);
    }

    /**
     * Get transactions by status.
     * Used by TransactionController for GET /transactions?status=PENDING
     */
    @Transactional(readOnly = true)
    public Page<Transaction> getByStatus(Transaction.TransactionStatus status, Pageable pageable) {
        log.debug("Getting transactions by status={}, page={}", status, pageable.getPageNumber());
        return transactionRepository.findByStatus(status, pageable);
    }

    /**
     * Get recent transactions.
     */
    @Transactional(readOnly = true)
    public List<Transaction> getRecentTransactions(int limit, UUID corporateId) {
        log.debug("Getting recent transactions, limit={}, corporateId={}", limit, corporateId);
        Pageable pageable = PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "transactionDate"));
        
        if (corporateId != null) {
            return transactionRepository.findByCorporateId(corporateId, pageable).getContent();
        }
        return transactionRepository.findAll(pageable).getContent();
    }

/**
     * Get transaction statistics for dashboard.
     */
    @Transactional(readOnly = true)
    public TransactionDto.TransactionStatsResponse getTransactionStats(UUID corporateId) {
        log.debug("Getting transaction stats, corporateId={}", corporateId);
        
        long totalCount = transactionRepository.count();
        long pendingCount = transactionRepository.countByStatus(Transaction.TransactionStatus.PENDING);
        long completedCount = transactionRepository.countByStatus(Transaction.TransactionStatus.COMPLETED);
        long failedCount = transactionRepository.countByStatus(Transaction.TransactionStatus.FAILED);
        
        BigDecimal totalCredits = BigDecimal.ZERO;
        BigDecimal totalDebits = BigDecimal.ZERO;
        
        LocalDateTime startOfToday = LocalDateTime.now().toLocalDate().atStartOfDay();
        long todayCount = transactionRepository.countByCreatedAtAfter(startOfToday);
        
        return TransactionDto.TransactionStatsResponse.builder()
            .totalCredits(totalCredits)
            .totalDebits(totalDebits)
            .netFlow(totalCredits.subtract(totalDebits))
            .todayCredits(BigDecimal.ZERO)
            .todayDebits(BigDecimal.ZERO)
            .todayNetFlow(BigDecimal.ZERO)
            .totalCount(totalCount)
            .todayCount(todayCount)
            .pendingCount(pendingCount)
            .failedCount(failedCount)
            .completedCount(completedCount)
            .swiftCount(0L)
            .rtgsCount(0L)
            .internalCount(0L)
            .poboCount(0L)
            .build();
    }
    // ========================================================================
    // CREDIT OPERATION
    // ========================================================================

    @Transactional
    public Transaction credit(TransactionDto.CreditRequest request) {
        log.info("Processing credit: VA={}, Amount={}", request.getVaId(), request.getAmount());

        VirtualAccount va = virtualAccountRepository.findById(request.getVaId())
                .orElseThrow(() -> new ResourceNotFoundException("Virtual account not found"));

        validateVaActive(va, "credit");

        BigDecimal balanceBefore = va.getCurrentBalance();
        BigDecimal balanceAfter = balanceBefore.add(request.getAmount());

        va.setCurrentBalance(balanceAfter);
        va.setAvailableBalance(balanceAfter);
        virtualAccountRepository.save(va);

        TransactionCategory category = request.getExternalReference() != null ? 
            TransactionCategory.EXTERNAL : TransactionCategory.INTERNAL;

        Transaction txn = Transaction.builder()
                .movementType(Transaction.MovementType.CREDIT)
                .transactionCategory(category)
                .corporateId(va.getCorporateId())
                .legalEntityId(va.getOwningEntityId())
                .vaId(va.getId())
                .programId(va.getProgramId())
                .amount(request.getAmount())
                .currencyCode(va.getCurrencyCode())
                .balanceBefore(balanceBefore)
                .balanceAfter(balanceAfter)
                .transactionDate(LocalDateTime.now())
                .valueDate(request.getValueDate() != null ? request.getValueDate() : LocalDate.now())
                .referenceNumber(generateReferenceNumber("CR"))
                .description(request.getDescription())
                .channel(request.getChannel())
                .remitterName(request.getRemitterName())
                .remitterAccount(request.getRemitterAccount())
                .status(Transaction.TransactionStatus.COMPLETED)
                .externalReference(request.getExternalReference())
                .build();

        txn = transactionRepository.save(txn);

        releaseLimitUtilizationSafe(va.getId(), request.getAmount());

        log.info("Credit completed: VA={}, Ref={}, Balance={}", 
            va.getVaNumber(), txn.getReferenceNumber(), balanceAfter);

        return txn;
    }

    // ========================================================================
    // DEBIT OPERATION
    // ========================================================================

    @Transactional
    public Transaction debit(TransactionDto.DebitRequest request) {
        log.info("Processing debit: VA={}, Amount={}", request.getVaId(), request.getAmount());

        VirtualAccount va = virtualAccountRepository.findById(request.getVaId())
                .orElseThrow(() -> new ResourceNotFoundException("Virtual account not found"));

        FundsCheckResult fundsCheck = fundsAvailabilityService.checkFundsAvailability(
            request.getVaId(), request.getAmount(), va.getCurrencyCode());

        if (!fundsCheck.isApproved()) {
            log.warn("Debit REJECTED - VA={}, Reason={}", va.getVaNumber(), fundsCheck.getRejectionReason());
            throw new InsufficientFundsException(fundsCheck.getRejectionReason(), fundsCheck);
        }

        BigDecimal balanceBefore = va.getCurrentBalance();
        BigDecimal balanceAfter = balanceBefore.subtract(request.getAmount());

        va.setCurrentBalance(balanceAfter);
        va.setAvailableBalance(balanceAfter);
        virtualAccountRepository.save(va);

        TransactionCategory category = request.getExternalReference() != null ? 
            TransactionCategory.EXTERNAL : TransactionCategory.INTERNAL;

        Transaction txn = Transaction.builder()
                .movementType(Transaction.MovementType.DEBIT)
                .transactionCategory(category)
                .corporateId(va.getCorporateId())
                .legalEntityId(va.getOwningEntityId())
                .vaId(va.getId())
                .programId(va.getProgramId())
                .amount(request.getAmount())
                .currencyCode(va.getCurrencyCode())
                .balanceBefore(balanceBefore)
                .balanceAfter(balanceAfter)
                .transactionDate(LocalDateTime.now())
                .valueDate(request.getValueDate() != null ? request.getValueDate() : LocalDate.now())
                .referenceNumber(generateReferenceNumber("DR"))
                .description(request.getDescription())
                .channel(request.getChannel())
                .beneficiaryName(request.getBeneficiaryName())
                .beneficiaryAccount(request.getBeneficiaryAccount())
                .status(Transaction.TransactionStatus.COMPLETED)
                .externalReference(request.getExternalReference())
                .build();

        txn = transactionRepository.save(txn);

        updateLimitUtilizationSafe(va.getId(), request.getAmount());

        log.info("Debit completed: VA={}, Ref={}, Balance={}", 
            va.getVaNumber(), txn.getReferenceNumber(), balanceAfter);

        return txn;
    }

    // ========================================================================
    // TRANSFER PREVIEW - Calculate fees without executing transfer
    // ========================================================================

    /**
     * Preview transfer to show fees and validation before execution.
     * Does NOT execute the transfer - only calculates fees and validates.
     */
    public TransactionDto.TransferPreviewResponse previewTransfer(TransactionDto.TransferPreviewRequest request) {
        log.info("Previewing transfer: From={}, To={}, Amount={}",
                request.getFromVaId(), request.getToVaId(), request.getAmount());

        VirtualAccount fromVa = virtualAccountRepository.findById(request.getFromVaId())
                .orElseThrow(() -> new ResourceNotFoundException("Source VA not found"));

        VirtualAccount toVa = virtualAccountRepository.findById(request.getToVaId())
                .orElseThrow(() -> new ResourceNotFoundException("Destination VA not found"));

        // Classify transfer and calculate fees
        TransferClassification classification = classifyTransfer(fromVa, toVa);
        TransferFeeBreakdown feeBreakdown = calculateTransferFees(fromVa, toVa, request.getAmount(), classification);
        BigDecimal totalFee = feeBreakdown.getTotalFee();
        BigDecimal totalDebit = request.getAmount().add(totalFee);

        // Check funds availability
        FundsCheckResult fundsCheck = fundsAvailabilityService.checkFundsAvailability(
            request.getFromVaId(), totalDebit, fromVa.getCurrencyCode());

        // Map fee breakdown to response DTO
        List<TransactionDto.FeeLineItemResponse> feeLineItems = feeBreakdown.getLineItems() != null ?
            feeBreakdown.getLineItems().stream()
                .map(item -> TransactionDto.FeeLineItemResponse.builder()
                    .chargeCode(item.getChargeCode())
                    .chargeName(item.getChargeName())
                    .amount(item.getAmount())
                    .currency(item.getCurrency())
                    .waived(item.isWaived())
                    .waiverReason(item.getWaiverReason())
                    .feeOwner(item.getFeeOwner() != null ? item.getFeeOwner().name() : null)
                    .build())
                .collect(java.util.stream.Collectors.toList()) : new ArrayList<>();

        TransactionDto.FeeBreakdownResponse feeResponse = TransactionDto.FeeBreakdownResponse.builder()
            .totalFee(totalFee)
            .lineItems(feeLineItems)
            .build();

        return TransactionDto.TransferPreviewResponse.builder()
            .fromVaId(fromVa.getId())
            .fromVaNumber(fromVa.getVaNumber())
            .fromVaName(fromVa.getVaName())
            .fromBalance(fromVa.getCurrentBalance())
            .toVaId(toVa.getId())
            .toVaNumber(toVa.getVaNumber())
            .toVaName(toVa.getVaName())
            .amount(request.getAmount())
            .currency(fromVa.getCurrencyCode())
            .sameProgram(classification.isSameProgram())
            .intercompany(classification.isIntercompany())
            .crossBorder(classification.isCrossBorder())
            .requiresFx(classification.isRequiresFx())
            .feeBreakdown(feeResponse)
            .totalDebit(totalDebit)
            .fundsAvailable(fundsCheck.isApproved())
            .validationMessage(fundsCheck.isApproved() ? null : fundsCheck.getRejectionReason())
            .build();
    }

    // ========================================================================
    // TRANSFER OPERATION - VA to VA via Settlement VA
    // ========================================================================

    /**
     * Transfer between VAs via Settlement VA as clearing house.
     *
     * FLOW: Source VA → Settlement VA → Destination VA (4 entries)
     * Settlement VA nets to zero (pass-through).
     */
    @Transactional
    public Transaction transfer(TransactionDto.TransferRequest request) {
        log.info("Processing transfer: From={}, To={}, Amount={}", 
                request.getFromVaId(), request.getToVaId(), request.getAmount());

        VirtualAccount fromVa = virtualAccountRepository.findById(request.getFromVaId())
                .orElseThrow(() -> new ResourceNotFoundException("Source VA not found"));
        
        VirtualAccount toVa = virtualAccountRepository.findById(request.getToVaId())
                .orElseThrow(() -> new ResourceNotFoundException("Destination VA not found"));

        validateVaActive(fromVa, "transfer from");
        validateVaActive(toVa, "transfer to");
        
        // Classify transfer and calculate fees from configuration
        TransferClassification classification = classifyTransfer(fromVa, toVa);
        TransferFeeBreakdown feeBreakdown = calculateTransferFees(fromVa, toVa, request.getAmount(), classification);
        BigDecimal totalFee = feeBreakdown.getTotalFee();
        BigDecimal totalDebit = request.getAmount().add(totalFee);
        
        log.info("Transfer classification: sameProgram={}, intercompany={}, fees={}", 
            classification.isSameProgram(), classification.isIntercompany(), totalFee);
        
        // Funds availability check
        FundsCheckResult fundsCheck = fundsAvailabilityService.checkFundsAvailability(
            request.getFromVaId(), totalDebit, fromVa.getCurrencyCode());

        if (!fundsCheck.isApproved()) {
            log.warn("Transfer REJECTED - VA={}, Reason={}, Required={}", 
                fromVa.getVaNumber(), fundsCheck.getRejectionReason(), totalDebit);
            throw new InsufficientFundsException(
                "Transfer rejected: " + fundsCheck.getRejectionReason() + 
                " (Required including fees: " + totalDebit + ")", fundsCheck);
        }

        // Resolve Settlement VA with hierarchy traversal and entity validation (v5.3.0)
        // Pass toVa as contraVa to enable:
        // 1. Hierarchy traversal (sibling → parent chain → program → corporate)
        // 2. Legal entity ownership validation
        // 3. Cross-currency detection
        SettlementVaResolverService.SettlementVaResolutionResult settlementResolution =
            settlementVaResolver.resolveSettlementVaWithResult(fromVa, request.getAmount(), null, toVa);

        // If Settlement VA not found OR entity mismatch, throw user-friendly exception
        if (!settlementResolution.isSuccess()) {
            ExceptionTransaction exceptionRecord = settlementResolution.getExceptionRaised();

            log.warn("Transfer {} → {} cannot proceed: {}. " +
                     "Transaction parked in Exception VA. Exception: {}",
                fromVa.getVaNumber(), toVa.getVaNumber(),
                settlementResolution.getFailureReason(),
                exceptionRecord.getExceptionNumber());

            // Determine the type of failure and throw appropriate user-friendly exception
            if (settlementResolution.getFailureReason().contains("ENTITY MISMATCH")) {
                throw SettlementVaException.entityMismatch(
                    exceptionRecord.getExceptionNumber(),
                    exceptionRecord.getExceptionVaId(),
                    fromVa.getProgramId(),
                    fromVa.getCurrencyCode(),
                    fromVa.getOwningEntityId() != null ? fromVa.getOwningEntityId().toString() : "Unknown",
                    toVa.getOwningEntityId() != null ? toVa.getOwningEntityId().toString() : "Unknown"
                );
            } else {
                throw SettlementVaException.missingSettlementVa(
                    exceptionRecord.getExceptionNumber(),
                    exceptionRecord.getExceptionVaId(),
                    fromVa.getProgramId(),
                    fromVa.getCurrencyCode()
                );
            }
        }

        VirtualAccount settlementVa = settlementResolution.getResolvedVa();

        String correlationId = generateCorrelationId("TRF");
        LocalDate valueDate = request.getValueDate() != null ? request.getValueDate() : LocalDate.now();
        String feeBreakdownJson = serializeFeeBreakdown(feeBreakdown);

        List<Transaction> allTransactions = new ArrayList<>();

        // =====================================================================
        // PRINCIPAL FLOW: Source VA → Settlement VA → Destination VA
        // =====================================================================

        // ENTRY 1: DEBIT Source VA (TRANSFER_OUT)
        BigDecimal fromBalanceBefore = fromVa.getCurrentBalance();
        fromVa.setCurrentBalance(fromBalanceBefore.subtract(request.getAmount()));
        virtualAccountRepository.save(fromVa);

        Transaction txn1_sourceOut = createTransaction(
            Transaction.MovementType.TRANSFER_OUT,
            TransactionCategory.INTERNAL,
            fromVa, request.getAmount(),
            fromBalanceBefore, fromVa.getCurrentBalance(),
            valueDate, correlationId,
            buildTransferDescription(toVa, classification),
            toVa.getId(), totalFee, feeBreakdownJson,  // Use actual destination VA as counterparty (not Settlement VA)
            "Transfer via Settlement VA - leg 1 of 4"
        );
        allTransactions.add(txn1_sourceOut);

        // ENTRY 2: CREDIT Settlement VA
        BigDecimal settlementBalanceBefore1 = settlementVa.getCurrentBalance();
        settlementVa.setCurrentBalance(settlementBalanceBefore1.add(request.getAmount()));
        virtualAccountRepository.save(settlementVa);

        Transaction txn2_settlementIn = createTransaction(
            Transaction.MovementType.TRANSFER_IN,
            TransactionCategory.INTERNAL,
            settlementVa, request.getAmount(),
            settlementBalanceBefore1, settlementVa.getCurrentBalance(),
            valueDate, correlationId,
            "Settlement received from " + fromVa.getVaNumber(),
            fromVa.getId(), null, null,
            "Transfer via Settlement VA - leg 2 of 4 (clearing)"
        );
        allTransactions.add(txn2_settlementIn);

        // ENTRY 3: DEBIT Settlement VA
        BigDecimal settlementBalanceBefore2 = settlementVa.getCurrentBalance();
        settlementVa.setCurrentBalance(settlementBalanceBefore2.subtract(request.getAmount()));
        virtualAccountRepository.save(settlementVa);

        Transaction txn3_settlementOut = createTransaction(
            Transaction.MovementType.TRANSFER_OUT,
            TransactionCategory.INTERNAL,
            settlementVa, request.getAmount(),
            settlementBalanceBefore2, settlementVa.getCurrentBalance(),
            valueDate, correlationId,
            "Settlement payout to " + toVa.getVaNumber(),
            toVa.getId(), null, null,
            "Transfer via Settlement VA - leg 3 of 4 (clearing)"
        );
        allTransactions.add(txn3_settlementOut);

        // ENTRY 4: CREDIT Destination VA
        BigDecimal toBalanceBefore = toVa.getCurrentBalance();
        toVa.setCurrentBalance(toBalanceBefore.add(request.getAmount()));
        toVa.setAvailableBalance(toVa.getCurrentBalance());
        virtualAccountRepository.save(toVa);

        Transaction txn4_destIn = createTransaction(
            Transaction.MovementType.TRANSFER_IN,
            TransactionCategory.INTERNAL,
            toVa, request.getAmount(),
            toBalanceBefore, toVa.getCurrentBalance(),
            valueDate, correlationId,
            "Transfer from " + fromVa.getVaNumber(),
            fromVa.getId(), null, null,  // Use actual source VA as counterparty (not Settlement VA)
            "Transfer via Settlement VA - leg 4 of 4"
        );
        allTransactions.add(txn4_destIn);

        // =====================================================================
        // FEE FLOW: Source VA → Settlement VA
        // =====================================================================
        if (totalFee.compareTo(BigDecimal.ZERO) > 0) {
            postTransferFees(fromVa, toVa, feeBreakdown, txn1_sourceOut, correlationId);
        }

        updateLimitUtilizationSafe(fromVa.getId(), totalDebit);

        // Propagate balance changes through hierarchy (only for transaction/operating VAs)
        // Settlement VA is a system account - its balance doesn't propagate to hierarchy
        propagateBalanceChanges(fromVa.getId(), request.getAmount().negate());  // Source VA decreased (transaction VA)
        propagateBalanceChanges(toVa.getId(), request.getAmount());             // Destination VA increased (transaction VA)

        log.info("Transfer completed: {} → {} → {}, amount={}, fees={}",
            fromVa.getVaNumber(), settlementVa.getVaNumber(), toVa.getVaNumber(),
            request.getAmount(), totalFee);

        return txn1_sourceOut;
    }

    // ========================================================================
    // OUTBOUND PAYMENT PREVIEW - Calculate fees without executing
    // ========================================================================

    /**
     * Preview outbound payment to show fees and validation before execution.
     * Does NOT execute the payment - only calculates fees and validates.
     */
    public TransactionDto.PaymentPreviewResponse previewPayment(TransactionDto.PaymentPreviewRequest request) {
        log.info("Previewing payment: VA={}, Amount={}, Channel={}",
                request.getFromVaId(), request.getAmount(), request.getChannel());

        VirtualAccount fromVa = virtualAccountRepository.findById(request.getFromVaId())
                .orElseThrow(() -> new ResourceNotFoundException("Source VA not found"));

        // Calculate payment fees
        TransactionDto.PaymentRequest feeRequest = TransactionDto.PaymentRequest.builder()
            .fromVaId(request.getFromVaId())
            .amount(request.getAmount())
            .channel(request.getChannel())
            .build();
        BigDecimal paymentFee = calculatePaymentFee(fromVa, feeRequest);
        BigDecimal totalDebit = request.getAmount().add(paymentFee);

        // Check funds availability
        FundsCheckResult fundsCheck = fundsAvailabilityService.checkFundsAvailability(
            request.getFromVaId(), totalDebit, fromVa.getCurrencyCode());

        // Find Shadow VA (for info)
        VirtualAccount shadowVa = findShadowVa(fromVa);
        boolean shadowAvailable = shadowVa != null;

        return TransactionDto.PaymentPreviewResponse.builder()
            .fromVaId(fromVa.getId())
            .fromVaNumber(fromVa.getVaNumber())
            .fromVaName(fromVa.getVaName())
            .fromBalance(fromVa.getCurrentBalance())
            .shadowVaId(shadowVa != null ? shadowVa.getId() : null)
            .shadowVaNumber(shadowVa != null ? shadowVa.getVaNumber() : null)
            .physicalAccountNumber(shadowVa != null ? shadowVa.getBankAccountNumber() : null)
            .amount(request.getAmount())
            .currency(fromVa.getCurrencyCode())
            .channel(request.getChannel())
            .paymentFee(paymentFee)
            .totalDebit(totalDebit)
            .fundsAvailable(fundsCheck.isApproved())
            .shadowVaAvailable(shadowAvailable)
            .validationMessage(fundsCheck.isApproved() ?
                (shadowAvailable ? null : "No Shadow VA available for CBS payment") :
                fundsCheck.getRejectionReason())
            .build();
    }

    /**
     * Find Shadow VA for outbound payments.
     */
    private VirtualAccount findShadowVa(VirtualAccount sourceVa) {
        // Try to find Shadow VA for the same program/currency
        List<VirtualAccount> shadowVas = virtualAccountRepository.findShadowAccountsByCurrency(
            sourceVa.getCorporateId(), sourceVa.getCurrencyCode());
        return shadowVas.isEmpty() ? null : shadowVas.get(0);
    }

    // ========================================================================
    // OUTBOUND PAYMENT - VA to External via Shadow VA
    // ========================================================================

    /**
     * Process outbound payment from VA to external beneficiary.
     *
     * FLOW: Source VA → Settlement VA → Shadow VA → [CBS triggers real payment]
     *
     * Shadow VA is required as it:
     * - Mirrors the Physical Account balance
     * - Triggers CBS integration for real money movement
     * - Provides reconciliation point between virtual and physical
     */
    @Transactional
    public Transaction makePayment(TransactionDto.PaymentRequest request) {
        log.info("Processing outbound payment: VA={}, Amount={}, Beneficiary={}", 
                request.getFromVaId(), request.getAmount(), request.getBeneficiaryName());

        VirtualAccount fromVa = virtualAccountRepository.findById(request.getFromVaId())
                .orElseThrow(() -> new ResourceNotFoundException("Source VA not found"));

        validateVaActive(fromVa, "payment from");

        // Calculate payment fees from configuration
        BigDecimal paymentFee = calculatePaymentFee(fromVa, request);
        BigDecimal totalDebit = request.getAmount().add(paymentFee);

        // Funds availability check
        FundsCheckResult fundsCheck = fundsAvailabilityService.checkFundsAvailability(
            request.getFromVaId(), totalDebit, fromVa.getCurrencyCode());

        if (!fundsCheck.isApproved()) {
            log.warn("Payment REJECTED - VA={}, Reason={}, Required={}", 
                fromVa.getVaNumber(), fundsCheck.getRejectionReason(), totalDebit);
            throw new InsufficientFundsException(
                "Payment rejected: " + fundsCheck.getRejectionReason() + 
                " (Required including fee: " + totalDebit + ")", fundsCheck);
        }

        // Resolve Settlement VA with proper exception handling (v5.2.0)
        SettlementVaResolverService.SettlementVaResolutionResult settlementResolution =
            settlementVaResolver.resolveSettlementVaWithResult(fromVa, request.getAmount(), null);

        if (!settlementResolution.isSuccess()) {
            ExceptionTransaction exceptionRecord = settlementResolution.getExceptionRaised();
            log.error("Outbound payment from {} cannot proceed: Settlement VA not configured. " +
                     "Exception: {}", fromVa.getVaNumber(), exceptionRecord.getExceptionNumber());

            throw SettlementVaException.missingSettlementVa(
                exceptionRecord.getExceptionNumber(),
                exceptionRecord.getExceptionVaId(),
                fromVa.getProgramId(),
                fromVa.getCurrencyCode()
            );
        }

        VirtualAccount settlementVa = settlementResolution.getResolvedVa();

        // Resolve Shadow VA
        VirtualAccount shadowVa = resolveShadowVa(fromVa);
        if (shadowVa == null) {
            throw new BusinessException("No Shadow VA found for outbound payment. " +
                "Shadow VA (PHYSICAL_MIRROR) is required to link to Physical Account for CBS settlement.");
        }

        log.info("Payment routing: {} → {} → {} → CBS",
            fromVa.getVaNumber(), settlementVa.getVaNumber(), shadowVa.getVaNumber());

        String correlationId = generateCorrelationId("PMT");
        LocalDate valueDate = request.getValueDate() != null ? request.getValueDate() : LocalDate.now();

        List<Transaction> allTransactions = new ArrayList<>();

        // =====================================================================
        // PRINCIPAL FLOW: Source VA → Settlement VA → Shadow VA
        // =====================================================================

        // ENTRY 1: DEBIT Source VA
        BigDecimal fromBalanceBefore = fromVa.getCurrentBalance();
        fromVa.setCurrentBalance(fromBalanceBefore.subtract(request.getAmount()));
        virtualAccountRepository.save(fromVa);

        Transaction txn1 = Transaction.builder()
                .movementType(Transaction.MovementType.DEBIT)
                .transactionCategory(TransactionCategory.EXTERNAL)
                .corporateId(fromVa.getCorporateId())
                .legalEntityId(fromVa.getOwningEntityId())
                .vaId(fromVa.getId())
                .programId(fromVa.getProgramId())
                .amount(request.getAmount())
                .currencyCode(fromVa.getCurrencyCode())
                .balanceBefore(fromBalanceBefore)
                .balanceAfter(fromVa.getCurrentBalance())
                .transactionDate(LocalDateTime.now())
                .valueDate(valueDate)
                .referenceNumber(generateReferenceNumber("PMT"))
                .description("Payment to " + request.getBeneficiaryName())
                .channel(request.getChannel() != null ? request.getChannel() : "PAYMENT")
                .beneficiaryName(request.getBeneficiaryName())
                .beneficiaryAccount(request.getBeneficiaryAccount())
                .counterpartyVaId(settlementVa.getId())
                .correlationId(correlationId)
                .feeAmount(paymentFee)
                .status(Transaction.TransactionStatus.COMPLETED)
                .processingNotes("Outbound payment - leg 1 of 4")
                .build();
        txn1 = transactionRepository.save(txn1);
        allTransactions.add(txn1);

        // ENTRY 2: CREDIT Settlement VA
        BigDecimal settlementBalanceBefore1 = settlementVa.getCurrentBalance();
        settlementVa.setCurrentBalance(settlementBalanceBefore1.add(request.getAmount()));
        virtualAccountRepository.save(settlementVa);

        Transaction txn2 = createSettlementTransaction(
            Transaction.MovementType.CREDIT, settlementVa, request.getAmount(),
            settlementBalanceBefore1, settlementVa.getCurrentBalance(),
            valueDate, correlationId, fromVa.getId(),
            "Settlement received for payment to " + request.getBeneficiaryName(),
            "Outbound payment - leg 2 of 4"
        );
        allTransactions.add(txn2);

        // ENTRY 3: DEBIT Settlement VA
        BigDecimal settlementBalanceBefore2 = settlementVa.getCurrentBalance();
        settlementVa.setCurrentBalance(settlementBalanceBefore2.subtract(request.getAmount()));
        virtualAccountRepository.save(settlementVa);

        Transaction txn3 = createSettlementTransaction(
            Transaction.MovementType.DEBIT, settlementVa, request.getAmount(),
            settlementBalanceBefore2, settlementVa.getCurrentBalance(),
            valueDate, correlationId, shadowVa.getId(),
            "Settlement payout for payment to " + request.getBeneficiaryName(),
            "Outbound payment - leg 3 of 4"
        );
        allTransactions.add(txn3);

        // ENTRY 4: DEBIT Shadow VA (triggers CBS - money leaves physical account)
        // Shadow VA balance DECREASES to reflect that physical account has less money
        BigDecimal shadowBalanceBefore = shadowVa.getCurrentBalance();
        shadowVa.applyShadowMovement(request.getAmount().negate());
        virtualAccountRepository.save(shadowVa);

        Transaction txn4 = Transaction.builder()
                .movementType(Transaction.MovementType.DEBIT)
                .transactionCategory(TransactionCategory.EXTERNAL)
                .corporateId(shadowVa.getCorporateId())
                .vaId(shadowVa.getId())
                .physicalAccountId(shadowVa.getLinkedPhysicalAccountId())
                .programId(shadowVa.getProgramId())
                .amount(request.getAmount())
                .currencyCode(fromVa.getCurrencyCode())
                .balanceBefore(shadowBalanceBefore)
                .balanceAfter(shadowVa.getCurrentBalance())
                .transactionDate(LocalDateTime.now())
                .valueDate(valueDate)
                .referenceNumber(generateReferenceNumber("CBS"))
                .description("CBS Payment to " + request.getBeneficiaryName())
                .channel(request.getChannel() != null ? request.getChannel() : "CBS")
                .beneficiaryName(request.getBeneficiaryName())
                .beneficiaryAccount(request.getBeneficiaryAccount())
                .counterpartyVaId(settlementVa.getId())
                .correlationId(correlationId)
                .status(Transaction.TransactionStatus.COMPLETED)
                .processingNotes("Outbound payment - leg 4 of 4 (CBS trigger). Physical Account: " + 
                    shadowVa.getBankAccountNumber())
                .build();
        txn4 = transactionRepository.save(txn4);
        allTransactions.add(txn4);

        // =====================================================================
        // FEE FLOW
        // =====================================================================
        if (paymentFee.compareTo(BigDecimal.ZERO) > 0) {
            postSingleFee(fromVa, paymentFee, getPaymentFeeCode(request.getChannel()), 
                "Outbound Payment Fee", txn1.getId(), correlationId);
        }

        updateLimitUtilizationSafe(fromVa.getId(), totalDebit);

        // Propagate balance changes through hierarchy (only for transaction/operating VAs)
        // Shadow VA and Settlement VA are system accounts - their balances don't propagate to hierarchy
        propagateBalanceChanges(fromVa.getId(), request.getAmount().negate());    // Source VA decreased (transaction VA)

        log.info("Payment completed: {} → {} → {} → CBS, amount={}, fee={}",
            fromVa.getVaNumber(), settlementVa.getVaNumber(), shadowVa.getVaNumber(),
            request.getAmount(), paymentFee);

        return txn1;
    }

    // ========================================================================
    // POBO (PAY-ON-BEHALF-OF) OPERATION
    // ========================================================================

    /**
     * Process POBO payment where Treasury Center pays on behalf of a subsidiary.
     * 
     * FLOW: 
     * 1. Debit Subsidiary's VA (the entity owning the payable)
     * 2. Credit Treasury Center's VA
     * 3. Debit Treasury Center's VA
     * 4. Debit Shadow VA → CBS (real payment)
     * 
     * The payment goes through Treasury Center's physical account,
     * but the subsidiary bears the cost.
     */
    @Transactional
    public Transaction makePoboPayment(TransactionDto.PoboPaymentRequest request) {
        log.info("Processing POBO payment: OwnerVA={}, PayerVA={}, Amount={}", 
                request.getOwnerVaId(), request.getPayerVaId(), request.getAmount());

        // Owner VA (subsidiary that owns the payable)
        VirtualAccount ownerVa = virtualAccountRepository.findById(request.getOwnerVaId())
                .orElseThrow(() -> new ResourceNotFoundException("Owner VA not found"));

        // Payer VA (Treasury Center that will make the actual payment)
        VirtualAccount payerVa = virtualAccountRepository.findById(request.getPayerVaId())
                .orElseThrow(() -> new ResourceNotFoundException("Payer VA not found"));

        validateVaActive(ownerVa, "POBO owner");
        validateVaActive(payerVa, "POBO payer");

        // Get legal entities for audit
        LegalEntity ownerEntity = ownerVa.getOwningEntityId() != null ?
            legalEntityRepository.findById(ownerVa.getOwningEntityId()).orElse(null) : null;
        LegalEntity payerEntity = payerVa.getOwningEntityId() != null ?
            legalEntityRepository.findById(payerVa.getOwningEntityId()).orElse(null) : null;

        // Calculate POBO fee from configuration
        BigDecimal poboFee = calculatePoboFee(request.getAmount(), ownerVa.getCurrencyCode());
        BigDecimal totalDebit = request.getAmount().add(poboFee);

        // Funds check on OWNER VA (they bear the cost)
        FundsCheckResult fundsCheck = fundsAvailabilityService.checkFundsAvailability(
            request.getOwnerVaId(), totalDebit, ownerVa.getCurrencyCode());

        if (!fundsCheck.isApproved()) {
            log.warn("POBO Payment REJECTED - OwnerVA={}, Reason={}", 
                ownerVa.getVaNumber(), fundsCheck.getRejectionReason());
            throw new InsufficientFundsException(
                "POBO payment rejected: " + fundsCheck.getRejectionReason(), fundsCheck);
        }

        // Resolve Shadow VA from payer's hierarchy
        VirtualAccount shadowVa = resolveShadowVa(payerVa);
        if (shadowVa == null) {
            throw new BusinessException("No Shadow VA found for POBO payer. " +
                "Shadow VA is required for Treasury Center to make external payments.");
        }

        String correlationId = generateCorrelationId("POBO");
        LocalDate valueDate = request.getValueDate() != null ? request.getValueDate() : LocalDate.now();
        String behalfOfEntity = ownerEntity != null ? ownerEntity.getEntityCode() : ownerVa.getVaNumber();

        List<Transaction> allTransactions = new ArrayList<>();

        // =====================================================================
        // POBO FLOW: Owner VA → Payer VA → Shadow VA → CBS
        // =====================================================================

        // ENTRY 1: DEBIT Owner VA (POBO_DEBIT - subsidiary bears cost)
        BigDecimal ownerBalanceBefore = ownerVa.getCurrentBalance();
        ownerVa.setCurrentBalance(ownerBalanceBefore.subtract(request.getAmount()));
        virtualAccountRepository.save(ownerVa);

        Transaction txn1_ownerOut = Transaction.builder()
                .movementType(Transaction.MovementType.POBO_DEBIT)
                .transactionCategory(TransactionCategory.INTERNAL)
                .corporateId(ownerVa.getCorporateId())
                .legalEntityId(ownerVa.getOwningEntityId())
                .vaId(ownerVa.getId())
                .programId(ownerVa.getProgramId())
                .amount(request.getAmount())
                .currencyCode(ownerVa.getCurrencyCode())
                .balanceBefore(ownerBalanceBefore)
                .balanceAfter(ownerVa.getCurrentBalance())
                .transactionDate(LocalDateTime.now())
                .valueDate(valueDate)
                .referenceNumber(generateReferenceNumber("POBO"))
                .description("POBO payment to " + request.getBeneficiaryName() + " via " + 
                    (payerEntity != null ? payerEntity.getEntityName() : payerVa.getVaNumber()))
                .channel("POBO")
                .beneficiaryName(request.getBeneficiaryName())
                .beneficiaryAccount(request.getBeneficiaryAccount())
                .counterpartyVaId(payerVa.getId())
                .correlationId(correlationId)
                .feeAmount(poboFee)
                .isPobo(true)
                .behalfOfEntity(behalfOfEntity)
                .behalfOfVaId(ownerVa.getId())
                .status(Transaction.TransactionStatus.COMPLETED)
                .processingNotes("POBO - leg 1 of 4 (owner debit)")
                .build();
        txn1_ownerOut = transactionRepository.save(txn1_ownerOut);
        allTransactions.add(txn1_ownerOut);

        // ENTRY 2: CREDIT Payer VA (Treasury Center receives intercompany)
        BigDecimal payerBalanceBefore1 = payerVa.getCurrentBalance();
        payerVa.setCurrentBalance(payerBalanceBefore1.add(request.getAmount()));
        virtualAccountRepository.save(payerVa);

        Transaction txn2_payerIn = Transaction.builder()
                .movementType(Transaction.MovementType.TRANSFER_IN)
                .transactionCategory(TransactionCategory.INTERNAL)
                .corporateId(payerVa.getCorporateId())
                .legalEntityId(payerVa.getOwningEntityId())
                .vaId(payerVa.getId())
                .programId(payerVa.getProgramId())
                .amount(request.getAmount())
                .currencyCode(ownerVa.getCurrencyCode())
                .balanceBefore(payerBalanceBefore1)
                .balanceAfter(payerVa.getCurrentBalance())
                .transactionDate(LocalDateTime.now())
                .valueDate(valueDate)
                .referenceNumber(generateReferenceNumber("POBO"))
                .description("POBO received from " + behalfOfEntity + " for " + request.getBeneficiaryName())
                .channel("POBO")
                .counterpartyVaId(ownerVa.getId())
                .correlationId(correlationId)
                .isPobo(true)
                .behalfOfEntity(behalfOfEntity)
                .behalfOfVaId(ownerVa.getId())
                .status(Transaction.TransactionStatus.COMPLETED)
                .processingNotes("POBO - leg 2 of 4 (payer credit)")
                .build();
        txn2_payerIn = transactionRepository.save(txn2_payerIn);
        allTransactions.add(txn2_payerIn);

        // ENTRY 3: DEBIT Payer VA (Treasury Center makes payment)
        BigDecimal payerBalanceBefore2 = payerVa.getCurrentBalance();
        payerVa.setCurrentBalance(payerBalanceBefore2.subtract(request.getAmount()));
        virtualAccountRepository.save(payerVa);

        Transaction txn3_payerOut = Transaction.builder()
                .movementType(Transaction.MovementType.POBO_DEBIT)
                .transactionCategory(TransactionCategory.EXTERNAL)
                .corporateId(payerVa.getCorporateId())
                .legalEntityId(payerVa.getOwningEntityId())
                .vaId(payerVa.getId())
                .programId(payerVa.getProgramId())
                .amount(request.getAmount())
                .currencyCode(ownerVa.getCurrencyCode())
                .balanceBefore(payerBalanceBefore2)
                .balanceAfter(payerVa.getCurrentBalance())
                .transactionDate(LocalDateTime.now())
                .valueDate(valueDate)
                .referenceNumber(generateReferenceNumber("POBO"))
                .description("POBO payout on behalf of " + behalfOfEntity + " to " + request.getBeneficiaryName())
                .channel("POBO")
                .beneficiaryName(request.getBeneficiaryName())
                .beneficiaryAccount(request.getBeneficiaryAccount())
                .counterpartyVaId(shadowVa.getId())
                .correlationId(correlationId)
                .isPobo(true)
                .behalfOfEntity(behalfOfEntity)
                .behalfOfVaId(ownerVa.getId())
                .status(Transaction.TransactionStatus.COMPLETED)
                .processingNotes("POBO - leg 3 of 4 (payer debit)")
                .build();
        txn3_payerOut = transactionRepository.save(txn3_payerOut);
        allTransactions.add(txn3_payerOut);

        // ENTRY 4: POBO_DEBIT Shadow VA (CBS trigger - money leaves physical account)
        // Shadow VA mirrors the physical account, so when money goes out, Shadow VA balance DECREASES
        BigDecimal shadowBalanceBefore = shadowVa.getCurrentBalance();
        shadowVa.applyShadowMovement(request.getAmount().negate());
        virtualAccountRepository.save(shadowVa);

        Transaction txn4_shadowOut = Transaction.builder()
                .movementType(Transaction.MovementType.POBO_DEBIT)
                .transactionCategory(TransactionCategory.EXTERNAL)
                .corporateId(shadowVa.getCorporateId())
                .vaId(shadowVa.getId())
                .physicalAccountId(shadowVa.getLinkedPhysicalAccountId())
                .programId(shadowVa.getProgramId())
                .amount(request.getAmount())
                .currencyCode(ownerVa.getCurrencyCode())
                .balanceBefore(shadowBalanceBefore)
                .balanceAfter(shadowVa.getCurrentBalance())
                .transactionDate(LocalDateTime.now())
                .valueDate(valueDate)
                .referenceNumber(generateReferenceNumber("CBS"))
                .description("CBS POBO Payment on behalf of " + behalfOfEntity + " to " + request.getBeneficiaryName())
                .channel(request.getChannel() != null ? request.getChannel() : "CBS")
                .beneficiaryName(request.getBeneficiaryName())
                .beneficiaryAccount(request.getBeneficiaryAccount())
                .counterpartyVaId(payerVa.getId())
                .correlationId(correlationId)
                .isPobo(true)
                .behalfOfEntity(behalfOfEntity)
                .behalfOfVaId(ownerVa.getId())
                .status(Transaction.TransactionStatus.COMPLETED)
                .processingNotes("POBO - leg 4 of 4 (Shadow VA debit - CBS outbound). Physical Account: " +
                    shadowVa.getBankAccountNumber())
                .build();
        txn4_shadowOut = transactionRepository.save(txn4_shadowOut);
        allTransactions.add(txn4_shadowOut);

        // =====================================================================
        // FEE FLOW: Owner VA → Settlement VA
        // =====================================================================
        if (poboFee.compareTo(BigDecimal.ZERO) > 0) {
            // Note: postSingleFee handles Settlement VA resolution internally
            // and will gracefully handle missing Settlement VA
            postSingleFee(ownerVa, poboFee, CHARGE_POBO_FEE,
                "POBO Fee for payment to " + request.getBeneficiaryName(),
                txn1_ownerOut.getId(), correlationId);
        }

        // Update payable if linked
        if (request.getPayableId() != null) {
            updatePayableStatus(request.getPayableId(), txn1_ownerOut.getReferenceNumber());
        }

        updateLimitUtilizationSafe(ownerVa.getId(), totalDebit);

        // Propagate balance changes through hierarchy (only for transaction/operating VAs)
        // Payer VA (Treasury) nets to zero, Shadow VA is a system account - don't propagate
        propagateBalanceChanges(ownerVa.getId(), request.getAmount().negate());   // Owner VA decreased (transaction VA)

        log.info("POBO payment completed: {} → {} → {} → CBS, amount={}, fee={}, behalfOf={}",
            ownerVa.getVaNumber(), payerVa.getVaNumber(), shadowVa.getVaNumber(),
            request.getAmount(), poboFee, behalfOfEntity);

        // =====================================================================
        // CREATE INTERCOMPANY TRANSACTION RECORD (for Intercompany page display)
        // =====================================================================
        createIntercompanyTransactionRecord(
            ownerVa, payerVa, ownerEntity, payerEntity,
            request.getAmount(), poboFee, correlationId,
            txn1_ownerOut.getReferenceNumber(), "POBO_4LEG");

        return txn1_ownerOut;
    }

    // ========================================================================
    // IHB 6-LEG POBO PAYMENT - Mirror Account Model (Option A)
    // ========================================================================

    /**
     * Process IHB POBO payment with 6-leg accounting (Mirror Account Model).
     *
     * This method implements the proper intercompany accounting for IHB Current Accounts:
     *
     * 6-LEG FLOW:
     * | Leg | Account               | Debit | Credit | Movement Type    | Description |
     * |-----|-----------------------|-------|--------|------------------|-------------|
     * | 1   | IHB Current Account   | X     |        | POBO_DEBIT       | Subsidiary bears cost |
     * | 2   | IHB Settlement VA     |       | X      | TRANSFER_IN      | Per-subsidiary routing |
     * | 3   | IHB Settlement VA     | X     |        | TRANSFER_OUT     | Route to Treasury |
     * | 4   | Treasury Settlement VA|       | X      | TRANSFER_IN      | Treasury receives |
     * | 5   | IC Receivable VA      |       | X      | IC_RECEIVABLE    | Treasury's claim on subsidiary |
     * | 6   | Shadow VA             | X     |        | DEBIT            | CBS external payment |
     *
     * BALANCE EFFECTS:
     * - IHB Current Account: DECREASES by amount (subsidiary used credit)
     * - IHB Settlement VA: Nets to ZERO (pass-through)
     * - Treasury Settlement VA: INCREASES then CBS pays out
     * - IC Receivable VA: INCREASES (Treasury is owed more by subsidiary)
     * - Shadow VA: DECREASES (physical account funds outbound)
     *
     * FALLS BACK TO 4-LEG: If IHB Settlement VA or IC Receivable VA not configured.
     *
     * @param request POBO payment request with IHB Current Account as owner
     * @return Primary transaction (owner debit)
     */
    @Transactional
    public Transaction makeIhb6LegPoboPayment(TransactionDto.PoboPaymentRequest request) {
        log.info("Processing IHB 6-leg POBO payment: OwnerVA={}, PayerVA={}, Amount={}",
            request.getOwnerVaId(), request.getPayerVaId(), request.getAmount());

        // Owner VA (IHB Current Account - subsidiary's position at Treasury)
        VirtualAccount ownerVa = virtualAccountRepository.findById(request.getOwnerVaId())
                .orElseThrow(() -> new ResourceNotFoundException("Owner VA (IHB Current Account) not found"));

        // Validate this is an IHB Current Account configured for 6-leg
        if (!ownerVa.isConfiguredFor6LegPobo()) {
            log.warn("IHB Current Account {} not configured for 6-leg POBO. " +
                "IhbSettlementVaId={}, IcReceivableVaId={}, TreasuryPoolVaId={}. Falling back to 4-leg flow.",
                ownerVa.getVaNumber(), ownerVa.getIhbSettlementVaId(),
                ownerVa.getIcReceivableVaId(), ownerVa.getTreasuryPoolVaId());
            return makePoboPayment(request);
        }

        // Load IHB Settlement VA
        VirtualAccount ihbSettlementVa = virtualAccountRepository.findById(ownerVa.getIhbSettlementVaId())
                .orElseThrow(() -> new ResourceNotFoundException("IHB Settlement VA not found: " + ownerVa.getIhbSettlementVaId()));

        // Load Treasury Settlement VA (payer)
        VirtualAccount treasurySettlementVa = virtualAccountRepository.findById(ownerVa.getTreasuryPoolVaId())
                .orElseThrow(() -> new ResourceNotFoundException("Treasury Settlement VA not found: " + ownerVa.getTreasuryPoolVaId()));

        // Load IC Receivable VA
        VirtualAccount icReceivableVa = virtualAccountRepository.findById(ownerVa.getIcReceivableVaId())
                .orElseThrow(() -> new ResourceNotFoundException("IC Receivable VA not found: " + ownerVa.getIcReceivableVaId()));

        validateVaActive(ownerVa, "IHB owner");
        validateVaActive(ihbSettlementVa, "IHB Settlement");
        validateVaActive(treasurySettlementVa, "Treasury Settlement");
        validateVaActive(icReceivableVa, "IC Receivable");

        // Get legal entities for audit
        LegalEntity ownerEntity = ownerVa.getOwningEntityId() != null ?
            legalEntityRepository.findById(ownerVa.getOwningEntityId()).orElse(null) : null;
        LegalEntity treasuryEntity = treasurySettlementVa.getOwningEntityId() != null ?
            legalEntityRepository.findById(treasurySettlementVa.getOwningEntityId()).orElse(null) : null;

        // Calculate POBO fee from configuration
        BigDecimal poboFee = calculatePoboFee(request.getAmount(), ownerVa.getCurrencyCode());
        BigDecimal totalDebit = request.getAmount().add(poboFee);

        // Funds check on OWNER VA (they bear the cost) - uses IHB credit limit
        FundsCheckResult fundsCheck = fundsAvailabilityService.checkFundsAvailability(
            request.getOwnerVaId(), totalDebit, ownerVa.getCurrencyCode());

        if (!fundsCheck.isApproved()) {
            log.warn("IHB POBO Payment REJECTED - OwnerVA={}, Reason={}",
                ownerVa.getVaNumber(), fundsCheck.getRejectionReason());
            throw new InsufficientFundsException(
                "IHB POBO payment rejected: " + fundsCheck.getRejectionReason(), fundsCheck);
        }

        // Resolve Shadow VA from treasury's hierarchy
        VirtualAccount shadowVa = resolveShadowVa(treasurySettlementVa);
        if (shadowVa == null) {
            throw new BusinessException("No Shadow VA found for Treasury POBO. " +
                "Shadow VA is required for Treasury Center to make external payments.");
        }

        String correlationId = generateCorrelationId("IHBPOBO");
        LocalDate valueDate = request.getValueDate() != null ? request.getValueDate() : LocalDate.now();
        String behalfOfEntity = ownerEntity != null ? ownerEntity.getEntityCode() : ownerVa.getVaNumber();

        List<Transaction> allTransactions = new ArrayList<>();

        // =====================================================================
        // IHB 6-LEG POBO FLOW
        // IHB Current Account → IHB Settlement VA → Treasury Settlement VA → IC Receivable → Shadow VA → CBS
        // =====================================================================

        // LEG 1: DEBIT IHB Current Account (POBO_DEBIT - subsidiary bears cost)
        BigDecimal ownerBalanceBefore = ownerVa.getCurrentBalance();
        ownerVa.setCurrentBalance(ownerBalanceBefore.subtract(request.getAmount()));
        ownerVa.setAvailableBalance(ownerVa.getCurrentBalance().add(
            ownerVa.getEffectiveCreditLimit() != null ? ownerVa.getEffectiveCreditLimit() : BigDecimal.ZERO));
        virtualAccountRepository.save(ownerVa);

        Transaction txn1_ownerDebit = Transaction.builder()
                .movementType(Transaction.MovementType.POBO_DEBIT)
                .transactionCategory(TransactionCategory.INTERNAL)
                .corporateId(ownerVa.getCorporateId())
                .legalEntityId(ownerVa.getOwningEntityId())
                .vaId(ownerVa.getId())
                .programId(ownerVa.getProgramId())
                .amount(request.getAmount())
                .currencyCode(ownerVa.getCurrencyCode())
                .balanceBefore(ownerBalanceBefore)
                .balanceAfter(ownerVa.getCurrentBalance())
                .transactionDate(LocalDateTime.now())
                .valueDate(valueDate)
                .referenceNumber(generateReferenceNumber("IHBPOBO"))
                .description("IHB POBO payment to " + request.getBeneficiaryName() + " via Treasury")
                .channel("IHB_POBO")
                .beneficiaryName(request.getBeneficiaryName())
                .beneficiaryAccount(request.getBeneficiaryAccount())
                .counterpartyVaId(ihbSettlementVa.getId())
                .correlationId(correlationId)
                .feeAmount(poboFee)
                .isPobo(true)
                .behalfOfEntity(behalfOfEntity)
                .behalfOfVaId(ownerVa.getId())
                .status(Transaction.TransactionStatus.COMPLETED)
                .processingNotes("IHB POBO - leg 1 of 6 (IHB Current Account debit)")
                .build();
        txn1_ownerDebit = transactionRepository.save(txn1_ownerDebit);
        allTransactions.add(txn1_ownerDebit);

        // LEG 2: CREDIT IHB Settlement VA (receives from IHB Current Account)
        BigDecimal ihbSettBalanceBefore1 = ihbSettlementVa.getCurrentBalance();
        ihbSettlementVa.setCurrentBalance(ihbSettBalanceBefore1.add(request.getAmount()));
        virtualAccountRepository.save(ihbSettlementVa);

        Transaction txn2_ihbSettCredit = Transaction.builder()
                .movementType(Transaction.MovementType.TRANSFER_IN)
                .transactionCategory(TransactionCategory.INTERNAL)
                .corporateId(ihbSettlementVa.getCorporateId())
                .legalEntityId(ihbSettlementVa.getOwningEntityId())
                .vaId(ihbSettlementVa.getId())
                .programId(ihbSettlementVa.getProgramId())
                .amount(request.getAmount())
                .currencyCode(ownerVa.getCurrencyCode())
                .balanceBefore(ihbSettBalanceBefore1)
                .balanceAfter(ihbSettlementVa.getCurrentBalance())
                .transactionDate(LocalDateTime.now())
                .valueDate(valueDate)
                .referenceNumber(generateReferenceNumber("IHBPOBO"))
                .description("IHB Settlement received from " + behalfOfEntity + " for " + request.getBeneficiaryName())
                .channel("IHB_POBO")
                .counterpartyVaId(ownerVa.getId())
                .correlationId(correlationId)
                .isPobo(true)
                .behalfOfEntity(behalfOfEntity)
                .behalfOfVaId(ownerVa.getId())
                .status(Transaction.TransactionStatus.COMPLETED)
                .processingNotes("IHB POBO - leg 2 of 6 (IHB Settlement credit)")
                .build();
        txn2_ihbSettCredit = transactionRepository.save(txn2_ihbSettCredit);
        allTransactions.add(txn2_ihbSettCredit);

        // LEG 3: DEBIT IHB Settlement VA (routes to Treasury)
        BigDecimal ihbSettBalanceBefore2 = ihbSettlementVa.getCurrentBalance();
        ihbSettlementVa.setCurrentBalance(ihbSettBalanceBefore2.subtract(request.getAmount()));
        virtualAccountRepository.save(ihbSettlementVa);

        Transaction txn3_ihbSettDebit = Transaction.builder()
                .movementType(Transaction.MovementType.TRANSFER_OUT)
                .transactionCategory(TransactionCategory.INTERNAL)
                .corporateId(ihbSettlementVa.getCorporateId())
                .legalEntityId(ihbSettlementVa.getOwningEntityId())
                .vaId(ihbSettlementVa.getId())
                .programId(ihbSettlementVa.getProgramId())
                .amount(request.getAmount())
                .currencyCode(ownerVa.getCurrencyCode())
                .balanceBefore(ihbSettBalanceBefore2)
                .balanceAfter(ihbSettlementVa.getCurrentBalance())
                .transactionDate(LocalDateTime.now())
                .valueDate(valueDate)
                .referenceNumber(generateReferenceNumber("IHBPOBO"))
                .description("IHB Settlement route to Treasury for " + request.getBeneficiaryName())
                .channel("IHB_POBO")
                .counterpartyVaId(treasurySettlementVa.getId())
                .correlationId(correlationId)
                .isPobo(true)
                .behalfOfEntity(behalfOfEntity)
                .behalfOfVaId(ownerVa.getId())
                .status(Transaction.TransactionStatus.COMPLETED)
                .processingNotes("IHB POBO - leg 3 of 6 (IHB Settlement debit to Treasury)")
                .build();
        txn3_ihbSettDebit = transactionRepository.save(txn3_ihbSettDebit);
        allTransactions.add(txn3_ihbSettDebit);

        // LEG 4: CREDIT Treasury Settlement VA (receives from IHB Settlement)
        BigDecimal treasuryBalanceBefore = treasurySettlementVa.getCurrentBalance();
        treasurySettlementVa.setCurrentBalance(treasuryBalanceBefore.add(request.getAmount()));
        virtualAccountRepository.save(treasurySettlementVa);

        Transaction txn4_treasuryCredit = Transaction.builder()
                .movementType(Transaction.MovementType.TRANSFER_IN)
                .transactionCategory(TransactionCategory.INTERNAL)
                .corporateId(treasurySettlementVa.getCorporateId())
                .legalEntityId(treasurySettlementVa.getOwningEntityId())
                .vaId(treasurySettlementVa.getId())
                .programId(treasurySettlementVa.getProgramId())
                .amount(request.getAmount())
                .currencyCode(ownerVa.getCurrencyCode())
                .balanceBefore(treasuryBalanceBefore)
                .balanceAfter(treasurySettlementVa.getCurrentBalance())
                .transactionDate(LocalDateTime.now())
                .valueDate(valueDate)
                .referenceNumber(generateReferenceNumber("IHBPOBO"))
                .description("Treasury receives IHB POBO from " + behalfOfEntity + " for " + request.getBeneficiaryName())
                .channel("IHB_POBO")
                .counterpartyVaId(ihbSettlementVa.getId())
                .correlationId(correlationId)
                .isPobo(true)
                .behalfOfEntity(behalfOfEntity)
                .behalfOfVaId(ownerVa.getId())
                .status(Transaction.TransactionStatus.COMPLETED)
                .processingNotes("IHB POBO - leg 4 of 6 (Treasury Settlement credit)")
                .build();
        txn4_treasuryCredit = transactionRepository.save(txn4_treasuryCredit);
        allTransactions.add(txn4_treasuryCredit);

        // LEG 5: IC Receivable VA (Treasury's claim on subsidiary INCREASES)
        // This is an accounting entry - Treasury now has a larger receivable from subsidiary
        BigDecimal icReceivableBalanceBefore = icReceivableVa.getCurrentBalance();
        icReceivableVa.setCurrentBalance(icReceivableBalanceBefore.add(request.getAmount()));
        virtualAccountRepository.save(icReceivableVa);

        Transaction txn5_icReceivable = Transaction.builder()
                .movementType(Transaction.MovementType.IC_RECEIVABLE)
                .transactionCategory(TransactionCategory.INTERNAL)  // Internal IC accounting entry
                .corporateId(icReceivableVa.getCorporateId())
                .legalEntityId(icReceivableVa.getOwningEntityId())
                .vaId(icReceivableVa.getId())
                .programId(icReceivableVa.getProgramId())
                .amount(request.getAmount())
                .currencyCode(ownerVa.getCurrencyCode())
                .balanceBefore(icReceivableBalanceBefore)
                .balanceAfter(icReceivableVa.getCurrentBalance())
                .transactionDate(LocalDateTime.now())
                .valueDate(valueDate)
                .referenceNumber(generateReferenceNumber("IHBPOBO"))
                .description("IC Receivable: Treasury claim on " + behalfOfEntity + " for POBO to " + request.getBeneficiaryName())
                .channel("IHB_POBO")
                .counterpartyVaId(ownerVa.getId())
                .correlationId(correlationId)
                .isPobo(true)
                .behalfOfEntity(behalfOfEntity)
                .behalfOfVaId(ownerVa.getId())
                .status(Transaction.TransactionStatus.COMPLETED)
                .processingNotes("IHB POBO - leg 5 of 6 (IC Receivable increase - Treasury's claim on subsidiary)")
                .build();
        txn5_icReceivable = transactionRepository.save(txn5_icReceivable);
        allTransactions.add(txn5_icReceivable);

        // LEG 6: DEBIT Shadow VA (CBS trigger - money leaves physical account)
        BigDecimal shadowBalanceBefore = shadowVa.getCurrentBalance();
        shadowVa.applyShadowMovement(request.getAmount().negate());
        virtualAccountRepository.save(shadowVa);

        // Also debit Treasury Settlement VA for the external payment
        BigDecimal treasuryBalanceBefore2 = treasurySettlementVa.getCurrentBalance();
        treasurySettlementVa.setCurrentBalance(treasuryBalanceBefore2.subtract(request.getAmount()));
        virtualAccountRepository.save(treasurySettlementVa);

        Transaction txn6_shadowDebit = Transaction.builder()
                .movementType(Transaction.MovementType.POBO_DEBIT)
                .transactionCategory(TransactionCategory.EXTERNAL)
                .corporateId(shadowVa.getCorporateId())
                .vaId(shadowVa.getId())
                .physicalAccountId(shadowVa.getLinkedPhysicalAccountId())
                .programId(shadowVa.getProgramId())
                .amount(request.getAmount())
                .currencyCode(ownerVa.getCurrencyCode())
                .balanceBefore(shadowBalanceBefore)
                .balanceAfter(shadowVa.getCurrentBalance())
                .transactionDate(LocalDateTime.now())
                .valueDate(valueDate)
                .referenceNumber(generateReferenceNumber("POBO"))
                .description("CBS IHB POBO on behalf of " + behalfOfEntity + " to " + request.getBeneficiaryName())
                .channel(request.getChannel() != null ? request.getChannel() : "CBS")
                .beneficiaryName(request.getBeneficiaryName())
                .beneficiaryAccount(request.getBeneficiaryAccount())
                .counterpartyVaId(treasurySettlementVa.getId())
                .correlationId(correlationId)
                .isPobo(true)
                .behalfOfEntity(behalfOfEntity)
                .behalfOfVaId(ownerVa.getId())
                .status(Transaction.TransactionStatus.COMPLETED)
                .processingNotes("IHB POBO - leg 6 of 6 (Shadow VA debit - CBS outbound). Physical Account: " +
                    shadowVa.getBankAccountNumber())
                .build();
        txn6_shadowDebit = transactionRepository.save(txn6_shadowDebit);
        allTransactions.add(txn6_shadowDebit);

        // =====================================================================
        // FEE FLOW: Owner VA → Settlement VA
        // =====================================================================
        if (poboFee.compareTo(BigDecimal.ZERO) > 0) {
            postSingleFee(ownerVa, poboFee, CHARGE_POBO_FEE,
                "IHB POBO Fee for payment to " + request.getBeneficiaryName(),
                txn1_ownerDebit.getId(), correlationId);
        }

        // Update payable if linked
        if (request.getPayableId() != null) {
            updatePayableStatus(request.getPayableId(), txn1_ownerDebit.getReferenceNumber());
        }

        updateLimitUtilizationSafe(ownerVa.getId(), totalDebit);

        // Propagate balance changes through hierarchy
        propagateBalanceChanges(ownerVa.getId(), request.getAmount().negate());

        log.info("IHB 6-leg POBO completed: {} → {} → {} → {} (IC:{}) → {} → CBS, amount={}, fee={}, behalfOf={}",
            ownerVa.getVaNumber(), ihbSettlementVa.getVaNumber(), treasurySettlementVa.getVaNumber(),
            icReceivableVa.getVaNumber(), icReceivableVa.getCurrentBalance(),
            shadowVa.getVaNumber(), request.getAmount(), poboFee, behalfOfEntity);

        // =====================================================================
        // CREATE INTERCOMPANY TRANSACTION RECORD (for Intercompany page display)
        // =====================================================================
        createIntercompanyTransactionRecord(
            ownerVa, treasurySettlementVa, ownerEntity, treasuryEntity,
            request.getAmount(), poboFee, correlationId,
            txn1_ownerDebit.getReferenceNumber(), "POBO_6LEG");

        return txn1_ownerDebit;
    }

    // ========================================================================
    // INTERCOMPANY TRANSACTION RECORD CREATION
    // ========================================================================

    /**
     * Create dual IntercompanyTransaction records for POBO payments.
     *
     * Creates TWO records for proper intercompany accounting:
     * 1. IC_RECEIVABLE - Treasury's view (Treasury is owed by Subsidiary)
     * 2. IC_PAYABLE - Subsidiary's view (Subsidiary owes Treasury)
     *
     * This ensures POBO transactions appear in the Intercompany page/screens
     * with each entity seeing their own perspective.
     *
     * @param ownerVa The owner/behalf VA (subsidiary IHB Current Account)
     * @param payerVa The payer VA (Treasury Settlement VA)
     * @param ownerEntity The owner legal entity (subsidiary)
     * @param payerEntity The payer legal entity (Treasury Center)
     * @param amount The payment amount
     * @param charges The POBO fee/charges
     * @param correlationId The correlation ID linking all transaction legs
     * @param txnReference The primary transaction reference
     * @param flowType The POBO flow type ("POBO_4LEG" or "POBO_6LEG")
     */
    private void createIntercompanyTransactionRecord(
            VirtualAccount ownerVa,
            VirtualAccount payerVa,
            LegalEntity ownerEntity,
            LegalEntity payerEntity,
            BigDecimal amount,
            BigDecimal charges,
            String correlationId,
            String txnReference,
            String flowType) {

        try {
            // Extract entity details
            UUID treasuryEntityId = payerEntity != null ? payerEntity.getId() : payerVa.getOwningEntityId();
            String treasuryEntityCode = payerEntity != null ? payerEntity.getEntityCode() : payerVa.getOwningEntityCode();
            String treasuryEntityName = payerEntity != null ? payerEntity.getEntityName() : payerVa.getVaName();

            UUID subsidiaryEntityId = ownerEntity != null ? ownerEntity.getId() : ownerVa.getOwningEntityId();
            String subsidiaryEntityCode = ownerEntity != null ? ownerEntity.getEntityCode() : ownerVa.getOwningEntityCode();
            String subsidiaryEntityName = ownerEntity != null ? ownerEntity.getEntityName() : ownerVa.getVaName();

            BigDecimal netAmount = amount.add(charges != null ? charges : BigDecimal.ZERO);
            String currencyCode = ownerVa.getCurrencyCode();

            // =====================================================================
            // RECORD 1: IC_RECEIVABLE - Treasury's perspective
            // Treasury is owed by Subsidiary (receivable increases)
            // =====================================================================
            String icReceivableRef = "ICR-" + correlationId;

            if (intercompanyTransactionRepository.findByTransactionRef(icReceivableRef).isEmpty()) {
                IntercompanyTransaction treasuryRecord = IntercompanyTransaction.builder()
                    .transactionRef(icReceivableRef)
                    .transactionType(IntercompanyTransaction.TransactionType.IC_RECEIVABLE)
                    .status(IntercompanyTransaction.TransactionStatus.PROCESSED)

                    // For IC_RECEIVABLE: Entity that owns the receivable (Treasury)
                    .payingEntityId(treasuryEntityId)
                    .payingEntityCode(treasuryEntityCode)
                    .payingEntityName(treasuryEntityName)

                    // Counterparty: Entity that owes (Subsidiary)
                    .behalfEntityId(subsidiaryEntityId)
                    .behalfEntityCode(subsidiaryEntityCode)
                    .behalfEntityName(subsidiaryEntityName)

                    // Amounts (positive = Treasury is owed this amount)
                    .amount(amount)
                    .currencyCode(currencyCode)
                    .charges(charges)
                    .netAmount(netAmount)

                    // Original reference for linking
                    .originalReference(txnReference)
                    .originalReferenceType(flowType + "_RECEIVABLE")

                    // Processing info
                    .processedAt(LocalDateTime.now())
                    .description("IC Receivable from " + subsidiaryEntityCode + " for POBO - " + txnReference)
                    .createdBy("SYSTEM")
                    .build();

                intercompanyTransactionRepository.save(treasuryRecord);
                log.info("Created IC_RECEIVABLE {} for Treasury {} (owed by {})",
                    icReceivableRef, treasuryEntityCode, subsidiaryEntityCode);
            }

            // =====================================================================
            // RECORD 2: IC_PAYABLE - Subsidiary's perspective
            // Subsidiary owes Treasury (payable increases)
            // =====================================================================
            String icPayableRef = "ICP-" + correlationId;

            if (intercompanyTransactionRepository.findByTransactionRef(icPayableRef).isEmpty()) {
                IntercompanyTransaction subsidiaryRecord = IntercompanyTransaction.builder()
                    .transactionRef(icPayableRef)
                    .transactionType(IntercompanyTransaction.TransactionType.IC_PAYABLE)
                    .status(IntercompanyTransaction.TransactionStatus.PROCESSED)

                    // For IC_PAYABLE: Entity that owes (Subsidiary)
                    .payingEntityId(subsidiaryEntityId)
                    .payingEntityCode(subsidiaryEntityCode)
                    .payingEntityName(subsidiaryEntityName)

                    // Counterparty: Entity that is owed (Treasury)
                    .behalfEntityId(treasuryEntityId)
                    .behalfEntityCode(treasuryEntityCode)
                    .behalfEntityName(treasuryEntityName)

                    // Amounts (positive = Subsidiary owes this amount)
                    .amount(amount)
                    .currencyCode(currencyCode)
                    .charges(charges)
                    .netAmount(netAmount)

                    // Original reference for linking
                    .originalReference(txnReference)
                    .originalReferenceType(flowType + "_PAYABLE")

                    // Processing info
                    .processedAt(LocalDateTime.now())
                    .description("IC Payable to " + treasuryEntityCode + " for POBO - " + txnReference)
                    .createdBy("SYSTEM")
                    .build();

                intercompanyTransactionRepository.save(subsidiaryRecord);
                log.info("Created IC_PAYABLE {} for Subsidiary {} (owes {})",
                    icPayableRef, subsidiaryEntityCode, treasuryEntityCode);
            }

            log.info("Created dual IC records for POBO {}: ICR-{} (Treasury), ICP-{} (Subsidiary)",
                txnReference, correlationId, correlationId);

            // =====================================================================
            // RECORD 3: IntercompanyRecharge - For Recharges tab display
            // Creates recharge record to track settlement obligation
            // =====================================================================
            String rechargeRef = "RCH-" + correlationId;

            if (intercompanyRechargeRepository.findByRechargeReference(rechargeRef).isEmpty()) {
                IntercompanyRecharge recharge = IntercompanyRecharge.builder()
                    .rechargeReference(rechargeRef)
                    // Payer = Treasury (who paid)
                    .payerEntityId(treasuryEntityId)
                    .payerEntityCode(treasuryEntityCode)
                    .payerEntityName(treasuryEntityName)
                    // Behalf = Subsidiary (on whose behalf)
                    .behalfEntityId(subsidiaryEntityId)
                    .behalfEntityCode(subsidiaryEntityCode)
                    .behalfEntityName(subsidiaryEntityName)
                    // Amounts
                    .originalAmount(amount)
                    .rechargeAmount(amount)
                    .currencyCode(currencyCode)
                    .serviceFee(charges != null ? charges : BigDecimal.ZERO)
                    .adminFee(BigDecimal.ZERO)
                    .fxMarkup(BigDecimal.ZERO)
                    .totalRecharge(netAmount)
                    // Status - APPROVED since payment already executed
                    .status(IntercompanyRecharge.RechargeStatus.APPROVED)
                    .approvalRequired(false)
                    .approvedBy("SYSTEM_AUTO")
                    .approvedAt(LocalDateTime.now())
                    // Type and direction
                    .rechargeType(IntercompanyRecharge.RechargeType.POBO_PAYMENT)
                    .flowDirection(IntercompanyRecharge.FlowDirection.OUTBOUND)
                    // Link to original payable (use a placeholder UUID since direct payment doesn't have payable)
                    .originalPayableId(UUID.randomUUID())
                    .originalPaymentReference(txnReference)
                    .build();

                intercompanyRechargeRepository.save(recharge);
                log.info("Created IntercompanyRecharge {} for POBO: Treasury {} paid {} {} on behalf of {}",
                    rechargeRef, treasuryEntityCode, currencyCode, amount, subsidiaryEntityCode);
            }

        } catch (Exception e) {
            // Log but don't fail the main transaction - IC records are for reporting
            log.warn("Failed to create IntercompanyTransaction records for POBO {}: {}", txnReference, e.getMessage());
        }
    }

    // ========================================================================
    // INBOUND COLLECTION - CBS to VA via Shadow VA (4-leg accounting)
    // ========================================================================

    /**
     * Process inbound collection from CBS to target VA.
     *
     * This is the SYMMETRIC OPPOSITE of makePayment():
     * - makePayment:      Source VA → Settlement VA → Shadow VA → CBS (outbound)
     *   Shadow VA balance DECREASES (money leaves physical account)
     * - processCollection: CBS → Shadow VA → Settlement VA → Target VA (inbound)
     *   Shadow VA balance INCREASES (money enters physical account)
     *
     * FLOW: CBS → Shadow VA → Settlement VA → Target VA (4 principal entries)
     *
     * Shadow VA is required as it:
     * - Mirrors the Physical Account balance (CBS received this deposit)
     * - Shadow VA balance INCREASES to reflect the physical account has more money
     * - Provides reconciliation point between physical and virtual
     *
     * ACCOUNTING FLOW (4-leg + optional fee entries):
     * | Entry | Account       | Debit  | Credit | Type                |
     * |-------|---------------|--------|--------|---------------------|
     * | 1     | Shadow VA     |        | gross  | ROBO_CREDIT (CBS receipt - balance increases) |
     * | 2     | Settlement VA |        | gross  | TRANSFER_IN (receive for distribution) |
     * | 3     | Settlement VA | gross  |        | TRANSFER_OUT (pass to Target) |
     * | 4     | Target VA     |        | net    | ROBO_CREDIT (final credit) |
     * | 5     | Target VA     | fee    |        | FEE (if applicable) |
     * | 6     | Settlement VA |        | fee    | FEE_CREDIT (if applicable) |
     *
     * Shadow VA balance INCREASES permanently (mirrors physical account).
     * Settlement VA nets to zero (pass-through clearing).
     * Fee flows from Target VA to Settlement VA.
     */
    @Transactional
    public TransactionDto.CollectionResponse processCollection(TransactionDto.CollectionRequest request) {
        log.info("Processing inbound collection: TargetVA={}, Amount={}, Remitter={}",
                request.getTargetVaId(), request.getAmount(), request.getRemitterName());

        // 1. Resolve Target VA
        VirtualAccount targetVa = virtualAccountRepository.findById(request.getTargetVaId())
                .orElseThrow(() -> new ResourceNotFoundException("Target VA not found"));

        validateVaActive(targetVa, "collection to");

        // 2. Resolve Shadow VA (CBS entry point)
        VirtualAccount shadowVa = resolveShadowVa(targetVa);
        if (shadowVa == null) {
            throw new BusinessException("No Shadow VA found for inbound collection. " +
                "Shadow VA (PHYSICAL_MIRROR) is required to link to Physical Account for CBS receipts.");
        }

        // 3. Resolve Settlement VA (clearing house) with proper exception handling (v5.2.0)
        SettlementVaResolverService.SettlementVaResolutionResult settlementResolution =
            settlementVaResolver.resolveSettlementVaWithResult(targetVa, request.getAmount(), null);

        if (!settlementResolution.isSuccess()) {
            ExceptionTransaction exceptionRecord = settlementResolution.getExceptionRaised();
            log.error("Inbound collection to {} cannot proceed: Settlement VA not configured. " +
                     "Exception: {}", targetVa.getVaNumber(), exceptionRecord.getExceptionNumber());

            throw SettlementVaException.missingSettlementVa(
                exceptionRecord.getExceptionNumber(),
                exceptionRecord.getExceptionVaId(),
                targetVa.getProgramId(),
                targetVa.getCurrencyCode()
            );
        }

        VirtualAccount settlementVa = settlementResolution.getResolvedVa();

        // 4. Calculate collection fee
        BigDecimal collectionFee = calculateCollectionFee(targetVa, request);
        BigDecimal netAmount = request.getAmount().subtract(collectionFee);

        log.info("Collection routing: CBS → {} → {} → {}, gross={}, fee={}, net={}",
            shadowVa.getVaNumber(), settlementVa.getVaNumber(), targetVa.getVaNumber(),
            request.getAmount(), collectionFee, netAmount);

        String correlationId = generateCorrelationId("COL");
        LocalDate valueDate = request.getValueDate() != null ? request.getValueDate() : LocalDate.now();
        String currency = request.getCurrencyCode() != null ? request.getCurrencyCode() : targetVa.getCurrencyCode();

        List<Transaction> allTransactions = new ArrayList<>();

        // =====================================================================
        // PRINCIPAL FLOW: Shadow VA → Settlement VA → Target VA
        // =====================================================================

        // ENTRY 1: CREDIT Shadow VA (CBS deposit received - mirrors physical account)
        // Shadow VA balance INCREASES to reflect that the physical account has more money.
        // This balance stays increased - Shadow VA mirrors the CBS physical account balance.
        BigDecimal shadowBalanceBefore = shadowVa.getCurrentBalance();
        shadowVa.applyShadowMovement(request.getAmount());
        virtualAccountRepository.save(shadowVa);

        Transaction txn1_shadowIn = Transaction.builder()
                .movementType(Transaction.MovementType.ROBO_CREDIT)
                .transactionCategory(TransactionCategory.EXTERNAL)
                .corporateId(shadowVa.getCorporateId())
                .vaId(shadowVa.getId())
                .physicalAccountId(shadowVa.getLinkedPhysicalAccountId())
                .programId(shadowVa.getProgramId())
                .amount(request.getAmount())
                .currencyCode(currency)
                .balanceBefore(shadowBalanceBefore)
                .balanceAfter(shadowVa.getCurrentBalance())
                .transactionDate(LocalDateTime.now())
                .valueDate(valueDate)
                .referenceNumber(generateReferenceNumber("CBS"))
                .description("CBS Collection from " + request.getRemitterName())
                .channel(request.getChannel() != null ? request.getChannel() : "INCOMING")
                .remitterName(request.getRemitterName())
                .remitterAccount(request.getRemitterAccount())
                .counterpartyVaId(settlementVa.getId())
                .correlationId(correlationId)
                .externalReference(request.getBankReference())
                .viban(request.getViban())
                .routedViaViban(request.getViban() != null)
                .isRobo(true)
                .status(Transaction.TransactionStatus.COMPLETED)
                .processingNotes("Inbound collection - leg 1 of 4 (CBS receipt). Physical Account: " +
                    shadowVa.getBankAccountNumber())
                .build();
        txn1_shadowIn = transactionRepository.save(txn1_shadowIn);
        allTransactions.add(txn1_shadowIn);

        // ENTRY 2: CREDIT Settlement VA (receive for distribution)
        BigDecimal settlementBalanceBefore1 = settlementVa.getCurrentBalance();
        settlementVa.setCurrentBalance(settlementBalanceBefore1.add(request.getAmount()));
        virtualAccountRepository.save(settlementVa);

        Transaction txn2_settlementIn = createSettlementTransaction(
            Transaction.MovementType.TRANSFER_IN, settlementVa, request.getAmount(),
            settlementBalanceBefore1, settlementVa.getCurrentBalance(),
            valueDate, correlationId, shadowVa.getId(),
            "Settlement received for collection from " + request.getRemitterName(),
            "Inbound collection - leg 2 of 4"
        );
        allTransactions.add(txn2_settlementIn);

        // ENTRY 3: DEBIT Settlement VA (pass to Target)
        BigDecimal settlementBalanceBefore2 = settlementVa.getCurrentBalance();
        settlementVa.setCurrentBalance(settlementBalanceBefore2.subtract(request.getAmount()));
        virtualAccountRepository.save(settlementVa);

        Transaction txn3_settlementOut = createSettlementTransaction(
            Transaction.MovementType.TRANSFER_OUT, settlementVa, request.getAmount(),
            settlementBalanceBefore2, settlementVa.getCurrentBalance(),
            valueDate, correlationId, targetVa.getId(),
            "Settlement payout for collection from " + request.getRemitterName(),
            "Inbound collection - leg 3 of 4"
        );
        allTransactions.add(txn3_settlementOut);

        // ENTRY 4: CREDIT Target VA (final destination, NET amount after fee)
        BigDecimal targetBalanceBefore = targetVa.getCurrentBalance();
        targetVa.setCurrentBalance(targetBalanceBefore.add(netAmount));
        targetVa.setAvailableBalance(targetVa.getCurrentBalance());
        targetVa.setLastTransactionAt(LocalDateTime.now());
        targetVa.setLastActivityDate(LocalDate.now());
        virtualAccountRepository.save(targetVa);

        Transaction txn4_targetIn = Transaction.builder()
                .movementType(Transaction.MovementType.ROBO_CREDIT)
                .transactionCategory(TransactionCategory.EXTERNAL)
                .corporateId(targetVa.getCorporateId())
                .legalEntityId(targetVa.getOwningEntityId())
                .vaId(targetVa.getId())
                .programId(targetVa.getProgramId())
                .amount(netAmount)
                .currencyCode(currency)
                .balanceBefore(targetBalanceBefore)
                .balanceAfter(targetVa.getCurrentBalance())
                .transactionDate(LocalDateTime.now())
                .valueDate(valueDate)
                .referenceNumber(generateReferenceNumber("COL"))
                .description("Collection from " + request.getRemitterName() +
                    (request.getRemittanceInfo() != null ? " - " + request.getRemittanceInfo() : ""))
                .channel(request.getChannel() != null ? request.getChannel() : "INCOMING")
                .remitterName(request.getRemitterName())
                .remitterAccount(request.getRemitterAccount())
                .counterpartyVaId(settlementVa.getId())
                .correlationId(correlationId)
                .externalReference(request.getBankReference())
                .viban(request.getViban())
                .routedViaViban(request.getViban() != null)
                .isRobo(true)
                .feeAmount(collectionFee)
                .autoReconciled(request.getMatchedReceivableId() != null)
                .status(Transaction.TransactionStatus.COMPLETED)
                .processingNotes("Inbound collection - leg 4 of 4 (final credit). Net after fee: " + netAmount)
                .build();
        txn4_targetIn = transactionRepository.save(txn4_targetIn);
        allTransactions.add(txn4_targetIn);

        // =====================================================================
        // FEE FLOW: Target VA → Settlement VA (if fee > 0)
        // =====================================================================
        if (collectionFee.compareTo(BigDecimal.ZERO) > 0) {
            String feeCode = getCollectionFeeCode(request);
            postSingleFee(targetVa, collectionFee, feeCode,
                "Collection Fee for " + request.getRemitterName(),
                txn4_targetIn.getId(), correlationId);
        }

        // =====================================================================
        // IC PAYABLE TRACKING: ROBO Collection - Treasury owes Subsidiary
        // =====================================================================
        // If this is a ROBO collection (Treasury collected on behalf of Subsidiary),
        // create an IC Payable entry to track Treasury's obligation to the Subsidiary.
        // This uses the Current Account model for intercompany tracking (IHB Loan/Deposit is future scope).
        if (Boolean.TRUE.equals(request.getIsRobo()) && request.getBehalfOfEntityId() != null) {
            // Find or resolve IC Payable VA for Treasury
            VirtualAccount icPayableVa = resolveIcPayableVa(settlementVa, request.getBehalfOfEntityId());

            if (icPayableVa != null) {
                // Leg 5: IC Payable increases (Treasury's obligation to Subsidiary)
                // The IC Payable VA balance increases - Treasury now owes more to the Subsidiary
                BigDecimal icPayableBalanceBefore = icPayableVa.getCurrentBalance();
                icPayableVa.setCurrentBalance(icPayableBalanceBefore.add(netAmount));
                icPayableVa.setAvailableBalance(icPayableVa.getCurrentBalance());
                virtualAccountRepository.save(icPayableVa);

                Transaction txn5_icPayable = Transaction.builder()
                    .movementType(Transaction.MovementType.IC_PAYABLE)
                    .transactionCategory(Transaction.TransactionCategory.INTERNAL)
                    .corporateId(settlementVa.getCorporateId())
                    .vaId(icPayableVa.getId())
                    .programId(icPayableVa.getProgramId())
                    .amount(netAmount)
                    .currencyCode(currency)
                    .balanceBefore(icPayableBalanceBefore)
                    .balanceAfter(icPayableVa.getCurrentBalance())
                    .transactionDate(LocalDateTime.now())
                    .valueDate(valueDate)
                    .referenceNumber(generateReferenceNumber("ICPAY"))
                    .description("IC Payable: Treasury owes " + request.getBehalfOfEntityCode())
                    .correlationId(correlationId)
                    .counterpartyVaId(targetVa.getId())
                    .isRobo(true)
                    .behalfOfEntity(request.getBehalfOfEntityCode())
                    .behalfOfVaId(targetVa.getId())
                    .channel(request.getChannel())
                    .status(Transaction.TransactionStatus.COMPLETED)
                    .processingNotes("ROBO Collection - IC Payable leg (Treasury obligation to " +
                        request.getBehalfOfEntityCode() + ")")
                    .build();
                transactionRepository.save(txn5_icPayable);
                allTransactions.add(txn5_icPayable);

                log.info("ROBO IC Payable recorded: Treasury owes {} {} to {}, IC Payable VA: {}",
                    netAmount, currency, request.getBehalfOfEntityCode(), icPayableVa.getVaNumber());
            } else {
                log.warn("ROBO collection: IC Payable VA not found for entity {}. " +
                    "IC tracking skipped - configure icPayableVaId on Settlement VA or create IC_PAYABLE special type VA.",
                    request.getBehalfOfEntityCode());
            }
        }

        log.info("Collection completed: CBS → {} → {} → {}, gross={}, fee={}, net={}, correlation={}{}",
            shadowVa.getVaNumber(), settlementVa.getVaNumber(), targetVa.getVaNumber(),
            request.getAmount(), collectionFee, netAmount, correlationId,
            Boolean.TRUE.equals(request.getIsRobo()) ? ", ROBO=true, behalfOf=" + request.getBehalfOfEntityCode() : "");

        // Propagate balance changes through hierarchy (only for transaction/operating VAs)
        // Shadow VA and Settlement VA are system accounts - their balances don't propagate to hierarchy
        propagateBalanceChanges(targetVa.getId(), netAmount);             // Target VA increased (transaction VA)

        // Build response
        return TransactionDto.CollectionResponse.builder()
                .transactionId(txn4_targetIn.getId())
                .referenceNumber(txn4_targetIn.getReferenceNumber())
                .correlationId(correlationId)
                .status("COMPLETED")
                .shadowVaId(shadowVa.getId())
                .shadowVaNumber(shadowVa.getVaNumber())
                .physicalAccountNumber(shadowVa.getBankAccountNumber())
                .targetVaId(targetVa.getId())
                .targetVaNumber(targetVa.getVaNumber())
                .targetBalanceBefore(targetBalanceBefore)
                .targetBalanceAfter(targetVa.getCurrentBalance())
                .grossAmount(request.getAmount())
                .feeAmount(collectionFee)
                .netAmount(netAmount)
                .currencyCode(currency)
                .remitterName(request.getRemitterName())
                .remitterAccount(request.getRemitterAccount())
                .viban(request.getViban())
                .routedViaViban(request.getViban() != null)
                .matchStatus(request.getMatchedReceivableId() != null ? "AUTO_MATCHED" :
                    (request.getViban() != null ? "VIBAN_ROUTED" : "UNMATCHED"))
                .matchedReceivableId(request.getMatchedReceivableId())
                .matchedInvoiceNumber(request.getInvoiceReference())
                .transactionDate(txn4_targetIn.getTransactionDate())
                .valueDate(valueDate)
                .bankReference(request.getBankReference())
                .build();
    }

    /**
     * Calculate collection fee based on routing method.
     * VIBAN routing gets lower fee than standard collection.
     */
    private BigDecimal calculateCollectionFee(VirtualAccount targetVa, TransactionDto.CollectionRequest request) {
        String feeCode = getCollectionFeeCode(request);
        String currency = request.getCurrencyCode() != null ? request.getCurrencyCode() : targetVa.getCurrencyCode();
        return calculateChargeFromConfig(feeCode, request.getAmount(), currency);
    }

    /**
     * Determine collection fee code based on routing.
     *
     * Fee hierarchy (priority order):
     * 1. ROBO collection (isRobo = true) -> ROBO_COLLECTION_FEE
     * 2. VIBAN routing (viban != null) -> VIBAN_COLLECTION_FEE (lower fee)
     * 3. Standard inbound collection -> INBOUND_COLLECTION_FEE
     */
    private String getCollectionFeeCode(TransactionDto.CollectionRequest request) {
        // Check if this is a ROBO collection first (highest priority)
        if (Boolean.TRUE.equals(request.getIsRobo())) {
            return CHARGE_ROBO_COLLECTION;  // Use ROBO_COLLECTION_FEE
        }
        if (request.getViban() != null) {
            return CHARGE_VIBAN_COLLECTION;  // Lower fee for VIBAN routing
        }
        return CHARGE_INBOUND_COLLECTION;
    }

    // ========================================================================
    // SHADOW VA RESOLUTION
    // ========================================================================

    /**
     * Resolve Shadow VA for outbound payments.
     * 
     * Resolution strategy (priority order):
     * 1. Same Physical Account - Shadow VA linked to source VA's physical account
     * 2. Same Currency Corporate - Shadow VA at corporate level for this currency
     * 3. Any Currency Match - Shadow VA in the hierarchy with matching currency
     */
    private VirtualAccount resolveShadowVa(VirtualAccount sourceVa) {
        try {
            // Strategy 1: Same Physical Account link
            if (sourceVa.getPhysicalAccountId() != null) {
                Optional<VirtualAccount> shadowVa = virtualAccountRepository
                    .findByLinkedPhysicalAccountIdAndAccountCategory(
                        sourceVa.getPhysicalAccountId(), AccountCategory.PHYSICAL_MIRROR);
                if (shadowVa.isPresent() && shadowVa.get().getStatus() == VaStatus.ACTIVE) {
                    log.debug("Resolved Shadow VA by physical account link: {}", shadowVa.get().getVaNumber());
                    return shadowVa.get();
                }
            }
            
            // Strategy 2: Same Currency at Corporate level
            List<VirtualAccount> corporateShadows = virtualAccountRepository
                .findShadowAccountsByCurrency(sourceVa.getCorporateId(), sourceVa.getCurrencyCode());
            
            Optional<VirtualAccount> activeShadow = corporateShadows.stream()
                .filter(va -> va.getStatus() == VaStatus.ACTIVE)
                .findFirst();
            if (activeShadow.isPresent()) {
                log.debug("Resolved Shadow VA by corporate currency: {}", activeShadow.get().getVaNumber());
                return activeShadow.get();
            }
            
            // Strategy 3: Any Shadow VA in corporate hierarchy
            List<VirtualAccount> allShadows = virtualAccountRepository
                .findShadowAccountsByCorporate(sourceVa.getCorporateId());
            
            Optional<VirtualAccount> matchingCurrency = allShadows.stream()
                .filter(va -> sourceVa.getCurrencyCode().equals(va.getCurrencyCode()))
                .filter(va -> va.getStatus() == VaStatus.ACTIVE)
                .findFirst();
            
            if (matchingCurrency.isPresent()) {
                log.debug("Resolved Shadow VA by currency match: {}", matchingCurrency.get().getVaNumber());
                return matchingCurrency.get();
            }
            
            // Fallback to any active shadow
            Optional<VirtualAccount> anyShadow = allShadows.stream()
                .filter(va -> va.getStatus() == VaStatus.ACTIVE)
                .findFirst();
            
            if (anyShadow.isPresent()) {
                log.warn("Using Shadow VA with different currency: {} (source: {}, shadow: {})", 
                    anyShadow.get().getVaNumber(), sourceVa.getCurrencyCode(), anyShadow.get().getCurrencyCode());
                return anyShadow.get();
            }
            
            log.warn("No Shadow VA found for corporate: {}, currency: {}",
                sourceVa.getCorporateId(), sourceVa.getCurrencyCode());

        } catch (Exception e) {
            log.error("Failed to resolve Shadow VA: {}", e.getMessage(), e);
        }

        return null;
    }

    // ========================================================================
    // IC PAYABLE VA RESOLUTION (ROBO Collections)
    // ========================================================================

    /**
     * Resolve IC Payable VA for ROBO collections.
     *
     * IC Payable VA tracks Treasury's obligation to subsidiaries when Treasury
     * receives funds on their behalf (ROBO - Receive On Behalf Of).
     *
     * Resolution strategy (priority order):
     * 1. Settlement VA's icPayableVaId - if configured, use directly
     * 2. Special Type lookup - find IC_PAYABLE VA in same program
     *
     * Similar to how IC Receivable is resolved in POBO 6-leg flow.
     *
     * @param settlementVa The Settlement VA through which the collection was processed
     * @param behalfOfEntityId The legal entity ID on whose behalf Treasury collected
     * @return The IC Payable VA if found, null otherwise
     */
    private VirtualAccount resolveIcPayableVa(VirtualAccount settlementVa, UUID behalfOfEntityId) {
        try {
            // Strategy 1: Check if Settlement VA has icPayableVaId configured
            if (settlementVa.getIcPayableVaId() != null) {
                Optional<VirtualAccount> icPayableVa = virtualAccountRepository
                    .findById(settlementVa.getIcPayableVaId());
                if (icPayableVa.isPresent() && icPayableVa.get().getStatus() == VaStatus.ACTIVE) {
                    log.debug("Resolved IC Payable VA by configured ID: {}", icPayableVa.get().getVaNumber());
                    return icPayableVa.get();
                }
            }

            // Strategy 2: Find by special type in same program
            if (settlementVa.getProgramId() != null) {
                List<VirtualAccount> icPayableVas = virtualAccountRepository
                    .findByProgramIdAndSpecialType(settlementVa.getProgramId(),
                        VirtualAccount.VaSpecialType.IC_PAYABLE);

                Optional<VirtualAccount> activeIcPayable = icPayableVas.stream()
                    .filter(va -> va.getStatus() == VaStatus.ACTIVE)
                    .findFirst();

                if (activeIcPayable.isPresent()) {
                    log.debug("Resolved IC Payable VA by special type in program: {}",
                        activeIcPayable.get().getVaNumber());
                    return activeIcPayable.get();
                }
            }

            // Strategy 3: Find by special type at corporate level (fallback)
            List<VirtualAccount> corporateIcPayables = virtualAccountRepository
                .findByProgramIdAndSpecialType(null, VirtualAccount.VaSpecialType.IC_PAYABLE);

            Optional<VirtualAccount> corporateIcPayable = corporateIcPayables.stream()
                .filter(va -> va.getCorporateId().equals(settlementVa.getCorporateId()))
                .filter(va -> va.getStatus() == VaStatus.ACTIVE)
                .filter(va -> settlementVa.getCurrencyCode().equals(va.getCurrencyCode()))
                .findFirst();

            if (corporateIcPayable.isPresent()) {
                log.debug("Resolved IC Payable VA at corporate level: {}",
                    corporateIcPayable.get().getVaNumber());
                return corporateIcPayable.get();
            }

            log.debug("No IC Payable VA found for program: {}, currency: {}, behalfOfEntity: {}",
                settlementVa.getProgramId(), settlementVa.getCurrencyCode(), behalfOfEntityId);

        } catch (Exception e) {
            log.error("Failed to resolve IC Payable VA: {}", e.getMessage(), e);
        }

        return null;
    }

    // ========================================================================
    // TRANSFER CLASSIFICATION
    // ========================================================================

    private TransferClassification classifyTransfer(VirtualAccount fromVa, VirtualAccount toVa) {
        LegalEntity fromEntity = fromVa.getOwningEntityId() != null ? 
            legalEntityRepository.findById(fromVa.getOwningEntityId()).orElse(null) : null;
        LegalEntity toEntity = toVa.getOwningEntityId() != null ? 
            legalEntityRepository.findById(toVa.getOwningEntityId()).orElse(null) : null;
        
        boolean sameProgram = Objects.equals(fromVa.getProgramId(), toVa.getProgramId());
        boolean sameLegalEntity = Objects.equals(fromVa.getOwningEntityId(), toVa.getOwningEntityId());
        boolean sameCurrency = Objects.equals(fromVa.getCurrencyCode(), toVa.getCurrencyCode());
        boolean sameCountry = (fromEntity != null && toEntity != null) ? 
            Objects.equals(fromEntity.getCountryCode(), toEntity.getCountryCode()) : true;
        
        return TransferClassification.builder()
            .sameProgram(sameProgram)
            .sameLegalEntity(sameLegalEntity)
            .sameCurrency(sameCurrency)
            .sameCountry(sameCountry)
            .isIntercompany(!sameLegalEntity && fromVa.getOwningEntityId() != null && toVa.getOwningEntityId() != null)
            .isCrossBorder(!sameCountry)
            .requiresFx(!sameCurrency)
            .fromLegalEntityId(fromVa.getOwningEntityId())
            .toLegalEntityId(toVa.getOwningEntityId())
            .fromCountry(fromEntity != null ? fromEntity.getCountryCode() : null)
            .toCountry(toEntity != null ? toEntity.getCountryCode() : null)
            .fromCurrency(fromVa.getCurrencyCode())
            .toCurrency(toVa.getCurrencyCode())
            .fromEntity(fromEntity)
            .toEntity(toEntity)
            .build();
    }

    // ========================================================================
    // FEE CALCULATION (from ChargeConfiguration - no hardcoded values)
    // ========================================================================

    private TransferFeeBreakdown calculateTransferFees(VirtualAccount fromVa, VirtualAccount toVa,
                                                        BigDecimal amount, TransferClassification classification) {
        List<FeeLineItem> lineItems = new ArrayList<>();
        BigDecimal baseFee = BigDecimal.ZERO;
        BigDecimal intercompanyFee = BigDecimal.ZERO;
        BigDecimal fxFee = BigDecimal.ZERO;
        BigDecimal crossBorderFee = BigDecimal.ZERO;
        BigDecimal treasuryServiceFee = BigDecimal.ZERO;

        // 1. BASE FEE (Program-based)
        String baseFeeCode = classification.isSameProgram() ? 
            CHARGE_SAME_PROGRAM_TRANSFER : CHARGE_CROSS_PROGRAM_TRANSFER;
        baseFee = calculateChargeFromConfig(baseFeeCode, amount, fromVa.getCurrencyCode());
        if (baseFee.compareTo(BigDecimal.ZERO) > 0) {
            FeeOwner owner = classification.isSameProgram() ? FeeOwner.SOURCE_PROGRAM : FeeOwner.CORPORATE;
            lineItems.add(FeeLineItem.builder()
                .chargeCode(baseFeeCode)
                .chargeName(classification.isSameProgram() ? "Same Program Transfer Fee" : "Cross Program Transfer Fee")
                .amount(baseFee)
                .currency(fromVa.getCurrencyCode())
                .waived(false)
                .feeOwner(owner)
                .build());
        }

        // 2. INTERCOMPANY FEE
        if (classification.isIntercompany()) {
            boolean waived = shouldWaiveIntercompanyFee(classification);
            BigDecimal calculatedFee = calculateChargeFromConfig(CHARGE_INTERCOMPANY_TRANSFER, amount, fromVa.getCurrencyCode());
            
            if (waived) {
                lineItems.add(FeeLineItem.builder()
                    .chargeCode(CHARGE_INTERCOMPANY_TRANSFER)
                    .chargeName("Intercompany Transfer Fee")
                    .amount(calculatedFee)
                    .currency(fromVa.getCurrencyCode())
                    .waived(true)
                    .waiverReason("Parent-subsidiary or Treasury Center exemption")
                    .feeOwner(FeeOwner.CORPORATE)
                    .build());
            } else if (calculatedFee.compareTo(BigDecimal.ZERO) > 0) {
                intercompanyFee = calculatedFee;
                lineItems.add(FeeLineItem.builder()
                    .chargeCode(CHARGE_INTERCOMPANY_TRANSFER)
                    .chargeName("Intercompany Transfer Fee")
                    .amount(intercompanyFee)
                    .currency(fromVa.getCurrencyCode())
                    .waived(false)
                    .feeOwner(FeeOwner.CORPORATE)
                    .build());
            }
        }

        // 3. FX FEE
        if (classification.isRequiresFx()) {
            fxFee = calculateChargeFromConfig(CHARGE_FX_CONVERSION, amount, fromVa.getCurrencyCode());
            if (fxFee.compareTo(BigDecimal.ZERO) > 0) {
                lineItems.add(FeeLineItem.builder()
                    .chargeCode(CHARGE_FX_CONVERSION)
                    .chargeName("FX Conversion Fee")
                    .amount(fxFee)
                    .currency(fromVa.getCurrencyCode())
                    .waived(false)
                    .feeOwner(FeeOwner.TREASURY_CENTER)
                    .build());
            }
        }

        // 4. CROSS-BORDER FEE
        if (classification.isCrossBorder()) {
            crossBorderFee = calculateChargeFromConfig(CHARGE_CROSS_BORDER, amount, fromVa.getCurrencyCode());
            if (crossBorderFee.compareTo(BigDecimal.ZERO) > 0) {
                lineItems.add(FeeLineItem.builder()
                    .chargeCode(CHARGE_CROSS_BORDER)
                    .chargeName("Cross-Border Fee")
                    .amount(crossBorderFee)
                    .currency(fromVa.getCurrencyCode())
                    .waived(false)
                    .feeOwner(FeeOwner.CORPORATE)
                    .build());
            }
        }

        // 5. TREASURY SERVICE FEE (for intercompany)
        if (classification.isIntercompany()) {
            treasuryServiceFee = calculateChargeFromConfig(CHARGE_TREASURY_SERVICE, amount, fromVa.getCurrencyCode());
            if (treasuryServiceFee.compareTo(BigDecimal.ZERO) > 0) {
                lineItems.add(FeeLineItem.builder()
                    .chargeCode(CHARGE_TREASURY_SERVICE)
                    .chargeName("Treasury Service Fee")
                    .amount(treasuryServiceFee)
                    .currency(fromVa.getCurrencyCode())
                    .waived(false)
                    .feeOwner(FeeOwner.TREASURY_CENTER)
                    .build());
            }
        }

        BigDecimal totalFee = baseFee.add(intercompanyFee).add(fxFee).add(crossBorderFee).add(treasuryServiceFee);

        return TransferFeeBreakdown.builder()
            .baseFee(baseFee)
            .intercompanyFee(intercompanyFee)
            .fxFee(fxFee)
            .crossBorderFee(crossBorderFee)
            .treasuryServiceFee(treasuryServiceFee)
            .totalFee(totalFee)
            .lineItems(lineItems)
            .build();
    }

    private BigDecimal calculatePaymentFee(VirtualAccount fromVa, TransactionDto.PaymentRequest request) {
        String feeCode = getPaymentFeeCode(request.getChannel());
        return calculateChargeFromConfig(feeCode, request.getAmount(), fromVa.getCurrencyCode());
    }

    private BigDecimal calculatePoboFee(BigDecimal amount, String currency) {
        return calculateChargeFromConfig(CHARGE_POBO_FEE, amount, currency);
    }

    private String getPaymentFeeCode(String channel) {
        if (channel == null) return CHARGE_OUTBOUND_PAYMENT;
        return switch (channel.toUpperCase()) {
            case "SWIFT" -> CHARGE_SWIFT_PAYMENT;
            case "RTGS" -> CHARGE_RTGS_PAYMENT;
            case "SEPA" -> CHARGE_SEPA_PAYMENT;
            default -> CHARGE_OUTBOUND_PAYMENT;
        };
    }

    /**
     * Calculate charge amount from ChargeConfiguration.
     * Returns ZERO if configuration not found (no default/fallback values).
     */
    private BigDecimal calculateChargeFromConfig(String chargeCode, BigDecimal amount, String currency) {
        try {
            Optional<ChargeConfiguration> configOpt = chargeConfigRepository.findActiveByCode(chargeCode);
            
            if (configOpt.isPresent()) {
                ChargeConfiguration config = configOpt.get();
                BigDecimal calculatedFee = config.calculateCharge(amount);
                log.debug("Calculated fee for {}: {} {} (config: {})", 
                    chargeCode, calculatedFee, currency, config.getChargeName());
                return calculatedFee;
            }
            
            log.debug("No active ChargeConfiguration found for code: {}", chargeCode);
            return BigDecimal.ZERO;
            
        } catch (Exception e) {
            log.warn("Failed to calculate charge {}: {}", chargeCode, e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    private boolean shouldWaiveIntercompanyFee(TransferClassification classification) {
        LegalEntity fromEntity = classification.getFromEntity();
        LegalEntity toEntity = classification.getToEntity();
        
        if (fromEntity == null || toEntity == null) return false;
        
        // Waive if parent-subsidiary relationship
        if (fromEntity.getId().equals(toEntity.getParentEntityId()) ||
            toEntity.getId().equals(fromEntity.getParentEntityId())) {
            return true;
        }
        
        // Waive if Treasury Center is involved
        if ((fromEntity.getIsTreasuryCenter() != null && fromEntity.getIsTreasuryCenter()) ||
            (toEntity.getIsTreasuryCenter() != null && toEntity.getIsTreasuryCenter())) {
            return true;
        }
        
        return false;
    }

    // ========================================================================
    // FEE POSTING - TO SETTLEMENT VA
    // ========================================================================

    private void postTransferFees(VirtualAccount sourceVa, VirtualAccount destVa,
                                   TransferFeeBreakdown feeBreakdown,
                                   Transaction sourceTransaction, String correlationId) {
        for (FeeLineItem lineItem : feeBreakdown.getLineItems()) {
            if (lineItem.isWaived()) {
                log.debug("Skipping waived fee: {} = {}", lineItem.getChargeCode(), lineItem.getAmount());
                continue;
            }
            
            if (lineItem.getAmount() == null || lineItem.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            
            try {
                VirtualAccount targetSettlementVa = resolveSettlementVaForFeeOwner(
                    sourceVa, destVa, lineItem.getFeeOwner());
                
                FeePostingService.FeePostingResult result = feePostingService.postFeePairToTarget(
                    sourceVa.getId(),
                    targetSettlementVa.getId(),
                    lineItem.getAmount(),
                    lineItem.getChargeCode(),
                    sourceTransaction.getId(),
                    correlationId,
                    lineItem.getChargeName() + " - " + sourceTransaction.getReferenceNumber()
                );
                
                if ("POSTED".equals(result.getStatus())) {
                    log.info("Posted fee: {} = {} → {} [owner: {}]", 
                        lineItem.getChargeCode(), lineItem.getAmount(),
                        result.getSettlementVaNumber(), lineItem.getFeeOwner());
                }
                
            } catch (Exception e) {
                log.error("RECONCILIATION_ALERT: Failed to post fee {} for txn {}: {}", 
                    lineItem.getChargeCode(), sourceTransaction.getReferenceNumber(), e.getMessage());
            }
        }
    }

    /**
     * Post a single fee from source VA to Settlement VA.
     * Updated (v5.2.0): Handles missing Settlement VA by posting to Exception VA.
     */
    private void postSingleFee(VirtualAccount sourceVa, BigDecimal amount, String feeCode,
                               String feeName, UUID referenceId, String correlationId) {
        try {
            // Use new resolution method with proper exception handling
            SettlementVaResolverService.SettlementVaResolutionResult result =
                settlementVaResolver.resolveSettlementVaWithResult(sourceVa, amount, referenceId);

            VirtualAccount targetVa = result.getResolvedVa();

            if (!result.isSuccess()) {
                log.warn("Fee posting: Settlement VA not found. Posting fee {} to Exception VA {} instead. " +
                         "Exception: {}", feeCode, targetVa.getVaNumber(),
                    result.getExceptionRaised().getExceptionNumber());
            }

            feePostingService.postFeePairToTarget(
                sourceVa.getId(),
                targetVa.getId(),
                amount,
                feeCode,
                referenceId,
                correlationId,
                feeName
            );

            if (result.isSuccess()) {
                log.info("Posted fee: {} = {} → {}", feeCode, amount, targetVa.getVaNumber());
            } else {
                log.warn("Fee {} = {} posted to Exception VA {} (Settlement VA not configured)",
                    feeCode, amount, targetVa.getVaNumber());
            }

        } catch (Exception e) {
            log.error("Failed to post fee: {}", e.getMessage());
        }
    }

    /**
     * Resolve Settlement VA for fee owner.
     * Updated (v5.2.0): Now uses deprecated methods which return Exception VA as fallback.
     * Fee posting to Exception VA is acceptable - it will be tracked and resolved by operations.
     */
    private VirtualAccount resolveSettlementVaForFeeOwner(VirtualAccount sourceVa,
                                                           VirtualAccount destVa,
                                                           FeeOwner feeOwner) {
        if (feeOwner == null) {
            feeOwner = FeeOwner.SOURCE_PROGRAM;
        }

        // Note: getOrCreateSettlementVa is deprecated and now returns Exception VA as fallback
        // This is acceptable for fee posting - fees will be tracked in Exception VA
        // and operations team will resolve them when Settlement VA is created

        switch (feeOwner) {
            case DESTINATION_PROGRAM:
                return settlementVaResolver.getOrCreateSettlementVa(
                    destVa.getProgramId(),
                    destVa.getCurrencyCode(),
                    destVa.getCorporateId()
                );

            case CORPORATE:
                return settlementVaResolver.getOrCreateSettlementVa(
                    null,  // Corporate level
                    sourceVa.getCurrencyCode(),
                    sourceVa.getCorporateId()
                );

            case TREASURY_CENTER:
                VirtualAccount treasurySettlement = resolveTreasurySettlementVa(sourceVa);
                if (treasurySettlement != null) {
                    return treasurySettlement;
                }
                // Fallback to corporate
                return settlementVaResolver.getOrCreateSettlementVa(
                    null,
                    sourceVa.getCurrencyCode(),
                    sourceVa.getCorporateId()
                );

            case SOURCE_PROGRAM:
            default:
                // Use the new resolution method for source program
                SettlementVaResolverService.SettlementVaResolutionResult result =
                    settlementVaResolver.resolveSettlementVaWithResult(sourceVa);
                if (!result.isSuccess()) {
                    log.warn("Fee posting: Settlement VA not found for source program. " +
                             "Using Exception VA as fallback. Exception: {}",
                        result.getExceptionRaised().getExceptionNumber());
                }
                return result.getResolvedVa();
        }
    }

    private VirtualAccount resolveTreasurySettlementVa(VirtualAccount sourceVa) {
        try {
            List<LegalEntity> treasuryCenters = legalEntityRepository
                .findTreasuryCenters(sourceVa.getCorporateId());

            if (!treasuryCenters.isEmpty()) {
                LegalEntity tc = treasuryCenters.get(0);
                // Use deprecated method - returns Exception VA as fallback
                return settlementVaResolver.getOrCreateSettlementVa(
                    tc.getProgramId(),
                    sourceVa.getCurrencyCode(),
                    sourceVa.getCorporateId()
                );
            }
        } catch (Exception e) {
            log.warn("Failed to resolve Treasury Settlement VA: {}", e.getMessage());
        }
        return null;
    }

    // ========================================================================
    // EXCEPTION PARKING (v5.2.0 - Missing Settlement VA handling)
    // ========================================================================

    /**
     * Park a transfer in Exception VA when Settlement VA is not configured.
     * This creates an EXCEPTION_PARK transaction in the Exception VA.
     *
     * @param fromVa Source VA
     * @param toVa Destination VA
     * @param request Original transfer request
     * @param resolution Settlement VA resolution result (contains Exception VA and exception record)
     * @return Transaction record for the parked transfer
     */
    private Transaction parkTransferInException(
            VirtualAccount fromVa, VirtualAccount toVa,
            TransactionDto.TransferRequest request,
            SettlementVaResolverService.SettlementVaResolutionResult resolution) {

        VirtualAccount exceptionVa = resolution.getResolvedVa();
        ExceptionTransaction exception = resolution.getExceptionRaised();

        String correlationId = generateCorrelationId("PARK");
        LocalDate valueDate = request.getValueDate() != null ? request.getValueDate() : LocalDate.now();

        // Update the exception record with the transaction amount
        exception.setAmount(request.getAmount());
        exception.setDescription(String.format(
            "Transfer from %s to %s parked due to missing Settlement VA. Amount: %s %s. " +
            "Settlement VA must be configured for program %s currency %s.",
            fromVa.getVaNumber(), toVa.getVaNumber(),
            request.getAmount(), fromVa.getCurrencyCode(),
            fromVa.getProgramId(), fromVa.getCurrencyCode()));

        // Note: Exception is already saved by SettlementVaResolverService,
        // but we update it with the actual amount
        // exceptionRepository.save(exception); // Would need to inject this

        // Create EXCEPTION_PARK transaction in Exception VA
        BigDecimal exceptionBalanceBefore = exceptionVa.getCurrentBalance();
        exceptionVa.setCurrentBalance(exceptionBalanceBefore.add(request.getAmount()));
        virtualAccountRepository.save(exceptionVa);

        Transaction parkedTxn = Transaction.builder()
            .movementType(Transaction.MovementType.EXCEPTION_PARK)
            .transactionCategory(TransactionCategory.INTERNAL)
            .corporateId(fromVa.getCorporateId())
            .legalEntityId(fromVa.getOwningEntityId())
            .vaId(exceptionVa.getId())
            .programId(fromVa.getProgramId())
            .amount(request.getAmount())
            .currencyCode(fromVa.getCurrencyCode())
            .balanceBefore(exceptionBalanceBefore)
            .balanceAfter(exceptionVa.getCurrentBalance())
            .transactionDate(LocalDateTime.now())
            .valueDate(valueDate)
            .referenceNumber(generateReferenceNumber("PARK"))
            .description(String.format(
                "PARKED: Transfer %s → %s (Missing Settlement VA). Exception: %s",
                fromVa.getVaNumber(), toVa.getVaNumber(), exception.getExceptionNumber()))
            .channel("INTERNAL")
            .counterpartyVaId(fromVa.getId())
            .correlationId(correlationId)
            .status(Transaction.TransactionStatus.PENDING) // Pending until exception is resolved
            .processingNotes("Transfer parked in Exception VA due to missing Settlement VA configuration. " +
                           "Exception ID: " + exception.getId() + ". " +
                           "Resolve by creating Settlement VA and manually processing this transfer.")
            .build();

        parkedTxn = transactionRepository.save(parkedTxn);

        log.warn("TRANSFER PARKED: {} → {} parked in Exception VA {} due to missing Settlement VA. " +
                 "Transaction: {}, Exception: {}, Amount: {} {}",
            fromVa.getVaNumber(), toVa.getVaNumber(), exceptionVa.getVaNumber(),
            parkedTxn.getReferenceNumber(), exception.getExceptionNumber(),
            request.getAmount(), fromVa.getCurrencyCode());

        return parkedTxn;
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private void validateVaActive(VirtualAccount va, String operation) {
        if (va.getStatus() != VaStatus.ACTIVE) {
            throw new BusinessException("Virtual account is not active for " + operation + ": " + va.getStatus());
        }
    }

    private Transaction createTransaction(Transaction.MovementType movementType,
                                           TransactionCategory category,
                                           VirtualAccount va, BigDecimal amount,
                                           BigDecimal balanceBefore, BigDecimal balanceAfter,
                                           LocalDate valueDate, String correlationId,
                                           String description, UUID counterpartyVaId,
                                           BigDecimal feeAmount, String feeBreakdown,
                                           String processingNotes) {
        Transaction txn = Transaction.builder()
                .movementType(movementType)
                .transactionCategory(category)
                .corporateId(va.getCorporateId())
                .legalEntityId(va.getOwningEntityId())
                .vaId(va.getId())
                .programId(va.getProgramId())
                .amount(amount)
                .currencyCode(va.getCurrencyCode())
                .balanceBefore(balanceBefore)
                .balanceAfter(balanceAfter)
                .transactionDate(LocalDateTime.now())
                .valueDate(valueDate)
                .referenceNumber(generateReferenceNumber("TXN"))
                .description(description)
                .channel("INTERNAL")
                .counterpartyVaId(counterpartyVaId)
                .correlationId(correlationId)
                .feeAmount(feeAmount)
                .feeBreakdown(feeBreakdown)
                .status(Transaction.TransactionStatus.COMPLETED)
                .processingNotes(processingNotes)
                .build();
        return transactionRepository.save(txn);
    }

    private Transaction createSettlementTransaction(Transaction.MovementType movementType,
                                                     VirtualAccount settlementVa, BigDecimal amount,
                                                     BigDecimal balanceBefore, BigDecimal balanceAfter,
                                                     LocalDate valueDate, String correlationId,
                                                     UUID counterpartyVaId, String description,
                                                     String processingNotes) {
        Transaction txn = Transaction.builder()
                .movementType(movementType)
                .transactionCategory(TransactionCategory.INTERNAL)
                .corporateId(settlementVa.getCorporateId())
                .vaId(settlementVa.getId())
                .programId(settlementVa.getProgramId())
                .amount(amount)
                .currencyCode(settlementVa.getCurrencyCode())
                .balanceBefore(balanceBefore)
                .balanceAfter(balanceAfter)
                .transactionDate(LocalDateTime.now())
                .valueDate(valueDate)
                .referenceNumber(generateReferenceNumber("SET"))
                .description(description)
                .channel("INTERNAL")
                .counterpartyVaId(counterpartyVaId)
                .correlationId(correlationId)
                .status(Transaction.TransactionStatus.COMPLETED)
                .processingNotes(processingNotes)
                .build();
        return transactionRepository.save(txn);
    }

    private String buildTransferDescription(VirtualAccount toVa, TransferClassification classification) {
        StringBuilder desc = new StringBuilder("Transfer to " + toVa.getVaNumber());
        
        if (classification.isIntercompany()) {
            desc.append(" (Intercompany)");
        }
        if (classification.isCrossBorder()) {
            desc.append(" (Cross-border)");
        }
        if (classification.isRequiresFx()) {
            desc.append(" (FX: ").append(classification.getFromCurrency())
                .append("→").append(classification.getToCurrency()).append(")");
        }
        
        return desc.toString();
    }

    private void updatePayableStatus(UUID payableId, String paymentReference) {
        try {
            payableRepository.findById(payableId).ifPresent(payable -> {
                // Use the markPoboExecuted method or directly set status
                payable.setStatus(Payable.PayableStatus.PAID);
                payable.setPaymentStatus(Payable.PaymentStatus.PAID);
                payable.setPaidAmount(payable.getNetAmount());
                payable.setOutstandingAmount(java.math.BigDecimal.ZERO);
                payable.setPoboTransactionRef(paymentReference);
                payableRepository.save(payable);
                log.info("Updated payable {} status to PAID", payableId);
            });
        } catch (Exception e) {
            log.warn("Failed to update payable status: {}", e.getMessage());
        }
    }

    private String serializeFeeBreakdown(TransferFeeBreakdown feeBreakdown) {
        try {
            return objectMapper.writeValueAsString(feeBreakdown.getLineItems());
        } catch (Exception e) {
            log.warn("Failed to serialize fee breakdown: {}", e.getMessage());
            return null;
        }
    }

    private String generateReferenceNumber(String prefix) {
        return prefix + System.currentTimeMillis() + String.format("%04d", (int)(Math.random() * 10000));
    }
    
    private String generateCorrelationId(String prefix) {
        return prefix + "-" + System.currentTimeMillis() + "-" + 
               String.format("%04d", (int)(Math.random() * 10000));
    }

    private void updateLimitUtilizationSafe(UUID vaId, BigDecimal amount) {
        try {
            fundsAvailabilityService.updateLimitUtilization(vaId, amount);
        } catch (Exception e) {
            log.warn("Failed to update limit utilization: {}", e.getMessage());
        }
    }

    private void releaseLimitUtilizationSafe(UUID vaId, BigDecimal amount) {
        try {
            fundsAvailabilityService.releaseLimitUtilization(vaId, amount);
        } catch (Exception e) {
            log.warn("Failed to release limit utilization: {}", e.getMessage());
        }
    }

    // ========================================================================
    // BALANCE PROPAGATION
    // ========================================================================

    /**
     * Propagate balance changes through the VA hierarchy.
     * Called after a VA's balance is updated to ensure parent aggregations reflect the change.
     *
     * Uses tiered propagation:
     * - L7→L5: Real-time (immediate)
     * - L4→L1: Scheduled (via async job every 5 minutes)
     *
     * @param vaId The VA whose balance changed
     * @param delta The change amount (positive for increases, negative for decreases)
     */
    private void propagateBalanceChanges(UUID vaId, BigDecimal delta) {
        try {
            if (delta == null || delta.compareTo(BigDecimal.ZERO) == 0) {
                log.debug("Skipping propagation for VA {}: delta is null or zero", vaId);
                return; // No change to propagate
            }
            log.info("Propagating balance change for VA {}: delta={}", vaId, delta);
            balanceAggregationService.propagateBalanceChange(vaId, delta);
            log.info("Successfully propagated balance change for VA {}: delta={}", vaId, delta);
        } catch (Exception e) {
            // Log but don't fail transaction - balance aggregation is eventually consistent
            log.error("Failed to propagate balance change for VA {}: {}", vaId, e.getMessage(), e);
        }
    }

    // ========================================================================
    // GROUPED BUSINESS VIEW - Option B (Simplified View)
    // ========================================================================

    /**
     * Get transactions grouped by correlationId for a simplified business view.
     * Multi-leg transactions (4-leg collections/payments) appear as single line items
     * showing net effect to user's account.
     *
     * @param vaId Optional VA ID to filter by
     * @param corporateId Optional corporate ID to filter by
     * @param direction Optional direction filter (INBOUND, OUTBOUND, ALL)
     * @param includeInternal If true, include Shadow VA and Settlement VA entries in accounting breakdown
     * @param page Page number
     * @param pageSize Page size
     */
    @Transactional(readOnly = true)
    public TransactionDto.GroupedTransactionListResponse getGroupedTransactions(
            UUID vaId, UUID corporateId, String direction, boolean includeInternal, int page, int pageSize) {

        List<TransactionDto.GroupedTransactionResponse> groupedList = new ArrayList<>();
        long totalElements;

        // Get distinct correlation IDs with pagination
        Pageable pageable = PageRequest.of(page, pageSize);
        List<String> correlationIds;

        if (vaId != null) {
            // VA-specific query
            correlationIds = transactionRepository.findDistinctCorrelationIdsByVaId(vaId, pageable);
            totalElements = transactionRepository.countDistinctCorrelationIdsByVaId(vaId);
        } else if (corporateId != null) {
            // Corporate-wide query
            correlationIds = transactionRepository.findDistinctCorrelationIdsByCorporateId(corporateId, pageable);
            totalElements = transactionRepository.countDistinctCorrelationIdsByCorporateId(corporateId);
        } else {
            // All transactions - fallback to recent
            Page<Transaction> recentPage = transactionRepository.findRecentTransactions(pageable);
            correlationIds = recentPage.getContent().stream()
                .map(Transaction::getCorrelationId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
            totalElements = recentPage.getTotalElements();
        }

        // Build grouped responses for each correlation ID
        for (String correlationId : correlationIds) {
            if (correlationId == null) continue;

            TransactionDto.GroupedTransactionResponse grouped =
                buildGroupedTransaction(correlationId, vaId, includeInternal);

            // Apply direction filter
            if (direction != null && !direction.isEmpty() && !"ALL".equalsIgnoreCase(direction)) {
                if (!direction.equalsIgnoreCase(grouped.getDirection())) {
                    continue;
                }
            }

            groupedList.add(grouped);
        }

        // Calculate summary
        TransactionDto.GroupedTransactionSummary summary = calculateGroupedSummary(groupedList);

        return TransactionDto.GroupedTransactionListResponse.builder()
            .content(groupedList)
            .page(page)
            .pageSize(pageSize)
            .totalElements(totalElements)
            .totalPages((int) Math.ceil((double) totalElements / pageSize))
            .summary(summary)
            .build();
    }

    /**
     * Get a single grouped transaction by correlation ID with all accounting entries.
     *
     * @param correlationId The correlation ID to lookup
     * @param userVaId Optional user's VA ID to identify the primary transaction
     * @param includeInternal If true, include Shadow VA and Settlement VA entries for audit trail
     */
    @Transactional(readOnly = true)
    public TransactionDto.GroupedTransactionResponse getGroupedTransactionByCorrelationId(
            String correlationId, UUID userVaId, boolean includeInternal) {

        return buildGroupedTransaction(correlationId, userVaId, includeInternal);
    }

    /**
     * Build a grouped transaction response from all transactions sharing a correlationId.
     *
     * @param correlationId The correlation ID grouping related transactions
     * @param userVaId Optional user's VA ID to identify the primary transaction
     * @param includeInternal If true, include Shadow VA and Settlement VA entries in accounting breakdown
     */
    private TransactionDto.GroupedTransactionResponse buildGroupedTransaction(
            String correlationId, UUID userVaId, boolean includeInternal) {

        List<Transaction> allLegs = transactionRepository.findAllByCorrelationId(correlationId);

        if (allLegs.isEmpty()) {
            throw new ResourceNotFoundException("No transactions found for correlationId: " + correlationId);
        }

        // Find the primary transaction (the one affecting the user's account)
        Transaction primaryTxn = findPrimaryTransaction(allLegs, userVaId);

        // Get VA details
        VirtualAccount userVa = virtualAccountRepository.findById(primaryTxn.getVaId()).orElse(null);
        String userVaNumber = userVa != null ? userVa.getVaNumber() : null;
        String userVaName = userVa != null ? userVa.getVaName() : null;

        // Determine operation type and direction
        String operationType = determineOperationType(primaryTxn, allLegs);
        String directionStr = determineDirection(primaryTxn);
        boolean isCredit = isCrediting(primaryTxn.getMovementType());

        // Calculate amounts
        BigDecimal grossAmount = calculateGrossAmount(allLegs, primaryTxn);
        BigDecimal feeAmount = calculateTotalFees(allLegs);
        BigDecimal netAmount = primaryTxn.getAmount();

        // Get counterparty info
        String counterpartyName = getCounterpartyName(primaryTxn, allLegs);
        String counterpartyAccount = getCounterpartyAccount(primaryTxn, allLegs);
        String counterpartyType = determineCounterpartyType(allLegs);

        // Build accounting entries list
        // - Default (includeInternal=false): Only operating VA entries (CFO view)
        // - With includeInternal=true: All entries including Shadow VA and Settlement VA (audit trail)
        List<TransactionDto.AccountingEntryResponse> accountingEntries =
            buildAccountingEntries(allLegs, includeInternal);

        // Entry count shows all legs, but visible entries depends on includeInternal
        int totalLegCount = allLegs.size();
        int visibleEntryCount = accountingEntries.size();

        return TransactionDto.GroupedTransactionResponse.builder()
            .correlationId(correlationId)
            .primaryReferenceNumber(primaryTxn.getReferenceNumber())
            .primaryTransactionId(primaryTxn.getId())
            .operationType(operationType)
            .direction(directionStr)
            .userVaId(primaryTxn.getVaId())
            .userVaNumber(userVaNumber)
            .userVaName(userVaName)
            .netAmount(netAmount)
            .grossAmount(grossAmount)
            .feeAmount(feeAmount)
            .currencyCode(primaryTxn.getCurrencyCode())
            .isCredit(isCredit)
            .balanceBefore(primaryTxn.getBalanceBefore())
            .balanceAfter(primaryTxn.getBalanceAfter())
            .counterpartyName(counterpartyName)
            .counterpartyAccount(counterpartyAccount)
            .counterpartyType(counterpartyType)
            .description(primaryTxn.getDescription())
            .channel(primaryTxn.getChannel())
            .status(primaryTxn.getStatus() != null ? primaryTxn.getStatus().name() : null)
            .transactionDate(primaryTxn.getTransactionDate())
            .valueDate(primaryTxn.getValueDate())
            .isPobo(primaryTxn.getIsPobo())
            .behalfOfEntity(primaryTxn.getBehalfOfEntity())
            .behalfOfVaId(primaryTxn.getBehalfOfVaId())
            .externalReference(primaryTxn.getExternalReference())
            .bankReference(primaryTxn.getBancsReference())
            .entryCount(totalLegCount)  // Total accounting legs (including internal)
            .accountingEntries(accountingEntries)  // Filtered based on includeInternal
            .build();
    }

    /**
     * Find the primary transaction - the one that affects the user's account.
     * For collections: ROBO_CREDIT to Target VA
     * For payments: DEBIT/POBO_DEBIT from Source VA
     */
    private Transaction findPrimaryTransaction(List<Transaction> allLegs, UUID userVaId) {
        // If user VA is specified, find the transaction affecting that VA
        if (userVaId != null) {
            for (Transaction txn : allLegs) {
                if (userVaId.equals(txn.getVaId())) {
                    // Prefer the main business entry (not fee entries)
                    if (txn.getMovementType() != Transaction.MovementType.FEE &&
                        txn.getMovementType() != Transaction.MovementType.FEE_CREDIT) {
                        return txn;
                    }
                }
            }
            // Fallback to any transaction for that VA
            for (Transaction txn : allLegs) {
                if (userVaId.equals(txn.getVaId())) {
                    return txn;
                }
            }
        }

        // Otherwise, find by movement type priority
        // Priority: ROBO_CREDIT (collection), DEBIT/POBO_DEBIT (payment), CREDIT, TRANSFER_IN
        Transaction.MovementType[] priorities = {
            Transaction.MovementType.ROBO_CREDIT,
            Transaction.MovementType.DEBIT,
            Transaction.MovementType.POBO_DEBIT,
            Transaction.MovementType.CREDIT,
            Transaction.MovementType.TRANSFER_IN,
            Transaction.MovementType.TRANSFER_OUT
        };

        for (Transaction.MovementType type : priorities) {
            for (Transaction txn : allLegs) {
                if (txn.getMovementType() == type) {
                    // Skip Settlement VA and Shadow VA entries
                    VirtualAccount va = virtualAccountRepository.findById(txn.getVaId()).orElse(null);
                    if (va != null &&
                        va.getAccountCategory() != AccountCategory.SETTLEMENT &&
                        va.getAccountCategory() != AccountCategory.PHYSICAL_MIRROR) {
                        return txn;
                    }
                }
            }
        }

        // Fallback to first non-settlement transaction
        for (Transaction txn : allLegs) {
            VirtualAccount va = virtualAccountRepository.findById(txn.getVaId()).orElse(null);
            if (va != null &&
                va.getAccountCategory() != AccountCategory.SETTLEMENT &&
                va.getAccountCategory() != AccountCategory.PHYSICAL_MIRROR) {
                return txn;
            }
        }

        // Last resort: first transaction
        return allLegs.get(0);
    }

    private String determineOperationType(Transaction primaryTxn, List<Transaction> allLegs) {
        if (primaryTxn.getIsPobo() != null && primaryTxn.getIsPobo()) {
            return "POBO_PAYMENT";
        }
        if (primaryTxn.getIsRobo() != null && primaryTxn.getIsRobo()) {
            return "COLLECTION";
        }

        Transaction.MovementType type = primaryTxn.getMovementType();
        if (type == Transaction.MovementType.ROBO_CREDIT) {
            return "COLLECTION";
        }
        if (type == Transaction.MovementType.DEBIT || type == Transaction.MovementType.POBO_DEBIT) {
            return "PAYMENT";
        }
        if (type == Transaction.MovementType.TRANSFER_IN || type == Transaction.MovementType.TRANSFER_OUT) {
            return "TRANSFER";
        }
        if (type == Transaction.MovementType.FEE || type == Transaction.MovementType.FEE_CREDIT) {
            return "FEE";
        }

        return "OTHER";
    }

    private String determineDirection(Transaction primaryTxn) {
        if (isCrediting(primaryTxn.getMovementType())) {
            return "INBOUND";
        }
        return "OUTBOUND";
    }

    private boolean isCrediting(Transaction.MovementType type) {
        return type == Transaction.MovementType.CREDIT ||
               type == Transaction.MovementType.ROBO_CREDIT ||
               type == Transaction.MovementType.TRANSFER_IN ||
               type == Transaction.MovementType.FEE_CREDIT ||
               type == Transaction.MovementType.REVERSAL;
    }

    private BigDecimal calculateGrossAmount(List<Transaction> allLegs, Transaction primaryTxn) {
        // For collections: gross = Shadow VA credit amount
        // For payments: gross = Source VA debit amount
        for (Transaction txn : allLegs) {
            VirtualAccount va = virtualAccountRepository.findById(txn.getVaId()).orElse(null);
            if (va != null && va.getAccountCategory() == AccountCategory.PHYSICAL_MIRROR) {
                return txn.getAmount();
            }
        }
        // Fallback to primary transaction amount
        return primaryTxn.getAmount();
    }

    private BigDecimal calculateTotalFees(List<Transaction> allLegs) {
        BigDecimal totalFees = BigDecimal.ZERO;
        for (Transaction txn : allLegs) {
            if (txn.getMovementType() == Transaction.MovementType.FEE) {
                totalFees = totalFees.add(txn.getAmount());
            }
            if (txn.getFeeAmount() != null) {
                totalFees = totalFees.add(txn.getFeeAmount());
            }
        }
        return totalFees;
    }

    private String getCounterpartyName(Transaction primaryTxn, List<Transaction> allLegs) {
        // Check primary transaction first
        if (primaryTxn.getRemitterName() != null) {
            return primaryTxn.getRemitterName();
        }
        if (primaryTxn.getBeneficiaryName() != null) {
            return primaryTxn.getBeneficiaryName();
        }

        // Check counterparty VA
        if (primaryTxn.getCounterpartyVaId() != null) {
            VirtualAccount counterpartyVa = virtualAccountRepository
                .findById(primaryTxn.getCounterpartyVaId()).orElse(null);
            if (counterpartyVa != null) {
                return counterpartyVa.getVaName();
            }
        }

        return null;
    }

    private String getCounterpartyAccount(Transaction primaryTxn, List<Transaction> allLegs) {
        if (primaryTxn.getRemitterAccount() != null) {
            return primaryTxn.getRemitterAccount();
        }
        if (primaryTxn.getBeneficiaryAccount() != null) {
            return primaryTxn.getBeneficiaryAccount();
        }

        if (primaryTxn.getCounterpartyVaId() != null) {
            VirtualAccount counterpartyVa = virtualAccountRepository
                .findById(primaryTxn.getCounterpartyVaId()).orElse(null);
            if (counterpartyVa != null) {
                return counterpartyVa.getVaNumber();
            }
        }

        return null;
    }

    private String determineCounterpartyType(List<Transaction> allLegs) {
        for (Transaction txn : allLegs) {
            VirtualAccount va = virtualAccountRepository.findById(txn.getVaId()).orElse(null);
            if (va != null && va.getAccountCategory() == AccountCategory.PHYSICAL_MIRROR) {
                return "CBS"; // External via CBS
            }
        }
        return "INTERNAL_VA";
    }

    /**
     * Build accounting entries for user-facing views.
     *
     * CORPORATE CFO VIEW:
     * - Shadow VA (PHYSICAL_MIRROR) entries are EXCLUDED by default - they are omnibus accounts
     *   used for CBS reconciliation, not corporate accounting
     * - Settlement VA entries are included for audit trail (pass-through, nets to zero)
     * - Operating/Transaction VA entries show the actual business impact
     *
     * For full audit trail including Shadow VA, use buildAccountingEntriesWithInternal()
     */
    private List<TransactionDto.AccountingEntryResponse> buildAccountingEntries(List<Transaction> allLegs) {
        return buildAccountingEntries(allLegs, false);
    }

    /**
     * Build accounting entries with optional inclusion of internal system account entries.
     *
     * CORPORATE CFO VIEW (includeInternal = false):
     * - Only shows entries for OPERATING VAs (user's actual business accounts)
     * - Excludes Shadow VA (PHYSICAL_MIRROR) - omnibus account for CBS reconciliation
     * - Excludes Settlement VA (SETTLEMENT) - internal clearing house, nets to zero
     *
     * FULL AUDIT TRAIL (includeInternal = true):
     * - Shows all entries including Shadow VA and Settlement VA
     * - Required for bank reconciliation and regulatory audit
     *
     * @param allLegs All transaction legs
     * @param includeInternal If true, include Shadow VA and Settlement VA entries for full audit trail
     */
    private List<TransactionDto.AccountingEntryResponse> buildAccountingEntries(List<Transaction> allLegs, boolean includeInternal) {
        List<TransactionDto.AccountingEntryResponse> entries = new ArrayList<>();
        int legNumber = 1;

        for (Transaction txn : allLegs) {
            VirtualAccount va = virtualAccountRepository.findById(txn.getVaId()).orElse(null);

            // Determine account category
            VirtualAccount.AccountCategory accountCategory = va != null ? va.getAccountCategory() : null;
            String accountType = accountCategory != null ? accountCategory.name() : "OPERATING";

            // FILTER: For user-facing views (CFO view), exclude internal system accounts:
            // 1. Shadow VA (PHYSICAL_MIRROR) - omnibus account mirroring CBS physical account
            // 2. Settlement VA (SETTLEMENT) - internal clearing house, always nets to zero
            //
            // Corporate CFOs only need to see entries affecting their operating VAs.
            // Internal routing through Settlement VA is bank plumbing, not business accounting.
            if (!includeInternal) {
                if (accountCategory == VirtualAccount.AccountCategory.PHYSICAL_MIRROR) {
                    log.debug("Excluding Shadow VA {} from accounting entries (omnibus account)",
                        va != null ? va.getVaNumber() : txn.getVaId());
                    continue;
                }
                if (accountCategory == VirtualAccount.AccountCategory.SETTLEMENT) {
                    log.debug("Excluding Settlement VA {} from accounting entries (internal clearing)",
                        va != null ? va.getVaNumber() : txn.getVaId());
                    continue;
                }
            }

            // Determine if this is a debit or credit based on actual balance movement
            // This ensures accounting entries are balanced from double-entry perspective
            boolean isDebit = txn.getBalanceAfter() != null && txn.getBalanceBefore() != null &&
                             txn.getBalanceAfter().compareTo(txn.getBalanceBefore()) < 0;
            String entryType = isDebit ? "DEBIT" : "CREDIT";

            entries.add(TransactionDto.AccountingEntryResponse.builder()
                .legNumber(legNumber++)
                .transactionId(txn.getId())
                .referenceNumber(txn.getReferenceNumber())
                .vaId(txn.getVaId())
                .vaNumber(va != null ? va.getVaNumber() : null)
                .vaName(va != null ? va.getVaName() : null)
                .accountType(accountType)
                .movementType(txn.getMovementType() != null ? txn.getMovementType().name() : null)
                .entryType(entryType)  // Add explicit DEBIT/CREDIT based on balance movement
                .amount(txn.getAmount())
                .currencyCode(txn.getCurrencyCode())
                .balanceBefore(txn.getBalanceBefore())
                .balanceAfter(txn.getBalanceAfter())
                .description(txn.getDescription())
                .processingNotes(txn.getProcessingNotes())
                .transactionDate(txn.getTransactionDate())
                .isInternalAccount(accountCategory == VirtualAccount.AccountCategory.PHYSICAL_MIRROR ||
                                   accountCategory == VirtualAccount.AccountCategory.SETTLEMENT)
                .build());
        }

        return entries;
    }

    private TransactionDto.GroupedTransactionSummary calculateGroupedSummary(
            List<TransactionDto.GroupedTransactionResponse> groupedList) {

        BigDecimal totalCredits = BigDecimal.ZERO;
        BigDecimal totalDebits = BigDecimal.ZERO;
        BigDecimal totalFees = BigDecimal.ZERO;
        long creditCount = 0;
        long debitCount = 0;
        long collectionCount = 0;
        long paymentCount = 0;
        long poboCount = 0;

        for (TransactionDto.GroupedTransactionResponse grouped : groupedList) {
            if (grouped.isCredit()) {
                totalCredits = totalCredits.add(grouped.getNetAmount());
                creditCount++;
            } else {
                totalDebits = totalDebits.add(grouped.getNetAmount());
                debitCount++;
            }

            if (grouped.getFeeAmount() != null) {
                totalFees = totalFees.add(grouped.getFeeAmount());
            }

            if ("COLLECTION".equals(grouped.getOperationType())) {
                collectionCount++;
            } else if ("PAYMENT".equals(grouped.getOperationType())) {
                paymentCount++;
            } else if ("POBO_PAYMENT".equals(grouped.getOperationType())) {
                poboCount++;
            }
        }

        return TransactionDto.GroupedTransactionSummary.builder()
            .totalCredits(totalCredits)
            .totalDebits(totalDebits)
            .totalFees(totalFees)
            .netFlow(totalCredits.subtract(totalDebits))
            .creditCount(creditCount)
            .debitCount(debitCount)
            .collectionCount(collectionCount)
            .paymentCount(paymentCount)
            .poboCount(poboCount)
            .build();
    }

    // ========================================================================
    // INNER CLASSES - DTOs
    // ========================================================================

    @lombok.Data
    @lombok.Builder
    public static class TransferClassification {
        private boolean sameProgram;
        private boolean sameLegalEntity;
        private boolean sameCurrency;
        private boolean sameCountry;
        private boolean isIntercompany;
        private boolean isCrossBorder;
        private boolean requiresFx;
        private UUID fromLegalEntityId;
        private UUID toLegalEntityId;
        private String fromCountry;
        private String toCountry;
        private String fromCurrency;
        private String toCurrency;
        private LegalEntity fromEntity;
        private LegalEntity toEntity;
    }

    @lombok.Data
    @lombok.Builder
    public static class TransferFeeBreakdown {
        private BigDecimal baseFee;
        private BigDecimal intercompanyFee;
        private BigDecimal fxFee;
        private BigDecimal crossBorderFee;
        private BigDecimal treasuryServiceFee;
        private BigDecimal totalFee;
        private List<FeeLineItem> lineItems;
    }

    @lombok.Data
    @lombok.Builder
    public static class FeeLineItem {
        private String chargeCode;
        private String chargeName;
        private BigDecimal amount;
        private String currency;
        private boolean waived;
        private String waiverReason;
        @lombok.Builder.Default
        private FeeOwner feeOwner = FeeOwner.SOURCE_PROGRAM;
    }

    public enum FeeOwner {
        SOURCE_PROGRAM,
        DESTINATION_PROGRAM,
        CORPORATE,
        TREASURY_CENTER
    }

    public static class InsufficientFundsException extends BusinessException {
        private final FundsCheckResult fundsCheckResult;

        public InsufficientFundsException(String message, FundsCheckResult result) {
            super(message);
            this.fundsCheckResult = result;
        }

        public FundsCheckResult getFundsCheckResult() {
            return fundsCheckResult;
        }

        public int getRejectionLevel() {
            return fundsCheckResult != null ? fundsCheckResult.getRejectionLevel() : -1;
        }

        public String getRejectionVaNumber() {
            return fundsCheckResult != null ? fundsCheckResult.getRejectionVaNumber() : null;
        }
    }
}