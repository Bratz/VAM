import React from 'react';
import { cn } from '../../utils';
import { TONE, STAT_VALUE_BY_TONE } from './statTileTokens';

// ============================================================================
// StatTile — the canonical small stat / metric card.
//
// Phase 12 Task E: promoted from components/multiBank/MetricCard (which was
// itself lifted out of MultiBankLiquidityPage during the Commit-1
// view-extraction). Behaviour and styling of the original `stack` layout are
// preserved verbatim; `MetricCard` remains available as a compatibility
// re-export from components/multiBank/MetricCard.
//
// Becomes a <button> when `onClick` is provided so a metric strip can double
// as a filter control.
//
// Task E extensions (all optional, additive — existing callers unchanged):
//   - `icon` is now optional (several page clones render no medallion).
//   - `layout="row"` — compact horizontal variant (medallion left, text
//     right) for the dense operational strips that previously hand-rolled
//     `flex items-center gap-3` cards.
//   - `valueTone` — decouples the headline-number colour from the medallion
//     tone (e.g. info icon + success value on "Total Savings" tiles).
//     Defaults to `tone`.
//   - `loading` — replaces the value line with a skeleton shimmer.
//   - `delay` — opts into the page-load `animate-fade-in` stagger that the
//     migrated page clones used (`style={{ animationDelay }}`).
//
// `TONE` and `STAT_VALUE_BY_TONE` live in `statTileTokens.ts` so this file
// can export only the component (and keep React Fast Refresh happy).
//
// NOTE: this file is exempt from the display-tier typography lint
// (.eslintrc.cjs) — StatTile is ALLOWED to author display-tier styles; every
// other call site must consume it (or the .stat-value-* utilities) instead.
// ============================================================================

export interface StatTileProps {
  icon?: React.ReactNode;
  tone: keyof typeof TONE;
  label: string;
  /** Plain string/number, or a node like <TileAmount/> for compact currency
      display with a full-precision tooltip. */
  value: React.ReactNode;
  sub?: string;
  onClick?: () => void;
  active?: boolean;
  /** 'stack' (default) = medallion above label/value; 'row' = medallion left. */
  layout?: 'stack' | 'row';
  /** Override the value colour independently of the medallion tone. */
  valueTone?: keyof typeof STAT_VALUE_BY_TONE;
  /** Render a skeleton in place of the value while data loads. */
  loading?: boolean;
  /** Page-load stagger: adds `animate-fade-in` with this animation-delay. */
  delay?: string;
}

export const StatTile: React.FC<StatTileProps> = ({
  icon,
  tone,
  label,
  value,
  sub,
  onClick,
  active,
  layout = 'stack',
  valueTone,
  loading,
  delay,
}) => {
  const t = TONE[tone] ?? TONE.neutral;
  const valueClass = STAT_VALUE_BY_TONE[valueTone ?? tone] ?? 'stat-value-sm';
  const baseClass = cn(
    // shadow-sm aliases to --shadow-rest; dark-mode shadows were retired in
    // Phase 8 so no `dark:shadow-none` override is needed.
    'bg-white dark:bg-primary-900 rounded-lg p-5 shadow-sm border transition-colors',
    active
      ? 'border-primary-400 dark:border-accent-500/60 ring-2 ring-primary-200 dark:ring-accent-500/30'
      : 'border-neutral-200 dark:border-primary-800',
    onClick && !active && 'hover:border-primary-300 dark:hover:border-accent-500/40 cursor-pointer',
    delay && 'animate-fade-in',
  );
  const style = delay ? { animationDelay: delay } : undefined;

  const medallion = icon && (
    <div
      className={cn(
        'w-10 h-10 rounded-xl flex items-center justify-center shrink-0',
        layout === 'stack' && 'mb-3',
        t.bg,
        t.fg,
      )}
    >
      {icon}
    </div>
  );

  /* Phase 9 display tier: Fraunces + tabular-nums via .stat-value-*
     utilities. Replaces the inline raw-utility recipe that bypassed the
     brand serif on the headline numbers. */
  const valueEl = loading ? (
    <div className="skeleton h-7 w-20 rounded-lg mt-1" aria-hidden="true" />
  ) : (
    <p className={cn(valueClass, layout === 'row' ? 'mt-0.5' : 'mt-1')}>{value}</p>
  );

  const inner =
    layout === 'row' ? (
      <div className="flex items-center gap-3">
        {medallion}
        <div className="min-w-0 flex-1">
          <p className="text-sm text-neutral-500 dark:text-neutral-400">{label}</p>
          {valueEl}
          {sub && <p className="text-xs text-neutral-500 dark:text-neutral-400 mt-0.5 truncate" title={sub}>{sub}</p>}
        </div>
      </div>
    ) : (
      <>
        {medallion}
        <p className="text-sm text-neutral-500 dark:text-neutral-400">{label}</p>
        {valueEl}
        {sub && <p className="text-xs text-neutral-500 dark:text-neutral-400 mt-1">{sub}</p>}
      </>
    );

  if (onClick) {
    return (
      <button
        type="button"
        onClick={onClick}
        // aria-current: tile is selecting one filter from a mutually-exclusive
        // set (same model as the chip row).
        aria-current={active ? 'true' : undefined}
        style={style}
        className={cn(baseClass, 'text-left w-full focus:outline-none focus-visible:ring-2 focus-visible:ring-primary-400 dark:focus-visible:ring-accent-400')}
      >
        {inner}
      </button>
    );
  }
  return (
    <div className={baseClass} style={style}>
      {inner}
    </div>
  );
};
