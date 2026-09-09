-- client_encoding pinned to UTF8 for this session: without it, psql falls
-- back to the shell locale's encoding (often not UTF-8 on a fresh box/CI
-- runner), which silently mangles every non-ASCII character below into
-- mojibake on insert (e.g. '·' becomes 'Â·') -- a data corruption, not a
-- display bug, since it's the bytes actually written to the table. See
-- V14__fix_mojibake_encoding.sql for the one-time repair of data seeded
-- before this fix existed.
SET client_encoding = 'UTF8';

-- ============================================================================
-- SPRINT 1 / T13 — Forecast demo seed (AED variant, MNC-UAE entity)
-- ============================================================================
-- Populates the AR / AP / forecast_adjustment rows the Sprint 1 forecasting
-- engines (Pattern, Aging, ManualOverlay) need to produce a compelling chart
-- for the sign-off demo.
--
-- Architecture (this is the corrected model — original seed wrongly created
-- a fake corporate):
--   * Single super-corporate: 11111111-1111-1111-1111-111111111111
--     (MNC Holdings Ltd — the system-wide tenant under which every
--     legal entity sits).
--   * Target legal entity:    e1111111-1111-1111-1111-111111111113
--     (MNC-UAE — the AED-functional UAE operating subsidiary).
--   * All AR/AP rows seeded here belong to MNC-UAE under MNC Holdings.
--
-- Engines exercised (forecast_line counts on POST /run):
--   PatternEngine     — 3× SALARY (PAYROLL), 1× TAX, 3× UTILITY (RENT) = 7
--   AgingEngine       — 16 OPEN + 2 PARTIAL + 2 OVERDUE = 20
--   ManualOverlayEngine — 1 carry-forward
-- Total: 28 lines per orchestrator run.
--
-- Idempotency: every row carries a 'SEED-FCAST-AED-...' natural-key prefix on
-- its *_number / created_by / reason_code column. The block-level DELETE at
-- the top removes only this script's rows on rerun.
-- ============================================================================

\set ON_ERROR_STOP on

\set CORP_ID    '\'11111111-1111-1111-1111-111111111111\''
\set ENTITY_ID  '\'e1111111-1111-1111-1111-111111111113\''

BEGIN;

-- ----------------------------------------------------------------------------
-- 0. Idempotency cleanup
-- ----------------------------------------------------------------------------

DELETE FROM forecast_adjustment WHERE reason_code = 'SEED_DEMO_AED_CARRYFORWARD';
DELETE FROM forecast_line       WHERE run_id IN (SELECT id FROM forecast_run WHERE created_by = 'seed:t13-aed');
DELETE FROM forecast_run        WHERE created_by = 'seed:t13-aed';
DELETE FROM receivables         WHERE receivable_number LIKE 'SEED-FCAST-AED-%';
DELETE FROM payables            WHERE payable_number    LIKE 'SEED-FCAST-AED-%';


-- ----------------------------------------------------------------------------
-- 1. PatternEngine — 3× monthly SALARY payable (PAYROLL category)
-- ----------------------------------------------------------------------------
-- Day-relative offsets (today+28/58/88) guarantee all three rows land
-- inside the engine's [today, today+91d] horizon regardless of when the
-- seed is run. Calendar-day anchoring fails near month boundaries.

INSERT INTO payables (
    id, payable_number, payable_type, corporate_id, owning_entity_id,
    currency_code, gross_amount, net_amount, outstanding_amount,
    received_date, due_date, status, payment_status,
    description, created_at, updated_at, version
)
SELECT
    uuid_generate_v4(),
    'SEED-FCAST-AED-AP-SAL-' || gs,
    'SALARY',
    :CORP_ID::uuid,
    :ENTITY_ID::uuid,
    'AED', 1800000.00, 1800000.00, 1800000.00,
    CURRENT_DATE,
    (CURRENT_DATE + ((28 + gs * 30) || ' days')::interval)::date,
    'APPROVED', 'UNPAID',
    'Demo: monthly headcount payroll (MNC-UAE)',
    now(), now(), 0
FROM generate_series(0, 2) gs;


-- ----------------------------------------------------------------------------
-- 2. PatternEngine — quarterly TAX payable
-- ----------------------------------------------------------------------------

