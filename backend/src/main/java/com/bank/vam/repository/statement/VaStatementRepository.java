package com.bank.vam.repository.statement;

import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Repository for hierarchical VA statement queries.
 *
 * <h2>Key Features:</h2>
 * <ul>
 *   <li>Recursive CTE for VA hierarchy traversal</li>
 *   <li>Batch transaction loading for multiple VAs</li>
 *   <li>Aggregated balance calculations</li>
 *   <li>Historical balance reconstruction</li>
 * </ul>
 *
 * <h2>Performance Optimizations:</h2>
 * <ul>
 *   <li>Single query for entire hierarchy using recursive CTE</li>
 *   <li>Batch loading instead of N+1 queries</li>
 *   <li>Efficient date range filtering with indexes</li>
 * </ul>
 */
@Repository
public interface VaStatementRepository extends JpaRepository<VirtualAccount, UUID> {

    // ========================================================================
    // RECURSIVE HIERARCHY QUERIES
    // ========================================================================

    /**
     * Get all child VA IDs recursively using a recursive CTE.
     *
     * <p>This is the core query for hierarchical statement generation.
     * It traverses the VA tree starting from a parent and returns all descendants.</p>
     *
     * <h3>Query Explanation:</h3>
     * <pre>
     * WITH RECURSIVE va_tree AS (
     *   -- Anchor: Start with direct children of the parent
     *   SELECT id, parent_account_id, hierarchy_level, 1 as depth
     *   FROM virtual_accounts
     *   WHERE parent_account_id = :parentVaId AND status = 'ACTIVE'
     *
     *   UNION ALL
     *
     *   -- Recursive: Get children of each node at current level
     *   SELECT va.id, va.parent_account_id, va.hierarchy_level, vt.depth + 1
     *   FROM virtual_accounts va
     *   INNER JOIN va_tree vt ON va.parent_account_id = vt.id
     *   WHERE va.status = 'ACTIVE'
     *     AND (:maxDepth IS NULL OR vt.depth < :maxDepth)
     * )
     * SELECT id FROM va_tree
     * </pre>
     *
     * @param parentVaId The parent VA ID to start traversal from
     * @param maxDepth   Maximum depth to traverse (null = unlimited)
     * @return List of all descendant VA IDs (does NOT include the parent itself)
     */
    @Query(value = """
        WITH RECURSIVE va_tree AS (
            SELECT id, parent_account_id, hierarchy_level, 1 as depth
            FROM virtual_accounts
            WHERE parent_account_id = :parentVaId
              AND status = 'ACTIVE'

            UNION ALL

            SELECT va.id, va.parent_account_id, va.hierarchy_level, vt.depth + 1
            FROM virtual_accounts va
            INNER JOIN va_tree vt ON va.parent_account_id = vt.id
            WHERE va.status = 'ACTIVE'
              AND (CAST(:maxDepth AS INTEGER) IS NULL OR vt.depth < CAST(:maxDepth AS INTEGER))
        )
        SELECT id FROM va_tree
        """, nativeQuery = true)
    List<UUID> findAllChildVaIdsRecursive(
        @Param("parentVaId") UUID parentVaId,
        @Param("maxDepth") Integer maxDepth
    );

    /**
     * Get all VA IDs in the hierarchy including the root.
     *
     * @param rootVaId The root VA ID
     * @param maxDepth Maximum depth (null = unlimited)
     * @return List of all VA IDs including root
     */
    @Query(value = """
        WITH RECURSIVE va_tree AS (
            -- Start with root
            SELECT id, parent_account_id, hierarchy_level, 0 as depth
            FROM virtual_accounts
            WHERE id = :rootVaId

            UNION ALL

            -- Get all descendants
            SELECT va.id, va.parent_account_id, va.hierarchy_level, vt.depth + 1
            FROM virtual_accounts va
            INNER JOIN va_tree vt ON va.parent_account_id = vt.id
            WHERE va.status = 'ACTIVE'
              AND (CAST(:maxDepth AS INTEGER) IS NULL OR vt.depth < CAST(:maxDepth AS INTEGER))
        )
        SELECT id FROM va_tree
        """, nativeQuery = true)
    List<UUID> findAllVaIdsInHierarchy(
        @Param("rootVaId") UUID rootVaId,
        @Param("maxDepth") Integer maxDepth
    );

