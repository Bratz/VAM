-- ============================================================================
-- V59 - Take back the debit interest MNC Dubai accrued on a sweep that was reversed (IHB functional review C5).
--
-- On 15 Sep a ZERO_BALANCE sweep (SWP17894952000804047) moved 1,000,000.96 out of IHB-MNC-UAE-DUBAI-AED-0001, which
-- held 0.96, leaving it at -1,000,000.00. The daily accrual runs of 16-28 Sep (the last run on vam-five) each booked a
-- day's debit interest on that overdraft: 1,000,000.00 x 5.5% / 360 = 152.777778, stored to 4 places = 152.7778, 13 days
-- = 1,986.1114 (the accrual still used 360 days then; V54 moved AED to 365 later). The reversal on 1 Oct
-- (REV17908481197331111) put the principal back but not the interest, so it would post at the next interest run.
-- OverdrawingSweepReversalService now takes the interest back with the principal (IhbUnifiedService.takeBackAccruedInterest).
--
-- Correct accrual for those 13 days is the genuine balance's: 0.96 in credit at 4.5% / 360 = 0.000120 a day, stored
-- 0.0001, 13 days = 0.0013 credit interest; no debit interest. So: accrued debit 1,986.1114 -> 0, accrued credit
-- 0 -> 0.0013.
--
-- Guarded on the account's exact current state (balance, both accruals, last accrual and posting dates, and the
-- reversal row): a no-op anywhere it differs, e.g. locally, or if more accrual runs have happened since. Idempotent.
-- No other account carries interest accrued on a reversed movement (the other two IHB accounts never had one).
-- ============================================================================
DO $$
BEGIN
    IF to_regclass('virtual_accounts') IS NULL OR to_regclass('va_movements') IS NULL THEN
        RETURN; -- a fresh database: Flyway runs before Hibernate creates the tables
    END IF;
    UPDATE virtual_accounts va
       SET accrued_debit_interest  = 0,
           accrued_credit_interest = 0.0013,
           updated_at              = now()
     WHERE va.id = 'eeea067c-23c3-4741-aaf0-9194f1d3444d'
       AND va.va_number = 'IHB-MNC-UAE-DUBAI-AED-0001'
       AND va.ihb_participant
       AND va.current_balance = 14.48
       AND va.accrued_debit_interest = 1986.1114
       AND va.accrued_credit_interest = 0
       AND va.last_interest_calc_date = DATE '2026-09-28'
       AND va.last_interest_posting_date = DATE '2026-09-15'
       AND EXISTS (SELECT 1 FROM va_movements m
                    WHERE m.va_id = va.id
                      AND m.reference_number = 'REV17908481197331111'
                      AND m.movement_type = 'REVERSAL'
                      AND m.amount = 1000000.96
                      AND m.balance_after = 0.96);
END $$;
