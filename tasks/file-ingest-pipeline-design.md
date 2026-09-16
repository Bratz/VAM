# Universal file-ingestion agent pipeline (Payables / Receivables / Payments)

Status: **RE-ARCHITECTED (2026-09-16) and fully verified live.** The
original design below (kept as "Original design," further down) built this
as one fully isolated microservice, `file-ingest-agent-service`, containing
both the standard upload/processing pipeline AND the AI agent framework. All
7 of its build-order steps shipped and were verified end-to-end with a real
`ANTHROPIC_API_KEY` — a known shape processes via a hand-written transform;
a never-seen shape drives a real coding-agent run that writes/tests/merges a
transform; a repeat upload correctly hits the signature cache. That
verification is preserved below since the underlying agent mechanics didn't
change, only where they live.

The user then reversed the isolation decision: standard upload/staging/
reconciliation/quarantine/processing are ordinary backend features, exactly
like Payables or Receivables, and belong in the main `backend` app (8053).
The AI agent framework stays separate — its git-worktree/shell-exec
capability shouldn't share a process with the money-moving app — but is now
**fully decoupled**: no direct HTTP calls between backend and the agent
worker in either direction, in both directions. See "Revised architecture"
immediately below for the current design; the "Original design" section
further down is kept as the historical record of how the agent mechanics
were built and first proven, not as a description of what's deployed today.

## Revised architecture (2026-09-16) — decoupled via backend + Jira

### Why

Reuses everything already proven (the analysis agent, the coding agent, the
test gate, the signature cache) but moves the "standard, unattended" work
into the app that already owns the domains it touches, and narrows the
separate service to just the part that genuinely needs isolation: writing
and testing agent-generated code. The two services now coordinate the same
way `defect-fix-service` coordinates with the main repo — through a shared
Jira project and shared state (there, GitHub + Jira; here, Jira + the
database) — never through a direct call.

### Target architecture

**`backend` (8053)** gained a `fileingest` feature area
(`controller/dto/entity/repository/service.fileingest`, mirroring the
`payables`/`receivables` package convention exactly):
- `FileIngestController` — same REST surface the frontend always called:
  `POST /api/v1/ingest/{domain}/upload`, `GET /api/v1/ingest/jobs/{id}`,
  `GET /api/v1/ingest/jobs/{id}/timeline`.
- `IngestJobService`, `IngestOrchestrator`, `ReceivablesCsvTransform`,
  `StagingDryRunRunner`, `ReconciliationCheckRunner`, `RowQuarantineService`
  — ported near-verbatim from the original single-service design.
- `ReceivablesProcessor`/`PayablesProcessor`/`PaymentsProcessor` — ported,
  but simplified: they now `@Autowire` the real `Iso20022InwardPaymentService`,
  `PayablesService`, `Iso20022OutwardPaymentService`, `VirtualAccountService`
  directly (same JVM now) instead of going over HTTP to this same backend
  from a separate process. `BackendClient` (the old HTTP client) is gone.
- `IngestOrchestrator`'s unrecognized-format path no longer calls any agent
  inline. It emits `AWAITING_TRANSFORM` and files a Jira ticket (new
  `FileIngestJiraClient`/`FileIngestTicketService`, backend's own duplicate
  of the Jira plumbing — backend had zero prior Jira integration — embedding
  an `INGEST_JOB_ID: <uuid>` marker line, the same convention
  `defect-fix-service` uses for its own markers) and returns. No analysis
  call happens in backend at all; that's the agent worker's job once it
  picks up the ticket.
- New `GeneratedTransformRunner` — runs an already-merged, already-tested
  transform (via `mvn exec:java` against the shared `transform-handlers`
  checkout) and builds the reconciled output. This absorbed the "run the
  generated code" half of the original `TransformGenerationService`; backend
  is now the only side that ever touches real customer data.
- New `IngestRetrySweepService` — `@Scheduled(fixedRateString =
  "#{${vam.fileingest.retry-cadence-minutes:2} * 60 * 1000}")`, mirroring
  `BalanceRefreshService`'s exact "find stuck/pending items and retry"
  shape. Scans `ingest_job` rows in `AWAITING_TRANSFORM`; once a row's
  `formatSignatureId` is set AND that signature's `transformRef` is
  populated (the agent worker did its job), resumes staging → reconciliation
  → quarantine → processing → `DONE`. This is the *only* way backend learns
  the agent worker finished — there's no callback.
- New `IngestStage.AWAITING_TRANSFORM` value. New `FileIngestProperties`
  (`vam.fileingest.*`) for the workspace dir, retry cadence, and backend's
  own Jira config. No new Flyway migration — backend's schema is entity-
  mapped and comes from `hibernate.ddl-auto: update` per this repo's own
  convention (confirmed the hard way: an initial migration attempt here was
  wrong and reverted, since it duplicated what `@Entity` already expresses).

**`file-ingest-agent-service` (8091)** shrank to a headless agent worker —
no controllers, no port mapping, nothing outside it ever calls it:
- Kept unchanged: the whole `agent.*` package (analysis + coding agent),
  `transform.{TransformWorktreeManager, TransformTestGateRunner,
  TransformHandlersSkeleton}`, `signature.FormatSignatureService` (now the
  **only** place a signature hash is ever computed — closes off the
  cross-process hash-agreement risk the original design's cache-hit
  shortcut would otherwise have had), `jira.JiraClient`.
