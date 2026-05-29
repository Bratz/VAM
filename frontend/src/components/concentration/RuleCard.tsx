import React from 'react';
import { Edit3, Pause, Play, Trash2, Building2, Clock } from 'lucide-react';
import { Card, Button, Badge } from '../ui';
import { formatCompactCurrency, formatRelativeTime } from '../../utils';
import { SweepRule } from '../../services/api';
import { SWEEP_TYPES } from './constants';

// ============================================================================
// RULE CARD
// ============================================================================
export interface RuleCardProps {
  rule: SweepRule;
  onToggle: () => void;
  onDelete: () => void;
  onView: () => void;
  onEdit: () => void;
}

export const RuleCard: React.FC<RuleCardProps> = ({ rule, onToggle, onDelete, onView, onEdit }) => {
  const typeConfig = SWEEP_TYPES[rule.sweepType as keyof typeof SWEEP_TYPES] || SWEEP_TYPES.ZERO_BALANCE;

  return (
    <Card className="hover:shadow-md transition-shadow cursor-pointer" onClick={onView}>
      <div className="p-4">
        <div className="flex items-start justify-between mb-3">
          <div>
            <div className="flex items-center gap-2">
              <h3 className="font-medium text-primary-900 dark:text-neutral-50">{rule.ruleName}</h3>
              <Badge variant={rule.status === 'ACTIVE' ? 'success' : 'warning'} size="sm">
                {rule.status}
              </Badge>
            </div>
            <p className="text-sm text-neutral-500 dark:text-neutral-400">{rule.ruleReference}</p>
          </div>
          <div className="flex items-center gap-1">
            <Button
              variant="ghost"
              size="sm"
              onClick={(e) => { e.stopPropagation(); onEdit(); }}
              title="Edit rule"
            >
              <Edit3 className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
            </Button>
            <Button
              variant="ghost"
              size="sm"
              onClick={(e) => { e.stopPropagation(); onToggle(); }}
              title={rule.status === 'ACTIVE' ? 'Pause rule' : 'Activate rule'}
            >
              {rule.status === 'ACTIVE' ? <Pause className="w-4 h-4" /> : <Play className="w-4 h-4" />}
            </Button>
            <Button
              variant="ghost"
              size="sm"
              onClick={(e) => { e.stopPropagation(); onDelete(); }}
              title="Delete rule"
            >
              <Trash2 className="w-4 h-4 text-error-500 dark:text-error-300" />
            </Button>
          </div>
        </div>

        <div className="grid grid-cols-3 gap-4 text-sm">
          <div>
            <p className="text-neutral-500 dark:text-neutral-400">Type</p>
            <p className="font-medium">{typeConfig.short}</p>
          </div>
          <div>
            <p className="text-neutral-500 dark:text-neutral-400">Frequency</p>
            <p className="font-medium">{rule.frequency}</p>
          </div>
          <div>
            <p className="text-neutral-500 dark:text-neutral-400">Total Swept</p>
            <p className="font-medium">{formatCompactCurrency(rule.totalSwept || 0, rule.currencyCode || 'AED')}</p>
          </div>
        </div>

        {/* Source accounts count indicator */}
        {rule.sourceAccounts && rule.sourceAccounts.length > 0 && (
          <div className="mt-3 pt-3 border-t flex items-center gap-2 text-xs text-neutral-500 dark:text-neutral-400">
            <Building2 className="w-3 h-3" />
            <span>{rule.sourceAccounts.length} source account(s)</span>
          </div>
        )}

        {rule.lastExecution && (
          <div className="mt-2 flex items-center gap-2 text-xs text-neutral-500 dark:text-neutral-400">
            <Clock className="w-3 h-3" />
            <span>Last run: {formatRelativeTime(rule.lastExecution)}</span>
          </div>
        )}
      </div>
    </Card>
  );
};
