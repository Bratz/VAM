-- ============================================================================
-- VAM System - Critical Architecture Improvements Migration
-- Version: 2.0
-- Date: November 2024
-- 
-- This migration implements three critical architectural improvements:
-- 1. Reconciliation Records - Audit trail for payment matching
-- 2. FX Rates - Multi-currency operation support
-- 3. Row-Level Security (RLS) - Multi-tenant data isolation
-- ============================================================================

-- ============================================================================
-- PART 1: FX RATES TABLE
-- Purpose: Centralized foreign exchange rate management for accurate 
--          multi-currency position calculations and transactions
-- ============================================================================

CREATE TABLE fx_rates (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    
    -- Currency Pair
    from_currency VARCHAR(3) NOT NULL,
    to_currency VARCHAR(3) NOT NULL,
    
    -- Rate Information
    rate DECIMAL(18,8) NOT NULL CHECK (rate > 0),
    inverse_rate DECIMAL(18,8) GENERATED ALWAYS AS (1.0 / rate) STORED,
    
    -- Rate Metadata
    rate_date DATE NOT NULL,
    rate_type VARCHAR(20) NOT NULL DEFAULT 'MID', -- BID, ASK, MID, SPOT, FORWARD
    rate_source VARCHAR(50), -- REUTERS, BLOOMBERG, CENTRAL_BANK, MANUAL
    
    -- Validity Window (for intraday rate changes)
    valid_from TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_until TIMESTAMP, -- NULL means currently valid
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    
    -- Constraints
    CONSTRAINT fk_fx_from_currency FOREIGN KEY (from_currency) 
        REFERENCES currencies(currency_code),
    CONSTRAINT fk_fx_to_currency FOREIGN KEY (to_currency) 
        REFERENCES currencies(currency_code),
    CONSTRAINT chk_fx_different_currencies CHECK (from_currency != to_currency),
    CONSTRAINT uq_fx_rate_unique UNIQUE (from_currency, to_currency, rate_date, rate_type, valid_from)
);

-- Indexes for FX rate lookups
CREATE INDEX idx_fx_rates_pair ON fx_rates(from_currency, to_currency);
CREATE INDEX idx_fx_rates_date ON fx_rates(rate_date DESC);
CREATE INDEX idx_fx_rates_current ON fx_rates(from_currency, to_currency, valid_until) 
    WHERE valid_until IS NULL;
CREATE INDEX idx_fx_rates_lookup ON fx_rates(from_currency, to_currency, rate_date, rate_type);

-- Function to get current FX rate
CREATE OR REPLACE FUNCTION get_fx_rate(
    p_from_currency VARCHAR(3),
    p_to_currency VARCHAR(3),
    p_rate_date DATE DEFAULT CURRENT_DATE,
    p_rate_type VARCHAR(20) DEFAULT 'MID'
) RETURNS DECIMAL(18,8) AS $$
DECLARE
    v_rate DECIMAL(18,8);
BEGIN
    -- Same currency = 1.0
    IF p_from_currency = p_to_currency THEN
        RETURN 1.0;
    END IF;
    
    -- Try direct rate
    SELECT rate INTO v_rate
    FROM fx_rates
    WHERE from_currency = p_from_currency
      AND to_currency = p_to_currency
      AND rate_date = p_rate_date
      AND rate_type = p_rate_type
      AND (valid_until IS NULL OR valid_until > CURRENT_TIMESTAMP)
    ORDER BY valid_from DESC
    LIMIT 1;
    
    IF v_rate IS NOT NULL THEN
        RETURN v_rate;
    END IF;
    
    -- Try inverse rate
    SELECT inverse_rate INTO v_rate
    FROM fx_rates
    WHERE from_currency = p_to_currency
      AND to_currency = p_from_currency
      AND rate_date = p_rate_date
      AND rate_type = p_rate_type
      AND (valid_until IS NULL OR valid_until > CURRENT_TIMESTAMP)
    ORDER BY valid_from DESC
    LIMIT 1;
    
    IF v_rate IS NOT NULL THEN
        RETURN v_rate;
    END IF;
    
    -- Try most recent rate if today's not available
    SELECT rate INTO v_rate
    FROM fx_rates
    WHERE from_currency = p_from_currency
      AND to_currency = p_to_currency
      AND rate_date <= p_rate_date
      AND rate_type = p_rate_type
    ORDER BY rate_date DESC, valid_from DESC
    LIMIT 1;
    
    RETURN v_rate; -- Returns NULL if no rate found
