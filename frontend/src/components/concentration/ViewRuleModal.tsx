import React from 'react';
import { Layers, Building2, TrendingUp } from 'lucide-react';
import { Card, Button, Badge, StatusIconBadge } from '../ui';
import { Modal } from '../ui/enhanced';
import { formatCompactCurrency, formatRelativeTime } from '../../utils';
import { SweepRule } from '../../services/api';
import { SWEEP_TYPES } from './constants';
import { VirtualizedAccountList } from '../va/VirtualizedAccountList';

// ============================================================================
// VIEW RULE DETAILS MODAL
// ============================================================================
export interface ViewRuleModalProps {
  isOpen: boolean;
  onClose: () => void;
  rule: SweepRule | null;
}

export const ViewRuleModal: React.FC<ViewRuleModalProps> = ({ isOpen, onClose, rule }) => {
  if (!rule) return null;

  const typeConfig = SWEEP_TYPES[rule.sweepType as keyof typeof SWEEP_TYPES] || SWEEP_TYPES.ZERO_BALANCE;

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="Rule Details" size="md">
      <div className="space-y-6 animate-fade-in">
        {/* Header */}
        <Card className="bg-primary-50/50 dark:bg-primary-500/10 border-primary-200/60 dark:border-primary-700">
          <div className="flex items-start justify-between">
            <div className="flex items-center gap-4">
              <StatusIconBadge tone="primary" icon={Layers} size="lg" />
              <div>
                <h3 className="section-title">{rule.ruleName}</h3>
                <p className="text-sm text-neutral-500 dark:text-neutral-400 font-mono">{rule.ruleReference}</p>
              </div>
            </div>
            <Badge variant={rule.status === 'ACTIVE' ? 'success' : 'warning'} size="md" dot>
              {rule.status}
            </Badge>
          </div>
        </Card>

        {/* Details Grid */}
        <div className="grid grid-cols-2 gap-4">
          <Card padding="sm" className="bg-neutral-50/50 dark:bg-primary-950/50">
            <p className="text-xs text-neutral-500 dark:text-neutral-400 uppercase tracking-wider mb-1">Sweep Type</p>
            <p className="font-semibold text-primary-900 dark:text-neutral-50">{typeConfig.label}</p>
            <p className="text-xs text-neutral-500 dark:text-neutral-400 mt-1">{typeConfig.desc}</p>
          </Card>
          <Card padding="sm" className="bg-neutral-50/50 dark:bg-primary-950/50">
            <p className="text-xs text-neutral-500 dark:text-neutral-400 uppercase tracking-wider mb-1">Frequency</p>
            <p className="font-semibold text-primary-900 dark:text-neutral-50">{rule.frequency}</p>
          </Card>
          <Card padding="sm" className="bg-neutral-50/50 dark:bg-primary-950/50">
            <p className="text-xs text-neutral-500 dark:text-neutral-400 uppercase tracking-wider mb-1">Currency</p>
            <p className="font-semibold text-primary-900 dark:text-neutral-50">{rule.currencyCode || 'AED'}</p>
          </Card>
          <Card padding="sm" className="bg-neutral-50/50 dark:bg-primary-950/50">
            <p className="text-xs text-neutral-500 dark:text-neutral-400 uppercase tracking-wider mb-1">Priority</p>
            <p className="font-semibold text-primary-900 dark:text-neutral-50">{rule.priority || 1}</p>
          </Card>
        </div>

        {/* Target Account */}
        <Card className="border-success-200/60 dark:border-success-500/30 bg-success-50/30 dark:bg-success-500/10">
          <p className="text-xs text-neutral-500 dark:text-neutral-400 uppercase tracking-wider mb-3">Target Account (Concentration)</p>
          <div className="flex items-center gap-4">
            <StatusIconBadge tone="success" icon={Building2} size="lg" />
            <div>
              <p className="font-semibold text-primary-900 dark:text-neutral-50">{rule.targetEntityCode || 'HQ'}</p>
              <p className="text-sm text-neutral-500 dark:text-neutral-400 font-mono">{rule.targetAccountNumber || 'N/A'}</p>
            </div>
          </div>
        </Card>

        {/* Source Accounts — virtualized: a rule can have ~2000 sources */}
        {rule.sourceAccounts && rule.sourceAccounts.length > 0 && (
          <Card className="border-warning-200/60 dark:border-warning-500/30 bg-warning-50/30 dark:bg-warning-500/10">
            <div className="flex items-center justify-between mb-3">
              <p className="text-xs text-neutral-500 dark:text-neutral-400 uppercase tracking-wider">Source Accounts</p>
              <Badge variant="warning" size="sm">{rule.sourceAccounts.length} account(s)</Badge>
            </div>
            <VirtualizedAccountList
              items={rule.sourceAccounts.map((source) => ({
                id: source.id,
                vaNumber: source.accountNumber,
                vaName: source.entityName || source.entityCode,
                currencyCode: source.currencyCode || 'AED',
                balance: source.balance,
              }))}
              height={280}
            />
          </Card>
        )}

        {/* Type-specific Details */}
        {rule.sweepType === 'TARGET_BALANCE' && rule.targetAmount && (
          <Card className="bg-primary-50/50 dark:bg-primary-800/40 border-primary-200/60 dark:border-primary-700">
            <div className="flex items-center gap-4">
              <StatusIconBadge tone="primary" icon={TrendingUp} />
              <div>
                <p className="text-xs text-primary-600 dark:text-primary-200 uppercase tracking-wider mb-1">Target Balance</p>
                <p className="stat-value-xs">
                  {formatCompactCurrency(rule.targetAmount, rule.currencyCode || 'AED')}
                </p>
              </div>
            </div>
          </Card>
        )}

        {rule.sweepType === 'THRESHOLD' && (
          <div className="grid grid-cols-2 gap-4">
            <Card className="bg-warning-50/50 dark:bg-warning-500/10 border-warning-200/60 dark:border-warning-500/30" padding="sm">
              <p className="text-xs text-warning-600 dark:text-warning-300 uppercase tracking-wider mb-1">Min Threshold</p>
              <p className="stat-value-sm text-warning-900 dark:text-warning-300">
                {formatCompactCurrency(rule.thresholdMin || 0, rule.currencyCode || 'AED')}
              </p>
            </Card>
            <Card className="bg-warning-50/50 dark:bg-warning-500/10 border-warning-200/60 dark:border-warning-500/30" padding="sm">
              <p className="text-xs text-warning-600 dark:text-warning-300 uppercase tracking-wider mb-1">Max Threshold</p>
              <p className="stat-value-sm text-warning-900 dark:text-warning-300">
                {formatCompactCurrency(rule.thresholdMax || 0, rule.currencyCode || 'AED')}
              </p>
            </Card>
          </div>
        )}

        {rule.sweepType === 'PERCENTAGE' && rule.percentage && (
          <Card className="bg-info-50/50 dark:bg-info-500/10 border-info-200/60 dark:border-info-500/30">
            <div className="flex items-center gap-4">
              <StatusIconBadge tone="info" icon={TrendingUp} />
              <div>
                <p className="text-xs text-info-600 dark:text-info-300 uppercase tracking-wider mb-1">Sweep Percentage</p>
                <p className="stat-value-sm text-info-900 dark:text-info-300">{rule.percentage}%</p>
              </div>
            </div>
          </Card>
        )}

        {/* Statistics */}
        <div className="grid grid-cols-2 gap-4 pt-6 border-t border-neutral-200 dark:border-primary-800">
          <Card padding="sm" className="bg-success-50/50 dark:bg-success-500/10 border-success-200/60 dark:border-success-500/30">
            <p className="text-xs text-success-600 dark:text-success-300 uppercase tracking-wider mb-1">Total Swept</p>
            <p className="stat-value-sm text-success-700 dark:text-success-300">
              {formatCompactCurrency(rule.totalSwept || 0, rule.currencyCode || 'AED')}
            </p>
          </Card>
          <Card padding="sm" className="bg-neutral-50/50 dark:bg-primary-950/50">
            <p className="text-xs text-neutral-500 dark:text-neutral-400 uppercase tracking-wider mb-1">Last Execution</p>
            <p className="font-semibold text-primary-900 dark:text-neutral-50">
              {rule.lastExecution ? formatRelativeTime(rule.lastExecution) : 'Never'}
            </p>
          </Card>
        </div>
      </div>

      <div className="flex justify-end mt-8 pt-6 border-t border-neutral-200 dark:border-primary-800">
        <Button onClick={onClose}>Close</Button>
      </div>
    </Modal>
  );
};
