// ============================================================================
// Multi-Bank Liquidity — bank-split colour palette.
//
// Colours are plain categorical identity (this app's teal `accent` for home, a
// neutral gray ramp for externals) — NOT the success/info semantic tokens,
// which this app reserves for status; a bank being home or external isn't a
// status judgement, and `accent` already matches the "HOME BANK" badge used
// alongside the split bar in ByBankView/ByCurrencyView.
//
// Lives in its own module (not BankSplitBar.tsx) so that file exports
// components only and Fast Refresh keeps working.
// ============================================================================

// Neutral gray ramp slots so multiple external banks each get a distinct
// shade without borrowing the `info` status family. Exported so every
// bank/currency distribution visual on this page (Overview's distribution
// bar included) draws from the same ramp instead of a copy that can drift.
export const HOME_BANK_COLOUR = 'bg-accent-500 dark:bg-accent-400';
export const EXTERNAL_BANK_RAMP = [
  'bg-primary-400 dark:bg-primary-500',
  'bg-primary-600 dark:bg-primary-300',
  'bg-neutral-400 dark:bg-neutral-500',
  'bg-primary-300 dark:bg-primary-600',
  'bg-neutral-500 dark:bg-neutral-400',
  'bg-primary-500 dark:bg-primary-400',
];
