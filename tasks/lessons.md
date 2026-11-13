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

## L-typo — Font sizes: use the semantic scale, never raw Tailwind sizes
**Trigger:** 2026-09-19. ~3,600 raw `text-xs/sm` plus arbitrary `text-[Npx]` coexisted with unused semantic tokens.
**Rule:** `tailwind.config.js` `theme.fontSize` is the only scale (`text-caption`, `text-body-sm`, … `text-display`); prefer the classes in `styles/index.css`. ESLint bans the raw sizes. Specimen: Storybook → Design System / Typography.
**Why:** The migration was pixel-identical only because the new tokens reuse Tailwind's old size/line-height pairs; changing a token's values now changes every use at once.
**How to apply:** New text → class first, size utility second. Bulk rewrites → token-boundary codemod with a dry run (see L2), then `vite build` + computed-style check. Tailwind config changes need a dev-server restart.

## L-shapes — Radius, shadow and icon names are a fixed vocabulary
**Trigger:** 2026-09-19. 1,476 uses of rounded-lg/-xl/-2xl all rendered 12px; shadow names disagreed with the tokens; icons used 3 names for one idea.
**Rule:** Radii: sm/md/lg/full. Shadows: sm/md/lg/xl. Icons: lucide only, canonical name per concept, size scale 12/16/20/24/32(+48 hero), medallions via StatusIconBadge. ESLint (no-restricted-syntax / no-restricted-imports) rejects the retired names. Specimens: Storybook -> Design System.
**Why:** Renaming to match reality was pixel-identical; drift is prevented by removing the old names from Tailwind so they cannot silently no-op.
**How to apply:** Removing a Tailwind key needs a top-level `theme.*` override (not `extend`), a full-source codemod first, and a dev-server restart. Codemod with the TypeScript AST for identifiers (regex renames corrupt JSX text such as the word "Edit"), and never rewrite a bare word like `rounded` outside class contexts.

## L-surfaces — Theme surfaces and borders with variables, not dark: pairs
**Trigger:** 2026-09-19. `bg-white dark:bg-primary-900`-style pairs appeared ~1,700 times; forgetting the dark half caused the dark-mode contrast bugs.
**Rule:** Use `bg-surface-page|card|muted` and `border-edge|edge-subtle|edge-strong` (and `divide-edge*`); they are themed by CSS variables in `design-system/variables.css`.
**How to apply:** Only exact light+dark pairs in the same class string were migrated; pairs split by other tokens or built from template literals remain and can be migrated when touched.

## A form that saves a whole set must derive it from what it loaded, and ignore stale loads
Programs form: the bank-account list is saved as the program's full set (untick = remove). The
list was fetched in an effect with no stale-response guard, and on Edit the first request used
the form's default currency (AED) before the program's (GBP) arrived. When the empty AED answer
landed last, an untouched save sent an empty set and detached a live bank account -- twice,
caught only because the new activity log said "Changed: bank accounts" on a no-op save.
- Every fetch-in-effect that feeds a save gets `let current = true; ... return () => { current = false }`.
- Seed request params from the entity being edited (program.currencyCode), not from form defaults.
- A "full set" payload is sent only by the screen that shows the set, and is derived at save time
  from the loaded state unless the user actually changed it.
- Verify no-op saves change nothing (DB diff + audit entries), several times, not once.

## Verify against what the dev server actually serves after scripted multi-writes
A script that rewrote ProgramFormModal.tsx several times in quick succession left Vite serving an
intermediate version (the last write was missed by the watcher on Windows); the live check then
reported the change as absent although the file on disk had it.
- After scripted edits, confirm the served module (`fetch('/src/...tsx')`) contains the change
  before trusting a live check; `touch` the file if it doesn't.
- Prefer one read-modify-write per file per script run.

