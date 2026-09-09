-- client_encoding pinned to UTF8 for this session: without it, psql falls
-- back to the shell locale's encoding (often not UTF-8 on a fresh box/CI
-- runner), which silently mangles every non-ASCII character below into
-- mojibake on insert (e.g. '·' becomes 'Â·') -- a data corruption, not a
-- display bug, since it's the bytes actually written to the table. See
-- V14__fix_mojibake_encoding.sql for the one-time repair of data seeded
-- before this fix existed.
SET client_encoding = 'UTF8';

-- ============================================================================
-- Simulator Phase 2 — physical_accounts financials backfill (demo data)
-- ============================================================================
-- The seed ships with NULL interest/overdraft/effective-rate on every
-- physical account, which would make the Optimisation Score all-zeros. This
-- backfills plausible 2026 figures so the Simulator demonstrates real value.
--
-- IDEMPOTENT + NON-DESTRUCTIVE: every UPDATE is guarded so it only fills
-- NULL/zero rows — it never overwrites real data. Safe to re-run.
-- ============================================================================

-- Nominal annual interest rate, by account currency (plausible 2026 bands).
UPDATE physical_accounts SET interest_rate = CASE currency_code
        WHEN 'USD' THEN 4.5000
        WHEN 'AED' THEN 4.2500
        WHEN 'SAR' THEN 5.0000
        WHEN 'EUR' THEN 3.0000
        WHEN 'GBP' THEN 4.2500
        WHEN 'SGD' THEN 3.2500
        ELSE 3.5000
    END
WHERE interest_rate IS NULL;

-- Effective rate sits a touch below nominal (fees / tiering drag).
UPDATE physical_accounts
   SET effective_interest_rate = GREATEST(interest_rate - 0.2500, 0)
WHERE effective_interest_rate IS NULL AND interest_rate IS NOT NULL;

-- Sanctioned overdraft ≈ 20% of current balance.
UPDATE physical_accounts
   SET overdraft_limit = ROUND(COALESCE(current_balance, 0) * 0.20, 2)
WHERE overdraft_limit IS NULL;

-- Average drawn overdraft ≈ 10% of the sanctioned limit (so the
-- debt-avoided line is non-trivial without being alarmist).
UPDATE physical_accounts
   SET overdraft_utilized = ROUND(COALESCE(overdraft_limit, 0) * 0.10, 2)
WHERE (overdraft_utilized IS NULL OR overdraft_utilized = 0)
  AND overdraft_limit IS NOT NULL;
