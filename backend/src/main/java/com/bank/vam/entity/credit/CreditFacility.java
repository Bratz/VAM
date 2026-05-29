package com.bank.vam.entity.credit;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Credit Facility - Specific credit lines under a Credit Agreement.
 * 
 * UNIFIED ARCHITECTURE v4.2:
 * Credit Facilities are sub-limits under a master Credit Agreement.
 * Each facility has its own type, limit, and utilization tracking.
 * 
 * Facility Types:
 * - OVERDRAFT: Bank overdraft facility
 * - REVOLVING_CREDIT: Revolving credit line
 * - TERM_LOAN: Fixed term loan
 * - WORKING_CAPITAL: Working capital facility
 * - LETTER_OF_CREDIT: LC facility
 * - BANK_GUARANTEE: BG facility
 * - CASH_CREDIT: Cash credit line
 * - BILL_DISCOUNTING: Bill discounting facility
 * 
 * Domain Model:
 * - CreditAgreement (1) -> CreditFacility (N)
 * - CreditFacility (1) -> CreditLimit (N) [allocations to VAs]
 * - PhysicalAccount (1) -> CreditFacility (1) [for overdrafts]
 */
@Entity
@Table(name = "credit_facilities",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_facility_ref", columnNames = {"external_reference"})
    },
    indexes = {
        @Index(name = "idx_cf_agreement", columnList = "credit_agreement_id"),
        @Index(name = "idx_cf_corporate", columnList = "corporate_id"),
        @Index(name = "idx_cf_type", columnList = "facility_type"),
        @Index(name = "idx_cf_status", columnList = "status"),
        @Index(name = "idx_cf_physical", columnList = "physical_account_id"),
        @Index(name = "idx_cf_expiry", columnList = "expiry_date")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditFacility extends BaseEntity {

    // ========================================================================
    // IDENTITY
    // ========================================================================

    /**
     * External reference from CBS.
     */
    @Column(name = "external_reference", unique = true, length = 50)
    private String externalReference;

    /**
     * Facility name/description.
     */
    @Column(name = "facility_name", nullable = false, length = 200)
    private String facilityName;

    /**
     * Parent credit agreement.
     */
    @Column(name = "credit_agreement_id", nullable = false)
    private UUID creditAgreementId;

    /**
     * Corporate that holds this facility.
     */
    @Column(name = "corporate_id", nullable = false)
    private UUID corporateId;

    // ========================================================================
    // FACILITY TYPE
    // ========================================================================

    /**
     * Type of credit facility.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "facility_type", nullable = false, length = 30)
    private FacilityType facilityType;

    /**
     * Sub-type for more granular classification.
     */
    @Column(name = "facility_sub_type", length = 30)
    private String facilitySubType;

    // ========================================================================
    // LIMITS
    // ========================================================================

    /**
     * Sanctioned/approved limit.
     */
    @Column(name = "sanctioned_limit", nullable = false, precision = 19, scale = 4)
    private BigDecimal sanctionedLimit;

    /**
     * Drawing power (may differ from sanctioned for WC facilities).
     */
    @Column(name = "drawing_power", precision = 19, scale = 4)
    private BigDecimal drawingPower;

    /**
     * Current outstanding/utilized amount.
     */
    @Column(name = "current_outstanding", precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal currentOutstanding = BigDecimal.ZERO;

    /**
     * Available limit (sanctioned - outstanding).
     */
    @Column(name = "available_limit", precision = 19, scale = 4)
    private BigDecimal availableLimit;

    /**
     * Currency of the facility.
     */
    @Column(name = "facility_currency", nullable = false, length = 3)
    private String facilityCurrency;

    // ========================================================================
    // LINKED ACCOUNT
    // ========================================================================

    /**
     * Physical account this facility is linked to (for overdrafts).
     */
    @Column(name = "physical_account_id")
    private UUID physicalAccountId;

    // ========================================================================
    // VALIDITY
    // ========================================================================

    /**
     * Facility sanction date.
     */
    @Column(name = "sanction_date")
    private LocalDate sanctionDate;

    /**
     * Facility effective date.
     */
    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    /**
     * Facility expiry date.
     */
    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    /**
     * Last renewal date.
     */
    @Column(name = "last_renewal_date")
    private LocalDate lastRenewalDate;

    // ========================================================================
    // PRICING
    // ========================================================================

    /**
     * Interest rate type (FIXED, FLOATING).
     */
    @Column(name = "interest_rate_type", length = 20)
    private String interestRateType;

    /**
     * Base rate type (SOFR, EURIBOR, MCLR, etc.).
     */
    @Column(name = "base_rate_type", length = 20)
    private String baseRateType;

    /**
     * Current base rate value.
     */
    @Column(name = "base_rate_value", precision = 8, scale = 5)
    private BigDecimal baseRateValue;

    /**
     * Spread over base rate (in percentage).
     */
    @Column(name = "spread_percent", precision = 8, scale = 5)
    private BigDecimal spreadPercent;

    /**
     * Effective interest rate.
     */
    @Column(name = "effective_rate", precision = 8, scale = 5)
    private BigDecimal effectiveRate;

    /**
     * Penal interest rate for overdue.
     */
    @Column(name = "penal_rate", precision = 8, scale = 5)
    private BigDecimal penalRate;

    // ========================================================================
    // FEES
    // ========================================================================

    /**
     * Processing fee percentage.
     */
    @Column(name = "processing_fee_percent", precision = 8, scale = 5)
    private BigDecimal processingFeePercent;

    /**
     * Commitment fee on undrawn (percentage).
     */
    @Column(name = "commitment_fee_percent", precision = 8, scale = 5)
    private BigDecimal commitmentFeePercent;

    // ========================================================================
    // REPAYMENT (for term loans)
    // ========================================================================

    /**
     * Repayment frequency (MONTHLY, QUARTERLY, etc.).
     */
    @Column(name = "repayment_frequency", length = 20)
    private String repaymentFrequency;

    /**
     * EMI amount (for term loans).
     */
    @Column(name = "emi_amount", precision = 19, scale = 4)
    private BigDecimal emiAmount;

    /**
     * Number of installments.
     */
    @Column(name = "total_installments")
    private Integer totalInstallments;

    /**
     * Installments paid.
     */
    @Column(name = "installments_paid")
    @Builder.Default
    private Integer installmentsPaid = 0;

    // ========================================================================
    // SECURITY/COLLATERAL
    // ========================================================================

    /**
     * Whether facility is secured.
     */
    @Column(name = "is_secured")
    @Builder.Default
    private Boolean isSecured = false;

    /**
     * Security type (PROPERTY, INVENTORY, RECEIVABLES, etc.).
     */
    @Column(name = "security_type", length = 50)
    private String securityType;

    /**
     * Security value.
     */
    @Column(name = "security_value", precision = 19, scale = 4)
    private BigDecimal securityValue;

    // ========================================================================
    // STATUS
    // ========================================================================

    /**
     * Facility status.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    @Builder.Default
    private FacilityStatus status = FacilityStatus.ACTIVE;

    // ========================================================================
    // CBS SYNC
    // ========================================================================

    @Column(name = "last_sync_at")
    private java.time.LocalDateTime lastSyncAt;

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

    public enum FacilityType {
        OVERDRAFT,          // Bank overdraft
        REVOLVING_CREDIT,   // Revolving credit line
        TERM_LOAN,          // Fixed term loan
        WORKING_CAPITAL,    // Working capital facility
        LETTER_OF_CREDIT,   // LC facility
        BANK_GUARANTEE,     // BG facility
        CASH_CREDIT,        // Cash credit line
        BILL_DISCOUNTING,   // Bill discounting
        EXPORT_CREDIT,      // Export credit facility
        IMPORT_CREDIT,      // Import credit facility
        PACKING_CREDIT,     // Packing credit
        BUYERS_CREDIT,      // Buyers credit
        SUPPLIERS_CREDIT    // Suppliers credit
    }

    public enum FacilityStatus {
        DRAFT,
        PENDING_APPROVAL,
        ACTIVE,
        SUSPENDED,
        EXPIRED,
        CLOSED,
        CANCELLED,
        OVERDUE
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    public boolean isActive() {
        return status == FacilityStatus.ACTIVE;
    }

    public boolean isExpired() {
        if (expiryDate == null) return false;
        return LocalDate.now().isAfter(expiryDate);
    }

    public boolean isOverdraft() {
        return facilityType == FacilityType.OVERDRAFT;
    }

    public boolean isRevolvingCredit() {
        return facilityType == FacilityType.REVOLVING_CREDIT;
    }

    public boolean isTermLoan() {
        return facilityType == FacilityType.TERM_LOAN;
    }

    public BigDecimal getEffectiveLimit() {
        // For WC facilities, drawing power may be less than sanctioned
        if (drawingPower != null && drawingPower.compareTo(sanctionedLimit) < 0) {
            return drawingPower;
        }
        return sanctionedLimit;
    }

    public BigDecimal getUtilizationPercent() {
        BigDecimal limit = getEffectiveLimit();
        if (limit == null || limit.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return currentOutstanding.multiply(new BigDecimal("100"))
            .divide(limit, 2, java.math.RoundingMode.HALF_UP);
    }

    public void recalculateAvailable() {
        BigDecimal limit = getEffectiveLimit();
        if (limit != null) {
            this.availableLimit = limit.subtract(
                currentOutstanding != null ? currentOutstanding : BigDecimal.ZERO
            );
        }
    }

    public void drawDown(BigDecimal amount) {
        if (currentOutstanding == null) currentOutstanding = BigDecimal.ZERO;
        currentOutstanding = currentOutstanding.add(amount);
        recalculateAvailable();
    }

    public void repay(BigDecimal amount) {
        if (currentOutstanding == null) currentOutstanding = BigDecimal.ZERO;
        currentOutstanding = currentOutstanding.subtract(amount);
        if (currentOutstanding.compareTo(BigDecimal.ZERO) < 0) {
            currentOutstanding = BigDecimal.ZERO;
        }
        recalculateAvailable();
    }

    public void calculateEffectiveRate() {
        if (baseRateValue != null && spreadPercent != null) {
            this.effectiveRate = baseRateValue.add(spreadPercent);
        }
    }

    @PrePersist
    @PreUpdate
    private void preSave() {
        recalculateAvailable();
        calculateEffectiveRate();
    }
}