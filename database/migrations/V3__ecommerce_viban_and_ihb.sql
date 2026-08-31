-- ============================================================================
-- VAM System - Virtual IBAN E-commerce & In-House Bank Migration
-- Version: 3.0
-- Date: November 2024
-- 
-- This migration implements two major use cases:
-- 1. Virtual IBAN for E-commerce - High-volume VA per order with auto-closure
-- 2. In-House Bank (IHB) - Internal banking for large corporates
-- ============================================================================

-- ============================================================================
-- PART 1: VIRTUAL IBAN FOR E-COMMERCE
-- Purpose: Enable merchants to create unique IBANs per order/transaction
--          with automatic payment matching, closure, and webhook notifications
-- ============================================================================

-- -----------------------------------------------------------------------------
-- 1.1 E-COMMERCE MERCHANT CONFIGURATION
-- -----------------------------------------------------------------------------

CREATE TABLE ecommerce_merchants (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    customer_id UUID NOT NULL REFERENCES corporate_customers(id),
    scheme_id UUID NOT NULL REFERENCES corporate_schemes(id),
    
    -- Merchant Identity
    merchant_code VARCHAR(20) NOT NULL UNIQUE,
    merchant_name VARCHAR(200) NOT NULL,
    merchant_category_code VARCHAR(10), -- MCC
    website_url VARCHAR(500),
    
    -- IBAN Generation Config
    iban_prefix VARCHAR(10), -- Custom prefix for merchant's IBANs
    iban_pool_size INTEGER DEFAULT 1000, -- Pre-generated IBAN pool
    iban_generation_strategy VARCHAR(20) DEFAULT 'SEQUENTIAL', -- SEQUENTIAL, RANDOM, HASH
    
    -- Auto-closure Config
    auto_close_on_payment BOOLEAN DEFAULT TRUE,
    auto_close_on_exact_amount BOOLEAN DEFAULT TRUE,
    auto_close_delay_minutes INTEGER DEFAULT 5, -- Delay before auto-close
    allow_overpayment BOOLEAN DEFAULT FALSE,
    allow_partial_payment BOOLEAN DEFAULT FALSE,
    max_payment_attempts INTEGER DEFAULT 3,
    
    -- Expiry Config
    default_expiry_hours INTEGER DEFAULT 24,
    max_expiry_hours INTEGER DEFAULT 168, -- 7 days
    
    -- Webhook Config
    webhook_url VARCHAR(500),
    webhook_secret VARCHAR(100),
    webhook_enabled BOOLEAN DEFAULT TRUE,
    webhook_retry_count INTEGER DEFAULT 3,
    webhook_events TEXT[], -- Array of event types to notify
    
    -- Rate Limits
    daily_iban_limit INTEGER DEFAULT 10000,
    monthly_iban_limit INTEGER DEFAULT 100000,
    concurrent_active_limit INTEGER DEFAULT 5000,
    
    -- Settlement
    settlement_account_id UUID REFERENCES virtual_accounts(id),
    auto_sweep_to_settlement BOOLEAN DEFAULT TRUE,
    settlement_frequency VARCHAR(20) DEFAULT 'REALTIME', -- REALTIME, HOURLY, DAILY
    
    -- Status
    status VARCHAR(20) DEFAULT 'ACTIVE',
    onboarded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100)
);

CREATE INDEX idx_ecom_merchant_customer ON ecommerce_merchants(customer_id);
CREATE INDEX idx_ecom_merchant_code ON ecommerce_merchants(merchant_code);
CREATE INDEX idx_ecom_merchant_status ON ecommerce_merchants(status);

-- -----------------------------------------------------------------------------
-- 1.2 ECOMMERCE VIRTUAL IBAN (Order-specific IBANs)
-- -----------------------------------------------------------------------------

CREATE TABLE ecommerce_virtual_ibans (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    merchant_id UUID NOT NULL REFERENCES ecommerce_merchants(id),
    virtual_account_id UUID NOT NULL REFERENCES virtual_accounts(id),
    
    -- Order Reference
    order_reference VARCHAR(100) NOT NULL,
    order_id VARCHAR(100), -- External order ID
    customer_reference VARCHAR(100), -- Buyer/Customer identifier
    
    -- IBAN Details (denormalized for performance)
    virtual_iban VARCHAR(34) NOT NULL,
    
    -- Expected Payment
    expected_amount DECIMAL(18,2) NOT NULL,
    currency_code VARCHAR(3) DEFAULT 'AED',
    amount_tolerance_percentage DECIMAL(5,2) DEFAULT 0, -- Allow variance
    amount_tolerance_absolute DECIMAL(18,2) DEFAULT 0,
    
    -- Received Payment
    received_amount DECIMAL(18,2) DEFAULT 0,
    received_count INTEGER DEFAULT 0,
    last_payment_at TIMESTAMP,
    
    -- Status Lifecycle: CREATED -> ACTIVE -> PAID/EXPIRED/CANCELLED
    status VARCHAR(20) DEFAULT 'CREATED',
    payment_status VARCHAR(20) DEFAULT 'PENDING', -- PENDING, PARTIAL, EXACT, OVERPAID, UNDERPAID
    
    -- Expiry
    expires_at TIMESTAMP NOT NULL,
    expired_notification_sent BOOLEAN DEFAULT FALSE,
    
    -- Auto-closure
    auto_closed BOOLEAN DEFAULT FALSE,
    closed_at TIMESTAMP,
    closure_reason VARCHAR(50), -- PAID, EXPIRED, CANCELLED, MANUAL
    
    -- Metadata (flexible JSON for merchant-specific data)
    metadata JSONB,
    /* Example metadata:
    {
        "product_name": "Premium Subscription",
        "buyer_email": "customer@example.com",
        "invoice_number": "INV-2024-001",
        "custom_field_1": "value"
    }
    */
    
    -- Webhook Status
    webhook_notified BOOLEAN DEFAULT FALSE,
    webhook_last_attempt TIMESTAMP,
    webhook_attempts INTEGER DEFAULT 0,
    webhook_last_error TEXT,
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Constraints
    CONSTRAINT uq_ecom_merchant_order UNIQUE (merchant_id, order_reference)
);

