# VAM Portal Design System

## Swiss Minimalism Design Tokens

This design system implements a premium Swiss Minimalism aesthetic for the Virtual Account Management Portal. It provides a single source of truth for all design decisions through structured JSON tokens.

## File Structure

```
design-system/
├── tokens.json          # Source of truth - all design tokens
├── index.ts             # TypeScript utilities and token access
├── variables.css        # Pre-generated CSS custom properties
├── ThemeProvider.tsx    # React context for theme management
└── README.md            # This documentation
```

## Quick Start

### 1. Import CSS Variables

```css
/* In your main CSS file */
@import './design-system/variables.css';
```

### 2. Wrap Your App with ThemeProvider

```tsx
import { ThemeProvider } from './design-system/ThemeProvider';

function App() {
  return (
    <ThemeProvider defaultMode="system">
      <YourApp />
    </ThemeProvider>
  );
}
```

### 3. Use Design Tokens

```tsx
import { useTheme, useDesignTokens } from './design-system/ThemeProvider';
import { getToken, getButtonVariant } from './design-system';

function MyComponent() {
  const { resolvedMode, toggleMode } = useTheme();
  const tokens = useDesignTokens();
  
  return (
    <button 
      onClick={toggleMode}
      style={{ color: 'var(--color-text-primary)' }}
    >
      Current mode: {resolvedMode}
    </button>
  );
}
```

## Token Categories

### Colors

#### Primitive Colors (Raw Values)
> Updated 2026-09 — this section previously described the original Navy/Gold
> palette. A later palette swap (`tailwind.config.js`, Phase 12) replaced both;
> the names below are current.
- **Primary** (grey-slate "ink" family, replaces the original Navy): 11-shade
  scale, `primary-50` to `primary-950`. Named roles: 800 = banner slate, 900 =
  ink (main dark text), 950 = nav/deep surface (sidebar + dark-mode background).
- **Accent** (pacific cyan family, replaces the original Gold): 10-shade
  scale, `accent-50` to `accent-900` (`#1985a1` at 500) — fills, chart series,
  borders, CTAs.
- **Neutral**: Gray scale for text, backgrounds, borders
- **Semantic**: Green (success), Orange (warning — repalettised off Tailwind's
  amber so it no longer shares hex values with the gold-era accent), Red
  (error), Blue (info)
- **Categorical** (`cat-1`…`cat-8`): a separate 8-hue palette for
  typed-but-not-semantic data (account types, hierarchy levels, currency
  identity) — explicitly not for status, which stays success/warning/error/info.

#### Semantic Colors (Use These)
- **Brand**: `primary`, `primaryHover`, `accent`, `accentSubtle`
- **Text**: `primary`, `secondary`, `tertiary`, `inverse`, `link`
- **Background**: `primary`, `secondary`, `tertiary`, `inverse`, `overlay`
- **Border**: `default`, `subtle`, `strong`, `focus`
- **Status**: Each has `default`, `subtle`, `border`, `text` variants

#### Surfaces and borders (2026-09) — themed once, no `dark:` pairs
`bg-surface-*`, `border-edge*` and `divide-edge*` read CSS variables (`variables.css`), so they switch with the theme on their own. Do not write `bg-white dark:bg-primary-900` any more.

| Class | Light | Dark | Use |
|---|---|---|---|
| `bg-surface-page` | neutral-50 | primary-950 | page background |
| `bg-surface-card` | white | primary-900 | cards, modals, popovers, inputs |
| `bg-surface-muted` | neutral-100 | primary-800 | grouped/nested areas, chips |
| `border-edge` | neutral-200 | primary-800 | default border |
| `border-edge-subtle` / `divide-edge-subtle` | neutral-100 | primary-800 @ 60% | row dividers |
| `border-edge-strong` | neutral-300 | primary-700 | inputs, emphasised borders |

Opacity works (`bg-surface-card/80`). Specimen: Storybook → Design System / Elevation & Surfaces.

