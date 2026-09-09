package com.bank.vam.dto.treasury;

import com.bank.vam.entity.treasury.SweepRule;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * SweepRuleDto - DTOs for Cash Concentration / Sweep operations.
 * 
 * Enhanced with IHB integration fields in ExecutionResponse.
 */
public class SweepRuleDto {

    @Data
    public static class Response {
        private UUID id;
        private String ruleReference;
        private String ruleName;
        private SweepRule.SweepType sweepType;
        private BigDecimal targetAmount;
        private BigDecimal thresholdMin;
        private BigDecimal thresholdMax;
        private BigDecimal percentage;
        private UUID targetAccountId;
        private String targetAccountNumber;
        private String targetEntityCode;
        private UUID corporateId;
        private UUID programId;
        private SweepRule.SweepFrequency frequency;
        private LocalTime executionTime;
        private Integer priority;
        private SweepRule.SweepStatus status;
        private BigDecimal totalSwept;
        private Integer executionCount;
        private LocalDateTime lastExecution;
        private LocalDateTime nextExecution;
        private String currencyCode;
        private List<SourceAccountDto> sourceAccounts;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    public static class SourceAccountDto {
        private UUID id;
        private UUID accountId;
        private String accountNumber;
        private String entityCode;
        private String entityName;
        private String currencyCode;
        private String bankName;
        private BigDecimal balance;
    }

    @Data
    public static class CreateRequest {
        private String ruleName;
        private SweepRule.SweepType sweepType;
        private BigDecimal targetAmount;
        private BigDecimal thresholdMin;
        private BigDecimal thresholdMax;
        private BigDecimal percentage;
        private UUID targetAccountId;
        private String targetAccountNumber;
        private String targetEntityCode;
        private UUID corporateId;
        private UUID programId;
        private SweepRule.SweepFrequency frequency;
        private LocalTime executionTime;
        private Integer priority;
        private String currencyCode;
        private List<SourceAccountRequest> sourceAccounts;
    }

    @Data
    public static class SourceAccountRequest {
        private UUID accountId;
        private String accountNumber;
        private String entityCode;
        private String entityName;
        private String currencyCode;
        private String bankName;
    }

    @Data
    public static class UpdateRequest {
        private String ruleName;
        private SweepRule.SweepType sweepType;
        private BigDecimal targetAmount;
        private BigDecimal thresholdMin;
        private BigDecimal thresholdMax;
        private BigDecimal percentage;
        private SweepRule.SweepFrequency frequency;
        private LocalTime executionTime;
        private Integer priority;
    }

    @Data
    public static class ExecutionResponse {
        private UUID id;
        private String executionReference;
        private UUID ruleId;
        private String ruleName;
        private String sourceAccountNumber;
        private String sourceEntityCode;
        private String targetAccountNumber;
        private String targetEntityCode;
        private BigDecimal sweepAmount;
        private String currencyCode;
        private BigDecimal balanceBefore;
        private BigDecimal balanceAfter;
        private String status;
        private String errorMessage;
        private LocalDateTime executionTime;
        private LocalDateTime completedAt;
        
        // ====================================================================
        // IHB INTEGRATION FIELDS
        // ====================================================================
        
        /**
         * Whether this sweep created IHB intercompany positions.
         */
        private Boolean ihbEnabled;
        
        /**
         * IHB Deposit ID created for source entity.
         */
        private UUID ihbDepositId;
        
        /**
         * IHB Loan ID if a loan was created.
         */
        private UUID ihbLoanId;
        
        /**
         * IHB Deposit reference for display.
         */
        private String ihbDepositReference;
        
        /**
         * Interest rate applied to IHB position.
         */
        private BigDecimal ihbInterestRate;
    }

    @Data
    public static class RunSweepsRequest {
        private List<UUID> ruleIds;
        /**
         * Optional: restrict the run to rules configured with this exact
         * frequency. Null (the default for a manual "Run Sweeps" API call)
         * means "all active rules regardless of frequency" — used by
         * {@link com.bank.vam.service.ScheduledJobService}'s per-frequency
         * scheduled jobs so e.g. the every-5-minutes REAL_TIME job doesn't
         * also re-run DAILY/WEEKLY/MONTHLY rules on every tick.
         */
        private SweepRule.SweepFrequency frequency;
    }

    @Data
    public static class RunSweepsResponse {
        private int successCount;
        private int failedCount;
        private int skippedCount;
        private BigDecimal totalSwept;
        private List<ExecutionResponse> executions;

        // IHB summary
        private int ihbPositionsCreated;
        private BigDecimal totalIhbDeposits;
    }

    // ========================================================================
    // DEFICIT FUNDING DTOs
    // ========================================================================

    /**
     * Request to run deficit funding (reverse sweep - Treasury funds deficit accounts).
     */
    @Data
    public static class DeficitFundingRequest {
        /**
         * Optional list of rule IDs to run. If empty, all active TARGET_BALANCE rules.
         */
        private List<UUID> ruleIds;
    }

    /**
     * Response from deficit funding operation.
     */
    @Data
    public static class DeficitFundingResponse {
        private int fundedCount;
        private int skippedCount;
        private BigDecimal totalFunded;
        private List<DeficitFundingResult> results;
    }

    /**
     * Result for a single deficit funding operation.
     */
    @Data
    public static class DeficitFundingResult {
        private String sourceAccountNumber;
        private String sourceEntityCode;
        private BigDecimal balanceBefore;
        private BigDecimal balanceAfter;
        private boolean funded;
        private BigDecimal fundedAmount;
        private String message;
        // IHB loan created
        private UUID ihbLoanId;
        private String ihbLoanReference;
    }

    // ========================================================================
    // ORPHAN CLEANUP DTOs
    // ========================================================================

    /**
     * Information about an orphaned sweep rule source.
     * These are sources that reference VAs that have been deleted.
     */
    @Data
    public static class OrphanedSourceInfo {
        private UUID sourceId;
        private UUID accountId;
        private String accountNumber;
        private String entityCode;
        private String entityName;
        private UUID ruleId;
        private String ruleName;
    }

    /**
     * Response from orphan cleanup operation.
     */
    @Data
    public static class OrphanCleanupResponse {
        private int orphanedCount;
        private int deletedCount;
        private List<OrphanedSourceInfo> orphanedSources;
    }
}