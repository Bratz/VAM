package com.bank.vam.entity.treasury;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Exception Transaction Entity - Tracks transactions parked in Exception VA.
 * 
 * ENHANCED for Phase 3: Added support for bank statement reconciliation exceptions.
 * 
 * Exception transactions are created when:
 * - No Settlement VA is found (fee posted to Exception VA as fallback)
 * - Incoming payment cannot be matched to a receivable
 * - Bank reconciliation differences
 * - Failed outbound payments
 * - Invalid VIBAN references
 * - Bank interest credits pending allocation
 * - Bank charges/fees pending cost allocation
 * - FX gains/losses pending allocation
 * 
 * Resolution:
 * - Treasury team manually allocates exceptions to appropriate target VAs
 * - Resolution creates paired debit (from Exception VA) and credit (to target VA) transactions
 * - Full audit trail maintained
 */
@Entity
@Table(name = "exception_transactions", indexes = {
    @Index(name = "idx_exception_txn_status", columnList = "status"),
    @Index(name = "idx_exception_txn_exception_va", columnList = "exception_va_id"),
    @Index(name = "idx_exception_txn_type", columnList = "exception_type"),
    @Index(name = "idx_exception_txn_created", columnList = "created_at"),
    @Index(name = "idx_exception_txn_program", columnList = "program_id"),
    @Index(name = "idx_exception_txn_value_date", columnList = "value_date")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExceptionTransaction extends BaseEntity {

    // ========================================================================
    // IDENTIFICATION
    // ========================================================================

    /**
     * Unique exception number for reference.
     * Format: EXC-YYYYMMDD-NNNNNN
     */
    @Column(name = "exception_number", unique = true, nullable = false, length = 30)
    private String exceptionNumber;

    /**
     * Type of exception.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "exception_type", nullable = false, length = 30)
    private ExceptionType exceptionType;
    
    /**
     * Program ID for filtering exceptions by program.
     * NEW: Added for multi-program support.
     */
    @Column(name = "program_id")
    private UUID programId;

    // ========================================================================
    // SOURCE TRANSACTION
    // ========================================================================

    /**
     * Original transaction that caused this exception (if any).
     */
    @Column(name = "original_transaction_id")
    private UUID originalTransactionId;

    /**
     * Original VA where the transaction originated.
     */
    @Column(name = "original_va_id")
    private UUID originalVaId;

    // ========================================================================
    // EXCEPTION VA
    // ========================================================================

    /**
     * The Exception VA where this amount is parked.
     */
    @Column(name = "exception_va_id", nullable = false)
    private UUID exceptionVaId;

    // ========================================================================
    // AMOUNT
    // ========================================================================

    /**
     * Amount parked in exception.
     * Positive for credits, negative for debits.
     */
    @Column(name = "amount", precision = 18, scale = 2, nullable = false)
    private BigDecimal amount;

    /**
     * Currency code.
     */
    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;
    
    /**
     * Value date of the transaction.
     * NEW: Added for bank statement reconciliation.
     */
    @Column(name = "value_date")
    private LocalDate valueDate;

    // ========================================================================
    // CONTEXT - BANK STATEMENT INFO
    // ========================================================================

    /**
     * Bank reference (for reconciliation exceptions).
     */
    @Column(name = "bank_reference", length = 100)
    private String bankReference;

    /**
     * Remitter information (for unmatched payments).
     * @deprecated Use remitterName, remitterAccount, remitterReference instead
     */
    @Column(name = "remitter_info", length = 500)
    private String remitterInfo;
    
    /**
     * Remitter name.
     * NEW: Added for better remitter tracking.
     */
    @Column(name = "remitter_name", length = 200)
    private String remitterName;
    
    /**
     * Remitter account number.
     * NEW: Added for better remitter tracking.
     */
    @Column(name = "remitter_account", length = 50)
    private String remitterAccount;
    
    /**
     * Remitter reference (payment reference from sender).
     * NEW: Added for better remitter tracking.
     */
    @Column(name = "remitter_reference", length = 100)
    private String remitterReference;

    /**
     * Description of the exception.
     */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
    
    /**
     * Additional notes (for audit trail).
     * NEW: Added for additional context.
     */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    // ========================================================================
    // STATUS
    // ========================================================================

    /**
     * Current status of the exception.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ExceptionStatus status = ExceptionStatus.OPEN;
    
    /**
     * Priority level for exception handling.
     * NEW: Added for exception prioritization.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "priority", length = 20)
    @Builder.Default
    private ExceptionPriority priority = ExceptionPriority.NORMAL;

    // ========================================================================
    // RESOLUTION
    // ========================================================================

    /**
     * Target VA for allocation (set when resolved).
     */
    @Column(name = "target_va_id")
    private UUID targetVaId;

    /**
     * Notes about the resolution.
     */
    @Column(name = "resolution_notes", columnDefinition = "TEXT")
    private String resolutionNotes;

    /**
     * User who resolved the exception.
     */
    @Column(name = "resolved_by", length = 100)
    private String resolvedBy;

    /**
     * Timestamp when resolved.
     */
    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;
    
    /**
     * Resolution transaction ID (the transaction that allocated the exception).
     * NEW: Added for audit trail.
     */
    @Column(name = "resolution_transaction_id")
    private UUID resolutionTransactionId;
    
    /**
     * Correlation ID linking related exceptions.
     * NEW: Added for grouping related exceptions.
     */
    @Column(name = "correlation_id", length = 50)
    private String correlationId;

    // ========================================================================
    // ENUMS
    // ========================================================================

    /**
     * Types of exceptions that can occur.
     * ENHANCED: Added BANK_CHARGE, FX_GAIN, FX_LOSS
     */
    public enum ExceptionType {
        /**
         * Incoming payment without matching receivable or VIBAN.
         */
        UNMATCHED_PAYMENT,
        
        /**
         * Bank statement vs book difference.
         */
        RECONCILIATION_DIFF,
        
        /**
         * Outbound payment that failed after debit.
         */
        FAILED_PAYMENT,
        
        /**
         * Payment with invalid VIBAN reference.
         */
        INVALID_VIBAN,
        
        /**
         * Expected vs actual amount mismatch.
         */
        AMOUNT_MISMATCH,
        
        /**
         * Possible duplicate payment.
         */
        DUPLICATE_PAYMENT,
        
        /**
         * Bank interest credit pending allocation.
         */
        BANK_INTEREST,
        
        /**
         * Bank charge/fee pending cost allocation.
         * NEW: Added for bank statement processing.
         */
        BANK_CHARGE,
        
        /**
         * FX conversion gain or loss (generic).
         */
        FX_DIFFERENCE,
        
        /**
         * FX conversion gain (credit).
         * NEW: Added for specific FX tracking.
         */
        FX_GAIN,
        
        /**
         * FX conversion loss (debit).
         * NEW: Added for specific FX tracking.
         */
        FX_LOSS,
        
        /**
         * System processing error.
         */
        SYSTEM_ERROR,
        
        /**
         * No Settlement VA found - fee posted to Exception VA as fallback.
         */
        MISSING_SETTLEMENT_VA,
        
        /**
         * Overpayment received (payment exceeds outstanding amount).
         * NEW: Added for overpayment tracking.
         */
        OVERPAYMENT,
        
        /**
         * Refund pending processing.
         * NEW: Added for refund handling.
         */
        PENDING_REFUND
    }

    /**
     * Status of exception resolution.
     */
    public enum ExceptionStatus {
        /**
         * Exception is open and awaiting resolution.
         */
        OPEN,
        
        /**
         * Exception is being investigated.
         */
        IN_PROGRESS,
        
        /**
         * Exception has been resolved and allocated.
         */
        RESOLVED,
        
        /**
         * Exception has been written off (cannot be resolved).
         */
        WRITTEN_OFF,
        
        /**
         * Amount has been returned to source.
         */
        RETURNED,
        
        /**
         * Exception is on hold pending additional information.
         * NEW: Added for workflow management.
         */
        ON_HOLD,
        
        /**
         * Exception has been escalated to supervisor.
         * NEW: Added for escalation tracking.
         */
        ESCALATED
    }
    
    /**
     * Priority levels for exception handling.
     * NEW: Added for exception prioritization.
     */
    public enum ExceptionPriority {
        LOW,
        NORMAL,
        HIGH,
        CRITICAL
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    /**
     * Check if exception is still open.
     */
    public boolean isOpen() {
        return status == ExceptionStatus.OPEN || 
               status == ExceptionStatus.IN_PROGRESS ||
               status == ExceptionStatus.ON_HOLD ||
               status == ExceptionStatus.ESCALATED;
    }

    /**
     * Check if exception has been resolved.
     */
    public boolean isResolved() {
        return status == ExceptionStatus.RESOLVED;
    }

    /**
     * Check if exception can be resolved (is open).
     */
    public boolean canBeResolved() {
        return status == ExceptionStatus.OPEN || 
               status == ExceptionStatus.IN_PROGRESS ||
               status == ExceptionStatus.ON_HOLD ||
               status == ExceptionStatus.ESCALATED;
    }

    /**
     * Start investigation of this exception.
     */
    public void startInvestigation(String investigator) {
        if (this.status == ExceptionStatus.OPEN) {
            this.status = ExceptionStatus.IN_PROGRESS;
            this.resolvedBy = investigator;
        }
    }
    
    /**
     * Put exception on hold.
     * NEW: Added for workflow management.
     */
    public void putOnHold(String reason, String heldBy) {
        if (canBeResolved()) {
            this.status = ExceptionStatus.ON_HOLD;
            this.notes = (this.notes != null ? this.notes + "\n" : "") + 
                        "ON HOLD by " + heldBy + ": " + reason;
        }
    }
    
    /**
     * Escalate exception to supervisor.
     * NEW: Added for escalation workflow.
     */
    public void escalate(String reason, String escalatedBy) {
        if (canBeResolved()) {
            this.status = ExceptionStatus.ESCALATED;
            this.priority = ExceptionPriority.HIGH;
            this.notes = (this.notes != null ? this.notes + "\n" : "") + 
                        "ESCALATED by " + escalatedBy + ": " + reason;
        }
    }

    /**
     * Resolve the exception by allocating to a target VA.
     */
    public void resolve(UUID targetVaId, String notes, String resolvedBy) {
        this.status = ExceptionStatus.RESOLVED;
        this.targetVaId = targetVaId;
        this.resolutionNotes = notes;
        this.resolvedBy = resolvedBy;
        this.resolvedAt = LocalDateTime.now();
    }
    
    /**
     * Resolve the exception with transaction reference.
     * NEW: Added for full audit trail.
     */
    public void resolve(UUID targetVaId, UUID resolutionTransactionId, String notes, String resolvedBy) {
        this.status = ExceptionStatus.RESOLVED;
        this.targetVaId = targetVaId;
        this.resolutionTransactionId = resolutionTransactionId;
        this.resolutionNotes = notes;
        this.resolvedBy = resolvedBy;
        this.resolvedAt = LocalDateTime.now();
    }

    /**
     * Write off the exception (cannot be resolved).
     */
    public void writeOff(String reason, String approvedBy) {
        this.status = ExceptionStatus.WRITTEN_OFF;
        this.resolutionNotes = "WRITTEN OFF: " + reason;
        this.resolvedBy = approvedBy;
        this.resolvedAt = LocalDateTime.now();
    }

    /**
     * Return the exception amount to source.
     */
    public void returnToSource(String reason, String approvedBy) {
        this.status = ExceptionStatus.RETURNED;
        this.targetVaId = this.originalVaId;
        this.resolutionNotes = "RETURNED: " + reason;
        this.resolvedBy = approvedBy;
        this.resolvedAt = LocalDateTime.now();
    }

    /**
     * Check if this is a fee-related exception (missing Settlement VA).
     */
    public boolean isFeeException() {
        return exceptionType == ExceptionType.MISSING_SETTLEMENT_VA;
    }

    /**
     * Check if this is a payment-related exception.
     */
    public boolean isPaymentException() {
        return exceptionType == ExceptionType.UNMATCHED_PAYMENT ||
               exceptionType == ExceptionType.FAILED_PAYMENT ||
               exceptionType == ExceptionType.INVALID_VIBAN ||
               exceptionType == ExceptionType.DUPLICATE_PAYMENT ||
               exceptionType == ExceptionType.OVERPAYMENT;
    }

    /**
     * Check if this is a reconciliation-related exception.
     */
    public boolean isReconciliationException() {
        return exceptionType == ExceptionType.RECONCILIATION_DIFF ||
               exceptionType == ExceptionType.AMOUNT_MISMATCH ||
               exceptionType == ExceptionType.FX_DIFFERENCE ||
               exceptionType == ExceptionType.FX_GAIN ||
               exceptionType == ExceptionType.FX_LOSS;
    }
    
    /**
     * Check if this is a bank statement exception.
     * NEW: Added for bank statement processing.
     */
    public boolean isBankStatementException() {
        return exceptionType == ExceptionType.BANK_INTEREST ||
               exceptionType == ExceptionType.BANK_CHARGE ||
               exceptionType == ExceptionType.FX_GAIN ||
               exceptionType == ExceptionType.FX_LOSS ||
               exceptionType == ExceptionType.FX_DIFFERENCE;
    }
    
    /**
     * Check if this is a credit (positive amount).
     */
    public boolean isCredit() {
        return amount != null && amount.compareTo(BigDecimal.ZERO) > 0;
    }
    
    /**
     * Check if this is a debit (negative amount).
     */
    public boolean isDebit() {
        return amount != null && amount.compareTo(BigDecimal.ZERO) < 0;
    }

    /**
     * Get days since exception was created.
     */
    public long getDaysSinceCreated() {
        if (getCreatedAt() == null) return 0;
        return java.time.temporal.ChronoUnit.DAYS.between(
            getCreatedAt().toLocalDate(), 
            java.time.LocalDate.now()
        );
    }

    /**
     * Get aging bucket for reporting.
     */
    public String getAgingBucket() {
        long days = getDaysSinceCreated();
        if (days <= 1) return "TODAY";
        if (days <= 7) return "1_7_DAYS";
        if (days <= 30) return "8_30_DAYS";
        if (days <= 60) return "31_60_DAYS";
        if (days <= 90) return "61_90_DAYS";
        return "OVER_90_DAYS";
    }
    
    /**
     * Get absolute amount (always positive).
     */
    public BigDecimal getAbsoluteAmount() {
        return amount != null ? amount.abs() : BigDecimal.ZERO;
    }

    // ========================================================================
    // FACTORY METHODS
    // ========================================================================

    /**
     * Generate unique exception number.
     */
    public static String generateExceptionNumber() {
        String datePart = java.time.LocalDate.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        String randomPart = String.format("%06d", (int)(Math.random() * 1000000));
        return "EXC-" + datePart + "-" + randomPart;
    }

    /**
     * Create exception for missing Settlement VA.
     */
    public static ExceptionTransaction createMissingSettlementVaException(
            UUID exceptionVaId, UUID originalVaId, UUID originalTransactionId,
            BigDecimal amount, String currencyCode, String description) {
        return ExceptionTransaction.builder()
            .exceptionNumber(generateExceptionNumber())
            .exceptionType(ExceptionType.MISSING_SETTLEMENT_VA)
            .exceptionVaId(exceptionVaId)
            .originalVaId(originalVaId)
            .originalTransactionId(originalTransactionId)
            .amount(amount)
            .currencyCode(currencyCode)
            .description(description)
            .status(ExceptionStatus.OPEN)
            .priority(ExceptionPriority.NORMAL)
            .build();
    }

    /**
     * Create exception for unmatched payment.
     */
    public static ExceptionTransaction createUnmatchedPaymentException(
            UUID exceptionVaId, BigDecimal amount, String currencyCode,
            String bankReference, String remitterInfo) {
        return ExceptionTransaction.builder()
            .exceptionNumber(generateExceptionNumber())
            .exceptionType(ExceptionType.UNMATCHED_PAYMENT)
            .exceptionVaId(exceptionVaId)
            .amount(amount)
            .currencyCode(currencyCode)
            .bankReference(bankReference)
            .remitterInfo(remitterInfo)
            .description("Unmatched incoming payment")
            .status(ExceptionStatus.OPEN)
            .priority(ExceptionPriority.NORMAL)
            .build();
    }

    /**
     * Create exception for bank interest.
     */
    public static ExceptionTransaction createBankInterestException(
            UUID exceptionVaId, BigDecimal amount, String currencyCode,
            String bankReference, String description) {
        return ExceptionTransaction.builder()
            .exceptionNumber(generateExceptionNumber())
            .exceptionType(ExceptionType.BANK_INTEREST)
            .exceptionVaId(exceptionVaId)
            .amount(amount)
            .currencyCode(currencyCode)
            .bankReference(bankReference)
            .description(description != null ? description : "Bank interest credit pending allocation")
            .status(ExceptionStatus.OPEN)
            .priority(ExceptionPriority.LOW)
            .build();
    }
    
    /**
     * Create exception for bank charge/fee.
     * NEW: Added for bank statement processing.
     */
    public static ExceptionTransaction createBankChargeException(
            UUID programId, UUID exceptionVaId, BigDecimal amount, String currencyCode,
            LocalDate valueDate, String bankReference, String description) {
        return ExceptionTransaction.builder()
            .exceptionNumber(generateExceptionNumber())
            .programId(programId)
            .exceptionType(ExceptionType.BANK_CHARGE)
            .exceptionVaId(exceptionVaId)
            .amount(amount.negate()) // Bank charges are debits
            .currencyCode(currencyCode)
            .valueDate(valueDate)
            .bankReference(bankReference)
            .description(description != null ? description : "Bank charge pending cost allocation")
            .status(ExceptionStatus.OPEN)
            .priority(ExceptionPriority.LOW)
            .build();
    }
    
    /**
     * Create exception for FX gain.
     * NEW: Added for bank statement processing.
     */
    public static ExceptionTransaction createFxGainException(
            UUID programId, UUID exceptionVaId, BigDecimal amount, String currencyCode,
            LocalDate valueDate, String bankReference, String description) {
        return ExceptionTransaction.builder()
            .exceptionNumber(generateExceptionNumber())
            .programId(programId)
            .exceptionType(ExceptionType.FX_GAIN)
            .exceptionVaId(exceptionVaId)
            .amount(amount.abs()) // FX gains are credits
            .currencyCode(currencyCode)
            .valueDate(valueDate)
            .bankReference(bankReference)
            .description(description != null ? description : "FX gain pending allocation")
            .status(ExceptionStatus.OPEN)
            .priority(ExceptionPriority.LOW)
            .build();
    }
    
    /**
     * Create exception for FX loss.
     * NEW: Added for bank statement processing.
     */
    public static ExceptionTransaction createFxLossException(
            UUID programId, UUID exceptionVaId, BigDecimal amount, String currencyCode,
            LocalDate valueDate, String bankReference, String description) {
        return ExceptionTransaction.builder()
            .exceptionNumber(generateExceptionNumber())
            .programId(programId)
            .exceptionType(ExceptionType.FX_LOSS)
            .exceptionVaId(exceptionVaId)
            .amount(amount.abs().negate()) // FX losses are debits
            .currencyCode(currencyCode)
            .valueDate(valueDate)
            .bankReference(bankReference)
            .description(description != null ? description : "FX loss pending allocation")
            .status(ExceptionStatus.OPEN)
            .priority(ExceptionPriority.NORMAL)
            .build();
    }
    
    /**
     * Create exception for overpayment.
     * NEW: Added for overpayment handling.
     */
    public static ExceptionTransaction createOverpaymentException(
            UUID programId, UUID exceptionVaId, UUID originalVaId,
            BigDecimal overpaymentAmount, String currencyCode,
            String remitterName, String remitterReference, String description) {
        return ExceptionTransaction.builder()
            .exceptionNumber(generateExceptionNumber())
            .programId(programId)
            .exceptionType(ExceptionType.OVERPAYMENT)
            .exceptionVaId(exceptionVaId)
            .originalVaId(originalVaId)
            .amount(overpaymentAmount)
            .currencyCode(currencyCode)
            .remitterName(remitterName)
            .remitterReference(remitterReference)
            .description(description != null ? description : "Overpayment pending refund or allocation")
            .status(ExceptionStatus.OPEN)
            .priority(ExceptionPriority.NORMAL)
            .build();
    }
}