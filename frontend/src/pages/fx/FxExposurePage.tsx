// ============================================================================
// FxExposurePage ("Exposure & Hedging") — the foreign-currency exposure ladder per currency against the
// corporate's hedge policy, with the declared exposures and hedges that payables, receivables and balances do
// not capture (ERP forecasts, forwards held with other banks), uploaded as CSV. Amounts stay native to their
// currency; a base-currency equivalent is never summed across currencies, and the rate behind any single
// conversion is shown. From a row the corporate can hedge a gap: that opens the price ticket pre-filled.
// ============================================================================

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Upload, Download, Coins, ShieldAlert, Send, Filter, X, TrendingUp, ListTree } from 'lucide-react';
import toast from 'react-hot-toast';
import { Page } from '../../components/layout/Page';
import { PageHeader } from '../../components/layout/PageHeader';
import { StatStrip } from '../../components/layout/StatStrip';
import { Badge, Button, Card, CardHeader, DataTable, Drawer, RowActions, StatTile } from '../../components/ui';
import { useNavigation } from '../../hooks/useNavigation';
import { Alert, Modal } from '../../components/ui/enhanced';
import { Checkbox } from '../../components/ui/Checkbox';
import type { Column } from '../../components/ui/DataTable';
import { useUser } from '../../context/useUser';
import {
  fxExposureApi, ExposureBucket, ExposureBucketItems, ExposureEntry, ExposureItem, ExposureItemKind, ExposureLadder,
  HedgeStatus, PolicyBreach,
} from '../../services/fxMarketApi';
import { fxDealApi, HedgeKind, HedgeLink } from '../../services/fxDealApi';
import NewDealDrawer, { TicketPrefill } from './NewDealDrawer';
import { errorText, fmt } from './fxFormat';

const fmtN = (n: number) => fmt(n, 0);
const fmtSigned = (n: number) => (n > 0 ? '+' : n < 0 ? '-' : '') + fmtN(Math.abs(n));
const pct = (r?: number | null) => (r == null ? '-' : `${Math.round(r * 100)}%`);

const TEMPLATE_CSV = [
  'kind,currency,amount,valueDate,reference,description',
  'EXPOSURE,EUR,250000,2027-01-15,ERP-1042,Expected customer receipt',
  'EXPOSURE,EUR,-120000,2027-02-10,ERP-1077,Supplier invoice due',
  'HEDGE,EUR,-100000,2027-01-15,FWD-0091,Forward sold with another bank',
].join('\n');

const STATUS_BADGE: Record<HedgeStatus, { label: string; variant: 'success' | 'warning' | 'error' | 'neutral' | 'info' } | null> = {
  WITHIN_POLICY: { label: 'Within policy', variant: 'success' },
  UNDER_HEDGED: { label: 'Under-hedged', variant: 'error' },
  OVER_HEDGED: { label: 'Over-hedged', variant: 'warning' },
  NO_POLICY: { label: 'No policy', variant: 'neutral' },
  NOT_POLICED: { label: 'Not policed', variant: 'neutral' },
  NONE: null,
};

/** Hedge ratio against the policy band, on a 0-150% scale. */
const RatioBar: React.FC<{ ratio?: number | null; min?: number | null; max?: number | null; status: HedgeStatus }> = ({ ratio, min, max, status }) => {
  if (ratio == null && min == null) return <span className="text-neutral-500 dark:text-neutral-400">-</span>;
  const x = (v: number) => Math.max(0, Math.min(1.5, v)) / 1.5 * 120;
  const tone = status === 'UNDER_HEDGED' ? 'text-error-700 dark:text-error-300'
    : status === 'OVER_HEDGED' ? 'text-warning-700 dark:text-warning-300' : 'text-success-700 dark:text-success-300';
  return (
    <span className="inline-flex items-center gap-2">
      <svg width="120" height="14" viewBox="0 0 120 14" aria-hidden="true" className={tone}>
        <rect x="0" y="5" width="120" height="4" rx="2" className="fill-neutral-200 dark:fill-primary-800" />
        {min != null && max != null && (
          <rect x={x(min)} y="3" width={Math.max(2, x(max) - x(min))} height="8" rx="2" fill="currentColor" opacity="0.25" />
        )}
        {ratio != null && <rect x={x(ratio) - 1.5} y="1" width="3" height="12" rx="1.5" fill="currentColor" />}
      </svg>
      <span className="font-mono tabular-nums text-caption w-10 text-right">{pct(ratio)}</span>
    </span>
  );
};

