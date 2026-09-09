/** @type {import('tailwindcss').Config} */
export default {
  // F5: enable .dark: variants. ThemeProvider sets `dark` class on <html>.
  darkMode: 'class',
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      // Swiss Minimalist Design System
      colors: {
        // Primary — grey-slate "ink" family (palette swap, replaces navy).
        // 800/900/950 are named roles from the spec: 800 = banner slate
        // (#4c5c68), 900 = ink (#46494c, main dark text), 950 = nav/deep
        // surface (#3f4a54, sidebar + dark-mode background).
        primary: {
          50: '#f4f4f4',
          100: '#e5e6e6',
          200: '#cfd0d0',
          300: '#b5b6b7',
          400: '#999b9d',
          500: '#818385',
          600: '#6b6d70',
          700: '#595b5e',
          800: '#4c5c68',
          900: '#46494c',
          950: '#3f4a54',
        },
        // Accent — pacific cyan family (palette swap, replaces gold).
        // 500 = pacific cyan (#1985a1, fills/chart series/borders — 4.28:1
        // on white, never for 12-13px text per the contrast rule). 700 =
        // accent deep (#146b80, 6.10:1 — buttons and 13px link text).
        accent: {
          50: '#f1f8f9',
          100: '#dfeef2',
          200: '#c3dfe7',
          300: '#a3ced9',
          400: '#81bccb',
          500: '#1985a1',
          600: '#177891',
          700: '#146b80',
          800: '#115b6d',
          900: '#0e4b5a',
        },
        // Neutral grays — re-anchored to the new light-surface roles.
        // Ordered by actual lightness: surface-subtle (#f2f2f3) is lighter
        // than page-ground (#e9e9ea). 700-950 interpolated toward ink
        // (not individually specified by the palette spec).
        neutral: {
          50: '#f2f2f3',
          100: '#e9e9ea',
          200: '#dcdcdd',
          300: '#c5c3c6',
          400: '#b0aeb1',
          500: '#5d6165',
          600: '#54585c',
          700: '#4c4f52',
          800: '#343638',
          900: '#252627',
          950: '#1a1a1b',
        },
        // Semantic colors — Tier 2 page-adoption (2026-05-13):
        // Expanded from partial (50/500/600/700) to full 50–900 scales.
        // The partial palettes were a trap: pages writing `bg-green-100`
        // semantically (success banner) couldn't migrate to `bg-success-100`
        // because that class wasn't defined — it would render as nothing.
        // Codemod-friendly now: every `bg-green-*` / `text-green-*` etc.
        // can rewrite to `bg-success-*` and resolve correctly.
        // Hex values mirror Tailwind's built-in green/red/blue so the
        // rewrite is pixel-identical.
        success: {
          50:  '#f2f6f5',
          100: '#e1eae7',
          200: '#c7d8d3',
          300: '#a4c0b7',
          400: '#7da698',
          500: '#578b7a',
          600: '#3d7965',
          700: '#276a54',
          800: '#205745',
          900: '#194537',
        },
        // Warning — Phase 2 Design System Unification (2026-05-13):
        // Repalettised from the Tailwind amber family (which had identical
        // hex values to the gold/accent scale, making warnings read as brand
        // highlights) to the Tailwind orange family. Warnings now read as
        // genuinely warm-orange — visibly distinct from the gold accent.
        warning: {
          50:  '#f8f5f1',
          100: '#efe9de',
          200: '#e1d5c2',
          300: '#cebc9c',
          400: '#b99f72',
          500: '#a48248',
          600: '#966f2c',
          700: '#8a5f14',
          800: '#714e10',
          900: '#5a3e0d',
        },
        // Override Tailwind's built-in `amber` palette so direct callers
        // (`bg-amber-100`, `text-amber-700`, etc — used heavily across
        // warning banners, near-limit indicators, and shadow-account
        // strips) automatically pick up the new warm-orange hue. Otherwise
        // `bg-amber-*` would keep returning the original gold-equivalent
        // amber from Tailwind's defaults, defeating the unification.
        amber: {
          50:  '#f8f5f1',
          100: '#efe9de',
          200: '#e1d5c2',
          300: '#cebc9c',
          400: '#b99f72',
          500: '#a48248',
          600: '#966f2c',
          700: '#8a5f14',
          800: '#714e10',
          900: '#5a3e0d',
        },
        error: {
          50:  '#faf4f3',
          100: '#f3e5e4',
          200: '#e8cecc',
          300: '#dab0ad',
          400: '#cb8f8a',
          500: '#bb6d67',
          600: '#b15750',
          700: '#a8443c',
          800: '#8a3831',
          900: '#6d2c27',
        },
        info: {
          50:  '#eff6ff',
          100: '#dbeafe',
          200: '#bfdbfe',
          300: '#93c5fd',
          400: '#60a5fa',
          500: '#3b82f6',
          600: '#2563eb',
          700: '#1d4ed8',
          800: '#1e40af',
          900: '#1e3a8a',
        },
        // Categorical palette — for typed-but-not-semantic data (account
        // types, hierarchy levels, agreement types, currency identity).
        // 8 hues, each with subtle bg (`cat-N-soft`) / strong text (`cat-N`)
        // pairs for light + dark. NOT for status — status stays
        // success/warning/error/info. Callers write `bg-cat-2-soft
        // text-cat-2` (dark: `dark:bg-cat-2/15 dark:text-cat-2`). Mirrors
        // --cat-N / --cat-N-soft in design-system/variables.css and
        // color.categorical in tokens.json. Phase 12 (2026-06-12).
        // Palette swap: rebuilt from the grey-slate-cyan family instead of
        // indigo/purple/pink (which would fight the new palette) — pacific
        // cyan, accent-deep, banner-slate, nav-deep, success, warning,
        // danger, secondary-text.
        'cat-1': '#1985a1', 'cat-1-soft': '#e8f3f6',  // pacific cyan
        'cat-2': '#146b80', 'cat-2-soft': '#e8f0f2',  // accent deep
        'cat-3': '#4c5c68', 'cat-3-soft': '#edeff0',  // banner slate
        'cat-4': '#3f4a54', 'cat-4-soft': '#ecedee',  // nav deep
        'cat-5': '#276a54', 'cat-5-soft': '#e9f0ee',  // success
        'cat-6': '#8a5f14', 'cat-6-soft': '#f3efe8',  // warning
        'cat-7': '#a8443c', 'cat-7-soft': '#f6ecec',  // danger
        'cat-8': '#5d6165', 'cat-8-soft': '#efeff0',  // secondary text
      },
      fontFamily: {
        // Tier 3 stack (Phase 12, 2026-06-12) — see styles/index.css.
        // `sans` drives Tailwind preflight's body font: Open Sans replaced
        // Bricolage Grotesque, which had been hardcoded here SEPARATELY
        // from the --font-sans token (silent divergence — keep this line
        // aligned with design-system/variables.css).
        // `display` backs the `font-display` UTILITY CLASS (brand wordmark
        // serif) — unrelated to the retired --font-display CSS variable;
        // stays Fraunces by design.
        sans: ['"Geist"', 'system-ui', '-apple-system', '"Segoe UI"', 'sans-serif'],
        // 'Newsreader Variable' is the actual registered family name for
        // @fontsource-variable/newsreader — not 'Newsreader'.
        display: ['"Newsreader Variable"', 'Georgia', 'ui-serif', 'serif'],
        mono: ['"Geist Mono"', 'ui-monospace', 'Menlo', 'monospace'],
      },
      fontSize: {
        // Typography scale
        'display-xl': ['4rem', { lineHeight: '1.1', letterSpacing: '-0.02em', fontWeight: '600' }],
        'display-lg': ['3rem', { lineHeight: '1.1', letterSpacing: '-0.02em', fontWeight: '600' }],
        'display-md': ['2.25rem', { lineHeight: '1.2', letterSpacing: '-0.02em', fontWeight: '600' }],
        'display-sm': ['1.875rem', { lineHeight: '1.2', letterSpacing: '-0.01em', fontWeight: '600' }],
        'heading-xl': ['1.5rem', { lineHeight: '1.3', fontWeight: '600' }],
        'heading-lg': ['1.25rem', { lineHeight: '1.4', fontWeight: '600' }],
        'heading-md': ['1.125rem', { lineHeight: '1.4', fontWeight: '600' }],
        'heading-sm': ['1rem', { lineHeight: '1.5', fontWeight: '600' }],
        'body-lg': ['1.125rem', { lineHeight: '1.6' }],
        'body-md': ['1rem', { lineHeight: '1.6' }],
        'body-sm': ['0.875rem', { lineHeight: '1.5' }],
        'caption': ['0.75rem', { lineHeight: '1.4' }],
      },
      spacing: {
        '18': '4.5rem',
        '88': '22rem',
        '128': '32rem',
      },
      // Border radius — Phase 3 Design System Unification (2026-05-13).
      // Collapsed from 6 distinct values (sm/DEFAULT/md/lg/xl/2xl spanning
      // 4–24px) to 3 canonical values (sm 4px / md 8px / lg 12px). The
      // `xl` and `2xl` utility classes now resolve to the same 12px as
      // `lg`, so card surfaces unify visually whether the author wrote
      // `rounded-lg`, `rounded-xl`, or `rounded-2xl`. No page sweep
      // required — the alias resolves at compile time.
      borderRadius: {
        'sm':      '0.25rem',  // 4px  — badges, pills, chips
        'DEFAULT': '0.5rem',   // 8px  — was 6px, aligned to md
        'md':      '0.5rem',   // 8px  — buttons, inputs
        'lg':      '0.75rem',  // 12px — cards, modals (canonical)
        'xl':      '0.75rem',  // was 16px → canonical lg
        '2xl':     '0.75rem',  // was 24px → canonical lg
      },
      // Box shadows — Phase 3 + post-review (2026-05-13). Tailwind utility
      // classes `shadow-soft / -medium / -strong` previously had hardcoded
      // values that bypassed the canonical shadow tokens declared in
      // variables.css, so `enhanced.tsx`'s `shadow-strong` on the modal
      // landed on a 24/48px black shadow instead of the canonical
      // `--shadow-modal` (50px navy). Aligned them: soft → rest, medium
      // → hover, strong → modal. The values mirror the canonical depth
      // scale verbatim so the Tailwind utility and the CSS variable can
      // never drift again. (We can't reference CSS vars from Tailwind
      // config — the values have to be inlined here.)
      //
      // Tier 1 page-adoption (2026-05-13): added `dropdown` and `popover`
      // as Tailwind utility names so dropdown / popover surfaces can be
      // authored without the silent-fallback bug that hit
      // `shadow-dropdown` (it was referenced in 2 pages but never defined
      // — those dropdowns rendered with no shadow at all).
      boxShadow: {
        // rgba triple is ink (#46494c) — palette swap (was navy #102a43).
        'soft':       '0 1px 3px rgba(70, 73, 76, 0.06), 0 1px 2px rgba(70, 73, 76, 0.04)',
        'medium':     '0 4px 6px -1px rgba(70, 73, 76, 0.07), 0 2px 4px -1px rgba(70, 73, 76, 0.04)',
        'strong':     '0 25px 50px -12px rgba(70, 73, 76, 0.20)',
        'popover':    '0 10px 15px -3px rgba(70, 73, 76, 0.08), 0 4px 6px -2px rgba(70, 73, 76, 0.04)',
        'dropdown':   '0 10px 15px -3px rgba(70, 73, 76, 0.08), 0 4px 6px -2px rgba(70, 73, 76, 0.04)',
        'inner-soft': 'inset 0 2px 4px 0 rgba(70, 73, 76, 0.04)',
      },
      animation: {
        'fade-in': 'fadeIn 0.2s ease-out',
        'slide-up': 'slideUp 0.3s ease-out',
        'slide-down': 'slideDown 0.3s ease-out',
        'scale-in': 'scaleIn 0.2s ease-out',
        'pulse-soft': 'pulseSoft 2s infinite',
      },
      keyframes: {
        fadeIn: {
          '0%': { opacity: '0' },
          '100%': { opacity: '1' },
        },
        slideUp: {
          '0%': { transform: 'translateY(10px)', opacity: '0' },
          '100%': { transform: 'translateY(0)', opacity: '1' },
        },
        slideDown: {
          '0%': { transform: 'translateY(-10px)', opacity: '0' },
          '100%': { transform: 'translateY(0)', opacity: '1' },
        },
        scaleIn: {
          '0%': { transform: 'scale(0.95)', opacity: '0' },
          '100%': { transform: 'scale(1)', opacity: '1' },
        },
        pulseSoft: {
          '0%, 100%': { opacity: '1' },
          '50%': { opacity: '0.7' },
        },
      },
      transitionDuration: {
        '250': '250ms',
        '350': '350ms',
      },
    },
  },
  plugins: [],
}
