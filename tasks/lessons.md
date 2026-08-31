# Lessons

## L1 — Never infer "missing capability" from README/quickstart alone
**Trigger:** 2026-05-11. Asked to compare VAM Portal vs CashPro/Finacle LM. Read only README + SETUP_GUIDE + package.json + pom.xml, then claimed "no liquidity engine, no sweeping/pooling, no ISO 20022, no netting." User corrected — repo actually contains SweepService, NotionalPoolService, IhbUnifiedService, IhbSettlementService, NettingService, Iso20022StatementService, POBO/COBO, VIBAN routing, hierarchy aggregation, corporate-action wizards, etc.

**Rule:** Before claiming a capability is *absent* from a codebase, grep for it. A keyword sweep (`sweep|pool|notional|netting|iso20022|camt|pain|ihb|intercompany|liquidity|fx|interest|hierarchy`) takes one tool call and would have surfaced the entire `service/treasury/` and `iso20022/` packages.

**Why:** README files describe the headline pitch, not the actual surface area. The VAM Portal README led with escrow/KYCC/wallet/BaNCS — the actual code has 3× that scope. Trusting the README produces a confidently wrong answer that misrepresents the product.

**How to apply:**
- Capability comparisons / "what does this system do" questions → start with `Glob backend/**/service/**/*.java` + a Grep of the capability keywords, *then* read README for context.
- "Not implemented" claims require a negative-result Grep on file. "I didn't see it in the README" is not evidence of absence.
- For competitive comparisons, build an inventory of services + controllers + frontend pages first. The pages list alone (60+ pages here including NotionalPoolingPage, CashConcentrationPage, InHouseBankPage, NettingCyclesPage, Iso20022PaymentsPage) is a faster signal than prose docs.

## L2 — JSX-rewrite scripts must preserve attribute keywords, not just quoted values
**Trigger:** 2026-05-11. Wrote a `dedupe_dark.py` script to remove duplicate `dark:` Tailwind tokens. Its substitution function captured `className="(.*)"` and returned `f'"{new_inner}"'` — dropping the `className=` keyword and corrupting JSX in 4 files (`<div "...">` instead of `<div className="...">`). Required a separate repair script + 3 manual fixes.

**Rule:** When a regex substitution rewrites part of a *named-attribute pattern* (`className="..."`, `style={...}`, etc.), the substitution must reconstruct the full pattern, not just the inner value. Two safer alternatives:

1. **Match-narrowed substitution**: capture the value as one group and the surrounding template as another, then reassemble: `m.group(1) + new_inner + m.group(3)`.
2. **Restrict scope**: don't use a `className="..."` regex at all. Instead, match the bare `"..."` string literal and only substitute the literal — never the keyword.

**Why:** broken substitution that strips a keyword silently produces JSX that *looks plausible* but breaks the parser. The errors land in tsc as TS1003/TS1005 ("Identifier expected", "',' expected") which are confusing to triage, scattered across files, and slow to repair.

**How to apply:**
- Before running any rewriting script across many files, **dry-run on a single file first** and `grep` the output for the rewritten patterns to verify shape.
- Add a regression check: if any line in the output starts with `<[A-Za-z]+\s+["{]` (whitespace + quote/brace, no attribute name) — alert; that's the corruption signature.
- Idempotency: dark-variant additions must check by *family stem* (`dark:bg-success-500`), not exact match — otherwise `dark:bg-success-500/15` and `dark:bg-success-500/20` count as distinct and both end up in the className.
- Prefer to drive class transforms by extending Tailwind config (variants, plugins) where possible, reserving file rewrites for genuinely one-off seed work.

## L3 — `Map.of(...)` rejects null values; use `LinkedHashMap` for structured DTO sub-maps
**Trigger:** 2026-05-12. Built P2 Copilot read tools. Three of seven (`get_sweep_status`, `get_sweep_instructions`, `get_audit_trail`) crashed at runtime with `Failed to … : null` (a `NullPointerException` whose message was null). Cause: each was building its `filter` sub-map with `Map.of("status", statusFilter == null ? null : statusFilter.name(), "since", sinceParam)` — and Java's `Map.of(...)` factory throws `NullPointerException` on any null value. Compile is clean because the factory is `(K, V, K, V, …)` so the null is statically a legitimate `Object`.

