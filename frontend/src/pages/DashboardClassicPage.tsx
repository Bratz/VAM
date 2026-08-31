import React, { useState, useEffect, useCallback } from 'react';
import {
  TrendingUp,
  TrendingDown,
  DollarSign,
  Building2,
  ArrowRightLeft,
  AlertTriangle,
  Clock,
  CheckCircle2,
  XCircle,
  Info,
  RefreshCw,
  ChevronRight,
  CreditCard,
  Landmark,
  PiggyBank,
  Banknote,
  Users,
  FileText,
  Send,
  ArrowUpRight,
  ArrowDownRight,
  Layers,
  Bell,
  ExternalLink,
  Briefcase,
  X,
  Loader2,
} from 'lucide-react';
import {
  AreaChart,
  Area,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  Legend,
} from 'recharts';
import { Card, Badge, Button, Skeleton, Select, StatusIconBadge } from '../components/ui';
import { HeroMetricCard } from '../components/ui/HeroMetricCard';
import { dashboardApi } from '../services/api';
import { cn, formatCurrency } from '../utils';
import { useTheme } from '../design-system/ThemeProvider';
import { Page } from '../components/layout/Page';

import { StatStrip } from '../components/layout/StatStrip';
// ============================================================================
// CORPORATE & PROGRAM TYPES
// ============================================================================

interface Corporate {
  id: string;
  name: string;
  legalName?: string;
}

interface Program {
  id: string;
  programCode: string;
  programName: string;
  programType: string;
  currencyCode: string;
}

// ============================================================================
// TYPESCRIPT INTERFACES
// ============================================================================

interface DashboardStats {
  totalAccounts: number;
  activeAccounts: number;
  totalPhysicalAccounts: number;
  totalTransactions: number;
  todayTransactions: number;
  todayVolume: number;
  pendingTransactions: number;
  totalCorporates: number;
  activeCorporates: number;
  activeSweepRules: number;
  activePools: number;
  pendingNettingCycles: number;
  totalBalance: number;
  availableBalance: number;
  balanceChange: number;
  pendingKyc: number;
}

interface BalanceTrendPoint {
  date: string;
  fullDate?: string;
  balance: number;
  available?: number;
  inflow?: number;
  outflow?: number;
}

interface TopAccount {
  id: string;
  vaNumber: string;
  viban: string;
  name: string;
  balance: number;
  availableBalance: number;
  currency: string;
  status: string;
}

interface RecentTransaction {
  id: string;
  reference: string;
  beneficiary: string;
  amount: number;
  currency: string;
  type: 'CREDIT' | 'DEBIT';
  status: string;
  date: string;
}

interface TreasurySummary {
  pooling: {
    activePools: number;
    totalPooledBalance: number;
    interestSavingsYtd: number;
    memberCount: number;
  };
  sweeping: {
    activeRules: number;
    totalSweptToday: number;
    executionsToday: number;
    nextExecution: string;
  };
  netting: {
    pendingCycles: number;
    totalSavingsYtd: number;
    averageSavingsPercent: number;
    settledCyclesMtd: number;
  };
  inHouseBank: {
    totalLoansOutstanding: number;
    totalDeposits: number;
    netInterestIncome: number;
    activeEntities: number;
  };
}

interface DashboardAlert {
  id: string;
  type: 'WARNING' | 'INFO' | 'ERROR' | 'SUCCESS';
  severity: 'HIGH' | 'MEDIUM' | 'LOW';
  title: string;
  message: string;
  action?: string;
  timestamp: string;
}

interface PendingApprovals {
  nettingCycles: Array<{
    id: string;
    reference: string;
    name: string;
    amount: number;
    savingsAmount: number;
    createdAt: string;
  }>;
  kycApplications: Array<{
    id: string;
    corporateId: string;
    name: string;
    submittedAt: string;
  }>;
  payables: Array<{
    id: string;
    invoiceNumber: string;
    vendorName: string;
    amount: number;
    dueDate: string;
  }>;
  transactions: Array<{
    id: string;
    reference: string;
    amount: number;
    description: string;
    createdAt: string;
  }>;
  totalPending: number;
  nettingCount: number;
  kycCount: number;
  payablesCount: number;
  transactionsCount: number;
}

interface DashboardClassicPageProps {
  onNavigate?: (page: string) => void;
}

// ============================================================================
// DEFAULT VALUES
// ============================================================================

const defaultStats: DashboardStats = {
  totalAccounts: 0,
  activeAccounts: 0,
  totalPhysicalAccounts: 0,
  totalTransactions: 0,
  todayTransactions: 0,
  todayVolume: 0,
  pendingTransactions: 0,
  totalCorporates: 0,
  activeCorporates: 0,
  activeSweepRules: 0,
  activePools: 0,
  pendingNettingCycles: 0,
  totalBalance: 0,
  availableBalance: 0,
  balanceChange: 0,
  pendingKyc: 0,
};

const defaultTreasury: TreasurySummary = {
  pooling: { activePools: 0, totalPooledBalance: 0, interestSavingsYtd: 0, memberCount: 0 },
  sweeping: { activeRules: 0, totalSweptToday: 0, executionsToday: 0, nextExecution: '' },
  netting: { pendingCycles: 0, totalSavingsYtd: 0, averageSavingsPercent: 0, settledCyclesMtd: 0 },
  inHouseBank: { totalLoansOutstanding: 0, totalDeposits: 0, netInterestIncome: 0, activeEntities: 0 },
};

const defaultApprovals: PendingApprovals = {
  nettingCycles: [],
  kycApplications: [],
  payables: [],
  transactions: [],
  totalPending: 0,
  nettingCount: 0,
  kycCount: 0,
  payablesCount: 0,
  transactionsCount: 0,
};

// ============================================================================
// UTILITY FUNCTIONS
// ============================================================================

// formatCurrency now comes from the shared util (Phase 12 Task D3) — the local
// nullable 0-dp en-AE clone was deleted; every call site here is typed `number`.

const formatNumber = (num: number | undefined | null): string => {
  if (num === undefined || num === null || isNaN(num)) return '0';
  if (num >= 1000000) {
    return (num / 1000000).toFixed(1) + 'M';
  }
  if (num >= 1000) {
    return (num / 1000).toFixed(1) + 'K';
  }
  return num.toString();
};

const formatRelativeTime = (dateString: string | undefined | null): string => {
  if (!dateString) return 'Unknown';
  try {
    const date = new Date(dateString);
    if (isNaN(date.getTime())) return 'Unknown';

    const now = new Date();
    const diffMs = now.getTime() - date.getTime();
    const diffMins = Math.floor(diffMs / 60000);
    const diffHours = Math.floor(diffMs / 3600000);
    const diffDays = Math.floor(diffMs / 86400000);

    if (diffMins < 1) return 'Just now';
    if (diffMins < 60) return `${diffMins}m ago`;
    if (diffHours < 24) return `${diffHours}h ago`;
    return `${diffDays}d ago`;
  } catch {
    return 'Unknown';
  }
};

const ensureArray = <T,>(value: T[] | null | undefined): T[] => {
  if (Array.isArray(value)) return value;
  return [];
};

// ============================================================================
// HIERARCHY WIDGETS - Premium Design (with actual data)
// ============================================================================

interface HierarchyWidgetProps {
  stats: DashboardStats;
  loading: boolean;
  onClick?: () => void;
}

