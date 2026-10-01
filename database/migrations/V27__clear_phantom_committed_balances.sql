-- V27: clear committed_outflow / committed_inflow, which no code reads or writes.
--
-- These belonged to the IhbLoan/IhbDeposit instruments retired in 9950ceb. That mechanism never
-- released what it committed: SweepService called commitOutflow() on every execution, and no code
-- path anywhere -- then or now -- ever called releaseCommittedOutflow() or settleOutflow(). So the
-- figure only ever grew, one sweep at a time, reaching 27,898,819,768.53 across 10 accounts with
-- 7,522,585,000.00 on the worst. commitOutflow() also subtracted from available_balance on each
-- pass, which is part of why IHB participants show an available balance far below their cash.
--
-- This is not money and never was: it is a reservation counter for an instrument that no longer
-- exists, exposed in no DTO and read by nothing. Zeroing it moves no balance and writes no ledger
-- row; available_balance is deliberately left alone, since it is now maintained by credit()/debit()
-- and the correct value for the historical rows cannot be reconstructed from what remains.
--
-- Guarded on > 0 so it is idempotent and touches only rows that actually carry residue.
UPDATE virtual_accounts
   SET committed_outflow = 0,
       committed_inflow  = 0,
       updated_at        = NOW()
 WHERE COALESCE(committed_outflow, 0) <> 0
    OR COALESCE(committed_inflow, 0)  <> 0;

DO $$
DECLARE
    remaining INTEGER;
BEGIN
    SELECT COUNT(*) INTO remaining
      FROM virtual_accounts
     WHERE COALESCE(committed_outflow, 0) <> 0 OR COALESCE(committed_inflow, 0) <> 0;
    RAISE NOTICE 'V27: % account(s) still carry a committed balance (expected 0)', remaining;
END
$$;
