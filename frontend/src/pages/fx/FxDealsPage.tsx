// ============================================================================
// FxDealsPage — the corporate's FX deals with the bank, and everything done to one. The row menu carries
// the actions that apply to that deal (confirm, dispute, draw down, close out, roll, download the
// confirmation); what each needs beyond a click (a reason, a date, a priced preview) happens in the deal
// drawer, which the menu opens at the right section. Rules for what is on offer live in fxDealRules.
// "+ New Deal" opens the price ticket. Settlement is a record only: no balance moves.
// ============================================================================

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Plus, Play, RefreshCw, Download, Eye, FileText, Check, AlertTriangle, Layers, Scale, CreditCard, ShieldAlert, X, Repeat, ArrowDownToLine,
} from 'lucide-react';
import toast from 'react-hot-toast';
import { Page } from '../../components/layout/Page';
import { PageHeader } from '../../components/layout/PageHeader';
import { StatStrip } from '../../components/layout/StatStrip';
import { Badge, Button, Card, CardHeader, DataTable, Input, RowActions, Select, StatTile } from '../../components/ui';
import { Modal } from '../../components/ui/enhanced';
import type { Column } from '../../components/ui/DataTable';
import { useUser } from '../../context/useUser';
import { cn } from '../../utils';
import { fxDealApi, CreditLimit, Deal, DealStatus, SettlementRun } from '../../services/fxDealApi';
import FxDealDrawer from '../FxDealDrawer';
import { DEAL_BADGE, confirmationBadge } from '../fxDealBadges';
import NewDealDrawer, { TicketPrefill } from './NewDealDrawer';
import { dealRules, DealAction, OPEN_STATUSES } from './fxDealRules';
import { downloadCsv, errorText, fmt, fmtRate, fmtSigned, toneFor } from './fxFormat';

const STATUS_OPTIONS = [
  { value: 'OPEN', label: 'Open deals' },
  { value: 'ALL', label: 'All deals' },
  ...(Object.keys(DEAL_BADGE) as DealStatus[]).map((s) => ({ value: s, label: DEAL_BADGE[s].label })),
];

const CONFIRMATION_OPTIONS = [
  { value: '', label: 'Any confirmation' },
  { value: 'ISSUED', label: 'Awaiting confirmation' },
  { value: 'AFFIRMED', label: 'Confirmed' },
  { value: 'DISPUTED', label: 'Disputed' },
];

