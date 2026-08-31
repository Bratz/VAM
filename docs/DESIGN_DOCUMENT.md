# Aperture — Design Document

**Product:** Aperture — Corporate Digital Banking & Virtual Account Management
**Internal codename:** VAM (`com.bank.vam`, schema `vam_db`, API `/api`)
**Tagline:** *"See every flow, every account, every entity."*
**Document type:** Enterprise architecture & design reference
**Status:** Reflects the codebase as-built (as-is), with the redesigned target-state architecture called out where the two diverge.

> 📸 **Live walkthrough:** [`Aperture-Screens.pdf`](Aperture-Screens.pdf) — 53 screens captured from the running application against seeded data, cross-referenced from [`FEATURE_CAPABILITY_MAP.md`](FEATURE_CAPABILITY_MAP.md) (Part C — Live Screen Index).

> **Naming.** *Aperture* is the customer-facing brand; *VAM* is the internal codename (Java package, DB schema, API paths, config keys). The rename is a brand change, not a code/schema change — treat like Chromium (codebase) vs Chrome (product).

---

## 1. Executive Summary

Aperture is a corporate digital-banking platform built on a **Virtual Account Management (VAM)** core. It sits as a **virtual overlay above a core banking system of record (TCS BaNCS)**, letting a bank's corporate clients model their entire liquidity, entity, and payments landscape as a hierarchy of *virtual accounts* without provisioning a physical account per use case.

The platform spans eleven functional domains: **Accounts & Structure, Parties & Entities, Payments & Collections, Liquidity Management, Credit & Interest, Intercompany (POBO/COBO), Specialty Programs (Escrow, Wallets, E-Commerce), Tax & Charges, Integration & Sync, Insights (a Treasury Copilot assistant), and Administration**. It is delivered as a Spring Boot backend (≈60 REST controllers, ≈75 services, ≈70 JPA entities) and a React/TypeScript single-page front end (≈174 components across ≈70 pages).

The engineering core is genuinely deep — a **single-entry, per-VA movement ledger** aligned to the BaNCS statement API, **ISO 20022** payment and statement generation (pain.001/002, camt.052/053/054), a **materialized-path VA hierarchy** with balance aggregation and FX currency mirrors, an **In-House Bank** overlay, **notional pooling**, **rule-driven cash concentration**, **intercompany netting**, and a **human-in-the-loop AI action pipeline**. A smaller set of edge modules (Escrow, KYCC, Merchant onboarding, the E-Commerce dashboard, Sync Admin) are UI shells over mock data, and the Copilot's reasoning layer is a deterministic intent router rather than a live LLM — all explicitly scaffolded for a drop-in upgrade.

---

## 2. Product Context

### 2.1 What problem it solves
Corporate treasurers managing many legal entities, banks, and currencies face account sprawl, poor cash visibility, trapped liquidity, and manual reconciliation. Aperture collapses that into a single virtual structure:

- **One overlay, many use cases.** Collections, payables, wallets, escrow, e-commerce, and treasury pooling are all *virtual accounts governed by a Program*, not separate systems.
- **See every flow.** Balances aggregate up an entity/currency hierarchy in real time; every movement is a statement-ordered ledger row.
- **Move less cash.** Notional pooling, cash concentration sweeps, and an In-House Bank net and concentrate liquidity without unnecessary external transfers.
- **Pay and collect on behalf of.** POBO/COBO plus intercompany netting reduce external payment volume and bank fees.

### 2.2 System-of-record boundary
Aperture is **not** the ledger of record for real money — **TCS BaNCS** is. Aperture holds the *virtual* structure and a **locally-cached balance** per VA, synchronizing to BaNCS on a store-locally-sync-later basis. A **Resilience4j circuit breaker** guards BaNCS calls; when the core is unreachable the platform keeps operating against local state and queues sync. A WireMock BaNCS stub backs dev/demo.

