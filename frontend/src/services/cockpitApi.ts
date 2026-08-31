// ============================================================================
// Treasurer's Morning Cockpit — API surface.
//
// V1 implementation. Composes attention items and the today-horizon from
// existing endpoints client-side; V2 will replace this with dedicated
// `/cockpit/attention` and `/cockpit/horizon` server-side endpoints. The
// public surface (`cockpitApi.getAttentionItems` / `.getTodayHorizon` /
// `.snoozeItem` / `.executeAction`) is permanent — V2 is a wiring change
// only.
//
// V1 producer mapping:
//
//   Category             | V1 source                                         | Producer
//   ---------------------+---------------------------------------------------+--------------------------
//   funding_shortfall    | payablesApi.getAll(SCHEDULED) cross-checked vs    | produceFundingShortfalls
//                        |   per-account available balance + credit lines.   |
//                        |   V1 stub: surface near-term scheduled payments   |
//                        |   without the full credit-line check.             |
//   sweep_failure        | sweepingApi.getHistory() filtered status=FAILED   | produceSweepFailures
//                        |   in last 24h.                                    |
//   stuck_transaction    | transactionsApi.getAll({status:'PENDING'}) older  | produceStuckTransactions
//                        |   than the cutoff threshold.                      |
//   stale_balance        | multiBankLiquidityApi.getSummary() filtered to    | produceStaleBalances
//                        |   stale or status=FAILED|NEVER shadows.           |
//   pending_approval     | dashboardApi.getPendingApprovals() — no server-   | producePendingApprovals
//                        |   side permission filter in V1.                   |
//   fx_exposure          | STUB — needs `fx_policy_band` reference table.    | produceFxExposures
//   concentration_risk   | STUB — needs `bank_credit_rating` reference.      | produceConcentrationRisks
//   loan_rollover        | ihbApi.getAllLoans() filtered for maturity today. | produceLoanRollovers
//
// Naming note: this is intentionally `cockpitApi`, NOT `exceptionsApi` —
// the existing `exceptionApi` (services/api.ts ~4705) handles a different
// domain (unallocated settlement transactions). Don't confuse the two.
// ============================================================================

import {
  apiClient,
  multiBankLiquidityApi,
  dashboardApi,
  payablesApi,
  sweepingApi,
  transactionsApi,
  ihbApi,
  type MultiBankLiquiditySummary,
  type SweepExecution,
  type IhbLoan,
  type Transaction,
  type Payable,
} from './api';
import {
  AttentionItem,
  AttentionSeverity,
  AttentionTimePressure,
  TodayHorizon,
  FxRateDisclosure,
} from '../types/cockpit';

// ============================================================================
// Time-pressure helpers — every producer formats display strings consistently.
// ============================================================================

/** Format a positive ms duration as compact "Nh Mm" / "Nm" / "Ns". */
function formatDuration(ms: number): string {
  const seconds = Math.floor(ms / 1000);
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  const restMin = minutes - hours * 60;
  if (hours < 24) return restMin > 0 ? `${hours}h ${restMin}m` : `${hours}h`;
  const days = Math.floor(hours / 24);
  const restHours = hours - days * 24;
  return restHours > 0 ? `${days}d ${restHours}h` : `${days}d`;
}

function pressureFromFutureIso(iso: string): AttentionTimePressure {
  const ms = new Date(iso).getTime() - Date.now();
  if (ms <= 0) {
    return {
      kind: 'relative_past',
      displayText: `${formatDuration(-ms)} ago`,
      millisecondsSinceEvent: -ms,
    };
  }
  return {
    kind: 'relative_future',
    displayText: `in ${formatDuration(ms)}`,
    millisecondsToDeadline: ms,
    anchorHourLocal: new Date(iso).getHours(),
  };
}

function pressureFromPastIso(iso: string): AttentionTimePressure {
  const ms = Date.now() - new Date(iso).getTime();
  return {
    kind: 'relative_past',
    displayText: `${formatDuration(Math.max(0, ms))} ago`,
    millisecondsSinceEvent: Math.max(0, ms),
  };
}

function pressureFromAge(iso: string): AttentionTimePressure {
  const ms = Date.now() - new Date(iso).getTime();
  return {
    kind: 'age',
    displayText: `stale ${formatDuration(Math.max(0, ms))}`,
    millisecondsSinceEvent: Math.max(0, ms),
  };
}

// ============================================================================
// Producers — one per category. Each is independently failure-isolated by
// the orchestrator's `Promise.allSettled`.
// ============================================================================