const BalanceByLevelWidget: React.FC<HierarchyWidgetProps> = ({ stats, loading, onClick }) => {
  if (loading) {
    return (
      <Card className="animate-pulse">
        <div className="flex items-center gap-3 mb-4">
          <Skeleton className="w-10 h-10 rounded-xl" />
          <div className="flex-1">
            <Skeleton className="h-4 w-24 mb-1" />
            <Skeleton className="h-3 w-16" />
          </div>
        </div>
        <Skeleton className="h-24 rounded-xl" />
      </Card>
    );
  }

  const physicalPercent = stats.totalAccounts > 0
    ? Math.round((stats.totalPhysicalAccounts / stats.totalAccounts) * 100)
    : 0;

  return (
    <Card hover interactive={!!onClick} className="animate-fade-in cursor-pointer" style={{ animationDelay: '0.1s' }} onClick={onClick}>
      <div className="flex items-center gap-3 mb-4">
        {/* Tier 7 Design System Unification (2026-05-13): widget icon
            medallions flattened from inline `bg-gradient-to-br from-X-100
            to-X-50` gradients to the canonical StatusIconBadge. Body-level
            atmospherics already carry depth; inner-element gradients added
            noise without value. */}
        <StatusIconBadge tone="primary" icon={Layers} />
        <div>
          <h3 className="text-sm font-semibold text-neutral-900 dark:text-neutral-50">Account Structure</h3>
          <p className="text-xs text-neutral-500 dark:text-neutral-400">Physical vs Virtual</p>
        </div>
      </div>
      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <div className="w-3 h-3 rounded-full bg-primary-500" />
            <span className="text-xs text-neutral-600 dark:text-neutral-300">Physical</span>
          </div>
          <span className="text-sm font-semibold text-neutral-900 dark:text-neutral-50">{stats.totalPhysicalAccounts}</span>
        </div>
        <div className="h-2 bg-neutral-100 dark:bg-primary-800 rounded-full overflow-hidden">
          <div
            className="h-full bg-primary-500 rounded-full transition-all duration-500"
            style={{ width: `${physicalPercent}%` }}
          />
        </div>
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <div className="w-3 h-3 rounded-full bg-info-500" />
            <span className="text-xs text-neutral-600 dark:text-neutral-300">Virtual</span>
          </div>
          <span className="text-sm font-semibold text-neutral-900 dark:text-neutral-50">{stats.totalAccounts - stats.totalPhysicalAccounts}</span>
        </div>
      </div>
    </Card>
  );
};

const TopEntitiesWidget: React.FC<HierarchyWidgetProps> = ({ stats, loading, onClick }) => {
  if (loading) {
    return (
      <Card className="animate-pulse">
        <div className="flex items-center gap-3 mb-4">
          <Skeleton className="w-10 h-10 rounded-xl" />
          <div className="flex-1">
            <Skeleton className="h-4 w-24 mb-1" />
            <Skeleton className="h-3 w-16" />
          </div>
        </div>
        <Skeleton className="h-24 rounded-xl" />
      </Card>
    );
  }

  return (
    <Card hover interactive={!!onClick} className="animate-fade-in cursor-pointer" style={{ animationDelay: '0.15s' }} onClick={onClick}>
      <div className="flex items-center gap-3 mb-4">
        <StatusIconBadge tone="info" icon={Building2} />
        <div>
          <h3 className="text-sm font-semibold text-neutral-900 dark:text-neutral-50">Corporates</h3>
          <p className="text-xs text-neutral-500 dark:text-neutral-400">Entity overview</p>
        </div>
      </div>
      <div className="space-y-3">
        {/* Row backgrounds flattened: subtle solid tint instead of
            from-{tone}-50 → to-white gradient. Body atmospherics carry depth. */}
        <div className="flex items-center justify-between p-2.5 bg-info-50 dark:bg-info-500/10 rounded-lg">
          <span className="text-xs text-neutral-600 dark:text-neutral-300">Total</span>
          <span className="text-lg font-bold text-info-600 dark:text-info-300">{stats.totalCorporates}</span>
        </div>
        <div className="flex items-center justify-between p-2.5 bg-success-50 dark:bg-success-500/10 rounded-lg">
          <span className="text-xs text-neutral-600 dark:text-neutral-300">Active</span>
          <span className="text-lg font-bold text-success-600 dark:text-success-300">{stats.activeCorporates}</span>
        </div>
      </div>
    </Card>
  );
};

interface CollectionsWidgetProps {
  transactions: RecentTransaction[];
  loading: boolean;
  onClick?: () => void;
}

const VibanCollectionsWidget: React.FC<CollectionsWidgetProps> = ({ transactions, loading, onClick }) => {
  if (loading) {
    return (
      <Card className="animate-pulse">
        <div className="flex items-center gap-3 mb-4">
          <Skeleton className="w-10 h-10 rounded-xl" />
          <div className="flex-1">
            <Skeleton className="h-4 w-24 mb-1" />
            <Skeleton className="h-3 w-16" />
          </div>
        </div>
        <Skeleton className="h-24 rounded-xl" />
      </Card>
    );
  }

  const credits = transactions.filter(t => t.type === 'CREDIT');
  const totalCredits = credits.reduce((sum, t) => sum + (t.amount || 0), 0);

  return (
    <Card hover interactive={!!onClick} className="animate-fade-in cursor-pointer" style={{ animationDelay: '0.2s' }} onClick={onClick}>
      <div className="flex items-center gap-3 mb-4">
        <StatusIconBadge tone="success" icon={ArrowDownRight} />
        <div>
          <h3 className="text-sm font-semibold text-neutral-900 dark:text-neutral-50">Collections</h3>
          <p className="text-xs text-neutral-500 dark:text-neutral-400">Recent inflows</p>
        </div>
      </div>
      <div className="space-y-2">
        <div className="flex items-center justify-between">
          <span className="text-xs text-neutral-500 dark:text-neutral-400">Count</span>
          <span className="text-sm font-semibold text-neutral-900 dark:text-neutral-50">{credits.length}</span>
        </div>
        <div className="flex items-center justify-between p-3 bg-success-50 dark:bg-success-500/15 rounded-xl">
          <span className="text-xs font-medium text-success-700 dark:text-success-300">Total Volume</span>
          <span className="text-lg font-bold text-success-600 dark:text-success-300">{formatCurrency(totalCredits)}</span>
        </div>
      </div>
    </Card>
  );
};

const PoboActivityWidget: React.FC<CollectionsWidgetProps> = ({ transactions, loading, onClick }) => {
  if (loading) {
    return (
      <Card className="animate-pulse">
        <div className="flex items-center gap-3 mb-4">
          <Skeleton className="w-10 h-10 rounded-xl" />
          <div className="flex-1">
            <Skeleton className="h-4 w-24 mb-1" />
            <Skeleton className="h-3 w-16" />
          </div>
        </div>
        <Skeleton className="h-24 rounded-xl" />
      </Card>
    );
  }

  const debits = transactions.filter(t => t.type === 'DEBIT');
  const totalDebits = debits.reduce((sum, t) => sum + (t.amount || 0), 0);

  return (
    <Card hover interactive={!!onClick} className="animate-fade-in cursor-pointer" style={{ animationDelay: '0.25s' }} onClick={onClick}>
      <div className="flex items-center gap-3 mb-4">
        <StatusIconBadge tone="info" icon={ArrowUpRight} />
        <div>
          <h3 className="text-sm font-semibold text-neutral-900 dark:text-neutral-50">Payments</h3>
          <p className="text-xs text-neutral-500 dark:text-neutral-400">Recent outflows</p>
        </div>
      </div>
      <div className="space-y-2">
        <div className="flex items-center justify-between">
          <span className="text-xs text-neutral-500 dark:text-neutral-400">Count</span>
          <span className="text-sm font-semibold text-neutral-900 dark:text-neutral-50">{debits.length}</span>
        </div>
        <div className="flex items-center justify-between p-3 bg-info-50 dark:bg-info-500/15 rounded-xl">
          <span className="text-xs font-medium text-info-700 dark:text-info-300">Total Volume</span>
          <span className="text-lg font-bold text-info-600 dark:text-info-300">{formatCurrency(totalDebits)}</span>
        </div>
      </div>
    </Card>
  );
};

// ============================================================================
// CORPORATE & PROGRAM SELECTOR COMPONENT
// ============================================================================

