// Non-component half of createShared.tsx — kept in its own module so that
// file exports components only and Fast Refresh keeps working.

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
