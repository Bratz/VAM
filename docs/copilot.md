# Treasury Copilot — Stub Prototype

A floating chat drawer in **Aperture** that answers treasury questions and proposes gated write actions, grounded in **real data** from the application's services.

> *Aperture* is the user-facing product brand. *VAM* is the internal codename (package, schema, API paths). The Copilot is shipped *inside* Aperture; both share a single design system and the same authentication context.

> **Important**: this is a *stub-only* prototype. There is **no LLM connection**. Intent routing is regex-driven, response composition is per-intent Java code, and write actions land through `ActionExecutorService` after explicit user confirmation. The architecture is designed so a real LLM swaps in as a single-class change (`IntentRouter` + `ResponseComposer` → `LlmClient`) without touching tools, persistence, drawer, or action cards.

---

## 1. Enable / configure

The feature is enabled by default. Toggle via `application.yml`:

```yaml
vam:
  ai:
    copilot:
      enabled: ${VAM_AI_COPILOT_ENABLED:true}
      stream-token-delay-ms: 30          # SSE chunk pacing — "live typing" illusion
      action-proposal-ttl-minutes: 10    # Confirm window before proposals expire
      conversation:
        retention-days: 0                # 0 = keep forever (prototype default)
        message-history-window: 20       # reserved for future LLM context
```

No API keys, no external services. Runs offline.

**Health probe** — useful from the frontend to detect "Copilot offline" state:

```bash
curl http://localhost:8053/api/ai/copilot/health
```

Response:
```json
{
  "enabled": true,
  "mode": "stub",
  "phase": "P6",
  "streamTokenDelayMs": 30,
  "actionProposalTtlMinutes": 10,
  "toolCount": 9
}
```

---

## 2. Architecture at a glance

```
User message
   │
   ▼
IntentRouter     — regex slot extraction + priority-ordered intent matching
   │
   ▼
IntentExecutor   — maps intent → tool calls (read tools execute; write tools propose)
   │
   ▼
ResponseComposer — per-intent compose method renders markdown from tool results
   │
   ▼
CopilotService   — persists user msg + assistant msg + tool_calls jsonb,
                   then streams tokens via TokenStreamer over SSE
   │
   ▼
Frontend drawer  — renders markdown, expandable tool-call rows, action cards
```

State persists in three tables (auto-DDL):
- `copilot_conversation` — one row per chat thread
- `copilot_message` — user / assistant / tool messages with `sequence_number`
- `action_proposal` — pending Confirm/Cancel write actions

Write actions land in domain tables:
- `pause_sweep_rule` → updates `sweep_rules.status` to PAUSED
- `set_balance_alert` → inserts a row in `balance_alert`

Both write paths emit a `COPILOT_ACTION_EXECUTED` audit-log entry with full provenance.

---

## 3. Supported intents

12 intents, ordered roughly by frequency of use.

| Intent | Sample input | Tool(s) called | Returns |
|---|---|---|---|
| `GREETING` | "Hi", "What can you do" | — | Capability list with market-aware examples |
| `GET_POSITION` | "What's our AED position?" | `get_position` | Total + entity breakdown |
| `GET_POSITION_BY_BANK` | "AED position by bank" | `get_position` | Total + bank breakdown, home bank flagged |
| `LIST_RULES` | "List sweep rules" | `get_sweep_status` | Status histogram + rule table |
| `FAILED_SWEEPS` | "Failed sweeps in the last 24 hours" | `get_sweep_instructions` | Rejected instructions table |
| `EXPLAIN_REJECTION` | "Why did SI-001 fail?" | `get_sweep_instructions` + `get_rejection_codes` | Instruction context + code classification |
| `IDLE_ACCOUNTS` | "Idle accounts" | `get_accounts` | Top-N by balance (heuristic; full join is a v1 follow-up) |
| `RECENT_ACTIVITY` | "What happened today?" | `get_audit_trail` | Timeline of recent governance events |
| `STATEMENT_SUMMARY` | "Today's AED statement" | `get_statement_lines` | Inflow/outflow totals + recent entries |
| `PAUSE_RULE` | "Pause rule IHB-MNC-UAE-AED" | `pause_sweep_rule` (write) | Action card with Confirm/Cancel |
| `SET_ALERT` | "Alert me if VA-DUBAI-001 drops below 50000" | `set_balance_alert` (write) | Action card with Confirm/Cancel |
| `UNKNOWN` | "What's the weather?" | — | Polite refusal + 6 suggested prompts |

### Slot extraction (regex)

The router pulls these slots from input before matching intents:

| Slot | Pattern | Example |
|---|---|---|
| `currency` | `\b(AED\|USD\|EUR\|GBP\|SAR\|SGD\|…)\b` | "AED position" → `AED` |
| `ruleId` | `\b(SR-\d+)\b` or positional `\b(?:rule\|sweep)\s+([A-Z][A-Z0-9_\-]{2,})\b` | "rule IHB-MNC-UAE-AED" → `IHB-MNC-UAE-AED` |
| `instructionId` | `\b(SI-\d+)\b` | "SI-001" → `SI-001` |
| `amount` | `\b(\d[\d,]*(?:\.\d+)?)\s*(m\|million\|k\|thousand\|bn\|billion)?\b` | "2m" → `2000000.00` |
| `since` | "today" / "last 24 hours" / "7d" / "30m" | normalised to compact `Nh` / `Nd` |
| `direction` | "below" / "drops below" / "above" / "exceeds" | `below` or `above` |
| `accountRef` | `VA-…`, `Mirror-…`, IBAN | "VA-DUBAI-001" |

### Priority ordering

Write intents win when their gating slot is present. Idle-accounts is checked before position to avoid the word "balance" greedy-matching. Full order:

1. `PAUSE_RULE` (requires `ruleId`)
2. `SET_ALERT` (requires `amount`)
3. `EXPLAIN_REJECTION` (requires `instructionId` or `rejectionCode` + a verb)
4. `IDLE_ACCOUNTS`
5. `GET_POSITION_BY_BANK`
6. `GET_POSITION`
7. `FAILED_SWEEPS`
8. `RECENT_ACTIVITY`
9. `LIST_RULES`
10. `STATEMENT_SUMMARY`
11. `GREETING`
12. `UNKNOWN`

---

## 4. Tools (read + write)

Tools are `@Component`s implementing `CopilotTool`. The `ToolRegistry` auto-collects every implementation on the classpath — **adding a new tool is a single `@Component` away**.

| Tool | Mutating | Wraps | Returns |
|---|---|---|---|
| `get_accounts` | no | `VirtualAccountRepository` | Filtered VA list + status counts |
| `get_position` | no | `VirtualAccountRepository` + `HomeBankProperties` | Currency aggregate + entity/bank breakdown |
| `get_sweep_status` | no | `SweepRuleRepository` | Status histogram + rules table |
| `get_sweep_instructions` | no | `SweepInstructionRepository` | Single lookup or status/time-window query |
| `get_statement_lines` | no | `TransactionRepository` | Inflow/outflow totals + entries |
| `get_audit_trail` | no | `AuditLogRepository` | Recent events |
| `get_rejection_codes` | no | `RejectionCodeConfigRepository` + `RejectionCodeRegistry` | Single code or by category |
| `pause_sweep_rule` | yes | Creates `ActionProposal` row | Action card payload under `data._action` |
| `set_balance_alert` | yes | Creates `ActionProposal` row | Action card payload under `data._action` |

**Diagnostic endpoint** — invoke any tool directly:

```bash
# List all tools
curl http://localhost:8053/api/ai/copilot/tools

# Execute one tool
curl -X POST -H "Content-Type: application/json" \
  -d '{"currency":"AED","groupBy":"bank"}' \
  http://localhost:8053/api/ai/copilot/tools/get_position/execute
```

> Write tools fail at the diagnostic endpoint because they require a conversation scope — call them only via `/chat`.

---

## 5. Action cards & write actions

Write tools never mutate state. They:

1. Validate inputs (rule exists, account resolvable, etc.).
2. Create an `ActionProposal` row with status=`PENDING` and `expires_at = now + 10 min`.
3. Return a `ToolResult` whose `data._action` carries the action-card payload.

The frontend renders that as a Confirm/Cancel card next to the assistant reply.

**Confirm flow** — `POST /api/ai/copilot/actions/{id}/execute`:
- Checks the proposal is still PENDING and not expired (auto-marks EXPIRED if so).
- Dispatches to the right executor via `ActionExecutorService` (per-tool switch).
- Marks the proposal EXECUTED, records `executed_at` and `execution_result`.
- Writes a `COPILOT_ACTION_EXECUTED` audit-log row with `tool`, `params`, `conversationId`, `source=COPILOT`.

**Cancel flow** — `POST /api/ai/copilot/actions/{id}/cancel`:
- Marks proposal CANCELLED.
- Writes a `COPILOT_ACTION_CANCELLED` audit-log row.

**Idempotency** — a 2nd Confirm on an EXECUTED proposal returns the same success result without re-running the mutation.

---

## 6. How to add a new intent

**1. Add the enum value** in `intent/Intent.java`:
```java
SHOW_HIERARCHY,
```

**2. Add a matching rule** to `IntentRouter.chooseIntent()`:
```java
if (containsAny(lower, "hierarchy", "entity tree", "ownership chart")) {
    return Intent.SHOW_HIERARCHY;
}
```

**3. Map the intent to tool calls** in `IntentExecutor.execute()`:
```java
case SHOW_HIERARCHY -> List.of(
    runTool("get_hierarchy", ctx, paramsForHierarchy(match)));
```

**4. Implement the tool** as a new `@Component` under `tools/read/` (or `tools/write/`):
```java
@Component
@RequiredArgsConstructor
public class GetHierarchyTool implements CopilotTool {
    @Override public String name() { return "get_hierarchy"; }
    @Override public ToolResult execute(ToolContext ctx, Map<String,Object> params) {
        // pull data, return ToolResult.ok(...)
    }
    // …
}
```
No registry edit needed — `@Component` is enough; `ToolRegistry` auto-collects.