interface SelectorBarProps {
  corporates: Corporate[];
  programs: Program[];
  selectedCorporateId: string;
  selectedProgramId: string;
  onCorporateChange: (id: string) => void;
  onProgramChange: (id: string) => void;
  loading?: boolean;
  onRefresh?: () => void;
  refreshing?: boolean;
}

const SelectorBar: React.FC<SelectorBarProps> = ({
  corporates,
  programs,
  selectedCorporateId,
  selectedProgramId,
  onCorporateChange,
  onProgramChange,
  loading,
  onRefresh,
  refreshing,
}) => {
  const corporateOptions = [
    { value: '', label: `All Corporates (${corporates.length})` },
    ...corporates.map((c) => ({
      value: c.id,
      label: c.name || c.legalName || 'Unnamed',
    })),
  ];

  const programOptions = [
    { value: '', label: `All Programs (${programs.length})` },
    ...programs.map((p) => ({
      value: p.id,
      label: `${p.programName} (${p.programType})`,
    })),
  ];

  return (
    <Card
      className="border-primary-100/50 dark:border-primary-700/60 animate-fade-in"
      style={{ animationDelay: '0.05s' }}
    >
      {/* Desktop Layout */}
      <div className="hidden sm:flex flex-wrap items-center gap-4 p-4">
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-lg bg-primary-100 dark:bg-primary-700 flex items-center justify-center">
            <Building2 className="w-4 h-4 text-primary-600 dark:text-primary-200" />
          </div>
          <div className="flex flex-col">
            <span className="text-xs text-neutral-500 dark:text-neutral-400">Corporate</span>
            <Select
              value={selectedCorporateId}
              onChange={(e) => onCorporateChange(e.target.value)}
              options={corporateOptions}
              disabled={loading}
              className="min-w-[200px]"
            />
          </div>
        </div>

        <div className="w-px h-12 bg-neutral-200 dark:bg-primary-800" />

        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-lg bg-accent-100 dark:bg-accent-500/10 flex items-center justify-center">
            <Briefcase className="w-4 h-4 text-accent-600 dark:text-accent-300" />
          </div>
          <div className="flex flex-col">
            <span className="text-xs text-neutral-500 dark:text-neutral-400">Program</span>
            <Select
              value={selectedProgramId}
              onChange={(e) => onProgramChange(e.target.value)}
              options={programOptions}
              disabled={loading}
              className="min-w-[220px]"
            />
          </div>
        </div>

        <div className="flex items-center gap-2 ml-auto">
          {(selectedCorporateId || selectedProgramId) && (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => {
                onCorporateChange('');
                onProgramChange('');
              }}
            >
              <X className="w-4 h-4 mr-1" />
              Clear
            </Button>
          )}

          {/* Refresh Button */}
          {onRefresh && (
            <button
              onClick={onRefresh}
              disabled={refreshing || loading}
              className={cn(
                'w-9 h-9 rounded-lg flex items-center justify-center transition-all duration-300',
                'bg-neutral-100 hover:bg-primary-100 hover:text-primary-600 dark:bg-primary-800 dark:hover:bg-primary-700 dark:hover:text-primary-200',
                'disabled:opacity-50 disabled:cursor-not-allowed',
                refreshing && 'bg-primary-100 dark:bg-primary-700'
              )}
              title="Refresh data"
            >
              <RefreshCw className={cn('w-4 h-4', refreshing && 'animate-spin text-primary-600 dark:text-primary-200')} />
            </button>
          )}

          {loading && !refreshing && (
            <Loader2 className="w-4 h-4 animate-spin text-primary-500" />
          )}
        </div>
      </div>

      {/* Mobile Layout */}
      <div className="sm:hidden p-4 space-y-3">
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-lg bg-primary-100 dark:bg-primary-700 flex items-center justify-center shrink-0">
            <Building2 className="w-4 h-4 text-primary-600 dark:text-primary-200" />
          </div>
          <div className="flex-1">
            <span className="text-xs text-neutral-500 dark:text-neutral-400">Corporate</span>
            <Select
              value={selectedCorporateId}
              onChange={(e) => onCorporateChange(e.target.value)}
              options={corporateOptions}
              disabled={loading}
              className="w-full"
            />
          </div>
        </div>

        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-lg bg-accent-100 dark:bg-accent-500/10 flex items-center justify-center shrink-0">
            <Briefcase className="w-4 h-4 text-accent-600 dark:text-accent-300" />
          </div>
          <div className="flex-1">
            <span className="text-xs text-neutral-500 dark:text-neutral-400">Program</span>
            <Select
              value={selectedProgramId}
              onChange={(e) => onProgramChange(e.target.value)}
              options={programOptions}
              disabled={loading}
              className="w-full"
            />
          </div>
        </div>

        {/* Mobile Actions Row */}
        <div className="flex items-center gap-2">
          {(selectedCorporateId || selectedProgramId) && (
            <Button
              variant="outline"
              size="sm"
              className="flex-1"
              onClick={() => {
                onCorporateChange('');
                onProgramChange('');
              }}
            >
              <X className="w-4 h-4 mr-1" />
              Clear
            </Button>
          )}

          {/* Refresh Button - Mobile */}
          {onRefresh && (
            <button
              onClick={onRefresh}
              disabled={refreshing || loading}
              className={cn(
                'w-9 h-9 rounded-lg flex items-center justify-center transition-all duration-300',
                'bg-neutral-100 hover:bg-primary-100 hover:text-primary-600 dark:bg-primary-800 dark:hover:bg-primary-700 dark:hover:text-primary-200',
                'disabled:opacity-50 disabled:cursor-not-allowed',
                refreshing && 'bg-primary-100 dark:bg-primary-700',
                !(selectedCorporateId || selectedProgramId) && 'ml-auto'
              )}
              title="Refresh data"
            >
              <RefreshCw className={cn('w-4 h-4', refreshing && 'animate-spin text-primary-600 dark:text-primary-200')} />
            </button>
          )}
        </div>
      </div>
    </Card>
  );
};

// ============================================================================
// SUB-COMPONENTS - Premium Design
// ============================================================================

// Stat Card Component
const StatCard: React.FC<{
  title: string;
  value: string | number;
  subtitle?: string;
  icon: React.ReactNode;
  trend?: number;
  iconBg?: string;
  loading?: boolean;
  onClick?: () => void;
  delay?: number;
}> = ({ title, value, subtitle, icon, trend, iconBg = 'bg-primary-100 dark:bg-primary-700', loading, onClick, delay = 0 }) => {
  if (loading) {
    return (
      <Card className="animate-pulse">
        <div className="flex items-start justify-between">
          <div className="space-y-3 flex-1">
            <Skeleton className="h-4 w-24" />
            <Skeleton className="h-8 w-32" />
            <Skeleton className="h-3 w-20" />
          </div>
          <Skeleton className="w-12 h-12 rounded-xl" />
        </div>
      </Card>
    );
  }

  return (
    <Card
      interactive={!!onClick}
      hover
      className={cn(
        'animate-fade-in group',
        onClick && 'cursor-pointer'
      )}
      style={{ animationDelay: `${delay}s` }}
      onClick={onClick}
    >
      <div className="flex items-start justify-between">
        <div>
          <p className="text-sm font-medium text-neutral-500 dark:text-neutral-400 tracking-wide">{title}</p>
          <p className="stat-value-sm mt-1.5">{value}</p>
          {(subtitle || trend !== undefined) && (
            <div className="flex items-center mt-2.5 space-x-2">
              {trend !== undefined && !isNaN(trend) && (
                <span
                  className={cn(
                    'inline-flex items-center text-sm font-semibold px-2 py-0.5 rounded-full',
                    trend >= 0
                      ? 'text-success-700 bg-success-50 dark:text-success-300 dark:bg-success-500/10'
                      : 'text-error-700 bg-error-50 dark:text-error-300 dark:bg-error-500/10'
                  )}
                >
                  {trend >= 0 ? (
                    <TrendingUp className="w-3.5 h-3.5 mr-1" />
                  ) : (
                    <TrendingDown className="w-3.5 h-3.5 mr-1" />
                  )}
                  {Math.abs(trend).toFixed(1)}%
                </span>
              )}
              {subtitle && <span className="text-sm text-neutral-500 dark:text-neutral-400">{subtitle}</span>}
            </div>
          )}
        </div>
        <div className={cn(
          'p-3 rounded-xl transition-transform duration-300',
          iconBg,
          onClick && 'group-hover:scale-110'
        )}>
          {icon}
        </div>
      </div>
      {onClick && (
        <div className="mt-4 pt-4 border-t border-neutral-100 dark:border-primary-800/60">
          <span className="text-sm text-primary-600 dark:text-primary-200 font-medium flex items-center group-hover:translate-x-1 transition-transform duration-300">
            View details <ChevronRight className="w-4 h-4 ml-1" />
          </span>
        </div>
      )}
    </Card>
  );
};