CREATE INDEX idx_ecom_iban_merchant ON ecommerce_virtual_ibans(merchant_id);
CREATE INDEX idx_ecom_iban_va ON ecommerce_virtual_ibans(virtual_account_id);
CREATE INDEX idx_ecom_iban_order ON ecommerce_virtual_ibans(order_reference);
CREATE INDEX idx_ecom_iban_status ON ecommerce_virtual_ibans(status);
CREATE INDEX idx_ecom_iban_expires ON ecommerce_virtual_ibans(expires_at) WHERE status = 'ACTIVE';
CREATE INDEX idx_ecom_iban_iban ON ecommerce_virtual_ibans(virtual_iban);
CREATE INDEX idx_ecom_iban_pending_webhook ON ecommerce_virtual_ibans(merchant_id) 
    WHERE webhook_notified = FALSE AND status IN ('PAID', 'EXPIRED');

-- -----------------------------------------------------------------------------
-- 1.3 ECOMMERCE PAYMENTS (Detailed payment tracking)
-- -----------------------------------------------------------------------------

CREATE TABLE ecommerce_payments (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    ecommerce_iban_id UUID NOT NULL REFERENCES ecommerce_virtual_ibans(id),
    transaction_id UUID REFERENCES transactions(id),
    
    -- Payment Details
    payment_reference VARCHAR(50) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    currency_code VARCHAR(3) DEFAULT 'AED',
    
    -- Payer Info
    payer_name VARCHAR(200),
    payer_account VARCHAR(34),
    payer_bank_bic VARCHAR(11),
    
    -- Payment References
    e2e_reference VARCHAR(35),
    uetr VARCHAR(36),
    remittance_info TEXT,
    
    -- Status
    status VARCHAR(20) DEFAULT 'RECEIVED', -- RECEIVED, MATCHED, REFUNDED, DISPUTED
    
    -- Matching
    match_status VARCHAR(20), -- EXACT, OVERPAID, UNDERPAID, DUPLICATE
    amount_variance DECIMAL(18,2),
    
    -- Refund (if applicable)
    refund_required BOOLEAN DEFAULT FALSE,
    refund_amount DECIMAL(18,2),
    refund_status VARCHAR(20),
    refund_reference VARCHAR(50),
    refunded_at TIMESTAMP,
    
    -- Timestamps
    received_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    value_date DATE,
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ecom_payment_iban ON ecommerce_payments(ecommerce_iban_id);
CREATE INDEX idx_ecom_payment_txn ON ecommerce_payments(transaction_id);
CREATE INDEX idx_ecom_payment_status ON ecommerce_payments(status);
CREATE INDEX idx_ecom_payment_refund ON ecommerce_payments(ecommerce_iban_id) 
    WHERE refund_required = TRUE AND refund_status IS NULL;

-- -----------------------------------------------------------------------------
-- 1.4 WEBHOOK NOTIFICATIONS
-- -----------------------------------------------------------------------------

CREATE TABLE webhook_notifications (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    
    -- Target
    merchant_id UUID NOT NULL REFERENCES ecommerce_merchants(id),
    entity_type VARCHAR(50) NOT NULL, -- ECOMMERCE_IBAN, PAYMENT, etc.
    entity_id UUID NOT NULL,
    
    -- Event
    event_type VARCHAR(50) NOT NULL, -- PAYMENT_RECEIVED, IBAN_EXPIRED, PAYMENT_MATCHED, etc.
    event_timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Payload
    payload JSONB NOT NULL,
    
    -- Delivery
    webhook_url VARCHAR(500) NOT NULL,
    status VARCHAR(20) DEFAULT 'PENDING', -- PENDING, SENT, FAILED, CANCELLED
    
    -- Retry Management
    attempt_count INTEGER DEFAULT 0,
    max_attempts INTEGER DEFAULT 3,
    next_attempt_at TIMESTAMP,
    last_attempt_at TIMESTAMP,
    
    -- Response
    last_response_code INTEGER,
    last_response_body TEXT,
    last_error TEXT,
    
    -- Success
    delivered_at TIMESTAMP,
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_webhook_merchant ON webhook_notifications(merchant_id);
CREATE INDEX idx_webhook_status ON webhook_notifications(status);
CREATE INDEX idx_webhook_pending ON webhook_notifications(next_attempt_at) 
    WHERE status = 'PENDING';
CREATE INDEX idx_webhook_entity ON webhook_notifications(entity_type, entity_id);

-- -----------------------------------------------------------------------------
-- 1.5 IBAN POOL (Pre-generated IBANs for fast allocation)
-- -----------------------------------------------------------------------------

CREATE TABLE iban_pool (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    merchant_id UUID NOT NULL REFERENCES ecommerce_merchants(id),
    
    -- Pre-generated IBAN
    virtual_iban VARCHAR(34) NOT NULL UNIQUE,
    virtual_account_number BIGINT NOT NULL UNIQUE,
    
    -- Allocation Status
    status VARCHAR(20) DEFAULT 'AVAILABLE', -- AVAILABLE, ALLOCATED, RETIRED
    allocated_to UUID REFERENCES ecommerce_virtual_ibans(id),
    allocated_at TIMESTAMP,
    
    -- Reuse tracking
    use_count INTEGER DEFAULT 0,
    last_used_at TIMESTAMP,
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_iban_pool_merchant ON iban_pool(merchant_id);
CREATE INDEX idx_iban_pool_available ON iban_pool(merchant_id, status) 
    WHERE status = 'AVAILABLE';


-- ============================================================================
-- PART 2: IN-HOUSE BANK (IHB)
-- Purpose: Enable large corporates to operate internal banking functions
--          including intercompany lending, notional pooling, and interest calc
-- ============================================================================

-- -----------------------------------------------------------------------------
-- 2.1 IN-HOUSE BANK CONFIGURATION
-- -----------------------------------------------------------------------------

CREATE TABLE in_house_banks (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    customer_id UUID NOT NULL REFERENCES corporate_customers(id),
    
    -- IHB Identity
    ihb_code VARCHAR(20) NOT NULL UNIQUE,
    ihb_name VARCHAR(200) NOT NULL,
    base_currency VARCHAR(3) DEFAULT 'AED',
    
    -- Regulatory
    regulatory_classification VARCHAR(50), -- TREASURY_CENTER, PAYMENT_FACTORY, NETTING_CENTER
    regulatory_jurisdiction VARCHAR(3),
    
    -- Pooling Configuration
    pooling_type VARCHAR(20) DEFAULT 'NOTIONAL', -- NOTIONAL, PHYSICAL, HYBRID
    interest_calculation_enabled BOOLEAN DEFAULT TRUE,
    netting_enabled BOOLEAN DEFAULT TRUE,
    
    -- Interest Configuration
    interest_calculation_basis VARCHAR(20) DEFAULT 'ACT_360', -- ACT_360, ACT_365, 30_360
    interest_frequency VARCHAR(20) DEFAULT 'MONTHLY', -- DAILY, MONTHLY, QUARTERLY
    interest_posting_day INTEGER DEFAULT 1, -- Day of month
    
    -- Limits
    max_intercompany_exposure DECIMAL(18,2),
    max_single_entity_exposure DECIMAL(18,2),
    
    -- FX Configuration
    internal_fx_enabled BOOLEAN DEFAULT TRUE,
    fx_markup_percentage DECIMAL(5,4) DEFAULT 0, -- Internal FX spread
    
    -- Status
    status VARCHAR(20) DEFAULT 'ACTIVE',
    effective_from DATE DEFAULT CURRENT_DATE,
    effective_to DATE,
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100)
);

CREATE INDEX idx_ihb_customer ON in_house_banks(customer_id);
CREATE INDEX idx_ihb_code ON in_house_banks(ihb_code);

-- -----------------------------------------------------------------------------
-- 2.2 IHB PARTICIPANTS (Subsidiaries/Entities)
-- -----------------------------------------------------------------------------

CREATE TABLE ihb_participants (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    ihb_id UUID NOT NULL REFERENCES in_house_banks(id),
    scheme_id UUID NOT NULL REFERENCES corporate_schemes(id),
    
    -- Participant Identity
    participant_code VARCHAR(20) NOT NULL,
    participant_name VARCHAR(200) NOT NULL,
    legal_entity_id VARCHAR(50), -- LEI or local registration
    country_code VARCHAR(3),
    
    -- Role
    participant_role VARCHAR(20) DEFAULT 'PARTICIPANT', -- HEADER, PARTICIPANT, EXTERNAL
    is_treasury_center BOOLEAN DEFAULT FALSE,
    
    -- Account Links
    primary_account_id UUID REFERENCES virtual_accounts(id),
    intercompany_account_id UUID REFERENCES virtual_accounts(id), -- IC receivables/payables
    
    -- Interest Rates (can override IHB defaults)
    credit_interest_rate DECIMAL(8,5), -- Rate earned on positive balance
    debit_interest_rate DECIMAL(8,5),  -- Rate charged on negative balance
    interest_rate_spread DECIMAL(8,5), -- Spread over base rate
    
    -- Limits
    credit_limit DECIMAL(18,2) DEFAULT 0,
    debit_limit DECIMAL(18,2) DEFAULT 0,
    max_intercompany_lending DECIMAL(18,2),
    max_intercompany_borrowing DECIMAL(18,2),
    
    -- Netting
    netting_group VARCHAR(50), -- For multilateral netting
    netting_priority INTEGER DEFAULT 100,
    
    -- Status
    status VARCHAR(20) DEFAULT 'ACTIVE',
    joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT uq_ihb_participant UNIQUE (ihb_id, participant_code)
);

CREATE INDEX idx_ihb_part_ihb ON ihb_participants(ihb_id);
CREATE INDEX idx_ihb_part_scheme ON ihb_participants(scheme_id);
CREATE INDEX idx_ihb_part_code ON ihb_participants(participant_code);

-- -----------------------------------------------------------------------------
-- 2.3 INTERCOMPANY LOANS
-- -----------------------------------------------------------------------------

CREATE TABLE intercompany_loans (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    ihb_id UUID NOT NULL REFERENCES in_house_banks(id),
    loan_reference VARCHAR(50) NOT NULL UNIQUE,
    
    -- Parties
    lender_id UUID NOT NULL REFERENCES ihb_participants(id),
    borrower_id UUID NOT NULL REFERENCES ihb_participants(id),
    
    -- Loan Terms
    principal_amount DECIMAL(18,2) NOT NULL,
    currency_code VARCHAR(3) DEFAULT 'AED',
    interest_rate DECIMAL(8,5) NOT NULL, -- Annual rate
    interest_type VARCHAR(20) DEFAULT 'FIXED', -- FIXED, FLOATING, SPREAD
    base_rate_reference VARCHAR(50), -- e.g., EIBOR_3M, SOFR
    spread_over_base DECIMAL(8,5),
    
    -- Calculation
    day_count_basis VARCHAR(20) DEFAULT 'ACT_360',
    compounding_frequency VARCHAR(20) DEFAULT 'NONE', -- NONE, DAILY, MONTHLY
    
    -- Schedule
    disbursement_date DATE NOT NULL,
    maturity_date DATE NOT NULL,
    repayment_frequency VARCHAR(20) DEFAULT 'BULLET', -- BULLET, MONTHLY, QUARTERLY
    next_interest_date DATE,
    next_principal_date DATE,
    
    -- Balances
    outstanding_principal DECIMAL(18,2),
    accrued_interest DECIMAL(18,2) DEFAULT 0,
    total_interest_paid DECIMAL(18,2) DEFAULT 0,
    total_principal_paid DECIMAL(18,2) DEFAULT 0,
    
    -- Status: PENDING -> ACTIVE -> MATURED/CANCELLED/WRITTEN_OFF
    status VARCHAR(20) DEFAULT 'PENDING',
    
    -- Linked Accounts
    lender_account_id UUID REFERENCES virtual_accounts(id),
    borrower_account_id UUID REFERENCES virtual_accounts(id),
    
    -- Documentation
    loan_agreement_ref VARCHAR(100),
    approved_by VARCHAR(100),
    approved_at TIMESTAMP,
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100)
);

CREATE INDEX idx_ic_loan_ihb ON intercompany_loans(ihb_id);
CREATE INDEX idx_ic_loan_lender ON intercompany_loans(lender_id);
CREATE INDEX idx_ic_loan_borrower ON intercompany_loans(borrower_id);
CREATE INDEX idx_ic_loan_status ON intercompany_loans(status);
CREATE INDEX idx_ic_loan_maturity ON intercompany_loans(maturity_date) WHERE status = 'ACTIVE';

-- -----------------------------------------------------------------------------
-- 2.4 INTERCOMPANY LOAN TRANSACTIONS
-- -----------------------------------------------------------------------------

CREATE TABLE intercompany_loan_transactions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    loan_id UUID NOT NULL REFERENCES intercompany_loans(id),
    transaction_reference VARCHAR(50) NOT NULL UNIQUE,
    
    -- Transaction Type
    transaction_type VARCHAR(20) NOT NULL, -- DISBURSEMENT, PRINCIPAL_REPAYMENT, 
                                           -- INTEREST_PAYMENT, INTEREST_ACCRUAL, 
                                           -- CAPITALIZATION, WRITE_OFF
    
    -- Amounts
    principal_amount DECIMAL(18,2) DEFAULT 0,
    interest_amount DECIMAL(18,2) DEFAULT 0,
    total_amount DECIMAL(18,2) NOT NULL,
    currency_code VARCHAR(3) DEFAULT 'AED',
    
    -- Balances After
    outstanding_principal_after DECIMAL(18,2),
    accrued_interest_after DECIMAL(18,2),
    
    -- Period (for interest calculations)
    period_from DATE,
    period_to DATE,
    days_in_period INTEGER,
    rate_applied DECIMAL(8,5),
    
    -- Status
    status VARCHAR(20) DEFAULT 'COMPLETED',
    
    -- Link to main transaction
    main_transaction_id UUID REFERENCES transactions(id),
    
    -- Timestamps
    effective_date DATE NOT NULL DEFAULT CURRENT_DATE,
    value_date DATE,
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ic_loan_txn_loan ON intercompany_loan_transactions(loan_id);
CREATE INDEX idx_ic_loan_txn_type ON intercompany_loan_transactions(transaction_type);
CREATE INDEX idx_ic_loan_txn_date ON intercompany_loan_transactions(effective_date);

-- -----------------------------------------------------------------------------
-- 2.5 NOTIONAL POOLING
-- -----------------------------------------------------------------------------

CREATE TABLE notional_pools (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    ihb_id UUID NOT NULL REFERENCES in_house_banks(id),
    
    -- Pool Identity
    pool_code VARCHAR(20) NOT NULL,
    pool_name VARCHAR(200) NOT NULL,
    pool_currency VARCHAR(3) NOT NULL, -- Base currency for the pool
    
    -- Pool Type
    pool_type VARCHAR(20) DEFAULT 'SINGLE_CURRENCY', -- SINGLE_CURRENCY, MULTI_CURRENCY
    fx_conversion_enabled BOOLEAN DEFAULT FALSE,
    
    -- Interest Configuration
    interest_optimization_enabled BOOLEAN DEFAULT TRUE,
    credit_interest_rate DECIMAL(8,5),
    debit_interest_rate DECIMAL(8,5),
    interest_tier_enabled BOOLEAN DEFAULT FALSE,
    
    -- Advantage Calculation
    advantage_sharing_method VARCHAR(20) DEFAULT 'PRO_RATA', -- PRO_RATA, FIXED, CUSTOM
    advantage_distribution_day INTEGER DEFAULT 1,
    
    -- Current Position
    total_credit_balance DECIMAL(18,2) DEFAULT 0,
    total_debit_balance DECIMAL(18,2) DEFAULT 0,
    net_pool_position DECIMAL(18,2) DEFAULT 0,
    last_position_update TIMESTAMP,
    
    -- Status
    status VARCHAR(20) DEFAULT 'ACTIVE',
    effective_from DATE DEFAULT CURRENT_DATE,
    effective_to DATE,
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT uq_notional_pool UNIQUE (ihb_id, pool_code)
);

CREATE INDEX idx_notional_pool_ihb ON notional_pools(ihb_id);
CREATE INDEX idx_notional_pool_status ON notional_pools(status);

-- -----------------------------------------------------------------------------
-- 2.6 NOTIONAL POOL MEMBERS
-- -----------------------------------------------------------------------------

CREATE TABLE notional_pool_members (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    pool_id UUID NOT NULL REFERENCES notional_pools(id),
    participant_id UUID NOT NULL REFERENCES ihb_participants(id),
    account_id UUID NOT NULL REFERENCES virtual_accounts(id),
    
    -- Member Configuration
    member_role VARCHAR(20) DEFAULT 'MEMBER', -- HEADER, MEMBER
    contribution_percentage DECIMAL(5,2), -- For advantage sharing
    
    -- Interest Override
    custom_credit_rate DECIMAL(8,5),
    custom_debit_rate DECIMAL(8,5),
    
    -- Current Position
    current_balance DECIMAL(18,2) DEFAULT 0,
    currency_code VARCHAR(3),
    balance_in_pool_currency DECIMAL(18,2) DEFAULT 0, -- FX converted
    
    -- Interest Tracking
    accrued_interest DECIMAL(18,2) DEFAULT 0,
    interest_advantage DECIMAL(18,2) DEFAULT 0, -- Benefit from pooling
    
    -- Status
    status VARCHAR(20) DEFAULT 'ACTIVE',
    joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT uq_pool_member_account UNIQUE (pool_id, account_id)
);

CREATE INDEX idx_pool_member_pool ON notional_pool_members(pool_id);
CREATE INDEX idx_pool_member_participant ON notional_pool_members(participant_id);
CREATE INDEX idx_pool_member_account ON notional_pool_members(account_id);

-- -----------------------------------------------------------------------------
-- 2.7 INTEREST ACCRUALS
-- -----------------------------------------------------------------------------

CREATE TABLE interest_accruals (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    
    -- Source
    source_type VARCHAR(20) NOT NULL, -- NOTIONAL_POOL, IC_LOAN, ACCOUNT
    source_id UUID NOT NULL,
    
    -- Account/Participant
    participant_id UUID REFERENCES ihb_participants(id),
    account_id UUID REFERENCES virtual_accounts(id),
    
    -- Accrual Period
    accrual_date DATE NOT NULL,
    period_from DATE NOT NULL,
    period_to DATE NOT NULL,
    days_in_period INTEGER NOT NULL,
    
    -- Balance
    average_balance DECIMAL(18,2) NOT NULL,
    currency_code VARCHAR(3) NOT NULL,
    
    -- Interest Calculation
    interest_rate DECIMAL(8,5) NOT NULL,
    day_count_basis VARCHAR(20) NOT NULL,
    
    -- Amounts
    gross_interest DECIMAL(18,2) NOT NULL,
    withholding_tax DECIMAL(18,2) DEFAULT 0,
    net_interest DECIMAL(18,2) NOT NULL,
    
    -- Pooling Advantage (if applicable)
    standalone_interest DECIMAL(18,2), -- What they would earn/pay alone
    pooled_interest DECIMAL(18,2),     -- What they earn/pay in pool
    interest_advantage DECIMAL(18,2),  -- Benefit from pooling
    
    -- Status
    status VARCHAR(20) DEFAULT 'ACCRUED', -- ACCRUED, POSTED, REVERSED
    posted_at TIMESTAMP,
    posting_transaction_id UUID,
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_interest_accrual_source ON interest_accruals(source_type, source_id);
CREATE INDEX idx_interest_accrual_date ON interest_accruals(accrual_date);
CREATE INDEX idx_interest_accrual_account ON interest_accruals(account_id);
CREATE INDEX idx_interest_accrual_status ON interest_accruals(status);

-- -----------------------------------------------------------------------------
-- 2.8 NETTING CYCLES
-- -----------------------------------------------------------------------------

CREATE TABLE netting_cycles (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    ihb_id UUID NOT NULL REFERENCES in_house_banks(id),
    
    -- Cycle Identity
    cycle_reference VARCHAR(50) NOT NULL UNIQUE,
    netting_date DATE NOT NULL,
    
    -- Cycle Type
    netting_type VARCHAR(20) DEFAULT 'BILATERAL', -- BILATERAL, MULTILATERAL
    netting_group VARCHAR(50),
    
    -- Participants
    participant_count INTEGER,
    
    -- Amounts
    gross_payables DECIMAL(18,2),
    gross_receivables DECIMAL(18,2),
    net_settlement_amount DECIMAL(18,2),
    netting_efficiency_percentage DECIMAL(5,2), -- % reduction vs gross
    
    -- Currency
    settlement_currency VARCHAR(3),
    
    -- Status: DRAFT -> PROPOSED -> APPROVED -> SETTLED -> CANCELLED
    status VARCHAR(20) DEFAULT 'DRAFT',
    
    -- Approval
    proposed_at TIMESTAMP,
    proposed_by VARCHAR(100),
    approved_at TIMESTAMP,
    approved_by VARCHAR(100),
    
    -- Settlement
    settled_at TIMESTAMP,
    settlement_reference VARCHAR(50),
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_netting_cycle_ihb ON netting_cycles(ihb_id);
CREATE INDEX idx_netting_cycle_date ON netting_cycles(netting_date);
CREATE INDEX idx_netting_cycle_status ON netting_cycles(status);

-- -----------------------------------------------------------------------------
-- 2.9 NETTING POSITIONS (Per participant per cycle)
-- -----------------------------------------------------------------------------

CREATE TABLE netting_positions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    cycle_id UUID NOT NULL REFERENCES netting_cycles(id),
    participant_id UUID NOT NULL REFERENCES ihb_participants(id),
    
    -- Gross Positions
    gross_payable DECIMAL(18,2) DEFAULT 0,
    gross_receivable DECIMAL(18,2) DEFAULT 0,
    
    -- Net Position
    net_position DECIMAL(18,2), -- Positive = receivable, Negative = payable
    position_type VARCHAR(10), -- NET_RECEIVER, NET_PAYER, NEUTRAL
    
    -- Currency (if multi-currency netting)
    currency_code VARCHAR(3),
    
    -- Settlement
    settlement_amount DECIMAL(18,2),
    settlement_direction VARCHAR(10), -- PAY, RECEIVE
    settlement_account_id UUID REFERENCES virtual_accounts(id),
    
    -- Status
    status VARCHAR(20) DEFAULT 'CALCULATED',
    settled_at TIMESTAMP,
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT uq_netting_position UNIQUE (cycle_id, participant_id)
);

CREATE INDEX idx_netting_pos_cycle ON netting_positions(cycle_id);
CREATE INDEX idx_netting_pos_participant ON netting_positions(participant_id);

-- -----------------------------------------------------------------------------
-- 2.10 INTERNAL FX TRANSACTIONS
-- -----------------------------------------------------------------------------

CREATE TABLE internal_fx_transactions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    ihb_id UUID NOT NULL REFERENCES in_house_banks(id),
    fx_reference VARCHAR(50) NOT NULL UNIQUE,
    
    -- Parties
    requesting_participant_id UUID NOT NULL REFERENCES ihb_participants(id),
    
    -- FX Details
    sell_currency VARCHAR(3) NOT NULL,
    sell_amount DECIMAL(18,2) NOT NULL,
    buy_currency VARCHAR(3) NOT NULL,
    buy_amount DECIMAL(18,2) NOT NULL,
    
    -- Rate
    exchange_rate DECIMAL(18,8) NOT NULL,
    market_rate DECIMAL(18,8), -- Reference market rate
    spread_applied DECIMAL(8,5),
    rate_source VARCHAR(50),
    rate_timestamp TIMESTAMP,
    
    -- Accounts
    sell_account_id UUID REFERENCES virtual_accounts(id),
    buy_account_id UUID REFERENCES virtual_accounts(id),
    
    -- Value Date
    trade_date DATE DEFAULT CURRENT_DATE,
    value_date DATE NOT NULL,
    
    -- Status
    status VARCHAR(20) DEFAULT 'PENDING', -- PENDING, CONFIRMED, SETTLED, CANCELLED
    
    -- Settlement
    settled_at TIMESTAMP,
    sell_transaction_id UUID REFERENCES transactions(id),
    buy_transaction_id UUID REFERENCES transactions(id),
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100)
);

