// ============================================================================
// Interest yield (score line). Pure, per-currency.
//
// R2: rate is BASKET-aware (Pool vs External). R3 (2026-05-16) extends the
// Pool basket to PROPOSED notional pools and adds hybrid attribution A:
//
//   • Live      : every shadow's balance at its CURRENT rate — its live-pool
//                 rate if it is already in a live pool, else its deposit
//                 rate. A *proposed* pool does NOT exist in "live", so it
//                 never lifts the live baseline (Δ then = the real uplift).
//   • Proposed  : a proposed-pool member earns the SimulatedPool rate
//                 (engine-truth annual %); a non-pool shadow keeps the
//                 existing ZBA concentration math (unchanged — zero
//                 regression). Hybrid (a member ALSO swept): the sweep moves
//                 its physical amount, the RESIDUAL stays and earns the pool
//                 rate (attribution A); the swept part flows to its target.
//   • Internal/IHB : DEFERRED — disclosed, not faked.
//
// `proposedContributions` is the single generator for the proposed side, used
// by BOTH interestYield and interestBaskets so they provably cannot drift.
// ============================================================================

import type {
  ScoreInputs,
  SimulatedPool,
  SimulatedRule,
  SimulatorPhysicalAccount,
} from '../../../components/simulator/types';
import {
  PerCurrencyLine,
  accountOf,
  addAmount,
  concentrationTopology,
  depositRatePct,
  emptyLine,
} from './shared';

type Basket = 'pool' | 'external';

/**
 * Residual balance RETAINED after the sweep rule physically moves its part
 * (attribution A). Clamped to [0, bal] so residual + swept = bal exactly
 * (the no-double-count invariant holds by construction).
 */
function residualAfterSweep(
  bal: number,
  rule: SimulatedRule | undefined,
): number {
  if (bal <= 0 || !rule) return 0;
  let residual: number;
  switch (rule.sweepType) {
    case 'TARGET_BALANCE':
      residual = Math.max(0, rule.targetAmount ?? 0);
      break;
    case 'THRESHOLD':
      residual = Math.max(0, rule.thresholdMax ?? bal);
      break;
    case 'PERCENTAGE': {
      const pct = Math.min(1, Math.max(0, (rule.percentage ?? 0) / 100));
      residual = bal * (1 - pct);
      break;
    }
    case 'ZERO_BALANCE':
    default:
      residual = 0;
  }
  return Math.min(bal, Math.max(0, residual));
}

/** memberLocalId → its proposed pool (validation guarantees ≤1). */
function poolByShadow(input: ScoreInputs): Map<string, SimulatedPool> {
  const m = new Map<string, SimulatedPool>();
  for (const p of input.proposedPools ?? []) {
    for (const id of p.memberLocalIds ?? []) m.set(id, p);
  }
  return m;
}

/** sourceLocalId → the first rule it is swept by (residual basis). */
function firstSweepRuleBySource(
  rules: SimulatedRule[],
): Map<string, SimulatedRule> {
  const m = new Map<string, SimulatedRule>();
  for (const r of rules) {
    if (!r.targetLocalId) continue;
    for (const src of r.sourceLocalIds ?? []) {
      if (!src || src === r.targetLocalId || m.has(src)) continue;
      m.set(src, r);
    }
  }
  return m;
}

/** CURRENT rate: live-pool rate if already live-pooled, else deposit. */
function liveRatePct(
  input: ScoreInputs,
  acc: SimulatorPhysicalAccount,
): number {
  const live = input.pooledByPhys?.get(acc.id);
  return live ? Math.max(0, live.interestRate ?? 0) : depositRatePct(acc);
}

/** PROPOSED rate + basket: proposed pool wins, else live pool, else deposit. */
function proposedRate(
  input: ScoreInputs,
  localId: string,
  acc: SimulatorPhysicalAccount,
  pbs: Map<string, SimulatedPool>,
): { ratePct: number; basket: Basket } {
  const proposed = pbs.get(localId);
  if (proposed) {
    return { ratePct: Math.max(0, proposed.poolRatePct ?? 0), basket: 'pool' };
  }
  const live = input.pooledByPhys?.get(acc.id);
  if (live) {
    return { ratePct: Math.max(0, live.interestRate ?? 0), basket: 'pool' };
  }
  return { ratePct: depositRatePct(acc), basket: 'external' };
}

