// ============================================================================
// FxExposurePanel — phase 2 of the corporate FX feature (tasks/fx-corporate-design.md):
// the foreign-currency exposure ladder per corporate against its hedge policy, plus the
// CSV upload for exposures and hedges that do not live in payables, receivables or
// balances. Amounts stay native to their currency; a base-currency equivalent is never
// summed across currencies, and the rate behind any single conversion is shown.
// ============================================================================

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Upload, Download, AlertTriangle, CheckCircle } from 'lucide-react';
import toast from 'react-hot-toast';
import { ScopeSelector } from '../components/layout/ScopeSelector';
import { Card, CardHeader, Badge, Button, DataTable } from '../components/ui';
import { Checkbox } from '../components/ui/Checkbox';
import type { Column } from '../components/ui/DataTable';
import { cn } from '../utils';
import {
  fxExposureApi, ExposureBucket, ExposureEntry, ExposureLadder, HedgeStatus, PolicyBreach,
} from '../services/fxMarketApi';

const fmt = (n: number, dp = 0) => n.toLocaleString('en-US', { minimumFractionDigits: dp, maximumFractionDigits: dp });
const fmtSigned = (n: number) => (n > 0 ? '+' : n < 0 ? '-' : '') + fmt(Math.abs(n));
const pct = (r?: number | null) => (r == null ? '-' : `${Math.round(r * 100)}%`);

const errorText = (err: unknown): string => {
  const e = err as { response?: { data?: { message?: string } }; message?: string };
  return e?.response?.data?.message || e?.message || 'Request failed';
};

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

