package com.bank.vam.entity.tax;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Calculated Tax Entity - Audit trail for tax calculations.
 * 
 * Records all tax calculations for:
 * - Compliance and audit
 * - Tax reporting
 * - Withholding certificates
 * - Recovery tracking
 */
@Entity
@Table(name = "calculated_taxes", indexes = {
    @Index(name = "idx_calc_tax_reference", columnList = "reference_type, reference_id"),
    @Index(name = "idx_calc_tax_code", columnList = "tax_code"),
    @Index(name = "idx_calc_tax_type", columnList = "tax_type"),
    @Index(name = "idx_calc_tax_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CalculatedTax extends BaseEntity {

    // ========================================================================
    // REFERENCE
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", nullable = false, length = 30)
    private ReferenceType referenceType;

    @Column(name = "reference_id", nullable = false)
    private UUID referenceId;

    @Column(name = "line_item_id")
    private UUID lineItemId;

    // ========================================================================
    // TAX DETAILS
    // ========================================================================

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tax_config_id", nullable = false)
    private TaxConfiguration taxConfiguration;

    @Column(name = "tax_code", nullable = false, length = 30)
    private String taxCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "tax_type", nullable = false, length = 30)
    private TaxConfiguration.TaxType taxType;

    // ========================================================================
    // AMOUNTS
    // ========================================================================

    @Column(name = "base_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal baseAmount;

    @Column(name = "tax_rate", nullable = false, precision = 8, scale = 4)
    private BigDecimal taxRate;

    @Column(name = "calculated_tax", nullable = false, precision = 18, scale = 2)
    private BigDecimal calculatedTax;

    @Column(name = "adjusted_tax", precision = 18, scale = 2)
    private BigDecimal adjustedTax;

    @Column(name = "final_tax", nullable = false, precision = 18, scale = 2)
    private BigDecimal finalTax;

    @Column(name = "currency_code", nullable = false, length = 3)
    @Builder.Default
    private String currencyCode = "AED";

    // ========================================================================
    // WITHHOLDING SPECIFICS
    // ========================================================================

    @Column(name = "is_withholding")
    @Builder.Default
    private Boolean isWithholding = false;

    @Column(name = "withheld_by", length = 200)
    private String withheldBy;

    @Column(name = "withholding_certificate", length = 100)
    private String withholdingCertificate;

    // ========================================================================
    // STATUS
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    @Builder.Default
    private CalculationStatus status = CalculationStatus.CALCULATED;

    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    // ========================================================================
    // AUDIT
    // ========================================================================

    @Column(name = "va_id")
    private UUID vaId;  // Source VA to charge
    
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
        INVOICE,
        TRANSACTION,
        LINE_ITEM
    }

    public enum CalculationStatus {
        CALCULATED,     // Tax calculated
        APPLIED,        // Applied to transaction
        REPORTED,       // Reported to authority
        FILED,          // Tax return filed
        PAID            // Tax paid to authority
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    /**
     * Apply the calculated tax (finalize).
     */
    public void apply() {
        this.status = CalculationStatus.APPLIED;
        this.appliedAt = LocalDateTime.now();
        if (this.adjustedTax != null) {
            this.finalTax = this.adjustedTax;
        }
    }

    /**
     * Adjust the tax amount.
     */
    public void adjust(BigDecimal adjustment, String notes) {
        this.adjustedTax = adjustment;
        this.calculationNotes = notes;
    }

    /**
     * Check if this is a withholding tax.
     */
    public boolean isWithholdingTax() {
        return Boolean.TRUE.equals(isWithholding) 
            || taxType == TaxConfiguration.TaxType.WHT;
    }

    // ========================================================================
    // FACTORY METHODS
    // ========================================================================

    public static CalculatedTax create(ReferenceType refType, UUID refId,
                                        TaxConfiguration config, 
                                        BigDecimal baseAmount) {
        BigDecimal tax = config.calculateTax(baseAmount);
        
        return CalculatedTax.builder()
            .referenceType(refType)
            .referenceId(refId)
            .taxConfiguration(config)
            .taxCode(config.getTaxCode())
            .taxType(config.getTaxType())
            .baseAmount(baseAmount)
            .taxRate(config.getRatePercentage())
            .calculatedTax(tax)
            .finalTax(tax)
            .isWithholding(config.getIsWithholding())
            .status(CalculationStatus.CALCULATED)
            .build();
    }
}