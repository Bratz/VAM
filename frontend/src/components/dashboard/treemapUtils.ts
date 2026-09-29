// Non-component helpers for EntityHierarchyTreemap. Kept out of the .tsx so
// that file exports components only and Fast Refresh keeps working.
import { BalanceHierarchyNode } from '../../services/api';

// consolidatedBalance is now a true recursive rollup computed server-side
// (BalanceStructureService.recomputeRollup) — own + all real descendants,
// CURRENCY_MIRROR excluded, for every node in the tree, not just the
// outer root. This used to have to redo that walk client-side (a
// "Corporate Root Account" GROUP node read consolidatedBalance: 0 while a
// SHADOW_ACCOUNT nested beneath it held AED 9.18M) — kept as a thin
// passthrough, rather than deleted, so every existing call site (the
// Dashboard's "Consolidated position" headline, EntityHierarchyTreemap's own
// treemapData) keeps working unchanged now that the backend does the sum.
export function sumBalance(node: BalanceHierarchyNode): number {
  return Math.max(node.consolidatedBalance, 0);
}
