-- ============================================================================
-- V61 - netting_entries.source_type holds every SourceType: varchar(20) -> varchar(30).
--
-- The column was created as varchar(20) (local and the vam-five dump); NettingEntry maps it with length 30 and
-- ddl-auto never widens a column. INTERCOMPANY_RECEIVABLE is 23 characters, so adding any intercompany receivable to
-- a netting cycle (populate, Intercompany "Add to netting") failed at insert with 22001, a bare 500. Every row in
-- the table so far is POBO_RECHARGE, which fits.
--
-- Widens only a column narrower than 30; skipped when the table does not exist yet (Hibernate then creates it at 30).
-- Safe to run twice.
-- ============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'netting_entries'
                  AND column_name = 'source_type' AND character_maximum_length < 30) THEN
        ALTER TABLE netting_entries ALTER COLUMN source_type TYPE varchar(30);
    END IF;
END $$;
