package com.bank.vam.entity.tax;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Tax Configuration Entity - Tax rates and rules by jurisdiction.
 * 
 * Supports tax types:
 * - VAT (Value Added Tax)
 * - GST (Goods and Services Tax)
 * - WHT (Withholding Tax)
 * - Sales Tax
 * - Excise Duty
 * - Stamp Duty
 * - Customs
 * 
 * Integrated with:
 * - Payables for invoice tax calculation
 * - Receivables for billing tax
 * - Payment execution for withholding
 * 
 * VAM Compliance:
 * - Automatic tax calculation based on jurisdiction
 * - Withholding tax support for cross-border payments
 * - Tax recovery tracking
 */
@Entity
@Table(name = "tax_configurations", indexes = {
    @Index(name = "idx_tax_config_type", columnList = "tax_type"),
    @Index(name = "idx_tax_config_jurisdiction", columnList = "jurisdiction_id"),
    @Index(name = "idx_tax_config_status", columnList = "status"),
    @Index(name = "idx_tax_config_category", columnList = "tax_category"),
    @Index(name = "idx_tax_config_withholding", columnList = "is_withholding")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaxConfiguration extends BaseEntity {

    // ========================================================================
    // IDENTIFICATION
    // ========================================================================

    @Column(name = "tax_code", nullable = false, unique = true, length = 30)
    private String taxCode;

    @Column(name = "tax_name", nullable = false, length = 100)
    private String taxName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    // ========================================================================
    // CLASSIFICATION
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "tax_type", nullable = false, length = 30)
    private TaxType taxType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "jurisdiction_id")
    private TaxJurisdiction jurisdiction;

    @Column(name = "jurisdiction_code", length = 20)
    private String jurisdictionCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "tax_category", length = 50)
    @Builder.Default
    private TaxCategory taxCategory = TaxCategory.STANDARD;

    // ========================================================================
    // RATES
    // ========================================================================

    @Column(name = "rate_percentage", nullable = false, precision = 8, scale = 4)
    private BigDecimal ratePercentage;

    @Column(name = "minimum_amount", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal minimumAmount = BigDecimal.ZERO;

    @Column(name = "maximum_amount", precision = 18, scale = 2)
    private BigDecimal maximumAmount;

    // ========================================================================
    // APPLICABILITY
    // ========================================================================

    @Column(name = "applies_to_payables")
    @Builder.Default
    private Boolean appliesToPayables = true;

    @Column(name = "applies_to_receivables")
    @Builder.Default
    private Boolean appliesToReceivables = true;

    @Column(name = "applies_to_services")
    @Builder.Default
    private Boolean appliesToServices = true;

    @Column(name = "applies_to_goods")
    @Builder.Default
    private Boolean appliesToGoods = true;

    // ========================================================================
    // WITHHOLDING TAX SPECIFIC
    // ========================================================================

    @Column(name = "is_withholding")
    @Builder.Default
    private Boolean isWithholding = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "withholding_entity_type", length = 50)
    private WithholdingEntityType withholdingEntityType;

    // ========================================================================
    // RECOVERY
    // ========================================================================

    @Column(name = "is_recoverable")
    @Builder.Default
    private Boolean isRecoverable = true;

    @Column(name = "recovery_percentage", precision = 8, scale = 4)
    @Builder.Default
    private BigDecimal recoveryPercentage = new BigDecimal("100");

    // ========================================================================
    // ACCOUNT MAPPING
    // ========================================================================

    @Column(name = "tax_payable_account", length = 50)
    private String taxPayableAccount;

    @Column(name = "tax_receivable_account", length = 50)
    private String taxReceivableAccount;

    // ========================================================================
    // VALIDITY
    // ========================================================================

    @Column(name = "effective_from", nullable = false)
    @Builder.Default
    private LocalDate effectiveFrom = LocalDate.now();

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    @Builder.Default
    private TaxStatus status = TaxStatus.ACTIVE;

    // ========================================================================
    // ENUMS
    // ========================================================================

    public enum TaxType {
        VAT,            // Value Added Tax
        GST,            // Goods and Services Tax
        WHT,            // Withholding Tax
        SALES_TAX,      // Sales Tax
        EXCISE,         // Excise Duty
        STAMP_DUTY,     // Stamp Duty
        CUSTOMS,        // Customs Duty
        OTHER           // Other taxes
    }

    public enum TaxCategory {
        STANDARD,       // Standard rate
        REDUCED,        // Reduced rate
        ZERO,           // Zero rate
        EXEMPT,         // Exempt from tax
        SPECIAL         // Special rate
    }

    public enum WithholdingEntityType {
        RESIDENT,
        NON_RESIDENT,
        CORPORATE,
        INDIVIDUAL,
        GOVERNMENT
    }

    public enum TaxStatus {
        ACTIVE,
        INACTIVE,
        PENDING,
        SUPERSEDED
    }

    // ========================================================================
    // CALCULATION METHODS
    // ========================================================================

    /**
     * Calculate tax amount for a given base amount.
     */
    public BigDecimal calculateTax(BigDecimal baseAmount) {
        if (baseAmount == null || baseAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        
        if (taxCategory == TaxCategory.EXEMPT || taxCategory == TaxCategory.ZERO) {
            return BigDecimal.ZERO;
        }
        
        // Calculate tax: base * rate / 100
        BigDecimal tax = baseAmount.multiply(ratePercentage)
            .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        
        // Apply minimum
        if (minimumAmount != null && tax.compareTo(minimumAmount) < 0) {
            tax = minimumAmount;
        }
        
        // Apply maximum
        if (maximumAmount != null && tax.compareTo(maximumAmount) > 0) {
            tax = maximumAmount;
        }
        
        return tax;
    }

    /**
     * Calculate recoverable tax amount.
     */
    public BigDecimal calculateRecoverableTax(BigDecimal taxAmount) {
        if (!Boolean.TRUE.equals(isRecoverable) || taxAmount == null) {
            return BigDecimal.ZERO;
        }
        
        return taxAmount.multiply(recoveryPercentage)
            .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    }

    /**
     * Calculate net amount after tax.
     */
    public BigDecimal calculateNetWithTax(BigDecimal baseAmount, boolean addTax) {
        BigDecimal tax = calculateTax(baseAmount);
        return addTax ? baseAmount.add(tax) : baseAmount.subtract(tax);
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    public boolean isActive() {
        return status == TaxStatus.ACTIVE
            && (effectiveTo == null || !LocalDate.now().isAfter(effectiveTo));
    }

    public boolean isWithholdingTax() {
        return Boolean.TRUE.equals(isWithholding) || taxType == TaxType.WHT;
    }

    public boolean appliesTo(boolean isPayable, boolean isService) {
        if (isPayable && !Boolean.TRUE.equals(appliesToPayables)) {
            return false;
        }
        if (!isPayable && !Boolean.TRUE.equals(appliesToReceivables)) {
            return false;
        }
        if (isService && !Boolean.TRUE.equals(appliesToServices)) {
            return false;
        }
        if (!isService && !Boolean.TRUE.equals(appliesToGoods)) {
            return false;
        }
        return true;
    }

    // ========================================================================
    // FACTORY METHODS
    // ========================================================================

    public static TaxConfiguration createVAT5(String jurisdictionCode) {
        return TaxConfiguration.builder()
            .taxCode("VAT5")
            .taxName("Standard VAT 5%")
            .description("Standard VAT at 5%")
            .taxType(TaxType.VAT)
            .jurisdictionCode(jurisdictionCode)
            .taxCategory(TaxCategory.STANDARD)
            .ratePercentage(new BigDecimal("5"))
            .isWithholding(false)
            .isRecoverable(true)
            .build();
    }

    public static TaxConfiguration createWithholdingTax(String code, String name, 
                                                         BigDecimal rate, 
                                                         WithholdingEntityType entityType) {
        return TaxConfiguration.builder()
            .taxCode(code)
            .taxName(name)
            .taxType(TaxType.WHT)
            .taxCategory(TaxCategory.STANDARD)
            .ratePercentage(rate)
            .isWithholding(true)
            .withholdingEntityType(entityType)
            .isRecoverable(false)
            .build();
    }
}