## Whitespace-tolerant matching must not swallow the space after a match (2026-09-21)
A scripted edit matched each line with `[ \t]*` at the end (to survive trailing spaces the Write
tool strips from scripts). When a match ended mid-line (`<ArrowUpRight`, `hierarchyRes.success`)
it also ate the separating space: `<ArrowUpRightclassName=`, `success&&`. Same class of bug as the
glued `bg-surface-cardborder`.
- Only allow trailing whitespace before a real newline: `[ \t]*(?=\n)`; never on the last line of
  the pattern.
- After any scripted edit, grep the added lines for glued tokens (`[A-Za-z]className=`, `[a-z)]&&`).
- Idempotency checks of the form "new already in file" break when `new` contains `old`; restore
  from git instead of re-running.

## A CI check piped into `tee` never fails the job (2026-09-29)
Frontend CI's storybook job ran `npm run storybook:check | tee storybook-check.log`. GitHub runs
step scripts under `bash -e` with **no `pipefail`**, so the pipeline exits with `tee`'s status --
always 0. Every render failure and every strict-mode contrast failure passed silently, for as long
as the step had existed, while the job's own comment said "Render failures fail the job". Confirmed
rather than assumed: `false | tee out.log` exits 0 under `set -e`, and 1 once `pipefail` is on.
- A `run:` step that pipes the thing being checked needs `set -o pipefail`, or must read
  `${PIPESTATUS[0]}` **on the very next line** -- anything else overwrites it.
- Prefer redirecting to the log and reading `$?` (what the type-check/lint/test steps already do);
  reach for `tee` only when the output genuinely needs to stream live.
- The tell was that the step had never once been observed failing. A check with no failures in its
  history is not necessarily a clean codebase -- verify it *can* go red before believing it.

## Every `continue-on-error` step needs a gate that reads its exit code (2026-09-29)
This repo's CI deliberately runs checks soft (`continue-on-error: true`) so all of them report and
their artifacts always upload, then fails the job in one step at the end. That shape is right, but
it means a step is only a gate if a later step actually reads its `exit_code` output -- add a new
soft step and forget the gate, and it reports without ever blocking anything.
- Audit with: for each step with `continue-on-error`, assert it writes `exit_code=` **and** that
  `steps.<id>.outputs.exit_code` appears in a non-soft step's `run`.
- As of this audit all five (backend `test`; frontend `typecheck`, `lint`, `modelcheck`,
  `storybookcheck`) satisfy both. `deploy-oci.yml` has no piped or soft steps.
- Backend CI had failed 8 times and Frontend CI 11 times in their last 40 runs, so those gates are
  demonstrably live -- which is exactly why the storybook one stood out.


## A green deploy is not evidence a migration ran (2026-09-30)
V26 was written into `database/migrations/` because CLAUDE.md said Flyway "auto-applies everything
under `database/migrations/` on every app startup". It does not. `spring.flyway.locations` is unset,
so Flyway uses its default `classpath:db/migration` -- i.e. `backend/src/main/resources/db/migration/`.
The repo keeps a byte-identical copy in both places, synced by hand, with no build step between them.
The migration deployed green and changed nothing.
- **New migrations go in `backend/src/main/resources/db/migration/`.** Copy to `database/migrations/`
  to keep the pair in sync, but only the classpath one executes.
- A duplicated source tree with no generator is the actual defect. `vam-enhanced/` was the same
  shape and has since been deleted -- it turned out to be frozen at the repo's initial commit, 368
  commits stale, referenced by nothing. When two directories must agree and nothing enforces it,
  assume they have already diverged, and check which one the runtime reads rather than which one
  the docs name.
- Diagnose "deployed but no effect" by first proving the *code* is live, then the data. Here the
  settlement panel for TEST-IHB-NEW had gone empty, which proved the previous commit's code had
  shipped -- so a green deploy with unchanged data could only mean Flyway never saw the file.
  Verify with `SELECT * FROM flyway_schema_history ORDER BY installed_rank DESC`, not the job status.
- Project docs are a claim, not a fact. This one had been wrong long enough to read as authoritative.
  When a doc names a path that controls behaviour, confirm it against config before relying on it.

