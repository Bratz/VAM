// ============================================================================
// Operational cost (Phase-2 score line). Pure.
//
//   total = (rule_count × base_per_rule_annual)
//         + (cross_bank_count ^ exponent × surcharge_per_unit)
//
// Pure run-cost assumption (FTE/ops), expressed directly in base currency —
// not tied to any account currency. Live baseline = 0 (same caveat as bank
// fees). Δ = live − proposed = −total (more rules cost more; danger tone).
// ============================================================================

import type { ScoreInputs } from '../../../components/simulator/types';
import { PerCurrencyLine, addAmount, emptyLine } from './shared';

export function operational(input: ScoreInputs): PerCurrencyLine {
  const { proposedRules, constants } = input;
  const line = emptyLine(
    'Fixed run-cost per active rule + a non-linear cross-bank surcharge.',
  );

  const ruleCount = proposedRules.length;
  const crossBankCount = proposedRules.filter((r) => r.isCrossBank).length;

  const base = ruleCount * constants.operationalBasePerRuleAnnual;
  const surcharge =
    crossBankCount > 0
      ? Math.pow(crossBankCount, constants.crossBankExponent) *
        constants.crossBankSurchargePerUnit
      : 0;

  addAmount(line.proposed, constants.baseCurrency, base + surcharge);
  return line;
}
