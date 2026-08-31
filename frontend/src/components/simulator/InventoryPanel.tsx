import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Building2, Loader2, AlertCircle, Plus, Check, Clock } from 'lucide-react';
import { Card, Badge } from '../ui';
import { cn, formatCurrency } from '../../utils';
import { simulatorApi } from '../../services/simulatorApi';
import {
  classifyRelationship,
  groupByBank,
} from '../../utils/simulator/inventoryGrouping';
import type { SimulatorInventory, SimulatorPhysicalAccount } from './types';

// ============================================================================
// InventoryPanel — the ONLY self-fetching simulator view (multi-bank widget
// pattern). Lists the corporate's Physical Accounts grouped by bank, with
// filter chips. Read-only in commit 4; `onAdd` wires the AddShadowDrawer in
// commit 5.
// ============================================================================

type FilterKey =
  | 'ALL'
  | 'HOME'
  | 'GROUP'
  | 'EXTERNAL'
  | 'SWEEP'
  | 'CONSENT30';

const FILTERS: Array<{ key: FilterKey; label: string }> = [
  { key: 'ALL', label: 'All' },
  { key: 'HOME', label: 'Home bank' },
  { key: 'GROUP', label: 'Group' },
  { key: 'EXTERNAL', label: 'External' },
  { key: 'SWEEP', label: 'Sweep-eligible' },
  { key: 'CONSENT30', label: 'Consent < 30d' },
];

function consentDays(iso?: string): number | null {
  if (!iso) return null;
  const d = Math.ceil((new Date(iso).getTime() - Date.now()) / 86_400_000);
  return Number.isNaN(d) ? null : d;
}

export interface InventoryPanelProps {
  corporateId: string;
  /** Physical Account ids already represented in the scenario → "Added". */
  addedPhysicalAccountIds: Set<string>;
  /** Wires the AddShadowDrawer; absent = purely read-only. */
  onAdd?: (account: SimulatorPhysicalAccount) => void;
  /** Lifts the loaded accounts up (used for orphan validation). */
  onInventoryLoaded?: (accounts: SimulatorPhysicalAccount[]) => void;
  className?: string;
}

