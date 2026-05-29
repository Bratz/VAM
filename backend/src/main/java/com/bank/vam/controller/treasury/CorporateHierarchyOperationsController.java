package com.bank.vam.controller.treasury;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.dto.treasury.HierarchyOperationsDto.*;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.AccountCategory;
import com.bank.vam.entity.VirtualAccount.VaStatus;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.service.treasury.HierarchyMergeService;
import com.bank.vam.service.treasury.HierarchyMoveService;
import com.bank.vam.service.treasury.HierarchyOperationDtos.MoveLimitPolicy;
import com.bank.vam.service.treasury.HierarchyOperationDtos.MergeLimitPolicy;
import com.bank.vam.service.treasury.HierarchyOperationDtos.MoveValidationResult;
import com.bank.vam.service.treasury.HierarchyOperationDtos.AcquisitionValidationResult;
import com.bank.vam.service.treasury.LimitTransferService;
import com.bank.vam.service.treasury.HierarchyVaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * CorporateHierarchyOperationsController - REST API for hierarchy restructuring.
 * 
 * Handles Move, Merge/M&A, and Limit Transfer operations.
 * 
 * Base URL: /api/v1/treasury/hierarchy-operations
 * 
 * UPDATED v5.0.2: Added missing endpoints for hierarchy retrieval
 * - GET /hierarchy - Get hierarchy tree
 * - GET /movable-vas - Get movable Transaction VAs
 * - GET /movable-aggregations - Get movable Aggregations
 * - GET /history - Get operation history (stub)
 * - GET /pending-approvals - Get pending approvals (stub)
 * - POST /approve/{id} - Approve operation (stub)
 * - POST /reject/{id} - Reject operation (stub)
 * 
 * FIXED v5.0.1: Corrected imports for MoveLimitPolicy and MergeLimitPolicy
 * - Enums are now in HierarchyOperationDtos (service.treasury package)
 * 
 * @version 5.0.2
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/treasury/hierarchy-operations")
@RequiredArgsConstructor
@Tag(name = "Hierarchy Operations", description = "Move, Merge, and Limit Transfer operations")
public class CorporateHierarchyOperationsController {

    private final HierarchyMoveService moveService;
    private final HierarchyMergeService mergeService;
    private final LimitTransferService limitTransferService;
    private final HierarchyVaService hierarchyVaService;

    @Autowired
    private VirtualAccountRepository vaRepository;

    // ════════════════════════════════════════════════════════════════════════════════
    // NEW v5.0.2: HIERARCHY & DATA RETRIEVAL ENDPOINTS
    // ════════════════════════════════════════════════════════════════════════════════

    @GetMapping("/hierarchy")
    @Operation(summary = "Get Corporate Hierarchy",
               description = "Get the complete hierarchy tree for a corporate")
    public ResponseEntity<ApiResponse<HierarchyNode>> getHierarchy(@RequestParam UUID corporateId) {

        log.info("API: Get hierarchy for corporate={}", corporateId);

        try {
            // Check for multiple ROOT accounts (data integrity issue)
            long rootCount = vaRepository.countRootsByCorporateId(corporateId);
            if (rootCount > 1) {
                log.warn("DATA INTEGRITY: Corporate {} has {} ROOT accounts (expected 1). Using oldest ROOT.",
                    corporateId, rootCount);
            }

            // Find ROOT account for corporate (uses LIMIT 1 to handle duplicates)
            VirtualAccount root = vaRepository.findRootByCorporateId(corporateId)
                .orElseThrow(() -> new RuntimeException("No ROOT account found for corporate: " + corporateId));

            // Build hierarchy tree recursively
            HierarchyNode hierarchyTree = buildHierarchyNodeRecursive(root);

            return ResponseEntity.ok(ApiResponse.success(hierarchyTree, "Hierarchy retrieved successfully"));

        } catch (Exception e) {
            log.error("Failed to get hierarchy for corporate={}", corporateId, e);
            return ResponseEntity.status(500)
                .body(ApiResponse.error("Failed to retrieve hierarchy: " + e.getMessage()));
        }
    }