CREATE INDEX idx_internal_fx_ihb ON internal_fx_transactions(ihb_id);
CREATE INDEX idx_internal_fx_participant ON internal_fx_transactions(requesting_participant_id);
CREATE INDEX idx_internal_fx_status ON internal_fx_transactions(status);
CREATE INDEX idx_internal_fx_value_date ON internal_fx_transactions(value_date);


-- ============================================================================
-- PART 3: EXTEND VIRTUAL ACCOUNTS FOR NEW USE CASES
-- ============================================================================

-- Add new account types and purposes
ALTER TABLE virtual_accounts 
ADD COLUMN IF NOT EXISTS ecommerce_iban_id UUID REFERENCES ecommerce_virtual_ibans(id),
ADD COLUMN IF NOT EXISTS ihb_participant_id UUID REFERENCES ihb_participants(id),
ADD COLUMN IF NOT EXISTS pool_member_id UUID REFERENCES notional_pool_members(id);

-- Update account_type to include new types
COMMENT ON COLUMN virtual_accounts.account_type IS 
'Account type: STANDARD, ESCROW, WALLET, ECOMMERCE, IHB_OPERATING, IHB_INTERCOMPANY, IHB_POOL';

-- Add index for e-commerce accounts
CREATE INDEX IF NOT EXISTS idx_va_ecommerce ON virtual_accounts(ecommerce_iban_id) 
    WHERE ecommerce_iban_id IS NOT NULL;


