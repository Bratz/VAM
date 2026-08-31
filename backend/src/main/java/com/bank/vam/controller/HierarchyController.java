package com.bank.vam.controller;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.dto.hierarchy.HierarchyDto.*;
import com.bank.vam.entity.hierarchy.HierarchyNode;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.repository.hierarchy.HierarchyNodeRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.service.hierarchy.BalanceAggregationService;
import com.bank.vam.service.hierarchy.HierarchyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST Controller for Hierarchy management.
 * Provides endpoints for level configuration, node CRUD, and tree operations.
 * 
 * Two base paths:
 * - /api/v1/hierarchy - Global endpoints (not program-specific)
 * - /api/v1/programs/{programId}/hierarchy - Program-specific endpoints
 */
@RestController
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Hierarchy", description = "Hierarchy management APIs")
public class HierarchyController {

    private final HierarchyService hierarchyService;
    private final BalanceAggregationService balanceAggregationService;
    private final HierarchyNodeRepository hierarchyNodeRepository;
    private final LegalEntityRepository legalEntityRepository;

    // ========================================================================
    // LEGAL ENTITY ENDPOINTS
    // Base path: /api/v1/hierarchy/legal-entities
    // ========================================================================

    @GetMapping("/api/v1/hierarchy/legal-entities")
    @Operation(summary = "Get all legal entities", description = "Get all legal entities, optionally filtered by corporateId")
    public ResponseEntity<ApiResponse<List<LegalEntity>>> getAllLegalEntities(
            @RequestParam(required = false) UUID corporateId) {
        
        log.debug("Fetching all legal entities, corporateId={}", corporateId);
        
        try {
            List<LegalEntity> entities;
            
            if (corporateId != null) {
                entities = legalEntityRepository.findByCorporateIdOrderByHierarchyPath(corporateId);
            } else {
                // Get all entities - for multi-corporate view or when no filter specified
                entities = legalEntityRepository.findAll();
            }
            
            return ResponseEntity.ok(ApiResponse.success(entities));
            
        } catch (Exception e) {
            log.error("Failed to fetch legal entities", e);
            return ResponseEntity.ok(ApiResponse.error("Failed to fetch legal entities: " + e.getMessage()));
        }
    }

    @GetMapping("/api/v1/hierarchy/legal-entities/{entityId}")
    @Operation(summary = "Get legal entity by ID", description = "Get a specific legal entity by ID")
    public ResponseEntity<ApiResponse<LegalEntity>> getLegalEntityById(@PathVariable UUID entityId) {
        
        log.debug("Fetching legal entity: {}", entityId);
        
        try {
            return legalEntityRepository.findById(entityId)
                    .map(entity -> ResponseEntity.ok(ApiResponse.success(entity)))
                    .orElse(ResponseEntity.ok(ApiResponse.error("Legal entity not found")));
            
        } catch (Exception e) {
            log.error("Failed to fetch legal entity", e);
            return ResponseEntity.ok(ApiResponse.error("Failed to fetch legal entity: " + e.getMessage()));
        }
    }

    @GetMapping("/api/v1/hierarchy/legal-entities/corporate/{corporateId}")
    @Operation(summary = "Get legal entities by corporate", description = "Get all legal entities for a corporate")
    public ResponseEntity<ApiResponse<List<LegalEntity>>> getLegalEntitiesByCorporate(
            @PathVariable UUID corporateId) {
        
        log.debug("Fetching legal entities for corporate: {}", corporateId);
        
        try {
            List<LegalEntity> entities = legalEntityRepository.findByCorporateIdOrderByHierarchyPath(corporateId);
            return ResponseEntity.ok(ApiResponse.success(entities));
            
        } catch (Exception e) {
            log.error("Failed to fetch legal entities for corporate", e);
            return ResponseEntity.ok(ApiResponse.error("Failed to fetch: " + e.getMessage()));
        }
    }

