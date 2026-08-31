// ============================================================================
// Shared, framework-free helpers for the Phase-2 score formulas.
//
// Each formula emits PER-CURRENCY annual amounts (live vs proposed). The
// FX-honesty rule is applied ONCE, centrally, in scoreCalculator — formulas
// stay FX-agnostic and trivially unit-testable.
// ============================================================================

import type {
  SimulatedRule,
  SimulatedShadow,
  SimulatorPhysicalAccount,
} from '../../../components/simulator/types';

/** Per-currency annual amounts for one score line (currency → amount). */
export interface PerCurrencyLine {
  live: Record<string, number>;
  proposed: Record<string, number>;
  detail: string;
}

export function emptyLine(detail = ''): PerCurrencyLine {
  return { live: {}, proposed: {}, detail };
}

export function addAmount(
  bucket: Record<string, number>,
  currency: string,
  amount: number,
): void {
  if (!currency || !Number.isFinite(amount) || amount === 0) return;
  bucket[currency] = (bucket[currency] ?? 0) + amount;
}

/** Deposit rate (%) for an account: effective, else nominal, else 0. */
export function depositRatePct(acc?: SimulatorPhysicalAccount): number {
  if (!acc) return 0;
  return acc.effectiveInterestRate ?? acc.interestRate ?? 0;
}

export function accountOf(
  shadow: SimulatedShadow,
  accountsById: Map<string, SimulatorPhysicalAccount>,
): SimulatorPhysicalAccount | undefined {
  return accountsById.get(shadow.physicalAccountId);
}

/**
 * Single-level concentration topology from the proposed rules:
 *  - `sweptOut`  : localIds that are a source in ≥1 rule (their cash moves).
 *  - `childrenOf`: targetLocalId → [source child localIds] sweeping into it.
 *
 * Multi-tier concentration is intentionally NOT modelled in V2 (disclosed
 * assumption); ZBA semantics assumed (a swept child contributes its whole
 * balance to its target).
 */
export function concentrationTopology(rules: SimulatedRule[]): {
  sweptOut: Set<string>;
  childrenOf: Map<string, string[]>;
} {
  const sweptOut = new Set<string>();
  const childrenOf = new Map<string, string[]>();
  for (const r of rules) {
    if (!r.targetLocalId) continue;
    for (const src of r.sourceLocalIds ?? []) {
      if (!src || src === r.targetLocalId) continue;
      sweptOut.add(src);
      const list = childrenOf.get(r.targetLocalId) ?? [];
      list.push(src);
      childrenOf.set(r.targetLocalId, list);
    }
  }
  return { sweptOut, childrenOf };
}

export function round2(n: number): number {
  return Math.round((n + Number.EPSILON) * 100) / 100;
}

// R3 (2026-05-16): `normalizePoolPct` was RETIRED. The live pool engine
// (`NotionalPoolService.calculateInterest`) divides
// `notional_pools.interest_rate` by 36500, i.e. it IS an annual percent
// (3.5 ⇒ 3.5%/yr, 0.0250 ⇒ 0.025%/yr). The old >1⇒% / ≤1⇒×100 heuristic
// disagreed 100× for sub-1 rates; pool rates are now used directly
// (engine-truth — one source of truth with the live subsystem).
