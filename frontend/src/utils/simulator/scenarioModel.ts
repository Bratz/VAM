// ============================================================================
// scenarioModel — pure scenario helpers (validate / normalise / clone / hash)
//
// Framework-free and side-effect-free by contract (MVC discipline: all
// business logic lives here, testable without React). No imports from React,
// the API layer, or Tailwind.
// ============================================================================

import type {
  PaymentRail,
  SimulatedPool,
  SimulatedRule,
  SimulatedShadow,
  SimulatorScenario,
  ValidationIssue,
  ValidationResult,
} from '../../components/simulator/types';

// ---- clone ----------------------------------------------------------------

/** Deep, structural clone. Used before mutating a loaded scenario. */
export function cloneScenario(s: SimulatorScenario): SimulatorScenario {
  // structuredClone is available in all supported runtimes; JSON fallback
  // keeps this usable in tooling/test contexts that stub globals.
  if (typeof structuredClone === 'function') return structuredClone(s);
  return JSON.parse(JSON.stringify(s)) as SimulatorScenario;
}

// ---- ids ------------------------------------------------------------------

/**
 * Client-side stable id for a shadow/rule. Avoids `crypto.randomUUID`
 * (absent in older browsers / strict CSP) — same rationale as auditLog.
 * Time + entropy; unique enough for in-scenario local refs.
 */
export function newLocalId(prefix = 'id'): string {
  const t = Date.now().toString(36);
  const r = Math.random().toString(36).slice(2, 9);
  return `${prefix}_${t}${r}`;
}

// ---- hash -----------------------------------------------------------------

/** Recursively key-sorted serialise so the hash is order-independent. */
function stableStringify(value: unknown): string {
  if (value === null || typeof value !== 'object') return JSON.stringify(value);
  if (Array.isArray(value)) {
    return `[${value.map(stableStringify).join(',')}]`;
  }
  const keys = Object.keys(value as Record<string, unknown>).sort();
  return `{${keys
    .map((k) => `${JSON.stringify(k)}:${stableStringify((value as Record<string, unknown>)[k])}`)
    .join(',')}}`;
}

/** cyrb53 — fast, well-distributed 53-bit string hash (non-crypto). */
function cyrb53(str: string, seed = 0): string {
  let h1 = 0xdeadbeef ^ seed;
  let h2 = 0x41c6ce57 ^ seed;
  for (let i = 0; i < str.length; i++) {
    const ch = str.charCodeAt(i);
    h1 = Math.imul(h1 ^ ch, 2654435761);
    h2 = Math.imul(h2 ^ ch, 1597334677);
  }
  h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909);
  h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 13), 3266489909);
  const out = 4294967296 * (2097151 & h2) + (h1 >>> 0);
  return out.toString(16).padStart(14, '0');
}

/**
 * Deterministic hash of the *structure* (shadows + rules), independent of
 * key order and of metadata like timestamps. Used as the audit-trail
 * `dataStateHash` so a recorded decision can be tied to exactly what the
 * treasurer saw.
 */
export function hashScenario(s: SimulatorScenario): string {
  const structural = {
    shadows: s.proposedShadows,
    rules: s.proposedRules,
    pools: s.proposedPools,
  };
  return `scn_${cyrb53(stableStringify(structural))}`;
}

// ---- derived rule flags ---------------------------------------------------

function shadowMap(shadows: SimulatedShadow[]): Map<string, SimulatedShadow> {
  return new Map(shadows.map((sh) => [sh.localId, sh]));
}

/**
 * Recompute `isCrossBank` / `isCrossBorder` / `paymentRail` from the source
 * and target shadow snapshots. Cross-border is an honest country check
 * (snapshotBankCountry); when country is absent it conservatively falls back
 * to a currency-mismatch proxy.
 */
export function deriveRuleFlags(
  rule: SimulatedRule,
  byLocalId: Map<string, SimulatedShadow>,
): Pick<SimulatedRule, 'isCrossBank' | 'isCrossBorder' | 'paymentRail'> {
  const target = byLocalId.get(rule.targetLocalId);
  const sources = rule.sourceLocalIds
    .map((id) => byLocalId.get(id))
    .filter((x): x is SimulatedShadow => Boolean(x));

  if (!target || sources.length === 0) {
    return { isCrossBank: false, isCrossBorder: false, paymentRail: rule.paymentRail };
  }

  const isCrossBank = sources.some((s) => s.snapshotBankCode !== target.snapshotBankCode);

  const isCrossBorder = sources.some((s) => {
    if (s.snapshotBankCountry && target.snapshotBankCountry) {
      return s.snapshotBankCountry !== target.snapshotBankCountry;
    }
    return s.snapshotCurrencyCode !== target.snapshotCurrencyCode;
  });

  let paymentRail: PaymentRail | undefined = rule.paymentRail;
  if (!isCrossBank) {
    paymentRail = 'BANCS';
  } else if (isCrossBorder) {
    paymentRail = 'SWIFT_MT103';
  } else {
    // Same-country, cross-bank → domestic RTGS unless caller picked otherwise.
    paymentRail = rule.paymentRail ?? 'RTGS';
  }

  return { isCrossBank, isCrossBorder, paymentRail };
}

