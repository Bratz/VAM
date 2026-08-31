/**
 * EntityAllocationModal - Shared component for allocating entity credit limits
 * Used by both CreditLimitsPage and LegalEntitiesPage
 */
import React, { useState, useEffect } from 'react';
import {
  Building2, Crown, Landmark, Users, Briefcase, ArrowLeftRight,
  FlaskConical, Building, AlertTriangle, Lock, CheckCircle,
  ChevronDown, ChevronUp, Loader2, Wallet,
} from 'lucide-react';
import { formatCurrency, cn } from '../../utils';
import { Modal } from '../ui/enhanced';

// ============================================================================
// TYPES
// ============================================================================

export type EntityType = 'HOLDING' | 'SUBSIDIARY' | 'BRANCH' | 'REPRESENTATIVE' | 'JOINT_VENTURE' | 'ASSOCIATE' | 'SPV' | 'TREASURY_CENTER';
export type LimitType = 'OVERDRAFT' | 'INTRADAY' | 'DAYLIGHT' | 'OVERNIGHT' | 'AGGREGATE' | 'TRANSACTION' | 'DAILY' | 'MONTHLY';

export interface LegalEntityBasic {
  id: string;
  entityCode: string;
  entityName: string;
  entityType: EntityType;
  functionalCurrency: string;
  isBankCustomer?: boolean;
  isTreasuryCenter?: boolean;
}

export interface InternalLimitResponse {
  id: string;
  limitName?: string;
  limitType: 'GROUP' | 'ENTITY' | 'VA' | string;
  corporateId: string;
  targetType: string;
  targetId: string;
  parentLimitId?: string;
  allocatedToChildren?: number;
  unallocatedAmount?: number;
  limitAmount: number;
  currency: string;
  utilizedAmount: number;
  availableAmount: number;
  heldAmount?: number;
  utilizationPercent: number;
  warningThresholdPercent?: number;
  criticalThresholdPercent?: number;
  isAtWarningLevel: boolean;
  isAtCriticalLevel?: boolean;
  isBreached: boolean;
  isHardLimit: boolean;
  requiresApproval: boolean;
  approvalThresholdPercent?: number;
  needsApprovalNow?: boolean;
  effectiveFrom?: string;
  effectiveTo?: string;
  status: string;
  approvedBy?: string;
  notes?: string;
  createdAt: string;
  subLimitType?: LimitType;
}

export interface EntityLimitAllocationData {
  entityId: string;
  limitName: string;
  amount: number;
  currency: string;
  limitType: LimitType;
  hardLimit: boolean;
  requiresApproval: boolean;
  approvalThreshold?: number;
  warningThreshold: number;
  criticalThreshold: number;
  effectiveFrom: string;
  effectiveTo?: string;
  notes?: string;
  approvedBy: string;
}

// ============================================================================
// HELPERS
// ============================================================================

const safeNumber = (value: number | undefined | null, defaultValue: number = 0): number => {
  return typeof value === 'number' && !isNaN(value) && isFinite(value) ? value : defaultValue;
};

const formatDateString = (date: Date | string | null | undefined): string => {
  if (!date) return '';
  const d = typeof date === 'string' ? new Date(date) : date;
  return d.toISOString().split('T')[0];
};

// ============================================================================
// CONFIG
// ============================================================================

