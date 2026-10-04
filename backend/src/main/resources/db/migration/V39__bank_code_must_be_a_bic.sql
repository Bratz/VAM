-- Stop a bank name being written into the field read as a BIC.
--
-- physical_accounts.bank_code is the home-bank check: ShadowAccountService copies it into
-- virtual_accounts.bank_swift when a shadow is created, and HomeBankProperties.matches() is an exact
-- case-insensitive equality against VAM_HOME_BANK_BIC. A value that is not a BIC can never match any
-- configuration, so the account becomes silently ineligible to back a program instead of failing
-- visibly. V38 repaired the one row in that state -- PA-GBP-002 held the string 'HSBC' and was unusable
-- under every market profile, including the UK one where HSBC was the home bank it should have matched.
-- Nothing stopped it being written, which is what this fixes.
--
-- The entity now carries the same rule as a @Pattern, which gives a readable error on an application
-- save. This constraint is the durable half: it also covers the hand-run SQL and migrations this
-- repository uses freely, which bean validation never sees.
--
-- NULL passes deliberately: an account whose BIC is not known yet is simply never the home bank, and
-- the column is nullable.
--
-- Added NOT VALID then validated separately, the same way as the account-reference foreign keys. All 28
-- rows satisfy it today so validation should succeed, but a database still holding a bad row keeps the
-- constraint unvalidated -- protected going forward -- rather than failing the migration and blocking
-- startup.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_physical_accounts_bank_code_bic') THEN
        RETURN;
    END IF;

    BEGIN
        ALTER TABLE physical_accounts
            ADD CONSTRAINT chk_physical_accounts_bank_code_bic
            CHECK (bank_code IS NULL OR bank_code ~ '^[A-Za-z]{6}[A-Za-z0-9]{2}([A-Za-z0-9]{3})?$')
            NOT VALID;
    EXCEPTION WHEN insufficient_privilege THEN
        RAISE WARNING 'chk_physical_accounts_bank_code_bic not added: physical_accounts is not owned '
                      'by %. An operator must run: ALTER TABLE physical_accounts OWNER TO %',
                      current_user, current_user;
        RETURN;
    END;

    BEGIN
        ALTER TABLE physical_accounts VALIDATE CONSTRAINT chk_physical_accounts_bank_code_bic;
        RAISE NOTICE 'chk_physical_accounts_bank_code_bic validated: every bank_code is a BIC';
    EXCEPTION WHEN check_violation THEN
        RAISE WARNING 'chk_physical_accounts_bank_code_bic left NOT VALID: some bank_code is not a '
                      'BIC. New and updated rows are still checked; fix the existing rows and run '
                      'ALTER TABLE physical_accounts VALIDATE CONSTRAINT '
                      'chk_physical_accounts_bank_code_bic';
    END;
END $$;
