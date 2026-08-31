package com.bank.vam.entity.hierarchy;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Represents a node in the hierarchy tree.
 * Uses hybrid storage pattern: adjacency list (parent_id) + materialized path.
 *
 * Materialized path enables efficient queries:
 * - Get all ancestors: WHERE my_path LIKE node_path || '/%'
 * - Get all descendants: WHERE node_path LIKE my_path || '/%'
 *
 * Node types:
 * - MASTER: Level 1 root node (currency/physical account mirror)
 * - CONSOLIDATION: Intermediate grouping nodes (level 2 to maxDepth-1)
 * - VIRTUAL_ACCOUNT: Leaf nodes linked to VirtualAccount (can be at any level)
 *
 * CONFIGURABLE DEPTH (v5.3.0):
 * ============================
 * Hierarchy depth is now configurable per program (default: 20, max: 50).
 * The validation no longer enforces a fixed 7-level limit.
 * - Level 1 is always MASTER (ROOT)
 * - Intermediate levels (2 to N-1) are CONSOLIDATION
 * - Leaf level (any level) can be VIRTUAL_ACCOUNT
 *
 * DYNAMIC DEPTH ON MOVE:
 * ======================
 * When a subtree is moved to a new parent, levels are recalculated:
 * - newLevel = targetParentLevel + relativeDepthInSubtree
 * - This allows flexible restructuring of hierarchies
 */
