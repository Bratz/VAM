package com.bank.vam.dto.treasury;

import com.bank.vam.entity.treasury.IhbDeposit;
import com.bank.vam.entity.treasury.IhbEntity;
import com.bank.vam.entity.treasury.IhbLoan;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * IHB DTOs - Unified with LegalEntity (Phase 2)
 */
public class IhbDto {

    // ========================================================================
    // ENTITY DTOs
    // ========================================================================

    @Data
    public static class EntityResponse {
        private UUID id;
        private String entityCode;
        private String entityName;
        private IhbEntity.EntityType entityType;
        private BigDecimal creditLimit;
        private BigDecimal currentExposure;
        private BigDecimal availableLimit;
        private BigDecimal lendingRateSpread;
        private BigDecimal borrowingRateSpread;
        private Boolean canLend;
        private Boolean canBorrow;
        private String contactName;
        private String contactEmail;
        private IhbEntity.EntityStatus status;
        private LocalDateTime createdAt;
        
        // Phase 2 Unified fields
        private BigDecimal totalLentOut;
        private BigDecimal totalDeposited;
        private BigDecimal netIhbPosition;
        private UUID settlementVaId;
        private String ihbCurrency;
        private BigDecimal utilizationPercent;
        private boolean limitWarning;
        private boolean limitBreached;
    }

    @Data
    public static class CreateEntityRequest {
        private String entityCode;
        private String entityName;
        private IhbEntity.EntityType entityType;
        private BigDecimal creditLimit;
        private BigDecimal lendingRateSpread;
        private BigDecimal borrowingRateSpread;
        private String contactName;
        private String contactEmail;
    }

    // ========================================================================
    // UNIFIED IHB REQUEST DTOs (Phase 2)
    // ========================================================================

    /**
     * Request to enable IHB for a legal entity.
     * ENHANCED: Includes sweep configuration for auto-enrollment
     */
    @Data
    public static class EnableIhbRequest {
        private BigDecimal creditLimit;
        private boolean canLend;
        private boolean canBorrow = true;
        private BigDecimal lendingRateSpread;
        private BigDecimal borrowingRateSpread;
        private UUID settlementVaId;
        
        // ENHANCED: Sweep configuration
        private BigDecimal targetCashBalance;    // 0 = sweep all surplus
        private boolean autoSweepEnabled = true;
        private String sweepFrequency = "DAILY"; // DAILY, REAL_TIME
        private boolean autoEnrollAccounts = true; // Auto-enroll entity's VAs in IHB pool
    }

    /**
     * Request to update IHB settings for an entity.
     */
    @Data
    public static class UpdateIhbSettingsRequest {
        private BigDecimal creditLimit;
        private Boolean canLend;
        private Boolean canBorrow;
        private BigDecimal lendingRateSpread;
        private BigDecimal borrowingRateSpread;
        private UUID settlementVaId;
    }

    // ========================================================================
    // LOAN DTOs
    // ========================================================================

    @Data
    public static class LoanResponse {
        private UUID id;
        private String loanReference;
        private UUID lenderEntityId;
        private String lenderEntityCode;
        private String lenderEntityName;
        private UUID borrowerEntityId;
        private String borrowerEntityCode;
        private String borrowerEntityName;
        private BigDecimal principalAmount;
        private String currencyCode;
        private BigDecimal outstandingAmount;
        private BigDecimal interestRate;
        private IhbLoan.InterestType interestType;
        private String baseRateType;
        private BigDecimal spread;
        private BigDecimal accruedInterest;
        private BigDecimal totalInterestPaid;
        private LocalDate disbursementDate;
        private LocalDate maturityDate;
        private LocalDate nextInterestDate;
        private LocalDate nextPaymentDate;
        private IhbLoan.RepaymentFrequency repaymentFrequency;
        private IhbLoan.LoanStatus status;
        private LocalDateTime createdAt;
    }

    @Data
    public static class CreateLoanRequest {
        private UUID lenderEntityId;
        private UUID borrowerEntityId;
        private BigDecimal principalAmount;
        private String currencyCode;
        private BigDecimal interestRate;
        private IhbLoan.InterestType interestType;
        private String baseRateType;
        private BigDecimal spread;
        private LocalDate disbursementDate;
        private LocalDate maturityDate;
        private IhbLoan.RepaymentFrequency repaymentFrequency;
    }

