import React from 'react';
import { cn } from '../../utils';

/**
 * Dominant page-leading metric card.
 *
 * Aperture's pages typically lead with a headline financial figure (Total
 * Balance, Position, Pool Balance, etc.) that deserves more weight than
 * the surrounding operational tiles. This component delivers that:
 *
 *  - Full-width rounded card with subtle warm gradient
 *  - Soft gold "lens" glow anchored top-right (uses --section-accent-warm,
 *    so the hue shifts per route — gold for accounts, blue for treasury, etc.)
 *  - Primary metric rendered in Fraunces display at hero size (text-4xl/5xl)
 *  - Optional secondary metric mounted side-by-side for "Total / Available"
 *    storytelling
 *  - Optional icon, trend chip, and sub-line slot (currency chips, count, etc.)
 *
 * Reusable across pages. Owners of similar metric strips should adopt this
 * for their dominant figure and demote remaining numbers to the smaller
 * StatsCard / inline tile strip.
 */

export interface HeroMetric {
  label: string;
  value: React.ReactNode;
  /** Right-of-value pill like "+8.2%" or "▾ −3%". Tone-aware via {@link trendTone}. */
  trend?: string;
  trendTone?: 'success' | 'error' | 'neutral';
  /** Below-value content — currency chips, count breakdowns, footnotes. */
  sub?: React.ReactNode;
}

interface HeroMetricCardProps {
  primary: HeroMetric;
  /** When provided, sits to the right of the primary metric (vertical divider between). */
  secondary?: HeroMetric;
  /** Right-side icon medallion (e.g. Banknote, TrendingUp). Single icon serves both metrics. */
  icon?: React.ReactNode;
  /** Stagger this card's enter animation. Default 0.05s. */
  animationDelay?: number;
  className?: string;
}

export const HeroMetricCard: React.FC<HeroMetricCardProps> = ({
  primary,
  secondary,
  icon,
  animationDelay = 0.05,
  className,
}) => {
  return (
    <div
      className={cn(
        'relative rounded-2xl overflow-hidden animate-fade-in',
        'border border-neutral-200/70 dark:border-primary-800/60',
        'bg-gradient-to-br from-white via-white to-accent-50/40',
        'dark:from-primary-900/60 dark:via-primary-900/40 dark:to-accent-500/[0.06]',
        'p-6',
        className
      )}
      style={{ animationDelay: `${animationDelay}s` }}
    >
      {/* Ambient gold "lens" glow anchored to the value side. Uses
          --section-accent-warm so the hue shifts subtly per route. */}
      <div
        aria-hidden
        className="absolute -top-20 -right-20 w-80 h-80 rounded-full opacity-50 dark:opacity-70 blur-3xl pointer-events-none"
        style={{
          background:
            'radial-gradient(circle, rgb(var(--section-accent-warm) / 0.20) 0%, transparent 70%)',
        }}
      />

      {/* Anchored to the card's own corner (matches the glow above) rather
          than flex-aligned against the metric block below — it used to be
          an `items-end` flex sibling of the (variable-height) text block,
          so its vertical position drifted with however long each card's
          `sub` text happened to be, visibly misaligning the icon across a
          row of these cards (confirmed live on Cash Forecast: icons at
          three different heights despite the cards being the same size). */}
      {icon && (
        <div className="absolute top-6 right-6 shrink-0 w-14 h-14 rounded-2xl bg-accent-100 dark:bg-accent-500/15 ring-1 ring-accent-200 dark:ring-accent-500/30 flex items-center justify-center">
          {icon}
        </div>
      )}

      <div className={cn('relative flex flex-wrap items-end gap-6', icon && 'pr-16')}>
        <div className="flex items-end gap-8 flex-wrap min-w-0">
          <MetricBlock metric={primary} dominant />
          {secondary && (
            <>
              <div className="hidden sm:block w-px self-stretch bg-neutral-200/60 dark:bg-primary-700/60" />
              <MetricBlock metric={secondary} />
            </>
          )}
        </div>
      </div>
    </div>
  );
};

/**
 * Internal renderer for one metric block. `dominant` controls font size —
 * primary metric gets the hero treatment, secondary stays a notch smaller
 * so the eye lands on the primary first.
 */
const MetricBlock: React.FC<{ metric: HeroMetric; dominant?: boolean }> = ({
  metric,
  dominant,
}) => {
  const trendClass = cn(
    'ml-2 text-xs font-semibold',
    metric.trendTone === 'error'
      ? 'text-error-600 dark:text-error-300'
      : metric.trendTone === 'neutral'
      ? 'text-neutral-500 dark:text-neutral-400'
      : 'text-success-600 dark:text-success-300'
  );

  return (
    <div className="min-w-0">
      <p className="text-xs font-semibold text-neutral-500 dark:text-neutral-400 mb-2">
        {metric.label}
      </p>
      <p
        className={cn(
          'stat-value leading-none flex items-baseline flex-wrap',
          dominant ? 'text-4xl sm:text-5xl' : 'text-2xl sm:text-3xl'
        )}
      >
        <span>{metric.value}</span>
        {metric.trend && <span className={trendClass}>{metric.trend}</span>}
      </p>
      {metric.sub && (
        <div className="text-sm text-neutral-500 dark:text-neutral-400 mt-3">
          {metric.sub}
        </div>
      )}
    </div>
  );
};
