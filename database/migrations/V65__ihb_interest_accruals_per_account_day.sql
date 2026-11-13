-- ============================================================================
-- V65 - Replace the unused per-entity IHB interest accrual table (IHB/Intercompany pages spec R15, plan 4.3).
--
-- The old ihb_interest_accruals (entity_id -> ihb_entities, period_start/period_end, average_balance) never had a
-- writer: it is empty locally and in the vam-five dump (13 Nov). The IHB interest run now records one row per IHB
-- current account and day (va_id, accrual_date, side, balance, rate, day_count, amount), mapped by the
-- IhbInterestAccrual entity, so hibernate ddl-auto creates the new table after this drops the old one (its NOT NULL
-- entity_id / period columns would refuse every new row). Refuses rather than lose rows if the old table has any.
-- Only the old shape is dropped (it has entity_id; the new one does not), so a second run is a no-op.
-- ============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema() AND table_name = 'ihb_interest_accruals' AND column_name = 'entity_id') THEN
        IF EXISTS (SELECT 1 FROM ihb_interest_accruals) THEN
            RAISE EXCEPTION 'V65: ihb_interest_accruals (old per-entity shape) has rows; review them before dropping it';
        END IF;
        DROP TABLE ihb_interest_accruals;
    END IF;
END $$;
