import React, { useEffect, useMemo, useState } from 'react';
import {
  RefreshCw, AlertTriangle, Clock, Layers, Info,
} from 'lucide-react';
import { cn, formatCurrency } from '../../utils';
import {
  MultiBankLiquiditySummary,
  MultiBankBankBucket,
  fxRateApi,
} from '../../services/api';
import { Card, StatusIconBadge, Button, Badge } from '../ui';
import { CurrencyPicker } from '../ui/CurrencyPicker';
import { useMarket } from '../../context/MarketContext';
import { StatStrip } from '../layout/StatStrip';
import { MetricCard } from './MetricCard';
import { formatPct } from './format';
import { BankSplitBar, BankShare, HOME_BANK_COLOUR, EXTERNAL_BANK_RAMP } from './BankSplitBar';
import { FilterKey, ViewKey } from './types';

// ============================================================================
// Multi-Bank Liquidity — Overview (default landing view).
//
// Morning-glance read on portfolio-wide liquidity health, anchored by the
// FX-honesty rule: never present a single cross-currency total (the payload
// carries no FX rates). Where a dominant figure is needed, use a count or
// per-currency chip rows; where proportion is shown, only aggregate over
// shadow count (currency-agnostic) or within a single currency.
//
// Composition (top to bottom, expanded mode):
//   1. Per-currency liquidity hero (balance by currency; shadow counts
//      demoted to a footer strip — Phase 11 morning-glance reframe)
//   2. Shadow distribution bar — count-based, cross-bank
//   3. Operational StatStrip — 4 filter tiles (total, stale, failed, never)
//   4. Conditional freshness banner — refresh-all CTA when work exists
//   5. Per-currency cards — bank split bar per currency
//   6. Filter chip row — selecting stale/failed/never bounces to By Bank
//
// `compact` mode (consumed by the cockpit's Multi-Bank Band):
//   - Hero swapped for an inline strip (numbers, no large display text).
//   - Op Strip tiles render read-only (no onClick / not filter toggles).
//   - Distribution bar drops its Card wrapper (parent already provides one).
//   - Freshness banner omitted (the cockpit's attention inbox covers it).
//   - Per-currency cards capped at top-4 by total effective; lighter chrome.
//   - Filter chip row omitted.
// ============================================================================

interface OverviewViewProps {
  summary: MultiBankLiquiditySummary;
  filter: FilterKey;
  setFilter: (f: FilterKey) => void;
  /** Optional in `compact` mode (the band has no view-switching surface). */
  setView?: (v: ViewKey) => void;
  failedCount: number;
  /** Optional in `compact` mode (freshness banner is suppressed). */
  onBulkRefreshStale?: () => void;
  /** Optional in `compact` mode. */
  bulkRefreshing?: boolean;
  /**
   * When true, render a slimmer composition suitable for embedding inside
   * another `<Card>` wrapper (the cockpit's Multi-Bank Band). Defaults to
   * false (the standalone /treasury/multi-bank page).
   */
  compact?: boolean;
}