-- ============================================================================
-- PART 4: FUNCTIONS FOR E-COMMERCE
-- ============================================================================

-- Function to allocate IBAN from pool
CREATE OR REPLACE FUNCTION allocate_ecommerce_iban(
    p_merchant_id UUID,
    p_order_reference VARCHAR(100),
    p_expected_amount DECIMAL(18,2),
    p_currency VARCHAR(3),
    p_expiry_hours INTEGER,
    p_metadata JSONB DEFAULT NULL
) RETURNS TABLE (
    iban VARCHAR(34),
    ecommerce_iban_id UUID,
    expires_at TIMESTAMP
) AS $$
DECLARE
    v_pool_iban RECORD;
    v_merchant RECORD;
    v_va_id UUID;
    v_ecom_id UUID;
    v_expires TIMESTAMP;
BEGIN
    -- Get merchant config
    SELECT * INTO v_merchant FROM ecommerce_merchants WHERE id = p_merchant_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Merchant not found: %', p_merchant_id;
    END IF;
    
    -- Check daily limit
    IF (SELECT COUNT(*) FROM ecommerce_virtual_ibans 
        WHERE merchant_id = p_merchant_id 
        AND DATE(created_at) = CURRENT_DATE) >= v_merchant.daily_iban_limit THEN
        RAISE EXCEPTION 'Daily IBAN limit exceeded for merchant %', v_merchant.merchant_code;
    END IF;
    
    -- Allocate from pool
    SELECT * INTO v_pool_iban 
    FROM iban_pool 
    WHERE merchant_id = p_merchant_id AND status = 'AVAILABLE'
    ORDER BY id
    LIMIT 1
    FOR UPDATE SKIP LOCKED;
    
    IF NOT FOUND THEN
        RAISE EXCEPTION 'No available IBANs in pool for merchant %', v_merchant.merchant_code;
    END IF;
    
    -- Calculate expiry
    v_expires := CURRENT_TIMESTAMP + (COALESCE(p_expiry_hours, v_merchant.default_expiry_hours) || ' hours')::INTERVAL;
    
    -- Create virtual account
    INSERT INTO virtual_accounts (
        virtual_account_number, virtual_iban, corporate_scheme_id, customer_id,
        account_name, currency_code, account_type, account_purpose, status
    ) VALUES (
        v_pool_iban.virtual_account_number, 
        v_pool_iban.virtual_iban,
        v_merchant.scheme_id,
        v_merchant.customer_id,
        'ECOM-' || p_order_reference,
        p_currency,
        'ECOMMERCE',
        'ECOMMERCE_ORDER',
        1
    ) RETURNING id INTO v_va_id;
    
    -- Create e-commerce IBAN record
    INSERT INTO ecommerce_virtual_ibans (
        merchant_id, virtual_account_id, order_reference,
        virtual_iban, expected_amount, currency_code,
        expires_at, status, metadata
    ) VALUES (
        p_merchant_id, v_va_id, p_order_reference,
        v_pool_iban.virtual_iban, p_expected_amount, p_currency,
        v_expires, 'ACTIVE', p_metadata
    ) RETURNING id INTO v_ecom_id;
    
    -- Update pool
    UPDATE iban_pool 
    SET status = 'ALLOCATED', allocated_to = v_ecom_id, allocated_at = CURRENT_TIMESTAMP,
        use_count = use_count + 1, last_used_at = CURRENT_TIMESTAMP
    WHERE id = v_pool_iban.id;
    
    -- Update VA with ecommerce link
    UPDATE virtual_accounts SET ecommerce_iban_id = v_ecom_id WHERE id = v_va_id;
    
    RETURN QUERY SELECT v_pool_iban.virtual_iban, v_ecom_id, v_expires;
