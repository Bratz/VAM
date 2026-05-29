package com.bank.vam.entity.pobo;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Intercompany Recharge Entity - POBO recharge records for IHB integration.
 * 
 * When parent/treasury center pays on behalf of subsidiary:
 * 1. Parent makes payment to external vendor
 * 2. System creates intercompany recharge to subsidiary
 * 3. Recharge can be settled via:
 *    - IHB Loan (subsidiary borrows from parent)
 *    - IHB Deposit Offset
 *    - Netting in next cycle
 *    - Direct payment
 * 
 * Transfer Pricing Compliance:
 * - Arm's length validation
 * - Service fee documentation
 * - Regulatory reporting
 * 
 * VAM Integration:
 * - Links to Payables module
 * - Integrates with IHB loans/deposits
 * - Supports netting cycles
 */
@Entity
@Table(name = "intercompany_recharges", indexes = {
    @Index(name = "idx_recharge_payer", columnList = "payer_entity_id"),
    @Index(name = "idx_recharge_behalf", columnList = "behalf_entity_id"),
    @Index(name = "idx_recharge_payable", columnList = "original_payable_id"),
    @Index(name = "idx_recharge_status", columnList = "status"),
    @Index(name = "idx_recharge_settlement", columnList = "settlement_date"),
    @Index(name = "idx_recharge_type", columnList = "recharge_type"),
    @Index(name = "idx_recharge_flow_direction", columnList = "flow_direction")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IntercompanyRecharge extends BaseEntity {

    // ========================================================================
    // IDENTIFICATION
    // ========================================================================

    @Column(name = "recharge_reference", nullable = false, unique = true, length = 50)
    private String rechargeReference;

    // ========================================================================
    // PAYER (Who Paid - Parent/Treasury Center)
    // ========================================================================

    @Column(name = "payer_entity_id", nullable = false)
    private UUID payerEntityId;

    @Column(name = "payer_entity_code", nullable = false, length = 50)
    private String payerEntityCode;

    @Column(name = "payer_entity_name", length = 200)
    private String payerEntityName;

    @Column(name = "payer_va_id")
    private UUID payerVaId;

    // ========================================================================
    // BEHALF (On Whose Behalf - Subsidiary)
    // ========================================================================

    @Column(name = "behalf_entity_id", nullable = false)
    private UUID behalfEntityId;

    @Column(name = "behalf_entity_code", nullable = false, length = 50)
    private String behalfEntityCode;

    @Column(name = "behalf_entity_name", length = 200)
    private String behalfEntityName;

    @Column(name = "behalf_va_id")
    private UUID behalfVaId;

    // ========================================================================
    // ORIGINAL PAYMENT REFERENCE
    // ========================================================================

    @Column(name = "original_payable_id", nullable = false)
    private UUID originalPayableId;

    @Column(name = "original_payment_execution_id")
    private UUID originalPaymentExecutionId;

    @Column(name = "original_payment_reference", length = 100)
    private String originalPaymentReference;

    // ========================================================================
    // AMOUNTS
    // ========================================================================

    @Column(name = "original_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal originalAmount;

    @Column(name = "recharge_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal rechargeAmount;

    @Column(name = "currency_code", nullable = false, length = 3)
    @Builder.Default
    private String currencyCode = "AED";

    // ========================================================================
    // SERVICE/ADMIN FEES
    // ========================================================================

    @Column(name = "service_fee", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal serviceFee = BigDecimal.ZERO;

    @Column(name = "admin_fee", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal adminFee = BigDecimal.ZERO;

    @Column(name = "fx_markup", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal fxMarkup = BigDecimal.ZERO;

    @Column(name = "total_recharge", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalRecharge;

    // ========================================================================
    // TRANSFER PRICING
    // ========================================================================

    @Column(name = "transfer_pricing_rate", precision = 8, scale = 4)
    private BigDecimal transferPricingRate;

    @Column(name = "arm_length_validated")
    @Builder.Default
    private Boolean armLengthValidated = false;

    @Column(name = "arm_length_notes", columnDefinition = "TEXT")
    private String armLengthNotes;

    // ========================================================================
    // IHB INTEGRATION
    // ========================================================================

    @Column(name = "ihb_transaction_id")
    private UUID ihbTransactionId;

    @Column(name = "ihb_loan_id")
    private UUID ihbLoanId;

    @Column(name = "creates_intercompany_loan")
    @Builder.Default
    private Boolean createsIntercompanyLoan = false;

    // ========================================================================
    // STATUS
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30)
    @Builder.Default
    private RechargeStatus status = RechargeStatus.PENDING;

    // ========================================================================
    // SETTLEMENT
    // ========================================================================

    @Column(name = "settlement_date")
    private LocalDate settlementDate;

    @Column(name = "settlement_reference", length = 100)
    private String settlementReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "settled_via", length = 30)
    private SettlementMethod settledVia;

    // ========================================================================
    // APPROVAL
    // ========================================================================

    @Column(name = "approval_required")
    @Builder.Default
    private Boolean approvalRequired = true;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    // ========================================================================
    // RECHARGE TYPE & FLOW DIRECTION (Bidirectional Support)
    // ========================================================================

    /**
     * Type of recharge indicating the nature of the transaction.
     * Nullable for backward compatibility with existing records.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "recharge_type", length = 30)
    private RechargeType rechargeType;

    /**
     * Direction of fund flow relative to the corporate group.
     * Nullable for backward compatibility with existing records.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "flow_direction", length = 20)
    private FlowDirection flowDirection;

    // ========================================================================
    // ENUMS
    // ========================================================================

    public enum RechargeStatus {
        PENDING,        // Awaiting approval
        APPROVED,       // Approved, pending settlement
        RECHARGED,      // Recharge posted
        SETTLED,        // Fully settled
        DISPUTED,       // Under dispute
        CANCELLED       // Cancelled
    }

    public enum SettlementMethod {
        IHB_LOAN,               // Settled via IHB loan
        IHB_DEPOSIT_OFFSET,     // Offset against deposit
        NETTING,                // Included in netting cycle
        DIRECT_PAYMENT,         // Direct cash transfer
        REVERSAL                // Original payment reversed
    }

    /**
     * Recharge type indicating the nature of the intercompany transaction.
     */
    public enum RechargeType {
        POBO_PAYMENT,           // Treasury paid vendor on behalf of Subsidiary
        COBO_COLLECTION,        // Treasury collected from customer on behalf of Subsidiary
        INTERCOMPANY_SALE,      // Intercompany goods/services sale
        INTERCOMPANY_SERVICE    // Shared service allocation
    }

    /**
     * Flow direction indicating whether funds left or entered the group.
     */
    public enum FlowDirection {
        OUTBOUND,   // Funds left the group (POBO)
        INBOUND     // Funds entered the group (COBO)
    }

    // ========================================================================
    // CALCULATION METHODS
    // ========================================================================

    /**
     * Calculate total recharge including all fees.
     */
    public void calculateTotalRecharge() {
        this.totalRecharge = rechargeAmount
            .add(serviceFee != null ? serviceFee : BigDecimal.ZERO)
            .add(adminFee != null ? adminFee : BigDecimal.ZERO)
            .add(fxMarkup != null ? fxMarkup : BigDecimal.ZERO);
    }

    /**
     * Add service fee.
     */
    public void addServiceFee(BigDecimal rate, BigDecimal minFee, BigDecimal maxFee) {
        BigDecimal fee = rechargeAmount.multiply(rate).divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
        
        if (minFee != null && fee.compareTo(minFee) < 0) fee = minFee;
        if (maxFee != null && fee.compareTo(maxFee) > 0) fee = maxFee;
        
        this.serviceFee = fee;
        calculateTotalRecharge();
    }

    // ========================================================================
    // WORKFLOW METHODS
    // ========================================================================

    /**
     * Approve the recharge.
     */
    public void approve(String approver) {
        this.status = RechargeStatus.APPROVED;
        this.approvedBy = approver;
        this.approvedAt = LocalDateTime.now();
    }

    /**
     * Reject the recharge.
     */
    public void reject(String rejector, String reason) {
        this.status = RechargeStatus.CANCELLED;
        this.approvedBy = rejector;
        this.approvedAt = LocalDateTime.now();
        this.rejectionReason = reason;
    }

    /**
     * Mark as recharged (posted to subsidiary).
     */
    public void markRecharged() {
        this.status = RechargeStatus.RECHARGED;
    }

    /**
     * Settle the recharge.
     */
    public void settle(SettlementMethod method, String reference) {
        this.status = RechargeStatus.SETTLED;
        this.settledVia = method;
        this.settlementReference = reference;
        this.settlementDate = LocalDate.now();
    }

    /**
     * Settle via IHB loan.
     */
    public void settleViaIhbLoan(UUID loanId, String loanReference) {
        this.ihbLoanId = loanId;
        this.createsIntercompanyLoan = true;
        settle(SettlementMethod.IHB_LOAN, loanReference);
    }

    // ========================================================================
    // VALIDATION METHODS
    // ========================================================================

    /**
     * Validate arm's length pricing.
     */
    public void validateArmLength(String notes) {
        this.armLengthValidated = true;
        this.armLengthNotes = notes;
    }

    /**
     * Check if requires arm's length validation.
     */
    public boolean requiresArmLengthValidation() {
        // Cross-border or significant amount
        return !payerEntityCode.equals(behalfEntityCode) 
            || originalAmount.compareTo(new BigDecimal("100000")) > 0;
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    public boolean isPending() {
        return status == RechargeStatus.PENDING;
    }

    public boolean isSettled() {
        return status == RechargeStatus.SETTLED;
    }

    public boolean canBeSettled() {
        return status == RechargeStatus.APPROVED || status == RechargeStatus.RECHARGED;
    }

    // ========================================================================
    // FACTORY METHODS
    // ========================================================================

    public static IntercompanyRecharge createFromPayable(
            String rechargeRef,
            UUID payerEntityId, String payerCode, String payerName,
            UUID behalfEntityId, String behalfCode, String behalfName,
            UUID payableId, BigDecimal amount, String currency) {
        
        IntercompanyRecharge recharge = IntercompanyRecharge.builder()
            .rechargeReference(rechargeRef)
            .payerEntityId(payerEntityId)
            .payerEntityCode(payerCode)
            .payerEntityName(payerName)
            .behalfEntityId(behalfEntityId)
            .behalfEntityCode(behalfCode)
            .behalfEntityName(behalfName)
            .originalPayableId(payableId)
            .originalAmount(amount)
            .rechargeAmount(amount)
            .currencyCode(currency)
            .status(RechargeStatus.PENDING)
            .build();
        
        recharge.calculateTotalRecharge();
        return recharge;
    }
}