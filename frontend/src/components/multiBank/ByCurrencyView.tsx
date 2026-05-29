import React, { useMemo } from 'react';
import { RefreshCw, Loader2, Banknote } from 'lucide-react';
import { cn, formatCurrency } from '../../utils';
import {
  MultiBankLiquiditySummary,
  ShadowSummary,
} from '../../services/api';
import { FreshnessPill } from './FreshnessPill';
import { BankSplitBar } from './BankSplitBar';
import { FilterKey } from './types';

// ============================================================================
// Multi-Bank Liquidity — By Currency view.
//
// Pivot of the same data, currency-first instead of bank-first. The header
// of each Card answers "how much of this currency do we hold?" — a question
// the by-bank arrangement leaves the eye to assemble from per-bank totals.
//
// Renders one Card per currency with a table of banks holding it
// (Bank · Account · Effective · Committed · Freshness · Action). Filter
// semantics match By Bank: bank-level chips trim banks, shadow-level chips
// trim shadows + recompute per-bank rolls so headers don't lie about what
// is visible.
// ============================================================================

interface ByCurrencyViewProps {
  summary: MultiBankLiquiditySummary;
  filter: FilterKey;
  setFilter: (f: FilterKey) => void;
  refreshingIds: Set<string>;
  refresh: (shadow: ShadowSummary) => void;
  failedCount: number;
}

// Row shape rendered in the per-currency table.
type CurrencyBankRow = {
  bankBic: string;
  bankName?: string;
  homeBank: boolean;
  shadowCount: number;
  totalEffective: number;
  totalCommitted: number;
  shadows: ShadowSummary[];
};

// Top-level aggregation: one entry per currency code, sorted alphabetically,
// containing the rows for each bank that holds it. Filter is applied before
// aggregation so subtotals always agree with the rows the user sees.
function buildCurrencyView(summary: MultiBankLiquiditySummary, filter: FilterKey) {
  const matchesShadow = (s: ShadowSummary) => {
    if (filter === 'stale')  return s.stale;
    if (filter === 'failed') return s.lastBalanceRefreshStatus === 'FAILED';
    if (filter === 'never')  return s.lastBalanceRefreshStatus === 'NEVER';
    return true;
  };
  const matchesBank = (homeBank: boolean) => {
    if (filter === 'home')     return homeBank;
    if (filter === 'external') return !homeBank;
    return true;
  };

  const map = new Map<string, { totalEffective: number; rows: CurrencyBankRow[] }>();

  for (const b of summary.banks) {
    if (!matchesBank(b.homeBank)) continue;
    for (const c of b.currencies) {
      const shadows = c.shadows.filter(matchesShadow);
      if (shadows.length === 0) continue;
      const row: CurrencyBankRow = {
        bankBic: b.bankBic,
        bankName: b.bankName,
        homeBank: b.homeBank,
        shadowCount: shadows.length,
        totalEffective: shadows.reduce((a, s) => a + (s.bankBalanceEffective || 0), 0),
        totalCommitted: shadows.reduce((a, s) => a + (s.bankBalanceCommitted || 0), 0),
        shadows,
      };
      const entry = map.get(c.currencyCode) ?? { totalEffective: 0, rows: [] };
      entry.totalEffective += row.totalEffective;
      entry.rows.push(row);
      map.set(c.currencyCode, entry);
    }
  }

  return Array.from(map.entries())
    .map(([currencyCode, e]) => ({
      currencyCode,
      totalEffective: e.totalEffective,
      // Home bank first, then externals by descending effective.
      rows: e.rows.sort((a, b) => {
        if (a.homeBank && !b.homeBank) return -1;
        if (!a.homeBank && b.homeBank) return 1;
        return (b.totalEffective || 0) - (a.totalEffective || 0);
      }),
    }))
    .sort((a, b) => a.currencyCode.localeCompare(b.currencyCode));
}

