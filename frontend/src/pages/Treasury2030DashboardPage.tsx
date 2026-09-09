import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  RefreshCw, ArrowRight, Plus, Clock, AlertTriangle,
  ArrowLeftRight, FileDown, Repeat, Building2, Wallet, Sparkles,
} from 'lucide-react';
import { Page } from '../components/layout/Page';
import { PageHeader } from '../components/layout/PageHeader';
import { ScopeSelector } from '../components/layout/ScopeSelector';
import { Card, Button } from '../components/ui';
import { FreshnessPill } from '../components/multiBank/FreshnessPill';
import { BankSplitBar, BankShare } from '../components/multiBank/BankSplitBar';
import { cn, formatCurrency } from '../utils';
import { Amount } from '../components/Amount';
import { PositionStrip } from '../components/PositionStrip';
import { usePageHeaderActions } from '../context/PageHeaderContext';
import { useEligibleCampaign } from '../hooks/useEligibleCampaign';
import { CampaignBanner } from '../components/CampaignBanner';
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
  ihbApi,
  IhbLoan,
  IhbDeposit,
  fxRateApi,
  FxRate,
  dashboardApi,
  PendingApprovals,
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
const fmtDay = (d: Date) =>
  `${d.toLocaleDateString('en-US', { weekday: 'short' })} · ${d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' })}`;

