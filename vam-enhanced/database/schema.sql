-- ============================================================================
-- Virtual Account Management (VAM) Enhanced Database Schema
-- PostgreSQL 16
-- Features: Digital Escrow, KYCC, Wallet Programs, BaNCS Fallback Sync
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ============================================================================
-- CORE TABLES (Enhanced from Original)
-- ============================================================================

-- Corporate Customers (The Seller/Program Operator)
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

CREATE INDEX idx_corp_cust_ref ON corporate_customers(customer_reference);
CREATE INDEX idx_corp_cust_status ON corporate_customers(status);

-- Corporate Schemes (Enhanced with Program Types)
CREATE TABLE corporate_schemes (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    corporate_scheme_code VARCHAR(20) NOT NULL UNIQUE,
    customer_id UUID NOT NULL REFERENCES corporate_customers(id),
    scheme_name VARCHAR(100) NOT NULL,
    scheme_description TEXT,
    
    -- Program Type: STANDARD, ESCROW, WALLET
    program_type VARCHAR(20) NOT NULL DEFAULT 'STANDARD',
    
    operational_unit VARCHAR(20) NOT NULL DEFAULT 'GBUAERUYOU',
    account_usage INTEGER NOT NULL DEFAULT 1,
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
    
    -- KYCC Requirements for Beneficiaries
    kycc_required BOOLEAN DEFAULT FALSE,
    kycc_verification_level VARCHAR(20) DEFAULT 'BASIC', -- BASIC, STANDARD, ENHANCED
    
    status VARCHAR(20) DEFAULT 'ACTIVE',
    effective_from DATE NOT NULL DEFAULT CURRENT_DATE,
    effective_to DATE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

CREATE INDEX idx_corp_scheme_code ON corporate_schemes(corporate_scheme_code);
CREATE INDEX idx_corp_scheme_customer ON corporate_schemes(customer_id);
CREATE INDEX idx_corp_scheme_program ON corporate_schemes(program_type);

-- Virtual Accounts (Enhanced)
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
    
    -- Account Type: STANDARD, ESCROW, WALLET
    account_type VARCHAR(20) DEFAULT 'STANDARD',
    
    -- Hierarchy
    parent_account_id UUID REFERENCES virtual_accounts(id),
    parent_account_category INTEGER,
    hierarchy_name VARCHAR(100),
    hierarchy_level INTEGER DEFAULT 0,
    
    -- Balance (Local Cache)
    current_balance DECIMAL(18,2) DEFAULT 0,
    available_balance DECIMAL(18,2) DEFAULT 0,
    fund_hold DECIMAL(18,2) DEFAULT 0,
    overdraft_limit DECIMAL(18,2) DEFAULT 0,
    balance_last_updated TIMESTAMP,
    
    -- Status & Dates
    status INTEGER DEFAULT 1,
    opened_on DATE NOT NULL DEFAULT CURRENT_DATE,
    closed_on DATE,
    valid_till_date DATE,
    closure_reason INTEGER,
    
    -- Metadata
    fa_classification VARCHAR(50),
    account_manager VARCHAR(100),
    provenance INTEGER DEFAULT 1,
    initiating_channel VARCHAR(50),
    remarks TEXT,
    
    -- BaNCS Sync Status
    bancs_synced_at TIMESTAMP,
    bancs_sync_status VARCHAR(20) DEFAULT 'PENDING', -- PENDING, SYNCED, FAILED, LOCAL_ONLY
    bancs_sync_error TEXT,
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

CREATE INDEX idx_va_iban ON virtual_accounts(virtual_iban);
CREATE INDEX idx_va_scheme ON virtual_accounts(corporate_scheme_id);
CREATE INDEX idx_va_customer ON virtual_accounts(customer_id);
CREATE INDEX idx_va_parent ON virtual_accounts(parent_account_id);
CREATE INDEX idx_va_status ON virtual_accounts(status);
CREATE INDEX idx_va_type ON virtual_accounts(account_type);
CREATE INDEX idx_va_sync_status ON virtual_accounts(bancs_sync_status);

-- Account Level Definitions
CREATE TABLE account_level_definitions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    virtual_account_id UUID NOT NULL REFERENCES virtual_accounts(id),
    level_id INTEGER NOT NULL,
    level_description VARCHAR(100) NOT NULL,
    level_value VARCHAR(50) NOT NULL,
    level_format INTEGER NOT NULL,
    minimum_length INTEGER NOT NULL,
    maximum_length INTEGER NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_acct_levels_va ON account_level_definitions(virtual_account_id);

-- ============================================================================
-- KYCC - Know Your Customer's Clients (Beneficiary KYC)
-- ============================================================================

-- KYCC Records (Linked to Beneficiaries)
CREATE TABLE kycc_records (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    kycc_reference VARCHAR(50) NOT NULL UNIQUE,
    
    -- Minimal KYCC Fields as specified
    full_name VARCHAR(200) NOT NULL,
    mobile_number VARCHAR(20) NOT NULL,
    email_address VARCHAR(255),
    kyc_id_type VARCHAR(50) NOT NULL, -- EMIRATES_ID, PASSPORT, NATIONAL_ID, etc.
    kyc_id_number VARCHAR(50) NOT NULL,
    kyc_id_expiry DATE,
    kyc_id_country VARCHAR(3),
    
    -- Verification Status
    verification_status VARCHAR(20) DEFAULT 'PENDING', -- PENDING, VERIFIED, REJECTED, EXPIRED
    verification_method VARCHAR(50), -- MANUAL, OTP, DOCUMENT_CHECK
    verified_at TIMESTAMP,
    verified_by VARCHAR(100),
    rejection_reason TEXT,
    
    -- Risk Classification
    risk_score INTEGER DEFAULT 0,
    risk_category VARCHAR(20) DEFAULT 'LOW', -- LOW, MEDIUM, HIGH
    
    -- Consent & Compliance
    consent_given BOOLEAN DEFAULT FALSE,
    consent_date TIMESTAMP,
    privacy_notice_accepted BOOLEAN DEFAULT FALSE,
    
    -- Validity
    valid_from DATE DEFAULT CURRENT_DATE,
    valid_until DATE,
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

CREATE INDEX idx_kycc_ref ON kycc_records(kycc_reference);
CREATE INDEX idx_kycc_mobile ON kycc_records(mobile_number);
CREATE INDEX idx_kycc_email ON kycc_records(email_address);
CREATE INDEX idx_kycc_id ON kycc_records(kyc_id_type, kyc_id_number);
CREATE INDEX idx_kycc_status ON kycc_records(verification_status);

-- ============================================================================
-- BENEFICIARIES (Enhanced with KYCC Link)
-- ============================================================================

CREATE TABLE beneficiaries (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    beneficiary_reference VARCHAR(50) NOT NULL UNIQUE,
    customer_id UUID NOT NULL REFERENCES corporate_customers(id),
    
    -- KYCC Link (for Escrow: Buyer, for Wallet: Wallet Holder)
    kycc_id UUID REFERENCES kycc_records(id),
    
    -- Beneficiary Details
    beneficiary_name VARCHAR(200) NOT NULL,
    beneficiary_nickname VARCHAR(100),
    beneficiary_account VARCHAR(34) NOT NULL,
    beneficiary_account_type INTEGER,
    
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
    scheme_type INTEGER DEFAULT 1,
    currency_code VARCHAR(3) DEFAULT 'AED',
    default_purpose_code VARCHAR(10),
    
    -- Beneficiary Role: STANDARD, BUYER (Escrow), WALLET_HOLDER
    beneficiary_role VARCHAR(20) DEFAULT 'STANDARD',
    
    -- Status
    status VARCHAR(20) DEFAULT 'ACTIVE',
    start_date DATE NOT NULL DEFAULT CURRENT_DATE,
    end_date DATE,
    
    -- BaNCS Reference & Sync
    bancs_transaction_reference BIGINT,
    bancs_sync_status VARCHAR(20) DEFAULT 'PENDING',
    bancs_sync_error TEXT,
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

CREATE INDEX idx_bene_customer ON beneficiaries(customer_id);
CREATE INDEX idx_bene_account ON beneficiaries(beneficiary_account);
CREATE INDEX idx_bene_kycc ON beneficiaries(kycc_id);
CREATE INDEX idx_bene_role ON beneficiaries(beneficiary_role);
CREATE INDEX idx_bene_status ON beneficiaries(status);
CREATE INDEX idx_bene_sync ON beneficiaries(bancs_sync_status);

-- ============================================================================
-- DIGITAL ESCROW MANAGEMENT
-- ============================================================================

-- Escrow Contracts (Corporate=Seller, Beneficiary=Buyer)
CREATE TABLE escrow_contracts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    contract_reference VARCHAR(50) NOT NULL UNIQUE,
    
    -- Parties
    seller_id UUID NOT NULL REFERENCES corporate_customers(id), -- Corporate
    buyer_id UUID NOT NULL REFERENCES beneficiaries(id),        -- Beneficiary with KYCC
    
    -- Escrow Account
    escrow_account_id UUID NOT NULL REFERENCES virtual_accounts(id),
    
    -- Contract Details
    contract_name VARCHAR(200) NOT NULL,
    contract_description TEXT,
    contract_type VARCHAR(50) NOT NULL, -- GOODS, SERVICES, REAL_ESTATE, VEHICLE, etc.
    
    -- Financial Terms
    contract_amount DECIMAL(18,2) NOT NULL,
    currency_code VARCHAR(3) DEFAULT 'AED',
    deposit_amount DECIMAL(18,2), -- Initial deposit required
    escrow_fee DECIMAL(18,2),
    escrow_fee_payer VARCHAR(20) DEFAULT 'BUYER', -- BUYER, SELLER, SPLIT
    
    -- Release Conditions
    release_type VARCHAR(20) NOT NULL, -- MILESTONE, FULL, PARTIAL
    auto_release_enabled BOOLEAN DEFAULT FALSE,
    auto_release_days INTEGER, -- Days after condition met
    
    -- Timeline
    contract_date DATE NOT NULL DEFAULT CURRENT_DATE,
    expiry_date DATE,
    funding_deadline DATE,
    delivery_deadline DATE,
    
    -- Status Flow: DRAFT -> PENDING_FUNDING -> FUNDED -> IN_PROGRESS -> 
    --              PENDING_RELEASE -> RELEASED -> COMPLETED / DISPUTED / CANCELLED
    status VARCHAR(30) DEFAULT 'DRAFT',
    status_reason TEXT,
    
    -- Dispute Handling
    dispute_raised BOOLEAN DEFAULT FALSE,
    dispute_raised_by VARCHAR(20), -- BUYER, SELLER
    dispute_date TIMESTAMP,
    dispute_reason TEXT,
    dispute_resolution TEXT,
    dispute_resolved_at TIMESTAMP,
    
    -- Completion
    completed_at TIMESTAMP,
    cancelled_at TIMESTAMP,
    cancellation_reason TEXT,
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

CREATE INDEX idx_escrow_ref ON escrow_contracts(contract_reference);
CREATE INDEX idx_escrow_seller ON escrow_contracts(seller_id);
CREATE INDEX idx_escrow_buyer ON escrow_contracts(buyer_id);
CREATE INDEX idx_escrow_account ON escrow_contracts(escrow_account_id);
CREATE INDEX idx_escrow_status ON escrow_contracts(status);
CREATE INDEX idx_escrow_type ON escrow_contracts(contract_type);

-- Escrow Milestones (for milestone-based releases)
CREATE TABLE escrow_milestones (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    contract_id UUID NOT NULL REFERENCES escrow_contracts(id),
    milestone_number INTEGER NOT NULL,
    milestone_name VARCHAR(200) NOT NULL,
    milestone_description TEXT,
    release_amount DECIMAL(18,2) NOT NULL,
    release_percentage DECIMAL(5,2), -- Alternative to amount
    
    -- Conditions
    condition_type VARCHAR(50), -- DELIVERY, INSPECTION, APPROVAL, DATE, DOCUMENT
    condition_description TEXT,
    required_documents TEXT[], -- Array of required document types
    
    -- Status: PENDING -> CONDITION_MET -> APPROVED -> RELEASED
    status VARCHAR(20) DEFAULT 'PENDING',
    
    -- Approval
    buyer_approved BOOLEAN DEFAULT FALSE,
    buyer_approved_at TIMESTAMP,
    seller_confirmed BOOLEAN DEFAULT FALSE,
    seller_confirmed_at TIMESTAMP,
    
    -- Release
    released_at TIMESTAMP,
    release_transaction_id UUID,
    
    due_date DATE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_milestone_contract ON escrow_milestones(contract_id);
CREATE INDEX idx_milestone_status ON escrow_milestones(status);

-- Escrow Documents
CREATE TABLE escrow_documents (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    contract_id UUID NOT NULL REFERENCES escrow_contracts(id),
    milestone_id UUID REFERENCES escrow_milestones(id),
    
    document_type VARCHAR(50) NOT NULL, -- CONTRACT, INVOICE, DELIVERY_NOTE, INSPECTION, etc.
    document_name VARCHAR(200) NOT NULL,
    document_description TEXT,
    file_path VARCHAR(500),
    file_hash VARCHAR(64), -- SHA-256 hash for integrity
    file_size INTEGER,
    mime_type VARCHAR(100),
    
    uploaded_by VARCHAR(20) NOT NULL, -- BUYER, SELLER, BANK
    uploaded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Verification
    verified BOOLEAN DEFAULT FALSE,
    verified_by VARCHAR(100),
    verified_at TIMESTAMP
);

CREATE INDEX idx_escrow_doc_contract ON escrow_documents(contract_id);
CREATE INDEX idx_escrow_doc_milestone ON escrow_documents(milestone_id);

-- Escrow Transactions (Fund movements within escrow)
CREATE TABLE escrow_transactions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    contract_id UUID NOT NULL REFERENCES escrow_contracts(id),
    milestone_id UUID REFERENCES escrow_milestones(id),
    
    transaction_type VARCHAR(20) NOT NULL, -- DEPOSIT, RELEASE, REFUND, FEE
    transaction_reference VARCHAR(50) NOT NULL UNIQUE,
    
    amount DECIMAL(18,2) NOT NULL,
    currency_code VARCHAR(3) DEFAULT 'AED',
    
    from_account VARCHAR(34),
    to_account VARCHAR(34),
    
    status VARCHAR(20) DEFAULT 'PENDING', -- PENDING, COMPLETED, FAILED
    
    -- Link to main transaction
    main_transaction_id UUID,
    
    processed_at TIMESTAMP,
    failure_reason TEXT,
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_escrow_txn_contract ON escrow_transactions(contract_id);
CREATE INDEX idx_escrow_txn_ref ON escrow_transactions(transaction_reference);

-- ============================================================================
-- WALLET PROGRAM MANAGEMENT
-- ============================================================================

-- Wallet Programs (Corporate runs the program)
CREATE TABLE wallet_programs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    program_code VARCHAR(20) NOT NULL UNIQUE,
    program_name VARCHAR(200) NOT NULL,
    program_description TEXT,
    
    -- Program Operator (Corporate)
    operator_id UUID NOT NULL REFERENCES corporate_customers(id),
    
    -- Linked Scheme
    scheme_id UUID NOT NULL REFERENCES corporate_schemes(id),
    
    -- Program Configuration
    currency_code VARCHAR(3) DEFAULT 'AED',
    min_balance DECIMAL(18,2) DEFAULT 0,
    max_balance DECIMAL(18,2),
    daily_load_limit DECIMAL(18,2),
    daily_spend_limit DECIMAL(18,2),
    monthly_load_limit DECIMAL(18,2),
    monthly_spend_limit DECIMAL(18,2),
    
    -- Features
    allow_p2p_transfer BOOLEAN DEFAULT FALSE,
    allow_merchant_payment BOOLEAN DEFAULT TRUE,
    allow_atm_withdrawal BOOLEAN DEFAULT FALSE,
    allow_online_payment BOOLEAN DEFAULT TRUE,
    
    -- KYCC Requirements
    kycc_required BOOLEAN DEFAULT TRUE,
    kycc_verification_level VARCHAR(20) DEFAULT 'BASIC',
    
    -- Fee Structure
    issuance_fee DECIMAL(18,2) DEFAULT 0,
    monthly_fee DECIMAL(18,2) DEFAULT 0,
    load_fee_percentage DECIMAL(5,2) DEFAULT 0,
    transaction_fee DECIMAL(18,2) DEFAULT 0,
    
    -- Status
    status VARCHAR(20) DEFAULT 'ACTIVE',
    launch_date DATE,
    sunset_date DATE,
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

CREATE INDEX idx_wallet_prog_code ON wallet_programs(program_code);
CREATE INDEX idx_wallet_prog_operator ON wallet_programs(operator_id);
CREATE INDEX idx_wallet_prog_scheme ON wallet_programs(scheme_id);

-- Wallet Accounts (Beneficiary = Wallet Holder)
CREATE TABLE wallet_accounts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    wallet_reference VARCHAR(50) NOT NULL UNIQUE,
    
    -- Link to Program
    program_id UUID NOT NULL REFERENCES wallet_programs(id),
    
    -- Link to Virtual Account (Published Account)
    virtual_account_id UUID NOT NULL REFERENCES virtual_accounts(id),
    
    -- Wallet Holder (Beneficiary with KYCC)
    holder_id UUID NOT NULL REFERENCES beneficiaries(id),
    
    -- Wallet Details
    wallet_name VARCHAR(100),
    wallet_type VARCHAR(20) DEFAULT 'PREPAID', -- PREPAID, POSTPAID
    
    -- Balance (Mirror of VA balance)
    current_balance DECIMAL(18,2) DEFAULT 0,
    available_balance DECIMAL(18,2) DEFAULT 0,
    pending_loads DECIMAL(18,2) DEFAULT 0,
    
    -- Limits (Can override program defaults)
    custom_daily_limit DECIMAL(18,2),
    custom_monthly_limit DECIMAL(18,2),
    
    -- Usage Tracking
    daily_spent DECIMAL(18,2) DEFAULT 0,
    monthly_spent DECIMAL(18,2) DEFAULT 0,
    last_transaction_date DATE,
    transaction_count INTEGER DEFAULT 0,
    
    -- Status
    status VARCHAR(20) DEFAULT 'ACTIVE', -- ACTIVE, SUSPENDED, BLOCKED, CLOSED
    activation_date DATE DEFAULT CURRENT_DATE,
    last_active_date DATE,
    
    -- Suspension/Block
    suspended_reason TEXT,
    suspended_at TIMESTAMP,
    blocked_reason TEXT,
    blocked_at TIMESTAMP,
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

CREATE INDEX idx_wallet_acct_ref ON wallet_accounts(wallet_reference);
CREATE INDEX idx_wallet_acct_program ON wallet_accounts(program_id);
CREATE INDEX idx_wallet_acct_va ON wallet_accounts(virtual_account_id);
CREATE INDEX idx_wallet_acct_holder ON wallet_accounts(holder_id);
CREATE INDEX idx_wallet_acct_status ON wallet_accounts(status);

-- Wallet Transactions
CREATE TABLE wallet_transactions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    wallet_id UUID NOT NULL REFERENCES wallet_accounts(id),
    transaction_reference VARCHAR(50) NOT NULL UNIQUE,
    
    transaction_type VARCHAR(20) NOT NULL, -- LOAD, PURCHASE, TRANSFER, REFUND, FEE, REVERSAL
    
    amount DECIMAL(18,2) NOT NULL,
    currency_code VARCHAR(3) DEFAULT 'AED',
    
    -- For transfers/purchases
    counterparty_reference VARCHAR(100),
    counterparty_name VARCHAR(200),
    merchant_category_code VARCHAR(10),
    
    -- Balance after transaction
    balance_before DECIMAL(18,2),
    balance_after DECIMAL(18,2),
    
    -- Status
    status VARCHAR(20) DEFAULT 'PENDING',
    
    -- Link to main transaction
    main_transaction_id UUID,
    
    description TEXT,
    processed_at TIMESTAMP,
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_wallet_txn_wallet ON wallet_transactions(wallet_id);
CREATE INDEX idx_wallet_txn_ref ON wallet_transactions(transaction_reference);
CREATE INDEX idx_wallet_txn_type ON wallet_transactions(transaction_type);

-- ============================================================================
-- TRANSACTIONS (Enhanced)
-- ============================================================================

CREATE TABLE transactions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    transaction_reference VARCHAR(50) NOT NULL UNIQUE,
    e2e_reference VARCHAR(35),
    uetr VARCHAR(36), -- Universal End-to-End Transaction Reference
    
    -- Transaction Context
    transaction_context VARCHAR(20) DEFAULT 'STANDARD', -- STANDARD, ESCROW, WALLET
    
    -- Debtor
    debtor_account_id UUID REFERENCES virtual_accounts(id),
    debtor_account VARCHAR(34) NOT NULL,
    debtor_name VARCHAR(200),
    debtor_bic VARCHAR(11),
    
    -- Creditor/Beneficiary
    beneficiary_id UUID REFERENCES beneficiaries(id),
    beneficiary_account VARCHAR(34) NOT NULL,
    beneficiary_name VARCHAR(200),
    beneficiary_bic VARCHAR(11),
    beneficiary_bank_name VARCHAR(200),
    
    -- Amount
    instructed_amount DECIMAL(18,2) NOT NULL,
    instructed_currency VARCHAR(3) DEFAULT 'AED',
    settlement_amount DECIMAL(18,2),
    settlement_currency VARCHAR(3),
    exchange_rate DECIMAL(18,8),
    charges_amount DECIMAL(18,2),
    charges_currency VARCHAR(3),
    charge_bearer VARCHAR(10) DEFAULT 'SHA',
    
    -- Payment Details
    purpose_code VARCHAR(10),
    purpose_description VARCHAR(200),
    remittance_info TEXT,
    
    -- Clearing
    clearing_type VARCHAR(20), -- INTERNAL, RTGS, ACH, SWIFT
    payment_type VARCHAR(20), -- SINGLE, BULK
    priority VARCHAR(10) DEFAULT 'NORMAL',
    
    -- Dates
    transaction_date DATE NOT NULL DEFAULT CURRENT_DATE,
    value_date DATE,
    execution_date DATE,
    
    -- Status: PENDING -> VALIDATED -> SUBMITTED -> COMPLETED / FAILED / CANCELLED
    status VARCHAR(20) DEFAULT 'PENDING',
    status_reason TEXT,
    
    -- BaNCS Sync
    bancs_payment_reference BIGINT,
    bancs_sync_status VARCHAR(20) DEFAULT 'PENDING',
    bancs_sync_error TEXT,
    bancs_synced_at TIMESTAMP,
    
    -- Audit
    initiated_by VARCHAR(100),
    approved_by VARCHAR(100),
    approved_at TIMESTAMP,
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_txn_ref ON transactions(transaction_reference);
CREATE INDEX idx_txn_uetr ON transactions(uetr);
CREATE INDEX idx_txn_debtor ON transactions(debtor_account_id);
CREATE INDEX idx_txn_bene ON transactions(beneficiary_id);
CREATE INDEX idx_txn_status ON transactions(status);
CREATE INDEX idx_txn_date ON transactions(transaction_date);
CREATE INDEX idx_txn_context ON transactions(transaction_context);
CREATE INDEX idx_txn_sync ON transactions(bancs_sync_status);

-- ============================================================================
-- BANCS SYNC QUEUE (Fallback Mechanism)
-- ============================================================================

-- Sync Queue for BaNCS Operations
CREATE TABLE bancs_sync_queue (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    
    -- Operation Details
    operation_type VARCHAR(50) NOT NULL, -- CREATE_VA, CLOSE_VA, CREATE_BENE, PAYMENT, WHITELIST, etc.
    entity_type VARCHAR(50) NOT NULL,    -- VIRTUAL_ACCOUNT, BENEFICIARY, TRANSACTION, etc.
    entity_id UUID NOT NULL,
    
    -- Request Payload (JSON)
    request_payload JSONB NOT NULL,
    
    -- Status
    status VARCHAR(20) DEFAULT 'PENDING', -- PENDING, PROCESSING, COMPLETED, FAILED, CANCELLED
    
    -- Retry Management
    retry_count INTEGER DEFAULT 0,
    max_retries INTEGER DEFAULT 5,
    next_retry_at TIMESTAMP,
    
    -- Response
    response_payload JSONB,
    error_message TEXT,
    error_code VARCHAR(20),
    
    -- Processing
    processed_at TIMESTAMP,
    processing_duration_ms INTEGER,
    
    -- Priority (lower = higher priority)
    priority INTEGER DEFAULT 5,
    
    -- Idempotency
    idempotency_key VARCHAR(100) UNIQUE,
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sync_queue_status ON bancs_sync_queue(status);
CREATE INDEX idx_sync_queue_entity ON bancs_sync_queue(entity_type, entity_id);
CREATE INDEX idx_sync_queue_retry ON bancs_sync_queue(next_retry_at) WHERE status = 'PENDING';
CREATE INDEX idx_sync_queue_priority ON bancs_sync_queue(priority, created_at) WHERE status = 'PENDING';

-- Sync Queue History (Completed/Failed items moved here)
CREATE TABLE bancs_sync_queue_history (
    id UUID PRIMARY KEY,
    operation_type VARCHAR(50) NOT NULL,
    entity_type VARCHAR(50) NOT NULL,
    entity_id UUID NOT NULL,
    request_payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL,
    retry_count INTEGER,
    response_payload JSONB,
    error_message TEXT,
    error_code VARCHAR(20),
    processed_at TIMESTAMP,
    processing_duration_ms INTEGER,
    priority INTEGER,
    idempotency_key VARCHAR(100),
    created_at TIMESTAMP,
    archived_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sync_history_entity ON bancs_sync_queue_history(entity_type, entity_id);
CREATE INDEX idx_sync_history_status ON bancs_sync_queue_history(status);

-- BaNCS Health Status
CREATE TABLE bancs_health_status (
    id SERIAL PRIMARY KEY,
    check_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_available BOOLEAN NOT NULL,
    response_time_ms INTEGER,
    error_message TEXT,
    consecutive_failures INTEGER DEFAULT 0
);

CREATE INDEX idx_bancs_health_time ON bancs_health_status(check_time DESC);

-- ============================================================================
-- WHITELISTED ACCOUNTS
-- ============================================================================

CREATE TABLE whitelisted_accounts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    virtual_account_id UUID NOT NULL REFERENCES virtual_accounts(id),
    whitelisted_account VARCHAR(34) NOT NULL,
    whitelisted_account_name VARCHAR(200),
    whitelisted_bic VARCHAR(11),
    whitelisted_bank_name VARCHAR(200),
    direction INTEGER NOT NULL, -- 1-Debit, 2-Credit, 3-Both
    status INTEGER DEFAULT 1,
    start_date DATE DEFAULT CURRENT_DATE,
    end_date DATE,
    
    bancs_sync_status VARCHAR(20) DEFAULT 'PENDING',
    bancs_sync_error TEXT,
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

CREATE INDEX idx_whitelist_va ON whitelisted_accounts(virtual_account_id);
CREATE INDEX idx_whitelist_account ON whitelisted_accounts(whitelisted_account);
CREATE INDEX idx_whitelist_sync ON whitelisted_accounts(bancs_sync_status);

-- ============================================================================
-- ACCOUNT STATEMENTS (Local Cache)
-- ============================================================================

CREATE TABLE account_statements (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    virtual_account_id UUID NOT NULL REFERENCES virtual_accounts(id),
    statement_date DATE NOT NULL,
    value_date DATE,
    transaction_reference VARCHAR(50),
    description VARCHAR(500),
    debit_amount DECIMAL(18,2),
    credit_amount DECIMAL(18,2),
    running_balance DECIMAL(18,2),
    currency_code VARCHAR(3) DEFAULT 'AED',
    counterparty_account VARCHAR(34),
    counterparty_name VARCHAR(200),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_stmt_va ON account_statements(virtual_account_id);
CREATE INDEX idx_stmt_date ON account_statements(statement_date);

-- ============================================================================
-- AUDIT LOG
-- ============================================================================

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    entity_type VARCHAR(50) NOT NULL,
    entity_id UUID NOT NULL,
    action VARCHAR(50) NOT NULL, -- CREATE, UPDATE, DELETE, VIEW, SYNC, etc.
    action_detail TEXT,
    old_values JSONB,
    new_values JSONB,
    performed_by VARCHAR(100),
    performed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    ip_address VARCHAR(50),
    user_agent VARCHAR(500),
    session_id VARCHAR(100)
);

CREATE INDEX idx_audit_entity ON audit_logs(entity_type, entity_id);
CREATE INDEX idx_audit_action ON audit_logs(action);
CREATE INDEX idx_audit_time ON audit_logs(performed_at);
CREATE INDEX idx_audit_user ON audit_logs(performed_by);

-- ============================================================================
-- USER MANAGEMENT
-- ============================================================================

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    username VARCHAR(100) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(200),
    customer_id UUID REFERENCES corporate_customers(id),
    status VARCHAR(20) DEFAULT 'ACTIVE',
    last_login TIMESTAMP,
    failed_login_attempts INTEGER DEFAULT 0,
    locked_until TIMESTAMP,
    password_changed_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE roles (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    role_name VARCHAR(50) NOT NULL UNIQUE,
    role_description VARCHAR(200),
    is_system_role BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE permissions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    permission_code VARCHAR(50) NOT NULL UNIQUE,
    permission_name VARCHAR(100) NOT NULL,
    module VARCHAR(50) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE user_roles (
    user_id UUID REFERENCES users(id),
    role_id UUID REFERENCES roles(id),
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE role_permissions (
    role_id UUID REFERENCES roles(id),
    permission_id UUID REFERENCES permissions(id),
    PRIMARY KEY (role_id, permission_id)
);

-- ============================================================================
-- REFERENCE DATA
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

-- Roles
INSERT INTO roles (role_name, role_description, is_system_role) VALUES
('SUPER_ADMIN', 'Full system access', TRUE),
('CORPORATE_ADMIN', 'Corporate customer administrator', FALSE),
('ESCROW_MANAGER', 'Manages escrow contracts', FALSE),
('WALLET_ADMIN', 'Administers wallet programs', FALSE),
('FINANCE_MANAGER', 'Can initiate and approve transactions', FALSE),
('FINANCE_USER', 'Can view and initiate transactions', FALSE),
('VIEWER', 'Read-only access', FALSE);

-- Permissions
INSERT INTO permissions (permission_code, permission_name, module) VALUES
-- Virtual Account
('VA_VIEW', 'View Virtual Accounts', 'VIRTUAL_ACCOUNT'),
('VA_CREATE', 'Create Virtual Accounts', 'VIRTUAL_ACCOUNT'),
('VA_UPDATE', 'Update Virtual Accounts', 'VIRTUAL_ACCOUNT'),
('VA_CLOSE', 'Close Virtual Accounts', 'VIRTUAL_ACCOUNT'),
-- Escrow
('ESCROW_VIEW', 'View Escrow Contracts', 'ESCROW'),
('ESCROW_CREATE', 'Create Escrow Contracts', 'ESCROW'),
('ESCROW_MANAGE', 'Manage Escrow Contracts', 'ESCROW'),
('ESCROW_RELEASE', 'Release Escrow Funds', 'ESCROW'),
('ESCROW_DISPUTE', 'Handle Escrow Disputes', 'ESCROW'),
-- Wallet
('WALLET_VIEW', 'View Wallet Programs', 'WALLET'),
('WALLET_CREATE', 'Create Wallet Programs', 'WALLET'),
('WALLET_MANAGE', 'Manage Wallet Programs', 'WALLET'),
('WALLET_SUSPEND', 'Suspend Wallet Accounts', 'WALLET'),
-- KYCC
('KYCC_VIEW', 'View KYCC Records', 'KYCC'),
('KYCC_CREATE', 'Create KYCC Records', 'KYCC'),
('KYCC_VERIFY', 'Verify KYCC Records', 'KYCC'),
-- Beneficiary
('BENE_VIEW', 'View Beneficiaries', 'BENEFICIARY'),
('BENE_CREATE', 'Create Beneficiaries', 'BENEFICIARY'),
('BENE_UPDATE', 'Update Beneficiaries', 'BENEFICIARY'),
('BENE_DELETE', 'Delete Beneficiaries', 'BENEFICIARY'),
-- Transaction
('TXN_VIEW', 'View Transactions', 'TRANSACTION'),
('TXN_INITIATE', 'Initiate Transactions', 'TRANSACTION'),
('TXN_APPROVE', 'Approve Transactions', 'TRANSACTION'),
-- Admin
('USER_MANAGE', 'Manage Users', 'ADMIN'),
('ROLE_MANAGE', 'Manage Roles', 'ADMIN'),
('AUDIT_VIEW', 'View Audit Logs', 'ADMIN'),
('SYNC_MANAGE', 'Manage BaNCS Sync', 'ADMIN');

-- Currencies
INSERT INTO currencies (currency_code, currency_name, currency_number, decimal_places) VALUES
('AED', 'UAE Dirham', 784, 2),
('USD', 'US Dollar', 840, 2),
('EUR', 'Euro', 978, 2),
('GBP', 'British Pound', 826, 2),
('SAR', 'Saudi Riyal', 682, 2),
('INR', 'Indian Rupee', 356, 2);

-- ============================================================================
-- FUNCTIONS & TRIGGERS
-- ============================================================================

-- Update timestamp trigger
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Apply triggers
CREATE TRIGGER tr_corporate_customers_updated BEFORE UPDATE ON corporate_customers FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER tr_corporate_schemes_updated BEFORE UPDATE ON corporate_schemes FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER tr_virtual_accounts_updated BEFORE UPDATE ON virtual_accounts FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER tr_beneficiaries_updated BEFORE UPDATE ON beneficiaries FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER tr_kycc_records_updated BEFORE UPDATE ON kycc_records FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER tr_escrow_contracts_updated BEFORE UPDATE ON escrow_contracts FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER tr_wallet_programs_updated BEFORE UPDATE ON wallet_programs FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER tr_wallet_accounts_updated BEFORE UPDATE ON wallet_accounts FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER tr_transactions_updated BEFORE UPDATE ON transactions FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER tr_whitelisted_accounts_updated BEFORE UPDATE ON whitelisted_accounts FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER tr_bancs_sync_queue_updated BEFORE UPDATE ON bancs_sync_queue FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- Generate unique references
CREATE OR REPLACE FUNCTION generate_reference(prefix VARCHAR, length INTEGER DEFAULT 12)
RETURNS VARCHAR AS $$
DECLARE
    chars TEXT := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    result TEXT := prefix;
    i INTEGER;
BEGIN
    FOR i IN 1..length LOOP
        result := result || substr(chars, floor(random() * length(chars) + 1)::INTEGER, 1);
    END LOOP;
    RETURN result;
END;
$$ LANGUAGE plpgsql;

-- Move completed sync items to history
CREATE OR REPLACE FUNCTION archive_sync_queue_item()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.status IN ('COMPLETED', 'FAILED', 'CANCELLED') AND OLD.status = 'PROCESSING' THEN
        INSERT INTO bancs_sync_queue_history 
        SELECT id, operation_type, entity_type, entity_id, request_payload, status,
               retry_count, response_payload, error_message, error_code, processed_at,
               processing_duration_ms, priority, idempotency_key, created_at
        FROM bancs_sync_queue WHERE id = NEW.id;
        
        DELETE FROM bancs_sync_queue WHERE id = NEW.id;
        RETURN NULL;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_archive_sync_queue
AFTER UPDATE ON bancs_sync_queue
FOR EACH ROW EXECUTE FUNCTION archive_sync_queue_item();

-- ============================================================================
-- VIEWS
-- ============================================================================

-- Virtual Account Summary
CREATE OR REPLACE VIEW vw_virtual_account_summary AS
SELECT 
    va.id, va.virtual_account_number, va.virtual_iban, va.account_name,
    va.account_type, va.currency_code, va.current_balance, va.available_balance,
    va.status, va.bancs_sync_status, va.opened_on,
    cs.corporate_scheme_code, cs.scheme_name, cs.program_type,
    cc.customer_reference, cc.customer_name
FROM virtual_accounts va
JOIN corporate_schemes cs ON va.corporate_scheme_id = cs.id
JOIN corporate_customers cc ON va.customer_id = cc.id;

-- Escrow Dashboard View
CREATE OR REPLACE VIEW vw_escrow_dashboard AS
SELECT 
    ec.id, ec.contract_reference, ec.contract_name, ec.contract_type,
    ec.contract_amount, ec.currency_code, ec.status, ec.contract_date,
    ec.expiry_date, ec.dispute_raised,
    cc.customer_name as seller_name,
    b.beneficiary_name as buyer_name,
    va.virtual_iban as escrow_iban, va.current_balance as escrow_balance,
    (SELECT COUNT(*) FROM escrow_milestones em WHERE em.contract_id = ec.id) as total_milestones,
    (SELECT COUNT(*) FROM escrow_milestones em WHERE em.contract_id = ec.id AND em.status = 'RELEASED') as released_milestones
FROM escrow_contracts ec
JOIN corporate_customers cc ON ec.seller_id = cc.id
JOIN beneficiaries b ON ec.buyer_id = b.id
JOIN virtual_accounts va ON ec.escrow_account_id = va.id;

-- Wallet Program Summary
CREATE OR REPLACE VIEW vw_wallet_program_summary AS
SELECT 
    wp.id, wp.program_code, wp.program_name, wp.status,
    cc.customer_name as operator_name,
    wp.currency_code, wp.daily_spend_limit, wp.monthly_spend_limit,
    (SELECT COUNT(*) FROM wallet_accounts wa WHERE wa.program_id = wp.id) as total_wallets,
    (SELECT COUNT(*) FROM wallet_accounts wa WHERE wa.program_id = wp.id AND wa.status = 'ACTIVE') as active_wallets,
    (SELECT COALESCE(SUM(wa.current_balance), 0) FROM wallet_accounts wa WHERE wa.program_id = wp.id) as total_balance
FROM wallet_programs wp
JOIN corporate_customers cc ON wp.operator_id = cc.id;

-- Pending Sync Queue View
CREATE OR REPLACE VIEW vw_pending_sync AS
SELECT 
    bsq.id, bsq.operation_type, bsq.entity_type, bsq.entity_id,
    bsq.status, bsq.retry_count, bsq.max_retries, bsq.next_retry_at,
    bsq.priority, bsq.error_message, bsq.created_at
FROM bancs_sync_queue bsq
WHERE bsq.status IN ('PENDING', 'FAILED')
ORDER BY bsq.priority, bsq.created_at;

-- KYCC Verification Status
CREATE OR REPLACE VIEW vw_kycc_status AS
SELECT 
    kr.id, kr.kycc_reference, kr.full_name, kr.mobile_number, kr.email_address,
    kr.kyc_id_type, kr.verification_status, kr.risk_category,
    kr.valid_from, kr.valid_until,
    (SELECT COUNT(*) FROM beneficiaries b WHERE b.kycc_id = kr.id) as linked_beneficiaries
FROM kycc_records kr;
