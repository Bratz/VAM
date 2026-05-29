// ============================================================================
// Multi-Bank Liquidity — MetricCard tone tokens.
//
// Split out of MetricCard.tsx so the file can export only its component (and
// keep React Fast Refresh happy — `react-refresh/only-export-components`
// flags any non-component export from a component file).
// ============================================================================

export const TONE: Record<string, { bg: string; fg: string; ring: string }> = {
  primary: { bg: 'bg-primary-100 dark:bg-primary-800/60',     fg: 'text-primary-700 dark:text-primary-200',     ring: 'ring-primary-200 dark:ring-primary-700/40' },
  success: { bg: 'bg-success-100 dark:bg-success-500/15',     fg: 'text-success-700 dark:text-success-300',     ring: 'ring-success-200 dark:ring-success-500/30' },
  warning: { bg: 'bg-warning-100 dark:bg-warning-500/15',     fg: 'text-warning-700 dark:text-warning-300',     ring: 'ring-warning-200 dark:ring-warning-500/30' },
  danger:  { bg: 'bg-error-100 dark:bg-error-500/15',         fg: 'text-error-700  dark:text-error-300',        ring: 'ring-error-200  dark:ring-error-500/30' },
  accent:  { bg: 'bg-accent-100 dark:bg-accent-500/15',       fg: 'text-accent-700  dark:text-accent-300',      ring: 'ring-accent-200  dark:ring-accent-500/30' },
  neutral: { bg: 'bg-neutral-100 dark:bg-primary-800/60',     fg: 'text-neutral-600 dark:text-neutral-300',     ring: 'ring-neutral-200 dark:ring-primary-700/40' },
};

// Map tone -> semantic stat-value utility (Phase 9 display tier — Fraunces +
// tabular-nums). `accent` and `neutral` fall back to `.stat-value-sm`
// (primary-900/neutral-50 text) — the brand accent stays on the icon medallion
// only; the headline number reads as a neutral magnitude.
export const STAT_VALUE_BY_TONE: Record<keyof typeof TONE, string> = {
  primary: 'stat-value-sm',
  success: 'stat-value-success',
  warning: 'stat-value-warning',
  danger:  'stat-value-error',
  accent:  'stat-value-sm',
  neutral: 'stat-value-sm',
};
