// ============================================================================
// VIRTUAL ACCOUNTS PAGE - PREMIUM DESIGN SYSTEM
// ============================================================================
// Features:
// - Corporate & Program Selectors (session-only)
// - Table View (default) & Simplified Tree View
// - Account Detail Panel with tabs (Overview, Statements, Activity)
// - Owning Entity display for POBO/COBO
// - Status management with transitions
// - Export functionality
// - Mobile-first responsive design
// - Premium animations and polish
// ============================================================================

import React, { useState, useEffect, useCallback, useMemo } from 'react';
import {
  Search, Plus, Filter, Download, Eye, Edit, X,
  ChevronLeft, ChevronRight, Building2, RefreshCw, Loader2, CreditCard,
  CheckCircle, PauseCircle, Clock, Ban, Play,
  ArrowUpRight, ArrowDownRight, Layers, Hash, Banknote,
  GitBranch, Coins, ChevronRight as ChevronRightIcon,
  FolderTree, AlertTriangle, FileText, Activity, Copy,
  MoreHorizontal, XCircle, Shield, Briefcase,
} from 'lucide-react';
import { Card, Button, Badge, Input, EmptyState, Skeleton, Select, Drawer } from '../components/ui';
import { CurrencyPicker } from '../components/ui/CurrencyPicker';
import { Modal } from '../components/ui/enhanced';
import { HeroMetricCard } from '../components/ui/HeroMetricCard';
import { VaCreateModal } from '../pages/VaCreateModal';
import { formatCurrency, formatDate, cn, copyToClipboard } from '../utils';
import { isCredit, isDebit } from '../utils/transactionUtils';
import { usePageHeaderActions } from '../context/PageHeaderContext';
import toast from 'react-hot-toast';
import { Page } from '../components/layout/Page';
import { PageHeader } from '../components/layout/PageHeader';
import { StatStrip } from '../components/layout/StatStrip';
import { ScopeSelector } from '../components/layout/ScopeSelector';
// ============================================================================
// TYPES
// ============================================================================

type VaStatus = 'ACTIVE' | 'INACTIVE' | 'SUSPENDED' | 'BLOCKED' | 'PENDING_ACTIVATION' | 'CLOSED';
type ProgramType = 'COLLECTION' | 'WALLET' | 'IHB' | 'PAYABLES' | 'VIBAN' | 'ESCROW';
type AccountCategory = 'TRANSACTION' | 'COLLECTION' | 'DISBURSEMENT' | 'SETTLEMENT' | 
                       'EXCEPTION' | 'SUSPENSE' | 'ROOT' | 'AGGREGATION' | 
                       'PHYSICAL_MIRROR' | 'EXTERNAL_MIRROR' | 'CURRENCY_MIRROR' |
                       'INTERCOMPANY' | 'ESCROW' | 'NETTING';
type ViewMode = 'table' | 'tree';
type DetailTab = 'overview' | 'statements' | 'activity';

interface VirtualAccount {
  id: string;
  vaNumber: string;
  viban?: string;
  vaName: string;
  programId?: string;
  programName?: string;
  programType?: ProgramType;
  corporateId: string;
  corporateName?: string;
  physicalAccountId: string;
  currencyCode: string;
  currentBalance: number;
  availableBalance: number;
  heldBalance?: number;
  status: VaStatus;
  externalReference?: string;
  kycVerified?: boolean;
  kycLevel?: number;
  accountCategory?: AccountCategory;
  parentAccountId?: string;
  hierarchyPathVa?: string;
  hierarchyLevel?: number;
  owningEntityId?: string;
  owningEntityCode?: string;
  children?: VirtualAccount[];
  createdAt: string;
  updatedAt?: string;
  // Limits (display only)
  perTransactionLimit?: number;
  dailyLimit?: number;
  monthlyLimit?: number;
  // Status history
  suspensionReason?: string;
  blockReason?: string;
  // Aggregated balances (for hierarchy nodes)
  aggregatedBalance?: number;
  aggregatedBalanceBase?: number;
  mirrorBalance?: number;
  balanceInBase?: number;
  baseCurrency?: string;
}

interface Program {
  id: string;
  programCode: string;
  programName: string;
  programType: ProgramType;
  currencyCode: string;
}

interface Corporate { 
  id: string; 
  name: string; 
  legalName?: string; 
}

interface PhysicalAccount { 
  id: string; 
  accountNumber: string; 
  currencyCode?: string; 
  bankName?: string; 
}

interface HierarchyNode { 
  id: string; 
  name: string; 
  code: string; 
  level: number; 
}

interface VirtualAccountStats { 
  totalAccounts: number; 
  activeAccounts: number; 
  suspendedAccounts: number; 
  blockedAccounts: number; 
  totalBalance: number; 
  availableBalance: number; 
}

interface Transaction {
  id: string;
  referenceNumber: string;
  transactionDate: string;
  description?: string;
  movementType: string;
  amount: number;
  balanceAfter: number;
}

interface Statement {
  accountId: string;
  vaNumber: string;
  vaName: string;
  currencyCode: string;
  fromDate: string;
  toDate: string;
  openingBalance: number;
  closingBalance: number;
  totalCredits: number;
  totalDebits: number;
  transactionCount: number;
  transactions: Transaction[];
}

interface ApiResponse<T> { 
  success: boolean; 
  data: T; 
  message?: string; 
  pagination?: { 
    page: number; 
    size: number; 
    totalElements: number; 
    totalPages: number 
  }; 
}

// ============================================================================
// API SERVICE
// ============================================================================

const API_BASE = '/api/v1';

async function fetchApi<T>(url: string, options?: RequestInit): Promise<ApiResponse<T>> {
  try {
    const response = await fetch(API_BASE + url, { 
      headers: { 'Content-Type': 'application/json', ...options?.headers }, 
      ...options 
    });
    if (!response.ok) { 
      const err = await response.json().catch(() => ({})); 
      throw new Error(err.message || `API error: ${response.status}`); 
    }
    return await response.json();
  } catch (error) { 
    console.error('API Error:', error); 
    return { success: false, data: null as unknown as T, message: String(error) }; 
  }
}

// Helper to extract programs from nested response
const extractPrograms = (response: any): Program[] => {
  if (!response.success || !response.data) return [];
  // Programs API returns { data: { programs: [...] } }
  const data = response.data;
  if (data.programs && Array.isArray(data.programs)) {
    return data.programs;
  }
  // Fallback if direct array
  if (Array.isArray(data)) return data;
  return [];
};

// Helper to extract corporates - maps entity fields to interface
const extractCorporates = (response: any): Corporate[] => {
  if (!response.success || !response.data) return [];
  const data = response.data;
  const list = Array.isArray(data) ? data : [];
  // Map Corporate entity fields to our interface
  return list.map((c: any) => ({
    id: c.id,
    name: c.tradeName || c.legalName || c.corporateId, // Display name
    legalName: c.legalName,
  }));
};

const api = {
  virtualAccounts: {
    getAll: (page = 0, size = 10, filters?: { corporateId?: string; programId?: string }) => {
      let url = `/virtual-accounts?page=${page}&size=${size}&sort=createdAt,desc`;
      if (filters?.programId) url += `&programId=${filters.programId}`;
      return fetchApi<VirtualAccount[]>(url);
    },
    getByCorporate: (corporateId: string, page = 0, size = 10) =>
      fetchApi<VirtualAccount[]>(`/virtual-accounts/corporate/${corporateId}?page=${page}&size=${size}`),
    getByProgram: (programId: string, page = 0, size = 10) =>
      fetchApi<VirtualAccount[]>(`/virtual-accounts/program/${programId}?page=${page}&size=${size}`),
    getById: (id: string) => fetchApi<VirtualAccount>(`/virtual-accounts/${id}`),
    search: (query: string, page = 0, size = 10) => 
      fetchApi<VirtualAccount[]>(`/virtual-accounts/search?query=${encodeURIComponent(query)}&page=${page}&size=${size}`),
    updateStatus: (id: string, status: VaStatus, reason?: string) => 
      fetchApi<VirtualAccount>(`/virtual-accounts/${id}/status?status=${status}${reason ? `&reason=${encodeURIComponent(reason)}` : ''}`, { method: 'PATCH' }),
    getStats: (corporateId?: string, programId?: string) => {
      let url = '/virtual-accounts/stats';
      const params = [];
      if (corporateId) params.push(`corporateId=${corporateId}`);
      if (programId) params.push(`programId=${programId}`);
      if (params.length) url += '?' + params.join('&');
      return fetchApi<VirtualAccountStats>(url);
    },
    export: (corporateId?: string, programId?: string) => {
      let url = '/virtual-accounts/export';
      const params = [];
      if (corporateId) params.push(`corporateId=${corporateId}`);
      if (programId) params.push(`programId=${programId}`);
      if (params.length) url += '?' + params.join('&');
      return fetchApi<VirtualAccount[]>(url);
    },
  },
  statements: {
    getAccountStatement: (accountId: string, fromDate: string, toDate: string) =>
      fetchApi<Statement>(`/statements/account/${accountId}?fromDate=${fromDate}&toDate=${toDate}`),
    generate: (accountId: string, format: string, fromDate: string, toDate: string) =>
      fetchApi<{ statementReference: string; downloadUrl: string }>(
        `/statements/generate?accountId=${accountId}&format=${format}&fromDate=${fromDate}&toDate=${toDate}`
      ),
  },
  programs: { 
    getAll: async (): Promise<ApiResponse<Program[]>> => {
      const response = await fetchApi<any>('/programs');
      return { ...response, data: extractPrograms(response) };
    },
  },
  corporates: { 
    getAll: async (): Promise<ApiResponse<Corporate[]>> => {
      const response = await fetchApi<any>('/corporates');
      return { ...response, data: extractCorporates(response) };
    },
  },
  physicalAccounts: { 
    getAll: () => fetchApi<PhysicalAccount[]>('/physical-accounts') 
  },
  hierarchy: { 
    getNodes: () => fetchApi<HierarchyNode[]>('/hierarchy/nodes') 
  },
};

