# VAM Transaction Model - Single-Entry Per Virtual Account

## Core Principle (from TCS BaNCS API)

**Every transaction in VAM is recorded as a SINGLE-ENTRY movement per Virtual Account.**

When a transfer occurs between two VAs:
- **Source VA**: Records a DEBIT movement (negative)
- **Target VA**: Records a CREDIT movement (positive)

This creates TWO movement records - one for each VA involved.

---

## 1. BaNCS API Evidence

From `Virtual Account Transaction Statement` API response:

```json
{
  "vaMovements": [
    {
      "movementId": 1763,
      "transactionAmount": 3.1,
      "transactionCurrency": "AED",
      "creditAmount": 3.1,        // OR debitAmount - mutually exclusive
      "debitAmount": null,
      "balance": 3.1,             // Running balance AFTER this movement
      "transactionDate": "20250409",
      "valueDate": "20250409",
      "movementDescription": "Booking",
      "settlementAccount": "AE871323001099650000001"
    }
  ]
}
```

Key observations:
- Each movement has a unique `movementId` per VA
- `creditAmount` OR `debitAmount` is populated (single-entry)
- `balance` shows running balance after movement
- Statement is per-VA (accountReference in request)

---

## 2. Corrected Data Model

### 2.1 VA Movements Table (Core Transaction Ledger)

```sql
-- ============================================================================
-- VA_MOVEMENTS: Single-entry ledger per Virtual Account
-- ============================================================================
-- This is the PRIMARY transaction record for VAM
-- Each VA records its own movements (debits and credits separately)
-- A VA-to-VA transfer creates TWO movement records

CREATE TABLE va_movements (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    
    -- Movement Identity
    movement_id BIGSERIAL NOT NULL,  -- Sequential per VA for statement ordering
    movement_reference VARCHAR(50) NOT NULL UNIQUE,
    
    -- Virtual Account (THIS movement belongs to)
    virtual_account_id UUID NOT NULL REFERENCES virtual_accounts(id),
    
    -- Movement Type (Single Entry)
    movement_type VARCHAR(20) NOT NULL,
    -- CREDIT: Incoming funds (positive)
    -- DEBIT: Outgoing funds (negative)
    
    -- Amount (always positive, type determines direction)
    amount DECIMAL(18,2) NOT NULL,
    currency_code VARCHAR(3) NOT NULL,
    
    -- Running Balance (after this movement)
    balance_before DECIMAL(18,2) NOT NULL,
    balance_after DECIMAL(18,2) NOT NULL,
    
    -- Counterparty (the other side of the transaction)
    counterparty_account VARCHAR(34),
    counterparty_name VARCHAR(200),
    counterparty_bic VARCHAR(11),
    counterparty_bank_name VARCHAR(200),
    is_internal BOOLEAN DEFAULT FALSE,  -- TRUE if counterparty is another VA
    counterparty_va_id UUID REFERENCES virtual_accounts(id),  -- If internal
    
    -- Transaction Context
    transaction_context VARCHAR(30) DEFAULT 'STANDARD',
    -- STANDARD, ESCROW, WALLET, ECOMMERCE, IHB_POBO, IHB_ROBO, 
    -- IHB_IC_LOAN, IHB_NETTING, IHB_FX, SWEEP
    
    -- Linked Movement (for VA-to-VA transfers)
    -- Points to the OTHER movement in the pair
    linked_movement_id UUID REFERENCES va_movements(id),
    
    -- Parent Transaction (groups related movements)
    transaction_group_id UUID,  -- Same for both sides of a transfer
    
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
    booking_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Status
    status VARCHAR(20) DEFAULT 'POSTED',
    -- PENDING, POSTED, REVERSED
    
    -- Reversal
    is_reversal BOOLEAN DEFAULT FALSE,
    reversed_movement_id UUID REFERENCES va_movements(id),
    reversal_reason TEXT,
    
    -- BaNCS Sync
    bancs_movement_id BIGINT,
    bancs_sync_status VARCHAR(20) DEFAULT 'PENDING',
    bancs_synced_at TIMESTAMP,
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    
    CONSTRAINT chk_movement_type CHECK (movement_type IN ('CREDIT', 'DEBIT')),
    CONSTRAINT chk_amount_positive CHECK (amount > 0)
);

-- Indexes for statement queries
CREATE INDEX idx_va_mov_account ON va_movements(virtual_account_id);
CREATE INDEX idx_va_mov_account_date ON va_movements(virtual_account_id, transaction_date);
CREATE INDEX idx_va_mov_account_seq ON va_movements(virtual_account_id, movement_id);
CREATE INDEX idx_va_mov_group ON va_movements(transaction_group_id);
CREATE INDEX idx_va_mov_linked ON va_movements(linked_movement_id);
CREATE INDEX idx_va_mov_context ON va_movements(transaction_context);
CREATE INDEX idx_va_mov_status ON va_movements(status);
CREATE INDEX idx_va_mov_bancs ON va_movements(bancs_movement_id);

-- Unique movement_id per VA
CREATE UNIQUE INDEX idx_va_mov_unique_seq ON va_movements(virtual_account_id, movement_id);

COMMENT ON TABLE va_movements IS 'Single-entry movement ledger per Virtual Account (BaNCS aligned)';
```

