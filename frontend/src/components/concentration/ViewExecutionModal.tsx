import React from 'react';
import { CheckCircle, AlertCircle, Layers, Building2, ArrowRight, Clock } from 'lucide-react';
import { Card, Button, Badge, StatusIconBadge } from '../ui';
import { Modal } from '../ui/enhanced';
import { formatCompactCurrency, formatRelativeTime, cn } from '../../utils';
import { SweepExecution } from '../../services/api';

// ============================================================================
// VIEW EXECUTION MODAL
// ============================================================================
export interface ViewExecutionModalProps {
  isOpen: boolean;
  onClose: () => void;
  execution: SweepExecution | null;
}

export const ViewExecutionModal: React.FC<ViewExecutionModalProps> = ({ isOpen, onClose, execution }) => {
  if (!execution) return null;

  const exec = execution as any;
  const statusVariant = exec.status === 'SUCCESS' ? 'success' : exec.status === 'FAILED' ? 'error' : 'warning';

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="Execution Details" size="md">
      <div className="space-y-6 animate-fade-in">
        {/* Header */}
        <Card className={cn(
          'border-2',
          exec.status === 'SUCCESS' && 'bg-success-50/30 dark:bg-success-500/10 border-success-200/60 dark:border-success-500/30',
          exec.status === 'FAILED' && 'bg-error-50/30 dark:bg-error-500/10 border-error-200/60 dark:border-error-500/30',
          exec.status === 'SKIPPED' && 'bg-warning-50/30 dark:bg-warning-500/10 border-warning-200/60 dark:border-warning-500/30',
          !['SUCCESS', 'FAILED', 'SKIPPED'].includes(exec.status) && 'bg-neutral-50/30 dark:bg-primary-950/30 border-neutral-200/60 dark:border-primary-800'
        )}>
          <div className="flex items-start justify-between">
            <div className="flex items-center gap-4">
              <div className={cn(
                'w-12 h-12 rounded-xl flex items-center justify-center',
                exec.status === 'SUCCESS' && 'bg-success-100 dark:bg-success-500/20',
                exec.status === 'FAILED' && 'bg-error-100 dark:bg-error-500/20',
                exec.status === 'SKIPPED' && 'bg-warning-100 dark:bg-warning-500/20',
                !['SUCCESS', 'FAILED', 'SKIPPED'].includes(exec.status) && 'bg-neutral-100 dark:bg-primary-800'
              )}>
                {exec.status === 'SUCCESS' && <CheckCircle className="w-6 h-6 text-success-600 dark:text-success-300" />}
                {exec.status === 'FAILED' && <AlertCircle className="w-6 h-6 text-error-600 dark:text-error-300" />}
                {exec.status === 'SKIPPED' && <AlertCircle className="w-6 h-6 text-warning-600 dark:text-warning-300" />}
                {!['SUCCESS', 'FAILED', 'SKIPPED'].includes(exec.status) && <Layers className="w-6 h-6 text-neutral-600 dark:text-neutral-300" />}
              </div>
              <div>
                <h3 className="section-title">{exec.ruleName || 'Sweep Execution'}</h3>
                <p className="text-sm text-neutral-500 dark:text-neutral-400 font-mono">{exec.executionReference || exec.id}</p>
              </div>
            </div>
            <Badge variant={statusVariant} size="md" dot>{exec.status}</Badge>
          </div>
        </Card>

        {/* Execution Flow */}
        <Card>
          <p className="text-xs text-neutral-500 dark:text-neutral-400 uppercase tracking-wider mb-4">Transfer Flow</p>
          <div className="flex items-center justify-between">
            {/* Source Account */}
            <div className="flex-1">
              <div className="flex items-center gap-3">
                <StatusIconBadge tone="warning" icon={Building2} size="lg" />
                <div>
                  <p className="font-semibold text-primary-900 dark:text-neutral-50">{exec.sourceEntityCode || 'Source'}</p>
                  <p className="text-xs text-neutral-500 dark:text-neutral-400 font-mono">{exec.sourceAccountNumber || 'N/A'}</p>
                </div>
              </div>
            </div>

            {/* Arrow */}
            <div className="px-6">
              <div className="flex items-center gap-2">
                <div className="h-0.5 w-8 bg-warning-300 rounded-full"></div>
                <div className="w-8 h-8 rounded-full bg-primary-100 dark:bg-primary-700 flex items-center justify-center">
                  <ArrowRight className="w-4 h-4 text-primary-600 dark:text-primary-200" />
                </div>
                <div className="h-0.5 w-8 bg-success-300 rounded-full"></div>
              </div>
              <p className="text-sm font-semibold text-primary-700 dark:text-neutral-200 text-center mt-2">
                {formatCompactCurrency(exec.sweepAmount || exec.amountSwept || 0, exec.currencyCode || 'AED')}
              </p>
            </div>

            {/* Target Account */}
            <div className="flex-1 text-right">
              <div className="flex items-center gap-3 justify-end">
                <div>
                  <p className="font-semibold text-primary-900 dark:text-neutral-50">{exec.targetEntityCode || 'Target'}</p>
                  <p className="text-xs text-neutral-500 dark:text-neutral-400 font-mono">{exec.targetAccountNumber || 'N/A'}</p>
                </div>
                <StatusIconBadge tone="success" icon={Building2} size="lg" />
              </div>
            </div>
          </div>
        </Card>

        {/* Details Grid */}
        <div className="grid grid-cols-2 gap-4">
          <Card padding="sm" className="bg-primary-50/50 dark:bg-primary-800/40 border-primary-200/60 dark:border-primary-700">
            <p className="text-xs text-primary-600 dark:text-primary-200 uppercase tracking-wider mb-1">Amount Swept</p>
            <p className="stat-value-xs">
              {formatCompactCurrency(exec.sweepAmount || exec.amountSwept || 0, exec.currencyCode || 'AED')}
            </p>
          </Card>
          <Card padding="sm" className="bg-neutral-50/50 dark:bg-primary-950/50">
            <p className="text-xs text-neutral-500 dark:text-neutral-400 uppercase tracking-wider mb-1">Currency</p>
            <p className="stat-value-xs">{exec.currencyCode || 'AED'}</p>
          </Card>
        </div>

        {/* Balance Before/After */}
        {(exec.balanceBefore !== undefined || exec.balanceAfter !== undefined) && (
          <div className="grid grid-cols-2 gap-4">
            <Card padding="sm" className="bg-warning-50/50 dark:bg-warning-500/10 border-warning-200/60 dark:border-warning-500/30">
              <p className="text-xs text-warning-600 dark:text-warning-300 uppercase tracking-wider mb-1">Balance Before</p>
              <p className="stat-value-sm text-warning-800 dark:text-warning-300">
                {formatCompactCurrency(exec.balanceBefore || 0, exec.currencyCode || 'AED')}
              </p>
            </Card>
            <Card padding="sm" className="bg-success-50/50 dark:bg-success-500/10 border-success-200/60 dark:border-success-500/30">
              <p className="text-xs text-success-600 dark:text-success-300 uppercase tracking-wider mb-1">Balance After</p>
              <p className="stat-value-sm text-success-800 dark:text-success-300">
                {formatCompactCurrency(exec.balanceAfter || 0, exec.currencyCode || 'AED')}
              </p>
            </Card>
          </div>
        )}

        {/* Execution Time */}
        <Card padding="sm" className="bg-neutral-50/50 dark:bg-primary-950/50">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-neutral-100 dark:bg-primary-800 flex items-center justify-center">
              <Clock className="w-5 h-5 text-neutral-500 dark:text-neutral-400" />
            </div>
            <div>
              <p className="text-xs text-neutral-500 dark:text-neutral-400 uppercase tracking-wider mb-0.5">Execution Time</p>
              <p className="font-semibold text-primary-900 dark:text-neutral-50">
                {exec.executionTime
                  ? new Date(exec.executionTime).toLocaleString()
                  : exec.executedAt
                    ? new Date(exec.executedAt).toLocaleString()
                    : exec.completedAt
                      ? new Date(exec.completedAt).toLocaleString()
                      : 'N/A'}
              </p>
              <p className="text-xs text-neutral-500 dark:text-neutral-400">
                {exec.executionTime
                  ? formatRelativeTime(exec.executionTime)
                  : exec.executedAt
                    ? formatRelativeTime(exec.executedAt)
                    : ''}
              </p>
            </div>
          </div>
        </Card>

        {/* Error Message (if failed) */}
        {exec.status === 'FAILED' && exec.errorMessage && (
          <Card className="bg-error-50/50 dark:bg-error-500/10 border-error-200/60 dark:border-error-500/30">
            <div className="flex items-start gap-4">
              <StatusIconBadge tone="error" icon={AlertCircle} className="flex-shrink-0" />
              <div>
                <p className="font-semibold text-error-800 dark:text-error-300">Execution Failed</p>
                <p className="text-sm text-error-700 dark:text-error-300 mt-1">{exec.errorMessage}</p>
              </div>
            </div>
          </Card>
        )}

        {/* Skipped Reason (if skipped) */}
        {exec.status === 'SKIPPED' && exec.skipReason && (
          <Card className="bg-warning-50/50 dark:bg-warning-500/10 border-warning-200/60 dark:border-warning-500/30">
            <div className="flex items-start gap-4">
              <StatusIconBadge tone="warning" icon={AlertCircle} className="flex-shrink-0" />
              <div>
                <p className="font-semibold text-warning-800 dark:text-warning-300">Execution Skipped</p>
                <p className="text-sm text-warning-700 dark:text-warning-300 mt-1">{exec.skipReason}</p>
              </div>
            </div>
          </Card>
        )}

        {/* Additional Details */}
        <Card padding="sm" className="bg-neutral-50/50 dark:bg-primary-950/50">
          <p className="text-xs text-neutral-500 dark:text-neutral-400 uppercase tracking-wider mb-3">Additional Details</p>
          <div className="space-y-2">
            <div className="flex justify-between text-sm">
              <span className="text-neutral-500 dark:text-neutral-400">Rule Reference</span>
              <span className="font-mono text-primary-900 dark:text-neutral-50">{exec.ruleReference || 'N/A'}</span>
            </div>
            <div className="flex justify-between text-sm">
              <span className="text-neutral-500 dark:text-neutral-400">Sweep Type</span>
              <Badge variant="neutral" size="xs">{exec.sweepType || 'N/A'}</Badge>
            </div>
            {exec.ruleId && (
              <div className="flex justify-between text-sm">
                <span className="text-neutral-500 dark:text-neutral-400">Rule ID</span>
                <span className="font-mono text-neutral-600 dark:text-neutral-300 text-xs">{exec.ruleId}</span>
              </div>
            )}
          </div>
        </Card>
      </div>

      <div className="flex justify-end mt-8 pt-6 border-t border-neutral-200 dark:border-primary-800">
        <Button onClick={onClose}>Close</Button>
      </div>
    </Modal>
  );
};
