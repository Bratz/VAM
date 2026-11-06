# Corporate FX — scope & plan

Status: scoped via interview, not started. Nothing built, nothing committed.

## Decisions (from the interview)

| Topic | Decision |
|---|---|
| Counterparty | Our bank is the dealer (single-dealer, Citi Pulse / HSBC evolve model). Multi-bank RFQ is out of V1. |
| Segment | Mid-market corporates: guided, low-friction hedging. |
| Delivery | Working demo on mock rates, behind a `RateProvider` interface so a live feed swaps in later. |
| Jobs | Spot & forward dealing; exposure & hedge management; FX on payments. |
| Instruments | Spot, outright forward, window (time-option) forward. FX swap and NDF deferred. |
| Pricing | Negotiated per deal: corporate RFQ, a **simulated dealer** (auto-pricer) quotes, validity timer, accept or lapse. Real dealer workbench deferred. |
| Settlement | Two-leg posting on the corporate's real VAs (sold + bought currency) on value date. Currency mirrors stay cash-free. |
| Controls | Credit/settlement limits (with forward PFE add-on) and hedge policy bands. Maker-checker deferred. Audit trail is free via `AuditLogService`. |
| Exposure inputs | Payables & receivables, cash-flow forecast, FX balances, manual/CSV upload. |
| AI/ML | Copilot FX tools, FX-aware exposure forecasting, hedge recommendation. Rate alerts/timing/anomaly deferred. |
| Demo | One scripted story: exposure alert -> Copilot suggests hedge -> RFQ -> simulated dealer quote -> accept -> settle, against a live-ticking mock rate. |

## What exists today (codebase scan)

- `FxRate` entity + `FxRateService` + `/api/v1/treasury/fx-rates`: bid/ask/mid/spread, corporate-specific rows, cache, inverse and multi-hop. **No tenor/forward fields.**
- Rates are seed + manual only (8 static pairs). No feed adapter. `RateSource` lists REUTERS/BLOOMBERG with no implementation.
- Consumers: netting, currency mirrors, balance aggregation, POBO (flat 0.25% markup), IHB intercompany.
- **Not built:** quotes, deals, forwards, hedges, exposure computation, revaluation/P&L, FX-aware forecast, any FX in the payment flow.
- Stubs waiting for this: `produceFxExposures()` in `cockpitApi.ts` returns `[]` (needs `fx_policy_band` table); `fx_exposure` attention category already typed; "FX deal / Spot - Forward" tile on `Treasury2030DashboardPage` only links to rates.
- Copilot is stub-only (regex `IntentRouter`, no LLM) but has a clean `CopilotTool` registry and propose-then-confirm writes (`ActionProposal` 10-min TTL, idempotent, audited). No FX tool exists.
- Forecast engine (`forecast/`) assumes single currency; `FX_CONVERSION` driver is listed unbuilt.
- Latest Flyway migration is **V40**; new ones go in `backend/src/main/resources/db/migration/` (and mirrored in `database/migrations/`). Entity-mapped tables come from `ddl-auto`, so Flyway only for seed data (policy bands, rate sheet, limits).

## Landscape takeaways

- Citi: CitiFX Pulse (exposure module across subsidiaries, pre-trade research) and Velocity hedging from TMS/ERP.
- HSBC evolve: self-service across ~460 pairs (spot, forwards, swaps, NDFs, time-option forwards), one view of all FX incl. phone trades.
- BNP Paribas / Deutsche: execution algos and streaming + voice; relevant mainly for large corporates (out of segment).
- AI in the market: exposure consolidation from ERP/bank data, hedge-ratio suggestions, cash-flow/exposure forecasting. Vendor accuracy/savings claims are self-reported and unverified; don't quote them as benchmarks. Governance themes: audit trail, approval, model-drift monitoring.
- J.P. Morgan's corporate FX platform wasn't covered by the search; treat as a gap if the comparison matters.

## Architecture

New backend package `fx/` (domain, service, api) mirroring `forecast/`:

1. **RateProvider** interface. `MockRateProvider` = mean-reverting random walk anchored on the mids already in `fx_rates`, **held in memory and never written back** (see "Phase 1 outcome"). Forward points computed from the interest differential (covered interest parity), not stored.
2. **Exposure** (`FxExposureService`): aggregates by currency x time bucket from payables, receivables, FX balances, forecast, and CSV upload rows. Net, hedged (from deals), unhedged. Feeds `produceFxExposures()` via `/cockpit/v1/attention`.
3. **HedgePolicy** (`fx_policy_band`): per corporate, currency, tenor bucket, min/max hedge ratio. Breach -> `fx_exposure` attention item.
4. **Quote / RFQ**: `FxQuoteRequest` -> `SimulatedDealer` prices (mid + spread by size/tenor/client tier + forward points) with validity seconds -> accept/lapse. Dealer interface so a human workbench replaces the simulator later.
5. **Deal** (`FxDeal`): spot/forward/window, status machine, value date, rate, both legs, window start/end for time-option. Settlement job posts the two legs on VAs on value date; audit every transition.
6. **Limits**: per-corporate `fx_credit_limit`; utilisation = notional x PFE factor by tenor. Reject quote acceptance over limit.
7. **FX on payments**: rate-lock hook in POBO/payables conversion using a live quote or an existing forward deal, replacing the flat 0.25% where a quote exists.
8. **Copilot**: tools `get_fx_exposure`, `get_fx_rate`, `suggest_hedge` (read) and `propose_fx_deal` (write, uses existing proposal/confirm); FX intent in `IntentRouter`/`ResponseComposer`.

Frontend: FX workspace (ticking rate board, exposure ladder, hedge-ratio vs policy, RFQ ticket with countdown, deals blotter), cockpit attention drawer integration, Copilot cards.

