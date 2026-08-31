# Treasurer's Morning Cockpit — V2 Roadmap

> **Status: BACKLOG** — parked 2026-05-14, not in active development. Pick up when V1 has stabilised in production and product/legal have answered the blockers in §9.

Five workstreams that move the cockpit from V1 (frontend-composed, console-stub audit, no notifications) to a production-ready surface. Each ships independently; sequencing in §6.

V1 reference: `tasks/todo.md` (Multi-Bank Liquidity Redesign and the cockpit build prompt at `Downloads/cockpit-build-prompt.md`).

---

## 0. Foundation we already have (reuse callout)

Before designing anything new, list what V1 + the existing platform already give us. Each row below is a piece V2 can lean on — building net-new for these would be wasted work.

| Building block                                | Where it lives                                                | V2 reuse                                                                  |
|-----------------------------------------------|---------------------------------------------------------------|---------------------------------------------------------------------------|
| `AttentionItem` / `TodayHorizon` types        | `frontend/src/types/cockpit.ts`                                | Backend DTOs are 1:1 mirrors. JSON wire format unchanged across V1 → V2.  |
| `cockpitApi` public surface                   | `frontend/src/services/cockpitApi.ts`                          | Method signatures stay; producer functions become thin fetch calls.       |
| `auditLog.record(...)` permanent signature    | `frontend/src/utils/auditLog.ts`                               | V2 just removes the `console.info` and the fire-and-forget POST hedge.    |
| Existing audit pipeline (Copilot)             | `backend/.../service/audit/` (see `tasks/ai-features-roadmap.md`) | Cockpit audit events plug into the same pipeline; new event names only. |
| `featureFlags.read(...)` localStorage helper  | `frontend/src/utils/featureFlags.ts`                           | V2 swaps storage for a `useConfig(flag)` hook; signature unchanged.       |
| `telemetry.emit(...)`                         | `frontend/src/utils/telemetry.ts`                              | Sink swap (console.info → real analytics) is a 1-file change.             |
| `apiClient` axios instance                    | `frontend/src/services/api.ts`                                 | All new endpoints use it for auth + error mapping.                        |
| Spring Boot package layout                    | `backend/.../controller`, `service`, `dto`, `entity`, `repository` | Each new feature is a 4-file slice (controller/service/dto/repository).   |
| Flyway migration sequence                     | `database/migrations/V2..V4`                                   | New migrations land as V5..V9 (one per workstream).                       |
| `BaseEntity` auditing (created_at/updated_at) | `backend/.../entity/BaseEntity`                                | All new tables inherit it for free.                                       |
| Existing `dashboardApi.getPendingApprovals()` etc. | `frontend/src/services/api.ts`                            | Backend producers compose from the same Spring services those endpoints use, not from REST callbacks. |

**Net effect**: most workstreams below are 6–15 files of net-new code, not greenfield.

---

# Workstream A — Backend `/cockpit/attention` and `/cockpit/horizon`

> **One-line pitch**: move composition off the client so producers can read what the client cannot (credit limits, FX rates, server-only joins), and so cross-user caching becomes possible.

### Why this is needed
- **Latency**: V1 makes 6+ parallel HTTP calls per cockpit load (one per producer). V2 is one round-trip.
- **Privilege**: producers like `funding_shortfall` need credit-line data the client should not pull directly.
- **Caching**: server can cache attention items for 60s across all users on the same entity scope; the client cannot.
- **Sorting + pagination**: V1 ships everything; V2 paginates if `>100` items, ranks centrally.
- **Multi-tenant**: V2 enforces entity-scope at the producer layer — V1 has no tenant guard.

### Architecture

