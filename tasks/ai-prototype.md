# AI Prototype — Treasury Copilot (Stub Only)

> **Scope decision**: this prototype ships **with no LLM connection**. The "AI" is a deterministic
> stub — pattern-matched intent routing → real tool execution → templated response. The point
> is to validate the *product experience* (drawer UX, action cards, integration across pages),
> not the AI quality. Real LLM integration is an explicit follow-up, not part of this work.
>
> Why this is the right call for a prototype:
> - No API key, no cost, no rate limits — runs offline, demos every time
> - All tool plumbing, action gating, persistence, dark-mode parity stays real
> - When we promote to v1 with a real LLM, the only swap is one class (`IntentRouter` → `LlmClient`)
> - Forces us to design the intent space explicitly, which is good docs for the future LLM prompt

---

## 1. Goal

A floating chat drawer in the VAM Portal that lets a treasurer **ask any question grounded in their VAM data** (accounts, sweeps, statements, audit trail), and **execute gated write actions** (pause a rule, set an alert) — all from a single anchor point on every page.

The "intelligence" comes from a deterministic intent router that matches user input to one of ~12 known intents, executes the corresponding read tools against **real data**, and composes a templated answer with the real numbers filled in.

### Success criteria
- [ ] All 6 demo questions answer correctly with **real data** from the database
- [ ] 2 demo write actions execute end-to-end (pause sweep rule, set balance alert)
- [ ] Streaming illusion: tokens render at ~30ms each via SSE so it feels alive
- [ ] When user input doesn't match any intent, drawer shows a polite refusal listing what it *can* answer (with clickable suggested prompts)
- [ ] Honours active `MarketProfile` — same prompt produces UAE-localised vs UK-localised answers (currency, timezone, sample names)
- [ ] Dark-mode parity (drawer matches the design system we just shipped)
- [ ] Conversations persist across page reloads

### Non-goals (explicit, to avoid scope creep)
- **No LLM integration** — stub only. Real LLM is a separate v1 effort.
- Multi-turn agentic planning (one user turn → one stubbed turn this prototype)
- Voice / document upload / OCR
- Cross-corporate analysis (single corporate context per session)
- Anything that writes money (no payment initiation)
- Free-form natural language — stub only handles the curated intent set

---

## 2. Architecture at a glance

```
┌────────────────────────────────────────────────────────────────────┐
│  Frontend  (React + Vite)                                          │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  CopilotProvider (context, conversation state, SSE client)   │  │
│  │     ├── CopilotDrawer (Sheet component, anchored bottom-right)│  │
│  │     │    ├── MessageList (user / assistant / tool-call rows) │  │
│  │     │    ├── ActionCard  (confirm-to-execute write actions)  │  │
│  │     │    └── Composer    (textarea + send + suggested prompts)│ │
│  │     └── useCopilot()     (open/close, send, stream tokens)   │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                              │ POST /api/ai/copilot/chat (SSE)     │
└──────────────────────────────┼─────────────────────────────────────┘
                               ▼
┌────────────────────────────────────────────────────────────────────┐
│  Backend  (Spring Boot 3.2.5)                                      │
│  com.bank.vam.ai.copilot                                           │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  CopilotController     (SSE endpoint)                        │  │
│  │  CopilotService        (orchestrator: route → tools → render)│  │
│  │                                                              │  │
│  │  intent/                                                     │  │
│  │   ├── IntentRouter         (regex/keyword → IntentMatch)     │  │
│  │   ├── Intent               (enum: GET_POSITION, FAILED_SWEEPS,│  │
│  │   │                          EXPLAIN_REJECTION, IDLE_ACCOUNTS,│  │
│  │   │                          PAUSE_RULE, SET_ALERT, …)        │  │
│  │   ├── IntentMatch          (intent + extracted slots)        │  │
│  │   ├── ResponseComposer     (template → final markdown)       │  │
│  │   └── templates/           (one .md per intent, w/ {{slots}})│  │
│  │                                                              │  │
│  │  tools/                    (real, hit real services)         │  │
│  │   ├── CopilotTool          (interface)                       │  │
│  │   ├── ToolRegistry                                           │  │
│  │   ├── read/  (GetAccounts, GetPosition, GetSweepStatus,      │  │
│  │   │           GetSweepInstructions, GetStatementLines,       │  │
│  │   │           GetAuditTrail, GetRejectionCodes)              │  │
│  │   └── write/ (PauseSweepRule, SetBalanceAlert)               │  │
│  │                                                              │  │
│  │  conversation/  (CopilotConversation, CopilotMessage)        │  │
│  │  action/        (ActionCard, ActionProposal, Executor)       │  │
│  └──────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────┘
```

