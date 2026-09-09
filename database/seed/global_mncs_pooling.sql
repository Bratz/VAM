-- client_encoding pinned to UTF8 for this session: without it, psql falls
-- back to the shell locale's encoding (often not UTF-8 on a fresh box/CI
-- runner), which silently mangles every non-ASCII character below into
-- mojibake on insert (e.g. '·' becomes 'Â·') -- a data corruption, not a
-- display bug, since it's the bytes actually written to the table. See
-- V14__fix_mojibake_encoding.sql for the one-time repair of data seeded
-- before this fix existed.
SET client_encoding = 'UTF8';

-- ============================================================================
-- Global MNCs — Multi-Bank Pooling & Sweeping Seed
-- ============================================================================
-- Five global multinationals, one per home currency (EUR / SAR / GBP / USD /
-- AED), each with:
--   - 3-4 subsidiary legal entities across multiple countries
--   - 4 physical bank accounts at 3+ different banks (multi-bank)
--   - A unified treasury program
--   - Shadow virtual accounts mirroring each physical account
--   - A home-currency notional pool with 3-4 member accounts
--   - 2 active sweep rules (ZBA / target-balance / threshold / percentage mix)
--   - Historical sweep executions (1 FAILED so the cockpit shows a sweep_failure item)
--   - Varied shadow refresh statuses (2 STALE, 2 FAILED, 1 NEVER, 2 in-flight float)
--     so the Treasurer's Morning Cockpit attention inbox renders the full range
--     of stale_balance item flavours on first load.
--
-- Plus, for cockpit attention-inbox variety:
--   - 2 netting cycles in PENDING_APPROVAL — feed `pending_approval` items
--   - 3 SCHEDULED payables due today — feed `funding_shortfall` items
--   - 2 PENDING va_movements past the 4h cutoff — feed `stuck_transaction`
--     items (one >24h old → critical severity)
--
-- Usage:
--   psql -h localhost -U vam_user -d vam_db -f global_mncs_pooling.sql
--
-- Idempotent: every INSERT uses ON CONFLICT DO NOTHING; UPDATEs are
-- naturally idempotent (they set deterministic values). Safe to re-run.
-- Independent: does not depend on seed_data.sql; references only schema
-- tables. Existing UUIDs in seed_data.sql use prefixes c1.. c4..; this script
-- uses prefixes aa.. ee.. (one per MNC) so there's no collision.
--
-- UUID scheme (per MNC):
--   [prefix]000000N-[role]000-0000-0000-[seq]
-- where:
--   prefix = aa (EUR / MNC 1) | bb (SAR / MNC 2) | cc (GBP / MNC 3)
--          | dd (USD / MNC 4) | ee (AED / MNC 5)
--   N      = MNC index (1..5)
--   role   = 0000 corporate    | 1000 entity      | 2000 physical acct
--          | 3000 program       | 4000 virtual acct | 5000 pool
--          | 6000 pool member   | 7000 sweep rule   | 8000 sweep source
--          | 9000 sweep execution | a000 netting cycle | b000 payable
--          | c000 stuck va_movement
--   seq    = row sequence within that role
-- ============================================================================

SET timezone = 'UTC';

-- ============================================================================
-- 0. BANKS — reference data
-- ============================================================================
-- The live schema (V4 unified) has no dedicated `banks` reference table.
-- Bank identity (SWIFT/BIC, name, country) lives directly on the rows that
-- need it: `physical_accounts.bank_name` / `bank_code` / `bank_country` and
-- `virtual_accounts.bank_swift` / `bank_name`. The reference SWIFT codes
-- below are documented here so future readers know which banks the
-- subsequent sections will pin to. No INSERT statement is needed.
--
--   Europe
--     DEUTDEFFXXX  Deutsche Bank AG          DE  Frankfurt
--     COBADEFFXXX  Commerzbank AG            DE  Frankfurt
--     BNPAFRPPXXX  BNP Paribas               FR  Paris
--     UNCRITMMXXX  UniCredit S.p.A.          IT  Milan
--   Middle East
--     RJHISARIXXX  Al Rajhi Bank             SA  Riyadh
--     NCBKSAJEXXX  Saudi National Bank       SA  Jeddah
--     RIBLSARIXXX  Riyad Bank                SA  Riyadh
--     EBILAEADXXX  Emirates NBD              AE  Dubai
--     BOMLAEADXXX  Mashreq Bank              AE  Dubai
--     HBMEAEADXXX  HSBC Bank Middle East     AE  Dubai
--   UK
--     LOYDGB2LXXX  Lloyds Bank plc           GB  London
--     BARCGB22XXX  Barclays Bank UK plc      GB  London
--     HBUKGB4BXXX  HSBC UK Bank plc          GB  Birmingham
--   US
--     CHASUS33XXX  JPMorgan Chase Bank       US  New York
--     BOFAUS3NXXX  Bank of America N.A.      US  New York
--     CITIUS33XXX  Citibank N.A.             US  New York
--   Asia
--     SCBLSGSGXXX  Standard Chartered Bank   SG  Singapore

-- ============================================================================
-- 1. CORPORATES — five MNCs, one per home currency
-- ============================================================================

-- MNC 1 — Mercator Brands Holding SE (EUR · Germany · Consumer goods)
INSERT INTO corporates (
    id, corporate_id, legal_name, trade_name, registration_number, tax_id,
    incorporation_country, incorporation_date, legal_entity_type,
    industry_sector, business_description, website, primary_contact_name,
    primary_contact_email, primary_contact_phone, status, kyc_status,
    risk_rating, created_at, updated_at
) VALUES (
    'aa000001-0000-0000-0000-000000000001',
    'MNC-EUR-001',
    'Mercator Brands Holding SE',
    'Mercator',
    'HRB-2003-44211',
    'DE-VAT-815473902',
    'DE',
    '2003-06-12',
    'SE',
    'CONSUMER_GOODS',
    'Pan-European consumer-goods holding with textile, food, and retail subsidiaries',
    'https://mercator-brands.eu',
    'Lukas Hoffmann',
    'lukas.hoffmann@mercator-brands.eu',
    '+49 69 9587 4400',
    'ACTIVE', 'APPROVED', 'LOW',
    NOW() - INTERVAL '8 years', NOW()
) ON CONFLICT DO NOTHING;

-- MNC 2 — Al Mawarid Trading Group (SAR · Saudi Arabia · Petrochemicals)
INSERT INTO corporates (
    id, corporate_id, legal_name, trade_name, registration_number, tax_id,
    incorporation_country, incorporation_date, legal_entity_type,
    industry_sector, business_description, website, primary_contact_name,
    primary_contact_email, primary_contact_phone, status, kyc_status,
    risk_rating, created_at, updated_at
) VALUES (
    'bb000002-0000-0000-0000-000000000002',
    'MNC-SAR-002',
    'Al Mawarid Trading Group JSC',
    'Al Mawarid',
    'CR-1010-998211',
    'SA-VAT-310123456700003',
    'SA',
    '1998-11-04',
    'JSC',
    'PETROCHEMICALS',
    'Gulf-region petrochemicals and oil-services group; physical trading across SA, AE, KW',
    'https://almawarid.sa',
    'Khalid Al Otaibi',
    'khalid.alotaibi@almawarid.sa',
    '+966 11 480 9300',
    'ACTIVE', 'APPROVED', 'MEDIUM',
    NOW() - INTERVAL '12 years', NOW()
) ON CONFLICT DO NOTHING;

-- MNC 3 — Albion Industrial PLC (GBP · United Kingdom · Engineering)
INSERT INTO corporates (
    id, corporate_id, legal_name, trade_name, registration_number, tax_id,
    incorporation_country, incorporation_date, legal_entity_type,
    industry_sector, business_description, website, primary_contact_name,
    primary_contact_email, primary_contact_phone, status, kyc_status,
    risk_rating, created_at, updated_at
) VALUES (
    'cc000003-0000-0000-0000-000000000003',
    'MNC-GBP-003',
    'Albion Industrial PLC',
    'Albion',
    'CRN-1985-04477120',
    'GB-VAT-528470015',
    'GB',
    '1985-02-18',
    'PLC',
    'INDUSTRIAL_MANUFACTURING',
    'Heavy engineering and precision manufacturing; LSE-listed; revenue split UK / Americas',
    'https://albion-industrial.co.uk',
    'Eleanor Whitcombe',
    'e.whitcombe@albion-industrial.co.uk',
    '+44 20 7460 8900',
    'ACTIVE', 'APPROVED', 'LOW',
    NOW() - INTERVAL '15 years', NOW()
) ON CONFLICT DO NOTHING;

-- MNC 4 — Helios Global Inc. (USD · United States · Software)
INSERT INTO corporates (
    id, corporate_id, legal_name, trade_name, registration_number, tax_id,
    incorporation_country, incorporation_date, legal_entity_type,
    industry_sector, business_description, website, primary_contact_name,
    primary_contact_email, primary_contact_phone, status, kyc_status,
    risk_rating, created_at, updated_at
) VALUES (
    'dd000004-0000-0000-0000-000000000004',
    'MNC-USD-004',
    'Helios Global Inc.',
    'Helios',
    'DE-CORP-2011-7733410',
    'US-EIN-46-2918375',
    'US',
    '2011-09-22',
    'CORP',
    'TECHNOLOGY',
    'Enterprise software and cloud infrastructure; HQ San Francisco, EMEA hub Dublin, APAC hub Singapore',
    'https://helios.global',
    'Maya Subramanian',
    'maya.s@helios.global',
    '+1 415 555 0142',
    'ACTIVE', 'APPROVED', 'LOW',
    NOW() - INTERVAL '7 years', NOW()
) ON CONFLICT DO NOTHING;

-- MNC 5 — Brato Logistics LLC (AED · United Arab Emirates · Logistics)
INSERT INTO corporates (
    id, corporate_id, legal_name, trade_name, registration_number, tax_id,
    incorporation_country, incorporation_date, legal_entity_type,
    industry_sector, business_description, website, primary_contact_name,
    primary_contact_email, primary_contact_phone, status, kyc_status,
    risk_rating, created_at, updated_at
) VALUES (
    'ee000005-0000-0000-0000-000000000005',
    'MNC-AED-005',
    'Brato Logistics LLC',
    'Brato',
    'DED-2014-552180',
    'AE-TRN-100923187800003',
    'AE',
    '2014-08-30',
    'LLC',
    'LOGISTICS',
    'Regional logistics and maritime services; UAE-headquartered with subsidiaries in SA and OM',
    'https://brato-logistics.ae',
    'Yusuf Al Marri',
    'yusuf.almarri@brato-logistics.ae',
    '+971 4 805 7700',
    'ACTIVE', 'APPROVED', 'LOW',
    NOW() - INTERVAL '5 years', NOW()
) ON CONFLICT DO NOTHING;

