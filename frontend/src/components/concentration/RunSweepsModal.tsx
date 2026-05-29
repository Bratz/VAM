import React, { useState } from 'react';
import { Layers, CheckCircle, Play, Loader2 } from 'lucide-react';
import { Card, Button, Badge, StatusIconBadge } from '../ui';
import { Modal } from '../ui/enhanced';
import { formatCompactCurrency, cn } from '../../utils';
import { SweepRule } from '../../services/api';

// ============================================================================
// RUN SWEEPS MODAL
// ============================================================================
export interface RunSweepsModalProps {
  isOpen: boolean;
  onClose: () => void;
  rules: SweepRule[];
  onRun: (ruleIds?: string[]) => Promise<any>;
}

export const RunSweepsModal: React.FC<RunSweepsModalProps> = ({ isOpen, onClose, rules, onRun }) => {
  const [phase, setPhase] = useState<'select' | 'running' | 'complete'>('select');
  const [selectedRules, setSelectedRules] = useState<Set<string>>(new Set());
  const [results, setResults] = useState<any>(null);

  const activeRules = rules.filter(r => r.status === 'ACTIVE');

  const handleRun = async () => {
    setPhase('running');
    try {
      const result = await onRun(selectedRules.size > 0 ? Array.from(selectedRules) : undefined);
      setResults(result);
      setPhase('complete');
    } catch (err) {
      console.error('Sweep execution failed:', err);
      setPhase('select');
    }
  };

  const handleClose = () => {
    setPhase('select');
    setSelectedRules(new Set());
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
          <p className="text-neutral-500 dark:text-neutral-400 mt-2">Please wait while funds are being transferred</p>
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
    </Modal>
  );
};
