-- client_encoding pinned to UTF8 for this session: without it, psql falls
-- back to the shell locale's encoding (often not UTF-8 on a fresh box/CI
-- runner), which silently mangles every non-ASCII character below into
-- mojibake on insert (e.g. '·' becomes 'Â·') -- a data corruption, not a
-- display bug, since it's the bytes actually written to the table. See
-- V14__fix_mojibake_encoding.sql for the one-time repair of data seeded
-- before this fix existed.
SET client_encoding = 'UTF8';

-- ==========================================
-- VAM Portal - Seed Data Script
-- ==========================================
-- This script populates the database with realistic test data
-- for development and testing purposes.
-- 
-- Run after migrations: psql -h localhost -U vam_user -d vam_db -f seed_data.sql
-- ==========================================

-- Set timezone
SET timezone = 'UTC';

-- ==========================================
-- 1. REFERENCE DATA
-- ==========================================

-- Currencies
INSERT INTO currencies (code, name, decimal_places, is_active) VALUES
    ('AED', 'UAE Dirham', 2, true),
    ('USD', 'US Dollar', 2, true),
    ('EUR', 'Euro', 2, true),
    ('GBP', 'British Pound', 2, true),
    ('SAR', 'Saudi Riyal', 2, true),
    ('INR', 'Indian Rupee', 2, true),
    ('CNY', 'Chinese Yuan', 2, true),
    ('JPY', 'Japanese Yen', 0, true),
    ('CHF', 'Swiss Franc', 2, true),
    ('SGD', 'Singapore Dollar', 2, true)
ON CONFLICT (code) DO NOTHING;

-- Countries
INSERT INTO countries (iso_code, name, is_active) VALUES
    ('AE', 'United Arab Emirates', true),
    ('US', 'United States', true),
    ('GB', 'United Kingdom', true),
    ('DE', 'Germany', true),
    ('FR', 'France', true),
    ('SA', 'Saudi Arabia', true),
    ('IN', 'India', true),
    ('CN', 'China', true),
    ('JP', 'Japan', true),
    ('SG', 'Singapore', true),
    ('CH', 'Switzerland', true),
    ('NL', 'Netherlands', true)
ON CONFLICT (iso_code) DO NOTHING;

-- Banks (for beneficiary management)
INSERT INTO banks (swift_code, name, country_code, city, is_active) VALUES
    ('ABORAEADXXX', 'Arab Bank UAE', 'AE', 'Dubai', true),
    ('ABORAEADABU', 'Arab Bank UAE - Abu Dhabi', 'AE', 'Abu Dhabi', true),
    ('CBABORAAXXX', 'Commercial Bank of Dubai', 'AE', 'Dubai', true),
    ('EABORAADXXX', 'Emirates NBD', 'AE', 'Dubai', true),
    ('FAABORADXXX', 'First Abu Dhabi Bank', 'AE', 'Abu Dhabi', true),
    ('MAABORADXXX', 'Mashreq Bank', 'AE', 'Dubai', true),
    ('CIABORADXXX', 'Citibank UAE', 'AE', 'Dubai', true),
    ('HSBCAEADXXX', 'HSBC UAE', 'AE', 'Dubai', true),
    ('SCBLAEADXXX', 'Standard Chartered UAE', 'AE', 'Dubai', true),
    ('CHABORADXXX', 'Chase Bank', 'US', 'New York', true),
    ('BABORADXXX', 'Bank of America', 'US', 'New York', true),
    ('HSBCGB2LXXX', 'HSBC UK', 'GB', 'London', true),
    ('BABORADGBXXX', 'Barclays UK', 'GB', 'London', true),
    ('DEUTDEFFXXX', 'Deutsche Bank', 'DE', 'Frankfurt', true),
    ('ABORINDXXX', 'HDFC Bank', 'IN', 'Mumbai', true),
    ('ABORINDXXX2', 'ICICI Bank', 'IN', 'Mumbai', true)
ON CONFLICT (swift_code) DO NOTHING;

-- ==========================================
-- 2. CORPORATE CUSTOMERS
-- ==========================================

-- Corporate Entity 1: Large E-commerce Company
INSERT INTO corporates (
    id, corporate_id, legal_name, trade_name, registration_number,
    tax_id, incorporation_country, incorporation_date, legal_entity_type,
    industry_sector, business_description, website, primary_contact_name,
    primary_contact_email, primary_contact_phone, status, kyc_status,
    risk_rating, created_at, updated_at
) VALUES (
    'c1000001-0000-0000-0000-000000000001',
    'CORP-2024-001',
    'Desert Oasis E-Commerce LLC',
    'Desert Oasis',
    'LLC-2020-12345',
    'TRN100123456789',
    'AE',
    '2020-03-15',
    'LLC',
    'E_COMMERCE',
    'Leading online marketplace for electronics and consumer goods in the Middle East',
    'https://desertoasis.ae',
    'Ahmed Al Maktoum',
    'ahmed.maktoum@desertoasis.ae',
    '+971501234567',
    'ACTIVE',
    'APPROVED',
    'LOW',
    NOW() - INTERVAL '2 years',
    NOW()
) ON CONFLICT DO NOTHING;

-- Corporate Entity 2: Real Estate Developer
INSERT INTO corporates (
    id, corporate_id, legal_name, trade_name, registration_number,
    tax_id, incorporation_country, incorporation_date, legal_entity_type,
    industry_sector, business_description, website, primary_contact_name,
    primary_contact_email, primary_contact_phone, status, kyc_status,
    risk_rating, created_at, updated_at
) VALUES (
    'c2000002-0000-0000-0000-000000000002',
    'CORP-2024-002',
    'Golden Sands Properties PJSC',
    'Golden Sands',
    'PJSC-2018-54321',
    'TRN100987654321',
    'AE',
    '2018-07-20',
    'PJSC',
    'REAL_ESTATE',
    'Premium real estate development and property management company',
    'https://goldensands.ae',
    'Fatima Al Rashid',
    'fatima.rashid@goldensands.ae',
    '+971502345678',
    'ACTIVE',
    'APPROVED',
    'MEDIUM',
    NOW() - INTERVAL '3 years',
    NOW()
) ON CONFLICT DO NOTHING;

