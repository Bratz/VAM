# Aperture — Feature List & Feature-Capability Map

Companion to [`DESIGN_DOCUMENT.md`](DESIGN_DOCUMENT.md).

> 📸 **Live walkthrough:** [`Aperture-Screens.pdf`](Aperture-Screens.pdf) — 53 screens captured from the running app against seeded data (9 corporates · 23 accounts · 16 banks · 6 currencies). See **Part C — Live Screen Index** for a page-by-page map.

This document has two parts:

- **Part A — Feature List:** the enumerated, catalogued features grouped by domain, each with a maturity rating and the primary backend/frontend surface.
- **Part B — Feature-Capability Map:** the same features mapped to business capabilities, personas, and enabling technical components.

**Maturity legend:** **●** Production-grade · **◐** Partial (real core + stubs) · **○** Mock/UI-shell.

---

## Part A — Feature List

### Domain 1 — Accounts & Structure

| ID | Feature | What it does | Maturity | Primary surface |
|---|---|---|---|---|
| A1 | Virtual Account lifecycle | Create/update/close VAs; status transitions (suspend/block/reactivate); category & special-type assignment | ● | `VirtualAccountController` `/api/v1/virtual-accounts` · `AccountsPage`, `VaCreateModal` |
| A2 | VA limits & KYC | Daily/weekly/monthly/annual limits with resets; KYC config & verify; MCC restrictions | ● | `PATCH /{id}/limits,/kyc` · `LimitsTab`, `MccRestrictionsTab` |
| A3 | VA publish / VIBAN exposure | Publish/unpublish/suspend-publish/republish a VA for external visibility | ● | `.../publish*` |
| A4 | Dimension-driven VA creation | Auto-create intermediate ROOT/AGGREGATION nodes from hierarchy dimensions | ● | `POST /with-dimensions` |
| A5 | Physical / bank accounts | Register real bank accounts; home-vs-external (by BIC); pooling/sweep eligibility; balance sync | ● *(sync stubbed)* | `PhysicalAccountController` · `PhysicalAccountsPage` |
| A6 | VIBAN issuance & pooling | Issue Virtual IBANs from pools (MOD-97), assign/return, bulk-assign, invoice/order-linked | ● | `VibanController` · `VibanManagementPage` |
| A7 | VIBAN routing (ROBO) | Resolve inbound VIBAN → VA for sub-5ms payment posting | ● | `/vibans/lookup,/route,/{viban}/payment` |
| A8 | Account linking / attachments | Many-to-many VA↔entity (owner/beneficiary/authorized-signer/collateral); transfer ownership; auth checks | ● | `AccountAttachmentController` · `AccountAttachmentsPage` |
| A9 | Programs | Configuration templates (COLLECTION/VIBAN/ESCROW/WALLET/IHB/PAYABLES); clone; VIBAN strategy; wallet config | ● | `ProgramController` · `ProgramsPage` |
| A10 | Balance hierarchy | Program-scoped VA hierarchy: level config, node CRUD, tree/subtree/breadcrumb, move, initialize | ● | `HierarchyController` · `EntityBalanceTreePage`, `HierarchyInitWizard` |
| A11 | Entity balance tree | Legal-entity balance tree view | ● | `EntityBalanceTreePage` |
| A12 | Shadow accounts | PHYSICAL_MIRROR VAs mirroring bank accounts; CBS balance sync; parent for transactional VAs | ● | `ShadowAccountController` · `ShadowAccountsPage` |
| A13 | Currency mirrors | CURRENCY_MIRROR VAs: same-currency roll-up + FX→base; level/node breakdowns | ● | `CurrencyMirrorController` · `CurrencyMirrorPage` |
| A14 | Reorganization — move | Move a VA or aggregation subtree with limit policies (STRICT/TRANSFER/ABSORB/APPROVAL) | ◐ | `CorporateHierarchyOperationsController` · `MoveVaModal` |
| A15 | Reorganization — M&A | Acquire / Merge / Divest with limit policy + currency-mirror recalculation; pre-validation | ◐ *(approval + history unbuilt)* | `AcquisitionWizard`, `MergerWizard`, `DivestitureModal`, `OperationHistoryPage` |