### 2.3 Personas
| Persona | Primary use |
|---|---|
| **Corporate Treasurer** | Group cash position, pooling/IHB strategy, forecasts, simulator |
| **Cash Manager / Treasury Ops Analyst** | Sweeps, netting cycles, exceptions, statements, day-to-day movements |
| **Subsidiary Finance Manager** | Entity-scoped payables/receivables, POBO requests, intercompany position |
| **Bank Product / Operations Admin** | Programs, market profile, tax & charge config, integrations, tenant onboarding |
| **Compliance / KYC Officer** | KYCC review queue, screening, beneficiary verification, audit trail |
| **Integration Engineer** | Connectors, Open Banking consent, BaNCS sync, ISO 20022 message flows |
| **Any of the above (assisted)** | Treasury Copilot — natural-language position queries and guarded actions |

---

## 3. Design Principles

These five principles are load-bearing; every domain decision traces back to one of them.

1. **Programs as the universal container.** Every specialized VA use case (collections, payables, wallet, escrow, e-commerce, IHB) is a `Program` configuration row, not a bespoke table pair. One onboarding flow, one API surface, shared VIBAN pool / limits / fees. A new use case is a new `ProgramType` enum value — not a new subsystem.

2. **The VA hierarchy is king (Composite pattern).** A self-referential `parentAccountId` + `hierarchyLevel` + materialized path models all nesting: ROOT → PHYSICAL_MIRROR (shadow) → CURRENCY_MIRROR → transactional VAs. Pooling, aggregation, and consolidation are all traversals of this one tree.

3. **The In-House Bank is an overlay, not a parallel structure.** The IHB does **not** own accounts. It *designates* existing virtual accounts into treasury roles (operating, IC-receivable/payable, pool header/member) and *maps* existing legal entities into IHB roles. Minimal impact to existing infrastructure.

4. **Single-entry movement ledger.** Every transaction is a single-entry movement *per VA* (a debit on the source, a credit on the target, linked). This mirrors the BaNCS statement API exactly and makes statement generation a straight query. (See §5.3.)

5. **Allocations cascade for funding.** Budgets and credit limits flow *down* the entity hierarchy (group → region → subsidiary → VA); funds availability is checked *up* the hierarchy at every level before any debit.

---

## 4. Logical Architecture

### 4.1 Layered / container view

```
┌─────────────────────────────────────────────────────────────────────┐
│  Aperture SPA  (React 18 + TS 5, Vite 5, Tailwind, React Query,       │
│  Zustand, React Router 6, Recharts, framer-motion)                    │
│   • ~70 pages across 11 nav sections   • Treasury Copilot drawer (SSE) │
└───────────────────────────────┬───────────────────────────────────────┘
                                 │  HTTPS  /api  (Vite proxy in dev)
┌───────────────────────────────▼───────────────────────────────────────┐
│  Aperture Service  (Spring Boot 3.2.5, Java 17)                        │
│  ┌──────────────┬──────────────┬───────────────┬────────────────────┐ │
│  │ Controllers  │  Services     │  ISO 20022     │  AI Copilot        │ │
│  │ (~60 REST)   │  (~75, domain │  (pain/camt    │  (intent router,   │ │
│  │ ApiResponse<>│   logic)      │   gen + valid) │   tool registry,   │ │
│  │              │               │                │   action pipeline) │ │
│  ├──────────────┴──────────────┴───────────────┴────────────────────┤ │
│  │  Spring Data JPA / Hibernate  •  Spring Security OAuth2 RS + JWT   │ │
│  │  Resilience4j (BaNCS)  •  Spring Cache/Redis  •  WebFlux (SSE)     │ │
│  └───────────────────────────────────────────────────────────────────┘ │
└──────┬───────────────────────────┬───────────────────────────┬─────────┘
       │ JDBC                       │ REST + circuit breaker     │ OAuth2/H2H
┌──────▼───────┐          ┌─────────▼──────────┐        ┌────────▼─────────┐
│ PostgreSQL   │          │  TCS BaNCS core     │        │ Open Banking     │
│ (vam_db)     │          │  (system of record; │        │ ASPSPs / SWIFT / │
│ ~26+ tables  │          │  WireMock stub :8090)│       │ external banks   │
└──────────────┘          └─────────────────────┘        └──────────────────┘
```

