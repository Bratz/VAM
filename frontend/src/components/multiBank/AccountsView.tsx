import React, { useEffect, useMemo, useState } from 'react';
import { RefreshCw, Loader2 } from 'lucide-react';
import { formatCurrency } from '../../utils';
import { MultiBankLiquiditySummary, ShadowSummary } from '../../services/api';
import { Card, DataTable } from '../ui';
import type { Column } from '../ui';
import { CurrencyPicker } from '../ui/CurrencyPicker';
import { ReportingRates } from '../../hooks/useReportingRates';
import { CountryExposureMap } from './CountryExposureMap';
import { FreshnessPill } from './FreshnessPill';
import { FilterChips } from './FilterChips';
import { ViewSwitcher } from './ViewSwitcher';
import { FilterKey, GroupBy } from './types';

// ============================================================================
// Multi-Bank Liquidity — Accounts view.
//
// One paged table of every account, grouped by bank, currency, entity or
// country. Replaces the four By Bank / By Currency / By Entity / By Country
// tabs, which showed the same accounts as one card per group with
// near-identical columns (density audit, round 3).
//
// Rows are sorted so each group is contiguous; the Group column names the
// group on its first row on each page, with the group's account count and
// effective subtotal per currency — never summed across currencies. Subtotals
// cover the whole group (every page) under the active filter. Grouped by
// country, the exposure map (formerly on the By Country tab) sits above.
// ============================================================================

const PAGE_SIZE = 25;
const UNASSIGNED = 'Unassigned';
const UNKNOWN = 'Unknown';

const GROUP_OPTIONS: { key: GroupBy; label: string }[] = [
  { key: 'bank',     label: 'Bank' },
  { key: 'currency', label: 'Currency' },
  { key: 'entity',   label: 'Entity' },
  { key: 'country',  label: 'Country' },
];

type Row = { s: ShadowSummary; bankBic: string; bankName?: string; homeBank: boolean; groupKey: string };

type Group = {
  key: string;
  shadowCount: number;
  /** Effective subtotal per currency, sorted by code. */
  subtotals: { currencyCode: string; amount: number }[];
  homeBank: boolean;
  bankName?: string;
};

const HomeBankPill = () => (
  <span className="text-caption font-bold uppercase tracking-wider px-2 py-0 leading-4 rounded-full bg-accent-100 text-accent-700 dark:bg-accent-500/20 dark:text-accent-300">
    Home bank
  </span>
);

function groupKeyOf(groupBy: GroupBy, s: ShadowSummary, bankBic: string, currencyCode: string): string {
  if (groupBy === 'bank') return bankBic;
  if (groupBy === 'currency') return currencyCode;
  if (groupBy === 'entity') return s.owningEntityName || UNASSIGNED;
  return s.owningEntityCountry || UNKNOWN;
}

// Filter, then group. Bank grouping keeps the summary's bank order (home bank
// first, from the backend); the others sort by name with Unassigned/Unknown last.
function buildRows(summary: MultiBankLiquiditySummary, filter: FilterKey, groupBy: GroupBy) {
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

  const byGroup = new Map<string, Row[]>();
  for (const b of summary.banks) {
    if (!matchesBank(b.homeBank)) continue;
    for (const c of b.currencies) {
      for (const s of c.shadows) {
        if (!matchesShadow(s)) continue;
        const groupKey = groupKeyOf(groupBy, s, b.bankBic, c.currencyCode);
        const rows = byGroup.get(groupKey) ?? [];
        rows.push({ s, bankBic: b.bankBic, bankName: b.bankName, homeBank: b.homeBank, groupKey });
        byGroup.set(groupKey, rows);
      }
    }
  }

  const keys = Array.from(byGroup.keys());
  if (groupBy !== 'bank') {
    const last = groupBy === 'entity' ? UNASSIGNED : UNKNOWN;
    keys.sort((a, b) => (a === last ? 1 : b === last ? -1 : a.localeCompare(b)));
  }

  const groups = new Map<string, Group>();
  const rows: Row[] = [];
  for (const key of keys) {
    // Stable sort: within a group, currencies together, source order kept.
    const groupRows = byGroup.get(key)!.sort((a, b) => a.s.currencyCode.localeCompare(b.s.currencyCode));
    const totals = new Map<string, number>();
    for (const r of groupRows) {
      totals.set(r.s.currencyCode, (totals.get(r.s.currencyCode) ?? 0) + (r.s.bankBalanceEffective || 0));
    }
    groups.set(key, {
      key,
      shadowCount: groupRows.length,
      subtotals: Array.from(totals, ([currencyCode, amount]) => ({ currencyCode, amount })),
      homeBank: groupRows[0].homeBank,
      bankName: groupRows[0].bankName,
    });
    rows.push(...groupRows);
  }
  return { rows, groups };
}

