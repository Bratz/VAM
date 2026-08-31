/**
 * Aperture frontend ESLint configuration.
 *
 * Tier 8 Design System Unification (2026-05-13): added beyond the standard
 * TypeScript + React rules — a set of "no raw chromatic Tailwind classes"
 * lint rules in `src/pages/` and `src/components/`. These lock in the
 * page-adoption work shipped in Tiers 1-7 so future code drift gets caught
 * at PR time rather than rediscovered six months later by another design
 * review.
 *
 * The rules forbid:
 *   - bg-{red|green|blue|yellow|gray}-NN and friends — use semantic
 *     {error|success|info|warning|neutral} instead.
 *   - bg-danger-NN — Tailwind has no `danger` palette in this project;
 *     the class renders as nothing. Use `error` (which is defined).
 *   - text-gray-NN — use text-neutral-NN; gray ≠ neutral in default
 *     Tailwind (different hex values).
 *
 * Phase 12 Task H (2026-06-12): the warn-level training wheels came off.
 * The amber/orange/purple/… carve-outs that previously existed are GONE:
 *   - amber/orange → semantic `warning-*` (Phase 12 Task B migrated all
 *     ~100 sites).
 *   - purple/teal/indigo/pink/violet/emerald → categorical tokens
 *     `cat-1`…`cat-8` (Phase 12 Task C). Raw categorical hues are no
 *     longer sanctioned in pages/components.
 * Severity promoted from `warn` to `error` for the three enforced shapes:
 *   1. raw palette classes (full family list below),
 *   2. display-tier `text-(xl…4xl) font-(bold|semibold)` co-occurrence
 *      (use .stat-value* utilities / <StatTile>),
 *   3. `font-mono font-(bold|semibold)` (mono is for identifiers, never
 *      bold display).
 * The broader Phase 9 (all-size typography) and Phase 10 (layout
 * hand-roll) warn selectors were retired rather than promoted — their
 * remaining backlogs (~1090 + 9 sites) are outside Phase 12 scope and
 * promoting them would red-light the build. History preserved in
 * tasks/design-system-unification.md.
 *
 * To bypass for a specific line (e.g. a charts component that genuinely
 * needs `bg-red-500` for a data series): use `// eslint-disable-next-line
 * no-restricted-syntax -- <reason>` — the reason comment is REQUIRED;
 * bare disables get pushed back in review.
 */

// Pattern that matches forbidden Tailwind class tokens in a string.
// Targets: (state-prefix:)*(token)-(family)-NN(/NN)?
//   token  ∈ {text, bg, border, ring, from, to, via, placeholder, fill,
//             stroke, divide, outline, caret, decoration, accent, shadow}
//   family ∈ {red, green, blue, yellow, gray, danger}
// We intentionally do NOT match {amber, orange, purple, pink, cyan,
// emerald, indigo, teal} — see the doc comment above.
const FORBIDDEN_TOKEN_RE =
  '(?:dark|hover|focus|focus-visible|active|disabled|group-hover|peer-hover|md|sm|lg|xl|2xl):)*(?:text|bg|border|ring|from|to|via|placeholder|fill|stroke|divide|outline|caret|decoration|accent|shadow)-(?:red|green|blue|yellow|gray|danger)-\\\\d+(?:/\\\\d+)?';

