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
- **Navy** (Primary Brand): 11-shade scale from `navy-50` to `navy-950`
- **Gold** (Accent): 10-shade scale for highlights and CTAs
- **Neutral**: Gray scale for text, backgrounds, borders
- **Semantic**: Green (success), Amber (warning), Red (error), Blue (info)

#### Semantic Colors (Use These)
- **Brand**: `primary`, `primaryHover`, `accent`, `accentSubtle`
- **Text**: `primary`, `secondary`, `tertiary`, `inverse`, `link`
- **Background**: `primary`, `secondary`, `tertiary`, `inverse`, `overlay`
- **Border**: `default`, `subtle`, `strong`, `focus`
- **Status**: Each has `default`, `subtle`, `border`, `text` variants

### Typography

#### Font Families
```css
--font-sans: 'Inter', system-ui, sans-serif;
--font-mono: 'JetBrains Mono', monospace;
--font-display: 'Inter', system-ui, sans-serif;
```

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

### Border Radius

```css
--radius-none: 0;
--radius-sm: 0.25rem;     /* 4px - Subtle */
--radius-default: 0.375rem; /* 6px - Default */
--radius-md: 0.5rem;      /* 8px - Buttons, inputs */
--radius-lg: 0.75rem;     /* 12px - Cards */
--radius-xl: 1rem;        /* 16px - Large cards */
--radius-2xl: 1.5rem;     /* 24px - Modals */
--radius-full: 9999px;    /* Pills, avatars */
```

### Shadows

```css
--shadow-soft: 0 2px 8px -2px rgba(0,0,0,0.05), 0 4px 16px -4px rgba(0,0,0,0.1);
--shadow-medium: 0 4px 12px -2px rgba(0,0,0,0.08), 0 8px 24px -4px rgba(0,0,0,0.12);
--shadow-strong: 0 8px 24px -4px rgba(0,0,0,0.1), 0 16px 48px -8px rgba(0,0,0,0.15);
--shadow-inner-soft: inset 0 2px 4px 0 rgba(0,0,0,0.05);
--shadow-focus-offset: 0 0 0 2px white, 0 0 0 4px var(--color-border-focus);
```

**Usage Guidelines:**
- `soft`: Cards at rest, subtle elevation
- `medium`: Cards on hover, dropdowns, popovers
- `strong`: Modals, dialogs, overlays

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
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-soft);
  padding: var(--spacing-6);
  transition: var(--transition-default);
}

.my-card:hover {
  box-shadow: var(--shadow-medium);
}

.my-heading {
  font-family: var(--font-display);
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
