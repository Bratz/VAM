import React from 'react';
import type { LucideIcon } from 'lucide-react';
import { cn } from '../../utils';

/**
 * Canonical "rounded medallion with a tinted icon" — the pattern that
 * appears 165+ times across the page layer as inline JSX:
 *
 *     <div className="w-10 h-10 rounded-lg bg-success-100 dark:bg-success-500/20
 *                     flex items-center justify-center">
 *       <CheckCircle className="w-5 h-5 text-success-600 dark:text-success-300" />
 *     </div>
 *
 * Replaced with:
 *
 *     <StatusIconBadge tone="success" icon={CheckCircle} />
 *
 * Tier 3 Design System Unification (2026-05-13). The benefits:
 *   1. One source of truth for the medallion's shape — radius, size,
 *      dark-mode opacity, foreground/background colour pairing.
 *   2. Future restyling is a one-file change (e.g. shift from rounded-xl
 *      to rounded-lg, or bump dark-mode bg from /20 to /25) instead of
 *      a 165-site sweep.
 *   3. Enforced tone vocabulary — `tone` is a typed union over our
 *      semantic palette, so pages can no longer drift into raw
 *      `bg-emerald-100` / `bg-cyan-100` etc. while still calling it
 *      a status medallion.
 */

type Tone =
  | 'success'
  | 'warning'
  | 'error'
  | 'info'
  | 'primary'
  | 'neutral'
  | 'accent'
  | 'cat-1' | 'cat-2' | 'cat-3' | 'cat-4' | 'cat-5' | 'cat-6' | 'cat-7' | 'cat-8';

interface StatusIconBadgeProps {
  /** Semantic tone — drives background + icon colour together. */
  tone: Tone;
  /** Lucide icon component. Sized internally; do not pass className. */
  icon: LucideIcon;
  /**
   * Visual weight:
   *   - `xs` — 5x5 chip with 3x3 icon. Legend keys and tree rows.
   *   - `sm` — 8x8 medallion with 4x4 icon. Inline / table-cell.
   *   - `md` — 10x10 medallion with 5x5 icon. Canonical card / stat tile (default).
   *   - `lg` — 12x12 medallion with 6x6 icon. Hero / page-title accent.
   *   - `xl` — 16x16 medallion with 8x8 icon. Empty states.
   */
  size?: 'xs' | 'sm' | 'md' | 'lg' | 'xl';
  /** Corner radius: `lg` (12px, default) or `full` (circle). */
  rounded?: 'lg' | 'full';
  /**
   * Lighter background variant — uses the 50/10% stops instead of
   * 100/20%. Use when the medallion is decorative rather than primary
   * (e.g. legend rows, sub-cards).
   */
  subtle?: boolean;
  /**
   * Filled variant for selected / active states: solid tone background with white icon.
   * Not available for `neutral`-subtle semantics; use with primary/accent/status tones.
   */
  solid?: boolean;
  /** For badges on a dark hero/banner surface: translucent white tile and white icon (tone ignored). */
  inverse?: boolean;
  /** Spin the icon (loading states, with Loader2). */
  spin?: boolean;
  /** Extra utility classes (e.g. `shrink-0`, `mt-1`). */
  className?: string;
}

// ----------------------------------------------------------------------------
// Tone classes — one dictionary instead of inline string-template surgery
// at every callsite. Each tone provides bg + dark:bg + text + dark:text
// for both the default and subtle weights.
// ----------------------------------------------------------------------------

interface ToneClasses {
  default: { bg: string; text: string };
  subtle: { bg: string; text: string };
}

const CAT1: ToneClasses = {
  default: { bg: 'bg-cat-1-soft dark:bg-cat-1/15', text: 'text-cat-1 dark:text-cat-1-fg' },
  subtle:  { bg: 'bg-cat-1-soft dark:bg-cat-1/10', text: 'text-cat-1 dark:text-cat-1-fg' },
};
const CAT2: ToneClasses = {
  default: { bg: 'bg-cat-2-soft dark:bg-cat-2/15', text: 'text-cat-2 dark:text-cat-2-fg' },
  subtle:  { bg: 'bg-cat-2-soft dark:bg-cat-2/10', text: 'text-cat-2 dark:text-cat-2-fg' },
};
const CAT3: ToneClasses = {
  default: { bg: 'bg-cat-3-soft dark:bg-cat-3/15', text: 'text-cat-3 dark:text-cat-3-fg' },
  subtle:  { bg: 'bg-cat-3-soft dark:bg-cat-3/10', text: 'text-cat-3 dark:text-cat-3-fg' },
};
const CAT4: ToneClasses = {
  default: { bg: 'bg-cat-4-soft dark:bg-cat-4/15', text: 'text-cat-4 dark:text-cat-4-fg' },
  subtle:  { bg: 'bg-cat-4-soft dark:bg-cat-4/10', text: 'text-cat-4 dark:text-cat-4-fg' },
};
const CAT5: ToneClasses = {
  default: { bg: 'bg-cat-5-soft dark:bg-cat-5/15', text: 'text-cat-5 dark:text-cat-5-fg' },
  subtle:  { bg: 'bg-cat-5-soft dark:bg-cat-5/10', text: 'text-cat-5 dark:text-cat-5-fg' },
};
const CAT6: ToneClasses = {
  default: { bg: 'bg-cat-6-soft dark:bg-cat-6/15', text: 'text-cat-6 dark:text-cat-6-fg' },
  subtle:  { bg: 'bg-cat-6-soft dark:bg-cat-6/10', text: 'text-cat-6 dark:text-cat-6-fg' },
};
const CAT7: ToneClasses = {
  default: { bg: 'bg-cat-7-soft dark:bg-cat-7/15', text: 'text-cat-7 dark:text-cat-7-fg' },
  subtle:  { bg: 'bg-cat-7-soft dark:bg-cat-7/10', text: 'text-cat-7 dark:text-cat-7-fg' },
};
const CAT8: ToneClasses = {
  default: { bg: 'bg-cat-8-soft dark:bg-cat-8/15', text: 'text-cat-8 dark:text-cat-8-fg' },
  subtle:  { bg: 'bg-cat-8-soft dark:bg-cat-8/10', text: 'text-cat-8 dark:text-cat-8-fg' },
};

