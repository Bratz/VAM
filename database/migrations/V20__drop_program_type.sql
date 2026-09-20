-- V20: retire unified_programs.program_type.
--
-- The enum had 11 values and two of them branched on anything: LOYALTY set a
-- VA's valueType to POINTS, and one IHB operation refused programs of another
-- type. Everything else was a counter on the stats tile, a list filter, or a
-- label. Meanwhile the feature flags encoded the same fact, and both layers had
-- already given up on deciding which was authoritative -- Program.isWalletProgram()
-- read "programType == WALLET || walletEnabled", and the frontend repeated that
-- same OR in six places.
--
-- The flags win. The type is dropped, and the LOYALTY behaviour moves to
-- VirtualAccount.valueType, inherited from the parent account the way currency
-- and owning entity already are.
--
-- The mapping below is the frontend's own PROGRAM_TYPE_FEATURE_MAP
-- (pages/programs/shared.tsx:366), which the create form already applies when a
-- type is picked -- so for anything created through the UI this is a no-op and
-- only older or API-created rows move.
--
-- One deliberate departure from that map: it also sets hierarchyEnabled for
-- COLLECTION, PAYABLES, RECEIVABLES and IHB. That is a sensible *form default*,
-- not a fact about the type, and hierarchyEnabled is load-bearing (it drives
-- auto-initialisation and several read paths). Backfilling it would switch on
-- hierarchy for existing programs whose owners left it off, so it is left alone.
-- COLLECTION, PAYABLES and RECEIVABLES therefore end with no distinguishing
-- flag, which is correct: they are the base case, a plain virtual-account
-- program with no extra product attached.

UPDATE unified_programs SET viban_enabled          = true WHERE program_type = 'VIBAN'          AND viban_enabled          IS DISTINCT FROM true;
UPDATE unified_programs SET escrow_enabled         = true WHERE program_type = 'ESCROW'         AND escrow_enabled         IS DISTINCT FROM true;
UPDATE unified_programs SET ihb_enabled            = true WHERE program_type = 'IHB'            AND ihb_enabled            IS DISTINCT FROM true;
UPDATE unified_programs SET loyalty_enabled        = true WHERE program_type = 'LOYALTY'        AND loyalty_enabled        IS DISTINCT FROM true;
UPDATE unified_programs SET gift_card_enabled      = true WHERE program_type = 'GIFT_CARD'      AND gift_card_enabled      IS DISTINCT FROM true;
UPDATE unified_programs SET corporate_card_enabled = true WHERE program_type = 'CORPORATE_CARD' AND corporate_card_enabled IS DISTINCT FROM true;
UPDATE unified_programs SET mobile_money_enabled   = true WHERE program_type = 'MOBILE_MONEY'   AND mobile_money_enabled   IS DISTINCT FROM true;

-- Every card/points/mobile-money program is a wallet program, per the same map.
UPDATE unified_programs
SET wallet_enabled = true
WHERE program_type IN ('WALLET', 'LOYALTY', 'GIFT_CARD', 'CORPORATE_CARD', 'MOBILE_MONEY')
  AND wallet_enabled IS DISTINCT FROM true;

-- Points accounts were derived from the program's type at creation time. Carry
-- that onto the accounts themselves before the type is gone, so existing loyalty
-- balances keep their value type once nothing can re-derive it.
UPDATE virtual_accounts va
SET value_type = 'POINTS'
FROM unified_programs p
WHERE p.id = va.program_id
  AND p.program_type = 'LOYALTY'
  AND va.value_type IS DISTINCT FROM 'POINTS';

ALTER TABLE unified_programs DROP COLUMN IF EXISTS program_type;
