import React, { useState } from 'react';
import { ArrowRight, Loader2, Info } from 'lucide-react';
import { Button, Input, Select } from '../ui';
import { Modal } from '../ui/enhanced';
import { CurrencyPicker } from '../ui/CurrencyPicker';
import { cn } from '../../utils';
import { CreatePoolRequest, NotionalPool, AddMemberRequest } from '../../services/api';
import toast from 'react-hot-toast';
import { AccountSelector } from './AccountSelector';

// ============================================================================
// CREATE POOL MODAL - ENHANCED WITH MEMBER SELECTION
// ============================================================================
export interface CreatePoolModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSave: (data: CreatePoolRequest) => Promise<NotionalPool>;
  corporateId?: string;
}

export const CreatePoolModal: React.FC<CreatePoolModalProps> = ({ isOpen, onClose, onSave, corporateId }) => {
  const [saving, setSaving] = useState(false);
  const [step, setStep] = useState<1 | 2>(1);
  const [selectedMembers, setSelectedMembers] = useState<AddMemberRequest[]>([]);
  const [formData, setFormData] = useState({
    poolName: '',
    poolCurrency: 'AED',
    targetBalance: '',
    interestRate: '3.5',
    interestCalculationMethod: 'DAILY_AVERAGE' as const,
    effectiveFrom: new Date().toISOString().split('T')[0],
    effectiveTo: '',
  });

  const resetForm = () => {
    setFormData({
      poolName: '',
      poolCurrency: 'AED',
      targetBalance: '',
      interestRate: '3.5',
      interestCalculationMethod: 'DAILY_AVERAGE',
      effectiveFrom: new Date().toISOString().split('T')[0],
      effectiveTo: '',
    });
    setSelectedMembers([]);
    setStep(1);
  };

  const handleSubmit = async () => {
    setSaving(true);
    try {
      await onSave({
        poolName: formData.poolName,
        poolCurrency: formData.poolCurrency,
        targetBalance: formData.targetBalance ? parseFloat(formData.targetBalance) : undefined,
        interestRate: parseFloat(formData.interestRate),
        interestCalculationMethod: formData.interestCalculationMethod,
        effectiveFrom: formData.effectiveFrom || undefined,
        effectiveTo: formData.effectiveTo || undefined,
        members: selectedMembers.length > 0 ? selectedMembers : undefined,
      });
      toast.success('Pool created successfully');
      onClose();
      resetForm();
    } catch (err: any) {
      toast.error(err.message || 'Failed to create pool');
    } finally {
      setSaving(false);
    }
  };

  const canProceedToStep2 = formData.poolName && formData.interestRate;

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="Create Notional Pool" size="lg">
      {/* Step Indicator */}
      <div className="flex items-center gap-2 mb-6">
        <div className={cn(
          'flex items-center gap-2 px-3 py-1.5 rounded-full text-sm font-medium',
          step === 1 ? 'bg-primary-100 dark:bg-primary-700 text-primary-700 dark:text-neutral-200' : 'bg-neutral-100 dark:bg-primary-800 text-neutral-500 dark:text-neutral-400'
        )}>
          <span className="w-5 h-5 rounded-full bg-current/20 flex items-center justify-center text-xs">1</span>
          Pool Details
        </div>
        <ArrowRight className="w-4 h-4 text-neutral-300 dark:text-neutral-600" />
        <div className={cn(
          'flex items-center gap-2 px-3 py-1.5 rounded-full text-sm font-medium',
          step === 2 ? 'bg-primary-100 dark:bg-primary-700 text-primary-700 dark:text-neutral-200' : 'bg-neutral-100 dark:bg-primary-800 text-neutral-500 dark:text-neutral-400'
        )}>
          <span className="w-5 h-5 rounded-full bg-current/20 flex items-center justify-center text-xs">2</span>
          Add Members
        </div>
      </div>

      {step === 1 ? (
        <div className="space-y-4">
          {/* Pool Name */}
          <div>
            <label className="field-label block mb-1">
              Pool Name <span className="text-error-500 dark:text-error-300">*</span>
            </label>
            <Input
              placeholder="e.g., UAE Regional Pool"
              value={formData.poolName}
              onChange={(e) => setFormData({ ...formData, poolName: e.target.value })}
            />
          </div>

          {/* Currency & Target Balance */}
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="field-label block mb-1">Currency</label>
              <CurrencyPicker
                value={formData.poolCurrency}
                onChange={(c) => setFormData({ ...formData, poolCurrency: c })}
                withName
              />
            </div>
            <div>
              <label className="field-label block mb-1">Target Balance</label>
              <Input
                type="number"
                placeholder="Optional"
                value={formData.targetBalance}
                onChange={(e) => setFormData({ ...formData, targetBalance: e.target.value })}
              />
            </div>
          </div>

          {/* Interest Rate & Method */}
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="field-label block mb-1">
                Interest Rate (%) <span className="text-error-500 dark:text-error-300">*</span>
              </label>
              <Input
                type="number"
                step="0.01"
                value={formData.interestRate}
                onChange={(e) => setFormData({ ...formData, interestRate: e.target.value })}
              />
            </div>
            <div>
              <label className="field-label block mb-1">Calculation Method</label>
              <Select
                value={formData.interestCalculationMethod}
                onChange={(e) => setFormData({ ...formData, interestCalculationMethod: e.target.value as any })}
                options={[
                  { value: 'DAILY_AVERAGE', label: 'Daily Average' },
                  { value: 'MONTH_END', label: 'Month End' },
                  { value: 'TIER_BASED', label: 'Tier Based' },
                ]}
              />
            </div>
          </div>

          {/* Effective Dates */}
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="field-label block mb-1">Effective From</label>
              <Input
                type="date"
                value={formData.effectiveFrom}
                onChange={(e) => setFormData({ ...formData, effectiveFrom: e.target.value })}
              />
            </div>
            <div>
              <label className="field-label block mb-1">Effective To</label>
              <Input
                type="date"
                value={formData.effectiveTo}
                onChange={(e) => setFormData({ ...formData, effectiveTo: e.target.value })}
              />
            </div>
          </div>
        </div>
      ) : (
        <div className="space-y-4">
          <div className="bg-info-50 dark:bg-info-500/10 rounded-lg p-3 flex items-start gap-2">
            <Info className="w-4 h-4 text-info-600 dark:text-info-300 mt-0.5" />
            <div>
              <p className="text-sm font-medium text-info-800 dark:text-info-300">Select Pool Members</p>
              <p className="text-xs text-info-600 dark:text-info-300">
                Choose accounts to participate in this notional pool.
                Only {formData.poolCurrency} accounts are shown.
              </p>
            </div>
          </div>

          <AccountSelector
            selectedAccounts={selectedMembers}
            onSelect={setSelectedMembers}
            currency={formData.poolCurrency}
            corporateId={corporateId}
          />
        </div>
      )}

      {/* Footer */}
      <div className="flex justify-between gap-2 mt-6 pt-4 border-t">
        <Button variant="ghost" onClick={() => { onClose(); resetForm(); }}>Cancel</Button>
        <div className="flex gap-2">
          {step === 2 && (
            <Button variant="outline" onClick={() => setStep(1)}>Back</Button>
          )}
          {step === 1 ? (
            <Button onClick={() => setStep(2)} disabled={!canProceedToStep2}>
              Next: Add Members
            </Button>
          ) : (
            <Button onClick={handleSubmit} disabled={saving}>
              {saving && <Loader2 className="w-4 h-4 animate-spin mr-2" />}
              Create Pool {selectedMembers.length > 0 && `(${selectedMembers.length} members)`}
            </Button>
          )}
        </div>
      </div>
    </Modal>
  );
};
