// ============================================================================
// FxReportsPage ("FX Reports") — the corporate's four FX reports for a period: deal history, maturities and
// settlements, the mark-to-market statement (as of the period's last day) and the confirmation archive. The
// server builds each as one table, so the screen, the CSV and the PDF always agree.
// ============================================================================

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Download, FileText, RefreshCw } from 'lucide-react';
import toast from 'react-hot-toast';
import { Page } from '../../components/layout/Page';
import { PageHeader } from '../../components/layout/PageHeader';
import { Button, Card, CardHeader, DataTable, Input, Select } from '../../components/ui';
import { Tabs } from '../../components/ui/enhanced';
import type { Column } from '../../components/ui/DataTable';
import { useUser } from '../../context/useUser';
import { fxDealApi, ReportKind, ReportTable } from '../../services/fxDealApi';
import { errorText } from './fxFormat';

const REPORTS: { id: ReportKind; label: string; hint: string }[] = [
  { id: 'deals', label: 'Deal history', hint: 'Every deal traded in the period.' },
  { id: 'settlements', label: 'Maturities & settlements', hint: 'What settled in the period, from which accounts, and what is still due in it.' },
  { id: 'mtm', label: 'MTM statement', hint: 'Deals open at the end of the period and what they are worth then; positive is in your favour.' },
  { id: 'confirmations', label: 'Confirmation archive', hint: 'Confirmations issued in the period. The PDF holds the confirmations themselves, one per page.' },
];

const iso = (d: Date) => d.toLocaleDateString('en-CA');

const PERIODS = (() => {
  const now = new Date();
  const y = now.getFullYear();
  const m = now.getMonth();
  const q = Math.floor(m / 3) * 3;
  return [
    { value: 'this-month', label: 'This month', from: iso(new Date(y, m, 1)), to: iso(new Date(y, m + 1, 0)) },
    { value: 'last-month', label: 'Last month', from: iso(new Date(y, m - 1, 1)), to: iso(new Date(y, m, 0)) },
    { value: 'this-quarter', label: 'This quarter', from: iso(new Date(y, q, 1)), to: iso(new Date(y, q + 3, 0)) },
    { value: 'this-year', label: 'This year', from: iso(new Date(y, 0, 1)), to: iso(new Date(y, 11, 31)) },
  ];
})();

const FxReportsPage: React.FC = () => {
  const { currentCorporateId } = useUser();
  const corporateId = currentCorporateId ?? '';
  const [kind, setKind] = useState<ReportKind>('deals');
  const [period, setPeriod] = useState('this-month');
  const [from, setFrom] = useState(PERIODS[0].from);
  const [to, setTo] = useState(PERIODS[0].to);
  const [table, setTable] = useState<ReportTable | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!corporateId) return;
    setLoading(true);
    try {
      setTable(await fxDealApi.report(kind, corporateId, from, to));
      setError(null);
    } catch (err) {
      setTable(null);
      setError(errorText(err));
    } finally {
      setLoading(false);
    }
  }, [kind, corporateId, from, to]);

  useEffect(() => { load(); }, [load]);

  const choosePeriod = (v: string) => {
    setPeriod(v);
    const p = PERIODS.find((x) => x.value === v);
    if (p) { setFrom(p.from); setTo(p.to); }
  };

  const download = async (format: 'csv' | 'pdf') => {
    try {
      const blob = await fxDealApi.reportFile(kind, corporateId, from, to, format);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `fx-${kind}-${from}-to-${to}.${format}`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (err) {
      toast.error(errorText(err));
    }
  };

  // Rows come as arrays of text; the first and last columns read as the row's name and outcome.
  const columns: Column<string[]>[] = useMemo(() => (table?.columns ?? []).map((c, i) => ({
    key: String(i),
    header: c,
    dropOrder: i === 0 ? undefined : table && i > 2 ? table.columns.length - i : undefined,
    render: (_: unknown, r: string[]) => (
      <span className={i === 0 ? 'font-mono text-caption' : /^[+-]?[\d,]+\.\d{2}/.test(r[i]) || /^[A-Z]{3} [\d,]/.test(r[i]) ? 'font-mono tabular-nums' : 'text-body-sm'}>
        {r[i] || '-'}
      </span>
    ),
  })), [table]);

  const report = REPORTS.find((r) => r.id === kind) as (typeof REPORTS)[number];

  return (
    <Page>
      <PageHeader
        title="FX Reports"
        description="Your FX deals, settlements, mark-to-market and confirmations for a period, on screen or as CSV and PDF."
      />

      <Tabs tabs={REPORTS.map((r) => ({ id: r.id, label: r.label }))} activeTab={kind} onChange={(id) => setKind(id as ReportKind)} />

      {error && <p className="text-body-sm text-error-700 dark:text-error-300">{error}</p>}
      {!corporateId && <p className="text-body-sm text-neutral-600 dark:text-neutral-300">Choose a corporate in the header to see its reports.</p>}

      <Card>
        <CardHeader title={table?.title ?? report.label} subtitle={table?.subtitle ?? report.hint} />
        <p className="text-caption text-neutral-500 dark:text-neutral-400 mb-3">{report.hint}</p>
        <DataTable<string[]>
          data={table?.rows ?? []}
          columns={columns}
          keyExtractor={(r) => r.join('|')}
          loading={loading && !table}
          compact
          hairline
          emptyTitle="Nothing in this period"
          emptyDescription="Choose another period or report."
          actions={
            <>
              <Select aria-label="Period" selectSize="sm" value={period} onChange={(e) => choosePeriod(e.target.value)}
                options={[...PERIODS.map((p) => ({ value: p.value, label: p.label })), { value: 'custom', label: 'Custom dates' }]} />
              {period === 'custom' && (
                <>
                  <Input aria-label="From" type="date" inputSize="sm" value={from} onChange={(e) => setFrom(e.target.value)} />
                  <Input aria-label="To" type="date" inputSize="sm" value={to} onChange={(e) => setTo(e.target.value)} />
                </>
              )}
              <Button size="sm" variant="outline" onClick={load} leftIcon={<RefreshCw className="w-4 h-4" />}>Refresh</Button>
              <Button size="sm" variant="outline" onClick={() => download('csv')} disabled={!corporateId} leftIcon={<FileText className="w-4 h-4" />}>CSV</Button>
              <Button size="sm" onClick={() => download('pdf')} disabled={!corporateId} leftIcon={<Download className="w-4 h-4" />}>PDF</Button>
            </>
          }
        />
      </Card>
    </Page>
  );
};

export default FxReportsPage;
