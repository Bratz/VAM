import React from 'react';
import { Layers, Pencil, Trash2 } from 'lucide-react';
import { Button } from '../ui';
import { cn } from '../../utils';
import type { SimulatedPool } from './types';

// ============================================================================
// ProposedPoolNode — a proposed notional pool in the structure tree. Pure
// view. Mirrors PhysicalAccountNode's container vocabulary (semantic palette,
// info-toned left rail — a notional pool is a distinct, complementary
// primitive to physical sweeps). No hex / gradient.
// ============================================================================

export interface ProposedPoolNodeProps {
  pool: SimulatedPool;
  resolveName: (localId: string) => string;
  resolveCcy?: (localId: string) => string | undefined;
  /** Omit both for a read-only tree (e.g. the Compare columns). */
  onEdit?: (pool: SimulatedPool) => void;
  onDelete?: (pool: SimulatedPool) => void;
  className?: string;
}

export const ProposedPoolNode: React.FC<ProposedPoolNodeProps> = ({
  pool,
  resolveName,
  resolveCcy,
  onEdit,
  onDelete,
  className,
}) => {
  const members = pool.memberLocalIds ?? [];

  return (
    <div
      className={cn(
        'rounded-lg border border-neutral-200/80 dark:border-primary-800/60',
        'border-l-2 border-l-info-500',
        'bg-white dark:bg-primary-900/40',
        className,
      )}
    >
      <div className="group flex items-start justify-between gap-3 px-4 py-3">
        <div className="flex items-center gap-2 min-w-0">
          <Layers
            className="w-4 h-4 shrink-0 text-neutral-500 dark:text-neutral-400"
            aria-hidden
          />
          <div className="min-w-0">
            <div className="flex items-center gap-2 flex-wrap">
              <span className="section-title truncate">
                {pool.poolName}
              </span>
              <span className="inline-flex items-center px-2 py-0.5 rounded-full text-caption font-medium bg-info-100 text-info-800 dark:bg-info-500/20 dark:text-info-300">
                Notional pool
              </span>
          {/* Same hover/focus-revealed pattern as the rule edge. */}
        {(onEdit || onDelete) && (
          <span className="ml-auto flex items-center gap-0.5 shrink-0 opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100">
            {onEdit && (
              <Button
                variant="ghost"
                size="xs"
                onClick={() => onEdit(pool)}
                title={`Edit "${pool.poolName}"`}
                aria-label={`Edit pool ${pool.poolName}`}
              >
                <Pencil className="w-3.5 h-3.5" />
              </Button>
            )}
            {onDelete && (
              <Button
                variant="ghost"
                size="xs"
                onClick={() => onDelete(pool)}
                title={`Remove "${pool.poolName}"`}
                aria-label={`Remove pool ${pool.poolName}`}
              >
                <Trash2 className="w-3.5 h-3.5 text-error-500 dark:text-error-300" />
              </Button>
            )}
          </span>
        )}
            </div>
            <p className="body-sm text-neutral-500 dark:text-neutral-400 mt-0.5">
              <span className="code">{pool.poolCurrency || '—'}</span>
              {' · '}
              {pool.poolRatePct}% / yr
              {' · '}
              {members.length} member{members.length === 1 ? '' : 's'}
            </p>
          </div>
        </div>
      </div>
      <div className="px-4 pb-2 divide-y divide-neutral-100 dark:divide-primary-800/40">
        {members.map((id) => (
          <div key={id} className="flex items-center gap-2 py-2 body-sm">
            <span className="truncate text-primary-900 dark:text-neutral-100">
              {resolveName(id)}
            </span>
            <span className="code text-neutral-400 ml-auto">
              {resolveCcy?.(id) ?? ''}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
};
