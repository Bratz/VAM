-- client_encoding pinned to UTF8 for this session: without it, psql falls
-- back to the shell locale's encoding (often not UTF-8 on a fresh box/CI
-- runner), which silently mangles every non-ASCII character below into
-- mojibake on insert (e.g. '·' becomes 'Â·') -- a data corruption, not a
-- display bug, since it's the bytes actually written to the table. See
-- V14__fix_mojibake_encoding.sql for the one-time repair of data seeded
-- before this fix existed.
SET client_encoding = 'UTF8';

-- ============================================================================
-- SPRINT 1 — Forecast demo seed (UK variant, MNC-UK entity)
-- ============================================================================
-- Companion to seed_forecast_demo.sql. Use this when the backend is booted
-- with VAM_MARKET_PROFILE=UK so the chart's currency, locale, and trough
-- thresholds line up with sterling-denominated cashflows.
--
-- Architecture (this is the corrected model — original seed wrongly created
-- a fake corporate):
--   * Single super-corporate: 11111111-1111-1111-1111-111111111111
--     (MNC Holdings Ltd — system-wide tenant).
--   * Target legal entity:    e1111111-1111-1111-1111-111111111117
--     (MNC-UK — the GBP-functional UK subsidiary).
--   * All AR/AP rows seeded here belong to MNC-UK under MNC Holdings.
--
-- Amounts scaled to a UK mid-cap PLC level (5–10× the AED equivalents) so
-- screenshots feel commensurate with the GBP axis labels.
--
-- Idempotency: every row carries a 'SEED-FCAST-UK-...' natural-key prefix
-- on its *_number / created_by / reason_code column. Coexists with the AED
-- seed (different prefix, different entity).
-- ============================================================================

\set ON_ERROR_STOP on

\set CORP_ID    '\'11111111-1111-1111-1111-111111111111\''
\set ENTITY_ID  '\'e1111111-1111-1111-1111-111111111117\''

BEGIN;

-- ----------------------------------------------------------------------------
-- 0. Idempotency cleanup
-- ----------------------------------------------------------------------------

DELETE FROM forecast_adjustment WHERE reason_code = 'SEED_DEMO_UK_CARRYFORWARD';
DELETE FROM forecast_line       WHERE run_id IN (SELECT id FROM forecast_run WHERE created_by = 'seed:t16-uk');
DELETE FROM forecast_run        WHERE created_by = 'seed:t16-uk';
DELETE FROM receivables         WHERE receivable_number LIKE 'SEED-FCAST-UK-%';
DELETE FROM payables            WHERE payable_number    LIKE 'SEED-FCAST-UK-%';


-- ----------------------------------------------------------------------------
-- 1. PatternEngine — 3× monthly SALARY (UK payroll ~GBP 850k/month)
-- ----------------------------------------------------------------------------

INSERT INTO payables (
    id, payable_number, payable_type, corporate_id, owning_entity_id,
    currency_code, gross_amount, net_amount, outstanding_amount,
    received_date, due_date, status, payment_status,
    description, created_at, updated_at, version
)
SELECT
    uuid_generate_v4(),
    'SEED-FCAST-UK-AP-SAL-' || gs,
    'SALARY',
    :CORP_ID::uuid, :ENTITY_ID::uuid,
    'GBP', 850000.00, 850000.00, 850000.00,
    CURRENT_DATE,
    (CURRENT_DATE + ((28 + gs * 30) || ' days')::interval)::date,
    'APPROVED', 'UNPAID',
    'Demo: UK monthly headcount payroll PAYE+NICs (MNC-UK)',
    now(), now(), 0
FROM generate_series(0, 2) gs;


-- ----------------------------------------------------------------------------
-- 2. PatternEngine — quarterly Corporation Tax + VAT
-- ----------------------------------------------------------------------------