const Treasury2030DashboardPage: React.FC<Treasury2030DashboardPageProps> = ({ onNavigate }) => {
  const [summary, setSummary] = useState<MultiBankLiquiditySummary | null>(null);
  const [attention, setAttention] = useState<AttentionItem[]>([]);
  const [txns, setTxns] = useState<Transaction[]>([]);
  const [rules, setRules] = useState<SweepRule[]>([]);
  const [loans, setLoans] = useState<IhbLoan[]>([]);
  const [deposits, setDeposits] = useState<IhbDeposit[]>([]);
  const [fxRates, setFxRates] = useState<FxRate[]>([]);
  const [pending, setPending] = useState<PendingApprovals | null>(null);
  const [corporates, setCorporates] = useState<Corporate[]>([]);
  const [selectedCorporateId, setSelectedCorporateId] = useState('');
  const [view, setView] = useState<AcctView>('currency');
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const { open: openCopilot } = useCopilot();
  const [campaign, dismissCampaign] = useEligibleCampaign();

  const nav = (page?: string) => { if (page) onNavigate?.(page); };

  const load = async (scoped?: string) => {
    const s = scoped || undefined;
    const [mb, att, tx, rl, ln, dp, fx, pa] = await Promise.allSettled([
      multiBankLiquidityApi.getSummary(s),
      cockpitApi.getAttentionItems(s),
      transactionsApi.getRecent(20, s),
      sweepingApi.getAllRules(),
      ihbApi.getAllLoans(),
      ihbApi.getAllDeposits(),
      fxRateApi.getAllActiveRates(),
      dashboardApi.getPendingApprovals(),
    ]);
    if (mb.status === 'fulfilled' && mb.value?.data) setSummary(mb.value.data);
    if (att.status === 'fulfilled') setAttention(att.value);
    if (tx.status === 'fulfilled' && Array.isArray(tx.value?.data)) setTxns(tx.value.data);
    if (rl.status === 'fulfilled' && Array.isArray(rl.value?.data)) setRules(rl.value.data);
    if (ln.status === 'fulfilled' && Array.isArray(ln.value?.data)) setLoans(ln.value.data);
    if (dp.status === 'fulfilled' && Array.isArray(dp.value?.data)) setDeposits(dp.value.data);
    if (fx.status === 'fulfilled' && Array.isArray(fx.value?.data)) setFxRates(fx.value.data);
    if (pa.status === 'fulfilled') setPending(pa.value);
  };

  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const cRes = await corporatesApi.getAll().catch(() => null);
        const cData: any = (cRes as any)?.data ?? cRes;
        if (alive && Array.isArray(cData)) setCorporates(cData);
        else if (alive && Array.isArray(cData?.content)) setCorporates(cData.content);
        await load();
      } finally {
        if (alive) setLoading(false);
      }
    })();
    return () => { alive = false; };
  }, []);

  // Skips only its first (mount-time) run: this effect fires on mount
  // regardless of whether selectedCorporateId "changed" from its initial
  // '', which — before this guard — fired load('') a second time
  // immediately after the mount effect above already called load()
  // unscoped, doubling every request on the page (confirmed live:
  // /ihb/loans, /dashboard/pending-approvals, /treasury/multi-bank/summary
  // and /sweeping/rules each fired 2-3x on a single page load). The mount
  // effect already covers that initial unscoped load. A ref (not an
  // `if (!selectedCorporateId) return`) so switching the scope selector
  // back to "All Corporates" (also selectedCorporateId === '') still
  // reloads instead of silently keeping stale scoped data on screen.
  const didMountScopeLoad = useRef(false);
  useEffect(() => {
    if (!didMountScopeLoad.current) {
      didMountScopeLoad.current = true;
      return;
    }
    void load(selectedCorporateId);
  }, [selectedCorporateId]);

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

  // ─── REAL: IHB loans + deposits maturing within the next 14 days ───
  const maturities = useMemo(() => {
    const now = new Date();
    const horizon = new Date(now.getTime() + 14 * 24 * 60 * 60 * 1000);
    const rows: { id: string; kind: string; reference: string; date: Date; amount: number; label: string }[] = [];
    for (const l of loans) {
      if (!l.maturityDate) continue;
      const d = new Date(l.maturityDate);
      if (isNaN(d.getTime()) || d < now || d > horizon) continue;
      rows.push({ id: l.id, kind: 'Loan', reference: l.loanReference, date: d, amount: l.outstandingAmount ?? l.principalAmount, label: 'Outstanding' });
    }
    for (const dp of deposits) {
      if (!dp.maturityDate) continue;
      const d = new Date(dp.maturityDate);
      if (isNaN(d.getTime()) || d < now || d > horizon) continue;
      rows.push({ id: dp.id, kind: 'Deposit', reference: dp.depositReference, date: d, amount: dp.currentBalance ?? dp.principalAmount, label: 'Balance' });
    }
    return rows.sort((a, b) => a.date.getTime() - b.date.getTime()).slice(0, 7);
  }, [loans, deposits]);

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
      subtitle: formatCurrency(c.effective, c.code),
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
          scope selector + summary on the right. */}
      <div className="lg:h-11 flex flex-col lg:flex-row lg:flex-wrap lg:items-center justify-between gap-3 py-2 lg:py-0 border-b border-neutral-200 dark:border-primary-800 -mt-1">
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
              onCorporateChange={setSelectedCorporateId}
            />
          </div>
          <span className="font-mono text-xs text-neutral-500 dark:text-neutral-400 whitespace-nowrap">
            {acctCount} acct · {bankCount} banks · {model?.currencies.length ?? 0} ccy
          </span>
        </div>
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-[minmax(0,1fr)_320px] gap-4 items-start">
        {/* ─── Main column ─── */}
        <div className="space-y-4 min-w-0">

          {campaign && (
            <CampaignBanner
              campaign={campaign}
              onDismiss={() => dismissCampaign(campaign.id)}
              onCtaClick={() => nav(campaign.ctaHref.replace(/^\//, ''))}
            />
          )}

          {/* Position strip — replaces the old KPI card grid. Scalar counts
              (not currency figures — this app never sums balances across
              currencies into one "consolidated" total; see the FX-honest
              rule below on the currency band) rendered as format="count" so
              they show as plain integers, not through <Amount/>. */}
          <PositionStrip
            cells={[
              { label: 'Held at home bank', value: summary?.homeBankShadows ?? 0, format: 'count', size: 'lg', sublabel: 'accounts', tone: 'success' },
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
              <div className="divide-y divide-neutral-200 dark:divide-primary-800">
                {model.currencies.map((c) => {
                  const homePct = c.effective > 0 ? Math.round((c.home / c.effective) * 100) : 0;
                  return (
                    <div key={c.code} className="flex items-center gap-4 py-2">
                      <span className="font-mono text-xs text-neutral-500 dark:text-neutral-400 w-12 shrink-0">{c.code}</span>
                      <Amount value={c.effective} currency={c.code} showCurrency={false} className="text-[20px] font-semibold text-primary-900 dark:text-neutral-50 w-40 shrink-0" />
                      {/* neutral-100 (#f2f2f3) is nearly the same tone as the
                          page background this sits on, so at 0% fill (5 of
                          6 currencies here have no home-bank balance) the
                          track read as "not rendered" rather than "correctly
                          showing zero". neutral-200 gives the empty track a
                          visible rail regardless of fill amount. */}
                      <div className="flex-1 h-[3px] bg-neutral-200 dark:bg-primary-800/60 rounded-full overflow-hidden" title={`${homePct}% home bank`}>
                        <div className="h-full bg-accent-500" style={{ width: `${homePct}%` }} />
                      </div>
                      <span className="caption w-40 text-right shrink-0">{homePct}% home · {100 - homePct}% external</span>
                    </div>
                  );
                })}
              </div>
            ) : (
              <p className="body-sm">No shadow balances available for this scope.</p>
            )}
          </div>

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
                    {groups.map((g) => (
                      <React.Fragment key={g.key}>
                        <tr>
                          <td colSpan={7} className="px-3 py-[9px]">
                            <span className="label">{g.label}</span>
                            <span className="caption ml-2">{g.meta}</span>
                            <span className="float-right stat-value-xs text-primary-900 dark:text-neutral-50">{g.subtitle}</span>
                          </td>
                        </tr>
                        {g.rows.map((s) => (
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
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          {/* Row 2 — Payments workspace + Sweeps & pooling */}
          <div className="grid grid-cols-1 lg:grid-cols-[1.3fr_1fr] gap-4">

            {/* Payments — Awaiting approval is REAL; other states are nav-only */}
            <Card padding="none" className="overflow-hidden">
              <div className="flex items-center justify-between gap-3 px-4 pt-3 pb-2 border-b border-neutral-100 dark:border-primary-800/60">
                <p className="section-title">
                  Payments
                  <span className="ml-2 inline-flex items-center rounded-full bg-error-100 text-error-700 dark:bg-error-500/15 dark:text-error-300 px-1.5 py-0.5 text-xs font-medium tabular-nums">
                    {pending?.payablesCount ?? 0} awaiting
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
                  <p className="body-sm px-4 py-6 text-center">Nothing awaiting approval.</p>
                ) : (
                  pending!.payables.slice(0, 5).map((p) => (
                    <div key={p.id} className="px-4 py-2.5 border-b border-neutral-100 dark:border-primary-800/60 last:border-0">
                      <div className="flex items-center justify-between gap-3">
                        <div className="min-w-0">
                          <p className="text-sm font-medium text-primary-900 dark:text-neutral-50 truncate">{p.vendorName}</p>
                          <p className="font-mono text-xs text-neutral-500 dark:text-neutral-400">{p.invoiceNumber}</p>
                        </div>
                        <div className="text-right shrink-0">
                          <p className="stat-value-xs text-primary-900 dark:text-neutral-50"><Amount value={p.amount} showCurrency={false} /></p>
                          <p className="caption">due {p.dueDate ? new Date(p.dueDate).toLocaleDateString('en-US', { month: 'short', day: 'numeric' }) : '—'}</p>
                        </div>
                        <Button size="sm" variant="outline" onClick={() => nav('payables')}>Review</Button>
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

            {/* Sweeps & pooling — REAL */}
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
          </div>

          {/* Row 3 — Maturities + Recent statement activity */}
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">

            {/* Maturities · next 14 days — REAL (IHB) */}
            <Card padding="none" className="overflow-hidden">
              <div className="flex items-center gap-1.5 px-4 pt-3 pb-2 border-b border-neutral-100 dark:border-primary-800/60">
                <Clock className="w-3.5 h-3.5 text-neutral-500 dark:text-neutral-400" />
                <p className="section-title">IHB maturities · next 14 days</p>
              </div>
              <div>
                {maturities.length === 0 ? (
                  <p className="body-sm px-4 py-6 text-center">No in-house bank loans or deposits mature in the next 14 days.</p>
                ) : (
                  maturities.map((m) => (
                    <div key={m.id} className="flex items-center justify-between gap-3 px-4 py-2.5 border-b border-neutral-100 dark:border-primary-800/60 last:border-0">
                      <div className="min-w-0">
                        <p className="text-sm text-primary-900 dark:text-neutral-50">
                          {m.kind} · <span className="font-mono">{m.reference}</span>
                        </p>
                        <p className="caption">{m.label} <Amount value={m.amount} showCurrency={false} className="text-xs" /></p>
                      </div>
                      <p className="caption shrink-0 tabular-nums">{fmtDay(m.date)}</p>
                    </div>
                  ))
                )}
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
        <div className="space-y-4 xl:sticky xl:top-5">

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
