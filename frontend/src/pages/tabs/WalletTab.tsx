// ============================================================================
// WALLET TAB
// Wallet type, KYC level, expiry, value type, loyalty configuration
// ============================================================================

import React from 'react';
import { Wallet, Shield, Star, Clock } from 'lucide-react';
import { Input } from '../../components/ui';
import { Badge } from '../../components/ui';
import { FormField, SelectField, NumberInput } from './FormComponents';
import { CreateVaRequest, Program, ProgramTypeConfig } from '../vaTypes';

// ============================================================================
// TYPES
// ============================================================================

export interface WalletTabProps {
  formData: CreateVaRequest;
  setFormData: React.Dispatch<React.SetStateAction<CreateVaRequest>>;
  errors: Record<string, string>;
  program?: Program;
  config?: ProgramTypeConfig;
}

// ============================================================================
// OPTIONS
// ============================================================================

const WALLET_TYPES = [
  { value: 'CONSUMER', label: 'Consumer Wallet', description: 'Individual customer wallet' },
  { value: 'EMPLOYEE', label: 'Employee Wallet', description: 'Employee expense/benefits wallet' },
  { value: 'MERCHANT', label: 'Merchant Wallet', description: 'Merchant settlement wallet' },
  { value: 'AGENT', label: 'Agent Wallet', description: 'Distribution agent wallet' },
  { value: 'CORPORATE', label: 'Corporate Wallet', description: 'Business operating wallet' },
  { value: 'GIFT', label: 'Gift Wallet', description: 'Gift card/voucher wallet' },
];

const KYC_LEVELS = [
  { value: '0', label: 'Level 0 - Unverified', limits: '$1,000 max balance, $500/day' },
  { value: '1', label: 'Level 1 - Basic', limits: '$10,000 max balance, $5,000/day' },
  { value: '2', label: 'Level 2 - Enhanced', limits: '$100,000 max balance, $50,000/day' },
  { value: '3', label: 'Level 3 - Full', limits: '$1,000,000 max balance, $500,000/day' },
];

const VALUE_TYPES = [
  { value: 'FIAT', label: 'Fiat Currency', description: 'Traditional currency (USD, EUR, etc.)' },
  { value: 'POINTS', label: 'Loyalty Points', description: 'Reward points balance' },
  { value: 'MILES', label: 'Air Miles', description: 'Frequent flyer miles' },
  { value: 'TOKENS', label: 'Digital Tokens', description: 'Platform-specific tokens' },
];

const LOYALTY_TIERS = [
  { value: 'BASIC', label: 'Basic', color: 'neutral' },
  { value: 'BLUE', label: 'Blue', color: 'info' },
  { value: 'SILVER', label: 'Silver', color: 'neutral' },
  { value: 'GOLD', label: 'Gold', color: 'warning' },
  { value: 'PLATINUM', label: 'Platinum', color: 'primary' },
];

const EXPIRY_ACTIONS = [
  { value: 'ZERO_BALANCE', label: 'Zero Balance', description: 'Set balance to zero' },
  { value: 'FORFEIT', label: 'Forfeit to Program', description: 'Transfer to program account' },
  { value: 'TRANSFER', label: 'Transfer', description: 'Transfer to another account' },
  { value: 'EXTEND', label: 'Auto-Extend', description: 'Automatically extend expiry' },
  { value: 'NOTIFY', label: 'Notify Only', description: 'Send notification, no action' },
];

// ============================================================================
// COMPONENT
// ============================================================================

