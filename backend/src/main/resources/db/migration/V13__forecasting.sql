-- ============================================================================
-- VAM CASH FORECASTING MODULE
-- Migration V13: Forecast runs, lines, categories, adjustments, scenarios, variance
-- ============================================================================
--
-- Purpose:
--   Introduces the persistence layer for the Aperture cash-forecasting module
--   (Sprint 1 / T1). Supports multi-engine forecast generation (PATTERN,
--   AGING, ML, DRIVER, MANUAL), treasurer overlays, what-if scenarios, and
--   forecast-vs-actual variance tracking.
--
-- Author:  Treasury Platform
-- Date:    2026-05-29
-- Version: V13
--
-- Conventions:
--   * UUID primary keys via uuid_generate_v4() (uuid-ossp).
--   * Run-scoped child tables (forecast_line, _adjustment, _scenario,
--     _variance) cascade-delete with their parent forecast_run.
--   * `value_date` columns are tz-naive DATE values, stored in the owning
--     corporate's home timezone (resolved at query time, NOT UTC). This
--     mirrors how AR/AP aging buckets are interpreted in the rest of the
--     platform.
--   * `entity_id` is a typed UUID with NO foreign key constraint in V13: a
--     dedicated `legal_entity` table is not yet present in the schema (the
--     closest analogue is ihb_entity_mappings, but that is IHB-scoped and
--     not the right target for general forecasting). When the canonical
--     legal_entity table lands, a follow-up migration should add the FK.
--   * Monetary columns use numeric(20,4) to accommodate large-corp cash
--     positions without precision loss in roll-ups.
--   * Percentile columns (amount_p10 / amount_p90) are optional and only
--     populated by probabilistic engines (ML).
--
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 1. forecast_category
-- ----------------------------------------------------------------------------
-- Reference data. Drives the chart-of-cashflow taxonomy and the default
-- generation engine per category. Self-referential parent_id supports a
-- two-level hierarchy (group -> leaf) without a separate hierarchy table.

CREATE TABLE forecast_category (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    code            VARCHAR(50) NOT NULL UNIQUE,
    label           VARCHAR(200) NOT NULL,
    direction       VARCHAR(10) NOT NULL,
    parent_id       UUID REFERENCES forecast_category(id),
    default_engine  VARCHAR(20) NOT NULL,
    color           VARCHAR(7),

    CONSTRAINT chk_fcat_direction CHECK (direction IN ('IN', 'OUT')),
    CONSTRAINT chk_fcat_engine    CHECK (default_engine IN (
        'PATTERN', 'AGING', 'ML', 'DRIVER', 'MANUAL'
    ))
);

COMMENT ON TABLE forecast_category IS
    'Cashflow taxonomy. Each category has a default generation engine and a hex color used by UI charts.';


-- ----------------------------------------------------------------------------
-- 2. forecast_run
-- ----------------------------------------------------------------------------
-- One row per forecast generation execution. A run is the atomic unit of
-- forecast output: re-running invalidates nothing but produces a new run_id.

CREATE TABLE forecast_run (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    corporate_id    UUID NOT NULL,
    run_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    horizon_end     DATE NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    created_by      VARCHAR(100),
    generation_ms   INTEGER,

    CONSTRAINT chk_frun_status CHECK (status IN (
        'RUNNING', 'COMPLETED', 'FAILED'
    ))
);

CREATE INDEX idx_forecast_run_corp_run_at
    ON forecast_run (corporate_id, run_at DESC);

COMMENT ON TABLE forecast_run IS
    'Header for a single forecast generation. Child rows in forecast_line/_adjustment/_scenario/_variance reference this row and cascade-delete with it.';


-- ----------------------------------------------------------------------------
-- 3. forecast_line
-- ----------------------------------------------------------------------------
-- Atomic forecast row: one value-dated cashflow estimate for a given
-- (entity, currency, category, optional counterparty). Multiple engines may
-- contribute lines for the same key; the consuming roll-up sums them.

CREATE TABLE forecast_line (
    id                   UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    run_id               UUID NOT NULL REFERENCES forecast_run(id) ON DELETE CASCADE,
    value_date           DATE NOT NULL,
    entity_id            UUID NOT NULL,
    physical_account_id  UUID,
    currency             CHAR(3) NOT NULL,
    category_id          UUID NOT NULL REFERENCES forecast_category(id),
    counterparty_id      UUID,
    amount_mid           NUMERIC(20,4) NOT NULL,
    amount_p10           NUMERIC(20,4),
    amount_p90           NUMERIC(20,4),
    source               VARCHAR(20) NOT NULL,
    source_ref           VARCHAR(64),
    confidence           NUMERIC(3,2),

    CONSTRAINT chk_fline_source CHECK (source IN (
        'PATTERN', 'AGING', 'ML', 'DRIVER', 'MANUAL'
    )),
    CONSTRAINT chk_fline_confidence CHECK (
        confidence IS NULL OR (confidence >= 0 AND confidence <= 1)
    )
);

CREATE INDEX idx_forecast_line_run_date
    ON forecast_line (run_id, value_date);

