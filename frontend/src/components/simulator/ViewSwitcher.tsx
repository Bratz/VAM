import React from 'react';
import { LayoutGrid, GitCompare, Columns3 } from 'lucide-react';
import { cn } from '../../utils';

// ============================================================================
// ViewSwitcher — segmented control: Structure / Diff / Compare. Pure.
// ============================================================================

export type SimulatorView = 'structure' | 'diff' | 'compare';

export interface ViewSwitcherProps {
  value: SimulatorView;
  onChange: (v: SimulatorView) => void;
  className?: string;
}

const SEGMENTS: Array<{
  key: SimulatorView;
  label: string;
  icon: React.ReactNode;
}> = [
  { key: 'structure', label: 'Structure', icon: <LayoutGrid className="w-4 h-4" /> },
  { key: 'diff', label: 'Diff vs live', icon: <GitCompare className="w-4 h-4" /> },
  { key: 'compare', label: 'Compare', icon: <Columns3 className="w-4 h-4" /> },
];

export const ViewSwitcher: React.FC<ViewSwitcherProps> = ({
  value,
  onChange,
  className,
}) => (
  <div
    role="tablist"
    aria-label="Simulator view"
    className={cn(
      'inline-flex items-center gap-1 p-1 rounded-xl',
      'bg-neutral-100 dark:bg-primary-800/60',
      className,
    )}
  >
    {SEGMENTS.map((s) => {
      const active = value === s.key;
      return (
        <button
          key={s.key}
          type="button"
          role="tab"
          aria-selected={active}
          onClick={() => onChange(s.key)}
          className={cn(
            'inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium',
            'transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-primary-500',
            active
              ? 'bg-white text-primary-900 dark:bg-primary-900 dark:text-neutral-50'
              : 'text-neutral-600 hover:text-primary-900 dark:text-neutral-300 dark:hover:text-neutral-50',
          )}
        >
          {s.icon}
          {s.label}
        </button>
      );
    })}
  </div>
);
