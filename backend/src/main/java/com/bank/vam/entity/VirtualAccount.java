package com.bank.vam.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Virtual Account entity - Enhanced with hierarchy and multi-VIBAN support.
 * 
 * UNIFIED ARCHITECTURE v4.2:
 * - Standard VA operations (collections, payables)
 * - Wallet operations (topup, withdrawal, P2P)
 * - Hierarchy integration (7-level structure)
 * - Multi-VIBAN support (1:N relationship)
 * - Card programs (limits, MCC restrictions)
 * - Loyalty programs (points, tiers)
 * - Shadow Accounts (PHYSICAL_MIRROR of CBS accounts)
 * - Currency Mirrors (multi-currency aggregation with FX)
 * - Credit Limits (External CBS + Internal CFO)
 * - Legal Entity ownership
 * 
 * Backward compatible: All new fields have defaults or are nullable.
 * Existing VAs continue to work without hierarchy (hierarchyNodeId = null).
 * 
 * Domain Model:
 * - Corporate (1) -> VirtualAccount (N)
 * - Program (1) -> VirtualAccount (N)
 * - PhysicalAccount (1) -> VirtualAccount (N)
 * - VirtualAccount (1) -> Viban (N) [1:N relationship]
 * - HierarchyNode (1) -> VirtualAccount (1) [L7 node linkage]
 * - VirtualAccount (1) -> VirtualAccount (N) [Self-referencing hierarchy]
 * - LegalEntity (1) -> VirtualAccount (N) [Ownership]
 */
