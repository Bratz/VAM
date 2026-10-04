-- Let the application role own its own schema, then pick up the foreign keys V32 could not add.
--
-- V32 added 23 of 24 account-reference foreign keys. The 24th was refused: ALTER TABLE requires
-- ownership, and `payables` (with `fx_rates`) is owned by `postgres` rather than by the migrating
-- role, because those two tables were created out of band by a superuser instead of by the
-- application. 92 of the 94 public tables are owned by the application role; these two are the drift.
--
-- Ownership first, so a table created out of band stops being a hole in whatever the next migration
-- needs to alter. Only a superuser, or the current owner, can reassign ownership, so this succeeds on
-- a deployment whose Postgres was initialised with the application role as its bootstrap superuser
-- (deploy/oci/docker-compose.yml sets POSTGRES_USER: vam_user, which the official image makes a
-- superuser) and is skipped with a warning where the role lacks the privilege. A local database set up
-- by hand under a separate `postgres` superuser needs one command from an operator:
--
--     ALTER TABLE payables OWNER TO vam_user;  ALTER TABLE fx_rates OWNER TO vam_user;
--
-- after which this migration's work is already done and the constraint loop below adds the last key on
-- the next start.
DO $$
DECLARE t RECORD;
BEGIN
    FOR t IN
        SELECT tablename FROM pg_tables
        WHERE schemaname = 'public' AND tableowner <> current_user
        ORDER BY tablename
    LOOP
        BEGIN
            EXECUTE format('ALTER TABLE %I OWNER TO %I', t.tablename, current_user);
            RAISE NOTICE 'ownership of % moved to %', t.tablename, current_user;
        EXCEPTION WHEN insufficient_privilege THEN
            RAISE WARNING 'cannot take ownership of %: % is neither its owner nor a superuser. '
                          'An operator must run: ALTER TABLE % OWNER TO %',
                          t.tablename, current_user, t.tablename, current_user;
        END;
    END LOOP;
END $$;

-- The same list and the same loop as V32, repeated rather than extracted because a Flyway migration
-- that has already run cannot be edited. It is idempotent -- every constraint already present is
-- skipped -- so this adds only what V32 was refused, whichever tables that turned out to be on a given
-- database, instead of hardcoding the one case seen locally.
DO $$
DECLARE
    ref RECORD;
    fk_name TEXT;
BEGIN
    FOR ref IN
        SELECT * FROM (VALUES
            ('account_attachments',     'virtual_account_id', 'virtual_accounts'),
            ('balance_alert',           'va_id',              'virtual_accounts'),
            ('exception_transactions',  'exception_va_id',    'virtual_accounts'),
            ('exception_transactions',  'original_va_id',     'virtual_accounts'),
            ('exception_transactions',  'target_va_id',       'virtual_accounts'),
            ('hierarchy_nodes',         'virtual_account_id', 'virtual_accounts'),
            ('legal_entities',          'settlement_va_id',   'virtual_accounts'),
            ('payables',                'virtual_account_id', 'virtual_accounts'),
            ('payment_requests',        'source_va_id',       'virtual_accounts'),
            ('pool_members',            'account_id',         'virtual_accounts'),
            ('receivables',             'virtual_account_id', 'virtual_accounts'),
            ('shadow_balance_snapshot', 'shadow_va_id',       'virtual_accounts'),
            ('sweep_executions',        'source_account_id',  'virtual_accounts'),
            ('sweep_executions',        'target_account_id',  'virtual_accounts'),
            ('sweep_rule_sources',      'account_id',         'virtual_accounts'),
            ('sweep_rules',             'target_account_id',  'virtual_accounts'),
            ('va_movements',            'va_id',              'virtual_accounts'),
            ('va_movements',            'counterparty_va_id', 'virtual_accounts'),
            ('va_movements',            'behalf_of_va_id',    'virtual_accounts'),
            ('vibans',                  'virtual_account_id', 'virtual_accounts'),
            ('credit_facilities',       'physical_account_id', 'physical_accounts'),
            ('shadow_sync_log',         'physical_account_id', 'physical_accounts'),
            ('unified_programs',        'physical_account_id', 'physical_accounts'),
            ('va_movements',            'physical_account_id', 'physical_accounts')
        ) AS t(child_table, child_column, parent_table)
    LOOP
        CONTINUE WHEN NOT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = ref.child_table
              AND column_name = ref.child_column
        );
        CONTINUE WHEN NOT EXISTS (
            SELECT 1 FROM information_schema.tables
            WHERE table_schema = 'public' AND table_name = ref.parent_table
        );

        fk_name := 'fk_' || ref.child_table || '_' || ref.child_column;
        CONTINUE WHEN EXISTS (SELECT 1 FROM pg_constraint WHERE conname = fk_name);

        BEGIN
            EXECUTE format(
                'ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (%I) REFERENCES %I(id) NOT VALID',
                ref.child_table, fk_name, ref.child_column, ref.parent_table);
            RAISE NOTICE 'added % (not validated)', fk_name;
        EXCEPTION WHEN insufficient_privilege THEN
            RAISE WARNING 'still cannot add %: % is not owned by %', fk_name, ref.child_table, current_user;
            CONTINUE;
        END;

        BEGIN
            EXECUTE format('ALTER TABLE %I VALIDATE CONSTRAINT %I', ref.child_table, fk_name);
            RAISE NOTICE '  validated %', fk_name;
        EXCEPTION WHEN foreign_key_violation THEN
            RAISE NOTICE '  % left NOT VALID: existing rows reference removed accounts', fk_name;
        END;
    END LOOP;
END $$;
