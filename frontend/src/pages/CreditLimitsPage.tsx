import { Page } from '../components/layout/Page';
/**
 * CreditLimitsPage - Enhanced with Multi-Currency Support v5.2.0
 *
 * MULTI-CURRENCY FEATURES:
 * 1. Multiple Group Limits (one per currency: AED, USD, EUR, etc.)
 * 2. Entity Limits per Currency (each entity can have limits in multiple currencies)
 * 3. VA-Level Limits (allocate from entity limit to specific VAs)
 * 4. Currency selector/tabs in Group Limit Card
 * 5. Currency breakdown in Entity Tree
 */
import React, { useState, useEffect, useCallback, useMemo } from 'react';
import {
  Building, Building2, Plus, RefreshCw, Search, Loader2,
  CheckCircle, AlertTriangle, Lock, Unlock, ExternalLink, GitBranch,
  Target, X, Info, Edit, Trash2, ChevronDown, ChevronRight,
  Wallet, Download, Crown, Landmark, Users, Briefcase, ArrowLeftRight,
  FlaskConical, Calendar, Percent, FileText, TrendingUp, DollarSign, Clock,
  ChevronUp, Layers, CreditCard,
} from 'lucide-react';
import { StatusIconBadge } from '../components/ui';
import { Modal } from '../components/ui/enhanced';
import {
  EntityAllocationModal,
  EntityLimitAllocationData,
} from '../components/credit/EntityAllocationModal';
import { usePageHeaderActions } from '../context/PageHeaderContext';
import { formatCurrency } from '../utils';
import { Amount } from '../components/Amount';

// ============================================================================
// UTILITIES
// ============================================================================

const cn = (...classes: (string | boolean | undefined)[]): string => classes.filter(Boolean).join(' ');

const safeNumber = (value: number | undefined | null, defaultValue: number = 0): number => {
  return typeof value === 'number' && !isNaN(value) && isFinite(value) ? value : defaultValue;
};

// formatCurrency now comes from the shared util (Phase 12 Task D3) — the local
// 0-dp Intl clone was deleted; nullable call sites wrap args in safeNumber().

// Compact (K/M/B) formatters removed — full precision everywhere via
// <Amount />, no abbreviated output anywhere in the product.

const safePercent = (numerator: number | undefined | null, denominator: number | undefined | null): number => {
  const num = safeNumber(numerator);
  const denom = safeNumber(denominator);
  if (denom <= 0) return 0;
  const pct = (num / denom) * 100;
  return isNaN(pct) || !isFinite(pct) ? 0 : Math.min(pct, 100);
};

const formatDate = (date: Date | string | null | undefined): string => {
  if (!date) return '';
  const d = typeof date === 'string' ? new Date(date) : date;
  return d.toISOString().split('T')[0];
};

// ============================================================================
// CURRENCY CONFIG
// ============================================================================

const currencyConfig: Record<string, { symbol: string; name: string; color: string; bgColor: string }> = {
  AED: { symbol: 'د.إ', name: 'UAE Dirham', color: 'text-success-700 dark:text-success-300', bgColor: 'bg-success-50 dark:bg-success-500/10' },
  USD: { symbol: '$', name: 'US Dollar', color: 'text-success-700', bgColor: 'bg-success-50' },
  EUR: { symbol: '€', name: 'Euro', color: 'text-info-700', bgColor: 'bg-info-50' },
  GBP: { symbol: '£', name: 'British Pound', color: 'text-accent-700', bgColor: 'bg-accent-50' },
  SAR: { symbol: 'ر.س', name: 'Saudi Riyal', color: 'text-primary-700 dark:text-neutral-200', bgColor: 'bg-primary-50 dark:bg-primary-800/40' },
  QAR: { symbol: 'ر.ق', name: 'Qatari Riyal', color: 'text-info-700 dark:text-info-300', bgColor: 'bg-info-50 dark:bg-info-500/10' },
  KWD: { symbol: 'د.ك', name: 'Kuwaiti Dinar', color: 'text-warning-700 dark:text-warning-300', bgColor: 'bg-warning-50 dark:bg-warning-500/10' },
  CHF: { symbol: 'CHF', name: 'Swiss Franc', color: 'text-warning-700 dark:text-warning-300', bgColor: 'bg-warning-50 dark:bg-warning-500/10' },
  JPY: { symbol: '¥', name: 'Japanese Yen', color: 'text-accent-700', bgColor: 'bg-accent-50' },
  INR: { symbol: '₹', name: 'Indian Rupee', color: 'text-primary-700', bgColor: 'bg-primary-50' },
};

const availableCurrencies = ['AED', 'USD', 'EUR', 'GBP', 'SAR', 'QAR', 'KWD', 'CHF', 'JPY', 'INR'];

// ============================================================================
// TYPES
// ============================================================================

type EntityType = 'HOLDING' | 'SUBSIDIARY' | 'BRANCH' | 'REPRESENTATIVE' | 'JOINT_VENTURE' | 'ASSOCIATE' | 'SPV' | 'TREASURY_CENTER';
type LimitType = 'OVERDRAFT' | 'INTRADAY' | 'DAYLIGHT' | 'OVERNIGHT' | 'AGGREGATE' | 'TRANSACTION' | 'DAILY' | 'MONTHLY';

interface Corporate {
  id: string;
  legalName: string;
  shortName?: string;
}

interface LegalEntity {
  id: string;
  entityCode: string;
  entityName: string;
  parentEntityId?: string;
  hierarchyLevel: number;
  entityType: EntityType;
  countryCode?: string;
  functionalCurrency: string;
  isBankCustomer?: boolean;
  isTreasuryCenter: boolean;
  status: string;
  corporateId: string;
}

interface VirtualAccount {
  id: string;
  vaNumber: string;
  vaName: string;
  currencyCode: string;
  owningEntityId?: string;
  owningEntityCode?: string;
  currentBalance: number;
  availableBalance: number;
  status: string;
}

interface InternalLimitResponse {
  id: string;
  limitName: string;
  limitType: 'GROUP' | 'ENTITY' | 'VA';
  corporateId: string;
  targetType: string;
  targetId: string;
  targetName?: string;
  parentLimitId?: string;
  allocatedToChildren: number;
  unallocatedAmount: number;
  limitAmount: number;
  currency: string;
  utilizedAmount: number;
  availableAmount: number;
  heldAmount: number;
  utilizationPercent: number;
  warningThresholdPercent: number;
  criticalThresholdPercent: number;
  isAtWarningLevel: boolean;
  isAtCriticalLevel: boolean;
  isBreached: boolean;
  isHardLimit: boolean;
  requiresApproval: boolean;
  approvalThresholdPercent?: number;
  needsApprovalNow: boolean;
  effectiveFrom: string;
  effectiveTo?: string;
  status: string;
  approvedBy?: string;
  notes?: string;
  createdAt: string;
  subLimitType?: LimitType;
}

interface CurrencyLimitTotals {
  currency: string;
  externalTotal: number;
  externalUtilized: number;
  externalAvailable: number;
  groupTotal: number;
  groupAllocated: number;
  groupUnallocated: number;
  entityUtilized: number;
  entityCount: number;
  vaAllocated: number;
  vaCount: number;
}

// EntityLimitAllocationData is imported from '../components/credit/EntityAllocationModal'

// ============================================================================
// ENTITY TYPE CONFIG
// ============================================================================

const entityTypeConfig: Record<EntityType, { label: string; icon: React.ElementType; color: string; bgColor: string }> = {
  HOLDING: { label: 'Holding', icon: Crown, color: 'text-primary-700 dark:text-neutral-200', bgColor: 'bg-primary-100 dark:bg-primary-700' },
  SUBSIDIARY: { label: 'Subsidiary', icon: Building2, color: 'text-info-700 dark:text-info-300', bgColor: 'bg-info-50 dark:bg-info-500/10' },
  BRANCH: { label: 'Branch', icon: Building, color: 'text-info-600 dark:text-info-300', bgColor: 'bg-info-50 dark:bg-info-500/10' },
  REPRESENTATIVE: { label: 'Representative', icon: Users, color: 'text-neutral-700 dark:text-neutral-200', bgColor: 'bg-neutral-100 dark:bg-primary-800' },
  JOINT_VENTURE: { label: 'Joint Venture', icon: ArrowLeftRight, color: 'text-warning-700 dark:text-warning-300', bgColor: 'bg-warning-50 dark:bg-warning-500/10' },
  ASSOCIATE: { label: 'Associate', icon: Briefcase, color: 'text-success-700 dark:text-success-300', bgColor: 'bg-success-50 dark:bg-success-500/10' },
  SPV: { label: 'SPV', icon: FlaskConical, color: 'text-error-700 dark:text-error-300', bgColor: 'bg-error-50 dark:bg-error-500/10' },
  TREASURY_CENTER: { label: 'Treasury', icon: Landmark, color: 'text-accent-700 dark:text-accent-300', bgColor: 'bg-accent-100 dark:bg-accent-500/20' },
};

