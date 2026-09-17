-- ============================================================================
-- V18 — shadow_balance_snapshot: daily bank-balance history for
-- PHYSICAL_MIRROR shadow accounts, feeding the Multi-Bank Liquidity page's
-- new historical trend view.
--
-- Nothing captured this before: BalanceRefreshService.refresh() only
-- overwrote the shadow's own bank_balance/bank_available_balance in place,
-- and the existing daily EOD reconstruction (VaStatementRepository's
-- getDailyBalanceSnapshots) only sees ledger-routed transaction movements,
-- not bank-balance refreshes -- a different, partially-overlapping number.
--
-- BalanceRefreshService now writes one row per shadow per day going
-- forward (upsert on shadow_va_id+as_of -- see the unique index below).
-- This migration seeds 90 days of synthetic demo history so the trend has
-- something to show immediately rather than only accumulating from today.
-- Today itself is deliberately NOT seeded here -- the real refresh hook
-- owns it, so a fresh app boot doesn't insert a row that immediately
-- conflicts with (or shadows) the first real refresh of the day.
--
-- Each seeded day is a deterministic +/-10% walk off the shadow's CURRENT
-- balance, keyed by hashtext(shadow id || day) so re-running this migration
-- (it won't, Flyway tracks it once) or reading the data back is
-- reproducible -- no stored-random dependency.
--
-- The table is also @Entity-mapped (ShadowBalanceSnapshot.java), so
-- hibernate.ddl-auto: update normally owns its DDL -- but Flyway runs
-- before ddl-auto on a fresh database, and this migration needs the table
-- to insert into. CREATE ... IF NOT EXISTS makes the ordering harmless
-- either way: whichever of Flyway/ddl-auto runs first creates it, the
-- other is a no-op.
-- ============================================================================

CREATE TABLE IF NOT EXISTS shadow_balance_snapshot (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shadow_va_id             UUID NOT NULL,
    corporate_id             UUID,
    currency_code            VARCHAR(3),
    bank_balance             NUMERIC(19,4),
    bank_available_balance   NUMERIC(19,4),
    bank_balance_effective   NUMERIC(19,4),
    as_of                    DATE NOT NULL,
    created_at               TIMESTAMP NOT NULL DEFAULT now(),
    updated_at               TIMESTAMP,
    created_by               VARCHAR(255),
    updated_by               VARCHAR(255),
    version                  BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_shadow_snapshot_shadow_asof ON shadow_balance_snapshot (shadow_va_id, as_of);
CREATE INDEX IF NOT EXISTS idx_shadow_snapshot_corporate_asof ON shadow_balance_snapshot (corporate_id, as_of);

-- id is supplied explicitly (not left to the table's own DEFAULT) because
-- when this table was created by hibernate.ddl-auto instead of the
-- CREATE TABLE above (whichever ran first), Hibernate's GenerationType.UUID
-- assigns ids in application code, not via a DB-side column default -- so
-- the column has no default to fall back on in that case.
INSERT INTO shadow_balance_snapshot
    (id, shadow_va_id, corporate_id, currency_code, bank_balance, bank_available_balance, bank_balance_effective, as_of)
SELECT
    gen_random_uuid(),
    v.id,
    v.corporate_id,
    v.currency_code,
    ROUND(COALESCE(v.bank_balance, 0) * factor, 2),
    ROUND(COALESCE(v.bank_available_balance, v.bank_balance, 0) * factor, 2),
    ROUND((COALESCE(v.bank_available_balance, v.bank_balance, 0) * factor)
          - COALESCE(v.bank_balance_committed, 0), 2),
    d.as_of
FROM virtual_accounts v
CROSS JOIN LATERAL generate_series(CURRENT_DATE - 89, CURRENT_DATE - 1, interval '1 day') AS d(as_of)
CROSS JOIN LATERAL (
    SELECT 0.9 + 0.2 * (((hashtext(v.id::text || d.as_of::text) % 1000) + 1000) % 1000) / 1000.0 AS factor
) f
WHERE v.account_category = 'PHYSICAL_MIRROR';