END;
$$ LANGUAGE plpgsql;

-- Function to process e-commerce payment
CREATE OR REPLACE FUNCTION process_ecommerce_payment(
    p_iban VARCHAR(34),
    p_amount DECIMAL(18,2),
    p_currency VARCHAR(3),
    p_payer_name VARCHAR(200),
    p_payer_account VARCHAR(34),
    p_reference VARCHAR(50)
) RETURNS TABLE (
    status VARCHAR(20),
    match_status VARCHAR(20),
    variance DECIMAL(18,2),
    auto_closed BOOLEAN
) AS $$
DECLARE
    v_ecom RECORD;
    v_merchant RECORD;
    v_payment_id UUID;
    v_match_status VARCHAR(20);
    v_variance DECIMAL(18,2);
    v_should_close BOOLEAN := FALSE;
BEGIN
    -- Find e-commerce IBAN
    SELECT ei.*, em.auto_close_on_payment, em.auto_close_on_exact_amount,
           em.allow_overpayment, em.amount_tolerance_percentage, em.amount_tolerance_absolute
    INTO v_ecom
    FROM ecommerce_virtual_ibans ei
    JOIN ecommerce_merchants em ON ei.merchant_id = em.id
    WHERE ei.virtual_iban = p_iban AND ei.status = 'ACTIVE';
    
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Active e-commerce IBAN not found: %', p_iban;
    END IF;
    
    -- Calculate variance
    v_variance := p_amount - v_ecom.expected_amount;
    
    -- Determine match status
    IF ABS(v_variance) <= GREATEST(
        v_ecom.expected_amount * COALESCE(v_ecom.amount_tolerance_percentage, 0) / 100,
        COALESCE(v_ecom.amount_tolerance_absolute, 0)
    ) THEN
        v_match_status := 'EXACT';
        v_should_close := v_ecom.auto_close_on_exact_amount;
    ELSIF v_variance > 0 THEN
        v_match_status := 'OVERPAID';
        v_should_close := v_ecom.auto_close_on_payment AND v_ecom.allow_overpayment;
    ELSE
        v_match_status := 'UNDERPAID';
        v_should_close := FALSE; -- Don't auto-close underpayments
    END IF;
    
    -- Record payment
    INSERT INTO ecommerce_payments (
        ecommerce_iban_id, payment_reference, amount, currency_code,
        payer_name, payer_account, match_status, amount_variance, status
    ) VALUES (
        v_ecom.id, p_reference, p_amount, p_currency,
        p_payer_name, p_payer_account, v_match_status, v_variance, 'MATCHED'
    ) RETURNING id INTO v_payment_id;
    
    -- Update e-commerce IBAN
    UPDATE ecommerce_virtual_ibans
    SET received_amount = received_amount + p_amount,
        received_count = received_count + 1,
        last_payment_at = CURRENT_TIMESTAMP,
        payment_status = v_match_status,
        status = CASE WHEN v_should_close THEN 'PAID' ELSE status END,
        auto_closed = v_should_close,
        closed_at = CASE WHEN v_should_close THEN CURRENT_TIMESTAMP ELSE NULL END,
        closure_reason = CASE WHEN v_should_close THEN 'PAID' ELSE NULL END,
        updated_at = CURRENT_TIMESTAMP
    WHERE id = v_ecom.id;
    
    -- Close VA if needed
    IF v_should_close THEN
        UPDATE virtual_accounts SET status = 0, closed_on = CURRENT_DATE
        WHERE id = v_ecom.virtual_account_id;
    END IF;
    
    RETURN QUERY SELECT 'SUCCESS'::VARCHAR(20), v_match_status, v_variance, v_should_close;
