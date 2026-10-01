-- Clear configuration that points at virtual accounts which no longer exist.
--
-- An earlier generation of the IHB setup was torn down and rebuilt in late January 2026. There are no
-- foreign keys on the columns that reference virtual_accounts, so the deletes left pointers behind in
-- rows that survived. Scanning every va/account reference column against its target table found two
-- kinds of leftover, and they need opposite treatment:
--
--   Configuration -- read at runtime, so a dangling pointer is a live defect. Cleared here.
--   History       -- a record of what happened, where the dangling id is a fact about the past rather
--                    than a broken setting. Left alone: 5,981 sweep_executions rows and 39
--                    va_movements rows, all of which denormalise the account number or rule name, so
--                    they stay readable without the account.
--
-- The accounts involved were IHB-MNC-UK-GBP-0001, IHB-MNC-UAE-DUBAI-USD-0001, IHB-MNC-UK-AED-0001,
-- IHB-MNC-UAE-AED-0001 (an earlier namesake of the live account, not the same row) and
-- TSETT-MNC-TREASURY-USD.
--
-- Re-runnable: both statements are conditioned on the reference still dangling.

-- 1. All four active legal entities carried a settlement_va_id pointing at a deleted account.
--    Cleared rather than repointed: the column is an override, and resolveSettlementVaForCurrency
--    reaches the same accounts through its IHB-current-account and TRANSACTION-VA steps, so an empty
--    override is correct where the intended target is no longer knowable. Guessing a replacement
--    would invent configuration the setup never had.
UPDATE legal_entities e
SET settlement_va_id = NULL
WHERE e.settlement_va_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM virtual_accounts v WHERE v.id = e.settlement_va_id);

-- 2. A sweep rule whose target account is gone can never succeed -- it fails every cycle with
--    "Source VA not found" or its target equivalent. Disabled, not deleted, because
--    sweep_executions.rule_id is a real foreign key and removing the row would mean destroying the
--    execution history to satisfy it. Same treatment as V30.
UPDATE sweep_rules r
SET status = 'DISABLED'
WHERE r.status <> 'DISABLED'
  AND r.target_account_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM virtual_accounts v WHERE v.id = r.target_account_id);
