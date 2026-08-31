package com.bank.vam.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Virtual Account DTOs - Enhanced with full feature support.
 * 
 * Supports all program types:
 * - COLLECTION: Receivables collection
 * - VIBAN: Virtual IBAN assignment
 * - ESCROW: Digital escrow
 * - WALLET: Prepaid/stored value
 * - IHB: In-House Banking
 * - PAYABLES: Payment-on-Behalf (POBO)
 * - LOYALTY: Points/Miles programs
 * - GIFT_CARD: Gift/Prepaid cards
 * - CORPORATE_CARD: Expense management
 * - MOBILE_MONEY: Agent banking
 * 
 * Backward compatible: All new fields are optional with sensible defaults.
 * 
 * v4.4: Added parentNodeId support for direct parent-child VA creation from UI.
 */
public class VirtualAccountDto {

    // ========================================================================
    // CREATE REQUEST - Full Featured
    // ========================================================================
    
    /**
     * Request to create a new Virtual Account.
     * 
     * CREATION MODES:
     * 1. LEGACY MODE: No parent reference - creates standalone VA
     * 2. HIERARCHY MODE (Dimension Map): Use CreateWithHierarchyRequest with hierarchyDimensions
     * 3. PARENT MODE (NEW v4.4): Use parentNodeId to create as child of existing node/VA
     * 
     * Required fields vary by program type:
     * - All types: vaName, corporateId, physicalAccountId, currencyCode
     * - WALLET/MOBILE_MONEY: walletType, kycLevel recommended
     * - COLLECTION/IHB/PAYABLES: hierarchyNodeId recommended
     * - CORPORATE_CARD: cardProgramType, budgetOwnerId required
     * - LOYALTY: valueType=POINTS, pointsToCurrencyRate required
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        
        // ====================================================================
        // CORE FIELDS (existing - backward compatible)
        // ====================================================================
        
        @NotBlank(message = "VA name is required")
        @Size(max = 100, message = "VA name must not exceed 100 characters")
        private String vaName;
        
        @Size(max = 10, message = "VA prefix must not exceed 10 characters")
        private String vaPrefix;
        
        @Size(max = 34, message = "VIBAN must not exceed 34 characters")
        private String viban;
        
        private UUID programId;
        
        private UUID corporateId;
        
        private UUID physicalAccountId;
        
        @Size(min = 3, max = 3, message = "Currency code must be 3 characters")
        private String currencyCode;
        
        @Size(max = 100, message = "External reference must not exceed 100 characters")
        private String externalReference;
        
        private String metadata;
        
        // ====================================================================
        // PROGRAM INHERITANCE
        // ====================================================================
        
        /**
         * If true, inherit default limits and configuration from the program.
         * Defaults to true for convenience.
         */
        @Builder.Default
        private Boolean inheritProgramDefaults = true;
        
        // ====================================================================
        // HIERARCHY (COLLECTION, IHB, PAYABLES, RECEIVABLES)
        // ====================================================================
        
        /**
         * Link to hierarchy node (L7 for VA level).
         * Required for programs with hierarchyEnabled=true.
         */
        private UUID hierarchyNodeId;
        
        /**
         * Collection channel for routing.
         * Values: INVOICE, ECOMMERCE, POS, DIRECT, ESCROW, WALLET, SUBSCRIPTION, QR_CODE, AGENT
         */
        private String collectionChannel;
        
        // ====================================================================
        // NEW v4.4: PARENT-BASED HIERARCHY CREATION
        // ====================================================================
        
        /**
         * Parent node ID (HierarchyNode ID or VirtualAccount ID).
         * If provided, the VA will be placed as a child of this parent in the hierarchy.
         * The service will resolve whether this is a node ID or VA ID.
         * 
         * This is an alternative to hierarchyNodeId - use this when you want to
         * create a VA as a CHILD of a parent, rather than linking to a specific node.
         */
        private UUID parentNodeId;
        
        /**
         * Alternative field name for parentNodeId (frontend compatibility).
         * If parentNodeId is null, this will be used.
         */
        private UUID parentVaId;
        
        /**
         * Account category - determines VA behavior and position in hierarchy.
         * Values: ROOT, AGGREGATION, TRANSACTION, COLLECTION, DISBURSEMENT,
         *         SETTLEMENT, EXCEPTION, CURRENCY_MIRROR, INTERCOMPANY, ESCROW
         * Defaults to TRANSACTION if parentNodeId is provided.
         */
        private String accountCategory;
        
        /**
         * Account type - REAL or VIRTUAL.
         * Defaults to VIRTUAL.
         */
        private String accountType;
        
        /**
         * Account purpose - used for dimensioning in hierarchy.
         * Examples: OPERATING, COLLECTIONS, PAYABLES, RECEIVABLES, TREASURY, ESCROW, PAYROLL
         */
        private String accountPurpose;
        
        /**
         * Base currency for FX conversion (used in multi-currency hierarchies).
         * Defaults to parent's currency if not specified.
         */
        private String baseCurrency;
        
        /**
         * Owning entity ID - Legal Entity that owns this VA (for POBO/COBO).
         */
        private UUID owningEntityId;
        
