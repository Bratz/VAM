// ============================================================================
// scoreCalculator — composes the four Phase-2 formulas into a ScoreResult.
//
// Pure, framework-free, deterministic (MVC: all score logic here, testable
// without React or the network). The FX-honesty rule is enforced HERE, once:
// per-currency amounts are converted to base only when a quote exists; an
// unavailable pair is EXCLUDED from the base figure and surfaced as a caveat
// (never a silent cross-currency sum). Every constant is emitted in
// `assumptions` for the "View assumptions" drawer.
// ============================================================================

import type {
  FxQuote,
  FxResolver,
  ScoreAssumption,
  ScoreConstants,
  ScoreFxDisclosure,
  ScoreInputs,
  ScoreLineResult,
  ScoreResult,
} from '../../components/simulator/types';
import {
  interestYield,
  interestBaskets,
} from './scoreFormulas/interestYield';
import { debtAvoided } from './scoreFormulas/debtAvoided';
import { bankFees } from './scoreFormulas/bankFees';
import { operational } from './scoreFormulas/operational';
import { sourceQuality } from './scoreFormulas/sourceQuality';
import { consentWindows } from './scoreFormulas/consentTimeline';
import { taxLeakage } from './scoreFormulas/taxLeakage';
import type { PerCurrencyLine } from './scoreFormulas/shared';
import { round2 } from './scoreFormulas/shared';

export const DEFAULT_SCORE_CONSTANTS: ScoreConstants = {
  baseCurrency: 'USD',
  executionsPerYear: { DAILY: 252, WEEKLY: 52, MONTHLY: 12, ON_DEMAND: 1 },
  overdraftSpreadPct: 3.0,
  operationalBasePerRuleAnnual: 2000,
  crossBankSurchargePerUnit: 500,
  crossBankExponent: 1.5,
  headlineCeiling: 2_000_000,
  // Phase-4 operational-risk (disclosed in the assumptions drawer)
  manualCorrectionBps: 5,
  lagCostPerHour: 50,
  consentSevereDays: 14,
  consentModerateDays: 30,
  consentSevereRate: 0.005,
  consentModerateRate: 0.002,
  // R3 — verbatim from NotionalPoolService: POOL_MANAGEMENT_FEE_RATE
  // 0.01%/mo (×12 = 0.12%/yr), POOL_MANAGEMENT_FEE_MIN $100/mo
  // (×12 = $1,200/yr), POOL_MEMBERSHIP_FEE $25 flat/member.
  poolMgmtFeeAnnualPct: 0.12,
  poolMgmtFeeMinAnnual: 1200,
  poolMembershipFeeFlat: 25,
};

export function makeConstants(
  overrides: Partial<ScoreConstants> = {},
): ScoreConstants {
  return { ...DEFAULT_SCORE_CONSTANTS, ...overrides };
}

/** A constant FX map → resolver. Returns null for unknown pairs (honest). */
export function fxFromMap(
  rates: Record<
    string,
    { rate: number; source: string; asOf: string; spreadBps?: number }
  >,
): FxResolver {
  return (from, to) => {
    if (from === to) return { rate: 1, source: 'identity', asOf: '' };
    const q = rates[`${from}/${to}`];
    if (q) return { ...q };
    const inv = rates[`${to}/${from}`];
    if (inv && inv.rate !== 0) {
      return {
        rate: 1 / inv.rate,
        source: inv.source,
        asOf: inv.asOf,
        spreadBps: inv.spreadBps,
      };
    }
    return null;
  };
}

interface ConvCtx {
  base: string;
  fx: FxResolver;
  disclosures: Map<string, FxQuote>;
  missing: Set<string>;
}

function toBase(bucket: Record<string, number>, ctx: ConvCtx): number {
  let total = 0;
  for (const [ccy, amt] of Object.entries(bucket)) {
    if (!amt) continue;
    if (ccy === ctx.base) {
      total += amt;
      continue;
    }
    const q = ctx.fx(ccy, ctx.base);
    if (q) {
      total += amt * q.rate;
      ctx.disclosures.set(`${ccy}/${ctx.base}`, q);
    } else {
      // FX-honesty: do NOT fold an unconvertible currency into the base total.
      ctx.missing.add(`${ccy}→${ctx.base}`);
    }
  }
  return total;
}

type Direction = 'benefit' | 'costAvoided' | 'cost';

function assemble(
  key: ScoreLineResult['key'],
  label: string,
  pcl: PerCurrencyLine,
  direction: Direction,
  ctx: ConvCtx,
): ScoreLineResult {
  const liveAnnual = round2(toBase(pcl.live, ctx));
  const proposedAnnual = round2(toBase(pcl.proposed, ctx));

  // Sign so POSITIVE always = better.
  let deltaAnnual: number;
  if (direction === 'benefit') deltaAnnual = proposedAnnual - liveAnnual;
  else deltaAnnual = liveAnnual - proposedAnnual; // costAvoided & cost

  deltaAnnual = round2(deltaAnnual);
  const tone: ScoreLineResult['tone'] =
    deltaAnnual > 0 ? 'success' : deltaAnnual < 0 ? 'danger' : 'neutral';

  return { key, label, liveAnnual, proposedAnnual, deltaAnnual, tone, detail: pcl.detail };
}

