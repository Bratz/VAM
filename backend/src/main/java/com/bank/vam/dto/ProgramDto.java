package com.bank.vam.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Program DTOs for Virtual Account Programs Management
 * 
 * Aligned with unified_programs schema - includes:
 * - Core program fields
 * - Hierarchy support (7-level)
 * - VIBAN pool settings
 * - Balance aggregation settings
 * - Wallet configuration
 * - Additional program types (Loyalty, Gift Card, Corporate Card, Mobile Money)
 * 
 * Program Types:
 * - COLLECTION: Receivables collection
 * - VIBAN: Virtual IBAN assignment
 * - ESCROW: Digital escrow management
 * - WALLET: Prepaid wallet programs
 * - IHB: In-house banking
 * - PAYABLES: Payables management
 * - RECEIVABLES: Receivables management
 * - LOYALTY: Loyalty/rewards programs
 * - GIFT_CARD: Gift card programs
 * - CORPORATE_CARD: Corporate card programs
 * - MOBILE_MONEY: Mobile money/agent programs
 */
public class ProgramDto {

    // ========================================================================
    // ENUMS
    // ========================================================================

    public enum ProgramStatus {
        ACTIVE("Active", "success"),
        INACTIVE("Inactive", "neutral"),
        SUSPENDED("Suspended", "warning"),
        PENDING_APPROVAL("Pending Approval", "info"),
        CLOSED("Closed", "error");

        private final String label;
        private final String variant;

        ProgramStatus(String label, String variant) {
            this.label = label;
            this.variant = variant;
        }

        public String getLabel() { return label; }
        public String getVariant() { return variant; }
    }

    public enum VibanGenerationStrategy {
        SEQUENTIAL("Sequential", "Sequential numbering"),
        RANDOM("Random", "Random generation"),
        HIERARCHY_ENCODED("Hierarchy Encoded", "Encodes hierarchy path in VIBAN");

        private final String label;
        private final String description;

        VibanGenerationStrategy(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public String getLabel() { return label; }
        public String getDescription() { return description; }
    }

