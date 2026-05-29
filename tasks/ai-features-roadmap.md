# AI Features Roadmap — Forecasting · Sweep Optimizer · Smart Reconciliation

Designs + actionable plans for three AI features in the VAM Portal, sequenced after Feature #1 (Treasury Copilot, shipped as stub).

> **Operating philosophy**: same stub-first pattern that worked for the Copilot. Each feature ships a *deterministic, demo-ready core* with a clear seam where a real model swaps in. No external LLM dependencies in the prototype. Every feature ends with a demo-ready slice and a real follow-up path.

---

## 0. Foundation we already have (reuse callout)

The Copilot prototype landed a stack of pieces that all three features lean on:

| Building block | Where it lives | Reuse for these features |
|---|---|---|
| `CopilotTool` interface + `ToolRegistry` | `ai/copilot/tools/` | Each feature exposes its capabilities as tools, instantly reachable from chat |
| Intent routing + slot extraction | `ai/copilot/intent/` | Add 4–6 new intents — one per feature's main verb |
| Action card lifecycle + `ActionExecutorService` | `ai/copilot/action/` | Promote-recommendation, accept-match, set-forecast-alert — same pattern |
| Audit pipeline (`COPILOT_ACTION_*`) | `service/audit/` | All write actions audited the same way |
| SSE streaming + drawer + markdown render | `ai/copilot/` (FE+BE) | Long-running batch jobs stream progress |
| Market profile + locale | `config/MarketProfileProperties` | All forecasts/optimisations/matches respect active currency |
| `BaseEntity` auditing | `entity/BaseEntity` | All new tables get `created_at`/`updated_at`/`version` for free |
| Headless UI Sheet + Recharts + Tailwind | `frontend/` | Pages mirror the design language already in the app |

**Net**: each new feature is a backend service package + a frontend page + 1–2 Copilot tools + 1–2 intents. Total file count per feature is in the 20–30 range, not 50+.

---

# Feature A — AI Cash Flow Forecasting

> **One-line pitch**: predict per-account balances 7 / 30 / 90 days out, with NSF risk score and human-readable drivers.

## A.1 Goal & success criteria

A treasurer opens an account detail (or the new Forecast page) and sees:
- A line chart of historical balance + forward forecast with P10 / P50 / P90 bands
- NSF risk score (0–100) for the next 30 days
- A "Drivers" panel — 3–5 plain-English bullet points ("Monthly payroll on day 25 typically debits AED 4.2M", "Inflow concentration on Mondays")
- Idle-cash flag if a structural surplus is detected

### Success criteria
- [ ] Forecasts available for any account with ≥ 14 days of transactions
- [ ] Forecast generation < 200ms per account (cached for 24h)
- [ ] NSF risk score has explained provenance (drivers list, not a black box)
- [ ] Same intent ("Forecast AED position") works from the Copilot drawer
- [ ] Graceful fallback for new accounts with insufficient history — "Need at least 14 days of activity to forecast" message
- [ ] Locale-aware currency, dark-mode parity, market-profile aware