    /**
     * Get the maximum hierarchy depth for a VA tree.
     *
     * @param rootVaId The root VA ID
     * @return Maximum depth in the tree
     */
    @Query(value = """
        WITH RECURSIVE va_tree AS (
            SELECT id, 0 as depth
            FROM virtual_accounts
            WHERE id = :rootVaId

            UNION ALL

            SELECT va.id, vt.depth + 1
            FROM virtual_accounts va
            INNER JOIN va_tree vt ON va.parent_account_id = vt.id
            WHERE va.status = 'ACTIVE'
        )
        SELECT COALESCE(MAX(depth), 0) FROM va_tree
        """, nativeQuery = true)
    int getMaxHierarchyDepth(@Param("rootVaId") UUID rootVaId);

    /**
     * Get VAs with their hierarchy details for building tree structures.
     *
     * @param rootVaId The root VA ID
     * @param maxDepth Maximum depth
     * @return List of VA objects with hierarchy info
     */
    @Query(value = """
        WITH RECURSIVE va_tree AS (
            SELECT id, va_number, va_name, parent_account_id, hierarchy_level,
                   currency_code, current_balance, available_balance, account_category,
                   0 as depth
            FROM virtual_accounts
            WHERE id = :rootVaId

            UNION ALL

            SELECT va.id, va.va_number, va.va_name, va.parent_account_id, va.hierarchy_level,
                   va.currency_code, va.current_balance, va.available_balance, va.account_category,
                   vt.depth + 1
            FROM virtual_accounts va
            INNER JOIN va_tree vt ON va.parent_account_id = vt.id
            WHERE va.status = 'ACTIVE'
              AND (CAST(:maxDepth AS INTEGER) IS NULL OR vt.depth < CAST(:maxDepth AS INTEGER))
        )
        SELECT * FROM va_tree ORDER BY depth, va_number
        """, nativeQuery = true)
    List<Object[]> findVaHierarchyTree(
        @Param("rootVaId") UUID rootVaId,
        @Param("maxDepth") Integer maxDepth
    );

    // ========================================================================
    // BATCH TRANSACTION LOADING
    // ========================================================================

    /**
     * Load all transactions for multiple VAs in a single query.
     *
     * <p>This is critical for performance when generating aggregated statements.
     * Instead of N queries for N VAs, we use a single query with IN clause.</p>
     *
     * @param vaIds    List of VA IDs to load transactions for
     * @param fromDate Start of date range
     * @param toDate   End of date range
     * @return All transactions for the specified VAs in the date range
     */
    @Query("SELECT t FROM Transaction t " +
           "WHERE t.vaId IN :vaIds " +
           "AND t.transactionDate >= :fromDate " +
           "AND t.transactionDate < :toDate " +
           "AND t.status = 'COMPLETED' " +
           "ORDER BY t.transactionDate ASC")
    List<Transaction> findTransactionsByVaIdsAndDateRange(
        @Param("vaIds") List<UUID> vaIds,
        @Param("fromDate") LocalDateTime fromDate,
        @Param("toDate") LocalDateTime toDate
    );

    /**
     * Load transactions for multiple VAs with pagination.
     *
     * @param vaIds    List of VA IDs
     * @param fromDate Start date
     * @param toDate   End date
     * @param offset   Offset for pagination
     * @param limit    Limit for pagination
     * @return Paginated transactions
     */
    @Query(value = """
        SELECT * FROM va_movements
        WHERE va_id IN :vaIds
          AND transaction_date >= :fromDate
          AND transaction_date < :toDate
          AND status = 'COMPLETED'
        ORDER BY transaction_date ASC
        LIMIT :limit OFFSET :offset
        """, nativeQuery = true)
    List<Transaction> findTransactionsByVaIdsAndDateRangePaginated(
        @Param("vaIds") List<UUID> vaIds,
        @Param("fromDate") LocalDateTime fromDate,
        @Param("toDate") LocalDateTime toDate,
        @Param("offset") int offset,
        @Param("limit") int limit
    );