async function produceFundingShortfalls(_entityId?: string): Promise<AttentionItem[]> {
  // V1 simplification: surface SCHEDULED payments due within the next 8h.
  // The full check (vs available balance + credit line) is a V2 producer
  // change — schema unaffected.
  try {
    const res = await payablesApi.getAll(0, 50, undefined, 'SCHEDULED');
    const items: Payable[] = (res?.data ?? []) as Payable[];
    const cutoffMs = 8 * 60 * 60 * 1000;
    const now = Date.now();
    return items
      .filter((p) => {
        const dueDate = (p as any).paymentDate ?? (p as any).dueDate;
        if (!dueDate) return false;
        const t = new Date(dueDate).getTime();
        return t - now <= cutoffMs && t - now > -60 * 60 * 1000; // include up to 1h overdue
      })
      .map((p): AttentionItem => {
        const dueDate = (p as any).paymentDate ?? (p as any).dueDate;
        const pressure = pressureFromFutureIso(dueDate);
        const amount = (p as any).amount ?? 0;
        const currency = (p as any).currencyCode ?? 'USD';
        const isLarge = amount >= 5_000_000;
        return {
          id: `funding_shortfall:${p.id}`,
          severity: pressure.millisecondsToDeadline! < 2 * 60 * 60 * 1000 ? 'critical' : 'high',
          category: 'funding_shortfall',
          headline: `Funding shortfall on ${(p as any).vendorName ?? 'scheduled payment'}`,
          detail: `${currency} ${amount.toLocaleString()} due ${pressure.displayText} · invoice ${(p as any).invoiceNumber ?? p.id}`,
          context: {
            currencyCode: currency,
            nativeAmount: { value: amount, currency },
            reference: (p as any).invoiceNumber,
            counterpartyName: (p as any).vendorName,
          },
          timePressure: pressure,
          actions: [
            { label: 'Fund', kind: 'execute', actionId: 'fund', requiresConfirmation: true, requiresSecondFactor: isLarge },
            { label: 'Investigate', kind: 'drawer', actionId: 'inspect', requiresConfirmation: false },
          ],
          createdAt: new Date().toISOString(),
        };
      });
  } catch (err) {
    // eslint-disable-next-line no-console
    console.warn('[cockpit] produceFundingShortfalls failed:', err);
    return [];
  }
}

async function produceSweepFailures(_entityId?: string): Promise<AttentionItem[]> {
  try {
    const res = await sweepingApi.getHistory();
    // /sweeping/history returns a Spring Page envelope — the executions live
    // in `data.content`, not `data` itself. Guard both shapes so a backend
    // contract change degrades to "no items" instead of a TypeError.
    const raw: any = res?.data;
    const executions: SweepExecution[] = Array.isArray(raw)
      ? raw
      : Array.isArray(raw?.content) ? raw.content : [];
    const since = Date.now() - 24 * 60 * 60 * 1000;
    return executions
      .filter((e) => e.status === 'FAILED' && new Date(e.executionTime).getTime() >= since)
      .map((e): AttentionItem => {
        const pressure = pressureFromPastIso(e.executionTime);
        const isLarge = (e.sweepAmount ?? 0) >= 5_000_000;
        return {
          id: `sweep_failure:${e.id}`,
          severity: 'high',
          category: 'sweep_failure',
          headline: `Sweep failed: ${e.ruleName ?? e.ruleReference ?? e.ruleId}`,
          detail: `${e.currencyCode ?? ''} ${(e.sweepAmount ?? 0).toLocaleString()} ${e.sourceAccountNumber ?? ''} → ${e.targetAccountNumber ?? ''} · ${e.errorMessage ?? 'no error message'}`,
          context: {
            currencyCode: e.currencyCode,
            nativeAmount: e.currencyCode ? { value: e.sweepAmount, currency: e.currencyCode } : undefined,
            reference: e.executionReference,
          },
          timePressure: pressure,
          actions: [
            { label: 'Re-run', kind: 'execute', actionId: 'rerun_sweep', requiresConfirmation: true, requiresSecondFactor: isLarge },
            { label: 'Investigate', kind: 'drawer', actionId: 'inspect', requiresConfirmation: false },
          ],
          createdAt: e.executionTime,
        };
      });
  } catch (err) {
    // eslint-disable-next-line no-console
    console.warn('[cockpit] produceSweepFailures failed:', err);
    return [];
  }
}