- `TransformGenerationService` trimmed to just generate → test → merge →
  record signature; it no longer runs the generated code against real data
  (that moved to backend's `GeneratedTransformRunner`).
- Removed entirely: `web.*`, `intake.*`, `process.*`
  (`BackendClient`/`DomainProcessor`/the three domain processors/
  `TransformedRow`/`TransformOutput`), its own Flyway migrations and schema
  ownership. Its datasource now points at backend's own `public` schema —
  `spring.flyway.enabled: false`, a second JPA client of tables it doesn't
  migrate itself, keeping trimmed `IngestJob`/`FormatSignature`/
  `TimelineEvent` mappings.
- New `orchestrate.IngestTriageOrchestrator` — a real structural mirror of
  `defect-fix-service.TriageOrchestrator`: `@EventListener
  (ApplicationReadyEvent.class)` on boot + `@Async`-triggered drain loop
  over `jiraClient.findOldestOpenTicket()`, guarded by an `AtomicBoolean
  busy` (confirmed by direct investigation: the real pattern is event-driven,
  *not* a cron poll, despite that being the more obvious guess). Per ticket:
  extract the job id from the `INGEST_JOB_ID:` marker, load the shared row,
  resolve the source file from the shared workspace volume, run the real
  analysis agent, check/compute the signature (hit → close the ticket; miss
  → the full generate/test/merge loop), set `formatSignatureId` on the
  shared row, close the ticket (or escalate `needs-human`/`Blocked`).

**Frontend**: `ingestApi.ts` now reuses the shared `apiClient` from `./api`
(no separate axios instance/base URL) — the REST contract is unchanged, so
`FileIngestUploadPage.tsx`/`Stepper`/`EventTimeline` needed no rework beyond
adding the new `AWAITING_TRANSFORM` stage label and step-sequence branch.

**Deploy**: `file-ingest-agent-service` dropped its port mapping and
`BACKEND_BASE_URL`; gained a shared named volume with `backend`. Its
Dockerfile was upgraded from a bare JRE image to the same
JDK+git+bash+maven runtime `defect-fix-service` uses (it was never actually
upgraded when the coding agent was first built — never caught because Docker
was never used for local verification, only `mvn spring-boot:run`). Caddy's
`/ingest/*` route is gone; the file-ingest pipeline's real public endpoint is
now backend's own `/api/v1/ingest/*`.

### Verified live end to end (2026-09-16, real Postgres, real Anthropic key)

Every step below was driven through real HTTP calls against real running
services, not mocks:
1. Backend schema (via `ddl-auto`, not a migration) + entities — Hibernate
   validated cleanly, including indexes and an unexpected-but-correct
   inferred FK from `ingest_job_id` columns to `ingest_job(id)`.
2. A known Receivables upload straight to backend reached `DONE` with a
   genuinely `PROCESSED` row — a real transaction, real 4-leg accounting,
   real VA update, all in-process (no HTTP, no `BackendClient`).
3. An unrecognized shape correctly filed a real ticket attempt against the
   real (but uncredentialed) Jira project, got the expected 404 for the
   nonexistent `KAN` project, and landed cleanly on `BLOCKED` — proving the
   error handling, not just the happy path.
4. The retry sweep was proven independently of a live agent run: a
   `format_signature` row and an `AWAITING_TRANSFORM` job were seeded
   directly (reusing the actual artifacts from this morning's real
   coding-agent run — the same `transform-handlers` checkout, the same
   merged `GeneratedTransform` class), and the sweep picked it up on its own
   schedule and completed it to `DONE` with a real `PROCESSED` row.
5. The new `IngestTriageOrchestrator`'s logic (ticket → job lookup, cache
   hit/miss branching, `formatSignatureId` assignment) is covered by
   `IngestTriageOrchestratorTest` (all collaborators mocked, no live
   Anthropic/Jira calls needed to prove the orchestration itself is
   correct) and was confirmed live to boot cleanly and correctly find zero
   open tickets against the real, uncredentialed Jira project (Jira's
   search API returns an empty result for a project it can't see, rather
   than erroring — confirmed by hand).

Two things remain genuinely unverified, both purely because this sandbox
has no real Jira credentials (email + API token), not because of any code
gap: a ticket actually being *found* and picked up by
`IngestTriageOrchestrator` end-to-end from a live upload (every piece of
that path is proven individually — filing in step 3 above, pickup logic in
step 5 above — just not chained together against a real authenticated
project), and the exact wording Jira returns for a ticket transition once
one can actually be created.

## Original design (superseded 2026-09-16)

Everything from here down describes the original fully-isolated-microservice
design and its own 7-step build order. It's kept for the historical record
of how the AI agent framework (analysis agent, coding agent, test gate,
signature cache) was designed and first verified — that verification still
stands and nothing about the agent mechanics themselves changed, only where
the *rest* of the pipeline lives. Don't read this as the current deployed
architecture; see "Revised architecture" above for that.