    /**
     * Count transactions for multiple VAs in date range.
     *
     * @param vaIds    List of VA IDs
     * @param fromDate Start date
     * @param toDate   End date
     * @return Total count
     */
    @Query("SELECT COUNT(t) FROM Transaction t " +
           "WHERE t.vaId IN :vaIds " +
           "AND t.transactionDate >= :fromDate " +
           "AND t.transactionDate < :toDate " +
           "AND t.status = 'COMPLETED'")
    long countTransactionsByVaIdsAndDateRange(
        @Param("vaIds") List<UUID> vaIds,
        @Param("fromDate") LocalDateTime fromDate,
        @Param("toDate") LocalDateTime toDate
    );

    // ========================================================================
    // AGGREGATED BALANCE CALCULATIONS
    // ========================================================================

    /**
     * Sum current balance for multiple VAs.
     *
     * @param vaIds List of VA IDs
     * @return Sum of current balances
     */
    @Query("SELECT COALESCE(SUM(v.currentBalance), 0) FROM VirtualAccount v WHERE v.id IN :vaIds")
    BigDecimal sumCurrentBalanceForVaIds(@Param("vaIds") List<UUID> vaIds);

    /**
     * Sum available balance for multiple VAs.
     *
     * @param vaIds List of VA IDs
     * @return Sum of available balances
     */
    @Query("SELECT COALESCE(SUM(v.availableBalance), 0) FROM VirtualAccount v WHERE v.id IN :vaIds")
    BigDecimal sumAvailableBalanceForVaIds(@Param("vaIds") List<UUID> vaIds);

    /**
     * Sum held balance for multiple VAs.
     *
     * @param vaIds List of VA IDs
     * @return Sum of held balances
     */
    @Query("SELECT COALESCE(SUM(v.heldBalance), 0) FROM VirtualAccount v WHERE v.id IN :vaIds")
    BigDecimal sumHeldBalanceForVaIds(@Param("vaIds") List<UUID> vaIds);

    /**
     * Sum credit limit for multiple VAs.
     *
     * @param vaIds List of VA IDs
     * @return Sum of credit limits
     */
    @Query("SELECT COALESCE(SUM(v.effectiveCreditLimit), 0) FROM VirtualAccount v WHERE v.id IN :vaIds")
    BigDecimal sumCreditLimitForVaIds(@Param("vaIds") List<UUID> vaIds);

    /**
     * Sum balances grouped by currency for multi-currency aggregation.
     *
     * @param vaIds List of VA IDs
     * @return List of [currencyCode, sumCurrentBalance, sumAvailableBalance, vaCount]
     */
    @Query("SELECT v.currencyCode, " +
           "COALESCE(SUM(v.currentBalance), 0), " +
           "COALESCE(SUM(v.availableBalance), 0), " +
           "COUNT(v) " +
           "FROM VirtualAccount v " +
           "WHERE v.id IN :vaIds " +
           "GROUP BY v.currencyCode")
    List<Object[]> sumBalancesByVaIdsGroupedByCurrency(@Param("vaIds") List<UUID> vaIds);

    // ========================================================================
    // HISTORICAL BALANCE RECONSTRUCTION
    // ========================================================================

