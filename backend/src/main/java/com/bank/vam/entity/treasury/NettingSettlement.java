package com.bank.vam.entity.treasury;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * NettingSettlement Entity - Phase 4 Enhanced for Bidirectional Netting
 * 
 * PHASE 4 ENHANCEMENTS:
 * - Gross payables and gross receivables tracking
 * - Entry counts for reporting
 * - Settlement VA link for actual fund movement
 * - Settlement transaction reference
 * 
 * Represents the net position of a single entity within a netting cycle.
 * After calculation, each participating entity has one settlement record
 * showing their net position (pay or receive).
 * 
 * @see <a href="https://www.capgemini.com/wp-content/uploads/2020/12/Virtual-Acc-Management-in-Transaction-Banking.pdf">
 *      Capgemini VAM Reference - Netting Settlement</a>
 */
@Entity
@Table(name = "netting_settlements", indexes = {
    @Index(name = "idx_settlement_cycle", columnList = "cycle_id"),
    @Index(name = "idx_settlement_entity", columnList = "entity_id"),
    @Index(name = "idx_settlement_status", columnList = "settlement_status"),
    @Index(name = "idx_settlement_direction", columnList = "settlement_direction"),
    @Index(name = "idx_settlement_va", columnList = "settlement_va_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, exclude = {"cycle"})
public class NettingSettlement extends BaseEntity {

    // ========================================================================
    // CYCLE REFERENCE
    // ========================================================================

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cycle_id", nullable = false)
    private NettingCycle cycle;

    // ========================================================================
    // ENTITY IDENTIFICATION
    // ========================================================================

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "entity_code", nullable = false, length = 20)
    private String entityCode;

    @Column(name = "entity_name", length = 100)
    private String entityName;

    // ========================================================================
    // POSITION AMOUNTS (Legacy - Preserved)
    // ========================================================================

    @Column(name = "total_payable", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal totalPayable = BigDecimal.ZERO;

    @Column(name = "total_receivable", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal totalReceivable = BigDecimal.ZERO;

    @Column(name = "net_position", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal netPosition = BigDecimal.ZERO;

    // ========================================================================
    // PHASE 4: GROSS AMOUNTS (NEW)
    // ========================================================================
    
    /**
     * Sum of all payable entries (what this entity owes to others).
     * Before netting: total outgoing obligations.
     */
    @Column(name = "gross_payables", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal grossPayables = BigDecimal.ZERO;
    
    /**
     * Sum of all receivable entries (what this entity is owed by others).
     * Before netting: total incoming obligations.
     */
    @Column(name = "gross_receivables", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal grossReceivables = BigDecimal.ZERO;

    // ========================================================================
    // PHASE 4: ENTRY COUNTS (NEW)
    // ========================================================================
    
    /**
     * Number of payable entries for this entity.
     */
    @Column(name = "payable_entry_count")
    @Builder.Default
    private Integer payableEntryCount = 0;
    
    /**
     * Number of receivable entries for this entity.
     */
    @Column(name = "receivable_entry_count")
    @Builder.Default
    private Integer receivableEntryCount = 0;

    // ========================================================================
    // SETTLEMENT DIRECTION
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_direction", length = 10)
    private SettlementDirection settlementDirection;

    // ========================================================================
    // SETTLEMENT EXECUTION
    // ========================================================================

    @Column(name = "settlement_account", length = 34)
    private String settlementAccount;
    
    /**
     * Phase 4: Link to the virtual account used for settlement.
     */
    @Column(name = "settlement_va_id")
    private UUID settlementVaId;
    
    /**
     * Phase 4: Transaction ID from the settlement execution.
     */
    @Column(name = "settlement_transaction_id")
    private UUID settlementTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_status", length = 20)
    @Builder.Default
    private SettlementStatus settlementStatus = SettlementStatus.PENDING;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    @Column(name = "settlement_reference", length = 50)
    private String settlementReference;

    // ========================================================================
    // TIMESTAMPS
    // ========================================================================

    @Column(name = "created_at")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    // ========================================================================
    // ENUMS
    // ========================================================================

    public enum SettlementDirection {
        /** Entity needs to PAY the net amount */
        PAY,
        /** Entity will RECEIVE the net amount */
        RECEIVE,
        /** Net position is zero */
        NEUTRAL
    }

    public enum SettlementStatus {
        PENDING,    // Awaiting settlement execution
        SETTLED,    // Settlement complete
        FAILED,     // Settlement failed
        PARTIAL     // Partially settled (rare edge case)
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================
    
    /**
     * Calculate net position from gross amounts.
     * Positive = entity receives, Negative = entity pays.
     */
    public void calculateNetPosition() {
        // Net = what I'm owed - what I owe
        this.netPosition = this.grossReceivables.subtract(this.grossPayables);
        
        // Also update legacy fields for backward compatibility
        this.totalPayable = this.grossPayables;
        this.totalReceivable = this.grossReceivables;
        
        // Determine direction
        int comparison = this.netPosition.compareTo(BigDecimal.ZERO);
        if (comparison > 0) {
            this.settlementDirection = SettlementDirection.RECEIVE;
        } else if (comparison < 0) {
            this.settlementDirection = SettlementDirection.PAY;
        } else {
            this.settlementDirection = SettlementDirection.NEUTRAL;
        }
    }
    
    /**
     * Add a payable amount to this settlement.
     */
    public void addPayable(BigDecimal amount) {
        this.grossPayables = this.grossPayables.add(amount);
        this.payableEntryCount++;
    }
    
    /**
     * Add a receivable amount to this settlement.
     */
    public void addReceivable(BigDecimal amount) {
        this.grossReceivables = this.grossReceivables.add(amount);
        this.receivableEntryCount++;
    }
    
    /**
     * Get the absolute amount that needs to move.
     */
    public BigDecimal getSettlementAmount() {
        return netPosition.abs();
    }
    
    /**
     * Check if this entity needs to pay.
     */
    public boolean needsToPay() {
        return settlementDirection == SettlementDirection.PAY;
    }
    
    /**
     * Check if this entity will receive.
     */
    public boolean willReceive() {
        return settlementDirection == SettlementDirection.RECEIVE;
    }
    
    /**
     * Check if position is neutral (no settlement needed).
     */
    public boolean isNeutral() {
        return settlementDirection == SettlementDirection.NEUTRAL;
    }
    
    /**
     * Get total number of entries.
     */
    public int getTotalEntryCount() {
        return (payableEntryCount != null ? payableEntryCount : 0) 
             + (receivableEntryCount != null ? receivableEntryCount : 0);
    }
    
    /**
     * Get gross total (payables + receivables) before netting.
     */
    public BigDecimal getGrossTotal() {
        return grossPayables.add(grossReceivables);
    }
    
    /**
     * Calculate savings from netting (gross - net).
     */
    public BigDecimal getSavings() {
        return getGrossTotal().subtract(getSettlementAmount());
    }
    
    /**
     * Mark as settled.
     */
    public void markSettled(String reference, UUID transactionId) {
        this.settlementStatus = SettlementStatus.SETTLED;
        this.settledAt = LocalDateTime.now();
        this.settlementReference = reference;
        this.settlementTransactionId = transactionId;
        this.updatedAt = LocalDateTime.now();
    }
    
    /**
     * Mark as failed.
     */
    public void markFailed() {
        this.settlementStatus = SettlementStatus.FAILED;
        this.updatedAt = LocalDateTime.now();
    }
    
    /**
     * Get display-friendly position description.
     */
    public String getPositionDescription() {
        if (settlementDirection == null) return "Pending calculation";
        return switch (settlementDirection) {
            case PAY -> String.format("Pays %s", netPosition.abs());
            case RECEIVE -> String.format("Receives %s", netPosition);
            case NEUTRAL -> "Neutral position";
        };
    }

    // ========================================================================
    // FACTORY METHOD
    // ========================================================================
    
    /**
     * Create a new settlement for an entity in a cycle.
     */
    public static NettingSettlement forEntity(NettingCycle cycle, UUID entityId, 
                                               String entityCode, String entityName) {
        return NettingSettlement.builder()
            .cycle(cycle)
            .entityId(entityId)
            .entityCode(entityCode)
            .entityName(entityName)
            .grossPayables(BigDecimal.ZERO)
            .grossReceivables(BigDecimal.ZERO)
            .totalPayable(BigDecimal.ZERO)
            .totalReceivable(BigDecimal.ZERO)
            .netPosition(BigDecimal.ZERO)
            .payableEntryCount(0)
            .receivableEntryCount(0)
            .settlementStatus(SettlementStatus.PENDING)
            .build();
    }
    
    // ========================================================================
    // LIFECYCLE CALLBACKS
    // ========================================================================
    
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}