# VAM Architecture - Comprehensive Analysis

## Reference Documents
1. **Capgemini/TietoEvry** - "The Untapped Potential of Virtual Accounts in Transaction Banking" (Dec 2020)
2. **TietoEvry VAM Documentation** - Virtual Account Management Platform
3. **Current VAM Implementation** - Our existing schema and services

---

## Part 1: Capgemini/TietoEvry Use Case Mapping

### Use Cases from Capgemini Document

| # | Use Case | Target Segment | Core Benefit |
|---|----------|----------------|--------------|
| 1 | **Automation of Account Receivables** | Global corps, Large enterprises, SMEs | Automated reconciliation, reduced manual work |
| 2 | **Virtual Cash Management (VCM)** | Global corps, Large enterprises | Centralization, POBO/ROBO, IC positions |
| 3 | **Client Money Management (CMM)** | Brokers, lawyers, real-estate, asset managers | Segregated client funds, escrow |
| 4 | **Virtual Branches** | Banks, PSPs | Cross-border access without physical presence |
| 5 | **All-in-One Cash Management** | All segments | Unified platform for all pooling methods |

### Sub-Capabilities from "All-in-One" Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     ALL-IN-ONE CASH MANAGEMENT                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐        │
│  │   Virtual   │  │   Client    │  │   Balance   │  │    Cash     │        │
│  │  Branches   │  │   Money     │  │   Netting   │  │Concentration│        │
│  │             │  │   Mgmt      │  │             │  │             │        │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘        │
│                                                                              │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                         │
│  │Intercompany │  │   Virtual   │  │  Notional   │                         │
│  │   Loans     │  │  Cash Mgmt  │  │Cash Pooling │                         │
│  │             │  │             │  │             │                         │
│  └─────────────┘  └─────────────┘  └─────────────┘                         │
│                                                                              │
│  Foundation: APIs, Single Access Mgmt, Global Network, Common Data Store    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Part 2: Mapping to Our Implementation

### Current vs Required Capabilities

| Capgemini Capability | Our Current State | Gap Analysis |
|---------------------|-------------------|--------------|
| **AR Automation** | ✅ `reconciliation_records` + VA hierarchy | Minor: Add published IBAN per invoice |
| **Virtual Cash Management** | ⚠️ Partial - VA hierarchy exists | Gap: No POBO/ROBO, limited IC tracking |
| **Client Money Management** | ✅ `escrow_contracts` + `wallet_accounts` | Minor: Merge into unified Programs |
| **Virtual Branches** | ❌ Not implemented | Gap: Need virtual branch + nostro/vostro |
| **Balance Netting** | ⚠️ `netting_cycles` exists | Gap: Not integrated with IC positions |
| **Cash Concentration** | ⚠️ Sweeping logic exists | Gap: Formalize in sweeping rules table |
| **Intercompany Loans** | ✅ `intercompany_loans` exists | Minor: Link to IC positions |
| **Notional Pooling** | ⚠️ `notional_pools` exists | Gap: Interest optimization calculation |
| **Multi-bank Overlay** | ❌ Not implemented | Gap: External bank account aggregation |

### Use Case Coverage Matrix

| Our Use Case | Capgemini Mapping | Status |
|--------------|-------------------|--------|
| **Standard Collections** | AR Automation | ✅ Covered |
| **Standard Payables** | VCM (POBO) | ⚠️ Needs POBO enhancement |
| **Digital Escrow** | Client Money Mgmt | ✅ Covered |
| **Wallet Programs** | Client Money Mgmt | ✅ Covered |
| **E-commerce VIBAN** | AR Automation (per-invoice) | ✅ Covered |
| **In-House Bank** | VCM + IC Loans + Netting + Pooling | ⚠️ Needs overlay redesign |
| **Virtual Branch** | Virtual Branches | ❌ New capability needed |

---

## Part 3: Revised Unified Architecture