### Domain 2 — Parties & Entities

| ID | Feature | What it does | Maturity | Primary surface |
|---|---|---|---|---|
| B1 | Corporates | Top-level customer + KYC approve/status/stats | ● *(thin CRUD)* | `CorporateController` |
| B2 | Legal entities | Entity tree (branch/SPV/treasury-center); children/ancestors; move parent; consolidation & IHB limits | ● | `LegalEntityController` · `LegalEntitiesPage` |
| B3 | Parties / counterparties | Vendors/customers/employees: KYC, risk, screening, bank accounts, documents | ● | `PartyController` · `PartiesPage` |
| B4 | Party payment-factory attributes | POBO eligibility, intercompany config + IC credit limit, netting eligibility, owning-entity scope | ● | `/parties/pobo-eligible,/intercompany,/netting-eligible` |
| B5 | Beneficiaries | Payee directory: CRUD, verify, stats | ◐ *(verify flips a flag)* | `BeneficiaryController` · `BeneficiariesPage` |

### Domain 3 — Payments & Collections

| ID | Feature | What it does | Maturity | Primary surface |
|---|---|---|---|---|
| C1 | Transactions (multi-leg ledger) | Money movement over `va_movements`; correlated legs; credit/debit | ● | `TransactionController` · `TransactionsPage` |
| C2 | Transfers | VA-to-VA transfer with preview; bulk transfer | ● | `/transactions/transfer(+preview),/bulk-transfer` · `TransfersPage` |
| C3 | Grouped "business view" | Net-effect grouping with expandable legs; hide/show internal legs (CFO vs audit view) | ● | `/transactions/grouped*` |
| C4 | Inline ISO message from txn | Generate pain.001 XML for a transaction | ● | `/transactions/{id}/iso-message` |
| C5 | Receivables (AR) | Invoices, e-commerce orders (escrow-tracked), payment VIBANs/links, POS, unmatched matching | ● | `ReceivablesController` · `EnhancedReceivablesPage`, `CreateReceivablePage` |
| C6 | Unmatched payment handling | Match / return / escalate unmatched inbound payments | ● | `/receivables/unmatched*` |
| C7 | Payables (AP) | AP with entity context; approval workflow; batch; payment execution | ◐ *(approval/batch stubbed)* | `PayablesController` · `EnhancedPayablesPage`, `CreatePayablePage` |
| C8 | ISO 20022 inward | Post inward pacs.008 / camt.054 credits to VAs | ● | `Iso20022PaymentController` `/inward/*` · `Iso20022PaymentsPage` |
| C9 | ISO 20022 outward | pain.001 generation (incl. POBO & bulk); pain.002 status | ● | `/outward/*,/status*` |
| C10 | ISO 20022 statements | camt.052 intraday, camt.053 statement (single + aggregated), camt.054 notification; XML + history + async jobs | ● | `Iso20022StatementController`, `VaStatementController` · `StatementsPage` |
| C11 | Legacy statements | Per-account/corporate statements with totals | ◐ *(download/history stubbed)* | `StatementController` |
| C12 | Settlement VAs | Per-level settlement/exception VAs; resolver walks the tree; hierarchy initialize | ● | `SettlementVaController` · `SettlementVaPage` |
| C13 | Exception transactions | Investigate / allocate / write-off unallocated postings; suggestion engine | ● | `ExceptionTransactionController` · `ExceptionDashboardPage` |
| C14 | Fee posting | Double-entry fee/charge/tax posting to resolved settlement VA; exception fallback | ● | `FeePostingController` |

### Domain 4 — Liquidity Management

