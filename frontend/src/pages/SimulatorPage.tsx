import React, {
  useState,
  useEffect,
  useCallback,
  useMemo,
  useRef,
} from 'react';
import toast from 'react-hot-toast';
import {
  Plus,
  FlaskConical,
  Loader2,
  AlertCircle,
  Archive,
  AlertTriangle,
  Building2,
} from 'lucide-react';
import { Card, Button, Select } from '../components/ui';
import { Page } from '../components/layout/Page';
import { PageHeader } from '../components/layout/PageHeader';
import {
  ScopeSelector,
  type ScopeCorporate,
} from '../components/layout/ScopeSelector';
import { usePageHeaderActions } from '../context/PageHeaderContext';
import {
  corporatesApi,
  fxRateApi,
  taxChargeApi,
  type ChargeConfiguration,
} from '../services/api';
import { simulatorApi } from '../services/simulatorApi';
import { auditLog } from '../utils/auditLog';
import { SandboxBadge } from '../components/simulator/SandboxBadge';
import { ScenarioHeader } from '../components/simulator/ScenarioHeader';
import { StructureView } from '../components/simulator/StructureView';
import { DiffView } from '../components/simulator/DiffView';
import { CompareView } from '../components/simulator/CompareView';
import { ForkDialog } from '../components/simulator/ForkDialog';
import type { SimulatorView } from '../components/simulator/ViewSwitcher';
import { AddShadowDrawer } from '../components/simulator/AddShadowDrawer';
import { AddRuleDrawer } from '../components/simulator/AddRuleDrawer';
import { AddPoolDrawer } from '../components/simulator/AddPoolDrawer';
import { ProposeDrawer } from '../components/simulator/ProposeDrawer';
import type {
  SimulatorChargeConfig,
  ScoreResult,
  SimulatedPool,
  SimulatedRule,
  SimulatedShadow,
  SimulatorPhysicalAccount,
  SimulatorScenario,
} from '../components/simulator/types';
import {
  hashScenario,
  normaliseScenario,
  validateScenario,
} from '../utils/simulator/scenarioModel';
import {
  computeScore,
  fxFromMap,
  makeConstants,
} from '../utils/simulator/scoreCalculator';
import { computeDiff } from '../utils/simulator/diffEngine';

// ============================================================================
// SimulatorPage — controller for the Cash Concentration Simulator
// (Phase 1 designer + Phase 2 Optimisation Score).
//
// Owns: corporate scope, scenario list/selection, the editable draft
// (name + shadows + rules), dirty/validation memos, toolbar, audit-trail
// calls, the Add drawers, and (Phase 2, behind `simulator.v2.score`) the
// debounced client-side score recompute + persistence. View components
// receive props; all business logic lives in utils/simulator/*. No live
// operational tables are written.
// ============================================================================

type FxRatesMap = Record<
  string,
  { rate: number; source: string; asOf: string; spreadBps?: number }
>;

function timeAgo(iso?: string): string | undefined {
  if (!iso) return undefined;
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return undefined;
  const secs = Math.max(0, Math.round((Date.now() - then) / 1000));
  if (secs < 60) return 'last saved just now';
  const mins = Math.round(secs / 60);
  if (mins < 60) return `last saved ${mins}m ago`;
  const hrs = Math.round(mins / 60);
  if (hrs < 24) return `last saved ${hrs}h ago`;
  const days = Math.round(hrs / 24);
  return `last saved ${days}d ago`;
}

