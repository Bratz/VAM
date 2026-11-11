-- ============================================================================
-- V48 - Every IHB settlement account carries settlement_mark.
--
-- An account is an IHB settlement account when another account names it in ihb_settlement_va_id.
-- "IHB Settlement - MNC UK Ltd AED" was marked, "IHB Settlement - MNC Dubai Branch AED" was not, so
-- screens that leave settlement accounts out (Create Invoice's collection accounts) still offered it.
--
-- Data repair only; touches no account that is not referenced as an IHB settlement account.
-- Conditional because on a fresh database Flyway runs before Hibernate creates the table. Safe to rerun.
-- ============================================================================

DO $$
BEGIN
    IF to_regclass('public.virtual_accounts') IS NOT NULL
       AND EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'virtual_accounts' AND column_name = 'settlement_mark')
       AND EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'virtual_accounts' AND column_name = 'ihb_settlement_va_id') THEN
        UPDATE public.virtual_accounts s
           SET settlement_mark = TRUE
         WHERE s.settlement_mark IS DISTINCT FROM TRUE
           AND s.id IN (SELECT v.ihb_settlement_va_id FROM public.virtual_accounts v
                         WHERE v.ihb_settlement_va_id IS NOT NULL);
    END IF;
END $$;
