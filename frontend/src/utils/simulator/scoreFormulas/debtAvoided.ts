// ============================================================================
// Debt avoided (Phase-2 score line). Pure, per-currency.
//
// Overdraft is priced at the account's deposit rate + a disclosed spread.
// Live  : every shadow's account pays OD cost on its drawn overdraft.
// Proposed: a swept child's deficit is funded by the header → its OD cost
//         goes to 0; headers/standalones keep their own OD.
// Δ = live − proposed  (positive = debt avoided = success).
// ============================================================================

import type { ScoreInputs } from '../../../components/simulator/types';
import {
  PerCurrencyLine,
  accountOf,
  addAmount,
  concentrationTopology,
  depositRatePct,
  emptyLine,
} from './shared';

export function debtAvoided(input: ScoreInputs): PerCurrencyLine {
  const { proposedShadows, proposedRules, accountsById, constants } = input;
  const line = emptyLine(
    'Sweeps cover child-account deficits, removing their overdraft cost.',
  );
  const { sweptOut } = concentrationTopology(proposedRules);

  for (const shadow of proposedShadows) {
    const acc = accountOf(shadow, accountsById);
    if (!acc) continue;
    const ccy = acc.currencyCode || shadow.snapshotCurrencyCode;
    const drawn = acc.overdraftUtilized ?? 0;
    if (drawn <= 0) continue;
    const odRatePct = depositRatePct(acc) + constants.overdraftSpreadPct;
    const annualOdCost = drawn * (odRatePct / 100);

    // Live: account carries its own overdraft cost.
    addAmount(line.live, ccy, annualOdCost);

    // Proposed: swept child funded by the header → no overdraft.
    if (sweptOut.has(shadow.localId)) continue;
    addAmount(line.proposed, ccy, annualOdCost);
  }

  return line;
}
