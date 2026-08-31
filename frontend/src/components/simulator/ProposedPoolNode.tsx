import React from 'react';
import { Layers } from 'lucide-react';
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
  className?: string;
}

export const ProposedPoolNode: React.FC<ProposedPoolNodeProps> = ({
  pool,
  resolveName,
  resolveCcy,
  className,
}) => {
  const members = pool.memberLocalIds ?? [];

  return (
    <div
      className={cn(
        'rounded-xl border border-neutral-200/80 dark:border-primary-800/60',
        'border-l-2 border-l-info-500',
        'bg-white dark:bg-primary-900/40',
        className,
      )}
    >
      <div className="flex items-start justify-between gap-3 px-4 py-3">
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
              {/* eslint-disable-next-line no-restricted-syntax -- status pill chip recipe; intentionally mirrors PhysicalAccountNode's bank-relationship pill (no typography utility exists for chip tags) */}
              <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-info-100 text-info-800 dark:bg-info-500/20 dark:text-info-300">
                Notional pool
              </span>
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
