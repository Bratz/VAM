import React, { useEffect, useMemo, useState } from 'react';
import {
  RefreshCw, AlertTriangle, Clock, Layers, Info, Banknote,
} from 'lucide-react';
import { cn, formatCurrency } from '../../utils';
import {
  MultiBankLiquiditySummary,
  multiBankLiquidityApi,
  TrendPoint,
} from '../../services/api';
import { Card } from '../ui';
import { HeroMetricCard } from '../ui/HeroMetricCard';
import { CurrencyPicker } from '../ui/CurrencyPicker';
import { StatStrip } from '../layout/StatStrip';
import { MetricCard } from './MetricCard';
import { formatPct } from './format';
import type { BankShare } from './BankSplitBar';
import { HOME_BANK_COLOUR, EXTERNAL_BANK_RAMP } from './bankSplitColours';
import { ReportingRates } from '../../hooks/useReportingRates';
import { FilterKey, ViewKey } from './types';

// ============================================================================
// Multi-Bank Liquidity — Overview (default landing view).
//
// Morning-glance read on portfolio-wide liquidity health, anchored by the
// FX-honesty rule: the per-currency figures below never blend currencies
// (the payload carries no FX rates on its own). The one deliberate exception
// is the hero — a consolidated, FX-converted total — always paired with an
// "indicative rates" badge so it never reads as a second source of truth for
// the honest per-currency numbers underneath it.
//
// Composition (top to bottom, expanded mode):
//   1. Hero — consolidated position (HeroMetricCard): total, available,
//      1D/7D/30D delta, currency picker, in-transit figure.
//   2. Liquidity distribution across banks — value-weighted, not count-based.
//   3. Operational StatStrip — 4 filter tiles (total incl. home-bank/home
//      bank name as its sub-line, stale, failed, never)
// The sparkline (= Trend tab), freshness banner (= header "Refresh stale" +
// tiles), per-currency cards (= By Currency tab) and filter chips (= tiles)
// were removed in the density audit, round 2. With no filter UI left here,
// the Overview always shows every bank (a home/external filter picked on
// another tab no longer silently narrows the hero).
//
// `compact` mode (consumed by the cockpit's Multi-Bank Band) skips the hero,
// the rate map, and the trend fetch entirely — it stays the lightweight
// inline strip it always was; only the count-based currency chip row + op
// tiles render.
// ============================================================================

interface OverviewViewProps {
  summary: MultiBankLiquiditySummary;
  filter: FilterKey;
  setFilter: (f: FilterKey) => void;
  /** Optional in `compact` mode (the band has no view-switching surface). */
  setView?: (v: ViewKey) => void;
  failedCount: number;
  /** Scopes the hero's trend fetch. Optional in `compact` mode (no fetch runs there). */
  corporateId?: string;
  /**
   * Reporting currency + rate map, owned by the page so the Accounts country map
   * shares the same fetch. Optional in `compact` mode, which never reads
   * them (no hero, no rate-derived figures in the inline strip).
   */
  reportingCurrency?: string;
  setReportingCurrency?: (c: string) => void;
  rateState?: ReportingRates;
  /**
   * When true, render a slimmer composition suitable for embedding inside
   * another `<Card>` wrapper (the cockpit's Multi-Bank Band). Defaults to
   * false (the standalone /treasury/multi-bank page).
   */
  compact?: boolean;
}

const PERIODS: { key: 1 | 7 | 30; label: string }[] = [
  { key: 1, label: '1D' },
  { key: 7, label: '7D' },
  { key: 30, label: '30D' },
];

