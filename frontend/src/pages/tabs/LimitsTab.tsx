// ============================================================================
// LIMITS TAB
// Spending limits (per-transaction, daily, weekly, monthly, annual) and topup limits
// ============================================================================

import React from 'react';
import { Settings, TrendingUp, Info, AlertCircle } from 'lucide-react';
import { Badge } from '../../components/ui';
import { Alert } from '../../components/ui/enhanced';
import { FormField, NumberInput } from './FormComponents';
import { CreateVaRequest, Program, ProgramTypeConfig } from '../vaTypes';
import { formatCurrency } from '../../utils';

// ============================================================================
// TYPES
// ============================================================================

export interface LimitsTabProps {
  formData: CreateVaRequest;
  setFormData: React.Dispatch<React.SetStateAction<CreateVaRequest>>;
  errors: Record<string, string>;
  program?: Program;
  config?: ProgramTypeConfig;
}

// ============================================================================
// KYC-BASED LIMITS REFERENCE
// ============================================================================

const KYC_TIER_LIMITS = [
  { level: 0, name: 'Unverified', maxBalance: 1000, dailyLimit: 500, monthlyLimit: 2000 },
  { level: 1, name: 'Basic', maxBalance: 10000, dailyLimit: 5000, monthlyLimit: 20000 },
  { level: 2, name: 'Enhanced', maxBalance: 100000, dailyLimit: 50000, monthlyLimit: 200000 },
  { level: 3, name: 'Full', maxBalance: 1000000, dailyLimit: 500000, monthlyLimit: 2000000 },
];

// ============================================================================
// COMPONENT
// ============================================================================

