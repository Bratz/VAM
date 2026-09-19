import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  RefreshCw, ArrowRight, Plus, Clock, AlertTriangle,
  ArrowLeftRight, FileDown, Repeat, Building2, Wallet, Sparkles,
  ChevronDown, ChevronRight,
} from 'lucide-react';
import { ResponsiveContainer, BarChart, Bar, XAxis, YAxis, Tooltip, Cell, LabelList } from 'recharts';
import { Page } from '../components/layout/Page';
import { useNavigation } from '../App';
import { PageHeader } from '../components/layout/PageHeader';
import { ScopeSelector } from '../components/layout/ScopeSelector';
import { Card, Button } from '../components/ui';
import { FreshnessPill } from '../components/multiBank/FreshnessPill';
import { BankSplitBar, BankShare } from '../components/multiBank/BankSplitBar';
import { EntityHierarchyTreemap, FlatBreakdownTreemap, sumBalance } from '../components/dashboard/EntityHierarchyTreemap';
import { GeoExposureMap } from '../components/dashboard/GeoExposureMap';
import { IntercompanyPositionChart } from '../components/dashboard/IntercompanyPositionChart';
import { CashFlowForecastChart, CashFlowWeek } from '../components/dashboard/CashFlowForecastChart';
import { cn, formatCurrency, formatAmountForTile } from '../utils';
import { Amount } from '../components/Amount';
import { PositionStrip } from '../components/PositionStrip';
import { usePageHeaderActions } from '../context/PageHeaderContext';
import { useEligibleCampaign } from '../hooks/useEligibleCampaign';
import { CampaignBanner } from '../components/CampaignBanner';
import { useTheme } from '../design-system/ThemeProvider';
import {
  multiBankLiquidityApi,
  MultiBankLiquiditySummary,
  ShadowSummary,
  corporatesApi,
  Corporate,
  transactionsApi,
  Transaction,
  sweepingApi,
  SweepRule,
  fxRateApi,
  FxRate,
  dashboardApi,
  PendingApprovals,
  balanceStructureApi,
  BalanceHierarchyNode,
  BalanceBreakdownItem,
  intercompanyApiEnhanced,
  SubsidiaryIntercompanyPosition,
  forecastApi,
} from '../services/api';
import { cockpitApi } from '../services/cockpitApi';
import { useCopilot } from '../ai/copilot/CopilotProvider';
import type { AttentionItem, AttentionSeverity } from '../types/cockpit';

// ============================================================================
// Treasury 2030 — v2 "Cash Position" cockpit (replaces the v1
// performance-attribution composition at /dashboard; Classic stays the
// flag-off fallback).
//
// Built from the "Treasury 2030 Wireframe v2" handoff. v2 deliberately
// abandons v1's forecast/attribution model (which presupposed a forecast +
// entity hierarchy most corporates don't have) and takes the structural cue
// from how DBS IDEAL / BofA CashPro actually open: cash position first,
// accounts table second, payments + liquidity actions next, with Aperture's
// multi-bank synthesis layered on top.
//
// Honesty discipline (carried over): every panel here is wired to a real
// Aperture API. Where the wireframe showed a figure with no real source we
// OMIT it rather than fabricate — most notably there is NO synthetic
// cross-currency grand total (the codebase's own Multi-Bank review flagged
// summing USD+EUR+GBP into one number as "worse than no number"). The
// per-currency rail is the FX-honest source of truth. The greyscale
// wireframe styling is NOT copied — it's translated into the Aperture
// design system (Page/PageHeader/Card, semantic tokens, dark mode, the
// .stat-value / .label / typography tier, reused FreshnessPill/BankSplitBar).
// ============================================================================

interface Treasury2030DashboardPageProps {
  onNavigate?: (page: string) => void;
}

// Wireframe severity → Now / Today / FYI triage chip.
const TRIAGE: Record<AttentionSeverity, { label: string; cls: string }> = {
  critical: { label: 'Now',   cls: 'bg-error-100 text-error-700 dark:bg-error-500/15 dark:text-error-300' },
  high:     { label: 'Today', cls: 'bg-warning-100 text-warning-700 dark:bg-warning-500/15 dark:text-warning-300' },
  medium:   { label: 'FYI',   cls: 'bg-primary-100 text-primary-700 dark:bg-primary-800/60 dark:text-primary-200' },
};

// v2's top-tab taxonomy. Per the scope decision these are NOT new global
// chrome — they deep-link into the app's existing routes. "Cash Position" is
// this page; "Reports" is the deferred Treasury-Performance workbook (not
// built yet) so it renders disabled rather than dead-linking.
const TABS: { label: string; page?: string; disabled?: boolean }[] = [
  { label: 'Cash Position' },
  { label: 'Payments', page: 'payables' },
  { label: 'Receivables', page: 'receivables' },
  { label: 'Liquidity', page: 'multi-bank-liquidity' },
  { label: 'FX', page: 'fx-rates' },
  { label: 'Reports', disabled: true },
  { label: 'Admin', page: 'settings' },
];

// CR/DR classification for the statement-activity feed.
const CREDIT_MOVES = new Set<string>([
  'CREDIT', 'TRANSFER_IN', 'SWEEP_IN', 'FEE_CREDIT', 'TOPUP', 'INTEREST',
  'SETTLEMENT_CREDIT', 'EXCEPTION_CREDIT', 'EXCEPTION_RELEASE', 'ROBO_CREDIT', 'POOL_CREDIT',
]);

// Categorical chart palette — six semantic families (not the flat cat-1..8
// tokens: those include primary-800/950-based tones — banner-slate/nav-deep
// — that are near-indistinguishable from this app's dark-mode backgrounds
// when used as a bar fill; confirmed live, a GBP bar in that color was
// effectively invisible in dark mode). Same light/dark-per-hue pattern as
// ForecastingPage.tsx's useChartColors: a deep "700" shade on light
// backgrounds, a bright "400" shade on dark ones, per family.
const CHART_CATEGORICAL_LIGHT = ['#146b80', '#276a54', '#8a5f14', '#a8443c', '#1d4ed8', '#5d6165'];
const CHART_CATEGORICAL_DARK = ['#81bccb', '#7da698', '#b99f72', '#cb8f8a', '#60a5fa', '#b5b6b7'];

// Chart chrome (grid/axis/tooltip) does need to flip with the theme, same
// pattern as ForecastingPage.tsx's useChartColors.
function useChartChrome() {
  const { resolvedMode } = useTheme();
  const isDark = resolvedMode === 'dark';
  return useMemo(() => ({
    tickFill: isDark ? '#b5b6b7' : '#5d6165', // primary-300 / neutral-500
    tooltipBg: isDark ? '#343638' : '#ffffff', // neutral-800
    tooltipText: isDark ? '#f2f2f3' : '#46494c', // neutral-50 / primary-900
    tooltipShadow: '0 4px 12px rgba(70,73,76,0.15)',
    categorical: isDark ? CHART_CATEGORICAL_DARK : CHART_CATEGORICAL_LIGHT,
    // Semantic (not categorical) pair for IC Receivable/Payable — reuses the
    // same green/red family already in CHART_CATEGORICAL_* (indices 1 and 3)
    // rather than inventing new colors, since those two are already verified
    // visible in both themes.
    receivableFill: isDark ? CHART_CATEGORICAL_DARK[1] : CHART_CATEGORICAL_LIGHT[1],
    payableFill: isDark ? CHART_CATEGORICAL_DARK[3] : CHART_CATEGORICAL_LIGHT[3],
  }), [isDark]);
}

