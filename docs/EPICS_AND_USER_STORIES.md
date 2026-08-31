# Aperture — Epics & User Stories

Delivery backlog derived from [`FEATURE_CAPABILITY_MAP.md`](FEATURE_CAPABILITY_MAP.md). Each **Epic** maps to a capability domain; each **Story** follows the *As a … I want … so that …* form with acceptance criteria and a build status.

**Status legend:** ✅ **Done** (production-grade) · 🟡 **Partial** (real core, gaps noted) · ⬜ **Not built** (mock/UI-shell or absent).
Feature IDs (e.g. `A1`) cross-reference the Feature List.

> 📸 **Live walkthrough:** [`Aperture-Screens.pdf`](Aperture-Screens.pdf) — 53 screens captured from the running app against seeded data (9 corporates · 23 accounts · 16 banks · 6 currencies). See **Part C — Live Screen Index** for a page-by-page map.


**How to read acceptance criteria:** written in Given/When/Then where useful; they describe the *intended* behaviour, so 🟡/⬜ stories double as the gap backlog.

---

## Epic 1 — Virtual Account Structuring
*Capability: Account structuring. Goal: model an entire corporate as a virtual hierarchy without physical accounts.*

**US-1.1 — Create a virtual account** `A1` ✅
As a **cash manager**, I want to create a virtual account under a program and hierarchy node, so that I can represent a use case without opening a bank account.
- AC: VA is created with number, currency, category, owning entity, and parent; status defaults to a valid initial state; appears in `AccountsPage`.
- AC: creation validates program eligibility and inherits program defaults.

**US-1.2 — Manage VA lifecycle** `A1` ✅
As a **cash manager**, I want to suspend/block/reactivate/close a VA, so that I can control its usage.
- AC: each transition enforces valid state machine; closing requires zero balance; audit entry written.

**US-1.3 — Configure limits, KYC and MCC restrictions** `A2` ✅
As a **risk officer**, I want to set daily/weekly/monthly/annual limits, KYC status, and MCC restrictions on a VA, so that spend is controlled and compliant.
- AC: limits reset on their period; KYC verify transitions state; MCC list enforced at authorization.

**US-1.4 — Publish a VA for external visibility** `A3` ✅
As a **product admin**, I want to publish/unpublish a VA (VIBAN exposure), so that external parties can pay into it only when intended.
- AC: publish assigns/exposes VIBAN; unpublish/suspend hides it; publish-status queryable.

**US-1.5 — Create a VA with hierarchy dimensions** `A4` ✅
As a **treasurer**, I want to create a VA by specifying dimensions, so that intermediate ROOT/AGGREGATION nodes are auto-created.
- AC: missing parent nodes are created; resulting path is consistent and materialized.

**US-1.6 — Configure programs** `A9` ✅
As a **product admin**, I want to define and clone programs (collection/wallet/escrow/IHB/payables) with VIBAN strategy and fee/limit defaults, so that new use cases onboard through one flow.
- AC: program CRUD + clone; type/status lookups; VAs inherit program config.

**US-1.7 — Build and navigate the balance hierarchy** `A10 A11` ✅
As a **treasurer**, I want to view the VA/entity tree with subtree, breadcrumb, and move, so that I understand and reshape structure.
- AC: tree/subtree/breadcrumb endpoints; initialize bootstraps a program hierarchy; move updates paths.

**US-1.8 — Link accounts to entities** `A8` ✅
As a **product admin**, I want to attach entities to a VA as owner/beneficiary/authorized-signer/collateral with limits, so that authorization and ownership are explicit.
- AC: quick-create per relationship type; transfer-ownership; check-authorization returns allow/deny; expiring attachments listed.

---

## Epic 2 — Virtual IBAN & Auto-Reconciliation
*Capability: VIBAN & auto-reconciliation. Goal: issue per-invoice/order VIBANs and auto-match inbound payments.*

