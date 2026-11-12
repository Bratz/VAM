import React, { useEffect, useMemo, useState } from 'react';
import { ArrowRight, Loader2, Info } from 'lucide-react';
import { Button, Input, Select } from '../ui';
import { Modal } from '../ui/enhanced';
import { CurrencyPicker } from '../ui/CurrencyPicker';
import { cn } from '../../utils';
import {
  CreatePoolRequest, UpdatePoolRequest, NotionalPool, AddMemberRequest, LegalEntity, legalEntityApi,
} from '../../services/api';
import toast from 'react-hot-toast';
import { AccountSelector } from './AccountSelector';

// Same list as InterestConfigurationService.defaultDayCount: money-market ACT/365 currencies, the rest ACT/360.
const ACT_365 = ['GBP', 'AED', 'SAR', 'QAR', 'KWD', 'BHD', 'OMR', 'SGD', 'INR', 'HKD', 'AUD', 'NZD', 'ZAR'];
const defaultDayCount = (ccy: string) => (ACT_365.includes(ccy) ? 'ACT/365' : 'ACT/360');
const DAY_COUNTS = ['ACT/360', 'ACT/365', '30/360', 'ACT/ACT'].map(v => ({ value: v, label: v }));

const num = (s: string) => (s.trim() === '' ? undefined : parseFloat(s));
const str = (n?: number | null) => (n == null ? '' : String(n));

// ============================================================================
// CREATE / EDIT POOL MODAL
// Create: details, entity, currency, rates and floor; then members (filtered by entity + currency + home bank),
// the header among them and their debit limits. Edit (pool given): the same minus entity and currency, which are
// fixed; members are the pool's own.
// ============================================================================
export interface CreatePoolModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSave: (data: CreatePoolRequest) => Promise<NotionalPool>;
  corporateId?: string;
  programId?: string;
  /** Edit this pool instead of creating one. */
  pool?: NotionalPool;
  onUpdate?: (poolId: string, data: UpdatePoolRequest) => Promise<NotionalPool>;
}

const emptyForm = () => ({
  poolName: '',
  legalEntityId: '',
  poolCurrency: 'AED',
  targetBalance: '',
  creditBaseRate: '3.5',
  creditSpread: '0',
  debitBaseRate: '3.5',
  debitSpread: '2',
  dayCountConvention: defaultDayCount('AED'),
  poolFloor: '',
  effectiveFrom: new Date().toISOString().split('T')[0],
  effectiveTo: '',
});

