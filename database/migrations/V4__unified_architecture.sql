-- ============================================================================
-- VAM REDESIGNED ARCHITECTURE - UNIFIED MODEL
-- Migration V4: Capgemini/TietoEvry Aligned Architecture
-- ============================================================================
-- 
-- This migration implements:
-- 1. Unified Program Model (replaces wallet_programs, ecommerce_merchants)
-- 2. IHB as Overlay (not parallel structure)
-- 3. POBO/ROBO capabilities
-- 4. Real-time IC Position tracking
-- 5. Allocations for budget control
-- 6. Sweeping rules for cash concentration
-- 7. Virtual Branches for cross-border
--
-- ============================================================================

-- ============================================================================
-- PART 1: UNIFIED PROGRAM MODEL
-- ============================================================================

-- -----------------------------------------------------------------------------
-- 1.1 VA Programs (Universal Container)
-- -----------------------------------------------------------------------------
-- Replaces: wallet_programs, ecommerce_merchants
-- Handles: WALLET, ECOMMERCE, COLLECTIONS, PAYABLES, ESCROW, CLIENT_MONEY, VIRTUAL_BRANCH

CREATE TABLE va_programs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    
    -- Identity
    program_code VARCHAR(30) NOT NULL,
    program_name VARCHAR(200) NOT NULL,
    program_description TEXT,
    
    -- Type (determines behavior)
    program_type VARCHAR(30) NOT NULL,
    -- WALLET        : Consumer/employee prepaid wallets
    -- ECOMMERCE     : Order-specific ephemeral VIBANs
    -- COLLECTIONS   : AR automation - per-payer or per-invoice VAs
    -- PAYABLES      : AP automation - per-vendor VAs
    -- ESCROW        : Segregated escrow holding
    -- CLIENT_MONEY  : Asset manager/broker fund segregation
    -- VIRTUAL_BRANCH: Cross-border virtual presence
    
    -- Ownership
    customer_id UUID NOT NULL REFERENCES corporate_customers(id),
    scheme_id UUID NOT NULL REFERENCES corporate_schemes(id),
    
    -- Settlement Account
    settlement_account_id UUID REFERENCES virtual_accounts(id),
    settlement_frequency VARCHAR(20) DEFAULT 'REALTIME',
    -- REALTIME, HOURLY, DAILY, EOD
    auto_sweep_to_settlement BOOLEAN DEFAULT FALSE,
    
    -- IBAN Generation
    iban_prefix VARCHAR(20),
    iban_generation_strategy VARCHAR(20) DEFAULT 'SEQUENTIAL',
    -- SEQUENTIAL, RANDOM, HASH, POOL
    iban_pool_size INTEGER DEFAULT 1000,
    
    -- Limits
    daily_account_limit INTEGER DEFAULT 10000,
    monthly_account_limit INTEGER DEFAULT 100000,
    concurrent_active_limit INTEGER DEFAULT 5000,
    max_account_balance DECIMAL(18,2),
    
    -- Auto-closure Rules
    auto_close_on_payment BOOLEAN DEFAULT FALSE,
    auto_close_on_exact_match BOOLEAN DEFAULT TRUE,
    allow_overpayment BOOLEAN DEFAULT FALSE,
    allow_partial_payment BOOLEAN DEFAULT TRUE,
    auto_close_delay_minutes INTEGER DEFAULT 5,
    default_expiry_hours INTEGER,
    max_expiry_hours INTEGER DEFAULT 168, -- 7 days
    
    -- Webhooks
    webhook_url VARCHAR(500),
    webhook_secret VARCHAR(100),
    webhook_enabled BOOLEAN DEFAULT TRUE,
    webhook_retry_count INTEGER DEFAULT 3,
    webhook_events TEXT[], -- Array of event types
    
    -- Fee Structure (JSON for flexibility)
    fee_structure JSONB,
    -- {
    --   "account_creation_fee": 0,
    --   "monthly_fee": 0,
    --   "transaction_fee": 0.50,
    --   "transaction_fee_percentage": 0.1
    -- }
    
    -- Program-specific Configuration (JSON for flexibility)
    program_config JSONB,
    -- WALLET: {"kyc_level": "BASIC", "card_enabled": false, "daily_spend_limit": 5000}
    -- ECOMMERCE: {"tolerance_pct": 1.0, "merchant_category": "RETAIL"}
    -- COLLECTIONS: {"matching_rules": [...], "auto_reconcile": true}
    -- ESCROW: {"milestone_release": true, "dispute_handling": "MANUAL"}
    -- CLIENT_MONEY: {"interest_bearing": true, "audit_required": true}
    
    -- Status
    status VARCHAR(20) DEFAULT 'ACTIVE',
    effective_from DATE DEFAULT CURRENT_DATE,
    effective_to DATE,
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    
    CONSTRAINT uq_program_code UNIQUE (customer_id, program_code),
    CONSTRAINT chk_program_type CHECK (program_type IN (
        'WALLET', 'ECOMMERCE', 'COLLECTIONS', 'PAYABLES', 
        'ESCROW', 'CLIENT_MONEY', 'VIRTUAL_BRANCH'
    ))
);

CREATE INDEX idx_va_prog_customer ON va_programs(customer_id);
CREATE INDEX idx_va_prog_scheme ON va_programs(scheme_id);
CREATE INDEX idx_va_prog_type ON va_programs(program_type);
CREATE INDEX idx_va_prog_status ON va_programs(status);

COMMENT ON TABLE va_programs IS 'Universal program container for all VA use cases (Capgemini All-in-One model)';


-- -----------------------------------------------------------------------------
-- 1.2 Program Account Holders (Universal Holder)
-- -----------------------------------------------------------------------------
-- Replaces: beneficiaries with role WALLET_HOLDER, BUYER
-- Plus: E-commerce buyers, collection payers, IHB subsidiaries

CREATE TABLE program_account_holders (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    
    -- Link to Program
    program_id UUID NOT NULL REFERENCES va_programs(id),
    
    -- Holder Type
    holder_type VARCHAR(20) NOT NULL,
    -- INDIVIDUAL  : Natural person (wallet holder, escrow buyer)
    -- CORPORATE   : Legal entity (B2B customer)
    -- ANONYMOUS   : One-time payer (e-commerce)
    -- SUBSIDIARY  : Internal entity (IHB participant)
    
    -- External Identity
    holder_reference VARCHAR(50) NOT NULL,
    external_id VARCHAR(100), -- External system ID
    
    -- Identity Details
    holder_name VARCHAR(200),
    holder_email VARCHAR(200),
    holder_phone VARCHAR(50),
    
    -- Bank Account (if known)
    holder_account VARCHAR(34),
    holder_account_type VARCHAR(20), -- IBAN, ACCOUNT_NUMBER
    holder_bank_bic VARCHAR(11),
    holder_bank_name VARCHAR(200),
    
    -- Address
    address_line1 VARCHAR(200),
    address_line2 VARCHAR(200),
    city VARCHAR(100),
    country_code VARCHAR(3),
    postal_code VARCHAR(20),
    
    -- KYC Linkage
    kycc_id UUID REFERENCES kycc_records(id),
    identity_verified BOOLEAN DEFAULT FALSE,
    kyc_level VARCHAR(20), -- NONE, BASIC, STANDARD, ENHANCED
    kyc_verified_at TIMESTAMP,
    
    -- Link to Beneficiary (if also a payment recipient)
    beneficiary_id UUID REFERENCES beneficiaries(id),
    
    -- Status
    status VARCHAR(20) DEFAULT 'ACTIVE',
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    
    CONSTRAINT uq_program_holder_ref UNIQUE (program_id, holder_reference),
    CONSTRAINT chk_holder_type CHECK (holder_type IN (
        'INDIVIDUAL', 'CORPORATE', 'ANONYMOUS', 'SUBSIDIARY'
    ))
);

CREATE INDEX idx_prog_holder_program ON program_account_holders(program_id);
CREATE INDEX idx_prog_holder_email ON program_account_holders(holder_email);
CREATE INDEX idx_prog_holder_account ON program_account_holders(holder_account);
CREATE INDEX idx_prog_holder_kycc ON program_account_holders(kycc_id);
CREATE INDEX idx_prog_holder_status ON program_account_holders(status);

COMMENT ON TABLE program_account_holders IS 'Universal holder identity for all program types';


-- -----------------------------------------------------------------------------
-- 1.3 Program Accounts (Universal Account)
-- -----------------------------------------------------------------------------
-- Replaces: wallet_accounts, ecommerce_virtual_ibans
-- Links 1:1 to virtual_accounts

CREATE TABLE program_accounts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    
    -- Links
    program_id UUID NOT NULL REFERENCES va_programs(id),
    virtual_account_id UUID NOT NULL REFERENCES virtual_accounts(id),
    holder_id UUID REFERENCES program_account_holders(id),
    
    -- Subtype (program-specific)
    account_subtype VARCHAR(30),
    -- WALLET: PREPAID, POSTPAID, SAVINGS
    -- ECOMMERCE: ORDER_PAYMENT, SUBSCRIPTION, INVOICE
    -- COLLECTIONS: PAYER_DEDICATED, INVOICE_SPECIFIC, GENERAL
    -- PAYABLES: VENDOR_DEDICATED, CATEGORY_POOL
    -- ESCROW: GOODS, SERVICES, REAL_ESTATE
    
    -- External References
    external_reference VARCHAR(100) NOT NULL, -- order_id, invoice_no, payer_code, wallet_ref
    secondary_reference VARCHAR(100), -- Additional reference
    
    -- Expected Payment (for matching)
    expected_amount DECIMAL(18,2),
    currency_code VARCHAR(3) DEFAULT 'AED',
    amount_tolerance_pct DECIMAL(5,2) DEFAULT 0,
    amount_tolerance_abs DECIMAL(18,2) DEFAULT 0,
    
    -- Received Tracking
    received_amount DECIMAL(18,2) DEFAULT 0,
    received_count INTEGER DEFAULT 0,
    last_payment_at TIMESTAMP,
    
    -- Variance (computed)
    -- variance_amount = received_amount - expected_amount (calculated in app layer)
    
    -- Lifecycle Status
    status VARCHAR(20) DEFAULT 'ACTIVE',
    -- CREATED, ACTIVE, PAID, EXPIRED, CANCELLED, CLOSED
    
    payment_status VARCHAR(20) DEFAULT 'PENDING',
    -- PENDING, PARTIAL, EXACT, OVERPAID, UNDERPAID
    
    -- Expiry & Closure
    expires_at TIMESTAMP,
    closed_at TIMESTAMP,
    closure_reason VARCHAR(50),
    -- PAID, EXPIRED, CANCELLED, MANUAL, SETTLEMENT
    auto_closed BOOLEAN DEFAULT FALSE,
    
    -- Flexible Metadata (JSON)
    metadata JSONB,
    -- WALLET: {"employee_id": "E123", "department": "Sales"}
    -- ECOMMERCE: {"buyer_email": "...", "product_sku": "...", "cart_id": "..."}
    -- COLLECTIONS: {"customer_name": "...", "invoice_date": "..."}
    -- ESCROW: {"contract_type": "GOODS", "seller_id": "..."}
    
    -- Webhook Tracking
    webhook_notified BOOLEAN DEFAULT FALSE,
    webhook_last_attempt TIMESTAMP,
    webhook_attempts INTEGER DEFAULT 0,
    webhook_last_error TEXT,
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    
    CONSTRAINT uq_program_ext_ref UNIQUE (program_id, external_reference),
    CONSTRAINT uq_program_va UNIQUE (virtual_account_id)
);