async function produceStuckTransactions(_entityId?: string): Promise<AttentionItem[]> {
  // V1 cutoff: PENDING transactions older than 4 hours.
  try {
    const res = await transactionsApi.getAll({ status: 'PENDING', pageSize: 50 });
    const list = (res?.data?.content ?? []) as Transaction[];
    const cutoff = Date.now() - 4 * 60 * 60 * 1000;
    return list
      .filter((t) => new Date(t.transactionDate).getTime() <= cutoff)
      .map((t): AttentionItem => {
        const pressure = pressureFromPastIso(t.transactionDate);
        return {
          id: `stuck_transaction:${t.id}`,
          severity: pressure.millisecondsSinceEvent! > 24 * 60 * 60 * 1000 ? 'critical' : 'medium',
          category: 'stuck_transaction',
          headline: `Stuck ${t.movementType.toLowerCase()} on ${t.vaName ?? t.vaNumber ?? t.vaId}`,
          detail: `${t.currencyCode} ${t.amount.toLocaleString()} · ${t.description ?? ''} · ${t.referenceNumber}`,
          context: {
            currencyCode: t.currencyCode,
            nativeAmount: { value: t.amount, currency: t.currencyCode },
            reference: t.referenceNumber,
            counterpartyName: t.counterpartyName,
          },
          timePressure: pressure,
          actions: [
            { label: 'Investigate', kind: 'drawer', actionId: 'inspect', requiresConfirmation: false },
            { label: 'Reverse', kind: 'execute', actionId: 'reverse', requiresConfirmation: true },
          ],
          createdAt: t.createdAt,
        };
      });
  } catch (err) {
    // eslint-disable-next-line no-console
    console.warn('[cockpit] produceStuckTransactions failed:', err);
    return [];
  }
}

async function produceStaleBalances(_entityId?: string): Promise<AttentionItem[]> {
  try {
    const res = await multiBankLiquidityApi.getSummary();
    const summary: MultiBankLiquiditySummary | undefined = res?.data;
    if (!summary) return [];
    const out: AttentionItem[] = [];
    for (const bank of summary.banks) {
      for (const ccy of bank.currencies) {
        for (const s of ccy.shadows) {
          const stale = s.stale || s.lastBalanceRefreshStatus === 'FAILED' || s.lastBalanceRefreshStatus === 'NEVER';
          if (!stale) continue;
          const lastIso = s.lastBalanceRefreshAt;
          const pressure: AttentionTimePressure = lastIso
            ? pressureFromAge(lastIso)
            : { kind: 'age', displayText: 'never refreshed' };
          out.push({
            id: `stale_balance:${s.vaId}`,
            severity:
              s.lastBalanceRefreshStatus === 'FAILED' ? 'high'
              : pressure.millisecondsSinceEvent && pressure.millisecondsSinceEvent > 48 * 60 * 60 * 1000 ? 'high'
              : 'medium',
            category: 'stale_balance',
            headline: `Stale balance · ${s.vaName ?? s.vaNumber} (${s.currencyCode})`,
            detail: `${bank.bankName ?? bank.bankBic} · last refresh ${pressure.displayText} · status ${s.lastBalanceRefreshStatus}`,
            context: {
              bankBic: bank.bankBic,
              bankName: bank.bankName,
              currencyCode: s.currencyCode,
              entityId: s.owningEntityCode,
              reference: s.vaNumber,
            },
            timePressure: pressure,
            actions: [
              { label: 'Refresh', kind: 'execute', actionId: 'refresh_shadow', requiresConfirmation: false },
              { label: 'Open multi-bank', kind: 'navigate', actionId: 'multi-bank-liquidity', requiresConfirmation: false },
            ],
            createdAt: lastIso ?? new Date().toISOString(),
          });
        }
      }
    }
    return out;
  } catch (err) {
    // eslint-disable-next-line no-console
    console.warn('[cockpit] produceStaleBalances failed:', err);
    return [];
  }
}