Key design choices:
- **Stub = IntentRouter + ResponseComposer**: regex/keyword extraction → known intent + slots → tool calls → template fill. No model, no tokens.
- **Tools are real**: every tool queries actual services (`MultiBankLiquidityViewService`, `SweepInstruction` repo, `AuditLogService`, etc.) so answers contain real numbers.
- **Read vs Write split**: read tools execute server-side immediately. Write tools are *proposed only* — emit an `action_card` payload, user clicks Confirm, separate `/api/ai/copilot/actions/{id}/execute` endpoint mutates state.
- **SSE for streaming illusion**: even though the answer is fully composed before the first byte goes out, we tokenise it and emit one chunk per ~30ms so the UI feels alive. Spring WebFlux is already on classpath.
- **One-class LLM swap path**: when we promote, replace `IntentRouter` + `ResponseComposer` with a single `LlmClient` calling Anthropic with the same `ToolRegistry`. Frontend, persistence, action cards, drawer — all unchanged.

---

## 3. File scaffolding (concrete paths)

### Backend (new files)

```
backend/src/main/java/com/bank/vam/
├── ai/
│   └── copilot/
│       ├── CopilotController.java               # @RestController, SSE endpoint
│       ├── CopilotService.java                  # Orchestrator: route → tools → render → stream
│       ├── CopilotProperties.java               # @ConfigurationProperties("vam.ai.copilot")
│       │
│       ├── intent/
│       │   ├── Intent.java                      # enum of supported intents
│       │   ├── IntentRouter.java                # regex/keyword matcher → IntentMatch
│       │   ├── IntentMatch.java                 # intent + extracted slots map
│       │   ├── Slot.java                        # constants: CURRENCY, RULE_ID, INSTRUCTION_ID, …
│       │   ├── ResponseComposer.java            # loads template, interpolates slots + tool outputs
│       │   └── templates/                       # resources, one .md per intent
│       │       ├── get_position.md
│       │       ├── failed_sweeps.md
│       │       ├── explain_rejection.md
│       │       ├── idle_accounts.md
│       │       ├── pause_rule.md
│       │       ├── set_alert.md
│       │       ├── unknown.md                   # polite refusal + suggested prompts
│       │       └── greeting.md
│       │
│       ├── conversation/
│       │   ├── CopilotConversation.java         # @Entity
│       │   ├── CopilotMessage.java              # @Entity (role, content, tool_calls jsonb)
│       │   ├── CopilotConversationRepository.java
│       │   └── CopilotMessageRepository.java
│       │
│       ├── tools/
│       │   ├── CopilotTool.java                 # interface { name, execute(ToolContext, params) }
│       │   ├── ToolRegistry.java                # @Component, autowires all CopilotTool beans
│       │   ├── ToolContext.java                 # carries Corporate, MarketProfile, principal
│       │   ├── ToolResult.java                  # uniform return shape for templates
│       │   ├── read/
│       │   │   ├── GetAccountsTool.java
│       │   │   ├── GetPositionTool.java
│       │   │   ├── GetSweepStatusTool.java
│       │   │   ├── GetSweepInstructionsTool.java
│       │   │   ├── GetStatementLinesTool.java
│       │   │   ├── GetAuditTrailTool.java
│       │   │   └── GetRejectionCodesTool.java
│       │   └── write/
│       │       ├── PauseSweepRuleTool.java      # emits ActionCard (does not mutate)
│       │       └── SetBalanceAlertTool.java     # emits ActionCard (does not mutate)
│       │
│       ├── action/
│       │   ├── ActionCard.java                  # DTO: id, tool, params, summary
│       │   ├── ActionProposal.java              # @Entity (pending action, ttl)
│       │   ├── ActionProposalRepository.java
│       │   └── ActionExecutorService.java       # validates + dispatches to write tool
│       │
│       └── streaming/
│           └── TokenStreamer.java               # splits composed response into ~30ms chunks
│
└── entity/ai/
    └── BalanceAlert.java                        # @Entity for SetBalanceAlertTool target
```

### Backend (modified files)

```
backend/src/main/resources/application.yml
  + vam.ai.copilot.enabled, .stream-token-delay-ms, .conversation.*

backend/src/main/java/com/bank/vam/config/SecurityConfig.java
  + auth required for /api/ai/copilot/chat and /actions/**
```

### Frontend (new files)

```
frontend/src/
├── ai/
│   └── copilot/
│       ├── CopilotProvider.tsx                  # React context, SSE client, conversation cache
│       ├── useCopilot.ts                        # hook: open/close, send, messages
│       ├── CopilotDrawer.tsx                    # Sheet anchored bottom-right
│       ├── CopilotLauncher.tsx                  # FAB (Sparkles icon, gold accent)
│       ├── components/
│       │   ├── MessageList.tsx
│       │   ├── UserMessage.tsx
│       │   ├── AssistantMessage.tsx             # streams tokens, renders markdown
│       │   ├── ToolCallRow.tsx                  # collapsible "Looked up 47 accounts"
│       │   ├── ActionCard.tsx                   # confirm-to-execute write action
│       │   ├── Composer.tsx                     # textarea + send + Cmd+Enter
│       │   └── SuggestedPrompts.tsx             # 6 starter prompts mapped to known intents
│       └── types.ts                             # ChatMessage, ToolCall, Action
└── services/
    └── copilotApi.ts                            # POST /chat (EventSource), GET /conversations, POST /actions/:id/execute
```

