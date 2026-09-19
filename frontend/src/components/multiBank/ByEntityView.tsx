import React, { useMemo } from 'react';
import { RefreshCw, Loader2, Building2 } from 'lucide-react';
import { formatCurrency } from '../../utils';
import {
  MultiBankLiquiditySummary,
  ShadowSummary,
} from '../../services/api';
import { StatusIconBadge, DataTable } from '../ui';
import { FreshnessPill } from './FreshnessPill';
import { FilterChips } from './FilterChips';
import { FilterKey } from './types';

// ============================================================================
// Multi-Bank Liquidity — By Entity view.
//
// Groups shadows by owning legal entity instead of bank or currency. Within
// an entity, amounts are broken out per currency (never summed across
// currencies — same FX-honesty rule as every other view here) via a nested
// currency table, mirroring how By Bank nests currency under bank.
//
// Shadows with no owning entity (nullable, no DB constraint) land in an
// "Unassigned" group sorted last rather than being dropped.
// ============================================================================

interface ByEntityViewProps {
  summary: MultiBankLiquiditySummary;
  filter: FilterKey;
  setFilter: (f: FilterKey) => void;
  refreshingIds: Set<string>;
  refresh: (shadow: ShadowSummary) => void;
  failedCount: number;
}

type EntityCurrencyRow = {
  currencyCode: string;
  totalEffective: number;
  totalCommitted: number;
  shadows: ShadowSummary[];
};

type EntityGroup = {
  entityName: string;
  shadowCount: number;
  currencies: EntityCurrencyRow[];
};

const UNASSIGNED = 'Unassigned';

function buildEntityView(summary: MultiBankLiquiditySummary, filter: FilterKey): EntityGroup[] {
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
        const entityName = s.owningEntityName || UNASSIGNED;
        const byCurrency = groups.get(entityName) ?? new Map<string, ShadowSummary[]>();
        const shadows = byCurrency.get(c.currencyCode) ?? [];
        shadows.push(s);
        byCurrency.set(c.currencyCode, shadows);
        groups.set(entityName, byCurrency);
      }
    }
  }

  return Array.from(groups.entries())
    .map(([entityName, byCurrency]) => {
      const currencies: EntityCurrencyRow[] = Array.from(byCurrency.entries())
        .map(([currencyCode, shadows]) => ({
          currencyCode,
          totalEffective: shadows.reduce((a, s) => a + (s.bankBalanceEffective || 0), 0),
          totalCommitted: shadows.reduce((a, s) => a + (s.bankBalanceCommitted || 0), 0),
          shadows,
        }))
        .sort((a, b) => a.currencyCode.localeCompare(b.currencyCode));
      return {
        entityName,
        shadowCount: currencies.reduce((a, c) => a + c.shadows.length, 0),
        currencies,
      };
    })
    .sort((a, b) => {
      if (a.entityName === UNASSIGNED) return 1;
      if (b.entityName === UNASSIGNED) return -1;
      return a.entityName.localeCompare(b.entityName);
    });
}

export const ByEntityView: React.FC<ByEntityViewProps> = ({
  summary,
  filter,
  setFilter,
  refreshingIds,
  refresh,
  failedCount,
}) => {
  const entities = useMemo(() => buildEntityView(summary, filter), [summary, filter]);

  return (
    <>
      <FilterChips
        filter={filter}
        setFilter={setFilter}
        staleCount={summary.staleCount}
        failedCount={failedCount}
        neverCount={summary.neverRefreshedCount}
      />

      {entities.length === 0 && (
        <div className="bg-surface-card rounded-lg border border-edge p-12 text-center text-neutral-500 dark:text-neutral-400">
          No shadows match the current filter.
        </div>
      )}

      <div className="space-y-4">
        {entities.map((entity) => (
          <div
            key={entity.entityName}
            className="rounded-lg shadow-sm border border-edge bg-surface-card"
          >
            <div className="p-5 border-b border-edge-subtle flex items-center justify-between gap-3 flex-wrap">
              <div className="flex items-center gap-3 min-w-0">
                <StatusIconBadge tone="neutral" icon={Building2} />
                <div>
                  <h2 className="section-title">{entity.entityName}</h2>
                  <p className="caption mt-0.5">
                    {entity.shadowCount} shadow{entity.shadowCount === 1 ? '' : 's'} · {entity.currencies.length} currenc{entity.currencies.length === 1 ? 'y' : 'ies'}
                  </p>
                </div>
              </div>
            </div>

            <div className="p-5">
              <DataTable
                hairline
                data={entity.currencies.flatMap((c) => c.shadows.map((s, i) => ({ s, c, i })))}
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