END;
$$ LANGUAGE plpgsql;


-- ============================================================================
-- PART 5: FUNCTIONS FOR IN-HOUSE BANK
-- ============================================================================

-- Function to calculate interest on intercompany loan
CREATE OR REPLACE FUNCTION calculate_loan_interest(
    p_loan_id UUID,
    p_calculation_date DATE DEFAULT CURRENT_DATE
) RETURNS TABLE (
    interest_amount DECIMAL(18,2),
    days INTEGER,
    rate DECIMAL(8,5),
    principal DECIMAL(18,2)
) AS $$
DECLARE
    v_loan RECORD;
    v_last_calc_date DATE;
    v_days INTEGER;
    v_interest DECIMAL(18,2);
BEGIN
    -- Get loan details
    SELECT * INTO v_loan FROM intercompany_loans WHERE id = p_loan_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Loan not found: %', p_loan_id;
    END IF;
    
    -- Get last interest calculation date
    SELECT COALESCE(MAX(period_to), v_loan.disbursement_date) INTO v_last_calc_date
    FROM intercompany_loan_transactions
    WHERE loan_id = p_loan_id AND transaction_type = 'INTEREST_ACCRUAL';
    
    -- Calculate days
    v_days := p_calculation_date - v_last_calc_date;
    
    IF v_days <= 0 THEN
        RETURN QUERY SELECT 0::DECIMAL(18,2), 0, v_loan.interest_rate, v_loan.outstanding_principal;
        RETURN;
    END IF;
    
    -- Calculate interest based on day count basis
    CASE v_loan.day_count_basis
        WHEN 'ACT_360' THEN
            v_interest := v_loan.outstanding_principal * v_loan.interest_rate / 100 * v_days / 360;
        WHEN 'ACT_365' THEN
            v_interest := v_loan.outstanding_principal * v_loan.interest_rate / 100 * v_days / 365;
        WHEN '30_360' THEN
            v_interest := v_loan.outstanding_principal * v_loan.interest_rate / 100 * v_days / 360;
        ELSE
            v_interest := v_loan.outstanding_principal * v_loan.interest_rate / 100 * v_days / 360;
    END CASE;
    
    RETURN QUERY SELECT ROUND(v_interest, 2), v_days, v_loan.interest_rate, v_loan.outstanding_principal;