/** The drawer's sections, in reading order: what makes up the exposure, then the hedges against it. */
const ITEM_SECTIONS: { kind: ExposureItemKind; title: string; page?: 'receivables' | 'payables' | 'forecasting' }[] = [
  { kind: 'RECEIVABLE', title: 'Receivables', page: 'receivables' },
  { kind: 'PAYABLE', title: 'Payables', page: 'payables' },
  { kind: 'FORECAST', title: 'Cash Forecast', page: 'forecasting' },
  { kind: 'CASH', title: 'Cash at banks' },
  { kind: 'DECLARED', title: 'Declared exposures' },
  { kind: 'HEDGE', title: 'Hedges' },
];

/** What of an invoice no deal hedges yet: its amount less the effective links, to the cent. */
const unhedgedOf = (i: ExposureItem, links?: HedgeLink[]) =>
  Math.max(0, Math.round((Math.abs(i.amount) - (links ?? []).reduce((t, l) => t + l.effective, 0)) * 100) / 100);

/**
 * What a bucket's net exposure and hedge are made of: the invoices, forecast lines, cash, declared entries and hedges
 * the ladder counted there. The server places them by the ladder's own rules, so they add up to the row. Invoices
 * show the deals that hedge them; ticking unhedged ones of one kind books a hedge linked to them (fx-hedge-links D2).
 */
const BucketItemsDrawer: React.FC<{
  open: { currency: string; bucket: ExposureBucket } | null; data: ExposureBucketItems | null; error: string | null;
  links: Record<string, HedgeLink[]>;
  onClose: () => void; onOpenPage: (page: 'receivables' | 'payables' | 'forecasting') => void;
  onHedge: (kind: HedgeKind, picked: { item: ExposureItem; amount: number }[]) => void;
}> = ({ open, data, error, links, onClose, onOpenPage, onHedge }) => {
  const [picked, setPicked] = useState<Set<string>>(new Set());
  useEffect(() => setPicked(new Set()), [data]);
  const rows = (data?.items ?? []).filter((i) => i.id && picked.has(i.id));
  // Receivables are hedged by selling the currency and payables by buying it, so one deal takes one kind.
  const pickedKind = rows[0]?.kind as HedgeKind | undefined;
  const pickedTotal = rows.reduce((t, i) => t + unhedgedOf(i, links[i.id as string]), 0);
  const toggle = (id: string, on: boolean) => setPicked((p) => {
    const next = new Set(p);
    if (on) next.add(id); else next.delete(id);
    return next;
  });

  return (
    <Drawer
      isOpen={!!open}
      onClose={onClose}
      size="lg"
      title={open ? `${open.currency} · ${open.bucket.label}` : ''}
      subtitle={data ? `Net exposure ${fmtSigned(data.exposure)} · hedged ${data.hedged ? fmtSigned(data.hedged) : '-'}` : undefined}
      footer={pickedKind ? (
        <div className="flex flex-wrap items-center justify-between gap-3 w-full">
          <span className="body-sm">
            {rows.length} {pickedKind === 'RECEIVABLE' ? 'receivable' : 'payable'}{rows.length === 1 ? '' : 's'}:{' '}
            {pickedKind === 'RECEIVABLE' ? 'sell' : 'buy'} <span className="font-mono tabular-nums">{fmt(pickedTotal)} {open?.currency}</span> forward
          </span>
          <span className="inline-flex gap-2">
            <Button variant="ghost" onClick={() => setPicked(new Set())}>Clear</Button>
            <Button leftIcon={<Send className="w-4 h-4" />}
              onClick={() => onHedge(pickedKind, rows.map((item) => ({ item, amount: unhedgedOf(item, links[item.id as string]) })))}>
              Hedge selected
            </Button>
          </span>
        </div>
      ) : undefined}
    >
      <div className="p-6">
      {error && <p className="text-body-sm text-error-700 dark:text-error-300">{error}</p>}
      {!data && !error && <p className="caption">Loading…</p>}
      {data && (
        <div className="space-y-5">
          {ITEM_SECTIONS.map(({ kind, title, page }) => {
            const items = data.items.filter((i) => i.kind === kind);
            if (items.length === 0) return null;
            const total = items.reduce((t, i) => t + i.amount, 0);
            const invoices = kind === 'RECEIVABLE' || kind === 'PAYABLE';
            const otherKind = invoices && !!pickedKind && pickedKind !== kind;
            return (
              <section key={kind}>
                <div className="flex items-center justify-between mb-1.5">
                  <p className="label">{title} · {fmtSigned(total)}</p>
                  {page && <Button size="sm" variant="ghost" onClick={() => onOpenPage(page)}>Open {title}</Button>}
                </div>
                {otherKind && (
                  <p className="caption mb-1">
                    Receivables are hedged by selling {data.currency} and payables by buying it, so hedge them in separate deals.
                  </p>
                )}
                <div className="divide-y divide-edge-subtle">
                  {items.map((i: ExposureItem, n) => {
                    const id = i.id ?? undefined;
                    const hedging = (id ? links[id] ?? [] : []).filter((l) => l.effective > 0);
                    const left = unhedgedOf(i, id ? links[id] : undefined);
                    return (
                      <div key={id ?? `${kind}-${n}`} className="py-1.5 flex items-center justify-between gap-3 body-sm">
                        <div className="flex items-center gap-2 min-w-0">
                          {invoices && id && (
                            <Checkbox size="sm" aria-label={`Hedge ${i.reference ?? 'invoice'}`}
                              checked={picked.has(id)} onChange={(on) => toggle(id, on)}
                              disabled={otherKind || left <= 0} />
                          )}
                          <div className="min-w-0">
                            <p className="font-medium text-primary-900 dark:text-neutral-50 truncate">{i.reference ?? '-'}</p>
                            <p className="caption truncate">
                              {[i.counterparty, i.date ? `due ${i.date}` : kind === 'CASH' ? 'on hand' : 'undated: due now'].filter(Boolean).join(' · ')}
                            </p>
                            {hedging.length > 0 && (
                              <p className="caption text-success-700 dark:text-success-300 truncate">
                                {left > 0 ? `${fmt(Math.abs(i.amount) - left)} hedged` : 'Hedged'} by {[...new Set(hedging.map((l) => l.dealRef))].join(', ')}
                              </p>
                            )}
                          </div>
                        </div>
                        <span className="font-mono tabular-nums shrink-0">{fmtSigned(i.amount)}</span>
                      </div>
                    );
                  })}
                </div>
              </section>
            );
          })}
          {data.items.length === 0 && <p className="caption">Nothing is counted in this bucket.</p>}
        </div>
      )}
      </div>
    </Drawer>
  );
};