### 4.2 Technology stack (as-built)
| Layer | Choice | Notes |
|---|---|---|
| Backend runtime | Spring Boot **3.2.5**, **Java 17** | *pom `java.version=17`. CLAUDE.md's "Java 21" is documentation drift.* |
| Persistence | PostgreSQL, Spring Data JPA / Hibernate | `ddl-auto: update` is the **active** schema path; **Flyway is a dependency but disabled** (`spring.flyway.enabled: false`). Migrations V2–V13 exist but are not the live mechanism. |
| Security | Spring Security **OAuth2 Resource Server** + JWT (JJWT 0.12.5) | Bearer tokens; Copilot shares the app auth context. |
| Resilience | **Resilience4j 2.2.0** | Circuit breaker around BaNCS client; mock fallback (`bancs.api.mock-enabled=true` default). |
| Reactive/streaming | Spring **WebFlux** | Copilot SSE token streaming; reserved for the future `LlmClient`. |
| Standards | **JAXB 4.x** | ISO 20022 XML (pain.001/002, camt.052/053/054). |
| Mapping / boilerplate | MapStruct 1.5.5, Lombok | |
| API docs | springdoc-openapi 2.4.0 | Swagger UI at `/api/swagger-ui.html`. |
| Caching | Redis + Spring Cache | Optional in dev. |
| Server | Port **8053**, context path `/api`, app name `aperture-service` | *Port 8080 in CLAUDE.md is drift.* Hikari max pool 10; Hibernate `batch_size: 500`, ordered inserts/updates. |
| Front end | React 18.2, TS 5.3, Vite 5, Tailwind 3.4 | React Query 5, react-table 8, Zustand 4.5, React Router 6.22, axios, react-hook-form + zod, Recharts, framer-motion, headlessui, react-markdown + remark-gfm, lucide-react, react-hot-toast. |

> **Config-drift note for readers.** `CLAUDE.md` states Java 21, Flyway-driven migrations, and port 8080/`/api`. The running system is **Java 17, JPA `ddl-auto:update` (Flyway disabled), port 8053**. This document reflects the code.

### 4.3 API conventions
- Most controllers return a typed `ApiResponse<T>` envelope; a few older ones (Receivables, Wallets) return ad-hoc `Map.of("success", …, "data", …)`. **Standardizing on `ApiResponse<T>` is a recommended cleanup.**
- Corporate/entity scoping is passed via request context/headers. A hardcoded `DEMO_CORPORATE_ID` fallback appears in a few controllers (Party, Payables) — a demo affordance to remove before multi-tenant production.

---

## 5. Domain & Data Model

### 5.1 Ownership chain
```
Corporate (customer, KYC)
  └─ Legal Entity  (subsidiary / branch / SPV / treasury-center; consolidation %, IHB limits)
       └─ Physical / Bank Account  (real BaNCS account; home-bank vs external)
            └─ Virtual Account  (the overlay; hierarchy within a Program)
```
- **Corporate** — top-level customer with KYC status.
- **Legal Entity** — the entity tree (self-referential parent, treasury-center flag, ownership %, consolidation method, IHB lend/borrow limits & exposure). Owns physical accounts.
- **Physical Account** — real external/home bank account. Home-bank is derived **authoritatively from the configured BIC** (`vam.home-bank.bic`), not a seeded column — this distinguishes pool-eligible mirror VAs from external-bank shadows.
- **Virtual Account** — the heart of the model (see §5.2).

### 5.2 Virtual Account model
A rich entity (`virtual_accounts`) carrying: identifiers (`vaNumber`, `viban`), scope (`programId`, `corporateId`, `physicalAccountId`, `owningEntityId`), balances (current / available / aggregated / mirror / balance-in-base + FX fields), hierarchy (`parentAccountId`, `hierarchyLevel`, materialized path), and IHB participation fields.