// Balance Chart Component
const BalanceChart: React.FC<{
  data: BalanceTrendPoint[];
  loading: boolean;
  onRefresh?: () => void;
}> = ({ data, loading, onRefresh }) => {
  const safeData = ensureArray(data);
  const { resolvedMode } = useTheme();
  const isDark = resolvedMode === 'dark';
  // Theme-aware chart palette
  const chartColors = {
    grid: isDark ? '#243b53' : '#e5e7eb',          // primary-800 / neutral-200
    axisLine: isDark ? '#334e68' : '#e5e7eb',      // primary-700
    tickFill: isDark ? '#9fb3c8' : '#6b7280',      // primary-300 / neutral-500
    tooltipBg: isDark ? 'rgba(16, 42, 67, 0.97)' : 'rgba(255, 255, 255, 0.98)',
    tooltipShadow: isDark ? '0 10px 40px -5px rgba(0, 0, 0, 0.5)' : '0 10px 40px -5px rgba(0, 0, 0, 0.15)',
    tooltipText: isDark ? '#f5f5f5' : '#102a43',
    balanceLine: isDark ? '#38bdf8' : '#0ea5e9',   // sky-400 / sky-500
    availableLine: isDark ? '#4ade80' : '#22c55e', // green-400 / green-500
  };

  if (loading) {
    return (
      <Card>
        <div className="flex items-center justify-between mb-6">
          <Skeleton className="h-6 w-32" />
          <Skeleton className="h-8 w-8 rounded-lg" />
        </div>
        <Skeleton className="h-[300px] w-full rounded-xl" />
      </Card>
    );
  }

  return (
    <Card className="animate-fade-in" style={{ animationDelay: '0.3s' }}>
      <div className="flex items-center justify-between mb-6">
        <div>
          <h3 className="section-title">Balance Trend</h3>
          <p className="text-sm text-neutral-500 dark:text-neutral-400 mt-0.5">Last 30 days</p>
        </div>
        {onRefresh && (
          <Button
            variant="ghost"
            size="sm"
            onClick={onRefresh}
            className="!p-2"
          >
            <RefreshCw className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
          </Button>
        )}
      </div>
      {safeData.length === 0 ? (
        <div className="h-[300px] flex items-center justify-center bg-neutral-50/60 dark:bg-primary-950/50 rounded-xl border border-neutral-100 dark:border-primary-800/60">
          <div className="text-center">
            <TrendingUp className="w-12 h-12 mx-auto mb-3 text-neutral-300 dark:text-neutral-600" />
            <p className="text-neutral-500 dark:text-neutral-400 font-medium">No balance data available</p>
            <p className="text-sm text-neutral-400 dark:text-neutral-500 mt-1">Data will appear here once transactions occur</p>
          </div>
        </div>
      ) : (
        <div className="h-[300px]">
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart data={safeData} margin={{ top: 10, right: 30, left: 0, bottom: 0 }}>
              <defs>
                <linearGradient id="colorBalance" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor={chartColors.balanceLine} stopOpacity={isDark ? 0.35 : 0.25} />
                  <stop offset="95%" stopColor={chartColors.balanceLine} stopOpacity={0} />
                </linearGradient>
                <linearGradient id="colorAvailable" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor={chartColors.availableLine} stopOpacity={isDark ? 0.35 : 0.25} />
                  <stop offset="95%" stopColor={chartColors.availableLine} stopOpacity={0} />
                </linearGradient>
              </defs>
              <CartesianGrid strokeDasharray="3 3" stroke={chartColors.grid} vertical={false} />
              <XAxis
                dataKey="date"
                tick={{ fontSize: 12, fill: chartColors.tickFill }}
                tickLine={false}
                axisLine={{ stroke: chartColors.axisLine }}
              />
              <YAxis
                tickFormatter={(value) => formatNumber(value)}
                tick={{ fontSize: 12, fill: chartColors.tickFill }}
                tickLine={false}
                axisLine={false}
                width={60}
              />
              <Tooltip
                formatter={(value: number) => formatCurrency(value)}
                labelFormatter={(label) => `Date: ${label}`}
                contentStyle={{
                  backgroundColor: chartColors.tooltipBg,
                  border: 'none',
                  borderRadius: '12px',
                  boxShadow: chartColors.tooltipShadow,
                  padding: '12px 16px',
                  color: chartColors.tooltipText,
                }}
                labelStyle={{ color: chartColors.tooltipText }}
                itemStyle={{ color: chartColors.tooltipText }}
              />
              <Legend
                wrapperStyle={{ paddingTop: '20px', color: chartColors.tickFill }}
                iconType="circle"
              />
              <Area
                type="monotone"
                dataKey="balance"
                stroke={chartColors.balanceLine}
                strokeWidth={2.5}
                fillOpacity={1}
                fill="url(#colorBalance)"
                name="Total Balance"
              />
              <Area
                type="monotone"
                dataKey="available"
                stroke={chartColors.availableLine}
                strokeWidth={2.5}
                fillOpacity={1}
                fill="url(#colorAvailable)"
                name="Available"
              />
            </AreaChart>
          </ResponsiveContainer>
        </div>
      )}
    </Card>
  );
};

