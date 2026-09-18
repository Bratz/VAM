import React from 'react';
import { cn, formatCurrency } from '../../utils';
import { formatPct } from './format';

// ============================================================================
// Multi-Bank Liquidity — bank-split horizontal bar.
//
// Within a single currency, summing bank balances is FX-honest, so a
// value-weighted horizontal bar accurately answers "where does this currency
// sit?". The same composition rule (home bank first, externals after in
// descending share order) drives both Overview's per-currency cards and
// ByCurrencyView's headers.
//
// The bar itself is a 12px-high stack of width-proportional segments. The
// legend underneath lists each bank with amount + percentage. Colours are
// plain categorical identity (this app's teal `accent` for home, a neutral
// gray ramp for externals) — NOT the success/info semantic tokens, which
// this app reserves for status; a bank being home or external isn't a
// status judgement, and `accent` already matches the "HOME BANK" badge used
// alongside this bar in ByBankView/ByCurrencyView.
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

// Neutral gray ramp slots so multiple external banks each get a distinct
// shade without borrowing the `info` status family. Exported so every
// bank/currency distribution visual on this page (Overview's distribution
// bar included) draws from the same ramp instead of a copy that can drift.
export const HOME_BANK_COLOUR = 'bg-accent-500 dark:bg-accent-400';
export const EXTERNAL_BANK_RAMP = [
  'bg-primary-400 dark:bg-primary-500',
  'bg-primary-600 dark:bg-primary-300',
  'bg-neutral-400 dark:bg-neutral-500',
  'bg-primary-300 dark:bg-primary-600',
  'bg-neutral-500 dark:bg-neutral-400',
  'bg-primary-500 dark:bg-primary-400',
];

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

  let extIdx = 0;
  const segments = ordered.map((b) => {
    const pct = computedTotal > 0 ? Math.max(0, (b.amount / computedTotal) * 100) : 0;
    const colour = b.homeBank
      ? HOME_BANK_COLOUR
      : EXTERNAL_BANK_RAMP[extIdx++ % EXTERNAL_BANK_RAMP.length];
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
                ? 'text-accent-700 dark:text-accent-300 font-medium'
                : 'text-neutral-600 dark:text-neutral-300'
            )}>
              {s.bankName ?? s.bankBic}
            </span>
            <span className="amount text-neutral-500 dark:text-neutral-400">
              {formatCurrency(s.amount, s.currencyCode)} · {formatPct(s.pct)}
            </span>
          </span>
        ))}
      </div>
    </div>
  );
};