-- Corporate Entity 3: Fintech Startup
INSERT INTO corporates (
    id, corporate_id, legal_name, trade_name, registration_number,
    tax_id, incorporation_country, incorporation_date, legal_entity_type,
    industry_sector, business_description, website, primary_contact_name,
    primary_contact_email, primary_contact_phone, status, kyc_status,
    risk_rating, created_at, updated_at
) VALUES (
    'c3000003-0000-0000-0000-000000000003',
    'CORP-2024-003',
    'PayFlow Technologies FZ-LLC',
    'PayFlow',
    'FZ-2022-98765',
    'TRN100555666777',
    'AE',
    '2022-01-10',
    'FZ_LLC',
    'FINTECH',
    'Digital payment solutions and wallet services provider',
    'https://payflow.ae',
    'Omar Hassan',
    'omar.hassan@payflow.ae',
    '+971503456789',
    'ACTIVE',
    'APPROVED',
    'LOW',
    NOW() - INTERVAL '1 year',
    NOW()
) ON CONFLICT DO NOTHING;

-- Corporate Entity 4: Import/Export Trading
INSERT INTO corporates (
    id, corporate_id, legal_name, trade_name, registration_number,
    tax_id, incorporation_country, incorporation_date, legal_entity_type,
    industry_sector, business_description, website, primary_contact_name,
    primary_contact_email, primary_contact_phone, status, kyc_status,
    risk_rating, created_at, updated_at
) VALUES (
    'c4000004-0000-0000-0000-000000000004',
    'CORP-2024-004',
    'Global Trade Solutions FZE',
    'GTS Trading',
    'FZE-2019-11111',
    'TRN100111222333',
    'AE',
    '2019-05-25',
    'FZE',
    'TRADING',
    'International commodities trading and logistics services',
    'https://gtstrading.ae',
    'Khalid Ibrahim',
    'khalid.ibrahim@gtstrading.ae',
    '+971504567890',
    'ACTIVE',
    'APPROVED',
    'MEDIUM',
    NOW() - INTERVAL '2 years',
    NOW()
) ON CONFLICT DO NOTHING;

-- ==========================================
-- 3. PHYSICAL ACCOUNTS (Real Bank Accounts)
-- ==========================================

-- Physical Account for Desert Oasis - Main Operating
INSERT INTO physical_accounts (
    id, account_number, account_name, corporate_id, currency_code,
    account_type, bancs_customer_id, bancs_account_id, iban,
    current_balance, available_balance, status, opened_date,
    branch_code, relationship_manager, created_at, updated_at
) VALUES (
    'pa100001-0000-0000-0000-000000000001',
    '0011234567890',
    'Desert Oasis Main Operating',
    'c1000001-0000-0000-0000-000000000001',
    'AED',
    'CURRENT',
    'BANCS-CUST-001',
    'BANCS-ACC-001',
    'AE070331234567890123456',
    15750000.00,
    15250000.00,
    'ACTIVE',
    '2020-04-01',
    'DXB001',
    'Sarah Ahmed',
    NOW() - INTERVAL '2 years',
    NOW()
) ON CONFLICT DO NOTHING;

-- Physical Account for Desert Oasis - USD
INSERT INTO physical_accounts (
    id, account_number, account_name, corporate_id, currency_code,
    account_type, bancs_customer_id, bancs_account_id, iban,
    current_balance, available_balance, status, opened_date,
    branch_code, relationship_manager, created_at, updated_at
) VALUES (
    'pa100002-0000-0000-0000-000000000002',
    '0011234567891',
    'Desert Oasis USD Account',
    'c1000001-0000-0000-0000-000000000001',
    'USD',
    'CURRENT',
    'BANCS-CUST-001',
    'BANCS-ACC-002',
    'AE070331234567891123456',
    2500000.00,
    2450000.00,
    'ACTIVE',
    '2020-04-01',
    'DXB001',
    'Sarah Ahmed',
    NOW() - INTERVAL '2 years',
    NOW()
) ON CONFLICT DO NOTHING;

-- Physical Account for Golden Sands - Escrow Master
INSERT INTO physical_accounts (
    id, account_number, account_name, corporate_id, currency_code,
    account_type, bancs_customer_id, bancs_account_id, iban,
    current_balance, available_balance, status, opened_date,
    branch_code, relationship_manager, created_at, updated_at
) VALUES (
    'pa200001-0000-0000-0000-000000000001',
    '0022345678901',
    'Golden Sands Escrow Master',
    'c2000002-0000-0000-0000-000000000002',
    'AED',
    'ESCROW',
    'BANCS-CUST-002',
    'BANCS-ACC-003',
    'AE070332345678901234567',
    125000000.00,
    125000000.00,
    'ACTIVE',
    '2018-08-15',
    'AUH001',
    'Mohammed Ali',
    NOW() - INTERVAL '3 years',
    NOW()
) ON CONFLICT DO NOTHING;

-- Physical Account for PayFlow - Wallet Pool
INSERT INTO physical_accounts (
    id, account_number, account_name, corporate_id, currency_code,
    account_type, bancs_customer_id, bancs_account_id, iban,
    current_balance, available_balance, status, opened_date,
    branch_code, relationship_manager, created_at, updated_at
) VALUES (
    'pa300001-0000-0000-0000-000000000001',
    '0033456789012',
    'PayFlow Wallet Pool',
    'c3000003-0000-0000-0000-000000000003',
    'AED',
    'POOL',
    'BANCS-CUST-003',
    'BANCS-ACC-004',
    'AE070333456789012345678',
    8750000.00,
    8500000.00,
    'ACTIVE',
    '2022-02-01',
    'DXB002',
    'Layla Khan',
    NOW() - INTERVAL '1 year',
    NOW()
) ON CONFLICT DO NOTHING;

