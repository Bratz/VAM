# Automated Defect-Fix Pipeline — Design

Status: **APPROVED — implementation underway.**

- Jira project key: **KAN** (`vam-five.atlassian.net`)
- Jira API token: generated (held by user, wired in as an OCI env var when
  the service is deployed — never pasted into chat/committed to the repo)

## 1. Goal

Detect defects in vam-portal (frontend + backend), file them in Jira, triage and
fix them with agents, verify with tests, and open a PR for human merge —
without a person touching the loop until review time.

## 2. Scope decisions (confirmed)

| Decision | Choice |
|---|---|
| Codebase scope | Both frontend (React/TS) and backend (Spring Boot) |
| Defect sources | Static analysis/CI failures (`tsc`, `eslint`, `mvn test`) + Sentry (new account, both stacks) |
| Backlog handling | New failures only — pre-existing warnings/backlog are NOT auto-ticketed |
| Trigger | Real-time/event-driven (webhooks), not scheduled polling |
| Concurrency | One ticket in flight at a time |
| Retries | Up to 2 retries (3 attempts total) before escalating to a human |
| Push policy | Branch + PR; **human merges**. No auto-merge, no direct push to main |
| Risk filtering | None — every accepted ticket goes through the same pipeline (the PR-merge gate is the safety net) |
| Orchestration | **Standalone service** (not Claude-Code-session-native) |
| Service stack | Java/Spring Boot (matches vam-service) |
| Hosting | Same OCI deployment as vam-portal (new container alongside existing compose) |
| State storage | **Jira is the source of truth** — no separate DB for ticket/retry state |
| Dashboard | None — Jira + GitHub PRs are the UI |
| Notifications | None beyond GitHub PR + Jira ticket state (no Slack/email) |

## 3. Architecture

```
                 ┌─────────────────────────┐
GitHub Actions ──►  Webhook receiver        │
(CI failures)      │  (Spring Boot service) │
                 │                         │
Sentry webhook ──►│                         │
(new issue)        └───────────┬─────────────┘
                                │
                        Dedup + ticket create
                                │
                                ▼
                       ┌─────────────┐
                       │    Jira     │  ← source of truth for all state
                       │  (project)  │
                       └──────┬──────┘
                                │  poll/react: "any ticket In Progress?"
                                ▼
                     ┌────────────────────┐
                     │   Triage step        │  (in-process, same service)
                     │ - pick next ticket   │
                     │ - build task brief   │
                     │ - mark In Progress   │
                     └──────────┬─────────┘
                                │
                                ▼
                 ┌───────────────────────────┐
                 │  Coding agent run           │
                 │  - git worktree + branch    │
                 │  - Claude Agent SDK/API     │
                 │  - up to 3 attempts         │
                 └─────────────┬─────────────┘
                                │
                       run test gate (per stack)
                                │
                 ┌──────────────┴──────────────┐
              pass                            fail (after 3 attempts)
                 │                              │
                 ▼                              ▼
        commit, push branch,           comment diff + logs,
        open PR, link Jira,            label "needs-human",
        Jira → In Review               Jira → Blocked
                 │                              │
                 ▼                              ▼
          human reviews/merges         human investigates
```

## 4. Components

### 4.1 Detectors
- **Frontend CI** — new GitHub Actions workflow (none exists today besides
  `deploy-oci.yml`): `npm run type-check`, `npm run lint` on every PR.
- **Backend CI** — new GitHub Actions workflow: `mvn -B verify` (compile +
  test) on every PR.
- **"New failures only" mechanism**: for each PR-triggered run, the service
  diffs the failing-check set at `HEAD` against the same commands run at the
  PR's merge-base. Only failures absent at the merge-base become tickets.
  (Avoids needing main to be pre-cleaned of today's 3 pre-existing lint
  warnings before this can go live.)
- **Sentry** — SDK added to frontend (error boundary + browser SDK) and
  backend (Spring Boot starter + exception handler), reporting to a new
  Sentry project. Sentry's own issue webhook (`issue.created` /
  first-seen) fires into the service. Sentry already de-duplicates repeat
  occurrences into one "issue" — the service treats one Sentry issue as one
  Jira ticket (no re-ticketing on repeat occurrences of the same issue).

### 4.2 Ticket creator (in the service)
- Builds a **defect signature** per source so duplicates aren't filed twice:
  - Lint/type error → `rule + file (+ line)`
  - Test failure → `test class + method + assertion`
  - Sentry → the Sentry issue ID
- Before creating a ticket, searches Jira (JQL) for an open ticket with the
  same signature (stored as a label, e.g. `sig:<hash>`); skips if found.
- Ticket includes: source, file/stack trace or Sentry link, the specific
  failing command output, and the signature label.

### 4.3 Triage step
Since there's no risk-based filtering, triage's job is narrower than a
full "should we even fix this" gate. It:
1. Picks the next ticket only when **no ticket is currently `In Progress`**
   (JQL query — this is how single-concurrency is enforced without a DB).
2. Orders the backlog (oldest first, or by a priority field if set).
3. Builds the coding agent's task brief from the ticket content.
4. Transitions the ticket to `In Progress`.

### 4.4 Coding agent
- `git worktree add` a fresh checkout on a branch named
  `fix/<TICKET-KEY>-<slug>`.
- Calls the Claude Agent SDK (Java) with the task brief + relevant source.
- Runs the stack-appropriate test gate:
  - Frontend defect → `npm run type-check && npm run lint` (+ existing
    tests if/when a runner is added — there is none today beyond these two
    checks).
  - Backend defect → `mvn test`.