**5. Add a compose method** in `ResponseComposer.compose()` switch and a render helper:
```java
case SHOW_HIERARCHY -> composeHierarchy(toolResults);
```

**6. Add test phrasings** to `IntentRouterTest.java`:
```java
@ParameterizedTest @CsvSource({"Show entity hierarchy", "Ownership chart", "Entity tree"})
void showHierarchy(String input) {
    assertThat(router.match(input).intent()).isEqualTo(Intent.SHOW_HIERARCHY);
}
```

**7. (Optional) Surface in `SuggestedPrompts.tsx`** if it's a common starter.

Restart the backend. The new intent is live; the tool is callable via `/tools/get_hierarchy/execute`; the chat endpoint routes to it through the normal pipeline.

---

## 7. Demo script

A clean walkthrough that exercises every facet of the prototype.

| # | Action | What to point out |
|---|---|---|
| 1 | Click the **Treasury Copilot** FAB | Bottom-right, navy gradient + gold sparkle, on every page |
| 2 | Click "**AED position**" suggested prompt | User bubble appears; assistant message streams token-by-token (~30 ms); markdown table with home bank flagged |
| 3 | Expand the tool-call row beneath the answer | Real JSON from `get_position` — proves grounding |
| 4 | Type "**List sweep rules**" | Status histogram + rule table |
| 5 | Type "**Pause rule IHB-MNC-UAE-AED**" | Action card: title, expiry countdown, params table, Cancel + Confirm |
| 6 | Click **Confirm** | Spinner → green success row + toast; rule status flips to PAUSED |
| 7 | Re-run "**List sweep rules**" | Shows the rule is now PAUSED |
| 8 | Type "**Why did SI-001 fail?**" | Graceful "no instruction found" — system honest about empty data |
| 9 | Type "**What's the weather in Dubai?**" | Polite refusal with capability suggestions |
| 10 | Toggle dark mode (sun/moon in main header) | Every part of the drawer respects it |
| 11 | Click **New Conversation** in drawer header | Resets to suggested prompts |

For UK/EU/US demos, set `VAM_MARKET_PROFILE=UK` (etc.) and restart; the suggested prompts and currency display switch automatically.

---

## 8. Future v1 — real LLM swap

This stub is intentionally shaped so the LLM swap is one class:

1. New `LlmClient` interface + `AnthropicLlmClient` impl using Spring WebFlux's `WebClient`.
2. New `LlmCopilotService` that uses `LlmClient` instead of `IntentRouter` + `ResponseComposer`.
3. Config flag `vam.ai.copilot.mode: stub | llm` chooses between them.
4. Tools, action cards, persistence, frontend, audit — **zero changes**.
5. The 12 intent templates in `ResponseComposer` become few-shot examples in the system prompt.

The point of the stub-first approach: every piece around the AI proves out and is demo-ready, and the AI itself becomes a one-class swap when there's budget for tokens.

---

## 9. Files map

```
backend/src/main/java/com/bank/vam/ai/copilot/
├── CopilotController.java          # REST + SSE entrypoints
├── CopilotService.java             # Orchestrator
├── CopilotConversationService.java # @Transactional persistence
├── CopilotProperties.java          # @ConfigurationProperties
├── conversation/                   # @Entity CopilotConversation, CopilotMessage + repos
├── action/                         # @Entity ActionProposal + ActionExecutorService
├── intent/                         # IntentRouter, IntentExecutor, ResponseComposer
├── streaming/TokenStreamer.java    # Reactor Flux with delayElements
├── tools/
│   ├── CopilotTool.java            # interface
│   ├── ToolContext.java / ToolResult.java / ToolRegistry.java
│   ├── read/                       # 7 read tools
│   └── write/                      # 2 write tools (pause_sweep_rule, set_balance_alert)
└── dto/                            # ChatRequest, ChatChunk*

frontend/src/ai/copilot/
├── CopilotProvider.tsx             # Context + state machine
├── CopilotLauncher.tsx             # FAB
├── CopilotDrawer.tsx               # Headless UI sheet
├── types.ts
└── components/
    ├── MessageList.tsx / UserMessage.tsx / AssistantMessage.tsx
    ├── ToolCallRow.tsx             # Expandable JSON
    ├── ActionCard.tsx              # Confirm/Cancel
    ├── Composer.tsx                # Auto-resizing textarea
    └── SuggestedPrompts.tsx        # Market-aware starters

frontend/src/services/copilotApi.ts  # fetch+SSE streaming client
```

---

## 10. Tests

```bash
# Unit tests
cd backend && mvn test -Dtest=IntentRouterTest    # 58 phrasing + slot tests
cd backend && mvn test -Dtest=TokenStreamerTest   # 6 pacing / tokenisation tests

# Live smoke (requires backend on :8053 with seeded data)
python backend/target/copilot-smoke.py            # 12 intents
python backend/target/p5-smoke.py                 # 17-check action-card lifecycle
```
