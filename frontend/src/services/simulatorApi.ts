// ============================================================================
// simulatorApi — scenario CRUD + composite inventory read (Phase 1)
//
// Composes the FROZEN existing contracts (physicalAccountsApi,
// shadowAccountApi) — it never reaches past them into live tables. Maps the
// backend wire shape (`proposedPayload: { shadows, rules }`) to/from the
// flattened frontend domain model. Returns domain objects (not the
// ApiResponse envelope) per the spec's simulatorApi surface.
// ============================================================================

import {
  apiClient,
  physicalAccountsApi,
  shadowAccountApi,
  type ApiResponse,
} from './api';
import type {
  BankFeeTariff,
  CreateScenarioInput,
  ExistingShadowHint,
  ScenarioProposedPayload,
  ScenarioSnapshotPayload,
  PoolMembership,
  ScenarioStatus,
  ScoreResult,
  SourceQuality,
  SimulatorInventory,
  SimulatorPhysicalAccount,
  SimulatorScenario,
  UpdateScenarioPatch,
} from '../components/simulator/types';

const BASE = '/simulator/scenarios';

/** Backend SimulatorScenarioDto.Response shape (payloads arrive as objects). */
interface ScenarioWire {
  id: string;
  corporateId: string;
  scenarioName: string;
  scenarioReference: string;
  description?: string;
  baseCurrency?: string;
  status: ScenarioStatus;
  parentScenarioId?: string;
  forkLabel?: string;
  snapshotTakenAt?: string;
  snapshotPayload?: unknown;
  proposedPayload?: Partial<ScenarioProposedPayload> | null;
  scorePayload?: unknown;
  notes?: string;
  createdAt: string;
  createdBy?: string;
  updatedAt: string;
  updatedBy?: string;
  version?: number;
}

function unwrap<T>(res: ApiResponse<T>): T {
  if (!res || res.success === false) {
    throw new Error(res?.message || res?.error || 'Simulator API request failed');
  }
  return res.data;
}

/** Wire → domain. Tolerates a missing/partial payload (defaults to empty). */
function toScenario(w: ScenarioWire): SimulatorScenario {
  const payload = (w.proposedPayload ?? {}) as Partial<ScenarioProposedPayload>;
  return {
    id: w.id,
    corporateId: w.corporateId,
    scenarioName: w.scenarioName,
    scenarioReference: w.scenarioReference,
    description: w.description,
    baseCurrency: w.baseCurrency ?? '',
    status: w.status,
    parentScenarioId: w.parentScenarioId,
    forkLabel: w.forkLabel,
    snapshotTakenAt: w.snapshotTakenAt,
    snapshotPayload:
      (w.snapshotPayload as ScenarioSnapshotPayload | undefined) ?? null,
    proposedShadows: Array.isArray(payload.shadows) ? payload.shadows : [],
    proposedRules: Array.isArray(payload.rules) ? payload.rules : [],
    proposedPools: Array.isArray(payload.pools) ? payload.pools : [],
    notes: w.notes,
    createdAt: w.createdAt,
    createdBy: w.createdBy ?? '',
    updatedAt: w.updatedAt,
  };
}

/** Domain patch → wire patch (folds shadows/rules back into proposedPayload). */
function toUpdateBody(patch: UpdateScenarioPatch): Record<string, unknown> {
  const body: Record<string, unknown> = {};
  if (patch.scenarioName !== undefined) body.scenarioName = patch.scenarioName;
  if (patch.description !== undefined) body.description = patch.description;
  if (patch.baseCurrency !== undefined) body.baseCurrency = patch.baseCurrency;
  if (patch.status !== undefined) body.status = patch.status;
  if (patch.forkLabel !== undefined) body.forkLabel = patch.forkLabel;
  if (patch.notes !== undefined) body.notes = patch.notes;
  // `proposed_payload` is a WHOLE-OBJECT replace — shadows, rules AND pools
  // must always be sent together (a structure save passes all three; a
  // partial patch would otherwise wipe the omitted arrays).
  if (
    patch.proposedShadows !== undefined ||
    patch.proposedRules !== undefined ||
    patch.proposedPools !== undefined
  ) {
    body.proposedPayload = {
      shadows: patch.proposedShadows ?? [],
      rules: patch.proposedRules ?? [],
      pools: patch.proposedPools ?? [],
    } satisfies ScenarioProposedPayload;
  }
  return body;
}