INSERT INTO payables (
    id, payable_number, payable_type, corporate_id, owning_entity_id,
    currency_code, gross_amount, net_amount, outstanding_amount,
    received_date, due_date, status, payment_status,
    description, created_at, updated_at, version
) VALUES (
    uuid_generate_v4(), 'SEED-FCAST-UK-AP-TAX-1', 'TAX',
    :CORP_ID::uuid, :ENTITY_ID::uuid,
    'GBP', 1200000.00, 1200000.00, 1200000.00,
    CURRENT_DATE, (CURRENT_DATE + interval '45 days')::date,
    'SCHEDULED', 'UNPAID',
    'Demo: HMRC quarterly Corporation Tax + VAT (MNC-UK)',
    now(), now(), 0
);


-- ----------------------------------------------------------------------------
-- 3. PatternEngine — 3× monthly UTILITY (→ RENT category)
-- ----------------------------------------------------------------------------

INSERT INTO payables (
    id, payable_number, payable_type, corporate_id, owning_entity_id,
    currency_code, gross_amount, net_amount, outstanding_amount,
    received_date, due_date, status, payment_status,
    description, created_at, updated_at, version
)
SELECT
    uuid_generate_v4(),
    'SEED-FCAST-UK-AP-RNT-' || gs,
    'UTILITY',
    :CORP_ID::uuid, :ENTITY_ID::uuid,
    'GBP', 65000.00, 65000.00, 65000.00,
    CURRENT_DATE,
    (CURRENT_DATE + ((10 + gs * 30) || ' days')::interval)::date,
    'APPROVED', 'UNPAID',
    'Demo: UK warehouse lease + utilities, Hull plant (MNC-UK)',
    now(), now(), 0
FROM generate_series(0, 2) gs;


-- ----------------------------------------------------------------------------
-- 4. AgingEngine background — 12 INVOICE payables
-- ----------------------------------------------------------------------------

INSERT INTO payables (
    id, payable_number, payable_type, corporate_id, owning_entity_id,
    currency_code, gross_amount, net_amount, outstanding_amount,
    received_date, due_date, status, payment_status,
    description, created_at, updated_at, version
)
SELECT
    uuid_generate_v4(),
    'SEED-FCAST-UK-AP-INV-' || lpad(gs::text, 2, '0'),
    'INVOICE',
    :CORP_ID::uuid, :ENTITY_ID::uuid,
    'GBP',
    (25000 + (gs * 27000))::numeric(18,2),
    (25000 + (gs * 27000))::numeric(18,2),
    (25000 + (gs * 27000))::numeric(18,2),
    CURRENT_DATE - interval '7 days',
    (CURRENT_DATE + ((gs * 5) || ' days')::interval)::date,
    CASE WHEN gs % 2 = 0 THEN 'APPROVED' ELSE 'SCHEDULED' END,
    'UNPAID',
    'Demo: UK vendor invoice batch (MNC-UK)',
    now(), now(), 0
FROM generate_series(0, 11) gs;


-- ----------------------------------------------------------------------------
-- 5. AgingEngine — 20 open AR
-- ----------------------------------------------------------------------------

-- 16 OPEN
INSERT INTO receivables (
    id, receivable_number, receivable_type, corporate_id, owning_entity_id,
    currency_code, gross_amount, net_amount, outstanding_amount,
    issue_date, due_date, status, payment_status,
    description, created_at, updated_at, version
)
SELECT
    uuid_generate_v4(),
    'SEED-FCAST-UK-AR-OPN-' || lpad(gs::text, 2, '0'),
    'INVOICE',
    :CORP_ID::uuid, :ENTITY_ID::uuid,
    'GBP',
    (40000 + (gs * 41000) + ((gs * 11) % 25000))::numeric(18,2),
    (40000 + (gs * 41000) + ((gs * 11) % 25000))::numeric(18,2),
    (40000 + (gs * 41000) + ((gs * 11) % 25000))::numeric(18,2),
    CURRENT_DATE - interval '14 days',
    (CURRENT_DATE + ((4 + gs * 5) || ' days')::interval)::date,
    'OPEN', 'PENDING',
    'Demo: MNC-UK customer invoice (open)',
    now(), now(), 0
FROM generate_series(0, 15) gs;

