import React from 'react';
import { cn } from '../utils';
import { Amount } from './Amount';

export interface PositionStripCell {
  label: string;
  value: number;
  currency?: string;
  /**
   * 'currency' (default) renders via <Amount/> — full-precision, never
   * summed across currencies (this app's FX-honest rule: no synthetic
   * cross-currency total). 'count' renders a plain grouped integer for
   * scalar counts (account counts etc.) that aren't a currency figure at
   * all — running a count through <Amount/> would wrongly imply a currency.
   */
  format?: 'currency' | 'count';
  /** 'lg' = 38px figure, wider flex-basis (the headline cell). 'sm' = 26px figure. */
  size?: 'lg' | 'sm';
  sublabel?: React.ReactNode;
  tone?: 'success' | 'warning' | 'neutral';
}

const toneClass: Record<NonNullable<PositionStripCell['tone']>, string> = {
  success: 'text-success-700 dark:text-success-300',
  warning: 'text-warning-700 dark:text-warning-300',
  neutral: 'text-neutral-500 dark:text-neutral-400',
};

/**
 * Full-width hairline position band — replaces a card-grid of KPI tiles.
 * No card wrapper, no shadow, no rounded corners (shadows are reserved for
 * overlays per the density spec). Below `lg` the dividers rotate from
 * vertical (between cells) to horizontal (between stacked rows).
 */
export const PositionStrip: React.FC<{ cells: PositionStripCell[] }> = ({ cells }) => (
  <div className="grid grid-cols-2 lg:flex border-b border-neutral-200 dark:border-primary-800">
    {cells.map((cell, i) => (
      <div
        key={cell.label}
        className={cn(
          'p-3',
          cell.size === 'lg' ? 'lg:flex-[1.5]' : 'lg:flex-1',
          // Vertical divider between lg-row cells; horizontal between
          // stacked grid rows below lg (every cell from index 2 on stacks
          // onto a new row in the 2-col grid, so border-t there).
          i > 0 && 'border-l border-neutral-200 dark:border-primary-800',
          i >= 2 && 'lg:border-t-0 border-t'
        )}
      >
        <p className="text-xs font-semibold text-neutral-600 dark:text-neutral-300">{cell.label}</p>
        {cell.format === 'count' ? (
          <p
            className={cn(
              'figure mt-1 font-semibold text-primary-900 dark:text-neutral-50',
              cell.size === 'lg' ? 'text-[38px]' : 'text-[26px]'
            )}
          >
            {cell.value.toLocaleString('en-US')}
          </p>
        ) : (
          <Amount
            value={cell.value}
            currency={cell.currency}
            className={cn(
              'block mt-1 font-semibold text-primary-900 dark:text-neutral-50',
              cell.size === 'lg' ? 'text-[38px]' : 'text-[26px]'
            )}
          />
        )}
        {cell.sublabel && (
          <p className={cn('text-xs mt-0.5', cell.tone ? toneClass[cell.tone] : 'text-neutral-500 dark:text-neutral-400')}>
            {cell.sublabel}
          </p>
        )}
      </div>
    ))}
  </div>
);
