import { cn } from '../../utils';

// ============================================================================
// Multi-Bank Liquidity — segmented control.
//
// Used twice on the page: the view pivot (Overview / Accounts / Trend) and the
// Accounts table's Group-by (Bank / Currency / Entity / Country).
//
// Active option uses the canonical primary-action fill (primary-900 navy in
// light mode, accent-500 gold in dark mode — same recipe as the page's
// primary CTA). Inactive options are transparent and pick up a neutral
// hover. The container matches the toolbar button height (h ≈ `py-1.5`).
// ============================================================================

interface ViewSwitcherProps<K extends string> {
  value: K;
  onChange: (next: K) => void;
  options: { key: K; label: string }[];
  label: string;
}

export function ViewSwitcher<K extends string>({ value, onChange, options, label }: ViewSwitcherProps<K>) {
  return (
    <div
      role="tablist"
      aria-label={label}
      className="inline-flex border border-edge-strong rounded-lg overflow-hidden"
    >
      {options.map(({ key, label: optionLabel }) => {
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
              'px-3 py-1.5 text-body-sm font-medium transition-colors',
              'focus:outline-none focus-visible:ring-2 focus-visible:ring-primary-400 dark:focus-visible:ring-accent-400',
              active
                ? 'bg-primary-900 text-white dark:bg-accent-500 dark:text-white'
                : 'bg-transparent text-neutral-600 dark:text-neutral-300 hover:bg-neutral-100 dark:hover:bg-primary-800',
            )}
          >
            {optionLabel}
          </button>
        );
      })}
    </div>
  );
}