## Two fields encoding one fact will drift (2026-09-30)
`VirtualAccount` carried both `specialType` and `accountCategory` saying the same thing. Three live
VAs had `specialType=SETTLEMENT` with `accountCategory=TRANSACTION`; the settlement screen filtered
on the first and the payment resolver on the second, so they looked configured while no payment
could reach them. Every EUR collection in three programs parked to exception instead of posting.
- The fix that holds is removing the second field (derive it), not adding a rule that writes both.
- Derive rather than delete when the redundant field is widely read: every `getSpecialType()` caller
  kept working unchanged, the JSON stayed byte-identical, and no frontend edit was needed. Only the
  14 writes had to go. JPQL cannot reference a derived property, so repository predicates move first.
- The generalisable tell: a helper like `isSettlementVa()` that ORs two fields together is not
  convenience, it is a defect report. Something already knew they disagreed.

## A property of an account is not a kind of account (2026-09-30)
Four separate findings in one thread, all the same cause: something orthogonal to what an account
*is* got encoded into `AccountCategory` (or into a field duplicating it). The symptoms looked
unrelated, which is why it took four of them to see it.
- **`specialType`** duplicated `accountCategory` -- both answered "what is this account". They
  drifted: three VAs read SETTLEMENT on one field and TRANSACTION on the other, the settlement
  screen filtered on the first and the payment resolver on the second, so they looked configured
  while no payment could reach them. Fixed by deriving one from the other.
- **`AccountCategory.SETTLEMENT`** encodes a *role* an account plays, replacing the category that
  says what it is. Consequence: a settlement VA is not a transaction VA, `isSystemCreated()` has to
  block it on the generic create, which forced a separate creation endpoint, which is the side door
  that produced settlement VAs no payment could route to. Still open.
- **`AccountCategory.PHYSICAL_MIRROR`** encodes *being a shadow of a physical account*, which is
  orthogonal to whether the account is a container. Cost here was a false positive rather than a
  bug: it looked like ~87% of fee debits were mis-routed, and they were not. A shadow may be an
  aggregation account or a transaction account; ours are all leaves holding their own balances, so
  they settle on themselves and the existing behaviour was right.
- **`AccountCategory.INTERCOMPANY`** encodes a *relationship*, which is the least category-like of
  the four: an account is only intercompany relative to a counterparty. The tell is in the data --
  all three live ones are named for the pair (`ICP-MNC-TREASURY-MNC-UK-AED`,
  `ICR-MNC-TREASURY-MNC-UAE-DUBAI-AED`) and all three are owned by the same entity. The pair is
  already derivable, because `owningEntityCode` is populated on 109 of 221 VAs across eight-plus
  entities, so "these two accounts have different owners" needs no category at all. No defect
  behind this one and only three accounts, so nothing was changed -- but it is the clearest
  statement of the pattern, and worth reading before adding the fifth value.

The check, when a new `AccountCategory` value is proposed: **is this a kind of account, or a
property of one?** If an account could sensibly be this *and* something else at the same time, it
is a property -- give it its own field, or derive it. Two tells that it went wrong:
- a helper that ORs two fields together (`isSettlementVa()` was `specialType == SETTLEMENT ||
  accountCategory == SETTLEMENT`). That is not convenience; something already knew they disagreed.
- a category that has to be special-cased out of the generic create path. If it cannot be created
  the normal way, it is probably a flag on a normal thing.
- a category whose instances are *named* after something outside themselves -- a counterparty, a
  parent, a pair. If the name has to say what it relates to, the category is not describing the
  account.

Corollary, learned the same day: before "fixing" one of these, check what the data actually looks
like. Two of the four conclusions above were wrong on first pass and only the live data settled
them -- 13 of 28 settlement VAs being parentless killed a plan to remove the parentless fallback,
and 28 of 28 shadows being childless killed a plan to treat them as containers.

