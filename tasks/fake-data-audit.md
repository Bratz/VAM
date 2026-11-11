# Fake data in the backend — audit (2026-11-10)

Ask: "there should be no fake data anywhere". Three read-only sweeps of `backend/src/main/java`
(service/; controller, ai, config, mcp, client; fx, forecast, collections, iso20022, entity, dto).
Findings are the sweeps' reports, to be verified line by line when fixed. Paths are under
`com/bank/vam/`.

Already fixed (uncommitted): `ReceivablesService.getAllInvoices` returned 4 demo invoices
(INV-2024-0156..0159) for any corporate with none — why Brato and Mercator showed the same four.

## A. Made-up records or results presented as real — remove / make real (no decision needed)

Receivables (`service/receivables/ReceivablesService`)
- e-commerce orders, unmatched payments, POS collections are always demo lists (`buildDemo*`);
  `/stats` totals add them up; match/return/escalate/release-escrow relabel a demo record and change nothing.
- `createInvoice` stores under demo corporate `550e8400-…` when corporateId is missing.
- Dead demo helpers: `matchPaymentEnhanced`, `returnPaymentEnhanced`, `releaseEscrowEnhanced`,
  `createPublishedPaymentViban`, `buildDemoCollectionResponse`, `processCoboCollection` "VA-DEMO-001".

Parties (`service/party/PartyService`, `controller/party/PartyController`)
- Empty results fall back to demo parties / bank accounts / documents; unknown id returns a demo party.
- Real parties get a made-up activity log ("Sarah M.", "John D.") and compliance detail (risk 25, docs VERIFIED).
- Stats invent counts when empty. Demo corporate substituted in ~13 places, and parties are stored under it.
- `sanctionsStatus=CLEAR` stored with no screening.

Demo corporate substitution elsewhere: `PayablesController` (stores payables under it; POBO eligible /
history always the demo corporate; netting by-cycle ignores the cycle), `KycController` (`1111…`),
`BalanceStructureController`/`BalanceStructureService` (`1111…`, invents "CORP-001").

Endpoints that report success and do nothing / return invented lists
- `SyncAdminController`: every endpoint (jobs, queue, stats 98.5%, logs) is fabricated.
- `StatementController`: history made up; generate/download say ready, no file.
- `BalanceStructureController`: create/move/delete node, export, refresh — success, nothing happens.
- `CorporateHierarchyOperationsController` approve/reject — success, no change.
- `PhysicalAccountController` sync / sync-all and `treasury/refresh/StubAdapter` mark balances SYNCED
  / fresh without contacting a bank.
- `IntegrationService`: connection test is `Math.random() > 0.2`; connections stored CONNECTED;
  demo ASPSP list; fake open-banking consent URL.
- `PoboExecutionService.executePaymentViaTreasury` returns success with a made-up TXN reference.
- `IntercompanyService.processCoboCollection` stores a random IHB deposit id; `executeSettlement`
  marks SETTLED, moves nothing.
- `PayablesController` netting remove (always 0), `PoboController` history (always empty).

Made-up figures in real screens
- `WalletService` program stats / detail: volumes 50k/350k/1.5M and counts hardcoded.
- `PayablesService.getPoboPreview`: IHB rate 5.5, fee 0.5%, payer "Corporate Treasury" hardcoded;
  intercompany position receivables always 0.
- `ForecastSummaryAssembler`: opening balance 0 (TODO), currencies added together.
- `Iso20022StatementService`: XML download is a stub comment; CLBD/OPBD use the current balance.
- `BalanceStructureService` covered-VA / pending-exception counts hardcoded 0.
- `Iso20022MessageMapper`: every FAILED transaction reported as AM04; `MandateService` refusal with no
  code recorded as MS03.
- `VibanService.generatePaymentLink` returns a `pay.bank.com` URL.
- `TransactionService.createIntercompanyTransactionRecord` stores a random originalPayableId.
- `NettingService.applyFxConversion` 1:1 cross-currency.
- `PartyService`/`ExceptionTransactionService`/copilot: users "Treasury User", "demo-user".
- `HierarchyService` stores hardcoded FX rates on currency mirrors (unknown currency = 1.0).
- BIC fallbacks `BANKAEXX` in pain.001 / camt.053; currency fallbacks to AED in builders and entities.

## B. Simulators for systems the platform is not connected to — decision needed

- FX market: `fx/MockRateProvider` random-walks every rate (board, quotes, deals, MTM, payment and
  collection conversions); `FxRateService.DEFAULT_RATES` and `FxMarketProperties.fallbackMid`/money-market
  rates as fallbacks.
- FX dealer: `fx/deal/SimulatedDealer` prices and counter-offers deals (labelled SIMULATED on the deal).
- Clearing: `CollectionSettlementService.endOfDay` treats a direct debit as collected on its date
  ("Settle due collections up to"); `FxSettlementService.run(asOf)` settles deals early.
- Core banking: `client/BancsClientImpl` mock mode on by default (random balances); currently unused.
- Balance refresh for SWIFT/Open Banking/H2H shadows (`StubAdapter`) — listed in A as it reports success.
- Outbox emails marked SENT (simulated, labelled).