const TONE_CLASSES: Record<Tone, ToneClasses> = {
  'cat-1': CAT1, 'cat-2': CAT2, 'cat-3': CAT3, 'cat-4': CAT4,
  'cat-5': CAT5, 'cat-6': CAT6, 'cat-7': CAT7, 'cat-8': CAT8,
  success: {
    default: { bg: 'bg-success-100 dark:bg-success-500/20', text: 'text-success-600 dark:text-success-300' },
    subtle:  { bg: 'bg-success-50 dark:bg-success-500/10',  text: 'text-success-600 dark:text-success-300' },
  },
  warning: {
    default: { bg: 'bg-warning-100 dark:bg-warning-500/20', text: 'text-warning-600 dark:text-warning-300' },
    subtle:  { bg: 'bg-warning-50 dark:bg-warning-500/10',  text: 'text-warning-600 dark:text-warning-300' },
  },
  error: {
    default: { bg: 'bg-error-100 dark:bg-error-500/20', text: 'text-error-600 dark:text-error-300' },
    subtle:  { bg: 'bg-error-50 dark:bg-error-500/10',  text: 'text-error-600 dark:text-error-300' },
  },
  info: {
    default: { bg: 'bg-info-100 dark:bg-info-500/20', text: 'text-info-600 dark:text-info-300' },
    subtle:  { bg: 'bg-info-50 dark:bg-info-500/10',  text: 'text-info-600 dark:text-info-300' },
  },
  // Primary uses solid navy stops in dark mode (primary-500/20 wouldn't have
  // enough contrast against the navy surface — pages were already opting for
  // the solid `dark:bg-primary-700` treatment).
  primary: {
    default: { bg: 'bg-primary-100 dark:bg-primary-700',  text: 'text-primary-700 dark:text-neutral-200' },
    subtle:  { bg: 'bg-primary-50 dark:bg-primary-800',   text: 'text-primary-700 dark:text-neutral-200' },
  },
  // Neutral uses primary-{800,900} backgrounds in dark mode — neutral-700/800
  // would clash with the page's navy chrome.
  neutral: {
    default: { bg: 'bg-surface-muted', text: 'text-neutral-600 dark:text-neutral-300' },
    subtle:  { bg: 'bg-neutral-50 dark:bg-primary-900',  text: 'text-neutral-500 dark:text-neutral-400' },
  },
  accent: {
    default: { bg: 'bg-accent-100 dark:bg-accent-500/20', text: 'text-accent-700 dark:text-accent-300' },
    subtle:  { bg: 'bg-accent-50 dark:bg-accent-500/10',  text: 'text-accent-700 dark:text-accent-300' },
  },
};

// Solid (filled) backgrounds for selected / active states. Literal class names so Tailwind can see them.
const SOLID_BG: Record<Tone, string> = {
  success: 'bg-success-600 dark:bg-success-500',
  warning: 'bg-warning-600 dark:bg-warning-500',
  error: 'bg-error-600 dark:bg-error-500',
  info: 'bg-info-600 dark:bg-info-500',
  primary: 'bg-primary-900 dark:bg-accent-500',
  neutral: 'bg-neutral-900 dark:bg-primary-700',
  accent: 'bg-accent-600 dark:bg-accent-500',
  'cat-1': 'bg-cat-1', 'cat-2': 'bg-cat-2', 'cat-3': 'bg-cat-3', 'cat-4': 'bg-cat-4',
  'cat-5': 'bg-cat-5', 'cat-6': 'bg-cat-6', 'cat-7': 'bg-cat-7', 'cat-8': 'bg-cat-8',
};

// Size pairs — outer dimensions plus the icon's matching tailwind size class.
const SIZE_CLASSES = {
  xs: { box: 'w-5 h-5',  icon: 'w-3 h-3' },
  sm: { box: 'w-8 h-8',  icon: 'w-4 h-4' },
  md: { box: 'w-10 h-10', icon: 'w-5 h-5' },
  lg: { box: 'w-12 h-12', icon: 'w-6 h-6' },
  xl: { box: 'w-16 h-16', icon: 'w-8 h-8' },
} as const;

const ROUNDED_CLASSES = {
  lg: 'rounded-lg',
  full: 'rounded-full',
} as const;

export const StatusIconBadge: React.FC<StatusIconBadgeProps> = ({
  tone,
  icon: Icon,
  size = 'md',
  rounded = 'lg',
  subtle = false,
  solid = false,
  inverse = false,
  spin = false,
  className,
}) => {
  const tonePair = TONE_CLASSES[tone][subtle ? 'subtle' : 'default'];
  const dim = SIZE_CLASSES[size];

  return (
    <div
      className={cn(
        dim.box,
        size === 'xs' && rounded === 'lg' ? 'rounded-md' : ROUNDED_CLASSES[rounded],
        'flex items-center justify-center',
        inverse ? 'bg-white/10 border border-white/20 backdrop-blur-sm' : solid ? SOLID_BG[tone] : tonePair.bg,
        className
      )}
    >
      <Icon className={cn(dim.icon, inverse || solid ? 'text-white' : tonePair.text, spin && 'animate-spin')} />
    </div>
  );
};
