-- ============================================================================
-- HIERARCHICAL VA STATEMENT SQL QUERIES
-- ============================================================================
-- These queries support the VaStatementService for generating hierarchical
-- camt.053/054 statements with aggregated balances across VA trees.
--
-- Key Features:
-- 1. Recursive CTE for VA hierarchy traversal
-- 2. Batch transaction loading for multiple VAs
-- 3. Historical balance reconstruction
-- 4. Multi-currency aggregation
-- ============================================================================

-- ============================================================================
-- 1. RECURSIVE VA HIERARCHY TRAVERSAL
-- ============================================================================

-- Get all child VA IDs recursively (excludes the parent itself)
-- This is the core query for hierarchical statement generation
WITH RECURSIVE va_tree AS (
    -- Anchor: Direct children of the parent
    SELECT
        id,
        parent_account_id,
        hierarchy_level,
        va_number,
        va_name,
        currency_code,
        current_balance,
        available_balance,
        1 as depth
    FROM virtual_accounts
    WHERE parent_account_id = :parentVaId
      AND status = 'ACTIVE'

    UNION ALL

    -- Recursive: Children of each node at current level
    SELECT
        va.id,
        va.parent_account_id,
        va.hierarchy_level,
        va.va_number,
        va.va_name,
        va.currency_code,
        va.current_balance,
        va.available_balance,
        vt.depth + 1
    FROM virtual_accounts va
    INNER JOIN va_tree vt ON va.parent_account_id = vt.id
    WHERE va.status = 'ACTIVE'
      AND (:maxDepth IS NULL OR vt.depth < :maxDepth)
)
SELECT id FROM va_tree;

-- Get all VAs in hierarchy INCLUDING the root
WITH RECURSIVE va_tree AS (
    -- Start with root
    SELECT
        id,
        parent_account_id,
        hierarchy_level,
        va_number,
        va_name,
        currency_code,
        current_balance,
        available_balance,
        account_category,
        0 as depth
    FROM virtual_accounts
    WHERE id = :rootVaId

    UNION ALL

    -- Get all descendants
    SELECT
        va.id,
        va.parent_account_id,
        va.hierarchy_level,
        va.va_number,
        va.va_name,
        va.currency_code,
        va.current_balance,
        va.available_balance,
        va.account_category,
        vt.depth + 1
    FROM virtual_accounts va
    INNER JOIN va_tree vt ON va.parent_account_id = vt.id
    WHERE va.status = 'ACTIVE'
      AND (:maxDepth IS NULL OR vt.depth < :maxDepth)
)
SELECT * FROM va_tree
ORDER BY depth, va_number;

-- Get maximum hierarchy depth
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
SELECT COALESCE(MAX(depth), 0) as max_depth FROM va_tree;

-- ============================================================================
-- 2. BATCH TRANSACTION LOADING FOR MULTIPLE VAs
-- ============================================================================

-- Load all transactions for a list of VAs in date range (for aggregated statements)
SELECT
    t.*,
    va.va_number as source_va_number,
    va.va_name as source_va_name
FROM va_movements t
INNER JOIN virtual_accounts va ON t.va_id = va.id
WHERE t.va_id IN (:vaIds)
  AND t.transaction_date >= :fromDate
  AND t.transaction_date < :toDate
  AND t.status = 'COMPLETED'
ORDER BY t.transaction_date ASC;

-- Load with pagination for large statements
SELECT * FROM va_movements
WHERE va_id IN (:vaIds)
  AND transaction_date >= :fromDate
  AND transaction_date < :toDate
  AND status = 'COMPLETED'
ORDER BY transaction_date ASC
LIMIT :limit OFFSET :offset;

-- Count total transactions for pagination info
SELECT COUNT(*) as total_count
FROM va_movements
WHERE va_id IN (:vaIds)
  AND transaction_date >= :fromDate
  AND transaction_date < :toDate
  AND status = 'COMPLETED';

-- ============================================================================
-- 3. AGGREGATED BALANCE CALCULATIONS
-- ============================================================================

-- Sum balances for multiple VAs (for aggregation nodes)
SELECT
    COALESCE(SUM(current_balance), 0) as total_current_balance,
    COALESCE(SUM(available_balance), 0) as total_available_balance,
    COALESCE(SUM(held_balance), 0) as total_held_balance,
    COALESCE(SUM(effective_credit_limit), 0) as total_credit_limit,
    COUNT(*) as va_count
FROM virtual_accounts
WHERE id IN (:vaIds);

-- Sum balances grouped by currency (for multi-currency aggregation)
SELECT
    currency_code,
    COALESCE(SUM(current_balance), 0) as total_current_balance,
    COALESCE(SUM(available_balance), 0) as total_available_balance,
    COUNT(*) as va_count
FROM virtual_accounts
WHERE id IN (:vaIds)
GROUP BY currency_code
ORDER BY currency_code;

-- ============================================================================
-- 4. HISTORICAL BALANCE RECONSTRUCTION
-- ============================================================================

-- Calculate balance at a specific point in time
-- Formula: balance_at_time = current_balance - credits_after + debits_after

