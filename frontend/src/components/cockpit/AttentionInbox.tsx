import React, { useMemo } from 'react';
import { AlertTriangle, RefreshCw } from 'lucide-react';
import { cn } from '../../utils';
import { Card, Skeleton, StatusIconBadge, Button } from '../ui';
import { AttentionItem, AttentionAction } from '../../types/cockpit';
import { AttentionRow } from './AttentionRow';
import { InboxFilterKey } from './inboxFilter';

// ============================================================================
// Cockpit — attention inbox.
//
// The dominant above-the-fold element. Header + filter chips + list of
// AttentionRow components, sorted by severity then time pressure (the
// orchestrator already does this server-side via cockpitApi).
//
// Filter chips are URL-persisted at the page level via `?filter=…` (see
// CockpitPage). The chip set is six fixed values: All / Critical / High /
// Medium are severity filters; By bank / By entity / By currency are
// grouping modes.
//
// `InboxFilterKey`, `VALID_INBOX_FILTERS`, and `parseInboxFilter` live in
// `inboxFilter.ts` so this file can export only the component.
// ============================================================================

interface AttentionInboxProps {
  items: AttentionItem[];
  loading?: boolean;
  filter: InboxFilterKey;
  setFilter: (f: InboxFilterKey) => void;
  /** Last critical resolution copy for the empty state. */
  lastCriticalResolved?: { time: string; userName: string };
  onOpenItem: (item: AttentionItem) => void;
  onAction: (item: AttentionItem, action: AttentionAction) => void;
  /** Snoozed items — displayed as a collapsed footer row. */
  snoozedCount: number;
  onShowSnoozed?: () => void;
}

const SEVERITY_FILTERS: ReadonlySet<InboxFilterKey> = new Set(['critical', 'high', 'medium']);
const GROUPING_FILTERS: ReadonlySet<InboxFilterKey> = new Set(['by-bank', 'by-entity', 'by-currency']);

function groupKey(item: AttentionItem, filter: InboxFilterKey): string {
  if (filter === 'by-bank')     return item.context.bankName ?? item.context.bankBic ?? 'Unscoped';
  if (filter === 'by-entity')   return item.context.entityName ?? item.context.entityId ?? 'Unscoped';
  if (filter === 'by-currency') return item.context.currencyCode ?? 'Unscoped';
  return '__all__';
}

export const AttentionInbox: React.FC<AttentionInboxProps> = ({
  items,
  loading,
  filter,
  setFilter,
  lastCriticalResolved,
  onOpenItem,
  onAction,
  snoozedCount,
  onShowSnoozed,
}) => {
  // Apply severity filter, leave grouping for the render branch.
  const filteredItems = useMemo(() => {
    if (!SEVERITY_FILTERS.has(filter)) return items;
    return items.filter((i) => i.severity === filter);
  }, [items, filter]);

  // Group rows when a grouping filter is active.
  const grouped = useMemo(() => {
    if (!GROUPING_FILTERS.has(filter)) return null;
    const map = new Map<string, AttentionItem[]>();
    for (const item of filteredItems) {
      const key = groupKey(item, filter);
      const arr = map.get(key) ?? [];
      arr.push(item);
      map.set(key, arr);
    }
    return Array.from(map.entries()).sort((a, b) => a[0].localeCompare(b[0]));
  }, [filteredItems, filter]);

  // Counts for the right-aligned summary.
  const criticalCount = items.filter((i) => i.severity === 'critical').length;
  const summaryText = items.length === 0
    ? '0 items'
    : `${items.length} item${items.length === 1 ? '' : 's'}${criticalCount > 0 ? ` · ${criticalCount} critical` : ''}`;

  return (
    <Card padding="md">
      {/* Header */}
      <div className="flex items-center gap-3 mb-4 flex-wrap">
        <StatusIconBadge tone={criticalCount > 0 ? 'error' : 'warning'} icon={AlertTriangle} />
        <h2 className="section-title flex-1 min-w-0">Needs your attention today</h2>
        <span className="body-sm text-neutral-500 dark:text-neutral-400">{summaryText}</span>
      </div>

      {/* Filter chips */}
      <div className="flex flex-wrap gap-2 mb-4">
        {([
          { key: 'all',         label: 'All' },
          { key: 'critical',    label: 'Critical', count: items.filter((i) => i.severity === 'critical').length },
          { key: 'high',        label: 'High',     count: items.filter((i) => i.severity === 'high').length },
          { key: 'medium',      label: 'Medium',   count: items.filter((i) => i.severity === 'medium').length },
          { key: 'by-bank',     label: 'By bank' },
          { key: 'by-entity',   label: 'By entity' },
          { key: 'by-currency', label: 'By currency' },
        ] as { key: InboxFilterKey; label: string; count?: number }[]).map(({ key, label, count }) => (
          <button
            key={key}
            type="button"
            onClick={() => setFilter(key)}
            aria-current={filter === key ? 'true' : undefined}
            className={cn(
              'px-3 py-1 rounded-full text-xs font-medium transition-colors border focus:outline-none focus-visible:ring-2 focus-visible:ring-primary-400 dark:focus-visible:ring-accent-400',
              filter === key
                ? 'bg-primary-600 text-white border-primary-600 dark:bg-accent-500 dark:text-primary-950 dark:border-accent-500'
                : 'bg-white text-neutral-700 border-neutral-200 hover:bg-neutral-100 dark:bg-primary-900 dark:text-neutral-300 dark:border-primary-800 dark:hover:bg-primary-800',
            )}
          >
            {label}{typeof count === 'number' ? ` · ${count}` : ''}
          </button>
        ))}
      </div>

      {/* Body */}
      {loading ? (
        <div className="space-y-2">
          {Array.from({ length: 3 }).map((_, i) => (
            <Skeleton key={i} className="h-16 rounded-lg" />
          ))}
        </div>
      ) : filteredItems.length === 0 ? (
        <p className="body-sm text-neutral-500 dark:text-neutral-400 py-4">
          {lastCriticalResolved
            ? `No attention items. Last critical resolved ${lastCriticalResolved.time} by ${lastCriticalResolved.userName}.`
            : 'No attention items.'}
        </p>
      ) : grouped ? (
        <div className="space-y-4">
          {grouped.map(([key, rows]) => (
            <div key={key}>
              <p className="label mb-2">{key} · {rows.length}</p>
              <div className="space-y-2">
                {rows.map((row) => (
                  <AttentionRow key={row.id} item={row} onOpen={onOpenItem} onAction={onAction} />
                ))}
              </div>
            </div>
          ))}
        </div>
      ) : (
        <div className="space-y-2">
          {filteredItems.map((row) => (
            <AttentionRow key={row.id} item={row} onOpen={onOpenItem} onAction={onAction} />
          ))}
        </div>
      )}

      {/* Snoozed footer */}
      {snoozedCount > 0 && (
        <div className="flex items-center justify-between mt-4 pt-3 border-t border-neutral-100 dark:border-primary-800/60">
          <p className="body-sm text-neutral-500 dark:text-neutral-400">
            <RefreshCw className="w-3.5 h-3.5 inline mr-1.5" />
            {snoozedCount} snoozed until end of day
          </p>
          {onShowSnoozed && (
            <Button variant="ghost" size="sm" onClick={onShowSnoozed}>Show</Button>
          )}
        </div>
      )}
    </Card>
  );
};