**US-2.1 — Issue and manage VIBAN pools** `A6` ✅
As a **product admin**, I want to create VIBAN pools and generate/assign/return VIBANs (incl. bulk), so that I can allocate identifiers at scale.
- AC: MOD-97 valid IBANs; pool utilization & thresholds tracked; assign/return lifecycle.

**US-2.2 — Route an inbound payment by VIBAN (ROBO)** `A7` ✅
As the **platform**, I want to resolve an inbound VIBAN to its VA in sub-5ms, so that receipts post to the right account instantly.
- AC: lookup/route resolves VIBAN→VA; payment posts a CREDIT movement; unknown VIBAN routes to exception.

**US-2.3 — Link a VIBAN to an invoice/order** `A6 C5` ✅
As a **cash manager**, I want to assign a VIBAN to a specific invoice/order, so that payment auto-reconciles to the receivable.
- AC: assign-invoice binds VIBAN↔receivable; inbound receipt matches and updates receivable status.

---

## Epic 3 — Cash Visibility (single pane)
*Capability: Cash visibility. Goal: one aggregated, multi-currency, multi-bank position.*

**US-3.1 — Aggregate balances up the hierarchy** `D7` ✅
As a **treasurer**, I want balances to roll up the VA tree with FX to base, so that I see consolidated positions in real time.
- AC: propagate/aggregate endpoints; multi-currency positions per corporate/program; scheduled aggregation runs.

**US-3.2 — Mirror physical accounts as shadow VAs** `A12` ✅
As a **cash manager**, I want shadow (PHYSICAL_MIRROR) VAs synced from the core, so that bank balances appear in the virtual structure.
- AC: shadow created per bank account; sync updates bankBalance + timestamp + data source; children attach beneath.

**US-3.3 — Consolidate currencies to base** `A13` ✅
As a **treasurer**, I want CURRENCY_MIRROR VAs that FX-convert same-currency children to base, so that consolidation avoids double-counting.
- AC: recalculate per node/program/corporate; level/node breakdowns; balance-in-base computed with rate + source.

**US-3.4 — View a multi-bank position** `D1` ✅ *(external rails stubbed)*
As a **treasurer**, I want balances grouped by bank × currency split home-held vs external, so that I see cross-bank liquidity.
- AC: summary aggregates shadows; refresh (or refresh-if-stale) updates a shadow; freshness pill reflects staleness.
- Gap: SWIFT/Open-Banking/H2H refresh adapters are stubs.

---

## Epic 4 — Liquidity Optimization
*Capability: Liquidity optimization. Goal: concentrate and net cash, minimize external movement and idle balances.*

**US-4.1 — Define and run cash-concentration sweeps** `D3` ✅
As a **cash manager**, I want rule-driven ZBA/target/threshold/% sweeps with execution modes and rails, so that cash concentrates automatically.
- AC: rule CRUD + toggle; execute runs due sweeps honoring cutoffs; history per rule; orphan-source cleanup.

**US-4.2 — Auto-fund deficits during a sweep** `D4` ✅
As a **cash manager**, I want accounts below target auto-funded via IHB loans, so that shortfalls are covered without manual intervention.
- AC: deficit-funding creates `IhbLoan` records for accounts under target during the sweep run.

**US-4.3 — Operate notional pools** `D2` ✅
As a **treasurer**, I want to group VAs into a notional pool and allocate pooled interest by strategy, so that I benefit from offset without moving funds.
- AC: pool + member CRUD; calculate computes interest via DAILY_AVERAGE/MONTH_END/TIER; allocation strategy (contribution/equal/weighted) applied.

**US-4.4 — Run an intercompany netting cycle** `D5` ✅ *(cross-ccy 1:1)*
As a **treasury ops analyst**, I want to build a netting cycle, populate obligations, calculate net & savings, approve and settle, so that IC gross flows shrink.
- AC: cycle lifecycle DRAFT→…→SETTLED; populate pulls payables/receivables/POBO recharges; savings computed.
- Gap: cross-currency uses 1:1 instead of `FxRateService`; entity→VA settlement mapping TODO.

---

## Epic 5 — Funding & Credit Control
*Capability: Funding & credit control. Goal: cascade limits down, gate every debit.*

