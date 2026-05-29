// ============================================================================
// Cash Concentration Simulator — type contracts (Phase 1)
//
// A simulated structure is the SAME data shape as a live structure, with a
// sandbox flag. These types model the three layers:
//   Layer 1  Physical Accounts  (live, read-only inputs → snapshotted on add)
//   Layer 2  Shadow VAs         (SimulatedShadow — proposed, client-owned)
//   Layer 3  Sweep Rules        (SimulatedRule — proposed, client-owned)
//
// Persistence: the backend stores the structure as `proposed_payload` JSONB
// shaped `{ shadows, rules }`. The frontend domain model flattens that to
// `proposedShadows` / `proposedRules`. `simulatorApi` maps between the two —
// nothing else should touch the wire shape.
// ============================================================================

export type ScenarioStatus =
  | 'DRAFT'
  | 'READY'
  | 'PROPOSED'
  | 'ACTIVATED'
  | 'ARCHIVED';

/**
 * Bank-relationship tiers. The live `physical_accounts.bank_relationship`
 * field currently emits only INTERNAL / EXTERNAL; GROUP is reserved so the
 * design-system pill recipe (Home=success, Group=info, External=warning)
 * stays forward-compatible without a type change when group-bank data lands.
 */
export type BankRelationship = 'INTERNAL' | 'GROUP' | 'EXTERNAL';

export type SweepType =
  | 'ZERO_BALANCE'
  | 'TARGET_BALANCE'
  | 'THRESHOLD'
  | 'PERCENTAGE';

export type SweepFrequency = 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'ON_DEMAND';

export type PaymentRail =
  | 'BANCS'
  | 'SWIFT_MT103'
  | 'SEPA'
  | 'RTGS'
  | 'OPEN_BANKING';

export type ShadowRole = 'HEADER' | 'CHILD';

export interface SimulatedShadow {
  /** Client-generated uuid; stable for the scenario's lifetime. */
  localId: string;
  /** FK into live `physical_accounts` (read-only). */
  physicalAccountId: string;
  proposedVaName: string;
  /** Auto-generated on activation (Phase 3); absent in V1. */
  proposedVaNumber?: string;
  /** Parent shadow's localId for the in-scenario tree; absent for a header. */
  parentLocalId?: string;
  role: ShadowRole;
  notes?: string;

  // ---- Snapshot fields, frozen at add time -------------------------------
  // The inventory can change without breaking the scenario. If the underlying
  // Physical Account is later deleted, the scenario still loads and the shadow
  // is flagged `orphaned` by validation.
  snapshotBankCode: string;
  snapshotBankName: string;
  snapshotBankRelationship: BankRelationship;
  /** ISO country of the bank — enables an honest cross-border derivation.
   *  (Added beyond the spec's field list: the API exposes `bankCountry`, so
   *  cross-border is a real country check, not a currency proxy.) */
  snapshotBankCountry?: string;
  snapshotDataSource: string;
  snapshotCurrencyCode: string;
  snapshotConsentExpiresAt?: string;
  snapshotInterestRate?: number;
  /** Not exposed by the V1 physical-accounts API; populated from Phase 2 when
   *  the debt-avoided line needs it. Optional + tolerated as undefined. */
  snapshotOverdraftLimit?: number;
}

export interface SimulatedRule {
  localId: string;
  ruleName: string;
  sweepType: SweepType;
  frequency: SweepFrequency;
  executionTime?: string;
  /** Refs into proposed shadows (localId). */
  sourceLocalIds: string[];
  /** Single ref into proposed shadows (localId). */
  targetLocalId: string;
  /** Auto-derived from the cross-bank check; see `deriveRuleFlags`. */
  paymentRail?: PaymentRail;
  targetAmount?: number;
  thresholdMin?: number;
  thresholdMax?: number;
  percentage?: number;
  /** Derived + cached for filters. Recomputed by `normaliseScenario`. */
  isCrossBank: boolean;
  isCrossBorder: boolean;
}