### Frontend (modified files)

```
frontend/src/App.tsx
  + wrap children in <CopilotProvider> (inside ThemeProvider, inside MarketProvider)

frontend/src/components/layout/Layout.tsx
  + render <CopilotLauncher /> in main wrapper
  + render <CopilotDrawer /> at root

frontend/src/styles/index.css
  + .copilot-drawer utility class for backdrop blur + dark variants
```

### Database (auto-DDL via JPA `ddl-auto: update`)
Tables created automatically on startup:
- `copilot_conversation`
- `copilot_message`
- `action_proposal`
- `balance_alert`

If we promote to v1, fold into Flyway migration `V5__ai_copilot.sql`.

---

## 4. Intent catalogue (the stub's "brain")

Each intent has: trigger keywords/regex, slots to extract, tools to call, response template.

| # | Intent | Trigger examples | Slots | Tools called | Template output |
|---|--------|-----------------|-------|-------------|-----------------|
| 1 | `GET_POSITION` | "what's our {ccy} position", "total {ccy} balance", "how much {ccy} do we have" | `currency` | `GetPositionTool` | Total + breakdown by entity, formatted in market locale |
| 2 | `GET_POSITION_BY_BANK` | "{ccy} position by bank", "where is our {ccy}" | `currency` | `GetPositionTool`, `MultiBankLiquidityViewService` | Table grouped by bank, with mirror freshness |
| 3 | `FAILED_SWEEPS` | "failed sweeps", "sweep rejects", "what sweeps failed" | `since` (default 24h) | `GetSweepInstructionsTool(status=REJECTED)` | Table of failed instructions with rule + reason |
| 4 | `EXPLAIN_REJECTION` | "why did {id} fail", "explain rejection {code}" | `instructionId` or `code` | `GetSweepInstructionsTool`, `GetRejectionCodesTool` | Reason + recoverable/unrecoverable + recommended next step |
| 5 | `IDLE_ACCOUNTS` | "idle accounts", "accounts with no activity", "stale balances" | `days` (default 7) | `GetAccountsTool`, `GetStatementLinesTool` | List of accounts with no outflow > N days, sorted by balance |
| 6 | `RECENT_ACTIVITY` | "what happened today", "recent activity", "show me audit" | `since` (default today) | `GetAuditTrailTool` | Timeline of last N events |
| 7 | `LIST_RULES` | "list sweep rules", "show me rules", "what rules are active" | `status` (optional) | `GetSweepStatusTool` | Table of rules + last run + next run |
| 8 | `STATEMENT_SUMMARY` | "today's statement", "show me {ccy} statement", "camt 053" | `currency`, `date` | `GetStatementLinesTool` | Inflow/outflow totals + line count |
| 9 | `PAUSE_RULE` | "pause rule {id}", "stop sweep {id}", "disable {id}" | `ruleId` | `PauseSweepRuleTool` | **Action card** (Confirm/Cancel) |
| 10 | `SET_ALERT` | "alert me if {account} {below\|above} {amount}", "notify when {account} drops below {amount}" | `accountId`, `direction`, `amount` | `SetBalanceAlertTool` | **Action card** (Confirm/Cancel) |
| 11 | `GREETING` | "hi", "hello", "what can you do" | — | — | Capability list + suggested prompts |
| 12 | `UNKNOWN` | (fallback) | — | — | Polite refusal + 6 suggested prompts |

**Slot extraction strategy**:
- `currency`: `\b(AED|USD|EUR|GBP|SAR|SGD)\b` — case-insensitive
- `ruleId`: `\b(SR-\d+)\b`
- `instructionId`: `\b(SI-\d+)\b`
- `amount`: `\d[\d,]*(?:\.\d+)?\s?(?:m|million|k|thousand)?` → normalised
- `since`: "today" → 0d, "yesterday" → 1d, "last 24 hours" → 24h, etc.
- `days`: `(\d+)\s+days?`

**Intent matching priority**: longest keyword match wins; if multiple intents tie, action intents (PAUSE_RULE, SET_ALERT) outrank read intents.

---

## 5. Phased delivery

### Phase P1 — Backend skeleton (2 days)
- [ ] P1.1 Add `CopilotProperties`, `application.yml` keys, feature flag
- [ ] P1.2 Create `CopilotController` with SSE endpoint that echoes a hardcoded response (proves wiring)
- [ ] P1.3 JPA entities: `CopilotConversation`, `CopilotMessage`, `ActionProposal`
- [ ] P1.4 `CopilotService.handleTurn()` skeleton — persists user message, returns hardcoded assistant reply
- [ ] P1.5 `TokenStreamer` — splits string into chunks, emits over SSE every `stream-token-delay-ms` (default 30ms)
- [ ] **Verify**: curl the SSE endpoint, get streamed tokens, see conversations table populate