const FxDealsPage: React.FC = () => {
  const { currentCorporateId, corporates } = useUser();
  const corporateId = currentCorporateId ?? '';
  const corporateName = corporates.find((c) => c.id === corporateId)?.name;

  const [deals, setDeals] = useState<Deal[]>([]);
  const [limit, setLimit] = useState<CreditLimit | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [status, setStatus] = useState('OPEN');
  const [confirmation, setConfirmation] = useState('');
  const [attentionOnly, setAttentionOnly] = useState(false);
  const [query, setQuery] = useState('');

  const [ticket, setTicket] = useState<{ prefill: TicketPrefill } | null>(null);
  const [opened, setOpened] = useState<{ id: string; action: DealAction | null } | null>(null);
  const [confirming, setConfirming] = useState<Deal | null>(null);
  const [confirmBusy, setConfirmBusy] = useState(false);
  const [settleOpen, setSettleOpen] = useState(false);
  const [asOf, setAsOf] = useState('');
  const [running, setRunning] = useState(false);
  const [runResult, setRunResult] = useState<SettlementRun | null>(null);
  const [runError, setRunError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    if (!corporateId) return;
    setLoading(true);
    try {
      const [d, l] = await Promise.all([fxDealApi.deals(corporateId), fxDealApi.limit(corporateId)]);
      setDeals(d);
      setLimit(l);
      setError(null);
    } catch (err) {
      setError(errorText(err));
    } finally {
      setLoading(false);
    }
  }, [corporateId]);

  useEffect(() => {
    setDeals([]);
    setLimit(null);
    refresh();
  }, [refresh]);

  const open = deals.filter((d) => OPEN_STATUSES.includes(d.status));
  const attention = deals.filter((d) => dealRules(d).needsAttention);
  const marked = open.filter((d) => d.mtmHome !== undefined);
  const net = marked.reduce((sum, d) => sum + (d.mtmHome ?? 0), 0);
  const usedPct = limit && limit.limit > 0 ? (limit.used / limit.limit) * 100 : 0;

  const shown = useMemo(() => {
    const q = query.trim().toLowerCase();
    return deals.filter((d) => {
      if (attentionOnly && !dealRules(d).needsAttention) return false;
      if (status === 'OPEN' ? !OPEN_STATUSES.includes(d.status) : status !== 'ALL' && d.status !== status) return false;
      if (confirmation && d.confirmationStatus !== confirmation) return false;
      return !q || d.dealRef.toLowerCase().includes(q) || d.pair.toLowerCase().includes(q) || (d.purpose ?? '').toLowerCase().includes(q);
    });
  }, [deals, status, confirmation, attentionOnly, query]);

  const downloadPdf = async (d: Deal) => {
    try {
      const blob = await fxDealApi.confirmationPdf(d.id);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${d.dealRef}-confirmation.pdf`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (err) {
      toast.error(errorText(err));
    }
  };

  const onConfirm = async () => {
    if (!confirming) return;
    setConfirmBusy(true);
    try {
      await fxDealApi.confirm(confirming.id);
      setConfirming(null);
      refresh();
    } catch (err) {
      toast.error(errorText(err));
    } finally {
      setConfirmBusy(false);
    }
  };

  const onRunSettlement = async () => {
    setRunning(true);
    setRunError(null);
    try {
      setRunResult(await fxDealApi.runSettlement(asOf || undefined, corporateId));
      refresh();
    } catch (err) {
      setRunError(errorText(err));
    } finally {
      setRunning(false);
    }
  };

  const exportCsv = () => downloadCsv('fx-deals.csv',
    ['Deal', 'Entity', 'Pair', 'Side', 'Instrument', 'Buy currency', 'Buy amount', 'Sell currency', 'Sell amount', 'Rate', 'Value date', 'Status', 'Confirmation', 'Worth today (home)'],
    shown.map((d) => [d.dealRef, d.entityName, d.pair, d.side, d.instrument, d.buyCurrency, d.buyAmount, d.sellCurrency, d.sellAmount, d.rate, d.valueDate, d.status, d.confirmationStatus, d.mtmHome]));

  const columns: Column<Deal>[] = [
    { key: 'dealRef', header: 'Deal', render: (_, d) => <span className="font-mono text-caption">{d.dealRef}</span> },
    { key: 'entityName', header: 'Entity', render: (_, d) => <span className="text-body-sm">{d.entityName ?? '-'}</span> },
    { key: 'pair', header: 'Trade', render: (_, d) => <span className="font-medium">{d.side === 'BUY' ? 'Buy' : 'Sell'} {d.pair} <span className="text-neutral-500 dark:text-neutral-400">{d.instrument.toLowerCase()}</span></span> },
    { key: 'buyAmount', header: 'You buy', align: 'right', dropOrder: 2, render: (_, d) => <span className="font-mono tabular-nums">{d.buyCurrency} {fmt(d.buyAmount)}</span> },
    { key: 'sellAmount', header: 'You sell', align: 'right', dropOrder: 3, render: (_, d) => <span className="font-mono tabular-nums">{d.sellCurrency} {fmt(d.sellAmount)}</span> },
    { key: 'rate', header: 'Rate', align: 'right', dropOrder: 1, render: (_, d) => <span className="font-mono tabular-nums">{fmtRate(d.rate)}</span> },
    { key: 'valueDate', header: 'Value date', render: (_, d) => <span className="font-mono tabular-nums">{d.windowStart ? `${d.windowStart} to ${d.valueDate}` : d.valueDate}</span> },
    {
      key: 'mtmQuote', header: 'Worth today', align: 'right', dropOrder: 4, render: (_, d) => {
        const v = d.mtmHome ?? d.mtmQuote;
        if (v === undefined) return <span className="text-neutral-500 dark:text-neutral-400">-</span>;
        return <span className={cn('font-mono tabular-nums', toneFor(v))}>{fmtSigned(v, 0)} {d.mtmHome !== undefined ? d.mtmHomeCurrency : d.mtmQuoteCurrency}</span>;
      },
    },
    {
      key: 'confirmationStatus', header: 'Confirmation', render: (_, d) => {
        const c = dealRules(d).inForce || d.status === 'BOOKED' || d.status === 'PENDING_SETTLEMENT' ? confirmationBadge(d) : null;
        return c ? <Badge variant={c.variant} size="sm">{c.label}</Badge> : <span className="text-neutral-500 dark:text-neutral-400">-</span>;
      },
    },
    { key: 'status', header: 'Status', render: (_, d) => <Badge variant={DEAL_BADGE[d.status].variant} size="sm">{DEAL_BADGE[d.status].label}</Badge> },
    {
      key: 'actions', header: 'Actions', width: '4rem', minWidth: 64, render: (_, d) => {
        const r = dealRules(d);
        return (
          <RowActions actions={[
            { label: 'View details', icon: <Eye className="w-4 h-4" />, onClick: () => setOpened({ id: d.id, action: null }) },
            { label: 'Download confirmation', icon: <Download className="w-4 h-4" />, onClick: () => downloadPdf(d) },
            { label: 'Confirm deal', icon: <Check className="w-4 h-4" />, onClick: () => setConfirming(d), hidden: !r.canConfirm, divider: true },
            { label: 'Dispute…', icon: <AlertTriangle className="w-4 h-4" />, onClick: () => setOpened({ id: d.id, action: 'dispute' }), hidden: !r.canConfirm },
            { label: 'Draw down…', icon: <ArrowDownToLine className="w-4 h-4" />, onClick: () => setOpened({ id: d.id, action: 'draw' }), hidden: !r.canDraw, divider: true },
            { label: 'Roll…', icon: <Repeat className="w-4 h-4" />, onClick: () => setOpened({ id: d.id, action: 'roll' }), hidden: !r.canAct },
            { label: 'Close out early…', icon: <X className="w-4 h-4" />, onClick: () => setOpened({ id: d.id, action: 'close' }), hidden: !r.canAct, danger: true, divider: true },
          ]} />
        );
      },
    },
  ];

  const openDeal = deals.find((d) => d.id === opened?.id) ?? null;

  return (
    <Page>
      <PageHeader
        title="FX Deals"
        description="Your foreign-exchange deals with the bank. Use a row’s menu to confirm the bank’s confirmation, draw down a window, roll or close out early. Settlement is a record only: no balance moves. The desk and rates are simulated."
        actions={
          <>
            <Button variant="outline" onClick={() => { setRunResult(null); setRunError(null); setSettleOpen(true); }} leftIcon={<Play className="w-4 h-4" />}>Run settlement</Button>
            <Button onClick={() => setTicket({ prefill: {} })} leftIcon={<Plus className="w-4 h-4" />}>New Deal</Button>
          </>
        }
      />

      <StatStrip columns={4}>
        <StatTile tone="primary" icon={<Layers className="w-5 h-5" />} label="Open deals" value={String(open.length)}
          sub={corporateName ?? 'selected corporate'} loading={loading && !deals.length} />
        <StatTile tone={net < 0 ? 'danger' : net > 0 ? 'success' : 'neutral'} icon={<Scale className="w-5 h-5" />} label="Worth today (net)"
          value={marked.length ? `${fmtSigned(net, 0)} ${marked[0].mtmHomeCurrency ?? ''}` : '-'}
          sub={open.length > marked.length ? `${open.length - marked.length} deal(s) not marked` : 'at mid, home currency'} />
        <StatTile tone={limit && !limit.usable ? 'danger' : usedPct >= 95 ? 'danger' : usedPct >= 80 ? 'warning' : 'info'} icon={<CreditCard className="w-5 h-5" />} label="FX line"
          value={!limit ? '-' : limit.usable ? `${fmt(limit.used, 0)} of ${fmt(limit.limit, 0)}` : 'Not available'}
          sub={!limit ? undefined : limit.usable ? `${fmt(limit.headroom, 0)} ${limit.currency} left; sub-limits per entity on Credit Limits` : limit.reason} />
        <StatTile tone={attention.length ? 'warning' : 'success'} icon={<ShieldAlert className="w-5 h-5" />} label="Needs attention"
          value={String(attention.length)} sub={attention.length ? 'unconfirmed, disputed or failed' : 'nothing waiting'}
          onClick={attention.length || attentionOnly ? () => setAttentionOnly((v) => !v) : undefined} active={attentionOnly} />
      </StatStrip>

      {error && <p className="text-body-sm text-error-700 dark:text-error-300">{error}</p>}

      <Card>
        <CardHeader title="Deals" subtitle={`${shown.length} of ${deals.length}${attentionOnly ? ', needing attention' : ''}`} />
        <DataTable<Deal>
          data={shown}
          columns={columns}
          keyExtractor={(d) => d.id}
          onRowClick={(d) => setOpened({ id: d.id, action: null })}
          searchable
          searchPlaceholder="Search deal, pair or purpose..."
          onSearch={setQuery}
          loading={loading && !deals.length}
          compact
          hairline
          emptyTitle="No deals"
          emptyDescription={deals.length ? 'Nothing matches these filters.' : 'Accepted prices appear here. Use New Deal to request one.'}
          actions={
            <>
              <Select aria-label="Status" selectSize="sm" value={status} onChange={(e) => setStatus(e.target.value)} options={STATUS_OPTIONS} />
              <Select aria-label="Confirmation" selectSize="sm" value={confirmation} onChange={(e) => setConfirmation(e.target.value)} options={CONFIRMATION_OPTIONS} />
              <Button size="sm" variant="outline" onClick={refresh} leftIcon={<RefreshCw className="w-4 h-4" />}>Refresh</Button>
              <Button size="sm" variant="outline" onClick={exportCsv} disabled={!shown.length} leftIcon={<FileText className="w-4 h-4" />}>Export</Button>
            </>
          }
        />
      </Card>

      <NewDealDrawer
        isOpen={!!ticket}
        prefill={ticket?.prefill ?? null}
        corporateId={corporateId}
        onClose={() => setTicket(null)}
        onChanged={refresh}
      />
      <FxDealDrawer deal={openDeal} action={opened?.action} onClose={() => setOpened(null)} onChanged={refresh} />

      <Modal
        isOpen={!!confirming}
        onClose={() => setConfirming(null)}
        title="Confirm this deal"
        subtitle={confirming?.dealRef}
        size="sm"
        footer={
          <>
            <Button variant="ghost" onClick={() => setConfirming(null)}>Cancel</Button>
            <Button onClick={onConfirm} loading={confirmBusy} leftIcon={<Check className="w-4 h-4" />}>Confirm deal</Button>
          </>
        }
      >
        {confirming && (
          <div className="space-y-2 text-body-sm">
            <p>
              You buy <strong>{confirming.buyCurrency} {fmt(confirming.buyAmount)}</strong> and sell{' '}
              <strong>{confirming.sellCurrency} {fmt(confirming.sellAmount)}</strong> at <span className="font-mono">{fmtRate(confirming.rate)}</span>,
              value {confirming.windowStart ? `${confirming.windowStart} to ${confirming.valueDate}` : confirming.valueDate}.
            </p>
            <p className="text-neutral-600 dark:text-neutral-300">
              Check this against the trade you agreed. A deal that is not confirmed cannot settle. To raise a problem instead, close this and choose Dispute.
            </p>
          </div>
        )}
      </Modal>

      <Modal
        isOpen={settleOpen}
        onClose={() => setSettleOpen(false)}
        title="Run value-date settlement"
        size="sm"
        footer={
          <>
            <Button variant="ghost" onClick={() => setSettleOpen(false)}>Close</Button>
            <Button onClick={onRunSettlement} loading={running} disabled={!corporateId} leftIcon={<Play className="w-4 h-4" />}>Run settlement</Button>
          </>
        }
      >
        <div className="space-y-3">
          <p className="text-body-sm text-neutral-600 dark:text-neutral-300">
            Settles every confirmed deal that is due, if the cash is there. It is a record only: no balance moves. Pick a later date to move the demo clock forward.
          </p>
          <Input label="Settle as of (optional)" type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} />
          {runError && <p className="text-body-sm text-error-700 dark:text-error-300">{runError}</p>}
          {runResult && (
            <div className="text-body-sm space-y-1">
              <p>As of {runResult.asOf}: <strong>{runResult.settled}</strong> settled, <strong>{runResult.failed}</strong> failed, <strong>{runResult.blocked}</strong> blocked.</p>
              {runResult.notes.map((n, i) => <p key={i} className="text-caption text-neutral-600 dark:text-neutral-300">{n}</p>)}
            </div>
          )}
        </div>
      </Modal>
    </Page>
  );
};

export default FxDealsPage;
