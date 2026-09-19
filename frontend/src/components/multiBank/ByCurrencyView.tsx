import React, { useMemo } from 'react';
import { RefreshCw, Loader2, Banknote } from 'lucide-react';
import { formatCurrency } from '../../utils';
import { StatusIconBadge, DataTable } from '../ui';
import {
  MultiBankLiquiditySummary,
  ShadowSummary,
} from '../../services/api';
import { FreshnessPill } from './FreshnessPill';
import { BankSplitBar } from './BankSplitBar';
import { FilterChips } from './FilterChips';
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
      <FilterChips
        filter={filter}
        setFilter={setFilter}
        staleCount={summary.staleCount}
        failedCount={failedCount}
        neverCount={summary.neverRefreshedCount}
      />

      {currencies.length === 0 && (
        <div className="bg-surface-card rounded-lg border border-edge p-12 text-center text-neutral-500 dark:text-neutral-400">
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
            className="rounded-lg shadow-sm border border-edge bg-surface-card"
          >
            <div className="p-5 border-b border-edge-subtle flex items-center justify-between gap-3 flex-wrap">
              <div className="flex items-center gap-3 min-w-0">
                <StatusIconBadge tone="neutral" icon={Banknote} />
                <div>
                  <h2 className="section-title">{c.currencyCode}</h2>
                  <p className="caption mt-0.5">
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

            <div className="p-5">
              <DataTable
                hairline
                data={c.rows.flatMap((row) => row.shadows.map((s) => ({ row, s })))}
                keyExtractor={(r) => r.s.vaId}
                columns={[
                  { key: 'bank', header: 'Bank', minWidth: 200, mobileLabel: true, render: (_v, { row }) => (
                    <div className="text-neutral-700 dark:text-neutral-200">
                      <div className="flex items-center gap-2 flex-wrap">
                        <span className="text-primary-900 dark:text-neutral-50">{row.bankName ?? row.bankBic}</span>
                        {row.homeBank && (
                          <span className="text-caption font-bold uppercase tracking-wider px-2 py-0 leading-4 rounded-full bg-accent-100 text-accent-700 dark:bg-accent-500/20 dark:text-accent-300">
                            Home bank
                          </span>
                        )}
                      </div>
                      <p className="font-mono text-caption text-neutral-500 dark:text-neutral-400">{row.bankBic}</p>
                    </div>
                  ) },
                  { key: 'account', header: 'Account', minWidth: 160, dropOrder: 2, render: (_v, r) => r.s.bankAccountNumber ?? r.s.bankIban ?? '—' },
                  { key: 'effective', header: 'Effective', align: 'right', minWidth: 130, mobileValue: true, render: (_v, r) => (
                    <span className="amount text-primary-900 dark:text-neutral-50">{formatCurrency(r.s.bankBalanceEffective, r.s.currencyCode)}</span>
                  ) },
                  { key: 'committed', header: 'Committed', align: 'right', minWidth: 130, dropOrder: 1, render: (_v, r) => (
                    <span className="amount text-neutral-700 dark:text-neutral-200">{r.s.bankBalanceCommitted ? formatCurrency(r.s.bankBalanceCommitted, r.s.currencyCode) : '—'}</span>
                  ) },
                  { key: 'freshness', header: 'Freshness', minWidth: 130, dropOrder: 3, render: (_v, r) => <FreshnessPill shadow={r.s} /> },
                  { key: 'action', header: 'Action', align: 'right', minWidth: 100, render: (_v, r) => {
                    const s = r.s;
                    return (
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
                    );
                  } },
                ]}
              />
            </div>
          </div>
        ))}
      </div>
    </>
  );
};