**Account categories** encode the hierarchy role: `ROOT`, `AGGREGATION`, `PHYSICAL_MIRROR` (shadow of a bank account), `CURRENCY_MIRROR` (same-currency roll-up + FX to base), `TRANSACTION`, `COLLECTION`, `DISBURSEMENT`, `INTERCOMPANY`, `SETTLEMENT`, `EXCEPTION`. **Special types** (`SETTLEMENT`/`EXCEPTION`/`REGULAR`) drive fee routing.

Canonical hierarchy shape:
```
ROOT (corporate, base currency)
 └─ CURRENCY_MIRROR (per currency, FX→base)
     └─ PHYSICAL_MIRROR / Shadow (per bank account)
         └─ Transactional VAs (COLLECTION / DISBURSEMENT / WALLET / ESCROW …)
         └─ SETTLEMENT VA + EXCEPTION VA (per level, for fees & unallocated)
```

**VIBAN** — Virtual IBANs are issued from pools with a real ISO-13616 **MOD-97** check-digit generator; invoice/order-linked VIBANs enable auto-reconciliation; a routing service (ROBO) resolves an inbound VIBAN to its VA for sub-5ms posting.

### 5.3 Single-entry movement ledger (the accounting core)
The load-bearing decision, aligned to the BaNCS statement API:

- Every transaction is a **single-entry movement per VA**. A VA-to-VA transfer produces **two linked rows** (a DEBIT on source, a CREDIT on target) sharing a `correlationId` / transaction-group — *not* one double-entry record.
- Movements are stored in **`va_movements`** with: `movementType` (CREDIT/DEBIT, amount always positive), `balanceBefore`/`balanceAfter` (running balance per VA), a per-VA sequential id (statement ordering), `linkedMovementId` (pairs the two sides), `transactionGroupId`, `isInternal` + `counterpartyVaId`, and a `transactionContext` (STANDARD / ESCROW / WALLET / ECOMMERCE / IHB_POBO / IHB_ROBO / IHB_IC_LOAN / IHB_NETTING / IHB_FX / SWEEP), plus BaNCS sync + reversal fields.
- The **MovementType** enum has ~50 values (transfer in/out, sweep in/out, pool ops, IC/IHB settlement, POBO_DEBIT/ROBO_CREDIT, wallet/card/points, exception).
- **Two views** are exposed: the raw legs, and a **grouped "business view"** (net effect + expandable legs, optionally hiding internal Shadow/Settlement legs) — a CFO view vs an audit view.
- **Statement generation** is then a straight query over `va_movements` (credit XOR debit populated, running balance), which is exactly the shape camt.053 expects.

### 5.4 Intercompany position ledger
Intercompany activity (POBO/COBO/loans/netting) is tracked as bilateral positions per entity-pair per currency, with a canonical ordering (`entity_a < entity_b`) and a movement ledger (position-before/after) separate from the fund-movement ledger — so IC *exposure* and IC *cash* are distinct.

### 5.5 Current-state vs target-state
The three architecture docs in `docs/` describe a **redesigned unified model** that is partly aspirational:

| Concept | As-built (current) | Redesigned (target) |
|---|---|---|
| Specialized accounts | `Program` + `VirtualAccount` (wallets/escrow ride on VAs) — **largely realized** | `va_programs` + `program_accounts` (1:1 to a VA, external_reference / expected vs received / variance) |
| IHB | `IhbLoan`/`IhbDeposit` + INTERCOMPANY VAs; legacy `IhbEntity` **deprecated** in favor of `LegalEntity` | `ihb_configurations` (policy only) + `ihb_entity_mappings` + `ihb_account_designations` + `ihb_allocations` |
| Ledger | `va_movements` single-entry — **realized** | same, plus `transaction_groups`, `ic_positions` real-time ledger |
| Account holders | Party / Beneficiary / inline e-commerce payer | unified `program_account_holders` (INDIVIDUAL/CORPORATE/ANONYMOUS/SUBSIDIARY) |

