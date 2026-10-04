-- Remove live configuration that points at accounts which no longer exist.
--
-- V31 cleared the dangling configuration it found and deliberately left five rows in four tables for a
-- decision. Inspecting them splits the five by kind rather than by table, and the two kinds need
-- opposite treatment:
--
--   Live configuration -- a setting that is supposed to act, and cannot. Cleared here.
--     * two balance_alert rows, both active, BELOW 50,000 AED on va_id 58716d6f (recorded on the rows
--       themselves as VA-DUBAI-001, "Dubai Collections Account"), created 2026-05-12. That account is
--       gone, so neither alert can ever fire. va_id is NOT NULL, so the orphan cannot be nulled away
--       and the row has to go; the account number and name are denormalised onto each row, so what was
--       configured is preserved in this comment rather than lost silently.
--     * one credit_facilities row, status ACTIVE, whose physical_account_id references a deleted
--       physical account. Nulled rather than deleted -- the facility is real, only the pointer is not.
--
--   History -- a record of something that happened, where the dangling id is a fact about the past.
--   Left exactly as it is, the same rule applied to the ~6,000 sweep_executions and va_movements rows:
--     * payable PAY-1768969041688-1111, status PAID, net 13.20 AED, outstanding 0.00. A settled
--       document. Deleting it would destroy a financial record and nulling its account would erase
--       which account settled it.
--     * the payment_requests row for the same 13.20 AED on the same day, which is almost certainly the
--       request that paid it. Its status still reads SUBMITTED although the payable is PAID -- a stale
--       status worth knowing about, but a separate matter from a dangling reference.
--
-- Neither deleted account ever posted: both have zero rows in va_movements, so nothing is being
-- orphaned from a ledger by this.
--
-- With those two tables clean, their foreign keys are promoted from NOT VALID to validated, which is
-- the proof the cleanup worked -- validation fails if a single orphan remains. Guarded so a database in
-- a different state is left NOT VALID instead of failing the migration. Re-runnable throughout.
DELETE FROM balance_alert t
WHERE t.va_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM virtual_accounts v WHERE v.id = t.va_id);

UPDATE credit_facilities t
SET physical_account_id = NULL
WHERE t.physical_account_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM physical_accounts p WHERE p.id = t.physical_account_id);

DO $$
DECLARE c RECORD;
BEGIN
    FOR c IN
        SELECT * FROM (VALUES
            ('balance_alert',     'fk_balance_alert_va_id'),
            ('credit_facilities', 'fk_credit_facilities_physical_account_id')
        ) AS t(tbl, fk)
    LOOP
        CONTINUE WHEN NOT EXISTS (
            SELECT 1 FROM pg_constraint WHERE conname = c.fk AND NOT convalidated
        );
        BEGIN
            EXECUTE format('ALTER TABLE %I VALIDATE CONSTRAINT %I', c.tbl, c.fk);
            RAISE NOTICE '% validated: no orphans remain', c.fk;
        EXCEPTION
            WHEN foreign_key_violation THEN
                RAISE WARNING '% still has orphan rows, left NOT VALID', c.fk;
            WHEN insufficient_privilege THEN
                RAISE WARNING '% not validated: % is not owned by %', c.fk, c.tbl, current_user;
        END;
    END LOOP;
END $$;