-- ============================================================================
-- 2. LEGAL ENTITIES — subsidiaries per MNC
-- ============================================================================
INSERT INTO legal_entities (
    id, corporate_id, entity_code, entity_name, short_name, country_code,
    jurisdiction, functional_currency, reporting_currency, entity_type,
    legal_form, is_treasury_center, can_hold_physical_accounts,
    can_participate_pooling, can_participate_netting, status, effective_from,
    created_at, updated_at
) VALUES
    -- MNC 1 EUR — Mercator (Germany parent + 2 subsidiaries)
    ('aa000001-1000-0000-0000-000000000001', 'aa000001-0000-0000-0000-000000000001',
     'MERC-DE', 'Mercator Brands Holding SE', 'Mercator DE', 'DE', 'Germany',
     'EUR', 'EUR', 'HOLDING', 'SE', true, true, true, true, 'ACTIVE',
     '2003-06-12', NOW() - INTERVAL '8 years', NOW()),
    ('aa000001-1000-0000-0000-000000000002', 'aa000001-0000-0000-0000-000000000001',
     'MERC-FR', 'Mercator France SAS', 'Mercator FR', 'FR', 'France',
     'EUR', 'EUR', 'SUBSIDIARY', 'SAS', false, true, true, true, 'ACTIVE',
     '2007-04-01', NOW() - INTERVAL '7 years', NOW()),
    ('aa000001-1000-0000-0000-000000000003', 'aa000001-0000-0000-0000-000000000001',
     'MERC-IT', 'Mercator Italia S.r.l.', 'Mercator IT', 'IT', 'Italy',
     'EUR', 'EUR', 'SUBSIDIARY', 'SRL', false, true, true, true, 'ACTIVE',
     '2010-09-15', NOW() - INTERVAL '6 years', NOW()),

    -- MNC 2 SAR — Al Mawarid (Saudi parent + UAE & Kuwait subs)
    ('bb000002-1000-0000-0000-000000000001', 'bb000002-0000-0000-0000-000000000002',
     'MAWR-SA', 'Al Mawarid Trading Group JSC', 'Mawarid SA', 'SA', 'Saudi Arabia',
     'SAR', 'SAR', 'HOLDING', 'JSC', true, true, true, true, 'ACTIVE',
     '1998-11-04', NOW() - INTERVAL '12 years', NOW()),
    ('bb000002-1000-0000-0000-000000000002', 'bb000002-0000-0000-0000-000000000002',
     'MAWR-AE', 'Al Mawarid Gulf FZE', 'Mawarid AE', 'AE', 'UAE',
     'USD', 'SAR', 'SUBSIDIARY', 'FZE', false, true, true, true, 'ACTIVE',
     '2005-03-20', NOW() - INTERVAL '10 years', NOW()),
    ('bb000002-1000-0000-0000-000000000003', 'bb000002-0000-0000-0000-000000000002',
     'MAWR-KW', 'Al Mawarid Trading Kuwait Co.', 'Mawarid KW', 'KW', 'Kuwait',
     'USD', 'SAR', 'SUBSIDIARY', 'WLL', false, true, true, true, 'ACTIVE',
     '2008-07-11', NOW() - INTERVAL '9 years', NOW()),

    -- MNC 3 GBP — Albion (UK parent + UK subsidiary + US subsidiary)
    ('cc000003-1000-0000-0000-000000000001', 'cc000003-0000-0000-0000-000000000003',
     'ALBN-GB', 'Albion Industrial PLC', 'Albion UK', 'GB', 'United Kingdom',
     'GBP', 'GBP', 'HOLDING', 'PLC', true, true, true, true, 'ACTIVE',
     '1985-02-18', NOW() - INTERVAL '15 years', NOW()),
    ('cc000003-1000-0000-0000-000000000002', 'cc000003-0000-0000-0000-000000000003',
     'ALBN-EN', 'Albion Engineering Ltd', 'Albion Eng', 'GB', 'United Kingdom',
     'GBP', 'GBP', 'SUBSIDIARY', 'LTD', false, true, true, true, 'ACTIVE',
     '1992-06-30', NOW() - INTERVAL '14 years', NOW()),
    ('cc000003-1000-0000-0000-000000000003', 'cc000003-0000-0000-0000-000000000003',
     'ALBN-US', 'Albion Americas Inc.', 'Albion US', 'US', 'Delaware, US',
     'USD', 'GBP', 'SUBSIDIARY', 'CORP', false, true, true, true, 'ACTIVE',
     '2001-11-15', NOW() - INTERVAL '13 years', NOW()),

    -- MNC 4 USD — Helios (US parent + UK & SG subs)
    ('dd000004-1000-0000-0000-000000000001', 'dd000004-0000-0000-0000-000000000004',
     'HEL-US',  'Helios Global Inc.',     'Helios US', 'US', 'Delaware, US',
     'USD', 'USD', 'HOLDING', 'CORP', true, true, true, true, 'ACTIVE',
     '2011-09-22', NOW() - INTERVAL '7 years', NOW()),
    ('dd000004-1000-0000-0000-000000000002', 'dd000004-0000-0000-0000-000000000004',
     'HEL-IE',  'Helios EMEA Ltd',         'Helios EMEA', 'IE', 'Ireland',
     'EUR', 'USD', 'SUBSIDIARY', 'LTD', false, true, true, true, 'ACTIVE',
     '2014-02-10', NOW() - INTERVAL '6 years', NOW()),
    ('dd000004-1000-0000-0000-000000000003', 'dd000004-0000-0000-0000-000000000004',
     'HEL-SG',  'Helios APAC Pte Ltd',     'Helios APAC', 'SG', 'Singapore',
     'SGD', 'USD', 'SUBSIDIARY', 'PTE', false, true, true, true, 'ACTIVE',
     '2016-05-04', NOW() - INTERVAL '5 years', NOW()),

    -- MNC 5 AED — Brato (UAE parent + SA & OM subsidiaries)
    ('ee000005-1000-0000-0000-000000000001', 'ee000005-0000-0000-0000-000000000005',
     'BRTO-AE', 'Brato Logistics LLC',          'Brato UAE', 'AE', 'UAE',
     'AED', 'AED', 'HOLDING', 'LLC', true, true, true, true, 'ACTIVE',
     '2014-08-30', NOW() - INTERVAL '5 years', NOW()),
    ('ee000005-1000-0000-0000-000000000002', 'ee000005-0000-0000-0000-000000000005',
     'BRTO-SA', 'Brato Logistics KSA',          'Brato KSA', 'SA', 'Saudi Arabia',
     'SAR', 'AED', 'SUBSIDIARY', 'LLC', false, true, true, true, 'ACTIVE',
     '2016-11-20', NOW() - INTERVAL '4 years', NOW()),
    ('ee000005-1000-0000-0000-000000000003', 'ee000005-0000-0000-0000-000000000005',
     'BRTO-OM', 'Brato Maritime Oman SAOC',     'Brato Oman', 'OM', 'Oman',
     'OMR', 'AED', 'SUBSIDIARY', 'SAOC', false, true, true, true, 'ACTIVE',
     '2018-03-08', NOW() - INTERVAL '3 years', NOW())
ON CONFLICT DO NOTHING;

-- ============================================================================
-- 3. PHYSICAL ACCOUNTS — multi-bank (3-4 banks per MNC)
-- ============================================================================
-- Each MNC's first physical account is the pool header (home currency, home
-- bank). The fourth account on each MNC is a cross-border/foreign-currency
-- account used as a sweep source.

INSERT INTO physical_accounts (
    id, corporate_id, account_number, account_name, account_type, iban,
    currency_code, bank_name, bank_code, bank_country, branch_code, branch_name,
    entity_code, entity_name, current_balance, available_balance, ledger_balance,
    status, pooling_eligible, pooling_enabled, sweep_eligible, sweep_enabled,
    sweep_role, opened_date, relationship_manager, created_at, updated_at
) VALUES
    -- ──────────── MNC 1 EUR — Mercator (Frankfurt / Paris / Milan) ────────
    ('aa000001-2000-0000-0000-000000000001', 'aa000001-0000-0000-0000-000000000001',
     '0070055544001', 'Mercator Frankfurt EUR Header', 'CURRENT',
     'DE89370400440532013000', 'EUR', 'Deutsche Bank AG', 'DEUTDEFFXXX', 'DE',
     'FFM001', 'Frankfurt Hauptwache', 'MERC-DE', 'Mercator Brands Holding SE',
     5125000.00, 5125000.00, 5125000.00, 'ACTIVE', true, true, true, false,
     'HEADER', '2003-07-01', 'Annika Bauer',
     NOW() - INTERVAL '8 years', NOW()),
    ('aa000001-2000-0000-0000-000000000002', 'aa000001-0000-0000-0000-000000000001',
     '0070066678002', 'Mercator Frankfurt EUR Secondary', 'CURRENT',
     'DE89370400440532013001', 'EUR', 'Commerzbank AG', 'COBADEFFXXX', 'DE',
     'FFM002', 'Frankfurt Innenstadt', 'MERC-DE', 'Mercator Brands Holding SE',
     2040000.00, 2040000.00, 2040000.00, 'ACTIVE', true, true, true, false,
     'PARTICIPANT', '2005-02-15', 'Annika Bauer',
     NOW() - INTERVAL '8 years', NOW()),
    ('aa000001-2000-0000-0000-000000000003', 'aa000001-0000-0000-0000-000000000001',
     '0058811199301', 'Mercator France EUR Operating', 'CURRENT',
     'FR7630004000031234567890143', 'EUR', 'BNP Paribas', 'BNPAFRPPXXX', 'FR',
     'PAR001', 'Paris Opéra', 'MERC-FR', 'Mercator France SAS',
     1485000.00, 1485000.00, 1485000.00, 'ACTIVE', true, true, true, true,
     'PARTICIPANT', '2007-05-10', 'Jean-Marc Leblanc',
     NOW() - INTERVAL '7 years', NOW()),
    ('aa000001-2000-0000-0000-000000000004', 'aa000001-0000-0000-0000-000000000001',
     '0033377700401', 'Mercator Italia EUR Operating', 'CURRENT',
     'IT60X0542811101000000123456', 'EUR', 'UniCredit S.p.A.', 'UNCRITMMXXX', 'IT',
     'MIL001', 'Milano Centro', 'MERC-IT', 'Mercator Italia S.r.l.',
     1212000.00, 1180000.00, 1212000.00, 'ACTIVE', true, true, true, true,
     'PARTICIPANT', '2010-10-01', 'Sofia Romano',
     NOW() - INTERVAL '6 years', NOW()),

    -- ──────────── MNC 2 SAR — Al Mawarid (Riyadh / Jeddah / Dammam / Dubai) ────
    ('bb000002-2000-0000-0000-000000000001', 'bb000002-0000-0000-0000-000000000002',
     'SA0388811001001', 'Al Mawarid Riyadh SAR Header', 'CURRENT',
     'SA0380000000608010167519', 'SAR', 'Al Rajhi Bank', 'RJHISARIXXX', 'SA',
     'RUH001', 'Riyadh King Fahd Road', 'MAWR-SA', 'Al Mawarid Trading Group JSC',
     12450000.00, 12450000.00, 12450000.00, 'ACTIVE', true, true, true, false,
     'HEADER', '1998-12-01', 'Faisal Al Harbi',
     NOW() - INTERVAL '12 years', NOW()),
    ('bb000002-2000-0000-0000-000000000002', 'bb000002-0000-0000-0000-000000000002',
     'SA0388822002002', 'Al Mawarid Jeddah SAR Operating', 'CURRENT',
     'SA1010000010608800167519', 'SAR', 'Saudi National Bank', 'NCBKSAJEXXX', 'SA',
     'JED001', 'Jeddah Tahliyah', 'MAWR-SA', 'Al Mawarid Trading Group JSC',
     8200000.00, 8200000.00, 8200000.00, 'ACTIVE', true, true, true, true,
     'PARTICIPANT', '2001-06-15', 'Faisal Al Harbi',
     NOW() - INTERVAL '11 years', NOW()),
    ('bb000002-2000-0000-0000-000000000003', 'bb000002-0000-0000-0000-000000000002',
     'SA0388833003003', 'Al Mawarid Dammam SAR Operating', 'CURRENT',
     'SA2020000020608900167519', 'SAR', 'Riyad Bank', 'RIBLSARIXXX', 'SA',
     'DMM001', 'Dammam King Saud Street', 'MAWR-SA', 'Al Mawarid Trading Group JSC',
     4015000.00, 4015000.00, 4015000.00, 'ACTIVE', true, true, true, true,
     'PARTICIPANT', '2003-11-20', 'Faisal Al Harbi',
     NOW() - INTERVAL '10 years', NOW()),
    ('bb000002-2000-0000-0000-000000000004', 'bb000002-0000-0000-0000-000000000002',
     'AE0388844004004', 'Al Mawarid Dubai USD Trading', 'CURRENT',
     'AE070338822334455667788', 'USD', 'Emirates NBD',  'EBILAEADXXX', 'AE',
     'DXB003', 'Dubai DIFC', 'MAWR-AE', 'Al Mawarid Gulf FZE',
     455000.00, 455000.00, 455000.00, 'ACTIVE', false, false, true, true,
     'PARTICIPANT', '2005-04-15', 'Mariam Al Suwaidi',
     NOW() - INTERVAL '10 years', NOW()),

    -- ──────────── MNC 3 GBP — Albion (London / Manchester / Birmingham / Boston) ──
    ('cc000003-2000-0000-0000-000000000001', 'cc000003-0000-0000-0000-000000000003',
     '12345601', 'Albion London GBP Header', 'CURRENT',
     'GB29LOYD30960412345601', 'GBP', 'Lloyds Bank plc', 'LOYDGB2LXXX', 'GB',
     'LON001', 'London Gracechurch Street', 'ALBN-GB', 'Albion Industrial PLC',
     8210000.00, 8210000.00, 8210000.00, 'ACTIVE', true, true, true, false,
     'HEADER', '1985-03-01', 'Henry Lawson',
     NOW() - INTERVAL '15 years', NOW()),
    ('cc000003-2000-0000-0000-000000000002', 'cc000003-0000-0000-0000-000000000003',
     '23456702', 'Albion Manchester GBP Operating', 'CURRENT',
     'GB29BARC20121723456702', 'GBP', 'Barclays Bank UK plc', 'BARCGB22XXX', 'GB',
     'MAN001', 'Manchester Spinningfields', 'ALBN-EN', 'Albion Engineering Ltd',
     3520000.00, 3520000.00, 3520000.00, 'ACTIVE', true, true, true, true,
     'PARTICIPANT', '1992-07-15', 'Henry Lawson',
     NOW() - INTERVAL '14 years', NOW()),
    ('cc000003-2000-0000-0000-000000000003', 'cc000003-0000-0000-0000-000000000003',
     '34567803', 'Albion Birmingham GBP Operating', 'CURRENT',
     'GB29HBUK40121734567803', 'GBP', 'HSBC UK Bank plc', 'HBUKGB4BXXX', 'GB',
     'BIR001', 'Birmingham Edgbaston', 'ALBN-EN', 'Albion Engineering Ltd',
     2040000.00, 2040000.00, 2040000.00, 'ACTIVE', true, true, true, true,
     'PARTICIPANT', '1995-04-20', 'Henry Lawson',
     NOW() - INTERVAL '13 years', NOW()),
    ('cc000003-2000-0000-0000-000000000004', 'cc000003-0000-0000-0000-000000000003',
     '45678904', 'Albion Boston USD Operating', 'CURRENT',
     'US33CITIUS33000045678904', 'USD', 'Citibank N.A.', 'CITIUS33XXX', 'US',
     'BOS001', 'Boston State Street', 'ALBN-US', 'Albion Americas Inc.',
     1820000.00, 1820000.00, 1820000.00, 'ACTIVE', false, false, true, true,
     'PARTICIPANT', '2001-12-01', 'Margaret Chen',
     NOW() - INTERVAL '13 years', NOW()),

    -- ──────────── MNC 4 USD — Helios (SF / NY / Boston / Singapore) ────────
    ('dd000004-2000-0000-0000-000000000001', 'dd000004-0000-0000-0000-000000000004',
     '9876543201', 'Helios SF USD Header', 'CURRENT',
     'US33CHAS33000098765432', 'USD', 'JPMorgan Chase Bank', 'CHASUS33XXX', 'US',
     'SFO001', 'San Francisco Embarcadero', 'HEL-US', 'Helios Global Inc.',
     15280000.00, 15280000.00, 15280000.00, 'ACTIVE', true, true, true, false,
     'HEADER', '2011-10-01', 'Daniel Park',
     NOW() - INTERVAL '7 years', NOW()),
    ('dd000004-2000-0000-0000-000000000002', 'dd000004-0000-0000-0000-000000000004',
     '8765432101', 'Helios NY USD Operating', 'CURRENT',
     'US33BOFA22000087654321', 'USD', 'Bank of America N.A.', 'BOFAUS3NXXX', 'US',
     'NYC001', 'New York Park Avenue', 'HEL-US', 'Helios Global Inc.',
     6045000.00, 6045000.00, 6045000.00, 'ACTIVE', true, true, true, true,
     'PARTICIPANT', '2013-03-15', 'Daniel Park',
     NOW() - INTERVAL '6 years', NOW()),
    ('dd000004-2000-0000-0000-000000000003', 'dd000004-0000-0000-0000-000000000004',
     '7654321001', 'Helios Boston USD Operating', 'CURRENT',
     'US33CITIUS33000076543210', 'USD', 'Citibank N.A.', 'CITIUS33XXX', 'US',
     'BOS002', 'Boston Financial District', 'HEL-US', 'Helios Global Inc.',
     4012000.00, 4012000.00, 4012000.00, 'ACTIVE', true, true, true, true,
     'PARTICIPANT', '2015-08-20', 'Daniel Park',
     NOW() - INTERVAL '5 years', NOW()),
    ('dd000004-2000-0000-0000-000000000004', 'dd000004-0000-0000-0000-000000000004',
     '6543210901', 'Helios APAC SGD Operating', 'CURRENT',
     'SG12SCBL11000065432109', 'SGD', 'Standard Chartered Bank', 'SCBLSGSGXXX', 'SG',
     'SIN001', 'Singapore Marina One', 'HEL-SG', 'Helios APAC Pte Ltd',
     2510000.00, 2510000.00, 2510000.00, 'ACTIVE', false, false, true, true,
     'PARTICIPANT', '2016-06-15', 'Wei Lin Tan',
     NOW() - INTERVAL '5 years', NOW()),

    -- ──────────── MNC 5 AED — Brato (Dubai / Abu Dhabi / Sharjah / Riyadh) ──
    ('ee000005-2000-0000-0000-000000000001', 'ee000005-0000-0000-0000-000000000005',
     'AE0322255501101', 'Brato Dubai AED Header', 'CURRENT',
     'AE070330111122223333444', 'AED', 'Emirates NBD', 'EBILAEADXXX', 'AE',
     'DXB004', 'Dubai Business Bay', 'BRTO-AE', 'Brato Logistics LLC',
     10120000.00, 10120000.00, 10120000.00, 'ACTIVE', true, true, true, false,
     'HEADER', '2014-09-15', 'Hessa Al Mansoori',
     NOW() - INTERVAL '5 years', NOW()),
    ('ee000005-2000-0000-0000-000000000002', 'ee000005-0000-0000-0000-000000000005',
     'AE0322266602202', 'Brato Abu Dhabi AED Operating', 'CURRENT',
     'AE070332111233344455566', 'AED', 'Emirates NBD', 'EBILAEADXXX', 'AE',
     'AUH002', 'Abu Dhabi Al Maryah Island', 'BRTO-AE', 'Brato Logistics LLC',
     4520000.00, 4520000.00, 4520000.00, 'ACTIVE', true, true, true, true,
     'PARTICIPANT', '2015-03-10', 'Hessa Al Mansoori',
     NOW() - INTERVAL '4 years', NOW()),
    ('ee000005-2000-0000-0000-000000000003', 'ee000005-0000-0000-0000-000000000005',
     'AE0322277703303', 'Brato Sharjah AED Operating', 'CURRENT',
     'AE070333222334455667788', 'AED', 'HSBC Bank Middle East', 'HBMEAEADXXX', 'AE',
     'SHJ001', 'Sharjah Al Khan', 'BRTO-AE', 'Brato Logistics LLC',
     2515000.00, 2515000.00, 2515000.00, 'ACTIVE', true, true, true, true,
     'PARTICIPANT', '2017-01-25', 'Hessa Al Mansoori',
     NOW() - INTERVAL '3 years', NOW()),
    ('ee000005-2000-0000-0000-000000000004', 'ee000005-0000-0000-0000-000000000005',
     'SA0388855505504', 'Brato KSA SAR Operating', 'CURRENT',
     'SA0480000000608010111222', 'SAR', 'Al Rajhi Bank',  'RJHISARIXXX', 'SA',
     'RUH002', 'Riyadh Al Olaya', 'BRTO-SA', 'Brato Logistics KSA',
     605000.00, 605000.00, 605000.00, 'ACTIVE', false, false, true, true,
     'PARTICIPANT', '2017-04-01', 'Abdullah Al Qahtani',
     NOW() - INTERVAL '3 years', NOW())
