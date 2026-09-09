-- client_encoding pinned to UTF8 for this session: without it, psql falls
-- back to the shell locale's encoding (often not UTF-8 on a fresh box/CI
-- runner), which silently mangles every non-ASCII character below into
-- mojibake on insert (e.g. '·' becomes 'Â·') -- a data corruption, not a
-- display bug, since it's the bytes actually written to the table. See
-- V14__fix_mojibake_encoding.sql for the one-time repair of data seeded
-- before this fix existed.
SET client_encoding = 'UTF8';

-- ==========================================
-- VAM Portal - Demo Users Seed Script
-- ==========================================
-- Creates demo users for testing the application
-- Passwords are BCrypt hashed
-- 
-- Default password for all users: Demo@123
-- ==========================================

-- BCrypt hash for 'Demo@123'
-- Generated with cost factor 10

-- ==========================================
-- 1. USER ROLES
-- ==========================================

INSERT INTO roles (id, role_name, description, permissions, is_active, created_at) VALUES
    ('role0001-0000-0000-0000-000000000001', 'SUPER_ADMIN', 
     'Full system access', 
     '["*"]', 
     true, NOW()),
    
    ('role0002-0000-0000-0000-000000000002', 'BANK_ADMIN',
     'Bank operations administrator',
     '["corporates:*", "programs:*", "accounts:*", "transactions:read", "reports:*", "settings:read"]',
     true, NOW()),
    
    ('role0003-0000-0000-0000-000000000003', 'BANK_OPERATOR',
     'Bank operations staff',
     '["corporates:read", "programs:read", "accounts:read", "transactions:read", "reports:read"]',
     true, NOW()),
    
    ('role0004-0000-0000-0000-000000000004', 'CORPORATE_ADMIN',
     'Corporate customer administrator',
     '["programs:read", "accounts:*", "transactions:*", "beneficiaries:*", "reports:read"]',
     true, NOW()),
    
    ('role0005-0000-0000-0000-000000000005', 'CORPORATE_USER',
     'Corporate customer user',
     '["accounts:read", "transactions:read", "beneficiaries:read"]',
     true, NOW()),
    
    ('role0006-0000-0000-0000-000000000006', 'AUDITOR',
     'Read-only audit access',
     '["*:read", "audit:*"]',
     true, NOW())
ON CONFLICT (id) DO NOTHING;

-- ==========================================
-- 2. DEMO USERS
-- ==========================================

-- Note: Password hash is for 'Demo@123'
-- In production, use proper password hashing