**US-5.1 — Check funds availability before a debit** `D6` ✅
As the **platform**, I want to traverse VA→ROOT checking balance and credit limits at every level, so that no debit exceeds available funds+credit.
- AC: check returns per-level results; batch check; utilization updated post-transaction; LIFO limit release.

**US-5.2 — Manage credit agreements** `F1` ✅
As a **bank admin**, I want CBS-synced master agreements with activate/suspend/expire, so that the credit envelope is authoritative.
- AC: agreement CRUD; totals (limit/utilized/available); `sync` + `needing-sync`.

**US-5.3 — Manage credit facilities** `F2` ✅
As a **credit officer**, I want overdraft/term/revolving facilities with draw-down/repay and drawing-power, so that entities can borrow within terms.
- AC: draw-down/repay adjust utilization; drawing-power computed; link-physical associates a bank account.

**US-5.4 — Administer multi-level credit limits** `F3` ✅
As a **treasurer**, I want a corporate→group→entity→VA limit tree with utilize/release and breach dashboards, so that credit is controlled hierarchically.
- AC: internal group/entity/VA tiers; availability/blocked/approval-required checks; corporate dashboards (totals/warnings/critical/breached); recalculate-utilization.

---

## Epic 6 — Payments & the Movement Ledger
*Capability: Payments processing + reporting. Goal: move money to standards and produce statements.*

**US-6.1 — Post transactions as a single-entry ledger** `C1` ✅
As the **platform**, I want each transaction recorded as single-entry movements per VA with running balances and correlation, so that statements mirror the BaNCS API.
- AC: transfer creates two linked movements (debit/credit); balanceBefore/After maintained; per-VA sequence assigned.

**US-6.2 — Execute transfers with preview** `C2` ✅
As a **cash manager**, I want to preview then execute VA-to-VA transfers (and bulk), so that I confirm the effect before committing.
- AC: preview returns net effect + fees; execute posts movements; bulk-transfer processes a batch.

**US-6.3 — View grouped business vs audit ledger** `C3` ✅
As a **CFO/analyst**, I want transactions grouped to net effect with expandable legs and an internal-leg toggle, so that I see either a business or an audit view.
- AC: grouped endpoints return net + legs; `includeInternal` shows/hides shadow/settlement legs.

**US-6.4 — Process ISO 20022 inward payments** `C8` ✅
As the **platform**, I want to post pacs.008/camt.054 inward credits to the correct VA, so that receipts reconcile.
- AC: inward message parsed & validated; account resolved via VIBAN/VA; CREDIT movement posted.

**US-6.5 — Initiate ISO 20022 outward payments** `C9` ✅
As a **cash manager**, I want pain.001 generation (incl. POBO and bulk) and pain.002 status, so that outbound payments meet standards.
- AC: pain.001 XML generated & schema-valid; bulk supported; status query returns pain.002.

**US-6.6 — Generate ISO 20022 statements** `C10` ✅
As a **treasurer**, I want camt.052/053/054 for a VA (single and hierarchically aggregated) with XML and history, so that reporting is standards-compliant.
- AC: camt.053 single + aggregated (recursive child rollup, maxDepth); camt.052 intraday; camt.054 notification; XML download; async job status.

**US-6.7 — Post fees to settlement VAs** `C14 C12` ✅
As the **platform**, I want charges/taxes posted double-entry to the resolved settlement VA (exception fallback), so that fee accounting is correct.
- AC: resolver walks the tree to the settlement VA; debit source / credit settlement with correlation; falls back to exception VA.

**US-6.8 — Handle exceptions and unmatched funds** `C13 C6` ✅
As a **treasury ops analyst**, I want to investigate/allocate/write-off exception postings and match/return/escalate unmatched receipts, so that no funds are stranded.
- AC: exception workflow with suggestions; unmatched match/return/escalate updates receivable state.

---

## Epic 7 — Collections & Receivables (AR)
*Capability: Collections / AR automation.*