ON CONFLICT DO NOTHING;

-- ============================================================================
-- 4. UNIFIED PROGRAMS — one treasury program per MNC
-- ============================================================================
-- One treasury program per MNC parented at the corporate's pool-header
-- physical account. This is the program every shadow VA below attaches to.

INSERT INTO unified_programs (
    id, program_code, program_name, program_type, corporate_id,
    physical_account_id, currency_code, description, va_prefix, va_format,
    max_virtual_accounts, auto_reconciliation, settlement_frequency,
    min_balance_threshold, status, effective_from, created_at, updated_at
) VALUES
    ('aa000001-3000-0000-0000-000000000001', 'TRES-EUR-MERC', 'Mercator Treasury Program',
     'COLLECTION', 'aa000001-0000-0000-0000-000000000001',
     'aa000001-2000-0000-0000-000000000001', 'EUR',
     'Multi-bank treasury management for Mercator group EUR/GBP/USD exposures',
     'MERC', 'PREFIX_SEQUENTIAL', 100, true, 'DAILY', 0.00,
     'ACTIVE', '2003-07-01', NOW() - INTERVAL '8 years', NOW()),

    ('bb000002-3000-0000-0000-000000000001', 'TRES-SAR-MAWR', 'Al Mawarid Treasury Program',
     'COLLECTION', 'bb000002-0000-0000-0000-000000000002',
     'bb000002-2000-0000-0000-000000000001', 'SAR',
     'Multi-bank treasury for Al Mawarid SAR home pool + USD trading flows',
     'MAWR', 'PREFIX_SEQUENTIAL', 100, true, 'DAILY', 0.00,
     'ACTIVE', '1998-12-01', NOW() - INTERVAL '12 years', NOW()),

    ('cc000003-3000-0000-0000-000000000001', 'TRES-GBP-ALBN', 'Albion Treasury Program',
     'COLLECTION', 'cc000003-0000-0000-0000-000000000003',
     'cc000003-2000-0000-0000-000000000001', 'GBP',
     'Multi-bank treasury for Albion GBP home pool + USD Americas exposure',
     'ALBN', 'PREFIX_SEQUENTIAL', 100, true, 'DAILY', 0.00,
     'ACTIVE', '1985-03-01', NOW() - INTERVAL '15 years', NOW()),

    ('dd000004-3000-0000-0000-000000000001', 'TRES-USD-HEL',  'Helios Treasury Program',
     'COLLECTION', 'dd000004-0000-0000-0000-000000000004',
     'dd000004-2000-0000-0000-000000000001', 'USD',
     'Multi-bank treasury for Helios USD home pool + SGD APAC flows',
     'HEL',  'PREFIX_SEQUENTIAL', 100, true, 'DAILY', 0.00,
     'ACTIVE', '2011-10-01', NOW() - INTERVAL '7 years', NOW()),

    ('ee000005-3000-0000-0000-000000000001', 'TRES-AED-BRTO', 'Brato Treasury Program',
     'COLLECTION', 'ee000005-0000-0000-0000-000000000005',
     'ee000005-2000-0000-0000-000000000001', 'AED',
     'Multi-bank treasury for Brato AED home pool + SAR cross-border flows',
     'BRTO', 'PREFIX_SEQUENTIAL', 100, true, 'DAILY', 0.00,
     'ACTIVE', '2014-09-15', NOW() - INTERVAL '5 years', NOW())
ON CONFLICT DO NOTHING;

-- ============================================================================
-- 5. VIRTUAL ACCOUNTS — shadow VAs mirroring each physical account
-- ============================================================================
-- These are PHYSICAL_MIRROR shadows the multi-bank liquidity feature reads
-- from. Each VA carries its bank's SWIFT, the bank account number/IBAN, and
-- a snapshot bank balance (the multi-bank refresh job updates these in the
-- live system; for seed purposes we set them to match the physical account).