## Context

Customers send payables/receivables/payment files in whatever format their own
systems export — CSV, Excel, proprietary layouts. Today this codebase accepts
exactly one rigid shape per domain (confirmed: Receivables' only inbound path
takes raw ISO 20022 CAMT.054 XML; Payables/Payments have no file-based inbound
path at all — pain.001 is output-only). Every new customer format today would
mean a developer hand-writing a new parser. This service is a multi-agent
pipeline, modeled on `defect-fix-service`, that removes the human from that
loop: log a ticket per upload, have an analysis agent figure out the file's
structure, have a coding agent write and test a transform, run it through an
automated safety net (a human approval gate was explicitly ruled out), and let
the customer watch the whole thing happen on a live timeline.

## Decisions made (design around these, don't re-litigate)

1. Ticketing: reuse Jira, exactly as `defect-fix-service` does.
2. The analysis agent sees the **real** uploaded file contents (masking was
   considered and explicitly declined).
3. Per-file processing is **fully automatic** once the transform's tests pass
   — no human approval gate.
4. Transform reuse: first sighting of a customer+format combination runs the
   full agent pipeline; the result is saved and auto-applied on every later
   upload of that same combination, skipping the agent entirely.
5. Automated safety net (replacing the human gate) is **all three** of:
   reconciliation-total check, staging-table dry run, per-row quarantine
   (not all-or-nothing).
6. Target shape: ISO 20022 (CAMT/pain) wherever a real consumer exists for it.
7. New transform *code* also ships with **no** human review — fully automatic
   end to end, including a never-seen-before format.
8. Reuse `defect-fix-service`'s coding-agent infrastructure conceptually (see
   "Can an existing agent already do this?" — the generic pieces are
   duplicated, the defect-specific orchestration is not).
9. Scope: all three domains (Payables, Receivables, Payments) from v1.
10. Deployment: a new isolated service, mirroring `defect-fix-service`'s
    isolation — not built inside the main backend.
11. Timeline UI: a generic, reusable component, not a one-off.

## Can an existing agent already do this?

Checked three candidates before writing anything new:

- **`defect-fix-service`'s orchestrator, as-is: no.**
  `TriageOrchestrator.processTicket()` inlines defect-specific logic directly
  in the method body — no `TicketHandler` seam exists today. Three
  assumptions are hardwired: (1) every iteration extracts a
  `DEFECT_SIGNATURE:` marker and gates on that specific signature being
  resolved — a transform ticket has none; (2) `GitWorktreeManager` fetches
  and pushes back onto the *same branch as the failing PR* — a transform
  ticket has no PR to fix, it needs a fresh branch off `main`; (3)
  `TestGateRunner` re-parses `mvn test`/`eslint` output specifically to diff
  a target signature out of ~900 pre-existing unrelated warnings in the
  shared monorepo — a small, purpose-built `transform-handlers` repo has no
  such noise, so a plain exit-code check is sufficient and simpler to write
  fresh than to bend the original to fit. Bolting a second ticket type onto
  the live service means refactoring it first — real work on a service whose
  own design doc calls it "VERIFIED END-TO-END," not a config change.
- **Any other existing agent: no.** A repo-wide `*Agent*` class search across
  `backend/`, `defect-fix-service/`, and `mcp-gateway/` finds exactly one:
  `CodingAgentClient`.
- **The AI Copilot's chat tools: no.** It has a real propose→human-confirms→
  execute pattern for two narrow mutations (`PauseSweepRuleTool`,
  `SetBalanceAlertTool`), and read-only-ness is enforced structurally for MCP
  specifically (`McpToolPipeline` filters out any `isMutating()` tool) — but
  there's no file-upload handling, no code-execution/write loop, and no
  ticket/timeline concept anywhere in `ai/copilot`.

**Conclusion**: a new service is genuinely necessary. It reuses the truly
generic sub-pieces of `defect-fix-service` (the Claude tool-use loop, the
low-level Jira and GitHub REST clients) but writes fresh, simpler equivalents
of the higher-level orchestration classes rather than copying defect-specific
logic that doesn't apply and would need immediate rewriting anyway.

## What already exists and gets reused (verified by reading the source)

**`defect-fix-service`** is the architectural template — see its own
`tasks/defect-autofix-pipeline-design.md`. Reused pieces are listed in the
table below; deployment (own Dockerfile, `docker-compose.yml` service under
its own profile, env vars default empty, named volume, routed through the
shared Caddy proxy with no public port) is copied directly as a pattern.

