package com.bank.vam.repository.hierarchy;

import com.bank.vam.entity.hierarchy.HierarchyNode;
import com.bank.vam.entity.hierarchy.HierarchyNode.HierarchyNodeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for HierarchyNode entity.
 * Provides tree operations using materialized path pattern.
 */
@Repository
public interface HierarchyNodeRepository extends JpaRepository<HierarchyNode, UUID> {

    // ========================================================================
    // Basic queries
    // ========================================================================

    /**
     * Find all nodes for a program.
     */
    List<HierarchyNode> findByProgramId(UUID programId);

    /**
     * Find all nodes for a program ordered by path (tree order).
     */
    List<HierarchyNode> findByProgramIdOrderByMaterializedPathAsc(UUID programId);

    /**
     * Find root nodes (Level 1) for a program.
     */
    List<HierarchyNode> findByProgramIdAndLevelNumber(UUID programId, Integer levelNumber);

    /**
     * Find root node (MASTER) for a program.
     */
    @Query("SELECT n FROM HierarchyNode n WHERE n.programId = :programId AND n.nodeType = 'MASTER'")
    Optional<HierarchyNode> findRootNode(@Param("programId") UUID programId);

    /**
     * Find node by virtual account ID.
     */
    Optional<HierarchyNode> findByVirtualAccountId(UUID virtualAccountId);

    /**
     * Find node by materialized path.
     */
    Optional<HierarchyNode> findByProgramIdAndMaterializedPath(UUID programId, String materializedPath);

    /**
     * Find node by code within a parent.
     */
    Optional<HierarchyNode> findByParentIdAndNodeCode(UUID parentId, String nodeCode);

    /**
     * Count nodes under a parent with node code starting with prefix.
     * Used to generate unique node codes for multiple IHB accounts.
     */
    @Query("SELECT COUNT(n) FROM HierarchyNode n WHERE n.parentId = :parentId " +
           "AND n.nodeCode LIKE CONCAT(:prefix, '%')")
    long countByParentIdAndNodeCodeStartingWith(@Param("parentId") UUID parentId, @Param("prefix") String prefix);

    // ========================================================================
    // Tree traversal queries
    // ========================================================================

    /**
     * Find direct children of a node.
     */
    List<HierarchyNode> findByParentIdOrderByDisplayOrderAsc(UUID parentId);

    /**
     * Find direct children of a node with status filter.
     */
    @Query("SELECT n FROM HierarchyNode n WHERE n.parentId = :parentId AND n.status = :status ORDER BY n.displayOrder")
    List<HierarchyNode> findChildrenByStatus(@Param("parentId") UUID parentId, @Param("status") String status);

    /**
     * Find all descendants using materialized path (subtree).
     */
    @Query("SELECT n FROM HierarchyNode n WHERE n.programId = :programId " +
           "AND n.materializedPath LIKE CONCAT(:parentPath, '/%') ORDER BY n.materializedPath")
    List<HierarchyNode> findDescendants(@Param("programId") UUID programId, @Param("parentPath") String parentPath);

    /**
     * Find all ancestors using materialized path.
     */
    @Query(value = "SELECT h.* FROM hierarchy_nodes h " +
                   "WHERE h.program_id = :programId " +
                   "AND :nodePath LIKE CONCAT(h.materialized_path, '/%') " +
                   "ORDER BY h.level_number", nativeQuery = true)
    List<HierarchyNode> findAncestors(@Param("programId") UUID programId, @Param("nodePath") String nodePath);

    /**
     * Find siblings (same parent).
     */
    @Query("SELECT n FROM HierarchyNode n WHERE n.parentId = :parentId AND n.id != :nodeId ORDER BY n.displayOrder")
    List<HierarchyNode> findSiblings(@Param("parentId") UUID parentId, @Param("nodeId") UUID nodeId);

    // ========================================================================
    // Leaf node queries
    // ========================================================================

    /**
     * Find all leaf nodes for a program.
     */
    @Query("SELECT n FROM HierarchyNode n WHERE n.programId = :programId AND n.isLeaf = true")
    List<HierarchyNode> findLeafNodes(@Param("programId") UUID programId);

    /**
     * Find leaf nodes under a specific node.
     */
    @Query("SELECT n FROM HierarchyNode n WHERE n.programId = :programId " +
           "AND n.materializedPath LIKE CONCAT(:parentPath, '/%') " +
           "AND n.isLeaf = true ORDER BY n.materializedPath")
    List<HierarchyNode> findLeafNodesUnder(@Param("programId") UUID programId, @Param("parentPath") String parentPath);

