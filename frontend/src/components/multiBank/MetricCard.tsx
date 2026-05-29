import React from 'react';
import { cn } from '../../utils';
import { TONE, STAT_VALUE_BY_TONE } from './metricCardTokens';

// ============================================================================
// Multi-Bank Liquidity — small metric card.
//
// Lifted out of MultiBankLiquidityPage as part of the Commit-1 view-extraction
// (the three-view Treasurer's Cockpit refactor). Behaviour and styling are
// preserved verbatim from the page's previous local definition — see the
// in-page comments for the design rationale that shipped with the earlier
// design-system conformance pass.
//
// Becomes a <button> when `onClick` is provided so the metric strip can
// double as a filter control.
//
// `TONE` and `STAT_VALUE_BY_TONE` live in `metricCardTokens.ts` so this file
// can export only the component (and keep React Fast Refresh happy).
// ============================================================================

export interface MetricCardProps {
  icon: React.ReactNode;
  tone: keyof typeof TONE;
  label: string;
  value: string;
  sub?: string;
  onClick?: () => void;
  active?: boolean;
}

export const MetricCard: React.FC<MetricCardProps> = ({ icon, tone, label, value, sub, onClick, active }) => {
  const t = TONE[tone] ?? TONE.neutral;
  const baseClass = cn(
    // shadow-sm aliases to --shadow-rest; dark-mode shadows were retired in
    // Phase 8 so no `dark:shadow-none` override is needed.
    'bg-white dark:bg-primary-900 rounded-lg p-5 shadow-sm border transition-colors',
    active
      ? 'border-primary-400 dark:border-accent-500/60 ring-2 ring-primary-200 dark:ring-accent-500/30'
      : 'border-neutral-200 dark:border-primary-800',
    onClick && !active && 'hover:border-primary-300 dark:hover:border-accent-500/40 cursor-pointer',
  );
  const inner = (
    <>
      <div className={cn('w-10 h-10 rounded-xl flex items-center justify-center mb-3', t.bg, t.fg)}>
        {icon}
      </div>
      <p className="text-sm text-neutral-500 dark:text-neutral-400">{label}</p>
      {/* Phase 9 display tier: Fraunces + tabular-nums via .stat-value-*
          utilities. Replaces the inline raw-utility recipe that bypassed the
          brand serif on the headline numbers. */}
      <p className={cn(STAT_VALUE_BY_TONE[tone] ?? 'stat-value-sm', 'mt-1')}>{value}</p>
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
        className={cn(baseClass, 'text-left w-full focus:outline-none focus-visible:ring-2 focus-visible:ring-primary-400 dark:focus-visible:ring-accent-400')}
      >
        {inner}
      </button>
    );
  }
  return <div className={baseClass}>{inner}</div>;
};