export const ByCurrencyView: React.FC<ByCurrencyViewProps> = ({
  summary,
  filter,
  setFilter,
  refreshingIds,
  refresh,
  failedCount,
}) => {
  const currencies = useMemo(() => buildCurrencyView(summary, filter), [summary, filter]);

  return (
    <>
      {/* Filter chips at the top — same semantics as By Bank's chip row.
          Phase 11 morning-glance: the chip row is now sticky (top-16, below
          the app-shell header) so filter context survives scrolling past
          8+ currency cards. (Supersedes the earlier "stays static" note —
          that rationale no longer holds.) Background matches the header
          glass recipe so the two surfaces read as one plane. */}
      <div className="sticky top-16 z-20 py-2 -mt-2 bg-white/80 backdrop-blur-xl dark:bg-primary-900/80 supports-[backdrop-filter]:bg-white/60 dark:supports-[backdrop-filter]:bg-primary-900/60">
        <div className="flex flex-wrap gap-2">
        {([
          { key: 'all',      label: 'All' },
          { key: 'home',     label: 'Home-bank' },
          { key: 'external', label: 'External' },
          { key: 'stale',    label: 'Stale',           count: summary.staleCount },
          { key: 'failed',   label: 'Failed',          count: failedCount },
          { key: 'never',    label: 'Never refreshed', count: summary.neverRefreshedCount },
        ] as { key: FilterKey; label: string; count?: number }[]).map(({ key, label, count }) => (
          <button
            key={key}
            type="button"
            onClick={() => setFilter(key)}
            aria-current={filter === key ? 'true' : undefined}
            className={cn(
              'px-3 py-1 rounded-full text-xs font-medium transition-colors border focus:outline-none focus-visible:ring-2 focus-visible:ring-primary-400 dark:focus-visible:ring-accent-400',
              filter === key
                ? 'bg-primary-900 text-white border-primary-900 dark:bg-accent-500 dark:text-primary-950 dark:border-accent-500'
                : 'bg-white text-neutral-700 border-neutral-200 hover:bg-neutral-100 dark:bg-primary-900 dark:text-neutral-300 dark:border-primary-800 dark:hover:bg-primary-800',
            )}
          >
            {label}{typeof count === 'number' ? ` · ${count}` : ''}
          </button>
        ))}
        </div>
      </div>

      {currencies.length === 0 && (
        <div className="bg-white dark:bg-primary-900 rounded-lg border border-neutral-200 dark:border-primary-800 p-12 text-center text-neutral-500 dark:text-neutral-400">
          {filter === 'home'     ? 'No home-bank shadows.'
            : filter === 'external' ? 'No external-bank shadows.'
            : filter === 'stale'    ? 'No stale shadows — all balances are fresh.'
            : filter === 'failed'   ? 'No failed refreshes.'
            : filter === 'never'    ? 'All shadows have been refreshed at least once.'
            : 'No shadows match the current filter.'}
        </div>
      )}

      <div className="space-y-4">
        {currencies.map((c) => (
          <div
            key={c.currencyCode}
            className="rounded-lg shadow-sm border border-neutral-200 dark:border-primary-800 bg-white dark:bg-primary-900"
          >
            <div className="p-5 border-b border-neutral-100 dark:border-primary-800/60 flex items-center justify-between gap-3 flex-wrap">
              <div className="flex items-center gap-3 min-w-0">
                <div className="w-10 h-10 rounded-lg flex items-center justify-center bg-neutral-100 text-neutral-600 dark:bg-primary-800/60 dark:text-neutral-300">
                  <Banknote className="w-5 h-5" />
                </div>
                <div>
                  <h2 className="section-title">{c.currencyCode}</h2>
                  <p className="text-xs text-neutral-500 dark:text-neutral-400 mt-0.5">
                    {c.rows.length} bank{c.rows.length === 1 ? '' : 's'} · {c.rows.reduce((a, r) => a + r.shadowCount, 0)} shadow{c.rows.reduce((a, r) => a + r.shadowCount, 0) === 1 ? '' : 's'}
                  </p>
                </div>
              </div>
              <p className="stat-value-xs">{formatCurrency(c.totalEffective, c.currencyCode)}</p>
            </div>

            {/* Bank-split bar — visual answer to "where is this currency
                held". Within a single currency, summing bank effective
                balances is FX-honest. Same primitive Overview uses; visual
                continuity across views. */}
            <div className="px-5 pt-4">
              <BankSplitBar
                bankShares={c.rows.map((r) => ({
                  bankBic: r.bankBic,
                  bankName: r.bankName,
                  homeBank: r.homeBank,
                  amount: r.totalEffective,
                  currencyCode: c.currencyCode,
                }))}
                total={c.totalEffective}
              />
            </div>

            <div className="p-5 overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left">
                    <th className="label py-2">Bank</th>
                    <th className="label py-2">Account</th>
                    <th className="label py-2 text-right">Effective</th>
                    <th className="label py-2 text-right">Committed</th>
                    <th className="label py-2">Freshness</th>
                    <th className="label py-2 text-right">Action</th>
                  </tr>
                </thead>
                <tbody>
                  {c.rows.flatMap((row) =>
                    row.shadows.map((s) => (
                      <tr
                        key={s.vaId}
                        className="border-t border-neutral-100 dark:border-primary-800/60 hover:bg-neutral-50 dark:hover:bg-primary-800/40 text-neutral-700 dark:text-neutral-200"
                      >
                        <td className="py-2.5">
                          <div className="flex items-center gap-2 flex-wrap">
                            <span className="text-primary-900 dark:text-neutral-50">{row.bankName ?? row.bankBic}</span>
                            {row.homeBank && (
                              <span className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-full bg-accent-100 text-accent-700 dark:bg-accent-500/20 dark:text-accent-300">
                                Home bank
                              </span>
                            )}
                          </div>
                          <p className="font-mono text-[11px] text-neutral-500 dark:text-neutral-400">{row.bankBic}</p>
                        </td>
                        <td className="py-2.5">{s.bankAccountNumber ?? s.bankIban ?? '—'}</td>
                        <td className="py-2.5 font-mono text-right text-primary-900 dark:text-neutral-50">{formatCurrency(s.bankBalanceEffective, s.currencyCode)}</td>
                        <td className="py-2.5 font-mono text-right">
                          {s.bankBalanceCommitted ? formatCurrency(s.bankBalanceCommitted, s.currencyCode) : '—'}
                        </td>
                        <td className="py-2.5">
                          <FreshnessPill shadow={s} />
                        </td>
                        <td className="py-2.5 text-right">
                          <button
                            onClick={() => refresh(s)}
                            disabled={refreshingIds.has(s.vaId)}
                            aria-label={`Refresh ${s.vaNumber}`}
                            className="text-primary-600 hover:text-primary-700 dark:text-accent-400 dark:hover:text-accent-300 text-xs inline-flex items-center gap-1 ml-auto disabled:opacity-50 p-2 -m-2 rounded focus:outline-none focus-visible:ring-2 focus-visible:ring-primary-400 dark:focus-visible:ring-accent-400"
                          >
                            {refreshingIds.has(s.vaId)
                              ? <Loader2 className="w-3 h-3 animate-spin" />
                              : <RefreshCw className="w-3 h-3" />
                            }
                            Refresh
                          </button>
                        </td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>
          </div>
        ))}
      </div>
    </>
  );
};
