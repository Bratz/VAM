import React, { useCallback, useEffect, useState } from 'react';
import { Download, Undo2, Workflow, CalendarCheck, Save, QrCode, Copy, ExternalLink, FileCode } from 'lucide-react';
import QRCode from 'react-qr-code';
import { Badge, Button, DataTable, Drawer, Input, RowActions, Select } from '../ui';
import { Modal } from '../ui/enhanced';
import { collectionsApi, LineStatus, OutboxEmail, outboxApi, payLink, RunDetail, RunLine, RunSummary } from '../../services/collectionsApi';
import { OutboxEmailView } from './OutboxTab';
import { errorText } from '../../pages/fx/fxFormat';
import { formatCurrency, formatDate } from '../../utils';
import { METHOD, STATUS } from './collectionLabels';

const RETURNABLE: LineStatus[] = ['SENT', 'SETTLED', 'AVAILABLE'];
const SOURCE: Record<RunSummary['source'], string> = { INVOICES: 'From invoices', FILE: 'File upload', API: 'ERP / API' };

/**
 * Collection runs for one corporate: each run's direct debits and Requests to Pay, where every line has got to,
 * the run's pain.008, and the corporate's hold period. Returns can be recorded by hand (there is no clearing link),
 * and due debits settled up to a date.
 */
