-- ============================================================================
-- V52 - A corrected pool run supersedes the old one (notional pooling spec R11a, D11).
--
-- V51 allowed one run per (pool, value date). A correction keeps the old run (marked SUPERSEDED) and stores a new one
-- for the same date, so the unique constraint becomes a partial unique index: one run per (pool, date) that is not
-- SUPERSEDED. The entity no longer declares the old constraint, so ddl-auto does not put it back.
--
-- Conditional for the same reason as V51: on a fresh database Flyway runs before Hibernate creates the table.
-- Safe to run twice.
-- ============================================================================

DO $$
BEGIN
    IF to_regclass('public.pool_interest_calculations') IS NOT NULL THEN
        ALTER TABLE public.pool_interest_calculations DROP CONSTRAINT IF EXISTS uq_pool_calc_pool_date;
        CREATE UNIQUE INDEX IF NOT EXISTS uq_pool_calc_pool_date_current
            ON public.pool_interest_calculations (pool_id, calculation_date) WHERE status <> 'SUPERSEDED';
    END IF;
END $$;