```
Frontend                                 Backend
────────                                 ───────
cockpitApi.getAttentionItems(entityId)
        │
        ▼
GET /api/cockpit/v1/attention?entityId=…
        │
        ▼
                                   CockpitController
                                          │
                                          ▼
                                   CockpitService.getAttentionItems(entityId)
                                          │
                       ┌──────────────────┼──────────────────┐
                       ▼                  ▼                  ▼
              FundingShortfallProducer  StaleBalanceProducer  ...8 total
                       │                  │                  │
                       ▼                  ▼                  ▼
                    [existing repositories / services in com.bank.vam]
                       │                  │                  │
                       └──────────────────┼──────────────────┘
                                          ▼
                                   Sort + filter + cache (Redis 60s)
                                          │
                                          ▼
                                   List<AttentionItemDto>
```

### Files to create — backend (Java)

```
backend/src/main/java/com/bank/vam/cockpit/
├── CockpitController.java              # 4 endpoints (see contract below)
├── CockpitService.java                 # orchestrator (Promise.allSettled equivalent)
├── dto/
│   ├── AttentionItemDto.java           # 1:1 with frontend AttentionItem
│   ├── AttentionContextDto.java
│   ├── AttentionTimePressureDto.java
│   ├── AttentionActionDto.java
│   ├── TodayHorizonDto.java
│   ├── FxRateDisclosureDto.java
│   └── enums/{AttentionSeverity, AttentionCategory, AttentionTimePressureKind, AttentionActionKind}.java
├── producer/
│   ├── AttentionProducer.java          # interface — produce(entityId) → List
│   ├── FundingShortfallProducer.java
│   ├── SweepFailureProducer.java
│   ├── StuckTransactionProducer.java
│   ├── StaleBalanceProducer.java
│   ├── PendingApprovalProducer.java
│   ├── FxExposureProducer.java         # depends on Workstream A2 below
│   ├── ConcentrationRiskProducer.java  # depends on Workstream A3 below
│   └── LoanRolloverProducer.java
├── snooze/
│   ├── CockpitSnoozeEntity.java        # JPA entity
│   ├── CockpitSnoozeRepository.java    # JpaRepository<...>
│   └── CockpitSnoozeService.java
└── horizon/
    ├── HorizonComposer.java             # builds TodayHorizonDto
    └── FxRateService.java               # pluggable rate source (V2 stub: WMR static; V3: live)

backend/src/main/resources/db/migration/
└── V5__cockpit_snooze.sql               # cockpit_snooze table (see schema below)
```

### REST contract

| Method | Path                                                     | Body                          | Returns                       |
|--------|----------------------------------------------------------|-------------------------------|-------------------------------|
| GET    | `/cockpit/v1/attention?entityId={uuid}`                  | —                             | `AttentionItemDto[]`          |
| GET    | `/cockpit/v1/horizon?entityId={uuid}`                    | —                             | `TodayHorizonDto`             |
| POST   | `/cockpit/v1/attention/{itemId}/snooze`                  | `{reason, until}`             | `204 No Content`              |
| POST   | `/cockpit/v1/attention/{itemId}/actions/{actionId}`      | `{auditId, ...payload}`       | `{auditId}` (echoed)          |

### Schema — `V5__cockpit_snooze.sql`

```sql
CREATE TABLE cockpit_snooze (
  id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id       UUID NOT NULL,
  item_id       VARCHAR(256) NOT NULL,
  reason        TEXT,
  snoozed_until TIMESTAMP NOT NULL,
  created_at    TIMESTAMP NOT NULL DEFAULT now(),
  UNIQUE (user_id, item_id)
);
CREATE INDEX idx_cockpit_snooze_user_until ON cockpit_snooze (user_id, snoozed_until);
```

`item_id` is a string because V1 ids are composite (`stale_balance:vaId`, `pending_approval:netting:cycleId`). Keep that scheme; V2 may add a structured `category + reference` column later if helpful.

### Files to modify — frontend

`frontend/src/services/cockpitApi.ts`:
- Replace each `produceX(entityId)` private function with a single `apiClient.get('/cockpit/v1/attention')` call.
- Keep the FX disclosure stub as a final fallback when the backend hasn't shipped yet.
- **Cutover**: behind a `cockpit.api.v2` feature flag, default `off` until backend is live in each environment.