### 2.2 Transaction Groups Table (Links Related Movements)

```sql
-- ============================================================================
-- TRANSACTION_GROUPS: Groups related movements together
-- ============================================================================
-- A VA-to-VA transfer has ONE group with TWO movements
-- External payments have ONE group with ONE movement

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
    
    -- Customer Context
    customer_id UUID NOT NULL REFERENCES corporate_customers(id),
    
    -- Total Amount
    total_amount DECIMAL(18,2) NOT NULL,
    currency_code VARCHAR(3) NOT NULL,
    
    -- Movement Count
    movement_count INTEGER DEFAULT 0,
    
    -- External References
    external_reference VARCHAR(100),
    e2e_reference VARCHAR(35),
    uetr VARCHAR(36),
    
    -- Status
    status VARCHAR(20) DEFAULT 'COMPLETED',
    -- PENDING, COMPLETED, PARTIALLY_COMPLETED, FAILED, REVERSED
    
    -- Dates
    initiated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    
    -- Initiator
    initiated_by VARCHAR(100),
    initiated_channel VARCHAR(50),
    
    -- Approval (if required)
    requires_approval BOOLEAN DEFAULT FALSE,
    approved_by VARCHAR(100),
    approved_at TIMESTAMP,
    
    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_txn_grp_customer ON transaction_groups(customer_id);
CREATE INDEX idx_txn_grp_type ON transaction_groups(group_type);
CREATE INDEX idx_txn_grp_status ON transaction_groups(status);
CREATE INDEX idx_txn_grp_date ON transaction_groups(initiated_at);

COMMENT ON TABLE transaction_groups IS 'Groups related VA movements into logical transactions';
```

---

## 3. How Different Scenarios Work

### 3.1 VA-to-VA Internal Transfer

**Scenario:** Transfer 1000 AED from VA-A to VA-B