// ============================================================================
// CONFIGURATION
// ============================================================================

const statusConfig: Record<VaStatus, { label: string; variant: string; icon: React.ElementType }> = {
  ACTIVE: { label: 'Active', variant: 'success', icon: CheckCircle },
  INACTIVE: { label: 'Inactive', variant: 'neutral', icon: Clock },
  SUSPENDED: { label: 'Suspended', variant: 'warning', icon: PauseCircle },
  BLOCKED: { label: 'Blocked', variant: 'error', icon: Ban },
  PENDING_ACTIVATION: { label: 'Pending', variant: 'info', icon: Clock },
  CLOSED: { label: 'Closed', variant: 'neutral', icon: XCircle },
};

/**
 * Get the effective balance for display based on account category.
 * - AGGREGATION/ROOT: use aggregatedBalance (sum of child balances)
 * - CURRENCY_MIRROR: use mirrorBalance (sum of same-currency siblings)
 * - Transaction VAs: use currentBalance
 */
const getEffectiveBalance = (account: VirtualAccount): number => {
  const category = account.accountCategory;

  if (category === 'ROOT' || category === 'AGGREGATION') {
    // Aggregation nodes show aggregatedBalance
    return account.aggregatedBalance ?? account.currentBalance ?? 0;
  }

  if (category === 'CURRENCY_MIRROR') {
    // Currency mirrors show mirrorBalance
    return account.mirrorBalance ?? account.currentBalance ?? 0;
  }

  // All other accounts show currentBalance
  return account.currentBalance ?? 0;
};

const accountCategoryConfig: Record<AccountCategory, {
  label: string;
  icon: React.ElementType;
  color: string;
  bgColor: string;
}> = {
  TRANSACTION: { label: 'Transaction', icon: CreditCard, color: 'text-primary-600 dark:text-primary-200', bgColor: 'bg-primary-50 dark:bg-primary-800/40' },
  COLLECTION: { label: 'Collection', icon: ArrowDownRight, color: 'text-success-600 dark:text-success-300', bgColor: 'bg-success-50 dark:bg-success-500/10' },
  DISBURSEMENT: { label: 'Disbursement', icon: ArrowUpRight, color: 'text-warning-600 dark:text-warning-300', bgColor: 'bg-warning-50 dark:bg-warning-500/10' },
  SETTLEMENT: { label: 'Settlement', icon: Banknote, color: 'text-info-600 dark:text-info-300', bgColor: 'bg-info-50 dark:bg-info-500/10' },
  EXCEPTION: { label: 'Exception', icon: AlertTriangle, color: 'text-warning-600 dark:text-warning-300', bgColor: 'bg-warning-50 dark:bg-warning-500/10' },
  SUSPENSE: { label: 'Suspense', icon: Clock, color: 'text-neutral-600 dark:text-neutral-300', bgColor: 'bg-neutral-50 dark:bg-primary-950' },
  ROOT: { label: 'Root', icon: Layers, color: 'text-cat-1', bgColor: 'bg-cat-1-soft dark:bg-cat-1/15' },
  AGGREGATION: { label: 'Aggregation', icon: Layers, color: 'text-cat-2', bgColor: 'bg-cat-2-soft dark:bg-cat-2/15' },
  PHYSICAL_MIRROR: { label: 'Shadow', icon: Building2, color: 'text-cyan-600 dark:text-cyan-300', bgColor: 'bg-cyan-50 dark:bg-cyan-500/10' },
  EXTERNAL_MIRROR: { label: 'External', icon: Building2, color: 'text-cat-3', bgColor: 'bg-cat-3-soft dark:bg-cat-3/15' },
  CURRENCY_MIRROR: { label: 'Currency Mirror', icon: Coins, color: 'text-cat-5', bgColor: 'bg-cat-5-soft dark:bg-cat-5/15' },
  INTERCOMPANY: { label: 'Intercompany', icon: GitBranch, color: 'text-cat-2', bgColor: 'bg-cat-2-soft dark:bg-cat-2/15' },
  ESCROW: { label: 'Escrow', icon: Shield, color: 'text-rose-600 dark:text-rose-300', bgColor: 'bg-rose-50 dark:bg-rose-500/10' },
  NETTING: { label: 'Netting', icon: Hash, color: 'text-cat-4', bgColor: 'bg-cat-4-soft dark:bg-cat-4/15' },
};

const programTypeConfig: Record<ProgramType, { label: string; icon: React.ElementType; color: string }> = {
  COLLECTION: { label: 'Collection', icon: ArrowDownRight, color: 'text-success-600 dark:text-success-300' },
  WALLET: { label: 'Wallet', icon: CreditCard, color: 'text-primary-600 dark:text-primary-200' },
  IHB: { label: 'In-House Bank', icon: Building2, color: 'text-cat-2' },
  PAYABLES: { label: 'Payables', icon: ArrowUpRight, color: 'text-warning-600 dark:text-warning-300' },
  VIBAN: { label: 'VIBAN', icon: Hash, color: 'text-info-600 dark:text-info-300' },
  ESCROW: { label: 'Escrow', icon: Shield, color: 'text-cat-3' },
};

// Picker now uses the shared `<ScopeSelector mode="corporate-program">`
// primitive from components/layout/ — see import block at the top. The
// inline `SelectorBar` that lived here (Desktop + Mobile variants) is
// removed; ScopeSelector handles responsive layout via flex-wrap and
// renders identically across breakpoints.

// ============================================================================
// STATS CARDS COMPONENT
// ============================================================================

interface StatsCardsProps {
  stats: VirtualAccountStats;
  loading?: boolean;
}

const StatsCards: React.FC<StatsCardsProps> = ({ stats, loading }) => {
  // Operational tiles only — the headline financial figures (Total Balance,
  // Available) are now rendered above this strip by HeroMetricCard.
  const cards = [
    { label: 'Total Accounts', value: stats.totalAccounts, icon: Layers, color: 'primary', trend: null, isBalance: false },
    { label: 'Active', value: stats.activeAccounts, icon: CheckCircle, color: 'success', trend: '+12%', isBalance: false },
    { label: 'Suspended', value: stats.suspendedAccounts, icon: PauseCircle, color: 'warning', trend: null, isBalance: false },
  ];

  const colorMap: Record<string, { gradient: string; iconBg: string; iconColor: string }> = {
    primary: { gradient: 'from-primary-50 to-white dark:from-primary-800/40 dark:to-primary-900', iconBg: 'bg-primary-100 dark:bg-primary-700', iconColor: 'text-primary-600 dark:text-primary-200' },
    success: { gradient: 'from-success-50 to-white dark:from-success-500/15 dark:to-primary-900', iconBg: 'bg-success-100 dark:bg-success-500/20', iconColor: 'text-success-600 dark:text-success-300' },
    warning: { gradient: 'from-warning-50 to-white dark:from-warning-500/15 dark:to-primary-900', iconBg: 'bg-warning-100 dark:bg-warning-500/20', iconColor: 'text-warning-600 dark:text-warning-300' },
    info:    { gradient: 'from-info-50 to-white dark:from-info-500/15 dark:to-primary-900',       iconBg: 'bg-info-100 dark:bg-info-500/20',       iconColor: 'text-info-600 dark:text-info-300' },
    accent:  { gradient: 'from-accent-50 to-white dark:from-accent-500/15 dark:to-primary-900',   iconBg: 'bg-accent-100 dark:bg-accent-500/20',   iconColor: 'text-accent-600 dark:text-accent-300' },
  };

  if (loading) {
    return (
      <StatStrip columns={3}>
        {[...Array(3)].map((_, idx) => (
          <Card key={idx} hover className="animate-fade-in" style={{ animationDelay: `${idx * 0.05}s` }}>
            <div className="flex items-center gap-3">
              <Skeleton className="w-10 h-10 rounded-lg" />
              <div className="flex-1">
                <Skeleton className="h-3 w-16 mb-2" />
                <Skeleton className="h-6 w-20" />
              </div>
            </div>
          </Card>
        ))}
      </StatStrip>
    );
  }

  return (
    <StatStrip columns={3}>
      {cards.map((card, idx) => {
        const Icon = card.icon;
        const colors = colorMap[card.color] || colorMap.primary;

        return (
          <Card
            key={idx}
            hover
            className={cn(
              "bg-gradient-to-br animate-fade-in",
              colors.gradient,
              "hover:shadow-card-hover transition-all duration-300"
            )}
            style={{ animationDelay: `${idx * 0.05}s` }}
          >
            <div className="flex items-center gap-3">
              <div className={cn("w-10 h-10 rounded-xl flex items-center justify-center shrink-0", colors.iconBg)}>
                <Icon className={cn("w-5 h-5", colors.iconColor)} />
              </div>
              <div className="min-w-0">
                <p className="text-xs font-medium text-neutral-500 truncate dark:text-neutral-400">{card.label}</p>
                <p className={cn(
                  "font-bold text-primary-900 truncate dark:text-neutral-50",
                  card.isBalance ? "text-base sm:text-lg" : "text-xl sm:text-2xl"
                )}>
                  {card.value}
                </p>
                {card.trend && (
                  <span className="text-xs text-success-600 font-medium dark:text-success-300">{card.trend}</span>
                )}
              </div>
            </div>
          </Card>
        );
      })}
    </StatStrip>
  );
};