CREATE INDEX idx_prog_acct_program ON program_accounts(program_id);
CREATE INDEX idx_prog_acct_va ON program_accounts(virtual_account_id);
CREATE INDEX idx_prog_acct_holder ON program_accounts(holder_id);
CREATE INDEX idx_prog_acct_ext_ref ON program_accounts(external_reference);
CREATE INDEX idx_prog_acct_status ON program_accounts(status);
CREATE INDEX idx_prog_acct_expires ON program_accounts(expires_at) WHERE status = 'ACTIVE';
CREATE INDEX idx_prog_acct_payment_status ON program_accounts(payment_status);

COMMENT ON TABLE program_accounts IS 'Universal program account linked 1:1 to virtual_accounts';


-- -----------------------------------------------------------------------------
-- 1.4 Program Payments (Universal Payment Tracking)
-- -----------------------------------------------------------------------------
-- Replaces: ecommerce_payments, wallet_transactions (for inbound)

CREATE TABLE program_payments (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    
    -- Links
    program_account_id UUID NOT NULL REFERENCES program_accounts(id),
    transaction_id UUID REFERENCES transactions(id),
    
    -- Payment Reference
    payment_reference VARCHAR(50) NOT NULL,
    
    -- Amount
    amount DECIMAL(18,2) NOT NULL,
    currency_code VARCHAR(3) DEFAULT 'AED',
    
    -- Payer Details (from payment message)
    payer_name VARCHAR(200),
    payer_account VARCHAR(34),
    payer_bank_bic VARCHAR(11),
    payer_bank_name VARCHAR(200),
    
    -- Payment References
    e2e_reference VARCHAR(35),
    uetr VARCHAR(36),
    remittance_info TEXT,
    
    -- Matching
    match_status VARCHAR(20) NOT NULL,
    -- EXACT, OVERPAID, UNDERPAID, DUPLICATE, UNMATCHED
    amount_variance DECIMAL(18,2),
    
    -- Refund Tracking
    refund_required BOOLEAN DEFAULT FALSE,
    refund_amount DECIMAL(18,2),
    refund_status VARCHAR(20),
    refund_reference VARCHAR(50),
    refunded_at TIMESTAMP,
    
    -- Audit
    received_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_prog_pay_account ON program_payments(program_account_id);
CREATE INDEX idx_prog_pay_transaction ON program_payments(transaction_id);
CREATE INDEX idx_prog_pay_payer ON program_payments(payer_account);
CREATE INDEX idx_prog_pay_status ON program_payments(match_status);

COMMENT ON TABLE program_payments IS 'Payments received for program accounts';


-- -----------------------------------------------------------------------------
-- 1.5 IBAN Pool (Shared across programs)
-- -----------------------------------------------------------------------------
-- Enhanced from ecommerce - now shared across all program types

CREATE TABLE program_iban_pool (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    
    -- Pool Owner
    program_id UUID NOT NULL REFERENCES va_programs(id),
    
    -- IBAN Details
    virtual_iban VARCHAR(34) NOT NULL UNIQUE,
    virtual_account_number BIGINT NOT NULL UNIQUE,
    virtual_bban VARCHAR(23),
    
    -- Allocation Status
    status VARCHAR(20) DEFAULT 'AVAILABLE',
    -- AVAILABLE, ALLOCATED, RESERVED, RETIRED
    
    allocated_to_account_id UUID REFERENCES program_accounts(id),
    allocated_at TIMESTAMP,
    
    -- Reuse Tracking
    use_count INTEGER DEFAULT 0,
    last_used_at TIMESTAMP,
    
    -- Retirement
    retired_at TIMESTAMP,
    retirement_reason VARCHAR(100),
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_iban_pool_program ON program_iban_pool(program_id);
CREATE INDEX idx_iban_pool_status ON program_iban_pool(status);
CREATE INDEX idx_iban_pool_available ON program_iban_pool(program_id, status) 
    WHERE status = 'AVAILABLE';

COMMENT ON TABLE program_iban_pool IS 'Pre-generated IBAN pool for rapid allocation';


-- ============================================================================
-- PART 2: IHB AS OVERLAY
-- ============================================================================

-- -----------------------------------------------------------------------------
-- 2.1 IHB Configurations (Policy Layer - NOT account owner)
-- -----------------------------------------------------------------------------
-- Renamed/slimmed from: in_house_banks

CREATE TABLE ihb_configurations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    
    -- Link to existing customer (parent company)
    customer_id UUID NOT NULL REFERENCES corporate_customers(id),
    
    -- Identity
    ihb_code VARCHAR(20) NOT NULL UNIQUE,
    ihb_name VARCHAR(200) NOT NULL,
    legal_entity_name VARCHAR(200),
    
    -- Base Settings
    base_currency VARCHAR(3) DEFAULT 'AED',
    regulatory_classification VARCHAR(30),
    -- TREASURY_CENTER, PAYMENT_FACTORY, NETTING_CENTER, SHARED_SERVICE
    regulatory_jurisdiction VARCHAR(50),
    
    -- Pooling Policy
    pooling_type VARCHAR(20) DEFAULT 'NOTIONAL',
    -- NOTIONAL, PHYSICAL, HYBRID
    
    -- Netting Policy
    netting_enabled BOOLEAN DEFAULT TRUE,
    netting_frequency VARCHAR(20) DEFAULT 'MONTHLY',
    -- DAILY, WEEKLY, MONTHLY, QUARTERLY
    netting_auto_settle BOOLEAN DEFAULT FALSE,
    
    -- Internal FX Policy
    internal_fx_enabled BOOLEAN DEFAULT TRUE,
    fx_markup_bps INTEGER DEFAULT 0, -- Basis points over market
    fx_rate_source VARCHAR(50) DEFAULT 'INTERNAL',
    
    -- Interest Policy
    interest_calculation_enabled BOOLEAN DEFAULT TRUE,
    interest_calculation_basis VARCHAR(20) DEFAULT 'ACT_360',
    -- ACT_360, ACT_365, 30_360
    interest_posting_frequency VARCHAR(20) DEFAULT 'MONTHLY',
    -- DAILY, MONTHLY, QUARTERLY
    interest_posting_day INTEGER DEFAULT 1, -- Day of period
    
    -- POBO/ROBO Settings
    pobo_enabled BOOLEAN DEFAULT TRUE,
    robo_enabled BOOLEAN DEFAULT TRUE,
    centralized_payments_account_id UUID REFERENCES virtual_accounts(id),
    centralized_collections_account_id UUID REFERENCES virtual_accounts(id),
    
    -- Global Limits
    max_intercompany_exposure DECIMAL(18,2),
    max_single_entity_exposure DECIMAL(18,2),
    
    -- Configuration (JSON for flexibility)
    config JSONB,
    
    -- Status
    status VARCHAR(20) DEFAULT 'ACTIVE',
    effective_from DATE DEFAULT CURRENT_DATE,
    effective_to DATE,
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

CREATE INDEX idx_ihb_config_customer ON ihb_configurations(customer_id);
CREATE INDEX idx_ihb_config_status ON ihb_configurations(status);

COMMENT ON TABLE ihb_configurations IS 'IHB policy configuration (overlay, not account owner)';


-- -----------------------------------------------------------------------------
-- 2.2 IHB Entity Mappings (Maps existing schemes to IHB participants)
-- -----------------------------------------------------------------------------
-- Replaces: ihb_participants (which created new accounts)
-- Key change: References EXISTING corporate_schemes, doesn't create new structure

CREATE TABLE ihb_entity_mappings (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    
    -- IHB Link
    ihb_id UUID NOT NULL REFERENCES ihb_configurations(id),
    
    -- Link to EXISTING scheme (not new accounts!)
    scheme_id UUID NOT NULL REFERENCES corporate_schemes(id),
    
    -- Entity Identity
    entity_code VARCHAR(20) NOT NULL,
    entity_name VARCHAR(200),
    legal_entity_id VARCHAR(50), -- LEI
    tax_id VARCHAR(50),
    country_code VARCHAR(3),
    
    -- Role in IHB
    entity_role VARCHAR(30) NOT NULL,
    -- TREASURY_CENTER : Central treasury
    -- SUBSIDIARY      : Group company
    -- REGIONAL_HUB    : Regional treasury
    -- EXTERNAL_PARTNER: External IC partner
    
    is_treasury_center BOOLEAN DEFAULT FALSE,
    
    -- Interest Rates (override IHB defaults)
    credit_interest_rate DECIMAL(8,5),
    debit_interest_rate DECIMAL(8,5),
    spread_over_base DECIMAL(8,5),
    base_rate_reference VARCHAR(30), -- EIBOR_3M, SOFR, etc.
    
    -- Limits
    max_ic_lending DECIMAL(18,2),
    max_ic_borrowing DECIMAL(18,2),
    max_netting_exposure DECIMAL(18,2),
    max_fx_exposure DECIMAL(18,2),
    
    -- Netting Group
    netting_group VARCHAR(50),
    netting_priority INTEGER DEFAULT 100,
    
    -- Status
    status VARCHAR(20) DEFAULT 'ACTIVE',
    joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    exited_at TIMESTAMP,
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    
    CONSTRAINT uq_ihb_entity UNIQUE (ihb_id, scheme_id),
    CONSTRAINT uq_ihb_entity_code UNIQUE (ihb_id, entity_code),
    CONSTRAINT chk_entity_role CHECK (entity_role IN (
        'TREASURY_CENTER', 'SUBSIDIARY', 'REGIONAL_HUB', 'EXTERNAL_PARTNER'
    ))
);

CREATE INDEX idx_ihb_entity_ihb ON ihb_entity_mappings(ihb_id);
CREATE INDEX idx_ihb_entity_scheme ON ihb_entity_mappings(scheme_id);
CREATE INDEX idx_ihb_entity_role ON ihb_entity_mappings(entity_role);
CREATE INDEX idx_ihb_entity_netting ON ihb_entity_mappings(netting_group);

COMMENT ON TABLE ihb_entity_mappings IS 'Maps existing schemes to IHB participant roles (no new accounts)';


-- -----------------------------------------------------------------------------
-- 2.3 IHB Account Designations (Designates existing VAs for IHB purposes)
-- -----------------------------------------------------------------------------
-- Key concept: IHB doesn't own accounts, it DESIGNATES existing VAs for specific roles

CREATE TABLE ihb_account_designations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    
    -- Links
    ihb_id UUID NOT NULL REFERENCES ihb_configurations(id),
    entity_mapping_id UUID NOT NULL REFERENCES ihb_entity_mappings(id),
    virtual_account_id UUID NOT NULL REFERENCES virtual_accounts(id), -- EXISTING VA!
    
    -- Designation (purpose in IHB)
    designation VARCHAR(30) NOT NULL,
    -- OPERATING       : Main operating account
    -- IC_RECEIVABLE   : Intercompany receivables tracking
    -- IC_PAYABLE      : Intercompany payables tracking
    -- POOL_HEADER     : Notional pool header
    -- POOL_MEMBER     : Notional pool member
    -- NETTING         : Netting settlement account
    -- FX_SETTLEMENT   : Internal FX settlement
    -- POBO            : Pay-on-behalf-of account
    -- ROBO            : Receive-on-behalf-of account
    
    -- Flags
    is_primary BOOLEAN DEFAULT FALSE,
    interest_bearing BOOLEAN DEFAULT TRUE,
    include_in_pooling BOOLEAN DEFAULT TRUE,
    include_in_netting BOOLEAN DEFAULT TRUE,
    
    -- Pool Link (if POOL_MEMBER)
    pool_header_account_id UUID REFERENCES virtual_accounts(id),
    
    -- Notes
    designation_notes TEXT,
    
    -- Status
    status VARCHAR(20) DEFAULT 'ACTIVE',
    designated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP,
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    
    CONSTRAINT uq_ihb_va_desig UNIQUE (ihb_id, virtual_account_id, designation),
    CONSTRAINT chk_designation CHECK (designation IN (
        'OPERATING', 'IC_RECEIVABLE', 'IC_PAYABLE', 'POOL_HEADER', 
        'POOL_MEMBER', 'NETTING', 'FX_SETTLEMENT', 'POBO', 'ROBO'
    ))
);

CREATE INDEX idx_ihb_desig_ihb ON ihb_account_designations(ihb_id);
CREATE INDEX idx_ihb_desig_entity ON ihb_account_designations(entity_mapping_id);
CREATE INDEX idx_ihb_desig_va ON ihb_account_designations(virtual_account_id);
CREATE INDEX idx_ihb_desig_type ON ihb_account_designations(designation);

COMMENT ON TABLE ihb_account_designations IS 'Designates existing VAs for IHB purposes (overlay model)';


-- ============================================================================
-- PART 3: IHB ALLOCATIONS
-- ============================================================================

-- -----------------------------------------------------------------------------
-- 3.1 IHB Allocations (Budget & Credit Control)
-- -----------------------------------------------------------------------------

CREATE TABLE ihb_allocations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    
    -- Links
    ihb_id UUID NOT NULL REFERENCES ihb_configurations(id),
    entity_mapping_id UUID NOT NULL REFERENCES ihb_entity_mappings(id),
    
    -- Allocation Type
    allocation_type VARCHAR(30) NOT NULL,
    -- CREDIT_LINE    : Borrowing limit from IHB
    -- OVERDRAFT      : Allowed negative balance
    -- BUDGET         : Spending allocation for period
    -- FX_LIMIT       : Internal FX trading limit
    -- NETTING_LIMIT  : Maximum netting exposure
    -- INVESTMENT     : Cash investment allocation
    
    -- Allocation Reference
    allocation_reference VARCHAR(50),
    allocation_name VARCHAR(200),
    
    -- Hierarchy (for cascading allocations)
    parent_allocation_id UUID REFERENCES ihb_allocations(id),
    
    -- Period
    period_type VARCHAR(20),
    -- PERMANENT, ANNUAL, QUARTERLY, MONTHLY
    period_start DATE,
    period_end DATE,
    
    -- Currency
    currency_code VARCHAR(3) DEFAULT 'AED',
    
    -- Amounts
    allocated_amount DECIMAL(18,2) NOT NULL,
    utilized_amount DECIMAL(18,2) DEFAULT 0,
    reserved_amount DECIMAL(18,2) DEFAULT 0, -- Pending utilization
    -- available_amount = allocated - utilized - reserved (computed)
    
    -- Thresholds
    warning_threshold_pct DECIMAL(5,2) DEFAULT 80,
    breach_threshold_pct DECIMAL(5,2) DEFAULT 100,
    hard_limit BOOLEAN DEFAULT FALSE, -- If true, block transactions over limit
    
    -- Tracking
    last_utilization_update TIMESTAMP,
    warning_sent_at TIMESTAMP,
    breach_sent_at TIMESTAMP,
    
    -- Status
    status VARCHAR(20) DEFAULT 'ACTIVE',
    approved_by VARCHAR(100),
    approved_at TIMESTAMP,
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    
    CONSTRAINT chk_alloc_type CHECK (allocation_type IN (
        'CREDIT_LINE', 'OVERDRAFT', 'BUDGET', 'FX_LIMIT', 
        'NETTING_LIMIT', 'INVESTMENT'
    ))
);