```
┌─────────────────────────────────────────────────────────────────┐
│                    INTERNAL TRANSFER                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  transaction_groups:                                             │
│  ├── id: TXN-001                                                │
│  ├── group_type: INTERNAL_TRANSFER                              │
│  ├── total_amount: 1000                                         │
│  └── movement_count: 2                                          │
│                                                                  │
│  va_movements (Movement 1 - DEBIT from VA-A):                   │
│  ├── movement_id: 101                                           │
│  ├── virtual_account_id: VA-A                                   │
│  ├── movement_type: DEBIT                                       │
│  ├── amount: 1000                                               │
│  ├── balance_before: 5000                                       │
│  ├── balance_after: 4000                                        │
│  ├── counterparty_va_id: VA-B                                   │
│  ├── is_internal: TRUE                                          │
│  ├── transaction_group_id: TXN-001                              │
│  └── linked_movement_id: MOV-002                                │
│                                                                  │
│  va_movements (Movement 2 - CREDIT to VA-B):                    │
│  ├── movement_id: 202                                           │
│  ├── virtual_account_id: VA-B                                   │
│  ├── movement_type: CREDIT                                      │
│  ├── amount: 1000                                               │
│  ├── balance_before: 2000                                       │
│  ├── balance_after: 3000                                        │
│  ├── counterparty_va_id: VA-A                                   │
│  ├── is_internal: TRUE                                          │
│  ├── transaction_group_id: TXN-001                              │
│  └── linked_movement_id: MOV-001                                │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 3.2 External Payment (Outward)

**Scenario:** Pay 500 AED from VA-A to external beneficiary

```
┌─────────────────────────────────────────────────────────────────┐
│                    EXTERNAL PAYMENT                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  transaction_groups:                                             │
│  ├── id: TXN-002                                                │
│  ├── group_type: EXTERNAL_PAYMENT                               │
│  ├── total_amount: 500                                          │
│  └── movement_count: 1                                          │
│                                                                  │
│  va_movements (Single DEBIT):                                   │
│  ├── movement_id: 102                                           │
│  ├── virtual_account_id: VA-A                                   │
│  ├── movement_type: DEBIT                                       │
│  ├── amount: 500                                                │
│  ├── balance_before: 4000                                       │
│  ├── balance_after: 3500                                        │
│  ├── counterparty_account: "DE89370400440532013000"             │
│  ├── counterparty_name: "Vendor XYZ"                            │
│  ├── is_internal: FALSE                                         │
│  ├── transaction_group_id: TXN-002                              │
│  └── linked_movement_id: NULL                                   │
│                                                                  │
│  (Settlement account movement handled by core banking)          │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 3.3 External Receipt (Inward - E-commerce Payment)

**Scenario:** Customer pays 250 AED to e-commerce VA

```
┌─────────────────────────────────────────────────────────────────┐
│                    EXTERNAL RECEIPT                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  transaction_groups:                                             │
│  ├── id: TXN-003                                                │
│  ├── group_type: EXTERNAL_RECEIPT                               │
│  ├── total_amount: 250                                          │
│  └── movement_count: 1                                          │
│                                                                  │
│  va_movements (Single CREDIT):                                  │
│  ├── movement_id: 301                                           │
│  ├── virtual_account_id: ECOM-VA-123                            │
│  ├── movement_type: CREDIT                                      │
│  ├── amount: 250                                                │
│  ├── balance_before: 0                                          │
│  ├── balance_after: 250                                         │
│  ├── counterparty_account: "AE123456789"                        │
│  ├── counterparty_name: "John Customer"                         │
│  ├── is_internal: FALSE                                         │
│  ├── transaction_context: ECOMMERCE                             │
│  ├── transaction_group_id: TXN-003                              │
│  └── linked_movement_id: NULL                                   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 3.4 IHB POBO (Pay On Behalf Of)

**Scenario:** Treasury Center pays 10,000 AED on behalf of Subsidiary A

```
┌─────────────────────────────────────────────────────────────────┐
│                    IHB POBO                                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  transaction_groups:                                             │
│  ├── id: TXN-004                                                │
│  ├── group_type: IHB_POBO                                       │
│  ├── total_amount: 10000                                        │
│  └── movement_count: 2                                          │
│                                                                  │
│  va_movements (Movement 1 - Treasury pays externally):          │
│  ├── virtual_account_id: TREASURY_VA                            │
│  ├── movement_type: DEBIT                                       │
│  ├── amount: 10000                                              │
│  ├── counterparty_account: "External Vendor"                    │
│  ├── is_internal: FALSE                                         │
│  ├── transaction_context: IHB_POBO                              │
│  └── transaction_group_id: TXN-004                              │
│                                                                  │
│  va_movements (Movement 2 - IC position to Sub A):              │
│  ├── virtual_account_id: SUBSIDIARY_A_IC_VA                     │
│  ├── movement_type: DEBIT (Sub A owes Treasury)                 │
│  ├── amount: 10000                                              │
│  ├── counterparty_va_id: TREASURY_IC_VA                         │
│  ├── is_internal: TRUE                                          │
│  ├── transaction_context: IHB_POBO                              │
│  └── transaction_group_id: TXN-004                              │
│                                                                  │
│  (Plus corresponding CREDIT on Treasury IC VA)                   │
│                                                                  │
│  ic_position_movements also updated for IC tracking             │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 3.5 IHB ROBO (Receive On Behalf Of)

**Scenario:** Treasury receives 5,000 AED on behalf of Subsidiary B