### Typography

#### Font sizes (2026-09) — semantic scale only
Raw Tailwind sizes (`text-xs/sm/base/lg/xl/2xl…`, `text-[Npx]`) no longer exist in `tailwind.config.js`;
ESLint (`no-restricted-syntax`) rejects them in `pages/` and `components/`.
1. Prefer a semantic class (`.caption`, `.body-sm`, `.label`, `.field-label`, `.section-title`, `.stat-value*`, …): size + weight + colour + dark mode.
2. Otherwise use a size utility: `text-caption` 12/16, `text-body-sm` 14/20, `text-body` 16/24, `text-body-lg` 18/28,
   `text-heading-sm|md|lg` 20/24/30, `text-stat-sm` 28, `text-stat` 36, `text-display` 48. Size + line-height only.
3. Live specimen: `npm run storybook` → Design System / Typography (`src/design-system/Typography.stories.tsx`).

#### Font Families
> Updated 2026-09 — this section previously documented a Fraunces-for-titles
> scheme that has since been fully retired (see below); the block below is
> current (`design-system/variables.css`).
```css
--font-sans:    'Geist', system-ui, sans-serif;        /* body, UI */
--font-numeric: 'Geist', system-ui, sans-serif;        /* stat values, amounts */
--font-title:   'Geist', system-ui, sans-serif;        /* page titles too — see note below */
--font-mono:    'Geist Mono', ui-monospace, monospace; /* identifiers only: IBANs, VIBANs, reference codes, entity IDs — never monetary amounts */
```

Geist is now the only typeface app-wide — `--font-sans`/`--font-title`/
`--font-numeric` all resolve to the same stack. This is the end state of a
multi-step migration: titles and figures ran on Fraunces (a variable serif),
then briefly Newsreader Variable, before both were dropped in favor of Geist
everywhere so no page carries a second serif identity alongside it. The
historical `--font-display` alias is retired — stat utilities read
`--font-numeric` directly. Fonts are self-hosted via `@fontsource` packages
(not a Google Fonts `@import`) — see the top of `styles/index.css`. See
`CLAUDE.md` "Frontend Aesthetics".

#### Text Styles (Composite)
| Token | Size | Weight | Use Case |
|-------|------|--------|----------|
| `displayXl` | 4rem | 600 | Hero headlines |
| `displayLg` | 3rem | 600 | Page titles |
| `displayMd` | 2.25rem | 600 | Section titles |
| `displaySm` | 1.875rem | 600 | Stat values |
| `headingXl` | 1.5rem | 600 | Card titles large |
| `headingLg` | 1.25rem | 600 | Card titles |
| `headingMd` | 1.125rem | 600 | Subsection titles |
| `headingSm` | 1rem | 600 | Small headings |
| `bodyLg` | 1.125rem | 400 | Large body text |
| `bodyMd` | 1rem | 400 | Default body |
| `bodySm` | 0.875rem | 400 | Small body |
| `caption` | 0.75rem | 400 | Timestamps, labels |
| `label` | 0.875rem | 500 | Form labels |
| `overline` | 0.75rem | 500 | Section overlines (uppercase) |
| `code` | 0.875rem | 400 | IBANs, references |

### Spacing

8px base grid with Tailwind-compatible scale:
```
0, 0.5 (2px), 1 (4px), 1.5 (6px), 2 (8px), 2.5 (10px), 
3 (12px), 4 (16px), 5 (20px), 6 (24px), 8 (32px), 
10 (40px), 12 (48px), 16 (64px), 20 (80px), 24 (96px)...
```

#### Semantic Spacing
- **Page**: `paddingX: 2rem`, `paddingY: 2rem`, `gap: 1.5rem`
- **Card**: `sm: 1rem`, `md: 1.5rem`, `lg: 2rem`
- **Form**: `gap: 1rem`, `labelGap: 0.375rem`
- **Button**: Sizes define padding and gaps

