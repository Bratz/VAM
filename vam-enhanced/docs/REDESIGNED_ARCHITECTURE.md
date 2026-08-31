# VAM System - Redesigned Architecture

## Design Principles (Inspired by TietoEvry VAM)

1. **Programs as Universal Container** - All specialized VA use cases are "programs"
2. **IHB as Overlay** - Not a parallel structure, but an orchestration layer on existing VAs
3. **VA Hierarchy is King** - Parent-child relationships handle all pooling/nesting
4. **Allocations for Funding** - Budget allocations flow down the hierarchy

---

## Part 1: Unified Program Model

### Current Problem
We have separate tables for different use cases:
- `wallet_programs` + `wallet_accounts` for prepaid wallets
- `ecommerce_merchants` + `ecommerce_virtual_ibans` for e-commerce
- `in_house_banks` + `ihb_participants` for IHB

### Proposed Solution
**One `va_programs` table to rule them all.**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           VA_PROGRAMS (Universal)                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  program_type:                                                               │
│  ├── WALLET          → Prepaid/postpaid consumer wallets                    │
│  ├── ECOMMERCE       → Merchant order-based VIBANs                          │
│  ├── COLLECTIONS     → Per-payer or per-invoice collection VAs              │
│  ├── PAYABLES        → Per-vendor or per-category disbursement VAs          │
│  ├── ESCROW          → Escrow holding accounts                              │
│  ├── CLIENT_MONEY    → Segregated client funds (asset managers, brokers)    │
│  └── VIRTUAL_BRANCH  → Cross-border virtual presence                        │
│                                                                              │
│  Common attributes:                                                          │
│  ├── operator_id (corporate customer who runs the program)                  │
│  ├── scheme_id (VA scheme for this program)                                 │
│  ├── settlement_account_id (where funds sweep to)                           │
│  ├── iban_generation_strategy                                               │
│  ├── auto_closure_rules (JSON)                                              │
│  ├── webhook_config (JSON)                                                  │
│  ├── limits (daily, monthly, concurrent)                                    │
│  └── fee_structure (JSON)                                                   │
│                                                                              │
│  Program-specific config in JSONB:                                          │
│  └── program_config: {                                                      │
│        // WALLET: kyc_level, card_issuance, etc.                           │
│        // ECOMMERCE: expiry_hours, tolerance, merchant_category             │
│        // COLLECTIONS: matching_rules, reconciliation_config                │
│        // ESCROW: milestone_release, dispute_handling                       │
│      }                                                                       │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Program Accounts (Universal)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      PROGRAM_ACCOUNTS (Universal)                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Links:                                                                      │
│  ├── program_id → va_programs.id                                            │
│  ├── virtual_account_id → virtual_accounts.id (1:1)                         │
│  └── holder_id → program_account_holders.id (optional)                      │
│                                                                              │
│  account_subtype (program-specific):                                         │
│  ├── WALLET: PREPAID, POSTPAID, SAVINGS                                     │
│  ├── ECOMMERCE: ORDER_PAYMENT, SUBSCRIPTION, INVOICE                        │
│  ├── COLLECTIONS: PAYER_DEDICATED, INVOICE_SPECIFIC, GENERAL                │
│  ├── PAYABLES: VENDOR_DEDICATED, CATEGORY_POOL                              │
│  └── ESCROW: GOODS, SERVICES, REAL_ESTATE                                   │
│                                                                              │
│  Common fields:                                                              │
│  ├── external_reference (order_id, invoice_number, payer_code, etc.)        │
│  ├── expected_amount (for payment matching)                                 │
│  ├── received_amount                                                         │
│  ├── expires_at (auto-closure)                                              │
│  ├── metadata (JSONB - flexible per use case)                               │
│  └── status lifecycle                                                        │
│                                                                              │
│  This REPLACES:                                                              │
│  - wallet_accounts                                                           │
│  - ecommerce_virtual_ibans                                                   │
│  - (escrow_contracts remains separate for complex workflows)                 │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Program Account Holders (Universal)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                   PROGRAM_ACCOUNT_HOLDERS (Universal)                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Replaces multiple concepts:                                                 │
│  ├── Wallet holder (beneficiary with role WALLET_HOLDER)                    │
│  ├── E-commerce buyer (inline in metadata currently)                        │
│  ├── Collection payer (inline in transactions currently)                    │
│  └── Escrow buyer (beneficiary with role BUYER)                             │
│                                                                              │
│  holder_type:                                                                │
│  ├── INDIVIDUAL      → Natural person                                       │
│  ├── CORPORATE       → Legal entity                                         │
│  ├── ANONYMOUS       → E-commerce one-time payer                            │
│  └── SUBSIDIARY      → IHB participant (internal)                           │
│                                                                              │
│  Links:                                                                      │
│  ├── program_id → va_programs.id                                            │
│  ├── kycc_id → kycc_records.id (if KYC required)                            │
│  └── beneficiary_id → beneficiaries.id (if also a beneficiary)              │
│                                                                              │
│  Identity fields:                                                            │
│  ├── holder_reference (external ID)                                         │
│  ├── holder_name                                                             │
│  ├── holder_email                                                            │
│  ├── holder_phone                                                            │
│  ├── holder_account (bank account if known)                                 │
│  └── identity_verified (boolean)                                            │
│                                                                              │
│  This allows:                                                                │
│  - Same person can be holder in multiple programs                           │
│  - Repeat e-commerce buyers can be tracked                                  │
│  - Wallet holders have proper identity management                           │
│  - IHB subsidiaries are "internal holders"                                  │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Part 2: IHB as Overlay (Not Parallel Structure)

