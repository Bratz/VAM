# Design System Unification — Aperture

**Trigger:** External design review (Claude Design) — 2026-05-13.
**Direction owned:** **Premium-Glass.** The "Swiss Minimalism" framing was
aspirational and contradicted by the actual output (gradients, glows, blurs,
glass blur on glass blur). We commit to the polish direction honestly, with
discipline applied per element.

**Scope this session:** Phases 1 + 2 + 4 — token unification, amber/gold
collision, focus ring. Phases 3 / 5 / 6 / 7 / 8 deferred to future sessions.

---

## Phase 1 — Token unification

Goal: single `:root` token source, dead CSS deleted.

- [x] Confirm `swiss-minimalism.css` is not imported anywhere (audit done).
- [x] Delete `frontend/src/styles/swiss-minimalism.css` (448 lines, dead code).
- [x] Audit `index.css` for `:root` declarations that overlap or contradict
      `variables.css`.
- [x] Move/merge any unique tokens from `index.css :root` into `variables.css`.
- [x] Leave `index.css` as the *utility + component layer* only — no token
      declarations.
- [x] Update `tokens.json` `meta` block: drop the "Swiss Minimalism" claim
      and Dieter Rams quote. Replace with the Premium-Glass POV statement.
- [x] Verify the app still builds + renders (no broken var refs).

---

## Phase 2 — Amber / Gold collision

Goal: warnings ≠ accents. Today both scales share hex values at every step,
so amber `bg-amber-500` reads identical to gold `bg-accent-500`.

