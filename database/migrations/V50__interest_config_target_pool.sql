-- ============================================================================
-- V50 - interest_configurations.target_type accepts POOL (a notional pool's rates, spec R7).
--
-- chk_ic_target_type lists the TargetType values and does not follow the enum, so saving a pool's config failed
-- at commit. Widened with POOL; the existing values are unchanged.
--
-- Conditional because on a fresh database Flyway runs before Hibernate has created the table; Hibernate then
-- creates it from the enum, POOL included. Safe to run twice.
-- ============================================================================

DO $$
BEGIN
    IF to_regclass('public.interest_configurations') IS NOT NULL THEN
        ALTER TABLE public.interest_configurations DROP CONSTRAINT IF EXISTS chk_ic_target_type;
        ALTER TABLE public.interest_configurations ADD CONSTRAINT chk_ic_target_type CHECK (target_type IS NULL OR target_type IN (
            'VIRTUAL_ACCOUNT', 'PHYSICAL_ACCOUNT', 'LEGAL_ENTITY', 'PROGRAM', 'CURRENCY', 'CORPORATE', 'POOL'));
    END IF;
END $$;