export function computeScore(input: ScoreInputs): ScoreResult {
  const c = input.constants;
  const ctx: ConvCtx = {
    base: c.baseCurrency,
    fx: input.fx,
    disclosures: new Map(),
    missing: new Set(),
  };

  // The original design's 6-line score, always shown, in mockup order
  // (R1, 2026-05-16: retired the Phase-2 4-line/flag-gated split; Consent
  // timeline is NOT a score line — it surfaces only in the Operational-risk
  // profile via `consentWindows`).
  const lines: ScoreLineResult[] = [
    assemble('interestYield', 'Interest yield', interestYield(input), 'benefit', ctx),
    assemble('debtAvoided', 'Debt avoided', debtAvoided(input), 'costAvoided', ctx),
    assemble('bankFees', 'Bank fees', bankFees(input), 'cost', ctx),
    assemble('taxLeakage', 'Tax leakage', taxLeakage(input), 'cost', ctx),
    assemble('sourceQuality', 'Source quality', sourceQuality(input), 'cost', ctx),
    assemble('operational', 'Operational', operational(input), 'cost', ctx),
  ];
  const windows: ScoreResult['consentWindows'] = consentWindows(input);

  // R2 — proposed interest-yield split by basket (FX→base for disclosure).
  const ib = interestBaskets(input);
  const interestBasketsBase = {
    external: round2(toBase(ib.external, ctx)),
    pool: round2(toBase(ib.pool, ctx)),
  };

  const netAnnualBenefit = round2(
    lines.reduce((sum, l) => sum + l.deltaAnnual, 0),
  );

  const headline = Math.min(
    100,
    Math.max(
      0,
      Math.round(50 + (netAnnualBenefit / c.headlineCeiling) * 50),
    ),
  );

  const fxDisclosures: ScoreFxDisclosure[] = [...ctx.disclosures.entries()]
    .filter(([, q]) => q.source !== 'identity')
    .map(([pair, q]) => ({
      pair,
      rate: q.rate,
      source: q.source,
      asOf: q.asOf,
      spreadBps: q.spreadBps,
    }));

  const chargeTypesUsed = [
    ...new Set(
      (input.chargeConfigs ?? [])
        .filter((c) => c.status === 'ACTIVE')
        .map((c) => c.chargeType),
    ),
  ].join(', ');

  const assumptions: ScoreAssumption[] = [
    {
      label: 'Annualisation',
      value: `DAILY ${c.executionsPerYear.DAILY} · WEEKLY ${c.executionsPerYear.WEEKLY} · MONTHLY ${c.executionsPerYear.MONTHLY} executions/yr`,
    },
    {
      label: 'Interest basis',
      value: 'Effective rate where present, else nominal; single-tier ZBA concentration',
    },
    {
      label: 'Overdraft pricing',
      value: `deposit rate + ${c.overdraftSpreadPct.toFixed(2)}% spread`,
    },
    {
      label: 'Operational',
      value: `$${c.operationalBasePerRuleAnnual.toLocaleString()} / rule / yr + cross-bank^${c.crossBankExponent} × $${c.crossBankSurchargePerUnit}`,
    },
    {
      label: 'Headline ceiling',
      value: `$${c.headlineCeiling.toLocaleString()} ${c.baseCurrency} (no corporate revenue field — disclosed constant)`,
    },
    {
      label: 'Bank-fee / operational live baseline',
      value: '0 — proposed rules are new; real live baseline arrives with the Phase-3 diff',
    },
    {
      label: 'Bank-fee source',
      value: chargeTypesUsed
        ? `bank charge schedule (ChargeConfiguration): ${chargeTypesUsed}`
        : 'no charge configs loaded',
      source: 'tax-charges/charge-configs',
    },
  ];

  // Always disclosed — the 6-line score is the default (R1).
  assumptions.push(
    {
      label: 'Interest yield baskets',
      value: `proposed = ${c.baseCurrency} ${Math.round(interestBasketsBase.external).toLocaleString()} External/standalone + ${c.baseCurrency} ${Math.round(interestBasketsBase.pool).toLocaleString()} Pool · pool rate = annual % (engine-truth: NotionalPoolService interestRate÷36500) · Internal & IHB baskets deferred (proposed shadows have no live VA dual-config / IHB position)`,
    },
    {
      label: 'Source quality',
      value: `trailing-90d miss-rate + avg-lag from shadow_sync_log; manual-correction ${c.manualCorrectionBps}bps, lag $${c.lagCostPerHour}/h`,
      source: 'shadow_sync_log (90d)',
    },
    {
      label: 'Tax leakage',
      value: 'cross-border swept principal × withholding rate from the configured withholding tax-configs (by jurisdiction)',
      source: 'tax-charges/tax-configs/withholding',
    },
    {
      label: 'Consent timeline (Operational-risk profile)',
      value: `<${c.consentSevereDays}d → ${(c.consentSevereRate * 100).toFixed(2)}% · <${c.consentModerateDays}d → ${(c.consentModerateRate * 100).toFixed(2)}% of annual principal — surfaced as consent windows, not a score line`,
    },
    {
      label: 'Notional-pool yield',
      value:
        'Proposed-pool members earn the SimulatedPool rate (annual %) vs their standalone deposit rate; a hybrid member (also swept) uses attribution A — the sweep moves its physical amount, the residual pools (no double-count). Home-bank members only (live assertPoolEligible).',
    },
    {
      label: 'Notional-pool fee',
      value: `management ${c.poolMgmtFeeAnnualPct}%/yr of pool balance (0.01%/mo) · min $${c.poolMgmtFeeMinAnnual.toLocaleString()}/yr · $${c.poolMembershipFeeFlat} flat per member`,
      source: 'NotionalPoolService',
    },
  );

  const caveats: string[] = [];
  for (const m of ctx.missing) {
    caveats.push(`${m} rate unavailable — those amounts are excluded from the base total (shown per-currency).`);
  }

  return {
    baseCurrency: c.baseCurrency,
    lines,
    netAnnualBenefit,
    headline,
    assumptions,
    fxDisclosures,
    caveats,
    consentWindows: windows,
    interestBaskets: interestBasketsBase,
    computedAt: new Date().toISOString(),
  };
}