// Fetches the latest cash forecast for a corporate and buckets its raw
// lines into Receivables (AR_COLLECTIONS) vs Payables (AP_DISBURSEMENTS)
// per week. `/forecasts/latest`'s own weeklyBuckets carry only a single net
// number (all categories combined) — the AR/AP split isn't available there,
// so this fetches the underlying lines and aggregates client-side into the
// SAME week boundaries the summary already established (not a separately
// computed week grouping, so it can't drift from whatever convention the
// backend uses). corporateId is passed as an explicit header (see
// forecastApi in services/api.ts) rather than relying on the axios
// interceptor's localStorage fallback, so this can't silently show a
// different corporate's forecast than the rest of the page.
interface ForecastWeeklyResult {
  currency: string;
  weeks: CashFlowWeek[];
}

async function fetchForecastWeekly(corporateId: string): Promise<ForecastWeeklyResult> {
  let summary;
  try {
    summary = (await forecastApi.getLatest({ horizonDays: 91 }, corporateId)).data;
  } catch (err: any) {
    if (err?.response?.status !== 404) throw err;
    // No forecast run yet for this corporate — kick one off (synchronous,
    // typically <5s per the endpoint's own doc comment) and retry once.
    await forecastApi.triggerRun(corporateId);
    summary = (await forecastApi.getLatest({ horizonDays: 91 }, corporateId)).data;
  }
  if (!summary || summary.weeklyBuckets.length === 0) return { currency: summary?.currency || 'AED', weeks: [] };

  const lines = (await forecastApi.getLines(summary.runId, {}, corporateId)).data;
  const weeks = summary.weeklyBuckets.map((bucket) => {
    let receivables = 0;
    let payables = 0;
    for (const line of lines) {
      if (line.valueDate < bucket.weekStart || line.valueDate > bucket.weekEnd) continue;
      if (line.categoryCode === 'AR_COLLECTIONS') receivables += line.amountMid;
      else if (line.categoryCode === 'AP_DISBURSEMENTS') payables += line.amountMid;
    }
    return { weekStart: bucket.weekStart, weekEnd: bucket.weekEnd, receivables, payables };
  });
  return { currency: summary.currency, weeks };
}

type AcctView = 'currency' | 'bank';

interface CcyBucket {
  code: string;
  effective: number;
  home: number;
  external: number;
  shadows: ShadowSummary[];
  banks: Map<string, { bic: string; name?: string; home: boolean; amount: number }>;
}
interface BankBucket {
  bic: string;
  name?: string;
  home: boolean;
  shadows: ShadowSummary[];
}

