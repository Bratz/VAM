package com.bank.vam.service.statement;

import com.bank.vam.dto.statement.Camt053Dto.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Service interface for generating hierarchical VA account statements (camt.053/054).
 *
 * <h2>Key Features:</h2>
 * <ul>
 *   <li>Single VA statements (leaf level)</li>
 *   <li>Aggregated statements (includes all child VAs recursively)</li>
 *   <li>Recursive VA hierarchy traversal</li>
 *   <li>Aggregated balance calculation</li>
 *   <li>ISO 20022 camt.053/052/054 XML generation</li>
 * </ul>
 *
 * <h2>Hierarchical Aggregation Logic:</h2>
 * <ul>
 *   <li>A VA can have child VAs (aggregation account)</li>
 *   <li>Statement for an aggregation account includes ALL transactions from child VAs recursively</li>
 *   <li>Top-level account shows consolidated statement across entire VA tree</li>
 *   <li>Balances are aggregated bottom-up from leaf VAs</li>
 * </ul>
 *
 * <h2>Balance Types:</h2>
 * <ul>
 *   <li>OPBD - Opening Booked Balance (balance at start of period)</li>
 *   <li>CLBD - Closing Booked Balance (balance at end of period)</li>
 *   <li>CLAV - Closing Available Balance</li>
 *   <li>For aggregation: sum of all child VA balances</li>
 * </ul>
 *
 * <h2>Performance Considerations:</h2>
 * <ul>
 *   <li>Recursive CTE for fetching VA hierarchy (single query)</li>
 *   <li>Batch loading of transactions</li>
 *   <li>Caching of VA tree structure</li>
 *   <li>Pagination support for large statement periods</li>
 * </ul>
 *
 * @see Camt053Dto
 * @see VaStatementServiceImpl
 */
public interface VaStatementService {

    // ========================================================================
    // SINGLE VA STATEMENT GENERATION
    // ========================================================================

    /**
     * Generate statement for a single VA (leaf level).
     *
     * <p>This generates a standard bank statement for a single virtual account,
     * containing all transactions within the specified date range.</p>
     *
     * @param vaId     The virtual account ID
     * @param fromDate Start of statement period (inclusive)
     * @param toDate   End of statement period (inclusive)
     * @return Complete camt.053 statement with balances and entries
     */
    Camt053Statement generateStatement(UUID vaId, LocalDate fromDate, LocalDate toDate);

    /**
     * Generate statement for a single VA with full request options.
     *
     * @param request Statement request with all options
     * @return Complete camt.053 statement
     */
    Camt053Statement generateStatement(StatementRequest request);

    // ========================================================================
    // AGGREGATED STATEMENT GENERATION (HIERARCHICAL)
    // ========================================================================

    /**
     * Generate aggregated statement that includes all child VAs recursively.
     *
     * <p>This is the key method for hierarchical statement generation. It:</p>
     * <ol>
     *   <li>Traverses the VA hierarchy starting from the aggregation VA</li>
     *   <li>Collects all transactions from all descendant VAs</li>
     *   <li>Aggregates balances across the entire subtree</li>
     *   <li>Sorts entries by booking date/time</li>
     *   <li>Includes VA identifier in entry details for traceability</li>
     * </ol>
     *
     * <h3>Algorithm:</h3>
     * <pre>
     * 1. Get all child VA IDs recursively using recursive CTE
     * 2. Calculate opening balance = sum of all child VA balances at fromDate
     * 3. Batch load all transactions for all VAs in date range
     * 4. Sort transactions by booking datetime
     * 5. Calculate closing balance = opening + sum(credits) - sum(debits)
     * 6. Build statement with entries tagged with source VA
     * </pre>
     *
     * @param aggregationVaId The aggregation/parent VA ID
     * @param fromDate        Start of statement period (inclusive)
     * @param toDate          End of statement period (inclusive)
     * @return Aggregated camt.053 statement containing all child VA entries
     */
    Camt053Statement generateAggregatedStatement(UUID aggregationVaId, LocalDate fromDate, LocalDate toDate);

    /**
     * Generate aggregated statement with full request options.
     *
     * @param request Aggregated statement request with all options
     * @return Aggregated camt.053 statement
     */
    Camt053Statement generateAggregatedStatement(AggregatedStatementRequest request);

    // ========================================================================
    // HIERARCHY TRAVERSAL
    // ========================================================================

    /**
     * Get all child VA IDs recursively for a parent VA.
     *
     * <p>Uses a recursive CTE to efficiently fetch the entire subtree
     * in a single database query.</p>
     *
     * <h3>SQL Strategy (Recursive CTE):</h3>
     * <pre>
     * WITH RECURSIVE va_tree AS (
     *   -- Base case: the parent VA itself
     *   SELECT id, parent_account_id, hierarchy_level, 0 as depth
     *   FROM virtual_accounts
     *   WHERE id = :parentVaId
     *
     *   UNION ALL
     *
     *   -- Recursive case: children of current level
     *   SELECT va.id, va.parent_account_id, va.hierarchy_level, vt.depth + 1
     *   FROM virtual_accounts va
     *   INNER JOIN va_tree vt ON va.parent_account_id = vt.id
     *   WHERE va.status = 'ACTIVE'
     *     AND (maxDepth IS NULL OR vt.depth < maxDepth)
     * )
     * SELECT id FROM va_tree WHERE id != :parentVaId
     * </pre>
     *
     * @param parentVaId The parent VA ID
     * @return List of all child VA IDs (excluding the parent itself)
     */
    List<UUID> getAllChildVaIds(UUID parentVaId);

