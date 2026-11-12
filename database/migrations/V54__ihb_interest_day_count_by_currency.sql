-- V54: IHB interest configs carry the day count of their currency.
-- getOrCreateIhbInterestConfig created every IHB config with a hard-coded 'ACT/360' (also the column default),
-- so AED/GBP/... IHB accounts accrued on a 360-day year. An account's configured day count wins over the currency
-- default (IhbUnifiedService), so the existing rows need the currency's convention. Only IHB configs (those IHB current accounts use, or
-- named 'IHB Rates - <entity>' as the code creates them) still on the untouched default are changed; a deliberate ACT/365 or ACT/ACT is left alone. Same list as
-- InterestConfigurationService.defaultDayCount. Safe to re-run; no-op where the table is absent.
DO $$
BEGIN
  IF to_regclass('public.interest_configurations') IS NOT NULL THEN
    UPDATE interest_configurations
       SET day_count_convention = 'ACT/365'
     WHERE (id IN (SELECT internal_interest_config_id FROM virtual_accounts
                    WHERE mirror_account_type = 'IHB_CURRENT' AND internal_interest_config_id IS NOT NULL)
            OR config_name LIKE 'IHB Rates - %')
       AND day_count_convention = 'ACT/360'
       AND currency_code IN ('GBP','AED','SAR','QAR','KWD','BHD','OMR','SGD','INR','HKD','AUD','NZD','ZAR');
  END IF;
END $$;
