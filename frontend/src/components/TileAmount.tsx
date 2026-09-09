import React from 'react';
import { formatAmountForTile, formatCurrency } from '../utils';
import { cn } from '../utils';

export interface TileAmountProps {
  value: number;
  currency?: string;
  /** Prefix the currency code (e.g. "AED 11.7M"). Default true — set false
      when the figure aggregates across currencies (e.g. a cross-currency
      cumulative total) so a single currency code isn't shown misleadingly. */
  showCurrency?: boolean;
  className?: string;
}

/**
 * Compact (K/M/B) currency display for stat tiles/cards (StatTile,
 * HeroMetricCard) — NOT for line items, table cells, or anything used to
 * reconcile a figure; use <Amount/> there instead. The abbreviation lives in
 * `formatAmountForTile` (utils/index.ts); this component pairs it with the
 * exact full-precision value as a native `title` tooltip, so the abbreviated
 * display never actually hides the real number, just doesn't force-fit it
 * into a box too small for it.
 */
export const TileAmount: React.FC<TileAmountProps> = ({ value, currency, showCurrency = true, className }) => {
  const compact = formatAmountForTile(value, currency);
  const full = formatCurrency(value, currency);
  const stripCurrency = (s: string) => {
    const spaceIdx = s.indexOf(' ');
    return spaceIdx >= 0 ? s.slice(spaceIdx + 1) : s;
  };
  return (
    <span
      className={cn('figure', className)}
      title={showCurrency ? full : stripCurrency(full)}
    >
      {showCurrency ? compact : stripCurrency(compact)}
    </span>
  );
};
