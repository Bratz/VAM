// The corporate's authority for the home bank to instruct its account at another bank (spec
// docs/specs/external-bank-sweeps.md R1, R2, R18). Without an ACTIVE one the account cannot be swept.

import React, { useCallback, useEffect, useState } from 'react';
import { Ban, CheckCircle, Pause, Plus } from 'lucide-react';
import { Badge, Button, Input, Select } from '../ui';
import { formatCurrency, formatDate } from '../../utils';
import {
  externalAuthorityApi,
  type AuthorityStatus,
  type ExternalAuthority,
  type InstructionFormat,
} from '../../services/api';

const STATUS: Record<AuthorityStatus, { label: string; variant: 'success' | 'warning' | 'neutral' | 'error' }> = {
  PENDING_ACTIVATION: { label: 'Pending activation', variant: 'warning' },
  ACTIVE: { label: 'Active', variant: 'success' },
  SUSPENDED: { label: 'Suspended', variant: 'warning' },
  EXPIRED: { label: 'Expired', variant: 'neutral' },
  REVOKED: { label: 'Revoked', variant: 'error' },
};

const FORMAT: Record<InstructionFormat, string> = { MT101: 'MT101', PAIN_001: 'pain.001' };

/** A yyyy-MM-dd date in local time (a bare date string would parse as UTC midnight). */
const day = (d?: string) => (d ? formatDate(`${d}T00:00:00`) : null);

const apiError = (e: unknown, fallback: string) =>
  (e as { response?: { data?: { message?: string } } })?.response?.data?.message ?? fallback;

const EMPTY = { mandateReference: '', instructionFormat: 'MT101' as InstructionFormat, validFrom: '', validTo: '', maxPerTransaction: '', maxDaily: '', documentRef: '' };

/**
 * ponytail: register / activate / suspend / revoke are shown to everyone — there is no role model yet (SecurityConfig
 * permits all). Spec D3 makes them bank operations' only; hide the actions for corporate users when sign-in arrives.
 */
