import React, { useMemo } from 'react';
import { RefreshCw, Loader2, Globe2 } from 'lucide-react';
import { formatCurrency } from '../../utils';
import {
  MultiBankLiquiditySummary,
  ShadowSummary,
} from '../../services/api';
import { Card, StatusIconBadge, DataTable } from '../ui';
import { CurrencyPicker } from '../ui/CurrencyPicker';
import { CountryExposureMap } from './CountryExposureMap';
import { ReportingRates } from './useReportingRates';
import { FreshnessPill } from './FreshnessPill';
import { FilterChips } from './FilterChips';
import { FilterKey } from './types';

// ============================================================================
// Multi-Bank Liquidity — By Country view.
//
// Same grouping mechanics as ByEntityView, keyed by the owning entity's
// countryCode instead of its name — the geography lens a global CFO asks
// for first. Amounts stay broken out per currency within a country, never
// blended (FX-honesty rule).
//
// Shadows whose owning entity has no country on file land in an "Unknown"
// group sorted last.
// ============================================================================

interface ByCountryViewProps {
  summary: MultiBankLiquiditySummary;
  filter: FilterKey;
  setFilter: (f: FilterKey) => void;
  refreshingIds: Set<string>;
  refresh: (shadow: ShadowSummary) => void;
  failedCount: number;
  reportingCurrency: string;
  setReportingCurrency: (c: string) => void;
  rateState: ReportingRates;
}

type CountryCurrencyRow = {
  currencyCode: string;
  totalEffective: number;
  totalCommitted: number;
  shadows: ShadowSummary[];
};

type CountryGroup = {
  country: string;
  shadowCount: number;
  currencies: CountryCurrencyRow[];
};

const UNKNOWN = 'Unknown';

function buildCountryView(summary: MultiBankLiquiditySummary, filter: FilterKey): CountryGroup[] {
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

  const groups = new Map<string, Map<string, ShadowSummary[]>>();
  for (const b of summary.banks) {
    if (!matchesBank(b.homeBank)) continue;
    for (const c of b.currencies) {
      for (const s of c.shadows) {
        if (!matchesShadow(s)) continue;
        const country = s.owningEntityCountry || UNKNOWN;
        const byCurrency = groups.get(country) ?? new Map<string, ShadowSummary[]>();
        const shadows = byCurrency.get(c.currencyCode) ?? [];
        shadows.push(s);
        byCurrency.set(c.currencyCode, shadows);
        groups.set(country, byCurrency);
      }
    }
  }

  return Array.from(groups.entries())
    .map(([country, byCurrency]) => {
      const currencies: CountryCurrencyRow[] = Array.from(byCurrency.entries())
        .map(([currencyCode, shadows]) => ({
          currencyCode,
          totalEffective: shadows.reduce((a, s) => a + (s.bankBalanceEffective || 0), 0),
          totalCommitted: shadows.reduce((a, s) => a + (s.bankBalanceCommitted || 0), 0),
          shadows,
        }))
        .sort((a, b) => a.currencyCode.localeCompare(b.currencyCode));
      return {
        country,
        shadowCount: currencies.reduce((a, c) => a + c.shadows.length, 0),
        currencies,
      };
    })
    .sort((a, b) => {
      if (a.country === UNKNOWN) return 1;
      if (b.country === UNKNOWN) return -1;
      return a.country.localeCompare(b.country);
    });
}

export const ByCountryView: React.FC<ByCountryViewProps> = ({
  summary,
  filter,
  setFilter,
  refreshingIds,
  refresh,
  failedCount,
  reportingCurrency,
  setReportingCurrency,
  rateState,
}) => {
  const countries = useMemo(() => buildCountryView(summary, filter), [summary, filter]);

  // Per-country totals converted to the shared reporting currency, for the
  // map's bubble sizes. "Unknown" (no owningEntityCountry on file) can't be
  // plotted and is excluded here — it stays visible in the table below.
  const countryTotals = useMemo(() => {
    return countries
      .filter((g) => g.country !== UNKNOWN)
      .map((g) => {
        let amount = 0;
        for (const c of g.currencies) {
          const rate = rateState.rates.get(c.currencyCode);
          if (rate === undefined) continue;
          amount += c.totalEffective * rate;
        }
        return { code: g.country, amount };
      })
      .filter((c) => c.amount > 0);
  }, [countries, rateState]);

  return (
    <>
      {countryTotals.length > 0 && (
        <Card padding="sm">
          <div className="flex items-center justify-between gap-3 mb-3 flex-wrap">
            <p className="label">Liquidity by country</p>
            <div className="w-24">
              <CurrencyPicker value={reportingCurrency} onChange={setReportingCurrency} />
            </div>
          </div>
          <CountryExposureMap countryTotals={countryTotals} currency={reportingCurrency} />
        </Card>
      )}

      <FilterChips
        filter={filter}
        setFilter={setFilter}
        staleCount={summary.staleCount}
        failedCount={failedCount}
        neverCount={summary.neverRefreshedCount}
      />

      {countries.length === 0 && (
        <div className="bg-surface-card rounded-lg border border-edge p-12 text-center text-neutral-500 dark:text-neutral-400">
          No shadows match the current filter.
        </div>
      )}

      <div className="space-y-4">
        {countries.map((group) => (
          <div
            key={group.country}
            className="rounded-lg shadow-sm border border-edge bg-surface-card"
          >
            <div className="p-5 border-b border-edge-subtle flex items-center justify-between gap-3 flex-wrap">
              <div className="flex items-center gap-3 min-w-0">
                <StatusIconBadge tone="neutral" icon={Globe2} />
                <div>
                  <h2 className="section-title">{group.country}</h2>
                  <p className="caption mt-0.5">
                    {group.shadowCount} shadow{group.shadowCount === 1 ? '' : 's'} · {group.currencies.length} currenc{group.currencies.length === 1 ? 'y' : 'ies'}
                  </p>
                </div>
              </div>
            </div>

            <div className="p-5">
              <DataTable
                hairline
                data={group.currencies.flatMap((c) => c.shadows.map((s, i) => ({ s, c, i })))}
                keyExtractor={(r) => r.s.vaId}
                columns={[
                  { key: 'currency', header: 'Currency', minWidth: 100, mobileLabel: true, render: (_v, r) => (
                    <span className="font-mono text-caption text-primary-900 dark:text-neutral-50">{r.i === 0 ? r.c.currencyCode : ''}</span>
                  ) },
                  { key: 'account', header: 'Bank Account', minWidth: 160, dropOrder: 2, render: (_v, r) => r.s.bankAccountNumber ?? r.s.bankIban ?? '—' },
                  { key: 'committed', header: 'Committed', align: 'right', minWidth: 130, dropOrder: 1, render: (_v, r) => (
                    <span className="amount text-neutral-700 dark:text-neutral-200">{r.s.bankBalanceCommitted ? formatCurrency(r.s.bankBalanceCommitted, r.s.currencyCode) : '—'}</span>
                  ) },
                  { key: 'effective', header: 'Effective', align: 'right', minWidth: 130, mobileValue: true, render: (_v, r) => (
                    <span className="amount text-primary-900 dark:text-neutral-50">{formatCurrency(r.s.bankBalanceEffective, r.s.currencyCode)}</span>
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
