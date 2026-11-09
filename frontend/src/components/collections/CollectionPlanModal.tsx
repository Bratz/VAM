import React, { useEffect, useState } from 'react';
import { Badge, Button, DataTable } from '../ui';
import { Modal } from '../ui/enhanced';
import { collectionsApi, CollectionMethod, CollectionPreview } from '../../services/collectionsApi';
import { errorText } from '../../pages/fx/fxFormat';
import { formatCurrency, formatDate } from '../../utils';

const METHOD: Record<CollectionMethod, { label: string; variant: 'success' | 'info' | 'neutral' }> = {
  DIRECT_DEBIT: { label: 'Direct debit', variant: 'success' },
  REQUEST_TO_PAY: { label: 'Request to Pay', variant: 'info' },
  EXCLUDED: { label: 'Left out', variant: 'neutral' },
};

/** How the selected invoices would be collected: mandate first, else Request to Pay, else left out. */
export const CollectionPlanModal: React.FC<{ isOpen: boolean; onClose: () => void; corporateId: string; receivableIds: string[] }> = ({
  isOpen, onClose, corporateId, receivableIds,
}) => {
  const [plan, setPlan] = useState<CollectionPreview | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!isOpen) return;
    let live = true;
    setPlan(null);
    setError(null);
    collectionsApi.preview(corporateId, receivableIds)
      .then(p => { if (live) setPlan(p); })
      .catch(err => { if (live) setError(errorText(err)); });
    return () => { live = false; };
  }, [isOpen, corporateId, receivableIds]);

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="Collection plan" subtitle="How these invoices would be collected. Nothing is sent yet." size="xl"
      footer={<Button variant="ghost" onClick={onClose}>Close</Button>}>
      {error && <p className="text-body-sm text-error-700 dark:text-error-300">{error}</p>}
      {plan && (
        <div className="space-y-4">
          <div className="flex flex-wrap gap-2">
            {plan.totals.map(t => (
              <div key={t.method + t.currency} className="rounded-md border border-neutral-200 dark:border-neutral-700 px-3 py-2">
                <p className="text-caption text-neutral-500 dark:text-neutral-400">{METHOD[t.method].label} · {t.count}</p>
                <p className="body-strong tabular-nums">{formatCurrency(t.amount, t.currency)}</p>
              </div>
            ))}
          </div>
          <DataTable
            data={plan.lines}
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
              { key: 'method', header: 'How', render: (_, l) => <Badge variant={METHOD[l.method].variant} size="sm">{METHOD[l.method].label}</Badge> },
              {
                key: 'collectionDate', header: 'Collects on',
                render: (_, l) => <span className="text-body-sm">{l.collectionDate ? formatDate(l.collectionDate) : l.method === 'REQUEST_TO_PAY' ? 'When the customer pays' : '—'}</span>,
              },
              {
                key: 'mandateReference', header: 'Mandate / reason',
                render: (_, l) => l.mandateReference
                  ? <span className="text-body-sm font-mono">{l.mandateReference} · {l.schemeCode}</span>
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
