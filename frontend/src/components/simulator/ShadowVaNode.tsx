import React from 'react';
import { CornerDownRight } from 'lucide-react';
import { Badge } from '../ui';
import { cn } from '../../utils';
import { SweepRuleEdge } from './SweepRuleEdge';
import type { SimulatedRule, SimulatedShadow } from './types';

// ============================================================================
// ShadowVaNode — one proposed Shadow VA row within a bank group. Pure view.
// Shows role, proposed name, currency/rate/source meta, and any rule that
// originates here (inline via SweepRuleEdge).
// ============================================================================

export interface ShadowVaNodeProps {
  shadow: SimulatedShadow;
  /** Rules where this shadow is a source — rendered inline beneath. */
  rulesFrom: SimulatedRule[];
  /** localId → proposed VA name, for the rule edge's target. */
  resolveName: (localId: string) => string;
  className?: string;
}

export const ShadowVaNode: React.FC<ShadowVaNodeProps> = ({
  shadow,
  rulesFrom,
  resolveName,
  className,
}) => {
  const isChild = shadow.role === 'CHILD';

  return (
    <div
      className={cn(
        'py-2',
        isChild && 'pl-6',
        className,
      )}
    >
      <div className="flex items-start gap-2">
        {isChild && (
          <CornerDownRight
            className="w-4 h-4 mt-0.5 shrink-0 text-neutral-400"
            aria-hidden
          />
        )}
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2 flex-wrap">
            <Badge variant={isChild ? 'neutral' : 'info'} size="xs">
              {isChild ? 'Child' : 'Header'}
            </Badge>
            <span className="font-medium text-primary-900 dark:text-neutral-50 truncate">
              {shadow.proposedVaName || 'Unnamed shadow'}
            </span>
          </div>
          <div className="mt-0.5 flex items-center gap-2 flex-wrap body-sm text-neutral-500 dark:text-neutral-400">
            <span className="code">{shadow.snapshotCurrencyCode || '—'}</span>
            {typeof shadow.snapshotInterestRate === 'number' && (
              <>
                <span aria-hidden>·</span>
                <span>{shadow.snapshotInterestRate}%</span>
              </>
            )}
            {shadow.snapshotDataSource && (
              <>
                <span aria-hidden>·</span>
                <span>{shadow.snapshotDataSource}</span>
              </>
            )}
          </div>
          {rulesFrom.length > 0 && (
            <div className="mt-1 space-y-0.5">
              {rulesFrom.map((r) => (
                <SweepRuleEdge
                  key={r.localId}
                  rule={r}
                  targetName={resolveName(r.targetLocalId)}
                />
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
