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