**US-7.1 — Manage receivables and record payments** `C5` ✅
As a **subsidiary finance manager**, I want invoices, e-commerce orders, payment VIBANs/links, and POS collections in one AR module, so that collections are unified.
- AC: invoice CRUD + record-payment; e-commerce orders with escrow tracking; POS collections/stats; entity/party/route scoping.

**US-7.2 — Match unmatched payments** `C6` ✅
As a **cash manager**, I want unmatched inbound payments listed with match/return/escalate, so that receipts are reconciled or actioned.
- AC: unmatched list; match binds to a receivable; return/escalate transitions state.

---

## Epic 8 — Payables & Disbursement (AP)
*Capability: Payables / disbursement.*

**US-8.1 — Manage payables with entity context** `C7` ✅
As a **subsidiary finance manager**, I want AP records with rich search, stats, and entity scoping, so that I track obligations.
- AC: CRUD + search filters + stats; entity/IC/netting attributes on the record.

**US-8.2 — Approve and execute payables** `C7` 🟡
As an **approver**, I want to submit/approve/reject/schedule/record-payment and execute in batches, so that disbursement is controlled.
- AC: submit→approve→execute transitions persist and post movements.
- Gap: `submit/approve/reject/schedule/record-payment`, `batch/approve`, `batch/schedule` are stubs ("would implement in service") that echo the unchanged payable; `execute` is real. **Backlog: implement the approval state machine and batch operations.**

---

## Epic 9 — Pay/Collect on Behalf Of (POBO/COBO)
*Capability: Pay/collect on behalf of.*

**US-9.1 — Authorize a payer-on-behalf relationship** `E2` ✅
As a **treasurer**, I want payer↔behalf-entity authorizations with single/daily/monthly limits, so that on-behalf payments are governed.
- AC: authorization CRUD; limit setting; suspend/reactivate; validate checks authorization without executing.

**US-9.2 — Preview and execute a POBO payment** `E1` 🟡
As a **treasury ops analyst**, I want to preview cost (charge + IHB loan) then execute a POBO payment (single and batch), so that I pay a vendor for a subsidiary.
- AC: preview returns charge + funding estimate; execute posts the payment and initiates recharge.
- Gap: `GET /pobo/history` and `/history/{id}` are TODO stubs. **Backlog: implement POBO history.**

**US-9.3 — Recharge the subsidiary** `E3` ✅
As a **treasurer**, I want to recharge the subsidiary via IHB loan / deposit offset / netting with arm's-length validation, so that intercompany accounting is compliant.
- AC: recharge approve/reject/settle-ihb/settle-netting; arm's-length validation endpoint.

**US-9.4 — Collect on behalf of a subsidiary (COBO)** `E4` ✅
As a **collecting entity**, I want to submit→approve→collect a COBO receivable and recharge back, so that I receive customer funds for a subsidiary.
- AC: submit/approve/reject/collect workflow; preview fees; pending-approval & stats; recharge/IHB-deposit linkage.

---

## Epic 10 — Intercompany Settlement & Compliance
*Capability: IC settlement & compliance.*

**US-10.1 — Track bilateral intercompany positions** `E5` ✅
As a **treasurer**, I want a unified IC ledger with bilateral positions and entity pairs, so that I see who owes whom.
- AC: IC transaction CRUD (incl. from-payable/from-receivable); bilateral-position & entity-pairs; position summary & entity reports.

**US-10.2 — Settle bilateral positions and feed netting** `E6` ✅
As a **treasury ops analyst**, I want to settle bilateral positions and add transactions to a netting cycle, so that exposure is cleared efficiently.
- AC: settle-bilateral posts settlement; add-to-netting attaches to a cycle; netting-eligibility checked.

**US-10.3 — Validate transfer pricing** `E7` ✅
As a **compliance officer**, I want IC transactions validated for arm's-length compliance, so that transfer-pricing rules are met.
- AC: validate-transfer-pricing returns compliance result on a transaction.

---

## Epic 11 — In-House Bank
*Capability: In-house banking. (Legacy `IhbEntity` path deprecated in favor of unified `LegalEntity` model.)*

