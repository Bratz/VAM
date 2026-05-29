package com.bank.vam.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Transaction Entity - Unified model for all VA/Wallet movements.
 * 
 * PURE VIRTUAL ARCHITECTURE v5.0:
 * - physicalAccountId is now OPTIONAL (nullable)
 * - Added transactionCategory (INTERNAL vs EXTERNAL)
 * - Added feeBreakdown for detailed fee tracking
 * 
 * Key Changes:
 * - INTERNAL transactions: Pure VA bookkeeping, physicalAccountId can be null
 * - EXTERNAL transactions: Real money movement, physicalAccountId recommended
 * - Reconciliation uses VA hierarchy traversal, not transaction-level physical links
 * 
 * Enhanced with:
 * - VIBAN tracking for ROBO (Receive On Behalf Of) routing
 * - Auto-reconciliation fields
 * - Hierarchy context (source/target nodes)
 * - POBO/ROBO flags for on-behalf-of transactions
 * - Fee breakdown JSON for intercompany transfers
 * 
 * Domain Model:
 * - VirtualAccount (1) -> Transaction (N)
 * - Transaction links to counterpartyVaId for transfers
 * - Transaction links to vibanId for ROBO routing
 * - Transaction links to hierarchy nodes for reporting
 */
@Entity
@Table(name = "va_movements", indexes = {
    @Index(name = "idx_txn_corporate_id", columnList = "corporate_id"),
    @Index(name = "idx_txn_legal_entity_id", columnList = "legal_entity_id"),
    @Index(name = "idx_txn_va_id", columnList = "va_id"),
    @Index(name = "idx_txn_reference", columnList = "reference_number"),
    @Index(name = "idx_txn_date", columnList = "transaction_date"),
    @Index(name = "idx_txn_movement_type", columnList = "movement_type"),
    @Index(name = "idx_txn_status", columnList = "status"),
    @Index(name = "idx_txn_correlation", columnList = "correlation_id"),
    @Index(name = "idx_txn_viban", columnList = "viban"),
    @Index(name = "idx_txn_viban_id", columnList = "viban_id"),
    @Index(name = "idx_txn_reconciled", columnList = "reconciled_reference_type, reconciled_reference_id"),
    @Index(name = "idx_txn_program_id", columnList = "program_id"),
    @Index(name = "idx_txn_category", columnList = "transaction_category")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction extends BaseEntity {

    // ========================================================================
    // CORE TRANSACTION FIELDS
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false)
    private MovementType movementType;

    @Column(name = "corporate_id", nullable = false)
    private UUID corporateId;

    /**
     * Legal Entity ID - the owning entity that initiated/owns this transaction.
     * Links to LegalEntity (subsidiary/branch) for reporting and filtering.
     */
    @Column(name = "legal_entity_id")
    private UUID legalEntityId;

    @Column(name = "va_id", nullable = false)
    private UUID vaId;

    /**
     * Physical account ID - NOW OPTIONAL (v5.0).
     * 
     * For INTERNAL transactions (VA-to-VA, fees, sweeps): Can be NULL
     * For EXTERNAL transactions (SWIFT, RTGS, bank transfers): Should be populated
     * 
     * Reconciliation to physical accounts uses VA hierarchy traversal
     * (VA → parent → ... → SHADOW → Physical Account)
     */
    @Column(name = "physical_account_id")  // Removed nullable = false
    private UUID physicalAccountId;

    /**
     * Transaction Category - NEW (v5.0).
     * 
     * INTERNAL: Pure VA bookkeeping (fees, transfers, sweeps) - no real money movement
     * EXTERNAL: Real money in/out (SWIFT, RTGS, bank transfer) - involves CBS/BANCS
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_category", length = 20)
    @Builder.Default
    private TransactionCategory transactionCategory = TransactionCategory.INTERNAL;

    @Column(name = "program_id")
    private UUID programId;

    @Column(name = "amount", precision = 18, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "balance_before", precision = 18, scale = 2)
    private BigDecimal balanceBefore;

    @Column(name = "balance_after", precision = 18, scale = 2)
    private BigDecimal balanceAfter;

    @Column(name = "transaction_date", nullable = false)
    private LocalDateTime transactionDate;

    @Column(name = "value_date")
    private LocalDate valueDate;

    @Column(name = "reference_number", unique = true)
    private String referenceNumber;

    @Column(name = "description")
    private String description;

    @Column(name = "channel")
    private String channel;

    @Column(name = "remitter_name")
    private String remitterName;

    @Column(name = "remitter_account")
    private String remitterAccount;

    @Column(name = "beneficiary_name")
    private String beneficiaryName;

    @Column(name = "beneficiary_account")
    private String beneficiaryAccount;

    @Column(name = "counterparty_va_id")
    private UUID counterpartyVaId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    @Builder.Default
    private TransactionStatus status = TransactionStatus.COMPLETED;

    @Column(name = "bancs_reference")
    private String bancsReference;

    @Column(name = "external_reference")
    private String externalReference;

    // ========================================================================
    // VIBAN TRACKING (for ROBO routing)
    // ========================================================================

    @Column(name = "viban_id")
    private UUID vibanId;

    @Column(name = "viban", length = 34)
    private String viban;

    @Column(name = "routed_via_viban")
    @Builder.Default
    private Boolean routedViaViban = false;

    // ========================================================================
    // AUTO-RECONCILIATION FIELDS
    // ========================================================================

    @Column(name = "auto_reconciled")
    @Builder.Default
    private Boolean autoReconciled = false;

    @Column(name = "reconciled_reference_type", length = 50)
    private String reconciledReferenceType;

    @Column(name = "reconciled_reference_id", length = 100)
    private String reconciledReferenceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reconciliation_match_type", length = 20)
    private ReconciliationMatchType reconciliationMatchType;

    @Column(name = "match_confidence")
    private Integer matchConfidence;

    // ========================================================================
    // HIERARCHY CONTEXT
    // ========================================================================

    @Column(name = "source_hierarchy_node_id")
    private UUID sourceHierarchyNodeId;

    @Column(name = "target_hierarchy_node_id")
    private UUID targetHierarchyNodeId;

    @Column(name = "hierarchy_path", length = 500)
    private String hierarchyPath;

    // ========================================================================
    // POBO/ROBO FLAGS
    // ========================================================================

    @Column(name = "is_pobo")
    @Builder.Default
    private Boolean isPobo = false;

    @Column(name = "is_robo")
    @Builder.Default
    private Boolean isRobo = false;

    @Column(name = "behalf_of_entity", length = 100)
    private String behalfOfEntity;

    @Column(name = "behalf_of_va_id")
    private UUID behalfOfVaId;

    // ========================================================================
    // FEE TRACKING (ENHANCED v5.0)
    // ========================================================================

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "fee_amount", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal feeAmount = BigDecimal.ZERO;

    /**
     * Fee Breakdown - NEW (v5.0).
     * 
     * JSON array of all fees applied to this transaction.
     * Format: [{"chargeCode": "...", "chargeName": "...", "amount": 0.00, 
     *           "currency": "AED", "waived": false, "waiverReason": null}]
     * 
     * Used for intercompany transfers with multiple fee types.
     */
    @Column(name = "fee_breakdown", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String feeBreakdown;

    @Column(name = "net_amount", precision = 18, scale = 2)
    private BigDecimal netAmount;

    @Column(name = "source_type", length = 30)
    private String sourceType;

    @Column(name = "source_reference")
    private String sourceReference;

    @Column(name = "destination_type", length = 30)
    private String destinationType;

    @Column(name = "destination_reference")
    private String destinationReference;

    // ========================================================================
    // MERCHANT PAYMENT FIELDS
    // ========================================================================

    @Column(name = "merchant_id", length = 30)
    private String merchantId;

    @Column(name = "merchant_name")
    private String merchantName;

    @Column(name = "merchant_category", length = 10)
    private String merchantCategory;

    @Column(name = "terminal_id", length = 20)
    private String terminalId;

    @Column(name = "authorization_code", length = 20)
    private String authorizationCode;

    // ========================================================================
    // REVERSAL TRACKING
    // ========================================================================

    @Column(name = "reversed_at")
    private LocalDateTime reversedAt;

    @Column(name = "reversal_reference")
    private String reversalReference;

    @Column(name = "original_transaction_id")
    private UUID originalTransactionId;

    // ========================================================================
    // AUDIT
    // ========================================================================

    @Column(name = "initiated_by")
    private String initiatedBy;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    @Column(name = "device_id")
    private String deviceId;

    // ========================================================================
    // PROCESSING METADATA
    // ========================================================================

    @Column(name = "routing_time_ms")
    private Integer routingTimeMs;

    @Column(name = "processing_notes", length = 500)
    private String processingNotes;

    @Column(name = "metadata", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String metadata;

    // ========================================================================
    // ENUMS
    // ========================================================================

    /**
     * Transaction Category - INTERNAL vs EXTERNAL (v5.0).
     */
    public enum TransactionCategory {
        /**
         * Internal VA bookkeeping - no real money movement.
         * Examples: VA-to-VA transfers, fees, sweeps, netting, IHB
         * physicalAccountId: OPTIONAL (can be null)
         */
        INTERNAL,
        
        /**
         * External money movement - involves CBS/BANCS.
         * Examples: SWIFT, RTGS, bank deposits, withdrawals
         * physicalAccountId: RECOMMENDED (for reconciliation)
         */
        EXTERNAL
    }

    public enum MovementType {
        // Standard VA movements
        CREDIT, DEBIT, TRANSFER_IN, TRANSFER_OUT, REVERSAL,
        // Treasury movements
        SWEEP_IN, SWEEP_OUT, POOL_CREDIT, POOL_DEBIT, NETTING,
        // IHB Intercompany movements (Mirror Account Model - 6-leg POBO)
        IC_RECEIVABLE, IC_PAYABLE, IHB_SETTLEMENT, IC_SETTLEMENT,
        // Wallet movements
        TOPUP, WITHDRAWAL, WALLET_TRANSFER_IN, WALLET_TRANSFER_OUT,
        PAYMENT, PURCHASE, REFUND, CASHBACK, FEE, INTEREST, ADJUSTMENT,
        // ROBO/POBO movements
        ROBO_CREDIT, POBO_DEBIT, HIERARCHY_TRANSFER,
        // Loyalty movements
        POINTS_EARN, POINTS_BURN, POINTS_TRANSFER, POINTS_EXPIRE,
        // Card movements
        CARD_PURCHASE, CARD_REFUND, CARD_AUTHORIZATION, CARD_SETTLEMENT,
        // Gift card movements
        GIFT_CARD_LOAD, GIFT_CARD_REDEEM,
        // Settlement/Exception VA types
        FEE_CREDIT, CHARGE_CREDIT, TAX_CREDIT, EXCEPTION_PARK,
        EXCEPTION_RELEASE, SETTLEMENT_CREDIT, INTEREST_ALLOCATE,
        // Exception Account movements (v5.3)
        EXCEPTION_CREDIT, EXCEPTION_DEBIT
    }

    public enum TransactionStatus {
        PENDING, PROCESSING, COMPLETED, FAILED, REVERSED, CANCELLED,
        ON_HOLD, EXPIRED, UNMATCHED, PARTIAL, HELD
    }

    public enum ReconciliationMatchType {
        AUTO, MANUAL, PARTIAL, UNMATCHED, REJECTED
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    public boolean isCredit() {
        return movementType == MovementType.CREDIT ||
               movementType == MovementType.TRANSFER_IN ||
               movementType == MovementType.TOPUP ||
               movementType == MovementType.WALLET_TRANSFER_IN ||
               movementType == MovementType.REFUND ||
               movementType == MovementType.CASHBACK ||
               movementType == MovementType.INTEREST ||
               movementType == MovementType.SWEEP_IN ||
               movementType == MovementType.POOL_CREDIT ||
               movementType == MovementType.ROBO_CREDIT ||
               movementType == MovementType.POINTS_EARN ||
               movementType == MovementType.CARD_REFUND ||
               movementType == MovementType.GIFT_CARD_LOAD ||
               movementType == MovementType.FEE_CREDIT ||
               movementType == MovementType.CHARGE_CREDIT ||
               movementType == MovementType.TAX_CREDIT ||
               movementType == MovementType.SETTLEMENT_CREDIT ||
               movementType == MovementType.EXCEPTION_PARK ||
               movementType == MovementType.EXCEPTION_CREDIT ||
               movementType == MovementType.INTEREST_ALLOCATE ||
               movementType == MovementType.IC_PAYABLE;  // IC Payable increases liability (Treasury owes more)
    }

    public boolean isDebit() {
        return movementType == MovementType.DEBIT ||
               movementType == MovementType.TRANSFER_OUT ||
               movementType == MovementType.WITHDRAWAL ||
               movementType == MovementType.WALLET_TRANSFER_OUT ||
               movementType == MovementType.PAYMENT ||
               movementType == MovementType.PURCHASE ||
               movementType == MovementType.FEE ||
               movementType == MovementType.SWEEP_OUT ||
               movementType == MovementType.POOL_DEBIT ||
               movementType == MovementType.POBO_DEBIT ||
               movementType == MovementType.POINTS_BURN ||
               movementType == MovementType.CARD_PURCHASE ||
               movementType == MovementType.GIFT_CARD_REDEEM ||
               movementType == MovementType.EXCEPTION_RELEASE ||
               movementType == MovementType.EXCEPTION_DEBIT;
    }

    /**
     * Check if this is an internal transaction (pure VA bookkeeping).
     */
    public boolean isInternalTransaction() {
        return transactionCategory == TransactionCategory.INTERNAL;
    }

    /**
     * Check if this is an external transaction (real money movement).
     */
    public boolean isExternalTransaction() {
        return transactionCategory == TransactionCategory.EXTERNAL;
    }

    public boolean isWalletTransaction() {
        return movementType == MovementType.TOPUP ||
               movementType == MovementType.WITHDRAWAL ||
               movementType == MovementType.WALLET_TRANSFER_IN ||
               movementType == MovementType.WALLET_TRANSFER_OUT ||
               movementType == MovementType.PAYMENT ||
               movementType == MovementType.PURCHASE ||
               movementType == MovementType.REFUND ||
               movementType == MovementType.CASHBACK;
    }

    public boolean wasRoutedViaViban() {
        return Boolean.TRUE.equals(routedViaViban) && viban != null;
    }

    public boolean wasAutoReconciled() {
        return Boolean.TRUE.equals(autoReconciled) && reconciledReferenceId != null;
    }

    public boolean isPayOnBehalfOf() {
        return Boolean.TRUE.equals(isPobo);
    }

    public boolean isReceiveOnBehalfOf() {
        return Boolean.TRUE.equals(isRobo);
    }

    public boolean hasHierarchyContext() {
        return sourceHierarchyNodeId != null || targetHierarchyNodeId != null;
    }

    public BigDecimal getSignedAmount() {
        return isDebit() ? amount.negate() : amount;
    }

    public boolean isCompleted() {
        return status == TransactionStatus.COMPLETED;
    }

    public boolean isPending() {
        return status == TransactionStatus.PENDING;
    }

    public boolean needsReconciliation() {
        return status == TransactionStatus.UNMATCHED 
            || reconciliationMatchType == ReconciliationMatchType.UNMATCHED;
    }

    public boolean canBeReversed() {
        return status == TransactionStatus.COMPLETED && reversedAt == null;
    }

    public void calculateNetAmount() {
        if (feeAmount == null) feeAmount = BigDecimal.ZERO;
        if (isCredit()) {
            this.netAmount = amount.subtract(feeAmount);
        } else {
            this.netAmount = amount.add(feeAmount);
        }
    }

    public boolean isSettlementCredit() {
        return movementType == MovementType.FEE_CREDIT ||
               movementType == MovementType.CHARGE_CREDIT ||
               movementType == MovementType.TAX_CREDIT ||
               movementType == MovementType.SETTLEMENT_CREDIT;
    }

    public boolean isExceptionMovement() {
        return movementType == MovementType.EXCEPTION_PARK ||
               movementType == MovementType.EXCEPTION_RELEASE;
    }

    /**
     * Check if this transaction has a fee breakdown.
     */
    public boolean hasFeeBreakdown() {
        return feeBreakdown != null && !feeBreakdown.isEmpty();
    }

    // ========================================================================
    // FACTORY METHODS
    // ========================================================================

    public static String generateReference(MovementType type) {
        String prefix = switch (type) {
            case TOPUP -> "TOP";
            case WITHDRAWAL -> "WTH";
            case WALLET_TRANSFER_IN, WALLET_TRANSFER_OUT, TRANSFER_IN, TRANSFER_OUT -> "TRF";
            case PAYMENT, PURCHASE -> "PAY";
            case REFUND -> "REF";
            case FEE -> "FEE";
            case REVERSAL -> "REV";
            case SWEEP_IN, SWEEP_OUT -> "SWP";
            case POOL_CREDIT, POOL_DEBIT -> "POL";
            case NETTING -> "NET";
            case ROBO_CREDIT -> "ROBO";
            case POBO_DEBIT -> "POBO";
            case POINTS_EARN, POINTS_BURN, POINTS_TRANSFER -> "PTS";
            case CARD_PURCHASE, CARD_REFUND -> "CRD";
            case GIFT_CARD_LOAD, GIFT_CARD_REDEEM -> "GFT";
            case FEE_CREDIT, CHARGE_CREDIT, TAX_CREDIT, SETTLEMENT_CREDIT -> "SET";
            case EXCEPTION_PARK -> "EXP";
            case EXCEPTION_RELEASE -> "EXR";
            case EXCEPTION_CREDIT -> "EXC";
            case EXCEPTION_DEBIT -> "EXD";
            case INTEREST_ALLOCATE -> "INT";
            default -> "TXN";
        };
        return prefix + System.currentTimeMillis() + String.format("%04d", (int)(Math.random() * 10000));
    }

    /**
     * Determine transaction category based on movement type.
     */
    public static TransactionCategory determineCategory(MovementType type, UUID counterpartyVaId) {
        // Internal transaction types (pure VA bookkeeping)
        if (type == MovementType.FEE || type == MovementType.FEE_CREDIT ||
            type == MovementType.CHARGE_CREDIT || type == MovementType.TAX_CREDIT ||
            type == MovementType.SETTLEMENT_CREDIT || type == MovementType.INTEREST ||
            type == MovementType.INTEREST_ALLOCATE || type == MovementType.SWEEP_IN ||
            type == MovementType.SWEEP_OUT || type == MovementType.POOL_CREDIT ||
            type == MovementType.POOL_DEBIT || type == MovementType.NETTING ||
            type == MovementType.WALLET_TRANSFER_IN || type == MovementType.WALLET_TRANSFER_OUT ||
            type == MovementType.TRANSFER_IN || type == MovementType.TRANSFER_OUT ||
            type == MovementType.EXCEPTION_PARK || type == MovementType.EXCEPTION_RELEASE ||
            type == MovementType.HIERARCHY_TRANSFER) {
            return TransactionCategory.INTERNAL;
        }
        
        // VA-to-VA transfer is internal
        if (counterpartyVaId != null) {
            return TransactionCategory.INTERNAL;
        }
        
        // Default to external for real money movement
        return TransactionCategory.EXTERNAL;
    }

    // ========================================================================
    // SIMPLIFIED FACTORY METHODS (v5.0 - physicalAccountId optional)
    // ========================================================================

    /**
     * Create an internal VA transaction (physicalAccountId optional).
     */
    public static Transaction createInternal(UUID corporateId, UUID vaId, MovementType type,
                                              BigDecimal amount, String currencyCode,
                                              UUID counterpartyVaId, String correlationId,
                                              String description) {
        return Transaction.builder()
            .movementType(type)
            .transactionCategory(TransactionCategory.INTERNAL)
            .corporateId(corporateId)
            .vaId(vaId)
            .physicalAccountId(null)  // Not required for internal
            .amount(amount)
            .currencyCode(currencyCode)
            .counterpartyVaId(counterpartyVaId)
            .correlationId(correlationId)
            .description(description)
            .transactionDate(LocalDateTime.now())
            .valueDate(LocalDate.now())
            .referenceNumber(generateReference(type))
            .status(TransactionStatus.COMPLETED)
            .channel("INTERNAL")
            .build();
    }

    /**
     * Create fee credit transaction for Settlement VA (physicalAccountId optional).
     */
    public static Transaction createFeeCredit(UUID corporateId, UUID settlementVaId,
                                              UUID programId, BigDecimal amount, String currencyCode,
                                              UUID sourceVaId, String correlationId, String description) {
        return Transaction.builder()
            .movementType(MovementType.FEE_CREDIT)
            .transactionCategory(TransactionCategory.INTERNAL)
            .corporateId(corporateId)
            .vaId(settlementVaId)
            .physicalAccountId(null)  // Not required for fee credits
            .programId(programId)
            .amount(amount)
            .currencyCode(currencyCode)
            .counterpartyVaId(sourceVaId)
            .correlationId(correlationId)
            .description(description)
            .transactionDate(LocalDateTime.now())
            .valueDate(LocalDate.now())
            .referenceNumber(generateReference(MovementType.FEE_CREDIT))
            .status(TransactionStatus.COMPLETED)
            .channel("TREASURY")
            .build();
    }

    /**
     * Create exception park transaction (physicalAccountId optional).
     */
    public static Transaction createExceptionPark(UUID corporateId, UUID exceptionVaId,
                                                   UUID programId, BigDecimal amount, String currencyCode,
                                                   UUID sourceVaId, String correlationId, String description) {
        return Transaction.builder()
            .movementType(MovementType.EXCEPTION_PARK)
            .transactionCategory(TransactionCategory.INTERNAL)
            .corporateId(corporateId)
            .vaId(exceptionVaId)
            .physicalAccountId(null)  // Not required for exception park
            .programId(programId)
            .amount(amount)
            .currencyCode(currencyCode)
            .counterpartyVaId(sourceVaId)
            .correlationId(correlationId)
            .description(description)
            .transactionDate(LocalDateTime.now())
            .valueDate(LocalDate.now())
            .referenceNumber(generateReference(MovementType.EXCEPTION_PARK))
            .status(TransactionStatus.COMPLETED)
            .channel("TREASURY")
            .build();
    }

    /**
     * Create exception release transaction (physicalAccountId optional).
     */
    public static Transaction createExceptionRelease(UUID corporateId, UUID exceptionVaId,
                                                      UUID programId, BigDecimal amount, String currencyCode,
                                                      UUID targetVaId, String correlationId, String description) {
        return Transaction.builder()
            .movementType(MovementType.EXCEPTION_RELEASE)
            .transactionCategory(TransactionCategory.INTERNAL)
            .corporateId(corporateId)
            .vaId(exceptionVaId)
            .physicalAccountId(null)  // Not required for exception release
            .programId(programId)
            .amount(amount)
            .currencyCode(currencyCode)
            .counterpartyVaId(targetVaId)
            .correlationId(correlationId)
            .description(description)
            .transactionDate(LocalDateTime.now())
            .valueDate(LocalDate.now())
            .referenceNumber(generateReference(MovementType.EXCEPTION_RELEASE))
            .status(TransactionStatus.COMPLETED)
            .channel("TREASURY")
            .build();
    }

    /**
     * Create settlement credit transaction (physicalAccountId optional).
     */
    public static Transaction createSettlementCredit(UUID corporateId, UUID settlementVaId,
                                                      UUID programId, BigDecimal amount, String currencyCode,
                                                      UUID sourceVaId, String correlationId, String description) {
        return Transaction.builder()
            .movementType(MovementType.SETTLEMENT_CREDIT)
            .transactionCategory(TransactionCategory.INTERNAL)
            .corporateId(corporateId)
            .vaId(settlementVaId)
            .physicalAccountId(null)  // Not required for settlement credit
            .programId(programId)
            .amount(amount)
            .currencyCode(currencyCode)
            .counterpartyVaId(sourceVaId)
            .correlationId(correlationId)
            .description(description)
            .transactionDate(LocalDateTime.now())
            .valueDate(LocalDate.now())
            .referenceNumber(generateReference(MovementType.SETTLEMENT_CREDIT))
            .status(TransactionStatus.COMPLETED)
            .channel("TREASURY")
            .build();
    }

    // ========================================================================
    // LEGACY FACTORY METHODS (kept for backward compatibility)
    // ========================================================================

    @Deprecated
    public static Transaction createRoboCredit(UUID corporateId, UUID vaId, UUID physicalAccountId,
                                                BigDecimal amount, String currencyCode,
                                                String viban, UUID vibanId,
                                                String referenceType, String referenceId) {
        return Transaction.builder()
            .movementType(MovementType.ROBO_CREDIT)
            .transactionCategory(TransactionCategory.EXTERNAL)
            .corporateId(corporateId)
            .vaId(vaId)
            .physicalAccountId(physicalAccountId)
            .amount(amount)
            .currencyCode(currencyCode)
            .transactionDate(LocalDateTime.now())
            .valueDate(LocalDate.now())
            .referenceNumber(generateReference(MovementType.ROBO_CREDIT))
            .viban(viban)
            .vibanId(vibanId)
            .routedViaViban(true)
            .isRobo(true)
            .autoReconciled(referenceId != null)
            .reconciledReferenceType(referenceType)
            .reconciledReferenceId(referenceId)
            .reconciliationMatchType(referenceId != null ? ReconciliationMatchType.AUTO : ReconciliationMatchType.UNMATCHED)
            .status(TransactionStatus.COMPLETED)
            .build();
    }

    @Deprecated
    public static Transaction createPoboDebit(UUID corporateId, UUID vaId, UUID physicalAccountId,
                                               BigDecimal amount, String currencyCode,
                                               String behalfOfEntity, UUID behalfOfVaId) {
        return Transaction.builder()
            .movementType(MovementType.POBO_DEBIT)
            .transactionCategory(TransactionCategory.EXTERNAL)
            .corporateId(corporateId)
            .vaId(vaId)
            .physicalAccountId(physicalAccountId)
            .amount(amount)
            .currencyCode(currencyCode)
            .transactionDate(LocalDateTime.now())
            .valueDate(LocalDate.now())
            .referenceNumber(generateReference(MovementType.POBO_DEBIT))
            .isPobo(true)
            .behalfOfEntity(behalfOfEntity)
            .behalfOfVaId(behalfOfVaId)
            .status(TransactionStatus.COMPLETED)
            .build();
    }

    @Deprecated
    public static Transaction createTopup(UUID corporateId, UUID vaId, UUID physicalAccountId,
                                          BigDecimal amount, String currencyCode,
                                          String sourceType, String sourceReference) {
        return Transaction.builder()
            .movementType(MovementType.TOPUP)
            .transactionCategory(TransactionCategory.EXTERNAL)
            .corporateId(corporateId)
            .vaId(vaId)
            .physicalAccountId(physicalAccountId)
            .amount(amount)
            .currencyCode(currencyCode)
            .transactionDate(LocalDateTime.now())
            .valueDate(LocalDate.now())
            .referenceNumber(generateReference(MovementType.TOPUP))
            .sourceType(sourceType)
            .sourceReference(sourceReference)
            .status(TransactionStatus.COMPLETED)
            .build();
    }

    @Deprecated
    public static Transaction createWithdrawal(UUID corporateId, UUID vaId, UUID physicalAccountId,
                                               BigDecimal amount, String currencyCode,
                                               String destinationType, String destinationReference) {
        return Transaction.builder()
            .movementType(MovementType.WITHDRAWAL)
            .transactionCategory(TransactionCategory.EXTERNAL)
            .corporateId(corporateId)
            .vaId(vaId)
            .physicalAccountId(physicalAccountId)
            .amount(amount)
            .currencyCode(currencyCode)
            .transactionDate(LocalDateTime.now())
            .valueDate(LocalDate.now())
            .referenceNumber(generateReference(MovementType.WITHDRAWAL))
            .destinationType(destinationType)
            .destinationReference(destinationReference)
            .status(TransactionStatus.COMPLETED)
            .build();
    }

    @Deprecated
    public static Transaction createPayment(UUID corporateId, UUID vaId, UUID physicalAccountId,
                                            BigDecimal amount, String currencyCode,
                                            String merchantId, String merchantName, String merchantCategory) {
        return Transaction.builder()
            .movementType(MovementType.PAYMENT)
            .transactionCategory(TransactionCategory.EXTERNAL)
            .corporateId(corporateId)
            .vaId(vaId)
            .physicalAccountId(physicalAccountId)
            .amount(amount)
            .currencyCode(currencyCode)
            .transactionDate(LocalDateTime.now())
            .valueDate(LocalDate.now())
            .referenceNumber(generateReference(MovementType.PAYMENT))
            .merchantId(merchantId)
            .merchantName(merchantName)
            .merchantCategory(merchantCategory)
            .status(TransactionStatus.COMPLETED)
            .build();
    }
}