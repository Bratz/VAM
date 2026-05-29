# VAM System - Virtual Account Linkages & Beneficiary Design Analysis

## Executive Summary

This document provides a comprehensive analysis of how Virtual Accounts (VA) are linked across different use cases and how beneficiary data is structured and utilized in the VAM system.

---

## 1. Core Entity Relationship Overview

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           CORPORATE CUSTOMER                                     │
│                     (corporate_customers table)                                  │
│                              ↓ owns                                              │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│    ┌──────────────┐     ┌──────────────┐     ┌──────────────┐                  │
│    │   SCHEME     │     │ BENEFICIARY  │     │    IHB       │                  │
│    │   (1..n)     │     │   (1..n)     │     │   (0..1)     │                  │
│    └──────┬───────┘     └──────┬───────┘     └──────┬───────┘                  │
│           │                    │                    │                           │
│           ↓                    │                    ↓                           │
│    ┌──────────────┐            │            ┌──────────────┐                    │
│    │ VIRTUAL      │←───────────┼────────────│ IHB          │                    │
│    │ ACCOUNTS     │            │            │ PARTICIPANT  │                    │
│    │ (1..n)       │            │            │ (1..n)       │                    │
│    └──────────────┘            │            └──────────────┘                    │
│           ↑                    │                                                │
│           │                    │                                                │
│    ┌──────┴──────────────────┬┴─────────────────────────────┐                  │
│    │                         │                               │                  │
│    ↓                         ↓                               ↓                  │
│  ESCROW                   WALLET                         E-COMMERCE             │
│  CONTRACT                 ACCOUNT                        VIBAN                  │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Virtual Account Linkages by Use Case

### 2.1 Standard Collections/Payables (Core Use Case)

```
┌─────────────────────────────────────────────────────────────────┐
│                    STANDARD VA STRUCTURE                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  corporate_customers                                             │
│       │                                                          │
│       ├──→ corporate_schemes (1..n)                             │
│       │         │                                                │
│       │         └──→ virtual_accounts (1..n)                    │
│       │                   │                                      │
│       │                   ├── account_type: STANDARD            │
│       │                   ├── account_purpose: COLLECTIONS_PAYER│
│       │                   │                    COLLECTIONS_INVOICE│
│       │                   │                    PAYABLES_VENDOR   │
│       │                   │                    PAYABLES_CATEGORY │
│       │                   │                    TREASURY_POOLING  │
│       │                   ├── parent_account_id (hierarchy)     │
│       │                   └── hierarchy_level: 0,1,2...         │
│       │                                                          │
│       └──→ beneficiaries (1..n)                                 │
│                 │                                                │
│                 ├── beneficiary_role: STANDARD                  │
│                 └── kycc_id: NULL (no KYCC required)            │
│                                                                  │
│  TRANSACTIONS:                                                   │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ transactions                                                │ │
│  │   ├── debtor_account_id → virtual_accounts.id              │ │
│  │   ├── beneficiary_id → beneficiaries.id (optional)         │ │
│  │   ├── beneficiary_account (always populated)               │ │
│  │   └── transaction_context: STANDARD                        │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  WHITELISTING:                                                   │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ whitelisted_accounts                                        │ │
│  │   ├── virtual_account_id → virtual_accounts.id             │ │
│  │   └── whitelisted_account_reference (IBAN)                 │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**Key Relationships:**
| From | To | Relationship | Purpose |
|------|-----|--------------|---------|
| `virtual_accounts` | `corporate_schemes` | N:1 | VA belongs to a scheme |
| `virtual_accounts` | `corporate_customers` | N:1 | VA owned by customer |
| `virtual_accounts` | `virtual_accounts` | N:1 | Parent-child hierarchy |
| `transactions` | `virtual_accounts` | N:1 | Debtor account |
| `transactions` | `beneficiaries` | N:1 (optional) | Registered beneficiary |
| `whitelisted_accounts` | `virtual_accounts` | N:1 | Whitelist for VA |

**Beneficiary Data Flow:**
- Beneficiaries are registered at **customer level** (not VA level)
- Transactions can reference a registered `beneficiary_id` OR just use inline beneficiary data
- Whitelist restricts which accounts a VA can pay to

---

### 2.2 Digital Escrow

```
┌─────────────────────────────────────────────────────────────────┐
│                    ESCROW VA STRUCTURE                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  PARTIES:                                                        │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │                                                             │ │
│  │  corporate_customers (SELLER)                              │ │
│  │       │                                                     │ │
│  │       └──→ escrow_contracts.seller_id                      │ │
│  │                                                             │ │
│  │  beneficiaries (BUYER)                                     │ │
│  │       │                                                     │ │
│  │       ├── beneficiary_role: BUYER                          │ │
│  │       ├── kycc_id → kycc_records.id (REQUIRED)             │ │
│  │       └──→ escrow_contracts.buyer_id                       │ │
│  │                                                             │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  ESCROW ACCOUNT:                                                 │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ escrow_contracts                                            │ │
│  │   ├── seller_id → corporate_customers.id                   │ │
│  │   ├── buyer_id → beneficiaries.id (with KYCC)              │ │
│  │   └── escrow_account_id → virtual_accounts.id              │ │
│  │                                                             │ │
│  │ virtual_accounts (ESCROW)                                   │ │
│  │   ├── account_type: ESCROW                                 │ │
│  │   ├── account_purpose: ESCROW                              │ │
│  │   └── customer_id → seller (corporate)                     │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  FUND FLOW:                                                      │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │                                                             │ │
│  │  BUYER ────deposit────→ ESCROW_VA ────release────→ SELLER  │ │
│  │   │                        │                         │      │ │
│  │   │                        │                         │      │ │
│  │   beneficiaries.          virtual_accounts.     corporate_  │ │
│  │   beneficiary_account     virtual_iban          customers   │ │
│  │                                                             │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  MILESTONES:                                                     │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ escrow_milestones                                           │ │
│  │   ├── contract_id → escrow_contracts.id                    │ │
│  │   ├── release_amount                                       │ │
│  │   └── status: PENDING → APPROVED → RELEASED                │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**Key Relationships:**
| From | To | Relationship | Purpose |
|------|-----|--------------|---------|
| `escrow_contracts` | `virtual_accounts` | 1:1 | Escrow holds funds |
| `escrow_contracts` | `corporate_customers` | N:1 | Seller party |
| `escrow_contracts` | `beneficiaries` | N:1 | Buyer party |
| `beneficiaries` | `kycc_records` | N:1 | KYC for buyer |
| `escrow_milestones` | `escrow_contracts` | N:1 | Release schedule |