| ID | Feature | What it does | Maturity | Primary surface |
|---|---|---|---|---|
| D1 | Multi-bank liquidity | Aggregate balances by bank × currency; home-held vs external; on-demand shadow refresh | ● *(rails stubbed)* | `MultiBankLiquidityController` · `MultiBankLiquidityPage` |
| D2 | Notional pooling | Group VAs into pools; compute & allocate pooled interest (strategy pattern) without moving funds | ● | `NotionalPoolController` · `NotionalPoolingPage` |
| D3 | Cash concentration / sweeps | Rule-driven ZBA/target/threshold/% sweeps (REAL/NOTIONAL/HYBRID); rails; cutoffs; history | ● | `SweepController` · `CashConcentrationPage` |
| D4 | Deficit funding | Auto-create IHB loans for accounts below target during sweeps | ● | `/sweeping/deficit-funding` |
| D5 | Netting cycles | Build cycle, populate obligations, calculate net & savings, approve, settle | ● *(cross-ccy 1:1)* | `NettingController` · `NettingCyclesPage`, `EnhancedNettingCyclesPage` |
| D6 | Funds availability | Pre-debit gate: traverse VA→ROOT checking balance + credit limits at every level; batch; LIFO release | ● | `FundsAvailabilityController` · `FundsAvailabilityPage` |
| D7 | Balance aggregation | Real-time + scheduled roll-up with FX; multi-currency positions; propagate | ● | `BalanceAggregationController` · `BalanceAggregationPage` |
| D8 | Balance structure | Hierarchical treasury tree with IC positions & participation flags; export/refresh | ◐ *(mutations mock)* | `BalanceStructureController` · `TreasuryHierarchyPage`, `Treasury2030DashboardPage` |
| D9 | FX rates | Spot/fixing/internal rates; convert; history; pairs; caching | ● *(no live feed)* | `FxRateController` · `FxRatesPage` |
| D10 | Cash forecasting | ~13-week forecast per corporate; pluggable engines (pattern/aging/manual); variance; scenarios | ● | `ForecastController` · `ForecastingPage` |
| D11 | Cash-concentration simulator | Sandbox: design structures, score, fork A/B/C, snapshot diff, activate into real sweep rules | ● | `Simulator*Controller` · `SimulatorPage` |

### Domain 5 — Intercompany & In-House Bank

| ID | Feature | What it does | Maturity | Primary surface |
|---|---|---|---|---|
| E1 | POBO (payments-on-behalf-of) | Pay vendors on behalf of a subsidiary within authorization limits; preview cost; execute; recharge | ● *(history stubbed)* | `PoboController` · `EnhancedPoboPicker` |
| E2 | POBO authorizations | Payer↔behalf-entity authorization pairs; single/daily/monthly limits; suspend/reactivate | ● | `/pobo/authorizations*` |
| E3 | Intercompany recharge | Recharge subsidiary via IHB loan / deposit offset / netting; arm's-length validation | ● | `/pobo/recharges*` |
| E4 | COBO (collections-on-behalf-of) | Collect customer payments for a subsidiary; submit→approve→collect; recharge back | ● | `ReceivablesController` `/cobo/*` · `EnhancedCoboPicker` |
| E5 | Intercompany dashboard | Unified IC ledger; bilateral positions; entity pairs; reports | ● | `IntercompanyController` · `IntercompanyDashboardPage` |
| E6 | Bilateral settlement | Settle bilateral IC positions; add-to-netting | ● | `/intercompany/settle-bilateral,/add-to-netting` |
| E7 | Transfer-pricing validation | Validate IC transactions for arm's-length compliance | ● | `/intercompany/.../validate-transfer-pricing` |
| E8 | IHB current accounts | INTERCOMPANY VAs with running balance, credit/debit interest, overdraft; deposit/withdraw/transfer; post interest | ● | `IhbController` `/ihb/current-account/*` · `InHouseBankPage` |
| E9 | IHB loans & deposits | Intercompany loans (cross-currency, lifecycle) and deposits; repay; withdraw | ● | `/ihb/loans,/deposits` |
| E10 | Treasury-center rates | Offered/indicative IHB rates per corporate; interest, stats, settlement | ● | `/ihb/.../treasury-rates,/indicative-rate` |

