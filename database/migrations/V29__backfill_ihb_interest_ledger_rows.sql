-- Give the two IHB interest postings the ledger rows they never got.
--
-- IhbUnifiedService moved balances in six places and wrote no va_movements row anywhere, so an
-- interest posting changed the balance and left nothing behind to explain it. The code is fixed
-- (every path now writes its row), but the balances it already moved are still unexplained.
--
-- Two accounts are affected, both IHB current accounts, each off by one day of interest, and the
-- sign of each gap matches the sign of its balance: the overdrawn participant was charged 1.10, the
-- one in credit was paid 0.01. Every other account carrying a balance reconciles to the cent.
--
-- Deliberately narrow. It targets these two accounts by number, and only acts when the gap is still
-- exactly what was diagnosed -- if the balance or the ledger has moved since, the premise is gone
-- and inventing a row would fabricate history rather than record it. Re-runnable: the correlation id
-- is the guard, so this is a no-op once applied and on any database that never had the drift.
INSERT INTO va_movements (
    id, reference_number, movement_type, corporate_id, va_id, program_id,
    amount, currency_code, balance_before, balance_after,
    correlation_id, description, status, transaction_date, value_date, channel,
    created_at, updated_at, created_by, version
)
SELECT
    gen_random_uuid(),
    'INT-BACKFILL-' || va.va_number,
    'INTEREST',
    va.corporate_id,
    va.id,
    va.program_id,
    abs(va.current_balance - led.balance_after),
    va.currency_code,
    led.balance_after,
    va.current_balance,
    'INT-BACKFILL-' || va.id,
    CASE WHEN va.current_balance < led.balance_after THEN 'Debit' ELSE 'Credit' END
        || ' interest posted before IHB movements were ledgered'
        || ' (backfilled to reconcile the statement with the balance)',
    'COMPLETED',
    va.updated_at,
    va.updated_at::date,
    'IHB_INTEREST',
    now(), now(), 'V29-backfill', 0
FROM virtual_accounts va
JOIN LATERAL (
    -- the balance the ledger actually ends on for this account
    SELECT m.balance_after
    FROM va_movements m
    WHERE m.va_id = va.id
    ORDER BY m.transaction_date DESC, m.created_at DESC
    LIMIT 1
) led ON TRUE
WHERE va.va_number IN ('IHB-MNC-UAE-AED-0001', 'IHB-MNC-UK-AED-0002')
  -- the diagnosed gap, to the cent, and nothing else
  AND va.current_balance - led.balance_after
      = CASE va.va_number WHEN 'IHB-MNC-UAE-AED-0001' THEN -1.10 ELSE 0.01 END
  AND NOT EXISTS (
      SELECT 1 FROM va_movements d WHERE d.correlation_id = 'INT-BACKFILL-' || va.id
  );