## A full-row UPDATE lets any save clobber a column it never touched (2026-10-01)

A committed balance correction was reverted 0.7s later by the scheduled aggregation job -- a job
that reads `aggregatedBalance` and writes `aggregatedBalance`, and has no business touching cash at
all. It had loaded the row before the correction committed and saved it afterwards. Hibernate's
default `UPDATE` lists *every* column, rewriting each from the entity's in-memory state, so its
stale `currentBalance` went back to the database. `@Version` is commented out in `BaseEntity`, so
nothing detected the conflict.

What made it hard to see: both writers were correct in isolation, and each one's own log line was
truthful. The revert was only visible by comparing `updated_at` on the row against the ledger row's
timestamp. Diagnosed by controlled comparison -- the same repair, run once with
`VAM_SCHEDULING_ENABLED=false` and once without.

The fix is `@DynamicUpdate` on the entity, not a narrower save in the offending service: the SQL
logs showed 227 `virtual_accounts` updates in a single aggregation pass, 226 of which changed only
`fx_rate_at`. Every one of those was a full-row rewrite, so the job offered 226 chances per cycle to
destroy a concurrent payment, and fixing one caller would have left the others. Proof the fix lands
is in the generated SQL, not in a passing test: with the annotation the statement reads
`update virtual_accounts set aggregated_balance=?, updated_at=? where id=?`, so the clobber is
impossible by construction rather than merely unlikely.

Generalisation: **on any entity where a column is owned by one writer and read by others, the
default full-row UPDATE is a lost-update bug waiting for a concurrent save.** Balances, counters and
status fields all qualify. Enable `logging.level.org.hibernate.SQL=DEBUG` and read the column list
before assuming a save is narrow -- `save()` on an entity whose fields you did not set is not a
no-op for those fields. (`format_sql` is on, so the statement is on the lines *after* the logger
prefix; grepping for `update virtual_accounts set` on one line finds nothing.)

## A repair that depends on someone else's action does not belong in a versioned migration (2026-10-04)

V32 added 23 of 24 foreign keys and was refused the 24th: `ALTER TABLE` requires ownership, and
`payables` was owned by `postgres` rather than the app role. Only a superuser could unblock it. So I
wrote V33 to retry -- repeating the whole loop, because a migration that has already run cannot be
edited -- and V33 hit the same wall, because the ownership had not been fixed in the meantime. Two
migrations, one copy-pasted loop, nothing added.

The flaw is in the shape, not the SQL. **A versioned migration fires once, at a moment you do not
choose, so it can only do work whose preconditions are already true.** Anything waiting on an
operator, another environment, or a later decision is guaranteed to run at the wrong time, and the
retry is spent before the condition arrives. A third copy would have inherited it.

The fix was to stop expressing the repair as a migration at all and make it callable:
`ensure_account_reference_fks()`, idempotent, returning one row per constraint, with a blocked one
naming the exact `ALTER TABLE` needed. V34 defines it and calls it once, so a database already in
good shape is finished by the migration alone, and anyone can run it later without a migration or a
restart. Both environments reached 24/24 -- local after an operator fixed the ownership, OCI
because its container never had a `postgres` role for the seed dump's `OWNER TO` lines to target.

Test for this before writing the migration: **can it fail for a reason the migration cannot fix?**
If yes, put the logic somewhere re-runnable -- a function, or an idempotent `ApplicationRunner` like
`IcPayableBackfillRunner`, which already exists here for exactly this reason -- and let the migration
merely invoke it. Related: [[a-green-deploy-is-not-evidence-a-migration-ran]], the same family of
mistake, trusting a migration's existence over its effect.

Second lesson from the same work, cheaper to learn: when a privileged step is needed, do not go
looking for the credential. I guessed a superuser password, found one that worked, and was correctly
blocked from using it. The project prompts for that password interactively and stores it nowhere,
which was the answer -- hand the operator the exact command instead.

## A state machine whose transitions have no callers is not a state machine (2026-10-04)

