import React from 'react';
import { formatCurrency } from '../utils';
import { cn } from '../utils';

export interface AmountProps {
  value: number;
  currency?: string;
  /** Prefix the currency code (e.g. "AED 10,000.00"). Default true. */
  showCurrency?: boolean;
  className?: string;
}

/**
 * The one currency formatter — full precision always, no K/M/B abbreviation.
 * Built on the existing currency-aware `formatCurrency` (JPY 0dp, BHD-family
 * 3dp, else 2dp) rather than reimplementing minor-unit logic.
 *
 * Renders the decimal part in a lighter tone so magnitude reads first, a
 * true minus (U+2212, not a hyphen) + danger colour for negatives — never
 * parentheses — and the `.figure` numeral treatment (tabular-nums
 * lining-nums, Newsreader). Stays inline so callers control alignment
 * (add `text-right` on the table cell / flex container, not here).
 */
export const Amount: React.FC<AmountProps> = ({ value, currency, showCurrency = true, className }) => {
  const isNegative = value < 0;
  const formatted = formatCurrency(Math.abs(value), currency); // "AED 10,000,000.00"
  const spaceIdx = formatted.indexOf(' ');
  const currencyCode = spaceIdx >= 0 ? formatted.slice(0, spaceIdx) : '';
  const numeric = spaceIdx >= 0 ? formatted.slice(spaceIdx + 1) : formatted;
  const dotIdx = numeric.lastIndexOf('.');
  const wholePart = dotIdx >= 0 ? numeric.slice(0, dotIdx) : numeric;
  const decimalPart = dotIdx >= 0 ? numeric.slice(dotIdx) : '';

  return (
    <span
      className={cn(
        'figure inline-flex items-baseline',
        isNegative ? 'text-error-700 dark:text-error-300' : undefined,
        className
      )}
    >
      {isNegative && <span aria-hidden>{'−'}</span>}
      {showCurrency && currencyCode && <span className="mr-1">{currencyCode}</span>}
      <span>{wholePart}</span>
      {decimalPart && <span className="text-neutral-500 dark:text-neutral-400">{decimalPart}</span>}
    </span>
  );
};
