# Virtual Account Management (VAM) System - Architectural Analysis

**Analysis Date:** November 2024  
**Analyst Role:** Solution Architect  
**Scope:** Data Model, Entity Relationships, Use Case Support, Extensibility

---

## Executive Summary

This document provides a comprehensive architectural analysis of the VAM system, examining how data is organized and related across implemented use cases, identifying strengths and gaps, and recommending improvements for future extensibility.

---

## Part 1: Current Data Model Analysis

### 1.1 Entity Inventory

The system comprises **26 core tables** organized into logical domains:

| Domain | Tables | Purpose |
|--------|--------|---------|
| Customer Management | `corporate_customers`, `users`, `roles`, `permissions` | Corporate onboarding & access |
| Account Structure | `corporate_schemes`, `virtual_accounts`, `account_level_definitions` | VA hierarchy |
| Counterparty | `beneficiaries`, `kycc_records`, `whitelisted_accounts` | Payer/Payee management |
| Transactions | `transactions`, `account_statements` | Movement tracking |
| Escrow | `escrow_contracts`, `escrow_milestones`, `escrow_documents`, `escrow_transactions` | Escrow lifecycle |
| Wallet | `wallet_programs`, `wallet_accounts`, `wallet_transactions` | Prepaid programs |
| Integration | `bancs_sync_queue`, `bancs_sync_queue_history`, `bancs_health_status` | Core banking sync |
| Reference | `currencies`, `purpose_codes`, `banks` | Master data |
| Audit | `audit_logs` | Compliance trail |

### 1.2 Core Entity Relationships

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           ENTITY RELATIONSHIP MAP                                │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  CORPORATE_CUSTOMERS (1)                                                         │
│       │                                                                          │
│       ├──(1:N)──► CORPORATE_SCHEMES ──(1:N)──► VIRTUAL_ACCOUNTS                 │
│       │               │                              │                           │
│       │               └──(1:1)──► WALLET_PROGRAMS    ├──(1:N)──► WHITELISTED    │
│       │                              │               │                           │
│       ├──(1:N)──► BENEFICIARIES      │               ├──(1:N)──► LEVEL_DEFS     │
│       │               │              │               │                           │
│       │               ├──(0:1)──► KYCC_RECORDS       ├──(1:N)──► STATEMENTS     │
│       │               │              │               │                           │
│       │               └──(1:N)◄── ESCROW_CONTRACTS   └──(SELF-REF)──► PARENT_VA │
│       │                              │                                           │
│       └──(1:N)◄── ESCROW_CONTRACTS   ├──(1:N)──► ESCROW_MILESTONES              │
│            (as seller)               │                                           │
│                                      └──(1:1)◄── VIRTUAL_ACCOUNTS (escrow_acct) │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Part 2: Detailed Domain Analysis

### 2.1 Virtual Account Domain

**Central Entity:** `virtual_accounts`

**Design Pattern:** **Composite Pattern** with self-referential hierarchy

```sql
parent_account_id UUID REFERENCES virtual_accounts(id)
hierarchy_level INTEGER DEFAULT 0
```

**Strengths:**
- ✅ Flexible N-level hierarchy through self-reference
- ✅ Generic reference fields (`account_reference1`, `account_reference2`) enable multi-purpose use
- ✅ Account type polymorphism (`STANDARD`, `ESCROW`, `WALLET`)
- ✅ Local balance caching reduces core banking calls
- ✅ BaNCS sync status tracking per account

**Weaknesses & Debates:**

**Issue 1: Generic Reference Fields**
```sql
account_reference1 VARCHAR(50),  -- Used for payer_ref, vendor_ref, invoice_ref
account_reference2 VARCHAR(50),  -- Used for metadata, expected_amount, category
```

*Debate:*
- **Pro Generalization:** Single VA table serves all use cases (Collections, Payables, Treasury)
- **Con Generalization:** No semantic meaning; requires application-layer interpretation
- **Risk:** No database-level validation of reference content by account purpose

*Recommendation:* Add `account_purpose` ENUM column:
```sql
account_purpose VARCHAR(30) -- COLLECTIONS_PAYER, COLLECTIONS_INVOICE, 
                            -- PAYABLES_VENDOR, TREASURY_POOLING, GENERAL
```

