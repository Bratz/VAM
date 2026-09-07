import React, { useEffect, useRef, useState } from 'react';
import { Layers, CheckCircle, Play, Loader2, XCircle } from 'lucide-react';
import { Card, Button, Badge, StatusIconBadge } from '../ui';
import { Modal } from '../ui/enhanced';
import { formatCompactCurrency, cn } from '../../utils';
import { SweepRule, SweepRunStatus, sweepingApi } from '../../services/api';

// ============================================================================
// RUN SWEEPS MODAL
//
// 'select' -> POST /sweeping/execute-async (fire-and-forget, returns a runId)
// -> 'running' (polls GET /sweeping/runs/{runId} every 1.5s for live counts)
// -> 'complete' | 'failed' once the run terminates.
// ============================================================================
export interface RunSweepsModalProps {
  isOpen: boolean;
  onClose: () => void;
  rules: SweepRule[];
  /** Called once a run terminates (completed or failed) so the parent can refresh rules/history. */
  onComplete?: () => void;
}

const POLL_INTERVAL_MS = 1500;

export const RunSweepsModal: React.FC<RunSweepsModalProps> = ({ isOpen, onClose, rules, onComplete }) => {
  const [phase, setPhase] = useState<'select' | 'running' | 'complete' | 'failed'>('select');
  const [selectedRules, setSelectedRules] = useState<Set<string>>(new Set());
  const [runStatus, setRunStatus] = useState<SweepRunStatus | null>(null);
  const [results, setResults] = useState<any>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const activeRules = rules.filter(r => r.status === 'ACTIVE');

  const stopPolling = () => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  };

  // Stop polling if the modal unmounts mid-run.
  useEffect(() => () => stopPolling(), []);

  const handleRun = async () => {
    setPhase('running');
    setRunStatus(null);
    const ruleIds = selectedRules.size > 0 ? Array.from(selectedRules) : undefined;

    try {
      const res = await sweepingApi.executeAsync(ruleIds);
      const runId = res?.data?.runId;
      if (!runId) throw new Error('execute-async did not return a runId');

      pollRef.current = setInterval(async () => {
        try {
          const statusRes = await sweepingApi.getRunStatus(runId);
          const status = statusRes?.data;
          if (!status) return;
          setRunStatus(status);

          if (status.status === 'COMPLETED' || status.status === 'FAILED') {
            stopPolling();
            setResults({
              totalRules: ruleIds?.length ?? activeRules.length,
              successCount: status.successCount,
              failedCount: status.failedCount,
            });
            setPhase(status.status === 'COMPLETED' ? 'complete' : 'failed');
            // NOT calling onComplete() here: CashConcentrationPage's fetchRules
            // sets a page-level `loading` flag that unmounts this whole modal
            // tree while it refetches — that would blow away the just-set
            // 'complete'/'failed' phase before the user ever sees the result.
            // Defer the parent refresh to handleClose instead (see below).
          }
        } catch (err) {
          console.error('Failed to poll sweep run status:', err);
        }
      }, POLL_INTERVAL_MS);
    } catch (err) {
      console.error('Sweep execution failed:', err);
      setPhase('select');
    }
  };

  const handleClose = () => {
    // A run actually finished (as opposed to the user cancelling from the
    // 'select' phase with nothing executed) — refresh the underlying rules
    // list/history now that the completion UI has been shown and dismissed.
    if (phase === 'complete' || phase === 'failed') {
      onComplete?.();
    }
    stopPolling();
    setPhase('select');
    setSelectedRules(new Set());
    setRunStatus(null);
    setResults(null);
    onClose();
  };

  const toggleRule = (ruleId: string) => {
    const newSet = new Set(selectedRules);
    if (newSet.has(ruleId)) {
      newSet.delete(ruleId);
    } else {
      newSet.add(ruleId);
    }
    setSelectedRules(newSet);
  };

  const progressPct = runStatus && runStatus.sourcesTotal > 0
    ? Math.min(100, Math.round((runStatus.sourcesProcessed / runStatus.sourcesTotal) * 100))
    : 0;

  return (
    <Modal isOpen={isOpen} onClose={handleClose} title="Run Sweeps" size="md">
      {phase === 'select' && (
        <div className="animate-fade-in">
          <p className="text-neutral-600 dark:text-neutral-300 mb-6">
            Select specific rules to execute or run all active sweep rules at once.
          </p>

          {activeRules.length === 0 ? (
            <Card className="text-center py-12">
              <div className="w-12 h-12 rounded-xl bg-neutral-100 dark:bg-primary-800 flex items-center justify-center mx-auto mb-3">
                <Layers className="w-6 h-6 text-neutral-400 dark:text-neutral-500" />
              </div>
              <p className="text-neutral-500 dark:text-neutral-400">No active rules to execute</p>
              <p className="text-xs text-neutral-400 dark:text-neutral-500 mt-1">Activate some rules first</p>
            </Card>
          ) : (
            <Card padding="none" className="divide-y divide-neutral-100 dark:divide-primary-800/60 max-h-64 overflow-y-auto mb-6">
              {activeRules.map((rule) => {
                const isSelected = selectedRules.has(rule.id);
                return (
                  <label
                    key={rule.id}
                    className={cn(
                      'flex items-center gap-4 p-4 cursor-pointer transition-all duration-150',
                      isSelected ? 'bg-primary-50 dark:bg-primary-800/40' : 'hover:bg-neutral-50 dark:hover:bg-primary-800/50'
                    )}
                    onClick={() => toggleRule(rule.id)}
                  >
                    <div className={cn(
                      'w-5 h-5 rounded-md border-2 flex items-center justify-center transition-all duration-150',
                      isSelected
                        ? 'bg-primary-600 border-primary-600'
                        : 'border-neutral-300 dark:border-primary-700 hover:border-primary-400 dark:hover:border-primary-700'
                    )}>
                      {isSelected && <CheckCircle className="w-3.5 h-3.5 text-white" />}
                    </div>
                    <StatusIconBadge tone="primary" icon={Layers} className="flex-shrink-0" />
                    <div className="flex-1 min-w-0">
                      <p className="font-medium text-sm text-primary-900 dark:text-neutral-50">{rule.ruleName}</p>
                      <p className="text-xs text-neutral-500 dark:text-neutral-400 font-mono">{rule.ruleReference}</p>
                    </div>
                    <Badge variant="success" size="sm" dot>Active</Badge>
                  </label>
                );
              })}
            </Card>
          )}

          {selectedRules.size > 0 && (
            <div className="mb-6">
              <Badge variant="primary" size="sm">
                {selectedRules.size} rule(s) selected
              </Badge>
            </div>
          )}

          <div className="flex justify-end gap-3 pt-6 border-t border-neutral-200 dark:border-primary-800">
            <Button variant="outline" onClick={handleClose}>Cancel</Button>
            <Button
              onClick={handleRun}
              disabled={activeRules.length === 0}
              leftIcon={<Play className="w-4 h-4" />}
            >
              {selectedRules.size > 0 ? `Run ${selectedRules.size} Rule(s)` : 'Run All Active'}
            </Button>
          </div>
        </div>
      )}

      {phase === 'running' && (
        <div className="text-center py-12 animate-fade-in">
          <div className="w-16 h-16 rounded-2xl bg-primary-100 dark:bg-primary-700 flex items-center justify-center mx-auto mb-6">
            <Loader2 className="w-8 h-8 animate-spin text-primary-600 dark:text-primary-200" />
          </div>
          <p className="body-lg">Executing Sweeps...</p>

          {runStatus && runStatus.sourcesTotal > 0 ? (
            <div className="mt-4 max-w-xs mx-auto">
              <div className="h-2 rounded-full bg-neutral-100 dark:bg-primary-800 overflow-hidden">
                <div
                  className="h-full bg-primary-600 dark:bg-primary-400 transition-all duration-300"
                  style={{ width: `${progressPct}%` }}
                />
              </div>
              <p className="text-sm text-neutral-500 dark:text-neutral-400 mt-2">
                {runStatus.sourcesProcessed} / {runStatus.sourcesTotal} source accounts processed
              </p>
            </div>
          ) : (
            <p className="text-neutral-500 dark:text-neutral-400 mt-2">Please wait while funds are being transferred</p>
          )}
        </div>
      )}

      {phase === 'complete' && results && (
        <div className="space-y-6 animate-fade-in">
          <div className="text-center py-6">
            <div className="w-16 h-16 rounded-2xl bg-success-100 dark:bg-success-500/20 flex items-center justify-center mx-auto mb-4">
              <CheckCircle className="w-8 h-8 text-success-600 dark:text-success-300" />
            </div>
            <p className="body-lg">Sweeps Complete</p>
          </div>

          <div className="grid grid-cols-3 gap-4">
            <Card padding="sm" className="bg-primary-50/50 dark:bg-primary-800/40 border-primary-200/60 dark:border-primary-700 text-center">
              <p className="stat-value-sm">{results.totalRules || 0}</p>
              <p className="text-xs text-primary-600 dark:text-primary-200 uppercase tracking-wider mt-1">Executed</p>
            </Card>
            <Card padding="sm" className="bg-success-50/50 dark:bg-success-500/10 border-success-200/60 dark:border-success-500/30 text-center">
              <p className="stat-value-success">{results.successCount || 0}</p>
              <p className="text-xs text-success-600 dark:text-success-300 uppercase tracking-wider mt-1">Successful</p>
            </Card>
            <Card padding="sm" className="bg-error-50/50 dark:bg-error-500/10 border-error-200/60 dark:border-error-500/30 text-center">
              <p className="stat-value-error">{results.failedCount || 0}</p>
              <p className="text-xs text-error-600 dark:text-error-300 uppercase tracking-wider mt-1">Failed</p>
            </Card>
          </div>

          {results.totalSwept && (
            <Card className="bg-success-50/50 dark:bg-success-500/10 border-success-200/60 dark:border-success-500/30 text-center">
              <p className="text-xs text-success-600 dark:text-success-300 uppercase tracking-wider mb-1">Total Amount Swept</p>
              <p className="stat-value-sm text-success-700 dark:text-success-300">
                {formatCompactCurrency(results.totalSwept, 'AED')}
              </p>
            </Card>
          )}

          <div className="flex justify-end pt-6 border-t border-neutral-200 dark:border-primary-800">
            <Button onClick={handleClose}>Close</Button>
          </div>
        </div>
      )}

      {phase === 'failed' && (
        <div className="space-y-6 animate-fade-in">
          <div className="text-center py-6">
            <div className="w-16 h-16 rounded-2xl bg-error-100 dark:bg-error-500/20 flex items-center justify-center mx-auto mb-4">
              <XCircle className="w-8 h-8 text-error-600 dark:text-error-300" />
            </div>
            <p className="body-lg">Sweep Run Failed</p>
            <p className="text-neutral-500 dark:text-neutral-400 mt-2">Check execution history for details.</p>
          </div>

          {results && (
            <div className="grid grid-cols-3 gap-4">
              <Card padding="sm" className="bg-primary-50/50 dark:bg-primary-800/40 border-primary-200/60 dark:border-primary-700 text-center">
                <p className="stat-value-sm">{results.totalRules || 0}</p>
                <p className="text-xs text-primary-600 dark:text-primary-200 uppercase tracking-wider mt-1">Executed</p>
              </Card>
              <Card padding="sm" className="bg-success-50/50 dark:bg-success-500/10 border-success-200/60 dark:border-success-500/30 text-center">
                <p className="stat-value-success">{results.successCount || 0}</p>
                <p className="text-xs text-success-600 dark:text-success-300 uppercase tracking-wider mt-1">Successful</p>
              </Card>
              <Card padding="sm" className="bg-error-50/50 dark:bg-error-500/10 border-error-200/60 dark:border-error-500/30 text-center">
                <p className="stat-value-error">{results.failedCount || 0}</p>
                <p className="text-xs text-error-600 dark:text-error-300 uppercase tracking-wider mt-1">Failed</p>
              </Card>
            </div>
          )}

          <div className="flex justify-end pt-6 border-t border-neutral-200 dark:border-primary-800">
            <Button onClick={handleClose}>Close</Button>
          </div>
        </div>
      )}
    </Modal>
  );
};