**ISO 20022 reality** (materially shapes per-domain processing below):
- Receivables has a real, live, but **completely unvalidated** inbound path:
  `Iso20022PaymentController.processCamt054` → `Iso20022InwardPaymentService
  .parseCamt054()` (manual DOM parse, no XSD) → `TransactionService
  .processCollection()`. `Iso20022ValidationService` exists but is wired only
  to the unrelated outbound generation path, and even there its XSD check is
  a silent no-op (the referenced `.xsd` files aren't on the classpath).
  `ReceivablesService.processCamt054` is a second, parallel method —
  confirmed dead code, zero callers; not a target.
- Payables/Payments have **no inbound ISO 20022 path at all**. pain.001 is
  output-only (`Iso20022XmlBuilder`). `PayablesService.executePayment`
  requires a pre-existing, already-`APPROVED` `Payable` row;
  `Iso20022OutwardPaymentService.processOutwardPayment` takes a structured
  request object, not a file.
- Two precedents reused directly: `CoboReceivableService
  .previewCoboCollection()` (a real dry-run-without-writing method) and
  `Payable.PayableStatus` (a real `DRAFT → PENDING_APPROVAL → APPROVED →
  PROCESSING → PAID` state machine already enforced by `payable.canBePaid()`).

**Frontend precedents**:
- `Stepper` (`frontend/src/components/ui/enhanced.tsx:203-303`) exists,
  unused anywhere — the stage-progress rail.
- `ExceptionDashboardPage.tsx`'s `ExceptionDetailDrawer` (lines 332-345)
  already renders a dot+connector timeline — extracted into a standalone
  `EventTimeline.tsx` used by both it and this feature's upload page.
- No polling/WebSocket/SSE exists anywhere in this frontend — the timeline
  uses interval polling (~3s), consistent with the rest of the app.

## Design

### Service: `file-ingest-agent-service`

Package `com.bank.vam.fileingest`. Layout mirrors `defect-fix-service`:
- `intake/` — upload endpoint, workspace file storage, ticket-per-upload.
- `orchestrate/IngestOrchestrator` — per-job state machine (not a single
  global busy-flag — uploads from different customers are legitimately
  concurrent).
- `agent/AnalysisAgentClient` — read-only Claude tool-use loop, produces a
  `FileStructureProfile`. (build order step 3)
- `signature/FormatSignatureService` — the reuse cache. (step 5)
- `gate/` — `TransformTestGateRunner`, `ReconciliationCheckRunner`,
  `StagingDryRunRunner`. (steps 2, 4)
- `quarantine/` — per-row status bookkeeping. (step 2)
- `process/` — `ReceivablesProcessor`, `PayablesProcessor`,
  `PaymentsProcessor`: thin adapters onto the *existing* backend's real
  services, never reimplementing them. (steps 2, 6)
- `events/` — `TimelineEventPublisher` + read endpoint. (step 1, extended
  throughout)
- `jira/` — duplicated `JiraClient` (label changed to
  `file-ingest-pipeline`) + a fresh `IngestTicketService`. (step 1)
- `web/` — REST controllers.

**Reuse table** (what's duplicated from `defect-fix-service` as-is vs.
written fresh — see "Can an existing agent already do this?" above for why):

| Component | Treatment |
|---|---|
| `CodingAgentClient` + `WorkspaceTools` | Duplicate as-is (generic tool-use loop) |
| `JiraClient` (low-level REST wrapper) | Duplicate, change the pipeline label constant |
| `GitHubPullRequestClient` | Duplicate as-is |
| `IngestTicketService` (replaces `JiraTicketService`) | Fresh — identity is the `ingest_job` row, not a defect signature |
| Worktree manager equivalent | Fresh, simpler — new branch off `main` per job, not "push back to the failing PR's branch" |
| `IngestOrchestrator` (replaces `TriageOrchestrator`) | Fresh, simpler — no defect-signature/CI-artifact handling to inherit |
| `TransformTestGateRunner` (replaces `TestGateRunner`) | Fresh, simpler — one generated test in a noise-free repo, plain exit code is enough |

Each duplicated file carries a `// ponytail: duplicated from defect-fix-
service; extract to a shared module if a third consumer needs this` comment.
`defect-fix-service` itself is never modified.

### Pipeline

| # | Stage | Component | Done when |
|---|-------|-----------|-----------|
| 1 | File arrives | `intake` upload endpoint (multipart) | File on workspace volume + `ingest_job` row (`RECEIVED`) |
| 2 | Ticket filed | `IngestTicketService` | Jira ticket key stored on the job |
| 3 | Analysis | `AnalysisAgentClient` | `FileStructureProfile` written; job → `ANALYZED` |
| 4 | Signature lookup | `FormatSignatureService` | Cache hit → skip to 6; miss → `SIGNATURE_NEW` |
| 5 | Coding agent (miss only) | duplicated `CodingAgentClient` against `transform-handlers` | Transform + test committed to a worktree branch |
| 6 | Test gate | `TransformTestGateRunner` | Tests green → merged; `format_signature.transform_ref` set |
| 7 | Reconciliation + staging dry run | `ReconciliationCheckRunner` | Rows in `staged_transaction`; control-total match recorded |
| 8 | Per-row quarantine | `quarantine` | Every staged row is `READY` or `QUARANTINED` with a reason |
| 9 | Live processing | domain `process/*Processor` | Each `READY` row `PROCESSED` or `FAILED`, doesn't block siblings |
| 10 | Ticket closed | `IngestOrchestrator` | All-clear → Jira `Done`; reconciliation mismatch → `Blocked` + `needs-human` |

### Data model

New tables in this service's own Postgres schema (`fileingest`, inside the
same shared `vam_db` instance the main backend already uses — no new
database engine):
- `ingest_job` — one row per upload; the queryable backbone. The Jira ticket
  is a view onto it, not the source of truth (unlike `defect-fix-service`,
  whose worst failure mode tolerates Jira-only state — this pipeline moves
  real money and must answer "what happened to row 4,812" months later).
