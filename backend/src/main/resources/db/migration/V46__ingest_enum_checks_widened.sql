-- ============================================================================
-- V46 - the file-ingest tables accept every current enum value.
--
-- ingest_job, format_signature, staged_transaction and timeline_event are created by Hibernate
-- (hibernate.ddl-auto: update), and Hibernate 6 writes a CHECK constraint listing an @Enumerated
-- column's values when it creates a table -- and never changes it afterwards (same as V41 for fx_deal and
-- V45 for collection_lines). On OCI these tables were created before the COLLECTIONS domain existed, so
-- every Collections upload failed at insert with a constraint violation (a bare 500), while the same file
-- uploaded as RECEIVABLES worked. Row status AWAITING_FX (FX-held file lines) is affected the same way.
--
-- Drops any CHECK on those columns whatever it is called, then adds one with the full current lists:
-- IngestDomain, IngestStage, RowStatus. Tables that do not exist yet are skipped (Hibernate then creates
-- them with the full lists). Safe to run twice.
-- ============================================================================

DO $$
DECLARE
    r record;
BEGIN
    FOR r IN
        SELECT c.conrelid::regclass AS tbl, c.conname
        FROM pg_constraint c
        WHERE c.contype = 'c'
          AND c.conrelid::regclass::text IN ('ingest_job', 'format_signature', 'staged_transaction', 'timeline_event')
          AND pg_get_constraintdef(c.oid) ~ '\m(domain|stage|status)\M'
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.tbl, r.conname);
    END LOOP;

    IF to_regclass('public.ingest_job') IS NOT NULL THEN
        ALTER TABLE public.ingest_job ADD CONSTRAINT ingest_job_domain_check CHECK (domain IN (
            'RECEIVABLES', 'RECEIVABLES_INVOICE', 'PAYABLES', 'PAYMENTS', 'COLLECTIONS'));
        ALTER TABLE public.ingest_job ADD CONSTRAINT ingest_job_stage_check CHECK (stage IN (
            'RECEIVED', 'ANALYZED', 'SIGNATURE_MATCHED', 'SIGNATURE_NEW', 'CODING_AGENT_RUNNING', 'TEST_GATE',
            'AWAITING_TRANSFORM', 'STAGED', 'PROCESSING', 'DONE', 'BLOCKED'));
    END IF;
    IF to_regclass('public.format_signature') IS NOT NULL THEN
        ALTER TABLE public.format_signature ADD CONSTRAINT format_signature_domain_check CHECK (domain IN (
            'RECEIVABLES', 'RECEIVABLES_INVOICE', 'PAYABLES', 'PAYMENTS', 'COLLECTIONS'));
    END IF;
    IF to_regclass('public.staged_transaction') IS NOT NULL THEN
        ALTER TABLE public.staged_transaction ADD CONSTRAINT staged_transaction_status_check CHECK (status IN (
            'STAGED', 'READY', 'QUARANTINED', 'PROCESSED', 'AWAITING_FX', 'FAILED'));
    END IF;
    IF to_regclass('public.timeline_event') IS NOT NULL THEN
        ALTER TABLE public.timeline_event ADD CONSTRAINT timeline_event_stage_check CHECK (stage IN (
            'RECEIVED', 'ANALYZED', 'SIGNATURE_MATCHED', 'SIGNATURE_NEW', 'CODING_AGENT_RUNNING', 'TEST_GATE',
            'AWAITING_TRANSFORM', 'STAGED', 'PROCESSING', 'DONE', 'BLOCKED'));
    END IF;
END $$;
