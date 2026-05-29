package com.bank.vam.entity.treasury;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * IhbDeposit - In-House Bank Deposit.
 * 
 * Represents funds deposited with the Treasury Center by subsidiaries.
 * The depositor earns interest on these funds.
 * 
 * Can be created:
 * - Manually via IHB UI
 * - Automatically via Cash Concentration sweeps
 */
@Data
@Entity
@Table(name = "ihb_deposits", indexes = {
    @Index(name = "idx_ihb_deposit_depositor", columnList = "depositor_legal_entity_id"),
    @Index(name = "idx_ihb_deposit_treasury", columnList = "treasury_legal_entity_id"),
    @Index(name = "idx_ihb_deposit_corporate", columnList = "corporate_id"),
    @Index(name = "idx_ihb_deposit_status", columnList = "status"),
    @Index(name = "idx_ihb_deposit_sweep", columnList = "sweep_execution_reference")
})
public class IhbDeposit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "deposit_reference", nullable = false, unique = true, length = 30)
    private String depositReference;

    /**
     * Legacy IhbEntity reference - nullable for unified architecture.
     * @deprecated Use depositorLegalEntityId instead
     */
    @Deprecated
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "depositor_entity_id", nullable = true)
    private IhbEntity depositorEntity;

    @Column(name = "depositor_entity_code", length = 20)
    private String depositorEntityCode;

    @Column(name = "principal_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal principalAmount;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode = "AED";

    @Column(name = "current_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal currentBalance;

    @Column(name = "interest_rate", nullable = false, precision = 8, scale = 4)
    private BigDecimal interestRate;

    @Column(name = "accrued_interest", precision = 18, scale = 2)
    private BigDecimal accruedInterest = BigDecimal.ZERO;

    @Column(name = "total_interest_earned", precision = 18, scale = 2)
    private BigDecimal totalInterestEarned = BigDecimal.ZERO;

    @Column(name = "deposit_date", nullable = false)
    private LocalDate depositDate;

    @Column(name = "maturity_date")
    private LocalDate maturityDate;

    @Column(name = "last_interest_date")
    private LocalDate lastInterestDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "deposit_type", length = 20)
    private DepositType depositType = DepositType.CALL;

    @Column(name = "notice_period_days")
    private Integer noticePeriodDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private DepositStatus status = DepositStatus.ACTIVE;

    // ========================================================================
    // LEGAL ENTITY REFERENCES (Phase 2 Unification)
    // ========================================================================

    /**
     * Depositor Legal Entity ID (unified reference).
     */
    @Column(name = "depositor_legal_entity_id")
    private UUID depositorLegalEntityId;

    /**
     * Treasury Legal Entity ID (where deposit is placed).
     */
    @Column(name = "treasury_legal_entity_id")
    private UUID treasuryLegalEntityId;

    /**
     * Corporate ID for scoping queries.
     */
    @Column(name = "corporate_id")
    private UUID corporateId;

    /**
     * Depositor's VA for transaction posting.
     */
    @Column(name = "depositor_va_id")
    private UUID depositorVaId;

    /**
     * Treasury's VA for transaction posting.
     */
    @Column(name = "treasury_va_id")
    private UUID treasuryVaId;

    /**
     * Interest configuration ID for rate lookups.
     */
    @Column(name = "interest_config_id")
    private UUID interestConfigId;

    // ========================================================================
    // SWEEP INTEGRATION FIELDS
    // ========================================================================

    /**
     * Reference to the sweep execution that created this deposit.
     * Null if manually created.
     */
    @Column(name = "sweep_execution_reference", length = 50)
    private String sweepExecutionReference;

    /**
     * Whether this deposit was auto-created by a sweep operation.
     */
    @Column(name = "auto_created")
    private Boolean autoCreated = false;

    /**
     * Sweep rule ID that created this deposit (for recurring sweeps).
     */
    @Column(name = "sweep_rule_id")
    private UUID sweepRuleId;

    /**
     * Sweep frequency from the rule (determines maturity period).
     * DAILY = overnight, WEEKLY = 7 days, MONTHLY = 30 days, etc.
     */
    @Column(name = "sweep_frequency", length = 20)
    private String sweepFrequency;

    // ========================================================================
    // SETTLEMENT FIELDS (Option B Architecture)
    // ========================================================================

    /**
     * Value date - when interest starts accruing.
     * Defaults to depositDate but can be set differently.
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
     * For manual deposits: who approved the position.
     */
    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    /**
     * For manual deposits: when approved.
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
     * For cross-currency deposits, this is the depositor's currency.
     */
    @Column(name = "original_currency", length = 3)
    private String originalCurrency;

    /**
     * Original amount in original currency (before FX conversion).
     */
    @Column(name = "original_amount", precision = 18, scale = 2)
    private BigDecimal originalAmount;

    /**
     * FX rate used for conversion (originalCurrency → currencyCode).
     */
    @Column(name = "fx_rate", precision = 18, scale = 8)
    private BigDecimal fxRate;

    /**
     * FX rate date (for audit/reconciliation).
     */
    @Column(name = "fx_rate_date")
    private LocalDate fxRateDate;

    // ========================================================================
    // TIMESTAMPS & AUDIT
    // ========================================================================

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "created_by", length = 100)
    private String createdBy;

    // ========================================================================
    // ENUMS
    // ========================================================================

    public enum DepositType {
        /** No fixed maturity, can withdraw anytime */
        CALL,
        /** Fixed term deposit with maturity date */
        FIXED,
        /** Requires notice period before withdrawal */
        NOTICE
    }

    /**
     * Deposit Status Lifecycle:
     *
     * SWEEP-CREATED (autoCreated=true):
     *   COMMITTED → SETTLED → MATURED (auto on next sweep cycle)
     *
     * MANUAL (autoCreated=false):
     *   PENDING → APPROVED → SETTLED → MATURED/WITHDRAWN
     *           → REJECTED
     *           → CANCELLED
     */
    public enum DepositStatus {
        // ===== Settlement Lifecycle =====
        /** Manual deposit awaiting Treasury approval */
        PENDING,
        /** Manual deposit approved, awaiting EOD settlement */
        APPROVED,
        /** Sweep deposit committed, awaiting EOD settlement */
        COMMITTED,
        /** Funds settled, position active and earning interest */
        SETTLED,

        // ===== Terminal States =====
        /** Reached maturity date (for sweep: next cycle; for manual: maturityDate) */
        MATURED,
        /** Fully withdrawn (manual deposits only) */
        WITHDRAWN,
        /** Broken before maturity - may incur penalty (manual only) */
        BROKEN,
        /** Rejected by Treasury (manual only) */
        REJECTED,
        /** Cancelled by requestor before approval (manual only) */
        CANCELLED,

        // ===== Legacy (deprecated) =====
        /** @deprecated Use SETTLED instead. Kept for backward compatibility. */
        @Deprecated
        ACTIVE
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    /**
     * Check if deposit is active (earning interest).
     * Includes both legacy ACTIVE and new SETTLED status.
     */
    public boolean isActive() {
        return status == DepositStatus.ACTIVE || status == DepositStatus.SETTLED;
    }

    /**
     * Check if deposit was created by a sweep operation.
     */
    public boolean isFromSweep() {
        return Boolean.TRUE.equals(autoCreated) || sweepExecutionReference != null;
    }

    /**
     * Check if deposit is awaiting settlement.
     */
    public boolean isAwaitingSettlement() {
        return status == DepositStatus.COMMITTED || status == DepositStatus.APPROVED;
    }

    /**
     * Check if deposit is in a terminal state.
     */
    public boolean isTerminal() {
        return status == DepositStatus.MATURED ||
               status == DepositStatus.WITHDRAWN ||
               status == DepositStatus.BROKEN ||
               status == DepositStatus.REJECTED ||
               status == DepositStatus.CANCELLED;
    }

    /**
     * Check if deposit requires approval (manual deposits only).
     */
    public boolean requiresApproval() {
        return !isFromSweep() && status == DepositStatus.PENDING;
    }

    /**
     * Check if withdrawal is allowed.
     * Only SETTLED manual deposits can be withdrawn.
     * Sweep deposits auto-mature at next cycle.
     */
    public boolean canWithdraw() {
        // Sweep deposits cannot be manually withdrawn
        if (isFromSweep()) return false;

        // Must be settled
        if (status != DepositStatus.SETTLED && status != DepositStatus.ACTIVE) return false;

        // Fixed deposits must reach maturity
        if (depositType == DepositType.FIXED && maturityDate != null) {
            return !LocalDate.now().isBefore(maturityDate);
        }
        return true;
    }

    /**
     * Check if position can be cancelled.
     * Only PENDING or APPROVED manual positions can be cancelled.
     */
    public boolean canCancel() {
        if (isFromSweep()) return false;
        return status == DepositStatus.PENDING || status == DepositStatus.APPROVED;
    }

    /**
     * Check if this is a cross-currency deposit.
     */
    public boolean isCrossCurrency() {
        return originalCurrency != null && !originalCurrency.equals(currencyCode);
    }

    public BigDecimal getTotalValue() {
        return currentBalance.add(accruedInterest != null ? accruedInterest : BigDecimal.ZERO);
    }

    /**
     * Get the effective value date (defaults to deposit date if not set).
     */
    public LocalDate getEffectiveValueDate() {
        return valueDate != null ? valueDate : depositDate;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}