async function producePendingApprovals(_entityId?: string): Promise<AttentionItem[]> {
  try {
    const res = await dashboardApi.getPendingApprovals();
    const out: AttentionItem[] = [];
    for (const n of res.nettingCycles) {
      const pressure = pressureFromPastIso(n.createdAt);
      out.push({
        id: `pending_approval:netting:${n.id}`,
        severity: 'medium',
        category: 'pending_approval',
        headline: `Approval pending · netting cycle ${n.reference}`,
        detail: `${n.name} · ${n.amount.toLocaleString()} · savings ${n.savingsAmount.toLocaleString()}`,
        context: { reference: n.reference, nativeAmount: undefined },
        timePressure: pressure,
        actions: [
          { label: 'Review', kind: 'drawer', actionId: 'review', requiresConfirmation: false },
          { label: 'Open netting', kind: 'navigate', actionId: 'netting', requiresConfirmation: false },
        ],
        createdAt: n.createdAt,
      });
    }
    for (const k of res.kycApplications) {
      const pressure = pressureFromPastIso(k.submittedAt);
      out.push({
        id: `pending_approval:kyc:${k.id}`,
        severity: 'medium',
        category: 'pending_approval',
        headline: `Approval pending · KYC ${k.name}`,
        detail: `Submitted ${pressure.displayText}`,
        context: { entityId: k.corporateId, entityName: k.name },
        timePressure: pressure,
        actions: [
          { label: 'Review', kind: 'navigate', actionId: 'kyc', requiresConfirmation: false },
        ],
        createdAt: k.submittedAt,
      });
    }
    for (const p of res.payables) {
      const pressure = pressureFromFutureIso(p.dueDate);
      out.push({
        id: `pending_approval:payable:${p.id}`,
        severity: pressure.millisecondsToDeadline && pressure.millisecondsToDeadline < 4 * 60 * 60 * 1000 ? 'high' : 'medium',
        category: 'pending_approval',
        headline: `Approval pending · payable ${p.invoiceNumber}`,
        detail: `${p.vendorName} · ${p.amount.toLocaleString()} · due ${pressure.displayText}`,
        context: { reference: p.invoiceNumber, counterpartyName: p.vendorName },
        timePressure: pressure,
        actions: [
          { label: 'Review', kind: 'navigate', actionId: 'payables', requiresConfirmation: false },
        ],
        createdAt: new Date().toISOString(),
      });
    }
    for (const t of res.transactions) {
      const pressure = pressureFromPastIso(t.createdAt);
      out.push({
        id: `pending_approval:transaction:${t.id}`,
        severity: 'medium',
        category: 'pending_approval',
        headline: `Approval pending · transaction ${t.reference}`,
        detail: `${t.amount.toLocaleString()} · ${t.description}`,
        context: { reference: t.reference },
        timePressure: pressure,
        actions: [
          { label: 'Review', kind: 'navigate', actionId: 'transactions', requiresConfirmation: false },
        ],
        createdAt: t.createdAt,
      });
    }
    return out;
  } catch (err) {
    // eslint-disable-next-line no-console
    console.warn('[cockpit] producePendingApprovals failed:', err);
    return [];
  }
}

// V2 producers — schema accepts these now; producers return [] until the
// reference data they depend on lands.

async function produceFxExposures(_entityId?: string): Promise<AttentionItem[]> {
  // TODO V2: needs the `fx_policy_band` reference table to know which
  // exposures cross the policy band. Schema accepts the items today.
  return [];
}

async function produceConcentrationRisks(_entityId?: string): Promise<AttentionItem[]> {
  // TODO V2: needs the `bank_credit_rating` reference table to know which
  // counterparty banks tip the portfolio over its concentration limit.
  return [];
}

async function produceLoanRollovers(_entityId?: string): Promise<AttentionItem[]> {
  try {
    const res = await ihbApi.getAllLoans();
    const loans: IhbLoan[] = (res?.data ?? []) as IhbLoan[];
    const today = new Date();
    const todayStr = today.toISOString().slice(0, 10);
    return loans
      .filter((l) => l.maturityDate?.slice(0, 10) === todayStr && l.status === 'ACTIVE')
      .map((l): AttentionItem => {
        const pressure = pressureFromFutureIso(l.maturityDate);
        const isLarge = l.outstandingAmount >= 5_000_000;
        return {
          id: `loan_rollover:${l.id}`,
          severity: 'high',
          category: 'loan_rollover',
          headline: `Loan matures today · ${l.loanReference}`,
          detail: `Outstanding ${l.outstandingAmount.toLocaleString()} · rate ${l.interestRate}%`,
          context: {
            reference: l.loanReference,
            entityId: l.borrowerId,
          },
          timePressure: pressure,
          actions: [
            { label: 'Review', kind: 'drawer', actionId: 'review', requiresConfirmation: false },
            { label: 'Roll over', kind: 'execute', actionId: 'rollover', requiresConfirmation: true, requiresSecondFactor: isLarge },
          ],
          createdAt: l.createdAt,
        };
      });
  } catch (err) {
    // eslint-disable-next-line no-console
    console.warn('[cockpit] produceLoanRollovers failed:', err);
    return [];
  }
}

