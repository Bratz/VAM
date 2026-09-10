# Automated Defect-Fix Pipeline — Design

Status: **VERIFIED END-TO-END — a genuine, confirmed fix (2026-09-10).**

The first two "successes" (KAN-7, KAN-8) turned out to be false positives:
`GitWorktreeManager` branched the coding agent's worktree from
`origin/main`, but every defect this pipeline detects comes from a
PR-triggered CI failure — code that fails CI is, by definition, not
merged, so the defective code only ever existed on the PR's own branch,
never on `main`. The agent's worktree never contained the real buggy
file; it reconstructed something plausible from the ticket text alone
(a fabricated clean file for KAN-7, a shadow class that trivially passed
its own fake test for KAN-8) while the real bug sat untouched.

**Fixed and re-verified live with `KAN-9`**: the coding agent's worktree
now checks out the *originating PR's own branch* (threaded through as a
`SOURCE_BRANCH` ticket marker) and pushes the fix straight back onto that
branch, updating the existing PR in place. This time the agent correctly
found and edited the real production file:
```diff
     public String greet(String name) {
-        return "Hello, World!";
+        return "Hello, " + name + "!";
     }
```
Confirmed via the GitHub API directly (commit `9428a454`, authored by
`defect-fix-bot@vam-portal.local`) — a minimal, correct, genuine fix, not
a workaround. The mechanism is now trustworthy, not just mechanically
running.

Getting this far took a long live-debugging pass — wrong GitHub Actions
merge-commit SHA, a redirect the HTTP client wasn't following, two Jira
API deprecations, a hardcoded issue type that doesn't exist on
team-managed projects, a test gate that gated on the whole project's exit
code instead of the one ticket's defect, absolute/inconsistent file paths
between the CI runner and the VM worktree, stale worktrees/branches left
behind by interrupted attempts, a non-force push rejected by an earlier
attempt's leftover branch, the worktree-source bug above, and a missing
loop-prevention guard (added after researching CI-autofix reference
implementations — see below). None of these were guessable from the
design — each needed a real run to surface. See the git log for
`defect-fix-service/` for the full list if useful context later.