CREATE INDEX idx_ihb_alloc_ihb ON ihb_allocations(ihb_id);
CREATE INDEX idx_ihb_alloc_entity ON ihb_allocations(entity_mapping_id);
CREATE INDEX idx_ihb_alloc_type ON ihb_allocations(allocation_type);
CREATE INDEX idx_ihb_alloc_parent ON ihb_allocations(parent_allocation_id);
CREATE INDEX idx_ihb_alloc_period ON ihb_allocations(period_start, period_end);

COMMENT ON TABLE ihb_allocations IS 'Budget and credit allocations for IHB participants';


-- -----------------------------------------------------------------------------
-- 3.2 Allocation Utilization History
-- -----------------------------------------------------------------------------

CREATE TABLE ihb_allocation_history (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    
    allocation_id UUID NOT NULL REFERENCES ihb_allocations(id),
    
    -- Change
    change_type VARCHAR(20) NOT NULL,
    -- UTILIZATION, RELEASE, ADJUSTMENT, INCREASE, DECREASE
    
    change_amount DECIMAL(18,2) NOT NULL,
    
    -- Balances After
    utilized_after DECIMAL(18,2) NOT NULL,
    available_after DECIMAL(18,2) NOT NULL,
    
    -- Reference
    reference_type VARCHAR(30),
    -- POBO_PAYMENT, IC_LOAN, FX_DEAL, NETTING, MANUAL
    reference_id UUID,
    
    -- Notes
    notes TEXT,
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100)
);

CREATE INDEX idx_alloc_hist_allocation ON ihb_allocation_history(allocation_id);
CREATE INDEX idx_alloc_hist_ref ON ihb_allocation_history(reference_type, reference_id);

COMMENT ON TABLE ihb_allocation_history IS 'Audit trail of allocation utilization changes';


-- ============================================================================
-- PART 4: IC POSITION TRACKING (POBO/ROBO)
-- ============================================================================

-- -----------------------------------------------------------------------------
-- 4.1 IC Positions (Real-time Intercompany Balances)
-- -----------------------------------------------------------------------------

CREATE TABLE ic_positions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    
    -- IHB Context
    ihb_id UUID NOT NULL REFERENCES ihb_configurations(id),
    
    -- Entity Pair (always stored with entity_a_id < entity_b_id for consistency)
    entity_a_id UUID NOT NULL REFERENCES ihb_entity_mappings(id),
    entity_b_id UUID NOT NULL REFERENCES ihb_entity_mappings(id),
    
    -- Currency
    currency_code VARCHAR(3) NOT NULL,
    
    -- Position
    -- Positive = Entity A owes Entity B
    -- Negative = Entity B owes Entity A
    net_position DECIMAL(18,2) DEFAULT 0,
    
    -- Gross Tracking
    gross_a_to_b DECIMAL(18,2) DEFAULT 0, -- Total A paid/received on behalf of B
    gross_b_to_a DECIMAL(18,2) DEFAULT 0, -- Total B paid/received on behalf of A
    
    -- Interest
    accrued_interest DECIMAL(18,2) DEFAULT 0,
    last_interest_calc_date DATE,
    interest_rate_applied DECIMAL(8,5),
    
    -- Settlement
    last_settlement_date DATE,
    last_settlement_amount DECIMAL(18,2),
    last_settlement_reference VARCHAR(50),
    
    -- Timestamps
    last_movement_at TIMESTAMP,
    position_as_of TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT uq_ic_position UNIQUE (ihb_id, entity_a_id, entity_b_id, currency_code),
    CONSTRAINT chk_entity_order CHECK (entity_a_id < entity_b_id)
);

CREATE INDEX idx_ic_pos_ihb ON ic_positions(ihb_id);
CREATE INDEX idx_ic_pos_entity_a ON ic_positions(entity_a_id);
CREATE INDEX idx_ic_pos_entity_b ON ic_positions(entity_b_id);
CREATE INDEX idx_ic_pos_currency ON ic_positions(currency_code);

COMMENT ON TABLE ic_positions IS 'Real-time intercompany positions between IHB entities';


-- -----------------------------------------------------------------------------
-- 4.2 IC Position Movements (Detailed Ledger)
-- -----------------------------------------------------------------------------

CREATE TABLE ic_position_movements (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    
    -- Position Link
    position_id UUID NOT NULL REFERENCES ic_positions(id),
    
    -- Movement Type
    movement_type VARCHAR(30) NOT NULL,
    -- POBO_PAYMENT     : A paid on behalf of B
    -- ROBO_COLLECTION  : A collected on behalf of B
    -- LOAN_DRAWDOWN    : A borrowed from B
    -- LOAN_REPAYMENT   : A repaid to B
    -- INTEREST_CHARGE  : Interest on IC balance
    -- NETTING_SETTLE   : Netting settlement
    -- FX_SETTLEMENT    : Internal FX settlement
    -- DIVIDEND         : Dividend payment
    -- MANAGEMENT_FEE   : Management fee charge
    -- MANUAL_ADJUST    : Manual adjustment
    
    -- Direction
    from_entity_id UUID NOT NULL REFERENCES ihb_entity_mappings(id),
    to_entity_id UUID NOT NULL REFERENCES ihb_entity_mappings(id),
    
    -- Amount
    amount DECIMAL(18,2) NOT NULL,
    currency_code VARCHAR(3) NOT NULL,
    
    -- Position Snapshot
    position_before DECIMAL(18,2) NOT NULL,
    position_after DECIMAL(18,2) NOT NULL,
    
    -- Reference to Source
    reference_type VARCHAR(30),
    -- TRANSACTION, IC_LOAN, NETTING_CYCLE, FX_DEAL, INTEREST_ACCRUAL
    reference_id UUID,
    external_reference VARCHAR(100),
    
    -- Description
    description TEXT,
    
    -- Dates
    effective_date DATE NOT NULL DEFAULT CURRENT_DATE,
    value_date DATE,
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    
    CONSTRAINT chk_movement_type CHECK (movement_type IN (
        'POBO_PAYMENT', 'ROBO_COLLECTION', 'LOAN_DRAWDOWN', 'LOAN_REPAYMENT',
        'INTEREST_CHARGE', 'NETTING_SETTLE', 'FX_SETTLEMENT', 'DIVIDEND',
        'MANAGEMENT_FEE', 'MANUAL_ADJUST'
    ))
);

