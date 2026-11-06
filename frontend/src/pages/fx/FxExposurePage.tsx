// ============================================================================
// FxExposurePage ("Exposure & Hedging") — the foreign-currency exposure ladder per currency against the
// corporate's hedge policy, with the declared exposures and hedges that payables, receivables and balances do
// not capture (ERP forecasts, forwards held with other banks), uploaded as CSV. Amounts stay native to their
// currency; a base-currency equivalent is never summed across currencies, and the rate behind any single
// conversion is shown. From a row the corporate can hedge a gap: that opens the price ticket pre-filled.
// ============================================================================

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Upload, Download, AlertTriangle, CheckCircle, Coins, ShieldAlert, ListChecks, Send, Filter, X } from 'lucide-react';
import toast from 'react-hot-toast';
import { Page } from '../../components/layout/Page';
import { PageHeader } from '../../components/layout/PageHeader';
import { StatStrip } from '../../components/layout/StatStrip';
import { Badge, Button, Card, CardHeader, DataTable, RowActions, StatTile } from '../../components/ui';
import { Modal } from '../../components/ui/enhanced';
import { Checkbox } from '../../components/ui/Checkbox';
import type { Column } from '../../components/ui/DataTable';
import { useUser } from '../../context/useUser';
import {
  fxExposureApi, ExposureBucket, ExposureEntry, ExposureLadder, HedgeStatus, PolicyBreach,
} from '../../services/fxMarketApi';
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
    { key: 'hedged', header: 'Hedged', align: 'right', dropOrder: 3, render: (_, b) => <span className="font-mono tabular-nums">{b.hedged ? fmtSigned(b.hedged) : '-'}</span> },
    { key: 'hedgeRatio', header: 'Hedge ratio vs policy', dropOrder: 2, render: (_, b) => <RatioBar ratio={b.hedgeRatio} min={b.minRatio} max={b.maxRatio} status={b.status} /> },
    {
      key: 'status', header: 'Status',
      render: (_, b) => {
        const s = STATUS_BADGE[b.status];
        return s ? <Badge variant={s.variant} size="sm">{s.label}</Badge> : null;
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
        description="Foreign-currency exposure by currency and time bucket against the hedge policy. Amounts stay in each currency and are never summed across currencies. Declared exposures and hedges cover what payables, receivables and balances do not: ERP forecasts and forwards held with other banks."
        actions={<Button onClick={() => { setUploadError(null); setUploadOpen(true); }} leftIcon={<Upload className="w-4 h-4" />}>Upload exposures</Button>}
      />

      <StatStrip columns={3}>
        <StatTile tone="primary" icon={<Coins className="w-5 h-5" />} label="Foreign currencies" value={ladder ? String(ladder.currencies.length) : '-'}
          sub={ladder ? `home currency ${ladder.baseCurrency}` : corporateName} loading={loading && !ladder} />
        <StatTile tone={policyBreaches ? 'danger' : 'success'} icon={<ShieldAlert className="w-5 h-5" />} label="Outside policy"
          value={ladder ? String(policyBreaches) : '-'} sub={policyBreaches ? 'time buckets to hedge' : 'within the hedge policy'} />
        <StatTile tone="info" icon={<ListChecks className="w-5 h-5" />} label="Declared entries" value={String(entries.length)} sub="exposures and hedges uploaded" />
      </StatStrip>

      {error && <p className="text-body-sm text-error-700 dark:text-error-300">{error}</p>}

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
              ? `Stored platform rate: 1 ${row.currency} = ${row.rateToBase} ${ladder.baseCurrency}. Amounts are in ${row.currency}.`
              : `No platform rate from ${row.currency} to ${ladder.baseCurrency}, so no ${ladder.baseCurrency} equivalent is shown.`}
            action={row.buckets.some((b) => b.status === 'UNDER_HEDGED' || b.status === 'OVER_HEDGED')
              ? <Badge variant="error" icon={<AlertTriangle className="w-3 h-3" />}>Outside policy</Badge>
              : <Badge variant="success" icon={<CheckCircle className="w-3 h-3" />}>Within policy</Badge>}
          />
          <DataTable<ExposureBucket>
            data={row.buckets.filter((b) => b.exposure !== 0 || b.hedged !== 0)}
            columns={columns(row.currency)}
            keyExtractor={(b) => b.bucket}
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
            Columns: kind (EXPOSURE or HEDGE), currency, amount, valueDate (YYYY-MM-DD), optional reference and description.
            Exposure: positive = you will receive the currency, negative = you will pay it. Hedge: the deal’s own position,
            so a forward sale of EUR is negative. The whole file is rejected if any row is wrong.
          </p>
          {uploadError && <p className="text-body-sm text-error-700 dark:text-error-300">{uploadError}</p>}
        </div>
      </Modal>

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