END;
$$ LANGUAGE plpgsql STABLE;

-- Function to convert amount between currencies
CREATE OR REPLACE FUNCTION convert_currency(
    p_amount DECIMAL(18,2),
    p_from_currency VARCHAR(3),
    p_to_currency VARCHAR(3),
    p_rate_date DATE DEFAULT CURRENT_DATE
) RETURNS DECIMAL(18,2) AS $$
DECLARE
    v_rate DECIMAL(18,8);
BEGIN
    IF p_from_currency = p_to_currency THEN
        RETURN p_amount;
    END IF;
    
    v_rate := get_fx_rate(p_from_currency, p_to_currency, p_rate_date, 'MID');
    
    IF v_rate IS NULL THEN
        RAISE EXCEPTION 'No FX rate found for % to % on %', 
            p_from_currency, p_to_currency, p_rate_date;
    END IF;
    
    RETURN ROUND(p_amount * v_rate, 2);
END;
$$ LANGUAGE plpgsql STABLE;

-- Insert base FX rates (AED as base currency for UAE bank)
INSERT INTO fx_rates (from_currency, to_currency, rate, rate_date, rate_type, rate_source) VALUES
-- USD rates (AED is pegged to USD at approximately 3.6725)
('USD', 'AED', 3.6725, CURRENT_DATE, 'MID', 'CENTRAL_BANK'),
('EUR', 'AED', 4.0150, CURRENT_DATE, 'MID', 'REUTERS'),
('GBP', 'AED', 4.6500, CURRENT_DATE, 'MID', 'REUTERS'),
('SAR', 'AED', 0.9793, CURRENT_DATE, 'MID', 'CENTRAL_BANK'),
('INR', 'AED', 0.0438, CURRENT_DATE, 'MID', 'REUTERS'),
-- Cross rates via AED
('USD', 'EUR', 0.9147, CURRENT_DATE, 'MID', 'REUTERS'),
('USD', 'GBP', 0.7898, CURRENT_DATE, 'MID', 'REUTERS'),
('EUR', 'GBP', 0.8635, CURRENT_DATE, 'MID', 'REUTERS');

-- ============================================================================
-- PART 2: RECONCILIATION RECORDS TABLE
-- Purpose: Persistent audit trail for payment-to-invoice matching
--          Supports collections reconciliation use case
-- ============================================================================

-- Reconciliation status enum type
DO $$ BEGIN
    CREATE TYPE reconciliation_match_status AS ENUM (
        'EXACT_MATCH',      -- Amount and reference match perfectly
        'PARTIAL_MATCH',    -- Reference matches, amount differs
        'OVERPAID',         -- Received more than expected
        'UNDERPAID',        -- Received less than expected
        'UNMATCHED',        -- No match found
        'MANUAL_MATCH',     -- Manually matched by user
        'DISPUTED'          -- Match is under dispute
    );
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;

-- Reconciliation method enum type
DO $$ BEGIN
    CREATE TYPE reconciliation_match_method AS ENUM (
        'AUTO_IBAN',        -- Matched by unique IBAN assignment
        'AUTO_E2E_REF',     -- Matched by end-to-end reference pattern
        'AUTO_AMOUNT',      -- Matched by exact amount
        'AUTO_COMPOSITE',   -- Matched by multiple factors
        'MANUAL',           -- Manually matched by user
        'RULE_BASED',       -- Matched by custom reconciliation rule
        'ML_SUGGESTED'      -- Machine learning suggested match
    );
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;

