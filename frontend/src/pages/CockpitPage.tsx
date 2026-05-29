import React, { useCallback, useEffect, useMemo, useState } from 'react';
import toast from 'react-hot-toast';
import { Page } from '../components/layout/Page';
import {
  dashboardApi,
  multiBankLiquidityApi,
  transactionsApi,
  corporatesApi,
  programsApi,
  TreasurySummary,
  Transaction,
} from '../services/api';
import { cockpitApi } from '../services/cockpitApi';
import { auditLog } from '../utils/auditLog';
import { telemetry } from '../utils/telemetry';
import { featureFlags } from '../utils/featureFlags';
import {
  AttentionItem,
  AttentionAction,
  TodayHorizon,
} from '../types/cockpit';
import { GreetingStrip } from '../components/cockpit/GreetingStrip';
import { AttentionInbox } from '../components/cockpit/AttentionInbox';
import { InboxFilterKey, parseInboxFilter } from '../components/cockpit/inboxFilter';
import { AttentionDrawer } from '../components/cockpit/AttentionDrawer';
import { TodayHorizonPanel } from '../components/cockpit/TodayHorizonPanel';
import { MultiBankBand } from '../components/cockpit/MultiBankBand';
import { ContextStrip, ContextStripTransaction } from '../components/cockpit/ContextStrip';

// ============================================================================
// Treasurer's Morning Cockpit — page coordinator.
//
// MVC controller: owns all state (attention items, horizon, treasury,
// transactions, drawer, filter, refreshing, lastRefreshedAt), all side
// effects (fetches, refresh, snooze, action execute), and the URL sync for
// `?filter=`. View components receive everything via props.
//
// Layout, top to bottom:
//   1. GreetingStrip   — name + time + market session + selector + freshness
//   2. AttentionInbox  — the dominant above-the-fold element
//   3. TodayHorizonPanel — second above-the-fold band
//   4. MultiBankBand   — wraps OverviewView in compact mode
//   5. ContextStrip    — operational tiles + sparkline + recent activity
//
// Bands fetch independently (the cockpit doesn't gate the page on any one
// fetch). A failure in one band renders a quiet empty state in that band
// only.
// ============================================================================

interface CockpitPageProps {
  onNavigate: (page: string) => void;
}

const SNOOZE_KEY = 'cockpit.snoozedItems.v1';

// V1 snooze persistence: localStorage. V2 backend will own this.
function loadSnoozedItems(): Record<string, string> {
  try {
    const raw = localStorage.getItem(SNOOZE_KEY);
    if (!raw) return {};
    const parsed = JSON.parse(raw) as Record<string, string>;
    // Drop entries past their snoozedUntil (auto-cleanup).
    const now = Date.now();
    const live: Record<string, string> = {};
    for (const [id, until] of Object.entries(parsed)) {
      if (new Date(until).getTime() > now) live[id] = until;
    }
    return live;
  } catch {
    return {};
  }
}

function saveSnoozedItems(snoozed: Record<string, string>): void {
  try {
    localStorage.setItem(SNOOZE_KEY, JSON.stringify(snoozed));
  } catch {
    // localStorage full / disabled — V1 just degrades to in-memory snooze.
  }
}