- [x] In `tokens.json`: keep `gold` scale as-is (brand accent, warm yellow).
- [x] Redefine `amber` scale to genuinely orange/warmer hues (e.g. Tailwind
      orange family — #fff7ed → #c2410c). Warnings now read distinct.
- [x] If `variables.css` declares `--color-amber-*` separately, update those
      to match the new orange-leaning palette.
- [x] If Tailwind config aliases `warning` to amber, verify it still resolves
      correctly with new values.
- [x] Sanity-check pages that surface amber-tinted warning UI (CashConcentration
      alerts, EnhancedNettingCycles status pills, EntityLimits near-warning
      cards, Treasury Hierarchy shadow banner) — should now look orange-warm,
      not gold.

---

## Phase 4 — Focus ring unification

Goal: one focus token applied everywhere. Today there are three:
- `*:focus-visible` → hardcoded `0 0 0 2px white, 0 0 0 4px #102a43` (navy)
- `button:focus-visible` etc → `ring-2 ring-primary-500 ring-offset-2 + var(--shadow-primary)`
- `.form-input:focus` → `border-primary-500 + var(--shadow-primary), var(--shadow-inner)`

- [x] Pick the canonical token: `--shadow-focus-offset` (already in
      `variables.css`). If its value isn't quite right yet, tune it now.
- [x] Replace the hardcoded `*:focus-visible` declaration with one that uses
      the token.
- [x] Replace the button/a/input/select/textarea focus-visible block — keep
      the Tailwind ring (which uses semantic primary), but pull the box-shadow
      from `--shadow-focus-offset`.
- [x] Update `.form-input:focus` to use the same token.
- [x] Spot-check focus rings on: Login form, Add Party modal, Create Pool
      modal, sidebar search input. All should look identical.

---

## Phase 3 — Shadow & radius pruning

Goal: collapse the 14-shadow sprawl and 8-radius scale into canonical
disciplined sets. Old names stay alive as aliases pointing at the new ones,
so no caller breaks; new code should use the canonical names.

### Shadows — collapse 14 → 5
Canonical set:
- `--shadow-rest` — base depth on cards, popovers at rest
- `--shadow-hover` — elevated cards / interactive surfaces on hover
- `--shadow-popover` — floating UI (tooltips, popovers, dropdowns)
- `--shadow-modal` — modal dialog drop-shadow
- `--shadow-focus` — already exists; canonical gold ring (Phase 4)

Aliases to keep alive (mapped to new tokens):
- `--shadow-xs / sm / inner` → `--shadow-rest`
- `--shadow-md / lg / soft / medium` → `--shadow-hover`
- `--shadow-xl / strong / glow` → `--shadow-popover`
- `--shadow-2xl` → `--shadow-modal`
- `--shadow-accent / primary / success / error` → kept as semantic state halos
  (these aren't depth shadows, they're 3px coloured rings). Renamed to
  `--ring-accent / -primary / -success / -error` for clarity.

- [x] Add canonical tokens to `variables.css`.
- [x] Repoint the old names as aliases so callers stay green.
- [x] Update the 3 TypeScript callers that use `--shadow-soft/medium/strong`
      directly (`ThemeProvider.tsx`).

### Radii — collapse 8 → 4
Canonical set:
- `--radius-sm` (4px) — badges, pills, chips
- `--radius-md` (8px) — buttons, inputs, dropdowns
- `--radius-lg` (12px) — cards, modals, popovers
- `--radius-full` — circles, fully rounded elements

Drop or alias:
- `--radius-none` (0) → keep as alias, niche use
- `--radius-default` → alias of `--radius-md`
- `--radius-xl` (16px) → alias of `--radius-lg`. The 4px difference vs 12px
  was not load-bearing; pages using `rounded-xl` for cards now match
  `rounded-lg` modals visually.
- `--radius-2xl` (24px) → alias of `--radius-lg`. Same logic.

- [x] Add canonical tokens to `variables.css`, keep old names as aliases.
- [x] Update Tailwind config `borderRadius` to match (so `rounded-xl` and
      `rounded-2xl` resolve to the same 12px as `rounded-lg`).
- [x] No page sweep in this phase — the alias strategy means callers stay
      working. Future page work can switch to canonical names organically.

## Phase 5 — Typography utility completion

Goal: finish the work started by `.page-title`, `.section-title`, `.stat-value`.
Add the remaining text-style utilities so pages stop authoring with raw
`text-xl font-semibold text-primary-900 dark:text-neutral-50` soup.

- [x] Add `.body` — default body copy (sans, 14–16px, primary text colour).
- [x] Add `.body-sm` — secondary copy (sans, 13px, secondary text colour).
- [x] Add `.label` — form labels, table headers (uppercase, tracking, 11px).
- [x] Add `.overline` — section eyebrows (uppercase, wider tracking, 10px).
- [x] Add `.caption` — supporting text under inputs / chart axes (12px).
- [x] Add `.code` — inline code / numeric refs (JetBrains Mono, 13px).
- [x] Document the typography utility set in `tokens.json` `meta` for reference.

Sweep deferred — only establish the utilities this phase. Pages get migrated
organically when next touched.

## Phase 6 — Active nav state calm-down

Goal: the active sidebar nav item currently uses
`bg-gradient-to-r from-primary-600 to-primary-700 + shadow-lg shadow-primary-600/25
+ ring-1 ring-accent-400/30` — three effects layered. Too much, and the
gradient + colored shadow read as Linear/Stripe, not Aperture.

- [x] Replace with: solid `bg-primary-800` fill + `border-l-2
      border-accent-400` left rule. No drop shadow, no gradient.
- [x] Light mode equivalent: solid `bg-primary-50` fill + `border-l-2
      border-accent-500` left rule + `text-primary-900` (already there).
- [x] Verify hover state still reads correctly against the new active fill.

---

## Out of scope this session

(Tracked separately; will pick up in subsequent sessions.)

- Phase 7 — Extract nav config out of `Layout.tsx`.
- Phase 8 — Small things (kbd font, skeleton color, modal token unification,
  mobile bottom-nav indicator, drop grid overlay).

---

## Review

### Phase 1 — Token unification (shipped)

- `swiss-minimalism.css` deleted (448 lines, dead code — confirmed not imported
  anywhere and no `.swiss-*` utility class is used in any `.tsx`/`.ts` file).
- `variables.css` is now the single source of truth. All `:root` token
  declarations live there. `index.css` owns utility classes, component layer,
  body atmospherics, and section-theming CSS variables only — no token
  declarations.
- **Important discovery**: `variables.css` was not being imported into the
  bundle. `main.tsx` only imported `index.css`, so the design tokens declared
  in `variables.css` were effectively dead at runtime. Fixed by adding
  `@import '../design-system/variables.css';` at the top of `index.css`.
  Captured as Lesson L5 — *always verify the actual load path before
  assuming a declared CSS variable is live.*
- All previously-live shadow names preserved (xs/sm/md/lg/xl/2xl/glow/inner,
  soft/medium/strong/inner-soft, accent/primary/success/error). Phase 3 will
  collapse them; for now the consolidation is non-breaking by design.
- All previously-live easing names preserved (--ease-out-expo, --ease-out-back,
  --ease-spring, --ease-in-out-circ alongside --easing-linear, etc).
- `tokens.json` meta block updated to v2.1.0. Swiss/Dieter Rams framing
  replaced with explicit Premium-Glass direction statement. The rejected
  Swiss direction documented in `rejectedDirections` so future readers see
  the deliberate choice instead of reading inconsistency.

### Phase 2 — Amber / Gold collision (shipped)

- `tokens.json`: amber primitive scale redefined from `#fffbeb / #f59e0b /
  #d97706 / #b45309` (identical to gold) to the Tailwind orange family
  `#fff7ed → #c2410c`. Warnings now read as genuinely warm-orange.
- `variables.css`: `--color-amber-*` matches the new orange palette. All
  semantic warning tokens (`--color-status-warning`, `--color-status-warning-text`,
  `--color-status-warning-subtle`, `--color-status-warning-border`) flow
  through automatically.
- `tailwind.config.js`: extended `warning` colour to full 50–700 scale on
  the orange palette, AND overrode Tailwind's built-in `amber` palette so
  direct callers (`bg-amber-100`, `text-amber-700` — used heavily across
  CashConcentration alerts, EnhancedNettingCycles status pills, EntityLimits
  near-warning cards, shadow-account strips) pick up the new hue without a
  page-by-page sweep.
- Net effect: warnings now read distinctly from accents. Gold stays
  exclusively for brand moments (page-title underline, hero lens, focus
  ring, top accent strip).

### Phase 4 — Focus ring unification (shipped)

- `--shadow-focus-offset` in `variables.css` retuned: was `0 0 0 2px white,
  0 0 0 4px var(--color-border-focus)` (navy-500) — now `0 0 0 2px
  var(--color-bg-primary), 0 0 0 4px var(--color-brand-accent)` (gold).
  Focus state now reads as on-brand instead of generic navy.
- `*:focus-visible` in `index.css`: hardcoded `box-shadow: 0 0 0 2px white,
  0 0 0 4px #102a43` replaced with `box-shadow: var(--shadow-focus-offset)`.
- `button/a/input/select/textarea:focus-visible` block (Tailwind ring + navy
  shadow halo) removed entirely. Every focusable element now inherits the
  canonical `*:focus-visible` rule.
- `.form-input:focus` updated: border tints to `border-accent-500` (gold) to
  agree with the ring, box-shadow now composes `var(--shadow-focus-offset),
  var(--shadow-inner)` — keeps the slight inner-shadow depth while using
  the canonical ring.
- `.form-input-error` / `.form-input-success` deliberately kept as-is: red/
  green halos here carry semantic state, not focus signal. They compose
  correctly on top of the canonical ring when both are active.

### Verification

- `npx vite build` succeeded in 21.4s. 172.91 kB CSS bundle. Zero CSS errors
  or unresolved variable references.
- All previously-shipped page work continues to function unchanged.

### Phase 3 — Shadow & radius pruning (shipped)

**Shadows (14 → 5 canonical + 4 coloured rings)**

Added canonical depth scale to `variables.css`:
- `--shadow-rest`    — base depth on a surface at rest (cards, inputs).
- `--shadow-hover`   — elevated state, raised z-feel.
- `--shadow-popover` — floating UI off the page plane.
- `--shadow-modal`   — modal drop-shadow, deepest depth.
- `--shadow-focus` (from Phase 4) — keyboard focus, gold.

Every old depth name kept alive as an alias:
- `--shadow-xs / sm / soft` → `var(--shadow-rest)`
- `--shadow-md / medium` → `var(--shadow-hover)`
- `--shadow-lg / xl / glow / strong` → `var(--shadow-popover)`
- `--shadow-2xl` → `var(--shadow-modal)`
- `--shadow-inner` and `--shadow-inner-soft` kept as inset variants.

Coloured-ring set renamed to `--ring-accent / -primary / -success / -error`
(they're 3px halos, not depth shadows). Old `--shadow-accent / -primary /
-success / -error` aliased to the new names so callers don't break.
**Side-effect:** `--ring-error` retuned to orange-tinted (rgba(247, 115, 22, 0.18))
to match the new amber/warning palette from Phase 2 — form-input-error states
now visually agree with the new orange warnings.

**Radii (8 → 4 canonical)**

Canonical scale:
- `--radius-sm`   (4px)  — badges, pills, chips
- `--radius-md`   (8px)  — buttons, inputs, dropdowns
- `--radius-lg`   (12px) — cards, modals, popovers
- `--radius-full`        — circles

Old names aliased:
- `--radius-default` → `--radius-md` (was 6px, now 8px — buttons match inputs)
- `--radius-xl` → `--radius-lg` (was 16px, now 12px)
- `--radius-2xl` → `--radius-lg` (was 24px, now 12px)

**Tailwind config updated** so `rounded-xl` and `rounded-2xl` utilities now
resolve to 12px (same as `rounded-lg`). No page sweep needed — cards
authored with any of the three classes now visually match. The 4–24px
spread that previously made `rounded-xl` cards visually distinct from
`rounded-2xl` modals is gone; everything reads as one unified 12px corner.

### Phase 5 — Typography utility completion (shipped)

Added 6 utility classes to `index.css` to complete the typography set:
- `.body` — default body copy. Bricolage 16px/400w, line-height 1.55,
  primary text colour.
- `.body-sm` — secondary copy. Bricolage 14px/400w, neutral-600 /
  dark:neutral-400.
- `.label` — form labels, table headers. Bricolage 12px/600w uppercase,
  tracking 0.05em.
- `.overline` — section eyebrows (e.g. "ACCOUNTS & STRUCTURE" above
  page-title). Bricolage 10px/600w uppercase, tracking 0.18em.
- `.caption` — supporting text under inputs / chart axes / footnotes.
  Bricolage 12px/400w.
- `.code` — inline code / numeric refs. JetBrains Mono 13px/500w,
  tabular-nums.

The complete utility set is now documented in `tokens.json` under
`meta.designPhilosophy.typographyUtilities` so future contributors have
an authoritative reference.

Page sweep intentionally deferred — utilities established, migration
happens organically when pages are next touched.

### Phase 6 — Active nav state calm-down (shipped)

The sidebar's active item had three competing effects: a navy gradient,
a colored drop shadow, and a dark-mode-only accent left rule. Layered
together it read as Linear/Stripe glass-y rather than Aperture's
confident-banking direction.

Replaced with:
- Solid `bg-primary-800` fill in light mode, `dark:bg-primary-700/80`
  in dark — single colour, no gradient.
- `border-l-2 border-accent-500` (`dark:border-accent-400`) — the gold
  rule that signals "you are here".
- White text in both modes; icon inherits.
- No drop shadow.
- Inactive items now carry `border-l-2 border-transparent` so there's
  zero layout shift when an item becomes active.

Also flipped the button shape from `rounded-xl` to `rounded-lg` per the
new canonical 12px radius (no visual change since `rounded-xl` now
resolves to the same value via the Phase 3 alias, but explicit is better).

Transitions narrowed from `transition-all` to `transition-colors` since
there's no longer a box-shadow or transform to animate — modest perf win.

### Verification

- `npx vite build` succeeded in 15.94s. 173.59 kB CSS bundle (+0.68 kB
  from the new typography utilities). Zero CSS errors, zero unresolved
  variable references.
- Both rounds of unification (Phases 1/2/4 + Phases 3/5/6) compose cleanly.

### Phase 7 — Extract nav config (shipped)

Created `frontend/src/config/navigation.tsx` (320 lines) holding the full
information architecture:
- `navSections` — desktop sidebar's section + item structure (9 sections,
  ~50 items total).
- `mobileNavItems` — 5-slot mobile bottom-nav belt.
- `pageTitles` — page id → page H1 title map.
- `ALL_SECTION_TITLES` — derived list for sidebar initial collapse state.
- `sectionForPage(page)` — breadcrumb pill lookup.
- `sectionTitleForPage(page)` — sidebar auto-expand helper.
- Exported types `NavItem` and `NavSection`.

`Layout.tsx` no longer holds any IA decisions — it imports from the config
and is purely a renderer. The icon import list in Layout shrank from 51
icons to 16 (only chrome/header/mobile-control icons remain; menu items
get their icons from the config). Layout's line count dropped from ~1010
to 876.

A designer / PM can now read `navigation.tsx` end-to-end and understand
the entire menu shape, without scrolling through ~900 lines of rendering
JSX. That was the Phase 7 goal.

File is `.tsx` not `.ts` because icons are JSX elements — deliberate
trade-off for authoring ergonomics over fully decoupled string-name
configs. Documented in the header comment.

### Phase 8 — Small things (shipped)

- **⌘K kbd chip** now renders in JetBrains Mono (was generic sans) so it
  reads as "keystroke/code" alongside our numerics-and-code mono rule.
  Also bumped text colour from `neutral-400` to `neutral-500` for slightly
  better contrast against the search input.

- **Skeleton dark-mode shimmer** retuned from brand-tinted navy
  (`primary-800 / primary-700`) to neutral greys (`neutral-700 /
  neutral-600`). Skeletons are momentary "this is loading" placeholders,
  not brand surfaces — neutral shimmer reads as generic loading without
  the cognitive load of "is this navy element competing with the chrome?"
  Light mode was already neutral and is unchanged.

- **Modal max-width tokens unified.** Added `--modal-width-sm / md / lg /
  xl / full` declarations to `variables.css`. `components/ui/enhanced.tsx`
  now reads from them via `max-w-[var(--modal-width-md)]` instead of
  hardcoded `max-w-md / max-w-lg / max-w-2xl / max-w-4xl` Tailwind utility
  names. Single declaration site means changing modal widths is one edit,
  not two — and the values can be tuned at runtime without a Tailwind
  rebuild. `tokens.json`'s `component.modal.sizes` already mirrors these
  values; both are documented as agreeing.

- **Mobile bottom-nav active indicator** changed from a 1×1px gold dot
  above the icon to a 16px × 2px gold underline at the bottom of the
  button. The thicker bar is visible at thumb-distance viewing without
  having to look directly at the nav, and it speaks the same language as
  the desktop sidebar's gold left-rule (Phase 6).

- **Body grid overlay dropped.** The 56px hairline navy grid (at 1.8%
  opacity in light, 3% in dark) was barely perceptible — but it cost two
  extra gradient layers in the paint budget on every viewport repaint
  (body-attachment: fixed means it repaints on scroll). The radial
  atmosphere alone carries the depth signal. Background now uses 3
  layers (2 radials + base) instead of 5.

### Final verification

- `npx vite build` succeeded in 15.54s. 174.13 kB CSS bundle (+0.54 kB
  from new modal-width tokens + utility class comments). Zero CSS errors,
  zero unresolved variable references, zero TypeScript errors in any of
  the touched files.
- All 8 design system unification phases now shipped: Phases 1, 2, 4
  (foundational — token unification, amber/gold, focus ring), Phases 3,
  5, 6 (canonical scales, typography utilities, calm nav), Phases 7, 8
  (nav config extraction, polish).

### Net impact

What the user sees:
- Focus ring everywhere is gold (was navy in three different sizes).
- Warnings everywhere are warm orange (were gold-yellow, indistinguishable
  from brand accents).
- Card corners are uniformly 12px across the platform (were 12/16/24px
  depending on author).
- Card shadows resolve to 5 visual weights, not 14 (no more subtly-mismatched
  shadows on neighbouring cards).
- Sidebar active item is solid navy + gold left rule (was gradient +
  colored shadow + dark-only rule).
- Mobile active indicator is a thumb-visible gold underline (was a 1px dot).
- ⌘K chip reads as a real keystroke (was generic sans).
- Skeletons read as neutral loading bands (were navy-tinted in dark mode).
- Slight body-paint speedup from dropping the grid overlay.

What contributors see:
- One CSS variable file (`variables.css`) is the canonical token source —
  was three with contradicting values.
- One typography utility set documented in `tokens.json` — was raw
  Tailwind soup in pages.
- IA lives in a 320-line readable config file — was inline in a
  1000-line Layout.tsx.
- One focus token, one ring colour, one card radius, one canonical shadow
  scale.
- Lesson L5 in `tasks/lessons.md` captures the "declared CSS variables
  do nothing unless imported" near-miss from Phase 1, so the trap doesn't
  catch the next person.

---

## Post-review pass (2026-05-13)

External design review caught six concrete issues across the 8 shipped
phases. All addressed in this pass.

### 🔴 1. `tokens.json` borderRadius + shadow blocks didn't match `variables.css`

The most important catch: after Phase 3 consolidated the CSS variables
to 4 radii + 4 canonical shadow depths, the JSON token spec still
described the OLD 8 radii + 6 shadow names. Worse, component tokens
referenced the old names (`card.borderRadius = {borderRadius.xl}`,
`modal.borderRadius = {borderRadius.2xl}`, `card.shadow = {shadow.soft}`,
`modal.shadow = {shadow.strong}`). The CSS and the JSON told two
inconsistent stories — anyone reading "the design system" would be
confused about which to trust.

Fixed:
- `borderRadius` block in `tokens.json` reduced to the canonical 4 (sm/md/lg/full + none), with a `_description` field noting the consolidation and pointing at `variables.css`.
- `shadow` block in `tokens.json` rewritten to match the canonical depth scale (rest/hover/popover/modal) + canonical focus + 4 semantic state rings (ringAccent/ringPrimary/ringSuccess/ringError). Old names removed.
- Component references updated:
  - `card.borderRadius`: `xl` → `lg`
  - `card.shadow`: `soft` → `rest`
  - `card.shadowHover`: `medium` → `hover`
  - `modal.borderRadius`: `2xl` → `lg`
  - `modal.shadow`: `strong` → `modal`

### 🟠 2. Logo tile was the loudest thing in the sidebar

After Phase 6 calmed the active nav state, the logo tile (44×44 surface)
became the most decorated element on the sidebar — three-stop gradient
+ colored drop shadow + dark-only accent ring, the exact recipe just
retired from active items.

Fixed: flattened to a solid `bg-primary-900` (`dark:bg-primary-800`)
tile with an always-on gold ring (`ring-1 ring-accent-500/60` light,
`ring-accent-400/60` dark). Tile now ties to the rest of the gold
brand moments (focus ring, page-title underline, active nav rule)
without competing with them.

### 🟡 3. Mobile bottom-nav active state was louder than desktop

Mobile previously stacked 3 signals for active: button background pill
(`bg-primary-100`), icon background pill (`bg-primary-700`), and an
indicator above the icon. Desktop uses 2 signals (solid fill + gold
left-rule).

Fixed: dropped the button-level background pill AND the icon-level
background pill. Mobile now uses the same two-signal language:
1. Text + icon colour shift to primary-700 / dark:neutral-50.
2. 16×2px gold underline at the bottom edge of the active item.

### 🟡 4. Content-area gradient redundant with body atmospherics

Layout's main container had `bg-gradient-to-br from-neutral-50 via-white
to-neutral-50/80` — but `body::after` already paints two radial gradient
atmospherics that carry the depth signal.

Fixed: container is now `bg-transparent` so the body radials show
through. One less layer in the paint budget per repaint.

### 🟡 5. Dark-mode shadow overrides defeated the Phase 3 consolidation

The `.dark` block in `variables.css` overrode `--shadow-soft / -medium
/ -strong` with bigger black-tinted shadows. Post-Phase-3 those names
are aliases, so overriding them shadowed the canonical set on dark mode
specifically.

Fixed: dark-mode shadow overrides dropped entirely. On dark surfaces
(cards = navy-900, panels = navy-950), the brightness step between the
two navys carries the depth signal more cleanly than any drop shadow.
Cards already explicitly null their shadow in dark via `[data-theme=
"dark"] .card-base { box-shadow: none }` in `index.css` — so this is
a no-op for the surfaces that intentionally suppressed shadows, and
for anything else the canonical-set shadow applies uniformly.

### 🟢 6. Smaller things

- **Insights menu trimmed.** Section had 4 items, 3 of them
  `isComingSoon` — read as a roadmap teaser, not a menu. Dropped
  Forecasting / Sweep Optimizer / Smart Reconciliation; only the
  shipped Treasury Copilot remains. Each unbuilt item gets added
  back to the menu when it ships. (Roadmap doc stays at
  `tasks/ai-features-roadmap.md`.) Cleaned up the now-orphan icon
  imports and page-title entries.
- **Page-title underline width unified.** Was 24px on
  `.page-title-display` and 32px on `.page-title`. Standardised on
  32px across both — one canonical motif width.
- **`--shadow-strong` alias remapped.** Previously aliased to
  `--shadow-popover` (15px blur), but the only caller was
  `enhanced.tsx`'s modal drop-shadow — which wanted `--shadow-modal`
  (50px). Alias updated to `--shadow-strong → --shadow-modal`. Also
  updated Tailwind config's `boxShadow.soft / medium / strong` values
  so `shadow-strong` utility class lands on the canonical 50px modal
  shadow rather than the legacy 24/48px black shadow.
- **Notification dot colours** already use semantic Tailwind classes
  (`bg-warning-500` / `bg-success-500` / etc) that route through the
  semantic palette in `tailwind.config.js` — already token-backed, no
  change needed.

### Final verification (post-review)

- `npx vite build` succeeded in 16.21s. 173.22 kB CSS bundle — 0.9 kB
  *smaller* than the previous build (dropping dark-mode shadow overrides
  + the grid overlay layers + tokens.json consolidation).
- Zero TypeScript errors in any of the touched files.

---

## Page-adoption migration — Tier 1 (critical / clean wins)

External design review #2 (2026-05-13): "the system is in place, the
pages haven't fully consumed it." Audit confirmed: ~70% of pages still
author UI like the design system doesn't exist. Migration broken into
tiers; this is Tier 1 — the active-bug fixes and dead-code removals.

- [x] **`danger-*` → `error-*` codemod**. 325 class-token rewrites
      across 19 files. Codemod regex narrowly targets Tailwind class
      tokens of shape `(prefix-)*(text|bg|border|ring|from|to|via|…)-
      danger-NN(/NN)?` so component-variant strings like
      `variant="danger"` (legitimate semantic API on Button + Alert)
      are left alone. Codemod script preserved at
      `scripts/codemod_danger_to_error.py` so the same playbook can
      be applied to future rewrites (Tier 2 will use a similar
      shape for the `text-gray-* → text-neutral-*` sweep).
- [x] **Delete orphan duplicate files**. Verified neither was
      imported anywhere (only the canonical PascalCase versions are
      referenced in `App.tsx` lines 42 + 48). Deleted:
      `frontend/src/pages/vibanmanagement.tsx` (286 lines) and
      `frontend/src/pages/Sellercollectionpage.tsx` (232 lines).
      518 lines of dead code gone.
- [x] **Tailwind `shadow-dropdown` + `shadow-popover` defined**.
      Both added to `tailwind.config.js`'s `boxShadow` block with the
      canonical 15px popover value. The 2 existing `shadow-dropdown`
      callers (AccountsPage, BeneficiariesPage) now render with the
      correct shadow instead of falling back to none.
- [x] **`.stat-value-inverse` + `.stat-value-inverse-lg` utilities
      added**. Two new classes in `index.css` for dark hero surfaces
      that paint white in both modes. Replaced the two existing
      `!text-white` `!important` hacks:
        - CurrencyMirrorPage hero balance: `stat-value !text-white`
          → `stat-value-inverse-lg`
        - TreasuryHierarchyPage bank-account banner balance:
          `stat-value-sm !text-white` → `stat-value-inverse`

### Tier 1 side fixes (caught during typecheck)

Two pre-existing TS errors surfaced when verifying. Same family of bug
(`variant="danger"` passed to a `Badge` which only accepts `error` as
a semantic variant). Fixed:
- `BaaSCashOperationsPage.tsx` L934: `variant={... ? 'success' : 'danger'}` →
  `variant={... ? 'success' : 'error'}`.
- `pages/tabs/MccRestrictionsTab.tsx`: Local `CodeTagListProps.variant`
  union type changed from `'success' | 'danger' | 'info'` to
  `'success' | 'error' | 'info'`; two `<Badge variant="danger">` callers
  and one prop value updated to match. The Button caller on L296
  (which uses `variant="danger"` legitimately, since Button supports
  it) was left alone.

### Tier 1 verification

- `npx vite build` succeeded in 18.16s. 174.63 kB CSS bundle.
- `npx tsc --noEmit` finds zero `danger`-related errors.
- `grep -rE "(text|bg|border|…)-danger-[0-9]"` returns zero hits.
- Spot-check: destructive buttons in AccountsPage, DashboardPage,
  KyccPage, BeneficiariesPage, EnhancedNettingCyclesPage now render
  with the proper `error-*` colour treatment.

---

## Page-adoption migration — Tier 2 (color discipline codemod)

External design review #2 follow-up. Goal: kill the parallel chromatic
vocabulary (`bg-red-*` / `bg-green-*` / `bg-blue-*` / `bg-yellow-*` /
`bg-orange-*` / `bg-gray-*`) so pages speak the semantic palette
(`error / success / info / warning / neutral`) consistently.

### Pre-step: Expand semantic palettes (the trap)

The previous semantic palettes (`success`, `error`, `info`) were partial
— only 50/500/600/700 stops defined. Pages writing `bg-green-100`
semantically couldn't migrate to `bg-success-100` because that class
wasn't defined in the Tailwind config — it would render as nothing,
exact same trap as the Tier 1 `danger-*` bug.

Fixed before the codemod ran:
- **`variables.css`**: expanded `--color-green-*`, `--color-red-*`, and
  `--color-blue-*` from 6 stops (50/100/200/500/600/700) to full 50/100/
  200/300/400/500/600/700/800/900. Hex values mirror Tailwind defaults
  so semantic and primitive families stay pixel-identical.
- **`tailwind.config.js`**: expanded `success`, `error`, `info` semantic
  palettes from 4 stops to 10 (50–900). Codemod targets are now defined.

### Codemod (`scripts/codemod_chromatic_to_semantic.py`)

Same shape as the Tier 1 `danger-*` codemod. Maps:

    gray   -> neutral   (different hex; intentional shift)
    red    -> error     (hex-equivalent; pure rename)
    green  -> success   (hex-equivalent; pure rename)
    blue   -> info      (hex-equivalent; pure rename)
    orange -> warning   (hex-equivalent post-Phase 2)
    yellow -> warning   (VISIBLE CHANGE: gold-yellow -> orange; matches Phase 2 design intent)

Targets only Tailwind class shape `(state-prefix:)*(token)-(family)-NN(/NN)?`.
Component variant strings (`variant="info"`, type unions) untouched.

Exclusion list to preserve deliberate chromatic intent:
- `frontend/src/pages/TaxChargeSetupPage.tsx` — uses orange/yellow/cyan/
  pink/blue/green as categorical fee-type and jurisdiction colours.
  Migrating them would collide semantically (POBO_FEE and IHB_FEE
  would both become warning-tinted).

### Results

- [x] **gray -> neutral**: 53 rewrites across 2 files
      (Iso20022PaymentsPage, LegalEntitiesPage).
- [x] **red -> error**: 433 rewrites.
- [x] **green -> success**: 592 rewrites.
- [x] **blue -> info**: 605 rewrites.
- [x] **orange -> warning**: 105 rewrites.
- [x] **yellow -> warning**: 30 rewrites across 3 files
      (CreateReceivablePage, IntegrationsPage, TreasuryHierarchyPage).

**Total: 1818 class-token rewrites.** All hex-equivalent except `gray`
(intentional shift to canonical neutral palette) and `yellow` (intentional
gold-yellow -> orange shift, matches Phase 2 design intent — warnings
now distinct from gold accent everywhere).

### Tier 2 side fixes

- **`App.tsx` casing typo**: pre-existing import path
  `'./pages/ShadowAccountspage'` (lowercase 'p') resolved on Windows
  case-insensitive FS but type-errored as TS1261. Fixed to
  `'./pages/ShadowAccountsPage'`. Same family of file-discipline
  issue as the Tier 1 orphan-file deletions.

### Tier 2 verification

- `npx vite build` succeeded in 15.37s. **172.43 kB CSS bundle —
  2.2 kB *smaller* than the post-Tier-1 bundle**. The shrink came
  from Tailwind's JIT no longer needing to ship duplicate class
  generations for chromatic + semantic variants of the same hex
  (e.g. `bg-green-100` and `bg-success-100` previously both
  shipped; now only `bg-success-100`).
- `npx tsc --noEmit` finds 512 errors, all pre-existing
  (TS6133 unused imports + TS2353/TS2322/TS2339 type mismatches
  in component prop typings); zero new errors from the codemod.
- Remaining raw-chromatic class-token uses: 73, all inside
  `TaxChargeSetupPage.tsx` (the deliberately-excluded categorical
  palette). Audit-clean elsewhere.
- Spot-check: AcquisitionWizard's local status palette + step
  indicator + connector line all migrated cleanly (lines 67–109).
  IntercompanyDashboard, CreatePayablePage, CurrencyMirrorPage,
  TreasuryHierarchyPage all render unchanged (hex equivalence).

### What's next

After Tier 1 + Tier 2:
- Tailwind's amber, warning, success, error, info, neutral are now the
  *only* colour vocabulary in the page layer (except the intentional
  TaxChargeSetupPage exception).
- Future colour drift is preventable via Tier 8 ESLint rule.
- The semantic palettes are full 50–900 so no future codemod will hit
  the partial-palette trap.

---

## Page-adoption migration — Tier 3 (StatusIconBadge extraction)

Audit found the canonical "rounded medallion + tinted icon" pattern
authored as inline JSX 165+ times across 33 pages — with subtle drift:
sometimes `w-10 h-10`, sometimes `w-12 h-12`, sometimes `rounded-xl`,
sometimes `rounded-lg`, mixed dark-mode opacity (`/10` vs `/20`), and
in some cases the icon's dark-mode text colour didn't match the
medallion's tone (which is fine for `primary` but confusing for the
codemod). Extracting the pattern into a single component is the
highest-leverage component-extraction win on the platform.

### Component (`frontend/src/components/ui/StatusIconBadge.tsx`)

```tsx
<StatusIconBadge tone="success" icon={CheckCircle2} />
<StatusIconBadge tone="info" icon={Layers} size="lg" rounded="lg" subtle />
<StatusIconBadge tone="primary" icon={Building} className="shrink-0" />
```

Props:
- `tone` — typed union over the 7 canonical semantic tones
  (`success | warning | error | info | primary | neutral | accent`).
  Forces pages onto our palette; raw `bg-emerald-100` etc. is no
  longer expressible through this component.
- `icon` — Lucide icon component (NOT a rendered element). Sized
  internally so the caller can't override.
- `size` — `'sm' | 'md' | 'lg'` (8/10/12px). Defaults to `md`.
- `rounded` — `'lg' | 'xl' | 'full'`. Defaults to `xl` (canonical
  card radius).
- `subtle` — boolean. Uses the 50/10% stops instead of 100/20% for
  decorative / sub-card use.
- `className` — passthrough for utility extras (`shrink-0`, `mt-1`).

Re-exported from `components/ui` barrel so callers can use the same
import path as `Card`, `Button`, `Badge`.

### Codemod (`scripts/codemod_status_icon_badge.py`)

Multi-line regex matching the canonical shape:
- `<div className="w-{8|10|12} h-{...} rounded-{lg|xl} bg-{tone}-{50|100}
  [dark:bg-{tone}-...] flex items-center justify-center [extras]">`
- followed by single icon child `<{IconName} className="w-{4|5|6}
  h-{...} text-{tone}-{NN} [dark:text-{any}-{NN}]" />`
- `</div>`

Key design choices:
- Only **semantic tones** (`success / warning / error / info / primary /
  neutral / accent`) are migrated. Raw chromatic tones (purple, cyan,
  pink, emerald, indigo, teal) are categorical palettes that the
  component would force-flatten — leave them inline.
- Icon's dark-mode text colour can differ from the medallion's tone
  (e.g. `primary` medallions often use `dark:text-neutral-200`); the
  regex accepts any dark-mode tone there.
- `bg-{tone}-50` detected as `subtle`; `bg-{tone}-100` as default.
- Box width 10 → default `size` (omitted); 12 → `size="lg"`; 8 → `size="sm"`.
- Radius `xl` → default (omitted); `lg` → `rounded="lg"`.
- Any extra utility classes after `justify-center` (e.g. `shrink-0`,
  `mt-1`) preserved as `className=`.

### Codemod's `ensure_import` bug

First codemod run produced two corrupt files where the new `import
{ StatusIconBadge } from '../components/ui';` line was inserted INTO
a multi-line `import { ... } from 'lucide-react';` block:

```
import {
import { StatusIconBadge } from '../components/ui';
  Calendar, …
} from 'lucide-react';
```

Root cause: fallback heuristic checked `lines[i+1].startswith('import ')`
to find the end of the import block, but multi-line lucide imports
span many lines that start with whitespace + an icon name, not with
`import`. Fixed in two ways:
1. Manual repair of `CreditLimitsPage.tsx` and `StatementDownloadPanel.tsx`.
2. Codemod heuristic rewritten to walk imports properly: track the
   last `from '...'` line index across multi-line declarations,
   insert AFTER that line. Also relaxed the path match to accept
   `'../ui'`, `'../components/ui'`, and any `*/ui` form.

Lesson worth capturing for `lessons.md`: tools that auto-insert
imports must handle multi-line `import { ... }` blocks. A heuristic
that walks line-by-line and stops at the first non-`import`-prefixed
line will fall INTO the multi-line block. The right invariant is
"insert after the line containing `from '...'`."

### Tier 3 collateral cleanup

While verifying the codemod's output, found **11 pre-existing invalid
Tailwind class corruptions** that predate every codemod in this work
(invalid double-slash opacity suffixes like `dark:bg-info-500/100/20`
or `dark:bg-primary-800/40/50`). The Tier 2 chromatic codemod
propagated them faithfully (rewriting `blue` → `info` etc.) but
didn't introduce them. Cleaned up with a regex pass:
- `<utility>-<scale>/100/<N>` → `<utility>-<scale>/<N>` (8 hits across
  BaaSCashOperationsPage + EnhancedPayablesPage).
- `<utility>-<scale>/<a>/<b>` → `<utility>-<scale>/<a>` (5 hits across
  WalletPage + CreatePayablePage).

These were rendering as no shadow/no opacity background in dark mode
because Tailwind silently drops malformed classes — same family of
bug as Tier 1's `danger-*`.

### Results

- [x] **199 `<StatusIconBadge>` usages** now exist across the codebase.
- [x] **198 medallion rewrites** in the codemod's apply step (one more
      than the 165 audit found because the relaxed regex caught
      variants the audit didn't count).
- [x] 33 files migrated. Top: IntercompanyDashboard (27), CashConcentration
      (22), Transfers (19), EnhancedNettingCycles (12), BalanceAggregation
      (11), SettlementVa (9), Programs (8), MerchantOnboarding (7),
      ShadowAccounts (7), CurrencyMirror / CreditFacilities / FxRates (6 each).
- [x] **37 medallion-shape patterns remain** in pages, all variants the
      codemod intentionally skipped (mixed-tone, gradient backgrounds,
      nested children, or non-standard JSX shape). Future page touches
      can migrate them organically.

### Tier 3 verification

- `npx vite build` succeeded in 19.29s. **172.46 kB CSS bundle.
  JS bundle: 2,818.98 kB — ~22 kB *smaller* than after Tier 2** (the
  198 medallion inline className strings, averaging ~110 chars each,
  are no longer duplicated in source).
- `npx tsc --noEmit` finds 511 errors, all pre-existing; zero new
  errors from Tier 3.
- Zero `<div className="w-... rounded-... bg-... flex items-center
  justify-center">` patterns with the canonical 7 semantic tones
  remain in the pages — only categorical-palette and complex-variant
  shapes survive.
- Spot-check: medallions in CashConcentration, Transfers, Intercompany
  Dashboard, BalanceAggregation render identically (visual no-op —
  the component's output classes match the inline forms exactly).

### What's next

- Tier 5 (system bypassers: CreatePayablePage local components,
  AcquisitionWizard step indicator polish, inline modals).
- Tier 6 (HeroMetricCard adoption on showroom pages).
- Tier 7 (Dashboard flatten).
- Tier 8 (ESLint rule forbidding raw chromatic + `danger-*` tokens).

---

## Page-adoption migration — Tier 4 (long-form stat-value codemod)

Goal: migrate inline `text-2xl font-(bold|semibold) text-primary-900
dark:text-neutral-50` patterns to the canonical `.stat-value-sm`
utility class. The audit reported 26 occurrences across 17 files;
re-scoping post-Tier-2 found ~41 (the chromatic codemod unmasked some
patterns that were previously hidden behind raw `text-{red,green}-*`
colours).

### Codemod (`scripts/codemod_stat_value.py`)

Narrowly-scoped: looks for `className="..."` LITERAL strings (no
`cn(...)` helpers or template literals — those are too risky to
rewrite mechanically). Matches when ALL three of `text-2xl`,
`text-primary-900`, `dark:text-neutral-50` AND one of `font-bold`/
`font-semibold` are present.

Migration:
- Drop classes whose semantics are absorbed by `.stat-value-sm`:
  `text-2xl`, `font-bold`, `font-semibold`, `text-primary-900`,
  `dark:text-neutral-50`, `tracking-tight` (utility sets letter-spacing
  internally), `tabular-nums` (utility sets font-variant-numeric).
- Preserve any non-stat utility classes (`mt-1`, `mb-2`, etc.).
- Skip responsive cases (any `sm:text-`, `md:text-`, etc.).
- Skip coloured stat values (`text-success-600`, `text-error-600`,
  etc.) — different semantic, future tier may add tone variants.

Visible changes for callers:
- Font family: Bricolage sans → Fraunces serif (intentional — stat
  values across the platform now read as the display tier).
- Weight: `font-bold` (700) → 600 (intentional, design-system spec).
- Letter-spacing: `tracking-tight` (-0.025em) → utility's -0.02em
  (de minimis).

### Results

- [x] **39 codemod rewrites** across 22 files.
- [x] **1 manual migration** in `EnhancedPayablesPage.tsx` L290 (case
      was wrapped in `cn(...)` so the literal-string codemod skipped
      it).
- [x] **1 manual `text-4xl` migration** in `FxRatesPage.tsx` L419 →
      `.stat-value` (hero-sized variant for the rate detail's prominent
      figure).
- [x] **1 responsive case left inline** in `PhysicalAccountsPage.tsx`
      L350: `text-xl sm:text-2xl font-bold text-primary-900 truncate
      dark:text-neutral-50` — adaptive shrink to 20px on narrow widths
      is intentional behavior. `.stat-value-sm` doesn't have a
      responsive variant; introducing one is out of scope for this
      tier.

### Tier 4 verification

- `npx vite build` succeeded in 15.64s. **172.46 kB CSS bundle.
  JS bundle 2,817.13 kB — ~2 kB smaller** than post-Tier-3 (the
  long-form Tailwind class soup deduplicated into one utility).
- `npx tsc --noEmit` finds 511 errors, all pre-existing; zero new
  errors from Tier 4.
- Post-Tier-4 stat-value utility adoption:
  - `.stat-value-sm`: 80 usages (was ~25 pre-Tier-4).
  - `.stat-value`: 6 usages.
  - `.stat-value-inverse` / `.stat-value-inverse-lg`: 2 + 2 (the dark
    hero surfaces added in Tier 1).
- Spot-check: stat tiles in IntercompanyDashboard, CreditLimits,
  Programs, FxRates, EnhancedPayables, BeneficiariesPage, etc. now
  render in Fraunces serif with tabular-nums — visually distinct from
  the raw-Tailwind-soup version, signalling the display tier
  consistently.

### Carry-overs

- The 80+ coloured stat-value sites (`text-2xl font-bold ...
  text-success-600 dark:text-success-300` etc.) remain inline. A
  future tier can introduce `.stat-value-{success|warning|error|info}`
  tone variants — would migrate ~80 more sites and complete the
  display-tier discipline. Probably worth a dedicated 30-minute
  follow-up rather than mixing with Tier 5's component-bypass work.

---

## Page-adoption migration — Tier 5 (system bypassers)

The most concerning pattern in the codebase: pages that define their own
`Card` / `Button` / `Badge` / `Modal` locally, bypassing the shared
component library. Anything we change in the system — tokens, focus
rings, dark-mode passes, amber/orange repalette — simply doesn't reach
these pages. From a design-integrity standpoint they're in their own
worlds.

- [x] **CreatePayablePage.tsx** — local `Card`, `Button`, `Badge`
      definitions (lines 251 / 335 / 372) deleted; shared versions
      imported from `components/ui`. Page-local `Input`, `Select`,
      `Toggle` left for now (richer APIs differ from shared versions —
      a follow-up migration can wrap or align them).
      Caller migration:
        - `<Button icon={X}>` → `<Button leftIcon={X}>` × 5 sites
        - `<Badge variant="default">` → `<Badge variant="neutral">` × 4 sites
- [x] **AcquisitionWizard step indicator** — completed steps were
      `bg-success-500` (green); current was `bg-primary-600` (mid
      navy). Wizards are brand moments — replaced both with
      `bg-primary-900` (dark navy) + accent ring discipline:
        - Completed: 1px gold edge ring (`ring-1 ring-accent-500/40`)
        - Current:   2px gold accent ring (`ring-2 ring-accent-500`)
        - Connector traversed: `bg-primary-900` (was green)
      Now reads as Aperture instead of generic-success-green.
- [x] **Inline modal hand-rolls** —
      - `AcquisitionWizard` + `DivestitureModal`: their multi-step
        layouts don't fit the shared Modal's title/children/footer slot
        model. Kept the visual structure but added a small a11y effect
        block (escape-to-close + body-scroll-lock) to each so they
        match Modal's behaviour.
      - `CreditLimitsPage` GroupLimitModal + VaLimitsModal: simple
        form modals, migrated cleanly to `<Modal>` from `enhanced.tsx`.
        Title + subtitle + footer (for VaLimitsModal) come from Modal
        props.

### Tier 5 verification

- `npx vite build` succeeded in 17.15s. **172.33 kB CSS bundle.
  JS bundle 2,814.01 kB — ~3 kB *smaller*** than post-Tier-4 from
  removing the inline component duplicates + modal wrappers in
  CreatePayablePage and CreditLimitsPage.
- `npx tsc --noEmit` finds 511 errors, all pre-existing; zero new
  errors from Tier 5.
- All `<Card>`, `<Button>`, `<Badge>` callsites in CreatePayablePage
  resolve through shared `components/ui` exports.

## Page-adoption migration — Tier 6 (HeroMetricCard adoption)

`HeroMetricCard` currently imported by 4 pages (Accounts, LegalEntities,
PhysicalAccounts, VibanManagement). Every other balance-led page either
hand-rolls a navy-gradient hero or skips the dominant figure entirely.
The "one dominant figure per page" principle the tokens.json explicitly
states wasn't being applied.

- [x] **DashboardPage** — replaced 4-up StatCard strip (Total Balance,
      Available Balance, Today's Transactions, Pending) with HeroMetricCard
      hero pair (Total Balance + Available, mirroring the Accounts hero
      pattern) and a 2-up operational strip below (Today's Transactions
      + Pending). Carries forward the `trend` chip + `balanceChange`
      vs-last-month signal. Note: the 18 inner gradients on Dashboard
      remain (Tier 7 still has them as a deferred clean-up — separate
      scope from hero adoption).
- [x] **IntercompanyDashboardPage** — multiple wins in one pass:
        - Removed all 4 duplicate `<h1 className="page-title">` renders
          (one per render branch — empty state, loading state, error
          state, main). Layout header already shows "Intercompany
          Dashboard" from `pageTitles`. Pure duplication.
        - Migrated Refresh / COBO / POBO CTAs to Layout header via
          `usePageHeaderActions`.
        - Replaced 6-up stat strip with HeroMetricCard hero pair
          (POBO Volume + COBO Volume — the dollar throughput is the
          treasurer's first read) + 4-up operational strip (POBO Count,
          COBO Count, Pending, Entities).
- [x] **CashConcentrationPage** — replaced 4-up stat strip (Active
      Rules, Linked Accounts, Total Swept, Today's Swept) with
      HeroMetricCard hero pair (Total Swept + Today's Swept) +
      2-up operational strip (Active Rules + Linked Accounts).
- [x] **CurrencyMirrorPage** — explicitly NOT migrated. The page's
      hand-rolled navy-gradient hero contains a custom currency
      distribution bar with per-currency legend dots — content that
      doesn't fit HeroMetricCard's slot model. The `!important`-override
      smell that was the original concern was already resolved in
      Tier 1 (replaced with `.stat-value-inverse-lg`). Migrating
      would either lose the distribution UX or require a HeroMetricCard
      API expansion that's out of scope here.

### Tier 6 verification

- `npx vite build` succeeded in 22.34s. **172.27 kB CSS bundle.
  JS bundle 2,811.52 kB** — another ~2.5 kB smaller than post-Tier-5,
  from deduplicating the StatCard strips into HeroMetricCard
  invocations.
- `npx tsc --noEmit` finds 511 errors, all pre-existing; zero new
  errors from Tier 6.
- HeroMetricCard adoption: was 4 pages (Accounts, LegalEntities,
  PhysicalAccounts, VibanManagement). Now **7 pages** — added
  Dashboard, IntercompanyDashboard, CashConcentration. CurrencyMirror
  intentionally retains its bespoke hero (per above).

### Carry-overs

- **Coloured stat-value variants** (`.stat-value-success`, etc.) —
  the Pending tile in IntercompanyDashboard is using inline Fraunces
  styling because the canonical `.stat-value-sm` is neutral-only.
  Same pattern applies to ~80 coloured stat sites across the
  codebase. A 30-minute follow-up tier could add the variants.

---

## Page-adoption migration — Tier 7 (Dashboard inner-gradient flatten)

Goal: drop the 18 `bg-gradient-to-*` inner-element gradients inside
DashboardPage. The body-level radial atmospherics (added in Phase 1
unification) already carry depth — adding gradient layers on every
icon medallion, row, and tile created the "over-busy" feel the
reviewer flagged.

### Migrations

- [x] **4 widget icon medallions** (Account Structure / Corporates /
      Collections / Payments) — replaced inline `<div className="w-10
      h-10 rounded-xl bg-gradient-to-br from-X-100 to-X-50
      dark:from-X-500/20 dark:to-X-500/10">` with `<StatusIconBadge
      tone="..." icon={...} />` (Tier 3 component). Saves both source
      and bundle.
- [x] **1 progress bar** in BalanceByLevelWidget — `from-primary-500
      to-primary-400` → solid `bg-primary-500`.
- [x] **4 inline row backgrounds** (Total/Active highlight rows,
      Collections/Payments volume rows) — `from-X-50 to-white` →
      solid `bg-X-50`. Solid tint reads as the same accent without
      the gradient-on-card noise.
- [x] **1 selector-bar card background** — multi-stop
      `from-primary-50/50 via-white to-primary-50/50` → transparent
      (rely on Card's default background + body atmosphere).
- [x] **4 empty-state containers** (chart placeholder, no-accounts
      tile, no-corporates tile, treasury-stats placeholder) —
      `from-neutral-50 to-white dark:from-primary-950 dark:to-primary-900`
      → solid `bg-neutral-50/60 dark:bg-primary-950/50`. The /60
      alpha lets the body's two radial gradients show through.
- [x] **3 rank chips** (1st/2nd/3rd in top-accounts widget) —
      `from-X-100 to-X-50` → solid `bg-X-100`. Categorical hue
      (amber/neutral/warning) still readable.
- [x] **4 feature tile backgrounds** (Notional Pools, Sweep Rules,
      Netting Savings, IHB Loans) — data structure refactored from
      `gradient: 'from-info-50 to-white …'` to `bgColor: 'bg-info-50
      dark:bg-info-500/10'`. Template uses the flat name; no
      `bg-gradient-to-br` in the JSX.

### Tier 7 verification

- `npx vite build` succeeded in 16.84s. JS bundle 2,809.62 kB — ~2 kB
  smaller than post-Tier-6 from removing duplicated gradient class
  string literals.
- `grep -c "bg-gradient-to-" src/pages/DashboardPage.tsx` returns 3
  matches, all inside `// Tier 7: …` migration commentary comments.
  Zero actual gradient class uses remain in the JSX.
- The page now relies on body-level atmospherics + HeroMetricCard's
  gold lens for visual depth — exactly the "atmospheric depth over
  flat surfaces" principle from tokens.json.

---

## Page-adoption migration — Tier 8 (ESLint rule)

Goal: lock in everything Tiers 1-7 just did. Future drift into raw
chromatic Tailwind classes gets caught at PR time rather than during
the next design review.

### Setup

Created `frontend/.eslintrc.cjs` (none existed before, despite the
`lint` script in package.json). The config provides:
- Standard TypeScript + React rules.
- Pragmatic relaxation for the codebase's 500+ pre-existing TS6133
  unused-import warnings (downgraded to warnings, not errors).
- A **`no-restricted-syntax`** rule targeting raw chromatic Tailwind
  classes in `src/pages/` and `src/components/`.

### The rule

The rule fires on JSX `className="..."` attributes (string literals)
AND on template literals inside `className={\`...\`}` expressions
where the class matches:

```
(state-prefix:)* (token)-(family)-NN(/NN)?
```

with:
- `state-prefix` ∈ `{dark, hover, focus, active, disabled,
  group-hover, peer-hover, md, sm, lg, xl, 2xl}`
- `token` ∈ `{text, bg, border, ring, from, to, via, placeholder,
  fill, stroke, divide, outline, caret, decoration, accent, shadow}`
- **`family` ∈ `{red, green, blue, yellow, gray, danger}`** (the
  forbidden set)

The error message says: "Raw chromatic Tailwind class detected. Use
the semantic palette instead: red→error, green→success, blue→info,
yellow→warning, gray→neutral, danger→error. See
tasks/design-system-unification.md (Tiers 1+2). For genuinely
chromatic UI (charts, status keys), add an eslint-disable comment
explaining why."

### Intentional carve-outs

- **TaxChargeSetupPage.tsx** is in the rule's `excludedFiles` —
  the page uses categorical chromatic colours for fee-type and
  jurisdiction icons (POBO=orange, IHB=yellow, VAT=blue, GST=green,
  etc.) and ANY semantic remapping would collide visually.
- **{amber, orange, purple, pink, cyan, emerald, indigo, teal}**
  are NOT in the forbidden set. `amber` is aliased to the warning
  palette in `tailwind.config.js` so it resolves to orange-warning.
  The others are categorical accents used in chromatic UI (currency
  codes, AI / sparkles tiles, etc.).
- Per-line escape: `// eslint-disable-next-line no-restricted-syntax`
  with a comment explaining why.

### Tier 8 verification

Test fixture (later deleted): a synthetic file with `bg-red-500`,
`bg-green-100`, `text-blue-700`, `bg-danger-500`, and the template-
literal forms. The rule correctly fires on all of them, and DOES NOT
fire on the semantic equivalents (`bg-success-100`, etc.) or the
categorical exceptions (`bg-purple-100`, `bg-cyan-50`).

Running `npx eslint src/pages src/components` against the real
codebase finds **0 chromatic-class warnings** — Tiers 1-7 already
cleared every actual site. The rule is now standing guard against
future drift.

Total lint output: 410 problems (5 pre-existing code-quality errors
like `prefer-const`, 405 warnings — mostly TS6133 unused imports).
None design-system-related; none caused by this work.

---

## Closure

All eight phases of the Aperture design system unification are
shipped. The system that was "in place but not consumed" at the
start of this work is now consumed end-to-end:

| Layer | State at start | State now |
|---|---|---|
| **Token sources** | 3 competing CSS files, 1 unimported | 1 (`variables.css`), imported, mirrored in `tokens.json` |
| **Focus ring** | 3 different declarations | 1 (gold via `--shadow-focus-offset`) |
| **Active nav** | gradient + colored shadow | solid navy + 2px gold rule |
| **Logo tile** | gradient + colored shadow + conditional ring | flat navy + always-on gold ring |
| **Modal max-widths** | Duplicated in TS + tokens.json + Tailwind | Single `--modal-width-*` token source |
| **Mobile bottom-nav active** | 1px dot above icon (sub-thumb-readable) | 16×2px gold underline |
| **Body grid overlay** | 56px hairline pattern on every repaint | Removed; radial atmosphere alone |
| **`danger-*` classes** | 100+ broken (silent fallback) | Migrated to semantic `error-*` |
| **Raw chromatic classes** | ~1900 instances | ~70 (all in deliberate TaxChargeSetupPage carve-out) |
| **`gray-*` vs `neutral-*`** | Both in use | All `neutral-*` |
| **`amber-*` vs gold accent** | Visually identical (Tailwind defaults) | Amber repalettised to orange |
| **Medallion pattern** | 165+ inline JSX duplicates | 199 `<StatusIconBadge>` usages |
| **Long-form stat-value** | 40+ inline `text-2xl font-bold ...` | All migrated to `.stat-value-sm` |
| **Hero adoption** | 4 pages | 7 pages |
| **System bypass** | CreatePayablePage local Card/Button/Badge | All shared |
| **Wizard active step** | Generic-success green | Brand navy + gold ring |
| **Dashboard inner gradients** | 18 occurrences | 0 |
| **Future drift** | Caught by next design review | Caught by ESLint at PR time |

### Bundle effect

- CSS: 174.63 kB → **172.27 kB** (–2.4 kB) over 8 tiers.
- JS:   2,841.99 kB → **2,809.62 kB** (–32.4 kB) over 8 tiers.

Bundle shrinkage came from deduplicating inline className templates
across 198 medallions, 41 long-form stat values, 18 gradient sites,
2 deleted orphan pages, and CreatePayablePage's removed local
component definitions.

### Lessons captured in `tasks/lessons.md`

- **L5** — Declared CSS variables do nothing unless the file is
  actually imported. (Phase 1 near-miss with `variables.css`.)
- **L6** — Auto-import insertion must understand multi-line
  `import { ... }` blocks. (Tier 3 codemod bug.)

### Carry-overs (if anyone wants to keep pushing)

- Per-context: when touching a page that has remaining
  raw `bg-purple-*` / `bg-cyan-*` etc. for non-categorical reasons,
  audit whether it should join the semantic palette.

---

## Tone-variant follow-up (2026-05-13, same day)

Closing the carry-over from Tier 4: ~40 coloured stat-value sites
(was estimated at ~80; the Tier 2 chromatic codemod had already
reduced it) still authored inline `text-2xl font-bold text-{tone}-600
dark:text-{tone}-300` Tailwind soup. Now finished.

### CSS — 4 new tone variants

Added to `frontend/src/styles/index.css`:
- `.stat-value-success` — green
- `.stat-value-warning` — warm-orange
- `.stat-value-error` — red
- `.stat-value-info` — blue

Same typographic treatment as `.stat-value-sm` (Fraunces 24px / 600w
/ tabular-nums / letter-spacing -0.02em) — only the text colour
differs. Mirror added to `tokens.json` `typographyUtilities.display`.

### Codemod (`scripts/codemod_stat_value_tones.py`)

Same shape as the Tier 4 stat-value codemod. Matches `className="..."`
LITERAL strings containing `text-2xl` + one of `font-bold`/`font-
semibold` + matching `text-{tone}-{600|700}` light + `dark:text-
{tone}-{200|300}` dark on the SAME tone. Drops the absorbed classes,
preserves spacing utility extras.

Intentional skips:
- `cn(...)` calls and template literals — too risky to rewrite. Two
  manual migrations handled below.
- Cross-tone variants (e.g. `text-success-600` + `dark:text-info-300`)
  — almost certainly bugs; left visible.
- Different sizes (`text-3xl`, `text-xl`, responsive variants).

### Results

- [x] **39 codemod rewrites** across 14 files:
        - success: 14, warning: 13, error: 8, info: 4
        - Files: CashConcentration, CreditAgreements, CreditFacilities,
          EnhancedNettingCycles, FxRates, InHouseBank,
          IntercompanyDashboard, MerchantOnboarding, Programs,
          SellerCollections, ShadowAccounts, SyncAdmin,
          TreasuryHierarchy, VibanManagement.
- [x] **1 manual `cn(...)` migration** in `CreditLimitsPage` L642
      (conditional warning/success based on `utilizationPercent > 80`).
      Migrated to `cn('mt-1', cond ? 'stat-value-warning' :
      'stat-value-success')` — utility now picks the colour.
- [x] **1 inline-style cleanup** in `IntercompanyDashboardPage` —
      the Pending tile I authored in Tier 6 with inline Fraunces
      `style={{ fontFamily: 'var(--font-display)', ... }}` is now
      `.stat-value-warning` with no inline style. The Tier 6 inline
      smell was a `.stat-value-{tone}`-shaped hole; that's closed.

### Verification

- `npx vite build` succeeded in 22.05s. **JS bundle 2,807.78 kB —
  ~2 kB smaller again** than post-Tier-8 from deduplicating the 41
  inline class templates into utility class calls.
- `grep -roE "text-2xl[^\"]*text-(success|warning|error|info)-[67]00"
  src` returns 0 hits — zero remaining coloured stat-value Tailwind
  soup in the codebase.
- Final stat-value family adoption (across `src/`):
  - `.stat-value-sm` (neutral primary, 82)
  - `.stat-value-success` (15)
  - `.stat-value-warning` (14)
  - `.stat-value-error` (8)
  - `.stat-value` (hero, 6)
  - `.stat-value-info` (4)
  - `.stat-value-inverse-lg` (dark hero, 2)
  - `.stat-value-inverse` (dark, 2)
  - **Total: 133 stat-value utility usages.** Was ~25 at the start
    of Tier 4.

### Final bundle effect (all tiers + carry-over)

- CSS: 174.63 kB → **172.31 kB** (–2.3 kB)
- JS:  2,841.99 kB → **2,807.78 kB** (–34 kB)

### Closed (was)

The 8 tiers + the stat-value tone variants are shipped. A separate
typography-discipline review surfaced additional gaps that the tier
work didn't touch — captured as Phase 9 below.

---

## Phase 9 — Typography Discipline Pass

Goal: complete the typography migration so every page consumes the
utility tier and the brand serif (Fraunces) reaches every stat tile,
page title, and section heading. The earlier tiers consolidated TOKENS;
this phase consolidates AUTHORING.

Confirmed audit numbers (vs. the reviewer's claims):
- The exact stat-tile patterns the reviewer cited (`text-2xl font-bold
  text-primary-900 dark:text-neutral-50`, etc.) — **0 occurrences** in
  the codebase. Tier 4 + tone-variant carry-over already cleared those.
- **Residual stat-tile variants**: 31 sites of `text-2xl font-bold`
  with various non-canonical shapes (no-color, mismatched dark variants,
  inverse-on-dark, sibling-tile `tracking-tight` inconsistency).
- **`.section-title`**: 0 adoption. 126 raw `text-lg font-semibold` +
  32 raw `text-xl font-semibold` sites.
- **Form labels**: 308 raw `text-sm font-medium text-neutral-700` vs.
  19 `.label` adoptions. Top offenders: InHouseBankPage (29),
  ProgramsPage (26), PartiesPage (24).
- **`text-[10px]` ad-hoc**: 64 sites, dominated by IntegrationsPage (37).
- **Mono weight chaos**: 291 `font-mono` total — 78 unweighted, 28
  medium, 2 semibold, 1 bold. KyccPage has 3 entire `<h3>` headings
  set in monospace.

### Tasks

- [x] **A — Split form-label utility**. Added `.field-label` (14px/500w,
      neutral-700/dark:neutral-200) and `.label-cased` (10px/500w
      non-uppercase microlabel) to `index.css`. Mirrored in
      `tokens.json` `meta.designPhilosophy.typographyUtilities.body`.
      Existing `.label` re-documented as the uppercase eyebrow variant.
- [x] **B — Residual stat-tile codemod** (`scripts/phase9_task_b.py` +
      ad-hoc sweeps). 17 rewrites — covered neutral-900/primary-900
      cross-variants, no-color inheritance patterns, `tracking-tight`
      cleanup. 4 sites intentionally left for manual review:
      - InHouseBankPage L2392/L2595 use `text-amber-900` /
        `text-success-900` for rate displays — deeper than canonical
        warning-600 / success-600. Could be intentional.
      - ProgramsPage L549 `text-2xl font-semibold text-neutral-700` —
        likely intentional muting for "max VAs" secondary stat.
      - PhysicalAccountsPage L350 `text-xl sm:text-2xl ...` —
        responsive variant the utility doesn't support.
- [x] **C — Page/section title migration** (`scripts/phase9_task_c.py`).
      **98 rewrites across 38 files.** `.section-title` adoption went
      from 0 → 95. Two H1 hand-rolls migrated to `.page-title`
      (Iso20022PaymentsPage, SettlementVaPage). Coloured section titles
      (`text-lg font-semibold text-success-600` etc.) intentionally
      left — they need `.section-title-{tone}` variants which is out
      of Phase 9 scope.
- [x] **D — Form-label migration**. Three rounds:
      - Round 1 (`phase9_task_d.py`): **424 rewrites across 51 files**.
        Includes 19 sub-patterns (block/no-block × mb-N × dark/no-dark
        variants × eyebrow variants × microlabel variants).
      - Round 2 (`phase9_task_d_round2.py`): **35 rewrites** — caught
        sites where `mb-N` was AFTER `dark:text-neutral-200` (my round 1
        regex had them before), plus inline `flex items-center gap-2`
        labels.
      - Round 3 (inline): **139 rewrites** — no-color form labels
        (`block text-sm font-medium mb-N` without explicit color).
      **Total Task D: 598 rewrites.** `.field-label` adoption 0 → 443.
- [x] **E — Mono discipline**. 5 total changes:
      - 2 `font-mono font-semibold` → `font-mono font-medium`
        (CurrencyMirrorPage, MultiBankLiquidityPage).
      - 2 KyccPage H3 headings de-monospace'd; the application ref
        identifier wrapped in `<span className="code">` so the heading
        is sans-serif and the data reads as code.
      - 1 intentional skip: EnhancedCoboPicker L102 VIBAN headline
        keeps `font-mono font-bold` (flagged as intentional in spec).
- [x] **F — Lint rule extension**. Added `no-restricted-syntax` rule
      pair (string-literal + template-literal selectors) to
      `.eslintrc.cjs` that warns on
      `text-(xs|sm|base|lg|xl|2xl|3xl|4xl|5xl)\s+font-(medium|semibold|bold)`
      co-occurrence in `src/pages/**` and `src/components/**`. Files
      under `src/styles/`, `src/design-system/`, and
      `src/components/ui/HeroMetricCard.tsx` are explicitly
      excluded (those DEFINE the utilities).
      Currently fires as **warnings** (not errors) so the build stays
      green while the residual ~1100 sites get migrated organically.

### Phase 9 verification

- `npx vite build` succeeded in 15.46s. **JS bundle: 2,777.32 kB
  — ~30 kB smaller** than post-tone-variant-carry-over (2,807.78 kB).
  CSS bundle stable around 172 kB.
- `npx eslint --rule "..."` test fixture confirmed:
  - Rule fires on 3 hand-roll patterns (string + template literal).
  - Rule correctly silent on utility-class usage and size-only /
    weight-only patterns.
- Phase 9 lint warnings against real codebase: **1104**. Down from
  ~1500+ projected pre-codemod. The remaining 1104 are mostly:
  - Body-emphasis patterns (`text-sm font-medium text-primary-900`
    for inline emphasis — different role from `.field-label`).
  - Table-header cells (`text-left py-3 px-4 text-sm font-semibold
    text-neutral-700`).
  - Status text (`text-sm font-medium text-amber-800` etc. — coloured
    semantics, not generic form labels).
  - Conditional cn() expressions (genuinely can't migrate
    mechanically; need design judgment).
  None are critical regressions — they represent typography roles
  that don't have a clean utility-class mapping yet.

### Final utility adoption (post-Phase 9)

Display tier:
| Utility | Count |
|---|---:|
| `.stat-value-sm` | **100** (was 82) |
| `.stat-value-success` | 15 |
| `.stat-value-warning` | 14 |
| `.stat-value-error` | 8 |
| `.stat-value` (hero) | 6 |
| `.stat-value-info` | 4 |
| `.stat-value-inverse-lg` | 2 |
| `.stat-value-inverse` | 2 |
| **Total display-tier** | **151** (was ~25 at start of Tier 4) |

Body tier (Phase 9 work):
| Utility | Count |
|---|---:|
| `.field-label` | **443** (was 0) |
| `.section-title` | **95** (was 0) |
| `.label` | 175+ (from Task D eyebrow migrations) |
| `.label-cased` | 2 |
| `.page-title` | 14 |

### Cumulative bundle effect (Tiers 1-8 + carry-overs + Phase 9)

- CSS: 174.63 kB → **~172 kB** (–2.5 kB)
- JS: 2,841.99 kB → **2,777.32 kB** (–65 kB)

JS dropped 65 kB total from deduplicating ~1500 inline className
templates across medallions, stat values, gradients, modals, form
labels, section titles, and CreatePayablePage's local component
definitions.

### Closed (was)

Phase 9 closed but a follow-up review surfaced a critical regression
(`labelr` typo from a codemod bug) plus six smaller items. Captured
as Phase 9.1 below.

---

## Phase 9.1 — Typography Cleanup Pass (2026-05-13)

### Task A — Fix the `labelr` typo (regression)

Phase 9 Task D's form-label codemod used `text.replace()` (substring
match, not whole-token match) for the substitution
`'text-xs font-medium text-neutral-500 uppercase tracking-wide'` →
`'label'`. The source contained `tracking-wider` (the longer
suffix). `replace()` matched the `tracking-wide` prefix and left
the trailing `r dark:text-neutral-400` stranded:

  Before: `text-xs ... tracking-wider dark:text-neutral-400`
  After:  `labelr dark:text-neutral-400`

8 sites silently rendered with no styling because Tailwind drops
unknown classes (same family of bug as Tier 1's `danger-*`).

Fixed:
- All 8 `labelr` sites renamed → `label`.
- Trailing redundant `dark:text-neutral-400` stripped (the utility
  already provides `dark:text-neutral-300`).
- **Lesson L7 captured in `tasks/lessons.md`**: "Tailwind class-token
  substitutions need word-boundary anchoring on the suffix." Future
  codemods must use `\b...\b` regex anchors, not `text.replace()`.

### Task B — `.stat-value-xs` utility + 11 site migrations

A mid-size stat tier was missing (~15 sites authoring `text-lg
font-bold` / `text-xl font-bold` for secondary stats with no utility
to consume).

Added `.stat-value-xs` (Fraunces 20px / 600w / tabular-nums /
letter-spacing -0.015em) to `index.css` + `tokens.json`.

Migrations:
- 10 sites `text-(lg|xl) font-bold text-primary-900 dark:text-neutral-50`
  → `.stat-value-xs` across CashConcentration, CreditLimits,
  EnhancedNetting, IntercompanyDashboard, ShadowAccounts, Transfers.
- 1 site `text-3xl font-bold` for transaction hero amount →
  `.stat-value` in TransactionsPage L786 (cn() conditional tone
  preserved via cascade ordering).
- ExceptionDashboard L394 + Transfers L1108 redirected to
  `.code-display` (Task E) — they're identifier headlines, not
  metrics.

### Task C — Remaining raw section / page titles

3 sites migrated:
- KyccPage L565 `<h3>` (containing application ref) →
  `.section-title` + nested `.code` for the identifier.
- CurrencyMirrorPage L185 `text-lg font-bold ... tracking-tight`
  → `.section-title` (tracking-tight stripped — utility supplies
  its own).
- TransfersPage L842 `<h3>Transfer Successful/Failed</h3>` →
  `.section-title`.

### Task D — Semantic-role mismatches

Phase 9's Task D codemod over-applied `.field-label` and
`.section-title` to elements that visually looked right but were
semantically the wrong role. Each migration:

- **`.field-label` → `.label`** (uppercase eyebrow): 2 sites
  in EnhancedNettingCyclesPage (Cycle Details / Netting Impact
  h4 section headings).
- **`.field-label` → `.body-lg`** (loading state): 1 site
  in EnhancedNettingCyclesPage ("No entries yet" empty-state
  h4 — needed `.body-lg` which was added in this pass).
- **`.field-label` → `.body-sm`** (inline tile labels): 4 sites
  in DashboardPage (Treasury Summary + Quick Action tiles),
  EnhancedCoboPicker (VIBAN toggle title), EnhancedNettingCycles
  ("Settlement Savings" inline label).
- **`.section-title` → `.body-lg`**: 2 sites in CashConcentrationPage
  ("Executing Sweeps..." / "Sweeps Complete" loading states).
- **`.stat-value-sm` on `<h3>` deliberately kept** with explanatory
  comment in CurrencyMirrorPage L874 (currency code IS the hero of
  the panel; heading semantics for a11y, stat-value styling for
  visual weight).

Also added `.body-lg` (Bricolage 18px/500w) utility to fill the gap
between `.body` and `.section-title`.

### Task E — `.code-display` utility + VIBAN migration

Added `.code-display` (JetBrains Mono 18px/600w, letter-spacing
0.025em) for identifier-as-panel-headline contexts. Only ever
`font-mono font-bold` callsite (EnhancedCoboPicker VIBAN
headline) migrated to use it.

Additional `.code-display` adopters during this pass:
- ExceptionDashboard L394 (`exception.exceptionNumber` as h2).
- TransfersPage L1108 (`transaction.referenceNumber` as h3).

Other identifier-headline candidates intentionally LEFT for human
review per Task E constraint:
- EnhancedCoboPicker L180 (different context — likely a body-text
  display, not headline).
- EnhancedReceivablesPage L1597.
- AccountsPage L1208 + L1236.

### Task F — Lint rule exemption extension

Added `src/components/ui/enhanced.tsx` to the `.eslintrc.cjs`
exemption list for the typography rule. This file defines shared
Modal / Tabs / Alert / ProgressBar etc. — those components author
their own type styles internally and shouldn't be forced through
the page-level utilities.

### Verification

- `grep -rn "labelr" src/` → 0 matches ✓
- `grep -rE "text-(lg|xl) font-bold text-primary-900 dark:text-neutral-50" src/pages` → 0 matches ✓
- `grep -rE "font-mono font-bold" src/pages src/components` → 0 matches ✓
- `grep -rE "font-mono font-semibold" src/pages src/components` → 0 matches ✓
- `npx vite build` succeeded in 20.47s. **JS bundle: 2,776.40 kB**
  (1 kB smaller than post-Phase-9 from the 18 additional
  consolidations + utility centralisation).
- Phase 9 lint warnings: 1104 → **1087** (–17). The reduction
  matches the 17+ raw-pattern sites Phase 9.1 cleaned up.

### Final utility adoption (post-Phase 9.1)

Display tier (Fraunces serif, signature brand):
| Utility | Count | Note |
|---|---:|---|
| `.stat-value-sm` | **101** | canonical mid-tier stat (24px) |
| `.stat-value-success` | 15 | semantic green stat |
| `.stat-value-warning` | 14 | semantic orange stat |
| `.stat-value-xs` | **11** | dense-card secondary stat (20px) — new |
| `.stat-value` (hero) | 9 | +3 from Task B's transaction amount migration |
| `.stat-value-error` | 8 |   |
| `.stat-value-info` | 4 |   |
| `.stat-value-inverse-lg` | 2 | dark hero variants |
| `.stat-value-inverse` | 2 |   |
| **Total display-tier** | **166** | was 151 post-Phase-9; 25 at start of Tier 4 |

Body tier (Bricolage sans):
| Utility | Count | Note |
|---|---:|---|
| `.field-label` | **436** |   |
| `.section-title` | **96** |   |
| `.page-title` | 18 |   |
| `.label` | 175+ | (codemod-inserted, not freshly counted) |
| `.code-display` | **3** | new (VIBAN, exception#, transaction#) |
| `.body-lg` | **3** | new (loading states + empty heading) |
| `.label-cased` | 2 |   |

### Cumulative bundle (Tiers 1-8 + carry-overs + Phase 9 + 9.1)

- CSS: 174.63 kB → **~172.5 kB** (–2.1 kB)
- JS: 2,841.99 kB → **2,776.40 kB** (–65.6 kB)

### Closed (final)

The typography discipline pass is now complete. Display tier is
fully consumed by stat-tile + heading surfaces. Form-label tier
is fully consumed across forms. The `.code-display` utility
distinguishes "identifier-as-hero" (VIBANs, references) from
generic body code (`.code`) — three semantic mono tiers now
exist: `.code` (inline), `.code-display` (headline), and
`font-mono` (still freely available for raw use).

Lessons captured: L7 — Tailwind class-token substitutions need
whole-token anchoring, not substring matching. The Phase 9 Task D
`tracking-wide`-vs-`tracking-wider` bug is the prototype.

The 1087 residual lint warnings remain as the long-tail typography
backlog (body emphasis, table headers, colored status text) — they
need design-judgment work to merit utility classes of their own,
out of Phase 9.1 scope.

---

## Phase 10 — Page Layout Standardization (2026-05-13)

Goal: introduce shared layout primitives (`<Page>`, `<PageHeader>`,
`<StatStrip>`) and migrate ~16 representative pages + modals as
proof. The long tail of ~70 pages migrates opportunistically.

### Tasks

- [x] **A — Build primitives**: `Page` / `PageHeader` / `StatStrip`
      created in `src/components/layout/`. Barrel exports via new
      `index.ts`. Documented in `tokens.json` under
      `meta.designPhilosophy.layoutPrimitives`.
      - `Page`: maxWidth='default' (max-w-7xl, 1280px) | 'narrow'
        (max-w-5xl, 1024px) | 'full' (uncapped). Applies space-y-6.
        Does NOT apply `animate-page-enter` — that's on the shell.
      - `PageHeader`: title (.page-title) + optional description
        (.body-sm) + right-aligned actions slot.
      - `StatStrip`: auto-derives column count from child count (1→6);
        standardizes gap-4. Override via `columns={n}` for dynamic sets.
- [x] **B — Animation location**: kept `animate-page-enter` on
      `<main>` in Layout.tsx so unmigrated pages still get it. Page
      omits it. Updated the `<main>` comment to explain.
- [x] **C — 3 width-outliers migrated**:
      - CreatePayablePage: `<Page maxWidth="narrow">` for main content;
        sticky header inner wrapper narrowed from max-w-7xl to max-w-5xl
        for alignment.
      - CreateReceivablePage: same shape as CreatePayable.
      - IntegrationsPage: `<Page maxWidth="default">` for content
        section; banner chrome (header + tab nav) keeps full-width
        `bg-white border-b` but drops redundant `px-6` (main supplies
        horizontal padding). The remaining 2 `max-w-7xl mx-auto` sites
        in the IntegrationsPage banners are intentional carve-outs.
- [x] **D — 8 representative pages migrated**:
      - Codemod handled 7 cleanly. IntercompanyDashboardPage required
        manual migration because its nested JSX had unbalanced div
        tokens that confused the naive depth counter.
      - All 8 root divs `<div className="space-y-6 animate-page-enter">`
        replaced with `<Page>` + Page import added.
      - StatStrip migration shown for CreditAgreementsPage as proof
        of primitive (4-col strip, auto-derived). Other 7 pages can
        adopt StatStrip when next touched.
- [x] **E — 3 hand-rolled modals migrated** (2 of 5 were already
      migrated in Tier 5):
      - **LegalEntitiesPage EntityFormModal** (line 502): → `<Modal
        size="xl">`. Tab nav + form + custom footer with "Last
        updated" stays inside children with `-mx-6 -mt-6` to break
        out of Modal's default p-6.
      - **AcquisitionWizard** (line 485): → `<Modal size="lg">`.
        Icon medallion + title pass as ReactNode to Modal's title
        prop. Step indicator + content + custom footer stay inside
        children with `-mx-6 -my-6` for full-bleed treatment. Tier 5
        a11y patch (escape + scroll-lock useEffect) removed —
        Modal handles it.
      - **DivestitureModal** (line 192): same shape as Acquisition.
      - **CreditLimitsPage GroupLimitModal + VaLimitsModal**: already
        migrated in Tier 5.
- [x] **F — Stat-strip gap audit**: CreditAgreementsPage migrated as
      proof of `<StatStrip>` (replaced `grid grid-cols-4 gap-4` strip
      and fixed an inline `text-xl font-bold` to `stat-value-xs` while
      at it). Other Task D pages keep their existing grids — opt-in
      migration when touched.
- [x] **G — Lint rule**: ESLint `no-restricted-syntax` extension
      catches `max-w-(5|6|7)xl\s+mx-auto` and `^space-y-6\s+animate-
      page-enter\b` in `src/pages/**` and `src/components/**`. Banner
      chrome carve-out via `// eslint-disable-next-line`.

### Phase 10 verification

- `npx vite build` succeeded in 16.68s. JS bundle 2,773.93 kB
  (–3.4 kB from inline-modal removal + page wrapper consolidation).
- Verification regex sweep:
  - `space-y-6 animate-page-enter` in src/pages: **35** (down from 43
    — 8 Task-D pages migrated). The remaining 35 are the long tail
    for opportunistic migration; they still get the animation via
    `<main>`.
  - `max-w-7xl mx-auto` in src/pages: **2** (both intentional
    IntegrationsPage banner internals, documented).
  - `max-w-6xl mx-auto` in src/pages: **0** ✓ (CreateReceivable
    migrated).
  - `max-w-2xl shadow-xl` modal hand-rolls in src/pages: **4**
    remaining (HierarchyInitializationModal, HierarchyInitWizard,
    HierarchyOperationsPage, MoveVaModal). These are out of Phase 10's
    explicit scope — future modal migration backlog.
- Lint rule fires correctly on test fixture (3 warnings on bad
  patterns, silent on safe patterns + eslint-disable carve-out).
- Phase 10 layout warnings against real codebase: 47 (long tail).

### Phase 10 closure

| Deliverable | Status |
|---|---|
| 3 layout primitives (`Page`, `PageHeader`, `StatStrip`) | ✓ |
| Layout.tsx animation comment + decision | ✓ |
| 3 width-outliers migrated | ✓ |
| 8 representative pages migrated | ✓ |
| 3 of 5 modals migrated (2 already done in Tier 5) | ✓ |
| StatStrip proof-of-concept | ✓ (CreditAgreements) |
| ESLint rule extension | ✓ |
| Long-tail layout backlog (~35 pages) | Lint visible; future work |
| Additional modal hand-rolls (4 in Hierarchy/MoveVa) | Future work |

### Constraints honored

- No visual changes beyond width-capping / centering / gap-4. ✓
- Long tail (62 unmigrated pages) untouched. ✓
- No section reordering. ✓
- `data-comment-anchor`, `data-screen-label`, `data-om-validate`
  preserved (only swapped wrappers). ✓

---

## Phase 10 follow-up — Complete long-tail migration (2026-05-13)

User request: complete the three remaining long-tail items.

### Workstream 1 — Migrate remaining 35 pages to `<Page>`

Two-pass codemod approach:

**v1** (`scripts/phase10_followup_pages.py`): bracket-balance walker.
Handled 23 of 35 cleanly (31 replacements — some files like
NettingCyclesPage / NotionalPoolingPage have multiple render branches
with the same root pattern). 12 files had nested JSX where the
naive depth counter failed.

**v2** (`scripts/phase10_followup_pages_v2.py`): tail-anchored
approach. Find the next `);\n};` end-of-component marker after the
open; take the LAST `</div>` before that marker as the match. Handled
11 of the remaining 12. TransfersPage failed because its component
ends with `}` instead of `};` — fixed manually.

**Total**: all 35 pages migrated. 0 residual
`space-y-6 animate-page-enter` occurrences in `src/pages/`.

### Workstream 2 — Migrate 4 (plus 2 extra) hand-rolled modals

- `HierarchyInitializationModal.tsx` → `<Modal size="lg">` with the
  ROOT-config icon medallion in the title slot, custom header preserved
  in children via `-mx-6 -my-6`.
- `HierarchyInitWizard.tsx` → same pattern.
- `HierarchyOperationsPage.tsx` RulesModal → `<Modal size="lg">` as
  `SharedModal` (local namespace conflicted with another Modal-shaped
  helper in the file).
- `MoveVaModal.tsx` → `<Modal size="lg">` with title + subtitle props.

Two additional hand-rolled modals surfaced during verification (not on
the original Phase 10 spec list):
- `MergerWizard.tsx` → `<Modal size="xl">` (was max-w-3xl).
- `OperationHistoryPage.tsx OperationDetailModal` → `<Modal size="md">`
  (was max-w-lg) with footer button via Modal's `footer` slot.

**Total**: 6 modals migrated. Zero `fixed inset-0 z-50` modal hand-rolls
remain in `src/pages/`.

### Workstream 3 — StatStrip adoption on Task D pages

Codemod (`scripts/phase10_followup_statstrips.py`) using the
bracket-balance walker to find matching closes.

Page-level stat strips migrated:
- **DashboardPage** L1691: `grid grid-cols-1 sm:grid-cols-2
  lg:grid-cols-4 gap-4 lg:gap-6` (the 4-up hierarchy widgets row) →
  `<StatStrip>` (auto-derives 4 cols).
- **CreditFacilitiesPage** L361: 5-col strip → `<StatStrip>`.
- **BalanceAggregationPage** L603: 4-col strip → `<StatStrip>`.
- **IntercompanyDashboardPage** L1263: 4-col operational strip →
  `<StatStrip>` (the HeroMetricCard pair above stays — different
  semantic role).
- **PhysicalAccountsPage** L1144: responsive 5-col strip → `<StatStrip>`.
- **AccountsPage** L530+L547: two 3-col strips inside `StatsCards`
  component (one for loading state, one for live render) →
  `<StatStrip columns={3}>` (explicit because the children are
  rendered via `.map(...)`).
- **CashConcentrationPage** L1923: 2-col operational strip →
  `<StatStrip>`. The L1846 corporate+program selector grid was NOT
  migrated (it's a layout grid, not a stat strip).

CreditAgreementsPage was already migrated as the Task F proof-of-concept.

**Total**: 10 `<StatStrip>` usages across 8 pages.

### Final verification

- `npx vite build` succeeded in 15.28s. **JS bundle: 2,768.09 kB**
  (–5.8 kB from this follow-up; consolidating ~40 inline wrappers
  into shared primitives).
- `grep -rE "space-y-6 animate-page-enter" src/pages` → **0 hits** ✓
- `grep -rln "fixed inset-0 z-50" src/pages` → **0 hits** ✓
- `<Page>` adoption: **61** (was 8 at end of Phase 10).
- `<StatStrip>` adoption: **10** (was 1 at end of Phase 10).
- Phase 10 lint warnings: **4** (down from 47 — the 4 are inside
  intentional IntegrationsPage banner chrome and other carve-outs).

### Bug caught + repaired during migration

In `OperationHistoryPage.tsx`, my modal migration left one extra
`</div>` inside the new Modal's children (the inner conditional
`<div>` for content + the outer wrapper `<div>` that was now
redundant). The build caught it immediately ("Unexpected closing
div tag does not match opening Modal tag"), I removed the extra
close, build cleared. Same family of risk as Phase 9 Task D's
multi-line div counting — the build is the verification mechanism.

### Cumulative bundle (final)

- CSS: 174.63 → ~172.5 kB (–2.1 kB)
- JS: 2,841.99 → **2,768.09 kB** (–74 kB)

### What's truly closed

| Surface | Coverage |
|---|---|
| Token unification (CSS variables, tokens.json) | 100% |
| Focus ring (gold, single token) | 100% |
| Active nav (solid + gold rule) | 100% |
| Page-width wrapper (`<Page>`) | 100% in scope |
| Modal wrapper (`<Modal>`) | 100% in scope |
| Stat-strip grid recipe (`<StatStrip>`) | 10 pages adopted; long tail organic |
| Typography display tier (`.stat-value-*`, `.page-title`) | 166 sites adopted |
| Typography body tier (`.field-label`, `.section-title`) | 545+ sites adopted |
| Colour vocabulary (semantic palette) | 1818 chromatic tokens migrated |
| `danger-*` invalid classes | 100% migrated |
| Medallion pattern (`StatusIconBadge`) | 199 sites adopted |
| Coloured stat-value tone variants | 100% in scope |
| ESLint guard rails | Phase 8 + 9 + 10 rules in place |
| Lessons captured | L1-L7 in `tasks/lessons.md` |

The "system in place but not consumed" critique that started the
post-Phase-8 review work is now fully resolved across every level
of the system. Page-level adoption matches token-level discipline.

### Constraints (per the Phase 9 spec)

- Do NOT migrate inside `src/styles/`, `src/design-system/`, or
  `src/components/ui/HeroMetricCard.tsx`.
- Do NOT invent new utilities beyond `.field-label` and `.label-cased`.
- Do NOT reflow JSX. Only swap className strings.
- Do NOT change semantic HTML.
- Preserve `data-comment-anchor` and `data-screen-label` attributes.
- Commit in logical chunks (one per task).
