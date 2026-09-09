-- ============================================================================
-- CASH CONCENTRATION SIMULATOR - PHASE 1
-- Migration V5: simulator_scenarios
-- ============================================================================
--
-- A sandbox over the live three-layer model (Physical Accounts → Shadow VAs →
-- Sweep Rules). A simulated structure is the SAME data shape as a live one,
-- carried in JSONB, with no writes to live operational tables in Phase 1.
--
-- Schema-of-record. NOTE: this deployment runs with `spring.flyway.enabled=false`
-- and `spring.jpa.hibernate.ddl-auto=update`, so at runtime the table is created
-- by the JPA entity (com.bank.vam.entity.simulator.SimulatorScenario). This file
-- is the canonical DDL for fresh bootstrap / when Flyway is re-enabled, and MUST
-- stay in lockstep with that entity.
--
-- Phase 2 adds bank_fee_tariff (V6). Phase 5 adds regulatory_rules.
-- No changes to live operational tables in any phase except the explicit
-- Phase 3 activation flow, which routes through the existing approval pipeline.
-- ============================================================================

CREATE TABLE IF NOT EXISTS simulator_scenarios (
    id                  UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),

    -- Scope
    corporate_id        UUID         NOT NULL,

    -- Identity
    scenario_name       VARCHAR(120) NOT NULL,
    scenario_reference  VARCHAR(20)  NOT NULL,
    description         TEXT,
    base_currency       VARCHAR(3),

    -- Lifecycle: DRAFT → READY → PROPOSED → ACTIVATED ; ARCHIVED = soft delete
    status              VARCHAR(20)  DEFAULT 'DRAFT',

    -- Forking (Phase 4) — accepted by the API in V1, surfaced in the UI later
    parent_scenario_id  UUID,
    fork_label          VARCHAR(60),

    -- Diff baseline (Phase 3). Frozen on first capture; manual re-snapshot only.
    snapshot_taken_at   TIMESTAMP WITHOUT TIME ZONE,
    snapshot_payload    JSONB,

    -- The simulated structure: { "shadows": [...], "rules": [...] }
    proposed_payload    JSONB        NOT NULL DEFAULT '{"shadows":[],"rules":[]}'::jsonb,

    -- Last computed Optimisation Score (Phase 2)
    score_payload       JSONB,

    notes               TEXT,

    -- BaseEntity audit columns (mapped by com.bank.vam.entity.BaseEntity)
    created_at          TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_by          VARCHAR(100),
    updated_at          TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_by          VARCHAR(100),
    version             BIGINT       DEFAULT 0,

    CONSTRAINT chk_scenario_status CHECK (
        status IN ('DRAFT', 'READY', 'PROPOSED', 'ACTIVATED', 'ARCHIVED')
    )
);

CREATE INDEX IF NOT EXISTS idx_simscen_corporate ON simulator_scenarios (corporate_id);
CREATE INDEX IF NOT EXISTS idx_simscen_parent    ON simulator_scenarios (parent_scenario_id);
CREATE INDEX IF NOT EXISTS idx_simscen_status    ON simulator_scenarios (status);

COMMENT ON TABLE  simulator_scenarios               IS 'Cash Concentration Simulator: a saved proposed structure (sandbox; no live writes in V1).';
COMMENT ON COLUMN simulator_scenarios.proposed_payload IS 'Simulated structure JSONB: { shadows:[SimulatedShadow], rules:[SimulatedRule] }.';
COMMENT ON COLUMN simulator_scenarios.snapshot_payload IS 'Frozen live config captured as the Phase 3 diff baseline.';
COMMENT ON COLUMN simulator_scenarios.score_payload    IS 'Last computed Optimisation Score (Phase 2).';
COMMENT ON COLUMN simulator_scenarios.status           IS 'DRAFT | READY | PROPOSED | ACTIVATED | ARCHIVED (ARCHIVED = soft delete).';