### V1 → V2 migration path

1. Backend ships `/cockpit/v1/attention` returning the **same JSON** the frontend was building. Validation: contract test that asserts `JSON.stringify(client.compose()) === serverResponse` for a known fixture.
2. Frontend gates the call behind `cockpit.api.v2 = on` in dev/staging only.
3. After 2 weeks of green telemetry, flip on for production.
4. Frontend producer functions deleted; only the orchestrator skeleton remains (now just an HTTP call).
5. Each backend producer can ship in any order — the orchestrator already mixes V1 client + V2 server during the overlap if needed (use `Promise.allSettled` over both sources, dedupe by `item.id`).

### Open decisions
- **Pagination**: do we need it for attention items, or is the natural cap (rarely >50) good enough? Recommendation: skip in V2; revisit if telemetry shows users with 100+ items.
- **Caching key**: `(userId, entityId, category)` — agreed?
- **Tenant guard**: enforce entity-scope at the controller (`@PreAuthorize`) or the producer? Recommendation: controller, so producers stay testable without security context.
- **Server-side time pressure**: do we render `displayText` server-side (locale-aware) or keep it client-side as today? Recommendation: server-side, with locale from the `Accept-Language` header.

---

# Workstream B — Real audit pipeline

> **One-line pitch**: replace the V1 fire-and-forget POST with a durable, queryable, hash-chained audit trail that satisfies banking compliance.

### Why this is needed
- V1 audit is fire-and-forget → drops on backend errors.
- No query API → compliance reports impossible.
- No tamper-evidence → not auditable in the regulatory sense.
- 7-year retention not yet wired.

### Architecture — reuse the Copilot audit pipeline

The existing Copilot prototype already lands an audit pipeline (`backend/.../service/audit/` per `tasks/ai-features-roadmap.md`). V2 cockpit audit plugs into the **same** pipeline — we just register new event names. No new infrastructure needed.

If the Copilot pipeline is sync-write to a `copilot_audit` table, we extend it to a generic `audit_event` table that all subsystems write to, distinguished by an `event_namespace` column.

### Files to create — backend

If the existing Copilot audit table is acceptable to extend (recommended):
```
backend/src/main/java/com/bank/vam/audit/
├── AuditEventNamespace.java        # enum: COPILOT, COCKPIT, ...
├── AuditEventService.java          # generalisation of the existing CopilotAuditService
├── AuditEventController.java       # POST /audit/v1/record (cockpit V1 already calls this)
└── AuditQueryController.java       # GET /audit/v1/query (compliance reports)

backend/src/main/resources/db/migration/
└── V6__audit_event.sql             # see schema below; if extending an existing table, this becomes ALTER
```

If we want a fresh, hash-chained table:
```sql
-- V6__audit_event.sql
CREATE TABLE audit_event (
  id              BIGSERIAL PRIMARY KEY,            -- monotonic, NOT a UUID (chain order matters)
  event_namespace VARCHAR(32) NOT NULL,             -- 'COCKPIT', 'COPILOT', etc.
  action          VARCHAR(128) NOT NULL,            -- 'cockpit.attention.action.executed'
  user_id         UUID NOT NULL,
  item_id         VARCHAR(256),
  action_id       VARCHAR(64),
  payload         JSONB,
  data_state_hash CHAR(64),                         -- SHA-256 of visible state when user clicked
  prev_hash       CHAR(64),                         -- previous row's row_hash, for the chain
  row_hash        CHAR(64) NOT NULL,                -- SHA-256(prev_hash || action || payload || timestamp)
  occurred_at     TIMESTAMP NOT NULL DEFAULT now(),
  audit_id        VARCHAR(64) NOT NULL UNIQUE       -- the client-generated correlation id
);
CREATE INDEX idx_audit_user_time   ON audit_event (user_id, occurred_at DESC);
CREATE INDEX idx_audit_namespace   ON audit_event (event_namespace, occurred_at DESC);
CREATE INDEX idx_audit_item        ON audit_event (item_id);
```