Based on Capgemini's "All-in-One" approach, here's the comprehensive architecture:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        VAM UNIFIED ARCHITECTURE                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  LAYER 1: CORE FOUNDATION                                                    │
│  ════════════════════════════════════════════════════════════════════════   │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │ PHYSICAL ACCOUNTS (Core Banking - TCS BaNCS)                         │   │
│  │  • Real bank accounts with actual balances                           │   │
│  │  • Nostro/Vostro accounts for correspondent banking                  │   │
│  │  • Shadow accounts for multi-bank visibility                         │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                              ↑                                               │
│                              │ Sync                                          │
│                              ↓                                               │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │ VIRTUAL ACCOUNTS (VAM Layer)                                         │   │
│  │  • corporate_customers → corporate_schemes → virtual_accounts        │   │
│  │  • Self-service administration                                       │   │
│  │  • Unlimited hierarchy depth                                         │   │
│  │  • Published IBANs for clearing recognition                          │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  LAYER 2: PROGRAM FRAMEWORK (Unified)                                        │
│  ════════════════════════════════════════════════════════════════════════   │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │ va_programs (Universal Container)                                    │   │
│  │  ├── COLLECTIONS    - AR automation, per-payer/invoice VAs          │   │
│  │  ├── PAYABLES       - AP automation, per-vendor VAs                 │   │
│  │  ├── ECOMMERCE      - Order-specific ephemeral VIBANs               │   │
│  │  ├── WALLET         - Consumer/employee prepaid wallets             │   │
│  │  ├── ESCROW         - Client money segregation                      │   │
│  │  ├── CLIENT_MONEY   - Asset manager/broker fund segregation         │   │
│  │  └── VIRTUAL_BRANCH - Cross-border virtual presence                 │   │
│  │                                                                      │   │
│  │ program_accounts (Universal Account)                                 │   │
│  │  • Links to virtual_accounts 1:1                                     │   │
│  │  • Holder reference (optional)                                       │   │
│  │  • Expected amount for matching                                      │   │
│  │  • Auto-closure rules                                                │   │
│  │  • Webhook notifications                                             │   │
│  │                                                                      │   │
│  │ program_account_holders (Universal Holder)                           │   │
│  │  • INDIVIDUAL, CORPORATE, ANONYMOUS, SUBSIDIARY                     │   │
│  │  • KYC linkage                                                       │   │
│  │  • Cross-program identity                                            │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  LAYER 3: IHB OVERLAY (Treasury Center)                                      │
│  ════════════════════════════════════════════════════════════════════════   │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │ ihb_configurations (Policy Layer - NOT account owner)                │   │
│  │  • Interest calculation rules                                        │   │
│  │  • FX policies                                                       │   │
│  │  • Netting frequency                                                 │   │
│  │                                                                      │   │
│  │ ihb_entity_mappings (Links existing schemes to IHB)                  │   │
│  │  • Maps corporate_schemes → IHB participant role                    │   │
│  │  • TREASURY_CENTER, SUBSIDIARY, EXTERNAL_PARTNER                    │   │
│  │  • Interest profiles                                                 │   │
│  │  • Allocation limits                                                 │   │
│  │                                                                      │   │
│  │ ihb_account_designations (Designates existing VAs for IHB use)       │   │
│  │  • OPERATING, IC_TRACKING, POOL_HEADER, POOL_MEMBER                 │   │
│  │  • NO new accounts created - uses existing VAs                      │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  LAYER 4: OPERATIONS                                                         │
│  ════════════════════════════════════════════════════════════════════════   │
│  ┌───────────────┐ ┌───────────────┐ ┌───────────────┐ ┌───────────────┐   │
│  │    POBO       │ │    ROBO       │ │   NETTING     │ │   POOLING     │   │
│  │  Pay on       │ │  Receive on   │ │   Balance     │ │   Notional &  │   │
│  │  Behalf Of    │ │  Behalf Of    │ │   Settlement  │ │   Physical    │   │
│  └───────────────┘ └───────────────┘ └───────────────┘ └───────────────┘   │
│                                                                              │
│  ┌───────────────┐ ┌───────────────┐ ┌───────────────┐ ┌───────────────┐   │
│  │  IC LOANS     │ │ INTERNAL FX   │ │ ALLOCATIONS   │ │  SWEEPING     │   │
│  │  Intercompany │ │  Treasury     │ │   Budget &    │ │   Cash        │   │
│  │  Lending      │ │  FX Desk      │ │   Credit      │ │ Concentration │   │
│  └───────────────┘ └───────────────┘ └───────────────┘ └───────────────┘   │
│                                                                              │
│  LAYER 5: TRACKING & REPORTING                                               │
│  ════════════════════════════════════════════════════════════════════════   │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │ ic_positions + ic_position_movements (Real-time IC Ledger)           │   │
│  │ interest_accruals (Interest tracking)                                │   │
│  │ reconciliation_records (Payment matching)                            │   │
│  │ fx_rates (Multi-currency support)                                    │   │
│  │ account_statements (Per-VA statements)                               │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  LAYER 6: INTEGRATION                                                        │
│  ════════════════════════════════════════════════════════════════════════   │
│  ┌───────────────┐ ┌───────────────┐ ┌───────────────┐ ┌───────────────┐   │
│  │   WEBHOOKS    │ │   ERP/TMS     │ │   OPEN        │ │   SWIFT/      │   │
│  │  Real-time    │ │  Integration  │ │   BANKING     │ │   ISO20022    │   │
│  │  Notifications│ │   APIs        │ │   PSD2/APIs   │ │   Messaging   │   │
│  └───────────────┘ └───────────────┘ └───────────────┘ └───────────────┘   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Part 4: Complete Use Case Enablement Analysis