### Key Insight from TietoEvry
> "VAM is designed as a virtual system overlay, that can be implemented on top of current 
> core platforms of a bank with minimal impact to the existing infrastructure."

### IHB Should NOT Own Accounts
Instead of `ihb_participants` having their own account structure, IHB should be an 
**orchestration layer** that operates on existing VAs and Programs.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        IHB AS OVERLAY ARCHITECTURE                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  EXISTING VA STRUCTURE (unchanged):                                          │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │ corporate_customers                                                  │    │
│  │     │                                                                │    │
│  │     ├── corporate_schemes (per subsidiary/region)                   │    │
│  │     │       │                                                        │    │
│  │     │       └── virtual_accounts (hierarchy within scheme)          │    │
│  │     │             ├── parent_account_id (pooling structure)         │    │
│  │     │             └── account_purpose (COLLECTIONS, PAYABLES, etc.) │    │
│  │     │                                                                │    │
│  │     └── beneficiaries (external payment recipients)                 │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  IHB OVERLAY (NEW):                                                          │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                                                                      │    │
│  │  ihb_configurations                                                  │    │
│  │    │                                                                 │    │
│  │    ├── Links to existing customer: customer_id                      │    │
│  │    ├── Defines IHB policies: interest, FX, netting rules            │    │
│  │    └── Does NOT own accounts directly                               │    │
│  │                                                                      │    │
│  │  ihb_entity_mappings (maps existing schemes to IHB participants)    │    │
│  │    │                                                                 │    │
│  │    ├── ihb_id → ihb_configurations.id                               │    │
│  │    ├── scheme_id → corporate_schemes.id (existing!)                 │    │
│  │    ├── entity_role: TREASURY_CENTER, SUBSIDIARY, EXTERNAL           │    │
│  │    ├── interest_profile_id → interest_profiles.id                   │    │
│  │    └── allocation rules                                              │    │
│  │                                                                      │    │
│  │  ihb_account_designations (designates existing VAs for IHB roles)   │    │
│  │    │                                                                 │    │
│  │    ├── ihb_id → ihb_configurations.id                               │    │
│  │    ├── virtual_account_id → virtual_accounts.id (existing!)         │    │
│  │    ├── designation: OPERATING, IC_RECEIVABLE, IC_PAYABLE,           │    │
│  │    │                POOL_HEADER, POOL_MEMBER, NETTING               │    │
│  │    └── interest_bearing: true/false                                 │    │
│  │                                                                      │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  IHB OPERATIONS (on existing accounts):                                      │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                                                                      │    │
│  │  ▶ POBO: Payment-on-Behalf-Of                                       │    │
│  │    - Treasury center pays from its VA                               │    │
│  │    - Books IC payable to originating subsidiary                     │    │
│  │    - Uses existing transactions + ic_positions                      │    │
│  │                                                                      │    │
│  │  ▶ ROBO: Receivables-on-Behalf-Of                                   │    │
│  │    - Collections flow to centralized VA                             │    │
│  │    - Books IC receivable from subsidiary                            │    │
│  │    - Uses existing VA hierarchy + reconciliation_records            │    │
│  │                                                                      │    │
│  │  ▶ Notional Pooling                                                 │    │
│  │    - Uses existing VA hierarchy (parent = pool header)              │    │
│  │    - Calculates interest optimization across children               │    │
│  │    - Posts interest via interest_accruals                           │    │
│  │                                                                      │    │
│  │  ▶ Netting                                                          │    │
│  │    - Calculates net positions from IC balances                      │    │
│  │    - Settles via internal transfers between existing VAs            │    │
│  │                                                                      │    │
│  │  ▶ IC Loans                                                         │    │
│  │    - Loan between two existing VAs (lender VA → borrower VA)        │    │
│  │    - Uses ic_loans table for terms and tracking                     │    │
│  │                                                                      │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Part 3: IHB Allocations

