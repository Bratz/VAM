import React from 'react';
import { Button, Badge } from '../ui';
import { Modal } from '../ui/enhanced';
import { formatCompactCurrency, formatRelativeTime } from '../../utils';
import { SweepRule } from '../../services/api';
import { SWEEP_TYPES, frequencyLabel, ruleStatusLabel } from './constants';
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

  const ccy = rule.currencyCode || 'AED';
  // One definition list instead of a card per field; type-specific rows only where they apply.
  const rows: [string, React.ReactNode][] = [
    ['Sweep type', <>{typeConfig.label}<span className="caption block">{typeConfig.desc}</span></>],
    ['Frequency', frequencyLabel(rule.frequency)],
    ['Currency', ccy],
    ['Priority', rule.priority || 1],
    ['Target account', <>{rule.targetEntityCode || '—'} <span className="font-mono text-neutral-500 dark:text-neutral-400">{rule.targetAccountNumber || ''}</span></>],
    ...(rule.sweepType === 'TARGET_BALANCE' && rule.targetAmount
      ? [['Target balance', formatCompactCurrency(rule.targetAmount, ccy)] as [string, React.ReactNode]] : []),
    ...(rule.sweepType === 'THRESHOLD'
      ? [['Min threshold', formatCompactCurrency(rule.thresholdMin || 0, ccy)],
         ['Max threshold', formatCompactCurrency(rule.thresholdMax || 0, ccy)]] as [string, React.ReactNode][] : []),
    ...(rule.sweepType === 'PERCENTAGE' && rule.percentage
      ? [['Sweep percentage', `${rule.percentage}%`] as [string, React.ReactNode]] : []),
    ['Total swept', formatCompactCurrency(rule.totalSwept || 0, ccy)],
    ['Last execution', rule.lastExecution ? formatRelativeTime(rule.lastExecution) : 'Never'],
  ];

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="Rule Details" size="md">
      <div className="space-y-6 animate-fade-in">
        <div className="flex items-start justify-between">
          <div>
            <h3 className="section-title">{rule.ruleName}</h3>
            <p className="text-body-sm text-neutral-500 dark:text-neutral-400 font-mono">{rule.ruleReference}</p>
          </div>
          <Badge variant={rule.status === 'ACTIVE' ? 'success' : 'warning'} size="md" dot>
            {ruleStatusLabel(rule.status)}
          </Badge>
        </div>

        <dl className="grid grid-cols-[10rem_1fr] gap-x-4 gap-y-2 text-body-sm">
          {rows.map(([label, value]) => (
            <React.Fragment key={label}>
              <dt className="text-neutral-500 dark:text-neutral-400">{label}</dt>
              <dd className="font-medium text-primary-900 dark:text-neutral-50">{value}</dd>
            </React.Fragment>
          ))}
        </dl>

        {/* Source Accounts — virtualized: a rule can have ~2000 sources */}
        {rule.sourceAccounts && rule.sourceAccounts.length > 0 && (
          <div>
            <p className="label mb-3">Source accounts ({rule.sourceAccounts.length})</p>
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
          </div>
        )}
      </div>

      <div className="flex justify-end mt-8 pt-6 border-t border-edge">
        <Button onClick={onClose}>Close</Button>
      </div>
    </Modal>
  );
};
