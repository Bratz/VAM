import React from 'react';
import type { LucideIcon } from 'lucide-react';
import { cn } from '../../utils';

/**
 * Canonical "rounded medallion with a tinted icon" — the pattern that
 * appears 165+ times across the page layer as inline JSX:
 *
 *     <div className="w-10 h-10 rounded-xl bg-success-100 dark:bg-success-500/20
 *                     flex items-center justify-center">
 *       <CheckCircle2 className="w-5 h-5 text-success-600 dark:text-success-300" />
 *     </div>
 *
 * Replaced with:
 *
 *     <StatusIconBadge tone="success" icon={CheckCircle2} />
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
  | 'accent';

interface StatusIconBadgeProps {
  /** Semantic tone — drives background + icon colour together. */
  tone: Tone;
  /** Lucide icon component. Sized internally; do not pass className. */
  icon: LucideIcon;
  /**
   * Visual weight:
   *   - `sm` — 8x8 medallion with 4x4 icon. Inline / table-cell.
   *   - `md` — 10x10 medallion with 5x5 icon. Canonical card / stat tile (default).
   *   - `lg` — 12x12 medallion with 6x6 icon. Hero / page-title accent.
   */
  size?: 'sm' | 'md' | 'lg';
  /** Corner radius. Defaults to `xl` (matches the canonical card radius). */
  rounded?: 'lg' | 'xl' | 'full';
  /**
   * Lighter background variant — uses the 50/10% stops instead of
   * 100/20%. Use when the medallion is decorative rather than primary
   * (e.g. legend rows, sub-cards).
   */
  subtle?: boolean;
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

const TONE_CLASSES: Record<Tone, ToneClasses> = {
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
    default: { bg: 'bg-neutral-100 dark:bg-primary-800', text: 'text-neutral-600 dark:text-neutral-300' },
    subtle:  { bg: 'bg-neutral-50 dark:bg-primary-900',  text: 'text-neutral-500 dark:text-neutral-400' },
  },
  accent: {
    default: { bg: 'bg-accent-100 dark:bg-accent-500/20', text: 'text-accent-700 dark:text-accent-300' },
    subtle:  { bg: 'bg-accent-50 dark:bg-accent-500/10',  text: 'text-accent-700 dark:text-accent-300' },
  },
};

// Size pairs — outer dimensions plus the icon's matching tailwind size class.
const SIZE_CLASSES = {
  sm: { box: 'w-8 h-8',  icon: 'w-4 h-4' },
  md: { box: 'w-10 h-10', icon: 'w-5 h-5' },
  lg: { box: 'w-12 h-12', icon: 'w-6 h-6' },
} as const;

const ROUNDED_CLASSES = {
  lg: 'rounded-lg',
  xl: 'rounded-xl',
  full: 'rounded-full',
} as const;

export const StatusIconBadge: React.FC<StatusIconBadgeProps> = ({
  tone,
  icon: Icon,
  size = 'md',
  rounded = 'xl',
  subtle = false,
  className,
}) => {
  const tonePair = TONE_CLASSES[tone][subtle ? 'subtle' : 'default'];
  const dim = SIZE_CLASSES[size];

  return (
    <div
      className={cn(
        dim.box,
        ROUNDED_CLASSES[rounded],
        'flex items-center justify-center',
        tonePair.bg,
        className
      )}
    >
      <Icon className={cn(dim.icon, tonePair.text)} />
    </div>
  );
};