### Domain 6 — Credit & Interest

| ID | Feature | What it does | Maturity | Primary surface |
|---|---|---|---|---|
| F1 | Credit agreements | Master CBS-synced agreements; activate/suspend/expire; total/utilized/available | ● | `CreditAgreementController` · `CreditAgreementsPage` |
| F2 | Credit facilities | Overdraft/term/revolving drawdowns; draw-down/repay; drawing power; link physical | ● | `CreditFacilityController` · `CreditFacilitiesPage` |
| F3 | Multi-level credit limits | Corporate→group→entity→VA limit tree; utilize/release; availability & breach checks; dashboards | ● | `CreditLimitController` · `CreditLimitsPage` |
| F4 | Interest configuration | Internal/external configs: base + spreads, day-count, accrual/compounding, min-balance, penalty | ● | `InterestConfigurationController` · `InterestConfigurationPage` |
| F5 | Interest config attachment | Resolve/attach effective config per VA; sync rates; propagate; auto-attach | ● | `InterestConfigAttachmentController` |
| F6 | Interest accruals & reports | Accrual calculation and reporting | ● | `InterestAccrualReportsPage` |

### Domain 7 — Specialty Programs

| ID | Feature | What it does | Maturity | Primary surface |
|---|---|---|---|---|
| G1 | Wallets | Prepaid wallets (load/withdraw/transfer/bulk-load); status; KYC gating; history — reuses VA/Program/Transaction | ● | `WalletController` · `WalletPage`, `EnhancedWalletPage` |
| G2 | Wallet programs | Wallet-program config, per-program stats & wallets | ● | `/wallets/programs*` |
| G3 | Escrow | Milestone/condition escrow (trade/RE/M&A/rent); fund/release/dispute | ○ *(entity exists, API mock)* | `EscrowController` · `EscrowPage` |
| G4 | E-Commerce collections | Marketplace merchant collections, settlement runs, per-platform tracking | ○ *(real data via Receivables)* | `EcommerceController` · `EcommerceCollectionsPage`, `EcommerceDashboardPage` |
| G5 | Seller collections | Seller-level collection views | ○ | `SellerCollectionsPage` |
| G6 | Merchant onboarding | Application → review → activate (settlement account, MCC, commission) | ○ *(no persistence)* | `EcommerceController` `/merchants` · `MerchantOnboardingPage` |

### Domain 8 — Tax & Charges

| ID | Feature | What it does | Maturity | Primary surface |
|---|---|---|---|---|
| H1 | Tax jurisdictions & config | Jurisdiction-aware VAT + withholding tax with treaty rates | ● | `TaxChargeController` `/jurisdictions,/tax-configs` · `TaxChargeSetupPage` |
| H2 | Tax calculation & application | Calculate tax/net-amount/payable-taxes; apply taxes to a reference | ● | `/calculate-tax,/apply-taxes/{id}` |
| H3 | Charge / fee engine | Charge configs; calculate charges/payment-total; apply; waive / partial-waive | ● | `/charge-configs,/charges/{id}/waive` |
| H4 | Program charge overrides | Per-program (wallet) fee overrides; migrate fees; calculate wallet fees | ● | `ProgramChargeOverrideController` `/program-charges` |

### Domain 9 — Integration & Sync

| ID | Feature | What it does | Maturity | Primary surface |
|---|---|---|---|---|
| I1 | Connector registry | Connectors + connections (test, reconnect); data-flow definitions + field mappings | ● | `IntegrationController` · `IntegrationsPage` |
| I2 | Open Banking (PSD2) | ASPSP list; authorize; callback; refresh/revoke consent | ● | `/integrations/open-banking/*` · `OpenBankingSetupModal` |
| I3 | BaNCS client + fallback | Core-banking client with real-REST-with-mock-fallback (store-locally-sync-later) | ◐ | `BancsClient/Impl` |
| I4 | Data-flow sync | Trigger connection/flow sync; sync logs | ● | `/integrations/sync,/connections/{id}/sync` |
| I5 | Sync administration | Jobs / queue / stats / logs dashboard | ○ *(synthetic data)* | `SyncAdminController` · `SyncAdminPage` |