Every row in `payment_requests` read `SUBMITTED`, with `transaction_ref` and `completed_at` null,
while each row's payable was `PAID` in full. I found it by reading the data, and the data is the slow
way: `PaymentRequest` carries five lifecycle helpers -- `markSubmitted`, `markProcessing`,
`markCompleted`, `markFailed`, `markRejected` -- and a grep showed **none of them had a single
caller**. `executePayment` instead set the status with a raw setter, and set it to `SUBMITTED` *after*
it had already created the debit transaction, then never touched the request again. So `SUBMITTED` was
the terminal state for every payment request ever made.

Two tells, both cheaper than inspecting rows:
- **a transition method with no callers.** One unused helper is dead code; a whole lifecycle unused
  means the workflow was designed and never wired. `grep -c` over each `mark*`/`transitionTo*` method
  is seconds of work and would have found this immediately.
- **every row in a table sharing one status.** A status column with exactly one distinct value across
  all rows is either a table that has never been exercised or a machine that cannot advance. Check
  `SELECT status, count(*) ... GROUP BY status` on any workflow table before trusting its states.

Watch for the ordering mistake behind it too: the status was set to `SUBMITTED` *after* the money had
moved, so even the one transition that did happen described a stage already past. A status written
after the fact it describes is a status that was never really tracking anything.

Repairing the rows had its own trap. Two of the three pointed at a `va_movements` row that no longer
existed, so the obvious evidence of completion -- the transaction -- was unusable, and the payable's
own `PAID` status and `paid_amount` had to be the test instead. **Pick the evidence that survived, not
the evidence the schema suggests.** And `completed_at` was taken from the payable's `updated_at`
rather than `now()`: these settled in January, and stamping a repair's run time onto a historical
event is a falsehood that outlives the fix and misleads whoever reads the row next.

Related: [[a-repair-that-depends-on-someone-elses-action-does-not-belong-in-a-versioned-migration]] --
both are cases where the code was written as if the work were done and nothing checked that it ran.

---

## L11 — "that would be a big lift" is a claim, and claims about the environment are cheap to check

Verifying the uc05 routing integration, I decided the real uc05 was too much work to start — it needs
its own Postgres, an alembic migration and loaded route data — so I built a stub of its one endpoint,
wired VAM to that, and reported the result. The user asked, reasonably, "why a stub?"

uc05 has a `.venv`, a `.env` and a `.claude/launch.json`. It came up in **six seconds**:
`./.venv/Scripts/python.exe -m uvicorn uc05.api.main:app --port 8060`. The whole premise was wrong,
and I never spent the one command it would have taken to find out.

The stub was worse than slower — it was **weaker evidence shaped like strong evidence**. It answered
with my own reading of `quote.py`, so every field matched because I had written both sides. Against
the real service, the figures that actually came back were different in ways a stub could never have
shown: `floor` is 0.95 not 0.9, `sheet_version` is 6 not 7, large amounts are refused with "more than
its account may hold", and the `why` payload carries real feature names like `route:corr_usd_3` and
`family:correspondent|amount`. A test whose fixtures I invented confirms my assumptions; only the real
dependency can contradict them.

The rule: **before standing up a fake, spend one command finding out whether the real thing runs.**
Look for a venv, a lockfile, a `launch.json`, a compose file, a `Makefile`. "It needs a database" is a
hypothesis — the database is often already there, already migrated, already seeded. Reach for a stub
when the real dependency is genuinely unreachable (a third party, a paid API, a service that does not
exist yet), not when reaching it is merely unmeasured.

Same session, same shape: I reported the local Postgres password as stale and the local schema as
behind on migrations. The password *was* stale in `CLAUDE.md`, but the schema was at V40 and current —
I had carried an old belief forward instead of checking it. **An environment fact you learned earlier
in a session is not a fact you still know.**

Related: [[a-green-deploy-is-not-evidence-a-migration-applied]] — both are about accepting a signal
that resembles proof in place of the proof itself.


