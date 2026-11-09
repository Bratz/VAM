-- ============================================================================
-- V45 - collection_lines.status accepts REJECTED and RETURNED (direct-debit rejects and returns).
--
-- Hibernate 6 writes a CHECK constraint listing an @Enumerated column's values when it creates the
-- table, and hibernate.ddl-auto: update never changes it afterwards (same as V41 for fx_deal). The
-- table was created with SENT .. FAILED, so recording a return failed at commit.
--
-- Conditional because on a fresh database Flyway runs before Hibernate has created the table; Hibernate
-- then creates it with the full list, and there is nothing to widen. Safe to run twice.
-- ============================================================================

DO $$
BEGIN
    IF to_regclass('public.collection_lines') IS NOT NULL THEN
        ALTER TABLE public.collection_lines DROP CONSTRAINT IF EXISTS collection_lines_status_check;
        ALTER TABLE public.collection_lines ADD CONSTRAINT collection_lines_status_check CHECK (status IN (
            'SENT', 'SETTLED', 'AVAILABLE', 'RTP_SENT', 'EXCLUDED', 'FAILED', 'REJECTED', 'RETURNED'));
    END IF;
END $$;