const SimulatorPage: React.FC = () => {
  const [corporates, setCorporates] = useState<ScopeCorporate[]>([]);
  const [selectedCorporateId, setSelectedCorporateId] = useState<string>('');
  const [scenarios, setScenarios] = useState<SimulatorScenario[]>([]);
  const [selectedScenarioId, setSelectedScenarioId] = useState<string>('');
  const [currentScenario, setCurrentScenario] =
    useState<SimulatorScenario | null>(null);

  // Editable draft — separate from the persisted snapshot.
  const [draftName, setDraftName] = useState<string>('');
  const [draftShadows, setDraftShadows] = useState<SimulatedShadow[]>([]);
  const [draftRules, setDraftRules] = useState<SimulatedRule[]>([]);
  const [draftPools, setDraftPools] = useState<SimulatedPool[]>([]);
  const [inventoryAccounts, setInventoryAccounts] = useState<
    SimulatorPhysicalAccount[]
  >([]);

  const [pendingPhysical, setPendingPhysical] =
    useState<SimulatorPhysicalAccount | null>(null);
  const [shadowDrawerOpen, setShadowDrawerOpen] = useState(false);
  const [ruleDrawerOpen, setRuleDrawerOpen] = useState(false);
  const [poolDrawerOpen, setPoolDrawerOpen] = useState(false);

  const [loadingCorporates, setLoadingCorporates] = useState(false);
  const [loadingScenarios, setLoadingScenarios] = useState(false);
  const [loadingScenario, setLoadingScenario] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // ---- Phase 2: Optimisation Score ----------------------------------------

  const [score, setScore] = useState<ScoreResult | null>(null);
  const [scoring, setScoring] = useState(false);

  // ---- Phase 3: Diff vs live + single-user activation ---------------------
  const [view, setView] = useState<SimulatorView>('structure');
  const [snapshotting, setSnapshotting] = useState(false);
  const [proposeDrawerOpen, setProposeDrawerOpen] = useState(false);
  const [activating, setActivating] = useState(false);
  const [chargeConfigs, setChargeConfigs] = useState<SimulatorChargeConfig[]>(
    [],
  );
  const [fxRatesMap, setFxRatesMap] = useState<FxRatesMap>({});
  const [inventoryDrawerOpen, setInventoryDrawerOpen] = useState(false);

  // ---- Phase 4: Compare + operational-risk lines -------------------------
  const [sourceQualityByPhys, setSourceQualityByPhys] = useState<
    Map<string, { missRate: number; avgLagHours: number }>
  >(new Map());
  const [withholdingByCountry, setWithholdingByCountry] = useState<
    Record<string, number>
  >({});
  const [pooledByPhys, setPooledByPhys] = useState<
    Map<
      string,
      { interestRate: number; poolReference: string; poolCurrency: string }
    >
  >(new Map());
  const [forkSet, setForkSet] = useState<SimulatorScenario[]>([]);
  const [loadingForks, setLoadingForks] = useState(false);
  const [forkDialogOpen, setForkDialogOpen] = useState(false);
  const [forking, setForking] = useState(false);

  // Latest score, read by handleSave WITHOUT being a dependency (recompute
  // is debounced every 500ms — a dep would re-register the toolbar each tick).
  const scoreRef = useRef<ScoreResult | null>(null);
  useEffect(() => {
    scoreRef.current = score;
  }, [score]);

  const fxResolver = useMemo(() => fxFromMap(fxRatesMap), [fxRatesMap]);

  // ---- loads --------------------------------------------------------------

  const loadCorporates = useCallback(async () => {
    setLoadingCorporates(true);
    try {
      const res: any = await corporatesApi.getAll();
      const list: ScopeCorporate[] = Array.isArray(res)
        ? res
        : res?.data ?? [];
      setCorporates(list);
      if (list.length > 0) setSelectedCorporateId(list[0].id);
    } catch (err) {
      console.error('Failed to load corporates:', err);
      setError('Failed to load corporates.');
    } finally {
      setLoadingCorporates(false);
    }
  }, []);

  const loadScenarios = useCallback(async (corporateId: string) => {
    setLoadingScenarios(true);
    setError(null);
    try {
      const list = await simulatorApi.listScenarios(corporateId);
      setScenarios(list);
      setSelectedScenarioId((prev) =>
        list.some((s) => s.id === prev && s.status !== 'ARCHIVED')
          ? prev
          : '',
      );
    } catch (err) {
      console.error('Failed to load scenarios:', err);
      setError('Failed to load scenarios.');
      setScenarios([]);
    } finally {
      setLoadingScenarios(false);
    }
  }, []);

  const applyScenario = useCallback((s: SimulatorScenario) => {
    setCurrentScenario(s);
    setDraftName(s.scenarioName);
    setDraftShadows(s.proposedShadows ?? []);
    setDraftRules(s.proposedRules ?? []);
    setDraftPools(s.proposedPools ?? []);
  }, []);

  const loadScenario = useCallback(
    async (id: string) => {
      setLoadingScenario(true);
      try {
        applyScenario(await simulatorApi.getScenario(id));
      } catch (err) {
        console.error('Failed to load scenario:', err);
        setError('Failed to load the selected scenario.');
        setCurrentScenario(null);
      } finally {
        setLoadingScenario(false);
      }
    },
    [applyScenario],
  );

  useEffect(() => {
    loadCorporates();
  }, [loadCorporates]);

  // ?view= URL sync (read once on mount).
  useEffect(() => {
    const v = new URLSearchParams(window.location.search).get('view');
    if (v === 'diff' || v === 'structure' || v === 'compare') setView(v);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    if (view === 'structure') params.delete('view');
    else params.set('view', view);
    const qs = params.toString();
    window.history.replaceState(
      null,
      '',
      qs ? `${window.location.pathname}?${qs}` : window.location.pathname,
    );
  }, [view]);

  // Capture the frozen diff baseline the first time Diff is opened for a
  // scenario (freeze-on-first; backend writes ONLY snapshot_payload).
  useEffect(() => {
    if (view !== 'diff' || !currentScenario) return;
    if (currentScenario.snapshotPayload) return;
    let cancelled = false;
    setSnapshotting(true);
    simulatorApi
      .snapshotLiveConfig(currentScenario.id, false)
      .then(async (updated) => {
        if (cancelled) return;
        setCurrentScenario(updated);
        await auditLog.record({
          action: 'simulator.scenario.snapshot',
          itemId: updated.id,
          payload: { takenAt: updated.snapshotTakenAt },
        });
      })
      .catch((err) => {
        console.error('Snapshot capture failed:', err);
        if (!cancelled) {
          setError('Failed to capture the live-config baseline.');
        }
      })
      .finally(() => {
        if (!cancelled) setSnapshotting(false);
      });
    return () => {
      cancelled = true;
    };
  }, [view, currentScenario]);

  useEffect(() => {
    if (selectedCorporateId) {
      loadScenarios(selectedCorporateId);
    } else {
      setScenarios([]);
      setSelectedScenarioId('');
    }
  }, [selectedCorporateId, loadScenarios]);

  useEffect(() => {
    setInventoryDrawerOpen(false);
    setView('structure');
    if (selectedScenarioId) {
      loadScenario(selectedScenarioId);
    } else {
      setCurrentScenario(null);
      setDraftName('');
      setDraftShadows([]);
      setDraftRules([]);
      setDraftPools([]);
      setScore(null);
    }
  }, [selectedScenarioId, loadScenario]);

  // Phase 2 — FX rates for cross-currency score disclosure (non-fatal).
  useEffect(() => {
    let cancelled = false;
    fxRateApi
      .getAllActiveRates()
      .then((res: any) => {
        if (cancelled) return;
        const list = Array.isArray(res) ? res : res?.data ?? [];
        const map: FxRatesMap = {};
        for (const r of list) {
          if (r?.fromCurrency && r?.toCurrency && typeof r.rate === 'number') {
            map[`${r.fromCurrency}/${r.toCurrency}`] = {
              rate: r.rate,
              source: r.rateSource || 'fx-rates',
              asOf: r.rateDate || '',
              spreadBps:
                typeof r.spreadBps === 'number' ? r.spreadBps : undefined,
            };
          }
        }
        setFxRatesMap(map);
      })
      .catch((err) =>
        console.warn('[simulator] fx rates unavailable (non-fatal):', err),
      );
    return () => {
      cancelled = true;
    };
  }, []);

  // Phase 2 — page-owned inventory for the score (independent of the
  // inventory drawer, so the score computes before it is opened).
  useEffect(() => {
    if (!selectedCorporateId) return;
    let cancelled = false;
    simulatorApi
      .getInventory(selectedCorporateId)
      .then((inv) => {
        if (!cancelled) setInventoryAccounts(inv.physicalAccounts);
      })
      .catch((err) =>
        console.warn(
          '[simulator] score inventory unavailable (non-fatal):',
          err,
        ),
      );
    return () => {
      cancelled = true;
    };
  }, [selectedCorporateId]);

  // Bank-fee source (rewired 2026-05-16): the bank's real configured charge
  // schedule via taxChargeApi — NOT the deprecated bank_fee_tariff. Configs
  // are not bank-scoped, so fetched once. Non-fatal.
  useEffect(() => {
    let cancelled = false;
    taxChargeApi
      .getChargeConfigs()
      .then((res: any) => {
        if (cancelled) return;
        const list: ChargeConfiguration[] = Array.isArray(res)
          ? res
          : res?.data ?? [];
        setChargeConfigs(
          list.map((c) => ({
            chargeType: c.chargeType,
            chargeCategory: c.chargeCategory,
            fixedAmount: c.fixedAmount,
            percentageRate: c.percentageRate,
            currencyCode: c.currencyCode,
            minimumCharge: c.minimumCharge,
            maximumCharge: c.maximumCharge,
            isCrossBorder: c.isCrossBorder,
            isDomestic: c.isDomestic,
            status: c.status,
          })),
        );
      })
      .catch((err) =>
        console.warn(
          '[simulator] charge configs unavailable (non-fatal):',
          err,
        ),
      );
    return () => {
      cancelled = true;
    };
  }, []);

  // ---- derived ------------------------------------------------------------

  const workingScenario = useMemo<SimulatorScenario | null>(
    () =>
      currentScenario
        ? {
            ...currentScenario,
            scenarioName: draftName,
            proposedShadows: draftShadows,
            proposedRules: draftRules,
            proposedPools: draftPools,
          }
        : null,
    [currentScenario, draftName, draftShadows, draftRules, draftPools],
  );

  const baselineHash = useMemo(
    () => (currentScenario ? hashScenario(currentScenario) : ''),
    [currentScenario],
  );
  const workingHash = useMemo(
    () => (workingScenario ? hashScenario(workingScenario) : ''),
    [workingScenario],
  );

  const nameDirty =
    currentScenario != null &&
    draftName.trim() !== currentScenario.scenarioName;
  const structureDirty =
    currentScenario != null && workingHash !== baselineHash;
  const dirty = nameDirty || structureDirty;

  const validPhysicalAccountIds = useMemo(
    () => new Set(inventoryAccounts.map((a) => a.id)),
    [inventoryAccounts],
  );

  const validation = useMemo(
    () =>
      workingScenario
        ? validateScenario(normaliseScenario(workingScenario), {
            validPhysicalAccountIds,
          })
        : null,
    [workingScenario, validPhysicalAccountIds],
  );

  const canSave =
    dirty && draftName.trim().length > 0 && (validation?.ok ?? true);

  // Phase 3 — pure diff of the frozen snapshot vs the working draft.
  const diffResult = useMemo(
    () =>
      currentScenario
        ? computeDiff(
            currentScenario.id,
            currentScenario.snapshotPayload ?? null,
            {
              shadows: draftShadows,
              rules: draftRules,
              pools: draftPools,
            },
          )
        : null,
    [currentScenario, draftShadows, draftRules, draftPools],
  );

  const proposeReady =
    currentScenario?.status === 'READY' && score != null;

  // Phase 2 — recompute the score on every structure mutation, DEBOUNCED
  // 500ms. Pure + client-side (deterministic; inputs already in memory).
  useEffect(() => {
    if (!workingScenario) {
      setScore(null);
      return;
    }
    setScoring(true);
    const handle = setTimeout(() => {
      try {
        const accountsById = new Map(
          inventoryAccounts.map((a) => [a.id, a] as const),
        );
        const result = computeScore({
          proposedShadows: workingScenario.proposedShadows,
          proposedRules: workingScenario.proposedRules,
          proposedPools: workingScenario.proposedPools,
          accountsById,
          chargeConfigs,
          sourceQualityByPhys,
          withholdingByCountry,
          pooledByPhys,
          constants: makeConstants({
            baseCurrency: workingScenario.baseCurrency || 'USD',
          }),
          fx: fxResolver,
        });
        setScore(result);
      } catch (err) {
        console.error('[simulator] score compute failed:', err);
        setScore(null);
      } finally {
        setScoring(false);
      }
    }, 500);
    return () => clearTimeout(handle);
  }, [
    workingScenario,
    inventoryAccounts,
    chargeConfigs,
    fxResolver,
    sourceQualityByPhys,
    withholdingByCountry,
    pooledByPhys,
  ]);

  // Source-quality (90d aggregate) for the inventory's accounts. R1: the
  // Source-quality line is always shown, so fetch whenever the score does.
  useEffect(() => {
    if (inventoryAccounts.length === 0) return;
    let cancelled = false;
    const ids = inventoryAccounts.map((a) => a.id);
    simulatorApi
      .getSourceQuality(ids)
      .then((rows) => {
        if (cancelled) return;
        setSourceQualityByPhys(
          new Map(
            rows.map((r) => [
              r.physicalAccountId,
              { missRate: r.missRate, avgLagHours: r.avgLagHours },
            ]),
          ),
        );
      })
      .catch((err) =>
        console.warn('[simulator] source-quality unavailable:', err),
      );
    return () => {
      cancelled = true;
    };
  }, [inventoryAccounts]);

  // R2 — notional-pool membership for the inventory's accounts (interest
  // Pool basket). Always shown → fetch whenever the score does. Non-fatal.
  useEffect(() => {
    if (inventoryAccounts.length === 0) return;
    let cancelled = false;
    simulatorApi
      .getPoolMembership(inventoryAccounts.map((a) => a.id))
      .then((rows) => {
        if (cancelled) return;
        setPooledByPhys(
          new Map(
            rows.map((r) => [
              r.physicalAccountId,
              {
                interestRate: r.interestRate,
                poolReference: r.poolReference,
                poolCurrency: r.poolCurrency,
              },
            ]),
          ),
        );
      })
      .catch((err) =>
        console.warn('[simulator] pool-membership unavailable:', err),
      );
    return () => {
      cancelled = true;
    };
  }, [inventoryAccounts]);

  // Tax leakage source: withholding tax-configs ⨝ jurisdictions →
  // rate(%) by ISO bank country. From taxChargeApi (real config). Non-fatal.
  // R1: Tax-leakage line is always shown → fetch whenever the score does.
  useEffect(() => {
    let cancelled = false;
    Promise.all([
      taxChargeApi.getWithholdingTaxConfigs(),
      taxChargeApi.getJurisdictions(),
    ])
      .then(([whtRes, jurRes]: [any, any]) => {
        if (cancelled) return;
        const whts: Array<{ jurisdictionCode?: string; ratePercentage?: number }> =
          Array.isArray(whtRes) ? whtRes : whtRes?.data ?? [];
        const jurs: Array<{ jurisdictionCode?: string; countryCode?: string }> =
          Array.isArray(jurRes) ? jurRes : jurRes?.data ?? [];
        const countryByJur = new Map(
          jurs
            .filter((j) => j.jurisdictionCode && j.countryCode)
            .map((j) => [j.jurisdictionCode as string, j.countryCode as string]),
        );
        const map: Record<string, number> = {};
        for (const w of whts) {
          const country = w.jurisdictionCode
            ? countryByJur.get(w.jurisdictionCode)
            : undefined;
          const rate = w.ratePercentage ?? 0;
          if (country && rate > 0) {
            map[country] = Math.max(map[country] ?? 0, rate);
          }
        }
        setWithholdingByCountry(map);
      })
      .catch((err) =>
        console.warn('[simulator] withholding configs unavailable:', err),
      );
    return () => {
      cancelled = true;
    };
  }, []);

  // Phase 4 — load the fork comparison set when Compare is opened.
  useEffect(() => {
    if (view !== 'compare' || !currentScenario) return;
    let cancelled = false;
    setLoadingForks(true);
    simulatorApi
      .listForks(currentScenario.id)
      .then((set) => {
        if (!cancelled) setForkSet(set);
      })
      .catch((err) => {
        console.error('Failed to load fork set:', err);
        if (!cancelled) setForkSet([]);
      })
      .finally(() => {
        if (!cancelled) setLoadingForks(false);
      });
    return () => {
      cancelled = true;
    };
  }, [view, currentScenario]);

  // ---- handlers -----------------------------------------------------------

  const handleCreate = useCallback(async () => {
    if (!selectedCorporateId) return;
    try {
      const created = await simulatorApi.createScenario({
        corporateId: selectedCorporateId,
        scenarioName: 'Untitled scenario',
        baseCurrency: '',
      });
      await auditLog.record({
        action: 'simulator.scenario.created',
        itemId: created.id,
        payload: {
          corporateId: selectedCorporateId,
          scenarioReference: created.scenarioReference,
        },
      });
      await loadScenarios(selectedCorporateId);
      setSelectedScenarioId(created.id);
      toast.success(`Scenario ${created.scenarioReference} created`);
    } catch (err) {
      console.error('Failed to create scenario:', err);
      toast.error('Could not create the scenario.');
    }
  }, [selectedCorporateId, loadScenarios]);

  const handleSave = useCallback(async () => {
    if (!currentScenario || !canSave) return;
    setSaving(true);
    try {
      const updated = await simulatorApi.updateScenario(currentScenario.id, {
        scenarioName: draftName.trim(),
        proposedShadows: draftShadows,
        proposedRules: draftRules,
        proposedPools: draftPools,
      });
      await auditLog.record({
        action: 'simulator.scenario.saved',
        itemId: updated.id,
        payload: {
          shadowCount: updated.proposedShadows.length,
          ruleCount: updated.proposedRules.length,
          poolCount: updated.proposedPools.length,
        },
        dataStateHash: hashScenario(updated),
      });
      applyScenario(updated);
      setScenarios((prev) =>
        prev.map((s) => (s.id === updated.id ? updated : s)),
      );
      // Phase 2 — freeze the latest computed score onto the row (best-effort;
      // a persistence hiccup must not fail the structure save).
      if (scoreRef.current) {
        simulatorApi
          .saveScore(updated.id, scoreRef.current)
          .catch((err) =>
            console.warn(
              '[simulator] score persist failed (non-fatal):',
              err,
            ),
          );
      }
      toast.success('Scenario saved');
    } catch (err) {
      console.error('Failed to save scenario:', err);
      toast.error('Could not save the scenario.');
    } finally {
      setSaving(false);
    }
  }, [
    currentScenario,
    canSave,
    draftName,
    draftShadows,
    draftRules,
    draftPools,
    applyScenario,
  ]);

  const handleDiscard = useCallback(() => {
    if (currentScenario) applyScenario(currentScenario);
  }, [currentScenario, applyScenario]);

  const handleRefreshSnapshot = useCallback(async () => {
    if (!currentScenario) return;
    setSnapshotting(true);
    try {
      const updated = await simulatorApi.snapshotLiveConfig(
        currentScenario.id,
        true,
      );
      setCurrentScenario(updated);
      await auditLog.record({
        action: 'simulator.scenario.snapshot',
        itemId: updated.id,
        payload: { takenAt: updated.snapshotTakenAt, refresh: true },
      });
      toast.success('Snapshot refreshed');
    } catch (err) {
      console.error('Failed to refresh snapshot:', err);
      toast.error('Could not refresh the snapshot.');
    } finally {
      setSnapshotting(false);
    }
  }, [currentScenario]);

  const handlePropose = useCallback(() => {
    setProposeDrawerOpen(true);
  }, []);

  // ---- Phase 4: fork + compare ----
  const handleFork = useCallback(() => {
    setForkDialogOpen(true);
  }, []);

  const handleConfirmFork = useCallback(async () => {
    if (!currentScenario) return;
    setForking(true);
    try {
      const fork = await simulatorApi.forkScenario(currentScenario.id);
      await auditLog.record({
        action: 'simulator.scenario.forked',
        itemId: fork.id,
        payload: {
          parentId: currentScenario.id,
          forkLabel: fork.forkLabel,
        },
      });
      await loadScenarios(selectedCorporateId);
      setForkDialogOpen(false);
      setView('structure');
      setSelectedScenarioId(fork.id);
      toast.success(`Forked → ${fork.forkLabel ?? fork.scenarioReference}`);
    } catch (err) {
      console.error('Fork failed:', err);
      toast.error('Could not fork the scenario.');
    } finally {
      setForking(false);
    }
  }, [currentScenario, selectedCorporateId, loadScenarios]);

  const buildCompareScore = useCallback(
    (s: SimulatorScenario): ScoreResult | null => {
      try {
        const accountsById = new Map(
          inventoryAccounts.map((a) => [a.id, a] as const),
        );
        return computeScore({
          proposedShadows: s.proposedShadows,
          proposedRules: s.proposedRules,
          accountsById,
          chargeConfigs,
          sourceQualityByPhys,
          withholdingByCountry,
          pooledByPhys,
          constants: makeConstants({ baseCurrency: s.baseCurrency || 'USD' }),
          fx: fxResolver,
        });
      } catch (err) {
        console.error('[simulator] compare score failed:', err);
        return null;
      }
    },
    [
      inventoryAccounts,
      chargeConfigs,
      sourceQualityByPhys,
      withholdingByCountry,
      pooledByPhys,
      fxResolver,
    ],
  );

  const handlePromoteFromCompare = useCallback((s: SimulatorScenario) => {
    setSelectedScenarioId(s.id);
    setView('diff');
  }, []);

  const handleConfirmActivate = useCallback(
    async (opts: { notes?: string; scheduledAt?: string }) => {
      if (!currentScenario) return;
      setActivating(true);
      try {
        await auditLog.record({
          action: 'simulator.scenario.proposed',
          itemId: currentScenario.id,
          payload: { counts: diffResult?.counts, notes: opts.notes },
          dataStateHash: hashScenario(currentScenario),
        });
        const updated = await simulatorApi.activate(currentScenario.id, opts);
        await auditLog.record({
          action: 'simulator.scenario.activated',
          itemId: updated.id,
          payload: { status: updated.status },
        });
        applyScenario(updated);
        setScenarios((prev) =>
          prev.map((s) => (s.id === updated.id ? updated : s)),
        );
        setProposeDrawerOpen(false);
        toast.success('Scenario activated — live rules created');
      } catch (err: any) {
        console.error('Activation failed:', err);
        // Backend rolled back atomically + persisted activationError +
        // reverted status to READY. Reload to reflect that truthfully.
        try {
          applyScenario(await simulatorApi.getScenario(currentScenario.id));
        } catch {
          /* keep prior state */
        }
        await auditLog.record({
          action: 'simulator.scenario.activation_failed',
          itemId: currentScenario.id,
          payload: { message: err?.response?.data?.message ?? String(err) },
        });
        toast.error(
          err?.response?.data?.message ??
            'Activation failed — rolled back, no live changes.',
        );
      } finally {
        setActivating(false);
      }
    },
    [currentScenario, diffResult, applyScenario],
  );

  const handleArchive = useCallback(async () => {
    if (!currentScenario) return;
    if (
      !window.confirm(
        `Archive "${currentScenario.scenarioName}"? It will be removed from the active list.`,
      )
    ) {
      return;
    }
    try {
      await simulatorApi.deleteScenario(currentScenario.id);
      await auditLog.record({
        action: 'simulator.scenario.archived',
        itemId: currentScenario.id,
        payload: { scenarioReference: currentScenario.scenarioReference },
      });
      toast.success('Scenario archived');
      setSelectedScenarioId('');
      setCurrentScenario(null);
      await loadScenarios(selectedCorporateId);
    } catch (err) {
      console.error('Failed to archive scenario:', err);
      toast.error('Could not archive the scenario.');
    }
  }, [currentScenario, selectedCorporateId, loadScenarios]);

  const handleAddPhysical = useCallback((pa: SimulatorPhysicalAccount) => {
    setPendingPhysical(pa);
    setShadowDrawerOpen(true);
  }, []);

  const handleCreateShadow = useCallback((shadow: SimulatedShadow) => {
    setDraftShadows((prev) => [...prev, shadow]);
    setShadowDrawerOpen(false);
    setPendingPhysical(null);
    toast.success(`Added "${shadow.proposedVaName}"`);
  }, []);

  const handleCreateRule = useCallback((rule: SimulatedRule) => {
    setDraftRules((prev) => [...prev, rule]);
    setRuleDrawerOpen(false);
    toast.success(`Added rule "${rule.ruleName}"`);
  }, []);

  const handleCreatePool = useCallback((pool: SimulatedPool) => {
    setDraftPools((prev) => [...prev, pool]);
    setPoolDrawerOpen(false);
    toast.success(`Added pool "${pool.poolName}"`);
  }, []);

  // ---- toolbar ------------------------------------------------------------

  const scenarioOptions = useMemo(
    () => [
      { value: '', label: 'Select scenario…' },
      ...scenarios
        .filter((s) => s.status !== 'ARCHIVED')
        .map((s) => ({
          value: s.id,
          label: `${s.scenarioName} · ${s.scenarioReference}`,
        })),
    ],
    [scenarios],
  );

  usePageHeaderActions(
    () => (
      <>
        <Select
          value={selectedScenarioId}
          onChange={(e: React.ChangeEvent<HTMLSelectElement>) =>
            setSelectedScenarioId(e.target.value)
          }
          options={scenarioOptions}
          selectSize="sm"
          disabled={!selectedCorporateId || loadingScenarios}
          aria-label="Scenario"
        />
        {currentScenario && (
          <Button
            variant="secondary"
            size="sm"
            leftIcon={<Building2 className="w-4 h-4" />}
            onClick={() => setInventoryDrawerOpen(true)}
          >
            Inventory
          </Button>
        )}
        {currentScenario && (
          <Button
            variant="ghost"
            size="sm"
            leftIcon={<Archive className="w-4 h-4" />}
            onClick={handleArchive}
          >
            Archive
          </Button>
        )}
        <Button
          variant="primary"
          size="sm"
          leftIcon={<Plus className="w-4 h-4" />}
          onClick={handleCreate}
          disabled={!selectedCorporateId}
        >
          New scenario
        </Button>
      </>
    ),
    [
      selectedScenarioId,
      scenarioOptions,
      selectedCorporateId,
      loadingScenarios,
      currentScenario,
      handleArchive,
      handleCreate,
    ],
  );

  // ---- render -------------------------------------------------------------

  const lastSavedLabel = timeAgo(currentScenario?.updatedAt);
  const issues = validation?.issues ?? [];

  return (
    <Page>
      <PageHeader
        title="Structure simulator"
        description={
          <div className="flex items-center gap-2 flex-wrap">
            <SandboxBadge />
            <span>
              Sandbox over your three-layer model — design, score, and propose
              cash-concentration structures with no live writes.
            </span>
          </div>
        }
      />

      {error && (
        <div className="flex items-center gap-2 px-4 py-2 rounded-xl border border-error-200 bg-error-50 dark:border-error-500/30 dark:bg-error-500/10 body-sm text-error-700 dark:text-error-300">
          <AlertCircle className="w-4 h-4 shrink-0" />
          {error}
        </div>
      )}

      <ScopeSelector
        mode="corporate-only"
        requireSelection
        corporates={corporates}
        selectedCorporateId={selectedCorporateId}
        onCorporateChange={setSelectedCorporateId}
        loading={loadingCorporates}
      />

      {!selectedCorporateId ? (
        <Card padding="lg">
          <p className="body-sm text-neutral-500 dark:text-neutral-400 text-center">
            Select a corporate to design a cash-concentration scenario.
          </p>
        </Card>
      ) : loadingScenarios || loadingScenario ? (
        <Card padding="lg">
          <div className="flex items-center justify-center gap-2 body-sm text-neutral-500 dark:text-neutral-400">
            <Loader2 className="w-4 h-4 animate-spin" />
            Loading scenarios…
          </div>
        </Card>
      ) : !currentScenario || !workingScenario ? (
        <Card padding="lg">
          <div className="flex flex-col items-center text-center gap-3">
            <FlaskConical className="w-8 h-8 text-warning-500" />
            <div>
              <p className="section-title">No scenario selected</p>
              <p className="body-sm text-neutral-500 dark:text-neutral-400 mt-1">
                {scenarios.filter((s) => s.status !== 'ARCHIVED').length === 0
                  ? 'This corporate has no scenarios yet.'
                  : 'Pick a scenario from the toolbar, or start a new one.'}
              </p>
            </div>
            <Button
              variant="primary"
              size="sm"
              leftIcon={<Plus className="w-4 h-4" />}
              onClick={handleCreate}
            >
              New scenario
            </Button>
          </div>
        </Card>
      ) : (
        <>
          <ScenarioHeader
            scenario={currentScenario}
            draftName={draftName}
            dirty={dirty}
            saving={saving}
            canSave={canSave}
            lastSavedLabel={lastSavedLabel}
            onRename={setDraftName}
            onSave={handleSave}
            onDiscard={handleDiscard}
            onFork={handleFork}
            view={view}
            onViewChange={setView}
            proposeReady={!!proposeReady}
            onPropose={handlePropose}
          />

          {view === 'structure' ? (
            <>
              {issues.length > 0 && (
                <Card padding="sm">
                  <div className="flex items-center gap-2">
                    <AlertTriangle className="w-4 h-4 text-warning-500 shrink-0" />
                    <span className="label">
                      {issues.filter((i) => i.level === 'error').length} error
                      {issues.filter((i) => i.level === 'error').length === 1
                        ? ''
                        : 's'}
                      {' · '}
                      {issues.filter((i) => i.level === 'warning').length}{' '}
                      warning
                      {issues.filter((i) => i.level === 'warning').length === 1
                        ? ''
                        : 's'}
                    </span>
                  </div>
                  <ul className="mt-2 space-y-1">
                    {issues.map((iss, idx) => (
                      <li
                        key={`${iss.code}-${idx}`}
                        className={cnIssue(iss.level)}
                      >
                        {iss.message}
                      </li>
                    ))}
                  </ul>
                </Card>
              )}

              <StructureView
                scenario={workingScenario}
                corporateId={selectedCorporateId}
                onAddPhysical={handleAddPhysical}
                onAddRule={() => setRuleDrawerOpen(true)}
                onAddPool={() => setPoolDrawerOpen(true)}
                onInventoryLoaded={setInventoryAccounts}
                score={score}
                scoring={scoring}
                inventoryDrawerOpen={inventoryDrawerOpen}
                onInventoryDrawerOpenChange={setInventoryDrawerOpen}
              />
            </>
          ) : view === 'compare' ? (
            <CompareView
              scenarios={forkSet}
              buildScore={buildCompareScore}
              loading={loadingForks}
              onPromote={handlePromoteFromCompare}
              onForkMore={handleFork}
            />
          ) : (
            <DiffView
              result={diffResult}
              loading={snapshotting}
              refreshing={snapshotting}
              onRefreshSnapshot={handleRefreshSnapshot}
            />
          )}
        </>
      )}

      <AddShadowDrawer
        open={shadowDrawerOpen}
        physicalAccount={pendingPhysical}
        existingShadows={draftShadows}
        onClose={() => {
          setShadowDrawerOpen(false);
          setPendingPhysical(null);
        }}
        onCreate={handleCreateShadow}
      />

      <AddRuleDrawer
        open={ruleDrawerOpen}
        shadows={draftShadows}
        onClose={() => setRuleDrawerOpen(false)}
        onCreate={handleCreateRule}
      />

      <AddPoolDrawer
        open={poolDrawerOpen}
        shadows={draftShadows}
        onClose={() => setPoolDrawerOpen(false)}
        onCreate={handleCreatePool}
      />

      {currentScenario && (
        <ProposeDrawer
          open={proposeDrawerOpen}
          scenario={currentScenario}
          diff={diffResult}
          busy={activating}
          onClose={() => setProposeDrawerOpen(false)}
          onConfirm={handleConfirmActivate}
        />
      )}

      {currentScenario && (
        <ForkDialog
          open={forkDialogOpen}
          scenario={currentScenario}
          busy={forking}
          onClose={() => setForkDialogOpen(false)}
          onConfirm={handleConfirmFork}
        />
      )}
    </Page>
  );
};

function cnIssue(level: 'error' | 'warning'): string {
  return level === 'error'
    ? 'body-sm text-error-700 dark:text-error-300'
    : 'body-sm text-warning-700 dark:text-warning-300';
}

export default SimulatorPage;
