import React from 'react';
import { cn } from '../../utils';
import { FilterKey } from './types';

// ============================================================================
// Multi-Bank Liquidity — shared sticky filter-chip row.
//
// Identical markup used to live copy-pasted in ByBankView and ByCurrencyView;
// extracted once a third and fourth view (By Entity, By Country) needed the
// same row rather than a third/fourth copy.
// ============================================================================

interface FilterChipsProps {
  filter: FilterKey;
  setFilter: (f: FilterKey) => void;
  staleCount: number;
  failedCount: number;
  neverCount: number;
}

export const FilterChips: React.FC<FilterChipsProps> = ({ filter, setFilter, staleCount, failedCount, neverCount }) => (
  <div className="sticky top-16 z-20 py-2 -mt-2 bg-white/80 backdrop-blur-xl dark:bg-primary-900/80 supports-[backdrop-filter]:bg-white/60 dark:supports-[backdrop-filter]:bg-primary-900/60">
    <div className="flex flex-wrap gap-2">
      {([
        { key: 'all',      label: 'All' },
        { key: 'home',     label: 'Home-bank' },
        { key: 'external', label: 'External' },
        { key: 'stale',    label: 'Stale',           count: staleCount },
        { key: 'failed',   label: 'Failed',          count: failedCount },
        { key: 'never',    label: 'Never refreshed', count: neverCount },
      ] as { key: FilterKey; label: string; count?: number }[]).map(({ key, label, count }) => (
        <button
          key={key}
          type="button"
          onClick={() => setFilter(key)}
          aria-current={filter === key ? 'true' : undefined}
          className={cn(
            'px-3 py-1 rounded-full text-caption font-medium transition-colors border focus:outline-none focus-visible:ring-2 focus-visible:ring-primary-400 dark:focus-visible:ring-accent-400',
            filter === key
              ? 'bg-primary-900 text-white border-primary-900 dark:bg-accent-500 dark:text-white dark:border-accent-500'
              : 'bg-surface-card text-neutral-700 border-edge hover:bg-neutral-100 dark:text-neutral-300 dark:hover:bg-primary-800',
          )}
        >
          {label}{typeof count === 'number' ? ` · ${count}` : ''}
        </button>
      ))}
    </div>
  </div>
);
