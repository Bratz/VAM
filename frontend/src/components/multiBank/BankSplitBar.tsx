import React from 'react';
import { cn, formatCurrency } from '../../utils';
import { formatPct } from './format';

// ============================================================================
// Multi-Bank Liquidity — bank-split horizontal bar.
//
// Within a single currency, summing bank balances is FX-honest, so a
// value-weighted horizontal bar accurately answers "where does this currency
// sit?". The same composition rule (home bank first in `success` tones,
// externals after in `info` tones in descending share order) drives both
// Overview's per-currency cards and ByCurrencyView's headers.
//
// The bar itself is a 12px-high stack of width-proportional segments. The
// legend underneath lists each bank with amount + percentage, home-bank rows
// coloured with the `success` ramp and externals with neutral text.
// ============================================================================

export interface BankShare {
  bankBic: string;
  bankName?: string;
  homeBank: boolean;
  amount: number;
  currencyCode: string;
}

interface BankSplitBarProps {
  bankShares: BankShare[];
  /** Total to divide by. Defaults to sum of `bankShares[*].amount`. */
  total?: number;
}

export const BankSplitBar: React.FC<BankSplitBarProps> = ({ bankShares, total }) => {
  const computedTotal = total ?? bankShares.reduce((a, b) => a + (b.amount || 0), 0);
  // Order: home bank first, then externals by descending amount. That ordering
  // is what the bar's colour ramp expects (home is the single success segment
  // on the left, externals stack from biggest to smallest in info tones).
  const ordered = [...bankShares].sort((a, b) => {
    if (a.homeBank && !b.homeBank) return -1;
    if (!a.homeBank && b.homeBank) return 1;
    return (b.amount || 0) - (a.amount || 0);
  });

  // Info ramp slots so multiple external banks each get a distinct shade
  // within the `info` family — no new colour vocabulary.
  const externalRamp = [
    'bg-info-500 dark:bg-info-400',
    'bg-info-400 dark:bg-info-300',
    'bg-info-600 dark:bg-info-500',
    'bg-info-300 dark:bg-info-200',
  ];

  let extIdx = 0;
  const segments = ordered.map((b) => {
    const pct = computedTotal > 0 ? Math.max(0, (b.amount / computedTotal) * 100) : 0;
    const colour = b.homeBank
      ? 'bg-success-500 dark:bg-success-400'
      : externalRamp[extIdx++ % externalRamp.length];
    return { ...b, pct, colour };
  });

  return (
    <div>
      <div
        className="flex h-3 rounded-md overflow-hidden border border-neutral-200 dark:border-primary-800"
        role="img"
        aria-label="Bank distribution"
      >
        {segments.map((s) => (
          <div
            key={s.bankBic}
            className={cn('h-full', s.colour)}
            style={{ width: `${s.pct}%` }}
            title={`${s.bankName ?? s.bankBic} · ${formatCurrency(s.amount, s.currencyCode)}`}
          />
        ))}
      </div>
      <div className="flex flex-wrap gap-x-3 gap-y-1 mt-2">
        {segments.map((s) => (
          <span key={s.bankBic} className="inline-flex items-center gap-1.5 text-xs">
            <span className={cn('w-2 h-2 rounded-sm', s.colour)} />
            <span className={cn(
              s.homeBank
                ? 'text-success-700 dark:text-success-300 font-medium'
                : 'text-neutral-600 dark:text-neutral-300'
            )}>
              {s.bankName ?? s.bankBic}
            </span>
            <span className="font-mono text-neutral-500 dark:text-neutral-400">
              {formatCurrency(s.amount, s.currencyCode)} · {formatPct(s.pct)}
            </span>
          </span>
        ))}
      </div>
    </div>
  );
};