export const LimitsTab: React.FC<LimitsTabProps> = ({
  formData,
  setFormData,
  errors,
  program,
  config,
}) => {
  // Update field helper
  const updateField = <K extends keyof CreateVaRequest>(
    field: K,
    value: CreateVaRequest[K]
  ) => {
    setFormData(prev => ({ ...prev, [field]: value }));
  };

  // Currency for display
  const currency = formData.currencyCode || 'USD';

  // Get program defaults for placeholders
  const programDefaults = {
    perTransaction: program?.defaultPerTransactionLimit,
    daily: program?.defaultDailyLimit,
    weekly: program?.defaultWeeklyLimit,
    monthly: program?.defaultMonthlyLimit,
    yearly: program?.defaultYearlyLimit,
    maxBalance: program?.defaultMaxBalance,
    dailyTopup: program?.defaultDailyTopupLimit,
    monthlyTopup: program?.defaultMonthlyTopupLimit,
  };

  // Get KYC tier info
  const kycLevel = formData.kycLevel ?? 0;
  const kycTierInfo = KYC_TIER_LIMITS.find(t => t.level === kycLevel) || KYC_TIER_LIMITS[0];

  // Validation warnings
  const warnings: string[] = [];
  
  if (formData.perTransactionLimit && formData.dailyLimit && 
      formData.perTransactionLimit > formData.dailyLimit) {
    warnings.push('Per-transaction limit exceeds daily limit');
  }
  
  if (formData.dailyLimit && formData.weeklyLimit && 
      formData.dailyLimit * 7 < formData.weeklyLimit) {
    warnings.push('Weekly limit is higher than 7x daily limit');
  }
  
  if (formData.weeklyLimit && formData.monthlyLimit && 
      formData.weeklyLimit * 4 < formData.monthlyLimit) {
    warnings.push('Monthly limit is higher than 4x weekly limit');
  }

  if (formData.maxBalance && kycTierInfo && formData.maxBalance > kycTierInfo.maxBalance) {
    warnings.push(`Max balance exceeds KYC Level ${kycLevel} limit of ${formatCurrency(kycTierInfo.maxBalance, currency)}`);
  }

  return (
    <div className="space-y-6">
      {/* Header with inheritance toggle */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Settings className="w-5 h-5 text-primary-600" />
          <h4 className="text-sm font-medium text-neutral-900">Spending Limits</h4>
        </div>
        {program && formData.inheritProgramDefaults && (
          <Badge variant="info" size="sm">Using program defaults</Badge>
        )}
      </div>

      {/* Spending Limits Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        <FormField 
          label="Per Transaction Limit" 
          hint={`Maximum per single transaction in ${currency}`}
          error={errors.perTransactionLimit}
        >
          <NumberInput
            value={formData.perTransactionLimit}
            onChange={(v) => updateField('perTransactionLimit', v)}
            min={0}
            placeholder={programDefaults.perTransaction?.toString() || 'No limit'}
            prefix={currency}
            error={!!errors.perTransactionLimit}
          />
          {programDefaults.perTransaction && !formData.perTransactionLimit && (
            <p className="text-xs text-neutral-400 mt-1">
              Default: {formatCurrency(programDefaults.perTransaction, currency)}
            </p>
          )}
        </FormField>

        <FormField 
          label="Daily Limit" 
          hint={`Maximum daily spending in ${currency}`}
          error={errors.dailyLimit}
        >
          <NumberInput
            value={formData.dailyLimit}
            onChange={(v) => updateField('dailyLimit', v)}
            min={0}
            placeholder={programDefaults.daily?.toString() || 'No limit'}
            prefix={currency}
            error={!!errors.dailyLimit}
          />
          {programDefaults.daily && !formData.dailyLimit && (
            <p className="text-xs text-neutral-400 mt-1">
              Default: {formatCurrency(programDefaults.daily, currency)}
            </p>
          )}
        </FormField>

        <FormField 
          label="Weekly Limit"
          hint={`Maximum weekly spending in ${currency}`}
        >
          <NumberInput
            value={formData.weeklyLimit}
            onChange={(v) => updateField('weeklyLimit', v)}
            min={0}
            placeholder={programDefaults.weekly?.toString() || 'No limit'}
            prefix={currency}
          />
        </FormField>

        <FormField 
          label="Monthly Limit"
          hint={`Maximum monthly spending in ${currency}`}
        >
          <NumberInput
            value={formData.monthlyLimit}
            onChange={(v) => updateField('monthlyLimit', v)}
            min={0}
            placeholder={programDefaults.monthly?.toString() || 'No limit'}
            prefix={currency}
          />
          {programDefaults.monthly && !formData.monthlyLimit && (
            <p className="text-xs text-neutral-400 mt-1">
              Default: {formatCurrency(programDefaults.monthly, currency)}
            </p>
          )}
        </FormField>

        <FormField 
          label="Annual Limit"
          hint={`Maximum yearly spending in ${currency}`}
        >
          <NumberInput
            value={formData.annualLimit}
            onChange={(v) => updateField('annualLimit', v)}
            min={0}
            placeholder={programDefaults.yearly?.toString() || 'No limit'}
            prefix={currency}
          />
        </FormField>

        <FormField 
          label="Maximum Balance" 
          hint="Maximum balance allowed in this account"
          error={errors.maxBalance}
        >
          <NumberInput
            value={formData.maxBalance}
            onChange={(v) => updateField('maxBalance', v)}
            min={0}
            placeholder={programDefaults.maxBalance?.toString() || 'No limit'}
            prefix={currency}
            error={!!errors.maxBalance}
          />
          {programDefaults.maxBalance && !formData.maxBalance && (
            <p className="text-xs text-neutral-400 mt-1">
              Default: {formatCurrency(programDefaults.maxBalance, currency)}
            </p>
          )}
        </FormField>
      </div>

      {/* Warnings */}
      {warnings.length > 0 && (
        <Alert variant="warning">
          <AlertCircle className="w-4 h-4" />
          <div>
            <strong>Limit configuration warnings:</strong>
            <ul className="list-disc list-inside mt-1 text-sm">
              {warnings.map((warning, idx) => (
                <li key={idx}>{warning}</li>
              ))}
            </ul>
          </div>
        </Alert>
      )}

      {/* Topup Limits Section */}
      <div className="border-t border-neutral-200 pt-6">
        <div className="flex items-center gap-2 mb-4">
          <TrendingUp className="w-5 h-5 text-success-600" />
          <h4 className="text-sm font-medium text-neutral-900">Topup Limits</h4>
        </div>
        
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <FormField 
            label="Daily Topup Limit" 
            hint={`Maximum daily topup amount in ${currency}`}
          >
            <NumberInput
              value={formData.dailyTopupLimit}
              onChange={(v) => updateField('dailyTopupLimit', v)}
              min={0}
              placeholder={programDefaults.dailyTopup?.toString() || 'No limit'}
              prefix={currency}
            />
          </FormField>

          <FormField 
            label="Monthly Topup Limit" 
            hint={`Maximum monthly topup amount in ${currency}`}
          >
            <NumberInput
              value={formData.monthlyTopupLimit}
              onChange={(v) => updateField('monthlyTopupLimit', v)}
              min={0}
              placeholder={programDefaults.monthlyTopup?.toString() || 'No limit'}
              prefix={currency}
            />
          </FormField>
        </div>
      </div>

      {/* KYC Tier Limits Reference */}
      <div className="border-t border-neutral-200 pt-6">
        <Alert variant="info">
          <Info className="w-4 h-4" />
          <div>
            <strong>KYC-Based Limits Reference</strong>
            <p className="text-sm mt-1">
              Higher KYC levels automatically enable higher limits. Current selection: Level {kycLevel}
            </p>
          </div>
        </Alert>
        
        <div className="mt-4 overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-neutral-200">
                <th className="text-left py-2 px-3 font-medium text-neutral-600">KYC Level</th>
                <th className="text-right py-2 px-3 font-medium text-neutral-600">Max Balance</th>
                <th className="text-right py-2 px-3 font-medium text-neutral-600">Daily Limit</th>
                <th className="text-right py-2 px-3 font-medium text-neutral-600">Monthly Limit</th>
              </tr>
            </thead>
            <tbody>
              {KYC_TIER_LIMITS.map((tier) => (
                <tr 
                  key={tier.level}
                  className={`border-b border-neutral-100 ${
                    tier.level === kycLevel ? 'bg-primary-50' : ''
                  }`}
                >
                  <td className="py-2 px-3">
                    <div className="flex items-center gap-2">
                      <span className="font-medium">Level {tier.level}</span>
                      <span className="text-neutral-500">- {tier.name}</span>
                      {tier.level === kycLevel && (
                        <Badge variant="primary" size="sm">Selected</Badge>
                      )}
                    </div>
                  </td>
                  <td className="text-right py-2 px-3 amount">
                    {formatCurrency(tier.maxBalance, currency)}
                  </td>
                  <td className="text-right py-2 px-3 amount">
                    {formatCurrency(tier.dailyLimit, currency)}
                  </td>
                  <td className="text-right py-2 px-3 amount">
                    {formatCurrency(tier.monthlyLimit, currency)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* Limit Presets (Quick Apply) */}
      <div className="border-t border-neutral-200 pt-6">
        <h4 className="text-sm font-medium text-neutral-900 mb-3">Quick Apply Presets</h4>
        <div className="flex flex-wrap gap-2">
          <PresetButton
            label="Basic"
            description="$500/day, $2K/month"
            onClick={() => {
              updateField('perTransactionLimit', 200);
              updateField('dailyLimit', 500);
              updateField('monthlyLimit', 2000);
              updateField('maxBalance', 1000);
            }}
          />
          <PresetButton
            label="Standard"
            description="$5K/day, $20K/month"
            onClick={() => {
              updateField('perTransactionLimit', 2000);
              updateField('dailyLimit', 5000);
              updateField('monthlyLimit', 20000);
              updateField('maxBalance', 10000);
            }}
          />
          <PresetButton
            label="Premium"
            description="$50K/day, $200K/month"
            onClick={() => {
              updateField('perTransactionLimit', 20000);
              updateField('dailyLimit', 50000);
              updateField('monthlyLimit', 200000);
              updateField('maxBalance', 100000);
            }}
          />
          <PresetButton
            label="Unlimited"
            description="No limits"
            onClick={() => {
              updateField('perTransactionLimit', undefined);
              updateField('dailyLimit', undefined);
              updateField('weeklyLimit', undefined);
              updateField('monthlyLimit', undefined);
              updateField('annualLimit', undefined);
              updateField('maxBalance', undefined);
            }}
          />
        </div>
      </div>
    </div>
  );
};

// ============================================================================
// HELPER COMPONENTS
// ============================================================================

interface PresetButtonProps {
  label: string;
  description: string;
  onClick: () => void;
}

const PresetButton: React.FC<PresetButtonProps> = ({ label, description, onClick }) => (
  <button
    type="button"
    onClick={onClick}
    className="px-4 py-2 border border-neutral-300 rounded-lg hover:border-primary-400 hover:bg-primary-50 transition-colors text-left"
  >
    <div className="font-medium text-sm text-neutral-900">{label}</div>
    <div className="text-xs text-neutral-500">{description}</div>
  </button>
);

// ============================================================================
// HELPERS
// ============================================================================
// formatCurrency now comes from the shared util (Phase 12 Task D3) — the local
// 0-dp Intl clone was deleted.

export default LimitsTab;