// ---- normalise ------------------------------------------------------------

/**
 * Return a structurally clean scenario: array fields guaranteed, names
 * trimmed, derived rule flags recomputed. Pure — does not mutate the input.
 */
export function normaliseScenario(s: SimulatorScenario): SimulatorScenario {
  const shadows = (s.proposedShadows ?? []).map((sh) => ({
    ...sh,
    proposedVaName: sh.proposedVaName?.trim() ?? '',
  }));
  const byId = shadowMap(shadows);
  const rules = (s.proposedRules ?? []).map((r) => ({
    ...r,
    ruleName: r.ruleName?.trim() ?? '',
    sourceLocalIds: Array.from(new Set(r.sourceLocalIds ?? [])),
    ...deriveRuleFlags(r, byId),
  }));
  const pools: SimulatedPool[] = (s.proposedPools ?? []).map((p) => ({
    ...p,
    poolName: p.poolName?.trim() ?? '',
    memberLocalIds: Array.from(new Set(p.memberLocalIds ?? [])),
  }));
  return {
    ...s,
    scenarioName: s.scenarioName?.trim() ?? '',
    proposedShadows: shadows,
    proposedRules: rules,
    proposedPools: pools,
  };
}

// ---- validation -----------------------------------------------------------

const CURRENCY_STRICT_TYPES = new Set(['ZERO_BALANCE', 'THRESHOLD']);

export interface ValidateOptions {
  /** Physical Account ids present in the corporate's inventory at load time.
   *  A shadow pointing outside this set is flagged `orphaned` (warning, not
   *  a save blocker — snapshot fields keep the scenario loadable). */
  validPhysicalAccountIds?: Set<string>;
}

/**
 * Phase-1 validation rules. Errors block Save; warnings inform.
 *
 *  1. Shadow → existing Physical Account (warning `orphaned` if missing).
 *  2. Rule has ≥1 source and exactly 1 target.
 *  3. Rule target is not also one of its sources.
 *  4. Rule refs resolve to shadows in the scenario.
 *  5. Currency consistency — strict for ZBA/THRESHOLD (error), advisory for
 *     TARGET_BALANCE/PERCENTAGE (warning "FX conversion will apply").
 *  6. No circular rules — A→B blocked if B→A already exists.
 *  7. Pools (R3): ≥2 members; refs resolve; members are home-bank
 *     (INTERNAL) — external/group can't be pooled; a shadow ∉ >1 pool;
 *     hybrid (a member also swept) is ALLOWED; mixed-ccy ⇒ FX warning.
 */
