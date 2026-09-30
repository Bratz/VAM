-- V26: three VAs named SETTLEMENT-EUR-* are categorised TRANSACTION, so no payment can reach them.
--
-- They predate the hierarchy creation path setting accountCategory, and were marked only on the
-- old special_type column. That mattered because the two markers were read by different code:
-- the Settlement VAs panel filtered on special_type, the payment resolver filters on
-- account_category. So these three showed as configured on screen while
-- SettlementVaResolverService could never find them, and every EUR collection in those three
-- programs parked to the exception VA instead of posting.
--
-- special_type is derived from account_category now, so the drift cannot recur -- but that also
-- means these rows currently read as REGULAR everywhere. This sets the field that is actually
-- read, which is the one thing that makes them work.
--
-- Verified against the live data before writing this: with the category corrected, the three
-- M-EUR-* currency mirrors in these programs resolve, taking the unresolved-account count from
-- 50 to 44. The remaining gaps are other currencies missing their own settlement VA and are
-- deliberately not touched here.
--
-- Matched by va_number rather than id so this is reproducible across environments, and guarded
-- on the wrong category so it is idempotent and cannot disturb a row that is already correct or
-- has since been fixed by hand.
UPDATE virtual_accounts
   SET account_category = 'SETTLEMENT',
       updated_at       = NOW()
 WHERE va_number IN (
           'SETTLEMENT-EUR-TEST-WAL-L2-5962',
           'SETTLEMENT-EUR-TEST-IHB-L2-5710',
           'SETTLEMENT-EUR-TEST-MUL-L2-6328'
       )
   AND account_category = 'TRANSACTION';

-- Report rather than assert: these rows exist in the shared demo instance but not necessarily in
-- a fresh database seeded from the dump, and a migration that failed there would block startup
-- for a repair that simply has nothing to do.
DO $$
DECLARE
    fixed INTEGER;
BEGIN
    SELECT COUNT(*) INTO fixed
      FROM virtual_accounts
     WHERE va_number IN (
               'SETTLEMENT-EUR-TEST-WAL-L2-5962',
               'SETTLEMENT-EUR-TEST-IHB-L2-5710',
               'SETTLEMENT-EUR-TEST-MUL-L2-6328'
           )
       AND account_category = 'SETTLEMENT';
    RAISE NOTICE 'V26: % of 3 SETTLEMENT-EUR-* VAs now carry account_category = SETTLEMENT', fixed;
END
$$;