**Rule:** Anywhere a map's *values* can be `null`, use `new LinkedHashMap<>()` + `put(...)`. Reserve `Map.of(...)` for constants and for keys/values you can statically prove non-null (parameter schemas, error envelopes, etc.).

**Why:** the NPE crashes at the call-site of `Map.of`, not where the null surfaced — making it look like an unrelated bug. And because `ToolResult.error("…: " + e.getMessage())` interpolates a null message, the operator-facing summary becomes the unhelpful `"Failed to list sweep rules: null"`. Bad combo.

**How to apply:**
- For Copilot tool `data` sub-maps: always `LinkedHashMap` (also preserves template iteration order, which `Map.of` does not).
- When a `Map.of` literal contains anything that could be `null`, swap it before commit. Search regex: `Map\.of\(.*\?\s*null\s*:`.
- When catching exceptions for user-facing summaries, fall back to `e.toString()` (which includes the class name) when `e.getMessage()` is null, so "Failed to …: java.lang.NullPointerException" beats "Failed to …: null".
- Note re Jackson + Spring: this project sets `spring.jackson.default-property-inclusion: non_null`, so even after the fix, a `filter` map containing only null values *serialises* as `{}`. Templates must treat absent fields as "no filter applied", not key-missing-from-data.

## L4 — Regex alternation captures the wrong token when keywords overlap
**Trigger:** 2026-05-12. Built P5 PAUSE_RULE flow. Wrote a positional regex to capture rule references after action keywords: `\b(?:rule|sweep|disable|pause|stop)\s+([A-Z][A-Z0-9_\-]{2,})\b`. Sent "Pause rule IHB-MNC-UK-GBP". The regex matched `pause` (the *first* keyword in the alternation), so the capture group greedily took the next code-like token — which was the literal word **"rule"** (4 uppercase-ish letters, length 4, matches `[A-Z][A-Z0-9_\-]{2,}`). The tool then looked up rule reference "RULE" and (correctly) returned "no sweep rule found". Demo broken even though IntentMatch.intent was correct.

**Rule:** When an alternation `(?:A|B|C)\s+(CAPTURE)` exists for a positional capture, every alternation member must be a token that *can only appear immediately before* the value you want — not a token that could itself be the value. If a verb like "pause" can precede *any* of {"rule", "sweep", "<ref>"}, the verb does not narrow the position enough.

**Why:** The pattern was meant to be a fallback for the strict `SR-\d+` form, but the alternation accidentally widened the trigger set so much that the first "word boundary" the regex saw was just any verb. Capture groups bind to the next match, not the most-specific one — there is no implicit "prefer longer reference".

**How to apply:**
- Narrow positional regexes to *position-defining* tokens only. Verbs like "pause", "stop", "disable" describe intent, not position; *nouns* like "rule", "sweep" describe position.
- When tempted to add a verb to a positional regex, write the test case `"verb noun X"` first and prove which token the capture group binds to. The fix here was deleting "pause", "stop", "disable" from the alternation — the verbs are already captured by the intent matcher upstream, the regex only needs to find the *immediately preceding* noun.
- For ambiguous code-like tokens with English false-positives, also consider blocklisting common words (`RULE|RULES|SWEEP|SWEEPS|ID|THE|ON|AT`) in the capture group via a negative lookahead — belt-and-braces if the position regex stays broad.

