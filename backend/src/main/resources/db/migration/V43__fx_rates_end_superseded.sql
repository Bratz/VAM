-- Rates replaced by a newer one were left current beside it: FxRateService only ended old rates when the new
-- rate already had an id, which a new rate never has, and seeded rates never went through it. So several pairs
-- had two or more open MID rates (EUR/AED 4.01 and 4.0125, EUR/USD 1.0905 and 1.0926, ...) and "latest rate"
-- picked one of them by date alone. Keep the newest open rate per pair, type and scope (one corporate, or global);
-- end the others now. They stay active, so a fixing can still be read for its date.
UPDATE fx_rates f
SET effective_to = now(), updated_at = now()
FROM (
    SELECT id, row_number() OVER (
               PARTITION BY from_currency, to_currency, rate_type, COALESCE(corporate_id, '00000000-0000-0000-0000-000000000000'::uuid)
               ORDER BY rate_date DESC, effective_from DESC NULLS LAST, created_at DESC) AS rn
    FROM fx_rates
    WHERE is_active AND effective_to IS NULL
) ranked
WHERE f.id = ranked.id AND ranked.rn > 1;