        /**
         * Owning entity code - for display purposes.
         */
        @Size(max = 50, message = "Owning entity code must not exceed 50 characters")
        private String owningEntityCode;
        
        // ====================================================================
        // WALLET CONFIGURATION (WALLET, MOBILE_MONEY)
        // ====================================================================
        
        /**
         * Wallet type for categorization.
         * Values: CONSUMER, EMPLOYEE, MERCHANT, AGENT, CORPORATE, GIFT
         */
        private String walletType;
        
        /**
         * Initial KYC level (0-3).
         * Level 0: Unverified, Level 1: Basic, Level 2: Enhanced, Level 3: Full
         */
        private Integer kycLevel;
        
        /**
         * Wallet expiry date.
         * If not set, will be calculated from program.walletExpiryDays.
         */
        private LocalDate expiresAt;
        
        /**
         * Party ID of the wallet holder.
         */
        private UUID holderPartyId;
        
        // ====================================================================
        // SPENDING LIMITS (WALLET, PAYABLES, CORPORATE_CARD, MOBILE_MONEY)
        // ====================================================================
        
        /**
         * Maximum amount per transaction.
         */
        @DecimalMin(value = "0", message = "Per transaction limit must be non-negative")
        private BigDecimal perTransactionLimit;
        
        /**
         * Maximum daily spending.
         */
        @DecimalMin(value = "0", message = "Daily limit must be non-negative")
        private BigDecimal dailyLimit;
        
        /**
         * Maximum weekly spending.
         */
        @DecimalMin(value = "0", message = "Weekly limit must be non-negative")
        private BigDecimal weeklyLimit;
        
        /**
         * Maximum monthly spending.
         */
        @DecimalMin(value = "0", message = "Monthly limit must be non-negative")
        private BigDecimal monthlyLimit;
        
        /**
         * Maximum annual spending.
         */
        @DecimalMin(value = "0", message = "Annual limit must be non-negative")
        private BigDecimal annualLimit;
        
        /**
         * Maximum allowed balance.
         */
        @DecimalMin(value = "0", message = "Max balance must be non-negative")
        private BigDecimal maxBalance;
        
        // ====================================================================
        // TOPUP LIMITS (WALLET, MOBILE_MONEY)
        // ====================================================================
        
        /**
         * Maximum daily topup amount.
         */
        @DecimalMin(value = "0", message = "Daily topup limit must be non-negative")
        private BigDecimal dailyTopupLimit;
        
        /**
         * Maximum monthly topup amount.
         */
        @DecimalMin(value = "0", message = "Monthly topup limit must be non-negative")
        private BigDecimal monthlyTopupLimit;
        
        // ====================================================================
        // VALUE TYPE (LOYALTY, GIFT_CARD)
        // ====================================================================
        
        /**
         * Type of value stored.
         * Values: FIAT (default), POINTS, MILES, TOKENS, CRYPTO
         */
        private String valueType;
        
        /**
         * Conversion rate from points/miles to currency.
         * Example: 0.01 means 1 point = 0.01 AED
         */
        @DecimalMin(value = "0", message = "Points rate must be non-negative")
        private BigDecimal pointsToCurrencyRate;
        
        /**
         * Loyalty tier assignment.
         * Values: PLATINUM, GOLD, SILVER, BLUE, BASIC
         */
        private String loyaltyTier;
        
        /**
         * Link to loyalty program.
         */
        private UUID loyaltyProgramId;
        
        // ====================================================================
        // CARD PROGRAM (CORPORATE_CARD)
        // ====================================================================
        
        /**
         * Type of card program.
         * Values: TRAVEL, PROCUREMENT, FLEET, VIRTUAL, EXPENSE, PETTY_CASH
         */
        private String cardProgramType;
        
        /**
         * ID of linked physical/virtual card.
         */
        private UUID linkedCardId;
        
        /**
         * Budget owner (manager) for approval workflows.
         */
        private UUID budgetOwnerId;
        
        /**
         * Cost center for expense allocation.
         */
        @Size(max = 50, message = "Cost center must not exceed 50 characters")
        private String costCenter;
        
        /**
         * Department for organizational grouping.
         */
        @Size(max = 100, message = "Department must not exceed 100 characters")
        private String department;
        
        // ====================================================================
        // MCC RESTRICTIONS (CORPORATE_CARD, GIFT_CARD, WALLET)
        // ====================================================================
        
        /**
         * JSON array of allowed MCC codes.
         * Example: ["5411", "5412", "5499"]
         */
        private String mccWhitelist;
        
        /**
         * JSON array of blocked MCC codes.
         * Example: ["5813", "7995", "5933"]
         */
        private String mccBlacklist;
        
        /**
         * JSON array of allowed merchant IDs.
         */
        private String merchantWhitelist;
        
        /**
         * JSON array of allowed country codes.
         * Example: ["AE", "SA", "BH"]
         */
        private String countryWhitelist;
        
        // ====================================================================
        // EXPIRY (LOYALTY, GIFT_CARD, WALLET)
        // ====================================================================
        
        /**
         * Date when balance expires.
         */
        private LocalDate balanceExpiryDate;
        
