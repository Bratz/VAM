import React, { useEffect, useState } from 'react';
import { Download, Send } from 'lucide-react';
import { Badge, Button, DataTable } from '../ui';
import { Modal } from '../ui/enhanced';
import { collectionsApi, CollectionLine, CollectionMethod, CollectionPreview, LineStatus, RunDetail } from '../../services/collectionsApi';
import { errorText } from '../../pages/fx/fxFormat';
import { formatCurrency, formatDate } from '../../utils';

const METHOD: Record<CollectionMethod, { label: string; variant: 'success' | 'info' | 'neutral' }> = {
  DIRECT_DEBIT: { label: 'Direct debit', variant: 'success' },
  REQUEST_TO_PAY: { label: 'Request to Pay', variant: 'info' },
  EXCLUDED: { label: 'Left out', variant: 'neutral' },
};

const STATUS: Record<LineStatus, { label: string; variant: 'success' | 'info' | 'neutral' | 'warning' | 'error' }> = {
  SENT: { label: 'Debit sent', variant: 'info' },
  SETTLED: { label: 'Collected, held', variant: 'warning' },
  AVAILABLE: { label: 'Collected', variant: 'success' },
  RTP_SENT: { label: 'Request sent', variant: 'info' },
  EXCLUDED: { label: 'Left out', variant: 'neutral' },
  FAILED: { label: 'Failed', variant: 'error' },
};

type Row = CollectionLine & { status?: LineStatus };

/**
 * How the selected invoices would be collected (mandate first, else Request to Pay, else left out), and sending it:
 * direct debits go into the run's pain.008, the rest get a Request to Pay.
 */
export const CollectionPlanModal: React.FC<{
  isOpen: boolean; onClose: () => void; corporateId: string; receivableIds: string[]; onSent?: () => void;
}> = ({ isOpen, onClose, corporateId, receivableIds, onSent }) => {
  const [plan, setPlan] = useState<CollectionPreview | null>(null);
  const [sent, setSent] = useState<RunDetail | null>(null);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!isOpen) return;
    let live = true;
    setPlan(null);
    setSent(null);
    setError(null);
    collectionsApi.preview(corporateId, receivableIds)
      .then(p => { if (live) setPlan(p); })
      .catch(err => { if (live) setError(errorText(err)); });
    return () => { live = false; };
  }, [isOpen, corporateId, receivableIds]);

  const send = async () => {
    setSending(true);
    setError(null);
    try {
      setSent(await collectionsApi.submit(corporateId, receivableIds));
      onSent?.();
    } catch (err) {
      setError(errorText(err));
    } finally {
      setSending(false);
    }
  };

  const download = async () => {
    if (!sent) return;
    try {
      await collectionsApi.downloadPain008(sent.run.id, sent.run.runRef);
    } catch (err) {
      setError(errorText(err));
    }
  };

  const rows: Row[] | undefined = sent ? sent.lines : plan?.lines;
  const totals = sent ? sent.run.totals : plan?.totals;
  const collectable = !!plan?.lines.some(l => l.method !== 'EXCLUDED');

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title={sent ? `Collection run ${sent.run.runRef}` : 'Collection plan'}
      subtitle={sent ? 'Sent. Direct debits settle on their dates and are held for the return window.' : 'How these invoices would be collected. Nothing is sent until you send it.'}
      size="xl"
      footer={sent ? (
        <>
          {sent.run.hasPain008 && <Button variant="outline" onClick={download} leftIcon={<Download className="w-4 h-4" />}>Download pain.008</Button>}
          <Button onClick={onClose}>Done</Button>
        </>
      ) : (
        <>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button onClick={send} loading={sending} disabled={!collectable} leftIcon={<Send className="w-4 h-4" />}>Send collections</Button>
        </>
      )}
    >
      {error && <p className="mb-3 text-body-sm text-error-700 dark:text-error-300">{error}</p>}
      {rows && totals && (
        <div className="space-y-4">
          <div className="flex flex-wrap gap-2">
            {totals.map(t => (
              <div key={t.method + t.currency} className="rounded-md border border-neutral-200 dark:border-neutral-700 px-3 py-2">
                <p className="text-caption text-neutral-500 dark:text-neutral-400">{METHOD[t.method].label} · {t.count}</p>
                <p className="body-strong tabular-nums">{formatCurrency(t.amount, t.currency)}</p>
              </div>
            ))}
          </div>
          <DataTable
            data={rows}
            keyExtractor={(l) => l.receivableId}
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
                key: 'amount', header: 'Outstanding', align: 'right',
                render: (_, l) => <span className="text-body-sm tabular-nums">{l.amount != null && l.currency ? formatCurrency(l.amount, l.currency) : '—'}</span>,
              },
              {
                key: 'method', header: sent ? 'Status' : 'How',
                render: (_, l) => l.status
                  ? <Badge variant={STATUS[l.status].variant} size="sm">{STATUS[l.status].label}</Badge>
                  : <Badge variant={METHOD[l.method].variant} size="sm">{METHOD[l.method].label}</Badge>,
              },
              {
                key: 'collectionDate', header: 'Collects on',
                render: (_, l) => <span className="text-body-sm">{l.collectionDate ? formatDate(l.collectionDate) : l.method === 'REQUEST_TO_PAY' ? 'When the customer pays' : '—'}</span>,
              },
              {
                key: 'mandateReference', header: 'Mandate / reason',
                render: (_, l) => l.mandateReference
                  ? <span className="text-body-sm font-mono whitespace-nowrap">{l.mandateReference} · {l.sequenceType}</span>
                  : <span className="text-body-sm text-neutral-500 dark:text-neutral-400">{l.reason}</span>,
              },
            ]}
          />
        </div>
      )}
    </Modal>
  );
};

export default CollectionPlanModal;
