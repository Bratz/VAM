package com.bank.vam.entity.treasury;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "ihb_loans")
public class IhbLoan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "loan_reference", nullable = false, unique = true, length = 20)
    private String loanReference;

    /**
     * Legacy IhbEntity reference - nullable for unified architecture.
     * @deprecated Use lenderLegalEntityId instead
     */
    @Deprecated
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lender_entity_id", nullable = true)
    private IhbEntity lenderEntity;

    @Column(name = "lender_entity_code", length = 20)
    private String lenderEntityCode;

    /**
     * Legacy IhbEntity reference - nullable for unified architecture.
     * @deprecated Use borrowerLegalEntityId instead
     */
    @Deprecated
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "borrower_entity_id", nullable = true)
    private IhbEntity borrowerEntity;

    @Column(name = "borrower_entity_code", length = 20)
    private String borrowerEntityCode;

    @Column(name = "principal_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal principalAmount;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode = "AED";

    @Column(name = "outstanding_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal outstandingAmount;

    @Column(name = "interest_rate", nullable = false, precision = 8, scale = 4)
    private BigDecimal interestRate;

    @Enumerated(EnumType.STRING)
    @Column(name = "interest_type", length = 20)
    private InterestType interestType = InterestType.FIXED;

    @Column(name = "base_rate_type", length = 20)
    private String baseRateType;

    @Column(name = "spread", precision = 8, scale = 4)
    private BigDecimal spread;

    @Column(name = "accrued_interest", precision = 18, scale = 2)
    private BigDecimal accruedInterest = BigDecimal.ZERO;

    @Column(name = "total_interest_paid", precision = 18, scale = 2)
    private BigDecimal totalInterestPaid = BigDecimal.ZERO;

    @Column(name = "disbursement_date", nullable = false)
    private LocalDate disbursementDate;

    @Column(name = "maturity_date", nullable = false)
    private LocalDate maturityDate;

    @Column(name = "next_interest_date")
    private LocalDate nextInterestDate;

    @Column(name = "next_payment_date")
    private LocalDate nextPaymentDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "repayment_frequency", length = 20)
    private RepaymentFrequency repaymentFrequency = RepaymentFrequency.MONTHLY;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private LoanStatus status = LoanStatus.ACTIVE;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "created_by", length = 100)
    private String createdBy;

    public enum InterestType {
        FIXED, FLOATING
    }

    public enum RepaymentFrequency {
        BULLET, MONTHLY, QUARTERLY, ANNUAL
    }

    /**
     * Loan Status Lifecycle:
     *
     * SWEEP-CREATED (deficit funding, autoCreated=true):
     *   COMMITTED → SETTLED → MATURED (auto on next sweep cycle)
     *
     * MANUAL (autoCreated=false):
     *   PENDING → APPROVED → SETTLED → MATURED/PREPAID
     *           → REJECTED
     *           → CANCELLED
     */
    public enum LoanStatus {
        // ===== Settlement Lifecycle =====
        /** Manual loan awaiting Treasury approval */
        PENDING,
        /** Manual loan approved, awaiting EOD settlement */
        APPROVED,
        /** Sweep loan (deficit funding) committed, awaiting EOD settlement */
        COMMITTED,
        /** Funds settled, loan active and accruing interest */
        SETTLED,

        // ===== Terminal States =====
        /** Reached maturity date */
        MATURED,
        /** Prepaid before maturity */
        PREPAID,
        /** In default (payment overdue) */
        DEFAULT,
        /** Rejected by Treasury (manual only) */
        REJECTED,
        /** Cancelled by requestor before approval (manual only) */
        CANCELLED,

        // ===== Legacy (deprecated) =====
        /** @deprecated Use SETTLED instead. Kept for backward compatibility. */
        @Deprecated
        ACTIVE
    }


    // ============================================================================
// PATCH: IhbLoan.java - Add LegalEntity References
// ============================================================================
// 
// Add these fields to the existing IhbLoan entity
//
// LOCATION: backend/src/main/java/com/bank/vam/entity/treasury/IhbLoan.java
// ============================================================================

