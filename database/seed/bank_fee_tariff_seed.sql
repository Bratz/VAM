-- client_encoding pinned to UTF8 for this session: without it, psql falls
-- back to the shell locale's encoding (often not UTF-8 on a fresh box/CI
-- runner), which silently mangles every non-ASCII character below into
-- mojibake on insert (e.g. '·' becomes 'Â·') -- a data corruption, not a
-- display bug, since it's the bytes actually written to the table. See
-- V14__fix_mojibake_encoding.sql for the one-time repair of data seeded
-- before this fix existed.
SET client_encoding = 'UTF8';

-- ============================================================================
-- Simulator Phase 2 — bank_fee_tariff seed
-- ============================================================================
-- Curated ESTIMATE tariffs for the bank codes actually present in this DB
-- (not the spec's example list). Idempotent: ON CONFLICT DO NOTHING against
-- the UNIQUE (bank_code, payment_rail, effective_from) constraint.
-- Source = 'ESTIMATE' — disclosed in the Score "View assumptions" drawer.
-- ============================================================================

-- Universal rails (every bank): internal book transfer, domestic RTGS,
-- cross-border SWIFT MT103.
WITH banks(bank_code, ccy) AS (VALUES
    ('ENBD','AED'), ('HSBC','USD'),
    ('EBILAEADXXX','AED'), ('BOMLAEADXXX','AED'), ('HBMEAEADXXX','AED'),
    ('HBUKGB4BXXX','GBP'), ('BARCGB22XXX','GBP'), ('LOYDGB2LXXX','GBP'),
    ('BNPAFRPPXXX','EUR'), ('COBADEFFXXX','EUR'), ('DEUTDEFFXXX','EUR'),
    ('UNCRITMMXXX','EUR'),
    ('CITIUS33XXX','USD'), ('CHASUS33XXX','USD'), ('BOFAUS3NXXX','USD'),
    ('SCBLSGSGXXX','SGD'),
    ('NCBKSAJEXXX','SAR'), ('RIBLSARIXXX','SAR'), ('RJHISARIXXX','SAR')
),
rails(payment_rail, fee_fixed, fee_bps) AS (VALUES
    ('BANCS',       0.5000, 0.0000),
    ('RTGS',        5.0000, 0.0000),
    ('SWIFT_MT103', 25.0000, 2.0000)
)
INSERT INTO bank_fee_tariff
    (bank_code, payment_rail, fee_currency, fee_fixed, fee_bps, effective_from, source)
SELECT b.bank_code, r.payment_rail, b.ccy, r.fee_fixed, r.fee_bps,
       DATE '2026-01-01', 'ESTIMATE'
FROM banks b CROSS JOIN rails r
ON CONFLICT (bank_code, payment_rail, effective_from) DO NOTHING;

-- SEPA — EUR-zone banks only.
INSERT INTO bank_fee_tariff
    (bank_code, payment_rail, fee_currency, fee_fixed, fee_bps, effective_from, source)
SELECT s.bank_code, 'SEPA', 'EUR', 0.2000, 0.0000, DATE '2026-01-01', 'ESTIMATE'
FROM (VALUES ('BNPAFRPPXXX'),('COBADEFFXXX'),('DEUTDEFFXXX'),('UNCRITMMXXX'))
     AS s(bank_code)
ON CONFLICT (bank_code, payment_rail, effective_from) DO NOTHING;

-- OPEN_BANKING — UAE + UK open-banking markets.
INSERT INTO bank_fee_tariff
    (bank_code, payment_rail, fee_currency, fee_fixed, fee_bps, effective_from, source)
SELECT s.bank_code, 'OPEN_BANKING', s.ccy, 0.1000, 0.0000,
       DATE '2026-01-01', 'ESTIMATE'
FROM (VALUES ('ENBD','AED'),('EBILAEADXXX','AED'),('BOMLAEADXXX','AED'),
             ('HBMEAEADXXX','AED'),('HBUKGB4BXXX','GBP'),('BARCGB22XXX','GBP'),
             ('LOYDGB2LXXX','GBP')) AS s(bank_code, ccy)
ON CONFLICT (bank_code, payment_rail, effective_from) DO NOTHING;
