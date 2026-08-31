import React, { useMemo } from 'react';
import {
  Globe, Building2, RefreshCw, AlertTriangle,
  Clock, Loader2, Layers, Banknote,
} from 'lucide-react';
import { formatCurrency, cn } from '../../utils';
import {
  MultiBankLiquiditySummary,
  MultiBankBankBucket,
  ShadowSummary,
} from '../../services/api';
import { MetricCard } from './MetricCard';
import { FreshnessPill } from './FreshnessPill';

// ============================================================================
// Multi-Bank Liquidity — By Bank view (per-bank cards).
//
// Lifted from MultiBankLiquidityPage as part of the three-view Treasurer's
// Cockpit refactor (Commit 1 of multi-bank-redesign.md). View is presentation
// only — no network calls, no URL surgery; all state and side-effects stay on
// the page controller and flow in via props.
//
// Visual contract: must render byte-for-byte identically to the prior page
// body. The five-tile MetricCard strip, the sticky filter chip row, the
// per-bank cards with currency tables + freshness pills, and the
// "Effective by currency" footer all live here and only here.
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

  // Per-currency effective totals derived from filteredBanks so the footer
  // scope tracks the active filter. With "External" selected, USD shows only
  // external-bank USD effective — not portfolio-wide. (For shadow-level
  // filters, filteredBanks has already recomputed totalEffective from the
  // narrowed shadows.)
  const currencyTotals = useMemo(() => {
    const m = new Map<string, { bank: number; effective: number }>();
    for (const b of filteredBanks) {
      for (const c of b.currencies) {
        const cur = m.get(c.currencyCode) ?? { bank: 0, effective: 0 };
        cur.bank += c.totalBankBalance || 0;
        cur.effective += c.totalEffective || 0;
        m.set(c.currencyCode, cur);
      }
    }
    return Array.from(m.entries())
      .map(([code, t]) => ({ code, ...t }))
      .sort((a, b) => a.code.localeCompare(b.code));
  }, [filteredBanks]);

  // Empty-state copy. After a bulk refresh we auto-reset shadow-level
  // filters, but a user can also land here via a deep link (?filter=stale)
  // with zero matching shadows — so every branch has explicit copy.
  const noShadowsAtAll = summary.banks.length === 0;
  const emptyCopy = noShadowsAtAll
    ? 'No shadow accounts found. Create shadows from the Shadow Accounts page first.'
    : filter === 'home'     ? 'No home-bank shadows.'
    : filter === 'external' ? 'No external-bank shadows.'
    : filter === 'stale'    ? 'No stale shadows — all balances are fresh.'
    : filter === 'failed'   ? 'No failed refreshes.'
    : filter === 'never'    ? 'All shadows have been refreshed at least once.'
    : 'No shadows match the current filter.';

  return (
    <>
      {/* Top-line metrics, responsive. Filter-tiles set the chip; Total Shadows
          is a pure summary (no onClick) so the strip doesn't always have one
          tile lit and trained as the "selected" state. */}
      <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-5 gap-4">
        <MetricCard
          icon={<Layers className="w-5 h-5" />}
          tone="primary"
          label="Total Shadows"
          value={summary.totalShadows.toString()}
        />
        <MetricCard
          icon={<Building2 className="w-5 h-5" />}
          tone="success"
          label="Home-Bank Held"
          value={summary.homeBankShadows.toString()}
          sub="Currency-pool subject to rules"
          onClick={() => setFilter('home')}
          active={filter === 'home'}
        />
        <MetricCard
          icon={<Globe className="w-5 h-5" />}
          tone="accent"
          label="External"
          value={summary.externalShadows.toString()}
          sub="Sweep source only"
          onClick={() => setFilter('external')}
          active={filter === 'external'}
        />
        <MetricCard
          icon={<AlertTriangle className="w-5 h-5" />}
          tone={summary.staleCount > 0 ? 'warning' : 'neutral'}
          label="Stale"
          value={summary.staleCount.toString()}
          sub="Exceeds threshold"
          onClick={() => setFilter('stale')}
          active={filter === 'stale'}
        />
        <MetricCard
          icon={<Clock className="w-5 h-5" />}
          tone={summary.neverRefreshedCount > 0 ? 'danger' : 'neutral'}
          label="Never Refreshed"
          value={summary.neverRefreshedCount.toString()}
          sub="No fetch attempted"
          onClick={() => setFilter('never')}
          active={filter === 'never'}
        />
      </div>

      {/* Sticky filter chips. top-16 clears the 64px app-shell header (also
          sticky at top-0 z-30). Recipe mirrors the app-shell header bg so the
          two surfaces read as the same glass plane. z-20 sits below the
          header so the header always paints over chips when they collide. */}
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
              // aria-current is more accurate than aria-pressed for a
              // mutually-exclusive filter set: chips select one of N, they
              // aren't independent toggles.
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

      {/* Per-bank cards (filtered) */}
      <div className="space-y-4">
        {filteredBanks.length === 0 && (
          <div className="bg-white dark:bg-primary-900 rounded-lg border border-neutral-200 dark:border-primary-800 p-12 text-center text-neutral-500 dark:text-neutral-400">
            {emptyCopy}
          </div>
        )}
        {filteredBanks.map((bank) => {
          const shadowCountLabel = bank.shadowCount === bank.unfilteredShadowCount
            ? `${bank.shadowCount} shadow${bank.shadowCount !== 1 ? 's' : ''}`
            : `${bank.shadowCount} of ${bank.unfilteredShadowCount} shadows`;
          return (
          <div
            key={bank.bankBic}
            className={cn(
              'rounded-lg shadow-sm border border-neutral-200 dark:border-primary-800',
              'bg-white dark:bg-primary-900',
              // Home-bank emphasis: 2px gold/accent left rule. Pairs with the
              // HOME BANK pill below — those are the two semantic signals.
              // The ring, border-color shift, and medallion tone shift were
              // five-signal overkill; calmed to two.
              bank.homeBank && 'border-l-2 border-l-accent-500 dark:border-l-accent-400',
            )}
          >
            <div className="p-5 border-b border-neutral-100 dark:border-primary-800/60 flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-lg flex items-center justify-center bg-neutral-100 text-neutral-600 dark:bg-primary-800/60 dark:text-neutral-300">
                  <Building2 className="w-5 h-5" />
                </div>
                <div>
                  <div className="flex items-center gap-2">
                    <h2 className="section-title">
                      {bank.bankName ?? bank.bankBic}
                    </h2>
                    {bank.homeBank && (
                      <span className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-full bg-accent-100 text-accent-700 dark:bg-accent-500/20 dark:text-accent-300">
                        Home bank
                      </span>
                    )}
                  </div>
                  <p className="text-xs text-neutral-500 dark:text-neutral-400 mt-0.5 font-mono">{bank.bankBic}</p>
                </div>
              </div>
              <div className="text-sm text-neutral-600 dark:text-neutral-300">{shadowCountLabel}</div>
            </div>

            <div className="divide-y divide-neutral-100 dark:divide-primary-800/60">
              {bank.currencies.map((ccy) => (
                <div key={ccy.currencyCode} className="p-5">
                  <div className="flex items-center justify-between mb-3">
                    <div className="flex items-center gap-2">
                      <Banknote className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
                      <span className="font-semibold text-primary-900 dark:text-neutral-50">{ccy.currencyCode}</span>
                      <span className="text-xs text-neutral-500 dark:text-neutral-400">({ccy.shadowCount} mirror{ccy.shadowCount !== 1 ? 's' : ''})</span>
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
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="text-left">
                        <th className="label py-2">Shadow</th>
                        <th className="label py-2">Freshness</th>
                        <th className="label py-2">Entity</th>
                        <th className="label py-2">Bank Account</th>
                        <th className="label py-2 text-right">Bank Balance</th>
                        <th className="label py-2 text-right">Committed</th>
                        <th className="label py-2 text-right">Effective</th>
                        <th className="label py-2 text-right">Action</th>
                      </tr>
                    </thead>
                    <tbody>
                      {ccy.shadows.map((s) => (
                        // Row text defaults to neutral-700 so non-dominant mono
                        // cells (Bank Balance, Committed) sit at mid emphasis.
                        // The vaNumber (identifier) and Effective (dominant
                        // column) override back to primary-900 — hierarchy via
                        // tone, not weight.
                        <tr key={s.vaId} className="border-t border-neutral-100 dark:border-primary-800/60 hover:bg-neutral-50 dark:hover:bg-primary-800/40 text-neutral-700 dark:text-neutral-200">
                          <td className="py-2.5 font-mono text-xs text-primary-900 dark:text-neutral-50">{s.vaNumber}</td>
                          <td className="py-2.5">
                            <FreshnessPill shadow={s} />
                          </td>
                          <td className="py-2.5 text-neutral-600 dark:text-neutral-300">{s.owningEntityCode ?? '—'}</td>
                          <td className="py-2.5">{s.bankAccountNumber ?? s.bankIban ?? '—'}</td>
                          <td className="py-2.5 amount text-right">{formatCurrency(s.bankBalance, s.currencyCode)}</td>
                          {/* Committed is a neutral accounting value, not a caution. */}
                          <td className="py-2.5 amount text-right">
                            {s.bankBalanceCommitted ? formatCurrency(s.bankBalanceCommitted, s.currencyCode) : '—'}
                          </td>
                          <td className="py-2.5 amount text-right text-primary-900 dark:text-neutral-50">{formatCurrency(s.bankBalanceEffective, s.currencyCode)}</td>
                          <td className="py-2.5 text-right">
                            <button
                              onClick={() => refresh(s)}
                              disabled={refreshingIds.has(s.vaId)}
                              aria-label={`Refresh ${s.vaNumber}`}
                              // p-2 -m-2 expands the tap target to ~40px without
                              // changing the visual footprint — page is in the
                              // mobile bottom-nav.
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
                      ))}
                    </tbody>
                  </table>
                </div>
              ))}
            </div>
          </div>
        );})}
      </div>

      {/* Per-currency effective totals — section, not stray pill row. */}
      {currencyTotals.length > 0 && (
        <div className="border-t border-neutral-200 dark:border-primary-800 pt-4">
          <p className="label mb-2">Effective by currency</p>
          <div className="flex flex-wrap gap-2">
            {currencyTotals.map(({ code, effective }) => (
              <span
                key={code}
                className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-neutral-100 dark:bg-primary-800/60 text-xs"
              >
                <span className="font-semibold text-neutral-700 dark:text-neutral-200">{code}</span>
                <span className="amount text-neutral-900 dark:text-neutral-50">{formatCurrency(effective, code)}</span>
              </span>
            ))}
          </div>
        </div>
      )}
    </>
  );
};