```
┌─────────────────────────────────────────────────────────────────┐
│                    IHB ROBO                                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  transaction_groups:                                             │
│  ├── id: TXN-005                                                │
│  ├── group_type: IHB_ROBO                                       │
│  ├── total_amount: 5000                                         │
│  └── movement_count: 2                                          │
│                                                                  │
│  va_movements (Movement 1 - Treasury receives from external):   │
│  ├── virtual_account_id: TREASURY_VA                            │
│  ├── movement_type: CREDIT                                      │
│  ├── amount: 5000                                               │
│  ├── counterparty_account: "External Customer"                  │
│  ├── is_internal: FALSE                                         │
│  ├── transaction_context: IHB_ROBO                              │
│  └── transaction_group_id: TXN-005                              │
│                                                                  │
│  va_movements (Movement 2 - IC position: Treasury owes Sub B):  │
│  ├── virtual_account_id: TREASURY_IC_VA                         │
│  ├── movement_type: CREDIT (Treasury owes Sub B)                │
│  ├── amount: 5000                                               │
│  ├── counterparty_va_id: SUBSIDIARY_B_IC_VA                     │
│  ├── is_internal: TRUE                                          │
│  ├── transaction_context: IHB_ROBO                              │
│  └── transaction_group_id: TXN-005                              │
│                                                                  │
│  (Plus corresponding DEBIT on Sub B IC VA showing receivable)   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 3.6 IC Loan Disbursement

**Scenario:** Subsidiary B lends 50,000 AED to Subsidiary C

```
┌─────────────────────────────────────────────────────────────────┐
│                    IC LOAN DISBURSEMENT                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  transaction_groups:                                             │
│  ├── id: TXN-006                                                │
│  ├── group_type: IHB_IC_LOAN                                    │
│  ├── total_amount: 50000                                        │
│  └── movement_count: 2                                          │
│                                                                  │
│  va_movements (Movement 1 - Lender Sub B):                      │
│  ├── virtual_account_id: SUB_B_OPERATING_VA                     │
│  ├── movement_type: DEBIT                                       │
│  ├── amount: 50000                                              │
│  ├── counterparty_va_id: SUB_C_OPERATING_VA                     │
│  ├── is_internal: TRUE                                          │
│  ├── transaction_context: IHB_IC_LOAN                           │
│  └── movement_description: "IC Loan to Sub C - LOAN-001"        │
│                                                                  │
│  va_movements (Movement 2 - Borrower Sub C):                    │
│  ├── virtual_account_id: SUB_C_OPERATING_VA                     │
│  ├── movement_type: CREDIT                                      │
│  ├── amount: 50000                                              │
│  ├── counterparty_va_id: SUB_B_OPERATING_VA                     │
│  ├── is_internal: TRUE                                          │
│  ├── transaction_context: IHB_IC_LOAN                           │
│  └── movement_description: "IC Loan from Sub B - LOAN-001"      │
│                                                                  │
│  intercompany_loans table also updated                          │
│  ic_position_movements also created                             │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 3.7 Netting Settlement

**Scenario:** Monthly netting settles net position: Sub A pays Sub B 15,000 AED

```
┌─────────────────────────────────────────────────────────────────┐
│                    NETTING SETTLEMENT                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  transaction_groups:                                             │
│  ├── id: TXN-007                                                │
│  ├── group_type: IHB_NETTING                                    │
│  ├── total_amount: 15000                                        │
│  ├── external_reference: NETTING-CYCLE-2024-03                  │
│  └── movement_count: 2                                          │
│                                                                  │
│  va_movements (Movement 1 - Net Payer Sub A):                   │
│  ├── virtual_account_id: SUB_A_OPERATING_VA                     │
│  ├── movement_type: DEBIT                                       │
│  ├── amount: 15000                                              │
│  ├── counterparty_va_id: SUB_B_OPERATING_VA                     │
│  ├── is_internal: TRUE                                          │
│  ├── transaction_context: IHB_NETTING                           │
│  └── movement_description: "Netting Settlement Mar-2024"        │
│                                                                  │
│  va_movements (Movement 2 - Net Receiver Sub B):                │
│  ├── virtual_account_id: SUB_B_OPERATING_VA                     │
│  ├── movement_type: CREDIT                                      │
│  ├── amount: 15000                                              │
│  ├── counterparty_va_id: SUB_A_OPERATING_VA                     │
│  ├── is_internal: TRUE                                          │
│  ├── transaction_context: IHB_NETTING                           │
│  └── movement_description: "Netting Settlement Mar-2024"        │
│                                                                  │
│  netting_cycles status updated to SETTLED                       │
│  ic_positions reset to 0 for settled pairs                      │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 4. Account Statement Query

Following BaNCS API pattern:

```sql
-- Get statement for a specific VA
SELECT 
    m.movement_id,
    m.transaction_date,
    m.value_date,
    CASE WHEN m.movement_type = 'CREDIT' THEN m.amount ELSE NULL END AS credit_amount,
    CASE WHEN m.movement_type = 'DEBIT' THEN m.amount ELSE NULL END AS debit_amount,
    m.balance_after AS balance,
    m.movement_description,
    m.counterparty_name,
    m.counterparty_account,
    m.e2e_reference,
    m.uetr,
    g.group_reference AS transaction_reference