### Use Case 1: AR Automation (Collections)

**Capgemini Definition:**
> "Taking manual work out of matching remittance information with invoices. While funds are 
> received into a single physical account, incoming funds are automatically allocated to 
> individual virtual accounts or sub-ledgers."

**Our Implementation:**

| Component | Current | Required | Gap |
|-----------|---------|----------|-----|
| Per-payer VA | ✅ `account_purpose=COLLECTIONS_PAYER` | ✅ | None |
| Per-invoice VA | ✅ `account_purpose=COLLECTIONS_INVOICE` | ✅ | None |
| Published IBAN | ✅ `virtual_iban` | ✅ | None |
| Auto-allocation | ⚠️ Manual | ✅ Auto | Need allocation rules engine |
| ERP Reporting | ⚠️ Basic | ✅ Rich | Need enhanced statement format |
| Reconciliation | ✅ `reconciliation_records` | ✅ | None |

**Unified Model Mapping:**
```
va_programs (type=COLLECTIONS)
  └── program_accounts
        ├── external_reference = payer_code or invoice_number
        ├── expected_amount = invoice_amount
        └── metadata = {payer_name, invoice_date, due_date}
```

**Impact:** LOW - Existing structure fits, minor enhancements needed.

---

### Use Case 2: Virtual Cash Management (VCM)

**Capgemini Definition:**
> "Enables centralization, POBO/ROBO, intercompany positions, VA-to-VA transfers, 
> interest apportionment, and multi-bank visibility."

**Our Implementation:**

| Component | Current | Required | Gap |
|-----------|---------|----------|-----|
| VA Hierarchy | ✅ `parent_account_id` | ✅ | None |
| POBO | ❌ Not implemented | ✅ | **HIGH** - New capability |
| ROBO | ❌ Not implemented | ✅ | **HIGH** - New capability |
| IC Positions | ⚠️ Via IC loans only | ✅ Real-time | **MEDIUM** - Add `ic_positions` |
| VA-to-VA Transfer | ✅ Internal transfers | ✅ | None |
| Interest Apportionment | ⚠️ Basic | ✅ Flexible | Need configurable rules |
| Multi-bank Overlay | ❌ Not implemented | ✅ | **HIGH** - New capability |
| Self-service | ⚠️ Limited | ✅ Full | Need admin APIs |

