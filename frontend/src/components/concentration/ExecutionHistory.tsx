import React from 'react';
import { Clock, ArrowRight, Eye } from 'lucide-react';
import { Card, Button, Badge, DataTable } from '../ui';
import { formatCurrency, formatRelativeTime } from '../../utils';
import { Amount } from '../Amount';
import { SweepExecution } from '../../services/api';

// ============================================================================
// EXECUTION HISTORY TABLE
//
// Backend field alignment notes:
// - exec.sweepAmount (not amountSwept), exec.executionTime (not executedAt)
// - includes executionReference, source→target flow, balance before/after,
//   and error/skip messaging.
// ============================================================================
export interface ExecutionHistoryProps {
  executions: SweepExecution[];
  onViewExecution: (execution: SweepExecution) => void;
}

export const ExecutionHistory: React.FC<ExecutionHistoryProps> = ({ executions, onViewExecution }) => {
  if (executions.length === 0) {
    return (
      <Card className="text-center py-8">
        <Clock className="w-8 h-8 text-neutral-300 dark:text-neutral-400 mx-auto mb-2" />
        <p className="text-neutral-500 dark:text-neutral-400">No execution history yet</p>
        <p className="caption mt-1">Execute sweep rules to see history here</p>
      </Card>
    );
  }

  return (
    <Card className="animate-fade-in" style={{ animationDelay: '0.2s' }}>
      <DataTable
        hairline
        data={executions}
        keyExtractor={(exec) => exec.id}
        columns={[
          { key: 'reference', header: 'Reference', minWidth: 120, mobileLabel: true, render: (_v, exec) => (
            <span className="font-mono text-neutral-600 dark:text-neutral-300">
              {(exec as any).executionReference || exec.id?.slice(0, 8) || 'N/A'}
            </span>
          ) },
          { key: 'rule', header: 'Rule', minWidth: 140, dropOrder: 3, render: (_v, exec) => (
            <span className="body-strong">{(exec as any).ruleName || 'N/A'}</span>
          ) },
          { key: 'flow', header: 'Flow', minWidth: 170, dropOrder: 2, render: (_v, exec) => (
            <div className="text-caption">
              <div className="flex items-center gap-1">
                <span className="font-medium">{(exec as any).sourceEntityCode || ''}</span>
                <ArrowRight className="w-3 h-3 text-neutral-400" />
                <span className="font-medium">{(exec as any).targetEntityCode || ''}</span>
              </div>
              {((exec as any).sourceAccountNumber || (exec as any).targetAccountNumber) && (
                <div className="text-neutral-400 font-mono mt-0.5">
                  {(exec as any).sourceAccountNumber?.slice(-6) || ''}
                  {' → '}
                  {(exec as any).targetAccountNumber?.slice(-6) || ''}
                </div>
              )}
            </div>
          ) },
          { key: 'amount', header: 'Amount', align: 'right', minWidth: 170, mobileValue: true, render: (_v, exec) => (
            <div className="font-medium tracking-tight">
              <Amount
                value={(exec as any).sweepAmount || (exec as any).amountSwept || 0}
                currency={(exec as any).currencyCode || 'AED'}
              />
              {/* Balance change indicator */}
              {(exec as any).balanceBefore !== undefined && (exec as any).balanceAfter !== undefined && (
                <div className="text-caption text-neutral-400 font-normal">
                  {formatCurrency((exec as any).balanceBefore, (exec as any).currencyCode || 'AED')} → {formatCurrency((exec as any).balanceAfter, (exec as any).currencyCode || 'AED')}
                </div>
              )}
            </div>
          ) },
          { key: 'status', header: 'Status', minWidth: 130, render: (_v, exec) => (
            <>
              <Badge
                variant={
                  exec.status === 'SUCCESS' ? 'success' :
                  exec.status === 'FAILED' ? 'error' :
                  exec.status === 'SKIPPED' ? 'warning' :
                  'neutral'
                }
                size="sm"
              >
                {exec.status}
              </Badge>
              {/* Error message if failed */}
              {(exec as any).errorMessage && (
                <p className="text-caption text-error-500 dark:text-error-300 mt-1 max-w-[150px] truncate"
                   title={(exec as any).errorMessage}>
                  {(exec as any).errorMessage}
                </p>
              )}
            </>
          ) },
          { key: 'time', header: 'Time', minWidth: 110, dropOrder: 1, render: (_v, exec) => (
            <span className="body-sm">
              {(exec as any).executionTime
                ? formatRelativeTime((exec as any).executionTime)
                : (exec as any).executedAt
                  ? formatRelativeTime((exec as any).executedAt)
                  : (exec as any).completedAt
                    ? formatRelativeTime((exec as any).completedAt)
                    : 'N/A'}
            </span>
          ) },
          { key: 'actions', header: 'Actions', align: 'center', minWidth: 90, render: (_v, exec) => (
            <Button
              variant="ghost"
              size="sm"
              onClick={(e) => { e.stopPropagation(); onViewExecution(exec); }}
              title="View execution details"
            >
              <Eye className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
            </Button>
          ) },
        ]}
      />
    </Card>
  );
};
