-- ============================================================================
-- V51 - pool_interest_calculations holds the daily pool runs (notional pooling spec R10, D5, D11).
--
-- One run per pool and value date: unique (pool_id, calculation_date), so a second run for the same date can never be
-- stored. The table was never written (no repository until now), so it is empty and the constraint applies cleanly.
-- No approval step (D11): approved_by / approved_at go; net_interest goes (pooled interest is gross_interest; the
-- benefit and the rates used are new columns that Hibernate adds from the entity). status has no CHECK constraint, so
-- its new values (CALCULATED, POSTED) need nothing here.
--
-- Conditional because on a fresh database Flyway runs before Hibernate has created the table; Hibernate then creates
-- it from the entity, unique constraint included (same name). Safe to run twice.
-- ============================================================================

DO $$
BEGIN
    IF to_regclass('public.pool_interest_calculations') IS NOT NULL THEN
        ALTER TABLE public.pool_interest_calculations DROP COLUMN IF EXISTS approved_by;
        ALTER TABLE public.pool_interest_calculations DROP COLUMN IF EXISTS approved_at;
        ALTER TABLE public.pool_interest_calculations DROP COLUMN IF EXISTS net_interest;
        ALTER TABLE public.pool_interest_calculations ALTER COLUMN status SET DEFAULT 'CALCULATED';
        ALTER TABLE public.pool_interest_calculations DROP CONSTRAINT IF EXISTS uq_pool_calc_pool_date;
        ALTER TABLE public.pool_interest_calculations ADD CONSTRAINT uq_pool_calc_pool_date UNIQUE (pool_id, calculation_date);
    END IF;
END $$;