The single-entry ledger, program container, VA hierarchy, and IHB-as-overlay principles are **built**; the fully-unified `va_programs`/`program_accounts`/`ic_positions` schema and SCD-Type-2 temporal history are the **forward roadmap**.

---

## 6. Capability Domains (bounded contexts)

| # | Domain | Core capabilities |
|---|---|---|
| 1 | **Accounts & Structure** | Virtual accounts, physical/bank accounts, VIBAN issuance & routing, account linking, programs, balance hierarchy, entity balance tree, shadow accounts, currency mirrors, reorganization (M&A/move) |
| 2 | **Parties & Entities** | Corporates, legal entities, parties/counterparties, beneficiaries |
| 3 | **Payments & Collections** | Transactions (multi-leg), transfers, receivables (AR), payables (AP), ISO 20022, settlement VAs, exceptions |
| 4 | **Liquidity Management** | Multi-bank liquidity, notional pooling, cash concentration/sweeps, netting, funds availability, balance aggregation, FX rates, forecasting, simulator |
| 5 | **Intercompany** | POBO, COBO, intercompany dashboard & bilateral settlement, transfer-pricing validation |
| 6 | **In-House Bank** | IHB current accounts, IC loans & deposits, treasury-center rates, IHB interest |
| 7 | **Credit & Interest** | Credit agreements, facilities, multi-level credit limits, interest configuration & accruals |
| 8 | **Specialty Programs** | Wallets, Escrow, E-Commerce collections, Seller collections, Merchant onboarding |
| 9 | **Tax & Charges** | Tax jurisdictions/config (VAT, withholding + treaties), charge/fee engine, program charge overrides, fee posting |
| 10 | **Integration & Sync** | Connectors, Open Banking (PSD2) consent, BaNCS client + store-locally-sync-later, sync admin |
| 11 | **Insights** | Treasury Copilot (assistant), dashboards, cockpit |
| — | **Administration** | Market profiles, KYCC/compliance, settings |

(The full feature enumeration and maturity ratings are in `FEATURE_CAPABILITY_MAP.md`.)

---

## 7. Cross-Cutting Concerns (NFRs)

### 7.1 Security & Auth
OAuth2 Resource Server + JWT bearer tokens. The Copilot inherits the app's auth context and handles **no** credentials/API keys. **Gap:** multi-tenancy is enforced only at the application layer via `customer_id` FKs — there is **no DB-level isolation**. Recommendation: PostgreSQL **Row-Level Security** keyed on `current_setting('app.current_customer')`, and removal of `DEMO_CORPORATE_ID` fallbacks.

### 7.2 Audit
A generic `audit_logs` table exists. The Copilot writes full-provenance action rows (`COPILOT_ACTION_EXECUTED`/`_CANCELLED` with tool, params, conversation id, source). Recommendation: domain-specific audit for balance changes, reconciliation matches, and approval-workflow decisions.

### 7.3 Sync / offline (BaNCS fallback)
Store-locally-sync-later: local VA balance cache + per-VA `bancs_sync_status`, a sync queue, and health status. Resilience4j circuit breaker guards the core; `BancsClientImpl` uses real-REST-with-mock-fallback. **Multi-bank freshness:** shadow-balance staleness threshold 60 min, refresh cadence 15 min; a pluggable `BalanceRefreshService` adapter (real `CoreBankingAdapter` + `StubAdapter`). **Gap:** SWIFT / Open-Banking / H2H rails for real-money movement are stub-backed today; balance-cache TTL/invalidation SLA is not yet formalized.

### 7.4 Standards & interoperability
ISO 20022 is first-class: **pain.001** (payment initiation, incl. POBO & bulk), **pain.002** (status), **camt.052** (intraday), **camt.053** (statement), **camt.054** (debit/credit notification) — with real XML generation (`Camt053XmlGenerator`, ~969 lines) and a validation service (~624 lines). Inward posting resolves accounts via VIBAN/VA repositories and posts to the movement ledger.