### What are Allocations?
Allocations are **funding limits and budget assignments** from parent to subsidiaries.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           IHB ALLOCATIONS                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  allocation_type:                                                            │
│  ├── CREDIT_LINE      → Borrowing limit from IHB                            │
│  ├── OVERDRAFT        → Allowed negative balance                            │
│  ├── BUDGET           → Spending allocation for period                      │
│  ├── FX_LIMIT         → Internal FX trading limit                           │
│  ├── NETTING_LIMIT    → Max netting exposure                                │
│  └── INVESTMENT       → Cash investment allocation                          │
│                                                                              │
│  Allocation hierarchy:                                                       │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                                                                      │    │
│  │  GROUP TOTAL: 100M AED                                              │    │
│  │       │                                                              │    │
│  │       ├── REGION_MENA: 40M                                          │    │
│  │       │       ├── SUBSIDIARY_UAE: 20M                               │    │
│  │       │       ├── SUBSIDIARY_KSA: 15M                               │    │
│  │       │       └── SUBSIDIARY_EGYPT: 5M                              │    │
│  │       │                                                              │    │
│  │       ├── REGION_APAC: 35M                                          │    │
│  │       │       ├── SUBSIDIARY_INDIA: 20M                             │    │
│  │       │       └── SUBSIDIARY_SINGAPORE: 15M                         │    │
│  │       │                                                              │    │
│  │       └── REGION_EUROPE: 25M                                        │    │
│  │               ├── SUBSIDIARY_UK: 15M                                │    │
│  │               └── SUBSIDIARY_GERMANY: 10M                           │    │
│  │                                                                      │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  Allocation tracking:                                                        │
│  ├── allocated_amount (budget)                                              │
│  ├── utilized_amount (spent/borrowed)                                       │
│  ├── available_amount (remaining)                                           │
│  ├── utilization_percentage                                                 │
│  └── breach_notifications                                                   │
│                                                                              │
│  Time-based:                                                                 │
│  ├── ANNUAL allocations (budget year)                                       │
│  ├── QUARTERLY allocations                                                  │
│  ├── MONTHLY allocations                                                    │
│  └── PERMANENT allocations (credit lines)                                   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Part 4: Intercompany (IC) Position Tracking

