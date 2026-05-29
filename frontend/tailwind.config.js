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
        // Primary - Deep navy for trust and professionalism
        primary: {
          50: '#f0f4f8',
          100: '#d9e2ec',
          200: '#bcccdc',
          300: '#9fb3c8',
          400: '#829ab1',
          500: '#627d98',
          600: '#486581',
          700: '#334e68',
          800: '#243b53',
          900: '#102a43',
          950: '#0a1929',
        },
        // Accent - Warm gold for highlights
        accent: {
          50: '#fffbeb',
          100: '#fef3c7',
          200: '#fde68a',
          300: '#fcd34d',
          400: '#fbbf24',
          500: '#f59e0b',
          600: '#d97706',
          700: '#b45309',
          800: '#92400e',
          900: '#78350f',
        },
        // Neutral grays
        neutral: {
          50: '#fafafa',
          100: '#f5f5f5',
          200: '#e5e5e5',
          300: '#d4d4d4',
          400: '#a3a3a3',
          500: '#737373',
          600: '#525252',
          700: '#404040',
          800: '#262626',
          900: '#171717',
          950: '#0a0a0a',
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
          50:  '#ecfdf5',
          100: '#d1fae5',
          200: '#a7f3d0',
          300: '#6ee7b7',
          400: '#34d399',
          500: '#10b981',
          600: '#059669',
          700: '#047857',
          800: '#065f46',
          900: '#064e3b',
        },
        // Warning — Phase 2 Design System Unification (2026-05-13):
        // Repalettised from the Tailwind amber family (which had identical
        // hex values to the gold/accent scale, making warnings read as brand
        // highlights) to the Tailwind orange family. Warnings now read as
        // genuinely warm-orange — visibly distinct from the gold accent.
        warning: {
          50:  '#fff7ed',
          100: '#ffedd5',
          200: '#fed7aa',
          300: '#fdba74',
          400: '#fb923c',
          500: '#f97316',
          600: '#ea580c',
          700: '#c2410c',
        },
        // Override Tailwind's built-in `amber` palette so direct callers
        // (`bg-amber-100`, `text-amber-700`, etc — used heavily across
        // warning banners, near-limit indicators, and shadow-account
        // strips) automatically pick up the new warm-orange hue. Otherwise
        // `bg-amber-*` would keep returning the original gold-equivalent
        // amber from Tailwind's defaults, defeating the unification.
        amber: {
          50:  '#fff7ed',
          100: '#ffedd5',
          200: '#fed7aa',
          300: '#fdba74',
          400: '#fb923c',
          500: '#f97316',
          600: '#ea580c',
          700: '#c2410c',
          800: '#9a3412',
          900: '#7c2d12',
        },
        error: {
          50:  '#fef2f2',
          100: '#fee2e2',
          200: '#fecaca',
          300: '#fca5a5',
          400: '#f87171',
          500: '#ef4444',
          600: '#dc2626',
          700: '#b91c1c',
          800: '#991b1b',
          900: '#7f1d1d',
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
      },
      fontFamily: {
        // Distinctive type pair — see CLAUDE.md "Frontend Aesthetics" and styles/index.css.
        sans: ['"Bricolage Grotesque"', 'system-ui', '-apple-system', 'sans-serif'],
        display: ['"Fraunces"', 'Georgia', 'ui-serif', 'serif'],
        mono: ['"JetBrains Mono"', 'Menlo', 'monospace'],
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
        'soft':       '0 1px 3px rgba(16, 42, 67, 0.06), 0 1px 2px rgba(16, 42, 67, 0.04)',
        'medium':     '0 4px 6px -1px rgba(16, 42, 67, 0.07), 0 2px 4px -1px rgba(16, 42, 67, 0.04)',
        'strong':     '0 25px 50px -12px rgba(16, 42, 67, 0.20)',
        'popover':    '0 10px 15px -3px rgba(16, 42, 67, 0.08), 0 4px 6px -2px rgba(16, 42, 67, 0.04)',
        'dropdown':   '0 10px 15px -3px rgba(16, 42, 67, 0.08), 0 4px 6px -2px rgba(16, 42, 67, 0.04)',
        'inner-soft': 'inset 0 2px 4px 0 rgba(16, 42, 67, 0.04)',
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
