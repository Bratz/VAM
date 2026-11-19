import React, { useMemo } from 'react';
import {
  Building2, RefreshCw, Loader2, Banknote,
} from 'lucide-react';
import { formatCurrency, cn } from '../../utils';
import {
  MultiBankLiquiditySummary,
  MultiBankBankBucket,
  ShadowSummary,
} from '../../services/api';
import { StatusIconBadge, DataTable } from '../ui';
import { FreshnessPill } from './FreshnessPill';
import { FilterChips } from './FilterChips';

// ============================================================================
// Multi-Bank Liquidity — By Bank view (per-bank cards).
//
// Lifted from MultiBankLiquidityPage as part of the three-view Treasurer's
// Cockpit refactor (Commit 1 of multi-bank-redesign.md). View is presentation
// only — no network calls, no URL surgery; all state and side-effects stay on
// the page controller and flow in via props.
//
// The five-tile MetricCard strip this view used to lead with was dropped —
// it duplicated Overview's own StatStrip (Total Shadows/Stale/Failed/Never
// Refreshed) and its Home-Bank-Held/External tiles did nothing FilterChips'
// own Home-bank/External chips didn't already do. FilterChips (with live
// counts) is this view's sole filter surface now.
// ============================================================================

const VALID_FILTERS = ['all', 'home', 'external', 'stale', 'failed', 'never'] as const;
export type FilterKey = typeof VALID_FILTERS[number];

// Per-render derived bank. `unfilteredShadowCount` is the bank's original
// shadowCount before any shadow-level filter narrowed the rows; the card
// header reads it to render "1 of 5 shadows" when a filter is active. It is
// a public field of the rendered shape (not an internal underscore'd one)
// because the JSX below consumes it directly.
type FilteredBank = MultiBankBankBucket & { unfilteredShadowCount: number };

export interface ByBankViewProps {
  summary: MultiBankLiquiditySummary;
  filter: FilterKey;
  setFilter: (f: FilterKey) => void;
  refreshingIds: Set<string>;
  refresh: (shadow: ShadowSummary) => void;
  failedCount: number;
}