### 7.5 Performance & scaling
Local balance caching to minimize core-banking round trips; JDBC batch size 500 with ordered inserts/updates (tuned for the forecast orchestrator's bulk `saveAll`); heavy indexing on `va_movements` (per-VA sequence, group, linked, context, status, sync). Volume assumptions: VAs 50K–500K, transactions 1M–100M/yr, e-commerce VIBANs up to 10M/yr. **Gaps:** no EOD position snapshots (historical positions require replay — recommend `position_snapshots` + nightly job); no point-in-time / effective-dated (SCD-2) history.

### 7.6 Localization — market profiles
A boot-time **market profile** (UAE/KSA/UK/EU/US/SG via `VAM_MARKET_PROFILE`) sets currency, country, locale, timezone, weekend definition, base-rate type, home-bank BIC/name, IBAN country, and suggested currencies — so the UI defaults per market without per-concern config calls. Default home-bank BIC is Emirates NBD (`EBILAEADXXX`).

---

## 8. AI Treasury Copilot

An in-app conversational assistant (right-side drawer) grounded in real domain data.

- **No LLM today — a deterministic intent router.** Pipeline: persist user message → `IntentRouter` (regex slot extraction + priority-ordered intent match — the "brain") → `IntentExecutor` (intent → tool calls) → `ResponseComposer` (per-intent markdown) → persist assistant message **before** streaming (durable across disconnect) → `TokenStreamer` paces SSE `meta`→`token`→`done` (~30 ms/chunk for a live-typing feel).
- **12 intents** (position, position-by-bank, list rules, failed sweeps, explain rejection, idle accounts, recent activity, statement summary, pause rule, set alert, greeting, unknown). UNKNOWN returns a polite refusal + suggested prompts.
- **Tool registry.** 9 tools as `@Component implements CopilotTool` (auto-collected). **7 read tools** wrap real repositories (`get_position`, `get_accounts`, `get_sweep_status`, `get_sweep_instructions`, `get_statement_lines`, `get_audit_trail`, `get_rejection_codes`). **2 write tools** (`pause_sweep_rule`, `set_balance_alert`). Each tool's `parameterSchema` deliberately **mirrors the Anthropic tool-use JSON schema**, so an LLM can serialize tools directly.
- **Human-in-the-loop write guardrails (the notable design).** Write tools **never mutate directly** — they create an `ActionProposal` (PENDING, TTL ≈10 min) and return an action-card payload. The user confirms; the confirm endpoint verifies still-PENDING-and-unexpired (auto-EXPIRES otherwise), dispatches via `ActionExecutorService`, marks EXECUTED, and writes an audit row. The path is **idempotent** (second confirm returns the same result) and CANCELLED blocks execution.
- **LLM-ready.** The reasoning layer is designed to swap to a real model as a single-class change (`vam.ai.copilot.mode: stub|llm`, an `AnthropicLlmClient` over WebFlux WebClient); tools, persistence, streaming, drawer, and action cards stay unchanged. The 12 intent templates become few-shot examples.

---

## 9. Maturity Heatmap

Legend: **● Production-grade** (entity + repo + real service logic) · **◐ Partial** (real core, some stubbed endpoints) · **○ Mock/UI-shell** (hardcoded/random data).

| Domain | Capability | Maturity |
|---|---|---|
| Accounts & Structure | Virtual Accounts | ● |
| | Physical/Bank Accounts | ● *(BaNCS sync stubbed)* |
| | VIBAN issuance & routing | ● |
| | Account linking / attachments | ● |
| | Programs | ● |
| | Balance hierarchy / entity tree | ● *(`TreasuryHierarchyController` all-mock)* |
| | Shadow accounts / currency mirrors | ● |
| | Reorganization (move / M&A) | ◐ *(execution real; approval workflow + history unbuilt)* |
| Parties & Entities | Corporates / Legal entities | ● *(Corporate CRUD thin)* |
| | Parties / Counterparties | ● |
| | Beneficiaries | ◐ *(`verify` just flips a flag)* |
| Payments & Collections | Transactions (multi-leg) | ● |
| | Receivables (AR) | ● |
| | Payables (AP) | ◐ *(CRUD/POBO/IC/netting real; approval + batch stubbed)* |
| | ISO 20022 payments & statements | ● |
| | Settlement VAs / Exceptions / Fee posting | ● |
| | Statements (legacy) | ◐ *(`download`/`history` stubbed; camt path real)* |
| Liquidity | Multi-bank liquidity | ● *(external rails stubbed)* |
| | Notional pooling | ● |
| | Cash concentration / sweeps | ● |
| | Netting cycles | ● *(cross-ccy at 1:1)* |
| | Funds availability | ● |
| | Balance aggregation | ● |
| | Balance structure (mutations) | ◐ *(create/move/export/refresh mock)* |
| | FX rates | ● *(no live provider feed)* |
| | Forecasting | ● *(most mature; ML/seasonality out of scope)* |
| | Simulator | ● |
| Intercompany / IHB | POBO / COBO / Intercompany | ● *(POBO `history` stubbed)* |
| | In-House Bank (unified) | ● *(legacy `IhbEntity` deprecated)* |
| Credit & Interest | Agreements / facilities / limits | ● |
| | Interest config & accruals | ● |
| Specialty | Wallets | ● *(reuses VA/Program)* |
| | Escrow | ○ *(entity exists, controller mock)* |
| | E-Commerce dashboard / Merchant onboarding | ○ *(real e-commerce lives in Receivables)* |
| Tax & Charges | Tax / charges / overrides | ● |
| Integration | Connectors / Open Banking | ● |
| | BaNCS client | ◐ *(REST + mock fallback)* |
| | Sync Admin | ○ *(synthetic demo data)* |
| Admin | Market profiles | ● *(thin, config exposure)* |
| | KYCC / compliance | ○ *(mock queue, no persistence)* |
| Insights | Treasury Copilot | ◐ *(scaffolding real; reasoning is a stub router, no LLM)* |

---

## 10. Key Risks & Recommendations

1. **Multi-tenant isolation is application-only.** Add PostgreSQL RLS; remove `DEMO_CORPORATE_ID` fallbacks. *(Highest priority before production multi-tenant.)*
2. **Schema management ambiguity.** Flyway is present but disabled; `ddl-auto:update` drives schema. Decide one path (recommend Flyway-managed migrations for production auditability) and align `CLAUDE.md`.
3. **Documentation drift.** Java version, port, and migration mechanism in `CLAUDE.md` do not match the code — correct to Java 17 / 8053 / JPA-DDL (or change the code to match the docs).
4. **Stub modules presented as features.** Escrow, KYCC, Merchant onboarding, E-Commerce dashboard, and Sync Admin are UI shells; two (Escrow) even have an unused entity. Prioritize wiring or clearly label as demo.
5. **External money rails are stubbed.** SWIFT/Open-Banking/H2H execution and live FX feeds are adapters over stubs; real-money movement needs the production adapters and settlement testing.
6. **Duplicated surfaces.** POBO/COBO exist on both dedicated and intercompany-scoped paths; pick canonical entry points. Standardize the response envelope on `ApiResponse<T>`.
7. **Approval workflows are partial.** Reorganization and payables approvals have execution but no persisted approval/history — close before production.
8. **Copilot reasoning.** Deterministic router is brittle to unanticipated phrasing; the LLM swap is designed but not done — gate with the `mode` flag and retention cleanup before v1.

---

*Companion documents:* [`FEATURE_CAPABILITY_MAP.md`](FEATURE_CAPABILITY_MAP.md) (feature list + capability map) · [`EPICS_AND_USER_STORIES.md`](EPICS_AND_USER_STORIES.md) (delivery backlog).