/**
 * R3 — a proposed notional pool. Members are proposed shadows (by localId);
 * on activation each resolves to its live home-bank PHYSICAL_MIRROR VA. The
 * live `NotionalPoolService.assertPoolEligible` rule is HOME-BANK-ONLY, so a
 * member must be an INTERNAL shadow (validated client-side too). A member
 * MAY also participate in a sweep rule (hybrid structure); the score then
 * attributes balance via "sweep moves, residual pools".
 *
 * `poolRatePct` is captured EXPLICITLY as an annual percent (e.g. 3.5 =
 * 3.5%/yr) — the engine-truth convention matching
 * `NotionalPoolService.calculateInterest` (`interestRate / 36500`). No
 * heuristic normalisation (the retired `normalizePoolPct`).
 */
export interface SimulatedPool {
  /** Client-generated uuid; stable for the scenario's lifetime. */
  localId: string;
  poolName: string;
  poolCurrency: string;
  /** Annual percent. Engine-truth — never heuristically normalised. */
  poolRatePct: number;
  /** Mirrors the live pool's interest-calculation method label; optional. */
  interestCalcMethod?: string;
  /** Refs into proposed shadows (localId). A valid pool needs ≥2. */
  memberLocalIds: string[];
  targetBalance?: number;
  notes?: string;
}

/** The JSONB wire shape persisted in `simulator_scenarios.proposed_payload`. */
export interface ScenarioProposedPayload {
  shadows: SimulatedShadow[];
  rules: SimulatedRule[];
  /** R3 — proposed notional pools (legacy payloads omit it; read as []). */
  pools: SimulatedPool[];
}

/** Frontend domain model (flattened). `simulatorApi` maps to/from the wire. */
export interface SimulatorScenario {
  id: string;
  corporateId: string;
  scenarioName: string;
  scenarioReference: string;
  description?: string;
  baseCurrency: string;
  status: ScenarioStatus;
  parentScenarioId?: string;
  forkLabel?: string;
  snapshotTakenAt?: string;
  /** Frozen live-config diff baseline (Phase 3); null until first capture. */
  snapshotPayload?: ScenarioSnapshotPayload | null;
  proposedShadows: SimulatedShadow[];
  proposedRules: SimulatedRule[];
  /** R3 — proposed notional pools (flattened from `payload.pools`). */
  proposedPools: SimulatedPool[];
  notes?: string;
  createdAt: string;
  createdBy: string;
  updatedAt: string;
}

// ---- Inventory (composite read) -------------------------------------------

/**
 * The Physical Account shape the inventory + snapshot need. Structurally the
 * superset the backend already returns to PhysicalAccountsPage — declared here
 * so the simulator owns its contract rather than importing a page-local type.
 * Field names tolerate both the rich (`currency`) and lean (`currencyCode`)
 * API variants; `simulatorApi.getInventory` normalises on read.
 */
export interface SimulatorPhysicalAccount {
  id: string;
  accountNumber: string;
  accountName: string;
  corporateId: string;
  currencyCode: string;
  currentBalance: number;
  availableBalance: number;
  status: string;
  bankCode: string;
  bankName: string;
  bankCountry?: string;
  bankRelationship: BankRelationship;
  isHomeBank?: boolean;
  isExternalBank?: boolean;
  dataSource: string;
  consentExpiresAt?: string;
  interestRate?: number;
  interestType?: string;
  /** Effective (post-tiering) annual rate — Phase-2 additive API field. */
  effectiveInterestRate?: number;
  overdraftLimit?: number;
  /** Avg drawn overdraft — Phase-2 additive API field. */
  overdraftUtilized?: number;
  sweepEligible?: boolean;
  poolingEligible?: boolean;
  /** Set by the backend when this Physical already has a mirror Shadow VA. */
  shadowVaId?: string;
  shadowVaNumber?: string;
}

export interface SimulatorInventory {
  physicalAccounts: SimulatorPhysicalAccount[];
  /** Existing live Shadow VAs — rendered as "already mirrored" hints. */
  existingShadows: ExistingShadowHint[];
}

