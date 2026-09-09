-- ============================================================================
-- CASH CONCENTRATION SIMULATOR - PHASE 3
-- Migration V7: single-user activation fields (additive)
-- ============================================================================
--
-- Additive columns on simulator_scenarios for the single-user activation
-- flow (proposer-only; no co-approver — see tasks/todo.md decision). The
-- status state-machine (DRAFT/READY → PROPOSED → ACTIVATED, or back to READY
-- with activation_error on failure) already exists via V5's chk_scenario_status.
--
-- Same deployment caveat as V5/V6: spring.flyway.enabled=false +
-- ddl-auto=update — the JPA entity is the runtime source of truth; this file
-- is the canonical DDL / fresh-bootstrap path and MUST stay in lockstep.
-- ============================================================================

ALTER TABLE simulator_scenarios
    ADD COLUMN IF NOT EXISTS proposed_by              VARCHAR(100),
    ADD COLUMN IF NOT EXISTS proposal_notes           TEXT,
    ADD COLUMN IF NOT EXISTS scheduled_activation_at  TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN IF NOT EXISTS activated_by             VARCHAR(100),
    ADD COLUMN IF NOT EXISTS activated_at             TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN IF NOT EXISTS activation_error         TEXT;

COMMENT ON COLUMN simulator_scenarios.activation_error IS
    'Last activation failure reason; set when a transactional activate() rolls back (status stays READY).';
