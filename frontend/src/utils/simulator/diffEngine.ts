// ============================================================================
// diffEngine — PURE diff of a frozen live snapshot vs the proposed structure.
//
// Framework-free, deterministic (MVC: all diff logic here, testable without
// React/network). Match heuristic per spec — match by WHAT a thing does, not
// by entity ids, so the diff is resilient to scenario builders who recreate
// rules from scratch:
//   • a live Shadow matches a proposed Shadow ⇔ same physical_account_id
//   • a live Rule matches a proposed Rule ⇔ equal SET of source physical
//     account ids AND equal target physical account id
//   • a live Pool matches a proposed Pool ⇔ equal SET of member physical
//     account ids (R3 — same "match by what it does" heuristic)
// ============================================================================

import type {
  DiffEntry,
  DiffFieldChange,
  DiffResult,
  ScenarioSnapshotPayload,
  SimulatedPool,
  SimulatedRule,
  SimulatedShadow,
} from '../../components/simulator/types';

export interface ProposedStructure {
  shadows: SimulatedShadow[];
  rules: SimulatedRule[];
  /** R3 — proposed notional pools (absent ⇒ no POOL diff entries). */
  pools?: SimulatedPool[];
}

function fieldChange(
  field: string,
  before: unknown,
  after: unknown,
): DiffFieldChange | null {
  const b = before ?? null;
  const a = after ?? null;
  if (JSON.stringify(b) === JSON.stringify(a)) return null;
  return { field, before: b, after: a };
}

/** Caveats surfaced on a proposed shadow (consent / data-source lag). */
function shadowCaveats(s: SimulatedShadow): string[] {
  const out: string[] = [];
  if (s.snapshotConsentExpiresAt) {
    const days = Math.ceil(
      (new Date(s.snapshotConsentExpiresAt).getTime() - Date.now()) /
        86_400_000,
    );
    if (!Number.isNaN(days)) {
      if (days < 0) out.push('Consent expired');
      else if (days < 30) out.push(`Consent expires in ${days}d`);
    }
  }
  const ds = s.snapshotDataSource;
  if (ds === 'SWIFT_MT940' || ds === 'SWIFT_MT942') {
    out.push(`${ds} batch lag`);
  } else if (ds === 'OPEN_BANKING') {
    out.push('Open-banking consent dependency');
  }
  return out;
}

function ruleKey(srcPhysIds: string[], tgtPhysId: string): string {
  return `${[...new Set(srcPhysIds)].sort().join(',')}->${tgtPhysId}`;
}

/** Membership-SET key for pool matching (R3). Order-independent. */
function poolKey(memberPhysIds: string[]): string {
  return [...new Set(memberPhysIds)].sort().join(',');
}