export const CreatePoolModal: React.FC<CreatePoolModalProps> = ({
  isOpen, onClose, onSave, corporateId, programId, pool, onUpdate,
}) => {
  const editing = !!pool;
  const [saving, setSaving] = useState(false);
  const [step, setStep] = useState<1 | 2>(1);
  const [entities, setEntities] = useState<LegalEntity[]>([]);
  const [selectedMembers, setSelectedMembers] = useState<AddMemberRequest[]>([]);
  const [headerAccountId, setHeaderAccountId] = useState('');
  // Keyed by account id when creating, by member id when editing.
  const [debitLimits, setDebitLimits] = useState<Record<string, string>>({});
  const [formData, setFormData] = useState(emptyForm);

  const poolMembers = useMemo(() => (pool?.members || []).filter(m => m.status === 'ACTIVE'), [pool]);

  useEffect(() => {
    if (!isOpen) return;
    legalEntityApi.getAll(corporateId || undefined)
      .then(r => setEntities(Array.isArray(r?.data) ? r.data : []))
      .catch(() => setEntities([]));
  }, [isOpen, corporateId]);

  useEffect(() => {
    if (!isOpen || !pool) return;
    const r = pool.rates || {};
    setFormData({
      poolName: pool.poolName,
      legalEntityId: pool.legalEntityId || '',
      poolCurrency: pool.poolCurrency,
      targetBalance: str(pool.targetBalance),
      creditBaseRate: str(r.creditBaseRate),
      creditSpread: str(r.creditSpread),
      debitBaseRate: str(r.debitBaseRate),
      debitSpread: str(r.debitSpread),
      dayCountConvention: r.dayCountConvention || defaultDayCount(pool.poolCurrency),
      poolFloor: str(pool.poolFloor),
      effectiveFrom: pool.effectiveFrom || '',
      effectiveTo: pool.effectiveTo || '',
    });
    setHeaderAccountId(pool.headerAccountId || '');
    setDebitLimits(Object.fromEntries((pool.members || []).map(m => [m.id, str(m.debitLimit)])));
    setStep(1);
  }, [isOpen, pool]);

  const resetForm = () => {
    setFormData(emptyForm());
    setSelectedMembers([]);
    setHeaderAccountId('');
    setDebitLimits({});
    setStep(1);
  };

  const close = () => { onClose(); if (!editing) resetForm(); };

  // Members chosen for another entity or currency no longer fit: start the selection again.
  const changeScope = (patch: Partial<ReturnType<typeof emptyForm>>) => {
    setFormData(f => ({ ...f, ...patch }));
    setSelectedMembers([]);
    setHeaderAccountId('');
  };

  const selectMembers = (members: AddMemberRequest[]) => {
    setSelectedMembers(members);
    if (!members.some(m => m.accountId === headerAccountId)) setHeaderAccountId(members[0]?.accountId || '');
  };

  const rates = () => ({
    creditBaseRate: num(formData.creditBaseRate),
    creditSpread: num(formData.creditSpread),
    debitBaseRate: num(formData.debitBaseRate),
    debitSpread: num(formData.debitSpread),
    dayCountConvention: formData.dayCountConvention,
  });

  const handleSubmit = async () => {
    setSaving(true);
    try {
      if (pool && onUpdate) {
        await onUpdate(pool.id, {
          poolName: formData.poolName,
          targetBalance: num(formData.targetBalance),
          headerAccountId: headerAccountId || undefined,
          poolFloor: num(formData.poolFloor) ?? null,
          rates: rates(),
          memberDebitLimits: Object.fromEntries(poolMembers.map(m => [m.id, num(debitLimits[m.id] || '') ?? null])),
        });
        toast.success('Pool updated');
        onClose();
        return;
      }
      await onSave({
        poolName: formData.poolName,
        poolCurrency: formData.poolCurrency,
        corporateId: corporateId || undefined,
        programId: programId || undefined,
        legalEntityId: formData.legalEntityId,
        headerAccountId,
        poolFloor: num(formData.poolFloor),
        rates: rates(),
        targetBalance: num(formData.targetBalance),
        effectiveFrom: formData.effectiveFrom || undefined,
        effectiveTo: formData.effectiveTo || undefined,
        members: selectedMembers.map(m => ({ ...m, debitLimit: num(debitLimits[m.accountId] || '') })),
      });
      toast.success('Pool created successfully');
      onClose();
      resetForm();
    } catch (err: any) {
      toast.error(err.message || (editing ? 'Failed to update pool' : 'Failed to create pool'));
    } finally {
      setSaving(false);
    }
  };

  const canProceedToStep2 = formData.poolName && (editing || formData.legalEntityId) && formData.creditBaseRate;
  // Header + limits rows: the chosen accounts when creating, the pool's members when editing.
  const rows = editing
    ? poolMembers.map(m => ({ key: m.id, accountId: m.accountId, label: `${m.accountNumber} — ${m.entityName || m.entityCode}` }))
    : selectedMembers.map(m => ({ key: m.accountId, accountId: m.accountId, label: `${m.accountNumber} — ${m.entityName}` }));
  const canSubmit = rows.length > 0 && !!headerAccountId && !saving;

  const set = (patch: Partial<ReturnType<typeof emptyForm>>) => setFormData(f => ({ ...f, ...patch }));
  const field = (label: string, key: keyof ReturnType<typeof emptyForm>, placeholder?: string) => (
    <div>
      <label className="field-label block mb-1">{label}</label>
      <Input type="number" step="0.01" placeholder={placeholder} value={formData[key]}
             onChange={(e) => set({ [key]: e.target.value })} />
    </div>
  );

  return (
    <Modal isOpen={isOpen} onClose={close} title={editing ? `Edit ${pool!.poolName}` : 'Create Notional Pool'} size="lg">
      {/* Step Indicator */}
      <div className="flex items-center gap-2 mb-6">
        <div className={cn(
          'flex items-center gap-2 px-3 py-1.5 rounded-full text-body-sm font-medium',
          step === 1 ? 'bg-primary-100 dark:bg-primary-700 text-primary-700 dark:text-neutral-200' : 'bg-surface-muted text-neutral-500 dark:text-neutral-400'
        )}>
          <span className="w-5 h-5 rounded-full bg-current/20 flex items-center justify-center text-caption">1</span>
          Pool Details
        </div>
        <ArrowRight className="w-4 h-4 text-neutral-300 dark:text-neutral-400" />
        <div className={cn(
          'flex items-center gap-2 px-3 py-1.5 rounded-full text-body-sm font-medium',
          step === 2 ? 'bg-primary-100 dark:bg-primary-700 text-primary-700 dark:text-neutral-200' : 'bg-surface-muted text-neutral-500 dark:text-neutral-400'
        )}>
          <span className="w-5 h-5 rounded-full bg-current/20 flex items-center justify-center text-caption">2</span>
          {editing ? 'Header & Limits' : 'Members & Header'}
        </div>
      </div>

      {step === 1 ? (
        <div className="space-y-4">
          <div>
            <label className="field-label block mb-1">
              Pool Name <span className="text-error-500 dark:text-error-300">*</span>
            </label>
            <Input
              placeholder="e.g., UAE Regional Pool"
              value={formData.poolName}
              onChange={(e) => set({ poolName: e.target.value })}
            />
          </div>

          {/* One legal entity and one currency per pool (spec D8, D1); fixed once created. */}
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="field-label block mb-1">
                Legal Entity <span className="text-error-500 dark:text-error-300">*</span>
              </label>
              <Select
                value={formData.legalEntityId}
                disabled={editing}
                placeholder="Select the pool's entity"
                onChange={(e) => changeScope({ legalEntityId: e.target.value })}
                options={entities.map(e => ({ value: e.id, label: `${e.entityCode} — ${e.entityName}` }))}
              />
            </div>
            <div>
              <label className="field-label block mb-1">Currency</label>
              <CurrencyPicker
                value={formData.poolCurrency}
                disabled={editing}
                onChange={(c) => changeScope({ poolCurrency: c, dayCountConvention: defaultDayCount(c) })}
                withName
              />
            </div>
          </div>

          {/* Rates (interest engine): effective = base + spread. */}
          <div className="grid grid-cols-2 gap-4">
            {field('Credit Base Rate (%) *', 'creditBaseRate')}
            {field('Credit Spread (%)', 'creditSpread')}
            {field('Debit Base Rate (%)', 'debitBaseRate', 'Same as credit')}
            {field('Debit Spread (%)', 'debitSpread')}
          </div>
          {pool?.rates?.systemDefault && (
            <p className="caption-info">These rates are a default made from the pool's old rate (debit = credit + 2%); saving makes them yours.</p>
          )}

          <div className="grid grid-cols-3 gap-4">
            <div>
              <label className="field-label block mb-1">Day Count</label>
              <Select
                value={formData.dayCountConvention}
                onChange={(e) => set({ dayCountConvention: e.target.value })}
                options={DAY_COUNTS}
              />
            </div>
            {field(`Pool Debit Floor (${formData.poolCurrency})`, 'poolFloor', 'No floor')}
            {field('Target Balance', 'targetBalance', 'Optional')}
          </div>

          {!editing && (
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="field-label block mb-1">Effective From</label>
                <Input type="date" value={formData.effectiveFrom} onChange={(e) => set({ effectiveFrom: e.target.value })} />
              </div>
              <div>
                <label className="field-label block mb-1">Effective To</label>
                <Input type="date" value={formData.effectiveTo} onChange={(e) => set({ effectiveTo: e.target.value })} />
              </div>
            </div>
          )}
        </div>
      ) : (
        <div className="space-y-4">
          {!editing && (
            <>
              <div className="bg-info-50 dark:bg-info-500/10 rounded-lg p-3 flex items-start gap-2">
                <Info className="w-4 h-4 text-info-600 dark:text-info-300 mt-0.5" />
                <div>
                  <p className="text-body-sm font-medium text-info-800 dark:text-info-300">Select Pool Members</p>
                  <p className="caption-info">
                    Home-bank {formData.poolCurrency} accounts of the chosen legal entity. The header is one of them.
                  </p>
                </div>
              </div>
              <AccountSelector
                selectedAccounts={selectedMembers}
                onSelect={selectMembers}
                currency={formData.poolCurrency}
                corporateId={corporateId}
                entityId={formData.legalEntityId}
              />
            </>
          )}

          {rows.length > 0 && (
            <div className="space-y-3">
              <div>
                <label className="field-label block mb-1">
                  Header Account <span className="text-error-500 dark:text-error-300">*</span>
                </label>
                <Select
                  value={headerAccountId}
                  onChange={(e) => setHeaderAccountId(e.target.value)}
                  options={rows.map(r => ({ value: r.accountId, label: r.label }))}
                />
              </div>
              <div>
                <p className="field-label mb-1">Member Debit Limits ({formData.poolCurrency})</p>
                <div className="max-h-48 overflow-y-auto space-y-2">
                  {rows.map(r => (
                    <div key={r.key} className="grid grid-cols-2 gap-3 items-center">
                      <span className="text-body-sm font-mono truncate">{r.label}</span>
                      <Input
                        type="number"
                        placeholder="No limit"
                        value={debitLimits[r.key] || ''}
                        onChange={(e) => setDebitLimits(d => ({ ...d, [r.key]: e.target.value }))}
                      />
                    </div>
                  ))}
                </div>
              </div>
            </div>
          )}
        </div>
      )}

      {/* Footer */}
      <div className="flex justify-between gap-2 mt-6 pt-4 border-t">
        <Button variant="ghost" onClick={close}>Cancel</Button>
        <div className="flex gap-2">
          {step === 2 && (
            <Button variant="outline" onClick={() => setStep(1)}>Back</Button>
          )}
          {step === 1 ? (
            <Button onClick={() => setStep(2)} disabled={!canProceedToStep2}>
              {editing ? 'Next: Header & Limits' : 'Next: Add Members'}
            </Button>
          ) : (
            <Button onClick={handleSubmit} disabled={!canSubmit}>
              {saving && <Loader2 className="w-4 h-4 animate-spin mr-2" />}
              {editing ? 'Save Pool' : `Create Pool${selectedMembers.length > 0 ? ` (${selectedMembers.length} members)` : ''}`}
            </Button>
          )}
        </div>
      </div>
    </Modal>
  );
};
