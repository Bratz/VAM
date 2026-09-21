-- V24: a VIBAN that can take money must say which account the money goes to.
--
-- Routing is one hop -- VIBAN -> virtual_account_id -> credit that account -- in
-- both inbound paths (ISO 20022 pacs.008 and /vibans/route). Both check the
-- status and then look the account up with no null guard, so an ACTIVE VIBAN
-- without an account would fail as a technical error rather than a clean
-- rejection. Until now that held only because every writer happened to set the
-- account when it set ACTIVE; this makes it a rule for all of them.
--
-- virtual_account_id stays nullable for everything else: unissued pool stock
-- (RETURNED) legitimately has no account, and spent or retired numbers
-- (PAID, EXPIRED, CANCELLED) keep whichever one they had.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'chk_viban_in_use_has_account'
                     AND conrelid = 'vibans'::regclass) THEN
        ALTER TABLE vibans ADD CONSTRAINT chk_viban_in_use_has_account
            CHECK (status NOT IN ('ACTIVE', 'PARTIAL') OR virtual_account_id IS NOT NULL);
    END IF;
END
$$;