// Buckets count days from today; tenors count from the spot date two business days later. These are
// the standard tenors whose value date lands inside each bucket (about 11, 34, 67, 96 and 186 days
// out), so a hedge suggested for a bucket is dated where the ladder will count it.
const BUCKET_TENOR: Record<string, string> = { D0_30: '1W', D31_60: '1M', D61_90: '2M', D91_180: '3M', D181_PLUS: '6M' };

const FxExposurePage: React.FC = () => {
  const { currentCorporateId, corporates } = useUser();
  const corporateId = currentCorporateId ?? '';
  const corporateName = corporates.find((c) => c.id === corporateId)?.name;

  const [ladder, setLadder] = useState<ExposureLadder | null>(null);
  const [entries, setEntries] = useState<ExposureEntry[]>([]);
  const [breaches, setBreaches] = useState<PolicyBreach[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [entryCurrency, setEntryCurrency] = useState<string | null>(null);

  const [ticket, setTicket] = useState<{ prefill: TicketPrefill } | null>(null);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [replace, setReplace] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const { navigate } = useNavigation();
  const [drill, setDrill] = useState<{ currency: string; bucket: ExposureBucket } | null>(null);
  const [drillData, setDrillData] = useState<ExposureBucketItems | null>(null);
  const [drillError, setDrillError] = useState<string | null>(null);
  const [drillLinks, setDrillLinks] = useState<Record<string, HedgeLink[]>>({});

  const openItems = (currency: string, bucket: ExposureBucket) => {
    setDrill({ currency, bucket });
    setDrillData(null);
    setDrillError(null);
    setDrillLinks({});
    fxExposureApi.items(corporateId, currency, bucket.bucket)
      .then(async (data) => {
        // The deals hedging these invoices; if that read fails the items still show, just unmarked.
        const ids = (kind: HedgeKind) => data.items.filter((i) => i.kind === kind && i.id).map((i) => i.id as string);
        const found = await Promise.all((['RECEIVABLE', 'PAYABLE'] as HedgeKind[]).map((k) => (ids(k).length
          ? fxDealApi.invoiceHedges(corporateId, k, ids(k)).catch(() => ({}))
          : Promise.resolve({}))));
        setDrillLinks(Object.assign({}, ...found));
        setDrillData(data);
      })
      .catch((err) => setDrillError(errorText(err)));
  };

  const load = useCallback(async (id: string, isCurrent: () => boolean) => {
    setLoading(true);
    try {
      const [l, e, b] = await Promise.all([fxExposureApi.ladder(id), fxExposureApi.entries(id), fxExposureApi.breaches(id).catch(() => [] as PolicyBreach[])]);
      if (!isCurrent()) return;
      setLadder(l);
      setEntries(e);
      setBreaches(b);
      setError(null);
    } catch (err) {
      if (isCurrent()) { setLadder(null); setEntries([]); setBreaches([]); setError(errorText(err)); }
    } finally {
      if (isCurrent()) setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!corporateId) return;
    let current = true;
    setLadder(null);
    setEntryCurrency(null);
    load(corporateId, () => current);
    return () => { current = false; };
  }, [corporateId, load]);

  const onUpload = async () => {
    const file = fileInput.current?.files?.[0];
    if (!file || !corporateId) { setUploadError('Choose a CSV file first.'); return; }
    setUploading(true);
    setUploadError(null);
    try {
      const res = await fxExposureApi.upload(corporateId, file, replace);
      toast.success(`Imported ${res.imported} rows${res.replaced ? `, replacing ${res.replaced}` : ''}`);
      setUploadOpen(false);
      await load(corporateId, () => true);
    } catch (err) {
      setUploadError(errorText(err));
    } finally {
      setUploading(false);
    }
  };

  const downloadTemplate = () => {
    const url = URL.createObjectURL(new Blob([TEMPLATE_CSV], { type: 'text/csv' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = 'fx-exposure-template.csv';
    a.click();
    URL.revokeObjectURL(url);
  };

  const hedgeBucket = (ccy: string, b: ExposureBucket) => {
    if (!ladder || b.adjustToBand == null) return;
    setTicket({
      prefill: {
        pair: `${ccy}/${ladder.baseCurrency}`,
        side: b.adjustToBand > 0 ? 'BUY' : 'SELL',
        amount: Math.abs(b.adjustToBand),
        tenor: BUCKET_TENOR[b.bucket] ?? '1M',
        instrument: 'FORWARD',
        exposureRef: `${ccy}|${b.bucket}`,
        purpose: `Hedge ${ccy} ${b.label}`,
      },
    });
  };

  /** Hedge the invoices ticked in a bucket: one forward dated in the bucket, linked to each for what is unhedged. */
  const hedgeInvoices = (kind: HedgeKind, picked: { item: ExposureItem; amount: number }[]) => {
    if (!ladder || !drill) return;
    const { currency, bucket } = drill;
    const refs = picked.map((p) => p.item.reference).filter(Boolean);
    setDrill(null);
    setTicket({
      prefill: {
        pair: `${currency}/${ladder.baseCurrency}`,
        side: kind === 'RECEIVABLE' ? 'SELL' : 'BUY',
        amount: Math.round(picked.reduce((t, p) => t + p.amount, 0) * 100) / 100,
        tenor: BUCKET_TENOR[bucket.bucket] ?? '1M',
        instrument: 'FORWARD',
        exposureRef: `${currency}|${bucket.bucket}`,
        purpose: `Hedge ${refs.length > 3 ? `${refs.slice(0, 3).join(', ')} and ${refs.length - 3} more` : refs.join(', ')}`,
        hedgeLinks: picked.map((p) => ({ kind, invoiceId: p.item.id as string, amount: p.amount })),
      },
    });
  };

  const hedgeEntry = (e: ExposureEntry) => {
    if (!ladder) return;
    setTicket({
      prefill: {
        pair: `${e.currency}/${ladder.baseCurrency}`,
        // Money coming in (positive exposure) is sold forward; money going out is bought.
        side: e.amount > 0 ? 'SELL' : 'BUY',
        amount: Math.abs(e.amount),
        instrument: 'FORWARD',
        valueDate: e.valueDate,
        purpose: `Hedge ${e.reference ?? e.description ?? 'declared exposure'}`,
      },
    });
  };

  const columns = (ccy: string): Column<ExposureBucket>[] => [
    { key: 'label', header: 'Settles in', render: (_, b) => <span className="font-medium">{b.label}</span> },
    { key: 'exposure', header: 'Net exposure', align: 'right', render: (_, b) => <span className="font-mono tabular-nums">{fmtSigned(b.exposure)}</span> },
    {
      key: 'forecast', header: 'Of which forecast', align: 'right', dropOrder: 4,
      render: (_, b) => <span className="font-mono tabular-nums">{b.forecast ? fmtSigned(b.forecast) : '-'}</span>,
    },
    { key: 'hedged', header: 'Hedged', align: 'right', dropOrder: 3, render: (_, b) => <span className="font-mono tabular-nums">{b.hedged ? fmtSigned(b.hedged) : '-'}</span> },
    {
      key: 'status', header: 'Hedge ratio vs policy',
      render: (_, b) => {
        const s = STATUS_BADGE[b.status];
        return (
          <span className="inline-flex items-center gap-2">
            <RatioBar ratio={b.hedgeRatio} min={b.minRatio} max={b.maxRatio} status={b.status} />
            {s && <Badge variant={s.variant} size="sm">{s.label}</Badge>}
          </span>
        );
      },
    },
    {
      key: 'adjustToBand', header: 'To reach policy', align: 'right', dropOrder: 1,
      render: (_, b) => b.adjustToBand == null
        ? <span className="text-neutral-500 dark:text-neutral-400">-</span>
        : <span className="font-mono tabular-nums">{b.adjustToBand > 0 ? 'Buy' : 'Sell'} {fmtN(Math.abs(b.adjustToBand))} {ccy}</span>,
    },
    {
      key: 'actions', header: 'Actions', width: '4rem', minWidth: 64,
      render: (_, b) => (
        <RowActions actions={[
          { label: "What's behind it", icon: <ListTree className="w-4 h-4" />, onClick: () => openItems(ccy, b) },
          { label: 'Hedge to policy', icon: <Send className="w-4 h-4" />, onClick: () => hedgeBucket(ccy, b), hidden: b.adjustToBand == null },
          { label: `View ${ccy} declared entries`, icon: <Filter className="w-4 h-4" />, onClick: () => setEntryCurrency(ccy) },
        ]} />
      ),
    },
  ];

  const entryColumns: Column<ExposureEntry>[] = [
    { key: 'kind', header: 'Kind', render: (_, e) => <Badge variant={e.kind === 'HEDGE' ? 'info' : 'neutral'} size="sm">{e.kind === 'HEDGE' ? 'Hedge' : 'Exposure'}</Badge> },
    { key: 'currency', header: 'Currency' },
    { key: 'amount', header: 'Amount', align: 'right', render: (_, e) => <span className="font-mono tabular-nums">{fmtSigned(e.amount)}</span> },
    { key: 'valueDate', header: 'Value date', render: (_, e) => <span className="font-mono tabular-nums">{e.valueDate}</span> },
    { key: 'reference', header: 'Reference', dropOrder: 2, render: (_, e) => e.reference ?? '-' },
    { key: 'description', header: 'Description', dropOrder: 1, render: (_, e) => e.description ?? '-' },
    {
      key: 'actions', header: 'Actions', width: '4rem', minWidth: 64,
      render: (_, e) => (
        <RowActions actions={[
          { label: 'Hedge this', icon: <Send className="w-4 h-4" />, onClick: () => hedgeEntry(e), hidden: e.kind === 'HEDGE' },
        ]} />
      ),
    },
  ];

  const shownEntries = entryCurrency ? entries.filter((e) => e.currency === entryCurrency) : entries;
  const policyBreaches = breaches.length;

  return (
    <Page>
      <PageHeader
        title="Exposure & Hedging"
        description="Currency exposure by time bucket against your hedge policy. Amounts stay in their own currency."
        actions={<Button onClick={() => { setUploadError(null); setUploadOpen(true); }} leftIcon={<Upload className="w-4 h-4" />}>Upload exposures</Button>}
      />

      <StatStrip columns={3}>
        <StatTile tone="primary" icon={<Coins className="w-5 h-5" />} label="Foreign currencies" value={ladder ? String(ladder.currencies.length) : '-'}
          sub={ladder ? `home currency ${ladder.baseCurrency}` : corporateName} loading={loading && !ladder} />
        <StatTile tone={policyBreaches ? 'danger' : 'success'} icon={<ShieldAlert className="w-5 h-5" />} label="Outside policy"
          value={ladder ? String(policyBreaches) : '-'}
          sub={policyBreaches ? 'time buckets to hedge' : 'within the hedge policy'} />
        <StatTile tone={!ladder?.forecastRunAt ? 'neutral' : ladder.forecastStale ? 'warning' : 'success'} icon={<TrendingUp className="w-5 h-5" />}
          label="Cash Forecast" value={ladder?.forecastRunAt ? new Date(ladder.forecastRunAt).toLocaleDateString() : 'No run'}
          sub={!ladder?.forecastRunAt ? 'invoices and cash only' : ladder.forecastStale ? 'out of date: run it again in Cash Forecast' : 'flows no invoice covers'} />
      </StatStrip>

      {error && <p className="text-body-sm text-error-700 dark:text-error-300">{error}</p>}

      {ladder?.policyDefault && (
        <Alert variant="warning">Default hedge policy: these bands are the application&apos;s defaults, not a policy you agreed.</Alert>
      )}

      {ladder && ladder.currencies.length === 0 && !loading && (
        <Card>
          <p className="text-body-sm text-neutral-600 dark:text-neutral-300">
            No foreign-currency exposure for {ladder.corporateName}: nothing open in payables or receivables, no cash held,
            and no declared exposures outside {ladder.baseCurrency}.
          </p>
        </Card>
      )}

      {ladder?.currencies.map((row) => (
        <Card key={row.currency}>
          <CardHeader
            title={`${row.currency} exposure`}
            subtitle={row.rateToBase
              ? `1 ${row.currency} = ${row.rateToBase} ${ladder.baseCurrency}`
              : `No ${row.currency} to ${ladder.baseCurrency} rate`}
          />
          <DataTable<ExposureBucket>
            data={row.buckets.filter((b) => b.exposure !== 0 || b.hedged !== 0)}
            columns={columns(row.currency)}
            keyExtractor={(b) => b.bucket}
            onRowClick={(b) => openItems(row.currency, b)}
            compact
            hairline
          />
        </Card>
      ))}

      <Card>
        <CardHeader
          title="Declared exposures and hedges"
          subtitle={entryCurrency ? `${shownEntries.length} ${entryCurrency} entries` : `${entries.length} entries`}
          action={entryCurrency
            ? <Button size="sm" variant="outline" onClick={() => setEntryCurrency(null)} leftIcon={<X className="w-4 h-4" />}>Show all currencies</Button>
            : undefined}
        />
        <DataTable<ExposureEntry>
          data={shownEntries}
          columns={entryColumns}
          keyExtractor={(e) => e.id}
          compact
          hairline
          emptyTitle="Nothing declared"
          emptyDescription="Use Upload exposures to add ERP exposures or hedges held with other banks."
        />
      </Card>

      <Modal
        isOpen={uploadOpen}
        onClose={() => setUploadOpen(false)}
        title="Upload exposures"
        subtitle={corporateName}
        footer={
          <>
            <Button variant="ghost" onClick={() => setUploadOpen(false)}>Cancel</Button>
            <Button onClick={onUpload} loading={uploading} disabled={!corporateId} leftIcon={<Upload className="w-4 h-4" />}>Upload</Button>
          </>
        }
      >
        <div className="space-y-4">
          <div>
            <label htmlFor="fx-exposure-file" className="block text-body-sm font-medium text-neutral-700 dark:text-neutral-200 mb-1.5">CSV file</label>
            <input id="fx-exposure-file" ref={fileInput} type="file" accept=".csv,text/csv" className="block text-body-sm text-primary-900 dark:text-neutral-50" />
          </div>
          <Checkbox checked={replace} onChange={setReplace} label="Replace earlier uploads" />
          <Button size="sm" variant="secondary" onClick={downloadTemplate} leftIcon={<Download className="w-4 h-4" />}>Download template</Button>
          <p className="text-caption text-neutral-500 dark:text-neutral-400">
            For what payables, receivables, balances and the Cash Forecast do not cover: ERP forecasts and forwards held
            with other banks. Use the template&apos;s format; the whole file is rejected if any row is wrong.
          </p>
          {uploadError && <p className="text-body-sm text-error-700 dark:text-error-300">{uploadError}</p>}
        </div>
      </Modal>

      <BucketItemsDrawer
        open={drill}
        data={drillData}
        error={drillError}
        links={drillLinks}
        onClose={() => setDrill(null)}
        onOpenPage={(page) => { setDrill(null); navigate(page); }}
        onHedge={hedgeInvoices}
      />

      <NewDealDrawer
        isOpen={!!ticket}
        prefill={ticket?.prefill ?? null}
        corporateId={corporateId}
        onClose={() => setTicket(null)}
        onChanged={() => { if (corporateId) load(corporateId, () => true); }}
      />
    </Page>
  );
};

export default FxExposurePage;