### IC Positions Replace Separate IC Accounts
Instead of separate "intercompany accounts", we track IC positions as a ledger.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        IC POSITION TRACKING                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ic_positions (running balance per entity pair)                             │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                                                                      │    │
│  │  ihb_id                                                              │    │
│  │  entity_a_scheme_id  → corporate_schemes.id                         │    │
│  │  entity_b_scheme_id  → corporate_schemes.id                         │    │
│  │  currency_code                                                       │    │
│  │                                                                      │    │
│  │  net_position        → Positive = A owes B, Negative = B owes A     │    │
│  │  gross_a_to_b        → Total A paid on behalf of B                  │    │
│  │  gross_b_to_a        → Total B paid on behalf of A                  │    │
│  │                                                                      │    │
│  │  last_settlement_date                                                │    │
│  │  last_settlement_amount                                              │    │
│  │                                                                      │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  ic_position_movements (detailed ledger)                                    │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                                                                      │    │
│  │  movement_type:                                                      │    │
│  │  ├── POBO_PAYMENT    → A paid on behalf of B                        │    │
│  │  ├── ROBO_COLLECTION → A collected on behalf of B                   │    │
│  │  ├── LOAN_DRAWDOWN   → A borrowed from B                            │    │
│  │  ├── LOAN_REPAYMENT  → A repaid to B                                │    │
│  │  ├── INTEREST_CHARGE → Interest on IC balance                       │    │
│  │  ├── NETTING_SETTLE  → Netting settlement                           │    │
│  │  ├── FX_SETTLEMENT   → Internal FX settlement                       │    │
│  │  └── MANUAL_ADJUST   → Manual adjustment                            │    │
│  │                                                                      │    │
│  │  from_entity_scheme_id                                               │    │
│  │  to_entity_scheme_id                                                 │    │
│  │  amount, currency                                                    │    │
│  │  reference_type, reference_id (links to source transaction)         │    │
│  │  position_before, position_after                                     │    │
│  │                                                                      │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Part 5: Revised Entity Relationship Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                       REVISED ERD - UNIFIED MODEL                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│                        ┌──────────────────┐                                 │
│                        │ CORPORATE_CUSTOMER│                                 │
│                        └────────┬─────────┘                                 │
│                                 │                                            │
│              ┌──────────────────┼──────────────────┐                        │
│              │                  │                  │                        │
│              ▼                  ▼                  ▼                        │
│    ┌─────────────────┐  ┌─────────────┐  ┌────────────────┐                │
│    │ CORPORATE_SCHEME│  │ BENEFICIARY │  │IHB_CONFIGURATION│                │
│    └────────┬────────┘  └─────────────┘  └────────┬───────┘                │
│             │                                      │                        │
│             │ 1:N                                  │ (overlay)              │
│             ▼                                      ▼                        │
│    ┌─────────────────┐                   ┌────────────────────┐            │
│    │ VIRTUAL_ACCOUNT │◄──────────────────│IHB_ENTITY_MAPPING  │            │
│    │                 │                   │(scheme → IHB role) │            │
│    │ - parent_id     │                   └────────────────────┘            │
│    │ - account_type  │                            │                        │
│    │ - account_purpose│                           │                        │
│    └────────┬────────┘                   ┌────────▼───────────┐            │
│             │                            │IHB_ACCOUNT_DESIG   │            │
│             │ 1:1                        │(VA → IHB role)     │            │
│             ▼                            └────────────────────┘            │
│    ┌─────────────────┐                                                      │
│    │ PROGRAM_ACCOUNT │◄────────────┐                                       │
│    │                 │             │                                        │
│    │ - program_id    │             │ 1:1 (optional)                        │
│    │ - holder_id     │             │                                        │
│    │ - subtype       │    ┌────────┴───────┐                               │
│    │ - external_ref  │    │   VA_PROGRAM   │                               │
│    │ - expected_amt  │    │                │                               │
│    │ - metadata      │    │ - program_type │                               │
│    └─────────────────┘    │ - operator_id  │                               │
│                           │ - scheme_id    │                               │
│             │             │ - config (JSON)│                               │
│             │ N:1         └────────────────┘                               │
│             ▼                                                               │
│    ┌───────────────────┐                                                    │
│    │PROGRAM_ACCT_HOLDER│                                                    │
│    │                   │                                                    │
│    │ - holder_type     │                                                    │
│    │ - kycc_id         │                                                    │
│    │ - beneficiary_id  │                                                    │
│    └───────────────────┘                                                    │
│                                                                              │
│  IHB OPERATIONAL TABLES (use existing VAs):                                 │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                                                                      │    │
│  │  ic_positions         → Running IC balance between entities         │    │
│  │  ic_position_movements → Detailed IC ledger                         │    │
│  │  ic_loans             → IC loan tracking (existing)                 │    │
│  │  ihb_allocations      → Budget/credit allocations                   │    │
│  │  netting_cycles       → Netting settlements (existing)              │    │
│  │  internal_fx          → Internal FX (existing)                      │    │
│  │  interest_accruals    → Interest calculations (existing)            │    │
│  │                                                                      │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Part 6: Migration Strategy