@Entity
@Table(name = "virtual_accounts", indexes = {
    @Index(name = "idx_va_corporate", columnList = "corporate_id"),
    @Index(name = "idx_va_program", columnList = "program_id"),
    @Index(name = "idx_va_physical", columnList = "physical_account_id"),
    @Index(name = "idx_va_status", columnList = "status"),
    @Index(name = "idx_va_currency", columnList = "currency_code"),
    // Hierarchy and VIBAN indexes
    @Index(name = "idx_va_hierarchy_node", columnList = "hierarchy_node_id"),
    @Index(name = "idx_va_primary_viban", columnList = "primary_viban_id"),
    @Index(name = "idx_va_collection_channel", columnList = "collection_channel"),
    @Index(name = "idx_va_value_type", columnList = "value_type"),
    @Index(name = "idx_va_wallet_type", columnList = "wallet_type"),
    @Index(name = "idx_va_special_type", columnList = "special_type"),
    // NEW: Unified architecture indexes
    @Index(name = "idx_va_account_type", columnList = "account_type"),
    @Index(name = "idx_va_account_category", columnList = "account_category"),
    @Index(name = "idx_va_parent_account", columnList = "parent_account_id"),
    @Index(name = "idx_va_owning_entity", columnList = "owning_entity_id"),
    @Index(name = "idx_va_base_currency", columnList = "base_currency"),
    @Index(name = "idx_va_linked_physical", columnList = "linked_physical_account_id"),
    @Index(name = "idx_va_hierarchy_path_va", columnList = "hierarchy_path_va"),
    @Index(name = "idx_va_bank_account_number", columnList = "bank_account_number")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VirtualAccount extends BaseEntity {

    // ========================================================================
    // CORE FIELDS (existing)
    // ========================================================================

    @Column(name = "va_number", unique = true, nullable = false)
    private String vaNumber;

    /**
     * Legacy VIBAN field - kept for backward compatibility.
     * New VIBANs should use the vibans table with 1:N relationship.
     */
    @Column(name = "viban", unique = true)
    private String viban;

    @Column(name = "va_name", nullable = false)
    private String vaName;

    /**
     * Program ID linking this VA to a product offering.
     *
     * Programs define fee structures, interest rates, limits, and enabled features.
     * Each program type (IHB, COLLECTION, WALLET, ESCROW) has its own aggregation
     * and transaction VAs that belong to that program.
     *
     * <b>When programId SHOULD be set:</b>
     * <ul>
     *   <li>IHB Current Accounts (ihbParticipant=true) → IHB program</li>
     *   <li>IHB Treasury Settlement VA (AGGREGATION) → IHB program (aggregates IHB accounts)</li>
     *   <li>Wallet VAs → WALLET program</li>
     *   <li>Collection VAs and ROOT → COLLECTION/VIBAN program</li>
     *   <li>Escrow VAs and Pool VA → ESCROW program</li>
     *   <li>Any VA that belongs to a specific product offering, including aggregation VAs</li>
     * </ul>
     *
     * <b>When programId should be NULL (bank-level system accounts only):</b>
     * <ul>
     *   <li>SETTLEMENT VA - bank's contra account for fees from ANY program</li>
     *   <li>EXCEPTION VA - suspense for unmatched transactions from ANY program</li>
     *   <li>Physical Mirror VAs - shadows real bank accounts, not product-specific</li>
     * </ul>
     *
     * Key principle: If a VA aggregates or participates in a specific program's
     * operations, it should have that programId. Only truly cross-program bank-level
     * system accounts should have null programId.
     */
    @Column(name = "program_id")
    private UUID programId;

    @Column(name = "corporate_id", nullable = false)
    private UUID corporateId;

    @Column(name = "physical_account_id", nullable = false)
    private UUID physicalAccountId;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "current_balance", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal currentBalance = BigDecimal.ZERO;

    @Column(name = "available_balance", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal availableBalance = BigDecimal.ZERO;

    // ========================================================================
    // IHB COMMITTED BALANCES (Option B Architecture)
    // ========================================================================

    /**
     * Committed outflow - funds committed to IHB deposits awaiting settlement.
     * This amount is "spoken for" and reduces available balance.
     * Reset to zero after EOD settlement.
     */
    @Column(name = "committed_outflow", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal committedOutflow = BigDecimal.ZERO;

    /**
     * Committed inflow - funds committed from IHB loans awaiting settlement.
     * Expected incoming funds (deficit funding).
     * Reset to zero after EOD settlement.
     */
    @Column(name = "committed_inflow", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal committedInflow = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    @Builder.Default
    private VaStatus status = VaStatus.ACTIVE;

    @Column(name = "external_reference")
    private String externalReference;

    @Column(name = "kyc_verified")
    @Builder.Default
    private Boolean kycVerified = false;

    @Column(name = "wallet_type", length = 20)
    private String walletType;

    @Column(name = "metadata", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String metadata;

    // ========================================================================
    // UNIFIED ARCHITECTURE: ACCOUNT CLASSIFICATION (NEW - Phase 4.2)
    // ========================================================================

    /**
     * Account Type - REAL (shadow of physical) or VIRTUAL.
     * REAL accounts mirror actual bank accounts from CBS.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", length = 20)
    @Builder.Default
    private AccountType accountType = AccountType.VIRTUAL;

    /**
     * Account Category - The role/purpose of this account in hierarchy.
     * Determines behavior, balance calculation, and UI display.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "account_category", length = 30)
    @Builder.Default
    private AccountCategory accountCategory = AccountCategory.TRANSACTION;

    // ========================================================================
    // UNIFIED ARCHITECTURE: SELF-REFERENCING HIERARCHY (NEW - Phase 4.2)
    // ========================================================================

    /**
     * Parent account ID for self-referencing tree structure.
     * ROOT nodes have null parentAccountId.
     */
    @Column(name = "parent_account_id")
    private UUID parentAccountId;

    /**
     * Level in the hierarchy (0 = ROOT, 1 = Shadow, 2 = Mirror, etc.).
     */
    @Column(name = "hierarchy_level")
    @Builder.Default
    private Integer hierarchyLevel = 0;

    /**
     * Materialized path for efficient tree queries.
     * Example: /ROOT/SHADOW-EUR/MIRROR-EUR/VA-001
     */
    @Column(name = "hierarchy_path_va", length = 1000)
    private String hierarchyPathVa;

    // ========================================================================
    // UNIFIED ARCHITECTURE: LEGAL ENTITY OWNERSHIP (NEW - Phase 4.2)
    // ========================================================================

    /**
     * Owning legal entity ID (subsidiary/branch).
     */
    @Column(name = "owning_entity_id")
    private UUID owningEntityId;

    /**
     * Denormalized entity code for display.
     */
    @Column(name = "owning_entity_code", length = 20)
    private String owningEntityCode;

    // ========================================================================
    // IHB (IN-HOUSE BANK) PARTICIPATION
    // ========================================================================

    /**
     * Whether this account participates in the In-House Bank scheme.
     *
     * When true:
     * - Interest accrues based on internalInterestConfigId rates
     * - Credit interest earned on positive balances
     * - Debit interest charged on negative/overdraft balances
     * - Account appears in IHB reporting and treasury dashboards
     *
     * Any TRANSACTION VA can be an IHB participant - no need for INTERCOMPANY category.
     * This allows operational accounts to also participate in treasury interest schemes.
     */
    @Column(name = "ihb_participant")
    @Builder.Default
    private Boolean ihbParticipant = false;

    /**
     * Date when IHB participation was enabled.
     */
    @Column(name = "ihb_enabled_at")
    private LocalDateTime ihbEnabledAt;

    /**
     * User/system that enabled IHB participation.
     */
    @Column(name = "ihb_enabled_by", length = 100)
    private String ihbEnabledBy;

    // ========================================================================
    // IHB SWEEP CONFIGURATION (Account-Level)
    // ========================================================================

    /**
     * Target cash balance for IHB sweeping.
     * 0 = sweep all surplus/deficit to Treasury Center.
     * >0 = maintain this balance, sweep only excess.
     */
    @Column(name = "target_cash_balance", precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal targetCashBalance = BigDecimal.ZERO;

    /**
     * Whether this account participates in IHB sweeping.
     */
    @Column(name = "ihb_sweep_enabled")
    @Builder.Default
    private Boolean ihbSweepEnabled = false;

    /**
     * IHB sweep frequency for this account.
     */
    @Column(name = "ihb_sweep_frequency", length = 20)
    private String ihbSweepFrequency;  // DAILY, REAL_TIME, WEEKLY

    // ========================================================================
    // UNIFIED ARCHITECTURE: MULTI-CURRENCY SUPPORT (NEW - Phase 4.2)
    // ========================================================================

    /**
     * Base currency for aggregation (e.g., EUR for European group).
     */
    @Column(name = "base_currency", length = 3)
    private String baseCurrency;

    /**
     * Aggregated balance from all children (in original currencies).
     * For ROOT/AGGREGATION nodes only.
     */
    @Column(name = "aggregated_balance", precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal aggregatedBalance = BigDecimal.ZERO;

    /**
     * Aggregated balance converted to base currency.
     */
    @Column(name = "aggregated_balance_base", precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal aggregatedBalanceBase = BigDecimal.ZERO;

    // ========================================================================
    // UNIFIED ARCHITECTURE: CURRENCY MIRROR FIELDS (NEW - Phase 4.2)
    // For accountCategory = CURRENCY_MIRROR
    // ========================================================================

    /**
     * Sum of balances in this currency from all children.
     */
    @Column(name = "mirror_balance", precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal mirrorBalance = BigDecimal.ZERO;

    /**
     * FX rate to convert to base currency.
     */
    @Column(name = "fx_rate", precision = 18, scale = 8)
    private BigDecimal fxRate;

    /**
     * When FX rate was last updated.
     */
    @Column(name = "fx_rate_at")
    private LocalDateTime fxRateAt;

    /**
     * Source of FX rate (REUTERS, BLOOMBERG, CBS, MANUAL).
     */
    @Column(name = "fx_rate_source", length = 50)
    private String fxRateSource;

    /**
     * Mirror balance converted to base currency.
     */
    @Column(name = "balance_in_base", precision = 19, scale = 4)
    private BigDecimal balanceInBase;

    // ========================================================================
    // UNIFIED ARCHITECTURE: SHADOW ACCOUNT FIELDS (NEW - Phase 4.2)
    // For accountCategory = PHYSICAL_MIRROR or EXTERNAL_MIRROR
    // ========================================================================

    /**
     * Link to the physical account this shadows.
     */
    @Column(name = "linked_physical_account_id")
    private UUID linkedPhysicalAccountId;

    /**
     * Current balance from CBS (real bank balance).
     */
    @Column(name = "bank_balance", precision = 19, scale = 4)
    private BigDecimal bankBalance;

    /**
     * Available balance from CBS.
     */
    @Column(name = "bank_available_balance", precision = 19, scale = 4)
    private BigDecimal bankAvailableBalance;

    /**
     * When bank balance was last synced.
     */
    @Column(name = "bank_balance_at")
    private LocalDateTime bankBalanceAt;

    /**
     * Bank account number (for display/matching).
     */
    @Column(name = "bank_account_number", length = 50)
    private String bankAccountNumber;

    /**
     * Bank IBAN.
     */
    @Column(name = "bank_iban", length = 34)
    private String bankIban;

    /**
     * Bank SWIFT/BIC code.
     */
    @Column(name = "bank_swift", length = 11)
    private String bankSwift;

    /**
     * Bank name (for display).
     */
    @Column(name = "bank_name", length = 100)
    private String bankName;

    /**
     * How balance data is sourced.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "balance_data_source", length = 30)
    private BalanceDataSource balanceDataSource;

    // ========================================================================
    // MULTI-BANK LIQUIDITY v1 — Shadow freshness + intraday float
    // For accountCategory = PHYSICAL_MIRROR
    // ========================================================================

    /**
     * Per-shadow override of the freshness threshold (minutes). When set,
     * overrides the system default {@code vam.multi-bank.default-freshness-threshold-minutes}.
     * Null = use system default.
     */
    @Column(name = "freshness_threshold_minutes")
    private Integer freshnessThresholdMinutes;

    /**
     * Intraday float — amount committed via outstanding sweep instructions that
     * have not yet been reflected on {@code bankBalance} by the external bank.
     * Increment on INSTRUCTED, decrement on terminal status (SETTLED/REJECTED/NACKED/EXPIRED).
     */
    @Column(name = "bank_balance_committed", precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal bankBalanceCommitted = BigDecimal.ZERO;

    /**
     * Wall-clock time of the most recent balance refresh attempt (success or failure).
     */
    @Column(name = "last_balance_refresh_at")
    private LocalDateTime lastBalanceRefreshAt;

    /**
     * Outcome of the most recent balance refresh attempt.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "last_balance_refresh_status", length = 20)
    private BalanceRefreshStatus lastBalanceRefreshStatus;

    // ========================================================================
    // UNIFIED ARCHITECTURE: CREDIT LIMIT FIELDS (NEW - Phase 4.2)
    // ========================================================================

    /**
     * External credit limit ID (from CBS via CreditFacility).
     */
    @Column(name = "external_limit_id")
    private UUID externalLimitId;

    /**
     * Internal credit limit ID (allocated by CFO).
     */
    @Column(name = "internal_limit_id")
    private UUID internalLimitId;

    /**
     * Effective combined credit limit.
     */
    @Column(name = "effective_credit_limit", precision = 19, scale = 4)
    private BigDecimal effectiveCreditLimit;

    /**
     * Amount of credit limit currently utilized.
     */
    @Column(name = "credit_limit_utilized", precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal creditLimitUtilized = BigDecimal.ZERO;

    /**
     * Available credit limit (effective - utilized).
     */
    @Column(name = "credit_limit_available", precision = 19, scale = 4)
    private BigDecimal creditLimitAvailable;

    // ========================================================================
    // UNIFIED ARCHITECTURE: INTEREST CONFIGURATION (NEW - Phase 4.2)
    // ========================================================================

    /**
     * External interest configuration ID (bank rates).
     */
    @Column(name = "external_interest_config_id")
    private UUID externalInterestConfigId;

    /**
     * Internal interest configuration ID (treasury rates).
     */
    @Column(name = "internal_interest_config_id")
    private UUID internalInterestConfigId;

    /**
     * Effective credit interest rate (for positive balances).
     */
    @Column(name = "effective_credit_rate", precision = 8, scale = 5)
    private BigDecimal effectiveCreditRate;

    /**
     * Effective debit interest rate (for overdrafts).
     */
    @Column(name = "effective_debit_rate", precision = 8, scale = 5)
    private BigDecimal effectiveDebitRate;

    /**
     * Penalty rate applied when overdraft limit is exceeded.
     */
    @Column(name = "penalty_rate", precision = 8, scale = 5)
    private BigDecimal penaltyRate;

    // ========================================================================
    // IHB CURRENT ACCOUNT - INTEREST ACCRUAL FIELDS
    // ========================================================================

    /**
     * Accrued credit interest (earned on positive balance).
     * Calculated daily on EOD balance, posted periodically (monthly).
     * Only applicable for INTERCOMPANY accounts functioning as IHB current accounts.
     */
    @Column(name = "accrued_credit_interest", precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal accruedCreditInterest = BigDecimal.ZERO;

    /**
     * Accrued debit interest (owed on negative/overdraft balance).
     * Calculated daily on EOD balance, posted periodically (monthly).
     */
    @Column(name = "accrued_debit_interest", precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal accruedDebitInterest = BigDecimal.ZERO;

    /**
     * Last date interest was calculated.
     */
    @Column(name = "last_interest_calc_date")
    private LocalDate lastInterestCalcDate;

    /**
     * Last date interest was posted to account balance.
     */
    @Column(name = "last_interest_posting_date")
    private LocalDate lastInterestPostingDate;

    /**
     * Treasury Pool VA that this IHB current account reports to.
     *
     * @deprecated Use {@link #parentAccountId} instead for VA hierarchy.
     * IHB Current Accounts now link to Treasury's Settlement VA via parentAccountId,
     * which enables proper multi-level treasury aggregation.
     *
     * This field is kept for backward compatibility with existing data.
     * New IHB accounts should use parentAccountId to link to Treasury's Settlement VA.
     */
    @Deprecated
    @Column(name = "treasury_pool_va_id")
    private UUID treasuryPoolVaId;

    // ========================================================================
    // IHB MIRROR ACCOUNT MODEL (Option A - 6-leg POBO)
    // ========================================================================

    /**
     * IHB Settlement VA ID - Sibling settlement VA for this IHB Current Account.
     *
     * Each IHB Current Account has a companion IHB Settlement VA that:
     * - Acts as the routing point for POBO payments
     * - Receives funds from IHB Current Account before routing to Treasury
     * - Enables per-subsidiary settlement tracking
     *
     * POBO flow: IHB Current Account → IHB Settlement VA → Treasury Settlement VA → CBS
     */
    @Column(name = "ihb_settlement_va_id")
    private UUID ihbSettlementVaId;

    /**
     * IC Receivable VA ID - Treasury's intercompany receivable VA for this subsidiary.
     *
     * For each subsidiary's IHB Current Account, Treasury has a corresponding
     * IC Receivable VA that:
     * - Tracks Treasury's financial claim on the subsidiary
     * - Mirrors the subsidiary's IHB Current Account balance (opposite sign)
     * - Enables proper intercompany accounting and reconciliation
     *
     * When subsidiary uses POBO: IC Receivable balance INCREASES (Treasury is owed more)
     * When subsidiary funds IHB: IC Receivable balance DECREASES (Treasury is owed less)
     */
    @Column(name = "ic_receivable_va_id")
    private UUID icReceivableVaId;

    /**
     * Mirror Account Type - Indicates the role of this VA in mirror accounting.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "mirror_account_type", length = 30)
    private MirrorAccountType mirrorAccountType;

    /**
     * Reference to the source VA that this mirrors (for IC_RECEIVABLE accounts).
     * Points to the subsidiary's IHB Current Account that this IC Receivable mirrors.
     */
    @Column(name = "mirrors_va_id")
    private UUID mirrorsVaId;

    /**
     * IC Payable VA ID - Treasury's intercompany payable VA for this subsidiary.
     *
     * For ROBO (Receive-On-Behalf-Of) collections where Treasury collects on behalf of a subsidiary,
     * this tracks Treasury's obligation to the subsidiary. When Treasury receives funds via ROBO,
     * the IC Payable VA balance INCREASES (Treasury owes more to subsidiary).
     *
     * Symmetric counterpart to icReceivableVaId for POBO flows.
     */
    @Column(name = "ic_payable_va_id")
    private UUID icPayableVaId;

    // ========================================================================
    // HIERARCHY INTEGRATION (existing - kept for backward compatibility)
    // ========================================================================

    /**
     * Link to L7 hierarchy node (legacy - use parentAccountId for new hierarchy).
     */
    @Column(name = "hierarchy_node_id")
    private UUID hierarchyNodeId;

    /**
     * Primary VIBAN ID from vibans table.
     */
    @Column(name = "primary_viban_id")
    private UUID primaryVibanId;

    /**
     * Denormalized hierarchy path (legacy).
     */
    @Column(name = "hierarchy_path", length = 500)
    private String hierarchyPath;

    /**
     * Collection channel for receivables routing.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "collection_channel", length = 20)
    private CollectionChannel collectionChannel;

    // ========================================================================
    // VALUE TYPE (for loyalty/rewards programs)
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", length = 20)
    @Builder.Default
    private ValueType valueType = ValueType.FIAT;

    @Column(name = "points_to_currency_rate", precision = 10, scale = 4)
    private BigDecimal pointsToCurrencyRate;

    // ========================================================================
    // SPENDING LIMITS (for card/wallet programs)
    // ========================================================================

    @Column(name = "per_transaction_limit", precision = 18, scale = 2)
    private BigDecimal perTransactionLimit;

    @Column(name = "daily_limit", precision = 18, scale = 2)
    private BigDecimal dailyLimit;

    @Column(name = "weekly_limit", precision = 18, scale = 2)
    private BigDecimal weeklyLimit;

    @Column(name = "monthly_limit", precision = 18, scale = 2)
    private BigDecimal monthlyLimit;

    @Column(name = "annual_limit", precision = 18, scale = 2)
    private BigDecimal annualLimit;

    @Column(name = "max_balance", precision = 18, scale = 2)
    private BigDecimal maxBalance;

    // Usage tracking
    @Column(name = "daily_used", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal dailyUsed = BigDecimal.ZERO;

    @Column(name = "weekly_used", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal weeklyUsed = BigDecimal.ZERO;

    @Column(name = "monthly_used", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal monthlyUsed = BigDecimal.ZERO;

    @Column(name = "annual_used", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal annualUsed = BigDecimal.ZERO;

    @Column(name = "last_limit_reset_date")
    private LocalDate lastLimitResetDate;

    // ========================================================================
    // TOPUP/WITHDRAWAL LIMITS (wallet-specific)
    // ========================================================================

    @Column(name = "daily_topup_limit", precision = 18, scale = 2)
    private BigDecimal dailyTopupLimit;

    @Column(name = "monthly_topup_limit", precision = 18, scale = 2)
    private BigDecimal monthlyTopupLimit;

    @Column(name = "daily_topup_used", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal dailyTopupUsed = BigDecimal.ZERO;

    @Column(name = "monthly_topup_used", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal monthlyTopupUsed = BigDecimal.ZERO;

    // ========================================================================
    // MCC RESTRICTIONS (for card programs)
    // ========================================================================

    @Column(name = "mcc_whitelist", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String mccWhitelist;

    @Column(name = "mcc_blacklist", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String mccBlacklist;

    @Column(name = "merchant_whitelist", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String merchantWhitelist;

    @Column(name = "country_whitelist", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String countryWhitelist;

    // ========================================================================
    // EXPIRY (for gift cards, loyalty points, wallets)
    // ========================================================================

    @Column(name = "balance_expiry_date")
    private LocalDate balanceExpiryDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "expiry_action", length = 20)
    @Builder.Default
    private ExpiryAction expiryAction = ExpiryAction.ZERO_BALANCE;

    @Column(name = "wallet_expiry_date")
    private LocalDate walletExpiryDate;

    @Column(name = "last_activity_date")
    private LocalDate lastActivityDate;

    // ========================================================================
    // LOYALTY TIER
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "loyalty_tier", length = 20)
    private LoyaltyTier loyaltyTier;

    @Column(name = "loyalty_program_id")
    private UUID loyaltyProgramId;

    @Column(name = "points_balance", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal pointsBalance = BigDecimal.ZERO;

    @Column(name = "pending_points", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal pendingPoints = BigDecimal.ZERO;

    @Column(name = "lifetime_points", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal lifetimePoints = BigDecimal.ZERO;

    // ========================================================================
    // CARD PROGRAM FIELDS
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "card_program_type", length = 30)
    private CardProgramType cardProgramType;

    @Column(name = "linked_card_id")
    private UUID linkedCardId;

    @Column(name = "budget_owner_id")
    private UUID budgetOwnerId;

    @Column(name = "cost_center", length = 50)
    private String costCenter;

    @Column(name = "department", length = 100)
    private String department;

    // ========================================================================
    // KYC FIELDS (wallet-specific)
    // ========================================================================

    @Column(name = "kyc_level")
    @Builder.Default
    private Integer kycLevel = 0;

    @Column(name = "kyc_expiry_date")
    private LocalDate kycExpiryDate;

    // ========================================================================
    // HELD BALANCE (for escrow/pending)
    // ========================================================================

    @Column(name = "held_balance", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal heldBalance = BigDecimal.ZERO;

    // ========================================================================
    // WALLET HOLDER FIELDS
    // ========================================================================

    @Column(name = "holder_party_id")
    private UUID holderPartyId;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @Column(name = "expires_at")
    private LocalDate expiresAt;

    @Column(name = "last_transaction_at")
    private LocalDateTime lastTransactionAt;

    @Column(name = "last_topup_at")
    private LocalDateTime lastTopupAt;

    @Column(name = "last_withdrawal_at")
    private LocalDateTime lastWithdrawalAt;

    @Column(name = "kyc_verified_at")
    private LocalDateTime kycVerifiedAt;

    @Column(name = "transaction_count")
    @Builder.Default
    private Integer transactionCount = 0;

    @Column(name = "topup_count")
    @Builder.Default
    private Integer topupCount = 0;

    @Column(name = "withdrawal_count")
    @Builder.Default
    private Integer withdrawalCount = 0;

    @Column(name = "suspension_reason")
    private String suspensionReason;

    @Column(name = "block_reason")
    private String blockReason;

    /**
     * Special type for internal treasury VAs.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "special_type", length = 20)
    @Builder.Default
    private VaSpecialType specialType = VaSpecialType.REGULAR;

    // ========================================================================
    // PUBLISH STATUS (Phase 1: Published/Unpublished VA)
    // ========================================================================

    /**
     * Publish status controls VIBAN assignment and external visibility.
     * - UNPUBLISHED (default): Internal-only, no VIBAN, cannot receive external payments
     * - PUBLISHED: Has VIBAN, can receive external payments via camt.054
     * - SUSPENDED: Previously published but temporarily disabled
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "publish_status", length = 20)
    @Builder.Default
    private PublishStatus publishStatus = PublishStatus.UNPUBLISHED;

    /**
     * Timestamp when the VA was published (VIBAN assigned).
     */
    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    /**
     * User/system that published the VA.
     */
    @Column(name = "published_by", length = 100)
    private String publishedBy;

    /**
     * Timestamp when the VA was unpublished/suspended.
     */
    @Column(name = "unpublished_at")
    private LocalDateTime unpublishedAt;

    /**
     * Reason for unpublishing/suspending.
     */
    @Column(name = "unpublish_reason")
    private String unpublishReason;

    // ========================================================================
    // ENUMS
    // ========================================================================

    public enum VaStatus {
        ACTIVE,
        INACTIVE,
        SUSPENDED,
        CLOSED,
        BLOCKED,
        PENDING_ACTIVATION,
        EXPIRED
    }

    public enum CollectionChannel {
        INVOICE, ECOMMERCE, POS, DIRECT, ESCROW, WALLET, SUBSCRIPTION, QR_CODE, AGENT
    }

    public enum ValueType {
        FIAT, POINTS, MILES, TOKENS, CRYPTO
    }

    public enum ExpiryAction {
        ZERO_BALANCE, FORFEIT, TRANSFER, EXTEND, NOTIFY
    }

    public enum LoyaltyTier {
        PLATINUM, GOLD, SILVER, BLUE, BASIC
    }

    public enum CardProgramType {
        TRAVEL, PROCUREMENT, FLEET, VIRTUAL, EXPENSE, PETTY_CASH
    }

    public enum VaSpecialType {
        REGULAR, SETTLEMENT, EXCEPTION, IC_PAYABLE, IC_RECEIVABLE
    }

    /**
     * Publish Status - Controls VIBAN assignment and external visibility.
     *
     * Phase 1: Published/Unpublished VAs for Payment Factory:
     * - UNPUBLISHED: Internal-only VA, no VIBAN, cannot receive external payments
     * - PUBLISHED: Has VIBAN assigned, can receive external payments
     * - SUSPENDED: Previously published but temporarily disabled
     */
    public enum PublishStatus {
        /** Internal-only VA, no VIBAN, cannot receive external payments */
        UNPUBLISHED,
        /** Has VIBAN assigned, can receive external payments */
        PUBLISHED,
        /** Previously published but temporarily disabled */
        SUSPENDED
    }

    // ========================================================================
    // NEW ENUMS FOR UNIFIED ARCHITECTURE (Phase 4.2)
    // ========================================================================

    /**
     * Account Type - Real (shadow of physical bank account) vs Virtual.
     */
    public enum AccountType {
        REAL,       // Physical bank account mirror (shadow from CBS)
        VIRTUAL     // Virtual account (internal)
    }

    /**
     * Account Category - The role/purpose of this account in the hierarchy.
     */
    public enum AccountCategory {
        // OPERATIONAL VAs (leaf nodes)
        TRANSACTION,        // General purpose transaction VA
        COLLECTION,         // Collections/Receivables VA
        DISBURSEMENT,       // Disbursements/Payables VA
        
        // SYSTEM VAs
        SETTLEMENT,         // Contra-entry for fees/charges
        EXCEPTION,          // Suspense for unmatched transactions
        SUSPENSE,           // General suspense
        
        // HIERARCHY VAs (structural nodes)
        ROOT,               // Root aggregation node
        AGGREGATION,        // Intermediate aggregation node
        PHYSICAL_MIRROR,    // Shadow of real bank account (from CBS)
        EXTERNAL_MIRROR,    // Mirror of external bank account
        CURRENCY_MIRROR,    // Currency aggregation node (for FX)
        
        // TREASURY VAs
        INTERCOMPANY,       // Intercompany settlement
        ESCROW,             // Escrow holding
        NETTING,            // Netting settlement
        POOL_HEADER,        // Notional pool header
        POOL_PARTICIPANT    // Notional pool member
    }

    /**
     * Balance Data Source - How balance is synchronized.
     */
    public enum BalanceDataSource {
        CORE_BANKING,   // Direct CBS integration
        SWIFT_MT940,    // SWIFT end-of-day statements
        SWIFT_MT942,    // SWIFT intraday statements
        OPEN_BANKING,   // Open Banking APIs
        HOST_TO_HOST,   // H2H file transfer
        API,            // External API
        MANUAL          // Manual entry
    }

    /**
     * Outcome of the most recent shadow balance refresh attempt.
     */
    public enum BalanceRefreshStatus {
        /** Refresh succeeded; bankBalance is current. */
        SUCCESS,
        /** Refresh attempted but failed (rail error, timeout, etc.). */
        FAILED,
        /** Last refresh older than freshness threshold; needs re-fetch. */
        STALE,
        /** No refresh has been attempted yet. */
        NEVER
    }

    /**
     * Mirror Account Type - Role in the IHB Mirror Account Model.
     *
     * Used for 6-leg POBO accounting where each subsidiary has:
     * - IHB Current Account (IHB_CURRENT) - Subsidiary's position at Treasury
     * - IHB Settlement VA (IHB_SETTLEMENT) - Per-subsidiary routing/settlement point
     * - IC Receivable VA at Treasury (IC_RECEIVABLE) - Treasury's claim on subsidiary
     */
    public enum MirrorAccountType {
        /** Subsidiary's IHB Current Account - the main IHB position account */
        IHB_CURRENT,
        /** Per-subsidiary settlement VA - routes payments to Treasury */
        IHB_SETTLEMENT,
        /** Treasury's IC Receivable - mirrors subsidiary's IHB balance */
        IC_RECEIVABLE,
        /** Treasury's omnibus settlement account */
        TREASURY_SETTLEMENT,
        /** Not a mirror account */
        NONE
    }

    // ========================================================================
    // UNIFIED ARCHITECTURE: ACCOUNT TYPE HELPERS (NEW)
    // ========================================================================

    /**
     * Check if this is a PHYSICAL_MIRROR (shadow of real bank account).
     */
    public boolean isPhysicalMirror() {
        return accountCategory == AccountCategory.PHYSICAL_MIRROR;
    }

    /**
     * Check if this PHYSICAL_MIRROR mirrors an account held at the home bank.
     * Home-bank-held mirrors are eligible for notional pool membership; external
     * shadows are not.
     *
     * @param homeBankBic the configured home-bank BIC (from HomeBankProperties)
     * @return true iff this is a PHYSICAL_MIRROR whose bankSwift equals homeBankBic
     */
    public boolean isHomeBankHeld(String homeBankBic) {
        if (!isPhysicalMirror() || homeBankBic == null || bankSwift == null) {
            return false;
        }
        return homeBankBic.equalsIgnoreCase(bankSwift);
    }

    /**
     * Effective bank balance available for sweep — accounts for intraday float
     * from outstanding sweep instructions not yet reflected on the bank statement.
     */
    public BigDecimal getBankBalanceEffective() {
        BigDecimal available = bankAvailableBalance != null ? bankAvailableBalance
                : (bankBalance != null ? bankBalance : BigDecimal.ZERO);
        BigDecimal committed = bankBalanceCommitted != null ? bankBalanceCommitted : BigDecimal.ZERO;
        return available.subtract(committed);
    }

    /**
     * Check if this is an EXTERNAL_MIRROR.
     */
    public boolean isExternalMirror() {
        return accountCategory == AccountCategory.EXTERNAL_MIRROR;
    }

    /**
     * Check if this is a CURRENCY_MIRROR (aggregates one currency).
     */
    public boolean isCurrencyMirror() {
        return accountCategory == AccountCategory.CURRENCY_MIRROR;
    }

    /**
     * Check if this is an aggregation node (ROOT, AGGREGATION, or CURRENCY_MIRROR).
     */
    public boolean isAggregationNode() {
        return accountCategory == AccountCategory.AGGREGATION || 
               accountCategory == AccountCategory.ROOT ||
               accountCategory == AccountCategory.CURRENCY_MIRROR;
    }

    /**
     * Check if this is the ROOT node.
     */
    public boolean isRootNode() {
        return accountCategory == AccountCategory.ROOT;
    }

    /**
     * Check if this is a leaf account (not an aggregation node).
     */
    public boolean isLeafAccount() {
        return !isAggregationNode() && !isPhysicalMirror() && !isCurrencyMirror();
    }

    /**
     * Check if this is an operational VA (TRANSACTION, COLLECTION, or DISBURSEMENT).
     */
    public boolean isOperationalVa() {
        return accountCategory == AccountCategory.TRANSACTION ||
               accountCategory == AccountCategory.COLLECTION ||
               accountCategory == AccountCategory.DISBURSEMENT;
    }

    // ========================================================================
    // UNIFIED ARCHITECTURE: BALANCE ACCESSORS (NEW)
    // ========================================================================

    /**
     * Get the effective balance based on account category.
     * - Currency mirrors: return mirrorBalance (MUST CHECK FIRST!)
     * - Aggregation nodes: return aggregatedBalance
     * - Physical mirrors: return bankBalance
     * - Regular VAs: return currentBalance
     *
     * FIX v5.5.2: Check isCurrencyMirror() BEFORE isAggregationNode() because
     * isAggregationNode() returns true for CURRENCY_MIRROR, which would incorrectly
     * return aggregatedBalance instead of mirrorBalance.
     */
    public BigDecimal getEffectiveBalance() {
        // CRITICAL: Check Currency Mirror FIRST because isAggregationNode() includes CURRENCY_MIRROR
        if (isCurrencyMirror()) {
            return mirrorBalance != null ? mirrorBalance : BigDecimal.ZERO;
        }
        if (isAggregationNode()) {
            return aggregatedBalance != null ? aggregatedBalance : BigDecimal.ZERO;
        }
        if (isPhysicalMirror() || isExternalMirror()) {
            return bankBalance != null ? bankBalance : BigDecimal.ZERO;
        }
        return currentBalance != null ? currentBalance : BigDecimal.ZERO;
    }

    /**
     * Get total available including credit limit.
     * Used by FundsAvailabilityService to check debit eligibility.
     */
    public BigDecimal getTotalAvailableWithLimit() {
        BigDecimal balance = getEffectiveBalance();
        BigDecimal limit = creditLimitAvailable != null ? creditLimitAvailable : BigDecimal.ZERO;
        return balance.add(limit);
    }

    // ========================================================================
    // UNIFIED ARCHITECTURE: CURRENCY MIRROR OPERATIONS (NEW)
    // ========================================================================

    /**
     * Credit the mirror balance (for CURRENCY_MIRROR accounts).
     */
    public void creditMirror(BigDecimal amount) {
        if (!isCurrencyMirror()) {
            throw new IllegalStateException("creditMirror only for CURRENCY_MIRROR accounts");
        }
        if (mirrorBalance == null) mirrorBalance = BigDecimal.ZERO;
        mirrorBalance = mirrorBalance.add(amount);
        recalculateBalanceInBase();
    }

    /**
     * Debit the mirror balance (for CURRENCY_MIRROR accounts).
     */
    public void debitMirror(BigDecimal amount) {
        if (!isCurrencyMirror()) {
            throw new IllegalStateException("debitMirror only for CURRENCY_MIRROR accounts");
        }
        if (mirrorBalance == null) mirrorBalance = BigDecimal.ZERO;
        mirrorBalance = mirrorBalance.subtract(amount);
        recalculateBalanceInBase();
    }

    /**
     * Update FX rate and recalculate base currency balance.
     */
    public void updateFxRate(BigDecimal rate, String source) {
        this.fxRate = rate;
        this.fxRateAt = LocalDateTime.now();
        this.fxRateSource = source;
        recalculateBalanceInBase();
    }

    /**
     * Recalculate balance in base currency using current FX rate.
     */
    public void recalculateBalanceInBase() {
        if (mirrorBalance != null && fxRate != null) {
            this.balanceInBase = mirrorBalance.multiply(fxRate)
                .setScale(4, RoundingMode.HALF_UP);
        }
    }

    // ========================================================================
    // UNIFIED ARCHITECTURE: SHADOW ACCOUNT OPERATIONS (NEW)
    // ========================================================================

    /**
     * Update bank balance from CBS (for PHYSICAL_MIRROR accounts).
     */
    public void updateBankBalance(BigDecimal current, BigDecimal available) {
        if (!isPhysicalMirror() && !isExternalMirror()) {
            throw new IllegalStateException("updateBankBalance only for mirror accounts");
        }
        this.bankBalance = current;
        this.bankAvailableBalance = available;
        this.bankBalanceAt = LocalDateTime.now();
    }

    /**
     * Apply a signed movement (positive = credit, negative = debit) to a shadow account,
     * keeping currentBalance/availableBalance and the CBS-mirrored bankBalance in lockstep.
     * This is the CBS-triggering posting used by outbound/inbound payment legs
     * (see TransactionService.makePayment/makePoboPayment/processCollection) and by
     * shadow-account sweep legs.
     */
    public void applyShadowMovement(BigDecimal delta) {
        this.currentBalance = this.currentBalance.add(delta);
        this.availableBalance = this.currentBalance;
        mirrorBankBalance(delta);
    }

    /**
     * Mirror a balance delta into the CBS-tracked {@code bankBalance}, if this account
     * has one (PHYSICAL_MIRROR/EXTERNAL_MIRROR). No-op for accounts with no bank balance
     * of their own. Used alongside the normal ledger mutators ({@code credit}/{@code debit}/
     * {@code settleOutflow}/{@code settleInflow}) when those are applied to a shadow account,
     * so the real bank-held balance stays in sync with whatever moved the ledger balance.
     */
    public void mirrorBankBalance(BigDecimal delta) {
        if (this.bankBalance != null) {
            this.bankBalance = this.bankBalance.add(delta);
            this.bankBalanceAt = LocalDateTime.now();
        }
    }

    // ========================================================================
    // UNIFIED ARCHITECTURE: CREDIT LIMIT OPERATIONS (NEW)
    // ========================================================================

    /**
     * Check if this account has a credit limit.
     */
    public boolean hasCreditLimit() {
        return effectiveCreditLimit != null && 
               effectiveCreditLimit.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * Check if debit amount is within credit limit.
     */
    public boolean isWithinCreditLimit(BigDecimal debitAmount) {
        BigDecimal available = getTotalAvailableWithLimit();
        return available.compareTo(debitAmount) >= 0;
    }

    /**
     * Utilize credit limit (after debit that goes into overdraft).
     */
    public void utilizeCreditLimit(BigDecimal amount) {
        if (creditLimitUtilized == null) creditLimitUtilized = BigDecimal.ZERO;
        creditLimitUtilized = creditLimitUtilized.add(amount);
        recalculateCreditAvailable();
    }

    /**
     * Release credit limit (after credit that reduces overdraft).
     */
    public void releaseCreditLimit(BigDecimal amount) {
        if (creditLimitUtilized == null) creditLimitUtilized = BigDecimal.ZERO;
        creditLimitUtilized = creditLimitUtilized.subtract(amount);
        if (creditLimitUtilized.compareTo(BigDecimal.ZERO) < 0) {
            creditLimitUtilized = BigDecimal.ZERO;
        }
        recalculateCreditAvailable();
    }

    /**
     * Recalculate available credit limit.
     */
    public void recalculateCreditAvailable() {
        if (effectiveCreditLimit != null) {
            creditLimitAvailable = effectiveCreditLimit.subtract(
                creditLimitUtilized != null ? creditLimitUtilized : BigDecimal.ZERO
            );
        }
    }

    // ========================================================================
    // UNIFIED ARCHITECTURE: HIERARCHY PATH BUILDER (NEW)
    // ========================================================================

    /**
     * Build hierarchical path from parent path and node code.
     */
    public static String buildHierarchyPath(String parentPath, String nodeCode) {
        if (parentPath == null || parentPath.isEmpty()) {
            return "/" + nodeCode;
        }
        return parentPath + "/" + nodeCode;
    }

    // ========================================================================
    // EXISTING HELPER METHODS (preserved for backward compatibility)
    // ========================================================================

    public boolean isActive() {
        return status == VaStatus.ACTIVE;
    }

    public boolean hasHierarchy() {
        return hierarchyNodeId != null || parentAccountId != null;
    }

    public boolean hasPrimaryViban() {
        return primaryVibanId != null || (viban != null && !viban.isEmpty());
    }

    public String getEffectiveViban() {
        return viban;
    }

    public boolean isWithinTransactionLimit(BigDecimal amount) {
        if (perTransactionLimit == null) return true;
        return amount.compareTo(perTransactionLimit) <= 0;
    }

    public boolean isWithinDailyLimit(BigDecimal amount) {
        if (dailyLimit == null) return true;
        BigDecimal used = dailyUsed != null ? dailyUsed : BigDecimal.ZERO;
        return used.add(amount).compareTo(dailyLimit) <= 0;
    }

    public boolean isWithinWeeklyLimit(BigDecimal amount) {
        if (weeklyLimit == null) return true;
        BigDecimal used = weeklyUsed != null ? weeklyUsed : BigDecimal.ZERO;
        return used.add(amount).compareTo(weeklyLimit) <= 0;
    }

    public boolean isWithinMonthlyLimit(BigDecimal amount) {
        if (monthlyLimit == null) return true;
        BigDecimal used = monthlyUsed != null ? monthlyUsed : BigDecimal.ZERO;
        return used.add(amount).compareTo(monthlyLimit) <= 0;
    }

    public boolean isWithinAnnualLimit(BigDecimal amount) {
        if (annualLimit == null) return true;
        BigDecimal used = annualUsed != null ? annualUsed : BigDecimal.ZERO;
        return used.add(amount).compareTo(annualLimit) <= 0;
    }

    public boolean isWithinAllLimits(BigDecimal amount) {
        return isWithinTransactionLimit(amount) 
            && isWithinDailyLimit(amount) 
            && isWithinWeeklyLimit(amount)
            && isWithinMonthlyLimit(amount)
            && isWithinAnnualLimit(amount);
    }

    public boolean isWithinMaxBalance(BigDecimal creditAmount) {
        if (maxBalance == null) return true;
        BigDecimal newBalance = currentBalance.add(creditAmount);
        return newBalance.compareTo(maxBalance) <= 0;
    }

    public boolean isWithinDailyTopupLimit(BigDecimal amount) {
        if (dailyTopupLimit == null) return true;
        BigDecimal used = dailyTopupUsed != null ? dailyTopupUsed : BigDecimal.ZERO;
        return used.add(amount).compareTo(dailyTopupLimit) <= 0;
    }

    public boolean isWithinMonthlyTopupLimit(BigDecimal amount) {
        if (monthlyTopupLimit == null) return true;
        BigDecimal used = monthlyTopupUsed != null ? monthlyTopupUsed : BigDecimal.ZERO;
        return used.add(amount).compareTo(monthlyTopupLimit) <= 0;
    }

    public boolean isBalanceExpired() {
        if (balanceExpiryDate == null) return false;
        return LocalDate.now().isAfter(balanceExpiryDate);
    }

    public boolean isWalletExpired() {
        if (walletExpiryDate == null) return false;
        return LocalDate.now().isAfter(walletExpiryDate);
    }

    public boolean isKycExpired() {
        if (kycExpiryDate == null) return false;
        return LocalDate.now().isAfter(kycExpiryDate);
    }

    public BigDecimal getCurrencyEquivalentBalance() {
        if (valueType == ValueType.FIAT || pointsToCurrencyRate == null) {
            return currentBalance;
        }
        return currentBalance.multiply(pointsToCurrencyRate);
    }

    public BigDecimal getTrueAvailableBalance() {
        BigDecimal held = heldBalance != null ? heldBalance : BigDecimal.ZERO;
        return availableBalance.subtract(held);
    }

    public void recordSpending(BigDecimal amount) {
        if (dailyUsed == null) dailyUsed = BigDecimal.ZERO;
        if (weeklyUsed == null) weeklyUsed = BigDecimal.ZERO;
        if (monthlyUsed == null) monthlyUsed = BigDecimal.ZERO;
        if (annualUsed == null) annualUsed = BigDecimal.ZERO;
        
        dailyUsed = dailyUsed.add(amount);
        weeklyUsed = weeklyUsed.add(amount);
        monthlyUsed = monthlyUsed.add(amount);
        annualUsed = annualUsed.add(amount);
        
        lastActivityDate = LocalDate.now();
    }

    public void recordTopup(BigDecimal amount) {
        if (dailyTopupUsed == null) dailyTopupUsed = BigDecimal.ZERO;
        if (monthlyTopupUsed == null) monthlyTopupUsed = BigDecimal.ZERO;
        
        dailyTopupUsed = dailyTopupUsed.add(amount);
        monthlyTopupUsed = monthlyTopupUsed.add(amount);
        
        lastActivityDate = LocalDate.now();
    }

    public void resetDailyLimits() {
        dailyUsed = BigDecimal.ZERO;
        dailyTopupUsed = BigDecimal.ZERO;
        lastLimitResetDate = LocalDate.now();
    }

    public void resetWeeklyLimits() {
        weeklyUsed = BigDecimal.ZERO;
    }

    public void resetMonthlyLimits() {
        monthlyUsed = BigDecimal.ZERO;
        monthlyTopupUsed = BigDecimal.ZERO;
    }

    public void resetAnnualLimits() {
        annualUsed = BigDecimal.ZERO;
    }

    public void credit(BigDecimal amount) {
        this.currentBalance = this.currentBalance.add(amount);
        this.availableBalance = this.availableBalance.add(amount);
        this.lastActivityDate = LocalDate.now();
    }

    public void debit(BigDecimal amount) {
        this.currentBalance = this.currentBalance.subtract(amount);
        this.availableBalance = this.availableBalance.subtract(amount);
        this.lastActivityDate = LocalDate.now();
    }

    public void hold(BigDecimal amount) {
        if (this.heldBalance == null) this.heldBalance = BigDecimal.ZERO;
        this.heldBalance = this.heldBalance.add(amount);
        this.availableBalance = this.availableBalance.subtract(amount);
    }

    public void releaseHold(BigDecimal amount) {
        if (this.heldBalance == null) this.heldBalance = BigDecimal.ZERO;
        this.heldBalance = this.heldBalance.subtract(amount);
        this.availableBalance = this.availableBalance.add(amount);
    }

    // ========================================================================
    // IHB COMMITTED BALANCE METHODS (Option B Architecture)
    // ========================================================================

    /**
     * Commit an outflow (IHB deposit creation).
     * Reduces available balance but not current balance.
     * Funds are "spoken for" until EOD settlement.
     */
    public void commitOutflow(BigDecimal amount) {
        if (this.committedOutflow == null) this.committedOutflow = BigDecimal.ZERO;
        this.committedOutflow = this.committedOutflow.add(amount);
        this.availableBalance = this.availableBalance.subtract(amount);
    }

    /**
     * Commit an inflow (IHB loan disbursement to this account).
     * Expected incoming funds from Treasury.
     */
    public void commitInflow(BigDecimal amount) {
        if (this.committedInflow == null) this.committedInflow = BigDecimal.ZERO;
        this.committedInflow = this.committedInflow.add(amount);
    }

    /**
     * Release committed outflow (position cancelled or rejected).
     * Restores available balance.
     */
    public void releaseCommittedOutflow(BigDecimal amount) {
        if (this.committedOutflow == null) this.committedOutflow = BigDecimal.ZERO;
        this.committedOutflow = this.committedOutflow.subtract(amount);
        if (this.committedOutflow.compareTo(BigDecimal.ZERO) < 0) {
            this.committedOutflow = BigDecimal.ZERO;
        }
        this.availableBalance = this.availableBalance.add(amount);
    }

    /**
     * Release committed inflow (position cancelled or rejected).
     */
    public void releaseCommittedInflow(BigDecimal amount) {
        if (this.committedInflow == null) this.committedInflow = BigDecimal.ZERO;
        this.committedInflow = this.committedInflow.subtract(amount);
        if (this.committedInflow.compareTo(BigDecimal.ZERO) < 0) {
            this.committedInflow = BigDecimal.ZERO;
        }
    }

    /**
     * Settle committed outflow (EOD settlement - actual fund movement).
     * Debits current balance and clears committed outflow.
     */
    public void settleOutflow(BigDecimal amount) {
        if (this.committedOutflow == null) this.committedOutflow = BigDecimal.ZERO;
        this.currentBalance = this.currentBalance.subtract(amount);
        this.committedOutflow = this.committedOutflow.subtract(amount);
        if (this.committedOutflow.compareTo(BigDecimal.ZERO) < 0) {
            this.committedOutflow = BigDecimal.ZERO;
        }
        this.lastActivityDate = LocalDate.now();
    }

    /**
     * Settle committed inflow (EOD settlement - actual fund received).
     * Credits current balance, available balance, and clears committed inflow.
     */
    public void settleInflow(BigDecimal amount) {
        if (this.committedInflow == null) this.committedInflow = BigDecimal.ZERO;
        this.currentBalance = this.currentBalance.add(amount);
        this.availableBalance = this.availableBalance.add(amount);
        this.committedInflow = this.committedInflow.subtract(amount);
        if (this.committedInflow.compareTo(BigDecimal.ZERO) < 0) {
            this.committedInflow = BigDecimal.ZERO;
        }
        this.lastActivityDate = LocalDate.now();
    }

    /**
     * Clear all committed balances (after full settlement).
     */
    public void clearCommittedBalances() {
        this.committedOutflow = BigDecimal.ZERO;
        this.committedInflow = BigDecimal.ZERO;
    }

    /**
     * Get the effective available balance (accounting for holds and committed outflows).
     * This is what's truly available for new transactions.
     */
    public BigDecimal getEffectiveAvailableBalance() {
        BigDecimal available = this.availableBalance != null ? this.availableBalance : BigDecimal.ZERO;
        // Note: committedOutflow is already subtracted when commitOutflow() is called
        // So availableBalance already reflects committed outflows
        return available;
    }

    /**
     * Get total committed amounts (outflow + inflow for reporting).
     */
    public BigDecimal getTotalCommitted() {
        BigDecimal outflow = this.committedOutflow != null ? this.committedOutflow : BigDecimal.ZERO;
        BigDecimal inflow = this.committedInflow != null ? this.committedInflow : BigDecimal.ZERO;
        return outflow.add(inflow);
    }

    /**
     * Check if there are any pending commitments.
     */
    public boolean hasCommitments() {
        return getTotalCommitted().compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * Check if outflow amount can be committed (sufficient available balance).
     * This enforces hard block on insufficient balance.
     */
    public boolean canCommitOutflow(BigDecimal amount) {
        return getEffectiveAvailableBalance().compareTo(amount) >= 0;
    }

    // Wallet-specific methods
    public boolean isWallet() {
        return walletType != null && !walletType.isEmpty();
    }

    public boolean isOperational() {
        return status == VaStatus.ACTIVE && !isWalletExpired();
    }

    public boolean hasSufficientBalance(BigDecimal amount) {
        BigDecimal available = this.availableBalance != null ? this.availableBalance : BigDecimal.ZERO;
        return available.compareTo(amount) >= 0;
    }

    public boolean canTopup(BigDecimal amount) {
        return isWithinMaxBalance(amount) && isWithinDailyTopupLimit(amount) && isWithinMonthlyTopupLimit(amount);
    }

    public void withdraw(BigDecimal amount) {
        debit(amount);
        recordSpending(amount);
        this.lastWithdrawalAt = LocalDateTime.now();
        this.withdrawalCount = (withdrawalCount != null ? withdrawalCount : 0) + 1;
        this.transactionCount = (transactionCount != null ? transactionCount : 0) + 1;
    }

    public void transferOut(BigDecimal amount) {
        debit(amount);
        recordSpending(amount);
        this.lastTransactionAt = LocalDateTime.now();
        this.transactionCount = (transactionCount != null ? transactionCount : 0) + 1;
    }

    public void topup(BigDecimal amount) {
        credit(amount);
        recordTopup(amount);
        this.lastTopupAt = LocalDateTime.now();
        this.topupCount = (topupCount != null ? topupCount : 0) + 1;
        this.transactionCount = (transactionCount != null ? transactionCount : 0) + 1;
    }

    public void suspend(String reason) {
        this.status = VaStatus.SUSPENDED;
        this.suspensionReason = reason;
    }

    public void block(String reason) {
        this.status = VaStatus.BLOCKED;
        this.blockReason = reason;
    }

    public void reactivate() {
        this.status = VaStatus.ACTIVE;
        this.suspensionReason = null;
        this.blockReason = null;
    }

    public void verifyKyc(Integer level) {
        this.kycVerified = true;
        this.kycLevel = level != null ? level : 1;
        this.kycVerifiedAt = LocalDateTime.now();
    }

    // Settlement/Exception VA helpers
    public boolean isSettlementVa() {
        return specialType == VaSpecialType.SETTLEMENT || 
               accountCategory == AccountCategory.SETTLEMENT;
    }

    public boolean isExceptionVa() {
        return specialType == VaSpecialType.EXCEPTION ||
               accountCategory == AccountCategory.EXCEPTION;
    }

    public boolean isSystemVa() {
        return (specialType != null && specialType != VaSpecialType.REGULAR) ||
               accountCategory == AccountCategory.SETTLEMENT ||
               accountCategory == AccountCategory.EXCEPTION ||
               accountCategory == AccountCategory.SUSPENSE;
    }

    public boolean isRegularVa() {
        return (specialType == null || specialType == VaSpecialType.REGULAR) &&
               (accountCategory == null || accountCategory == AccountCategory.TRANSACTION);
    }

    // ========================================================================
    // IHB (IN-HOUSE BANK) HELPER METHODS
    // ========================================================================

    /**
     * Check if this account participates in the In-House Bank scheme.
     * Uses the ihbParticipant flag - any TRANSACTION VA can be an IHB participant.
     */
    public boolean isIhbParticipant() {
        return Boolean.TRUE.equals(ihbParticipant);
    }

    /**
     * Check if this is an IHB Current Account (legacy method for backward compatibility).
     * Now uses ihbParticipant flag instead of INTERCOMPANY category check.
     */
    public boolean isIhbCurrentAccount() {
        return isIhbParticipant() && owningEntityId != null;
    }

    /**
     * Enable IHB participation for this account.
     * @param enabledBy The user/system enabling IHB
     */
    public void enableIhbParticipation(String enabledBy) {
        this.ihbParticipant = true;
        this.ihbEnabledAt = LocalDateTime.now();
        this.ihbEnabledBy = enabledBy;
    }

    /**
     * Disable IHB participation for this account.
     */
    public void disableIhbParticipation() {
        this.ihbParticipant = false;
        // Keep ihbEnabledAt/By for audit trail
    }

    /**
     * Get IHB position type based on current balance.
     * CREDIT = positive balance (participant has surplus)
     * DEBIT = negative balance (participant has borrowed/overdraft)
     * NEUTRAL = zero balance
     */
    public String getIhbPositionType() {
        if (currentBalance == null || currentBalance.compareTo(BigDecimal.ZERO) == 0) {
            return "NEUTRAL";
        }
        return currentBalance.compareTo(BigDecimal.ZERO) > 0 ? "CREDIT" : "DEBIT";
    }

    /**
     * Check if account is in overdraft (negative balance).
     */
    public boolean isInOverdraft() {
        return currentBalance != null && currentBalance.compareTo(BigDecimal.ZERO) < 0;
    }

    /**
     * Get the overdraft amount (absolute value of negative balance).
     */
    public BigDecimal getOverdraftAmount() {
        if (!isInOverdraft()) return BigDecimal.ZERO;
        return currentBalance.abs();
    }

    /**
     * Get available balance including credit limit.
     * For IHB accounts: currentBalance + effectiveCreditLimit
     */
    public BigDecimal getIhbAvailableBalance() {
        BigDecimal balance = currentBalance != null ? currentBalance : BigDecimal.ZERO;
        BigDecimal limit = effectiveCreditLimit != null ? effectiveCreditLimit : BigDecimal.ZERO;
        return balance.add(limit);
    }

    /**
     * Check if withdrawal amount is within available funds + credit limit.
     */
    public boolean canWithdrawIhb(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) return false;
        return getIhbAvailableBalance().compareTo(amount) >= 0;
    }

    /**
     * Get net accrued interest (credit - debit).
     * Positive = participant will receive interest.
     * Negative = participant will pay interest.
     */
    public BigDecimal getNetAccruedInterest() {
        BigDecimal credit = accruedCreditInterest != null ? accruedCreditInterest : BigDecimal.ZERO;
        BigDecimal debit = accruedDebitInterest != null ? accruedDebitInterest : BigDecimal.ZERO;
        return credit.subtract(debit);
    }

    /**
     * Add to accrued credit interest.
     */
    public void accrueCredit(BigDecimal amount) {
        if (accruedCreditInterest == null) accruedCreditInterest = BigDecimal.ZERO;
        accruedCreditInterest = accruedCreditInterest.add(amount);
    }

    /**
     * Add to accrued debit interest.
     */
    public void accrueDebit(BigDecimal amount) {
        if (accruedDebitInterest == null) accruedDebitInterest = BigDecimal.ZERO;
        accruedDebitInterest = accruedDebitInterest.add(amount);
    }

    /**
     * Reset accrued interest after posting.
     */
    public void resetAccruedInterest() {
        accruedCreditInterest = BigDecimal.ZERO;
        accruedDebitInterest = BigDecimal.ZERO;
        lastInterestPostingDate = LocalDate.now();
    }

    // ========================================================================
    // IHB MIRROR ACCOUNT MODEL HELPER METHODS
    // ========================================================================

    /**
     * Check if this is an IHB Current Account (main IHB position for subsidiary).
     */
    public boolean isIhbCurrentVa() {
        return mirrorAccountType == MirrorAccountType.IHB_CURRENT ||
               (Boolean.TRUE.equals(ihbParticipant) && owningEntityId != null);
    }

    /**
     * Check if this is an IHB Settlement VA (per-subsidiary routing).
     */
    public boolean isIhbSettlementVa() {
        return mirrorAccountType == MirrorAccountType.IHB_SETTLEMENT;
    }

    /**
     * Check if this is an IC Receivable VA (Treasury's claim on subsidiary).
     */
    public boolean isIcReceivableVa() {
        return mirrorAccountType == MirrorAccountType.IC_RECEIVABLE;
    }

    /**
     * Check if this is a Treasury Settlement VA (omnibus settlement).
     */
    public boolean isTreasurySettlementVa() {
        return mirrorAccountType == MirrorAccountType.TREASURY_SETTLEMENT;
    }

    /**
     * Check if this VA has a configured IHB Settlement VA.
     */
    public boolean hasIhbSettlementVa() {
        return ihbSettlementVaId != null;
    }

    /**
     * Check if this VA has a corresponding IC Receivable at Treasury.
     */
    public boolean hasIcReceivable() {
        return icReceivableVaId != null;
    }

    /**
     * Check if this VA has a corresponding IC Payable at Treasury.
     */
    public boolean hasIcPayable() {
        return icPayableVaId != null;
    }

    /**
     * Check if all mirror accounts are configured for 6-leg POBO.
     */
    public boolean isConfiguredFor6LegPobo() {
        return hasIhbSettlementVa() && hasIcReceivable() && treasuryPoolVaId != null;
    }

    // Backward compatibility getters
    public BigDecimal getDailySpent() { return dailyUsed; }
    public BigDecimal getWeeklySpent() { return weeklyUsed; }
    public BigDecimal getMonthlySpent() { return monthlyUsed; }
    public BigDecimal getYearlySpent() { return annualUsed; }
    public BigDecimal getYearlyLimit() { return annualLimit; }
    public BigDecimal getBlockedBalance() { return heldBalance; }

    // ========================================================================
    // PHASE 1: PUBLISH STATUS HELPER METHODS
    // ========================================================================

    /**
     * Check if this VA is published (has VIBAN and can receive external payments).
     */
    public boolean isPublished() {
        return publishStatus == PublishStatus.PUBLISHED && viban != null && !viban.isEmpty();
    }

    /**
     * Check if this VA is unpublished (internal-only, no VIBAN).
     */
    public boolean isUnpublished() {
        return publishStatus == PublishStatus.UNPUBLISHED || publishStatus == null;
    }

    /**
     * Check if this VA's publish status is suspended.
     */
    public boolean isPublishSuspended() {
        return publishStatus == PublishStatus.SUSPENDED;
    }

    /**
     * Check if this VA can be published.
     * Requirements: Active status, not already published, and eligible category.
     */
    public boolean canBePublished() {
        if (status != VaStatus.ACTIVE) {
            return false;
        }
        if (isPublished()) {
            return false;
        }
        // Only operational VAs can be published
        return isOperationalVa() || accountCategory == AccountCategory.COLLECTION;
    }

    /**
     * Publish the VA with a generated or provided VIBAN.
     * @param assignedViban The VIBAN to assign
     * @param publishedByUser The user/system publishing
     */
    public void publish(String assignedViban, String publishedByUser) {
        if (assignedViban == null || assignedViban.isEmpty()) {
            throw new IllegalArgumentException("VIBAN is required for publishing");
        }
        this.viban = assignedViban;
        this.publishStatus = PublishStatus.PUBLISHED;
        this.publishedAt = LocalDateTime.now();
        this.publishedBy = publishedByUser;
        this.unpublishedAt = null;
        this.unpublishReason = null;
    }

    /**
     * Unpublish the VA (remove external visibility but keep VIBAN for audit).
     * @param reason Reason for unpublishing
     */
    public void unpublish(String reason) {
        this.publishStatus = PublishStatus.UNPUBLISHED;
        this.unpublishedAt = LocalDateTime.now();
        this.unpublishReason = reason;
        // Note: VIBAN is kept for audit trail, but VA won't receive external payments
    }

    /**
     * Suspend the publish status (temporary, can be re-published).
     * @param reason Reason for suspension
     */
    public void suspendPublish(String reason) {
        if (publishStatus != PublishStatus.PUBLISHED) {
            throw new IllegalStateException("Cannot suspend non-published VA");
        }
        this.publishStatus = PublishStatus.SUSPENDED;
        this.unpublishedAt = LocalDateTime.now();
        this.unpublishReason = reason;
    }

    /**
     * Re-publish a suspended VA.
     * @param publishedByUser The user/system re-publishing
     */
    public void republish(String publishedByUser) {
        if (publishStatus != PublishStatus.SUSPENDED) {
            throw new IllegalStateException("Cannot republish non-suspended VA");
        }
        if (viban == null || viban.isEmpty()) {
            throw new IllegalStateException("Cannot republish VA without VIBAN");
        }
        this.publishStatus = PublishStatus.PUBLISHED;
        this.publishedAt = LocalDateTime.now();
        this.publishedBy = publishedByUser;
        this.unpublishedAt = null;
        this.unpublishReason = null;
    }

    /**
     * Check if this VA can receive external payments (published and active).
     */
    public boolean canReceiveExternalPayments() {
        return isPublished() && status == VaStatus.ACTIVE;
    }
}