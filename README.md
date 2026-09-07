# Aperture

> **See every flow, every account, every entity, every time.**

A comprehensive Corporate Digital Banking platform built on a Virtual Account Management core, with **multi-bank liquidity**, **in-house bank**, **notional pooling & cash concentration**, **POBO/COBO intercompany**, **ISO 20022 payments**, **Digital Escrow**, **KYCC**, **Wallet Programs**, **BaNCS Fallback**, and an embedded **Treasury Copilot** AI assistant.

> **Naming**: *Aperture* is the customer-facing product brand. *VAM* (Virtual Account Management) remains the internal codename — visible in the Java package (`com.bank.vam`), database schema, API paths (`/api/...`), and configuration keys (`vam.*`). Think Chromium / Chrome.

## 🏗️ Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Frontend (React + TypeScript)                 │
│         Swiss Minimalist Design • Tailwind CSS • Vite               │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    Backend (Spring Boot 3.2)                         │
│    REST APIs • OAuth 2.0 • Scheduled Jobs • Health Monitor          │
├─────────────────────────────────────────────────────────────────────┤
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────────┐  │
│  │  KYCC        │  │  Escrow      │  │  Wallet                  │  │
│  │  Service     │  │  Service     │  │  Service                 │  │
│  └──────────────┘  └──────────────┘  └──────────────────────────┘  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────────┐  │
│  │  Virtual     │  │  Beneficiary │  │  Transaction             │  │
│  │  Account     │  │  Service     │  │  Service                 │  │
│  └──────────────┘  └──────────────┘  └──────────────────────────┘  │
├─────────────────────────────────────────────────────────────────────┤
│                    BaNCS Integration Layer                           │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  Health Monitor  │  Sync Queue Service  │  Sync Processor    │  │
│  │  (Availability)  │  (Queue Operations)  │  (Background Job)  │  │
│  └──────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
              │                              │
              ▼                              ▼
       ┌──────────┐                   ┌──────────────┐
       │PostgreSQL│                   │  TCS BaNCS   │
       │    DB    │◄──────────────────│    APIs      │
       │ (Primary)│   Sync Queue      │  (Optional)  │
       └──────────┘                   └──────────────┘
```

## 🌅 Treasurer's Morning Cockpit (V1)

The dashboard route (`/dashboard`) is now the **Treasurer's Morning Cockpit** — a triage surface organised around exceptions and the today-horizon, not around portfolio summaries. Five bands, top to bottom:

1. **Greeting strip** — name, local time, market session (Asia / EMEA / Americas), entity selector, balance freshness with colour shift (neutral → warning at 15min → error at 60min), Refresh button.
2. **Attention inbox** — *"Needs your attention today"*. The dominant above-the-fold element. Rows are sorted by severity (critical → high → medium) then by time pressure. Filter chips (URL-persisted at `?filter=`): All / Critical / High / Medium / By bank / By entity / By currency. Clicking a row opens a slide-in drawer with full context, related items in the last 24h, and an audit-trail preview.
3. **Today's horizon** — net position EOD, scheduled outflows next 8h, expected inflows next 8h, credit headroom, plus a 24-hour flow bar. Hours that match an attention item's deadline get a thin warning underline. FX disclosure line names the rates used.
4. **Multi-bank band** — embeds the multi-bank Overview view in `compact` mode. "Open full page →" navigates to `/treasury/multi-bank`.
5. **Context strip** — Pooling / Sweeping / Netting / IHB tiles, plus a 30-day balance trend sparkline and last-5 transactions.

### V1 scope

- **Frontend-only.** All composition is client-side (`cockpitApi` in `frontend/src/services/cockpitApi.ts`); V2 will replace this with dedicated `/cockpit/attention` and `/cockpit/horizon` backend endpoints. The public surface (`getAttentionItems`, `getTodayHorizon`, `snoozeItem`, `executeAction`) is permanent.
- **8 attention categories** in the schema: `funding_shortfall`, `sweep_failure`, `stuck_transaction`, `stale_balance`, `pending_approval` (V1 producers), plus `fx_exposure`, `concentration_risk`, `loan_rollover` (V2 producers — return `[]` until backing data lands).
- **FX-honesty.** Cross-currency totals are converted using the disclosed rates only; per-currency components are revealed on hover/click. Anywhere fresh rates aren't available, fall back to per-currency rows or counts.
- **Auditable.** Every action button writes an audit-trail entry via `auditLog.record(...)` (V1 stub: `console.info` + fire-and-forget POST to `/audit/v1/record`; V2 wires the real audit pipeline).
- **Telemetry hooks.** `console.info` events at: page load, attention row click, action executed, drawer opened, snooze, refresh, classic-view switch (`utils/telemetry.ts`). V2 swaps the sink.

### Feature flag and the 60-day rollout overlap

The cockpit ships behind `cockpit.v1` (default `'on'`), read from a minimal localStorage-backed flag layer (`utils/featureFlags.ts`). The classic dashboard remains accessible:

- `/dashboard` resolves to the cockpit when `cockpit.v1` is `on` (default), or the classic dashboard when `off`.
- `/dashboard-classic` always resolves to the classic dashboard while `cockpit.v1.classic_fallback_enabled` is `on`. Default `on` for V1; scheduled for removal next major release.
- Pilot users can flip back via the **"Switch to classic view"** link in the cockpit's greeting strip — that writes the override to localStorage and bounces them to `/dashboard-classic`.

To force the cockpit off in your browser:
```js
localStorage.setItem('featureFlag:cockpit.v1', 'off');
```

To force it back on:
```js
localStorage.setItem('featureFlag:cockpit.v1', 'on');
```

### Naming

The cockpit's notion of an "exception" is operational (funding shortfall, sweep failure, stale balance, etc.). The existing `exceptionApi` in `services/api.ts` handles a different domain — *unallocated settlement transactions*. To keep the two distinct, the cockpit's surface is `cockpitApi` and its UI uses the term **"attention items"** internally and **"Needs your attention"** as the user-facing heading. Avoid the word *exception* in cockpit copy.

## ✨ New Features

### 1. Digital Escrow Management
Corporate = **Seller**, Beneficiary = **Buyer**

```
Contract Flow:
DRAFT → PENDING_FUNDING → FUNDED → IN_PROGRESS → PENDING_RELEASE → RELEASED → COMPLETED
                                         ↓
                                    DISPUTED (can occur at any active stage)
