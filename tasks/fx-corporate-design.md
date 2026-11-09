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

**Decisions for B, E, F, G and the pre-existing issues (interview, 2026-11-06).**

| Item | Decision |
|---|---|
| B | Per pair, bank-set: a settings row per pair with a max streaming amount and tenor. Seeded: majors up to 1m base and 1 month, others up to 250k and spot only. Above them: RFQ with negotiation |
| E | All payment types (single, POBO, bulk): rate lock on submit, or use a booked deal |
| F | (default, not asked) the four reports, period-filtered, CSV and PDF |
| G | Rate alerts on the indicative market rate; maturity and pending-confirmation reminders; all through the notification bell |
| Old bugs | Fix all three: Credit Facilities page (hard-coded corporate, NaN, "Standalone Facility"); collections and payments reverted by the balance refresh; two active EUR/AED mid rates |

**Old bugs fixed (2026-11-06), uncommitted.**

- **Duplicate current FX rates.** Root cause: `FxRateService.saveRate` ended earlier rates only `if (rate.getId() != null)`, and a new rate has no id until saved, so every new rate stayed current beside the one it replaced (seeded rates never went through it either). Eight pairs had two or more open rates (EUR/AED 4.01 and 4.0125, EUR/USD 1.0905 and 1.0926, ...). Now the service saves first, then ends (sets `effective_to`, keeps active so fixings still resolve by date) the open rates of the same pair, type and scope dated on or before it. V43 ends the existing duplicates. Test: `FxRateSupersedeTest`. Live: no pair has more than one open rate.
- **Credit Facilities page.** The page read `facilityLimit` (the API sends `sanctionedLimit`: hence NaN), expected an `agreementName` the API does not send (hence "Standalone Facility"), and used a hard-coded `corp-1` (the API rejected it and the page silently fell back to every corporate's facilities). Now: the header's corporate (all corporates when none is chosen), the API's field names, agreement names looked up from the corporate's agreements, totals shown only when one currency, creation refused without a corporate. Credit Agreements had the same hard-coded corporate and a `counterpartyBankName` the API calls `bankName`; both fixed.
- **Postings undone by the balance refresh.** `CoreBankingBalance.follow` moves the linked physical account by the same delta whenever the app moves a CORE_BANKING mirror's bank balance; called after every such movement (five in `TransactionService`: collections, payments, POBO; one in `SweepService`; FX posting). Mirrors fed by statements (MT940) are left to their bank. Test: `CoreBankingBalanceTest`. Live: an FX settlement debit survived a forced refresh of the mirror.

**Market Rates shows the client's two-way price (2026-11-06), uncommitted.** The Rate Board and forward curve showed the interbank mid and market forward points ("Market Rates keeps indicative rates", decided at the pricing-leak fix): not a price the corporate can deal at, and beside a deal price it gives the margin away. Interview: buy and sell at an amount box (default 100,000 of the base); no mid anywhere on the page; the forward curve shows the client's outright buy and sell per tenor with the client points; clicking a price opens the ticket on that side (pair, side, amount, and for the curve instrument and value date). `FxBoardService`/`FxBoardController` (`/fx/board`, `/fx/board/curve`, `/fx/board/forward`) price both sides with the desk pricing the ticket uses; their rows carry no mid (pricing guard test covers them). Rate alerts follow: the corporate chooses "buy at or below" or "sell at or above" a rate, on its own price at the board's amount; `fx_rate_alert` gains `side` and `amount`; the dialog shows the current buy and sell, never the mid. Tests: `FxBoardServiceTest`, `FxNotifierTest`. Live: board payload has buy/sell/change/value date only, the spread widens with the amount (EUR/USD 100k 1.092272/1.092928, 5m 1.091874/1.093186), a curve price opens a forward ticket on that side, an alert fired on the client buy price.

**E: findings and decisions (2026-11-06).** No payment path converts today: the single-payment request has no currency; the ISO 20022, file and payables paths carry one but book every leg in the debit account's currency (a USD 1,000 payable on an AED account debits AED 1,000); the POBO "FX markup" is a 0.25% fee on any non-AED payment; one POBO execution path (`PoboExecutionService.executePaymentViaTreasury`) is a mock with no ledger posting. Interview: rate lock = the streamed price within the pair's streaming limits, a firm RFQ price above them; a booked deal can be used if it is a window forward (draw the payment amount on the payment date) or a forward whose value date is the payment date (part or all; the rest settles through the SSIs); money moves through the payment (the paying account is debited the converted amount; the deal or drawdown is marked settled by the payment, no SSI postings); also fix: refuse a payment whose currency differs from its account unless FX is locked or a deal is used. Not fixing (not chosen): the POBO mock execution, the AED-hardcoded POBO markup.

**E built (2026-11-06), uncommitted.** `FxPaymentService.convert` runs before a payment's ledger legs, `settle` after, in one transaction. Instruction: `fxPriceId` (a streamed price the payer saw), `fxQuoteId` (a firm price requested with `forPayment`), `fxDealId` (a booked window open today or a forward due today) or `fxAtMarket` (files: the live price, within the streaming limits only). The FX deal is booked for the paying account's owning entity (its FX sub-limit and the line apply), is confirmed by submitting the payment, needs no settlement accounts, and is settled by the payment ("Paid out as payment ..."); a part-used forward keeps the rest for its SSIs. The payment's transaction carries `instructed_amount`, `instructed_currency`, `fx_rate`, `fx_deal_ref`; `amount` is the debit in the account's currency; the pain.001 instructs the payment currency. A for-payment price cannot be accepted on its own, and has no dealing minimum. Hooked into the ISO 20022 outward path (Transfers screen, API, file-ingest PAYMENTS rows at market), the ISO bulk path (per row; at market unless the row carries FX), and payables execution (Pay now dialog, file-ingest PAYABLES at market). A payment in another currency without FX is now refused (it used to be debited one for one). A failed ISO payment now rolls back cleanly (FX booking included) and returns its RJCT, instead of the 500 "rolled back because it has been marked as rollback-only" any inner failure caused before. Not covered: the single `/transactions/payment` API has no currency (always the account's); the ISO bulk path still commits rows in one transaction (an inner failure there marks it rollback-only, as before). UI: `PaymentFxPanel` on the Transfers outward payment and the payables Pay now dialog (live rate, firm price above the limits, or a deal to draw on). Live 18/18 (streamed price, at market, firm price above the limit and refusal of its standalone accept, failed payment leaves no deal, unconfirmed window refused, window drawn by the payment, over-draw refused, refusal without FX). Found while testing, then fixed (2026-11-06, "Fix outward payments that cannot settle"): (1) since commit a64b8c7 (payments settle only through the paying program's own bank account) FINAL IHB, Main Coll VAs and Test Multi-Currency could not pay: their corporate's real bank accounts belong to "Test Already Initialized Program". `ProgramBankAccountBackfillRunner` (@Order 113) opens a home-bank account for each program and currency that has accounts but no bank account (balance = what the program's accounts hold there, ledger = bank), provisions a missing settlement account (Main Coll VAs AED), and where a program has currency mirrors completes the mirror layer along its older accounts' paths (a level with a mirror counts only mirrors, so a partial layer had left COLL5's root at 0 and failed every funds check). (2) Settlement VAs were provisioned inside a currency mirror (`findParentForSettlement` picked the source's mirror, else any mirror of the program), where the resolver never looks; now beside the mirror or at the program's top level, and the three stuck ones (COLL5 AED, TEST-WALLET-NEW USD, TEST-MULTI-CCY INR) are moved to the top level. (3) The "parked for review" record of a refused payment is written in its own transaction (REQUIRES_NEW), so it is kept when the payment rolls back. (4) Because MNC Treasury Center gained a second home-bank account in USD and AED (Test Multi-Currency's), `FxSettlementAccountSeeder` (now @Order 114) pins an instruction to the oldest account where an FX entity has several and none, so its deals keep settling where they did. Live: 8/8 repair checks, FX in payments 18/18 on an ordinary account (QA-TXN-L7-53197), no root or aggregation total changed other than COLL5's root (0 to 1,635,001). (5) Deadlock between payments and the balance refresh ("Fix deadlock between payments and balance refresh"): `propagateBalanceChange` started the corporate's async full recalculation from inside the payment's transaction, and `aggregateForCorporate` rewrote every mirror and aggregation in one transaction, locking in another order the rows the payment had locked (steps 1-2), so two payments a second apart could fail with a 500 "deadlock detected"; it also read pre-commit balances. Now the recalculation starts after the commit (once per corporate per transaction), and `aggregateForCorporate` recalculates node by node, each in its own REQUIRES_NEW transaction that locks the row (`VirtualAccountRepository.lockById`) and recomputes from the children at that moment, deepest first then by id; it, `scheduledAggregation` and the async refreshes are no longer one long transaction. The payment's own steps 1-2 are unchanged (the funds check reads the aggregates they keep current). Tests: `BalanceRefreshAfterCommitTest`. Live: 4 rounds of 16 parallel payments in one corporate, 0 failures, no deadlock in the log, figures left by the refreshes equal a fresh recalculation, totals back to the snapshot after restoring. Also not fixed: the Transfers summary sidebar's "Total debit" previews the amount in the account's currency, so for an FX payment it shows the payment amount plus fee, not the converted debit; and most demo accounts have no owning entity, so they cannot convert (the panel says so).

**B built (2026-11-06), uncommitted.** Streaming limits per pair in `vam.fx.deal.streaming` (bank-set, like the per-pair spreads): majors (EUR/USD, GBP/USD, USD/JPY, USD/CHF, EUR/GBP, EUR/JPY) up to 1,000,000 base and 31 days after spot; every other pair (and its inverse) up to 250,000 and spot only; windows always on request. `FxStreamService` prices both sides, keeps the issued price for `stream-validity-seconds` (5) and books a click through the same quote-and-accept path as an RFQ (limits and settlement accounts checked the same way; the request appears in the RFQ log as accepted, not negotiable). A price is dealt on once; a stale one is refused. Endpoints `GET /fx/stream`, `POST /fx/stream/deal`. The ticket shows a live two-sided tile (refreshed every 2 s, client spot and points for forwards) and switches to "Request price" with the reason when the trade is above the limits. Tests: `FxStreamServiceTest` (rules, inverse pairs, one-shot price, declined path); the pricing guard covers the stream shapes. Live 9/9. ponytail: issued prices are held in memory, so one backend instance (Redis or sticky sessions beyond that).

**G built (2026-11-06), uncommitted.** The header bell was hard-coded demo data and nothing in the backend wrote or read the existing `notifications` table. Now: `NotificationService` (raise once per type and reference, inbox per corporate, mark read) and `GET /notifications`, `POST /notifications/{id}/read`, `POST /notifications/read-all`; the bell (`NotificationBell`) shows the selected corporate's latest 30 (all corporates' when none is selected), polls every 30 s, and opening one marks it read and goes to its page. **The hard-coded demo entries are gone**: the bell shows only real notifications, and today only FX raises them. FX (`FxNotifier`, one scan every 30 s): rate alerts on the indicative market rate (`fx_rate_alert`; direction set from the rate when made; fire once; set and cancel on Market Rates, listed there), a reminder when a deal's value date is within two days, and a confirmation waiting to be checked. Tests: `FxNotifierTest`. Live 10/10.

**F built (2026-11-06), uncommitted.** FX Reports page (Foreign Exchange menu) with the four reports for a period (this month, last month, this quarter, this year, custom): deal history (traded in the period), maturities and settlements (settled in the period with the accounts posted, plus what is still due), MTM statement (deals open at the period's end: live indicative marks when that is today or later, else the last end-of-day mark on or before it; total in home currency), confirmation archive (issued in the period; its PDF is the confirmations themselves, one per page). `FxReportService` builds one table per report; the screen, CSV and PDF come from it. The hand-written PDF writer now paginates (and starts a page on a form feed) and has a landscape layout for tables; confirmations keep their portrait 88x68 layout. No mid, margin or spread in any report. Tests: `FxReportServiceTest`, `ConfirmationPdfTest`.

**A and H done (2026-11-06), uncommitted.**

- **A.** Quotes and deals carry `spotRate` and `forwardPoints` for forwards and windows: the spot is the market spot moved against the client by the same margin as the all-in rate, and the points take it exactly to the all-in rate (`FxDealModels.ClientPrice`). Neither the mid nor the margin can be read from them. Shown on the price ticket, the deal drawer, the RFQ drawer and the confirmation ("Spot rate", "Forward points", then "Rate"). Spot deals show no build-up. The guard test now allows the client's spot and points and still rejects any mid, spread, margin or floor field, and checks spot plus points equals the all-in rate. Live: EUR/USD 3M buy, spot 1.092904 + 54.97 points = 1.098401, market mid 1.09236.
- **H.** The ladder gains a Forecast column: flows from the corporate's latest completed Cash Forecast run that no invoice carries (pattern, ML, driver and manual lines; payroll, rent, tax), counted in full. Committed flows still come from live payables and receivables, not the run's AGING copy of them, because a run can be weeks old (the only one is) while invoices change daily; this differs from the first proposal above, which would have taken AGING from the run. Intercompany and FX-conversion categories and past dates are left out; forecast adjustments are not applied, matching the Cash Forecast summary. The ladder reports the run's time and flags it stale after `vam.fx.exposure.forecast-stale-days` (7). Exposure page: an "Of which forecast" column and a Cash Forecast tile (run date, or out of date, or no run). The MCP exposure tool carries the run date too. Live: GBP payroll of 850,000 on 20 Nov turned the GBP 0-30 bucket from +419,320 into -430,680 and the suggested hedge from a sale into "Buy 323,010 GBP".

## Plan: FX line inside the app's credit model, with D (legal entity on the deal) - awaiting go

**Review (2026-11-06).** The app has a full credit model: per-bank credit agreements holding typed facilities (overdraft, revolving, LC, guarantee, trade lines), synced from core banking (external limits), and CFO-allocated internal limits (a group limit per currency, sub-limits per legal entity and account), with utilise/release, held amounts, hard or soft, approval thresholds, warning (80%) and critical (95%) levels, effective and expiry dates, and breach and expiry lists. The FX limit sat outside all of it: `fx_credit_limit`, one row per corporate (nine rows, default 20m in the home currency), usage = notional x a tenor factor, no agreement, facility, entity, threshold, approval or expiry.

**Decisions (interview).**

| Question | Decision |
|---|---|
| Where the FX line lives | A facility of a new type (FX / treasury line) under the bank's credit agreement, synced like the others; its external limit is what deals use |
| How a deal uses it | Spot at full notional; forwards and windows risk-weighted (notional x tenor factor, as now) |
| Entities | One group line, sub-allocated to legal entities as internal sub-limits; a deal checks its entity's sub-limit and the group line |
| Over the line | Hard: the desk declines with the reason |
| Currency | The facility's currency; deals converted at the platform rate |
| No FX facility | The corporate cannot deal ("no FX line"); demo: seed an FX facility per corporate at today's 20m, and an agreement where one is missing |
| Entity with no FX sub-limit | Cannot deal until the CFO allocates one |
| Controls that apply | Expiry and status block dealing; 80% and 95% raise attention items; the line and its sub-limits are shown and managed on Credit Facilities and Credit Limits; FX Deals shows the group line and the entity's usage |

**Build (one step with D, because entity sub-limits need deals booked for an entity).**
1. Facility type for the FX line (a Flyway migration, as V41 did, since Hibernate's CHECK on `facility_type` will not widen by itself); its external limit at group level; FX sub-limits per entity as internal limits whose parent is that line, kept apart from the entity's cash and overdraft sub-limits.
2. Entity on quote and deal (D): picked on the ticket from the corporate's legal entities; carried to the confirmation, the deal, the RFQ log, the reports later, and the MCP tools.
3. Limit checks at quote and accept: facility and limits active and in date, entity sub-limit present, charge within the entity sub-limit and the group line; locks on both rows at accept, as today on `fx_credit_limit`.
4. Usage stays derived from open deals (the source of truth) and is written to the credit limits' `utilizedAmount` on every booking, close-out, roll and settlement, so the Administration pages show the same figure; spot at full notional.
5. Migration and seeding (an ApplicationRunner, as the FX seeders do): per corporate, an FX facility at 20m under its agreement with the home bank (creating the agreement where missing) and, so the demo keeps dealing, the whole line allocated to the corporate's treasury centre or holding entity; other entities need an allocation. `fx_credit_limit` retired.
6. UI: FX facility on Credit Facilities; FX sub-limits on Credit Limits; FX Deals shows the group line and the selected entity's sub-limit; the ticket shows the entity and its headroom.
7. Attention items at 80% and 95% through the existing limit warning lists.

### Built (2026-11-06): FX line in the credit model, with the deal booked for a legal entity - uncommitted

As planned above. Notes from the build:

- **Kept out of cash credit.** The FX line is `credit_limits` target type FX_LINE (external, from the FX_TREASURY_LINE facility) and the entity sub-limits FX_ENTITY (internal, parent the line). Fifteen `CreditLimitRepository` queries that matched only on corporate, target id or limit type now exclude both (`FxCreditLineRepository` reads them for FX). The guard is needed, not theoretical: two entities hold both a cash and an FX sub-limit (an unguarded single-result lookup would throw), and FX rows would have added 40m to two corporates' credit totals. Proof: 198 cash-credit reads (corporate limits, totals, totals by currency, groups, entity checks and ceilings, entity limits, funds availability) captured before the change are byte-identical after seeding and again with an FX deal open. The warning and critical lists include FX on purpose.
- **V42** widens the hand-written CHECKs (`chk_cf_type`, `chk_cl_target_type`) and drops `fx_credit_limit`; mirrored in `database/migrations/`. `FxLineSeeder` gave the nine corporates a 20m FX facility under a home-bank agreement (created for all nine, none had one with the home bank), the line, and the whole line on the treasury centre or holding entity.
- **Usage** is derived from open deals and written to the line's and sub-limits' utilised amounts, the line's allocated-to-children and the facility's outstanding on every deal status change and after the daily mark. The Credit Facilities page no longer offers Draw/Repay on an FX line.
- **Entity on the deal**: quote, deal, confirmation ("Entity"), RFQ log, deals list, CSV exports, roll (the replacement keeps the entity), MCP deal tool (line and sub-limits). The ticket defaults to the header's entity and shows its sub-limit and the group line's headroom; an entity without a sub-limit sees why and cannot request a price.
- **Credit Limits page**: an FX line section with every entity's sub-limit and an allocate/change action; allocations over the line or below current use are refused with the reason. Limit alerts at each limit's own 80% / 95% thresholds feed the Cockpit ("Review limits").
- **Tests**: `FxLimitServiceTest` rewritten (12, against an in-memory store of the credit rows), quote tests for the entity and the locked re-check, roll keeps the entity; backend 436 passing. Live: 19 scripted checks against the running app and database (decline without a sub-limit, allocation over the line, spot at full notional, usage written to line, sub-limit and facility, decline over the sub-limit, forward at its risk weight, 80% alert, no cut below use, re-check at accept, expired facility, suspended line, facility listed); then the screens.
- **Found, not fixed (pre-existing):** the Credit Facilities page uses a hard-coded corporate (`'corp-1'`, a TODO) and so lists every corporate's facilities, shows "NaN" for every facility's limit and "Standalone Facility" for every agreement name; two active EUR/AED mid rates exist (Reuters 4.0125, central bank 4.01) and the rate service resolves 4.01.

## Plan: C, standing settlement instructions (SSIs) - built 2026-11-06, uncommitted

**Review (2026-11-06).** FX settlement is a record only: no account is named and no balance moves; the funds check sums the corporate's cash in the sell currency across all its bank-account mirrors. The app has accounts per legal entity per currency, bank-account mirrors (shadows of real accounts, each linked to a physical account with a bank BIC), a settlement mark on transaction accounts (used for fee and price postings), external beneficiaries and debit mandates, and a single-entry posting model (one movement per account; fee postings already post a debit/credit pair). No SSI concept exists. Under the active UK profile the home bank is HBUKGB4BXXX and only two mirrors in the data are at it.

**Decisions (interview).**

| Question | Decision |
|---|---|
| What an SSI may point to | The entity's **bank-account mirrors at the home bank**, in that currency |
| No SSI for a currency | Default to the entity's only home-bank mirror in it; none, or several, and the price request is declined with the reason |
| Settlement | **Posts**: on value date, debit the sell-currency account and credit the buy-currency account (single-entry movements). Replaces "record only" |
| Funds check | Corporate total in the sell currency, as now |
| Debit account short | Post anyway; the account goes negative and a funding attention item is raised |
| Where managed | A Settlement Instructions page under Foreign Exchange; a deal's accounts can be changed until the day before value date |
| Demo data | For each entity holding an FX sub-limit, open home-bank accounts (physical account and mirror) in its home currency, USD, EUR and GBP where it has none, with a demo opening balance |

**Build.**
1. `FxSettlementInstruction` (new table: corporate, entity, currency, account; one per entity and currency) and a resolver: SSI, else the single home-bank mirror, else a reason.
2. Quote: both legs' accounts resolved at the price, declined with the reason when one cannot be; the accounts carried to the deal (and a roll's replacement), shown on the ticket, the deal, the confirmation and the MCP tools.
3. Deal: change settlement accounts until the day before value date (validated against the same rules).
4. Settlement (maturity, drawdown, close-out difference): after the corporate-level funds check, post the debit and credit movements through the app's posting path; an account left negative raises a funding attention item.
5. Settlement Instructions page: entities by currency, current SSI or derived default, set or change from eligible accounts.
6. Demo seeding (runner): home-bank accounts for FX entities, as above.
7. Tests: resolver, quote refusal, change window, posting amounts and directions, negative balance item; live run including balances before and after a settlement, and a check that only the two settlement accounts moved.

### Built (2026-11-06) - uncommitted

- `FxSettlementInstruction` + `FxSettlementAccounts` (resolver, eligibility = active PHYSICAL_MIRROR of the entity whose physical account's BIC starts with the home bank's 8-character BIC) + `FxSettlementController` (`GET/PUT /fx/settlement/instructions`, `GET /fx/settlement/accounts`, `PUT /fx/deals/{id}/settlement-accounts`).
- Quote, deal and settlement carry account ids and labels; the confirmation lists the credit and debit accounts; a roll's replacement keeps the rolled deal's accounts.
- `FxPosting` posts one leg: `applyShadowMovement` on the mirror, the same delta on the linked **physical account** (current, available, ledger), and a COMPLETED CREDIT/DEBIT movement in `va_movements` with the deal ref as correlation id. Settlement (maturity, drawdown) posts both legs; close-out posts the result in the quote currency to the deal's account in it (or records why not). Deals booked before C take their entity's accounts at settlement; an unusable account fails the deal with the reason (the account can then be changed, as SETTLEMENT_FAILED is inside the change window).
- Why the physical account moves too: `BalanceRefreshService` copies the physical account's balance onto CORE_BANKING mirrors every 15 minutes, so moving only the mirror would be undone. **Pre-existing, not fixed:** the app's other postings to mirrors (collections, payments) move only the mirror and are reverted by that refresh.
- Overdrawn settlement accounts raise a FUNDING_NEEDED attention item ("Account overdrawn by FX settlement: ...").
- `FxSettlementAccountSeeder` (@Order 112) opened home-bank accounts with 5,000,000 for each FX-entity in its functional currency, USD, EUR, GBP where missing (31 accounts for 9 entities on the demo data).
- UI: Settlement Instructions page (Foreign Exchange menu); the ticket shows where each leg settles and blocks the request with the reason; the deal drawer shows and changes the accounts (row action "Change settlement accounts..."); settlement items show the accounts posted; "record only" wording removed.
- Tests: 121 FX unit tests green (new: posting directions/amounts, overdrawn note, unusable account, legacy deal resolution, close-out posting and its refusal path, quote accounts and refusal, change window). Live scenario 22/22 (`ssi_scenario.py` in the session scratchpad): balances on mirror and physical account, two movements, nothing else moved, ambiguity decline, SSI set and refused for a non-eligible account, change inside and outside the window, overdraft and its funding item. Test data removed afterwards.
- Not unit-tested: the resolver's JPQL (covered by the live run).

## Open risks / questions to revisit

- Forward points from a simple interest-differential table are illustrative only; fine for a demo, not for pricing real trades.
- Settlement of a forward on value date needs a scheduler and cut-off handling (`CurrencyCutoffConfig` exists, reuse).
- Hedge accounting (IFRS 9 effectiveness, revaluation P&L) is out of scope but the deal model should keep fields to add it.
- Regulatory framing (mock demo vs live dealing licence, EMIR/Dodd-Frank-style reporting, local market rules per `MarketProfile`) deferred until the live-rates decision.
- Maker-checker for corporate users was deferred; for a bank it is usually a day-one requirement for dealing.

**FX on the remaining payment paths (2026-11-06).** The single payment API (`POST /transactions/payment`) takes an optional `currency` and the same FX choices as the other paths (`fxPriceId`, `fxQuoteId`, `fxDealId`, `fxAtMarket`), converted inside `TransactionService.makePayment` (callers that converted already send no currency); its response carries `instructedAmount`, `instructedCurrency`, `fxRate`, `fxDealRef`. Its preview (`/transactions/payment/preview`) takes `currency`, `fxQuoteId`, `fxDealId` and returns the converted debit, the rate and pair, the deal ref and whether the rate is indicative, booking nothing (`FxPaymentService.preview`); a payment that cannot be converted previews with the reason. The Transfers sidebar shows "Pays", the rate (deal, firm price, or live), "Converted", then fee and Total Debit in the account's currency. Bulk ISO rows each run in their own transaction (REQUIRES_NEW): a failed row rolls back alone, its FX booking with it, so `abandon` is gone. Found while testing: since E, bulk rows and file payables with no FX of their own were sent "at market" and refused when in the account's own currency ("nothing to convert"); "at market" now means convert if needed. Live 15/15 (`payfx_gaps.py`).

## Plan: deal-first FX in payments, with utilisation - interview 2026-11-06, built (uncommitted)

**Today.** The Transfers outward payment and Payables "Pay now" show `PaymentFxPanel`: "Lock a rate" (live rate under the pair's streaming limit, booked on submit; a firm price above it) or "Use a booked deal" (a confirmed window open today or a forward due today, one deal, whole payment). Files and bulk convert at the live rate per row. A deal's use shows only as `drawn_amount` and a settlement row "Paid out as payment ...".

**Decisions (interview).**

| Question | Decision |
|---|---|
| Threshold between auto-booking and asking the desk | The pair's streaming limit (bank-set), as for dealing |
| Default for a foreign-currency payment | Outstanding deals that fit first; whatever they don't cover is booked at the live rate on submit (below the limit) or needs a firm price (above it). Applies above the limit too |
| Deals a payment can draw on | Window forward open today; forward due today; **forward not yet due** (early take-up); **spot not yet settled**. Same pair and direction (buys the payment currency, sells the account's), same entity, confirmed, something left |
| Early take-up rate | The deal rate as booked (the bank absorbs the points difference) |
| Payment larger than one deal | Combine deals in order until covered, then the live rate / firm price for the rest |
| Order when several fit | Soonest value date first |
| Payer override | The preselected deals are listed with the amount each covers; the payer can untick any (the rest goes to the live rate or a firm price) or switch to rate only |
| Bulk and files | Per currency, the file's total is covered by fitting deals (soonest first), then ONE deal for the remainder; the streaming limit is judged on that remainder (a big file split into small rows cannot dodge it); rows are then assigned line by line in file order to those deals, each line showing the deal(s) and rate it used. A row naming its own FX keeps it |
| File remainder above the limit, no deal | Those rows are held (awaiting FX); treasury asks for a firm price for the remainder (or books a deal) in the app and releases them; other currencies pay now |
| Utilisation shown | On the deal (used / remaining, and each payment that drew on it: ref, date, amount, rate); on the FX Deals list (Utilised amount and %); on the payment (deal ref(s) and rate) |

**Build.**
1. `fx_deal_utilisation` (deal, payment transaction and ref, base amount, counter amount, rate, when): one row per deal a payment draws on; `drawn_amount` stays the running total. Written by `FxPaymentService.settle`; replaces reading `fx_settlement` for payment use.
2. `FxPaymentService`: a cover plan, `plan(account, currency, amount)` = fitting deals (eligibility above, soonest value date first) with the amount each covers, plus the rest; `convert` takes a list of deal draws plus an optional FX for the rest (price, firm quote, at market). Spot and not-yet-due forwards: the drawn part is settled by the payment, the rest keeps settling through the SSIs on value date. The payment's debit = sum of the draws plus the rest; its `fx_rate` = debit / instructed (blended), `fx_deal_ref` the first deal, the full list in the utilisation table.
3. Single API, ISO single payment, payables: accept a list of deal draws (`fxDeals: [{dealId, amount}]`); with none and no other FX, the plan is applied for unattended callers (`fxAtMarket`), never silently for the screen (the screen sends what the payer saw).
4. Preview and `PaymentFxPanel`: show the plan (deals ticked, amount each, rate, remainder at live or "ask for a price"); untick or switch to rate only; the Transfers sidebar lists each part.
5. Bulk ISO and file ingest (PAYMENTS, PAYABLES): group by currency, plan the total, book one deal for the remainder within the limit, assign rows in order; above the limit with no deal, hold the rows of that currency (new status AWAITING_FX with the reason) and offer "Get a price and release" on the file's page; rows in their own transactions as now.
6. FX Deals: Utilised column (amount, %); deal drawer: utilisation list. Payment details (Transactions, Transfers result, Payables history): deal ref(s) and rate.
7. Tests: plan ordering and eligibility, combining, early take-up and spot partial use, blended rate, bulk total and line assignment, the hold path; live run on the demo data.

**Open (defaults unless told otherwise).** POBO and IHB-routed payments follow the same rule (they share the conversion step). A deal fully used by payments is SETTLED; one partly used keeps its status (PARTIALLY_DRAWN for windows; a forward or spot keeps ACTIVE / PENDING_SETTLEMENT with less left).

### Built (2026-11-06) - uncommitted

- **Differences from the plan.** No `fx_deal_utilisation` table: `fx_settlement` already recorded each payment's part of a deal (amounts, date, "Paid out as payment ..."), so it gains `payment_ref` and `payment_id`; a deal's rows with a payment are its utilisation. The FX Deals blotter shows utilisation as a line under "You buy" ("USD 300.00 used · 20%") rather than its own column: at desktop width the blotter (1154px) has no room for another column.
- **`FxPaymentService`**: `plan` (the fitting deals, soonest value date first, each covering what it can; `use` limits them to the ticked ones), `convert` with a list of deal draws then a streamed price, firm quote or at market for the rest (unattended at-market applies the plan itself; screens send what the payer saw), `preview` (parts: each deal, then live or firm), `prepareBatch` (per entity and pair: the batch total covered by deals, ONE live deal for the remainder within the streaming limit, lines draw in order; a line not covered waits, with the reason). The payment's `fx_rate` is the deal's for one part, else blended (account currency per unit); `fx_deal_ref` is "FXD-1 +2" when several deals were used. Partly used forwards and spots keep their status (the rest settles through the SSIs on value date); windows go to PARTIALLY_DRAWN as before.
- **Channels**: single payment API, ISO single, payables (`fxDeals: [{dealId, amount}]`, `fxDealId` kept as shorthand); bulk ISO plans the batch in its own transaction before the rows (held rows come back as `AWAITING_FX` with the reason); file ingest PAYMENTS and PAYABLES plan the file, hold uncovered lines as `AWAITING_FX` (with the transformed line kept in `staged_transaction.held_row`), and `POST /ingest/jobs/{id}/release-fx` pays them once a deal covers them (planned again; still uncovered lines wait again). `GET /fx/payments/plan` for the screens.
- **Screens**: `PaymentFxPanel` (Transfers outward, Payables Pay now) lists the fitting deals ticked, soonest first, with what each covers and debits; untick any or "Rate only"; the rest at the live rate or a firm price. Transfers sidebar: one line per part. FX Deals: utilisation under the amount; drawer "Used by payments" (payment, date, bought, debited; used / left). Transactions: "Paid USD X at R · FXD-...". File ingest: "Waiting for FX" count and chip, notice with "Release lines".
- **Found and fixed (pre-existing)**: a window forward was booked with the dealer's pricing date as its value date (`FxQuoteService`): the window is priced at its worse end for the client, which can be its start, so a 30-day window could be booked ending the day it opened. It now keeps the end date asked for and is charged for that tenor. Window deals already booked may carry the short date.
- **Tests**: `FxPaymentServiceTest` (plan order and eligibility, unticking, combining with a live rest at the blended rate, unattended deals first, not covered, batch with one remainder deal, batch held above the limit), `FxSettlementServiceTest` (partial forward keeps ACTIVE, window PARTIALLY_DRAWN, payment recorded), `FxQuoteServiceTest` (window keeps its end date). Backend 485 passing. Live 23/23 (`dealfirst.py`): plan, preview, a spot and a not-yet-due forward combined, utilisation rows, at market deals first, window plus live rest, used-up deal refused, bulk with one remainder deal and rows in order, bulk above the limit held, file lines held then released after treasury books a firm spot. Screens checked in the browser: the panel (two deals, untick moves the amount to the live rate), the sidebar parts, the blotter line, the drawer section.