const FxExposurePanel: React.FC = () => {
  const [corporates, setCorporates] = useState<{ id: string; legalName?: string; tradeName?: string }[]>([]);
  const [corporateId, setCorporateId] = useState('');
  const [ladder, setLadder] = useState<ExposureLadder | null>(null);
  const [entries, setEntries] = useState<ExposureEntry[]>([]);
  const [breaches, setBreaches] = useState<PolicyBreach[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [replace, setReplace] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  useEffect(() => {
    let current = true;
    Promise.all([
      fetch('/api/v1/corporates').then((r) => r.json()),
      fxExposureApi.breaches().catch(() => [] as PolicyBreach[]),
    ]).then(([result, all]) => {
      if (!current) return;
      const list = (result.data || result || []) as typeof corporates;
      setCorporates(list);
      setBreaches(all);
      setCorporateId((prev) => prev || all[0]?.corporateId || list[0]?.id || '');
    }).catch((err) => current && setError(errorText(err)));
    return () => { current = false; };
  }, []);

  const load = useCallback(async (id: string, isCurrent: () => boolean) => {
    setLoading(true);
    try {
      const [l, e] = await Promise.all([fxExposureApi.ladder(id), fxExposureApi.entries(id)]);
      if (!isCurrent()) return;
      setLadder(l);
      setEntries(e);
      setError(null);
    } catch (err) {
      if (isCurrent()) { setLadder(null); setEntries([]); setError(errorText(err)); }
    } finally {
      if (isCurrent()) setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!corporateId) return;
    let current = true;
    load(corporateId, () => current);
    return () => { current = false; };
  }, [corporateId, load]);

  const corporateBreaches = breaches.filter((b) => b.corporateId === corporateId).length;

  const onUpload = async () => {
    const file = fileInput.current?.files?.[0];
    if (!file || !corporateId) return;
    setUploading(true);
    setUploadError(null);
    try {
      const res = await fxExposureApi.upload(corporateId, file, replace);
      toast.success(`Imported ${res.imported} rows${res.replaced ? `, replacing ${res.replaced}` : ''}`);
      if (fileInput.current) fileInput.current.value = '';
      await load(corporateId, () => true);
      setBreaches(await fxExposureApi.breaches().catch(() => breaches));
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

  const columns = (ccy: string): Column<ExposureBucket>[] => [
    { key: 'label', header: 'Settles in', render: (_, b) => <span className="font-medium">{b.label}</span> },
    {
      key: 'exposure', header: 'Net exposure', align: 'right',
      render: (_, b) => <span className="font-mono tabular-nums">{fmtSigned(b.exposure)}</span>,
    },
    {
      key: 'hedged', header: 'Hedged', align: 'right', dropOrder: 3,
      render: (_, b) => <span className="font-mono tabular-nums">{b.hedged ? fmtSigned(b.hedged) : '-'}</span>,
    },
    {
      key: 'hedgeRatio', header: 'Hedge ratio vs policy', dropOrder: 2,
      render: (_, b) => <RatioBar ratio={b.hedgeRatio} min={b.minRatio} max={b.maxRatio} status={b.status} />,
    },
    {
      key: 'status', header: 'Status',
      render: (_, b) => {
        const s = STATUS_BADGE[b.status];
        return s ? <Badge variant={s.variant} size="sm">{s.label}</Badge> : null;
      },
    },
    {
      key: 'adjustToBand', header: 'To reach policy', align: 'right', dropOrder: 1,
      render: (_, b) => b.adjustToBand == null ? <span className="text-neutral-500 dark:text-neutral-400">-</span> : (
        <span className="font-mono tabular-nums">{b.adjustToBand > 0 ? 'Buy' : 'Sell'} {fmt(Math.abs(b.adjustToBand))} {ccy}</span>
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
  ];

  return (
    <div className="space-y-4">
      <Card>
        <div className="flex flex-wrap items-end justify-between gap-4">
          <div className="min-w-[16rem] flex-1 max-w-md">
            <ScopeSelector
              mode="corporate-only"
              corporates={corporates}
              selectedCorporateId={corporateId}
              onCorporateChange={setCorporateId}
              loading={!corporates.length && !error}
            />
          </div>
          {ladder && (
            <div className="flex items-center gap-3 text-body-sm text-neutral-600 dark:text-neutral-300">
              <span>Home currency <strong className="text-primary-900 dark:text-neutral-50">{ladder.baseCurrency}</strong></span>
              {corporateBreaches > 0
                ? <Badge variant="error" icon={<AlertTriangle className="w-3 h-3" />}>{corporateBreaches} outside policy</Badge>
                : <Badge variant="success" icon={<CheckCircle className="w-3 h-3" />}>Within policy</Badge>}
            </div>
          )}
        </div>
        {error && <p className="text-body-sm text-error-700 dark:text-error-300 mt-3">{error}</p>}
      </Card>

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
          subtitle="For ERP forecasts and forwards held with other banks, which payables, receivables and balances do not capture."
          action={<Button variant="secondary" size="sm" onClick={downloadTemplate} leftIcon={<Download className="w-4 h-4" />}>Template</Button>}
        />
        <div className="flex flex-wrap items-end gap-3">
          <div>
            <label htmlFor="fx-exposure-file" className="block text-body-sm font-medium text-neutral-700 dark:text-neutral-200 mb-1.5">CSV file</label>
            <input
              id="fx-exposure-file"
              ref={fileInput}
              type="file"
              accept=".csv,text/csv"
              className="block text-body-sm text-primary-900 dark:text-neutral-50"
            />
          </div>
          <Checkbox checked={replace} onChange={setReplace} label="Replace earlier uploads" />
          <Button size="sm" onClick={onUpload} disabled={!corporateId} loading={uploading} leftIcon={<Upload className="w-4 h-4" />}>
            Upload
          </Button>
        </div>
        <p className={cn('text-caption text-neutral-500 dark:text-neutral-400 mt-2')}>
          Columns: kind (EXPOSURE or HEDGE), currency, amount, valueDate (YYYY-MM-DD), optional reference and description.
          Exposure: positive = you will receive the currency, negative = you will pay it. Hedge: the deal's own position,
          so a forward sale of EUR is negative. The whole file is rejected if any row is wrong.
        </p>
        {uploadError && <p className="text-body-sm text-error-700 dark:text-error-300 mt-3">{uploadError}</p>}
        <div className="mt-4">
          <DataTable<ExposureEntry>
            data={entries}
            columns={entryColumns}
            keyExtractor={(e) => e.id}
            compact
            hairline
            emptyTitle="Nothing declared"
            emptyDescription="Upload a CSV to add exposures or hedges for this corporate."
          />
        </div>
      </Card>
    </div>
  );
};

export default FxExposurePanel;