CREATE INDEX idx_forecast_line_run_entity_ccy
    ON forecast_line (run_id, entity_id, currency);

COMMENT ON TABLE forecast_line IS
    'Atomic forecast cashflow. amount_p10/p90 populated only by probabilistic engines (ML). source_ref links back to the originating invoice/contract/pattern row.';


-- ----------------------------------------------------------------------------
-- 4. forecast_adjustment
-- ----------------------------------------------------------------------------
-- Treasurer overlay on top of generated lines. Stored separately from
-- forecast_line so the model output is preserved and adjustments are
-- auditable and individually reversible.

CREATE TABLE forecast_adjustment (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    run_id          UUID NOT NULL REFERENCES forecast_run(id) ON DELETE CASCADE,
    value_date      DATE NOT NULL,
    entity_id       UUID NOT NULL,
    currency        CHAR(3) NOT NULL,
    category_id     UUID NOT NULL REFERENCES forecast_category(id),
    delta_amount    NUMERIC(20,4) NOT NULL,
    reason_code     VARCHAR(50) NOT NULL,
    note            TEXT,
    created_by      VARCHAR(100),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_forecast_adjustment_run_date
    ON forecast_adjustment (run_id, value_date);

COMMENT ON TABLE forecast_adjustment IS
    'Manual treasurer overlay. delta_amount is added to the corresponding (entity, currency, category, value_date) bucket when rolled up.';


-- ----------------------------------------------------------------------------
-- 5. forecast_scenario
-- ----------------------------------------------------------------------------
-- Named what-if variations of a base run. `rules` is jsonb so the rule
-- engine can evolve without schema changes (shift_pct, shift_days,
-- category_filter, etc.).

CREATE TABLE forecast_scenario (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    run_id      UUID NOT NULL REFERENCES forecast_run(id) ON DELETE CASCADE,
    name        VARCHAR(100) NOT NULL,
    type        VARCHAR(20) NOT NULL,
    rules       JSONB NOT NULL DEFAULT '{}'::jsonb,

    CONSTRAINT chk_fscenario_type CHECK (type IN (
        'BASE', 'UPSIDE', 'DOWNSIDE', 'WHATIF'
    ))
);

COMMENT ON TABLE forecast_scenario IS
    'Named overlay scenarios on a base run. rules JSON drives transformations applied to forecast_line at query time.';


-- ----------------------------------------------------------------------------
-- 6. forecast_variance
-- ----------------------------------------------------------------------------
-- Backtest output: forecast vs. settled actuals. Populated by the variance
-- job after value_date passes.

CREATE TABLE forecast_variance (
    id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    run_id            UUID NOT NULL REFERENCES forecast_run(id) ON DELETE CASCADE,
    value_date        DATE NOT NULL,
    entity_id         UUID NOT NULL,
    currency          CHAR(3) NOT NULL,
    category_id       UUID NOT NULL REFERENCES forecast_category(id),
    forecast_amount   NUMERIC(20,4) NOT NULL,
    actual_amount     NUMERIC(20,4) NOT NULL,
    variance          NUMERIC(20,4) NOT NULL,
    variance_pct      NUMERIC(8,4),
    reason_code       VARCHAR(50)
);

CREATE INDEX idx_forecast_variance_run_date
    ON forecast_variance (run_id, value_date);

COMMENT ON TABLE forecast_variance IS
    'Forecast vs. actual reconciliation per (entity, currency, category, value_date). reason_code captures the post-hoc explanation when set.';


-- ============================================================================
-- SEED DATA - forecast_category
-- ============================================================================
-- 12 baseline categories spanning AR/AP, payroll, tax, intercompany, FX,
-- capex, and financing. Colors chosen for visual distinguishability on
-- stacked-bar cashflow charts.

INSERT INTO forecast_category (code, label, direction, default_engine, color) VALUES
    ('AR_COLLECTIONS',    'AR Collections',        'IN',  'AGING',   '#2E7D32'),
    ('AP_DISBURSEMENTS',  'AP Disbursements',      'OUT', 'AGING',   '#C62828'),
    ('PAYROLL',           'Payroll',               'OUT', 'PATTERN', '#6A1B9A'),
    ('TAX',               'Tax Payments',          'OUT', 'PATTERN', '#283593'),
    ('RENT',              'Rent & Lease',          'OUT', 'PATTERN', '#4E342E'),
    ('DEBT_SERVICE',      'Debt Service',          'OUT', 'PATTERN', '#B71C1C'),
    ('INTERCOMPANY_IN',   'Intercompany Inflows',  'IN',  'PATTERN', '#00897B'),
    ('INTERCOMPANY_OUT',  'Intercompany Outflows', 'OUT', 'PATTERN', '#EF6C00'),
    ('FX_CONVERSION',     'FX Conversion',         'IN',  'DRIVER',  '#0277BD'),
    ('CAPEX',             'Capital Expenditure',   'OUT', 'MANUAL',  '#5D4037'),
    ('FINANCING',         'Financing Inflows',     'IN',  'MANUAL',  '#1565C0'),
    ('MANUAL_OTHER',      'Manual / Other',        'OUT', 'MANUAL',  '#616161');