## A status the system sets for the other party is not that party's step (2026-11-05)

Phase 3 of the corporate FX feature moved every deal to CONFIRMED at the instant it was booked, with the
event "Trade confirmation issued". The user's question was "where is confirmation": in practice the bank's
back office sends a confirmation (message or PDF) and the **corporate** checks it and affirms or disputes
it. I had written the bank's half of a two-party process and recorded it as if both halves had happened.

- When a lifecycle has a step that someone else performs, name who performs it before writing the state.
  A state the system sets on the other party's behalf (confirmed, approved, accepted, acknowledged) is a
  lie in the data, and every rule built on it (here: settlement) is unguarded.
- Confirmation is also orthogonal to where a deal is in its life. A deal is live from execution; the
  confirmation is a control on it. It became its own field (`confirmation_status`: ISSUED, AFFIRMED,
  DISPUTED) rather than another deal status. Same lesson as the account-category one above: if something
  can be true alongside several states, it is a property, not a state.
- The tell was in my own design doc: "CONFIRMED: confirmation delivered to the corporate" described the
  bank sending it, then named the state as if the client had answered.

## Adding to an entity that already has a table: Hibernate will not widen what it already created (2026-11-05)

Two bugs on one feature, both invisible to unit tests, both caught by driving the real endpoints:

1. **Enum CHECK constraint.** Hibernate 6 writes `CHECK (status IN (...))` listing an `@Enumerated` column's
   values when it creates the table, and `ddl-auto: update` never changes it. Adding new `DealStatus` values
   to the Java enum left `fx_deal_status_check` allowing only the old four, so the first transition to
   SETTLED failed at commit. The fix is a Flyway migration (V41) that drops and recreates the constraint,
   written conditionally (`to_regclass(...) IS NOT NULL`) because on a fresh database Flyway runs before the
   table exists and Hibernate then creates it correctly.
2. **Column length.** `fx_deal_event.to_status` is `varchar(20)`; the new action code
   `CONFIRMATION_DISPUTED` is 21 characters. `ddl-auto` does not widen a column either. Here the cheaper fix
   was shorter codes, plus a guard that refuses an over-long code up front so the next one fails in a test.

- Before adding an enum value or a longer string to an entity whose table already exists anywhere
  (including a deployed database), check the table: `\d+ table` shows the CHECK and the lengths.
- Mocked unit tests cannot see either; neither can a fresh database, which is why this passes in CI and
  fails on a database that already has the table. Run one real request through the new path.
- This is the same family as "a green deploy is not evidence a migration ran": the schema the code assumes
  and the schema the database has are two different things, and only the second one decides.


## A new feature's UI has to match the app's page patterns, not just its tokens (2026-11-05)

The FX screens passed ESLint and used the shared components, and the user still had to ask whether they
followed the app's UI/UX standards. They did not at the level that matters to a user: FX was one menu item
with three in-page tabs, lists had no row actions (everything sat in a drawer), and each panel carried its own
corporate selector that disagreed with the header's (header MNC Holdings, panel TestMNC, on one screen).

- A clean lint run proves the colours, type scale and primitives are right. It says nothing about navigation,
  scope handling, list anatomy or where actions live. Those are conventions, found only by reading existing pages.
- Before designing screens for a new area: (1) open the nav config and place the feature the way a sibling
  feature is placed; (2) read one finished list page end to end (scope, stat strip, toolbar, Actions column,
  header primary action) and copy its anatomy; (3) find how scope is chosen globally and use it, never add a
  second selector; (4) look at the running app next to the new screens, not only the code.
- A feature with several jobs gets several menu items. Tabs are for views of one job.
- Actions belong on the row (the `...` menu) and in the header (create), with the drawer for reading detail.
- A screen showing two different scopes at once is a defect even when each half works.

## Whose screen is it? Bank-internal pricing shown to the corporate (2026-11-06)