**Issue 2: Balance Staleness**

Current approach caches balance locally with `balance_last_updated` timestamp, but:
- No SLA guarantee on staleness
- No mechanism for invalidation

*Recommendation:* Add TTL configuration:
```sql
balance_cache_ttl_seconds INTEGER DEFAULT 300,
balance_cache_valid BOOLEAN GENERATED ALWAYS AS (
    balance_last_updated > NOW() - INTERVAL '1 second' * balance_cache_ttl_seconds
) STORED
```

---

### 2.2 Transaction Domain

**Current State:**

The `transactions` table handles all payment types with `transaction_context`:
```sql
transaction_context VARCHAR(20) DEFAULT 'STANDARD' -- STANDARD, ESCROW, WALLET
```

**Analysis:**

| Attribute | Support | Gap |
|-----------|---------|-----|
| Debit tracking | ✅ `debtor_account_id` | - |
| Credit tracking | ⚠️ `beneficiary_account` (VARCHAR) | No FK to virtual_accounts for internal credits |
| Multi-currency | ✅ Instructed + Settlement amounts | - |
| Reconciliation | ⚠️ `e2e_reference`, `uetr` | No explicit `reconciliation_status` |
| Scheduled payments | ❌ Not present | Missing `scheduled_execution_date`, `recurring_pattern` |

**Critical Gap: Internal Transfer Modeling**

For intercompany transfers (Subsidiary A → Subsidiary B), the current model:
- Records ONE transaction with debtor/beneficiary
- Does NOT create contra-entry for creditor account

*Debate:*
- **Single Transaction View:** Simpler, matches payment instruction paradigm
- **Double-Entry View:** Proper accounting, explicit audit trail for both sides

*Recommendation:* For internal transfers, implement double-entry:
```sql
-- Add linked transaction support
related_transaction_id UUID REFERENCES transactions(id),
transaction_leg VARCHAR(10) -- 'DEBIT', 'CREDIT'
```

---

### 2.3 Reconciliation Data Flow

**Current Implementation Analysis:**

The `ReconciliationService` uses `AccountStatement` + `VirtualAccount.accountReference1` for matching:

```
INCOMING PAYMENT
     │
     ▼
┌────────────────┐
│ Account        │ Match by: VA IBAN → account_reference1
│ Statement      │           (payer_ref or invoice_ref)
└────────────────┘
     │
     ▼
┌────────────────┐
│ Auto-Match     │ Pattern: E2E ref contains "INV-\d+"
│ Algorithm      │ Confidence: EXACT > HIGH > MEDIUM > UNMATCHED
└────────────────┘
```

**Gaps Identified:**

1. **No Persistent Match Status**
   - Reconciliation is computed on-the-fly
   - No audit trail of who matched what and when
   
2. **No Invoice Master**
   - Matching depends on external invoice reference pattern
   - No validation against expected amounts

3. **No Overpayment Handling**
   - What happens when payer sends more than expected?

**Recommendation:** Add reconciliation tracking table:
```sql
CREATE TABLE reconciliation_records (
    id UUID PRIMARY KEY,
    payment_id UUID REFERENCES transactions(id),
    virtual_account_id UUID REFERENCES virtual_accounts(id),
    external_reference VARCHAR(100), -- Invoice/PO number
    expected_amount DECIMAL(18,2),
    received_amount DECIMAL(18,2),
    match_status VARCHAR(20), -- EXACT, PARTIAL, OVERPAID, UNMATCHED
    match_method VARCHAR(20), -- AUTO_IBAN, AUTO_PATTERN, MANUAL
    match_confidence INTEGER,
    matched_by VARCHAR(100),
    matched_at TIMESTAMP,
    notes TEXT
);
```

---

### 2.4 Liquidity Management Data Flow

**Current Implementation:**