// ============================================================================
// Sort key — severity first (critical → high → medium), then time pressure
// (least time first; for past events, most time elapsed first).
// ============================================================================

const SEVERITY_RANK: Record<AttentionSeverity, number> = { critical: 0, high: 1, medium: 2 };

function compareItems(a: AttentionItem, b: AttentionItem): number {
  const sevDiff = SEVERITY_RANK[a.severity] - SEVERITY_RANK[b.severity];
  if (sevDiff !== 0) return sevDiff;
  // Same severity: deadline-first events sort by least-time-remaining.
  const aDeadline = a.timePressure.millisecondsToDeadline ?? Number.POSITIVE_INFINITY;
  const bDeadline = b.timePressure.millisecondsToDeadline ?? Number.POSITIVE_INFINITY;
  if (aDeadline !== bDeadline) return aDeadline - bDeadline;
  // Past events: most-time-elapsed first.
  const aSince = a.timePressure.millisecondsSinceEvent ?? 0;
  const bSince = b.timePressure.millisecondsSinceEvent ?? 0;
  return bSince - aSince;
}

// ============================================================================
// V1 FX disclosure stub — hardcoded WMR fix rates. V2 reads from a rate
// service; the schema is permanent.
// ============================================================================

const V1_FX_DISCLOSURE: FxRateDisclosure = {
  baseCurrency: 'USD',
  rates: [
    { pair: 'GBP/USD', rate: 1.2812, source: 'WMR 10:00 fix', asOf: new Date().toISOString() },
    { pair: 'EUR/USD', rate: 1.0731, source: 'WMR 10:00 fix', asOf: new Date().toISOString() },
    { pair: 'AED/USD', rate: 0.2722, source: 'WMR 10:00 fix', asOf: new Date().toISOString() },
  ],
};

function rateToBase(currency: string): number {
  if (currency === V1_FX_DISCLOSURE.baseCurrency) return 1;
  const direct = V1_FX_DISCLOSURE.rates.find((r) => r.pair === `${currency}/${V1_FX_DISCLOSURE.baseCurrency}`);
  if (direct) return direct.rate;
  // Unknown currencies fall through at 1:1 — V2 fetches missing rates on
  // demand. The footer disclosure makes the gap visible to the user.
  return 1;
}

// ============================================================================
// Public surface
// ============================================================================

