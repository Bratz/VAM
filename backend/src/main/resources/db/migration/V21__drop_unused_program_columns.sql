-- V21: drop fourteen unified_programs columns that nothing read.
--
-- Each was written by createProgram, copied by cloneProgram, settable by
-- updateProgram and returned by toProgramResponse -- and consumed by nothing
-- else. Grepping every getter across the backend, excluding the entity, the DTO
-- and those four CRUD sites, returned zero consumers for all fourteen.
--
-- Two groups. Seven were inert on both sides: min_withdrawal, max_withdrawal,
-- kyc_validity_days, allow_bulk_operations and inactive_expiry_days existed in
-- the create form's state but were never rendered as inputs and never sent in
-- the payload, and brand_name/brand_logo_url were not even in the form --
-- settable only by raw API call, readable by nobody.
--
-- The other seven were visible: auto_reconciliation, settlement_frequency,
-- settlement_time, min_balance_threshold, balance_aggregation_interval_minutes,
-- realtime_balance_propagation and allow_payment had form inputs, detail-modal
-- rows and badges. A user could configure them, save them, and see them read
-- back -- and nothing anywhere acted on the value. A settlement frequency that
-- schedules no settlement is worse than no field: it looks like configuration.
-- Their inputs and displays are removed in the same change.
--
-- Nothing is carried anywhere first, because nothing derived from these values
-- in the first place. The data is dropped with the columns.

ALTER TABLE unified_programs
    DROP COLUMN IF EXISTS auto_reconciliation,
    DROP COLUMN IF EXISTS settlement_frequency,
    DROP COLUMN IF EXISTS settlement_time,
    DROP COLUMN IF EXISTS min_balance_threshold,
    DROP COLUMN IF EXISTS balance_aggregation_interval_minutes,
    DROP COLUMN IF EXISTS realtime_balance_propagation,
    DROP COLUMN IF EXISTS min_withdrawal,
    DROP COLUMN IF EXISTS max_withdrawal,
    DROP COLUMN IF EXISTS kyc_validity_days,
    DROP COLUMN IF EXISTS allow_payment,
    DROP COLUMN IF EXISTS allow_bulk_operations,
    DROP COLUMN IF EXISTS inactive_expiry_days,
    DROP COLUMN IF EXISTS brand_name,
    DROP COLUMN IF EXISTS brand_logo_url;