export const ByBankView: React.FC<ByBankViewProps> = ({
  summary,
  filter,
  setFilter,
  refreshingIds,
  refresh,
  failedCount,
}) => {
  // Filter banks by the active chip. Bank-level chips (home / external) keep
  // all shadows. Shadow-level chips (stale / failed / never) narrow shadows
  // and recompute per-currency totals + shadowCount so the headers don't lie
  // about what's visible.
  const filteredBanks: FilteredBank[] = useMemo(() => {
    const decorate = (b: MultiBankBankBucket): FilteredBank => ({
      ...b,
      unfilteredShadowCount: b.shadowCount,
    });
    if (filter === 'all')      return summary.banks.map(decorate);
    if (filter === 'home')     return summary.banks.filter(b => b.homeBank).map(decorate);
    if (filter === 'external') return summary.banks.filter(b => !b.homeBank).map(decorate);

    const matches = (s: ShadowSummary) => {
      if (filter === 'stale')  return s.stale;
      if (filter === 'failed') return s.lastBalanceRefreshStatus === 'FAILED';
      if (filter === 'never')  return s.lastBalanceRefreshStatus === 'NEVER';
      return true;
    };
    return summary.banks
      .map(b => {
        const currencies = b.currencies
          .map(c => {
            const shadows = c.shadows.filter(matches);
            return {
              ...c,
              shadows,
              shadowCount: shadows.length,
              totalBankBalance: shadows.reduce((a, s) => a + (s.bankBalance || 0), 0),
              totalCommitted: shadows.reduce((a, s) => a + (s.bankBalanceCommitted || 0), 0),
              totalEffective: shadows.reduce((a, s) => a + (s.bankBalanceEffective || 0), 0),
            };
          })
          .filter(c => c.shadows.length > 0);
        const shadowCount = currencies.reduce((a, c) => a + c.shadowCount, 0);
        return { ...b, currencies, shadowCount, unfilteredShadowCount: b.shadowCount };
      })
      .filter(b => b.currencies.length > 0);
  }, [summary, filter]);

  // Empty-state copy. After a bulk refresh we auto-reset shadow-level
  // filters, but a user can also land here via a deep link (?filter=stale)
  // with zero matching shadows — so every branch has explicit copy.
  const noShadowsAtAll = summary.banks.length === 0;
  const emptyCopy = noShadowsAtAll
    ? 'No bank accounts found. Add them on the Bank Accounts page first.'
    : filter === 'home'     ? 'No home-bank accounts.'
    : filter === 'external' ? 'No external-bank accounts.'
    : filter === 'stale'    ? 'No stale balances — all are fresh.'
    : filter === 'failed'   ? 'No failed refreshes.'
    : filter === 'never'    ? 'All balances have been refreshed at least once.'
    : 'No accounts match the current filter.';

  return (
    <>
      {/* Sticky filter chips. top-16 clears the 64px app-shell header (also
          sticky at top-0 z-30). Recipe mirrors the app-shell header bg so the
          two surfaces read as the same glass plane. z-20 sits below the
          header so the header always paints over chips when they collide. */}
      <FilterChips
        filter={filter}
        setFilter={setFilter}
        staleCount={summary.staleCount}
        failedCount={failedCount}
        neverCount={summary.neverRefreshedCount}
      />

      {/* Per-bank cards (filtered) */}
      <div className="space-y-4">
        {filteredBanks.length === 0 && (
          <div className="bg-surface-card rounded-lg border border-edge p-12 text-center text-neutral-500 dark:text-neutral-400">
            {emptyCopy}
          </div>
        )}
        {filteredBanks.map((bank) => {
          const shadowCountLabel = bank.shadowCount === bank.unfilteredShadowCount
            ? `${bank.shadowCount} account${bank.shadowCount !== 1 ? 's' : ''}`
            : `${bank.shadowCount} of ${bank.unfilteredShadowCount} accounts`;
          return (
          <div
            key={bank.bankBic}
            className={cn(
              'rounded-lg shadow-sm border border-edge',
              'bg-surface-card',
              // Home-bank emphasis: 2px gold/accent left rule. Pairs with the
              // HOME BANK pill below — those are the two semantic signals.
              // The ring, border-color shift, and medallion tone shift were
              // five-signal overkill; calmed to two.
              bank.homeBank && 'border-l-2 border-l-accent-500 dark:border-l-accent-400',
            )}
          >
            <div className="p-5 border-b border-edge-subtle flex items-center justify-between">
              <div className="flex items-center gap-3">
                <StatusIconBadge tone="neutral" icon={Building2} />
                <div>
                  <div className="flex items-center gap-2">
                    <h2 className="section-title">
                      {bank.bankName ?? bank.bankBic}
                    </h2>
                    {bank.homeBank && (
                      <span className="text-caption font-bold uppercase tracking-wider px-2 py-0 leading-4 rounded-full bg-accent-100 text-accent-700 dark:bg-accent-500/20 dark:text-accent-300">
                        Home bank
                      </span>
                    )}
                  </div>
                  <p className="text-caption text-neutral-500 dark:text-neutral-400 mt-0.5 font-mono">{bank.bankBic}</p>
                </div>
              </div>
              <div className="body-sm">{shadowCountLabel}</div>
            </div>

            <div className="divide-y divide-edge-subtle">
              {bank.currencies.map((ccy) => (
                <div key={ccy.currencyCode} className="p-5">
                  <div className="flex items-center justify-between mb-3">
                    <div className="flex items-center gap-2">
                      <Banknote className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
                      {/* Currency code as an identifier label (not a section
                          heading, which is what .section-title's Fraunces
                          treatment is reserved for elsewhere on this tab) —
                          font-mono matches how the same code renders in
                          the By Entity/By
                          Country tables, instead of a plain sans weight. */}
                      <span className="font-mono text-body-sm font-medium text-primary-900 dark:text-neutral-50">{ccy.currencyCode}</span>
                      <span className="caption">({ccy.shadowCount} account{ccy.shadowCount !== 1 ? 's' : ''})</span>
                    </div>
                    <div className="text-right">
                      {/* Disambiguate from the table's per-row Effective column.
                          Totals are recomputed from currently-visible shadows
                          so the subtotal never disagrees with the rows. */}
                      <p className="label">Subtotal (Effective)</p>
                      {/* Subtotal is a stat, not an identifier — use the
                          Phase 9 display tier (Fraunces 20px / 600 tabular-nums)
                          via .stat-value-xs instead of font-mono font-medium.
                          This removes the visual clash with the per-row
                          Effective cell (also mono). */}
                      <p className="stat-value-xs">
                        {formatCurrency(ccy.totalEffective, ccy.currencyCode)}
                      </p>
                    </div>
                  </div>
                  <DataTable
                    hairline
                    data={ccy.shadows}
                    keyExtractor={(s) => s.vaId}
                    columns={[
                      { key: 'shadow', header: 'Account', minWidth: 130, mobileLabel: true, render: (_v, s) => (
                        <span className="font-mono text-caption text-primary-900 dark:text-neutral-50">{s.vaNumber}</span>
                      ) },
                      { key: 'freshness', header: 'Freshness', minWidth: 130, dropOrder: 3, render: (_v, s) => <FreshnessPill shadow={s} /> },
                      { key: 'entity', header: 'Entity', minWidth: 100, dropOrder: 1, render: (_v, s) => (
                        <span className="text-neutral-600 dark:text-neutral-300">{s.owningEntityCode ?? '—'}</span>
                      ) },
                      { key: 'account', header: 'Bank Account', minWidth: 150, dropOrder: 2, render: (_v, s) => s.bankAccountNumber ?? s.bankIban ?? '—' },
                      { key: 'bankBalance', header: 'Bank Balance', align: 'right', minWidth: 130, render: (_v, s) => (
                        <span className="amount text-neutral-700 dark:text-neutral-200">{formatCurrency(s.bankBalance, s.currencyCode)}</span>
                      ) },
                      { key: 'committed', header: 'Committed', align: 'right', minWidth: 120, dropOrder: 4, render: (_v, s) => (
                        <span className="amount text-neutral-700 dark:text-neutral-200">{s.bankBalanceCommitted ? formatCurrency(s.bankBalanceCommitted, s.currencyCode) : '—'}</span>
                      ) },
                      { key: 'effective', header: 'Effective', align: 'right', minWidth: 130, mobileValue: true, render: (_v, s) => (
                        <span className="amount text-primary-900 dark:text-neutral-50">{formatCurrency(s.bankBalanceEffective, s.currencyCode)}</span>
                      ) },
                      { key: 'action', header: 'Action', align: 'right', minWidth: 100, render: (_v, s) => (
                        <button
                onClick={() => refresh(s)}
                disabled={refreshingIds.has(s.vaId)}
                aria-label={`Refresh ${s.vaNumber}`}
                className="text-primary-600 hover:text-primary-700 dark:text-accent-400 dark:hover:text-accent-300 text-caption inline-flex items-center gap-1 ml-auto disabled:opacity-50 p-2 -m-2 rounded-md focus:outline-none focus-visible:ring-2 focus-visible:ring-primary-400 dark:focus-visible:ring-accent-400"
              >
                {refreshingIds.has(s.vaId)
                  ? <Loader2 className="w-3 h-3 animate-spin" />
                  : <RefreshCw className="w-3 h-3" />
                }
                Refresh
              </button>
                      ) },
                    ]}
                  />
                </div>
              ))}
            </div>
          </div>
        );})}
      </div>
    </>
  );
};
