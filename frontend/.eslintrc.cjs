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
 * Tokens we DO allow (intentional exceptions to keep certain pages working):
 *   - bg-{amber|orange}-NN — Tailwind's amber palette is overridden in
 *     tailwind.config.js to the orange family in Phase 2; both work and
 *     resolve to warning's hue. They're "warning by another name."
 *   - bg-{purple|pink|cyan|emerald|indigo|teal}-NN — categorical palettes
 *     used in legitimately chromatic contexts (TaxChargeSetupPage's
 *     per-fee-type icons, currency mirror colour codes, etc.). These
 *     aren't semantic-status surfaces; banning them would be too strict.
 *
 * To bypass for a specific line (e.g. a charts component that genuinely
 * needs `bg-red-500` for a data series): use `// eslint-disable-next-line
 * no-restricted-syntax` with a comment explaining why.
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
        'no-restricted-syntax': [
          'warn',
          {
            // Forbid raw chromatic Tailwind tokens in className string literals.
            //
            // Selector: JSXAttribute whose name is `className` and whose
            // value is a string literal containing the forbidden pattern.
            // Catches `<div className="bg-red-500">` but NOT
            // `<div className={cn(...)}>` — those go through cn/clsx and
            // would need a different lint mechanism (eslint-plugin-tailwindcss
            // could close that gap; out of scope for this tier).
            selector:
              "JSXAttribute[name.name='className'] > Literal[value=/(?:(?:dark|hover|focus|active|disabled|group-hover|peer-hover|md|sm|lg|xl|2xl):)*(?:text|bg|border|ring|from|to|via|placeholder|fill|stroke|divide|outline|caret|decoration|accent|shadow)-(?:red|green|blue|yellow|gray|danger)-\\d+/]",
            message:
              "Raw chromatic Tailwind class detected. Use the semantic palette instead: red→error, green→success, blue→info, yellow→warning, gray→neutral, danger→error. See tasks/design-system-unification.md (Tiers 1+2). For genuinely chromatic UI (charts, status keys), add an eslint-disable comment explaining why.",
          },
          {
            // Same rule, but for template literals in className expressions:
            // <div className={`bg-blue-500 ${active && 'bg-blue-600'}`}>
            selector:
              "JSXExpressionContainer > TemplateLiteral > TemplateElement[value.raw=/(?:(?:dark|hover|focus|active|disabled|group-hover|peer-hover|md|sm|lg|xl|2xl):)*(?:text|bg|border|ring|from|to|via|placeholder|fill|stroke|divide|outline|caret|decoration|accent|shadow)-(?:red|green|blue|yellow|gray|danger)-\\d+/]",
            message:
              "Raw chromatic Tailwind class detected in template literal. Use the semantic palette instead: red→error, green→success, blue→info, yellow→warning, gray→neutral. See tasks/design-system-unification.md (Tiers 1+2).",
          },
          /* -------------------------------------------------------------
           * Phase 9 Task F — typography discipline (2026-05-13).
           * Bans raw `text-{size} font-{weight}` co-occurrence inside
           * pages + components. Forces authors onto the typography
           * utilities (.stat-value*, .section-title, .page-title,
           * .field-label, .label, .overline, .body, .body-sm, .caption,
           * .code).
           *
           * STATUS (post-MultiBankLiquidity review, 2026-05-13): the
           * Phase 9 codemod landed but ~1090 long-tail call sites remain
           * (most of them dynamic `cn('text-2xl font-bold', tone)` patterns
           * the literal-only selector can't catch). Rule stays at `warn`
           * until that backlog clears — promoting to `error` today would
           * red-light the whole codebase. The MultiBankLiquidity review
           * also flagged that a stronger selector covering `cn(...)`
           * arguments would have caught at least one of the slips on that
           * page; that's tracked alongside the backlog cleanup, not here.
           *
           * To author raw typography (the system files define them, not
           * consume them): files under src/styles/, src/design-system/,
           * and HeroMetricCard.tsx are excluded.
           * ------------------------------------------------------------- */
          {
            selector:
              "JSXAttribute[name.name='className'] > Literal[value=/\\btext-(xs|sm|base|lg|xl|2xl|3xl|4xl|5xl)\\s+font-(medium|semibold|bold)\\b/]",
            message:
              "Raw `text-{size} font-{weight}` co-occurrence detected. Use the typography utility for this role instead: .stat-value-sm (stat tiles), .section-title (section heads), .page-title (page H1), .field-label (form labels), .label / .overline (eyebrows), .body / .body-sm (body copy), .code (identifiers). See tasks/design-system-unification.md Phase 9. If this is genuinely a one-off (chart label, terminal block), add an eslint-disable comment explaining why.",
          },
          {
            selector:
              "JSXExpressionContainer > TemplateLiteral > TemplateElement[value.raw=/\\btext-(xs|sm|base|lg|xl|2xl|3xl|4xl|5xl)\\s+font-(medium|semibold|bold)\\b/]",
            message:
              "Raw `text-{size} font-{weight}` co-occurrence in template literal. See tasks/design-system-unification.md Phase 9 for the canonical utility classes.",
          },
          /* -------------------------------------------------------------
           * Phase 10 Task G — page-layout discipline (2026-05-13).
           * Catches the two shapes that indicate a page is hand-rolling
           * its own max-width / vertical rhythm instead of consuming the
           * <Page> primitive:
           *   (1) `max-w-(5|6|7)xl mx-auto`  — width-cap hand-roll
           *   (2) `space-y-6 animate-page-enter` — root-div hand-roll
           * Configured as `warn` not `error` — pages are migrating opportun-
           * istically. The fix in both cases is "use <Page>".
           * ------------------------------------------------------------- */
          {
            selector:
              "JSXAttribute[name.name='className'] > Literal[value=/\\bmax-w-(5|6|7)xl\\s+mx-auto\\b/]",
            message:
              "Hand-rolled page-width wrapper (`max-w-(5|6|7)xl mx-auto`) detected. Use <Page maxWidth=\"default\"|\"narrow\"|\"full\"> from src/components/layout/. See tasks/design-system-unification.md Phase 10. Exception: inside banner chrome (page-spanning header/footer with bg-X border-b), the max-w-* wrapper is acceptable — add an eslint-disable comment.",
          },
          {
            selector:
              "JSXAttribute[name.name='className'] > Literal[value=/^space-y-6\\s+animate-page-enter\\b/]",
            message:
              "Page root using `space-y-6 animate-page-enter` directly. Use <Page> from src/components/layout/. The <main> shell already applies `animate-page-enter`; <Page> applies `space-y-6`. See tasks/design-system-unification.md Phase 10.",
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
      ],
      rules: {
        'no-restricted-syntax': 'off',
      },
    },
  ],
};