**POBO/ROBO Model:**
```
┌─────────────────────────────────────────────────────────────────┐
│                         POBO FLOW                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  SUBSIDIARY A                 TREASURY CENTER                    │
│  (Originator)                 (Payer)                           │
│       │                            │                             │
│       │ 1. Payment request         │                             │
│       │    (vendor, amount)        │                             │
│       │ ──────────────────────────→│                             │
│       │                            │                             │
│       │                            │ 2. Execute payment          │
│       │                            │    from Treasury VA         │
│       │                            │ ──────────────────→ VENDOR  │
│       │                            │                             │
│       │ 3. Book IC payable         │                             │
│       │    Sub A owes Treasury     │                             │
│       │ ←──────────────────────────│                             │
│       │                            │                             │
│  ic_position_movements:                                          │
│  ├── movement_type: POBO_PAYMENT                                │
│  ├── from_entity: SUBSIDIARY_A                                  │
│  ├── to_entity: TREASURY_CENTER                                 │
│  └── amount: payment_amount                                     │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                         ROBO FLOW                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  CUSTOMER                     TREASURY CENTER      SUBSIDIARY B  │
│  (Payer)                      (Collector)          (Beneficiary) │
│       │                            │                    │        │
│       │ 1. Payment to              │                    │        │
│       │    Treasury VA             │                    │        │
│       │ ──────────────────────────→│                    │        │
│       │    (reference: Sub B)      │                    │        │
│       │                            │                    │        │
│       │                            │ 2. Identify Sub B  │        │
│       │                            │    via reference   │        │
│       │                            │                    │        │
│       │                            │ 3. Book IC receivable       │
│       │                            │    Treasury owes Sub B      │
│       │                            │ ──────────────────→│        │
│       │                            │                    │        │
│  ic_position_movements:                                          │
│  ├── movement_type: ROBO_COLLECTION                             │
│  ├── from_entity: TREASURY_CENTER                               │
│  ├── to_entity: SUBSIDIARY_B                                    │
│  └── amount: collection_amount                                  │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**Impact:** HIGH - Core new capability required.

---

### Use Case 3: Client Money Management (Escrow/Segregation)

**Capgemini Definition:**
> "Segregation of client funds, rapid virtual account opening, interest allocation, 
> transaction allocation with incomplete referencing."

**Our Implementation:**

| Component | Current | Required | Gap |
|-----------|---------|----------|-----|
| Fund Segregation | ✅ `escrow_contracts`, `wallet_accounts` | ✅ | None |
| Self-service VA creation | ⚠️ Limited | ✅ | Need admin APIs |
| Interest Allocation | ⚠️ Basic | ✅ Flexible | Need interest profiles |
| KYC Integration | ✅ `kycc_records` | ✅ | None |
| Transaction Allocation | ⚠️ Manual | ✅ Rule-based | Need allocation rules |
| Reporting | ⚠️ Basic | ✅ Per-client | Need enhanced statements |

**Unified Model Mapping:**
```
va_programs (type=ESCROW or type=CLIENT_MONEY)
  ├── operator_id = law_firm / broker / property_manager
  └── program_accounts
        ├── holder_id → program_account_holders (client)
        ├── external_reference = client_code
        └── metadata = {matter_reference, property_id}
```

**Impact:** LOW - Existing structure fits, unify into Programs.

---

### Use Case 4: E-commerce VIBAN

**Capgemini Alignment:**
> Part of AR Automation - "Published account references per invoice"

**Our Implementation:**

| Component | Current | Required | Gap |
|-----------|---------|----------|-----|
| Per-order IBAN | ✅ `ecommerce_virtual_ibans` | ✅ | None |
| IBAN Pool | ✅ `iban_pool` | ✅ | None |
| Auto-closure | ✅ Implemented | ✅ | None |
| Payment Matching | ✅ `ecommerce_payments` | ✅ | None |
| Webhooks | ✅ `webhook_notifications` | ✅ | None |
| Expiry Management | ✅ Implemented | ✅ | None |

**Unified Model Mapping:**
```
va_programs (type=ECOMMERCE)
  ├── operator_id = merchant
  ├── auto_close_on_payment = true
  ├── default_expiry_hours = 24
  └── program_accounts
        ├── external_reference = order_id
        ├── expected_amount = order_total
        ├── expires_at = order_expiry
        └── holder_id = NULL (anonymous payer)
