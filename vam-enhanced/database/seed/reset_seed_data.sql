-- ==========================================
-- VAM Portal - Reset Seed Data Script
-- ==========================================
-- WARNING: This script will DELETE all seed data!
-- Use with caution - only for development/testing
-- ==========================================

-- Confirm before proceeding (comment out to skip)
DO $$
BEGIN
    RAISE NOTICE '';
    RAISE NOTICE '==========================================';
    RAISE NOTICE '  WARNING: DATA DELETION IN PROGRESS';
    RAISE NOTICE '==========================================';
    RAISE NOTICE '  This will delete all seed data.';
    RAISE NOTICE '  Schema and migrations will be preserved.';
    RAISE NOTICE '==========================================';
END $$;

-- ==========================================
-- DISABLE FOREIGN KEY CHECKS TEMPORARILY
-- ==========================================
SET session_replication_role = 'replica';

-- ==========================================
-- DELETE IN CORRECT ORDER (child tables first)
-- ==========================================

-- 1. Audit and Logs
TRUNCATE TABLE audit_logs CASCADE;
RAISE NOTICE 'Cleared: audit_logs';

-- 2. User related
TRUNCATE TABLE user_preferences CASCADE;
TRUNCATE TABLE api_keys CASCADE;
TRUNCATE TABLE user_sessions CASCADE;
RAISE NOTICE 'Cleared: user preferences, api_keys, sessions';

-- 3. Transactions
TRUNCATE TABLE va_movements CASCADE;
TRUNCATE TABLE transaction_groups CASCADE;
TRUNCATE TABLE pending_transactions CASCADE;
RAISE NOTICE 'Cleared: transactions';

-- 4. Beneficiaries
TRUNCATE TABLE beneficiaries CASCADE;
TRUNCATE TABLE beneficiary_validations CASCADE;
RAISE NOTICE 'Cleared: beneficiaries';

-- 5. Virtual Accounts
TRUNCATE TABLE virtual_accounts CASCADE;
TRUNCATE TABLE va_balance_history CASCADE;
RAISE NOTICE 'Cleared: virtual_accounts';

-- 6. Programs
TRUNCATE TABLE unified_programs CASCADE;
TRUNCATE TABLE program_limits CASCADE;
RAISE NOTICE 'Cleared: programs';

-- 7. Physical Accounts
TRUNCATE TABLE physical_accounts CASCADE;
TRUNCATE TABLE account_balance_history CASCADE;
RAISE NOTICE 'Cleared: physical_accounts';

-- 8. Corporates
TRUNCATE TABLE corporates CASCADE;
TRUNCATE TABLE corporate_documents CASCADE;
RAISE NOTICE 'Cleared: corporates';

-- 9. Users (but keep structure)
TRUNCATE TABLE users CASCADE;
TRUNCATE TABLE roles CASCADE;
RAISE NOTICE 'Cleared: users, roles';

-- 10. Reference Data
TRUNCATE TABLE banks CASCADE;
TRUNCATE TABLE countries CASCADE;
TRUNCATE TABLE currencies CASCADE;
RAISE NOTICE 'Cleared: reference data';

-- 11. System Config (optional - uncomment if needed)
-- TRUNCATE TABLE system_config CASCADE;
-- RAISE NOTICE 'Cleared: system_config';

-- 12. Sync Queue (BaNCS related)
TRUNCATE TABLE sync_queue CASCADE;
TRUNCATE TABLE sync_status CASCADE;
RAISE NOTICE 'Cleared: sync tables';

-- ==========================================
-- RE-ENABLE FOREIGN KEY CHECKS
-- ==========================================
SET session_replication_role = 'origin';

-- ==========================================
-- RESET SEQUENCES
-- ==========================================

-- Reset any auto-increment sequences if needed
-- ALTER SEQUENCE table_name_id_seq RESTART WITH 1;

-- ==========================================
-- SUMMARY
-- ==========================================

DO $$
DECLARE
    table_count INT;
BEGIN
    SELECT COUNT(*) INTO table_count 
    FROM information_schema.tables 
    WHERE table_schema = 'public' AND table_type = 'BASE TABLE';
    
    RAISE NOTICE '';
    RAISE NOTICE '==========================================';
    RAISE NOTICE '  SEED DATA RESET COMPLETE';
    RAISE NOTICE '==========================================';
    RAISE NOTICE '  Tables in schema: %', table_count;
    RAISE NOTICE '  All data has been cleared.';
    RAISE NOTICE '';
    RAISE NOTICE '  To reload seed data, run:';
    RAISE NOTICE '    psql -f seed_data.sql';
    RAISE NOTICE '    psql -f demo_users.sql';
    RAISE NOTICE '==========================================';
END $$;