export interface ExistingShadowHint {
  id: string;
  vaNumber: string;
  vaName: string;
  linkedPhysicalAccountId: string;
  currencyCode: string;
  status: string;
}

export interface BankGroup {
  bankCode: string;
  bankName: string;
  bankCountry?: string;
  relationship: BankRelationship;
  accounts: SimulatorPhysicalAccount[];
}

// ---- Validation -----------------------------------------------------------

export type ValidationLevel = 'error' | 'warning';

export interface ValidationIssue {
  level: ValidationLevel;
  code: string;
  message: string;
  shadowLocalId?: string;
  ruleLocalId?: string;
  poolLocalId?: string;
}

export interface ValidationResult {
  ok: boolean;
  issues: ValidationIssue[];
}

// ---- API request shapes ---------------------------------------------------

export interface CreateScenarioInput {
  corporateId: string;
  scenarioName: string;
  baseCurrency: string;
  description?: string;
}

export type UpdateScenarioPatch = Partial<
  Pick<
    SimulatorScenario,
    | 'scenarioName'
    | 'description'
    | 'baseCurrency'
    | 'status'
    | 'forkLabel'
    | 'notes'
    | 'proposedShadows'
    | 'proposedRules'
    | 'proposedPools'
  >
>;

// ---- Optimisation Score (Phase 2) -----------------------------------------

/**
 * @deprecated Replaced 2026-05-16 by `SimulatorChargeConfig` (the bank's real
 * `ChargeConfiguration` via `taxChargeApi`). The bespoke `bank_fee_tariff`
 * table + `simulatorApi.getTariffs` are deprecated; this type lingers only
 * for the unwired deprecated path. Do not use in new code.
 */
export interface BankFeeTariff {
  bankCode: string;
  paymentRail: PaymentRail | string;
  feeCurrency: string;
  feeFixed: number;
  feeBps: number;
  effectiveFrom?: string;
  effectiveTo?: string;
  source?: string;
}

/**
 * Lean projection of the backend `ChargeConfiguration` the Bank-fees line
 * needs. `SimulatorPage` maps `taxChargeApi.getChargeConfigs()` → this so the
 * pure formula stays IO-free.
 */
export interface SimulatorChargeConfig {
  chargeType: string;
  chargeCategory: 'FIXED' | 'PERCENTAGE' | 'TIERED' | 'SLIDING_SCALE' | string;
  fixedAmount?: number;
  percentageRate?: number;
  currencyCode: string;
  minimumCharge?: number;
  maximumCharge?: number;
  isCrossBorder?: boolean;
  isDomestic?: boolean;
  status: string;
}

/** Phase-4 trailing-90d source-quality metrics for one physical account. */
export interface SourceQuality {
  physicalAccountId: string;
  missRate: number;
  avgLagHours: number;
  samples: number;
}

/** R2 — notional-pool membership for a physical account (via its live
 *  PHYSICAL_MIRROR Shadow VA). `interestRate` is the annual percent stored
 *  in `notional_pools.interest_rate` (engine-truth: NotionalPoolService
 *  divides by 36500 ⇒ it IS an annual %); used directly, no normalisation. */
export interface PoolMembership {
  physicalAccountId: string;
  poolReference: string;
  poolCurrency: string;
  interestRate: number;
}

export type ScoreLineKey =
  | 'interestYield'
  | 'debtAvoided'
  | 'bankFees'
  | 'operational'
  | 'sourceQuality'
  | 'consentTimeline'
  | 'taxLeakage';

/** An FX quote used in a cross-currency conversion — disclosed inline per
 *  the FX-honesty rule (rate + source + timestamp). */
export interface FxQuote {
  rate: number;
  source: string;
  asOf: string;
  /** R4 — spread the corporate actually pays (bps), disclosed per FX-honesty. */
  spreadBps?: number;
}

/** Pure FX resolver: (from,to) → quote, or null when the pair is unavailable
 *  (caller then falls back to per-currency lines + a caveat). */
