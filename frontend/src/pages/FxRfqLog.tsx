// ============================================================================
// FxRfqLog — every request for a price a corporate has made, with what became of it: priced and
// accepted, left to expire, withdrawn, or declined by the desk (and why). Declined, expired and
// withdrawn requests never become deals, so this is the only place they can be seen. The summary
// covers the whole period for the chosen pair; the status filter only trims the rows below it.
// ============================================================================

import React, { useEffect, useState } from 'react';
import { Card, CardHeader, Badge, DataTable, Drawer, Select } from '../components/ui';
import type { Column } from '../components/ui/DataTable';
import { EventTimeline } from '../components/ui/EventTimeline';
import { QUOTE_BADGE } from './fxDealBadges';
import { fxDealApi, DealEvent, QuoteStatus, RfqLog, RfqRow } from '../services/fxDealApi';

const fmt = (n: number, dp = 2) => n.toLocaleString('en-US', { minimumFractionDigits: dp, maximumFractionDigits: dp });
const rateDigits = (n: number) => (n >= 50 ? 4 : 6);
const when = (iso?: string) => (iso ? new Date(iso).toLocaleString() : '-');

const errorText = (err: unknown): string => {
  const e = err as { response?: { data?: { message?: string } }; message?: string };
  return e?.response?.data?.message || e?.message || 'Request failed';
};

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

const Tile: React.FC<{ label: string; value: string; hint?: string }> = ({ label, value, hint }) => (
  <div className="rounded-md border border-edge-subtle px-3 py-2">
    <p className="text-caption text-neutral-500 dark:text-neutral-400">{label}</p>
    <p className="font-mono tabular-nums text-body text-primary-900 dark:text-neutral-50">{value}</p>
    {hint && <p className="text-caption text-neutral-500 dark:text-neutral-400">{hint}</p>}
  </div>
);

const FxRfqLog: React.FC<{ corporateId: string; pairs: string[]; refreshToken: string }> = ({ corporateId, pairs, refreshToken }) => {
  const [status, setStatus] = useState('');
  const [pair, setPair] = useState('');
  const [days, setDays] = useState('30');
  const [log, setLog] = useState<RfqLog | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState<RfqRow | null>(null);
  const [events, setEvents] = useState<DealEvent[]>([]);

  useEffect(() => {
    if (!corporateId) return;
    let current = true;
    fxDealApi
      .rfqLog(corporateId, { status: (status || undefined) as QuoteStatus | undefined, pair: pair || undefined, days: Number(days) })
      .then((l) => { if (current) { setLog(l); setError(null); } })
      .catch((err) => { if (current) setError(errorText(err)); });
    return () => { current = false; };
  }, [corporateId, status, pair, days, refreshToken]);

  useEffect(() => {
    if (!open) return;
    let current = true;
    setEvents([]);
    fxDealApi.quoteEvents(open.quote.id).then((e) => { if (current) setEvents(e); }).catch(() => undefined);
    return () => { current = false; };
  }, [open]);

  const s = log?.summary;
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
        : <span className="font-mono tabular-nums">{r.quote.clientRate.toFixed(rateDigits(r.quote.clientRate))}{r.quote.spreadBps !== undefined && <span className="text-caption text-neutral-500 dark:text-neutral-400"> · {r.quote.spreadBps.toFixed(2)} bps</span>}</span>,
    },
    {
      key: 'status', header: 'Outcome',
      render: (_, r) => (
        <span>
          <Badge variant={QUOTE_BADGE[r.quote.status].variant} size="sm">{QUOTE_BADGE[r.quote.status].label}</Badge>
          {r.quote.statusReason && <span className="block text-caption text-neutral-500 dark:text-neutral-400 mt-0.5 max-w-[22rem] truncate" title={r.quote.statusReason}>{r.quote.statusReason}</span>}
        </span>
      ),
    },
    { key: 'requestedBy', header: 'By', dropOrder: 1, render: (_, r) => <span className="text-caption">{r.quote.requestedBy ?? '-'}</span> },
    { key: 'dealRef', header: 'Deal', render: (_, r) => r.dealRef ? <span className="font-mono text-caption">{r.dealRef}</span> : <span className="text-neutral-500 dark:text-neutral-400">-</span> },
  ];

  const q = open?.quote;
  return (
    <Card>
      <CardHeader title="RFQ log" subtitle="Every request for a price, including those that expired, were withdrawn or were declined and so never became a deal." />
      <div className="grid grid-cols-2 sm:grid-cols-4 lg:grid-cols-7 gap-2 mb-4">
        <Tile label="Requests" value={String(s?.requests ?? 0)} />
        <Tile label="Priced" value={String(s?.priced ?? 0)} />
        <Tile label="Accepted" value={String(s?.accepted ?? 0)} />
        <Tile label="Expired" value={String(s?.expired ?? 0)} />
        <Tile label="Declined" value={String(s?.declined ?? 0)} hint="by the desk" />
        <Tile label="Withdrawn" value={String(s?.withdrawn ?? 0)} />
        <Tile label="Hit rate" value={s?.hitRate === undefined ? '-' : `${Math.round(s.hitRate * 100)}%`}
          hint={s?.avgSecondsToAccept === undefined ? 'accepted of settled' : `accepted in ~${s.avgSecondsToAccept}s`} />
      </div>
      <div className="grid gap-3 sm:grid-cols-3 mb-4">
        <Select label="Outcome" value={status} onChange={(e) => setStatus(e.target.value)} options={STATUS_OPTIONS} />
        <Select label="Pair" value={pair} onChange={(e) => setPair(e.target.value)} options={[{ value: '', label: 'All pairs' }, ...pairs.map((p) => ({ value: p, label: p }))]} />
        <Select label="Period" value={days} onChange={(e) => setDays(e.target.value)} options={PERIOD_OPTIONS} />
      </div>
      {error && <p className="text-body-sm text-error-700 dark:text-error-300 mb-3">{error}</p>}
      <DataTable<RfqRow>
        data={log?.quotes ?? []}
        columns={columns}
        keyExtractor={(r) => r.quote.id}
        onRowClick={setOpen}
        compact
        hairline
        emptyTitle="No requests"
        emptyDescription="Nothing matches these filters in the period."
      />

      <Drawer isOpen={!!open} onClose={() => setOpen(null)} title="Request for a price"
        subtitle={q ? `${q.side === 'BUY' ? 'Buy' : 'Sell'} ${q.pair} ${q.instrument.toLowerCase()}` : undefined}>
        {q && (
          <div className="space-y-5">
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
                <div><dt className="text-caption text-neutral-500 dark:text-neutral-400">Price</dt><dd className="font-mono tabular-nums">{q.clientRate.toFixed(rateDigits(q.clientRate))}</dd></div>
                <div><dt className="text-caption text-neutral-500 dark:text-neutral-400">Bank margin</dt><dd className="font-mono tabular-nums">{q.spreadBps?.toFixed(2)} bps</dd></div>
                <div><dt className="text-caption text-neutral-500 dark:text-neutral-400">Interbank mid</dt><dd className="font-mono tabular-nums">{q.midRate?.toFixed(rateDigits(q.midRate))}</dd></div>
                <div><dt className="text-caption text-neutral-500 dark:text-neutral-400">Firm until</dt><dd>{when(q.validUntil)}</dd></div>
              </>)}
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
    </Card>
  );
};

export default FxRfqLog;
