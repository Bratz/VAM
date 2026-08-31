// ============================================================================
// Shared vocabulary for the two VA-creation surfaces:
//   - VaCreateModal          (Virtual Accounts page — dimension wizard)
//   - CreateTransactionVaModal (Balance Hierarchy page — contextual modal)
//
// Both flows write through the same backend path
// (VirtualAccountService.createWithParentNode); this module keeps their UI
// vocabulary identical too — one purpose list, one currency control with the
// same mirror hint. Extend HERE, not in the individual modals.
// ============================================================================

import React from 'react';
import { CurrencyPicker } from '../ui/CurrencyPicker';

/** Canonical account-purpose list — the union both modals previously split. */
export const PURPOSE_OPTIONS: ReadonlyArray<{ value: string; label: string }> = [
  { value: 'OPERATING', label: 'Operating (General Purpose)' },
  { value: 'COLLECTIONS', label: 'Collections (Receivables)' },
  { value: 'PAYABLES', label: 'Payables (Disbursements)' },
  { value: 'PAYROLL', label: 'Payroll' },
  { value: 'TAXES', label: 'Taxes' },
  { value: 'ESCROW', label: 'Escrow' },
  { value: 'TREASURY', label: 'Treasury' },
  { value: 'INTERCOMPANY', label: 'Intercompany' },
];

export const PurposeSelect: React.FC<{
  value: string;
  onChange: (value: string) => void;
  className?: string;
}> = ({ value, onChange, className }) => (
  <select
    value={value}
    onChange={(e) => onChange(e.target.value)}
    className={className || 'w-full px-3 py-2 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm'}
  >
    {PURPOSE_OPTIONS.map((o) => (
      <option key={o.value} value={o.value}>{o.label}</option>
    ))}
  </select>
);

/**
 * One line of side-effect transparency shown in both creation modals:
 * treasurers should not later discover system accounts they never knowingly
 * created.
 */
export const CreationSideEffectsNote: React.FC = () => (
  <p className="text-xs text-neutral-500 dark:text-neutral-400">
    Creating this account also ensures a settlement VA at its hierarchy level,
    and a foreign-currency account gets a currency mirror automatically.
  </p>
);

/**
 * Currency picker + the mirror hint: whenever the chosen currency differs
 * from the base (program / parent-node currency), the backend rolls the VA
 * up through an auto-created currency mirror — say so, in both flows.
 */
export const CurrencyFieldWithMirrorHint: React.FC<{
  value: string;
  onChange: (currency: string) => void;
  baseCurrency?: string;
  extra?: string[];
}> = ({ value, onChange, baseCurrency, extra = ['SAR'] }) => {
  const isForeign = !!baseCurrency && !!value && value !== baseCurrency;
  return (
    <div>
      <CurrencyPicker value={value} onChange={onChange} extra={extra} />
      {isForeign && (
        <p className="text-xs text-info-600 dark:text-info-300 mt-1">
          Differs from base currency ({baseCurrency}) — a currency mirror will roll it up.
        </p>
      )}
    </div>
  );
};
