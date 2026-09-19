import React, { useState, useEffect } from 'react';
import { Loader2, GitMerge } from 'lucide-react';
import { Button } from '../ui';
import { Modal } from '../ui/enhanced';
import { formatCurrency } from '../../utils';
import { nettingApi, type NettingCycle } from '../../services/api';

interface NettingCyclePickerItem {
  id: string;
  label: string;
  amount: number;
  currencyCode: string;
}

interface NettingCyclePickerModalProps {
  isOpen: boolean;
  onClose: () => void;
  items: NettingCyclePickerItem[];
  onConfirm: (cycleId: string) => Promise<void>;
}

// Cycles still accepting entries -- matches the backend's own check
// (ReceivableNettingService/PayableNettingService reject anything else).
const OPEN_STATUSES = new Set(['DRAFT', 'OPEN']);

export const NettingCyclePickerModal: React.FC<NettingCyclePickerModalProps> = ({
  isOpen, onClose, items, onConfirm,
}) => {
  const [cycles, setCycles] = useState<NettingCycle[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedCycleId, setSelectedCycleId] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!isOpen) return;
    setSelectedCycleId('');
    setError('');
    setLoading(true);
    nettingApi.getAllCycles()
      .then(res => {
        const all = Array.isArray(res.data) ? res.data : [];
        setCycles(all.filter(c => OPEN_STATUSES.has(c.status)));
      })
      .catch(err => setError(err.message || 'Failed to load netting cycles'))
      .finally(() => setLoading(false));
  }, [isOpen]);

  const totalAmount = items.reduce((s, i) => s + i.amount, 0);
  const currencyCode = items[0]?.currencyCode || 'AED';

  const handleConfirm = async () => {
    if (!selectedCycleId) return;
    setSubmitting(true);
    setError('');
    try {
      await onConfirm(selectedCycleId);
      onClose();
    } catch (err: any) {
      setError(err.message || 'Failed to add to netting cycle');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="Add to Netting Cycle" size="md">
      <div className="space-y-4">
        <div className="bg-neutral-50 rounded-lg p-4 dark:bg-primary-950">
          <div className="space-y-1 max-h-32 overflow-y-auto">
            {items.map(item => (
              <div key={item.id} className="flex justify-between text-body-sm">
                <span className="font-mono">{item.label}</span>
                <span className="font-semibold">{formatCurrency(item.amount, item.currencyCode)}</span>
              </div>
            ))}
          </div>
          {items.length > 1 && (
            <div className="border-t border-neutral-200 mt-2 pt-2 flex justify-between text-body-sm font-semibold dark:border-primary-800">
              <span>Total</span>
              <span>{formatCurrency(totalAmount, currencyCode)}</span>
            </div>
          )}
        </div>

        <div>
          <label className="field-label block mb-2">Netting Cycle</label>
          {loading ? (
            <div className="flex items-center gap-2 body-sm py-2">
              <Loader2 className="w-4 h-4 animate-spin" /> Loading cycles...
            </div>
          ) : cycles.length === 0 ? (
            <p className="body-sm">
              No open netting cycles available. Create one from Netting Cycles first.
            </p>
          ) : (
            <select
              value={selectedCycleId}
              onChange={(e) => setSelectedCycleId(e.target.value)}
              className="w-full px-3 py-2 border border-neutral-300 rounded-lg focus:ring-2 focus:ring-cat-1 focus:border-cat-1 dark:border-primary-700 dark:bg-primary-900"
            >
              <option value="">Select cycle...</option>
              {cycles.map(cycle => (
                <option key={cycle.id} value={cycle.id}>
                  {cycle.cycleReference} - {cycle.cycleName} ({cycle.baseCurrency}, {cycle.status})
                </option>
              ))}
            </select>
          )}
        </div>

        {error && <p className="text-body-sm text-error-600 dark:text-error-300">{error}</p>}

        <div className="flex justify-end gap-3 pt-4 border-t">
          <Button variant="outline" onClick={onClose} disabled={submitting}>
            Cancel
          </Button>
          <Button
            variant="primary"
            onClick={handleConfirm}
            disabled={!selectedCycleId || submitting}
            leftIcon={submitting ? <Loader2 className="w-4 h-4 animate-spin" /> : <GitMerge className="w-4 h-4" />}
          >
            {submitting ? 'Adding...' : 'Add to Cycle'}
          </Button>
        </div>
      </div>
    </Modal>
  );
};
