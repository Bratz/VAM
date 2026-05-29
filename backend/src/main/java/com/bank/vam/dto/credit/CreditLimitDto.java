package com.bank.vam.dto.credit;

import com.bank.vam.entity.credit.CreditLimit.LimitStatus;
import com.bank.vam.entity.credit.CreditLimit.LimitType;
import com.bank.vam.entity.credit.CreditLimit.TargetType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.Map;

/**
 * Credit Limit DTOs - Proper MVC Separation.
 * 
 * Location: dto/credit/CreditLimitDto.java
 * 
 * UNIFIED ARCHITECTURE v4.2 - MVC PATTERN:
 * ========================================
 * 
 * Controller → uses DTOs for request/response
 * Service    → works with Entity
 * Repository → JPA operations on Entity
 * 
 * This file contains ALL DTOs related to CreditLimit:
 * - Response DTOs (sent to frontend)
 * - Request DTOs (received from frontend)  
 * - Check/Validation DTOs
 * - Statistics DTOs
 */
public class CreditLimitDto {

    private CreditLimitDto() {} // Prevent instantiation

    // ========================================================================
    // MAIN RESPONSE DTO
    // ========================================================================

    /**
     * Full credit limit response - used for detailed views.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private UUID id;
        private UUID corporateId;
        private String limitName;
        private LimitType limitType;
        private UUID targetId;
        private TargetType targetType;
        private UUID creditFacilityId;
        private String externalReference;
        private UUID parentLimitId;
        
        // Amounts
        private BigDecimal limitAmount;
        private String limitCurrency;
        private BigDecimal utilizedAmount;
        private BigDecimal availableAmount;
        private BigDecimal heldAmount;
        private BigDecimal allocatedToChildren;
        private BigDecimal unallocatedAmount;
        private BigDecimal utilizationPercent;
        
        // Thresholds & Controls
        private BigDecimal warningThresholdPercent;
        private BigDecimal criticalThresholdPercent;
        private boolean isHardLimit;
        private Boolean requiresApproval;
        private BigDecimal approvalThresholdPercent;
        
        // Status flags
        private boolean isAtWarningLevel;
        private boolean isAtCriticalLevel;
        private boolean isBreached;
        
        // Dates
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
        private LimitStatus status;
        private String approvedBy;
        private LocalDateTime approvedAt;
        private String notes;
        
        // Sync
        private String sourceSystem;
        private LocalDateTime lastSyncAt;
        
        // Audit
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    /**
     * Summary for lists/dropdowns.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Summary {
        private UUID id;
        private String limitName;
        private LimitType limitType;
        private TargetType targetType;
        private UUID targetId;
        private String targetName;
        private BigDecimal limitAmount;
        private String limitCurrency;
        private BigDecimal utilizedAmount;
        private BigDecimal availableAmount;
        private BigDecimal utilizationPercent;
        private LimitStatus status;
        private boolean isBreached;
    }

    // ========================================================================
    // EXTERNAL LIMIT DTOs (Bank-Provided, Read-Only)
    // ========================================================================

    /**
     * External (bank-provided) limit response.
     * These are READ-ONLY for corporate users.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExternalLimitResponse {
        private UUID id;
        private String limitName;
        private UUID facilityId;
        private String externalReference;
        private UUID targetId;
        private TargetType targetType;
        private BigDecimal limitAmount;
        private String currency;
        private BigDecimal utilizedAmount;
        private BigDecimal availableAmount;
        private BigDecimal utilizationPercent;
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
        private LimitStatus status;
        private String sourceSystem;
        private LocalDateTime lastSyncAt;
        
        // Read-only indicator for UI
        @Builder.Default
        private boolean readOnly = true;
        @Builder.Default
        private String readOnlyReason = "Bank-provided limits cannot be modified. Contact bank for changes.";
    }

    // ========================================================================
    // INTERNAL LIMIT DTOs (CFO-Defined, Manageable)
    // ========================================================================

    /**
     * Internal (CFO-defined) limit response.
     * Corporate users can manage these.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InternalLimitResponse {
        private UUID id;
        private String limitName;
        private String limitType; // "GROUP" or "ENTITY"
        private UUID corporateId;
        private TargetType targetType;
        private UUID targetId;
        private String targetName;
        
        // Hierarchy
        private UUID parentLimitId;
        private BigDecimal allocatedToChildren;
        private BigDecimal unallocatedAmount;
        
        // Amounts
        private BigDecimal limitAmount;
        private String currency;
        private BigDecimal utilizedAmount;
        private BigDecimal availableAmount;
        private BigDecimal heldAmount;
        private BigDecimal utilizationPercent;
        
        // Thresholds
        private BigDecimal warningThresholdPercent;
        private BigDecimal criticalThresholdPercent;
        private boolean isAtWarningLevel;
        private boolean isAtCriticalLevel;
        private boolean isBreached;
        
        // Controls
        private boolean isHardLimit;
        private boolean requiresApproval;
        private BigDecimal approvalThresholdPercent;
        private boolean needsApprovalNow;
        
        // Dates & Status
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
        private LimitStatus status;
        private String approvedBy;
        private LocalDateTime approvedAt;
        private String notes;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    // ========================================================================
    // FACILITY DTO
    // ========================================================================

    /**
     * Credit facility response.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FacilityResponse {
        private UUID id;
        private String facilityName;
        private String facilityType;
        private String externalReference;
        private BigDecimal sanctionedLimit;
        private BigDecimal currentOutstanding;
        private BigDecimal availableLimit;
        private String currency;
        private String interestRateType;
        private String baseRateType;
        private BigDecimal baseRateValue;
        private BigDecimal spreadPercent;
        private BigDecimal effectiveRate;
        private LocalDate expiryDate;
        private String status;
        private LocalDateTime lastSyncAt;
    }

    // ========================================================================
    // REQUEST DTOs
    // ========================================================================

    /**
     * Create group limit request.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateGroupLimitRequest {
        @NotNull(message = "Corporate ID is required")
        private UUID corporateId;
        
        private String limitName;
        
        @NotNull(message = "Amount is required")
        @Positive(message = "Amount must be positive")
        private BigDecimal amount;
        
        @NotBlank(message = "Currency is required")
        private String currency;
        
        @NotBlank(message = "Approver is required")
        private String approvedBy;
        
        @Builder.Default
        private boolean hardLimit = true;
        
        private BigDecimal warningThreshold;
        private BigDecimal criticalThreshold;
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
        private String notes;
    }

    /**
     * Create entity sub-limit request.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateEntitySubLimitRequest {
        @NotNull(message = "Corporate ID is required")
        private UUID corporateId;

        @NotNull(message = "Entity ID is required")
        private UUID entityId;

        private String limitName;

        @NotNull(message = "Amount is required")
        @Positive(message = "Amount must be positive")
        private BigDecimal amount;

        @NotBlank(message = "Currency is required")
        private String currency;

        @NotBlank(message = "Approver is required")
        private String approvedBy;

        @Builder.Default
        private boolean hardLimit = true;

        @Builder.Default
        private boolean requiresApproval = false;

        private BigDecimal approvalThreshold;
        private BigDecimal warningThreshold;
        private BigDecimal criticalThreshold;
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
        private String notes;

        // Optional: Specify parent limit for hierarchical allocation
        // If null, will use the Group Limit for the specified currency
        // If provided, will allocate from that parent limit (e.g., Regional Treasury's pool)
        private UUID parentLimitId;
    }

    /**
     * Update limit amount request.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateAmountRequest {
        @NotNull(message = "New amount is required")
        @Positive(message = "Amount must be positive")
        private BigDecimal newAmount;
        
        @Builder.Default
        private String updatedBy = "SYSTEM";
        
        private String reason;
    }


    // ============================================================================
// ADD THIS NEW DTO CLASS TO CreditLimitDto.java
// Location: backend/src/main/java/com/bank/vam/dto/credit/CreditLimitDto.java
// 
// Add after the CreateEntitySubLimitRequest class
// ============================================================================

    /**
     * Create VA-level limit request.
     * 
     * VA limits are allocated from entity limits.
     * Hierarchy: Group Limit → Entity Limit → VA Limit
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateVaLimitRequest {
        @NotNull(message = "Corporate ID is required")
        private UUID corporateId;
        
        @NotNull(message = "VA ID is required")
        private UUID vaId;
        
        private String limitName;
        
        @NotNull(message = "Amount is required")
        @Positive(message = "Amount must be positive")
        private BigDecimal amount;
        
        @NotBlank(message = "Approver is required")
        private String approvedBy;
        
        @Builder.Default
        private boolean hardLimit = true;
        
        private String notes;
    }

    /**
     * Update VA limit request.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateVaLimitRequest {
        @NotNull(message = "New amount is required")
        @Positive(message = "Amount must be positive")
        private BigDecimal newAmount;
        
        @Builder.Default
        private String updatedBy = "SYSTEM";
        
        private Boolean hardLimit;
        private String notes;
    }

    /**
     * Currency-specific limit totals for dashboard.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CurrencyLimitTotals {
        private String currency;
        
        // External (bank)
        private BigDecimal externalTotal;
        private BigDecimal externalUtilized;
        private BigDecimal externalAvailable;
        
        // Group
        private BigDecimal groupTotal;
        private BigDecimal groupAllocated;
        private BigDecimal groupUnallocated;
        
        // Entity
        private BigDecimal entityUtilized;
        private int entityCount;
        
        // VA
        private BigDecimal vaAllocated;
        private int vaCount;
    }

    /**
     * Multi-currency entity limit summary.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EntityMultiCurrencyLimits {
        private UUID entityId;
        private String entityCode;
        private String entityName;
        private boolean isBankCustomer;
        
        /**
         * Map of currency -> limit details.
         */
        private Map<String, CurrencyLimitDetail> limitsByCurrency;
        
