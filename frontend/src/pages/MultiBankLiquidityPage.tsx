import React, { useCallback, useEffect, useMemo, useState } from 'react';
import toast from 'react-hot-toast';
import { RefreshCw } from 'lucide-react';
import { cn } from '../utils';
import {
  multiBankLiquidityApi,
  MultiBankLiquiditySummary,
  ShadowSummary,
  corporatesApi,
  Corporate,
} from '../services/api';
import { usePageHeaderActions } from '../context/PageHeaderContext';
import { Page } from '../components/layout/Page';
import { PageHeader } from '../components/layout/PageHeader';
import { ScopeSelector } from '../components/layout/ScopeSelector';
import { ByBankView, FilterKey } from '../components/multiBank/ByBankView';
import { OverviewView } from '../components/multiBank/OverviewView';
import { ByCurrencyView } from '../components/multiBank/ByCurrencyView';
import { ViewSwitcher } from '../components/multiBank/ViewSwitcher';
import { ViewKey, parseView } from '../components/multiBank/types';

// ============================================================================
// Multi-Bank Liquidity Dashboard — page controller.
//
// MVC discipline (per multi-bank-redesign.md):
//   - This file owns state (`summary`, `loading`, `refreshingIds`,
//     `bulkRefreshing`, `filter`), side effects (`load`, `refresh`,
//     `bulkRefreshStale`), and the toolbar via `usePageHeaderActions`.
//   - View components in components/multiBank/ make zero network calls and
//     receive everything via props.
//
// Pool-eligible shadows (home-bank-held PHYSICAL_MIRRORs) are tagged so the
// treasurer can see at a glance which liquidity is available to the pool
// vs which sits at an external bank awaiting a sweep.
//
// Renders Overview (default) / By Bank / By Currency. The corporate
// ScopeSelector and the ViewSwitcher (content pivot) live in the page body
// near the content they control; the header toolbar holds only true actions
// (Refresh stale / Reload view). The summary is fetched scoped to the
// selected corporate (empty = all corporates, portfolio-wide).
// ============================================================================

// How many shadow balance refreshes to fan out in parallel during a bulk run.
// Banks rate-limit differently — tune here.
const BULK_REFRESH_CONCURRENCY = 4;

// Concurrency-capped fanout — N workers pull from a shared cursor. Avoids
// pulling in p-queue / async-pool for a small helper.
async function pQueue<T>(
  items: T[],
  cap: number,
  worker: (item: T) => Promise<void>,
): Promise<void> {
  let cursor = 0;
  const runOne = async () => {
    while (cursor < items.length) {
      const idx = cursor++;
      await worker(items[idx]);
    }
  };
  await Promise.all(
    Array.from({ length: Math.min(cap, items.length) }, () => runOne()),
  );
}

const VALID_FILTERS = ['all', 'home', 'external', 'stale', 'failed', 'never'] as const;
const SHADOW_LEVEL_FILTERS: ReadonlySet<FilterKey> = new Set(['stale', 'failed', 'never']);

const parseFilter = (s: string | null): FilterKey =>
  (VALID_FILTERS as readonly string[]).includes(s ?? '') ? (s as FilterKey) : 'all';