const limitTypeOptions: { value: LimitType; label: string; description: string }[] = [
  { value: 'OVERDRAFT', label: 'Overdraft', description: 'Standard overdraft facility' },
  { value: 'INTRADAY', label: 'Intraday', description: 'Daylight borrowing - cleared by EOD' },
  { value: 'AGGREGATE', label: 'Aggregate', description: 'Combined limit across all types' },
  { value: 'TRANSACTION', label: 'Per-Transaction', description: 'Maximum per single transaction' },
  { value: 'DAILY', label: 'Daily Cap', description: 'Maximum daily cumulative usage' },
  { value: 'MONTHLY', label: 'Monthly Cap', description: 'Maximum monthly cumulative usage' },
];

// ============================================================================
// API SERVICE - Enhanced for Multi-Currency
// ============================================================================

const API_BASE = '/api/v1';

const corporateApi = {
  getAll: async (): Promise<Corporate[]> => {
    const response = await fetch(`${API_BASE}/corporates`);
    if (!response.ok) throw new Error('Failed to fetch corporates');
    const result = await response.json();
    return result.data || [];
  },
};

const legalEntityApi = {
  getByCorporate: async (corporateId: string): Promise<LegalEntity[]> => {
    const response = await fetch(`${API_BASE}/legal-entities/corporate/${corporateId}`);
    if (!response.ok) return [];
    const result = await response.json();
    return result.data || [];
  },
};

const vaApi = {
  getByEntity: async (entityId: string): Promise<VirtualAccount[]> => {
    try {
      const response = await fetch(`${API_BASE}/virtual-accounts/entity/${entityId}`);
      if (!response.ok) return [];
      const result = await response.json();
      return result.data || [];
    } catch { return []; }
  },
};

const creditLimitApi = {
  // GROUP LIMITS (Multi-Currency)
  getAllGroupLimits: async (corporateId: string): Promise<InternalLimitResponse[]> => {
    try {
      const response = await fetch(`${API_BASE}/credit/limits/internal/corporate/${corporateId}/groups`);
      if (!response.ok) return [];
      const result = await response.json();
      return result.data || [];
    } catch { return []; }
  },

  createGroupLimit: async (data: {
    corporateId: string;
    limitName: string;
    amount: number;
    currency: string;
    approvedBy: string;
    hardLimit: boolean;
  }): Promise<InternalLimitResponse> => {
    const response = await fetch(`${API_BASE}/credit/limits/internal/group`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data),
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.message || 'Failed to create group limit');
    return result.data;
  },

  updateGroupLimitAmount: async (limitId: string, newAmount: number, updatedBy: string): Promise<InternalLimitResponse> => {
    const response = await fetch(
      `${API_BASE}/credit/limits/internal/group/${limitId}/amount?newAmount=${newAmount}&updatedBy=${encodeURIComponent(updatedBy)}`,
      { method: 'PUT' }
    );
    const result = await response.json();
    if (!response.ok) throw new Error(result.message || 'Failed to update group limit');
    return result.data;
  },

  // ENTITY LIMITS (Multi-Currency)
  getAllEntityLimits: async (entityId: string): Promise<InternalLimitResponse[]> => {
    try {
      const response = await fetch(`${API_BASE}/credit/limits/internal/entity/${entityId}/all`);
      if (!response.ok) return [];
      const result = await response.json();
      return result.data || [];
    } catch { return []; }
  },

  createEntitySubLimitMultiCurrency: async (data: {
    corporateId: string;
    entityId: string;
    limitName: string;
    amount: number;
    currency: string;
    approvedBy: string;
    hardLimit: boolean;
    requiresApproval: boolean;
    approvalThreshold?: number;
  }): Promise<InternalLimitResponse> => {
    const response = await fetch(`${API_BASE}/credit/limits/internal/entity/v2`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data),
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.message || 'Failed to create entity limit');
    return result.data;
  },

  updateEntitySubLimitAmount: async (limitId: string, newAmount: number, updatedBy: string): Promise<InternalLimitResponse> => {
    const response = await fetch(
      `${API_BASE}/credit/limits/internal/entity/${limitId}/amount?newAmount=${newAmount}&updatedBy=${encodeURIComponent(updatedBy)}`,
      { method: 'PUT' }
    );
    const result = await response.json();
    if (!response.ok) throw new Error(result.message || 'Failed to update entity limit');
    return result.data;
  },

  deleteEntitySubLimit: async (limitId: string, reason: string): Promise<void> => {
    const response = await fetch(
      `${API_BASE}/credit/limits/internal/entity/${limitId}?reason=${encodeURIComponent(reason)}`,
      { method: 'DELETE' }
    );
    if (!response.ok) {
      const result = await response.json();
      throw new Error(result.message || 'Failed to delete entity limit');
    }
  },

  // VA LIMITS
  getVaLimitsByEntity: async (entityId: string): Promise<InternalLimitResponse[]> => {
    try {
      const response = await fetch(`${API_BASE}/credit/limits/internal/entity/${entityId}/va-limits`);
      if (!response.ok) return [];
      const result = await response.json();
      return result.data || [];
    } catch { return []; }
  },

  createVaLimit: async (data: {
    corporateId: string;
    vaId: string;
    limitName: string;
    amount: number;
    approvedBy: string;
    hardLimit: boolean;
  }): Promise<InternalLimitResponse> => {
    const response = await fetch(`${API_BASE}/credit/limits/internal/va`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data),
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.message || 'Failed to create VA limit');
    return result.data;
  },

  deleteVaLimit: async (limitId: string, reason: string): Promise<void> => {
    const response = await fetch(
      `${API_BASE}/credit/limits/internal/va/${limitId}?reason=${encodeURIComponent(reason)}`,
      { method: 'DELETE' }
    );
    if (!response.ok) {
      const result = await response.json();
      throw new Error(result.message || 'Failed to delete VA limit');
    }
  },

  // DASHBOARD
  getTotalsByCurrency: async (corporateId: string): Promise<Record<string, CurrencyLimitTotals>> => {
    try {
      const response = await fetch(`${API_BASE}/credit/limits/dashboard/corporate/${corporateId}/totals-by-currency`);
      if (!response.ok) return {};
      const result = await response.json();
      return result.data || {};
    } catch { return {}; }
  },

  getExternalCeiling: async (entityId: string): Promise<number | null> => {
    try {
      const response = await fetch(`${API_BASE}/credit/limits/external/entity/${entityId}/ceiling`);
      if (!response.ok) return null;
      const result = await response.json();
      return result.data?.externalCeiling || null;
    } catch { return null; }
  },

  getInternalByCorporate: async (corporateId: string): Promise<InternalLimitResponse[]> => {
    try {
      const response = await fetch(`${API_BASE}/credit/limits/internal/corporate/${corporateId}`);
      if (!response.ok) return [];
      const result = await response.json();
      return result.data || [];
    } catch { return []; }
  },
};

// Component code continues in next part...

// ============================================================================
// MULTI-CURRENCY GROUP LIMITS CARD
// ============================================================================