        /**
         * Action to take on expiry.
         * Values: ZERO_BALANCE, FORFEIT, TRANSFER, EXTEND, NOTIFY
         */
        private String expiryAction;
        
        // ====================================================================
        // IHB SPECIFIC (In-House Bank)
        // ====================================================================
        
        /**
         * Credit limit for IHB entities (allows negative balance up to this limit).
         */
        @DecimalMin(value = "0", message = "Credit limit must be non-negative")
        private BigDecimal creditLimit;
        
        // ====================================================================
        // HELPER METHODS (v4.4)
        // ====================================================================
        
        /**
         * Get the effective parent ID (supports both field names for frontend compatibility).
         * @return parentNodeId if set, otherwise parentVaId
         */
        public UUID getEffectiveParentId() {
            return parentNodeId != null ? parentNodeId : parentVaId;
        }
        
        /**
         * Check if this is a parent-based hierarchy creation request.
         * Different from hierarchyNodeId which links to a SPECIFIC node.
         * @return true if parentNodeId or parentVaId is set
         */
        public boolean hasParentReference() {
            return getEffectiveParentId() != null;
        }
    }

    // ========================================================================
    // UPDATE REQUEST - Partial Updates
    // ========================================================================
    
    /**
     * Request to update an existing Virtual Account.
     * All fields are optional - only provided fields are updated.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {
        
        // Core
        @Size(max = 100, message = "VA name must not exceed 100 characters")
        private String vaName;
        
        @Size(max = 100, message = "External reference must not exceed 100 characters")
        private String externalReference;
        
        private String metadata;
        
        // Hierarchy
        private UUID hierarchyNodeId;
        private String collectionChannel;
        
        // Ownership (v4.4)
        private UUID owningEntityId;
        private String owningEntityCode;
        
        // Wallet
        private String walletType;
        private LocalDate expiresAt;
        private UUID holderPartyId;
        
        // Card Program
        private String cardProgramType;
        private UUID linkedCardId;
        private UUID budgetOwnerId;
        
        @Size(max = 50, message = "Cost center must not exceed 50 characters")
        private String costCenter;
        
        @Size(max = 100, message = "Department must not exceed 100 characters")
        private String department;
        
        // Loyalty
        private String loyaltyTier;
        private UUID loyaltyProgramId;
        
        // Expiry
        private LocalDate balanceExpiryDate;
        private String expiryAction;
    }

    // ========================================================================
    // LIMITS UPDATE REQUEST
    // ========================================================================
    
    /**
     * Request to update spending and topup limits.
     * Used for WALLET, PAYABLES, CORPORATE_CARD, MOBILE_MONEY programs.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LimitsUpdateRequest {
        
        @DecimalMin(value = "0", message = "Per transaction limit must be non-negative")
        private BigDecimal perTransactionLimit;
        
        @DecimalMin(value = "0", message = "Daily limit must be non-negative")
        private BigDecimal dailyLimit;
        
        @DecimalMin(value = "0", message = "Weekly limit must be non-negative")
        private BigDecimal weeklyLimit;
        
        @DecimalMin(value = "0", message = "Monthly limit must be non-negative")
        private BigDecimal monthlyLimit;
        
        @DecimalMin(value = "0", message = "Annual limit must be non-negative")
        private BigDecimal annualLimit;
        
        @DecimalMin(value = "0", message = "Max balance must be non-negative")
        private BigDecimal maxBalance;
        
        @DecimalMin(value = "0", message = "Daily topup limit must be non-negative")
        private BigDecimal dailyTopupLimit;
        
        @DecimalMin(value = "0", message = "Monthly topup limit must be non-negative")
        private BigDecimal monthlyTopupLimit;
        
        /**
         * Optional: Reset current usage counters when updating limits.
         */
        @Builder.Default
        private Boolean resetUsage = false;
    }

    // ========================================================================
    // KYC UPDATE REQUEST
    // ========================================================================
    
    /**
     * Request to update KYC status and level.
     * Used for WALLET, ESCROW, MOBILE_MONEY programs.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KycUpdateRequest {
        
        @NotNull(message = "KYC level is required")
        private Integer kycLevel;
        
        private LocalDate kycExpiryDate;
        
        /**
         * Method used for verification.
         * Values: DOCUMENT, BIOMETRIC, VIDEO_KYC, THIRD_PARTY, MANUAL
         */
        private String verificationMethod;
        
        /**
         * Reference ID from verification provider.
         */
        private String verificationReference;
        
        /**
         * Optional notes about verification.
         */
        private String notes;
    }

    // ========================================================================
    // MCC RESTRICTIONS REQUEST
    // ========================================================================
    
    /**
     * Request to update MCC and merchant restrictions.
     * Used for CORPORATE_CARD, GIFT_CARD, WALLET programs.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MccRestrictionsRequest {
        
        /**
         * Allowed MCC codes. If set, only these MCCs are allowed.
         */
        private List<String> mccWhitelist;
        
        /**
         * Blocked MCC codes. These MCCs are always blocked.
         */
        private List<String> mccBlacklist;
        
        /**
         * Allowed merchant IDs. If set, only these merchants are allowed.
         */
        private List<String> merchantWhitelist;
        
        /**
         * Allowed country codes. If set, only transactions in these countries are allowed.
         */
        private List<String> countryWhitelist;
        
        /**
         * If true, replace all restrictions. If false, merge with existing.
         */
        @Builder.Default
        private Boolean replaceAll = true;
    }

    // ========================================================================
    // STATUS UPDATE REQUEST
    // ========================================================================
    
    /**
     * Request to update VA status with optional reason.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatusUpdateRequest {
        
        @NotBlank(message = "Status is required")
        private String status;
        
        /**
         * Reason for status change (required for SUSPENDED, BLOCKED).
         */
        private String reason;
        
        /**
         * User/system initiating the change.
         */
        private String changedBy;
    }

    // ========================================================================
    // HIERARCHY UPDATE REQUEST
    // ========================================================================
    
    /**
     * Request to update hierarchy node assignment.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HierarchyUpdateRequest {
        
        @NotNull(message = "Hierarchy node ID is required")
        private UUID hierarchyNodeId;
        
        /**
         * Optional collection channel override.
         */
        private String collectionChannel;
        
        /**
         * If true, recalculate hierarchy path from node.
         */
        @Builder.Default
        private Boolean recalculatePath = true;
    }

    // ========================================================================
    // RESPONSE - Full Details
    // ========================================================================
    
    /**
     * Full Virtual Account response with all details.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        
        // ====================================================================
        // CORE
        // ====================================================================
        
        private UUID id;
        private String vaNumber;
        private String viban;
        private String vaName;
        private UUID programId;
        private String programName;
        private String programType;
        private UUID corporateId;
        private String corporateName;
        private UUID physicalAccountId;
        private String physicalAccountNumber;
        private String currencyCode;
        private BigDecimal currentBalance;
        private BigDecimal availableBalance;
        private BigDecimal heldBalance;
        private String status;
        private String statusLabel;
        private String statusVariant;
        private String externalReference;
        private Boolean kycVerified;
        private String metadata;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private String createdBy;
        
        // ====================================================================
        // HIERARCHY
        // ====================================================================
        
        private UUID hierarchyNodeId;
        private String hierarchyNodeName;
        private String hierarchyPath;
        private Integer hierarchyLevel;
        private String collectionChannel;
        
        // ====================================================================
        // WALLET
        // ====================================================================
        
        private String walletType;
        private String walletTypeLabel;
        private Integer kycLevel;
        private String kycLevelLabel;
        private LocalDate kycExpiryDate;
        private LocalDateTime kycVerifiedAt;
        private LocalDate expiresAt;
        private Boolean isExpired;
        private Integer daysUntilExpiry;
        private String suspensionReason;
        private String blockReason;
        private UUID holderPartyId;
        private String holderPartyName;
        
        // ====================================================================
        // LIMITS
        // ====================================================================
        
        private LimitsInfo limits;
        private LimitsUsage limitsUsage;
        
        // ====================================================================
        // VALUE TYPE / LOYALTY
        // ====================================================================
        
        private String valueType;
        private String valueTypeLabel;
        private BigDecimal pointsToCurrencyRate;
        private BigDecimal pointsBalance;
        private BigDecimal pendingPoints;
        private BigDecimal lifetimePoints;
        private String loyaltyTier;
        private String loyaltyTierLabel;
        private UUID loyaltyProgramId;
        private String loyaltyProgramName;
        
        // ====================================================================
        // CARD PROGRAM
        // ====================================================================
        
        private String cardProgramType;
        private String cardProgramTypeLabel;
        private UUID linkedCardId;
        private String linkedCardNumber;
        private UUID budgetOwnerId;
        private String budgetOwnerName;
        private String costCenter;
        private String department;
        
        // ====================================================================
        // MCC RESTRICTIONS
        // ====================================================================
        
        private MccRestrictions mccRestrictions;
        private Boolean hasMccRestrictions;
        
        // ====================================================================
        // EXPIRY
        // ====================================================================
        
        private LocalDate balanceExpiryDate;
        private String expiryAction;
        private String expiryActionLabel;
        private Boolean balanceExpired;
        private Integer daysUntilBalanceExpiry;
        
        // ====================================================================
        // IHB (IN-HOUSE BANK) PARTICIPATION
        // ====================================================================

        // IHB Participation Flag
        private Boolean ihbParticipant;
        private LocalDateTime ihbEnabledAt;
        private String ihbEnabledBy;

        // Credit/Overdraft Limits
        private BigDecimal creditLimit;
        private BigDecimal availableCreditLimit;
        private BigDecimal currentExposure;

        // Accrued Interest
        private BigDecimal accruedCreditInterest;
        private BigDecimal accruedDebitInterest;
        private BigDecimal netAccruedInterest;

        // Interest Rates (from InterestConfiguration)
        private BigDecimal effectiveCreditRate;
        private BigDecimal effectiveDebitRate;

        // Interest Configuration References
        private UUID internalInterestConfigId;
        private UUID externalInterestConfigId;

        // IHB Position
        private String ihbPositionType;  // CREDIT, DEBIT, NEUTRAL

        // IHB Sweep Configuration
        private Boolean ihbSweepEnabled;
        private BigDecimal targetCashBalance;
        private String ihbSweepFrequency;

        // Treasury Link
        private UUID treasuryPoolVaId;
        
        // ====================================================================
        // STATS
        // ====================================================================
        
        private Integer transactionCount;
        private Integer topupCount;
        private Integer withdrawalCount;
        private LocalDateTime lastTransactionAt;
        private LocalDateTime lastTopupAt;
        private LocalDateTime lastWithdrawalAt;
        private LocalDate lastActivityDate;
        private LocalDateTime activatedAt;

        // ====================================================================
        // OWNERSHIP & CLASSIFICATION (v4.3+)
        // ====================================================================

        private UUID owningEntityId;
        private String owningEntityCode;
        private String accountCategory;
        private String accountType;
        private UUID parentAccountId;

        // ====================================================================
        // AGGREGATED BALANCES (for AGGREGATION, ROOT, CURRENCY_MIRROR nodes)
        // ====================================================================

        private BigDecimal aggregatedBalance;       // Sum of child balances (in base currency)
        private BigDecimal aggregatedBalanceBase;   // Same as aggregatedBalance (base currency)
        private BigDecimal mirrorBalance;           // For CURRENCY_MIRROR: sum of same-currency siblings
        private BigDecimal balanceInBase;           // For CURRENCY_MIRROR: mirrorBalance converted to base currency
        private String baseCurrency;                // Base currency for aggregation

        // ====================================================================
        // PUBLISH STATUS (Phase 1: Published/Unpublished VA)
        // ====================================================================

        private String publishStatus;
        private Boolean isPublished;
        private Boolean canBePublished;
        private Boolean canReceiveExternalPayments;
        private LocalDateTime publishedAt;
        private String publishedBy;
    }

    // ========================================================================
    // NESTED RESPONSE OBJECTS
    // ========================================================================
    
    /**
     * Limit configuration information.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LimitsInfo {
        private BigDecimal perTransactionLimit;
        private BigDecimal dailyLimit;
        private BigDecimal weeklyLimit;
        private BigDecimal monthlyLimit;
        private BigDecimal annualLimit;
        private BigDecimal maxBalance;
        private BigDecimal dailyTopupLimit;
        private BigDecimal monthlyTopupLimit;
        
        // Formatted for display
        private String perTransactionLimitFormatted;
        private String dailyLimitFormatted;
        private String weeklyLimitFormatted;
        private String monthlyLimitFormatted;
        private String annualLimitFormatted;
        private String maxBalanceFormatted;
        
        // Flags
        private Boolean hasSpendingLimits;
        private Boolean hasTopupLimits;
    }

    /**
     * Current limit usage and percentages.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LimitsUsage {
        // Current usage
        private BigDecimal dailyUsed;
        private BigDecimal weeklyUsed;
        private BigDecimal monthlyUsed;
        private BigDecimal annualUsed;
        private BigDecimal dailyTopupUsed;
        private BigDecimal monthlyTopupUsed;
        private LocalDate lastResetDate;
        
        // Remaining amounts
        private BigDecimal dailyRemaining;
        private BigDecimal weeklyRemaining;
        private BigDecimal monthlyRemaining;
        private BigDecimal annualRemaining;
        private BigDecimal dailyTopupRemaining;
        private BigDecimal monthlyTopupRemaining;
        
        // Calculated percentages (0-100)
        private Double dailyUsagePercent;
        private Double weeklyUsagePercent;
        private Double monthlyUsagePercent;
        private Double annualUsagePercent;
        private Double dailyTopupUsagePercent;
        private Double monthlyTopupUsagePercent;
        
        // Warning flags
        private Boolean dailyLimitWarning;    // >75%
        private Boolean weeklyLimitWarning;
        private Boolean monthlyLimitWarning;
        private Boolean dailyLimitExceeded;   // >=100%
        private Boolean weeklyLimitExceeded;
        private Boolean monthlyLimitExceeded;
    }

    /**
     * MCC and merchant restrictions.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MccRestrictions {
        private List<String> mccWhitelist;
        private List<String> mccBlacklist;
        private List<String> merchantWhitelist;
        private List<String> countryWhitelist;
        
        // Counts for display
        private Integer mccWhitelistCount;
        private Integer mccBlacklistCount;
        private Integer merchantWhitelistCount;
        private Integer countryWhitelistCount;
    }

    // ========================================================================
    // LIST RESPONSE WITH STATS
    // ========================================================================
    
    /**
     * Paginated list response with statistics.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ListResponse {
        private List<Response> accounts;
        private long totalCount;
        private int page;
        private int pageSize;
        private int totalPages;
        private VaStats stats;
    }

    /**
     * Aggregated statistics for VA listing.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VaStats {
        // Counts
        private long totalAccounts;
        private long activeAccounts;
        private long inactiveAccounts;
        private long suspendedAccounts;
        private long blockedAccounts;
        private long expiredAccounts;
        private long pendingActivationAccounts;
        
        // Balances
        private BigDecimal totalBalance;
        private BigDecimal availableBalance;
        private BigDecimal heldBalance;
        private String currencyCode;
        
        // Breakdowns
        private Map<String, Long> countByWalletType;
        private Map<String, Long> countByStatus;
        private Map<String, Long> countByKycLevel;
        private Map<String, BigDecimal> balanceByWalletType;
        
        // KYC stats
        private long kycVerifiedCount;
        private long kycPendingCount;
        private long kycExpiringCount;  // Expiring in 30 days
        
        // Activity stats
        private long activeLastDay;
        private long activeLastWeek;
        private long activeLastMonth;
    }

    // ========================================================================
    // SUMMARY RESPONSE (Lightweight)
    // ========================================================================
    
    /**
     * Lightweight VA response for lists and dropdowns.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Summary {
        private UUID id;
        private String vaNumber;
        private String viban;
        private String vaName;
        private String currencyCode;
        private BigDecimal currentBalance;
        private BigDecimal availableBalance;
        private String status;
        private String statusVariant;
        private String walletType;
        private Boolean kycVerified;
        private Integer kycLevel;
        private String hierarchyPath;
        private LocalDateTime createdAt;
        // OWNERSHIP FIELDS (v4.3)
        private UUID owningEntityId;
        private String owningEntityCode;
    }

    // ========================================================================
    // BALANCE RESPONSE
    // ========================================================================
    
    /**
     * Balance information response.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BalanceResponse {
        private UUID id;
        private String vaNumber;
        private String currencyCode;
        private BigDecimal currentBalance;
        private BigDecimal availableBalance;
        private BigDecimal heldBalance;
        private BigDecimal pendingCredits;
        private BigDecimal pendingDebits;
        private LocalDateTime asOf;
        
        // Points (for LOYALTY type)
        private BigDecimal pointsBalance;
        private BigDecimal pendingPoints;
        private BigDecimal pointsValue;  // Converted to currency
        
        // Activity tracking
        private LocalDate lastActivityDate;
    }

    // ========================================================================
    // SEARCH/FILTER REQUEST
    // ========================================================================
    
    /**
     * Search and filter parameters for VA listing.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchRequest {
        private String query;  // Search in vaNumber, viban, vaName
        private UUID programId;
        private UUID corporateId;
        private UUID physicalAccountId;
        private String status;
        private List<String> statuses;
        private String walletType;
        private List<String> walletTypes;
        private String currencyCode;
        private Integer kycLevel;
        private Boolean kycVerified;
        private UUID hierarchyNodeId;
        private Boolean includeDescendants;  // Include child nodes
        private String collectionChannel;
        private String cardProgramType;
        private String costCenter;
        private String department;
        private String valueType;
        private String loyaltyTier;
        
        // Balance filters
        private BigDecimal minBalance;
        private BigDecimal maxBalance;
        private Boolean hasBalance;
        
        // Date filters
        private LocalDate createdAfter;
        private LocalDate createdBefore;
        private LocalDate expiresAfter;
        private LocalDate expiresBefore;
        private LocalDate lastActivityAfter;
        
        // Pagination
        @Builder.Default
        private Integer page = 0;
        @Builder.Default
        private Integer pageSize = 20;
        @Builder.Default
        private String sortBy = "createdAt";
        @Builder.Default
        private String sortOrder = "desc";
    }

    // ========================================================================
    // BULK OPERATIONS
    // ========================================================================
    
    /**
     * Request for bulk status update.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkStatusUpdateRequest {
        @NotNull(message = "Account IDs are required")
        private List<UUID> accountIds;
        
        @NotBlank(message = "Status is required")
        private String status;
        
        private String reason;
        private String changedBy;
    }

    /**
     * Request for bulk limit update.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkLimitsUpdateRequest {
        @NotNull(message = "Account IDs are required")
        private List<UUID> accountIds;
        
        private BigDecimal perTransactionLimit;
        private BigDecimal dailyLimit;
        private BigDecimal weeklyLimit;
        private BigDecimal monthlyLimit;
        private BigDecimal annualLimit;
        private BigDecimal maxBalance;
    }

    /**
     * Response for bulk operations.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkOperationResponse {
        private int totalRequested;
        private int successCount;
        private int failureCount;
        private List<BulkOperationError> errors;
    }

    /**
     * Error detail for bulk operations.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkOperationError {
        private UUID accountId;
        private String vaNumber;
        private String errorCode;
        private String error;
        
        /**
         * Alias getter for backward compatibility.
         */
        public String getErrorMessage() {
            return error;
        }
    }

    // ========================================================================
    // EXPORT REQUEST
    // ========================================================================
    
    /**
     * Request for exporting VA data.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExportRequest {
        private SearchRequest filters;
        
        /**
         * Format: CSV, XLSX, PDF
         */
        @Builder.Default
        private String format = "CSV";
        
        /**
         * Fields to include in export.
         */
        private List<String> fields;
        
        /**
         * Include balance history.
         */
        @Builder.Default
        private Boolean includeBalanceHistory = false;
        
        /**
         * Include transaction summary.
         */
        @Builder.Default
        private Boolean includeTransactionSummary = false;
    }

    // ========================================================================
    // HIERARCHY-BASED VA CREATION REQUESTS
    // ========================================================================

    // ========================================================================
    // PHASE 1: PUBLISH STATUS DTOs
    // ========================================================================

    /**
     * Publish status information for a VA.
     * Published VAs have VIBANs and can receive external payments.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PublishStatusInfo {
        private UUID id;
        private String vaNumber;
        private String viban;
        private String publishStatus;
        private Boolean isPublished;
        private Boolean canBePublished;
        private Boolean canReceiveExternalPayments;
        private LocalDateTime publishedAt;
        private String publishedBy;
        private LocalDateTime unpublishedAt;
        private String unpublishReason;
    }

    /**
     * Request to publish a VA (assign VIBAN).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PublishRequest {
        /**
         * Optional pre-assigned VIBAN. If null, one will be generated.
         */
        private String viban;

        /**
         * User/system publishing the VA.
         */
        private String publishedBy;
    }

    /**
     * Request to unpublish a VA.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UnpublishRequest {
        /**
         * Reason for unpublishing (required).
         */
        @NotBlank(message = "Reason is required for unpublishing")
        private String reason;
    }

    // ========================================================================
    // HIERARCHY-BASED VA CREATION REQUESTS
    // ========================================================================

    /**
     * Generic request for creating VA with dynamic hierarchy path.
     * The hierarchy dimensions will auto-create nodes as needed.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateWithHierarchyRequest {
        private UUID programId;
        private String currency;
        
        /**
         * Hierarchy dimensions map: "L1" -> "AED", "L2" -> "NORTH", etc.
         * Nodes will be auto-created if they don't exist.
         */
        private Map<String, String> hierarchyDimensions;
        
        private String vaName;
        private String externalReference;
        private String metadata;
        private String walletType;
        private Integer kycLevel;
    }

    /**
     * Collection Program VA request - for receivables/collections.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CollectionVaRequest {
        private String currency;
        private String channel;      // L2: INVOICE, ECOMMERCE, POS, DIRECT
        private String platform;     // L3: AMAZON, NOON, SHOPIFY, etc.
        private String segment;      // L4: SME, ENTERPRISE, RETAIL
        private String customerId;   // L5: Customer identifier
        private String customerName;
        private String accountType;  // L6: RECEIVABLES, REFUNDS, etc.
    }

    /**
     * IHB (In-House Bank) Program VA request.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IhbVaRequest {
        private String currency;
        private String region;       // L2: NORTH, SOUTH, EMEA, APAC
        private String state;        // L3: State/Province/Emirate
        private String city;         // L4: City
        private String entityCode;   // L5: Legal entity code
        private String entityName;
        private String accountType;  // L6: PAYABLES, RECEIVABLES, TAXES, PAYROLL
    }

    /**
     * IHB Current Account creation request.
     *
     * Creates a TRANSACTION VA with ihbParticipant=true that functions as the
     * participant's current account at the In-House Bank (Treasury Center).
     *
     * KEY DESIGN: Uses TRANSACTION category (not INTERCOMPANY) with IHB flag.
     * This allows any operational VA to participate in IHB interest schemes.
     *
     * Features:
     * - Running balance (can go negative for overdraft)
     * - Credit interest on positive balance
     * - Debit interest on negative balance
     * - Overdraft limit (effectiveCreditLimit)
     * - Interest configuration attachment
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IhbCurrentAccountRequest {

        @NotNull(message = "Participant entity ID is required")
        private UUID participantEntityId;

        @NotBlank(message = "Currency is required")
        private String currencyCode;

        /**
         * IHB Program ID. Optional - if not provided, the service will auto-resolve
         * the corporate's active IHB program. If no active IHB program exists for
         * the corporate, an error will be thrown.
         *
         * When provided, the program must be:
         * - An active program (status = ACTIVE)
         * - An IHB program type (programType = IHB)
         * - Belong to the same corporate as the participant entity
         */
        private UUID programId;

        /**
         * Parent hierarchy node ID. Optional - if provided, the IHB Current Account
         * will be placed as a child of this node in the balance hierarchy.
         *
         * This enables the account to appear in the Treasury Hierarchy view.
         * If not provided, the account is created but won't appear in the hierarchy
         * (only in the IHB Current Accounts list).
         */
        private UUID parentNodeId;

        // Optional overrides (defaults come from entity/treasury)
        private BigDecimal creditLimit;       // Override entity's ihbCreditLimit
        private BigDecimal creditRate;        // Override treasury's credit rate
        private BigDecimal debitRate;         // Override treasury's debit rate
        private BigDecimal penaltyRate;       // Override treasury's penalty rate

        // Interest configuration (optional - if provided, rates are from config)
        private UUID interestConfigId;

        // IHB Sweep configuration
        @Builder.Default
        private Boolean ihbSweepEnabled = false;
        private BigDecimal targetCashBalance;  // 0 = sweep all surplus
        private String ihbSweepFrequency;      // DAILY, REAL_TIME

        // VA Name override (optional - defaults to "IHB-{entityCode}-{currency}")
        private String vaName;
    }

    /**
     * IHB Current Account position response.
     * Returns complete IHB participation details for display in frontend cards.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IhbPositionResponse {
        private UUID accountId;
        private String accountNumber;
        private String accountName;
        private String currencyCode;

        // IHB Participation (NEW - for frontend display)
        private Boolean ihbParticipant;
        private LocalDateTime ihbEnabledAt;
        private String ihbEnabledBy;
        private String accountCategory;  // TRANSACTION (not INTERCOMPANY)
        private String status;

        // Participant info
        private UUID participantEntityId;
        private String participantEntityCode;
        private String participantEntityName;

        // Balances
        private BigDecimal currentBalance;
        private BigDecimal availableBalance;   // currentBalance + creditLimit
        private String positionType;           // CREDIT, DEBIT, NEUTRAL

        // Credit/Overdraft
        private BigDecimal creditLimit;
        private BigDecimal creditLimitUsed;    // abs(min(0, currentBalance))
        private BigDecimal creditLimitAvailable;

        // Accrued Interest
        private BigDecimal accruedCreditInterest;
        private BigDecimal accruedDebitInterest;
        private BigDecimal netAccruedInterest;

        // Interest Rates (both naming conventions for frontend compatibility)
        private BigDecimal effectiveCreditRate;
        private BigDecimal effectiveDebitRate;
        private BigDecimal creditRate;   // Alias for effectiveCreditRate
        private BigDecimal debitRate;    // Alias for effectiveDebitRate
        private BigDecimal penaltyRate;  // Penalty rate for overdraft

        // Interest Configuration
        private UUID internalInterestConfigId;

        // IHB Sweep Configuration
        private Boolean ihbSweepEnabled;
        private BigDecimal targetCashBalance;
        private String ihbSweepFrequency;

        // Dates
        private LocalDate lastInterestCalcDate;
        private LocalDate lastInterestPostingDate;

        // Treasury link - VA hierarchy (preferred over treasuryPoolVaId)
        private UUID parentAccountId;         // Links to Treasury's Settlement VA

        /**
         * @deprecated Use parentAccountId instead. Kept for backward compatibility.
         */
        @Deprecated
        private UUID treasuryPoolVaId;
        private String treasuryEntityCode;
    }

    /**
     * IHB interest calculation result.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IhbInterestCalcResult {
        private UUID accountId;
        private String accountNumber;
        private LocalDate calcDate;
        private BigDecimal eodBalance;
        private String positionType;
        private BigDecimal rateApplied;
        private BigDecimal interestAmount;
        private String interestType;  // CREDIT or DEBIT
        private BigDecimal accruedCreditInterest;
        private BigDecimal accruedDebitInterest;
    }

    /**
     * IHB interest posting result.
     * Enhanced with WHT (Withholding Tax) support for cross-border IHB.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IhbInterestPostingResult {
        private UUID accountId;
        private String accountNumber;
        private LocalDate postingDate;

        // Gross interest amounts (before WHT)
        private BigDecimal creditInterestGross;
        private BigDecimal debitInterestGross;

        // WHT amounts (if cross-border)
        private BigDecimal creditInterestWht;
        private BigDecimal debitInterestWht;
        private BigDecimal whtRate;
        private String whtTreatyCode;
        private boolean whtApplied;

        // Net interest amounts (after WHT)
        private BigDecimal creditInterestPosted;  // creditInterestGross - creditInterestWht
        private BigDecimal debitInterestPosted;   // debitInterestGross - debitInterestWht
        private BigDecimal netInterestPosted;     // Final net impact on balance

        private String postingType;  // CREDIT, DEBIT, NONE
        private UUID transactionId;
        private String transactionReference;

        // Jurisdiction info (for audit)
        private String participantJurisdiction;
        private String treasuryJurisdiction;
        private boolean crossBorder;
    }

    /**
     * Wallet Program VA request.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WalletVaRequest {
        private String currency;
        private String userType;     // L2: CONSUMER, MERCHANT, AGENT
        private String region;       // L3: Region
        private String kycTier;      // L4: TIER_1, TIER_2, TIER_3
        private String groupCode;    // L5: Optional grouping
        private String walletType;   // L6: MAIN, SAVINGS, GIFT
        private String customerId;
        private String customerName;
        private UUID holderPartyId;
    }

    /**
     * Escrow Program VA request.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EscrowVaRequest {
        private String currency;
        private String escrowType;    // L2: REAL_ESTATE, TRADE, M_AND_A, PROJECT
        private String transactionId; // L3: Transaction reference
        private String partyRole;     // L4: BUYER, SELLER, AGENT, BROKER
        private String partyName;
    }

    /**
     * VIBAN Program VA request.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VibanVaRequest {
        private String currency;
        private String poolId;        // L2: VIBAN pool identifier
        private String customerType;  // L3: CORPORATE, SME, RETAIL, FINTECH
        private String customerId;    // L4: Customer identifier
        private String customerName;
        private String viban;         // Pre-assigned VIBAN (optional)
    }

    /**
     * Payables Program VA request.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PayablesVaRequest {
        private String currency;
        private String paymentType;    // L2: SUPPLIER, PAYROLL, TAX, UTILITY
        private String entityCode;     // L3: Legal entity
        private String entityName;
        private String costCenter;     // L4: Cost center
        private String beneficiaryType;// L5: DOMESTIC, INTERNATIONAL, INTERNAL
    }
}