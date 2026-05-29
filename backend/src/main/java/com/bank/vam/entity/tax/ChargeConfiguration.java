package com.bank.vam.entity.tax;

import com.bank.vam.entity.BaseEntity;
import com.bank.vam.entity.payables.Payable;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Charge Configuration Entity - Fee schedules for payment processing.
 * 
 * Supports charge types:
 * - SWIFT fees
 * - FX conversion fees
 * - Processing fees
 * - Urgency/priority fees
 * - Service fees
 * - Maintenance fees
 * 
 * Calculation categories:
 * - FIXED: Flat fee
 * - PERCENTAGE: Rate-based
 * - TIERED: Volume-based tiers
 * - SLIDING_SCALE: Progressive rates
 * 
 * VAM Compliance:
 * - POBO service charges
 * - Cross-border payment fees
 * - Transparent fee disclosure
 */
@Entity
@Table(name = "charge_configurations", indexes = {
    @Index(name = "idx_charge_config_type", columnList = "charge_type"),
    @Index(name = "idx_charge_config_category", columnList = "charge_category"),
    @Index(name = "idx_charge_config_method", columnList = "applies_to_payment_method"),
    @Index(name = "idx_charge_config_priority", columnList = "applies_to_priority"),
    @Index(name = "idx_charge_config_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChargeConfiguration extends BaseEntity {

    // ========================================================================
    // IDENTIFICATION
    // ========================================================================

    @Column(name = "charge_code", nullable = false, unique = true, length = 30)
    private String chargeCode;

    @Column(name = "charge_name", nullable = false, length = 100)
    private String chargeName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    // ========================================================================
    // CLASSIFICATION
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "charge_type", nullable = false, length = 30)
    private ChargeType chargeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "charge_category", length = 30)
    @Builder.Default
    private ChargeCategory chargeCategory = ChargeCategory.FIXED;

    // ========================================================================
    // RATE CONFIGURATION
    // ========================================================================

    @Column(name = "fixed_amount", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal fixedAmount = BigDecimal.ZERO;

    @Column(name = "percentage_rate", precision = 8, scale = 4)
    @Builder.Default
    private BigDecimal percentageRate = BigDecimal.ZERO;

    @Column(name = "currency_code", length = 3)
    @Builder.Default
    private String currencyCode = "AED";

    // ========================================================================
    // BOUNDS
    // ========================================================================

    @Column(name = "minimum_charge", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal minimumCharge = BigDecimal.ZERO;

    @Column(name = "maximum_charge", precision = 18, scale = 2)
    private BigDecimal maximumCharge;

    // ========================================================================
    // TIERED PRICING
    // ========================================================================

    /**
     * Tier configuration as JSON.
     * Example: [{"from": 0, "to": 10000, "rate": 0.5}, {"from": 10000, "to": 100000, "rate": 0.3}]
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tier_config", columnDefinition = "jsonb")
    
    private List<Map<String, Object>> tierConfig;

    // ========================================================================
    // APPLICABILITY
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "applies_to_payment_method", length = 30)
    private Payable.PaymentMethod appliesToPaymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "applies_to_priority", length = 20)
    private Payable.PaymentPriority appliesToPriority;

    @Column(name = "applies_to_currency", length = 3)
    private String appliesToCurrency;

    @Column(name = "applies_to_country", length = 3)
    private String appliesToCountry;

    @Column(name = "is_cross_border")
    @Builder.Default
    private Boolean isCrossBorder = false;

    @Column(name = "is_domestic")
    @Builder.Default
    private Boolean isDomestic = true;

    // ========================================================================
    // WAIVER RULES
    // ========================================================================

    @Column(name = "waiver_threshold", precision = 18, scale = 2)
    private BigDecimal waiverThreshold;

    @Column(name = "waiver_for_vip")
    @Builder.Default
    private Boolean waiverForVip = false;

    @Column(name = "waiver_for_bulk")
    @Builder.Default
    private Boolean waiverForBulk = false;

    // ========================================================================
    // ACCOUNT MAPPING
    // ========================================================================

    @Column(name = "income_account", length = 50)
    private String incomeAccount;

    @Column(name = "expense_account", length = 50)
    private String expenseAccount;

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
    private ChargeStatus status = ChargeStatus.ACTIVE;

    // ========================================================================
    // ENUMS
    // ========================================================================

    public enum ChargeType {
        SWIFT_FEE,          // SWIFT transfer fees
        FX_FEE,             // Foreign exchange fees
        PROCESSING_FEE,     // Payment processing fees
        URGENCY_FEE,        // Priority/urgency fees
        SERVICE_FEE,        // General service fees
        MAINTENANCE_FEE,    // Account maintenance
        TRANSACTION_FEE,    // Per-transaction fees
        POBO_FEE,           // Pay On Behalf Of fee
        ADMIN_FEE,          // Administrative fees
        
        // BaaS / Wallet specific fees
        WALLET_TOPUP_FEE,   // Wallet top-up fee
        WALLET_WITHDRAWAL_FEE, // Wallet withdrawal fee
        CARD_ISSUANCE_FEE,  // Virtual/physical card issuance
        CARD_TRANSACTION_FEE, // Card transaction fee
        
        // E-commerce fees
        MERCHANT_FEE,       // Merchant processing fee
        SETTLEMENT_FEE,     // Settlement/payout fee
        REFUND_FEE,         // Refund processing fee
        CHARGEBACK_FEE,     // Chargeback handling fee
        
        // Treasury fees
        NETTING_FEE,        // Netting cycle fee
        POOLING_FEE,        // Notional pooling fee
        IHB_FEE,            // In-house bank fee
        
        OTHER               // Other charges
    }

    
    public enum ChargeCategory {
        FIXED,          // Flat fee
        PERCENTAGE,     // Percentage of amount
        TIERED,         // Volume-based tiers
        SLIDING_SCALE   // Progressive rates
    }

    public enum ChargeStatus {
        ACTIVE,
        INACTIVE,
        PENDING,
        SUPERSEDED
    }

    // ========================================================================
    // CALCULATION METHODS
    // ========================================================================

    /**
     * Calculate charge for a given base amount.
     */
    public BigDecimal calculateCharge(BigDecimal baseAmount) {
        if (baseAmount == null || baseAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal charge;
        
        switch (chargeCategory) {
            case FIXED:
                charge = fixedAmount != null ? fixedAmount : BigDecimal.ZERO;
                break;
                
            case PERCENTAGE:
                charge = baseAmount.multiply(percentageRate)
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                break;
                
            case TIERED:
                charge = calculateTieredCharge(baseAmount);
                break;
                
            case SLIDING_SCALE:
                charge = calculateSlidingScaleCharge(baseAmount);
                break;
                
            default:
                // Combined: fixed + percentage
                BigDecimal fixed = fixedAmount != null ? fixedAmount : BigDecimal.ZERO;
                BigDecimal pct = baseAmount.multiply(percentageRate)
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                charge = fixed.add(pct);
        }
        
        // Apply bounds
        charge = applyBounds(charge);
        
        return charge;
    }

    /**
     * Calculate tiered charge based on tier configuration.
     */
    private BigDecimal calculateTieredCharge(BigDecimal baseAmount) {
        if (tierConfig == null || tierConfig.isEmpty()) {
            return calculatePercentageCharge(baseAmount);
        }

        BigDecimal totalCharge = BigDecimal.ZERO;
        BigDecimal remaining = baseAmount;

        for (Map<String, Object> tier : tierConfig) {
            BigDecimal from = toBigDecimal(tier.get("from"));
            BigDecimal to = toBigDecimal(tier.get("to"));
            BigDecimal rate = toBigDecimal(tier.get("rate"));

            if (from == null || rate == null) continue;
            if (baseAmount.compareTo(from) <= 0) continue;

            BigDecimal tierAmount;
            if (to == null || baseAmount.compareTo(to) <= 0) {
                tierAmount = baseAmount.subtract(from);
            } else {
                tierAmount = to.subtract(from);
            }

            BigDecimal tierCharge = tierAmount.multiply(rate)
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            totalCharge = totalCharge.add(tierCharge);
        }

        return totalCharge;
    }

    /**
     * Calculate sliding scale charge (marginal rate on entire amount).
     */
    private BigDecimal calculateSlidingScaleCharge(BigDecimal baseAmount) {
        if (tierConfig == null || tierConfig.isEmpty()) {
            return calculatePercentageCharge(baseAmount);
        }

        // Find applicable tier
        BigDecimal applicableRate = percentageRate;
        
        for (Map<String, Object> tier : tierConfig) {
            BigDecimal from = toBigDecimal(tier.get("from"));
            BigDecimal to = toBigDecimal(tier.get("to"));
            BigDecimal rate = toBigDecimal(tier.get("rate"));

            if (from == null || rate == null) continue;
            
            boolean inRange = baseAmount.compareTo(from) >= 0 
                && (to == null || baseAmount.compareTo(to) < 0);
                
            if (inRange) {
                applicableRate = rate;
                break;
            }
        }

        return baseAmount.multiply(applicableRate)
            .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculatePercentageCharge(BigDecimal baseAmount) {
        return baseAmount.multiply(percentageRate)
            .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal applyBounds(BigDecimal charge) {
        if (minimumCharge != null && charge.compareTo(minimumCharge) < 0) {
            charge = minimumCharge;
        }
        if (maximumCharge != null && charge.compareTo(maximumCharge) > 0) {
            charge = maximumCharge;
        }
        return charge;
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal) return (BigDecimal) value;
        if (value instanceof Number) return new BigDecimal(value.toString());
        return new BigDecimal(value.toString());
    }

    /**
     * Check if charge should be waived.
     */
    public boolean shouldWaive(BigDecimal amount, boolean isVip, boolean isBulk) {
        if (waiverThreshold != null && amount.compareTo(waiverThreshold) >= 0) {
            return true;
        }
        if (Boolean.TRUE.equals(waiverForVip) && isVip) {
            return true;
        }
        if (Boolean.TRUE.equals(waiverForBulk) && isBulk) {
            return true;
        }
        return false;
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    public boolean isActive() {
        return status == ChargeStatus.ACTIVE
            && (effectiveTo == null || !LocalDate.now().isAfter(effectiveTo));
    }

    public boolean appliesTo(Payable.PaymentMethod method, Payable.PaymentPriority priority) {
        if (appliesToPaymentMethod != null && appliesToPaymentMethod != method) {
            return false;
        }
        if (appliesToPriority != null && appliesToPriority != priority) {
            return false;
        }
        return true;
    }

    // ========================================================================
    // FACTORY METHODS
    // ========================================================================

    public static ChargeConfiguration createSwiftFee(BigDecimal amount) {
        return ChargeConfiguration.builder()
            .chargeCode("SWIFT_STD")
            .chargeName("SWIFT Transfer Fee")
            .chargeType(ChargeType.SWIFT_FEE)
            .chargeCategory(ChargeCategory.FIXED)
            .fixedAmount(amount)
            .isCrossBorder(true)
            .build();
    }

    public static ChargeConfiguration createProcessingFee(BigDecimal rate) {
        return ChargeConfiguration.builder()
            .chargeCode("PROC_STD")
            .chargeName("Processing Fee")
            .chargeType(ChargeType.PROCESSING_FEE)
            .chargeCategory(ChargeCategory.PERCENTAGE)
            .percentageRate(rate)
            .minimumCharge(new BigDecimal("10"))
            .maximumCharge(new BigDecimal("500"))
            .build();
    }

    public static ChargeConfiguration createUrgencyFee(Payable.PaymentPriority priority, 
                                                        BigDecimal amount) {
        return ChargeConfiguration.builder()
            .chargeCode("URG_" + priority.name())
            .chargeName(priority.name() + " Priority Fee")
            .chargeType(ChargeType.URGENCY_FEE)
            .chargeCategory(ChargeCategory.FIXED)
            .fixedAmount(amount)
            .appliesToPriority(priority)
            .build();
    }

    public static ChargeConfiguration createPoboFee(BigDecimal rate) {
        return ChargeConfiguration.builder()
            .chargeCode("SVC_POBO")
            .chargeName("POBO Service Fee")
            .chargeType(ChargeType.POBO_FEE)
            .chargeCategory(ChargeCategory.PERCENTAGE)
            .percentageRate(rate)
            .minimumCharge(new BigDecimal("25"))
            .maximumCharge(new BigDecimal("1000"))
            .build();
    }
}