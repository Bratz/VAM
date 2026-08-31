-- V11 — Add a first-class corporate (and program) dimension to
-- notional_pools (mirrors V9 for sweep_rules).
--
-- Notional Pooling's corporate picker was a silent no-op: notional_pools
-- had no corporate column, so the page's client-side predicate
-- `!p.corporateId || p.corporateId === selectedCorporateId` was always
-- fail-open (p.corporateId undefined → every pool always passed → the
-- picker filtered nothing). CreatePoolModal already passed a corporateId,
-- but with no column it was silently dropped.
--
-- Idempotent (IF NOT EXISTS) and nullable: legacy/seed pools created
-- before this carry no corporate; V12 backfills them from their members'
-- accounts. Hibernate ddl-auto=update would add the bare columns on entity
-- change too; this file is the canonical record for fresh setups
-- (quickstart applies database/migrations/*.sql) and adds the filter index.
--
-- uuid to match corporate_id / program_id elsewhere (virtual_accounts.*).

ALTER TABLE notional_pools ADD COLUMN IF NOT EXISTS corporate_id UUID;
ALTER TABLE notional_pools ADD COLUMN IF NOT EXISTS program_id   UUID;

CREATE INDEX IF NOT EXISTS idx_notional_pools_corporate_id ON notional_pools (corporate_id);