// ============================================================================
// TABLE ROW COMPONENT (Desktop)
// ============================================================================

interface AccountRowProps {
  account: VirtualAccount;
  onView: (a: VirtualAccount) => void;
  onEdit: (a: VirtualAccount) => void;
  onStatusChange: (id: string, status: VaStatus) => void;
}

const AccountRow: React.FC<AccountRowProps> = ({ account, onView, onEdit, onStatusChange }) => {
  const [showActions, setShowActions] = useState(false);
  const category = account.accountCategory || 'TRANSACTION';
  const categoryConfig = accountCategoryConfig[category] || accountCategoryConfig.TRANSACTION;
  const CategoryIcon = categoryConfig.icon;
  const statusCfg = statusConfig[account.status];
  const StatusIcon = statusCfg?.icon || Clock;

  return (
    <tr
      className="data-table-row group cursor-pointer"
      onClick={() => onView(account)}
    >
      {/* Account Info */}
      <td className="data-table-cell">
        <div className="flex items-center gap-3">
          <div className={cn(
            "w-10 h-10 rounded-xl flex items-center justify-center shrink-0 transition-transform group-hover:scale-105",
            categoryConfig.bgColor
          )}>
            <CategoryIcon className={cn("w-5 h-5", categoryConfig.color)} />
          </div>
          <div className="min-w-0">
            <p className="text-sm font-semibold text-primary-900 truncate group-hover:text-primary-600 transition-colors dark:text-neutral-50">
              {account.vaName}
            </p>
            <div className="flex items-center gap-2 text-xs text-neutral-500 mt-0.5 dark:text-neutral-400">
              <span className="font-mono">{account.vaNumber}</span>
              {account.owningEntityCode && (
                <Badge variant="neutral" size="sm" className="bg-cat-2-soft text-cat-2 border-cat-2/10 dark:bg-cat-2/15 dark:border-cat-2/30">
                  {account.owningEntityCode}
                </Badge>
              )}
            </div>
          </div>
        </div>
      </td>

      {/* Program */}
      <td className="data-table-cell">
        {account.programName ? (
          <div className="flex items-center gap-2">
            <div className="w-6 h-6 rounded-lg bg-primary-100 flex items-center justify-center dark:bg-primary-700">
              <Briefcase className="w-3.5 h-3.5 text-primary-600 dark:text-primary-200" />
            </div>
            <div className="min-w-0">
              <p className="text-sm font-medium text-primary-900 truncate max-w-[150px] dark:text-neutral-50">
                {account.programName}
              </p>
              {account.programType && (
                <p className="text-xs text-neutral-500 dark:text-neutral-400">{account.programType}</p>
              )}
            </div>
          </div>
        ) : (
          <span className="text-xs text-neutral-400 italic dark:text-neutral-500">No program</span>
        )}
      </td>

      {/* Category */}
      <td className="data-table-cell">
        <Badge variant="neutral" size="sm" className={cn(categoryConfig.bgColor, categoryConfig.color, "border-0")}>
          {categoryConfig.label}
        </Badge>
      </td>

      {/* Currency & Balance — negatives render in error tone so the eye
          catches overdrawn / mirror-deficit accounts at a glance. */}
      <td className="data-table-cell text-right">
        <p className={cn(
          "text-sm font-bold tabular-nums",
          getEffectiveBalance(account) < 0
            ? "text-error-600 dark:text-error-300"
            : "text-primary-900 dark:text-neutral-50"
        )}>
          {formatCurrency(getEffectiveBalance(account), account.currencyCode)}
        </p>
        <p className={cn(
          "text-xs mt-0.5",
          account.availableBalance < 0
            ? "text-error-500 dark:text-error-300"
            : "text-neutral-500 dark:text-neutral-400"
        )}>
          Avail: {formatCurrency(account.availableBalance, account.currencyCode)}
        </p>
      </td>

      {/* Status */}
      <td className="data-table-cell">
        <Badge variant={statusCfg?.variant as any} size="sm">
          <StatusIcon className="w-3 h-3 mr-1" />
          {statusCfg?.label}
        </Badge>
      </td>

      {/* Actions */}
      <td className="data-table-cell" onClick={(e) => e.stopPropagation()}>
        <div className="relative">
          <button
            onClick={() => setShowActions(!showActions)}
            className="p-2 hover:bg-neutral-100 rounded-lg transition-all opacity-0 group-hover:opacity-100 dark:hover:bg-primary-800"
          >
            <MoreHorizontal className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
          </button>
          {showActions && (
            <>
              <div className="fixed inset-0 z-10" onClick={() => setShowActions(false)} />
              <div className="absolute right-0 top-full mt-1 w-48 bg-white rounded-xl shadow-dropdown border border-neutral-200 py-1 z-20 animate-fade-in dark:bg-primary-900 dark:border-primary-800">
                <button
                  onClick={() => { onView(account); setShowActions(false); }}
                  className="w-full flex items-center gap-2 px-4 py-2.5 text-sm text-primary-900 hover:bg-neutral-50 transition-colors dark:text-neutral-50 dark:hover:bg-primary-800/50"
                >
                  <Eye className="w-4 h-4 text-neutral-500 dark:text-neutral-400" /> View Details
                </button>
                <button
                  onClick={() => { onEdit(account); setShowActions(false); }}
                  className="w-full flex items-center gap-2 px-4 py-2.5 text-sm text-primary-900 hover:bg-neutral-50 transition-colors dark:text-neutral-50 dark:hover:bg-primary-800/50"
                >
                  <Edit className="w-4 h-4 text-neutral-500 dark:text-neutral-400" /> Edit Account
                </button>
                <hr className="my-1 border-neutral-100 dark:border-primary-800/60" />
                {account.status === 'ACTIVE' && (
                  <>
                    <button
                      onClick={() => { onStatusChange(account.id, 'SUSPENDED'); setShowActions(false); }}
                      className="w-full flex items-center gap-2 px-4 py-2.5 text-sm text-warning-600 hover:bg-warning-50 transition-colors dark:text-warning-300 dark:hover:bg-warning-500/10"
                    >
                      <PauseCircle className="w-4 h-4" /> Suspend Account
                    </button>
                    <button
                      onClick={() => { onStatusChange(account.id, 'BLOCKED'); setShowActions(false); }}
                      className="w-full flex items-center gap-2 px-4 py-2.5 text-sm text-error-600 hover:bg-error-50 transition-colors dark:text-error-300 dark:hover:bg-error-500/10"
                    >
                      <Ban className="w-4 h-4" /> Block Account
                    </button>
                  </>
                )}
                {(account.status === 'SUSPENDED' || account.status === 'BLOCKED') && (
                  <button
                    onClick={() => { onStatusChange(account.id, 'ACTIVE'); setShowActions(false); }}
                    className="w-full flex items-center gap-2 px-4 py-2.5 text-sm text-success-600 hover:bg-success-50 transition-colors dark:text-success-300 dark:hover:bg-success-500/10"
                  >
                    <Play className="w-4 h-4" /> Reactivate Account
                  </button>
                )}
              </div>
            </>
          )}
        </div>
      </td>
    </tr>
  );
};

// ============================================================================
// MOBILE CARD COMPONENT
// ============================================================================

interface AccountMobileCardProps {
  account: VirtualAccount;
  onView: (a: VirtualAccount) => void;
  index: number;
}

const AccountMobileCard: React.FC<AccountMobileCardProps> = ({ account, onView, index }) => {
  const category = account.accountCategory || 'TRANSACTION';
  const categoryConfig = accountCategoryConfig[category] || accountCategoryConfig.TRANSACTION;
  const CategoryIcon = categoryConfig.icon;
  const statusCfg = statusConfig[account.status];
  const StatusIcon = statusCfg?.icon || Clock;

  return (
    <Card
      interactive
      hover
      onClick={() => onView(account)}
      className="animate-fade-in"
      style={{ animationDelay: `${index * 0.03}s` }}
    >
      <div className="flex items-start gap-3">
        <div className={cn(
          "w-12 h-12 rounded-xl flex items-center justify-center shrink-0",
          categoryConfig.bgColor
        )}>
          <CategoryIcon className={cn("w-6 h-6", categoryConfig.color)} />
        </div>

        <div className="flex-1 min-w-0">
          <div className="flex items-start justify-between gap-2">
            <div className="min-w-0">
              <h3 className="font-semibold text-primary-900 truncate dark:text-neutral-50">{account.vaName}</h3>
              <p className="text-xs text-neutral-500 font-mono mt-0.5 dark:text-neutral-400">{account.vaNumber}</p>
            </div>
            <Badge variant={statusCfg?.variant as any} size="sm">
              <StatusIcon className="w-3 h-3 mr-1" />
              {statusCfg?.label}
            </Badge>
          </div>

          <div className="mt-3 pt-3 border-t border-neutral-100 flex items-center justify-between dark:border-primary-800/60">
            <div>
              <p className="text-xs text-neutral-500 dark:text-neutral-400">Balance</p>
              <p className={cn(
                "text-sm font-bold tabular-nums",
                getEffectiveBalance(account) < 0
                  ? "text-error-600 dark:text-error-300"
                  : "text-primary-900 dark:text-neutral-50"
              )}>
                {formatCurrency(getEffectiveBalance(account), account.currencyCode)}
              </p>
            </div>
            <div className="text-right">
              <p className="text-xs text-neutral-500 dark:text-neutral-400">Available</p>
              <p className={cn(
                "text-sm font-semibold tabular-nums",
                account.availableBalance < 0
                  ? "text-error-600 dark:text-error-300"
                  : "text-success-600 dark:text-success-300"
              )}>
                {formatCurrency(account.availableBalance, account.currencyCode)}
              </p>
            </div>
          </div>

          {(account.owningEntityCode || account.programName) && (
            <div className="mt-2 flex items-center gap-2 flex-wrap">
              {account.owningEntityCode && (
                <Badge variant="neutral" size="sm" className="bg-cat-2-soft text-cat-2 border-cat-2/10 dark:bg-cat-2/15 dark:border-cat-2/30">
                  {account.owningEntityCode}
                </Badge>
              )}
              {account.programName && (
                <Badge variant="neutral" size="sm">
                  {account.programName}
                </Badge>
              )}
            </div>
          )}
        </div>

        <ChevronRight className="w-5 h-5 text-neutral-400 shrink-0 mt-4 dark:text-neutral-500" />
      </div>
    </Card>
  );
};

