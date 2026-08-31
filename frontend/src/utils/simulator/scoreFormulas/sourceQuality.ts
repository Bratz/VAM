// ============================================================================
// Source quality (Phase-4 operational-risk line). Pure, per-currency.
//
// For each proposed rule whose SOURCE shadow rides an external feed
// (SWIFT_MT940 / SWIFT_MT942 / OPEN_BANKING), charge the reliability cost
// from the REAL trailing-90d shadow_sync_log aggregate:
//
//   (sweep_principal_annual × miss_rate × manualCorrectionBps/10000)   [src ccy]
// + (avg_lag_hours × lagCostPerHour × execs/yr)                        [base ccy]
//
// Live baseline 0 (proposed rules are new — Phase-3-diff caveat, like
// bankFees). Δ = −proposed (a new operational cost; danger tone).
// ============================================================================

import type { ScoreInputs } from '../../../components/simulator/types';
import { PerCurrencyLine, accountOf, addAmount, emptyLine } from './shared';

// Must match the backend `PhysicalAccount.DataSource` enum — external feeds
// only (NOT CORE_BANKING / INTERNAL_API). There is no bare 'OPEN_BANKING'.
const EXTERNAL = new Set([
  'SWIFT_MT940',
  'SWIFT_MT942',
  'SWIFT_CAMT053',
  'SWIFT_CAMT052',
  'OPEN_BANKING_PSD2',
  'OPEN_BANKING_UK',
  'OPEN_BANKING_UAE',
  'OPEN_BANKING_KSA',
]);

export function sourceQuality(input: ScoreInputs): PerCurrencyLine {
  const { proposedShadows, proposedRules, accountsById, constants } = input;
  const sq = input.sourceQualityByPhys;
  const line = emptyLine(
    'Reliability cost of external feeds (manual-correction overhead on missed syncs + lag opportunity cost) — from 90-day sync history.',
  );
  if (!sq || sq.size === 0) return line;

  const byLocalId = new Map(proposedShadows.map((s) => [s.localId, s]));

  for (const rule of proposedRules) {
    const execs = constants.executionsPerYear[rule.frequency] ?? 0;
    if (execs === 0) continue;
    for (const srcLocal of rule.sourceLocalIds ?? []) {
      const shadow = byLocalId.get(srcLocal);
      if (!shadow) continue;
      const acc = accountOf(shadow, accountsById);
      if (!acc || !acc.dataSource || !EXTERNAL.has(acc.dataSource)) continue;
      const m = sq.get(acc.id);
      if (!m) continue;

      const ccy = acc.currencyCode || shadow.snapshotCurrencyCode;
      const principalAnnual = (acc.currentBalance ?? 0) * execs;

      const correctionCost =
        principalAnnual *
        m.missRate *
        (constants.manualCorrectionBps / 10000);
      const lagCost =
        m.avgLagHours * constants.lagCostPerHour * execs;

      addAmount(line.proposed, ccy, correctionCost);
      addAmount(line.proposed, constants.baseCurrency, lagCost);
    }
  }

  return line;
}