INSERT INTO virtual_accounts (
    id, corporate_id, program_id, physical_account_id, linked_physical_account_id,
    va_number, va_name, currency_code, current_balance, available_balance,
    status, owning_entity_id, owning_entity_code, account_type, account_category,
    bank_balance, bank_available_balance, bank_balance_at, bank_account_number,
    bank_iban, bank_swift, bank_name, balance_data_source,
    metadata, created_at, updated_at
) VALUES
    -- MNC 1 EUR — Mercator shadows
    ('aa000001-4000-0000-0000-000000000001', 'aa000001-0000-0000-0000-000000000001',
     'aa000001-3000-0000-0000-000000000001', 'aa000001-2000-0000-0000-000000000001',
     'aa000001-2000-0000-0000-000000000001', 'MERC0000001', 'Shadow · Deutsche Bank EUR (Header)',
     'EUR', 5125000.00, 5125000.00, 'ACTIVE',
     'aa000001-1000-0000-0000-000000000001', 'MERC-DE', 'VIRTUAL', 'PHYSICAL_MIRROR',
     5125000.00, 5125000.00, NOW() - INTERVAL '15 minutes', '0070055544001',
     'DE89370400440532013000', 'DEUTDEFFXXX', 'Deutsche Bank AG', 'CORE_BANKING',
     '{"home_bank": true, "pool_role": "HEADER"}', NOW() - INTERVAL '8 years', NOW()),
    ('aa000001-4000-0000-0000-000000000002', 'aa000001-0000-0000-0000-000000000001',
     'aa000001-3000-0000-0000-000000000001', 'aa000001-2000-0000-0000-000000000002',
     'aa000001-2000-0000-0000-000000000002', 'MERC0000002', 'Shadow · Commerzbank EUR',
     'EUR', 2040000.00, 2040000.00, 'ACTIVE',
     'aa000001-1000-0000-0000-000000000001', 'MERC-DE', 'VIRTUAL', 'PHYSICAL_MIRROR',
     2040000.00, 2040000.00, NOW() - INTERVAL '20 minutes', '0070066678002',
     'DE89370400440532013001', 'COBADEFFXXX', 'Commerzbank AG', 'SWIFT_MT940',
     '{"pool_role": "MEMBER"}', NOW() - INTERVAL '8 years', NOW()),
    ('aa000001-4000-0000-0000-000000000003', 'aa000001-0000-0000-0000-000000000001',
     'aa000001-3000-0000-0000-000000000001', 'aa000001-2000-0000-0000-000000000003',
     'aa000001-2000-0000-0000-000000000003', 'MERC0000003', 'Shadow · BNP Paribas EUR',
     'EUR', 1485000.00, 1485000.00, 'ACTIVE',
     'aa000001-1000-0000-0000-000000000002', 'MERC-FR', 'VIRTUAL', 'PHYSICAL_MIRROR',
     1485000.00, 1485000.00, NOW() - INTERVAL '45 minutes', '0058811199301',
     'FR7630004000031234567890143', 'BNPAFRPPXXX', 'BNP Paribas', 'SWIFT_MT940',
     '{"sweep_source": true, "pool_role": "MEMBER"}', NOW() - INTERVAL '7 years', NOW()),
    ('aa000001-4000-0000-0000-000000000004', 'aa000001-0000-0000-0000-000000000001',
     'aa000001-3000-0000-0000-000000000001', 'aa000001-2000-0000-0000-000000000004',
     'aa000001-2000-0000-0000-000000000004', 'MERC0000004', 'Shadow · UniCredit EUR',
     'EUR', 1212000.00, 1180000.00, 'ACTIVE',
     'aa000001-1000-0000-0000-000000000003', 'MERC-IT', 'VIRTUAL', 'PHYSICAL_MIRROR',
     1212000.00, 1180000.00, NOW() - INTERVAL '1 hour', '0033377700401',
     'IT60X0542811101000000123456', 'UNCRITMMXXX', 'UniCredit S.p.A.', 'SWIFT_MT940',
     '{"sweep_source": true, "pool_role": "MEMBER"}', NOW() - INTERVAL '6 years', NOW()),

    -- MNC 2 SAR — Al Mawarid shadows
    ('bb000002-4000-0000-0000-000000000001', 'bb000002-0000-0000-0000-000000000002',
     'bb000002-3000-0000-0000-000000000001', 'bb000002-2000-0000-0000-000000000001',
     'bb000002-2000-0000-0000-000000000001', 'MAWR0000001', 'Shadow · Al Rajhi SAR (Header)',
     'SAR', 12450000.00, 12450000.00, 'ACTIVE',
     'bb000002-1000-0000-0000-000000000001', 'MAWR-SA', 'VIRTUAL', 'PHYSICAL_MIRROR',
     12450000.00, 12450000.00, NOW() - INTERVAL '10 minutes', 'SA0388811001001',
     'SA0380000000608010167519', 'RJHISARIXXX', 'Al Rajhi Bank', 'CORE_BANKING',
     '{"home_bank": true, "pool_role": "HEADER"}', NOW() - INTERVAL '12 years', NOW()),
    ('bb000002-4000-0000-0000-000000000002', 'bb000002-0000-0000-0000-000000000002',
     'bb000002-3000-0000-0000-000000000001', 'bb000002-2000-0000-0000-000000000002',
     'bb000002-2000-0000-0000-000000000002', 'MAWR0000002', 'Shadow · SNB SAR',
     'SAR', 8200000.00, 8200000.00, 'ACTIVE',
     'bb000002-1000-0000-0000-000000000001', 'MAWR-SA', 'VIRTUAL', 'PHYSICAL_MIRROR',
     8200000.00, 8200000.00, NOW() - INTERVAL '25 minutes', 'SA0388822002002',
     'SA1010000010608800167519', 'NCBKSAJEXXX', 'Saudi National Bank', 'SWIFT_MT940',
     '{"pool_role": "MEMBER"}', NOW() - INTERVAL '11 years', NOW()),
    ('bb000002-4000-0000-0000-000000000003', 'bb000002-0000-0000-0000-000000000002',
     'bb000002-3000-0000-0000-000000000001', 'bb000002-2000-0000-0000-000000000003',
     'bb000002-2000-0000-0000-000000000003', 'MAWR0000003', 'Shadow · Riyad Bank SAR',
     'SAR', 4015000.00, 4015000.00, 'ACTIVE',
     'bb000002-1000-0000-0000-000000000001', 'MAWR-SA', 'VIRTUAL', 'PHYSICAL_MIRROR',
     4015000.00, 4015000.00, NOW() - INTERVAL '40 minutes', 'SA0388833003003',
     'SA2020000020608900167519', 'RIBLSARIXXX', 'Riyad Bank', 'SWIFT_MT940',
     '{"sweep_source": true, "pool_role": "MEMBER"}', NOW() - INTERVAL '10 years', NOW()),
    ('bb000002-4000-0000-0000-000000000004', 'bb000002-0000-0000-0000-000000000002',
     'bb000002-3000-0000-0000-000000000001', 'bb000002-2000-0000-0000-000000000004',
     'bb000002-2000-0000-0000-000000000004', 'MAWR0000004', 'Shadow · Emirates NBD USD',
     'USD', 455000.00, 455000.00, 'ACTIVE',
     'bb000002-1000-0000-0000-000000000002', 'MAWR-AE', 'VIRTUAL', 'PHYSICAL_MIRROR',
     455000.00, 455000.00, NOW() - INTERVAL '2 hours', 'AE0388844004004',
     'AE070338822334455667788', 'EBILAEADXXX', 'Emirates NBD', 'SWIFT_MT940',
     '{"sweep_source": true, "cross_border": true}', NOW() - INTERVAL '10 years', NOW()),

    -- MNC 3 GBP — Albion shadows
    ('cc000003-4000-0000-0000-000000000001', 'cc000003-0000-0000-0000-000000000003',
     'cc000003-3000-0000-0000-000000000001', 'cc000003-2000-0000-0000-000000000001',
     'cc000003-2000-0000-0000-000000000001', 'ALBN0000001', 'Shadow · Lloyds GBP (Header)',
     'GBP', 8210000.00, 8210000.00, 'ACTIVE',
     'cc000003-1000-0000-0000-000000000001', 'ALBN-GB', 'VIRTUAL', 'PHYSICAL_MIRROR',
     8210000.00, 8210000.00, NOW() - INTERVAL '12 minutes', '12345601',
     'GB29LOYD30960412345601', 'LOYDGB2LXXX', 'Lloyds Bank plc', 'CORE_BANKING',
     '{"home_bank": true, "pool_role": "HEADER"}', NOW() - INTERVAL '15 years', NOW()),
    ('cc000003-4000-0000-0000-000000000002', 'cc000003-0000-0000-0000-000000000003',
     'cc000003-3000-0000-0000-000000000001', 'cc000003-2000-0000-0000-000000000002',
     'cc000003-2000-0000-0000-000000000002', 'ALBN0000002', 'Shadow · Barclays GBP',
     'GBP', 3520000.00, 3520000.00, 'ACTIVE',
     'cc000003-1000-0000-0000-000000000002', 'ALBN-EN', 'VIRTUAL', 'PHYSICAL_MIRROR',
     3520000.00, 3520000.00, NOW() - INTERVAL '30 minutes', '23456702',
     'GB29BARC20121723456702', 'BARCGB22XXX', 'Barclays Bank UK plc', 'SWIFT_MT940',
     '{"sweep_source": true, "pool_role": "MEMBER"}', NOW() - INTERVAL '14 years', NOW()),
    ('cc000003-4000-0000-0000-000000000003', 'cc000003-0000-0000-0000-000000000003',
     'cc000003-3000-0000-0000-000000000001', 'cc000003-2000-0000-0000-000000000003',
     'cc000003-2000-0000-0000-000000000003', 'ALBN0000003', 'Shadow · HSBC UK GBP',
     'GBP', 2040000.00, 2040000.00, 'ACTIVE',
     'cc000003-1000-0000-0000-000000000002', 'ALBN-EN', 'VIRTUAL', 'PHYSICAL_MIRROR',
     2040000.00, 2040000.00, NOW() - INTERVAL '50 minutes', '34567803',
     'GB29HBUK40121734567803', 'HBUKGB4BXXX', 'HSBC UK Bank plc', 'SWIFT_MT940',
     '{"sweep_source": true, "pool_role": "MEMBER"}', NOW() - INTERVAL '13 years', NOW()),
    ('cc000003-4000-0000-0000-000000000004', 'cc000003-0000-0000-0000-000000000003',
     'cc000003-3000-0000-0000-000000000001', 'cc000003-2000-0000-0000-000000000004',
     'cc000003-2000-0000-0000-000000000004', 'ALBN0000004', 'Shadow · Citibank USD (Boston)',
     'USD', 1820000.00, 1820000.00, 'ACTIVE',
     'cc000003-1000-0000-0000-000000000003', 'ALBN-US', 'VIRTUAL', 'PHYSICAL_MIRROR',
     1820000.00, 1820000.00, NOW() - INTERVAL '90 minutes', '45678904',
     'US33CITIUS33000045678904', 'CITIUS33XXX', 'Citibank N.A.', 'SWIFT_MT940',
     '{"sweep_source": true, "cross_border": true}', NOW() - INTERVAL '13 years', NOW()),

    -- MNC 4 USD — Helios shadows
    ('dd000004-4000-0000-0000-000000000001', 'dd000004-0000-0000-0000-000000000004',
     'dd000004-3000-0000-0000-000000000001', 'dd000004-2000-0000-0000-000000000001',
     'dd000004-2000-0000-0000-000000000001', 'HEL0000001',  'Shadow · JPMorgan USD (Header)',
     'USD', 15280000.00, 15280000.00, 'ACTIVE',
     'dd000004-1000-0000-0000-000000000001', 'HEL-US', 'VIRTUAL', 'PHYSICAL_MIRROR',
     15280000.00, 15280000.00, NOW() - INTERVAL '8 minutes', '9876543201',
     'US33CHAS33000098765432', 'CHASUS33XXX', 'JPMorgan Chase Bank', 'CORE_BANKING',
     '{"home_bank": true, "pool_role": "HEADER"}', NOW() - INTERVAL '7 years', NOW()),
    ('dd000004-4000-0000-0000-000000000002', 'dd000004-0000-0000-0000-000000000004',
     'dd000004-3000-0000-0000-000000000001', 'dd000004-2000-0000-0000-000000000002',
     'dd000004-2000-0000-0000-000000000002', 'HEL0000002',  'Shadow · Bank of America USD',
     'USD', 6045000.00, 6045000.00, 'ACTIVE',
     'dd000004-1000-0000-0000-000000000001', 'HEL-US', 'VIRTUAL', 'PHYSICAL_MIRROR',
     6045000.00, 6045000.00, NOW() - INTERVAL '18 minutes', '8765432101',
     'US33BOFA22000087654321', 'BOFAUS3NXXX', 'Bank of America N.A.', 'SWIFT_MT940',
     '{"sweep_source": true, "pool_role": "MEMBER"}', NOW() - INTERVAL '6 years', NOW()),
    ('dd000004-4000-0000-0000-000000000003', 'dd000004-0000-0000-0000-000000000004',
     'dd000004-3000-0000-0000-000000000001', 'dd000004-2000-0000-0000-000000000003',
     'dd000004-2000-0000-0000-000000000003', 'HEL0000003',  'Shadow · Citibank USD (NYC)',
     'USD', 4012000.00, 4012000.00, 'ACTIVE',
     'dd000004-1000-0000-0000-000000000001', 'HEL-US', 'VIRTUAL', 'PHYSICAL_MIRROR',
     4012000.00, 4012000.00, NOW() - INTERVAL '35 minutes', '7654321001',
     'US33CITIUS33000076543210', 'CITIUS33XXX', 'Citibank N.A.', 'SWIFT_MT940',
     '{"sweep_source": true, "pool_role": "MEMBER"}', NOW() - INTERVAL '5 years', NOW()),
    ('dd000004-4000-0000-0000-000000000004', 'dd000004-0000-0000-0000-000000000004',
     'dd000004-3000-0000-0000-000000000001', 'dd000004-2000-0000-0000-000000000004',
     'dd000004-2000-0000-0000-000000000004', 'HEL0000004',  'Shadow · StanChart SGD',
     'SGD', 2510000.00, 2510000.00, 'ACTIVE',
     'dd000004-1000-0000-0000-000000000003', 'HEL-SG', 'VIRTUAL', 'PHYSICAL_MIRROR',
     2510000.00, 2510000.00, NOW() - INTERVAL '3 hours', '6543210901',
     'SG12SCBL11000065432109', 'SCBLSGSGXXX', 'Standard Chartered Bank', 'SWIFT_MT940',
     '{"sweep_source": true, "cross_border": true}', NOW() - INTERVAL '5 years', NOW()),

    -- MNC 5 AED — Brato shadows
    ('ee000005-4000-0000-0000-000000000001', 'ee000005-0000-0000-0000-000000000005',
     'ee000005-3000-0000-0000-000000000001', 'ee000005-2000-0000-0000-000000000001',
     'ee000005-2000-0000-0000-000000000001', 'BRTO0000001', 'Shadow · Emirates NBD AED (Header)',
     'AED', 10120000.00, 10120000.00, 'ACTIVE',
     'ee000005-1000-0000-0000-000000000001', 'BRTO-AE', 'VIRTUAL', 'PHYSICAL_MIRROR',
     10120000.00, 10120000.00, NOW() - INTERVAL '5 minutes', 'AE0322255501101',
     'AE070330111122223333444', 'EBILAEADXXX', 'Emirates NBD', 'CORE_BANKING',
     '{"home_bank": true, "pool_role": "HEADER"}', NOW() - INTERVAL '5 years', NOW()),
    ('ee000005-4000-0000-0000-000000000002', 'ee000005-0000-0000-0000-000000000005',
     'ee000005-3000-0000-0000-000000000001', 'ee000005-2000-0000-0000-000000000002',
     'ee000005-2000-0000-0000-000000000002', 'BRTO0000002', 'Shadow · Emirates NBD AED',
     'AED', 4520000.00, 4520000.00, 'ACTIVE',
     'ee000005-1000-0000-0000-000000000001', 'BRTO-AE', 'VIRTUAL', 'PHYSICAL_MIRROR',
     4520000.00, 4520000.00, NOW() - INTERVAL '22 minutes', 'AE0322266602202',
     'AE070332111233344455566', 'EBILAEADXXX', 'Emirates NBD', 'CORE_BANKING',
     '{"sweep_source": true, "pool_role": "MEMBER"}', NOW() - INTERVAL '4 years', NOW()),
    ('ee000005-4000-0000-0000-000000000003', 'ee000005-0000-0000-0000-000000000005',
     'ee000005-3000-0000-0000-000000000001', 'ee000005-2000-0000-0000-000000000003',
     'ee000005-2000-0000-0000-000000000003', 'BRTO0000003', 'Shadow · HSBC UAE AED',
     'AED', 2515000.00, 2515000.00, 'ACTIVE',
     'ee000005-1000-0000-0000-000000000001', 'BRTO-AE', 'VIRTUAL', 'PHYSICAL_MIRROR',
     2515000.00, 2515000.00, NOW() - INTERVAL '55 minutes', 'AE0322277703303',
     'AE070333222334455667788', 'HBMEAEADXXX', 'HSBC Bank Middle East', 'SWIFT_MT940',
     '{"sweep_source": true, "pool_role": "MEMBER"}', NOW() - INTERVAL '3 years', NOW()),
    ('ee000005-4000-0000-0000-000000000004', 'ee000005-0000-0000-0000-000000000005',
     'ee000005-3000-0000-0000-000000000001', 'ee000005-2000-0000-0000-000000000004',
     'ee000005-2000-0000-0000-000000000004', 'BRTO0000004', 'Shadow · Al Rajhi SAR (Brato KSA)',
     'SAR', 605000.00, 605000.00, 'ACTIVE',
     'ee000005-1000-0000-0000-000000000002', 'BRTO-SA', 'VIRTUAL', 'PHYSICAL_MIRROR',
     605000.00, 605000.00, NOW() - INTERVAL '2 hours', 'SA0388855505504',
     'SA0480000000608010111222', 'RJHISARIXXX', 'Al Rajhi Bank', 'SWIFT_MT940',
     '{"sweep_source": true, "cross_border": true}', NOW() - INTERVAL '3 years', NOW())
