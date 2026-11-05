-- ============================================================================
-- V41 - fx_deal.status accepts the lifecycle statuses added after the table was first created.
--
-- Hibernate 6 writes a CHECK constraint listing an @Enumerated column's values when it creates the
-- table, and hibernate.ddl-auto: update never changes it afterwards. fx_deal was created with only
-- BOOKED, CONFIRMED, ACTIVE and PENDING_SETTLEMENT, so moving a deal to SETTLED, CLOSED_OUT, ROLLED,
-- CANCELLED, PARTIALLY_DRAWN or SETTLEMENT_FAILED failed at commit with a constraint violation.
-- That is DDL ddl-auto cannot express, so it belongs here (see CLAUDE.md, "Flyway").
--
-- Conditional because on a fresh database Flyway runs before Hibernate has created the table; Hibernate
-- then creates it with the full list, and there is nothing to widen. Safe to run twice.
-- CONFIRMED is gone: the corporate's confirmation is now fx_deal.confirmation_status, not a deal status.
-- ============================================================================

DO $$
BEGIN
    IF to_regclass('public.fx_deal') IS NOT NULL THEN
        ALTER TABLE public.fx_deal DROP CONSTRAINT IF EXISTS fx_deal_status_check;
        ALTER TABLE public.fx_deal ADD CONSTRAINT fx_deal_status_check CHECK (status IN (
            'BOOKED', 'ACTIVE', 'PENDING_SETTLEMENT', 'PARTIALLY_DRAWN', 'SETTLEMENT_FAILED',
            'SETTLED', 'CLOSED_OUT', 'ROLLED', 'CANCELLED'));
    END IF;
END $$;
