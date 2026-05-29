import React from 'react';
import { Input, Select } from '../../ui';
import { cn } from '../../../utils';
import { Corporate, Program } from '../../../services/api';
import { SWEEP_TYPES, FREQUENCIES } from '../constants';
import type { CreateRuleFormData, SetCreateRuleFormData } from './types';

// ============================================================================
// Step 1 — Setup: Corporate, Program, Rule Name, Sweep Type, Frequency
// ============================================================================
export interface Step1SetupProps {
  formData: CreateRuleFormData;
  setFormData: SetCreateRuleFormData;
  corporates: Corporate[];
  programs: Program[];
  loadingCorporates: boolean;
  loadingPrograms: boolean;
}

export const Step1Setup: React.FC<Step1SetupProps> = ({
  formData,
  setFormData,
  corporates,
  programs,
  loadingCorporates,
  loadingPrograms,
}) => (
  <div className="space-y-6 animate-fade-in">
    {/* Corporate Selection */}
    <Select
      label="Corporate"
      value={formData.corporateId}
      onChange={(e) => setFormData({ ...formData, corporateId: e.target.value, programId: '', sourceAccounts: [], targetAccountId: '' })}
      disabled={loadingCorporates}
      placeholder="Select Corporate..."
      options={corporates.map((corp) => ({
        value: corp.id,
        label: corp.legalName || corp.tradeName || corp.corporateId
      }))}
      error={!formData.corporateId ? undefined : undefined}
      hint={loadingCorporates ? 'Loading corporates...' : undefined}
    />

    {/* Program Selection */}
    <Select
      label="Program"
      value={formData.programId}
      onChange={(e) => setFormData({ ...formData, programId: e.target.value })}
      disabled={!formData.corporateId || loadingPrograms}
      placeholder="All Programs (Optional)"
      options={programs.map((prog) => ({
        value: prog.id,
        label: prog.programName || prog.programCode
      }))}
      hint={loadingPrograms ? 'Loading programs...' : 'Optional - leave empty to apply to all programs'}
    />

    <Input
      label="Rule Name"
      placeholder="e.g., UAE Branches Daily Sweep"
      value={formData.ruleName}
      onChange={(e) => setFormData({ ...formData, ruleName: e.target.value })}
      hint="A descriptive name for this sweep rule"
    />

    <div>
      <label className="block text-sm font-medium text-primary-900 dark:text-neutral-50 mb-3">Sweep Type</label>
      <div className="grid grid-cols-2 gap-3">
        {(Object.keys(SWEEP_TYPES) as Array<keyof typeof SWEEP_TYPES>).map((type) => (
          <div
            key={type}
            onClick={() => setFormData({ ...formData, sweepType: type })}
            className={cn(
              'p-4 border-2 rounded-xl cursor-pointer transition-all duration-200',
              formData.sweepType === type
                ? 'border-primary-500 bg-primary-50 dark:bg-primary-800/40 shadow-sm'
                : 'border-neutral-200 dark:border-primary-800 hover:border-primary-300 dark:hover:border-primary-700 hover:bg-neutral-50 dark:hover:bg-primary-800/50'
            )}
          >
            <p className="font-semibold text-primary-900 dark:text-neutral-50">{SWEEP_TYPES[type].label}</p>
            <p className="text-sm text-neutral-500 dark:text-neutral-400 mt-1">{SWEEP_TYPES[type].desc}</p>
          </div>
        ))}
      </div>
    </div>

    <Select
      label="Frequency"
      value={formData.frequency}
      onChange={(e) => setFormData({ ...formData, frequency: e.target.value })}
      options={FREQUENCIES.map((f) => ({ value: f.value, label: f.label }))}
      hint="How often should this sweep execute"
    />
  </div>
);
