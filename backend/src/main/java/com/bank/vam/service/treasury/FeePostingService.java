package com.bank.vam.service.treasury;

import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.Transaction.MovementType;
import com.bank.vam.entity.Transaction.TransactionCategory;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.VaSpecialType;
import com.bank.vam.entity.tax.CalculatedCharge;
import com.bank.vam.entity.tax.CalculatedTax;
import com.bank.vam.entity.treasury.ExceptionTransaction;
import com.bank.vam.entity.treasury.ExceptionTransaction.ExceptionStatus;
import com.bank.vam.entity.treasury.ExceptionTransaction.ExceptionType;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.tax.CalculatedChargeRepository;
import com.bank.vam.repository.tax.CalculatedTaxRepository;
import com.bank.vam.repository.treasury.ExceptionTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * FeePostingService - Pure Virtual Architecture v5.3
 *
 * KEY CHANGES (v5.3 - TRANSACTION FIX):
 * =====================================
 * REMOVED Propagation.REQUIRES_NEW from fee posting methods.
 *
 * PROBLEM WITH REQUIRES_NEW:
 * When TransactionService.transfer() calls postTransferFees(), if fee posting
 * runs in a REQUIRES_NEW transaction:
 * 1. Fee posting commits independently in its own transaction
 * 2. If the main transfer transaction rolls back, fees remain but principal is lost
 * 3. Fee posting may read stale balance data (before main tx uncommitted changes)
 * 4. This caused "fees posted but no transfer transactions in database" issue
 *
 * FIX: Fee posting methods now use default propagation (REQUIRED) so they
 * participate in the caller's transaction. If the caller rolls back, fees
 * also roll back. This ensures atomicity of the complete transfer + fees.
 *
 * KEY CHANGES (v5.2 - ACCOUNTING FIX):
 * ====================================
 * 1. postFeePairToTarget() now DEBITS source VA balance (was audit-only in v5.1)
 * 2. postFeePair() now DEBITS source VA balance (was audit-only in v5.1)
 * 3. All fee postings create proper double-entry with ACTUAL balance changes
 *
 * METHODS:
 * ========
 * - postFee(): Full transfer - use for standalone fee posting
 * - postFeePair(): Full transfer - use for general fee posting
 * - postFeePairToTarget(): Full transfer to specific Settlement VA
 *
 * All methods now properly debit source VA balance AND credit Settlement VA balance.
 *
 * DOUBLE-ENTRY ACCOUNTING PRINCIPLE:
 * ==================================
 * Every fee must have BOTH debit and credit entries WITH BALANCE CHANGES:
 *
 * | Entry | Account       | Debit | Credit | Balance Change |
 * |-------|---------------|-------|--------|----------------|
 * | 1     | Source VA     | X     |        | -X (DECREASE)  |
 * | 2     | Settlement VA |       | X      | +X (INCREASE)  |
 *
 * Total Debits = Total Credits (Balanced)
 *
 * PURE VIRTUAL PRINCIPLE:
 * =======================
 * All fee postings are internal VA bookkeeping.
 * No physicalAccountId required.
 * Settlement/Exception VAs are purely virtual.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeePostingService {

    private final VirtualAccountRepository vaRepository;
    private final TransactionRepository transactionRepository;
    private final CalculatedChargeRepository chargeRepository;
    private final CalculatedTaxRepository taxRepository;
    private final ExceptionTransactionRepository exceptionRepository;
    private final SettlementVaResolverService settlementVaResolver;

    // ========================================================================
    // FULL FEE POSTING (DEBIT SOURCE BALANCE + CREDIT SETTLEMENT BALANCE)
    // ========================================================================

    /**
     * Post a fee/charge from source VA to Settlement VA.
     * 
     * This method:
     * 1. DEBITS source VA balance
     * 2. Creates FEE debit transaction record
     * 3. CREDITS Settlement VA balance  
     * 4. Creates FEE_CREDIT transaction record
     * 
     * Use this when the fee has NOT yet been debited from the source VA balance.
     * For cases where the fee was already debited (e.g., transfers), use postFeePair().
     * 
     * PURE VIRTUAL: No physicalAccountId required.
     */
    /**
     * v5.3: Changed from REQUIRES_NEW to default REQUIRED propagation.
     * Fee posting now participates in caller's transaction for atomicity.
     */
    @Transactional
    public FeePostingResult postFee(UUID sourceVaId, BigDecimal amount, String feeType,
                                     UUID referenceId, String description) {

        log.info("Posting fee (full transfer): sourceVa={}, amount={}, type={}", sourceVaId, amount, feeType);

        // 1. Validate amount
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("Skipping zero/negative fee: {}", amount);
            return FeePostingResult.skipped(referenceId, "Zero or negative amount");
        }

        // 2. Get source VA - return FAILED result instead of throwing to avoid transaction rollback
        VirtualAccount sourceVa = vaRepository.findById(sourceVaId).orElse(null);
        if (sourceVa == null) {
            log.warn("Source VA not found for fee posting: {}", sourceVaId);
            return FeePostingResult.failed(referenceId, "Source VA not found: " + sourceVaId);
        }
        
        // 3. Validate source VA has sufficient balance
        if (sourceVa.getCurrentBalance().compareTo(amount) < 0) {
            log.warn("Insufficient balance for fee. Required: {}, Available: {} on VA {}", 
                    amount, sourceVa.getCurrentBalance(), sourceVa.getVaNumber());
            return FeePostingResult.failed(referenceId, 
                "Insufficient balance for fee. Required: " + amount +
                ", Available: " + sourceVa.getCurrentBalance());
        }

        // 4. Resolve Settlement VA with proper exception handling (v5.2.0)
        SettlementVaResolverService.SettlementVaResolutionResult resolution =
            settlementVaResolver.resolveSettlementVaWithResult(sourceVa, amount, referenceId);

        VirtualAccount settlementVa = resolution.getResolvedVa();
        boolean isExceptionFallback = !resolution.isSuccess();

        if (isExceptionFallback) {
            log.warn("Fee posting (postFee): Settlement VA not found. Posting to Exception VA {} instead. " +
                     "Exception: {}", settlementVa.getVaNumber(),
                resolution.getExceptionRaised().getExceptionNumber());
        }

        // 5. Generate correlation ID
        String correlationId = generateCorrelationId(feeType);
        
        // 6. DEBIT source VA (balance change + transaction record)
        BigDecimal sourceBalanceBefore = sourceVa.getCurrentBalance();
        sourceVa.setCurrentBalance(sourceBalanceBefore.subtract(amount));
        sourceVa.setAvailableBalance(sourceVa.getCurrentBalance());
        vaRepository.save(sourceVa);
        
        Transaction debitTxn = Transaction.builder()
            .movementType(MovementType.FEE)
            .transactionCategory(TransactionCategory.INTERNAL)
            .corporateId(sourceVa.getCorporateId())
            .vaId(sourceVa.getId())
            .physicalAccountId(null)  // Not required for internal fee
            .programId(sourceVa.getProgramId())
            .amount(amount)
            .currencyCode(sourceVa.getCurrencyCode())
            .balanceBefore(sourceBalanceBefore)
            .balanceAfter(sourceVa.getCurrentBalance())
            .transactionDate(LocalDateTime.now())
            .valueDate(LocalDate.now())
            .referenceNumber(generateReference("FEE"))
            .description("FEE DEBIT: " + description)
            .correlationId(correlationId)
            .counterpartyVaId(settlementVa.getId())
            .channel("TREASURY")
            .status(Transaction.TransactionStatus.COMPLETED)
            .build();
        debitTxn = transactionRepository.save(debitTxn);
        
        // 7. CREDIT Settlement VA (balance change + transaction record)
        BigDecimal settlementBalanceBefore = settlementVa.getCurrentBalance();
        settlementVa.setCurrentBalance(settlementBalanceBefore.add(amount));
        settlementVa.setAvailableBalance(settlementVa.getCurrentBalance());
        vaRepository.save(settlementVa);
        
        Transaction creditTxn = Transaction.builder()
            .movementType(MovementType.FEE_CREDIT)
            .transactionCategory(TransactionCategory.INTERNAL)
            .corporateId(settlementVa.getCorporateId())
            .vaId(settlementVa.getId())
            .physicalAccountId(null)  // Not required for Settlement VA
            .programId(settlementVa.getProgramId())
            .amount(amount)
            .currencyCode(sourceVa.getCurrencyCode())
            .balanceBefore(settlementBalanceBefore)
            .balanceAfter(settlementVa.getCurrentBalance())
            .transactionDate(LocalDateTime.now())
            .valueDate(LocalDate.now())
            .referenceNumber(generateReference("SET"))
            .description("FEE CREDIT: " + description)
            .correlationId(correlationId)
            .counterpartyVaId(sourceVa.getId())
            .channel("TREASURY")
            .status(Transaction.TransactionStatus.COMPLETED)
            .build();
        creditTxn = transactionRepository.save(creditTxn);
        
        // 8. If fallback to Exception VA, create exception record
        ExceptionTransaction exceptionTxn = null;
        if (isExceptionFallback) {
            exceptionTxn = createExceptionRecord(
                settlementVa, sourceVa, creditTxn.getId(),
                amount, sourceVa.getCurrencyCode(), feeType
            );
            
            log.warn("TREASURY_ALERT: Fee posted to Exception VA. Source={}, Amount={}, Exception={}",
                sourceVa.getVaNumber(), amount, exceptionTxn.getExceptionNumber());
        }
        
        log.info("Fee posted successfully (full transfer): {} -> {}, amount={}, correlation={}",
            sourceVa.getVaNumber(), settlementVa.getVaNumber(), amount, correlationId);
        
        return FeePostingResult.builder()
            .debitTransactionId(debitTxn.getId())
            .creditTransactionId(creditTxn.getId())
            .debitTransaction(debitTxn)
            .creditTransaction(creditTxn)
            .settlementVaId(settlementVa.getId())
            .settlementVaNumber(settlementVa.getVaNumber())
            .settlementVa(settlementVa)
            .amount(amount)
            .currency(sourceVa.getCurrencyCode())
            .correlationId(correlationId)
            .isExceptionFallback(isExceptionFallback)
            .exceptionTransaction(exceptionTxn)
            .status("POSTED")
            .postedAt(LocalDateTime.now())
            .build();
    }

    // ========================================================================
    // DOUBLE-ENTRY FEE PAIR POSTING (v5.1 - for transfers where fee already 
    // debited from source VA balance)
    // ========================================================================

    /**
     * Post fee PAIR with proper double-entry accounting (v5.2 - WITH BALANCE CHANGES).
     * 
     * This method creates BOTH sides of the fee journal entry:
     * 1. FEE debit transaction on source VA (WITH balance change)
     * 2. FEE_CREDIT transaction on Settlement VA (WITH balance change)
     * 
     * v5.2 FIX: This method now DEBITS source VA balance (was audit-only in v5.1)
     * 
     * ACCOUNTING:
     * ===========
     * | Entry | Account       | Debit | Credit | Balance Change |
     * |-------|---------------|-------|--------|----------------|
     * | 1     | Source VA     | fee   |        | -fee           |
     * | 2     | Settlement VA |       | fee    | +fee           |
     * 
     * Total Debits = Total Credits (Balanced) ✓
     * 
     * @param sourceVaId The VA to be charged the fee
     * @param amount Fee amount
     * @param feeType Fee type code for categorization
     * @param referenceId Reference to originating transaction
     * @param correlationId Correlation ID for linking related transactions
     * @param description Description for the fee
     * @return FeePostingResult with both debit and credit transactions
     */
    /**
     * v5.3: Changed from REQUIRES_NEW to default REQUIRED propagation.
     * Fee posting now participates in caller's transaction for atomicity.
     */
    @Transactional
    public FeePostingResult postFeePair(UUID sourceVaId, BigDecimal amount, String feeType,
                                         UUID referenceId, String correlationId,
                                         String description) {

        log.info("Posting fee pair: sourceVa={}, amount={}, type={}, correlation={}",
            sourceVaId, amount, feeType, correlationId);

        // 1. Validate amount
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("Skipping zero/negative fee pair: {}", amount);
            return FeePostingResult.skipped(referenceId, "Zero or negative amount");
        }

        // 2. Get source VA - return FAILED result instead of throwing to avoid transaction rollback
        VirtualAccount sourceVa = vaRepository.findById(sourceVaId).orElse(null);
        if (sourceVa == null) {
            log.warn("Source VA not found for fee pair posting: {}", sourceVaId);
            return FeePostingResult.failed(referenceId, "Source VA not found: " + sourceVaId);
        }
        
        // 3. Validate source VA has sufficient balance for fee (v5.2)
        if (sourceVa.getCurrentBalance().compareTo(amount) < 0) {
            log.warn("Insufficient balance for fee. Required: {}, Available: {} on VA {}",
                    amount, sourceVa.getCurrentBalance(), sourceVa.getVaNumber());
            return FeePostingResult.failed(referenceId,
                "Insufficient balance for fee. Required: " + amount +
                ", Available: " + sourceVa.getCurrentBalance());
        }

        // 4. Resolve Settlement VA with proper exception handling (v5.2.0)
        SettlementVaResolverService.SettlementVaResolutionResult resolution =
            settlementVaResolver.resolveSettlementVaWithResult(sourceVa, amount, referenceId);

        VirtualAccount settlementVa = resolution.getResolvedVa();
        boolean isExceptionFallback = !resolution.isSuccess();

        if (isExceptionFallback) {
            log.warn("Fee posting (postFeePair): Settlement VA not found. Posting to Exception VA {} instead. " +
                     "Exception: {}", settlementVa.getVaNumber(),
                resolution.getExceptionRaised().getExceptionNumber());
        }

        // 5. Generate correlation ID if not provided
        if (correlationId == null || correlationId.isEmpty()) {
            correlationId = generateCorrelationId(feeType);
        }
        
        // 6. DEBIT source VA (WITH balance change) - v5.2 FIX
        BigDecimal sourceBalanceBefore = sourceVa.getCurrentBalance();
        sourceVa.setCurrentBalance(sourceBalanceBefore.subtract(amount));
        sourceVa.setAvailableBalance(sourceVa.getCurrentBalance());
        vaRepository.save(sourceVa);
        
        Transaction debitTxn = Transaction.builder()
            .movementType(MovementType.FEE)
            .transactionCategory(TransactionCategory.INTERNAL)
            .corporateId(sourceVa.getCorporateId())
            .vaId(sourceVa.getId())
            .physicalAccountId(null)
            .programId(sourceVa.getProgramId())
            .amount(amount)
            .currencyCode(sourceVa.getCurrencyCode())
            .balanceBefore(sourceBalanceBefore)
            .balanceAfter(sourceVa.getCurrentBalance())  // v5.2: Reflects actual balance change
            .transactionDate(LocalDateTime.now())
            .valueDate(LocalDate.now())
            .referenceNumber(generateReference("FEE"))
            .description("FEE DEBIT: " + description)
            .correlationId(correlationId)
            .counterpartyVaId(settlementVa.getId())
            .channel("TREASURY")
            .status(Transaction.TransactionStatus.COMPLETED)
            .processingNotes("Fee debit with balance change")
            .build();
        debitTxn = transactionRepository.save(debitTxn);
        
        // 6. CREDIT Settlement VA (balance change + transaction record)
        BigDecimal settlementBalanceBefore = settlementVa.getCurrentBalance();
        settlementVa.setCurrentBalance(settlementBalanceBefore.add(amount));
        settlementVa.setAvailableBalance(settlementVa.getCurrentBalance());
        vaRepository.save(settlementVa);
        
        Transaction creditTxn = Transaction.builder()
            .movementType(MovementType.FEE_CREDIT)
            .transactionCategory(TransactionCategory.INTERNAL)
            .corporateId(settlementVa.getCorporateId())
            .vaId(settlementVa.getId())
            .physicalAccountId(null)  // Not required for Settlement VA
            .programId(settlementVa.getProgramId())
            .amount(amount)
            .currencyCode(sourceVa.getCurrencyCode())
            .balanceBefore(settlementBalanceBefore)
            .balanceAfter(settlementVa.getCurrentBalance())
            .transactionDate(LocalDateTime.now())
            .valueDate(LocalDate.now())
            .referenceNumber(generateReference("SET"))
            .description("FEE CREDIT: " + description)
            .correlationId(correlationId)
            .counterpartyVaId(sourceVa.getId())
            .channel("TREASURY")
            .status(Transaction.TransactionStatus.COMPLETED)
            .build();
        creditTxn = transactionRepository.save(creditTxn);
        
        // 7. If fallback to Exception VA, create exception record
        ExceptionTransaction exceptionTxn = null;
        if (isExceptionFallback) {
            exceptionTxn = createExceptionRecord(
                settlementVa, sourceVa, creditTxn.getId(),
                amount, sourceVa.getCurrencyCode(), feeType
            );
            
            log.warn("TREASURY_ALERT: Fee pair posted to Exception VA. Source={}, Amount={}, Exception={}",
                sourceVa.getVaNumber(), amount, exceptionTxn.getExceptionNumber());
        }
        
        log.info("Fee pair posted successfully (double-entry): {} -> {}, amount={}, correlation={}, " +
                 "debitTxn={}, creditTxn={}",
            sourceVa.getVaNumber(), settlementVa.getVaNumber(), amount, correlationId,
            debitTxn.getReferenceNumber(), creditTxn.getReferenceNumber());
        
        return FeePostingResult.builder()
            .debitTransactionId(debitTxn.getId())
            .creditTransactionId(creditTxn.getId())
            .debitTransaction(debitTxn)
            .creditTransaction(creditTxn)
            .settlementVaId(settlementVa.getId())
            .settlementVaNumber(settlementVa.getVaNumber())
            .settlementVa(settlementVa)
            .amount(amount)
            .currency(sourceVa.getCurrencyCode())
            .correlationId(correlationId)
            .isExceptionFallback(isExceptionFallback)
            .exceptionTransaction(exceptionTxn)
            .status("POSTED")
            .postedAt(LocalDateTime.now())
            .build();
    }

    /**
     * Simplified overload of postFeePair without explicit correlation ID.
     * v5.3: Changed from REQUIRES_NEW to default REQUIRED propagation.
     */
    @Transactional
    public FeePostingResult postFeePair(UUID sourceVaId, BigDecimal amount, String feeType,
                                         UUID referenceId, String description) {
        return postFeePair(sourceVaId, amount, feeType, referenceId, null, description);
    }

    // ========================================================================
    // TARGETED FEE PAIR POSTING (v5.1 - for cross-program fee routing)
    // ========================================================================

    /**
     * Post fee PAIR to a SPECIFIC Settlement VA (v5.2 - WITH BALANCE CHANGES).
     * 
     * v5.2 FIX: This method now DEBITS source VA balance (was audit-only in v5.1)
     * 
     * This method is used for cross-program transfers where fees need to be
     * routed to different Settlement VAs based on fee ownership:
     * - Same-program fees → Source program's Settlement VA
     * - Cross-program fees → Corporate Settlement VA
     * - FX/Treasury fees → Treasury Center's Settlement VA
     * 
     * ACCOUNTING:
     * ===========
     * | Entry | Account            | Debit | Credit | Balance Change |
     * |-------|--------------------|-------|--------|----------------|
     * | 1     | Source VA          | fee   |        | -fee           |
     * | 2     | Target Settlement  |       | fee    | +fee           |
     * 
     * @param sourceVaId The VA to be charged the fee
     * @param targetSettlementVaId The Settlement VA to receive the fee credit
     * @param amount Fee amount
     * @param feeType Fee type code
     * @param referenceId Reference to originating transaction
     * @param correlationId Correlation ID for linking transactions
     * @param description Fee description
     * @return FeePostingResult with both debit and credit transactions
     */
    /**
     * v5.3: Changed from REQUIRES_NEW to default REQUIRED propagation.
     * Fee posting now participates in caller's transaction for atomicity.
     */
    @Transactional
    public FeePostingResult postFeePairToTarget(UUID sourceVaId, UUID targetSettlementVaId,
                                                 BigDecimal amount, String feeType,
                                                 UUID referenceId, String correlationId,
                                                 String description) {

        log.info("Posting fee pair to target: sourceVa={}, targetSettlement={}, amount={}, type={}",
            sourceVaId, targetSettlementVaId, amount, feeType);

        // 1. Validate amount
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("Skipping zero/negative fee pair: {}", amount);
            return FeePostingResult.skipped(referenceId, "Zero or negative amount");
        }

        // 2. Get source VA - return FAILED result instead of throwing to avoid transaction rollback
        VirtualAccount sourceVa = vaRepository.findById(sourceVaId).orElse(null);
        if (sourceVa == null) {
            log.warn("Source VA not found for fee pair to target posting: {}", sourceVaId);
            return FeePostingResult.failed(referenceId, "Source VA not found: " + sourceVaId);
        }
        
        // 3. Validate source VA has sufficient balance for fee (v5.2)
        if (sourceVa.getCurrentBalance().compareTo(amount) < 0) {
            log.warn("Insufficient balance for fee. Required: {}, Available: {} on VA {}", 
                    amount, sourceVa.getCurrentBalance(), sourceVa.getVaNumber());
            return FeePostingResult.failed(referenceId, 
                "Insufficient balance for fee. Required: " + amount + 
                ", Available: " + sourceVa.getCurrentBalance());
        }
        
        // 4. Get target Settlement VA - return FAILED result instead of throwing
        VirtualAccount targetSettlementVa = vaRepository.findById(targetSettlementVaId).orElse(null);
        if (targetSettlementVa == null) {
            log.warn("Target Settlement VA not found for fee posting: {}", targetSettlementVaId);
            return FeePostingResult.failed(referenceId, "Target Settlement VA not found: " + targetSettlementVaId);
        }
        
        // 5. Validate target is a Settlement or Exception VA
        if (targetSettlementVa.getSpecialType() != VaSpecialType.SETTLEMENT &&
            targetSettlementVa.getSpecialType() != VaSpecialType.EXCEPTION) {
            log.warn("Target VA {} is not a Settlement/Exception VA (type: {}). Proceeding anyway.",
                targetSettlementVa.getVaNumber(), targetSettlementVa.getSpecialType());
        }
        
        boolean isExceptionFallback = targetSettlementVa.getSpecialType() == VaSpecialType.EXCEPTION;
        
        // 6. Generate correlation ID if not provided
        if (correlationId == null || correlationId.isEmpty()) {
            correlationId = generateCorrelationId(feeType);
        }
        
        // 7. DEBIT source VA (WITH balance change) - v5.2 FIX
        BigDecimal sourceBalanceBefore = sourceVa.getCurrentBalance();
        sourceVa.setCurrentBalance(sourceBalanceBefore.subtract(amount));
        sourceVa.setAvailableBalance(sourceVa.getCurrentBalance());
        vaRepository.save(sourceVa);
        
        Transaction debitTxn = Transaction.builder()
            .movementType(MovementType.FEE)
            .transactionCategory(TransactionCategory.INTERNAL)
            .corporateId(sourceVa.getCorporateId())
            .vaId(sourceVa.getId())
            .physicalAccountId(null)
            .programId(sourceVa.getProgramId())
            .amount(amount)
            .currencyCode(sourceVa.getCurrencyCode())
            .balanceBefore(sourceBalanceBefore)
            .balanceAfter(sourceVa.getCurrentBalance())  // v5.2: Reflects actual balance change
            .transactionDate(LocalDateTime.now())
            .valueDate(LocalDate.now())
            .referenceNumber(generateReference("FEE"))
            .description("FEE DEBIT: " + description)
            .correlationId(correlationId)
            .counterpartyVaId(targetSettlementVa.getId())
            .channel("TREASURY")
            .status(Transaction.TransactionStatus.COMPLETED)
            .processingNotes("Fee debit with balance change. Target: " + targetSettlementVa.getVaNumber())
            .build();
        debitTxn = transactionRepository.save(debitTxn);
        
        // 7. CREDIT target Settlement VA (balance change + transaction record)
        BigDecimal settlementBalanceBefore = targetSettlementVa.getCurrentBalance();
        targetSettlementVa.setCurrentBalance(settlementBalanceBefore.add(amount));
        targetSettlementVa.setAvailableBalance(targetSettlementVa.getCurrentBalance());
        vaRepository.save(targetSettlementVa);
        
        Transaction creditTxn = Transaction.builder()
            .movementType(MovementType.FEE_CREDIT)
            .transactionCategory(TransactionCategory.INTERNAL)
            .corporateId(targetSettlementVa.getCorporateId())
            .vaId(targetSettlementVa.getId())
            .physicalAccountId(null)
            .programId(targetSettlementVa.getProgramId())
            .amount(amount)
            .currencyCode(sourceVa.getCurrencyCode())
            .balanceBefore(settlementBalanceBefore)
            .balanceAfter(targetSettlementVa.getCurrentBalance())
            .transactionDate(LocalDateTime.now())
            .valueDate(LocalDate.now())
            .referenceNumber(generateReference("SET"))
            .description("FEE CREDIT: " + description)
            .correlationId(correlationId)
            .counterpartyVaId(sourceVa.getId())
            .channel("TREASURY")
            .status(Transaction.TransactionStatus.COMPLETED)
            .build();
        creditTxn = transactionRepository.save(creditTxn);
        
        // 8. If target is Exception VA, create exception record
        ExceptionTransaction exceptionTxn = null;
        if (isExceptionFallback) {
            exceptionTxn = createExceptionRecord(
                targetSettlementVa, sourceVa, creditTxn.getId(),
                amount, sourceVa.getCurrencyCode(), feeType
            );
            
            log.warn("TREASURY_ALERT: Fee posted to Exception VA. Source={}, Target={}, Amount={}, Exception={}",
                sourceVa.getVaNumber(), targetSettlementVa.getVaNumber(), 
                amount, exceptionTxn.getExceptionNumber());
        }
        
        log.info("Fee pair posted to target successfully: {} -> {}, amount={}, correlation={}",
            sourceVa.getVaNumber(), targetSettlementVa.getVaNumber(), amount, correlationId);
        
        return FeePostingResult.builder()
            .debitTransactionId(debitTxn.getId())
            .creditTransactionId(creditTxn.getId())
            .debitTransaction(debitTxn)
            .creditTransaction(creditTxn)
            .settlementVaId(targetSettlementVa.getId())
            .settlementVaNumber(targetSettlementVa.getVaNumber())
            .settlementVa(targetSettlementVa)
            .amount(amount)
            .currency(sourceVa.getCurrencyCode())
            .correlationId(correlationId)
            .isExceptionFallback(isExceptionFallback)
            .exceptionTransaction(exceptionTxn)
            .status("POSTED")
            .postedAt(LocalDateTime.now())
            .build();
    }

    // ========================================================================
    // CREDIT-ONLY FEE POSTING (DEPRECATED - Use postFeePair for proper accounting)
    // ========================================================================

    /**
     * Post fee CREDIT ONLY to Settlement VA.
     * 
     * @deprecated Use {@link #postFeePair(UUID, BigDecimal, String, UUID, String, String)} 
     *             instead for proper double-entry accounting. This method only creates the 
     *             credit side, leaving an incomplete audit trail.
     * 
     * This method is maintained for backward compatibility but should be replaced
     * with postFeePair() in all new code.
     * 
     * PURE VIRTUAL: No physicalAccountId required.
     */
    /**
     * v5.3: Changed from REQUIRES_NEW to default REQUIRED propagation.
     */
    @Deprecated(since = "5.1", forRemoval = false)
    @Transactional
    public FeePostingResult postFeeCredit(UUID sourceVaId, BigDecimal amount, String feeType,
                                           UUID referenceId, String correlationId,
                                           String description) {
        
        log.warn("DEPRECATED: postFeeCredit() called - use postFeePair() for proper double-entry. " +
                 "sourceVa={}, amount={}, type={}", sourceVaId, amount, feeType);
        
        // Delegate to postFeePair for proper double-entry
        // This maintains backward compatibility while improving accounting
        return postFeePair(sourceVaId, amount, feeType, referenceId, correlationId, description);
    }

    /**
     * Simplified overload of postFeeCredit without explicit correlation ID.
     * v5.3: Changed from REQUIRES_NEW to default REQUIRED propagation.
     *
     * @deprecated Use {@link #postFeePair(UUID, BigDecimal, String, UUID, String)} instead.
     */
    @Deprecated(since = "5.1", forRemoval = false)
    @Transactional
    public FeePostingResult postFeeCredit(UUID sourceVaId, BigDecimal amount, String feeType,
                                           UUID referenceId, String description) {
        return postFeePair(sourceVaId, amount, feeType, referenceId, null, description);
    }

    // ========================================================================
    // POST CALCULATED CHARGE
    // ========================================================================

    /**
     * Post a calculated charge to Settlement VA.
     * Uses full transfer (debit balance + credit balance).
     */
    @Transactional
    public FeePostingResult postCalculatedCharge(UUID chargeId, UUID sourceVaId) {
        CalculatedCharge charge = chargeRepository.findById(chargeId)
            .orElseThrow(() -> new ResourceNotFoundException("Calculated charge not found: " + chargeId));
        
        if (charge.getStatus() == CalculatedCharge.CalculationStatus.APPLIED) {
            log.debug("Charge {} already applied, skipping", chargeId);
            return FeePostingResult.skipped(chargeId, "Already applied");
        }
        
        if (charge.getFinalCharge() == null || charge.getFinalCharge().compareTo(BigDecimal.ZERO) <= 0) {
            log.info("Skipping zero/negative charge: {}", chargeId);
            return FeePostingResult.skipped(chargeId, "Zero or negative amount");
        }
        
        UUID effectiveSourceVaId = sourceVaId != null ? sourceVaId : charge.getVaId();
        if (effectiveSourceVaId == null) {
            throw new BusinessException("Cannot resolve source VA for charge: " + chargeId);
        }
        
        String chargeDescription = charge.getChargeCode();
        if (charge.getChargeType() != null) {
            chargeDescription += " - " + charge.getChargeType().name();
        }
        
        FeePostingResult result = postFee(
            effectiveSourceVaId,
            charge.getFinalCharge(),
            charge.getChargeCode(),
            charge.getId(),
            chargeDescription
        );
        
        charge.apply();
        chargeRepository.save(charge);
        
        result.setChargeId(chargeId);
        return result;
    }

    @Transactional
    public FeePostingResult postCalculatedCharge(UUID chargeId) {
        return postCalculatedCharge(chargeId, null);
    }

    // ========================================================================
    // POST CALCULATED TAX
    // ========================================================================

    /**
     * Post a calculated tax to Settlement VA.
     * Uses full transfer (debit balance + credit balance).
     */
    @Transactional
    public FeePostingResult postCalculatedTax(UUID taxId, UUID sourceVaId) {
        CalculatedTax tax = taxRepository.findById(taxId)
            .orElseThrow(() -> new ResourceNotFoundException("Calculated tax not found: " + taxId));
        
        if (tax.getStatus() == CalculatedTax.CalculationStatus.APPLIED) {
            log.debug("Tax {} already applied, skipping", taxId);
            return FeePostingResult.skipped(taxId, "Already applied");
        }
        
        if (tax.getFinalTax() == null || tax.getFinalTax().compareTo(BigDecimal.ZERO) <= 0) {
            log.info("Skipping zero/negative tax: {}", taxId);
            return FeePostingResult.skipped(taxId, "Zero or negative amount");
        }
        
        UUID effectiveSourceVaId = sourceVaId != null ? sourceVaId : tax.getVaId();
        if (effectiveSourceVaId == null) {
            throw new BusinessException("Cannot resolve source VA for tax: " + taxId);
        }
        
        FeePostingResult result = postFee(
            effectiveSourceVaId,
            tax.getFinalTax(),
            tax.getTaxCode(),
            tax.getId(),
            "TAX: " + tax.getTaxCode() + " - " + tax.getTaxType()
        );
        
        tax.setStatus(CalculatedTax.CalculationStatus.APPLIED);
        tax.setAppliedAt(LocalDateTime.now());
        taxRepository.save(tax);
        
        result.setChargeId(taxId);
        return result;
    }

    @Transactional
    public FeePostingResult postCalculatedTax(UUID taxId) {
        return postCalculatedTax(taxId, null);
    }

    // ========================================================================
    // BULK POSTING METHODS
    // ========================================================================

    @Transactional
    public List<FeePostingResult> postAllChargesForReference(String referenceType, UUID referenceId) {
        return postAllChargesForReference(referenceType, referenceId, null);
    }

    @Transactional
    public List<FeePostingResult> postAllChargesForReference(String referenceType, UUID referenceId, UUID sourceVaId) {
        log.info("Posting all charges for {} {}", referenceType, referenceId);
        
        List<FeePostingResult> results = new ArrayList<>();
        
        try {
            List<CalculatedCharge> charges = chargeRepository.findByReferenceTypeAndReferenceId(
                CalculatedCharge.ReferenceType.valueOf(referenceType), referenceId);
            
            for (CalculatedCharge charge : charges) {
                if (charge.getStatus() != CalculatedCharge.CalculationStatus.APPLIED) {
                    try {
                        FeePostingResult result = postCalculatedCharge(charge.getId(), sourceVaId);
                        results.add(result);
                    } catch (Exception e) {
                        log.error("Failed to post charge {}: {}", charge.getId(), e.getMessage());
                        results.add(FeePostingResult.failed(charge.getId(), e.getMessage()));
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            log.warn("Unknown reference type: {}", referenceType);
        }
        
        log.info("Posted {} charges for {} {}", results.size(), referenceType, referenceId);
        return results;
    }

    @Transactional
    public List<FeePostingResult> postAllTaxesForReference(String referenceType, UUID referenceId) {
        return postAllTaxesForReference(referenceType, referenceId, null);
    }

    @Transactional
    public List<FeePostingResult> postAllTaxesForReference(String referenceType, UUID referenceId, UUID sourceVaId) {
        log.info("Posting all taxes for {} {}", referenceType, referenceId);
        
        List<FeePostingResult> results = new ArrayList<>();
        
        try {
            List<CalculatedTax> taxes = taxRepository.findByReferenceTypeAndReferenceId(
                CalculatedTax.ReferenceType.valueOf(referenceType), referenceId);
            
            for (CalculatedTax tax : taxes) {
                if (tax.getStatus() != CalculatedTax.CalculationStatus.APPLIED) {
                    try {
                        FeePostingResult result = postCalculatedTax(tax.getId(), sourceVaId);
                        results.add(result);
                    } catch (Exception e) {
                        log.error("Failed to post tax {}: {}", tax.getId(), e.getMessage());
                        results.add(FeePostingResult.failed(tax.getId(), e.getMessage()));
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            log.warn("Unknown reference type: {}", referenceType);
        }
        
        log.info("Posted {} taxes for {} {}", results.size(), referenceType, referenceId);
        return results;
    }

    @Transactional
    public List<FeePostingResult> postAllForReference(String referenceType, UUID referenceId) {
        return postAllForReference(referenceType, referenceId, null);
    }

    @Transactional
    public List<FeePostingResult> postAllForReference(String referenceType, UUID referenceId, UUID sourceVaId) {
        List<FeePostingResult> results = new ArrayList<>();
        results.addAll(postAllChargesForReference(referenceType, referenceId, sourceVaId));
        results.addAll(postAllTaxesForReference(referenceType, referenceId, sourceVaId));
        return results;
    }

    // ========================================================================
    // BATCH POSTING
    // ========================================================================

    @Transactional
    public BatchPostingResult batchPostFees(List<FeePostingRequest> requests) {
        int successCount = 0;
        int failureCount = 0;
        int exceptionCount = 0;
        int skippedCount = 0;
        BigDecimal totalPosted = BigDecimal.ZERO;
        
        for (FeePostingRequest request : requests) {
            try {
                FeePostingResult result = postFee(
                    request.getSourceVaId(),
                    request.getAmount(),
                    request.getFeeType(),
                    request.getReferenceId(),
                    request.getDescription()
                );
                
                if ("POSTED".equals(result.getStatus())) {
                    successCount++;
                    totalPosted = totalPosted.add(request.getAmount());
                    if (result.isExceptionFallback()) {
                        exceptionCount++;
                    }
                } else if ("SKIPPED".equals(result.getStatus())) {
                    skippedCount++;
                }
            } catch (Exception e) {
                log.error("Failed to post fee: {} - {}", request, e.getMessage());
                failureCount++;
            }
        }
        
        log.info("Batch fee posting complete: success={}, failures={}, skipped={}, exceptions={}, total={}",
            successCount, failureCount, skippedCount, exceptionCount, totalPosted);
        
        return BatchPostingResult.builder()
            .successCount(successCount)
            .failureCount(failureCount)
            .skippedCount(skippedCount)
            .exceptionFallbackCount(exceptionCount)
            .totalAmountPosted(totalPosted)
            .processedAt(LocalDateTime.now())
            .build();
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private ExceptionTransaction createExceptionRecord(VirtualAccount exceptionVa, 
                                                        VirtualAccount sourceVa,
                                                        UUID creditTransactionId,
                                                        BigDecimal amount, 
                                                        String currency,
                                                        String feeType) {
        ExceptionTransaction exception = ExceptionTransaction.builder()
            .exceptionNumber(ExceptionTransaction.generateExceptionNumber())
            .exceptionType(ExceptionType.MISSING_SETTLEMENT_VA)
            .exceptionVaId(exceptionVa.getId())
            .originalVaId(sourceVa.getId())
            .originalTransactionId(creditTransactionId)
            .amount(amount)
            .currencyCode(currency)
            .status(ExceptionStatus.OPEN)
            .description("Fee posted to Exception VA due to missing Settlement VA. " +
                        "Fee type: " + feeType + ", Source VA: " + sourceVa.getVaNumber())
            .build();
        
        return exceptionRepository.save(exception);
    }

    private String generateCorrelationId(String feeType) {
        String sanitized = feeType != null ? 
            feeType.toUpperCase().replaceAll("[^A-Z0-9]", "").substring(0, Math.min(10, feeType.length())) : 
            "FEE";
        return "FEE-" + sanitized + "-" + System.currentTimeMillis() + "-" + 
               String.format("%04d", (int)(Math.random() * 10000));
    }

    private String generateReference(String prefix) {
        return prefix + "-" + System.currentTimeMillis() + "-" + 
               String.format("%04d", (int)(Math.random() * 10000));
    }

    // ========================================================================
    // RESULT CLASSES
    // ========================================================================

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class FeePostingResult {
        private UUID chargeId;
        private UUID debitTransactionId;
        private UUID creditTransactionId;
        private Transaction debitTransaction;
        private Transaction creditTransaction;
        private UUID settlementVaId;
        private String settlementVaNumber;
        private VirtualAccount settlementVa;
        private BigDecimal amount;
        private String currency;
        private String correlationId;
        private boolean isExceptionFallback;
        private ExceptionTransaction exceptionTransaction;
        private String status;
        private String skipReason;
        private String errorMessage;
        private LocalDateTime postedAt;

        public static FeePostingResult skipped(UUID referenceId, String reason) {
            return FeePostingResult.builder()
                .chargeId(referenceId)
                .status("SKIPPED")
                .skipReason(reason)
                .build();
        }

        public static FeePostingResult failed(UUID referenceId, String error) {
            return FeePostingResult.builder()
                .chargeId(referenceId)
                .status("FAILED")
                .errorMessage(error)
                .build();
        }
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class FeePostingRequest {
        private UUID sourceVaId;
        private BigDecimal amount;
        private String feeType;
        private UUID referenceId;
        private String description;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class BatchPostingResult {
        private int successCount;
        private int failureCount;
        private int skippedCount;
        private int exceptionFallbackCount;
        private BigDecimal totalAmountPosted;
        private LocalDateTime processedAt;
    }
}