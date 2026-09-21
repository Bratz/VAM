import React, { useCallback, useEffect, useState } from 'react';
import toast from 'react-hot-toast';
import { Hash, Loader2, Plus } from 'lucide-react';
import { Modal } from '../ui/enhanced';
import { Badge, Button, Select } from '../ui';
import { vibanApi, Viban, VibanPool } from '../../services/api';

/**
 * The VIBANs on one virtual account, and the two ways to give it another:
 * mint a fresh one, or take one from the program's pool.
 *
 * A VIBAN always belongs to a program and routes payments to one account, so
 * the account is where "give this a VIBAN" naturally lives. The first one
 * becomes the account's primary (stamped onto the VA); later ones are CUSTOMER.
 */
export const VaVibanModal: React.FC<{
  account: { id: string; vaNumber: string; vaName: string; programId?: string };
  isOpen: boolean;
  onClose: () => void;
}> = ({ account, isOpen, onClose }) => {
  const [vibans, setVibans] = useState<Viban[]>([]);
  const [pools, setPools] = useState<VibanPool[]>([]);
  const [poolId, setPoolId] = useState('');
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [list, poolRes] = await Promise.all([
        vibanApi.getForVa(account.id),
        account.programId ? vibanApi.getPools(account.programId) : Promise.resolve(null),
      ]);
      setVibans(Array.isArray(list) ? list : []);
      const available = (poolRes?.data ?? []).filter(p => p.status === 'ACTIVE' && p.availableCount > 0);
      setPools(available);
      setPoolId(available[0]?.id ?? '');
    } catch {
      toast.error('Could not load VIBANs');
    } finally {
      setLoading(false);
    }
  }, [account.id, account.programId]);

  useEffect(() => { if (isOpen) load(); }, [isOpen, load]);

  const hasPrimary = vibans.some(v => v.isPrimary);

  const run = async (action: () => Promise<unknown>, done: string) => {
    setBusy(true);
    try {
      await action();
      toast.success(done);
      await load();
    } catch (e: any) {
      toast.error(e?.response?.data?.message || e?.message || 'Request failed');
    } finally {
      setBusy(false);
    }
  };

  const generate = () => run(() => vibanApi.createForVa(account.id, hasPrimary ? 'CUSTOMER' : 'PRIMARY'), 'VIBAN generated');
  const assign = () => run(
    () => vibanApi.assignFromPool(poolId, { virtualAccountId: account.id, isPrimary: !hasPrimary }),
    'VIBAN assigned from pool');

  return (
    <Modal isOpen={isOpen} onClose={onClose} size="md" title={`VIBANs — ${account.vaName}`} subtitle={account.vaNumber}>
      <div className="space-y-4">
        {loading ? (
          <div className="flex justify-center py-6"><Loader2 className="w-5 h-5 animate-spin text-neutral-400" /></div>
        ) : vibans.length === 0 ? (
          <p className="body-sm">No VIBANs on this account yet. The first one becomes its primary.</p>
        ) : (
          <ul className="divide-y divide-edge border border-edge rounded-lg">
            {vibans.map(v => (
              <li key={v.id} className="flex items-center justify-between px-3 py-2">
                <span className="font-mono text-body-sm flex items-center gap-2"><Hash className="w-3.5 h-3.5 text-neutral-400" />{v.viban}</span>
                <span className="flex gap-1">
                  {v.isPrimary && <Badge variant="success" size="sm">Primary</Badge>}
                  <Badge variant="neutral" size="sm">{v.vibanType ?? '—'}</Badge>
                  <Badge variant={v.status === 'ACTIVE' ? 'info' : 'neutral'} size="sm">{v.status}</Badge>
                </span>
              </li>
            ))}
          </ul>
        )}

        <div className="border-t border-edge pt-4 space-y-3">
          <Button onClick={generate} disabled={busy || loading} leftIcon={<Plus className="w-4 h-4" />}>
            Generate {hasPrimary ? 'additional' : 'primary'} VIBAN
          </Button>
          {pools.length > 0 && (
            <div className="flex items-end gap-2">
              <div className="flex-1">
                <label className="field-label block mb-1">Or assign from a pool</label>
                <Select value={poolId} onChange={e => setPoolId(e.target.value)} aria-label="VIBAN pool">
                  {pools.map(p => <option key={p.id} value={p.id}>{p.poolName} ({p.availableCount} available)</option>)}
                </Select>
              </div>
              <Button variant="outline" onClick={assign} disabled={busy || !poolId}>Assign</Button>
            </div>
          )}
        </div>
      </div>
    </Modal>
  );
};
