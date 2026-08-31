/**
 * Categorical palette helper — Phase 12 (2026-06-12).
 *
 * The design system defines 8 categorical hues (`cat-1` … `cat-8`, each with
 * a `-soft` subtle-background pair) for typed-but-not-semantic data: account
 * types, hierarchy levels, agreement types, currency identity. NOT for
 * status — status stays success/warning/error/info.
 *
 * Tokens live in design-system/variables.css (--cat-N / --cat-N-soft),
 * design-system/tokens.json (color.categorical) and tailwind.config.js
 * (cat-N / cat-N-soft).
 */

/* Literal class strings (not template-interpolated) so Tailwind's content
 * scanner sees every class and generates it — interpolated `bg-cat-${n}`
 * would silently produce nothing. */
const CATEGORICAL_CLASSES = [
  'bg-cat-1-soft text-cat-1 dark:bg-cat-1/15 dark:text-cat-1',
  'bg-cat-2-soft text-cat-2 dark:bg-cat-2/15 dark:text-cat-2',
  'bg-cat-3-soft text-cat-3 dark:bg-cat-3/15 dark:text-cat-3',
  'bg-cat-4-soft text-cat-4 dark:bg-cat-4/15 dark:text-cat-4',
  'bg-cat-5-soft text-cat-5 dark:bg-cat-5/15 dark:text-cat-5',
  'bg-cat-6-soft text-cat-6 dark:bg-cat-6/15 dark:text-cat-6',
  'bg-cat-7-soft text-cat-7 dark:bg-cat-7/15 dark:text-cat-7',
  'bg-cat-8-soft text-cat-8 dark:bg-cat-8/15 dark:text-cat-8',
] as const;

/** Light+dark class pair for categorical hue n (1-8, wraps). Dark surfaces
 *  use hue/15 backgrounds + a lighter text tone per the design-system
 *  dark-mode guidance. Phase 12. */
export function categoricalClasses(n: number): string {
  return CATEGORICAL_CLASSES[((n - 1) % 8 + 8) % 8];
}
