-- Make a dangling account reference impossible to create.
--
-- Nothing has ever stopped one. Every column below holds an account id with no constraint behind it,
-- so deleting an account silently left pointers in whatever still referenced it. That is how an IHB
-- rebuild in January 2026 left four active legal entities configured with settlement accounts that no
-- longer existed, a sweep rule aimed at a deleted target, and ~6,000 history rows pointing at five
-- removed accounts. V31 cleared the configuration; this stops it recurring.
--
-- Added NOT VALID, which is the point rather than a shortcut. A NOT VALID foreign key is fully
-- enforced for inserts and updates and for deletes of the referenced row, but existing rows are not
-- examined. That is exactly the split this schema needs: history keeps its dangling ids, which are
-- facts about the past and in many cases the only remaining trace of a deleted account, while nothing
-- new can be written that points at an account which is not there. Verified all four behaviours before
-- writing this -- a new row with a bad reference is rejected, the same row with a good one is
-- accepted, deleting a referenced account is blocked, and the 4,483 pre-existing orphans in
-- sweep_executions survive untouched.
--
-- Consequence worth knowing: an account that anything references can no longer be deleted. The only
-- delete path in the codebase, DELETE /api/v1/settlement-vas/{vaId}, today checks for a zero balance
-- and nothing else -- it will now fail at the database for any account carrying movements, hierarchy
-- nodes or sweep history. That is the protection working, and it is how the dangling pointers above
-- came to exist, but the endpoint reports it as a server error rather than a reason.
--
-- Re-runnable: each constraint is skipped if already present, and validation is attempted separately
-- so a table whose history still carries orphans stays NOT VALID instead of failing the migration.
DO $$
DECLARE
    ref RECORD;
    fk_name TEXT;
BEGIN
    FOR ref IN
        SELECT * FROM (VALUES
            -- references to virtual_accounts
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
            -- references to physical_accounts
            ('credit_facilities',       'physical_account_id', 'physical_accounts'),
            ('shadow_sync_log',         'physical_account_id', 'physical_accounts'),
            ('unified_programs',        'physical_account_id', 'physical_accounts'),
            ('va_movements',            'physical_account_id', 'physical_accounts')
        ) AS t(child_table, child_column, parent_table)
    LOOP
        -- Skip anything this database does not actually have: the schema is built by ddl-auto, so a
        -- table or column can legitimately be absent on an older instance.
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

        -- Ownership is not uniform: payables and fx_rates are owned by postgres rather than by the
        -- application role, and ALTER TABLE requires ownership. Skip with a notice instead of failing
        -- the migration, so one table's ownership cannot block protecting the other twenty-three, and
        -- so this behaves the same on an environment whose ownership differs.
        BEGIN
            EXECUTE format(
                'ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (%I) REFERENCES %I(id) NOT VALID',
                ref.child_table, fk_name, ref.child_column, ref.parent_table);
            RAISE NOTICE 'added % (not validated)', fk_name;
        EXCEPTION WHEN insufficient_privilege THEN
            RAISE WARNING 'skipped %: % is not owned by the migrating role, so no constraint could be '
                          'added -- this column stays unprotected', fk_name, ref.child_table;
            CONTINUE;
        END;

        -- Promote to validated where the existing rows already satisfy it, so the planner can trust
        -- it and a later reader can tell clean columns from ones carrying historical orphans. A table
        -- that still has orphans keeps the constraint NOT VALID rather than failing the migration.
        BEGIN
            EXECUTE format('ALTER TABLE %I VALIDATE CONSTRAINT %I', ref.child_table, fk_name);
            RAISE NOTICE '  validated %', fk_name;
        EXCEPTION WHEN foreign_key_violation THEN
            RAISE NOTICE '  % left NOT VALID: existing rows reference removed accounts', fk_name;
        END;
    END LOOP;
END $$;