**Beneficiary as Buyer:**
- Buyer is a **beneficiary** with `beneficiary_role = 'BUYER'`
- **KYCC record is REQUIRED** for escrow buyers
- Buyer's bank account is in `beneficiaries.beneficiary_account`
- Escrow VA is owned by the **seller** (corporate customer)

---

### 2.3 Wallet Programs

```
┌─────────────────────────────────────────────────────────────────┐
│                    WALLET VA STRUCTURE                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  OPERATOR (Corporate):                                           │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ corporate_customers (WALLET OPERATOR)                       │ │
│  │       │                                                     │ │
│  │       └──→ wallet_programs.operator_id                     │ │
│  │             │                                               │ │
│  │             └── program_code, program_name                 │ │
│  │             └── limits (daily, monthly, balance)           │ │
│  │             └── fee structure                              │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  WALLET HOLDER (Beneficiary):                                    │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ beneficiaries (WALLET HOLDER)                               │ │
│  │       │                                                     │ │
│  │       ├── beneficiary_role: WALLET_HOLDER                  │ │
│  │       ├── kycc_id → kycc_records.id (REQUIRED for KYC)     │ │
│  │       └──→ wallet_accounts.holder_id                       │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  WALLET ACCOUNT STRUCTURE:                                       │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │                                                             │ │
│  │  wallet_programs                                            │ │
│  │       │                                                     │ │
│  │       └──→ wallet_accounts (1..n)                          │ │
│  │             │                                               │ │
│  │             ├── program_id → wallet_programs.id            │ │
│  │             ├── virtual_account_id → virtual_accounts.id   │ │
│  │             ├── holder_id → beneficiaries.id               │ │
│  │             └── balance, limits, usage tracking            │ │
│  │                                                             │ │
│  │  virtual_accounts (WALLET)                                  │ │
│  │       │                                                     │ │
│  │       ├── account_type: WALLET                             │ │
│  │       └── 1:1 with wallet_accounts                         │ │
│  │                                                             │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  WALLET TRANSACTIONS:                                            │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ wallet_transactions                                         │ │
│  │   ├── wallet_id → wallet_accounts.id                       │ │
│  │   ├── transaction_type: LOAD, PURCHASE, TRANSFER, etc.     │ │
│  │   └── merchant_category_code (for purchases)               │ │
│  │                                                             │ │
│  │ (Also links to main transactions table for fund movements) │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**Key Relationships:**
| From | To | Relationship | Purpose |
|------|-----|--------------|---------|
| `wallet_programs` | `corporate_customers` | N:1 | Operator owns program |
| `wallet_programs` | `corporate_schemes` | N:1 | Scheme for VAs |
| `wallet_accounts` | `wallet_programs` | N:1 | Account in program |
| `wallet_accounts` | `virtual_accounts` | 1:1 | Published IBAN |
| `wallet_accounts` | `beneficiaries` | N:1 | Wallet holder |
| `beneficiaries` | `kycc_records` | N:1 | KYC for holder |

**Beneficiary as Wallet Holder:**
- Holder is a **beneficiary** with `beneficiary_role = 'WALLET_HOLDER'`
- KYCC record required for KYC compliance
- Each wallet account has a dedicated `virtual_account` (1:1)
- The VA's IBAN is the wallet's "published account"

---

### 2.4 E-commerce Virtual IBAN

```
┌─────────────────────────────────────────────────────────────────┐
│                 E-COMMERCE VIBAN STRUCTURE                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  MERCHANT (Corporate):                                           │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ corporate_customers                                         │ │
│  │       │                                                     │ │
│  │       └──→ ecommerce_merchants                             │ │
│  │             │                                               │ │
│  │             ├── customer_id → corporate_customers.id       │ │
│  │             ├── scheme_id → corporate_schemes.id           │ │
│  │             ├── settlement_account_id → virtual_accounts.id│ │
│  │             └── webhook_url, limits, closure rules         │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  ORDER-SPECIFIC IBAN:                                            │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ ecommerce_virtual_ibans                                     │ │
│  │       │                                                     │ │
│  │       ├── merchant_id → ecommerce_merchants.id             │ │
│  │       ├── virtual_account_id → virtual_accounts.id         │ │
│  │       ├── order_reference (unique per merchant)            │ │
│  │       ├── expected_amount                                  │ │
│  │       ├── expires_at                                       │ │
│  │       └── metadata (JSONB - buyer email, product, etc.)    │ │
│  │                                                             │ │
│  │ virtual_accounts (ECOMMERCE)                                │ │
│  │       │                                                     │ │
│  │       ├── account_type: ECOMMERCE                          │ │
│  │       ├── account_purpose: ECOMMERCE_ORDER                 │ │
│  │       ├── ecommerce_iban_id → ecommerce_virtual_ibans.id   │ │
│  │       └── status: 1 (active) → 0 (closed after payment)    │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  PAYER (Anonymous - NOT a Beneficiary):                          │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │                                                             │ │
│  │  *** PAYERS ARE NOT STORED AS BENEFICIARIES ***            │ │
│  │                                                             │ │
│  │  ecommerce_payments                                         │ │
│  │       │                                                     │ │
│  │       ├── payer_name (from payment message)                │ │
│  │       ├── payer_account (from payment message)             │ │
│  │       ├── payer_bank_bic                                   │ │
│  │       └── e2e_reference, uetr, remittance_info             │ │
│  │                                                             │ │
│  │  Payer data is TRANSACTIONAL, not master data              │ │
│  │                                                             │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  IBAN POOL:                                                      │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ iban_pool                                                   │ │
│  │       │                                                     │ │
│  │       ├── merchant_id → ecommerce_merchants.id             │ │
│  │       ├── virtual_iban (pre-generated)                     │ │
│  │       ├── status: AVAILABLE → ALLOCATED → AVAILABLE        │ │
│  │       └── allocated_to → ecommerce_virtual_ibans.id        │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**Key Relationships:**
| From | To | Relationship | Purpose |
|------|-----|--------------|---------|
| `ecommerce_merchants` | `corporate_customers` | N:1 | Merchant owns |
| `ecommerce_merchants` | `virtual_accounts` | N:1 | Settlement account |
| `ecommerce_virtual_ibans` | `ecommerce_merchants` | N:1 | IBAN for merchant |
| `ecommerce_virtual_ibans` | `virtual_accounts` | 1:1 | IBAN's VA |
| `ecommerce_payments` | `ecommerce_virtual_ibans` | N:1 | Payments received |
| `iban_pool` | `ecommerce_merchants` | N:1 | Pre-generated IBANs |