    /**
     * Unified request to create an IHB loan using LegalEntity references.
     */
    @Data
    public static class CreateLoanUnifiedRequest {
        @NotNull
        private UUID lenderEntityId;
        
        @NotNull
        private UUID borrowerEntityId;
        
        @NotNull
        @DecimalMin(value = "0.01")
        private BigDecimal principalAmount;
        
        private String currencyCode;
        
        @NotNull
        @DecimalMin(value = "0.00")
        private BigDecimal baseRate;
        
        private String baseRateType;
        
        private IhbLoan.InterestType interestType;
        
        private LocalDate disbursementDate;
        
        @NotNull
        private LocalDate maturityDate;
        
        private IhbLoan.RepaymentFrequency repaymentFrequency;
        
        // ENHANCED: Interest configuration attachment
        private UUID interestConfigId;  // If provided, rates are looked up from config
    }

    @Data
    public static class LoanRepaymentRequest {
        private BigDecimal amount;
        private boolean includeInterest;
    }

    // ========================================================================
    // DEPOSIT DTOs
    // ========================================================================

    @Data
    public static class DepositResponse {
        private UUID id;
        private String depositReference;
        private UUID depositorEntityId;
        private String depositorEntityCode;
        private String depositorEntityName;
        private BigDecimal principalAmount;
        private String currencyCode;
        private BigDecimal currentBalance;
        private BigDecimal interestRate;
        private BigDecimal accruedInterest;
        private BigDecimal totalInterestEarned;
        private LocalDate depositDate;
        private LocalDate maturityDate;
        private LocalDate lastInterestDate;
        private IhbDeposit.DepositType depositType;
        private Integer noticePeriodDays;
        private IhbDeposit.DepositStatus status;
        private LocalDateTime createdAt;
    }

    @Data
    public static class CreateDepositRequest {
        private UUID depositorEntityId;
        private BigDecimal principalAmount;
        private String currencyCode;
        private BigDecimal interestRate;
        private LocalDate depositDate;
        private LocalDate maturityDate;
        private IhbDeposit.DepositType depositType;
        private Integer noticePeriodDays;
    }

    /**
     * Unified request to create an IHB deposit using LegalEntity references.
     */
    @Data
    public static class CreateDepositUnifiedRequest {
        @NotNull
        private UUID depositorEntityId;
        
        private UUID treasuryEntityId; // Optional - auto-resolved if null
        
        @NotNull
        @DecimalMin(value = "0.01")
        private BigDecimal principalAmount;
        
        private String currencyCode;
        
        private BigDecimal interestRate;
        
        private BigDecimal baseRate;
        
        private LocalDate depositDate;
        
        private LocalDate maturityDate;
        
        private IhbDeposit.DepositType depositType;
        
        private Integer noticePeriodDays;
        
        // ENHANCED: Interest configuration attachment
        private UUID interestConfigId;  // If provided, rates are looked up from config
    }

    @Data
    public static class WithdrawRequest {
        private BigDecimal amount;
        private boolean breakDeposit;
    }

    // ========================================================================
    // STATS & POSITION DTOs
    // ========================================================================

    @Data
    public static class IhbStatsResponse {
        private Long totalEntities;
        private int activeLoans;
        private int activeDeposits;
        private BigDecimal totalOutstandingLoans;
        private BigDecimal totalDepositsBalance;
        private BigDecimal netPosition;
        
        // Phase 2 fields
        private BigDecimal totalAccruedLoanInterest;
        private BigDecimal totalAccruedDepositInterest;
    }

    @Data
    public static class EntityPositionResponse {
        private UUID entityId;
        private String entityCode;
        private String entityName;
        private BigDecimal totalLentOut;
        private BigDecimal totalBorrowed;
        private BigDecimal totalDeposited;
        private BigDecimal netPosition;
        
        // Phase 2 fields
        private BigDecimal ihbCreditLimit;
        private BigDecimal ihbAvailableLimit;
        private BigDecimal utilizationPercent;
        