export const CollectionRunsTab: React.FC<{ corporateId: string }> = ({ corporateId }) => {
  const [runs, setRuns] = useState<RunSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [holdDays, setHoldDays] = useState('');
  const [asOf, setAsOf] = useState(new Date().toISOString().slice(0, 10));
  const [busy, setBusy] = useState(false);
  const [open, setOpen] = useState<RunDetail | null>(null);
  const [returning, setReturning] = useState<RunLine | null>(null);
  const [reasons, setReasons] = useState<Record<string, string>>({});
  const [reason, setReason] = useState('AM04');
  const [paying, setPaying] = useState<RunLine | null>(null);
  const [copied, setCopied] = useState(false);
  const [emails, setEmails] = useState<OutboxEmail[] | null>(null);

  const load = useCallback(async () => {
    if (!corporateId) return;
    setLoading(true);
    setError(null);
    try {
      const [r, days] = await Promise.all([collectionsApi.runs(corporateId), collectionsApi.holdDays(corporateId)]);
      setRuns(r);
      setHoldDays(String(days));
    } catch (err) {
      setError(errorText(err));
    } finally {
      setLoading(false);
    }
  }, [corporateId]);

  useEffect(() => { load(); }, [load]);

  const openRun = async (id: string) => {
    try {
      setOpen(await collectionsApi.run(id));
    } catch (err) {
      setError(errorText(err));
    }
  };

  const saveHold = async () => {
    setBusy(true);
    try {
      const days = await collectionsApi.setHoldDays(corporateId, Number(holdDays));
      setNotice(`Collected money is now held for ${days} business day${days === 1 ? '' : 's'}.`);
    } catch (err) {
      setError(errorText(err));
    } finally {
      setBusy(false);
    }
  };

  const settle = async () => {
    setBusy(true);
    setError(null);
    try {
      const r = await collectionsApi.process(asOf);
      setNotice(`Up to ${formatDate(r.asOf)}: ${r.settled} collected, ${r.released} released from hold`
        + (r.notCollected ? `, ${r.notCollected} not collected` : '') + (r.failed ? `, ${r.failed} could not settle` : '') + '.');
      await load();
      if (open) await openRun(open.run.id);
    } catch (err) {
      setError(errorText(err));
    } finally {
      setBusy(false);
    }
  };

  const startReturn = async (l: RunLine) => {
    setReturning(l);
    if (!Object.keys(reasons).length) {
      try {
        setReasons(await collectionsApi.returnReasons());
      } catch (err) {
        setError(errorText(err));
      }
    }
  };

  const recordReturn = async () => {
    if (!returning || !open) return;
    setBusy(true);
    try {
      const o = await collectionsApi.returnLine(returning.id, reason);
      setNotice(`${returning.receivableNumber}: ${o.detail}`);
      setReturning(null);
      await openRun(open.run.id);
      await load();
    } catch (err) {
      setError(errorText(err));
    } finally {
      setBusy(false);
    }
  };

  const download = async (id: string, ref: string) => {
    try {
      await collectionsApi.downloadPain008(id, ref);
    } catch (err) {
      setError(errorText(err));
    }
  };

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="flex items-end gap-2">
          <Input id="collection-hold-days" label="Hold collected direct debits for" type="number" min="0" max="60"
            value={holdDays} onChange={(e) => setHoldDays(e.target.value)} hint="Business days: the window for returns" className="w-28" />
          <Button size="sm" variant="outline" onClick={saveHold} loading={busy} disabled={!corporateId || holdDays === ''} leftIcon={<Save className="w-4 h-4" />}>Save</Button>
        </div>
        <div className="flex items-end gap-2">
          <Input id="collection-as-of" label="Settle due collections up to" type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} />
          <Button size="sm" onClick={settle} loading={busy} leftIcon={<CalendarCheck className="w-4 h-4" />}>Settle</Button>
        </div>
      </div>
      <p className="text-caption text-neutral-500 dark:text-neutral-400">
        Direct debits settle on their collection date (this also runs every evening), are held for the return window, then become available.
      </p>
      {notice && <p className="text-body-sm text-success-700 dark:text-success-300">{notice}</p>}
      {error && <p className="text-body-sm text-error-700 dark:text-error-300">{error}</p>}

      <DataTable
        data={runs}
        loading={loading}
        keyExtractor={(r) => r.id}
        onRowClick={(r) => openRun(r.id)}
        emptyIcon={<Workflow className="w-12 h-12 text-neutral-300 dark:text-neutral-400" />}
        emptyTitle={corporateId ? 'No collection runs yet' : 'Choose a corporate'}
        emptyDescription={corporateId ? 'Select invoices and use Plan collection, or upload a Collections file.' : undefined}
        columns={[
          {
            key: 'runRef', header: 'Run',
            render: (_, r) => (
              <div>
                <p className="text-body-sm font-mono whitespace-nowrap text-primary-900 dark:text-neutral-50">{r.runRef}</p>
                <p className="text-caption text-neutral-500 dark:text-neutral-400">{SOURCE[r.source]} · {formatDate(r.createdAt)}</p>
              </div>
            ),
          },
          {
            key: 'totals', header: 'Amounts',
            render: (_, r) => (
              <div className="text-body-sm space-y-0.5">
                {r.totals.map(t => (
                  <p key={t.method + t.currency} className="whitespace-nowrap">
                    <span className="text-neutral-500 dark:text-neutral-400">{METHOD[t.method].label} · {t.count}</span>{' '}
                    <span className="tabular-nums">{formatCurrency(t.amount, t.currency)}</span>
                  </p>
                ))}
              </div>
            ),
          },
          {
            key: 'lineStatuses', header: 'Where they are',
            render: (_, r) => (
              <div className="flex flex-wrap gap-1">
                {(Object.entries(r.lineStatuses) as [LineStatus, number][]).map(([s, n]) => (
                  <Badge key={s} variant={STATUS[s].variant} size="sm">{STATUS[s].label} · {n}</Badge>
                ))}
              </div>
            ),
          },
          {
            key: 'completed', header: '', align: 'right',
            render: (_, r) => r.hasPain008
              ? <Button size="sm" variant="ghost" onClick={(e) => { e.stopPropagation(); download(r.id, r.runRef); }} leftIcon={<Download className="w-4 h-4" />}>pain.008</Button>
              : null,
          },
        ]}
      />

      <Drawer
        isOpen={!!open}
        onClose={() => setOpen(null)}
        title={open ? `Collection run ${open.run.runRef}` : ''}
        subtitle={open ? `${SOURCE[open.run.source]} · ${formatDate(open.run.createdAt)}${open.run.completed ? ' · complete' : ''}` : undefined}
        size="xl"
        footer={open?.run.hasPain008 ? <Button variant="outline" onClick={() => download(open.run.id, open.run.runRef)} leftIcon={<Download className="w-4 h-4" />}>Download pain.008</Button> : undefined}
      >
        {open && (
          <DataTable
            data={open.lines}
            keyExtractor={(l) => l.id}
            columns={[
              {
                key: 'receivableNumber', header: 'Invoice',
                render: (_, l) => (
                  <div>
                    <p className="text-body-sm font-mono whitespace-nowrap">{l.receivableNumber ?? '—'}</p>
                    <p className="text-caption text-neutral-500 dark:text-neutral-400">{l.customerName ?? ''}</p>
                  </div>
                ),
              },
              {
                key: 'amount', header: 'Amount', align: 'right',
                render: (_, l) => (
                  <div className="text-body-sm tabular-nums text-right">
                    <p className="whitespace-nowrap">{l.amount != null && l.currency ? formatCurrency(l.amount, l.currency) : '—'}</p>
                    {l.creditGross != null && l.creditCurrency && (
                      <p className="text-caption text-neutral-500 dark:text-neutral-400 whitespace-nowrap">
                        {formatCurrency(l.creditGross, l.creditCurrency)} at {l.fxRate}{l.fxDealRef ? ` · ${l.fxDealRef}` : ''}
                      </p>
                    )}
                  </div>
                ),
              },
              {
                key: 'status', header: 'Status',
                render: (_, l) => (
                  <div className="space-y-0.5">
                    <Badge variant={STATUS[l.status].variant} size="sm">{STATUS[l.status].label}</Badge>
                    <p className="text-caption text-neutral-500 dark:text-neutral-400 whitespace-nowrap">
                      {l.status === 'SENT' && l.collectionDate ? `Collects ${formatDate(l.collectionDate)}`
                        : l.status === 'SETTLED' && l.holdUntil ? `Held until ${formatDate(l.holdUntil)}`
                          : l.status === 'AVAILABLE' && l.releasedOn ? `Available since ${formatDate(l.releasedOn)}`
                            : l.returnedOn ? `${l.returnCode} on ${formatDate(l.returnedOn)}` : METHOD[l.method].label}
                    </p>
                  </div>
                ),
              },
              {
                key: 'mandateReference', header: 'Mandate / reason',
                render: (_, l) => (
                  <div className="text-body-sm">
                    {l.mandateReference && <p className="font-mono whitespace-nowrap">{l.mandateReference} · {l.sequenceType}</p>}
                    {l.reason && <p className="text-neutral-500 dark:text-neutral-400">{l.reason}</p>}
                    {l.paymentLinkToken && (
                      <Button size="sm" variant="ghost" className="mt-1 -ml-2" leftIcon={<QrCode className="w-4 h-4" />}
                        onClick={() => {
                          setCopied(false); setEmails(null); setPaying(l);
                          outboxApi.forRecord(l.receivableId).then(setEmails).catch(() => setEmails([]));
                        }}>
                        Pay link &amp; QR
                      </Button>
                    )}
                  </div>
                ),
              },
              {
                key: 'id', header: '', align: 'right',
                render: (_, l) => (
                  <RowActions actions={[
                    { label: 'Download pain.013', icon: <FileCode className="w-4 h-4" />, hidden: !l.requestToPayKept,
                      onClick: () => collectionsApi.downloadRequestToPay(l.receivableId, l.receivableNumber ?? 'request').catch(err => setError(errorText(err))) },
                    { label: l.status === 'SENT' ? 'Mark rejected' : 'Mark returned', icon: <Undo2 className="w-4 h-4" />, danger: true,
                      onClick: () => startReturn(l), hidden: l.method !== 'DIRECT_DEBIT' || !RETURNABLE.includes(l.status) },
                  ]} />
                ),
              },
            ]}
          />
        )}
      </Drawer>

      <Modal
        isOpen={!!paying}
        onClose={() => setPaying(null)}
        title={paying ? `Pay ${paying.receivableNumber}` : ''}
        subtitle={paying ? `${paying.customerName ?? ''} · ${paying.amount != null && paying.currency ? formatCurrency(paying.amount, paying.currency) : ''}` : undefined}
        footer={<Button variant="ghost" onClick={() => setPaying(null)}>Close</Button>}
      >
        {paying?.paymentLinkToken && (
          <div className="flex flex-col sm:flex-row gap-5 items-center sm:items-start">
            <div className="p-3 bg-white rounded-lg border border-neutral-200 shrink-0">
              <QRCode value={payLink(paying.paymentLinkToken)} size={156} />
            </div>
            <div className="space-y-3 min-w-0 w-full">
              <div>
                <p className="text-caption text-neutral-500 dark:text-neutral-400">Pay link — the customer opens it, or scans the code</p>
                <p className="text-body-sm font-mono break-all">{payLink(paying.paymentLinkToken)}</p>
              </div>
              <div className="flex flex-wrap gap-2">
                <Button size="sm" variant="outline" leftIcon={<Copy className="w-4 h-4" />}
                  onClick={() => navigator.clipboard.writeText(payLink(paying.paymentLinkToken!)).then(() => setCopied(true))}>
                  {copied ? 'Copied' : 'Copy link'}
                </Button>
                <Button size="sm" variant="outline" leftIcon={<ExternalLink className="w-4 h-4" />}
                  onClick={() => window.open(payLink(paying.paymentLinkToken!), '_blank', 'noopener')}>
                  Open pay page
                </Button>
              </div>
              {paying.payViban && (
                <div>
                  <p className="text-caption text-neutral-500 dark:text-neutral-400">Or pay by bank transfer to this invoice's IBAN (matched automatically)</p>
                  <p className="text-body-sm font-mono">{paying.payViban}</p>
                </div>
              )}
            </div>
          </div>
        )}
        {emails && (
          <div className="mt-5 pt-4 border-t border-neutral-200 dark:border-neutral-700">
            <p className="text-caption text-neutral-500 dark:text-neutral-400 mb-2">
              {emails.length === 0 ? 'No email was sent for this request.' : 'The email the customer got (simulated: kept in the Outbox, not delivered)'}
            </p>
            {emails[0] && <OutboxEmailView email={emails[0]} />}
          </div>
        )}
      </Modal>

      <Modal
        isOpen={!!returning}
        onClose={() => setReturning(null)}
        title={returning?.status === 'SENT' ? 'Record a rejected direct debit' : 'Record a returned direct debit'}
        subtitle={returning ? `${returning.receivableNumber} · ${returning.amount != null && returning.currency ? formatCurrency(returning.amount, returning.currency) : ''}` : undefined}
        footer={
          <>
            <Button variant="ghost" onClick={() => setReturning(null)}>Cancel</Button>
            <Button variant="danger" onClick={recordReturn} loading={busy}>{returning?.status === 'SENT' ? 'Mark rejected' : 'Mark returned'}</Button>
          </>
        }
      >
        <div className="space-y-3">
          <Select id="return-reason" label="Reason from the payer's bank" value={reason} onChange={(e) => setReason(e.target.value)}
            options={Object.entries(reasons).map(([code, text]) => ({ value: code, label: `${code} · ${text}` }))} />
          <p className="text-caption text-neutral-500 dark:text-neutral-400">
            {returning?.status === 'SENT'
              ? 'No money has moved yet. The customer is sent a Request to Pay instead.'
              : 'The money goes back to the customer (the collection fee is not refunded), the invoice reopens and the customer is sent a Request to Pay.'}
            {' '}Reasons that mean the mandate is unusable (for example AC04, MD01) suspend it.
          </p>
        </div>
      </Modal>
    </div>
  );
};

export default CollectionRunsTab;