CREATE TABLE reconciliation_records (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    
    -- Link to Payment
    transaction_id UUID NOT NULL,
    statement_id UUID, -- Link to account_statements if applicable
    
    -- Link to Virtual Account (receiving account)
    virtual_account_id UUID NOT NULL,
    customer_id UUID NOT NULL, -- Denormalized for RLS
    
    -- External Reference (Invoice/PO/Contract)
    external_reference VARCHAR(100),
    external_reference_type VARCHAR(30), -- INVOICE, PURCHASE_ORDER, CONTRACT, LOAN_ACCOUNT
    external_system VARCHAR(50), -- ERP, BILLING, LOAN_SYSTEM
    
    -- Amount Reconciliation
    expected_amount DECIMAL(18,2),
    received_amount DECIMAL(18,2) NOT NULL,
    variance_amount DECIMAL(18,2) GENERATED ALWAYS AS (
        received_amount - COALESCE(expected_amount, received_amount)
    ) STORED,
    variance_percentage DECIMAL(5,2) GENERATED ALWAYS AS (
        CASE WHEN expected_amount IS NOT NULL AND expected_amount != 0 
             THEN ROUND(((received_amount - expected_amount) / expected_amount) * 100, 2)
             ELSE 0 
        END
    ) STORED,
    currency_code VARCHAR(3) DEFAULT 'AED',
    
    -- Match Information
    match_status VARCHAR(20) NOT NULL DEFAULT 'UNMATCHED',
    match_method VARCHAR(20),
    match_confidence INTEGER CHECK (match_confidence BETWEEN 0 AND 100),
    match_factors JSONB, -- Detailed breakdown of matching criteria
    
    -- Suggested Matches (for unmatched items)
    suggested_matches JSONB, -- Array of potential matches with confidence scores
    
    -- Manual Matching
    matched_by VARCHAR(100),
    matched_at TIMESTAMP,
    match_notes TEXT,
    
    -- Approval (for manual matches requiring review)
    requires_approval BOOLEAN DEFAULT FALSE,
    approved_by VARCHAR(100),
    approved_at TIMESTAMP,
    rejection_reason TEXT,
    
    -- Dispute Handling
    is_disputed BOOLEAN DEFAULT FALSE,
    disputed_by VARCHAR(100),
    disputed_at TIMESTAMP,
    dispute_reason TEXT,
    dispute_resolution TEXT,
    dispute_resolved_at TIMESTAMP,
    dispute_resolved_by VARCHAR(100),
    
    -- Write-off (for unreconcilable differences)
    is_written_off BOOLEAN DEFAULT FALSE,
    written_off_amount DECIMAL(18,2),
    written_off_by VARCHAR(100),
    written_off_at TIMESTAMP,
    write_off_reason TEXT,
    write_off_approval VARCHAR(100),
    
    -- Payer Information (from payment)
    payer_reference VARCHAR(100),
    payer_name VARCHAR(200),
    payer_account VARCHAR(34),
    
    -- Payment Details
    payment_date DATE,
    value_date DATE,
    payment_reference VARCHAR(50),
    e2e_reference VARCHAR(35),
    uetr VARCHAR(36),
    remittance_info TEXT,
    
    -- Audit Trail
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Constraints
    CONSTRAINT fk_recon_transaction FOREIGN KEY (transaction_id) 
        REFERENCES transactions(id),
    CONSTRAINT fk_recon_va FOREIGN KEY (virtual_account_id) 
        REFERENCES virtual_accounts(id),
    CONSTRAINT fk_recon_customer FOREIGN KEY (customer_id) 
        REFERENCES corporate_customers(id),
    CONSTRAINT uq_recon_transaction_va UNIQUE (transaction_id, virtual_account_id)
);

-- Indexes for reconciliation queries
CREATE INDEX idx_recon_customer ON reconciliation_records(customer_id);
CREATE INDEX idx_recon_va ON reconciliation_records(virtual_account_id);
CREATE INDEX idx_recon_transaction ON reconciliation_records(transaction_id);
CREATE INDEX idx_recon_status ON reconciliation_records(match_status);
CREATE INDEX idx_recon_external_ref ON reconciliation_records(external_reference);
CREATE INDEX idx_recon_payment_date ON reconciliation_records(payment_date);
CREATE INDEX idx_recon_unmatched ON reconciliation_records(customer_id, match_status) 
    WHERE match_status IN ('UNMATCHED', 'PARTIAL_MATCH');
CREATE INDEX idx_recon_disputed ON reconciliation_records(customer_id) 
    WHERE is_disputed = TRUE;
CREATE INDEX idx_recon_payer ON reconciliation_records(payer_reference);