```
┌──────────────────────────────────────────────────────────────────┐
│                    LIQUIDITY POSITION CALCULATION                 │
├──────────────────────────────────────────────────────────────────┤
│                                                                   │
│  Step 1: Load accounts by customer_id                            │
│          virtual_accounts WHERE customer_id = ? AND status = 1   │
│                                                                   │
│  Step 2: Group by currency_code                                  │
│          SUM(current_balance, available_balance, fund_hold)      │
│                                                                   │
│  Step 3: Convert to base currency (hardcoded rates)              │
│          AED, USD(3.6725), EUR(4.0150), GBP(4.6500)             │
│                                                                   │
│  Result: LiquidityPositionDto with currency breakdown            │
│                                                                   │
└──────────────────────────────────────────────────────────────────┘
```

**Gaps:**

1. **No FX Rate Table**
   ```sql
   -- Missing
   CREATE TABLE fx_rates (
       from_currency VARCHAR(3),
       to_currency VARCHAR(3),
       rate DECIMAL(18,8),
       rate_date DATE,
       rate_source VARCHAR(50),
       PRIMARY KEY (from_currency, to_currency, rate_date)
   );
   ```

2. **No Position Snapshots**
   - Historical position queries require transaction replay
   - No EOD position persistence

3. **Sweep Configuration at Wrong Level**
   - Currently on `corporate_schemes`
   - Should also be per-account for granular control

---

### 2.5 Escrow Domain

**Current Model Strengths:**
- ✅ Proper milestone-based release tracking
- ✅ Document management with integrity hash
- ✅ Dispute workflow support
- ✅ Dedicated escrow transaction audit

**Gaps:**

1. **No Partial Funding Support**
   ```sql
   -- escrow_contracts only tracks
   contract_amount DECIMAL(18,2)
   deposit_amount DECIMAL(18,2)
   
   -- Missing: funded_amount tracking
   -- Risk: Multiple partial deposits cannot be tracked
   ```

2. **No Fee Ledger**
   - `escrow_fee` defined but no breakdown of fee collection

3. **No Amendment Tracking**
   - Contract modifications not versioned

**Recommendation:** Add funding and amendment tables:
```sql
CREATE TABLE escrow_funding (
    id UUID PRIMARY KEY,
    contract_id UUID REFERENCES escrow_contracts(id),
    funding_amount DECIMAL(18,2),
    funding_date TIMESTAMP,
    funding_source VARCHAR(34), -- Account IBAN
    transaction_id UUID REFERENCES transactions(id)
);

CREATE TABLE escrow_amendments (
    id UUID PRIMARY KEY,
    contract_id UUID REFERENCES escrow_contracts(id),
    amendment_number INTEGER,
    amendment_type VARCHAR(50),
    previous_value JSONB,
    new_value JSONB,
    requested_by VARCHAR(20),
    approved_by VARCHAR(20),
    effective_date DATE
);
```

---

### 2.6 Wallet Domain

**Current Model Assessment:**

| Aspect | Status | Notes |
|--------|--------|-------|
| Balance tracking | ✅ | Mirrors VA balance |
| Spend limits | ✅ | Daily/Monthly at program + override |
| P2P transfer | ✅ | Flag-controlled |
| Transaction history | ✅ | Dedicated wallet_transactions |
| Merchant categorization | ⚠️ | MCC field exists, no category table |
| Reward/Cashback | ❌ | Not modeled |

**Gap: No Wallet Lifecycle Events**

Missing state transitions audit:
- Activation
- Suspension (and reason)
- Reactivation
- Closure

**Recommendation:**
```sql
CREATE TABLE wallet_lifecycle_events (
    id UUID PRIMARY KEY,
    wallet_id UUID REFERENCES wallet_accounts(id),
    event_type VARCHAR(30), -- CREATED, ACTIVATED, SUSPENDED, REACTIVATED, CLOSED
    event_reason TEXT,
    performed_by VARCHAR(100),
    performed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    metadata JSONB
);
```

---

## Part 3: Cross-Cutting Concerns

### 3.1 Multi-Tenancy

**Current State:** Implicit tenant isolation via `customer_id` foreign keys

**Risk:** No database-level enforcement prevents cross-customer data access if application layer fails.

**Options:**

| Approach | Pros | Cons |
|----------|------|------|
| Row-Level Security (RLS) | DB-enforced, transparent | PostgreSQL overhead, complex policies |
| Separate Schemas | Strong isolation | Migration complexity, connection pooling |
| Composite PKs with tenant_id | Application pattern | Breaking change to existing model |