// Recent Transactions Component
const RecentTransactions: React.FC<{
  transactions: RecentTransaction[];
  loading: boolean;
  onViewAll?: () => void;
}> = ({ transactions, loading, onViewAll }) => {
  const safeTransactions = ensureArray(transactions);

  if (loading) {
    return (
      <Card>
        <div className="flex items-center justify-between mb-4">
          <Skeleton className="h-6 w-40" />
        </div>
        <div className="space-y-4">
          {[1, 2, 3, 4].map((i) => (
            <div key={i} className="flex items-center space-x-4">
              <Skeleton className="w-10 h-10 rounded-full" />
              <div className="flex-1 space-y-2">
                <Skeleton className="h-4 w-3/4" />
                <Skeleton className="h-3 w-1/2" />
              </div>
              <Skeleton className="h-4 w-20" />
            </div>
          ))}
        </div>
      </Card>
    );
  }

  return (
    <Card className="animate-fade-in" style={{ animationDelay: '0.35s' }}>
      <div className="flex items-center justify-between mb-5">
        <div>
          <h3 className="section-title">Recent Transactions</h3>
          <p className="text-sm text-neutral-500 dark:text-neutral-400 mt-0.5">Latest activity</p>
        </div>
        {onViewAll && (
          <Button
            variant="ghost"
            size="sm"
            onClick={onViewAll}
            rightIcon={<ChevronRight className="w-4 h-4" />}
          >
            View All
          </Button>
        )}
      </div>

      {safeTransactions.length === 0 ? (
        <div className="text-center py-10 bg-neutral-50/60 dark:bg-primary-950/50 rounded-xl border border-neutral-100 dark:border-primary-800/60">
          <ArrowRightLeft className="w-12 h-12 mx-auto mb-3 text-neutral-300 dark:text-neutral-600" />
          <p className="text-neutral-600 dark:text-neutral-300 font-medium">No recent transactions</p>
          <p className="text-sm text-neutral-400 dark:text-neutral-500 mt-1">Transactions will appear here</p>
        </div>
      ) : (
        <div className="space-y-1">
          {safeTransactions.map((tx, index) => (
            <div
              key={tx.id}
              className={cn(
                'flex items-center justify-between py-3.5 px-3 -mx-3 rounded-xl',
                'hover:bg-neutral-50 dark:hover:bg-primary-800/50 transition-colors duration-200 cursor-pointer group'
              )}
              style={{ animationDelay: `${0.35 + index * 0.05}s` }}
            >
              <div className="flex items-center space-x-3.5">
                <div
                  className={cn(
                    'w-10 h-10 rounded-xl flex items-center justify-center transition-transform duration-300 group-hover:scale-110',
                    tx.type === 'CREDIT' ? 'bg-success-50 dark:bg-success-500/10' : 'bg-primary-50 dark:bg-primary-800/40'
                  )}
                >
                  {tx.type === 'CREDIT' ? (
                    <ArrowDownRight className="w-5 h-5 text-success-600 dark:text-success-300" />
                  ) : (
                    <ArrowUpRight className="w-5 h-5 text-primary-600 dark:text-primary-200" />
                  )}
                </div>
                <div>
                  <p className="font-medium text-neutral-900 dark:text-neutral-50">{tx.beneficiary || 'N/A'}</p>
                  <p className="text-sm text-neutral-500 dark:text-neutral-400">{tx.reference || 'No reference'}</p>
                </div>
              </div>
              <div className="text-right">
                <p
                  className={cn(
                    'font-semibold',
                    tx.type === 'CREDIT' ? 'text-success-600 dark:text-success-300' : 'text-neutral-900 dark:text-neutral-50'
                  )}
                >
                  {tx.type === 'CREDIT' ? '+' : '-'}
                  {formatCurrency(tx.amount, tx.currency)}
                </p>
                <p className="text-xs text-neutral-500 dark:text-neutral-400">{formatRelativeTime(tx.date)}</p>
              </div>
            </div>
          ))}
        </div>
      )}
    </Card>
  );
};

// Top Accounts Component
const TopAccounts: React.FC<{
  accounts: TopAccount[];
  loading: boolean;
  onViewAll?: () => void;
  onAccountClick?: (id: string) => void;
}> = ({ accounts, loading, onViewAll, onAccountClick }) => {
  const safeAccounts = ensureArray(accounts);

  if (loading) {
    return (
      <Card>
        <div className="flex items-center justify-between mb-4">
          <Skeleton className="h-6 w-32" />
        </div>
        <div className="space-y-3">
          {[1, 2, 3, 4, 5].map((i) => (
            <div key={i} className="flex items-center space-x-3">
              <Skeleton className="w-8 h-8 rounded-full" />
              <div className="flex-1 space-y-2">
                <Skeleton className="h-4 w-3/4" />
                <Skeleton className="h-3 w-1/2" />
              </div>
            </div>
          ))}
        </div>
      </Card>
    );
  }

  return (
    <Card className="animate-fade-in" style={{ animationDelay: '0.4s' }}>
      <div className="flex items-center justify-between mb-5">
        <div>
          <h3 className="section-title">Top Accounts</h3>
          <p className="text-sm text-neutral-500 dark:text-neutral-400 mt-0.5">By balance</p>
        </div>
        {onViewAll && (
          <Button
            variant="ghost"
            size="sm"
            onClick={onViewAll}
            rightIcon={<ChevronRight className="w-4 h-4" />}
          >
            View All
          </Button>
        )}
      </div>
      {safeAccounts.length === 0 ? (
        <div className="text-center py-10 bg-neutral-50/60 dark:bg-primary-950/50 rounded-xl border border-neutral-100 dark:border-primary-800/60">
          <Building2 className="w-12 h-12 mx-auto mb-3 text-neutral-300 dark:text-neutral-600" />
          <p className="text-neutral-600 dark:text-neutral-300 font-medium">No accounts available</p>
        </div>
      ) : (
        <div className="space-y-1">
          {safeAccounts.map((account, index) => (
            <div
              key={account.id || index}
              className={cn(
                'flex items-center space-x-3.5 p-3 -mx-3 rounded-xl transition-all duration-200',
                onAccountClick && 'cursor-pointer hover:bg-neutral-50 dark:hover:bg-primary-800/50 group'
              )}
              onClick={() => onAccountClick?.(account.id)}
            >
              {/* Tier 7: rank chips flattened from `bg-gradient-to-br
                  from-X-100 to-X-50` to solid `bg-X-100`. The chromatic
                  ranking (1=amber/gold, 2=neutral, 3=warning, 4+=grey) is
                  still readable as priority shorthand — the gradient added
                  no value beyond that. */}
              <div
                className={cn(
                  'w-9 h-9 rounded-xl flex items-center justify-center text-sm font-bold transition-transform duration-300',
                  index === 0
                    ? 'bg-warning-100 dark:bg-warning-500/20 text-warning-700 dark:text-warning-300'
                    : index === 1
                    ? 'bg-neutral-200 dark:bg-primary-800 text-neutral-600 dark:text-neutral-300'
                    : index === 2
                    ? 'bg-warning-100 dark:bg-warning-500/20 text-warning-700 dark:text-warning-300'
                    : 'bg-neutral-100 dark:bg-primary-800 text-neutral-500 dark:text-neutral-400',
                  onAccountClick && 'group-hover:scale-110'
                )}
              >
                {index + 1}
              </div>
              <div className="flex-1 min-w-0">
                <p className="font-medium text-neutral-900 dark:text-neutral-50 truncate">{account.name || 'Unnamed Account'}</p>
                <p className="text-sm text-neutral-500 dark:text-neutral-400 truncate font-mono">
                  {account.viban ? `${account.viban.slice(0, 8)}...${account.viban.slice(-4)}` : account.vaNumber || 'N/A'}
                </p>
              </div>
              <div className="text-right shrink-0">
                <p className="font-semibold text-neutral-900 dark:text-neutral-50">
                  {formatCurrency(account.balance, account.currency)}
                </p>
              </div>
            </div>
          ))}
        </div>
      )}
    </Card>
  );
};