### What Changes

| Current | New | Migration |
|---------|-----|-----------|
| `wallet_programs` | `va_programs` (type=WALLET) | Migrate data |
| `wallet_accounts` | `program_accounts` | Migrate data |
| `ecommerce_merchants` | `va_programs` (type=ECOMMERCE) | Migrate data |
| `ecommerce_virtual_ibans` | `program_accounts` | Migrate data |
| `in_house_banks` | `ihb_configurations` | Rename + slim down |
| `ihb_participants` | `ihb_entity_mappings` | Maps to existing schemes |
| N/A | `program_account_holders` | New - unify holder concept |
| N/A | `ihb_account_designations` | New - link existing VAs |
| N/A | `ihb_allocations` | New |
| N/A | `ic_positions` | New - replace IC accounts |
| N/A | `ic_position_movements` | New - IC ledger |

### What Stays

| Table | Reason |
|-------|--------|
| `corporate_customers` | Core entity |
| `corporate_schemes` | VA grouping |
| `virtual_accounts` | Core - IHB uses existing VAs |
| `beneficiaries` | External recipients |
| `kycc_records` | KYC data |
| `transactions` | Core transactions |
| `escrow_contracts` | Complex workflow |
| `escrow_milestones` | Escrow-specific |
| `reconciliation_records` | Works with any VA |
| `fx_rates` | Core FX |
| All existing audit tables | Audit trail |

---

## Part 7: Benefits of Redesign

### 1. Unified Program Model
- **Single onboarding flow** for any program type
- **Consistent API** across wallet, e-commerce, collections
- **Shared infrastructure** - IBAN pool, webhooks, limits
- **Easy to add new program types** - just add enum value

### 2. IHB as Overlay
- **No duplicate account structures** - uses existing VAs
- **Existing reporting works** - VA reports include IHB
- **Simpler data model** - fewer tables, fewer joins
- **Incremental adoption** - designate existing VAs for IHB

### 3. Proper IC Tracking
- **Real-time IC positions** - not derived from transactions
- **Full audit trail** - every IC movement logged
- **Easy reconciliation** - positions match to movements
- **Support for POBO/ROBO** - industry-standard patterns

### 4. Allocations
- **Budget control** - subsidiaries have limits
- **Real-time utilization** - dashboard visibility
- **Hierarchical** - group → region → entity
- **Multi-type** - credit, overdraft, FX, netting

---

## Part 8: Sample SQL - Core Tables