    /**
     * Get all child VA IDs with depth limit.
     *
     * @param parentVaId The parent VA ID
     * @param maxDepth   Maximum hierarchy depth to traverse (null = unlimited)
     * @return List of child VA IDs up to the specified depth
     */
    List<UUID> getAllChildVaIds(UUID parentVaId, Integer maxDepth);

    /**
     * Get the complete VA hierarchy tree as a nested structure.
     *
     * <p>Returns a tree representation of the VA hierarchy, useful for
     * UI display and debugging.</p>
     *
     * @param rootVaId The root VA ID
     * @return Tree structure with nested children
     */
    VaHierarchyNode getVaHierarchyTree(UUID rootVaId);

    /**
     * Get the complete VA hierarchy tree with depth limit.
     *
     * @param rootVaId The root VA ID
     * @param maxDepth Maximum depth to traverse
     * @return Tree structure with nested children
     */
    VaHierarchyNode getVaHierarchyTree(UUID rootVaId, Integer maxDepth);

    // ========================================================================
    // BALANCE CALCULATION
    // ========================================================================

    /**
     * Calculate aggregated balance for a VA as of a specific date.
     *
     * <p>For leaf VAs, returns the VA's balance directly.
     * For aggregation VAs, sums the balances of all child VAs.</p>
     *
     * <h3>Balance Types Calculated:</h3>
     * <ul>
     *   <li>Booked Balance: Sum of currentBalance across all children</li>
     *   <li>Available Balance: Sum of availableBalance across all children</li>
     *   <li>Held Balance: Sum of heldBalance across all children</li>
     * </ul>
     *
     * <h3>For Historical Balances:</h3>
     * <p>To calculate balance as of a past date:</p>
     * <pre>
     * balance_at_date = current_balance - sum(credits_after_date) + sum(debits_after_date)
     * </pre>
     *
     * @param vaId     The VA ID (can be leaf or aggregation)
     * @param asOfDate The date to calculate balance as of
     * @return Aggregated balance with breakdown
     */
    AggregatedBalance calculateAggregatedBalance(UUID vaId, LocalDate asOfDate);

    /**
     * Calculate aggregated balance with full options.
     *
     * @param request Balance calculation request
     * @return Aggregated balance with breakdown
     */
    AggregatedBalance calculateAggregatedBalance(BalanceCalculationRequest request);

    /**
     * Calculate opening balance for a VA (or VA tree) at a specific date.
     *
     * <p>This is the balance at the start of the day on the given date.
     * For aggregation VAs, it's the sum of all child VA opening balances.</p>
     *
     * @param vaId The VA ID
     * @param date The date to calculate opening balance for
     * @return Opening balance (booked)
     */
    Balance calculateOpeningBalance(UUID vaId, LocalDate date);

    /**
     * Calculate closing balance for a VA (or VA tree) at a specific date.
     *
     * <p>This is the balance at the end of the day on the given date.
     * For aggregation VAs, it's the sum of all child VA closing balances.</p>
     *
     * @param vaId The VA ID
     * @param date The date to calculate closing balance for
     * @return Closing balance (booked)
     */
    Balance calculateClosingBalance(UUID vaId, LocalDate date);

    // ========================================================================
    // INTRADAY / NOTIFICATION STATEMENTS
    // ========================================================================

    /**
     * Generate intraday statement (camt.052).
     *
     * <p>Similar to camt.053 but for intraday reporting with
     * interim balances (ITBD, ITAV).</p>
     *
     * @param vaId      The VA ID
     * @param asOfTime  The timestamp to generate statement as of
     * @param aggregated Whether to include child VAs
     * @return Intraday statement
     */
    Camt053Statement generateIntradayStatement(UUID vaId, java.time.LocalDateTime asOfTime, boolean aggregated);

    /**
     * Generate debit/credit notification (camt.054) for a specific transaction.
     *
     * <p>Used for real-time transaction notifications.</p>
     *
     * @param transactionId The transaction ID
     * @return Notification statement
     */
    Camt053Statement generateTransactionNotification(UUID transactionId);

    // ========================================================================
    // XML GENERATION
    // ========================================================================

    /**
     * Generate camt.053 XML from a statement object.
     *
     * @param statement The statement to convert to XML
     * @return ISO 20022 camt.053 compliant XML
     */
    String generateCamt053Xml(Camt053Statement statement);

    /**
     * Generate camt.052 XML (intraday) from a statement object.
     *
     * @param statement The statement to convert to XML
     * @return ISO 20022 camt.052 compliant XML
     */
    String generateCamt052Xml(Camt053Statement statement);

    /**
     * Generate camt.054 XML (notification) from a statement object.
     *
     * @param statement The statement to convert to XML
     * @return ISO 20022 camt.054 compliant XML
     */
    String generateCamt054Xml(Camt053Statement statement);

    // ========================================================================
    // CACHING & PERFORMANCE
    // ========================================================================

    /**
     * Get cached VA hierarchy for a root VA.
     *
     * <p>Returns cached hierarchy if available and not stale,
     * otherwise rebuilds and caches.</p>
     *
     * @param rootVaId The root VA ID
     * @return List of all VA IDs in the hierarchy (including root)
     */
    List<UUID> getCachedVaHierarchy(UUID rootVaId);

    /**
     * Invalidate cached VA hierarchy.
     *
     * <p>Should be called when VA hierarchy changes (VA added/moved/deleted).</p>
     *
     * @param rootVaId The root VA ID whose cache should be invalidated
     */
    void invalidateHierarchyCache(UUID rootVaId);

    /**
     * Preload VA hierarchy cache for a corporate.
     *
     * <p>Useful for warming up cache before batch statement generation.</p>
     *
     * @param corporateId The corporate ID
     */
    void preloadHierarchyCache(UUID corporateId);
}