### Files to modify — frontend

`frontend/src/utils/auditLog.ts`:
- Remove the `console.info` line (or downgrade to `debug` and only in dev).
- Remove the fire-and-forget hedge — V2 service guarantees write or returns 5xx.
- Same return shape (`{auditId}`); no callsite changes anywhere.

### Open decisions
- **Hash chain**: per-user chain or global chain? Per-user is cheaper to query and verify; global is stricter for tamper evidence. Recommendation: per-user chain with a daily Merkle root commit to a separate immutable store (S3 + object lock).
- **PII redaction**: payload may contain account numbers, amounts. Where do we redact? Recommendation: at write time in `AuditEventService`, with a deny-list of fields (account_number, iban, beneficiary_name) — replace with a hash for correlation.
- **Retention**: 7 years bankwide-standard? Confirm with compliance. Recommendation: hot store 90 days (PostgreSQL), cold store 7 years (Parquet on S3 with monthly partitions).
- **Query API audience**: compliance officers only, or also tenant admins? Recommendation: scope to compliance + the user themselves (their own actions); deny everyone else by default.

---

# Workstream C — Notifications

> **One-line pitch**: tell the treasurer about critical attention items even when they're not on the cockpit, with sane defaults and per-category preferences.

### Why this is needed
- V1 inbox is pull-only; users miss things they don't open the app to see.
- Critical funding shortfalls deserve a push within seconds.
- Medium-severity items deserve a daily digest, not a stream of pings.

### Architecture

```
attention item created   ──►   NotificationRouter   ──►   ChannelAdapter
   (in CockpitService)              │                          │
                                    ├─► email-immediate         ▼
                                    ├─► push-immediate     SES / Web Push API
                                    └─► email-digest (cron)
```

### Default routing

| Severity / category                      | Default channel + cadence  |
|------------------------------------------|----------------------------|
| `critical` (any category)                | Push immediate + email immediate |
| `high` (funding_shortfall, sweep_failure)| Push immediate                   |
| `high` (other)                           | Email digest hourly              |
| `medium` (any)                           | Email digest daily 09:00 local   |
| `pending_approval` (any)                 | Email digest daily 09:00 local   |

User can override per category × channel.

### Files to create — backend

```
backend/src/main/java/com/bank/vam/notification/
├── NotificationController.java          # GET/PUT /notifications/preferences ; GET /notifications/log
├── NotificationService.java             # orchestrates routing + sending
├── NotificationRouter.java              # evaluates preferences + defaults
├── adapter/
│   ├── ChannelAdapter.java              # interface
│   ├── EmailChannelAdapter.java         # reuses existing email service (SES?)
│   └── WebPushChannelAdapter.java       # VAPID-based Web Push
├── digest/
│   └── DigestBuilderJob.java            # @Scheduled cron, runs hourly
├── entity/
│   ├── NotificationPreferenceEntity.java
│   └── NotificationLogEntity.java
└── repository/
    ├── NotificationPreferenceRepository.java
    └── NotificationLogRepository.java

backend/src/main/resources/db/migration/
└── V7__notification.sql
```

### Schema — `V7__notification.sql`

