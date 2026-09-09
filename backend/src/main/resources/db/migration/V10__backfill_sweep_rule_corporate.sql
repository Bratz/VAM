-- V10 — Backfill sweep_rules.corporate_id / program_id from the rule's
-- target (concentration) account.
--
-- V9 added the columns; legacy/seed sweep rules created before it carry
-- NULL, so they only ever surface under "All Corporates" on the Cash
-- Concentration page. A sweep rule concentrates funds INTO its target
-- account, so the rule's owning corporate (and program) is naturally that
-- target account's — resolved via target_account_id -> virtual_accounts.
--
-- Idempotent and non-clobbering:
--   * Only rows still NULL are filled, so rules created post-V9 (which
--     persist their own corporate from the create payload) are untouched.
--   * Rules whose target_account_id is NULL or references a deleted VA
--     have no matching join row and stay NULL — the honest state when no
--     corporate can be derived (they remain "All Corporates"-only).
-- Safe to re-run.

UPDATE sweep_rules sr
SET corporate_id = va.corporate_id,
    program_id   = va.program_id
FROM virtual_accounts va
WHERE va.id = sr.target_account_id
  AND sr.corporate_id IS NULL;