    @GetMapping("/api/v1/hierarchy/legal-entities/corporate/{corporateId}/tree")
    @Operation(summary = "Get legal entity hierarchy tree", description = "Get legal entities as a tree structure")
    public ResponseEntity<ApiResponse<List<LegalEntity>>> getLegalEntityTree(
            @PathVariable UUID corporateId) {
        
        log.debug("Fetching legal entity tree for corporate: {}", corporateId);
        
        try {
            // Get root entities (no parent)
            List<LegalEntity> entities = legalEntityRepository.findByCorporateIdAndParentEntityIdIsNull(corporateId);
            return ResponseEntity.ok(ApiResponse.success(entities));
            
        } catch (Exception e) {
            log.error("Failed to fetch legal entity tree", e);
            return ResponseEntity.ok(ApiResponse.error("Failed to fetch: " + e.getMessage()));
        }
    }

    @GetMapping("/api/v1/hierarchy/legal-entities/{entityId}/children")
    @Operation(summary = "Get children of legal entity", description = "Get direct children of a legal entity")
    public ResponseEntity<ApiResponse<List<LegalEntity>>> getLegalEntityChildren(
            @PathVariable UUID entityId) {
        
        log.debug("Fetching children of legal entity: {}", entityId);
        
        try {
            List<LegalEntity> children = legalEntityRepository.findByParentEntityIdOrderByEntityName(entityId);
            return ResponseEntity.ok(ApiResponse.success(children));
            
        } catch (Exception e) {
            log.error("Failed to fetch legal entity children", e);
            return ResponseEntity.ok(ApiResponse.error("Failed to fetch: " + e.getMessage()));
        }
    }

    @GetMapping("/api/v1/hierarchy/legal-entities/corporate/{corporateId}/treasury-centers")
    @Operation(summary = "Get treasury centers", description = "Get all treasury centers for a corporate")
    public ResponseEntity<ApiResponse<List<LegalEntity>>> getTreasuryCenters(
            @PathVariable UUID corporateId) {
        
        log.debug("Fetching treasury centers for corporate: {}", corporateId);
        
        try {
            List<LegalEntity> treasuryCenters = legalEntityRepository.findTreasuryCenters(corporateId);
            return ResponseEntity.ok(ApiResponse.success(treasuryCenters));
            
        } catch (Exception e) {
            log.error("Failed to fetch treasury centers", e);
            return ResponseEntity.ok(ApiResponse.error("Failed to fetch: " + e.getMessage()));
        }
    }

    @PostMapping("/api/v1/hierarchy/legal-entities")
    @Operation(summary = "Create legal entity", description = "Create a new legal entity")
    public ResponseEntity<ApiResponse<LegalEntity>> createLegalEntity(@RequestBody LegalEntity entity) {
        
        log.info("Creating legal entity: {}", entity.getEntityCode());
        
        try {
            // Set defaults
            if (entity.getInternalLimitUtilized() == null) {
                entity.setInternalLimitUtilized(BigDecimal.ZERO);
            }
            if (entity.getStatus() == null) {
                entity.setStatus(LegalEntity.EntityStatus.ACTIVE);
            }
            if (entity.getHierarchyLevel() == null) {
                entity.setHierarchyLevel(entity.getParentEntityId() == null ? 0 : 1);
            }
            
            LegalEntity saved = legalEntityRepository.save(entity);
            return ResponseEntity.ok(ApiResponse.success(saved, "Legal entity created successfully"));
            
        } catch (Exception e) {
            log.error("Failed to create legal entity", e);
            return ResponseEntity.ok(ApiResponse.error("Failed to create: " + e.getMessage()));
        }
    }

    @PutMapping("/api/v1/hierarchy/legal-entities/{entityId}")
    @Operation(summary = "Update legal entity", description = "Update an existing legal entity")
    public ResponseEntity<ApiResponse<LegalEntity>> updateLegalEntity(
            @PathVariable UUID entityId,
            @RequestBody LegalEntity updates) {
        
        log.info("Updating legal entity: {}", entityId);
        
        try {
            return legalEntityRepository.findById(entityId)
                    .map(entity -> {
                        // Update allowed fields
                        if (updates.getEntityName() != null) entity.setEntityName(updates.getEntityName());
                        if (updates.getShortName() != null) entity.setShortName(updates.getShortName());
                        if (updates.getCountryCode() != null) entity.setCountryCode(updates.getCountryCode());
                        if (updates.getFunctionalCurrency() != null) entity.setFunctionalCurrency(updates.getFunctionalCurrency());
                        if (updates.getIsTreasuryCenter() != null) entity.setIsTreasuryCenter(updates.getIsTreasuryCenter());
                        if (updates.getCanParticipatePooling() != null) entity.setCanParticipatePooling(updates.getCanParticipatePooling());
                        if (updates.getCanParticipateNetting() != null) entity.setCanParticipateNetting(updates.getCanParticipateNetting());
                        if (updates.getContactEmail() != null) entity.setContactEmail(updates.getContactEmail());
                        if (updates.getContactPhone() != null) entity.setContactPhone(updates.getContactPhone());
                        
                        LegalEntity saved = legalEntityRepository.save(entity);
                        return ResponseEntity.ok(ApiResponse.success(saved, "Legal entity updated successfully"));
                    })
                    .orElse(ResponseEntity.ok(ApiResponse.error("Legal entity not found")));
            
        } catch (Exception e) {
            log.error("Failed to update legal entity", e);
            return ResponseEntity.ok(ApiResponse.error("Failed to update: " + e.getMessage()));
        }
    }

