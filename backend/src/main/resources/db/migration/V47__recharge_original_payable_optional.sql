-- ============================================================================
-- V47 - intercompany_recharges.original_payable_id may be null.
--
-- A direct POBO payment pays no payable, and its recharge used to be given a random UUID to
-- satisfy NOT NULL -- a payable id that pointed at nothing. The entity no longer marks the column
-- nullable = false, but hibernate.ddl-auto: update never drops a NOT NULL it created, hence this.
--
-- Conditional because on a fresh database Flyway runs before Hibernate has created the table.
-- Safe to run twice.
-- ============================================================================

DO $$
BEGIN
    IF to_regclass('public.intercompany_recharges') IS NOT NULL THEN
        ALTER TABLE public.intercompany_recharges ALTER COLUMN original_payable_id DROP NOT NULL;
    END IF;
END $$;
