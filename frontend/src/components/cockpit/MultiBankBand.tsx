import React, { useEffect, useState } from 'react';
import { ArrowRight } from 'lucide-react';
import { Card, Skeleton } from '../ui';
import { multiBankLiquidityApi, MultiBankLiquiditySummary } from '../../services/api';
import { OverviewView } from '../multiBank/OverviewView';
import { FilterKey } from '../multiBank/types';

// ============================================================================
// Cockpit — Multi-Bank band.
//
// Wraps the multi-bank `OverviewView` in `compact` mode so the same component
// powers both the standalone /treasury/multi-bank page and the dashboard
// band — single source of truth for the per-currency / distribution view.
//
// Self-fetches its own summary so the cockpit page coordinator can stay
// lean. Failure renders a quiet empty band; never blocks the rest of the
// page.
// ============================================================================

interface MultiBankBandProps {
  /** Called when the user clicks "Open full page →". */
  onOpenFull: () => void;
  /**
   * Active corporate scope from the cockpit picker. When set, the band's
   * multi-bank summary is filtered to that corporate; empty/undefined =
   * all corporates. Changing it re-fetches (the band previously self-fetched
   * once on mount and ignored the picker — Phase 11 defect fix 2026-05-17).
   */
  corporateId?: string;
}

export const MultiBankBand: React.FC<MultiBankBandProps> = ({ onOpenFull, corporateId }) => {
  const [summary, setSummary] = useState<MultiBankLiquiditySummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [errored, setErrored] = useState(false);
  // Local filter — the band's own toggle, not URL-persisted (the dashboard
  // URL is reserved for the inbox filter).
  const [filter, setFilter] = useState<FilterKey>('all');

  useEffect(() => {
    let alive = true;
    // Reset to the loading state on (re)fetch so a corporate-scope change
    // shows the skeleton and clears any prior error rather than leaving
    // stale figures on screen.
    setLoading(true);
    setErrored(false);
    (async () => {
      try {
        const res = await multiBankLiquidityApi.getSummary(corporateId || undefined);
        if (!alive) return;
        if (res.success && res.data) setSummary(res.data);
        else setErrored(true);
      } catch {
        if (alive) setErrored(true);
      } finally {
        if (alive) setLoading(false);
      }
    })();
    return () => { alive = false; };
  }, [corporateId]);

  // failedCount derived inline from the summary (the standalone page
  // computes the same number).
  const failedCount = React.useMemo(() => {
    if (!summary) return 0;
    let n = 0;
    for (const b of summary.banks)
      for (const c of b.currencies)
        for (const s of c.shadows)
          if (s.lastBalanceRefreshStatus === 'FAILED') n++;
    return n;
  }, [summary]);

  return (
    <Card padding="md">
      <div className="flex items-center justify-between mb-3 flex-wrap gap-2">
        <p className="label">Multi-bank liquidity</p>
        <button
          onClick={onOpenFull}
          className="inline-flex items-center gap-1 body-sm text-info-600 hover:text-info-700 dark:text-info-300 dark:hover:text-info-200"
        >
          Open full page <ArrowRight className="w-3.5 h-3.5" />
        </button>
      </div>

      {loading ? (
        <div className="space-y-3">
          <Skeleton className="h-12 rounded-lg" />
          <Skeleton className="h-16 rounded-lg" />
          <Skeleton className="h-24 rounded-lg" />
        </div>
      ) : errored || !summary ? (
        <p className="body-sm text-neutral-500 dark:text-neutral-400 py-2">
          Multi-bank summary unavailable. Open the full page to retry.
        </p>
      ) : (
        // OverviewView in compact mode renders without nested Cards, drops
        // the freshness banner / filter chip row, and caps per-currency
        // cards at top-4. setView is omitted (the band has no view-switching
        // surface — the eye goes to the "Open full page" link instead).
        <div className="space-y-4">
          <OverviewView
            summary={summary}
            filter={filter}
            setFilter={setFilter}
            failedCount={failedCount}
            compact
          />
        </div>
      )}
    </Card>
  );
};