**Recommendation:** Implement PostgreSQL RLS:
```sql
ALTER TABLE virtual_accounts ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON virtual_accounts
    USING (customer_id = current_setting('app.current_customer')::UUID);
```

---

### 3.2 Audit Trail Completeness

**Current State:** Generic `audit_logs` table captures entity changes

**Gap:** No specific audit for:
- Balance changes (who changed, why)
- Reconciliation decisions
- Approval workflows

**Recommendation:** Add domain-specific audit tables:
```sql
CREATE TABLE balance_audit (
    id UUID PRIMARY KEY,
    virtual_account_id UUID REFERENCES virtual_accounts(id),
    previous_balance DECIMAL(18,2),
    new_balance DECIMAL(18,2),
    change_reason VARCHAR(50), -- TRANSACTION, ADJUSTMENT, SYNC, CORRECTION
    transaction_id UUID,
    changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    changed_by VARCHAR(100)
);
```

---

### 3.3 Temporal Data Handling

**Current State:** `created_at`, `updated_at` timestamps on most entities

**Gap:** No support for:
- Point-in-time queries ("What was the balance on Dec 31?")
- Effective-dated changes (scheme terms change on future date)

**Options:**

| Pattern | Use Case | Implementation |
|---------|----------|----------------|
| Event Sourcing | Full replay capability | Major architectural change |
| Slowly Changing Dimensions | Historical attribute tracking | Add valid_from/valid_to |
| Snapshots | EOD position queries | Scheduled job |

**Recommendation for VAM:** Implement SCD Type 2 for key entities:
```sql
ALTER TABLE corporate_schemes 
ADD COLUMN version_start_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
ADD COLUMN version_end_date TIMESTAMP,
ADD COLUMN is_current BOOLEAN DEFAULT TRUE;
```

---

## Part 4: Extensibility Assessment

### 4.1 Use Case Coverage Matrix

| Use Case | Data Model Support | Service Implementation | API | Gap Assessment |
|----------|-------------------|----------------------|-----|----------------|
| Collections (Payer VA) | ⚠️ Generic refs | ✅ ReconciliationService | ✅ | Need purpose tracking |
| Collections (Invoice VA) | ⚠️ Generic refs | ✅ ReconciliationService | ✅ | Need invoice master |
| Payables Segregation | ⚠️ Generic refs | ✅ PayablesService | ✅ | Need vendor master |
| Treasury Pooling | ✅ Hierarchy | ✅ LiquidityService | ✅ | Solid |
| Zero-Balance Sweep | ✅ Scheme config | ✅ LiquidityService | ✅ | Solid |
| Threshold Sweep | ✅ Scheme config | ✅ LiquidityService | ✅ | Solid |
| Cash Forecasting | ⚠️ Transaction history | ✅ LiquidityService | ✅ | Need forecast storage |
| Digital Escrow | ✅ Full model | ✅ EscrowService | ✅ | Need amendments |
| Wallet Programs | ✅ Full model | ✅ WalletService | ✅ | Need rewards |

### 4.2 Future Use Case Readiness

**1. Virtual IBAN for E-commerce**
*Scenario:* Merchant receives payments via unique VA per order

| Requirement | Current Support | Gap |
|-------------|-----------------|-----|
| High-volume VA creation | ⚠️ Sequence generation | Need batch API |
| Auto-close after payment | ❌ | Need trigger/scheduler |
| Notification webhook | ❌ | Need webhook table |

**2. Loan Collections**
*Scenario:* Unique VA per loan account for EMI collection

| Requirement | Current Support | Gap |
|-------------|-----------------|-----|
| Link to external loan ID | ⚠️ account_reference1 | Need loan_account_id FK |
| Overdue tracking | ❌ | Need expected_date field |
| Partial payment handling | ⚠️ Implicit | Need installment tracking |

**3. Investment Account Segregation**
*Scenario:* Custody accounts with regulatory reporting

| Requirement | Current Support | Gap |
|-------------|-----------------|-----|
| Regulatory classification | ❌ | Need classification fields |
| NAV tracking | ❌ | New entity required |
| Corporate action handling | ❌ | New entity required |