const CockpitPage: React.FC<CockpitPageProps> = ({ onNavigate }) => {
  // Inbox filter — URL persisted at `?filter=`.
  const [filter, setFilter] = useState<InboxFilterKey>(() =>
    parseInboxFilter(new URLSearchParams(window.location.search).get('filter')),
  );

  useEffect(() => {
    const u = new URL(window.location.href);
    if (filter === 'all') u.searchParams.delete('filter');
    else u.searchParams.set('filter', filter);
    window.history.replaceState(null, '', u);
  }, [filter]);

  // Data state — each band loads independently.
  const [items, setItems] = useState<AttentionItem[]>([]);
  const [horizon, setHorizon] = useState<TodayHorizon | null>(null);
  const [treasury, setTreasury] = useState<TreasurySummary | null>(null);
  const [recentTransactions, setRecentTransactions] = useState<ContextStripTransaction[]>([]);
  const [corporates, setCorporates] = useState<Array<{ id: string; name?: string; legalName?: string }>>([]);
  const [programs, setPrograms] = useState<Array<{ id: string; programName: string; programType: string }>>([]);

  const [loadingItems, setLoadingItems] = useState(true);
  const [loadingHorizon, setLoadingHorizon] = useState(true);
  const [loadingContext, setLoadingContext] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [lastRefreshedAt, setLastRefreshedAt] = useState<string | undefined>(undefined);

  // Selection — drives filtered fetches.
  const [selectedCorporateId, setSelectedCorporateId] = useState('');
  const [selectedProgramId, setSelectedProgramId] = useState('');

  // Drawer state.
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [drawerItem, setDrawerItem] = useState<AttentionItem | null>(null);

  // Snoozed item ids → snoozedUntil ISO. Hidden from the inbox while live.
  const [snoozed, setSnoozed] = useState<Record<string, string>>(() => loadSnoozedItems());

  // ----------------------------------------------------------------------------
  // Fetches
  // ----------------------------------------------------------------------------

  const fetchAttention = useCallback(async () => {
    setLoadingItems(true);
    try {
      const list = await cockpitApi.getAttentionItems(selectedCorporateId || undefined);
      setItems(list);
    } catch (err) {
      // eslint-disable-next-line no-console
      console.warn('[cockpit] getAttentionItems failed:', err);
    } finally {
      setLoadingItems(false);
    }
  }, [selectedCorporateId]);

  const fetchHorizon = useCallback(async () => {
    setLoadingHorizon(true);
    try {
      const h = await cockpitApi.getTodayHorizon(selectedCorporateId || undefined);
      setHorizon(h);
    } catch (err) {
      // eslint-disable-next-line no-console
      console.warn('[cockpit] getTodayHorizon failed:', err);
    } finally {
      setLoadingHorizon(false);
    }
  }, [selectedCorporateId]);

  const fetchContext = useCallback(async () => {
    setLoadingContext(true);
    try {
      const [treasurySummary, recent] = await Promise.allSettled([
        dashboardApi.getTreasurySummary(),
        // The cockpit only needs ~8 rows for the activity card.
        transactionsApi.getRecent(8, selectedCorporateId || undefined),
      ]);
      if (treasurySummary.status === 'fulfilled') setTreasury(treasurySummary.value);
      if (recent.status === 'fulfilled') {
        const txs = (recent.value?.data ?? []) as Transaction[];
        setRecentTransactions(
          txs.map((t) => ({
            id: t.id,
            description: t.description ?? t.movementType,
            amount: t.amount,
            currencyCode: t.currencyCode,
            movementType: t.movementType,
            transactionDate: t.transactionDate,
          })),
        );
      }
    } finally {
      setLoadingContext(false);
    }
  }, [selectedCorporateId]);

  const fetchSelectorData = useCallback(async () => {
    try {
      const [c, p] = await Promise.allSettled([
        corporatesApi.getAll(),
        programsApi.getAll(),
      ]);
      // Tolerate either an array response or an ApiResponse<...> — we coerce
      // to whichever shape we can read.
      const arrFrom = (v: any): any[] =>
        Array.isArray(v) ? v
        : Array.isArray(v?.data) ? v.data
        : Array.isArray(v?.data?.content) ? v.data.content
        : [];
      if (c.status === 'fulfilled') setCorporates(arrFrom(c.value));
      if (p.status === 'fulfilled') setPrograms(arrFrom(p.value));
    } catch {
      // selector data is optional — silently degrade.
    }
  }, []);

  const refreshAll = useCallback(async () => {
    setRefreshing(true);
    try {
      await Promise.allSettled([fetchAttention(), fetchHorizon(), fetchContext()]);
      setLastRefreshedAt(new Date().toISOString());
    } finally {
      setRefreshing(false);
    }
  }, [fetchAttention, fetchHorizon, fetchContext]);

  // Initial load.
  useEffect(() => { fetchSelectorData(); }, [fetchSelectorData]);
  useEffect(() => {
    telemetry.emit('cockpit.loaded');
    void Promise.allSettled([fetchAttention(), fetchHorizon(), fetchContext()])
      .then(() => setLastRefreshedAt(new Date().toISOString()));
  }, [fetchAttention, fetchHorizon, fetchContext]);

  // ----------------------------------------------------------------------------
  // Snooze handling
  // ----------------------------------------------------------------------------

  const handleSnooze = useCallback(async (item: AttentionItem, reason: string) => {
    telemetry.emit('cockpit.attention.snoozed', { itemId: item.id, category: item.category });
    // Compute end-of-day in user's local timezone.
    const eod = new Date();
    eod.setHours(23, 59, 59, 999);
    const eodIso = eod.toISOString();
    setSnoozed((prev) => {
      const next = { ...prev, [item.id]: eodIso };
      saveSnoozedItems(next);
      return next;
    });
    setDrawerOpen(false);
    await auditLog.record({
      action: 'cockpit.attention.snoozed',
      itemId: item.id,
      payload: { reason, until: eodIso },
    });
    void cockpitApi.snoozeItem(item.id, reason);
    toast.success('Snoozed until end of day');
  }, []);

  // ----------------------------------------------------------------------------
  // Action handling
  // ----------------------------------------------------------------------------

  const handleAction = useCallback(
    async (item: AttentionItem, action: AttentionAction, payload?: unknown) => {
      telemetry.emit('cockpit.attention.action.executed', {
        itemId: item.id,
        actionId: action.actionId,
        kind: action.kind,
        category: item.category,
      });
      // Always write the audit BEFORE doing the thing — so even a failed
      // action leaves a trail.
      const { auditId } = await auditLog.record({
        action: 'cockpit.attention.action.executed',
        itemId: item.id,
        actionId: action.actionId,
        payload,
      });
      if (action.kind === 'navigate') {
        onNavigate(action.actionId);
        return;
      }
      if (action.kind === 'drawer') {
        setDrawerItem(item);
        setDrawerOpen(true);
        return;
      }
      // execute kind — special-case the few that V1 wires server-side, fall
      // back to cockpitApi.executeAction for the rest.
      try {
        if (action.actionId === 'refresh_shadow' && item.context.reference) {
          // Stale-balance Refresh action — call multi-bank refresh endpoint
          // directly (we have the vaNumber in `reference` and can derive vaId
          // from item.id which we encoded as `stale_balance:${vaId}`).
          const vaId = item.id.startsWith('stale_balance:') ? item.id.slice('stale_balance:'.length) : '';
          if (vaId) {
            await multiBankLiquidityApi.refresh(vaId);
            toast.success('Refresh requested');
          }
        } else {
          await cockpitApi.executeAction(item.id, action.actionId, { ...((payload as object) ?? {}), auditId });
          toast.success(`${action.label} requested`);
        }
        // Optimistic: drop the item from the inbox; the next refresh will
        // re-resolve.
        setItems((prev) => prev.filter((i) => i.id !== item.id));
        setDrawerOpen(false);
        // Refetch attention in background.
        void fetchAttention();
      } catch (err) {
        // eslint-disable-next-line no-console
        console.warn('[cockpit] action failed:', err);
        toast.error(`${action.label} failed`);
      }
    },
    [fetchAttention, onNavigate],
  );

  // ----------------------------------------------------------------------------
  // Visible items: drop snoozed; respect filter handled inside AttentionInbox.
  // ----------------------------------------------------------------------------

  const visibleItems = useMemo(() => items.filter((i) => !snoozed[i.id]), [items, snoozed]);

  const markedHours = useMemo(() => {
    const set = new Set<number>();
    for (const i of visibleItems) {
      if (i.timePressure.kind === 'absolute' && typeof i.timePressure.anchorHourLocal === 'number') {
        set.add(i.timePressure.anchorHourLocal);
      } else if (i.timePressure.kind === 'relative_future' && typeof i.timePressure.anchorHourLocal === 'number') {
        set.add(i.timePressure.anchorHourLocal);
      }
    }
    return set;
  }, [visibleItems]);

  // Related items for the drawer — V1: same context (bank or currency)
  // within the last 24h.
  const relatedItems = useMemo(() => {
    if (!drawerItem) return [];
    const since = Date.now() - 24 * 60 * 60 * 1000;
    return items
      .filter((i) =>
        i.id !== drawerItem.id
        && new Date(i.createdAt).getTime() >= since
        && (
          (i.context.bankBic && i.context.bankBic === drawerItem.context.bankBic)
          || (i.context.currencyCode && i.context.currencyCode === drawerItem.context.currencyCode)
          || (i.context.entityId && i.context.entityId === drawerItem.context.entityId)
        ),
      );
  }, [items, drawerItem]);

  // The classic-view switch is only offered when the fallback flag is on
  // (the 60-day overlap). Flipping the cockpit flag and bouncing the user
  // to /dashboard-classic is enough — the flag check happens on every
  // render of CockpitFeatureFlag.
  const showClassicSwitch = featureFlags.isOn('cockpit.v1.classic_fallback_enabled');
  const switchToClassic = () => {
    telemetry.emit('cockpit.classic.switch.clicked');
    featureFlags.set('cockpit.v1', 'off');
    onNavigate('dashboard-classic');
  };

  return (
    <Page>
      <GreetingStrip
        userName="Treasurer"
        corporates={corporates}
        programs={programs}
        selectedCorporateId={selectedCorporateId}
        selectedProgramId={selectedProgramId}
        onCorporateChange={setSelectedCorporateId}
        onProgramChange={setSelectedProgramId}
        loading={loadingItems || loadingHorizon}
        lastRefreshedAt={lastRefreshedAt}
        refreshing={refreshing}
        onRefresh={() => { telemetry.emit('cockpit.refresh.clicked'); void refreshAll(); }}
        onSwitchToClassic={showClassicSwitch ? switchToClassic : undefined}
      />

      <AttentionInbox
        items={visibleItems}
        loading={loadingItems}
        filter={filter}
        setFilter={setFilter}
        onOpenItem={(it) => {
          telemetry.emit('cockpit.attention.row.clicked', { itemId: it.id, category: it.category });
          telemetry.emit('cockpit.attention.drawer.opened', { itemId: it.id });
          setDrawerItem(it);
          setDrawerOpen(true);
        }}
        onAction={handleAction}
        snoozedCount={Object.keys(snoozed).length}
      />

      <TodayHorizonPanel
        horizon={horizon}
        loading={loadingHorizon}
        markedHours={markedHours}
      />

      <MultiBankBand
        corporateId={selectedCorporateId}
        onOpenFull={() => onNavigate('multi-bank-liquidity')}
      />

      <ContextStrip
        treasury={treasury}
        loading={loadingContext}
        recentTransactions={recentTransactions}
        onNavigate={onNavigate}
      />

      <AttentionDrawer
        open={drawerOpen}
        item={drawerItem}
        onClose={() => setDrawerOpen(false)}
        onAction={handleAction}
        onSnooze={handleSnooze}
        relatedItems={relatedItems}
      />
    </Page>
  );
};

export default CockpitPage;
