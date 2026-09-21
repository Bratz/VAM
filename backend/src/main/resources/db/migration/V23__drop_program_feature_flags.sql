-- V23: drop the last six program feature flags.
--
-- escrow_enabled, ihb_enabled, loyalty_enabled, gift_card_enabled,
-- corporate_card_enabled and mobile_money_enabled decided nothing. No backend
-- code read them outside create/update/clone and the response mapper, and no
-- UI flow was gated on them -- they were checkboxes, badges and the Programs
-- page's filter tiles, i.e. the same decorative classification program_type was
-- (V20), spelled as booleans.
--
-- ihb_enabled in particular was a decoy: every IHB decision reads
-- legal_entities.ihb_enabled, which is untouched.
ALTER TABLE unified_programs
    DROP COLUMN IF EXISTS escrow_enabled,
    DROP COLUMN IF EXISTS ihb_enabled,
    DROP COLUMN IF EXISTS loyalty_enabled,
    DROP COLUMN IF EXISTS gift_card_enabled,
    DROP COLUMN IF EXISTS corporate_card_enabled,
    DROP COLUMN IF EXISTS mobile_money_enabled;
