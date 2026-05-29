import React from 'react';
import { Loader2, Building2, CheckCircle } from 'lucide-react';
import { Card, Badge, Select } from '../../ui';
import { formatCompactCurrency, cn } from '../../../utils';
import { VirtualAccount } from '../../../services/api';
import type { CreateRuleFormData } from './types';

// ============================================================================
// Step 2 — Accounts: Target (concentration) account + source account picker
// ============================================================================
export interface Step2AccountsProps {
  formData: CreateRuleFormData;
  accounts: VirtualAccount[];
  loadingAccounts: boolean;
  onTargetAccountChange: (accountId: string) => void;
  onToggleSourceAccount: (account: VirtualAccount) => void;
}

export const Step2Accounts: React.FC<Step2AccountsProps> = ({
  formData,
  accounts,
  loadingAccounts,
  onTargetAccountChange,
  onToggleSourceAccount,
}) => (
  <div className="space-y-6 animate-fade-in">
    {/* Target Account */}
    <Select
      label="Target Account (Concentration Account)"
      value={formData.targetAccountId}
      onChange={(e) => onTargetAccountChange(e.target.value)}
      disabled={loadingAccounts}
      placeholder="Select Target Account..."
      options={accounts.map((acc) => ({
        value: acc.id,
        label: `${acc.vaName} - ${acc.vaNumber || acc.viban} (${acc.currencyCode})`
      }))}
      hint={formData.targetAccountNumber
        ? `Selected: ${formData.targetAccountNumber} | Entity: ${formData.targetEntityCode}`
        : loadingAccounts ? 'Loading accounts...' : 'Select the concentration account where funds will be swept to'
      }
    />

    {/* Source Accounts */}
    <div>
      <label className="block text-sm font-medium text-primary-900 dark:text-neutral-50 mb-3">
        Source Accounts
      </label>
      <p className="text-sm text-neutral-500 dark:text-neutral-400 mb-3">Select accounts to sweep funds from</p>

      {loadingAccounts ? (
        <Card className="flex items-center justify-center py-12">
          <Loader2 className="w-6 h-6 animate-spin text-primary-600 dark:text-primary-200" />
        </Card>
      ) : accounts.length === 0 ? (
        <Card className="text-center py-12">
          <div className="w-12 h-12 rounded-xl bg-neutral-100 dark:bg-primary-800 flex items-center justify-center mx-auto mb-3">
            <Building2 className="w-6 h-6 text-neutral-400 dark:text-neutral-500" />
          </div>
          <p className="text-neutral-500 dark:text-neutral-400">No accounts found for this corporate</p>
        </Card>
      ) : (
        <Card padding="none" className="divide-y divide-neutral-100 dark:divide-primary-800/60 max-h-64 overflow-y-auto">
          {accounts
            .filter(acc => acc.id !== formData.targetAccountId)
            .map((acc) => {
              const isSelected = formData.sourceAccounts.some(s => s.accountId === acc.id);
              return (
                <label
                  key={acc.id}
                  className={cn(
                    'flex items-center gap-4 p-4 cursor-pointer transition-all duration-150',
                    isSelected ? 'bg-primary-50 dark:bg-primary-800/40' : 'hover:bg-neutral-50 dark:hover:bg-primary-800/50'
                  )}
                  onClick={() => onToggleSourceAccount(acc)}
                >
                  <div className={cn(
                    'w-5 h-5 rounded-md border-2 flex items-center justify-center transition-all duration-150',
                    isSelected
                      ? 'bg-primary-600 border-primary-600'
                      : 'border-neutral-300 dark:border-primary-700 hover:border-primary-400 dark:hover:border-primary-700'
                  )}>
                    {isSelected && <CheckCircle className="w-3.5 h-3.5 text-white" />}
                  </div>
                  <div className="w-10 h-10 rounded-xl bg-neutral-100 dark:bg-primary-800 flex items-center justify-center flex-shrink-0">
                    <Building2 className="w-5 h-5 text-neutral-500 dark:text-neutral-400" />
                  </div>
                  <div className="flex-1 min-w-0">
                    <p className="font-medium text-sm text-primary-900 dark:text-neutral-50 truncate">{acc.vaName}</p>
                    <p className="text-xs text-neutral-500 dark:text-neutral-400 font-mono truncate">{acc.vaNumber || acc.viban}</p>
                  </div>
                  <div className="text-right flex-shrink-0">
                    <Badge variant="neutral" size="sm">{acc.currencyCode}</Badge>
                    <p className="text-xs text-neutral-500 dark:text-neutral-400 mt-1">
                      {formatCompactCurrency(acc.currentBalance || 0, acc.currencyCode)}
                    </p>
                  </div>
                </label>
              );
            })}
        </Card>
      )}
      {formData.sourceAccounts.length > 0 && (
        <div className="flex items-center gap-2 mt-3">
          <Badge variant="success" size="sm" dot>
            {formData.sourceAccounts.length} account(s) selected
          </Badge>
        </div>
      )}
    </div>
  </div>
);
