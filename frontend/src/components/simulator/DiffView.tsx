import React from 'react';
import { Loader2, RefreshCw, ShieldCheck } from 'lucide-react';
import { Card, Button } from '../ui';
import { cn } from '../../utils';
import { DiffSummaryStrip } from './DiffSummaryStrip';
import { DiffRow } from './DiffRow';
import type { DiffResult } from './types';

// ============================================================================
// DiffView — the Diff-vs-live surface. Pure view over a DiffResult (computed
// by the pure diffEngine upstream). Snapshot is frozen on first capture;
// "Refresh snapshot" re-takes it.
// ============================================================================

export interface DiffViewProps {
  result: DiffResult | null;
  loading?: boolean;
  refreshing?: boolean;
  onRefreshSnapshot?: () => void;
  className?: string;
}

export const DiffView: React.FC<DiffViewProps> = ({
  result,
  loading,
  refreshing,
  onRefreshSnapshot,
  className,
}) => {
  if (loading) {
    return (
      <Card padding="lg" className={className}>
        <div className="flex items-center justify-center gap-2 py-12 body-sm text-neutral-500 dark:text-neutral-400">
          <Loader2 className="w-4 h-4 animate-spin" />
          Capturing the live-config baseline…
        </div>
      </Card>
    );
  }

  if (!result) {
    return (
      <Card padding="lg" className={className}>
        <p className="body-sm text-neutral-500 dark:text-neutral-400 text-center">
          No diff yet — the live-config baseline will be captured when you open
          this view.
        </p>
      </Card>
    );
  }

  const changed =
    result.counts.add + result.counts.modify + result.counts.retire;

  return (
    <div className={cn('space-y-4', className)}>
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <p className="body-sm text-neutral-500 dark:text-neutral-400">
          Baseline frozen{' '}
          {result.snapshotTakenAt
            ? new Date(result.snapshotTakenAt).toLocaleString()
            : '—'}{' '}
          · {changed} change{changed === 1 ? '' : 's'}
        </p>
        {onRefreshSnapshot && (
          <Button
            variant="secondary"
            size="sm"
            leftIcon={
              <RefreshCw
                className={cn('w-4 h-4', refreshing && 'animate-spin')}
              />
            }
            onClick={onRefreshSnapshot}
            disabled={refreshing}
          >
            Refresh snapshot
          </Button>
        )}
      </div>

      <DiffSummaryStrip counts={result.counts} />

      <Card padding="sm">
        {result.entries.length === 0 ? (
          <p className="body-sm text-neutral-500 dark:text-neutral-400 text-center py-8">
            The proposed structure is identical to the live baseline.
          </p>
        ) : (
          <div className="divide-y divide-neutral-100 dark:divide-primary-800/40">
            {result.entries.map((e, i) => (
              <DiffRow key={`${e.entity}-${e.op}-${i}`} entry={e} />
            ))}
          </div>
        )}
      </Card>

      <div className="flex items-start gap-2 px-4 py-3 rounded-xl border border-info-200 bg-info-50 dark:border-info-500/30 dark:bg-info-500/10">
        <ShieldCheck className="w-4 h-4 mt-0.5 shrink-0 text-info-600 dark:text-info-300" />
        <p className="body-sm text-info-800 dark:text-info-200">
          Activation writes to the live virtual_accounts and sweep_rules and
          is fully recorded to the audit trail with a link back to this
          scenario.
        </p>
      </div>
    </div>
  );
};
