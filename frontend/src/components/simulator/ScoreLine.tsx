import React from 'react';
import { cn, formatCompactCurrency } from '../../utils';
import type { ScoreLineResult } from './types';

// ============================================================================
// ScoreLine — one Optimisation-Score row: label + proportional bar + signed
// annual delta. Pure view. POSITIVE delta always = better (success tone);
// negative = danger.
// ============================================================================

export interface ScoreLineProps {
  line: ScoreLineResult;
  /** Largest |delta| across the panel's lines — drives the bar proportion. */
  maxAbsDelta: number;
  baseCurrency: string;
  className?: string;
}

const TONE_BAR: Record<ScoreLineResult['tone'], string> = {
  success: 'bg-success-500',
  danger: 'bg-error-500',
  neutral: 'bg-neutral-400',
};

const TONE_TEXT: Record<ScoreLineResult['tone'], string> = {
  success: 'text-success-700 dark:text-success-300',
  danger: 'text-error-700 dark:text-error-300',
  neutral: 'text-neutral-500 dark:text-neutral-400',
};

export const ScoreLine: React.FC<ScoreLineProps> = ({
  line,
  maxAbsDelta,
  baseCurrency,
  className,
}) => {
  const pct =
    maxAbsDelta > 0
      ? Math.min(100, (Math.abs(line.deltaAnnual) / maxAbsDelta) * 100)
      : 0;
  const sign = line.deltaAnnual > 0 ? '+' : line.deltaAnnual < 0 ? '−' : '';
  const deltaLabel = `${sign}${formatCompactCurrency(
    Math.abs(line.deltaAnnual),
    baseCurrency,
  )}`;

  return (
    <div className={cn('py-2', className)}>
      <div className="flex items-center justify-between gap-3">
        <span className="body-sm text-primary-900 dark:text-neutral-100">
          {line.label}
        </span>
        <span className={cn('code', TONE_TEXT[line.tone])}>{deltaLabel}</span>
      </div>
      <div
        className="mt-1.5 h-1.5 w-full rounded-full bg-neutral-100 dark:bg-primary-800/60 overflow-hidden"
        role="presentation"
      >
        <div
          className={cn('h-full rounded-full transition-all', TONE_BAR[line.tone])}
          style={{ width: `${pct}%` }}
        />
      </div>
      {line.detail && (
        <p className="mt-1 body-sm text-neutral-500 dark:text-neutral-400">
          {line.detail}
        </p>
      )}
    </div>
  );
};