FROM va_movements m
LEFT JOIN transaction_groups g ON m.transaction_group_id = g.id
WHERE m.virtual_account_id = :va_id
  AND m.transaction_date BETWEEN :from_date AND :to_date
  AND m.status = 'POSTED'
ORDER BY m.movement_id DESC;
```

---

## 5. Key Design Principles

| Principle | Implementation |
|-----------|----------------|
| **Single-entry per VA** | Each VA records only its own DEBIT or CREDIT |
| **Dual movements for transfers** | VA-to-VA creates two linked movements |
| **Movement ID sequence per VA** | Each VA has its own movement_id sequence |
| **Running balance** | Every movement stores balance_before and balance_after |
| **Linkage** | linked_movement_id connects paired movements |
| **Grouping** | transaction_group_id groups related movements |
| **Context awareness** | transaction_context identifies use case |
| **BaNCS alignment** | Structure matches BaNCS statement API response |

---

## 6. Relationship to IC Positions

For IHB operations, `va_movements` and `ic_position_movements` work together:

```
┌─────────────────────────────────────────────────────────────────┐
│                                                                  │
│  va_movements                        ic_position_movements       │
│  ┌────────────────────┐              ┌────────────────────┐     │
│  │ Records actual     │              │ Tracks IC balance  │     │
│  │ fund movements     │  ───────────→│ between entities   │     │
│  │ on VAs             │  (creates)   │                    │     │
│  └────────────────────┘              └────────────────────┘     │
│                                                                  │
│  Purpose:                            Purpose:                    │
│  - VA balance tracking               - IC exposure tracking     │
│  - Statement generation              - Netting calculation      │
│  - Audit trail                       - Interest calculation     │
│                                                                  │
│  Example: POBO Payment                                          │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │ 1. Treasury VA: DEBIT 10,000 (actual payment)          │    │
│  │ 2. Treasury IC VA: CREDIT 10,000 (Sub A owes us)       │    │
│  │ 3. Sub A IC VA: DEBIT 10,000 (we owe Treasury)         │    │
│  │                                                         │    │
│  │ ic_positions updated: Sub A net position +10,000       │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 7. Migration Impact

### Tables to Add
- `va_movements` (core transaction ledger)
- `transaction_groups` (logical grouping)

### Tables to Modify
- `virtual_accounts`: Add `last_movement_id`, ensure balance fields align

### Tables to Review
- `transactions`: May become wrapper/legacy for external payments
- `wallet_transactions`: Migrate to `va_movements`
- `ecommerce_payments`: Link to `va_movements`
- `intercompany_loan_transactions`: Link to `va_movements`

### Migration Approach
1. Create new `va_movements` table
2. Dual-write: Write to both old and new tables
3. Migrate historical data
4. Switch reads to new table
5. Deprecate old transaction tables

---

## 8. Summary

**The single-entry principle ensures:**

1. ✅ Each VA has its own complete ledger
2. ✅ Statement API returns only movements for requested VA
3. ✅ Running balance is always accurate per VA
4. ✅ Audit trail is complete and traceable
5. ✅ Internal transfers are properly recorded on both sides
6. ✅ IC positions can be derived from IC VA movements
7. ✅ BaNCS API compatibility is maintained