-- Trigger to update timestamp
CREATE TRIGGER tr_reconciliation_records_updated 
    BEFORE UPDATE ON reconciliation_records 
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ============================================================================
-- PART 2B: RECONCILIATION RULES TABLE
-- Purpose: Configurable rules for auto-matching
-- ============================================================================

CREATE TABLE reconciliation_rules (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    customer_id UUID NOT NULL REFERENCES corporate_customers(id),
    scheme_id UUID REFERENCES corporate_schemes(id), -- Optional: scheme-specific rules
    
    rule_name VARCHAR(100) NOT NULL,
    rule_description TEXT,
    rule_priority INTEGER DEFAULT 100, -- Lower = higher priority
    
    -- Rule Conditions (JSONB for flexibility)
    conditions JSONB NOT NULL,
    /* Example conditions:
    {
        "amount_tolerance_percentage": 0.5,
        "amount_tolerance_absolute": 10.00,
        "reference_patterns": ["INV-*", "PO-*"],
        "payer_whitelist": ["CUST001", "CUST002"],
        "currency_match_required": true
    }
    */
    
    -- Rule Actions
    auto_match BOOLEAN DEFAULT TRUE,
    require_approval BOOLEAN DEFAULT FALSE,
    confidence_threshold INTEGER DEFAULT 90,
    
    -- Status
    is_active BOOLEAN DEFAULT TRUE,
    effective_from DATE DEFAULT CURRENT_DATE,
    effective_to DATE,
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100)
);

CREATE INDEX idx_recon_rules_customer ON reconciliation_rules(customer_id);
CREATE INDEX idx_recon_rules_active ON reconciliation_rules(customer_id, is_active) 
    WHERE is_active = TRUE;

-- ============================================================================
-- PART 2C: RECONCILIATION HISTORY/AUDIT TABLE
-- Purpose: Track all changes to reconciliation records
-- ============================================================================

CREATE TABLE reconciliation_audit (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    reconciliation_id UUID NOT NULL REFERENCES reconciliation_records(id),
    
    action VARCHAR(50) NOT NULL, -- CREATED, STATUS_CHANGED, MATCHED, DISPUTED, RESOLVED, WRITTEN_OFF
    previous_status VARCHAR(20),
    new_status VARCHAR(20),
    
    -- Change Details
    change_details JSONB,
    change_reason TEXT,
    
    -- Actor
    performed_by VARCHAR(100),
    performed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Context
    ip_address VARCHAR(50),
    user_agent VARCHAR(500)
);

CREATE INDEX idx_recon_audit_record ON reconciliation_audit(reconciliation_id);
CREATE INDEX idx_recon_audit_action ON reconciliation_audit(action);
CREATE INDEX idx_recon_audit_time ON reconciliation_audit(performed_at);

-- ============================================================================
-- PART 3: ROW-LEVEL SECURITY (RLS)
-- Purpose: Database-enforced multi-tenant data isolation
-- ============================================================================

-- Create application roles
DO $$ BEGIN
    -- Role for application service account
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'vam_app') THEN
        CREATE ROLE vam_app LOGIN;
    END IF;
    
    -- Role for read-only reporting
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'vam_readonly') THEN
        CREATE ROLE vam_readonly LOGIN;
    END IF;
    
    -- Role for admin operations (bypasses RLS)
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'vam_admin') THEN
        CREATE ROLE vam_admin LOGIN BYPASSRLS;
    END IF;
END $$;

-- Function to get current customer context
CREATE OR REPLACE FUNCTION current_customer_id() 
RETURNS UUID AS $$
BEGIN
    RETURN NULLIF(current_setting('app.current_customer_id', TRUE), '')::UUID;
EXCEPTION
    WHEN OTHERS THEN
        RETURN NULL;
END;
$$ LANGUAGE plpgsql STABLE;

-- Function to set current customer context
CREATE OR REPLACE FUNCTION set_customer_context(p_customer_id UUID) 
RETURNS VOID AS $$
BEGIN
    PERFORM set_config('app.current_customer_id', p_customer_id::TEXT, FALSE);
END;
$$ LANGUAGE plpgsql;

-- Function to clear customer context
CREATE OR REPLACE FUNCTION clear_customer_context() 
RETURNS VOID AS $$
BEGIN
    PERFORM set_config('app.current_customer_id', '', FALSE);
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- PART 3A: Enable RLS on Core Tables
-- ============================================================================