**NO Beneficiary for Payers:**
- E-commerce payers are **anonymous** - they are NOT stored in beneficiaries
- Payer information is captured in `ecommerce_payments` table only
- This is because payers are one-time, unknown parties paying into a specific IBAN
- Buyer metadata (email, name) can be stored in `metadata` JSONB field

---

### 2.5 In-House Bank (IHB)

```
┌─────────────────────────────────────────────────────────────────┐
│                    IHB VA STRUCTURE                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  PARENT CORPORATE (IHB Owner):                                   │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ corporate_customers (PARENT COMPANY)                        │ │
│  │       │                                                     │ │
│  │       └──→ in_house_banks                                  │ │
│  │             │                                               │ │
│  │             ├── customer_id → corporate_customers.id       │ │
│  │             ├── base_currency                              │ │
│  │             ├── pooling_type: NOTIONAL, PHYSICAL, HYBRID   │ │
│  │             └── interest & FX configuration                │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  PARTICIPANTS (Subsidiaries):                                    │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ ihb_participants                                            │ │
│  │       │                                                     │ │
│  │       ├── ihb_id → in_house_banks.id                       │ │
│  │       ├── scheme_id → corporate_schemes.id                 │ │
│  │       ├── primary_account_id → virtual_accounts.id         │ │
│  │       ├── intercompany_account_id → virtual_accounts.id    │ │
│  │       ├── participant_role: HEADER, PARTICIPANT            │ │
│  │       └── interest rates, limits                           │ │
│  │                                                             │ │
│  │ *** EACH PARTICIPANT HAS ITS OWN SCHEME ***                │ │
│  │ *** SUBSIDIARIES ARE NOT BENEFICIARIES ***                 │ │
│  │                                                             │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  PARTICIPANT ACCOUNTS:                                           │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │                                                             │ │
│  │  virtual_accounts (IHB_OPERATING)                           │ │
│  │       │                                                     │ │
│  │       ├── account_type: IHB_OPERATING                      │ │
│  │       ├── ihb_participant_id → ihb_participants.id         │ │
│  │       └── linked to participant's primary_account_id       │ │
│  │                                                             │ │
│  │  virtual_accounts (IHB_INTERCOMPANY)                        │ │
│  │       │                                                     │ │
│  │       ├── account_type: IHB_INTERCOMPANY                   │ │
│  │       └── for IC receivables/payables tracking             │ │
│  │                                                             │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  INTERCOMPANY LOANS:                                             │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ intercompany_loans                                          │ │
│  │       │                                                     │ │
│  │       ├── ihb_id → in_house_banks.id                       │ │
│  │       ├── lender_id → ihb_participants.id                  │ │
│  │       ├── borrower_id → ihb_participants.id                │ │
│  │       ├── lender_account_id → virtual_accounts.id          │ │
│  │       ├── borrower_account_id → virtual_accounts.id        │ │
│  │       └── principal, interest, schedule                    │ │
│  │                                                             │ │
│  │ *** LENDER & BORROWER ARE PARTICIPANTS, NOT BENEFICIARIES *│ │
│  │                                                             │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  NOTIONAL POOLS:                                                 │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ notional_pools                                              │ │
│  │       │                                                     │ │
│  │       └──→ notional_pool_members                           │ │
│  │             │                                               │ │
│  │             ├── pool_id → notional_pools.id                │ │
│  │             ├── participant_id → ihb_participants.id       │ │
│  │             ├── account_id → virtual_accounts.id           │ │
│  │             └── balance, interest tracking                 │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  INTERNAL FX:                                                    │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ internal_fx_transactions                                    │ │
│  │       │                                                     │ │
│  │       ├── requesting_participant_id → ihb_participants.id  │ │
│  │       ├── sell_account_id → virtual_accounts.id            │ │
│  │       └── buy_account_id → virtual_accounts.id             │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**Key Relationships:**
| From | To | Relationship | Purpose |
|------|-----|--------------|---------|
| `in_house_banks` | `corporate_customers` | N:1 | Parent owns IHB |
| `ihb_participants` | `in_house_banks` | N:1 | Participant in IHB |
| `ihb_participants` | `corporate_schemes` | N:1 | Subsidiary's scheme |
| `ihb_participants` | `virtual_accounts` | 1:1 | Primary account |
| `ihb_participants` | `virtual_accounts` | 1:1 | IC account |
| `intercompany_loans` | `ihb_participants` | N:1 | Lender |
| `intercompany_loans` | `ihb_participants` | N:1 | Borrower |
| `notional_pool_members` | `virtual_accounts` | N:1 | Pool member's VA |

**NO Beneficiary for Subsidiaries:**
- IHB participants (subsidiaries) are NOT beneficiaries
- They have their own `corporate_schemes` and `virtual_accounts`
- Fund transfers are internal (participant-to-participant)
- Uses `ihb_participants` table instead of beneficiaries

---

## 3. Beneficiary Usage Summary

### 3.1 When Beneficiaries ARE Used

| Use Case | Beneficiary Role | KYCC Required | Purpose |
|----------|------------------|---------------|---------|
| **Standard Payments** | STANDARD | No | External payment recipients |
| **Escrow (Buyer)** | BUYER | **Yes** | Buyer party in escrow |
| **Wallet (Holder)** | WALLET_HOLDER | **Yes** | Wallet holder identity |

### 3.2 When Beneficiaries are NOT Used

| Use Case | Why Not | Alternative |
|----------|---------|-------------|
| **E-commerce Payers** | Anonymous, one-time | `ecommerce_payments.payer_*` fields |
| **IHB Participants** | Internal subsidiaries | `ihb_participants` table |
| **Internal Transfers** | Same customer | Direct VA-to-VA |
| **Sweeping/Pooling** | Internal accounts | Parent-child VA hierarchy |

---

## 4. Virtual Account Type Matrix

| Account Type | Purpose | Linked To | Beneficiary? |
|--------------|---------|-----------|--------------|
| `STANDARD` | General purpose | `corporate_schemes` | No |
| `ESCROW` | Escrow holding | `escrow_contracts` | Buyer (beneficiary) |
| `WALLET` | Wallet account | `wallet_accounts` | Holder (beneficiary) |
| `ECOMMERCE` | Order-specific | `ecommerce_virtual_ibans` | No (payer is inline) |
| `IHB_OPERATING` | IHB participant | `ihb_participants` | No |
| `IHB_INTERCOMPANY` | IC tracking | `ihb_participants` | No |
| `IHB_POOL` | Pool header | `notional_pools` | No |

---

## 5. Account Purpose Enumeration

```sql
-- From V2 migration
COMMENT ON COLUMN virtual_accounts.account_purpose IS 
'GENERAL, COLLECTIONS_PAYER, COLLECTIONS_INVOICE, 
 PAYABLES_VENDOR, PAYABLES_CATEGORY, TREASURY_POOLING, 
 ESCROW, WALLET, ECOMMERCE_ORDER';
