# Aperture — User Manual

**Corporate Digital Banking · Treasury Intelligence Platform**

| | |
|---|---|
| Product | Aperture v1.0 (platform 4.3.0) |
| Audience | Corporate treasury users, finance operations, bank administrators |
| Last updated | 24 August 2026 |
| Screens | 91 captures taken live from the running application, demo corporate **TestMNC** — every view, tab, and dialog |

---

## Contents

1. [Getting Started](#1-getting-started)
2. [Overview Surfaces](#2-overview-surfaces) — Dashboard · Multi-Bank Liquidity · Statements
3. [Accounts & Structure](#3-accounts--structure)
4. [Parties & Entities](#4-parties--entities)
5. [Payments & Collections](#5-payments--collections)
6. [Liquidity Management](#6-liquidity-management)
7. [Credit & Interest](#7-credit--interest)
8. [Specialty Programs](#8-specialty-programs)
9. [Administration](#9-administration)
- [Appendix A — Troubleshooting](#appendix-a--troubleshooting)
- [Appendix B — Glossary](#appendix-b--glossary)

---

# 1. Getting Started

## 1.1 Accessing Aperture

| Surface | URL |
|---|---|
| Web portal | `http://localhost:3000` |
| REST API | `http://localhost:8080/api` (UK market profile: `:8053/api`) |
| API explorer (Swagger) | `…/api/swagger-ui.html` |

Sign in with your corporate credentials. Your user chip (top-right) shows your name and role; click it for profile and sign-out options.

## 1.2 The application layout

Every page shares the same frame:

- **Sidebar (left)** — the full module tree, grouped into sections. Sections collapse; the one holding the current page auto-expands. **Search menu…** at the top jumps to any page by name.
- **Header (top)** — page title with section breadcrumb, global **search** (`⌘K`) across accounts and transactions, the **entity selector**, dark-mode toggle, and notifications.
- **Scope bar** — on data pages, **Corporate** and **Entity** selectors sit above the content. Aperture is multi-tenant: *everything you see is scoped to the selected corporate* (and optionally one legal entity). If a page looks empty, check the scope first — see [Appendix A](#appendix-a--troubleshooting).
- **Treasury Copilot** — the floating button (bottom-right) opens the AI assistant on any page.

## 1.3 Choosing your working scope

1. Pick a **Corporate** (e.g. *TestMNC*) in the scope bar; all KPIs, tables, and charts now reflect that corporate only.
2. Optionally narrow to a single **legal entity**; leave at *All Entities* for group-wide views.
3. **Clear scope** resets both. Your selection persists across pages and sessions.

## 1.4 Display preferences

**Dark mode** — moon icon in the header. Tables consistently offer **Refresh**, **Export**, filters, and pagination at the top-right of each card.

---

# 2. Overview Surfaces

## 2.1 Dashboard

![Dashboard](manual/img/dashboard.png)

Your morning cockpit. The workbench tabs along the top swap the canvas without leaving the page:

| Tab | Contents |
|---|---|
| **Cash Position** (default) | Group cash by account — *Account · Entity · Bank · Current · Available · Freshness* — with **By currency / By bank** pivots and a **Full liquidity view** shortcut into §2.2 |
| **Payments** | Outgoing activity summary; **Review** jumps to items awaiting approval; **All payments** opens the ledger |
| **Receivables** | Collection status and unmatched-payment queue |
| **Liquidity** | Pool and sweep snapshot |
| **FX** | Rate summary (full table in §6.8) |
| **Reports / Admin** | Reporting shortcuts and admin quick-links |

Click any account row to drill into its statement and movements.

## 2.2 Multi-Bank Liquidity

![Multi-Bank Liquidity](manual/img/multi-bank-liquidity.png)

Aggregated balances across every connected bank, split **home-bank vs external**. The freshness strip tracks data quality — **Stale** (age exceeds threshold), **Failed** (refresh errored), **Never refreshed** — and the **All / Home-bank / External** chips filter the population.

**By Bank view** — one card per institution with its accounts underneath (*Shadow · Freshness · Entity · Bank Account · Bank Balance · Committed · Effective*). The *Action* column offers a per-account **Refresh**; external accounts are marked *sweep source only*.

![By Bank](manual/img/multi-bank-liquidity__by-bank.png)

**By Currency view** — the same accounts re-grouped under AED / EUR / GBP / SAR / SGD headers, so you can read a single currency's group position at a glance.

![By Currency](manual/img/multi-bank-liquidity__by-currency.png)

## 2.3 Statements

![Statements](manual/img/statements.png)

The **ISO 20022 Statement Generator** produces four statement types — pick the type, then fill the form that appears:

| Type | Purpose | Form fields |
|---|---|---|
| **camt.053 — Account Statement** | End-of-period statement for one account | *Account · From Date · To Date* |
| **camt.054 — Notifications** | Credit/debit notifications for a window | *Account · From · To* |
| **Aggregated Statement** | One statement across an account subtree | *Account · From · To · **Include Children*** |
| **Standard Statement** | Non-ISO legacy format | *Account · Period* |

![camt.054 Notifications](manual/img/statements__notifications.png)

The aggregated form adds the **Include Children** toggle — tick it to roll a hierarchy node's entire subtree into one statement:

![Aggregated statement](manual/img/statements__aggregated.png)

**Statement History** below lists prior generations (*Reference · Account · Period · Format · Generated*) with **View** (inline XML) and **Download** actions. Long-running statements run as async jobs and appear here when done.

---

# 3. Accounts & Structure

## 3.1 Virtual Accounts

![Virtual Accounts](manual/img/accounts.png)

The registry of every virtual account (VA) under the corporate — transactional VAs, aggregation nodes, shadow accounts, exception accounts. Columns: *Account · Program · Category · Balance · Status*.

**Table / Tree toggle** — *Table* is the flat, sortable list; *Tree* redraws the same accounts as their hierarchy, scoped by the **Program** selector:

![Tree view](manual/img/accounts__tree.png)

**Filters panel** — opens above the table with *Status*, *Account Type*, and *Currency* selectors; **Apply** filters the list, **Clear All** resets:

![Filters](manual/img/accounts__filters.png)

**Row actions** — click a VA for its detail drawer: balances, limits (daily/weekly/monthly/annual), KYC configuration, MCC restrictions, publish state (VIBAN exposure), movement history. Lifecycle actions (suspend, block, reactivate, close, publish/unpublish) live in the row menu. **Export** downloads the filtered list.

## 3.2 Bank Accounts

![Bank Accounts](manual/img/physical-accounts.png)

Physical (real) bank accounts, grouped in the **Banks Overview** by institution (HSBC UK, Emirates NBD, JPMorgan Chase, Lloyds, …). Home-vs-external derives from the BIC. Columns: *Account · Entity · Bank · Balance · Shadow · Entity Link · Sync*.

1. Register an account with BIC/IBAN; mark pooling/sweep eligibility.
2. **Shadow** links the account to its mirror VA (§3.8); **Sync** pulls the latest CBS balance into it.
3. **Export** for reconciliation extracts; **Previous / Next** page through large books.

## 3.3 VIBAN Management

![VIBAN Management](manual/img/viban.png)

Virtual IBAN issuance. The **Overview** tab shows the **Pool Summary** — capacity and utilisation per pool.

**Pools tab** — each pool card (e.g. *UAE Virtual IBANs*, *ORDER VIBAN*) with **Assign**, **Bulk Assign**, **View**, and **Generate** (mint more VIBANs into the pool, MOD-97 valid):

![Pools](manual/img/viban__pools.png)

**VIBANs tab** — the individual numbers: *VIBAN · Pool · Assigned To · Reference · Status · Usage · Amount*. **Assign** binds a free VIBAN to a VA; invoice- or order-linked VIBANs carry their reference here:

![VIBANs](manual/img/viban__vibans.png)

Inbound routing (VIBAN → VA) is automatic and sub-5 ms; no user action needed.

## 3.4 Account Linking

![Account Linking](manual/img/account-attachments.png)

Many-to-many relationships between VAs and legal entities. Columns: *Account/Entity · Relationship · Status · Validity · Limits*.

**New Attachment dialog** — the fields:

![New Attachment](manual/img/account-attachments__new-attachment.png)

| Field | Meaning |
|---|---|
| Virtual Account / Aggregation Node * | The account (or subtree node) being linked |
| Legal Entity * | The entity gaining the relationship |
| Relationship Type | **Owner · Beneficiary · Authorized · Guarantor · Collateral** |
| Set as Primary | Marks this the account's primary relationship of that type |
| Effective From / To | Validity window (open-ended if *To* is blank) |

**Create Attachment** saves; ownership transfers and limit edits happen from the row afterwards.

## 3.5 Programs

![Programs](manual/img/programs.png)

Configuration templates that govern how VAs behave. Columns: *Program · Type · Corporate · VAs · Balance · Features · Status*.

**Create New Program wizard** — Step 1 (Basic Information):

![New Program](manual/img/programs__new-program.png)

1. Pick the **Program Type**: *Collection · VIBAN · Escrow · Wallet · In-House Bank · Payables · Receivables · Loyalty · Gift Card*.
2. Fill *Program Code*, *Program Name*, *Currency*, optional *Max Virtual Accounts* and a backing *Physical Account*.
3. Later steps configure type-specific features (VIBAN strategy, wallet limits, hierarchy levels). Existing programs can also be **cloned** from the row menu.

## 3.6 Balance Hierarchy

![Balance Hierarchy](manual/img/hierarchy.png)

The program-scoped VA tree: pick a program, then **Expand All / Collapse** to navigate. Balances roll up in real time; clicking a node opens **Entity Details** (balances, children, breadcrumb). New hierarchies initialize through the wizard (level names + dimensions); structural moves happen in Reorganization (§3.10).

## 3.7 Entity Balance Tree

![Entity Balance Tree](manual/img/entity-balance-tree.png)

The same roll-up keyed by **legal entity**: each node aggregates the balances of all VAs that entity owns — "how much cash does MNC-UK hold?" at a glance. **Refresh** recomputes; **Expand / Collapse** navigate.

## 3.8 Shadow Accounts

![Shadow Accounts](manual/img/shadow-accounts.png)

`PHYSICAL_MIRROR` VAs that mirror real bank accounts inside the VA ledger, so physical cash participates in hierarchies and sweeps. Each card shows the linked bank account and last sync. **Sync** refreshes from the core banking system; **Attach** parents transactional VAs under the shadow.

## 3.9 Currency Mirrors

![Currency Mirrors](manual/img/currency-mirrors.png)

`CURRENCY_MIRROR` VAs roll up balances per currency (AED, EUR, GBP, USD, INR) with FX conversion to base. **Details** breaks a mirror down by level and node; **Recalc** forces recomputation after rate or structure changes; the **Active FX Rates** panel shows the rates in use.

## 3.10 Reorganization

![Reorganization](manual/img/hierarchy-operations.png)

Corporate-structure operations, each with a limit policy (**STRICT / TRANSFER / ABSORB / APPROVAL**) governing what happens to moved limits. The **Quick Actions** launch:

| Action | Scope |
|---|---|
| **Relocate Account** | One VA to a new division |
| **Move Division** | An entire subtree |
| **Acquisition** | Absorb another corporate |
| **Merger** | Combine two corporates |
| **Divestiture** | Spin a subtree off to a new corporate |

**Relocate Account dialog** — pick the VA to move (searchable, program-scoped), then the destination node and limit policy:

![Relocate](manual/img/hierarchy-operations__relocate.png)

**Acquisition wizard** — select the *Target Corporate to Acquire*; the wizard pre-validates (cycles, currency mismatches, limit conflicts) before anything commits, and currency mirrors recalculate afterwards:

![Acquisition](manual/img/hierarchy-operations__acquisition.png)

**Current Hierarchy** shows the live tree for orientation; **Recent Operations** lists prior runs with status.

---

# 4. Parties & Entities

## 4.1 Legal Entities

![Legal Entities](manual/img/legal-entities.png)

The corporate's legal-entity tree — branches, SPVs, treasury centers — with per-entity consolidation and IHB limits. **Expand All / Collapse** to navigate; select an entity to edit attributes or move it under a new parent.

## 4.2 Parties & Counterparties

![Parties & Counterparties](manual/img/parties.png)

The directory of vendors, customers, and employees. Columns: *Party · Entity · Roles · Payment Factory · KYC · Status*. Each party carries KYC state, risk rating, screening, bank accounts, documents — and the **payment-factory attributes** that other modules depend on: POBO eligibility (§5.5), intercompany configuration with IC credit limit, netting eligibility (§6.6).

---

# 5. Payments & Collections

## 5.1 All Transactions

![All Transactions](manual/img/transactions.png)

The full movement ledger. Two controls define what you see:

- **Status chips** — *All / Credits / Debits / Pending* (counts shown live).
- **Business / Ledger toggle** — *Business* groups multi-leg postings into net effect (columns: *Operation · Account · Counterparty · Net Amount*, expandable to legs); *Ledger* lists every atomic leg (*Reference · Account · Counterparty · Amount*) — the audit view:

![Ledger view](manual/img/transactions__ledger.png)

**Filters** adds date-range, account, and counterparty constraints:

![Filters](manual/img/transactions__filters.png)

**New Transaction** posts a manual movement; **Simulate Collection** generates an inbound test credit (demo environments); the row menu's *ISO message* renders the pain.001 XML for any transaction; **Export** downloads the current view.

## 5.2 Transfers

![Transfers](manual/img/transfers.png)

Guided VA-to-VA transfer: choose the transfer type card, pick source and destination, **Continue** to the preview (balances before/after, fees), **Submit Transfer**.

**Transfer History** — prior submissions (*Reference · Type · From · To · Amount · Status · Date*) with **Export**:

![Transfer History](manual/img/transfers__history.png)

## 5.3 Receivables (AR)

![Receivables](manual/img/receivables.png)

Invoices and collections. Tabs: **All Invoices · Intercompany · Pending Netting · VIBANs · COBO History** — the counts show each queue's size. Main columns: *Invoice · Customer · Amount · Due Date · Status · VIBAN*.

**VIBANs tab** — collection VIBANs issued for invoices: *VIBAN · Reference · Customer · Expected · Received · Status · Expires*. Watch *Expected vs Received* to spot short payments:

![AR VIBANs](manual/img/receivables__vibans.png)

**Create Receivable dialog** — a full invoice builder:

![Create Invoice](manual/img/receivables__create-invoice.png)

1. **Customer** — pick the party (KYC state shown).
2. **Invoice Details** — *Invoice Number, Invoice Date, Amount, Payment Terms, Due Date, Reference (PO/Contract), Description*.
3. **Collection Account** — the receiving VA; **Generate** mints a dedicated VIBAN so the inbound payment auto-matches.
4. Sub-tabs extend the invoice: **Line Items**, **Tax & Charges**, **Payment Link**, **COBO** (route collection through treasury), **Hierarchy**, **Documents**, **Reminders**.
5. **Save Draft** or **Create Invoice**; the **Summary** panel recaps before submission.

Unmatched inbound payments (money that fits no invoice) surface in the Exceptions flow (§5.9) where they can be *matched*, *returned*, or *escalated*.

## 5.4 Payables (AP)

![Payables](manual/img/payables.png)

Outgoing obligations with entity context. Tabs: **All · Pending · Intercompany · POBO · Netting**; the *Route* column shows how each payable settles (direct, POBO via treasury, or inside a netting cycle).

**Pending tab** — the approval queue; each row awaits sign-off before payment execution:

![Pending payables](manual/img/payables__pending.png)

**Create Payable dialog** — *Select Vendor* (payment-factory attributes decide available routes), then amount/due date; the **Payment Summary** panel recaps; **Save Draft** or **Submit for Approval**:

![New Payable](manual/img/payables__new-payable.png)

## 5.5 POBO Payments

![POBO Payments](manual/img/intercompany-pobo.png)

**Payments-On-Behalf-Of** — treasury pays a subsidiary's vendor and books an intercompany receivable (optionally an IHB loan) against the subsidiary. The workbench tab bar — shared with COBO (§5.6) and the Intercompany Dashboard (§6.7) — is the map of the whole intercompany product:

| Tab | Contents |
|---|---|
| **Overview** | Corporate Position Summary (who owes whom, net), Quick Actions, Settlement Options |
| **POBO** | The POBO transaction table (this page's default) — *Treasury (Creditor) → Subsidiary (Owes) · Amount · IHB Loan · Status* |
| **COBO** | Collections mirror (§5.6) |
| **Settlement** | Pairs ready to settle + settlement history |
| **Recharges** | Management-fee / cost-recharge flows |
| **All Transactions** | Every intercompany movement |
| **Entities / Entity Pairs** | Per-entity and pairwise net positions |

**Overview tab**:

![Overview](manual/img/intercompany-pobo__overview.png)

**Settlement tab** — *Entity Pairs — Ready for Settlement* with **Bilateral Settlement** and **Add to Netting** per pair, the intercompany VAs involved, and *Recent Settlements* (*Reference · Type · Entities · Net Amount · Status · Settled At*):

![Settlement](manual/img/intercompany-pobo__settlement.png)

**Entity Pairs tab** — pairwise positions with **Details** and **Settle** shortcuts:

![Entity pairs](manual/img/intercompany-pobo__entity-pairs.png)

**New POBO Payment dialog**:

![New POBO](manual/img/intercompany-pobo__new-payment.png)

| Field | Meaning |
|---|---|
| Treasury (Payer) | The entity executing the payment |
| Subsidiary (Behalf Of) | The entity whose obligation is being paid |
| Amount / Currency / Description | Payment details |
| **Create IHB loan for subsidiary reimbursement** | Tick to book the receivable as a formal in-house-bank loan (appears in §6.5) |

**Preview** shows the postings before you commit.

## 5.6 COBO Collections

![COBO Collections](manual/img/intercompany-cobo.png)

**Collections-On-Behalf-Of** — treasury collects customer receipts for a subsidiary. Table: *Treasury (Collector) → Subsidiary (Behalf Of) · Amount · VIBAN · Status*.

**Setup COBO Collection dialog** — mirror of the POBO form, with one key extra: **Generate VIBAN for collection** issues a treasury-owned VIBAN that customers pay into, so receipts route straight to treasury while the ledger tracks the subsidiary's entitlement:

![Setup COBO](manual/img/intercompany-cobo__setup.png)

## 5.7 ISO 20022 Payments

![ISO 20022 Payments](manual/img/iso20022.png)

Direct ISO 20022 processing, one tab per flow:

**Inward Payment** (default) — paste or upload a **pacs.008** credit (or camt.054 notification); **Process Inward Payment** posts it to the target VA. Results land in **Recent Results**.

**Outward Payment** — a payment form that *generates* the ISO message: *Source Virtual Account, Amount, Service Level, Beneficiary Name/Account/BIC, Remittance Information*, and a **Pay On Behalf Of (POBO)** toggle. **Process Outward Payment & Generate pain.001**:

![Outward](manual/img/iso20022__outward.png)

**Bulk Payment** — one debtor VA, many beneficiaries (*Beneficiary · Account · Amount · Reference* grid) → a single multi-transaction pain.001:

![Bulk](manual/img/iso20022__bulk.png)

**Payment Status** — query by reference (*Search By + Search Value*) → the pain.002 status report:

![Status](manual/img/iso20022__status.png)

**Statement** — shortcut to the statement generator (§2.3).

## 5.8 Settlement VAs

![Settlement VAs](manual/img/settlement-vas.png)

Per-level settlement and exception accounts inside a program hierarchy (shown: a 7-level structure topped by *FINAL IHB*). When fees post, the resolver walks sibling level → hierarchy → program → corporate to find the right settlement VA; with none found, the posting parks in an **Exception** VA.

Filter chips **All / Settlement / Exception** split the list; **Add Settlement VA** creates one at the selected level — keep one per currency per level to avoid exception parking.

**Exception filter** — parked items (same queue as §5.9) reached in context:

![Exceptions](manual/img/settlement-vas__exceptions.png)

## 5.9 Exceptions

![Exceptions](manual/img/exceptions.png)

Unallocated or parked postings — fees that found no settlement VA, unmatched receipts. **Filters** narrows the queue; for each item, review the suggestion engine's proposed target, then **Allocate** to the right account (or write off / escalate from the row).

---

# 6. Liquidity Management

## 6.1 Cash Forecast

![Cash Forecast](manual/img/forecasting.png)

A rolling cash forecast built from the live AR/AP book plus recurring patterns (payroll, tax, rent) and carried-forward manual adjustments.

**Reading the page**
- Headline strip: **Opening balance** (anchored at 0 in Sprint 1), **Closing** at horizon, **Trough week** — the tightest week, with its date.
- **Weekly cash position** chart: bars = net weekly cashflow (green in / red out), orange line = running closing balance. *Click a bar to see the exact forecast lines that produced it* — every line carries a source reference to its originating invoice, pattern, or adjustment.
- **Category breakdown**: week-by-week totals per category (AR Collections, Payroll, Tax, Rent & Lease).

**Horizon tabs** — **30d** re-buckets to daily/weekly granularity over one month; **12m** stretches to a year (pattern-driven flows dominate the far end):

![30-day horizon](manual/img/forecasting__30d.png)

![12-month horizon](manual/img/forecasting__12m.png)

**Other controls** — the **ccy** selector filters to one currency (mixed-currency sums are only meaningful filtered); **Run forecast now** regenerates from current data in seconds, stamping the run time in the header.

## 6.2 Cash Concentration

![Cash Concentration](manual/img/sweeping.png)

Rule-driven sweeping. The **Sweep Rules** tab lists each rule — named for what it does (e.g. *"Albion Manchester Target 500k → London"*) — covering **ZBA**, **target-balance**, **threshold**, and **percentage** types, physical (REAL) or book-only (NOTIONAL/HYBRID), each with rails, cut-offs, and bank-calendar respect.

**Execution History tab** — every run: *Reference · Rule · Flow · Amount · Status · Time*. Skipped rows carry the reason (below trigger, cut-off passed); failures link to the error:

![Execution history](manual/img/sweeping__history.png)

**Deficit funding** — accounts below target during a sweep can auto-create an IHB loan to top up (§6.5).

## 6.3 Simulator

![Simulator](manual/img/simulator.png)

What-if sandbox for account structures. **New scenario** clones the live structure into a *Proposed structure* workspace:

![New scenario](manual/img/simulator__new-scenario.png)

| Control | Purpose |
|---|---|
| **Structure** | Edit the proposed tree — move accounts, change pooling membership |
| **Add rule / Add pool** | Introduce hypothetical sweep rules or pools |
| **Diff vs live** | Side-by-side changes against production |
| **Compare** | Interest/liquidity outcome vs baseline |
| **View assumptions** | The rate and balance assumptions behind the numbers |
| **Fork / Save / Discard** | Branch, keep, or drop the scenario |
| **Propose as live rules** | Hand the winning structure to an approver — nothing changes in production until then |

## 6.4 Notional Pooling

![Notional Pooling](manual/img/notional-pooling.png)

Pools whose member balances offset **without moving funds**. Cards (e.g. *ALL AED ACCOUNTS*) show net position; interest is computed on the net and allocated back by the configured strategy.

**View Members** expands per-account contributions — who is long, who is short, and each member's share of the pooled benefit:

![Members](manual/img/notional-pooling__members.png)

## 6.5 In-House Bank

![In-House Bank](manual/img/ihb.png)

The corporate's internal bank. The **Overview** shows **Settlement Status**, **Recent Loans**, **Recent Deposits**, and two settlement controls: **Dry Run** (preview interest + principal movements, no postings) and **Run Settlement** (post them).

**IHB Entities tab** — each participating entity with **Position / Lend / Deposit** shortcuts:

![Entities](manual/img/ihb__entities.png)

**Current Accounts tab** — intercompany current accounts; **New Current Account** opens one:

![Current accounts](manual/img/ihb__current-accounts.png)

**Loans tab** — *Reference · Lender · Borrower · Principal · Outstanding · Rate · Accrued · Status*, with **Repay** per row. Loans created automatically by POBO (§5.5) and sweep deficit-funding (§6.2) appear here too:

![Loans](manual/img/ihb__loans.png)

**Create Intercompany Loan dialog**:

![New loan](manual/img/ihb__new-loan.png)

| Field | Meaning |
|---|---|
| Lender / Borrower Entity * | The two sides of the loan |
| Principal Amount * / Currency | Loan size |
| Interest Type · Base Rate (%) · Rate Type | Pricing; **Use Treasury Rate** pulls the house rate |
| Repayment | Bullet or amortising |
| Disbursement / Maturity Date * | Term |

## 6.6 Netting Cycles

![Netting Cycles](manual/img/netting-enhanced.png)

Multilateral intercompany netting. Cycles (e.g. *Q1 2026*, monthly EUR/GBP cycles) progress through **build → populate obligations → calculate → approve → settle**; the calculation nets every pairwise obligation to one payment per participant and reports the savings.

**Details** opens a cycle's workspace with **Overview / Entries / Positions / Settlement** tabs — obligations in, net positions out, settlement instructions last:

![Cycle details](manual/img/netting-enhanced__details.png)

## 6.7 Intercompany Dashboard

![Intercompany Dashboard](manual/img/intercompany.png)

The single pane over all intercompany positions — same tab bar as §5.5, opening on the **Corporate Position Summary**. From here: **Export Report**, **New POBO Payment**, **Setup COBO Collection**, **Bilateral Settlement**, or **Add to Netting Cycle**.

## 6.8 FX Rates

![FX Rates](manual/img/fx-rates.png)

The rate table used across the platform (mirrors, netting, forecasts): *Pair · Rate · Inverse · Bid · Ask · Type · Source · Updated · Status*. Rates arrive from the configured source; manual overrides are flagged by *Type*.

---

# 7. Credit & Interest

## 7.1 Credit Limits

![Credit Limits](manual/img/credit-limits.png)

Credit limits arranged over the entity hierarchy, per currency. The currency chips (AED 100.0B · GBP 2.0B · USD 5.0B) switch the tree; limits cascade — a child's utilisation consumes its parent's headroom.

![GBP limits](manual/img/credit-limits__gbp.png)

**Add Currency** introduces a new limit currency; **Add Limit** sets one on an entity node, with utilisation and headroom displayed in-tree.

## 7.2 Interest Configuration

![Interest Configuration](manual/img/interest-config.png)

Interest schemes applied to accounts or pools. **New Config** opens the full setup:

![New configuration](manual/img/interest-config__new-config.png)

| Group | Fields |
|---|---|
| Identity | *Configuration Name · Type · Currency · Target Type · Status* |
| **Credit interest** (positive balances) | *Base Rate Type · Base Rate (%) · Spread (%) · Min Balance* |
| **Debit interest** (negative/overdraft) | *Penalty Rate (%)* |
| **Calculation parameters** | *Day Count* convention · *Compounding* frequency |

**Create Configuration** activates the scheme; accruals then appear in §7.3.

## 7.3 Interest Accruals

![Interest Accruals](manual/img/interest-accruals.png)

Accrual engine output per account/period: *Reference/Type · Entity/Account · Period · Principal · Rate · Days · Accrued · Status*. **Run Accrual** executes a period; export via **CSV / Excel**. Posted accruals appear in the movement ledger.

---

# 8. Specialty Programs

## 8.1 Escrow

![Escrow](manual/img/escrow.png)

Digital escrow: funds held in a dedicated VA until milestone conditions release them. Tabs **All Contracts · Active · Disputed · Completed**; columns *Contract · Parties · Amount · Status · Progress · Expires*.

**Disputed tab** — frozen contracts awaiting resolution (also reachable via **View Disputes**):

![Disputed](manual/img/escrow__disputed.png)

**Create Escrow Contract dialog** — *Contract Name, Contract Type, Seller (Corporate), Buyer (Beneficiary), Contract Amount, Currency*; funding on creation opens the escrow VA, and milestones release progressively:

![New contract](manual/img/escrow__new-contract.png)

## 8.2 Wallets

![Wallets](manual/img/wallet.png)

Wallet programs for end-customer stored value. Tabs **Overview / Programs / Wallets**; **Bulk Load** tops up many wallets at once.

**Programs tab** — each wallet program's card with initialization state:

![Programs](manual/img/wallet__programs.png)

**Issue New Wallet dialog** — *Type, Initial Load, Daily Limit, Monthly Limit*, and **Auto-trigger KYC** (starts KYC automatically when the customer has an ID). Program-level wallet config (§3.5) supplies the defaults:

![Issue wallet](manual/img/wallet__issue.png)

## 8.3 E-Commerce Collections

![E-Commerce Collections](manual/img/ecommerce-collections.png)

Order-level inbound collections for online merchants: *Reference · Merchant · Payment Method · Amount · Status · Date*. Escrow-backed orders hold funds until delivery confirmation.

## 8.4 Seller Collections

![Seller Collections](manual/img/seller-collections.png)

Marketplace flows. **Collections** — customer receipts per seller (*Reference · Seller · Payment Method · Amount · Status · Date*).

**Settlements tab** — periodic payouts: *Settlement Ref · Seller · Gross · Commission · Net · Status · Date*, with **Process Settlements** to execute a due batch:

![Settlements](manual/img/seller-collections__settlements.png)

---

# 9. Administration

## 9.1 Tax & Charges

![Tax & Charges](manual/img/tax-charges.png)

Fee, charge, and tax setup across jurisdictions; the **Quick Reference** panel summarises active rates. Fees post double-entry to the resolved settlement VA (§5.8).

**Tax Configurations tab** (default) — *Tax Code · Type · Jurisdiction · Rate · Applies To · Flags · Status*; **Add Tax Config** creates one; **Import / Export** for bulk maintenance.

**Charge Configurations tab** — service charges: *Charge Code · Type · Category · Rate/Amount · Scope · Waivers · Status*, with **Add Charge**:

![Charges](manual/img/tax-charges__charges.png)

**Jurisdictions tab** — *Jurisdiction · Country · Tax Authority · Supported Taxes · Currency · Status*, with **Add Jurisdiction**:

![Jurisdictions](manual/img/tax-charges__jurisdictions.png)

## 9.2 Integrations

![Integrations](manual/img/integrations.png)

External connections with per-connection health. **Active Connections** (default) lists what's live — SAP Production, Kyriba TMS, Bank Statement SFTP, … — each with **Manage** (credentials, schedules).

**Available Connectors tab** — the catalogue (SAP S/4HANA, Oracle Fusion, Dynamics 365, NetSuite, Workday, …), filterable by category chips *ERP · Treasury · Open Banking · Payments · Banking · Generic*; **Add Connection** starts setup:

![Connectors](manual/img/integrations__connectors.png)

**Sync History tab** — every exchange: *Connection · Flow · Direction · Time · Records · Status · Details*:

![Sync history](manual/img/integrations__sync-history.png)

**API Docs** links the Swagger explorer for direct API integration.

## 9.3 Settings

![Settings](manual/img/settings.png)

Platform preferences: profile, notification rules, security options, default display settings.

---

# Appendix A — Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Pages show empty tables / "select a corporate" | No corporate scope selected | Pick a corporate (e.g. **TestMNC**) in the scope bar (§1.3) |
| Forecast page: "No completed forecast run" | Never run for this corporate | Click **Run forecast now** (§6.1) |
| Forecast totals look inflated on "All ccy" | Multiple currencies summed | Filter to one currency (§6.1) |
| Balances stale on Multi-Bank Liquidity | Refresh failed or threshold exceeded | Freshness chips → per-account **Refresh** (§2.2) |
| Fees appear in an EXCEPTION account | No settlement VA for that currency/level | Add one in **Settlement VAs** (§5.8), then **Allocate** the parked items (§5.9) |
| API unreachable on `:8080` | UK market profile active | Backend serves on **`:8053/api`**; the portal proxies automatically |
| Backend fails to start on AVD/RDP (JDK 21 loopback error) | AF_UNIX sockets disabled | Start with `-Djdk.net.unixdomain.tmpdir=C:\Temp` (see project README/CLAUDE.md) |

# Appendix B — Glossary

| Term | Meaning |
|---|---|
| **VA** | Virtual Account — a ledger account inside Aperture, not at the bank |
| **VIBAN** | Virtual IBAN — externally addressable IBAN routing to a VA |
| **Shadow account** | VA mirroring a real bank account's balance |
| **Currency mirror** | VA aggregating all balances of one currency (+FX to base) |
| **POBO / COBO** | Payments / Collections On Behalf Of — treasury pays or collects for a subsidiary |
| **IHB** | In-House Bank — internal loans, deposits, current accounts |
| **ZBA** | Zero-Balance Account sweep |
| **Notional pooling** | Interest offset across accounts without moving funds |
| **Netting cycle** | Periodic multilateral compression of intercompany obligations |
| **Settlement VA** | Designated account where fees/charges post within a hierarchy level |
| **pain.001 / pacs.008 / camt.05x** | ISO 20022: payment initiation / interbank credit / account reporting |
| **KYCC** | Know Your Customer's Customer — party-level due diligence |

---

*Generated from the running application (demo dataset, corporate TestMNC). 91 captures in `docs/manual/img/`; harness: `docs/manual/capture.mjs` — extend its `STATES` map when pages gain new tabs, then re-run.*