export const OverviewView: React.FC<OverviewViewProps> = ({
  summary,
  filter,
  setFilter,
  setView,
  failedCount,
  onBulkRefreshStale,
  bulkRefreshing = false,
  compact = false,
}) => {
  // Apply bank-level filter (home / external) to the slice we render per-
  // currency cards from. Shadow-level filters (stale / failed / never) bounce
  // the user to By Bank where the rows live; the Overview keeps showing the
  // full picture in that case.
  const visibleBanks: MultiBankBankBucket[] = useMemo(() => {
    if (filter === 'home')     return summary.banks.filter(b => b.homeBank);
    if (filter === 'external') return summary.banks.filter(b => !b.homeBank);
    return summary.banks;
  }, [summary, filter]);

  // Per-currency aggregation across visibleBanks. Within a single currency
  // we can sum amounts directly — FX-honest.
  const currencies = useMemo(() => {
    type CurAgg = {
      currencyCode: string;
      totalEffective: number;
      totalAvailable: number;
      shadowCount: number;
      bankShares: BankShare[];
      homeBankShare: number;
      homeBankAmount: number;
    };
    const map = new Map<string, CurAgg>();
    for (const b of visibleBanks) {
      for (const c of b.currencies) {
        const cur = map.get(c.currencyCode) ?? {
          currencyCode: c.currencyCode,
          totalEffective: 0,
          totalAvailable: 0,
          shadowCount: 0,
          bankShares: [],
          homeBankShare: 0,
          homeBankAmount: 0,
        };
        cur.totalEffective += c.totalEffective || 0;
        // bankAvailableBalance lives per-shadow, not on the currency bucket
        // itself (unlike totalEffective/totalCommitted, which the backend
        // pre-aggregates) — sum it here for the Available-vs-trapped tiles.
        cur.totalAvailable += c.shadows.reduce((a, s) => a + (s.bankAvailableBalance || 0), 0);
        cur.shadowCount += c.shadowCount || 0;
        cur.bankShares.push({
          bankBic: b.bankBic,
          bankName: b.bankName,
          homeBank: b.homeBank,
          amount: c.totalEffective || 0,
          currencyCode: c.currencyCode,
        });
        if (b.homeBank) cur.homeBankAmount += c.totalEffective || 0;
        map.set(c.currencyCode, cur);
      }
    }
    return Array.from(map.values())
      .map((cur) => ({
        ...cur,
        homeBankShare: cur.totalEffective > 0 ? cur.homeBankAmount / cur.totalEffective : 0,
      }))
      .sort((a, b) => a.currencyCode.localeCompare(b.currencyCode));
  }, [visibleBanks]);

  // Consolidated cross-currency total — the one deliberate exception to the
  // FX-honesty rule above. Unlike the per-currency figures (which sum
  // directly off the payload), this converts each currency's total via the
  // backend's fxRateApi.convert() (full rate-resolution fallback chain:
  // direct → inverse → multi-hop → default table) into a user-picked
  // reporting currency, so it's always labelled as an indicative conversion,
  // never presented as a second source of truth for the per-currency totals.
  const { profile } = useMarket();
  const [reportingCurrency, setReportingCurrency] = useState(profile.defaultCurrency || 'AED');
  const [consolidated, setConsolidated] = useState<{
    total: number; available: number; loading: boolean; excluded: number;
  }>({ total: 0, available: 0, loading: false, excluded: 0 });

  useEffect(() => {
    if (compact || currencies.length === 0) return;
    let alive = true;
    setConsolidated((prev) => ({ ...prev, loading: true }));
    Promise.allSettled(
      currencies.map((c) =>
        c.currencyCode === reportingCurrency
          ? Promise.resolve({ currencyCode: c.currencyCode, total: c.totalEffective, available: c.totalAvailable })
          : Promise.all([
              fxRateApi.convert(c.totalEffective, c.currencyCode, reportingCurrency),
              fxRateApi.convert(c.totalAvailable, c.currencyCode, reportingCurrency),
            ]).then(([totalRes, availRes]) => {
              if (!totalRes.success || !availRes.success || !totalRes.data || !availRes.data) {
                throw new Error(`No rate ${c.currencyCode} → ${reportingCurrency}`);
              }
              return {
                currencyCode: c.currencyCode,
                total: totalRes.data.convertedAmount,
                available: availRes.data.convertedAmount,
              };
            })
      )
    ).then((results) => {
      if (!alive) return;
      let total = 0;
      let available = 0;
      let excluded = 0;
      for (const r of results) {
        if (r.status === 'fulfilled') {
          total += r.value.total;
          available += r.value.available;
        } else {
          excluded++;
        }
      }
      setConsolidated({ total, available, loading: false, excluded });
    });
    return () => { alive = false; };
  }, [currencies, reportingCurrency, compact]);

  // Currency chip row for the hero `sub` slot.
  const currencyChipRow = currencies.length > 0 ? (
    <div className="flex flex-wrap gap-1.5">
      {currencies.map((c) => (
        <span
          key={c.currencyCode}
          className="amount text-xs px-1.5 py-0.5 rounded bg-neutral-100 dark:bg-primary-800/60 text-neutral-700 dark:text-neutral-200"
        >
          {c.currencyCode} {formatCurrency(c.totalEffective, c.currencyCode)}
        </span>
      ))}
    </div>
  ) : null;

  // Shadow-count distribution across banks (currency-agnostic — FX-honest).
  const totalShadows = summary.totalShadows;
  const orderedBanks = useMemo(() => {
    const banks = [...summary.banks];
    return banks.sort((a, b) => {
      if (a.homeBank && !b.homeBank) return -1;
      if (!a.homeBank && b.homeBank) return 1;
      return (b.shadowCount || 0) - (a.shadowCount || 0);
    });
  }, [summary.banks]);

  const externals = orderedBanks.filter((b) => !b.homeBank);
  const topExternalBank = externals[0];
  const topExternalPct = topExternalBank && totalShadows > 0
    ? Math.round((topExternalBank.shadowCount / totalShadows) * 100)
    : 0;

  let extIdx = 0;
  const distributionSegments = orderedBanks.map((b) => ({
    bank: b,
    pct: totalShadows > 0 ? (b.shadowCount / totalShadows) * 100 : 0,
    colour: b.homeBank ? HOME_BANK_COLOUR : EXTERNAL_BANK_RAMP[extIdx++ % EXTERNAL_BANK_RAMP.length],
  }));

  const hasWork = summary.staleCount + summary.neverRefreshedCount + failedCount > 0;

  // In compact mode the per-currency rail is capped at the top-4 by total
  // effective so the band stays a glance-surface. The standalone page passes
  // the full set.
  const visibleCurrencies = compact
    ? [...currencies].sort((a, b) => (b.totalEffective || 0) - (a.totalEffective || 0)).slice(0, 4)
    : currencies;

  // Distribution panel — outer container differs in compact mode (no Card to
  // avoid a card-in-card visual).
  const DistributionWrap: React.FC<{ children: React.ReactNode }> = ({ children }) =>
    compact
      ? <div className="rounded-lg border border-neutral-200 dark:border-primary-800 p-4">{children}</div>
      : <Card padding="sm">{children}</Card>;

  return (
    <>
      {/* 1. Hero — per-currency liquidity strip in expanded mode; an inline
          count strip in compact mode (cockpit Band; same numbers, no large
          display text, no gold lens). Phase 11 morning-glance reframe: the
          expanded hero leads with balance-by-currency (the question a
          treasurer opens this page to answer) rather than shadow counts,
          which demote to the footer strip. */}
      {compact ? (
        <div className="flex flex-wrap items-end justify-between gap-4">
          <div className="flex items-center gap-6 flex-wrap">
            <div>
              <p className="label">Total shadows</p>
              <p className="stat-value-sm mt-0.5">{summary.totalShadows}</p>
            </div>
            <div>
              <p className="label">Held at home bank</p>
              <p className="stat-value-sm mt-0.5">{summary.homeBankShadows}</p>
              <p className="body-sm text-neutral-500 dark:text-neutral-400">
                {summary.homeBankName ?? 'Home bank'}
              </p>
            </div>
          </div>
          {currencyChipRow}
        </div>
      ) : (
        <div className={cn(
          'relative rounded-2xl overflow-hidden animate-fade-in',
          'border border-neutral-200/70 dark:border-primary-800/60',
          // Brand-moment hero gradient — deliberately retains the gold-lens
          // treatment of the shared app-wide hero metric primitive this
          // replaces (a sanctioned brand pattern). Not an incidental
          // data-surface gradient (those were flattened in the
          // de-gradient passes).
          'bg-gradient-to-br from-white via-white to-accent-50/40',
          'dark:from-primary-900/60 dark:via-primary-900/40 dark:to-accent-500/[0.06]',
          'p-6',
        )}>
          {/* Same gold lens accent the prior hero metric primitive used;
              pulls from --section-accent-warm so the hue shifts per route. */}
          <div
            aria-hidden
            className="absolute -top-20 -right-20 w-80 h-80 rounded-full opacity-50 dark:opacity-70 blur-3xl pointer-events-none"
            style={{ background: 'radial-gradient(circle, rgb(var(--section-accent-warm) / 0.20) 0%, transparent 70%)' }}
          />

          <div className="relative">
            <p className="label mb-3">Liquidity by currency</p>

            {currencies.length === 0 ? (
              <p className="body-sm">No shadow balances available.</p>
            ) : (
              <div className="flex flex-wrap gap-x-8 gap-y-4">
                {currencies.map((c) => (
                  <div key={c.currencyCode} className="min-w-[8rem]">
                    <p className="font-mono text-xs text-neutral-500 dark:text-neutral-400 mb-1">
                      {c.currencyCode}
                    </p>
                    <p className="stat-value-sm">
                      {formatCurrency(c.totalEffective, c.currencyCode)}
                    </p>
                    <p className="body-sm text-neutral-500 dark:text-neutral-400 mt-0.5">
                      {c.shadowCount} mirror{c.shadowCount === 1 ? '' : 's'}
                      {' · '}
                      {c.bankShares.length} bank{c.bankShares.length === 1 ? '' : 's'}
                    </p>
                  </div>
                ))}
              </div>
            )}

            {/* Pool-eligible + total shadow counts demoted to a footer strip —
                still visible, no longer competing for hero attention. */}
            <div className="mt-5 pt-4 border-t border-neutral-200/70 dark:border-primary-800/60 flex flex-wrap gap-x-8 gap-y-2">
              <div>
                <p className="label">Total shadows</p>
                <p className="body mt-0.5">{summary.totalShadows}</p>
              </div>
              <div>
                <p className="label">Held at home bank</p>
                <p className="body mt-0.5">
                  {summary.homeBankShadows}
                  <span className="body-sm text-neutral-500 dark:text-neutral-400 ml-2">
                    of {summary.totalShadows}
                  </span>
                </p>
              </div>
              {summary.homeBankName && (
                <div>
                  <p className="label">Home bank</p>
                  <p className="body mt-0.5">{summary.homeBankName}</p>
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      {/* 1b. Consolidated cross-currency total + available/trapped cash —
          the one place this page blends currencies, always paired with the
          "indicative rates" badge so it never reads as a second source of
          truth for the honest per-currency figures above. Skipped in
          compact mode (the cockpit band stays a glance-surface). */}
      {!compact && currencies.length > 0 && (
        <Card padding="sm">
          <div className="flex items-start justify-between gap-3 flex-wrap mb-4">
            <div>
              <div className="flex items-center gap-2">
                <p className="label">Consolidated position</p>
                <span
                  title="Converted using this platform's stored FX rates, which are seeded reference data — not a live market feed. Treat this total as indicative, not a live mark."
                  className="inline-flex items-center gap-1 text-xs px-1.5 py-0.5 rounded bg-warning-50 text-warning-700 border border-warning-200 dark:bg-warning-500/10 dark:text-warning-300 dark:border-warning-500/30"
                >
                  <Info className="w-3 h-3" />
                  Indicative rates
                </span>
              </div>
              <p className="stat-value-sm mt-1">
                {consolidated.loading ? '…' : formatCurrency(consolidated.total, reportingCurrency)}
              </p>
              {consolidated.excluded > 0 && (
                <p className="body-sm text-warning-600 dark:text-warning-400 mt-0.5">
                  {consolidated.excluded} currenc{consolidated.excluded === 1 ? 'y' : 'ies'} excluded — no rate available
                </p>
              )}
            </div>
            <div className="w-28">
              <CurrencyPicker value={reportingCurrency} onChange={setReportingCurrency} />
            </div>
          </div>
          <div className="grid grid-cols-2 gap-4 pt-4 border-t border-neutral-200/70 dark:border-primary-800/60">
            <div>
              <p className="label">Available to move</p>
              <p className="body mt-0.5">
                {consolidated.loading ? '…' : formatCurrency(consolidated.available, reportingCurrency)}
              </p>
            </div>
            <div>
              <p className="label">Committed / held</p>
              <p className="body mt-0.5">
                {/* bankBalanceEffective = bankAvailableBalance - bankBalanceCommitted
                    (see VirtualAccount.getBankBalanceEffective()), so committed is
                    available minus effective, not the other way round. */}
                {consolidated.loading ? '…' : formatCurrency(Math.max(0, consolidated.available - consolidated.total), reportingCurrency)}
              </p>
            </div>
          </div>
        </Card>
      )}

      {/* 2. Shadow distribution (count-based, FX-honest). */}
      <DistributionWrap>
        <div className="flex items-start justify-between gap-3 mb-3">
          <p className="label">Shadow distribution across banks</p>
          {topExternalBank && (
            <p className="body-sm text-neutral-500 dark:text-neutral-400">
              Top external · {topExternalBank.bankName ?? topExternalBank.bankBic} {topExternalPct}%
            </p>
          )}
        </div>
        <div
          className="flex h-3 rounded-md overflow-hidden border border-neutral-200 dark:border-primary-800"
          role="img"
          aria-label="Shadow distribution"
        >
          {distributionSegments.map(({ bank, pct, colour }) => (
            <div
              key={bank.bankBic}
              className={cn('h-full', colour)}
              style={{ width: `${pct}%` }}
              title={`${bank.bankName ?? bank.bankBic} · ${bank.shadowCount} shadows`}
            />
          ))}
        </div>
        <div className="flex flex-wrap gap-x-3 gap-y-1 mt-3">
          {distributionSegments.map(({ bank, pct, colour }) => (
            <span key={bank.bankBic} className="inline-flex items-center gap-1.5 text-xs">
              <span className={cn('w-2 h-2 rounded-sm', colour)} />
              <span className={cn(
                bank.homeBank
                  ? 'text-success-700 dark:text-success-300 font-medium'
                  : 'text-neutral-600 dark:text-neutral-300'
              )}>
                {bank.bankName ?? bank.bankBic}
              </span>
              <span className="text-neutral-500 dark:text-neutral-400">
                {bank.shadowCount} shadows · {formatPct(pct)}
              </span>
            </span>
          ))}
        </div>
      </DistributionWrap>

      {/* 3. Operational stat strip — four filter tiles. In compact mode the
          tiles render read-only (the cockpit's attention inbox is the place
          for action; the band is just a glance-surface). */}
      <StatStrip columns={4}>
        <MetricCard
          icon={<Layers className="w-5 h-5" />}
          tone="primary"
          label="Total Shadows"
          value={summary.totalShadows.toString()}
        />
        <MetricCard
          icon={<AlertTriangle className="w-5 h-5" />}
          tone={summary.staleCount > 0 ? 'warning' : 'neutral'}
          label="Stale"
          value={summary.staleCount.toString()}
          sub="Exceeds threshold"
          onClick={compact ? undefined : () => { setFilter('stale'); setView?.('by-bank'); }}
          active={!compact && filter === 'stale'}
        />
        <MetricCard
          icon={<RefreshCw className="w-5 h-5" />}
          tone={failedCount > 0 ? 'danger' : 'neutral'}
          label="Failed"
          value={failedCount.toString()}
          sub="Refresh errored"
          onClick={compact ? undefined : () => { setFilter('failed'); setView?.('by-bank'); }}
          active={!compact && filter === 'failed'}
        />
        <MetricCard
          icon={<Clock className="w-5 h-5" />}
          tone={summary.neverRefreshedCount > 0 ? 'danger' : 'neutral'}
          label="Never Refreshed"
          value={summary.neverRefreshedCount.toString()}
          sub="No fetch attempted"
          onClick={compact ? undefined : () => { setFilter('never'); setView?.('by-bank'); }}
          active={!compact && filter === 'never'}
        />
      </StatStrip>

      {/* 4. Freshness banner — only when there's work. Suppressed in compact
          mode because the cockpit's attention inbox already surfaces
          stale balances as their own attention items. */}
      {!compact && hasWork && (
        <Card
          padding="sm"
          className="bg-warning-50 dark:bg-warning-500/10 border-warning-200 dark:border-warning-500/30"
        >
          <div className="flex items-center gap-3 flex-wrap">
            <StatusIconBadge tone="warning" icon={RefreshCw} />
            <p className="body-sm flex-1 min-w-0 text-neutral-700 dark:text-neutral-200">
              {summary.staleCount + summary.neverRefreshedCount + failedCount} shadow balance{summary.staleCount + summary.neverRefreshedCount + failedCount === 1 ? '' : 's'} need attention. Effective totals exclude stale and never-refreshed positions.
            </p>
            <Button
              variant="outline"
              size="sm"
              onClick={onBulkRefreshStale}
              disabled={bulkRefreshing}
              leftIcon={<RefreshCw className={cn('w-4 h-4', bulkRefreshing && 'animate-spin')} />}
            >
              Refresh all
            </Button>
          </div>
        </Card>
      )}

      {/* 5. Per-currency rail — bank split bar within each currency. In compact
          mode the rail is capped to the top-4 currencies and uses lighter
          chrome (bordered div instead of nested Card). */}
      {visibleCurrencies.length > 0 && (
        <div className="space-y-3">
          <div className="flex items-end justify-between">
            <p className="label">By currency</p>
            <p className="body-sm text-neutral-500 dark:text-neutral-400">
              {compact && visibleCurrencies.length < currencies.length
                ? `Top ${visibleCurrencies.length} of ${currencies.length} · effective balance · bank split`
                : 'Effective balance · bank split'}
            </p>
          </div>
          {visibleCurrencies.map((c) => {
            const externalHeavy = c.bankShares.some((b) => b.homeBank) && c.homeBankShare < 0.5 && c.bankShares.length > 1;
            const singleSourceExternal = c.bankShares.length === 1 && !c.bankShares[0].homeBank;
            const inner = (
              <>
                <div className="flex items-start justify-between gap-3 mb-3 flex-wrap">
                  <div className="flex items-center gap-2 min-w-0">
                    {/* Currency code is a tabular identifier (mono) rendered
                        at body-sm weight per multi-bank-redesign.md. The
                        existing `.code` utility uses the same mono +
                        primary text recipe but at xs size; we want sm here
                        so the code line-aligns with the right-side stat. */}
                    {/* eslint-disable-next-line no-restricted-syntax */}
                    <span className="font-mono text-sm font-medium text-primary-900 dark:text-neutral-50">{c.currencyCode}</span>
                    <span className="body-sm text-neutral-500 dark:text-neutral-400">
                      {c.shadowCount} mirror{c.shadowCount === 1 ? '' : 's'} · {c.bankShares.length} bank{c.bankShares.length === 1 ? '' : 's'}
                    </span>
                    {externalHeavy && (
                      <Badge variant="warning" size="xs">External-heavy</Badge>
                    )}
                    {singleSourceExternal && (
                      <Badge variant="error" size="xs">Single source</Badge>
                    )}
                  </div>
                  <p className="stat-value-xs">{formatCurrency(c.totalEffective, c.currencyCode)}</p>
                </div>
                <BankSplitBar bankShares={c.bankShares} total={c.totalEffective} />
              </>
            );
            return compact ? (
              <div
                key={c.currencyCode}
                className="rounded-lg border border-neutral-200 dark:border-primary-800 p-4"
              >
                {inner}
              </div>
            ) : (
              <Card key={c.currencyCode} padding="sm">{inner}</Card>
            );
          })}
        </div>
      )}

      {/* 6. Filter chips at the bottom — shadow-level filters bounce to By
          Bank where the rows live; bank-level chips stay on Overview and
          narrow the per-currency cards. Suppressed in compact mode (the
          band is a glance-surface, not a filter UI). */}
      {!compact && (
      <div className="flex flex-wrap gap-2 pt-2">
        {([
          { key: 'all',      label: 'All' },
          { key: 'home',     label: 'Home-bank' },
          { key: 'external', label: 'External' },
          { key: 'stale',    label: 'Stale',           count: summary.staleCount,         bounces: true },
          { key: 'failed',   label: 'Failed',          count: failedCount,                bounces: true },
          { key: 'never',    label: 'Never refreshed', count: summary.neverRefreshedCount, bounces: true },
        ] as { key: FilterKey; label: string; count?: number; bounces?: boolean }[]).map(({ key, label, count, bounces }) => (
          <button
            key={key}
            type="button"
            onClick={() => {
              setFilter(key);
              if (bounces) setView?.('by-bank');
            }}
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
      )}

      {/* Helper context for the rare case where no banks match the filter. */}
      {!compact && visibleBanks.length === 0 && (
        <Card padding="md" className="text-center text-neutral-500 dark:text-neutral-400">
          {filter === 'home'
            ? 'No home-bank shadows.'
            : filter === 'external'
              ? 'No external-bank shadows.'
              : 'No shadows match the current filter.'}
        </Card>
      )}
    </>
  );
};