// Treasury Summary Card
const TreasurySummaryCard: React.FC<{
  treasury: TreasurySummary | null;
  loading: boolean;
  onNavigate?: (page: string) => void;
}> = ({ treasury, loading, onNavigate }) => {
  if (loading) {
    return (
      <Card>
        <Skeleton className="h-6 w-40 mb-4" />
        <div className="grid grid-cols-2 gap-4">
          {[1, 2, 3, 4].map((i) => (
            <Skeleton key={i} className="h-24 rounded-xl" />
          ))}
        </div>
      </Card>
    );
  }

  const safeTreasury = treasury || defaultTreasury;

  // Tier 7: feature tiles flattened from `bg-gradient-to-br from-X-50
  // to-white` to solid `bg-X-50` (matching the row backgrounds elsewhere
  // on the page). The categorical tone (info/success/purple/amber) is
  // still readable as the tile's identity.
  const items = [
    {
      icon: <Layers className="w-5 h-5 text-info-600 dark:text-info-300" />,
      label: 'Notional Pools',
      value: safeTreasury.pooling?.activePools ?? 0,
      subValue: formatCurrency(safeTreasury.pooling?.totalPooledBalance ?? 0),
      page: 'pooling',
      bgColor: 'bg-info-50 dark:bg-info-500/10',
      borderColor: 'border-info-100 dark:border-info-500/20',
    },
    {
      icon: <ArrowRightLeft className="w-5 h-5 text-success-600 dark:text-success-300" />,
      label: 'Sweep Rules',
      value: safeTreasury.sweeping?.activeRules ?? 0,
      subValue: `${safeTreasury.sweeping?.executionsToday ?? 0} today`,
      page: 'sweeping',
      bgColor: 'bg-success-50 dark:bg-success-500/10',
      borderColor: 'border-success-100 dark:border-success-500/20',
    },
    {
      icon: <PiggyBank className="w-5 h-5 text-cat-2" />,
      label: 'Netting Savings',
      value: formatCurrency(safeTreasury.netting?.totalSavingsYtd ?? 0),
      subValue: `${(safeTreasury.netting?.averageSavingsPercent ?? 0).toFixed(1)}% avg`,
      page: 'netting',
      bgColor: 'bg-cat-2-soft dark:bg-cat-2/15',
      borderColor: 'border-cat-2/10 dark:border-cat-2/20',
    },
    {
      icon: <Landmark className="w-5 h-5 text-warning-600 dark:text-warning-300" />,
      label: 'IHB Loans',
      value: formatCurrency(safeTreasury.inHouseBank?.totalLoansOutstanding ?? 0),
      subValue: `${safeTreasury.inHouseBank?.activeEntities ?? 0} entities`,
      page: 'ihb',
      bgColor: 'bg-warning-50 dark:bg-warning-500/10',
      borderColor: 'border-warning-100 dark:border-warning-500/20',
    },
  ];

  return (
    <Card className="animate-fade-in" style={{ animationDelay: '0.45s' }}>
      <div className="mb-5">
        <h3 className="section-title">Treasury Summary</h3>
        <p className="text-sm text-neutral-500 dark:text-neutral-400 mt-0.5">Key treasury metrics</p>
      </div>
      <div className="grid grid-cols-2 gap-4">
        {items.map((item, index) => (
          <div
            key={item.label}
            className={cn(
              'p-4 rounded-xl border cursor-pointer',
              'transition-all duration-300 hover:shadow-md hover:-translate-y-0.5 group',
              item.bgColor,
              item.borderColor
            )}
            onClick={() => onNavigate?.(item.page)}
            style={{ animationDelay: `${0.45 + index * 0.05}s` }}
          >
            <div className="flex items-center space-x-2.5 mb-3">
              <div className="transition-transform duration-300 group-hover:scale-110">
                {item.icon}
              </div>
              <span className="body-sm">{item.label}</span>
            </div>
            {/* Phase 12 Task E: .stat-value-xs replaces the raw `text-xl
                font-bold` display-tier hand-roll (same 20px scale). */}
            <p className="stat-value-xs">{item.value}</p>
            <p className="text-sm text-neutral-500 dark:text-neutral-400 mt-1">{item.subValue}</p>
          </div>
        ))}
      </div>
    </Card>
  );
};

// Pending Approvals Card
const PendingApprovalsCard: React.FC<{
  approvals: PendingApprovals | null;
  loading: boolean;
  onNavigate?: (page: string) => void;
}> = ({ approvals, loading, onNavigate }) => {
  if (loading) {
    return (
      <Card>
        <Skeleton className="h-6 w-40 mb-4" />
        <div className="space-y-3">
          {[1, 2, 3, 4].map((i) => (
            <Skeleton key={i} className="h-14 rounded-xl" />
          ))}
        </div>
      </Card>
    );
  }

  const safeApprovals = approvals || defaultApprovals;

  const items = [
    {
      icon: <Layers className="w-4 h-4" />,
      label: 'Netting Cycles',
      count: safeApprovals.nettingCount ?? 0,
      page: 'netting',
      color: 'text-cat-2',
      bg: 'bg-cat-2-soft dark:bg-cat-2/15',
    },
    {
      icon: <Users className="w-4 h-4" />,
      label: 'KYC Applications',
      count: safeApprovals.kycCount ?? 0,
      page: 'kycc',
      color: 'text-info-600 dark:text-info-300',
      bg: 'bg-info-50 dark:bg-info-500/10',
    },
    {
      icon: <FileText className="w-4 h-4" />,
      label: 'Payables',
      count: safeApprovals.payablesCount ?? 0,
      page: 'payables',
      color: 'text-warning-600 dark:text-warning-300',
      bg: 'bg-warning-50 dark:bg-warning-500/10',
    },
    {
      icon: <ArrowRightLeft className="w-4 h-4" />,
      label: 'Transactions',
      count: safeApprovals.transactionsCount ?? 0,
      page: 'transactions',
      color: 'text-success-600 dark:text-success-300',
      bg: 'bg-success-50 dark:bg-success-500/10',
    },
  ];

  return (
    <Card className="animate-fade-in" style={{ animationDelay: '0.5s' }}>
      <div className="flex items-center justify-between mb-5">
        <div>
          <h3 className="section-title">Pending Approvals</h3>
          <p className="text-sm text-neutral-500 dark:text-neutral-400 mt-0.5">Items requiring action</p>
        </div>
        {(safeApprovals.totalPending ?? 0) > 0 && (
          <Badge variant="warning" size="md" className="font-bold">
            {safeApprovals.totalPending ?? 0}
          </Badge>
        )}
      </div>
      <div className="space-y-1">
        {items.map((item, index) => (
          <div
            key={item.label}
            className={cn(
              'flex items-center justify-between p-3.5 -mx-3 rounded-xl',
              'hover:bg-neutral-50 dark:hover:bg-primary-800/50 cursor-pointer transition-all duration-200 group'
            )}
            onClick={() => onNavigate?.(item.page)}
            style={{ animationDelay: `${0.5 + index * 0.05}s` }}
          >
            <div className="flex items-center space-x-3.5">
              <div className={cn(
                'w-9 h-9 rounded-xl flex items-center justify-center transition-transform duration-300 group-hover:scale-110',
                item.bg
              )}>
                <span className={item.color}>{item.icon}</span>
              </div>
              <span className="font-medium text-neutral-700 dark:text-neutral-200">{item.label}</span>
            </div>
            <div className="flex items-center space-x-3">
              {item.count > 0 && (
                <Badge variant="neutral" size="sm" className="font-bold">
                  {item.count}
                </Badge>
              )}
              <ChevronRight className="w-4 h-4 text-neutral-400 dark:text-neutral-500 transition-transform duration-300 group-hover:translate-x-1" />
            </div>
          </div>
        ))}
      </div>
    </Card>
  );
};

