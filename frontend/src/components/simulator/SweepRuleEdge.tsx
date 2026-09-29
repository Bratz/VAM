import React from 'react';
import { ArrowRight, Pencil, Trash2 } from 'lucide-react';
import { Badge, Button } from '../ui';
import { cn } from '../../utils';
import type { SimulatedRule } from './types';

// ============================================================================
// SweepRuleEdge — inline rule metadata shown under a source ShadowVaNode.
// Pure view. e.g. "ZBA → HQ Master · daily 18:00" + cross-bank chip.
// ============================================================================

const SWEEP_LABEL: Record<SimulatedRule['sweepType'], string> = {
  ZERO_BALANCE: 'ZBA',
  TARGET_BALANCE: 'Target',
  THRESHOLD: 'Threshold',
  PERCENTAGE: '%',
};

const FREQ_LABEL: Record<SimulatedRule['frequency'], string> = {
  DAILY: 'daily',
  WEEKLY: 'weekly',
  MONTHLY: 'monthly',
  ON_DEMAND: 'on demand',
};

export interface SweepRuleEdgeProps {
  rule: SimulatedRule;
  /** Resolved proposed VA name of the rule's target shadow. */
  targetName: string;
  /** Omit both to render read-only (e.g. the Compare columns). */
  onEdit?: (rule: SimulatedRule) => void;
  onDelete?: (rule: SimulatedRule) => void;
  className?: string;
}

export const SweepRuleEdge: React.FC<SweepRuleEdgeProps> = ({
  rule,
  targetName,
  onEdit,
  onDelete,
  className,
}) => (
  <div
    className={cn(
      'group flex items-center gap-1.5 body-sm text-neutral-500 dark:text-neutral-400',
      className,
    )}
  >
    <span className="font-medium text-neutral-600 dark:text-neutral-300">
      {SWEEP_LABEL[rule.sweepType] ?? rule.sweepType}
    </span>
    <ArrowRight className="w-4 h-4 shrink-0" aria-hidden />
    <span className="truncate">{targetName || '—'}</span>
    <span aria-hidden>·</span>
    <span className="shrink-0">
      {FREQ_LABEL[rule.frequency] ?? rule.frequency}
      {rule.executionTime ? ` ${rule.executionTime}` : ''}
    </span>
    {rule.isCrossBank && (
      <Badge variant="warning" size="xs" className="ml-1">
        cross-bank{rule.paymentRail ? ` · ${rule.paymentRail}` : ''}
      </Badge>
    )}
    {/* A rule was previously write-once: it could be added and then never
        changed or removed, so getting a frequency wrong meant rebuilding the
        scenario. Revealed on hover/focus so the row stays quiet at rest, but
        always reachable by keyboard. */}
    {(onEdit || onDelete) && (
      <span className="ml-auto flex items-center gap-0.5 shrink-0 opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100">
        {onEdit && (
          <Button
            variant="ghost"
            size="xs"
            onClick={() => onEdit(rule)}
            title={`Edit "${rule.ruleName}"`}
            aria-label={`Edit rule ${rule.ruleName}`}
          >
            <Pencil className="w-3.5 h-3.5" />
          </Button>
        )}
        {onDelete && (
          <Button
            variant="ghost"
            size="xs"
            onClick={() => onDelete(rule)}
            title={`Remove "${rule.ruleName}"`}
            aria-label={`Remove rule ${rule.ruleName}`}
          >
            <Trash2 className="w-3.5 h-3.5 text-error-500 dark:text-error-300" />
          </Button>
        )}
      </span>
    )}
  </div>
);
