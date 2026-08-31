-- V9 — Add a first-class corporate (and program) dimension to sweep_rules.
--
-- Cash Concentration's corporate picker could never filter: sweep_rules had
-- no corporate column at all, so the page's client-side
-- `rule.corporateId === selectedCorporate` predicate matched nothing and the
-- list emptied whenever a corporate was selected. CreateRuleModal already
-- sent a `corporateId` in the create payload, but with no column it was
-- silently dropped. This migration gives sweep rules a real corporate (and
-- program) association so the rule can be persisted with, and filtered by,
-- its owning corporate end to end.
--
-- Idempotent (IF NOT EXISTS) and nullable: legacy/seed rules created before
-- this change carry no corporate and remain valid (they show under "All
-- Corporates" only). Hibernate ddl-auto=update would add the bare columns
-- on entity change too; this file is the canonical record for fresh setups
-- (quickstart applies database/migrations/*.sql) and adds the filter index
-- Hibernate would not.
--
-- Matches the type used for corporate_id / program_id elsewhere
-- (virtual_accounts.*) — Postgres uuid.

ALTER TABLE sweep_rules ADD COLUMN IF NOT EXISTS corporate_id UUID;
ALTER TABLE sweep_rules ADD COLUMN IF NOT EXISTS program_id   UUID;

-- Filter support — Cash Concentration filters rules by corporate.
CREATE INDEX IF NOT EXISTS idx_sweep_rules_corporate_id ON sweep_rules (corporate_id);