**4. Cross-Border Pooling**
*Scenario:* Multi-currency notional pooling across entities

| Requirement | Current Support | Gap |
|-------------|-----------------|-----|
| Multi-currency VA | ✅ currency_code | Solid |
| FX conversion | ❌ Hardcoded | Need FX rate table |
| Interest calculation | ❌ | Need interest rules engine |
| Notional vs Physical | ❌ | Need pool_type field |

---

## Part 5: Recommendations Summary

### 5.1 Critical (Data Integrity)

| # | Recommendation | Impact | Effort |
|---|----------------|--------|--------|
| 1 | Add `reconciliation_records` table | Audit compliance | Medium |
| 2 | Implement double-entry for internal transfers | Accounting accuracy | Medium |
| 3 | Add `fx_rates` table | Multi-currency operations | Low |
| 4 | Implement RLS for multi-tenancy | Security | Medium |

### 5.2 Important (Functionality)

| # | Recommendation | Impact | Effort |
|---|----------------|--------|--------|
| 5 | Add `account_purpose` enum | Semantic clarity | Low |
| 6 | Add scheduled payment support | Payables automation | Medium |
| 7 | Create `escrow_funding` table | Partial funding | Low |
| 8 | Add `wallet_lifecycle_events` | Compliance | Low |

### 5.3 Nice-to-Have (Extensibility)

| # | Recommendation | Impact | Effort |
|---|----------------|--------|--------|
| 9 | Implement SCD Type 2 | Historical queries | High |
| 10 | Add webhook configuration | Integration | Medium |
| 11 | Create vendor/invoice master | Reconciliation accuracy | Medium |
| 12 | Add rewards/cashback model | Wallet features | Medium |

---

## Part 6: Proposed Enhanced ERD

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          PROPOSED ENHANCED DATA MODEL                                │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                      │
│  [NEW] fx_rates                    corporate_customers ◄──────── users              │
│       │                                    │                                         │
│       └─────────────────┐                  ├──► corporate_schemes                   │
│                         │                  │         │                               │
│  [NEW] vendors ◄────────┼──────────────────┤         ├──► virtual_accounts          │
│       │                 │                  │         │         │                     │
│  [NEW] invoices ◄───────┼──────────────────┤         │         ├──► [NEW] recon_records
│       │                 │                  │         │         │                     │
│       └─────────────────┼──────────────────┼─────────┼─────────┤                    │
│                         │                  │         │         │                     │
│  [NEW] scheduled_payments ◄────────────────┼─────────┼─────────┤                    │
│                         │                  │         │         │                     │
│  [NEW] position_snapshots ◄────────────────┼─────────┼─────────┘                    │
│                         │                  │         │                               │
│                         └──────────────────┴─────────┴──► transactions              │
│                                                              │                       │
│                                                              └──► [NEW] balance_audit│
│                                                                                      │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Part 7: Implementation Roadmap

### Phase 1: Foundation (Weeks 1-2)
- Add `fx_rates` table with historical rate support
- Add `account_purpose` column to `virtual_accounts`
- Create `reconciliation_records` table
- Implement balance audit trigger

### Phase 2: Reconciliation Enhancement (Weeks 3-4)
- Create `vendors` master table
- Create `invoices` master table  
- Link reconciliation to invoices
- Add overpayment handling workflow

### Phase 3: Payment Scheduling (Weeks 5-6)
- Create `scheduled_payments` table
- Implement scheduling service
- Add recurring payment patterns
- Create execution scheduler

### Phase 4: Historical & Reporting (Weeks 7-8)
- Create `position_snapshots` table
- Implement EOD snapshot job
- Add point-in-time position queries
- Create regulatory reporting views

---

## Appendix A: SQL Scripts for Critical Recommendations