- On failure: feeds the failure output back to the agent, retries (cap: 2
  retries, 3 attempts total).
- On success: commits (message includes the Jira key), pushes the branch,
  opens a GitHub PR (description links the Jira ticket), Jira → `In Review`
  with a comment linking the PR.
- On exhausted retries: comments the last attempted diff + failure output
  on the ticket, labels it `needs-human`, Jira → `Blocked`. Worktree is left
  in place for inspection, cleaned up when the ticket is closed.

### 4.5 Human gate
PR review/merge in GitHub is the only manual step in the happy path.
Escalated tickets surface as `Blocked` in Jira with full context attached.

## 5. Jira project requirements

- A dedicated project (key TBD — you'll provide it).
- Statuses used: `To Do` → `In Progress` → `In Review` → `Done`, plus
  `Blocked` for escalations.
- Labels: `auto-fix-pipeline` (so JQL queries don't pick up unrelated
  tickets in the same project), `sig:<hash>` (dedup), `needs-human`
  (escalation marker).
- No custom fields required — retry count is inferred by counting
  `Attempt N failed` comments on the ticket rather than needing custom
  field admin setup.

## 6. Integrations needed before build

- **Atlassian (Jira) connector** — authorize + confirm project key.
- **GitHub connector/token** — for opening PRs on `Bratz/VAM` (repo
  confirmed via `git remote`).
- **Sentry** — you create the account/org + project (needs a login);
  hand over the DSN(s) for frontend and backend.
- **Anthropic API key** for the standalone service to call the Agent SDK.

## 7. Deployment

- New service module, added as another container in the existing OCI
  `docker-compose.yml` alongside the current backend/frontend/Postgres/Redis
  stack (`deploy/oci/docker-compose.yml`).
- Needs outbound network access to: GitHub API, Jira API, Sentry, Anthropic
  API. Needs inbound access for webhook receipt from GitHub/Sentry (or the
  OCI box already has a reachable endpoint — reuses existing ingress).
- Secrets (Jira token, GitHub token, Sentry DSNs, Anthropic key) via env
  vars, consistent with how `vam.*` config is already handled.

## 8. Explicit non-goals (this pass)

- No new regression tests required from the coding agent — existing test
  suite is the only gate.
- No dashboard/UI beyond Jira + GitHub.
- No Slack/email notifications.
- No auto-merge — every fix lands as a human-reviewed PR.
- No risk-based category filtering — every accepted ticket goes through the
  same pipeline.
- No production-error monitoring beyond Sentry (no Datadog, etc.).

## 9. Open items before implementation can start

1. ~~Jira project key~~ — **KAN**, done.
2. ~~Jira API token~~ — **done** (generated, will be wired as an OCI env
   var, not committed).
3. **GitHub PAT** for the service (repo + PR scopes on `Bratz/VAM`) — code
   is written and unit-tested, but nothing has been run end-to-end against
   the real GitHub API yet. Needed to actually verify artifact download,
   merge-base diffing, and PR creation live.
4. **Create the Sentry account/org + project(s)**, hand over DSNs — not
   started yet (separate detector, not on the critical path below).
5. **Anthropic API key** — code is written (real tool-use loop against the
   verified Anthropic Java SDK API surface), but never called live yet.
6. **Jira workflow statuses** — the code assumes a project with `To Do` →
   `In Progress` → `In Review` → `Done`/`Blocked` statuses (transitions are
   looked up by name via the Jira API, so exact IDs don't matter, but the
   status *names* must exist in the KAN project's workflow). Please confirm
   KAN has these, or tell me the actual status names to use instead.
7. Confirm branch/PR target (`main`, presumably) and any required PR
   labels/reviewers convention.

## 10. Suggested build order (once signed off)

1. ~~GitHub Actions workflows for frontend/backend CI~~ — **done**:
   `.github/workflows/frontend-ci.yml` (type-check + eslint JSON, artifact
   `frontend-checks-<sha>`), `.github/workflows/backend-ci.yml` (`mvn test`,
   artifact `backend-checks-<sha>`). Both run on PRs and pushes to `main`,
   uploading commit-tagged structured output for the service to diff later;
   neither is forced green — real pass/fail stays a normal CI signal.
2. Service skeleton: webhook receivers → Jira ticket creation + dedup
   (this alone is independently useful/testable before any coding agent
   exists).
3. ~~Triage step~~ — **done**: `TriageOrchestrator` (single-concurrency via
   an in-process gate — see its ponytail note on that choice's ceiling),
   picks the oldest `To Do` ticket via JQL, builds the task brief from the
   ticket's summary+description.
4. ~~Coding agent loop~~ — **done**: `GitWorktreeManager` (per-ticket
   worktree on its own branch), `CodingAgentClient` (real tool-use loop
   against the Anthropic Java SDK — read_file/write_file/list_files/
   run_command, all path-escape-guarded, tested), `TestGateRunner`
   (frontend: type-check+lint; backend: mvn test), retry loop wired in
   `TriageOrchestrator` up to `pipeline.agent.max-retries` (default 2).
5. ~~PR creation + Jira transition wiring~~ — **done**: `GitHubPullRequestClient`
   opens the PR; on success Jira → `In Review` with a comment linking it; on
   exhausted retries Jira → `Blocked` + `needs-human` label + the last gate
   output as a comment (worktree deliberately left on disk for inspection).
6. Sentry instrumentation (frontend + backend) + its webhook receiver — not
   started. Independent of steps 1-5 (separate detector).