export function computeDiff(
  scenarioId: string,
  snapshot: ScenarioSnapshotPayload | null,
  proposed: ProposedStructure,
): DiffResult {
  const snap: ScenarioSnapshotPayload = snapshot ?? {
    takenAt: '',
    shadows: [],
    rules: [],
    pools: [],
  };
  const entries: DiffEntry[] = [];

  // ---- Shadows: match by physical_account_id ------------------------------
  const liveShadowByPhys = new Map(
    snap.shadows.map((s) => [s.physicalAccountId, s]),
  );
  const proposedShadowByPhys = new Map(
    proposed.shadows.map((s) => [s.physicalAccountId, s]),
  );
  const shadowLocalToPhys = new Map(
    proposed.shadows.map((s) => [s.localId, s.physicalAccountId]),
  );

  for (const ps of proposed.shadows) {
    const live = liveShadowByPhys.get(ps.physicalAccountId);
    const caveats = shadowCaveats(ps);
    if (!live) {
      entries.push({
        op: 'ADD',
        entity: 'SHADOW',
        label: ps.proposedVaName || ps.localId,
        proposedRef: { localId: ps.localId },
        caveats: caveats.length ? caveats : undefined,
      });
      continue;
    }
    const fc = [fieldChange('name', live.vaName, ps.proposedVaName)].filter(
      (x): x is DiffFieldChange => x !== null,
    );
    entries.push({
      op: fc.length ? 'MODIFY' : 'UNCHANGED',
      entity: 'SHADOW',
      label: ps.proposedVaName || live.vaName,
      liveRef: { id: live.id, type: 'SHADOW' },
      proposedRef: { localId: ps.localId },
      fieldChanges: fc.length ? fc : undefined,
      caveats: caveats.length ? caveats : undefined,
    });
  }
  for (const ls of snap.shadows) {
    if (!proposedShadowByPhys.has(ls.physicalAccountId)) {
      entries.push({
        op: 'RETIRE',
        entity: 'SHADOW',
        label: ls.vaName,
        liveRef: { id: ls.id, type: 'SHADOW' },
      });
    }
  }

  // ---- Rules: match by source-physical SET + target physical --------------
  const liveRuleByKey = new Map(
    snap.rules.map((r) => [
      ruleKey(r.sourcePhysicalAccountIds, r.targetPhysicalAccountId),
      r,
    ]),
  );
  const seenLiveRuleKeys = new Set<string>();

  for (const pr of proposed.rules) {
    const srcPhys = (pr.sourceLocalIds ?? [])
      .map((id) => shadowLocalToPhys.get(id))
      .filter((x): x is string => Boolean(x));
    const tgtPhys = shadowLocalToPhys.get(pr.targetLocalId) ?? '';
    const key = ruleKey(srcPhys, tgtPhys);
    const live = liveRuleByKey.get(key);

    if (!live) {
      entries.push({
        op: 'ADD',
        entity: 'RULE',
        label: pr.ruleName || pr.localId,
        proposedRef: { localId: pr.localId },
        caveats: pr.isCrossBank
          ? [`Cross-bank${pr.paymentRail ? ` · ${pr.paymentRail}` : ''}`]
          : undefined,
      });
      continue;
    }
    seenLiveRuleKeys.add(key);
    const fc = [
      fieldChange('sweepType', live.sweepType, pr.sweepType),
      fieldChange('frequency', live.frequency, pr.frequency),
      fieldChange('executionTime', live.executionTime, pr.executionTime),
    ].filter((x): x is DiffFieldChange => x !== null);
    entries.push({
      op: fc.length ? 'MODIFY' : 'UNCHANGED',
      entity: 'RULE',
      label: pr.ruleName || live.ruleName,
      liveRef: { id: live.id, type: 'RULE' },
      proposedRef: { localId: pr.localId },
      fieldChanges: fc.length ? fc : undefined,
    });
  }
  for (const lr of snap.rules) {
    const key = ruleKey(lr.sourcePhysicalAccountIds, lr.targetPhysicalAccountId);
    if (!seenLiveRuleKeys.has(key)) {
      entries.push({
        op: 'RETIRE',
        entity: 'RULE',
        label: lr.ruleName,
        liveRef: { id: lr.id, type: 'RULE' },
      });
    }
  }

  // ---- Pools: match by member-physical SET (R3) --------------------------
  const livePools = snap.pools ?? [];
  const livePoolByKey = new Map(
    livePools.map((p) => [poolKey(p.memberPhysicalAccountIds), p]),
  );
  const seenLivePoolKeys = new Set<string>();

  for (const pp of proposed.pools ?? []) {
    const memberPhys = (pp.memberLocalIds ?? [])
      .map((id) => shadowLocalToPhys.get(id))
      .filter((x): x is string => Boolean(x));
    const key = poolKey(memberPhys);
    const live = livePoolByKey.get(key);

    if (!live) {
      entries.push({
        op: 'ADD',
        entity: 'POOL',
        label: pp.poolName || pp.localId,
        proposedRef: { localId: pp.localId },
      });
      continue;
    }
    seenLivePoolKeys.add(key);
    const fc = [
      fieldChange('poolName', live.poolName, pp.poolName),
      fieldChange('poolCurrency', live.poolCurrency, pp.poolCurrency),
      fieldChange('rate%', live.interestRate, pp.poolRatePct),
    ].filter((x): x is DiffFieldChange => x !== null);
    entries.push({
      op: fc.length ? 'MODIFY' : 'UNCHANGED',
      entity: 'POOL',
      label: pp.poolName || live.poolName || live.poolReference || pp.localId,
      liveRef: { id: live.id, type: 'POOL' },
      proposedRef: { localId: pp.localId },
      fieldChanges: fc.length ? fc : undefined,
    });
  }
  for (const lp of livePools) {
    if (!seenLivePoolKeys.has(poolKey(lp.memberPhysicalAccountIds))) {
      entries.push({
        op: 'RETIRE',
        entity: 'POOL',
        label: lp.poolName || lp.poolReference || lp.id,
        liveRef: { id: lp.id, type: 'POOL' },
      });
    }
  }

  const counts = { add: 0, modify: 0, retire: 0, unchanged: 0 };
  for (const e of entries) {
    if (e.op === 'ADD') counts.add++;
    else if (e.op === 'MODIFY') counts.modify++;
    else if (e.op === 'RETIRE') counts.retire++;
    else counts.unchanged++;
  }

  return { scenarioId, snapshotTakenAt: snap.takenAt, entries, counts };
}