export interface AccountsViewProps {
  summary: MultiBankLiquiditySummary;
  filter: FilterKey;
  setFilter: (f: FilterKey) => void;
  groupBy: GroupBy;
  setGroupBy: (g: GroupBy) => void;
  refreshingIds: Set<string>;
  refresh: (shadow: ShadowSummary) => void;
  failedCount: number;
  reportingCurrency: string;
  setReportingCurrency: (c: string) => void;
  rateState: ReportingRates;
}

export const AccountsView: React.FC<AccountsViewProps> = ({
  summary,
  filter,
  setFilter,
  groupBy,
  setGroupBy,
  refreshingIds,
  refresh,
  failedCount,
  reportingCurrency,
  setReportingCurrency,
  rateState,
}) => {
  const { rows, groups } = useMemo(() => buildRows(summary, filter, groupBy), [summary, filter, groupBy]);

  const [page, setPage] = useState(1);
  useEffect(() => { setPage(1); }, [filter, groupBy]);
  const pageCount = Math.max(1, Math.ceil(rows.length / PAGE_SIZE));
  const currentPage = Math.min(page, pageCount);
  const pageRows = rows.slice((currentPage - 1) * PAGE_SIZE, currentPage * PAGE_SIZE);

  // Per-country totals converted to the shared reporting currency, for the
  // map's bubble sizes. "Unknown" (no owningEntityCountry on file) can't be
  // plotted and is excluded here — it stays visible in the table below.
  const countryTotals = useMemo(() => {
    if (groupBy !== 'country') return [];
    return Array.from(groups.values())
      .filter((g) => g.key !== UNKNOWN)
      .map((g) => {
        let amount = 0;
        for (const t of g.subtotals) {
          const rate = rateState.rates.get(t.currencyCode);
          if (rate === undefined) continue;
          amount += t.amount * rate;
        }
        return { code: g.key, amount };
      })
      .filter((c) => c.amount > 0);
  }, [groupBy, groups, rateState]);

  // Empty-state copy. After a bulk refresh the page resets shadow-level
  // filters, but a deep link (?filter=stale) can still land on zero rows.
  const emptyTitle = summary.banks.length === 0
    ? 'No bank accounts found. Add them on the Bank Accounts page first.'
    : filter === 'home'     ? 'No home-bank accounts.'
    : filter === 'external' ? 'No external-bank accounts.'
    : filter === 'stale'    ? 'No stale balances — all are fresh.'
    : filter === 'failed'   ? 'No failed refreshes.'
    : filter === 'never'    ? 'All balances have been refreshed at least once.'
    : 'No accounts match the current filter.';

  const groupLabel = GROUP_OPTIONS.find((o) => o.key === groupBy)!.label;

  const columns: Column<Row>[] = [
    { key: 'group', header: groupLabel, minWidth: 190, render: (_v, r, i) => {
      if (i > 0 && pageRows[i - 1].groupKey === r.groupKey) return null;
      const g = groups.get(r.groupKey)!;
      return (
        <div className="space-y-0.5">
          {groupBy === 'bank' ? (
            <>
              <div className="flex items-center gap-2 flex-wrap">
                <span className="font-medium text-primary-900 dark:text-neutral-50">{g.bankName ?? g.key}</span>
                {g.homeBank && <HomeBankPill />}
              </div>
              <p className="font-mono text-caption text-neutral-500 dark:text-neutral-400">{g.key}</p>
            </>
          ) : (
            <p className={groupBy === 'entity' ? 'font-medium text-primary-900 dark:text-neutral-50' : 'font-mono font-medium text-primary-900 dark:text-neutral-50'}>
              {g.key}
            </p>
          )}
          <p className="caption">{g.shadowCount} account{g.shadowCount === 1 ? '' : 's'}</p>
          {g.subtotals.map((t) => (
            <p key={t.currencyCode} className="amount text-caption text-neutral-700 dark:text-neutral-200" title="Subtotal (effective)">
              {formatCurrency(t.amount, t.currencyCode)}
            </p>
          ))}
        </div>
      );
    } },
    { key: 'shadow', header: 'Account', minWidth: 150, mobileLabel: true, render: (_v, r) => (
      <div>
        <div className="text-primary-900 dark:text-neutral-50">{r.s.vaName}</div>
        <div className="font-mono text-caption text-neutral-500 dark:text-neutral-400">{r.s.vaNumber}</div>
      </div>
    ) },
    ...(groupBy === 'bank' ? [] : [{ key: 'bank', header: 'Bank', minWidth: 160, dropOrder: 1, render: (_v: unknown, r: Row) => (
      <div className="flex items-center gap-2 flex-wrap text-neutral-700 dark:text-neutral-200">
        <span>{r.bankName ?? r.bankBic}</span>
        {r.homeBank && <HomeBankPill />}
      </div>
    ) } as Column<Row>]),
    ...(groupBy === 'entity' ? [] : [{ key: 'entity', header: 'Entity', minWidth: 120, dropOrder: 2, render: (_v: unknown, r: Row) => (
      <span className="text-neutral-600 dark:text-neutral-300">{r.s.owningEntityName ?? r.s.owningEntityCode ?? '—'}</span>
    ) } as Column<Row>]),
    { key: 'account', header: 'Bank Account', minWidth: 150, dropOrder: 3, render: (_v, r) => r.s.bankAccountNumber ?? r.s.bankIban ?? '—' },
    { key: 'bankBalance', header: 'Bank Balance', align: 'right', minWidth: 130, dropOrder: 6, render: (_v, r) => (
      <span className="amount text-neutral-700 dark:text-neutral-200">{formatCurrency(r.s.bankBalance, r.s.currencyCode)}</span>
    ) },
    { key: 'available', header: 'Available', align: 'right', minWidth: 130, dropOrder: 7, render: (_v, r) => (
      <span className="amount text-neutral-700 dark:text-neutral-200">{r.s.bankAvailableBalance != null ? formatCurrency(r.s.bankAvailableBalance, r.s.currencyCode) : '—'}</span>
    ) },
    { key: 'committed', header: 'Committed', align: 'right', minWidth: 120, dropOrder: 5, render: (_v, r) => (
      <span className="amount text-neutral-700 dark:text-neutral-200">{r.s.bankBalanceCommitted ? formatCurrency(r.s.bankBalanceCommitted, r.s.currencyCode) : '—'}</span>
    ) },
    { key: 'effective', header: 'Effective', align: 'right', minWidth: 130, mobileValue: true, render: (_v, r) => (
      <span className="amount text-primary-900 dark:text-neutral-50">{formatCurrency(r.s.bankBalanceEffective, r.s.currencyCode)}</span>
    ) },
    { key: 'freshness', header: 'Freshness', minWidth: 130, dropOrder: 4, render: (_v, r) => <FreshnessPill shadow={r.s} /> },
    { key: 'action', header: 'Action', align: 'right', minWidth: 100, render: (_v, r) => (
      <button
        onClick={() => refresh(r.s)}
        disabled={refreshingIds.has(r.s.vaId)}
        aria-label={`Refresh ${r.s.vaNumber}`}
        className="text-primary-600 hover:text-primary-700 dark:text-accent-400 dark:hover:text-accent-300 text-caption inline-flex items-center gap-1 ml-auto disabled:opacity-50 p-2 -m-2 rounded-md focus:outline-none focus-visible:ring-2 focus-visible:ring-primary-400 dark:focus-visible:ring-accent-400"
      >
        {refreshingIds.has(r.s.vaId)
          ? <Loader2 className="w-3 h-3 animate-spin" />
          : <RefreshCw className="w-3 h-3" />
        }
        Refresh
      </button>
    ) },
  ];

  return (
    <>
      {groupBy === 'country' && countryTotals.length > 0 && (
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

      <Card padding="none">
        <div className="p-4 flex items-center gap-3 flex-wrap">
          <span className="label">Group by</span>
          <ViewSwitcher label="Group by" value={groupBy} onChange={setGroupBy} options={GROUP_OPTIONS} />
        </div>
        <DataTable
          hairline
          data={pageRows}
          keyExtractor={(r) => r.s.vaId}
          columns={columns}
          pagination
          pageSize={PAGE_SIZE}
          currentPage={currentPage}
          totalCount={rows.length}
          onPageChange={setPage}
          emptyTitle={emptyTitle}
          emptyDescription=""
        />
      </Card>
    </>
  );
};