-- Physical Account for GTS Trading - Multi-Currency
INSERT INTO physical_accounts (
    id, account_number, account_name, corporate_id, currency_code,
    account_type, bancs_customer_id, bancs_account_id, iban,
    current_balance, available_balance, status, opened_date,
    branch_code, relationship_manager, created_at, updated_at
) VALUES (
    'pa400001-0000-0000-0000-000000000001',
    '0044567890123',
    'GTS Trading Main',
    'c4000004-0000-0000-0000-000000000004',
    'AED',
    'CURRENT',
    'BANCS-CUST-004',
    'BANCS-ACC-005',
    'AE070334567890123456789',
    45000000.00,
    44500000.00,
    'ACTIVE',
    '2019-06-15',
    'DXB001',
    'Sarah Ahmed',
    NOW() - INTERVAL '2 years',
    NOW()
) ON CONFLICT DO NOTHING;

-- ==========================================
-- 4. UNIFIED PROGRAMS
-- ==========================================

-- Program 1: E-commerce Collection (Marketplace Sellers)
INSERT INTO unified_programs (
    id, program_code, program_name, program_type, corporate_id,
    physical_account_id, currency_code, description, va_prefix,
    va_format, max_virtual_accounts, auto_reconciliation,
    settlement_frequency, settlement_time, min_balance_threshold,
    status, effective_from, created_at, updated_at
) VALUES (
    'prog0001-0000-0000-0000-000000000001',
    'ECOM-SELLERS-001',
    'Marketplace Seller Collections',
    'COLLECTION',
    'c1000001-0000-0000-0000-000000000001',
    'pa100001-0000-0000-0000-000000000001',
    'AED',
    'Virtual accounts for marketplace seller payment collections',
    'SELL',
    'PREFIX_SEQUENTIAL',
    10000,
    true,
    'DAILY',
    '23:00:00',
    100.00,
    'ACTIVE',
    '2020-05-01',
    NOW() - INTERVAL '2 years',
    NOW()
) ON CONFLICT DO NOTHING;

-- Program 2: E-commerce VIBAN (Customer Deposits)
INSERT INTO unified_programs (
    id, program_code, program_name, program_type, corporate_id,
    physical_account_id, currency_code, description, va_prefix,
    va_format, max_virtual_accounts, auto_reconciliation,
    settlement_frequency, min_balance_threshold, viban_enabled,
    viban_bank_code, viban_branch_code, status, effective_from,
    created_at, updated_at
) VALUES (
    'prog0002-0000-0000-0000-000000000002',
    'ECOM-VIBAN-001',
    'Customer Deposit VIBANs',
    'VIBAN',
    'c1000001-0000-0000-0000-000000000001',
    'pa100001-0000-0000-0000-000000000001',
    'AED',
    'Dedicated VIBANs for customer wallet top-ups',
    'CUST',
    'IBAN_COMPATIBLE',
    50000,
    true,
    'REAL_TIME',
    0.00,
    true,
    '033',
    '0001',
    'ACTIVE',
    '2021-01-01',
    NOW() - INTERVAL '18 months',
    NOW()
) ON CONFLICT DO NOTHING;

-- Program 3: Real Estate Escrow
INSERT INTO unified_programs (
    id, program_code, program_name, program_type, corporate_id,
    physical_account_id, currency_code, description, va_prefix,
    va_format, max_virtual_accounts, requires_beneficiary_validation,
    escrow_enabled, status, effective_from, created_at, updated_at
) VALUES (
    'prog0003-0000-0000-0000-000000000003',
    'RE-ESCROW-001',
    'Property Transaction Escrow',
    'ESCROW',
    'c2000002-0000-0000-0000-000000000002',
    'pa200001-0000-0000-0000-000000000001',
    'AED',
    'Escrow accounts for property sales and off-plan purchases',
    'ESC',
    'PREFIX_SEQUENTIAL',
    5000,
    true,
    true,
    'ACTIVE',
    '2018-09-01',
    NOW() - INTERVAL '3 years',
    NOW()
) ON CONFLICT DO NOTHING;

-- Program 4: Digital Wallet
INSERT INTO unified_programs (
    id, program_code, program_name, program_type, corporate_id,
    physical_account_id, currency_code, description, va_prefix,
    va_format, max_virtual_accounts, auto_reconciliation,
    wallet_enabled, wallet_max_balance, wallet_daily_limit,
    status, effective_from, created_at, updated_at
) VALUES (
    'prog0004-0000-0000-0000-000000000004',
    'WALLET-001',
    'PayFlow Digital Wallets',
    'WALLET',
    'c3000003-0000-0000-0000-000000000003',
    'pa300001-0000-0000-0000-000000000001',
    'AED',
    'Consumer and merchant digital wallet accounts',
    'WAL',
    'PREFIX_SEQUENTIAL',
    100000,
    true,
    true,
    50000.00,
    10000.00,
    'ACTIVE',
    '2022-03-01',
    NOW() - INTERVAL '1 year',
    NOW()
) ON CONFLICT DO NOTHING;

-- Program 5: IHB (In-House Bank) for GTS Trading
INSERT INTO unified_programs (
    id, program_code, program_name, program_type, corporate_id,
    physical_account_id, currency_code, description, va_prefix,
    va_format, max_virtual_accounts, ihb_enabled, ihb_allow_intercompany,
    ihb_netting_enabled, status, effective_from, created_at, updated_at
) VALUES (
    'prog0005-0000-0000-0000-000000000005',
    'IHB-GTS-001',
    'GTS Group Treasury',
    'IHB',
    'c4000004-0000-0000-0000-000000000004',
    'pa400001-0000-0000-0000-000000000001',
    'AED',
    'In-house bank accounts for subsidiaries and intercompany settlements',
    'IHB',
    'PREFIX_SEQUENTIAL',
    500,
    true,
    true,
    true,
    'ACTIVE',
    '2019-07-01',
    NOW() - INTERVAL '2 years',
    NOW()
) ON CONFLICT DO NOTHING;

-- ==========================================
-- 5. VIRTUAL ACCOUNTS
-- ==========================================