    @GetMapping("/movable-vas")
    @Operation(summary = "Get Movable Transaction VAs",
               description = "Get list of Transaction VAs that can be moved")
    public ResponseEntity<ApiResponse<List<HierarchyNode>>> getMovableVas(
            @RequestParam(required = false) UUID corporateId) {
        
        log.info("API: Get movable VAs for corporate={}", corporateId);
        
        try {
            List<VirtualAccount> vas;
            
            if (corporateId != null) {
                // Get Transaction VAs for specific corporate
                vas = vaRepository.findByCorporateIdAndAccountCategory(corporateId, AccountCategory.TRANSACTION)
                    .stream()
                    .filter(va -> va.getStatus() == VaStatus.ACTIVE)
                    .collect(Collectors.toList());
            } else {
                // Get all Transaction VAs
                vas = vaRepository.findByAccountCategoryAndStatus(
                    AccountCategory.TRANSACTION, VaStatus.ACTIVE);
            }
            
            // Convert to HierarchyNode DTOs
            List<HierarchyNode> nodes = vas.stream()
                .map(this::toHierarchyNode)
                .collect(Collectors.toList());
            
            return ResponseEntity.ok(ApiResponse.success(nodes, 
                "Found " + nodes.size() + " movable Transaction VAs"));
                
        } catch (Exception e) {
            log.error("Failed to get movable VAs", e);
            return ResponseEntity.status(500)
                .body(ApiResponse.error("Failed to retrieve movable VAs: " + e.getMessage()));
        }
    }

    @GetMapping("/movable-aggregations")
    @Operation(summary = "Get Movable Aggregations",
               description = "Get list of Aggregations that can be moved")
    public ResponseEntity<ApiResponse<List<HierarchyNode>>> getMovableAggregations(
            @RequestParam(required = false) UUID corporateId) {
        
        log.info("API: Get movable Aggregations for corporate={}", corporateId);
        
        try {
            List<VirtualAccount> aggregations;
            
            if (corporateId != null) {
                // Get Aggregations for specific corporate
                aggregations = vaRepository.findByCorporateIdAndAccountCategory(
                    corporateId, AccountCategory.AGGREGATION)
                    .stream()
                    .filter(va -> va.getStatus() == VaStatus.ACTIVE)
                    .collect(Collectors.toList());
            } else {
                // Get all Aggregations
                aggregations = vaRepository.findByAccountCategoryAndStatus(
                    AccountCategory.AGGREGATION, VaStatus.ACTIVE);
            }
            
            // Convert to HierarchyNode DTOs
            List<HierarchyNode> nodes = aggregations.stream()
                .map(this::toHierarchyNode)
                .collect(Collectors.toList());
            
            return ResponseEntity.ok(ApiResponse.success(nodes, 
                "Found " + nodes.size() + " movable Aggregations"));
                
        } catch (Exception e) {
            log.error("Failed to get movable aggregations", e);
            return ResponseEntity.status(500)
                .body(ApiResponse.error("Failed to retrieve movable aggregations: " + e.getMessage()));
        }
    }

    @GetMapping("/history")
    @Operation(summary = "Get Operation History",
               description = "Get history of hierarchy operations for a corporate")
    public ResponseEntity<ApiResponse<Page<OperationHistoryEntry>>> getOperationHistory(
            @RequestParam UUID corporateId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        log.info("API: Get operation history for corporate={}, page={}, size={}", 
            corporateId, page, size);
        
        try {
            // TODO: Implement operation history tracking
            // For now, return empty page to prevent 500 errors
            
            Page<OperationHistoryEntry> history = Page.empty(PageRequest.of(page, size));
            
            return ResponseEntity.ok(ApiResponse.success(history, 
                "Operation history retrieved (history tracking not yet implemented)"));
                
        } catch (Exception e) {
            log.error("Failed to get operation history", e);
            return ResponseEntity.status(500)
                .body(ApiResponse.error("Failed to retrieve operation history: " + e.getMessage()));
        }
    }

    @GetMapping("/pending-approvals")
    @Operation(summary = "Get Pending Approvals",
               description = "Get operations pending CFO/admin approval")
    public ResponseEntity<ApiResponse<List<OperationHistoryEntry>>> getPendingApprovals(
            @RequestParam UUID corporateId) {
        
        log.info("API: Get pending approvals for corporate={}", corporateId);
        
        try {
            // TODO: Implement approval workflow tracking
            // For now, return empty list to prevent 500 errors
            
            List<OperationHistoryEntry> pending = new ArrayList<>();
            
            return ResponseEntity.ok(ApiResponse.success(pending, 
                "Pending approvals retrieved (approval workflow not yet implemented)"));
                
        } catch (Exception e) {
            log.error("Failed to get pending approvals", e);
            return ResponseEntity.status(500)
                .body(ApiResponse.error("Failed to retrieve pending approvals: " + e.getMessage()));
        }
    }

    @PostMapping("/approve/{operationId}")
    @Operation(summary = "Approve Operation",
               description = "Approve a pending operation")
    public ResponseEntity<ApiResponse<Void>> approveOperation(
            @PathVariable UUID operationId,
            @RequestParam String approvedBy) {
        
        log.info("API: Approve operation={} by {}", operationId, approvedBy);
        
        try {
            // TODO: Implement approval logic
            log.warn("Approval workflow not yet implemented for operation: {}", operationId);
            return ResponseEntity.ok(ApiResponse.success(null, 
                "Operation approved (approval workflow not yet implemented)"));
                
        } catch (Exception e) {
            log.error("Failed to approve operation", e);
            return ResponseEntity.status(500)
                .body(ApiResponse.error("Failed to approve operation: " + e.getMessage()));
        }
    }

