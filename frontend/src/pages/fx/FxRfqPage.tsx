// ============================================================================
// FxRfqPage ("RFQ Log") — every request for a price a corporate has made, and what became of it: priced and
// accepted, left to expire, withdrawn, or declined by the desk (and why), and how many times the price was
// negotiated. Declined, expired and withdrawn requests never become deals, so this is the only place they can
// be seen. The summary covers the whole period for the chosen pair; the outcome filter only trims the rows.
// Row actions: read the history, ask again with the same terms, or withdraw a price that is still firm.
// ============================================================================

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Plus, RefreshCw, FileText, Eye, Repeat, X, Inbox, CheckCircle, Clock, XCircle, Percent } from 'lucide-react';
import toast from 'react-hot-toast';
import { Page } from '../../components/layout/Page';
import { PageHeader } from '../../components/layout/PageHeader';
import { StatStrip } from '../../components/layout/StatStrip';
import { Badge, Button, Card, CardHeader, DataTable, Drawer, RowActions, Select, StatTile } from '../../components/ui';
import type { Column } from '../../components/ui/DataTable';
import { EventTimeline } from '../../components/ui/EventTimeline';
import { useUser } from '../../context/useUser';
import { fxMarketApi } from '../../services/fxMarketApi';
import { fxDealApi, DealEvent, QuoteStatus, RfqLog, RfqRow } from '../../services/fxDealApi';
import { QUOTE_BADGE } from '../fxDealBadges';
import NewDealDrawer, { TicketPrefill } from './NewDealDrawer';
import { downloadCsv, errorText, fmt, fmtRate, when } from './fxFormat';

const STATUS_OPTIONS = [
  { value: '', label: 'All outcomes' },
  { value: 'ACCEPTED', label: 'Accepted' },
  { value: 'QUOTED', label: 'Firm price (open)' },
  { value: 'EXPIRED', label: 'Expired' },
  { value: 'REJECTED', label: 'Declined' },
  { value: 'CANCELLED', label: 'Withdrawn' },
];

const PERIOD_OPTIONS = [
  { value: '1', label: 'Last 24 hours' },
  { value: '7', label: 'Last 7 days' },
  { value: '30', label: 'Last 30 days' },
  { value: '90', label: 'Last 90 days' },
];