const MultiCurrencyGroupLimitsCard: React.FC<{
  groupLimits: InternalLimitResponse[];
  onEdit: (limit: InternalLimitResponse) => void;
  onAddCurrency: () => void;
}> = ({ groupLimits, onEdit, onAddCurrency }) => {
  const [selectedCurrency, setSelectedCurrency] = useState<string | null>(null);
  const currencies = groupLimits.map(l => l.currency).sort();
  
  useEffect(() => {
    if (currencies.length > 0 && !selectedCurrency) {
      setSelectedCurrency(currencies[0]);
    }
  }, [currencies, selectedCurrency]);

  const selectedLimit = selectedCurrency ? groupLimits.find(l => l.currency === selectedCurrency) : null;

  if (groupLimits.length === 0) {
    return (
      <div className="bg-white rounded-xl border-2 border-dashed border-primary-300 p-8 text-center mb-6 dark:bg-primary-900">
        <Target className="w-12 h-12 text-primary-300 mx-auto mb-4" />
        <h3 className="text-lg font-semibold text-neutral-900 mb-2 dark:text-neutral-50">No Group Limits Set</h3>
        <p className="text-sm text-neutral-500 mb-4 max-w-md mx-auto dark:text-neutral-400">
          Create corporate-wide internal credit limits for each currency.
        </p>
        <button type="button" onClick={onAddCurrency} className="px-4 py-2 bg-primary-900 text-white rounded-lg text-sm font-medium hover:bg-primary-800">
          <Plus className="w-4 h-4 inline mr-1" /> Create Group Limit
        </button>
      </div>
    );
  }

  const limitAmount = safeNumber(selectedLimit?.limitAmount);
  const allocated = safeNumber(selectedLimit?.allocatedToChildren);
  const unallocated = safeNumber(selectedLimit?.unallocatedAmount);
  const utilized = safeNumber(selectedLimit?.utilizedAmount);
  const available = safeNumber(selectedLimit?.availableAmount);
  const allocationPct = safePercent(allocated, limitAmount);

  return (
    <div className="bg-gradient-to-r from-primary-50 via-primary-100/50 to-primary-50 rounded-xl border border-primary-200 p-6 mb-6 dark:border-primary-700">
      <div className="flex items-start justify-between mb-4">
        <div className="flex items-center gap-4">
          <div className="p-3 bg-primary-100 rounded-xl dark:bg-primary-700">
            <Layers className="w-8 h-8 text-primary-700 dark:text-neutral-200" />
          </div>
          <div>
            <p className="text-xs text-primary-600 uppercase font-semibold tracking-wide dark:text-primary-200">Group Internal Limits</p>
            <p className="text-sm text-neutral-600 mt-0.5 dark:text-neutral-300">{groupLimits.length} {groupLimits.length === 1 ? 'currency' : 'currencies'} configured</p>
          </div>
        </div>
        <button type="button" onClick={onAddCurrency} className="px-3 py-1.5 bg-primary-100 text-primary-700 rounded-lg text-sm font-medium hover:bg-primary-200 flex items-center gap-1 dark:bg-primary-700 dark:text-neutral-200">
          <Plus className="w-4 h-4" /> Add Currency
        </button>
      </div>

      {/* Currency Tabs */}
      <div className="flex gap-2 mb-4 flex-wrap">
        {currencies.map(currency => {
          const limit = groupLimits.find(l => l.currency === currency);
          const isSelected = currency === selectedCurrency;
          const config = currencyConfig[currency] || { color: 'text-neutral-700 dark:text-neutral-200', bgColor: 'bg-neutral-100 dark:bg-primary-800' };
          const utilizationPct = safePercent(limit?.utilizedAmount, limit?.limitAmount);
          
          return (
            <button
              type="button"
              key={currency}
              onClick={() => setSelectedCurrency(currency)}
              className={cn(
                "px-4 py-2 rounded-lg text-sm font-medium transition-all flex items-center gap-2",
                isSelected ? "bg-white shadow-md border-2 border-primary-500 dark:bg-primary-900" : "bg-white/60 hover:bg-white border border-transparent dark:bg-primary-900/60"
              )}
            >
              <span className={cn("font-bold", config.color)}>{currency}</span>
              <span className="text-neutral-600 dark:text-neutral-300"><Amount value={safeNumber(limit?.limitAmount)} currency={currency} showCurrency={false} /></span>
              {utilizationPct > 80 && <AlertTriangle className={cn("w-3.5 h-3.5", utilizationPct > 95 ? "text-error-500" : "text-warning-500")} />}
            </button>
          );
        })}
      </div>

      {/* Selected Currency Details */}
      {selectedLimit && (
        <>
          <div className="bg-white/80 rounded-xl p-4 mb-4 dark:bg-primary-900/80">
            <div className="flex items-center justify-between mb-3">
              <div className="flex items-center gap-3">
                <span className={cn("text-xl font-bold", currencyConfig[selectedCurrency!]?.color || 'text-neutral-900 dark:text-neutral-50')}>
                  <Amount value={limitAmount} currency={selectedCurrency!} />
                </span>
                {selectedLimit.isHardLimit ? (
                  <span className="px-2 py-0.5 rounded-full text-xs bg-error-50 text-error-700 flex items-center gap-1 dark:bg-error-500/10 dark:text-error-300"><Lock className="w-3 h-3" /> Hard</span>
                ) : (
                  <span className="px-2 py-0.5 rounded-full text-xs bg-warning-50 text-warning-700 flex items-center gap-1 dark:bg-warning-500/10 dark:text-warning-300"><Unlock className="w-3 h-3" /> Soft</span>
                )}
              </div>
              <button type="button" onClick={() => onEdit(selectedLimit)} className="p-2 hover:bg-neutral-100 rounded-lg dark:hover:bg-primary-800"><Edit className="w-5 h-5 text-primary-600 dark:text-primary-200" /></button>
            </div>

            <div className="mb-3">
              <div className="w-full bg-neutral-200 rounded-full h-3 dark:bg-primary-800">
                <div className={cn("h-3 rounded-full", allocationPct > 95 ? "bg-error-500" : allocationPct > 80 ? "bg-warning-500" : "bg-primary-600")} style={{ width: `${allocationPct}%` }} />
              </div>
              <div className="flex justify-between text-xs mt-1">
                <span className="text-primary-700 font-medium dark:text-neutral-200"><Amount value={allocated} currency={selectedCurrency!} /> allocated ({allocationPct.toFixed(0)}%)</span>
                <span className="text-success-700 font-medium dark:text-success-300"><Amount value={unallocated} currency={selectedCurrency!} /> avail.</span>
              </div>
            </div>
          </div>

          <div className="grid grid-cols-4 gap-2">
            <div className="p-2 bg-white/60 rounded-lg min-w-0 dark:bg-primary-900/60">
              <p className="text-xs text-neutral-500 dark:text-neutral-400">Allocated</p>
              <p className="text-sm font-bold text-primary-700 truncate dark:text-neutral-200"><Amount value={allocated} currency={selectedCurrency!} /></p>
            </div>
            <div className="p-2 bg-white/60 rounded-lg min-w-0 dark:bg-primary-900/60">
              <p className="text-xs text-neutral-500 dark:text-neutral-400">Unallocated</p>
              <p className="text-sm font-bold text-success-700 truncate dark:text-success-300"><Amount value={unallocated} currency={selectedCurrency!} /></p>
            </div>
            <div className="p-2 bg-white/60 rounded-lg min-w-0 dark:bg-primary-900/60">
              <p className="text-xs text-neutral-500 dark:text-neutral-400">Utilized</p>
              <p className="text-sm font-bold text-warning-700 truncate dark:text-warning-300"><Amount value={utilized} currency={selectedCurrency!} /></p>
            </div>
            <div className="p-2 bg-white/60 rounded-lg min-w-0 dark:bg-primary-900/60">
              <p className="text-xs text-neutral-500 dark:text-neutral-400">Available</p>
              <p className="text-sm font-bold text-info-700 truncate dark:text-info-300"><Amount value={available} currency={selectedCurrency!} /></p>
            </div>
          </div>
        </>
      )}
    </div>
  );
};

// ============================================================================
// STATS CARDS
// ============================================================================