/** Normalise the rich/lean physical-account API variants into one shape. */
function toSimPhysical(raw: Record<string, any>): SimulatorPhysicalAccount {
  const rel = raw.bankRelationship === 'EXTERNAL' ? 'EXTERNAL' : raw.bankRelationship === 'GROUP' ? 'GROUP' : 'INTERNAL';
  return {
    id: raw.id,
    accountNumber: raw.accountNumber,
    accountName: raw.accountName,
    corporateId: raw.corporateId,
    currencyCode: raw.currency ?? raw.currencyCode ?? '',
    currentBalance: Number(raw.currentBalance ?? 0),
    availableBalance: Number(raw.availableBalance ?? 0),
    status: raw.status,
    bankCode: raw.bankCode ?? '',
    bankName: raw.bankName ?? '',
    bankCountry: raw.bankCountry,
    bankRelationship: rel,
    isHomeBank: raw.isHomeBank,
    isExternalBank: raw.isExternalBank,
    dataSource: raw.dataSource ?? '',
    consentExpiresAt: raw.consentExpiresAt,
    interestRate: raw.interestRate,
    interestType: raw.interestType,
    effectiveInterestRate: raw.effectiveInterestRate ?? raw.effective_interest_rate,
    overdraftLimit: raw.overdraftLimit ?? raw.overdraft_limit,
    overdraftUtilized: raw.overdraftUtilized ?? raw.overdraft_utilized,
    sweepEligible: raw.sweepEligible,
    poolingEligible: raw.poolingEligible,
    shadowVaId: raw.shadowVaId,
    shadowVaNumber: raw.shadowVaNumber,
  };
}