## L5 — Declared CSS variables do nothing unless the file is actually imported
**Trigger:** 2026-05-13. Phase 1 of the Design System Unification. The codebase had three CSS files — `swiss-minimalism.css` (448 lines), `design-system/variables.css` (372 lines, the "official" W3C-DTCG token system), and `styles/index.css` (1348 lines) — each with its own `:root { … }` token block. An external design review flagged "three competing token systems". The audit confirmed all three existed. But `swiss-minimalism.css` was not imported anywhere, and **`design-system/variables.css` was *also* not imported anywhere** — `main.tsx` only loaded `styles/index.css`. So although `variables.css` declared the canonical token set, none of its `--color-text-primary`, `--shadow-soft`, etc. were actually live at runtime. Only the `:root` block inside `index.css` was being applied to the DOM.

This was almost a serious bug: when I consolidated by *deleting* the duplicate `:root` from `index.css` and assuming `variables.css` would take over, the entire token system would have evaporated had I not paused to verify the load path. Build would still succeed (no syntax errors) but every component using `var(--shadow-sm)` etc. would silently fall back to the property's initial value (`none` for shadows). UI would render broken without any compile-time signal.

**Rule:** Before treating a CSS variables file as live, verify three things in order:
1. Does some `.tsx` / `.ts` entry point (typically `main.tsx`, `App.tsx`, or a styles index) `import` it?
2. Does some bundled `.css` file `@import` it?
3. Is it listed as a Vite/webpack/PostCSS entry?

If none of the above, the file is a spec doc, not a runtime artefact — *no matter how authoritative its comment header looks*. ("Auto-generated from design tokens" / "Do not edit directly — modify tokens.json instead" was the header on `variables.css` here, and it was a complete fiction; no generator was wired up either.)

**Why:** CSS variables silently fall back to their initial value when undefined. There is no warning, no build error, no Tailwind PurgeCSS complaint, no console message. The token system "looks live" because the file exists, the IDE auto-completes its variables, and `grep` finds it. The only way to confirm liveness is to follow the import graph from the actual bundler entry point. Documentation about how the system "should" work is unreliable evidence of how it does.

**How to apply:**
- When auditing a design-token / theming setup, run `grep -rn "import.*\.css\|@import" src/` from the actual entry point's directory. Treat anything not found in the resulting graph as effectively dead, even if it parses cleanly.
- When *consolidating* token sources, the migration must include a non-skippable step: "verify that the consolidated source is loaded by the entry point." For this project the fix was adding `@import '../design-system/variables.css';` at the top of `index.css` (which is the bundle entry via `main.tsx`).
- The same trap exists for JSON tokens like `tokens.json`. If the project uses Style Dictionary, Theo, or similar, verify the generator runs in the build — otherwise `tokens.json` is also just a spec doc.
- Header comments like "Auto-generated — do not edit directly" are a *claim*, not evidence. Trust the import graph.

## L6 — Auto-import insertion must understand multi-line `import { ... }` blocks
**Trigger:** 2026-05-13. Tier 3 of the Design System Unification — built a codemod (`scripts/codemod_status_icon_badge.py`) that migrates an inline JSX medallion pattern to a new `<StatusIconBadge>` component and adds the import if it's missing. The `ensure_import` fallback heuristic walked lines top-to-bottom looking for `import` lines, and inserted the new import after the first line that satisfied `line.startswith('import ') and not lines[i+1].startswith('import ')`. The intent was "insert at the end of the file's import block." For files like `CreditLimitsPage.tsx` whose first import is multi-line:

```tsx
import React, ... from 'react';
import {
  Building, Building2, Plus, ...
} from 'lucide-react';
```

…line 2 (`import {`) satisfies the start condition. Line 3 (`  Building, ...`) does NOT start with `import `, so the heuristic concluded the import block ended after line 2 and inserted:

```tsx
import {
import { StatusIconBadge } from '../components/ui';
  Building, Building2, ...
```

Build failure: `Expected "as" but found "{"`. Cascade: had to manually repair two files and rewrite the heuristic.

**Rule:** Any tool that auto-inserts imports must understand that a single TypeScript `import` statement can span many lines. The correct invariant is "insert after the line containing `from '<path>';`", not "insert after a line that starts with `import`." Walk imports forward, tracking the *last* line where a `from '...'` clause closes (with optional whitespace, optional trailing comment). Continuation lines inside `import { ... }` are part of the previous statement, not separate non-import lines.

