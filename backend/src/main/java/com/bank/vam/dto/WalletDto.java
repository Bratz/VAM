package com.bank.vam.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Wallet DTOs - Request/Response objects for Wallet APIs.
 * 
 * Uses existing domain model:
 * - VirtualAccount (with walletType) as Wallet
 * - Transaction for wallet operations
 * - Party as wallet holder
 * - Program (with walletEnabled) for wallet program config
 */
public class WalletDto {

    // ========================================================================
    // STATS RESPONSE
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WalletStatsResponse {
        // Programs
        private Integer totalPrograms;
        private Integer activePrograms;
        
        // Wallets
        private Long totalWallets;
        private Long activeWallets;
        private Long suspendedWallets;
        private Long blockedWallets;
        
        // Balances
        private BigDecimal totalBalance;
        private BigDecimal totalAvailableBalance;
        
        // Volume
        private BigDecimal monthlyVolume;
        private BigDecimal dailyVolume;
        private Long todayTransactions;
        private Long monthlyTransactions;
        
        // KYC
        private Long kycVerifiedCount;
        private Long kycPendingCount;
        
        // Limits
        private BigDecimal totalDailyLimitUsed;
        private BigDecimal totalMonthlyLimitUsed;
    }

    // ========================================================================
    // PROGRAM RESPONSES
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WalletProgramResponse {
        private UUID id;
        private String programCode;
        private String programName;
        private String operatorName;          // Corporate/operator name
        private UUID corporateId;
        private String currency;
        
        // Wallet counts
        private Long activeWallets;
        private Long totalWallets;
        
        // Balances
        private BigDecimal totalBalance;
        private BigDecimal totalAvailableBalance;
        
        // Limits
        private BigDecimal dailySpendLimit;
        private BigDecimal monthlySpendLimit;
        private BigDecimal maxBalance;
        private BigDecimal minTopup;
        private BigDecimal maxTopup;
        
        // Settings
        private Boolean kycRequired;
        private Boolean autoKyc;
        private Integer expiryDays;
        
        // Status
        private String status;                // ACTIVE, SUSPENDED, CLOSED
        private LocalDate launchDate;
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
        
        // Timestamps
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WalletProgramDetailResponse {
        private UUID id;
        private String programCode;
        private String programName;
        private String description;
        private String operatorName;
        private UUID corporateId;
        private UUID physicalAccountId;
        private String physicalAccountNumber;
        private String currency;
        
        // Wallet counts
        private Long activeWallets;
        private Long suspendedWallets;
        private Long blockedWallets;
        private Long totalWallets;
        
        // Balances
        private BigDecimal totalBalance;
        private BigDecimal totalAvailableBalance;
        
        // Limits
        private BigDecimal dailySpendLimit;
        private BigDecimal monthlySpendLimit;
        private BigDecimal dailyTopupLimit;
        private BigDecimal monthlyTopupLimit;
        private BigDecimal maxBalance;
        private BigDecimal minTopup;
        private BigDecimal maxTopup;
        private BigDecimal minWithdrawal;
        private BigDecimal maxWithdrawal;
        
        // Settings
        private Boolean kycRequired;
        private Boolean autoKyc;
        private Integer expiryDays;
        private Boolean allowTransfers;
        private Boolean allowWithdrawals;
        private Boolean allowTopups;
        
        // VA Settings
        private String vaPrefix;
        private String vaFormat;
        private Integer maxVirtualAccounts;
        
        // Status
        private String status;
        private LocalDate launchDate;
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
        
        // Volume stats
        private BigDecimal monthlyVolume;
        private Long monthlyTransactions;
        
        // Timestamps
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        
        // Recent wallets
        private List<WalletAccountResponse> recentWallets;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WalletProgramStatsResponse {
        private UUID programId;
        private String programCode;
        private String programName;
        
        // Wallet counts
        private Long activeWallets;
        private Long suspendedWallets;
        private Long blockedWallets;
        private Long totalWallets;
        
        // Balances
        private BigDecimal totalBalance;
        private BigDecimal averageBalance;
        
        // Volume
        private BigDecimal dailyVolume;
        private BigDecimal weeklyVolume;
        private BigDecimal monthlyVolume;
        private Long dailyTransactions;
        private Long weeklyTransactions;
        private Long monthlyTransactions;
        
        // KYC
        private Long kycVerifiedCount;
        private Long kycPendingCount;
        
        // Top wallets by balance
        private List<WalletAccountResponse> topWalletsByBalance;
        
        // Top wallets by activity
        private List<WalletAccountResponse> topWalletsByActivity;
    }

    // ========================================================================
    // WALLET ACCOUNT RESPONSES
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WalletAccountResponse {
        private UUID id;
        private String walletReference;       // VA number
        private String walletName;
        
        // Holder info
        private String holderName;
        private String holderMobile;
        private String holderEmail;
        private UUID partyId;                 // Link to Party master
        
        // Program info
        private UUID programId;
        private String programName;
        private String programCode;
        
        // Balances
        private BigDecimal currentBalance;
        private BigDecimal availableBalance;
        private String currency;
        
        // Usage
        private BigDecimal dailySpent;
        private BigDecimal monthlySpent;
        private BigDecimal dailyLimit;
        private BigDecimal monthlyLimit;
        
        // Status
        private String status;                // ACTIVE, SUSPENDED, BLOCKED
        private Boolean kycVerified;
        private String kycStatus;
        
        // Activity
        private LocalDateTime lastTransaction;
        private Integer transactionCount;
        
        // Timestamps
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WalletAccountDetailResponse {
        private UUID id;
        private String walletReference;
        private String walletName;
        private String viban;
        
        // Holder info
        private String holderName;
        private String holderMobile;
        private String holderEmail;
        private UUID partyId;
        private String partyCode;
        
        // Program info
        private UUID programId;
        private String programName;
        private String programCode;
        private String operatorName;
        
        // Corporate info
        private UUID corporateId;
        private String corporateName;
        
        // Physical account
        private UUID physicalAccountId;
        private String physicalAccountNumber;
        
        // Balances
        private BigDecimal currentBalance;
        private BigDecimal availableBalance;
        private BigDecimal blockedBalance;
        private String currency;
        
        // Usage
        private BigDecimal dailySpent;
        private BigDecimal weeklySpent;
        private BigDecimal monthlySpent;
        private BigDecimal yearlySpent;
        
        // Limits
        private BigDecimal dailyLimit;
        private BigDecimal weeklyLimit;
        private BigDecimal monthlyLimit;
        private BigDecimal yearlyLimit;
        private BigDecimal perTransactionLimit;
        
        // Status
        private String status;
        private Boolean kycVerified;
        private String kycStatus;
        private LocalDateTime kycVerifiedAt;
        
        // Activity
        private LocalDateTime lastTransaction;
        private LocalDateTime lastTopup;
        private LocalDateTime lastWithdrawal;
        private Integer transactionCount;
        private Integer topupCount;
        private Integer withdrawalCount;
        
        // External
        private String externalReference;
        private String metadata;
        
        // Timestamps
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private LocalDate expiresAt;
        
        // Recent transactions
        private List<WalletTransactionResponse> recentTransactions;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WalletTransactionResponse {
        private UUID id;
        private String referenceNumber;
        private String type;                  // TOPUP, WITHDRAWAL, TRANSFER_IN, TRANSFER_OUT, PURCHASE
        private BigDecimal amount;
        private String currency;
        private BigDecimal balanceBefore;
        private BigDecimal balanceAfter;
        private String description;
        private String status;
        private LocalDateTime transactionDate;
        private String counterpartyName;
        private String counterpartyAccount;
    }

    // ========================================================================
    // LIST RESPONSE (with pagination)
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WalletListResponse {
        private List<WalletAccountResponse> content;
        private Integer page;
        private Integer pageSize;
        private Long totalElements;
        private Integer totalPages;
        private WalletListSummary summary;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WalletListSummary {
        private Long activeCount;
        private Long suspendedCount;
        private Long blockedCount;
        private BigDecimal totalBalance;
        private Long kycVerifiedCount;
        private Long kycPendingCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProgramListResponse {
        private List<WalletProgramResponse> content;
        private Integer page;
        private Integer pageSize;
        private Long totalElements;
        private Integer totalPages;
    }

    // ========================================================================
    // CREATE / UPDATE REQUESTS
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateProgramRequest {
        private String programCode;
        private String programName;
        private String description;
        private UUID corporateId;
        private UUID physicalAccountId;
        private String currency;
        
        // Limits
        private BigDecimal dailySpendLimit;
        private BigDecimal monthlySpendLimit;
        private BigDecimal maxBalance;
        private BigDecimal minTopup;
        private BigDecimal maxTopup;
        
        // Settings
        private Boolean kycRequired;
        private Integer expiryDays;
        private Boolean allowTransfers;
        private Boolean allowWithdrawals;
        
        // VA settings
        private String vaPrefix;
        private String vaFormat;
        private Integer maxVirtualAccounts;
        
        // Dates
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
        
        // Limits
        private BigDecimal dailySpendLimit;
        private BigDecimal monthlySpendLimit;
        private BigDecimal maxBalance;
        private BigDecimal minTopup;
        private BigDecimal maxTopup;
        
        // Settings
        private Boolean kycRequired;
        private Integer expiryDays;
        private Boolean allowTransfers;
        private Boolean allowWithdrawals;
        
        private LocalDate effectiveTo;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IssueWalletRequest {
        private UUID programId;
        
        // Holder info
        private String holderName;
        private String holderMobile;
        private String holderEmail;
        private UUID partyId;                 // Optional - link to existing party
        
        // Initial settings
        private BigDecimal initialLoadAmount;
        private BigDecimal dailyLimit;
        private BigDecimal monthlyLimit;
        
        // Optional
        private String externalReference;
        private String metadata;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateWalletRequest {
        private String walletName;
        private String holderEmail;
        private BigDecimal dailyLimit;
        private BigDecimal monthlyLimit;
        private BigDecimal perTransactionLimit;
        private String metadata;
    }

    // ========================================================================
    // OPERATION REQUESTS
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoadFundsRequest {
        private BigDecimal amount;
        private String source;                // BANK_TRANSFER, CARD, CASH, INTERNAL
        private String sourceReference;
        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoadFundsResponse {
        private UUID walletId;
        private String walletReference;
        private BigDecimal previousBalance;
        private BigDecimal loadAmount;
        private BigDecimal newBalance;
        private String referenceNumber;
        private String status;
        private LocalDateTime timestamp;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WithdrawRequest {
        private BigDecimal amount;
        private String destination;           // BANK_ACCOUNT, CASH, INTERNAL
        private String destinationReference;
        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WithdrawResponse {
        private UUID walletId;
        private String walletReference;
        private BigDecimal previousBalance;
        private BigDecimal withdrawAmount;
        private BigDecimal newBalance;
        private String referenceNumber;
        private String status;
        private LocalDateTime timestamp;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransferRequest {
        private UUID toWalletId;
        private String toWalletReference;     // Alternative to ID
        private BigDecimal amount;
        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransferResponse {
        private UUID fromWalletId;
        private String fromWalletReference;
        private UUID toWalletId;
        private String toWalletReference;
        private BigDecimal amount;
        private BigDecimal fromPreviousBalance;
        private BigDecimal fromNewBalance;
        private BigDecimal toPreviousBalance;
        private BigDecimal toNewBalance;
        private String referenceNumber;
        private String status;
        private LocalDateTime timestamp;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkLoadRequest {
        private UUID programId;
        private List<BulkLoadItem> loads;
        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkLoadItem {
        private UUID walletId;
        private String walletReference;
        private BigDecimal amount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkLoadResponse {
        private Integer totalCount;
        private Integer successCount;
        private Integer failedCount;
        private BigDecimal totalAmount;
        private BigDecimal successAmount;
        private List<BulkLoadResult> results;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkLoadResult {
        private UUID walletId;
        private String walletReference;
        private BigDecimal amount;
        private String status;                // SUCCESS, FAILED
        private String referenceNumber;
        private String errorMessage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateLimitsRequest {
        private BigDecimal dailyLimit;
        private BigDecimal weeklyLimit;
        private BigDecimal monthlyLimit;
        private BigDecimal yearlyLimit;
        private BigDecimal perTransactionLimit;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SuspendRequest {
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReactivateRequest {
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BlockRequest {
        private String reason;
        private Boolean permanent;
    }

    // ========================================================================
    // SEARCH / FILTER REQUEST
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WalletSearchRequest {
        private String query;                 // Search by reference, name, mobile
        private UUID programId;
        private String status;                // ACTIVE, SUSPENDED, BLOCKED
        private Boolean kycVerified;
        private BigDecimal minBalance;
        private BigDecimal maxBalance;
        private LocalDate createdFrom;
        private LocalDate createdTo;
        private Integer page;
        private Integer pageSize;
        private String sortBy;
        private String sortOrder;
    }

    // ========================================================================
    // KYC REQUESTS
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VerifyKycRequest {
        private String verificationMethod;    // DOCUMENT, BIOMETRIC, MANUAL
        private String documentType;          // EMIRATES_ID, PASSPORT, DRIVING_LICENSE
        private String documentNumber;
        private String documentExpiryDate;
        private String verifiedBy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KycVerificationResponse {
        private UUID walletId;
        private String walletReference;
        private Boolean kycVerified;
        private String kycStatus;
        private LocalDateTime kycVerifiedAt;
        private String verifiedBy;
        private LocalDate kycExpiresAt;
    }

    // ========================================================================
    // EXPORT / REPORT REQUEST
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExportRequest {
        private String format;                // CSV, XLSX, PDF
        private UUID programId;
        private String status;
        private LocalDate dateFrom;
        private LocalDate dateTo;
        private Boolean includeTransactions;
    }

    // ========================================================================
    // LEGACY DTOs (backward compatibility)
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateWalletRequest {
        private UUID corporateId;
        private UUID physicalAccountId;
        private String walletName;
        private String walletType;
        private String currencyCode;
        private String viban;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopUpRequest {
        private BigDecimal amount;
        private String source;
        private String reference;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SimpleWithdrawRequest {
        private BigDecimal amount;
        private String destination;
        private String reference;
    }
}