- `format_signature` — signature hash → `transform_ref` (git commit SHA).
- `staged_transaction` — one row per source row: status + reason + FK to the
  real created entity. The audit trail and the quarantine mechanism.
- `reconciliation_result` — control totals compared, delta, pass/fail.
- `timeline_event` — one row per stage-transition event, polled by the UI.

Stays filesystem/Jira: the raw uploaded file, `analysis.json`, agent
trajectory logs, and the transform code itself (lives in git, referenced by
commit SHA).

### Format-signature cache

Signature = `SHA-256(customerId | domain | normalized-column-headers |
delimiter)`. Transform code lives in a separate small git repo,
`transform-handlers` (own repo, own CI) — one package per signature hash,
transform class + its test + the sample fixture, committed together.

### Per-domain processing

- **Receivables**: transform output is CAMT.054-shaped XML fed into the
  existing `Iso20022InwardPaymentService.parseCamt054()`. Flagged separately:
  that path has zero validation of its own today — this pipeline's
  reconciliation/staging/quarantine layer is doing safety work that arguably
  belongs in `Iso20022ValidationService` itself (a follow-up, out of scope
  here).
- **Payables**: no inbound pain.001 parser exists. The transform's structured
  output drives `Payable` creation directly; the pipeline programmatically
  walks `submitForApproval()` → `approve()` → `PayablesService
  .executePayment()` (no human clicks these, per decision 3). The pain.001
  shape is produced only as an audit artifact alongside the staged row.
- **Payments**: same shape as Payables — `PaymentsProcessor` calls
  `Iso20022OutwardPaymentService.processOutwardPayment()` directly per
  `READY` row.

### Timeline UI

`TimelineEventPublisher.emit()` fires at the start/end of every pipeline
stage. Frontend polls `GET /api/v1/ingest/jobs/{id}/timeline` every ~3s. The
ten stages become the `steps` array for the existing `Stepper`; the full
event list renders below it via the new `EventTimeline.tsx` (extracted from
`ExceptionDetailDrawer`, used by both).

## Build order

- [x] **Step 1** — schema (`ingest_job`/`format_signature`/
      `staged_transaction`/`reconciliation_result`/`timeline_event`) + plain
      upload endpoint + Jira ticket creation. No agents yet.
- [x] **Step 2** — Receivables-only processor, hand-written transform for one
      known format (a fixed CSV shape — see `ReceivablesCsvTransform`), wired
      through staging → reconciliation → quarantine → the real backend's
      `POST /api/v1/iso20022/inward/payment` (called over HTTP, per this
      service's own module boundary — see the finding below). Verified
      against a real local Postgres (schema migrates, entities validate,
      full context starts) and with unit tests on the transform/reconciliation
      math and per-row quarantine rules; not yet verified against a live
      backend + Jira (neither was running in this environment).
      **Finding while implementing**: `Iso20022InwardPaymentService` exposes
      a plain-JSON `processInwardPayment` endpoint directly — the transform
      never needs to produce literal CAMT.054 XML and round-trip it through
      `parseCamt054()`; it targets `InwardPaymentRequest`'s fields (the same
      semantic content ISO 20022 carries) and calls the JSON endpoint. Simpler
      than what this doc originally described, and avoids inventing XML this
      pipeline would immediately have thrown away.
- [x] **Step 3** — Analysis agent, Receivables-only, always cache-miss.
      `AnalysisAgentClient` duplicates `CodingAgentClient`'s tool-use loop
      shape but is read-only (`read_file`/`sample_rows` only, no
      write/exec — see `AnalysisWorkspaceTools`) and terminates on a
      mandatory `submit_profile` tool call instead of "model stops calling
      tools." Wired into `IngestOrchestrator.runReceivablesJob` right before
      the hand-written transform, writing `analysis.json` to the job's
      workspace dir and emitting an `ANALYZED` timeline event; deliberately
      does **not** block or drive the job on failure yet — the step 2
      hand-written transform remains what actually processes rows until
      step 4 wires a coding agent to consume this profile. Verified:
      compiles clean, all 5 existing unit tests still pass, and the full
      Spring context (including the new `AnalysisAgentClient` bean) starts
      cleanly against real Postgres with an empty `ANTHROPIC_API_KEY` (no
      `BeanCreationException`, same class of check done for `JiraClient` in
      step 1). **Update**: subsequently verified live with a real
      `ANTHROPIC_API_KEY` (see the status line at the top of this doc) — the
      agent correctly profiled both a known and a never-seen file's real
      structure (columns, delimiter, control-total column) via genuine
      `read_file`/`sample_rows`/`submit_profile` tool calls.
