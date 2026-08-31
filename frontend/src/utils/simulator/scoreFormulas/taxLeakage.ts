// ============================================================================
// Tax leakage (operational-risk line). Pure, per-currency.
//
// For each CROSS-BORDER proposed rule, withholding tax leaks on the swept
// principal as it leaves the source jurisdiction:
//   Σ over source accounts  (balance × execs/yr) × withholding_rate%
// `withholding_rate` comes from the bank's real withholding tax-configs
// (`taxChargeApi` /tax-configs/withholding ⨝ jurisdictions), joined on the
// page into `withholdingByCountry` (keyed by the account's ISO bank country).
//
// Live baseline 0 (proposed rules are new — same caveat as bankFees).
// Δ = −proposed (a leakage/cost; danger tone). Gated with the other
// operational-risk lines so OFF stays exactly the Phase-2 four lines.
// ============================================================================

import type { ScoreInputs } from '../../../components/simulator/types';
import { PerCurrencyLine, accountOf, addAmount, emptyLine } from './shared';

export function taxLeakage(input: ScoreInputs): PerCurrencyLine {
  const { proposedShadows, proposedRules, accountsById, constants } = input;
  const wht = input.withholdingByCountry ?? {};
  const line = emptyLine(
    'Withholding tax on cross-border swept principal (rate from the bank’s configured withholding tax-configs by jurisdiction).',
  );
  if (Object.keys(wht).length === 0) return line;

  const byLocalId = new Map(proposedShadows.map((s) => [s.localId, s]));

  for (const rule of proposedRules) {
    if (!rule.isCrossBorder) continue;
    const execs = constants.executionsPerYear[rule.frequency] ?? 0;
    if (execs === 0) continue;
    for (const srcLocal of rule.sourceLocalIds ?? []) {
      const shadow = byLocalId.get(srcLocal);
      if (!shadow) continue;
      const acc = accountOf(shadow, accountsById);
      if (!acc) continue;
      const country = acc.bankCountry;
      if (!country) continue;
      const ratePct = wht[country];
      if (!ratePct || ratePct <= 0) continue;
      const principalAnnual = (acc.currentBalance ?? 0) * execs;
      addAmount(
        line.proposed,
        acc.currencyCode,
        principalAnnual * (ratePct / 100),
      );
    }
  }

  return line;
}