-- Step 1: Get current balance
SELECT COALESCE(SUM(current_balance), 0) as current_total
FROM virtual_accounts
WHERE id IN (:vaIds);

-- Step 2: Sum credits after the target time
SELECT COALESCE(SUM(amount), 0) as credits_after
FROM va_movements
WHERE va_id IN (:vaIds)
  AND transaction_date >= :asOfTime
  AND status = 'COMPLETED'
  AND movement_type IN ('CREDIT', 'TRANSFER_IN', 'TOPUP', 'ROBO_CREDIT',
                         'SWEEP_IN', 'POOL_CREDIT', 'FEE_CREDIT', 'SETTLEMENT_CREDIT');

-- Step 3: Sum debits after the target time
SELECT COALESCE(SUM(amount), 0) as debits_after
FROM va_movements
WHERE va_id IN (:vaIds)
  AND transaction_date >= :asOfTime
  AND status = 'COMPLETED'
  AND movement_type IN ('DEBIT', 'TRANSFER_OUT', 'WITHDRAWAL', 'POBO_DEBIT',
                         'SWEEP_OUT', 'POOL_DEBIT', 'FEE', 'PAYMENT');

-- Combined query for historical balance
SELECT
    (SELECT COALESCE(SUM(current_balance), 0) FROM virtual_accounts WHERE id IN (:vaIds))
    - (SELECT COALESCE(SUM(amount), 0) FROM va_movements
       WHERE va_id IN (:vaIds) AND transaction_date >= :asOfTime AND status = 'COMPLETED'
       AND movement_type IN ('CREDIT', 'TRANSFER_IN', 'TOPUP', 'ROBO_CREDIT',
                              'SWEEP_IN', 'POOL_CREDIT', 'FEE_CREDIT', 'SETTLEMENT_CREDIT'))
    + (SELECT COALESCE(SUM(amount), 0) FROM va_movements
       WHERE va_id IN (:vaIds) AND transaction_date >= :asOfTime AND status = 'COMPLETED'
       AND movement_type IN ('DEBIT', 'TRANSFER_OUT', 'WITHDRAWAL', 'POBO_DEBIT',
                              'SWEEP_OUT', 'POOL_DEBIT', 'FEE', 'PAYMENT'))
    as balance_at_time;

-- ============================================================================
-- 5. TRANSACTION SUMMARY FOR STATEMENT
-- ============================================================================

-- Get summary for multiple VAs
SELECT
    COALESCE(SUM(CASE WHEN movement_type IN ('CREDIT', 'TRANSFER_IN', 'TOPUP', 'ROBO_CREDIT',
                                              'SWEEP_IN', 'POOL_CREDIT', 'FEE_CREDIT')
                      THEN 1 ELSE 0 END), 0) as credit_count,
    COALESCE(SUM(CASE WHEN movement_type IN ('CREDIT', 'TRANSFER_IN', 'TOPUP', 'ROBO_CREDIT',
                                              'SWEEP_IN', 'POOL_CREDIT', 'FEE_CREDIT')
                      THEN amount ELSE 0 END), 0) as credit_sum,
    COALESCE(SUM(CASE WHEN movement_type IN ('DEBIT', 'TRANSFER_OUT', 'WITHDRAWAL', 'POBO_DEBIT',
                                              'SWEEP_OUT', 'POOL_DEBIT', 'FEE', 'PAYMENT')
                      THEN 1 ELSE 0 END), 0) as debit_count,
    COALESCE(SUM(CASE WHEN movement_type IN ('DEBIT', 'TRANSFER_OUT', 'WITHDRAWAL', 'POBO_DEBIT',
                                              'SWEEP_OUT', 'POOL_DEBIT', 'FEE', 'PAYMENT')
                      THEN amount ELSE 0 END), 0) as debit_sum
FROM va_movements
WHERE va_id IN (:vaIds)
  AND transaction_date >= :fromDate
  AND transaction_date < :toDate
  AND status = 'COMPLETED';

-- Get summary per VA for detailed breakdown
SELECT
    va_id,
    (SELECT va_number FROM virtual_accounts WHERE id = va_id) as va_number,
    COALESCE(SUM(CASE WHEN movement_type IN ('CREDIT', 'TRANSFER_IN', 'TOPUP', 'ROBO_CREDIT')
                      THEN 1 ELSE 0 END), 0) as credit_count,
    COALESCE(SUM(CASE WHEN movement_type IN ('CREDIT', 'TRANSFER_IN', 'TOPUP', 'ROBO_CREDIT')
                      THEN amount ELSE 0 END), 0) as credit_sum,
    COALESCE(SUM(CASE WHEN movement_type IN ('DEBIT', 'TRANSFER_OUT', 'WITHDRAWAL', 'POBO_DEBIT', 'FEE')
                      THEN 1 ELSE 0 END), 0) as debit_count,
    COALESCE(SUM(CASE WHEN movement_type IN ('DEBIT', 'TRANSFER_OUT', 'WITHDRAWAL', 'POBO_DEBIT', 'FEE')
                      THEN amount ELSE 0 END), 0) as debit_sum,
    COUNT(*) as entry_count