export const simulatorApi = {
  // ---- Scenario CRUD ------------------------------------------------------

  async listScenarios(corporateId: string): Promise<SimulatorScenario[]> {
    const res = await apiClient
      .get<ApiResponse<ScenarioWire[]>>(BASE, { params: { corporateId } })
      .then((r) => r.data);
    return unwrap(res).map(toScenario);
  },

  async getScenario(id: string): Promise<SimulatorScenario> {
    const res = await apiClient
      .get<ApiResponse<ScenarioWire>>(`${BASE}/${id}`)
      .then((r) => r.data);
    return toScenario(unwrap(res));
  },

  async createScenario(input: CreateScenarioInput): Promise<SimulatorScenario> {
    const res = await apiClient
      .post<ApiResponse<ScenarioWire>>(BASE, input)
      .then((r) => r.data);
    return toScenario(unwrap(res));
  },

  async updateScenario(
    id: string,
    patch: UpdateScenarioPatch,
  ): Promise<SimulatorScenario> {
    const res = await apiClient
      .put<ApiResponse<ScenarioWire>>(`${BASE}/${id}`, toUpdateBody(patch))
      .then((r) => r.data);
    return toScenario(unwrap(res));
  },

  async deleteScenario(id: string): Promise<void> {
    await apiClient
      .delete<ApiResponse<void>>(`${BASE}/${id}`)
      .then((r) => r.data);
  },

  // ---- Composite reads — convenience for the page -------------------------

  /**
   * Inventory for the structure designer. Composes the existing frozen APIs;
   * no new backend endpoint. V1 fetches a single large page (sandbox-scale
   * data); revisit if a pilot corporate exceeds it.
   *
   * Two real-world shapes handled (verified against the running backend):
   *  - `physical-accounts` returns `data` as a *paginated* `{ content: [] }`
   *    wrapper, not a bare array.
   *  - the bare `shadow-accounts?corporateId=` (getAll) endpoint 500s; the
   *    working endpoint is `getByCorporate` (`/corporate/{id}`). The existing
   *    Shadow VAs are only an "already mirrored" *hint*, so a failure there
   *    degrades to an empty hint list rather than blanking the inventory.
   */
  async getInventory(corporateId: string): Promise<SimulatorInventory> {
    const paRes = await physicalAccountsApi.getAll(0, 500, corporateId);
    const paData = unwrap(paRes) as unknown;
    const paArray: Record<string, any>[] = Array.isArray(paData)
      ? (paData as Record<string, any>[])
      : ((paData as { content?: Record<string, any>[] })?.content ?? []);
    const physicalAccounts: SimulatorPhysicalAccount[] =
      paArray.map(toSimPhysical);

    let existingShadows: ExistingShadowHint[] = [];
    try {
      const shRes = await shadowAccountApi.getByCorporate(corporateId);
      const shData = unwrap(shRes) as unknown;
      const shArray = Array.isArray(shData)
        ? shData
        : ((shData as { content?: any[] })?.content ?? []);
      existingShadows = shArray.map((s: any) => ({
        id: s.id,
        vaNumber: s.vaNumber,
        vaName: s.vaName,
        linkedPhysicalAccountId: s.linkedPhysicalAccountId,
        currencyCode: s.currencyCode,
        status: s.status,
      }));
    } catch (err) {
      // Non-fatal: the inventory is still usable without the mirror hints.
      console.warn(
        '[simulatorApi] existing-shadows hint unavailable (non-fatal):',
        err,
      );
    }

    return { physicalAccounts, existingShadows };
  },

  // ---- Phase 2: score inputs + persistence --------------------------------

  /**
   * @deprecated 2026-05-16 — the Bank-fees line was rewired onto the bank's
   * real `ChargeConfiguration` (`taxChargeApi.getChargeConfigs`). The
   * `bank_fee_tariff` table + `/simulator/tariffs` are deprecated (table
   * retained, code unwired). Kept only so nothing dangles; do not use.
   */
  async getTariffs(
    bankCodes: string[],
    asOf?: string,
  ): Promise<BankFeeTariff[]> {
    if (!bankCodes || bankCodes.length === 0) return [];
    try {
      const res = await apiClient
        .get<ApiResponse<BankFeeTariff[]>>('/simulator/tariffs', {
          params: { bankCodes: bankCodes.join(','), asOf },
        })
        .then((r) => r.data);
      return unwrap(res) ?? [];
    } catch (err) {
      console.warn('[simulatorApi] tariffs unavailable (non-fatal):', err);
      return [];
    }
  },

  /**
   * Freeze the latest client-computed Optimisation Score onto the scenario
   * row (`score_payload`). The score is computed client-side; this only
   * persists it. Sandbox-only.
   */
  async saveScore(
    scenarioId: string,
    score: ScoreResult,
  ): Promise<SimulatorScenario> {
    const res = await apiClient
      .post<ApiResponse<ScenarioWire>>(`${BASE}/${scenarioId}/score`, score)
      .then((r) => r.data);
    return toScenario(unwrap(res));
  },

  // ---- Phase 3: Diff vs live ---------------------------------------------

  /**
   * Capture (or, with force, refresh) the frozen live-config diff baseline.
   * Sandbox-only — the backend writes ONLY `simulator_scenarios.snapshot_
   * payload`. Returns the scenario with `snapshotPayload` populated; the diff
   * itself is the pure client-side `diffEngine.computeDiff` (deterministic,
   * inputs in memory — same pattern as the score).
   */
  async snapshotLiveConfig(
    scenarioId: string,
    force = false,
  ): Promise<SimulatorScenario> {
    const res = await apiClient
      .post<ApiResponse<ScenarioWire>>(
        `${BASE}/${scenarioId}/snapshot`,
        null,
        { params: { force } },
      )
      .then((r) => r.data);
    return toScenario(unwrap(res));
  },

  /**
   * Phase-4 trailing-90d source-quality per physical account (from the
   * seeded `shadow_sync_log`). Non-fatal — an outage just zeroes the
   * Source-quality line (disclosed in assumptions).
   */
  async getSourceQuality(
    physicalAccountIds: string[],
  ): Promise<SourceQuality[]> {
    if (!physicalAccountIds || physicalAccountIds.length === 0) return [];
    try {
      const res = await apiClient
        .get<ApiResponse<SourceQuality[]>>('/simulator/source-quality', {
          params: { physicalAccountIds: physicalAccountIds.join(',') },
        })
        .then((r) => r.data);
      return unwrap(res) ?? [];
    } catch (err) {
      console.warn('[simulatorApi] source-quality unavailable (non-fatal):', err);
      return [];
    }
  },

  /**
   * R2 — notional-pool membership for the given physical accounts (via their
   * live PHYSICAL_MIRROR Shadow VAs). Feeds the interest-yield Pool basket.
   * Non-fatal — an outage just collapses everything to the External basket.
   */
  async getPoolMembership(
    physicalAccountIds: string[],
  ): Promise<PoolMembership[]> {
    if (!physicalAccountIds || physicalAccountIds.length === 0) return [];
    try {
      const res = await apiClient
        .get<ApiResponse<PoolMembership[]>>('/simulator/pool-membership', {
          params: { physicalAccountIds: physicalAccountIds.join(',') },
        })
        .then((r) => r.data);
      return unwrap(res) ?? [];
    } catch (err) {
      console.warn('[simulatorApi] pool-membership unavailable (non-fatal):', err);
      return [];
    }
  },

  // ---- Phase 4: fork / compare -------------------------------------------

  /** Fork into a sibling A/B/C set (copies the structure; status DRAFT). */
  async forkScenario(scenarioId: string): Promise<SimulatorScenario> {
    const res = await apiClient
      .post<ApiResponse<ScenarioWire>>(`${BASE}/${scenarioId}/fork`, null)
      .then((r) => r.data);
    return toScenario(unwrap(res));
  },

  /** Root + all non-archived siblings of the comparison set. */
  async listForks(scenarioId: string): Promise<SimulatorScenario[]> {
    const res = await apiClient
      .get<ApiResponse<ScenarioWire[]>>(`${BASE}/${scenarioId}/forks`)
      .then((r) => r.data);
    return (unwrap(res) ?? []).map(toScenario);
  },

  /**
   * Single-user activation (Phase 3) — the ONLY sandbox→live writer. Backend
   * is atomic + fail-closed; on failure it throws (status stays READY,
   * `activationError` persisted). Gated by `simulator.v3.activation`.
   */
  async activate(
    scenarioId: string,
    opts: { notes?: string; scheduledAt?: string; activatedBy?: string } = {},
  ): Promise<SimulatorScenario> {
    const res = await apiClient
      .post<ApiResponse<ScenarioWire>>(
        `${BASE}/${scenarioId}/activate`,
        opts,
      )
      .then((r) => r.data);
    return toScenario(unwrap(res));
  },
};