## C. Business settings invented at startup — decision needed

- `ChargeDataInitializer`: 19 fee tariffs (e.g. SWIFT 35, POBO 0.50%) inserted if missing; charged on real transactions.
- `FxLineSeeder` / `FxLimitService.ensureLine`: a 20m FX credit line for every corporate.
- `FxSettlementAccountSeeder`: home-bank accounts opened with 5,000,000 each, marked CORE_BANKING / SYNCED.
- `ProgramBankAccountBackfillRunner`: physical accounts with made-up numbers, balance = ledger total, marked SYNCED.
- `HedgePolicyService` default bands written as the corporate's policy.
- `IhbUnifiedService` interest: 2.50 / 5.00 / 7.00 configuration persisted; accrual at 2.50/5.00 when a VA has no rate;
  indicative base rate 5.00 "EIBOR".
- `LegalEntity` default spreads 0.50 / 0.75 / 0.25 and ownership 100%; `TaxService` VAT 5% fallback;
  `IntercompanyService` 3.50 fallback; `PoboPreviewService` 5.50 fallback.
- `MultiBankReferenceDataInitializer` holiday calendars partial (no Eid, no Easter).
- `MarketProfile` unused `sample*` fields.

Not fake (left alone): `Math.random()` suffixes on record references (collision risk only), static
reference data (currencies, ISO codes, cut-offs, rejection codes).

## Stored fake rows — inventory only (local DB, 2026-11-10; OCI to be counted the same way)

| What | Table | Rows (local) |
|---|---|---|
| Payables stored under demo corporate `550e8400-…` (no such corporate exists) | payables | 2 |
| Parties marked sanctions CLEAR with no screening | parties | 11 |
| FX settlement accounts opened with 5,000,000 | physical_accounts (current_balance = 5,000,000) | 32 |
| Program accounts with invented numbers `PRG…` | physical_accounts | 8 |
| Accounts claiming SYNCED that never synced (CORE_BANKING 53 rows, 14 with no last_sync_at; SWIFT / Open Banking 14 rows, none ever synced) | physical_accounts | 67 SYNCED in total |
| Synthetic 90-day balance history (migration V18 says "synthetic demo history") | shadow_balance_snapshot | 2,526 |
| FX credit lines made up at startup (20m each) | credit_facilities FX_TREASURY_LINE | 9 |
| Default hedge policy bands | fx_policy_band | 45 (9 corporates) |
| Seeded fee tariffs | charge_configurations | 43 |
| Interest configurations (defaults 2.5/5/7) | interest_configurations | 25 |

No cleanup done; per decision, these are listed for a later call.

Found while fixing (open): `ReconciliationService.returnPayment` marks a payment RETURNED with a made-up
`RTN-<time>` reference and moves no money; `ReceivablesService.processCamt054` / `processCollection` have no callers.
- `frontend/src/pages/IntegrationsPage.tsx` is entirely client-side mock (Math.random test, invented connections/flows); never calls the API.
- `PayablesService.searchPayables` returns every corporate's payables when no corporate is given, and on any error.
- `PhysicalAccount` entity defaults `syncStatus` to SYNCED.
- `IntercompanyService.executePobo` records an ad-hoc POBO as PROCESSED, moves no money.
- `StatementController /types` lists MT940 / CAMT.053 / XLSX formats nothing produces.
- Frontend `copilotApi.ts` sends "demo-user"; `EnhancedPayablesPage` has its own invented POBO preview fallback
  (0.5% fee, 5% IHB, TREASURY, AED); `Treasury2030Dashboard` AED fallback; `ForecastingPage` shows a null opening balance as 0.
- `CollectionReturnService` defaults a return reason to MS03; CANCELLED reported as CUST; "Creditor" / "Corporate" / "CORP" name fallbacks.
- Payables stats and netting-eligible totals (labelled AED) and aggregated statements sum across currencies.
- `BalanceAggregationServiceEnhanced` treats a null FX rate as 1.

## Status after the fix passes (2026-11-10)
Groups A, B and C done (see tasks/todo.md). Still open, found in the last pass:
- `PayablesService`: pain.001 creditor fallbacks "Unknown Bank" / "Vendor" / "UNKNOWN"; `requestPobo` and
  `executeBatchPayment` totals labelled AED across currencies; `getNettingEligiblePayables` without an entity spans corporates.
- `CurrencyMirrorPage.tsx` `DEMO_BREAKDOWNS`; `context/useMarket.ts` falls back to a UAE profile with a real bank's BIC.
- `TransactionController` pain.001 "Unknown" names; `Iso20022MessageMapper` REVERSED -> FOCR with nothing stored.
- `ReceivablesService` invoice email "Your supplier" fallback; `IntegrationService.createConnection` createdBy "system".
- `IntegrationsPage` inert buttons (API Docs, Settings, Credentials, Re-authenticate, Add Data Flow).
- `StatementsPage` reads `aggregatedBalance.aggregatedBalance`, which the backend never sends.
- Existing seeded rows show no "Default" badge (created before the marker); needs a data backfill — decision pending with the stored-row cleanup.