        private List<LoanResponse> loansAsLender;
        private List<LoanResponse> loansAsBorrower;
        private List<DepositResponse> deposits;
    }

    @Data
    public static class CalculateInterestRequest {
        private LocalDate calculationDate;
    }

    @Data
    public static class CalculateInterestResponse {
        private LocalDate calculationDate;
        private int loansProcessed;
        private int depositsProcessed;
        private BigDecimal totalLoanInterest;
        private BigDecimal totalDepositInterest;
        
        // Phase 2 fields
        private BigDecimal totalSpread;
        private BigDecimal netInterest;
    }

    // ========================================================================
    // TREASURY RATES DTOs (for IHB participation visibility)
    // ========================================================================

    /**
     * Treasury Center's offered rates for IHB participation.
     * Shown to entities considering joining IHB scheme.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TreasuryRatesResponse {
        private UUID treasuryCenterId;
        private String treasuryCenterCode;
        private String treasuryCenterName;
        private String ihbCurrency;
        
        // Lending rates (what borrowers pay)
        private BigDecimal lendingBaseRate;
        private String lendingBaseRateType;      // e.g., "EIBOR", "SOFR"
        private BigDecimal treasuryLendingSpread; // Treasury's spread
        private BigDecimal indicativeLendingRate; // Base + Treasury spread
        
        // Deposit rates (what depositors earn)
        private BigDecimal depositBaseRate;
        private String depositBaseRateType;
        private BigDecimal treasuryDepositSpread; // Treasury's spread (usually negative)
        private BigDecimal indicativeDepositRate; // Base - Treasury spread
        
        // Terms
        private String dayCountConvention;
        private String compoundingFrequency;
        private String settlementFrequency;
        
        // Limits
        private BigDecimal minLoanAmount;
        private BigDecimal maxLoanAmount;
        private BigDecimal minDepositAmount;
        
        // Interest Configuration reference (NEW)
        private UUID interestConfigId;           // The attached InterestConfiguration
        private boolean hasInterestConfig;       // Whether rates come from config or defaults
        
        // Legacy fields (deprecated, kept for backward compatibility)
        @Deprecated
        private UUID lendingInterestConfigId;
        @Deprecated
        private UUID depositInterestConfigId;
        
        // Effective date
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
    }

    /**
     * Request to get indicative rate for a specific amount.
     */
    @Data
    public static class IndicativeRateRequest {
        private UUID treasuryCenterId;
        private BigDecimal amount;
        private String currency;
        private String rateType; // LENDING or DEPOSIT
        private Integer tenorDays;
    }

    /**
     * Response with indicative rate calculation.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IndicativeRateResponse {
        private BigDecimal baseRate;
        private String baseRateType;
        private BigDecimal treasurySpread;
        private BigDecimal entitySpread;      // Borrower's spread (for loans)
        private BigDecimal effectiveRate;
        private BigDecimal estimatedInterest; // For the tenor
        private String currency;
        private Integer tenorDays;
        private String rateType;
    }

    // ========================================================================
    // IHB CURRENT ACCOUNT DTOs
    // ========================================================================

    /**
     * Request for deposit/withdraw operations on IHB Current Account.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CurrentAccountTransactionRequest {
        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be positive")
        private BigDecimal amount;

        private String description;
        private String externalReference;
    }

    /**
     * Request for transfer between IHB Current Accounts.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CurrentAccountTransferRequest {
        @NotNull(message = "From account ID is required")
        private UUID fromAccountId;

        @NotNull(message = "To account ID is required")
        private UUID toAccountId;

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be positive")
        private BigDecimal amount;

        private String description;
        private String externalReference;
    }

    /**
     * Response for transfer between IHB Current Accounts.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CurrentAccountTransferResponse {
        private String correlationId;
        private UUID fromAccountId;
        private String fromAccountNumber;
        private UUID toAccountId;
        private String toAccountNumber;
        private BigDecimal amount;
        private String currencyCode;

        // Post-transfer balances
        private BigDecimal fromAccountNewBalance;
        private BigDecimal toAccountNewBalance;

        private UUID debitTransactionId;
        private UUID creditTransactionId;
        private LocalDateTime transferDate;
    }
}