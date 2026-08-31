// ============================================================================
// Bank fees (Phase-2 score line). Pure, per-currency.
//
// REWIRED (2026-05-16): consumes the bank's REAL configured charges
// (`ChargeConfiguration` via taxChargeApi) instead of the bespoke, now-
// deprecated `bank_fee_tariff`. Per proposed rule: derive the applicable
// ChargeType(s) from the rail / cross-border flag, pick the matching ACTIVE
// charge config, and charge:
//   FIXED      → fixedAmount         × execs/yr
//   PERCENTAGE → (percentageRate/100 × principal) × execs/yr
// clamped to [minimumCharge, maximumCharge]. TIERED/SLIDING_SCALE deferred
// (disclosed). Configs are NOT per-bank — the configured charge is the bank's
// real source of truth (more honest than a synthetic per-bank tariff).
//
// Live baseline = 0 (proposed rules are new — Phase-3-diff caveat).
// Δ = −proposed (a new cost; danger tone).
// ============================================================================

import type {
  ScoreInputs,
  SimulatedRule,
  SimulatorChargeConfig,
} from '../../../components/simulator/types';
import { PerCurrencyLine, accountOf, addAmount, emptyLine } from './shared';

function railOf(rule: SimulatedRule): string {
  if (rule.paymentRail) return rule.paymentRail;
  return rule.isCrossBank ? 'SWIFT_MT103' : 'BANCS';
}

/** Rail → applicable ChargeType(s) (cross-border adds an FX leg on SWIFT). */
function chargeTypesFor(rule: SimulatedRule): string[] {
  switch (railOf(rule)) {
    case 'SWIFT_MT103':
      return rule.isCrossBorder
        ? ['SWIFT_FEE', 'FX_FEE']
        : ['SWIFT_FEE'];
    case 'OPEN_BANKING':
      return ['PROCESSING_FEE'];
    case 'SEPA':
    case 'RTGS':
    case 'BANCS':
    default:
      return ['TRANSACTION_FEE'];
  }
}

function principalOf(rule: SimulatedRule, sourceBalanceSum: number): number {
  switch (rule.sweepType) {
    case 'TARGET_BALANCE':
      return rule.targetAmount ?? sourceBalanceSum;
    case 'PERCENTAGE':
      return ((rule.percentage ?? 100) / 100) * sourceBalanceSum;
    default:
      return sourceBalanceSum;
  }
}

function pickConfig(
  configs: SimulatorChargeConfig[],
  chargeType: string,
  crossBorder: boolean,
): SimulatorChargeConfig | undefined {
  const active = configs.filter(
    (c) => c.status === 'ACTIVE' && c.chargeType === chargeType,
  );
  if (active.length === 0) return undefined;
  return (
    active.find((c) => (crossBorder ? c.isCrossBorder : c.isDomestic)) ??
    active[0]
  );
}

function perExecCharge(
  c: SimulatorChargeConfig,
  principal: number,
): number {
  let v: number;
  if (c.chargeCategory === 'FIXED') {
    v = c.fixedAmount ?? 0;
  } else if (c.chargeCategory === 'PERCENTAGE') {
    v = ((c.percentageRate ?? 0) / 100) * principal;
  } else {
    // TIERED / SLIDING_SCALE — deferred (disclosed in assumptions).
    return 0;
  }
  if (c.minimumCharge != null) v = Math.max(v, c.minimumCharge);
  if (c.maximumCharge != null && c.maximumCharge > 0) {
    v = Math.min(v, c.maximumCharge);
  }
  return v;
}

export function bankFees(input: ScoreInputs): PerCurrencyLine {
  const { proposedShadows, proposedRules, accountsById, constants } = input;
  const configs = input.chargeConfigs ?? [];
  const line = emptyLine(
    'New routing fees from the bank’s configured charge schedule (rail → charge type; FIXED + PERCENTAGE; TIERED deferred). Plus notional-pool management + membership fees (real NotionalPoolService constants).',
  );
  const byLocalId = new Map(proposedShadows.map((s) => [s.localId, s]));

  // R3 — notional-pool fees, independent of the bank charge schedule. The
  // management fee is on the pool's GROSS member balance (matches the live
  // engine, which sums member balances irrespective of any sweep residual)
  // floored at the annual minimum; plus a flat membership fee per member.
  // Live baseline = 0 (a proposed pool is new — Phase-3-diff caveat).
  for (const pool of input.proposedPools ?? []) {
    const members = pool.memberLocalIds ?? [];
    if (members.length === 0) continue;
    const grossBal = members.reduce((sum, id) => {
      const sh = byLocalId.get(id);
      const acc = sh ? accountOf(sh, accountsById) : undefined;
      return sum + (acc?.currentBalance ?? 0);
    }, 0);
    const mgmt = Math.max(
      grossBal * (constants.poolMgmtFeeAnnualPct / 100),
      constants.poolMgmtFeeMinAnnual,
    );
    const membership = constants.poolMembershipFeeFlat * members.length;
    addAmount(
      line.proposed,
      pool.poolCurrency || constants.baseCurrency,
      mgmt + membership,
    );
  }

  if (configs.length === 0) return line;

  for (const rule of proposedRules) {
    const sources = (rule.sourceLocalIds ?? [])
      .map((id) => byLocalId.get(id))
      .filter((s): s is NonNullable<typeof s> => Boolean(s));
    if (sources.length === 0) continue;

    const sourceBalanceSum = sources.reduce((sum, s) => {
      const acc = accountOf(s, accountsById);
      return sum + (acc?.currentBalance ?? 0);
    }, 0);
    const principal = principalOf(rule, sourceBalanceSum);
    const execs = constants.executionsPerYear[rule.frequency] ?? 0;
    if (execs === 0) continue;

    const crossBorder = Boolean(rule.isCrossBorder);
    for (const ct of chargeTypesFor(rule)) {
      const cfg = pickConfig(configs, ct, crossBorder);
      if (!cfg) continue;
      const fee = perExecCharge(cfg, principal) * execs;
      addAmount(line.proposed, cfg.currencyCode, fee);
    }
  }

  return line;
}