### Border Radius (2026-09 — four radii only)

`tailwind.config.js` `theme.borderRadius` is the only scale. `rounded-xl/2xl/3xl` and bare `rounded` do not exist (ESLint rejects them).

| Class | px | Use |
|---|---|---|
| `rounded-sm` | 4 | checkboxes, code chips |
| `rounded-md` | 8 | tooltips, small category tags |
| `rounded-lg` | 12 | controls and surfaces: buttons, inputs, cards, modals, popovers, icon medallions |
| `rounded-full` | 9999 | pills, badges, avatars, dots |

Borders are 1px; 2px only for the active-tab underline, selection and focus. Focus ring: `ring-2 ring-offset-2`.

### Shadows / Elevation (2026-09 — four levels)

`theme.boxShadow` maps Tailwind's names onto the canonical tokens (`--shadow-rest/hover/popover/modal` in `variables.css`). `shadow-soft/medium/strong/dropdown/popover` no longer exist.

| Class | Token | Use |
|---|---|---|
| `shadow-sm` (or bare `shadow`) | rest | cards at rest |
| `shadow-md` | hover | hover / raised |
| `shadow-lg` | popover | dropdowns, popovers, tooltips |
| `shadow-xl` / `shadow-2xl` | modal | modals, drawers |

Dark mode has no real shadows: depth comes from surface tone (page `primary-950` < card `primary-900` < muted `primary-800`).

### Iconography (2026-09)

- **Library:** `lucide-react` only. Do not draw custom icons; do not use glyph characters (✓ × ⚠️ ▲) as icons.
- **Sizes:** 12 `w-3 h-3` · 16 `w-4 h-4` (default) · 20 `w-5 h-5` · 24 `w-6 h-6` · 32 `w-8 h-8` · 48 `w-12 h-12` (empty-state art only). Default stroke; colour via `currentColor`.
- **Canonical icons:** success `CheckCircle` · warning `AlertTriangle` · error `XCircle` · info `Info` · add `Plus` · close `X` · tick `Check` · refresh `RefreshCw` · download `Download` · upload `Upload` · search `Search` · edit `Pencil` · delete `Trash2` · settings `Settings` · loading `Loader2` · bank `Landmark` · entity `Building2`.
- **Medallions:** use `StatusIconBadge` (`xs` 20/12, `sm` 32/16, `md` 40/20, `lg` 48/24, `xl` 64/32; `rounded` lg|full; `subtle`; `solid` for selected; `inverse` on dark heroes; `spin` with Loader2). Never hand-build an icon tile.
- **Allowed exceptions:** brand marks (sidebar logo, Copilot) and third-party logos (`ConnectorIcons.tsx` connector logos and their frames, Open Banking bank logos).
- **Specimens:** `npm run storybook` → Design System / Iconography, Shapes, Elevation & Surfaces.

### Animation

#### Durations
```css
--duration-instant: 0ms;
--duration-fast: 150ms;    /* Micro-interactions */
--duration-normal: 200ms;  /* Default transitions */
--duration-slow: 300ms;    /* Complex animations */
--duration-slower: 500ms;  /* Page transitions */
```

#### Easings
```css
--easing-linear: linear;
--easing-ease-in: cubic-bezier(0.4, 0, 1, 1);     /* Accelerate */
--easing-ease-out: cubic-bezier(0, 0, 0.2, 1);    /* Decelerate */
--easing-ease-in-out: cubic-bezier(0.4, 0, 0.2, 1); /* Smooth */
--easing-spring: cubic-bezier(0.34, 1.56, 0.64, 1); /* Bouncy */
```

### Z-Index

```css
--z-hide: -1;
--z-base: 0;
--z-raised: 1;
--z-dropdown: 10;
--z-sticky: 20;
--z-header: 30;
--z-sidebar: 40;
--z-modal: 50;
--z-popover: 60;
--z-toast: 70;
--z-max: 9999;
```

## Component Tokens