```sql
CREATE TABLE notification_preference (
  id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id         UUID NOT NULL,
  category        VARCHAR(64) NOT NULL,           -- 'funding_shortfall' | … | '__default__'
  channel         VARCHAR(32) NOT NULL,           -- 'email_immediate' | 'email_digest_daily' | 'push'
  enabled         BOOLEAN NOT NULL DEFAULT true,
  quiet_hours     JSONB,                           -- {start: "22:00", end: "07:00", tz: "Asia/Dubai"}
  created_at      TIMESTAMP NOT NULL DEFAULT now(),
  updated_at      TIMESTAMP NOT NULL DEFAULT now(),
  UNIQUE (user_id, category, channel)
);

CREATE TABLE notification_log (
  id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id         UUID NOT NULL,
  item_id         VARCHAR(256) NOT NULL,
  channel         VARCHAR(32) NOT NULL,
  status          VARCHAR(32) NOT NULL,           -- 'SENT' | 'FAILED' | 'SUPPRESSED_QUIET'
  error_message   TEXT,
  sent_at         TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_notification_log_user_sent ON notification_log (user_id, sent_at DESC);
```

### Files to create — frontend

```
frontend/src/pages/NotificationPreferencesPage.tsx     # /settings/notifications
frontend/src/components/cockpit/NotificationBadge.tsx  # bell icon in greeting strip with unread count
frontend/src/services/notificationApi.ts               # GET/PUT preferences
public/cockpit-sw.js                                   # service worker (Web Push subscription)
```

### Open decisions
- **Push channel**: Web Push only (browser), or also native iOS/Android via FCM? Recommendation: Web Push for V2 (no app store work); native deferred to V3.
- **Digest sender**: SES or SendGrid? Recommendation: reuse whatever the existing email service is — don't introduce a new dependency.
- **Per-tenant overrides**: can the tenant admin force "all critical → email" regardless of user prefs? Recommendation: yes, via a tenant-level template that takes precedence; user can opt out only of explicitly opt-out-able templates.
- **Quiet hours scope**: per-user, or per-tenant default with per-user override? Recommendation: tenant default, user can override.
- **Anti-spam**: how do we avoid pinging the same user 50 times when a sweep failure produces 50 stuck items? Recommendation: NotificationRouter dedupes within a 5-minute window by `(user_id, category, related_entity_id)`.

---

# Workstream D — Save-views

> **One-line pitch**: treasurers triage in different ways (by region, by entity, by category); let them save and share their view configuration.

### Why this is needed
- V1 has one filter dimension (severity OR grouping). Power users want compound filters: "critical + high, scoped to UAE entities, by bank".
- Pilot users have already asked: "Can I bookmark this view?"
- Sharing a view with a colleague is currently impossible (URL has only `?filter=`).

### Architecture

A view is a JSON blob describing the cockpit's filter + grouping + entity scope state. Views are per-user; some can be shared via a tokenised URL.

```
{
  "name": "UAE liquidity triage",
  "scope": { "entityId": "uuid-of-uae-entity-group" },
  "inboxFilter": { "severities": ["critical","high"], "categories": ["funding_shortfall","sweep_failure"] },
  "grouping": "by-bank",
  "horizonBaseCurrency": "AED"
}
```

### Files to create — backend

```
backend/src/main/java/com/bank/vam/cockpit/view/
├── CockpitViewController.java          # CRUD; share-token generation
├── CockpitViewService.java
├── CockpitViewEntity.java
└── CockpitViewRepository.java

backend/src/main/resources/db/migration/
└── V8__cockpit_view.sql
```

### Schema — `V8__cockpit_view.sql`

```sql
CREATE TABLE cockpit_view (
  id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id       UUID NOT NULL,
  name          VARCHAR(128) NOT NULL,
  config        JSONB NOT NULL,
  share_token   VARCHAR(64) UNIQUE,                  -- nullable; nullable => private
  share_scope   VARCHAR(16),                          -- 'PUBLIC_LINK' | 'TENANT_ONLY'
  is_default    BOOLEAN NOT NULL DEFAULT false,       -- one per user
  created_at    TIMESTAMP NOT NULL DEFAULT now(),
  updated_at    TIMESTAMP NOT NULL DEFAULT now(),
  UNIQUE (user_id, name)
);
CREATE INDEX idx_cockpit_view_user ON cockpit_view (user_id);
CREATE INDEX idx_cockpit_view_share ON cockpit_view (share_token) WHERE share_token IS NOT NULL;
```

