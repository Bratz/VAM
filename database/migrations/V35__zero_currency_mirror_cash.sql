-- A currency mirror holds no cash of its own, so its cash columns go to zero.
--
-- A mirror's balance is a derived view of the same-currency transaction accounts beside it, and that
-- figure lives in mirror_balance. current_balance on a mirror is a second encoding of the same fact,
-- and it drifted, because recalculateCurrencyMirror writes mirror_balance, fx_rate, fx_rate_at and
-- balance_in_base and has never written current_balance -- so whatever an older path left there simply
-- stayed. It was invisible for as long as the two happened to agree, and obvious once a recompute
-- moved one and not the other.
--
-- Not cosmetic. Plenty of balance queries carry no category filter: sumBalanceByParent,
-- sumBalanceByParentAndCurrency and the whole-table totals among them. A mirror is a child of its
-- aggregation, so summing an aggregation's children added the mirror's cash to the very transaction
-- accounts it mirrors. Even a mirror whose current_balance agreed with its mirror_balance was double
-- counted that way, so agreement was never evidence of correctness.
--
-- Nothing reads the value being cleared: the sibling sum filters to operational categories
-- (TRANSACTION, COLLECTION, DISBURSEMENT), nested propagation reads mirror_balance, and aggregation
-- nodes sum balance_in_base. mirror_balance, balance_in_base and fx_rate are untouched here, so every
-- figure anything actually displays is unchanged.
--
-- PHYSICAL_MIRROR is deliberately excluded. Those are shadows of real bank accounts and hold real
-- balances -- all of them are leaves, and debiting one for a fee is correct -- so only CURRENCY_MIRROR
-- is the derived duplicate.
--
-- The code now pins these to zero on every aggregation pass, so this migration is the one-off repair
-- for databases whose scheduler is parked and will not reach a recompute soon. Re-runnable: it only
-- touches rows that are not already zero.
UPDATE virtual_accounts
SET current_balance   = 0,
    available_balance = 0
WHERE account_category = 'CURRENCY_MIRROR'
  AND (current_balance <> 0 OR available_balance <> 0);

-- A hold on a derived view would be meaningless, and would break available = current - held the
-- moment the two above are zeroed. None exist today; this keeps the invariant true if one ever does.
UPDATE virtual_accounts
SET held_balance = 0
WHERE account_category = 'CURRENCY_MIRROR'
  AND COALESCE(held_balance, 0) <> 0;
