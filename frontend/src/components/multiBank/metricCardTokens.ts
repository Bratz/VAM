// ============================================================================
// Multi-Bank Liquidity — MetricCard tone tokens.
//
// Phase 12 Task E: tokens moved to `components/ui/statTileTokens.ts` when
// MetricCard was promoted to the shared <StatTile>. Compatibility re-export
// kept so any straggler imports keep resolving; new code should import from
// `../ui/statTileTokens`.
// ============================================================================

export { TONE, STAT_VALUE_BY_TONE } from '../ui/statTileTokens';
