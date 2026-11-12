-- ============================================================================
-- V57 - Drop what the IhbLoan/IhbDeposit instruments (retired in 9950ceb) left in the schema.
--
-- The entities no longer map these columns and ddl-auto never drops a column. Sweeps, POBO and COBO
-- post to the IHB current account and its IC Receivable/Payable mirror instead. Nothing writes the
-- columns now; legal_entities.total_lent_out / total_deposited / net_ihb_position still hold stale
-- figures from the old loans (MNC-TREASURY, MNC-UAE, MNC-UK, MNC-UAE-DUBAI), the rest are null.
-- v_ihb_entities selects three of them and nothing reads it; the four PL/pgSQL functions maintained
-- them from the dropped ihb_loans/ihb_deposits tables and nothing calls them. Dropping a column drops
-- its indexes. Idempotent: every statement is IF EXISTS.
-- ============================================================================
DROP VIEW IF EXISTS v_ihb_entities;
DROP FUNCTION IF EXISTS update_entity_ihb_position(uuid);
DROP FUNCTION IF EXISTS trg_update_ihb_position_on_loan_change();
DROP FUNCTION IF EXISTS trg_update_ihb_position_on_deposit_change();
DROP FUNCTION IF EXISTS enable_ihb_for_entity(uuid, numeric, boolean, boolean, numeric, numeric);

ALTER TABLE IF EXISTS legal_entities
    DROP COLUMN IF EXISTS total_lent_out,
    DROP COLUMN IF EXISTS total_deposited,
    DROP COLUMN IF EXISTS net_ihb_position;
ALTER TABLE IF EXISTS intercompany_transactions
    DROP COLUMN IF EXISTS ihb_loan_id,
    DROP COLUMN IF EXISTS ihb_deposit_id,
    DROP COLUMN IF EXISTS ihb_interest_rate;
ALTER TABLE IF EXISTS intercompany_recharges
    DROP COLUMN IF EXISTS ihb_loan_id,
    DROP COLUMN IF EXISTS creates_intercompany_loan,
    DROP COLUMN IF EXISTS ihb_transaction_id;
ALTER TABLE IF EXISTS netting_entries
    DROP COLUMN IF EXISTS ihb_transaction_id;
ALTER TABLE IF EXISTS payables
    DROP COLUMN IF EXISTS pobo_ihb_loan_id;
ALTER TABLE IF EXISTS receivables
    DROP COLUMN IF EXISTS cobo_ihb_deposit_id;
ALTER TABLE IF EXISTS sweep_executions
    DROP COLUMN IF EXISTS ihb_enabled,
    DROP COLUMN IF EXISTS ihb_deposit_id,
    DROP COLUMN IF EXISTS ihb_loan_id;