```

Features:
- **Milestone-based releases** - Partial fund releases tied to deliverables
- **Full release** - Single release on completion
- **Dispute handling** - Raise and resolve disputes
- **Auto-release** - Configurable automatic release after conditions met
- **Document management** - Attach contracts, invoices, delivery notes

### 2. KYCC - Know Your Customer's Clients
Minimal KYC for beneficiaries (buyers/wallet holders):
- **Full Name**
- **Mobile Number**
- **Email Address**
- **KYC ID** (Type, Number, Expiry, Country)

Features:
- OTP/Manual verification
- Risk scoring and categorization
- Expiry tracking
- Consent management

### 3. Wallet Program Management
Corporate = **Program Operator**, Beneficiary = **Wallet Holder**

Features:
- **Program configuration** - Limits, fees, features
- **Published virtual accounts** - Each wallet backed by a VA
- **Load/Spend/Transfer** - Full transaction lifecycle
- **Limit management** - Daily/Monthly spend limits
- **Fee structure** - Issuance, monthly, load, transaction fees

### 4. BaNCS Fallback Mechanism
**Store locally, sync later** pattern:

```
When BaNCS Available:
  Request → BaNCS API → Response → Local DB

When BaNCS Unavailable:
  Request → Local DB (sync_status='PENDING') → Sync Queue
                                                    ↓
                                          Background Processor
                                                    ↓
                                    (When BaNCS recovers) → Sync to BaNCS
```

Components:
- **Health Monitor** - Tracks BaNCS availability
- **Sync Queue** - Stores pending operations (JSONB payload)
- **Sync Processor** - Background job with exponential backoff retry

## 📁 Project Structure

```
vam-enhanced/
├── database/
│   └── schema.sql              # Enhanced PostgreSQL schema
│
├── backend/src/main/java/com/bank/vam/
│   ├── model/
│   │   ├── entity/
│   │   │   ├── KyccRecord.java        # KYCC entity
│   │   │   ├── EscrowContract.java    # Escrow entity
│   │   │   ├── EscrowMilestone.java   # Milestone entity
│   │   │   ├── WalletProgram.java     # Wallet program entity
│   │   │   ├── WalletAccount.java     # Wallet account entity
│   │   │   └── BancsSyncQueue.java    # Sync queue entity
│   │   ├── dto/
│   │   │   ├── KyccDto.java
│   │   │   ├── EscrowDto.java
│   │   │   └── WalletDto.java
│   │   └── enums/
│   │       ├── EscrowStatus.java
│   │       ├── KyccVerificationStatus.java
│   │       ├── SyncOperationType.java
│   │       └── SyncStatus.java
│   │
│   ├── service/
│   │   ├── KyccService.java           # KYCC business logic
│   │   ├── EscrowService.java         # Escrow business logic
│   │   ├── WalletService.java         # Wallet business logic
│   │   ├── BancsHealthMonitor.java    # Health monitoring
│   │   ├── BancsSyncQueueService.java # Queue management
│   │   └── BancsSyncProcessor.java    # Background sync
│   │
│   ├── integration/
│   │   └── BancsIntegrationService.java # Enhanced with fallback
│   │
│   ├── controller/
│   │   ├── KyccController.java
│   │   ├── EscrowController.java
│   │   ├── WalletController.java
│   │   └── BancsSyncController.java   # Admin endpoints
│   │
│   └── repository/
│       └── AllRepositories.java       # JPA repositories
│
└── docs/
    └── ARCHITECTURE.md