```

| Purpose | Use Case | Typical account_type |
|---------|----------|---------------------|
| `GENERAL` | Multi-purpose | STANDARD |
| `COLLECTIONS_PAYER` | Per-payer VA | STANDARD |
| `COLLECTIONS_INVOICE` | Per-invoice VA | STANDARD |
| `PAYABLES_VENDOR` | Per-vendor VA | STANDARD |
| `PAYABLES_CATEGORY` | Category grouping | STANDARD |
| `TREASURY_POOLING` | Cash pooling | STANDARD |
| `ESCROW` | Escrow holding | ESCROW |
| `WALLET` | Prepaid wallet | WALLET |
| `ECOMMERCE_ORDER` | Order payment | ECOMMERCE |

---

## 6. Gap Analysis & Recommendations

### 6.1 Current Gaps

| Gap | Impact | Recommendation |
|-----|--------|----------------|
| No VA-level beneficiary restriction | Any beneficiary can be paid from any VA | Add `va_beneficiary_mapping` table |
| E-commerce has no buyer registration | Cannot track repeat buyers | Add optional `buyer_id` to `ecommerce_virtual_ibans` |
| IHB has no external counterparty | Cannot track external IC partners | Add `ihb_external_parties` table |
| No beneficiary for reconciliation | Cannot auto-match to beneficiary | Add `expected_beneficiary_id` to reconciliation |

### 6.2 Proposed Enhancements

```sql
-- 6.2.1 VA-Beneficiary Mapping (Restrict which beneficiaries a VA can pay)
CREATE TABLE va_beneficiary_mappings (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    virtual_account_id UUID NOT NULL REFERENCES virtual_accounts(id),
    beneficiary_id UUID NOT NULL REFERENCES beneficiaries(id),
    status VARCHAR(20) DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(virtual_account_id, beneficiary_id)
);