export const WalletTab: React.FC<WalletTabProps> = ({
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

  // Determine if loyalty features should be shown
  const showLoyalty = config?.supportsLoyalty || 
    program?.programType === 'LOYALTY' ||
    formData.valueType === 'POINTS' ||
    formData.valueType === 'MILES';

  // Get selected KYC level info
  const selectedKycLevel = KYC_LEVELS.find(l => l.value === formData.kycLevel?.toString());

  return (
    <div className="space-y-6">
      {/* Wallet Type Section */}
      <div>
        <div className="flex items-center gap-2 mb-4">
          <Wallet className="w-5 h-5 text-primary-600" />
          <h4 className="text-sm font-medium text-neutral-900">Wallet Configuration</h4>
        </div>
        
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <FormField 
            label="Wallet Type"
            hint="Determines the account behavior and features"
          >
            <SelectField
              value={formData.walletType || ''}
              onChange={(v) => updateField('walletType', v)}
              options={WALLET_TYPES.map(t => ({ value: t.value, label: t.label }))}
              placeholder="Select wallet type..."
            />
            {formData.walletType && (
              <p className="text-xs text-neutral-500 mt-1">
                {WALLET_TYPES.find(t => t.value === formData.walletType)?.description}
              </p>
            )}
          </FormField>

          <FormField 
            label="Holder Party ID" 
            hint="Link to a specific party/customer record"
          >
            <Input
              value={formData.holderPartyId || ''}
              onChange={(e) => updateField('holderPartyId', e.target.value)}
              placeholder="Party UUID"
            />
          </FormField>
        </div>
      </div>

      {/* KYC Section */}
      <div className="border-t border-neutral-200 pt-6">
        <div className="flex items-center gap-2 mb-4">
          <Shield className="w-5 h-5 text-primary-600" />
          <h4 className="text-sm font-medium text-neutral-900">KYC Configuration</h4>
          {program?.kycRequired && (
            <Badge variant="warning" size="sm">KYC Required</Badge>
          )}
        </div>
        
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <FormField 
            label="KYC Level" 
            hint="Higher levels enable higher limits"
          >
            <SelectField
              value={formData.kycLevel?.toString() || ''}
              onChange={(v) => updateField('kycLevel', v ? parseInt(v) : undefined)}
              options={KYC_LEVELS.map(l => ({ value: l.value, label: l.label }))}
              placeholder="Select KYC level..."
            />
            {selectedKycLevel && (
              <p className="text-xs text-success-600 mt-1">
                Limits: {selectedKycLevel.limits}
              </p>
            )}
          </FormField>

          <FormField 
            label="Account Expiry Date" 
            hint="When this account expires (optional)"
          >
            <input
              type="date"
              value={formData.expiresAt || ''}
              onChange={(e) => updateField('expiresAt', e.target.value)}
              min={new Date().toISOString().split('T')[0]}
              className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-primary-500"
            />
          </FormField>
        </div>

        {/* KYC Level Cards */}
        <div className="mt-4 grid grid-cols-2 md:grid-cols-4 gap-2">
          {KYC_LEVELS.map((level) => (
            <button
              key={level.value}
              type="button"
              onClick={() => updateField('kycLevel', parseInt(level.value))}
              className={`p-3 rounded-lg border text-left transition-all ${
                formData.kycLevel?.toString() === level.value
                  ? 'border-primary-500 bg-primary-50 ring-2 ring-primary-200'
                  : 'border-neutral-200 hover:border-primary-300 hover:bg-neutral-50'
              }`}
            >
              <div className="font-medium text-sm">Level {level.value}</div>
              <div className="text-xs text-neutral-500 mt-1">{level.limits.split(',')[0]}</div>
            </button>
          ))}
        </div>
      </div>

      {/* Value Type Section */}
      <div className="border-t border-neutral-200 pt-6">
        <div className="flex items-center gap-2 mb-4">
          <Star className="w-5 h-5 text-primary-600" />
          <h4 className="text-sm font-medium text-neutral-900">Value Type</h4>
        </div>
        
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <FormField 
            label="Value Type"
            hint="The type of value stored in this account"
          >
            <SelectField
              value={formData.valueType || 'FIAT'}
              onChange={(v) => updateField('valueType', v)}
              options={VALUE_TYPES.map(t => ({ value: t.value, label: t.label }))}
            />
            {formData.valueType && (
              <p className="text-xs text-neutral-500 mt-1">
                {VALUE_TYPES.find(t => t.value === formData.valueType)?.description}
              </p>
            )}
          </FormField>

          {(formData.valueType === 'POINTS' || formData.valueType === 'MILES') && (
            <FormField 
              label="Points to Currency Rate" 
              hint="Conversion rate (e.g., 100 points = $1.00 → enter 0.01)"
            >
              <NumberInput
                value={formData.pointsToCurrencyRate}
                onChange={(v) => updateField('pointsToCurrencyRate', v)}
                min={0}
                max={1}
                step={0.001}
                placeholder="e.g., 0.01"
              />
            </FormField>
          )}
        </div>
      </div>

      {/* Loyalty Section (conditional) */}
      {showLoyalty && (
        <div className="border-t border-neutral-200 pt-6">
          <div className="flex items-center gap-2 mb-4">
            <Star className="w-5 h-5 text-warning-500" />
            <h4 className="text-sm font-medium text-neutral-900">Loyalty Configuration</h4>
          </div>
          
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <FormField 
              label="Loyalty Tier"
              hint="Member tier for benefits and earn rates"
            >
              <SelectField
                value={formData.loyaltyTier || ''}
                onChange={(v) => updateField('loyaltyTier', v)}
                options={LOYALTY_TIERS.map(t => ({ value: t.value, label: t.label }))}
                placeholder="Select tier..."
              />
            </FormField>

            <FormField 
              label="Loyalty Program ID"
              hint="Link to a specific loyalty program"
            >
              <Input
                value={formData.loyaltyProgramId || ''}
                onChange={(e) => updateField('loyaltyProgramId', e.target.value)}
                placeholder="Loyalty program UUID"
              />
            </FormField>
          </div>

          {/* Loyalty Tier Visual Selection */}
          <div className="mt-4 flex flex-wrap gap-2">
            {LOYALTY_TIERS.map((tier) => (
              <button
                key={tier.value}
                type="button"
                onClick={() => updateField('loyaltyTier', tier.value)}
                className={`px-4 py-2 rounded-full border text-sm font-medium transition-all ${
                  formData.loyaltyTier === tier.value
                    ? 'border-primary-500 bg-primary-100 text-primary-700'
                    : 'border-neutral-300 hover:border-primary-300 text-neutral-600'
                }`}
              >
                {tier.label}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Balance Expiry Section */}
      <div className="border-t border-neutral-200 pt-6">
        <div className="flex items-center gap-2 mb-4">
          <Clock className="w-5 h-5 text-primary-600" />
          <h4 className="text-sm font-medium text-neutral-900">Balance Expiry</h4>
        </div>
        
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <FormField 
            label="Balance Expiry Date" 
            hint="When the balance in this account expires"
          >
            <input
              type="date"
              value={formData.balanceExpiryDate || ''}
              onChange={(e) => updateField('balanceExpiryDate', e.target.value)}
              min={new Date().toISOString().split('T')[0]}
              className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-primary-500"
            />
          </FormField>

          <FormField 
            label="Expiry Action" 
            hint="What happens when the balance expires"
          >
            <SelectField
              value={formData.expiryAction || ''}
              onChange={(v) => updateField('expiryAction', v)}
              options={EXPIRY_ACTIONS.map(a => ({ value: a.value, label: a.label }))}
              placeholder="Select action..."
            />
            {formData.expiryAction && (
              <p className="text-xs text-neutral-500 mt-1">
                {EXPIRY_ACTIONS.find(a => a.value === formData.expiryAction)?.description}
              </p>
            )}
          </FormField>
        </div>
      </div>
    </div>
  );
};

export default WalletTab;