// ============================================================================
// SIMPLIFIED TREE VIEW
// ============================================================================

interface TreeNodeProps {
  account: VirtualAccount;
  level: number;
  expanded: Set<string>;
  onToggle: (id: string) => void;
  onView: (a: VirtualAccount) => void;
}

const TreeNode: React.FC<TreeNodeProps> = ({ account, level, expanded, onToggle, onView }) => {
  const isExpanded = expanded.has(account.id);
  const hasChildren = account.children && account.children.length > 0;
  const category = account.accountCategory || 'TRANSACTION';
  const categoryConfig = accountCategoryConfig[category] || accountCategoryConfig.TRANSACTION;
  const CategoryIcon = categoryConfig.icon;

  return (
    <div className="select-none">
      <div 
        className={cn(
          "flex items-center gap-2 py-2 px-3 rounded-lg transition-colors cursor-pointer group hover:bg-neutral-50 dark:hover:bg-primary-800/50"
        )}
        style={{ paddingLeft: `${level * 20 + 8}px` }}
        onClick={() => onView(account)}
      >
        {/* Expand Toggle */}
        <button 
          onClick={(e) => { e.stopPropagation(); onToggle(account.id); }}
          className={cn("w-5 h-5 flex items-center justify-center rounded", hasChildren ? "hover:bg-neutral-200" : "invisible")}
        >
          {hasChildren && (
            <ChevronRightIcon className={cn("w-4 h-4 text-neutral-400 transition-transform dark:text-neutral-500", isExpanded && "rotate-90")} />
          )}
        </button>

        {/* Icon */}
        <div className={cn("w-7 h-7 rounded-lg flex items-center justify-center shrink-0", categoryConfig.bgColor)}>
          <CategoryIcon className={cn("w-3.5 h-3.5", categoryConfig.color)} />
        </div>

        {/* Info */}
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <span className="text-sm font-medium text-primary-900 truncate dark:text-neutral-50">{account.vaName}</span>
            <Badge variant={statusConfig[account.status]?.variant as any} size="sm">
              {statusConfig[account.status]?.label}
            </Badge>
            {account.owningEntityCode && (
              <Badge variant="neutral" size="sm" className="bg-cat-2-soft text-cat-2 dark:bg-cat-2/15">
                {account.owningEntityCode}
              </Badge>
            )}
          </div>
          <span className="text-xs text-neutral-500 font-mono dark:text-neutral-400">{account.vaNumber}</span>
        </div>

        {/* Balance */}
        <div className="text-right shrink-0">
          <p className="text-sm font-semibold text-primary-900 dark:text-neutral-50">
            {formatCurrency(getEffectiveBalance(account), account.currencyCode)}
          </p>
        </div>
      </div>

      {/* Children */}
      {isExpanded && hasChildren && (
        <div>
          {account.children!.map(child => (
            <TreeNode key={child.id} account={child} level={level + 1} expanded={expanded} onToggle={onToggle} onView={onView} />
          ))}
        </div>
      )}
    </div>
  );
};

interface TreeViewProps {
  accounts: VirtualAccount[];
  loading: boolean;
  onView: (a: VirtualAccount) => void;
}

const TreeView: React.FC<TreeViewProps> = ({ accounts, loading, onView }) => {
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  // Build tree from flat list
  const treeData = useMemo(() => {
    const idMap = new Map<string, VirtualAccount>();
    accounts.forEach(a => idMap.set(a.id, { ...a, children: [] }));

    const roots: VirtualAccount[] = [];
    idMap.forEach(account => {
      if (account.parentAccountId && idMap.has(account.parentAccountId)) {
        const parent = idMap.get(account.parentAccountId)!;
        parent.children = parent.children || [];
        parent.children.push(account);
      } else {
        roots.push(account);
      }
    });

    return roots;
  }, [accounts]);

  const toggleExpand = (id: string) => {
    setExpanded(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center py-12">
        <Loader2 className="w-6 h-6 animate-spin text-primary-500" />
        <span className="ml-2 text-neutral-500 dark:text-neutral-400">Loading...</span>
      </div>
    );
  }

  if (treeData.length === 0) {
    return (
      <div className="text-center py-12">
        <FolderTree className="w-12 h-12 text-neutral-300 mx-auto mb-4 dark:text-neutral-600" />
        <p className="text-neutral-500 dark:text-neutral-400">No accounts with hierarchy</p>
      </div>
    );
  }

  return (
    <div className="border border-neutral-200 rounded-lg divide-y divide-neutral-100 dark:border-primary-800 dark:divide-primary-800/60">
      {treeData.map(account => (
        <TreeNode key={account.id} account={account} level={0} expanded={expanded} onToggle={toggleExpand} onView={onView} />
      ))}
    </div>
  );
};

// ============================================================================
// ACCOUNT DETAIL PANEL
// ============================================================================

interface AccountDetailPanelProps {
  account: VirtualAccount;
  onClose: () => void;
  onEdit: (a: VirtualAccount) => void;
  onStatusChange: (id: string, status: VaStatus, reason?: string) => void;
}