### A.1 Reconciliation Records Table
```sql
CREATE TABLE reconciliation_records (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    payment_id UUID NOT NULL REFERENCES transactions(id),
    virtual_account_id UUID NOT NULL REFERENCES virtual_accounts(id),
    external_reference VARCHAR(100),
    external_reference_type VARCHAR(30), -- INVOICE, PO, CONTRACT
    expected_amount DECIMAL(18,2),
    received_amount DECIMAL(18,2) NOT NULL,
    variance_amount DECIMAL(18,2) GENERATED ALWAYS AS (received_amount - COALESCE(expected_amount, received_amount)) STORED,
    match_status VARCHAR(20) NOT NULL, -- EXACT_MATCH, PARTIAL_MATCH, OVERPAID, UNDERPAID, UNMATCHED
    match_method VARCHAR(20) NOT NULL, -- AUTO_IBAN, AUTO_REFERENCE, MANUAL
    match_confidence INTEGER CHECK (match_confidence BETWEEN 0 AND 100),
    matched_by VARCHAR(100),
    matched_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (payment_id, virtual_account_id)
);

CREATE INDEX idx_recon_status ON reconciliation_records(match_status);
CREATE INDEX idx_recon_account ON reconciliation_records(virtual_account_id);
CREATE INDEX idx_recon_external ON reconciliation_records(external_reference);
```

### A.2 FX Rates Table
```sql
CREATE TABLE fx_rates (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    from_currency VARCHAR(3) NOT NULL REFERENCES currencies(currency_code),
    to_currency VARCHAR(3) NOT NULL REFERENCES currencies(currency_code),
    rate DECIMAL(18,8) NOT NULL,
    rate_date DATE NOT NULL,
    rate_type VARCHAR(20) DEFAULT 'MID', -- BID, ASK, MID
    rate_source VARCHAR(50), -- REUTERS, BLOOMBERG, CENTRAL_BANK
    valid_from TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    valid_until TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (from_currency, to_currency, rate_date, rate_type)
);

CREATE INDEX idx_fx_pair_date ON fx_rates(from_currency, to_currency, rate_date DESC);
```

### A.3 Scheduled Payments Table
```sql
CREATE TABLE scheduled_payments (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    customer_id UUID NOT NULL REFERENCES corporate_customers(id),
    debtor_account_id UUID NOT NULL REFERENCES virtual_accounts(id),
    beneficiary_id UUID REFERENCES beneficiaries(id),
    beneficiary_account VARCHAR(34) NOT NULL,
    beneficiary_name VARCHAR(200),
    
    -- Amount
    amount DECIMAL(18,2) NOT NULL,
    currency_code VARCHAR(3) DEFAULT 'AED',
    
    -- Scheduling
    schedule_type VARCHAR(20) NOT NULL, -- ONCE, DAILY, WEEKLY, MONTHLY, QUARTERLY
    scheduled_date DATE NOT NULL,
    execution_time TIME,
    recurrence_end_date DATE,
    day_of_week INTEGER, -- 1-7 for weekly
    day_of_month INTEGER, -- 1-31 for monthly
    
    -- Execution
    next_execution_date DATE,
    last_execution_date DATE,
    execution_count INTEGER DEFAULT 0,
    max_executions INTEGER,
    
    -- Fund Hold
    hold_funds BOOLEAN DEFAULT FALSE,
    held_amount DECIMAL(18,2),
    hold_reference VARCHAR(50),
    
    -- Status
    status VARCHAR(20) DEFAULT 'ACTIVE', -- ACTIVE, PAUSED, COMPLETED, CANCELLED
    failure_count INTEGER DEFAULT 0,
    last_failure_reason TEXT,
    
    -- Payment Details
    purpose_code VARCHAR(10),
    remittance_info TEXT,
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100)
);

CREATE INDEX idx_sched_next ON scheduled_payments(next_execution_date) WHERE status = 'ACTIVE';
CREATE INDEX idx_sched_customer ON scheduled_payments(customer_id);
CREATE INDEX idx_sched_debtor ON scheduled_payments(debtor_account_id);
```

---

## Appendix B: Risk Assessment Matrix

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Balance cache staleness | High | Medium | Implement TTL + webhook notification |
| Cross-tenant data leak | Low | Critical | Implement RLS |
| Reconciliation disputes | Medium | High | Persist match audit |
| FX rate errors | Medium | High | Centralize rate service |
| Orphaned escrow funds | Low | Critical | Add timeout + escalation |
| Sweep overdraft | Low | High | Pre-validate available balance |

---

*End of Architectural Analysis*
