# Sprint 1 — Cash Forecast Sign-off Demo

A 5-minute walkthrough of the Sprint 1 deliverable end-to-end: from clean docker boot to a treasurer clicking a trough week and tracing it back to specific seeded invoices.

---

## Prerequisites

- Docker Desktop 24+ running.
- Ports free locally: **5432** (Postgres), **8080** (backend), **3000** (frontend).
- `.env` present at repo root (copy from `.env.example` if needed).

---

## Step 1 — Stack up

From the project root:

```bash
docker-compose up --build -d
docker-compose logs -f backend | grep -E "Started|Flyway|forecast"
```

Wait for:
- `Flyway` log line confirming **V13 → forecasting schema** migrated.
- `Started VamServiceApplication in ...`.
- `DefaultForecastOrchestrator initialised with 3 engine(s): PATTERN, AGING, MANUAL` (proof Spring discovered all three engines).
- `ForecastScheduler nightly trigger registered` (proof the cron bean is alive).

Open Adminer at <http://localhost:8081> (Docker `tools` profile) or `psql` directly to confirm: `SELECT count(*) FROM forecast_category;` returns **12**.

---

## Step 2 — Load demo seed

The base seed (`seed_data.sql`) already creates the Desert Oasis demo corporate. The forecast-specific rows live in a separate file so they can be reloaded without touching the base seed.

```bash
docker exec -i vam-postgres psql -U vam_user -d vam_db < database/seed/seed_forecast_demo.sql
```

Expected tail of the output:

```
                what                 | n
-------------------------------------+---
 payables  (pattern: SALARY/TAX/...) | 7
 payables  (invoice / AGING bg)      | 12
 receivables (AR open/partial/...)   | 20
 forecast_run  (prior COMPLETED)     | 1
 forecast_adjustment (carry-forward) | 1
```

The seed is **idempotent** — re-running drops and re-creates only its own rows (no impact on the base seed).

---

## Step 3 — Fire the first forecast run via the API

```bash
curl -X POST http://localhost:8080/api/v1/forecasts/run \
     -H 'X-Corporate-Id: c1000001-0000-0000-0000-000000000001' \
     -H 'Content-Type: application/json' | jq
```

Expected response (under 5 seconds typical):

```json
{
  "success": true,
  "data": {
    "runId": "....",
    "status": "COMPLETED",
    "runAt": "2026-05-...",
    "horizonEnd": "2026-08-...",
    "generationMs": 1234
  }
}
```

What just happened under the hood:
1. Controller → `ForecastScheduler.triggerOnDemand()` → acquires per-corporate mutex.
2. `DefaultForecastOrchestrator.run()` writes a `RUNNING` row in a REQUIRES_NEW transaction.
3. The 3 engines fire (PATTERN, AGING, MANUAL).
4. ~30+ `forecast_line` rows persist in batches of 500.
5. Run transitions to `COMPLETED` with `generation_ms`.

Sanity check:
```sql
SELECT source, count(*) FROM forecast_line
 WHERE run_id = (SELECT id FROM forecast_run
                  WHERE created_by = 'system'
                  ORDER BY run_at DESC LIMIT 1)
 GROUP BY source;
```

Expected: `PATTERN ≈ 7`, `AGING ≈ 20`, `MANUAL = 1`.

---

## Step 4 — Open the Forecasting page

1. Open <http://localhost:3000>.
2. Pick the **Desert Oasis E-Commerce LLC** corporate from the entity picker if not already active.
3. In the left sidebar expand **Liquidity Management** → click **Cash Forecast**.

You should see:
- Header strip: horizon toggle (30d / **13w** / 12m), currency picker (AED), and **Run forecast now** button.
- 3 hero tiles: **Opening = AED 0** (Sprint-1 caveat), **Closing (13w)** signed (likely red — payroll + AP > AR), **Trough week** date and amount.
- Main chart: a `ComposedChart` with **green bars** (inflow weeks dominated by AR) and **red bars** (outflow weeks containing payroll/tax/rent), an **accent-gold cumulative balance line**, and a **red reference area** painting the weeks where the closing balance dips below 0.
- Category breakdown table below: PAYROLL row in red, AR_COLLECTIONS row in green, MANUAL_OTHER row with the +500k carry-forward.

📸 Take a screenshot of the chart for the demo deck.

---

## Step 5 — Click a trough week → trace the lines