### Non-goals (explicit)
- **No real ML in the prototype** — deterministic seasonal/trend model. Prophet/LightGBM swap is a v1 follow-up behind a config flag.
- No multi-currency conversion in the forecast (forecast each currency separately).
- No corporate-wide stress scenarios. Per-account or per-currency only.
- No automated remediation (recommending sweeps to fix NSF risk is the Optimizer's job).

## A.2 Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│  Frontend                                                           │
│   ForecastPage.tsx          — standalone page (sidebar entry)       │
│   ForecastTab on Account    — embedded tab on AccountDetail         │
│   ForecastChart.tsx         — Recharts area chart with P10/P50/P90  │
│   DriversPanel.tsx          — bullet-list of contributing factors   │
│   NsfRiskPill.tsx           — coloured pill with score              │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              ▼  /api/forecasts/{accountId}?horizonDays=30
┌─────────────────────────────────────────────────────────────────────┐
│  Backend  (com.bank.vam.forecasting)                                │
│                                                                     │
│   ForecastController        — GET endpoints                         │
│   ForecastService           — orchestrator: cache-or-compute        │
│                                                                     │
│   engine/                                                           │
│     ForecastEngine          — SPI                                   │
│     ├─ SeasonalForecastEngine    (stub default — Holt-Winters)      │
│     └─ ProphetForecastEngine     (v1 follow-up — Python sidecar)    │
│                                                                     │
│   features/                                                         │
│     FeatureExtractor        — turns history into model features     │
│     DriverExplainer         — feature attribution → English bullets │
│                                                                     │
│   ForecastSnapshot          — @Entity (account × horizon × computedAt)│
│   ForecastDriver            — @Entity (snapshot × feature × weight) │
│                                                                     │
│   ForecastBatchScheduler    — nightly job, refreshes top-N accounts │
│                                                                     │
│   tools/GetForecastTool     — Copilot integration                   │
└─────────────────────────────────────────────────────────────────────┘
```

### Model approach (stub — `SeasonalForecastEngine`)

For the prototype, no Python or ONNX. Pure-Java:

1. Pull the last N (default 180) days of daily closing balances for the account. If insufficient history, return `INSUFFICIENT_HISTORY`.
2. Decompose: trend (rolling linear regression over last 30 days) + weekly seasonality (mean by day-of-week) + monthly seasonality (mean by day-of-month for last 3 months).
3. Forecast: `trend(t) + weekly(t) + monthly(t)` for each day in horizon.
4. Uncertainty bands: residual stddev × {1.28, 1.0, 0.5} → P10/P50/P90 (rough but defensible for demo).
5. NSF risk: count of days in horizon where P10 < 0; normalise to 0–100.

This is ~150 LOC of statistical code, demo-defensible, and the seam where Prophet/LightGBM plugs in is the `ForecastEngine` interface.

### Driver attribution

Each forecast snapshot is annotated with `ForecastDriver` rows:
- `name` — "Weekly seasonality (Mon–Fri inflow)", "Monthly outflow on day 25", "Linear trend +0.4%/day"
- `weightPct` — share of the forecast magnitude this driver explains
- `direction` — IN / OUT

`DriverExplainer` runs after engine output: for each significant component (>5% magnitude), emit a bullet. ~100 LOC.

## A.3 Data model

```sql
forecast_snapshot
  id                 UUID PK
  va_id              UUID                           -- FK virtual_account
  horizon_days       INT     -- 7 / 30 / 90
  computed_at        TIMESTAMP
  expires_at         TIMESTAMP                       -- 24h cache TTL
  history_start      DATE
  history_end        DATE
  point_forecast     JSONB    -- array of [date, p50, p10, p90]
  nsf_risk_score     INT      -- 0..100
  status             VARCHAR  -- OK | INSUFFICIENT_HISTORY | FAILED
  engine_name        VARCHAR  -- "seasonal-v1" / "prophet-v1"

forecast_driver
  id                 UUID PK
  snapshot_id        UUID FK forecast_snapshot
  name               VARCHAR
  description        TEXT
  weight_pct         DECIMAL(5,2)
  direction          VARCHAR  -- IN | OUT
  rank               INT      -- ordering, 1 = most impactful
```

Both tables use `BaseEntity` (auditing comes free). Indexes on `(va_id, horizon_days, computed_at DESC)` for the latest-snapshot lookup.

## A.4 Phased delivery

### A.P1 — Schema + skeleton (1.5 days)
- [ ] A.P1.1 `ForecastSnapshot` + `ForecastDriver` entities, repos
- [ ] A.P1.2 `ForecastProperties` + `application.yml` (feature flag, default engine, cache TTL, batch top-N)
- [ ] A.P1.3 `ForecastEngine` SPI + `NoOpForecastEngine` stub returning canned data
- [ ] A.P1.4 `ForecastService.getOrCompute(vaId, horizonDays)` — cache-first, falls back to engine
- [ ] A.P1.5 `ForecastController` — `GET /api/forecasts/{vaId}?horizonDays=30`
- [ ] **Verify**: curl returns canned forecast JSON; row persisted

### A.P2 — Seasonal engine (3 days)
- [ ] A.P2.1 `FeatureExtractor` — pull daily closing balances from `transaction` table (or derive from `va.currentBalance` + transaction deltas)
- [ ] A.P2.2 Trend (rolling 30-day linear regression)
- [ ] A.P2.3 Weekly seasonality (mean residual by day-of-week)
- [ ] A.P2.4 Monthly seasonality (mean residual by day-of-month, last 3 months)
- [ ] A.P2.5 Uncertainty bands (residual stddev → P10/P50/P90)
- [ ] A.P2.6 NSF risk computation (P10 < 0 day-count → 0–100 score)
- [ ] A.P2.7 Unit test: synthetic seasonal data → engine recovers seasonality within ±10%
- [ ] **Verify**: forecast for a real seeded account produces a non-flat curve; chart it locally to eyeball

### A.P3 — Driver explanation (1.5 days)
- [ ] A.P3.1 `DriverExplainer` extracts ranked drivers from engine internals
- [ ] A.P3.2 Plain-English templates ("Monthly outflow on day 25 averages AED 4.2M")
- [ ] A.P3.3 Persisted `ForecastDriver` rows with `rank` ordering
- [ ] **Verify**: forecast endpoint now returns drivers list; copy reads naturally

### A.P4 — Frontend (3 days)
- [ ] A.P4.1 `ForecastPage.tsx` route + sidebar entry (lucide TrendingUp icon)
- [ ] A.P4.2 `ForecastChart.tsx` — Recharts area chart, history left of "now" line, forecast right
- [ ] A.P4.3 `DriversPanel.tsx` + `NsfRiskPill.tsx`
- [ ] A.P4.4 Account selector (drop-down) + horizon toggle (7 / 30 / 90)
- [ ] A.P4.5 Empty state + insufficient-history message
- [ ] A.P4.6 Dark mode parity
- [ ] **Verify**: page renders for 3 different accounts (one with strong seasonality, one with sparse data, one new account)

### A.P5 — Copilot integration (1 day)
- [ ] A.P5.1 New `Intent.FORECAST_BALANCE`
- [ ] A.P5.2 IntentRouter keywords ("forecast", "predict", "outlook", "next N days")
- [ ] A.P5.3 `GetForecastTool` wraps `ForecastService`
- [ ] A.P5.4 `IntentExecutor` + `ResponseComposer.composeForecast` — markdown with sparkline-ish ASCII or mini-table + NSF pill emoji
- [ ] A.P5.5 Suggested prompt: "Forecast our AED position next 30 days"
- [ ] **Verify**: chat → matches FORECAST_BALANCE → returns the same drivers + NSF score as the page

### A.P6 — Batch refresh + polish (1 day)
- [ ] A.P6.1 `ForecastBatchScheduler` — `@Scheduled(cron = ...)` refreshes top-N (default 50) accounts nightly
- [ ] A.P6.2 Telemetry: log batch duration, fail count, per-engine stats
- [ ] A.P6.3 Snapshot cleanup (TTL): expired snapshots are excluded from queries
- [ ] A.P6.4 README in `docs/forecasting.md`
- [ ] **Verify**: trigger batch manually via admin endpoint; verify snapshots refreshed

**Total: ~10 working days (~2 weeks calendar).**

## A.5 File scaffolding (concrete paths)

```
backend/src/main/java/com/bank/vam/forecasting/
├── ForecastController.java
├── ForecastService.java
├── ForecastProperties.java
├── ForecastBatchScheduler.java
├── entity/
│   ├── ForecastSnapshot.java
│   └── ForecastDriver.java
├── repository/
│   ├── ForecastSnapshotRepository.java
│   └── ForecastDriverRepository.java
├── engine/
│   ├── ForecastEngine.java               # SPI
│   ├── ForecastInput.java / ForecastOutput.java
│   ├── SeasonalForecastEngine.java       # default
│   └── NoOpForecastEngine.java           # for tests / cold-start
├── features/
│   ├── FeatureExtractor.java
│   └── DailyBalance.java
├── explain/
│   └── DriverExplainer.java
├── dto/
│   ├── ForecastResponse.java
│   ├── PointForecast.java
│   └── DriverDto.java
└── tools/
    └── GetForecastTool.java              # Copilot integration

frontend/src/
├── pages/ForecastPage.tsx
├── components/forecasting/
│   ├── ForecastChart.tsx
│   ├── DriversPanel.tsx
│   ├── NsfRiskPill.tsx
│   ├── HorizonToggle.tsx
│   └── AccountPicker.tsx
└── services/forecastingApi.ts

docs/forecasting.md
```

## A.6 Risks & mitigations

| Risk | Mitigation |
|---|---|
| Seasonal model produces unrealistic forecasts for spiky accounts | Bound P10/P90 to [0.3×median, 3×median] of history; fallback to flat-trend with wide bands |
| Insufficient history blocks demo | Pre-seed a synthetic daily-balance generator that backfills 180 days for empty accounts when `vam.forecasting.demo-backfill: true` |
| NSF risk feels arbitrary | Always show the days-below-zero count alongside the score so users can sanity-check |
| Performance: 50 accounts × 180 days = 9000 transactions to load every night | Materialise daily-closing-balances in a small table (`va_daily_balance`); compute incrementally |
| Forecast cache served stale | 24h TTL + cache key includes `transaction_count` so any new activity invalidates |

## A.7 Demo moment

Open `Forecasting` from sidebar → pick ENBD-AED-MASTER → see:
- History line for last 90 days (jagged), forecast bands fanning out over next 30
- NSF risk: 🟡 24/100 ("4 days in horizon where P10 < 0")
- Drivers: "Monthly payroll day 25: typical −AED 4.2M", "Weekly inflow Tue–Thu: typical +AED 1.8M each", "Trend −0.3%/day over last 30 days"
- Switch horizon to 90d → bands widen, more drivers appear
- Switch to a new empty account → "Need 14+ days of activity" message
- Open Copilot drawer → "Forecast our AED position next 30 days" → same data, condensed

---

# Feature B — AI Sweep & Pool Optimizer

> **One-line pitch**: analyse 90 days of historical positions, surface concrete rule changes with backtests and ROI estimates.

## B.1 Goal & success criteria

A treasurer opens the Optimizer and sees a list of **recommendation cards**, each with:
- A clear title ("Raise threshold on SR-12 from 100K to 250K")
- Estimated annual benefit ("Saves ~AED 8,400/yr in idle interest")
- The rationale (what data drove the recommendation)
- A "Backtest" button → side-by-side chart current vs proposed for last 90 days
- A "Promote to draft" button → creates a draft sweep rule pre-filled

### Success criteria
- [ ] At least 4 distinct recommendation types implemented (idle cash, churn, threshold tuning, pool restructure)
- [ ] Each recommendation has a measurable, displayable ROI in active market currency
- [ ] Backtest replays the proposed rule against real historical data and produces a comparable chart
- [ ] "Promote to draft" creates a real `SweepRule` row in DRAFT status, navigable from the sweep editor
- [ ] Recommendations refresh on a nightly cadence; on-demand refresh button in the UI
- [ ] Copilot intent surfaces top-N recommendations inline in chat

### Non-goals
- **No automatic application** — every promote is human-clicked. Auto-tune is a v1 follow-up.
- No optimisation across corporates — single-corporate scope.
- No FX-aware recommendations in v0 (assume single-currency per recommendation).

## B.2 Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│  Frontend                                                           │
│   OptimizerPage.tsx           — recommendation cards grid           │
│   RecommendationCard.tsx      — ROI chip + Backtest + Promote       │
│   BacktestModal.tsx           — current vs proposed chart           │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              ▼ /api/optimizer/recommendations
┌─────────────────────────────────────────────────────────────────────┐
│  Backend  (com.bank.vam.optimizer)                                  │
│                                                                     │
│   OptimizerController                                               │
│   OptimizerService             — scan + score + persist             │
│   BacktestService              — replay one proposed rule           │
│   PromoteService               — create DRAFT SweepRule             │
│                                                                     │
│   scanner/                                                          │
│     RecommendationScanner      — SPI                                │
│     ├─ IdleCashScanner                                              │
│     ├─ ChurnScanner                                                 │
│     ├─ ThresholdTuner                                               │
│     └─ PoolRestructureScanner                                       │
│                                                                     │
│   replay/                                                           │
│     SweepReplayEngine          — re-runs sweep logic on history     │
│     BalanceHistorySource                                            │
│                                                                     │
│   RuleRecommendation           — @Entity                            │
│   OptimizerBatchScheduler                                           │
│                                                                     │
│   tools/                                                            │
│     GetOptimizerRecommendationsTool                                 │
│     RunBacktestTool                                                 │
└─────────────────────────────────────────────────────────────────────┘
```

### Recommendation scanners (heuristics, no ML)

Each scanner is a `@Component RecommendationScanner` with `List<RuleRecommendation> scan(corporateId)`:

| Scanner | Detects | Example output |
|---|---|---|
| `IdleCashScanner` | Account stayed > threshold for ≥ 7 days with no outbound sweep | "Account VA-OPS-002 has held > AED 5M for 14 days. Proposed: zero-balance sweep to ENBD-AED-MASTER at 17:30. Est. AED 2,300/yr saved at 1.2% IHB rate." |
| `ChurnScanner` | Sweep rule fired > 4×/day with avg < AED 50K each | "Rule SR-12 churned 92 times last month at avg AED 12K. Proposed: raise threshold from 5K to 50K. Cuts executions ~80%, no NSF impact." |
| `ThresholdTuner` | Target balance is held perpetually below historical floor (over-funded) | "SR-21 holds target AED 500K but balance never dropped below 800K in 90 days. Proposed: lower target to 300K, freeing AED 200K avg." |
| `PoolRestructureScanner` | Currency outlier in a pool (e.g., a GBP-mostly pool with 1 EUR member) | "EUR-3 in pool POOL-A is the only EUR member; FX peg costs ~0.4%/yr. Proposed: move to POOL-EUR." |

Each scanner returns `RuleRecommendation` objects with:
- `kind` (one of the four above)
- `affectedRuleId` / `affectedAccountId` (foreign references)
- `proposedChange` (JSON snapshot — fields to change)
- `rationale` (English string, deterministic for stub; LLM-generated in v1)
- `estimatedBenefit` (BigDecimal, annualised)
- `benefitBasis` (e.g., `"IHB_INTEREST_AT_RATE"` — for audit)
- `status` (NEW / VIEWED / PROMOTED / DISMISSED)

### `SweepReplayEngine`

Given a proposed rule and a date range, simulate what the position would have been:

1. Load daily snapshots of source + target balances for the range (`va_daily_balance` materialised view).
2. For each day, apply the proposed rule's logic (threshold, target, frequency) and compute the "would-have-swept" amount.
3. Apply that delta cumulatively to both source and target, recomputing balances day-by-day.
4. Return two parallel series: "actual" balance and "proposed" balance.

For prototype, this is deterministic and works without ML. The math is straightforward; the engineering is the data plumbing (`va_daily_balance`).

## B.3 Data model

```sql
rule_recommendation
  id                  UUID PK
  corporate_id        UUID
  kind                VARCHAR  -- IDLE_CASH | CHURN | THRESHOLD_TUNE | POOL_RESTRUCTURE
  title               VARCHAR
  rationale           TEXT
  affected_rule_id    UUID FK sweep_rules (nullable — IDLE_CASH may target an account)
  affected_account_id UUID FK virtual_accounts (nullable)
  proposed_change     JSONB
  estimated_benefit   DECIMAL(19,4)
  benefit_currency    VARCHAR(3)
  benefit_basis       VARCHAR
  status              VARCHAR  -- NEW | VIEWED | PROMOTED | DISMISSED
  promoted_rule_id    UUID FK sweep_rules (set when status=PROMOTED)
  scanned_at          TIMESTAMP
  scan_window_start   DATE
  scan_window_end     DATE

va_daily_balance        -- materialised view, rebuilt nightly
  va_id        UUID
  business_date DATE
  closing_balance DECIMAL(19,4)
  inflow_total    DECIMAL(19,4)
  outflow_total   DECIMAL(19,4)
  PRIMARY KEY (va_id, business_date)
```

## B.4 Phased delivery

### B.P1 — Schema + skeleton (1 day)
- [ ] B.P1.1 `RuleRecommendation` entity + repo
- [ ] B.P1.2 `va_daily_balance` materialised view via JPA + `@Scheduled` rebuild
- [ ] B.P1.3 `OptimizerProperties` (feature flag, scan window default 90, top-N to surface)
- [ ] B.P1.4 `OptimizerController` skeleton — `GET /api/optimizer/recommendations`
- [ ] **Verify**: curl returns empty list; va_daily_balance populates from seeded transactions

### B.P2 — Scanners (3 days)
- [ ] B.P2.1 `RecommendationScanner` SPI + auto-collect via Spring
- [ ] B.P2.2 `IdleCashScanner` (read va_daily_balance, look for max-balance plateau)
- [ ] B.P2.3 `ChurnScanner` (read `sweep_execution` table, count/avg per rule)
- [ ] B.P2.4 `ThresholdTuner` (compare rule's target vs historical floor)
- [ ] B.P2.5 `PoolRestructureScanner` (read `pool_member`, detect currency outliers)
- [ ] B.P2.6 `OptimizerService.runFullScan(corporateId)` orchestrator
- [ ] B.P2.7 Unit tests per scanner with synthetic balance histories
- [ ] **Verify**: scan against seeded data produces ≥ 3 recommendations across kinds

### B.P3 — Backtest (2 days)
- [ ] B.P3.1 `SweepReplayEngine` — load history, apply proposed rule day-by-day
- [ ] B.P3.2 `BacktestService.run(recommendationId, windowDays)`
- [ ] B.P3.3 `POST /api/optimizer/backtest` endpoint returning two parallel series
- [ ] B.P3.4 `RunBacktestTool` for Copilot
- [ ] **Verify**: backtest of IdleCashScanner's top recommendation shows visibly lower source balance + higher target

### B.P4 — Promote (1 day)
- [ ] B.P4.1 `PromoteService.promote(recommendationId)` — creates DRAFT `SweepRule`
- [ ] B.P4.2 Updates `RuleRecommendation.status=PROMOTED`, sets `promoted_rule_id`
- [ ] B.P4.3 Audit hook (`COPILOT_RECOMMENDATION_PROMOTED`)
- [ ] B.P4.4 Wire as a write action via the Copilot's `ActionExecutorService` so it gets the Confirm-card UX for free
- [ ] **Verify**: promote → row in `sweep_rules` with status=DRAFT, navigable from existing rule editor

### B.P5 — Frontend (3 days)
- [ ] B.P5.1 `OptimizerPage.tsx` — list of cards grouped by kind
- [ ] B.P5.2 `RecommendationCard.tsx` with ROI chip, kind icon, rationale
- [ ] B.P5.3 `BacktestModal.tsx` — Recharts line chart, hover tooltips on date
- [ ] B.P5.4 Filter pills (NEW / VIEWED / PROMOTED / DISMISSED)
- [ ] B.P5.5 Refresh button → kicks `OptimizerBatchScheduler` job
- [ ] **Verify**: end-to-end click — recommendation → backtest → promote → toast → check rule editor

### B.P6 — Copilot intent + polish (1 day)
- [ ] B.P6.1 `Intent.OPTIMIZE_SWEEPS` ("optimise", "save on sweeps", "any recommendations?")
- [ ] B.P6.2 `GetOptimizerRecommendationsTool` → markdown table of top 3
- [ ] B.P6.3 Suggested prompt
- [ ] B.P6.4 `docs/sweep-optimizer.md`
- [ ] **Verify**: "any sweep recommendations?" returns the same top 3 as the page

**Total: ~11 working days (~2.5 weeks calendar).**

## B.5 File scaffolding

```
backend/src/main/java/com/bank/vam/optimizer/
├── OptimizerController.java
├── OptimizerService.java
├── BacktestService.java
├── PromoteService.java
├── OptimizerProperties.java
├── OptimizerBatchScheduler.java
├── entity/
│   ├── RuleRecommendation.java
│   └── VaDailyBalance.java
├── repository/
│   ├── RuleRecommendationRepository.java
│   └── VaDailyBalanceRepository.java
├── scanner/
│   ├── RecommendationScanner.java     # SPI
│   ├── IdleCashScanner.java
│   ├── ChurnScanner.java
│   ├── ThresholdTuner.java
│   └── PoolRestructureScanner.java
├── replay/
│   ├── SweepReplayEngine.java
│   └── BalanceHistorySource.java
├── dto/
│   ├── RecommendationDto.java
│   ├── BacktestRequest.java
│   ├── BacktestResponse.java
│   └── BalancePoint.java
└── tools/
    ├── GetOptimizerRecommendationsTool.java
    └── RunBacktestTool.java

frontend/src/
├── pages/OptimizerPage.tsx
├── components/optimizer/
│   ├── RecommendationCard.tsx
│   ├── BacktestModal.tsx
│   ├── BacktestChart.tsx
│   ├── KindBadge.tsx
│   └── BenefitChip.tsx
└── services/optimizerApi.ts

docs/sweep-optimizer.md
```

## B.6 Risks & mitigations

| Risk | Mitigation |
|---|---|
| Heuristic produces low-quality recommendations | Each scanner has a minimum-confidence threshold (e.g., IdleCash requires ≥ 7-day plateau and ≥ AED 1M idle); recommendations below threshold are dropped |
| `va_daily_balance` is expensive to maintain | Nightly rebuild from current `transaction` table; for prototype scale (thousands of rows) this is trivial |
| Backtest replay is wrong | Unit test the replay engine against a synthetic 30-day balance series with a known correct outcome |
| Promote creates a duplicate rule | Promote sets `affected_rule_id` if tuning an existing rule (clones into DRAFT for edit), or creates new if IdleCash recommends a brand-new rule |
| ROI estimate is suspect | Always show the `benefit_basis` ("IHB interest at 1.2% × avg idle of AED 192K × 365 days") so the math is auditable |

## B.7 Demo moment

Open `Optimizer` → see 6 cards:
1. **IDLE_CASH** — "Idle AED 5.2M for 14d on VA-OPS-002 — Save AED 2,300/yr" → Backtest → side-by-side chart shows source draining to 100K target every evening → Promote → toast → DRAFT rule appears in sweep editor
2. **CHURN** — "Rule SR-12 churned 92× last month — Raise threshold 5K→50K" → Backtest → execution count drops from 92 to 18 → Promote
3. **THRESHOLD_TUNE** — "SR-21 over-funded by avg AED 200K" → Promote
4. **POOL_RESTRUCTURE** — "EUR-3 is the only EUR member of POOL-A — Move to POOL-EUR" → Promote
…
Open Copilot → "Any optimisations?" → top 3 in markdown, links to the page.

---

# Feature C — Smart Reconciliation

> **One-line pitch**: match ISO 20022 statement lines to internal transactions and open invoices, with confidence-scored auto-posting.

## C.1 Goal & success criteria

A finance operator uploads a camt.053 statement (or it arrives via SFTP), and within seconds sees:
- **Auto-posted** lane (≥95% confidence) — already matched and posted
- **Review** lane (70–95%) — pre-suggested match, one-click accept
- **Investigation** lane (<70%) — no match, full UI to manually link

Each match has:
- Statement line on the left, suggested transaction/invoice on the right
- Confidence score + which strategy contributed (exact / fuzzy / LLM)
- Match rationale ("Amount and date matched within 1 day; remittance reference matches invoice INV-2024-117")
- Accept / Reject buttons

### Success criteria
- [ ] Auto-match rate ≥ 60% on seeded data with reasonable test statements
- [ ] False-positive rate < 1% in the auto-posted lane (verified by manual review of demo)
- [ ] Match latency < 100ms per line (deterministic strategies); LLM strategy is optional + async
- [ ] Accepting a Review-lane match in one click writes the reconciliation row + flips status
- [ ] Investigation lane has search/filter so operator can find a match manually
- [ ] Copilot intent surfaces last-batch summary

### Non-goals
- No statement parsing changes — reuses existing `Iso20022StatementService` ingestion
- No FX-aware matching in v0 (assume same currency)
- No multi-line splits in v0 (one statement line → one transaction)
- **No LLM in stub mode** — LLM strategy ships behind a config flag, returns "LLM disabled" gracefully

## C.2 Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│  Frontend                                                           │
│   ReconciliationPage.tsx     — three-lane UI                        │
│   StatementUpload.tsx        — drag-and-drop camt.053               │
│   MatchRow.tsx               — left/right side-by-side              │
│   AcceptRejectButtons.tsx                                           │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              ▼ /api/reconciliation/batches
┌─────────────────────────────────────────────────────────────────────┐
│  Backend  (com.bank.vam.reconciliation)                             │
│                                                                     │
│   ReconciliationController                                          │
│   ReconciliationService       — main matching pipeline              │
│   BatchOrchestrator           — runs strategies in order            │
│                                                                     │
│   strategy/                                                         │
│     MatchStrategy             — SPI (priority + threshold)          │
│     ├─ ExactMatchStrategy     (amount + ccy + date + reference)     │
│     ├─ FuzzyMatchStrategy     (amount range + Levenshtein on names) │
│     ├─ ReferenceRegexStrategy (extract "INV-XXXX" patterns)         │
│     └─ LlmMatchStrategy       (gated; v1 follow-up)                 │
│                                                                     │
│   features/                                                         │
│     CandidateFetcher          — load txns + invoices in date window │
│     SimilarityScorer          — amount, date, name, ref components  │
│                                                                     │
│   ReconciliationBatch         — @Entity                             │
│   ReconciliationMatch         — @Entity                             │
│                                                                     │
│   tools/                                                            │
│     GetReconciliationBatchTool                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### Match strategies (ordered, first to clear threshold wins)

| Strategy | Confidence | Logic |
|---|---|---|
| `ExactMatchStrategy` | 0.95–1.0 | Amount equal, currency equal, transaction date within ±1 day, reference number exact substring match |
| `ReferenceRegexStrategy` | 0.85–0.95 | Remittance info contains `INV-\d+` or `PO-\d+` matching an open invoice/PO; amount matches invoice within ±5% |
| `FuzzyMatchStrategy` | 0.70–0.85 | Amount within ±2%, date within ±3 days, Levenshtein distance < 5 on beneficiary name vs counterparty |
| `LlmMatchStrategy` | 0.60–0.95 | (v1 follow-up) Free-form remittance text → most-likely target via prompt |

A line that no strategy clears at ≥ 0.70 lands in the investigation lane with the top suggestion (if any) annotated.

### `SimilarityScorer`

Component-based scoring so `MatchStrategy`s can compose:
- `amountScore` — `1.0` if exact, `1 - |dx|/max(a,b)` otherwise
- `dateScore` — `1.0` if same day, `0.8` if ±1, `0.5` if ±3, `0` beyond
- `nameScore` — `1 - levenshtein/max_len`
- `referenceScore` — `1.0` if exact ref-in-ref, `0.7` if pattern match
- Final confidence = weighted sum (weights stored in `ReconciliationProperties`)

## C.3 Data model

```sql
reconciliation_batch
  id              UUID PK
  source          VARCHAR  -- camt053 | manual | sftp
  source_ref      VARCHAR  -- statement file id
  uploaded_at     TIMESTAMP
  uploaded_by     VARCHAR
  line_count      INT
  auto_matched    INT
  review_count    INT
  investigation_count INT
  status          VARCHAR  -- PROCESSING | COMPLETED | FAILED

reconciliation_match
  id              UUID PK
  batch_id        UUID FK
  statement_line_id UUID                 -- existing camt.053 entry id
  target_kind     VARCHAR  -- TRANSACTION | INVOICE | NONE
  target_id       UUID                   -- nullable
  confidence      DECIMAL(4,3)           -- 0..1
  strategy        VARCHAR
  rationale       TEXT
  status          VARCHAR  -- AUTO_POSTED | PENDING_REVIEW | INVESTIGATION | ACCEPTED | REJECTED
  accepted_at     TIMESTAMP
  accepted_by     VARCHAR
  rejected_at     TIMESTAMP
  rejection_reason TEXT
```

## C.4 Phased delivery

### C.P1 — Schema + skeleton (1 day)
- [ ] C.P1.1 `ReconciliationBatch` + `ReconciliationMatch` entities + repos
- [ ] C.P1.2 `ReconciliationProperties` (thresholds, weights, top-N suggestions in investigation)
- [ ] C.P1.3 `ReconciliationController` skeleton
- [ ] C.P1.4 `MatchStrategy` SPI + `NoOpStrategy`
- [ ] **Verify**: schema deploys; controller returns empty batch list

### C.P2 — Exact + reference-regex (2 days)
- [ ] C.P2.1 `CandidateFetcher` — loads transactions in ±3 day window + open invoices
- [ ] C.P2.2 `ExactMatchStrategy` (amount + ccy + date + ref)
- [ ] C.P2.3 `ReferenceRegexStrategy` (extract `INV-\d+`, `PO-\d+`)
- [ ] C.P2.4 `BatchOrchestrator.process(batchId)` — iterates strategies in priority order
- [ ] C.P2.5 Unit tests with synthetic statement+txn pairs
- [ ] **Verify**: synthetic batch of 10 lines → 8 auto-posted, 2 investigation

### C.P3 — Fuzzy match (2 days)
- [ ] C.P3.1 `SimilarityScorer` (amount, date, name, reference components)
- [ ] C.P3.2 `FuzzyMatchStrategy` with Levenshtein on names
- [ ] C.P3.3 Component weights configurable via `ReconciliationProperties`
- [ ] C.P3.4 Top-N candidate suggestions stored on investigation-lane matches
- [ ] **Verify**: fuzzy strategy moves at least 2 lines from Investigation → Review on tweaked synthetic data

### C.P4 — Accept / reject lifecycle (1.5 days)
- [ ] C.P4.1 `POST /api/reconciliation/matches/{id}/accept`
- [ ] C.P4.2 `POST /api/reconciliation/matches/{id}/reject` (with reason)
- [ ] C.P4.3 On accept: write the reconciliation link onto the statement-line + target row; mark match ACCEPTED
- [ ] C.P4.4 Audit hook (`RECONCILIATION_MATCH_ACCEPTED` / `REJECTED`)
- [ ] **Verify**: accept → link visible on both sides; reject → moves to investigation

### C.P5 — Frontend (3 days)
- [ ] C.P5.1 `ReconciliationPage.tsx` with three-lane layout
- [ ] C.P5.2 `MatchRow.tsx` — left (statement line) / right (suggested target) with diff-style highlights
- [ ] C.P5.3 Accept / Reject buttons with optimistic UI updates
- [ ] C.P5.4 Investigation-lane manual search (transaction id, amount range, date)
- [ ] C.P5.5 `StatementUpload.tsx` — drag-and-drop (reuses existing camt.053 upload endpoint)
- [ ] C.P5.6 Dark-mode parity
- [ ] **Verify**: upload demo camt.053 → see lanes populate → accept 5 review-lane matches → see auto-posted count go up

### C.P6 — Copilot intent + polish (1 day)
- [ ] C.P6.1 `Intent.RECONCILE_LATEST` ("how did the last reconciliation go?")
- [ ] C.P6.2 `GetReconciliationBatchTool` returning last batch's stats
- [ ] C.P6.3 Markdown summary in chat: counts, top 3 still in review, oldest investigation item
- [ ] C.P6.4 `docs/reconciliation.md`
- [ ] **Verify**: chat → "any reconciliation pending?" returns matching summary

**Total: ~10.5 working days (~2 weeks calendar).**

## C.5 File scaffolding

```
backend/src/main/java/com/bank/vam/reconciliation/
├── ReconciliationController.java
├── ReconciliationService.java
├── BatchOrchestrator.java
├── ReconciliationProperties.java
├── entity/
│   ├── ReconciliationBatch.java
│   └── ReconciliationMatch.java
├── repository/
│   ├── ReconciliationBatchRepository.java
│   └── ReconciliationMatchRepository.java
├── strategy/
│   ├── MatchStrategy.java                 # SPI
│   ├── MatchCandidate.java
│   ├── ExactMatchStrategy.java
│   ├── ReferenceRegexStrategy.java
│   ├── FuzzyMatchStrategy.java
│   └── LlmMatchStrategy.java              # gated, v1 follow-up
├── features/
│   ├── CandidateFetcher.java
│   └── SimilarityScorer.java
├── dto/
│   ├── BatchSummaryDto.java
│   ├── MatchRowDto.java
│   └── AcceptRejectRequest.java
└── tools/
    └── GetReconciliationBatchTool.java

frontend/src/
├── pages/ReconciliationPage.tsx
├── components/reconciliation/
│   ├── LaneColumn.tsx
│   ├── MatchRow.tsx
│   ├── ConfidenceBadge.tsx
│   ├── StatementUpload.tsx
│   └── ManualSearch.tsx
└── services/reconciliationApi.ts

docs/reconciliation.md
```

## C.6 Risks & mitigations

| Risk | Mitigation |
|---|---|
| Auto-post writes a wrong link (false positive) | Require ALL components (amount, date, ref) to score perfectly for ≥0.95 confidence; never auto-post on fuzzy/LLM strategies |
| Reference regex too liberal (matches "INV-1" against "INV-100") | Use `\bINV-\d{3,}\b` (3+ digit refs) and exact-substring not partial; collect false-positive examples in tests |
| Levenshtein distance for long beneficiary names is slow | Cap candidate set to ±3 day window; pre-tokenise names; for prototype scale this is fast enough |
| Accept is destructive (writes both sides) | Audit log captures full snapshot; accept is reversible by reverting the link |
| LLM strategy adds non-determinism in demos | Hard-disabled by default; flag-gated under `vam.reconciliation.llm.enabled=false` |

## C.7 Demo moment

Upload `demo-statement.camt053.xml` (80 lines, seeded fixture) → in <2s see:
- **Auto-posted: 52** (green chip)
- **Review queue: 18** (amber) → click first one — side-by-side, both 4,250.00 AED on 2026-04-12 — rationale "Amount match, date match within 1 day, name 'ACME TRDG' fuzzy-matches counterparty 'Acme Trading LLC' (Levenshtein 0.85)" → click Accept → row leaves the lane, counter ticks
- **Investigation: 10** (grey) → click first one — top suggestion AMT off by 2.3% — operator opens manual search, finds the right txn, links
- Open Copilot → "How did the last reconciliation go?" → summary table with the same stats

---

# 4. Sequencing & dependencies

## Build order recommendation

```
Optimizer  →  Forecasting  →  Reconciliation
  (B)            (A)              (C)
```

Why this order:
1. **Optimizer first**: directly monetises the sweep architecture you just built in v1/v2a. Clearest ROI story for a 5-minute demo. Most reuse of existing Copilot framework (tools + action cards + audit). ~11 days.
2. **Forecasting second**: depends on `va_daily_balance` materialised view, which Optimizer also needs — building Optimizer first means Forecasting inherits that infra. ~10 days.
3. **Reconciliation third**: largest UX surface (three-lane page is meatier than the others) and benefits from operator feedback on the first two before tackling the most operationally sensitive flow. ~10.5 days.

**Total ~32 working days (~6.5 weeks calendar)** for all three behind a stub-first, demo-ready baseline. A real LLM in any of them is a separate follow-up of ~2–4 days per feature.

## Parallelisable cuts

Two of the three can run in parallel once the foundation is shared:
- After `va_daily_balance` lands (B.P1 or A.P2), Optimizer and Forecasting can fork
- Reconciliation has no shared infra with the other two — can start any time

If you have two engineers: B + C in parallel (~2 weeks), then A on top of B's `va_daily_balance` (~2 more weeks). Total: ~4 weeks instead of 6.5.

## Dependencies map

| What | Depends on | Notes |
|---|---|---|
| `va_daily_balance` | (none) | First thing to build — Optimizer and Forecasting both need it |
| `ForecastEngine` SPI | `va_daily_balance` | |
| `SweepReplayEngine` | `va_daily_balance` + existing `SweepRule` | |
| Reconciliation match | existing camt.053 ingestion + transaction table | already in place |
| Copilot intents for all three | existing `IntentRouter`, `IntentExecutor`, `ResponseComposer` | one new enum value + one new compose method per feature |
| Promote-to-DRAFT (Optimizer) | existing Copilot `ActionExecutorService` | reuses action-card lifecycle |

---

# 5. Open questions (resolve before starting any of them)

1. **`va_daily_balance` regen strategy**: nightly rebuild from scratch, or incremental on each transaction? Recommend **nightly full** for prototype simplicity. Trade-off: stale during the day. Acceptable.
2. **Forecasting demo data**: do we backfill synthetic balance history for accounts with < 14 days of activity, or just show the "insufficient history" empty state? Recommend **backfill** behind `vam.forecasting.demo-backfill: true` so the demo always lands on a populated chart.
3. **Optimizer scope**: corporate-wide or per-rule? Recommend **corporate-wide scan** with grouping by rule/account in the UI.
4. **Reconciliation upload**: reuse existing camt.053 endpoint or add a new "reconcile mode" upload? Recommend **new endpoint** (`POST /api/reconciliation/upload`) that internally calls the existing parser, then kicks off `BatchOrchestrator`. Keeps statement-only ingestion path clean.
5. **LLM strategy in Reconciliation**: gate it now (stub returns "disabled") or defer entirely? Recommend **gate now** — the seam being in place is cheap, swapping in a real Anthropic call is a one-class change.
6. **Recommendation TTL**: stale recommendations should expire. After 7 days? After underlying rule changes? Recommend **TTL of 14 days** + invalidate when the affected rule is edited.
7. **Auto-post threshold for Reconciliation**: 0.95? 0.98? Demo with 0.95 to populate the lane. Tighten before any real-money posting.

---

# 6. Reuse cheatsheet (what NOT to rebuild)

For every one of the three features:
- **DO** add tools as `@Component implements CopilotTool` — the registry auto-discovers
- **DO** use `IntentRouter` + `IntentExecutor` + `ResponseComposer` for chat surface
- **DO** route write actions through `ActionExecutorService` for the Confirm/Cancel + audit pattern
- **DO** persist with `extends BaseEntity` for free auditing fields
- **DO** read locale + currency from `MarketProfileProperties.getDefaultCurrency()` / `getDefaultLocale()`
- **DO** use the deterministic `DecimalFormat("#,##0.00", Locale.US)` pattern lifted from `ResponseComposer.formatCurrency`
- **DO** ship a `docs/<feature>.md` mirroring `docs/copilot.md`'s 10-section layout
- **DO** write per-feature smoke scripts in `backend/target/<feature>-smoke.py` mirroring `copilot-smoke.py`

**DON'T** reinvent: tool registration, intent routing, action-card UX, audit emission, currency formatting, dark-mode styling, SSE streaming, conversation persistence.

---

# 7. Status

- [x] Copilot prototype (Feature #1) — shipped, P1–P6 complete, see `tasks/ai-prototype.md`
- [ ] Optimizer (Feature B) — design above, not started
- [ ] Forecasting (Feature A) — design above, not started
- [ ] Reconciliation (Feature C) — design above, not started

Next decision: pick one of the three (recommend Optimizer first per §4 sequencing) and turn its phase plan into actionable todos.