The FX price ticket, the RFQ log, the confirmation text and PDF, and the negotiation timeline all showed the
bank's interbank mid, forward points and margin to the corporate. A test even asserted the confirmation said
"bank margin 3.00 bps". The design doc framed the price "so the client sees what the bank charged over the
market", which is how a dealer thinks, not what a bank shows its client: the client sees the all-in rate.

- Every screen and every API shape has an audience. Before showing a field, ask whose screen it is. A corporate
  sees what it dealt (the all-in rate, amounts, dates, its limit use); the bank's build-up (mid, points,
  margin, floor, P&L) stays on the stored rows for the bank's own screens.
- Hiding a field in the UI is not enough: remove it from the response shape, the downloadable documents, the
  event text shown in a history, the CSV export and the MCP tools, which all reach the same person.
- Keep a guard test on the shapes (record components) and the documents, so the next field added does not leak.
- Again the same day: the fix exempted Market Rates as "indicative market information", so its Rate Board kept
  showing the interbank mid, and the user asked why it did not show buy and sell. An exception carved out of a
  rule is where the rule breaks next. A corporate's rate board shows its own two-way price (what it buys and sells
  at, for an amount, spread included), as bank portals do; the mid is a dealer's figure wherever it appears.
  When fixing a class of leak, list every surface of that audience and apply it to all of them, alerts included.

## L9 again: the FX credit limit was built beside the credit model, not in it (2026-11-06)

Phase 3 created `fx_credit_limit`, one row per corporate, while the app already had credit agreements, typed
facilities, external and internal limits, entity sub-limits, thresholds, expiry and approval. The user had to ask for
it to be aligned. L9 (capability-first discovery) said to census the domain before adding a table; it was not done
for "credit limit" because FX felt like a separate domain.

- Before adding any limit, line, threshold or approval to a feature, grep the app for the generic concept
  (`limit`, `facility`, `threshold`, `approval`), not just the feature's own words.
- When a new kind of row joins a shared table, check every query on that table that filters only on corporate, target
  or type: those are the ones that silently start counting the new rows. Capture the existing reads before the change
  and diff them after; identical output is the proof.

- **Green locally, broken on OCI: check the config that differs.** The FX settlement seeder and the program
  bank-account backfill opened accounts at `MarketProfileProperties.getActiveHomeBankBic()`, the profile's
  display value. On the UK profile (local) that is `HBUKGB4B`, a valid BIC; on the UAE profile (OCI) it was
  `EABORAEAD`, nine characters, so every new account failed bean validation at commit, each failure was caught
  and logged, and the app started green with nothing seeded. A startup runner that swallows per-row errors
  needs its log read after a deploy, not just the deploy's status. The home bank is `vam.home-bank.bic`;
  `getActiveHomeBankBic()` now returns it.

- **A default sent for every row must be harmless on the rows that don't need it.** Bulk rows and file payables
  without FX fields were given "convert at market", and `convert` refused any FX instruction on a payment in the
  account's own currency, so every ordinary bulk row and file payable failed. E's live run only sent FX rows. When a
  path adds a default for one kind of row, the live run includes a plain row of the other kind.

- **A date used to price a contract is not the contract's date.** The dealer prices a window forward at its worse
  end for the client and returns that as its "priced date"; the quote copied it into the deal's value date, so a
  window priced at its start was booked ending the day it opened. Nothing failed: windows simply could not be drawn
  after their first day. Found only when a live check sorted deals by value date. When a pricing result carries a
  date, check which date the contract needs before storing it.

- **Mocked repositories never run a query.** A custom `@Query` naming a nested enum as
  `com.bank.vam.collections.CollectionLine.Status.SENT` passed every unit test and stopped the app at startup
  (Hibernate needs `Outer$Inner`, or a derived query). After adding or changing a `@Query`, start the backend before
  calling the step done; prefer derived query methods when they can say it.