```

**Impact:** LOW - Migrate to unified Programs model.

---

### Use Case 5: Wallet Programs

**Capgemini Alignment:**
> Part of Client Money Management - "Consumer/employee wallets"

**Our Implementation:**

| Component | Current | Required | Gap |
|-----------|---------|----------|-----|
| Wallet Accounts | ✅ `wallet_accounts` | ✅ | None |
| Program Config | ✅ `wallet_programs` | ✅ | None |
| Holder Identity | ✅ Via `beneficiaries` | ⚠️ | Move to `program_account_holders` |
| KYC | ✅ `kycc_records` | ✅ | None |
| Limits | ✅ Implemented | ✅ | None |
| Card Issuance | ❌ Not implemented | Optional | Future enhancement |

**Unified Model Mapping:**
```
va_programs (type=WALLET)
  ├── operator_id = fintech / employer
  ├── program_config = {kyc_level, card_enabled, limits}
  └── program_accounts
        ├── holder_id → program_account_holders (wallet owner)
        ├── account_subtype = PREPAID / POSTPAID
        └── metadata = {employee_id, department}
```

**Impact:** LOW - Migrate to unified Programs model.

---

### Use Case 6: In-House Bank (IHB)

**Capgemini Alignment:**
> VCM + Intercompany Loans + Balance Netting + Notional Pooling + Cash Concentration

**Our Implementation:**

| Component | Current | Required | Gap |
|-----------|---------|----------|-----|
| IHB Entity | ✅ `in_house_banks` | → Rename `ihb_configurations` | Slim down |
| Participants | ⚠️ `ihb_participants` owns accounts | → Maps to existing schemes | **MEDIUM** - Redesign |
| IC Loans | ✅ `intercompany_loans` | ✅ | Link to IC positions |
| Netting | ⚠️ `netting_cycles` | ✅ | Integrate with IC positions |
| Notional Pooling | ⚠️ `notional_pools` | ✅ | Calculate interest advantage |
| Internal FX | ✅ `internal_fx_transactions` | ✅ | None |
| POBO/ROBO | ❌ Not implemented | ✅ | **HIGH** - New |
| Allocations | ❌ Not implemented | ✅ | **HIGH** - New |
| IC Positions | ❌ Derived | ✅ Real-time ledger | **HIGH** - New |

**IHB as Overlay (Key Insight):**
```
BEFORE (Current - Wrong):
  ihb_participants
    ├── primary_account_id → NEW virtual_account (created)
    └── intercompany_account_id → NEW virtual_account (created)

AFTER (Redesigned - Correct):
  ihb_entity_mappings
    └── scheme_id → EXISTING corporate_schemes (reference)
  
  ihb_account_designations
    └── virtual_account_id → EXISTING virtual_accounts (reference)
```

**Impact:** HIGH - Major redesign, but cleaner architecture.

---

### Use Case 7: Virtual Branches (NEW)

**Capgemini Definition:**
> "Enabling banks to offer customers access to local payment infrastructures for transaction 
> handling (both payables and receivables), with or without a physical account in a currency."

**Our Implementation:**

| Component | Current | Required | Gap |
|-----------|---------|----------|-----|
| Virtual Branch Entity | ❌ Not implemented | ✅ | **NEW** |
| Partner Bank Linkage | ❌ Not implemented | ✅ | **NEW** |
| Nostro/Vostro Accounts | ❌ Not implemented | ✅ | **NEW** |
| Cross-border Routing | ❌ Not implemented | ✅ | **NEW** |
| Reconciliation | ⚠️ General recon exists | ✅ Nostro recon | Extend |

**Virtual Branch Model:**
```
virtual_branches
  ├── home_bank_id → corporate_customers (us)
  ├── branch_code (e.g., "VIRTUAL_LONDON")
  ├── branch_country
  ├── branch_currency
  ├── partner_bank_id (optional - for correspondent)
  ├── nostro_account_id → virtual_accounts (our account at partner)
  └── status

virtual_branch_customers
  ├── branch_id → virtual_branches
  ├── customer_scheme_id → corporate_schemes
  └── customer_accounts (shadow VAs in branch currency)
```

**Impact:** HIGH - New capability, but follows existing patterns.

---

### Use Case 8: Balance Netting

**Capgemini Alignment:**
> "Balance Netting" as separate capability in All-in-One

**Our Implementation:**

| Component | Current | Required | Gap |
|-----------|---------|----------|-----|
| Netting Cycles | ✅ `netting_cycles` | ✅ | None |
| Netting Positions | ✅ `netting_positions` | ✅ | None |
| Settlement | ⚠️ Basic | ✅ Automated | Need settlement automation |
| IC Integration | ❌ Separate | ✅ Integrated | Link to IC positions |

**Integration with IC Positions:**
```
netting_cycles
  └── on settlement:
        └── CREATE ic_position_movements (type=NETTING_SETTLE)
              ├── Clears gross positions
              └── Records net settlement
