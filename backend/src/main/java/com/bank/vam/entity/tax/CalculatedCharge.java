package com.bank.vam.entity.tax;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Calculated Charge Entity - Audit trail for charge/fee calculations.
 * 
 * Records all fee calculations for:
 * - Billing transparency
 * - Revenue tracking
 * - Waiver documentation
 * - Customer statements
 */
@Entity
@Table(name = "calculated_charges", indexes = {
    @Index(name = "idx_calc_charge_reference", columnList = "reference_type, reference_id"),
    @Index(name = "idx_calc_charge_code", columnList = "charge_code"),
    @Index(name = "idx_calc_charge_type", columnList = "charge_type"),
    @Index(name = "idx_calc_charge_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CalculatedCharge extends BaseEntity {

    // ========================================================================
    // REFERENCE
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", nullable = false, length = 30)
    private ReferenceType referenceType;

    @Column(name = "reference_id", nullable = false)
    private UUID referenceId;

    // ========================================================================
    // CHARGE DETAILS
    // ========================================================================

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "charge_config_id", nullable = false)
    private ChargeConfiguration chargeConfiguration;

    @Column(name = "charge_code", nullable = false, length = 30)
    private String chargeCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "charge_type", nullable = false, length = 30)
    private ChargeConfiguration.ChargeType chargeType;

    // ========================================================================
    // AMOUNTS
    // ========================================================================

    @Column(name = "base_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal baseAmount;

    @Column(name = "calculated_charge", nullable = false, precision = 18, scale = 2)
    private BigDecimal calculatedCharge;

    @Column(name = "waived_amount", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal waivedAmount = BigDecimal.ZERO;

    @Column(name = "final_charge", nullable = false, precision = 18, scale = 2)
    private BigDecimal finalCharge;

    @Column(name = "currency_code", nullable = false, length = 3)
    @Builder.Default
    private String currencyCode = "AED";

    // ========================================================================
    // CALCULATION METHOD
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "calculation_method", length = 30)
    private ChargeConfiguration.ChargeCategory calculationMethod;

    @Column(name = "tier_applied")
    private Integer tierApplied;

    // ========================================================================
    // WAIVER INFO
    // ========================================================================

    @Column(name = "waiver_reason", length = 200)
    private String waiverReason;

    @Column(name = "waiver_approved_by", length = 100)
    private String waiverApprovedBy;

    // ========================================================================
    // STATUS
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    @Builder.Default
    private CalculationStatus status = CalculationStatus.CALCULATED;

    @Column(name = "va_id")
    private UUID vaId;  // Source VA to charge
    
    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    // ========================================================================
    // AUDIT
    // ========================================================================

    @Column(name = "calculated_by", length = 100)
    private String calculatedBy;

    @Column(name = "calculation_notes", columnDefinition = "TEXT")
    private String calculationNotes;

    // ========================================================================
    // ENUMS
    // ========================================================================

    public enum ReferenceType {
        PAYABLE,
        RECEIVABLE,
        PAYMENT_EXECUTION,
        BATCH,
        TRANSACTION
    }

    public enum CalculationStatus {
        CALCULATED,     // Charge calculated
        APPLIED,        // Applied to transaction
        INVOICED,       // Invoiced to customer
        COLLECTED       // Collected from customer
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    /**
     * Apply the calculated charge.
     */
    public void apply() {
        this.status = CalculationStatus.APPLIED;
        this.appliedAt = LocalDateTime.now();
    }

    /**
     * Waive all or part of the charge.
     */
    public void waive(BigDecimal amount, String reason, String approvedBy) {
        this.waivedAmount = amount;
        this.waiverReason = reason;
        this.waiverApprovedBy = approvedBy;
        this.finalCharge = this.calculatedCharge.subtract(amount);
        if (this.finalCharge.compareTo(BigDecimal.ZERO) < 0) {
            this.finalCharge = BigDecimal.ZERO;
        }
    }

    /**
     * Waive entire charge.
     */
    public void waiveAll(String reason, String approvedBy) {
        waive(this.calculatedCharge, reason, approvedBy);
    }

    /**
     * Check if charge was waived.
     */
    public boolean isWaived() {
        return waivedAmount != null && waivedAmount.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * Check if charge was fully waived.
     */
    public boolean isFullyWaived() {
        return finalCharge.compareTo(BigDecimal.ZERO) == 0 && isWaived();
    }

    // ========================================================================
    // FACTORY METHODS
    // ========================================================================

    public static CalculatedCharge create(ReferenceType refType, UUID refId,
                                           ChargeConfiguration config,
                                           BigDecimal baseAmount) {
        BigDecimal charge = config.calculateCharge(baseAmount);
        
        return CalculatedCharge.builder()
            .referenceType(refType)
            .referenceId(refId)
            .chargeConfiguration(config)
            .chargeCode(config.getChargeCode())
            .chargeType(config.getChargeType())
            .baseAmount(baseAmount)
            .calculatedCharge(charge)
            .finalCharge(charge)
            .calculationMethod(config.getChargeCategory())
            .status(CalculationStatus.CALCULATED)
            .build();
    }

    public static CalculatedCharge createWithWaiver(ReferenceType refType, UUID refId,
                                                     ChargeConfiguration config,
                                                     BigDecimal baseAmount,
                                                     boolean isVip, boolean isBulk) {
        CalculatedCharge charge = create(refType, refId, config, baseAmount);
        
        if (config.shouldWaive(baseAmount, isVip, isBulk)) {
            String reason = isVip ? "VIP Customer" : 
                           isBulk ? "Bulk Transaction" : "Threshold Waiver";
            charge.waiveAll(reason, "SYSTEM");
        }
        
        return charge;
    }
}