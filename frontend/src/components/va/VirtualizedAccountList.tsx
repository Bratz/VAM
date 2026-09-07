// ============================================================================
// VirtualizedAccountList — reusable large-list renderer for three call sites
// (a) reviewing a scope/CSV-resolved candidate set before bulk enrollment
//     (checkboxes, pre-checked by default, "N of M selected", can uncheck),
// (b) read-only viewing of an existing pool's members,
// (c) read-only viewing of an existing sweep rule's sources.
//
// Selection-UX contract mirrors DataTable's checkbox pattern (selectable/
// selectedIds/onSelectionChange/select-all header + "N selected" summary),
// but the row rendering itself is virtualized via react-window's
// FixedSizeList so it stays smooth at thousands of rows.
// ============================================================================

import React, { useMemo, useState } from 'react';
import { FixedSizeList, type ListChildComponentProps } from 'react-window';
import { Search, Check, Inbox } from 'lucide-react';
import { cn } from '../../utils';

export interface VirtualizedAccountRow {
  id: string;
  vaNumber: string;
  vaName: string;
  currencyCode: string;
  balance?: number;
}

interface VirtualizedAccountListProps {
  items: VirtualizedAccountRow[];
  selectable?: boolean;
  selectedIds?: Set<string>;
  onSelectionChange?: (ids: Set<string>) => void;
  searchable?: boolean;
  height?: number;
  rowHeight?: number;
}

const formatBalance = (balance: number | undefined, currencyCode: string) => {
  if (balance === undefined || balance === null) return '-';
  try {
    return new Intl.NumberFormat('en-US', { style: 'currency', currency: currencyCode || 'USD' }).format(balance);
  } catch {
    return `${balance.toLocaleString()} ${currencyCode || ''}`.trim();
  }
};

export const VirtualizedAccountList: React.FC<VirtualizedAccountListProps> = ({
  items,
  selectable = false,
  selectedIds = new Set(),
  onSelectionChange,
  searchable = true,
  height = 400,
  rowHeight = 52,
}) => {
  const [query, setQuery] = useState('');

  const filtered = useMemo(() => {
    if (!query.trim()) return items;
    const q = query.trim().toLowerCase();
    return items.filter(it =>
      it.vaNumber?.toLowerCase().includes(q) || it.vaName?.toLowerCase().includes(q)
    );
  }, [items, query]);

  const allFilteredSelected = filtered.length > 0 && filtered.every(it => selectedIds.has(it.id));

  const toggleOne = (id: string) => {
    if (!onSelectionChange) return;
    const next = new Set(selectedIds);
    if (next.has(id)) next.delete(id); else next.add(id);
    onSelectionChange(next);
  };

  const toggleAll = () => {
    if (!onSelectionChange) return;
    if (allFilteredSelected) {
      const next = new Set(selectedIds);
      filtered.forEach(it => next.delete(it.id));
      onSelectionChange(next);
    } else {
      const next = new Set(selectedIds);
      filtered.forEach(it => next.add(it.id));
      onSelectionChange(next);
    }
  };

  const Row = ({ index, style }: ListChildComponentProps) => {
    const item = filtered[index];
    const isSelected = selectedIds.has(item.id);
    return (
      <div
        style={style}
        className={cn(
          'flex items-center gap-3 px-3 border-b border-neutral-100 dark:border-primary-800/60 text-sm',
          selectable && 'cursor-pointer',
          isSelected ? 'bg-primary-50 dark:bg-primary-800/40' : 'hover:bg-neutral-50 dark:hover:bg-primary-900/60'
        )}
        onClick={selectable ? () => toggleOne(item.id) : undefined}
      >
        {selectable && (
          <button
            onClick={(e) => { e.stopPropagation(); toggleOne(item.id); }}
            className={cn(
              'w-5 h-5 rounded border-2 flex items-center justify-center shrink-0 transition-all duration-200',
              isSelected
                ? 'bg-primary-600 border-primary-600'
                : 'border-neutral-300 hover:border-primary-400 dark:border-primary-700'
            )}
          >
            {isSelected && <Check className="w-3 h-3 text-white" />}
          </button>
        )}
        <span className="font-mono text-xs w-32 shrink-0 truncate text-neutral-700 dark:text-neutral-200">{item.vaNumber}</span>
        <span className="flex-1 min-w-0 truncate text-primary-900 dark:text-neutral-50">{item.vaName}</span>
        <span className="w-14 shrink-0 text-xs text-neutral-500 dark:text-neutral-400">{item.currencyCode}</span>
        <span className="w-28 shrink-0 text-right text-neutral-700 dark:text-neutral-200 tabular-nums">
          {formatBalance(item.balance, item.currencyCode)}
        </span>
      </div>
    );
  };

  return (
    <div className="border border-neutral-200 dark:border-primary-800 rounded-lg overflow-hidden bg-white dark:bg-primary-900">
      <div className="flex items-center justify-between gap-3 px-3 py-2 border-b border-neutral-200 dark:border-primary-800 bg-neutral-50/80 dark:bg-primary-950/40">
        <div className="flex items-center gap-3 min-w-0">
          {selectable && (
            <button
              onClick={toggleAll}
              className={cn(
                'w-5 h-5 rounded border-2 flex items-center justify-center shrink-0 transition-all duration-200',
                allFilteredSelected
                  ? 'bg-primary-600 border-primary-600'
                  : 'border-neutral-300 hover:border-primary-400 dark:border-primary-700'
              )}
              title={allFilteredSelected ? 'Deselect all' : 'Select all'}
            >
              {allFilteredSelected && <Check className="w-3 h-3 text-white" />}
            </button>
          )}
          <span className="text-xs font-medium text-neutral-500 dark:text-neutral-400 whitespace-nowrap">
            {selectable ? `${selectedIds.size} of ${items.length} selected` : `${filtered.length} account${filtered.length === 1 ? '' : 's'}`}
          </span>
        </div>
        {searchable && (
          <div className="relative w-56 shrink-0">
            <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-neutral-400" />
            <input
              type="text"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search VA number or name…"
              className="w-full h-8 pl-8 pr-3 rounded-lg border border-neutral-200 dark:border-primary-800 bg-white dark:bg-primary-900 text-xs placeholder:text-neutral-400 focus:outline-none focus:ring-2 focus:ring-primary-500/10 focus:border-primary-300"
            />
          </div>
        )}
      </div>

      {filtered.length === 0 ? (
        <div className="flex flex-col items-center justify-center gap-2 text-neutral-400 dark:text-neutral-500" style={{ height }}>
          <Inbox className="w-8 h-8" />
          <p className="text-sm">No accounts to display</p>
        </div>
      ) : (
        <FixedSizeList
          height={height}
          itemCount={filtered.length}
          itemSize={rowHeight}
          width="100%"
        >
          {Row}
        </FixedSizeList>
      )}
    </div>
  );
};

export default VirtualizedAccountList;
