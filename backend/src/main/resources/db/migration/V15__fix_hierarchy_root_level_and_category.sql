-- ============================================================================
-- V15 — Repair two related virtual_accounts hierarchy corruptions, both
-- caused by conflating hierarchy_nodes.level_number (1-indexed: a ROOT
-- node = 1) with virtual_accounts.hierarchy_level (0-indexed: a ROOT VA
-- = 0). Every level-based currency-mirror/balance query in the app
-- (CurrencyMirrorService) treats a ROOT-category VA as level 0
-- unconditionally, but in the data this migration repairs, the stored
-- numeric level was sometimes wrong, and in two cases the VA wasn't even
-- categorized as ROOT at all — both silently excluded whole programs'
-- currencies from corporate-wide totals instead of preventing double
-- counting (the opposite failure mode, and much harder to notice).
--
-- Root-cause prevention for *future* hierarchies lives in application
-- code, not here:
--   - HierarchyService.java (ROOT VA creation) now stores hierarchyLevel
--     0, not 1.
--   - CurrencyMirrorService.createCurrencyMirrorVaForNode now derives
--     hierarchyLevel as (node.levelNumber - 1), not a direct copy.
--   - CurrencyMirrorService.isMirrorAtLevel treats AccountCategory.ROOT
--     as level 0 unconditionally, regardless of the stored number, so a
--     future seeding inconsistency degrades gracefully instead of
--     silently dropping a program's currencies again.
-- This migration repairs trees already corrupted before those fixes
-- existed. It is idempotent — safe to run against an already-correct
-- database, where both parts affect zero rows.
--
-- Part 1: a program's real root — identified by its hierarchy_nodes row
-- having no parent node, i.e. it IS that program's top — whose linked
-- virtual_accounts row was never reclassified to ROOT (left as
-- AGGREGATION, the category used everywhere else in the tree) and/or
-- was wrongly parented under an unrelated program's root VA instead of
-- being parentless. Reclassify and re-parent.
--
-- Part 2: recompute hierarchy_level for every ROOT-category VA and all
-- of its descendants, from *actual* parent-chain depth rather than
-- trusting the stored value — this both fixes Part 1's targets (which
-- had descendants inheriting their wrong level) and any other ROOT
-- whose own stored level was wrong for an unrelated reason (e.g.
-- inconsistent seeding). A wrong ROOT level shifts every descendant's
-- level by the same amount, so the whole subtree needs recomputing, not
-- just the root row.
-- ============================================================================

-- Part 1: reclassify/re-parent any program root that isn't a proper ROOT VA.
UPDATE virtual_accounts va
SET account_category = 'ROOT',
    parent_account_id = NULL
FROM hierarchy_nodes hn
WHERE va.id = hn.virtual_account_id
  AND hn.parent_id IS NULL
  AND va.account_category <> 'ROOT';

-- Part 2: recompute hierarchy_level for every ROOT and its descendants
-- from actual depth.
WITH RECURSIVE depths AS (
    SELECT id, 0 AS computed_depth
    FROM virtual_accounts
    WHERE account_category = 'ROOT'
    UNION ALL
    SELECT v.id, d.computed_depth + 1
    FROM virtual_accounts v
    JOIN depths d ON v.parent_account_id = d.id
)
UPDATE virtual_accounts va
SET hierarchy_level = depths.computed_depth
FROM depths
WHERE va.id = depths.id
  AND va.hierarchy_level IS DISTINCT FROM depths.computed_depth;