export type FxResolver = (from: string, to: string) => FxQuote | null;

/**
 * Tunable, DISCLOSED constants — every one is surfaced in the score panel's
 * "View assumptions" drawer (the spec's honesty mechanism). Defaults live in
 * `scoreCalculator.ts`.
 */
export interface ScoreConstants {
  baseCurrency: string;
  /** Executions per year by frequency (DAILY≈252 business days, …). */
  executionsPerYear: Record<SweepFrequency, number>;
  /** Overdraft is priced at the deposit rate + this spread (pct points). */
  overdraftSpreadPct: number;
  /** Fixed annual run-cost per active rule. */
  operationalBasePerRuleAnnual: number;
  /** Non-linear cross-bank surcharge: count^exponent × perUnit. */
  crossBankSurchargePerUnit: number;
  crossBankExponent: number;
  /** Headline-score denominator (no `corporate.annual_revenue` exists, so a
   *  disclosed constant ceiling stands in for `max_realistic_benefit`). */
  headlineCeiling: number;
  // ---- Phase 4: operational-risk lines (disclosed) ----
  /** Source-quality: bps overhead to manually correct a missed sync. */
  manualCorrectionBps: number;
  /** Source-quality: opportunity cost per hour of feed lag. */
  lagCostPerHour: number;
  /** Consent timeline thresholds (days) + surcharge rates (× principal/yr). */
  consentSevereDays: number;
  consentModerateDays: number;
  consentSevereRate: number;
  consentModerateRate: number;
  // ---- R3: notional-pool fees (mirror the real NotionalPoolService) ----
  /** Pool management fee, % of pool balance per YEAR (0.01%/mo ×12). */
  poolMgmtFeeAnnualPct: number;
  /** Pool management fee annual floor (min $100/mo ×12 = $1,200). */
  poolMgmtFeeMinAnnual: number;
  /** Flat membership fee per member (one-off; charged in the year-1 annual). */
  poolMembershipFeeFlat: number;
}

export interface SourceQualityMetric {
  missRate: number;
  avgLagHours: number;
}

export interface ScoreInputs {
  proposedShadows: SimulatedShadow[];
  proposedRules: SimulatedRule[];
  /** R3 — proposed notional pools. Members earn the SimulatedPool rate;
   *  hybrid (a member also swept) uses attribution A: sweep moves, residual
   *  pools. Absent ⇒ behaves exactly as the pre-R3 score (no regression). */
  proposedPools?: SimulatedPool[];
  /** Live inventory keyed by physicalAccountId (balances + rates + OD). */
  accountsById: Map<string, SimulatorPhysicalAccount>;
  /** Bank's configured charge schedule (rewired from bank_fee_tariff). */
  chargeConfigs: SimulatorChargeConfig[];
  /** Phase-4 trailing-90d metrics keyed by physicalAccountId. */
  sourceQualityByPhys?: Map<string, SourceQualityMetric>;
  /** R2 — notional-pool membership keyed by physicalAccountId. `interestRate`
   *  is the annual percent from `notional_pools.interest_rate` (engine-truth,
   *  used directly — the `normalizePoolPct` heuristic was retired in R3). */
  pooledByPhys?: Map<
    string,
    { interestRate: number; poolReference: string; poolCurrency: string }
  >;
  /** Withholding-tax rate (%) by ISO bank country — joined on the page from
   *  taxChargeApi jurisdictions ⨝ withholding tax-configs. Drives the
   *  tax-leakage line for cross-border rules. */
  withholdingByCountry?: Record<string, number>;
  constants: ScoreConstants;
  fx: FxResolver;
}

export interface ScoreLineResult {
  key: ScoreLineKey;
  label: string;
  /** Annualised, base currency. */
  liveAnnual: number;
  proposedAnnual: number;
  /** Signed so POSITIVE always = better (benefit up / cost down). */
  deltaAnnual: number;
  tone: 'success' | 'danger' | 'neutral';
  detail: string;
}