export const OverviewView: React.FC<OverviewViewProps> = ({
  summary,
  filter,
  setFilter,
  setView,
  failedCount,
  corporateId,
  reportingCurrency = 'AED',
  setReportingCurrency = () => {},
  rateState = { rates: new Map(), excluded: [], loading: false },
  compact = false,
}) => {
  // Always the full picture — the Overview has no filter UI of its own.
  const visibleBanks = summary.banks;

  // Per-currency aggregation across visibleBanks. Within a single currency
  // we can sum amounts directly — FX-honest. Sorted by size descending to
  // match the Dashboard's own currency-bar ordering (it used to sort
  // alphabetically here, which disagreed with the Dashboard for no reason).
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
      .sort((a, b) => (b.totalEffective || 0) - (a.totalEffective || 0));
  }, [visibleBanks]);

  // Consolidated total + available — the one deliberate cross-currency blend
  // on this page, always paired with the "indicative rates" badge.
  const consolidated = useMemo(() => {
    let total = 0;
    let available = 0;
    for (const c of currencies) {
      const rate = rateState.rates.get(c.currencyCode);
      if (rate === undefined) continue;
      total += c.totalEffective * rate;
      available += c.totalAvailable * rate;
    }
    return { total, available, loading: rateState.loading, excluded: rateState.excluded.length };
  }, [currencies, rateState]);

  // ==========================================================================
  // Historical trend (for the delta chip) — fetched once per
  // corporate scope, independent of the rate map so switching reporting
  // currency doesn't re-fetch history, only re-converts it.
  // ==========================================================================
  const [period, setPeriod] = useState<1 | 7 | 30>(1);
  const [trendPoints, setTrendPoints] = useState<TrendPoint[]>([]);

  useEffect(() => {
    if (compact) return;
    let alive = true;
    multiBankLiquidityApi.getTrend(corporateId, 31)
      .then((res) => { if (alive) setTrendPoints(res.success && Array.isArray(res.data) ? res.data : []); })
      .catch(() => { if (alive) setTrendPoints([]); });
    return () => { alive = false; };
  }, [corporateId, compact]);

  const trendByDate = useMemo(() => {
    const m = new Map<string, Map<string, number>>();
    for (const p of trendPoints) {
      const day = m.get(p.asOf) ?? new Map<string, number>();
      day.set(p.currencyCode, p.totalEffective);
      m.set(p.asOf, day);
    }
    return m;
  }, [trendPoints]);

  const sortedDates = useMemo(() => Array.from(trendByDate.keys()).sort(), [trendByDate]);

  // Converts one historical day's per-currency totals through the CURRENT
  // rate map (no historical FX is tracked — same "indicative" honesty as
  // everything else here). Returns null if none of that day's currencies
  // have a rate yet.
  const convertDay = (dateKey: string): number | null => {
    const day = trendByDate.get(dateKey);
    if (!day) return null;
    let total = 0;
    let any = false;
    for (const [code, amt] of day.entries()) {
      const rate = rateState.rates.get(code);
      if (rate === undefined) continue;
      total += amt * rate;
      any = true;
    }
    return any ? total : null;
  };

  const delta = useMemo(() => {
    if (rateState.loading || sortedDates.length === 0) return null;
    const target = new Date();
    target.setDate(target.getDate() - period);
    const targetKey = target.toISOString().slice(0, 10);
    let dateKey: string | undefined;
    for (const d of sortedDates) {
      if (d <= targetKey) dateKey = d; else break;
    }
    if (!dateKey) return null;
    const past = convertDay(dateKey);
    if (!past) return null;
    return ((consolidated.total - past) / past) * 100;
    // convertDay is recreated on every render and closes over rateState, which is already a
    // dependency here, so the result changes exactly when rateState does.
    // eslint-disable-next-line react-hooks/exhaustive-deps -- depending on convertDay itself would recompute this every render.
  }, [sortedDates, period, rateState, consolidated.total]);

  // Currency chip row — used in compact mode's inline strip and, in expanded
  // mode, as the slim body of the "Liquidity by currency" card below (the
  // Accounts tab, grouped by currency, is where the account-level breakdown lives).
  const currencyChipRow = currencies.length > 0 ? (
    <div className="flex flex-wrap gap-1.5">
      {currencies.map((c) => (
        <span
          key={c.currencyCode}
          className="amount text-caption px-1.5 py-0.5 rounded-md bg-neutral-100 dark:bg-primary-800/60 text-neutral-700 dark:text-neutral-200"
        >
          {formatCurrency(c.totalEffective, c.currencyCode)}
        </span>
      ))}
    </div>
  ) : null;

  // ==========================================================================
  // Liquidity distribution across banks — value-weighted (% of converted
  // cash value), not the account-count split this used to be. Counting
  // accounts answers "where are my accounts"; a treasurer opening this bar
  // wants "where is my money" — a real counterparty-concentration read,
  // which account-count silently wasn't.
  // ==========================================================================
  const bankDistribution = useMemo(() => {
    const withValue = summary.banks.map((bank) => {
      let value = 0;
      let hasValue = false;
      for (const c of bank.currencies) {
        const rate = rateState.rates.get(c.currencyCode);
        if (rate === undefined) continue;
        value += (c.totalEffective || 0) * rate;
        hasValue = true;
      }
      return { bank, value, hasValue };
    }).sort((a, b) => {
      if (a.bank.homeBank && !b.bank.homeBank) return -1;
      if (!a.bank.homeBank && b.bank.homeBank) return 1;
      return b.value - a.value;
    });
    const totalValue = withValue.reduce((a, x) => a + x.value, 0);
    let extIdx = 0;
    const segments = withValue.map(({ bank, value, hasValue }) => ({
      bank,
      value,
      hasValue,
      pct: totalValue > 0 ? (value / totalValue) * 100 : 0,
      colour: bank.homeBank ? HOME_BANK_COLOUR : EXTERNAL_BANK_RAMP[extIdx++ % EXTERNAL_BANK_RAMP.length],
    }));
    const topExternal = segments.find((s) => !s.bank.homeBank && s.hasValue);
    return { segments, topExternal };
  }, [summary.banks, rateState]);

  // Distribution panel — outer container differs in compact mode (no Card to
  // avoid a card-in-card visual).
  const DistributionWrap: React.FC<{ children: React.ReactNode }> = ({ children }) =>
    compact
      ? <div className="rounded-lg border border-edge p-4">{children}</div>
      : <Card padding="sm">{children}</Card>;

  // In transit (pending sweeps) — bankBalanceEffective = bankAvailableBalance
  // - bankBalanceCommitted (see VirtualAccount.getBankBalanceEffective()), so
  // this is available minus effective, not the other way round. It's
  // outstanding sweep instructions not yet reflected on the bank statement —
  // a settlement-timing float, not a structural liquidity restriction (no
  // regulatory-minimum/FX-control data model exists in this app) — hence the
  // label and tooltip below, not "Committed / held".
  const inTransit = Math.max(0, consolidated.available - consolidated.total);

  return (
    <>
      {compact ? (
        <div className="flex flex-wrap items-end justify-between gap-4">
          <div className="flex items-center gap-6 flex-wrap">
            <div>
              <p className="label">Accounts</p>
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
        <>
          {/* 1. Hero — consolidated position. The one deliberate cross-
              currency blend on this page; HeroMetricCard is the shared
              dominant-metric primitive other pages already use for their
              own headline figure. */}
          {currencies.length > 0 && (
            <HeroMetricCard
              icon={<Banknote className="w-6 h-6 text-accent-700 dark:text-accent-300" />}
              primary={{
                label: 'Consolidated position',
                value: consolidated.loading ? '…' : formatCurrency(consolidated.total, reportingCurrency),
                trend: delta !== null ? `${delta >= 0 ? '▲' : '▼'} ${formatPct(Math.abs(delta))} · ${period}D` : undefined,
                trendTone: delta === null ? 'neutral' : delta >= 0 ? 'success' : 'error',
                sub: (
                <div className="space-y-3">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span
                      title="Converted using this platform's stored FX rates, which are seeded reference data — not a live market feed. Treat this total as indicative, not a live mark."
                      className="inline-flex items-center gap-1 text-caption px-1.5 py-0.5 rounded-md bg-warning-50 text-warning-700 border border-warning-200 dark:bg-warning-500/10 dark:text-warning-300 dark:border-warning-500/30"
                    >
                      <Info className="w-3 h-3" />
                      Indicative rates
                    </span>
                    {consolidated.excluded > 0 && (
                      <span className="caption-warning">
                        {consolidated.excluded} currenc{consolidated.excluded === 1 ? 'y' : 'ies'} excluded — no rate available
                      </span>
                    )}
                    <span
                      title="Outstanding sweep instructions not yet reflected on the bank statement — a settlement-timing float, not a structural liquidity restriction."
                      className="inline-flex items-center gap-1 caption"
                    >
                      In transit (pending sweeps)
                      <Info className="w-3 h-3" />
                      <span className="amount">{consolidated.loading ? '…' : formatCurrency(inTransit, reportingCurrency)}</span>
                    </span>
                  </div>

                  <div className="flex items-center gap-3 flex-wrap">
                    <div className="w-24">
                      <CurrencyPicker value={reportingCurrency} onChange={setReportingCurrency} />
                    </div>
                    <div
                      role="tablist"
                      aria-label="Trend period"
                      className="inline-flex border border-edge-strong rounded-lg overflow-hidden"
                    >
                      {PERIODS.map(({ key, label }) => (
                        <button
                          key={key}
                          type="button"
                          role="tab"
                          aria-selected={period === key}
                          onClick={() => setPeriod(key)}
                          className={cn(
                            'px-2.5 py-1 text-caption font-medium transition-colors',
                            period === key
                              ? 'bg-primary-900 text-white dark:bg-accent-500 dark:text-white'
                              : 'bg-transparent text-neutral-600 dark:text-neutral-300 hover:bg-neutral-100 dark:hover:bg-primary-800',
                          )}
                        >
                          {label}
                        </button>
                      ))}
                    </div>
                  </div>
                </div>
                ),
              }}
              secondary={{
                label: 'Available to move',
                value: consolidated.loading ? '…' : formatCurrency(consolidated.available, reportingCurrency),
              }}
            />
          )}

        </>
      )}

      {/* 2. Liquidity distribution across banks (value-weighted). */}
      <DistributionWrap>
        <div className="flex items-start justify-between gap-3 mb-3">
          <p className="label">Liquidity distribution across banks</p>
          {bankDistribution.topExternal && (
            <p className="body-sm text-neutral-500 dark:text-neutral-400">
              Top external · {bankDistribution.topExternal.bank.bankName ?? bankDistribution.topExternal.bank.bankBic} {formatPct(bankDistribution.topExternal.pct)}
            </p>
          )}
        </div>
        <div
          className="flex h-3 rounded-md overflow-hidden border border-edge"
          role="img"
          aria-label="Liquidity distribution by bank"
        >
          {bankDistribution.segments.map(({ bank, pct, colour }) => (
            <div
              key={bank.bankBic}
              className={cn('h-full', colour)}
              style={{ width: `${pct}%` }}
              title={`${bank.bankName ?? bank.bankBic} · ${bank.shadowCount} accounts · ${formatPct(pct)} of value`}
            />
          ))}
        </div>
        <div className="flex flex-wrap gap-x-3 gap-y-1 mt-3">
          {bankDistribution.segments.map(({ bank, pct, hasValue, colour }) => (
            <span key={bank.bankBic} className="inline-flex items-center gap-1.5 text-caption">
              <span className={cn('w-2 h-2 rounded-sm', colour)} />
              <span className={cn(
                bank.homeBank
                  ? 'text-accent-700 dark:text-accent-300 font-medium'
                  : 'text-neutral-600 dark:text-neutral-300'
              )}>
                {bank.bankName ?? bank.bankBic}
              </span>
              <span className="text-neutral-500 dark:text-neutral-400">
                {hasValue ? formatPct(pct) : 'value unavailable'}
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
          label="Accounts"
          value={summary.totalShadows.toString()}
          sub={`${summary.homeBankShadows} at home bank${summary.homeBankName ? ` · ${summary.homeBankName}` : ''}`}
        />
        <MetricCard
          icon={<AlertTriangle className="w-5 h-5" />}
          tone={summary.staleCount > 0 ? 'warning' : 'neutral'}
          label="Stale"
          value={summary.staleCount.toString()}
          sub="Exceeds threshold"
          onClick={compact ? undefined : () => { setFilter('stale'); setView?.('accounts'); }}
          active={!compact && filter === 'stale'}
        />
        <MetricCard
          icon={<RefreshCw className="w-5 h-5" />}
          tone={failedCount > 0 ? 'danger' : 'neutral'}
          label="Failed"
          value={failedCount.toString()}
          sub="Refresh errored"
          onClick={compact ? undefined : () => { setFilter('failed'); setView?.('accounts'); }}
          active={!compact && filter === 'failed'}
        />
        <MetricCard
          icon={<Clock className="w-5 h-5" />}
          tone={summary.neverRefreshedCount > 0 ? 'danger' : 'neutral'}
          label="Never Refreshed"
          value={summary.neverRefreshedCount.toString()}
          sub="No fetch attempted"
          onClick={compact ? undefined : () => { setFilter('never'); setView?.('accounts'); }}
          active={!compact && filter === 'never'}
        />
      </StatStrip>
    </>
  );
};