    // ========================================================================
    // RESPONSE DTOs
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProgramResponse {
        private UUID id;
        private String programCode;
        private String programName;
        private String description;

        // Relationships
        private UUID corporateId;
        private String corporateName;
        private UUID physicalAccountId;
        private String physicalAccountNumber;
        private String currencyCode;

        // VA Configuration
        private String vaPrefix;
        private String vaFormat;
        private Integer maxVirtualAccounts;
        private Integer currentVaCount;

        // Settlement

        // Feature Flags - Core

        // ====================================================================
        // HIERARCHY SUPPORT
        // ====================================================================
        private Integer hierarchyDepth;
        /** Level cap for this program's hierarchy (defaults to 20) — the UI
         *  level editor honors this instead of any hardcoded count. */
        private Integer maxHierarchyDepth;
        private String defaultHierarchyTemplate;
        private UUID rootHierarchyNodeId;

        // ====================================================================
        // VIBAN POOL SETTINGS
        // ====================================================================
        private UUID defaultVibanPoolId;
        private String vibanGenerationStrategy;
        private String vibanPrefix;
        private String vibanBankCode;

        // ====================================================================
        // BALANCE AGGREGATION SETTINGS
        // ====================================================================

        // ====================================================================
        // ADDITIONAL PROGRAM TYPE FLAGS
        // ====================================================================

        // ====================================================================
        // WALLET CONFIGURATION
        // ====================================================================
        private String defaultWalletType;
        
        // Default Limits
        private BigDecimal defaultPerTransactionLimit;
        private BigDecimal defaultDailyLimit;
        private BigDecimal defaultWeeklyLimit;
        private BigDecimal defaultMonthlyLimit;
        private BigDecimal defaultYearlyLimit;
        private BigDecimal defaultMaxBalance;
        private BigDecimal defaultDailyTopupLimit;
        private BigDecimal defaultMonthlyTopupLimit;

        // Wallet Settings
        private BigDecimal minTopup;
        private BigDecimal maxTopup;
        private Integer walletExpiryDays;

        // KYC Settings
        private Boolean kycRequired;
        private Integer minKycLevel;
        private Boolean autoKyc;

        // Wallet Features
        private Boolean allowTopup;
        private Boolean allowWithdrawal;
        private Boolean allowTransfer;

        // Fees
        private BigDecimal issuanceFee;
        private BigDecimal monthlyFee;
        private BigDecimal topupFeePercent;
        private BigDecimal topupFeeFlat;
        private BigDecimal withdrawalFeePercent;
        private BigDecimal withdrawalFeeFlat;
        private BigDecimal transferFeePercent;
        private BigDecimal transferFeeFlat;

        // Branding

        // Status
        private String status;
        private String statusLabel;
        private String statusVariant;

        // Effective Dates
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;

        // Statistics
        private Integer virtualAccountCount;
        private Integer activeVirtualAccountCount;
        private BigDecimal totalBalance;

        // Metadata
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private String createdBy;
        private String updatedBy;
        private Long version;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProgramDetailResponse {
        private ProgramResponse program;
        private CorporateInfo corporate;
        private PhysicalAccountInfo physicalAccount;
        private HierarchyInfo hierarchy;
        private VibanPoolInfo vibanPool;
        private List<VirtualAccountSummary> recentVirtualAccounts;
        private ProgramUsageStats usageStats;
        private List<ActivityLogEntry> activityLog;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CorporateInfo {
        private UUID id;
        private String corporateId;
        private String legalName;
        private String tradeName;
        private String status;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PhysicalAccountInfo {
        private UUID id;
        private String accountNumber;
        private String accountName;
        private String bankName;
        private String currencyCode;
        private BigDecimal currentBalance;
        private String status;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HierarchyInfo {
        private Boolean enabled;
        private Integer depth;
        private String template;
        private UUID rootNodeId;
        private String rootNodeName;
        private Integer totalNodes;
        private Integer leafNodes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VibanPoolInfo {
        private UUID poolId;
        private String poolName;
        private String generationStrategy;
        private String prefix;
        private Integer totalVibans;
        private Integer availableVibans;
        private Integer assignedVibans;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VirtualAccountSummary {
        private UUID id;
        private String vaNumber;
        private String viban;
        private String vaName;
        private BigDecimal currentBalance;
        private String status;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProgramUsageStats {
        private Integer totalTransactions;
        private Integer todayTransactions;
        private BigDecimal totalVolume;
        private BigDecimal todayVolume;
        private BigDecimal averageBalance;
        private LocalDateTime lastTransactionAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActivityLogEntry {
        private String action;
        private String user;
        private LocalDateTime timestamp;
        private String type; // success, info, warning, error
        private String details;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProgramStatsResponse {
        private Long totalPrograms;
        private Long activePrograms;
        private Long inactivePrograms;
        private Long suspendedPrograms;
        private Long pendingPrograms;

        // By Feature. These replace the old per-programType counters: the type
        // was dropped, and every one of its values that carried real meaning had
        // a feature flag saying the same thing. COLLECTION/PAYABLES/RECEIVABLES
        // had no flag because they are the base case -- a plain VA program --
        // and so have no counter here.

        // Totals
        private Long totalVirtualAccounts;
        /** All program balances converted to {@link #reportingCurrency} at mid rates. */
        private BigDecimal totalBalance;
        private String reportingCurrency;
        /** Unconverted balances per program currency. */
        private java.util.Map<String, BigDecimal> balancesByCurrency;
        /** Currencies with no FX rate to the reporting currency (left out of totalBalance). */
        private List<String> excludedCurrencies;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProgramListResponse {
        private List<ProgramResponse> programs;
        private Long totalCount;
        private Integer page;
        private Integer pageSize;
        private ProgramStatsResponse stats;
    }

    // ========================================================================
    // REQUEST DTOs
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateProgramRequest {
        // Core Fields
        private String programCode;
        private String programName;
        private String description;

        // Required relationships
        private UUID corporateId;
        private UUID physicalAccountId;
        /** Shadow accounts of home-bank accounts the program runs on; the first is its main backing account. */
        private List<UUID> shadowAccountIds;
        private String currencyCode;

        // VA Configuration
        private String vaPrefix;
        private String vaFormat;
        private Integer maxVirtualAccounts;

        // Settlement

        // Feature Flags

        // Hierarchy Settings
        private Integer hierarchyDepth;
        private String defaultHierarchyTemplate;

        // VIBAN Settings
        private UUID defaultVibanPoolId;
        private String vibanGenerationStrategy;
        private String vibanPrefix;
        private String vibanBankCode;

        // Balance Aggregation

        // Wallet Configuration
        private String defaultWalletType;
        private BigDecimal defaultPerTransactionLimit;
        private BigDecimal defaultDailyLimit;
        private BigDecimal defaultWeeklyLimit;
        private BigDecimal defaultMonthlyLimit;
        private BigDecimal defaultYearlyLimit;
        private BigDecimal defaultMaxBalance;
        private BigDecimal defaultDailyTopupLimit;
        private BigDecimal defaultMonthlyTopupLimit;

        // Wallet Settings
        private BigDecimal minTopup;
        private BigDecimal maxTopup;
        private Integer walletExpiryDays;

        // KYC Settings
        private Boolean kycRequired;
        private Integer minKycLevel;
        private Boolean autoKyc;

        // Wallet Features
        private Boolean allowTopup;
        private Boolean allowWithdrawal;
        private Boolean allowTransfer;

        // Fees
        private BigDecimal issuanceFee;
        private BigDecimal monthlyFee;
        private BigDecimal topupFeePercent;
        private BigDecimal topupFeeFlat;
        private BigDecimal withdrawalFeePercent;
        private BigDecimal withdrawalFeeFlat;
        private BigDecimal transferFeePercent;
        private BigDecimal transferFeeFlat;

        // Branding

        // Effective Dates
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateProgramRequest {
        private String programName;
        private String description;
        /** When present, the program's full set of bank-account shadows (null leaves them as they are). */
        private List<UUID> shadowAccountIds;

        // VA Configuration
        private String vaPrefix;
        private String vaFormat;
        private Integer maxVirtualAccounts;

        // Settlement

        // Feature Flags

        // Hierarchy Settings
        private Integer hierarchyDepth;
        private String defaultHierarchyTemplate;

        // VIBAN Settings
        private UUID defaultVibanPoolId;
        private String vibanGenerationStrategy;
        private String vibanPrefix;
        private String vibanBankCode;

        // Balance Aggregation

        // Wallet Configuration
        private String defaultWalletType;
        private BigDecimal defaultPerTransactionLimit;
        private BigDecimal defaultDailyLimit;
        private BigDecimal defaultWeeklyLimit;
        private BigDecimal defaultMonthlyLimit;
        private BigDecimal defaultYearlyLimit;
        private BigDecimal defaultMaxBalance;

        // KYC Settings
        private Boolean kycRequired;
        private Integer minKycLevel;
        private Boolean autoKyc;

        // Wallet Features
        private Boolean allowTopup;
        private Boolean allowWithdrawal;
        private Boolean allowTransfer;

        // Status
        private String status;

        // Effective Dates
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateProgramStatusRequest {
        private String status;
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProgramSearchRequest {
        private String query;
        private String status;
        private UUID corporateId;
        private String currencyCode;
        private Integer page;
        private Integer pageSize;
        private String sortBy;
        private String sortOrder;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CloneProgramRequest {
        private String newProgramCode;
        private String newProgramName;
        private UUID targetCorporateId;
        private UUID targetPhysicalAccountId;
        private Boolean cloneHierarchy;
        private Boolean cloneVibanPool;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EnableHierarchyRequest {
        private Integer hierarchyDepth;
        private String template;
    }
}