INSERT INTO users (
    id, username, email, password_hash, first_name, last_name,
    phone_number, role_id, corporate_id, status, email_verified,
    mfa_enabled, last_login, failed_login_attempts, created_at, updated_at
) VALUES
    -- Super Admin
    ('user0001-0000-0000-0000-000000000001',
     'superadmin',
     'superadmin@vamportal.ae',
     '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZRGdjGj/n3.Qk8Z8OKT8OKT8OKT8O', -- Demo@123
     'System',
     'Administrator',
     '+971500000001',
     'role0001-0000-0000-0000-000000000001',
     NULL,
     'ACTIVE',
     true,
     true,
     NOW() - INTERVAL '1 hour',
     0,
     NOW() - INTERVAL '2 years',
     NOW()),
    
    -- Bank Admin
    ('user0002-0000-0000-0000-000000000002',
     'bankadmin',
     'admin@ruyabank.ae',
     '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZRGdjGj/n3.Qk8Z8OKT8OKT8OKT8O',
     'Sarah',
     'Ahmed',
     '+971500000002',
     'role0002-0000-0000-0000-000000000002',
     NULL,
     'ACTIVE',
     true,
     false,
     NOW() - INTERVAL '2 hours',
     0,
     NOW() - INTERVAL '1 year',
     NOW()),
    
    -- Bank Operator
    ('user0003-0000-0000-0000-000000000003',
     'bankops',
     'operations@ruyabank.ae',
     '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZRGdjGj/n3.Qk8Z8OKT8OKT8OKT8O',
     'Mohammed',
     'Ali',
     '+971500000003',
     'role0003-0000-0000-0000-000000000003',
     NULL,
     'ACTIVE',
     true,
     false,
     NOW() - INTERVAL '4 hours',
     0,
     NOW() - INTERVAL '6 months',
     NOW()),
    
    -- Corporate Admin - Desert Oasis
    ('user0004-0000-0000-0000-000000000004',
     'desertoasis_admin',
     'ahmed.maktoum@desertoasis.ae',
     '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZRGdjGj/n3.Qk8Z8OKT8OKT8OKT8O',
     'Ahmed',
     'Al Maktoum',
     '+971501234567',
     'role0004-0000-0000-0000-000000000004',
     'c1000001-0000-0000-0000-000000000001',
     'ACTIVE',
     true,
     true,
     NOW() - INTERVAL '1 day',
     0,
     NOW() - INTERVAL '2 years',
     NOW()),
    
    -- Corporate User - Desert Oasis
    ('user0005-0000-0000-0000-000000000005',
     'desertoasis_user',
     'finance@desertoasis.ae',
     '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZRGdjGj/n3.Qk8Z8OKT8OKT8OKT8O',
     'Layla',
     'Hassan',
     '+971501234568',
     'role0005-0000-0000-0000-000000000005',
     'c1000001-0000-0000-0000-000000000001',
     'ACTIVE',
     true,
     false,
     NOW() - INTERVAL '3 days',
     0,
     NOW() - INTERVAL '18 months',
     NOW()),
    
    -- Corporate Admin - Golden Sands
    ('user0006-0000-0000-0000-000000000006',
     'goldensands_admin',
     'fatima.rashid@goldensands.ae',
     '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZRGdjGj/n3.Qk8Z8OKT8OKT8OKT8O',
     'Fatima',
     'Al Rashid',
     '+971502345678',
     'role0004-0000-0000-0000-000000000004',
     'c2000002-0000-0000-0000-000000000002',
     'ACTIVE',
     true,
     true,
     NOW() - INTERVAL '2 days',
     0,
     NOW() - INTERVAL '3 years',
     NOW()),
    
    -- Corporate Admin - PayFlow
    ('user0007-0000-0000-0000-000000000007',
     'payflow_admin',
     'omar.hassan@payflow.ae',
     '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZRGdjGj/n3.Qk8Z8OKT8OKT8OKT8O',
     'Omar',
     'Hassan',
     '+971503456789',
     'role0004-0000-0000-0000-000000000004',
     'c3000003-0000-0000-0000-000000000003',
     'ACTIVE',
     true,
     false,
     NOW() - INTERVAL '5 hours',
     0,
     NOW() - INTERVAL '1 year',
     NOW()),
    
    -- Corporate Admin - GTS Trading
    ('user0008-0000-0000-0000-000000000008',
     'gts_admin',
     'khalid.ibrahim@gtstrading.ae',
     '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZRGdjGj/n3.Qk8Z8OKT8OKT8OKT8O',
     'Khalid',
     'Ibrahim',
     '+971504567890',
     'role0004-0000-0000-0000-000000000004',
     'c4000004-0000-0000-0000-000000000004',
     'ACTIVE',
     true,
     true,
     NOW() - INTERVAL '12 hours',
     0,
     NOW() - INTERVAL '2 years',
     NOW()),
    
    -- Auditor
    ('user0009-0000-0000-0000-000000000009',
     'auditor',
     'audit@ruyabank.ae',
     '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZRGdjGj/n3.Qk8Z8OKT8OKT8OKT8O',
     'External',
     'Auditor',
     '+971500000009',
     'role0006-0000-0000-0000-000000000006',
     NULL,
     'ACTIVE',
     true,
     false,
     NOW() - INTERVAL '7 days',
     0,
     NOW() - INTERVAL '1 year',
     NOW())
ON CONFLICT (id) DO NOTHING;

-- ==========================================
-- 3. USER PREFERENCES
-- ==========================================