ON CONFLICT DO NOTHING;

-- ----------------------------------------------------------------------------
-- 5b. SHADOW BACK-POINTER — set physical_accounts.shadow_va_id from the VA
-- ----------------------------------------------------------------------------
-- The shadow relationship is modelled bidirectionally:
--   - virtual_accounts.linked_physical_account_id  →  physical_accounts.id
--   - physical_accounts.shadow_va_id               →  virtual_accounts.id
--
-- Section 5 set the forward link (VA → physical); this step sets the
-- inverse so the PhysicalAccountsPage's "X accounts not linked to
-- hierarchy" badge clears and the multi-bank liquidity view can resolve
-- "this physical account's shadow" without scanning all VAs.
--
-- Idempotent: deterministic SET; safe to re-run.

UPDATE physical_accounts pa
SET    shadow_va_id = va.id
FROM   virtual_accounts va
WHERE  va.linked_physical_account_id = pa.id
  AND  va.account_category = 'PHYSICAL_MIRROR'
  AND  pa.corporate_id IN (
       'aa000001-0000-0000-0000-000000000001',  -- Mercator
       'bb000002-0000-0000-0000-000000000002',  -- Al Mawarid
       'cc000003-0000-0000-0000-000000000003',  -- Albion
       'dd000004-0000-0000-0000-000000000004',  -- Helios
       'ee000005-0000-0000-0000-000000000005'   -- Brato
  );

-- ============================================================================
-- 6. NOTIONAL POOLS — one home-currency pool per MNC
-- ============================================================================
-- interest_rate is an ANNUAL PERCENT: NotionalPoolService.calculateInterest
-- divides by 36500, so 3.5 = 3.5%/yr. Store the percent number (5.1000),
-- NOT the decimal fraction (0.0510) — the fraction under-prices interest
-- 100× on the live engine AND the simulator. (Corrected 2026-05-16.)
INSERT INTO notional_pools (
    id, pool_reference, pool_name, pool_currency, target_balance, total_balance,
    member_count, interest_rate, interest_calculation_method, interest_savings_ytd,
    status, effective_from, last_calculation_date, created_at, updated_at
) VALUES
    ('aa000001-5000-0000-0000-000000000001', 'POOL-MERC-EUR', 'Mercator EUR Notional Pool',
     'EUR', 0.00, 9862000.00, 4, 2.5000, 'DAILY_AVERAGE', 184000.00,
     'ACTIVE', '2010-01-01', CURRENT_DATE - 1, NOW() - INTERVAL '7 years', NOW()),

    ('bb000002-5000-0000-0000-000000000001', 'POOL-MAWR-SAR', 'Al Mawarid SAR Notional Pool',
     'SAR', 0.00, 24665000.00, 3, 4.1000, 'DAILY_AVERAGE', 612000.00,
     'ACTIVE', '2005-01-01', CURRENT_DATE - 1, NOW() - INTERVAL '10 years', NOW()),

    ('cc000003-5000-0000-0000-000000000001', 'POOL-ALBN-GBP', 'Albion GBP Notional Pool',
     'GBP', 0.00, 13770000.00, 3, 4.7500, 'DAILY_AVERAGE', 392000.00,
     'ACTIVE', '2010-04-01', CURRENT_DATE - 1, NOW() - INTERVAL '10 years', NOW()),

    ('dd000004-5000-0000-0000-000000000001', 'POOL-HEL-USD',  'Helios USD Notional Pool',
     'USD', 0.00, 25337000.00, 3, 5.1000, 'DAILY_AVERAGE', 798000.00,
     'ACTIVE', '2013-07-01', CURRENT_DATE - 1, NOW() - INTERVAL '5 years', NOW()),

    ('ee000005-5000-0000-0000-000000000001', 'POOL-BRTO-AED', 'Brato AED Notional Pool',
     'AED', 0.00, 17155000.00, 3, 3.2000, 'DAILY_AVERAGE', 165000.00,
     'ACTIVE', '2017-01-01', CURRENT_DATE - 1, NOW() - INTERVAL '3 years', NOW())
ON CONFLICT (pool_reference) DO NOTHING;

-- ============================================================================
-- 7. POOL MEMBERS — three to four members per pool (header + members)
-- ============================================================================
-- The live schema's `pool_members` table (unified in V4 from V3's earlier
-- `notional_pool_members`) is much leaner than the V3 design: no
-- participant_id FK to ihb_participants, no per-member rate overrides, no
-- in-pool balance currency conversion. The simpler shape is intentional —
-- the rate config + interest tracking moved to ihb_configurations and the
-- per-currency conversion moved into a calculated read-model.

INSERT INTO pool_members (
    id, pool_id, account_id, account_number, entity_code, entity_name,
    current_balance, contribution_percent, interest_allocation,
    status, joined_date, created_at, updated_at, created_by
) VALUES
    -- MNC 1 EUR — Mercator (header + 3 members)
    ('aa000001-6000-0000-0000-000000000001', 'aa000001-5000-0000-0000-000000000001',
     'aa000001-4000-0000-0000-000000000001', 'MERC0000001', 'MERC-DE', 'Mercator Brands Holding SE',
     5125000.00, 52.00, 23100.00, 'ACTIVE', CURRENT_DATE - INTERVAL '7 years',
     NOW() - INTERVAL '7 years', NOW(), 'system'),
    ('aa000001-6000-0000-0000-000000000002', 'aa000001-5000-0000-0000-000000000001',
     'aa000001-4000-0000-0000-000000000002', 'MERC0000002', 'MERC-DE', 'Mercator Brands Holding SE',
     2040000.00, 21.00,  8650.00, 'ACTIVE', CURRENT_DATE - INTERVAL '7 years',
     NOW() - INTERVAL '7 years', NOW(), 'system'),
    ('aa000001-6000-0000-0000-000000000003', 'aa000001-5000-0000-0000-000000000001',
     'aa000001-4000-0000-0000-000000000003', 'MERC0000003', 'MERC-FR', 'Mercator France SAS',
     1485000.00, 15.00,  6210.00, 'ACTIVE', CURRENT_DATE - INTERVAL '6 years',
     NOW() - INTERVAL '6 years', NOW(), 'system'),
    ('aa000001-6000-0000-0000-000000000004', 'aa000001-5000-0000-0000-000000000001',
     'aa000001-4000-0000-0000-000000000004', 'MERC0000004', 'MERC-IT', 'Mercator Italia S.r.l.',
     1212000.00, 12.00,  5080.00, 'ACTIVE', CURRENT_DATE - INTERVAL '5 years',
     NOW() - INTERVAL '5 years', NOW(), 'system'),

    -- MNC 2 SAR — Al Mawarid (header + 2 members)
    ('bb000002-6000-0000-0000-000000000001', 'bb000002-5000-0000-0000-000000000001',
     'bb000002-4000-0000-0000-000000000001', 'MAWR0000001', 'MAWR-SA', 'Al Mawarid Trading Group JSC',
     12450000.00, 50.50, 71450.00, 'ACTIVE', CURRENT_DATE - INTERVAL '10 years',
     NOW() - INTERVAL '10 years', NOW(), 'system'),
    ('bb000002-6000-0000-0000-000000000002', 'bb000002-5000-0000-0000-000000000001',
     'bb000002-4000-0000-0000-000000000002', 'MAWR0000002', 'MAWR-SA', 'Al Mawarid Trading Group JSC',
     8200000.00, 33.25, 46930.00, 'ACTIVE', CURRENT_DATE - INTERVAL '10 years',
     NOW() - INTERVAL '10 years', NOW(), 'system'),
    ('bb000002-6000-0000-0000-000000000003', 'bb000002-5000-0000-0000-000000000001',
     'bb000002-4000-0000-0000-000000000003', 'MAWR0000003', 'MAWR-SA', 'Al Mawarid Trading Group JSC',
     4015000.00, 16.25, 22980.00, 'ACTIVE', CURRENT_DATE - INTERVAL '9 years',
     NOW() - INTERVAL '9 years', NOW(), 'system'),

    -- MNC 3 GBP — Albion (header + 2 members)
    ('cc000003-6000-0000-0000-000000000001', 'cc000003-5000-0000-0000-000000000001',
     'cc000003-4000-0000-0000-000000000001', 'ALBN0000001', 'ALBN-GB', 'Albion Industrial PLC',
     8210000.00, 59.60, 49530.00, 'ACTIVE', CURRENT_DATE - INTERVAL '10 years',
     NOW() - INTERVAL '10 years', NOW(), 'system'),
    ('cc000003-6000-0000-0000-000000000002', 'cc000003-5000-0000-0000-000000000001',
     'cc000003-4000-0000-0000-000000000002', 'ALBN0000002', 'ALBN-EN', 'Albion Engineering Ltd',
     3520000.00, 25.60, 21240.00, 'ACTIVE', CURRENT_DATE - INTERVAL '10 years',
     NOW() - INTERVAL '10 years', NOW(), 'system'),
    ('cc000003-6000-0000-0000-000000000003', 'cc000003-5000-0000-0000-000000000001',
     'cc000003-4000-0000-0000-000000000003', 'ALBN0000003', 'ALBN-EN', 'Albion Engineering Ltd',
     2040000.00, 14.80, 12320.00, 'ACTIVE', CURRENT_DATE - INTERVAL '9 years',
     NOW() - INTERVAL '9 years', NOW(), 'system'),

    -- MNC 4 USD — Helios (header + 2 members)
    ('dd000004-6000-0000-0000-000000000001', 'dd000004-5000-0000-0000-000000000001',
     'dd000004-4000-0000-0000-000000000001', 'HEL0000001',  'HEL-US', 'Helios Global Inc.',
     15280000.00, 60.30, 99230.00, 'ACTIVE', CURRENT_DATE - INTERVAL '5 years',
     NOW() - INTERVAL '5 years', NOW(), 'system'),
    ('dd000004-6000-0000-0000-000000000002', 'dd000004-5000-0000-0000-000000000001',
     'dd000004-4000-0000-0000-000000000002', 'HEL0000002',  'HEL-US', 'Helios Global Inc.',
     6045000.00, 23.85, 39280.00, 'ACTIVE', CURRENT_DATE - INTERVAL '5 years',
     NOW() - INTERVAL '5 years', NOW(), 'system'),
    ('dd000004-6000-0000-0000-000000000003', 'dd000004-5000-0000-0000-000000000001',
     'dd000004-4000-0000-0000-000000000003', 'HEL0000003',  'HEL-US', 'Helios Global Inc.',
     4012000.00, 15.85, 26010.00, 'ACTIVE', CURRENT_DATE - INTERVAL '4 years',
     NOW() - INTERVAL '4 years', NOW(), 'system'),

    -- MNC 5 AED — Brato (header + 2 members)
    ('ee000005-6000-0000-0000-000000000001', 'ee000005-5000-0000-0000-000000000001',
     'ee000005-4000-0000-0000-000000000001', 'BRTO0000001', 'BRTO-AE', 'Brato Logistics LLC',
     10120000.00, 59.00, 22600.00, 'ACTIVE', CURRENT_DATE - INTERVAL '3 years',
     NOW() - INTERVAL '3 years', NOW(), 'system'),
    ('ee000005-6000-0000-0000-000000000002', 'ee000005-5000-0000-0000-000000000001',
     'ee000005-4000-0000-0000-000000000002', 'BRTO0000002', 'BRTO-AE', 'Brato Logistics LLC',
     4520000.00, 26.35, 10090.00, 'ACTIVE', CURRENT_DATE - INTERVAL '3 years',
     NOW() - INTERVAL '3 years', NOW(), 'system'),
    ('ee000005-6000-0000-0000-000000000003', 'ee000005-5000-0000-0000-000000000001',
     'ee000005-4000-0000-0000-000000000003', 'BRTO0000003', 'BRTO-AE', 'Brato Logistics LLC',
     2515000.00, 14.65,  5610.00, 'ACTIVE', CURRENT_DATE - INTERVAL '2 years',
     NOW() - INTERVAL '2 years', NOW(), 'system')
