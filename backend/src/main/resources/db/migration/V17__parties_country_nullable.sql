-- ============================================================================
-- V17 — parties.country and parties.registration_country were NOT NULL in the
-- live schema despite neither Party.java (the @Entity) nor PartyDto's
-- CreatePartyRequest ever marking them required -- confirmed live: POST
-- /api/v1/parties with no country fields fails with
-- "null value in column "country" ... violates not-null constraint" (and,
-- once country is supplied, the same for registration_country). Neither
-- column carries `nullable = false` in the entity, so the DB constraint was
-- stricter than the contract Hibernate itself enforces -- most likely a
-- leftover from a pre-V13 hand-run schema (see V13's baseline note in
-- CLAUDE.md), never revisited once Party gained non-required address/
-- registration fields.
--
-- This surfaced concretely through ReceivableInvoiceProcessor's auto-onboard
-- path (file-ingest's bulk "Raise Invoices" feature): a debtor with no known
-- country in the source row calls PartyService.createParty() without either
-- field and would fail in this exact way against a real database.
-- ============================================================================

ALTER TABLE parties ALTER COLUMN country DROP NOT NULL;
ALTER TABLE parties ALTER COLUMN registration_country DROP NOT NULL;