@Entity
@Table(name = "hierarchy_nodes",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_program_path",
        columnNames = {"program_id", "materialized_path"}
    ),
    indexes = {
        @Index(name = "idx_hierarchy_nodes_program_id", columnList = "program_id"),
        @Index(name = "idx_hierarchy_nodes_parent_id", columnList = "parent_id"),
        @Index(name = "idx_hierarchy_nodes_virtual_account_id", columnList = "virtual_account_id"),
        @Index(name = "idx_hierarchy_nodes_level", columnList = "program_id, level_number"),
        @Index(name = "idx_hierarchy_nodes_status", columnList = "status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HierarchyNode extends BaseEntity {

    // ========================================================================
    // Core identification
    // ========================================================================

    /**
     * Reference to the program this node belongs to.
     */
    @Column(name = "program_id", nullable = false)
    private UUID programId;

    /**
     * Parent node ID (null for root nodes).
     */
    @Column(name = "parent_id")
    private UUID parentId;

    /**
     * Level number (1 to maxDepth, configurable per program).
     * Default max depth is 20, absolute max is 50.
     */
    @Column(name = "level_number", nullable = false)
    private Integer levelNumber;

    /**
     * Maximum depth for this hierarchy (copied from Program.hierarchyDepth).
     * Used for validation. Default: 20, Max: 50.
     */
    @Column(name = "max_depth")
    @Builder.Default
    private Integer maxDepth = DEFAULT_MAX_DEPTH;

    // Constants for hierarchy depth
    public static final int DEFAULT_MAX_DEPTH = 20;
    public static final int ABSOLUTE_MAX_DEPTH = 50;

    /**
     * Unique code for this node within its parent.
     * Used in materialized path construction.
     */
    @Column(name = "node_code", nullable = false, length = 50)
    private String nodeCode;

    /**
     * Display name for this node.
     */
    @Column(name = "node_name", nullable = false, length = 100)
    private String nodeName;

    /**
     * Type of node: MASTER, CONSOLIDATION, or VIRTUAL_ACCOUNT.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "node_type", nullable = false, length = 20)
    private HierarchyNodeType nodeType;

    // ========================================================================
    // Classification
    // ========================================================================

    /**
     * Currency code for this node (inherited from parent if not set).
     */
    @Column(name = "currency_code", length = 3)
    private String currencyCode;

    /**
     * The dimension value this node represents.
     * Example: For a REGION dimension, value might be "NORTH".
     */
    @Column(name = "dimension_value", length = 100)
    private String dimensionValue;

    // ========================================================================
    // Materialized path
    // ========================================================================

    /**
     * Full path from root to this node.
     * Format: /{programCode}/{L1Code}/{L2Code}/.../{nodeCode}
     * Example: /IHB-001/INR/WEST/MH/MUMBAI/ANDHERI/PAYABLES/VA-PAY-001
     */
    @Column(name = "materialized_path", nullable = false, length = 500)
    private String materializedPath;

    // ========================================================================
    // Tree structure
    // ========================================================================

    /**
     * Whether this is a leaf node (has no children).
     * VIRTUAL_ACCOUNT nodes are always leaves.
     */
    @Column(name = "is_leaf")
    @Builder.Default
    private Boolean isLeaf = false;

    /**
     * Number of direct children.
     */
    @Column(name = "child_count")
    @Builder.Default
    private Integer childCount = 0;

    /**
     * Link to VirtualAccount for L7 nodes only.
     */
    @Column(name = "virtual_account_id")
    private UUID virtualAccountId;

    // ========================================================================
    // Balance tracking (tiered aggregation)
    // ========================================================================

    /**
     * Aggregated balance of all descendant virtual accounts.
     * Updated via tiered aggregation:
     * - L7-L5: Real-time on transaction
     * - L4-L1: Scheduled job every 5 minutes
     */
    @Column(name = "aggregated_balance", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal aggregatedBalance = BigDecimal.ZERO;

    /**
     * Available balance (aggregated_balance minus holds).
     */
    @Column(name = "available_balance", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal availableBalance = BigDecimal.ZERO;

    /**
     * Held balance (pending transactions, escrow, etc.).
     */
    @Column(name = "held_balance", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal heldBalance = BigDecimal.ZERO;

    /**
     * When balance was last aggregated.
     */
    @Column(name = "last_aggregated_at")
    private LocalDateTime lastAggregatedAt;

    /**
     * Currency of the balance (may differ from node currency for FX).
     */
    @Column(name = "balance_currency", length = 3)
    private String balanceCurrency;

    // ========================================================================
    // FX conversion support
    // ========================================================================

    /**
     * Balance converted to base currency.
     */
    @Column(name = "base_currency_balance", precision = 18, scale = 2)
    private BigDecimal baseCurrencyBalance;

    /**
     * FX rate used for conversion.
     */
    @Column(name = "fx_rate", precision = 18, scale = 8)
    private BigDecimal fxRate;

    /**
     * Date of FX rate.
     */
    @Column(name = "fx_rate_date")
    private LocalDate fxRateDate;

    // ========================================================================
    // Status
    // ========================================================================

    /**
     * Node status: ACTIVE, INACTIVE, SUSPENDED, CLOSED.
     */
    @Column(name = "status", length = 20)
    @Builder.Default
    private String status = STATUS_ACTIVE;

    // ========================================================================
    // Display settings
    // ========================================================================

    /**
     * Display order within parent's children.
     */
    @Column(name = "display_order")
    @Builder.Default
    private Integer displayOrder = 0;

    /**
     * Icon identifier for UI rendering.
     */
    @Column(name = "icon", length = 50)
    private String icon;

    /**
     * Color for UI rendering.
     */
    @Column(name = "color", length = 20)
    private String color;

    // ========================================================================
    // Metadata
    // ========================================================================

    /**
     * Additional metadata as JSON.
     */
    @Column(name = "metadata", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String metadata;

    /**
     * Tags for filtering/searching.
     */
    @Column(name = "tags", length = 255)
    private String tags;

    // ========================================================================
    // Enums
    // ========================================================================

    public enum HierarchyNodeType {
        MASTER,           // L1 - Currency/Physical Account mirror
        CONSOLIDATION,    // L2-L6 - Intermediate grouping nodes
        VIRTUAL_ACCOUNT,  // L7 - Leaf nodes linked to VirtualAccount
        CURRENCY_MIRROR   // Currency aggregation node (virtual - derived from VirtualAccount.CURRENCY_MIRROR)
    }

    // ========================================================================
    // Status constants
    // ========================================================================

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";
    public static final String STATUS_SUSPENDED = "SUSPENDED";
    public static final String STATUS_CLOSED = "CLOSED";

    // ========================================================================
    // Helper methods - Status
    // ========================================================================

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }

    public boolean isInactive() {
        return STATUS_INACTIVE.equals(status);
    }

    public boolean isSuspended() {
        return STATUS_SUSPENDED.equals(status);
    }

    public boolean isClosed() {
        return STATUS_CLOSED.equals(status);
    }

    // ========================================================================
    // Helper methods - Tree structure
    // ========================================================================

    /**
     * Check if this is a root node (Level 1, no parent).
     */
    public boolean isRoot() {
        return parentId == null || levelNumber == 1;
    }

    /**
     * Check if this node has children.
     */
    public boolean hasChildren() {
        return childCount != null && childCount > 0;
    }

    /**
     * Get the parent path (path without this node's code).
     */
    public String getParentPath() {
        if (materializedPath == null || materializedPath.isEmpty()) {
            return "";
        }
        int lastSlash = materializedPath.lastIndexOf('/');
        return lastSlash > 0 ? materializedPath.substring(0, lastSlash) : "";
    }

    /**
     * Get path depth (number of levels from root).
     */
    public int getDepth() {
        if (materializedPath == null || materializedPath.isEmpty()) {
            return 0;
        }
        // Count slashes, subtract 1 for leading slash
        return (int) materializedPath.chars().filter(ch -> ch == '/').count() - 1;
    }

    /**
     * Check if this node is an ancestor of the given node.
     */
    public boolean isAncestorOf(HierarchyNode other) {
        if (other == null || other.getMaterializedPath() == null || this.materializedPath == null) {
            return false;
        }
        return other.getMaterializedPath().startsWith(this.materializedPath + "/");
    }

    /**
     * Check if this node is a descendant of the given node.
     */
    public boolean isDescendantOf(HierarchyNode other) {
        if (other == null || this.materializedPath == null || other.getMaterializedPath() == null) {
            return false;
        }
        return this.materializedPath.startsWith(other.getMaterializedPath() + "/");
    }

    /**
     * Check if this node is a sibling of the given node (same parent).
     */
    public boolean isSiblingOf(HierarchyNode other) {
        if (other == null) {
            return false;
        }
        if (this.parentId == null && other.getParentId() == null) {
            return this.programId.equals(other.getProgramId());
        }
        return this.parentId != null && this.parentId.equals(other.getParentId());
    }

    // ========================================================================
    // Helper methods - Balance
    // ========================================================================

    /**
     * Get effective balance (available minus held).
     */
    public BigDecimal getEffectiveBalance() {
        BigDecimal avail = availableBalance != null ? availableBalance : BigDecimal.ZERO;
        BigDecimal held = heldBalance != null ? heldBalance : BigDecimal.ZERO;
        return avail.subtract(held);
    }

    /**
     * Check if balance needs refresh (older than given minutes).
     */
    public boolean needsBalanceRefresh(int maxAgeMinutes) {
        if (lastAggregatedAt == null) {
            return true;
        }
        return lastAggregatedAt.plusMinutes(maxAgeMinutes).isBefore(LocalDateTime.now());
    }

    // ========================================================================
    // Path building helpers
    // ========================================================================

    /**
     * Build materialized path from parent path and node code.
     */
    public static String buildPath(String parentPath, String nodeCode) {
        if (parentPath == null || parentPath.isEmpty()) {
            return "/" + nodeCode;
        }
        return parentPath + "/" + nodeCode;
    }

    /**
     * Extract node codes from materialized path.
     */
    public String[] getPathSegments() {
        if (materializedPath == null || materializedPath.isEmpty()) {
            return new String[0];
        }
        // Remove leading slash and split
        String path = materializedPath.startsWith("/") ? materializedPath.substring(1) : materializedPath;
        return path.split("/");
    }

    // ========================================================================
    // Validation
    // ========================================================================

    @PrePersist
    @PreUpdate
    private void validate() {
        // Initialize maxDepth if not set
        if (maxDepth == null) {
            maxDepth = DEFAULT_MAX_DEPTH;
        }

        // Ensure maxDepth is within absolute limits
        if (maxDepth < 2 || maxDepth > ABSOLUTE_MAX_DEPTH) {
            throw new IllegalArgumentException(
                "Max depth must be between 2 and " + ABSOLUTE_MAX_DEPTH + ", got: " + maxDepth);
        }

        // Level number validation (dynamic based on maxDepth)
        if (levelNumber == null || levelNumber < 1 || levelNumber > maxDepth) {
            throw new IllegalArgumentException(
                "Level number must be between 1 and " + maxDepth + ", got: " + levelNumber);
        }

        // Node type validation
        if (nodeType == null) {
            throw new IllegalArgumentException("Node type is required");
        }

        // MASTER node must be at level 1
        if (nodeType == HierarchyNodeType.MASTER && levelNumber != 1) {
            throw new IllegalArgumentException("MASTER nodes must be at level 1");
        }

        // Level 1 must be MASTER
        if (levelNumber == 1 && nodeType != HierarchyNodeType.MASTER) {
            throw new IllegalArgumentException("Level 1 nodes must be MASTER type");
        }

        // VIRTUAL_ACCOUNT must be leaf
        if (nodeType == HierarchyNodeType.VIRTUAL_ACCOUNT) {
            if (!Boolean.TRUE.equals(isLeaf)) {
                isLeaf = true;
            }
            if (virtualAccountId == null) {
                throw new IllegalArgumentException("VIRTUAL_ACCOUNT nodes must have virtualAccountId");
            }
        }

        // Note: Removed fixed "Level 7 must be VIRTUAL_ACCOUNT" constraint
        // VIRTUAL_ACCOUNT can now be at any level (leaf nodes)

        // CONSOLIDATION can be at any intermediate level (2 to maxDepth-1)
        // Or at maxDepth if not a VIRTUAL_ACCOUNT node
        if (nodeType == HierarchyNodeType.CONSOLIDATION) {
            if (levelNumber < 2) {
                throw new IllegalArgumentException(
                    "CONSOLIDATION nodes must be at level 2 or higher, got: " + levelNumber);
            }
        }

        // Node code validation
        if (nodeCode == null || nodeCode.trim().isEmpty()) {
            throw new IllegalArgumentException("Node code is required");
        }

        // Node name validation
        if (nodeName == null || nodeName.trim().isEmpty()) {
            throw new IllegalArgumentException("Node name is required");
        }

        // Materialized path validation
        if (materializedPath == null || materializedPath.trim().isEmpty()) {
            throw new IllegalArgumentException("Materialized path is required");
        }

        // Path must start with /
        if (!materializedPath.startsWith("/")) {
            materializedPath = "/" + materializedPath;
        }

        // Initialize defaults
        if (childCount == null) {
            childCount = 0;
        }
        if (aggregatedBalance == null) {
            aggregatedBalance = BigDecimal.ZERO;
        }
        if (availableBalance == null) {
            availableBalance = BigDecimal.ZERO;
        }
        if (heldBalance == null) {
            heldBalance = BigDecimal.ZERO;
        }
        if (status == null) {
            status = STATUS_ACTIVE;
        }
        if (displayOrder == null) {
            displayOrder = 0;
        }
    }

    // ========================================================================
    // Builder factory methods
    // ========================================================================

    /**
     * Create a MASTER (L1) node.
     */
    public static HierarchyNode createMasterNode(UUID programId, String programCode, 
                                                  String currencyCode, String nodeName) {
        return HierarchyNode.builder()
            .programId(programId)
            .parentId(null)
            .levelNumber(1)
            .nodeCode(currencyCode)
            .nodeName(nodeName != null ? nodeName : currencyCode + " Master")
            .nodeType(HierarchyNodeType.MASTER)
            .currencyCode(currencyCode)
            .balanceCurrency(currencyCode)
            .dimensionValue(currencyCode)
            .materializedPath("/" + programCode + "/" + currencyCode)
            .isLeaf(false)
            .status(STATUS_ACTIVE)
            .build();
    }

    /**
     * Create a CONSOLIDATION node at any intermediate level.
     * Level must be >= 2 (level 1 is reserved for MASTER/ROOT).
     *
     * @param programId Program ID
     * @param parentId Parent node ID
     * @param level Level number (2 or higher)
     * @param nodeCode Unique code for this node
     * @param nodeName Display name
     * @param parentPath Parent's materialized path
     * @param currencyCode Currency code
     * @param maxDepth Maximum depth for this hierarchy (optional, defaults to 20)
     */
    public static HierarchyNode createConsolidationNode(UUID programId, UUID parentId,
                                                        int level, String nodeCode, String nodeName,
                                                        String parentPath, String currencyCode,
                                                        Integer maxDepth) {
        int effectiveMaxDepth = maxDepth != null ? maxDepth : DEFAULT_MAX_DEPTH;
        if (level < 2) {
            throw new IllegalArgumentException("Consolidation level must be 2 or higher, got: " + level);
        }
        if (level > effectiveMaxDepth) {
            throw new IllegalArgumentException(
                "Consolidation level " + level + " exceeds max depth " + effectiveMaxDepth);
        }
        return HierarchyNode.builder()
            .programId(programId)
            .parentId(parentId)
            .levelNumber(level)
            .nodeCode(nodeCode)
            .nodeName(nodeName)
            .nodeType(HierarchyNodeType.CONSOLIDATION)
            .currencyCode(currencyCode)
            .maxDepth(effectiveMaxDepth)
            .balanceCurrency(currencyCode)
            .dimensionValue(nodeCode)
            .materializedPath(buildPath(parentPath, nodeCode))
            .isLeaf(false)
            .status(STATUS_ACTIVE)
            .build();
    }

    /**
     * Backward compatible overload (uses default max depth).
     */
    public static HierarchyNode createConsolidationNode(UUID programId, UUID parentId,
                                                        int level, String nodeCode, String nodeName,
                                                        String parentPath, String currencyCode) {
        return createConsolidationNode(programId, parentId, level, nodeCode, nodeName,
            parentPath, currencyCode, DEFAULT_MAX_DEPTH);
    }

    /**
     * Create a VIRTUAL_ACCOUNT node at any level (leaf node).
     * Can be at any level >= 2, not restricted to level 7.
     *
     * @param programId Program ID
     * @param parentId Parent node ID
     * @param level Level number (parent level + 1)
     * @param virtualAccountId Linked virtual account ID
     * @param vaNumber VA number (used as node code)
     * @param vaName VA name
     * @param parentPath Parent's materialized path
     * @param currencyCode Currency code
     * @param maxDepth Maximum depth for this hierarchy (optional)
     */
    public static HierarchyNode createVirtualAccountNode(UUID programId, UUID parentId,
                                                         int level, UUID virtualAccountId,
                                                         String vaNumber, String vaName,
                                                         String parentPath, String currencyCode,
                                                         Integer maxDepth) {
        int effectiveMaxDepth = maxDepth != null ? maxDepth : DEFAULT_MAX_DEPTH;
        if (level < 2) {
            throw new IllegalArgumentException("VIRTUAL_ACCOUNT level must be 2 or higher");
        }
        if (level > effectiveMaxDepth) {
            throw new IllegalArgumentException(
                "VIRTUAL_ACCOUNT level " + level + " exceeds max depth " + effectiveMaxDepth);
        }
        return HierarchyNode.builder()
            .programId(programId)
            .parentId(parentId)
            .levelNumber(level)
            .nodeCode(vaNumber)
            .nodeName(vaName)
            .nodeType(HierarchyNodeType.VIRTUAL_ACCOUNT)
            .currencyCode(currencyCode)
            .balanceCurrency(currencyCode)
            .dimensionValue(vaNumber)
            .virtualAccountId(virtualAccountId)
            .materializedPath(buildPath(parentPath, vaNumber))
            .isLeaf(true)
            .maxDepth(effectiveMaxDepth)
            .status(STATUS_ACTIVE)
            .build();
    }

    /**
     * Backward compatible overload - creates VIRTUAL_ACCOUNT at level = parent level + 1.
     * For legacy code that assumed level 7.
     */
    public static HierarchyNode createVirtualAccountNode(UUID programId, UUID parentId,
                                                         UUID virtualAccountId, String vaNumber, String vaName,
                                                         String parentPath, String currencyCode) {
        // Legacy behavior: assume parent is level 6, so this is level 7
        // New code should use the overload with explicit level
        return createVirtualAccountNode(programId, parentId, 7, virtualAccountId,
            vaNumber, vaName, parentPath, currencyCode, DEFAULT_MAX_DEPTH);
    }

    /**
     * Calculate new level numbers for a subtree being moved to a new parent.
     * Used when restructuring hierarchies.
     *
     * @param currentLevel Current level of the node
     * @param oldParentLevel Level of the old parent
     * @param newParentLevel Level of the new parent
     * @return New level number for this node
     */
    public static int calculateNewLevel(int currentLevel, int oldParentLevel, int newParentLevel) {
        int relativeDepth = currentLevel - oldParentLevel;
        return newParentLevel + relativeDepth;
    }

    /**
     * Check if this node can be moved to a new parent at the given level.
     *
     * @param newParentLevel Level of the new parent
     * @param subtreeDepth Depth of the subtree rooted at this node
     * @return true if the move is valid
     */
    public boolean canMoveToParent(int newParentLevel, int subtreeDepth) {
        int effectiveMaxDepth = maxDepth != null ? maxDepth : DEFAULT_MAX_DEPTH;
        int newMaxLevel = newParentLevel + subtreeDepth;
        return newMaxLevel <= effectiveMaxDepth;
    }
}