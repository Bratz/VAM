package com.bank.vam.dto.credit;

import com.bank.vam.entity.credit.InterestConfiguration.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTOs for Interest Configuration operations.
 */
public class InterestConfigurationDto {

    // ========================================================================
    // RESPONSE DTO
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private UUID id;
        private UUID corporateId;
        private String configName;
        private String externalReference;
        
        // Type
        private ConfigType configType;
        private UUID targetId;
        private TargetType targetType;
        private String currencyCode;
        
        // Credit rates
        private String creditBaseRateType;
        private BigDecimal creditBaseRate;
        private BigDecimal creditSpread;
        private BigDecimal effectiveCreditRate;
        private BigDecimal creditMinBalance;
        
        // Debit rates
        private String debitBaseRateType;
        private BigDecimal debitBaseRate;
        private BigDecimal debitSpread;
        private BigDecimal effectiveDebitRate;
        private BigDecimal penaltyRate;
        
        // Calculation parameters
        private String dayCountConvention;
        private CompoundingFrequency compoundingFrequency;
        private CalculationFrequency calculationFrequency;
        private PostingFrequency postingFrequency;
        
        // Tiers
        private Boolean isTiered;
        private String tierConfig;
        
        // Validity
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
        
        // Status
        private ConfigStatus status;
        
        // Sync
        private LocalDateTime lastSyncAt;
        private String sourceSystem;
        
        // Audit
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    // ========================================================================
    // CREATE REQUEST
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        private UUID corporateId;
        private String configName;
        private String externalReference;
        
        // Type
        private ConfigType configType;
        private UUID targetId;
        private TargetType targetType;
        private String currencyCode;
        
        // Credit rates
        private String creditBaseRateType;
        private BigDecimal creditBaseRate;
        private BigDecimal creditSpread;
        private BigDecimal creditMinBalance;
        
        // Debit rates
        private String debitBaseRateType;
        private BigDecimal debitBaseRate;
        private BigDecimal debitSpread;
        private BigDecimal penaltyRate;
        
        // Calculation parameters
        private String dayCountConvention;
        private CompoundingFrequency compoundingFrequency;
        private CalculationFrequency calculationFrequency;
        private PostingFrequency postingFrequency;
        
        // Tiers
        private Boolean isTiered;
        private String tierConfig;
        
        // Validity
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
    }

    // ========================================================================
    // UPDATE REQUEST
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {
        private String configName;
        
        // Credit rates
        private BigDecimal creditBaseRate;
        private BigDecimal creditSpread;
        private BigDecimal creditMinBalance;
        
        // Debit rates
        private BigDecimal debitBaseRate;
        private BigDecimal debitSpread;
        private BigDecimal penaltyRate;
        
        // Calculation parameters
        private String dayCountConvention;
        private CompoundingFrequency compoundingFrequency;
        private PostingFrequency postingFrequency;
        
        // Tiers
        private Boolean isTiered;
        private String tierConfig;
        
        // Validity
        private LocalDate effectiveTo;
    }

    // ========================================================================
    // RATE UPDATE REQUEST (from CBS sync)
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RateUpdateRequest {
        private BigDecimal creditBaseRate;
        private BigDecimal debitBaseRate;
        private String sourceSystem;
    }

    // ========================================================================
    // SPREAD UPDATE REQUEST (treasury pricing)
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SpreadUpdateRequest {
        private BigDecimal creditSpread;
        private BigDecimal debitSpread;
    }

    // ========================================================================
    // INTEREST CALCULATION REQUEST
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CalculateRequest {
        private UUID configId;
        private BigDecimal balance;
        private LocalDate fromDate;
        private LocalDate toDate;
        private Integer days;
    }

    // ========================================================================
    // INTEREST CALCULATION RESPONSE
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CalculationResult {
        private UUID configId;
        private String configName;
        private BigDecimal balance;
        private Boolean isCredit;
        private BigDecimal effectiveRate;
        private Integer days;
        private String dayCountConvention;
        private BigDecimal dailyInterest;
        private BigDecimal totalInterest;
        private LocalDate calculatedAt;
    }

    // ========================================================================
    // SUMMARY DTO
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Summary {
        private UUID id;
        private String configName;
        private ConfigType configType;
        private String currencyCode;
        private BigDecimal effectiveCreditRate;
        private BigDecimal effectiveDebitRate;
        private ConfigStatus status;
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
    }

    // ========================================================================
    // STATISTICS
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Statistics {
        private int totalConfigs;
        private int externalConfigs;
        private int internalConfigs;
        private BigDecimal avgCreditRate;
        private BigDecimal avgDebitRate;
        private int expiringThisMonth;
        private int tieredConfigs;
    }

    // ========================================================================
    // TIER DEFINITION (for parsing tierConfig JSON)
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RateTier {
        private BigDecimal minBalance;
        private BigDecimal maxBalance;
        private BigDecimal rate;
    }
}