    /**
     * Sum of credits after a specific time for multiple VAs.
     *
     * <p>Used for historical balance calculation:
     * balance_at_time = current_balance - credits_after + debits_after</p>
     *
     * @param vaIds     List of VA IDs
     * @param afterTime Time threshold
     * @return Sum of credit amounts after the specified time
     */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t " +
           "WHERE t.vaId IN :vaIds " +
           "AND t.transactionDate >= :afterTime " +
           "AND t.status = 'COMPLETED' " +
           "AND (t.movementType = 'CREDIT' OR t.movementType = 'TRANSFER_IN' OR " +
           "t.movementType = 'TOPUP' OR t.movementType = 'ROBO_CREDIT' OR " +
           "t.movementType = 'SWEEP_IN' OR t.movementType = 'POOL_CREDIT' OR " +
           "t.movementType = 'FEE_CREDIT' OR t.movementType = 'SETTLEMENT_CREDIT')")
    BigDecimal sumCreditsAfterTime(
        @Param("vaIds") List<UUID> vaIds,
        @Param("afterTime") LocalDateTime afterTime
    );

    /**
     * Sum of debits after a specific time for multiple VAs.
     *
     * @param vaIds     List of VA IDs
     * @param afterTime Time threshold
     * @return Sum of debit amounts after the specified time
     */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t " +
           "WHERE t.vaId IN :vaIds " +
           "AND t.transactionDate >= :afterTime " +
           "AND t.status = 'COMPLETED' " +
           "AND (t.movementType = 'DEBIT' OR t.movementType = 'TRANSFER_OUT' OR " +
           "t.movementType = 'WITHDRAWAL' OR t.movementType = 'POBO_DEBIT' OR " +
           "t.movementType = 'SWEEP_OUT' OR t.movementType = 'POOL_DEBIT' OR " +
           "t.movementType = 'FEE' OR t.movementType = 'PAYMENT')")
    BigDecimal sumDebitsAfterTime(
        @Param("vaIds") List<UUID> vaIds,
        @Param("afterTime") LocalDateTime afterTime
    );

    /**
     * Get daily balance snapshots for a VA (if stored).
     * This is an optimization for historical balance queries.
     *
     * @param vaId VA ID
     * @param fromDate Start date
     * @param toDate End date
     * @return List of [date, balance]
     */
    @Query(value = """
        SELECT DATE(transaction_date) as balance_date,
               MAX(balance_after) as eod_balance
        FROM va_movements
        WHERE va_id = :vaId
          AND transaction_date >= :fromDate
          AND transaction_date < :toDate
          AND status = 'COMPLETED'
        GROUP BY DATE(transaction_date)
        ORDER BY balance_date
        """, nativeQuery = true)
    List<Object[]> getDailyBalanceSnapshots(
        @Param("vaId") UUID vaId,
        @Param("fromDate") LocalDateTime fromDate,
        @Param("toDate") LocalDateTime toDate
    );

    // ========================================================================
    // TRANSACTION SUMMARY QUERIES
    // ========================================================================

    /**
     * Get transaction summary (counts and sums) for multiple VAs.
     *
     * @param vaIds    List of VA IDs
     * @param fromDate Start date
     * @param toDate   End date
     * @return [creditCount, creditSum, debitCount, debitSum]
     */
    @Query(value = """
        SELECT
            COALESCE(SUM(CASE WHEN movement_type IN ('CREDIT', 'TRANSFER_IN', 'TOPUP', 'ROBO_CREDIT', 'SWEEP_IN', 'POOL_CREDIT') THEN 1 ELSE 0 END), 0) as credit_count,
            COALESCE(SUM(CASE WHEN movement_type IN ('CREDIT', 'TRANSFER_IN', 'TOPUP', 'ROBO_CREDIT', 'SWEEP_IN', 'POOL_CREDIT') THEN amount ELSE 0 END), 0) as credit_sum,
            COALESCE(SUM(CASE WHEN movement_type IN ('DEBIT', 'TRANSFER_OUT', 'WITHDRAWAL', 'POBO_DEBIT', 'SWEEP_OUT', 'POOL_DEBIT', 'FEE', 'PAYMENT') THEN 1 ELSE 0 END), 0) as debit_count,
            COALESCE(SUM(CASE WHEN movement_type IN ('DEBIT', 'TRANSFER_OUT', 'WITHDRAWAL', 'POBO_DEBIT', 'SWEEP_OUT', 'POOL_DEBIT', 'FEE', 'PAYMENT') THEN amount ELSE 0 END), 0) as debit_sum
        FROM va_movements
        WHERE va_id IN :vaIds
          AND transaction_date >= :fromDate
          AND transaction_date < :toDate
          AND status = 'COMPLETED'
        """, nativeQuery = true)
    Object[] getTransactionSummary(
        @Param("vaIds") List<UUID> vaIds,
        @Param("fromDate") LocalDateTime fromDate,
        @Param("toDate") LocalDateTime toDate
    );

    /**
     * Get transaction summary per VA for detailed breakdown.
     *
     * @param vaIds    List of VA IDs
     * @param fromDate Start date
     * @param toDate   End date
     * @return List of [vaId, creditCount, creditSum, debitCount, debitSum]
     */
    @Query(value = """
        SELECT
            va_id,
            COALESCE(SUM(CASE WHEN movement_type IN ('CREDIT', 'TRANSFER_IN', 'TOPUP', 'ROBO_CREDIT', 'SWEEP_IN', 'POOL_CREDIT') THEN 1 ELSE 0 END), 0) as credit_count,
            COALESCE(SUM(CASE WHEN movement_type IN ('CREDIT', 'TRANSFER_IN', 'TOPUP', 'ROBO_CREDIT', 'SWEEP_IN', 'POOL_CREDIT') THEN amount ELSE 0 END), 0) as credit_sum,
            COALESCE(SUM(CASE WHEN movement_type IN ('DEBIT', 'TRANSFER_OUT', 'WITHDRAWAL', 'POBO_DEBIT', 'SWEEP_OUT', 'POOL_DEBIT', 'FEE', 'PAYMENT') THEN 1 ELSE 0 END), 0) as debit_count,
            COALESCE(SUM(CASE WHEN movement_type IN ('DEBIT', 'TRANSFER_OUT', 'WITHDRAWAL', 'POBO_DEBIT', 'SWEEP_OUT', 'POOL_DEBIT', 'FEE', 'PAYMENT') THEN amount ELSE 0 END), 0) as debit_sum
        FROM va_movements
        WHERE va_id IN :vaIds
          AND transaction_date >= :fromDate
          AND transaction_date < :toDate
          AND status = 'COMPLETED'
        GROUP BY va_id
        """, nativeQuery = true)
    List<Object[]> getTransactionSummaryByVa(
        @Param("vaIds") List<UUID> vaIds,
        @Param("fromDate") LocalDateTime fromDate,
        @Param("toDate") LocalDateTime toDate
    );

    // ========================================================================
    // HIERARCHY PATH QUERIES (Alternative to Recursive CTE)
    // ========================================================================

    /**
     * Find all VAs under a hierarchy path prefix.
     *
     * <p>This is an alternative to recursive CTE for databases that
     * don't support it well. Uses the materialized path pattern.</p>
     *
     * @param pathPrefix The hierarchy path prefix (e.g., "/ROOT/EUR")
     * @return List of VA IDs matching the path prefix
     */
    @Query("SELECT v.id FROM VirtualAccount v " +
           "WHERE v.hierarchyPathVa LIKE CONCAT(:pathPrefix, '%') " +
           "AND v.status = 'ACTIVE'")
    List<UUID> findVaIdsByHierarchyPathPrefix(@Param("pathPrefix") String pathPrefix);

    /**
     * Find all VAs under a parent using parent_account_id (non-recursive).
     * Only gets direct children, not descendants.
     *
     * @param parentVaId Parent VA ID
     * @return List of direct child VA IDs
     */
    @Query("SELECT v.id FROM VirtualAccount v WHERE v.parentAccountId = :parentVaId AND v.status = 'ACTIVE'")
    List<UUID> findDirectChildVaIds(@Param("parentVaId") UUID parentVaId);
}