/**
 * Proposed-side per-shadow interest contribution (amount + basket). Single
 * source of truth for interestYield's proposed branch AND interestBaskets.
 */
function proposedContributions(
  input: ScoreInputs,
): Array<{ ccy: string; amount: number; basket: Basket }> {
  const { proposedShadows, proposedRules, accountsById } = input;
  const pbs = poolByShadow(input);
  const ruleOf = firstSweepRuleBySource(proposedRules);
  const { childrenOf } = concentrationTopology(proposedRules);
  const byLocalId = new Map(proposedShadows.map((s) => [s.localId, s]));
  const out: Array<{ ccy: string; amount: number; basket: Basket }> = [];

  for (const shadow of proposedShadows) {
    const acc = accountOf(shadow, accountsById);
    if (!acc) continue;
    const ccy = acc.currencyCode || shadow.snapshotCurrencyCode;
    const bal = acc.currentBalance ?? 0;
    const sweptRule = ruleOf.get(shadow.localId);

    if (sweptRule) {
      // Swept. A hybrid pool member keeps its residual at the pool rate;
      // a non-pool swept shadow contributes 0 (ZBA — unchanged).
      if (pbs.has(shadow.localId)) {
        const residual = residualAfterSweep(bal, sweptRule);
        const { ratePct, basket } = proposedRate(
          input,
          shadow.localId,
          acc,
          pbs,
        );
        out.push({ ccy, amount: residual * (ratePct / 100), basket });
      }
      continue;
    }

    // Target / standalone: earns on its concentrated balance. Children
    // contribute the part that physically moved — full balance under ZBA,
    // (balance − residual) for a hybrid pool child.
    let concentrated = bal;
    for (const childId of childrenOf.get(shadow.localId) ?? []) {
      const childShadow = byLocalId.get(childId);
      const childAcc = childShadow
        ? accountOf(childShadow, accountsById)
        : undefined;
      const cBal = childAcc?.currentBalance ?? 0;
      concentrated += pbs.has(childId)
        ? cBal - residualAfterSweep(cBal, ruleOf.get(childId))
        : cBal;
    }
    const { ratePct, basket } = proposedRate(
      input,
      shadow.localId,
      acc,
      pbs,
    );
    out.push({ ccy, amount: concentrated * (ratePct / 100), basket });
  }
  return out;
}

export function interestYield(input: ScoreInputs): PerCurrencyLine {
  const { proposedShadows, accountsById } = input;
  const line = emptyLine(
    'Concentration lifts idle balances to the header’s rate; pooled accounts earn the pool rate. Hybrid: the sweep moves, the residual pools (attribution A). Basket split disclosed.',
  );

  // Live = each shadow at its CURRENT rate (a proposed pool is not yet live).
  for (const shadow of proposedShadows) {
    const acc = accountOf(shadow, accountsById);
    if (!acc) continue;
    const ccy = acc.currencyCode || shadow.snapshotCurrencyCode;
    addAmount(
      line.live,
      ccy,
      (acc.currentBalance ?? 0) * (liveRatePct(input, acc) / 100),
    );
  }

  for (const c of proposedContributions(input)) {
    addAmount(line.proposed, c.ccy, c.amount);
  }
  return line;
}

/**
 * Proposed-side interest split by basket, PER CURRENCY. Uses the SAME
 * generator as interestYield's proposed branch, so external + pool always
 * sums to the proposed total (no drift possible).
 */
export function interestBaskets(input: ScoreInputs): {
  external: Record<string, number>;
  pool: Record<string, number>;
} {
  const external: Record<string, number> = {};
  const pool: Record<string, number> = {};
  for (const c of proposedContributions(input)) {
    addAmount(c.basket === 'pool' ? pool : external, c.ccy, c.amount);
  }
  return { external, pool };
}
