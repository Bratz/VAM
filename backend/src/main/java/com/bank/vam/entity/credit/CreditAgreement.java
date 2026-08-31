package com.bank.vam.entity.credit;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Credit Agreement - Master credit agreement from Core Banking System.
 * 
 * UNIFIED ARCHITECTURE v4.2:
 * Credit Agreements represent master credit facilities agreed with banks.
 * They contain one or more Credit Facilities (sub-limits).
 * 
 * A corporate may have multiple credit agreements with different banks.
 * Each agreement has an overall limit, and facilities draw from this limit.
 * 
 * Example:
 * - ACME Corp has €100M agreement with Deutsche Bank
 * - Under this: €50M Overdraft, €30M Revolving, €20M Working Capital
 * 
 * Domain Model:
 * - Corporate (1) -> CreditAgreement (N)
 * - CreditAgreement (1) -> CreditFacility (N)
 * - CreditAgreement synced from CBS
 */
@Entity
@Table(name = "credit_agreements",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_credit_agreement_ref", columnNames = {"external_reference"})
    },
    indexes = {
        @Index(name = "idx_ca_corporate", columnList = "corporate_id"),
        @Index(name = "idx_ca_bank", columnList = "bank_code"),
        @Index(name = "idx_ca_status", columnList = "status"),
        @Index(name = "idx_ca_expiry", columnList = "expiry_date"),
        @Index(name = "idx_ca_external_ref", columnList = "external_reference")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditAgreement extends BaseEntity {

    // ========================================================================
    // IDENTITY
    // ========================================================================

    /**
     * External reference from CBS.
     */
    @Column(name = "external_reference", unique = true, length = 50)
    private String externalReference;

    /**
     * Agreement name/description.
     */
    @Column(name = "agreement_name", nullable = false, length = 200)
    private String agreementName;

    /**
     * Corporate that holds this agreement.
     */
    @Column(name = "corporate_id", nullable = false)
    private UUID corporateId;

    // ========================================================================
    // BANK DETAILS
    // ========================================================================

    /**
     * Bank code (SWIFT/BIC or internal code).
     */
    @Column(name = "bank_code", length = 20)
    private String bankCode;

    /**
     * Bank name.
     */
    @Column(name = "bank_name", length = 100)
    private String bankName;

    /**
     * Branch/location.
     */
    @Column(name = "bank_branch", length = 100)
    private String bankBranch;

    // ========================================================================
    // AGREEMENT LIMITS
    // ========================================================================

    /**
     * Total agreement limit.
     */
    @Column(name = "total_limit", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalLimit;

    /**
     * Currency of the limit.
     */
    @Column(name = "limit_currency", nullable = false, length = 3)
    private String limitCurrency;

    /**
     * Total utilized across all facilities.
     */
    @Column(name = "total_utilized", precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal totalUtilized = BigDecimal.ZERO;

    /**
     * Available limit (total - utilized).
     */
    @Column(name = "available_limit", precision = 19, scale = 4)
    private BigDecimal availableLimit;

    // ========================================================================
    // VALIDITY
    // ========================================================================

    /**
     * Agreement effective date.
     */
    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    /**
     * Agreement expiry date.
     */
    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    /**
     * Last review date.
     */
    @Column(name = "last_review_date")
    private LocalDate lastReviewDate;

    /**
     * Next review date.
     */
    @Column(name = "next_review_date")
    private LocalDate nextReviewDate;

    // ========================================================================
    // PRICING
    // ========================================================================

    /**
     * Arrangement fee percentage.
     */
    @Column(name = "arrangement_fee_percent", precision = 8, scale = 5)
    private BigDecimal arrangementFeePercent;

    /**
     * Commitment fee on undrawn portion (percentage).
     */
    @Column(name = "commitment_fee_percent", precision = 8, scale = 5)
    private BigDecimal commitmentFeePercent;

    /**
     * Base rate type (SOFR, EURIBOR, etc.).
     */
    @Column(name = "base_rate_type", length = 20)
    private String baseRateType;

    /**
     * Spread over base rate (in basis points).
     */
    @Column(name = "spread_bps", precision = 10, scale = 4)
    private BigDecimal spreadBps;

    // ========================================================================
    // STATUS
    // ========================================================================

    /**
     * Agreement status.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    @Builder.Default
    private AgreementStatus status = AgreementStatus.ACTIVE;

    // ========================================================================
    // COVENANTS
    // ========================================================================

    /**
     * Whether agreement has financial covenants.
     */
    @Column(name = "has_covenants")
    @Builder.Default
    private Boolean hasCovenants = false;

    /**
     * Covenant details (JSON).
     */
    @Column(name = "covenant_details", columnDefinition = "TEXT")
    private String covenantDetails;

    // ========================================================================
    // CBS SYNC
    // ========================================================================

    /**
     * When last synced from CBS.
     */
    @Column(name = "last_sync_at")
    private java.time.LocalDateTime lastSyncAt;

    /**
     * CBS system source.
     */
    @Column(name = "source_system", length = 50)
    private String sourceSystem;

    // ========================================================================
    // NOTES
    // ========================================================================

    @Column(name = "notes", length = 1000)
    private String notes;

    // ========================================================================
    // ENUMS
    // ========================================================================

    public enum AgreementStatus {
        DRAFT,          // Not yet finalized
        PENDING,        // Pending approval
        ACTIVE,         // Active and available
        SUSPENDED,      // Temporarily suspended
        EXPIRED,        // Past expiry date
        TERMINATED,     // Manually terminated
        CANCELLED       // Cancelled before activation
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    public boolean isActive() {
        return status == AgreementStatus.ACTIVE;
    }

    public boolean isExpired() {
        if (expiryDate == null) return false;
        return LocalDate.now().isAfter(expiryDate);
    }

    public boolean isExpiringSoon(int days) {
        if (expiryDate == null) return false;
        return expiryDate.isBefore(LocalDate.now().plusDays(days));
    }

    public BigDecimal getUtilizationPercent() {
        if (totalLimit == null || totalLimit.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return totalUtilized.multiply(new BigDecimal("100"))
            .divide(totalLimit, 2, java.math.RoundingMode.HALF_UP);
    }

    public void recalculateAvailable() {
        if (totalLimit != null) {
            this.availableLimit = totalLimit.subtract(
                totalUtilized != null ? totalUtilized : BigDecimal.ZERO
            );
        }
    }

    public void utilize(BigDecimal amount) {
        if (totalUtilized == null) totalUtilized = BigDecimal.ZERO;
        totalUtilized = totalUtilized.add(amount);
        recalculateAvailable();
    }

    public void release(BigDecimal amount) {
        if (totalUtilized == null) totalUtilized = BigDecimal.ZERO;
        totalUtilized = totalUtilized.subtract(amount);
        if (totalUtilized.compareTo(BigDecimal.ZERO) < 0) {
            totalUtilized = BigDecimal.ZERO;
        }
        recalculateAvailable();
    }

    @PrePersist
    @PreUpdate
    private void preSave() {
        recalculateAvailable();
    }
}