```

**Impact:** MEDIUM - Integration work needed.

---

### Use Case 9: Cash Concentration (Sweeping)

**Capgemini Alignment:**
> "Cash Concentration" as separate capability

**Our Implementation:**

| Component | Current | Required | Gap |
|-----------|---------|----------|-----|
| Sweeping Logic | ⚠️ In Java service | ✅ Configurable | Need `sweeping_rules` table |
| Target Balancing | ⚠️ Basic | ✅ Flexible | Need min/max/target config |
| Schedule | ⚠️ Hardcoded | ✅ Configurable | Need schedule config |
| Multi-currency | ⚠️ Limited | ✅ Full | Integrate with FX |

**Sweeping Rules Model:**
```
sweeping_rules
  ├── source_account_id → virtual_accounts
  ├── target_account_id → virtual_accounts
  ├── sweep_type: ZERO_BALANCE, TARGET_BALANCE, THRESHOLD
  ├── threshold_amount (for THRESHOLD type)
  ├── target_amount (for TARGET_BALANCE type)
  ├── frequency: REALTIME, HOURLY, DAILY, EOD
  ├── schedule_time
  ├── fx_enabled (for cross-currency sweeps)
  └── status
```

**Impact:** MEDIUM - New configuration table needed.

---

### Use Case 10: Notional Pooling

**Capgemini Alignment:**
> "Notional Cash Pooling" in All-in-One

**Our Implementation:**

| Component | Current | Required | Gap |
|-----------|---------|----------|-----|
| Pool Definition | ✅ `notional_pools` | ✅ | None |
| Pool Members | ✅ `notional_pool_members` | ✅ | None |
| Position Calculation | ⚠️ Basic | ✅ With interest | Need interest optimization |
| Interest Advantage | ⚠️ Concept exists | ✅ Calculated | Need proper calculation |
| Multi-currency | ⚠️ Basic | ✅ FX conversion | Integrate with fx_rates |

**Interest Advantage Calculation:**
```sql
-- Standalone interest (what each would pay/earn alone)
standalone_interest = Σ (member_balance * member_rate)

-- Pooled interest (on net position)
net_position = Σ member_balances
pooled_interest = net_position * pool_rate

