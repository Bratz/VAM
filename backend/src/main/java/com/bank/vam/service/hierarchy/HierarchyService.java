package com.bank.vam.service.hierarchy;

import com.bank.vam.dto.hierarchy.HierarchyDto.*;
import com.bank.vam.entity.Program;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.AccountCategory;
import com.bank.vam.entity.VirtualAccount.VaSpecialType;
import com.bank.vam.entity.VirtualAccount.VaStatus;
import com.bank.vam.entity.hierarchy.HierarchyLevelConfig;
import com.bank.vam.entity.hierarchy.HierarchyNode;
import com.bank.vam.entity.hierarchy.HierarchyNode.HierarchyNodeType;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.ProgramRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.HierarchyLevelConfigRepository;
import com.bank.vam.repository.hierarchy.HierarchyNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for managing hierarchy structure.
 * Handles level configuration, node CRUD, and tree operations.
 * 
 * Enhanced in v4.5.0 to create Currency Mirrors during initialization.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class HierarchyService {

    private final HierarchyLevelConfigRepository levelConfigRepository;
    private final HierarchyNodeRepository nodeRepository;
    private final ProgramRepository programRepository;
    private final VirtualAccountRepository virtualAccountRepository;

    // Default FX rates (in production, use FxRateService)
    private static final Map<String, BigDecimal> EXCHANGE_RATES_TO_AED = Map.of(
        "AED", BigDecimal.ONE,
        "USD", new BigDecimal("3.6725"),
        "EUR", new BigDecimal("4.0125"),
        "GBP", new BigDecimal("4.6500"),
        "SGD", new BigDecimal("2.7500"),
        "CHF", new BigDecimal("4.1200"),
        "JPY", new BigDecimal("0.0245"),
        "SAR", new BigDecimal("0.9790")
    );

    // ========================================================================
    // Level Configuration
    // ========================================================================

    /**
     * Configure hierarchy levels for a program.
     */
    public List<LevelConfigResponse> configureLevels(UUID programId, List<LevelConfigRequest> requests) {
        Program program = programRepository.findById(programId)
            .orElseThrow(() -> new ResourceNotFoundException("Program not found: " + programId));

        // Delete existing configs
        levelConfigRepository.deleteByProgramId(programId);

        // Create new configs
        List<HierarchyLevelConfig> configs = new ArrayList<>();
        for (LevelConfigRequest req : requests) {
            HierarchyLevelConfig config = HierarchyLevelConfig.builder()
                .programId(programId)
                .levelNumber(req.getLevelNumber())
                .levelName(req.getLevelName())
                .dimensionType(req.getDimensionType())
                .isRequired(req.getIsRequired() != null ? req.getIsRequired() : true)
                .allowedValues(req.getAllowedValues() != null ? 
                    "[\"" + String.join("\", \"", req.getAllowedValues()) + "\"]" : null)
                .description(req.getDescription())
                .icon(req.getIcon())
                .build();
            configs.add(config);
        }

        List<HierarchyLevelConfig> saved = levelConfigRepository.saveAll(configs);

        // Enable hierarchy on program
        program.setHierarchyEnabled(true);
        program.setHierarchyDepth(requests.size());
        programRepository.save(program);

        return saved.stream().map(this::toResponse).collect(Collectors.toList());
    }

    /**
     * Get level configurations for a program.
     */
    @Transactional(readOnly = true)
    public List<LevelConfigResponse> getLevelConfigs(UUID programId) {
        return levelConfigRepository.findByProgramIdOrderByLevelNumberAsc(programId)
            .stream()
            .map(this::toResponse)
            .collect(Collectors.toList());
    }

    /**
     * Apply a template to configure hierarchy levels.
     */
    public List<LevelConfigResponse> applyTemplate(UUID programId, String templateType) {
        List<LevelConfigRequest> configs = switch (templateType.toUpperCase()) {
            case "IHB_PROGRAM" -> createIhbTemplate();
            case "COLLECTION_PROGRAM" -> createCollectionTemplate();
            case "WALLET_PROGRAM" -> createWalletTemplate();
            case "LOYALTY_PROGRAM" -> createLoyaltyTemplate();
            case "GIFT_CARD_PROGRAM" -> createGiftCardTemplate();
            case "CORPORATE_CARD_PROGRAM" -> createCorporateCardTemplate();
            case "ESCROW_PROGRAM" -> createEscrowTemplate();
            case "VIBAN_PROGRAM" -> createVibanTemplate();
            case "PAYABLES_PROGRAM" -> createPayablesTemplate();
            default -> throw new BusinessException("Unknown template: " + templateType);
        };
        return configureLevels(programId, configs);
    }

    // ========================================================================
    // Node CRUD Operations
    // ========================================================================

    /**
     * Create a new hierarchy node.
     *
     * CONFIGURABLE DEPTH (v5.3.0):
     * Validates against program's maxHierarchyDepth instead of hardcoded 7.
     */
    public NodeResponse createNode(UUID programId, NodeCreateRequest request) {
        Program program = programRepository.findById(programId)
            .orElseThrow(() -> new ResourceNotFoundException("Program not found: " + programId));

        if (!Boolean.TRUE.equals(program.getHierarchyEnabled())) {
            throw new BusinessException("Hierarchy not enabled for program: " + programId);
        }

        // Get max depth for this program
        int maxDepth = program.getMaxHierarchyDepth() != null
            ? program.getMaxHierarchyDepth()
            : HierarchyNode.DEFAULT_MAX_DEPTH;

        // Validate level is within bounds
        if (request.getLevelNumber() < 1 || request.getLevelNumber() > maxDepth) {
            throw new BusinessException(String.format(
                "Level number must be between 1 and %d for this program, got: %d",
                maxDepth, request.getLevelNumber()));
        }

        // Validate parent
        String parentPath = "";
        if (request.getParentId() != null) {
            HierarchyNode parent = nodeRepository.findById(request.getParentId())
                .orElseThrow(() -> new ResourceNotFoundException("Parent node not found"));

            if (!parent.getProgramId().equals(programId)) {
                throw new BusinessException("Parent node belongs to different program");
            }
            parentPath = parent.getMaterializedPath();

            // Validate level sequence
            if (request.getLevelNumber() != parent.getLevelNumber() + 1) {
                throw new BusinessException("Node level must be parent level + 1");
            }
        } else {
            // Root node - must be level 1
            if (request.getLevelNumber() != 1) {
                throw new BusinessException("Root node must be level 1");
            }
            parentPath = "/" + program.getProgramCode();
        }

        // Check for duplicate code under parent
        if (nodeRepository.existsByParentIdAndNodeCode(request.getParentId(), request.getNodeCode())) {
            throw new BusinessException("Node code already exists under parent: " + request.getNodeCode());
        }

        // Determine node type based on configurable depth
        HierarchyNodeType nodeType = determineNodeType(request.getLevelNumber(), maxDepth);
        if (request.getNodeType() != null) {
            nodeType = request.getNodeType();
        }

        // Build materialized path
        String materializedPath = HierarchyNode.buildPath(parentPath, request.getNodeCode());

        // Create node with maxDepth
        HierarchyNode node = HierarchyNode.builder()
            .programId(programId)
            .parentId(request.getParentId())
            .levelNumber(request.getLevelNumber())
            .maxDepth(maxDepth)
            .nodeCode(request.getNodeCode())
            .nodeName(request.getNodeName())
            .nodeType(nodeType)
            .currencyCode(request.getCurrencyCode() != null ? request.getCurrencyCode() : program.getCurrencyCode())
            .dimensionValue(request.getDimensionValue() != null ? request.getDimensionValue() : request.getNodeCode())
            .materializedPath(materializedPath)
            .isLeaf(nodeType == HierarchyNodeType.VIRTUAL_ACCOUNT)
            .virtualAccountId(request.getVirtualAccountId())
            .status(HierarchyNode.STATUS_ACTIVE)
            .icon(request.getIcon())
            .color(request.getColor())
            .displayOrder(request.getDisplayOrder() != null ? request.getDisplayOrder() : 0)
            .metadata(request.getMetadata())
            .tags(request.getTags())
            .build();

        HierarchyNode saved = nodeRepository.save(node);

        // If this is root node, update program
        if (request.getParentId() == null) {
            program.setRootHierarchyNodeId(saved.getId());
            programRepository.save(program);
        }

        // If VA node, update VA with hierarchy info
        if (nodeType == HierarchyNodeType.VIRTUAL_ACCOUNT && request.getVirtualAccountId() != null) {
            VirtualAccount va = virtualAccountRepository.findById(request.getVirtualAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Virtual account not found"));
            va.setHierarchyNodeId(saved.getId());
            va.setHierarchyPath(materializedPath);
            virtualAccountRepository.save(va);
        }

        log.info("Created hierarchy node: {} at path: {}", saved.getNodeCode(), saved.getMaterializedPath());
        return toNodeResponse(saved);
    }

    /**
     * Update a hierarchy node.
     */
    public NodeResponse updateNode(UUID nodeId, NodeUpdateRequest request) {
        HierarchyNode node = nodeRepository.findById(nodeId)
            .orElseThrow(() -> new ResourceNotFoundException("Node not found: " + nodeId));

        if (request.getNodeName() != null) {
            node.setNodeName(request.getNodeName());
        }
        if (request.getDimensionValue() != null) {
            node.setDimensionValue(request.getDimensionValue());
        }
        if (request.getIcon() != null) {
            node.setIcon(request.getIcon());
        }
        if (request.getColor() != null) {
            node.setColor(request.getColor());
        }
        if (request.getDisplayOrder() != null) {
            node.setDisplayOrder(request.getDisplayOrder());
        }
        if (request.getMetadata() != null) {
            node.setMetadata(request.getMetadata());
        }
        if (request.getTags() != null) {
            node.setTags(request.getTags());
        }
        if (request.getStatus() != null) {
            node.setStatus(request.getStatus());
        }

        HierarchyNode saved = nodeRepository.save(node);
        return toNodeResponse(saved);
    }

    /**
     * Move a node to a new parent with dynamic level recalculation.
     *
     * DYNAMIC DEPTH (v5.3.0):
     * =======================
     * When moving a subtree to a new parent:
     * 1. Calculate the depth of the subtree being moved
     * 2. Verify new position won't exceed maxHierarchyDepth
     * 3. Recalculate all levels in the subtree relative to new parent
     * 4. Update materialized paths for all descendants
     *
     * This allows flexible restructuring - e.g., moving a 3-level subtree
     * from depth 4 to depth 2 will recalculate all nodes to depths 2, 3, 4.
     */
    public NodeResponse moveNode(UUID nodeId, NodeMoveRequest request) {
        HierarchyNode node = nodeRepository.findById(nodeId)
            .orElseThrow(() -> new ResourceNotFoundException("Node not found: " + nodeId));

        HierarchyNode newParent = nodeRepository.findById(request.getNewParentId())
            .orElseThrow(() -> new ResourceNotFoundException("New parent not found"));

        // Validate same program (cross-program moves require explicit migration)
        if (!node.getProgramId().equals(newParent.getProgramId())) {
            throw new BusinessException("Cannot move node to different program. Use migrateSubtree for cross-program moves.");
        }

        // Check not moving to descendant
        if (newParent.isDescendantOf(node)) {
            throw new BusinessException("Cannot move node to its own descendant");
        }

        // Get program's max depth
        Program program = programRepository.findById(node.getProgramId())
            .orElseThrow(() -> new ResourceNotFoundException("Program not found"));
        int maxDepth = program.getMaxHierarchyDepth() != null
            ? program.getMaxHierarchyDepth()
            : HierarchyNode.DEFAULT_MAX_DEPTH;

        // Calculate subtree depth
        int subtreeDepth = calculateSubtreeDepth(node);

        // Calculate new levels
        int oldParentLevel = node.getParentId() != null
            ? nodeRepository.findById(node.getParentId()).map(HierarchyNode::getLevelNumber).orElse(0)
            : 0;
        int newParentLevel = newParent.getLevelNumber();
        int newNodeLevel = newParentLevel + 1;
        int newMaxLevel = newParentLevel + subtreeDepth;

        // Validate new position won't exceed max depth
        if (newMaxLevel > maxDepth) {
            throw new BusinessException(String.format(
                "Cannot move subtree: new max level %d would exceed program's max depth %d. " +
                "Subtree depth: %d, new parent level: %d",
                newMaxLevel, maxDepth, subtreeDepth, newParentLevel));
        }

        log.info("Moving node {} from level {} to level {} (subtree depth: {}, new max: {})",
            node.getNodeCode(), node.getLevelNumber(), newNodeLevel, subtreeDepth, newMaxLevel);

        String oldPath = node.getMaterializedPath();
        String newPath = HierarchyNode.buildPath(newParent.getMaterializedPath(), node.getNodeCode());

        // Calculate level offset for subtree
        int levelOffset = newNodeLevel - node.getLevelNumber();

        // Update this node
        node.setParentId(newParent.getId());
        node.setLevelNumber(newNodeLevel);
        node.setMaterializedPath(newPath);
        node.setMaxDepth(maxDepth);
        nodeRepository.save(node);

        // Update all descendants' paths AND levels
        if (levelOffset != 0) {
            updateSubtreeLevels(node.getProgramId(), oldPath, levelOffset, maxDepth);
        }
        nodeRepository.updateSubtreePaths(node.getProgramId(), oldPath, newPath);

        log.info("Moved node {} from {} to {} (level {} -> {})",
            node.getNodeCode(), oldPath, newPath, node.getLevelNumber() - levelOffset, newNodeLevel);

        return toNodeResponse(node);
    }

    /**
     * Calculate the depth of a subtree rooted at the given node.
     *
     * @param root The root node of the subtree
     * @return Depth of the subtree (1 if leaf, higher if has children)
     */
    private int calculateSubtreeDepth(HierarchyNode root) {
        List<HierarchyNode> descendants = nodeRepository.findDescendants(
            root.getProgramId(), root.getMaterializedPath());

        if (descendants.isEmpty()) {
            return 1; // Just the root node
        }

        int maxChildLevel = descendants.stream()
            .mapToInt(HierarchyNode::getLevelNumber)
            .max()
            .orElse(root.getLevelNumber());

        return maxChildLevel - root.getLevelNumber() + 1;
    }

    /**
     * Update levels for all nodes in a subtree.
     *
     * @param programId Program ID
     * @param rootPath Materialized path of the subtree root
     * @param levelOffset Amount to add to each node's level
     * @param maxDepth Maximum allowed depth
     */
    private void updateSubtreeLevels(UUID programId, String rootPath, int levelOffset, int maxDepth) {
        List<HierarchyNode> descendants = nodeRepository.findDescendants(programId, rootPath);

        for (HierarchyNode descendant : descendants) {
            int newLevel = descendant.getLevelNumber() + levelOffset;
            if (newLevel > maxDepth) {
                throw new BusinessException(String.format(
                    "Cannot update level for node %s: new level %d exceeds max depth %d",
                    descendant.getNodeCode(), newLevel, maxDepth));
            }
            descendant.setLevelNumber(newLevel);
            descendant.setMaxDepth(maxDepth);
            nodeRepository.save(descendant);
        }

        log.debug("Updated {} descendant levels by offset {}", descendants.size(), levelOffset);
    }

    /**
     * Migrate a subtree from one program to another.
     * Levels are recalculated based on target program's configuration.
     *
     * @param nodeId Root node of subtree to migrate
     * @param targetProgramId Target program ID
     * @param targetParentId Target parent node ID in the new program
     * @return Updated node response
     */
    @Transactional
    public NodeResponse migrateSubtree(UUID nodeId, UUID targetProgramId, UUID targetParentId) {
        HierarchyNode node = nodeRepository.findById(nodeId)
            .orElseThrow(() -> new ResourceNotFoundException("Node not found: " + nodeId));

        HierarchyNode targetParent = nodeRepository.findById(targetParentId)
            .orElseThrow(() -> new ResourceNotFoundException("Target parent not found"));

        if (!targetParent.getProgramId().equals(targetProgramId)) {
            throw new BusinessException("Target parent does not belong to target program");
        }

        Program targetProgram = programRepository.findById(targetProgramId)
            .orElseThrow(() -> new ResourceNotFoundException("Target program not found"));

        int maxDepth = targetProgram.getMaxHierarchyDepth() != null
            ? targetProgram.getMaxHierarchyDepth()
            : HierarchyNode.DEFAULT_MAX_DEPTH;

        int subtreeDepth = calculateSubtreeDepth(node);
        int newParentLevel = targetParent.getLevelNumber();
        int newMaxLevel = newParentLevel + subtreeDepth;

        if (newMaxLevel > maxDepth) {
            throw new BusinessException(String.format(
                "Cannot migrate subtree: would exceed target program's max depth %d (need %d levels)",
                maxDepth, newMaxLevel));
        }

        // Migrate node and all descendants
        migrateNodeRecursive(node, targetProgramId, targetParent, maxDepth);

        log.info("Migrated subtree {} from program {} to program {} under parent {}",
            node.getNodeCode(), node.getProgramId(), targetProgramId, targetParent.getNodeCode());

        return toNodeResponse(node);
    }

    private void migrateNodeRecursive(HierarchyNode node, UUID targetProgramId,
                                       HierarchyNode newParent, int maxDepth) {
        String oldPath = node.getMaterializedPath();
        String newPath = HierarchyNode.buildPath(newParent.getMaterializedPath(), node.getNodeCode());
        int newLevel = newParent.getLevelNumber() + 1;

        // Get children before updating this node
        List<HierarchyNode> children = nodeRepository.findByParentIdOrderByDisplayOrderAsc(node.getId());

        // Update this node
        node.setProgramId(targetProgramId);
        node.setParentId(newParent.getId());
        node.setLevelNumber(newLevel);
        node.setMaterializedPath(newPath);
        node.setMaxDepth(maxDepth);
        nodeRepository.save(node);

        // Recursively migrate children
        for (HierarchyNode child : children) {
            migrateNodeRecursive(child, targetProgramId, node, maxDepth);
        }
    }

    /**
     * Delete a node and all descendants.
     */
    public void deleteNode(UUID nodeId) {
        HierarchyNode node = nodeRepository.findById(nodeId)
            .orElseThrow(() -> new ResourceNotFoundException("Node not found: " + nodeId));

        // Check for linked VAs
        List<HierarchyNode> vaNodes = nodeRepository.findLeafNodesUnder(node.getProgramId(), node.getMaterializedPath());
        for (HierarchyNode vaNode : vaNodes) {
            if (vaNode.getVirtualAccountId() != null) {
                VirtualAccount va = virtualAccountRepository.findById(vaNode.getVirtualAccountId()).orElse(null);
                if (va != null) {
                    va.setHierarchyNodeId(null);
                    va.setHierarchyPath(null);
                    virtualAccountRepository.save(va);
                }
            }
        }

        // Delete subtree
        int deleted = nodeRepository.deleteSubtree(node.getProgramId(), node.getMaterializedPath());
        log.info("Deleted {} nodes under path: {}", deleted, node.getMaterializedPath());
    }

    // ========================================================================
    // Tree Operations
    // ========================================================================

    /**
     * Get full tree for a program.
     *
     * Enhanced in v5.4.0 to include Currency Mirror VAs as tree nodes.
     * Currency Mirrors are displayed as children of their parent AGGREGATION/ROOT nodes.
     */
    @Transactional(readOnly = true)
    public TreeResponse getTree(UUID programId) {
        Program program = programRepository.findById(programId)
            .orElseThrow(() -> new ResourceNotFoundException("Program not found: " + programId));

        List<LevelConfigResponse> levelConfigs = getLevelConfigs(programId);
        List<HierarchyNode> allNodes = nodeRepository.findByProgramIdOrderByMaterializedPathAsc(programId);

        // NOTE: System-generated VAs (Currency Mirrors, Exception VAs) are NOT included in the tree view
        // They exist for internal balance aggregation purposes only
        // Use the Currency Breakdown API (/api/v1/treasury/aggregation/multi-currency/{programId})
        // to see currency-wise balance breakdown

        log.debug("Building tree for program {} with {} nodes", programId, allNodes.size());

        // Build tree structure (without Currency Mirrors - they are system VAs)
        List<TreeNodeResponse> roots = buildTreeStructure(allNodes);

        // Calculate stats
        TreeStats stats = TreeStats.builder()
            .totalNodes(allNodes.size())
            .leafNodes((int) allNodes.stream().filter(HierarchyNode::getIsLeaf).count())
            .maxDepth(allNodes.stream().mapToInt(HierarchyNode::getDepth).max().orElse(0))
            .totalBalance(allNodes.stream()
                .filter(n -> n.getLevelNumber() == 1)
                .map(HierarchyNode::getAggregatedBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
            .lastUpdated(LocalDateTime.now())
            .build();

        return TreeResponse.builder()
            .programId(programId)
            .programCode(program.getProgramCode())
            .programName(program.getProgramName())
            .levelConfigs(levelConfigs)
            .roots(roots)
            .stats(stats)
            .build();
    }

    /**
     * Get subtree under a specific node.
     * NOTE: System-generated VAs (Currency Mirrors, Exception VAs) are NOT included.
     */
    @Transactional(readOnly = true)
    public List<TreeNodeResponse> getSubtree(UUID nodeId) {
        HierarchyNode node = nodeRepository.findById(nodeId)
            .orElseThrow(() -> new ResourceNotFoundException("Node not found: " + nodeId));

        List<HierarchyNode> descendants = nodeRepository.findDescendants(node.getProgramId(), node.getMaterializedPath());

        // Add the node itself
        List<HierarchyNode> allNodes = new ArrayList<>();
        allNodes.add(node);
        allNodes.addAll(descendants);

        return buildTreeStructure(allNodes);
    }

    /**
     * Get children of a node.
     */
    @Transactional(readOnly = true)
    public List<NodeResponse> getChildren(UUID nodeId) {
        return nodeRepository.findByParentIdOrderByDisplayOrderAsc(nodeId)
            .stream()
            .map(this::toNodeResponse)
            .collect(Collectors.toList());
    }

    /**
     * Get ancestors (breadcrumb) for a node.
     */
    @Transactional(readOnly = true)
    public BreadcrumbResponse getBreadcrumb(UUID nodeId) {
        HierarchyNode node = nodeRepository.findById(nodeId)
            .orElseThrow(() -> new ResourceNotFoundException("Node not found: " + nodeId));

        List<HierarchyNode> ancestors = nodeRepository.findAncestors(node.getProgramId(), node.getMaterializedPath());
        List<LevelConfigResponse> levelConfigs = getLevelConfigs(node.getProgramId());
        Map<Integer, String> levelNames = levelConfigs.stream()
            .collect(Collectors.toMap(LevelConfigResponse::getLevelNumber, LevelConfigResponse::getLevelName));

        List<BreadcrumbItem> path = ancestors.stream()
            .map(n -> BreadcrumbItem.builder()
                .id(n.getId())
                .nodeCode(n.getNodeCode())
                .nodeName(n.getNodeName())
                .levelNumber(n.getLevelNumber())
                .levelName(levelNames.get(n.getLevelNumber()))
                .build())
            .collect(Collectors.toList());

        // Add current node
        path.add(BreadcrumbItem.builder()
            .id(node.getId())
            .nodeCode(node.getNodeCode())
            .nodeName(node.getNodeName())
            .levelNumber(node.getLevelNumber())
            .levelName(levelNames.get(node.getLevelNumber()))
            .build());

        return BreadcrumbResponse.builder()
            .path(path)
            .currentNode(toNodeResponse(node))
            .build();
    }

    /**
     * Search nodes by name or code.
     */
    @Transactional(readOnly = true)
    public List<NodeResponse> searchNodes(UUID programId, String searchTerm) {
        List<HierarchyNode> byName = nodeRepository.searchByName(programId, searchTerm);
        List<HierarchyNode> byCode = nodeRepository.searchByCode(programId, searchTerm);

        Set<UUID> seen = new HashSet<>();
        List<NodeResponse> results = new ArrayList<>();
        
        for (HierarchyNode n : byName) {
            if (seen.add(n.getId())) {
                results.add(toNodeResponse(n));
            }
        }
        for (HierarchyNode n : byCode) {
            if (seen.add(n.getId())) {
                results.add(toNodeResponse(n));
            }
        }

        return results;
    }

    // ========================================================================
    // HIERARCHY INITIALIZATION (v4.5.0 - Enhanced with Currency Mirrors)
    // ========================================================================

    /**
     * Initialize hierarchy for a program with full response.
     * Creates ROOT node, ROOT VA, Currency Mirrors, and optional Exception VAs.
     * 
     * @param programId Program ID
     * @param request Initialization request with configuration
     * @return InitializationResponse with created entity IDs
     */
    @Transactional
    public InitializationResponse initializeHierarchyWithResponse(UUID programId, InitializeHierarchyRequest request) {
        log.info("╔════════════════════════════════════════════════════════════════════════════════╗");
        log.info("║ INITIALIZING HIERARCHY FOR PROGRAM: {}                                    ║", programId);
        log.info("║ Base Currency: {} | Create Exception VA: {} | Create Currency Mirror: {} ║", 
            request.getBaseCurrency(), request.isCreateExceptionVa(), request.isCreateCurrencyMirror());
        log.info("╚════════════════════════════════════════════════════════════════════════════════╝");
        
        try {
            Program program = programRepository.findById(programId)
                .orElseThrow(() -> new ResourceNotFoundException("Program not found: " + programId));

            // Check if already initialized
            if (program.getRootHierarchyNodeId() != null) {
                HierarchyNode existingRoot = nodeRepository.findById(program.getRootHierarchyNodeId()).orElse(null);
                if (existingRoot != null) {
                    log.warn("Hierarchy already initialized for program {}", program.getProgramCode());
                    return InitializationResponse.builder()
                        .success(false)
                        .programId(programId)
                        .programCode(program.getProgramCode())
                        .status("ALREADY_INITIALIZED")
                        .message("Hierarchy already initialized for this program")
                        .rootNodeId(existingRoot.getId())
                        .build();
                }
            }

            // Apply template if provided
            if (request.getTemplateType() != null && !request.getTemplateType().isEmpty()) {
                log.info("Applying template: {}", request.getTemplateType());
                applyTemplate(programId, request.getTemplateType());
            }

            String baseCurrency = request.getBaseCurrency() != null ? request.getBaseCurrency() : program.getCurrencyCode();
            if (baseCurrency == null || baseCurrency.isEmpty()) {
                baseCurrency = "AED"; // Default
            }

            String rootName = request.getRootName() != null ? request.getRootName() : program.getProgramName() + " ROOT";
            String rootCode = request.getRootCode() != null ? request.getRootCode() : "ROOT";

            // ================================================================
            // 1. Create ROOT hierarchy node (Level 1, MASTER type)
            // ================================================================
            log.info("Step 1: Creating ROOT hierarchy node...");
            String materializedPath = "/" + program.getProgramCode() + "/" + rootCode;
            
            HierarchyNode rootNode = HierarchyNode.builder()
                .programId(programId)
                .parentId(null)
                .levelNumber(1)
                .nodeCode(rootCode)
                .nodeName(rootName)
                .nodeType(HierarchyNodeType.MASTER)
                .currencyCode(baseCurrency)
                .dimensionValue(baseCurrency)
                .materializedPath(materializedPath)
                .isLeaf(false)
                .childCount(0)
                .status("ACTIVE")
                .aggregatedBalance(BigDecimal.ZERO)
                .availableBalance(BigDecimal.ZERO)
                .build();

            rootNode = nodeRepository.save(rootNode);
            log.info("✓ Created ROOT node: {} (ID: {})", rootNode.getNodeCode(), rootNode.getId());

            // ================================================================
            // 2. Create ROOT virtual account
            // ================================================================
            log.info("Step 2: Creating ROOT virtual account...");
            String vaNumber = generateRootVaNumber(program, rootCode);
            
            VirtualAccount rootVa = VirtualAccount.builder()
                .vaNumber(vaNumber)
                .vaName(rootName)
                .programId(programId)
                .corporateId(program.getCorporateId())
                .physicalAccountId(program.getPhysicalAccountId())
                .currencyCode(baseCurrency)
                .hierarchyNodeId(rootNode.getId())
                .hierarchyPath(materializedPath)
                .hierarchyPathVa("/ROOT")
                .hierarchyLevel(1)
                .accountType(VirtualAccount.AccountType.VIRTUAL)
                .accountCategory(VirtualAccount.AccountCategory.ROOT)
                .baseCurrency(baseCurrency)
                .status(VirtualAccount.VaStatus.ACTIVE)
                .currentBalance(BigDecimal.ZERO)
                .availableBalance(BigDecimal.ZERO)
                .aggregatedBalance(BigDecimal.ZERO)
                .aggregatedBalanceBase(BigDecimal.ZERO)
                .build();

            rootVa = virtualAccountRepository.save(rootVa);
            log.info("✓ Created ROOT VA: {} (ID: {})", rootVa.getVaNumber(), rootVa.getId());

            // ================================================================
            // 3. Link node to VA
            // ================================================================
            rootNode.setVirtualAccountId(rootVa.getId());
            nodeRepository.save(rootNode);

            // ================================================================
            // 4. Update program
            // ================================================================
            program.setRootHierarchyNodeId(rootNode.getId());
            program.setHierarchyEnabled(true);
            programRepository.save(program);

            // ================================================================
            // 5. Create Currency Mirrors if requested (for multi-currency support)
            // ================================================================
            List<UUID> currencyMirrorIds = new ArrayList<>();
            List<String> currencyMirrorCurrencies = new ArrayList<>();

            if (request.isCreateCurrencyMirror()) {
                log.info("Step 5: Creating Currency Mirror for base currency {}...", baseCurrency);
                VirtualAccount baseMirror = createCurrencyMirrorForInit(
                    rootVa.getId(), baseCurrency, baseCurrency, program.getCorporateId(), programId);
                if (baseMirror != null) {
                    currencyMirrorIds.add(baseMirror.getId());
                    currencyMirrorCurrencies.add(baseCurrency);
                    log.info("✓ Created CURRENCY_MIRROR: {} for {}", baseMirror.getVaNumber(), baseCurrency);
                }
            }

            // ================================================================
            // 6. Create Exception VAs if requested
            // ================================================================
            List<UUID> exceptionVaIds = new ArrayList<>();
            List<String> exceptionCurrencies = new ArrayList<>();
            
            if (request.isCreateExceptionVa()) {
                log.info("Step 6: Creating Exception VA for base currency {}...", baseCurrency);
                VirtualAccount exceptionVa = createExceptionVaForInit(program, rootNode, baseCurrency);
                if (exceptionVa != null) {
                    exceptionVaIds.add(exceptionVa.getId());
                    exceptionCurrencies.add(baseCurrency);
                    log.info("✓ Created EXCEPTION VA: {} for {}", exceptionVa.getVaNumber(), baseCurrency);
                }
            }

            // ================================================================
            // 7. Additional currencies (for both Exception VAs and Currency Mirrors)
            // ================================================================
            if (request.getAdditionalExceptionCurrencies() != null && !request.getAdditionalExceptionCurrencies().isEmpty()) {
                log.info("Step 7: Processing {} additional currencies...", request.getAdditionalExceptionCurrencies().size());
                
                for (String currency : request.getAdditionalExceptionCurrencies()) {
                    if (!currency.equals(baseCurrency)) {
                        log.info("  Processing currency: {}", currency);
                        
                        // Create currency-specific master node
                        // HierarchyNode currencyNode = findOrCreateCurrencyMasterNode(program, currency);
                        
                        // Create Exception VA for this currency
                        if (request.isCreateExceptionVa()) {
                            VirtualAccount exceptionVa = createExceptionVaForInit(program, rootNode, currency);
                            if (exceptionVa != null) {
                                exceptionVaIds.add(exceptionVa.getId());
                                exceptionCurrencies.add(currency);
                                log.info("  ✓ Created EXCEPTION VA: {} for {}", exceptionVa.getVaNumber(), currency);
                            }
                        }
                        
                        // Create Currency Mirror for this currency
                        if (request.isCreateCurrencyMirror()) {
                            VirtualAccount additionalMirror = createCurrencyMirrorForInit(
                                rootVa.getId(), currency, baseCurrency, program.getCorporateId(), programId);
                            if (additionalMirror != null) {
                                currencyMirrorIds.add(additionalMirror.getId());
                                currencyMirrorCurrencies.add(currency);
                                log.info("  ✓ Created CURRENCY_MIRROR: {} for {}", additionalMirror.getVaNumber(), currency);
                            }
                        }
                    }
                }
            }

            // ================================================================
            // 8. Create Sample Aggregation Path (Levels 2 to hierarchyDepth-1)
            // ================================================================
            int sampleNodesCreated = 0;
            log.info("Step 8: Checking sample path creation - hierarchyDepth: {}", program.getHierarchyDepth());
            if (program.getHierarchyDepth() != null && program.getHierarchyDepth() > 1) {
                log.info("Creating sample aggregation path for hierarchy depth {}...", program.getHierarchyDepth());
                sampleNodesCreated = createSampleAggregationPath(program, rootNode, baseCurrency);
                log.info("✓ Created {} sample aggregation nodes", sampleNodesCreated);
            } else {
                log.warn("Skipping sample path creation - hierarchyDepth is null or <= 1");
            }

            log.info("╔════════════════════════════════════════════════════════════════════════════════╗");
            log.info("║ HIERARCHY INITIALIZATION COMPLETE                                              ║");
            log.info("║ Program: {} | ROOT: {} | Mirrors: {} | Exceptions: {} | Sample: {} ║",
                program.getProgramCode(), rootNode.getId(), currencyMirrorIds.size(), exceptionVaIds.size(), sampleNodesCreated);
            log.info("╚════════════════════════════════════════════════════════════════════════════════╝");

            return InitializationResponse.builder()
                .success(true)
                .programId(programId)
                .programCode(program.getProgramCode())
                .rootNodeId(rootNode.getId())
                .rootVaId(rootVa.getId())
                .rootVaNumber(rootVa.getVaNumber())
                .baseCurrency(baseCurrency)
                .currencyMirrorIds(currencyMirrorIds)
                .currencyMirrorCurrencies(currencyMirrorCurrencies)
                .exceptionVaIds(exceptionVaIds)
                .exceptionCurrencies(exceptionCurrencies)
                .status("INITIALIZED")
                .message("Hierarchy initialized successfully with " + currencyMirrorIds.size() + 
                         " Currency Mirrors and " + exceptionVaIds.size() + " Exception VAs")
                .initializedAt(LocalDateTime.now())
                .build();

        } catch (Exception e) {
            log.error("Failed to initialize hierarchy for program {}: {}", programId, e.getMessage(), e);
            return InitializationResponse.builder()
                .success(false)
                .programId(programId)
                .status("FAILED")
                .message("Failed to initialize hierarchy: " + e.getMessage())
                .build();
        }
    }

    /**
     * Create Currency Mirror during initialization.
     * 
     * Currency Mirrors are SIBLINGS at the ROOT level, not children.
     * They have the SAME hierarchyLevel as ROOT but with parentAccountId pointing to ROOT.
     * This allows the tree view to show them under ROOT while maintaining correct level.
     * 
     * @param parentVaId ROOT VA ID
     * @param currency Currency code for the mirror
     * @param baseCurrency Base currency for FX conversion
     * @param corporateId Corporate ID
     * @param programId Program ID
     * @return Created Currency Mirror VA
     */
    private VirtualAccount createCurrencyMirrorForInit(UUID parentVaId, String currency, 
                                                        String baseCurrency, UUID corporateId,
                                                        UUID programId) {
        log.debug("Creating Currency Mirror for {} under parent {}", currency, parentVaId);
        
        // Validate inputs
        if (parentVaId == null) {
            log.error("Cannot create Currency Mirror: parentVaId is null");
            return null;
        }
        if (currency == null || currency.isEmpty()) {
            log.error("Cannot create Currency Mirror: currency is null or empty");
            return null;
        }
        
        try {
            // Generate unique VA number using UUID to guarantee uniqueness
            String vaNumber = "M-" + currency + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            
            // Simple hierarchy path
            String hierarchyPath = "/M-" + currency;
            
            // Default level = 1 (same as ROOT)
            int level = 1;
            
            // Get FX rate
            BigDecimal fxRate = BigDecimal.ONE;
            if (baseCurrency != null && !currency.equals(baseCurrency)) {
                fxRate = getDefaultFxRate(currency, baseCurrency);
                if (fxRate == null) {
                    fxRate = BigDecimal.ONE;
                }
            }
            
            // Get physical account ID from program - use try-catch to avoid any query issues
            UUID physicalAccountId = null;
            try {
                Program program = programRepository.findById(programId).orElse(null);
                if (program != null) {
                    physicalAccountId = program.getPhysicalAccountId();
                }
            } catch (Exception e) {
                log.warn("Could not get physical account ID from program: {}", e.getMessage());
            }
            
            log.info("Building Currency Mirror VA: vaNumber={}, currency={}, level={}, fxRate={}", 
                vaNumber, currency, level, fxRate);

            // Build the entity directly - NO QUERIES!
            VirtualAccount mirror = VirtualAccount.builder()
                .vaNumber(vaNumber)
                .vaName(currency + " Currency Mirror")
                .programId(programId)
                .corporateId(corporateId)
                .currencyCode(currency)
                .baseCurrency(baseCurrency != null ? baseCurrency : currency)
                .physicalAccountId(physicalAccountId)
                .accountType(VirtualAccount.AccountType.VIRTUAL)
                .accountCategory(VirtualAccount.AccountCategory.CURRENCY_MIRROR)
                .parentAccountId(parentVaId)
                .hierarchyLevel(level)
                .hierarchyPathVa(hierarchyPath)
                // Initialize ALL numeric fields to avoid NPE
                .mirrorBalance(BigDecimal.ZERO)
                .fxRate(fxRate)
                .fxRateAt(LocalDateTime.now())
                .fxRateSource("SYSTEM")
                .balanceInBase(BigDecimal.ZERO)
                .currentBalance(BigDecimal.ZERO)
                .availableBalance(BigDecimal.ZERO)
                .aggregatedBalance(BigDecimal.ZERO)
                .aggregatedBalanceBase(BigDecimal.ZERO)
                .heldBalance(BigDecimal.ZERO)
                .dailyUsed(BigDecimal.ZERO)
                .weeklyUsed(BigDecimal.ZERO)
                .monthlyUsed(BigDecimal.ZERO)
                .annualUsed(BigDecimal.ZERO)
                .dailyTopupUsed(BigDecimal.ZERO)
                .monthlyTopupUsed(BigDecimal.ZERO)
                .creditLimitUtilized(BigDecimal.ZERO)
                .pointsBalance(BigDecimal.ZERO)
                .pendingPoints(BigDecimal.ZERO)
                .lifetimePoints(BigDecimal.ZERO)
                // Initialize Integer fields
                .transactionCount(0)
                .topupCount(0)
                .withdrawalCount(0)
                .kycLevel(0)
                .kycVerified(false)
                .status(VirtualAccount.VaStatus.ACTIVE)
                .specialType(VirtualAccount.VaSpecialType.REGULAR)
                .valueType(VirtualAccount.ValueType.FIAT)
                .build();

            log.info("Saving Currency Mirror VA to database...");
            mirror = virtualAccountRepository.save(mirror);
            log.info("✓ Saved CURRENCY_MIRROR {} (ID: {}) for currency {}", 
                mirror.getVaNumber(), mirror.getId(), currency);

            return mirror;
            
        } catch (Exception e) {
            log.error("Failed to create Currency Mirror for {}: {} - {}", 
                currency, e.getClass().getSimpleName(), e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Create Exception VA during initialization.
     * 
     * Exception VAs are SIBLINGS at the ROOT level, not children.
     * They have the SAME hierarchyLevel as ROOT but with parentAccountId pointing to ROOT.
     * 
     * @param program Program entity
     * @param parentNode ROOT hierarchy node
     * @param currency Currency code
     * @return Created Exception VA
     */
    private VirtualAccount createExceptionVaForInit(Program program, HierarchyNode parentNode, String currency) {
        log.debug("Creating Exception VA for program {} currency {}", program.getProgramCode(), currency);
        
        try {
            // Generate unique VA number for Exception VA
            String programPrefix = program.getProgramCode().length() > 8 
                ? program.getProgramCode().substring(0, 8).toUpperCase()
                : program.getProgramCode().toUpperCase();
            // Add timestamp to make it unique
            String vaNumber = "EXCEPTION-" + currency + "-" + programPrefix + "-" + System.currentTimeMillis() % 10000;

            // Default level = 1 (same as ROOT - they are siblings)
            int level = 1;

            // Exception VA path is sibling path
            String hierarchyPathVa = "/EXCEPTION-" + currency;

            log.info("Building Exception VA: vaNumber={}, currency={}, level={}", vaNumber, currency, level);

            // Create Exception VA - all fields initialized to avoid NPE
            VirtualAccount exceptionVa = VirtualAccount.builder()
                .vaNumber(vaNumber)
                .vaName("Exception Account - " + currency)
                .programId(program.getId())
                .corporateId(program.getCorporateId())
                .physicalAccountId(program.getPhysicalAccountId())
                .currencyCode(currency)
                .accountType(VirtualAccount.AccountType.VIRTUAL)
                .accountCategory(VirtualAccount.AccountCategory.EXCEPTION)
                .specialType(VirtualAccount.VaSpecialType.EXCEPTION)
                .hierarchyNodeId(parentNode.getId())
                .hierarchyPath(parentNode.getMaterializedPath() + "/EXCEPTION-" + currency)
                .hierarchyPathVa(hierarchyPathVa)
                .hierarchyLevel(level)
                .parentAccountId(parentNode.getVirtualAccountId())  // Use node's VA ID directly
                .status(VirtualAccount.VaStatus.ACTIVE)
                // Initialize ALL numeric fields to avoid NPE
                .currentBalance(BigDecimal.ZERO)
                .availableBalance(BigDecimal.ZERO)
                .aggregatedBalance(BigDecimal.ZERO)
                .aggregatedBalanceBase(BigDecimal.ZERO)
                .heldBalance(BigDecimal.ZERO)
                .mirrorBalance(BigDecimal.ZERO)
                .balanceInBase(BigDecimal.ZERO)
                .dailyUsed(BigDecimal.ZERO)
                .weeklyUsed(BigDecimal.ZERO)
                .monthlyUsed(BigDecimal.ZERO)
                .annualUsed(BigDecimal.ZERO)
                .dailyTopupUsed(BigDecimal.ZERO)
                .monthlyTopupUsed(BigDecimal.ZERO)
                .creditLimitUtilized(BigDecimal.ZERO)
                .pointsBalance(BigDecimal.ZERO)
                .pendingPoints(BigDecimal.ZERO)
                .lifetimePoints(BigDecimal.ZERO)
                // Initialize Integer fields
                .transactionCount(0)
                .topupCount(0)
                .withdrawalCount(0)
                .kycLevel(0)
                .kycVerified(false)
                .valueType(VirtualAccount.ValueType.FIAT)
                .build();

            log.info("Saving Exception VA to database...");
            exceptionVa = virtualAccountRepository.save(exceptionVa);
            log.info("✓ Saved EXCEPTION VA {} (ID: {}) for currency {}", 
                exceptionVa.getVaNumber(), exceptionVa.getId(), currency);

            // Update parent node child count - use safe increment
            // if (parentNode.getChildCount() == null) {
            //     parentNode.setChildCount(1);
            // } else {
            //     parentNode.setChildCount(parentNode.getChildCount() + 1);
            // }
            // nodeRepository.save(parentNode);

            return exceptionVa;
            
        } catch (Exception e) {
            log.error("Failed to create Exception VA for {}: {} - {}", 
                currency, e.getClass().getSimpleName(), e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Create a sample aggregation path from Level 2 to hierarchyDepth-1.
     * Uses the first allowedValue from each level's config to create a single sample path.
     * This gives users a working example of the hierarchy structure.
     *
     * @param program The program being initialized
     * @param rootNode The ROOT node (Level 1)
     * @param baseCurrency Base currency for the VAs
     * @return Number of aggregation nodes created
     */
    private int createSampleAggregationPath(Program program, HierarchyNode rootNode, String baseCurrency) {
        int nodesCreated = 0;

        try {
            // Flush pending changes to ensure level configs are visible
            // (applyTemplate may have saved them in the same transaction)
            levelConfigRepository.flush();

            // Get level configurations for this program
            List<HierarchyLevelConfig> levelConfigs = levelConfigRepository.findByProgramIdOrderByLevelNumberAsc(program.getId());

            log.info("Found {} level configs for program {}", levelConfigs.size(), program.getProgramCode());

            // Check if L1 is configured as CURRENCY dimension
            HierarchyLevelConfig l1Config = levelConfigs.stream()
                .filter(c -> c.getLevelNumber() == 1)
                .findFirst()
                .orElse(null);

            boolean l1IsCurrency = l1Config != null && "CURRENCY".equals(l1Config.getDimensionType());

            // If L1 is CURRENCY, create currency mirror node under ROOT at level 2
            // Note: Level 1 is always ROOT (MASTER type). Currency aggregation goes at level 2.
            HierarchyNode currentParent = rootNode;
            int startLevel = 2;

            if (l1IsCurrency) {
                log.info("L1 is CURRENCY dimension - creating currency aggregation node for {} under ROOT at level 2", baseCurrency);
                // Create currency aggregation node UNDER ROOT at level 2 (not level 1)
                // Level 1 is reserved for MASTER (ROOT) type only
                HierarchyNode currencyNode = createAggregationNodeInternal(
                    program, rootNode, baseCurrency, baseCurrency + " Currency Aggregation",
                    "CURRENCY", baseCurrency, 2  // Level 2, not level 1
                );
                if (currencyNode != null) {
                    currentParent = currencyNode;
                    nodesCreated++;
                    startLevel = 3; // Continue from L3 since L2 is now the currency node
                    log.info("✓ Created currency aggregation node {} at level 2 under ROOT", baseCurrency);
                }
            }

            // Determine the depth to create (hierarchyDepth - 1, because last level is for transaction VAs)
            int maxLevel = program.getHierarchyDepth() != null ? program.getHierarchyDepth() - 1 : 6;

            // Ensure we create at least one level
            if (maxLevel < startLevel) {
                log.info("Max level {} is less than start level {}, nothing more to create", maxLevel, startLevel);
                return nodesCreated;
            }

            log.info("Creating sample path from level {} to level {} for program {}", startLevel, maxLevel, program.getProgramCode());

            for (int level = startLevel; level <= maxLevel; level++) {
                // Find config for this level
                final int currentLevel = level;
                HierarchyLevelConfig levelConfig = levelConfigs.stream()
                    .filter(c -> c.getLevelNumber() == currentLevel)
                    .findFirst()
                    .orElse(null);

                // Always use default if no config found (don't skip)
                if (levelConfig == null) {
                    log.info("No config for level {}, creating default config", level);
                    levelConfig = createDefaultLevelConfig(program.getId(), level);
                }

                // Get first allowed value, or use a default
                String nodeCode = getFirstAllowedValue(levelConfig);
                String nodeName = levelConfig.getLevelName() + " - " + nodeCode;

                log.info("  Creating sample node at level {}: {} ({})", level, nodeCode, levelConfig.getDimensionType());

                // Create the aggregation node
                HierarchyNode newNode = createAggregationNodeInternal(
                    program, currentParent, nodeCode, nodeName,
                    levelConfig.getDimensionType(), baseCurrency, level
                );

                if (newNode != null) {
                    nodesCreated++;
                    currentParent = newNode;  // Move down the tree
                    log.info("  ✓ Created sample node {} at level {}", nodeCode, level);
                } else {
                    log.warn("Failed to create sample node at level {}, stopping sample path", level);
                    break;
                }
            }

            log.info("Sample path creation complete: {} nodes created", nodesCreated);
            return nodesCreated;

        } catch (Exception e) {
            log.error("Error creating sample aggregation path: {}", e.getMessage(), e);
            e.printStackTrace();
            return nodesCreated;
        }
    }

    /**
     * Extract the first allowed value from a level config's JSON array.
     * Falls back to a default based on dimension type if no values defined.
     */
    private String getFirstAllowedValue(HierarchyLevelConfig config) {
        if (config.getAllowedValues() != null && !config.getAllowedValues().isEmpty()) {
            // Parse JSON array like ["NORTH", "SOUTH", "EAST", "WEST"]
            String values = config.getAllowedValues();
            if (values.startsWith("[") && values.contains("\"")) {
                int start = values.indexOf("\"") + 1;
                int end = values.indexOf("\"", start);
                if (end > start) {
                    return values.substring(start, end);
                }
            }
        }

        // Return default based on dimension type
        return switch (config.getDimensionType()) {
            case "CURRENCY" -> "AED";
            case "REGION" -> "MENA";
            case "COUNTRY" -> "UAE";
            case "STATE" -> "DUBAI";
            case "CITY" -> "DXB";
            case "ENTITY" -> "HQ";
            case "DEPARTMENT" -> "TREASURY";
            case "COST_CENTER" -> "CC001";
            case "ACCOUNT_TYPE" -> "OPERATING";
            case "CHANNEL" -> "DIRECT";
            case "PLATFORM" -> "CORE";
            case "SEGMENT" -> "CORPORATE";
            case "CUSTOMER" -> "CUST001";
            case "MERCHANT" -> "MERCH001";
            case "PARTNER" -> "PARTNER001";
            case "TIER" -> "GOLD";
            default -> "SAMPLE";
        };
    }

    /**
     * Create a default level config if none exists.
     */
    private HierarchyLevelConfig createDefaultLevelConfig(UUID programId, int level) {
        String dimensionType = switch (level) {
            case 2 -> "REGION";
            case 3 -> "COUNTRY";
            case 4 -> "ENTITY";
            case 5 -> "DEPARTMENT";
            case 6 -> "ACCOUNT_TYPE";
            default -> "ENTITY";
        };

        return HierarchyLevelConfig.builder()
            .programId(programId)
            .levelNumber(level)
            .levelName("Level " + level)
            .dimensionType(dimensionType)
            .isRequired(true)
            .build();
    }

    /**
     * Internal method to create an aggregation node and its VA.
     * Used by sample path creation.
     *
     * CONFIGURABLE DEPTH (v5.3.0): Sets maxDepth from program configuration.
     */
    private HierarchyNode createAggregationNodeInternal(
            Program program, HierarchyNode parentNode,
            String nodeCode, String nodeName, String dimensionType,
            String currencyCode, int level) {

        try {
            String materializedPath = parentNode.getMaterializedPath() + "/" + nodeCode;

            // Get max depth from program configuration
            int maxDepth = program.getMaxHierarchyDepth() != null
                ? program.getMaxHierarchyDepth()
                : HierarchyNode.DEFAULT_MAX_DEPTH;

            // Create hierarchy node with configurable maxDepth
            HierarchyNode node = HierarchyNode.builder()
                .programId(program.getId())
                .parentId(parentNode.getId())
                .levelNumber(level)
                .maxDepth(maxDepth)
                .nodeCode(nodeCode)
                .nodeName(nodeName)
                .nodeType(HierarchyNodeType.CONSOLIDATION)
                .currencyCode(currencyCode)
                .dimensionValue(nodeCode)
                .materializedPath(materializedPath)
                .isLeaf(false)
                .childCount(0)
                .status("ACTIVE")
                .aggregatedBalance(BigDecimal.ZERO)
                .availableBalance(BigDecimal.ZERO)
                .build();

            node = nodeRepository.save(node);

            // Generate VA number
            String vaNumber = generateAggregationVaNumber(program, nodeCode, level);

            // Create aggregation VA
            VirtualAccount aggVa = VirtualAccount.builder()
                .vaNumber(vaNumber)
                .vaName(nodeName)
                .programId(program.getId())
                .corporateId(program.getCorporateId())
                .physicalAccountId(program.getPhysicalAccountId())
                .currencyCode(currencyCode)
                .hierarchyNodeId(node.getId())
                .hierarchyPath(materializedPath)
                .hierarchyLevel(level)
                .parentAccountId(parentNode.getVirtualAccountId())
                .accountType(VirtualAccount.AccountType.VIRTUAL)
                .accountCategory(VirtualAccount.AccountCategory.AGGREGATION)
                .status(VirtualAccount.VaStatus.ACTIVE)
                .currentBalance(BigDecimal.ZERO)
                .availableBalance(BigDecimal.ZERO)
                .aggregatedBalance(BigDecimal.ZERO)
                .aggregatedBalanceBase(BigDecimal.ZERO)
                // FIX: baseCurrency should be the PROGRAM's base currency, not the node's currency
                // This is critical for Currency Mirror propagation to work correctly
                .baseCurrency(program.getCurrencyCode())
                .build();

            aggVa = virtualAccountRepository.save(aggVa);

            // Link node to VA
            node.setVirtualAccountId(aggVa.getId());
            nodeRepository.save(node);

            // Update parent's child count
            parentNode.setChildCount(parentNode.getChildCount() != null ? parentNode.getChildCount() + 1 : 1);
            parentNode.setIsLeaf(false);
            nodeRepository.save(parentNode);

            log.debug("  ✓ Created L{} node: {} -> VA: {}", level, nodeCode, vaNumber);
            return node;

        } catch (Exception e) {
            log.error("Failed to create aggregation node {} at level {}: {} - {}",
                nodeCode, level, e.getClass().getSimpleName(), e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Get hierarchy initialization status for a program.
     */
    @Transactional(readOnly = true)
    public HierarchyStatusResponse getInitializationStatus(UUID programId) {
        Program program = programRepository.findById(programId)
            .orElseThrow(() -> new ResourceNotFoundException("Program not found: " + programId));

        boolean initialized = program.getRootHierarchyNodeId() != null;

        HierarchyStatusResponse.HierarchyStatusResponseBuilder builder = HierarchyStatusResponse.builder()
            .programId(programId)
            .programCode(program.getProgramCode())
            .programName(program.getProgramName())
            .corporateId(program.getCorporateId())
            .initialized(initialized);

        if (initialized) {
            HierarchyNode rootNode = nodeRepository.findById(program.getRootHierarchyNodeId()).orElse(null);
            
            if (rootNode != null) {
                builder.rootNodeId(rootNode.getId())
                       .baseCurrency(rootNode.getCurrencyCode());

                if (rootNode.getVirtualAccountId() != null) {
                    VirtualAccount rootVa = virtualAccountRepository.findById(rootNode.getVirtualAccountId()).orElse(null);
                    if (rootVa != null) {
                        builder.rootVaId(rootVa.getId())
                               .rootVaName(rootVa.getVaName());
                    }
                }
            }

            // Count nodes
            long nodeCount = nodeRepository.countByProgramId(programId);
            builder.nodeCount((int) nodeCount);

            // Count VAs
            long vaCount = virtualAccountRepository.countByProgramId(programId);
            builder.vaCount((int) vaCount);

            builder.legalEntityCount(0);

            // Get exception currencies
            List<VirtualAccount> exceptionVas = getExceptionVas(programId);
            List<String> exceptionCurrencies = exceptionVas.stream()
                .map(VirtualAccount::getCurrencyCode)
                .distinct()
                .toList();
            builder.exceptionCurrencies(exceptionCurrencies);

            // Count settlement VAs
            List<VirtualAccount> settlementVas = getSettlementVas(programId);
            builder.settlementVaCount(settlementVas.size());

            // Count and list Currency Mirrors (NEW)
            List<VirtualAccount> currencyMirrors = getCurrencyMirrors(programId);
            builder.currencyMirrorCount(currencyMirrors.size());
            builder.currencyMirrorCurrencies(currencyMirrors.stream()
                .map(VirtualAccount::getCurrencyCode)
                .distinct()
                .toList());

            // Get template type if applied
            List<HierarchyLevelConfig> configs = levelConfigRepository.findByProgramIdOrderByLevelNumberAsc(programId);
            if (!configs.isEmpty()) {
                builder.templateType(inferTemplateType(configs));
            }
        }

        return builder.build();
    }

    // ========================================================================
    // SETTLEMENT/EXCEPTION VA Operations
    // ========================================================================

    /**
     * Initialize program hierarchy with auto-created Exception VAs.
     */
    public void initializeProgramHierarchy(UUID programId, String templateType) {
        Program program = programRepository.findById(programId)
            .orElseThrow(() -> new ResourceNotFoundException("Program not found: " + programId));

        applyTemplate(programId, templateType);

        String currency = program.getCurrencyCode();
        if (currency == null || currency.isEmpty()) {
            currency = "AED";
        }

        HierarchyNode masterNode = findOrCreateCurrencyMasterNode(program, currency);
        createExceptionVaIfNotExists(program, masterNode, currency);

        log.info("Initialized hierarchy for program {} with currency {}", 
            program.getProgramCode(), currency);
    }

    /**
     * Initialize program hierarchy for multiple currencies.
     */
    public void initializeProgramHierarchyMultiCurrency(UUID programId, String templateType, List<String> currencies) {
        Program program = programRepository.findById(programId)
            .orElseThrow(() -> new ResourceNotFoundException("Program not found: " + programId));

        applyTemplate(programId, templateType);

        for (String currency : currencies) {
            HierarchyNode masterNode = findOrCreateCurrencyMasterNode(program, currency);
            createExceptionVaIfNotExists(program, masterNode, currency);
        }

        log.info("Initialized hierarchy for program {} with {} currencies", 
            program.getProgramCode(), currencies.size());
    }

    /**
     * Find or create the L1 Master node for a currency.
     */
    private HierarchyNode findOrCreateCurrencyMasterNode(Program program, String currency) {
        String nodeCode = currency + "-MASTER";
        
        Optional<HierarchyNode> existingOpt = nodeRepository
            .findByProgramIdAndLevelNumber(program.getId(), 1)
            .stream()
            .filter(n -> n.getCurrencyCode() != null && n.getCurrencyCode().equals(currency))
            .findFirst();
        
        if (existingOpt.isPresent()) {
            return existingOpt.get();
        }

        String path = "/" + program.getProgramCode() + "/" + currency;
        
        HierarchyNode masterNode = HierarchyNode.builder()
            .programId(program.getId())
            .parentId(null)
            .levelNumber(1)
            .nodeCode(nodeCode)
            .nodeName(currency + " Master")
            .nodeType(HierarchyNodeType.MASTER)
            .currencyCode(currency)
            .dimensionValue(currency)
            .materializedPath(path)
            .isLeaf(false)
            .childCount(0)
            .status("ACTIVE")
            .aggregatedBalance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO)
            .build();

        masterNode = nodeRepository.save(masterNode);

        if (program.getRootHierarchyNodeId() == null) {
            program.setRootHierarchyNodeId(masterNode.getId());
            programRepository.save(program);
        }

        log.info("Created L1 master node {} for program {} currency {}", 
            nodeCode, program.getProgramCode(), currency);

        return masterNode;
    }

    /**
     * Create Exception VA for a currency (system-managed).
     */
    private VirtualAccount createExceptionVaIfNotExists(Program program, 
                                                         HierarchyNode masterNode, 
                                                         String currency) {
        List<VirtualAccount> programVas = virtualAccountRepository.findByProgramId(program.getId());
        Optional<VirtualAccount> existingException = programVas.stream()
            .filter(va -> va.getSpecialType() == VirtualAccount.VaSpecialType.EXCEPTION 
                       && currency.equals(va.getCurrencyCode()))
            .findFirst();

        if (existingException.isPresent()) {
            log.debug("Exception VA already exists for program {} currency {}", 
                program.getProgramCode(), currency);
            return existingException.get();
        }

        String programPrefix = program.getProgramCode().length() > 8 
            ? program.getProgramCode().substring(0, 8).toUpperCase()
            : program.getProgramCode().toUpperCase();
        String vaNumber = "EXCEPTION-" + currency + "-" + programPrefix;

        VirtualAccount exceptionVa = VirtualAccount.builder()
            .vaNumber(vaNumber)
            .vaName("Exception Account - " + currency)
            .programId(program.getId())
            .corporateId(program.getCorporateId())
            .physicalAccountId(program.getPhysicalAccountId())
            .currencyCode(currency)
            .specialType(VirtualAccount.VaSpecialType.EXCEPTION)
            .hierarchyNodeId(masterNode.getId())
            .hierarchyPath(masterNode.getMaterializedPath() + "/EXCEPTION")
            .status(VirtualAccount.VaStatus.ACTIVE)
            .currentBalance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO)
            .build();

        exceptionVa = virtualAccountRepository.save(exceptionVa);

        masterNode.setChildCount(masterNode.getChildCount() + 1);
        nodeRepository.save(masterNode);

        log.info("Created Exception VA {} for program {} currency {}", 
            exceptionVa.getVaNumber(), program.getProgramCode(), currency);

        return exceptionVa;
    }

    /**
     * Create Settlement VA at specified hierarchy level.
     */
    public VirtualAccount createSettlementVa(UUID programId, UUID parentNodeId, 
                                              String currency, String vaName) {
        Program program = programRepository.findById(programId)
            .orElseThrow(() -> new ResourceNotFoundException("Program not found: " + programId));

        HierarchyNode parentNode = nodeRepository.findById(parentNodeId)
            .orElseThrow(() -> new ResourceNotFoundException("Parent node not found: " + parentNodeId));

        if (parentNode.getLevelNumber() >= 6) {
            throw new BusinessException("Settlement VA cannot be created at L7 level. " +
                "Please select a parent at L1-L6.");
        }

        String programPrefix = program.getProgramCode().length() > 8 
            ? program.getProgramCode().substring(0, 8).toUpperCase()
            : program.getProgramCode().toUpperCase();
        String levelSuffix = "L" + (parentNode.getLevelNumber() + 1);
        String vaNumber = "SETTLEMENT-" + currency + "-" + programPrefix + "-" + levelSuffix + 
                          "-" + System.currentTimeMillis() % 10000;

        HierarchyNode settlementNode = HierarchyNode.builder()
            .programId(programId)
            .parentId(parentNodeId)
            .levelNumber(parentNode.getLevelNumber() + 1)
            .nodeCode("SETTLEMENT-" + currency)
            .nodeName(vaName != null ? vaName : "Settlement Account - " + currency)
            .nodeType(HierarchyNodeType.CONSOLIDATION)
            .currencyCode(currency)
            .materializedPath(parentNode.getMaterializedPath() + "/SETTLEMENT")
            .isLeaf(true)
            .childCount(0)
            .status("ACTIVE")
            .aggregatedBalance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO)
            .build();

        settlementNode = nodeRepository.save(settlementNode);

        parentNode.setChildCount(parentNode.getChildCount() + 1);
        parentNode.setIsLeaf(false);
        nodeRepository.save(parentNode);

        VirtualAccount settlementVa = VirtualAccount.builder()
            .vaNumber(vaNumber)
            .vaName(vaName != null ? vaName : "Settlement Account - " + currency)
            .programId(programId)
            .corporateId(program.getCorporateId())
            .physicalAccountId(program.getPhysicalAccountId())
            .currencyCode(currency)
            .specialType(VirtualAccount.VaSpecialType.SETTLEMENT)
            .hierarchyNodeId(settlementNode.getId())
            .hierarchyPath(settlementNode.getMaterializedPath())
            .status(VirtualAccount.VaStatus.ACTIVE)
            .currentBalance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO)
            .build();

        settlementVa = virtualAccountRepository.save(settlementVa);

        settlementNode.setVirtualAccountId(settlementVa.getId());
        nodeRepository.save(settlementNode);

        log.info("Created Settlement VA {} at hierarchy level {} for program {} currency {}", 
            settlementVa.getVaNumber(), settlementNode.getLevelNumber(), 
            program.getProgramCode(), currency);

        return settlementVa;
    }

    /**
     * Create an AGGREGATION node under a parent with corresponding VA.
     */
    /**
     * Create an AGGREGATION node under a parent with corresponding VA.
     * 
     * ENHANCED: 
     * 1. Resolves parentNodeId from either HierarchyNode ID or VirtualAccount ID
     * 2. Uses the parent's programId (not the URL programId) to ensure correct hierarchy
     */
    @Transactional
    public CreateAggregationResponse createAggregation(UUID programId, CreateAggregationRequest request) {
        log.info("Creating aggregation node {} under parent {} (URL programId: {})", 
            request.getCode(), request.getParentNodeId(), programId);

        try {
            // STEP 1: Resolve parent node from either Node ID or VA ID
            HierarchyNode parentNode = resolveParentNode(request.getParentNodeId());
            if (parentNode == null) {
                throw new ResourceNotFoundException("Parent node not found: " + request.getParentNodeId() + 
                    " (tried both hierarchy_nodes and virtual_accounts tables)");
            }

            // STEP 2: Use parent's programId (FIX for cross-program hierarchy views)
            UUID actualProgramId = parentNode.getProgramId();
            
            // Log if there's a mismatch (for debugging - common when tree shows cross-program data)
            if (!actualProgramId.equals(programId)) {
                log.info("FIX: Using parent's programId {} instead of URL programId {}", actualProgramId, programId);
            }

            Program program = programRepository.findById(actualProgramId)
                .orElseThrow(() -> new ResourceNotFoundException("Program not found: " + actualProgramId));

            // STEP 3: Validate level constraints
            int newLevel = parentNode.getLevelNumber() + 1;
            if (newLevel > 6) {
                throw new BusinessException("Cannot create aggregation beyond level 6.");
            }

            // STEP 4: Check for duplicate code under parent
            if (nodeRepository.existsByParentIdAndNodeCode(parentNode.getId(), request.getCode())) {
                throw new BusinessException("Node code already exists under parent: " + request.getCode());
            }

            // STEP 5: Determine currency (inherit from parent if not specified)
            String currencyCode = request.getCurrencyCode() != null ? request.getCurrencyCode() : parentNode.getCurrencyCode();
            String materializedPath = parentNode.getMaterializedPath() + "/" + request.getCode();

            // STEP 6: Create hierarchy node
            HierarchyNode node = HierarchyNode.builder()
                .programId(actualProgramId)  // Use actual program ID
                .parentId(parentNode.getId())
                .levelNumber(newLevel)
                .nodeCode(request.getCode())
                .nodeName(request.getName())
                .nodeType(HierarchyNodeType.CONSOLIDATION)
                .currencyCode(currencyCode)
                .dimensionValue(request.getDimensionValue() != null ? request.getDimensionValue() : request.getCode())
                .materializedPath(materializedPath)
                .isLeaf(false)
                .childCount(0)
                .status("ACTIVE")
                .icon(request.getIcon())
                .color(request.getColor())
                .aggregatedBalance(BigDecimal.ZERO)
                .availableBalance(BigDecimal.ZERO)
                .build();

            node = nodeRepository.save(node);

            // STEP 7: Generate VA number and create AGGREGATION VA
            String vaNumber = generateAggregationVaNumber(program, request.getCode(), newLevel);

            VirtualAccount aggVa = VirtualAccount.builder()
                .vaNumber(vaNumber)
                .vaName(request.getName())
                .programId(actualProgramId)  // Use actual program ID
                .corporateId(program.getCorporateId())
                .physicalAccountId(program.getPhysicalAccountId())
                .currencyCode(currencyCode)
                .hierarchyNodeId(node.getId())
                .hierarchyPath(materializedPath)
                .hierarchyLevel(newLevel)
                .parentAccountId(getParentVaId(parentNode))
                .accountType(VirtualAccount.AccountType.VIRTUAL)
                .accountCategory(VirtualAccount.AccountCategory.AGGREGATION)
                .owningEntityId(request.getOwningEntityId())
                .owningEntityCode(request.getOwningEntityCode())
                .status(VirtualAccount.VaStatus.ACTIVE)
                .currentBalance(BigDecimal.ZERO)
                .availableBalance(BigDecimal.ZERO)
                // FIX: baseCurrency should be the PROGRAM's base currency, not the parent node's currency
                // This is critical for Currency Mirror propagation to work correctly
                .baseCurrency(program.getCurrencyCode())
                .build();

            aggVa = virtualAccountRepository.save(aggVa);

            // STEP 8: Link node to VA
            node.setVirtualAccountId(aggVa.getId());
            nodeRepository.save(node);

            // STEP 9: Update parent node's child count
            parentNode.setChildCount(parentNode.getChildCount() != null ? parentNode.getChildCount() + 1 : 1);
            parentNode.setIsLeaf(false);
            nodeRepository.save(parentNode);

            log.info("✓ Created aggregation node {} with VA {} at level {} under program {}", 
                node.getNodeCode(), aggVa.getVaNumber(), newLevel, actualProgramId);

            return CreateAggregationResponse.builder()
                .success(true)
                .nodeId(node.getId())
                .vaId(aggVa.getId())
                .vaNumber(aggVa.getVaNumber())
                .nodeName(node.getNodeName())
                .nodeCode(node.getNodeCode())
                .levelNumber(newLevel)
                .materializedPath(materializedPath)
                .currencyCode(currencyCode)
                .owningEntityId(request.getOwningEntityId())
                .message("Aggregation created successfully")
                .build();

        } catch (BusinessException e) {
            throw e;
        } catch (ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to create aggregation: {}", e.getMessage(), e);
            return CreateAggregationResponse.builder()
                .success(false)
                .message("Failed to create aggregation: " + e.getMessage())
                .build();
        }
    }

    // ========================================================================
    // Query Methods
    // ========================================================================

    public List<VirtualAccount> getSettlementVas(UUID programId) {
        List<VirtualAccount> programVas = virtualAccountRepository.findByProgramId(programId);
        return programVas.stream()
            .filter(va -> va.getSpecialType() == VirtualAccount.VaSpecialType.SETTLEMENT)
            .toList();
    }

    public List<VirtualAccount> getExceptionVas(UUID programId) {
        List<VirtualAccount> programVas = virtualAccountRepository.findByProgramId(programId);
        return programVas.stream()
            .filter(va -> va.getSpecialType() == VirtualAccount.VaSpecialType.EXCEPTION)
            .toList();
    }

    public List<VirtualAccount> getCurrencyMirrors(UUID programId) {
        List<VirtualAccount> programVas = virtualAccountRepository.findByProgramId(programId);
        return programVas.stream()
            .filter(va -> va.getAccountCategory() == VirtualAccount.AccountCategory.CURRENCY_MIRROR)
            .toList();
    }

    public boolean hasExceptionVa(UUID programId, String currency) {
        List<VirtualAccount> programVas = virtualAccountRepository.findByProgramId(programId);
        return programVas.stream()
            .anyMatch(va -> va.getSpecialType() == VirtualAccount.VaSpecialType.EXCEPTION 
                        && currency.equals(va.getCurrencyCode()));
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

        /**
     * Resolve parent node from either a HierarchyNode ID or a VirtualAccount ID.
     * 
     * This method first tries to find the ID in hierarchy_nodes table.
     * If not found, it looks in virtual_accounts table and gets the associated hierarchy node.
     * 
     * @param id UUID that could be either a node ID or a VA ID
     * @return HierarchyNode or null if not found
     */
    private HierarchyNode resolveParentNode(UUID id) {
        if (id == null) {
            return null;
        }
        
        // First, try to find directly in hierarchy_nodes
        Optional<HierarchyNode> nodeOpt = nodeRepository.findById(id);
        if (nodeOpt.isPresent()) {
            log.debug("Found parent as HierarchyNode: {}", id);
            return nodeOpt.get();
        }
        
        // If not found, try to find in virtual_accounts and get the associated hierarchy node
        Optional<VirtualAccount> vaOpt = virtualAccountRepository.findById(id);
        if (vaOpt.isPresent()) {
            VirtualAccount va = vaOpt.get();
            log.debug("Found parent as VirtualAccount: {}, looking for associated hierarchy node", id);
            
            if (va.getHierarchyNodeId() != null) {
                Optional<HierarchyNode> nodeFromVa = nodeRepository.findById(va.getHierarchyNodeId());
                if (nodeFromVa.isPresent()) {
                    log.debug("Found associated hierarchy node: {}", va.getHierarchyNodeId());
                    return nodeFromVa.get();
                }
            }
            
            // If VA doesn't have hierarchyNodeId, try to find node by VA ID
            Optional<HierarchyNode> nodeByVaId = nodeRepository.findByVirtualAccountId(id);
            if (nodeByVaId.isPresent()) {
                log.debug("Found hierarchy node by virtualAccountId: {}", id);
                return nodeByVaId.get();
            }
        }
        
        log.warn("Could not resolve parent node for ID: {}", id);
        return null;
    }


    // ========================================================================
    // Helper methods
    // ========================================================================

    private HierarchyNodeType determineNodeType(int level, int maxDepth) {
        if (level == 1) return HierarchyNodeType.MASTER;
        if (level >= maxDepth) return HierarchyNodeType.VIRTUAL_ACCOUNT;
        return HierarchyNodeType.CONSOLIDATION;
    }

    /**
     * Build tree structure without Currency Mirrors (legacy method).
     */
    private List<TreeNodeResponse> buildTreeStructure(List<HierarchyNode> nodes) {
        return buildTreeStructureWithMirrors(nodes, Collections.emptyList(), null);
    }

    /**
     * Build tree structure with Currency Mirrors included as children.
     *
     * Currency Mirrors are attached to their parent node based on:
     * 1. hierarchyNodeId - Direct linkage to a HierarchyNode
     * 2. parentAccountId chain - Following VA parent links to find the HierarchyNode
     *
     * M-Nodes appear as children of CONSOLIDATION/MASTER nodes with a special
     * CURRENCY_MIRROR nodeType and distinctive icon/color.
     *
     * @param nodes List of HierarchyNode entities
     * @param currencyMirrors List of Currency Mirror VAs
     * @param baseCurrency Program's base currency
     * @return Tree structure with Currency Mirrors included
     */
    private List<TreeNodeResponse> buildTreeStructureWithMirrors(
            List<HierarchyNode> nodes,
            List<VirtualAccount> currencyMirrors,
            String baseCurrency) {

        Map<UUID, TreeNodeResponse> nodeMap = new HashMap<>();
        Map<UUID, UUID> vaIdToNodeId = new HashMap<>();  // Map VA ID to HierarchyNode ID
        List<TreeNodeResponse> roots = new ArrayList<>();

        // First pass: create tree nodes for all hierarchy nodes
        for (HierarchyNode node : nodes) {
            TreeNodeResponse treeNode = TreeNodeResponse.builder()
                .id(node.getId())
                .nodeCode(node.getNodeCode())
                .nodeName(node.getNodeName())
                .nodeType(node.getNodeType())
                .levelNumber(node.getLevelNumber())
                .currencyCode(node.getCurrencyCode())
                .aggregatedBalance(node.getAggregatedBalance())
                .availableBalance(node.getAvailableBalance())
                .status(node.getStatus())
                .icon(node.getIcon())
                .color(node.getColor())
                .isLeaf(node.getIsLeaf())
                .expanded(node.getLevelNumber() <= 3)
                .virtualAccountId(node.getVirtualAccountId())
                .isCurrencyMirror(false)
                .baseCurrency(baseCurrency)
                .children(new ArrayList<>())
                .build();
            nodeMap.put(node.getId(), treeNode);

            // Track VA ID to Node ID mapping
            if (node.getVirtualAccountId() != null) {
                vaIdToNodeId.put(node.getVirtualAccountId(), node.getId());
            }
        }

        // Second pass: attach hierarchy nodes to their parents
        for (HierarchyNode node : nodes) {
            TreeNodeResponse treeNode = nodeMap.get(node.getId());
            if (node.getParentId() == null) {
                roots.add(treeNode);
            } else {
                TreeNodeResponse parent = nodeMap.get(node.getParentId());
                if (parent != null) {
                    parent.getChildren().add(treeNode);
                }
            }
        }

        // Third pass: create and attach Currency Mirror nodes
        if (currencyMirrors != null && !currencyMirrors.isEmpty()) {
            log.info("Processing {} currency mirrors for tree attachment", currencyMirrors.size());

            // Group mirrors by their parent hierarchy node
            Map<UUID, List<VirtualAccount>> mirrorsByNodeId = new HashMap<>();

            // Build a map of ROOT VA IDs to MASTER node IDs for fallback lookup
            Map<UUID, UUID> rootVaToMasterNode = new HashMap<>();
            for (HierarchyNode node : nodes) {
                if (node.getNodeType() == HierarchyNodeType.MASTER ||
                    (node.getLevelNumber() != null && node.getLevelNumber() == 1 && node.getParentId() == null)) {
                    // This is a root/master node - find its linked VA
                    if (node.getVirtualAccountId() != null) {
                        rootVaToMasterNode.put(node.getVirtualAccountId(), node.getId());
                    }
                    // Also register by program's ROOT VA
                    List<VirtualAccount> rootVas = virtualAccountRepository
                        .findByProgramIdAndAccountCategory(node.getProgramId(), AccountCategory.ROOT);
                    for (VirtualAccount rootVa : rootVas) {
                        rootVaToMasterNode.put(rootVa.getId(), node.getId());
                    }
                }
            }
            log.debug("Built rootVaToMasterNode map with {} entries", rootVaToMasterNode.size());

            for (VirtualAccount mirror : currencyMirrors) {
                UUID targetNodeId = findTargetNodeForMirror(mirror, vaIdToNodeId, nodes);

                // Fallback: check if parentAccountId is a ROOT VA
                if (targetNodeId == null && mirror.getParentAccountId() != null) {
                    targetNodeId = rootVaToMasterNode.get(mirror.getParentAccountId());
                    if (targetNodeId != null) {
                        log.debug("Mirror {} found target via rootVaToMasterNode fallback: {}",
                            mirror.getVaNumber(), targetNodeId);
                    }
                }

                if (targetNodeId != null) {
                    mirrorsByNodeId.computeIfAbsent(targetNodeId, k -> new ArrayList<>()).add(mirror);
                } else {
                    log.warn("Currency Mirror {} (parentAccountId={}, hierarchyNodeId={}) has no target hierarchy node - skipping from tree",
                        mirror.getVaNumber(), mirror.getParentAccountId(), mirror.getHierarchyNodeId());
                }
            }

            // Attach mirrors to their parent nodes
            for (Map.Entry<UUID, List<VirtualAccount>> entry : mirrorsByNodeId.entrySet()) {
                UUID nodeId = entry.getKey();
                List<VirtualAccount> mirrors = entry.getValue();
                TreeNodeResponse parentNode = nodeMap.get(nodeId);

                if (parentNode != null) {
                    // Sort mirrors by currency for consistent display
                    mirrors.sort(Comparator.comparing(VirtualAccount::getCurrencyCode));

                    for (VirtualAccount mirror : mirrors) {
                        TreeNodeResponse mirrorNode = createMirrorTreeNode(mirror, parentNode, baseCurrency);
                        // Insert mirrors at the beginning of children (before other nodes)
                        parentNode.getChildren().add(0, mirrorNode);
                    }

                    log.debug("Attached {} currency mirrors to node {} ({})",
                        mirrors.size(), parentNode.getNodeCode(), nodeId);
                }
            }
        }

        return roots;
    }

    /**
     * Find the target HierarchyNode ID for a Currency Mirror.
     *
     * Strategy:
     * 1. If mirror has hierarchyNodeId, use that directly
     * 2. If mirror has parentAccountId, find the VA's linked HierarchyNode
     * 3. For ROOT-level mirrors, find the ROOT/MASTER node
     * 4. Fall back to finding via hierarchy level traversal
     */
    private UUID findTargetNodeForMirror(
            VirtualAccount mirror,
            Map<UUID, UUID> vaIdToNodeId,
            List<HierarchyNode> nodes) {

        // Strategy 1: Direct hierarchyNodeId linkage
        if (mirror.getHierarchyNodeId() != null) {
            log.trace("Mirror {} found target via hierarchyNodeId: {}",
                mirror.getVaNumber(), mirror.getHierarchyNodeId());
            return mirror.getHierarchyNodeId();
        }

        // Strategy 2: Find via parentAccountId
        if (mirror.getParentAccountId() != null) {
            // Direct lookup in our VA-to-Node map
            UUID nodeId = vaIdToNodeId.get(mirror.getParentAccountId());
            if (nodeId != null) {
                log.trace("Mirror {} found target via vaIdToNodeId map: {}",
                    mirror.getVaNumber(), nodeId);
                return nodeId;
            }

            // Parent VA might have hierarchyNodeId set
            VirtualAccount parentVa = virtualAccountRepository.findById(mirror.getParentAccountId()).orElse(null);
            if (parentVa != null) {
                // Check if parent has hierarchyNodeId
                if (parentVa.getHierarchyNodeId() != null) {
                    log.trace("Mirror {} found target via parent VA's hierarchyNodeId: {}",
                        mirror.getVaNumber(), parentVa.getHierarchyNodeId());
                    return parentVa.getHierarchyNodeId();
                }

                // Strategy 3: For ROOT-level mirrors, find the MASTER node
                if (parentVa.getAccountCategory() == AccountCategory.ROOT) {
                    for (HierarchyNode node : nodes) {
                        if (node.getNodeType() == HierarchyNodeType.MASTER) {
                            log.trace("Mirror {} (ROOT-level) found MASTER node: {} ({})",
                                mirror.getVaNumber(), node.getId(), node.getNodeCode());
                            return node.getId();
                        }
                    }
                    // Also try finding by level 1
                    for (HierarchyNode node : nodes) {
                        if (node.getLevelNumber() != null && node.getLevelNumber() == 1) {
                            log.trace("Mirror {} (ROOT-level) found L1 node: {} ({})",
                                mirror.getVaNumber(), node.getId(), node.getNodeCode());
                            return node.getId();
                        }
                    }
                }

                // For AGGREGATION parent, find matching node by VA ID search
                if (parentVa.getAccountCategory() == AccountCategory.AGGREGATION) {
                    for (HierarchyNode node : nodes) {
                        if (parentVa.getId().equals(node.getVirtualAccountId())) {
                            log.trace("Mirror {} found AGGREGATION node by VA match: {} ({})",
                                mirror.getVaNumber(), node.getId(), node.getNodeCode());
                            return node.getId();
                        }
                    }
                }
            }
        }

        // Strategy 4: Match by hierarchy level (if mirror has hierarchyLevel set)
        if (mirror.getHierarchyLevel() != null) {
            for (HierarchyNode node : nodes) {
                if (node.getLevelNumber().equals(mirror.getHierarchyLevel()) &&
                    node.getVirtualAccountId() != null) {
                    // Found a node at same level - check if it's the parent
                    VirtualAccount nodeVa = virtualAccountRepository.findById(node.getVirtualAccountId()).orElse(null);
                    if (nodeVa != null &&
                        (nodeVa.getAccountCategory() == AccountCategory.AGGREGATION ||
                         nodeVa.getAccountCategory() == AccountCategory.ROOT)) {
                        log.trace("Mirror {} found target via hierarchy level match: {} ({})",
                            mirror.getVaNumber(), node.getId(), node.getNodeCode());
                        return node.getId();
                    }
                }
            }
        }

        log.debug("Mirror {} could not find target node (parentAccountId={}, hierarchyNodeId={}, level={})",
            mirror.getVaNumber(), mirror.getParentAccountId(), mirror.getHierarchyNodeId(), mirror.getHierarchyLevel());
        return null;
    }

    /**
     * Create a TreeNodeResponse for a Currency Mirror VA.
     */
    private TreeNodeResponse createMirrorTreeNode(
            VirtualAccount mirror,
            TreeNodeResponse parentNode,
            String baseCurrency) {

        // Determine icon and color based on currency
        String icon = "💱";  // Currency exchange icon
        String color = determineMirrorColor(mirror.getCurrencyCode(), baseCurrency);

        // Node code: M-{currency} or M-{parentCode}-{currency}
        String nodeCode = "M-" + mirror.getCurrencyCode();
        if (parentNode.getNodeCode() != null && !parentNode.getNodeCode().isEmpty()) {
            nodeCode = "M-" + parentNode.getNodeCode() + "-" + mirror.getCurrencyCode();
        }

        // Node name
        String nodeName = mirror.getCurrencyCode() + " Currency Mirror";
        if (baseCurrency != null && baseCurrency.equals(mirror.getCurrencyCode())) {
            nodeName = mirror.getCurrencyCode() + " Base Currency Mirror";
            icon = "🏦";  // Bank icon for base currency
        }

        return TreeNodeResponse.builder()
            .id(mirror.getId())
            .nodeCode(nodeCode)
            .nodeName(nodeName)
            .nodeType(HierarchyNodeType.CURRENCY_MIRROR)
            .levelNumber(parentNode.getLevelNumber())  // Same level as parent (shown as child visually)
            .currencyCode(mirror.getCurrencyCode())
            .aggregatedBalance(mirror.getAggregatedBalance())
            .availableBalance(mirror.getAvailableBalance())
            .status(mirror.getStatus() != null ? mirror.getStatus().name() : "ACTIVE")
            .icon(icon)
            .color(color)
            .isLeaf(true)  // Currency Mirrors don't have children in the hierarchy
            .expanded(false)
            .virtualAccountId(mirror.getId())
            .vaNumber(mirror.getVaNumber())
            .isCurrencyMirror(true)
            .baseCurrency(baseCurrency)
            .mirrorBalance(mirror.getMirrorBalance())
            .balanceInBase(mirror.getBalanceInBase())
            .fxRate(mirror.getFxRate())
            .parentMirrorId(mirror.getParentAccountId())
            .children(new ArrayList<>())  // Empty children list
            .build();
    }

    /**
     * Determine color for Currency Mirror based on currency.
     */
    private String determineMirrorColor(String currency, String baseCurrency) {
        if (currency == null) return "#808080";  // Gray for unknown

        // Base currency gets a distinct color
        if (currency.equals(baseCurrency)) {
            return "#2E7D32";  // Green for base currency
        }

        // Color map for common currencies
        return switch (currency.toUpperCase()) {
            case "USD" -> "#1565C0";  // Blue
            case "EUR" -> "#6A1B9A";  // Purple
            case "GBP" -> "#C62828";  // Red
            case "JPY" -> "#EF6C00";  // Orange
            case "CHF" -> "#00695C";  // Teal
            case "AUD" -> "#558B2F";  // Light Green
            case "CAD" -> "#D84315";  // Deep Orange
            case "SGD" -> "#00838F";  // Cyan
            case "AED" -> "#2E7D32";  // Green (UAE)
            case "SAR" -> "#4527A0";  // Deep Purple (Saudi)
            default -> "#455A64";  // Blue Grey for others
        };
    }

    private LevelConfigResponse toResponse(HierarchyLevelConfig config) {
        return LevelConfigResponse.builder()
            .id(config.getId())
            .programId(config.getProgramId())
            .levelNumber(config.getLevelNumber())
            .levelName(config.getLevelName())
            .dimensionType(config.getDimensionType())
            .isRequired(config.getIsRequired())
            .description(config.getDescription())
            .icon(config.getIcon())
            .createdAt(config.getCreatedAt())
            .updatedAt(config.getUpdatedAt())
            .build();
    }

    private NodeResponse toNodeResponse(HierarchyNode node) {
        return NodeResponse.builder()
            .id(node.getId())
            .programId(node.getProgramId())
            .parentId(node.getParentId())
            .levelNumber(node.getLevelNumber())
            .nodeCode(node.getNodeCode())
            .nodeName(node.getNodeName())
            .nodeType(node.getNodeType())
            .currencyCode(node.getCurrencyCode())
            .dimensionValue(node.getDimensionValue())
            .materializedPath(node.getMaterializedPath())
            .isLeaf(node.getIsLeaf())
            .childCount(node.getChildCount())
            .virtualAccountId(node.getVirtualAccountId())
            .aggregatedBalance(node.getAggregatedBalance())
            .availableBalance(node.getAvailableBalance())
            .heldBalance(node.getHeldBalance())
            .lastAggregatedAt(node.getLastAggregatedAt())
            .icon(node.getIcon())
            .color(node.getColor())
            .displayOrder(node.getDisplayOrder())
            .status(node.getStatus())
            .tags(node.getTags())
            .createdAt(node.getCreatedAt())
            .updatedAt(node.getUpdatedAt())
            .build();
    }

    private String generateRootVaNumber(Program program, String rootCode) {
        String prefix = program.getProgramCode().length() > 6 
            ? program.getProgramCode().substring(0, 6).toUpperCase()
            : program.getProgramCode().toUpperCase();
        return prefix + "-" + rootCode + "-" + System.currentTimeMillis() % 100000;
    }

    private String generateAggregationVaNumber(Program program, String nodeCode, int level) {
        String prefix = program.getProgramCode().length() > 6 
            ? program.getProgramCode().substring(0, 6).toUpperCase()
            : program.getProgramCode().toUpperCase();
        return prefix + "-L" + level + "-" + nodeCode.toUpperCase() + "-" + System.currentTimeMillis() % 10000;
    }

    private UUID getParentVaId(HierarchyNode parentNode) {
        if (parentNode.getVirtualAccountId() != null) {
            return parentNode.getVirtualAccountId();
        }
        return virtualAccountRepository.findByHierarchyNodeId(parentNode.getId())
            .map(VirtualAccount::getId)
            .orElse(null);
    }

    private String inferTemplateType(List<HierarchyLevelConfig> configs) {
        if (configs.isEmpty()) return null;
        
        String firstDim = configs.get(0).getDimensionType();
        if (configs.size() >= 2) {
            String secondDim = configs.get(1).getDimensionType();
            if ("CURRENCY".equals(firstDim)) {
                if ("REGION".equals(secondDim)) return "IHB_PROGRAM";
                if ("CHANNEL".equals(secondDim)) return "COLLECTION_PROGRAM";
                if ("USER_TYPE".equals(secondDim) || "SEGMENT".equals(secondDim)) return "WALLET_PROGRAM";
                if ("CARD_TYPE".equals(secondDim)) return "GIFT_CARD_PROGRAM";
            }
        }
        return "CUSTOM";
    }

    private BigDecimal getDefaultFxRate(String fromCurrency, String toCurrency) {
        if (fromCurrency.equals(toCurrency)) {
            return BigDecimal.ONE;
        }

        BigDecimal fromRate = EXCHANGE_RATES_TO_AED.getOrDefault(fromCurrency, BigDecimal.ONE);
        BigDecimal toRate = EXCHANGE_RATES_TO_AED.getOrDefault(toCurrency, BigDecimal.ONE);

        if (toCurrency.equals("AED")) {
            return fromRate;
        } else if (fromCurrency.equals("AED")) {
            return BigDecimal.ONE.divide(toRate, 6, RoundingMode.HALF_UP);
        } else {
            return fromRate.divide(toRate, 6, RoundingMode.HALF_UP);
        }
    }

    // ========================================================================
    // Template factory methods
    // ========================================================================

    private List<LevelConfigRequest> createIhbTemplate() {
        return Arrays.asList(
            LevelConfigRequest.builder().levelNumber(1).levelName("Currency").dimensionType("CURRENCY").build(),
            LevelConfigRequest.builder().levelNumber(2).levelName("Region").dimensionType("REGION")
                .allowedValues(Arrays.asList("NORTH", "SOUTH", "EAST", "WEST")).build(),
            LevelConfigRequest.builder().levelNumber(3).levelName("State").dimensionType("STATE").build(),
            LevelConfigRequest.builder().levelNumber(4).levelName("City").dimensionType("CITY").build(),
            LevelConfigRequest.builder().levelNumber(5).levelName("Entity").dimensionType("ENTITY").build(),
            LevelConfigRequest.builder().levelNumber(6).levelName("Account Type").dimensionType("ACCOUNT_TYPE")
                .allowedValues(Arrays.asList("PAYABLES", "RECEIVABLES", "TAXES", "PAYROLL", "CAPEX", "INTERCOMPANY")).build(),
            LevelConfigRequest.builder().levelNumber(7).levelName("Virtual Account").dimensionType("VIRTUAL_ACCOUNT").build()
        );
    }

    private List<LevelConfigRequest> createCollectionTemplate() {
        return Arrays.asList(
            LevelConfigRequest.builder().levelNumber(1).levelName("Currency").dimensionType("CURRENCY").build(),
            LevelConfigRequest.builder().levelNumber(2).levelName("Channel").dimensionType("CHANNEL")
                .allowedValues(Arrays.asList("INVOICE", "ECOMMERCE", "POS", "DIRECT")).build(),
            LevelConfigRequest.builder().levelNumber(3).levelName("Platform").dimensionType("PLATFORM").build(),
            LevelConfigRequest.builder().levelNumber(4).levelName("Segment").dimensionType("SEGMENT").build(),
            LevelConfigRequest.builder().levelNumber(5).levelName("Customer").dimensionType("CUSTOMER").build(),
            LevelConfigRequest.builder().levelNumber(6).levelName("Account Type").dimensionType("ACCOUNT_TYPE").build(),
            LevelConfigRequest.builder().levelNumber(7).levelName("Virtual Account").dimensionType("VIRTUAL_ACCOUNT").build()
        );
    }

    private List<LevelConfigRequest> createWalletTemplate() {
        return Arrays.asList(
            LevelConfigRequest.builder().levelNumber(1).levelName("Currency").dimensionType("CURRENCY").build(),
            LevelConfigRequest.builder().levelNumber(2).levelName("User Type").dimensionType("SEGMENT")
                .allowedValues(Arrays.asList("CONSUMER", "MERCHANT", "AGENT")).build(),
            LevelConfigRequest.builder().levelNumber(3).levelName("Region").dimensionType("REGION").build(),
            LevelConfigRequest.builder().levelNumber(4).levelName("KYC Tier").dimensionType("KYC_TIER")
                .allowedValues(Arrays.asList("TIER_1", "TIER_2", "TIER_3")).build(),
            LevelConfigRequest.builder().levelNumber(5).levelName("Group").dimensionType("ENTITY").build(),
            LevelConfigRequest.builder().levelNumber(6).levelName("Wallet Type").dimensionType("ACCOUNT_TYPE").build(),
            LevelConfigRequest.builder().levelNumber(7).levelName("Wallet").dimensionType("VIRTUAL_ACCOUNT").build()
        );
    }

    private List<LevelConfigRequest> createLoyaltyTemplate() {
        return Arrays.asList(
            LevelConfigRequest.builder().levelNumber(1).levelName("Program").dimensionType("CURRENCY").build(),
            LevelConfigRequest.builder().levelNumber(2).levelName("Partner Type").dimensionType("CHANNEL").build(),
            LevelConfigRequest.builder().levelNumber(3).levelName("Tier").dimensionType("TIER")
                .allowedValues(Arrays.asList("PLATINUM", "GOLD", "SILVER", "BLUE")).build(),
            LevelConfigRequest.builder().levelNumber(4).levelName("Earn Category").dimensionType("SEGMENT").build(),
            LevelConfigRequest.builder().levelNumber(5).levelName("Partner").dimensionType("PARTNER").build(),
            LevelConfigRequest.builder().levelNumber(6).levelName("Points Status").dimensionType("POINTS_STATUS")
                .allowedValues(Arrays.asList("EARNED", "AVAILABLE", "PENDING", "EXPIRED")).build(),
            LevelConfigRequest.builder().levelNumber(7).levelName("Member Wallet").dimensionType("VIRTUAL_ACCOUNT").build()
        );
    }

    private List<LevelConfigRequest> createEscrowTemplate() {
        return Arrays.asList(
            LevelConfigRequest.builder().levelNumber(1).levelName("Currency").dimensionType("CURRENCY").isRequired(true).build(),
            LevelConfigRequest.builder().levelNumber(2).levelName("Escrow Type").dimensionType("ESCROW_TYPE")
                .allowedValues(Arrays.asList("REAL_ESTATE", "M_AND_A", "TRADE", "PROJECT", "RENTAL", "MARKETPLACE")).build(),
            LevelConfigRequest.builder().levelNumber(3).levelName("Transaction").dimensionType("TRANSACTION").build(),
            LevelConfigRequest.builder().levelNumber(4).levelName("Party Role").dimensionType("PARTY_ROLE")
                .allowedValues(Arrays.asList("BUYER", "SELLER", "AGENT", "BROKER", "FEES", "TAXES")).build(),
            LevelConfigRequest.builder().levelNumber(5).levelName("Escrow Account").dimensionType("VIRTUAL_ACCOUNT").isRequired(true).build()
        );
    }

    private List<LevelConfigRequest> createVibanTemplate() {
        return Arrays.asList(
            LevelConfigRequest.builder().levelNumber(1).levelName("Currency").dimensionType("CURRENCY").isRequired(true).build(),
            LevelConfigRequest.builder().levelNumber(2).levelName("VIBAN Pool").dimensionType("POOL").build(),
            LevelConfigRequest.builder().levelNumber(3).levelName("Customer Type").dimensionType("CUSTOMER_TYPE")
                .allowedValues(Arrays.asList("CORPORATE", "SME", "RETAIL", "FINTECH", "GOVERNMENT")).build(),
            LevelConfigRequest.builder().levelNumber(4).levelName("Customer").dimensionType("CUSTOMER").build(),
            LevelConfigRequest.builder().levelNumber(5).levelName("VIBAN Account").dimensionType("VIRTUAL_ACCOUNT").isRequired(true).build()
        );
    }

    private List<LevelConfigRequest> createPayablesTemplate() {
        return Arrays.asList(
            LevelConfigRequest.builder().levelNumber(1).levelName("Currency").dimensionType("CURRENCY").isRequired(true).build(),
            LevelConfigRequest.builder().levelNumber(2).levelName("Payment Type").dimensionType("PAYMENT_TYPE")
                .allowedValues(Arrays.asList("SUPPLIER", "PAYROLL", "TAX", "UTILITY", "INTERCOMPANY", "EXPENSE")).build(),
            LevelConfigRequest.builder().levelNumber(3).levelName("Entity").dimensionType("ENTITY").build(),
            LevelConfigRequest.builder().levelNumber(4).levelName("Cost Center").dimensionType("COST_CENTER").build(),
            LevelConfigRequest.builder().levelNumber(5).levelName("Beneficiary Type").dimensionType("BENEFICIARY_TYPE")
                .allowedValues(Arrays.asList("DOMESTIC", "INTERNATIONAL", "INTERNAL", "GOVERNMENT")).build(),
            LevelConfigRequest.builder().levelNumber(6).levelName("Payable Account").dimensionType("VIRTUAL_ACCOUNT").isRequired(true).build()
        );
    }

    private List<LevelConfigRequest> createGiftCardTemplate() {
        return Arrays.asList(
            LevelConfigRequest.builder().levelNumber(1).levelName("Currency").dimensionType("CURRENCY").build(),
            LevelConfigRequest.builder().levelNumber(2).levelName("Card Type").dimensionType("CARD_TYPE")
                .allowedValues(Arrays.asList("OPEN_LOOP", "CLOSED_LOOP", "SEMI_CLOSED")).build(),
            LevelConfigRequest.builder().levelNumber(3).levelName("Merchant").dimensionType("MERCHANT").build(),
            LevelConfigRequest.builder().levelNumber(4).levelName("Channel").dimensionType("CHANNEL")
                .allowedValues(Arrays.asList("RETAIL", "CORPORATE", "DIGITAL")).build(),
            LevelConfigRequest.builder().levelNumber(5).levelName("Batch").dimensionType("BATCH").build(),
            LevelConfigRequest.builder().levelNumber(6).levelName("Denomination").dimensionType("DENOMINATION").build(),
            LevelConfigRequest.builder().levelNumber(7).levelName("Gift Card").dimensionType("VIRTUAL_ACCOUNT").build()
        );
    }

    private List<LevelConfigRequest> createCorporateCardTemplate() {
        return Arrays.asList(
            LevelConfigRequest.builder().levelNumber(1).levelName("Currency").dimensionType("CURRENCY").build(),
            LevelConfigRequest.builder().levelNumber(2).levelName("Card Program").dimensionType("CARD_TYPE")
                .allowedValues(Arrays.asList("TRAVEL", "PROCUREMENT", "FLEET", "VIRTUAL")).build(),
            LevelConfigRequest.builder().levelNumber(3).levelName("Region").dimensionType("REGION").build(),
            LevelConfigRequest.builder().levelNumber(4).levelName("Department").dimensionType("DEPARTMENT").build(),
            LevelConfigRequest.builder().levelNumber(5).levelName("Budget Owner").dimensionType("BUDGET_OWNER").build(),
            LevelConfigRequest.builder().levelNumber(6).levelName("Card Format").dimensionType("CARD_FORMAT")
                .allowedValues(Arrays.asList("PHYSICAL", "VIRTUAL")).build(),
            LevelConfigRequest.builder().levelNumber(7).levelName("Card Account").dimensionType("VIRTUAL_ACCOUNT").build()
        );
    }
}