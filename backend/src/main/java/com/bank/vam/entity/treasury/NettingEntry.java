package com.bank.vam.entity.treasury;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * NettingEntry Entity - Phase 4 Enhanced for Unified Netting Engine
 * 
 * PHASE 4 ENHANCEMENTS:
 * - Flow direction (PAYABLE vs RECEIVABLE) for bidirectional netting
 * - Source document links (payable_id, receivable_id, recharge_id, ihb_transaction_id)
 * - Extended source types for all obligation types
 * - Original currency tracking for multi-currency netting
 * - Due date for aging and prioritization
 * 
 * BACKWARD COMPATIBLE:
 * - All existing fields preserved
 * - New fields have sensible defaults
 * - Existing query methods still work
 * 
 * @see <a href="https://www.capgemini.com/wp-content/uploads/2020/12/Virtual-Acc-Management-in-Transaction-Banking.pdf">
 *      Capgemini VAM Reference - Intercompany Netting</a>
 */
@Entity
@Table(name = "netting_entries", indexes = {
    @Index(name = "idx_netting_entry_cycle", columnList = "cycle_id"),
    @Index(name = "idx_netting_entry_payer", columnList = "payer_entity_id"),
    @Index(name = "idx_netting_entry_payee", columnList = "payee_entity_id"),
    @Index(name = "idx_netting_entry_status", columnList = "status"),
    @Index(name = "idx_netting_entry_source", columnList = "source_type"),
    // Phase 4 indexes
    @Index(name = "idx_netting_entry_flow_direction", columnList = "flow_direction"),
    @Index(name = "idx_netting_entry_payable", columnList = "payable_id"),
    @Index(name = "idx_netting_entry_receivable", columnList = "receivable_id"),
    @Index(name = "idx_netting_entry_recharge", columnList = "intercompany_recharge_id"),
    @Index(name = "idx_netting_entry_ihb", columnList = "ihb_transaction_id"),
    @Index(name = "idx_netting_entry_source_entity", columnList = "source_entity_id"),
    @Index(name = "idx_netting_entry_due_date", columnList = "due_date")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, exclude = {"cycle"})
public class NettingEntry extends BaseEntity {

    // ========================================================================
    // CYCLE REFERENCE
    // ========================================================================

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cycle_id", nullable = false)
    private NettingCycle cycle;

    @Column(name = "entry_reference", nullable = false, length = 30)
    private String entryReference;

    // ========================================================================
    // PHASE 4: FLOW DIRECTION (NEW)
    // ========================================================================
    
    /**
     * Direction of money flow in this entry:
     * - PAYABLE: Payer entity owes money to payee entity
     * - RECEIVABLE: Payee entity is owed money by payer entity
     * 
     * This enables bidirectional netting where the same entity can have
     * both payable and receivable entries in the same cycle.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "flow_direction", length = 20)
    @Builder.Default
    private FlowDirection flowDirection = FlowDirection.PAYABLE;

    // ========================================================================
    // PAYER/PAYEE ENTITIES
    // ========================================================================

    @Column(name = "payer_entity_id", nullable = false)
    private UUID payerEntityId;

    @Column(name = "payer_entity_code", nullable = false, length = 20)
    private String payerEntityCode;

    @Column(name = "payer_entity_name", length = 100)
    private String payerEntityName;

    @Column(name = "payee_entity_id", nullable = false)
    private UUID payeeEntityId;

    @Column(name = "payee_entity_code", nullable = false, length = 20)
    private String payeeEntityCode;

    @Column(name = "payee_entity_name", length = 100)
    private String payeeEntityName;

    // ========================================================================
    // AMOUNTS
    // ========================================================================

    @Column(name = "gross_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal grossAmount;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "exchange_rate", precision = 18, scale = 8)
    @Builder.Default
    private BigDecimal exchangeRate = BigDecimal.ONE;

    @Column(name = "base_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal baseAmount;

    // ========================================================================
    // PHASE 4: ORIGINAL CURRENCY TRACKING (NEW)
    // ========================================================================
    
    /**
     * Original currency before FX conversion.
     * Used for multi-currency netting where entries may be converted to base currency.
     */
    @Column(name = "original_currency", length = 3)
    private String originalCurrency;
    
    /**
     * Original amount before FX conversion.
     */
    @Column(name = "original_amount", precision = 18, scale = 2)
    private BigDecimal originalAmount;

    // ========================================================================
    // SOURCE TRACKING (Enhanced in Phase 4)
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", length = 30)
    @Builder.Default
    private SourceType sourceType = SourceType.MANUAL;

    @Column(name = "source_reference", length = 50)
    private String sourceReference;

    // ========================================================================
    // PHASE 4: SOURCE DOCUMENT LINKS (NEW)
    // ========================================================================
    
    /**
     * Link to source Payable if this entry originated from a payable.
     */
    @Column(name = "payable_id")
    private UUID payableId;
    
    /**
     * Link to source Receivable if this entry originated from a receivable.
     */
    @Column(name = "receivable_id")
    private UUID receivableId;
    
    /**
     * Link to IntercompanyTransaction (recharge) if from POBO/COBO.
     */
    @Column(name = "intercompany_recharge_id")
    private UUID intercompanyRechargeId;
    
    /**
     * Link to IHB transaction (loan/deposit) if from IHB.
     */
    @Column(name = "ihb_transaction_id")
    private UUID ihbTransactionId;
    
    /**
     * The entity that is the source of this obligation.
     * For payables: owning entity (debtor)
     * For receivables: owning entity (creditor)
     */
    @Column(name = "source_entity_id")
    private UUID sourceEntityId;
    
    @Column(name = "source_entity_code", length = 20)
    private String sourceEntityCode;

    // ========================================================================
    // PHASE 4: DUE DATE FOR PRIORITIZATION (NEW)
    // ========================================================================
    
    /**
     * Due date of the underlying obligation.
     * Used for:
     * - Aging analysis within netting cycles
     * - Prioritization of settlements
     * - Overdue flagging
     */
    @Column(name = "due_date")
    private LocalDate dueDate;

    // ========================================================================
    // STATUS
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    @Builder.Default
    private EntryStatus status = EntryStatus.PENDING;

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
    // ENUMS - EXTENDED IN PHASE 4
    // ========================================================================

    /**
     * Flow direction for bidirectional netting.
     */
    public enum FlowDirection {
        /** Payer owes money - creates payable position */
        PAYABLE,
        /** Payee is owed money - creates receivable position */
        RECEIVABLE
    }

    /**
     * Source type - Extended in Phase 4 for unified netting.
     */
    public enum SourceType {
        // Original types
        INVOICE,
        LOAN,
        PAYMENT,
        MANUAL,
        
        // Phase 4: Extended types for unified netting
        /** Intercompany payable from owning entity to group entity */
        INTERCOMPANY_PAYABLE,
        /** Intercompany receivable from group entity */
        INTERCOMPANY_RECEIVABLE,
        /** Treasury recharge created after POBO payment */
        POBO_RECHARGE,
        /** Collection recharge created after COBO */
        COBO_COLLECTION,
        /** IHB loan obligation (subsidiary owes treasury) */
        IHB_LOAN,
        /** IHB deposit (treasury owes subsidiary) */
        IHB_DEPOSIT,
        /** External vendor payable (non-IC) */
        EXTERNAL_PAYABLE,
        /** External customer receivable (non-IC) */
        EXTERNAL_RECEIVABLE
    }

    public enum EntryStatus {
        PENDING,    // Added to cycle, awaiting inclusion
        INCLUDED,   // Included in netting calculation
        EXCLUDED,   // Explicitly excluded from netting
        SETTLED     // Netting cycle settled
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================
    
    /**
     * Check if this entry is from a payable source.
     */
    public boolean isPayableSource() {
        return payableId != null || sourceType == SourceType.INTERCOMPANY_PAYABLE 
            || sourceType == SourceType.EXTERNAL_PAYABLE
            || sourceType == SourceType.POBO_RECHARGE;
    }
    
    /**
     * Check if this entry is from a receivable source.
     */
    public boolean isReceivableSource() {
        return receivableId != null || sourceType == SourceType.INTERCOMPANY_RECEIVABLE
            || sourceType == SourceType.EXTERNAL_RECEIVABLE
            || sourceType == SourceType.COBO_COLLECTION;
    }
    
    /**
     * Check if this is an intercompany entry.
     */
    public boolean isIntercompany() {
        return sourceType == SourceType.INTERCOMPANY_PAYABLE 
            || sourceType == SourceType.INTERCOMPANY_RECEIVABLE
            || sourceType == SourceType.POBO_RECHARGE
            || sourceType == SourceType.COBO_COLLECTION;
    }
    
    /**
     * Check if this entry is overdue.
     */
    public boolean isOverdue() {
        return dueDate != null && LocalDate.now().isAfter(dueDate);
    }
    
    /**
     * Get days until due (negative if overdue).
     */
    public Integer getDaysUntilDue() {
        if (dueDate == null) return null;
        return (int) java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), dueDate);
    }
    
    /**
     * Calculate base amount from gross amount and exchange rate.
     */
    public void calculateBaseAmount() {
        if (grossAmount != null && exchangeRate != null) {
            this.baseAmount = grossAmount.multiply(exchangeRate);
        }
    }
    
    /**
     * Mark as included in netting calculation.
     */
    public void include() {
        this.status = EntryStatus.INCLUDED;
        this.updatedAt = LocalDateTime.now();
    }
    
    /**
     * Mark as excluded from netting.
     */
    public void exclude() {
        this.status = EntryStatus.EXCLUDED;
        this.updatedAt = LocalDateTime.now();
    }
    
    /**
     * Mark as settled.
     */
    public void markSettled() {
        this.status = EntryStatus.SETTLED;
        this.updatedAt = LocalDateTime.now();
    }

    // ========================================================================
    // FACTORY METHODS - PHASE 4 NEW
    // ========================================================================
    
    /**
     * Create entry from an intercompany payable.
     * 
     * @param cycle The netting cycle
     * @param payableId Source payable ID
     * @param payerEntityId Entity that owes (owns the payable)
     * @param payerEntityCode Code of payer entity
     * @param payerEntityName Name of payer entity
     * @param payeeEntityId Entity that is owed (counterparty on payable)
     * @param payeeEntityCode Code of payee entity
     * @param payeeEntityName Name of payee entity
     * @param amount Payment amount
     * @param currency Currency code
     * @param dueDate Due date
     * @param reference External reference
     * @return NettingEntry
     */
    public static NettingEntry fromIntercompanyPayable(
            NettingCycle cycle,
            UUID payableId,
            UUID payerEntityId, String payerEntityCode, String payerEntityName,
            UUID payeeEntityId, String payeeEntityCode, String payeeEntityName,
            BigDecimal amount, String currency, LocalDate dueDate, String reference) {
        
        return NettingEntry.builder()
            .cycle(cycle)
            .flowDirection(FlowDirection.PAYABLE)
            .payableId(payableId)
            .sourceType(SourceType.INTERCOMPANY_PAYABLE)
            .sourceReference(reference)
            .sourceEntityId(payerEntityId)
            .sourceEntityCode(payerEntityCode)
            .payerEntityId(payerEntityId)
            .payerEntityCode(payerEntityCode)
            .payerEntityName(payerEntityName)
            .payeeEntityId(payeeEntityId)
            .payeeEntityCode(payeeEntityCode)
            .payeeEntityName(payeeEntityName)
            .grossAmount(amount)
            .currencyCode(currency)
            .originalCurrency(currency)
            .originalAmount(amount)
            .baseAmount(amount) // Will be recalculated if FX needed
            .exchangeRate(BigDecimal.ONE)
            .dueDate(dueDate)
            .status(EntryStatus.PENDING)
            .build();
    }
    
    /**
     * Create entry from an intercompany receivable.
     * 
     * @param cycle The netting cycle
     * @param receivableId Source receivable ID
     * @param payeeEntityId Entity that is owed (owns the receivable)
     * @param payeeEntityCode Code of payee entity
     * @param payeeEntityName Name of payee entity
     * @param payerEntityId Entity that owes (counterparty on receivable)
     * @param payerEntityCode Code of payer entity
     * @param payerEntityName Name of payer entity
     * @param amount Receivable amount
     * @param currency Currency code
     * @param dueDate Due date
     * @param reference External reference
     * @return NettingEntry
     */
    public static NettingEntry fromIntercompanyReceivable(
            NettingCycle cycle,
            UUID receivableId,
            UUID payeeEntityId, String payeeEntityCode, String payeeEntityName,
            UUID payerEntityId, String payerEntityCode, String payerEntityName,
            BigDecimal amount, String currency, LocalDate dueDate, String reference) {
        
        return NettingEntry.builder()
            .cycle(cycle)
            .flowDirection(FlowDirection.RECEIVABLE)
            .receivableId(receivableId)
            .sourceType(SourceType.INTERCOMPANY_RECEIVABLE)
            .sourceReference(reference)
            .sourceEntityId(payeeEntityId)
            .sourceEntityCode(payeeEntityCode)
            .payerEntityId(payerEntityId)
            .payerEntityCode(payerEntityCode)
            .payerEntityName(payerEntityName)
            .payeeEntityId(payeeEntityId)
            .payeeEntityCode(payeeEntityCode)
            .payeeEntityName(payeeEntityName)
            .grossAmount(amount)
            .currencyCode(currency)
            .originalCurrency(currency)
            .originalAmount(amount)
            .baseAmount(amount) // Will be recalculated if FX needed
            .exchangeRate(BigDecimal.ONE)
            .dueDate(dueDate)
            .status(EntryStatus.PENDING)
            .build();
    }
    
    /**
     * Create entry from a POBO recharge.
     */
    public static NettingEntry fromPoboRecharge(
            NettingCycle cycle,
            UUID rechargeId,
            UUID subsidiaryEntityId, String subsidiaryCode, String subsidiaryName,
            UUID treasuryEntityId, String treasuryCode, String treasuryName,
            BigDecimal amount, String currency, String reference) {
        
        return NettingEntry.builder()
            .cycle(cycle)
            .flowDirection(FlowDirection.PAYABLE) // Subsidiary owes treasury
            .intercompanyRechargeId(rechargeId)
            .sourceType(SourceType.POBO_RECHARGE)
            .sourceReference(reference)
            .sourceEntityId(subsidiaryEntityId)
            .sourceEntityCode(subsidiaryCode)
            .payerEntityId(subsidiaryEntityId)
            .payerEntityCode(subsidiaryCode)
            .payerEntityName(subsidiaryName)
            .payeeEntityId(treasuryEntityId)
            .payeeEntityCode(treasuryCode)
            .payeeEntityName(treasuryName)
            .grossAmount(amount)
            .currencyCode(currency)
            .originalCurrency(currency)
            .originalAmount(amount)
            .baseAmount(amount)
            .exchangeRate(BigDecimal.ONE)
            .status(EntryStatus.PENDING)
            .build();
    }
    
    /**
     * Create entry from a COBO collection recharge.
     */
    public static NettingEntry fromCoboCollection(
            NettingCycle cycle,
            UUID rechargeId,
            UUID treasuryEntityId, String treasuryCode, String treasuryName,
            UUID subsidiaryEntityId, String subsidiaryCode, String subsidiaryName,
            BigDecimal amount, String currency, String reference) {
        
        return NettingEntry.builder()
            .cycle(cycle)
            .flowDirection(FlowDirection.RECEIVABLE) // Treasury owes subsidiary (collected on their behalf)
            .intercompanyRechargeId(rechargeId)
            .sourceType(SourceType.COBO_COLLECTION)
            .sourceReference(reference)
            .sourceEntityId(treasuryEntityId)
            .sourceEntityCode(treasuryCode)
            .payerEntityId(treasuryEntityId)
            .payerEntityCode(treasuryCode)
            .payerEntityName(treasuryName)
            .payeeEntityId(subsidiaryEntityId)
            .payeeEntityCode(subsidiaryCode)
            .payeeEntityName(subsidiaryName)
            .grossAmount(amount)
            .currencyCode(currency)
            .originalCurrency(currency)
            .originalAmount(amount)
            .baseAmount(amount)
            .exchangeRate(BigDecimal.ONE)
            .status(EntryStatus.PENDING)
            .build();
    }
    
    /**
     * Create entry from an IHB loan.
     */
    public static NettingEntry fromIhbLoan(
            NettingCycle cycle,
            UUID ihbTransactionId,
            UUID borrowerEntityId, String borrowerCode, String borrowerName,
            UUID lenderEntityId, String lenderCode, String lenderName,
            BigDecimal amount, String currency, LocalDate dueDate, String reference) {
        
        return NettingEntry.builder()
            .cycle(cycle)
            .flowDirection(FlowDirection.PAYABLE) // Borrower owes lender
            .ihbTransactionId(ihbTransactionId)
            .sourceType(SourceType.IHB_LOAN)
            .sourceReference(reference)
            .sourceEntityId(borrowerEntityId)
            .sourceEntityCode(borrowerCode)
            .payerEntityId(borrowerEntityId)
            .payerEntityCode(borrowerCode)
            .payerEntityName(borrowerName)
            .payeeEntityId(lenderEntityId)
            .payeeEntityCode(lenderCode)
            .payeeEntityName(lenderName)
            .grossAmount(amount)
            .currencyCode(currency)
            .originalCurrency(currency)
            .originalAmount(amount)
            .baseAmount(amount)
            .exchangeRate(BigDecimal.ONE)
            .dueDate(dueDate)
            .status(EntryStatus.PENDING)
            .build();
    }
    
    /**
     * Create manual entry (legacy support).
     */
    public static NettingEntry createManual(
            NettingCycle cycle,
            UUID payerEntityId, String payerEntityCode, String payerEntityName,
            UUID payeeEntityId, String payeeEntityCode, String payeeEntityName,
            BigDecimal amount, String currency, String reference) {
        
        return NettingEntry.builder()
            .cycle(cycle)
            .flowDirection(FlowDirection.PAYABLE)
            .sourceType(SourceType.MANUAL)
            .sourceReference(reference)
            .payerEntityId(payerEntityId)
            .payerEntityCode(payerEntityCode)
            .payerEntityName(payerEntityName)
            .payeeEntityId(payeeEntityId)
            .payeeEntityCode(payeeEntityCode)
            .payeeEntityName(payeeEntityName)
            .grossAmount(amount)
            .currencyCode(currency)
            .baseAmount(amount)
            .exchangeRate(BigDecimal.ONE)
            .status(EntryStatus.PENDING)
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