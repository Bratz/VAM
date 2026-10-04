-- Give PA-GBP-002 a real BIC, and its shadow the same.
--
-- physical_accounts.bank_code held the string 'HSBC' on this one account -- a bank name where every
-- other row carries an 11-character BIC. It matters because bank_code is what the home-bank check
-- compares: ShadowAccountService copies it into virtual_accounts.bank_swift at creation, and
-- HomeBankProperties.matches() is an exact, case-insensitive equality against VAM_HOME_BANK_BIC. A
-- value that is not a BIC can therefore never match any configuration, so SHADOW-GBP-GBP-002 could
-- not be picked to back a program under any market profile -- including the UK one, where HSBC was
-- precisely the home bank it should have matched.
--
-- Isolated, not systemic: of 28 physical accounts this is the only bank_code that fails the BIC shape
-- (6 letters, 2 alphanumerics, optional 3-character branch). The value is unambiguous because the
-- data already contains HBUKGB4BXXX for 'HSBC UK Bank plc' on account 34567803, and this is a GBP
-- account owned by MNC-UK.
--
-- The shadow is updated too rather than left to re-derive, because the copy happens once at creation
-- and nothing refreshes bank_swift afterwards.
--
-- Guarded on the wrong value, so it is re-runnable and a no-op on any database already correct.
UPDATE physical_accounts
SET bank_code = 'HBUKGB4BXXX'
WHERE account_number = 'PA-GBP-002'
  AND bank_code = 'HSBC';

UPDATE virtual_accounts
SET bank_swift = 'HBUKGB4BXXX'
WHERE account_category = 'PHYSICAL_MIRROR'
  AND bank_swift = 'HSBC'
  AND linked_physical_account_id IN (
      SELECT id FROM physical_accounts WHERE account_number = 'PA-GBP-002'
  );
