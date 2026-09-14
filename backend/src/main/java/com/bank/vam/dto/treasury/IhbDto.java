package com.bank.vam.dto.treasury;

import com.bank.vam.entity.treasury.IhbEntity;

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
    // STATS & POSITION DTOs
    // ========================================================================

    @Data
    public static class IhbStatsResponse {
        private Long totalEntities;
        private BigDecimal netPosition;
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