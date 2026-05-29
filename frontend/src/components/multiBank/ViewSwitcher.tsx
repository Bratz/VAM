import React from 'react';
import { cn } from '../../utils';
import { ViewKey } from './types';

// ============================================================================
// Multi-Bank Liquidity — view switcher.
//
// Segmented control offering three options (Overview / By Bank / By Currency).
// Mounted in the page toolbar via `usePageHeaderActions`, sitting to the left
// of the Refresh stale and Reload view buttons.
//
// Active option uses the canonical primary-action fill (primary-900 navy in
// light mode, accent-500 gold in dark mode — same recipe as the page's
// primary CTA). Inactive options are transparent and pick up a neutral
// hover. The container matches the toolbar button height (h ≈ `py-1.5`).
// ============================================================================

interface ViewSwitcherProps {
  value: ViewKey;
  onChange: (next: ViewKey) => void;
}

const OPTIONS: { key: ViewKey; label: string }[] = [
  { key: 'overview',    label: 'Overview' },
  { key: 'by-bank',     label: 'By Bank' },
  { key: 'by-currency', label: 'By Currency' },
];

export const ViewSwitcher: React.FC<ViewSwitcherProps> = ({ value, onChange }) => (
  <div
    role="tablist"
    aria-label="View"
    className="inline-flex border border-neutral-300 dark:border-primary-700 rounded-lg overflow-hidden"
  >
    {OPTIONS.map(({ key, label }) => {
      const active = value === key;
      return (
        <button
          key={key}
          type="button"
          role="tab"
          aria-current={active ? 'true' : undefined}
          aria-selected={active}
          onClick={() => onChange(key)}
          className={cn(
            'px-3 py-1.5 text-sm font-medium transition-colors',
            'focus:outline-none focus-visible:ring-2 focus-visible:ring-primary-400 dark:focus-visible:ring-accent-400',
            active
              ? 'bg-primary-900 text-white dark:bg-accent-500 dark:text-primary-950'
              : 'bg-transparent text-neutral-600 dark:text-neutral-300 hover:bg-neutral-100 dark:hover:bg-primary-800',
          )}
        >
          {label}
        </button>
      );
    })}
  </div>
);