-- E-commerce Seller Virtual Accounts
INSERT INTO virtual_accounts (
    id, va_number, va_name, program_id, corporate_id, physical_account_id,
    currency_code, current_balance, available_balance, status,
    external_reference, metadata, created_at, updated_at
) VALUES
    -- Seller 1: Electronics Store
    ('va100001-0000-0000-0000-000000000001', 'SELL0000001', 'TechMart Electronics', 
     'prog0001-0000-0000-0000-000000000001', 'c1000001-0000-0000-0000-000000000001',
     'pa100001-0000-0000-0000-000000000001', 'AED', 125000.00, 120000.00, 'ACTIVE',
     'SELLER-TM-001', '{"seller_tier": "GOLD", "category": "electronics"}',
     NOW() - INTERVAL '18 months', NOW()),
    
    -- Seller 2: Fashion Boutique
    ('va100002-0000-0000-0000-000000000002', 'SELL0000002', 'Style Avenue Fashion',
     'prog0001-0000-0000-0000-000000000001', 'c1000001-0000-0000-0000-000000000001',
     'pa100001-0000-0000-0000-000000000001', 'AED', 85000.00, 82000.00, 'ACTIVE',
     'SELLER-SA-002', '{"seller_tier": "SILVER", "category": "fashion"}',
     NOW() - INTERVAL '12 months', NOW()),
    
    -- Seller 3: Home & Garden
    ('va100003-0000-0000-0000-000000000003', 'SELL0000003', 'Green Living Home',
     'prog0001-0000-0000-0000-000000000001', 'c1000001-0000-0000-0000-000000000001',
     'pa100001-0000-0000-0000-000000000001', 'AED', 45000.00, 45000.00, 'ACTIVE',
     'SELLER-GL-003', '{"seller_tier": "BRONZE", "category": "home_garden"}',
     NOW() - INTERVAL '6 months', NOW()),
    
    -- Seller 4: Sports Equipment
    ('va100004-0000-0000-0000-000000000004', 'SELL0000004', 'FitZone Sports',
     'prog0001-0000-0000-0000-000000000001', 'c1000001-0000-0000-0000-000000000001',
     'pa100001-0000-0000-0000-000000000001', 'AED', 67500.00, 65000.00, 'ACTIVE',
     'SELLER-FZ-004', '{"seller_tier": "GOLD", "category": "sports"}',
     NOW() - INTERVAL '15 months', NOW()),
    
    -- Seller 5: Books & Media
    ('va100005-0000-0000-0000-000000000005', 'SELL0000005', 'PageTurner Books',
     'prog0001-0000-0000-0000-000000000001', 'c1000001-0000-0000-0000-000000000001',
     'pa100001-0000-0000-0000-000000000001', 'AED', 23000.00, 23000.00, 'ACTIVE',
     'SELLER-PT-005', '{"seller_tier": "BRONZE", "category": "books"}',
     NOW() - INTERVAL '9 months', NOW())
ON CONFLICT DO NOTHING;

-- VIBAN Customer Accounts
INSERT INTO virtual_accounts (
    id, va_number, viban, va_name, program_id, corporate_id, physical_account_id,
    currency_code, current_balance, available_balance, status,
    external_reference, kyc_verified, metadata, created_at, updated_at
) VALUES
    -- Customer 1
    ('va200001-0000-0000-0000-000000000001', 'CUST0000001', 'AE070330001000000001',
     'Mohammed Al Farsi', 'prog0002-0000-0000-0000-000000000002',
     'c1000001-0000-0000-0000-000000000001', 'pa100001-0000-0000-0000-000000000001',
     'AED', 5250.00, 5250.00, 'ACTIVE', 'CUST-MAF-001', true,
     '{"customer_type": "PREMIUM", "verified_date": "2023-01-15"}',
     NOW() - INTERVAL '12 months', NOW()),
    
    -- Customer 2
    ('va200002-0000-0000-0000-000000000002', 'CUST0000002', 'AE070330001000000002',
     'Sara Ahmed Khan', 'prog0002-0000-0000-0000-000000000002',
     'c1000001-0000-0000-0000-000000000001', 'pa100001-0000-0000-0000-000000000001',
     'AED', 12750.00, 12750.00, 'ACTIVE', 'CUST-SAK-002', true,
     '{"customer_type": "PREMIUM", "verified_date": "2023-03-20"}',
     NOW() - INTERVAL '10 months', NOW()),
    
    -- Customer 3
    ('va200003-0000-0000-0000-000000000003', 'CUST0000003', 'AE070330001000000003',
     'John Smith', 'prog0002-0000-0000-0000-000000000002',
     'c1000001-0000-0000-0000-000000000001', 'pa100001-0000-0000-0000-000000000001',
     'AED', 3500.00, 3500.00, 'ACTIVE', 'CUST-JS-003', true,
     '{"customer_type": "STANDARD", "verified_date": "2023-06-10"}',
     NOW() - INTERVAL '6 months', NOW())
ON CONFLICT DO NOTHING;

