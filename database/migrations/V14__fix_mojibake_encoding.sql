-- ============================================================================
-- V14 — Repair mojibake introduced by seeding UTF-8 SQL files through a
-- psql client whose client_encoding was not UTF8 (Windows-1252 by the
-- pattern of corruption). Multi-byte UTF-8 sequences got decoded one byte
-- at a time as CP1252 and re-saved as UTF-8, so e.g. the middot '·'
-- (bytes C2 B7) became the four-byte string 'Â·', and the arrow '→'
-- (bytes E2 86 92) became 'â†’'. The wrong bytes are now permanently
-- stored in the database (this is a data problem, not a display bug), so
-- fixing the frontend or the JSON response encoding would not help.
--
-- Root-cause prevention for *future* seed runs lives in
-- database/seed/run_seed.sh (forces PGCLIENTENCODING=UTF8). This
-- migration repairs data already corrupted by seed runs that happened
-- before that fix existed.
--
-- Repair: convert_to(text, 'WIN1252') re-encodes the (wrongly-decoded)
-- characters back to their original single-byte codes, and
-- convert_from(..., 'UTF8') then decodes those bytes as UTF-8, recovering
-- the original character. Only applied to rows matching MOJIBAKE_MARKER
-- below, so already-correct text (e.g. a genuine "Schäfer" or "â" in French
-- prose) is left untouched — 'â' alone is a real Latin character, but 'â'
-- immediately followed by one of the CP1252 C1 punctuation glyphs
-- (†, —, ', " etc.) is not: that combination only occurs when a UTF-8
-- arrow/dash/quote got split into its raw bytes and each byte re-decoded
-- as CP1252, so it's a safe, specific tell for this exact corruption.
-- ============================================================================

DO $$
DECLARE
    rec RECORD;
    affected BIGINT;
BEGIN
    FOR rec IN
        SELECT c.table_schema, c.table_name, c.column_name
        FROM information_schema.columns c
        JOIN information_schema.tables t
          ON t.table_schema = c.table_schema AND t.table_name = c.table_name
        WHERE c.table_schema = 'public'
          AND t.table_type = 'BASE TABLE'
          AND c.data_type IN ('character varying', 'text', 'character')
    LOOP
        BEGIN
            EXECUTE format(
                'UPDATE %I.%I SET %I = convert_from(convert_to(%I, ''WIN1252''), ''UTF8'')
                 WHERE %I ~ ''Â·|â[€‚ƒ„…†‡ˆ‰Š‹ŒŽ‘’“”•–—˜™š›œžŸ]''',
                rec.table_schema, rec.table_name, rec.column_name, rec.column_name, rec.column_name
            );
            GET DIAGNOSTICS affected = ROW_COUNT;
            IF affected > 0 THEN
                RAISE NOTICE 'Repaired % mojibake value(s) in %.%.%',
                    affected, rec.table_schema, rec.table_name, rec.column_name;
            END IF;
        EXCEPTION WHEN OTHERS THEN
            -- A column can match the marker regex without actually being
            -- CP1252-mangled UTF-8 (e.g. genuine non-Latin1 text that
            -- happens to contain 'Ã'/'Â'). convert_to(..., 'WIN1252') then
            -- fails with untranslatable_character/invalid_byte_sequence.
            -- Skip that column rather than aborting the whole repair.
            RAISE WARNING 'Skipped %.%.% (not CP1252-mangled UTF-8?): %',
                rec.table_schema, rec.table_name, rec.column_name, SQLERRM;
        END;
    END LOOP;
END $$;