module.exports = {
  root: true,
  env: { browser: true, es2020: true, node: true },
  extends: [
    'eslint:recommended',
    'plugin:@typescript-eslint/recommended',
    'plugin:react-hooks/recommended',
  ],
  ignorePatterns: [
    'dist',
    'build',
    'node_modules',
    '.eslintrc.cjs',
    'tailwind.config.js',
    'postcss.config.js',
    'vite.config.ts',
    'add_dark_variants.py',
    'apply_typography.py',
  ],
  parser: '@typescript-eslint/parser',
  parserOptions: {
    ecmaVersion: 'latest',
    sourceType: 'module',
    ecmaFeatures: { jsx: true },
  },
  plugins: ['react-refresh'],
  rules: {
    'react-refresh/only-export-components': 'warn',

    // The codebase has 500+ pre-existing TS6133 unused-import warnings.
    // Don't fail builds on those — they're a known follow-up cleanup.
    '@typescript-eslint/no-unused-vars': [
      'warn',
      { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
    ],
    '@typescript-eslint/no-explicit-any': 'off',
    'no-empty': ['warn', { allowEmptyCatch: true }],
  },
  overrides: [
    /* -----------------------------------------------------------------
     * Pages + UI components: enforce the design-system colour vocabulary
     * ----------------------------------------------------------------- */
    {
      files: ['src/pages/**/*.{ts,tsx}', 'src/components/**/*.{ts,tsx}'],
      excludedFiles: [
        // TaxChargeSetupPage intentionally uses categorical chromatic
        // colours for fee-type and jurisdiction icons. Documented in
        // tasks/design-system-unification.md Tier 2 carve-out.
        'src/pages/TaxChargeSetupPage.tsx',
      ],
      rules: {
        /* Phase 12 Task H (2026-06-12): severity warn → ERROR, and the rule
         * body was restructured around the three enforceable shapes. The
         * Phase 9 all-size typography selectors (text-xs…5xl + medium) and
         * the Phase 10 layout-hand-roll selectors were retired rather than
         * promoted — see the file header for the rationale. */
        'no-restricted-syntax': [
          'error',
          {
            // 1. Raw palette classes — full family list. Phase 12 extended
            //    the Tier 8 set (red/green/blue/yellow/gray/danger) with the
            //    previously-tolerated categorical hues; `cat-1`…`cat-8`
            //    tokens are the sanctioned alternative for typed-but-not-
            //    semantic data (Task C).
            //
            // Catches `<div className="bg-red-500">` but NOT
            // `<div className={cn(...)}>` — those go through cn/clsx and
            // would need eslint-plugin-tailwindcss to close; still out of
            // scope this phase.
            selector:
              "JSXAttribute[name.name='className'] > Literal[value=/(?:(?:dark|hover|focus|active|disabled|group-hover|peer-hover|md|sm|lg|xl|2xl):)*(?:text|bg|border|ring|from|to|via|placeholder|fill|stroke|divide|outline|caret|decoration|accent|shadow)-(?:red|green|blue|yellow|gray|danger|amber|orange|purple|teal|indigo|pink|violet|emerald)-\\d+/]",
            message:
              "Raw palette Tailwind class detected. Semantic statuses: red→error, green→success, blue→info, yellow/amber/orange→warning, gray→neutral. Categorical (typed-but-not-status) data: use cat-1…cat-8 tokens (Phase 12 Task C). For genuinely chromatic one-offs add `// eslint-disable-next-line no-restricted-syntax -- <reason>`.",
          },
          {
            // Same shape for template literals:
            // <div className={`bg-blue-500 ${active && 'bg-blue-600'}`}>
            selector:
              "JSXExpressionContainer > TemplateLiteral > TemplateElement[value.raw=/(?:(?:dark|hover|focus|active|disabled|group-hover|peer-hover|md|sm|lg|xl|2xl):)*(?:text|bg|border|ring|from|to|via|placeholder|fill|stroke|divide|outline|caret|decoration|accent|shadow)-(?:red|green|blue|yellow|gray|danger|amber|orange|purple|teal|indigo|pink|violet|emerald)-\\d+/]",
            message:
              "Raw palette Tailwind class in template literal. Semantic statuses or cat-1…cat-8 categorical tokens instead — see file header.",
          },
          {
            // 2. Display-tier typography hand-rolls. Narrowed from the
            //    retired Phase 9 all-size selector to the display tier only
            //    (xl–4xl + bold/semibold) — these are the stat-tile-shaped
            //    sins that <StatTile> / .stat-value* utilities exist for.
            selector:
              "JSXAttribute[name.name='className'] > Literal[value=/\\btext-(xl|2xl|3xl|4xl)\\s+font-(bold|semibold)\\b/]",
            message:
              "Display-tier `text-{xl…4xl} font-{bold|semibold}` detected. Use <StatTile> or the .stat-value* utilities (see tasks/design-system-unification.md Phase 12 Task E). One-offs need `// eslint-disable-next-line no-restricted-syntax -- <reason>`.",
          },
          {
            selector:
              "JSXExpressionContainer > TemplateLiteral > TemplateElement[value.raw=/\\btext-(xl|2xl|3xl|4xl)\\s+font-(bold|semibold)\\b/]",
            message:
              "Display-tier `text-{xl…4xl} font-{bold|semibold}` in template literal. Use <StatTile> / .stat-value* utilities.",
          },
          {
            // 3. Mono is for identifiers; bold mono reads as system-log
            //    shouting. Amounts use `.amount` (sans-tabular, Task D);
            //    stat values use `.stat-value*`.
            selector:
              "JSXAttribute[name.name='className'] > Literal[value=/\\bfont-mono\\s+font-(bold|semibold)\\b|\\bfont-(bold|semibold)\\s+font-mono\\b/]",
            message:
              "`font-mono` combined with bold/semibold. Identifiers stay mono at regular/medium weight (.code); monetary amounts use .amount; stat values use .stat-value*.",
          },
        ],
      },
    },
    /* -----------------------------------------------------------------
     * Utility-definition files: opt OUT of the Phase 9 typography rule.
     * These files DEFINE the utility classes; they must be allowed to
     * author raw `text-{size} font-{weight}` styles.
     * ----------------------------------------------------------------- */
    {
      files: [
        'src/styles/**/*.{ts,tsx,css}',
        'src/design-system/**/*.{ts,tsx}',
        'src/components/ui/HeroMetricCard.tsx',
        // Phase 9.1: enhanced.tsx defines shared Modal / Tabs / Alert /
        // ProgressBar etc. — these components author their own type styles
        // (Modal title text, Tab labels, etc.) and shouldn't be forced to
        // adopt the page-level typography utilities.
        'src/components/ui/enhanced.tsx',
        // Phase 12 Task E: StatTile is the canonical stat-display component —
        // it authors the display-tier styles everyone else must consume.
        'src/components/ui/StatTile.tsx',
      ],
      rules: {
        'no-restricted-syntax': 'off',
      },
    },
  ],
};