-- Advantage (benefit from pooling)
interest_advantage = ABS(standalone_interest) - ABS(pooled_interest)
```

**Impact:** LOW - Enhance existing calculation.

---

## Part 5: Impact Analysis Summary

### Change Classification

| Change Type | Count | Risk |
|-------------|-------|------|
| **NEW Tables** | 8 | Medium |
| **RENAMED Tables** | 3 | Low |
| **MODIFIED Tables** | 4 | Medium |
| **DEPRECATED Tables** | 4 | Low |
| **NEW Services** | 5 | High |
| **MODIFIED Services** | 6 | Medium |

### Detailed Impact Matrix

#### Database Changes

| Table | Change | Impact | Migration Strategy |
|-------|--------|--------|-------------------|
| `va_programs` | **NEW** | High | Create + migrate from `wallet_programs`, `ecommerce_merchants` |
| `program_accounts` | **NEW** | High | Create + migrate from `wallet_accounts`, `ecommerce_virtual_ibans` |
| `program_account_holders` | **NEW** | Medium | Create + migrate from `beneficiaries` (role-based) |
| `wallet_programs` | **DEPRECATE** | Low | Migrate data → `va_programs` |
| `wallet_accounts` | **DEPRECATE** | Low | Migrate data → `program_accounts` |
| `ecommerce_merchants` | **DEPRECATE** | Low | Migrate data → `va_programs` |
| `ecommerce_virtual_ibans` | **DEPRECATE** | Low | Migrate data → `program_accounts` |
| `in_house_banks` | **RENAME** | Low | Rename → `ihb_configurations`, slim columns |
| `ihb_participants` | **REPLACE** | High | Replace with `ihb_entity_mappings` + `ihb_account_designations` |
| `ihb_entity_mappings` | **NEW** | Medium | Maps existing schemes to IHB |
| `ihb_account_designations` | **NEW** | Medium | Designates existing VAs |
| `ihb_allocations` | **NEW** | Medium | New allocation capability |
| `ic_positions` | **NEW** | High | Real-time IC balance tracking |
| `ic_position_movements` | **NEW** | High | IC ledger |
| `sweeping_rules` | **NEW** | Medium | Cash concentration config |
| `virtual_branches` | **NEW** | High | New capability |
| `virtual_accounts` | **MODIFY** | Low | Add `program_account_id` FK |
| `beneficiaries` | **MODIFY** | Low | Keep as external recipients only |

#### Service Changes

| Service | Change | Impact | Notes |
|---------|--------|--------|-------|
| `ProgramService` | **NEW** | High | Unified program management |
| `ProgramAccountService` | **NEW** | High | Unified account lifecycle |
| `POBOService` | **NEW** | High | Pay-on-behalf-of |
| `ROBOService` | **NEW** | High | Receive-on-behalf-of |
| `ICPositionService` | **NEW** | High | IC position management |
| `EcommerceIbanService` | **DEPRECATE** | Low | Move logic to ProgramAccountService |
| `WalletService` | **DEPRECATE** | Low | Move logic to ProgramAccountService |
| `InHouseBankService` | **MODIFY** | High | Use overlay model |
| `NettingService` | **MODIFY** | Medium | Integrate with IC positions |
| `SweepingService` | **MODIFY** | Medium | Use sweeping_rules table |
| `LiquidityManagementService` | **MODIFY** | Medium | Enhanced aggregation |
| `ReconciliationService` | **MODIFY** | Low | Support ROBO |

#### API Changes

| Endpoint Pattern | Change | Breaking? | Notes |
|-----------------|--------|-----------|-------|
| `/api/v1/programs/**` | **NEW** | No | Unified program APIs |
| `/api/v1/programs/{id}/accounts/**` | **NEW** | No | Program account APIs |
| `/api/v1/ihb/{id}/pobo/**` | **NEW** | No | POBO operations |
| `/api/v1/ihb/{id}/robo/**` | **NEW** | No | ROBO operations |
| `/api/v1/ihb/{id}/ic-positions/**` | **NEW** | No | IC position queries |
| `/api/v1/ecommerce/**` | **DEPRECATE** | Soft | Redirect to programs |
| `/api/v1/wallets/**` | **DEPRECATE** | Soft | Redirect to programs |

---

## Part 6: Migration Approach

### Phase 1: Foundation (Week 1-2)
1. Create new unified tables (`va_programs`, `program_accounts`, `program_account_holders`)
2. Create IHB overlay tables (`ihb_entity_mappings`, `ihb_account_designations`)
3. Create IC tracking tables (`ic_positions`, `ic_position_movements`)
4. Keep old tables operational (dual-write)

### Phase 2: Data Migration (Week 3-4)
1. Migrate `wallet_programs` → `va_programs`
2. Migrate `ecommerce_merchants` → `va_programs`
3. Migrate `wallet_accounts` → `program_accounts`
4. Migrate `ecommerce_virtual_ibans` → `program_accounts`
5. Create `ihb_entity_mappings` from `ihb_participants`
6. Create `ihb_account_designations` from existing VA relationships

### Phase 3: Service Layer (Week 5-6)
1. Implement `ProgramService` and `ProgramAccountService`
2. Implement `POBOService` and `ROBOService`
3. Implement `ICPositionService`
4. Update `InHouseBankService` to use overlay model
5. Update existing services for dual-read

### Phase 4: API Layer (Week 7-8)
1. Implement new unified APIs
2. Implement POBO/ROBO APIs
3. Add deprecation headers to old APIs
4. Create API migration guide

### Phase 5: Cutover (Week 9-10)
1. Switch reads to new tables
2. Verify data consistency
3. Remove dual-write
4. Deprecation notices for old APIs

---

## Part 7: Final Architecture Comparison

### Before (Current)

```
┌─────────────────────────────────────────────────────────────────┐
│  FRAGMENTED MODEL                                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  wallet_programs ──→ wallet_accounts ──→ beneficiaries          │
│                            ↓                                     │
│  ecommerce_merchants ──→ ecommerce_virtual_ibans                │
│                            ↓                                     │
│  in_house_banks ──→ ihb_participants ──→ NEW virtual_accounts   │
│                            ↓                                     │
│  escrow_contracts ──→ virtual_accounts ──→ beneficiaries        │
│                                                                  │
│  Problems:                                                       │
│  • 4 different account models                                   │
│  • IHB creates parallel VA structure                            │
│  • No unified holder concept                                    │
│  • No POBO/ROBO                                                 │
│  • No real-time IC positions                                    │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### After (Redesigned)

```
┌─────────────────────────────────────────────────────────────────┐
│  UNIFIED MODEL (Capgemini/TietoEvry Aligned)                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  corporate_customers                                             │
│       │                                                          │
│       ├──→ corporate_schemes                                    │
│       │       │                                                  │
│       │       └──→ virtual_accounts (CORE)                      │
│       │             │                                            │
│       │             ├── 1:1 program_accounts (if in program)    │
│       │             │     │                                      │
│       │             │     └── program_account_holders            │
│       │             │                                            │
│       │             └── ihb_account_designations (IHB overlay)  │
│       │                                                          │
│       ├──→ va_programs (WALLET, ECOMMERCE, ESCROW, etc.)        │
│       │                                                          │
│       ├──→ ihb_configurations (policies only)                   │
│       │       │                                                  │
│       │       └──→ ihb_entity_mappings (to existing schemes)    │
│       │                                                          │
│       └──→ beneficiaries (EXTERNAL recipients only)             │
│                                                                  │
│  IHB Operations (on existing VAs):                               │
│  ├── ic_positions + ic_position_movements (real-time ledger)   │
│  ├── POBO/ROBO (centralized treasury)                          │
│  ├── ihb_allocations (budget control)                          │
│  └── sweeping_rules (cash concentration)                        │
│                                                                  │
│  Benefits:                                                       │
│  ✅ Single program model for all use cases                      │
│  ✅ IHB uses existing VAs (no parallel structure)              │
│  ✅ Unified holder identity                                     │
│  ✅ POBO/ROBO enabled                                          │
│  ✅ Real-time IC positions                                      │
│  ✅ Capgemini/TietoEvry aligned                                │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## Part 8: Capgemini Use Case Checklist

| Use Case | Capgemini Requirement | Our Solution | Status |
|----------|----------------------|--------------|--------|
| **AR Automation** | Per-payer/invoice VAs, auto-allocation | `program_accounts` with matching | ✅ |
| **VCM** | POBO, ROBO, IC positions, VA-VA transfers | `POBOService`, `ROBOService`, `ic_positions` | ✅ |
| **CMM/Escrow** | Segregated funds, interest allocation | `va_programs` (type=ESCROW/CLIENT_MONEY) | ✅ |
| **Virtual Branches** | Cross-border without physical account | `virtual_branches` table | ✅ |
| **Balance Netting** | IC netting with settlement | `netting_cycles` + IC integration | ✅ |
| **Cash Concentration** | Sweeping to target/zero balance | `sweeping_rules` table | ✅ |
| **IC Loans** | Intercompany lending | `intercompany_loans` + IC tracking | ✅ |
| **Notional Pooling** | Interest optimization | `notional_pools` + advantage calc | ✅ |
| **Self-service** | Customer VA administration | Admin APIs + webhooks | ✅ |
| **Multi-bank** | External account visibility | Shadow accounts + Open Banking | ⚠️ Future |
| **Real-time** | Instant visibility and reporting | Event-driven + webhooks | ✅ |
| **ERP Integration** | Statement delivery, reconciliation | Enhanced statements + APIs | ✅ |

---

## Conclusion

The redesigned architecture:

1. **Aligns with Capgemini/TietoEvry** "All-in-One Cash Management" approach
2. **Unifies all program types** under single `va_programs` model
3. **Makes IHB an overlay** that uses existing VAs (not parallel structure)
4. **Enables POBO/ROBO** for centralized treasury operations
5. **Adds proper IC tracking** with real-time positions
6. **Supports all use cases** from our original requirements

The migration is significant but follows a phased approach to minimize risk.
