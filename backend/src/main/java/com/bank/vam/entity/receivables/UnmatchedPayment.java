package com.bank.vam.entity.receivables;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * UnmatchedPayment Entity - Payments that couldn't be auto-matched.
 * 
 * ENHANCED for Phase 3: Added support for Exception VA routing.
 * 
 * Tracks payments received that need manual intervention:
 * - No matching VIBAN found
 * - No matching receivable reference
 * - Amount mismatch outside tolerance
 * 
 * Resolution options:
 * - MATCHED: Manually matched to a receivable
 * - RETURNED: Returned to sender
 * - ESCALATED: Sent for supervisor review
 * - WRITTEN_OFF: Written off
 * - MOVED_TO_EXCEPTION: Moved to Exception VA for treasury handling (NEW)
 */
@Entity
@Table(name = "unmatched_payments", indexes = {
    @Index(name = "idx_unmatched_status", columnList = "status"),
    @Index(name = "idx_unmatched_payment_date", columnList = "payment_date"),
    @Index(name = "idx_unmatched_va", columnList = "virtual_account_id"),
    @Index(name = "idx_unmatched_viban", columnList = "viban_id"),
    @Index(name = "idx_unmatched_exception", columnList = "exception_transaction_id"),
    @Index(name = "idx_unmatched_created", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UnmatchedPayment extends BaseEntity {

    // ========================================================================
    // SOURCE
    // ========================================================================

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "viban_id")
    private UUID vibanId;

    @Column(name = "virtual_account_id")
    private UUID virtualAccountId;
    
    /**
     * Program ID for filtering by program.
     * NEW: Added for multi-program support.
     */
    @Column(name = "program_id")
    private UUID programId;

    // ========================================================================
    // PAYMENT DETAILS
    // ========================================================================

    @Column(name = "payment_reference", length = 100)
    private String paymentReference;
    
    /**
     * Bank reference from the bank statement.
     * NEW: Added for bank statement reconciliation.
     */
    @Column(name = "bank_reference", length = 100)
    private String bankReference;

    @Column(name = "payment_date", nullable = false)
    private LocalDateTime paymentDate;

    @Column(name = "amount", precision = 18, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "currency_code", nullable = false, length = 3)
    @Builder.Default
    private String currencyCode = "AED";

    // ========================================================================
    // PAYER INFO
    // ========================================================================

    @Column(name = "payer_name", length = 200)
    private String payerName;

    @Column(name = "payer_account", length = 50)
    private String payerAccount;

    @Column(name = "payer_bank", length = 100)
    private String payerBank;
    
    /**
     * Payer bank BIC/SWIFT code.
     * NEW: Added for better bank identification.
     */
    @Column(name = "payer_bank_bic", length = 11)
    private String payerBankBic;

    @Column(name = "remittance_info", columnDefinition = "TEXT")
    private String remittanceInfo;

    // ========================================================================
    // MATCHING ATTEMPTS
    // ========================================================================

    /**
     * JSON array of suggested receivable IDs with confidence scores.
     */
    @Column(name = "suggested_receivable_ids", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String suggestedReceivableIds;

    @Column(name = "match_attempts")
    @Builder.Default
    private Integer matchAttempts = 0;

    @Column(name = "last_match_attempt_at")
    private LocalDateTime lastMatchAttemptAt;
    
    /**
     * Best match confidence score from auto-matching.
     * NEW: Added for match quality tracking.
     */
    @Column(name = "best_match_confidence")
    private Integer bestMatchConfidence;

    // ========================================================================
    // STATUS
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private UnmatchedStatus status = UnmatchedStatus.PENDING;
    
    /**
     * Priority level for handling.
     * NEW: Added for prioritization.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "priority", length = 20)
    @Builder.Default
    private UnmatchedPriority priority = UnmatchedPriority.NORMAL;

    // ========================================================================
    // RESOLUTION
    // ========================================================================

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by", length = 100)
    private String resolvedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_type", length = 30)
    private ResolutionType resolutionType;

    @Column(name = "resolution_notes", columnDefinition = "TEXT")
    private String resolutionNotes;

    // ========================================================================
    // IF MATCHED
    // ========================================================================

    @Column(name = "matched_receivable_id")
    private UUID matchedReceivableId;

    @Column(name = "matched_payment_id")
    private UUID matchedPaymentId;

    // ========================================================================
    // IF RETURNED
    // ========================================================================

    @Column(name = "return_reference", length = 100)
    private String returnReference;

    @Column(name = "return_date")
    private LocalDateTime returnDate;

    @Column(name = "return_reason", length = 500)
    private String returnReason;

    // ========================================================================
    // ESCALATION
    // ========================================================================

    @Column(name = "escalated_at")
    private LocalDateTime escalatedAt;

    @Column(name = "escalated_to", length = 100)
    private String escalatedTo;

    @Column(name = "escalation_reason", length = 500)
    private String escalationReason;
    
    // ========================================================================
    // EXCEPTION VA ROUTING (NEW)
    // ========================================================================
    
    /**
     * Exception Transaction ID if moved to Exception VA.
     * NEW: Added for Exception VA routing.
     */
    @Column(name = "exception_transaction_id")
    private UUID exceptionTransactionId;
    
    /**
     * Reason for moving to Exception VA.
     * NEW: Added for audit trail.
     */
    @Column(name = "exception_reason", length = 500)
    private String exceptionReason;
    
    /**
     * Date when moved to Exception VA.
     * NEW: Added for audit trail.
     */
    @Column(name = "moved_to_exception_at")
    private LocalDateTime movedToExceptionAt;

    // ========================================================================
    // ENUMS
    // ========================================================================

    /**
     * Status of unmatched payment.
     * ENHANCED: Added MOVED_TO_EXCEPTION status.
     */
    public enum UnmatchedStatus {
        PENDING,            // Awaiting resolution
        MATCHED,            // Matched to receivable
        RETURNED,           // Returned to sender
        ESCALATED,          // Escalated for review
        WRITTEN_OFF,        // Written off
        MOVED_TO_EXCEPTION, // Moved to Exception VA (NEW)
        ON_HOLD            // On hold pending additional info (NEW)
    }

    /**
     * Resolution type.
     * ENHANCED: Added MOVED_TO_EXCEPTION type.
     */
    public enum ResolutionType {
        MATCHED,            // Manually matched
        RETURNED,           // Returned to sender
        ESCALATED,          // Escalated (still pending)
        WRITTEN_OFF,        // Written off
        MOVED_TO_EXCEPTION  // Moved to Exception VA (NEW)
    }
    
    /**
     * Priority levels for handling unmatched payments.
     * NEW: Added for prioritization.
     */
    public enum UnmatchedPriority {
        LOW,
        NORMAL,
        HIGH,
        URGENT
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    /**
     * Check if payment is pending resolution.
     */
    public boolean isPending() {
        return status == UnmatchedStatus.PENDING || 
               status == UnmatchedStatus.ON_HOLD;
    }

    /**
     * Check if payment is resolved.
     */
    public boolean isResolved() {
        return status == UnmatchedStatus.MATCHED 
            || status == UnmatchedStatus.RETURNED 
            || status == UnmatchedStatus.WRITTEN_OFF
            || status == UnmatchedStatus.MOVED_TO_EXCEPTION;
    }
    
    /**
     * Check if payment can be moved to Exception VA.
     * NEW: Added for Exception VA routing.
     */
    public boolean canMoveToException() {
        return status == UnmatchedStatus.PENDING ||
               status == UnmatchedStatus.ESCALATED ||
               status == UnmatchedStatus.ON_HOLD;
    }

    /**
     * Match to a receivable.
     */
    public void matchToReceivable(UUID receivableId, UUID paymentId, String resolvedBy) {
        this.status = UnmatchedStatus.MATCHED;
        this.resolutionType = ResolutionType.MATCHED;
        this.matchedReceivableId = receivableId;
        this.matchedPaymentId = paymentId;
        this.resolvedAt = LocalDateTime.now();
        this.resolvedBy = resolvedBy;
    }

    /**
     * Return payment to sender.
     */
    public void returnToSender(String reference, String reason, String resolvedBy) {
        this.status = UnmatchedStatus.RETURNED;
        this.resolutionType = ResolutionType.RETURNED;
        this.returnReference = reference;
        this.returnDate = LocalDateTime.now();
        this.returnReason = reason;
        this.resolvedAt = LocalDateTime.now();
        this.resolvedBy = resolvedBy;
    }

    /**
     * Escalate for review.
     */
    public void escalate(String escalateTo, String reason) {
        this.status = UnmatchedStatus.ESCALATED;
        this.escalatedAt = LocalDateTime.now();
        this.escalatedTo = escalateTo;
        this.escalationReason = reason;
    }

    /**
     * Write off payment.
     */
    public void writeOff(String reason, String resolvedBy) {
        this.status = UnmatchedStatus.WRITTEN_OFF;
        this.resolutionType = ResolutionType.WRITTEN_OFF;
        this.resolutionNotes = reason;
        this.resolvedAt = LocalDateTime.now();
        this.resolvedBy = resolvedBy;
    }
    
    /**
     * Move to Exception VA.
     * NEW: Added for Exception VA routing.
     */
    public void moveToExceptionVa(UUID exceptionTransactionId, String reason, String movedBy) {
        this.status = UnmatchedStatus.MOVED_TO_EXCEPTION;
        this.resolutionType = ResolutionType.MOVED_TO_EXCEPTION;
        this.exceptionTransactionId = exceptionTransactionId;
        this.exceptionReason = reason;
        this.movedToExceptionAt = LocalDateTime.now();
        this.resolvedAt = LocalDateTime.now();
        this.resolvedBy = movedBy;
        this.resolutionNotes = "Moved to Exception VA: " + reason;
    }
    
    /**
     * Put payment on hold.
     * NEW: Added for workflow management.
     */
    public void putOnHold(String reason) {
        this.status = UnmatchedStatus.ON_HOLD;
        this.resolutionNotes = (this.resolutionNotes != null ? this.resolutionNotes + "\n" : "") +
                              "ON HOLD: " + reason;
    }

    /**
     * Increment match attempts.
     */
    public void incrementMatchAttempts() {
        this.matchAttempts = (matchAttempts != null ? matchAttempts : 0) + 1;
        this.lastMatchAttemptAt = LocalDateTime.now();
    }
    
    /**
     * Get days since payment was received.
     * NEW: Added for aging tracking.
     */
    public long getDaysSinceReceived() {
        if (paymentDate == null) return 0;
        return java.time.temporal.ChronoUnit.DAYS.between(
            paymentDate.toLocalDate(),
            java.time.LocalDate.now()
        );
    }
    
    /**
     * Get aging bucket for reporting.
     * NEW: Added for aging reports.
     */
    public String getAgingBucket() {
        long days = getDaysSinceReceived();
        if (days <= 1) return "TODAY";
        if (days <= 7) return "1_7_DAYS";
        if (days <= 14) return "8_14_DAYS";
        if (days <= 30) return "15_30_DAYS";
        if (days <= 60) return "31_60_DAYS";
        return "OVER_60_DAYS";
    }
    
    /**
     * Check if payment is aged (older than specified days).
     * NEW: Added for aged payment processing.
     */
    public boolean isAged(int days) {
        return getDaysSinceReceived() >= days;
    }
    
    /**
     * Check if payment should be auto-escalated based on age.
     * NEW: Added for auto-escalation.
     */
    public boolean shouldAutoEscalate(int escalationDays) {
        return isPending() && getDaysSinceReceived() >= escalationDays;
    }
    
    /**
     * Check if payment should be moved to Exception VA based on age.
     * NEW: Added for aged payment processing.
     */
    public boolean shouldMoveToException(int exceptionDays) {
        return (status == UnmatchedStatus.PENDING || 
                status == UnmatchedStatus.ESCALATED) && 
               getDaysSinceReceived() >= exceptionDays;
    }

    // ========================================================================
    // FACTORY METHOD
    // ========================================================================

    /**
     * Create from an unmatched transaction.
     */
    public static UnmatchedPayment fromTransaction(UUID transactionId, UUID vibanId, 
                                                    UUID virtualAccountId,
                                                    BigDecimal amount, String currencyCode,
                                                    String payerName, String payerAccount,
                                                    String remittanceInfo) {
        return UnmatchedPayment.builder()
            .transactionId(transactionId)
            .vibanId(vibanId)
            .virtualAccountId(virtualAccountId)
            .paymentDate(LocalDateTime.now())
            .amount(amount)
            .currencyCode(currencyCode)
            .payerName(payerName)
            .payerAccount(payerAccount)
            .remittanceInfo(remittanceInfo)
            .status(UnmatchedStatus.PENDING)
            .priority(UnmatchedPriority.NORMAL)
            .matchAttempts(1)
            .lastMatchAttemptAt(LocalDateTime.now())
            .build();
    }
    
    /**
     * Create from bank statement line.
     * NEW: Added for bank statement processing.
     */
    public static UnmatchedPayment fromBankStatement(
            UUID programId,
            UUID virtualAccountId,
            UUID transactionId,
            BigDecimal amount,
            String currencyCode,
            String bankReference,
            String payerName,
            String payerAccount,
            String remittanceInfo) {
        return UnmatchedPayment.builder()
            .programId(programId)
            .transactionId(transactionId)
            .virtualAccountId(virtualAccountId)
            .paymentDate(LocalDateTime.now())
            .amount(amount)
            .currencyCode(currencyCode)
            .bankReference(bankReference)
            .payerName(payerName)
            .payerAccount(payerAccount)
            .remittanceInfo(remittanceInfo)
            .status(UnmatchedStatus.PENDING)
            .priority(UnmatchedPriority.NORMAL)
            .matchAttempts(1)
            .lastMatchAttemptAt(LocalDateTime.now())
            .build();
    }
}