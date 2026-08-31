import React, { useState, useEffect, useCallback } from 'react';
import {
  Wallet, Plus, Search, Filter, CreditCard, ArrowUpRight, ArrowDownRight,
  Users, TrendingUp, MoreHorizontal, Eye, Lock, Unlock, Ban, RefreshCw,
  Send, Download, Settings, Loader2, AlertCircle, CheckCircle, Building2,
  Shield, Edit, Upload, FileText, UserCheck, XCircle, LayoutDashboard,
  Banknote, PieChart, Activity, Clock, ChevronDown, ChevronUp, Copy,
} from 'lucide-react';
import { Card, CardHeader, Button, Badge, Input, EmptyState } from '../components/ui';
import { Modal, Tabs, ProgressBar, Avatar, Alert } from '../components/ui/enhanced';
import { formatCurrency, formatDate, cn } from '../utils';

// ============================================================================
// API Service - Enhanced with ALL backend endpoints
// ============================================================================

const API_BASE = '/api/v1/wallets';

interface ApiResponse<T> {
  success: boolean;
  data: T;
  message?: string;
}

const fetchApi = async <T,>(endpoint: string, options?: RequestInit): Promise<T> => {
  const { headers: optHeaders, ...restOptions } = options || {};
  const response = await fetch(`${API_BASE}${endpoint}`, {
    ...restOptions,
    headers: { 'Content-Type': 'application/json', ...optHeaders },
  });
  if (!response.ok) {
    const errorData = await response.json().catch(() => ({}));
    throw new Error(errorData.message || `API error: ${response.status}`);
  }
  const result: ApiResponse<T> = await response.json();
  return result.data;
};