-- Escrow Virtual Accounts (Property Transactions)
INSERT INTO virtual_accounts (
    id, va_number, va_name, program_id, corporate_id, physical_account_id,
    currency_code, current_balance, available_balance, status,
    external_reference, metadata, created_at, updated_at
) VALUES
    -- Property 1: Marina Tower Penthouse
    ('va300001-0000-0000-0000-000000000001', 'ESC0000001', 
     'Marina Tower Unit 4501 - Al Maktoum/Rashid',
     'prog0003-0000-0000-0000-000000000003', 'c2000002-0000-0000-0000-000000000002',
     'pa200001-0000-0000-0000-000000000001', 'AED', 8500000.00, 0.00, 'ACTIVE',
     'PROP-MT-4501', '{"property_type": "PENTHOUSE", "project": "Marina Tower", "unit": "4501", "buyer": "Ahmed Al Maktoum", "seller": "Golden Sands", "completion_date": "2024-06-30"}',
     NOW() - INTERVAL '3 months', NOW()),
    
    -- Property 2: Palm Villa
    ('va300002-0000-0000-0000-000000000002', 'ESC0000002',
     'Palm Signature Villa 12 - Chen/Developer',
     'prog0003-0000-0000-0000-000000000003', 'c2000002-0000-0000-0000-000000000002',
     'pa200001-0000-0000-0000-000000000001', 'AED', 25000000.00, 0.00, 'ACTIVE',
     'PROP-PSV-12', '{"property_type": "VILLA", "project": "Palm Signature", "unit": "V12", "buyer": "Wei Chen", "seller": "Golden Sands", "completion_date": "2024-12-31"}',
     NOW() - INTERVAL '6 months', NOW()),
    
    -- Property 3: Downtown Apartment
    ('va300003-0000-0000-0000-000000000003', 'ESC0000003',
     'Downtown Heights 2305 - Smith/Developer',
     'prog0003-0000-0000-0000-000000000003', 'c2000002-0000-0000-0000-000000000002',
     'pa200001-0000-0000-0000-000000000001', 'AED', 3200000.00, 0.00, 'ACTIVE',
     'PROP-DH-2305', '{"property_type": "APARTMENT", "project": "Downtown Heights", "unit": "2305", "buyer": "James Smith", "seller": "Golden Sands", "completion_date": "2024-09-30"}',
     NOW() - INTERVAL '2 months', NOW())
ON CONFLICT DO NOTHING;

-- Digital Wallet Accounts
INSERT INTO virtual_accounts (
    id, va_number, va_name, program_id, corporate_id, physical_account_id,
    currency_code, current_balance, available_balance, status,
    external_reference, kyc_verified, wallet_type, metadata, created_at, updated_at
) VALUES
    -- Consumer Wallet 1
    ('va400001-0000-0000-0000-000000000001', 'WAL0000001', 'Fatima Al Zahra',
     'prog0004-0000-0000-0000-000000000004', 'c3000003-0000-0000-0000-000000000003',
     'pa300001-0000-0000-0000-000000000001', 'AED', 2500.00, 2500.00, 'ACTIVE',
     'WALLET-FAZ-001', true, 'CONSUMER',
     '{"tier": "GOLD", "kyc_level": "FULL", "daily_limit": 10000}',
     NOW() - INTERVAL '8 months', NOW()),
    
    -- Consumer Wallet 2
    ('va400002-0000-0000-0000-000000000002', 'WAL0000002', 'Raj Patel',
     'prog0004-0000-0000-0000-000000000004', 'c3000003-0000-0000-0000-000000000003',
     'pa300001-0000-0000-0000-000000000001', 'AED', 850.00, 850.00, 'ACTIVE',
     'WALLET-RP-002', true, 'CONSUMER',
     '{"tier": "SILVER", "kyc_level": "BASIC", "daily_limit": 5000}',
     NOW() - INTERVAL '5 months', NOW()),
    
    -- Merchant Wallet 1
    ('va400003-0000-0000-0000-000000000003', 'WAL0000003', 'Quick Bites Restaurant',
     'prog0004-0000-0000-0000-000000000004', 'c3000003-0000-0000-0000-000000000003',
     'pa300001-0000-0000-0000-000000000001', 'AED', 45000.00, 42000.00, 'ACTIVE',
     'WALLET-QBR-003', true, 'MERCHANT',
     '{"merchant_category": "RESTAURANT", "settlement_frequency": "DAILY"}',
     NOW() - INTERVAL '10 months', NOW()),
    
    -- Merchant Wallet 2
    ('va400004-0000-0000-0000-000000000004', 'WAL0000004', 'City Pharmacy',
     'prog0004-0000-0000-0000-000000000004', 'c3000003-0000-0000-0000-000000000003',
     'pa300001-0000-0000-0000-000000000001', 'AED', 28000.00, 28000.00, 'ACTIVE',
     'WALLET-CP-004', true, 'MERCHANT',
     '{"merchant_category": "PHARMACY", "settlement_frequency": "WEEKLY"}',
     NOW() - INTERVAL '7 months', NOW())
ON CONFLICT DO NOTHING;

-- IHB Subsidiary Accounts
INSERT INTO virtual_accounts (
    id, va_number, va_name, program_id, corporate_id, physical_account_id,
    currency_code, current_balance, available_balance, status,
    external_reference, metadata, created_at, updated_at
) VALUES
    -- Subsidiary 1: UAE Operations
    ('va500001-0000-0000-0000-000000000001', 'IHB0000001', 'GTS UAE Operations',
     'prog0005-0000-0000-0000-000000000005', 'c4000004-0000-0000-0000-000000000004',
     'pa400001-0000-0000-0000-000000000001', 'AED', 12500000.00, 12000000.00, 'ACTIVE',
     'SUB-UAE-001', '{"entity_type": "SUBSIDIARY", "country": "AE", "consolidation": true}',
     NOW() - INTERVAL '2 years', NOW()),
    
    -- Subsidiary 2: Saudi Branch
    ('va500002-0000-0000-0000-000000000002', 'IHB0000002', 'GTS Saudi Branch',
     'prog0005-0000-0000-0000-000000000005', 'c4000004-0000-0000-0000-000000000004',
     'pa400001-0000-0000-0000-000000000001', 'AED', 8750000.00, 8500000.00, 'ACTIVE',
     'SUB-SA-002', '{"entity_type": "BRANCH", "country": "SA", "consolidation": true}',
     NOW() - INTERVAL '18 months', NOW()),
    
    -- Subsidiary 3: India Sourcing
    ('va500003-0000-0000-0000-000000000003', 'IHB0000003', 'GTS India Sourcing',
     'prog0005-0000-0000-0000-000000000005', 'c4000004-0000-0000-0000-000000000004',
     'pa400001-0000-0000-0000-000000000001', 'AED', 5250000.00, 5000000.00, 'ACTIVE',
     'SUB-IN-003', '{"entity_type": "SUBSIDIARY", "country": "IN", "consolidation": true}',
     NOW() - INTERVAL '15 months', NOW())
ON CONFLICT DO NOTHING;

-- ==========================================
-- 6. BENEFICIARIES
-- ==========================================