```

## 🚀 API Endpoints

### KYCC APIs
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/kycc` | Create KYCC record |
| GET | `/api/v1/kycc/{id}` | Get KYCC by ID |
| GET | `/api/v1/kycc/mobile/{mobile}` | Find by mobile |
| POST | `/api/v1/kycc/{id}/verify` | Verify KYCC |
| POST | `/api/v1/kycc/{id}/reject` | Reject KYCC |

### Escrow APIs
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/escrow` | Create escrow contract |
| POST | `/api/v1/escrow/{id}/activate` | Activate contract |
| POST | `/api/v1/escrow/{id}/fund` | Record funding |
| POST | `/api/v1/escrow/{id}/milestones/{mid}/approve` | Approve milestone |
| POST | `/api/v1/escrow/{id}/milestones/{mid}/release` | Release milestone |
| POST | `/api/v1/escrow/{id}/dispute` | Raise dispute |
| POST | `/api/v1/escrow/{id}/dispute/resolve` | Resolve dispute |

### Wallet APIs
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/wallet/programs` | Create wallet program |
| POST | `/api/v1/wallet/accounts` | Issue wallet |
| POST | `/api/v1/wallet/accounts/{id}/load` | Load funds |
| POST | `/api/v1/wallet/accounts/{id}/spend` | Spend from wallet |
| POST | `/api/v1/wallet/accounts/{id}/transfer` | P2P transfer |
| POST | `/api/v1/wallet/accounts/{id}/suspend` | Suspend wallet |

### Admin/Sync APIs
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/admin/sync/health` | BaNCS health status |
| GET | `/api/v1/admin/sync/queue/stats` | Queue statistics |
| POST | `/api/v1/admin/sync/queue/process` | Manual sync trigger |
| POST | `/api/v1/admin/sync/queue/retry-failed` | Retry failed items |

## 🔄 BaNCS Sync Flow

```
1. Client Request → Virtual Account Service
                            │
2. Check BaNCS Health ──────┼───────────────────────────────────┐
        │                   │                                   │
        ▼                   ▼                                   ▼
   [Available]         [Unavailable]                      [Call Failed]
        │                   │                                   │
        ▼                   ▼                                   ▼
   Call BaNCS API     Create Local Entity              Create Local Entity
        │             (sync_status='PENDING')          (sync_status='PENDING')
        │                   │                                   │
        ▼                   ▼                                   ▼
   Update Local DB    Queue to bancs_sync_queue        Queue to bancs_sync_queue
   (sync_status=      (operation, payload, retry)      (operation, payload, retry)
    'SYNCED')               │                                   │
                            └───────────────┬───────────────────┘
                                            ▼
                            Background Processor (Every 60s)
                                            │
                            ┌───────────────┴───────────────┐
                            │   For each pending item:      │
                            │   1. Mark as PROCESSING       │
                            │   2. Call BaNCS API           │
                            │   3. On Success:              │
                            │      - Update entity status   │
                            │      - Mark queue COMPLETED   │
                            │   4. On Failure:              │
                            │      - Increment retry count  │
                            │      - Calculate next retry   │
                            │      - If max retries: FAILED │
                            └───────────────────────────────┘
```

## 📊 Database Tables

### New Tables
| Table | Description |
|-------|-------------|
| `kycc_records` | KYCC verification records |
| `escrow_contracts` | Digital escrow contracts |
| `escrow_milestones` | Milestone-based releases |
| `escrow_transactions` | Escrow fund movements |
| `escrow_documents` | Contract documents |
| `wallet_programs` | Wallet program configuration |
| `wallet_accounts` | Individual wallets |
| `wallet_transactions` | Wallet transactions |
| `bancs_sync_queue` | Pending sync operations |
| `bancs_sync_queue_history` | Completed/failed syncs |
| `bancs_health_status` | Health check history |

### Enhanced Tables
| Table | Changes |
|-------|---------|
| `corporate_schemes` | Added `program_type`, `kycc_required` |
| `virtual_accounts` | Added `account_type`, `bancs_sync_*` fields |
| `beneficiaries` | Added `kycc_id`, `beneficiary_role`, `bancs_sync_*` |
| `transactions` | Added `transaction_context`, `bancs_sync_*` |

## 🔒 Security Considerations

- KYCC records store masked ID numbers
- Consent tracking for privacy compliance
- Role-based access for escrow operations
- Audit logging for all transactions
- Encrypted payloads in sync queue

## 🏃 Quick Start

```bash
# 1. Database Setup
createdb vam_db
psql -d vam_db -f database/schema.sql

# 2. Backend
cd backend
mvn clean install
mvn spring-boot:run

# 3. Access Swagger UI
open http://localhost:8080/swagger-ui.html
```

## 📝 Configuration

Key environment variables:
```
DB_PASSWORD=your_db_password
BANCS_BASE_URL=https://your-bancs-endpoint
BANCS_CLIENT_ID=your_client_id
BANCS_CLIENT_SECRET=your_client_secret
JWT_SECRET=your_jwt_secret
```

## 📄 License

Proprietary - All rights reserved