### Phase P2 — Read tools (3 days)
- [ ] P2.1 `GetAccountsTool` — wraps `VirtualAccountService` with filter params
- [ ] P2.2 `GetPositionTool` — wraps `BalanceAggregationServiceEnhanced` + `MultiBankLiquidityViewService`
- [ ] P2.3 `GetSweepStatusTool` — reads `SweepRule` repo
- [ ] P2.4 `GetSweepInstructionsTool` — reads `SweepInstruction` lifecycle, filters by status/date
- [ ] P2.5 `GetStatementLinesTool` — reads `Iso20022StatementService`
- [ ] P2.6 `GetAuditTrailTool` — reads `AuditLogService`, last N events
- [ ] P2.7 `GetRejectionCodesTool` — reads `RejectionCodeRegistry`
- [ ] P2.8 `ToolResult` shape uniform across all tools so templates can interpolate consistently
- [ ] **Verify**: integration test per tool — runs against seeded data, asserts response shape and corporate scoping

### Phase P3 — Intent router + response composer (2 days)
- [ ] P3.1 `Intent` enum + `IntentRouter` with regex/keyword matchers per intent
- [ ] P3.2 Slot extractors (currency, ruleId, instructionId, amount, date)
- [ ] P3.3 `ResponseComposer` — loads `.md` template by intent, interpolates `{{slots}}` and tool outputs
- [ ] P3.4 12 templates in `intent/templates/` (see §4 catalogue)
- [ ] P3.5 Wire `CopilotService` to: route → execute tools → compose → stream
- [ ] P3.6 Conversation context — last N messages stored, but stub does NOT use them (each turn is independent). Documented as known limitation.
- [ ] **Verify**: each of the 12 intents matched correctly via unit tests with 3-5 input phrasings each

### Phase P4 — Frontend drawer (3 days)
- [ ] P4.1 `CopilotProvider` context + `useCopilot` hook + SSE client in `copilotApi.ts`
- [ ] P4.2 `CopilotLauncher` FAB (Sparkles icon, gold accent, bottom-right with safe-area padding)
- [ ] P4.3 `CopilotDrawer` — Sheet anchored right, 420px wide, full height, backdrop blur
- [ ] P4.4 Message components: user (right-aligned navy bubble), assistant (left-aligned, markdown render), tool-call row (collapsed: "Looked up 47 accounts" → expand to JSON)
- [ ] P4.5 `Composer` with Cmd+Enter to send, auto-resize textarea
- [ ] P4.6 `SuggestedPrompts` — 6 starters seeded from `MarketProfile` (e.g. UAE → "What's our AED position?")
- [ ] P4.7 Dark-mode parity (test in both themes)
- [ ] **Verify**: end-to-end demo question rendered correctly, tokens stream, citations visible

### Phase P5 — Action mode (write tools) (2 days)
- [ ] P5.1 `PauseSweepRuleTool` — emits `ActionCard` with rule ID, summary, params
- [ ] P5.2 `SetBalanceAlertTool` — emits `ActionCard` with account, direction, amount
- [ ] P5.3 `ActionProposal` table stores pending actions with TTL (10 min)
- [ ] P5.4 `POST /api/ai/copilot/actions/{id}/execute` validates + dispatches the actual mutation
- [ ] P5.5 Frontend `ActionCard` component: title, summary, params table, Confirm + Cancel buttons. On confirm, call execute, show success toast, append "Action complete" message
- [ ] P5.6 Audit: every executed action writes to `AuditLogService` with source `COPILOT`
- [ ] **Verify**: "Pause sweep rule SR-101" → action card → confirm → rule status flips to PAUSED, audit row written

### Phase P6 — Polish & demo prep (1 day)
- [ ] P6.1 "Thinking" indicator (pulsing dot) before first token streams
- [ ] P6.2 Error states: tool failure, action validation failure
- [ ] P6.3 Empty state copy + suggested prompts polish
- [ ] P6.4 Brand: Fraunces "Treasury Copilot" header, navy gradient, gold sparkle
- [ ] P6.5 Demo script (see §7) practised end-to-end in UAE + UK profiles
- [ ] P6.6 README section: how to enable, list of supported intents, how to add a new one

**Total: ~13 working days (~2.5 weeks calendar with buffer).**

---

## 6. Response template example (so the shape is concrete)

`backend/src/main/resources/.../intent/templates/get_position.md`:

```
Your **{{currency}}** position across all entities is **{{position.totalFormatted}}**.

{{#if position.breakdown.length}}
By entity:

| Entity | Available | Committed |
|--------|----------:|----------:|
{{#each position.breakdown}}
| {{this.entityName}} | {{this.availableFormatted}} | {{this.committedFormatted}} |
{{/each}}
{{/if}}

{{#if position.staleAccounts}}
> ⚠️ {{position.staleAccounts}} account(s) have balances older than {{position.freshnessThresholdMinutes}} min.
> Refresh suggested.
{{/if}}

_Pulled from {{position.sourceCount}} account(s) at {{nowFormatted}}._
```