CREATE INDEX idx_ic_mov_position ON ic_position_movements(position_id);
CREATE INDEX idx_ic_mov_from ON ic_position_movements(from_entity_id);
CREATE INDEX idx_ic_mov_to ON ic_position_movements(to_entity_id);
CREATE INDEX idx_ic_mov_date ON ic_position_movements(effective_date);
CREATE INDEX idx_ic_mov_type ON ic_position_movements(movement_type);
CREATE INDEX idx_ic_mov_ref ON ic_position_movements(reference_type, reference_id);

COMMENT ON TABLE ic_position_movements IS 'Detailed IC position ledger for audit trail';


-- ============================================================================
-- PART 5: SWEEPING RULES (Cash Concentration)
-- ============================================================================

CREATE TABLE sweeping_rules (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    
    -- Context (either IHB or standalone)
    ihb_id UUID REFERENCES ihb_configurations(id),
    customer_id UUID REFERENCES corporate_customers(id),
    
    -- Rule Identity
    rule_name VARCHAR(100) NOT NULL,
    rule_description TEXT,
    
    -- Source & Target
    source_account_id UUID NOT NULL REFERENCES virtual_accounts(id),
    target_account_id UUID NOT NULL REFERENCES virtual_accounts(id),
    
    -- Sweep Type
    sweep_type VARCHAR(20) NOT NULL,
    -- ZERO_BALANCE   : Sweep all to target
    -- TARGET_BALANCE : Maintain specific balance in source
    -- THRESHOLD      : Sweep when above threshold
    -- DEFICIT        : Top-up when below minimum
    
    -- Amounts
    threshold_amount DECIMAL(18,2), -- For THRESHOLD type
    target_balance DECIMAL(18,2),   -- For TARGET_BALANCE type
    minimum_balance DECIMAL(18,2),  -- For DEFICIT type
    maximum_sweep DECIMAL(18,2),    -- Max per sweep
    minimum_sweep DECIMAL(18,2),    -- Min to trigger sweep
    
    -- Currency
    currency_code VARCHAR(3) DEFAULT 'AED',
    cross_currency_enabled BOOLEAN DEFAULT FALSE,
    
    -- Schedule
    frequency VARCHAR(20) DEFAULT 'DAILY',
    -- REALTIME, HOURLY, DAILY, EOD, WEEKLY, MONTHLY
    schedule_time TIME, -- For scheduled sweeps
    schedule_day INTEGER, -- Day of week (1-7) or month (1-31)
    
    -- Execution
    auto_execute BOOLEAN DEFAULT TRUE,
    requires_approval BOOLEAN DEFAULT FALSE,
    approval_threshold DECIMAL(18,2),
    
    -- Priority (for multiple rules)
    priority INTEGER DEFAULT 100,
    
    -- Status
    status VARCHAR(20) DEFAULT 'ACTIVE',
    last_executed_at TIMESTAMP,
    last_sweep_amount DECIMAL(18,2),
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    
    CONSTRAINT chk_sweep_type CHECK (sweep_type IN (
        'ZERO_BALANCE', 'TARGET_BALANCE', 'THRESHOLD', 'DEFICIT'
    )),
    CONSTRAINT chk_sweep_context CHECK (
        ihb_id IS NOT NULL OR customer_id IS NOT NULL
    )
);

CREATE INDEX idx_sweep_ihb ON sweeping_rules(ihb_id);
CREATE INDEX idx_sweep_customer ON sweeping_rules(customer_id);
CREATE INDEX idx_sweep_source ON sweeping_rules(source_account_id);
CREATE INDEX idx_sweep_target ON sweeping_rules(target_account_id);
CREATE INDEX idx_sweep_status ON sweeping_rules(status);

COMMENT ON TABLE sweeping_rules IS 'Configurable cash concentration/sweeping rules';


-- -----------------------------------------------------------------------------
-- Sweep Execution Log
-- -----------------------------------------------------------------------------

CREATE TABLE sweep_executions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    
    rule_id UUID NOT NULL REFERENCES sweeping_rules(id),
    
    -- Execution Details
    executed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    trigger_type VARCHAR(20), -- SCHEDULED, MANUAL, REALTIME
    
    -- Balances
    source_balance_before DECIMAL(18,2),
    source_balance_after DECIMAL(18,2),
    target_balance_before DECIMAL(18,2),
    target_balance_after DECIMAL(18,2),
    
    -- Sweep Amount
    sweep_amount DECIMAL(18,2),
    currency_code VARCHAR(3),
    
    -- FX (if cross-currency)
    fx_rate DECIMAL(18,8),
    fx_amount DECIMAL(18,2),
    fx_currency VARCHAR(3),
    
    -- Result
    status VARCHAR(20) NOT NULL,
    -- SUCCESS, FAILED, SKIPPED, PENDING_APPROVAL
    failure_reason TEXT,
    
    -- Transaction Link
    transaction_id UUID REFERENCES transactions(id),
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sweep_exec_rule ON sweep_executions(rule_id);
CREATE INDEX idx_sweep_exec_date ON sweep_executions(executed_at);
CREATE INDEX idx_sweep_exec_status ON sweep_executions(status);

COMMENT ON TABLE sweep_executions IS 'Log of sweep rule executions';


-- ============================================================================
-- PART 6: VIRTUAL BRANCHES
-- ============================================================================

CREATE TABLE virtual_branches (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    
    -- Home Bank (us)
    home_customer_id UUID NOT NULL REFERENCES corporate_customers(id),
    
    -- Branch Identity
    branch_code VARCHAR(20) NOT NULL UNIQUE,
    branch_name VARCHAR(200) NOT NULL,
    
    -- Location
    branch_country VARCHAR(3) NOT NULL,
    branch_currency VARCHAR(3) NOT NULL,
    
    -- Partner Bank (correspondent)
    partner_bank_name VARCHAR(200),
    partner_bank_bic VARCHAR(11),
    partner_bank_country VARCHAR(3),
    
    -- Nostro Account (our account at partner)
    nostro_account_id UUID REFERENCES virtual_accounts(id),
    nostro_account_number VARCHAR(34),
    nostro_currency VARCHAR(3),
    
    -- Vostro Reference (partner's account with us, if applicable)
    vostro_reference VARCHAR(50),
    
    -- Clearing Access
    local_clearing_enabled BOOLEAN DEFAULT TRUE,
    rtgs_enabled BOOLEAN DEFAULT FALSE,
    ach_enabled BOOLEAN DEFAULT TRUE,
    
    -- Limits
    daily_payment_limit DECIMAL(18,2),
    single_payment_limit DECIMAL(18,2),
    
    -- Status
    status VARCHAR(20) DEFAULT 'ACTIVE',
    effective_from DATE DEFAULT CURRENT_DATE,
    effective_to DATE,
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100)
);

CREATE INDEX idx_vbranch_customer ON virtual_branches(home_customer_id);
CREATE INDEX idx_vbranch_country ON virtual_branches(branch_country);
CREATE INDEX idx_vbranch_status ON virtual_branches(status);

COMMENT ON TABLE virtual_branches IS 'Virtual presence in foreign markets via correspondent banking';


-- -----------------------------------------------------------------------------
-- Virtual Branch Customer Accounts
-- -----------------------------------------------------------------------------

CREATE TABLE virtual_branch_accounts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    
    -- Links
    branch_id UUID NOT NULL REFERENCES virtual_branches(id),
    customer_scheme_id UUID NOT NULL REFERENCES corporate_schemes(id),
    shadow_account_id UUID NOT NULL REFERENCES virtual_accounts(id),
    
    -- Account Details
    account_reference VARCHAR(50) NOT NULL,
    account_currency VARCHAR(3) NOT NULL,
    
    -- Balance (shadow of actual position)
    shadow_balance DECIMAL(18,2) DEFAULT 0,
    last_reconciled_at TIMESTAMP,
    
    -- Status
    status VARCHAR(20) DEFAULT 'ACTIVE',
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT uq_vbranch_account UNIQUE (branch_id, customer_scheme_id, account_currency)
);

CREATE INDEX idx_vbranch_acct_branch ON virtual_branch_accounts(branch_id);
CREATE INDEX idx_vbranch_acct_scheme ON virtual_branch_accounts(customer_scheme_id);

COMMENT ON TABLE virtual_branch_accounts IS 'Customer accounts within virtual branches';


-- ============================================================================
-- PART 7: VA MOVEMENTS (Single-Entry Transaction Ledger)
-- ============================================================================
-- Core principle: Each VA records its own movements (DEBIT or CREDIT)
-- A VA-to-VA transfer creates TWO movement records (one per VA)
-- This aligns with TCS BaNCS API accountStatementResponse structure

-- -----------------------------------------------------------------------------
-- 7.1 Transaction Groups (Links Related Movements)
-- -----------------------------------------------------------------------------

CREATE TABLE transaction_groups (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    
    -- Group Identity
    group_reference VARCHAR(50) NOT NULL UNIQUE,
    
    -- Type
    group_type VARCHAR(30) NOT NULL,
    -- INTERNAL_TRANSFER : VA to VA within same customer
    -- EXTERNAL_PAYMENT  : VA to external account
    -- EXTERNAL_RECEIPT  : External account to VA
    -- SWEEP             : Automated sweep
    -- IHB_POBO          : Pay on behalf of
    -- IHB_ROBO          : Receive on behalf of
    -- IHB_IC_LOAN       : Intercompany loan movement
    -- IHB_NETTING       : Netting settlement
    -- IHB_FX            : Internal FX
    -- ESCROW_FUND       : Escrow funding
    -- ESCROW_RELEASE    : Escrow release
    -- WALLET_LOAD       : Wallet load
    -- WALLET_SPEND      : Wallet spend
    -- INTEREST_POSTING  : Interest accrual posting
    
    -- Customer Context
    customer_id UUID NOT NULL REFERENCES corporate_customers(id),
    
    -- IHB Context (if applicable)
    ihb_id UUID REFERENCES ihb_configurations(id),
    
    -- Total Amount
    total_amount DECIMAL(18,2) NOT NULL,
    currency_code VARCHAR(3) NOT NULL,
    
    -- Movement Count (expected and actual)
    expected_movements INTEGER DEFAULT 2,
    actual_movements INTEGER DEFAULT 0,
    
    -- External References
    external_reference VARCHAR(100),
    e2e_reference VARCHAR(35),
    uetr VARCHAR(36),
    
    -- Source Reference (loan, netting cycle, etc.)
    source_type VARCHAR(30),
    source_id UUID,
    
    -- Status
    status VARCHAR(20) DEFAULT 'PENDING',
    -- PENDING, IN_PROGRESS, COMPLETED, PARTIALLY_COMPLETED, FAILED, REVERSED
    failure_reason TEXT,
    
    -- Dates
    initiated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    value_date DATE DEFAULT CURRENT_DATE,
    
    -- Initiator
    initiated_by VARCHAR(100),
    initiated_channel VARCHAR(50),
    
    -- Approval (if required)
    requires_approval BOOLEAN DEFAULT FALSE,
    approved_by VARCHAR(100),
    approved_at TIMESTAMP,
    
    -- BaNCS Sync
    bancs_transaction_ref BIGINT,
    bancs_sync_status VARCHAR(20) DEFAULT 'PENDING',
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT chk_group_type CHECK (group_type IN (
        'INTERNAL_TRANSFER', 'EXTERNAL_PAYMENT', 'EXTERNAL_RECEIPT', 'SWEEP',
        'IHB_POBO', 'IHB_ROBO', 'IHB_IC_LOAN', 'IHB_NETTING', 'IHB_FX',
        'ESCROW_FUND', 'ESCROW_RELEASE', 'WALLET_LOAD', 'WALLET_SPEND',
        'INTEREST_POSTING', 'FEE_CHARGE', 'REVERSAL'
    ))
);

