// ============================================================================
// Cash Concentration shared config — sweep type catalogue + frequencies.
// Imported by the page and the extracted rule modals/cards so the taxonomy
// lives in one place.
// ============================================================================
export const SWEEP_TYPES = {
  ZERO_BALANCE: { label: 'Zero Balance', short: 'ZBA', desc: 'Sweep all funds to target, leaving zero balance' },
  TARGET_BALANCE: { label: 'Target Balance', short: 'Target', desc: 'Maintain a specific balance, sweep excess' },
  THRESHOLD: { label: 'Threshold', short: 'Threshold', desc: 'Sweep when balance exceeds maximum' },
  PERCENTAGE: { label: 'Percentage', short: 'Percent', desc: 'Sweep a percentage of available balance' },
} as const;

export const FREQUENCIES = [
  { value: 'REAL_TIME', label: 'Real-Time' },
  { value: 'DAILY', label: 'Daily' },
  { value: 'WEEKLY', label: 'Weekly' },
  { value: 'MONTHLY', label: 'Monthly' },
] as const;

/**
 * Every sweep execution status, one map for the list and the details. COMMITTED is an external-bank sweep instructed
 * at the other bank and awaiting its statement (SETTLED once confirmed, FAILED when rejected or expired).
 */
const EXECUTION_STATUS: Record<string, { label: string; variant: 'success' | 'warning' | 'error' | 'info' | 'neutral' }> = {
  SUCCESS: { label: 'Success', variant: 'success' },
  SETTLED: { label: 'Settled', variant: 'success' },
  COMMITTED: { label: 'Instructed · awaiting bank statement', variant: 'info' },
  FAILED: { label: 'Failed', variant: 'error' },
  PARTIAL: { label: 'Partial', variant: 'warning' },
  SKIPPED: { label: 'Skipped', variant: 'warning' },
  PENDING: { label: 'Pending', variant: 'neutral' },
};

export const executionStatus = (status: string) => EXECUTION_STATUS[status] ?? { label: status, variant: 'neutral' as const };

/** Statuses that moved money (or instructed it): Today's Swept counts these. */
export const SWEPT_STATUSES = ['SUCCESS', 'SETTLED', 'COMMITTED'];