FROM va_movements
WHERE va_id IN (:vaIds)
  AND transaction_date >= :fromDate
  AND transaction_date < :toDate
  AND status = 'COMPLETED'
GROUP BY va_id
ORDER BY va_id;

-- ============================================================================
-- 6. ALTERNATIVE: MATERIALIZED PATH QUERIES (No Recursive CTE)
-- ============================================================================

-- Find all VAs under a hierarchy path prefix
-- Use this if the database doesn't support recursive CTE well
SELECT id, va_number, va_name, currency_code, current_balance
FROM virtual_accounts
WHERE hierarchy_path_va LIKE :pathPrefix || '%'
  AND status = 'ACTIVE'
ORDER BY hierarchy_path_va;

-- Find all descendants using self-join (limited depth)
-- This approach works without recursive CTE but has depth limitation
SELECT
    l1.id as level1_id,
    l2.id as level2_id,
    l3.id as level3_id,
    l4.id as level4_id,
    l5.id as level5_id
FROM virtual_accounts l1
LEFT JOIN virtual_accounts l2 ON l2.parent_account_id = l1.id AND l2.status = 'ACTIVE'
LEFT JOIN virtual_accounts l3 ON l3.parent_account_id = l2.id AND l3.status = 'ACTIVE'
LEFT JOIN virtual_accounts l4 ON l4.parent_account_id = l3.id AND l4.status = 'ACTIVE'
LEFT JOIN virtual_accounts l5 ON l5.parent_account_id = l4.id AND l5.status = 'ACTIVE'
WHERE l1.id = :rootVaId;

-- ============================================================================
-- 7. INDEXES FOR OPTIMIZATION
-- ============================================================================

-- Recommended indexes for hierarchical statement queries:

-- Index on parent_account_id for hierarchy traversal
CREATE INDEX IF NOT EXISTS idx_va_parent_account ON virtual_accounts(parent_account_id)
WHERE status = 'ACTIVE';

-- Composite index for transaction date range queries
CREATE INDEX IF NOT EXISTS idx_txn_va_date ON va_movements(va_id, transaction_date)
WHERE status = 'COMPLETED';

-- Index on hierarchy path for path-based queries
CREATE INDEX IF NOT EXISTS idx_va_hierarchy_path ON virtual_accounts(hierarchy_path_va);

-- Index for status filtering
CREATE INDEX IF NOT EXISTS idx_va_status ON virtual_accounts(status);

-- Composite index for balance aggregation
CREATE INDEX IF NOT EXISTS idx_va_status_balance ON virtual_accounts(status, currency_code, current_balance);

-- ============================================================================
-- 8. EXAMPLE USAGE: COMPLETE AGGREGATED STATEMENT QUERY
-- ============================================================================

-- This is a complete example showing how to generate an aggregated statement
-- for a parent VA including all its descendants

-- Step 1: Get all VA IDs in hierarchy
WITH RECURSIVE va_tree AS (
    SELECT id, 0 as depth
    FROM virtual_accounts
    WHERE id = '550e8400-e29b-41d4-a716-446655440000'  -- Replace with actual parent VA ID

    UNION ALL

    SELECT va.id, vt.depth + 1
    FROM virtual_accounts va
    INNER JOIN va_tree vt ON va.parent_account_id = vt.id
    WHERE va.status = 'ACTIVE'
),
all_va_ids AS (
    SELECT id FROM va_tree
),

-- Step 2: Get aggregated balances
aggregated_balances AS (
    SELECT
        COALESCE(SUM(current_balance), 0) as total_current,
        COALESCE(SUM(available_balance), 0) as total_available,
        COUNT(*) as va_count
    FROM virtual_accounts
    WHERE id IN (SELECT id FROM all_va_ids)
),

-- Step 3: Get transaction summary
txn_summary AS (
    SELECT
        COUNT(*) as total_entries,
        SUM(CASE WHEN movement_type IN ('CREDIT', 'TRANSFER_IN', 'TOPUP') THEN 1 ELSE 0 END) as credit_count,
        SUM(CASE WHEN movement_type IN ('CREDIT', 'TRANSFER_IN', 'TOPUP') THEN amount ELSE 0 END) as credit_sum,
        SUM(CASE WHEN movement_type IN ('DEBIT', 'TRANSFER_OUT', 'WITHDRAWAL', 'FEE') THEN 1 ELSE 0 END) as debit_count,
        SUM(CASE WHEN movement_type IN ('DEBIT', 'TRANSFER_OUT', 'WITHDRAWAL', 'FEE') THEN amount ELSE 0 END) as debit_sum
    FROM va_movements
    WHERE va_id IN (SELECT id FROM all_va_ids)
      AND transaction_date >= '2024-01-01'::timestamp
      AND transaction_date < '2024-02-01'::timestamp
      AND status = 'COMPLETED'
)

-- Step 4: Combine results
SELECT
    ab.total_current as closing_balance,
    ab.total_available as available_balance,
    ab.va_count,
    ts.*
FROM aggregated_balances ab
CROSS JOIN txn_summary ts;
