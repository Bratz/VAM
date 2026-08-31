import React from 'react';
import { Card } from '../ui';
import { cn } from '../../utils';
import type { DiffCounts } from './types';

// ============================================================================
// DiffSummaryStrip — 4 tiles: Add / Modify / Retire / Unchanged. Pure.
// ============================================================================

export interface DiffSummaryStripProps {
  counts: DiffCounts;
  className?: string;
}

const TILES: Array<{
  key: keyof DiffCounts;
  label: string;
  desc: string;
  tone: string;
}> = [
  { key: 'add', label: 'Add', desc: 'new shadows / rules', tone: 'text-success-700 dark:text-success-300' },
  { key: 'modify', label: 'Modify', desc: 'changed in place', tone: 'text-warning-700 dark:text-warning-300' },
  { key: 'retire', label: 'Retire', desc: 'live, not proposed', tone: 'text-neutral-600 dark:text-neutral-300' },
  { key: 'unchanged', label: 'Unchanged', desc: 'identical', tone: 'text-neutral-500 dark:text-neutral-400' },
];

export const DiffSummaryStrip: React.FC<DiffSummaryStripProps> = ({
  counts,
  className,
}) => (
  <div
    className={cn(
      'grid grid-cols-2 lg:grid-cols-4 gap-3',
      className,
    )}
  >
    {TILES.map((t) => (
      <Card key={t.key} padding="sm">
        <p className="label">{t.label}</p>
        <p className={cn('stat-value-sm mt-1', t.tone)}>{counts[t.key]}</p>
        <p className="body-sm text-neutral-500 dark:text-neutral-400 mt-0.5">
          {t.desc}
        </p>
      </Card>
    ))}
  </div>
);
