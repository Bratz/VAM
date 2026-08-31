package com.bank.vam.entity.treasury;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * SweepExecution - Records each sweep execution.
 * 
 * Enhanced with IHB integration to track when sweeps create
 * intercompany positions (loans/deposits) between IHB-enabled entities.
 */
@Data
@Entity
@Table(name = "sweep_executions", indexes = {
    @Index(name = "idx_sweep_exec_rule", columnList = "rule_id"),
    @Index(name = "idx_sweep_exec_status", columnList = "status"),
    @Index(name = "idx_sweep_exec_time", columnList = "execution_time"),
    @Index(name = "idx_sweep_exec_ihb_deposit", columnList = "ihb_deposit_id")
})
public class SweepExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "execution_reference", nullable = false, unique = true, length = 30)
    private String executionReference;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_id", nullable = false)
    private SweepRule rule;

    @Column(name = "rule_name", length = 100)
    private String ruleName;

    @Column(name = "source_account_id", nullable = false)
    private UUID sourceAccountId;

    @Column(name = "source_account_number", length = 34)
    private String sourceAccountNumber;

    @Column(name = "source_entity_code", length = 20)
    private String sourceEntityCode;

    @Column(name = "target_account_id", nullable = false)
    private UUID targetAccountId;

    @Column(name = "target_account_number", length = 34)
    private String targetAccountNumber;

    @Column(name = "target_entity_code", length = 20)
    private String targetEntityCode;

    @Column(name = "sweep_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal sweepAmount;

    @Column(name = "currency_code", length = 3)
    private String currencyCode = "AED";

    @Column(name = "balance_before", precision = 18, scale = 2)
    private BigDecimal balanceBefore;

    @Column(name = "balance_after", precision = 18, scale = 2)
    private BigDecimal balanceAfter;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ExecutionStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "execution_time")
    private LocalDateTime executionTime = LocalDateTime.now();

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /**
     * When the EOD settlement was executed (funds moved).
     */
    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    /**
     * Settlement batch reference (links to IhbSettlement run).
     */
    @Column(name = "settlement_batch_ref", length = 50)
    private String settlementBatchRef;

    @Column(name = "bancs_transaction_ref", length = 50)
    private String bancsTransactionRef;

    @Column(name = "bancs_sync_status", length = 20)
    private String bancsSyncStatus = "PENDING";

    // ========================================================================
    // IHB INTEGRATION FIELDS
    // ========================================================================

    /**
     * Whether this sweep created IHB positions (loan/deposit).
     * True when both source and target entities are IHB-enabled.
     */
    @Column(name = "ihb_enabled")
    private Boolean ihbEnabled = false;

    /**
     * IHB Deposit ID created from this sweep.
     * When funds are swept TO Treasury, source entity gets a deposit.
     */
    @Column(name = "ihb_deposit_id")
    private UUID ihbDepositId;

    /**
     * IHB Loan ID if a loan was created.
     * Used for reverse sweeps or deficit funding.
     */
    @Column(name = "ihb_loan_id")
    private UUID ihbLoanId;

    // ========================================================================
    // TIMESTAMPS
    // ========================================================================

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    // ========================================================================
    // ENUM
    // ========================================================================

    /**
     * Execution Status for Option B Architecture:
     *
     * COMMITTED: Position created, funds committed (awaiting EOD settlement)
     * SETTLED: EOD settlement complete, funds moved
     * SUCCESS: Legacy - same as SETTLED (for backward compatibility)
     * FAILED: Execution failed (insufficient balance, entity not found, etc.)
     * SKIPPED: No funds to sweep or below threshold
     * PENDING: In progress (transient state)
     * PARTIAL: Partial execution (for batch processing)
     */
    public enum ExecutionStatus {
        /** Position committed, awaiting EOD settlement */
        COMMITTED,
        /** EOD settlement complete, funds moved */
        SETTLED,
        /** Legacy status - same as SETTLED */
        SUCCESS,
        FAILED,
        PARTIAL,
        PENDING,
        SKIPPED
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    public boolean isSuccess() {
        return status == ExecutionStatus.SUCCESS || status == ExecutionStatus.SETTLED;
    }

    public boolean isCommitted() {
        return status == ExecutionStatus.COMMITTED;
    }

    public boolean isSettled() {
        return status == ExecutionStatus.SETTLED || status == ExecutionStatus.SUCCESS;
    }

    public boolean isAwaitingSettlement() {
        return status == ExecutionStatus.COMMITTED;
    }

    public boolean hasIhbPosition() {
        return Boolean.TRUE.equals(ihbEnabled) && (ihbDepositId != null || ihbLoanId != null);
    }

    public boolean wasSkipped() {
        return status == ExecutionStatus.SKIPPED;
    }

    public BigDecimal getActualSwept() {
        if (balanceBefore != null && balanceAfter != null) {
            return balanceBefore.subtract(balanceAfter);
        }
        return sweepAmount;
    }

    /**
     * Get committed amount (for COMMITTED status, amount awaiting settlement).
     */
    public BigDecimal getCommittedAmount() {
        return isAwaitingSettlement() ? sweepAmount : BigDecimal.ZERO;
    }
}