export const cockpitApi = {
  /**
   * Compose attention items from all producers. Producer failures contribute
   * zero items (and a warning to the console) — they never block the feed.
   * Returned items are sorted by severity, then time pressure.
   */
  async getAttentionItems(entityId?: string): Promise<AttentionItem[]> {
    const results = await Promise.allSettled([
      produceFundingShortfalls(entityId),
      produceSweepFailures(entityId),
      produceStuckTransactions(entityId),
      produceStaleBalances(entityId),
      producePendingApprovals(entityId),
      produceFxExposures(entityId),
      produceConcentrationRisks(entityId),
      produceLoanRollovers(entityId),
    ]);
    const items: AttentionItem[] = [];
    for (const r of results) {
      if (r.status === 'fulfilled') items.push(...r.value);
    }
    items.sort(compareItems);
    return items;
  },

  /**
   * Compose today's horizon. V1 derives most numbers from the multi-bank
   * summary + recent transactions; the FX disclosure is the V1 hardcoded
   * stub above. V2 swaps to a dedicated `/cockpit/horizon` endpoint.
   */
  async getTodayHorizon(entityId?: string): Promise<TodayHorizon> {
    // Phase 11 defect fix (2026-05-17): the parameter was previously named
    // `_entityId` and ignored, so the cockpit's Today-Horizon panel never
    // responded to the corporate picker. Thread it into the corporate-aware
    // inputs. `dashboardApi.getStats()` stays corporate-agnostic — its
    // endpoint takes no corporateId; it is a minor contributor and is
    // flagged for a V2 follow-up rather than faked with an ignored param.
    const [mbSettled, txSettled, statsSettled] = await Promise.allSettled([
      multiBankLiquidityApi.getSummary(entityId || undefined),
      transactionsApi.getRecent(200, entityId || undefined),
      dashboardApi.getStats(),
    ]);

    // Per-currency aggregation from multi-bank.
    const summary = mbSettled.status === 'fulfilled' ? mbSettled.value?.data : undefined;
    const byCurrencyMap = new Map<string, { net: number; outflows: number; inflows: number }>();
    if (summary) {
      for (const bank of summary.banks) {
        for (const ccy of bank.currencies) {
          const cur = byCurrencyMap.get(ccy.currencyCode) ?? { net: 0, outflows: 0, inflows: 0 };
          cur.net += ccy.totalEffective || 0;
          byCurrencyMap.set(ccy.currencyCode, cur);
        }
      }
    }

    // Hourly flow buckets — last/next 24h split out of recent transactions.
    const hourlyFlows = Array.from({ length: 24 }, (_, hour) => ({ hour, inflow: 0, outflow: 0 }));
    const transactions = txSettled.status === 'fulfilled' ? (txSettled.value?.data ?? []) : [];
    const todayStart = new Date();
    todayStart.setHours(0, 0, 0, 0);
    let scheduledOut = 0;
    let expectedIn = 0;
    const next8h = Date.now() + 8 * 60 * 60 * 1000;
    for (const t of transactions as Transaction[]) {
      const tDate = new Date(t.transactionDate || t.createdAt);
      const tMs = tDate.getTime();
      if (tDate < todayStart) continue;
      const hour = tDate.getHours();
      const isInflow = ['CREDIT', 'TRANSFER_IN', 'SWEEP_IN', 'POOL_CREDIT', 'TOPUP', 'INTEREST', 'SETTLEMENT_CREDIT'].includes(t.movementType);
      const baseAmount = t.amount * rateToBase(t.currencyCode);
      if (isInflow) {
        hourlyFlows[hour].inflow += baseAmount;
        if (tMs > Date.now() && tMs <= next8h) expectedIn += baseAmount;
      } else {
        hourlyFlows[hour].outflow += baseAmount;
        if (tMs > Date.now() && tMs <= next8h) scheduledOut += baseAmount;
      }
      // Also add per-currency to the byCurrency map's flow lanes.
      const c = byCurrencyMap.get(t.currencyCode) ?? { net: 0, outflows: 0, inflows: 0 };
      if (tMs > Date.now() && tMs <= next8h) {
        if (isInflow) c.inflows += t.amount;
        else c.outflows += t.amount;
      }
      byCurrencyMap.set(t.currencyCode, c);
    }

    const stats = statsSettled.status === 'fulfilled' ? statsSettled.value : undefined;
    const opening = stats?.totalBalance ?? 0;
    const netEod = opening + expectedIn - scheduledOut;

    return {
      baseCurrency: V1_FX_DISCLOSURE.baseCurrency,
      netPositionEndOfDay: netEod,
      openingBalance: opening,
      scheduledOutflowsNext8h: scheduledOut,
      expectedInflowsNext8h: expectedIn,
      // V1 doesn't have a credit-headroom endpoint. Use availableBalance as
      // a proxy; V2 plugs in the real credit-line aggregate.
      creditHeadroom: stats?.availableBalance ?? 0,
      hourlyFlows,
      fxDisclosure: V1_FX_DISCLOSURE,
      byCurrency: Array.from(byCurrencyMap.entries())
        .map(([currencyCode, agg]) => ({
          currencyCode,
          netPositionEndOfDay: agg.net,
          scheduledOutflowsNext8h: agg.outflows,
          expectedInflowsNext8h: agg.inflows,
        }))
        .sort((a, b) => a.currencyCode.localeCompare(b.currencyCode)),
    };
  },

  /**
   * Snooze an item until end-of-day. V1 fires a POST and returns; the inbox
   * removes the item optimistically and refetches when convenient.
   */
  async snoozeItem(itemId: string, reason: string): Promise<void> {
    try {
      await apiClient.post(`/cockpit/v1/attention/${encodeURIComponent(itemId)}/snooze`, { reason });
    } catch (err) {
      // eslint-disable-next-line no-console
      console.warn('[cockpit] snoozeItem failed (non-blocking):', err);
    }
  },

  /**
   * Execute an action on an attention item. Returns the audit ID (the
   * audit trail wraps every action). Failures throw — callers are expected
   * to surface a toast.
   */
  async executeAction(itemId: string, actionId: string, payload?: unknown): Promise<{ auditId: string }> {
    const res = await apiClient.post<{ auditId: string }>(
      `/cockpit/v1/attention/${encodeURIComponent(itemId)}/actions/${encodeURIComponent(actionId)}`,
      payload ?? {},
    );
    return res.data ?? { auditId: '' };
  },
};