ON CONFLICT DO NOTHING;

-- ============================================================================
-- 8. SWEEP RULES — two per MNC (mix of ZBA / TARGET_BALANCE / THRESHOLD /
--    PERCENTAGE so the UI demos all four sweep types)
-- ============================================================================
INSERT INTO sweep_rules (
    id, rule_reference, rule_name, sweep_type, target_account_id,
    target_account_number, target_entity_code, target_amount,
    threshold_min, threshold_max, percentage,
    frequency, execution_time, priority, currency_code, status,
    total_swept, execution_count, last_execution, next_execution,
    created_at, updated_at, created_by
) VALUES
    -- MNC 1 EUR — Mercator: ZBA from FR; TARGET_BALANCE on IT
    ('aa000001-7000-0000-0000-000000000001', 'SWP-MERC-001', 'Mercator FR EUR ZBA → DE Header',
     'ZERO_BALANCE',          'aa000001-4000-0000-0000-000000000001', 'MERC0000001', 'MERC-DE',
     NULL, NULL, NULL, NULL, 'DAILY', '18:00:00', 1, 'EUR', 'ACTIVE',
     185000000.00, 1024, NOW() - INTERVAL '1 day' + TIME '18:00:00',
     NOW() + INTERVAL '4 hours', NOW() - INTERVAL '4 years', NOW(), 'system'),
    ('aa000001-7000-0000-0000-000000000002', 'SWP-MERC-002', 'Mercator IT EUR Target 100k → DE',
     'TARGET_BALANCE','aa000001-4000-0000-0000-000000000001', 'MERC0000001', 'MERC-DE',
     100000.00, NULL, NULL, NULL, 'DAILY', '17:00:00', 2, 'EUR', 'ACTIVE',
     94000000.00, 1018, NOW() - INTERVAL '1 day' + TIME '17:00:00',
     NOW() + INTERVAL '3 hours', NOW() - INTERVAL '4 years', NOW(), 'system'),

    -- MNC 2 SAR — Al Mawarid: THRESHOLD from Jeddah; ZBA from Dammam
    ('bb000002-7000-0000-0000-000000000001', 'SWP-MAWR-001', 'Al Mawarid Jeddah > 10M → Riyadh',
     'THRESHOLD',    'bb000002-4000-0000-0000-000000000001', 'MAWR0000001', 'MAWR-SA',
     NULL, NULL, 10000000.00, NULL, 'DAILY', '17:30:00', 1, 'SAR', 'ACTIVE',
     320000000.00, 1280, NOW() - INTERVAL '1 day' + TIME '17:30:00',
     NOW() + INTERVAL '3.5 hours', NOW() - INTERVAL '6 years', NOW(), 'system'),
    ('bb000002-7000-0000-0000-000000000002', 'SWP-MAWR-002', 'Al Mawarid Dammam ZBA → Riyadh',
     'ZERO_BALANCE',          'bb000002-4000-0000-0000-000000000001', 'MAWR0000001', 'MAWR-SA',
     NULL, NULL, NULL, NULL, 'DAILY', '18:30:00', 2, 'SAR', 'ACTIVE',
     142000000.00, 1280, NOW() - INTERVAL '1 day' + TIME '18:30:00',
     NOW() + INTERVAL '4.5 hours', NOW() - INTERVAL '5 years', NOW(), 'system'),

    -- MNC 3 GBP — Albion: TARGET_BALANCE on Manchester; PERCENTAGE on Birmingham
    ('cc000003-7000-0000-0000-000000000001', 'SWP-ALBN-001', 'Albion Manchester Target 500k → London',
     'TARGET_BALANCE','cc000003-4000-0000-0000-000000000001', 'ALBN0000001', 'ALBN-GB',
     500000.00, NULL, NULL, NULL, 'DAILY', '18:00:00', 1, 'GBP', 'ACTIVE',
     420000000.00, 2540, NOW() - INTERVAL '1 day' + TIME '18:00:00',
     NOW() + INTERVAL '4 hours', NOW() - INTERVAL '10 years', NOW(), 'system'),
    ('cc000003-7000-0000-0000-000000000002', 'SWP-ALBN-002', 'Albion Birmingham 80% → London',
     'PERCENTAGE',   'cc000003-4000-0000-0000-000000000001', 'ALBN0000001', 'ALBN-GB',
     NULL, NULL, NULL, 80.00, 'DAILY', '18:00:00', 2, 'GBP', 'ACTIVE',
     205000000.00, 2540, NOW() - INTERVAL '1 day' + TIME '18:00:00',
     NOW() + INTERVAL '4 hours', NOW() - INTERVAL '8 years', NOW(), 'system'),

    -- MNC 4 USD — Helios: ZBA from NY; THRESHOLD from Boston
    ('dd000004-7000-0000-0000-000000000001', 'SWP-HEL-001',  'Helios NY USD ZBA → SF Header',
     'ZERO_BALANCE',          'dd000004-4000-0000-0000-000000000001', 'HEL0000001',  'HEL-US',
     NULL, NULL, NULL, NULL, 'DAILY', '17:00:00', 1, 'USD', 'ACTIVE',
     580000000.00, 1820, NOW() - INTERVAL '1 day' + TIME '17:00:00',
     NOW() + INTERVAL '3 hours', NOW() - INTERVAL '5 years', NOW(), 'system'),
    ('dd000004-7000-0000-0000-000000000002', 'SWP-HEL-002',  'Helios Boston > 5M → SF',
     'THRESHOLD',    'dd000004-4000-0000-0000-000000000001', 'HEL0000001',  'HEL-US',
     NULL, NULL, 5000000.00, NULL, 'DAILY', '17:30:00', 2, 'USD', 'ACTIVE',
     312000000.00, 1430, NOW() - INTERVAL '1 day' + TIME '17:30:00',
     NOW() + INTERVAL '3.5 hours', NOW() - INTERVAL '4 years', NOW(), 'system'),

    -- MNC 5 AED — Brato: ZBA from Abu Dhabi; PERCENTAGE from Sharjah
    ('ee000005-7000-0000-0000-000000000001', 'SWP-BRTO-001', 'Brato Abu Dhabi ZBA → Dubai Header',
     'ZERO_BALANCE',          'ee000005-4000-0000-0000-000000000001', 'BRTO0000001', 'BRTO-AE',
     NULL, NULL, NULL, NULL, 'DAILY', '18:00:00', 1, 'AED', 'ACTIVE',
     156000000.00, 1120, NOW() - INTERVAL '1 day' + TIME '18:00:00',
     NOW() + INTERVAL '4 hours', NOW() - INTERVAL '3 years', NOW(), 'system'),
    ('ee000005-7000-0000-0000-000000000002', 'SWP-BRTO-002', 'Brato Sharjah 75% → Dubai',
     'PERCENTAGE',   'ee000005-4000-0000-0000-000000000001', 'BRTO0000001', 'BRTO-AE',
     NULL, NULL, NULL, 75.00, 'DAILY', '18:30:00', 2, 'AED', 'ACTIVE',
      72000000.00, 1060, NOW() - INTERVAL '1 day' + TIME '18:30:00',
     NOW() + INTERVAL '4.5 hours', NOW() - INTERVAL '2 years', NOW(), 'system')
ON CONFLICT (rule_reference) DO NOTHING;

-- ============================================================================
-- 9. SWEEP RULE SOURCES — source accounts for each sweep rule
-- ============================================================================
INSERT INTO sweep_rule_sources (
    id, rule_id, account_id, account_number, entity_code, entity_name,
    currency_code, bank_name, created_at, created_by
) VALUES
    -- Mercator
    ('aa000001-8000-0000-0000-000000000001', 'aa000001-7000-0000-0000-000000000001',
     'aa000001-4000-0000-0000-000000000003', 'MERC0000003', 'MERC-FR', 'Mercator France SAS',
     'EUR', 'BNP Paribas', NOW() - INTERVAL '4 years', 'system'),
    ('aa000001-8000-0000-0000-000000000002', 'aa000001-7000-0000-0000-000000000002',
     'aa000001-4000-0000-0000-000000000004', 'MERC0000004', 'MERC-IT', 'Mercator Italia S.r.l.',
     'EUR', 'UniCredit S.p.A.', NOW() - INTERVAL '4 years', 'system'),

    -- Al Mawarid
    ('bb000002-8000-0000-0000-000000000001', 'bb000002-7000-0000-0000-000000000001',
     'bb000002-4000-0000-0000-000000000002', 'MAWR0000002', 'MAWR-SA', 'Al Mawarid Trading Group JSC',
     'SAR', 'Saudi National Bank', NOW() - INTERVAL '6 years', 'system'),
    ('bb000002-8000-0000-0000-000000000002', 'bb000002-7000-0000-0000-000000000002',
     'bb000002-4000-0000-0000-000000000003', 'MAWR0000003', 'MAWR-SA', 'Al Mawarid Trading Group JSC',
     'SAR', 'Riyad Bank', NOW() - INTERVAL '5 years', 'system'),

    -- Albion
    ('cc000003-8000-0000-0000-000000000001', 'cc000003-7000-0000-0000-000000000001',
     'cc000003-4000-0000-0000-000000000002', 'ALBN0000002', 'ALBN-EN', 'Albion Engineering Ltd',
     'GBP', 'Barclays Bank UK plc', NOW() - INTERVAL '10 years', 'system'),
    ('cc000003-8000-0000-0000-000000000002', 'cc000003-7000-0000-0000-000000000002',
     'cc000003-4000-0000-0000-000000000003', 'ALBN0000003', 'ALBN-EN', 'Albion Engineering Ltd',
     'GBP', 'HSBC UK Bank plc', NOW() - INTERVAL '8 years', 'system'),

    -- Helios
    ('dd000004-8000-0000-0000-000000000001', 'dd000004-7000-0000-0000-000000000001',
     'dd000004-4000-0000-0000-000000000002', 'HEL0000002', 'HEL-US', 'Helios Global Inc.',
     'USD', 'Bank of America N.A.', NOW() - INTERVAL '5 years', 'system'),
    ('dd000004-8000-0000-0000-000000000002', 'dd000004-7000-0000-0000-000000000002',
     'dd000004-4000-0000-0000-000000000003', 'HEL0000003', 'HEL-US', 'Helios Global Inc.',
     'USD', 'Citibank N.A.', NOW() - INTERVAL '4 years', 'system'),

    -- Brato
    ('ee000005-8000-0000-0000-000000000001', 'ee000005-7000-0000-0000-000000000001',
     'ee000005-4000-0000-0000-000000000002', 'BRTO0000002', 'BRTO-AE', 'Brato Logistics LLC',
     'AED', 'Emirates NBD', NOW() - INTERVAL '3 years', 'system'),
    ('ee000005-8000-0000-0000-000000000002', 'ee000005-7000-0000-0000-000000000002',
     'ee000005-4000-0000-0000-000000000003', 'BRTO0000003', 'BRTO-AE', 'Brato Logistics LLC',
     'AED', 'HSBC Bank Middle East', NOW() - INTERVAL '2 years', 'system')