END;
$$ LANGUAGE plpgsql;

-- Function to calculate notional pool position
CREATE OR REPLACE FUNCTION calculate_pool_position(
    p_pool_id UUID
) RETURNS TABLE (
    total_credit DECIMAL(18,2),
    total_debit DECIMAL(18,2),
    net_position DECIMAL(18,2),
    member_count INTEGER,
    interest_advantage DECIMAL(18,2)
) AS $$
DECLARE
    v_pool RECORD;
    v_total_credit DECIMAL(18,2) := 0;
    v_total_debit DECIMAL(18,2) := 0;
    v_standalone_interest DECIMAL(18,2) := 0;
    v_pooled_interest DECIMAL(18,2) := 0;
    v_member_count INTEGER := 0;
BEGIN
    -- Get pool config
    SELECT * INTO v_pool FROM notional_pools WHERE id = p_pool_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Pool not found: %', p_pool_id;
    END IF;
    
    -- Calculate positions from members
    SELECT 
        COUNT(*),
        COALESCE(SUM(CASE WHEN balance_in_pool_currency >= 0 THEN balance_in_pool_currency ELSE 0 END), 0),
        COALESCE(SUM(CASE WHEN balance_in_pool_currency < 0 THEN ABS(balance_in_pool_currency) ELSE 0 END), 0)
    INTO v_member_count, v_total_credit, v_total_debit
    FROM notional_pool_members
    WHERE pool_id = p_pool_id AND status = 'ACTIVE';
    
    -- Calculate standalone interest (what each would pay/earn individually)
    SELECT COALESCE(SUM(
        CASE 
            WHEN npm.balance_in_pool_currency >= 0 
            THEN npm.balance_in_pool_currency * COALESCE(npm.custom_credit_rate, v_pool.credit_interest_rate) / 100 / 360
            ELSE ABS(npm.balance_in_pool_currency) * COALESCE(npm.custom_debit_rate, v_pool.debit_interest_rate) / 100 / 360
        END
    ), 0) INTO v_standalone_interest
    FROM notional_pool_members npm
    WHERE npm.pool_id = p_pool_id AND npm.status = 'ACTIVE';
    
    -- Calculate pooled interest (on net position)
    IF (v_total_credit - v_total_debit) >= 0 THEN
        v_pooled_interest := (v_total_credit - v_total_debit) * v_pool.credit_interest_rate / 100 / 360;
    ELSE
        v_pooled_interest := ABS(v_total_credit - v_total_debit) * v_pool.debit_interest_rate / 100 / 360;
    END IF;
    
    -- Update pool
    UPDATE notional_pools
    SET total_credit_balance = v_total_credit,
        total_debit_balance = v_total_debit,
        net_pool_position = v_total_credit - v_total_debit,
        last_position_update = CURRENT_TIMESTAMP
    WHERE id = p_pool_id;
    
    RETURN QUERY SELECT 
        v_total_credit, 
        v_total_debit, 
        v_total_credit - v_total_debit,
        v_member_count,
        ABS(v_standalone_interest - v_pooled_interest);