### Files to create — frontend

```
frontend/src/components/cockpit/SaveViewDropdown.tsx     # in greeting strip; lists user's saved views + Save current
frontend/src/components/cockpit/ShareViewModal.tsx       # generate + copy share URL; revoke
frontend/src/services/cockpitViewApi.ts                  # CRUD wrapper
```

`CockpitPage.tsx` updates:
- Read `?view={token}` on mount → resolve via API → apply config (overrides URL params).
- "Save current view" writes the current state to a new `cockpit_view` row.
- The default view (if user has marked one) loads on cockpit open if no `?view=` param is present.

### Open decisions
- **View ownership**: per-user only, or per-team / per-tenant too? Recommendation: per-user only in V2; team/tenant scopes are V3.
- **Share semantics**: shared views are read-only for non-owners (can clone), or live-collaborative (changes propagate)? Recommendation: read-only (clone-to-edit pattern) — collaborative views are scope creep.
- **URL persistence**: keep `?filter=` working alongside `?view=`? Recommendation: yes, with `?view=` taking precedence (so old bookmarks still work).
- **Scope of `config` JSON**: just inbox filter, or also horizon base currency, also which bands are visible? Recommendation: include band visibility in V2 (some users want a tighter cockpit), but keep base currency on user profile (not per-view).

---

# Workstream E — AI suggestions

> **One-line pitch**: when the treasurer opens an attention row, surface a one-line AI-generated suggestion with a pre-filled action that they accept / edit / reject. Audit captures both the suggestion and the decision.

### Why this is needed
- The triage cost of "I see a $4.2M shortfall — where do I source it from?" is high. AI can pre-compute a candidate.
- Reduces mean-time-to-resolution for routine items (sweep this from there, refresh that, etc.).
- Audit captures the suggestion AND the user's decision, which is exactly what the regulator wants when there's an AI in the loop.

### Architecture — reuse the Copilot stack

The existing Copilot prototype already lands `CopilotTool`, `ToolRegistry`, `ActionExecutorService`, intent routing, audit pipeline, SSE streaming, action card lifecycle. **Cockpit suggestions reuse all of that.** We do not introduce a new model interface, action surface, or audit channel.

A suggestion is just a Copilot action card pre-resolved for a specific attention item:

```
AttentionItem ──► CockpitSuggestionService ──► CopilotIntent (existing)
   (in drawer)                                       │
                                                     ▼
                                            ActionExecutorService (existing)
                                                     │
                                                     ▼
                                            Audit pipeline (Workstream B)
```

### Files to create — backend

```
backend/src/main/java/com/bank/vam/cockpit/suggestion/
├── CockpitSuggestionController.java     # POST /cockpit/v1/attention/{itemId}/suggestions
├── CockpitSuggestionService.java        # builds Copilot prompt context, calls intent router
└── SuggestionPromptBuilder.java         # per-category prompt templates

backend/src/main/resources/db/migration/
└── V9__attention_suggestion_cache.sql
```

### Schema — `V9__attention_suggestion_cache.sql`

```sql
CREATE TABLE attention_suggestion_cache (
  id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  item_id         VARCHAR(256) NOT NULL,
  user_id         UUID NOT NULL,
  payload         JSONB NOT NULL,                   -- the suggestion + pre-filled action card
  confidence      NUMERIC(3,2),                      -- 0.00..1.00 from the model
  generated_at    TIMESTAMP NOT NULL DEFAULT now(),
  expires_at      TIMESTAMP NOT NULL,                -- TTL — refetched on expiry
  consumed_at     TIMESTAMP,                         -- when the user accepted/rejected
  decision        VARCHAR(16),                       -- 'ACCEPTED' | 'EDITED' | 'REJECTED'
  UNIQUE (item_id, user_id)
);
CREATE INDEX idx_suggestion_user_item ON attention_suggestion_cache (user_id, item_id);
```