INSERT INTO payables (
    id, payable_number, payable_type, corporate_id, owning_entity_id,
    currency_code, gross_amount, net_amount, outstanding_amount,
    received_date, due_date, status, payment_status,
    description, created_at, updated_at, version
) VALUES (
    uuid_generate_v4(), 'SEED-FCAST-AED-AP-TAX-1', 'TAX',
    :CORP_ID::uuid, :ENTITY_ID::uuid,
    'AED', 750000.00, 750000.00, 750000.00,
    CURRENT_DATE, (CURRENT_DATE + interval '45 days')::date,
    'SCHEDULED', 'UNPAID',
    'Demo: quarterly corporate tax (MNC-UAE)',
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
    'SEED-FCAST-AED-AP-RNT-' || gs,
    'UTILITY',
    :CORP_ID::uuid, :ENTITY_ID::uuid,
    'AED', 95000.00, 95000.00, 95000.00,
    CURRENT_DATE,
    (CURRENT_DATE + ((10 + gs * 30) || ' days')::interval)::date,
    'APPROVED', 'UNPAID',
    'Demo: warehouse rent + utilities (MNC-UAE Dubai)',
    now(), now(), 0
FROM generate_series(0, 2) gs;


-- ----------------------------------------------------------------------------
-- 4. AgingEngine background — 12 INVOICE payables (Sprint-1 visual only)
-- ----------------------------------------------------------------------------

INSERT INTO payables (
    id, payable_number, payable_type, corporate_id, owning_entity_id,
    currency_code, gross_amount, net_amount, outstanding_amount,
    received_date, due_date, status, payment_status,
    description, created_at, updated_at, version
)
SELECT
    uuid_generate_v4(),
    'SEED-FCAST-AED-AP-INV-' || lpad(gs::text, 2, '0'),
    'INVOICE',
    :CORP_ID::uuid, :ENTITY_ID::uuid,
    'AED',
    (30000 + (gs * 31000))::numeric(18,2),
    (30000 + (gs * 31000))::numeric(18,2),
    (30000 + (gs * 31000))::numeric(18,2),
    CURRENT_DATE - interval '7 days',
    (CURRENT_DATE + ((gs * 5) || ' days')::interval)::date,
    CASE WHEN gs % 2 = 0 THEN 'APPROVED' ELSE 'SCHEDULED' END,
    'UNPAID',
    'Demo: vendor invoice batch (MNC-UAE)',
    now(), now(), 0
FROM generate_series(0, 11) gs;


-- ----------------------------------------------------------------------------
-- 5. AgingEngine — 20 open AR (16 OPEN + 2 PARTIAL + 2 OVERDUE)
-- ----------------------------------------------------------------------------

-- 16 OPEN — AED 50k → 800k spread
INSERT INTO receivables (
    id, receivable_number, receivable_type, corporate_id, owning_entity_id,
    currency_code, gross_amount, net_amount, outstanding_amount,
    issue_date, due_date, status, payment_status,
    description, created_at, updated_at, version
)
SELECT
    uuid_generate_v4(),
    'SEED-FCAST-AED-AR-OPN-' || lpad(gs::text, 2, '0'),
    'INVOICE',
    :CORP_ID::uuid, :ENTITY_ID::uuid,
    'AED',
    (50000 + (gs * 47000) + ((gs * 13) % 30000))::numeric(18,2),
    (50000 + (gs * 47000) + ((gs * 13) % 30000))::numeric(18,2),
    (50000 + (gs * 47000) + ((gs * 13) % 30000))::numeric(18,2),
    CURRENT_DATE - interval '14 days',
    (CURRENT_DATE + ((4 + gs * 5) || ' days')::interval)::date,
    'OPEN', 'PENDING',
    'Demo: MNC-UAE customer invoice (open)',
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
    'SEED-FCAST-AED-AR-PAR-' || gs,
    'INVOICE',
    :CORP_ID::uuid, :ENTITY_ID::uuid,
    'AED', 400000.00, 400000.00,
    (180000 + gs * 40000)::numeric(18,2),
    (220000 - gs * 40000)::numeric(18,2),
    CURRENT_DATE - interval '30 days',
    (CURRENT_DATE + ((20 + gs * 14) || ' days')::interval)::date,
    'PARTIAL', 'PARTIAL',
    'Demo: MNC-UAE customer invoice (partial pay)',
    now(), now(), 0
FROM generate_series(0, 1) gs;

-- 2 OVERDUE — engine shifts to today+7d
INSERT INTO receivables (
    id, receivable_number, receivable_type, corporate_id, owning_entity_id,
    currency_code, gross_amount, net_amount, outstanding_amount,
    issue_date, due_date, status, payment_status,
    description, created_at, updated_at, version
)
SELECT
    uuid_generate_v4(),
    'SEED-FCAST-AED-AR-ODU-' || gs,
    'INVOICE',
    :CORP_ID::uuid, :ENTITY_ID::uuid,
    'AED', 320000.00, 320000.00, 320000.00,
    CURRENT_DATE - interval '60 days',
    (CURRENT_DATE - ((gs + 1) * 4 || ' days')::interval)::date,
    'OVERDUE', 'PENDING',
    'Demo: MNC-UAE customer invoice (overdue)',
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
        'COMPLETED', 'seed:t13-aed', 1234
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
    'AED', fc.id,
    500000.0000,
    'SEED_DEMO_AED_CARRYFORWARD',
    'Demo carry-forward: expected M&A proceeds (Acme acquisition) — confirmed by Treasury',
    'seed:t13-aed', now()
FROM prior_run pr
CROSS JOIN forecast_category fc
WHERE fc.code = 'MANUAL_OTHER';


-- ----------------------------------------------------------------------------
-- 7. Smoke counts
-- ----------------------------------------------------------------------------

SELECT 'payables (AED pattern)'        AS what, count(*) AS n FROM payables    WHERE payable_number    LIKE 'SEED-FCAST-AED-AP-SAL-%' OR payable_number LIKE 'SEED-FCAST-AED-AP-TAX-%' OR payable_number LIKE 'SEED-FCAST-AED-AP-RNT-%';
SELECT 'payables (AED INVOICE bg)'     AS what, count(*) AS n FROM payables    WHERE payable_number    LIKE 'SEED-FCAST-AED-AP-INV-%';
SELECT 'receivables (AED AR)'          AS what, count(*) AS n FROM receivables WHERE receivable_number LIKE 'SEED-FCAST-AED-AR-%';
SELECT 'forecast_run (AED carry seed)' AS what, count(*) AS n FROM forecast_run WHERE created_by = 'seed:t13-aed';
SELECT 'forecast_adjustment (AED)'     AS what, count(*) AS n FROM forecast_adjustment WHERE reason_code = 'SEED_DEMO_AED_CARRYFORWARD';

COMMIT;
