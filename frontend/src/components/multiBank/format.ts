// ============================================================================
// Multi-Bank Liquidity — shared display formatting.
//
// Phase 11 morning-glance: extracted so BankSplitBar's legend and
// OverviewView's distribution legend render percentages identically.
// ============================================================================

/**
 * Format a percent with 1 decimal, trimming a trailing `.0`.
 *   22.4 → "22.4%"   ·   20 → "20%"   ·   100 → "100%"
 *
 * Replaces the previous whole-number rounding at the call sites, which
 * collapsed near-equal shares to the same label (e.g. 22.4 / 19.8 / 20.1
 * all rendering "20%") even though the bar widths stayed accurate.
 */
export function formatPct(pct: number): string {
  const scaled = pct * 10;
  const oneDp = Math.round(scaled) / 10;
  return oneDp % 1 === 0 ? `${oneDp.toFixed(0)}%` : `${oneDp.toFixed(1)}%`;
}
