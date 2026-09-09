-- V12 — Backfill notional_pools.corporate_id / program_id from the pool's
-- members' accounts (mirrors V10 for sweep_rules).
--
-- A pool has no target account; its owning corporate is the corporate of
-- its member accounts (pool_members.account_id -> virtual_accounts). All
-- existing pools were verified single-corporate, so the derivation is
-- unambiguous.
--
-- Honest / conservative:
--   * corporate_id is set only when every member resolves to exactly ONE
--     corporate (HAVING COUNT(DISTINCT corporate_id) = 1). A mixed-corporate
--     pool (none today) is left NULL — flagged for manual review rather
--     than guessed.
--   * program_id is set only when every member shares exactly one non-null
--     program; pools whose members span programs (legitimate) keep NULL.
--   * Only rows still NULL are filled — idempotent and non-clobbering
--     (pools created post-V11 persist their own corporate). Safe to re-run.

UPDATE notional_pools np
SET corporate_id = sub.cid,
    program_id   = sub.pid
FROM (
    SELECT pm.pool_id,
           (ARRAY_AGG(DISTINCT va.corporate_id))[1] AS cid,
           CASE
               WHEN COUNT(*) FILTER (WHERE va.program_id IS NULL) = 0
                AND COUNT(DISTINCT va.program_id) = 1
               THEN (ARRAY_AGG(DISTINCT va.program_id))[1]
               ELSE NULL
           END AS pid
    FROM pool_members pm
    JOIN virtual_accounts va ON va.id = pm.account_id
    WHERE va.corporate_id IS NOT NULL
    GROUP BY pm.pool_id
    HAVING COUNT(DISTINCT va.corporate_id) = 1
) sub
WHERE np.id = sub.pool_id
  AND np.corporate_id IS NULL;