    /**
     * Find all virtual account nodes for a program.
     */
    @Query("SELECT n FROM HierarchyNode n WHERE n.programId = :programId AND n.nodeType = 'VIRTUAL_ACCOUNT'")
    List<HierarchyNode> findVirtualAccountNodes(@Param("programId") UUID programId);

    // ========================================================================
    // Level-based queries
    // ========================================================================

    /**
     * Find nodes at a specific level.
     */
    @Query("SELECT n FROM HierarchyNode n WHERE n.programId = :programId AND n.levelNumber = :level ORDER BY n.materializedPath")
    List<HierarchyNode> findByLevel(@Param("programId") UUID programId, @Param("level") Integer level);

    /**
     * Find nodes at a specific level under a parent.
     */
    @Query("SELECT n FROM HierarchyNode n WHERE n.programId = :programId " +
           "AND n.materializedPath LIKE CONCAT(:parentPath, '/%') " +
           "AND n.levelNumber = :level ORDER BY n.materializedPath")
    List<HierarchyNode> findByLevelUnder(@Param("programId") UUID programId, 
                                          @Param("parentPath") String parentPath,
                                          @Param("level") Integer level);

    /**
     * Count nodes at each level.
     */
    @Query("SELECT n.levelNumber, COUNT(n) FROM HierarchyNode n WHERE n.programId = :programId GROUP BY n.levelNumber ORDER BY n.levelNumber")
    List<Object[]> countByLevel(@Param("programId") UUID programId);

    // ========================================================================
    // Balance queries
    // ========================================================================

    /**
     * Find nodes needing balance refresh.
     */
    @Query("SELECT n FROM HierarchyNode n WHERE n.programId = :programId " +
           "AND (n.lastAggregatedAt IS NULL OR n.lastAggregatedAt < :threshold)")
    List<HierarchyNode> findNodesNeedingRefresh(@Param("programId") UUID programId, 
                                                 @Param("threshold") LocalDateTime threshold);

    /**
     * Get total balance for a program.
     */
    @Query("SELECT COALESCE(SUM(n.aggregatedBalance), 0) FROM HierarchyNode n " +
           "WHERE n.programId = :programId AND n.levelNumber = 1")
    BigDecimal getTotalBalance(@Param("programId") UUID programId);

    /**
     * Get balance by level.
     */
    @Query("SELECT n.levelNumber, COALESCE(SUM(n.aggregatedBalance), 0) FROM HierarchyNode n " +
           "WHERE n.programId = :programId GROUP BY n.levelNumber ORDER BY n.levelNumber")
    List<Object[]> getBalanceByLevel(@Param("programId") UUID programId);

    // ========================================================================
    // Update queries
    // ========================================================================

    /**
     * Update node balance.
     */
    @Modifying
    @Query("UPDATE HierarchyNode n SET n.aggregatedBalance = :balance, n.availableBalance = :availableBalance, " +
           "n.lastAggregatedAt = :timestamp WHERE n.id = :nodeId")
    int updateBalance(@Param("nodeId") UUID nodeId, 
                      @Param("balance") BigDecimal balance,
                      @Param("availableBalance") BigDecimal availableBalance,
                      @Param("timestamp") LocalDateTime timestamp);

    /**
     * Increment node balance (for real-time updates).
     */
    @Modifying
    @Query("UPDATE HierarchyNode n SET n.aggregatedBalance = n.aggregatedBalance + :delta, " +
           "n.availableBalance = n.availableBalance + :delta, " +
           "n.lastAggregatedAt = :timestamp WHERE n.id = :nodeId")
    int incrementBalance(@Param("nodeId") UUID nodeId, 
                         @Param("delta") BigDecimal delta,
                         @Param("timestamp") LocalDateTime timestamp);

    /**
     * Update child count.
     */
    @Modifying
    @Query("UPDATE HierarchyNode n SET n.childCount = :count, n.isLeaf = :isLeaf WHERE n.id = :nodeId")
    int updateChildCount(@Param("nodeId") UUID nodeId, @Param("count") Integer count, @Param("isLeaf") Boolean isLeaf);

    /**
     * Update status.
     */
    @Modifying
    @Query("UPDATE HierarchyNode n SET n.status = :status WHERE n.id = :nodeId")
    int updateStatus(@Param("nodeId") UUID nodeId, @Param("status") String status);

    /**
     * Update materialized path (for move operations).
     */
    @Modifying
    @Query("UPDATE HierarchyNode n SET n.materializedPath = :newPath, n.parentId = :newParentId WHERE n.id = :nodeId")
    int updatePath(@Param("nodeId") UUID nodeId, @Param("newPath") String newPath, @Param("newParentId") UUID newParentId);

