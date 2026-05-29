package com.bank.vam.entity.receivables;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * ReceivablePayment Entity - Links payments/transactions to receivables.
 * 
 * Records the matching between incoming payments and receivables:
 * - AUTO: System-matched via VIBAN or reference
 * - MANUAL: User-matched
 * - SUGGESTED: System-suggested, user-confirmed
 * - PARTIAL: Partial payment match
 * 
 * Domain Model:
 * - Receivable (1) -> ReceivablePayment (N)
 * - Transaction (1) -> ReceivablePayment (0..1)
 * - Viban (1) -> ReceivablePayment (N)
 */
@Entity
@Table(name = "receivable_payments", indexes = {
    @Index(name = "idx_recv_payment_receivable", columnList = "receivable_id"),
    @Index(name = "idx_recv_payment_transaction", columnList = "transaction_id"),
    @Index(name = "idx_recv_payment_viban", columnList = "viban_id"),
    @Index(name = "idx_recv_payment_date", columnList = "payment_date"),
    @Index(name = "idx_recv_payment_status", columnList = "status"),
    @Index(name = "idx_recv_payment_match_type", columnList = "match_type")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReceivablePayment extends BaseEntity {

    // ========================================================================
    // LINKS
    // ========================================================================

    @Column(name = "receivable_id", nullable = false)
    private UUID receivableId;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "viban_id")
    private UUID vibanId;

    // ========================================================================
    // PAYMENT DETAILS
    // ========================================================================

    @Column(name = "payment_reference", length = 100)
    private String paymentReference;

    @Column(name = "payment_date", nullable = false)
    @Builder.Default
    private LocalDateTime paymentDate = LocalDateTime.now();

    @Column(name = "value_date")
    private LocalDate valueDate;

    // ========================================================================
    // AMOUNTS
    // ========================================================================

    @Column(name = "payment_amount", precision = 18, scale = 2, nullable = false)
    private BigDecimal paymentAmount;

    @Column(name = "applied_amount", precision = 18, scale = 2, nullable = false)
    private BigDecimal appliedAmount;

    @Column(name = "unapplied_amount", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal unappliedAmount = BigDecimal.ZERO;

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

    @Column(name = "remittance_info", columnDefinition = "TEXT")
    private String remittanceInfo;

    // ========================================================================
    // MATCHING INFO
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false, length = 20)
    @Builder.Default
    private MatchType matchType = MatchType.MANUAL;

    @Column(name = "match_confidence")
    private Integer matchConfidence;

    @Column(name = "match_reason", length = 500)
    private String matchReason;

    @Column(name = "matched_by", length = 100)
    private String matchedBy;

    @Column(name = "matched_at")
    private LocalDateTime matchedAt;

    // ========================================================================
    // STATUS
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private PaymentMatchStatus status = PaymentMatchStatus.APPLIED;

    // ========================================================================
    // REVERSAL TRACKING
    // ========================================================================

    @Column(name = "reversed_at")
    private LocalDateTime reversedAt;

    @Column(name = "reversal_reason", length = 500)
    private String reversalReason;

    @Column(name = "reversal_reference", length = 100)
    private String reversalReference;

    // ========================================================================
    // ENUMS
    // ========================================================================

    public enum MatchType {
        AUTO,       // System-matched via VIBAN or reference
        MANUAL,     // User-matched
        SUGGESTED,  // System-suggested, user-confirmed
        PARTIAL,    // Partial payment match
        SYSTEM      // System-generated (e.g., fee, adjustment)
    }

    public enum PaymentMatchStatus {
        PENDING,    // Awaiting confirmation
        APPLIED,    // Applied to receivable
        REVERSED,   // Reversed/cancelled
        RETURNED,   // Returned to payer
        DISPUTED    // Under dispute
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    /**
     * Check if this is an auto-matched payment.
     */
    public boolean isAutoMatched() {
        return matchType == MatchType.AUTO;
    }

    /**
     * Check if payment is applied.
     */
    public boolean isApplied() {
        return status == PaymentMatchStatus.APPLIED;
    }

    /**
     * Check if payment can be reversed.
     */
    public boolean canBeReversed() {
        return status == PaymentMatchStatus.APPLIED && reversedAt == null;
    }

    /**
     * Check if full payment amount was applied.
     */
    public boolean isFullyApplied() {
        return unappliedAmount == null || unappliedAmount.compareTo(BigDecimal.ZERO) == 0;
    }

    /**
     * Reverse this payment match.
     */
    public void reverse(String reason, String reference) {
        this.status = PaymentMatchStatus.REVERSED;
        this.reversedAt = LocalDateTime.now();
        this.reversalReason = reason;
        this.reversalReference = reference;
    }

    // ========================================================================
    // FACTORY METHODS
    // ========================================================================

    /**
     * Create an auto-matched payment.
     */
    public static ReceivablePayment createAutoMatch(UUID receivableId, UUID transactionId, 
                                                     UUID vibanId, BigDecimal amount,
                                                     String payerName, int confidence, 
                                                     String matchReason) {
        return ReceivablePayment.builder()
            .receivableId(receivableId)
            .transactionId(transactionId)
            .vibanId(vibanId)
            .paymentAmount(amount)
            .appliedAmount(amount)
            .payerName(payerName)
            .matchType(MatchType.AUTO)
            .matchConfidence(confidence)
            .matchReason(matchReason)
            .matchedAt(LocalDateTime.now())
            .matchedBy("SYSTEM")
            .status(PaymentMatchStatus.APPLIED)
            .build();
    }

    /**
     * Create a manual payment match.
     */
    public static ReceivablePayment createManualMatch(UUID receivableId, UUID transactionId,
                                                       BigDecimal amount, String matchedBy) {
        return ReceivablePayment.builder()
            .receivableId(receivableId)
            .transactionId(transactionId)
            .paymentAmount(amount)
            .appliedAmount(amount)
            .matchType(MatchType.MANUAL)
            .matchConfidence(100)
            .matchReason("Manual match by user")
            .matchedAt(LocalDateTime.now())
            .matchedBy(matchedBy)
            .status(PaymentMatchStatus.APPLIED)
            .build();
    }

    /**
     * Create a partial payment match.
     */
    public static ReceivablePayment createPartialMatch(UUID receivableId, UUID transactionId,
                                                        BigDecimal paymentAmount, 
                                                        BigDecimal appliedAmount,
                                                        String matchReason) {
        return ReceivablePayment.builder()
            .receivableId(receivableId)
            .transactionId(transactionId)
            .paymentAmount(paymentAmount)
            .appliedAmount(appliedAmount)
            .unappliedAmount(paymentAmount.subtract(appliedAmount))
            .matchType(MatchType.PARTIAL)
            .matchConfidence(80)
            .matchReason(matchReason)
            .matchedAt(LocalDateTime.now())
            .matchedBy("SYSTEM")
            .status(PaymentMatchStatus.APPLIED)
            .build();
    }
}