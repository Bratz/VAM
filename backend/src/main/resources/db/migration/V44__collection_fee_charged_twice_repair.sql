-- Inbound collections charged their fee twice until 2026-11-09 (commit 0eb5fcc). processCollection credited
-- the target account the NET amount (gross - fee) and then posted the fee leg, which debited the fee again:
-- 4,850 in with a 5 fee left the account 4,840 better off instead of 4,845. The fee leg booked the fee income
-- once, correctly; the second deduction went nowhere.
--
-- Affected: old-style leg-4 credits (their processing_notes still say "Net after fee") with a fee whose
-- FEE leg was posted on the same account under the same correlation id. postSingleFee swallowed its own
-- failures, so a collection whose fee leg never posted was charged once and is left alone.
--
-- Each one gets an ADJUSTMENT credit of its fee, referenced FEEFIX-<collection ref>, and the account's
-- current and available balances go up by the same. Re-running finds nothing: the NOT EXISTS skips any
-- collection that already has its FEEFIX row.

WITH affected AS (
    SELECT c.id, c.va_id, c.corporate_id, c.legal_entity_id, c.program_id, c.currency_code, c.fee_amount,
           c.correlation_id, c.reference_number, c.created_at,
           SUM(c.fee_amount) OVER (PARTITION BY c.va_id ORDER BY c.created_at, c.id) AS running
    FROM va_movements c
    WHERE c.processing_notes LIKE 'Inbound collection - leg 4 of 4 (final credit). Net after fee:%'
      AND c.fee_amount > 0
      AND EXISTS (SELECT 1 FROM va_movements f
                  WHERE f.correlation_id = c.correlation_id AND f.va_id = c.va_id AND f.movement_type = 'FEE')
      AND NOT EXISTS (SELECT 1 FROM va_movements x WHERE x.reference_number = 'FEEFIX-' || c.reference_number)
),
credited AS (
    INSERT INTO va_movements (id, va_id, corporate_id, legal_entity_id, program_id, amount, currency_code,
                              movement_type, transaction_category, status, transaction_date, value_date,
                              balance_before, balance_after, reference_number, correlation_id, channel,
                              description, processing_notes, created_at, updated_at, version)
    SELECT gen_random_uuid(), a.va_id, a.corporate_id, a.legal_entity_id, a.program_id, a.fee_amount, a.currency_code,
           'ADJUSTMENT', 'INTERNAL', 'COMPLETED', now(), current_date,
           v.current_balance + a.running - a.fee_amount, v.current_balance + a.running,
           'FEEFIX-' || a.reference_number, a.correlation_id, 'SYSTEM',
           'Collection fee refund: charged twice on ' || a.reference_number,
           'V44: the collection fee was deducted from the credit and again by the fee leg; this returns the second deduction',
           now(), now(), 0
    FROM affected a
    JOIN virtual_accounts v ON v.id = a.va_id
    RETURNING va_id, amount
)
UPDATE virtual_accounts v
SET current_balance = v.current_balance + t.total,
    available_balance = v.available_balance + t.total
FROM (SELECT va_id, SUM(amount) AS total FROM credited GROUP BY va_id) t
WHERE v.id = t.va_id;