// ADD these fields alongside existing fields:

    // ========================================================================
    // LEGAL ENTITY REFERENCES (NEW - Phase 2 Unification)
    // ========================================================================

    /**
     * Lender Legal Entity ID (new unified reference).
     */
    @Column(name = "lender_legal_entity_id")
    private UUID lenderLegalEntityId;

    /**
     * Borrower Legal Entity ID (new unified reference).
     */
    @Column(name = "borrower_legal_entity_id")
    private UUID borrowerLegalEntityId;

    /**
     * Corporate ID for scoping queries.
     */
    @Column(name = "corporate_id")
    private UUID corporateId;

    /**
     * Lender's VA for transaction posting.
     */
    @Column(name = "lender_va_id")
    private UUID lenderVaId;

    /**
     * Borrower's VA for transaction posting.
     */
    @Column(name = "borrower_va_id")
    private UUID borrowerVaId;

    /**
     * Interest configuration ID for rate lookups.
     */
    @Column(name = "interest_config_id")
    private UUID interestConfigId;

    // ========================================================================
    // SWEEP INTEGRATION FIELDS
    // ========================================================================

    /**
     * Whether this loan was auto-created by a deficit funding sweep.
     */
    @Column(name = "auto_created")
    private Boolean autoCreated = false;

    /**
     * Reference to the sweep execution that created this loan.
     * Null if manually created.
     */
    @Column(name = "sweep_execution_reference", length = 50)
    private String sweepExecutionReference;

    /**
     * Sweep rule ID that created this loan (for deficit funding).
     */
    @Column(name = "sweep_rule_id")
    private UUID sweepRuleId;

    /**
     * Sweep frequency from the rule (determines maturity period).
     */
    @Column(name = "sweep_frequency", length = 20)
    private String sweepFrequency;

    // ========================================================================
    // SETTLEMENT FIELDS (Option B Architecture)
    // ========================================================================

    /**
     * Value date - when interest starts accruing.
     * Defaults to disbursementDate but can be set differently.
     */
    @Column(name = "value_date")
    private LocalDate valueDate;

    /**
     * Settlement date - when funds actually moved between VAs.
     * Null until position is SETTLED.
     */
    @Column(name = "settlement_date")
    private LocalDate settlementDate;

    /**
     * Settlement batch reference - links to the EOD settlement run.
     */
    @Column(name = "settlement_batch_ref", length = 50)
    private String settlementBatchRef;

    /**
     * For manual loans: who approved the position.
     */
    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    /**
     * For manual loans: when approved.
     */
    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    /**
     * Rejection/cancellation reason.
     */
    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    // ========================================================================
    // CROSS-CURRENCY SUPPORT
    // ========================================================================

    /**
     * Original currency if different from settlement currency.
     * For cross-currency loans, this is the borrower's currency.
     */
    @Column(name = "original_currency", length = 3)
    private String originalCurrency;

    /**
     * Original amount in original currency (before FX conversion).
     */
    @Column(name = "original_amount", precision = 18, scale = 2)
    private BigDecimal originalAmount;

    /**
     * FX rate used for conversion (lender's currency → borrower's currency).
     */
    @Column(name = "fx_rate", precision = 18, scale = 8)
    private BigDecimal fxRate;

    /**
     * FX rate date (for audit/reconciliation).
     */
    @Column(name = "fx_rate_date")
    private LocalDate fxRateDate;

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    /**
     * Check if loan is active (accruing interest).
     * Includes both legacy ACTIVE and new SETTLED status.
     */
    public boolean isActive() {
        return status == LoanStatus.ACTIVE || status == LoanStatus.SETTLED;
    }

    /**
     * Check if loan was created by a deficit funding sweep.
     */
    public boolean isFromSweep() {
        return Boolean.TRUE.equals(autoCreated) || sweepExecutionReference != null;
    }

    /**
     * Check if loan is awaiting settlement.
     */
    public boolean isAwaitingSettlement() {
        return status == LoanStatus.COMMITTED || status == LoanStatus.APPROVED;
    }

    /**
     * Check if loan is in a terminal state.
     */
    public boolean isTerminal() {
        return status == LoanStatus.MATURED ||
               status == LoanStatus.PREPAID ||
               status == LoanStatus.DEFAULT ||
               status == LoanStatus.REJECTED ||
               status == LoanStatus.CANCELLED;
    }

    /**
     * Check if loan requires approval (manual loans only).
     */
    public boolean requiresApproval() {
        return !isFromSweep() && status == LoanStatus.PENDING;
    }

    /**
     * Check if repayment is allowed.
     * Only SETTLED loans can be repaid.
     */
    public boolean canRepay() {
        return status == LoanStatus.SETTLED || status == LoanStatus.ACTIVE;
    }

    /**
     * Check if position can be cancelled.
     * Only PENDING or APPROVED manual positions can be cancelled.
     */
    public boolean canCancel() {
        if (isFromSweep()) return false;
        return status == LoanStatus.PENDING || status == LoanStatus.APPROVED;
    }

    /**
     * Check if this is a cross-currency loan.
     */
    public boolean isCrossCurrency() {
        return originalCurrency != null && !originalCurrency.equals(currencyCode);
    }

    /**
     * Get the effective value date (defaults to disbursement date if not set).
     */
    public LocalDate getEffectiveValueDate() {
        return valueDate != null ? valueDate : disbursementDate;
    }

    /**
     * Get total amount owed (principal + accrued interest).
     */
    public BigDecimal getTotalOwed() {
        return outstandingAmount.add(accruedInterest != null ? accruedInterest : BigDecimal.ZERO);
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