INSERT INTO beneficiaries (
    id, corporate_id, beneficiary_name, beneficiary_type, bank_name,
    swift_code, account_number, iban, currency_code, country_code,
    city, address_line1, status, validation_status, created_at, updated_at
) VALUES
    -- Supplier: China Electronics
    ('ben00001-0000-0000-0000-000000000001', 'c1000001-0000-0000-0000-000000000001',
     'Shenzhen Electronics Manufacturing Co Ltd', 'CORPORATE',
     'Bank of China', 'BKCHCNBJ', '6214830012345678', NULL, 'CNY', 'CN',
     'Shenzhen', '888 Technology Park, Nanshan District',
     'ACTIVE', 'VERIFIED', NOW() - INTERVAL '18 months', NOW()),
    
    -- Supplier: India Textiles
    ('ben00002-0000-0000-0000-000000000002', 'c1000001-0000-0000-0000-000000000001',
     'Mumbai Fashion Exports Pvt Ltd', 'CORPORATE',
     'HDFC Bank', 'HABORINDXXX', '50100123456789', NULL, 'INR', 'IN',
     'Mumbai', '42 Fashion Street, Andheri East',
     'ACTIVE', 'VERIFIED', NOW() - INTERVAL '12 months', NOW()),
    
    -- Property Seller
    ('ben00003-0000-0000-0000-000000000003', 'c2000002-0000-0000-0000-000000000002',
     'Ahmed Khalil Al Maktoum', 'INDIVIDUAL',
     'Emirates NBD', 'EABORAADXXX', '1012345678901', 'AE090260001012345678901', 'AED', 'AE',
     'Dubai', 'Villa 42, Emirates Hills',
     'ACTIVE', 'VERIFIED', NOW() - INTERVAL '6 months', NOW()),
    
    -- Trading Partner: Europe
    ('ben00004-0000-0000-0000-000000000004', 'c4000004-0000-0000-0000-000000000004',
     'European Commodities Trading GmbH', 'CORPORATE',
     'Deutsche Bank', 'DEUTDEFFXXX', 'DE89370400440532013000', 'DE89370400440532013000', 'EUR', 'DE',
     'Frankfurt', 'Taunusanlage 12',
     'ACTIVE', 'VERIFIED', NOW() - INTERVAL '2 years', NOW()),
    
    -- Trading Partner: Singapore
    ('ben00005-0000-0000-0000-000000000005', 'c4000004-0000-0000-0000-000000000004',
     'Asia Pacific Trading Pte Ltd', 'CORPORATE',
     'DBS Bank', 'DBSSSGSGXXX', '0012345678', NULL, 'SGD', 'SG',
     'Singapore', '12 Marina Boulevard, Tower 3',
     'ACTIVE', 'VERIFIED', NOW() - INTERVAL '15 months', NOW())
ON CONFLICT DO NOTHING;

-- ==========================================
-- 7. VA MOVEMENTS (TRANSACTIONS)
-- ==========================================

-- Generate transaction data for the past 90 days
-- Note: This creates realistic transaction patterns

-- Seller Collection Transactions
INSERT INTO va_movements (
    id, movement_type, va_id, physical_account_id, amount, currency_code,
    balance_before, balance_after, transaction_date, value_date,
    reference_number, description, channel, status, created_at
)
SELECT
    gen_random_uuid(),
    'CREDIT',
    'va100001-0000-0000-0000-000000000001',
    'pa100001-0000-0000-0000-000000000001',
    (random() * 5000 + 500)::numeric(18,2),
    'AED',
    120000.00,
    125000.00,
    NOW() - (random() * 90 || ' days')::interval,
    NOW() - (random() * 90 || ' days')::interval,
    'TXN' || lpad((row_number() over())::text, 10, '0'),
    'Customer payment - Order #' || lpad((random() * 100000)::int::text, 6, '0'),
    'ONLINE',
    'COMPLETED',
    NOW() - (random() * 90 || ' days')::interval
FROM generate_series(1, 50);

-- VIBAN Top-up Transactions
INSERT INTO va_movements (
    id, movement_type, va_id, physical_account_id, amount, currency_code,
    balance_before, balance_after, transaction_date, value_date,
    reference_number, description, channel, remitter_name, remitter_account,
    status, created_at
)
SELECT
    gen_random_uuid(),
    'CREDIT',
    'va200001-0000-0000-0000-000000000001',
    'pa100001-0000-0000-0000-000000000001',
    (random() * 2000 + 100)::numeric(18,2),
    'AED',
    5000.00,
    5250.00,
    NOW() - (random() * 60 || ' days')::interval,
    NOW() - (random() * 60 || ' days')::interval,
    'VIB' || lpad((row_number() over())::text, 10, '0'),
    'Wallet top-up via bank transfer',
    'SWIFT',
    'Mohammed Al Farsi',
    'AE070331234567890111111',
    'COMPLETED',
    NOW() - (random() * 60 || ' days')::interval
FROM generate_series(1, 20);

