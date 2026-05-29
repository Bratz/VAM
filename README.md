# Aperture

**See every flow, every account, every entity.**

A comprehensive Corporate Digital Banking platform built on a Virtual Account Management core, with **multi-bank liquidity**, **in-house bank**, **notional pooling & cash concentration**, **POBO/COBO intercompany**, **ISO 20022 payments**, **Digital Escrow**, **KYCC**, **Wallet Programs**, **BaNCS Fallback**, and an embedded **Treasury Copilot** AI assistant.

**Naming:** *Aperture* is the customer-facing product brand. *VAM* (Virtual Account Management) remains the internal codename — visible in the Java package (`com.bank.vam`), database schema, API paths (`/api/...`), and configuration keys (`vam.*`). Think Chromium / Chrome.

## Quick reference

| What | Where |
| :---- | :---- |
| **Install and run locally** | [INSTALL.md](http://./INSTALL.md) |
| **Architectural model and design intent** | [docs/COMPREHENSIVE\_VAM\_ANALYSIS.md](http://./docs/COMPREHENSIVE_VAM_ANALYSIS.md) |
| **Database schema (with `COMMENT ON` documentation)** | Restored from `database/vam_db.dump`; explore with `psql` after restore |
| **API documentation (Swagger UI)** | `http://localhost:8080/swagger-ui.html` once the backend is running |
| **Build prompts and design notes** | [tasks/](http://./tasks/) |
| **Repository** | [github.com/Bratz/VAM](https://github.com/Bratz/VAM) |

## 🏗️ Architecture Overview

┌─────────────────────────────────────────────────────────────────────┐

│                        Frontend (React \+ TypeScript)                 │

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

## 🌅 Treasurer's Morning Cockpit (V1)

The dashboard route (`/dashboard`) is the **Treasurer's Morning Cockpit** — a triage surface organised around exceptions and the today-horizon, not around portfolio summaries. Five bands, top to bottom:

1. **Greeting strip** — name, local time, market session (Asia / EMEA / Americas), entity selector, balance freshness with colour shift (neutral → warning at 15min → error at 60min), Refresh button.  
2. **Attention inbox** — *"Needs your attention today"*. The dominant above-the-fold element. Rows are sorted by severity (critical → high → medium) then by time pressure. Filter chips (URL-persisted at `?filter=`): All / Critical / High / Medium / By bank / By entity / By currency. Clicking a row opens a slide-in drawer with full context, related items in the last 24h, and an audit-trail preview.  
3. **Today's horizon** — net position EOD, scheduled outflows next 8h, expected inflows next 8h, credit headroom, plus a 24-hour flow bar. Hours that match an attention item's deadline get a thin warning underline. FX disclosure line names the rates used.  
4. **Multi-bank band** — embeds the multi-bank Overview view in `compact` mode. "Open full page →" navigates to `/treasury/multi-bank`.  
5. **Context strip** — Pooling / Sweeping / Netting / IHB tiles, plus a 30-day balance trend sparkline and last-5 transactions.

### V1 scope

- **Frontend-only.** All composition is client-side (`cockpitApi` in `frontend/src/services/cockpitApi.ts`); V2 will replace this with dedicated `/cockpit/attention` and `/cockpit/horizon` backend endpoints. The public surface (`getAttentionItems`, `getTodayHorizon`, `snoozeItem`, `executeAction`) is permanent.  
- **8 attention categories** in the schema: `funding_shortfall`, `sweep_failure`, `stuck_transaction`, `stale_balance`, `pending_approval` (V1 producers), plus `fx_exposure`, `concentration_risk`, `loan_rollover` (V2 producers — return `[]` until backing data lands).  
- **FX-honesty.** Cross-currency totals are converted using the disclosed rates only; per-currency components are revealed on hover/click. Anywhere fresh rates aren't available, fall back to per-currency rows or counts.  
- **Auditable.** Every action button writes an audit-trail entry via `auditLog.record(...)` (V1 stub: `console.info` \+ fire-and-forget POST to `/audit/v1/record`; V2 wires the real audit pipeline).  
- **Telemetry hooks.** `console.info` events at: page load, attention row click, action executed, drawer opened, snooze, refresh, classic-view switch (`utils/telemetry.ts`). V2 swaps the sink.

### Feature flag and the 60-day rollout overlap

The cockpit ships behind `cockpit.v1` (default `'on'`), read from a minimal localStorage-backed flag layer (`utils/featureFlags.ts`). The classic dashboard remains accessible:

- `/dashboard` resolves to the cockpit when `cockpit.v1` is `on` (default), or the classic dashboard when `off`.  
- `/dashboard-classic` always resolves to the classic dashboard while `cockpit.v1.classic_fallback_enabled` is `on`. Default `on` for V1; scheduled for removal next major release.  
- Pilot users can flip back via the **"Switch to classic view"** link in the cockpit's greeting strip — that writes the override to localStorage and bounces them to `/dashboard-classic`.

To force the cockpit off in your browser:

localStorage.setItem('featureFlag:cockpit.v1', 'off');

To force it back on:

localStorage.setItem('featureFlag:cockpit.v1', 'on');

### Naming

The cockpit's notion of an "exception" is operational (funding shortfall, sweep failure, stale balance, etc.). The existing `exceptionApi` in `services/api.ts` handles a different domain — *unallocated settlement transactions*. To keep the two distinct, the cockpit's surface is `cockpitApi` and its UI uses the term **"attention items"** internally and **"Needs your attention"** as the user-facing heading. Avoid the word *exception* in cockpit copy.

## 🏦 Multi-Bank Liquidity (V1)

`/treasury/multi-bank` is a three-view cockpit over the same underlying summary payload:

- **Overview** *(default)* — shadow-count distribution bar across banks, per-currency cards with the bank split, freshness banner, filter chips. Embeddable in `compact` mode (used by the cockpit's Multi-Bank band).  
- **By Bank** — the original per-bank table view, byte-identical to the pre-refactor page for back-compatibility. Reachable via `?view=by-bank`.  
- **By Currency** — currency-first pivot showing which banks hold each currency. Reachable via `?view=by-currency`.

All views share the same `?filter=` query parameter, the same bulk-refresh toolbar action, and the same data source (`multiBankLiquidityApi.getSummary`). Deep links from email / Slack continue to resolve to the same view they originally pointed at.

## ✨ Capabilities

### Digital Escrow Management

Corporate \= **Seller**, Beneficiary \= **Buyer**

Contract Flow:

DRAFT → PENDING\_FUNDING → FUNDED → IN\_PROGRESS → PENDING\_RELEASE → RELEASED → COMPLETED

                                         ↓

                                    DISPUTED (can occur at any active stage)

Features:

- **Milestone-based releases** — Partial fund releases tied to deliverables  
- **Full release** — Single release on completion  
- **Dispute handling** — Raise and resolve disputes  
- **Auto-release** — Configurable automatic release after conditions met  
- **Document management** — Attach contracts, invoices, delivery notes

### KYCC — Know Your Customer's Clients

Minimal KYC for beneficiaries (buyers / wallet holders):

- Full Name, Mobile Number, Email Address  
- KYC ID (Type, Number, Expiry, Country)

Features: OTP / manual verification, risk scoring, expiry tracking, consent management.

### Wallet Program Management

Corporate \= **Program Operator**, Beneficiary \= **Wallet Holder**

Features:

- **Program configuration** — Limits, fees, features  
- **Published virtual accounts** — Each wallet backed by a VA  
- **Load / Spend / Transfer** — Full transaction lifecycle  
- **Limit management** — Daily / Monthly spend limits  
- **Fee structure** — Issuance, monthly, load, transaction fees

### BaNCS Fallback Mechanism

**Store locally, sync later** pattern:

When BaNCS Available:

  Request → BaNCS API → Response → Local DB

When BaNCS Unavailable:

  Request → Local DB (sync\_status='PENDING') → Sync Queue

                                                    ↓

                                          Background Processor

                                                    ↓

                                    (When BaNCS recovers) → Sync to BaNCS

Components:

- **Health Monitor** — Tracks BaNCS availability.  
- **Sync Queue** — Stores pending operations (JSONB payload).  
- **Sync Processor** — Background job with exponential backoff retry.

## 📁 Project Structure

vam-portal/

├── backend/         Spring Boot 3.2 / Java 17 API

│   └── src/main/java/com/bank/vam/      ... domain packages

├── frontend/        React 18 / TypeScript / Vite / Tailwind

│   └── src/

│       ├── pages/                       page-level routes

│       ├── components/                  feature components

│       └── services/api.ts              the API client (one large file by design)

├── database/        Full PostgreSQL dump (schema \+ synthetic data)

├── docs/            Architectural documents

├── tasks/           Build prompts and design notes

├── INSTALL.md       Installation guide

└── README.md        This file

Enhanced/new entities under `com.bank.vam`:

| Domain | Key entities |
| :---- | :---- |
| **KYCC** | `KyccRecord`, `KyccVerificationStatus` |
| **Escrow** | `EscrowContract`, `EscrowMilestone`, `EscrowStatus` |
| **Wallet** | `WalletProgram`, `WalletAccount` |
| **BaNCS sync** | `BancsSyncQueue`, `SyncOperationType`, `SyncStatus` |
| **Cockpit** | `cockpitApi` (frontend), `auditLog`, `telemetry` |

## 🚀 API Endpoints

The full API surface is available via Swagger UI at `http://localhost:8080/swagger-ui.html` once the backend is running. Representative endpoints by domain:

| Domain | Examples |
| :---- | :---- |
| **KYCC** | `POST /api/v1/kycc`, `POST /api/v1/kycc/{id}/verify` |
| **Escrow** | `POST /api/v1/escrow`, `POST /api/v1/escrow/{id}/fund`, `POST /api/v1/escrow/{id}/milestones/{mid}/release` |
| **Wallet** | `POST /api/v1/wallet/programs`, `POST /api/v1/wallet/accounts/{id}/load`, `POST /api/v1/wallet/accounts/{id}/transfer` |
| **Admin / Sync** | `GET /api/v1/admin/sync/health`, `GET /api/v1/admin/sync/queue/stats`, `POST /api/v1/admin/sync/queue/process` |
| **Multi-bank liquidity** | `GET /treasury/multi-bank/summary`, `POST /treasury/multi-bank/refresh` |
| **Cockpit (V1, client-composed)** | `cockpitApi.getAttentionItems`, `cockpitApi.getTodayHorizon` |

## 🔄 BaNCS Sync Flow

1\. Client Request → Virtual Account Service

                            │

2\. Check BaNCS Health ──────┼───────────────────────────────────┐

        │                   │                                   │

        ▼                   ▼                                   ▼

   \[Available\]         \[Unavailable\]                      \[Call Failed\]

        │                   │                                   │

        ▼                   ▼                                   ▼

   Call BaNCS API     Create Local Entity              Create Local Entity

        │             (sync\_status='PENDING')          (sync\_status='PENDING')

        │                   │                                   │

        ▼                   ▼                                   ▼

   Update Local DB    Queue to bancs\_sync\_queue        Queue to bancs\_sync\_queue

   (sync\_status=      (operation, payload, retry)      (operation, payload, retry)

    'SYNCED')               │                                   │

                            └───────────────┬───────────────────┘

                                            ▼

                            Background Processor (Every 60s)

                                            │

                            ┌───────────────┴───────────────┐

                            │   For each pending item:      │

                            │   1\. Mark as PROCESSING       │

                            │   2\. Call BaNCS API           │

                            │   3\. On Success:              │

                            │      \- Update entity status   │

                            │      \- Mark queue COMPLETED   │

                            │   4\. On Failure:              │

                            │      \- Increment retry count  │

                            │      \- Calculate next retry   │

                            │      \- If max retries: FAILED │

                            └───────────────────────────────┘

## 📊 Database

The shipped PostgreSQL dump contains roughly 65 tables across domains: corporate hierarchy, virtual & physical accounts, sweep rules, notional pools, intercompany netting, IHB, KYCC, escrow, wallet programs, BaNCS sync queue, fee/tax/charge configuration, FX rates, and notifications. The schema *is* the design — read the `COMMENT ON COLUMN` and `COMMENT ON TABLE` lines after restore for design intent.

Explore after restore (see `INSTALL.md` §4):

\\dt public.\*

\\d virtual\_accounts

\\d physical\_accounts

\\d sweep\_rules

\\d notional\_pools

## 🔒 Security Considerations

- KYCC records store masked ID numbers.  
- Consent tracking for privacy compliance.  
- Role-based access for escrow operations.  
- Audit logging for transactions and (in V2) cockpit attention actions.  
- Encrypted payloads in sync queue.

## 🏃 Quick Start

See [**INSTALL.md**](http://./INSTALL.md) for the full installation walkthrough — prerequisites, database restore, environment configuration, backend and frontend startup, demo credentials, and troubleshooting.

The condensed version: PostgreSQL 16 \+ JDK 17 \+ Node 20 \+ Maven 3.8, restore `database/vam_db.dump` via `pg_restore`, `mvn spring-boot:run` for the backend, `npm run dev` for the frontend, log in at `http://localhost:5173`.

## A note on the data

All data shipped in this repository is illustrative. "Brato Group" is a fictional corporate; bank names, BICs, account numbers, balances, and counterparty references are synthetic. Do not interpret any record as relating to a real entity, customer, or transaction.

## Distribution

This repository is shared for file-distribution purposes only. There is no support channel attached. For installation issues, work through [**INSTALL.md §9 "Common installation problems"**](http://./INSTALL.md) first, then contact the person who shared the repo with you.

## 📄 License

Proprietary — All rights reserved.  