export function validateScenario(
  s: SimulatorScenario,
  opts: ValidateOptions = {},
): ValidationResult {
  const issues: ValidationIssue[] = [];
  const byId = shadowMap(s.proposedShadows ?? []);

  // Rule 1 — orphaned shadows
  if (opts.validPhysicalAccountIds) {
    for (const sh of s.proposedShadows ?? []) {
      if (!opts.validPhysicalAccountIds.has(sh.physicalAccountId)) {
        issues.push({
          level: 'warning',
          code: 'SHADOW_ORPHANED',
          message: `"${sh.proposedVaName || sh.localId}" references a Physical Account no longer in the inventory.`,
          shadowLocalId: sh.localId,
        });
      }
    }
  }

  // Build directed edges for the cycle check (source-set → target).
  const edges: Array<{ rule: SimulatedRule; sources: string[]; target: string }> = [];

  for (const r of s.proposedRules ?? []) {
    const label = r.ruleName || r.localId;
    const sources = r.sourceLocalIds ?? [];
    const target = r.targetLocalId;

    // Rule 2 — cardinality
    if (sources.length === 0) {
      issues.push({
        level: 'error',
        code: 'RULE_NO_SOURCE',
        message: `Rule "${label}" needs at least one source.`,
        ruleLocalId: r.localId,
      });
    }
    if (!target) {
      issues.push({
        level: 'error',
        code: 'RULE_NO_TARGET',
        message: `Rule "${label}" needs exactly one target.`,
        ruleLocalId: r.localId,
      });
    }

    // Rule 3 — target ∉ sources
    if (target && sources.includes(target)) {
      issues.push({
        level: 'error',
        code: 'RULE_TARGET_IS_SOURCE',
        message: `Rule "${label}" has its target also listed as a source.`,
        ruleLocalId: r.localId,
      });
    }

    // Rule 4 — refs resolve
    const unresolved = [...sources, target].filter((id) => id && !byId.has(id));
    if (unresolved.length > 0) {
      issues.push({
        level: 'error',
        code: 'RULE_DANGLING_REF',
        message: `Rule "${label}" references a shadow that is not in the scenario.`,
        ruleLocalId: r.localId,
      });
    }

    // Rule 5 — currency consistency
    const currencies = new Set(
      [...sources, target]
        .map((id) => byId.get(id || '')?.snapshotCurrencyCode)
        .filter((c): c is string => Boolean(c)),
    );
    if (currencies.size > 1) {
      if (CURRENCY_STRICT_TYPES.has(r.sweepType)) {
        issues.push({
          level: 'error',
          code: 'RULE_CURRENCY_MISMATCH',
          message: `Rule "${label}" (${r.sweepType}) requires all sources and the target to share one currency.`,
          ruleLocalId: r.localId,
        });
      } else {
        issues.push({
          level: 'warning',
          code: 'RULE_FX_APPLIES',
          message: `Rule "${label}" mixes currencies — FX conversion will apply.`,
          ruleLocalId: r.localId,
        });
      }
    }

    if (target && sources.length > 0) {
      edges.push({ rule: r, sources, target });
    }
  }

  // Rule 6 — no 2-cycles (A→B blocked if B→A already exists). A full DAG
  // check is deferred; the spec's V1 rule is the pairwise reciprocal case.
  for (let i = 0; i < edges.length; i++) {
    for (let j = i + 1; j < edges.length; j++) {
      const a = edges[i];
      const b = edges[j];
      const aFeedsB = a.sources.includes(b.target) && b.sources.includes(a.target);
      if (aFeedsB) {
        issues.push({
          level: 'error',
          code: 'RULE_CIRCULAR',
          message: `Rules "${a.rule.ruleName || a.rule.localId}" and "${b.rule.ruleName || b.rule.localId}" form a circular sweep.`,
          ruleLocalId: a.rule.localId,
        });
      }
    }
  }

  // Rule 7 — notional pools (R3). Hybrid is ALLOWED: a pool member may also
  // be a sweep source/target. The hard limiter is the live
  // `NotionalPoolService.assertPoolEligible` — pool members must be
  // home-bank-held PHYSICAL_MIRRORs — so a member must be an INTERNAL
  // shadow (external/group swept shadows can never be pooled). Mixed-ccy
  // pools are allowed with an FX-honest disclosure (warning, not a blocker).
  const memberPoolCount = new Map<string, number>();
  for (const p of s.proposedPools ?? []) {
    const plabel = p.poolName || p.localId;
    const members = p.memberLocalIds ?? [];

    if (members.length < 2) {
      issues.push({
        level: 'error',
        code: 'POOL_TOO_FEW_MEMBERS',
        message: `Pool "${plabel}" needs at least two member shadows.`,
        poolLocalId: p.localId,
      });
    }

    if (members.some((id) => !byId.has(id))) {
      issues.push({
        level: 'error',
        code: 'POOL_DANGLING_REF',
        message: `Pool "${plabel}" references a shadow that is not in the scenario.`,
        poolLocalId: p.localId,
      });
    }

    for (const id of members) {
      memberPoolCount.set(id, (memberPoolCount.get(id) ?? 0) + 1);
      const sh = byId.get(id);
      if (sh && sh.snapshotBankRelationship !== 'INTERNAL') {
        issues.push({
          level: 'error',
          code: 'POOL_MEMBER_NOT_HOME_BANK',
          message: `Pool "${plabel}" includes "${sh.proposedVaName || id}" — only home-bank (INTERNAL) shadows can be pooled; an external/group shadow is a sweep participant, not a pool member.`,
          poolLocalId: p.localId,
          shadowLocalId: id,
        });
      }
    }

    const memberCurrencies = new Set(
      members
        .map((id) => byId.get(id)?.snapshotCurrencyCode)
        .filter((c): c is string => Boolean(c)),
    );
    if (memberCurrencies.size > 1) {
      issues.push({
        level: 'warning',
        code: 'POOL_FX_APPLIES',
        message: `Pool "${plabel}" mixes currencies — the score discloses an FX-honest cross-currency conversion.`,
        poolLocalId: p.localId,
      });
    }
  }

  // A shadow may not belong to more than one pool.
  for (const [id, count] of memberPoolCount) {
    if (count > 1) {
      issues.push({
        level: 'error',
        code: 'POOL_MEMBER_REUSED',
        message: `Shadow "${byId.get(id)?.proposedVaName || id}" is a member of more than one pool.`,
        shadowLocalId: id,
      });
    }
  }

  return { ok: !issues.some((i) => i.level === 'error'), issues };
}
