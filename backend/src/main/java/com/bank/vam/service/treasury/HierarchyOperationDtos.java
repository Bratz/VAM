package com.bank.vam.service.treasury;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * DTOs for Hierarchy Operations (Move, Merge, Limit Transfer).
 * 
 * FIXED v5.0.1: All enums are now self-contained in this file.
 * No external dependencies on non-existent services.
 * 
 * @version 5.0.1
 */
public class HierarchyOperationDtos {

    // ════════════════════════════════════════════════════════════════════════════════════════════
    // ENUMS - Self-contained, no external dependencies
    // ════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Move Limit Policy - determines how limits are handled during VA/Aggregation moves.
     */
    public enum MoveLimitPolicy {
        /** Block move if target has insufficient limit headroom */
        STRICT,
        /** Limit amount follows the VA - source decreases, target increases */
        TRANSFER_WITH_VA,
        /** Target absorbs VA's utilization without limit transfer */
        ABSORB_INTO_TARGET,
        /** Move is staged for CFO approval before execution */
        REQUIRE_APPROVAL
    }

    /**
     * Merge Limit Policy - determines how limits are handled during M&A operations.
     */
    public enum MergeLimitPolicy {
        /** Add both group limits together. Target's structure becomes sub-hierarchy. */
        COMBINE_LIMITS,
        /** Cancel all target's limits. CFO must reallocate from acquirer's pool. */
        RESET_TARGET_LIMITS,
        /** Target's entire limit hierarchy preserved as nested sub-structure */
        PRESERVE_TARGET_STRUCTURE
    }

    /**
     * Operation Type - categorizes the type of hierarchy operation.
     */
    public enum OperationType {
        MOVE_TRANSACTION_VA,
        MOVE_AGGREGATION,
        BATCH_MOVE,
        ACQUISITION,
        MERGER,
        DIVESTITURE,
        RESTRUCTURING
    }

    // ════════════════════════════════════════════════════════════════════════════════════════════
    // LIMIT TRANSFER RESULT DTOs
    // ════════════════════════════════════════════════════════════════════════════════════════════

    @Data
    @Builder
    public static class LimitAdjustment {
        private UUID targetId;
        private String adjustmentType;
        private BigDecimal oldAmount;
        private BigDecimal newAmount;
        private UUID oldParentLimitId;
        private UUID newParentLimitId;
        private String description;
    }

    @Data
    @Builder
    public static class LimitTransferResult {
        private boolean success;
        private MoveLimitPolicy policy;
        private BigDecimal movingUtilization;
        private List<LimitAdjustment> adjustments;
        private LocalDateTime completedAt;
        private String approvedBy;
    }

    @Data
    @Builder
    public static class MergeLimitValidationResult {
        private boolean valid;
        private List<String> warnings;
        private List<String> plannedActions;
        private BigDecimal acquirerGroupLimit;
        private BigDecimal targetGroupLimit;
        private BigDecimal combinedGroupLimit;
        private BigDecimal acquirerExternalCeiling;
        private BigDecimal targetExternalCeiling;
        private BigDecimal combinedExternalCeiling;
        private int targetLimitCount;
        private MergeLimitPolicy recommendedPolicy;
    }

    @Data
    @Builder
    public static class MergeLimitTransferResult {
        private boolean success;
        private MergeLimitPolicy policy;
        private List<LimitAdjustment> adjustments;
        private LocalDateTime completedAt;
        private String approvedBy;
    }

    // ════════════════════════════════════════════════════════════════════════════════════════════
    // CURRENCY MIRROR RESULT DTOs
    // ════════════════════════════════════════════════════════════════════════════════════════════

    @Data
    @Builder
    public static class CurrencyMirrorResult {
        private boolean oldParentRecalculated;
        private boolean newParentUpdated;
        private boolean mirrorCreated;
        private boolean mirrorDeleted;
        private String currency;
        private UUID mirrorId;
    }

    // ════════════════════════════════════════════════════════════════════════════════════════════
    // SETTLEMENT VA RESULT DTOs
    // ════════════════════════════════════════════════════════════════════════════════════════════

    @Data
    @Builder
    public static class SettlementResolutionResult {
        private boolean resolved;
        private UUID oldSettlementVaId;
        private UUID newSettlementVaId;
        private String resolutionPath;
    }

    // ════════════════════════════════════════════════════════════════════════════════════════════
    // VALIDATION DTOs
    // ════════════════════════════════════════════════════════════════════════════════════════════

    @Data
    @Builder
    public static class MoveValidationRequest {
        private UUID vaId;
        private UUID newParentId;
        private MoveLimitPolicy limitPolicy;
    }

    @Data
    @Builder
    public static class MoveValidationResult {
        private boolean valid;
        private List<String> errors;
        private List<String> warnings;
        private String vaType;
        private int affectedVaCount;
        private boolean currencyMirrorWillBeCreated;
        private boolean limitCheckPassed;
        private BigDecimal requiredHeadroom;
        private BigDecimal availableHeadroom;
    }

    @Data
    @Builder
    public static class AcquisitionValidationRequest {
        private UUID acquirerCorporateId;
        private UUID targetCorporateId;
        private MergeLimitPolicy limitPolicy;
    }

    @Data
    @Builder
    public static class AcquisitionValidationResult {
        private boolean valid;
        private List<String> errors;
        private List<String> warnings;
        private int acquirerVaCount;
        private int targetVaCount;
        private int totalVaCount;
        private MergeLimitValidationResult limitValidation;
    }

    // ════════════════════════════════════════════════════════════════════════════════════════════
    // POLICY INFO DTOs
    // ════════════════════════════════════════════════════════════════════════════════════════════

    @Data
    @Builder
    public static class MoveLimitPolicyInfo {
        private String policy;
        private String name;
        private String description;
        private boolean autoAdjustsLimits;
        private boolean requiresApproval;
        private String recommendedFor;
    }

    @Data
    @Builder
    public static class MergeLimitPolicyInfo {
        private String policy;
        private String name;
        private String description;
        private boolean preservesTargetStructure;
        private boolean requiresApproval;
        private String recommendedFor;
    }

    // ════════════════════════════════════════════════════════════════════════════════════════════
    // SUMMARY DTOs
    // ════════════════════════════════════════════════════════════════════════════════════════════

    @Data
    @Builder
    public static class OperationSummary {
        private String operationId;
        private OperationType operationType;
        private UUID corporateId;
        private UUID initiatedBy;
        private LocalDateTime initiatedAt;
        private LocalDateTime completedAt;
        private boolean success;
        private String status;
        private int affectedVaCount;
        private int limitAdjustmentsCount;
        private String notes;
    }

    @Data
    @Builder
    public static class OperationAuditEntry {
        private String operationId;
        private OperationType operationType;
        private String action;
        private UUID targetId;
        private String targetType;
        private String beforeState;
        private String afterState;
        private LocalDateTime timestamp;
        private String performedBy;
    }
}