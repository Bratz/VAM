-- Virtual Account Management (VAM) Database Schema
-- PostgreSQL 16

-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ============================================================================
-- SCHEMA: Core Tables
-- ============================================================================

-- Corporate Customers (Master)
CREATE TABLE corporate_customers (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    customer_reference VARCHAR(20) NOT NULL UNIQUE,
    cif_id VARCHAR(20),
    customer_name VARCHAR(200) NOT NULL,
    trade_license_number VARCHAR(50),
    legal_entity_type VARCHAR(50),
    registration_country VARCHAR(3) DEFAULT 'UAE',
    industry_sector VARCHAR(100),
    contact_email VARCHAR(255),
    contact_phone VARCHAR(20),
    address_line1 VARCHAR(200),
    address_line2 VARCHAR(200),
    city VARCHAR(100),
    postal_code VARCHAR(20),
    kyc_status VARCHAR(20) DEFAULT 'PENDING',
    kyc_verified_at TIMESTAMP,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

CREATE INDEX idx_corporate_customers_reference ON corporate_customers(customer_reference);
CREATE INDEX idx_corporate_customers_status ON corporate_customers(status);

-- Corporate Schemes
CREATE TABLE corporate_schemes (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    corporate_scheme_code VARCHAR(20) NOT NULL UNIQUE,
    customer_id UUID NOT NULL REFERENCES corporate_customers(id),
    scheme_name VARCHAR(100) NOT NULL,
    scheme_description TEXT,
    operational_unit VARCHAR(20) NOT NULL DEFAULT 'GBUAERUYOU',
    account_usage INTEGER NOT NULL DEFAULT 1, -- 1-VTA, 2-Summary, 3-VRN, etc.
    currency_code VARCHAR(3) DEFAULT 'AED',
    settlement_account_iban VARCHAR(34),
    settlement_account_bban VARCHAR(23),
    max_virtual_accounts INTEGER DEFAULT 1000,
    daily_transaction_limit DECIMAL(18,2),
    monthly_transaction_limit DECIMAL(18,2),
    auto_sweep_enabled BOOLEAN DEFAULT FALSE,
    sweep_threshold DECIMAL(18,2),
    sweep_target_balance DECIMAL(18,2),
    hierarchy_enabled BOOLEAN DEFAULT FALSE,
    whitelist_required BOOLEAN DEFAULT FALSE,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    effective_from DATE NOT NULL DEFAULT CURRENT_DATE,
    effective_to DATE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

CREATE INDEX idx_corporate_schemes_code ON corporate_schemes(corporate_scheme_code);
CREATE INDEX idx_corporate_schemes_customer ON corporate_schemes(customer_id);

-- Virtual Accounts (Core Entity)
CREATE TABLE virtual_accounts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    virtual_account_number BIGINT NOT NULL UNIQUE,
    virtual_iban VARCHAR(34) NOT NULL UNIQUE,
    virtual_bban VARCHAR(23),
    account_reference1 VARCHAR(50),
    account_reference2 VARCHAR(50),
    corporate_scheme_id UUID NOT NULL REFERENCES corporate_schemes(id),
    customer_id UUID NOT NULL REFERENCES corporate_customers(id),
    account_name VARCHAR(200) NOT NULL,
    local_account_name VARCHAR(200),
    account_sequence VARCHAR(20),
    account_usage INTEGER DEFAULT 1,
    currency_code VARCHAR(3) DEFAULT 'AED',
    
    -- Hierarchy
    parent_account_id UUID REFERENCES virtual_accounts(id),
    parent_account_category INTEGER, -- 1-Real, 2-Virtual
    hierarchy_name VARCHAR(100),
    hierarchy_level INTEGER DEFAULT 0,
    
    -- Balance Information (cached)
    current_balance DECIMAL(18,2) DEFAULT 0,
    available_balance DECIMAL(18,2) DEFAULT 0,
    fund_hold DECIMAL(18,2) DEFAULT 0,
    overdraft_limit DECIMAL(18,2) DEFAULT 0,
    balance_last_updated TIMESTAMP,
    
    -- Status & Dates
    status INTEGER DEFAULT 1, -- 1-Active, 2-OnHold, 3-Pending, etc.
    opened_on DATE NOT NULL DEFAULT CURRENT_DATE,
    closed_on DATE,
    valid_till_date DATE,
    closure_reason INTEGER,
    
    -- Metadata
    fa_classification VARCHAR(50),
    account_manager VARCHAR(100),
    provenance INTEGER DEFAULT 1, -- 1-Backoffice, 2-Branch, 3-Corporate Channel
    initiating_channel VARCHAR(50),
    remarks TEXT,
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    
    -- TCS BaNCS sync
    bancs_synced_at TIMESTAMP,
    bancs_sync_status VARCHAR(20) DEFAULT 'PENDING'
);

CREATE INDEX idx_virtual_accounts_iban ON virtual_accounts(virtual_iban);
CREATE INDEX idx_virtual_accounts_scheme ON virtual_accounts(corporate_scheme_id);
CREATE INDEX idx_virtual_accounts_customer ON virtual_accounts(customer_id);
CREATE INDEX idx_virtual_accounts_parent ON virtual_accounts(parent_account_id);
CREATE INDEX idx_virtual_accounts_status ON virtual_accounts(status);

-- Account Level Definitions (for account numbering scheme)
CREATE TABLE account_level_definitions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    virtual_account_id UUID NOT NULL REFERENCES virtual_accounts(id),
    level_id INTEGER NOT NULL,
    level_description VARCHAR(100) NOT NULL,
    level_value VARCHAR(50) NOT NULL,
    level_format INTEGER NOT NULL, -- 1-Numeric, 2-Alpha, 3-Alphanumeric
    minimum_length INTEGER NOT NULL,
    maximum_length INTEGER NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_account_levels_va ON account_level_definitions(virtual_account_id);

-- ============================================================================
-- SCHEMA: Beneficiary Management
-- ============================================================================

CREATE TABLE beneficiaries (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    beneficiary_reference VARCHAR(50) NOT NULL UNIQUE,
    customer_id UUID NOT NULL REFERENCES corporate_customers(id),
    beneficiary_name VARCHAR(200) NOT NULL,
    beneficiary_nickname VARCHAR(100),
    beneficiary_account VARCHAR(34) NOT NULL,
    beneficiary_account_type INTEGER, -- IBAN, Account Number
    
    -- Bank Details
    beneficiary_bank_name VARCHAR(200),
    beneficiary_bank_bic VARCHAR(11),
    beneficiary_bank_identifier_type INTEGER DEFAULT 150,
    local_clearing_code VARCHAR(20),
    
    -- Address
    beneficiary_address_line1 VARCHAR(200),
    beneficiary_address_line2 VARCHAR(200),
    beneficiary_address_line3 VARCHAR(200),
    beneficiary_country VARCHAR(3),
    
    -- Bank Address
    bank_address_line1 VARCHAR(200),
    bank_address_line2 VARCHAR(200),
    bank_address_line3 VARCHAR(200),
    bank_country VARCHAR(3),
    
    -- Configuration
    scheme_type INTEGER DEFAULT 1, -- 1-Outside Bank, 20-Within Bank
    currency_code VARCHAR(3) DEFAULT 'AED',
    default_purpose_code VARCHAR(10),
    
    -- Status
    status VARCHAR(20) DEFAULT 'ACTIVE',
    start_date DATE NOT NULL DEFAULT CURRENT_DATE,
    end_date DATE,
    
    -- TCS BaNCS Reference
    bancs_transaction_reference BIGINT,
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

CREATE INDEX idx_beneficiaries_customer ON beneficiaries(customer_id);
CREATE INDEX idx_beneficiaries_account ON beneficiaries(beneficiary_account);
CREATE INDEX idx_beneficiaries_status ON beneficiaries(status);

-- ============================================================================
-- SCHEMA: Whitelist Management
-- ============================================================================

CREATE TABLE whitelisted_accounts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    virtual_account_id UUID NOT NULL REFERENCES virtual_accounts(id),
    whitelisted_account_reference VARCHAR(34) NOT NULL,
    whitelisted_account_currency VARCHAR(3) DEFAULT 'AED',
    within_out_flag INTEGER NOT NULL, -- 1-Within Bank, 2-Outside Bank
    direction INTEGER NOT NULL, -- 1-Incoming, 2-Outgoing, 3-Both
    bank_identifier_code VARCHAR(11),
    local_clearing_code VARCHAR(20),
    valid_from DATE NOT NULL,
    valid_till DATE NOT NULL,
    status INTEGER DEFAULT 1, -- 1-Active, 2-Inactive
    deletion_flag INTEGER DEFAULT 0,
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    
    UNIQUE(virtual_account_id, whitelisted_account_reference)
);

CREATE INDEX idx_whitelist_va ON whitelisted_accounts(virtual_account_id);
CREATE INDEX idx_whitelist_account ON whitelisted_accounts(whitelisted_account_reference);

-- ============================================================================
-- SCHEMA: Cash Block Management
-- ============================================================================

CREATE TABLE cash_blocks (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    cash_block_id BIGINT NOT NULL UNIQUE,
    external_cash_block_id BIGINT,
    virtual_account_id UUID NOT NULL REFERENCES virtual_accounts(id),
    settlement_account VARCHAR(34),
    
    -- Transaction Details
    transaction_reference BIGINT,
    transaction_id BIGINT,
    transaction_currency VARCHAR(3),
    transaction_amount DECIMAL(18,2),
    
    -- Block Details
    block_currency VARCHAR(3),
    block_amount DECIMAL(18,2) NOT NULL,
    block_date DATE NOT NULL,
    release_date DATE,
    
    -- Configuration
    block_advance_flag INTEGER,
    auto_release_flag INTEGER DEFAULT 0,
    block_purpose INTEGER,
    block_level INTEGER,
    
    -- Exchange
    exchange_rate DECIMAL(18,8),
    
    -- Status
    status VARCHAR(20) DEFAULT 'ACTIVE',
    sent_back_flag INTEGER DEFAULT 0,
    remarks TEXT,
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

CREATE INDEX idx_cash_blocks_va ON cash_blocks(virtual_account_id);
CREATE INDEX idx_cash_blocks_status ON cash_blocks(status);

-- ============================================================================
-- SCHEMA: Transaction Management
-- ============================================================================

CREATE TABLE transactions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    
    -- References
    transaction_reference VARCHAR(50) NOT NULL UNIQUE,
    related_transaction_reference VARCHAR(50),
    e2e_reference VARCHAR(50),
    uetr VARCHAR(36), -- UUID format for SWIFT gpi
    payment_reference VARCHAR(50),
    
    -- Debit Account
    debtor_account_id UUID REFERENCES virtual_accounts(id),
    debtor_account VARCHAR(34) NOT NULL,
    debtor_account_type INTEGER,
    debtor_cif_id VARCHAR(20),
    debtor_name VARCHAR(200),
    debtor_address VARCHAR(500),
    
    -- Credit Account / Beneficiary
    beneficiary_id UUID REFERENCES beneficiaries(id),
    beneficiary_account VARCHAR(34) NOT NULL,
    beneficiary_account_type INTEGER,
    beneficiary_name VARCHAR(200),
    beneficiary_address VARCHAR(500),
    
    -- Beneficiary Bank
    beneficiary_bank_name VARCHAR(200),
    beneficiary_bank_bic VARCHAR(11),
    beneficiary_bank_country VARCHAR(3),
    
    -- Intermediary Bank
    intermediary_name VARCHAR(200),
    intermediary_identifier VARCHAR(50),
    intermediary_account VARCHAR(34),
    intermediary_country VARCHAR(3),
    
    -- Amount Details
    instructed_amount DECIMAL(18,2) NOT NULL,
    instructed_currency VARCHAR(3) NOT NULL,
    transfer_amount DECIMAL(18,2),
    transfer_currency VARCHAR(3),
    exchange_rate DECIMAL(18,8),
    forex_contract_reference VARCHAR(50),
    
    -- Charges
    charge_option INTEGER, -- OUR, BEN, SHA
    charge_account VARCHAR(34),
    waive_charge_flag INTEGER DEFAULT 0,
    total_charges DECIMAL(18,2) DEFAULT 0,
    
    -- Dates
    requested_execution_date DATE,
    value_date DATE,
    transaction_date DATE NOT NULL DEFAULT CURRENT_DATE,
    
    -- Payment Details
    clearing_type INTEGER, -- 1-RTGS, 2-ACH, 3-Internal
    instrument_id INTEGER,
    purpose_code VARCHAR(10),
    category_purpose VARCHAR(50),
    
    -- Remittance Info
    remittance_info_line1 VARCHAR(140),
    remittance_info_line2 VARCHAR(140),
    remittance_info_line3 VARCHAR(140),
    remittance_info_line4 VARCHAR(140),
    
    -- Regulatory
    regulatory_reporting_line1 VARCHAR(100),
    regulatory_reporting_line2 VARCHAR(100),
    regulatory_reporting_line3 VARCHAR(100),
    regulatory_reporting_line4 VARCHAR(100),
    
    -- Sender to Receiver
    sender_to_receiver_info TEXT,
    
    -- Status
    status VARCHAR(20) DEFAULT 'PENDING',
    status_reason TEXT,
    initiation_flag INTEGER, -- 1-Initiate, 2-Validate only
    cash_block_ref BIGINT,
    
    -- Narratives
    booking_narrative VARCHAR(200),
    debit_remarks VARCHAR(200),
    
    -- Channel
    initiating_channel VARCHAR(50),
    initiating_branch VARCHAR(50),
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    
    -- TCS BaNCS Sync
    bancs_payment_reference VARCHAR(50),
    bancs_status VARCHAR(20),
    bancs_synced_at TIMESTAMP
);

CREATE INDEX idx_transactions_reference ON transactions(transaction_reference);
CREATE INDEX idx_transactions_debtor ON transactions(debtor_account_id);
CREATE INDEX idx_transactions_beneficiary ON transactions(beneficiary_id);
CREATE INDEX idx_transactions_status ON transactions(status);
CREATE INDEX idx_transactions_date ON transactions(transaction_date);
CREATE INDEX idx_transactions_uetr ON transactions(uetr);

-- Transaction Charges
CREATE TABLE transaction_charges (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    transaction_id UUID NOT NULL REFERENCES transactions(id),
    charge_item INTEGER NOT NULL,
    charge_currency VARCHAR(3) NOT NULL,
    charge_amount DECIMAL(18,2) NOT NULL,
    child_sequence_identifier INTEGER,
    external_bank_fee_ref VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_tx_charges_transaction ON transaction_charges(transaction_id);

-- ============================================================================
-- SCHEMA: Account Statements (Cached)
-- ============================================================================

CREATE TABLE account_statements (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    virtual_account_id UUID NOT NULL REFERENCES virtual_accounts(id),
    movement_id BIGINT NOT NULL,
    
    -- Transaction Details
    transaction_amount DECIMAL(18,2) NOT NULL,
    transaction_currency VARCHAR(3) NOT NULL,
    credit_amount DECIMAL(18,2),
    debit_amount DECIMAL(18,2),
    exchange_rate DECIMAL(18,8),
    
    -- Dates
    transaction_date DATE NOT NULL,
    value_date DATE NOT NULL,
    
    -- Description
    movement_description VARCHAR(500),
    
    -- Balance
    running_balance DECIMAL(18,2) NOT NULL,
    
    -- References
    processing_system_txn_reference VARCHAR(50),
    initiating_channel_txn_reference VARCHAR(50),
    uetr VARCHAR(36),
    e2e_identification VARCHAR(50),
    settlement_account VARCHAR(34),
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    UNIQUE(virtual_account_id, movement_id)
);

CREATE INDEX idx_statements_va ON account_statements(virtual_account_id);
CREATE INDEX idx_statements_date ON account_statements(transaction_date);
CREATE INDEX idx_statements_value_date ON account_statements(value_date);

-- ============================================================================
-- SCHEMA: User & Role Management
-- ============================================================================

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    username VARCHAR(100) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    phone VARCHAR(20),
    customer_id UUID REFERENCES corporate_customers(id),
    
    -- Status
    status VARCHAR(20) DEFAULT 'ACTIVE',
    email_verified BOOLEAN DEFAULT FALSE,
    mfa_enabled BOOLEAN DEFAULT FALSE,
    mfa_secret VARCHAR(100),
    
    -- Login tracking
    last_login_at TIMESTAMP,
    failed_login_attempts INTEGER DEFAULT 0,
    locked_until TIMESTAMP,
    
    -- Preferences
    timezone VARCHAR(50) DEFAULT 'Asia/Dubai',
    language VARCHAR(10) DEFAULT 'en',
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

CREATE INDEX idx_users_customer ON users(customer_id);
CREATE INDEX idx_users_email ON users(email);

CREATE TABLE roles (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    role_name VARCHAR(50) NOT NULL UNIQUE,
    role_description TEXT,
    is_system_role BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE permissions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    permission_code VARCHAR(100) NOT NULL UNIQUE,
    permission_name VARCHAR(200) NOT NULL,
    module VARCHAR(50) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE role_permissions (
    role_id UUID NOT NULL REFERENCES roles(id),
    permission_id UUID NOT NULL REFERENCES permissions(id),
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users(id),
    role_id UUID NOT NULL REFERENCES roles(id),
    assigned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    assigned_by VARCHAR(100),
    PRIMARY KEY (user_id, role_id)
);

-- User to Virtual Account access control
CREATE TABLE user_account_access (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id),
    virtual_account_id UUID REFERENCES virtual_accounts(id),
    corporate_scheme_id UUID REFERENCES corporate_schemes(id),
    access_level VARCHAR(20) DEFAULT 'VIEW', -- VIEW, TRANSACT, ADMIN
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    
    UNIQUE(user_id, virtual_account_id),
    UNIQUE(user_id, corporate_scheme_id)
);

-- ============================================================================
-- SCHEMA: Audit & Logging
-- ============================================================================

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    entity_type VARCHAR(50) NOT NULL,
    entity_id UUID NOT NULL,
    action VARCHAR(20) NOT NULL, -- CREATE, UPDATE, DELETE, VIEW
    old_values JSONB,
    new_values JSONB,
    changed_fields TEXT[],
    user_id UUID REFERENCES users(id),
    user_ip VARCHAR(45),
    user_agent TEXT,
    session_id VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_entity ON audit_logs(entity_type, entity_id);
CREATE INDEX idx_audit_user ON audit_logs(user_id);
CREATE INDEX idx_audit_created ON audit_logs(created_at);

-- API Call Logs (for TCS BaNCS integration)
CREATE TABLE api_call_logs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    api_name VARCHAR(100) NOT NULL,
    api_endpoint VARCHAR(500) NOT NULL,
    http_method VARCHAR(10) NOT NULL,
    request_headers JSONB,
    request_body JSONB,
    response_status INTEGER,
    response_body JSONB,
    response_time_ms INTEGER,
    correlation_id VARCHAR(50),
    user_id UUID REFERENCES users(id),
    error_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_api_logs_name ON api_call_logs(api_name);
CREATE INDEX idx_api_logs_correlation ON api_call_logs(correlation_id);
CREATE INDEX idx_api_logs_created ON api_call_logs(created_at);

-- ============================================================================
-- SCHEMA: Notifications
-- ============================================================================

CREATE TABLE notifications (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID REFERENCES users(id),
    customer_id UUID REFERENCES corporate_customers(id),
    notification_type VARCHAR(50) NOT NULL,
    title VARCHAR(200) NOT NULL,
    message TEXT NOT NULL,
    metadata JSONB,
    priority VARCHAR(20) DEFAULT 'NORMAL',
    read_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_notifications_user ON notifications(user_id);
CREATE INDEX idx_notifications_customer ON notifications(customer_id);
CREATE INDEX idx_notifications_unread ON notifications(user_id) WHERE read_at IS NULL;

-- Webhook Subscriptions
CREATE TABLE webhook_subscriptions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    customer_id UUID NOT NULL REFERENCES corporate_customers(id),
    event_type VARCHAR(50) NOT NULL,
    webhook_url VARCHAR(500) NOT NULL,
    secret_key VARCHAR(100),
    headers JSONB,
    is_active BOOLEAN DEFAULT TRUE,
    retry_count INTEGER DEFAULT 3,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================================
-- SCHEMA: Reference Data
-- ============================================================================

CREATE TABLE purpose_codes (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    purpose_code VARCHAR(10) NOT NULL UNIQUE,
    purpose_description VARCHAR(200) NOT NULL,
    category VARCHAR(50),
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE currencies (
    currency_code VARCHAR(3) PRIMARY KEY,
    currency_name VARCHAR(100) NOT NULL,
    currency_number INTEGER,
    decimal_places INTEGER DEFAULT 2,
    is_active BOOLEAN DEFAULT TRUE
);

CREATE TABLE banks (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    bank_bic VARCHAR(11) NOT NULL UNIQUE,
    bank_name VARCHAR(200) NOT NULL,
    bank_country VARCHAR(3) NOT NULL,
    local_clearing_code VARCHAR(20),
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================================
-- INITIAL DATA
-- ============================================================================

-- Insert default roles
INSERT INTO roles (role_name, role_description, is_system_role) VALUES
('SUPER_ADMIN', 'Full system access', TRUE),
('CORPORATE_ADMIN', 'Corporate customer administrator', FALSE),
('FINANCE_MANAGER', 'Can initiate and approve transactions', FALSE),
('FINANCE_USER', 'Can view and initiate transactions', FALSE),
('VIEWER', 'Read-only access', FALSE);

-- Insert permissions
INSERT INTO permissions (permission_code, permission_name, module) VALUES
-- Virtual Account Module
('VA_VIEW', 'View Virtual Accounts', 'VIRTUAL_ACCOUNT'),
('VA_CREATE', 'Create Virtual Accounts', 'VIRTUAL_ACCOUNT'),
('VA_UPDATE', 'Update Virtual Accounts', 'VIRTUAL_ACCOUNT'),
('VA_CLOSE', 'Close Virtual Accounts', 'VIRTUAL_ACCOUNT'),
('VA_HIERARCHY', 'Manage Account Hierarchy', 'VIRTUAL_ACCOUNT'),
-- Beneficiary Module
('BENE_VIEW', 'View Beneficiaries', 'BENEFICIARY'),
('BENE_CREATE', 'Create Beneficiaries', 'BENEFICIARY'),
('BENE_UPDATE', 'Update Beneficiaries', 'BENEFICIARY'),
('BENE_DELETE', 'Delete Beneficiaries', 'BENEFICIARY'),
-- Transaction Module
('TXN_VIEW', 'View Transactions', 'TRANSACTION'),
('TXN_INITIATE', 'Initiate Transactions', 'TRANSACTION'),
('TXN_APPROVE', 'Approve Transactions', 'TRANSACTION'),
('TXN_CANCEL', 'Cancel Transactions', 'TRANSACTION'),
-- Statement Module
('STMT_VIEW', 'View Statements', 'STATEMENT'),
('STMT_DOWNLOAD', 'Download Statements', 'STATEMENT'),
-- Whitelist Module
('WL_VIEW', 'View Whitelist', 'WHITELIST'),
('WL_MANAGE', 'Manage Whitelist', 'WHITELIST'),
-- Admin Module
('USER_MANAGE', 'Manage Users', 'ADMIN'),
('ROLE_MANAGE', 'Manage Roles', 'ADMIN'),
('AUDIT_VIEW', 'View Audit Logs', 'ADMIN'),
('SETTINGS_MANAGE', 'Manage Settings', 'ADMIN');

-- Insert currencies
INSERT INTO currencies (currency_code, currency_name, currency_number, decimal_places) VALUES
('AED', 'UAE Dirham', 784, 2),
('USD', 'US Dollar', 840, 2),
('EUR', 'Euro', 978, 2),
('GBP', 'British Pound', 826, 2),
('SAR', 'Saudi Riyal', 682, 2),
('KWD', 'Kuwaiti Dinar', 414, 3),
('BHD', 'Bahraini Dinar', 48, 3),
('OMR', 'Omani Rial', 512, 3),
('QAR', 'Qatari Riyal', 634, 2),
('INR', 'Indian Rupee', 356, 2);

-- ============================================================================
-- FUNCTIONS & TRIGGERS
-- ============================================================================

-- Updated timestamp trigger function
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

-- Apply trigger to all tables with updated_at
CREATE TRIGGER update_corporate_customers_updated_at BEFORE UPDATE ON corporate_customers FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER update_corporate_schemes_updated_at BEFORE UPDATE ON corporate_schemes FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER update_virtual_accounts_updated_at BEFORE UPDATE ON virtual_accounts FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER update_beneficiaries_updated_at BEFORE UPDATE ON beneficiaries FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER update_whitelisted_accounts_updated_at BEFORE UPDATE ON whitelisted_accounts FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER update_cash_blocks_updated_at BEFORE UPDATE ON cash_blocks FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER update_transactions_updated_at BEFORE UPDATE ON transactions FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER update_users_updated_at BEFORE UPDATE ON users FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- Function to calculate account balance
CREATE OR REPLACE FUNCTION calculate_virtual_account_balance(account_id UUID)
RETURNS TABLE(current_bal DECIMAL, available_bal DECIMAL, held_amount DECIMAL) AS $$
BEGIN
    RETURN QUERY
    SELECT 
        COALESCE(SUM(CASE WHEN credit_amount IS NOT NULL THEN credit_amount ELSE 0 END) - 
                 SUM(CASE WHEN debit_amount IS NOT NULL THEN debit_amount ELSE 0 END), 0) as current_bal,
        COALESCE(SUM(CASE WHEN credit_amount IS NOT NULL THEN credit_amount ELSE 0 END) - 
                 SUM(CASE WHEN debit_amount IS NOT NULL THEN debit_amount ELSE 0 END), 0) -
        COALESCE((SELECT SUM(block_amount) FROM cash_blocks cb WHERE cb.virtual_account_id = account_id AND cb.status = 'ACTIVE'), 0) as available_bal,
        COALESCE((SELECT SUM(block_amount) FROM cash_blocks cb WHERE cb.virtual_account_id = account_id AND cb.status = 'ACTIVE'), 0) as held_amount
    FROM account_statements
    WHERE virtual_account_id = account_id;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- VIEWS
-- ============================================================================

-- Virtual Account Summary View
CREATE OR REPLACE VIEW vw_virtual_account_summary AS
SELECT 
    va.id,
    va.virtual_account_number,
    va.virtual_iban,
    va.account_name,
    va.currency_code,
    va.current_balance,
    va.available_balance,
    va.fund_hold,
    va.status,
    va.opened_on,
    cs.corporate_scheme_code,
    cs.scheme_name,
    cc.customer_reference,
    cc.customer_name,
    (SELECT COUNT(*) FROM whitelisted_accounts wa WHERE wa.virtual_account_id = va.id AND wa.status = 1) as whitelist_count,
    (SELECT COUNT(*) FROM transactions t WHERE t.debtor_account_id = va.id) as transaction_count
FROM virtual_accounts va
JOIN corporate_schemes cs ON va.corporate_scheme_id = cs.id
JOIN corporate_customers cc ON va.customer_id = cc.id;

-- Transaction Summary View
CREATE OR REPLACE VIEW vw_transaction_summary AS
SELECT 
    t.id,
    t.transaction_reference,
    t.e2e_reference,
    t.debtor_account,
    t.beneficiary_account,
    t.beneficiary_name,
    t.instructed_amount,
    t.instructed_currency,
    t.status,
    t.transaction_date,
    t.value_date,
    t.purpose_code,
    t.clearing_type,
    va.virtual_iban as debtor_iban,
    va.account_name as debtor_account_name,
    cc.customer_name
FROM transactions t
LEFT JOIN virtual_accounts va ON t.debtor_account_id = va.id
LEFT JOIN corporate_customers cc ON va.customer_id = cc.id;

-- Daily Position View
CREATE OR REPLACE VIEW vw_daily_position AS
SELECT 
    cs.corporate_scheme_code,
    cs.scheme_name,
    cc.customer_name,
    cs.currency_code,
    COUNT(va.id) as total_accounts,
    COUNT(va.id) FILTER (WHERE va.status = 1) as active_accounts,
    SUM(va.current_balance) as total_balance,
    SUM(va.available_balance) as total_available,
    SUM(va.fund_hold) as total_held
FROM corporate_schemes cs
JOIN corporate_customers cc ON cs.customer_id = cc.id
LEFT JOIN virtual_accounts va ON va.corporate_scheme_id = cs.id
GROUP BY cs.id, cs.corporate_scheme_code, cs.scheme_name, cc.customer_name, cs.currency_code;
