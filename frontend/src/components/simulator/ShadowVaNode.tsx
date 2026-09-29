import React from 'react';
import { CornerDownRight, Pencil, Trash2 } from 'lucide-react';
import { Badge, Button } from '../ui';
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
  /** Forwarded to each rule edge; omit both for a read-only tree. */
  onEditRule?: (rule: SimulatedRule) => void;
  onDeleteRule?: (rule: SimulatedRule) => void;
  /** This shadow's own controls; omit both for a read-only tree. */
  onEdit?: (shadow: SimulatedShadow) => void;
  onDelete?: (shadow: SimulatedShadow) => void;
  className?: string;
}

export const ShadowVaNode: React.FC<ShadowVaNodeProps> = ({
  shadow,
  rulesFrom,
  resolveName,
  onEditRule,
  onDeleteRule,
  onEdit,
  onDelete,
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
      <div className="group flex items-start gap-2">
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
        {/* Same hover/focus-revealed pattern as the rule edge. */}
        {(onEdit || onDelete) && (
          <span className="ml-auto flex items-center gap-0.5 shrink-0 opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100">
            {onEdit && (
              <Button
                variant="ghost"
                size="xs"
                onClick={() => onEdit(shadow)}
                title={`Edit "${shadow.proposedVaName || 'shadow'}"`}
                aria-label={`Edit shadow ${shadow.proposedVaName || 'shadow'}`}
              >
                <Pencil className="w-3.5 h-3.5" />
              </Button>
            )}
            {onDelete && (
              <Button
                variant="ghost"
                size="xs"
                onClick={() => onDelete(shadow)}
                title={`Remove "${shadow.proposedVaName || 'shadow'}"`}
                aria-label={`Remove shadow ${shadow.proposedVaName || 'shadow'}`}
              >
                <Trash2 className="w-3.5 h-3.5 text-error-500 dark:text-error-300" />
              </Button>
            )}
          </span>
        )}
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
                  onEdit={onEditRule}
                  onDelete={onDeleteRule}
                />
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