**Reference research (2026-09-10)**: before continuing to iterate blindly,
checked this design against high-star open-source reference
implementations (OpenHands ~70k★, SWE-agent ~19.7k★/NeurIPS 2024, and
CI-autofix guides from OpenAI/Anthropic/Devin). Confirmed correct:
push-fix-to-the-existing-PR-branch (matches `git-auto-commit-action` and
Codex/Devin's own CI-autofix patterns) and worktree/container-based
isolation (matches OpenHands' `Workspace` abstraction). One real gap
found and fixed: every CI-autofix guide checked calls out loop-prevention
as required — added a guard skipping any `workflow_run` whose head commit
was authored by the bot itself. Also noted, not yet acted on: SWE-agent's
Agent-Computer Interface (a curated command set, not raw shell) is the
more rigorous answer to the `run_command` sandboxing gap below.

**Known remaining gaps, not yet exercised:**
- The re-verification above still only covers single-file, single-defect
  changes. Multi-file fixes haven't been exercised.
- `run_command`'s shell access isn't sandboxed to the worktree the way
  `read_file`/`write_file`/`list_files` are (confirmed live: the agent
  could discover sibling worktrees this way) — the worktree/container is
  the intended boundary, not command allow-listing; tightening this is a
  real gap, not a hypothetical one.
- Sentry instrumentation was never started (separate detector).

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
3. ~~GitHub PAT~~ — **done** (generated, held by user).
4. **Create the Sentry account/org + project(s)**, hand over DSNs — not
   started yet (separate detector, not on the critical path below).
5. ~~Anthropic API key~~ — **done** (generated, held by user).
6. ~~Jira workflow statuses~~ — **done**: `In Review` and `Blocked` added to
   KAN's (team-managed) workflow, matching the exact names the code already
   uses. No code change needed.
7. Confirm branch/PR target (`main`, presumably) and any required PR
   labels/reviewers convention.
8. **Actual deployment to the OCI VM** — I have no SSH/remote access to
   that VM (only this local dev machine), so this step has to be run by the
   user. See "Deployment runbook" below.

## Deployment runbook (run on the OCI VM, not here)

1. SSH into the VM, `cd ~/VAM/deploy/oci`.
2. If `.env` doesn't already have `VAM_DB_PASSWORD`, this is the same file
   — add to it, don't replace it:
   ```
   cat >> .env <<'EOF'
   JIRA_EMAIL=<your Atlassian account email>
   JIRA_API_TOKEN=<the Jira API token>
   GITHUB_TOKEN=<the GitHub PAT>
   GITHUB_WEBHOOK_SECRET=<run `openssl rand -hex 32` on the VM to generate one>
   ANTHROPIC_API_KEY=<the Anthropic API key>
   EOF
   ```
   (Generate `GITHUB_WEBHOOK_SECRET` with the `openssl` command shown, on
   the VM itself, so it never has to be typed or pasted anywhere else.)
3. `git pull origin main` (picks up everything built so far).
4. `sudo docker compose --profile defectfix up -d --build`
5. `sudo docker compose logs -f defect-fix-service` — confirm it starts
   clean (look for "Started DefectFixServiceApplication").
6. Register the webhook: GitHub repo (`Bratz/VAM`) → **Settings → Webhooks
   → Add webhook** — Payload URL `https://161-33-9-182.sslip.io/webhooks/github`,
   content type `application/json`, secret = the same `GITHUB_WEBHOOK_SECRET`
   from step 2, events = **Workflow runs** only.
7. First real test: introduce a deliberate lint/type error on a branch, open
   a PR, let Frontend CI fail, and confirm a Jira ticket appears in KAN.

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
6. ~~OCI deployment wiring~~ — **done**: `defect-fix-service/Dockerfile`
   (needs a full JDK, not JRE — the test gate runs real `mvn test`/`npm run
   type-check` inside this container — plus git/bash/node/npm), a new
   opt-in `defectfix` profile in `deploy/oci/docker-compose.yml` (mirrors
   the existing `mcp` profile's pattern, including its "don't use `:?
   required` — Compose evaluates every service's env block regardless of
   active profiles" lesson, which a first draft of this change actually
   re-broke before being caught), a named volume for the workspace dir
   (survives restarts — holds both the live repo clone and any worktrees
   left behind for human inspection after an escalation), and a
   `/webhooks/*` path route added to the existing Caddy config alongside
   the untouched MCP gateway route. GitHub's webhook Payload URL, once
   deployed, is `https://161-33-9-182.sslip.io/webhooks/github`. **Not yet
   verified**: no Docker available in this dev environment to actually
   build/run the image — first real test happens on the OCI VM.
7. Sentry instrumentation (frontend + backend) + its webhook receiver — not
   started. Independent of steps 1-6 (separate detector).

## 11. Post-deployment live verification (this pass)

Steps 1-6 above were built untested against Docker (no local Docker in the
dev environment). Live-verified on the OCI VM since, in order:

- **KAN-7/KAN-8 were false positives** — the coding agent's git worktree
  was branching off `origin/main` instead of the PR's actual failing
  branch, so both "successful" fixes never touched real buggy code. Fixed
  by threading `SOURCE_BRANCH` through the ticket description
  (`JiraTicketService` writes it, `TriageOrchestrator` reads it back out)
  so `GitWorktreeManager.createWorktree` branches from the real PR head.
- **KAN-9** (backend) — first fix confirmed genuine post-SOURCE_BRANCH-fix,
  independently verified via the GitHub API (diff + commit author
  `Defect Fix Bot`), not just pipeline self-report.
- **Loop-prevention guard added**: the detector now ignores CI failures on
  commits authored by the bot itself, closing a real refire risk (bot pushes
  a fix → CI reruns → any unrelated pre-existing failure on that same commit
  would otherwise file a duplicate ticket against its own fix commit).
- **Trajectory logging added** (`CodingAgentClient.ToolCallRecord`,
  `TicketTrajectory`, `TriageOrchestrator.writeTrajectory`) — modeled on
  SWE-agent's `.traj` files / OpenHands' event log: one structured JSON
  artifact per ticket run under `<workspaceDir>/trajectories/`, covering
  every attempt's full tool-call sequence, gate result, and final outcome
  — not just log lines. Live-verified end-to-end on KAN-10 (frontend) and
  KAN-12 (backend); see `KAN-12-*.json` for a real example.
- **KAN-12 gate duration**: the backend test gate (`mvn -B test`, full
  suite, no filtering — see `TestGateRunner.runBackendChecks`) took ~11
  minutes wall-clock on the OCI VM for a fix that took the coding agent
  under 15 seconds to write, vs. ~1:22 for the same suite on a local dev
  machine. `ps aux` during the run showed the Maven process alive but with
  only ~9s of accumulated CPU time over 10+ minutes — mostly blocked/
  waiting, not compute-bound; root cause not yet confirmed (candidates:
  cold Maven cache for a fresh worktree, throttled/shared OCI vCPU, slow
  Postgres/network I/O during Spring context boot). **Open, unverified
  concern**: `TestGateRunner.run()` force-kills the gate command at its
  600s timeout but `runBackendChecks()` doesn't check whether that
  happened — it unconditionally parses whatever `target/surefire-reports/`
  XML files exist afterward. Since KAN-12's total run was close to that
  ceiling, a timeout-truncated run could silently read as a clean pass if
  the relevant test class's report happened to flush before the kill.
  Deferred — revisit if a future run's gate timing looks similarly close
  to 600s.
- Smoke-test branches/PRs used for this verification (`test/defectfix-*`,
  KAN-9/10/12): KAN-12's (PR #10, backend) merged then its throwaway
  `SmokeTestReverser`/`SmokeTestReverserTest` fixture removed from `main`
  in a follow-up commit. KAN-9 (PR #8) and KAN-10 (PR #9) are still open,
  unmerged — pending the same close-without-merge-and-delete-branch
  treatment.
