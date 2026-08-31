package com.bank.vam.service.receivables;

import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.entity.receivables.ReceivablePayment;
import com.bank.vam.entity.receivables.UnmatchedPayment;
import com.bank.vam.entity.treasury.ExceptionTransaction;
import com.bank.vam.entity.treasury.ExceptionTransaction.ExceptionStatus;
import com.bank.vam.entity.treasury.ExceptionTransaction.ExceptionType;
import com.bank.vam.entity.viban.Viban;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.receivables.ReceivablePaymentRepository;
import com.bank.vam.repository.receivables.ReceivableRepository;
import com.bank.vam.repository.receivables.UnmatchedPaymentRepository;
import com.bank.vam.repository.treasury.ExceptionTransactionRepository;
import com.bank.vam.repository.viban.VibanRepository;
import com.bank.vam.repository.ProgramRepository;
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

/**
 * ReconciliationService - Enhanced with Exception VA Integration.
 * 
 * NEW FEATURES (Phase 3 Enhancement):
 * - Bank interest credits → Exception VA with ExceptionTransaction
 * - FX differences → Exception VA with ExceptionTransaction  
 * - Unmatched payments → Can optionally create ExceptionTransaction
 * - Bank charges/fees → Exception VA for manual allocation
 * 
 * Integrates with:
 * - VIBAN module for payment routing
 * - Hierarchy module for balance propagation
 * - Settlement VA Resolver for Exception VA lookup
 * - Exception Transaction system for tracking
 * 
 * Reconciliation Flow:
 * 1. Payment arrives via VIBAN → VibanRoutingService routes to VA
 * 2. Check if payment is bank interest/fee → Route to Exception VA
 * 3. Otherwise attempt auto-match:
 *    a. VIBAN has referenceId → Direct match to receivable
 *    b. VIBAN without reference → Amount + tolerance matching
 *    c. No match → Create UnmatchedPayment + optional ExceptionTransaction
 * 4. Successful match → Update receivable, create ReceivablePayment
 * 
 * MVC Pattern:
 * - Entity: Receivable, ReceivablePayment, UnmatchedPayment, ExceptionTransaction
 * - Repository: ReceivableRepository, ExceptionTransactionRepository, etc.
 * - Service: ReconciliationService (this class)
 * - Controller: Integrated with ReceivablesController
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private final ReceivableRepository receivableRepository;
    private final ReceivablePaymentRepository receivablePaymentRepository;
    private final UnmatchedPaymentRepository unmatchedPaymentRepository;
    private final VibanRepository vibanRepository;
    
    // NEW: Exception handling dependencies
    private final SettlementVaResolverService settlementVaResolver;
    private final ExceptionTransactionRepository exceptionTransactionRepository;
    private final VirtualAccountRepository virtualAccountRepository;
    private final TransactionRepository transactionRepository;
    private final ProgramRepository programRepository;

    // Default confidence thresholds
    private static final int HIGH_CONFIDENCE_THRESHOLD = 90;
    private static final int MEDIUM_CONFIDENCE_THRESHOLD = 70;
    private static final int LOW_CONFIDENCE_THRESHOLD = 50;
    
    // NEW: Bank statement transaction type identifiers
    private static final Set<String> BANK_INTEREST_INDICATORS = Set.of(
        "INTEREST", "INT CREDIT", "INT PAYMENT", "CREDIT INTEREST",
        "INTEREST CREDIT", "INT/CR", "INT EARNED", "DEPOSIT INTEREST",
        "SAVINGS INTEREST", "ACCRUED INTEREST"
    );
    
    private static final Set<String> BANK_CHARGE_INDICATORS = Set.of(
        "BANK CHARGE", "SERVICE CHARGE", "ACCOUNT FEE", "MAINTENANCE FEE",
        "TRANSACTION FEE", "SWIFT CHARGE", "TRANSFER FEE", "CHG", "FEE"
    );
    
    private static final Set<String> FX_DIFFERENCE_INDICATORS = Set.of(
        "FX DIFF", "FX GAIN", "FX LOSS", "EXCHANGE DIFF", "CURRENCY ADJ",
        "FOREX", "REVAL", "REVALUATION"
    );

    // ========================================================================
    // BANK STATEMENT PROCESSING (NEW)
    // ========================================================================

    /**
     * Process a bank statement line and route to appropriate handler.
     * This is the main entry point for bank statement reconciliation.
     * 
     * @param programId Program ID
     * @param physicalAccountId Physical account ID
     * @param transactionId Transaction ID (if already created)
     * @param amount Transaction amount (positive for credit, negative for debit)
     * @param currencyCode Currency
     * @param valueDate Value date
     * @param bankReference Bank reference
     * @param description Transaction description
     * @param remittanceInfo Additional remittance information
     * @return Processing result
     */
    @Transactional
    public BankStatementProcessingResult processBankStatementLine(
            UUID programId,
            UUID physicalAccountId,
            UUID transactionId,
            BigDecimal amount,
            String currencyCode,
            LocalDate valueDate,
            String bankReference,
            String description,
            String remittanceInfo) {
        
        log.info("Processing bank statement line: program={}, amount={}, ref={}, desc={}",
                programId, amount, bankReference, description);
        
        String descUpper = description != null ? description.toUpperCase() : "";
        String remitUpper = remittanceInfo != null ? remittanceInfo.toUpperCase() : "";
        String combinedText = descUpper + " " + remitUpper;
        
        // Step 1: Check if this is bank interest
        if (isBankInterest(combinedText, amount)) {
            log.info("Detected bank interest credit: {}", bankReference);
            return processBankInterest(programId, physicalAccountId, transactionId,
                    amount, currencyCode, valueDate, bankReference, description);
        }
        
        // Step 2: Check if this is a bank charge/fee
        if (isBankCharge(combinedText, amount)) {
            log.info("Detected bank charge/fee: {}", bankReference);
            return processBankCharge(programId, physicalAccountId, transactionId,
                    amount.abs(), currencyCode, valueDate, bankReference, description);
        }
        
        // Step 3: Check if this is an FX difference
        if (isFxDifference(combinedText)) {
            log.info("Detected FX difference: {}", bankReference);
            return processFxDifference(programId, physicalAccountId, transactionId,
                    amount, currencyCode, valueDate, bankReference, description);
        }
        
        // Step 4: Regular payment - attempt reconciliation
        return BankStatementProcessingResult.builder()
                .processed(false)
                .processingType(ProcessingType.REGULAR_PAYMENT)
                .message("Regular payment - requires VIBAN-based reconciliation")
                .build();
    }

    /**
     * Process bank interest credit - routes to Exception VA.
     */
    @Transactional
    public BankStatementProcessingResult processBankInterest(
            UUID programId,
            UUID physicalAccountId,
            UUID transactionId,
            BigDecimal amount,
            String currencyCode,
            LocalDate valueDate,
            String bankReference,
            String description) {
        
        log.info("Processing bank interest: program={}, amount={} {}", programId, amount, currencyCode);
        
        try {
            // 1. Get or create Exception VA for this program and currency
            UUID corporateId = getCorporateIdFromProgram(programId);
            VirtualAccount exceptionVa = settlementVaResolver.getOrCreateExceptionVa(programId, currencyCode, corporateId);

            // 2. Credit the Exception VA
            BigDecimal balanceBefore = exceptionVa.getCurrentBalance();
            exceptionVa.credit(amount);
            virtualAccountRepository.save(exceptionVa);
            
            // 3. Create transaction record if not provided
            Transaction creditTxn = null;
            if (transactionId == null) {
                creditTxn = createExceptionVaTransaction(
                        exceptionVa, amount, currencyCode, valueDate,
                        "BANK_INTEREST", bankReference, description,
                        balanceBefore, exceptionVa.getCurrentBalance()
                );
                transactionId = creditTxn.getId();
            }
            
            // 4. Create ExceptionTransaction for tracking and manual allocation
            ExceptionTransaction exception = ExceptionTransaction.builder()
                    .exceptionNumber(ExceptionTransaction.generateExceptionNumber())
                    .programId(programId)
                    .exceptionType(ExceptionType.BANK_INTEREST)
                    .exceptionVaId(exceptionVa.getId())
                    .originalTransactionId(transactionId)
                    .amount(amount)
                    .currencyCode(currencyCode)
                    .valueDate(valueDate)
                    .bankReference(bankReference)
                    .status(ExceptionStatus.OPEN)
                    .description("Bank interest credit - pending allocation to beneficiary VA")
                    .remitterName("BANK")
                    .remitterReference(bankReference)
                    .notes("Auto-detected from bank statement. Description: " + description)
                    .build();
            
            exception = exceptionTransactionRepository.save(exception);
            
            log.info("Bank interest {} {} credited to Exception VA {}, exception #{}",
                    amount, currencyCode, exceptionVa.getVaNumber(), exception.getExceptionNumber());
            
            return BankStatementProcessingResult.builder()
                    .processed(true)
                    .processingType(ProcessingType.BANK_INTEREST)
                    .exceptionTransactionId(exception.getId())
                    .exceptionNumber(exception.getExceptionNumber())
                    .exceptionVaId(exceptionVa.getId())
                    .transactionId(transactionId)
                    .amount(amount)
                    .message("Bank interest credited to Exception VA for manual allocation")
                    .build();
                    
        } catch (Exception e) {
            log.error("Failed to process bank interest: {}", e.getMessage(), e);
            return BankStatementProcessingResult.builder()
                    .processed(false)
                    .processingType(ProcessingType.BANK_INTEREST)
                    .message("Error processing bank interest: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Process bank charge/fee - routes to Exception VA (as a debit to investigate).
     */
    @Transactional
    public BankStatementProcessingResult processBankCharge(
            UUID programId,
            UUID physicalAccountId,
            UUID transactionId,
            BigDecimal amount,
            String currencyCode,
            LocalDate valueDate,
            String bankReference,
            String description) {
        
        log.info("Processing bank charge: program={}, amount={} {}", programId, amount, currencyCode);
        
        try {
            // Get or create Exception VA
            UUID corporateId = getCorporateIdFromProgram(programId);
            VirtualAccount exceptionVa = settlementVaResolver.getOrCreateExceptionVa(programId, currencyCode, corporateId);

            // Create ExceptionTransaction for tracking (don't debit Exception VA, just track)
            ExceptionTransaction exception = ExceptionTransaction.builder()
                    .exceptionNumber(ExceptionTransaction.generateExceptionNumber())
                    .programId(programId)
                    .exceptionType(ExceptionType.BANK_CHARGE)
                    .exceptionVaId(exceptionVa.getId())
                    .originalTransactionId(transactionId)
                    .amount(amount.negate()) // Negative to indicate outflow
                    .currencyCode(currencyCode)
                    .valueDate(valueDate)
                    .bankReference(bankReference)
                    .status(ExceptionStatus.OPEN)
                    .description("Bank charge/fee - pending allocation to appropriate cost center")
                    .remitterName("BANK")
                    .remitterReference(bankReference)
                    .notes("Auto-detected bank charge. Description: " + description)
                    .build();
            
            exception = exceptionTransactionRepository.save(exception);
            
            log.info("Bank charge {} {} recorded as exception #{}, pending cost allocation",
                    amount, currencyCode, exception.getExceptionNumber());
            
            return BankStatementProcessingResult.builder()
                    .processed(true)
                    .processingType(ProcessingType.BANK_CHARGE)
                    .exceptionTransactionId(exception.getId())
                    .exceptionNumber(exception.getExceptionNumber())
                    .exceptionVaId(exceptionVa.getId())
                    .transactionId(transactionId)
                    .amount(amount.negate())
                    .message("Bank charge recorded for cost allocation")
                    .build();
                    
        } catch (Exception e) {
            log.error("Failed to process bank charge: {}", e.getMessage(), e);
            return BankStatementProcessingResult.builder()
                    .processed(false)
                    .processingType(ProcessingType.BANK_CHARGE)
                    .message("Error processing bank charge: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Process FX difference - routes to Exception VA.
     */
    @Transactional
    public BankStatementProcessingResult processFxDifference(
            UUID programId,
            UUID physicalAccountId,
            UUID transactionId,
            BigDecimal amount,
            String currencyCode,
            LocalDate valueDate,
            String bankReference,
            String description) {
        
        log.info("Processing FX difference: program={}, amount={} {}", programId, amount, currencyCode);
        
        try {
            // Get or create Exception VA
            UUID corporateId = getCorporateIdFromProgram(programId);
            VirtualAccount exceptionVa = settlementVaResolver.getOrCreateExceptionVa(programId, currencyCode, corporateId);

            // Credit or debit Exception VA based on amount sign
            BigDecimal balanceBefore = exceptionVa.getCurrentBalance();
            if (amount.compareTo(BigDecimal.ZERO) > 0) {
                exceptionVa.credit(amount);
            } else {
                exceptionVa.debit(amount.abs());
            }
            virtualAccountRepository.save(exceptionVa);
            
            // Create transaction record if not provided
            if (transactionId == null) {
                Transaction txn = createExceptionVaTransaction(
                        exceptionVa, amount, currencyCode, valueDate,
                        "FX_DIFFERENCE", bankReference, description,
                        balanceBefore, exceptionVa.getCurrentBalance()
                );
                transactionId = txn.getId();
            }
            
            // Create ExceptionTransaction
            ExceptionType exType = amount.compareTo(BigDecimal.ZERO) > 0 
                    ? ExceptionType.FX_GAIN 
                    : ExceptionType.FX_LOSS;
            
            ExceptionTransaction exception = ExceptionTransaction.builder()
                    .exceptionNumber(ExceptionTransaction.generateExceptionNumber())
                    .programId(programId)
                    .exceptionType(exType)
                    .exceptionVaId(exceptionVa.getId())
                    .originalTransactionId(transactionId)
                    .amount(amount)
                    .currencyCode(currencyCode)
                    .valueDate(valueDate)
                    .bankReference(bankReference)
                    .status(ExceptionStatus.OPEN)
                    .description(amount.compareTo(BigDecimal.ZERO) > 0 
                            ? "FX gain - pending allocation" 
                            : "FX loss - pending allocation")
                    .remitterName("BANK")
                    .remitterReference(bankReference)
                    .notes("Auto-detected FX difference. Description: " + description)
                    .build();
            
            exception = exceptionTransactionRepository.save(exception);
            
            log.info("FX {} {} {} posted to Exception VA {}, exception #{}",
                    amount.compareTo(BigDecimal.ZERO) > 0 ? "gain" : "loss",
                    amount.abs(), currencyCode, exceptionVa.getVaNumber(), exception.getExceptionNumber());
            
            return BankStatementProcessingResult.builder()
                    .processed(true)
                    .processingType(ProcessingType.FX_DIFFERENCE)
                    .exceptionTransactionId(exception.getId())
                    .exceptionNumber(exception.getExceptionNumber())
                    .exceptionVaId(exceptionVa.getId())
                    .transactionId(transactionId)
                    .amount(amount)
                    .message("FX difference posted to Exception VA")
                    .build();
                    
        } catch (Exception e) {
            log.error("Failed to process FX difference: {}", e.getMessage(), e);
            return BankStatementProcessingResult.builder()
                    .processed(false)
                    .processingType(ProcessingType.FX_DIFFERENCE)
                    .message("Error processing FX difference: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Create unmatched payment with optional Exception VA routing.
     * Enhanced to also create ExceptionTransaction for aged unmatched payments.
     */
    @Transactional
    public BankStatementProcessingResult processUnmatchedToException(
            UUID programId,
            UUID unmatchedPaymentId,
            String reason) {
        
        log.info("Moving unmatched payment {} to Exception VA: {}", unmatchedPaymentId, reason);
        
        Optional<UnmatchedPayment> unmatchedOpt = unmatchedPaymentRepository.findById(unmatchedPaymentId);
        if (unmatchedOpt.isEmpty()) {
            return BankStatementProcessingResult.builder()
                    .processed(false)
                    .processingType(ProcessingType.UNMATCHED_PAYMENT)
                    .message("Unmatched payment not found")
                    .build();
        }
        
        UnmatchedPayment unmatched = unmatchedOpt.get();
        
        try {
            // Get or create Exception VA
            UUID corporateId = getCorporateIdFromProgram(programId);
            VirtualAccount exceptionVa = settlementVaResolver.getOrCreateExceptionVa(
                    programId, unmatched.getCurrencyCode(), corporateId);

            // Create ExceptionTransaction
            ExceptionTransaction exception = ExceptionTransaction.builder()
                    .exceptionNumber(ExceptionTransaction.generateExceptionNumber())
                    .programId(programId)
                    .exceptionType(ExceptionType.UNMATCHED_PAYMENT)
                    .exceptionVaId(exceptionVa.getId())
                    .originalVaId(unmatched.getVirtualAccountId())
                    .originalTransactionId(unmatched.getTransactionId())
                    .amount(unmatched.getAmount())
                    .currencyCode(unmatched.getCurrencyCode())
                    .bankReference(unmatched.getBankReference())
                    .status(ExceptionStatus.OPEN)
                    .description("Unmatched payment moved to Exception VA: " + reason)
                    .remitterName(unmatched.getPayerName())
                    .remitterAccount(unmatched.getPayerAccount())
                    .remitterReference(unmatched.getRemittanceInfo())
                    .notes("Original unmatched payment ID: " + unmatchedPaymentId + 
                           ". Reason: " + reason)
                    .build();
            
            exception = exceptionTransactionRepository.save(exception);
            
            // Update unmatched payment status
            unmatched.setStatus(UnmatchedPayment.UnmatchedStatus.MOVED_TO_EXCEPTION);
            unmatched.setExceptionTransactionId(exception.getId());
            unmatched.setResolutionNotes("Moved to Exception VA: " + exception.getExceptionNumber());
            unmatched.setResolvedAt(LocalDateTime.now());
            unmatchedPaymentRepository.save(unmatched);
            
            log.info("Unmatched payment {} moved to Exception VA, exception #{}",
                    unmatchedPaymentId, exception.getExceptionNumber());
            
            return BankStatementProcessingResult.builder()
                    .processed(true)
                    .processingType(ProcessingType.UNMATCHED_PAYMENT)
                    .exceptionTransactionId(exception.getId())
                    .exceptionNumber(exception.getExceptionNumber())
                    .exceptionVaId(exceptionVa.getId())
                    .amount(unmatched.getAmount())
                    .message("Unmatched payment moved to Exception VA")
                    .build();
                    
        } catch (Exception e) {
            log.error("Failed to move unmatched payment to exception: {}", e.getMessage(), e);
            return BankStatementProcessingResult.builder()
                    .processed(false)
                    .processingType(ProcessingType.UNMATCHED_PAYMENT)
                    .message("Error: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Batch process aged unmatched payments to Exception VA.
     * Called by scheduled job for payments older than threshold.
     */
    @Transactional
    public BatchExceptionResult processAgedUnmatchedPayments(UUID programId, int daysOld) {
        log.info("Processing aged unmatched payments older than {} days for program {}", daysOld, programId);
        
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(daysOld);
        List<UnmatchedPayment> agedPayments = unmatchedPaymentRepository
                .findAgedPendingPayments(cutoffDate);
        
        int successCount = 0;
        int failureCount = 0;
        List<String> exceptionNumbers = new ArrayList<>();
        
        for (UnmatchedPayment payment : agedPayments) {
            try {
                BankStatementProcessingResult result = processUnmatchedToException(
                        programId, payment.getId(), 
                        "Auto-moved: payment aged " + daysOld + "+ days without match"
                );
                
                if (result.isProcessed()) {
                    successCount++;
                    exceptionNumbers.add(result.getExceptionNumber());
                } else {
                    failureCount++;
                }
            } catch (Exception e) {
                log.error("Failed to process aged payment {}: {}", payment.getId(), e.getMessage());
                failureCount++;
            }
        }
        
        log.info("Processed {} aged payments: {} success, {} failed", 
                agedPayments.size(), successCount, failureCount);
        
        return BatchExceptionResult.builder()
                .totalProcessed(agedPayments.size())
                .successCount(successCount)
                .failureCount(failureCount)
                .exceptionNumbers(exceptionNumbers)
                .build();
    }

    // ========================================================================
    // DETECTION HELPERS
    // ========================================================================

    private boolean isBankInterest(String text, BigDecimal amount) {
        // Bank interest is typically a credit (positive amount)
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        
        for (String indicator : BANK_INTEREST_INDICATORS) {
            if (text.contains(indicator)) {
                return true;
            }
        }
        return false;
    }

    private boolean isBankCharge(String text, BigDecimal amount) {
        // Bank charges are typically debits (negative amount)
        // But sometimes reported as positive in the description
        for (String indicator : BANK_CHARGE_INDICATORS) {
            if (text.contains(indicator)) {
                return true;
            }
        }
        return false;
    }

    private boolean isFxDifference(String text) {
        for (String indicator : FX_DIFFERENCE_INDICATORS) {
            if (text.contains(indicator)) {
                return true;
            }
        }
        return false;
    }

    private Transaction createExceptionVaTransaction(
            VirtualAccount exceptionVa,
            BigDecimal amount,
            String currencyCode,
            LocalDate valueDate,
            String transactionType,
            String bankReference,
            String description,
            BigDecimal balanceBefore,
            BigDecimal balanceAfter) {
        
        Transaction.MovementType movementType = amount.compareTo(BigDecimal.ZERO) > 0
                ? Transaction.MovementType.CREDIT
                : Transaction.MovementType.DEBIT;
        
        Transaction txn = Transaction.builder()
                .referenceNumber(Transaction.generateReference(movementType))
                .movementType(movementType)
                .vaId(exceptionVa.getId())
                .physicalAccountId(exceptionVa.getPhysicalAccountId())
                .programId(exceptionVa.getProgramId())
                .amount(amount.abs())
                .currencyCode(currencyCode)
                .description(transactionType + ": " + description)
                .externalReference(bankReference)
                .status(Transaction.TransactionStatus.COMPLETED)
                .transactionDate(LocalDateTime.now())
                .valueDate(valueDate)
                .channel("BANK_STATEMENT")
                .balanceBefore(balanceBefore)
                .balanceAfter(balanceAfter)
                .processingNotes("Auto-processed " + transactionType + " from bank statement")
                .build();
        
        return transactionRepository.save(txn);
    }

    // ========================================================================
    // AUTO-RECONCILIATION (Existing functionality preserved)
    // ========================================================================

    /**
     * Attempt to auto-reconcile an incoming payment.
     * Called by VibanRoutingService after routing payment to VA.
     * 
     * @param transactionId The transaction ID
     * @param vibanId The VIBAN that received the payment
     * @param amount Payment amount
     * @param currencyCode Currency
     * @param payerName Payer name from remittance
     * @param payerAccount Payer account
     * @param remittanceInfo Remittance information
     * @return ReconciliationResult with match details
     */
    @Transactional
    public ReconciliationResult attemptAutoReconcile(UUID transactionId, UUID vibanId,
                                                      BigDecimal amount, String currencyCode,
                                                      String payerName, String payerAccount,
                                                      String remittanceInfo) {
        log.info("Attempting auto-reconcile for transaction {} via VIBAN {}, amount {}", 
                 transactionId, vibanId, amount);
        
        try {
            // Step 1: Get VIBAN details
            Optional<Viban> vibanOpt = vibanRepository.findById(vibanId);
            if (vibanOpt.isEmpty()) {
                log.warn("VIBAN not found: {}", vibanId);
                return createUnmatchedResult(transactionId, vibanId, null, amount, currencyCode,
                                             payerName, payerAccount, remittanceInfo, "VIBAN not found");
            }
            
            Viban viban = vibanOpt.get();
            
            // Step 2: Check if VIBAN has direct reference to receivable
            if (viban.getReferenceType() != null && viban.getReferenceId() != null) {
                if ("INVOICE".equals(viban.getReferenceType()) || "RECEIVABLE".equals(viban.getReferenceType())) {
                    return attemptDirectMatch(transactionId, vibanId, viban, amount, currencyCode,
                                              payerName, payerAccount, remittanceInfo);
                }
            }
            
            // Step 3: Try to find receivable linked to this VIBAN
            Optional<Receivable> receivableByViban = receivableRepository.findActiveByVibanId(vibanId);
            if (receivableByViban.isPresent()) {
                return attemptVibanLinkedMatch(transactionId, vibanId, receivableByViban.get(),
                                               amount, currencyCode, payerName, payerAccount, remittanceInfo);
            }
            
            // Step 4: Try amount-based matching for the VA
            UUID virtualAccountId = viban.getVirtualAccountId();
            if (virtualAccountId != null) {
                List<Receivable> candidates = findMatchCandidates(virtualAccountId, amount, currencyCode);
                if (!candidates.isEmpty()) {
                    return attemptAmountBasedMatch(transactionId, vibanId, candidates,
                                                   amount, currencyCode, payerName, payerAccount, remittanceInfo);
                }
            }
            
            // Step 5: No match found - create unmatched payment
            log.info("No auto-match found for transaction {}", transactionId);
            return createUnmatchedResult(transactionId, vibanId, virtualAccountId, amount, currencyCode,
                                         payerName, payerAccount, remittanceInfo, "No matching receivable found");
            
        } catch (Exception e) {
            log.error("Error during auto-reconciliation for transaction {}: {}", transactionId, e.getMessage(), e);
            return ReconciliationResult.builder()
                    .success(false)
                    .matchType(MatchType.NONE)
                    .errorMessage("Reconciliation error: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Direct match when VIBAN has referenceId pointing to receivable.
     */
    private ReconciliationResult attemptDirectMatch(UUID transactionId, UUID vibanId, Viban viban,
                                                     BigDecimal amount, String currencyCode,
                                                     String payerName, String payerAccount,
                                                     String remittanceInfo) {
        UUID receivableId;
        try {
            receivableId = UUID.fromString(viban.getReferenceId());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid receivable ID format in VIBAN reference: {}", viban.getReferenceId());
            return createUnmatchedResult(transactionId, vibanId, viban.getVirtualAccountId(),
                                         amount, currencyCode, payerName, payerAccount, remittanceInfo,
                                         "Invalid receivable reference format");
        }
        Optional<Receivable> receivableOpt = receivableRepository.findById(receivableId);
        
        if (receivableOpt.isEmpty()) {
            log.warn("Referenced receivable not found: {}", receivableId);
            return createUnmatchedResult(transactionId, vibanId, viban.getVirtualAccountId(),
                                         amount, currencyCode, payerName, payerAccount, remittanceInfo,
                                         "Referenced receivable not found");
        }
        
        Receivable receivable = receivableOpt.get();
        
        // Validate receivable can accept payment
        if (!receivable.canAcceptPayment()) {
            log.warn("Receivable {} cannot accept payment, status: {}", receivableId, receivable.getStatus());
            return createUnmatchedResult(transactionId, vibanId, viban.getVirtualAccountId(),
                                         amount, currencyCode, payerName, payerAccount, remittanceInfo,
                                         "Receivable status: " + receivable.getStatus());
        }
        
        // Calculate confidence based on amount match
        int confidence = calculateAmountConfidence(amount, receivable.getOutstandingAmount(),
                                                   receivable.getAmountTolerancePercent());
        
        // Check if amount is acceptable
        if (!receivable.isPaymentAmountAcceptable(amount)) {
            log.warn("Amount {} outside acceptable range for receivable {}", amount, receivableId);
            confidence = Math.min(confidence, MEDIUM_CONFIDENCE_THRESHOLD);
        }
        
        // Create payment match
        ReceivablePayment payment = createPaymentMatch(transactionId, vibanId, receivable,
                                                        amount, currencyCode, payerName, payerAccount,
                                                        remittanceInfo, ReceivablePayment.MatchType.AUTO,
                                                        confidence, "Direct VIBAN reference match");
        
        // Update receivable
        updateReceivableAfterPayment(receivable, amount);
        
        // Update VIBAN statistics
        updateVibanAfterPayment(viban, amount);
        
        log.info("Direct match successful: transaction {} -> receivable {}, confidence {}%",
                 transactionId, receivableId, confidence);
        
        return ReconciliationResult.builder()
                .success(true)
                .matchType(MatchType.DIRECT)
                .receivableId(receivableId)
                .paymentId(payment.getId())
                .confidence(confidence)
                .matchReason("Direct VIBAN reference match")
                .build();
    }

    /**
     * Match when receivable is linked to VIBAN.
     */
    private ReconciliationResult attemptVibanLinkedMatch(UUID transactionId, UUID vibanId,
                                                          Receivable receivable, BigDecimal amount,
                                                          String currencyCode, String payerName,
                                                          String payerAccount, String remittanceInfo) {
        if (!receivable.canAcceptPayment()) {
            return createUnmatchedResult(transactionId, vibanId, receivable.getVirtualAccountId(),
                                         amount, currencyCode, payerName, payerAccount, remittanceInfo,
                                         "Receivable status: " + receivable.getStatus());
        }
        
        int confidence = calculateAmountConfidence(amount, receivable.getOutstandingAmount(),
                                                   receivable.getAmountTolerancePercent());
        
        ReceivablePayment payment = createPaymentMatch(transactionId, vibanId, receivable,
                                                        amount, currencyCode, payerName, payerAccount,
                                                        remittanceInfo, ReceivablePayment.MatchType.AUTO,
                                                        confidence, "VIBAN-linked receivable match");
        
        updateReceivableAfterPayment(receivable, amount);
        
        log.info("VIBAN-linked match successful: transaction {} -> receivable {}, confidence {}%",
                 transactionId, receivable.getId(), confidence);
        
        return ReconciliationResult.builder()
                .success(true)
                .matchType(MatchType.VIBAN_LINKED)
                .receivableId(receivable.getId())
                .paymentId(payment.getId())
                .confidence(confidence)
                .matchReason("VIBAN-linked receivable match")
                .build();
    }

    /**
     * Amount-based matching when no direct link exists.
     */
    private ReconciliationResult attemptAmountBasedMatch(UUID transactionId, UUID vibanId,
                                                          List<Receivable> candidates,
                                                          BigDecimal amount, String currencyCode,
                                                          String payerName, String payerAccount,
                                                          String remittanceInfo) {
        // Score each candidate
        List<ScoredMatch> scoredMatches = new ArrayList<>();
        for (Receivable candidate : candidates) {
            int score = scoreCandidate(candidate, amount, payerName, remittanceInfo);
            if (score >= LOW_CONFIDENCE_THRESHOLD) {
                scoredMatches.add(new ScoredMatch(candidate, score));
            }
        }
        
        if (scoredMatches.isEmpty()) {
            return createUnmatchedResult(transactionId, vibanId, candidates.get(0).getVirtualAccountId(),
                                         amount, currencyCode, payerName, payerAccount, remittanceInfo,
                                         "No candidates above confidence threshold");
        }
        
        // Sort by score descending
        scoredMatches.sort((a, b) -> Integer.compare(b.score, a.score));
        
        ScoredMatch bestMatch = scoredMatches.get(0);
        
        // Only auto-match if confidence is high enough
        if (bestMatch.score >= HIGH_CONFIDENCE_THRESHOLD) {
            ReceivablePayment payment = createPaymentMatch(transactionId, vibanId, bestMatch.receivable,
                                                            amount, currencyCode, payerName, payerAccount,
                                                            remittanceInfo, ReceivablePayment.MatchType.AUTO,
                                                            bestMatch.score, "Amount-based auto-match");
            
            updateReceivableAfterPayment(bestMatch.receivable, amount);
            
            log.info("Amount-based match successful: transaction {} -> receivable {}, confidence {}%",
                     transactionId, bestMatch.receivable.getId(), bestMatch.score);
            
            return ReconciliationResult.builder()
                    .success(true)
                    .matchType(MatchType.AMOUNT_BASED)
                    .receivableId(bestMatch.receivable.getId())
                    .paymentId(payment.getId())
                    .confidence(bestMatch.score)
                    .matchReason("Amount-based auto-match")
                    .suggestedMatches(buildSuggestedMatches(scoredMatches))
                    .build();
        } else {
            // Create unmatched with suggestions
            UnmatchedPayment unmatched = createUnmatchedPayment(transactionId, vibanId,
                                                                 candidates.get(0).getVirtualAccountId(),
                                                                 amount, currencyCode, payerName,
                                                                 payerAccount, remittanceInfo,
                                                                 "Confidence below threshold");
            
            // Store suggested matches
            unmatched.setSuggestedReceivableIds(buildSuggestedReceivableJson(scoredMatches));
            unmatchedPaymentRepository.save(unmatched);
            
            return ReconciliationResult.builder()
                    .success(false)
                    .matchType(MatchType.SUGGESTED)
                    .unmatchedPaymentId(unmatched.getId())
                    .confidence(bestMatch.score)
                    .matchReason("Confidence below auto-match threshold")
                    .suggestedMatches(buildSuggestedMatches(scoredMatches))
                    .build();
        }
    }

    // ========================================================================
    // MANUAL RECONCILIATION
    // ========================================================================

    /**
     * Manually match a payment to a receivable.
     */
    @Transactional
    public ReconciliationResult manualMatch(UUID unmatchedPaymentId, UUID receivableId, String matchedBy) {
        log.info("Manual match: unmatched {} -> receivable {} by {}", unmatchedPaymentId, receivableId, matchedBy);
        
        Optional<UnmatchedPayment> unmatchedOpt = unmatchedPaymentRepository.findById(unmatchedPaymentId);
        if (unmatchedOpt.isEmpty()) {
            return ReconciliationResult.builder()
                    .success(false)
                    .errorMessage("Unmatched payment not found")
                    .build();
        }
        
        UnmatchedPayment unmatched = unmatchedOpt.get();
        if (!unmatched.isPending()) {
            return ReconciliationResult.builder()
                    .success(false)
                    .errorMessage("Payment already resolved")
                    .build();
        }
        
        Optional<Receivable> receivableOpt = receivableRepository.findById(receivableId);
        if (receivableOpt.isEmpty()) {
            return ReconciliationResult.builder()
                    .success(false)
                    .errorMessage("Receivable not found")
                    .build();
        }
        
        Receivable receivable = receivableOpt.get();
        if (!receivable.canAcceptPayment()) {
            return ReconciliationResult.builder()
                    .success(false)
                    .errorMessage("Receivable cannot accept payment: " + receivable.getStatus())
                    .build();
        }
        
        // Create payment match
        ReceivablePayment payment = ReceivablePayment.createManualMatch(
                receivableId,
                unmatched.getTransactionId(),
                unmatched.getAmount(),
                matchedBy
        );
        payment.setVibanId(unmatched.getVibanId());
        payment.setPayerName(unmatched.getPayerName());
        payment.setPayerAccount(unmatched.getPayerAccount());
        payment.setRemittanceInfo(unmatched.getRemittanceInfo());
        payment.setCurrencyCode(unmatched.getCurrencyCode());
        payment = receivablePaymentRepository.save(payment);
        
        // Update receivable
        updateReceivableAfterPayment(receivable, unmatched.getAmount());
        
        // Update unmatched payment
        unmatched.matchToReceivable(receivableId, payment.getId(), matchedBy);
        unmatchedPaymentRepository.save(unmatched);
        
        log.info("Manual match successful: payment {} -> receivable {}", payment.getId(), receivableId);
        
        return ReconciliationResult.builder()
                .success(true)
                .matchType(MatchType.MANUAL)
                .receivableId(receivableId)
                .paymentId(payment.getId())
                .confidence(100)
                .matchReason("Manual match by " + matchedBy)
                .build();
    }

    /**
     * Return an unmatched payment.
     */
    @Transactional
    public ReconciliationResult returnPayment(UUID unmatchedPaymentId, String reason, String resolvedBy) {
        log.info("Returning unmatched payment {}: {}", unmatchedPaymentId, reason);
        
        Optional<UnmatchedPayment> unmatchedOpt = unmatchedPaymentRepository.findById(unmatchedPaymentId);
        if (unmatchedOpt.isEmpty()) {
            return ReconciliationResult.builder()
                    .success(false)
                    .errorMessage("Unmatched payment not found")
                    .build();
        }
        
        UnmatchedPayment unmatched = unmatchedOpt.get();
        if (!unmatched.isPending() && unmatched.getStatus() != UnmatchedPayment.UnmatchedStatus.ESCALATED) {
            return ReconciliationResult.builder()
                    .success(false)
                    .errorMessage("Payment already resolved")
                    .build();
        }
        
        // Generate return reference
        String returnReference = "RTN-" + System.currentTimeMillis();
        
        unmatched.returnToSender(returnReference, reason, resolvedBy);
        unmatchedPaymentRepository.save(unmatched);
        
        return ReconciliationResult.builder()
                .success(true)
                .matchType(MatchType.RETURNED)
                .unmatchedPaymentId(unmatchedPaymentId)
                .matchReason("Returned: " + reason)
                .build();
    }

    /**
     * Escalate an unmatched payment.
     */
    @Transactional
    public ReconciliationResult escalatePayment(UUID unmatchedPaymentId, String escalateTo, String reason) {
        log.info("Escalating unmatched payment {} to {}: {}", unmatchedPaymentId, escalateTo, reason);
        
        Optional<UnmatchedPayment> unmatchedOpt = unmatchedPaymentRepository.findById(unmatchedPaymentId);
        if (unmatchedOpt.isEmpty()) {
            return ReconciliationResult.builder()
                    .success(false)
                    .errorMessage("Unmatched payment not found")
                    .build();
        }
        
        UnmatchedPayment unmatched = unmatchedOpt.get();
        if (!unmatched.isPending()) {
            return ReconciliationResult.builder()
                    .success(false)
                    .errorMessage("Payment already resolved")
                    .build();
        }
        
        unmatched.escalate(escalateTo, reason);
        unmatchedPaymentRepository.save(unmatched);
        
        return ReconciliationResult.builder()
                .success(true)
                .matchType(MatchType.ESCALATED)
                .unmatchedPaymentId(unmatchedPaymentId)
                .matchReason("Escalated to " + escalateTo + ": " + reason)
                .build();
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    /**
     * Get corporateId from programId by looking up the program.
     */
    private UUID getCorporateIdFromProgram(UUID programId) {
        if (programId == null) {
            return null;
        }
        return programRepository.findById(programId)
                .map(program -> program.getCorporateId())
                .orElse(null);
    }

    private List<Receivable> findMatchCandidates(UUID virtualAccountId, BigDecimal amount, String currencyCode) {
        BigDecimal tolerance = amount.multiply(BigDecimal.valueOf(0.1));
        BigDecimal minAmount = amount.subtract(tolerance);
        BigDecimal maxAmount = amount.add(tolerance);
        
        return receivableRepository.findByVirtualAccountIdAndStatus(virtualAccountId, Receivable.ReceivableStatus.OPEN);
    }

    private int scoreCandidate(Receivable candidate, BigDecimal amount, String payerName, String remittanceInfo) {
        int score = 0;
        
        // Amount match (up to 50 points)
        int amountScore = calculateAmountConfidence(amount, candidate.getOutstandingAmount(),
                                                    candidate.getAmountTolerancePercent());
        score += (amountScore / 2);
        
        // Customer name match (up to 30 points)
        if (payerName != null && candidate.getCustomerName() != null) {
            String payerLower = payerName.toLowerCase();
            String customerLower = candidate.getCustomerName().toLowerCase();
            if (payerLower.equals(customerLower)) {
                score += 30;
            } else if (payerLower.contains(customerLower) || customerLower.contains(payerLower)) {
                score += 20;
            } else if (calculateSimilarity(payerLower, customerLower) > 0.7) {
                score += 15;
            }
        }
        
        // Reference in remittance (up to 20 points)
        if (remittanceInfo != null) {
            String remitLower = remittanceInfo.toLowerCase();
            if (candidate.getReceivableNumber() != null && 
                remitLower.contains(candidate.getReceivableNumber().toLowerCase())) {
                score += 20;
            } else if (candidate.getExternalReference() != null && 
                       remitLower.contains(candidate.getExternalReference().toLowerCase())) {
                score += 20;
            } else if (candidate.getCustomerReference() != null && 
                       remitLower.contains(candidate.getCustomerReference().toLowerCase())) {
                score += 15;
            }
        }
        
        return Math.min(score, 100);
    }

    private int calculateAmountConfidence(BigDecimal paymentAmount, BigDecimal expectedAmount, 
                                           BigDecimal tolerancePercent) {
        if (expectedAmount == null || expectedAmount.compareTo(BigDecimal.ZERO) == 0) {
            return 50;
        }
        
        BigDecimal difference = paymentAmount.subtract(expectedAmount).abs();
        BigDecimal percentDiff = difference.divide(expectedAmount, 4, RoundingMode.HALF_UP)
                                           .multiply(BigDecimal.valueOf(100));
        
        if (percentDiff.compareTo(BigDecimal.ZERO) == 0) {
            return 100;
        }
        
        BigDecimal tolerance = tolerancePercent != null ? tolerancePercent : BigDecimal.valueOf(5);
        
        if (percentDiff.compareTo(tolerance) <= 0) {
            return 95;
        } else if (percentDiff.compareTo(BigDecimal.valueOf(5)) <= 0) {
            return 90;
        } else if (percentDiff.compareTo(BigDecimal.valueOf(10)) <= 0) {
            return 80;
        } else if (percentDiff.compareTo(BigDecimal.valueOf(20)) <= 0) {
            return 60;
        } else {
            return 40;
        }
    }

    private double calculateSimilarity(String s1, String s2) {
        Set<String> set1 = new HashSet<>(Arrays.asList(s1.split("\\s+")));
        Set<String> set2 = new HashSet<>(Arrays.asList(s2.split("\\s+")));
        
        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);
        
        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);
        
        if (union.isEmpty()) return 0;
        return (double) intersection.size() / union.size();
    }

    private ReceivablePayment createPaymentMatch(UUID transactionId, UUID vibanId, Receivable receivable,
                                                  BigDecimal amount, String currencyCode,
                                                  String payerName, String payerAccount,
                                                  String remittanceInfo, ReceivablePayment.MatchType matchType,
                                                  int confidence, String matchReason) {
        BigDecimal appliedAmount = amount;
        BigDecimal unappliedAmount = BigDecimal.ZERO;
        
        if (receivable.getOutstandingAmount() != null && 
            amount.compareTo(receivable.getOutstandingAmount()) > 0) {
            if (Boolean.TRUE.equals(receivable.getAllowOverpayment())) {
                appliedAmount = receivable.getOutstandingAmount();
                unappliedAmount = amount.subtract(appliedAmount);
            }
        }
        
        ReceivablePayment payment = ReceivablePayment.builder()
                .receivableId(receivable.getId())
                .transactionId(transactionId)
                .vibanId(vibanId)
                .paymentAmount(amount)
                .appliedAmount(appliedAmount)
                .unappliedAmount(unappliedAmount)
                .currencyCode(currencyCode)
                .payerName(payerName)
                .payerAccount(payerAccount)
                .remittanceInfo(remittanceInfo)
                .matchType(matchType)
                .matchConfidence(confidence)
                .matchReason(matchReason)
                .matchedBy("SYSTEM")
                .matchedAt(LocalDateTime.now())
                .status(ReceivablePayment.PaymentMatchStatus.APPLIED)
                .build();
        
        return receivablePaymentRepository.save(payment);
    }

    private void updateReceivableAfterPayment(Receivable receivable, BigDecimal amount) {
        receivable.recordPayment(amount);
        receivableRepository.save(receivable);
    }

    private void updateVibanAfterPayment(Viban viban, BigDecimal amount) {
        viban.recordPayment(amount);
        vibanRepository.save(viban);
    }

    private ReconciliationResult createUnmatchedResult(UUID transactionId, UUID vibanId, UUID virtualAccountId,
                                                        BigDecimal amount, String currencyCode,
                                                        String payerName, String payerAccount,
                                                        String remittanceInfo, String reason) {
        UnmatchedPayment unmatched = createUnmatchedPayment(transactionId, vibanId, virtualAccountId,
                                                            amount, currencyCode, payerName, payerAccount,
                                                            remittanceInfo, reason);
        
        return ReconciliationResult.builder()
                .success(false)
                .matchType(MatchType.NONE)
                .unmatchedPaymentId(unmatched.getId())
                .matchReason(reason)
                .build();
    }

    private UnmatchedPayment createUnmatchedPayment(UUID transactionId, UUID vibanId, UUID virtualAccountId,
                                                     BigDecimal amount, String currencyCode,
                                                     String payerName, String payerAccount,
                                                     String remittanceInfo, String reason) {
        UnmatchedPayment unmatched = UnmatchedPayment.fromTransaction(
                transactionId, vibanId, virtualAccountId,
                amount, currencyCode, payerName, payerAccount, remittanceInfo
        );
        unmatched.setResolutionNotes(reason);
        return unmatchedPaymentRepository.save(unmatched);
    }

    private List<SuggestedMatch> buildSuggestedMatches(List<ScoredMatch> scoredMatches) {
        List<SuggestedMatch> suggestions = new ArrayList<>();
        for (ScoredMatch sm : scoredMatches) {
            suggestions.add(SuggestedMatch.builder()
                    .receivableId(sm.receivable.getId())
                    .receivableNumber(sm.receivable.getReceivableNumber())
                    .customerName(sm.receivable.getCustomerName())
                    .expectedAmount(sm.receivable.getOutstandingAmount())
                    .confidence(sm.score)
                    .build());
        }
        return suggestions;
    }

    private String buildSuggestedReceivableJson(List<ScoredMatch> scoredMatches) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < scoredMatches.size(); i++) {
            if (i > 0) json.append(",");
            ScoredMatch sm = scoredMatches.get(i);
            json.append("{\"id\":\"").append(sm.receivable.getId())
                .append("\",\"confidence\":").append(sm.score).append("}");
        }
        json.append("]");
        return json.toString();
    }

    // ========================================================================
    // INNER CLASSES
    // ========================================================================

    private static class ScoredMatch {
        final Receivable receivable;
        final int score;
        
        ScoredMatch(Receivable receivable, int score) {
            this.receivable = receivable;
            this.score = score;
        }
    }

    public enum MatchType {
        DIRECT,         // Direct VIBAN reference match
        VIBAN_LINKED,   // VIBAN linked to receivable
        AMOUNT_BASED,   // Amount-based auto-match
        SUGGESTED,      // Suggested but not auto-matched
        MANUAL,         // Manual match
        RETURNED,       // Returned to sender
        ESCALATED,      // Escalated for review
        NONE            // No match
    }
    
    public enum ProcessingType {
        BANK_INTEREST,      // Bank interest credit
        BANK_CHARGE,        // Bank charges/fees
        FX_DIFFERENCE,      // FX gain/loss
        UNMATCHED_PAYMENT,  // Unmatched payment to exception
        REGULAR_PAYMENT     // Regular payment for reconciliation
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ReconciliationResult {
        private boolean success;
        private MatchType matchType;
        private UUID receivableId;
        private UUID paymentId;
        private UUID unmatchedPaymentId;
        private Integer confidence;
        private String matchReason;
        private String errorMessage;
        private List<SuggestedMatch> suggestedMatches;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SuggestedMatch {
        private UUID receivableId;
        private String receivableNumber;
        private String customerName;
        private BigDecimal expectedAmount;
        private Integer confidence;
    }
    
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class BankStatementProcessingResult {
        private boolean processed;
        private ProcessingType processingType;
        private UUID exceptionTransactionId;
        private String exceptionNumber;
        private UUID exceptionVaId;
        private UUID transactionId;
        private BigDecimal amount;
        private String message;
    }
    
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class BatchExceptionResult {
        private int totalProcessed;
        private int successCount;
        private int failureCount;
        private List<String> exceptionNumbers;
    }
}