CREATE INDEX idx_txn_grp_customer ON transaction_groups(customer_id);
CREATE INDEX idx_txn_grp_ihb ON transaction_groups(ihb_id);
CREATE INDEX idx_txn_grp_type ON transaction_groups(group_type);
CREATE INDEX idx_txn_grp_status ON transaction_groups(status);
CREATE INDEX idx_txn_grp_date ON transaction_groups(initiated_at);
CREATE INDEX idx_txn_grp_source ON transaction_groups(source_type, source_id);
CREATE INDEX idx_txn_grp_e2e ON transaction_groups(e2e_reference);
CREATE INDEX idx_txn_grp_uetr ON transaction_groups(uetr);

COMMENT ON TABLE transaction_groups IS 'Groups related VA movements into logical transactions';


-- -----------------------------------------------------------------------------
-- 7.2 VA Movements (Single-Entry Ledger per Virtual Account)
-- -----------------------------------------------------------------------------

CREATE TABLE va_movements (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    
    -- Movement Identity
    movement_id BIGINT NOT NULL,  -- Sequential per VA for statement ordering
    movement_reference VARCHAR(50) NOT NULL UNIQUE,
    
    -- Virtual Account (THIS movement belongs to)
    virtual_account_id UUID NOT NULL REFERENCES virtual_accounts(id),
    
    -- Transaction Group
    transaction_group_id UUID REFERENCES transaction_groups(id),
    
    -- Movement Type (Single Entry - DEBIT or CREDIT only)
    movement_type VARCHAR(10) NOT NULL,
    -- CREDIT: Incoming funds (increases balance)
    -- DEBIT: Outgoing funds (decreases balance)
    
    -- Amount (ALWAYS POSITIVE - type determines direction)
    amount DECIMAL(18,2) NOT NULL,
    currency_code VARCHAR(3) NOT NULL,
    
    -- Running Balance (snapshot after this movement)
    balance_before DECIMAL(18,2) NOT NULL,
    balance_after DECIMAL(18,2) NOT NULL,
    
    -- Counterparty Details
    counterparty_account VARCHAR(34),
    counterparty_name VARCHAR(200),
    counterparty_bic VARCHAR(11),
    counterparty_bank_name VARCHAR(200),
    
    -- Internal Transfer Linkage
    is_internal BOOLEAN DEFAULT FALSE,
    counterparty_va_id UUID REFERENCES virtual_accounts(id),
    linked_movement_id UUID REFERENCES va_movements(id),
    
    -- Transaction Context (use case identification)
    transaction_context VARCHAR(30) DEFAULT 'STANDARD',
    -- STANDARD, ESCROW, WALLET, ECOMMERCE, IHB_OPERATING, 
    -- IHB_POBO, IHB_ROBO, IHB_IC_LOAN, IHB_NETTING, IHB_FX, 
    -- SWEEP, INTEREST, FEE
    
    -- Program Account Link (if from program)
    program_account_id UUID REFERENCES program_accounts(id),
    
    -- IHB Entity Link (if IHB context)
    ihb_entity_id UUID REFERENCES ihb_entity_mappings(id),
    
    -- References
    e2e_reference VARCHAR(35),
    uetr VARCHAR(36),
    remittance_info TEXT,
    
    -- Description
    movement_description VARCHAR(200),
    narrative TEXT,
    
    -- Dates
    transaction_date DATE NOT NULL DEFAULT CURRENT_DATE,
    value_date DATE NOT NULL DEFAULT CURRENT_DATE,
    booking_timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Status
    status VARCHAR(20) DEFAULT 'POSTED',
    -- PENDING, POSTED, REVERSED, FAILED
    
    -- Reversal Handling
    is_reversal BOOLEAN DEFAULT FALSE,
    reversed_movement_id UUID REFERENCES va_movements(id),
    reversal_reason TEXT,
    reversed_at TIMESTAMP,
    
    -- BaNCS Sync
    bancs_movement_id BIGINT,
    bancs_sync_status VARCHAR(20) DEFAULT 'PENDING',
    bancs_synced_at TIMESTAMP,
    bancs_sync_error TEXT,
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    
    CONSTRAINT chk_movement_type CHECK (movement_type IN ('CREDIT', 'DEBIT')),
    CONSTRAINT chk_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_balance_consistent CHECK (
        (movement_type = 'CREDIT' AND balance_after = balance_before + amount) OR
        (movement_type = 'DEBIT' AND balance_after = balance_before - amount)
    )
);

-- Primary indexes for statement queries (BaNCS API alignment)
CREATE INDEX idx_va_mov_account ON va_movements(virtual_account_id);
CREATE INDEX idx_va_mov_account_date ON va_movements(virtual_account_id, transaction_date DESC);
CREATE INDEX idx_va_mov_account_seq ON va_movements(virtual_account_id, movement_id DESC);
CREATE UNIQUE INDEX idx_va_mov_unique_seq ON va_movements(virtual_account_id, movement_id);

-- Linkage indexes
CREATE INDEX idx_va_mov_group ON va_movements(transaction_group_id);
CREATE INDEX idx_va_mov_linked ON va_movements(linked_movement_id);
CREATE INDEX idx_va_mov_counterparty_va ON va_movements(counterparty_va_id);

-- Context and status indexes
CREATE INDEX idx_va_mov_context ON va_movements(transaction_context);
CREATE INDEX idx_va_mov_status ON va_movements(status);
CREATE INDEX idx_va_mov_program ON va_movements(program_account_id);
CREATE INDEX idx_va_mov_ihb_entity ON va_movements(ihb_entity_id);

-- Reference indexes
CREATE INDEX idx_va_mov_e2e ON va_movements(e2e_reference);
CREATE INDEX idx_va_mov_uetr ON va_movements(uetr);
CREATE INDEX idx_va_mov_bancs ON va_movements(bancs_movement_id);

COMMENT ON TABLE va_movements IS 'Single-entry movement ledger per Virtual Account (BaNCS API aligned)';


-- -----------------------------------------------------------------------------
-- 7.3 Movement Sequence Generator (per VA)
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION get_next_movement_id(p_virtual_account_id UUID)
RETURNS BIGINT AS $$
DECLARE
    v_next_id BIGINT;
BEGIN
    SELECT COALESCE(MAX(movement_id), 0) + 1 INTO v_next_id
    FROM va_movements
    WHERE virtual_account_id = p_virtual_account_id;
    
    RETURN v_next_id;
END;
$$ LANGUAGE plpgsql;


-- -----------------------------------------------------------------------------
-- 7.4 Create VA Movement Function
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION create_va_movement(
    p_virtual_account_id UUID,
    p_transaction_group_id UUID,
    p_movement_type VARCHAR(10),
    p_amount DECIMAL(18,2),
    p_currency VARCHAR(3),
    p_counterparty_account VARCHAR(34) DEFAULT NULL,
    p_counterparty_name VARCHAR(200) DEFAULT NULL,
    p_counterparty_va_id UUID DEFAULT NULL,
    p_transaction_context VARCHAR(30) DEFAULT 'STANDARD',
    p_description VARCHAR(200) DEFAULT NULL,
    p_e2e_reference VARCHAR(35) DEFAULT NULL,
    p_value_date DATE DEFAULT CURRENT_DATE
) RETURNS UUID AS $$
DECLARE
    v_movement_id BIGINT;
    v_movement_uuid UUID;
    v_movement_ref VARCHAR(50);
    v_balance_before DECIMAL(18,2);
    v_balance_after DECIMAL(18,2);
    v_is_internal BOOLEAN;
BEGIN
    -- Get current balance
    SELECT COALESCE(current_balance, 0) INTO v_balance_before
    FROM virtual_accounts
    WHERE id = p_virtual_account_id
    FOR UPDATE;
    
    -- Calculate new balance
    IF p_movement_type = 'CREDIT' THEN
        v_balance_after := v_balance_before + p_amount;
    ELSE
        v_balance_after := v_balance_before - p_amount;
        
        -- Check sufficient balance (optional - can be disabled for overdraft)
        IF v_balance_after < 0 THEN
            RAISE EXCEPTION 'Insufficient balance. Available: %, Required: %', 
                v_balance_before, p_amount;
        END IF;
    END IF;
    
    -- Get next movement ID for this VA
    v_movement_id := get_next_movement_id(p_virtual_account_id);
    
    -- Generate movement reference
    v_movement_ref := 'MOV-' || EXTRACT(EPOCH FROM CURRENT_TIMESTAMP)::BIGINT || '-' || v_movement_id;
    
    -- Determine if internal
    v_is_internal := p_counterparty_va_id IS NOT NULL;
    
    -- Insert movement
    INSERT INTO va_movements (
        movement_id, movement_reference, virtual_account_id, transaction_group_id,
        movement_type, amount, currency_code,
        balance_before, balance_after,
        counterparty_account, counterparty_name, 
        is_internal, counterparty_va_id,
        transaction_context, movement_description,
        e2e_reference, value_date, status
    ) VALUES (
        v_movement_id, v_movement_ref, p_virtual_account_id, p_transaction_group_id,
        p_movement_type, p_amount, p_currency,
        v_balance_before, v_balance_after,
        p_counterparty_account, p_counterparty_name,
        v_is_internal, p_counterparty_va_id,
        p_transaction_context, p_description,
        p_e2e_reference, p_value_date, 'POSTED'
    )
    RETURNING id INTO v_movement_uuid;
    
    -- Update VA balance
    UPDATE virtual_accounts
    SET current_balance = v_balance_after,
        available_balance = v_balance_after - COALESCE(fund_hold, 0),
        balance_last_updated = CURRENT_TIMESTAMP,
        updated_at = CURRENT_TIMESTAMP
    WHERE id = p_virtual_account_id;
    
    -- Update transaction group movement count
    IF p_transaction_group_id IS NOT NULL THEN
        UPDATE transaction_groups
        SET actual_movements = actual_movements + 1,
            status = CASE 
                WHEN actual_movements + 1 >= expected_movements THEN 'COMPLETED'
                ELSE 'IN_PROGRESS'
            END,
            completed_at = CASE 
                WHEN actual_movements + 1 >= expected_movements THEN CURRENT_TIMESTAMP
                ELSE completed_at
            END,
            updated_at = CURRENT_TIMESTAMP
        WHERE id = p_transaction_group_id;
    END IF;
    
    RETURN v_movement_uuid;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION create_va_movement IS 'Creates a single VA movement and updates balances';