export interface ScoreAssumption {
  label: string;
  value: string;
  source?: string;
}

export interface ScoreFxDisclosure {
  pair: string;
  rate: number;
  source: string;
  asOf: string;
  /** R4 — spread (bps) the corporate pays on this pair. */
  spreadBps?: number;
}

export interface ScoreResult {
  baseCurrency: string;
  lines: ScoreLineResult[];
  /** Σ signed deltas (benefits +, costs −), base currency. */
  netAnnualBenefit: number;
  /** 0–100 visual anchor; the dollar figure is the defensible output. */
  headline: number;
  assumptions: ScoreAssumption[];
  fxDisclosures: ScoreFxDisclosure[];
  /** e.g. "EUR→USD rate unavailable — shown per-currency." */
  caveats: string[];
  /** Phase-4 "Operational risk profile" — consent windows by bank. */
  consentWindows?: ConsentWindow[];
  /** R2 — proposed interest-yield split by basket, base ccy (disclosure).
   *  Internal/IHB intentionally absent (deferred — see assumptions). */
  interestBaskets?: { external: number; pool: number };
  computedAt: string;
}

export interface ConsentWindow {
  physicalAccountId: string;
  bankCode: string;
  bankName: string;
  currencyCode: string;
  daysToExpiry: number;
  severity: 'severe' | 'moderate' | 'ok';
  /** Annual surcharge contributed by this account's consent window. */
  surchargeAnnual: number;
}

// ---- Diff vs live (Phase 3) -----------------------------------------------

export type DiffOp = 'ADD' | 'MODIFY' | 'RETIRE' | 'UNCHANGED';
export type DiffEntity = 'SHADOW' | 'RULE' | 'POOL';

export interface DiffFieldChange {
  field: string;
  before: unknown;
  after: unknown;
}

export interface DiffEntry {
  op: DiffOp;
  entity: DiffEntity;
  label: string;
  /** Live entity ref — present for MODIFY / RETIRE / UNCHANGED. */
  liveRef?: { id: string; type: DiffEntity };
  /** Proposed entity ref — present for ADD / MODIFY / UNCHANGED. */
  proposedRef?: { localId: string };
  fieldChanges?: DiffFieldChange[];
  caveats?: string[];
}

export interface DiffCounts {
  add: number;
  modify: number;
  retire: number;
  unchanged: number;
}

export interface DiffResult {
  scenarioId: string;
  snapshotTakenAt: string;
  entries: DiffEntry[];
  counts: DiffCounts;
}

/**
 * Frozen live config written by the backend `ScenarioSnapshotService` into
 * `simulator_scenarios.snapshot_payload`. Physical-account ids are resolved
 * server-side so the pure `diffEngine` can match by *what a rule does*
 * (source-physical-id set + target physical id), not by entity ids.
 */
export interface SnapshotShadow {
  id: string;
  vaName: string;
  physicalAccountId: string;
  currencyCode?: string;
  bankCode?: string;
}

export interface SnapshotRule {
  id: string;
  ruleName: string;
  sweepType?: string;
  frequency?: string;
  executionTime?: string;
  sourcePhysicalAccountIds: string[];
  targetPhysicalAccountId: string;
}

/**
 * R3 — a live notional pool frozen in the snapshot. Member physical-account
 * ids are resolved server-side so the pure diffEngine matches a pool by its
 * membership SET (analogous to the rule source-set heuristic).
 */
export interface SnapshotPool {
  id: string;
  poolName?: string;
  poolReference?: string;
  poolCurrency?: string;
  /** Engine-truth annual % (`notional_pools.interest_rate`; ÷36500 in the
   *  live engine ⇒ it IS an annual %). */
  interestRate?: number;
  memberPhysicalAccountIds: string[];
}

export interface ScenarioSnapshotPayload {
  takenAt: string;
  shadows: SnapshotShadow[];
  rules: SnapshotRule[];
  /** R3 — frozen live notional pools (legacy snapshots omit it; read []). */
  pools: SnapshotPool[];
}