const MultiBankLiquidityPage: React.FC = () => {
  const [summary, setSummary] = useState<MultiBankLiquiditySummary | null>(null);
  const [loading, setLoading] = useState(true);
  // Concurrent per-row refreshes need a Set, not a single id, or the second
  // click silently un-spins the first.
  const [refreshingIds, setRefreshingIds] = useState<Set<string>>(new Set());
  const [bulkRefreshing, setBulkRefreshing] = useState(false);
  // Filter survives reload via ?filter=… search param.
  const [filter, setFilter] = useState<FilterKey>(() =>
    parseFilter(new URLSearchParams(window.location.search).get('filter'))
  );
  // Active view (Overview / By Bank / By Currency) — defaults to Overview and
  // survives reload via ?view=… alongside ?filter=… Old bookmarks without
  // ?view= land on Overview (the new default).
  const [view, setView] = useState<ViewKey>(() =>
    parseView(new URLSearchParams(window.location.search).get('view'))
  );
  // Corporate scope. Empty string = all corporates (portfolio-wide). The
  // multi-bank summary API filters server-side via ?corporateId=, so the
  // views need no change — they re-render off the re-fetched, scoped summary.
  const [corporates, setCorporates] = useState<Corporate[]>([]);
  const [selectedCorporateId, setSelectedCorporateId] = useState('');
  const [loadingCorporates, setLoadingCorporates] = useState(false);

  useEffect(() => {
    const u = new URL(window.location.href);
    if (filter === 'all') u.searchParams.delete('filter');
    else u.searchParams.set('filter', filter);
    window.history.replaceState(null, '', u);
  }, [filter]);

  useEffect(() => {
    const u = new URL(window.location.href);
    if (view === 'overview') u.searchParams.delete('view');
    else u.searchParams.set('view', view);
    window.history.replaceState(null, '', u);
  }, [view]);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await multiBankLiquidityApi.getSummary(selectedCorporateId || undefined);
      if (res.success && res.data) {
        setSummary(res.data);
      } else {
        toast.error('Failed to load liquidity summary');
      }
    } catch (e: any) {
      toast.error(e?.message || 'Failed to load liquidity summary');
    } finally {
      setLoading(false);
    }
  }, [selectedCorporateId]);

  useEffect(() => { load(); }, [load]);

  // Corporate list for the scope picker — optional chrome; if it fails the
  // page still works (defaults to all-corporates / portfolio-wide).
  useEffect(() => {
    let alive = true;
    setLoadingCorporates(true);
    corporatesApi.getAll()
      .then((res) => {
        if (!alive) return;
        const data: any = (res as any)?.data ?? res;
        const list: Corporate[] = Array.isArray(data)
          ? data
          : Array.isArray(data?.content) ? data.content : [];
        setCorporates(list);
      })
      .catch(() => { /* picker is optional — silently degrade */ })
      .finally(() => { if (alive) setLoadingCorporates(false); });
    return () => { alive = false; };
  }, []);

  const refresh = useCallback(async (shadow: ShadowSummary) => {
    setRefreshingIds(prev => {
      const next = new Set(prev);
      next.add(shadow.vaId);
      return next;
    });
    try {
      const res = await multiBankLiquidityApi.refresh(shadow.vaId);
      if (res.success) {
        toast.success(`Refreshed ${shadow.vaNumber} → ${res.data}`);
        await load();
      } else {
        toast.error(`Refresh failed for ${shadow.vaNumber}`);
      }
    } catch (e: any) {
      toast.error(e?.message || 'Refresh failed');
    } finally {
      setRefreshingIds(prev => {
        const next = new Set(prev);
        next.delete(shadow.vaId);
        return next;
      });
    }
  }, [load]);

  // Bulk fetch all stale/failed/never shadows with concurrency cap.
  // Tracks ok/fail counts and surfaces failures in the final toast.
  const bulkRefreshStale = useCallback(async () => {
    if (!summary || bulkRefreshing) return;
    const targets: ShadowSummary[] = [];
    for (const b of summary.banks) {
      for (const c of b.currencies) {
        for (const s of c.shadows) {
          if (s.stale || s.lastBalanceRefreshStatus !== 'SUCCESS') targets.push(s);
        }
      }
    }
    if (targets.length === 0) return;
    setBulkRefreshing(true);
    const toastId = 'bulk-refresh';
    let ok = 0;
    let fail = 0;
    toast.loading(`Refreshing 0 / ${targets.length}…`, { id: toastId });
    await pQueue(targets, BULK_REFRESH_CONCURRENCY, async (s) => {
      try {
        await multiBankLiquidityApi.refreshIfStale(s.vaId);
        ok += 1;
      } catch {
        fail += 1;
      }
      toast.loading(`Refreshing ${ok + fail} / ${targets.length}…`, { id: toastId });
    });
    if (fail === 0) {
      toast.success(`Refreshed ${ok} shadow${ok === 1 ? '' : 's'}`, { id: toastId });
    } else {
      toast.error(`Refreshed ${ok} · ${fail} failed`, { id: toastId });
    }
    setBulkRefreshing(false);
    // After a bulk run, a shadow-level filter is likely to point at an empty
    // set (the whole point was to clear it). Reset to All so the user sees
    // the new state.
    if (SHADOW_LEVEL_FILTERS.has(filter)) setFilter('all');
    await load();
  }, [summary, bulkRefreshing, load, filter]);

  // Failed count isn't in the summary; compute once for the chip badge.
  const failedCount = useMemo(() => {
    if (!summary) return 0;
    let n = 0;
    for (const b of summary.banks) {
      for (const c of b.currencies) {
        for (const s of c.shadows) {
          if (s.lastBalanceRefreshStatus === 'FAILED') n++;
        }
      }
    }
    return n;
  }, [summary]);

  const hasStaleWork = !!summary && (summary.staleCount + summary.neverRefreshedCount + failedCount) > 0;

  // Toolbar — Refresh stale is the primary action whenever there's stale
  // work; Reload view is a secondary UI re-read.
  usePageHeaderActions(
    () => (
      <div className="flex items-center gap-2">
        <button
          onClick={bulkRefreshStale}
          disabled={!hasStaleWork || bulkRefreshing}
          // Primary CTA — stable across modes, matching the active sidebar
          // nav item's fill (primary-900). primary-600 was the "branded
          // hue" choice; primary-900 is the system's canonical
          // primary-action / selected-state navy. No dark variant — the
          // navy reads the same in both themes. Spin icon-only during run;
          // the label stays put.
          className="px-3 py-1.5 text-sm bg-primary-900 hover:bg-primary-800 text-white rounded-lg transition-colors flex items-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
          title={hasStaleWork ? 'Refetch all stale / failed / never-refreshed shadow balances' : 'Nothing to refresh'}
        >
          <RefreshCw className={cn('w-4 h-4', bulkRefreshing && 'animate-spin')} />
          Refresh stale
        </button>
        <button
          onClick={load}
          disabled={loading}
          className="px-3 py-1.5 text-sm bg-transparent border border-neutral-300 dark:border-primary-700 text-neutral-700 dark:text-neutral-200 hover:bg-neutral-100 dark:hover:bg-primary-800 rounded-lg transition-colors flex items-center gap-2 disabled:opacity-50"
        >
          <RefreshCw className={cn('w-4 h-4', loading && 'animate-spin')} />
          Reload view
        </button>
      </div>
    ),
    [load, loading, bulkRefreshStale, bulkRefreshing, hasStaleWork]
  );

  if (loading && !summary) {
    // Skeleton with the right shape so the page doesn't pop on first paint.
    return (
      <Page>
        <div>
          <div className="h-3 w-20 bg-neutral-200 dark:bg-primary-800 rounded animate-pulse" />
          <div className="h-4 w-64 bg-neutral-200 dark:bg-primary-800 rounded mt-2 animate-pulse" />
        </div>
        <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-5 gap-4">
          {Array.from({ length: 5 }).map((_, i) => (
            <div key={i} className="bg-white dark:bg-primary-900 rounded-lg p-5 border border-neutral-200 dark:border-primary-800 h-32 animate-pulse" />
          ))}
        </div>
        <div className="space-y-4">
          {Array.from({ length: 2 }).map((_, i) => (
            <div key={i} className="bg-white dark:bg-primary-900 rounded-lg border border-neutral-200 dark:border-primary-800 h-48 animate-pulse" />
          ))}
        </div>
      </Page>
    );
  }

  if (!summary) {
    return <div className="p-6 text-neutral-500 dark:text-neutral-400">No liquidity data available.</div>;
  }

  return (
    <Page>
      {/* The static "Home bank: <name> <BIC>" description line was removed:
          it asserted a single deployment-wide home bank that lied whenever
          the selected corporate held no accounts there, and its name came
          from a decoupled config string. The home bank is now conveyed only
          where it's truthful — flagged and sorted-first within the bank list
          itself, per scope (backend now resolves the name from data by BIC). */}
      <PageHeader title="Multi-Bank Liquidity" />

      {/* Corporate scope. Empty = all corporates (portfolio-wide). Server
          filters via ?corporateId=; the views re-render off the scoped
          summary with no per-view change. */}
      <ScopeSelector
        mode="corporate-only"
        corporates={corporates}
        selectedCorporateId={selectedCorporateId}
        onCorporateChange={setSelectedCorporateId}
        loading={loadingCorporates}
      />

      {/* View pivot — relocated out of the global header toolbar so the
          control that changes *what you see* sits with the content it
          pivots (just above the per-view filter chips), not in the app
          chrome beside the page title. The header keeps only true actions
          (Refresh stale / Reload view). */}
      <ViewSwitcher value={view} onChange={setView} />

      {view === 'overview' && (
        <OverviewView
          summary={summary}
          filter={filter}
          setFilter={setFilter}
          setView={setView}
          failedCount={failedCount}
          onBulkRefreshStale={bulkRefreshStale}
          bulkRefreshing={bulkRefreshing}
        />
      )}
      {view === 'by-bank' && (
        <ByBankView
          summary={summary}
          filter={filter}
          setFilter={setFilter}
          refreshingIds={refreshingIds}
          refresh={refresh}
          failedCount={failedCount}
        />
      )}
      {view === 'by-currency' && (
        <ByCurrencyView
          summary={summary}
          filter={filter}
          setFilter={setFilter}
          refreshingIds={refreshingIds}
          refresh={refresh}
          failedCount={failedCount}
        />
      )}
    </Page>
  );
};

export default MultiBankLiquidityPage;
