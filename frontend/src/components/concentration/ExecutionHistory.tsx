import React from 'react';
import { Clock, ArrowRight, Eye } from 'lucide-react';
import { Card, Button, Badge } from '../ui';
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
        <Clock className="w-8 h-8 text-neutral-300 dark:text-neutral-600 mx-auto mb-2" />
        <p className="text-neutral-500 dark:text-neutral-400">No execution history yet</p>
        <p className="text-xs text-neutral-400 dark:text-neutral-500 mt-1">Execute sweep rules to see history here</p>
      </Card>
    );
  }

  return (
    <Card className="animate-fade-in" style={{ animationDelay: '0.2s' }}>
      <div className="overflow-x-auto">
        <table className="w-full">
          <thead className="bg-neutral-50 dark:bg-primary-950 border-b border-neutral-100 dark:border-primary-800/60">
            <tr>
              <th className="text-left p-4 label">Reference</th>
              <th className="text-left p-4 label">Rule</th>
              <th className="text-left p-4 label">Flow</th>
              <th className="text-right p-4 label">Amount</th>
              <th className="text-left p-4 label">Status</th>
              <th className="text-left p-4 label">Time</th>
              <th className="text-center p-4 label">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-neutral-100 dark:divide-primary-800/60">
            {executions.map((exec) => (
              <tr key={exec.id} className="hover:bg-neutral-50 dark:hover:bg-primary-800/50 transition-colors">
                {/* Reference */}
                <td className="p-4 text-sm font-mono text-neutral-600 dark:text-neutral-300">
                  {(exec as any).executionReference || exec.id?.slice(0, 8) || 'N/A'}
                </td>

                {/* Rule Name */}
                <td className="p-4 text-sm font-medium text-neutral-900 dark:text-neutral-50">
                  {(exec as any).ruleName || 'N/A'}
                </td>

                {/* Flow: Source → Target */}
                <td className="p-4 text-xs">
                  <div className="flex items-center gap-1">
                    <span className="font-medium">{(exec as any).sourceEntityCode || ''}</span>
                    <ArrowRight className="w-3 h-3 text-neutral-400 dark:text-neutral-500" />
                    <span className="font-medium">{(exec as any).targetEntityCode || ''}</span>
                  </div>
                  {((exec as any).sourceAccountNumber || (exec as any).targetAccountNumber) && (
                    <div className="text-neutral-400 dark:text-neutral-500 font-mono mt-0.5">
                      {(exec as any).sourceAccountNumber?.slice(-6) || ''}
                      {' → '}
                      {(exec as any).targetAccountNumber?.slice(-6) || ''}
                    </div>
                  )}
                </td>

                {/* Amount */}
                <td className="p-4 text-right font-medium tracking-tight">
                  <Amount
                    value={(exec as any).sweepAmount || (exec as any).amountSwept || 0}
                    currency={(exec as any).currencyCode || 'AED'}
                  />
                  {/* Balance change indicator */}
                  {(exec as any).balanceBefore !== undefined && (exec as any).balanceAfter !== undefined && (
                    <div className="text-xs text-neutral-400 dark:text-neutral-500 font-normal">
                      {formatCurrency((exec as any).balanceBefore, (exec as any).currencyCode || 'AED')} → {formatCurrency((exec as any).balanceAfter, (exec as any).currencyCode || 'AED')}
                    </div>
                  )}
                </td>

                {/* Status */}
                <td className="p-4">
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
                    <p className="text-xs text-error-500 dark:text-error-300 mt-1 max-w-[150px] truncate"
                       title={(exec as any).errorMessage}>
                      {(exec as any).errorMessage}
                    </p>
                  )}
                </td>

                {/* Time */}
                <td className="p-4 text-sm text-neutral-600 dark:text-neutral-300">
                  {(exec as any).executionTime
                    ? formatRelativeTime((exec as any).executionTime)
                    : (exec as any).executedAt
                      ? formatRelativeTime((exec as any).executedAt)
                      : (exec as any).completedAt
                        ? formatRelativeTime((exec as any).completedAt)
                        : 'N/A'}
                </td>

                {/* Actions */}
                <td className="p-4 text-center">
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => onViewExecution(exec)}
                    title="View execution details"
                  >
                    <Eye className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Card>
  );
};