**US-11.1 — Operate IHB current accounts** `E8` ✅
As a **treasury center**, I want INTERCOMPANY current accounts with running balance, credit/debit interest, and overdraft, so that subsidiaries bank internally.
- AC: create/position/deposit/withdraw/transfer; calculate & post interest.

**US-11.2 — Manage IC loans and deposits** `E9` ✅
As a **treasury center**, I want to issue/repay IC loans (cross-currency, full lifecycle) and take/withdraw deposits, so that internal funding works end-to-end.
- AC: loans + repay; deposits + withdraw; FX handled via `IhbFxService`; lifecycle PENDING→…→SETTLED/MATURED.
- Gap: entity→VA fee mapping TODO (fees skipped until mapping established).

**US-11.3 — Offer treasury-center rates** `E10` ✅
As a **treasurer**, I want per-corporate offered/indicative rates with interest, stats, and settlement, so that internal pricing is transparent.
- AC: treasury-rates & indicative-rate endpoints; per-corporate interest/stats/settlement.

---

## Epic 12 — Interest & Pricing
*Capability: Interest & pricing.*

**US-12.1 — Configure interest** `F4` ✅
As a **bank admin**, I want internal/external interest configs (base+spreads, day-count, accrual/compounding, min-balance, penalty), so that interest is defined consistently.
- AC: config CRUD + activate/suspend; typed setters; `calculate` returns interest for inputs; spread analysis.

**US-12.2 — Resolve and attach effective config per VA** `F5` ✅
As the **platform**, I want to attach/resolve the effective interest config per VA with propagation and rate sync, so that each VA earns/pays the right rate.
- AC: attach/detach external & internal; resolve returns effective config; propagate to descendants; sync-rates; auto-attach.

**US-12.3 — Accrue and report interest** `F6` ✅
As a **treasurer**, I want interest accrual calculation and reports, so that I can review earned/paid interest.
- AC: accrual reports render in `InterestAccrualReportsPage`.

---

## Epic 13 — Tax & Charges
*Capability: Tax & fee management.*

**US-13.1 — Configure tax jurisdictions and rates** `H1` ✅
As a **bank admin**, I want jurisdictions, VAT configs, and withholding-tax treaties, so that tax is jurisdiction-aware.
- AC: jurisdiction & tax-config CRUD; withholding treaty rates; standard-rate lookup.

**US-13.2 — Calculate and apply taxes** `H2` ✅
As the **platform**, I want to calculate tax/net-amount/payable-taxes and apply to a reference, so that payments carry correct tax.
- AC: calculate-tax / net-amount / payable-taxes; apply-taxes/{referenceId}; convenience calculate-vat, calculate-pobo-fee.

**US-13.3 — Configure and apply charges** `H3` ✅
As a **bank admin**, I want charge configs with calculate/apply and waive/partial-waive, so that fees are managed with exceptions.
- AC: charge-config CRUD; calculate charges/payment-total; apply; waive & partial-waive lifecycle.

**US-13.4 — Override charges per program** `H4` ✅
As a **product admin**, I want per-program (wallet) fee overrides with migration and wallet-fee calculation, so that programs price independently.
- AC: override CRUD; program wallet config; migrate-fees; calculate wallet-fees; admin initialize.

---

## Epic 14 — Reorganization (M&A)
*Capability: Reorganization. Goal: restructure the tree on corporate events.*

**US-14.1 — Move a VA or aggregation subtree** `A14` ✅
As a **treasurer**, I want to move a VA/aggregation with a limit policy (STRICT/TRANSFER/ABSORB/APPROVAL), so that restructuring preserves limit integrity.
- AC: movable-VAs/aggregations listed; move applies limit policy; validate/move pre-checks; currency mirrors recalculated.

**US-14.2 — Acquire / merge / divest entities** `A15` 🟡
As a **treasurer**, I want to run acquisition/merge/divestiture with limit policies and validation, so that the structure reflects M&A events.
- AC: acquire/merge/divest execute with limit transfer + mirror recalc + settlement re-resolution; validation endpoints.
- Gap: `history`, `pending-approvals`, `approve/reject` are TODO no-ops; operation history is not persisted. **Backlog: implement approval workflow + history persistence.**