const FxRfqPage: React.FC = () => {
  const { currentCorporateId } = useUser();
  const corporateId = currentCorporateId ?? '';

  const [pairs, setPairs] = useState<string[]>([]);
  const [status, setStatus] = useState('');
  const [pair, setPair] = useState('');
  const [days, setDays] = useState('30');
  const [query, setQuery] = useState('');
  const [log, setLog] = useState<RfqLog | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [ticket, setTicket] = useState<{ prefill: TicketPrefill } | null>(null);
  const [open, setOpen] = useState<RfqRow | null>(null);
  const [events, setEvents] = useState<DealEvent[]>([]);

  useEffect(() => {
    fxMarketApi.spots()
      .then((spots) => {
        const listed = spots.map((s) => s.pair);
        setPairs([...new Set([...listed, ...listed.map((p) => `${p.slice(4)}/${p.slice(0, 3)}`)])].sort());
      })
      .catch(() => undefined);
  }, []);

  const refresh = useCallback(async () => {
    if (!corporateId) return;
    setLoading(true);
    try {
      setLog(await fxDealApi.rfqLog(corporateId, { status: (status || undefined) as QuoteStatus | undefined, pair: pair || undefined, days: Number(days) }));
      setError(null);
    } catch (err) {
      setError(errorText(err));
    } finally {
      setLoading(false);
    }
  }, [corporateId, status, pair, days]);

  useEffect(() => { refresh(); }, [refresh]);

  useEffect(() => {
    if (!open) return;
    let current = true;
    setEvents([]);
    fxDealApi.quoteEvents(open.quote.id).then((e) => { if (current) setEvents(e); }).catch(() => undefined);
    return () => { current = false; };
  }, [open]);

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase();
    return (log?.quotes ?? []).filter((r) => !q || r.quote.pair.toLowerCase().includes(q) || (r.dealRef ?? '').toLowerCase().includes(q)
      || (r.quote.purpose ?? '').toLowerCase().includes(q));
  }, [log, query]);

  const askAgain = (r: RfqRow) => {
    const q = r.quote;
    setTicket({
      prefill: {
        pair: q.pair, side: q.side, amount: q.amount, instrument: q.instrument,
        valueDate: q.instrument === 'SPOT' ? undefined : q.valueDate,
        windowStart: q.instrument === 'WINDOW' ? q.windowStart : undefined,
        purpose: q.purpose, exposureRef: q.exposureRef,
      },
    });
  };

  const withdraw = async (r: RfqRow) => {
    try {
      await fxDealApi.cancel(r.quote.id);
      refresh();
    } catch (err) {
      toast.error(errorText(err));
    }
  };

  const exportCsv = () => downloadCsv('fx-rfq-log.csv',
    ['Requested', 'Pair', 'Side', 'Instrument', 'Amount', 'Price', 'Margin (bps)', 'Outcome', 'Reason', 'Rounds', 'Deal'],
    rows.map((r) => [r.quote.createdAt, r.quote.pair, r.quote.side, r.quote.instrument, r.quote.amount, r.quote.clientRate, r.quote.spreadBps,
      r.quote.status, r.quote.statusReason, r.quote.rounds, r.dealRef]));

  const s = log?.summary;
  const q = open?.quote;

  const columns: Column<RfqRow>[] = [
    { key: 'createdAt', header: 'Requested', dropOrder: 3, render: (_, r) => <span className="text-caption">{when(r.quote.createdAt)}</span> },
    {
      key: 'pair', header: 'Request',
      render: (_, r) => (
        <span className="font-medium">
          {r.quote.side === 'BUY' ? 'Buy' : 'Sell'} {r.quote.pair} {r.quote.instrument.toLowerCase()}{' '}
          <span className="font-mono tabular-nums text-neutral-600 dark:text-neutral-300">{fmt(r.quote.amount, 0)}</span>
        </span>
      ),
    },
    {
      key: 'clientRate', header: 'Price', align: 'right', dropOrder: 2,
      render: (_, r) => r.quote.clientRate === undefined
        ? <span className="text-neutral-500 dark:text-neutral-400">-</span>
        : <span className="font-mono tabular-nums">{fmtRate(r.quote.clientRate)}{r.quote.spreadBps !== undefined && <span className="block text-caption text-neutral-500 dark:text-neutral-400">{r.quote.spreadBps.toFixed(2)} bps margin</span>}</span>,
    },
    {
      key: 'status', header: 'Outcome',
      render: (_, r) => (
        <span>
          <Badge variant={QUOTE_BADGE[r.quote.status].variant} size="sm">{QUOTE_BADGE[r.quote.status].label}</Badge>
          {r.quote.rounds > 0 && <span className="block text-caption text-neutral-500 dark:text-neutral-400 mt-0.5">Negotiated {r.quote.rounds} of {r.quote.maxRounds} rounds</span>}
          {r.quote.statusReason && <span className="block text-caption text-neutral-500 dark:text-neutral-400 mt-0.5 max-w-[22rem] truncate" title={r.quote.statusReason}>{r.quote.statusReason}</span>}
        </span>
      ),
    },
    { key: 'requestedBy', header: 'By', dropOrder: 1, render: (_, r) => <span className="text-caption">{r.quote.requestedBy ?? '-'}</span> },
    { key: 'dealRef', header: 'Deal', render: (_, r) => r.dealRef ? <span className="font-mono text-caption">{r.dealRef}</span> : <span className="text-neutral-500 dark:text-neutral-400">-</span> },
    {
      key: 'actions', header: 'Actions', width: '4rem', minWidth: 64,
      render: (_, r) => (
        <RowActions actions={[
          { label: 'View history', icon: <Eye className="w-4 h-4" />, onClick: () => setOpen(r) },
          { label: 'Request again', icon: <Repeat className="w-4 h-4" />, onClick: () => askAgain(r) },
          { label: 'Withdraw price', icon: <X className="w-4 h-4" />, onClick: () => withdraw(r), hidden: r.quote.status !== 'QUOTED', danger: true, divider: true },
        ]} />
      ),
    },
  ];

  return (
    <Page>
      <PageHeader
        title="RFQ Log"
        description="Every request for a price, including those that expired, were withdrawn or were declined and so never became a deal. Hit rate is accepted over settled requests; a price still open and a request the desk declined do not count against it."
        actions={<Button onClick={() => setTicket({ prefill: {} })} leftIcon={<Plus className="w-4 h-4" />}>New Deal</Button>}
      />

      <StatStrip columns={5}>
        <StatTile tone="primary" icon={<Inbox className="w-5 h-5" />} label="Requests" value={String(s?.requests ?? 0)} sub={`${s?.priced ?? 0} priced`} loading={loading && !log} />
        <StatTile tone="success" icon={<CheckCircle className="w-5 h-5" />} label="Accepted" value={String(s?.accepted ?? 0)}
          sub={s?.avgSecondsToAccept === undefined ? undefined : `in about ${s.avgSecondsToAccept}s`} />
        <StatTile tone="warning" icon={<Clock className="w-5 h-5" />} label="Expired or withdrawn" value={String((s?.expired ?? 0) + (s?.withdrawn ?? 0))}
          sub={`${s?.expired ?? 0} expired, ${s?.withdrawn ?? 0} withdrawn`} />
        <StatTile tone="danger" icon={<XCircle className="w-5 h-5" />} label="Declined" value={String(s?.declined ?? 0)} sub="by the desk" />
        <StatTile tone="info" icon={<Percent className="w-5 h-5" />} label="Hit rate" value={s?.hitRate === undefined ? '-' : `${Math.round(s.hitRate * 100)}%`} sub="accepted of settled" />
      </StatStrip>

      {error && <p className="text-body-sm text-error-700 dark:text-error-300">{error}</p>}

      <Card>
        <CardHeader title="Requests" subtitle={`${rows.length} shown`} />
        <DataTable<RfqRow>
          data={rows}
          columns={columns}
          keyExtractor={(r) => r.quote.id}
          onRowClick={setOpen}
          searchable
          searchPlaceholder="Search pair, deal or purpose..."
          onSearch={setQuery}
          loading={loading && !log}
          compact
          hairline
          emptyTitle="No requests"
          emptyDescription="Nothing matches these filters in the period."
          actions={
            <>
              <Select aria-label="Outcome" selectSize="sm" value={status} onChange={(e) => setStatus(e.target.value)} options={STATUS_OPTIONS} />
              <Select aria-label="Pair" selectSize="sm" value={pair} onChange={(e) => setPair(e.target.value)} options={[{ value: '', label: 'All pairs' }, ...pairs.map((p) => ({ value: p, label: p }))]} />
              <Select aria-label="Period" selectSize="sm" value={days} onChange={(e) => setDays(e.target.value)} options={PERIOD_OPTIONS} />
              <Button size="sm" variant="outline" onClick={refresh} leftIcon={<RefreshCw className="w-4 h-4" />}>Refresh</Button>
              <Button size="sm" variant="outline" onClick={exportCsv} disabled={!rows.length} leftIcon={<FileText className="w-4 h-4" />}>Export</Button>
            </>
          }
        />
      </Card>

      <Drawer isOpen={!!open} onClose={() => setOpen(null)} title="Request for a price"
        subtitle={q ? `${q.side === 'BUY' ? 'Buy' : 'Sell'} ${q.pair} ${q.instrument.toLowerCase()}` : undefined}>
        {q && (
          <div className="p-6 space-y-5">
            <div className="flex items-center gap-2">
              <Badge variant={QUOTE_BADGE[q.status].variant}>{QUOTE_BADGE[q.status].label}</Badge>
              {open?.dealRef && <span className="font-mono text-caption">{open.dealRef}</span>}
            </div>
            {q.statusReason && <p className="text-body-sm text-neutral-700 dark:text-neutral-200">{q.statusReason}</p>}
            <dl className="grid grid-cols-2 gap-3 text-body-sm">
              <div><dt className="text-caption text-neutral-500 dark:text-neutral-400">Amount</dt><dd className="font-mono tabular-nums">{q.baseCurrency} {fmt(q.amount)}</dd></div>
              <div><dt className="text-caption text-neutral-500 dark:text-neutral-400">Value date</dt><dd className="font-mono tabular-nums">{q.windowStart ? `${q.windowStart} to ${q.valueDate}` : q.valueDate}</dd></div>
              <div><dt className="text-caption text-neutral-500 dark:text-neutral-400">Requested</dt><dd>{when(q.createdAt)}</dd></div>
              <div><dt className="text-caption text-neutral-500 dark:text-neutral-400">By</dt><dd>{q.requestedBy ?? '-'}</dd></div>
              {q.clientRate !== undefined && (<>
                <div><dt className="text-caption text-neutral-500 dark:text-neutral-400">{q.status === 'ACCEPTED' ? 'Accepted price' : 'Last price'}</dt><dd className="font-mono tabular-nums">{fmtRate(q.clientRate)}</dd></div>
                <div><dt className="text-caption text-neutral-500 dark:text-neutral-400">Bank margin</dt><dd className="font-mono tabular-nums">{q.spreadBps?.toFixed(2)} bps</dd></div>
                <div><dt className="text-caption text-neutral-500 dark:text-neutral-400">Interbank mid</dt><dd className="font-mono tabular-nums">{q.midRate !== undefined && fmtRate(q.midRate)}</dd></div>
                <div><dt className="text-caption text-neutral-500 dark:text-neutral-400">Firm until</dt><dd>{when(q.validUntil)}</dd></div>
              </>)}
              {q.maxRounds > 0 && (
                <div><dt className="text-caption text-neutral-500 dark:text-neutral-400">Negotiation</dt><dd>{q.rounds} of {q.maxRounds} rounds{q.bestPrice ? ', desk at its best' : ''}</dd></div>
              )}
              {q.purpose && <div className="col-span-2"><dt className="text-caption text-neutral-500 dark:text-neutral-400">Purpose</dt><dd>{q.purpose}</dd></div>}
              {q.exposureRef && <div className="col-span-2"><dt className="text-caption text-neutral-500 dark:text-neutral-400">Hedging exposure</dt><dd>{q.exposureRef.replace('|', ' ')}</dd></div>}
            </dl>
            <div>
              <h3 className="text-body-sm font-semibold text-primary-900 dark:text-neutral-50 mb-2">History</h3>
              <EventTimeline entries={events} loading={!events.length} />
            </div>
          </div>
        )}
      </Drawer>

      <NewDealDrawer
        isOpen={!!ticket}
        prefill={ticket?.prefill ?? null}
        corporateId={corporateId}
        onClose={() => setTicket(null)}
        onChanged={refresh}
      />
    </Page>
  );
};

export default FxRfqPage;