-- -----------------------------------------------------------------------------
-- 7.5 Internal Transfer Function (Creates TWO movements)
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION execute_internal_transfer(
    p_source_va_id UUID,
    p_target_va_id UUID,
    p_amount DECIMAL(18,2),
    p_currency VARCHAR(3),
    p_description VARCHAR(200) DEFAULT NULL,
    p_transaction_context VARCHAR(30) DEFAULT 'INTERNAL_TRANSFER',
    p_e2e_reference VARCHAR(35) DEFAULT NULL,
    p_initiated_by VARCHAR(100) DEFAULT NULL
) RETURNS UUID AS $$
DECLARE
    v_group_id UUID;
    v_group_ref VARCHAR(50);
    v_source_movement_id UUID;
    v_target_movement_id UUID;
    v_source_va virtual_accounts%ROWTYPE;
    v_target_va virtual_accounts%ROWTYPE;
BEGIN
    -- Get VA details
    SELECT * INTO v_source_va FROM virtual_accounts WHERE id = p_source_va_id;
    SELECT * INTO v_target_va FROM virtual_accounts WHERE id = p_target_va_id;
    
    IF v_source_va.id IS NULL THEN
        RAISE EXCEPTION 'Source virtual account not found: %', p_source_va_id;
    END IF;
    
    IF v_target_va.id IS NULL THEN
        RAISE EXCEPTION 'Target virtual account not found: %', p_target_va_id;
    END IF;
    
    -- Generate group reference
    v_group_ref := 'TXN-' || TO_CHAR(CURRENT_TIMESTAMP, 'YYYYMMDDHH24MISS') || '-' || 
                   SUBSTR(uuid_generate_v4()::TEXT, 1, 8);
    
    -- Create transaction group
    INSERT INTO transaction_groups (
        group_reference, group_type, customer_id,
        total_amount, currency_code, expected_movements,
        e2e_reference, initiated_by, initiated_channel, status
    ) VALUES (
        v_group_ref, 'INTERNAL_TRANSFER', v_source_va.customer_id,
        p_amount, p_currency, 2,
        p_e2e_reference, p_initiated_by, 'API', 'IN_PROGRESS'
    )
    RETURNING id INTO v_group_id;
    
    -- Create DEBIT movement on source VA
    v_source_movement_id := create_va_movement(
        p_virtual_account_id := p_source_va_id,
        p_transaction_group_id := v_group_id,
        p_movement_type := 'DEBIT',
        p_amount := p_amount,
        p_currency := p_currency,
        p_counterparty_account := v_target_va.virtual_iban,
        p_counterparty_name := v_target_va.account_name,
        p_counterparty_va_id := p_target_va_id,
        p_transaction_context := p_transaction_context,
        p_description := COALESCE(p_description, 'Transfer to ' || v_target_va.account_name),
        p_e2e_reference := p_e2e_reference
    );
    
    -- Create CREDIT movement on target VA
    v_target_movement_id := create_va_movement(
        p_virtual_account_id := p_target_va_id,
        p_transaction_group_id := v_group_id,
        p_movement_type := 'CREDIT',
        p_amount := p_amount,
        p_currency := p_currency,
        p_counterparty_account := v_source_va.virtual_iban,
        p_counterparty_name := v_source_va.account_name,
        p_counterparty_va_id := p_source_va_id,
        p_transaction_context := p_transaction_context,
        p_description := COALESCE(p_description, 'Transfer from ' || v_source_va.account_name),
        p_e2e_reference := p_e2e_reference
    );
    
    -- Link the movements
    UPDATE va_movements SET linked_movement_id = v_target_movement_id WHERE id = v_source_movement_id;
    UPDATE va_movements SET linked_movement_id = v_source_movement_id WHERE id = v_target_movement_id;
    
    RETURN v_group_id;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION execute_internal_transfer IS 'Executes VA-to-VA transfer creating TWO linked movements';


-- -----------------------------------------------------------------------------
-- 7.6 IHB POBO Function (Pay On Behalf Of)
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION execute_ihb_pobo(
    p_ihb_id UUID,
    p_originating_entity_id UUID,  -- Subsidiary requesting payment
    p_treasury_va_id UUID,         -- Treasury account making payment
    p_amount DECIMAL(18,2),
    p_currency VARCHAR(3),
    p_beneficiary_account VARCHAR(34),
    p_beneficiary_name VARCHAR(200),
    p_description VARCHAR(200) DEFAULT NULL,
    p_e2e_reference VARCHAR(35) DEFAULT NULL,
    p_initiated_by VARCHAR(100) DEFAULT NULL
) RETURNS UUID AS $$
DECLARE
    v_group_id UUID;
    v_group_ref VARCHAR(50);
    v_treasury_movement_id UUID;
    v_treasury_va virtual_accounts%ROWTYPE;
    v_originating_entity ihb_entity_mappings%ROWTYPE;
    v_ihb ihb_configurations%ROWTYPE;
BEGIN
    -- Validate IHB
    SELECT * INTO v_ihb FROM ihb_configurations WHERE id = p_ihb_id;
    IF v_ihb.id IS NULL OR NOT v_ihb.pobo_enabled THEN
        RAISE EXCEPTION 'IHB not found or POBO not enabled';
    END IF;
    
    -- Get entity details
    SELECT * INTO v_originating_entity FROM ihb_entity_mappings WHERE id = p_originating_entity_id;
    SELECT * INTO v_treasury_va FROM virtual_accounts WHERE id = p_treasury_va_id;
    
    -- Generate group reference
    v_group_ref := 'POBO-' || TO_CHAR(CURRENT_TIMESTAMP, 'YYYYMMDDHH24MISS') || '-' || 
                   SUBSTR(uuid_generate_v4()::TEXT, 1, 8);
    
    -- Create transaction group
    INSERT INTO transaction_groups (
        group_reference, group_type, customer_id, ihb_id,
        total_amount, currency_code, expected_movements,
        e2e_reference, initiated_by, initiated_channel, status
    ) VALUES (
        v_group_ref, 'IHB_POBO', v_ihb.customer_id, p_ihb_id,
        p_amount, p_currency, 1,  -- External payment = 1 movement
        p_e2e_reference, p_initiated_by, 'IHB', 'IN_PROGRESS'
    )
    RETURNING id INTO v_group_id;
    
    -- Create DEBIT movement on Treasury VA (actual payment)
    v_treasury_movement_id := create_va_movement(
        p_virtual_account_id := p_treasury_va_id,
        p_transaction_group_id := v_group_id,
        p_movement_type := 'DEBIT',
        p_amount := p_amount,
        p_currency := p_currency,
        p_counterparty_account := p_beneficiary_account,
        p_counterparty_name := p_beneficiary_name,
        p_counterparty_va_id := NULL,  -- External
        p_transaction_context := 'IHB_POBO',
        p_description := 'POBO: ' || v_originating_entity.entity_name || ' - ' || COALESCE(p_description, ''),
        p_e2e_reference := p_e2e_reference
    );
    
    -- Update IHB entity link on movement
    UPDATE va_movements SET ihb_entity_id = p_originating_entity_id WHERE id = v_treasury_movement_id;
    
    -- Record IC position movement (originating entity owes treasury)
    PERFORM record_ic_movement(
        p_ihb_id := p_ihb_id,
        p_from_entity_id := p_originating_entity_id,  -- Subsidiary owes
        p_to_entity_id := (SELECT id FROM ihb_entity_mappings WHERE ihb_id = p_ihb_id AND is_treasury_center = TRUE LIMIT 1),
        p_amount := p_amount,
        p_currency := p_currency,
        p_movement_type := 'POBO_PAYMENT',
        p_reference_type := 'TRANSACTION_GROUP',
        p_reference_id := v_group_id,
        p_description := 'POBO payment for ' || v_originating_entity.entity_name
    );
    
    RETURN v_group_id;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION execute_ihb_pobo IS 'Executes Pay-On-Behalf-Of from treasury and records IC position';


-- -----------------------------------------------------------------------------
-- 7.7 IHB ROBO Function (Receive On Behalf Of)
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION execute_ihb_robo(
    p_ihb_id UUID,
    p_beneficiary_entity_id UUID,  -- Subsidiary who should receive
    p_treasury_va_id UUID,         -- Treasury account receiving
    p_amount DECIMAL(18,2),
    p_currency VARCHAR(3),
    p_payer_account VARCHAR(34),
    p_payer_name VARCHAR(200),
    p_description VARCHAR(200) DEFAULT NULL,
    p_e2e_reference VARCHAR(35) DEFAULT NULL
) RETURNS UUID AS $$
DECLARE
    v_group_id UUID;
    v_group_ref VARCHAR(50);
    v_treasury_movement_id UUID;
    v_treasury_va virtual_accounts%ROWTYPE;
    v_beneficiary_entity ihb_entity_mappings%ROWTYPE;
    v_ihb ihb_configurations%ROWTYPE;
BEGIN
    -- Validate IHB
    SELECT * INTO v_ihb FROM ihb_configurations WHERE id = p_ihb_id;
    IF v_ihb.id IS NULL OR NOT v_ihb.robo_enabled THEN
        RAISE EXCEPTION 'IHB not found or ROBO not enabled';
    END IF;
    
    -- Get entity details
    SELECT * INTO v_beneficiary_entity FROM ihb_entity_mappings WHERE id = p_beneficiary_entity_id;
    SELECT * INTO v_treasury_va FROM virtual_accounts WHERE id = p_treasury_va_id;
    
    -- Generate group reference
    v_group_ref := 'ROBO-' || TO_CHAR(CURRENT_TIMESTAMP, 'YYYYMMDDHH24MISS') || '-' || 
                   SUBSTR(uuid_generate_v4()::TEXT, 1, 8);
    
    -- Create transaction group
    INSERT INTO transaction_groups (
        group_reference, group_type, customer_id, ihb_id,
        total_amount, currency_code, expected_movements,
        e2e_reference, initiated_channel, status
    ) VALUES (
        v_group_ref, 'IHB_ROBO', v_ihb.customer_id, p_ihb_id,
        p_amount, p_currency, 1,  -- External receipt = 1 movement
        p_e2e_reference, 'IHB', 'IN_PROGRESS'
    )
    RETURNING id INTO v_group_id;
    
    -- Create CREDIT movement on Treasury VA (actual receipt)
    v_treasury_movement_id := create_va_movement(
        p_virtual_account_id := p_treasury_va_id,
        p_transaction_group_id := v_group_id,
        p_movement_type := 'CREDIT',
        p_amount := p_amount,
        p_currency := p_currency,
        p_counterparty_account := p_payer_account,
        p_counterparty_name := p_payer_name,
        p_counterparty_va_id := NULL,  -- External
        p_transaction_context := 'IHB_ROBO',
        p_description := 'ROBO: For ' || v_beneficiary_entity.entity_name || ' - ' || COALESCE(p_description, ''),
        p_e2e_reference := p_e2e_reference
    );
    
    -- Update IHB entity link on movement
    UPDATE va_movements SET ihb_entity_id = p_beneficiary_entity_id WHERE id = v_treasury_movement_id;
    
    -- Record IC position movement (treasury owes beneficiary entity)
    PERFORM record_ic_movement(
        p_ihb_id := p_ihb_id,
        p_from_entity_id := (SELECT id FROM ihb_entity_mappings WHERE ihb_id = p_ihb_id AND is_treasury_center = TRUE LIMIT 1),
        p_to_entity_id := p_beneficiary_entity_id,  -- Treasury owes subsidiary
        p_amount := p_amount,
        p_currency := p_currency,
        p_movement_type := 'ROBO_COLLECTION',
        p_reference_type := 'TRANSACTION_GROUP',
        p_reference_id := v_group_id,
        p_description := 'ROBO collection for ' || v_beneficiary_entity.entity_name
    );
    
    RETURN v_group_id;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION execute_ihb_robo IS 'Executes Receive-On-Behalf-Of to treasury and records IC position';