```sql
-- ============================================================================
-- UNIFIED PROGRAM MODEL
-- ============================================================================

CREATE TABLE va_programs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    program_code VARCHAR(20) NOT NULL UNIQUE,
    program_name VARCHAR(200) NOT NULL,
    
    -- Type
    program_type VARCHAR(20) NOT NULL, -- WALLET, ECOMMERCE, COLLECTIONS, PAYABLES, ESCROW, CLIENT_MONEY
    
    -- Ownership
    operator_id UUID NOT NULL REFERENCES corporate_customers(id),
    scheme_id UUID NOT NULL REFERENCES corporate_schemes(id),
    
    -- Settlement
    settlement_account_id UUID REFERENCES virtual_accounts(id),
    settlement_frequency VARCHAR(20) DEFAULT 'REALTIME',
    
    -- IBAN Generation
    iban_generation_strategy VARCHAR(20) DEFAULT 'SEQUENTIAL',
    iban_pool_size INTEGER DEFAULT 1000,
    
    -- Limits
    daily_account_limit INTEGER DEFAULT 10000,
    monthly_account_limit INTEGER DEFAULT 100000,
    concurrent_active_limit INTEGER DEFAULT 5000,
    
    -- Webhooks
    webhook_url VARCHAR(500),
    webhook_secret VARCHAR(100),
    webhook_enabled BOOLEAN DEFAULT TRUE,
    webhook_events TEXT[],
    
    -- Auto-closure (common)
    auto_close_on_payment BOOLEAN DEFAULT FALSE,
    default_expiry_hours INTEGER,
    
    -- Program-specific config
    program_config JSONB,
    /*
    WALLET: {kyc_level, card_enabled, daily_limit, monthly_limit}
    ECOMMERCE: {tolerance_pct, allow_overpayment, merchant_category}
    COLLECTIONS: {matching_rules, auto_reconcile}
    */
    
    -- Fee Structure
    fee_structure JSONB,
    
    -- Status
    status VARCHAR(20) DEFAULT 'ACTIVE',
    effective_from DATE DEFAULT CURRENT_DATE,
    effective_to DATE,
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100)
);

CREATE TABLE program_accounts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    
    -- Links
    program_id UUID NOT NULL REFERENCES va_programs(id),
    virtual_account_id UUID NOT NULL REFERENCES virtual_accounts(id),
    holder_id UUID REFERENCES program_account_holders(id),
    
    -- Subtype (program-specific)
    account_subtype VARCHAR(30),
    
    -- External References
    external_reference VARCHAR(100) NOT NULL, -- order_id, invoice_no, payer_code
    secondary_reference VARCHAR(100),
    
    -- Expected vs Received
    expected_amount DECIMAL(18,2),
    currency_code VARCHAR(3) DEFAULT 'AED',
    received_amount DECIMAL(18,2) DEFAULT 0,
    received_count INTEGER DEFAULT 0,
    variance_amount DECIMAL(18,2) GENERATED ALWAYS AS (received_amount - expected_amount) STORED,
    
    -- Lifecycle
    status VARCHAR(20) DEFAULT 'ACTIVE',
    payment_status VARCHAR(20) DEFAULT 'PENDING',
    expires_at TIMESTAMP,
    closed_at TIMESTAMP,
    closure_reason VARCHAR(50),
    
    -- Flexible Metadata
    metadata JSONB,
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT uq_program_external_ref UNIQUE (program_id, external_reference)
);

CREATE TABLE program_account_holders (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    
    -- Links
    program_id UUID NOT NULL REFERENCES va_programs(id),
    kycc_id UUID REFERENCES kycc_records(id),
    beneficiary_id UUID REFERENCES beneficiaries(id), -- if also a beneficiary
    
    -- Type
    holder_type VARCHAR(20) NOT NULL, -- INDIVIDUAL, CORPORATE, ANONYMOUS, SUBSIDIARY
    
    -- Identity
    holder_reference VARCHAR(50) NOT NULL,
    holder_name VARCHAR(200),
    holder_email VARCHAR(200),
    holder_phone VARCHAR(50),
    holder_account VARCHAR(34), -- bank account if known
    holder_bank_bic VARCHAR(11),
    
    -- Verification
    identity_verified BOOLEAN DEFAULT FALSE,
    kyc_level VARCHAR(20),
    
    -- Status
    status VARCHAR(20) DEFAULT 'ACTIVE',
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT uq_program_holder_ref UNIQUE (program_id, holder_reference)
);


-- ============================================================================
-- IHB AS OVERLAY
-- ============================================================================

CREATE TABLE ihb_configurations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    
    -- Link to existing customer
    customer_id UUID NOT NULL REFERENCES corporate_customers(id),
    
    -- Identity
    ihb_code VARCHAR(20) NOT NULL UNIQUE,
    ihb_name VARCHAR(200) NOT NULL,
    base_currency VARCHAR(3) DEFAULT 'AED',
    
    -- Policies
    pooling_type VARCHAR(20) DEFAULT 'NOTIONAL',
    netting_enabled BOOLEAN DEFAULT TRUE,
    netting_frequency VARCHAR(20) DEFAULT 'MONTHLY',
    internal_fx_enabled BOOLEAN DEFAULT TRUE,
    fx_markup_bps INTEGER DEFAULT 0, -- basis points
    
    -- Interest
    interest_calculation_enabled BOOLEAN DEFAULT TRUE,
    interest_calculation_basis VARCHAR(20) DEFAULT 'ACT_360',
    interest_posting_frequency VARCHAR(20) DEFAULT 'MONTHLY',
    
    -- Config
    config JSONB,
    
    status VARCHAR(20) DEFAULT 'ACTIVE',
    effective_from DATE DEFAULT CURRENT_DATE,
    effective_to DATE,
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE ihb_entity_mappings (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    
    ihb_id UUID NOT NULL REFERENCES ihb_configurations(id),
    scheme_id UUID NOT NULL REFERENCES corporate_schemes(id), -- Existing scheme!
    
    -- Role
    entity_role VARCHAR(20) NOT NULL, -- TREASURY_CENTER, SUBSIDIARY, EXTERNAL_PARTNER
    entity_code VARCHAR(20) NOT NULL,
    entity_name VARCHAR(200),
    legal_entity_id VARCHAR(50),
    country_code VARCHAR(3),
    
    -- Interest Profile
    credit_interest_rate DECIMAL(8,5),
    debit_interest_rate DECIMAL(8,5),
    spread_over_base DECIMAL(8,5),
    
    -- Limits
    max_ic_lending DECIMAL(18,2),
    max_ic_borrowing DECIMAL(18,2),
    max_netting_exposure DECIMAL(18,2),
    
    -- Netting
    netting_group VARCHAR(50),
    netting_priority INTEGER DEFAULT 100,
    
    status VARCHAR(20) DEFAULT 'ACTIVE',
    joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT uq_ihb_entity UNIQUE (ihb_id, scheme_id)
);

CREATE TABLE ihb_account_designations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    
    ihb_id UUID NOT NULL REFERENCES ihb_configurations(id),
    entity_mapping_id UUID NOT NULL REFERENCES ihb_entity_mappings(id),
    virtual_account_id UUID NOT NULL REFERENCES virtual_accounts(id), -- Existing VA!
    
    -- Designation
    designation VARCHAR(30) NOT NULL, -- OPERATING, IC_RECEIVABLE, IC_PAYABLE, 
                                       -- POOL_HEADER, POOL_MEMBER, NETTING, FX
    is_primary BOOLEAN DEFAULT FALSE,
    
    -- Interest
    interest_bearing BOOLEAN DEFAULT TRUE,
    
    -- Pool membership (if POOL_MEMBER)
    pool_header_id UUID REFERENCES virtual_accounts(id),
    
    status VARCHAR(20) DEFAULT 'ACTIVE',
    designated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT uq_ihb_va_designation UNIQUE (ihb_id, virtual_account_id, designation)
);


-- ============================================================================
-- IHB ALLOCATIONS
-- ============================================================================

CREATE TABLE ihb_allocations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    
    ihb_id UUID NOT NULL REFERENCES ihb_configurations(id),
    entity_mapping_id UUID NOT NULL REFERENCES ihb_entity_mappings(id),
    
    -- Allocation Type
    allocation_type VARCHAR(20) NOT NULL, -- CREDIT_LINE, OVERDRAFT, BUDGET, FX_LIMIT, NETTING_LIMIT
    
    -- Hierarchy (for cascading allocations)
    parent_allocation_id UUID REFERENCES ihb_allocations(id),
    
    -- Period
    period_type VARCHAR(20), -- PERMANENT, ANNUAL, QUARTERLY, MONTHLY
    period_start DATE,
    period_end DATE,
    
    -- Amounts
    allocated_amount DECIMAL(18,2) NOT NULL,
    currency_code VARCHAR(3) DEFAULT 'AED',
    utilized_amount DECIMAL(18,2) DEFAULT 0,
    available_amount DECIMAL(18,2) GENERATED ALWAYS AS (allocated_amount - utilized_amount) STORED,
    
    -- Thresholds
    warning_threshold_pct DECIMAL(5,2) DEFAULT 80,
    breach_threshold_pct DECIMAL(5,2) DEFAULT 100,
    
    -- Status
    status VARCHAR(20) DEFAULT 'ACTIVE',
    last_utilization_update TIMESTAMP,
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100)
);

CREATE INDEX idx_ihb_alloc_entity ON ihb_allocations(entity_mapping_id);
CREATE INDEX idx_ihb_alloc_type ON ihb_allocations(allocation_type);


-- ============================================================================
-- IC POSITION TRACKING
-- ============================================================================

CREATE TABLE ic_positions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    
    ihb_id UUID NOT NULL REFERENCES ihb_configurations(id),
    entity_a_id UUID NOT NULL REFERENCES ihb_entity_mappings(id),
    entity_b_id UUID NOT NULL REFERENCES ihb_entity_mappings(id),
    currency_code VARCHAR(3) NOT NULL,
    
    -- Position (positive = A owes B)
    net_position DECIMAL(18,2) DEFAULT 0,
    gross_a_to_b DECIMAL(18,2) DEFAULT 0,
    gross_b_to_a DECIMAL(18,2) DEFAULT 0,
    
    -- Interest
    accrued_interest DECIMAL(18,2) DEFAULT 0,
    last_interest_calc_date DATE,
    
    -- Settlement
    last_settlement_date DATE,
    last_settlement_amount DECIMAL(18,2),
    
    -- Timestamps
    last_movement_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT uq_ic_position UNIQUE (ihb_id, entity_a_id, entity_b_id, currency_code),
    CONSTRAINT chk_entity_order CHECK (entity_a_id < entity_b_id) -- Ensure consistent ordering
);

CREATE TABLE ic_position_movements (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    
    position_id UUID NOT NULL REFERENCES ic_positions(id),
    
    -- Movement Details
    movement_type VARCHAR(30) NOT NULL, -- POBO_PAYMENT, ROBO_COLLECTION, LOAN_DRAWDOWN,
                                        -- LOAN_REPAYMENT, INTEREST_CHARGE, NETTING_SETTLE, 
                                        -- FX_SETTLE, MANUAL_ADJUST
    
    -- Direction
    from_entity_id UUID NOT NULL REFERENCES ihb_entity_mappings(id),
    to_entity_id UUID NOT NULL REFERENCES ihb_entity_mappings(id),
    
    -- Amount
    amount DECIMAL(18,2) NOT NULL,
    currency_code VARCHAR(3) NOT NULL,
    
    -- Position snapshot
    position_before DECIMAL(18,2) NOT NULL,
    position_after DECIMAL(18,2) NOT NULL,
    
    -- Reference
    reference_type VARCHAR(30), -- TRANSACTION, LOAN, NETTING_CYCLE, FX_DEAL
    reference_id UUID,
    description TEXT,
    
    -- Timestamps
    effective_date DATE NOT NULL DEFAULT CURRENT_DATE,
    value_date DATE,
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100)
);

CREATE INDEX idx_ic_movement_position ON ic_position_movements(position_id);
CREATE INDEX idx_ic_movement_date ON ic_position_movements(effective_date);
CREATE INDEX idx_ic_movement_ref ON ic_position_movements(reference_type, reference_id);
```

---

## Summary

This redesign:

1. **Unifies Programs** - Wallet, E-commerce, Collections, Escrow all share the same foundation
2. **Makes IHB an Overlay** - Uses existing VAs and schemes instead of parallel structures
3. **Adds Allocations** - Budget control and credit limits for subsidiaries
4. **Proper IC Tracking** - Real-time positions with full audit trail
5. **Supports POBO/ROBO** - Industry-standard patterns for centralized treasury

The key insight is that **IHB doesn't need its own accounts** - it designates existing VAs 
for IHB purposes and tracks IC positions separately.
