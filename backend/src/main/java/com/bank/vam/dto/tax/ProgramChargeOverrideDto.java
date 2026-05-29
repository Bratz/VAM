package com.bank.vam.dto.tax;

import com.bank.vam.entity.tax.ChargeConfiguration;
import com.bank.vam.entity.tax.ProgramChargeOverride;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * DTOs for Program Charge Override operations.
 * 
 * Updated to support ALL wallet fee waivers:
 * - Transaction fees: topup, withdrawal, transfer
 * - Fixed fees: issuance, monthly, inactivity
 */
public class ProgramChargeOverrideDto {

    // ========================================================================
    // REQUEST DTOs
    // ========================================================================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateOverrideRequest {
        private UUID programId;
        private String chargeCode;
        private BigDecimal overridePercentage;
        private BigDecimal overrideFixed;
        private BigDecimal overrideMinimum;
        private BigDecimal overrideMaximum;
        private Boolean isWaived;
        private String waiverReason;
        private String waiverApprovedBy;
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
        private String notes;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UpdateOverrideRequest {
        private BigDecimal overridePercentage;
        private BigDecimal overrideFixed;
        private BigDecimal overrideMinimum;
        private BigDecimal overrideMaximum;
        private Boolean isWaived;
        private String waiverReason;
        private String waiverApprovedBy;
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
        private String notes;
        private ProgramChargeOverride.OverrideStatus status;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class BulkOverrideRequest {
        private UUID programId;
        private List<SingleOverride> overrides;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SingleOverride {
        private String chargeCode;
        private BigDecimal overridePercentage;
        private BigDecimal overrideFixed;
        private Boolean isWaived;
    }

    // ========================================================================
    // RESPONSE DTOs
    // ========================================================================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class OverrideResponse {
        private UUID id;
        private UUID programId;
        private String programCode;
        private String programName;
        private String chargeCode;
        private String chargeName;
        private ChargeConfiguration.ChargeType chargeType;
        private ChargeConfiguration.ChargeCategory chargeCategory;
        
        // Base config values
        private BigDecimal basePercentage;
        private BigDecimal baseFixed;
        private BigDecimal baseMinimum;
        private BigDecimal baseMaximum;
        
        // Override values
        private BigDecimal overridePercentage;
        private BigDecimal overrideFixed;
        private BigDecimal overrideMinimum;
        private BigDecimal overrideMaximum;
        
        // Effective values (override or base)
        private BigDecimal effectivePercentage;
        private BigDecimal effectiveFixed;
        private BigDecimal effectiveMinimum;
        private BigDecimal effectiveMaximum;
        
        // Waiver
        private Boolean isWaived;
        private String waiverReason;
        private String waiverApprovedBy;
        
        // Validity
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
        private String status;
        
        // Audit
        private String notes;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ProgramChargesResponse {
        private UUID programId;
        private String programCode;
        private String programName;
        private List<ChargeWithOverride> charges;
        private int totalCharges;
        private int overriddenCharges;
        private int waivedCharges;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ChargeWithOverride {
        private String chargeCode;
        private String chargeName;
        private ChargeConfiguration.ChargeType chargeType;
        private ChargeConfiguration.ChargeCategory chargeCategory;
        
        // Base configuration
        private BigDecimal basePercentage;
        private BigDecimal baseFixed;
        private BigDecimal baseMinimum;
        private BigDecimal baseMaximum;
        
        // Override (if exists)
        private UUID overrideId;
        private BigDecimal overridePercentage;
        private BigDecimal overrideFixed;
        private BigDecimal overrideMinimum;
        private BigDecimal overrideMaximum;
        private Boolean isWaived;
        
        // Effective values
        private BigDecimal effectivePercentage;
        private BigDecimal effectiveFixed;
        private BigDecimal effectiveMinimum;
        private BigDecimal effectiveMaximum;
        
        // Flags
        private boolean hasOverride;
        private boolean isActive;
    }

    // ========================================================================
    // WALLET-SPECIFIC DTOs
    // ========================================================================

    /**
     * Request DTO for setting wallet charges.
     * 
     * UPDATED: Now includes ALL waiver fields:
     * - waiveTopup, waiveWithdrawal, waiveTransfer (transaction fees)
     * - waiveIssuance, waiveMonthly, waiveInactivity (fixed fees)
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class WalletChargesRequest {
        private UUID programId;
        
        // ===== TRANSACTION FEE OVERRIDES =====
        
        // Topup fee
        private BigDecimal topupFeePercent;
        private BigDecimal topupFeeFlat;
        
        // Withdrawal fee
        private BigDecimal withdrawalFeePercent;
        private BigDecimal withdrawalFeeFlat;
        
        // Transfer fee
        private BigDecimal transferFeePercent;
        private BigDecimal transferFeeFlat;
        
        // ===== FIXED FEE OVERRIDES =====
        
        private BigDecimal issuanceFee;
        private BigDecimal monthlyFee;
        private BigDecimal inactivityFee;  // NEW: Added inactivity fee override
        
        // ===== WAIVERS - ALL FEES =====
        
        // Transaction fee waivers
        private Boolean waiveTopup;
        private Boolean waiveWithdrawal;
        private Boolean waiveTransfer;
        
        // Fixed fee waivers (NEW: Added all three)
        private Boolean waiveIssuance;
        private Boolean waiveMonthly;
        private Boolean waiveInactivity;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class WalletChargesResponse {
        private UUID programId;
        private String programCode;
        
        // Transaction fees
        private ChargeDetail topup;
        private ChargeDetail withdrawal;
        private ChargeDetail transfer;
        
        // Fixed fees
        private ChargeDetail issuance;
        private ChargeDetail monthly;
        private ChargeDetail inactivity;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ChargeDetail {
        private String chargeCode;
        private String chargeName;
        private BigDecimal percentage;
        private BigDecimal fixed;
        private BigDecimal minimum;
        private BigDecimal maximum;
        private Boolean isWaived;
        private Boolean hasOverride;
        private String source; // "BASE", "OVERRIDE", or "NONE"
    }

    // ========================================================================
    // CALCULATION DTOs
    // ========================================================================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CalculateChargeRequest {
        private UUID programId;
        private String chargeCode;
        private BigDecimal amount;
        private Boolean isVip;
        private Boolean isBulk;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CalculateChargeResponse {
        private String chargeCode;
        private String chargeName;
        private BigDecimal baseAmount;
        private BigDecimal calculatedCharge;
        private BigDecimal waivedAmount;
        private BigDecimal finalCharge;
        private String currencyCode;
        
        // Calculation details
        private BigDecimal percentageUsed;
        private BigDecimal fixedUsed;
        private String source; // "BASE", "OVERRIDE", "WAIVED"
        private boolean wasOverridden;
        private boolean wasWaived;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CalculateWalletFeesRequest {
        private UUID programId;
        private BigDecimal topupAmount;
        private BigDecimal withdrawalAmount;
        private BigDecimal transferAmount;
        private Boolean isVip;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CalculateWalletFeesResponse {
        private UUID programId;
        
        private CalculateChargeResponse topupFee;
        private CalculateChargeResponse withdrawalFee;
        private CalculateChargeResponse transferFee;
        
        private BigDecimal totalFees;
        private String currencyCode;
    }
}