-- VIRTUAL_ACCOUNTS: Enable RLS
ALTER TABLE virtual_accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE virtual_accounts FORCE ROW LEVEL SECURITY;

CREATE POLICY virtual_accounts_tenant_isolation ON virtual_accounts
    FOR ALL
    TO vam_app
    USING (customer_id = current_customer_id() OR current_customer_id() IS NULL);

CREATE POLICY virtual_accounts_readonly ON virtual_accounts
    FOR SELECT
    TO vam_readonly
    USING (customer_id = current_customer_id() OR current_customer_id() IS NULL);

-- CORPORATE_SCHEMES: Enable RLS
ALTER TABLE corporate_schemes ENABLE ROW LEVEL SECURITY;
ALTER TABLE corporate_schemes FORCE ROW LEVEL SECURITY;

CREATE POLICY corporate_schemes_tenant_isolation ON corporate_schemes
    FOR ALL
    TO vam_app
    USING (customer_id = current_customer_id() OR current_customer_id() IS NULL);

CREATE POLICY corporate_schemes_readonly ON corporate_schemes
    FOR SELECT
    TO vam_readonly
    USING (customer_id = current_customer_id() OR current_customer_id() IS NULL);

-- BENEFICIARIES: Enable RLS
ALTER TABLE beneficiaries ENABLE ROW LEVEL SECURITY;
ALTER TABLE beneficiaries FORCE ROW LEVEL SECURITY;

CREATE POLICY beneficiaries_tenant_isolation ON beneficiaries
    FOR ALL
    TO vam_app
    USING (customer_id = current_customer_id() OR current_customer_id() IS NULL);

CREATE POLICY beneficiaries_readonly ON beneficiaries
    FOR SELECT
    TO vam_readonly
    USING (customer_id = current_customer_id() OR current_customer_id() IS NULL);