### Button Variants

```typescript
import { getButtonVariant, getButtonSize } from './design-system';

const primaryButton = getButtonVariant('primary');
// { background, backgroundHover, backgroundActive, text, border }

const mediumSize = getButtonSize('md');
// { paddingX, paddingY, fontSize, gap }
```

### Badge Variants

```typescript
import { getBadgeVariant } from './design-system';

const successBadge = getBadgeVariant('success');
// { background: '#ecfdf5', text: '#047857', border: '#a7f3d0' }
```

### Modal Sizes

```typescript
import { getModalSize } from './design-system';

const lgModal = getModalSize('lg');
// { maxWidth: '42rem' }
```

## Theme Management

### Using the Theme Hook

```tsx
import { useTheme } from './design-system/ThemeProvider';

function ThemeToggle() {
  const { mode, resolvedMode, setMode, toggleMode } = useTheme();
  
  return (
    <select value={mode} onChange={(e) => setMode(e.target.value)}>
      <option value="light">Light</option>
      <option value="dark">Dark</option>
      <option value="system">System</option>
    </select>
  );
}
```

### Responsive Breakpoints

```tsx
import { useTheme, useBreakpoint, useResponsiveValue } from './design-system/ThemeProvider';

function ResponsiveComponent() {
  const { breakpoint, isMobile, isTablet, isDesktop } = useTheme();
  const isLargeScreen = useBreakpoint('lg');
  
  const columns = useResponsiveValue({
    sm: 1,
    md: 2,
    lg: 4,
  });
  
  return (
    <div style={{ gridTemplateColumns: `repeat(${columns}, 1fr)` }}>
      {/* Content */}
    </div>
  );
}
```

### Reduced Motion

```tsx
import { usePrefersReducedMotion } from './design-system/ThemeProvider';

function AnimatedComponent() {
  const reducedMotion = usePrefersReducedMotion();
  
  return (
    <div className={reducedMotion ? '' : 'animate-fade-in'}>
      Content
    </div>
  );
}
```

## Tailwind Integration

Generate Tailwind config from tokens:

```typescript
import { generateTailwindTheme } from './design-system';

// In tailwind.config.js
const generatedTheme = generateTailwindTheme();

export default {
  theme: {
    extend: generatedTheme,
  },
};
```

## CSS Variable Usage

All tokens are available as CSS custom properties:

```css
.my-card {
  background: var(--color-bg-primary);
  border: 1px solid var(--color-border-default);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-rest);
  padding: var(--spacing-6);
  transition: var(--transition-default);
}

.my-card:hover {
  box-shadow: var(--shadow-hover);
}

.my-heading {
  font-family: var(--font-numeric);
  font-size: var(--font-size-2xl);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}
```

## Dark Mode

Dark mode is automatically handled when you:

1. Use CSS variables (they update automatically)
2. Use the `ThemeProvider` component
3. Add `.dark` class or `data-theme="dark"` to `<html>`

The system respects `prefers-color-scheme` when mode is set to `system`.

## Accessibility

The design system includes:

- **Reduced motion**: Detected and exposed via `usePrefersReducedMotion()`
- **High contrast**: Supported via `@media (prefers-contrast: high)`
- **Focus states**: Consistent focus ring pattern (`--shadow-focus-offset`)
- **Color contrast**: WCAG AA compliant color combinations

## Token Modification

1. Edit `tokens.json` with your changes
2. Run the build script to regenerate `variables.css`
3. TypeScript types are automatically inferred

## Best Practices

1. **Always use semantic tokens** over primitive values
2. **Use CSS variables** for runtime theme switching
3. **Use TypeScript utilities** for type-safe token access
4. **Respect user preferences** (dark mode, reduced motion)
5. **Use the spacing scale** consistently for visual rhythm
6. **Follow the shadow hierarchy** for proper depth perception

---

*Design System Version: 2.0.0*  
*Last Updated: 2024-02-12*
