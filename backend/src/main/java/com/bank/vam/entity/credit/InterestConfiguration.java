package com.bank.vam.entity.credit;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Interest Configuration - Interest rate settings for accounts.
 * 
 * UNIFIED ARCHITECTURE v4.2:
 * Interest configurations define the rates for:
 * - Credit interest (on positive balances)
 * - Debit interest (on overdrafts/negative balances)
 * 
 * Like Credit Limits, there are two layers:
 * 1. EXTERNAL rates - From CBS (what bank charges/pays)
 * 2. INTERNAL rates - Treasury transfer pricing rates
 * 
 * The effective rate used for a VA considers both:
 * - External rate is what flows to/from the bank
 * - Internal rate is for intercompany settlement
 * - Spread between them is treasury's value capture
 * 
 * Domain Model:
 * - VirtualAccount.externalInterestConfigId -> InterestConfiguration.id
 * - VirtualAccount.internalInterestConfigId -> InterestConfiguration.id
 */
@Entity
@Table(name = "interest_configurations",
    indexes = {
        @Index(name = "idx_ic_corporate", columnList = "corporate_id"),
        @Index(name = "idx_ic_target", columnList = "target_id"),
        @Index(name = "idx_ic_type", columnList = "config_type"),
        @Index(name = "idx_ic_currency", columnList = "currency_code"),
        @Index(name = "idx_ic_status", columnList = "status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InterestConfiguration extends BaseEntity {

    // ========================================================================
    // IDENTITY
    // ========================================================================

    /**
     * Corporate that owns this configuration.
     */
    @Column(name = "corporate_id", nullable = false)
    private UUID corporateId;

    /**
     * Configuration name.
     */
    @Column(name = "config_name", length = 100)
    private String configName;

    /**
     * External reference from CBS.
     */
    @Column(name = "external_reference", length = 50)
    private String externalReference;

    // ========================================================================
    // CONFIGURATION TYPE
    // ========================================================================

    /**
     * Type: EXTERNAL (from bank) or INTERNAL (treasury).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "config_type", nullable = false, length = 20)
    private ConfigType configType;

    /**
     * Target this config applies to.
     */
    @Column(name = "target_id")
    private UUID targetId;

    /**
     * Target type (VA, Entity, Program, etc.).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", length = 30)
    private TargetType targetType;

    /**
     * Currency this config applies to.
     */
    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    // ========================================================================
    // CREDIT INTEREST (on positive balances)
    // ========================================================================

    /**
     * Base rate type for credit interest.
     */
    @Column(name = "credit_base_rate_type", length = 20)
    private String creditBaseRateType;

    /**
     * Current base rate value.
     */
    @Column(name = "credit_base_rate", precision = 8, scale = 5)
    private BigDecimal creditBaseRate;

    /**
     * Spread over base rate (can be negative).
     */
    @Column(name = "credit_spread", precision = 8, scale = 5)
    private BigDecimal creditSpread;

    /**
     * Effective credit rate (base + spread).
     */
    @Column(name = "effective_credit_rate", precision = 8, scale = 5)
    private BigDecimal effectiveCreditRate;

    /**
     * Minimum balance to earn credit interest.
     */
    @Column(name = "credit_min_balance", precision = 19, scale = 4)
    private BigDecimal creditMinBalance;

    // ========================================================================
    // DEBIT INTEREST (on negative/overdraft balances)
    // ========================================================================

    /**
     * Base rate type for debit interest.
     */
    @Column(name = "debit_base_rate_type", length = 20)
    private String debitBaseRateType;

    /**
     * Current base rate value.
     */
    @Column(name = "debit_base_rate", precision = 8, scale = 5)
    private BigDecimal debitBaseRate;

    /**
     * Spread over base rate.
     */
    @Column(name = "debit_spread", precision = 8, scale = 5)
    private BigDecimal debitSpread;

    /**
     * Effective debit rate (base + spread).
     */
    @Column(name = "effective_debit_rate", precision = 8, scale = 5)
    private BigDecimal effectiveDebitRate;

    /**
     * Penalty rate for unauthorized overdraft.
     */
    @Column(name = "penalty_rate", precision = 8, scale = 5)
    private BigDecimal penaltyRate;

    // ========================================================================
    // CALCULATION PARAMETERS
    // ========================================================================

    /**
     * Day count convention (ACT/360, ACT/365, 30/360, etc.).
     */
    @Column(name = "day_count_convention", length = 20)
    @Builder.Default
    private String dayCountConvention = "ACT/360";

    /**
     * Compounding frequency.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "compounding_frequency", length = 20)
    @Builder.Default
    private CompoundingFrequency compoundingFrequency = CompoundingFrequency.DAILY;

    /**
     * Interest calculation frequency.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "calculation_frequency", length = 20)
    @Builder.Default
    private CalculationFrequency calculationFrequency = CalculationFrequency.DAILY;

    /**
     * Interest posting frequency.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "posting_frequency", length = 20)
    @Builder.Default
    private PostingFrequency postingFrequency = PostingFrequency.MONTHLY;

    // ========================================================================
    // TIER-BASED RATES (optional)
    // ========================================================================

    /**
     * Whether tiered rates are used.
     */
    @Column(name = "is_tiered")
    @Builder.Default
    private Boolean isTiered = false;

    /**
     * Tier configuration as JSON.
     * Example: [{"min": 0, "max": 100000, "rate": 2.5}, {"min": 100000, "rate": 3.0}]
     */
    @Column(name = "tier_config", columnDefinition = "TEXT")
    private String tierConfig;

    // ========================================================================
    // VALIDITY
    // ========================================================================

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    // ========================================================================
    // STATUS
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    @Builder.Default
    private ConfigStatus status = ConfigStatus.ACTIVE;

    // ========================================================================
    // CBS SYNC
    // ========================================================================

    @Column(name = "last_sync_at")
    private java.time.LocalDateTime lastSyncAt;

    @Column(name = "source_system", length = 50)
    private String sourceSystem;

    // ========================================================================
    // ENUMS
    // ========================================================================

    public enum ConfigType {
        EXTERNAL,   // From CBS (bank rates)
        INTERNAL    // Treasury transfer pricing
    }

    public enum TargetType {
        VIRTUAL_ACCOUNT,
        PHYSICAL_ACCOUNT,
        LEGAL_ENTITY,
        PROGRAM,
        CURRENCY,       // Default for a currency
        CORPORATE       // Corporate-wide default
    }

    public enum CompoundingFrequency {
        SIMPLE,     // No compounding
        DAILY,
        WEEKLY,
        MONTHLY,
        QUARTERLY,
        SEMI_ANNUAL,
        ANNUAL
    }

    public enum CalculationFrequency {
        DAILY,
        WEEKLY,
        MONTHLY,
        QUARTERLY
    }

    public enum PostingFrequency {
        DAILY,
        WEEKLY,
        MONTHLY,
        QUARTERLY,
        SEMI_ANNUAL,
        ANNUAL
    }

    public enum ConfigStatus {
        DRAFT,
        PENDING_APPROVAL,
        ACTIVE,
        SUSPENDED,
        EXPIRED,
        CANCELLED
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    public boolean isActive() {
        return status == ConfigStatus.ACTIVE;
    }

    public boolean isExternal() {
        return configType == ConfigType.EXTERNAL;
    }

    public boolean isInternal() {
        return configType == ConfigType.INTERNAL;
    }

    public boolean isCurrentlyValid() {
        LocalDate today = LocalDate.now();
        if (effectiveFrom != null && today.isBefore(effectiveFrom)) {
            return false;
        }
        if (effectiveTo != null && today.isAfter(effectiveTo)) {
            return false;
        }
        return true;
    }

    public void calculateEffectiveRates() {
        // Calculate effective credit rate
        if (creditBaseRate != null) {
            BigDecimal spread = creditSpread != null ? creditSpread : BigDecimal.ZERO;
            this.effectiveCreditRate = creditBaseRate.add(spread);
        }
        
        // Calculate effective debit rate
        if (debitBaseRate != null) {
            BigDecimal spread = debitSpread != null ? debitSpread : BigDecimal.ZERO;
            this.effectiveDebitRate = debitBaseRate.add(spread);
        }
    }

    /**
     * Get day count basis for calculations.
     */
    public int getDayCountBasis() {
        if (dayCountConvention == null) return 360;
        if (dayCountConvention.contains("365")) return 365;
        if (dayCountConvention.contains("366")) return 366;
        return 360;
    }

    @PrePersist
    @PreUpdate
    private void preSave() {
        calculateEffectiveRates();
    }

    // ========================================================================
    // BUILDER FACTORY METHODS
    // ========================================================================

    /**
     * Create an external interest configuration.
     */
    public static InterestConfiguration createExternal(UUID corporateId, UUID targetId,
                                                        TargetType targetType, String currency,
                                                        BigDecimal creditRate, BigDecimal debitRate) {
        return InterestConfiguration.builder()
            .corporateId(corporateId)
            .configType(ConfigType.EXTERNAL)
            .targetId(targetId)
            .targetType(targetType)
            .currencyCode(currency)
            .effectiveCreditRate(creditRate)
            .effectiveDebitRate(debitRate)
            .status(ConfigStatus.ACTIVE)
            .effectiveFrom(LocalDate.now())
            .build();
    }

    /**
     * Create an internal (treasury) interest configuration.
     */
    public static InterestConfiguration createInternal(UUID corporateId, UUID targetId,
                                                         TargetType targetType, String currency,
                                                         BigDecimal creditRate, BigDecimal debitRate,
                                                         String baseRateType) {
        return InterestConfiguration.builder()
            .corporateId(corporateId)
            .configType(ConfigType.INTERNAL)
            .targetId(targetId)
            .targetType(targetType)
            .currencyCode(currency)
            .creditBaseRateType(baseRateType)
            .debitBaseRateType(baseRateType)
            .effectiveCreditRate(creditRate)
            .effectiveDebitRate(debitRate)
            .status(ConfigStatus.ACTIVE)
            .effectiveFrom(LocalDate.now())
            .build();
    }
}