export const currencyConfig: Record<string, { symbol: string; name: string; color: string; bgColor: string }> = {
  AED: { symbol: 'د.إ', name: 'UAE Dirham', color: 'text-success-700 dark:text-success-300', bgColor: 'bg-success-50 dark:bg-success-500/10' },
  USD: { symbol: '$', name: 'US Dollar', color: 'text-success-700', bgColor: 'bg-success-50' },
  EUR: { symbol: '€', name: 'Euro', color: 'text-info-700', bgColor: 'bg-info-50' },
  GBP: { symbol: '£', name: 'British Pound', color: 'text-accent-700', bgColor: 'bg-accent-50' },
  SAR: { symbol: 'ر.س', name: 'Saudi Riyal', color: 'text-primary-700 dark:text-neutral-200', bgColor: 'bg-primary-50 dark:bg-primary-800/40' },
  QAR: { symbol: 'ر.ق', name: 'Qatari Riyal', color: 'text-info-700 dark:text-info-300', bgColor: 'bg-info-50 dark:bg-info-500/10' },
  KWD: { symbol: 'د.ك', name: 'Kuwaiti Dinar', color: 'text-warning-700 dark:text-warning-300', bgColor: 'bg-warning-50 dark:bg-warning-500/10' },
  CHF: { symbol: 'CHF', name: 'Swiss Franc', color: 'text-warning-700 dark:text-warning-300', bgColor: 'bg-warning-50 dark:bg-warning-500/10' },
  JPY: { symbol: '¥', name: 'Japanese Yen', color: 'text-accent-700', bgColor: 'bg-accent-50' },
  INR: { symbol: '₹', name: 'Indian Rupee', color: 'text-primary-700 dark:text-neutral-200', bgColor: 'bg-primary-50' },
};

export const entityTypeConfig: Record<EntityType, { label: string; icon: React.ElementType; color: string; bgColor: string }> = {
  HOLDING: { label: 'Holding', icon: Crown, color: 'text-primary-700 dark:text-neutral-200', bgColor: 'bg-primary-100 dark:bg-primary-700' },
  SUBSIDIARY: { label: 'Subsidiary', icon: Building2, color: 'text-info-700 dark:text-info-300', bgColor: 'bg-info-50 dark:bg-info-500/10' },
  BRANCH: { label: 'Branch', icon: Building, color: 'text-info-600 dark:text-info-300', bgColor: 'bg-info-50 dark:bg-info-500/10' },
  REPRESENTATIVE: { label: 'Representative', icon: Users, color: 'text-neutral-700 dark:text-neutral-200', bgColor: 'bg-neutral-100 dark:bg-primary-800' },
  JOINT_VENTURE: { label: 'Joint Venture', icon: ArrowLeftRight, color: 'text-warning-700 dark:text-warning-300', bgColor: 'bg-warning-50 dark:bg-warning-500/10' },
  ASSOCIATE: { label: 'Associate', icon: Briefcase, color: 'text-success-700 dark:text-success-300', bgColor: 'bg-success-50 dark:bg-success-500/10' },
  SPV: { label: 'SPV', icon: FlaskConical, color: 'text-error-700 dark:text-error-300', bgColor: 'bg-error-50 dark:bg-error-500/10' },
  TREASURY_CENTER: { label: 'Treasury', icon: Landmark, color: 'text-accent-700 dark:text-accent-300', bgColor: 'bg-accent-100 dark:bg-accent-500/20' },
};

export const limitTypeOptions: { value: LimitType; label: string; description: string }[] = [
  { value: 'OVERDRAFT', label: 'Overdraft', description: 'Standard overdraft facility' },
  { value: 'INTRADAY', label: 'Intraday', description: 'Daylight borrowing - cleared by EOD' },
  { value: 'AGGREGATE', label: 'Aggregate', description: 'Combined limit across all types' },
  { value: 'TRANSACTION', label: 'Per-Transaction', description: 'Maximum per single transaction' },
  { value: 'DAILY', label: 'Daily Cap', description: 'Maximum daily cumulative usage' },
  { value: 'MONTHLY', label: 'Monthly Cap', description: 'Maximum monthly cumulative usage' },
];

// ============================================================================
// COMPONENT
// ============================================================================

export interface EntityAllocationModalProps {
  isOpen: boolean;
  onClose: () => void;
  entity: LegalEntityBasic | null;
  existingLimit: InternalLimitResponse | null;
  groupLimits: InternalLimitResponse[];
  existingEntityCurrencies: string[];
  externalCeiling?: number;
  corporateId: string;
  onSave: (data: EntityLimitAllocationData) => Promise<void>;
}