ON CONFLICT DO NOTHING;

-- ============================================================================
-- 10. SWEEP EXECUTIONS — last 3 runs per sweep rule (yesterday, day-before, etc.)
-- ============================================================================
-- One COMPLETED run yesterday, one COMPLETED 2 days ago, and one FAILED
-- example on the Brato Sharjah rule so the cockpit's attention inbox has a
-- `sweep_failure` item to render.

INSERT INTO sweep_executions (
    id, execution_reference, rule_id, rule_name, source_account_id,
    source_account_number, source_entity_code, target_account_id,
    target_account_number, target_entity_code, sweep_amount, currency_code,
    balance_before, balance_after, status, error_message, execution_time,
    completed_at, bancs_transaction_ref, bancs_sync_status, created_at, created_by
) VALUES
    -- Mercator FR ZBA (yesterday + day-before)
    ('aa000001-9000-0000-0000-000000000001', 'EXEC-MERC-FR-2024-401', 'aa000001-7000-0000-0000-000000000001',
     'Mercator FR EUR ZBA → DE Header',
     'aa000001-4000-0000-0000-000000000003', 'MERC0000003', 'MERC-FR',
     'aa000001-4000-0000-0000-000000000001', 'MERC0000001', 'MERC-DE',
     1485000.00, 'EUR', 1485000.00, 0.00, 'COMPLETED', NULL,
     NOW() - INTERVAL '1 day' + TIME '18:00:00',
     NOW() - INTERVAL '1 day' + TIME '18:00:14',
     'BANCS-SWP-MERC-FR-401', 'SYNCED',
     NOW() - INTERVAL '1 day' + TIME '18:00:00', 'system'),
    ('aa000001-9000-0000-0000-000000000002', 'EXEC-MERC-FR-2024-400', 'aa000001-7000-0000-0000-000000000001',
     'Mercator FR EUR ZBA → DE Header',
     'aa000001-4000-0000-0000-000000000003', 'MERC0000003', 'MERC-FR',
     'aa000001-4000-0000-0000-000000000001', 'MERC0000001', 'MERC-DE',
     1612000.00, 'EUR', 1612000.00, 0.00, 'COMPLETED', NULL,
     NOW() - INTERVAL '2 days' + TIME '18:00:00',
     NOW() - INTERVAL '2 days' + TIME '18:00:11',
     'BANCS-SWP-MERC-FR-400', 'SYNCED',
     NOW() - INTERVAL '2 days' + TIME '18:00:00', 'system'),

    -- Al Mawarid Jeddah threshold
    ('bb000002-9000-0000-0000-000000000001', 'EXEC-MAWR-JED-2024-512', 'bb000002-7000-0000-0000-000000000001',
     'Al Mawarid Jeddah > 10M → Riyadh',
     'bb000002-4000-0000-0000-000000000002', 'MAWR0000002', 'MAWR-SA',
     'bb000002-4000-0000-0000-000000000001', 'MAWR0000001', 'MAWR-SA',
     2150000.00, 'SAR', 10350000.00, 8200000.00, 'COMPLETED', NULL,
     NOW() - INTERVAL '1 day' + TIME '17:30:00',
     NOW() - INTERVAL '1 day' + TIME '17:30:08',
     'BANCS-SWP-MAWR-JED-512', 'SYNCED',
     NOW() - INTERVAL '1 day' + TIME '17:30:00', 'system'),

    -- Albion Manchester target-balance
    ('cc000003-9000-0000-0000-000000000001', 'EXEC-ALBN-MAN-2024-820', 'cc000003-7000-0000-0000-000000000001',
     'Albion Manchester Target 500k → London',
     'cc000003-4000-0000-0000-000000000002', 'ALBN0000002', 'ALBN-EN',
     'cc000003-4000-0000-0000-000000000001', 'ALBN0000001', 'ALBN-GB',
     3020000.00, 'GBP', 3520000.00, 500000.00, 'COMPLETED', NULL,
     NOW() - INTERVAL '1 day' + TIME '18:00:00',
     NOW() - INTERVAL '1 day' + TIME '18:00:13',
     'BANCS-SWP-ALBN-MAN-820', 'SYNCED',
     NOW() - INTERVAL '1 day' + TIME '18:00:00', 'system'),

    -- Helios NY ZBA
    ('dd000004-9000-0000-0000-000000000001', 'EXEC-HEL-NY-2024-705', 'dd000004-7000-0000-0000-000000000001',
     'Helios NY USD ZBA → SF Header',
     'dd000004-4000-0000-0000-000000000002', 'HEL0000002', 'HEL-US',
     'dd000004-4000-0000-0000-000000000001', 'HEL0000001', 'HEL-US',
     6045000.00, 'USD', 6045000.00, 0.00, 'COMPLETED', NULL,
     NOW() - INTERVAL '1 day' + TIME '17:00:00',
     NOW() - INTERVAL '1 day' + TIME '17:00:09',
     'BANCS-SWP-HEL-NY-705', 'SYNCED',
     NOW() - INTERVAL '1 day' + TIME '17:00:00', 'system'),

    -- Brato Abu Dhabi ZBA
    ('ee000005-9000-0000-0000-000000000001', 'EXEC-BRTO-AUH-2024-441', 'ee000005-7000-0000-0000-000000000001',
     'Brato Abu Dhabi ZBA → Dubai Header',
     'ee000005-4000-0000-0000-000000000002', 'BRTO0000002', 'BRTO-AE',
     'ee000005-4000-0000-0000-000000000001', 'BRTO0000001', 'BRTO-AE',
     4520000.00, 'AED', 4520000.00, 0.00, 'COMPLETED', NULL,
     NOW() - INTERVAL '1 day' + TIME '18:00:00',
     NOW() - INTERVAL '1 day' + TIME '18:00:10',
     'BANCS-SWP-BRTO-AUH-441', 'SYNCED',
     NOW() - INTERVAL '1 day' + TIME '18:00:00', 'system'),

    -- Brato Sharjah percentage — FAILED yesterday (drives a cockpit attention item)
    ('ee000005-9000-0000-0000-000000000002', 'EXEC-BRTO-SHJ-2024-389', 'ee000005-7000-0000-0000-000000000002',
     'Brato Sharjah 75% → Dubai',
     'ee000005-4000-0000-0000-000000000003', 'BRTO0000003', 'BRTO-AE',
     'ee000005-4000-0000-0000-000000000001', 'BRTO0000001', 'BRTO-AE',
     0.00, 'AED', 2515000.00, 2515000.00, 'FAILED',
     'CBS transfer rejected: insufficient credit limit on source account',
     NOW() - INTERVAL '1 day' + TIME '18:30:00',
     NULL, NULL, 'PENDING',
     NOW() - INTERVAL '1 day' + TIME '18:30:00', 'system')
ON CONFLICT (execution_reference) DO NOTHING;

-- ============================================================================
-- 11. NETTING CYCLES — pending-approval cycles for the cockpit inbox
-- ============================================================================
-- Two netting cycles in `PENDING_APPROVAL` status. The backend's
-- DashboardController.getPendingApprovals() queries
-- `nettingCycleRepository.findByStatus(PENDING_APPROVAL)` and the cockpit's
-- `producePendingApprovals` producer turns each into a medium-severity
-- `pending_approval` attention item.
--
-- Netting cycles are not directly tied to a corporate via FK in this
-- schema; cross-references happen through `netting_entries.payer_entity_id`
-- joining to legal_entities. The cycles below are intentionally generic so
-- they show up in the inbox regardless of which corporate is selected.

INSERT INTO netting_cycles (
    id, cycle_reference, cycle_name, period_start, period_end, settlement_date,
    base_currency, total_gross, total_net, savings_amount, savings_percent,
    entry_count, participant_count, status, created_at, updated_at, created_by
) VALUES
    -- December 2024 EUR netting cycle — savings of ~33% (Mercator group)
    ('aa000001-a000-0000-0000-000000000001', 'NET-202412-MERC',
     'Mercator December 2024 EUR Netting',
     CURRENT_DATE - INTERVAL '30 days', CURRENT_DATE - INTERVAL '1 day',
     CURRENT_DATE + INTERVAL '2 days',
     'EUR', 18_400_000.00, 12_280_000.00, 6_120_000.00, 33.2604,
     42, 3, 'PENDING_APPROVAL',
     NOW() - INTERVAL '2 days', NOW() - INTERVAL '2 hours', 'system'),

    -- November 2024 GBP netting cycle — savings of ~28% (Albion group)
    ('cc000003-a000-0000-0000-000000000001', 'NET-202411-ALBN',
     'Albion November 2024 GBP Netting',
     CURRENT_DATE - INTERVAL '60 days', CURRENT_DATE - INTERVAL '31 days',
     CURRENT_DATE - INTERVAL '1 day',
     'GBP', 9_200_000.00,  6_624_000.00, 2_576_000.00, 28.0000,
     28, 2, 'PENDING_APPROVAL',
     NOW() - INTERVAL '5 days', NOW() - INTERVAL '1 day', 'system')
ON CONFLICT (cycle_reference) DO NOTHING;

-- ============================================================================
-- 12. SCHEDULED PAYABLES — funding-shortfall candidates for the cockpit
-- ============================================================================
-- Three SCHEDULED payables due within the next 8 hours. The cockpit's
-- `produceFundingShortfalls` producer queries
-- `payablesApi.getAll(0, 50, undefined, 'SCHEDULED')` and creates
-- `funding_shortfall` attention items for each row whose `scheduled_date`
-- falls inside the 8-hour window. Severity escalates to `critical` for
-- anything due within 2 hours.
--
-- Note: scheduled_date is a `date` column (no time), so the producer
-- conservatively treats the row as "now-ish" — a row scheduled for today
-- generates an in-window item.

INSERT INTO payables (
    id, payable_number, invoice_number, payable_type, corporate_id,
    owning_entity_code, owning_entity_name, vendor_name, vendor_account,
    vendor_bank, currency_code, gross_amount, net_amount, outstanding_amount,
    invoice_date, received_date, due_date, scheduled_date,
    payment_terms_days, payment_priority, payment_method, payment_channel,
    status, payment_status, approval_required, approval_level, approved_by,
    approved_at, description, created_at, updated_at
) VALUES
    -- MNC 1 Mercator — large vendor payment due today (CRITICAL — in-window)
    ('aa000001-b000-0000-0000-000000000001', 'PAY-MERC-2024-0451',
     'INV-DEU-2024-7720', 'INVOICE', 'aa000001-0000-0000-0000-000000000001',
     'MERC-DE', 'Mercator Brands Holding SE',
     'Schäfer Textilien GmbH', '0070099988800', 'Deutsche Bank AG', 'EUR',
     4_200_000.00, 4_200_000.00, 4_200_000.00,
     CURRENT_DATE - INTERVAL '14 days', CURRENT_DATE - INTERVAL '12 days',
     CURRENT_DATE,            CURRENT_DATE,
     -- payment_method / payment_channel must be Java enum values
     -- (PaymentMethod: BANK_TRANSFER|INTERNAL_TRANSFER|CHECK|CARD|CASH,
     --  PaymentChannel: DIRECT|BATCH|SCHEDULED|IMMEDIATE). 'SWIFT'/'WIRE'/
     -- 'LOCAL_CLEARING' broke Hibernate row-mapping and 500'd every
     -- /payables list query that paged over these rows.
     14, 'HIGH', 'BANK_TRANSFER', 'DIRECT',
     'SCHEDULED', 'UNPAID', true, 1, 'Treasury Lead',
     NOW() - INTERVAL '2 hours',
     'Q4 textile supplier batch payment — Schäfer (Hannover)',
     NOW() - INTERVAL '14 days', NOW() - INTERVAL '2 hours'),

    -- MNC 4 Helios — supplier payment due today
    ('dd000004-b000-0000-0000-000000000001', 'PAY-HEL-2024-0884',
     'INV-AWS-2024-Q4-9912', 'INVOICE', 'dd000004-0000-0000-0000-000000000004',
     'HEL-US', 'Helios Global Inc.',
     'Cloud Infra Vendors LLC', '8810099887766', 'JPMorgan Chase Bank', 'USD',
     2_350_000.00, 2_350_000.00, 2_350_000.00,
     CURRENT_DATE - INTERVAL '20 days', CURRENT_DATE - INTERVAL '18 days',
     CURRENT_DATE,            CURRENT_DATE,
     30, 'NORMAL', 'BANK_TRANSFER', 'DIRECT',
     'SCHEDULED', 'UNPAID', true, 1, 'CFO',
     NOW() - INTERVAL '4 hours',
     'Q4 cloud infrastructure invoice — usage-based',
     NOW() - INTERVAL '20 days', NOW() - INTERVAL '4 hours'),

    -- MNC 5 Brato — logistics payment due today
    ('ee000005-b000-0000-0000-000000000001', 'PAY-BRTO-2024-0322',
     'INV-PORT-2024-1188', 'INVOICE', 'ee000005-0000-0000-0000-000000000005',
     'BRTO-AE', 'Brato Logistics LLC',
     'Jebel Ali Port Authority', 'AE0322299988877766', 'Emirates NBD', 'AED',
     1_175_000.00, 1_175_000.00, 1_175_000.00,
     CURRENT_DATE - INTERVAL '10 days', CURRENT_DATE - INTERVAL '8 days',
     CURRENT_DATE,            CURRENT_DATE,
     14, 'NORMAL', 'BANK_TRANSFER', 'DIRECT',
     'SCHEDULED', 'UNPAID', true, 1, 'Treasurer',
     NOW() - INTERVAL '6 hours',
     'Port dues + storage charges — December',
     NOW() - INTERVAL '10 days', NOW() - INTERVAL '6 hours')