---

## Epic 15 — Forecasting & Simulation
*Capability: Forecasting & scenario design.*

**US-15.1 — Generate a cash forecast** `D10` ✅
As a **treasurer**, I want a ~13-week forecast per corporate from pluggable engines with variance and scenarios, so that I anticipate cash.
- AC: run generates lines from pattern/aging/manual engines; latest/{runId}/lines endpoints; per-engine failures isolated; scheduled runs.
- Note: seasonality/DRIVER/ML engines and a concurrency lock are explicitly out of Sprint-2 scope.

**US-15.2 — Design and activate a cash-concentration structure** `D11` ✅
As a **treasurer**, I want to design structures in a sandbox, score, fork A/B/C, snapshot a live-config diff, and activate into real sweep rules, so that I test before committing.
- AC: scenario CRUD + score (frozen server-side) + fork + snapshot; activate writes live sweep rules in a rolled-back-on-failure tx behind the `simulator.v3.activation` gate with audited failures.

---

## Epic 16 — Counterparty & Entity Management
*Capability: Counterparty & entity management.*

**US-16.1 — Manage corporates** `B1` ✅
As a **bank admin**, I want corporate CRUD with KYC approve/status/stats, so that customers are onboarded.
- AC: corporate CRUD; kyc/approve; status; stats.

**US-16.2 — Manage the legal-entity tree** `B2` ✅
As a **treasurer**, I want a legal-entity tree (branch/SPV/treasury-center) with children/ancestors, move-parent, and treasury-center flag, so that the org is modeled.
- AC: by-corporate/tree/active/with-accounts/treasury-centers; create/child-create; move parent; set treasury-center; consolidation & IHB limits captured.

**US-16.3 — Manage parties/counterparties** `B3 B4` ✅
As a **cash manager**, I want counterparties with KYC/risk/screening, bank accounts, documents, and payment-factory attributes (POBO/IC/netting eligibility), so that payments and collections have valid counterparties.
- AC: party CRUD + detail; bank-accounts & documents; kyc/risk/screening; pobo-eligible, intercompany config + IC credit limit, netting-eligible; entity-scoped queries.

**US-16.4 — Maintain a beneficiary directory** `B5` 🟡
As a **cash manager**, I want a payee directory with verification, so that outbound payees are known and validated.
- AC: beneficiary CRUD + stats; verify confirms the payee.
- Gap: `verify` merely flips a flag — no real validation/screening. **Backlog: implement real beneficiary verification (account/name/sanctions).**

---

## Epic 17 — Specialty Programs
*Capability: Specialty banking programs.*

**US-17.1 — Issue and operate wallets** `G1 G2` ✅
As a **program operator**, I want prepaid wallets (load/withdraw/transfer/bulk-load) with KYC gating and history, riding on the VA/Program model, so that wallet programs run without a separate ledger.
- AC: wallet + program CRUD; load/withdraw/transfer/bulk-load; suspend/reactivate/block; verify-KYC; transactions history.

**US-17.2 — Run digital escrow** `G3` ⬜
As a **corporate**, I want milestone/condition escrow (trade/RE/M&A/rent) with fund/release/dispute, so that conditional payments are secured.
- AC: contract CRUD; fund/release/dispute transitions; milestone & condition tracking; escrow VA linkage.
- **Status: not built.** `EscrowContract` entity exists but `EscrowController` returns mock maps and ignores it. (Narrow real escrow-release exists inside Receivables e-commerce orders.) **Backlog: wire controller to a real `EscrowService`/repository.**

**US-17.3 — Onboard merchants** `G6` ⬜
As a **merchant ops admin**, I want application→review→activate with settlement account, MCC, and commission, so that marketplaces onboard sellers.
- AC: onboard/review/approve persists a merchant with settlement config.
- **Status: not built.** Endpoints return generated placeholders; no merchant entity/persistence. **Backlog: create merchant entity + service.**