export const EntityAllocationModal: React.FC<EntityAllocationModalProps> = ({
  isOpen,
  onClose,
  entity,
  existingLimit,
  groupLimits,
  existingEntityCurrencies,
  externalCeiling,
  corporateId: _corporateId, // Reserved for future use
  onSave,
}) => {
  const [limitName, setLimitName] = useState('');
  const [amount, setAmount] = useState<number>(0);
  const [currency, setCurrency] = useState('AED');
  const [limitType, setLimitType] = useState<LimitType>('AGGREGATE');
  const [hardLimit, setHardLimit] = useState(true);
  const [requiresApproval, setRequiresApproval] = useState(false);
  const [approvalThreshold, setApprovalThreshold] = useState(80);
  const [approvedBy, setApprovedBy] = useState('Treasury');
  const [warningThreshold, setWarningThreshold] = useState(80);
  const [criticalThreshold, setCriticalThreshold] = useState(95);
  const [effectiveFrom, setEffectiveFrom] = useState(formatDateString(new Date()));
  const [effectiveTo, setEffectiveTo] = useState('');
  const [notes, setNotes] = useState('');
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const isEditing = !!existingLimit;
  const availableGroupCurrencies = groupLimits.map(l => l.currency);
  const availableCurrenciesForEntity = isEditing
    ? [existingLimit.currency]
    : availableGroupCurrencies.filter(c => !existingEntityCurrencies.includes(c));

  const groupLimit = groupLimits.find(l => l.currency === currency);
  const groupUnallocated = safeNumber(groupLimit?.unallocatedAmount);
  const currentAllocation = isEditing && existingLimit.currency === currency ? safeNumber(existingLimit.limitAmount) : 0;
  const maxFromGroup = groupUnallocated + currentAllocation;
  const maxAllocation = externalCeiling ? Math.min(maxFromGroup, externalCeiling) : maxFromGroup;
  const minAmount = existingLimit ? safeNumber(existingLimit.utilizedAmount) : 0;

  useEffect(() => {
    if (!isOpen) return;

    if (existingLimit) {
      setLimitName(existingLimit.limitName || '');
      setAmount(safeNumber(existingLimit.limitAmount));
      setCurrency(existingLimit.currency || 'AED');
      setLimitType((existingLimit.subLimitType as LimitType) || 'AGGREGATE');
      setHardLimit(existingLimit.isHardLimit);
      setRequiresApproval(existingLimit.requiresApproval);
      setApprovalThreshold(existingLimit.approvalThresholdPercent || 80);
      setWarningThreshold(existingLimit.warningThresholdPercent || 80);
      setCriticalThreshold(existingLimit.criticalThresholdPercent || 95);
      setEffectiveFrom(existingLimit.effectiveFrom?.split('T')[0] || formatDateString(new Date()));
      setEffectiveTo(existingLimit.effectiveTo?.split('T')[0] || '');
      setNotes(existingLimit.notes || '');
      setApprovedBy(existingLimit.approvedBy || 'Treasury');
    } else if (entity) {
      const availableGroupCurrenciesLocal = groupLimits.map(l => l.currency);
      const existingCurrenciesLocal = existingEntityCurrencies || [];
      const availableCurrenciesLocal = availableGroupCurrenciesLocal.filter(c => !existingCurrenciesLocal.includes(c));
      const firstCurrency = availableCurrenciesLocal[0] || 'AED';

      setLimitName(`${entity.entityCode} Internal Credit Limit - ${firstCurrency}`);
      setAmount(0);
      setCurrency(firstCurrency);
      setLimitType('AGGREGATE');
      setHardLimit(true);
      setRequiresApproval(false);
      setApprovalThreshold(80);
      setWarningThreshold(80);
      setCriticalThreshold(95);
      setEffectiveFrom(formatDateString(new Date()));
      setEffectiveTo('');
      setNotes('');
      setApprovedBy('Treasury');
    }
    setError(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen, existingLimit?.id, entity?.id]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!entity || !limitName || amount <= 0) return;
    if (warningThreshold >= criticalThreshold) {
      setError('Warning threshold must be less than critical threshold');
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const data: EntityLimitAllocationData = {
        entityId: entity.id,
        limitName,
        amount,
        currency,
        limitType,
        hardLimit,
        requiresApproval,
        approvalThreshold: requiresApproval ? approvalThreshold : undefined,
        warningThreshold,
        criticalThreshold,
        effectiveFrom,
        effectiveTo: effectiveTo || undefined,
        notes: notes || undefined,
        approvedBy,
      };
      await onSave(data);
      onClose();
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Failed to save');
    } finally {
      setSaving(false);
    }
  };

  if (!isOpen || !entity) return null;

  const typeConfig = entityTypeConfig[entity.entityType] || entityTypeConfig.SUBSIDIARY;
  const TypeIcon = typeConfig.icon;

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title={isEditing ? `Update ${currency} Limit` : 'Allocate Entity Credit Limit'}
      subtitle={isEditing ? 'Modify existing currency limit' : 'Allocate from group limit pool'}
      size="xl"
    >
      <form onSubmit={handleSubmit} className="space-y-6">
          {/* Entity Info */}
          <div className="bg-white dark:bg-primary-900/60 dark:bg-white backdrop-blur-sm rounded-xl p-4 border border-neutral-200 dark:border-primary-800/60 dark:border-primary-700/30">
            <div className="flex items-center gap-3">
              <div className={cn('p-2 rounded-lg', typeConfig.bgColor, 'dark:bg-opacity-20')}>
                <TypeIcon className={cn('w-5 h-5', typeConfig.color)} />
              </div>
              <div className="flex-1">
                <p className="font-semibold text-primary-900 dark:text-neutral-50 tracking-tight">{entity.entityName}</p>
                <p className="text-sm text-neutral-500 dark:text-neutral-400 dark:text-neutral-500 font-mono">{entity.entityCode}</p>
              </div>
              {entity.isBankCustomer && (
                <span className="badge badge-sm bg-success-50 dark:bg-success-500/10 text-success-700 dark:text-success-400 border border-success-200/60 dark:border-success-500/20 dark:text-success-300">
                  <Wallet className="w-3 h-3 inline" /> Bank Customer
                </span>
              )}
            </div>
            {existingEntityCurrencies.length > 0 && !isEditing && (
              <div className="mt-3 pt-3 border-t border-neutral-200 dark:border-primary-800/60 dark:border-primary-700/30">
                <p className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">Existing limits: {existingEntityCurrencies.join(', ')}</p>
              </div>
            )}
          </div>

          {/* Error */}
          {error && (
            <div className="bg-error-50 dark:bg-error-500/10 rounded-xl p-4 border border-error-200/60 dark:border-error-500/20 animate-shake">
              <p className="text-sm text-error-700 dark:text-error-400 dark:text-error-300">
                <AlertTriangle className="w-4 h-4 inline mr-1" />
                {error}
              </p>
            </div>
          )}

          {/* Currency Selection */}
          <div>
            <label className="form-label dark:text-neutral-50">Select Currency</label>
            <div className="flex flex-wrap gap-2">
              {(isEditing ? [existingLimit!.currency] : availableCurrenciesForEntity).map(c => {
                const gl = groupLimits.find(l => l.currency === c);
                const unalloc = safeNumber(gl?.unallocatedAmount);
                const conf = currencyConfig[c] || {};
                const isSelected = c === currency;
                return (
                  <button
                    key={c}
                    type="button"
                    onClick={() => {
                      if (!isEditing) {
                        setCurrency(c);
                        setLimitName(`${entity.entityCode} Internal Credit Limit - ${c}`);
                      }
                    }}
                    disabled={isEditing}
                    className={cn(
                      'px-4 py-3 rounded-xl border-2 transition-premium text-left',
                      isSelected
                        ? 'border-primary-500 bg-primary-50/80 dark:bg-primary-500/15 dark:border-primary-400 backdrop-blur-sm'
                        : 'border-neutral-200 dark:border-primary-800 dark:border-primary-700 hover:border-neutral-300 dark:hover:border-primary-600 bg-white dark:bg-primary-900/40 dark:bg-white dark:hover:border-primary-700',
                      isEditing && 'cursor-not-allowed opacity-50'
                    )}
                  >
                    <div className="flex items-center gap-2">
                      <span className={cn('font-bold tracking-tight', conf.color)}>{c}</span>
                      {isSelected && <CheckCircle className="w-4 h-4 text-primary-600 dark:text-primary-200 dark:text-primary-400" />}
                    </div>
                    <p className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500 mt-1">
                      Available: <span className="currency-value">{formatCurrency(
                        unalloc + (isEditing && existingLimit?.currency === c ? safeNumber(existingLimit?.limitAmount) : 0),
                        c
                      )}</span>
                    </p>
                  </button>
                );
              })}
            </div>
            {availableCurrenciesForEntity.length === 0 && !isEditing && (
              <p className="text-sm text-warning-700 dark:text-warning-400 mt-2 dark:text-warning-300">
                <AlertTriangle className="w-4 h-4 inline mr-1" />
                No available currencies. Create group limits for more currencies first.
              </p>
            )}
          </div>

          {/* Allocation Form */}
          {groupLimit && (
            <>
              {/* Pool Info */}
              <div className="grid grid-cols-3 gap-3">
                <div className="p-3 bg-primary-50/80 dark:bg-primary-500/10 backdrop-blur-sm rounded-xl border border-primary-200/60 dark:border-primary-500/20">
                  <p className="text-xs font-medium text-primary-700 dark:text-neutral-200 dark:text-primary-300 tracking-wide uppercase">Group Available</p>
                  <p className="text-lg font-bold text-primary-700 dark:text-neutral-200 dark:text-primary-300 currency-value">{formatCurrency(maxFromGroup, currency)}</p>
                </div>
                <div className={cn('p-3 backdrop-blur-sm rounded-xl border', externalCeiling
                  ? 'bg-info-50/80 dark:bg-info-500/10 border-info-200/60 dark:border-info-500/20'
                  : 'bg-white dark:bg-primary-900/40 dark:bg-white border-neutral-200 dark:border-primary-800/60 dark:border-primary-700/30'
                )}>
                  <p className={cn('text-xs font-medium tracking-wide uppercase', externalCeiling ? 'text-info-700 dark:text-info-300' : 'text-neutral-500 dark:text-neutral-400 dark:text-neutral-500')}>External Ceiling</p>
                  <p className={cn('text-lg font-bold currency-value', externalCeiling ? 'text-info-700 dark:text-info-300' : 'text-neutral-400 dark:text-neutral-500 dark:text-neutral-400')}>
                    {externalCeiling ? formatCurrency(externalCeiling, entity.functionalCurrency) : 'N/A'}
                  </p>
                </div>
                <div className="p-3 bg-success-50/80 dark:bg-success-500/10 backdrop-blur-sm border border-success-200/60 dark:border-success-500/20 rounded-xl">
                  <p className="text-xs font-medium text-success-700 dark:text-success-300 tracking-wide uppercase">Max Allocation</p>
                  <p className="text-lg font-bold text-success-700 dark:text-success-300 currency-value">{formatCurrency(maxAllocation, currency)}</p>
                </div>
              </div>

              {/* Form Fields */}
              <div className="space-y-4">
                <div className="grid grid-cols-2 gap-4">
                  <div className="col-span-2">
                    <label className="form-label dark:text-neutral-50">Limit Name</label>
                    <input
                      type="text"
                      value={limitName}
                      onChange={(e) => setLimitName(e.target.value)}
                      className="form-input dark:bg-primary-800/50 dark:border-primary-700 dark:text-neutral-50 dark:placeholder:text-neutral-500 dark:text-neutral-400 dark:text-neutral-500"
                      required
                    />
                  </div>
                  <div>
                    <label className="form-label dark:text-neutral-50">
                      Amount <span className="text-neutral-400 dark:text-neutral-500 dark:text-neutral-400 font-normal">(max: <span className="currency-value">{formatCurrency(maxAllocation, currency)}</span>)</span>
                    </label>
                    <input
                      type="number"
                      value={amount}
                      onChange={(e) => setAmount(e.target.value === '' ? 0 : parseFloat(e.target.value))}
                      min={minAmount}
                      max={maxAllocation}
                      step={1000}
                      className={cn(
                        'form-input tabular-nums dark:bg-primary-800/50 dark:border-primary-700 dark:text-neutral-50',
                        amount > maxAllocation && 'form-input-error dark:border-error-500/50'
                      )}
                      required
                    />
                    {amount > maxAllocation && (
                      <p className="form-error dark:text-error-400">
                        <AlertTriangle className="w-3 h-3" /> Exceeds maximum
                      </p>
                    )}
                  </div>
                  <div>
                    <label className="form-label dark:text-neutral-50">Limit Type</label>
                    <select
                      value={limitType}
                      onChange={(e) => setLimitType(e.target.value as LimitType)}
                      className="form-input dark:bg-primary-800/50 dark:border-primary-700 dark:text-neutral-50"
                    >
                      {limitTypeOptions.map(opt => (
                        <option key={opt.value} value={opt.value}>{opt.label}</option>
                      ))}
                    </select>
                  </div>
                </div>

                {/* Control Settings */}
                <div className="p-4 bg-white dark:bg-primary-900/60 dark:bg-white backdrop-blur-sm rounded-xl border border-neutral-200 dark:border-primary-800/60 dark:border-primary-700/30 space-y-3">
                  <p className="text-sm font-semibold text-primary-900 dark:text-neutral-50 tracking-tight">Control Settings</p>
                  <label className="flex items-center gap-2 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={hardLimit}
                      onChange={(e) => setHardLimit(e.target.checked)}
                      className="w-4 h-4 text-primary-600 dark:text-primary-200 dark:text-primary-400 rounded border-neutral-300 dark:border-primary-700 dark:border-primary-600 dark:bg-primary-800"
                    />
                    <Lock className="w-4 h-4 text-neutral-500 dark:text-neutral-400 dark:text-neutral-500" />
                    <span className="text-sm text-primary-900 dark:text-neutral-50 dark:text-neutral-200">Hard Limit - Block transactions when exceeded</span>
                  </label>
                  <label className="flex items-center gap-2 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={requiresApproval}
                      onChange={(e) => setRequiresApproval(e.target.checked)}
                      className="w-4 h-4 text-primary-600 dark:text-primary-200 dark:text-primary-400 rounded border-neutral-300 dark:border-primary-700 dark:border-primary-600 dark:bg-primary-800"
                    />
                    <span className="text-sm text-primary-900 dark:text-neutral-50 dark:text-neutral-200">Require Approval</span>
                    {requiresApproval && (
                      <div className="flex items-center gap-1 ml-2">
                        <span className="text-sm text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">when exceeds</span>
                        <input
                          type="number"
                          value={approvalThreshold}
                          onChange={(e) => setApprovalThreshold(parseInt(e.target.value) || 80)}
                          min={0}
                          max={100}
                          className="w-16 px-2 py-1.5 border border-neutral-300 dark:border-primary-700 dark:border-primary-600 dark:bg-primary-800/50 dark:text-neutral-50 rounded-lg text-sm tabular-nums"
                        />
                        <span className="text-sm text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">%</span>
                      </div>
                    )}
                  </label>
                </div>
              </div>

              {/* Advanced Settings */}
              <div className="border-t border-neutral-200 dark:border-primary-800/60 dark:border-primary-700/30 pt-4">
                <button
                  type="button"
                  onClick={() => setShowAdvanced(!showAdvanced)}
                  className="flex items-center gap-2 text-sm font-medium text-primary-600 dark:text-primary-200 dark:text-primary-400 hover:text-primary-700 dark:text-neutral-200 dark:hover:text-primary-300 transition-premium dark:hover:text-neutral-200"
                >
                  {showAdvanced ? <ChevronUp className="w-4 h-4" /> : <ChevronDown className="w-4 h-4" />}
                  Advanced Settings
                </button>
                {showAdvanced && (
                  <div className="mt-4 space-y-4 p-4 bg-white dark:bg-primary-900/60 dark:bg-white backdrop-blur-sm rounded-xl border border-neutral-200 dark:border-primary-800/60 dark:border-primary-700/30 animate-slide-up">
                    <div className="grid grid-cols-2 gap-4">
                      <div>
                        <label className="form-label dark:text-neutral-50">Warning Threshold (%)</label>
                        <input
                          type="number"
                          value={warningThreshold}
                          onChange={(e) => setWarningThreshold(parseInt(e.target.value) || 80)}
                          min={0}
                          max={100}
                          className="form-input tabular-nums dark:bg-primary-800/50 dark:border-primary-700 dark:text-neutral-50"
                        />
                      </div>
                      <div>
                        <label className="form-label dark:text-neutral-50">Critical Threshold (%)</label>
                        <input
                          type="number"
                          value={criticalThreshold}
                          onChange={(e) => setCriticalThreshold(parseInt(e.target.value) || 95)}
                          min={0}
                          max={100}
                          className="form-input tabular-nums dark:bg-primary-800/50 dark:border-primary-700 dark:text-neutral-50"
                        />
                      </div>
                    </div>
                    <div className="grid grid-cols-2 gap-4">
                      <div>
                        <label className="form-label dark:text-neutral-50">Effective From</label>
                        <input
                          type="date"
                          value={effectiveFrom}
                          onChange={(e) => setEffectiveFrom(e.target.value)}
                          className="form-input dark:bg-primary-800/50 dark:border-primary-700 dark:text-neutral-50"
                          required
                        />
                      </div>
                      <div>
                        <label className="form-label dark:text-neutral-50">Effective To</label>
                        <input
                          type="date"
                          value={effectiveTo}
                          onChange={(e) => setEffectiveTo(e.target.value)}
                          min={effectiveFrom}
                          className="form-input dark:bg-primary-800/50 dark:border-primary-700 dark:text-neutral-50"
                        />
                      </div>
                    </div>
                    <div>
                      <label className="form-label dark:text-neutral-50">Notes</label>
                      <textarea
                        value={notes}
                        onChange={(e) => setNotes(e.target.value)}
                        rows={2}
                        className="form-input dark:bg-primary-800/50 dark:border-primary-700 dark:text-neutral-50"
                        placeholder="Optional notes..."
                      />
                    </div>
                    <div>
                      <label className="form-label dark:text-neutral-50">Approved By</label>
                      <input
                        type="text"
                        value={approvedBy}
                        onChange={(e) => setApprovedBy(e.target.value)}
                        className="form-input dark:bg-primary-800/50 dark:border-primary-700 dark:text-neutral-50"
                      />
                    </div>
                  </div>
                )}
              </div>
            </>
          )}

          {/* No Group Limit Warning */}
          {!groupLimit && (
            <div className="bg-warning-50/80 dark:bg-warning-500/10 backdrop-blur-sm border border-warning-200/60 dark:border-warning-500/20 rounded-xl p-4">
              <p className="text-sm text-warning-700 dark:text-warning-400 dark:text-warning-300">
                <AlertTriangle className="w-4 h-4 inline mr-1" />
                No group limit for {currency}. Please select a different currency or create a group limit first.
              </p>
            </div>
          )}

          {/* Actions */}
          <div className="flex justify-end gap-3 pt-4 border-t border-neutral-200 dark:border-primary-800/60 dark:border-primary-700/30">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2.5 border-2 border-neutral-300 dark:border-primary-700 dark:border-primary-600 rounded-xl text-sm font-medium text-primary-900 dark:text-neutral-50 dark:text-neutral-200 hover:bg-neutral-50 dark:bg-primary-950 dark:hover:bg-primary-800/50 dark:hover:bg-primary-800 transition-premium tracking-wide"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={saving || !limitName || amount <= 0 || amount > maxAllocation || amount < minAmount || !groupLimit}
              className="px-5 py-2.5 bg-primary-900 dark:bg-primary-600 text-white rounded-xl text-sm font-medium disabled:opacity-50 hover:bg-primary-800 dark:hover:bg-primary-500 transition-premium tracking-wide shadow-sm hover:shadow-md"
            >
              {saving && <Loader2 className="w-4 h-4 animate-spin inline mr-1.5" />}
              {isEditing ? 'Update Limit' : 'Allocate Limit'}
            </button>
          </div>
      </form>
    </Modal>
  );
};

export default EntityAllocationModal;