export const InventoryPanel: React.FC<InventoryPanelProps> = ({
  corporateId,
  addedPhysicalAccountIds,
  onAdd,
  onInventoryLoaded,
  className,
}) => {
  const [inventory, setInventory] = useState<SimulatorInventory | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState<FilterKey>('ALL');

  // Keep the latest callback without making it a fetch dependency.
  const onLoadedRef = useRef(onInventoryLoaded);
  useEffect(() => {
    onLoadedRef.current = onInventoryLoaded;
  }, [onInventoryLoaded]);

  useEffect(() => {
    let cancelled = false;
    if (!corporateId) {
      setInventory(null);
      return;
    }
    setLoading(true);
    setError(null);
    simulatorApi
      .getInventory(corporateId)
      .then((inv) => {
        if (!cancelled) {
          setInventory(inv);
          onLoadedRef.current?.(inv.physicalAccounts);
        }
      })
      .catch((err) => {
        console.error('Inventory load failed:', err);
        if (!cancelled) setError('Failed to load Physical Accounts.');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [corporateId]);

  const filtered = useMemo(() => {
    const all = inventory?.physicalAccounts ?? [];
    return all.filter((pa) => {
      switch (filter) {
        case 'HOME':
          return classifyRelationship(pa) === 'INTERNAL';
        case 'GROUP':
          return classifyRelationship(pa) === 'GROUP';
        case 'EXTERNAL':
          return classifyRelationship(pa) === 'EXTERNAL';
        case 'SWEEP':
          return pa.sweepEligible === true;
        case 'CONSENT30': {
          const d = consentDays(pa.consentExpiresAt);
          return d !== null && d < 30;
        }
        default:
          return true;
      }
    });
  }, [inventory, filter]);

  const groups = useMemo(() => groupByBank(filtered), [filtered]);
  const total = inventory?.physicalAccounts.length ?? 0;

  return (
    <Card padding="sm" className={className}>
      <div className="flex items-center justify-between gap-2">
        <h3 className="section-title">Available Physical Accounts</h3>
        <Badge variant="neutral" size="sm">
          {filtered.length}
          {filtered.length !== total ? ` / ${total}` : ''}
        </Badge>
      </div>

      <div className="mt-3 flex flex-wrap gap-1.5">
        {FILTERS.map((f) => (
          <button
            key={f.key}
            type="button"
            onClick={() => setFilter(f.key)}
            className={cn(
              'px-2.5 py-1 rounded-full text-xs font-medium transition-colors',
              'focus:outline-none focus-visible:ring-2 focus-visible:ring-primary-500',
              filter === f.key
                ? 'bg-primary-900 text-white dark:bg-primary-200 dark:text-primary-900'
                : 'bg-neutral-100 text-neutral-600 hover:bg-neutral-200 dark:bg-primary-800/60 dark:text-neutral-300 dark:hover:bg-primary-800',
            )}
          >
            {f.label}
          </button>
        ))}
      </div>

      <div className="mt-4">
        {loading ? (
          <div className="flex items-center justify-center gap-2 py-10 body-sm text-neutral-500 dark:text-neutral-400">
            <Loader2 className="w-4 h-4 animate-spin" />
            Loading inventory…
          </div>
        ) : error ? (
          <div className="flex items-center gap-2 px-3 py-2 rounded-lg border border-error-200 bg-error-50 dark:border-error-500/30 dark:bg-error-500/10 body-sm text-error-700 dark:text-error-300">
            <AlertCircle className="w-4 h-4 shrink-0" />
            {error}
          </div>
        ) : groups.length === 0 ? (
          <p className="py-10 text-center body-sm text-neutral-500 dark:text-neutral-400">
            No Physical Accounts match this filter.
          </p>
        ) : (
          <div className="space-y-4">
            {groups.map((g) => (
              <div key={g.bankCode}>
                <div className="flex items-center gap-2 mb-1">
                  <Building2
                    className="w-3.5 h-3.5 text-neutral-400 shrink-0"
                    aria-hidden
                  />
                  <span className="label">{g.bankName}</span>
                  <span className="code text-neutral-400">{g.bankCode}</span>
                </div>
                <div className="divide-y divide-neutral-100 dark:divide-primary-800/40">
                  {g.accounts.map((pa) => {
                    const added = addedPhysicalAccountIds.has(pa.id);
                    const d = consentDays(pa.consentExpiresAt);
                    const clickable = !added && Boolean(onAdd);
                    return (
                      <div
                        key={pa.id}
                        className={cn(
                          'flex items-center gap-3 py-2',
                          added && 'opacity-50',
                        )}
                      >
                        <div className="min-w-0 flex-1">
                          <div className="flex items-center gap-2 flex-wrap">
                            <span className="code">{pa.accountNumber}</span>
                            <span className="body-sm text-primary-900 dark:text-neutral-100 truncate">
                              {pa.accountName}
                            </span>
                          </div>
                          <div className="mt-0.5 flex items-center gap-2 flex-wrap body-sm text-neutral-500 dark:text-neutral-400">
                            <span>
                              {formatCurrency(
                                pa.currentBalance,
                                pa.currencyCode,
                              )}
                            </span>
                            {pa.dataSource && (
                              <Badge variant="neutral" size="xs">
                                {pa.dataSource}
                              </Badge>
                            )}
                            {pa.sweepEligible && (
                              <span className="inline-flex items-center gap-0.5 text-success-700 dark:text-success-300">
                                <Check className="w-3 h-3" aria-hidden />
                                sweep
                              </span>
                            )}
                            {d !== null && d < 30 && (
                              <Badge
                                variant={d < 0 ? 'error' : 'warning'}
                                size="xs"
                              >
                                <Clock className="w-3 h-3" aria-hidden />
                                {d < 0
                                  ? 'consent expired'
                                  : `consent ${d}d`}
                              </Badge>
                            )}
                          </div>
                        </div>
                        {added ? (
                          <Badge variant="neutral" size="xs">
                            Added
                          </Badge>
                        ) : clickable ? (
                          <button
                            type="button"
                            onClick={() => onAdd?.(pa)}
                            aria-label={`Add ${pa.accountName} to the structure`}
                            className={cn(
                              'inline-flex items-center gap-1 px-2 py-1 rounded-lg text-xs font-medium shrink-0',
                              'bg-primary-100 text-primary-800 hover:bg-primary-200',
                              'dark:bg-primary-800/60 dark:text-primary-200 dark:hover:bg-primary-800',
                              'focus:outline-none focus-visible:ring-2 focus-visible:ring-primary-500',
                            )}
                          >
                            <Plus className="w-3 h-3" aria-hidden />
                            Add
                          </button>
                        ) : null}
                      </div>
                    );
                  })}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </Card>
  );
};