const StatsCards: React.FC<{
  groupLimits: InternalLimitResponse[];
  entityCount: number;
  allocatedEntityCount: number;
  loading: boolean;
}> = ({ groupLimits, entityCount, allocatedEntityCount, loading }) => {
  const totalGroupLimit = groupLimits.reduce((sum, l) => sum + safeNumber(l.limitAmount), 0);
  const totalAllocated = groupLimits.reduce((sum, l) => sum + safeNumber(l.allocatedToChildren), 0);
  const totalUtilized = groupLimits.reduce((sum, l) => sum + safeNumber(l.utilizedAmount), 0);
  const currencyCount = groupLimits.length;
  const entityPercent = entityCount > 0 ? Math.round((allocatedEntityCount / entityCount) * 100) : 0;
  const allocationPercent = safePercent(totalAllocated, totalGroupLimit);
  const utilizationPercent = safePercent(totalUtilized, totalGroupLimit);

  return (
    <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
      <div className="bg-white rounded-xl p-4 shadow-sm border border-neutral-100 hover:shadow-md transition-shadow animate-fade-in dark:bg-primary-900 dark:border-primary-800/60" style={{ animationDelay: '0.1s' }}>
        <div className="flex items-start justify-between">
          <div className="flex-1">
            <p className="label">Currencies</p>
            <p className="stat-value-sm mt-1">{loading ? <Loader2 className="w-6 h-6 animate-spin text-primary-600 dark:text-primary-200" /> : currencyCount}</p>
            <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">{currencyCount > 0 ? groupLimits.map(l => l.currency).join(', ') : 'No group limits'}</p>
          </div>
          <StatusIconBadge tone="primary" icon={DollarSign} className="dark:bg-primary-700" />
        </div>
      </div>

      <div className="bg-white rounded-xl p-4 shadow-sm border border-neutral-100 hover:shadow-md transition-shadow animate-fade-in dark:bg-primary-900 dark:border-primary-800/60" style={{ animationDelay: '0.15s' }}>
        <div className="flex items-start justify-between">
          <div className="flex-1">
            <p className="label">Total Limits</p>
            <p className="stat-value-sm mt-1">{loading ? <Loader2 className="w-6 h-6 animate-spin text-primary-600 dark:text-primary-200" /> : currencyCount > 0 ? `${currencyCount} currencies` : 'Not Set'}</p>
            <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">{allocationPercent.toFixed(0)}% allocated overall</p>
          </div>
          <StatusIconBadge tone="info" icon={Target} className="dark:bg-info-500/20" />
        </div>
        {currencyCount > 0 && (
          <div className="mt-3">
            <div className="w-full bg-neutral-200 rounded-full h-2 dark:bg-primary-800">
              <div className={cn('h-2 rounded-full', allocationPercent > 90 ? 'bg-error-500' : 'bg-primary-600')} style={{ width: `${allocationPercent}%` }} />
            </div>
          </div>
        )}
      </div>

      <div className="bg-white rounded-xl p-4 shadow-sm border border-neutral-100 hover:shadow-md transition-shadow animate-fade-in dark:bg-primary-900 dark:border-primary-800/60" style={{ animationDelay: '0.2s' }}>
        <div className="flex items-start justify-between">
          <div className="flex-1">
            <p className="label">Entity Limits</p>
            <p className="stat-value-sm mt-1">{loading ? <Loader2 className="w-6 h-6 animate-spin text-primary-600 dark:text-primary-200" /> : `${allocatedEntityCount} / ${entityCount}`}</p>
            <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">{entityCount > 0 ? `${entityPercent}% entities allocated` : 'No entities'}</p>
          </div>
          <StatusIconBadge tone="success" icon={Building2} className="dark:bg-success-500/20" />
        </div>
      </div>

      <div className="bg-white rounded-xl p-4 shadow-sm border border-neutral-100 hover:shadow-md transition-shadow animate-fade-in dark:bg-primary-900 dark:border-primary-800/60" style={{ animationDelay: '0.25s' }}>
        <div className="flex items-start justify-between">
          <div className="flex-1">
            <p className="label">Utilization</p>
            <p className={cn('mt-1', utilizationPercent > 80 ? 'stat-value-warning' : 'stat-value-success')}>{loading ? <Loader2 className="w-6 h-6 animate-spin text-primary-600 dark:text-primary-200" /> : `${utilizationPercent.toFixed(0)}%`}</p>
            <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">across all currencies</p>
          </div>
          <div className={cn('w-10 h-10 rounded-xl flex items-center justify-center', utilizationPercent > 80 ? 'bg-warning-100 dark:bg-warning-500/20' : 'bg-success-100 dark:bg-success-500/20')}>
            {utilizationPercent > 80 ? <AlertTriangle className="w-5 h-5 text-warning-600 dark:text-warning-300" /> : <CheckCircle className="w-5 h-5 text-success-600 dark:text-success-300" />}
          </div>
        </div>
        {currencyCount > 0 && (
          <div className="mt-3">
            <div className="w-full bg-neutral-200 rounded-full h-2 dark:bg-primary-800">
              <div className={cn('h-2 rounded-full', utilizationPercent > 90 ? 'bg-error-500' : utilizationPercent > 80 ? 'bg-warning-500' : 'bg-success-500')} style={{ width: `${utilizationPercent}%` }} />
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

// ============================================================================
// ENTITY TREE NODE - Enhanced with Multi-Currency
// ============================================================================

const EntityTreeNode: React.FC<{
  entity: LegalEntity;
  entities: LegalEntity[];
  entityLimitsMap: Record<string, Record<string, InternalLimitResponse>>;
  externalCeilings: Record<string, number>;
  expandedIds: Set<string>;
  groupLimits: InternalLimitResponse[];
  level: number;
  onToggle: (id: string) => void;
  onAllocate: (entity: LegalEntity, currency?: string) => void;
  onEdit: (entity: LegalEntity, currency: string) => void;
  onDelete: (entityId: string, currency: string) => void;
  onManageVaLimits: (entity: LegalEntity, currency: string) => void;
}> = ({ entity, entities, entityLimitsMap, externalCeilings, expandedIds, groupLimits, level, onToggle, onAllocate, onEdit, onDelete, onManageVaLimits }) => {
  const isExpanded = expandedIds.has(entity.id);
  const children = entities.filter(e => e.parentEntityId === entity.id);
  const hasChildren = children.length > 0;

  const entityLimits = entityLimitsMap[entity.id] || {};
  const limitCurrencies = Object.keys(entityLimits).sort();
  const hasAnyLimit = limitCurrencies.length > 0;

  const availableGroupCurrencies = groupLimits.map(l => l.currency);
  const missingCurrencies = availableGroupCurrencies.filter(c => !entityLimits[c]);

  const externalCeiling = externalCeilings[entity.id];

  const typeConfig = entityTypeConfig[entity.entityType] || entityTypeConfig.SUBSIDIARY;
  const TypeIcon = typeConfig.icon;

  return (
    <div>
      <div
        className={cn(
          "py-3 px-4 hover:bg-neutral-50 transition-colors border-b border-neutral-100 last:border-0 dark:hover:bg-primary-800/50 dark:border-primary-800/60",
          !hasAnyLimit && "bg-neutral-50/50",
          entity.isTreasuryCenter && "border-l-4 border-l-accent-500"
        )}
        style={{ paddingLeft: `${16 + level * 28}px` }}
      >
        <div className="flex items-center gap-3">
          {hasChildren ? (
            <button type="button" onClick={() => onToggle(entity.id)} className="p-1 hover:bg-neutral-200 rounded">
              {isExpanded ? <ChevronDown className="w-4 h-4 text-neutral-500 dark:text-neutral-400" /> : <ChevronRight className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />}
            </button>
          ) : <span className="w-6" />}

          <div className={cn("p-2 rounded-lg", typeConfig.bgColor)}>
            <TypeIcon className={cn("w-4 h-4", typeConfig.color)} />
          </div>

          <div className="flex-1 min-w-[180px]">
            <div className="flex items-center gap-2">
              <span className="text-sm font-medium text-neutral-900 dark:text-neutral-50">{entity.entityCode}</span>
              {entity.isBankCustomer && <span className="px-2 py-0.5 rounded-full text-xs bg-success-50 text-success-700 dark:bg-success-500/10 dark:text-success-300"><Wallet className="w-3 h-3 inline" /> Bank</span>}
              {entity.isTreasuryCenter && <span className="px-2 py-0.5 rounded-full text-xs bg-accent-100 text-accent-700 dark:bg-accent-500/20 dark:text-accent-300">Treasury</span>}
            </div>
            <p className="text-xs text-neutral-500 truncate max-w-[200px] dark:text-neutral-400">{entity.entityName}</p>
          </div>

          <div className="w-[100px] text-right">
            <p className="text-xs text-neutral-400 uppercase dark:text-neutral-500">External</p>
            {entity.isBankCustomer && externalCeiling ? (
              <p className="text-sm font-medium text-info-700 dark:text-info-300">{formatCurrency(externalCeiling, entity.functionalCurrency)}</p>
            ) : <p className="text-sm text-neutral-400 dark:text-neutral-500">—</p>}
          </div>

          <div className="w-[100px]">
            {missingCurrencies.length > 0 && (
              <button type="button" onClick={() => onAllocate(entity)} className="text-sm text-primary-600 hover:text-primary-700 font-medium flex items-center gap-1 dark:text-primary-200 dark:hover:text-neutral-200">
                <Plus className="w-3 h-3" /> Add Limit
              </button>
            )}
          </div>
        </div>

        {hasAnyLimit && (
          <div className="mt-3 ml-10 flex flex-wrap gap-2">
            {limitCurrencies.map(currency => {
              const limit = entityLimits[currency];
              const limitAmount = safeNumber(limit.limitAmount);
              const utilized = safeNumber(limit.utilizedAmount);
              const utilizationPct = safePercent(utilized, limitAmount);
              const isNearLimit = limit.isAtWarningLevel || utilizationPct >= 80;
              const isBreached = limit.isBreached || utilizationPct >= 100;
              const config = currencyConfig[currency] || { color: 'text-neutral-700 dark:text-neutral-200', bgColor: 'bg-neutral-100 dark:bg-primary-800' };

              return (
                <div key={currency} className={cn(
                  "px-3 py-2 rounded-lg border flex items-center gap-3",
                  isBreached ? "bg-error-50 border-error-200 dark:bg-error-500/10 dark:border-error-500/30" : isNearLimit ? "bg-warning-50 border-warning-200 dark:bg-warning-500/10 dark:border-warning-500/30" : "bg-white border-neutral-200 dark:bg-primary-900 dark:border-primary-800"
                )}>
                  <div>
                    <div className="flex items-center gap-2">
                      <span className={cn("font-bold text-sm", config.color)}>{currency}</span>
                      {(isBreached || isNearLimit) && <AlertTriangle className={cn("w-3.5 h-3.5", isBreached ? "text-error-500" : "text-warning-500")} />}
                    </div>
                    <p className="text-sm font-medium text-neutral-900 dark:text-neutral-50">{formatCurrency(limitAmount, currency)}</p>
                    <div className="w-20 bg-neutral-200 rounded-full h-1.5 mt-1 dark:bg-primary-800">
                      <div className={cn("h-1.5 rounded-full", isBreached ? "bg-error-500" : isNearLimit ? "bg-warning-500" : "bg-success-500")} style={{ width: `${Math.min(utilizationPct, 100)}%` }} />
                    </div>
                    <p className="text-xs text-neutral-500 mt-0.5 dark:text-neutral-400">{utilizationPct.toFixed(0)}% utilized</p>
                  </div>
                  <div className="flex flex-col gap-1">
                    <button type="button" onClick={() => onEdit(entity, currency)} className="p-1.5 hover:bg-neutral-100 rounded dark:hover:bg-primary-800" title="Edit limit"><Edit className="w-3.5 h-3.5 text-neutral-500 dark:text-neutral-400" /></button>
                    <button type="button" onClick={() => onManageVaLimits(entity, currency)} className="p-1.5 hover:bg-primary-50 rounded dark:hover:bg-primary-800/40" title="Manage VA limits"><CreditCard className="w-3.5 h-3.5 text-primary-500" /></button>
                    <button type="button" onClick={() => onDelete(entity.id, currency)} className="p-1.5 hover:bg-error-50 rounded dark:hover:bg-error-500/10" title="Delete limit"><Trash2 className="w-3.5 h-3.5 text-error-500" /></button>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {hasChildren && isExpanded && children.sort((a, b) => a.hierarchyLevel - b.hierarchyLevel || a.entityCode.localeCompare(b.entityCode)).map(child => (
        <EntityTreeNode
          key={child.id}
          entity={child}
          entities={entities}
          entityLimitsMap={entityLimitsMap}
          externalCeilings={externalCeilings}
          expandedIds={expandedIds}
          groupLimits={groupLimits}
          level={level + 1}
          onToggle={onToggle}
          onAllocate={onAllocate}
          onEdit={onEdit}
          onDelete={onDelete}
          onManageVaLimits={onManageVaLimits}
        />
      ))}
    </div>
  );
};

// ============================================================================
// GROUP LIMIT MODAL
// ============================================================================

const GroupLimitModal: React.FC<{
  isOpen: boolean;
  onClose: () => void;
  existingLimit: InternalLimitResponse | null;
  existingCurrencies: string[];
  corporateId: string;
  onSave: (data: { limitName: string; amount: number; currency: string; hardLimit: boolean; approvedBy: string }) => Promise<void>;
}> = ({ isOpen, onClose, existingLimit, existingCurrencies, corporateId, onSave }) => {
  const [limitName, setLimitName] = useState('');
  const [amount, setAmount] = useState<number>(0);
  const [currency, setCurrency] = useState('AED');
  const [hardLimit, setHardLimit] = useState(true);
  const [approvedBy, setApprovedBy] = useState('CFO');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const isEditing = !!existingLimit;

  useEffect(() => {
    if (!isOpen) return; // Don't run when modal is closed
    
    if (existingLimit) {
      setLimitName(existingLimit.limitName || '');
      setAmount(safeNumber(existingLimit.limitAmount));
      setCurrency(existingLimit.currency || 'AED');
      setHardLimit(existingLimit.isHardLimit);
      setApprovedBy(existingLimit.approvedBy || 'CFO');
    } else {
      const firstAvailable = availableCurrencies.find(c => !existingCurrencies.includes(c)) || 'AED';
      setLimitName(`Corporate Group Credit Limit - ${firstAvailable}`);
      setAmount(0);
      setCurrency(firstAvailable);
      setHardLimit(true);
      setApprovedBy('CFO');
    }
    setError(null);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen, existingLimit?.id]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!limitName || amount <= 0) return;
    setSaving(true);
    setError(null);
    try {
      await onSave({ limitName, amount, currency, hardLimit, approvedBy });
      onClose();
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Failed to save');
    } finally {
      setSaving(false);
    }
  };

  const minAmount = existingLimit ? safeNumber(existingLimit.allocatedToChildren) : 0;

  // Tier 5 Design System Unification (2026-05-13): migrated from a
  // hand-rolled `<div className="fixed inset-0 …">` wrapper to the shared
  // <Modal>. Inherits escape-key handler, body-scroll-lock, standardised
  // backdrop + max-width tokens. Header (title + subtitle) and close
  // button come from Modal's props.
  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      size="md"
      title={isEditing ? `Update ${currency} Group Limit` : 'Create Group Limit'}
      subtitle={isEditing ? 'Modify existing currency limit' : 'Add a new currency to group limits'}
    >
        <form onSubmit={handleSubmit} className="space-y-4">
          {!isEditing && existingCurrencies.length > 0 && (
            <div className="bg-info-50 rounded-lg p-4 dark:bg-info-500/10">
              <p className="text-sm text-info-700 dark:text-info-300"><Info className="w-4 h-4 inline mr-1" />Existing group limits: {existingCurrencies.join(', ')}</p>
            </div>
          )}

          {error && <div className="bg-error-50 rounded-lg p-4 dark:bg-error-500/10"><p className="text-sm text-error-700 dark:text-error-300"><AlertTriangle className="w-4 h-4 inline mr-1" />{error}</p></div>}

          <div>
            <label className="field-label block mb-1">Limit Name</label>
            <input type="text" value={limitName} onChange={(e) => setLimitName(e.target.value)} className="w-full px-3 py-2 border border-neutral-300 rounded-lg focus:ring-2 focus:ring-primary-500 dark:border-primary-700" required />
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="field-label block mb-1">Currency</label>
              <select value={currency} onChange={(e) => { setCurrency(e.target.value); setLimitName(`Corporate Group Credit Limit - ${e.target.value}`); }} disabled={isEditing} className={cn("w-full px-3 py-2 border border-neutral-300 rounded-lg focus:ring-2 focus:ring-primary-500 dark:border-primary-700", isEditing && "bg-neutral-100 cursor-not-allowed dark:bg-primary-800")}>
                {(isEditing ? [existingLimit!.currency] : availableCurrencies.filter(c => !existingCurrencies.includes(c))).map(c => (
                  <option key={c} value={c}>{c} - {currencyConfig[c]?.name || c}</option>
                ))}
              </select>
            </div>
            <div>
              <label className="field-label block mb-1">Amount {minAmount > 0 && <span className="text-neutral-400 dark:text-neutral-500">(min: {formatCurrency(minAmount, currency)})</span>}</label>
              <input type="number" value={amount} onChange={(e) => setAmount(e.target.value === '' ? 0 : parseFloat(e.target.value))} min={minAmount} step={1000} className="w-full px-3 py-2 border border-neutral-300 rounded-lg focus:ring-2 focus:ring-primary-500 dark:border-primary-700" required />
            </div>
          </div>

          <div className="p-4 bg-neutral-50 rounded-lg dark:bg-primary-950">
            <p className="field-label mb-3">Limit Control</p>
            <div className="flex gap-4">
              <label className="flex items-center gap-2 cursor-pointer">
                <input type="radio" checked={hardLimit} onChange={() => setHardLimit(true)} className="w-4 h-4 text-primary-600 dark:text-primary-200" />
                <Lock className="w-4 h-4 text-error-500" /><span className="text-sm">Hard Limit</span>
              </label>
              <label className="flex items-center gap-2 cursor-pointer">
                <input type="radio" checked={!hardLimit} onChange={() => setHardLimit(false)} className="w-4 h-4 text-primary-600 dark:text-primary-200" />
                <Unlock className="w-4 h-4 text-warning-500" /><span className="text-sm">Soft Limit</span>
              </label>
            </div>
          </div>

          <div>
            <label className="field-label block mb-1">Approved By</label>
            <input type="text" value={approvedBy} onChange={(e) => setApprovedBy(e.target.value)} className="w-full px-3 py-2 border border-neutral-300 rounded-lg focus:ring-2 focus:ring-primary-500 dark:border-primary-700" />
          </div>

          <div className="flex justify-end gap-3 pt-4 border-t border-neutral-200 dark:border-primary-800">
            <button type="button" onClick={onClose} className="px-4 py-2 border border-neutral-300 rounded-lg text-sm hover:bg-neutral-50 dark:border-primary-700 dark:hover:bg-primary-800/50">Cancel</button>
            <button type="submit" disabled={saving || !limitName || amount <= 0 || amount < minAmount} className="px-4 py-2 bg-primary-900 text-white rounded-lg text-sm disabled:opacity-50 hover:bg-primary-800">
              {saving && <Loader2 className="w-4 h-4 animate-spin inline mr-1" />}{isEditing ? 'Update' : 'Create'}
            </button>
          </div>
        </form>
    </Modal>
  );
};

// EntityAllocationModal is now imported from '../components/credit/EntityAllocationModal'

// ============================================================================
// VA LIMITS MODAL
// ============================================================================

const VaLimitsModal: React.FC<{
  isOpen: boolean;
  onClose: () => void;
  entity: LegalEntity | null;
  currency: string;
  entityLimit: InternalLimitResponse | null;
  corporateId: string;
  onRefresh: () => void;
}> = ({ isOpen, onClose, entity, currency, entityLimit, corporateId, onRefresh }) => {
  const [vas, setVas] = useState<VirtualAccount[]>([]);
  const [vaLimits, setVaLimits] = useState<Record<string, InternalLimitResponse>>({});
  const [loading, setLoading] = useState(false);
  const [selectedVaId, setSelectedVaId] = useState<string | null>(null);
  const [allocAmount, setAllocAmount] = useState<number>(0);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (isOpen && entity) { loadVas(); }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen, entity?.id, currency]);

  const loadVas = async () => {
    if (!entity) return;
    setLoading(true);
    try {
      const vaList = await vaApi.getByEntity(entity.id);
      const filteredVas = vaList.filter(va => va.currencyCode === currency);
      setVas(filteredVas);
      const limits = await creditLimitApi.getVaLimitsByEntity(entity.id);
      const limitMap: Record<string, InternalLimitResponse> = {};
      limits.filter(l => l.currency === currency).forEach(l => { limitMap[l.targetId] = l; });
      setVaLimits(limitMap);
    } catch { setError('Failed to load VAs'); }
    finally { setLoading(false); }
  };

  const handleAllocate = async () => {
    if (!selectedVaId || !entity || allocAmount <= 0) return;
    setSaving(true); setError(null);
    try {
      const va = vas.find(v => v.id === selectedVaId);
      await creditLimitApi.createVaLimit({ 
        corporateId, 
        vaId: selectedVaId, 
        limitName: `${va?.vaName || 'VA'} Credit Limit - ${currency}`, 
        amount: allocAmount, 
        approvedBy: 'Treasury', 
        hardLimit: true 
      });
      setSelectedVaId(null); setAllocAmount(0);
      await loadVas(); onRefresh();
    } catch (err: unknown) { setError(err instanceof Error ? err.message : 'Failed to allocate'); }
    finally { setSaving(false); }
  };

  const handleDeleteVaLimit = async (vaId: string) => {
    const limit = vaLimits[vaId];
    if (!limit || !confirm('Delete this VA limit?')) return;
    try { await creditLimitApi.deleteVaLimit(limit.id, 'User deleted'); await loadVas(); onRefresh(); }
    catch (err: unknown) { setError(err instanceof Error ? err.message : 'Failed to delete'); }
  };

  if (!entity) return null;

  const entityUnallocated = safeNumber(entityLimit?.unallocatedAmount);
  const vasWithoutLimit = vas.filter(va => !vaLimits[va.id]);
  const vasWithLimit = vas.filter(va => vaLimits[va.id]);

  // Tier 5 Design System Unification (2026-05-13): migrated from hand-rolled
  // modal wrapper to shared <Modal>. Footer slot is used for the Close
  // button; Modal handles the body-scroll-lock and escape-key behavior
  // that the hand-roll was missing.
  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      size="lg"
      title={`VA Limits - ${entity.entityCode}`}
      subtitle={`Allocate ${currency} credit limits to Virtual Accounts`}
      footer={
        <button type="button" onClick={onClose} className="w-full px-4 py-2 border border-neutral-300 rounded-lg text-sm hover:bg-neutral-50 dark:border-primary-700 dark:hover:bg-primary-800/50">Close</button>
      }
    >
        <div>
          {entityLimit && (
            <div className="bg-primary-50 rounded-lg p-4 mb-6 dark:bg-primary-800/40">
              <div className="flex justify-between items-center">
                <div>
                  <p className="text-sm font-medium text-primary-700 dark:text-neutral-200">Entity {currency} Limit</p>
                  <p className="stat-value-xs">{formatCurrency(entityLimit.limitAmount, currency)}</p>
                </div>
                <div className="text-right">
                  <p className="text-sm text-primary-600 dark:text-primary-200">Available for VAs</p>
                  {/* Phase 12 Task E: .stat-value-xs + semantic colour replaces the
                      raw `text-xl font-bold` (keeps the 20px scale of the sibling
                      .stat-value-xs figure). */}
                  <p className="stat-value-xs text-success-700 dark:text-success-300">{formatCurrency(entityUnallocated, currency)}</p>
                </div>
              </div>
            </div>
          )}

          {error && <div className="bg-error-50 rounded-lg p-4 mb-4 dark:bg-error-500/10"><p className="text-sm text-error-700 dark:text-error-300"><AlertTriangle className="w-4 h-4 inline mr-1" />{error}</p></div>}

          {loading ? (
            <div className="flex justify-center py-8"><Loader2 className="w-8 h-8 animate-spin text-primary-600 dark:text-primary-200" /></div>
          ) : (
            <>
              {vasWithLimit.length > 0 && (
                <div className="mb-6">
                  <h3 className="text-sm font-semibold text-neutral-700 mb-3 dark:text-neutral-200">VAs with Limits ({vasWithLimit.length})</h3>
                  <div className="space-y-2">
                    {vasWithLimit.map(va => {
                      const limit = vaLimits[va.id];
                      const utilizationPct = safePercent(limit?.utilizedAmount, limit?.limitAmount);
                      return (
                        <div key={va.id} className="flex items-center justify-between p-3 bg-neutral-50 rounded-lg border border-neutral-200 dark:bg-primary-950 dark:border-primary-800">
                          <div className="flex-1">
                            <p className="font-medium text-neutral-900 dark:text-neutral-50">{va.vaNumber}</p>
                            <p className="text-sm text-neutral-500 dark:text-neutral-400">{va.vaName}</p>
                          </div>
                          <div className="text-right mr-4">
                            <p className="font-bold text-neutral-900 dark:text-neutral-50">{formatCurrency(safeNumber(limit?.limitAmount), currency)}</p>
                            <div className="w-24 bg-neutral-200 rounded-full h-1.5 mt-1 dark:bg-primary-800">
                              <div className={cn("h-1.5 rounded-full", utilizationPct > 80 ? "bg-warning-500" : "bg-success-500")} style={{ width: `${utilizationPct}%` }} />
                            </div>
                            <p className="text-xs text-neutral-500 dark:text-neutral-400">{utilizationPct.toFixed(0)}% utilized</p>
                          </div>
                          <button type="button" onClick={() => handleDeleteVaLimit(va.id)} className="p-2 hover:bg-error-50 rounded dark:hover:bg-error-500/10"><Trash2 className="w-4 h-4 text-error-500" /></button>
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}

              {vasWithoutLimit.length > 0 && entityUnallocated > 0 && (
                <div>
                  <h3 className="text-sm font-semibold text-neutral-700 mb-3 dark:text-neutral-200">Allocate to VA</h3>
                  <div className="space-y-3">
                    <div>
                      <label className="field-label block mb-1">Select VA</label>
                      <select value={selectedVaId || ''} onChange={(e) => setSelectedVaId(e.target.value || null)} className="w-full px-3 py-2 border border-neutral-300 rounded-lg dark:border-primary-700">
                        <option value="">Select a VA...</option>
                        {vasWithoutLimit.map(va => <option key={va.id} value={va.id}>{va.vaNumber} - {va.vaName}</option>)}
                      </select>
                    </div>
                    {selectedVaId && (
                      <div>
                        <label className="field-label block mb-1">Amount <span className="text-neutral-400 dark:text-neutral-500">(max: {formatCurrency(entityUnallocated, currency)})</span></label>
                        <input type="number" value={allocAmount} onChange={(e) => setAllocAmount(e.target.value === '' ? 0 : parseFloat(e.target.value))} max={entityUnallocated} step={1000} className="w-full px-3 py-2 border border-neutral-300 rounded-lg dark:border-primary-700" />
                      </div>
                    )}
                    <button type="button" onClick={handleAllocate} disabled={saving || !selectedVaId || allocAmount <= 0 || allocAmount > entityUnallocated} className="w-full px-4 py-2 bg-primary-900 text-white rounded-lg text-sm disabled:opacity-50 hover:bg-primary-800">
                      {saving && <Loader2 className="w-4 h-4 animate-spin inline mr-1" />}Allocate to VA
                    </button>
                  </div>
                </div>
              )}

              {vas.length === 0 && <div className="text-center py-8"><CreditCard className="w-12 h-12 text-neutral-300 mx-auto mb-4 dark:text-neutral-600" /><p className="text-neutral-500 dark:text-neutral-400">No {currency} VAs found for this entity</p></div>}
              {vasWithoutLimit.length === 0 && vas.length > 0 && <div className="text-center py-4 bg-success-50 rounded-lg dark:bg-success-500/10"><p className="text-success-700 dark:text-success-300">All VAs have limits allocated</p></div>}
              {entityUnallocated <= 0 && vasWithoutLimit.length > 0 && <div className="text-center py-4 bg-warning-50 rounded-lg dark:bg-warning-500/10"><p className="text-warning-700 dark:text-warning-300">No available entity limit to allocate. Increase entity limit first.</p></div>}
            </>
          )}
        </div>
    </Modal>
  );
};

// ============================================================================
// MAIN PAGE
// ============================================================================

const CreditLimitsPage: React.FC = () => {
  // Data
  const [corporates, setCorporates] = useState<Corporate[]>([]);
  const [selectedCorporateId, setSelectedCorporateId] = useState('');
  const [entities, setEntities] = useState<LegalEntity[]>([]);
  const [groupLimits, setGroupLimits] = useState<InternalLimitResponse[]>([]);
  const [entityLimitsMap, setEntityLimitsMap] = useState<Record<string, Record<string, InternalLimitResponse>>>({});
  const [externalCeilings, setExternalCeilings] = useState<Record<string, number>>({});
  const [currencyTotals, setCurrencyTotals] = useState<Record<string, CurrencyLimitTotals>>({});
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());

  // UI
  const [loading, setLoading] = useState(false);
  const [initialLoading, setInitialLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');

  // Modals
  const [showGroupModal, setShowGroupModal] = useState(false);
  const [editingGroupLimit, setEditingGroupLimit] = useState<InternalLimitResponse | null>(null);
  const [showEntityModal, setShowEntityModal] = useState(false);
  const [selectedEntity, setSelectedEntity] = useState<LegalEntity | null>(null);
  const [editingEntityLimit, setEditingEntityLimit] = useState<InternalLimitResponse | null>(null);
  const [showVaModal, setShowVaModal] = useState(false);
  const [vaModalCurrency, setVaModalCurrency] = useState('AED');

  // Load corporates
  useEffect(() => {
    const loadCorporates = async () => {
      try {
        const data = await corporateApi.getAll();
        setCorporates(data);
        if (data.length > 0) setSelectedCorporateId(data[0].id);
      } catch { setError('Failed to load corporates'); }
      finally { setInitialLoading(false); }
    };
    loadCorporates();
  }, []);

  // Load data when corporate changes
  const loadData = useCallback(async () => {
    if (!selectedCorporateId) return;
    setLoading(true); setError(null);
    try {
      // Load entities
      const entitiesData = await legalEntityApi.getByCorporate(selectedCorporateId);
      setEntities(entitiesData);
      const rootIds = entitiesData.filter(e => !e.parentEntityId).map(e => e.id);
      setExpandedIds(new Set(rootIds));

      // Load ALL group limits (multi-currency)
      const groups = await creditLimitApi.getAllGroupLimits(selectedCorporateId);
      setGroupLimits(groups);

      // Load ALL internal limits and build multi-currency map
      const limits = await creditLimitApi.getInternalByCorporate(selectedCorporateId);
      const limitMap: Record<string, Record<string, InternalLimitResponse>> = {};
      limits.filter(l => l.targetType === 'LEGAL_ENTITY').forEach(l => {
        if (!limitMap[l.targetId]) limitMap[l.targetId] = {};
        limitMap[l.targetId][l.currency] = l;
      });
      setEntityLimitsMap(limitMap);

      // Load currency totals
      const totals = await creditLimitApi.getTotalsByCurrency(selectedCorporateId);
      setCurrencyTotals(totals);

      // Load external ceilings
      const ceilingMap: Record<string, number> = {};
      for (const entity of entitiesData) {
        if (entity.isBankCustomer) {
          const ceiling = await creditLimitApi.getExternalCeiling(entity.id);
          if (ceiling) ceilingMap[entity.id] = ceiling;
        }
      }
      setExternalCeilings(ceilingMap);
    } catch { setError('Failed to load data'); }
    finally { setLoading(false); }
  }, [selectedCorporateId]);

  useEffect(() => { loadData(); }, [loadData]);

  // Handlers
  const handleToggle = (id: string) => {
    setExpandedIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  const handleExpandAll = () => setExpandedIds(new Set(entities.map(e => e.id)));
  const handleCollapseAll = () => setExpandedIds(new Set());

  const handleAddGroupCurrency = () => { setEditingGroupLimit(null); setShowGroupModal(true); };
  const handleEditGroupLimit = (limit: InternalLimitResponse) => { setEditingGroupLimit(limit); setShowGroupModal(true); };

  const handleSaveGroupLimit = async (data: { limitName: string; amount: number; currency: string; hardLimit: boolean; approvedBy: string }) => {
    if (editingGroupLimit) {
      await creditLimitApi.updateGroupLimitAmount(editingGroupLimit.id, data.amount, data.approvedBy);
    } else {
      await creditLimitApi.createGroupLimit({ corporateId: selectedCorporateId, ...data });
    }
    await loadData();
  };

  const handleAllocateEntity = (entity: LegalEntity) => { setSelectedEntity(entity); setEditingEntityLimit(null); setShowEntityModal(true); };
  const handleEditEntityLimit = (entity: LegalEntity, currency: string) => {
    setSelectedEntity(entity);
    setEditingEntityLimit(entityLimitsMap[entity.id]?.[currency] || null);
    setShowEntityModal(true);
  };

  const handleSaveEntityLimit = async (data: EntityLimitAllocationData) => {
    const existingLimit = entityLimitsMap[data.entityId]?.[data.currency];
    if (existingLimit) {
      await creditLimitApi.updateEntitySubLimitAmount(existingLimit.id, data.amount, data.approvedBy);
    } else {
      await creditLimitApi.createEntitySubLimitMultiCurrency({
        corporateId: selectedCorporateId, entityId: data.entityId, limitName: data.limitName,
        amount: data.amount, currency: data.currency, approvedBy: data.approvedBy,
        hardLimit: data.hardLimit, requiresApproval: data.requiresApproval, approvalThreshold: data.approvalThreshold,
      });
    }
    await loadData();
  };

  const handleDeleteEntityLimit = async (entityId: string, currency: string) => {
    const limit = entityLimitsMap[entityId]?.[currency];
    if (!limit || !confirm(`Delete ${currency} limit for this entity?`)) return;
    try { await creditLimitApi.deleteEntitySubLimit(limit.id, 'User deleted'); await loadData(); }
    catch (err: unknown) { setError(err instanceof Error ? err.message : 'Failed to delete'); }
  };

  const handleManageVaLimits = (entity: LegalEntity, currency: string) => {
    setSelectedEntity(entity); setVaModalCurrency(currency); setShowVaModal(true);
  };

  // Computed values
  const existingGroupCurrencies = groupLimits.map(l => l.currency);
  const existingEntityCurrencies = selectedEntity ? Object.keys(entityLimitsMap[selectedEntity.id] || {}) : [];
  const allocatedEntityCount = Object.keys(entityLimitsMap).filter(id => Object.keys(entityLimitsMap[id]).length > 0).length;

  const rootEntities = useMemo(() => {
    let filtered = entities;
    if (searchQuery) {
      const q = searchQuery.toLowerCase();
      const matchingIds = new Set<string>();
      entities.forEach(e => {
        if (e.entityCode.toLowerCase().includes(q) || e.entityName.toLowerCase().includes(q)) {
          matchingIds.add(e.id);
          let parent = entities.find(p => p.id === e.parentEntityId);
          while (parent) { matchingIds.add(parent.id); parent = entities.find(p => p.id === parent?.parentEntityId); }
        }
      });
      filtered = entities.filter(e => matchingIds.has(e.id));
    }
    return filtered.filter(e => !e.parentEntityId);
  }, [entities, searchQuery]);

  // Toolbar actions in Aperture Layout header. Corporate selector remains
  // inline as a filter input (not an action).
  usePageHeaderActions(
    () => (
      <>
        <button
          type="button"
          onClick={loadData}
          disabled={loading}
          className="px-3 py-1.5 text-sm border border-neutral-300 rounded-lg disabled:opacity-50 hover:bg-neutral-50 bg-white dark:border-primary-700 dark:hover:bg-primary-800/50 dark:bg-primary-900 inline-flex items-center"
        >
          <RefreshCw className={cn('w-4 h-4 mr-1', loading && 'animate-spin')} />
          Refresh
        </button>
        <button
          type="button"
          className="px-3 py-1.5 text-sm border border-neutral-300 rounded-lg hover:bg-neutral-50 bg-white dark:border-primary-700 dark:hover:bg-primary-800/50 dark:bg-primary-900 inline-flex items-center"
        >
          <Download className="w-4 h-4 mr-1" />
          Export
        </button>
      </>
    ),
    [loading]
  );

  if (initialLoading) {
    return <div className="min-h-screen bg-neutral-50 flex items-center justify-center dark:bg-primary-950"><Loader2 className="w-8 h-8 animate-spin text-primary-600 dark:text-primary-200" /></div>;
  }

  return (
    <Page>
      {/* Refresh + Export migrated to Aperture Layout header. Corporate
          selector stays here as a filter input. */}
      <div className="flex items-center justify-end animate-fade-in" style={{ animationDelay: '0.05s' }}>
          <select className="px-3 py-2 border border-neutral-300 rounded-lg min-w-[200px] bg-white text-sm font-medium dark:border-primary-700 dark:bg-primary-900" value={selectedCorporateId} onChange={(e) => setSelectedCorporateId(e.target.value)}>
            <option value="">Select Corporate...</option>
            {corporates.map(c => <option key={c.id} value={c.id}>{c.legalName}</option>)}
          </select>
      </div>

      {/* Error Banner */}
      {error && (
        <div className="bg-error-50 border border-error-200 rounded-xl p-4 flex items-center justify-between animate-fade-in dark:bg-error-500/10 dark:border-error-500/30">
          <div className="flex items-center gap-3">
            <StatusIconBadge tone="error" icon={AlertTriangle} className="dark:bg-error-500/20" />
            <p className="text-sm text-error-700 font-medium dark:text-error-300">{error}</p>
          </div>
          <button type="button" onClick={() => setError(null)} className="text-error-500 hover:text-error-700 p-1"><X className="w-5 h-5" /></button>
        </div>
      )}

      {/* Content Area */}
      {selectedCorporateId ? (
        <div className="space-y-6 animate-fade-in" style={{ animationDelay: '0.1s' }}>
          <StatsCards groupLimits={groupLimits} entityCount={entities.length} allocatedEntityCount={allocatedEntityCount} loading={loading} />
          <MultiCurrencyGroupLimitsCard groupLimits={groupLimits} onEdit={handleEditGroupLimit} onAddCurrency={handleAddGroupCurrency} />

          {groupLimits.length > 0 && (
            <div className="bg-white rounded-xl border border-neutral-100 shadow-sm animate-fade-in dark:bg-primary-900 dark:border-primary-800/60" style={{ animationDelay: '0.2s' }}>
              <div className="px-4 py-3 border-b border-neutral-100 flex items-center justify-between dark:border-primary-800/60">
                <div className="flex items-center gap-3">
                  <StatusIconBadge tone="primary" icon={GitBranch} className="dark:bg-primary-700" />
                  <div>
                    <h3 className="section-title">Entity Hierarchy</h3>
                    <p className="text-xs text-neutral-500 dark:text-neutral-400">{allocatedEntityCount} of {entities.length} entities with limits</p>
                  </div>
                </div>
                <div className="flex items-center gap-2">
                  <button type="button" onClick={handleExpandAll} className="px-3 py-1.5 text-xs font-medium text-primary-600 hover:bg-primary-50 rounded-lg transition-colors dark:text-primary-200 dark:hover:bg-primary-800/40">Expand All</button>
                  <button type="button" onClick={handleCollapseAll} className="px-3 py-1.5 text-xs font-medium text-primary-600 hover:bg-primary-50 rounded-lg transition-colors dark:text-primary-200 dark:hover:bg-primary-800/40">Collapse All</button>
                </div>
              </div>

              <div className="px-4 py-3 border-b border-neutral-100 dark:border-primary-800/60">
                <div className="relative max-w-sm">
                  <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400 dark:text-neutral-500" />
                  <input type="text" placeholder="Search entities..." value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} className="w-full pl-10 pr-4 py-2 border border-neutral-300 rounded-lg text-sm bg-white dark:border-primary-700 dark:bg-primary-900" />
                </div>
              </div>

              <div className="px-4 py-2 bg-neutral-50 border-b border-neutral-100 flex items-center gap-3 label dark:bg-primary-950 dark:border-primary-800/60">
                <div style={{ width: '28px' }} /><div style={{ width: '32px' }} />
                <div className="flex-1 min-w-[180px]">Entity</div>
                <div className="w-[100px] text-right">External</div>
                <div className="w-[100px]">Actions</div>
              </div>

              <div className="max-h-[500px] overflow-y-auto">
                {loading ? (
                  <div className="flex justify-center py-12"><Loader2 className="w-8 h-8 animate-spin text-primary-600 dark:text-primary-200" /></div>
                ) : rootEntities.length === 0 ? (
                  <div className="text-center py-12"><Building2 className="w-12 h-12 text-neutral-300 mx-auto mb-4 dark:text-neutral-600" /><p className="text-neutral-500 dark:text-neutral-400">No entities found</p></div>
                ) : (
                  rootEntities.sort((a, b) => a.hierarchyLevel - b.hierarchyLevel || a.entityCode.localeCompare(b.entityCode)).map(entity => (
                    <EntityTreeNode
                      key={entity.id} entity={entity} entities={entities} entityLimitsMap={entityLimitsMap}
                      externalCeilings={externalCeilings} expandedIds={expandedIds} groupLimits={groupLimits} level={0}
                      onToggle={handleToggle} onAllocate={handleAllocateEntity} onEdit={handleEditEntityLimit}
                      onDelete={handleDeleteEntityLimit} onManageVaLimits={handleManageVaLimits}
                    />
                  ))
                )}
              </div>
            </div>
          )}

          {groupLimits.length === 0 && !loading && (
            <div className="bg-white rounded-xl border border-neutral-100 p-8 text-center animate-fade-in dark:bg-primary-900 dark:border-primary-800/60" style={{ animationDelay: '0.15s' }}>
              <AlertTriangle className="w-12 h-12 text-warning-500 mx-auto mb-4" />
              <p className="text-neutral-600 dark:text-neutral-300">Create a Group Limit first to allocate to entities</p>
            </div>
          )}
        </div>
      ) : (
        <div className="bg-white rounded-xl border border-neutral-100 p-12 text-center animate-fade-in dark:bg-primary-900 dark:border-primary-800/60" style={{ animationDelay: '0.1s' }}>
          <Building className="w-12 h-12 text-neutral-300 mx-auto mb-4 dark:text-neutral-600" />
          <p className="text-neutral-500 dark:text-neutral-400">Select a corporate to view credit limits</p>
        </div>
      )}

      {/* Modals */}
      <GroupLimitModal isOpen={showGroupModal} onClose={() => setShowGroupModal(false)} existingLimit={editingGroupLimit} existingCurrencies={existingGroupCurrencies} corporateId={selectedCorporateId} onSave={handleSaveGroupLimit} />
      <EntityAllocationModal isOpen={showEntityModal} onClose={() => { setShowEntityModal(false); setSelectedEntity(null); setEditingEntityLimit(null); }} entity={selectedEntity} existingLimit={editingEntityLimit} groupLimits={groupLimits} existingEntityCurrencies={existingEntityCurrencies} externalCeiling={selectedEntity ? externalCeilings[selectedEntity.id] : undefined} corporateId={selectedCorporateId} onSave={handleSaveEntityLimit} />
      <VaLimitsModal isOpen={showVaModal} onClose={() => { setShowVaModal(false); setSelectedEntity(null); }} entity={selectedEntity} currency={vaModalCurrency} entityLimit={selectedEntity ? entityLimitsMap[selectedEntity.id]?.[vaModalCurrency] : null} corporateId={selectedCorporateId} onRefresh={loadData} />
    </Page>
  );
};

export default CreditLimitsPage;