export const ExternalAuthorityPanel: React.FC<{ shadowVaId: string; currency: string }> = ({ shadowVaId, currency }) => {
  const [list, setList] = useState<ExternalAuthority[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [form, setForm] = useState<typeof EMPTY | null>(null);
  const [revoking, setRevoking] = useState<{ id: string; reason: string } | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    externalAuthorityApi.forShadow(shadowVaId).then(setList).catch((e) => setError(apiError(e, 'Could not load the authorities')));
  }, [shadowVaId]);

  useEffect(() => { setList(null); setError(null); load(); }, [load]);

  const run = async (action: () => Promise<unknown>, done?: () => void) => {
    setBusy(true);
    setError(null);
    try {
      await action();
      done?.();
      load();
    } catch (e) {
      setError(apiError(e, 'The change was not saved'));
    } finally {
      setBusy(false);
    }
  };

  const register = () => form && run(() => externalAuthorityApi.register({
    shadowVaId,
    mandateReference: form.mandateReference,
    instructionFormat: form.instructionFormat,
    validFrom: form.validFrom || undefined,
    validTo: form.validTo || undefined,
    maxPerTransaction: form.maxPerTransaction ? Number(form.maxPerTransaction) : undefined,
    maxDaily: form.maxDaily ? Number(form.maxDaily) : undefined,
    documentRef: form.documentRef || undefined,
  }), () => setForm(null));

  const set = (k: keyof typeof EMPTY) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setForm((f) => (f ? { ...f, [k]: e.target.value } : f));

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between gap-2">
        <div>
          <p className="label">Authority to instruct</p>
          <p className="caption">The corporate's authority for the home bank to sweep this account. Registered and changed by bank operations.</p>
        </div>
        {!form && (
          <Button size="xs" variant="outline" leftIcon={<Plus className="w-3 h-3" />} onClick={() => setForm(EMPTY)}>Register</Button>
        )}
      </div>

      {error && <p className="caption-error">{error}</p>}

      {form && (
        <div className="rounded-lg border border-edge-subtle p-3 space-y-2">
          <div className="grid grid-cols-2 gap-2">
            <Input inputSize="sm" placeholder="Authority reference" aria-label="Authority reference" value={form.mandateReference} onChange={set('mandateReference')} maxLength={60} />
            <Select selectSize="sm" aria-label="Instruction format" value={form.instructionFormat} onChange={set('instructionFormat')}
              options={[{ value: 'MT101', label: 'MT101' }, { value: 'PAIN_001', label: 'pain.001' }]} />
            <label className="caption block space-y-1">Valid from<Input inputSize="sm" type="date" value={form.validFrom} onChange={set('validFrom')} /></label>
            <label className="caption block space-y-1">Valid to (empty: open)<Input inputSize="sm" type="date" value={form.validTo} onChange={set('validTo')} /></label>
            <Input inputSize="sm" type="number" min="0" step="0.01" placeholder={`Max per transaction (${currency})`} aria-label="Max per transaction" value={form.maxPerTransaction} onChange={set('maxPerTransaction')} />
            <Input inputSize="sm" type="number" min="0" step="0.01" placeholder={`Max per day (${currency})`} aria-label="Max per day" value={form.maxDaily} onChange={set('maxDaily')} />
          </div>
          <Input inputSize="sm" placeholder="Signed document reference" aria-label="Signed document reference" value={form.documentRef} onChange={set('documentRef')} maxLength={500} />
          <div className="flex justify-end gap-2">
            <Button size="xs" variant="ghost" onClick={() => setForm(null)}>Cancel</Button>
            <Button size="xs" loading={busy} disabled={!form.mandateReference.trim()} onClick={register}>Register</Button>
          </div>
        </div>
      )}

      {list && list.length === 0 && !form && (
        <p className="caption">No authority registered: this account cannot be a sweep source.</p>
      )}

      {list?.map((a) => {
        const limits = [
          a.maxPerTransaction != null && `${formatCurrency(a.maxPerTransaction, currency)} per transaction`,
          a.maxDaily != null && `${formatCurrency(a.maxDaily, currency)} per day`,
        ].filter(Boolean).join(' · ') || 'No limits';
        const validity = day(a.validFrom) || day(a.validTo) ? `${day(a.validFrom) ?? '…'} – ${day(a.validTo) ?? 'open'}` : 'No end date';
        const final = a.status === 'REVOKED' || a.status === 'EXPIRED';
        return (
          <div key={a.id} className="rounded-lg border border-edge-subtle px-3 py-2 space-y-1">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="flex items-center gap-1.5 min-w-0">
                <span className="font-mono text-caption text-primary-900 dark:text-neutral-50 truncate">{a.mandateReference}</span>
                <Badge variant="info" size="xs">{FORMAT[a.instructionFormat]}</Badge>
                <Badge variant={STATUS[a.status].variant} size="xs">{STATUS[a.status].label}</Badge>
              </div>
              {!final && (
                <div className="flex items-center gap-1">
                  {(a.status === 'PENDING_ACTIVATION' || a.status === 'SUSPENDED') && (
                    <Button size="xs" variant="ghost" disabled={busy} leftIcon={<CheckCircle className="w-3 h-3" />} onClick={() => run(() => externalAuthorityApi.activate(a.id))}>Activate</Button>
                  )}
                  {a.status === 'ACTIVE' && (
                    <Button size="xs" variant="ghost" disabled={busy} leftIcon={<Pause className="w-3 h-3" />} onClick={() => run(() => externalAuthorityApi.suspend(a.id))}>Suspend</Button>
                  )}
                  <Button size="xs" variant="ghost" disabled={busy} leftIcon={<Ban className="w-3 h-3" />} onClick={() => setRevoking({ id: a.id, reason: '' })}>Revoke</Button>
                </div>
              )}
            </div>
            <p className="caption">{validity} · {limits}{a.documentRef ? ` · document ${a.documentRef}` : ''}</p>
            {a.status === 'REVOKED' && a.revocationReason && <p className="caption">Revoked: {a.revocationReason}</p>}
            {revoking?.id === a.id && (
              <div className="flex items-center gap-2 pt-1">
                <Input inputSize="sm" placeholder="Reason (required, final)" aria-label="Revocation reason" value={revoking.reason} maxLength={200}
                  onChange={(e) => setRevoking({ id: a.id, reason: e.target.value })} />
                <Button size="xs" variant="ghost" onClick={() => setRevoking(null)}>Cancel</Button>
                <Button size="xs" variant="danger" loading={busy} disabled={!revoking.reason.trim()}
                  onClick={() => run(() => externalAuthorityApi.revoke(a.id, revoking.reason), () => setRevoking(null))}>Revoke</Button>
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
};
