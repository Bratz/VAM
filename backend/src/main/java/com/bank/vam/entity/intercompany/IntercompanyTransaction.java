package com.bank.vam.entity.intercompany;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entity representing an intercompany transaction.
 * Tracks POBO (Pay On Behalf Of) and COBO (Collect On Behalf Of) operations
 * and their settlement status.
 * 
 * V2: Aligned with IntercompanyDto enums
 */
@Entity
@Table(name = "intercompany_transactions", indexes = {
        @Index(name = "idx_ic_tx_ref", columnList = "transaction_ref"),
        @Index(name = "idx_ic_tx_paying_entity", columnList = "paying_entity_id"),
        @Index(name = "idx_ic_tx_behalf_entity", columnList = "behalf_entity_id"),
        @Index(name = "idx_ic_tx_status", columnList = "status"),
        @Index(name = "idx_ic_tx_settlement", columnList = "settlement_ref"),
        @Index(name = "idx_ic_tx_type", columnList = "transaction_type")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class IntercompanyTransaction extends BaseEntity {

    /**
     * Transaction types - must match DTO enum exactly.
     */
    public enum TransactionType {
        POBO,               // Pay On Behalf Of (simplified)
        POBO_PAYMENT,       // Pay On Behalf Of - Central treasury pays for subsidiary
        COBO,               // Collect On Behalf Of (simplified)
        COBO_COLLECTION,    // Collect On Behalf Of - Central treasury collects for subsidiary
        SETTLEMENT,         // Settlement/netting transaction
        INTEREST,           // Interest accrual transaction
        ADJUSTMENT,         // Manual adjustment
        // Dual-sided IC accounting (Mirror Account Model)
        IC_RECEIVABLE,      // Treasury's receivable from subsidiary (Treasury view)
        IC_PAYABLE          // Subsidiary's payable to treasury (Subsidiary view)
    }

    /**
     * Transaction status - must match DTO enum exactly.
     */
    public enum TransactionStatus {
        PENDING,            // Created, awaiting processing
        ACTIVE,             // COBO: Waiting for payment to arrive
        PROCESSED,          // Payment/collection executed, awaiting settlement
        COMPLETED,          // Transaction fully completed
        SETTLED,            // Settled via netting cycle
        REVERSED,           // Reversed/cancelled
        FAILED              // Failed to process
    }

    // ========================================================================
    // IDENTIFICATION
    // ========================================================================

    @Column(name = "transaction_ref", nullable = false, unique = true, length = 50)
    private String transactionRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 20)
    private TransactionType transactionType;

    // ========================================================================
    // PAYING/COLLECTING ENTITY
    // ========================================================================

    @Column(name = "paying_entity_id", nullable = false)
    private UUID payingEntityId;

    @Column(name = "paying_entity_code", nullable = false, length = 20)
    private String payingEntityCode;

    @Column(name = "paying_entity_name", nullable = false, length = 100)
    private String payingEntityName;

    // ========================================================================
    // BEHALF ENTITY
    // ========================================================================

    @Column(name = "behalf_entity_id", nullable = false)
    private UUID behalfEntityId;

    @Column(name = "behalf_entity_code", nullable = false, length = 20)
    private String behalfEntityCode;

    @Column(name = "behalf_entity_name", nullable = false, length = 100)
    private String behalfEntityName;

    // ========================================================================
    // AMOUNT
    // ========================================================================

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "charges", precision = 19, scale = 4)
    private BigDecimal charges;

    @Column(name = "net_amount", precision = 19, scale = 4)
    private BigDecimal netAmount;

    // ========================================================================
    // ORIGINAL REFERENCE
    // ========================================================================

    @Column(name = "original_reference", length = 100)
    private String originalReference;

    @Column(name = "original_reference_type", length = 20)
    private String originalReferenceType;

    @Column(name = "original_reference_id")
    private UUID originalReferenceId;

    // ========================================================================
    // IHB INTEGRATION
    // ========================================================================

    @Column(name = "ihb_loan_id")
    private UUID ihbLoanId;

    @Column(name = "ihb_deposit_id")
    private UUID ihbDepositId;

    @Column(name = "ihb_interest_rate", precision = 8, scale = 4)
    private BigDecimal ihbInterestRate;

    @Column(name = "accrued_interest", precision = 19, scale = 4)
    private BigDecimal accruedInterest;

    // ========================================================================
    // VIBAN INTEGRATION
    // ========================================================================

    @Column(name = "viban_id")
    private UUID vibanId;

    @Column(name = "viban", length = 34)
    private String viban;

    // ========================================================================
    // STATUS
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TransactionStatus status;

    @Column(name = "status_reason", length = 255)
    private String statusReason;

    // ========================================================================
    // SETTLEMENT
    // ========================================================================

    @Column(name = "settlement_ref", length = 50)
    private String settlementRef;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    // ========================================================================
    // TIMESTAMPS
    // ========================================================================

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "value_date")
    private LocalDateTime valueDate;

    // ========================================================================
    // DESCRIPTION
    // ========================================================================

    @Column(name = "description", length = 500)
    private String description;

    // ========================================================================
    // AUDIT
    // ========================================================================

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "processed_by", length = 100)
    private String processedBy;

    @Column(name = "settled_by", length = 100)
    private String settledBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    public boolean isPobo() {
        return transactionType == TransactionType.POBO || transactionType == TransactionType.POBO_PAYMENT;
    }

    public boolean isCobo() {
        return transactionType == TransactionType.COBO || transactionType == TransactionType.COBO_COLLECTION;
    }

    public boolean isPending() {
        return status == TransactionStatus.PENDING;
    }

    public boolean isProcessed() {
        return status == TransactionStatus.PROCESSED;
    }

    public boolean isSettled() {
        return status == TransactionStatus.SETTLED;
    }

    public BigDecimal getTotalWithCharges() {
        return amount.add(charges != null ? charges : BigDecimal.ZERO);
    }
}