-- Escrow Milestone Payments
INSERT INTO va_movements (
    id, movement_type, va_id, physical_account_id, amount, currency_code,
    balance_before, balance_after, transaction_date, value_date,
    reference_number, description, channel, status, created_at
) VALUES
    -- Marina Tower - Initial Deposit
    (gen_random_uuid(), 'CREDIT', 'va300001-0000-0000-0000-000000000001',
     'pa200001-0000-0000-0000-000000000001', 850000.00, 'AED',
     0.00, 850000.00, NOW() - INTERVAL '90 days', NOW() - INTERVAL '90 days',
     'ESC-MT-4501-001', 'Initial deposit - 10% booking amount', 'WIRE',
     'COMPLETED', NOW() - INTERVAL '90 days'),
    
    -- Marina Tower - 2nd Installment
    (gen_random_uuid(), 'CREDIT', 'va300001-0000-0000-0000-000000000001',
     'pa200001-0000-0000-0000-000000000001', 2550000.00, 'AED',
     850000.00, 3400000.00, NOW() - INTERVAL '60 days', NOW() - INTERVAL '60 days',
     'ESC-MT-4501-002', 'Construction milestone - Foundation complete', 'WIRE',
     'COMPLETED', NOW() - INTERVAL '60 days'),
    
    -- Marina Tower - 3rd Installment
    (gen_random_uuid(), 'CREDIT', 'va300001-0000-0000-0000-000000000001',
     'pa200001-0000-0000-0000-000000000001', 2550000.00, 'AED',
     3400000.00, 5950000.00, NOW() - INTERVAL '30 days', NOW() - INTERVAL '30 days',
     'ESC-MT-4501-003', 'Construction milestone - Structure complete', 'WIRE',
     'COMPLETED', NOW() - INTERVAL '30 days'),
    
    -- Marina Tower - 4th Installment
    (gen_random_uuid(), 'CREDIT', 'va300001-0000-0000-0000-000000000001',
     'pa200001-0000-0000-0000-000000000001', 2550000.00, 'AED',
     5950000.00, 8500000.00, NOW() - INTERVAL '5 days', NOW() - INTERVAL '5 days',
     'ESC-MT-4501-004', 'Construction milestone - Finishing works', 'WIRE',
     'COMPLETED', NOW() - INTERVAL '5 days'),
    
    -- Palm Villa - Initial Deposit
    (gen_random_uuid(), 'CREDIT', 'va300002-0000-0000-0000-000000000002',
     'pa200001-0000-0000-0000-000000000001', 5000000.00, 'AED',
     0.00, 5000000.00, NOW() - INTERVAL '180 days', NOW() - INTERVAL '180 days',
     'ESC-PSV-12-001', 'Initial deposit - 20% booking', 'SWIFT',
     'COMPLETED', NOW() - INTERVAL '180 days'),
    
    -- Palm Villa - 2nd Installment
    (gen_random_uuid(), 'CREDIT', 'va300002-0000-0000-0000-000000000002',
     'pa200001-0000-0000-0000-000000000001', 10000000.00, 'AED',
     5000000.00, 15000000.00, NOW() - INTERVAL '90 days', NOW() - INTERVAL '90 days',
     'ESC-PSV-12-002', 'Construction milestone - 40%', 'SWIFT',
     'COMPLETED', NOW() - INTERVAL '90 days'),
    
    -- Palm Villa - 3rd Installment
    (gen_random_uuid(), 'CREDIT', 'va300002-0000-0000-0000-000000000002',
     'pa200001-0000-0000-0000-000000000001', 10000000.00, 'AED',
     15000000.00, 25000000.00, NOW() - INTERVAL '15 days', NOW() - INTERVAL '15 days',
     'ESC-PSV-12-003', 'Construction milestone - 80%', 'SWIFT',
     'COMPLETED', NOW() - INTERVAL '15 days')
ON CONFLICT DO NOTHING;

-- Wallet Transactions
INSERT INTO va_movements (
    id, movement_type, va_id, physical_account_id, amount, currency_code,
    balance_before, balance_after, transaction_date, value_date,
    reference_number, description, channel, status, created_at
)
SELECT
    gen_random_uuid(),
    CASE WHEN random() > 0.3 THEN 'CREDIT' ELSE 'DEBIT' END,
    'va400001-0000-0000-0000-000000000001',
    'pa300001-0000-0000-0000-000000000001',
    (random() * 500 + 10)::numeric(18,2),
    'AED',
    2400.00,
    2500.00,
    NOW() - (random() * 30 || ' days')::interval,
    NOW() - (random() * 30 || ' days')::interval,
    'WAL' || lpad((row_number() over())::text, 10, '0'),
    CASE WHEN random() > 0.5 THEN 'P2P Transfer' ELSE 'Merchant Payment' END,
    'MOBILE_APP',
    'COMPLETED',
    NOW() - (random() * 30 || ' days')::interval
FROM generate_series(1, 100);

-- IHB Intercompany Transfers
INSERT INTO va_movements (
    id, movement_type, va_id, physical_account_id, amount, currency_code,
    balance_before, balance_after, transaction_date, value_date,
    reference_number, description, channel, counterparty_va_id, status, created_at
) VALUES
    -- UAE to Saudi Transfer
    (gen_random_uuid(), 'DEBIT', 'va500001-0000-0000-0000-000000000001',
     'pa400001-0000-0000-0000-000000000001', 500000.00, 'AED',
     13000000.00, 12500000.00, NOW() - INTERVAL '15 days', NOW() - INTERVAL '15 days',
     'IHB-INT-001', 'Intercompany funding - Saudi operations', 'INTERNAL',
     'va500002-0000-0000-0000-000000000002', 'COMPLETED', NOW() - INTERVAL '15 days'),
    
    (gen_random_uuid(), 'CREDIT', 'va500002-0000-0000-0000-000000000002',
     'pa400001-0000-0000-0000-000000000001', 500000.00, 'AED',
     8250000.00, 8750000.00, NOW() - INTERVAL '15 days', NOW() - INTERVAL '15 days',
     'IHB-INT-001', 'Intercompany receipt - From UAE HQ', 'INTERNAL',
     'va500001-0000-0000-0000-000000000001', 'COMPLETED', NOW() - INTERVAL '15 days'),
    
    -- India to UAE Netting
    (gen_random_uuid(), 'DEBIT', 'va500003-0000-0000-0000-000000000003',
     'pa400001-0000-0000-0000-000000000001', 250000.00, 'AED',
     5500000.00, 5250000.00, NOW() - INTERVAL '7 days', NOW() - INTERVAL '7 days',
     'IHB-NET-001', 'Monthly netting settlement', 'INTERNAL',
     'va500001-0000-0000-0000-000000000001', 'COMPLETED', NOW() - INTERVAL '7 days'),
    
    (gen_random_uuid(), 'CREDIT', 'va500001-0000-0000-0000-000000000001',
     'pa400001-0000-0000-0000-000000000001', 250000.00, 'AED',
     12250000.00, 12500000.00, NOW() - INTERVAL '7 days', NOW() - INTERVAL '7 days',
     'IHB-NET-001', 'Monthly netting receipt - India', 'INTERNAL',
     'va500003-0000-0000-0000-000000000003', 'COMPLETED', NOW() - INTERVAL '7 days')
