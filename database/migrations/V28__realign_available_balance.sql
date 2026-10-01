-- V28: restore available_balance = current_balance - held_balance.
--
-- That is exactly the invariant the entity's four mutators maintain, and the only four that touch
-- available_balance now that V27 retired the committed-balance machinery:
--   credit(a)      current += a, available += a
--   debit(a)       current -= a, available -= a
--   hold(a)        held    += a, available -= a
--   releaseHold(a) held    -= a, available += a
-- Each preserves it, so any row that breaks it was written by something outside those paths.
--
-- 211 of 216 rows already satisfy it. The five that do not are precisely the accounts the two
-- fixed defects touched: two IHB participants whose available carried the borrowing facility
-- (IhbUnifiedService used to persist currentBalance + effectiveCreditLimit into this column), and
-- three whose available was ground down by sweep commitOutflow() calls that were never released.
--
-- Safe because a hold recorded only in available_balance cannot exist: hold() always increments
-- held_balance in the same call, so there is no legitimate reason for available to sit below
-- current without held explaining it. Nothing here moves money or writes a ledger row -- this
-- column is a derived view of cash minus reservations, not a balance of its own.
--
-- Guarded on the violation itself, so it is idempotent and touches nothing already correct.
UPDATE virtual_accounts
   SET available_balance = current_balance - COALESCE(held_balance, 0),
       updated_at        = NOW()
 WHERE available_balance IS DISTINCT FROM current_balance - COALESCE(held_balance, 0);

DO $$
DECLARE
    violating INTEGER;
BEGIN
    SELECT COUNT(*) INTO violating
      FROM virtual_accounts
     WHERE available_balance IS DISTINCT FROM current_balance - COALESCE(held_balance, 0);
    RAISE NOTICE 'V28: % account(s) still break available = current - held (expected 0)', violating;
END
$$;