### Per-category prompt templates

| Category              | Suggestion shape                                                                                       |
|-----------------------|--------------------------------------------------------------------------------------------------------|
| `funding_shortfall`   | "Sweep $X from Pool Y to cover this. Pool Y has $Z available; sweep is allowed by rule R."             |
| `sweep_failure`       | "Re-run with last successful parameters" or "Source error suggests {reason}; suggest amending {field}". |
| `stuck_transaction`   | "Likely cause: {pattern from history}. Suggested action: investigate or reverse."                      |
| `stale_balance`       | "Refresh now" (deterministic — no AI needed; skip suggestion).                                         |
| `pending_approval`    | "Last 3 approvals from this counterparty all approved within 2h. Recommend Approve."                   |
| `loan_rollover`       | "Roll at current market rate {rate}; tenor 30d aligns with policy."                                    |

V2 stub categories (`fx_exposure`, `concentration_risk`) defer suggestions until their producers ship.

### Files to create — frontend

```
frontend/src/components/cockpit/AttentionSuggestionCard.tsx   # slot inside AttentionDrawer
frontend/src/services/attentionSuggestionApi.ts               # GET /suggestions/{itemId}
```

`AttentionDrawer.tsx` updates:
- On open, fetch suggestion for the item (cache hit if generated within TTL).
- Render `<AttentionSuggestionCard />` between the time-pressure block and the audit-preview block.
- Card has Accept / Edit / Reject buttons; Accept routes to the existing action; Edit opens the inline edit form; Reject records the rejection in `attention_suggestion_cache.decision`.
- All three decisions write audit entries (`cockpit.suggestion.{accepted|edited|rejected}`).

### Open decisions — these are blockers
- **Model**: Claude Sonnet (cost-effective for short suggestions) or Opus (better reasoning for ambiguous cases)? Recommendation: Sonnet for V2; profile per-category whether Opus moves the needle.
- **Generation timing**: pre-generate when an attention item is created (background), or on-demand when the drawer opens? Recommendation: pre-generate for `critical` only (latency matters); on-demand for `high`/`medium` (cache TTL handles repeat opens).
- **Data the AI sees**: minimum needed to generate the suggestion. For funding shortfall: the item's context + the relevant pool balances + the sweep rules + nothing else (no other entities' data). RBAC enforcement happens before the prompt is built.
- **Confidence threshold**: hide suggestions below {0.6, 0.7, 0.8}? Recommendation: 0.7. Below that, show a softer "Needs human judgement" banner.
- **Liability**: who is responsible if the AI suggests a wrong sweep and the user accepts? Recommendation: the user — the Accept button writes an audit entry that captures the visible suggestion + the decision, identical to a manual action. Legal sign-off needed before V2 ships.

---

# 6. Sequencing

Two viable orderings depending on team capacity.

### Sequential (1 dev)

```
A. Backend endpoints  ──►  B. Audit pipeline  ──►  D. Save-views  ──►  C. Notifications  ──►  E. AI suggestions
   (3-4 weeks)               (1-2 weeks)            (1 week)            (3-4 weeks)            (4-6 weeks)
```

Why: Workstreams C and E both depend on A (backend has the source of truth) and B (audit captures their effects). D is independent and small; slot it wherever there's capacity.

### Parallel (3 devs)

```
Track 1 (backend-heavy):  A → E
Track 2 (infra-heavy):    B → C
Track 3 (small):          D
```

D ships first regardless (no blockers), giving pilot users an immediate win.

### Hard dependencies

| Workstream | Depends on                                                            |
|------------|-----------------------------------------------------------------------|
| A          | Nothing (V1 frontend continues to work as fallback)                   |
| B          | Nothing in V1; Workstream A's `executeAction` endpoint writes audit   |
| C          | A (server-side item creation triggers the router)                     |
| D          | Nothing                                                               |
| E          | A (suggestion needs item context from server) + B (audit decisions)   |

