import React from 'react';
import { Layers, Building2, ArrowRight } from 'lucide-react';
import { Card, StatusIconBadge } from '../../ui';
import { Corporate, Program } from '../../../services/api';
import { SWEEP_TYPES } from '../constants';
import type { CreateRuleFormData } from './types';

// ============================================================================
// Step 4 — Review: summary, account flow, source account list
// ============================================================================
export interface Step4ReviewProps {
  formData: CreateRuleFormData;
  selectedCorporate?: Corporate;
  selectedProgram?: Program;
}

export const Step4Review: React.FC<Step4ReviewProps> = ({
  formData,
  selectedCorporate,
  selectedProgram,
}) => (
  <div className="space-y-6 animate-fade-in">
    {/* Summary Card */}
    <Card className="bg-primary-50/50 dark:bg-primary-500/10 border-primary-200/60 dark:border-primary-700">
      <div className="flex items-center gap-4 mb-6">
        <StatusIconBadge tone="primary" icon={Layers} size="lg" />
        <div>
          <h3 className="font-semibold text-primary-900 dark:text-neutral-50 text-lg">{formData.ruleName}</h3>
          <p className="text-sm text-neutral-500 dark:text-neutral-400">
            {SWEEP_TYPES[formData.sweepType as keyof typeof SWEEP_TYPES]?.label} • {formData.frequency}
          </p>
        </div>
      </div>

      <div className="grid grid-cols-2 gap-4">
        <div className="bg-white/60 dark:bg-primary-900/60 rounded-xl p-3 border border-neutral-200/60 dark:border-primary-800">
          <p className="text-xs text-neutral-500 dark:text-neutral-400 uppercase tracking-wider mb-1">Corporate</p>
          <p className="font-medium text-primary-900 dark:text-neutral-50 truncate">{selectedCorporate?.legalName || selectedCorporate?.tradeName || '-'}</p>
        </div>
        {selectedProgram && (
          <div className="bg-white/60 dark:bg-primary-900/60 rounded-xl p-3 border border-neutral-200/60 dark:border-primary-800">
            <p className="text-xs text-neutral-500 dark:text-neutral-400 uppercase tracking-wider mb-1">Program</p>
            <p className="font-medium text-primary-900 dark:text-neutral-50 truncate">{selectedProgram.programName}</p>
          </div>
        )}
        <div className="bg-white/60 dark:bg-primary-900/60 rounded-xl p-3 border border-neutral-200/60 dark:border-primary-800">
          <p className="text-xs text-neutral-500 dark:text-neutral-400 uppercase tracking-wider mb-1">Currency</p>
          <p className="font-medium text-primary-900 dark:text-neutral-50">{formData.currencyCode}</p>
        </div>
        <div className="bg-white/60 dark:bg-primary-900/60 rounded-xl p-3 border border-neutral-200/60 dark:border-primary-800">
          <p className="text-xs text-neutral-500 dark:text-neutral-400 uppercase tracking-wider mb-1">Priority</p>
          <p className="font-medium text-primary-900 dark:text-neutral-50">{formData.priority}</p>
        </div>
      </div>
    </Card>

    {/* Account Flow */}
    <Card>
      <p className="text-xs text-neutral-500 dark:text-neutral-400 uppercase tracking-wider mb-4">Account Flow</p>
      <div className="flex items-center gap-4">
        {/* Source Accounts */}
        <div className="flex-1">
          <div className="flex items-center gap-2 mb-2">
            <StatusIconBadge tone="warning" icon={Building2} size="sm" rounded="lg" />
            <div>
              <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">Source Accounts</p>
              <p className="text-xs text-neutral-500 dark:text-neutral-400">{formData.sourceAccounts.length} account(s)</p>
            </div>
          </div>
        </div>

        {/* Arrow */}
        <div className="flex flex-col items-center px-4">
          <ArrowRight className="w-6 h-6 text-primary-500 dark:text-primary-200" />
          <p className="text-xs text-neutral-500 dark:text-neutral-400 mt-1">Sweep</p>
        </div>

        {/* Target Account */}
        <div className="flex-1">
          <div className="flex items-center gap-2">
            <StatusIconBadge tone="success" icon={Building2} size="sm" rounded="lg" />
            <div>
              <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{formData.targetEntityCode || 'Target'}</p>
              <p className="text-xs text-neutral-500 dark:text-neutral-400 font-mono">{formData.targetAccountNumber}</p>
            </div>
          </div>
        </div>
      </div>
    </Card>

    {/* Source Accounts List */}
    {formData.sourceAccounts.length > 0 && (
      <div>
        <p className="text-sm font-medium text-primary-900 dark:text-neutral-50 mb-3">Source Accounts</p>
        <Card padding="none" className="divide-y divide-neutral-100 dark:divide-primary-800/60 max-h-32 overflow-y-auto">
          {formData.sourceAccounts.map((acc) => (
            <div key={acc.accountId} className="px-4 py-3 flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="w-8 h-8 rounded-lg bg-neutral-100 dark:bg-primary-800 flex items-center justify-center">
                  <Building2 className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
                </div>
                <span className="text-sm font-medium text-primary-900 dark:text-neutral-50">{acc.entityName}</span>
              </div>
              <span className="text-xs text-neutral-500 dark:text-neutral-400 font-mono">{acc.accountNumber}</span>
            </div>
          ))}
        </Card>
      </div>
    )}
  </div>
);