-- 2 PARTIAL
INSERT INTO receivables (
    id, receivable_number, receivable_type, corporate_id, owning_entity_id,
    currency_code, gross_amount, net_amount, outstanding_amount, paid_amount,
    issue_date, due_date, status, payment_status,
    description, created_at, updated_at, version
)
SELECT
    uuid_generate_v4(),
    'SEED-FCAST-UK-AR-PAR-' || gs,
    'INVOICE',
    :CORP_ID::uuid, :ENTITY_ID::uuid,
    'GBP', 320000.00, 320000.00,
    (150000 + gs * 35000)::numeric(18,2),
    (170000 - gs * 35000)::numeric(18,2),
    CURRENT_DATE - interval '30 days',
    (CURRENT_DATE + ((20 + gs * 14) || ' days')::interval)::date,
    'PARTIAL', 'PARTIAL',
    'Demo: MNC-UK customer invoice (partial pay)',
    now(), now(), 0
FROM generate_series(0, 1) gs;

-- 2 OVERDUE
INSERT INTO receivables (
    id, receivable_number, receivable_type, corporate_id, owning_entity_id,
    currency_code, gross_amount, net_amount, outstanding_amount,
    issue_date, due_date, status, payment_status,
    description, created_at, updated_at, version
)
SELECT
    uuid_generate_v4(),
    'SEED-FCAST-UK-AR-ODU-' || gs,
    'INVOICE',
    :CORP_ID::uuid, :ENTITY_ID::uuid,
    'GBP', 275000.00, 275000.00, 275000.00,
    CURRENT_DATE - interval '60 days',
    (CURRENT_DATE - ((gs + 1) * 4 || ' days')::interval)::date,
    'OVERDUE', 'PENDING',
    'Demo: MNC-UK customer invoice (overdue — chasing)',
    now(), now(), 0
FROM generate_series(0, 1) gs;


-- ----------------------------------------------------------------------------
-- 6. ManualOverlayEngine — prior COMPLETED run + carry-forward adjustment
-- ----------------------------------------------------------------------------

WITH prior_run AS (
    INSERT INTO forecast_run (
        id, corporate_id, run_at, horizon_end, status, created_by, generation_ms
    ) VALUES (
        uuid_generate_v4(),
        :CORP_ID::uuid,
        now() - interval '1 day',
        (CURRENT_DATE + interval '91 days')::date,
        'COMPLETED', 'seed:t16-uk', 987
    )
    RETURNING id
)
INSERT INTO forecast_adjustment (
    id, run_id, value_date, entity_id, currency, category_id,
    delta_amount, reason_code, note, created_by, created_at
)
SELECT
    uuid_generate_v4(), pr.id,
    (CURRENT_DATE + interval '28 days')::date,
    :ENTITY_ID::uuid,
    'GBP', fc.id,
    400000.0000,
    'SEED_DEMO_UK_CARRYFORWARD',
    'Demo carry-forward: HMRC VAT rebate expected — confirmed by Group Treasury',
    'seed:t16-uk', now()
FROM prior_run pr
CROSS JOIN forecast_category fc
WHERE fc.code = 'MANUAL_OTHER';


-- ----------------------------------------------------------------------------
-- 7. Smoke counts
-- ----------------------------------------------------------------------------

SELECT 'payables (UK pattern)'        AS what, count(*) AS n FROM payables    WHERE payable_number    LIKE 'SEED-FCAST-UK-AP-SAL-%' OR payable_number LIKE 'SEED-FCAST-UK-AP-TAX-%' OR payable_number LIKE 'SEED-FCAST-UK-AP-RNT-%';
SELECT 'payables (UK INVOICE bg)'     AS what, count(*) AS n FROM payables    WHERE payable_number    LIKE 'SEED-FCAST-UK-AP-INV-%';
SELECT 'receivables (UK AR)'          AS what, count(*) AS n FROM receivables WHERE receivable_number LIKE 'SEED-FCAST-UK-AR-%';
SELECT 'forecast_run (UK carry seed)' AS what, count(*) AS n FROM forecast_run WHERE created_by = 'seed:t16-uk';
SELECT 'forecast_adjustment (UK)'     AS what, count(*) AS n FROM forecast_adjustment WHERE reason_code = 'SEED_DEMO_UK_CARRYFORWARD';

COMMIT;