### Domain 10 — Administration & Insights

| ID | Feature | What it does | Maturity | Primary surface |
|---|---|---|---|---|
| J1 | Market profiles | Boot-time market localization (currency/country/locale/tz/weekend/home-bank/IBAN country) | ● | `MarketProfileController` `/config/market-profile` |
| J2 | KYCC / compliance queue | Review queue; document verification; beneficial-owner/PEP; screening; approve/reject/request-info; expiry | ○ *(mock, no persistence)* | `KycController` · `KyccPage` |
| J3 | Dashboards & cockpit | Home dashboard, treasury cockpit, Treasury-2030 dashboard | ● | `DashboardController` · `CockpitPage`, `DashboardClassicPage`, `Treasury2030DashboardPage` |
| J4 | Treasury Copilot | In-app assistant: NL position queries (read tools) + guarded actions (propose→confirm→audit); SSE streaming | ◐ *(reasoning is stub router; LLM-ready)* | `CopilotController` `/api/ai/copilot` · Copilot drawer |
| J5 | Settings | Application/user settings | ● | `SettingsPage` |

**Feature count:** 62 catalogued features across 10 domains — **≈40 production-grade (●), ≈11 partial (◐), ≈11 mock (○).**

---

## Part B — Feature-Capability Map

### B.1 Capability → Feature matrix

Business capabilities are the *outcomes* the platform delivers; features are the *mechanisms*. One capability is usually served by several features across domains.

| Business capability | Outcome for the client | Serving features |
|---|---|---|
| **Account structuring** | Model the entire org as a virtual hierarchy without physical accounts | A1, A4, A9, A10, A11, A8 |
| **Virtual IBAN & auto-reconciliation** | Issue per-invoice/order VIBANs; inbound auto-matches | A6, A7, C5, C6 |
| **Cash visibility (single pane)** | One aggregated, multi-currency, multi-bank position | A12, A13, D1, D7, D8, J3 |
| **Liquidity optimization** | Concentrate & net cash, minimize external movement & idle balances | D2, D3, D4, D5, E8, E9 |
| **Funding & credit control** | Cascade limits down, gate every debit | D6, F1, F2, F3 |
| **Payments processing** | Move money internally & externally to standards | C1, C2, C4, C8, C9, C14 |
| **Collections & AR automation** | Collect via VIBAN/POS/e-commerce, match, escalate | C5, C6, C13, G4, G5 |
| **Payables & disbursement** | Approve and execute AP, in batches | C7, C14 |
| **Pay/collect on behalf of** | Central treasury acts for subsidiaries | E1, E2, E3, E4, E5, E6 |
| **In-house banking** | Run an internal bank: IC accounts, loans, deposits, rates | E8, E9, E10, D4 |
| **Intercompany settlement & compliance** | Net IC exposure; arm's-length transfer pricing | E5, E6, E7, D5 |
| **Interest & pricing** | Configure, resolve, accrue interest per VA | F4, F5, F6, D2 |
| **Tax & fee management** | Jurisdiction-aware tax + charge engine with settlement | H1, H2, H3, H4, C14, C12 |
| **Reporting & statements** | ISO 20022 statements, exports, history | C10, C11, C3 |
| **Reconciliation & exception mgmt** | Handle unmatched/unallocated funds | C6, C13 |
| **Reorganization (M&A)** | Restructure the tree on corporate events | A14, A15 |
| **Forecasting & scenario design** | 13-week forecast; sandbox structures before committing | D10, D11 |
| **Specialty banking programs** | Wallets, escrow, marketplace collections | G1, G2, G3, G4, G6 |
| **Counterparty & entity management** | Corporates, legal entities, parties, beneficiaries | B1, B2, B3, B4, B5 |
| **Compliance (KYC/KYCC)** | Screen the client's own customers | J2, B3, B5 |
| **Integration & connectivity** | BaNCS, Open Banking, connectors, sync | I1, I2, I3, I4, I5 |
| **Assisted operations (AI)** | Natural-language treasury queries + guarded actions | J4 |
| **Platform administration** | Market localization, programs, settings | J1, A9, J5 |

