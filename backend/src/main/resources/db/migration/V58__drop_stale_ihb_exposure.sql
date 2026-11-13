-- ============================================================================
-- V58 - Drop the stored IHB exposure of a legal entity.
--
-- legal_entities.ihb_current_exposure / ihb_available_limit were maintained by the retired IHB loans and have not
-- been written since (9950ceb); one entity still carried a stale figure. Every screen and check now derives the
-- exposure from the money (IhbExposure: the debit balance of the entity's IHB current accounts). The view and
-- functions that read these columns went in V57. Idempotent.
-- ============================================================================
ALTER TABLE IF EXISTS legal_entities
    DROP COLUMN IF EXISTS ihb_current_exposure,
    DROP COLUMN IF EXISTS ihb_available_limit;
