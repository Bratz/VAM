package com.bank.vam.entity.escrow;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Escrow Contract entity - Manages escrow arrangements between parties.
 * 
 * Escrow Types:
 * - TRADE: Goods/services trade
 * - REAL_ESTATE: Property transactions  
 * - M_AND_A: Mergers and acquisitions
 * - MILESTONE: Project milestone payments
 * - RENT: Rental deposits and payments
 * 
 * Flow:
 * 1. Create contract (PENDING_FUNDING)
 * 2. Fund escrow (FUNDED)
 * 3. Release on conditions (PARTIALLY_RELEASED -> RELEASED)
 * 4. Handle disputes if needed (DISPUTED)
 */
@Entity
@Table(name = "escrow_contracts", indexes = {
    @Index(name = "idx_escrow_reference", columnList = "escrow_reference", unique = true),
    @Index(name = "idx_escrow_buyer", columnList = "buyer_id"),
    @Index(name = "idx_escrow_seller", columnList = "seller_id"),
    @Index(name = "idx_escrow_status", columnList = "status"),
    @Index(name = "idx_escrow_va", columnList = "escrow_va_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EscrowContract extends BaseEntity {

    // ========================================================================
    // Reference
    // ========================================================================

    @Column(name = "escrow_reference", unique = true, nullable = false, length = 50)
    private String escrowReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "escrow_type", nullable = false, length = 20)
    private EscrowType escrowType;

    // ========================================================================
    // Parties
    // ========================================================================

    @Column(name = "buyer_id")
    private UUID buyerId;

    @Column(name = "buyer_name", length = 200)
    private String buyerName;

    @Column(name = "seller_id")
    private UUID sellerId;

    @Column(name = "seller_name", length = 200)
    private String sellerName;

    // ========================================================================
    // Linked VA (escrow account)
    // ========================================================================

    @Column(name = "escrow_va_id")
    private UUID escrowVaId;

    @Column(name = "corporate_id")
    private UUID corporateId;

    @Column(name = "program_id")
    private UUID programId;

    // ========================================================================
    // Amounts
    // ========================================================================

    @Column(name = "contract_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal contractAmount;

    @Column(name = "funded_amount", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal fundedAmount = BigDecimal.ZERO;

    @Column(name = "released_amount", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal releasedAmount = BigDecimal.ZERO;

    @Column(name = "current_balance", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal currentBalance = BigDecimal.ZERO;

    @Column(name = "currency_code", nullable = false, length = 3)
    @Builder.Default
    private String currencyCode = "AED";

    // ========================================================================
    // Fees
    // ========================================================================

    @Column(name = "setup_fee", precision = 18, scale = 2)
    private BigDecimal setupFee;

    @Column(name = "setup_fee_charged")
    @Builder.Default
    private Boolean setupFeeCharged = false;

    @Column(name = "total_fees_charged", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal totalFeesCharged = BigDecimal.ZERO;

    // ========================================================================
    // Status & Dates
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private EscrowStatus status = EscrowStatus.PENDING_FUNDING;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "funded_at")
    private LocalDateTime fundedAt;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @Column(name = "disputed_at")
    private LocalDateTime disputedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    // ========================================================================
    // Conditions
    // ========================================================================

    @Column(name = "release_conditions", columnDefinition = "TEXT")
    private String releaseConditions;

    @Column(name = "dispute_reason", length = 500)
    private String disputeReason;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    // ========================================================================
    // Audit
    // ========================================================================

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "last_modified_by", length = 100)
    private String lastModifiedBy;

    // ========================================================================
    // Enums
    // ========================================================================

    public enum EscrowType {
        TRADE,
        REAL_ESTATE,
        M_AND_A,
        MILESTONE,
        RENT
    }

    public enum EscrowStatus {
        PENDING_FUNDING,
        PARTIALLY_FUNDED,
        FUNDED,
        PARTIALLY_RELEASED,
        RELEASED,
        DISPUTED,
        CANCELLED,
        EXPIRED
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Record funding.
     */
    public void recordFunding(BigDecimal amount) {
        this.fundedAmount = (this.fundedAmount != null ? this.fundedAmount : BigDecimal.ZERO).add(amount);
        this.currentBalance = (this.currentBalance != null ? this.currentBalance : BigDecimal.ZERO).add(amount);
        
        if (this.currentBalance.compareTo(this.contractAmount) >= 0) {
            this.status = EscrowStatus.FUNDED;
            this.fundedAt = LocalDateTime.now();
        } else {
            this.status = EscrowStatus.PARTIALLY_FUNDED;
        }
    }

    /**
     * Record release.
     */
    public void recordRelease(BigDecimal amount) {
        this.releasedAmount = (this.releasedAmount != null ? this.releasedAmount : BigDecimal.ZERO).add(amount);
        this.currentBalance = (this.currentBalance != null ? this.currentBalance : BigDecimal.ZERO).subtract(amount);
        
        if (this.currentBalance.compareTo(BigDecimal.ZERO) <= 0) {
            this.status = EscrowStatus.RELEASED;
            this.releasedAt = LocalDateTime.now();
        } else {
            this.status = EscrowStatus.PARTIALLY_RELEASED;
        }
    }

    /**
     * Record fee charged.
     */
    public void recordFeeCharged(BigDecimal fee) {
        this.totalFeesCharged = (this.totalFeesCharged != null ? this.totalFeesCharged : BigDecimal.ZERO).add(fee);
    }

    /**
     * Mark as disputed.
     */
    public void dispute(String reason) {
        this.status = EscrowStatus.DISPUTED;
        this.disputeReason = reason;
        this.disputedAt = LocalDateTime.now();
    }

    /**
     * Cancel escrow.
     */
    public void cancel(String reason) {
        this.status = EscrowStatus.CANCELLED;
        this.cancellationReason = reason;
        this.cancelledAt = LocalDateTime.now();
    }

    /**
     * Check if escrow can accept funding.
     */
    public boolean canFund() {
        return status == EscrowStatus.PENDING_FUNDING || status == EscrowStatus.PARTIALLY_FUNDED;
    }

    /**
     * Check if escrow can release funds.
     */
    public boolean canRelease() {
        return status == EscrowStatus.FUNDED || status == EscrowStatus.PARTIALLY_RELEASED;
    }

    /**
     * Check if escrow is expired.
     */
    public boolean isExpired() {
        if (expiryDate == null) return false;
        return LocalDate.now().isAfter(expiryDate);
    }

    /**
     * Get remaining amount to be funded.
     */
    public BigDecimal getRemainingToFund() {
        return contractAmount.subtract(fundedAmount != null ? fundedAmount : BigDecimal.ZERO);
    }

    /**
     * Get remaining balance for release.
     */
    public BigDecimal getRemainingForRelease() {
        return currentBalance != null ? currentBalance : BigDecimal.ZERO;
    }
}