**Why:** the bug is silent at codemod write-time — the regex matches correctly, the medallion pattern transforms cleanly, the only artifact is one wrong line inserted in the import block. The build error points at the import block, not at the codemod's logic, so triage takes minutes instead of seconds.

**How to apply:**
- Codemods touching imports: prefer to extend an existing `import { ... } from '<barrel>'` line rather than add a fresh import. Only add a fresh line if no barrel import exists. The danger codemod's `ensure_import` already does this for the common case; the fallback is the trap.
- For the fallback: scan lines, track `in_multiline = '{' in line and '}' not in line` for any line starting with `import`; on later lines, only close the multi-line when you see a line matching `\}\s+from\s+['\"][^'\"]+['\"]`. Insert after the *last* line that closes a `from` clause, not after the first line that matches `startswith('import ')`.
- Accept relative path forms in the "existing barrel" pattern. `'../components/ui'`, `'../ui'`, `'./ui'` all resolve to the same module — the regex matching for "already imported" should match any of them.
- Always dry-run a codemod that adds imports against at least one file with a multi-line lucide-react import before applying broadly. The lucide-react ~50-icon imports are the most common multi-line shape in this codebase and the most likely to expose the trap.

## L7 — Tailwind class-token substitutions need word-boundary anchoring on the suffix
**Trigger:** 2026-05-13. Phase 9 Task D form-label codemod had a replacement rule `'text-xs font-medium text-neutral-500 uppercase tracking-wide' → 'label'`. The source contained the longer suffix `tracking-wider` (Tailwind's widest-tracking variant). My codemod used Python `text.replace()` which matched the `tracking-wide` *prefix* of `tracking-wider`, leaving the trailing `r` stranded. Output: `labelr dark:text-neutral-400`. That class name doesn't exist in Tailwind, so 8 sites silently rendered with no styling — same fall-into-the-cracks shape as Tier 1's `danger-*` and Tier 3's `dark:bg-X-500/100/20` corruptions.

**Rule:** Any codemod doing string-level Tailwind class substitution must treat class names as *whole tokens*, not free strings. The substitution must anchor on a non-word boundary on both ends — if the search key ends in a letter or digit (and Tailwind class names typically do), the substring match will eat the suffix of any *longer* class with the same prefix. The classic instances in Tailwind:

- `tracking-wide` is a prefix of `tracking-wider` and `tracking-widest`.
- `text-base` is a prefix of `text-baseline` (rare but possible utility variants).
- `border` is a prefix of `border-2`, `border-t`, `border-neutral-200`, …
- `rounded` is a prefix of `rounded-md`, `rounded-lg`, …
- `font-bold` is NOT a prefix of any standard class — but `font-medium` shares prefix `font-m…`.

**Why this is silent:** Tailwind's JIT compiler quietly drops unknown class names. The browser sees `class="labelr dark:text-neutral-400"`, finds no `.labelr` rule, applies nothing. The element renders with default browser styles. No console error. No build error. Spot-checks pass because most elements *look approximately right* under default browser typography. The regression survived for the duration of the PR because nobody scrolled to those specific sites in the right theme.

**How to apply:**
- Always use a regex with `\b` (word boundary) on both ends when doing substring replacement against Tailwind class strings:
  ```python
  pattern = re.compile(r'\btext-xs font-medium text-neutral-500 uppercase tracking-wide\b')
  ```
  But note: `\b` checks word-character boundary, and `-` is *not* a word character — `\btracking-wide\b` matches *inside* `tracking-wider` because the `\b` after `wide` is satisfied by the `r` (word→word transition is fine, the boundary is between *word* and *non-word*). Wait — actually `\b` matches between a word char and a non-word char. So after `e` in `wide`, if the next char is `r` (word), there's NO boundary → `\b` does not match → the regex won't match. So `\b` DOES help here. The Python `text.replace()` is what was wrong; switching to `re.sub(r'\b…\b', …)` would have caught it.
- Concrete check: before applying a codemod, write a small test that includes the substring of interest *embedded inside a longer Tailwind class*. The Phase 9 Task D test should have included a sample like `"tracking-wider dark:text-neutral-400"` — that single case would have caught the bug.
- After every codemod that rewrites class strings, grep the output for the resulting tokens that aren't defined utilities. `grep -rn "labelr\|fieldlabel\|stat-valuex\|…" src/` — any not-a-real-utility name surfaces a regression.
- If multiple substitution rules might overlap (e.g. `tracking-wide` and `tracking-wider`), order them from *longest match first* and use whole-word anchoring so the longer match wins.
- Same family as L2 ("JSX-rewrite scripts must preserve attribute keywords") and L6 ("auto-import insertion must handle multi-line imports"): codemods need to think token-aware, not character-aware.

## L8 — Seeding a column mapped to a JPA `@Enumerated` enum: only use existing constants
**Trigger:** 2026-05-16. Phase-4 Simulator seed `sim_phase4_sync_log_seed.sql` set `physical_accounts.data_source` to `'OPEN_BANKING'` / `'SWIFT_MT942'` to give accounts realistic external feeds. `PhysicalAccount.dataSource` is `@Enumerated(EnumType.STRING) DataSource` whose constants are `CORE_BANKING, INTERNAL_API, OPEN_BANKING_PSD2/UAE/UK/KSA, SWIFT_MT940, SWIFT_MT942, SWIFT_CAMT053/052` — there is **no bare `OPEN_BANKING`**. Hibernate then threw `IllegalArgumentException: No enum constant …DataSource.OPEN_BANKING` on *every* `/physical-accounts` read whose page included a broken row → app-wide HTTP 500 (not just the new corporate). Self-caught during the browser pass via the console warning `[simulator] score inventory/tariffs unavailable` + a curl that 500'd for a *known-good* corporate too.

**Rule:** Before a seed/migration writes string values into a column that the JPA entity maps with `@Enumerated(EnumType.STRING)` (or an `@Convert`er), open the entity, read the enum, and use ONLY those exact constants. A value the enum can't resolve corrupts *reads* for any query that touches the row — the blast radius is every consumer of that table, not the feature you're building.

**Why:** DB-level the column is just `varchar`, so the bad INSERT/UPDATE succeeds silently. The failure surfaces later, app-wide, as a 500 on read — far from the seed, easy to misattribute to the feature under test.

**How to apply:**
- Seeding/altering an enum-backed column → `Grep "enum <Name>" the entity` first; map your domain values to real constants (here: UK→`OPEN_BANKING_UK`, EU→`OPEN_BANKING_PSD2`, SG→`SWIFT_MT940`).
- After applying a data seed, smoke-test a **known-good, unrelated** record through the API, not just the new one — an app-wide regression shows up there too.
- Keep the three copies in lockstep: live DB, the seed file (fresh-bootstrap), and any frontend set that enumerates the same values (`sourceQuality.ts` EXTERNAL set here).
- Same family as L1 (grep before asserting) and the codemod lessons: when a value crosses a typed boundary, verify it against the type's definition, not just that "it's a string".

## L9 — Capability-first discovery: enumerate schema + API + functions before proposing new tables
**Trigger:** 2026-05-16. An architectural review found the Simulator build prompts re-invented `bank_fee_tariff` (already = `charge_configurations` + `program_charge_overrides` + `calculated_charges` + `calculate_charge_amount()` + `taxChargeApi.calculateCharges()`), re-invented tax-leakage logic (already = `tax_configurations`/`tax_jurisdictions`/`taxChargeApi.calculateTax()`), and left a notional-pooling-shaped hole (the platform already models `notional_pools`/`pool_members` over Shadow VAs). Root causes: (1) scope-limited schema reading — read for *specific* concerns, stopped at first matches, never enumerated all 65 tables; (2) read column types but skipped `COMMENT ON COLUMN/TABLE` prose (e.g. "EXTERNAL (bank rates) or INTERNAL (treasury rates)" describes the entire dual-interest model); (3) designed the data model before surveying `services/api.ts` exports — a one-line `taxChargeApi.calculateCharges` obviated a whole proposed table.

**Rule:** Before any build prompt/plan that proposes a NEW table, service, or "missing capability", do an explicit capability census for the domain and write it into the plan's "Read first": (a) every `schema.sql` table whose name/columns touch the domain (+ read their `COMMENT`s, not just types), (b) every `services/api.ts` exported surface for the domain, (c) every PL/pgSQL function matching the domain. The plan then states verbatim "REUSING: X, Y, Z" and "ADDING ONLY: A, because <reason a generic alternative is insufficient>". A new table is the last resort, justified in prose.

**Why:** The platform's model is sophisticated, not generic. Treating it generically reinvents subsystems (wasted build + a deprecation/migration debt) AND misses primitives (the simulator scored concentration but not pooling — understating real benefit, which makes a treasurer reject good structures: worse than over-estimating).

**Also (corollary — verify the remediation too):** even a remediation plan must run the data check. This review prescribed reading VA `effective_credit_rate/effective_debit_rate`, but recon showed `external_interest_config_id` 0/163, those rates 4/163, and PA has no credit/debit split at all — applying the fix blind would regress the score to ~0 for 97% of accounts. Same family as L1/L8: a capability/field "exists in schema" ≠ "is populated"; grep the COUNT, not just the column.

## L10 — Grep "No files found" is NOT proof of absence; use Glob/find for existence checks
**Trigger:** 2026-05-29. Asked to build T7 of a cash-forecast bundle. Early recon ran `Grep "ForecastEngine|ForecastRun|ForecastCategory|forecast_run"` against `backend/src`, got "No files found", concluded T2–T6 didn't exist, then wrote 8 entity files under a new `com.bank.vam.forecast.entity/` package. Two reads later — when a `Bash find -path "*forecast*"` showed the WHOLE `com.bank.vam.forecast.{domain,engine,orchestration,repository}` tree already populated — had to delete the 8 conflicting files and reconcile against the pre-existing scaffolding (different package names, different entity shapes, `@ManyToOne` vs bare-UUID FKs, table-name plural/singular inconsistencies). Lost ~30 minutes to the bad start.

**Rule:** For an "does X exist in the codebase?" check, run **two** tools: a `Glob` for path patterns AND a `Grep` for content patterns. Don't trust either signal alone. Specifically:
- An empty `Grep` result can mean (a) the pattern truly isn't there, (b) the pattern is there but the file is on the ignore-list / outside the searched path / inside `target/`, (c) ripgrep timed out (it has happened in this repo — V13 SQL grep timed out at 20s).
- An empty `Glob` result is a stronger negative signal because it works on the filesystem name index, not file contents.
- The right composite: `Glob "**/Forecast*.java"` → if it returns 0 files, you're safe to assume "doesn't exist". Then a follow-up `Grep` for usages.

**Why:** Building against a phantom blank slate when scaffolding already exists is the most expensive recon failure — every new file is a conflict you'll have to delete, and every new design decision is one you'll have to undo. The cost of the second tool call (Glob) is microscopic compared to the cost of writing 8 files against the wrong assumption. Same family as L1 (grep before claiming absence): make the negative-result check cheaper than the cost of being wrong.

**Also (corollary — for "Refactor in flight" situations):** the forecast scaffolding kept mutating mid-session (entities switching from `extending BaseEntity` to standalone; `LocalDateTime`→`OffsetDateTime`; `Long`→`Integer`; `@ManyToOne` ForecastCategory → bare `UUID categoryId`). Code that compiled at 18:30 didn't compile at 18:55 because background edits happened. Defensive move: **re-read every entity I'm about to consume** in the same tool batch as my Write, not in an earlier exploration phase. The Read-modify-Write sandwich is a half-second cheaper than a failed Edit + an "edited by linter" retry.