INSERT INTO user_preferences (
    id, user_id, preference_key, preference_value, created_at, updated_at
) VALUES
    -- Super Admin preferences
    (gen_random_uuid(), 'user0001-0000-0000-0000-000000000001', 
     'theme', 'dark', NOW(), NOW()),
    (gen_random_uuid(), 'user0001-0000-0000-0000-000000000001',
     'language', 'en', NOW(), NOW()),
    (gen_random_uuid(), 'user0001-0000-0000-0000-000000000001',
     'notifications.email', 'true', NOW(), NOW()),
    
    -- Bank Admin preferences
    (gen_random_uuid(), 'user0002-0000-0000-0000-000000000002',
     'theme', 'light', NOW(), NOW()),
    (gen_random_uuid(), 'user0002-0000-0000-0000-000000000002',
     'dashboard.default_view', 'overview', NOW(), NOW()),
    
    -- Corporate Admin preferences
    (gen_random_uuid(), 'user0004-0000-0000-0000-000000000004',
     'theme', 'system', NOW(), NOW()),
    (gen_random_uuid(), 'user0004-0000-0000-0000-000000000004',
     'notifications.transaction_alerts', 'true', NOW(), NOW()),
    (gen_random_uuid(), 'user0004-0000-0000-0000-000000000004',
     'notifications.threshold', '100000', NOW(), NOW())
ON CONFLICT DO NOTHING;

-- ==========================================
-- 4. API KEYS (for integration testing)
-- ==========================================

INSERT INTO api_keys (
    id, key_name, api_key_hash, corporate_id, permissions,
    rate_limit, expires_at, is_active, created_by, created_at
) VALUES
    -- Desert Oasis API Key
    ('apikey01-0000-0000-0000-000000000001',
     'Desert Oasis Production API',
     '$2a$10$apikeyhash1234567890abcdefghijklmnopqrstuvwxyz',
     'c1000001-0000-0000-0000-000000000001',
     '["accounts:read", "transactions:create", "transactions:read"]',
     1000,
     NOW() + INTERVAL '1 year',
     true,
     'user0004-0000-0000-0000-000000000004',
     NOW()),
    
    -- PayFlow API Key
    ('apikey02-0000-0000-0000-000000000002',
     'PayFlow Wallet Integration',
     '$2a$10$apikeyhash2345678901bcdefghijklmnopqrstuvwxyza',
     'c3000003-0000-0000-0000-000000000003',
     '["accounts:*", "transactions:*", "wallets:*"]',
     5000,
     NOW() + INTERVAL '1 year',
     true,
     'user0007-0000-0000-0000-000000000007',
     NOW())
ON CONFLICT (id) DO NOTHING;

-- ==========================================
-- DEMO LOGIN CREDENTIALS
-- ==========================================

DO $$
BEGIN
    RAISE NOTICE '';
    RAISE NOTICE '==========================================';
    RAISE NOTICE '  DEMO USERS CREATED';
    RAISE NOTICE '==========================================';
    RAISE NOTICE '';
    RAISE NOTICE '  Default Password: Demo@123';
    RAISE NOTICE '';
    RAISE NOTICE '  Bank Users:';
    RAISE NOTICE '    superadmin / Demo@123 (Super Admin)';
    RAISE NOTICE '    bankadmin  / Demo@123 (Bank Admin)';
    RAISE NOTICE '    bankops    / Demo@123 (Bank Operator)';
    RAISE NOTICE '    auditor    / Demo@123 (Auditor)';
    RAISE NOTICE '';
    RAISE NOTICE '  Corporate Users:';
    RAISE NOTICE '    desertoasis_admin / Demo@123 (E-commerce)';
    RAISE NOTICE '    desertoasis_user  / Demo@123 (E-commerce)';
    RAISE NOTICE '    goldensands_admin / Demo@123 (Real Estate)';
    RAISE NOTICE '    payflow_admin     / Demo@123 (Fintech)';
    RAISE NOTICE '    gts_admin         / Demo@123 (Trading)';
    RAISE NOTICE '';
    RAISE NOTICE '==========================================';
END $$;