END;
$$ LANGUAGE plpgsql;


-- ============================================================================
-- PART 6: VIEWS
-- ============================================================================

-- E-commerce Dashboard View
CREATE OR REPLACE VIEW vw_ecommerce_dashboard AS
SELECT 
    em.id as merchant_id,
    em.merchant_code,
    em.merchant_name,
    DATE(ei.created_at) as date,
    COUNT(*) as total_ibans_created,
    COUNT(*) FILTER (WHERE ei.status = 'PAID') as paid_count,
    COUNT(*) FILTER (WHERE ei.status = 'EXPIRED') as expired_count,
    COUNT(*) FILTER (WHERE ei.status = 'ACTIVE') as active_count,
    SUM(ei.expected_amount) as total_expected,
    SUM(ei.received_amount) as total_received,
    ROUND(COUNT(*) FILTER (WHERE ei.status = 'PAID')::DECIMAL / NULLIF(COUNT(*), 0) * 100, 2) as conversion_rate
FROM ecommerce_merchants em
LEFT JOIN ecommerce_virtual_ibans ei ON em.id = ei.merchant_id
GROUP BY em.id, em.merchant_code, em.merchant_name, DATE(ei.created_at);

-- IHB Position View
CREATE OR REPLACE VIEW vw_ihb_positions AS
SELECT 
    ihb.id as ihb_id,
    ihb.ihb_code,
    ihb.ihb_name,
    p.id as participant_id,
    p.participant_code,
    p.participant_name,
    va.virtual_iban,
    va.currency_code,
    va.current_balance,
    p.credit_limit,
    p.debit_limit,
    CASE 
        WHEN va.current_balance < 0 AND ABS(va.current_balance) > p.debit_limit THEN 'LIMIT_BREACH'
        WHEN va.current_balance < 0 THEN 'DEBIT'
        ELSE 'CREDIT'
    END as position_status
FROM in_house_banks ihb
JOIN ihb_participants p ON ihb.id = p.ihb_id
LEFT JOIN virtual_accounts va ON p.primary_account_id = va.id
WHERE p.status = 'ACTIVE';

-- Intercompany Loan Summary View
CREATE OR REPLACE VIEW vw_intercompany_loans AS
SELECT 
    l.id as loan_id,
    l.loan_reference,
    ihb.ihb_name,
    lender.participant_name as lender_name,
    borrower.participant_name as borrower_name,
    l.principal_amount,
    l.outstanding_principal,
    l.accrued_interest,
    l.interest_rate,
    l.currency_code,
    l.disbursement_date,
    l.maturity_date,
    l.status,
    l.maturity_date - CURRENT_DATE as days_to_maturity
FROM intercompany_loans l
JOIN in_house_banks ihb ON l.ihb_id = ihb.id
JOIN ihb_participants lender ON l.lender_id = lender.id
JOIN ihb_participants borrower ON l.borrower_id = borrower.id;


-- ============================================================================
-- PART 7: ROW-LEVEL SECURITY FOR NEW TABLES
-- ============================================================================

-- E-commerce tables RLS
ALTER TABLE ecommerce_merchants ENABLE ROW LEVEL SECURITY;
ALTER TABLE ecommerce_merchants FORCE ROW LEVEL SECURITY;
CREATE POLICY ecommerce_merchants_tenant ON ecommerce_merchants
    FOR ALL TO vam_app
    USING (customer_id = current_customer_id() OR current_customer_id() IS NULL);

ALTER TABLE ecommerce_virtual_ibans ENABLE ROW LEVEL SECURITY;
ALTER TABLE ecommerce_virtual_ibans FORCE ROW LEVEL SECURITY;
CREATE POLICY ecommerce_ibans_tenant ON ecommerce_virtual_ibans
    FOR ALL TO vam_app
    USING (merchant_id IN (SELECT id FROM ecommerce_merchants WHERE customer_id = current_customer_id())
           OR current_customer_id() IS NULL);

-- IHB tables RLS
ALTER TABLE in_house_banks ENABLE ROW LEVEL SECURITY;
ALTER TABLE in_house_banks FORCE ROW LEVEL SECURITY;
CREATE POLICY ihb_tenant ON in_house_banks
    FOR ALL TO vam_app
    USING (customer_id = current_customer_id() OR current_customer_id() IS NULL);

ALTER TABLE ihb_participants ENABLE ROW LEVEL SECURITY;
ALTER TABLE ihb_participants FORCE ROW LEVEL SECURITY;
CREATE POLICY ihb_participants_tenant ON ihb_participants
    FOR ALL TO vam_app
    USING (ihb_id IN (SELECT id FROM in_house_banks WHERE customer_id = current_customer_id())
           OR current_customer_id() IS NULL);

ALTER TABLE intercompany_loans ENABLE ROW LEVEL SECURITY;
ALTER TABLE intercompany_loans FORCE ROW LEVEL SECURITY;
CREATE POLICY ic_loans_tenant ON intercompany_loans
    FOR ALL TO vam_app
    USING (ihb_id IN (SELECT id FROM in_house_banks WHERE customer_id = current_customer_id())
           OR current_customer_id() IS NULL);

ALTER TABLE notional_pools ENABLE ROW LEVEL SECURITY;
ALTER TABLE notional_pools FORCE ROW LEVEL SECURITY;
CREATE POLICY notional_pools_tenant ON notional_pools
    FOR ALL TO vam_app
    USING (ihb_id IN (SELECT id FROM in_house_banks WHERE customer_id = current_customer_id())
           OR current_customer_id() IS NULL);


-- ============================================================================
-- MIGRATION COMPLETE
-- ============================================================================

DO $$
BEGIN
    RAISE NOTICE 'Migration V3 completed successfully at %', CURRENT_TIMESTAMP;
    RAISE NOTICE 'Added E-commerce Virtual IBAN tables: ecommerce_merchants, ecommerce_virtual_ibans, ecommerce_payments, webhook_notifications, iban_pool';
    RAISE NOTICE 'Added In-House Bank tables: in_house_banks, ihb_participants, intercompany_loans, notional_pools, netting_cycles, internal_fx_transactions';
    RAISE NOTICE 'Added functions: allocate_ecommerce_iban, process_ecommerce_payment, calculate_loan_interest, calculate_pool_position';
END $$;