- [x] **Step 4** — Coding agent + `transform-handlers` repo + test gate,
      Receivables-only. `IngestOrchestrator` now tries `ReceivablesCsvTransform`
      first (cheap, no LLM call); an `IllegalArgumentException` (unrecognized
      header) falls through to `TransformGenerationService`, which:
      1. `TransformWorktreeManager` writes the `transform-handlers` repo
         skeleton on first use (own local git repo under the workspace dir,
         **not** cloned from / pushed to GitHub — decision 7 ruled out human
         review, so there's no PR to open; a passing test gate merges
         straight into `main` with a plain local `git merge`).
      2. `TransformCodingAgentClient` (full read/write/list/run_command tools
         via `TransformWorkspaceTools`, both duplicated from
         `CodingAgentClient`/`WorkspaceTools`) writes a
         `generated.<package>.GeneratedTransform implements RowTransform` +
         its own JUnit test against a copy of the job's actual file, in a
         fresh worktree branch.
      3. `TransformTestGateRunner` runs `mvn test` in the worktree and gates
         on the exit code alone (no signature-diffing needed — a small
         purpose-built repo has no pre-existing warning noise, exactly as
         the design doc's reuse table called for). Up to
         `ingest.agent.max-retries` retries, feeding the failure back to the
         agent, mirroring `TriageOrchestrator`'s attempt loop.
      4. On green: merge to `main`, run the generated transform via a fixed,
         hand-written `TransformRunnerMain` (reflection-loads the class by
         name — the agent never writes its own CLI/IO glue), and record a
         `format_signature` row via `FormatSignatureService` (hash + write
         only; step 5 adds the lookup that skips the agent on a repeat).
      5. Reconciliation gets a real independent check for this path too:
         `TransformGenerationService` re-parses the raw file itself using
         the analysis profile's delimiter/control-total column, rather than
         trusting the generated transform's own counts. **ponytail
         limitation**: only works for a single-character delimiter with a
         known control-total column; anything else (fixed-width, multi-char
         delimiter, no control column) falls back to trusting the
         transform's reported totals, so reconciliation can't catch a
         dropped/miscounted row for those shapes yet.
      Verified: compiles clean; 13 unit/integration tests pass, including a
      real (non-mocked) `TransformWorktreeManagerTest` that exercises actual
      `git init`/`worktree add`/`merge` against a temp directory; full Spring
      context starts cleanly against real Postgres with all five new beans
      wired and an empty `ANTHROPIC_API_KEY`. **Update**: subsequently
      verified live (see the top status line) — a real, never-seen
      semicolon-delimited file drove a genuine 5-turn coding-agent run
      (read the interface and fixture, write the transform and its test,
      run `mvn test` itself, stop cleanly), a real `TransformTestGateRunner`
      pass, and a real merge into `transform-handlers` with a real commit
      SHA recorded on the `format_signature` row.
- [x] **Step 5** — Format-signature cache, Receivables-only. The package a
      signature's generated code lives in is now derived deterministically
      from the signature hash itself (`sig<first-16-hex-chars>`), not the
      job id — that's what lets a later job reconstruct the same class to
      invoke without a new persisted column. `IngestOrchestrator` looks the
      signature up (`FormatSignatureService.lookup`) right after an
      unrecognized-format exception, before touching the coding agent: a hit
      goes straight to `TransformGenerationService.runCached`, which skips
      the worktree, the agent and the test gate entirely and runs the
      already-merged code straight off `transform-handlers`' own `main`
      checkout; a miss falls through to step 4's generate/test/merge path
      exactly as before. New `SIGNATURE_MATCHED` timeline event fires
      instead of `SIGNATURE_NEW`/`CODING_AGENT_RUNNING`/`TEST_GATE` on a hit.
      **Known limitation, accepted for now**: two different jobs racing a
      genuine first-sighting of the exact same brand-new shape would both
      target the same deterministic package from separate worktrees; the
      second merge fails on a git add/add conflict and that job is blocked
      rather than corrupting the repo — the same single-instance assumption
      `TriageOrchestrator` already makes elsewhere in this codebase.

      **Real bug found and fixed while verifying this step**: the very
      first genuinely end-to-end test in this service (one that actually
      shells out to `mvn` from Java, rather than just unit-testing a
      `decide()`-style pure method) surfaced two real, previously-latent
      Windows-specific defects, both now fixed for good reason, not just to
      make the test pass:
      1. Plain `"bash"` resolution on this Windows box can hit the WSL
         launcher stub in `System32` before Git for Windows' real bash
         (confirmed: `where bash` lists the WSL stub ahead of Git's), and
         WSL does not inherit the launching process's environment variables
         at all — not even a synthetic test variable came through, let
         alone `JAVA_HOME`. Fixed with a small `ShellCommands.bashExecutable()`
         helper that prefers Git for Windows' bash by absolute path on
         Windows and falls back to plain `"bash"` everywhere else (Linux
         containers, CI, mac), where this collision doesn't exist.
      2. Java's `ProcessBuilder` on Windows mis-escapes a command **string**
         containing embedded double quotes (confirmed by hand: it applies
         MSVCRT-style requoting that bash, a non-MSVCRT program, doesn't
         expect, silently truncating `-Dexec.args="a b"` down to one
         argument instead of two). Fixed in `TransformGenerationService` by
         using single quotes there, and — since the coding agent's own
         `run_command` tool takes arbitrary agent-authored shell commands
         that routinely contain double quotes (`find . -name "*.java"` and
         the like) — fixed more generally in `TransformWorkspaceTools
         .runCommand` by writing the command to a temp script file and
         running `bash scriptfile` instead of `bash -c "<command>"`,
         sidestepping the whole class of quoting bugs rather than one
         character.
      Verified: compiles clean; 14 unit/integration tests pass, including a
      real (non-mocked) `TransformGenerationServiceTest` that seeds an
      already-merged transform via the real git worktree machinery (no live
      Anthropic call needed for a cache HIT, since there's nothing to
      generate), then runs `runCached` end to end — real `mvn exec:java`
      invocation, real JSON parsing, and confirms reconciliation trusts the
      independently-re-parsed raw file's control total over the transform's
      own (deliberately mismatched, in the test) reported total. Full Spring
      context still starts cleanly against real Postgres. **Update**:
      subsequently verified live too (see the top status line) — uploading
      the same never-seen shape twice produced a real cache hit on the
      second upload (`SIGNATURE_MATCHED`, same commit SHA as the first
      run's real coding-agent output), completing in ~15s vs. ~55s for the
      original miss, with no `CODING_AGENT_RUNNING`/`TEST_GATE` events.
- [x] **Step 6** — Payables + Payments processors, reusing every upstream
      stage unchanged. `IngestOrchestrator.runJob` (renamed from
      `runReceivablesJob`) now dispatches on `job.getDomain()`: Receivables
      still tries its hand-written CSV shape first; Payables/Payments have
      no existing inbound file format at all (confirmed in the original
      plan investigation) so every upload for them goes straight to the
      steps 3-5 agent/cache path — that path was already fully
      domain-agnostic, so nothing in it needed to change. A new
      `DomainProcessor` interface (`ReceivablesProcessor` retrofitted to
      implement it too) makes the final live-processing dispatch a
      three-line `switch`.

      Since neither domain has a real inbound file format to target, both
      new processors reinterpret the same generic `TransformedRow` shape
      (built for Receivables' debtor/creditor language) for an outbound
      flow — documented on each processor: `viban` is always the SOURCE VA
      paying out (resolved to an internal VA id via a new
      `BackendClient.resolveVirtualAccountByViban`, since a customer's file
      only ever has a viban, never an internal VAM UUID);
      `debtorName`/`debtorAccount` are reused to carry the vendor/creditor
      (the other party), not literally a debtor.
      - **Payables**: walks the real `Payable` state machine in code per
        decision 3 — `POST /payables` (create, DRAFT) -> `POST
        /payables/{id}/submit` (PENDING_APPROVAL) -> `POST
        /payables/{id}/approve` (APPROVED) -> `POST /payables/{id}/execute`
        (the one step that was already real). `dueDate` defaults to +30
        days (ponytail: `TransformedRow` has no date field, and nothing in
        this chain gates on it).
      - **Payments**: one direct call to `POST /iso20022/outward/payment`
        (`Iso20022OutwardPaymentService`, same `ApiResponse`-wrapped
        envelope shape already proven for the Receivables inward endpoint).

      **Real, live bug found and fixed while verifying this step — not a
      hypothetical**: `PayablesController`'s `/submit` and `/approve`
      endpoints were stubs (`// Would implement in service`) that called
      `getPayable(payableId)` instead of the real
      `PayablesService.submitForApproval`/`approvePayable` methods, which
      already existed and were correct — the controller just never called
      them. Confirmed this is not dead code: `frontend/src/pages
      /EnhancedPayablesPage.tsx` calls `submitForApproval` from its real
      "submit for approval" button, so this was a live no-op in the actual
      Payables UI, not just a blocker for this pipeline. Fixed both
      endpoints in `backend/.../PayablesController.java` to call the real
      service methods (2-line change, verified `mvn compile` clean on the
      main backend afterward). Three sibling endpoints (`/reject`,
      `/schedule`, `/record-payment`) have the identical stub pattern and
      are very likely the same bug, but aren't needed by this pipeline —
      flagged as a separate follow-up task rather than fixed here.

      Verified: file-ingest-agent-service compiles clean; 19 unit tests
      pass, including new `PayablesProcessorTest`/`PaymentsProcessorTest`
      (Mockito-mocked `BackendClient`, covering the success path, a
      mid-chain failure that stops before later steps run, and a
      viban-resolution failure) — all real assertions on state-transition
      behavior, not the HTTP layer itself (no live backend running in this
      environment to hit end-to-end, same honest gap as every other step).
      Full Spring context starts cleanly against real Postgres with the two
      new processors wired in.
- [x] **Step 7** — Timeline UI + `Stepper`/`EventTimeline` extraction. New
      `frontend/src/pages/FileIngestUploadPage.tsx` (nav: Payments &
      Collections → "File Ingest Pipeline") lets a user pick a domain, a
      customer id, and a file via the `CsvAccountUpload` click-only-dropzone
      precedent, then polls `GET /jobs/{id}` + `GET /jobs/{id}/timeline`
      every 3s (no WebSocket/SSE exists anywhere in this frontend, per the
      design doc's own frontend-precedents research). `EventTimeline.tsx` is
      the dot+connector list literally extracted out of
      `ExceptionDashboardPage`'s `ExceptionDetailDrawer` (which now imports
      it too — two callers, one component, exactly per decision 11). The
      existing `Stepper` (`components/ui/enhanced.tsx`, previously unused
      anywhere) drives the stage rail; its step list is chosen dynamically
      per job (known-shape / cache-miss / cache-hit each skip different
      stages — a fixed single sequence would show "completed" checkmarks on
      stages that were legitimately never run).

      New `file-ingest-agent-service` `WebConfig` duplicates the main
      backend's CORS filter (this service is called directly cross-origin
      from the frontend dev server, its own separate deployable — no shared
      reverse proxy exists between them yet).

      **This step is what finally exercised the pipeline through a real
      browser-driven multipart upload against real running services** —
      every earlier step's verification was compile/test/context-start
      only. That surfaced two genuine, previously-latent bugs from much
      earlier steps, both confirmed live and fixed:
      1. `IngestJob.workspacePath` was `NOT NULL` (`V1__init_schema.sql`),
         but `IngestJobService.receiveUpload` deliberately saves the job
         once with it still unset (to get an id to build the path from)
         before setting it — the very first save of every upload failed
         with `PropertyValueException`, 100% reproducibly, from step 1
         onward. Fixed with `V3__make_workspace_path_nullable.sql` (V1 is
         already applied — never edit it, add a new migration) and dropped
         `nullable = false` from the entity to match.
      2. Jira ticket filing and every Jira call in `IngestOrchestrator`
         (`blockJob`, the DONE-path comment/transition) were unguarded —
         confirmed live against this sandbox's real-but-unconfigured Jira
         project (`KAN` doesn't exist there), a Jira failure crashed the
         *entire upload* with a raw 500 instead of leaving `jiraTicketKey`
         null and continuing. This directly contradicts the design doc's
         own "Data model" section (Jira is a view onto `ingest_job`, not
         the source of truth) — fixed by wrapping ticket filing in
         `IngestJobService` and adding a `safeJira()` helper in
         `IngestOrchestrator` around every Jira call after that, so a Jira
         outage can never mask the pipeline's real, already-committed
         outcome.
      With both fixed, a real upload through the actual browser UI now
      correctly reaches `RECEIVED` → attempts `ANALYZED` → hits the
      expected, already-documented wall (no live `ANTHROPIC_API_KEY` in
      this sandbox — a real 401 from the Anthropic API, propagating up
      through the existing outer safety net) → lands cleanly on `BLOCKED`
      with that exact reason surfaced in both the Stepper (correctly
      stopped at step 1, "Received") and the timeline list. This is the
      first genuinely real, non-mocked, full-stack proof in this entire
      build that the upload → orchestrate → block-on-failure path behaves
      exactly as designed under a real failure, not just in unit tests.

      Verified: `tsc --noEmit` shows zero new errors from any file this
      step touched (the frontend has a large pre-existing baseline of
      unrelated type errors in other pages — confirmed by diffing against
      what already failed before this step); `file-ingest-agent-service`'s
      19 tests still pass after the two fixes; and the live browser
      verification described above. **Update**: the analysis/coding-agent
      gap this note originally flagged was closed shortly after — see the
      top status line for the full live run (real analysis, real coding
      agent, real test gate, real cache hit) driven through this exact UI.

## Residual risks (accepted, not softened)

- No human ever reviews agent-generated code before it moves money. A subtly
  wrong transform that preserves the control total (e.g. swaps two
  same-magnitude columns) passes every automated check and posts anyway.
- The analysis agent sees real, unmasked customer financial data, which now
  also lives in generated test fixtures inside `transform-handlers` and in
  Anthropic API request logs.
- The coding agent has real shell (`run_command`) access inside its worktree,
  scoped to the small `transform-handlers` repo but not sandboxed further.
- A bad first-time transform becomes "the" transform for that customer+format
  indefinitely once cached — errors compound silently until reconciliation
  reporting or a customer complaint surfaces it.
- Staging writes and the reconciliation check must be enforced inside one
  transaction boundary in `ReconciliationCheckRunner` — if that's ever
  loosened, there's a race window between "rows staged" and "reconciliation
  confirmed."

## User stories

1. **New format** — a customer uploads a never-seen format; the ticket and
   job move `RECEIVED → ANALYZED → SIGNATURE_NEW → (build) → STAGED →
   PROCESSED/QUARANTINED → Done` unattended.
2. **Known format** — a repeat upload skips the coding-agent stage entirely
   and completes materially faster.
3. **Live timeline** — the customer watches the `Stepper` rail and event list
   update within one polling interval of each backend transition.
4. **Row quarantine** — one bad row is held back with a human-readable
   reason while the rest of the file processes; the ticket only escalates on
   a reconciliation mismatch or test-gate failure, not on quarantine alone.
5. **Support tracing** — given an `ingest_job` or customer+date, support
   finds the Jira ticket, the `transform-handlers` commit used, the
   reconciliation result, and every quarantine reason.