ON CONFLICT (payable_number) DO NOTHING;

-- ============================================================================
-- 13. STUCK TRANSACTIONS — PENDING va_movements past the 4-hour cutoff
-- ============================================================================
-- Two PENDING va_movements with `transaction_date` set far enough in the
-- past that the cockpit's `produceStuckTransactions` producer fires:
--   - the 4-hour cutoff turns each row into a `stuck_transaction` item
--   - rows older than 24 hours escalate severity from `medium` to `critical`
--
-- Underlying entity: com.bank.vam.entity.Transaction maps to `va_movements`.
-- The schema enforces:
--   - movement_type ∈ (CREDIT, DEBIT, TRANSFER_IN, TRANSFER_OUT, REVERSAL)
--   - status       ∈ (PENDING, COMPLETED, FAILED, REVERSED, CANCELLED)
-- Both `physical_account_id` and `va_id` are NOT NULL — we reuse the
-- shadow-VA + physical-account pairs from sections 3 and 5.

INSERT INTO va_movements (
    id, corporate_id, va_id, physical_account_id, program_id,
    movement_type, transaction_category, amount, currency_code,
    balance_before, balance_after, status, reference_number, description,
    channel, beneficiary_name, beneficiary_account, external_reference,
    transaction_date, value_date, fee_amount,
    initiated_by, created_at, updated_at, created_by, version
) VALUES
    -- Helios NYC USD → JPMorgan SF · stuck 8h (MEDIUM severity)
    ('dd000004-c000-0000-0000-000000000001', 'dd000004-0000-0000-0000-000000000004',
     'dd000004-4000-0000-0000-000000000002', 'dd000004-2000-0000-0000-000000000002',
     'dd000004-3000-0000-0000-000000000001',
     -- transaction_category must be a Java TransactionCategory value
     -- (INTERNAL | EXTERNAL). 'EXTERNAL_PAYMENT' belongs to the
     -- transaction_groups.group_type vocabulary — using it here broke
     -- Hibernate row-mapping and 500'd every filtered /transactions query.
     'TRANSFER_OUT', 'EXTERNAL', 1_200_000.00, 'USD',
     6_045_000.00, 6_045_000.00, 'PENDING', 'TXN-HEL-2024-009815',
     'Intra-day liquidity transfer · NY → SF · Treasury',
     'WIRE', 'Helios Global Inc. - SF Header', '9876543201',
     'WIRE-2024-12-009815',
     NOW() - INTERVAL '8 hours', CURRENT_DATE, 12.50,
     'system', NOW() - INTERVAL '8 hours', NOW() - INTERVAL '8 hours',
     'treasury.ops', 0),

    -- Albion Boston USD → SWIFT payment · stuck 30h (CRITICAL — >24h)
    ('cc000003-c000-0000-0000-000000000001', 'cc000003-0000-0000-0000-000000000003',
     'cc000003-4000-0000-0000-000000000004', 'cc000003-2000-0000-0000-000000000004',
     'cc000003-3000-0000-0000-000000000001',
     'DEBIT', 'EXTERNAL', 850_000.00, 'USD',
     1_820_000.00, 1_820_000.00, 'PENDING', 'TXN-ALBN-2024-014477',
     'Vendor payment · Boston ops · SWIFT-stuck-at-correspondent',
     'SWIFT', 'Precision Components Inc.', 'US33CITIUS33888899910',
     'MT103-2024-12-014477',
     NOW() - INTERVAL '30 hours', CURRENT_DATE - INTERVAL '1 day', 45.00,
     'system', NOW() - INTERVAL '30 hours', NOW() - INTERVAL '6 hours',
     'treasury.ops', 0)
ON CONFLICT (reference_number) DO NOTHING;

-- ============================================================================
-- 14. SHADOW REFRESH STATUS — varied statuses for the cockpit attention inbox
-- ============================================================================
-- The base 20 shadows from section 5 are healthy SUCCESS rows by default.
-- This block flips a handful of them to STALE / FAILED / NEVER / in-flight
-- so the Treasurer's Morning Cockpit attention inbox renders the full set
-- of `stale_balance` and `sweep_failure` row variations on first load.
--
-- Cockpit producer (cockpitApi.ts → produceStaleBalances) considers a shadow
-- staleness-eligible when ANY of:
--   - `stale = true` (computed server-side from refresh_at vs threshold)
--   - `last_balance_refresh_status = 'FAILED'`
--   - `last_balance_refresh_status = 'NEVER'`
-- This block sets all three flavours so the inbox shows variety.
--
-- Default-healthy update first: every shadow gets a fresh SUCCESS stamp so
-- only the explicitly-bad rows trigger attention items. Idempotent.

UPDATE virtual_accounts
SET    last_balance_refresh_at     = NOW() - (random() * INTERVAL '20 minutes'),
       last_balance_refresh_status = 'SUCCESS',
       bank_balance_committed      = 0
WHERE  va_number IN (
    'MERC0000001', 'MERC0000002', 'MERC0000003', 'MERC0000004',
    'MAWR0000001', 'MAWR0000002', 'MAWR0000003', 'MAWR0000004',
    'ALBN0000001', 'ALBN0000002', 'ALBN0000003', 'ALBN0000004',
    'HEL0000001',  'HEL0000002',  'HEL0000003',  'HEL0000004',
    'BRTO0000001', 'BRTO0000002', 'BRTO0000003', 'BRTO0000004'
);

-- ── STALE (last refresh > freshness threshold) ──────────────────────────────
-- Mercator IT (UniCredit Milan) — 31h since last successful refresh; the
-- bank's nightly file dropped late.
UPDATE virtual_accounts
SET    last_balance_refresh_at     = NOW() - INTERVAL '31 hours',
       last_balance_refresh_status = 'SUCCESS'
WHERE  va_number = 'MERC0000004';

-- Helios SGD (Standard Chartered Singapore) — 52h since last refresh; APAC
-- holiday on Friday delayed the next pull cycle.
UPDATE virtual_accounts
SET    last_balance_refresh_at     = NOW() - INTERVAL '52 hours',
       last_balance_refresh_status = 'SUCCESS'
WHERE  va_number = 'HEL0000004';

-- ── FAILED (last refresh attempt errored) ───────────────────────────────────
-- Al Mawarid Dubai USD (Emirates NBD) — 90 minutes ago, swift connection
-- dropped mid-fetch.
UPDATE virtual_accounts
SET    last_balance_refresh_at     = NOW() - INTERVAL '90 minutes',
       last_balance_refresh_status = 'FAILED'
WHERE  va_number = 'MAWR0000004';

-- Brato KSA SAR (Al Rajhi Riyadh subsidiary) — 4h ago, CBS auth token
-- expired and the renewal job hasn't run yet.
UPDATE virtual_accounts
SET    last_balance_refresh_at     = NOW() - INTERVAL '4 hours',
       last_balance_refresh_status = 'FAILED'
WHERE  va_number = 'BRTO0000004';

-- ── NEVER (refresh has not yet run) ─────────────────────────────────────────
-- Albion Boston USD (Citibank) — newly onboarded shadow; the first
-- scheduled refresh hasn't fired yet.
UPDATE virtual_accounts
SET    last_balance_refresh_at     = NULL,
       last_balance_refresh_status = 'NEVER'
WHERE  va_number = 'ALBN0000004';

-- ── IN-FLIGHT FLOAT (intraday committed amount > 0) ─────────────────────────
-- Brato Sharjah AED — has a 1.85M AED outstanding sweep instruction that
-- hasn't yet settled at the bank, so committed > 0 even though the bank
-- balance is unchanged.
UPDATE virtual_accounts
SET    last_balance_refresh_at     = NOW() - INTERVAL '7 minutes',
       last_balance_refresh_status = 'SUCCESS',
       bank_balance_committed      = 1850000.0000
WHERE  va_number = 'BRTO0000003';

-- Mercator FR EUR — has a 320K EUR pending ZBA sweep instruction.
UPDATE virtual_accounts
SET    last_balance_refresh_at     = NOW() - INTERVAL '12 minutes',
       last_balance_refresh_status = 'SUCCESS',
       bank_balance_committed      = 320000.0000
WHERE  va_number = 'MERC0000003';

-- ============================================================================
-- Done. Verify with:
--
--   -- Five MNCs:
--   SELECT corporate_id, legal_name, incorporation_country
--   FROM corporates WHERE corporate_id LIKE 'MNC-%' ORDER BY corporate_id;
--
--   -- Five home-currency notional pools:
--   SELECT pool_reference, pool_currency, member_count, total_balance
--   FROM notional_pools WHERE pool_reference LIKE 'POOL-%' ORDER BY pool_reference;
--
--   -- Ten sweep rules across the four sweep types:
--   SELECT rule_reference, sweep_type, frequency, status, execution_count
--   FROM sweep_rules WHERE rule_reference LIKE 'SWP-%' ORDER BY rule_reference;
--
--   -- Two pending netting cycles (drives `pending_approval` inbox rows):
--   SELECT cycle_reference, base_currency, total_net, savings_amount, status
--   FROM netting_cycles WHERE cycle_reference LIKE 'NET-2024%' ORDER BY cycle_reference;
--
--   -- Three SCHEDULED payables due today (drives `funding_shortfall` rows):
--   SELECT payable_number, currency_code, gross_amount, vendor_name, scheduled_date, status
--   FROM payables WHERE payable_number LIKE 'PAY-%-2024-%' ORDER BY scheduled_date, gross_amount DESC;
--
--   -- Two PENDING stuck transactions (drives `stuck_transaction` rows):
--   SELECT reference_number, movement_type, currency_code, amount,
--          transaction_date, status,
--          EXTRACT(EPOCH FROM (NOW() - transaction_date))/3600 AS hours_stuck
--   FROM va_movements WHERE reference_number LIKE 'TXN-%-2024-%' AND status = 'PENDING'
--   ORDER BY transaction_date;
--
--   -- Shadow attention demo — 5 rows: 2 STALE, 2 FAILED, 1 NEVER:
--   SELECT va_number, currency_code, bank_swift,
--          last_balance_refresh_status,
--          last_balance_refresh_at,
--          bank_balance_committed
--   FROM virtual_accounts
--   WHERE va_number IN (
--       'MERC0000004','HEL0000004',     -- STALE
--       'MAWR0000004','BRTO0000004',    -- FAILED
--       'ALBN0000004',                  -- NEVER
--       'BRTO0000003','MERC0000003'     -- in-flight float
--   )
--   ORDER BY last_balance_refresh_status NULLS LAST, va_number;
--
-- Expected cockpit inbox after seeding (per producer):
--   - 2 `pending_approval` from netting cycles (medium severity)
--   - 0+ `pending_approval` from controller-hardcoded KYC / payables mocks
--   - 3 `funding_shortfall` from scheduled payables (high · critical for <2h)
--   - 2 `stuck_transaction` (1 medium @ 8h, 1 critical @ 30h)
--   - 5 `stale_balance` from section 14 (2 STALE high, 2 FAILED high, 1 NEVER medium)
--   - 1 `sweep_failure` from the Brato Sharjah FAILED execution in section 10
--
-- Total: ~13-15 attention items on first load, spanning 5 categories.
-- ============================================================================