1. Click any bar (suggest the **week containing the 25th of next month** — the payroll bar).
2. The **right-side drawer** slides in showing:
   - Week-of date + Net cashflow + Closing balance up top.
   - One line per `forecast_line` row in that week, **sorted by descending magnitude**.
   - Each row shows: category label, engine chip (`Pattern`/`AR aging`/`Manual`), date, currency, **`sourceRef`** like `payable:<uuid>`.
3. Confirm the payroll line traces back to a `SEED-FCAST-AP-SAL-*` payable id (visible in the `sourceRef`).

This is the "forecast → invoice provenance" loop that distinguishes Aperture from CashPro / DBS.

---

## Step 6 — Re-run from the UI

Click the **Run forecast now** button in the header strip. Expected:
- Spinner on the icon (`animate-spin`).
- Toast: **"Forecast generated in N ms"**.
- Chart refreshes with a new `runId`.

Now hit **Run forecast now** again immediately — you should see **`A forecast is already running for this corporate. Try again in a moment.`** (toast) if you catch the in-flight collision. Confirms the T8 scheduler mutex via the T9 controller's 409 path.

---

## Step 7 — Swagger UI walkthrough

Open <http://localhost:8080/api/swagger-ui.html>, expand the **Forecasts** tag, and demo the four endpoints:

| Endpoint | Try it with |
|---|---|
| `GET /v1/forecasts/latest` | `X-Corporate-Id` header = the Desert Oasis UUID |
| `GET /v1/forecasts/{runId}/lines` | The runId from Step 3 |
| `GET /v1/forecasts/{runId}` | Same runId |
| `POST /v1/forecasts/run` | `X-Corporate-Id` header |

The 409-on-concurrent-run, 404-on-unknown-runId, and ApiResponse envelope are all visible from Swagger.

---

## What this proves end-to-end

| Sprint 1 task | Demonstrated by |
|---|---|
| T1  V13 migration | Boot log + 12 categories present |
| T2  Entities + repos | Run persists; lines aggregate cleanly |
| T3  Engine contract | Startup log lists 3 engines via `List<ForecastEngine>` |
| T4  PatternEngine | `forecast_line` rows with `source=PATTERN`, refs to SALARY/TAX/UTILITY payables |
| T5  AgingEngine | `forecast_line` rows with `source=AGING`, refs to OPEN/PARTIAL/OVERDUE receivables, +7d shift visible on OVERDUE |
| T6  ManualOverlayEngine | `forecast_line` with `source=MANUAL` carrying the seeded +AED 500k adjustment |
| T7  Orchestrator + bootstrap | RUNNING → COMPLETED transition + per-engine isolation visible in logs |
| T8  Scheduler | Step 6 second-click 409 + nightly trigger log |
| T9  Controller | All 4 endpoints work from Swagger; envelope + error mapping correct |
| T10 forecastApi | Page hits the right URLs (Network tab) |
| T11 ForecastingPage | Steps 4–6 |
| T12 Nav wiring | Sidebar "Cash Forecast" item, breadcrumb, section theme |
| T13 Demo seed + smoke | This document |

---

## What's next (call out at the end of the demo)

**Sprint 2** — ML forecasting (Prophet sidecar), confidence bands, variance back-test, Copilot integration (`GetCashForecast`, `ExplainForecast`, `RunScenario`).

**Sprint 3** — Manual overlays UI, scenarios (Base / Upside / Downside), Excel round-trip, maker-checker on adjustment writes.

**Out of scope for Sprint 1** (don't volunteer unless asked):
- Opening balance — anchored at 0; needs the `BalanceAggregationService` wiring.
- AP aging — `AgingEngine` emits AR only; AP rows are background data.
- Per-counterparty / per-currency DSO — currently a fixed +7d shift on OVERDUE.
- RBAC enforcement on the forecast endpoints.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `404` on `GET /forecasts/latest` | No COMPLETED run exists yet | Step 3 first |
| Empty chart after a successful run | Demo seed not loaded | Re-run Step 2 |
| Categories breakdown empty | Seed run before V13 migrated | `docker-compose down -v && up --build -d`, repeat from Step 1 |
| 409 on every `POST /run` | A run is genuinely stuck `RUNNING` | `UPDATE forecast_run SET status='FAILED' WHERE status='RUNNING' AND run_at < now() - interval '10 minutes';` |
