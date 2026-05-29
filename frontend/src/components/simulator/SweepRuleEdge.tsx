import React from 'react';
import { ArrowRight } from 'lucide-react';
import { Badge } from '../ui';
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
  className?: string;
}

export const SweepRuleEdge: React.FC<SweepRuleEdgeProps> = ({
  rule,
  targetName,
  className,
}) => (
  <div
    className={cn(
      'flex items-center gap-1.5 body-sm text-neutral-500 dark:text-neutral-400',
      className,
    )}
  >
    <span className="font-medium text-neutral-600 dark:text-neutral-300">
      {SWEEP_LABEL[rule.sweepType] ?? rule.sweepType}
    </span>
    <ArrowRight className="w-3.5 h-3.5 shrink-0" aria-hidden />
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
  </div>
);