    /**
     * Update paths for subtree (batch update for move operations).
     */
    @Modifying
    @Query(value = "UPDATE hierarchy_nodes SET materialized_path = REPLACE(materialized_path, :oldPath, :newPath) " +
                   "WHERE program_id = :programId AND materialized_path LIKE CONCAT(:oldPath, '%')", nativeQuery = true)
    int updateSubtreePaths(@Param("programId") UUID programId, 
                           @Param("oldPath") String oldPath, 
                           @Param("newPath") String newPath);

    // ========================================================================
    // Existence checks
    // ========================================================================

    /**
     * Check if node has children.
     */
    boolean existsByParentId(UUID parentId);

    /**
     * Check if path exists.
     */
    boolean existsByProgramIdAndMaterializedPath(UUID programId, String materializedPath);

    /**
     * Check if code exists under parent.
     */
    boolean existsByParentIdAndNodeCode(UUID parentId, String nodeCode);

    // ========================================================================
    // Count queries
    // ========================================================================

    /**
     * Count nodes for a program.
     */
    long countByProgramId(UUID programId);

    /**
     * Count children.
     */
    long countByParentId(UUID parentId);

    /**
     * Count descendants.
     */
    @Query("SELECT COUNT(n) FROM HierarchyNode n WHERE n.programId = :programId " +
           "AND n.materializedPath LIKE CONCAT(:parentPath, '/%')")
    long countDescendants(@Param("programId") UUID programId, @Param("parentPath") String parentPath);

    /**
     * Count active leaf nodes.
     */
    @Query("SELECT COUNT(n) FROM HierarchyNode n WHERE n.programId = :programId " +
           "AND n.isLeaf = true AND n.status = 'ACTIVE'")
    long countActiveLeafNodes(@Param("programId") UUID programId);

    // ========================================================================
    // Delete queries
    // ========================================================================

    /**
     * Delete all nodes for a program.
     */
    void deleteByProgramId(UUID programId);

    /**
     * Delete subtree (node and all descendants).
     */
    @Modifying
    @Query("DELETE FROM HierarchyNode n WHERE n.programId = :programId " +
           "AND (n.materializedPath = :path OR n.materializedPath LIKE CONCAT(:path, '/%'))")
    int deleteSubtree(@Param("programId") UUID programId, @Param("path") String path);

    // ========================================================================
    // Search queries
    // ========================================================================

    /**
     * Search nodes by name.
     */
    @Query("SELECT n FROM HierarchyNode n WHERE n.programId = :programId " +
           "AND LOWER(n.nodeName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) ORDER BY n.materializedPath")
    List<HierarchyNode> searchByName(@Param("programId") UUID programId, @Param("searchTerm") String searchTerm);

    /**
     * Search nodes by code.
     */
    @Query("SELECT n FROM HierarchyNode n WHERE n.programId = :programId " +
           "AND LOWER(n.nodeCode) LIKE LOWER(CONCAT('%', :searchTerm, '%')) ORDER BY n.materializedPath")
    List<HierarchyNode> searchByCode(@Param("programId") UUID programId, @Param("searchTerm") String searchTerm);

    /**
     * Find nodes by tags.
     */
    @Query("SELECT n FROM HierarchyNode n WHERE n.programId = :programId " +
           "AND n.tags LIKE CONCAT('%', :tag, '%') ORDER BY n.materializedPath")
    List<HierarchyNode> findByTag(@Param("programId") UUID programId, @Param("tag") String tag);


// ============================================================================
// ADD THESE METHODS TO HierarchyNodeRepository.java
// Required for global hierarchy endpoints
// ============================================================================

    // ========================================================================
    // Global Queries (not program-specific)
    // ========================================================================

    /**
     * Find all root nodes (no parent) across all programs.
     */
    List<HierarchyNode> findByParentIdIsNull();

    /**
     * Find nodes by level number across all programs.
     */
    List<HierarchyNode> findByLevelNumber(Integer levelNumber);

    /**
     * Find nodes by node type across all programs.
     */
    List<HierarchyNode> findByNodeType(String nodeType);

    /**
     * Search nodes globally by name or code.
     */
    List<HierarchyNode> findByNodeNameContainingIgnoreCaseOrNodeCodeContainingIgnoreCase(
            String nodeName, String nodeCode);

    /**
     * Find nodes by status across all programs.
     */
    List<HierarchyNode> findByStatus(String status);


}