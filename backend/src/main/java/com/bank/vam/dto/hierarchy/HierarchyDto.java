package com.bank.vam.dto.hierarchy;

import com.bank.vam.entity.hierarchy.HierarchyNode.HierarchyNodeType;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * DTOs for Hierarchy operations.
 */
public class HierarchyDto {

    // ========================================================================
    // Level Configuration DTOs
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LevelConfigRequest {
        private Integer levelNumber;
        private String levelName;
        private String dimensionType;
        private Boolean isRequired;
        private List<String> allowedValues;
        private String description;
        private String icon;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LevelConfigResponse {
        private UUID id;
        private UUID programId;
        private Integer levelNumber;
        private String levelName;
        private String dimensionType;
        private Boolean isRequired;
        private List<String> allowedValues;
        private String description;
        private String icon;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    // ========================================================================
    // Node DTOs
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NodeCreateRequest {
        private UUID parentId;
        private Integer levelNumber;
        private String nodeCode;
        private String nodeName;
        private HierarchyNodeType nodeType;
        private String currencyCode;
        private String dimensionValue;
        private UUID virtualAccountId;
        private String icon;
        private String color;
        private Integer displayOrder;
        private String metadata;
        private String tags;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NodeUpdateRequest {
        private String nodeName;
        private String dimensionValue;
        private String icon;
        private String color;
        private Integer displayOrder;
        private String metadata;
        private String tags;
        private String status;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NodeMoveRequest {
        private UUID newParentId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NodeResponse {
        private UUID id;
        private UUID programId;
        private UUID parentId;
        private Integer levelNumber;
        private String nodeCode;
        private String nodeName;
        private HierarchyNodeType nodeType;
        private String currencyCode;
        private String dimensionValue;
        private String materializedPath;
        private Boolean isLeaf;
        private Integer childCount;
        private UUID virtualAccountId;
        
        // Balance info
        private BigDecimal aggregatedBalance;
        private BigDecimal availableBalance;
        private BigDecimal heldBalance;
        private LocalDateTime lastAggregatedAt;
        
        // Display
        private String icon;
        private String color;
        private Integer displayOrder;
        private String status;
        private String tags;
        
        // Audit
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    // ========================================================================
    // Tree DTOs
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TreeNodeResponse {
        private UUID id;
        private String nodeCode;
        private String nodeName;
        private HierarchyNodeType nodeType;
        private Integer levelNumber;
        private String currencyCode;
        private BigDecimal aggregatedBalance;
        private BigDecimal availableBalance;
        private String status;
        private String icon;
        private String color;
        private Boolean isLeaf;
        private Boolean expanded;
        private UUID virtualAccountId;
        private String vaNumber;
        private List<TreeNodeResponse> children;

        // Currency Mirror specific fields (v5.4.0)
        private Boolean isCurrencyMirror;      // true if this node represents a Currency Mirror VA
        private String baseCurrency;           // The program's base currency (for M-nodes)
        private BigDecimal mirrorBalance;      // Balance in mirror currency
        private BigDecimal balanceInBase;      // Balance converted to base currency
        private BigDecimal fxRate;             // FX rate used for conversion
        private UUID parentMirrorId;           // Parent Currency Mirror VA ID (for M-node chain)
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TreeResponse {
        private UUID programId;
        private String programCode;
        private String programName;
        private List<LevelConfigResponse> levelConfigs;
        private List<TreeNodeResponse> roots;
        private TreeStats stats;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TreeStats {
        private Integer totalNodes;
        private Integer leafNodes;
        private Integer maxDepth;
        private BigDecimal totalBalance;
        private BigDecimal totalAvailableBalance;
        private LocalDateTime lastUpdated;
    }

    // ========================================================================
    // Breadcrumb DTO (for navigation)
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BreadcrumbItem {
        private UUID id;
        private String nodeCode;
        private String nodeName;
        private Integer levelNumber;
        private String levelName;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BreadcrumbResponse {
        private List<BreadcrumbItem> path;
        private NodeResponse currentNode;
    }

    // ========================================================================
    // Balance DTOs
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BalanceUpdateRequest {
        private BigDecimal delta;
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BalanceByLevelResponse {
        private Integer levelNumber;
        private String levelName;
        private Integer nodeCount;
        private BigDecimal totalBalance;
        private BigDecimal totalAvailableBalance;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BalanceSummaryResponse {
        private UUID programId;
        private BigDecimal totalBalance;
        private BigDecimal totalAvailableBalance;
        private BigDecimal totalHeldBalance;
        private List<BalanceByLevelResponse> byLevel;
        private LocalDateTime asOf;
    }

    // ========================================================================
    // Template DTOs
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TemplateRequest {
        private String templateType;  // IHB, COLLECTION, WALLET, ESCROW, CORPORATE_CARD, LOYALTY, GIFT_CARD
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TemplateResponse {
        private String templateType;
        private String description;
        private List<LevelConfigResponse> levelConfigs;
    }

    // ========================================================================
    // Search DTOs
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchRequest {
        private String searchTerm;
        private Integer levelNumber;
        private HierarchyNodeType nodeType;
        private String status;
        private BigDecimal minBalance;
        private BigDecimal maxBalance;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchResponse {
        private List<NodeResponse> results;
        private Integer totalCount;
        private String searchTerm;
    }

    // ========================================================================
    // Hierarchy Initialization DTOs (NEW)
    // ========================================================================

    /**
     * Request to initialize hierarchy for a program.
     * Creates ROOT node and ROOT VA with optional Exception VAs and Currency Mirrors.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InitializeHierarchyRequest {
        /** Name for the ROOT node, e.g., "Group Treasury" */
        private String rootName;
        
        /** Code for the ROOT node, e.g., "ROOT" */
        private String rootCode;
        
        /** Base currency for the hierarchy, e.g., "AED" - required */
        private String baseCurrency;
        
        /** Whether to create Exception VA for unmatched transactions */
        private boolean createExceptionVa;
        
        /** Whether to create Currency Mirror for base currency (default: true) */
        @Builder.Default
        private boolean createCurrencyMirror = true;
        
        /** Additional currencies for Exception VAs and Currency Mirrors (multi-currency support) */
        private List<String> additionalExceptionCurrencies;
        
        /** Optional: Hierarchy template to apply (IHB_PROGRAM, COLLECTION_PROGRAM, etc.) */
        private String templateType;
    }

    /**
     * Response after hierarchy initialization.
     * Includes ROOT, Exception VAs, and Currency Mirrors created.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InitializationResponse {
        private boolean success;
        private UUID programId;
        private String programCode;
        private UUID rootNodeId;
        private UUID rootVaId;
        private String rootVaNumber;
        private String baseCurrency;
        
        // Exception VAs
        private List<UUID> exceptionVaIds;
        private List<String> exceptionCurrencies;
        
        // Currency Mirrors (NEW)
        private List<UUID> currencyMirrorIds;
        private List<String> currencyMirrorCurrencies;
        
        private String status;            // INITIALIZED, ALREADY_INITIALIZED, FAILED
        private String message;
        private LocalDateTime initializedAt;
    }

    /**
     * Response for hierarchy initialization status check.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HierarchyStatusResponse {
        private UUID programId;
        private String programCode;
        private String programName;
        private UUID corporateId;
        private String corporateName;
        
        /** Whether hierarchy is initialized (has ROOT node) */
        private boolean initialized;
        
        /** ROOT hierarchy node ID */
        private UUID rootNodeId;
        
        /** ROOT virtual account ID */
        private UUID rootVaId;
        
        /** ROOT virtual account name */
        private String rootVaName;
        
        /** Base currency of the hierarchy */
        private String baseCurrency;
        
        /** Total hierarchy nodes count */
        private int nodeCount;
        
        /** Total virtual accounts in hierarchy */
        private int vaCount;
        
        /** Total legal entities linked to hierarchy */
        private int legalEntityCount;
        
        /** Exception VA currencies configured */
        private List<String> exceptionCurrencies;
        
        /** Settlement VA count */
        private int settlementVaCount;
        
        /** Currency Mirror count (NEW) */
        private int currencyMirrorCount;
        
        /** Currency Mirror currencies (NEW) */
        private List<String> currencyMirrorCurrencies;
        
        /** When hierarchy was initialized */
        private LocalDateTime initializedAt;
        
        /** Hierarchy template type if applied */
        private String templateType;
    }

    /**
     * Request to create an AGGREGATION node under a parent.
     * AGGREGATION nodes are intermediate consolidation points (L2-L6).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateAggregationRequest {
        /** Node name - required, e.g., "EMEA Region" */
        private String name;
        
        /** Node code - required, e.g., "EMEA" */
        private String code;
        
        /** Parent hierarchy node ID - required */
        private UUID parentNodeId;
        
        /** Currency code - defaults to parent's currency if not provided */
        private String currencyCode;
        
        /** Optional: Legal Entity that owns this aggregation (for POBO/COBO) */
        private UUID owningEntityId;
        
        /** Optional: Legal Entity code for display purposes */
        private String owningEntityCode;
        
        /** Dimension type - REGION, ENTITY, DEPARTMENT, COST_CENTER, etc. */
        private String dimensionType;
        
        /** Actual dimension value, defaults to code if not provided */
        private String dimensionValue;
        
        /** Display icon */
        private String icon;
        
        /** Display color */
        private String color;
    }

    /**
     * Response for aggregation creation.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateAggregationResponse {
        private boolean success;
        private UUID nodeId;
        private UUID vaId;
        private String vaNumber;
        private String nodeName;
        private String nodeCode;
        private int levelNumber;
        private String materializedPath;
        private String currencyCode;
        private UUID owningEntityId;
        private String message;
    }
}