ON CONFLICT DO NOTHING;

-- ==========================================
-- 8. TRANSACTION GROUPS (for related transactions)
-- ==========================================

INSERT INTO transaction_groups (
    id, group_type, group_reference, description, total_amount,
    currency_code, status, created_at
) VALUES
    -- Escrow Release Group
    ('tg000001-0000-0000-0000-000000000001', 'ESCROW_RELEASE',
     'ESC-REL-MT-4501', 'Marina Tower 4501 milestone release', 8500000.00,
     'AED', 'COMPLETED', NOW() - INTERVAL '5 days'),
    
    -- Daily Settlement Group
    ('tg000002-0000-0000-0000-000000000002', 'SETTLEMENT',
     'SETTLE-2024-001', 'Daily seller settlement batch', 125000.00,
     'AED', 'COMPLETED', NOW() - INTERVAL '1 day'),
    
    -- IHB Netting Group
    ('tg000003-0000-0000-0000-000000000003', 'IHB_NETTING',
     'NET-2024-JAN', 'January intercompany netting', 750000.00,
     'AED', 'COMPLETED', NOW() - INTERVAL '7 days')
ON CONFLICT DO NOTHING;

-- ==========================================
-- 9. AUDIT LOG ENTRIES
-- ==========================================

INSERT INTO audit_logs (
    id, entity_type, entity_id, action, actor_id, actor_type,
    actor_name, changes, ip_address, user_agent, created_at
) VALUES
    -- Corporate onboarding
    (gen_random_uuid(), 'CORPORATE', 'c1000001-0000-0000-0000-000000000001',
     'CREATE', 'admin-001', 'SYSTEM_USER', 'System Admin',
     '{"status": "ACTIVE", "kyc_status": "APPROVED"}',
     '10.0.0.1', 'VAM-Portal/1.0', NOW() - INTERVAL '2 years'),
    
    -- Program creation
    (gen_random_uuid(), 'PROGRAM', 'prog0001-0000-0000-0000-000000000001',
     'CREATE', 'admin-001', 'SYSTEM_USER', 'System Admin',
     '{"program_type": "COLLECTION", "status": "ACTIVE"}',
     '10.0.0.1', 'VAM-Portal/1.0', NOW() - INTERVAL '2 years'),
    
    -- VA creation
    (gen_random_uuid(), 'VIRTUAL_ACCOUNT', 'va100001-0000-0000-0000-000000000001',
     'CREATE', 'user-001', 'CORPORATE_USER', 'Ahmed Al Maktoum',
     '{"status": "ACTIVE", "initial_balance": 0}',
     '192.168.1.100', 'Mozilla/5.0', NOW() - INTERVAL '18 months'),
    
    -- Large transaction alert
    (gen_random_uuid(), 'TRANSACTION', 'va300002-0000-0000-0000-000000000002',
     'LARGE_TRANSACTION_ALERT', 'system', 'SYSTEM', 'Automated Alert',
     '{"amount": 10000000, "threshold": 5000000, "alert_type": "AML_CHECK"}',
     '10.0.0.1', 'VAM-AML-Service/1.0', NOW() - INTERVAL '90 days')
ON CONFLICT DO NOTHING;

-- ==========================================
-- 10. SYSTEM CONFIGURATION
-- ==========================================

INSERT INTO system_config (
    id, config_key, config_value, description, is_encrypted, updated_by, updated_at
) VALUES
    (gen_random_uuid(), 'va.auto_reconciliation.enabled', 'true', 
     'Enable automatic reconciliation for VA transactions', false, 'admin', NOW()),
    (gen_random_uuid(), 'escrow.release.approval_required', 'true',
     'Require manual approval for escrow releases above threshold', false, 'admin', NOW()),
    (gen_random_uuid(), 'escrow.release.approval_threshold', '1000000',
     'Threshold amount for escrow release approval (AED)', false, 'admin', NOW()),
    (gen_random_uuid(), 'wallet.daily_limit.default', '10000',
     'Default daily transaction limit for wallets (AED)', false, 'admin', NOW()),
    (gen_random_uuid(), 'ihb.netting.schedule', '0 0 23 L * ?',
     'Cron schedule for monthly IHB netting (last day of month)', false, 'admin', NOW()),
    (gen_random_uuid(), 'bancs.sync.batch_size', '100',
     'Batch size for BaNCS synchronization', false, 'admin', NOW()),
    (gen_random_uuid(), 'notification.email.enabled', 'true',
     'Enable email notifications', false, 'admin', NOW()),
    (gen_random_uuid(), 'notification.sms.enabled', 'true',
     'Enable SMS notifications', false, 'admin', NOW())
ON CONFLICT DO NOTHING;

-- ==========================================
-- SUMMARY STATISTICS
-- ==========================================

DO $$
DECLARE
    corp_count INT;
    pa_count INT;
    prog_count INT;
    va_count INT;
    txn_count INT;
BEGIN
    SELECT COUNT(*) INTO corp_count FROM corporates;
    SELECT COUNT(*) INTO pa_count FROM physical_accounts;
    SELECT COUNT(*) INTO prog_count FROM unified_programs;
    SELECT COUNT(*) INTO va_count FROM virtual_accounts;
    SELECT COUNT(*) INTO txn_count FROM va_movements;
    
    RAISE NOTICE '';
    RAISE NOTICE '==========================================';
    RAISE NOTICE '  SEED DATA LOADED SUCCESSFULLY';
    RAISE NOTICE '==========================================';
    RAISE NOTICE '  Corporates:        %', corp_count;
    RAISE NOTICE '  Physical Accounts: %', pa_count;
    RAISE NOTICE '  Programs:          %', prog_count;
    RAISE NOTICE '  Virtual Accounts:  %', va_count;
    RAISE NOTICE '  Transactions:      %', txn_count;
    RAISE NOTICE '==========================================';
END $$;
