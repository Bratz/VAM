// ============================================================================
// Multi-Bank Liquidity — small metric card.
//
// Phase 12 Task E: the component formerly defined here was promoted to the
// shared design-system tile — `components/ui/StatTile`. This file remains as
// a thin compatibility re-export so the multiBank views (OverviewView,
// ByBankView) keep importing `{ MetricCard } from './MetricCard'` unchanged.
//
// New call sites should import `{ StatTile } from '../ui/StatTile'` (or via
// the `components/ui` barrel) directly.
// ============================================================================

export { StatTile as MetricCard } from '../ui/StatTile';
export type { StatTileProps as MetricCardProps } from '../ui/StatTile';