**US-17.4 — Track e-commerce & seller collections** `G4 G5` ⬜ *(real data via Receivables)*
As a **marketplace operator**, I want per-platform collection dashboards, settlements, and seller views, so that I manage marketplace cash.
- AC: dashboard stats/trends; collections & settlements; seller views.
- **Status: dedicated `EcommerceController` is mock;** genuine order/escrow data flows only through the Receivables e-commerce endpoints. **Backlog: back the dashboard with real Receivables data or remove the duplicate surface.**

---

## Epic 18 — Compliance (KYC / KYCC)
*Capability: Compliance.*

**US-18.1 — Review a KYCC compliance queue** `J2` ⬜
As a **compliance officer**, I want a review queue with document verification, beneficial-owner/PEP capture, screening, risk scoring, approve/reject/request-info, and expiry tracking, so that I screen the client's own customers.
- AC: pending queue; case detail; approve/reject/request-info; screening results; expiring cases.
- **Status: not built.** `KycController` returns hardcoded data; no KYC entity/persistence. **Backlog: build a KYC domain (case entity, screening integration, workflow).**

---

## Epic 19 — Integration & Connectivity
*Capability: Integration & connectivity.*

**US-19.1 — Manage connectors and data flows** `I1 I4` ✅
As an **integration engineer**, I want connectors/connections with test/reconnect and data-flow + field-mapping definitions, so that external systems integrate configurably.
- AC: connector/connection CRUD; test & reconnect; flow CRUD + mappings + toggle; sync trigger + logs.

**US-19.2 — Manage Open Banking (PSD2) consent** `I2` ✅
As an **integration engineer**, I want ASPSP discovery, authorize, callback, and refresh/revoke consent, so that account access is compliant.
- AC: aspsps list; authorize returns consent URL; callback (GET/POST) completes; refresh/revoke consent.

**US-19.3 — Fall back to local state when BaNCS is down** `I3` 🟡
As the **platform**, I want a BaNCS client that stores locally and syncs later with a circuit breaker, so that operations continue during core outages.
- AC: real REST when reachable; mock fallback when `bancs.api.mock-enabled`; Resilience4j breaker; per-VA sync status + queue.
- Gap: production H2H/settlement adapters and reconciliation are not fully wired.

**US-19.4 — Administer sync jobs** `I5` ⬜
As an **ops admin**, I want a sync-admin view of jobs/queue/stats/logs with trigger, so that I monitor synchronization.
- AC: jobs/queue/stats/logs; trigger a job type.
- **Status: not built.** `SyncAdminController` returns synthetic (`UUID.randomUUID()`) demo data. **Backlog: persist sync jobs and expose real metrics.**

---

## Epic 20 — Assisted Operations (Treasury Copilot)
*Capability: Assisted operations (AI).*

**US-20.1 — Ask natural-language treasury questions** `J4` 🟡
As a **treasurer**, I want to ask about positions, sweeps, rejections, idle accounts, and recent activity and get grounded answers streamed live, so that I get insight without navigating menus.
- AC: chat streams over SSE; read tools pull from real services; tool-call provenance rows shown; UNKNOWN returns a polite refusal + suggested prompts.
- Gap: reasoning is a deterministic regex intent router (12 intents) — **no LLM**; brittle to unanticipated phrasing. **Backlog: swap in a real LLM via the `mode: stub|llm` flag (`AnthropicLlmClient`).**

**US-20.2 — Take a guarded action from the assistant** `J4` ✅
As a **cash manager**, I want to pause a sweep rule or set a balance alert via the assistant with an explicit confirm step, so that write actions are safe and audited.
- AC: write tool creates a PENDING `ActionProposal` (TTL ≈10 min) → action card → confirm executes via `ActionExecutorService` → EXECUTED + audit row; idempotent on double-confirm; CANCELLED/EXPIRED blocks.

**US-20.3 — Preserve conversation history** `J4` ✅
As a **user**, I want my conversations and messages persisted (assistant message stored before streaming), so that history survives disconnects.
- AC: conversation/message persistence; tool_calls captured; list conversations & messages.
- Note: retention is "keep forever" (prototype) — **backlog: retention cleanup before v1.**