    @PostMapping("/reject/{operationId}")
    @Operation(summary = "Reject Operation",
               description = "Reject a pending operation")
    public ResponseEntity<ApiResponse<Void>> rejectOperation(
            @PathVariable UUID operationId,
            @RequestParam String rejectedBy,
            @RequestParam(required = false) String reason) {

        log.info("API: Reject operation={} by {} - reason: {}", operationId, rejectedBy, reason);

        try {
            // TODO: Implement rejection logic
            log.warn("Approval workflow not yet implemented for operation: {}", operationId);
            return ResponseEntity.ok(ApiResponse.success(null,
                "Operation rejected (approval workflow not yet implemented)"));

        } catch (Exception e) {
            log.error("Failed to reject operation", e);
            return ResponseEntity.status(500)
                .body(ApiResponse.error("Failed to reject operation: " + e.getMessage()));
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // v5.4.0: CURRENCY MIRROR CLEANUP
    // ════════════════════════════════════════════════════════════════════════════════

    @PostMapping("/cleanup/currency-mirrors")
    @Operation(summary = "Cleanup Unused Currency Mirrors",
               description = "Remove Currency Mirrors that have no active Transaction VAs for their currency")
    public ResponseEntity<ApiResponse<CleanupResult>> cleanupUnusedCurrencyMirrors(
            @RequestParam UUID corporateId) {

        log.info("API: Cleanup unused Currency Mirrors for corporate={}", corporateId);

        try {
            int cleanedUp = hierarchyVaService.cleanupUnusedCurrencyMirrors(corporateId);

            CleanupResult result = CleanupResult.builder()
                .corporateId(corporateId)
                .mirrorsRemoved(cleanedUp)
                .success(true)
                .message(cleanedUp > 0
                    ? String.format("Cleaned up %d unused Currency Mirrors", cleanedUp)
                    : "No unused Currency Mirrors found")
                .build();

            return ResponseEntity.ok(ApiResponse.success(result, result.getMessage()));

        } catch (Exception e) {
            log.error("Failed to cleanup Currency Mirrors for corporate={}", corporateId, e);
            return ResponseEntity.status(500)
                .body(ApiResponse.error("Failed to cleanup Currency Mirrors: " + e.getMessage()));
        }
    }

    /**
     * Result DTO for cleanup operations.
     */
    @lombok.Data
    @lombok.Builder
    public static class CleanupResult {
        private UUID corporateId;
        private int mirrorsRemoved;
        private boolean success;
        private String message;
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // MOVE OPERATIONS
    // ════════════════════════════════════════════════════════════════════════════════

    @PostMapping("/move/transaction-va")
    @Operation(summary = "Move Transaction VA",
               description = "Move a single Transaction VA to a new AGGREGATION parent")
    public ResponseEntity<ApiResponse<MoveOperationResponse>> moveTransactionVa(
            @Valid @RequestBody MoveTransactionVaRequest request) {
        
        if (request.getVaId() == null || request.getNewParentId() == null) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("VA ID and new parent ID are required"));
        }
        
        log.info("API: Move Transaction VA - va={}, parent={}, policy={}",
            request.getVaId(), request.getNewParentId(), request.getLimitPolicy());
        
        MoveLimitPolicy policy = parseMoveLimitPolicy(request.getLimitPolicy());
        String approvedBy = request.getApprovedBy() != null ? request.getApprovedBy() : "API_USER";
        
        HierarchyMoveService.MoveResult result = moveService.moveTransactionVa(
            request.getVaId(), 
            request.getNewParentId(), 
            policy, 
            approvedBy
        );
        
        MoveOperationResponse response = toMoveOperationResponse(result);
        
        return ResponseEntity.ok(ApiResponse.success(response,
            result.isSuccess() ? "Transaction VA moved successfully" : "Move failed"));
    }

    @PostMapping("/move/transaction-va/{vaId}/to/{newParentId}")
    @Operation(summary = "Quick Move Transaction VA",
               description = "Move a Transaction VA using path parameters (STRICT policy)")
    public ResponseEntity<ApiResponse<MoveOperationResponse>> quickMoveTransactionVa(
            @PathVariable UUID vaId,
            @PathVariable UUID newParentId,
            @RequestParam(defaultValue = "STRICT") String limitPolicy) {
        
        MoveTransactionVaRequest request = MoveTransactionVaRequest.builder()
            .vaId(vaId)
            .newParentId(newParentId)
            .limitPolicy(limitPolicy)
            .approvedBy("API_USER")
            .build();
        
        return moveTransactionVa(request);
    }

    @PostMapping("/move/aggregation")
    @Operation(summary = "Move Aggregation",
               description = "Move an AGGREGATION (and all children) to a new parent")
    public ResponseEntity<ApiResponse<MoveOperationResponse>> moveAggregation(
            @Valid @RequestBody MoveAggregationRequest request) {
        
        if (request.getAggregationId() == null || request.getNewParentId() == null) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("Aggregation ID and new parent ID are required"));
        }
        