## Deal lifecycle

Two objects: **`FxQuoteRequest`** (pre-trade, short-lived) and **`FxDeal`** (the booked trade). Every transition writes an `FxDealEvent` (actor, from/to, reason, payload) which also feeds `AuditLogService` and renders with the existing `EventTimeline` component from the file-ingest work.

### 1. Pre-trade (RFQ)

```
 exposure/hedge suggestion --> REQUESTED --> QUOTED --+--> ACCEPTED --> (book)
                                   |           |      +--> EXPIRED   (validity timer lapsed)
                                   |           |      +--> REJECTED  (client declines, or dealer declines to quote)
                                   +-----------+----> CANCELLED      (client withdraws before a quote is accepted)
```

- **Request carries:** pair, side (buy/sell base), amount and which currency it is in, instrument (spot / forward / window), value date or window start-end, **linked exposure ids** (so the hedge is traceable to what it hedges), purpose, requesting user.
- **Validations on request:** corporate and user entitlement, pair eligibility, minimum/maximum ticket, value-date validity (holiday calendars and per-currency cut-offs from `CurrencyCutoffConfig`, spot = T+2 with pair exceptions, forward tenor cap), **limit pre-check** (headroom for notional x PFE factor), sanctions/KYC flags on the corporate.
- **Quote:** rate = mid + dealer spread (+ forward points for forwards), firm for N seconds, snapshot of the mid and points used so the price is reproducible. Simulated dealer prices instantly; the interface allows a human dealer later.
- **Accept:** re-check limit and validity atomically at acceptance (the quote can be valid but the limit consumed by another deal in the meantime). Idempotent on a client-supplied key so a double-click books once.

### 2. Execution and confirmation

```
 ACCEPTED --> BOOKED --> CONFIRMED --> ACTIVE (forward / window)
                                  \--> PENDING_SETTLEMENT (spot)
```

- **BOOKED:** deal id assigned, two legs created (sold currency leg, bought currency leg, with value date), limit utilisation reserved.
- **CONFIRMED:** trade confirmation generated and delivered to the corporate (PDF and in-app; ISO 20022 `fxtr` trade-confirmation shape is the production target, mock text is enough for the demo). Corporate can dispute within a window -> `DISPUTED` handled by the bank, out of V1 UI.
- Spot goes straight to settlement tracking; forwards and windows become `ACTIVE`.

### 3. In-life (forwards and windows only)