// Alerts Card
const AlertsCard: React.FC<{
  alerts: DashboardAlert[];
  loading: boolean;
  onNavigate?: (page: string) => void;
}> = ({ alerts, loading, onNavigate }) => {
  const safeAlerts = ensureArray(alerts);

  if (loading) {
    return (
      <Card>
        <Skeleton className="h-6 w-24 mb-4" />
        <div className="space-y-3">
          {[1, 2, 3].map((i) => (
            <Skeleton key={i} className="h-20 rounded-xl" />
          ))}
        </div>
      </Card>
    );
  }

  const getAlertIcon = (type: string) => {
    switch (type) {
      case 'WARNING':
        return <AlertTriangle className="w-4 h-4 text-warning-600 dark:text-warning-300" />;
      case 'ERROR':
        return <XCircle className="w-4 h-4 text-error-600 dark:text-error-300" />;
      case 'SUCCESS':
        return <CheckCircle2 className="w-4 h-4 text-success-600 dark:text-success-300" />;
      default:
        return <Info className="w-4 h-4 text-info-600 dark:text-info-300" />;
    }
  };

  const getAlertStyles = (type: string) => {
    switch (type) {
      case 'WARNING':
        return 'bg-warning-50 border-warning-200 dark:bg-warning-500/10 dark:border-warning-500/20';
      case 'ERROR':
        return 'bg-error-50 border-error-200 dark:bg-error-500/10 dark:border-error-500/20';
      case 'SUCCESS':
        return 'bg-success-50 border-success-200 dark:bg-success-500/10 dark:border-success-500/20';
      default:
        return 'bg-info-50 border-info-200 dark:bg-info-500/10 dark:border-info-500/20';
    }
  };

  return (
    <Card className="animate-fade-in" style={{ animationDelay: '0.55s' }}>
      <div className="flex items-center justify-between mb-5">
        <div>
          <h3 className="section-title">Alerts</h3>
          <p className="text-sm text-neutral-500 dark:text-neutral-400 mt-0.5">Important notifications</p>
        </div>
        {safeAlerts.length > 0 && (
          <div className="w-7 h-7 rounded-full bg-error-500 flex items-center justify-center">
            <span className="text-xs font-bold text-white">{safeAlerts.length}</span>
          </div>
        )}
      </div>

      {safeAlerts.length === 0 ? (
        <div className="text-center py-8 bg-neutral-50/60 dark:bg-primary-950/50 rounded-xl border border-neutral-100 dark:border-primary-800/60">
          <Bell className="w-10 h-10 mx-auto mb-2 text-neutral-300 dark:text-neutral-600" />
          <p className="text-neutral-600 dark:text-neutral-300 font-medium">No alerts</p>
          <p className="text-sm text-neutral-400 dark:text-neutral-500 mt-1">You're all caught up!</p>
        </div>
      ) : (
        <div className="space-y-3">
          {safeAlerts.slice(0, 5).map((alert, index) => (
            <div
              key={alert.id || index}
              className={cn(
                'p-3.5 rounded-xl border transition-all duration-200',
                getAlertStyles(alert.type),
                alert.action && 'cursor-pointer hover:shadow-md'
              )}
              onClick={() => {
                if (alert.action && onNavigate) {
                  const page = alert.action.replace(/^\//, '').replace(/\//g, '-');
                  onNavigate(page || 'dashboard');
                }
              }}
            >
              <div className="flex items-start space-x-3">
                <div className="mt-0.5">{getAlertIcon(alert.type)}</div>
                <div className="flex-1 min-w-0">
                  <p className="font-medium text-neutral-900 dark:text-neutral-50 text-sm">{alert.title || 'Alert'}</p>
                  <p className="text-sm text-neutral-600 dark:text-neutral-300 mt-1">{alert.message || ''}</p>
                </div>
                {alert.action && <ExternalLink className="w-4 h-4 text-neutral-400 dark:text-neutral-500 shrink-0" />}
              </div>
            </div>
          ))}
        </div>
      )}
    </Card>
  );
};

// Quick Actions
const QuickActions: React.FC<{
  onNavigate?: (page: string) => void;
}> = ({ onNavigate }) => {
  const actions = [
    { icon: <Send className="w-5 h-5" />, label: 'New Transfer', page: 'transfers', color: 'text-primary-600 dark:text-primary-200', bg: 'bg-primary-50 hover:bg-primary-100 dark:bg-primary-800/40 dark:hover:bg-primary-700' },
    { icon: <Building2 className="w-5 h-5" />, label: 'Virtual Accounts', page: 'accounts', color: 'text-info-600 dark:text-info-300', bg: 'bg-info-50 hover:bg-info-100 dark:bg-info-500/10 dark:hover:bg-info-500/20' },
    { icon: <CreditCard className="w-5 h-5" />, label: 'Payables (AP)', page: 'payables', color: 'text-cat-2', bg: 'bg-cat-2-soft hover:bg-cat-2/10 dark:bg-cat-2/15 dark:hover:bg-cat-2/25' },
    { icon: <FileText className="w-5 h-5" />, label: 'Statements', page: 'statements', color: 'text-success-600 dark:text-success-300', bg: 'bg-success-50 hover:bg-success-100 dark:bg-success-500/10 dark:hover:bg-success-500/20' },
    { icon: <Landmark className="w-5 h-5" />, label: 'In-House Bank', page: 'ihb', color: 'text-warning-600 dark:text-warning-300', bg: 'bg-warning-50 hover:bg-warning-100 dark:bg-warning-500/10 dark:hover:bg-warning-500/20' },
    { icon: <ArrowRightLeft className="w-5 h-5" />, label: 'Transactions', page: 'transactions', color: 'text-info-600 dark:text-info-300', bg: 'bg-info-50 hover:bg-info-100 dark:bg-info-500/10 dark:hover:bg-info-500/20' },
    { icon: <Users className="w-5 h-5" />, label: 'Legal Entities', page: 'legal-entities', color: 'text-cat-3', bg: 'bg-cat-3-soft hover:bg-cat-3/10 dark:bg-cat-3/15 dark:hover:bg-cat-3/25' },
    { icon: <Briefcase className="w-5 h-5" />, label: 'Programs', page: 'programs', color: 'text-cat-1', bg: 'bg-cat-1-soft hover:bg-cat-1/10 dark:bg-cat-1/15 dark:hover:bg-cat-1/25' },
  ];

  return (
    <Card className="animate-fade-in" style={{ animationDelay: '0.6s' }}>
      <div className="mb-5">
        <h3 className="section-title">Quick Actions</h3>
        <p className="text-sm text-neutral-500 dark:text-neutral-400 mt-0.5">Common operations</p>
      </div>
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        {actions.map((action, index) => (
          <button
            key={action.label}
            className={cn(
              'flex items-center space-x-3 p-3 rounded-xl transition-all duration-300',
              'hover:shadow-md hover:-translate-y-0.5 group',
              action.bg
            )}
            onClick={() => onNavigate?.(action.page)}
            style={{ animationDelay: `${0.6 + index * 0.05}s` }}
          >
            <span className={cn('transition-transform duration-300 group-hover:scale-110', action.color)}>
              {action.icon}
            </span>
            <span className="body-sm">{action.label}</span>
          </button>
        ))}
      </div>
    </Card>
  );
};

// ============================================================================
// MAIN DASHBOARD PAGE COMPONENT
// ============================================================================

const DashboardClassicPage: React.FC<DashboardClassicPageProps> = ({ onNavigate }) => {
  // State
  const [stats, setStats] = useState<DashboardStats>(defaultStats);
  const [balanceTrend, setBalanceTrend] = useState<BalanceTrendPoint[]>([]);
  const [topAccounts, setTopAccounts] = useState<TopAccount[]>([]);
  const [recentTransactions, setRecentTransactions] = useState<RecentTransaction[]>([]);
  const [treasury, setTreasury] = useState<TreasurySummary>(defaultTreasury);
  const [alerts, setAlerts] = useState<DashboardAlert[]>([]);
  const [pendingApprovals, setPendingApprovals] = useState<PendingApprovals>(defaultApprovals);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Corporate & Program State
  const [corporates, setCorporates] = useState<Corporate[]>([]);
  const [programs, setPrograms] = useState<Program[]>([]);
  const [selectedCorporateId, setSelectedCorporateId] = useState<string>('');
  const [selectedProgramId, setSelectedProgramId] = useState<string>('');
  const [selectorLoading, setSelectorLoading] = useState(false);

  // Navigation handler
  const handleNavigate = useCallback(
    (page: string) => {
      if (onNavigate) {
        onNavigate(page);
      } else {
        console.log('Navigate to:', page);
      }
    },
    [onNavigate]
  );

  // Fetch corporates and programs
  const fetchSelectors = useCallback(async () => {
    try {
      setSelectorLoading(true);
      const [corporatesRes, programsRes] = await Promise.allSettled([
        fetch('/api/v1/corporates').then(r => r.json()),
        fetch('/api/v1/programs').then(r => r.json()),
      ]);

      if (corporatesRes.status === 'fulfilled' && corporatesRes.value?.data) {
        const data = corporatesRes.value.data;
        const corporateList = Array.isArray(data) ? data : [];
        setCorporates(corporateList.map((c: any) => ({
          id: c.id,
          name: c.tradeName || c.legalName || c.corporateId || 'Unnamed',
          legalName: c.legalName,
        })));
      }

      if (programsRes.status === 'fulfilled' && programsRes.value?.data) {
        const data = programsRes.value.data;
        const programList = data.programs || (Array.isArray(data) ? data : []);
        setPrograms(programList.map((p: any) => ({
          id: p.id,
          programCode: p.programCode,
          programName: p.programName,
          programType: p.programType,
          currencyCode: p.currencyCode,
        })));
      }
    } catch (err) {
      console.error('Error fetching selectors:', err);
    } finally {
      setSelectorLoading(false);
    }
  }, []);

  // Fetch dashboard data
  const fetchDashboardData = useCallback(async (isRefresh = false) => {
    try {
      if (isRefresh) {
        setRefreshing(true);
      } else {
        setLoading(true);
      }
      setError(null);

      const results = await Promise.allSettled([
        dashboardApi.getStats(),
        dashboardApi.getBalanceTrend(30),
        dashboardApi.getTopAccounts(5),
        dashboardApi.getRecentTransactions(5),
        dashboardApi.getTreasurySummary(),
        dashboardApi.getAlerts(),
        dashboardApi.getPendingApprovals(),
      ]);

      if (results[0].status === 'fulfilled' && results[0].value) {
        setStats(results[0].value);
      }
      if (results[1].status === 'fulfilled') {
        setBalanceTrend(ensureArray(results[1].value));
      }
      if (results[2].status === 'fulfilled') {
        setTopAccounts(ensureArray(results[2].value));
      }
      if (results[3].status === 'fulfilled') {
        setRecentTransactions(ensureArray(results[3].value));
      }
      if (results[4].status === 'fulfilled' && results[4].value) {
        setTreasury(results[4].value);
      }
      if (results[5].status === 'fulfilled') {
        setAlerts(ensureArray(results[5].value));
      }
      if (results[6].status === 'fulfilled' && results[6].value) {
        setPendingApprovals(results[6].value);
      }

      const allFailed = results.every((r) => r.status === 'rejected');
      if (allFailed) {
        setError('Failed to load dashboard data. Please try again.');
      }
    } catch (err) {
      console.error('Error fetching dashboard data:', err);
      setError('Failed to load dashboard data. Please try again.');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  // Initial load
  useEffect(() => {
    fetchDashboardData();
    fetchSelectors();
  }, [fetchDashboardData, fetchSelectors]);

  // Re-fetch dashboard data when filters change
  useEffect(() => {
    if (selectedCorporateId || selectedProgramId) {
      fetchDashboardData(true);
    }
  }, [selectedCorporateId, selectedProgramId, fetchDashboardData]);

  // Refresh handler
  const handleRefresh = () => {
    fetchDashboardData(true);
  };

  return (
    <Page>
      {/* Corporate & Program Selector with integrated Refresh */}
      <SelectorBar
        corporates={corporates}
        programs={programs}
        selectedCorporateId={selectedCorporateId}
        selectedProgramId={selectedProgramId}
        onCorporateChange={setSelectedCorporateId}
        onProgramChange={setSelectedProgramId}
        loading={selectorLoading}
        onRefresh={handleRefresh}
        refreshing={refreshing}
      />

      {/* Error Banner */}
      {error && (
        <div className="bg-error-50 border border-error-200 dark:bg-error-500/10 dark:border-error-500/20 rounded-2xl p-4 flex items-center justify-between animate-fade-in">
          <div className="flex items-center space-x-3">
            <XCircle className="w-5 h-5 text-error-600 dark:text-error-300" />
            <span className="text-error-800 dark:text-error-300 font-medium">{error}</span>
          </div>
          <button
            onClick={() => setError(null)}
            className="p-1.5 hover:bg-error-100 dark:hover:bg-error-500/20 rounded-lg transition-colors"
          >
            <XCircle className="w-5 h-5 text-error-600 dark:text-error-300" />
          </button>
        </div>
      )}

      {/* Headline financial figures — Total Balance + Available, same hero
          pattern as Accounts. Tier 6 Design System Unification (2026-05-13):
          replaces a 4-up equal-weight stat strip that gave no visual
          hierarchy. Today's Transactions + Pending demoted to a 2-up
          operational strip below the hero. The hero card's gold lens
          glow + section-aware ambience are the right treatment for the
          dashboard's headline figure (treasurer's first read every morning). */}
      <HeroMetricCard
        primary={{
          label: 'Total Balance',
          value: formatCurrency(stats.totalBalance),
          trend: stats.balanceChange ? `${stats.balanceChange > 0 ? '+' : ''}${stats.balanceChange.toFixed(1)}%` : undefined,
          trendTone: stats.balanceChange && stats.balanceChange < 0 ? 'error' : 'success',
          sub: 'Sum of current balances vs last month',
        }}
        secondary={{
          label: 'Available',
          value: formatCurrency(stats.availableBalance),
          sub: 'Spendable today (includes credit headroom)',
        }}
        icon={<Banknote className="w-7 h-7 text-accent-600 dark:text-accent-300" />}
      />

      {/* Operational metrics — secondary strip below the hero. */}
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 lg:gap-6">
        <StatCard
          title="Today's Transactions"
          value={stats.todayTransactions}
          subtitle={formatCurrency(stats.todayVolume) + ' volume'}
          icon={<ArrowRightLeft className="w-6 h-6 text-info-600 dark:text-info-300" />}
          iconBg="bg-info-100 dark:bg-info-500/20"
          loading={loading}
          onClick={() => handleNavigate('transactions')}
          delay={0.15}
        />
        <StatCard
          title="Pending"
          value={stats.pendingTransactions}
          subtitle="transactions"
          icon={<Clock className="w-6 h-6 text-warning-600 dark:text-warning-300" />}
          iconBg="bg-warning-100 dark:bg-warning-500/20"
          loading={loading}
          onClick={() => handleNavigate('transactions')}
          delay={0.2}
        />
      </div>

      {/* Hierarchy Widgets Row */}
      <StatStrip>
        <BalanceByLevelWidget
          stats={stats}
          loading={loading}
          onClick={() => handleNavigate('accounts')}
        />
        <TopEntitiesWidget
          stats={stats}
          loading={loading}
          onClick={() => handleNavigate('corporates')}
        />
        <VibanCollectionsWidget
          transactions={recentTransactions}
          loading={loading}
          onClick={() => handleNavigate('transactions')}
        />
        <PoboActivityWidget
          transactions={recentTransactions}
          loading={loading}
          onClick={() => handleNavigate('transactions')}
        />
      </StatStrip>

      {/* Charts Row */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4 lg:gap-6">
        <div className="lg:col-span-2">
          <BalanceChart
            data={balanceTrend}
            loading={loading}
            onRefresh={handleRefresh}
          />
        </div>
        <div>
          <TopAccounts
            accounts={topAccounts}
            loading={loading}
            onViewAll={() => handleNavigate('accounts')}
            onAccountClick={() => handleNavigate('accounts')}
          />
        </div>
      </div>

      {/* Treasury & Approvals Row */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 lg:gap-6">
        <TreasurySummaryCard
          treasury={treasury}
          loading={loading}
          onNavigate={handleNavigate}
        />
        <PendingApprovalsCard
          approvals={pendingApprovals}
          loading={loading}
          onNavigate={handleNavigate}
        />
      </div>

      {/* Activity Row */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 lg:gap-6">
        <RecentTransactions
          transactions={recentTransactions}
          loading={loading}
          onViewAll={() => handleNavigate('transactions')}
        />
        <div className="space-y-4 lg:space-y-6">
          <AlertsCard
            alerts={alerts}
            loading={loading}
            onNavigate={handleNavigate}
          />
          <QuickActions onNavigate={handleNavigate} />
        </div>
      </div>
    </Page>
  );
};

export default DashboardClassicPage;
