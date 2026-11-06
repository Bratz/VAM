-- ============================================================================
-- V42 - The corporate's FX line becomes part of the credit model.
--
-- The FX credit limit used to sit in its own table (fx_credit_limit, one row per corporate). It is now a
-- facility of type FX_TREASURY_LINE under the bank's credit agreement, whose external limit is the group FX
-- line (credit_limits.target_type FX_LINE), split into internal sub-limits per legal entity (FX_ENTITY).
-- Both CHECK constraints below were written by hand with fixed value lists, so new enum values need this.
-- Conditional because on a fresh database Flyway runs before the tables exist. Safe to run twice.
--
-- fx_credit_limit is dropped: its rows were all the 20m default, and FxLineSeeder gives every corporate an
-- FX facility and line at that amount on the next start.
-- ============================================================================

DO $$
BEGIN
    IF to_regclass('public.credit_facilities') IS NOT NULL THEN
        ALTER TABLE public.credit_facilities DROP CONSTRAINT IF EXISTS chk_cf_type;
        ALTER TABLE public.credit_facilities ADD CONSTRAINT chk_cf_type CHECK (facility_type IN (
            'OVERDRAFT', 'REVOLVING_CREDIT', 'TERM_LOAN', 'WORKING_CAPITAL', 'LETTER_OF_CREDIT',
            'BANK_GUARANTEE', 'CASH_CREDIT', 'BILL_DISCOUNTING', 'EXPORT_CREDIT', 'IMPORT_CREDIT',
            'PACKING_CREDIT', 'BUYERS_CREDIT', 'SUPPLIERS_CREDIT', 'FX_TREASURY_LINE'));
    END IF;
    IF to_regclass('public.credit_limits') IS NOT NULL THEN
        ALTER TABLE public.credit_limits DROP CONSTRAINT IF EXISTS chk_cl_target_type;
        ALTER TABLE public.credit_limits ADD CONSTRAINT chk_cl_target_type CHECK (target_type IN (
            'VIRTUAL_ACCOUNT', 'LEGAL_ENTITY', 'AGGREGATION_NODE', 'SHADOW_ACCOUNT', 'ROOT', 'CORPORATE',
            'FX_LINE', 'FX_ENTITY'));
    END IF;
END $$;

DROP TABLE IF EXISTS public.fx_credit_limit;
