-- ============================================================================
-- CASH CONCENTRATION SIMULATOR - PHASE 4
-- Migration V8: shadow_sync_log (Source-quality score line)
-- ============================================================================
--
-- Per-physical-account external-feed sync history. The Phase-4 Source-quality
-- line aggregates trailing-90d miss_rate + avg_lag_hours from this table.
-- Append-only event log (no BaseEntity audit columns — they'd be noise on an
-- immutable, high-volume series).
--
-- Same deployment caveat as V5–V7: spring.flyway.enabled=false +
-- ddl-auto=update — the JPA entity is the runtime source of truth; this file
-- is the canonical DDL / fresh-bootstrap path and MUST stay in lockstep.
-- ============================================================================

CREATE TABLE IF NOT EXISTS shadow_sync_log (
    id                  UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    physical_account_id UUID         NOT NULL,
    data_source         VARCHAR(30)  NOT NULL,
    expected_sync_at    TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    actual_sync_at      TIMESTAMP WITHOUT TIME ZONE,         -- NULL = missed
    lag_minutes         INTEGER,                              -- actual − expected (when synced)
    sync_status         VARCHAR(20)  NOT NULL,                -- SUCCESS | FAILED | MISSED
    created_at          TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ssl_pa_expected
    ON shadow_sync_log (physical_account_id, expected_sync_at);

COMMENT ON TABLE  shadow_sync_log              IS 'External-feed sync history for the Simulator Source-quality score line (Phase 4).';
COMMENT ON COLUMN shadow_sync_log.sync_status  IS 'Anything other than SUCCESS counts toward miss_rate.';