-- 6.2.2 E-commerce Buyer Registration (Optional)
ALTER TABLE ecommerce_virtual_ibans 
ADD COLUMN registered_buyer_id UUID REFERENCES beneficiaries(id);

-- 6.2.3 Reconciliation Expected Beneficiary
ALTER TABLE reconciliation_records
ADD COLUMN expected_beneficiary_id UUID REFERENCES beneficiaries(id);
```

---

## 7. Data Flow Diagrams

### 7.1 Payment Initiation Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                                                                  │
│  User Request:                                                   │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ {                                                         │   │
│  │   "debtor_account_iban": "AE12...",                      │   │
│  │   "beneficiary_id": "uuid" OR "beneficiary_account": "", │   │
│  │   "amount": 1000,                                         │   │
│  │   "purpose": "TRADE"                                      │   │
│  │ }                                                         │   │
│  └──────────────────────────────────────────────────────────┘   │
│                           │                                      │
│                           ↓                                      │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ VALIDATION                                                │   │
│  │ 1. Lookup VA by IBAN → virtual_accounts                  │   │
│  │ 2. If beneficiary_id:                                    │   │
│  │    - Lookup beneficiary → beneficiaries                  │   │
│  │    - Verify beneficiary.customer_id = VA.customer_id     │   │
│  │ 3. If whitelist enabled:                                 │   │
│  │    - Check whitelisted_accounts for VA                   │   │
│  └──────────────────────────────────────────────────────────┘   │
│                           │                                      │
│                           ↓                                      │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ TRANSACTION CREATION                                      │   │
│  │ transactions.debtor_account_id = VA.id                   │   │
│  │ transactions.beneficiary_id = (if registered)            │   │
│  │ transactions.beneficiary_account = (always set)          │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 7.2 Collection Reconciliation Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                                                                  │
│  Incoming Payment (SWIFT/ACH):                                   │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ {                                                         │   │
│  │   "creditor_iban": "AE12...",   (Our VA)                 │   │
│  │   "debtor_name": "Acme Corp",                            │   │
│  │   "debtor_account": "DE89...",                           │   │
│  │   "amount": 5000,                                         │   │
│  │   "reference": "INV-2024-001"                            │   │
│  │ }                                                         │   │
│  └──────────────────────────────────────────────────────────┘   │
│                           │                                      │
│                           ↓                                      │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ MATCHING LOGIC                                            │   │
│  │                                                           │   │
│  │ 1. Lookup VA by creditor_iban → virtual_accounts         │   │
│  │                                                           │   │
│  │ 2. Determine account_purpose:                            │   │
│  │    - COLLECTIONS_PAYER: Match debtor_account to payer    │   │
│  │    - COLLECTIONS_INVOICE: Match reference to invoice     │   │
│  │    - ECOMMERCE_ORDER: Match to ecommerce_virtual_ibans   │   │
│  │                                                           │   │
│  │ 3. Create reconciliation_record with match_status        │   │
│  │                                                           │   │
│  │ *** PAYER IS NOT NECESSARILY A BENEFICIARY ***           │   │
│  │ (Payers are customers of our corporate, not payees)      │   │
│  │                                                           │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 8. Summary Table

| Entity | Links to VA? | Links to Beneficiary? | Purpose |
|--------|--------------|----------------------|---------|
| `corporate_schemes` | Owner | - | Scheme owns VAs |
| `escrow_contracts` | Holds funds | Buyer | Escrow management |
| `wallet_accounts` | 1:1 | Holder | Wallet operations |
| `ecommerce_virtual_ibans` | 1:1 | - | Order-specific IBAN |
| `ecommerce_payments` | Via IBAN | - | Payer data (inline) |
| `ihb_participants` | 1:2 (primary+IC) | - | IHB subsidiary |
| `intercompany_loans` | Lender+Borrower VAs | - | IC lending |
| `notional_pool_members` | Pool member VA | - | Pool position |
| `transactions` | Debtor VA | Creditor (optional) | Payment |
| `reconciliation_records` | Collection VA | - | Matching |
| `whitelisted_accounts` | Restricted VA | - | Payment control |

---

## Appendix: Entity Count Summary

| Entity | Expected Volume | Notes |
|--------|----------------|-------|
| `corporate_customers` | 100-1,000 | B2B customers |
| `corporate_schemes` | 500-5,000 | ~5 per customer |
| `virtual_accounts` | 50,000-500,000 | ~100 per scheme |
| `beneficiaries` | 10,000-100,000 | ~100 per customer |
| `transactions` | 1M-100M/year | High volume |
| `ecommerce_virtual_ibans` | 100K-10M/year | Per order |
| `wallet_accounts` | 10,000-1M | Consumer wallets |
| `ihb_participants` | 100-1,000 | Large corporate subsidiaries |