### B.2 Capability → Persona view

| Capability | Treasurer | Cash Mgr / Ops | Subsidiary Finance | Bank Admin | Compliance | Integration Eng |
|---|:--:|:--:|:--:|:--:|:--:|:--:|
| Account structuring | ● | ● | ◐ | ● | | |
| VIBAN & auto-recon | | ● | ◐ | ● | | ◐ |
| Cash visibility | ● | ● | ◐ | | | |
| Liquidity optimization | ● | ● | | | | |
| Funding & credit control | ● | ● | ◐ | ● | | |
| Payments processing | ◐ | ● | ● | | | ● |
| Collections / AR | | ● | ● | | | ◐ |
| Payables / disbursement | | ● | ● | | | |
| Pay/collect on behalf of | ● | ● | ● | | | |
| In-house banking | ● | ● | ◐ | ● | | |
| IC settlement & compliance | ● | ● | ● | | ◐ | |
| Interest & pricing | ● | ◐ | | ● | | |
| Tax & fee management | | ◐ | | ● | ◐ | |
| Reporting & statements | ● | ● | ● | | ◐ | |
| Reconciliation / exceptions | | ● | ◐ | | | |
| Reorganization (M&A) | ● | ◐ | | ● | | |
| Forecasting & simulation | ● | ● | | | | |
| Specialty programs | | ◐ | ◐ | ● | | |
| Counterparty & entity mgmt | ◐ | ● | ● | ● | ◐ | |
| Compliance (KYC/KYCC) | | | | ◐ | ● | |
| Integration & connectivity | | ◐ | | ● | | ● |
| Assisted operations (AI) | ● | ● | ◐ | | | |
| Platform administration | | | | ● | | ◐ |

● primary user · ◐ secondary user

### B.3 Capability → Enabling technical components

| Capability | Key entities / tables | Key services | Cross-cutting components |
|---|---|---|---|
| Account structuring | `VirtualAccount`, `HierarchyNode`, `Program`, `AccountAttachment` | `VirtualAccountService`, `HierarchyService` | Composite hierarchy, materialized path |
| VIBAN & auto-recon | `Viban`, `VibanPool`, `Receivable` | `VibanRoutingService` | MOD-97 IBAN generator |
| Cash visibility | `VirtualAccount` (mirrors) | `BalanceAggregationServiceEnhanced`, `MultiBankLiquidityViewService`, `CurrencyMirrorService` | `BalanceRefreshService` adapter (CoreBanking/Stub), freshness policy |
| Liquidity optimization | `SweepRule`, `NotionalPool`, `NettingCycle` | `SweepService`, `NotionalPoolService`, `NettingService` | Allocation strategy pattern, `CutoffCalendarService`, `RejectionCodeRegistry` |
| Funding & credit control | `CreditLimit`, `CreditFacility`, `CreditAgreement` | `FundsAvailabilityService`, `CreditLimitService` | Multi-level up-tree check, LIFO limit release |
| Payments & the ledger | `Transaction` (`va_movements`), `transaction_groups` | `TransactionService`, ISO 20022 services | Single-entry ledger, correlation IDs, `Camt053XmlGenerator` |
| Pay/collect on behalf of | `PoboAuthorization`, `IntercompanyRecharge`, `IntercompanyTransaction` | `PoboExecutionService`, `CoboReceivableService`, `IntercompanyService` | Transfer-pricing validation |
| In-house banking | `IhbLoan`, `IhbDeposit`, INTERCOMPANY VAs | `IhbUnifiedService`, `IhbFxService` | IHB-as-overlay on `LegalEntity` |
| Tax & fees | `TaxConfiguration`, `ChargeConfiguration`, `CalculatedTax/Charge` | `TaxService`, `ChargeService`, `FeePostingService` | Settlement-VA resolver, exception fallback |
| Forecasting & simulation | `ForecastRun/Line`, `SimulatorScenario` | `DefaultForecastOrchestrator` (pluggable `ForecastEngine`), `SimulatorActivationService` | Auto-discovered engine beans, feature-gated activation |
| Assisted operations (AI) | `CopilotConversation/Message`, `ActionProposal` | `CopilotService`, `IntentRouter`, `ToolRegistry`, `ActionExecutorService` | SSE streaming, Anthropic-schema tools, propose→confirm→audit |
| Integration & sync | `IntegrationConnector/Connection/DataFlow`, sync queue | `IntegrationService`, `BancsClientImpl` | Resilience4j circuit breaker, OAuth2 consent |

