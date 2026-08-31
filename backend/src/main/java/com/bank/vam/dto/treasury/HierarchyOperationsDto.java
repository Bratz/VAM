package com.bank.vam.dto.treasury;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Map;

/**
 * DTOs for Corporate Hierarchy Operations - Move, Merge, Limit Transfer.
 * 
 * Following MVC pattern with separate Request/Response classes.
 * 
 * @version 5.0.0
 */
public class HierarchyOperationsDto {

    // ════════════════════════════════════════════════════════════════════════════════
    // MOVE OPERATION REQUESTS
    // ════════════════════════════════════════════════════════════════════════════════

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MoveTransactionVaRequest {
        private UUID vaId;
        private UUID newParentId;
        private String limitPolicy; // STRICT, TRANSFER_WITH_VA, ABSORB_INTO_TARGET, REQUIRE_APPROVAL
        private String approvedBy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MoveAggregationRequest {
        private UUID aggregationId;
        private UUID newParentId;
        private String limitPolicy;
        private String approvedBy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchMoveRequest {
        private List<UUID> vaIds;
        private UUID newParentId;
        private String limitPolicy;
        private String approvedBy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidateMoveRequest {
        private UUID vaId;
        private UUID newParentId;
        private String limitPolicy;
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // M&A OPERATION REQUESTS
    // ════════════════════════════════════════════════════════════════════════════════

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AcquisitionRequest {
        private UUID acquirerCorporateId;
        private UUID targetCorporateId;
        private String newAggregationName;
        private String newAggregationCode;
        private String limitPolicy; // COMBINE_LIMITS, RESET_TARGET_LIMITS, PRESERVE_TARGET_STRUCTURE
        private String approvedBy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MergerRequest {
        private UUID corporateAId;
        private UUID corporateBId;
        private UUID newCorporateId;
        private String newBaseCurrency;
        private String corporateAName;
        private String corporateACode;
        private String corporateBName;
        private String corporateBCode;
        private String limitPolicy;
        private String approvedBy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DivestitureRequest {
        private UUID sourceCorporateId;
        private UUID aggregationId;
        private UUID newCorporateId;
        private String newBaseCurrency;
        private String approvedBy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidateAcquisitionRequest {
        private UUID acquirerCorporateId;
        private UUID targetCorporateId;
        private String limitPolicy;
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // MOVE OPERATION RESPONSES
    // ════════════════════════════════════════════════════════════════════════════════

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MoveOperationResponse {
        private String operationType; // TRANSACTION_VA, AGGREGATION_WITH_SUBTREE, BATCH
        private UUID movedVaId;
        private UUID oldParentId;
        private UUID newParentId;
        private int movedVaCount;
        private Set<String> currenciesAffected;
        private int currencyMirrorsRecalculated;
        private boolean settlementVaReResolved;
        private int settlementVasReResolved;
        private LimitTransferResponse limitTransfer;
        private boolean success;
        private String message;
        private LocalDateTime completedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MoveValidationResponse {
        private boolean valid;
        private List<String> errors;
        private List<String> warnings;
        private String vaType;
        private int affectedVaCount;
        private boolean currencyMirrorWillBeCreated;
        private LimitValidationResponse limitValidation;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchMoveResponse {
        private int totalRequested;
        private int successfulMoves;
        private int failedMoves;
        private List<MoveOperationResponse> results;
        private boolean success;
        private String message;
        private LocalDateTime completedAt;
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // M&A OPERATION RESPONSES
    // ════════════════════════════════════════════════════════════════════════════════

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MergeOperationResponse {
        private String operationType; // ACQUISITION, MERGER, DIVESTITURE, RESTRUCTURING
        private UUID acquirerCorporateId;
        private UUID targetCorporateId;
        private UUID sourceCorporateId;
        private UUID newCorporateId;
        private UUID newAggregationId;
        private UUID newRootId;
        private int migratedVaCount;
        private Set<String> currenciesAffected;
        private int currencyMirrorsCreated;
        private MergeLimitValidationResponse limitValidation;
        private MergeLimitTransferResponse limitTransfer;
        private boolean success;
        private String message;
        private LocalDateTime completedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AcquisitionValidationResponse {
        private boolean valid;
        private List<String> errors;
        private List<String> warnings;
        private int acquirerVaCount;
        private int targetVaCount;
        private int totalVaCount;
        private MergeLimitValidationResponse limitValidation;
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // LIMIT VALIDATION & TRANSFER RESPONSES
    // ════════════════════════════════════════════════════════════════════════════════

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LimitInfoResponse {
        private UUID limitId;
        private UUID targetId;
        private BigDecimal limitAmount;
        private BigDecimal utilizedAmount;
        private BigDecimal availableAmount;
        private BigDecimal allocatedToChildren;
        private BigDecimal unallocatedAmount;
        private boolean hardLimit;
        private boolean hasLimit;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LimitValidationResponse {
        private boolean valid;
        private List<String> errors;
        private List<String> warnings;
        private BigDecimal movingUtilization;
        private BigDecimal subtreeAllocatedLimit;
        private LimitInfoResponse sourceLimit;
        private LimitInfoResponse targetLimit;
        private boolean requiresApproval;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LimitAdjustmentResponse {
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
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LimitTransferResponse {
        private boolean success;
        private String policy;
        private BigDecimal movingUtilization;
        private List<LimitAdjustmentResponse> adjustments;
        private LocalDateTime completedAt;
        private String approvedBy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MergeLimitValidationResponse {
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
        private String recommendedPolicy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MergeLimitTransferResponse {
        private boolean success;
        private String policy;
        private List<LimitAdjustmentResponse> adjustments;
        private LocalDateTime completedAt;
        private String approvedBy;
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // POLICY INFO RESPONSES
    // ════════════════════════════════════════════════════════════════════════════════

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
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
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MergeLimitPolicyInfo {
        private String policy;
        private String name;
        private String description;
        private boolean preservesTargetStructure;
        private boolean requiresApproval;
        private String recommendedFor;
    }

    // ════════════════════════════════════════════════════════════════════════════════
// ADD THESE DTOs TO HierarchyOperationsDto.java
// ════════════════════════════════════════════════════════════════════════════════
// These DTOs are required by the new endpoints in CorporateHierarchyOperationsController
// Add them to the HierarchyOperationsDto class (inside the class, as static nested classes)
// ════════════════════════════════════════════════════════════════════════════════

    // ════════════════════════════════════════════════════════════════════════════════
    // HIERARCHY NODE DTO - For tree representation
    // ════════════════════════════════════════════════════════════════════════════════

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HierarchyNode {
        private String id;
        private String name;
        private String code;
        private String accountCategory;
        private String specialType;
        private String currencyCode;
        private Double balance;
        private String parentVaId;
        private String hierarchyNodeId;
        private String hierarchyPath;
        private Integer hierarchyLevel;
        private List<HierarchyNode> children;
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // OPERATION HISTORY DTO - For tracking operations
    // ════════════════════════════════════════════════════════════════════════════════

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OperationHistoryEntry {
        private String id;
        private String operationType;
        private String summary;
        private String status;
        private String performedBy;
        private LocalDateTime createdAt;
        private LocalDateTime completedAt;
        private Map<String, Object> details;
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // OPERATION RULES RESPONSE - Enhanced with new fields
    // ════════════════════════════════════════════════════════════════════════════════

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OperationRulesResponse {
        private List<String> transactionVaRules;
        private List<String> aggregationRules;
        private List<String> acquisitionRules;      // NEW
        private List<String> divestureRules;        // NEW
        private List<String> notMovableTypes;
        private String crossCorporateNote;          // Optional
        private String limitRulesNote;              // Optional
    }


    
    // @Data
    // @Builder
    // @NoArgsConstructor
    // @AllArgsConstructor
    // public static class OperationRulesResponse {
    //     private List<String> transactionVaRules;
    //     private List<String> aggregationRules;
    //     private List<String> notMovable;
    //     private String crossCorporateNote;
    //     private String limitRulesNote;
    // }
}