const fmtTime = (iso?: string) => {
  if (!iso) return '—';
  const d = new Date(iso);
  return isNaN(d.getTime()) ? '—' : d.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' });
};
const Treasury2030DashboardPage: React.FC<Treasury2030DashboardPageProps> = ({ onNavigate }) => {
  const chartChrome = useChartChrome();
  const [summary, setSummary] = useState<MultiBankLiquiditySummary | null>(null);
  const [attention, setAttention] = useState<AttentionItem[]>([]);
  const [txns, setTxns] = useState<Transaction[]>([]);
  const [rules, setRules] = useState<SweepRule[]>([]);
  const [fxRates, setFxRates] = useState<FxRate[]>([]);
  const [pending, setPending] = useState<PendingApprovals | null>(null);
  const [corporates, setCorporates] = useState<Corporate[]>([]);
  const [selectedCorporateId, setSelectedCorporateId] = useState('');
  // Program-level scope for the Position breakdown's drill-down — only
  // used by the entity-hierarchy fetch and this section, unlike
  // selectedCorporateId which drives the whole page.
  const [selectedProgramId, setSelectedProgramId] = useState('');
  const [view, setView] = useState<AcctView>('currency');
  // Accounts table groups collapse to just their header/subtotal by default
  // (see the Accounts table render below) — this tracks which group keys
  // have been expanded to show their individual account rows.
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set());
  const [hierarchyRoot, setHierarchyRoot] = useState<BalanceHierarchyNode | null>(null);
  // Program-scoped tree for the Position breakdown's "By entity" drill-down
  // only — deliberately separate from `hierarchyRoot` above. They used to
  // share one fetch/state, which meant drilling into a program in the
  // breakdown widget silently shrank the "Consolidated position" hero
  // figure to that one program's balance, with nothing on the hero card
  // indicating it was no longer showing the full corporate/firm total.
  const [programHierarchyRoot, setProgramHierarchyRoot] = useState<BalanceHierarchyNode | null>(null);
  const [programHierarchyLoading, setProgramHierarchyLoading] = useState(false);
  const [corporateBreakdown, setCorporateBreakdown] = useState<BalanceBreakdownItem[]>([]);
  const [programBreakdown, setProgramBreakdown] = useState<BalanceBreakdownItem[]>([]);
  const [icPositions, setIcPositions] = useState<SubsidiaryIntercompanyPosition[]>([]);
  const [forecast, setForecast] = useState<ForecastWeeklyResult | null>(null);
  const [forecastLoading, setForecastLoading] = useState(false);
  const [positionView, setPositionView] = useState<'corporate' | 'program' | 'entity' | 'geo' | 'intercompany'>('entity');
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const { open: openCopilot } = useCopilot();
  const [campaign, dismissCampaign] = useEligibleCampaign();

  const nav = (page?: string) => { if (page) onNavigate?.(page); };
  const { navigate } = useNavigation();

  // Guards against out-of-order responses: switching the corporate scope
  // fires a second load() while the first (e.g. the slower, heavier
  // "All Corporates" mount-time call) is still in flight. Without this,
  // whichever call's Promise.allSettled happens to resolve last wins and
  // overwrites the screen with stale data — confirmed live: scoping to
  // TestMNC kept showing the All-Corporates consolidated-position figure
  // because that unscoped call (23 accounts) resolved after the lighter
  // scoped one (3 accounts).
  const loadSeq = useRef(0);
  const load = async (scoped?: string) => {
    const seq = ++loadSeq.current;
    const s = scoped || undefined;
    setForecastLoading(!!s);
    const [mb, att, tx, rl, fx, pa, bh, cb, pb, icp, fc] = await Promise.allSettled([
      multiBankLiquidityApi.getSummary(s),
      cockpitApi.getAttentionItems(s),
      transactionsApi.getRecent(20, s),
      sweepingApi.getAllRules(),
      fxRateApi.getAllActiveRates(),
      dashboardApi.getPendingApprovals(s),
      // Firm/corporate-wide only — deliberately NOT narrowed by
      // selectedProgramId. This feeds the "Consolidated position" hero
      // figure, which must keep meaning the full scope regardless of
      // whatever the Position breakdown widget is currently drilled into.
      // See programHierarchyRoot below for the program-scoped tree used by
      // the breakdown's own "By entity" view.
      balanceStructureApi.getHierarchy(s),
      // "By corporate" is always firm-wide — unlike the other breakdowns it
      // doesn't take a scope, since it IS the firm-wide-by-corporate view.
      balanceStructureApi.getByCorporate('AED'),
      // "By program" only makes sense once a specific corporate is picked
      // above — with no corporate selected there's no single owner to
      // attribute a mixed list to, so skip the call entirely rather than
      // silently falling back to a firm-wide mix (confirmed confusing live:
      // a TestMNC program showed up with All Corporates selected, with
      // nothing indicating which corporate it belonged to).
      s ? balanceStructureApi.getByProgram(s, 'AED') : Promise.resolve({ data: [] as BalanceBreakdownItem[] }),
      // Intercompany positions are per-corporate (there's no firm-wide
      // "netted across everyone" view that would mean anything) — same
      // corporate-only gate as "By program" above.
      s ? intercompanyApiEnhanced.getPositions(s) : Promise.resolve({ data: [] as SubsidiaryIntercompanyPosition[] }),
      // Cash forecast is per-corporate only (no firm-wide aggregate exists
      // on the backend) — same corporate-only gate as "By program" above.
      s ? fetchForecastWeekly(s) : Promise.resolve(null as ForecastWeeklyResult | null),
    ]);
    if (seq !== loadSeq.current) return; // a newer load() call superseded this one
    setForecastLoading(false);
    if (mb.status === 'fulfilled' && mb.value?.data) setSummary(mb.value.data);
    if (att.status === 'fulfilled') setAttention(att.value);
    if (tx.status === 'fulfilled' && Array.isArray(tx.value?.data)) setTxns(tx.value.data);
    if (rl.status === 'fulfilled' && Array.isArray(rl.value?.data)) setRules(rl.value.data);
    if (fx.status === 'fulfilled' && Array.isArray(fx.value?.data)) setFxRates(fx.value.data);
    if (pa.status === 'fulfilled') setPending(pa.value);
    if (bh.status === 'fulfilled') setHierarchyRoot(bh.value?.data ?? null);
    if (cb.status === 'fulfilled' && Array.isArray(cb.value?.data)) setCorporateBreakdown(cb.value.data);
    if (pb.status === 'fulfilled' && Array.isArray(pb.value?.data)) setProgramBreakdown(pb.value.data);
    if (icp.status === 'fulfilled' && Array.isArray(icp.value?.data)) setIcPositions(icp.value.data);
    setForecast(fc.status === 'fulfilled' ? fc.value : null);
  };

  useEffect(() => {
    let alive = true;
    (async () => {
      const cRes = await corporatesApi.getAll().catch(() => null);
      const cData: any = (cRes as any)?.data ?? cRes;
      if (alive && Array.isArray(cData)) setCorporates(cData);
      else if (alive && Array.isArray(cData?.content)) setCorporates(cData.content);
    })();
    return () => { alive = false; };
  }, []);

  // Single owner of load(): fires on mount (selectedCorporateId === '') and
  // again on every scope change, including back to "All Corporates". A
  // separate mount-time load() plus a "skip first run" ref guard used to
  // live here, but the ref persists across React 18 StrictMode's dev-only
  // double-invoke of a single effect, so the "skip" flag was already true
  // by the guard's own second (synthetic) invocation, making it fire for
  // real AT mount instead of skipping it — three load() calls per page
  // visit instead of one (confirmed live: /dashboard/pending-approvals
  // fired 3x on a single Dashboard mount, with the extra in-flight
  // responses still landing after navigating away, looking like polling on
  // whatever page came next). load()'s own loadSeq guard already discards
  // a stale response, so one plain effect is safe under StrictMode too.
  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        await load(selectedCorporateId || undefined);
      } finally {
        if (alive) setLoading(false);
      }
    })();
    return () => { alive = false; };
  }, [selectedCorporateId]);

  // Program-scoped hierarchy for the Position breakdown's "By entity" view
  // only (see programHierarchyRoot's declaration for why this is separate
  // from the main load() above). Re-fetches whenever the drilled-into
  // program or the page's corporate scope changes; clears when no program
  // is selected so the breakdown falls back to the firm/corporate-wide tree.
  useEffect(() => {
    if (!selectedProgramId) { setProgramHierarchyRoot(null); return; }
    let alive = true;
    setProgramHierarchyLoading(true);
    balanceStructureApi.getHierarchy(selectedCorporateId || undefined, selectedProgramId)
      .then((res) => { if (alive) setProgramHierarchyRoot(res?.data ?? null); })
      .catch(() => { if (alive) setProgramHierarchyRoot(null); })
      .finally(() => { if (alive) setProgramHierarchyLoading(false); });
    return () => { alive = false; };
  }, [selectedCorporateId, selectedProgramId]);

  // Reused by both the top ScopeSelector and the Position breakdown's
  // "By corporate" drill-click — changing corporate invalidates whatever
  // program was selected under the PREVIOUS corporate.
  const handleCorporateChange = (id: string) => {
    setSelectedProgramId('');
    setSelectedCorporateId(id);
  };

  // "Refresh all (N stale)" — refresh-if-stale across every stale shadow,
  // capped concurrency, then reload once. Mirrors the Multi-Bank page's
  // bulk-refresh contract.
  const handleRefreshAll = async () => {
    setRefreshing(true);
    try {
      const stale: string[] = [];
      for (const b of summary?.banks ?? [])
        for (const c of b.currencies)
          for (const s of c.shadows)
            if (s.stale || s.lastBalanceRefreshStatus === 'STALE') stale.push(s.vaId);
      const CAP = 4;
      for (let i = 0; i < stale.length; i += CAP) {
        await Promise.allSettled(
          stale.slice(i, i + CAP).map((id) => multiBankLiquidityApi.refreshIfStale(id)),
        );
      }
      await load(selectedCorporateId);
    } finally {
      setRefreshing(false);
    }
  };

  const staleCount = summary?.staleCount ?? 0;

  usePageHeaderActions(
    () => (
      <div className="flex items-center gap-2">
        <Button
          variant="outline"
          size="sm"
          leftIcon={<RefreshCw className={cn('w-4 h-4', refreshing && 'animate-spin')} />}
          onClick={handleRefreshAll}
          disabled={refreshing || staleCount === 0}
        >
          Refresh all{staleCount > 0 ? ` (${staleCount} stale)` : ''}
        </Button>
        <Button
          size="sm"
          leftIcon={<Plus className="w-4 h-4" />}
          onClick={() => nav('payables-create')}
        >
          New payment
        </Button>
      </div>
    ),
    [refreshing, staleCount, selectedCorporateId],
  );

  // ─── REAL: per-currency / per-bank composition (FX-honest, no group sum) ──
  const model = useMemo(() => {
    if (!summary) return null;
    const byCcy = new Map<string, CcyBucket>();
    const byBank = new Map<string, BankBucket>();
    for (const bank of summary.banks) {
      for (const c of bank.currencies) {
        const cc: CcyBucket = byCcy.get(c.currencyCode) ?? {
          code: c.currencyCode, effective: 0, home: 0, external: 0,
          shadows: [], banks: new Map(),
        };
        const bb: BankBucket = byBank.get(bank.bankBic) ?? {
          bic: bank.bankBic, name: bank.bankName, home: bank.homeBank, shadows: [],
        };
        for (const s of c.shadows) {
          const eff = s.bankBalanceEffective || 0;
          cc.effective += eff;
          if (bank.homeBank) cc.home += eff; else cc.external += eff;
          cc.shadows.push(s);
          const b = cc.banks.get(bank.bankBic) ?? {
            bic: bank.bankBic, name: bank.bankName, home: bank.homeBank, amount: 0,
          };
          b.amount += eff;
          cc.banks.set(bank.bankBic, b);
          bb.shadows.push(s);
        }
        byCcy.set(c.currencyCode, cc);
        byBank.set(bank.bankBic, bb);
      }
    }
    const currencies = [...byCcy.values()]
      .filter((x) => x.effective !== 0)
      .sort((a, b) => b.effective - a.effective);
    const banks = [...byBank.values()].sort((a, b) => b.shadows.length - a.shadows.length);
    const topCcy = currencies[0];
    const bankShares: BankShare[] = topCcy
      ? [...topCcy.banks.values()].map((b) => ({
          bankBic: b.bic, bankName: b.name, homeBank: b.home,
          amount: b.amount, currencyCode: topCcy.code,
        }))
      : [];
    return { currencies, banks, topCcy, bankShares };
  }, [summary]);

  // ─── REAL: sweep rules, scope-filtered (group-level API, client filter) ──
  const sweepRows = useMemo(() => {
    const list = selectedCorporateId
      ? rules.filter((r) => r.corporateId === selectedCorporateId)
      : rules;
    return list.slice(0, 6);
  }, [rules, selectedCorporateId]);

  // ─── REAL: dedupe FX rates to latest per pair ───
  const fxRows = useMemo(() => {
    const latest = new Map<string, FxRate>();
    for (const r of fxRates) {
      const key = `${r.fromCurrency}/${r.toCurrency}`;
      const prev = latest.get(key);
      if (!prev || new Date(r.rateTimestamp).getTime() > new Date(prev.rateTimestamp).getTime()) {
        latest.set(key, r);
      }
    }
    return [...latest.values()].slice(0, 7);
  }, [fxRates]);

  // Accounts table groups — currency view carries an FX-honest single-currency
  // subtotal; bank view spans currencies so it shows a count, never a sum.
  const groups = useMemo(() => {
    if (!model) return [];
    if (view === 'bank') {
      return model.banks.map((b) => ({
        key: b.bic,
        label: b.name ?? b.bic,
        meta: `${b.bic}${b.home ? ' · home bank' : ''}`,
        subtitle: `${b.shadows.length} account${b.shadows.length === 1 ? '' : 's'}`,
        rows: b.shadows,
      }));
    }
    return model.currencies.map((c) => ({
      key: c.code,
      label: c.code,
      meta: `${c.shadows.length} account${c.shadows.length === 1 ? '' : 's'}`,
      // "available", not "current" — this is a sum of bankBalanceEffective,
      // which nets out holds/commitments. It won't match a manual sum of
      // the rows' Current column below whenever a shadow has money held,
      // so the label has to say which figure it is.
      subtitle: `${formatCurrency(c.effective, c.code)} avail.`,
      rows: c.shadows,
    }));
  }, [model, view]);

  if (loading) {
    return (
      <Page>
        <div className="flex items-center justify-center h-96">
          <RefreshCw className="w-6 h-6 animate-spin text-primary-600 dark:text-primary-200" />
        </div>
      </Page>
    );
  }

  const bankCount = summary?.banks.length ?? 0;
  const acctCount = summary?.totalShadows ?? 0;

  return (
    <Page maxWidth="full">
      <PageHeader
        title="Cash position"
        description={
          <span>
            Cash, accounts, sweeps, maturities, statement activity, the action queue and FX are{' '}
            <span className="font-medium text-success-700 dark:text-success-300">live</span> from connected banks.
            FX-honest: per-currency totals only — there is deliberately no synthetic cross-currency grand total.
          </span>
        }
      />

      {/* Tab strip + corporate scope merged into one 44px row (density pass) —
          tabs deep-link into existing routes (not global chrome) on the left;
          scope selector + summary on the right. min-h, not a fixed h: with
          lg:flex-wrap still on, selecting a corporate adds an active-chip +
          "Clear" button to the scope side, and at ~1280-1440px that pushes
          the row's two children (tabs ~639px + scope ~640px) past the
          available width, wrapping the scope selector onto a second line.
          A fixed h-11 doesn't grow for that second line, so it rendered 42px
          below the row's own box — squarely on top of the campaign banner
          underneath. Reproduced live: picking a corporate on the dashboard
          left the corporate <select> visually overlapping the banner's
          "OFFER" tag. min-h-11 keeps the common single-line case at the
          same 44px while letting a wrapped second line push the banner down
          instead of overlapping it. */}
      <div className="lg:min-h-11 flex flex-col lg:flex-row lg:flex-wrap lg:items-center justify-between gap-3 py-2 lg:py-0 border-b border-neutral-200 dark:border-primary-800 -mt-1">
        <div className="flex items-center gap-1">
          {TABS.map((t, i) => {
            const active = i === 0;
            return (
              <button
                key={t.label}
                type="button"
                disabled={t.disabled || active}
                onClick={() => nav(t.page)}
                title={t.disabled ? 'Treasury Performance workbook — coming soon' : undefined}
                className={cn(
                  'px-3 py-2 text-sm border-b-2 -mb-px transition-colors',
                  active
                    ? 'border-accent-500 text-primary-900 dark:text-neutral-50 font-medium'
                    : t.disabled
                      ? 'border-transparent text-neutral-300 dark:text-neutral-600 cursor-not-allowed'
                      : 'border-transparent text-neutral-500 dark:text-neutral-400 hover:text-primary-800 dark:hover:text-neutral-100',
                )}
              >
                {t.label}
                {/* The title tooltip above is the only explanation for why
                    this tab doesn't respond to a click, and title tooltips
                    don't exist on touch — a phone/tablet user gets zero
                    feedback. Same "Soon" pill already used for coming-soon
                    sidebar nav items (Layout.tsx), reused here for a
                    visible-on-any-input-method affordance and visual
                    consistency with that existing pattern. */}
                {t.disabled && (
                  <span className="ml-1.5 align-middle text-xs font-bold px-1.5 py-0 leading-4 rounded-full uppercase tracking-wide bg-neutral-100 text-neutral-500 dark:bg-primary-800/60 dark:text-neutral-400">
                    Soon
                  </span>
                )}
              </button>
            );
          })}
        </div>

        <div className="flex items-center gap-3">
          <div className="min-w-[220px]">
            <ScopeSelector
              mode="corporate-only"
              bare
              corporates={corporates}
              selectedCorporateId={selectedCorporateId}
              onCorporateChange={handleCorporateChange}
            />
          </div>
          <span className="font-mono text-xs text-neutral-500 dark:text-neutral-400 whitespace-nowrap">
            {acctCount} acct · {bankCount} banks · {model?.currencies.length ?? 0} ccy
          </span>
        </div>
      </div>

      <div className="grid grid-cols-1 2xl:grid-cols-[minmax(0,1fr)_320px] xl:[html[data-sidebar=collapsed]_&]:grid-cols-[minmax(0,1fr)_320px] gap-4 items-start">
        {/* ─── Main column ─── */}
        <div className="space-y-4 min-w-0">

          {campaign && (
            <CampaignBanner
              campaign={campaign}
              onDismiss={() => dismissCampaign(campaign.id)}
              onCtaClick={() => nav(campaign.ctaHref.replace(/^\//, ''))}
            />
          )}

          {/* Position summary — leads with one real headline figure, per
              every fintech dashboard studied (Mercury: "current balance
              with a visible trend"). This is NOT the client-side sum this
              app has always refused to fabricate — hierarchyRoot comes
              from balanceStructureApi.getHierarchy's reportingCurrency
              param, a real backend FX conversion, not raw currencies added
              together. Sublabel says so explicitly. Uses sumBalance (same
              recursive helper the treemap below uses), NOT
              hierarchyRoot.consolidatedBalance directly — that field alone
              undercounts by the same root-doesn't-roll-up-its-children
              issue the treemap already had to work around (confirmed live:
              root.consolidatedBalance read AED 1.9M against a true
              recursive total of AED 36.3M for the same scope). The rest of
              the strip (previously its own KPI row) are scalar counts,
              unchanged. */}
          <PositionStrip
            cells={[
              {
                label: 'Consolidated position',
                value: hierarchyRoot ? sumBalance(hierarchyRoot) : 0,
                currency: 'AED',
                format: 'currency',
                size: 'lg',
                sublabel: 'AED · FX-converted, live rates',
              },
              { label: 'Held at home bank', value: summary?.homeBankShadows ?? 0, format: 'count', sublabel: 'accounts', tone: 'success' },
              { label: 'External, sweepable', value: summary?.externalShadows ?? 0, format: 'count', sublabel: 'accounts' },
              { label: 'Stale balances', value: staleCount, format: 'count', sublabel: 'need refresh', tone: staleCount ? 'warning' : 'neutral' },
              { label: 'Total accounts', value: acctCount, format: 'count', sublabel: `${model?.currencies.length ?? 0} currencies` },
            ]}
          />

          {/* Currency breakdown — hairline, no card. FX-honest: per-currency
              only, deliberately no synthetic cross-currency grand total. */}
          <div className="border-b border-neutral-200 dark:border-primary-800 pb-4">
            <div className="flex items-center justify-between gap-3 pb-2">
              <p className="section-title">Currency breakdown</p>
              <div className="inline-flex rounded-sm border border-neutral-200 dark:border-primary-800 overflow-hidden">
                {(['currency', 'bank'] as AcctView[]).map((v) => (
                  <button
                    key={v}
                    type="button"
                    onClick={() => setView(v)}
                    className={cn(
                      'px-3 py-1 text-xs transition-colors capitalize',
                      view === v
                        ? 'bg-primary-900 text-white dark:bg-accent-500 dark:text-primary-950'
                        : 'text-neutral-600 hover:bg-neutral-100 dark:text-neutral-300 dark:hover:bg-primary-800',
                    )}
                  >
                    By {v}
                  </button>
                ))}
              </div>
            </div>

            {model && model.currencies.length > 0 ? (
              // Real chart, not a text list — bar length is proportional to
              // size so "USD dwarfs SGD" reads instantly; the home/external
              // split each row used to show inline moves to the tooltip
              // (still there, just on demand rather than always-on).
              <div style={{ height: Math.max(model.currencies.length * 32, 80) }}>
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart
                    data={model.currencies}
                    layout="vertical"
                    margin={{ top: 4, right: 56, bottom: 4, left: 4 }}
                  >
                    <XAxis type="number" hide />
                    <YAxis
                      type="category"
                      dataKey="code"
                      width={44}
                      tickLine={false}
                      axisLine={false}
                      tick={{ fontSize: 12, fill: chartChrome.tickFill, fontFamily: 'var(--font-mono)' }}
                    />
                    <Tooltip
                      cursor={{ fill: chartChrome.tickFill, opacity: 0.06 }}
                      contentStyle={{
                        backgroundColor: chartChrome.tooltipBg,
                        border: 'none',
                        borderRadius: '12px',
                        boxShadow: chartChrome.tooltipShadow,
                        padding: '10px 14px',
                        color: chartChrome.tooltipText,
                      }}
                      labelStyle={{ color: chartChrome.tooltipText, fontWeight: 600 }}
                      formatter={(_value: number, _name: string, item: any) => {
                        const c = item.payload as CcyBucket;
                        const homePct = c.effective > 0 ? Math.round((c.home / c.effective) * 100) : 0;
                        return [
                          `${formatCurrency(c.effective, c.code)} — ${homePct}% home · ${100 - homePct}% external`,
                          'Balance',
                        ] as [string, string];
                      }}
                    />
                    <Bar dataKey="effective" radius={[0, 4, 4, 0]} barSize={18} isAnimationActive={false}>
                      {model.currencies.map((c, i) => (
                        <Cell key={c.code} fill={chartChrome.categorical[i % chartChrome.categorical.length]} />
                      ))}
                      <LabelList
                        dataKey="effective"
                        position="right"
                        formatter={(v: number) => formatAmountForTile(v).replace(/^\S+\s/, '')}
                        style={{ fontSize: 12, fill: chartChrome.tickFill }}
                      />
                    </Bar>
                  </BarChart>
                </ResponsiveContainer>
              </div>
            ) : (
              <p className="body-sm">No shadow balances available for this scope.</p>
            )}
          </div>

          {/* Cash flow forecast — Receivables (AR aging) vs Payables (AP
              aging) projected weekly, from the real forecast engine. A
              trend view, complementary to the actionable Payments list
              below (that's "what needs approval now"; this is "what's my
              cash trajectory") — kept as separate cards, not a replacement. */}
          <Card padding="none" className="overflow-hidden">
            <div className="flex items-center justify-between gap-3 px-4 pt-3 pb-2 border-b border-neutral-100 dark:border-primary-800/60">
              <p className="section-title">Cash flow forecast</p>
              {forecast && forecast.weeks.length > 0 && (
                <span className="caption text-neutral-500 dark:text-neutral-400">
                  Next {forecast.weeks.length} weeks
                </span>
              )}
            </div>
            <div className="px-4 py-3">
              <CashFlowForecastChart
                weeklyData={forecast?.weeks ?? []}
                loading={forecastLoading}
                hasCorporate={!!selectedCorporateId}
                currency={forecast?.currency || 'AED'}
                receivableFill={chartChrome.receivableFill}
                payableFill={chartChrome.payableFill}
                tickFill={chartChrome.tickFill}
                tooltipBg={chartChrome.tooltipBg}
                tooltipText={chartChrome.tooltipText}
                tooltipShadow={chartChrome.tooltipShadow}
              />
            </div>
          </Card>

          {/* Payments — real payables the user can act on now (awaiting
              approval, or approved and payable via Pay Now); each row deep-links
              into the Payables page with that action open. Moved ahead of the Accounts table (was after it) —
              this is the treasury team's own actionable work queue
              ("money in motion"), which real dashboard research
              consistently places right after the headline number, not
              buried under a reference table. */}
          <Card padding="none" className="overflow-hidden">
            <div className="flex items-center justify-between gap-3 px-4 pt-3 pb-2 border-b border-neutral-100 dark:border-primary-800/60">
              <p className="section-title">
                Payments
                <span className="ml-2 inline-flex items-center rounded-full bg-error-100 text-error-700 dark:bg-error-500/15 dark:text-error-300 px-1.5 py-0.5 text-xs font-medium tabular-nums">
                  {pending?.payablesCount ?? 0} to action
                </span>
              </p>
              <button
                type="button"
                onClick={() => nav('payables')}
                className="inline-flex items-center gap-1 text-xs font-medium text-primary-600 dark:text-accent-400 hover:underline"
              >
                All payments <ArrowRight className="w-3 h-3" />
              </button>
            </div>
            <div>
              {(pending?.payables?.length ?? 0) === 0 ? (
                <p className="body-sm px-4 py-6 text-center">Nothing to approve or pay.</p>
              ) : (
                pending!.payables.slice(0, 5).map((p) => (
                  <div key={p.id} className="px-4 py-2.5 border-b border-neutral-100 dark:border-primary-800/60 last:border-0">
                    <div className="flex items-center justify-between gap-3">
                      <div className="min-w-0">
                        <p className="text-sm font-medium text-primary-900 dark:text-neutral-50 truncate">{p.vendorName}</p>
                        <p className="font-mono text-xs text-neutral-500 dark:text-neutral-400">{p.invoiceNumber}</p>
                      </div>
                      <div className="text-right shrink-0">
                        <p className="stat-value-xs text-primary-900 dark:text-neutral-50"><Amount value={p.amount} currency={p.currencyCode} showCurrency={false} /></p>
                        <p className="caption">due {p.dueDate ? new Date(p.dueDate).toLocaleDateString('en-US', { month: 'short', day: 'numeric' }) : '—'}</p>
                      </div>
                      <Button size="sm" variant="outline" onClick={() => navigate('payables', { payableId: p.id, action: p.action ?? 'PAY' })}>
                        {p.action === 'APPROVE' ? 'Approve' : 'Pay now'}
                      </Button>
                    </div>
                  </div>
                ))
              )}
              {(pending?.totalPending ?? 0) > 0 && (
                <div className="px-4 py-2 bg-neutral-50 dark:bg-primary-800/40 text-xs text-neutral-500 dark:text-neutral-400 flex items-center justify-between">
                  <span className="tabular-nums">{pending?.totalPending} items pending approval (incl. {pending?.transactionsCount ?? 0} transactions)</span>
                  <button type="button" onClick={() => nav('payables')} className="font-medium text-primary-600 dark:text-accent-400 hover:underline">View all →</button>
                </div>
              )}
            </div>
          </Card>

          {/* Accounts table — hairline: no card, no zebra, two hairline
              weights (heavier under the header, lighter between rows). */}
          <div>
            <div className="flex items-center justify-between gap-3 pb-2">
              <p className="section-title">Accounts <span className="label font-normal">· all banks</span></p>
              <button
                type="button"
                onClick={() => nav('multi-bank-liquidity')}
                className="inline-flex items-center gap-1 text-xs font-medium text-primary-600 dark:text-accent-400 hover:underline"
              >
                Full liquidity view <ArrowRight className="w-3 h-3" />
              </button>
            </div>
            {groups.length === 0 ? (
              <p className="body-sm py-6 text-center">No accounts in scope.</p>
            ) : (
              <div className="overflow-x-auto scroll-fade-x">
                <table className="w-full border-collapse text-sm">
                  <thead>
                    <tr className="text-left">
                      {['Account', 'Entity', 'Bank', 'Current', 'Available', 'Freshness', ''].map((h, i) => (
                        <th
                          key={h || i}
                          className={cn(
                            'label px-3 py-[9px] border-b border-neutral-300 dark:border-primary-700',
                            (i === 3 || i === 4) && 'text-right',
                            i === 5 && 'text-center',
                          )}
                        >
                          {h}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-neutral-200 dark:divide-primary-800/60">
                    {groups.map((g) => {
                      const isExpanded = expandedGroups.has(g.key);
                      return (
                      <React.Fragment key={g.key}>
                        <tr
                          className="cursor-pointer hover:bg-neutral-50/50 dark:hover:bg-primary-800/30"
                          onClick={() => setExpandedGroups((prev) => {
                            const next = new Set(prev);
                            if (next.has(g.key)) next.delete(g.key); else next.add(g.key);
                            return next;
                          })}
                        >
                          <td colSpan={7} className="px-3 py-[9px]">
                            <span className="inline-flex items-center gap-1.5">
                              {isExpanded
                                ? <ChevronDown className="w-3.5 h-3.5 text-neutral-400 dark:text-neutral-500" />
                                : <ChevronRight className="w-3.5 h-3.5 text-neutral-400 dark:text-neutral-500" />}
                              <span className="label">{g.label}</span>
                            </span>
                            <span className="caption ml-2">{g.meta}</span>
                            <span className="float-right stat-value-xs text-primary-900 dark:text-neutral-50">{g.subtitle}</span>
                          </td>
                        </tr>
                        {isExpanded && g.rows.map((s) => (
                          <tr key={s.vaId} className="hover:bg-neutral-50/50 dark:hover:bg-primary-800/30">
                            <td className="px-3 py-[9px]">
                              <div className="text-primary-900 dark:text-neutral-50">{s.vaName}</div>
                              <div className="font-mono text-xs text-neutral-500 dark:text-neutral-400">{s.vaNumber}</div>
                            </td>
                            <td className="px-3 py-[9px] text-neutral-600 dark:text-neutral-300">
                              {s.owningEntityCode || '—'}
                            </td>
                            <td className="px-3 py-[9px]">
                              <div className="text-neutral-600 dark:text-neutral-300">{s.bankName || s.bankBic}</div>
                              <div className="font-mono text-xs text-neutral-400 dark:text-neutral-500">{s.bankBic}</div>
                            </td>
                            <td className="px-3 py-[9px] text-right text-primary-900 dark:text-neutral-50">
                              <Amount value={s.bankBalance} currency={s.currencyCode} showCurrency={false} />
                            </td>
                            <td className="px-3 py-[9px] text-right text-neutral-600 dark:text-neutral-300">
                              {s.bankAvailableBalance != null ? <Amount value={s.bankAvailableBalance} currency={s.currencyCode} showCurrency={false} /> : '—'}
                            </td>
                            <td className="px-3 py-[9px] text-center">
                              <FreshnessPill shadow={s} />
                            </td>
                            <td className="px-3 py-[9px] text-right whitespace-nowrap">
                              {(s.stale || s.lastBalanceRefreshStatus !== 'SUCCESS') && (
                                <button
                                  type="button"
                                  onClick={() => multiBankLiquidityApi.refreshIfStale(s.vaId).then(() => load(selectedCorporateId))}
                                  className="text-xs font-medium text-primary-600 dark:text-accent-400 hover:underline"
                                >
                                  Refresh
                                </button>
                              )}
                            </td>
                          </tr>
                        ))}
                      </React.Fragment>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          {/* Position breakdown — entity treemap and geo map merged into
              ONE card via a toggle (same pattern as Currency breakdown's
              "By Currency / By Bank" above), not two separate sections —
              part of the module consolidation (12 -> 7 main sections)
              this pass makes per the "5-9 core elements" research finding.
              Geo intentionally scoped to the single dominant currency
              (model.topCcy/bankShares, the same FX-honest scope the
              Sweeps & pooling composition bar below already uses) — summing
              bank balances across countries that hold different currencies
              would be exactly the synthetic cross-currency total this page
              elsewhere refuses to fabricate. */}
          <div className="border-b border-neutral-200 dark:border-primary-800 pb-4">
            <div className="flex items-center justify-between gap-3 pb-2">
              <p className="section-title">
                Position breakdown
                {positionView === 'geo' && model?.topCcy && <span className="label ml-2 font-normal">· {model.topCcy.code} (largest balance)</span>}
                {/* Breadcrumb for wherever a corporate/program click has
                    drilled the page's own scope to — lets the user see
                    (and clear) the path without hunting for the top
                    picker. Only corporate/program show here: entity-level
                    drill is local to EntityHierarchyTreemap and shows its
                    own "Back" control instead. */}
                {selectedCorporateId && (positionView === 'program' || positionView === 'entity' || positionView === 'intercompany') && (
                  <span className="label ml-2 font-normal">
                    · {corporates.find((c) => c.id === selectedCorporateId)?.legalName ?? 'Selected corporate'}
                    {selectedProgramId && positionView === 'entity' && (
                      <> › {programBreakdown.find((p) => p.id === selectedProgramId)?.name ?? 'Selected program'}</>
                    )}
                    <button
                      type="button"
                      onClick={() => { handleCorporateChange(''); setPositionView('corporate'); }}
                      className="ml-1.5 underline hover:no-underline"
                    >
                      Clear
                    </button>
                  </span>
                )}
              </p>
              <div className="inline-flex rounded-sm border border-neutral-200 dark:border-primary-800 overflow-hidden">
                {([['corporate', 'By corporate'], ['program', 'By program'], ['entity', 'By entity'], ['geo', 'By country'], ['intercompany', 'By intercompany']] as const).map(([v, label]) => (
                  <button
                    key={v}
                    type="button"
                    onClick={() => setPositionView(v)}
                    className={cn(
                      'px-3 py-1 text-xs transition-colors',
                      positionView === v
                        ? 'bg-primary-900 text-white dark:bg-accent-500 dark:text-primary-950'
                        : 'text-neutral-600 hover:bg-neutral-100 dark:text-neutral-300 dark:hover:bg-primary-800',
                    )}
                  >
                    {label}
                  </button>
                ))}
              </div>
            </div>
            {positionView === 'corporate' ? (
              <FlatBreakdownTreemap
                data={corporateBreakdown.map((c) => ({ id: c.id, name: c.name, size: c.balance }))}
                loading={loading}
                categoricalColors={chartChrome.categorical}
                tooltipBg={chartChrome.tooltipBg}
                tooltipText={chartChrome.tooltipText}
                tooltipShadow={chartChrome.tooltipShadow}
                emptyMessage="No corporate balance data available."
                onItemClick={(item) => {
                  if (!item.id) return;
                  handleCorporateChange(item.id);
                  setPositionView('program');
                }}
              />
            ) : positionView === 'program' ? (
              !selectedCorporateId ? (
                <p className="body-sm py-6 text-center">Select a corporate above to see its programs.</p>
              ) : (
                <FlatBreakdownTreemap
                  data={programBreakdown.map((p) => ({ id: p.id, name: p.name, size: p.balance }))}
                  loading={loading}
                  categoricalColors={chartChrome.categorical}
                  tooltipBg={chartChrome.tooltipBg}
                  tooltipText={chartChrome.tooltipText}
                  tooltipShadow={chartChrome.tooltipShadow}
                  emptyMessage="No program balance data available."
                  onItemClick={(item) => {
                    if (!item.id) return;
                    setSelectedProgramId(item.id);
                    setPositionView('entity');
                  }}
                />
              )
            ) : positionView === 'entity' ? (
              <EntityHierarchyTreemap
                root={selectedProgramId ? programHierarchyRoot : hierarchyRoot}
                loading={loading || (!!selectedProgramId && programHierarchyLoading)}
                categoricalColors={chartChrome.categorical}
                tooltipBg={chartChrome.tooltipBg}
                tooltipText={chartChrome.tooltipText}
                tooltipShadow={chartChrome.tooltipShadow}
                currency={model?.topCcy?.code}
              />
            ) : positionView === 'intercompany' ? (
              !selectedCorporateId ? (
                <p className="body-sm py-6 text-center">Select a corporate above to see its intercompany positions.</p>
              ) : (
                <IntercompanyPositionChart
                  positions={icPositions}
                  loading={loading}
                  receivableFill={chartChrome.receivableFill}
                  payableFill={chartChrome.payableFill}
                  tickFill={chartChrome.tickFill}
                  tooltipBg={chartChrome.tooltipBg}
                  tooltipText={chartChrome.tooltipText}
                  tooltipShadow={chartChrome.tooltipShadow}
                />
              )
            ) : model?.bankShares && model.bankShares.length > 0 ? (
              <GeoExposureMap
                bankShares={model.bankShares}
                currency={model.topCcy!.code}
                tooltipBg={chartChrome.tooltipBg}
                tooltipText={chartChrome.tooltipText}
                tooltipShadow={chartChrome.tooltipShadow}
              />
            ) : (
              <p className="body-sm py-6 text-center">No shadow balances available for this scope.</p>
            )}
          </div>

          {/* Sweeps & pooling + Recent statement activity — paired side by
              side (items-start: same dead-space fix as the old Payments/
              Sweeps row). Both are lower-priority supporting/historical
              content now that Payments moved up to its own full-width slot
              above, so pairing them back into one row recovers the
              horizontal-packing efficiency that splitting the old 2-column
              rows into full-width sections gave up — confirmed live the
              consolidation alone (fewer named sections) still nudged
              scrollHeight up slightly (2364px -> 2501px at 1440x900) purely
              from lost packing, before this pairing recovers it. IHB
              maturities (was its own permanent card, usually an empty state
              — "No in-house bank loans or deposits mature in the next 14
              days" observed live) folds into Sweeps as a conditional line:
              hide-when-empty matches Brex's "surface exceptions, hide
              non-events" principle generalized to non-events generally,
              rather than giving a usually-empty section its own card. */}
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 items-start">
          <Card padding="none" className="overflow-hidden">
            <div className="flex items-center justify-between gap-3 px-4 pt-3 pb-2 border-b border-neutral-100 dark:border-primary-800/60">
              <p className="section-title">Sweeps &amp; pooling</p>
              <button
                type="button"
                onClick={() => nav('sweeping')}
                className="inline-flex items-center gap-1 text-xs font-medium text-primary-600 dark:text-accent-400 hover:underline"
              >
                Configure <ArrowRight className="w-3 h-3" />
              </button>
            </div>
            <div className="p-4 space-y-3">
              {model && model.bankShares.length > 0 && (
                <div>
                  <p className="label mb-1.5">Composition · {model.topCcy?.code} (largest balance)</p>
                  <BankSplitBar bankShares={model.bankShares} />
                </div>
              )}
              <div className="border-t border-neutral-100 dark:border-primary-800/60 pt-2">
                {sweepRows.length === 0 ? (
                  <p className="body-sm py-3 text-center">No sweep rules in scope.</p>
                ) : (
                  sweepRows.map((r) => (
                    <div key={r.id} className="flex items-center justify-between gap-2 py-1.5 border-b border-neutral-100 dark:border-primary-800/60 last:border-0">
                      <div className="min-w-0">
                        <p className="text-sm text-primary-900 dark:text-neutral-50 truncate">{r.ruleName}</p>
                        <p className="caption">{r.sweepType} · {r.frequency}{r.targetAccountNumber ? ` → ${r.targetAccountNumber}` : ''}</p>
                      </div>
                      <span className={cn(
                        'shrink-0 rounded-full px-2 py-0.5 text-xs font-medium',
                        r.status === 'ACTIVE'
                          ? 'bg-success-100 text-success-700 dark:bg-success-500/15 dark:text-success-300'
                          : 'bg-neutral-100 text-neutral-600 dark:bg-primary-800/60 dark:text-neutral-300',
                      )}>
                        {r.status}
                      </span>
                    </div>
                  ))
                )}
              </div>
            </div>
          </Card>

          {/* Recent statement activity — REAL */}
          <Card padding="none" className="overflow-hidden">
            <div className="flex items-center justify-between gap-3 px-4 pt-3 pb-2 border-b border-neutral-100 dark:border-primary-800/60">
              <p className="section-title">Recent statement activity</p>
              <button
                type="button"
                onClick={() => nav('statements')}
                className="inline-flex items-center gap-1 text-xs font-medium text-primary-600 dark:text-accent-400 hover:underline"
              >
                <FileDown className="w-3 h-3" /> Statements
              </button>
            </div>
            <div>
                {txns.length === 0 ? (
                  <p className="body-sm px-4 py-6 text-center">No recent transactions in scope.</p>
                ) : (
                  txns.slice(0, 7).map((t) => {
                    const cr = CREDIT_MOVES.has(t.movementType);
                    return (
                      <div key={t.id} className="flex items-center gap-3 px-4 py-2 border-b border-neutral-100 dark:border-primary-800/60 last:border-0">
                        <span className="caption tabular-nums shrink-0 w-12">{fmtTime(t.transactionDate)}</span>
                        <div className="min-w-0 flex-1">
                          <p className="text-sm text-primary-900 dark:text-neutral-50 truncate">
                            {t.description || t.counterpartyName || t.movementType}
                          </p>
                          <p className="font-mono text-xs text-neutral-400 dark:text-neutral-500 truncate">
                            {t.vaNumber || t.vaName} · {t.referenceNumber}
                          </p>
                        </div>
                        <span className={cn(
                          'shrink-0 rounded px-1.5 py-0.5 text-xs font-medium',
                          cr
                            ? 'bg-success-100 text-success-700 dark:bg-success-500/15 dark:text-success-300'
                            : 'bg-error-100 text-error-700 dark:bg-error-500/15 dark:text-error-300',
                        )}>
                          {cr ? 'CR' : 'DR'}
                        </span>
                        <span className="shrink-0 w-28 text-right tabular-nums text-sm text-primary-900 dark:text-neutral-50">
                          {formatCurrency(t.amount, t.currencyCode)}
                        </span>
                      </div>
                    );
                  })
                )}
              </div>
            </Card>
          </div>
        </div>

        {/* ─── Right rail ─── */}
        <div className="space-y-4 2xl:sticky 2xl:top-5 xl:[html[data-sidebar=collapsed]_&]:sticky xl:[html[data-sidebar=collapsed]_&]:top-5">

          {/* Action queue — REAL */}
          <Card padding="none" className="overflow-hidden">
            <div className="flex items-center justify-between px-4 py-2.5 border-b border-neutral-100 dark:border-primary-800/60">
              <span className="section-title">Action queue</span>
              <span className={cn('text-xs font-medium tabular-nums', attention.length ? 'text-warning-600 dark:text-warning-300' : 'text-neutral-400')}>
                {attention.length}
              </span>
            </div>
            <div>
              {attention.length === 0 ? (
                <p className="body-sm px-4 py-6 text-center">Nothing needs attention right now.</p>
              ) : (
                attention.slice(0, 6).map((it) => {
                  const tri = TRIAGE[it.severity] ?? TRIAGE.medium;
                  return (
                    <div key={it.id} className="px-4 py-2.5 border-b border-neutral-100 dark:border-primary-800/60 last:border-0 hover:bg-neutral-50 dark:hover:bg-primary-800/40">
                      <div className="flex items-center gap-2">
                        <span className={cn('rounded-full px-1.5 py-0 leading-4 text-xs font-medium uppercase tracking-[0.08em]', tri.cls)}>{tri.label}</span>
                        <span className="ml-auto text-xs text-neutral-400 dark:text-neutral-500">{it.timePressure?.displayText}</span>
                      </div>
                      <p className="text-xs font-medium text-primary-900 dark:text-neutral-50 mt-1.5 leading-snug">{it.headline}</p>
                      <p className="text-xs text-neutral-500 dark:text-neutral-400 mt-0.5">{it.detail}</p>
                      {it.actions?.[0] && (
                        <p className="inline-flex items-center gap-1 text-xs font-medium text-primary-600 dark:text-accent-400 mt-1.5">
                          {it.actions[0].label} <ArrowRight className="w-3 h-3" />
                        </p>
                      )}
                    </div>
                  );
                })
              )}
            </div>
          </Card>

          {/* FX · rates — REAL (no fabricated 1d move: no historical source) */}
          <Card padding="none" className="overflow-hidden">
            <div className="flex items-center justify-between px-4 py-2.5 border-b border-neutral-100 dark:border-primary-800/60">
              <span className="section-title">FX rates</span>
              <button
                type="button"
                onClick={() => nav('fx-rates')}
                className="text-xs font-medium text-primary-600 dark:text-accent-400 hover:underline"
              >
                Deal
              </button>
            </div>
            <div>
              {fxRows.length === 0 ? (
                <p className="body-sm px-4 py-6 text-center">No active FX rates.</p>
              ) : (
                fxRows.map((r) => (
                  <div key={r.id} className="flex items-center justify-between gap-2 px-4 py-2 border-b border-neutral-100 dark:border-primary-800/60 last:border-0">
                    <span className="font-mono text-sm text-primary-900 dark:text-neutral-50">{r.fromCurrency}/{r.toCurrency}</span>
                    <span className="tabular-nums text-sm text-primary-900 dark:text-neutral-50">{r.rate}</span>
                    <span className="caption shrink-0 w-20 text-right truncate">{r.rateSource}</span>
                  </div>
                ))
              )}
            </div>
          </Card>

          {/* Quick actions — nav + Copilot */}
          <Card padding="none" className="overflow-hidden">
            <div className="px-4 py-2.5 border-b border-neutral-100 dark:border-primary-800/60">
              <span className="section-title">Quick actions</span>
            </div>
            <div className="grid grid-cols-2 gap-px bg-neutral-100 dark:bg-primary-800/60">
              {[
                { ic: <Plus className="w-4 h-4" />, label: 'New payment', sub: 'Wire · ACH · SEPA', act: () => nav('payables-create') },
                { ic: <ArrowLeftRight className="w-4 h-4" />, label: 'FX deal', sub: 'Spot · forward', act: () => nav('fx-rates') },
                { ic: <FileDown className="w-4 h-4" />, label: 'Statement', sub: 'CAMT · MT940', act: () => nav('statements') },
                { ic: <Repeat className="w-4 h-4" />, label: 'Run sweep', sub: 'Cross-bank', act: () => nav('sweeping') },
                { ic: <Building2 className="w-4 h-4" />, label: 'Open account', sub: 'Partner bank', act: () => nav('accounts') },
                { ic: <Sparkles className="w-4 h-4" />, label: 'Ask Aperture', sub: 'NL assist', act: () => openCopilot() },
              ].map((q) => (
                <button
                  key={q.label}
                  type="button"
                  onClick={q.act}
                  className="bg-white dark:bg-primary-900 p-3 text-left hover:bg-neutral-50 dark:hover:bg-primary-800/40 transition-colors"
                >
                  <span className="inline-flex items-center justify-center w-7 h-7 rounded-md bg-accent-50 dark:bg-accent-500/15 text-accent-700 dark:text-accent-300">
                    {q.ic}
                  </span>
                  <p className="text-sm font-medium text-primary-900 dark:text-neutral-50 mt-1.5">{q.label}</p>
                  <p className="caption">{q.sub}</p>
                </button>
              ))}
            </div>
          </Card>
        </div>
      </div>

      {/* Footer stamps — honest: real connectivity, no unverifiable certs */}
      <div className="flex flex-wrap items-center justify-between gap-3 text-xs text-neutral-400 dark:text-neutral-500 pt-2 border-t border-neutral-100 dark:border-primary-800/60">
        <div className="flex flex-wrap gap-4">
          <span className="inline-flex items-center gap-1.5">
            <Wallet className="w-3.5 h-3.5" /> {bankCount} banks · {acctCount} accounts connected
          </span>
          <span className="inline-flex items-center gap-1.5">
            <AlertTriangle className={cn('w-3.5 h-3.5', staleCount ? 'text-warning-500' : 'text-neutral-400')} />
            {staleCount} stale balance{staleCount === 1 ? '' : 's'}
          </span>
        </div>
        <span>Aperture · Treasury Cockpit</span>
      </div>
    </Page>
  );
};

export default Treasury2030DashboardPage;