        /**
         * Total count of currency limits.
         */
        private int currencyCount;
        
        /**
         * Total count of VA limits under this entity.
         */
        private int vaLimitCount;
    }

    /**
     * Single currency limit detail.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CurrencyLimitDetail {
        private UUID limitId;
        private String currency;
        
        // External (ceiling)
        private BigDecimal externalCeiling;
        
        // Internal
        private BigDecimal internalLimit;
        private BigDecimal internalUtilized;
        private BigDecimal internalAvailable;
        private BigDecimal utilizationPercent;
        
        // Effective
        private BigDecimal effectiveLimit;
        
        // Status
        private boolean isHardLimit;
        private boolean isAtWarningLevel;
        private boolean isAtCriticalLevel;
        private boolean isBreached;
        
        // VA allocation
        private BigDecimal allocatedToVas;
        private BigDecimal unallocatedForVas;
        private int vaCount;
    }

    /**
     * VA limit response with parent info.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VaLimitResponse {
        private UUID id;
        private UUID vaId;
        private String vaNumber;
        private String vaName;
        private String currency;
        
        // Limit details
        private BigDecimal limitAmount;
        private BigDecimal utilizedAmount;
        private BigDecimal availableAmount;
        private BigDecimal utilizationPercent;
        
        // Controls
        private boolean isHardLimit;
        private boolean isAtWarningLevel;
        private boolean isAtCriticalLevel;
        
        // Parent
        private UUID parentEntityLimitId;
        private UUID owningEntityId;
        private String owningEntityCode;
        
        // Status
        private String status;
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
    }
    /**
     * Update limit controls request.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateControlsRequest {
        @Builder.Default
        private boolean hardLimit = true;
        
        @Builder.Default
        private boolean requiresApproval = false;
        
        private BigDecimal approvalThreshold;
    }

    /**
     * Update thresholds request.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateThresholdsRequest {
        private BigDecimal warningPercent;
        private BigDecimal criticalPercent;
    }

    // ========================================================================
    // CHECK/VALIDATION DTOs
    // ========================================================================

    /**
     * External ceiling response.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CeilingResponse {
        private UUID entityId;
        private String currency;
        private BigDecimal externalCeiling;
        private String message;
    }

    /**
     * Block check response.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BlockCheckResponse {
        private UUID entityId;
        private BigDecimal amount;
        private boolean blocked;
        private String reason;
    }

    /**
     * Approval check response.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApprovalCheckResponse {
        private UUID entityId;
        private BigDecimal amount;
        private boolean approvalRequired;
        private String reason;
    }

    /**
     * Limit check result.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LimitCheckResult {
        private UUID entityId;
        private BigDecimal requestedAmount;
        private String currency;
        
        // External check
        private boolean hasExternalLimit;
        private BigDecimal externalLimitAmount;
        private BigDecimal externalAvailable;
        private boolean externalCheckPassed;
        
        // Internal check
        private boolean hasInternalLimit;
        private BigDecimal internalLimitAmount;
        private BigDecimal internalAvailable;
        private boolean internalCheckPassed;
        private boolean isHardLimit;
        
        // Overall result
        private boolean canProceed;
        private boolean needsApproval;
        private String blockReason;
        private BigDecimal effectiveAvailable;
    }

    // ========================================================================
    // STATISTICS/TOTALS DTOs
    // ========================================================================

    /**
     * Limit totals for dashboard.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LimitTotals {
        // External
        private BigDecimal totalExternalLimit;
        private BigDecimal totalExternalUtilized;
        private BigDecimal totalExternalAvailable;
        private BigDecimal externalUtilizationPercent;
        
        // Internal
        private BigDecimal totalInternalLimit;
        private BigDecimal totalInternalUtilized;
        private BigDecimal totalInternalAvailable;
        private BigDecimal internalUtilizationPercent;
        
        // Group allocation
        private BigDecimal totalGroupLimit;
        private BigDecimal totalAllocated;
        private BigDecimal totalUnallocated;
        
        // Counts
        private int countExternalLimits;
        private int countInternalLimits;
        private int countGroupLimits;
        private int countEntityLimits;
        
        // Alerts
        private int countAtWarning;
        private int countAtCritical;
        private int countBreached;
        private int countExpiringSoon;
    }

    /**
     * Statistics by currency.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CurrencyStatistics {
        private String currency;
        private BigDecimal totalLimit;
        private BigDecimal totalUtilized;
        private BigDecimal totalAvailable;
        private BigDecimal utilizationPercent;
        private int limitCount;
    }
}