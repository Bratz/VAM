package com.bank.vam.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Program Entity - Unified configuration for VA Programs, Wallet Programs, etc.
 * 
 * Enhanced with:
 * - 7-level hierarchy support
 * - Multi-VIBAN pool management
 * - Balance aggregation settings
 * - Loyalty/Gift Card/Corporate Card program types
 * 
 * When programType = WALLET or walletEnabled = true:
 * - Wallet-specific limits apply (daily, monthly, max balance)
 * - KYC requirements
 * - Topup/withdrawal settings
 * - Fee configuration
 * 
 * Domain Model:
 * - Corporate (1) -> Program (N)
 * - Program (1) -> VirtualAccount (N)
 * - PhysicalAccount (1) -> Program (N)
 * - Program (1) -> HierarchyNode (Root)
 * - Program (1) -> VibanPool (N)
 */
@Entity
@Table(name = "unified_programs", indexes = {
    @Index(name = "idx_program_code", columnList = "program_code"),
    @Index(name = "idx_program_corporate", columnList = "corporate_id"),
    @Index(name = "idx_program_type", columnList = "program_type"),
    @Index(name = "idx_program_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Program extends BaseEntity {

    // ========================================================================
    // CORE PROGRAM FIELDS (existing)
    // ========================================================================

    @Column(name = "program_code", unique = true, nullable = false)
    private String programCode;

    @Column(name = "program_name", nullable = false)
    private String programName;

    @Enumerated(EnumType.STRING)
    @Column(name = "program_type", nullable = false)
    private ProgramType programType;

    @Column(name = "corporate_id", nullable = false)
    private UUID corporateId;

    @Column(name = "physical_account_id")
    private UUID physicalAccountId;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "va_prefix", length = 10)
    private String vaPrefix;

    @Column(name = "va_format")
    private String vaFormat;

    @Column(name = "max_virtual_accounts")
    private Integer maxVirtualAccounts;

    @Column(name = "current_va_count")
    @Builder.Default
    private Integer currentVaCount = 0;

    @Column(name = "auto_reconciliation")
    @Builder.Default
    private Boolean autoReconciliation = true;

    @Column(name = "settlement_frequency")
    private String settlementFrequency;

    @Column(name = "settlement_time")
    private LocalTime settlementTime;

    @Column(name = "min_balance_threshold", precision = 18, scale = 2)
    private BigDecimal minBalanceThreshold;

    @Column(name = "viban_enabled")
    @Builder.Default
    private Boolean vibanEnabled = false;

    @Column(name = "wallet_enabled")
    @Builder.Default
    private Boolean walletEnabled = false;

    @Column(name = "escrow_enabled")
    @Builder.Default
    private Boolean escrowEnabled = false;

    @Column(name = "ihb_enabled")
    @Builder.Default
    private Boolean ihbEnabled = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    @Builder.Default
    private ProgramStatus status = ProgramStatus.ACTIVE;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    // ========================================================================
    // HIERARCHY SUPPORT (NEW - Week 2)
    // ========================================================================

    /**
     * Whether hierarchy is enabled for this program.
     * Default: false (backward compatible)
     */
    @Column(name = "hierarchy_enabled")
    @Builder.Default
    private Boolean hierarchyEnabled = false;

    /**
     * Number of hierarchy levels to use.
     * Now configurable: default 7, max 50.
     *
     * CONFIGURABLE DEPTH (v5.3.0):
     * - Minimum: 2 (ROOT + at least one child level)
     * - Default: 7 (backward compatible)
     * - Maximum: 50 (for complex holding company structures)
     *
     * When moving subtrees between hierarchies, levels are recalculated dynamically.
     */
    @Column(name = "hierarchy_depth")
    @Builder.Default
    private Integer hierarchyDepth = 7;

    /**
     * Maximum allowed hierarchy depth for this program.
     * Can be higher than hierarchyDepth to allow future expansion.
     * Default: 20, Absolute max: 50.
     */
    @Column(name = "max_hierarchy_depth")
    @Builder.Default
    private Integer maxHierarchyDepth = 20;

    /**
     * Template used for hierarchy structure.
     * Examples: IHB_PROGRAM, COLLECTION_PROGRAM, WALLET_PROGRAM
     */
    @Column(name = "default_hierarchy_template", length = 50)
    private String defaultHierarchyTemplate;

    /**
     * Root hierarchy node ID for this program.
     */
    @Column(name = "root_hierarchy_node_id")
    private UUID rootHierarchyNodeId;

    // ========================================================================
    // VIBAN POOL SETTINGS (NEW - Week 2)
    // ========================================================================

    /**
     * Default VIBAN pool for this program.
     */
    @Column(name = "default_viban_pool_id")
    private UUID defaultVibanPoolId;

    /**
     * VIBAN generation strategy.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "viban_generation_strategy", length = 30)
    @Builder.Default
    private VibanGenerationStrategy vibanGenerationStrategy = VibanGenerationStrategy.SEQUENTIAL;

    /**
     * VIBAN prefix for this program.
     */
    @Column(name = "viban_prefix", length = 20)
    private String vibanPrefix;

    /**
     * Bank code for VIBAN generation.
     */
    @Column(name = "viban_bank_code", length = 10)
    private String vibanBankCode;

    // ========================================================================
    // BALANCE AGGREGATION SETTINGS (NEW - Week 2)
    // ========================================================================

    /**
     * Frequency of balance aggregation for higher levels.
     * Default: 5 minutes
     */
    @Column(name = "balance_aggregation_interval_minutes")
    @Builder.Default
    private Integer balanceAggregationIntervalMinutes = 5;

    /**
     * Whether to do real-time balance propagation for L7-L5.
     */
    @Column(name = "realtime_balance_propagation")
    @Builder.Default
    private Boolean realtimeBalancePropagation = true;

    // ========================================================================
    // ADDITIONAL PROGRAM TYPES (NEW - Week 2)
    // ========================================================================

    @Column(name = "loyalty_enabled")
    @Builder.Default
    private Boolean loyaltyEnabled = false;

    @Column(name = "gift_card_enabled")
    @Builder.Default
    private Boolean giftCardEnabled = false;

    @Column(name = "corporate_card_enabled")
    @Builder.Default
    private Boolean corporateCardEnabled = false;

    @Column(name = "mobile_money_enabled")
    @Builder.Default
    private Boolean mobileMoneyEnabled = false;

    // ========================================================================
    // WALLET CONFIGURATION (existing wallet fields)
    // ========================================================================

    /**
     * Default wallet type for VAs created under this program.
     * Values: CONSUMER, EMPLOYEE, MERCHANT, AGENT, CORPORATE, GIFT
     */
    @Column(name = "default_wallet_type", length = 20)
    private String defaultWalletType;

    // ========================================================================
    // WALLET DEFAULT LIMITS (inherited by wallets)
    // ========================================================================

    @Column(name = "default_per_txn_limit", precision = 18, scale = 2)
    private BigDecimal defaultPerTransactionLimit;

    @Column(name = "default_daily_limit", precision = 18, scale = 2)
    private BigDecimal defaultDailyLimit;

    @Column(name = "default_weekly_limit", precision = 18, scale = 2)
    private BigDecimal defaultWeeklyLimit;

    @Column(name = "default_monthly_limit", precision = 18, scale = 2)
    private BigDecimal defaultMonthlyLimit;

    @Column(name = "default_yearly_limit", precision = 18, scale = 2)
    private BigDecimal defaultYearlyLimit;

    @Column(name = "default_max_balance", precision = 18, scale = 2)
    private BigDecimal defaultMaxBalance;

    // ========================================================================
    // WALLET TOPUP LIMITS
    // ========================================================================

    @Column(name = "min_topup", precision = 18, scale = 2)
    private BigDecimal minTopup;

    @Column(name = "max_topup", precision = 18, scale = 2)
    private BigDecimal maxTopup;

    @Column(name = "default_daily_topup_limit", precision = 18, scale = 2)
    private BigDecimal defaultDailyTopupLimit;

    @Column(name = "default_monthly_topup_limit", precision = 18, scale = 2)
    private BigDecimal defaultMonthlyTopupLimit;

    // ========================================================================
    // WALLET WITHDRAWAL LIMITS
    // ========================================================================

    @Column(name = "min_withdrawal", precision = 18, scale = 2)
    private BigDecimal minWithdrawal;

    @Column(name = "max_withdrawal", precision = 18, scale = 2)
    private BigDecimal maxWithdrawal;

    // ========================================================================
    // WALLET KYC REQUIREMENTS
    // ========================================================================

    @Column(name = "kyc_required")
    @Builder.Default
    private Boolean kycRequired = false;

    @Column(name = "auto_kyc")
    @Builder.Default
    private Boolean autoKyc = false;

    @Column(name = "min_kyc_level")
    @Builder.Default
    private Integer minKycLevel = 0;

    @Column(name = "kyc_validity_days")
    private Integer kycValidityDays;

    // ========================================================================
    // WALLET FEATURES
    // ========================================================================

    @Column(name = "allow_topup")
    @Builder.Default
    private Boolean allowTopup = true;

    @Column(name = "allow_withdrawal")
    @Builder.Default
    private Boolean allowWithdrawal = true;

    @Column(name = "allow_transfer")
    @Builder.Default
    private Boolean allowTransfer = true;

    @Column(name = "allow_payment")
    @Builder.Default
    private Boolean allowPayment = true;

    @Column(name = "allow_bulk_operations")
    @Builder.Default
    private Boolean allowBulkOperations = true;

    // ========================================================================
    // WALLET EXPIRY
    // ========================================================================

    @Column(name = "wallet_expiry_days")
    private Integer walletExpiryDays;

    @Column(name = "inactive_expiry_days")
    private Integer inactiveExpiryDays;

    // ========================================================================
    // WALLET FEES
    // ========================================================================

    @Column(name = "issuance_fee", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal issuanceFee = BigDecimal.ZERO;

    @Column(name = "monthly_fee", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal monthlyFee = BigDecimal.ZERO;

    @Column(name = "topup_fee_percent", precision = 8, scale = 4)
    @Builder.Default
    private BigDecimal topupFeePercent = BigDecimal.ZERO;

    @Column(name = "topup_fee_flat", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal topupFeeFlat = BigDecimal.ZERO;

    @Column(name = "withdrawal_fee_percent", precision = 8, scale = 4)
    @Builder.Default
    private BigDecimal withdrawalFeePercent = BigDecimal.ZERO;

    @Column(name = "withdrawal_fee_flat", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal withdrawalFeeFlat = BigDecimal.ZERO;

    @Column(name = "transfer_fee_percent", precision = 8, scale = 4)
    @Builder.Default
    private BigDecimal transferFeePercent = BigDecimal.ZERO;

    @Column(name = "transfer_fee_flat", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal transferFeeFlat = BigDecimal.ZERO;

    // ========================================================================
    // BRANDING (for wallet programs)
    // ========================================================================

    @Column(name = "brand_name")
    private String brandName;

    @Column(name = "brand_logo_url")
    private String brandLogoUrl;

    // ========================================================================
    // ENUMS (extended for new program types)
    // ========================================================================

    public enum ProgramType {
        COLLECTION,      // General collections
        VIBAN,           // VIBAN-based collections
        ESCROW,          // Escrow/milestone
        WALLET,          // Wallet/prepaid
        IHB,             // In-house bank
        PAYABLES,        // Payables management
        RECEIVABLES,     // Receivables management
        LOYALTY,         // Loyalty/rewards
        GIFT_CARD,       // Gift cards
        CORPORATE_CARD,  // Corporate cards
        MOBILE_MONEY     // Mobile money/agent
    }

    public enum ProgramStatus {
        ACTIVE, INACTIVE, SUSPENDED, PENDING_APPROVAL, CLOSED
    }

    public enum VibanGenerationStrategy {
        SEQUENTIAL,        // Sequential numbering
        RANDOM,            // Random generation
        HIERARCHY_ENCODED  // Encodes hierarchy path in VIBAN
    }

    // ========================================================================
    // TEMPLATE CONSTANTS
    // ========================================================================

    public static final String TEMPLATE_IHB = "IHB_PROGRAM";
    public static final String TEMPLATE_COLLECTION = "COLLECTION_PROGRAM";
    public static final String TEMPLATE_WALLET = "WALLET_PROGRAM";
    public static final String TEMPLATE_ESCROW = "ESCROW_PROGRAM";
    public static final String TEMPLATE_LOYALTY = "LOYALTY_PROGRAM";
    public static final String TEMPLATE_GIFT_CARD = "GIFT_CARD_PROGRAM";
    public static final String TEMPLATE_CORPORATE_CARD = "CORPORATE_CARD_PROGRAM";
    public static final String TEMPLATE_MOBILE_MONEY = "MOBILE_MONEY_PROGRAM";

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    /**
     * Check if program is active.
     */
    public boolean isActive() {
        return status == ProgramStatus.ACTIVE;
    }

    /**
     * Check if this is a wallet program.
     */
    public boolean isWalletProgram() {
        return programType == ProgramType.WALLET || Boolean.TRUE.equals(walletEnabled);
    }

    /**
     * Check if hierarchy is enabled and configured.
     */
    public boolean hasHierarchy() {
        return Boolean.TRUE.equals(hierarchyEnabled) && rootHierarchyNodeId != null;
    }

    /**
     * Check if program uses VIBAN pools.
     */
    public boolean hasVibanPool() {
        return defaultVibanPoolId != null;
    }

    /**
     * Check if program can issue new wallets/VAs.
     */
    public boolean canIssueWallet() {
        if (status != ProgramStatus.ACTIVE) return false;
        if (maxVirtualAccounts != null && currentVaCount != null && currentVaCount >= maxVirtualAccounts) return false;
        if (effectiveTo != null && LocalDate.now().isAfter(effectiveTo)) return false;
        return true;
    }

    /**
     * Check if within effective date range.
     */
    public boolean isWithinEffectiveDates() {
        LocalDate today = LocalDate.now();
        if (effectiveFrom != null && today.isBefore(effectiveFrom)) {
            return false;
        }
        if (effectiveTo != null && today.isAfter(effectiveTo)) {
            return false;
        }
        return true;
    }

    /**
     * Alias for backward compatibility.
     */
    public boolean isEffective() {
        return isWithinEffectiveDates();
    }

    /**
     * Increment VA count.
     */
    public void incrementVaCount() {
        this.currentVaCount = (this.currentVaCount != null ? this.currentVaCount : 0) + 1;
    }

    /**
     * Decrement VA count.
     */
    public void decrementVaCount() {
        if (this.currentVaCount != null && this.currentVaCount > 0) {
            this.currentVaCount--;
        }
    }

    /**
     * Check if program supports a specific feature.
     */
    public boolean supportsFeature(String feature) {
        return switch (feature.toUpperCase()) {
            case "VIBAN" -> Boolean.TRUE.equals(vibanEnabled);
            case "WALLET" -> Boolean.TRUE.equals(walletEnabled);
            case "ESCROW" -> Boolean.TRUE.equals(escrowEnabled);
            case "IHB" -> Boolean.TRUE.equals(ihbEnabled);
            case "HIERARCHY" -> Boolean.TRUE.equals(hierarchyEnabled);
            case "LOYALTY" -> Boolean.TRUE.equals(loyaltyEnabled);
            case "GIFT_CARD" -> Boolean.TRUE.equals(giftCardEnabled);
            case "CORPORATE_CARD" -> Boolean.TRUE.equals(corporateCardEnabled);
            case "MOBILE_MONEY" -> Boolean.TRUE.equals(mobileMoneyEnabled);
            default -> false;
        };
    }

    /**
     * Get appropriate template based on program type.
     */
    public String getDefaultTemplate() {
        if (defaultHierarchyTemplate != null) {
            return defaultHierarchyTemplate;
        }
        return switch (programType) {
            case IHB -> TEMPLATE_IHB;
            case COLLECTION, VIBAN, RECEIVABLES -> TEMPLATE_COLLECTION;
            case WALLET -> TEMPLATE_WALLET;
            case ESCROW -> TEMPLATE_ESCROW;
            case LOYALTY -> TEMPLATE_LOYALTY;
            case GIFT_CARD -> TEMPLATE_GIFT_CARD;
            case CORPORATE_CARD -> TEMPLATE_CORPORATE_CARD;
            case MOBILE_MONEY -> TEMPLATE_MOBILE_MONEY;
            default -> TEMPLATE_COLLECTION;
        };
    }

    /**
     * Calculate wallet expiry date based on program config.
     */
    public LocalDate calculateWalletExpiryDate() {
        if (walletExpiryDays == null) return null;
        return LocalDate.now().plusDays(walletExpiryDays);
    }

    /**
     * Calculate KYC expiry date.
     */
    public LocalDate calculateKycExpiryDate() {
        if (kycValidityDays == null) return null;
        return LocalDate.now().plusDays(kycValidityDays);
    }

    /**
     * Calculate topup fee.
     */
    public BigDecimal calculateTopupFee(BigDecimal amount) {
        BigDecimal fee = BigDecimal.ZERO;
        if (topupFeePercent != null && topupFeePercent.compareTo(BigDecimal.ZERO) > 0) {
            fee = fee.add(amount.multiply(topupFeePercent).divide(BigDecimal.valueOf(100)));
        }
        if (topupFeeFlat != null) {
            fee = fee.add(topupFeeFlat);
        }
        return fee;
    }

    /**
     * Calculate withdrawal fee.
     */
    public BigDecimal calculateWithdrawalFee(BigDecimal amount) {
        BigDecimal fee = BigDecimal.ZERO;
        if (withdrawalFeePercent != null && withdrawalFeePercent.compareTo(BigDecimal.ZERO) > 0) {
            fee = fee.add(amount.multiply(withdrawalFeePercent).divide(BigDecimal.valueOf(100)));
        }
        if (withdrawalFeeFlat != null) {
            fee = fee.add(withdrawalFeeFlat);
        }
        return fee;
    }

    /**
     * Calculate transfer fee.
     */
    public BigDecimal calculateTransferFee(BigDecimal amount) {
        BigDecimal fee = BigDecimal.ZERO;
        if (transferFeePercent != null && transferFeePercent.compareTo(BigDecimal.ZERO) > 0) {
            fee = fee.add(amount.multiply(transferFeePercent).divide(BigDecimal.valueOf(100)));
        }
        if (transferFeeFlat != null) {
            fee = fee.add(transferFeeFlat);
        }
        return fee;
    }
}