---

---

## Part C — Live Screen Index

Each screen below was captured from the **running application against seeded data** and lives in the companion walkthrough [`Aperture-Screens.pdf`](Aperture-Screens.pdf). Links open the exact page. Corporate-scoped pages (receivables, payables, POBO, COBO, forecast) were captured with **TestMNC** selected.

| # | Screen | Page id | Walkthrough |
|---|---|---|---|
| 1 | Cash Position (Cockpit) | `dashboard` | [p.2](Aperture-Screens.pdf#page=2) |
| 2 | Multi-Bank Liquidity | `multi-bank-liquidity` | [p.3](Aperture-Screens.pdf#page=3) |
| 3 | Statements | `statements` | [p.4](Aperture-Screens.pdf#page=4) |
| 4 | Virtual Accounts | `accounts` | [p.5](Aperture-Screens.pdf#page=5) |
| 5 | Bank Accounts | `physical-accounts` | [p.6](Aperture-Screens.pdf#page=6) |
| 6 | VIBAN Management | `viban` | [p.7](Aperture-Screens.pdf#page=7) |
| 7 | Account Linking | `account-attachments` | [p.8](Aperture-Screens.pdf#page=8) |
| 8 | Programs | `programs` | [p.9](Aperture-Screens.pdf#page=9) |
| 9 | Balance Hierarchy | `hierarchy` | [p.10](Aperture-Screens.pdf#page=10) |
| 10 | Entity Balance Tree | `entity-balance-tree` | [p.11](Aperture-Screens.pdf#page=11) |
| 11 | Shadow Accounts | `shadow-accounts` | [p.12](Aperture-Screens.pdf#page=12) |
| 12 | Currency Mirrors | `currency-mirrors` | [p.13](Aperture-Screens.pdf#page=13) |
| 13 | Currency Mirrors | `currency-mirrors` | [p.14](Aperture-Screens.pdf#page=14) |
| 14 | Reorganization | `hierarchy-operations` | [p.15](Aperture-Screens.pdf#page=15) |
| 15 | Balance Aggregation | `balance-aggregation` | [p.16](Aperture-Screens.pdf#page=16) |
| 16 | Balance Aggregation | `balance-aggregation` | [p.17](Aperture-Screens.pdf#page=17) |
| 17 | Legal Entities | `legal-entities` | [p.18](Aperture-Screens.pdf#page=18) |
| 18 | Parties & Counterparties | `parties` | [p.19](Aperture-Screens.pdf#page=19) |
| 19 | All Transactions | `transactions` | [p.20](Aperture-Screens.pdf#page=20) |
| 20 | Transfers | `transfers` | [p.21](Aperture-Screens.pdf#page=21) |
| 21 | Receivables (AR) | `receivables` | [p.22](Aperture-Screens.pdf#page=22) |
| 22 | Payables (AP) | `payables` | [p.23](Aperture-Screens.pdf#page=23) |
| 23 | POBO Payments | `intercompany-pobo` | [p.24](Aperture-Screens.pdf#page=24) |
| 24 | COBO Collections | `intercompany-cobo` | [p.25](Aperture-Screens.pdf#page=25) |
| 25 | ISO 20022 Payments | `iso20022` | [p.26](Aperture-Screens.pdf#page=26) |
| 26 | Settlement VAs | `settlement-vas` | [p.27](Aperture-Screens.pdf#page=27) |
| 27 | Exceptions | `exceptions` | [p.28](Aperture-Screens.pdf#page=28) |
| 28 | Cash Forecast | `forecasting` | [p.29](Aperture-Screens.pdf#page=29) |
| 29 | Cash Concentration | `sweeping` | [p.30](Aperture-Screens.pdf#page=30) |
| 30 | Simulator | `simulator` | [p.31](Aperture-Screens.pdf#page=31) |
| 31 | Notional Pooling | `notional-pooling` | [p.32](Aperture-Screens.pdf#page=32) |
| 32 | In-House Bank | `ihb` | [p.33](Aperture-Screens.pdf#page=33) |
| 33 | Netting Cycles | `netting-enhanced` | [p.34](Aperture-Screens.pdf#page=34) |
| 34 | Intercompany Dashboard | `intercompany` | [p.35](Aperture-Screens.pdf#page=35) |
| 35 | FX Rates | `fx-rates` | [p.36](Aperture-Screens.pdf#page=36) |
| 36 | Funds Availability | `funds-check` | [p.37](Aperture-Screens.pdf#page=37) |
| 37 | Credit Limits | `credit-limits` | [p.38](Aperture-Screens.pdf#page=38) |
| 38 | Credit Agreements | `credit-agreements` | [p.39](Aperture-Screens.pdf#page=39) |
| 39 | Credit Facilities | `credit-facilities` | [p.40](Aperture-Screens.pdf#page=40) |
| 40 | Interest Configuration | `interest-config` | [p.41](Aperture-Screens.pdf#page=41) |
| 41 | Interest Accruals | `interest-accruals` | [p.42](Aperture-Screens.pdf#page=42) |
| 42 | Escrow | `escrow` | [p.43](Aperture-Screens.pdf#page=43) |
| 43 | Wallets | `wallet` | [p.44](Aperture-Screens.pdf#page=44) |
| 44 | E-Commerce Collections | `ecommerce-collections` | [p.45](Aperture-Screens.pdf#page=45) |
| 45 | E-Commerce Dashboard | `ecommerce-dashboard` | [p.46](Aperture-Screens.pdf#page=46) |
| 46 | Seller Collections | `seller-collections` | [p.47](Aperture-Screens.pdf#page=47) |
| 47 | Merchant Onboarding | `merchant-onboarding` | [p.48](Aperture-Screens.pdf#page=48) |
| 48 | KYCC Compliance | `kycc` | [p.49](Aperture-Screens.pdf#page=49) |
| 49 | Tax & Charges | `tax-charges` | [p.50](Aperture-Screens.pdf#page=50) |
| 50 | Integrations | `integrations` | [p.51](Aperture-Screens.pdf#page=51) |
| 51 | Sync Admin | `sync-admin` | [p.52](Aperture-Screens.pdf#page=52) |
| 52 | Operation History | `operation-history` | [p.53](Aperture-Screens.pdf#page=53) |
| 53 | Treasury Hierarchy | `treasury-hierarchy` | [p.54](Aperture-Screens.pdf#page=54) |

*53 data-bearing screens. Genuinely empty pages (0 beneficiaries) and pure config (settings) were skipped.*

*Companion documents:* [`DESIGN_DOCUMENT.md`](DESIGN_DOCUMENT.md) · [`EPICS_AND_USER_STORIES.md`](EPICS_AND_USER_STORIES.md)