const walletsApi = {
  // STATS
  getStats: (corporateId?: string) => {
    const headers: Record<string, string> = {};
    if (corporateId) headers['X-Corporate-Id'] = corporateId;
    return fetchApi<WalletStats>('/stats', { headers });
  },
  
  // PROGRAMS - Full CRUD
  getPrograms: (corporateId?: string, status?: string) => {
    const params = new URLSearchParams();
    if (status) params.set('status', status);
    const headers: Record<string, string> = {};
    if (corporateId) headers['X-Corporate-Id'] = corporateId;
    return fetchApi<WalletProgram[]>(`/programs?${params}`, { headers });
  },
  getProgramDetails: (id: string) => fetchApi<WalletProgramDetail>(`/programs/${id}`),
  getProgramStats: (id: string) => fetchApi<WalletProgramStats>(`/programs/${id}/stats`),
  getProgramWallets: (id: string, page = 0, size = 20, status?: string, query?: string) => {
    const params = new URLSearchParams({ page: page.toString(), size: size.toString() });
    if (status) params.set('status', status);
    if (query) params.set('query', query);
    return fetchApi<WalletListResponse>(`/programs/${id}/wallets?${params}`);
  },
  createProgram: (data: CreateProgramRequest) =>
    fetchApi<WalletProgram>('/programs', { method: 'POST', body: JSON.stringify(data) }),
  updateProgram: (id: string, data: UpdateProgramRequest) =>
    fetchApi<WalletProgram>(`/programs/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
  
  // WALLETS - Full CRUD
  getWallets: (params: WalletSearchParams = {}) => {
    const query = new URLSearchParams();
    if (params.page !== undefined) query.set('page', params.page.toString());
    if (params.size !== undefined) query.set('size', params.size.toString());
    if (params.status) query.set('status', params.status);
    if (params.query) query.set('query', params.query);
    if (params.programId) query.set('programId', params.programId);
    if (params.corporateId) query.set('corporateId', params.corporateId);
    if (params.kycVerified !== undefined) query.set('kycVerified', params.kycVerified.toString());
    return fetchApi<WalletListResponse>(`?${query}`);
  },
  getWalletDetails: (id: string) => fetchApi<WalletAccountDetail>(`/${id}`),
  getWalletByReference: (reference: string) => fetchApi<WalletAccountDetail>(`/reference/${reference}`),
  issueWallet: (data: IssueWalletRequest) => 
    fetchApi<WalletAccount>('', { method: 'POST', body: JSON.stringify(data) }),
  updateWallet: (id: string, data: UpdateWalletRequest) =>
    fetchApi<WalletAccount>(`/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
  
  // WALLET OPERATIONS
  loadFunds: (walletId: string, data: LoadFundsRequest) =>
    fetchApi<LoadFundsResponse>(`/${walletId}/load`, { method: 'POST', body: JSON.stringify(data) }),
  withdrawFunds: (walletId: string, data: WithdrawRequest) =>
    fetchApi<WithdrawResponse>(`/${walletId}/withdraw`, { method: 'POST', body: JSON.stringify(data) }),
  transferFunds: (walletId: string, data: TransferRequest) =>
    fetchApi<TransferResponse>(`/${walletId}/transfer`, { method: 'POST', body: JSON.stringify(data) }),
  bulkLoadFunds: (data: BulkLoadRequest) =>
    fetchApi<BulkLoadResponse>('/bulk-load', { method: 'POST', body: JSON.stringify(data) }),
  
  // STATUS MANAGEMENT
  suspendWallet: (walletId: string, reason?: string) =>
    fetchApi<WalletAccount>(`/${walletId}/suspend`, { method: 'POST', body: JSON.stringify({ reason }) }),
  reactivateWallet: (walletId: string, reason?: string) =>
    fetchApi<WalletAccount>(`/${walletId}/reactivate`, { method: 'POST', body: JSON.stringify({ reason }) }),
  blockWallet: (walletId: string, reason: string, permanent?: boolean) =>
    fetchApi<WalletAccount>(`/${walletId}/block`, { method: 'POST', body: JSON.stringify({ reason, permanent }) }),
  
  // KYC
  verifyKyc: (walletId: string, data: VerifyKycRequest) =>
    fetchApi<KycVerificationResponse>(`/${walletId}/verify-kyc`, { method: 'POST', body: JSON.stringify(data) }),
  
  // TRANSACTIONS
  getWalletTransactions: (walletId: string, page = 0, size = 20) =>
    fetchApi<WalletTransaction[]>(`/${walletId}/transactions?page=${page}&size=${size}`),
  
  // WALLET TYPES
  getWalletTypes: () => fetchApi<WalletType[]>('/types'),
};

// ============================================================================
// Types - Enhanced with all backend DTOs
// ============================================================================

interface WalletStats {
  totalPrograms: number;
  activePrograms: number;
  totalWallets: number;
  activeWallets: number;
  suspendedWallets: number;
  blockedWallets?: number;
  totalBalance: number;
  totalAvailableBalance?: number;
  monthlyVolume: number;
  dailyVolume?: number;
  todayTransactions?: number;
  monthlyTransactions?: number;
  kycVerifiedCount: number;
  kycPendingCount: number;
}

interface WalletProgram {
  id: string;
  programCode: string;
  programName: string;
  operatorName: string;
  corporateId?: string;
  currency: string;
  activeWallets: number;
  totalWallets: number;
  totalBalance: number;
  totalAvailableBalance?: number;
  dailySpendLimit: number;
  monthlySpendLimit: number;
  maxBalance?: number;
  minTopup?: number;
  maxTopup?: number;
  kycRequired?: boolean;
  expiryDays?: number;
  status: 'ACTIVE' | 'SUSPENDED' | 'CLOSED' | 'PENDING_APPROVAL';
  launchDate: string;
}

interface WalletProgramDetail extends WalletProgram {
  description: string;
  suspendedWallets: number;
  blockedWallets?: number;
  physicalAccountId?: string;
  physicalAccountNumber?: string;
  recentWallets: WalletAccount[];
}

interface WalletProgramStats {
  programId: string;
  programCode: string;
  totalBalance: number;
  averageBalance: number;
  dailyVolume: number;
  monthlyVolume: number;
  kycVerifiedCount: number;
  kycPendingCount: number;
}

interface WalletAccount {
  id: string;
  walletReference: string;
  walletName: string;
  holderName: string;
  holderMobile: string;
  holderEmail?: string;
  partyId?: string;
  programId?: string;
  programName: string;
  programCode?: string;
  currentBalance: number;
  availableBalance: number;
  currency?: string;
  dailySpent: number;
  monthlySpent: number;
  dailyLimit: number;
  monthlyLimit: number;
  status: 'ACTIVE' | 'SUSPENDED' | 'BLOCKED';
  kycVerified: boolean;
  kycStatus: string;
  lastTransaction?: string;
  transactionCount: number;
  createdAt: string;
}

interface WalletAccountDetail extends WalletAccount {
  viban?: string;
  operatorName?: string;
  corporateId?: string;
  weeklySpent?: number;
  yearlySpent?: number;
  perTransactionLimit?: number;
  maxBalance?: number;
  hierarchyPath?: string;
  expiresAt?: string;
  recentTransactions: WalletTransaction[];
}

interface WalletListResponse {
  content: WalletAccount[];
  page: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
  summary: {
    activeCount: number;
    suspendedCount: number;
    blockedCount?: number;
    totalBalance: number;
    kycVerifiedCount: number;
    kycPendingCount: number;
  };
}

interface WalletSearchParams {
  page?: number;
  size?: number;
  status?: string;
  query?: string;
  programId?: string;
  corporateId?: string;
  kycVerified?: boolean;
}

interface WalletTransaction {
  id: string;
  referenceNumber: string;
  type: string;
  amount: number;
  currency: string;
  balanceBefore: number;
  balanceAfter: number;
  description: string;
  status: string;
  transactionDate: string;
  counterpartyName?: string;
}

interface WalletType { code: string; name: string; description: string; }

interface IssueWalletRequest {
  programId?: string;
  holderName: string;
  holderMobile: string;
  holderEmail?: string;
  walletType?: string;
  initialLoadAmount?: number;
  dailyLimit?: number;
  monthlyLimit?: number;
}

interface UpdateWalletRequest {
  walletName?: string;
  dailyLimit?: number;
  monthlyLimit?: number;
  perTransactionLimit?: number;
}

interface CreateProgramRequest {
  programCode: string;
  programName: string;
  description?: string;
  corporateId: string;
  physicalAccountId: string;
  currency?: string;
  dailySpendLimit?: number;
  monthlySpendLimit?: number;
  maxBalance?: number;
  minTopup?: number;
  maxTopup?: number;
  kycRequired?: boolean;
  expiryDays?: number;
}

interface UpdateProgramRequest {
  programName?: string;
  dailySpendLimit?: number;
  monthlySpendLimit?: number;
  maxBalance?: number;
  kycRequired?: boolean;
}

interface LoadFundsRequest { amount: number; source: string; sourceReference?: string; description?: string; }
interface WithdrawRequest { amount: number; destination: string; description?: string; }
interface TransferRequest { toWalletId?: string; toWalletReference?: string; amount: number; description?: string; }
interface BulkLoadRequest { programId?: string; loads: BulkLoadItem[]; description?: string; }
interface BulkLoadItem { walletId?: string; walletReference?: string; amount: number; }
interface BulkLoadResponse { totalCount: number; successCount: number; failedCount: number; totalAmount: number; successAmount: number; results: BulkLoadResult[]; }
interface BulkLoadResult { walletId?: string; walletReference?: string; amount: number; status: 'SUCCESS' | 'FAILED'; referenceNumber?: string; errorMessage?: string; }
interface VerifyKycRequest { verificationMethod: string; documentType?: string; documentNumber?: string; documentExpiryDate?: string; verifiedBy?: string; }
interface KycVerificationResponse { walletId: string; walletReference: string; kycVerified: boolean; kycStatus: string; }
interface LoadFundsResponse { walletId: string; walletReference: string; previousBalance: number; loadAmount: number; newBalance: number; referenceNumber: string; status: string; }
interface WithdrawResponse { walletId: string; walletReference: string; previousBalance: number; withdrawAmount: number; newBalance: number; referenceNumber: string; status: string; }
interface TransferResponse { fromWalletId: string; fromWalletReference: string; toWalletId: string; toWalletReference: string; amount: number; referenceNumber: string; status: string; }

// ============================================================================
// Demo Data (BaaS-focused)
// ============================================================================

const demoPrograms: WalletProgram[] = [
  {
    id: '1',
    programCode: 'BAAS-FINTECH-A',
    programName: 'Fintech Partner A - Consumer Wallets',
    operatorName: 'Fintech A Technologies',
    corporateId: 'corp-fintech-a',
    currency: 'AED',
    activeWallets: 12500,
    totalWallets: 13200,
    totalBalance: 45000000,
    dailySpendLimit: 5000,
    monthlySpendLimit: 25000,
    maxBalance: 100000,
    kycRequired: true,
    status: 'ACTIVE',
    launchDate: '2024-01-01',
  },
  {
    id: '2',
    programCode: 'BAAS-FINTECH-B',
    programName: 'Fintech Partner B - Merchant Wallets',
    operatorName: 'Fintech B Payments',
    corporateId: 'corp-fintech-b',
    currency: 'AED',
    activeWallets: 3500,
    totalWallets: 3800,
    totalBalance: 125000000,
    dailySpendLimit: 50000,
    monthlySpendLimit: 500000,
    maxBalance: 1000000,
    kycRequired: true,
    status: 'ACTIVE',
    launchDate: '2023-06-15',
  },
  {
    id: '3',
    programCode: 'WP-CORP-GIFT',
    programName: 'Corporate Gift Cards',
    operatorName: 'Emirates Group',
    corporateId: 'corp-emirates',
    currency: 'AED',
    activeWallets: 1250,
    totalWallets: 1320,
    totalBalance: 4500000,
    dailySpendLimit: 5000,
    monthlySpendLimit: 25000,
    status: 'ACTIVE',
    launchDate: '2024-01-01',
  },
];

const demoWallets: WalletAccount[] = [
  {
    id: '1',
    walletReference: 'WAL-FINTA-00012345',
    walletName: 'Mohammed Al Rashid',
    holderName: 'Mohammed Al Rashid',
    holderMobile: '+971501234567',
    programId: '1',
    programName: 'Fintech Partner A - Consumer Wallets',
    programCode: 'BAAS-FINTECH-A',
    currentBalance: 25000,
    availableBalance: 25000,
    currency: 'AED',
    dailySpent: 500,
    monthlySpent: 3500,
    dailyLimit: 5000,
    monthlyLimit: 25000,
    status: 'ACTIVE',
    lastTransaction: '2024-02-12T14:30:00Z',
    transactionCount: 145,
    kycVerified: true,
    kycStatus: 'VERIFIED',
    createdAt: '2024-01-15T10:00:00Z',
  },
  {
    id: '2',
    walletReference: 'WAL-FINTA-00012346',
    walletName: 'Sarah Ahmed',
    holderName: 'Sarah Ahmed',
    holderMobile: '+971509876543',
    programId: '1',
    programName: 'Fintech Partner A - Consumer Wallets',
    programCode: 'BAAS-FINTECH-A',
    currentBalance: 12000,
    availableBalance: 12000,
    currency: 'AED',
    dailySpent: 0,
    monthlySpent: 1800,
    dailyLimit: 5000,
    monthlyLimit: 25000,
    status: 'ACTIVE',
    lastTransaction: '2024-02-11T09:15:00Z',
    transactionCount: 23,
    kycVerified: true,
    kycStatus: 'VERIFIED',
    createdAt: '2024-01-20T14:00:00Z',
  },
  {
    id: '3',
    walletReference: 'WAL-FINTB-00005001',
    walletName: 'Tech Solutions LLC',
    holderName: 'Tech Solutions LLC',
    holderMobile: '+971505551234',
    programId: '2',
    programName: 'Fintech Partner B - Merchant Wallets',
    programCode: 'BAAS-FINTECH-B',
    currentBalance: 850000,
    availableBalance: 850000,
    currency: 'AED',
    dailySpent: 15000,
    monthlySpent: 95000,
    dailyLimit: 50000,
    monthlyLimit: 500000,
    status: 'ACTIVE',
    lastTransaction: '2024-02-10T16:45:00Z',
    transactionCount: 512,
    kycVerified: true,
    kycStatus: 'VERIFIED',
    createdAt: '2024-02-01T09:00:00Z',
  },
  {
    id: '4',
    walletReference: 'WAL-FINTA-00012347',
    walletName: 'Fatima Al Ali',
    holderName: 'Fatima Al Ali',
    holderMobile: '+971507778899',
    programId: '1',
    programName: 'Fintech Partner A - Consumer Wallets',
    programCode: 'BAAS-FINTECH-A',
    currentBalance: 3500,
    availableBalance: 3500,
    currency: 'AED',
    dailySpent: 0,
    monthlySpent: 500,
    dailyLimit: 1000,
    monthlyLimit: 5000,
    status: 'ACTIVE',
    lastTransaction: '2024-02-09T11:20:00Z',
    transactionCount: 8,
    kycVerified: false,
    kycStatus: 'PENDING',
    createdAt: '2024-02-05T11:00:00Z',
  },
  {
    id: '5',
    walletReference: 'WAL-FINTA-00012348',
    walletName: 'Omar Hassan',
    holderName: 'Omar Hassan',
    holderMobile: '+971501112233',
    programId: '1',
    programName: 'Fintech Partner A - Consumer Wallets',
    programCode: 'BAAS-FINTECH-A',
    currentBalance: 0,
    availableBalance: 0,
    currency: 'AED',
    dailySpent: 0,
    monthlySpent: 0,
    dailyLimit: 1000,
    monthlyLimit: 5000,
    status: 'SUSPENDED',
    lastTransaction: '2024-01-25T11:20:00Z',
    transactionCount: 3,
    kycVerified: false,
    kycStatus: 'REJECTED',
    createdAt: '2024-01-20T11:00:00Z',
  },
];

const demoStats: WalletStats = {
  totalPrograms: 3,
  activePrograms: 3,
  totalWallets: 17320,
  activeWallets: 17250,
  suspendedWallets: 50,
  blockedWallets: 20,
  totalBalance: 174500000,
  totalAvailableBalance: 170000000,
  monthlyVolume: 25000000,
  dailyVolume: 1250000,
  todayTransactions: 4500,
  monthlyTransactions: 125000,
  kycVerifiedCount: 16500,
  kycPendingCount: 820,
};

// ============================================================================
// Status configurations
// ============================================================================

const walletStatusConfig: Record<string, { label: string; color: string; icon: React.ReactNode }> = {
  ACTIVE: { label: 'Active', color: 'success', icon: <Unlock className="w-3 h-3" /> },
  SUSPENDED: { label: 'Suspended', color: 'warning', icon: <Lock className="w-3 h-3" /> },
  BLOCKED: { label: 'Blocked', color: 'error', icon: <Ban className="w-3 h-3" /> },
};

const programStatusConfig: Record<string, { label: string; color: string }> = {
  ACTIVE: { label: 'Active', color: 'success' },
  SUSPENDED: { label: 'Suspended', color: 'warning' },
  CLOSED: { label: 'Closed', color: 'error' },
  PENDING_APPROVAL: { label: 'Pending', color: 'info' },
};

const kycStatusConfig: Record<string, { label: string; color: string; icon: React.ReactNode }> = {
  VERIFIED: { label: 'Verified', color: 'success', icon: <CheckCircle className="w-3 h-3" /> },
  PENDING: { label: 'Pending', color: 'warning', icon: <Clock className="w-3 h-3" /> },
  REJECTED: { label: 'Rejected', color: 'error', icon: <XCircle className="w-3 h-3" /> },
  EXPIRED: { label: 'Expired', color: 'error', icon: <AlertCircle className="w-3 h-3" /> },
};

// ============================================================================
// Utility Components
// ============================================================================

const ChevronRight = ({ className }: { className?: string }) => (
  <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
    <polyline points="9,18 15,12 9,6" />
  </svg>
);

const StatCard: React.FC<{
  label: string;
  value: string | number;
  subValue?: string;
  icon: React.ReactNode;
  iconBg: string;
  trend?: { value: number; label: string };
}> = ({ label, value, subValue, icon, iconBg, trend }) => (
  <Card padding="sm">
    <div className="flex items-center justify-between">
      <div>
        <p className="text-sm text-neutral-500 dark:text-neutral-400">{label}</p>
        <p className="stat-value-sm">{value}</p>
        {subValue && <p className="text-xs text-neutral-400 dark:text-neutral-500 mt-0.5">{subValue}</p>}
        {trend && (
          <div className={cn("flex items-center gap-1 text-xs mt-1", trend.value >= 0 ? "text-success-600 dark:text-success-300" : "text-error-600 dark:text-error-300")}>
            {trend.value >= 0 ? <ChevronUp className="w-3 h-3" /> : <ChevronDown className="w-3 h-3" />}
            <span>{Math.abs(trend.value)}% {trend.label}</span>
          </div>
        )}
      </div>
      <div className={cn("p-3 rounded-xl", iconBg)}>{icon}</div>
    </div>
  </Card>
);

// ============================================================================
// Program Card Component
// ============================================================================

const ProgramCard: React.FC<{
  program: WalletProgram;
  onClick: () => void;
  onEdit: () => void;
}> = ({ program, onClick, onEdit }) => {
  const statusConfig = programStatusConfig[program.status] || programStatusConfig.ACTIVE;
  
  return (
    <Card hover className="cursor-pointer" onClick={onClick}>
      <div className="flex items-start justify-between mb-4">
        <div className="flex items-center gap-3">
          {/* Phase 12 Task G: flat tonal medallion (gradient + white-icon
              recipe retired in Phase 8). */}
          <div className="w-12 h-12 rounded-xl bg-primary-100 dark:bg-primary-700 flex items-center justify-center">
            <Wallet className="w-6 h-6 text-primary-700 dark:text-primary-200" />
          </div>
          <div>
            <h3 className="section-title">{program.programName}</h3>
            <p className="text-sm text-neutral-500 dark:text-neutral-400">{program.programCode}</p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <Badge variant={statusConfig.color as any}>{statusConfig.label}</Badge>
          <button
            onClick={(e) => { e.stopPropagation(); onEdit(); }}
            className="p-1.5 hover:bg-neutral-100 dark:hover:bg-primary-800 rounded-lg transition-colors"
          >
            <Edit className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
          </button>
        </div>
      </div>

      <div className="grid grid-cols-3 gap-4 mb-4">
        <div>
          <p className="text-xs text-neutral-500 dark:text-neutral-400">Active Wallets</p>
          <p className="section-title">{program.activeWallets.toLocaleString()}</p>
        </div>
        <div>
          <p className="text-xs text-neutral-500 dark:text-neutral-400">Total Balance</p>
          <p className="section-title">{formatCurrency(program.totalBalance)}</p>
        </div>
        <div>
          <p className="text-xs text-neutral-500 dark:text-neutral-400">Daily Limit</p>
          {/* Phase 12 Task E: .stat-value-xs replaces the raw `text-xl font-semibold`;
              muted ink kept for the secondary figure. */}
          <p className="stat-value-xs text-neutral-700 dark:text-neutral-200">{formatCurrency(program.dailySpendLimit)}</p>
        </div>
      </div>

      <div className="flex items-center justify-between pt-4 border-t border-neutral-200 dark:border-primary-800">
        <div className="flex items-center gap-2 text-sm text-neutral-500 dark:text-neutral-400">
          <Building2 className="w-4 h-4" />
          <span>{program.operatorName}</span>
        </div>
        <div className="flex items-center gap-2">
          <span className="text-xs text-neutral-400 dark:text-neutral-500">
            {program.currency} • Since {new Date(program.launchDate).toLocaleDateString()}
          </span>
          <ChevronRight className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
        </div>
      </div>
    </Card>
  );
};

// ============================================================================
// Wallet Row Component - Enhanced with more actions
// ============================================================================

const WalletRow: React.FC<{
  wallet: WalletAccount;
  onView: () => void;
  onEdit: () => void;
  onAction: (action: string) => void;
}> = ({ wallet, onView, onEdit, onAction }) => {
  const [showActions, setShowActions] = useState(false);
  const status = walletStatusConfig[wallet.status] || walletStatusConfig.ACTIVE;
  const kycStatus = kycStatusConfig[wallet.kycStatus] || kycStatusConfig.PENDING;
  const dailyUsage = wallet.dailyLimit > 0 ? (wallet.dailySpent / wallet.dailyLimit) * 100 : 0;
  const monthlyUsage = wallet.monthlyLimit > 0 ? (wallet.monthlySpent / wallet.monthlyLimit) * 100 : 0;

  const copyToClipboard = (text: string) => {
    navigator.clipboard.writeText(text);
  };

  return (
    <tr className="hover:bg-neutral-50 dark:hover:bg-primary-800/50 transition-colors">
      <td className="px-6 py-4">
        <div className="flex items-center gap-3">
          <Avatar name={wallet.holderName} size="md" status={wallet.kycVerified ? 'online' : 'away'} />
          <div>
            <p className="text-base font-medium text-primary-900 dark:text-neutral-50">{wallet.holderName}</p>
            <p className="text-sm text-neutral-500 dark:text-neutral-400">{wallet.holderMobile}</p>
          </div>
        </div>
      </td>
      <td className="px-6 py-4">
        <div className="flex items-center gap-1">
          <p className="text-sm font-mono text-primary-900 dark:text-neutral-50">{wallet.walletReference}</p>
          <button onClick={() => copyToClipboard(wallet.walletReference)} className="p-1 hover:bg-neutral-100 dark:hover:bg-primary-800 rounded">
            <Copy className="w-3 h-3 text-neutral-400 dark:text-neutral-500" />
          </button>
        </div>
        <p className="text-xs text-neutral-500 dark:text-neutral-400">{wallet.programCode || wallet.programName}</p>
      </td>
      <td className="px-6 py-4">
        <p className="text-base font-semibold text-primary-900 dark:text-neutral-50">{formatCurrency(wallet.currentBalance)}</p>
        <p className="text-xs text-neutral-500 dark:text-neutral-400">Available: {formatCurrency(wallet.availableBalance)}</p>
      </td>
      <td className="px-6 py-4">
        <div className="space-y-2 w-32">
          <div>
            <div className="flex justify-between text-xs mb-1">
              <span className="text-neutral-500 dark:text-neutral-400">Daily</span>
              <span className="text-primary-900 dark:text-neutral-50">{formatCurrency(wallet.dailySpent)}</span>
            </div>
            <ProgressBar value={dailyUsage} size="sm" variant={dailyUsage > 80 ? 'warning' : 'default'} />
          </div>
          <div>
            <div className="flex justify-between text-xs mb-1">
              <span className="text-neutral-500 dark:text-neutral-400">Monthly</span>
              <span className="text-primary-900 dark:text-neutral-50">{formatCurrency(wallet.monthlySpent)}</span>
            </div>
            <ProgressBar value={monthlyUsage} size="sm" variant={monthlyUsage > 80 ? 'warning' : 'default'} />
          </div>
        </div>
      </td>
      <td className="px-6 py-4">
        <Badge variant={status.color as any} className="flex items-center gap-1 w-fit">
          {status.icon}{status.label}
        </Badge>
        <div className="flex items-center gap-1 mt-1">
          <Badge variant={kycStatus.color as any} size="sm" className="flex items-center gap-1">
            {kycStatus.icon}{kycStatus.label}
          </Badge>
        </div>
      </td>
      <td className="px-6 py-4">
        <p className="text-sm text-neutral-600 dark:text-neutral-300">{wallet.transactionCount} txns</p>
        <p className="text-xs text-neutral-400 dark:text-neutral-500">
          {wallet.lastTransaction ? new Date(wallet.lastTransaction).toLocaleDateString() : 'No txns'}
        </p>
      </td>
      <td className="px-6 py-4">
        <div className="relative">
          <button
            onClick={(e) => { e.stopPropagation(); setShowActions(!showActions); }}
            className="p-2 hover:bg-neutral-100 dark:hover:bg-primary-800 rounded-lg"
          >
            <MoreHorizontal className="w-5 h-5 text-neutral-500 dark:text-neutral-400" />
          </button>
          {showActions && (
            <>
              <div className="fixed inset-0 z-10" onClick={() => setShowActions(false)} />
              <div className="absolute right-0 top-full mt-1 w-52 bg-white dark:bg-primary-900 rounded-lg shadow-lg border py-1 z-20">
                <button onClick={() => { onView(); setShowActions(false); }} className="w-full flex items-center gap-2 px-4 py-2 text-sm hover:bg-neutral-50 dark:hover:bg-primary-800/50">
                  <Eye className="w-4 h-4" /> View Details
                </button>
                <button onClick={() => { onEdit(); setShowActions(false); }} className="w-full flex items-center gap-2 px-4 py-2 text-sm hover:bg-neutral-50 dark:hover:bg-primary-800/50">
                  <Edit className="w-4 h-4" /> Edit Limits
                </button>
                <hr className="my-1" />
                <button onClick={() => { onAction('load'); setShowActions(false); }} className="w-full flex items-center gap-2 px-4 py-2 text-sm hover:bg-neutral-50 dark:hover:bg-primary-800/50">
                  <ArrowDownRight className="w-4 h-4 text-success-600 dark:text-success-300" /> Load Funds
                </button>
                <button onClick={() => { onAction('withdraw'); setShowActions(false); }} className="w-full flex items-center gap-2 px-4 py-2 text-sm hover:bg-neutral-50 dark:hover:bg-primary-800/50">
                  <ArrowUpRight className="w-4 h-4 text-error-600 dark:text-error-300" /> Withdraw
                </button>
                <button onClick={() => { onAction('transfer'); setShowActions(false); }} className="w-full flex items-center gap-2 px-4 py-2 text-sm hover:bg-neutral-50 dark:hover:bg-primary-800/50">
                  <Send className="w-4 h-4" /> Transfer
                </button>
                <hr className="my-1" />
                {!wallet.kycVerified && (
                  <button onClick={() => { onAction('verify-kyc'); setShowActions(false); }} className="w-full flex items-center gap-2 px-4 py-2 text-sm text-success-600 dark:text-success-300 hover:bg-success-50 dark:bg-success-500/10 dark:hover:bg-success-500/10">
                    <UserCheck className="w-4 h-4" /> Verify KYC
                  </button>
                )}
                {wallet.status === 'ACTIVE' ? (
                  <>
                    <button onClick={() => { onAction('suspend'); setShowActions(false); }} className="w-full flex items-center gap-2 px-4 py-2 text-sm text-warning-600 dark:text-warning-300 hover:bg-warning-50 dark:bg-warning-500/10 dark:hover:bg-warning-500/10">
                      <Lock className="w-4 h-4" /> Suspend
                    </button>
                    <button onClick={() => { onAction('block'); setShowActions(false); }} className="w-full flex items-center gap-2 px-4 py-2 text-sm text-error-600 dark:text-error-300 hover:bg-error-50 dark:bg-error-500/10 dark:hover:bg-error-500/10">
                      <Ban className="w-4 h-4" /> Block
                    </button>
                  </>
                ) : wallet.status === 'SUSPENDED' ? (
                  <button onClick={() => { onAction('reactivate'); setShowActions(false); }} className="w-full flex items-center gap-2 px-4 py-2 text-sm text-success-600 dark:text-success-300 hover:bg-success-50 dark:bg-success-500/10 dark:hover:bg-success-500/10">
                    <Unlock className="w-4 h-4" /> Reactivate
                  </button>
                ) : null}
              </div>
            </>
          )}
        </div>
      </td>
    </tr>
  );
};

// ============================================================================
// Main Wallet Page - Enhanced BaaS Version
// ============================================================================

const WalletPage: React.FC = () => {
  // View state
  const [activeTab, setActiveTab] = useState<'overview' | 'programs' | 'wallets'>('overview');
  const [searchQuery, setSearchQuery] = useState('');
  const [statusFilter, setStatusFilter] = useState<string>('');
  const [kycFilter, setKycFilter] = useState<string>('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionSuccess, setActionSuccess] = useState<string | null>(null);
  
  // Partner filter for BaaS
  const [selectedPartnerId, setSelectedPartnerId] = useState<string>('');
  
  // Data state
  const [stats, setStats] = useState<WalletStats>(demoStats);
  const [programs, setPrograms] = useState<WalletProgram[]>(demoPrograms);
  const [wallets, setWallets] = useState<WalletAccount[]>(demoWallets);
  const [totalWallets, setTotalWallets] = useState(0);
  const [currentPage, setCurrentPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  
  // Modal state
  const [showIssueModal, setShowIssueModal] = useState(false);
  const [showLoadModal, setShowLoadModal] = useState(false);
  const [showWithdrawModal, setShowWithdrawModal] = useState(false);
  const [showTransferModal, setShowTransferModal] = useState(false);
  const [showDetailModal, setShowDetailModal] = useState(false);
  const [showEditWalletModal, setShowEditWalletModal] = useState(false);
  const [showCreateProgramModal, setShowCreateProgramModal] = useState(false);
  const [showEditProgramModal, setShowEditProgramModal] = useState(false);
  const [showBulkLoadModal, setShowBulkLoadModal] = useState(false);
  const [showKycVerifyModal, setShowKycVerifyModal] = useState(false);
  const [showBlockModal, setShowBlockModal] = useState(false);
  
  const [selectedProgram, setSelectedProgram] = useState<WalletProgram | null>(null);
  const [selectedWallet, setSelectedWallet] = useState<WalletAccount | null>(null);
  const [walletDetail, setWalletDetail] = useState<WalletAccountDetail | null>(null);
  
  // Form state
  const [issueForm, setIssueForm] = useState<IssueWalletRequest>({
    programId: '', holderName: '', holderMobile: '', holderEmail: '', walletType: 'CONSUMER',
    initialLoadAmount: undefined, dailyLimit: undefined, monthlyLimit: undefined,
  });
  const [editWalletForm, setEditWalletForm] = useState<UpdateWalletRequest>({
    walletName: '', dailyLimit: undefined, monthlyLimit: undefined, perTransactionLimit: undefined,
  });
  const [createProgramForm, setCreateProgramForm] = useState<CreateProgramRequest>({
    programCode: '', programName: '', description: '', corporateId: '', physicalAccountId: '',
    currency: 'AED', dailySpendLimit: 5000, monthlySpendLimit: 25000, maxBalance: 100000,
    minTopup: 10, maxTopup: 10000, kycRequired: true, expiryDays: 365,
  });
  const [editProgramForm, setEditProgramForm] = useState<UpdateProgramRequest>({});
  const [loadForm, setLoadForm] = useState({ amount: '', source: 'BANK_TRANSFER', description: '' });
  const [withdrawForm, setWithdrawForm] = useState({ amount: '', destination: 'BANK_TRANSFER', description: '' });
  const [transferForm, setTransferForm] = useState({ toWalletReference: '', amount: '', description: '' });
  const [bulkLoadForm, setBulkLoadForm] = useState({ programId: '', csvData: '', description: '' });
  const [kycVerifyForm, setKycVerifyForm] = useState<VerifyKycRequest>({
    verificationMethod: 'DOCUMENT', documentType: 'EMIRATES_ID', documentNumber: '', documentExpiryDate: '', verifiedBy: '',
  });
  const [blockForm, setBlockForm] = useState({ reason: '', permanent: false });
  const [actionLoading, setActionLoading] = useState(false);

  // Tabs configuration
  const tabs = [
    { id: 'overview', label: 'Overview', icon: <LayoutDashboard className="w-4 h-4" /> },
    { id: 'programs', label: 'Programs', icon: <Settings className="w-4 h-4" />, badge: programs.length },
    { id: 'wallets', label: 'Wallets', icon: <CreditCard className="w-4 h-4" />, badge: totalWallets || wallets.length },
  ];

  // Data Fetching
  const fetchData = useCallback(async () => {
    setLoading(true);
    setError(null);
    const errors: string[] = [];
    try {
      try {
        const statsData = await walletsApi.getStats(selectedPartnerId || undefined);
        if (statsData) setStats(statsData);
      } catch (e: any) {
        console.warn('Stats API failed:', e.message);
        errors.push('Stats');
      }

      try {
        const programsData = await walletsApi.getPrograms(selectedPartnerId || undefined);
        if (programsData) setPrograms(programsData);
      } catch (e: any) {
        console.warn('Programs API failed:', e.message);
        errors.push('Programs');
      }

      try {
        const walletsData = await walletsApi.getWallets({
          page: currentPage, size: 20, query: searchQuery || undefined,
          status: statusFilter || undefined, programId: selectedPartnerId || undefined,
          kycVerified: kycFilter === 'verified' ? true : kycFilter === 'pending' ? false : undefined,
        });
        if (walletsData?.content) {
          setWallets(walletsData.content);
          setTotalWallets(walletsData.totalElements);
          setTotalPages(walletsData.totalPages);
        }
      } catch (e: any) {
        console.warn('Wallets API failed:', e.message);
        errors.push('Wallets');
      }
      if (errors.length > 0) {
        setError(`API unavailable for: ${errors.join(', ')}. Showing demo data.`);
      }
    } catch (err) {
      console.error('Error fetching data:', err);
      setError('Failed to load data. Showing demo data.');
    } finally {
      setLoading(false);
    }
  }, [currentPage, searchQuery, statusFilter, kycFilter, selectedPartnerId]);

  useEffect(() => { fetchData(); }, [fetchData]);

  // Helper functions
  const showSuccess = (message: string) => { setActionSuccess(message); setTimeout(() => setActionSuccess(null), 3000); };
  const showError = (message: string) => { setError(message); };

  // Action Handlers
  const handleIssueWallet = async () => {
    if (!issueForm.holderName || !issueForm.holderMobile) { showError('Please fill in required fields'); return; }
    setActionLoading(true);
    try {
      await walletsApi.issueWallet(issueForm);
      showSuccess('Wallet issued successfully!');
      setShowIssueModal(false);
      setIssueForm({ programId: '', holderName: '', holderMobile: '', holderEmail: '', walletType: 'CONSUMER', initialLoadAmount: undefined });
      fetchData();
    } catch (err: any) { showError(err.message || 'Failed to issue wallet'); }
    finally { setActionLoading(false); }
  };

  const handleUpdateWallet = async () => {
    if (!selectedWallet) return;
    setActionLoading(true);
    try {
      await walletsApi.updateWallet(selectedWallet.id, editWalletForm);
      showSuccess('Wallet updated successfully!');
      setShowEditWalletModal(false);
      fetchData();
    } catch (err: any) { showError(err.message || 'Failed to update wallet'); }
    finally { setActionLoading(false); }
  };

  const handleCreateProgram = async () => {
    if (!createProgramForm.programCode || !createProgramForm.programName) { showError('Please fill in required fields'); return; }
    setActionLoading(true);
    try {
      await walletsApi.createProgram(createProgramForm);
      showSuccess('Program created successfully!');
      setShowCreateProgramModal(false);
      setCreateProgramForm({ programCode: '', programName: '', description: '', corporateId: '', physicalAccountId: '', currency: 'AED', dailySpendLimit: 5000, monthlySpendLimit: 25000, maxBalance: 100000, minTopup: 10, maxTopup: 10000, kycRequired: true, expiryDays: 365 });
      fetchData();
    } catch (err: any) { showError(err.message || 'Failed to create program'); }
    finally { setActionLoading(false); }
  };

  const handleUpdateProgram = async () => {
    if (!selectedProgram) return;
    setActionLoading(true);
    try {
      await walletsApi.updateProgram(selectedProgram.id, editProgramForm);
      showSuccess('Program updated successfully!');
      setShowEditProgramModal(false);
      fetchData();
    } catch (err: any) { showError(err.message || 'Failed to update program'); }
    finally { setActionLoading(false); }
  };

  const handleLoadFunds = async () => {
    if (!selectedWallet || !loadForm.amount) return;
    setActionLoading(true);
    try {
      const result = await walletsApi.loadFunds(selectedWallet.id, { amount: parseFloat(loadForm.amount), source: loadForm.source, description: loadForm.description || undefined });
      showSuccess(`Funds loaded successfully! Ref: ${result.referenceNumber}`);
      setShowLoadModal(false);
      setLoadForm({ amount: '', source: 'BANK_TRANSFER', description: '' });
      fetchData();
    } catch (err: any) { showError(err.message || 'Failed to load funds'); }
    finally { setActionLoading(false); }
  };

  const handleWithdraw = async () => {
    if (!selectedWallet || !withdrawForm.amount) return;
    setActionLoading(true);
    try {
      const result = await walletsApi.withdrawFunds(selectedWallet.id, { amount: parseFloat(withdrawForm.amount), destination: withdrawForm.destination, description: withdrawForm.description || undefined });
      showSuccess(`Withdrawal completed! Ref: ${result.referenceNumber}`);
      setShowWithdrawModal(false);
      setWithdrawForm({ amount: '', destination: 'BANK_TRANSFER', description: '' });
      fetchData();
    } catch (err: any) { showError(err.message || 'Failed to withdraw funds'); }
    finally { setActionLoading(false); }
  };

  const handleTransfer = async () => {
    if (!selectedWallet || !transferForm.toWalletReference || !transferForm.amount) return;
    setActionLoading(true);
    try {
      const result = await walletsApi.transferFunds(selectedWallet.id, { toWalletReference: transferForm.toWalletReference, amount: parseFloat(transferForm.amount), description: transferForm.description || undefined });
      showSuccess(`Transfer completed! Ref: ${result.referenceNumber}`);
      setShowTransferModal(false);
      setTransferForm({ toWalletReference: '', amount: '', description: '' });
      fetchData();
    } catch (err: any) { showError(err.message || 'Failed to complete transfer'); }
    finally { setActionLoading(false); }
  };

  const handleBulkLoad = async () => {
    if (!bulkLoadForm.csvData) { showError('Please enter wallet data'); return; }
    const lines = bulkLoadForm.csvData.trim().split('\n');
    const loads: BulkLoadItem[] = lines.map(line => {
      const [walletReference, amountStr] = line.split(',').map(s => s.trim());
      return { walletReference, amount: parseFloat(amountStr) };
    }).filter(item => item.walletReference && !isNaN(item.amount));
    if (loads.length === 0) { showError('No valid entries found. Format: walletReference,amount'); return; }
    setActionLoading(true);
    try {
      const result = await walletsApi.bulkLoadFunds({ programId: bulkLoadForm.programId || undefined, loads, description: bulkLoadForm.description || undefined });
      showSuccess(`Bulk load completed: ${result.successCount}/${result.totalCount} successful`);
      setShowBulkLoadModal(false);
      setBulkLoadForm({ programId: '', csvData: '', description: '' });
      fetchData();
    } catch (err: any) { showError(err.message || 'Failed to process bulk load'); }
    finally { setActionLoading(false); }
  };

  const handleVerifyKyc = async () => {
    if (!selectedWallet) return;
    setActionLoading(true);
    try {
      await walletsApi.verifyKyc(selectedWallet.id, kycVerifyForm);
      showSuccess('KYC verified successfully!');
      setShowKycVerifyModal(false);
      setKycVerifyForm({ verificationMethod: 'DOCUMENT', documentType: 'EMIRATES_ID', documentNumber: '', documentExpiryDate: '', verifiedBy: '' });
      fetchData();
    } catch (err: any) { showError(err.message || 'Failed to verify KYC'); }
    finally { setActionLoading(false); }
  };

  const handleBlockWallet = async () => {
    if (!selectedWallet || !blockForm.reason) { showError('Please provide a reason for blocking'); return; }
    setActionLoading(true);
    try {
      await walletsApi.blockWallet(selectedWallet.id, blockForm.reason, blockForm.permanent);
      showSuccess('Wallet blocked');
      setShowBlockModal(false);
      setBlockForm({ reason: '', permanent: false });
      fetchData();
    } catch (err: any) { showError(err.message || 'Failed to block wallet'); }
    finally { setActionLoading(false); }
  };

  const handleWalletAction = async (action: string, wallet: WalletAccount) => {
    setSelectedWallet(wallet);
    switch (action) {
      case 'load': setShowLoadModal(true); break;
      case 'withdraw': setShowWithdrawModal(true); break;
      case 'transfer': setShowTransferModal(true); break;
      case 'verify-kyc': setShowKycVerifyModal(true); break;
      case 'block': setShowBlockModal(true); break;
      case 'suspend':
        setActionLoading(true);
        try { await walletsApi.suspendWallet(wallet.id, 'User requested suspension'); showSuccess('Wallet suspended'); fetchData(); }
        catch (err: any) { showError(err.message || 'Failed to suspend wallet'); }
        finally { setActionLoading(false); }
        break;
      case 'reactivate':
        setActionLoading(true);
        try { await walletsApi.reactivateWallet(wallet.id, 'User requested reactivation'); showSuccess('Wallet reactivated'); fetchData(); }
        catch (err: any) { showError(err.message || 'Failed to reactivate wallet'); }
        finally { setActionLoading(false); }
        break;
    }
  };

  const handleViewWallet = async (wallet: WalletAccount) => {
    setSelectedWallet(wallet);
    setShowDetailModal(true);
    try {
      const detail = await walletsApi.getWalletDetails(wallet.id);
      setWalletDetail(detail);
    } catch (err) {
      setWalletDetail({ ...wallet, recentTransactions: [] } as WalletAccountDetail);
    }
  };

  const handleEditWallet = (wallet: WalletAccount) => {
    setSelectedWallet(wallet);
    setEditWalletForm({ walletName: wallet.walletName, dailyLimit: wallet.dailyLimit, monthlyLimit: wallet.monthlyLimit });
    setShowEditWalletModal(true);
  };

  const handleEditProgram = (program: WalletProgram) => {
    setSelectedProgram(program);
    setEditProgramForm({ programName: program.programName, dailySpendLimit: program.dailySpendLimit, monthlySpendLimit: program.monthlySpendLimit, maxBalance: program.maxBalance, kycRequired: program.kycRequired });
    setShowEditProgramModal(true);
  };

  // Filter wallets
  const filteredWallets = wallets.filter(w => {
    const matchesSearch = !searchQuery || 
      w.holderName.toLowerCase().includes(searchQuery.toLowerCase()) ||
      w.walletReference.toLowerCase().includes(searchQuery.toLowerCase()) ||
      w.holderMobile.includes(searchQuery);
    return matchesSearch;
  });

  // Get unique partners from programs
  const partners = Array.from(new Map(programs.filter(p => p.corporateId).map(p => [p.corporateId, { id: p.corporateId, name: p.operatorName }])).values());

  // ============================================================================
  // Render
  // ============================================================================

  return (
    <div className="space-y-6">
      {/* Success/Error Messages */}
      {actionSuccess && (
        <Alert variant="success" className="flex items-center gap-2">
          <CheckCircle className="w-4 h-4" />{actionSuccess}
        </Alert>
      )}
      {error && (
        <Alert variant="error" className="flex items-center gap-2">
          <AlertCircle className="w-4 h-4" />{error}
          <button onClick={() => setError(null)} className="ml-auto text-sm underline">Dismiss</button>
        </Alert>
      )}

      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="page-title">Wallet Programs</h1>
          <p className="text-base text-neutral-500 dark:text-neutral-400 mt-1">BaaS wallet management for fintech partners and prepaid programs</p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button variant="outline" leftIcon={loading ? <Loader2 className="w-4 h-4 animate-spin" /> : <RefreshCw className="w-4 h-4" />} onClick={() => fetchData()} disabled={loading}>Refresh</Button>
          <Button variant="outline" leftIcon={<Upload className="w-4 h-4" />} onClick={() => setShowBulkLoadModal(true)}>Bulk Load</Button>
          <Button variant="outline" leftIcon={<Plus className="w-4 h-4" />} onClick={() => setShowCreateProgramModal(true)}>New Program</Button>
          <Button leftIcon={<CreditCard className="w-4 h-4" />} onClick={() => setShowIssueModal(true)}>Issue Wallet</Button>
        </div>
      </div>

      {/* Partner Filter (BaaS) */}
      <Card padding="sm" className="bg-gradient-to-r from-primary-50 to-accent-50 border-primary-200 dark:border-primary-700 dark:from-primary-500/15 dark:to-accent-500/15">
        <div className="flex flex-col sm:flex-row sm:items-center gap-4">
          <div className="flex items-center gap-2">
            <Building2 className="w-5 h-5 text-primary-600 dark:text-primary-200" />
            <span className="font-medium text-primary-800 dark:text-neutral-100">Partner View</span>
          </div>
          <select
            className="flex-1 max-w-xs border border-primary-300 rounded-lg px-3 py-2 bg-white dark:bg-primary-900 text-sm"
            value={selectedPartnerId}
            onChange={(e) => { setSelectedPartnerId(e.target.value); setCurrentPage(0); }}
          >
            <option value="">All Partners</option>
            {partners.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
          <div className="flex items-center gap-4 text-sm text-primary-700 dark:text-neutral-200">
            <span><strong>{programs.length}</strong> Programs</span>
            <span><strong>{stats.activeWallets?.toLocaleString()}</strong> Active Wallets</span>
            <span><strong>{formatCurrency(stats.totalBalance)}</strong> Total Float</span>
          </div>
        </div>
      </Card>

      {/* Tabs */}
      <Tabs tabs={tabs} activeTab={activeTab} onChange={(id) => setActiveTab(id as typeof activeTab)} variant="pills" />

      {/* Overview Tab */}
      {activeTab === 'overview' && (
        <div className="space-y-6">
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
            <StatCard label="Total Float Balance" value={formatCurrency(stats.totalBalance)} subValue={`Available: ${formatCurrency(stats.totalAvailableBalance || stats.totalBalance)}`} icon={<Wallet className="w-6 h-6 text-primary-700 dark:text-neutral-200" />} iconBg="bg-primary-100 dark:bg-primary-700" />
            <StatCard label="Active Wallets" value={stats.activeWallets?.toLocaleString() || '0'} subValue={`${stats.suspendedWallets || 0} suspended, ${stats.blockedWallets || 0} blocked`} icon={<CreditCard className="w-6 h-6 text-success-600 dark:text-success-300" />} iconBg="bg-success-50 dark:bg-success-500/10" trend={{ value: 12, label: 'this month' }} />
            <StatCard label="Daily Volume" value={formatCurrency(stats.dailyVolume || 0)} subValue={`${stats.todayTransactions?.toLocaleString() || 0} transactions today`} icon={<Activity className="w-6 h-6 text-info-600 dark:text-info-300" />} iconBg="bg-info-50 dark:bg-info-500/10" />
            <StatCard label="KYC Status" value={`${Math.round((stats.kycVerifiedCount / (stats.kycVerifiedCount + stats.kycPendingCount)) * 100)}%`} subValue={`${stats.kycPendingCount} pending verification`} icon={<Shield className="w-6 h-6 text-warning-600 dark:text-warning-300" />} iconBg="bg-warning-50 dark:bg-warning-500/10" />
          </div>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <Card padding="sm">
              <div className="flex items-center justify-between mb-4"><h3 className="font-semibold text-primary-900 dark:text-neutral-50">Monthly Volume</h3><TrendingUp className="w-5 h-5 text-success-600 dark:text-success-300" /></div>
              <p className="stat-value-sm">{formatCurrency(stats.monthlyVolume)}</p>
              <p className="text-sm text-neutral-500 dark:text-neutral-400 mt-1">{stats.monthlyTransactions?.toLocaleString() || 0} transactions</p>
            </Card>
            <Card padding="sm">
              <div className="flex items-center justify-between mb-4"><h3 className="font-semibold text-primary-900 dark:text-neutral-50">Programs</h3><Settings className="w-5 h-5 text-primary-600 dark:text-primary-200" /></div>
              <p className="stat-value-sm">{stats.totalPrograms}</p>
              <p className="text-sm text-neutral-500 dark:text-neutral-400 mt-1">{stats.activePrograms} active</p>
            </Card>
            <Card padding="sm">
              <div className="flex items-center justify-between mb-4"><h3 className="font-semibold text-primary-900 dark:text-neutral-50">Avg Balance</h3><PieChart className="w-5 h-5 text-accent-600 dark:text-accent-300" /></div>
              <p className="stat-value-sm">{formatCurrency(stats.totalBalance / (stats.activeWallets || 1))}</p>
              <p className="text-sm text-neutral-500 dark:text-neutral-400 mt-1">per wallet</p>
            </Card>
          </div>
          <Card>
            <h3 className="font-semibold text-primary-900 dark:text-neutral-50 mb-4">Quick Actions</h3>
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
              <Button variant="outline" className="h-20 flex-col gap-2" onClick={() => setShowIssueModal(true)}><CreditCard className="w-6 h-6" /><span>Issue Wallet</span></Button>
              <Button variant="outline" className="h-20 flex-col gap-2" onClick={() => setShowBulkLoadModal(true)}><Upload className="w-6 h-6" /><span>Bulk Load</span></Button>
              <Button variant="outline" className="h-20 flex-col gap-2" onClick={() => setShowCreateProgramModal(true)}><Plus className="w-6 h-6" /><span>New Program</span></Button>
              <Button variant="outline" className="h-20 flex-col gap-2" onClick={() => setActiveTab('wallets')}><Search className="w-6 h-6" /><span>Search Wallets</span></Button>
            </div>
          </Card>
        </div>
      )}

      {/* Programs Tab */}
      {activeTab === 'programs' && (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
          {programs.map(program => (
            <ProgramCard key={program.id} program={program} onClick={() => { setSelectedPartnerId(program.id); setActiveTab('wallets'); }} onEdit={() => handleEditProgram(program)} />
          ))}
          {programs.length === 0 && (
            <div className="col-span-2">
              <EmptyState icon={<Settings className="w-8 h-8" />} title="No programs found" description="Create a wallet program to get started" action={<Button onClick={() => setShowCreateProgramModal(true)}>Create Program</Button>} />
            </div>
          )}
        </div>
      )}

      {/* Wallets Tab */}
      {activeTab === 'wallets' && (
        <Card padding="none">
          <div className="p-4 border-b border-neutral-200 dark:border-primary-800">
            <div className="flex flex-col sm:flex-row gap-4">
              <div className="flex-1">
                <Input placeholder="Search by name, mobile, or wallet reference..." value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} leftIcon={<Search className="w-4 h-4" />} />
              </div>
              <div className="flex gap-2">
                <select className="border border-neutral-300 dark:border-primary-700 rounded-lg px-3 py-2 text-sm" value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)}>
                  <option value="">All Status</option>
                  <option value="ACTIVE">Active</option>
                  <option value="SUSPENDED">Suspended</option>
                  <option value="BLOCKED">Blocked</option>
                </select>
                <select className="border border-neutral-300 dark:border-primary-700 rounded-lg px-3 py-2 text-sm" value={kycFilter} onChange={(e) => setKycFilter(e.target.value)}>
                  <option value="">All KYC</option>
                  <option value="verified">Verified</option>
                  <option value="pending">Pending</option>
                </select>
                <Button variant="outline" leftIcon={<Download className="w-4 h-4" />}>Export</Button>
              </div>
            </div>
          </div>

          {loading ? (
            <div className="flex items-center justify-center py-12">
              <Loader2 className="w-8 h-8 animate-spin text-primary-600 dark:text-primary-200" />
              <span className="ml-3 text-neutral-600 dark:text-neutral-300">Loading wallets...</span>
            </div>
          ) : filteredWallets.length > 0 ? (
            <div className="overflow-x-auto">
              <table className="w-full">
                <thead className="bg-neutral-50 dark:bg-primary-950 border-b border-neutral-200 dark:border-primary-800">
                  <tr>
                    <th className="px-6 py-3 text-left text-sm font-semibold text-neutral-600 dark:text-neutral-300">Holder</th>
                    <th className="px-6 py-3 text-left text-sm font-semibold text-neutral-600 dark:text-neutral-300">Wallet</th>
                    <th className="px-6 py-3 text-left text-sm font-semibold text-neutral-600 dark:text-neutral-300">Balance</th>
                    <th className="px-6 py-3 text-left text-sm font-semibold text-neutral-600 dark:text-neutral-300">Usage</th>
                    <th className="px-6 py-3 text-left text-sm font-semibold text-neutral-600 dark:text-neutral-300">Status</th>
                    <th className="px-6 py-3 text-left text-sm font-semibold text-neutral-600 dark:text-neutral-300">Activity</th>
                    <th className="px-6 py-3 text-left text-sm font-semibold text-neutral-600 dark:text-neutral-300">Actions</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-neutral-100 dark:divide-primary-800/60">
                  {filteredWallets.map(wallet => (
                    <WalletRow key={wallet.id} wallet={wallet} onView={() => handleViewWallet(wallet)} onEdit={() => handleEditWallet(wallet)} onAction={(action) => handleWalletAction(action, wallet)} />
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <div className="p-8">
              <EmptyState icon={<CreditCard className="w-8 h-8" />} title="No wallets found" description="Issue a new wallet or adjust your filters" action={<Button onClick={() => setShowIssueModal(true)}>Issue Wallet</Button>} />
            </div>
          )}
          
          {totalPages > 1 && (
            <div className="p-4 border-t border-neutral-200 dark:border-primary-800 flex items-center justify-between">
              <p className="text-sm text-neutral-600 dark:text-neutral-300">Showing {currentPage * 20 + 1} to {Math.min((currentPage + 1) * 20, totalWallets)} of {totalWallets}</p>
              <div className="flex gap-2">
                <Button variant="outline" size="sm" disabled={currentPage === 0} onClick={() => setCurrentPage(p => p - 1)}>Previous</Button>
                <Button variant="outline" size="sm" disabled={currentPage >= totalPages - 1} onClick={() => setCurrentPage(p => p + 1)}>Next</Button>
              </div>
            </div>
          )}
        </Card>
      )}

      {/* ================================================================== */}
      {/* MODALS */}
      {/* ================================================================== */}

      {/* Issue Wallet Modal */}
      <Modal isOpen={showIssueModal} onClose={() => setShowIssueModal(false)} title="Issue New Wallet" subtitle="Create a wallet for a beneficiary" size="md"
        footer={<><Button variant="outline" onClick={() => setShowIssueModal(false)}>Cancel</Button><Button onClick={handleIssueWallet} disabled={actionLoading}>{actionLoading ? <Loader2 className="w-4 h-4 animate-spin mr-2" /> : null}Issue Wallet</Button></>}>
        <div className="space-y-4">
          <div>
            <label className="field-label block mb-1">Select Program *</label>
            <select className="w-full border border-neutral-300 dark:border-primary-700 rounded-lg px-3 py-2" value={issueForm.programId} onChange={(e) => setIssueForm({ ...issueForm, programId: e.target.value })}>
              <option value="">Choose a wallet program...</option>
              {programs.map(p => <option key={p.id} value={p.id}>{p.programName} ({p.programCode})</option>)}
            </select>
          </div>
          <div>
            <label className="field-label block mb-1">Wallet Type</label>
            <select className="w-full border border-neutral-300 dark:border-primary-700 rounded-lg px-3 py-2" value={issueForm.walletType} onChange={(e) => setIssueForm({ ...issueForm, walletType: e.target.value })}>
              <option value="CONSUMER">Consumer</option><option value="MERCHANT">Merchant</option><option value="AGENT">Agent</option><option value="CORPORATE">Corporate</option>
            </select>
          </div>
          <Input label="Holder Name *" placeholder="Enter beneficiary name" value={issueForm.holderName} onChange={(e) => setIssueForm({ ...issueForm, holderName: e.target.value })} />
          <Input label="Mobile Number *" placeholder="+971 5XX XXX XXXX" value={issueForm.holderMobile} onChange={(e) => setIssueForm({ ...issueForm, holderMobile: e.target.value })} />
          <Input label="Email (Optional)" placeholder="email@example.com" value={issueForm.holderEmail} onChange={(e) => setIssueForm({ ...issueForm, holderEmail: e.target.value })} />
          <div className="grid grid-cols-2 gap-4">
            <Input label="Initial Load" placeholder="0.00" type="number" value={issueForm.initialLoadAmount?.toString() || ''} onChange={(e) => setIssueForm({ ...issueForm, initialLoadAmount: parseFloat(e.target.value) || undefined })} />
            <Input label="Daily Limit" placeholder="Program default" type="number" value={issueForm.dailyLimit?.toString() || ''} onChange={(e) => setIssueForm({ ...issueForm, dailyLimit: parseFloat(e.target.value) || undefined })} />
          </div>
          <Alert variant="info">KYC verification will be required for the wallet holder before full transaction limits apply.</Alert>
        </div>
      </Modal>

      {/* Edit Wallet Modal */}
      <Modal isOpen={showEditWalletModal} onClose={() => setShowEditWalletModal(false)} title="Edit Wallet" subtitle={selectedWallet?.walletReference} size="md"
        footer={<><Button variant="outline" onClick={() => setShowEditWalletModal(false)}>Cancel</Button><Button onClick={handleUpdateWallet} disabled={actionLoading}>{actionLoading ? <Loader2 className="w-4 h-4 animate-spin mr-2" /> : null}Save Changes</Button></>}>
        <div className="space-y-4">
          <Input label="Wallet Name" value={editWalletForm.walletName || ''} onChange={(e) => setEditWalletForm({ ...editWalletForm, walletName: e.target.value })} />
          <div className="grid grid-cols-2 gap-4">
            <Input label="Daily Limit" type="number" value={editWalletForm.dailyLimit?.toString() || ''} onChange={(e) => setEditWalletForm({ ...editWalletForm, dailyLimit: parseFloat(e.target.value) || undefined })} />
            <Input label="Monthly Limit" type="number" value={editWalletForm.monthlyLimit?.toString() || ''} onChange={(e) => setEditWalletForm({ ...editWalletForm, monthlyLimit: parseFloat(e.target.value) || undefined })} />
          </div>
          <Input label="Per Transaction Limit" type="number" value={editWalletForm.perTransactionLimit?.toString() || ''} onChange={(e) => setEditWalletForm({ ...editWalletForm, perTransactionLimit: parseFloat(e.target.value) || undefined })} />
        </div>
      </Modal>

      {/* Create Program Modal */}
      <Modal isOpen={showCreateProgramModal} onClose={() => setShowCreateProgramModal(false)} title="Create Wallet Program" subtitle="Configure a new BaaS wallet program" size="lg"
        footer={<><Button variant="outline" onClick={() => setShowCreateProgramModal(false)}>Cancel</Button><Button onClick={handleCreateProgram} disabled={actionLoading}>{actionLoading ? <Loader2 className="w-4 h-4 animate-spin mr-2" /> : null}Create Program</Button></>}>
        <div className="space-y-4">
          <div className="grid grid-cols-2 gap-4">
            <Input label="Program Code *" placeholder="BAAS-PARTNER-001" value={createProgramForm.programCode} onChange={(e) => setCreateProgramForm({ ...createProgramForm, programCode: e.target.value })} />
            <Input label="Program Name *" placeholder="Partner Consumer Wallets" value={createProgramForm.programName} onChange={(e) => setCreateProgramForm({ ...createProgramForm, programName: e.target.value })} />
          </div>
          <Input label="Description" placeholder="Describe the wallet program..." value={createProgramForm.description} onChange={(e) => setCreateProgramForm({ ...createProgramForm, description: e.target.value })} />
          <div className="grid grid-cols-2 gap-4">
            <Input label="Corporate ID *" placeholder="Partner corporate ID" value={createProgramForm.corporateId} onChange={(e) => setCreateProgramForm({ ...createProgramForm, corporateId: e.target.value })} />
            <Input label="Physical Account ID *" placeholder="Omnibus account ID" value={createProgramForm.physicalAccountId} onChange={(e) => setCreateProgramForm({ ...createProgramForm, physicalAccountId: e.target.value })} />
          </div>
          <div className="grid grid-cols-3 gap-4">
            <Input label="Daily Limit" type="number" value={createProgramForm.dailySpendLimit?.toString() || ''} onChange={(e) => setCreateProgramForm({ ...createProgramForm, dailySpendLimit: parseFloat(e.target.value) || undefined })} />
            <Input label="Monthly Limit" type="number" value={createProgramForm.monthlySpendLimit?.toString() || ''} onChange={(e) => setCreateProgramForm({ ...createProgramForm, monthlySpendLimit: parseFloat(e.target.value) || undefined })} />
            <Input label="Max Balance" type="number" value={createProgramForm.maxBalance?.toString() || ''} onChange={(e) => setCreateProgramForm({ ...createProgramForm, maxBalance: parseFloat(e.target.value) || undefined })} />
          </div>
          <div className="grid grid-cols-2 gap-4">
            <Input label="Min Topup" type="number" value={createProgramForm.minTopup?.toString() || ''} onChange={(e) => setCreateProgramForm({ ...createProgramForm, minTopup: parseFloat(e.target.value) || undefined })} />
            <Input label="Max Topup" type="number" value={createProgramForm.maxTopup?.toString() || ''} onChange={(e) => setCreateProgramForm({ ...createProgramForm, maxTopup: parseFloat(e.target.value) || undefined })} />
          </div>
          <div className="flex items-center gap-4">
            <label className="flex items-center gap-2"><input type="checkbox" checked={createProgramForm.kycRequired} onChange={(e) => setCreateProgramForm({ ...createProgramForm, kycRequired: e.target.checked })} className="rounded border-neutral-300 dark:border-primary-700" /><span className="text-sm">KYC Required</span></label>
            <Input label="Expiry Days" type="number" className="w-32" value={createProgramForm.expiryDays?.toString() || ''} onChange={(e) => setCreateProgramForm({ ...createProgramForm, expiryDays: parseInt(e.target.value) || undefined })} />
          </div>
        </div>
      </Modal>

      {/* Edit Program Modal */}
      <Modal isOpen={showEditProgramModal} onClose={() => setShowEditProgramModal(false)} title="Edit Program" subtitle={selectedProgram?.programCode} size="md"
        footer={<><Button variant="outline" onClick={() => setShowEditProgramModal(false)}>Cancel</Button><Button onClick={handleUpdateProgram} disabled={actionLoading}>{actionLoading ? <Loader2 className="w-4 h-4 animate-spin mr-2" /> : null}Save Changes</Button></>}>
        <div className="space-y-4">
          <Input label="Program Name" value={editProgramForm.programName || ''} onChange={(e) => setEditProgramForm({ ...editProgramForm, programName: e.target.value })} />
          <div className="grid grid-cols-2 gap-4">
            <Input label="Daily Limit" type="number" value={editProgramForm.dailySpendLimit?.toString() || ''} onChange={(e) => setEditProgramForm({ ...editProgramForm, dailySpendLimit: parseFloat(e.target.value) || undefined })} />
            <Input label="Monthly Limit" type="number" value={editProgramForm.monthlySpendLimit?.toString() || ''} onChange={(e) => setEditProgramForm({ ...editProgramForm, monthlySpendLimit: parseFloat(e.target.value) || undefined })} />
          </div>
          <Input label="Max Balance" type="number" value={editProgramForm.maxBalance?.toString() || ''} onChange={(e) => setEditProgramForm({ ...editProgramForm, maxBalance: parseFloat(e.target.value) || undefined })} />
          <label className="flex items-center gap-2"><input type="checkbox" checked={editProgramForm.kycRequired} onChange={(e) => setEditProgramForm({ ...editProgramForm, kycRequired: e.target.checked })} className="rounded border-neutral-300 dark:border-primary-700" /><span className="text-sm">KYC Required</span></label>
        </div>
      </Modal>

      {/* Load Funds Modal */}
      <Modal isOpen={showLoadModal} onClose={() => setShowLoadModal(false)} title="Load Funds" subtitle={selectedWallet ? `Loading to ${selectedWallet.walletReference}` : ''} size="sm"
        footer={<><Button variant="outline" onClick={() => setShowLoadModal(false)}>Cancel</Button><Button onClick={handleLoadFunds} disabled={actionLoading}>{actionLoading ? <Loader2 className="w-4 h-4 animate-spin mr-2" /> : null}Load Funds</Button></>}>
        <div className="space-y-4">
          <Input label="Amount *" placeholder="0.00" type="number" value={loadForm.amount} onChange={(e) => setLoadForm({ ...loadForm, amount: e.target.value })} />
          <div><label className="field-label block mb-1">Source</label>
            <select className="w-full border border-neutral-300 dark:border-primary-700 rounded-lg px-3 py-2" value={loadForm.source} onChange={(e) => setLoadForm({ ...loadForm, source: e.target.value })}>
              <option value="BANK_TRANSFER">Bank Transfer</option><option value="CARD">Card</option><option value="CASH">Cash</option><option value="VIBAN">VIBAN</option><option value="INTERNAL">Internal</option>
            </select>
          </div>
          <Input label="Description" placeholder="Enter description" value={loadForm.description} onChange={(e) => setLoadForm({ ...loadForm, description: e.target.value })} />
        </div>
      </Modal>

      {/* Withdraw Modal */}
      <Modal isOpen={showWithdrawModal} onClose={() => setShowWithdrawModal(false)} title="Withdraw Funds" subtitle={selectedWallet ? `From ${selectedWallet.walletReference}` : ''} size="sm"
        footer={<><Button variant="outline" onClick={() => setShowWithdrawModal(false)}>Cancel</Button><Button onClick={handleWithdraw} disabled={actionLoading}>{actionLoading ? <Loader2 className="w-4 h-4 animate-spin mr-2" /> : null}Withdraw</Button></>}>
        <div className="space-y-4">
          <Input label="Amount *" placeholder="0.00" type="number" value={withdrawForm.amount} onChange={(e) => setWithdrawForm({ ...withdrawForm, amount: e.target.value })} />
          <div><label className="field-label block mb-1">Destination</label>
            <select className="w-full border border-neutral-300 dark:border-primary-700 rounded-lg px-3 py-2" value={withdrawForm.destination} onChange={(e) => setWithdrawForm({ ...withdrawForm, destination: e.target.value })}>
              <option value="BANK_TRANSFER">Bank Transfer</option><option value="CASH">Cash</option><option value="INTERNAL">Internal</option>
            </select>
          </div>
          <Input label="Description" placeholder="Enter description" value={withdrawForm.description} onChange={(e) => setWithdrawForm({ ...withdrawForm, description: e.target.value })} />
          {selectedWallet && <p className="text-sm text-neutral-500 dark:text-neutral-400">Available balance: {formatCurrency(selectedWallet.availableBalance)}</p>}
        </div>
      </Modal>

      {/* Transfer Modal */}
      <Modal isOpen={showTransferModal} onClose={() => setShowTransferModal(false)} title="Transfer Funds" subtitle={selectedWallet ? `From ${selectedWallet.walletReference}` : ''} size="sm"
        footer={<><Button variant="outline" onClick={() => setShowTransferModal(false)}>Cancel</Button><Button onClick={handleTransfer} disabled={actionLoading}>{actionLoading ? <Loader2 className="w-4 h-4 animate-spin mr-2" /> : null}Send</Button></>}>
        <div className="space-y-4">
          <Input label="Destination Wallet *" placeholder="WAL-XXXX-XXXXXXXX" value={transferForm.toWalletReference} onChange={(e) => setTransferForm({ ...transferForm, toWalletReference: e.target.value })} />
          <Input label="Amount *" placeholder="0.00" type="number" value={transferForm.amount} onChange={(e) => setTransferForm({ ...transferForm, amount: e.target.value })} />
          <Input label="Description" placeholder="Enter description" value={transferForm.description} onChange={(e) => setTransferForm({ ...transferForm, description: e.target.value })} />
          {selectedWallet && <p className="text-sm text-neutral-500 dark:text-neutral-400">Available balance: {formatCurrency(selectedWallet.availableBalance)}</p>}
        </div>
      </Modal>

      {/* Bulk Load Modal */}
      <Modal isOpen={showBulkLoadModal} onClose={() => setShowBulkLoadModal(false)} title="Bulk Load Funds" subtitle="Load funds to multiple wallets at once" size="md"
        footer={<><Button variant="outline" onClick={() => setShowBulkLoadModal(false)}>Cancel</Button><Button onClick={handleBulkLoad} disabled={actionLoading}>{actionLoading ? <Loader2 className="w-4 h-4 animate-spin mr-2" /> : null}Process Bulk Load</Button></>}>
        <div className="space-y-4">
          <div><label className="field-label block mb-1">Program (Optional)</label>
            <select className="w-full border border-neutral-300 dark:border-primary-700 rounded-lg px-3 py-2" value={bulkLoadForm.programId} onChange={(e) => setBulkLoadForm({ ...bulkLoadForm, programId: e.target.value })}>
              <option value="">All Programs</option>
              {programs.map(p => <option key={p.id} value={p.id}>{p.programName}</option>)}
            </select>
          </div>
          <div><label className="field-label block mb-1">Wallet Data (CSV Format) *</label>
            <textarea className="w-full border border-neutral-300 dark:border-primary-700 rounded-lg px-3 py-2 h-40 font-mono text-sm" placeholder="walletReference,amount&#10;WAL-FINTA-00012345,100&#10;WAL-FINTA-00012346,250" value={bulkLoadForm.csvData} onChange={(e) => setBulkLoadForm({ ...bulkLoadForm, csvData: e.target.value })} />
            <p className="text-xs text-neutral-500 dark:text-neutral-400 mt-1">One entry per line. Format: walletReference,amount</p>
          </div>
          <Input label="Description" placeholder="Bulk load description" value={bulkLoadForm.description} onChange={(e) => setBulkLoadForm({ ...bulkLoadForm, description: e.target.value })} />
          <Alert variant="info">Failed entries will be skipped and reported in the results.</Alert>
        </div>
      </Modal>

      {/* KYC Verify Modal */}
      <Modal isOpen={showKycVerifyModal} onClose={() => setShowKycVerifyModal(false)} title="Verify KYC" subtitle={selectedWallet ? `For ${selectedWallet.holderName}` : ''} size="md"
        footer={<><Button variant="outline" onClick={() => setShowKycVerifyModal(false)}>Cancel</Button><Button onClick={handleVerifyKyc} disabled={actionLoading}>{actionLoading ? <Loader2 className="w-4 h-4 animate-spin mr-2" /> : null}Verify KYC</Button></>}>
        <div className="space-y-4">
          <div><label className="field-label block mb-1">Verification Method</label>
            <select className="w-full border border-neutral-300 dark:border-primary-700 rounded-lg px-3 py-2" value={kycVerifyForm.verificationMethod} onChange={(e) => setKycVerifyForm({ ...kycVerifyForm, verificationMethod: e.target.value })}>
              <option value="DOCUMENT">Document Verification</option><option value="BIOMETRIC">Biometric</option><option value="MANUAL">Manual Review</option>
            </select>
          </div>
          <div><label className="field-label block mb-1">Document Type</label>
            <select className="w-full border border-neutral-300 dark:border-primary-700 rounded-lg px-3 py-2" value={kycVerifyForm.documentType} onChange={(e) => setKycVerifyForm({ ...kycVerifyForm, documentType: e.target.value })}>
              <option value="EMIRATES_ID">Emirates ID</option><option value="PASSPORT">Passport</option><option value="DRIVING_LICENSE">Driving License</option>
            </select>
          </div>
          <Input label="Document Number" placeholder="784-XXXX-XXXXXXX-X" value={kycVerifyForm.documentNumber} onChange={(e) => setKycVerifyForm({ ...kycVerifyForm, documentNumber: e.target.value })} />
          <Input label="Document Expiry Date" type="date" value={kycVerifyForm.documentExpiryDate} onChange={(e) => setKycVerifyForm({ ...kycVerifyForm, documentExpiryDate: e.target.value })} />
          <Input label="Verified By" placeholder="Verifier name or ID" value={kycVerifyForm.verifiedBy} onChange={(e) => setKycVerifyForm({ ...kycVerifyForm, verifiedBy: e.target.value })} />
        </div>
      </Modal>

      {/* Block Wallet Modal */}
      <Modal isOpen={showBlockModal} onClose={() => setShowBlockModal(false)} title="Block Wallet" subtitle={selectedWallet?.walletReference} size="sm"
        footer={<><Button variant="outline" onClick={() => setShowBlockModal(false)}>Cancel</Button><Button variant="danger" onClick={handleBlockWallet} disabled={actionLoading}>{actionLoading ? <Loader2 className="w-4 h-4 animate-spin mr-2" /> : null}Block Wallet</Button></>}>
        <div className="space-y-4">
          <Alert variant="warning">Blocking a wallet will prevent all transactions. This action requires manual review to reverse.</Alert>
          <Input label="Reason *" placeholder="Enter reason for blocking" value={blockForm.reason} onChange={(e) => setBlockForm({ ...blockForm, reason: e.target.value })} />
          <label className="flex items-center gap-2"><input type="checkbox" checked={blockForm.permanent} onChange={(e) => setBlockForm({ ...blockForm, permanent: e.target.checked })} className="rounded border-neutral-300 dark:border-primary-700" /><span className="text-sm text-error-600 dark:text-error-300">Permanent block (cannot be reversed)</span></label>
        </div>
      </Modal>

      {/* Wallet Detail Modal */}
      <Modal isOpen={showDetailModal} onClose={() => { setShowDetailModal(false); setWalletDetail(null); }} title="Wallet Details" subtitle={selectedWallet?.walletReference} size="lg"
        footer={<div className="flex gap-2">
          <Button variant="outline" onClick={() => { setShowDetailModal(false); setWalletDetail(null); }}>Close</Button>
          {selectedWallet && <><Button variant="outline" leftIcon={<ArrowDownRight className="w-4 h-4" />} onClick={() => { setShowDetailModal(false); setShowLoadModal(true); }}>Load</Button>
          <Button variant="outline" leftIcon={<Send className="w-4 h-4" />} onClick={() => { setShowDetailModal(false); setShowTransferModal(true); }}>Transfer</Button></>}
        </div>}>
        {walletDetail ? (
          <div className="space-y-6">
            <div className="flex items-center gap-4">
              <Avatar name={walletDetail.holderName} size="lg" status={walletDetail.kycVerified ? 'online' : 'away'} />
              <div className="flex-1">
                <h3 className="section-title">{walletDetail.holderName}</h3>
                <p className="text-sm text-neutral-500 dark:text-neutral-400">{walletDetail.holderMobile}</p>
                {walletDetail.holderEmail && <p className="text-sm text-neutral-500 dark:text-neutral-400">{walletDetail.holderEmail}</p>}
                {walletDetail.viban && <div className="flex items-center gap-1 mt-1"><span className="text-xs text-neutral-400 dark:text-neutral-500">VIBAN:</span><span className="text-xs font-mono text-primary-600 dark:text-primary-200">{walletDetail.viban}</span><button onClick={() => navigator.clipboard.writeText(walletDetail.viban || '')} className="p-0.5 hover:bg-neutral-100 dark:hover:bg-primary-800 rounded"><Copy className="w-3 h-3 text-neutral-400 dark:text-neutral-500" /></button></div>}
              </div>
              <div className="flex flex-col items-end gap-2">
                <Badge variant={walletStatusConfig[walletDetail.status]?.color as any}>{walletDetail.status}</Badge>
                <Badge variant={(kycStatusConfig[walletDetail.kycStatus] || kycStatusConfig.PENDING).color as any} size="sm">KYC: {walletDetail.kycStatus}</Badge>
              </div>
            </div>
            
            <div className="grid grid-cols-3 gap-4">
              <Card padding="sm" className="bg-primary-50 dark:bg-primary-800/40"><p className="text-xs text-neutral-500 dark:text-neutral-400">Current Balance</p><p className="section-title">{formatCurrency(walletDetail.currentBalance)}</p></Card>
              {/* Phase 12 Task E: .stat-value-xs replaces the raw `text-xl font-semibold` hand-rolls. */}
              <Card padding="sm" className="bg-success-50 dark:bg-success-500/10"><p className="text-xs text-neutral-500 dark:text-neutral-400">Available Balance</p><p className="stat-value-xs text-success-600 dark:text-success-300">{formatCurrency(walletDetail.availableBalance)}</p></Card>
              <Card padding="sm" className="bg-neutral-50 dark:bg-primary-950"><p className="text-xs text-neutral-500 dark:text-neutral-400">Monthly Spent</p><p className="stat-value-xs text-neutral-700 dark:text-neutral-200">{formatCurrency(walletDetail.monthlySpent || 0)}</p></Card>
            </div>
            
            <div>
              <h4 className="font-medium text-primary-900 dark:text-neutral-50 mb-3">Spending Limits</h4>
              <div className="space-y-3">
                <div><div className="flex justify-between text-sm mb-1"><span className="text-neutral-500 dark:text-neutral-400">Daily Limit</span><span>{formatCurrency(walletDetail.dailySpent)} / {formatCurrency(walletDetail.dailyLimit)}</span></div><ProgressBar value={(walletDetail.dailySpent / walletDetail.dailyLimit) * 100} size="sm" /></div>
                <div><div className="flex justify-between text-sm mb-1"><span className="text-neutral-500 dark:text-neutral-400">Monthly Limit</span><span>{formatCurrency(walletDetail.monthlySpent || 0)} / {formatCurrency(walletDetail.monthlyLimit)}</span></div><ProgressBar value={((walletDetail.monthlySpent || 0) / walletDetail.monthlyLimit) * 100} size="sm" /></div>
                {walletDetail.perTransactionLimit && <div className="flex justify-between text-sm"><span className="text-neutral-500 dark:text-neutral-400">Per Transaction Limit</span><span>{formatCurrency(walletDetail.perTransactionLimit)}</span></div>}
                {walletDetail.maxBalance && <div className="flex justify-between text-sm"><span className="text-neutral-500 dark:text-neutral-400">Max Balance</span><span>{formatCurrency(walletDetail.maxBalance)}</span></div>}
              </div>
            </div>
            
            {walletDetail.recentTransactions && walletDetail.recentTransactions.length > 0 && (
              <div>
                <h4 className="font-medium text-primary-900 dark:text-neutral-50 mb-3">Recent Transactions</h4>
                <div className="space-y-2 max-h-60 overflow-y-auto">
                  {walletDetail.recentTransactions.map(txn => (
                    <div key={txn.id} className="flex items-center justify-between p-3 bg-neutral-50 dark:bg-primary-950 rounded-lg">
                      <div className="flex items-center gap-3">
                        {txn.type.includes('CREDIT') || txn.type.includes('TOPUP') || txn.type.includes('IN') ? <ArrowDownRight className="w-5 h-5 text-success-600 dark:text-success-300" /> : <ArrowUpRight className="w-5 h-5 text-error-600 dark:text-error-300" />}
                        <div><p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{txn.description || txn.type}</p><p className="text-xs text-neutral-500 dark:text-neutral-400">{new Date(txn.transactionDate).toLocaleString()}</p></div>
                      </div>
                      <div className="text-right">
                        <p className={cn("font-semibold", txn.type.includes('CREDIT') || txn.type.includes('TOPUP') || txn.type.includes('IN') ? 'text-success-600 dark:text-success-300' : 'text-error-600 dark:text-error-300')}>
                          {txn.type.includes('CREDIT') || txn.type.includes('TOPUP') || txn.type.includes('IN') ? '+' : '-'}{formatCurrency(txn.amount)}
                        </p>
                        <p className="text-xs text-neutral-400 dark:text-neutral-500">{txn.referenceNumber}</p>
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )}
            
            <div className="grid grid-cols-2 gap-4 text-sm">
              <div><span className="text-neutral-500 dark:text-neutral-400">Program:</span><span className="ml-2 text-primary-900 dark:text-neutral-50">{walletDetail.programName}</span></div>
              <div><span className="text-neutral-500 dark:text-neutral-400">Created:</span><span className="ml-2 text-primary-900 dark:text-neutral-50">{new Date(walletDetail.createdAt).toLocaleDateString()}</span></div>
              {walletDetail.expiresAt && <div><span className="text-neutral-500 dark:text-neutral-400">Expires:</span><span className="ml-2 text-primary-900 dark:text-neutral-50">{new Date(walletDetail.expiresAt).toLocaleDateString()}</span></div>}
              {walletDetail.hierarchyPath && <div className="col-span-2"><span className="text-neutral-500 dark:text-neutral-400">Hierarchy:</span><span className="ml-2 text-xs font-mono text-neutral-600 dark:text-neutral-300">{walletDetail.hierarchyPath}</span></div>}
            </div>
          </div>
        ) : (
          <div className="flex items-center justify-center py-8"><Loader2 className="w-6 h-6 animate-spin text-primary-600 dark:text-primary-200" /><span className="ml-2 text-neutral-600 dark:text-neutral-300">Loading details...</span></div>
        )}
      </Modal>
    </div>
  );
};

export default WalletPage;