---

# 7. Cross-cutting concerns

These apply to every workstream above and should be designed in, not bolted on.

### Multi-tenant isolation
Every API call must scope by tenant. `CockpitController` reads tenant from JWT claim; producers receive a `TenantContext` and must include it in every repository query. **Reference test**: a request with tenant A's JWT must never see tenant B's data even when the entityId is left blank.

### RBAC
The cockpit is for treasurers. Sub-roles:
- **Read-only treasurer**: sees inbox + horizon; action buttons hidden.
- **Authorising treasurer**: sees + acts on items below the 2FA threshold.
- **Senior treasurer**: sees + acts on all items including > $5M (which still require 2FA).

Enforce at the controller via `@PreAuthorize("hasRole('TREASURER_AUTH')")` etc.

### Performance budget
- Cockpit page first-paint: ≤ 2s on a well-connected client (V1 currently runs ~3-5s due to client composition)
- `/cockpit/v1/attention` p95 latency: ≤ 800ms with cache hot
- `/cockpit/v1/horizon` p95 latency: ≤ 600ms

Caching: Redis with a 60s TTL keyed by `(userId, entityId)` for attention; 5min TTL for horizon; invalidate on snooze / executeAction.

### Telemetry uplift
V1 telemetry is `console.info`. V2 wires the `telemetry.emit(...)` sink to whatever analytics platform the org uses (Segment, Posthog, internal Kafka topic). This is a 1-file change in `frontend/src/utils/telemetry.ts` — not a workstream of its own, but should land alongside Workstream A for visibility into the rollout.

### Localization
Cockpit copy is currently English. The producer time-pressure strings (`"in 5h 28m"`) and category-specific headlines need translation tables. V2 ships server-side rendering of `displayText` based on `Accept-Language`; UI strings move to an i18n table. Defer to V3 if not a launch requirement.

---

# 8. What I'd do first if I were the lead

1. **Land Workstream A** at the same time as flipping `cockpit.api.v2 = on` in production. This proves the backend can replace client composition without surprises and gives every other workstream the source of truth they need.
2. **Land Workstream D** in parallel — small, low-risk, immediate user delight. Ships in a week.
3. **Land Workstream B**, but only after A is stable for two weeks. Audit changes are hard to roll back; let A's traffic patterns settle first.
4. **Land Workstream C** with conservative defaults (digest-only). Add critical-push after a month of telemetry shows users want it.
5. **Land Workstream E** last, with a launch flag (`cockpit.suggestions.enabled`) per-tenant. Roll out to 1 tenant for 2 weeks before broad availability.

---

# 9. Open product decisions blocking ship

These need product/compliance/legal answers before engineering can start the corresponding workstream:

| Decision                                           | Blocks                  | Owner            |
|----------------------------------------------------|-------------------------|------------------|
| Audit retention period (7y? 10y?)                  | B                       | Compliance       |
| PII redaction policy in audit payloads             | B                       | Compliance + Legal |
| Per-tenant notification template overrides         | C                       | Product          |
| Push channel scope (web only? mobile?)             | C                       | Product          |
| AI model + cost budget                             | E                       | Product + Finance |
| AI liability framing                               | E                       | Legal            |
| Confidence threshold + "Needs human judgement" UX  | E                       | Product + UX     |

---

# Appendix — File counts at a glance

| Workstream | Backend files | Frontend files | DB migrations | Total |
|------------|---------------|----------------|---------------|-------|
| A          | ~22           | 1 modified     | 1             | ~24   |
| B          | ~4            | 1 modified     | 1             | ~6    |
| C          | ~12           | 4              | 1             | ~17   |
| D          | ~4            | 3              | 1             | ~8    |
| E          | ~3            | 2              | 1             | ~6    |
| **Total**  | **~45**       | **~10**        | **5**         | **~60** |

Numbers are deliberately conservative; tests + integration glue typically add 30-50%.