Renderer: simple Handlebars-ish syntax. Use [Jinjava](https://github.com/HubSpot/jinjava) (Apache 2, ~600 KB) or hand-roll a tiny mustache renderer (~80 LOC). Recommend hand-roll for zero deps.

---

## 7. Demo script (the bar for "done")

Run on the UAE profile with seeded data. Same script must work on UK profile.

| # | User question | Stub behaviour |
|---|---------------|----------------|
| 1 | "What's our total AED position across all entities?" | Matches `GET_POSITION` (slot ccy=AED). Calls `GetPositionTool`. Renders total + entity breakdown. |
| 2 | "Show me sweep rules that failed in the last 24 hours." | Matches `FAILED_SWEEPS`. Calls `GetSweepInstructionsTool(status=REJECTED, since=24h)`. Renders table. |
| 3 | "Why did sweep instruction SI-001 fail?" | Matches `EXPLAIN_REJECTION` (slot instructionId=SI-001). Calls `GetSweepInstructionsTool` + `GetRejectionCodesTool`. Renders explanation with recoverable/unrecoverable verdict. |
| 4 | "Which accounts have been idle for more than 7 days?" | Matches `IDLE_ACCOUNTS` (slot days=7). Calls `GetAccountsTool` + `GetStatementLinesTool`. Renders sorted list. |
| 5 | "Pause sweep rule SR-101." | Matches `PAUSE_RULE` (slot ruleId=SR-101). Emits `ActionCard`. User clicks Confirm. Rule status flips. Audit row created. |
| 6 | "Alert me if Mirror-NBD-AED drops below 2 million." | Matches `SET_ALERT` (slots accountId, direction=below, amount=2000000). Emits `ActionCard`. User confirms. `BalanceAlert` row created. |

Plus 3 negative-path / robustness checks:
- "What's the weather in Dubai?" → `UNKNOWN` intent → polite refusal + 6 clickable suggested prompts.
- "Hi" → `GREETING` → "I'm Treasury Copilot. I can help with: …" + suggested prompts.
- Click a suggested prompt → fills composer, sends, demo continues without typing.

---

## 8. Open questions (decide before P1 starts)

1. **Anchor location**: floating FAB bottom-right (current plan), or integrated into the header next to the dark-mode toggle? **Default: FAB — header is getting crowded.**
2. **Conversation persistence scope**: per-user, or per-(user × corporate)? **Default: per-(user × corporate).**
3. **Suggested prompts source**: hardcoded per-market in `MarketProfile.java`, or loaded from a `copilot_suggested_prompts` table? **Default: hardcoded for prototype, table later.**
4. **Token streaming delay**: 30ms feels alive, 50ms feels deliberate. **Default: 30ms, configurable via `vam.ai.copilot.stream-token-delay-ms`.**
5. **Slot extraction confidence**: when a user says "pause SR-101 and SR-102", do we (a) match only the first ID, (b) emit two action cards, or (c) refuse and ask to specify one? **Default: (a) for prototype — log a warning when multiple IDs detected.**

---

## 9. Risks & mitigations

| Risk | Mitigation |
|------|-----------|
| Stub feels rigid — user phrases something we didn't anticipate | Catalogue 3–5 phrasings per intent in unit tests; always show suggested prompts; fall back to UNKNOWN with a friendly list of capabilities |
| User accidentally executes destructive action | Action cards require explicit Confirm click; no keyboard shortcut; only PAUSE_RULE and SET_ALERT exist this phase |
| Multi-tenancy leak | Tools take `ToolContext` carrying authenticated principal + corporate; every repo call uses corporate-scoped finders. One unit test per tool asserting cross-corporate query returns empty. |
| Stub answers go stale when underlying schema changes | Tool integration tests cover all template field references; CI fails if a template references a field tool no longer returns |
| Demo question doesn't fit any intent | Suggested prompts always visible in empty state; clicking one fills composer — guarantees the demo works |
| Conversation table grows forever | `vam.ai.copilot.conversation.retention-days` (default 0 = forever for prototype). Add cleanup job before promoting to v1. |

---

## 10. Out-of-scope follow-ups (for v1 / later prototypes)

- **Real LLM integration** — replace `IntentRouter` + `ResponseComposer` with `LlmClient` (Anthropic/OpenAI). Rest of the stack unchanged.
- Multi-turn conversation memory (stub treats each turn independently)
- Voice input
- Document upload (paste a PDF statement, ask questions about it)
- Free-form natural language beyond the curated intent set
- Cross-corporate analytics for bank-side users
- Action types beyond pause/alert (create rule, modify pool, propose IHB sweep)
- Telemetry dashboard for AI usage
- Suggested-prompt personalisation based on user behaviour

---

## 11. Review section (filled in as work completes)

> Per CLAUDE.md §1.4 "Verification Before Done" — every phase ends with a verify step.

- [x] **P1 review** (2026-05-12):
  - **Shipped**: `CopilotProperties` + `application.yml` keys; `CopilotController` SSE endpoint at `POST /api/ai/copilot/chat` (event names: `meta` / `token` / `done`); `CopilotService` orchestrator returning a hardcoded P1 reply that acknowledges the user; `CopilotConversationService` split-out for `@Transactional` proxy correctness; JPA entities `CopilotConversation`, `CopilotMessage` (USER/ASSISTANT/TOOL roles, per-conversation `sequenceNumber`), `ActionProposal` (schema only — proposals not yet created until P5); repositories for all three; `TokenStreamer` with regex-based tokenisation that preserves whitespace and paces via Reactor `delayElements`.
  - **Files added** (12): `ai/copilot/CopilotProperties.java`, `CopilotService.java`, `CopilotConversationService.java`, `CopilotController.java`, `conversation/{CopilotConversation,CopilotMessage,CopilotConversationRepository,CopilotMessageRepository}.java`, `action/{ActionProposal,ActionProposalRepository}.java`, `dto/{ChatRequest,ChatChunkMeta,ChatChunkToken,ChatChunkDone}.java`, `streaming/TokenStreamer.java`. `application.yml` extended with `vam.ai.copilot.*`.
  - **Measured**: `mvn compile` clean on 334 source files (only pre-existing deprecation warnings). `mvn test -Dtest=TokenStreamerTest` — 6/6 pass, runtime 0.6 s.
  - **Verified live** (after backend restart): health endpoint returns expected payload; SSE stream emits 1 `meta` + N `token` + 1 `done` events; tokens paced at ~30ms; conversation listing endpoint returns history newest-first with `messageCount`; JPA auto-DDL created all three tables.
  - **Bug found & fixed during verify**: client disconnect mid-stream (e.g. `curl … | head -1`) caused the assistant message to be lost — the `Mono.fromCallable(persistAssistant)` in `concatWith` never fired on subscriber cancellation. Fix: persist the assistant reply *before* the token stream begins. Since the stub has the full text up front, this is safe. Future LLM mode will need `doFinally` accumulation instead. Verified post-fix: deliberately truncating the stream still persists both messages (count went 3 → 5).
  - **Learned**: (1) Spring's `@Transactional` proxy means persistence had to land in a separate `CopilotConversationService` bean — self-calls from `CopilotService` would bypass the proxy. (2) `reactor-test` is not on the classpath — tests use plain `Flux.collectList().block()` instead of `StepVerifier`. (3) Reactive concat with persistence-on-complete is fragile under client cancellation; persist eagerly when the data is available. (4) The Claude bash environment exports `TMP=/tmp` which breaks JDK 21's AF_UNIX pipe creation on Windows — backend must be started from a real Windows shell (PowerShell/cmd) where `TMP=C:\…\Temp`.
- [x] **P2 review** (2026-05-12):
  - **Shipped**: framework (`CopilotTool` interface, `ToolContext`, `ToolResult`, `ToolRegistry`) + 7 read tools that wrap real services and return template-friendly `data` maps. `ToolRegistry` auto-collects every `CopilotTool` bean on the classpath (drop-in extensibility) and builds `ToolContext` from `MarketProfileProperties`. Added two diagnostic endpoints (`GET /tools`, `POST /tools/{name}/execute`) so each tool is independently verifiable without the P3 IntentRouter.
  - **Files added** (12 main + Controller edits): `ai/copilot/tools/{CopilotTool,ToolContext,ToolResult,ToolRegistry}.java` + `tools/read/{GetAccountsTool, GetPositionTool, GetSweepStatusTool, GetSweepInstructionsTool, GetStatementLinesTool, GetAuditTrailTool, GetRejectionCodesTool}.java`. `CopilotController` gained `/tools` + `/tools/{name}/execute` plus a `ToolDescriptor` view record.
  - **Verified live** (curl against running backend):
    - `GET /tools` returns 7 registered tools with descriptions
    - `get_accounts` returned 77 AED accounts; `get_position` aggregated to 3,837,714.78 across 8 entities; `get_rejection_codes` returned the 17 seeded codes and resolved `AM04 → "Insufficient funds (RECOVERABLE)"`; `get_sweep_status` showed 7 active rules; `get_statement_lines`, `get_sweep_instructions`, `get_audit_trail` all returned correct empty-set shapes for the prototype data set; unknown tool returns 404 + standard error envelope.
  - **Bug found & fixed during verify**: 3 of 7 tools (`get_sweep_status`, `get_sweep_instructions`, `get_audit_trail`) crashed with a null-message NPE because they built the `filter` sub-map using `Map.of("status", statusFilter == null ? null : ..., "since", sinceParam)` — Java's `Map.of(...)` rejects any null value. Compile is silent on this. Fixed all 3 to use `LinkedHashMap` + `put(...)`. Lesson L3 captured.
  - **Note on serialisation**: project-wide Jackson is `default-property-inclusion: non_null`, so a `filter` map containing only nulls renders as `{}` even though the server-side map has the keys. Templates must treat absent fields as "no filter applied" rather than "key missing".
- [x] **P3 review** (2026-05-12):
  - **Shipped**: full intent stack — `Intent` enum (12 values), `Slot` key constants, `IntentMatch` record, `IntentRouter` (regex slot extractors + priority-ordered matching), `IntentExecutor` (intent→tool dispatch), `ResponseComposer` (per-intent markdown compose methods with locale-aware currency formatting). `CopilotService` rewired to `router → executor → composer` pipeline. Tool-call results serialised to `copilot_message.tool_calls` jsonb for audit + drawer expansion.
  - **Files added** (6 + 1 test): `ai/copilot/intent/{Intent,Slot,IntentMatch,IntentRouter,IntentExecutor,ResponseComposer}.java`, test `IntentRouterTest.java`. Modified `CopilotService.java` (replaced P1 echo with the routed pipeline).
  - **Design choice — programmatic vs external templates**: plan called for external `.md` Mustache templates + a tiny renderer. Chose programmatic Java compose methods instead for prototype simplicity (easier to debug, no parser to write). Trade-off: less flexible for non-engineer edits. Documented as a follow-up if template editing becomes a friction point.
  - **Verified**:
    - `mvn test` — 64/64 pass (58 IntentRouter + 6 TokenStreamer)
    - Live `/chat` smoke (`backend/target/copilot-smoke.py`) — **12/12 intents** match correctly and stream real data:
      - GREETING — capability list
      - GET_POSITION — "Your AED position across all entities is 3,837,714.78 AED (available: 4,841,556.82)"
      - GET_POSITION_BY_BANK — bank-grouped table with home bank flagged
      - LIST_RULES — "7 sweep rules (active: 7, paused: 0, disabled: 0)"
      - FAILED_SWEEPS — graceful empty state ("Good news — no failed sweep instructions ✅")
      - EXPLAIN_REJECTION — handles unknown id gracefully ("⚠️ No instruction found matching `SI-001`")
      - IDLE_ACCOUNTS — top-N by balance (heuristic, real query is P4 follow-up)
      - RECENT_ACTIVITY — empty-state message
      - STATEMENT_SUMMARY — credit/debit/net summary table
      - PAUSE_RULE — P5 action-card placeholder showing extracted ruleId
      - SET_ALERT — P5 placeholder showing extracted account, direction, amount
      - UNKNOWN — refusal + suggested prompts seeded from MarketProfile currency
  - **Bugs found & fixed**: (1) "Any stale balances?" was matching GET_POSITION because "balance" is a position keyword — fixed by reordering IDLE_ACCOUNTS check above GET_POSITION. (2) "USD across banks" fell to UNKNOWN because GET_POSITION_BY_BANK gated on `isPositionQuery AND isByBank` — relaxed to `isByBank` alone so bank-breakdown phrasing without "position" works. Both caught by tests before live verify.
  - **Known limitation**: IDLE_ACCOUNTS returns top-N by balance rather than truly idle accounts (no recent outflow). Real query needs a transaction-level join; tracked as P4 follow-up. The composed reply names this trade-off transparently.
  - **Learned**: locale-aware currency formatting via `MarketProfileProperties.getDefaultLocale()` works but the JDK's `en_AE` locale renders amounts with European separators ("3.837.714,78 AED" rather than "AED 3,837,714.78"). Defensible (en_AE is Arabic-locale-derived) but if it bothers UAE demo viewers, switch to a custom formatter that hardcodes `'#,##0.00'` patterns. Not blocking.
- [ ] **P4 review**: …
- [x] **P5 review** (2026-05-12):
  - **Shipped**: full action-card lifecycle. Two write tools (`pause_sweep_rule`, `set_balance_alert`) that create `ActionProposal` rows but never mutate; `ActionExecutorService` that dispatches on Confirm with idempotency + TTL + audit; `BalanceAlert` JPA entity for `SetBalanceAlertTool`'s persisted side-effect; two new endpoints `POST /actions/{id}/{execute|cancel}`; frontend `ActionCard.tsx` rendered inline with assistant replies, hitting the API on Confirm/Cancel with `react-hot-toast` feedback. `IntentExecutor`, `ResponseComposer`, `ToolContext` and `CopilotService` all rewired to thread the conversation id through write-tool invocations.
  - **Files added** (backend): `entity/ai/BalanceAlert.java`, `repository/ai/BalanceAlertRepository.java`, `ai/copilot/tools/write/PauseSweepRuleTool.java`, `ai/copilot/tools/write/SetBalanceAlertTool.java`, `ai/copilot/action/ActionExecutorService.java` (with `ExecutionResult` record). Modified: `ToolContext` (added `conversationId`), `ToolRegistry` (new overload), `IntentExecutor` (write-intent dispatch), `CopilotService` (passes conversation id), `ResponseComposer` (compose-from-tool-result for PAUSE/SET), `CopilotController` (action endpoints), `IntentRouter` (positional rule-ref regex).
  - **Files added** (frontend): `ai/copilot/components/ActionCard.tsx`. Modified: `types.ts` (`ActionProposal`, `ActionResult`), `services/copilotApi.ts` (`executeAction`, `cancelAction`), `components/AssistantMessage.tsx` (renders ActionCard from `data._action`).
  - **Verified live** with `backend/target/p5-smoke.py` — **17/17 checks pass**:
    - Happy path: chat → action card → Confirm → `IHB-MNC-UK-GBP` flips from ACTIVE to PAUSED, idempotency on 2nd Confirm
    - Cancel path: Cancel keeps rule ACTIVE; subsequent Confirm on the cancelled proposal returns `ok=false`
    - Error path: "Pause rule SR-999" → graceful failure, no action card
    - SET_ALERT: `VA-DUBAI-001` → BalanceAlert row created
    - Audit: 3 `COPILOT_ACTION_EXECUTED` rows with structured payloads (tool, params, conversationId, source=COPILOT)
  - **Bugs found & fixed**:
    1. **Slot regex captured the wrong token**. Positional regex `\b(?:rule|sweep|disable|pause|stop)\s+([A-Z][A-Z0-9_\-]{2,})\b` matched `pause` then captured `RULE` as the rule reference. Fixed by narrowing keyword alternation to just `(?:rule|sweep)` so only the *noun* (not the verb) drives the position. Lesson **L4** captured.
    2. **Seeded data uses non-canonical references**. Originally extracted only `SR-\d+`; the demo DB has `IHB-MNC-UK-GBP`, `IHB2026801173` etc. Added a positional fallback so users can refer to any of these by name.
  - **Known caveats**: `BalanceAlert` is persisted but no worker evaluates it against live balances (that's a v1 follow-up). `ActionProposal` rows never get cleaned up after EXECUTED/CANCELLED/EXPIRED — fine for prototype, want a janitor in v1.
- [x] **P6 review** (2026-05-12):
  - **Shipped**:
    - `/health` now reports `phase: P6` + `toolCount: 9` for frontend telemetry.
    - Drawer header subtitle humanises the matched intent (`get_position` → "Get Position") via a `humaniseIntent` helper.
    - **Deterministic currency formatter** in `ResponseComposer` — replaced `NumberFormat.getCurrencyInstance(Locale)` with `DecimalFormat("#,##0.00", Locale.US)` so amounts render `AED 3,837,714.78` instead of the JDK `en_AE` default of `3.837.714,78 AED`. Predictability over locale fidelity for the demo.
    - Suggested prompt for SET_ALERT now uses `VA-DUBAI-001` (a real seeded account) so the action card lights up out of the box rather than showing a graceful-but-disappointing "no account matched" error.
    - **`docs/copilot.md`** — 10-section reference covering: enable, architecture, all 12 intents + slot table, tools + diagnostic endpoint, action-card lifecycle, **how to add a new intent (7-step recipe)**, 11-action demo script, future LLM swap path, files map, test commands.
  - **Verified**:
    - `mvn test` — 64/64 still pass (no regressions on IntentRouter or TokenStreamer)
    - `/health` returns `{phase:"P6", toolCount:9, enabled:true, mode:"stub"}`
    - Live 12-intent smoke (`copilot-smoke.py`) — 12/12 match correctly with **`AED 3,837,714.78`** Anglo formatting
    - Audit memory across restarts: `LIST_RULES` shows "active: 6, paused: 1" reflecting the P5 smoke's pause of `IHB-MNC-UK-GBP`; `RECENT_ACTIVITY` shows 4 `COPILOT_ACTION_*` audit events
  - **Demo readiness**: the 11-step demo script in `docs/copilot.md` walks cleanly through every facet (FAB → drawer → suggested prompt → streaming → tool-call expansion → action card → Confirm → audit). UI works in dark + light, drawer survives navigation, conversation persists across page reloads (history fetchable via `GET /conversations`).
  - **Caveats noted in README**: BalanceAlert evaluator (v1), ActionProposal janitor (v1), IDLE_ACCOUNTS transaction-join (v1).

---

## Appendix A — `application.yml` additions

```yaml
vam:
  ai:
    copilot:
      enabled: ${VAM_AI_COPILOT_ENABLED:true}
      stream-token-delay-ms: 30
      action-proposal-ttl-minutes: 10
      conversation:
        retention-days: 0          # 0 = keep forever (prototype)
        message-history-window: 20 # not used by stub, reserved for future LLM
```

No API key, no provider config, no model selection. Ship-ready for offline demo.

---

## Appendix B — How the LLM swap will work later

When we promote, the only changes:

1. **New class** `LlmClient` (interface) + `AnthropicLlmClient` impl in `ai/copilot/llm/`
2. **New class** `LlmCopilotService` that uses `LlmClient` instead of `IntentRouter` + `ResponseComposer`
3. **Config flag** `vam.ai.copilot.mode: stub | llm` switches between them
4. **Tools, action cards, persistence, frontend** — zero changes
5. **Templates folder** repurposed as system-prompt examples / few-shot demos

This is the whole point of doing the stub first: every piece *around* the AI proves out, and the AI itself becomes a one-class swap when we're ready to spend on tokens.