    @PutMapping("/api/v1/hierarchy/legal-entities/{entityId}/limit")
    @Operation(summary = "Update internal limit", description = "Update internal credit limit for an entity")
    public ResponseEntity<ApiResponse<LegalEntity>> updateEntityLimit(
            @PathVariable UUID entityId,
            @RequestParam BigDecimal limit,
            @RequestParam String currency) {
        
        log.info("Updating limit for entity {}: {} {}", entityId, limit, currency);
        
        try {
            return legalEntityRepository.findById(entityId)
                    .map(entity -> {
                        entity.setInternalCreditLimit(limit);
                        entity.setInternalLimitCurrency(currency);
                        LegalEntity saved = legalEntityRepository.save(entity);
                        return ResponseEntity.ok(ApiResponse.success(saved, "Limit updated successfully"));
                    })
                    .orElse(ResponseEntity.ok(ApiResponse.error("Legal entity not found")));
            
        } catch (Exception e) {
            log.error("Failed to update limit", e);
            return ResponseEntity.ok(ApiResponse.error("Failed to update limit: " + e.getMessage()));
        }
    }

    @PutMapping("/api/v1/hierarchy/legal-entities/{entityId}/activate")
    @Operation(summary = "Activate entity", description = "Activate a legal entity")
    public ResponseEntity<ApiResponse<LegalEntity>> activateEntity(@PathVariable UUID entityId) {
        try {
            return legalEntityRepository.findById(entityId)
                    .map(entity -> {
                        entity.setStatus(LegalEntity.EntityStatus.ACTIVE);
                        LegalEntity saved = legalEntityRepository.save(entity);
                        return ResponseEntity.ok(ApiResponse.success(saved, "Entity activated"));
                    })
                    .orElse(ResponseEntity.ok(ApiResponse.error("Entity not found")));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error("Failed: " + e.getMessage()));
        }
    }

    @PutMapping("/api/v1/hierarchy/legal-entities/{entityId}/suspend")
    @Operation(summary = "Suspend entity", description = "Suspend a legal entity")
    public ResponseEntity<ApiResponse<LegalEntity>> suspendEntity(@PathVariable UUID entityId) {
        try {
            return legalEntityRepository.findById(entityId)
                    .map(entity -> {
                        entity.setStatus(LegalEntity.EntityStatus.SUSPENDED);
                        LegalEntity saved = legalEntityRepository.save(entity);
                        return ResponseEntity.ok(ApiResponse.success(saved, "Entity suspended"));
                    })
                    .orElse(ResponseEntity.ok(ApiResponse.error("Entity not found")));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error("Failed: " + e.getMessage()));
        }
    }

    @PutMapping("/api/v1/hierarchy/legal-entities/{entityId}/close")
    @Operation(summary = "Close entity", description = "Close a legal entity")
    public ResponseEntity<ApiResponse<LegalEntity>> closeEntity(@PathVariable UUID entityId) {
        try {
            return legalEntityRepository.findById(entityId)
                    .map(entity -> {
                        entity.setStatus(LegalEntity.EntityStatus.CLOSED);
                        LegalEntity saved = legalEntityRepository.save(entity);
                        return ResponseEntity.ok(ApiResponse.success(saved, "Entity closed"));
                    })
                    .orElse(ResponseEntity.ok(ApiResponse.error("Entity not found")));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error("Failed: " + e.getMessage()));
        }
    }

    @DeleteMapping("/api/v1/hierarchy/legal-entities/{entityId}")
    @Operation(summary = "Delete legal entity", description = "Delete a legal entity")
    public ResponseEntity<ApiResponse<Void>> deleteLegalEntity(@PathVariable UUID entityId) {
        
        log.info("Deleting legal entity: {}", entityId);
        
        try {
            if (!legalEntityRepository.existsById(entityId)) {
                return ResponseEntity.ok(ApiResponse.error("Legal entity not found"));
            }
            
            // Check for children
            List<LegalEntity> children = legalEntityRepository.findByParentEntityIdOrderByEntityName(entityId);
            if (!children.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error("Cannot delete entity with children"));
            }
            
            legalEntityRepository.deleteById(entityId);
            return ResponseEntity.ok(ApiResponse.success(null, "Legal entity deleted successfully"));
            
        } catch (Exception e) {
            log.error("Failed to delete legal entity", e);
            return ResponseEntity.ok(ApiResponse.error("Failed to delete: " + e.getMessage()));
        }
    }

    @GetMapping("/api/v1/hierarchy/legal-entities/corporate/{corporateId}/statistics")
    @Operation(summary = "Get entity statistics", description = "Get statistics for legal entities")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getLegalEntityStatistics(
            @PathVariable UUID corporateId) {
        
        try {
            List<LegalEntity> entities = legalEntityRepository.findByCorporateIdOrderByHierarchyPath(corporateId);
            
            long activeCount = entities.stream()
                    .filter(e -> e.getStatus() == LegalEntity.EntityStatus.ACTIVE)
                    .count();
            
            BigDecimal totalLimit = entities.stream()
                    .map(e -> e.getInternalCreditLimit() != null ? e.getInternalCreditLimit() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            BigDecimal totalUtilized = entities.stream()
                    .map(e -> e.getInternalLimitUtilized() != null ? e.getInternalLimitUtilized() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            Map<String, Object> stats = Map.of(
                    "totalEntities", entities.size(),
                    "activeEntities", activeCount,
                    "totalLimit", totalLimit,
                    "totalUtilized", totalUtilized,
                    "totalAvailable", totalLimit.subtract(totalUtilized)
            );
            
            return ResponseEntity.ok(ApiResponse.success(stats));
            
        } catch (Exception e) {
            log.error("Failed to get statistics", e);
            return ResponseEntity.ok(ApiResponse.error("Failed: " + e.getMessage()));
        }
    }

    // ========================================================================
    // GLOBAL ENDPOINTS (not program-specific)
    // Base path: /api/v1/hierarchy
    // ========================================================================

    @GetMapping("/api/v1/hierarchy/nodes")
    @Operation(summary = "Get all hierarchy nodes", description = "Get hierarchy nodes across all programs")
    public ResponseEntity<ApiResponse<List<NodeResponse>>> getAllNodes(
            @RequestParam(required = false) UUID programId,
            @RequestParam(required = false) Integer level,
            @RequestParam(required = false) String nodeType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {

        log.debug("Fetching global hierarchy nodes, programId={}, level={}, type={}, status={}", programId, level, nodeType, status);

        try {
            List<HierarchyNode> nodes;

            if (programId != null) {
                // Program-scoped listing — feeds the VA-create wizard's
                // cascading dimension LOVs.
                nodes = hierarchyNodeRepository.findByProgramIdOrderByMaterializedPathAsc(programId);
                if (level != null) {
                    nodes = nodes.stream().filter(n -> level.equals(n.getLevelNumber())).collect(Collectors.toList());
                }
            } else if (level != null) {
                nodes = hierarchyNodeRepository.findByLevelNumber(level);
            } else if (nodeType != null) {
                nodes = hierarchyNodeRepository.findByNodeType(nodeType);
            } else {
                nodes = hierarchyNodeRepository.findAll(PageRequest.of(page, size)).getContent();
            }
            
            // Apply status filter if provided
            if (status != null && !status.isEmpty()) {
                nodes = nodes.stream()
                        .filter(n -> status.equalsIgnoreCase(n.getStatus()))
                        .collect(Collectors.toList());
            }
            
            List<NodeResponse> responses = nodes.stream()
                    .limit(size)
                    .map(this::toNodeResponse)
                    .collect(Collectors.toList());
            
            return ResponseEntity.ok(ApiResponse.success(responses));
            
        } catch (Exception e) {
            log.error("Failed to fetch hierarchy nodes", e);
            return ResponseEntity.ok(ApiResponse.success(List.of()));
        }
    }

    @GetMapping("/api/v1/hierarchy/roots")
    @Operation(summary = "Get root hierarchy nodes", description = "Get all L1 root nodes across all programs")
    public ResponseEntity<ApiResponse<List<NodeResponse>>> getRootNodes() {
        
        log.debug("Fetching root hierarchy nodes");
        
        try {
            List<HierarchyNode> roots = hierarchyNodeRepository.findByParentIdIsNull();
            
            List<NodeResponse> responses = roots.stream()
                    .map(this::toNodeResponse)
                    .collect(Collectors.toList());
            
            return ResponseEntity.ok(ApiResponse.success(responses));
            
        } catch (Exception e) {
            log.error("Failed to fetch root nodes", e);
            return ResponseEntity.ok(ApiResponse.success(List.of()));
        }
    }

    @GetMapping("/api/v1/hierarchy/nodes/{nodeId}")
    @Operation(summary = "Get node by ID", description = "Get a specific hierarchy node by ID")
    public ResponseEntity<ApiResponse<NodeResponse>> getNodeById(@PathVariable UUID nodeId) {
        
        log.debug("Fetching hierarchy node: {}", nodeId);
        
        try {
            return hierarchyNodeRepository.findById(nodeId)
                    .map(node -> ResponseEntity.ok(ApiResponse.success(toNodeResponse(node))))
                    .orElse(ResponseEntity.ok(ApiResponse.error("Node not found")));
            
        } catch (Exception e) {
            log.error("Failed to fetch hierarchy node", e);
            return ResponseEntity.ok(ApiResponse.error("Failed to fetch node: " + e.getMessage()));
        }
    }

    @GetMapping("/api/v1/hierarchy/search")
    @Operation(summary = "Search nodes globally", description = "Search hierarchy nodes by name or code across all programs")
    public ResponseEntity<ApiResponse<List<NodeResponse>>> searchNodesGlobal(
            @RequestParam String q,
            @RequestParam(defaultValue = "50") int limit) {
        
        log.debug("Searching hierarchy nodes globally: {}", q);
        
        try {
            String searchPattern = "%" + q.toLowerCase() + "%";
            List<HierarchyNode> nodes = hierarchyNodeRepository
                    .findByNodeNameContainingIgnoreCaseOrNodeCodeContainingIgnoreCase(q, q);
            
            List<NodeResponse> responses = nodes.stream()
                    .limit(limit)
                    .map(this::toNodeResponse)
                    .collect(Collectors.toList());
            
            return ResponseEntity.ok(ApiResponse.success(responses));
            
        } catch (Exception e) {
            log.error("Failed to search hierarchy nodes", e);
            return ResponseEntity.ok(ApiResponse.success(List.of()));
        }
    }

    // ========================================================================
    // PROGRAM-SPECIFIC ENDPOINTS
    // Base path: /api/v1/programs/{programId}/hierarchy
    // ========================================================================

    // ------------------------------------------------------------------------
    // Level Configuration
    // ------------------------------------------------------------------------

    @PostMapping("/api/v1/programs/{programId}/hierarchy/config")
    @Operation(summary = "Configure hierarchy levels", description = "Set up the level configuration for a program's hierarchy")
    public ResponseEntity<List<LevelConfigResponse>> configureLevels(
            @PathVariable UUID programId,
            @RequestBody List<LevelConfigRequest> requests) {
        log.info("Configuring hierarchy levels for program: {}", programId);
        List<LevelConfigResponse> configs = hierarchyService.configureLevels(programId, requests);
        return ResponseEntity.ok(configs);
    }

    @GetMapping("/api/v1/programs/{programId}/hierarchy/config")
    @Operation(summary = "Get level configurations", description = "Get the level configuration for a program's hierarchy")
    public ResponseEntity<List<LevelConfigResponse>> getLevelConfigs(@PathVariable UUID programId) {
        List<LevelConfigResponse> configs = hierarchyService.getLevelConfigs(programId);
        return ResponseEntity.ok(configs);
    }

    @PostMapping("/api/v1/programs/{programId}/hierarchy/config/template")
    @Operation(summary = "Apply hierarchy template", description = "Apply a predefined template for hierarchy levels")
    public ResponseEntity<List<LevelConfigResponse>> applyTemplate(
            @PathVariable UUID programId,
            @RequestBody TemplateRequest request) {
        log.info("Applying template {} to program: {}", request.getTemplateType(), programId);
        List<LevelConfigResponse> configs = hierarchyService.applyTemplate(programId, request.getTemplateType());
        return ResponseEntity.ok(configs);
    }

    // ------------------------------------------------------------------------
    // Node CRUD
    // ------------------------------------------------------------------------

    @PostMapping("/api/v1/programs/{programId}/hierarchy/nodes")
    @Operation(summary = "Create hierarchy node", description = "Create a new node in the hierarchy")
    public ResponseEntity<NodeResponse> createNode(
            @PathVariable UUID programId,
            @RequestBody NodeCreateRequest request) {
        log.info("Creating hierarchy node for program: {}", programId);
        NodeResponse node = hierarchyService.createNode(programId, request);
        return ResponseEntity.ok(node);
    }

    @GetMapping("/api/v1/programs/{programId}/hierarchy/nodes/{nodeId}")
    @Operation(summary = "Get node details", description = "Get details of a specific hierarchy node")
    public ResponseEntity<NodeResponse> getNode(
            @PathVariable UUID programId,
            @PathVariable UUID nodeId) {
        return hierarchyNodeRepository.findById(nodeId)
                .map(node -> ResponseEntity.ok(toNodeResponse(node)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/api/v1/programs/{programId}/hierarchy/nodes/{nodeId}")
    @Operation(summary = "Update hierarchy node", description = "Update an existing hierarchy node")
    public ResponseEntity<NodeResponse> updateNode(
            @PathVariable UUID programId,
            @PathVariable UUID nodeId,
            @RequestBody NodeUpdateRequest request) {
        log.info("Updating hierarchy node: {}", nodeId);
        NodeResponse node = hierarchyService.updateNode(nodeId, request);
        return ResponseEntity.ok(node);
    }

    @PostMapping("/api/v1/programs/{programId}/hierarchy/nodes/{nodeId}/move")
    @Operation(summary = "Move hierarchy node", description = "Move a node to a new parent")
    public ResponseEntity<NodeResponse> moveNode(
            @PathVariable UUID programId,
            @PathVariable UUID nodeId,
            @RequestBody NodeMoveRequest request) {
        log.info("Moving hierarchy node {} to parent {}", nodeId, request.getNewParentId());
        NodeResponse node = hierarchyService.moveNode(nodeId, request);
        return ResponseEntity.ok(node);
    }

    @DeleteMapping("/api/v1/programs/{programId}/hierarchy/nodes/{nodeId}")
    @Operation(summary = "Delete hierarchy node", description = "Delete a node and all its descendants")
    public ResponseEntity<Void> deleteNode(
            @PathVariable UUID programId,
            @PathVariable UUID nodeId) {
        log.info("Deleting hierarchy node: {}", nodeId);
        hierarchyService.deleteNode(nodeId);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------------
    // Tree Operations
    // ------------------------------------------------------------------------

    @GetMapping("/api/v1/programs/{programId}/hierarchy/tree")
    @Operation(summary = "Get full hierarchy tree", description = "Get the complete hierarchy tree for a program")
    public ResponseEntity<TreeResponse> getTree(@PathVariable UUID programId) {
        TreeResponse tree = hierarchyService.getTree(programId);
        return ResponseEntity.ok(tree);
    }

    @GetMapping("/api/v1/programs/{programId}/hierarchy/nodes/{nodeId}/subtree")
    @Operation(summary = "Get subtree", description = "Get the subtree under a specific node")
    public ResponseEntity<List<TreeNodeResponse>> getSubtree(
            @PathVariable UUID programId,
            @PathVariable UUID nodeId) {
        List<TreeNodeResponse> subtree = hierarchyService.getSubtree(nodeId);
        return ResponseEntity.ok(subtree);
    }

    @GetMapping("/api/v1/programs/{programId}/hierarchy/nodes/{nodeId}/children")
    @Operation(summary = "Get children", description = "Get direct children of a node")
    public ResponseEntity<List<NodeResponse>> getChildren(
            @PathVariable UUID programId,
            @PathVariable UUID nodeId) {
        List<NodeResponse> children = hierarchyService.getChildren(nodeId);
        return ResponseEntity.ok(children);
    }

    @GetMapping("/api/v1/programs/{programId}/hierarchy/nodes/{nodeId}/breadcrumb")
    @Operation(summary = "Get breadcrumb", description = "Get the ancestor path for a node")
    public ResponseEntity<BreadcrumbResponse> getBreadcrumb(
            @PathVariable UUID programId,
            @PathVariable UUID nodeId) {
        BreadcrumbResponse breadcrumb = hierarchyService.getBreadcrumb(nodeId);
        return ResponseEntity.ok(breadcrumb);
    }

    @GetMapping("/api/v1/programs/{programId}/hierarchy/search")
    @Operation(summary = "Search nodes", description = "Search hierarchy nodes by name or code")
    public ResponseEntity<List<NodeResponse>> searchNodes(
            @PathVariable UUID programId,
            @RequestParam String q) {
        List<NodeResponse> results = hierarchyService.searchNodes(programId, q);
        return ResponseEntity.ok(results);
    }

    // ------------------------------------------------------------------------
    // Balance Operations
    // ------------------------------------------------------------------------

    @PostMapping("/api/v1/programs/{programId}/hierarchy/refresh-balances")
    @Operation(summary = "Refresh balances", description = "Force refresh of aggregated balances for the hierarchy")
    public ResponseEntity<Void> refreshBalances(@PathVariable UUID programId) {
        log.info("Refreshing balances for program: {}", programId);
        balanceAggregationService.fullRefresh(programId);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/api/v1/programs/{programId}/hierarchy/balance")
    @Operation(summary = "Get total balance", description = "Get total balance for the program hierarchy")
    public ResponseEntity<BigDecimal> getTotalBalance(@PathVariable UUID programId) {
        BigDecimal balance = balanceAggregationService.getTotalBalance(programId);
        return ResponseEntity.ok(balance);
    }

    @GetMapping("/api/v1/programs/{programId}/hierarchy/balance/by-level")
    @Operation(summary = "Get balance by level", description = "Get aggregated balance for each hierarchy level")
    public ResponseEntity<Map<Integer, BigDecimal>> getBalanceByLevel(@PathVariable UUID programId) {
        Map<Integer, BigDecimal> balanceByLevel = balanceAggregationService.getBalanceByLevel(programId);
        return ResponseEntity.ok(balanceByLevel);
    }

    @GetMapping("/api/v1/programs/{programId}/hierarchy/nodes/{nodeId}/balance")
    @Operation(summary = "Get node balance", description = "Get aggregated balance for a specific node")
    public ResponseEntity<BigDecimal> getNodeBalance(
            @PathVariable UUID programId,
            @PathVariable UUID nodeId) {
        BigDecimal balance = balanceAggregationService.getNodeBalance(nodeId);
        return ResponseEntity.ok(balance);
    }

    @PostMapping("/api/v1/programs/{programId}/hierarchy/nodes/{nodeId}/refresh-balance")
    @Operation(summary = "Refresh node balance", description = "Force refresh balance for a specific node and its ancestors")
    public ResponseEntity<Void> refreshNodeBalance(
            @PathVariable UUID programId,
            @PathVariable UUID nodeId) {
        log.info("Refreshing balance for node: {}", nodeId);
        balanceAggregationService.refreshNodeBalance(nodeId);
        return ResponseEntity.ok().build();
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Convert HierarchyNode entity to NodeResponse DTO
     */
    private NodeResponse toNodeResponse(HierarchyNode node) {
        return NodeResponse.builder()
                .id(node.getId())
                .nodeCode(node.getNodeCode())
                .nodeName(node.getNodeName())
                .nodeType(node.getNodeType())
                .levelNumber(node.getLevelNumber())
                .dimensionValue(node.getDimensionValue())
                .parentId(node.getParentId() != null ? node.getParentId() : null)
                .programId(node.getProgramId() != null ? node.getProgramId() : null)
                .currencyCode(node.getCurrencyCode())
                .aggregatedBalance(node.getAggregatedBalance())
                .availableBalance(node.getAvailableBalance())
                .childCount(node.getChildCount())
                .isLeaf(node.getIsLeaf())
                .materializedPath(node.getMaterializedPath())
                .status(node.getStatus())
                .virtualAccountId(node.getVirtualAccountId() != null ? node.getVirtualAccountId() : null)
                .build();
    }

// ========================================================================
// HIERARCHY INITIALIZATION ENDPOINTS - CORRECTED VERSION
// ========================================================================
// Add these endpoints to the existing HierarchyController.java
// 
// IMPORTANT: Use fully qualified Swagger annotation to avoid conflict
// with your DTO ApiResponse class
// ========================================================================

    // ========================================================================
    // Hierarchy Initialization Endpoints
    // ========================================================================

    /**
     * Initialize hierarchy for a program.
     * Creates ROOT node, ROOT VA, and optional Exception VAs.
     * 
     * POST /api/v1/programs/{programId}/hierarchy/initialize
     */
    @PostMapping("/api/v1/programs/{programId}/hierarchy/initialize")
    @Operation(
        summary = "Initialize hierarchy", 
        description = "Initialize hierarchy structure for a program. Creates ROOT node, ROOT VA, and optional Exception VAs."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Hierarchy initialized successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request or hierarchy already initialized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Program not found")
    })
    public ResponseEntity<InitializationResponse> initializeHierarchy(
            @PathVariable UUID programId,
            @RequestBody @Valid InitializeHierarchyRequest request) {
        log.info("Initializing hierarchy for program: {} with currency: {}", programId, request.getBaseCurrency());
        InitializationResponse response = hierarchyService.initializeHierarchyWithResponse(programId, request);
        
        if (response.isSuccess()) {
            return ResponseEntity.ok(response);
        } else if ("ALREADY_INITIALIZED".equals(response.getStatus())) {
            return ResponseEntity.badRequest().body(response);
        } else {
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Get hierarchy initialization status.
     * 
     * GET /api/v1/programs/{programId}/hierarchy/status
     */
    @GetMapping("/api/v1/programs/{programId}/hierarchy/status")
    @Operation(
        summary = "Get hierarchy status", 
        description = "Check if hierarchy is initialized for a program and get summary statistics."
    )
    public ResponseEntity<HierarchyStatusResponse> getHierarchyStatus(@PathVariable UUID programId) {
        log.debug("Getting hierarchy status for program: {}", programId);
        HierarchyStatusResponse status = hierarchyService.getInitializationStatus(programId);
        return ResponseEntity.ok(status);
    }

    /**
     * Create an aggregation node.
     * Creates CONSOLIDATION node (L2-L6) with corresponding AGGREGATION VA.
     * 
     * POST /api/v1/programs/{programId}/hierarchy/aggregation
     */
    @PostMapping("/api/v1/programs/{programId}/hierarchy/aggregation")
    @Operation(
        summary = "Create aggregation node", 
        description = "Create an AGGREGATION node under a parent. AGGREGATION nodes are intermediate consolidation points (L2-L6)."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Aggregation created successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request or validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Program or parent node not found")
    })
    public ResponseEntity<CreateAggregationResponse> createAggregation(
            @PathVariable UUID programId,
            @RequestBody @Valid CreateAggregationRequest request) {
        log.info("Creating aggregation {} under parent {} for program {}", 
            request.getCode(), request.getParentNodeId(), programId);
        CreateAggregationResponse response = hierarchyService.createAggregation(programId, request);
        
        if (response.isSuccess()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * Quick check if hierarchy is initialized (lightweight).
     * 
     * GET /api/v1/programs/{programId}/hierarchy/initialized
     */
    @GetMapping("/api/v1/programs/{programId}/hierarchy/initialized")
    @Operation(
        summary = "Check if initialized", 
        description = "Quick check if hierarchy is initialized (returns boolean only)."
    )
    public ResponseEntity<Map<String, Object>> isInitialized(@PathVariable UUID programId) {
        HierarchyStatusResponse status = hierarchyService.getInitializationStatus(programId);
        Map<String, Object> result = new HashMap<>();
        result.put("programId", programId);
        result.put("initialized", status.isInitialized());
        if (status.isInitialized()) {
            result.put("rootNodeId", status.getRootNodeId());
            result.put("rootVaId", status.getRootVaId());
        }
        return ResponseEntity.ok(result);
    }
}