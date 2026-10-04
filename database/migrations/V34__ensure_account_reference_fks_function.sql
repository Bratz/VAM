-- Make the foreign-key loop callable, so adding the rest never needs another migration.
--
-- V32 added 23 of 24 account-reference keys and V33 repeated the whole loop to retry the one it was
-- refused. Both were one-shot, and that is the actual defect: payables is owned by postgres instead
-- of the application role, so only an operator with superuser rights can unblock it, and whenever
-- they get round to it every migration that could have finished the job has already run and been
-- recorded. A third copy of the loop would inherit the same problem.
--
-- So the loop becomes a function. After the ownership fix,
--
--     ALTER TABLE payables OWNER TO vam_user;   -- as a superuser
--     SELECT ensure_account_reference_fks();    -- as the application role
--
-- finishes it, at any time, with no migration and no restart. The function is idempotent: it skips
-- every constraint already present, so it is a no-op once satisfied and safe to call by hand or on a
-- schedule. CREATE OR REPLACE, so a later migration can extend the list rather than copy it again.
CREATE OR REPLACE FUNCTION ensure_account_reference_fks()
RETURNS TABLE (constraint_name TEXT, outcome TEXT)
LANGUAGE plpgsql AS $fn$
DECLARE
    ref RECORD;
    fk_name TEXT;
BEGIN
    FOR ref IN
        SELECT * FROM (VALUES
            ('account_attachments',     'virtual_account_id',  'virtual_accounts'),
            ('balance_alert',           'va_id',               'virtual_accounts'),
            ('exception_transactions',  'exception_va_id',     'virtual_accounts'),
            ('exception_transactions',  'original_va_id',      'virtual_accounts'),
            ('exception_transactions',  'target_va_id',        'virtual_accounts'),
            ('hierarchy_nodes',         'virtual_account_id',  'virtual_accounts'),
            ('legal_entities',          'settlement_va_id',    'virtual_accounts'),
            ('payables',                'virtual_account_id',  'virtual_accounts'),
            ('payment_requests',        'source_va_id',        'virtual_accounts'),
            ('pool_members',            'account_id',          'virtual_accounts'),
            ('receivables',             'virtual_account_id',  'virtual_accounts'),
            ('shadow_balance_snapshot', 'shadow_va_id',        'virtual_accounts'),
            ('sweep_executions',        'source_account_id',   'virtual_accounts'),
            ('sweep_executions',        'target_account_id',   'virtual_accounts'),
            ('sweep_rule_sources',      'account_id',          'virtual_accounts'),
            ('sweep_rules',             'target_account_id',   'virtual_accounts'),
            ('va_movements',            'va_id',               'virtual_accounts'),
            ('va_movements',            'counterparty_va_id',  'virtual_accounts'),
            ('va_movements',            'behalf_of_va_id',     'virtual_accounts'),
            ('vibans',                  'virtual_account_id',  'virtual_accounts'),
            ('credit_facilities',       'physical_account_id', 'physical_accounts'),
            ('shadow_sync_log',         'physical_account_id', 'physical_accounts'),
            ('unified_programs',        'physical_account_id', 'physical_accounts'),
            ('va_movements',            'physical_account_id', 'physical_accounts')
        ) AS t(child_table, child_column, parent_table)
    LOOP
        fk_name := 'fk_' || ref.child_table || '_' || ref.child_column;

        -- The schema is built by ddl-auto, so a table or column can legitimately be absent here.
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = ref.child_table
              AND column_name = ref.child_column
        ) OR NOT EXISTS (
            SELECT 1 FROM information_schema.tables
            WHERE table_schema = 'public' AND table_name = ref.parent_table
        ) THEN
            RETURN QUERY SELECT fk_name, 'skipped: table or column not present'::TEXT;
            CONTINUE;
        END IF;

        IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = fk_name) THEN
            RETURN QUERY SELECT fk_name, 'already present'::TEXT;
            CONTINUE;
        END IF;

        -- NOT VALID is the design, not a shortcut: enforced for inserts, updates and deletes of the
        -- referenced row, while existing rows are never examined. History keeps the dangling ids left
        -- by accounts deleted before any of this existed; nothing new can point at an absent account.
        BEGIN
            EXECUTE format(
                'ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (%I) REFERENCES %I(id) NOT VALID',
                ref.child_table, fk_name, ref.child_column, ref.parent_table);
        EXCEPTION WHEN insufficient_privilege THEN
            RETURN QUERY SELECT fk_name,
                format('BLOCKED: %s is not owned by %s -- a superuser must run '
                       'ALTER TABLE %s OWNER TO %s, then call this function again',
                       ref.child_table, current_user, ref.child_table, current_user)::TEXT;
            CONTINUE;
        END;

        -- Validate where existing rows already comply, so a reader can tell a clean column from one
        -- carrying historical orphans. A table with orphans keeps the constraint NOT VALID.
        BEGIN
            EXECUTE format('ALTER TABLE %I VALIDATE CONSTRAINT %I', ref.child_table, fk_name);
            RETURN QUERY SELECT fk_name, 'added and validated'::TEXT;
        EXCEPTION WHEN foreign_key_violation THEN
            RETURN QUERY SELECT fk_name, 'added, left NOT VALID (history references removed accounts)'::TEXT;
        END;
    END LOOP;
END
$fn$;

COMMENT ON FUNCTION ensure_account_reference_fks() IS
    'Idempotently ensures a foreign key on every column holding an account id. Safe to call at any time; returns one row per constraint saying what happened. Call it after fixing table ownership to add keys an earlier migration was refused.';

-- Run it now, so a database whose ownership is already correct is finished by this migration alone.
-- Output goes to the Flyway log, and a blocked key names the exact remediation.
DO $$
DECLARE r RECORD;
BEGIN
    FOR r IN SELECT * FROM ensure_account_reference_fks() LOOP
        IF r.outcome LIKE 'BLOCKED%' THEN
            RAISE WARNING '%: %', r.constraint_name, r.outcome;
        ELSIF r.outcome <> 'already present' THEN
            RAISE NOTICE '%: %', r.constraint_name, r.outcome;
        END IF;
    END LOOP;
END $$;
