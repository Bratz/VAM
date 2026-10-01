-- Retire two accounts that were stamped as hierarchy roots but never were any.
--
-- FINAL IHB carried three accounts with account_category = 'ROOT'. Only one is a root: of the
-- three, FINALI-ROOT-3665 is the only one with a hierarchy_nodes row (node_type MASTER). The other
-- two have no node, no parent and no children -- they are sweep-destination accounts that were
-- given the ROOT category. Nothing in the codebase creates a TPOOL-* account, and nothing points at
-- them through treasury_pool_va_id (that column is marked deprecated, and live code resolves the
-- treasury side through the IHBS settlement VAs instead).
--
-- The reference model allows exactly one root per hierarchy and enforces it by replacement: adding
-- an aggregation account above the root makes the new account the root and demotes the old one to a
-- currency account. Three co-existing roots is a state it cannot reach.
--
-- TPOOL-MNC-TREASURY-GBP also held 1,000,000.00 that no report ever showed, because ROOT is excluded
-- from every position sum. It arrived via a single ZERO_BALANCE sweep on 2026-01-23 that took its
-- source account from 0.00 to -1,000,000.00 -- the same overdrawing shape already fixed in the sweep
-- sizing -- and that source account has since been deleted, which is why the rule then failed 695
-- times with "Source VA not found" and why the overdrawing-sweep reversal could not touch it: there
-- is no account left to put the money back into. Deleting the account removes no reported figure,
-- since nothing counted it.
--
-- Deliberately narrow, and it deletes only the two accounts:
--   * the sweep rule is DISABLED, not deleted. sweep_executions.rule_id is a real foreign key to it,
--     so removing the row would mean destroying 1,642 execution records to satisfy the constraint.
--     It is already PAUSED -- the only paused rule of seventeen, which is why the failures stop on
--     2026-01-29 -- so DISABLED simply makes the terminal state explicit.
--   * sweep_executions rows are kept. They denormalise target_account_number and rule_name, so the
--     1,642 attempts stay readable without the account.
--   * the two va_movements rows are kept, for the same reason -- a ledger is history, and one of the
--     pair already referenced a deleted account before this migration.
-- Guarded on the facts above, so it is a no-op anywhere the premise does not hold, and re-runnable.

-- 1. The rule can never succeed again: its source account is gone and its target is about to be.
--    Disabled rather than deleted -- see above.
UPDATE sweep_rules r
SET status = 'DISABLED'
WHERE r.status <> 'DISABLED'
  AND r.target_account_id IN (
        SELECT va.id FROM virtual_accounts va
        WHERE va.va_number IN ('TPOOL-MNC-TREASURY-GBP', 'TPOOL-MNC-TREASURY-AED')
          AND va.account_category = 'ROOT'
          AND NOT EXISTS (SELECT 1 FROM hierarchy_nodes n WHERE n.virtual_account_id = va.id)
  );

-- 2. The mislabelled accounts themselves -- only while they are still parentless, childless and
--    carry no hierarchy node, which is what distinguishes them from the program's real root, and
--    only once no rule still actively targets them.
DELETE FROM virtual_accounts va
WHERE va.va_number IN ('TPOOL-MNC-TREASURY-GBP', 'TPOOL-MNC-TREASURY-AED')
  AND va.account_category = 'ROOT'
  AND va.parent_account_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM hierarchy_nodes n WHERE n.virtual_account_id = va.id)
  AND NOT EXISTS (SELECT 1 FROM virtual_accounts c WHERE c.parent_account_id = va.id)
  AND NOT EXISTS (
      SELECT 1 FROM sweep_rules r
      WHERE r.target_account_id = va.id AND r.status <> 'DISABLED'
  );