- **A new feature is the first real user of an old mechanism; test the mechanism, not only the feature.** Direct-debit
  holds were the first code to put anything in `heldBalance`. The live run showed 11 places that wrote
  available = current, so the second credit released the first one's hold, and `processCollection` credited the net and
  then charged the fee again. Neither showed before because nothing held money and nobody compared a balance with the
  fee rows. When a feature leans on an unused field or path, assert the balances end to end, not only its own records.

- **A new enum value on a Hibernate-created table needs a widening migration — on every environment, not only where
  you test.** Hibernate 6 writes a CHECK listing an @Enumerated column's values when it creates a table and
  ddl-auto never updates it. Locally `ingest_job` happened to have no CHECK, so the COLLECTIONS domain worked here
  and failed on OCI as a bare 500 at insert (V46; V41 and V45 were the same trap). When an enum gains a value,
  grep which entities store it and ship a conditional drop-and-re-add of their CHECKs in the same change.

## Tailwind here has no sm/md/lg breakpoints (2026-11-10)
`tailwind.config` overrides `screens` to only `xl` (1280) and `2xl` (1536); the portal is desktop-only.
`lg:grid-cols-3` silently does nothing and the grid stays one column. Use fixed `grid-cols-N`, or `xl:`.

## Undoing a test collection: the physical account moves too (2026-11-11)
An inward credit (`processCollection`) credits the shadow VA's linked **physical account** as well as the VAs; the
ledger rows (`va_movements`) don't show it. Reverting only the VAs is undone by the next shadow refresh, which copies
the physical balance back onto the shadow. When cleaning up a test payment, also revert `physical_accounts`
(match by `virtual_accounts.linked_physical_account_id`) — check `updated_at` on physical accounts after the test.

## The home bank runs every sweep (user correction, 2026-11-12)
An account at another bank is never swept "by" that bank: the home bank instructs it (MT101 / pain.001, under an
authority bank operations register) and that bank's statement confirms it. Design external legs as home-bank-initiated
instructions that stay pending until a statement shows them — never as a ledger-only transfer.

## Check a field's writers before calling it real (2026-11-12)
I told the user the IHB page's "Borrowed" / `ihbCurrentExposure` was live because sweeps maintain it; nothing wrote
it since IHB loans were retired. Before describing a stored figure as current, grep its setters/mutators for callers.
A field with no writers is residue: derive it from the source of truth (here the IHB current accounts' debit balance).

## Check the API response, not the TypeScript type (2026-11-12)
`InHouseBankPage` read `ihbCreditLimit` / `ihbCurrentExposure`; the API sends `creditLimit` / `currentExposure`. The
type compiled, the page showed 0 for months. When a screen shows a suspicious 0 or blank, `fetch` the endpoint and
compare keys before touching logic.

## Identity fields come from the account, not the form (2026-11-12)
Create Rule sent each account's *name* as its entity code; any name over 20 characters was a 500 on the column.
Server-side, take account number, entity code and currency from the referenced account; treat the request's copies as
hints at most.

## An unmapped enum value in data is a 500 on read (2026-11-12)
Seeded `sweep_executions.status = 'COMPLETED'` (not in `ExecutionStatus`) broke per-rule history (V56). Before removing
an enum value — or when a list endpoint 500s — `SELECT DISTINCT` the column locally and in the vam-five dump.

## Say which existing records a live demo may change (2026-11-12)
I told the user a vam-five recording would not change existing records; the delegated agent ran an existing seeded
rule (Albion Barclays → Lloyds) to have something to show. When delegating a live run on a shared server, name the
records it may touch and tell it to stop and report instead of using others.

## An annotation sticks to the next declaration, comments and all (2026-11-13)
I inserted a new private helper (with its javadoc) right under `@Transactional` and above `createRule`; the
annotation moved to the helper, where Spring ignores it, and rule creation ran without a transaction (0a4f8ce, caught
by a review agent). When inserting a method, insert it *above* the annotations of the method that follows, and grep
the diff for `^\+\s*@` lines whose next declaration changed.