- **Daily mark-to-market:** a scheduled job revalues each active deal against the current forward curve for the remaining tenor, stores an `FxMtm` snapshot (MTM in the corporate's base currency, with the rate used), and refreshes **PFE/limit utilisation**. MTM feeds the exposure view (hedged amount) and the cockpit. Disclosed per the existing no-silent-conversion rule.
- **Drawdown (window forward):** corporate draws a tranche inside the window at the contracted rate; each drawdown is its own settlement leg. Deal status `PARTIALLY_DRAWN`; undrawn balance tracked. At window end any undrawn balance must be delivered or closed out (see maturity).
- **Early delivery:** settle (part of) a fixed forward before its value date at the contracted rate plus the forward-points difference, booked as an automatic close-out plus short-dated deal.
- **Extend / roll:** close the maturing deal at market and book a new deal at a later date; the points difference is cash-settled. Implemented as linked deals (`rolled_from_id`) so history is not rewritten.
- **Early close-out (cancel):** unwind at market (reverse deal at current rate); realised gain/loss settles in cash on the close-out value date. Partial close-out allowed.
- **Amend:** no in-place edit of rate or amount. Value-date changes are done as roll or close-out + rebook. Only descriptive fields (reference, purpose, linked exposure) are editable.
- **Margin / collateral:** deferred. The limit model (PFE) covers credit risk in V1.

### 4. Maturity and settlement

```
 ACTIVE --T-2 notice--> MATURING --value date--> SETTLED
                           |                  \-> SETTLEMENT_FAILED --> (ROLLED | CLOSED_OUT | RESOLVED)
 PENDING_SETTLEMENT (spot) ----value date------> SETTLED
```

- **T-2 pre-settlement check:** confirm the sold-currency account has the funds. Insufficient projected funds raises a cockpit attention item (this is where the forecast helps) and offers roll / fund / close-out.
- **Settlement run on value date:** one transaction posts both legs on the corporate's VAs (debit sold, credit bought) at the contracted rate, with fee lines if any; idempotent per leg; respects currency cut-offs. Status `SETTLED`, limit utilisation released.
- **Failure path:** insufficient funds or a blocked account -> `SETTLEMENT_FAILED`, retried until cut-off, then escalated; resolution options are roll (with points cost), forced close-out, or manual bank resolution. A sweep or overdraft facility could auto-cover; deferred.
- **Window forwards:** each drawdown settles on its own value date; the deal reaches `SETTLED` when drawn amount equals contracted amount, or `CLOSED_OUT` if the remainder is cancelled at window end.

### 5. Post-trade

- Realised gain/loss recorded on `SETTLED`/`CLOSED_OUT` (needed later for hedge accounting; the fields exist from day one even though the accounting does not).
- Statements and deal blotter, with filters by status, pair, maturity bucket; exportable.
- Reporting hooks (trade repository / regulatory reporting) are deferred; the event log is the source when added.

### State summary

| Instrument | Happy path | Terminal states |
|---|---|---|
| Spot | REQUESTED -> QUOTED -> ACCEPTED -> BOOKED -> CONFIRMED -> PENDING_SETTLEMENT -> SETTLED | SETTLED, EXPIRED, REJECTED, CANCELLED |
| Forward | ... -> CONFIRMED -> ACTIVE -> MATURING -> SETTLED | SETTLED, CLOSED_OUT, ROLLED, EXPIRED, REJECTED, CANCELLED |
| Window forward | ... -> ACTIVE -> PARTIALLY_DRAWN -> ... -> SETTLED | SETTLED, CLOSED_OUT (remainder), ROLLED |

### Where AI touches the lifecycle

- **Pre-trade:** hedge recommendation prefilled into the RFQ ticket (instrument, tenor, size, linked exposures).
- **In-life:** roll/extend suggestion as maturity nears, using the updated exposure and forecast ("this payable slipped 3 weeks; roll to the new date").
- **Pre-settlement:** funds-shortfall prediction on the sold-currency account from the forecast engine, so the T-2 check is a forecast, not just a balance read.
- **Quote guard:** sanity check on the simulated/human dealer quote against the mid and the pair's normal spread band before it is shown to the client.

### V1 cut for the demo

In: RFQ -> quote -> accept -> book -> confirm -> active; daily MTM; window drawdown; maturity settlement (happy path and one shortfall path); early close-out; roll as the one amendment. Out: early delivery, margin/collateral, dispute handling UI, regulatory reporting, partial close-out UI.

### Data model additions

`fx_quote_request`, `fx_deal` (instrument, status, pair, side, notional, contracted rate, value date or window, `rolled_from_id`, `corporate_id`, linked exposure refs), `fx_deal_leg` (currency, amount, value date, settlement status, VA ref), `fx_drawdown`, `fx_mtm`, `fx_deal_event`. Entity-mapped via `ddl-auto`; Flyway only for seed data (policy bands, limits, spread sheet).

## AI / ML design (ranked by value for the demo)

1. **Copilot FX tools** - lowest risk, highest visibility. Deterministic tools do the maths; the LLM (or the existing regex router first) only selects tools and explains. Never lets the model compute a rate or a notional.
2. **Hedge recommendation** - V1 is a rules/optimiser baseline: bring each exposure bucket to the policy target ratio, prefer forwards matching due dates, window forward when timing is uncertain, check limit headroom, then an LLM writes the rationale from the structured result. Human accepts; every suggestion and outcome is audited. Later: learn from accepted/rejected suggestions.
3. **FX-aware exposure forecasting** - add currency dimension to the forecast engine; exposure by bucket with P10/P50/P90 bands (quantile model on payables/receivables due-date slippage and historical cash-flow). Hedge against the P50, flag the band. Needs a held-out backtest to show the bands cover; do not claim accuracy numbers without it.
4. Deferred (noted from interview): rate alerts, convert-now-vs-wait timing, off-market quote and deal-pattern anomaly detection. Anomaly on the dealer's quote is cheap to add later as a guard on `SimulatedDealer`.

Guardrails: model output is advisory; execution always goes through propose -> confirm -> limit check -> audit. Mock data is labelled as mock in the UI. No cross-currency sums without disclosing the rate (existing project rule).

## Phased plan (each phase independently verifiable)

| # | Phase | Verify |
|---|---|---|
| 1 | Rate engine: `RateProvider`, mock ticker, forward points, tenor on `FxRate`/new `fx_forward_curve`; rate board UI | Rates tick; forward = spot + points; existing consumers unaffected (run existing treasury tests) |
| 2 | Exposure + policy bands: `FxExposureService`, `fx_policy_band` seed, CSV upload, wire `produceFxExposures` | Seeded payables/receivables produce expected ladder; breach raises attention item |
| 3 | RFQ + simulated dealer + booking + confirmation + limits (lifecycle sections 1-2) | Quote validity/lapse; over-limit rejected at accept; double-accept books once; unit tests on pricing and PFE |
| 4 | In-life + settlement (sections 3-4): daily MTM, drawdown, roll, close-out, value-date settlement on VAs, shortfall path; FX on payments (rate lock) | Value-date job posts both legs, balances reconcile, failed settlement escalates, mirror-holds-no-cash test still passes, state-machine test rejects illegal transitions |
| 5 | Copilot FX tools | Tools return exposure/rate/hedge; `propose_fx_deal` requires confirm and is audited |
| 6 | FX-aware forecast with bands + hedge recommender | Backtest of band coverage; recommendation reproduces policy target on seeded data |
| 7 | Scripted demo path + docs | Full story runs end-to-end in the browser |

Phases 1-4 are the dependable core; 5-6 are the AI layer and can run in parallel after 3.

## Phase 1 outcome (rate engine) - done, uncommitted

Built: `vam.fx.market.*` config (`FxMarketProperties`), `RateProvider` + `MockRateProvider`, `ForwardPricer`, `FxMarketController` (`/api/v1/fx/market/{spots,curve,forward}`), `FxWorkspacePage` (rate board with sparklines, forward curve, exact-value-date pricing), nav entry under Liquidity Management. 11 backend unit tests pass; checked live against the running backend and in the browser (pair switch, exact date, weekend error).

Deviations from the plan above, and why:
- **Ticks are not written to `fx_rates`.** The table is unique per pair/day/type/source and feeds balance aggregation, netting and mirrors; a write per tick would violate the key and move every consolidated balance. The walk starts at the stored mid, so the platform and the workspace agree on where the market is.
- **No scheduler.** The walk advances by elapsed time when read, so it also runs with `VAM_SCHEDULING_ENABLED=false`.
- **No `fx_forward_curve` table, no tenor fields on `FxRate`, no migration.** Forwards are computed on demand from configured interest rates; entity-mapped tables would come from `ddl-auto`, and nothing here needed one.
- **Crosses (EUR/GBP, EUR/AED, ...) are triangulated through USD** so the board is always triangular-consistent; only USD pairs walk independently. Pegged pairs (AED, SAR) have zero volatility.
- **Mid only on the board.** Bid/ask belongs to the negotiated quote in phase 3.

Known ceilings: weekends are the only non-business days (no per-currency holiday calendars, no USD/CAD T+1); the interest rates are illustrative config, not a curve; the board history lives in the browser only. The existing `FxRatesPage` (stored rates) is untouched.

## Phase 2 outcome (exposure and hedge policy) - done, uncommitted

Built (package `com.bank.vam.fx.exposure`): `FxPolicyBand` and `FxExposureEntry` entities, `ExposureCalculator` (pure ladder arithmetic), `ExposureSources` (payables, receivables, bank cash), `HedgePolicyService`, `FxExposureService`, `ExposureCsvParser`, `FxExposureController` (`/api/v1/fx/exposure`: ladder, `breaches`, `entries`, `entries/upload`), and `FxPolicySeeder`. Frontend: an "Exposure & hedging" tab on the FX Workspace (ladder per currency with hedge ratio against the policy band, CSV upload and template) and the cockpit's `fx_exposure` producer now returns real breaches. 16 new unit tests (27 FX tests in all); verified against the running backend and in the browser, and the ladder matches the raw payables, receivables and balances in the database.

All nine corporates were seeded (45 bands). Default policy, layered: 0-30d 75-100%, 31-60d 50-80%, 61-90d 40-70%, 91-180d 25-50%, 181d+ 0-25%. A currency-specific band row overrides the `ANY` default.

Decisions and deviations:
- **Seeding is a startup runner, not a Flyway migration.** `fx_policy_band` is an entity table, so Hibernate creates it after Flyway runs and a migration seeding it would fail on a fresh database. The runner is idempotent, and a corporate created later gets the default on first use.
- **Sign convention.** Exposure is receivables - payables + cash + declared (positive = long). A hedge is the deal's own position (selling forward is negative). Hedge ratio = -hedged / exposure, and "to reach policy" is the signed hedge to add to reach the nearest band edge. Phase 3 deals and the phase 5-6 recommender reuse this as is.
- **Base currency is per corporate**, derived from the oldest ROOT account's currency (market default if none), because the corporates have different home currencies. No new table; a per-corporate setting is the upgrade.
- **Hedges come from declared uploads only** until phase 3 books deals through the bank.
- **Excluded:** intercompany items (they net inside the corporate), items in the corporate's own base currency, drafts, rejected and settled items. Overdue and undated items count as due now.
- **Cash comes from the shadows of physical accounts** (bank-held balances), so mirrors are not counted twice.
- **No cross-currency totals.** Each currency shows native amounts; the stored platform rate is disclosed per currency, and a missing rate leaves the base equivalent out instead of guessing.
- **Materiality** `vam.fx.exposure.materiality-base` (default 25,000 in base currency): smaller exposures are not flagged unless already hedged.

Not done in phase 2 (by design): the cash-flow forecast as an input (it is single-currency until phase 6); natural-hedge netting of cash on hand against future flows (balances are shown but never policed); editing policy bands through the API or UI (SQL for now); a "Hedge" click that opens a prefilled quote request (phase 3). The dashboard's Action queue rows are display-only for every category, so "Hedge" there is a label, not a link.

## Phase 3 outcome (quote, deal, limits) - done, uncommitted

Built (package `com.bank.vam.fx.deal`, config `vam.fx.deal.*`): `FxQuoteRequest`, `FxDeal`, `FxDealEvent` and `FxCreditLimit` entities; `SimulatedDealer`; `FxQuoteService` (request, accept, withdraw); `FxDealService` (booking, the state machine, confirmation text); `FxLimitService`; `FxEvents` (timeline plus audit log in one place); `FxLimitSeeder`; and `FxDealController` (`/api/v1/fx`: `quotes`, `quotes/{id}/accept|cancel`, `deals`, `deals/{id}/events|confirmation`, `limits`). Frontend: a "Deal" tab on the FX Workspace (request ticket, live price card with a server-clock countdown, accept/withdraw, credit-limit strip, deals blotter with a drawer for the confirmation and history) and a **Quote** button beside each suggested hedge on the Exposure tab that opens a prefilled ticket. 22 new unit tests (49 FX tests in all).

Verified against the running backend and in the browser, including the cases mocks cannot prove: eight parallel accepts of one quote booked one deal (all eight got the same deal back); two forwards that each fit the limit alone but not together, so the second accept was refused at acceptance with the quote left QUOTED; a request far over the limit declined with the reason; withdraw then accept refused; a 32-second wait expired the quote; and a hedge booked from a breach moved GBP 0-30 days from 0% to exactly 75% (within policy). Booked deals now count as hedges in the exposure ladder.

All nine corporates were seeded a default limit of 20,000,000 in their home currency (illustrative; edit `fx_credit_limit` in SQL). Test data I created was removed afterwards; the audit-log rows were left, since that log is hash-chained.

Decisions and deviations:
- **Pricing.** Client rate = forward mid (spot + points) moved against the client by a spread: a per-pair base (2-12 bps), plus 1 bp from 1M base-currency units and 3 bps from 5M, plus 0.5 bp per month to maturity. A window is priced at whichever end date is worse for the client. The quote keeps mid, points, spread and all-in rate, so the margin is explained to the client.
- **Quote validity** 30 seconds, counted down from the server clock. A lapsed quote turns EXPIRED the first time anything reads it (no sweeper).
- **Accepting is the control point.** It locks the quote row and the corporate's limit row, rechecks validity and the limit, then books. A repeat accept returns the same deal. The limit is also checked at request time, where an over-limit request is declined and recorded rather than errored.
- **Credit limit.** A deal is charged notional (converted at the stored platform rate) times a factor by days to maturity: 1% to 7 days, 3% to 31, 6% to 92, 10% to 183, 15% beyond. These are illustrative, in `vam.fx.deal.pfe-bands`, not a risk policy.
- **States.** BOOKED, CONFIRMED, then ACTIVE (forward, window) or PENDING_SETTLEMENT (spot); every other move is refused. Each transition writes a timeline event and a hash-chained audit-log row.
- **Seeded by a startup runner**, not Flyway, for the same reason as the hedge policy (Hibernate creates the tables after Flyway runs). A corporate created later gets a limit on first use.
- **Bug found by running it end to end:** buckets count days from today but tenors count from the spot date, so a "1M" hedge suggested for the 0-30 day bucket was dated 34 days out and landed in 31-60. Suggestions now use 1W, 1M, 2M, 3M and 6M for the five buckets (about 11, 34, 67, 96 and 186 days), which fall inside them.

Not done, by design: the amount is always in the pair's base currency; spot and forwards only for the pairs the market lists (or their inverses); the dealer is a class, not an interface, until a human desk screen exists; there is no maker-checker; the dashboard Action queue still does not link to anything; quote guard (off-market check) and settlement, mark-to-market, drawdown, roll and close-out are phase 4 onward. Actor names show as "anonymousUser" in dev because the security config permits everyone.

## Phase 4 outcome (confirmation, in-life, settlement) - done, uncommitted

**Correction that shaped this phase.** Phase 3 set every deal CONFIRMED the moment it was booked. In practice the bank's back office issues a confirmation (message or PDF) and the *corporate* checks it and affirms or disputes it. Confirmation is now its own field, `fx_deal.confirmation_status` (ISSUED, AFFIRMED, DISPUTED), separate from the deal's life, and `CONFIRMED` is gone from `DealStatus`. See the lesson in `tasks/lessons.md`.

Built (package `com.bank.vam.fx.deal`): `FxConfirmationService` (affirm, dispute, bank-side resolve), `ConfirmationPdf` (a dependency-free one-page PDF writer), `FxMtmService` and `FxMtm`, `FxSettlementService` and `FxSettlement`, `FxLifecycleService` (close-out, roll), `FxDealAttentionService`, `FxEodScheduler`, `HeldCash`, `DealViewAssembler`, and `FxDealOpsController` (`/api/v1/fx/deals/...`: `confirm`, `dispute`, `resolve-dispute`, `confirmation.pdf`, `drawdowns`, `close-out`, `roll`, `settlements`, `mtm`, `settlement/run`, `mtm/run`, `attention`). Frontend: a deal drawer (read the bank's confirmation, download the PDF, confirm or dispute, then draw down, close out, roll, with the settlement records and history), Confirmation and Worth-today columns on the blotter, a value-date settlement control, and cockpit items for unconfirmed, disputed, failed and unfunded deals. Migration **V41** widens `fx_deal_status_check`. 38 more unit tests (87 FX tests in all).

**Confirmation flow.** Booking issues the confirmation (event "Confirmation sent"); the deal is live meanwhile. The corporate reads it in the drawer, can download the PDF, and affirms or disputes it (with a reason). A dispute goes to the bank, which re-issues a corrected confirmation or cancels the deal (API only, no screen yet). An unanswered confirmation is flagged overdue after 24 hours (`vam.fx.deal.confirmation-due-hours`) and raises a cockpit item. **A deal that is not affirmed cannot settle or be drawn down**, and a disputed one cannot be closed out.

**Lifecycle, as built.** Statuses: BOOKED, ACTIVE or PENDING_SETTLEMENT, PARTIALLY_DRAWN, SETTLEMENT_FAILED, SETTLED, CLOSED_OUT, ROLLED, CANCELLED. Window drawdowns settle on their own dates; the remainder settles at the window's last date. Close-out and roll are priced by the desk at the moment they run (not held firm): the drawer previews today's cost and running it prices again. A roll closes the old deal and books the replacement in one transaction (the replacement goes through the normal limit check and is itself awaiting confirmation). Marks are at the mid for the remaining term, undiscounted; each day's mark is stored once, and the daily run also refreshes each deal's limit charge, which shrinks as maturity nears. The end-of-day job (18:00, `vam.fx.deal.eod-cron`, gated by `vam.scheduling.enabled`) marks and settles.

**Settlement is a record only (option 2).** `fx_settlement` rows say what settled, but no account balance is moved. The funds check reads the cash the corporate holds in the currency it sells (shadows of physical accounts) and shares it across deals within one run; a shortfall moves the deal to SETTLEMENT_FAILED once (no pile of failure rows on reruns) and raises a cockpit item, and the next run retries. A settled record does not reduce the cash it was checked against, so figures say what would settle, not what has been paid. `POST .../settlement/run?asOf=` moves the demo clock forward; there is no other way for the simulated desk to reach a value date.

Verified against the running backend, not only unit tests: dispute, bank re-issue, affirm (twice, harmless), PDF parsed by poppler, PyMuPDF and pypdf; settlement blocked until affirmed; settle on the value date; shortfall once, retried without piling up; window drawdown through to SETTLED with the legs summing exactly; close-out (limit released 88,220 to 48,120); roll; mark reruns not duplicating; a roll whose replacement is refused left nothing behind; and a checksum of every virtual-account balance was identical before and after. Verified in the browser: blotter, drawer, PDF download, confirming through the UI, and the failed settlements appearing in the dashboard's action queue.

Two bugs only a real run exposed, both from Hibernate's `ddl-auto: update` never changing a table it already created: the enum `CHECK` on `fx_deal.status` rejected the new statuses (fixed by V41, conditional so a fresh database is untouched), and `fx_deal_event.to_status` is varchar(20) so a 21-character action code failed (fixed by shorter codes and a guard that refuses an over-long one).

Not done, by design: partial close-out and early delivery; margin and collateral; a screen for the bank back office to resolve a dispute (API only); real balance postings (settlement would then debit and credit the corporate's accounts, behind a setting); a window or part-drawn deal closes or rolls for its undrawn amount as a plain forward; per-currency holiday calendars; the quote guard and the AI layers (phases 5-6). Actor names show as "anonymousUser" in dev.

### RFQ log (added after phase 4)

Every request for a price was already stored (`fx_quote_request`, with a timeline per step in `fx_deal_event` and the audit log), including expired, withdrawn and declined ones, but nothing could list them: the only quote endpoints fetched one by id, and the Deal tab showed just the current quote plus the deals that came from accepted ones. Requests that never became a deal were invisible.

Added: `GET /api/v1/fx/quotes?corporateId=&status=&pair=&days=&limit=` (newest first, default 30 days, up to 365, capped at 500 rows) returning the rows and a summary, and `GET /api/v1/fx/quotes/{id}/events` for one request's history. On the Deal tab an **RFQ log** panel shows the summary tiles (requests, priced, accepted, expired, declined, withdrawn, hit rate and average time to accept), filters for outcome, pair and period, a table, and a drawer with the request's details and history.

Decisions: the summary covers all outcomes for the chosen pair and period, while the outcome filter only trims the rows, so the tiles stay a whole picture. **Hit rate is accepted over settled requests** (accepted, expired or withdrawn): a price still open and a request the desk declined are not counted against it, and with nothing settled it shows "-" rather than a misleading 0%. Listing turns any price that has outlived its validity into EXPIRED, the same as reading it by id, so the log never shows a firm price that is not. The requester shown is the security-context user ("anonymousUser" in dev). 3 more tests (90 FX tests in all); verified live with one request of each outcome, including filters, the restart (the log persists) and a bad status value (clear 400 instead of a 500).

Not done: a "request again" action on an expired or declined row, export, and RFQ analytics beyond the summary (for example decline reasons over time or response time by pair). The data supports all three.

## Phase 5 outcome (FX tools over MCP) - done, uncommitted

Direction from the user: the FX tools should work like the existing MCP connection (Claude and other connectors calling the enquiry server), not as an in-app dealing tool. So phase 5 is four **read-only** `CopilotTool` beans in `ai/copilot/tools/read/`. The MCP pipeline already lists only non-mutating tools, so they appear on `POST /mcp` with no registration.

| Tool | Scope | Returns |
|------|-------|---------|
| `get_fx_rates` | none (market data) | simulated spot for every pair; with `pair` its forward curve; with `pair` + `tenor` or `valueDate` one forward. Always labelled SIMULATED and not dealable. |
| `get_fx_exposure` | corporate required | ladder by currency and bucket against hedge policy, breaches; `currency`, `breachesOnly` filters |
| `get_fx_deals` | corporate required | deals (default open; `all` or one status; capped at 200), FX credit limit used and headroom, attention items |
| `get_fx_rfq_log` | corporate required | the RFQ log summary and rows; `status`, `pair`, `days`, `limit` filters |

Decisions:
- **No dealing through MCP.** Requesting or accepting a price, confirming, drawing down, closing and settling stay in the portal. A connector can read the position and the market; it cannot commit the corporate to a deal. This is why the earlier `propose_fx_deal` idea was dropped: the pipeline hides mutating tools by design, and a proposal tool would be the thin end of that.
- **Scope comes only from the context.** The argument stripper drops `corporateId` style arguments; the three corporate tools refuse with "requires a corporate scope" when the context has none (as `get_cash_forecast` does) and the entitlement guard rejects a corporate outside the caller's signed entitlement. Unscoped dev calls therefore get rates only.
- **Shared code, not copies.** The tools call the same services as the REST controllers (`FxExposureService`, `FxDealService`, `DealViewAssembler`, `FxLimitService`, `FxDealAttentionService`, and a new `FxQuoteService.rfqLog` that the `/fx/quotes` endpoint now uses too), so the portal and the connector cannot disagree. `DealViewAssembler` became public for this. Unlike most read tools these are not marked read-only transactions: listing RFQs lazily expires lapsed prices.
- **No masking rules needed:** the FX outputs carry no BaNCS ids; the global drop of `bancsCustomerId`/`bancsAccountId` still applies.
- MCP server version 0.3.0; the `server/discover` instructions now mention FX and that dealing is unavailable.

Verified: 7 new tests (`FxMcpToolsTest`: each tool's shape and filters, scope refusal touching no service, and the real pipeline stages keeping a caller to their corporate and rejecting an out-of-entitlement one); full backend suite 413 passing. Live with `vam.mcp.enabled=true`: `server/discover` text, `tools/list` shows the four tools, `get_fx_rates` (all, and EUR/USD 3M forward) returns data, the three scoped tools return `requires a corporate scope` when unscoped, and a tenor without a pair gives a clear error.

Not verified live: the scoped path through the gateway's OAuth and signed context (needs the gateway and an account link); it is covered by the pipeline test with a constructed caller. Not done: in-app Copilot intent routing for FX questions (`IntentRouter`/`ResponseComposer`), which is optional and separate from MCP.

## Phase 5b outcome (UI restructure and RFQ negotiation) - done, uncommitted

Triggered by two questions: did the UI follow the app's standards, and where is RFQ negotiation. Both were fair. Full review and the decisions taken are in `fx-ui-redesign.md`.

**Negotiation.** "Negotiated per deal" was built as one instant, take-it-or-leave-it price. Now, on a firm price the corporate can **ask for a better price**, naming a rate or asking for the best, up to 3 rounds (`vam.fx.deal.negotiation-max-rounds`). `SimulatedDealer.counter` answers by rule: the desk never gives up more than a share of its opening margin (its floor: 25%, 35% on a large ticket, 50% on a huge one), each round it gives half of what is left above the floor, and a named rate caps how far it goes (a modest ask is met exactly, an ambitious one part way). A named rate that is not better than the current price is refused with the reason. Each answer is a new firm price with a fresh 30s clock; when nothing is left to give it says "this is our best", and the round is still used. `POST /fx/quotes/{id}/counter` shares the quote's row lock with accept and cancel, so a counter and an accept cannot cross. Credit-limit charge does not depend on the rate, so it is unchanged. The history is the existing quote timeline (a `COUNTER_OFFER` event per round), so no new table; the quote gained nullable columns (`forward_mid`, `rate_scale`, `opening_spread_bps`, `max_rounds`, `negotiation_rounds`, `best_price`) which `ddl-auto` adds. A quote from before this change cannot be negotiated. Quoting precision is stored because the forward mid read back from the database is wider than the pair is quoted to (a first run showed 8-decimal counter prices beside a 6-decimal opening price). 11 new tests (`SimulatedDealerTest`, `FxQuoteServiceTest`); the MCP `get_fx_rfq_log` carries `rounds`, `maxRounds` and `bestPrice` for free.

**UI.** FX is now its own sidebar section, **Foreign Exchange**: Market Rates, Exposure & Hedging, FX Deals, RFQ Log. `FX Workspace` and its three tabs are gone; the old `fx-workspace` id still routes to Deals (and the Cockpit's Hedge and attention links were repointed). Every page reads the header's corporate (no in-page selector), has a header primary action, a stat strip, a toolbar and a list with a `...` Actions column (new shared `RowActions`, which renders in a portal because a menu inside the scrolling table was clipped). **New Deal** is a drawer (ticket, firm price with countdown, negotiation, accept) opened from the header or from a rate row, an exposure gap, or an earlier request ("Request again"). Row menus show only what is valid for that row, from one shared rule set (`fxDealRules`) that the deal drawer also uses; actions that need input open the drawer at that section. Settlement stays a bulk "Run settlement" in the Deals header. Two defects found only by looking at it live and fixed: the clipped row menu, and drawer content with no padding (the Drawer primitive leaves padding to its children; the earlier FX drawers had none either).

Verified: `tsc` and `eslint` clean; 424 backend tests; live: the negotiation API through all rounds to the floor and the round limit, then the same in the drawer (counter, new price and countdown, accept booked the negotiated rate); confirm from the Deals row menu; close-out from the row menu (opens the drawer at that section); Request price from a rate row, Hedge to policy from an exposure row, Request again from the RFQ Log (all pre-filled); light and dark. Test data removed afterwards.

Not done: the bank-side dealer screen (a person quotes and counters by hand; still the realistic end state), per-deal Settle, role gating for deal actions, and exposing negotiation through MCP (deliberately: dealing is not available there).

### Hallmark polish pass (after phase 5b)

An audit of the four FX pages with Hallmark (app scope) found FX-owned issues, fixed in place without changing layout or look: meta strings joined with middle dots now use commas or their own line; success toasts for effects the screen already shows (price improved, deal booked, confirmed, withdrawn, close-out, roll) are gone and toasts remain for errors and for the clipboard copy (the only invisible effect); "simulated" is said once on Market Rates (the badge) and the feed tile no longer claims "Running" before the first rate; apostrophes in labels are typographic. Not FX's to change: uppercase tracked table heads and nav section titles (shared `DataTable` and shell) and the coin-style `StatTile`; both would be an app-wide change.

Animation note: every right-side panel in the app (Accounts, Exceptions, Forecasting, the Simulator's create drawers, and FX) is the shared `Drawer` with `animate-slide-in-right`; create dialogs such as Create Sweep Rule are a centred `Modal` with `animate-scale-in`; payments is a page with `animate-page-enter` (fade and 8px rise). The app stylesheet cuts all animation to 0.01ms under `prefers-reduced-motion: reduce`, and the in-app browser pane reports that preference as on, so no panel visibly animates there; the same will hold on any machine (Azure Virtual Desktop and RDP sessions often set it) with Windows "Animation effects" off.

### Corporate view: no bank-internal pricing (after the polish pass) - done, uncommitted

The corporate saw the bank's interbank mid, forward points and margin in the price ticket, the RFQ log (table, drawer, CSV), the trade confirmation text and PDF ("Price build-up: interbank mid ..., bank margin ... bps") and the negotiation history ("margin 2.63 bps"). Removed from every corporate-facing surface: `QuoteView`, `DealView`, `CloseOutQuote` and `RollQuote` no longer carry them, so neither the screens nor the MCP tools can show them; the confirmation states the all-in rate only; the negotiation history says what was offered. The values stay on the stored rows for the bank-side dealer screen. Market Rates keeps indicative rates and forward points (market information, labelled indicative). A guard test fails if a corporate-facing shape gains a mid, spread, points or margin field, or the confirmation mentions them.

### Gaps found (2026-11-06)

**Against our own design.** The plan named the cash-flow forecast as an exposure input; phase 2 built payables, receivables, balances and CSV only. "FX on payments" (rate lock on POBO and payables conversion) was a chosen job and was never built.

**Against bank platforms** (public sources on Straight2Bank are thin and mostly 2011 to 2012; Citi Velocity is better documented): FX swaps; NDFs; 100+ pairs including exotics (we list 12); FX booked inside the payment flow; pre-agreed standing margins per client; exposure-file upload that hedges automatically; post-trade reports. Common on such platforms but not confirmed by a source here: limit, stop and fixing orders; rate alerts; settlement instructions per deal; maker-checker approval for dealing; booking for a subsidiary; holiday calendars per currency.

### Proposed: exposure from the Cash Forecast (not built, awaiting go-ahead)

Today the ladder reads open payables and receivables by due date, cash held in physical-account mirrors, and the CSV upload. The Cash Forecast already holds native-currency flows per corporate: AGING lines (the same payables and receivables, on an expected payment date), PATTERN lines (recurring flows such as payroll and rent that are in no invoice), and ML, DRIVER and MANUAL lines, each with a confidence and P10/P90. Proposal: when the corporate has a completed forecast run, take foreign-currency flows from its latest run instead of reading payables and receivables directly (AGING already carries them, so reading both would double count); show them as Committed (AGING) and Forecast (the other sources) per bucket; leave out the INTERCOMPANY_IN/OUT and FX_CONVERSION categories and dates already past; keep cash held and the CSV upload; fall back to payables and receivables when there is no run; show the run's date and flag it when stale. Seen in the data: one corporate has a completed run, dated 2026-09-19, with GBP 3.88m of PATTERN outflows that the ladder does not see today.

## Next scope, from the portal interview (2026-11-06) - not built

What the user sees on Straight2Bank, HSBCnet and Citi (CitiDirect/Velocity), and what follows for us. Not needed: FX swaps, NDFs, orders (only spot and forwards are bookable), maker-checker on dealing.

| # | Seen on the portals | We have | To build |
|---|---|---|---|
| A | Forward price shown as all-in rate plus spot and forward points, no margin | All-in only (the last fix removed points too) | Show the client spot (spot with the margin in it) and the points that take it to the all-in rate; never the mid or margin |
| B | Streaming click-to-deal for small and short trades, RFQ for large or long-dated, decided by amount and tenor | RFQ only | A streaming two-sided executable price under per-pair amount and tenor thresholds; RFQ (with negotiation) above them |
| C | Settlement accounts default per currency (standing instructions), editable on the deal | Settlement picks the corporate's accounts itself | Standing settlement instructions per entity and currency; shown and editable on the ticket and the deal |
| D | Deal booked for a legal entity chosen on the ticket | One counterparty per corporate | Entity on quote and deal; confirmations, settlement and reports per entity; limit stays at corporate level unless the bank sets entity limits |
| E | FX in payments: a live rate on the payment screen booked on submit, or the payment linked to a deal already booked | Nothing (flat conversion in payments) | Rate lock on foreign-currency payments, and "use deal FXD-..." to settle a payment against a forward or window drawdown |
| F | Reports: deal history export, maturities and settlements, month-end MTM statement, confirmation archive | CSV export of the blotter; confirmations per deal | A Reports page (or tab) with the four, period-filtered, PDF and CSV |
| G | Notifications: rate alerts, maturity reminders, confirmation pending | Attention items in the Cockpit only | Rate alerts the corporate sets; reminders through the app's notification bell |
| H | (our own plan) exposure from the Cash Forecast | AR/AP, cash, CSV | As proposed above; decision: forecast flows count **in full** toward the hedge ratio |

**A and H done (2026-11-06), uncommitted.**

- **A.** Quotes and deals carry `spotRate` and `forwardPoints` for forwards and windows: the spot is the market spot moved against the client by the same margin as the all-in rate, and the points take it exactly to the all-in rate (`FxDealModels.ClientPrice`). Neither the mid nor the margin can be read from them. Shown on the price ticket, the deal drawer, the RFQ drawer and the confirmation ("Spot rate", "Forward points", then "Rate"). Spot deals show no build-up. The guard test now allows the client's spot and points and still rejects any mid, spread, margin or floor field, and checks spot plus points equals the all-in rate. Live: EUR/USD 3M buy, spot 1.092904 + 54.97 points = 1.098401, market mid 1.09236.
- **H.** The ladder gains a Forecast column: flows from the corporate's latest completed Cash Forecast run that no invoice carries (pattern, ML, driver and manual lines; payroll, rent, tax), counted in full. Committed flows still come from live payables and receivables, not the run's AGING copy of them, because a run can be weeks old (the only one is) while invoices change daily; this differs from the first proposal above, which would have taken AGING from the run. Intercompany and FX-conversion categories and past dates are left out; forecast adjustments are not applied, matching the Cash Forecast summary. The ladder reports the run's time and flags it stale after `vam.fx.exposure.forecast-stale-days` (7). Exposure page: an "Of which forecast" column and a Cash Forecast tile (run date, or out of date, or no run). The MCP exposure tool carries the run date too. Live: GBP payroll of 850,000 on 20 Nov turned the GBP 0-30 bucket from +419,320 into -430,680 and the suggested hedge from a sale into "Buy 323,010 GBP".

## Open risks / questions to revisit

- Forward points from a simple interest-differential table are illustrative only; fine for a demo, not for pricing real trades.
- Settlement of a forward on value date needs a scheduler and cut-off handling (`CurrencyCutoffConfig` exists, reuse).
- Hedge accounting (IFRS 9 effectiveness, revaluation P&L) is out of scope but the deal model should keep fields to add it.
- Regulatory framing (mock demo vs live dealing licence, EMIR/Dodd-Frank-style reporting, local market rules per `MarketProfile`) deferred until the live-rates decision.
- Maker-checker for corporate users was deferred; for a bank it is usually a day-one requirement for dealing.
