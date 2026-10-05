-- Settlement stops being a kind of account and becomes a mark on a transaction account.
--
-- While SETTLEMENT was an accountCategory value, a settlement account was not a transaction account,
-- so the currency mirror -- which sums same-currency *transaction* siblings -- skipped it. In FINAL IHB
-- alone that left 12,024.52 of real balances outside every roll-up, and the same held wherever a
-- settlement account carried money. The reference model is explicit that a settlement account *is* a
-- transaction account carrying a mark; this restores that split: the category says what the account is,
-- the mark says what it additionally does.
--
-- Not a repeat of specialType, which was deleted for duplicating the category. That field answered the
-- same question twice and the two drifted apart. This answers a different one, and neither value is
-- derivable from the other -- the test in tasks/lessons.md for when a property deserves its own field.
--
-- This migration defines a column that an @Entity also maps, which the project normally leaves to
-- hibernate.ddl-auto. Deliberate, and the one case where it is necessary: Flyway runs before
-- ddl-auto, so a data move into a brand-new entity column cannot wait for Hibernate to create it. IF
-- NOT EXISTS plus a type matching exactly what ddl-auto generates for `@Column(name="settlement_mark")
-- private Boolean` -- nullable boolean, no database default -- keeps it harmless whichever runs first.
-- The alternative, deferring the move to a runner, would make the model's correctness depend on
-- something else having happened first, which is the trap recorded in lessons.md.
--
-- Balances are not touched. Nothing here reads or writes a balance column, which is the invariant to
-- check either side of this: the same accounts holding the same money, described differently.
ALTER TABLE virtual_accounts ADD COLUMN IF NOT EXISTS settlement_mark boolean;

-- Existing rows: false rather than NULL, so the mark is a straight answer everywhere and
-- Boolean.TRUE.equals() is never deciding between two kinds of "no".
UPDATE virtual_accounts SET settlement_mark = false WHERE settlement_mark IS NULL;

-- The move itself. Every account that was a SETTLEMENT becomes a marked TRANSACTION.
UPDATE virtual_accounts
SET account_category = 'TRANSACTION',
    settlement_mark  = true
WHERE account_category = 'SETTLEMENT';

-- special_type is already derived rather than stored (getSpecialType reads the mark first), but stale
-- values linger in the column from before that change. Clear the ones that claimed SETTLEMENT so a
-- reader of the raw table is not told two different stories by two columns.
UPDATE virtual_accounts
SET special_type = NULL
WHERE special_type = 'SETTLEMENT'
  AND settlement_mark = true;