-- ============================================================================
-- PART 8: SCHEMA MODIFICATIONS
-- ============================================================================

-- -----------------------------------------------------------------------------
-- 8.1 Add program_account_id to virtual_accounts
-- -----------------------------------------------------------------------------

ALTER TABLE virtual_accounts 
ADD COLUMN IF NOT EXISTS program_account_id UUID REFERENCES program_accounts(id);

CREATE INDEX IF NOT EXISTS idx_va_program_account ON virtual_accounts(program_account_id);

COMMENT ON COLUMN virtual_accounts.program_account_id IS 
    'Link to program_accounts for program-managed VAs';


-- -----------------------------------------------------------------------------
-- 8.2 Update account_type enum
-- -----------------------------------------------------------------------------

COMMENT ON COLUMN virtual_accounts.account_type IS 
    'STANDARD, ESCROW, WALLET, ECOMMERCE, COLLECTIONS, PAYABLES, CLIENT_MONEY, 
     IHB_OPERATING, IHB_POOLING, VIRTUAL_BRANCH';


-- -----------------------------------------------------------------------------
-- 8.3 Add IHB designation reference
-- -----------------------------------------------------------------------------

ALTER TABLE virtual_accounts 
ADD COLUMN IF NOT EXISTS ihb_designation_id UUID REFERENCES ihb_account_designations(id);

CREATE INDEX IF NOT EXISTS idx_va_ihb_desig ON virtual_accounts(ihb_designation_id);

COMMENT ON COLUMN virtual_accounts.ihb_designation_id IS 
    'Link to IHB designation for IHB-overlay VAs';


-- -----------------------------------------------------------------------------
-- 8.4 Add last_movement_id to virtual_accounts
-- -----------------------------------------------------------------------------

ALTER TABLE virtual_accounts 
ADD COLUMN IF NOT EXISTS last_movement_id BIGINT DEFAULT 0;

COMMENT ON COLUMN virtual_accounts.last_movement_id IS 
    'Last movement ID for this VA (for statement ordering)';


-- ============================================================================
-- PART 9: VIEWS
-- ============================================================================

-- -----------------------------------------------------------------------------
-- 9.1 Unified Program Dashboard View
-- -----------------------------------------------------------------------------

CREATE OR REPLACE VIEW vw_program_dashboard AS
SELECT 
    p.id AS program_id,
    p.program_code,
    p.program_name,
    p.program_type,
    c.customer_code,
    c.customer_name,
    p.status AS program_status,
    
    -- Account Counts
    COUNT(pa.id) AS total_accounts,
    COUNT(pa.id) FILTER (WHERE pa.status = 'ACTIVE') AS active_accounts,
    COUNT(pa.id) FILTER (WHERE pa.status = 'PAID') AS paid_accounts,
    COUNT(pa.id) FILTER (WHERE pa.status = 'EXPIRED') AS expired_accounts,
    
    -- Amounts
    COALESCE(SUM(pa.expected_amount), 0) AS total_expected,
    COALESCE(SUM(pa.received_amount), 0) AS total_received,
    COALESCE(SUM(pa.received_amount) - SUM(pa.expected_amount), 0) AS total_variance,
    
    -- Rates
    CASE 
        WHEN COUNT(pa.id) > 0 
        THEN ROUND(COUNT(pa.id) FILTER (WHERE pa.status = 'PAID')::NUMERIC / COUNT(pa.id) * 100, 2)
        ELSE 0 
    END AS conversion_rate_pct,
    
    -- Period
    MIN(pa.created_at)::DATE AS first_account_date,
    MAX(pa.created_at)::DATE AS last_account_date

FROM va_programs p
JOIN corporate_customers c ON p.customer_id = c.id
LEFT JOIN program_accounts pa ON p.id = pa.program_id
GROUP BY p.id, p.program_code, p.program_name, p.program_type, 
         c.customer_code, c.customer_name, p.status;

COMMENT ON VIEW vw_program_dashboard IS 'Unified program statistics dashboard';


-- -----------------------------------------------------------------------------
-- 9.2 VA Account Statement View (BaNCS API aligned)
-- -----------------------------------------------------------------------------

CREATE OR REPLACE VIEW vw_va_account_statement AS
SELECT 
    m.virtual_account_id,
    va.virtual_iban,
    va.account_name,
    
    -- Movement Details
    m.movement_id,
    m.movement_reference,
    m.transaction_date,
    m.value_date,
    m.booking_timestamp,
    
    -- Single-entry: credit OR debit
    CASE WHEN m.movement_type = 'CREDIT' THEN m.amount ELSE NULL END AS credit_amount,
    CASE WHEN m.movement_type = 'DEBIT' THEN m.amount ELSE NULL END AS debit_amount,
    m.currency_code AS transaction_currency,
    
    -- Running Balance
    m.balance_after AS balance,
    
    -- Counterparty
    m.counterparty_name,
    m.counterparty_account,
    m.is_internal,
    
    -- References (BaNCS API fields)
    g.group_reference AS processing_system_txn_reference,
    m.e2e_reference AS e2e_identification,
    m.uetr,
    
    -- Description
    m.movement_description,
    m.narrative,
    m.transaction_context,
    
    -- Settlement Account (for external)
    CASE WHEN NOT m.is_internal THEN 
        (SELECT va2.virtual_iban FROM virtual_accounts va2 
         JOIN corporate_schemes cs ON va2.corporate_scheme_id = cs.id
         WHERE cs.id = va.corporate_scheme_id AND va2.account_type = 'STANDARD'
         LIMIT 1)
    ELSE NULL END AS settlement_account

FROM va_movements m
JOIN virtual_accounts va ON m.virtual_account_id = va.id
LEFT JOIN transaction_groups g ON m.transaction_group_id = g.id
WHERE m.status = 'POSTED';

COMMENT ON VIEW vw_va_account_statement IS 'VA statement view aligned with BaNCS accountStatementResponse API';


-- -----------------------------------------------------------------------------
-- 9.3 IHB Entity Positions View
-- -----------------------------------------------------------------------------

CREATE OR REPLACE VIEW vw_ihb_entity_positions AS
SELECT 
    e.id AS entity_id,
    e.entity_code,
    e.entity_name,
    e.entity_role,
    h.ihb_code,
    h.ihb_name,
    
    -- Operating Account Balance
    COALESCE(va.current_balance, 0) AS operating_balance,
    va.currency_code,
    
    -- Allocations
    a_credit.allocated_amount AS credit_line_allocated,
    a_credit.utilized_amount AS credit_line_utilized,
    a_credit.allocated_amount - a_credit.utilized_amount AS credit_line_available,
    
    -- IC Positions (sum of all positions where this entity is involved)
    COALESCE(ic_owed.total_owed, 0) AS total_ic_owed,
    COALESCE(ic_owing.total_owing, 0) AS total_ic_owing,
    COALESCE(ic_owed.total_owed, 0) - COALESCE(ic_owing.total_owing, 0) AS net_ic_position,
    
    e.status

FROM ihb_entity_mappings e
JOIN ihb_configurations h ON e.ihb_id = h.id
LEFT JOIN ihb_account_designations d ON e.id = d.entity_mapping_id 
    AND d.designation = 'OPERATING' AND d.is_primary = TRUE
LEFT JOIN virtual_accounts va ON d.virtual_account_id = va.id
LEFT JOIN ihb_allocations a_credit ON e.id = a_credit.entity_mapping_id 
    AND a_credit.allocation_type = 'CREDIT_LINE' AND a_credit.status = 'ACTIVE'
LEFT JOIN LATERAL (
    SELECT SUM(ABS(CASE WHEN entity_a_id = e.id AND net_position < 0 THEN net_position 
                        WHEN entity_b_id = e.id AND net_position > 0 THEN net_position 
                        ELSE 0 END)) AS total_owed
    FROM ic_positions WHERE entity_a_id = e.id OR entity_b_id = e.id
) ic_owed ON TRUE
LEFT JOIN LATERAL (
    SELECT SUM(ABS(CASE WHEN entity_a_id = e.id AND net_position > 0 THEN net_position 
                        WHEN entity_b_id = e.id AND net_position < 0 THEN net_position 
                        ELSE 0 END)) AS total_owing
    FROM ic_positions WHERE entity_a_id = e.id OR entity_b_id = e.id
) ic_owing ON TRUE

WHERE e.status = 'ACTIVE';

COMMENT ON VIEW vw_ihb_entity_positions IS 'IHB participant positions and allocations';


-- -----------------------------------------------------------------------------
-- 8.3 IC Position Summary View
-- -----------------------------------------------------------------------------

CREATE OR REPLACE VIEW vw_ic_position_summary AS
SELECT 
    p.id AS position_id,
    h.ihb_code,
    ea.entity_code AS entity_a_code,
    ea.entity_name AS entity_a_name,
    eb.entity_code AS entity_b_code,
    eb.entity_name AS entity_b_name,
    p.currency_code,
    p.net_position,
    CASE 
        WHEN p.net_position > 0 THEN ea.entity_code || ' owes ' || eb.entity_code
        WHEN p.net_position < 0 THEN eb.entity_code || ' owes ' || ea.entity_code
        ELSE 'Settled'
    END AS position_description,
    ABS(p.net_position) AS absolute_position,
    p.gross_a_to_b,
    p.gross_b_to_a,
    p.accrued_interest,
    p.last_settlement_date,
    p.last_movement_at

FROM ic_positions p
JOIN ihb_configurations h ON p.ihb_id = h.id
JOIN ihb_entity_mappings ea ON p.entity_a_id = ea.id
JOIN ihb_entity_mappings eb ON p.entity_b_id = eb.id;

COMMENT ON VIEW vw_ic_position_summary IS 'Human-readable IC position summary';


-- ============================================================================
-- PART 9: FUNCTIONS
-- ============================================================================

