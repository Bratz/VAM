import React, { useState } from 'react';
import { ChevronRight } from 'lucide-react';
import { Badge } from '../ui';
import { cn } from '../../utils';
import type { DiffEntry, DiffOp } from './types';

// ============================================================================
// DiffRow — one diff entry. op pill (fixed width) + entity label/detail +
// caveat chips; click to expand fieldChanges. AttentionRow visual vocabulary.
// ============================================================================

const OP_VARIANT: Record<DiffOp, 'success' | 'warning' | 'neutral'> = {
  ADD: 'success',
  MODIFY: 'warning',
  RETIRE: 'neutral',
  UNCHANGED: 'neutral',
};

export interface DiffRowProps {
  entry: DiffEntry;
  className?: string;
}

export const DiffRow: React.FC<DiffRowProps> = ({ entry, className }) => {
  const [open, setOpen] = useState(false);
  const expandable = !!entry.fieldChanges && entry.fieldChanges.length > 0;

  return (
    <div className={cn('py-2', className)}>
      <div
        className={cn(
          'flex items-start gap-3',
          expandable && 'cursor-pointer',
        )}
        onClick={expandable ? () => setOpen((o) => !o) : undefined}
      >
        <div className="w-20 shrink-0 pt-0.5">
          <Badge variant={OP_VARIANT[entry.op]} size="sm">
            {entry.op}
          </Badge>
        </div>

        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2 flex-wrap">
            <span className="body-sm text-primary-900 dark:text-neutral-100 truncate">
              {entry.label}
            </span>
            <span className="code text-neutral-400">{entry.entity}</span>
          </div>
          {expandable && open && (
            <ul className="mt-1.5 space-y-1">
              {entry.fieldChanges!.map((f, i) => (
                <li
                  key={i}
                  className="body-sm text-neutral-500 dark:text-neutral-400"
                >
                  <span className="code">{f.field}</span>:{' '}
                  <span className="text-error-700 dark:text-error-300">
                    {String(f.before ?? '—')}
                  </span>{' '}
                  →{' '}
                  <span className="text-success-700 dark:text-success-300">
                    {String(f.after ?? '—')}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </div>

        <div className="flex items-center gap-1.5 shrink-0 flex-wrap justify-end">
          {(entry.caveats ?? []).map((c, i) => (
            <Badge key={i} variant="warning" size="xs">
              {c}
            </Badge>
          ))}
          {expandable && (
            <ChevronRight
              className={cn(
                'w-4 h-4 text-neutral-400 transition-transform',
                open && 'rotate-90',
              )}
              aria-hidden
            />
          )}
        </div>
      </div>
    </div>
  );
};