---

## Epic 21 — Platform Administration
*Capability: Platform administration.*

**US-21.1 — Localize by market profile** `J1` ✅
As a **bank admin**, I want a boot-time market profile (currency/country/locale/timezone/weekend/home-bank/IBAN country), so that the platform defaults correctly per market.
- AC: `/config/market-profile` returns the active profile; UI defaults consume it; switchable via `VAM_MARKET_PROFILE`.

**US-21.2 — View dashboards and cockpit** `J3` ✅
As a **treasurer**, I want home/cockpit/Treasury-2030 dashboards, so that I get an at-a-glance overview.
- AC: dashboard endpoints feed cockpit and classic/2030 dashboard pages.

**US-21.3 — Manage settings** `J5` ✅
As a **user**, I want application/user settings, so that I can configure my experience.

---

## Cross-Cutting / Platform Hardening Epic
*Non-functional stories drawn from the design document's risk register. All ⬜ unless noted.*

**US-X.1 — Enforce tenant isolation at the database** ⬜
As a **security engineer**, I want PostgreSQL Row-Level Security keyed on the current customer, so that cross-tenant data leakage is impossible even on a query bug. Remove `DEMO_CORPORATE_ID` fallbacks.

**US-X.2 — Adopt a single schema-management path** ⬜
As a **platform engineer**, I want production schema managed by Flyway migrations (not `ddl-auto:update`), so that schema changes are auditable and reversible.

**US-X.3 — Correct documentation drift** ⬜
As a **maintainer**, I want `CLAUDE.md` aligned to the code (Java 17, port 8053, JPA-DDL) or the code aligned to the docs, so that onboarding is accurate.

**US-X.4 — Standardize the API response envelope** ⬜
As an **API consumer**, I want every controller to return `ApiResponse<T>` (Receivables/Wallets currently return ad-hoc maps), so that clients parse responses uniformly.

**US-X.5 — Consolidate duplicated POBO/COBO surfaces** ⬜
As an **API consumer**, I want one canonical POBO/COBO entry point (currently on both dedicated and intercompany-scoped paths), so that the contract is unambiguous.

**US-X.6 — Wire real external money rails** ⬜
As a **payments engineer**, I want production SWIFT/Open-Banking/H2H adapters and live FX feeds (currently stubbed), so that real-money movement and cross-currency netting are accurate.

**US-X.7 — Add point-in-time / EOD snapshots** ⬜
As a **treasurer**, I want EOD `position_snapshots` and effective-dated (SCD-2) history, so that historical positions don't require transaction replay.

---

### Backlog summary (gap-closing stories)

| Priority | Story | Epic |
|---|---|---|
| P0 | US-X.1 Tenant isolation (RLS) | Hardening |
| P0 | US-X.6 Real external money rails | Hardening |
| P1 | US-8.2 Payables approval + batch | Payables |
| P1 | US-14.2 Reorg approval + history | Reorganization |
| P1 | US-20.1 LLM swap for Copilot | Copilot |
| P1 | US-X.2 Flyway migrations | Hardening |
| P2 | US-17.2 Escrow wiring | Specialty |
| P2 | US-18.1 KYC/KYCC domain | Compliance |
| P2 | US-9.2 POBO history | POBO |
| P2 | US-16.4 Real beneficiary verification | Counterparty |
| P2 | US-19.4 Real sync admin | Integration |
| P3 | US-17.3 Merchant onboarding | Specialty |
| P3 | US-17.4 E-commerce dashboard backing | Specialty |
| P3 | US-X.3/X.4/X.5 Docs/envelope/dedup cleanup | Hardening |
| P3 | US-X.7 Snapshots / temporal history | Hardening |

---

*Companion documents:* [`DESIGN_DOCUMENT.md`](DESIGN_DOCUMENT.md) · [`FEATURE_CAPABILITY_MAP.md`](FEATURE_CAPABILITY_MAP.md)
