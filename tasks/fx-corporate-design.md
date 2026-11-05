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

## Open risks / questions to revisit

- Forward points from a simple interest-differential table are illustrative only; fine for a demo, not for pricing real trades.
- Settlement of a forward on value date needs a scheduler and cut-off handling (`CurrencyCutoffConfig` exists, reuse).
- Hedge accounting (IFRS 9 effectiveness, revaluation P&L) is out of scope but the deal model should keep fields to add it.
- Regulatory framing (mock demo vs live dealing licence, EMIR/Dodd-Frank-style reporting, local market rules per `MarketProfile`) deferred until the live-rates decision.
- Maker-checker for corporate users was deferred; for a bank it is usually a day-one requirement for dealing.