const AccountDetailPanel: React.FC<AccountDetailPanelProps> = ({ account, onClose, onEdit, onStatusChange }) => {
  const [activeTab, setActiveTab] = useState<DetailTab>('overview');
  const [statement, setStatement] = useState<Statement | null>(null);
  const [stmtLoading, setStmtLoading] = useState(false);
  const [fromDate, setFromDate] = useState(() => {
    const d = new Date();
    d.setMonth(d.getMonth() - 1);
    return d.toISOString().split('T')[0];
  });
  const [toDate, setToDate] = useState(() => new Date().toISOString().split('T')[0]);
  const [statusReasonModal, setStatusReasonModal] = useState<{ action: VaStatus } | null>(null);
  const [statusReason, setStatusReason] = useState('');

  const category = account.accountCategory || 'TRANSACTION';
  const categoryConfig = accountCategoryConfig[category] || accountCategoryConfig.TRANSACTION;
  const CategoryIcon = categoryConfig.icon;
  const statusCfg = statusConfig[account.status];

  // Fetch statement when tab changes
  const fetchStatement = useCallback(async () => {
    setStmtLoading(true);
    try {
      const res = await api.statements.getAccountStatement(account.id, fromDate, toDate);
      if (res.success) setStatement(res.data);
    } catch (e) {
      toast.error('Failed to load statement');
    } finally {
      setStmtLoading(false);
    }
  }, [account.id, fromDate, toDate]);

  useEffect(() => {
    if (activeTab === 'statements') fetchStatement();
  }, [activeTab, fetchStatement]);

  const handleStatusAction = (action: VaStatus) => {
    if (action === 'SUSPENDED' || action === 'BLOCKED') {
      setStatusReasonModal({ action });
    } else {
      onStatusChange(account.id, action);
    }
  };

  const confirmStatusChange = () => {
    if (statusReasonModal) {
      onStatusChange(account.id, statusReasonModal.action, statusReason);
      setStatusReasonModal(null);
      setStatusReason('');
    }
  };

  const handleExport = async (format: string) => {
    try {
      const res = await api.statements.generate(account.id, format, fromDate, toDate);
      if (res.success) {
        toast.success(`${format} statement generated`);
      }
    } catch {
      toast.error('Failed to generate statement');
    }
  };

  const copyVaNumber = () => {
    copyToClipboard(account.vaNumber);
    toast.success('Copied!');
  };

  const drawerFooter = (
    <div className="flex items-center justify-between">
      <div className="flex gap-2">
        {account.status === 'ACTIVE' && (
          <>
            <Button variant="outline" size="sm" onClick={() => handleStatusAction('SUSPENDED')}>
              <PauseCircle className="w-4 h-4 mr-1" /> Suspend
            </Button>
            <Button variant="outline" size="sm" className="text-error-600 border-error-200 hover:bg-error-50 dark:text-error-300 dark:border-error-500/30 dark:hover:bg-error-500/10" onClick={() => handleStatusAction('BLOCKED')}>
              <Ban className="w-4 h-4 mr-1" /> Block
            </Button>
          </>
        )}
        {(account.status === 'SUSPENDED' || account.status === 'BLOCKED') && (
          <Button variant="outline" size="sm" className="text-success-600 border-success-200 hover:bg-success-50 dark:text-success-300 dark:border-success-500/30 dark:hover:bg-success-500/10" onClick={() => handleStatusAction('ACTIVE')}>
            <Play className="w-4 h-4 mr-1" /> Reactivate
          </Button>
        )}
      </div>
      <Button onClick={() => onEdit(account)}>
        <Edit className="w-4 h-4 mr-1" /> Edit Account
      </Button>
    </div>
  );

  return (
    <>
    <Drawer
      isOpen
      onClose={onClose}
      size="lg"
      title={(
        <span className="flex items-center gap-3">
          <span className={cn("w-10 h-10 rounded-lg flex items-center justify-center", categoryConfig.bgColor)}>
            <CategoryIcon className={cn("w-5 h-5", categoryConfig.color)} />
          </span>
          {account.vaName}
        </span>
      )}
      subtitle={(
        <span className="flex items-center gap-2 text-sm text-neutral-500 dark:text-neutral-400">
          <span className="font-mono">{account.vaNumber}</span>
          <button onClick={copyVaNumber} className="hover:text-primary-600">
            <Copy className="w-3.5 h-3.5" />
          </button>
        </span>
      )}
      footer={drawerFooter}
    >
    <div className="flex flex-col h-full">
      {/* Tabs */}
      <div className="flex border-b border-neutral-200 dark:border-primary-800">
        {[
          { id: 'overview' as DetailTab, label: 'Overview', icon: Eye },
          { id: 'statements' as DetailTab, label: 'Statements', icon: FileText },
          { id: 'activity' as DetailTab, label: 'Activity', icon: Activity },
        ].map((tab) => (
          <button
            key={tab.id}
            onClick={() => setActiveTab(tab.id)}
            className={cn(
              "flex items-center gap-2 px-4 py-3 text-sm font-medium border-b-2 transition-colors",
              activeTab === tab.id
                ? "border-primary-600 text-primary-600 dark:text-primary-200"
                : "border-transparent text-neutral-500 hover:text-primary-600 dark:text-neutral-400"
            )}
          >
            <tab.icon className="w-4 h-4" />
            {tab.label}
          </button>
        ))}
      </div>

      {/* Content */}
      <div className="flex-1 overflow-y-auto p-4">
        {/* OVERVIEW TAB */}
        {activeTab === 'overview' && (
          <div className="space-y-6">
            {/* Status & Balance */}
            <div className="grid grid-cols-2 gap-4">
              <Card className="bg-neutral-50 dark:bg-primary-950">
                <p className="text-xs text-neutral-500 mb-1 dark:text-neutral-400">Status</p>
                <Badge variant={statusCfg?.variant as any} size="md">
                  {statusCfg?.label}
                </Badge>
                {(account.suspensionReason || account.blockReason) && (
                  <p className="text-xs text-neutral-500 mt-2 dark:text-neutral-400">
                    Reason: {account.suspensionReason || account.blockReason}
                  </p>
                )}
              </Card>
              <Card className="bg-neutral-50 dark:bg-primary-950">
                <p className="text-xs text-neutral-500 mb-1 dark:text-neutral-400">Category</p>
                <div className="flex items-center gap-2">
                  <CategoryIcon className={cn("w-4 h-4", categoryConfig.color)} />
                  <span className="font-medium text-primary-900 dark:text-neutral-50">{categoryConfig.label}</span>
                </div>
              </Card>
            </div>

            {/* Balances */}
            <Card>
              <h3 className="text-sm font-semibold text-primary-900 mb-3 dark:text-neutral-50">Balances</h3>
              <div className="grid grid-cols-3 gap-4">
                <div>
                  <p className="text-xs text-neutral-500 dark:text-neutral-400">
                    {account.accountCategory === 'ROOT' || account.accountCategory === 'AGGREGATION'
                      ? 'Aggregated'
                      : account.accountCategory === 'CURRENCY_MIRROR'
                      ? 'Mirror'
                      : 'Current'}
                  </p>
                  <p className={cn(
                    "text-lg font-bold",
                    getEffectiveBalance(account) < 0
                      ? "text-error-600 dark:text-error-300"
                      : "text-primary-900 dark:text-neutral-50"
                  )}>
                    {formatCurrency(getEffectiveBalance(account), account.currencyCode)}
                  </p>
                </div>
                <div>
                  <p className="text-xs text-neutral-500 dark:text-neutral-400">Available</p>
                  <p className={cn(
                    "text-lg font-bold",
                    account.availableBalance < 0
                      ? "text-error-600 dark:text-error-300"
                      : "text-success-600 dark:text-success-300"
                  )}>
                    {formatCurrency(account.availableBalance, account.currencyCode)}
                  </p>
                </div>
                <div>
                  <p className="text-xs text-neutral-500 dark:text-neutral-400">Held</p>
                  <p className="text-lg font-bold text-warning-600 dark:text-warning-300">
                    {formatCurrency(account.heldBalance || 0, account.currencyCode)}
                  </p>
                </div>
              </div>
              {/* Show aggregated balance details for hierarchy nodes */}
              {(account.accountCategory === 'ROOT' || account.accountCategory === 'AGGREGATION') &&
               account.aggregatedBalanceBase !== undefined && account.baseCurrency && (
                <div className="mt-3 pt-3 border-t border-neutral-100 dark:border-primary-800/60">
                  <p className="text-xs text-neutral-500 dark:text-neutral-400">Aggregated (Base Currency)</p>
                  <p className="text-sm font-semibold text-cat-2">
                    {formatCurrency(account.aggregatedBalanceBase, account.baseCurrency)}
                  </p>
                </div>
              )}
              {account.accountCategory === 'CURRENCY_MIRROR' &&
               account.balanceInBase !== undefined && account.baseCurrency && (
                <div className="mt-3 pt-3 border-t border-neutral-100 dark:border-primary-800/60">
                  <p className="text-xs text-neutral-500 dark:text-neutral-400">Balance in Base Currency</p>
                  <p className="text-sm font-semibold text-cat-5">
                    {formatCurrency(account.balanceInBase, account.baseCurrency)}
                  </p>
                </div>
              )}
            </Card>

            {/* Account Details */}
            <Card>
              <h3 className="text-sm font-semibold text-primary-900 mb-3 dark:text-neutral-50">Account Details</h3>
              <div className="space-y-3">
                <div className="flex justify-between text-sm">
                  <span className="text-neutral-500 dark:text-neutral-400">Currency</span>
                  <span className="font-medium text-primary-900 dark:text-neutral-50">{account.currencyCode}</span>
                </div>
                {account.viban && (
                  <div className="flex justify-between text-sm">
                    <span className="text-neutral-500 dark:text-neutral-400">VIBAN</span>
                    <span className="font-mono text-primary-900 dark:text-neutral-50">{account.viban}</span>
                  </div>
                )}
                {account.programName && (
                  <div className="flex justify-between text-sm">
                    <span className="text-neutral-500 dark:text-neutral-400">Program</span>
                    <span className="font-medium text-primary-900 dark:text-neutral-50">{account.programName}</span>
                  </div>
                )}
                {account.owningEntityCode && (
                  <div className="flex justify-between text-sm">
                    <span className="text-neutral-500 dark:text-neutral-400">Owning Entity</span>
                    <Badge variant="neutral" size="sm" className="bg-cat-2-soft text-cat-2 dark:bg-cat-2/15">
                      {account.owningEntityCode}
                    </Badge>
                  </div>
                )}
                {account.hierarchyPathVa && (
                  <div className="flex justify-between text-sm">
                    <span className="text-neutral-500 dark:text-neutral-400">Hierarchy</span>
                    <span className="font-mono text-xs text-neutral-700 truncate max-w-[200px] dark:text-neutral-200">
                      {account.hierarchyPathVa}
                    </span>
                  </div>
                )}
                {account.externalReference && (
                  <div className="flex justify-between text-sm">
                    <span className="text-neutral-500 dark:text-neutral-400">External Ref</span>
                    <span className="font-mono text-primary-900 dark:text-neutral-50">{account.externalReference}</span>
                  </div>
                )}
                <div className="flex justify-between text-sm">
                  <span className="text-neutral-500 dark:text-neutral-400">Created</span>
                  <span className="text-primary-900 dark:text-neutral-50">{formatDate(account.createdAt)}</span>
                </div>
                {account.kycVerified !== undefined && (
                  <div className="flex justify-between text-sm">
                    <span className="text-neutral-500 dark:text-neutral-400">KYC</span>
                    <Badge variant={account.kycVerified ? 'success' : 'warning'} size="sm">
                      {account.kycVerified ? `Verified (L${account.kycLevel || 1})` : 'Pending'}
                    </Badge>
                  </div>
                )}
              </div>
            </Card>

            {/* Limits (if any) */}
            {(account.dailyLimit || account.monthlyLimit) && (
              <Card>
                <h3 className="text-sm font-semibold text-primary-900 mb-3 dark:text-neutral-50">Limits</h3>
                <div className="space-y-2">
                  {account.perTransactionLimit && (
                    <div className="flex justify-between text-sm">
                      <span className="text-neutral-500 dark:text-neutral-400">Per Transaction</span>
                      <span className="font-medium">{formatCurrency(account.perTransactionLimit, account.currencyCode)}</span>
                    </div>
                  )}
                  {account.dailyLimit && (
                    <div className="flex justify-between text-sm">
                      <span className="text-neutral-500 dark:text-neutral-400">Daily</span>
                      <span className="font-medium">{formatCurrency(account.dailyLimit, account.currencyCode)}</span>
                    </div>
                  )}
                  {account.monthlyLimit && (
                    <div className="flex justify-between text-sm">
                      <span className="text-neutral-500 dark:text-neutral-400">Monthly</span>
                      <span className="font-medium">{formatCurrency(account.monthlyLimit, account.currencyCode)}</span>
                    </div>
                  )}
                </div>
              </Card>
            )}
          </div>
        )}

        {/* STATEMENTS TAB */}
        {activeTab === 'statements' && (
          <div className="space-y-4">
            {/* Date Range */}
            <Card className="bg-neutral-50 dark:bg-primary-950">
              <div className="flex flex-wrap items-end gap-4">
                <div>
                  <label className="block text-xs font-medium text-neutral-600 mb-1 dark:text-neutral-300">From</label>
                  <Input
                    type="date"
                    value={fromDate}
                    onChange={(e) => setFromDate(e.target.value)}
                    className="w-40"
                  />
                </div>
                <div>
                  <label className="block text-xs font-medium text-neutral-600 mb-1 dark:text-neutral-300">To</label>
                  <Input
                    type="date"
                    value={toDate}
                    onChange={(e) => setToDate(e.target.value)}
                    className="w-40"
                  />
                </div>
                <Button onClick={fetchStatement} disabled={stmtLoading} size="sm">
                  {stmtLoading ? <Loader2 className="w-4 h-4 animate-spin" /> : 'Load'}
                </Button>
                <div className="flex gap-2 ml-auto">
                  <Button variant="outline" size="sm" onClick={() => handleExport('PDF')}>
                    <Download className="w-4 h-4 mr-1" /> PDF
                  </Button>
                  <Button variant="outline" size="sm" onClick={() => handleExport('CSV')}>
                    <Download className="w-4 h-4 mr-1" /> CSV
                  </Button>
                </div>
              </div>
            </Card>

            {/* Statement Summary */}
            {statement && (
              <>
                <div className="grid grid-cols-4 gap-3">
                  <Card className="bg-neutral-50 p-3 dark:bg-primary-950">
                    <p className="text-xs text-neutral-500 dark:text-neutral-400">Opening</p>
                    <p className="font-bold text-primary-900 dark:text-neutral-50">{formatCurrency(statement.openingBalance, statement.currencyCode)}</p>
                  </Card>
                  <Card className="bg-success-50 p-3 dark:bg-success-500/10">
                    <p className="text-xs text-success-600 dark:text-success-300">Credits</p>
                    <p className="font-bold text-success-700 dark:text-success-300">+{formatCurrency(statement.totalCredits, statement.currencyCode)}</p>
                  </Card>
                  <Card className="bg-error-50 p-3 dark:bg-error-500/10">
                    <p className="text-xs text-error-600 dark:text-error-300">Debits</p>
                    <p className="font-bold text-error-700 dark:text-error-300">-{formatCurrency(statement.totalDebits, statement.currencyCode)}</p>
                  </Card>
                  <Card className="bg-primary-50 p-3 dark:bg-primary-800/40">
                    <p className="text-xs text-primary-600 dark:text-primary-200">Closing</p>
                    <p className="font-bold text-primary-900 dark:text-neutral-50">{formatCurrency(statement.closingBalance, statement.currencyCode)}</p>
                  </Card>
                </div>

                {/* Transactions */}
                <Card padding="none">
                  <div className="overflow-x-auto max-h-64">
                    <table className="w-full text-sm">
                      <thead className="bg-neutral-50 sticky top-0 dark:bg-primary-950">
                        <tr>
                          <th className="text-left p-2 font-medium text-neutral-600 dark:text-neutral-300">Date</th>
                          <th className="text-left p-2 font-medium text-neutral-600 dark:text-neutral-300">Description</th>
                          <th className="text-right p-2 font-medium text-neutral-600 dark:text-neutral-300">Debit</th>
                          <th className="text-right p-2 font-medium text-neutral-600 dark:text-neutral-300">Credit</th>
                          <th className="text-right p-2 font-medium text-neutral-600 dark:text-neutral-300">Balance</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-neutral-100 dark:divide-primary-800/60">
                        {statement.transactions?.map((tx, idx) => {
                          const isDebitTxn = isDebit(tx.movementType);
                          const isCreditTxn = isCredit(tx.movementType);
                          return (
                            <tr key={idx} className="hover:bg-neutral-50 dark:hover:bg-primary-800/50">
                              <td className="p-2 text-neutral-700 dark:text-neutral-200">{new Date(tx.transactionDate).toLocaleDateString()}</td>
                              <td className="p-2 text-neutral-700 truncate max-w-[150px] dark:text-neutral-200">{tx.description || tx.referenceNumber}</td>
                              <td className="p-2 text-right text-error-600 dark:text-error-300">
                                {isDebitTxn ? formatCurrency(tx.amount, statement.currencyCode) : '-'}
                              </td>
                              <td className="p-2 text-right text-success-600 dark:text-success-300">
                                {isCreditTxn ? formatCurrency(tx.amount, statement.currencyCode) : '-'}
                              </td>
                              <td className="p-2 text-right font-medium text-primary-900 dark:text-neutral-50">
                                {formatCurrency(tx.balanceAfter, statement.currencyCode)}
                              </td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                    {(!statement.transactions || statement.transactions.length === 0) && (
                      <div className="text-center py-8 text-neutral-500 dark:text-neutral-400">No transactions in this period</div>
                    )}
                  </div>
                </Card>
              </>
            )}

            {stmtLoading && (
              <div className="flex items-center justify-center py-8">
                <Loader2 className="w-6 h-6 animate-spin text-primary-500" />
              </div>
            )}
          </div>
        )}

        {/* ACTIVITY TAB */}
        {activeTab === 'activity' && (
          <div className="space-y-4">
            <Card>
              <h3 className="text-sm font-semibold text-primary-900 mb-3 dark:text-neutral-50">Status History</h3>
              <div className="space-y-3">
                <div className="flex items-center gap-3 text-sm">
                  <div className="w-8 h-8 rounded-full bg-success-100 flex items-center justify-center dark:bg-success-500/20">
                    <CheckCircle className="w-4 h-4 text-success-600 dark:text-success-300" />
                  </div>
                  <div>
                    <p className="font-medium text-primary-900 dark:text-neutral-50">Account Created</p>
                    <p className="text-xs text-neutral-500 dark:text-neutral-400">{formatDate(account.createdAt)}</p>
                  </div>
                </div>
                {account.status !== 'ACTIVE' && (
                  <div className="flex items-center gap-3 text-sm">
                    <div className={cn(
                      "w-8 h-8 rounded-full flex items-center justify-center",
                      account.status === 'SUSPENDED' ? "bg-warning-100 dark:bg-warning-500/20" : "bg-error-100 dark:bg-error-500/20"
                    )}>
                      {account.status === 'SUSPENDED' 
                        ? <PauseCircle className="w-4 h-4 text-warning-600 dark:text-warning-300" />
                        : <Ban className="w-4 h-4 text-error-600 dark:text-error-300" />
                      }
                    </div>
                    <div>
                      <p className="font-medium text-primary-900 dark:text-neutral-50">
                        Account {account.status === 'SUSPENDED' ? 'Suspended' : 'Blocked'}
                      </p>
                      {(account.suspensionReason || account.blockReason) && (
                        <p className="text-xs text-neutral-500 dark:text-neutral-400">
                          {account.suspensionReason || account.blockReason}
                        </p>
                      )}
                    </div>
                  </div>
                )}
              </div>
            </Card>

            <Card>
              <h3 className="text-sm font-semibold text-primary-900 mb-3 dark:text-neutral-50">Recent Activity</h3>
              <p className="text-sm text-neutral-500 dark:text-neutral-400">
                View the Statements tab for transaction history.
              </p>
            </Card>
          </div>
        )}
      </div>

    </div>
    </Drawer>

      {/* Status Reason Modal */}
      {statusReasonModal && (
        <Modal
          isOpen={true}
          onClose={() => setStatusReasonModal(null)}
          title={`${statusReasonModal.action === 'SUSPENDED' ? 'Suspend' : 'Block'} Account`}
          size="sm"
        >
          <div className="p-4 space-y-4">
            <p className="text-sm text-neutral-600 dark:text-neutral-300">
              Please provide a reason for {statusReasonModal.action === 'SUSPENDED' ? 'suspending' : 'blocking'} this account.
            </p>
            <textarea
              value={statusReason}
              onChange={(e) => setStatusReason(e.target.value)}
              placeholder="Enter reason..."
              className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm resize-none h-24 dark:border-primary-700"
            />
            <div className="flex justify-end gap-2">
              <Button variant="outline" onClick={() => setStatusReasonModal(null)}>Cancel</Button>
              <Button
                variant="danger"
                onClick={confirmStatusChange}
                disabled={!statusReason.trim()}
              >
                Confirm
              </Button>
            </div>
          </div>
        </Modal>
      )}
    </>
  );
};

// ============================================================================
// FILTER PANEL
// ============================================================================

interface FilterPanelProps {
  isOpen: boolean;
  filters: { status: string; currency: string; accountCategory: string };
  onFilterChange: (f: any) => void;
  onClose: () => void;
  onClear: () => void;
}

const FilterPanel: React.FC<FilterPanelProps> = ({ isOpen, filters, onFilterChange, onClose, onClear }) => {
  if (!isOpen) return null;

  return (
    <div className="border-b border-neutral-200 p-4 bg-neutral-50 dark:border-primary-800 dark:bg-primary-950">
      <div className="flex items-center justify-between mb-4">
        <h3 className="text-sm font-semibold text-primary-900 dark:text-neutral-50">Filters</h3>
        <button onClick={onClose}><X className="w-5 h-5 text-neutral-500 dark:text-neutral-400" /></button>
      </div>
      <div className="grid grid-cols-3 gap-4">
        <div>
          <label className="field-label block mb-1.5">Status</label>
          <select 
            className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm dark:border-primary-700" 
            value={filters.status} 
            onChange={(e) => onFilterChange({ ...filters, status: e.target.value })}
          >
            <option value="">All</option>
            {Object.entries(statusConfig).map(([k, c]) => (
              <option key={k} value={k}>{c.label}</option>
            ))}
          </select>
        </div>
        <div>
          <label className="field-label block mb-1.5">Account Type</label>
          <select 
            className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm dark:border-primary-700" 
            value={filters.accountCategory} 
            onChange={(e) => onFilterChange({ ...filters, accountCategory: e.target.value })}
          >
            <option value="">All</option>
            <optgroup label="Operational">
              <option value="TRANSACTION">Transaction</option>
              <option value="COLLECTION">Collection</option>
              <option value="DISBURSEMENT">Disbursement</option>
            </optgroup>
            <optgroup label="System">
              <option value="CURRENCY_MIRROR">Currency Mirror</option>
              <option value="SETTLEMENT">Settlement</option>
              <option value="EXCEPTION">Exception</option>
            </optgroup>
            <optgroup label="Treasury">
              <option value="INTERCOMPANY">Intercompany</option>
              <option value="ESCROW">Escrow</option>
            </optgroup>
          </select>
        </div>
        <div>
          <label className="field-label block mb-1.5">Currency</label>
          <CurrencyPicker
            className="text-sm"
            value={filters.currency}
            onChange={(c) => onFilterChange({ ...filters, currency: c })}
            allowEmpty
          />
        </div>
      </div>
      <div className="flex justify-end gap-3 mt-4">
        <Button variant="ghost" size="sm" onClick={onClear}>Clear All</Button>
        <Button size="sm" onClick={onClose}>Apply</Button>
      </div>
    </div>
  );
};

// ============================================================================
// MAIN PAGE COMPONENT
// ============================================================================

interface VirtualAccountsPageProps {
  onNavigate?: (page: string) => void;
}

const VirtualAccountsPage: React.FC<VirtualAccountsPageProps> = ({ onNavigate: _onNavigate }) => {
  // Data state
  const [accounts, setAccounts] = useState<VirtualAccount[]>([]);
  const [stats, setStats] = useState<VirtualAccountStats>({
    totalAccounts: 0, activeAccounts: 0, suspendedAccounts: 0, blockedAccounts: 0,
    totalBalance: 0, availableBalance: 0
  });
  const [loading, setLoading] = useState(true);

  // Reference data
  const [programs, setPrograms] = useState<Program[]>([]);
  const [corporates, setCorporates] = useState<Corporate[]>([]);
  const [, setPhysicalAccounts] = useState<PhysicalAccount[]>([]);
  const [, setHierarchyNodes] = useState<HierarchyNode[]>([]);

  // Selection state (session-only)
  const [selectedCorporateId, setSelectedCorporateId] = useState('');
  const [selectedProgramId, setSelectedProgramId] = useState('');

  // UI state
  const [viewMode, setViewMode] = useState<ViewMode>('table');
  const [searchQuery, setSearchQuery] = useState('');
  const [showFilters, setShowFilters] = useState(false);
  const [filters, setFilters] = useState({ status: '', currency: '', accountCategory: '' });
  const [currentPage, setCurrentPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [totalElements, setTotalElements] = useState(0);
  const pageSize = 15;

  // Modal/Detail state
  const [selectedAccount, setSelectedAccount] = useState<VirtualAccount | null>(null);
  const [editAccount, setEditAccount] = useState<VirtualAccount | null>(null);
  const [showCreateModal, setShowCreateModal] = useState(false);

  // Load reference data
  const loadReferenceData = useCallback(async () => {
    try {
      const [programsRes, corporatesRes, accountsRes, hierarchyRes] = await Promise.all([
        api.programs.getAll(),
        api.corporates.getAll(),
        api.physicalAccounts.getAll(),
        api.hierarchy.getNodes().catch(() => ({ success: false, data: [] })),
      ]);

      // Programs are already extracted by the api helper
      if (programsRes.success && programsRes.data) {
        setPrograms(Array.isArray(programsRes.data) ? programsRes.data : []);
        console.log('Loaded programs:', programsRes.data);
      }
      
      // Corporates are already extracted and mapped by the api helper
      if (corporatesRes.success && corporatesRes.data) {
        setCorporates(Array.isArray(corporatesRes.data) ? corporatesRes.data : []);
        console.log('Loaded corporates:', corporatesRes.data);
      }
      
      if (accountsRes.success && accountsRes.data) {
        setPhysicalAccounts(Array.isArray(accountsRes.data) ? accountsRes.data : []);
      }
      
      if (hierarchyRes.success && hierarchyRes.data) {
        setHierarchyNodes(Array.isArray(hierarchyRes.data) ? hierarchyRes.data : []);
      }
    } catch (error) {
      console.error('Failed to load reference data:', error);
      toast.error('Failed to load reference data');
    }
  }, []);

  // Load accounts data
  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      let response;

      if (searchQuery) {
        response = await api.virtualAccounts.search(searchQuery, currentPage, pageSize);
      } else if (selectedCorporateId) {
        response = await api.virtualAccounts.getByCorporate(selectedCorporateId, currentPage, pageSize);
      } else if (selectedProgramId) {
        response = await api.virtualAccounts.getByProgram(selectedProgramId, currentPage, pageSize);
      } else {
        response = await api.virtualAccounts.getAll(currentPage, pageSize);
      }

      if (response.success && response.data) {
        let data = Array.isArray(response.data) ? response.data : [];

        // Apply client-side filters
        if (filters.status) data = data.filter(a => a.status === filters.status);
        if (filters.currency) data = data.filter(a => a.currencyCode === filters.currency);
        if (filters.accountCategory) data = data.filter(a => a.accountCategory === filters.accountCategory);

        setAccounts(data);
        if (response.pagination) {
          setTotalPages(response.pagination.totalPages);
          setTotalElements(response.pagination.totalElements);
        } else {
          setTotalPages(1);
          setTotalElements(data.length);
        }
      }

      // Load stats
      const statsRes = await api.virtualAccounts.getStats(selectedCorporateId || undefined, selectedProgramId || undefined);
      if (statsRes.success && statsRes.data) {
        setStats(statsRes.data);
      }
    } catch (error) {
      console.error('Failed to load data:', error);
      toast.error('Failed to load accounts');
    } finally {
      setLoading(false);
    }
  }, [searchQuery, currentPage, filters, selectedCorporateId, selectedProgramId]);

  useEffect(() => { loadReferenceData(); }, [loadReferenceData]);
  useEffect(() => { loadData(); }, [loadData]);

  // Reset page when filters change
  useEffect(() => { setCurrentPage(0); }, [selectedCorporateId, selectedProgramId, searchQuery, filters]);

  // Handlers
  const handleCreateSuccess = () => {
    setShowCreateModal(false);
    loadData();
    toast.success('Account created successfully');
  };

  const handleStatusChange = async (id: string, status: VaStatus, reason?: string) => {
    try {
      const res = await api.virtualAccounts.updateStatus(id, status, reason);
      if (res.success && res.data) {
        setAccounts(p => p.map(a => a.id === id ? res.data : a));
        if (selectedAccount?.id === id) setSelectedAccount(res.data);
        toast.success(`Account ${status.toLowerCase()}`);
      } else {
        toast.error(res.message || 'Failed to update status');
      }
    } catch {
      toast.error('Failed to update status');
    }
  };

  const handleExport = async () => {
    try {
      const res = await api.virtualAccounts.export(selectedCorporateId || undefined, selectedProgramId || undefined);
      if (res.success) {
        // In real app, trigger download
        toast.success('Export started');
      }
    } catch {
      toast.error('Failed to export');
    }
  };

  const clearFilters = () => setFilters({ status: '', currency: '', accountCategory: '' });

  const activeFiltersCount = Object.values(filters).filter(Boolean).length;

  // Register the primary CTA in the Layout header. Same pattern as MBL /
  // Treasury Hierarchy — removes the floating-button-in-page anti-pattern.
  usePageHeaderActions(
    () => (
      <Button
        leftIcon={<Plus className="w-4 h-4" />}
        onClick={() => setShowCreateModal(true)}
      >
        New Account
      </Button>
    ),
    []
  );

  // Loading state
  if (loading && accounts.length === 0) {
    return (
      <div className="flex items-center justify-center h-96">
        <Loader2 className="w-8 h-8 animate-spin text-primary-600 dark:text-primary-200" />
        <p className="ml-3 text-neutral-500 dark:text-neutral-400">Loading...</p>
      </div>
    );
  }

  return (
    <Page>
      {/* In-body identity (Phase 10) — aligns with Bank Accounts /
          VIBAN Management page header treatment. The "New Account" CTA
          lives in the Aperture Layout header via usePageHeaderActions
          above. No floating in-page button. */}
      <PageHeader
        title="Virtual Accounts"
        description="Browse and manage virtual accounts across pooling, in-house bank, escrow, and other programs. Filter by corporate and program."
      />

      {/* Corporate & Program Selector */}
      <ScopeSelector mode="corporate-program"
        corporates={corporates}
        programs={programs}
        selectedCorporateId={selectedCorporateId}
        selectedProgramId={selectedProgramId}
        onCorporateChange={setSelectedCorporateId}
        onProgramChange={setSelectedProgramId}
        loading={loading}
      />

      {/* Headline financial figures — Total Balance + Available side-by-side.
          Treasurer's first read of the page. Operational tiles sit below.
          Note: Available can legitimately exceed Total Balance when credit
          accounts are seeded (availableBalance = creditLimit on init). The
          sub-text on Available makes that explicit so the delta isn't read
          as a data bug. */}
      <HeroMetricCard
        primary={{
          label: 'Total Balance',
          value: formatCurrency(stats.totalBalance),
          trend: '+8.2%',
          trendTone: 'success',
          sub: 'Sum of current balances across programs and currencies',
        }}
        secondary={{
          label: 'Available',
          value: formatCurrency(stats.availableBalance),
          sub: 'Spendable today (includes credit headroom)',
        }}
        icon={<Banknote className="w-7 h-7 text-accent-600 dark:text-accent-300" />}
      />

      {/* Operational metrics — secondary strip below the hero. */}
      <StatsCards stats={stats} loading={loading} />

      {/* Main Content */}
      <Card padding="none">
        {/* Toolbar */}
        <div className="p-4 border-b border-neutral-200 dark:border-primary-800">
          <div className="flex flex-col sm:flex-row gap-4">
            {/* Search */}
            <div className="flex-1">
              <Input
                placeholder="Search by account name, VA number, VIBAN..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                leftIcon={<Search className="w-4 h-4" />}
              />
            </div>

            {/* View Mode Toggle */}
            <div className="flex items-center gap-1 bg-neutral-100 rounded-lg p-1 dark:bg-primary-800">
              <button
                onClick={() => setViewMode('table')}
                className={cn(
                  "flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium rounded-md transition-colors",
                  viewMode === 'table' ? "bg-white text-primary-600 shadow-sm dark:bg-primary-900 dark:text-primary-200" : "text-neutral-600 hover:text-primary-600 dark:text-neutral-300"
                )}
              >
                <CreditCard className="w-4 h-4" /> Table
              </button>
              <button
                onClick={() => setViewMode('tree')}
                className={cn(
                  "flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium rounded-md transition-colors",
                  viewMode === 'tree' ? "bg-white text-primary-600 shadow-sm dark:bg-primary-900 dark:text-primary-200" : "text-neutral-600 hover:text-primary-600 dark:text-neutral-300"
                )}
              >
                <FolderTree className="w-4 h-4" /> Tree
              </button>
            </div>

            {/* Actions */}
            <div className="flex gap-2">
              <Button 
                variant={showFilters ? 'secondary' : 'outline'} 
                leftIcon={<Filter className="w-4 h-4" />} 
                onClick={() => setShowFilters(!showFilters)}
              >
                Filters
                {activeFiltersCount > 0 && (
                  <Badge variant="info" size="sm" className="ml-1">{activeFiltersCount}</Badge>
                )}
              </Button>
              <Button 
                variant="outline" 
                leftIcon={<RefreshCw className={cn("w-4 h-4", loading && "animate-spin")} />} 
                onClick={loadData}
              >
                Refresh
              </Button>
              <Button variant="outline" leftIcon={<Download className="w-4 h-4" />} onClick={handleExport}>
                Export
              </Button>
            </div>
          </div>
        </div>

        {/* Filters */}
        <FilterPanel
          isOpen={showFilters}
          filters={filters}
          onFilterChange={setFilters}
          onClose={() => setShowFilters(false)}
          onClear={clearFilters}
        />

        {/* Content */}
        {viewMode === 'tree' ? (
          <div className="p-4">
            <TreeView accounts={accounts} loading={loading} onView={setSelectedAccount} />
          </div>
        ) : (
          <>
            {accounts.length > 0 ? (
              <>
                {/* Desktop Table View */}
                <div className="hidden md:block overflow-x-auto">
                  <table className="data-table">
                    <thead className="data-table-header">
                      <tr>
                        <th className="data-table-header-cell">Account</th>
                        <th className="data-table-header-cell">Program</th>
                        <th className="data-table-header-cell">Category</th>
                        <th className="data-table-header-cell text-right">Balance</th>
                        <th className="data-table-header-cell">Status</th>
                        <th className="data-table-header-cell w-16">Actions</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-neutral-100 dark:divide-primary-800/60">
                      {accounts.map((a) => (
                        <AccountRow
                          key={a.id}
                          account={a}
                          onView={setSelectedAccount}
                          onEdit={setEditAccount}
                          onStatusChange={handleStatusChange}
                        />
                      ))}
                    </tbody>
                  </table>
                </div>

                {/* Mobile Card View */}
                <div className="md:hidden p-4 space-y-3">
                  {accounts.map((account, idx) => (
                    <AccountMobileCard
                      key={account.id}
                      account={account}
                      onView={setSelectedAccount}
                      index={idx}
                    />
                  ))}
                </div>

                {/* Pagination */}
                <div className="flex flex-col sm:flex-row items-center justify-between gap-4 px-4 py-4 border-t border-neutral-200 dark:border-primary-800">
                  <p className="text-sm text-neutral-500 order-2 sm:order-1 dark:text-neutral-400">
                    Showing{' '}
                    <span className="font-medium text-primary-900 dark:text-neutral-50">
                      {Math.min(currentPage * pageSize + 1, totalElements)}
                    </span>
                    {' '}to{' '}
                    <span className="font-medium text-primary-900 dark:text-neutral-50">
                      {Math.min((currentPage + 1) * pageSize, totalElements)}
                    </span>
                    {' '}of{' '}
                    <span className="font-medium text-primary-900 dark:text-neutral-50">{totalElements}</span>
                    {' '}accounts
                  </p>
                  <div className="flex items-center gap-1 order-1 sm:order-2">
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={currentPage === 0}
                      onClick={() => setCurrentPage(currentPage - 1)}
                    >
                      <ChevronLeft className="w-4 h-4" />
                    </Button>
                    <div className="hidden sm:flex items-center gap-1">
                      {Array.from({ length: Math.min(totalPages, 5) }, (_, i) => {
                        const p = currentPage < 3 ? i : currentPage - 2 + i;
                        if (p >= totalPages) return null;
                        return (
                          <Button
                            key={p}
                            variant={currentPage === p ? 'primary' : 'ghost'}
                            size="sm"
                            onClick={() => setCurrentPage(p)}
                          >
                            {p + 1}
                          </Button>
                        );
                      })}
                    </div>
                    <span className="sm:hidden text-sm text-neutral-600 px-2 dark:text-neutral-300">
                      {currentPage + 1} / {totalPages}
                    </span>
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={currentPage >= totalPages - 1}
                      onClick={() => setCurrentPage(currentPage + 1)}
                    >
                      <ChevronRight className="w-4 h-4" />
                    </Button>
                  </div>
                </div>
              </>
            ) : (
              <div className="p-8">
                <EmptyState
                  icon={<CreditCard className="w-12 h-12" />}
                  title="No accounts found"
                  description={
                    searchQuery || activeFiltersCount > 0 || selectedCorporateId || selectedProgramId
                      ? 'Try adjusting your search, filters, or selection.'
                      : 'Get started by creating your first virtual account.'
                  }
                  action={
                    searchQuery || activeFiltersCount > 0 ? (
                      <Button
                        variant="outline"
                        onClick={() => {
                          setSearchQuery('');
                          clearFilters();
                        }}
                      >
                        Clear Search
                      </Button>
                    ) : (
                      <Button
                        onClick={() => setShowCreateModal(true)}
                        leftIcon={<Plus className="w-4 h-4" />}
                      >
                        Create Account
                      </Button>
                    )
                  }
                />
              </div>
            )}
          </>
        )}
      </Card>

      {/* Legend */}
      <Card className="bg-neutral-50 dark:bg-primary-950">
        <div className="flex flex-wrap items-center gap-4 text-xs">
          <span className="font-medium text-neutral-600 dark:text-neutral-300">Account Types:</span>
          {(['TRANSACTION', 'COLLECTION', 'DISBURSEMENT', 'CURRENCY_MIRROR', 'INTERCOMPANY'] as AccountCategory[]).map(cat => {
            const config = accountCategoryConfig[cat];
            const Icon = config.icon;
            return (
              <div key={cat} className="flex items-center gap-1.5">
                <div className={cn("w-5 h-5 rounded flex items-center justify-center", config.bgColor)}>
                  <Icon className={cn("w-3 h-3", config.color)} />
                </div>
                <span className="text-neutral-600 dark:text-neutral-300">{config.label}</span>
              </div>
            );
          })}
        </div>
      </Card>

      {/* Account Detail Panel */}
      {selectedAccount && (
        <AccountDetailPanel
          account={selectedAccount}
          onClose={() => setSelectedAccount(null)}
          onEdit={(a) => { setSelectedAccount(null); setEditAccount(a); }}
          onStatusChange={handleStatusChange}
        />
      )}

      {/* Create Modal - Now uses hierarchy-aware flow */}
      <VaCreateModal
        isOpen={showCreateModal}
        onClose={() => setShowCreateModal(false)}
        onSuccess={handleCreateSuccess}
        corporates={corporates}
        programs={programs}
      />

      {/* Edit Modal - Note: VaCreateModal is now create-only; for edit, reload data */}
      {editAccount && (
        <VaCreateModal
          isOpen={!!editAccount}
          onClose={() => setEditAccount(null)}
          onSuccess={() => {
            loadData();
            setEditAccount(null);
            toast.success('Account updated');
          }}
          corporates={corporates}
          programs={programs}
        />
      )}
    </Page>
  );
};

export default VirtualAccountsPage;