-- -----------------------------------------------------------------------------
-- 9.1 Record IC Position Movement
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION record_ic_movement(
    p_ihb_id UUID,
    p_from_entity_id UUID,
    p_to_entity_id UUID,
    p_amount DECIMAL(18,2),
    p_currency VARCHAR(3),
    p_movement_type VARCHAR(30),
    p_reference_type VARCHAR(30),
    p_reference_id UUID,
    p_description TEXT DEFAULT NULL
) RETURNS UUID AS $$
DECLARE
    v_position_id UUID;
    v_entity_a_id UUID;
    v_entity_b_id UUID;
    v_position_before DECIMAL(18,2);
    v_position_after DECIMAL(18,2);
    v_adjustment DECIMAL(18,2);
    v_movement_id UUID;
BEGIN
    -- Determine entity order (a < b)
    IF p_from_entity_id < p_to_entity_id THEN
        v_entity_a_id := p_from_entity_id;
        v_entity_b_id := p_to_entity_id;
        v_adjustment := p_amount; -- A owes B more (positive)
    ELSE
        v_entity_a_id := p_to_entity_id;
        v_entity_b_id := p_from_entity_id;
        v_adjustment := -p_amount; -- B owes A more (negative from A's perspective)
    END IF;
    
    -- Get or create position
    SELECT id, net_position INTO v_position_id, v_position_before
    FROM ic_positions
    WHERE ihb_id = p_ihb_id 
      AND entity_a_id = v_entity_a_id 
      AND entity_b_id = v_entity_b_id
      AND currency_code = p_currency
    FOR UPDATE;
    
    IF v_position_id IS NULL THEN
        -- Create new position
        INSERT INTO ic_positions (ihb_id, entity_a_id, entity_b_id, currency_code, net_position)
        VALUES (p_ihb_id, v_entity_a_id, v_entity_b_id, p_currency, 0)
        RETURNING id, net_position INTO v_position_id, v_position_before;
    END IF;
    
    -- Calculate new position
    v_position_after := v_position_before + v_adjustment;
    
    -- Update position
    UPDATE ic_positions
    SET net_position = v_position_after,
        gross_a_to_b = gross_a_to_b + CASE WHEN v_adjustment > 0 THEN p_amount ELSE 0 END,
        gross_b_to_a = gross_b_to_a + CASE WHEN v_adjustment < 0 THEN p_amount ELSE 0 END,
        last_movement_at = CURRENT_TIMESTAMP,
        updated_at = CURRENT_TIMESTAMP
    WHERE id = v_position_id;
    
    -- Record movement
    INSERT INTO ic_position_movements (
        position_id, movement_type, from_entity_id, to_entity_id,
        amount, currency_code, position_before, position_after,
        reference_type, reference_id, description, effective_date
    ) VALUES (
        v_position_id, p_movement_type, p_from_entity_id, p_to_entity_id,
        p_amount, p_currency, v_position_before, v_position_after,
        p_reference_type, p_reference_id, p_description, CURRENT_DATE
    )
    RETURNING id INTO v_movement_id;
    
    RETURN v_movement_id;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION record_ic_movement IS 'Records an IC position movement and updates the running position';


-- -----------------------------------------------------------------------------
-- 9.2 Allocate Program Account from Pool
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION allocate_program_account(
    p_program_id UUID,
    p_external_reference VARCHAR(100),
    p_expected_amount DECIMAL(18,2),
    p_currency VARCHAR(3),
    p_holder_id UUID DEFAULT NULL,
    p_expiry_hours INTEGER DEFAULT NULL,
    p_metadata JSONB DEFAULT NULL
) RETURNS TABLE (
    account_id UUID,
    virtual_account_id UUID,
    virtual_iban VARCHAR(34),
    expires_at TIMESTAMP
) AS $$
DECLARE
    v_program va_programs%ROWTYPE;
    v_pool_iban program_iban_pool%ROWTYPE;
    v_va_id UUID;
    v_account_id UUID;
    v_expires_at TIMESTAMP;
BEGIN
    -- Get program
    SELECT * INTO v_program FROM va_programs WHERE id = p_program_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Program not found: %', p_program_id;
    END IF;
    
    -- Check program status
    IF v_program.status != 'ACTIVE' THEN
        RAISE EXCEPTION 'Program is not active: %', v_program.program_code;
    END IF;
    
    -- Get available IBAN from pool
    SELECT * INTO v_pool_iban
    FROM program_iban_pool
    WHERE program_id = p_program_id AND status = 'AVAILABLE'
    ORDER BY id
    LIMIT 1
    FOR UPDATE SKIP LOCKED;
    
    IF NOT FOUND THEN
        RAISE EXCEPTION 'No available IBANs in pool for program: %', v_program.program_code;
    END IF;
    
    -- Calculate expiry
    v_expires_at := CASE 
        WHEN p_expiry_hours IS NOT NULL THEN CURRENT_TIMESTAMP + (p_expiry_hours || ' hours')::INTERVAL
        WHEN v_program.default_expiry_hours IS NOT NULL THEN CURRENT_TIMESTAMP + (v_program.default_expiry_hours || ' hours')::INTERVAL
        ELSE NULL
    END;
    
    -- Create virtual account
    INSERT INTO virtual_accounts (
        virtual_account_number, virtual_iban, virtual_bban,
        corporate_scheme_id, customer_id, account_name,
        account_type, currency_code, status
    ) VALUES (
        v_pool_iban.virtual_account_number,
        v_pool_iban.virtual_iban,
        v_pool_iban.virtual_bban,
        v_program.scheme_id,
        v_program.customer_id,
        'Program Account: ' || p_external_reference,
        v_program.program_type,
        COALESCE(p_currency, 'AED'),
        1
    )
    RETURNING id INTO v_va_id;
    
    -- Create program account
    INSERT INTO program_accounts (
        program_id, virtual_account_id, holder_id,
        external_reference, expected_amount, currency_code,
        expires_at, metadata, status, payment_status
    ) VALUES (
        p_program_id, v_va_id, p_holder_id,
        p_external_reference, p_expected_amount, COALESCE(p_currency, 'AED'),
        v_expires_at, p_metadata, 'ACTIVE', 'PENDING'
    )
    RETURNING id INTO v_account_id;
    
    -- Update VA with program account link
    UPDATE virtual_accounts SET program_account_id = v_account_id WHERE id = v_va_id;
    
    -- Update pool
    UPDATE program_iban_pool
    SET status = 'ALLOCATED',
        allocated_to_account_id = v_account_id,
        allocated_at = CURRENT_TIMESTAMP,
        use_count = use_count + 1,
        last_used_at = CURRENT_TIMESTAMP
    WHERE id = v_pool_iban.id;
    
    RETURN QUERY SELECT v_account_id, v_va_id, v_pool_iban.virtual_iban, v_expires_at;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION allocate_program_account IS 'Allocates a new program account from the IBAN pool';


-- ============================================================================
-- PART 10: RLS POLICIES
-- ============================================================================

-- Enable RLS on new tables
ALTER TABLE va_programs ENABLE ROW LEVEL SECURITY;
ALTER TABLE va_programs FORCE ROW LEVEL SECURITY;

ALTER TABLE program_accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE program_accounts FORCE ROW LEVEL SECURITY;

ALTER TABLE program_account_holders ENABLE ROW LEVEL SECURITY;
ALTER TABLE program_account_holders FORCE ROW LEVEL SECURITY;

ALTER TABLE ihb_configurations ENABLE ROW LEVEL SECURITY;
ALTER TABLE ihb_configurations FORCE ROW LEVEL SECURITY;

ALTER TABLE ihb_entity_mappings ENABLE ROW LEVEL SECURITY;
ALTER TABLE ihb_entity_mappings FORCE ROW LEVEL SECURITY;

ALTER TABLE ic_positions ENABLE ROW LEVEL SECURITY;
ALTER TABLE ic_positions FORCE ROW LEVEL SECURITY;

-- Policies for va_programs
CREATE POLICY va_programs_tenant_isolation ON va_programs
    USING (customer_id::TEXT = current_setting('app.current_customer_id', TRUE));

-- Policies for program_accounts (via program)
CREATE POLICY program_accounts_tenant_isolation ON program_accounts
    USING (program_id IN (
        SELECT id FROM va_programs 
        WHERE customer_id::TEXT = current_setting('app.current_customer_id', TRUE)
    ));

-- Policies for ihb_configurations
CREATE POLICY ihb_config_tenant_isolation ON ihb_configurations
    USING (customer_id::TEXT = current_setting('app.current_customer_id', TRUE));

-- Policies for ihb_entity_mappings (via ihb)
CREATE POLICY ihb_entity_tenant_isolation ON ihb_entity_mappings
    USING (ihb_id IN (
        SELECT id FROM ihb_configurations 
        WHERE customer_id::TEXT = current_setting('app.current_customer_id', TRUE)
    ));

-- Policies for ic_positions (via ihb)
CREATE POLICY ic_positions_tenant_isolation ON ic_positions
    USING (ihb_id IN (
        SELECT id FROM ihb_configurations 
        WHERE customer_id::TEXT = current_setting('app.current_customer_id', TRUE)
    ));


-- ============================================================================
-- PART 11: TRIGGERS
-- ============================================================================

-- Updated_at triggers
CREATE TRIGGER tr_va_programs_updated 
    BEFORE UPDATE ON va_programs 
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER tr_program_accounts_updated 
    BEFORE UPDATE ON program_accounts 
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER tr_program_holders_updated 
    BEFORE UPDATE ON program_account_holders 
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER tr_ihb_config_updated 
    BEFORE UPDATE ON ihb_configurations 
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER tr_ihb_entity_updated 
    BEFORE UPDATE ON ihb_entity_mappings 
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER tr_ihb_alloc_updated 
    BEFORE UPDATE ON ihb_allocations 
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER tr_sweep_rules_updated 
    BEFORE UPDATE ON sweeping_rules 
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();


-- ============================================================================
-- SUMMARY OF CHANGES
-- ============================================================================
-- 
-- NEW TABLES (12):
-- - va_programs (unified program container)
-- - program_account_holders (unified holder)
-- - program_accounts (unified account)
-- - program_payments (unified payment tracking)
-- - program_iban_pool (shared IBAN pool)
-- - ihb_configurations (IHB policies)
-- - ihb_entity_mappings (scheme → IHB mapping)
-- - ihb_account_designations (VA → IHB role)
-- - ihb_allocations (budget/credit)
-- - ihb_allocation_history (allocation audit)
-- - ic_positions (real-time IC balance)
-- - ic_position_movements (IC ledger)
-- - sweeping_rules (cash concentration)
-- - sweep_executions (sweep audit)
-- - virtual_branches (cross-border)
-- - virtual_branch_accounts (branch customer accounts)
-- 
-- MODIFIED TABLES:
-- - virtual_accounts: Added program_account_id, ihb_designation_id
-- 
-- NEW VIEWS (3):
-- - vw_program_dashboard
-- - vw_ihb_entity_positions
-- - vw_ic_position_summary
-- 
-- NEW FUNCTIONS (2):
-- - record_ic_movement()
-- - allocate_program_account()
-- 
-- RLS POLICIES: Added for all new tables
-- 
-- ============================================================================