-- TRANSACTIONS: Enable RLS (via debtor account's customer)
ALTER TABLE transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE transactions FORCE ROW LEVEL SECURITY;

CREATE POLICY transactions_tenant_isolation ON transactions
    FOR ALL
    TO vam_app
    USING (
        debtor_account_id IN (
            SELECT id FROM virtual_accounts 
            WHERE customer_id = current_customer_id()
        )
        OR current_customer_id() IS NULL
    );

CREATE POLICY transactions_readonly ON transactions
    FOR SELECT
    TO vam_readonly
    USING (
        debtor_account_id IN (
            SELECT id FROM virtual_accounts 
            WHERE customer_id = current_customer_id()
        )
        OR current_customer_id() IS NULL
    );

-- ACCOUNT_STATEMENTS: Enable RLS
ALTER TABLE account_statements ENABLE ROW LEVEL SECURITY;
ALTER TABLE account_statements FORCE ROW LEVEL SECURITY;

CREATE POLICY account_statements_tenant_isolation ON account_statements
    FOR ALL
    TO vam_app
    USING (
        virtual_account_id IN (
            SELECT id FROM virtual_accounts 
            WHERE customer_id = current_customer_id()
        )
        OR current_customer_id() IS NULL
    );

-- ESCROW_CONTRACTS: Enable RLS
ALTER TABLE escrow_contracts ENABLE ROW LEVEL SECURITY;
ALTER TABLE escrow_contracts FORCE ROW LEVEL SECURITY;

CREATE POLICY escrow_contracts_tenant_isolation ON escrow_contracts
    FOR ALL
    TO vam_app
    USING (seller_id = current_customer_id() OR current_customer_id() IS NULL);

-- WALLET_PROGRAMS: Enable RLS
ALTER TABLE wallet_programs ENABLE ROW LEVEL SECURITY;
ALTER TABLE wallet_programs FORCE ROW LEVEL SECURITY;

CREATE POLICY wallet_programs_tenant_isolation ON wallet_programs
    FOR ALL
    TO vam_app
    USING (operator_id = current_customer_id() OR current_customer_id() IS NULL);

-- WALLET_ACCOUNTS: Enable RLS (via program's operator)
ALTER TABLE wallet_accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE wallet_accounts FORCE ROW LEVEL SECURITY;

CREATE POLICY wallet_accounts_tenant_isolation ON wallet_accounts
    FOR ALL
    TO vam_app
    USING (
        program_id IN (
            SELECT id FROM wallet_programs 
            WHERE operator_id = current_customer_id()
        )
        OR current_customer_id() IS NULL
    );

-- WHITELISTED_ACCOUNTS: Enable RLS
ALTER TABLE whitelisted_accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE whitelisted_accounts FORCE ROW LEVEL SECURITY;

CREATE POLICY whitelisted_accounts_tenant_isolation ON whitelisted_accounts
    FOR ALL
    TO vam_app
    USING (
        virtual_account_id IN (
            SELECT id FROM virtual_accounts 
            WHERE customer_id = current_customer_id()
        )
        OR current_customer_id() IS NULL
    );

-- RECONCILIATION_RECORDS: Enable RLS
ALTER TABLE reconciliation_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE reconciliation_records FORCE ROW LEVEL SECURITY;

CREATE POLICY reconciliation_records_tenant_isolation ON reconciliation_records
    FOR ALL
    TO vam_app
    USING (customer_id = current_customer_id() OR current_customer_id() IS NULL);

-- RECONCILIATION_RULES: Enable RLS
ALTER TABLE reconciliation_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE reconciliation_rules FORCE ROW LEVEL SECURITY;

CREATE POLICY reconciliation_rules_tenant_isolation ON reconciliation_rules
    FOR ALL
    TO vam_app
    USING (customer_id = current_customer_id() OR current_customer_id() IS NULL);

-- ============================================================================
-- PART 3B: Grant Permissions to Roles
-- ============================================================================

-- Grant permissions to vam_app
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO vam_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO vam_app;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO vam_app;

-- Grant permissions to vam_readonly
GRANT SELECT ON ALL TABLES IN SCHEMA public TO vam_readonly;

-- Grant permissions to vam_admin (full access, bypasses RLS)
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO vam_admin;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO vam_admin;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO vam_admin;

-- ============================================================================
-- PART 4: VIEWS FOR RECONCILIATION
-- ============================================================================

-- Reconciliation Dashboard View
CREATE OR REPLACE VIEW vw_reconciliation_dashboard AS
SELECT 
    rr.customer_id,
    cc.customer_name,
    DATE_TRUNC('day', rr.payment_date) as recon_date,
    COUNT(*) as total_records,
    COUNT(*) FILTER (WHERE rr.match_status = 'EXACT_MATCH') as exact_matches,
    COUNT(*) FILTER (WHERE rr.match_status = 'PARTIAL_MATCH') as partial_matches,
    COUNT(*) FILTER (WHERE rr.match_status = 'UNMATCHED') as unmatched,
    COUNT(*) FILTER (WHERE rr.match_status IN ('OVERPAID', 'UNDERPAID')) as variance_items,
    COUNT(*) FILTER (WHERE rr.is_disputed = TRUE) as disputed,
    SUM(rr.received_amount) as total_received,
    SUM(CASE WHEN rr.match_status = 'EXACT_MATCH' THEN rr.received_amount ELSE 0 END) as matched_amount,
    SUM(CASE WHEN rr.match_status = 'UNMATCHED' THEN rr.received_amount ELSE 0 END) as unmatched_amount,
    ROUND(
        COUNT(*) FILTER (WHERE rr.match_status = 'EXACT_MATCH')::DECIMAL / 
        NULLIF(COUNT(*), 0) * 100, 2
    ) as match_rate_percentage
FROM reconciliation_records rr
JOIN corporate_customers cc ON rr.customer_id = cc.id
GROUP BY rr.customer_id, cc.customer_name, DATE_TRUNC('day', rr.payment_date);

-- Unmatched Items View
CREATE OR REPLACE VIEW vw_unmatched_payments AS
SELECT 
    rr.id as reconciliation_id,
    rr.customer_id,
    va.virtual_iban,
    va.account_name,
    va.account_reference1 as expected_payer,
    rr.payer_name as actual_payer,
    rr.received_amount,
    rr.currency_code,
    rr.payment_date,
    rr.e2e_reference,
    rr.remittance_info,
    rr.suggested_matches,
    rr.created_at as received_at,
    CURRENT_DATE - rr.payment_date as days_unmatched
FROM reconciliation_records rr
JOIN virtual_accounts va ON rr.virtual_account_id = va.id
WHERE rr.match_status = 'UNMATCHED'
  AND rr.is_disputed = FALSE
  AND rr.is_written_off = FALSE
ORDER BY rr.payment_date ASC;

-- FX Rates View (Current Rates)
CREATE OR REPLACE VIEW vw_current_fx_rates AS
SELECT DISTINCT ON (from_currency, to_currency)
    from_currency,
    to_currency,
    rate,
    inverse_rate,
    rate_date,
    rate_type,
    rate_source,
    valid_from
FROM fx_rates
WHERE valid_until IS NULL OR valid_until > CURRENT_TIMESTAMP
ORDER BY from_currency, to_currency, rate_date DESC, valid_from DESC;

-- ============================================================================
-- PART 5: HELPER FUNCTIONS FOR RECONCILIATION
-- ============================================================================

-- Function to create reconciliation record from transaction
CREATE OR REPLACE FUNCTION create_reconciliation_record(
    p_transaction_id UUID,
    p_virtual_account_id UUID
) RETURNS UUID AS $$
DECLARE
    v_txn transactions%ROWTYPE;
    v_va virtual_accounts%ROWTYPE;
    v_recon_id UUID;
    v_match_status VARCHAR(20);
    v_match_method VARCHAR(20);
    v_confidence INTEGER;
    v_expected_amount DECIMAL(18,2);
BEGIN
    -- Get transaction details
    SELECT * INTO v_txn FROM transactions WHERE id = p_transaction_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Transaction not found: %', p_transaction_id;
    END IF;
    
    -- Get virtual account details
    SELECT * INTO v_va FROM virtual_accounts WHERE id = p_virtual_account_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Virtual account not found: %', p_virtual_account_id;
    END IF;
    
    -- Determine match status based on account reference
    IF v_va.account_reference1 IS NOT NULL THEN
        -- Account has a payer/invoice reference assigned
        v_match_status := 'EXACT_MATCH';
        v_match_method := 'AUTO_IBAN';
        v_confidence := 100;
        
        -- Try to parse expected amount from account_reference2
        BEGIN
            v_expected_amount := v_va.account_reference2::DECIMAL(18,2);
        EXCEPTION WHEN OTHERS THEN
            v_expected_amount := NULL;
        END;
        
        -- Check for variance
        IF v_expected_amount IS NOT NULL THEN
            IF v_txn.instructed_amount > v_expected_amount THEN
                v_match_status := 'OVERPAID';
            ELSIF v_txn.instructed_amount < v_expected_amount THEN
                v_match_status := 'UNDERPAID';
            END IF;
        END IF;
    ELSE
        -- No pre-assigned reference, try pattern matching
        v_match_status := 'UNMATCHED';
        v_match_method := NULL;
        v_confidence := 0;
        
        -- Check E2E reference for invoice pattern
        IF v_txn.e2e_reference ~ '^INV[-_]?\d+' THEN
            v_match_status := 'PARTIAL_MATCH';
            v_match_method := 'AUTO_E2E_REF';
            v_confidence := 75;
        END IF;
    END IF;
    
    -- Insert reconciliation record
    INSERT INTO reconciliation_records (
        transaction_id,
        virtual_account_id,
        customer_id,
        external_reference,
        expected_amount,
        received_amount,
        currency_code,
        match_status,
        match_method,
        match_confidence,
        payer_name,
        payer_account,
        payment_date,
        value_date,
        payment_reference,
        e2e_reference,
        uetr,
        remittance_info
    ) VALUES (
        p_transaction_id,
        p_virtual_account_id,
        v_va.customer_id,
        v_va.account_reference1,
        v_expected_amount,
        v_txn.instructed_amount,
        v_txn.instructed_currency,
        v_match_status,
        v_match_method,
        v_confidence,
        v_txn.beneficiary_name,
        v_txn.beneficiary_account,
        v_txn.transaction_date,
        v_txn.value_date,
        v_txn.transaction_reference,
        v_txn.e2e_reference,
        v_txn.uetr,
        v_txn.remittance_info
    )
    RETURNING id INTO v_recon_id;
    
    -- Create audit record
    INSERT INTO reconciliation_audit (
        reconciliation_id,
        action,
        new_status,
        change_details,
        performed_by
    ) VALUES (
        v_recon_id,
        'CREATED',
        v_match_status,
        jsonb_build_object(
            'match_method', v_match_method,
            'confidence', v_confidence
        ),
        'SYSTEM'
    );
    
    RETURN v_recon_id;
END;
$$ LANGUAGE plpgsql;

-- Function to manually match a reconciliation record
CREATE OR REPLACE FUNCTION manual_match_reconciliation(
    p_reconciliation_id UUID,
    p_external_reference VARCHAR(100),
    p_matched_by VARCHAR(100),
    p_match_notes TEXT DEFAULT NULL
) RETURNS BOOLEAN AS $$
DECLARE
    v_old_status VARCHAR(20);
BEGIN
    -- Get current status
    SELECT match_status INTO v_old_status
    FROM reconciliation_records
    WHERE id = p_reconciliation_id;
    
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Reconciliation record not found: %', p_reconciliation_id;
    END IF;
    
    -- Update record
    UPDATE reconciliation_records
    SET 
        match_status = 'MANUAL_MATCH',
        match_method = 'MANUAL',
        match_confidence = 100,
        external_reference = p_external_reference,
        matched_by = p_matched_by,
        matched_at = CURRENT_TIMESTAMP,
        match_notes = p_match_notes,
        updated_at = CURRENT_TIMESTAMP
    WHERE id = p_reconciliation_id;
    
    -- Create audit record
    INSERT INTO reconciliation_audit (
        reconciliation_id,
        action,
        previous_status,
        new_status,
        change_details,
        change_reason,
        performed_by
    ) VALUES (
        p_reconciliation_id,
        'MATCHED',
        v_old_status,
        'MANUAL_MATCH',
        jsonb_build_object('external_reference', p_external_reference),
        p_match_notes,
        p_matched_by
    );
    
    RETURN TRUE;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- PART 6: ADD account_purpose TO VIRTUAL_ACCOUNTS
-- Purpose: Semantic clarity for account purpose
-- ============================================================================

-- Add account_purpose column
ALTER TABLE virtual_accounts 
ADD COLUMN IF NOT EXISTS account_purpose VARCHAR(30) DEFAULT 'GENERAL';

-- Add comment for documentation
COMMENT ON COLUMN virtual_accounts.account_purpose IS 
'Semantic purpose of the account: GENERAL, COLLECTIONS_PAYER, COLLECTIONS_INVOICE, PAYABLES_VENDOR, PAYABLES_CATEGORY, TREASURY_POOLING, ESCROW, WALLET';

-- Create index for filtering by purpose
CREATE INDEX IF NOT EXISTS idx_va_purpose ON virtual_accounts(account_purpose);

-- ============================================================================
-- PART 7: PERFORMANCE OPTIMIZATIONS
-- ============================================================================

-- Partial indexes for common queries
CREATE INDEX IF NOT EXISTS idx_va_active ON virtual_accounts(customer_id) 
    WHERE status = 1;

CREATE INDEX IF NOT EXISTS idx_txn_pending ON transactions(debtor_account_id) 
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_escrow_active ON escrow_contracts(seller_id) 
    WHERE status NOT IN ('COMPLETED', 'CANCELLED');

-- Covering index for balance queries
CREATE INDEX IF NOT EXISTS idx_va_balance_lookup ON virtual_accounts(
    customer_id, 
    currency_code, 
    status
) INCLUDE (current_balance, available_balance, fund_hold);

-- ============================================================================
-- MIGRATION COMPLETE
-- ============================================================================

-- Log migration completion
DO $$
BEGIN
    RAISE NOTICE 'Migration V2 completed successfully at %', CURRENT_TIMESTAMP;
    RAISE NOTICE 'Added: fx_rates table with % rate functions', 2;
    RAISE NOTICE 'Added: reconciliation_records, reconciliation_rules, reconciliation_audit tables';
    RAISE NOTICE 'Enabled: Row-Level Security on % tables', 10;
    RAISE NOTICE 'Added: account_purpose column to virtual_accounts';
END $$;