        log.info("API: Move Aggregation - agg={}, parent={}, policy={}",
            request.getAggregationId(), request.getNewParentId(), request.getLimitPolicy());
        
        MoveLimitPolicy policy = parseMoveLimitPolicy(request.getLimitPolicy());
        String approvedBy = request.getApprovedBy() != null ? request.getApprovedBy() : "API_USER";
        
        HierarchyMoveService.MoveResult result = moveService.moveAggregation(
            request.getAggregationId(), 
            request.getNewParentId(), 
            policy, 
            approvedBy
        );
        
        MoveOperationResponse response = toMoveOperationResponse(result);
        
        return ResponseEntity.ok(ApiResponse.success(response,
            result.isSuccess() ? "Aggregation moved successfully" : "Move failed"));
    }

    @PostMapping("/move/aggregation/{aggId}/to/{newParentId}")
    @Operation(summary = "Quick Move Aggregation",
               description = "Move an AGGREGATION using path parameters")
    public ResponseEntity<ApiResponse<MoveOperationResponse>> quickMoveAggregation(
            @PathVariable UUID aggId,
            @PathVariable UUID newParentId,
            @RequestParam(defaultValue = "TRANSFER_WITH_VA") String limitPolicy) {
        
        MoveAggregationRequest request = MoveAggregationRequest.builder()
            .aggregationId(aggId)
            .newParentId(newParentId)
            .limitPolicy(limitPolicy)
            .approvedBy("API_USER")
            .build();
        
        return moveAggregation(request);
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // M&A OPERATIONS
    // ════════════════════════════════════════════════════════════════════════════════

    @PostMapping("/acquire")
    @Operation(summary = "Acquire Corporate",
               description = "Target corporate's ROOT becomes AGGREGATION under acquirer's ROOT")
    public ResponseEntity<ApiResponse<MergeOperationResponse>> acquireCorporate(
            @Valid @RequestBody AcquisitionRequest request) {
        
        if (request.getAcquirerCorporateId() == null || request.getTargetCorporateId() == null) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("Acquirer and target corporate IDs are required"));
        }
        
        log.info("API: Acquisition - acquirer={}, target={}",
            request.getAcquirerCorporateId(), request.getTargetCorporateId());
        
        MergeLimitPolicy policy = parseMergeLimitPolicy(request.getLimitPolicy());
        String approvedBy = request.getApprovedBy() != null ? request.getApprovedBy() : "API_USER";
        
        HierarchyMergeService.AcquisitionRequest serviceRequest = 
            HierarchyMergeService.AcquisitionRequest.builder()
                .acquirerCorporateId(request.getAcquirerCorporateId())
                .targetCorporateId(request.getTargetCorporateId())
                .newAggregationName(request.getNewAggregationName())
                .newAggregationCode(request.getNewAggregationCode())
                .limitPolicy(policy)
                .approvedBy(approvedBy)
                .build();
        
        HierarchyMergeService.MergeResult result = mergeService.acquireCorporate(serviceRequest);
        
        MergeOperationResponse response = toMergeOperationResponse(result);
        
        return ResponseEntity.ok(ApiResponse.success(response,
            result.isSuccess() ? "Acquisition completed" : "Acquisition failed"));
    }

    @PostMapping("/merge")
    @Operation(summary = "Merge Corporates",
               description = "Create new ROOT with both old ROOTs as AGGREGATIONs")
    public ResponseEntity<ApiResponse<MergeOperationResponse>> mergeCorporates(
            @Valid @RequestBody MergerRequest request) {
        
        if (request.getCorporateAId() == null || request.getCorporateBId() == null) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("Both corporate IDs are required"));
        }
        
        log.info("API: Merger - corpA={}, corpB={}, newCorp={}",
            request.getCorporateAId(), request.getCorporateBId(), request.getNewCorporateId());
        
        MergeLimitPolicy policy = parseMergeLimitPolicy(request.getLimitPolicy());
        String approvedBy = request.getApprovedBy() != null ? request.getApprovedBy() : "API_USER";
        
        HierarchyMergeService.MergerRequest serviceRequest = 
            HierarchyMergeService.MergerRequest.builder()
                .corporateAId(request.getCorporateAId())
                .corporateBId(request.getCorporateBId())
                .newCorporateId(request.getNewCorporateId())
                .newBaseCurrency(request.getNewBaseCurrency())
                .corporateAName(request.getCorporateAName())
                .corporateACode(request.getCorporateACode())
                .corporateBName(request.getCorporateBName())
                .corporateBCode(request.getCorporateBCode())
                .limitPolicy(policy)
                .approvedBy(approvedBy)
                .build();
        
        HierarchyMergeService.MergeResult result = mergeService.mergeCorporates(serviceRequest);
        
        MergeOperationResponse response = toMergeOperationResponse(result);
        
        return ResponseEntity.ok(ApiResponse.success(response,
            result.isSuccess() ? "Merger completed" : "Merger failed"));
    }

    @PostMapping("/divest")
    @Operation(summary = "Divest Aggregation",
               description = "Spin-off an AGGREGATION into a new corporate")
    public ResponseEntity<ApiResponse<MergeOperationResponse>> divestAggregation(
            @Valid @RequestBody DivestitureRequest request) {
        
        if (request.getAggregationId() == null || request.getNewCorporateId() == null) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("Aggregation ID and new corporate ID are required"));
        }
        
        log.info("API: Divestiture - source={}, agg={}, newCorp={}",
            request.getSourceCorporateId(), request.getAggregationId(), request.getNewCorporateId());
        
        String approvedBy = request.getApprovedBy() != null ? request.getApprovedBy() : "API_USER";
        
        HierarchyMergeService.DivestitureRequest serviceRequest = 
            HierarchyMergeService.DivestitureRequest.builder()
                .sourceCorporateId(request.getSourceCorporateId())
                .aggregationId(request.getAggregationId())
                .newCorporateId(request.getNewCorporateId())
                .newBaseCurrency(request.getNewBaseCurrency())
                .approvedBy(approvedBy)
                .build();
        
        HierarchyMergeService.MergeResult result = mergeService.divestAggregation(serviceRequest);
        
        MergeOperationResponse response = toMergeOperationResponse(result);
        
        return ResponseEntity.ok(ApiResponse.success(response,
            result.isSuccess() ? "Divestiture completed" : "Divestiture failed"));
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // VALIDATION ENDPOINTS
    // ════════════════════════════════════════════════════════════════════════════════

    @PostMapping("/validate/move")
    @Operation(summary = "Validate Move Operation",
               description = "Pre-validate a move operation including limit checks")
    public ResponseEntity<ApiResponse<MoveValidationResponse>> validateMove(
            @Valid @RequestBody ValidateMoveRequest request) {
        
        if (request.getVaId() == null || request.getNewParentId() == null) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("VA ID and new parent ID are required"));
        }
        
        MoveLimitPolicy policy = parseMoveLimitPolicy(request.getLimitPolicy());
        
        MoveValidationResult result = 
            moveService.validateMove(request.getVaId(), request.getNewParentId(), policy);
        
        MoveValidationResponse response = toMoveValidationResponse(result);
        
        return ResponseEntity.ok(ApiResponse.success(response, "Validation complete"));
    }

    @GetMapping("/validate/move/{vaId}/to/{newParentId}")
    @Operation(summary = "Quick Validate Move",
               description = "Quick validation using path parameters")
    public ResponseEntity<ApiResponse<MoveValidationResponse>> quickValidateMove(
            @PathVariable UUID vaId,
            @PathVariable UUID newParentId,
            @RequestParam(defaultValue = "STRICT") String limitPolicy) {
        
        ValidateMoveRequest request = ValidateMoveRequest.builder()
            .vaId(vaId)
            .newParentId(newParentId)
            .limitPolicy(limitPolicy)
            .build();
        
        return validateMove(request);
    }

    @PostMapping("/validate/acquisition")
    @Operation(summary = "Validate Acquisition",
               description = "Pre-validate an acquisition including limit analysis")
    public ResponseEntity<ApiResponse<AcquisitionValidationResponse>> validateAcquisition(
            @Valid @RequestBody ValidateAcquisitionRequest request) {
        
        if (request.getAcquirerCorporateId() == null || request.getTargetCorporateId() == null) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("Acquirer and target corporate IDs are required"));
        }
        
        MergeLimitPolicy policy = parseMergeLimitPolicy(request.getLimitPolicy());
        
        AcquisitionValidationResult result = 
            mergeService.validateAcquisition(
                request.getAcquirerCorporateId(), 
                request.getTargetCorporateId(), 
                policy
            );
        
        AcquisitionValidationResponse response = toAcquisitionValidationResponse(result);
        
        return ResponseEntity.ok(ApiResponse.success(response, "Acquisition validation complete"));
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // POLICY INFO ENDPOINTS
    // ════════════════════════════════════════════════════════════════════════════════

    @GetMapping("/policies/move")
    @Operation(summary = "Get Move Limit Policies",
               description = "Get information about available move limit policies")
    public ResponseEntity<ApiResponse<List<MoveLimitPolicyInfo>>> getMovePolicies() {
        List<MoveLimitPolicyInfo> policies = List.of(
            MoveLimitPolicyInfo.builder()
                .policy("STRICT")
                .name("Strict")
                .description("Block move if target has insufficient limit headroom")
                .autoAdjustsLimits(false)
                .requiresApproval(false)
                .recommendedFor("High-risk scenarios, strict governance")
                .build(),
            MoveLimitPolicyInfo.builder()
                .policy("TRANSFER_WITH_VA")
                .name("Transfer with VA")
                .description("Limit amount follows the VA - source decreases, target increases")
                .autoAdjustsLimits(true)
                .requiresApproval(false)
                .recommendedFor("Internal reorganizations, team restructures")
                .build(),
            MoveLimitPolicyInfo.builder()
                .policy("ABSORB_INTO_TARGET")
                .name("Absorb into Target")
                .description("Target absorbs VA's utilization without limit transfer")
                .autoAdjustsLimits(false)
                .requiresApproval(false)
                .recommendedFor("Consolidation, soft limit environments")
                .build(),
            MoveLimitPolicyInfo.builder()
                .policy("REQUIRE_APPROVAL")
                .name("Require Approval")
                .description("Move is staged for CFO approval before execution")
                .autoAdjustsLimits(true)
                .requiresApproval(true)
                .recommendedFor("Large moves, compliance-sensitive operations")
                .build()
        );
        
        return ResponseEntity.ok(ApiResponse.success(policies, "Move limit policies"));
    }

    @GetMapping("/policies/merge")
    @Operation(summary = "Get Merge Limit Policies",
               description = "Get information about available merge/acquisition limit policies")
    public ResponseEntity<ApiResponse<List<MergeLimitPolicyInfo>>> getMergePolicies() {
        List<MergeLimitPolicyInfo> policies = List.of(
            MergeLimitPolicyInfo.builder()
                .policy("COMBINE_LIMITS")
                .name("Combine Limits")
                .description("Add both group limits together. Target's structure becomes sub-hierarchy.")
                .preservesTargetStructure(true)
                .requiresApproval(false)
                .recommendedFor("Acquisitions where both entities continue operations")
                .build(),
            MergeLimitPolicyInfo.builder()
                .policy("RESET_TARGET_LIMITS")
                .name("Reset Target Limits")
                .description("Cancel all target's limits. CFO must reallocate from acquirer's pool.")
                .preservesTargetStructure(false)
                .requiresApproval(true)
                .recommendedFor("Full integration, new limit structure needed")
                .build(),
            MergeLimitPolicyInfo.builder()
                .policy("PRESERVE_TARGET_STRUCTURE")
                .name("Preserve Target Structure")
                .description("Target's entire limit hierarchy preserved as nested sub-structure")
                .preservesTargetStructure(true)
                .requiresApproval(false)
                .recommendedFor("Maintaining operational autonomy of acquired entity")
                .build()
        );
        
        return ResponseEntity.ok(ApiResponse.success(policies, "Merge limit policies"));
    }

    @GetMapping("/rules")
    @Operation(summary = "Get Operation Rules",
               description = "Get information about what can be moved and restrictions")
    public ResponseEntity<ApiResponse<OperationRulesResponse>> getOperationRules() {
        OperationRulesResponse rules = OperationRulesResponse.builder()
            .transactionVaRules(List.of(
                "Transaction VAs can move to any AGGREGATION within same corporate",
                "Settlement VA will be re-resolved at new location",
                "Currency Mirrors are recalculated at both old and new parent",
                "Balance is subtracted from source mirror, added to target mirror",
                "Limit utilization follows the VA based on policy"
            ))
            .aggregationRules(List.of(
                "AGGREGATIONs can move to ROOT or another AGGREGATION",
                "Entire subtree moves together (preserving internal structure)",
                "Settlement VAs within subtree are preserved",
                "Currency Mirrors are created at new parent for all currencies in subtree",
                "Cannot move to a descendant (circular reference)",
                "Limit hierarchy is re-parented based on policy"
            ))
            .acquisitionRules(List.of(
                "Target's ROOT becomes an AGGREGATION under acquirer's ROOT",
                "All VAs under target are migrated with their structure intact",
                "Limit policies control how limits are combined or reset",
                "Currency Mirrors are created at acquirer's ROOT for target currencies"
            ))
            .divestureRules(List.of(
                "Selected AGGREGATION becomes ROOT of new corporate",
                "All child VAs are migrated to new corporate",
                "Settlement VAs are preserved in new structure",
                "New corporate gets independent limit structure"
            ))
            .notMovableTypes(List.of(
                "ROOT",
                "CURRENCY_MIRROR",
                "SETTLEMENT",
                "EXCEPTION",
                "PHYSICAL_MIRROR"
            ))
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(rules, "Operation rules"));
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // HELPER METHODS - Policy Parsing
    // ════════════════════════════════════════════════════════════════════════════════

    private MoveLimitPolicy parseMoveLimitPolicy(String policy) {
        if (policy == null || policy.isBlank()) {
            return MoveLimitPolicy.STRICT;
        }
        try {
            return MoveLimitPolicy.valueOf(policy.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid move limit policy: {}. Defaulting to STRICT", policy);
            return MoveLimitPolicy.STRICT;
        }
    }

    private MergeLimitPolicy parseMergeLimitPolicy(String policy) {
        if (policy == null || policy.isBlank()) {
            return MergeLimitPolicy.COMBINE_LIMITS;
        }
        try {
            return MergeLimitPolicy.valueOf(policy.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid merge limit policy: {}. Defaulting to COMBINE_LIMITS", policy);
            return MergeLimitPolicy.COMBINE_LIMITS;
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // HELPER METHODS - Hierarchy Building
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * Recursively build HierarchyNode from VirtualAccount.
     * Includes all children (AGGREGATION, CURRENCY_MIRROR, SETTLEMENT, TRANSACTION, etc.)
     */
    private HierarchyNode buildHierarchyNodeRecursive(VirtualAccount va) {
        // Build current node
        HierarchyNode node = toHierarchyNode(va);
        
        // Get all children
        List<VirtualAccount> children = vaRepository.findByParentAccountId(va.getId());
        
        if (!children.isEmpty()) {
            // Recursively build child nodes
            List<HierarchyNode> childNodes = children.stream()
                .filter(child -> child.getStatus() == VaStatus.ACTIVE)
                .map(this::buildHierarchyNodeRecursive)
                .collect(Collectors.toList());
            
            node.setChildren(childNodes);
        }
        
        return node;
    }

    /**
     * Convert VirtualAccount entity to HierarchyNode DTO.
     */
    private HierarchyNode toHierarchyNode(VirtualAccount va) {
        return HierarchyNode.builder()
            .id(va.getId().toString())
            .name(va.getVaName())
            .code(va.getVaNumber())
            .accountCategory(va.getAccountCategory().name())
            .specialType(va.getSpecialType() != null ? va.getSpecialType().name() : null)
            .currencyCode(va.getCurrencyCode())
            .balance(va.getCurrentBalance() != null ? va.getCurrentBalance().doubleValue() : 0.0)
            .parentVaId(va.getParentAccountId() != null ? va.getParentAccountId().toString() : null)
            .hierarchyNodeId(va.getHierarchyNodeId() != null ? va.getHierarchyNodeId().toString() : null)
            .hierarchyPath(va.getHierarchyPath())
            .hierarchyLevel(va.getHierarchyLevel())
            .children(new ArrayList<>()) // Will be populated by recursive call
            .build();
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // HELPER METHODS - Response Mapping
    // ════════════════════════════════════════════════════════════════════════════════

    private MoveOperationResponse toMoveOperationResponse(HierarchyMoveService.MoveResult result) {
        return MoveOperationResponse.builder()
            .operationType(result.getOperationType() != null ? result.getOperationType().name() : null)
            .movedVaId(result.getMovedVaId())
            .oldParentId(result.getOldParentId())
            .newParentId(result.getNewParentId())
            .movedVaCount(result.getMovedVaCount())
            .currenciesAffected(result.getCurrenciesAffected())
            .currencyMirrorsRecalculated(result.getCurrencyMirrorsRecalculated())
            .settlementVaReResolved(result.isSettlementVaReResolved())
            .settlementVasReResolved(result.getSettlementVasReResolved())
            .limitTransfer(toLimitTransferResponse(result.getLimitTransferResult()))
            .success(result.isSuccess())
            .message(result.getMessage())
            .completedAt(result.getCompletedAt())
            .build();
    }

    private MoveValidationResponse toMoveValidationResponse(MoveValidationResult result) {
        return MoveValidationResponse.builder()
            .valid(result.isValid())
            .errors(result.getErrors())
            .warnings(result.getWarnings())
            .vaType(result.getVaType())
            .affectedVaCount(result.getAffectedVaCount())
            .currencyMirrorWillBeCreated(result.isCurrencyMirrorWillBeCreated())
            .limitValidation(toLimitValidationResponse(result))
            .build();
    }

    private MergeOperationResponse toMergeOperationResponse(HierarchyMergeService.MergeResult result) {
        return MergeOperationResponse.builder()
            .operationType(result.getOperationType() != null ? result.getOperationType().name() : null)
            .acquirerCorporateId(result.getAcquirerCorporateId())
            .targetCorporateId(result.getTargetCorporateId())
            .sourceCorporateId(result.getSourceCorporateId())
            .newCorporateId(result.getNewCorporateId())
            .newAggregationId(result.getNewAggregationId())
            .newRootId(result.getNewRootId())
            .migratedVaCount(result.getMigratedVaCount())
            .currenciesAffected(result.getCurrenciesAffected())
            .currencyMirrorsCreated(result.getCurrencyMirrorsCreated())
            .limitValidation(toMergeLimitValidationResponse(result.getLimitValidation()))
            .limitTransfer(toMergeLimitTransferResponse(result.getLimitTransferResult()))
            .success(result.isSuccess())
            .message(result.getMessage())
            .completedAt(result.getCompletedAt())
            .build();
    }

    private AcquisitionValidationResponse toAcquisitionValidationResponse(
            AcquisitionValidationResult result) {
        return AcquisitionValidationResponse.builder()
            .valid(result.isValid())
            .errors(result.getErrors())
            .warnings(result.getWarnings())
            .acquirerVaCount(result.getAcquirerVaCount())
            .targetVaCount(result.getTargetVaCount())
            .totalVaCount(result.getTotalVaCount())
            .limitValidation(toMergeLimitValidationResponse(result.getLimitValidation()))
            .build();
    }

    private LimitTransferResponse toLimitTransferResponse(
            com.bank.vam.service.treasury.HierarchyOperationDtos.LimitTransferResult result) {
        if (result == null) return null;
        
        return LimitTransferResponse.builder()
            .success(result.isSuccess())
            .policy(result.getPolicy() != null ? result.getPolicy().name() : null)
            .movingUtilization(result.getMovingUtilization())
            .adjustments(result.getAdjustments() != null ? 
                result.getAdjustments().stream()
                    .map(this::toLimitAdjustmentResponse)
                    .collect(Collectors.toList()) : null)
            .completedAt(result.getCompletedAt())
            .approvedBy(result.getApprovedBy())
            .build();
    }

    private LimitValidationResponse toLimitValidationResponse(
            MoveValidationResult result) {
        if (result == null) return null;
        
        return LimitValidationResponse.builder()
            .valid(result.isLimitCheckPassed())
            .errors(result.getErrors())
            .warnings(result.getWarnings())
            .movingUtilization(result.getRequiredHeadroom())
            .subtreeAllocatedLimit(null) // Not available from MoveValidationResult directly
            .sourceLimit(null) // Would need separate limit lookup
            .targetLimit(null) // Would need separate limit lookup
            .requiresApproval(false) // Would need policy-based determination
            .build();
    }

    private MergeLimitValidationResponse toMergeLimitValidationResponse(
            com.bank.vam.service.treasury.HierarchyOperationDtos.MergeLimitValidationResult result) {
        if (result == null) return null;
        
        return MergeLimitValidationResponse.builder()
            .valid(result.isValid())
            .warnings(result.getWarnings())
            .plannedActions(result.getPlannedActions())
            .acquirerGroupLimit(result.getAcquirerGroupLimit())
            .targetGroupLimit(result.getTargetGroupLimit())
            .combinedGroupLimit(result.getCombinedGroupLimit())
            .acquirerExternalCeiling(result.getAcquirerExternalCeiling())
            .targetExternalCeiling(result.getTargetExternalCeiling())
            .combinedExternalCeiling(result.getCombinedExternalCeiling())
            .targetLimitCount(result.getTargetLimitCount())
            .recommendedPolicy(result.getRecommendedPolicy() != null ? 
                result.getRecommendedPolicy().name() : null)
            .build();
    }

    private MergeLimitTransferResponse toMergeLimitTransferResponse(
            com.bank.vam.service.treasury.HierarchyOperationDtos.MergeLimitTransferResult result) {
        if (result == null) return null;
        
        return MergeLimitTransferResponse.builder()
            .success(result.isSuccess())
            .policy(result.getPolicy() != null ? result.getPolicy().name() : null)
            .adjustments(result.getAdjustments() != null ?
                result.getAdjustments().stream()
                    .map(this::toLimitAdjustmentResponse)
                    .collect(Collectors.toList()) : null)
            .completedAt(result.getCompletedAt())
            .approvedBy(result.getApprovedBy())
            .build();
    }

    private LimitAdjustmentResponse toLimitAdjustmentResponse(
            com.bank.vam.service.treasury.HierarchyOperationDtos.LimitAdjustment adj) {
        if (adj == null) return null;
        
        return LimitAdjustmentResponse.builder()
            .targetId(adj.getTargetId())
            .adjustmentType(adj.getAdjustmentType())
            .oldAmount(adj.getOldAmount())
            .newAmount(adj.getNewAmount())
            .oldParentLimitId(adj.getOldParentLimitId())
            .newParentLimitId(adj.getNewParentLimitId())
            .description(adj.getDescription())
            .build();
    }
}