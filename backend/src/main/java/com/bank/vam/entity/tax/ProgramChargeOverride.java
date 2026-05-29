package com.bank.vam.entity.tax;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Program Charge Override Entity - Program-level customization of charge configurations.
 * 
 * Allows programs to override standard charge rates defined in ChargeConfiguration.
 * This enables:
 * - Program-specific fee structures
 * - Corporate-negotiated rates
 * - Promotional pricing
 * - White-label wallet fee customization
 * 
 * Domain Model:
 * - Program (1) -> ProgramChargeOverride (N)
 * - ChargeConfiguration (1) -> ProgramChargeOverride (N)
 * 
 * Calculation Priority:
 * 1. Check for program-specific override (this entity)
 * 2. Fall back to base ChargeConfiguration
 * 
 * VAM Compliance:
 * - Transparent fee disclosure
 * - Audit trail for fee changes
 * - Effective date management
 */
@Entity
@Table(name = "program_charge_overrides", indexes = {
    @Index(name = "idx_pco_program", columnList = "program_id"),
    @Index(name = "idx_pco_charge_code", columnList = "charge_code"),
    @Index(name = "idx_pco_program_charge", columnList = "program_id, charge_code"),
    @Index(name = "idx_pco_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProgramChargeOverride extends BaseEntity {

    // ========================================================================
    // RELATIONSHIPS
    // ========================================================================

    @Column(name = "program_id", nullable = false)
    private UUID programId;

    @Column(name = "charge_code", nullable = false, length = 30)
    private String chargeCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "charge_config_id")
    private ChargeConfiguration chargeConfiguration;

    // ========================================================================
    // OVERRIDE VALUES
    // ========================================================================

    /**
     * Override percentage rate (null = use base config).
     * For PERCENTAGE and TIERED categories.
     */
    @Column(name = "override_percentage", precision = 8, scale = 4)
    private BigDecimal overridePercentage;

    /**
     * Override fixed amount (null = use base config).
     * For FIXED category or flat component.
     */
    @Column(name = "override_fixed", precision = 18, scale = 2)
    private BigDecimal overrideFixed;

    /**
     * Override minimum charge (null = use base config).
     */
    @Column(name = "override_minimum", precision = 18, scale = 2)
    private BigDecimal overrideMinimum;

    /**
     * Override maximum charge (null = use base config).
     */
    @Column(name = "override_maximum", precision = 18, scale = 2)
    private BigDecimal overrideMaximum;

    // ========================================================================
    // WAIVER SETTINGS
    // ========================================================================

    /**
     * If true, this charge is completely waived for this program.
     */
    @Column(name = "is_waived")
    @Builder.Default
    private Boolean isWaived = false;

    @Column(name = "waiver_reason", length = 200)
    private String waiverReason;

    @Column(name = "waiver_approved_by", length = 100)
    private String waiverApprovedBy;

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
    private OverrideStatus status = OverrideStatus.ACTIVE;

    // ========================================================================
    // AUDIT
    // ========================================================================

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    // ========================================================================
    // ENUMS
    // ========================================================================

    public enum OverrideStatus {
        ACTIVE,
        INACTIVE,
        PENDING_APPROVAL,
        EXPIRED
    }

    // ========================================================================
    // CALCULATION METHODS
    // ========================================================================

    /**
     * Calculate charge using override values, falling back to base config.
     */
    public BigDecimal calculateCharge(BigDecimal baseAmount, ChargeConfiguration baseConfig) {
        // If waived, return zero
        if (Boolean.TRUE.equals(isWaived)) {
            return BigDecimal.ZERO;
        }

        // Use base config calculation with override values
        BigDecimal charge;

        switch (baseConfig.getChargeCategory()) {
            case FIXED:
                charge = overrideFixed != null ? overrideFixed : baseConfig.getFixedAmount();
                break;

            case PERCENTAGE:
                BigDecimal rate = overridePercentage != null ? overridePercentage : baseConfig.getPercentageRate();
                charge = baseAmount.multiply(rate)
                    .divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
                break;

            case TIERED:
            case SLIDING_SCALE:
                // For tiered, use base config calculation (can't override tiers easily)
                charge = baseConfig.calculateCharge(baseAmount);
                // But apply percentage adjustment if set
                if (overridePercentage != null) {
                    BigDecimal adjustment = overridePercentage.divide(baseConfig.getPercentageRate(), 4, java.math.RoundingMode.HALF_UP);
                    charge = charge.multiply(adjustment);
                }
                break;

            default:
                // Combined: fixed + percentage
                BigDecimal fixed = overrideFixed != null ? overrideFixed : 
                    (baseConfig.getFixedAmount() != null ? baseConfig.getFixedAmount() : BigDecimal.ZERO);
                BigDecimal pctRate = overridePercentage != null ? overridePercentage : baseConfig.getPercentageRate();
                BigDecimal pct = baseAmount.multiply(pctRate)
                    .divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
                charge = fixed.add(pct);
        }

        // Apply override bounds
        return applyBounds(charge, baseConfig);
    }

    private BigDecimal applyBounds(BigDecimal charge, ChargeConfiguration baseConfig) {
        BigDecimal min = overrideMinimum != null ? overrideMinimum : baseConfig.getMinimumCharge();
        BigDecimal max = overrideMaximum != null ? overrideMaximum : baseConfig.getMaximumCharge();

        if (min != null && charge.compareTo(min) < 0) {
            charge = min;
        }
        if (max != null && charge.compareTo(max) > 0) {
            charge = max;
        }
        return charge;
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    public boolean isActive() {
        return status == OverrideStatus.ACTIVE
            && (effectiveTo == null || !LocalDate.now().isAfter(effectiveTo));
    }

    public boolean hasOverrides() {
        return overridePercentage != null 
            || overrideFixed != null 
            || overrideMinimum != null 
            || overrideMaximum != null
            || Boolean.TRUE.equals(isWaived);
    }

    // ========================================================================
    // FACTORY METHODS
    // ========================================================================

    /**
     * Create a full waiver override.
     */
    public static ProgramChargeOverride createWaiver(UUID programId, String chargeCode, 
                                                      String reason, String approvedBy) {
        return ProgramChargeOverride.builder()
            .programId(programId)
            .chargeCode(chargeCode)
            .isWaived(true)
            .waiverReason(reason)
            .waiverApprovedBy(approvedBy)
            .build();
    }

    /**
     * Create a percentage override.
     */
    public static ProgramChargeOverride createPercentageOverride(UUID programId, String chargeCode,
                                                                  BigDecimal newPercentage) {
        return ProgramChargeOverride.builder()
            .programId(programId)
            .chargeCode(chargeCode)
            .overridePercentage(newPercentage)
            .build();
    }

    /**
     * Create a fixed amount override.
     */
    public static ProgramChargeOverride createFixedOverride(UUID programId, String chargeCode,
                                                             BigDecimal newFixed) {
        return ProgramChargeOverride.builder()
            .programId(programId)
            .chargeCode(chargeCode)
            .overrideFixed(newFixed)
            .build();
    }
}