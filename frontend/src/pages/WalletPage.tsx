import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  Wallet, Plus, Search, Filter, CreditCard, ArrowUpRight, ArrowDownRight,
  Users, TrendingUp, MoreHorizontal, Eye, Lock, Unlock, Ban, RefreshCw,
  Send, Download, Settings, Loader2, AlertCircle, CheckCircle, Building2,
  Shield, Edit, Upload, FileText, UserCheck, XCircle, LayoutDashboard,
  Banknote, PieChart, Activity, Clock, ChevronDown, ChevronUp, Copy,
  ExternalLink, User, Phone, Mail, MapPin, GitBranch, X, ChevronLeft
} from 'lucide-react';
import { Card, Button, Badge, Input, EmptyState, Skeleton , StatusIconBadge } from '../components/ui';
import { Modal, Tabs, ProgressBar, Avatar, Alert } from '../components/ui/enhanced';
import { formatCurrency, formatDate, cn } from '../utils';
import { HierarchyPicker } from '../components/hierarchy/HierarchyPicker';
import { Page } from '../components/layout/Page';

// ============================================================================
// API Service - Enhanced with Party and Hierarchy support
// ============================================================================

const API_BASE = '/api/v1/wallets';
const PARTY_API_BASE = '/api/v1/parties';

interface ApiResponse<T> {
  success: boolean;
  data: T;
  message?: string;
}

const fetchApi = async <T,>(endpoint: string, options?: RequestInit, base = API_BASE): Promise<T> => {
  const { headers: optHeaders, ...restOptions } = options || {};
  const response = await fetch(`${base}${endpoint}`, {
    ...restOptions,
    headers: { 'Content-Type': 'application/json', ...optHeaders },
  });
  if (!response.ok) {
    const errorData = await response.json().catch(() => ({}));
    throw new Error(errorData.message || `API error: ${response.status}`);
  }
  const result = await response.json();
  // Handle both ApiResponse wrapper { success, data } and direct response
  return result.data !== undefined ? result.data : result;
};

// Party API
const partyApi = {
  search: async (query: string, partyType?: string): Promise<PartySearchResponse> => {
    const params = new URLSearchParams({ query, size: '10' });
    if (partyType) params.set('partyType', partyType);
    try {
      const response = await fetchApi<any>(`/search?${params}`, {}, PARTY_API_BASE);
      // Handle backend response format: { parties: [...], totalCount: X } or { content: [...], totalElements: X }
      const parties = response.parties || response.content || [];
      const total = response.totalCount || response.totalElements || parties.length;
      return { content: parties, totalElements: total, page: response.page || 0, pageSize: response.pageSize || 10 };
    } catch {
      // Fallback to demo data
      const filtered = demoParties.filter(p =>
        p.legalName.toLowerCase().includes(query.toLowerCase()) ||
        p.contactPhone?.includes(query) ||
        p.contactEmail?.toLowerCase().includes(query.toLowerCase())
      );
      return { content: filtered, totalElements: filtered.length, page: 0, pageSize: 10 };
    }
  },
  create: async (data: CreatePartyRequest): Promise<Party> => {
    return await fetchApi<Party>('', { method: 'POST', body: JSON.stringify(data) }, PARTY_API_BASE);
  },
};

const walletsApi = {
  // Stats
  getStats: (corporateId?: string) => {
    const headers: Record<string, string> = {};
    if (corporateId) headers['X-Corporate-Id'] = corporateId;
    return fetchApi<WalletStats>('/stats', { headers });
  },
  
  // Programs - Read only (creation moved to Programs page)
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
  
  // Wallets - Full CRUD
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
  issueWallet: (data: IssueWalletRequest) => 
    fetchApi<WalletAccount>('', { method: 'POST', body: JSON.stringify(data) }),
  updateWallet: (id: string, data: UpdateWalletRequest) =>
    fetchApi<WalletAccount>(`/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
  
  // Operations
  loadFunds: (walletId: string, data: LoadFundsRequest) =>
    fetchApi<LoadFundsResponse>(`/${walletId}/load`, { method: 'POST', body: JSON.stringify(data) }),
  withdrawFunds: (walletId: string, data: WithdrawRequest) =>
    fetchApi<WithdrawResponse>(`/${walletId}/withdraw`, { method: 'POST', body: JSON.stringify(data) }),
  transferFunds: (walletId: string, data: TransferRequest) =>
    fetchApi<TransferResponse>(`/${walletId}/transfer`, { method: 'POST', body: JSON.stringify(data) }),
  bulkLoadFunds: (data: BulkLoadRequest) =>
    fetchApi<BulkLoadResponse>('/bulk-load', { method: 'POST', body: JSON.stringify(data) }),
  
  // Status
  suspendWallet: (walletId: string, reason?: string) =>
    fetchApi<WalletAccount>(`/${walletId}/suspend`, { method: 'POST', body: JSON.stringify({ reason }) }),
  reactivateWallet: (walletId: string, reason?: string) =>
    fetchApi<WalletAccount>(`/${walletId}/reactivate`, { method: 'POST', body: JSON.stringify({ reason }) }),
  blockWallet: (walletId: string, reason: string, permanent?: boolean) =>
    fetchApi<WalletAccount>(`/${walletId}/block`, { method: 'POST', body: JSON.stringify({ reason, permanent }) }),
  
  // KYC
  verifyKyc: (walletId: string, data: VerifyKycRequest) =>
    fetchApi<KycVerificationResponse>(`/${walletId}/verify-kyc`, { method: 'POST', body: JSON.stringify(data) }),
  
  // Transactions
  getWalletTransactions: (walletId: string, page = 0, size = 20) =>
    fetchApi<WalletTransaction[]>(`/${walletId}/transactions?page=${page}&size=${size}`),
};

// ============================================================================
// Types - Enhanced with Party and Hierarchy
// ============================================================================

interface Party {
  id: string;
  partyCode: string;
  partyType: 'INDIVIDUAL' | 'CORPORATE' | 'EMPLOYEE' | 'VENDOR' | 'CUSTOMER';
  legalName: string;
  displayName?: string;
  tradeName?: string;
  contactEmail?: string;
  contactPhone?: string;
  city?: string;
  country?: string;
  status: 'ACTIVE' | 'INACTIVE' | 'SUSPENDED' | 'BLOCKED';
  kycStatus?: 'VERIFIED' | 'PENDING' | 'REJECTED' | 'EXPIRED' | 'NOT_STARTED';
  emiratesId?: string;
  taxId?: string;
  createdAt: string;
}

interface PartySearchResponse {
  content: Party[];
  totalElements: number;
  page: number;
  pageSize: number;
}

interface CreatePartyRequest {
  partyType: 'INDIVIDUAL' | 'CORPORATE' | 'EMPLOYEE';
  legalName: string;
  displayName?: string;
  contactEmail?: string;
  contactPhone?: string;
  emiratesId?: string;
  taxId?: string;
  registrationCountry?: string;
  country?: string;
  city?: string;
  addressLine1?: string;
}

interface HierarchyNode {
  id: string;
  nodeCode: string;
  nodeName: string;
  levelNumber: number;
  materializedPath: string;
  parentId?: string;
}

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
  hierarchyNodeId?: string;
  hierarchyPath?: string;
  expiresAt?: string;
  recentTransactions: WalletTransaction[];
  party?: Party;
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

// Enhanced IssueWalletRequest with Party and Hierarchy
interface IssueWalletRequest {
  programId?: string;
  // Party linkage
  partyId?: string;
  createParty?: boolean;
  partyType?: string;
  // Holder info (for party creation or metadata)
  holderName: string;
  holderMobile: string;
  holderEmail?: string;
  emiratesId?: string;
  taxId?: string;
  city?: string;
  country?: string;
  // Hierarchy
  hierarchyNodeId?: string;
  // Wallet settings
  walletType?: string;
  initialLoadAmount?: number;
  dailyLimit?: number;
  monthlyLimit?: number;
  perTransactionLimit?: number;
  // Optional
  externalReference?: string;
  autoTriggerKyc?: boolean;
}

interface UpdateWalletRequest {
  walletName?: string;
  dailyLimit?: number;
  monthlyLimit?: number;
  perTransactionLimit?: number;
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
// Demo Data
// ============================================================================

const demoParties: Party[] = [
  { id: 'a0000001-0001-0001-0001-000000000001', partyCode: 'IND-001', partyType: 'INDIVIDUAL', legalName: 'Mohammed Al Rashid', contactPhone: '+971501234567', contactEmail: 'mohammed@email.com', city: 'Dubai', country: 'AE', status: 'ACTIVE', kycStatus: 'VERIFIED', emiratesId: '784-1990-1234567-1', createdAt: '2024-01-15T10:00:00Z' },
  { id: 'a0000001-0001-0001-0001-000000000002', partyCode: 'IND-002', partyType: 'INDIVIDUAL', legalName: 'Sarah Ahmed', contactPhone: '+971509876543', contactEmail: 'sarah@email.com', city: 'Abu Dhabi', country: 'AE', status: 'ACTIVE', kycStatus: 'VERIFIED', createdAt: '2024-01-20T14:00:00Z' },
  { id: 'a0000001-0001-0001-0001-000000000003', partyCode: 'CORP-001', partyType: 'CORPORATE', legalName: 'Tech Solutions LLC', tradeName: 'TechSol', contactPhone: '+97145551234', city: 'Dubai', country: 'AE', status: 'ACTIVE', kycStatus: 'VERIFIED', taxId: 'TRN-123456789', createdAt: '2024-02-01T09:00:00Z' },
  { id: 'a0000001-0001-0001-0001-000000000004', partyCode: 'IND-003', partyType: 'INDIVIDUAL', legalName: 'Fatima Al Ali', contactPhone: '+971507778899', city: 'Sharjah', country: 'AE', status: 'ACTIVE', kycStatus: 'PENDING', createdAt: '2024-02-05T11:00:00Z' },
  { id: 'a0000001-0001-0001-0001-000000000005', partyCode: 'EMP-001', partyType: 'EMPLOYEE', legalName: 'Omar Hassan', contactPhone: '+971501112233', contactEmail: 'omar@company.ae', city: 'Dubai', country: 'AE', status: 'ACTIVE', kycStatus: 'VERIFIED', createdAt: '2024-01-20T11:00:00Z' },
];

const demoPrograms: WalletProgram[] = [
  { id: 'c0000001-0001-0001-0001-000000000001', programCode: 'BAAS-FINTECH-A', programName: 'Fintech Partner A - Consumer Wallets', operatorName: 'Fintech A Technologies', corporateId: '550e8400-e29b-41d4-a716-446655440000', currency: 'AED', activeWallets: 12500, totalWallets: 13200, totalBalance: 45000000, dailySpendLimit: 5000, monthlySpendLimit: 25000, maxBalance: 100000, kycRequired: true, status: 'ACTIVE', launchDate: '2024-01-01' },
  { id: 'c0000001-0001-0001-0001-000000000002', programCode: 'BAAS-FINTECH-B', programName: 'Fintech Partner B - Merchant Wallets', operatorName: 'Fintech B Payments', corporateId: '550e8400-e29b-41d4-a716-446655440001', currency: 'AED', activeWallets: 3500, totalWallets: 3800, totalBalance: 125000000, dailySpendLimit: 50000, monthlySpendLimit: 500000, maxBalance: 1000000, kycRequired: true, status: 'ACTIVE', launchDate: '2023-06-15' },
  { id: 'c0000001-0001-0001-0001-000000000003', programCode: 'WP-CORP-GIFT', programName: 'Corporate Gift Cards', operatorName: 'Emirates Group', corporateId: '550e8400-e29b-41d4-a716-446655440002', currency: 'AED', activeWallets: 1250, totalWallets: 1320, totalBalance: 4500000, dailySpendLimit: 5000, monthlySpendLimit: 25000, status: 'ACTIVE', launchDate: '2024-01-01' },
];

const demoWallets: WalletAccount[] = [
  { id: 'b0000001-0001-0001-0001-000000000001', walletReference: 'WAL-FINTA-00012345', walletName: 'Mohammed Al Rashid', holderName: 'Mohammed Al Rashid', holderMobile: '+971501234567', partyId: 'a0000001-0001-0001-0001-000000000001', programId: 'c0000001-0001-0001-0001-000000000001', programName: 'Fintech Partner A - Consumer Wallets', programCode: 'BAAS-FINTECH-A', currentBalance: 25000, availableBalance: 25000, currency: 'AED', dailySpent: 500, monthlySpent: 3500, dailyLimit: 5000, monthlyLimit: 25000, status: 'ACTIVE', lastTransaction: '2024-02-12T14:30:00Z', transactionCount: 145, kycVerified: true, kycStatus: 'VERIFIED', createdAt: '2024-01-15T10:00:00Z' },
  { id: 'b0000001-0001-0001-0001-000000000002', walletReference: 'WAL-FINTA-00012346', walletName: 'Sarah Ahmed', holderName: 'Sarah Ahmed', holderMobile: '+971509876543', partyId: 'a0000001-0001-0001-0001-000000000002', programId: 'c0000001-0001-0001-0001-000000000001', programName: 'Fintech Partner A - Consumer Wallets', programCode: 'BAAS-FINTECH-A', currentBalance: 12000, availableBalance: 12000, currency: 'AED', dailySpent: 0, monthlySpent: 1800, dailyLimit: 5000, monthlyLimit: 25000, status: 'ACTIVE', lastTransaction: '2024-02-11T09:15:00Z', transactionCount: 23, kycVerified: true, kycStatus: 'VERIFIED', createdAt: '2024-01-20T14:00:00Z' },
  { id: 'b0000001-0001-0001-0001-000000000003', walletReference: 'WAL-FINTB-00005001', walletName: 'Tech Solutions LLC', holderName: 'Tech Solutions LLC', holderMobile: '+971505551234', partyId: 'a0000001-0001-0001-0001-000000000003', programId: 'c0000001-0001-0001-0001-000000000002', programName: 'Fintech Partner B - Merchant Wallets', programCode: 'BAAS-FINTECH-B', currentBalance: 850000, availableBalance: 850000, currency: 'AED', dailySpent: 15000, monthlySpent: 95000, dailyLimit: 50000, monthlyLimit: 500000, status: 'ACTIVE', lastTransaction: '2024-02-10T16:45:00Z', transactionCount: 512, kycVerified: true, kycStatus: 'VERIFIED', createdAt: '2024-02-01T09:00:00Z' },
  { id: 'b0000001-0001-0001-0001-000000000004', walletReference: 'WAL-FINTA-00012347', walletName: 'Fatima Al Ali', holderName: 'Fatima Al Ali', holderMobile: '+971507778899', partyId: 'a0000001-0001-0001-0001-000000000004', programId: 'c0000001-0001-0001-0001-000000000001', programName: 'Fintech Partner A - Consumer Wallets', programCode: 'BAAS-FINTECH-A', currentBalance: 3500, availableBalance: 3500, currency: 'AED', dailySpent: 0, monthlySpent: 500, dailyLimit: 1000, monthlyLimit: 5000, status: 'ACTIVE', lastTransaction: '2024-02-09T11:20:00Z', transactionCount: 8, kycVerified: false, kycStatus: 'PENDING', createdAt: '2024-02-05T11:00:00Z' },
  { id: 'b0000001-0001-0001-0001-000000000005', walletReference: 'WAL-FINTA-00012348', walletName: 'Omar Hassan', holderName: 'Omar Hassan', holderMobile: '+971501112233', partyId: 'a0000001-0001-0001-0001-000000000005', programId: 'c0000001-0001-0001-0001-000000000001', programName: 'Fintech Partner A - Consumer Wallets', programCode: 'BAAS-FINTECH-A', currentBalance: 0, availableBalance: 0, currency: 'AED', dailySpent: 0, monthlySpent: 0, dailyLimit: 1000, monthlyLimit: 5000, status: 'SUSPENDED', lastTransaction: '2024-01-25T11:20:00Z', transactionCount: 3, kycVerified: false, kycStatus: 'REJECTED', createdAt: '2024-01-20T11:00:00Z' },
];

const demoStats: WalletStats = {
  totalPrograms: 3, activePrograms: 3, totalWallets: 17320, activeWallets: 17250, suspendedWallets: 50, blockedWallets: 20,
  totalBalance: 174500000, totalAvailableBalance: 170000000, monthlyVolume: 25000000, dailyVolume: 1250000,
  todayTransactions: 4500, monthlyTransactions: 125000, kycVerifiedCount: 16500, kycPendingCount: 820,
};

// ============================================================================
// Status Configurations
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
  NOT_STARTED: { label: 'Not Started', color: 'neutral', icon: <Clock className="w-3 h-3" /> },
};

const partyTypeConfig: Record<string, { label: string; icon: React.ElementType; color: string; bgColor: string }> = {
  INDIVIDUAL: { label: 'Individual', icon: User, color: 'text-info-600 dark:text-info-300', bgColor: 'bg-info-50 dark:bg-info-500/10' },
  CORPORATE: { label: 'Corporate', icon: Building2, color: 'text-cat-2', bgColor: 'bg-cat-2-soft dark:bg-cat-2/15' },
  EMPLOYEE: { label: 'Employee', icon: Users, color: 'text-success-600 dark:text-success-300', bgColor: 'bg-success-50 dark:bg-success-500/10' },
  VENDOR: { label: 'Vendor', icon: Building2, color: 'text-warning-600 dark:text-warning-300', bgColor: 'bg-warning-50 dark:bg-warning-500/10' },
  CUSTOMER: { label: 'Customer', icon: UserCheck, color: 'text-cat-3', bgColor: 'bg-cat-3-soft dark:bg-cat-3/15' },
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
  label: string; value: string | number; subValue?: string; icon: React.ReactNode; iconBg: string;
  trend?: { value: number; label: string }; loading?: boolean; delay?: string;
}> = ({ label, value, subValue, icon, iconBg, trend, loading, delay }) => (
  <Card padding="sm" className="animate-fade-in" style={delay ? { animationDelay: delay } : undefined}>
    <div className="flex items-center justify-between">
      <div className="flex-1 min-w-0">
        <p className="text-xs font-medium text-neutral-500 dark:text-neutral-400 uppercase tracking-wider">{label}</p>
        {loading ? (
          <Skeleton className="h-8 w-24 mt-1" />
        ) : (
          <p className="stat-value-sm mt-1">{value}</p>
        )}
        {loading ? (
          <Skeleton className="h-3 w-20 mt-1" />
        ) : subValue ? (
          <p className="text-xs text-neutral-400 dark:text-neutral-500 mt-0.5">{subValue}</p>
        ) : null}
        {trend && !loading && (
          <div className={cn("flex items-center gap-1 text-xs mt-1", trend.value >= 0 ? "text-success-600 dark:text-success-300" : "text-error-600 dark:text-error-300")}>
            {trend.value >= 0 ? <ChevronUp className="w-3 h-3" /> : <ChevronDown className="w-3 h-3" />}
            <span>{Math.abs(trend.value)}% {trend.label}</span>
          </div>
        )}
      </div>
      <div className={cn("p-3 rounded-xl flex-shrink-0", iconBg)}>{icon}</div>
    </div>
  </Card>
);

// ============================================================================
// PARTY PICKER COMPONENT - Search/Select or Create Customer
// ============================================================================

const PartyPicker: React.FC<{
  value?: Party | null;
  onChange: (party: Party | null) => void;
  onCreateNew?: (party: Party) => void;
  placeholder?: string;
  allowCreate?: boolean;
}> = ({ value, onChange, onCreateNew, placeholder = 'Search customer...', allowCreate = true }) => {
  const [isOpen, setIsOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [searchResults, setSearchResults] = useState<Party[]>([]);
  const [loading, setLoading] = useState(false);
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [createLoading, setCreateLoading] = useState(false);
  const [createForm, setCreateForm] = useState<CreatePartyRequest>({
    partyType: 'INDIVIDUAL', legalName: '', contactPhone: '', contactEmail: '', emiratesId: '', taxId: '', city: '', country: 'AE',
  });
  
  const containerRef = useRef<HTMLDivElement>(null);
  const debounceRef = useRef<ReturnType<typeof setTimeout>>();

  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setIsOpen(false);
        setShowCreateForm(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const handleSearch = useCallback(async (query: string) => {
    setSearchQuery(query);
    if (debounceRef.current) clearTimeout(debounceRef.current);
    if (!query || query.length < 2) { setSearchResults([]); return; }

    debounceRef.current = setTimeout(async () => {
      setLoading(true);
      try {
        const result = await partyApi.search(query);
        setSearchResults(result.content);
      } catch {
        setSearchResults([]);
      } finally {
        setLoading(false);
      }
    }, 300);
  }, []);

  const handleSelect = (party: Party) => {
    onChange(party);
    setIsOpen(false);
    setSearchQuery('');
    setSearchResults([]);
  };

  const handleClear = () => {
    onChange(null);
    setSearchQuery('');
  };

  const handleCreateParty = async () => {
    if (!createForm.legalName || !createForm.contactPhone) return;
    setCreateLoading(true);
    try {
      const newParty = await partyApi.create(createForm);
      onChange(newParty);
      onCreateNew?.(newParty);
      setShowCreateForm(false);
      setIsOpen(false);
      setCreateForm({ partyType: 'INDIVIDUAL', legalName: '', contactPhone: '', contactEmail: '', emiratesId: '', taxId: '', city: '', country: 'AE' });
    } finally {
      setCreateLoading(false);
    }
  };

  const partyTypeConfig: Record<string, { label: string; icon: React.ElementType; color: string; bgColor: string }> = {
    INDIVIDUAL: { label: 'Individual', icon: User, color: 'text-info-600 dark:text-info-300', bgColor: 'bg-info-50 dark:bg-info-500/10' },
    CORPORATE: { label: 'Corporate', icon: Building2, color: 'text-cat-2', bgColor: 'bg-cat-2-soft dark:bg-cat-2/15' },
    EMPLOYEE: { label: 'Employee', icon: Users, color: 'text-success-600 dark:text-success-300', bgColor: 'bg-success-50 dark:bg-success-500/10' },
  };

  return (
    <div ref={containerRef} className="relative">
      {value ? (
        <div className="flex items-center gap-3 p-3 border border-neutral-300 dark:border-primary-700 rounded-lg bg-white dark:bg-primary-900">
          <div className={cn('w-10 h-10 rounded-full flex items-center justify-center', partyTypeConfig[value.partyType]?.bgColor || 'bg-neutral-100 dark:bg-primary-800')}>
            {(() => { const Icon = partyTypeConfig[value.partyType]?.icon || User; return <Icon className={cn('w-5 h-5', partyTypeConfig[value.partyType]?.color)} />; })()}
          </div>
          <div className="flex-1 min-w-0">
            <p className="font-medium text-neutral-900 dark:text-neutral-50 truncate">{value.legalName}</p>
            <p className="text-sm text-neutral-500 dark:text-neutral-400 truncate">
              {value.contactPhone}
              {value.kycStatus === 'VERIFIED' && <span className="ml-2 text-success-600 dark:text-success-300">• KYC Verified</span>}
            </p>
          </div>
          <button type="button" onClick={handleClear} className="p-1 hover:bg-neutral-100 dark:hover:bg-primary-800 rounded-full">
            <X className="w-5 h-5 text-neutral-400 dark:text-neutral-500" />
          </button>
        </div>
      ) : (
        <div className="relative">
          <input
            type="text"
            className="w-full px-10 py-2.5 border border-neutral-300 dark:border-primary-700 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-primary-500"
            placeholder={placeholder}
            value={searchQuery}
            onChange={(e) => handleSearch(e.target.value)}
            onFocus={() => setIsOpen(true)}
          />
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-neutral-400 dark:text-neutral-500" />
          {loading && <Loader2 className="absolute right-3 top-1/2 -translate-y-1/2 w-5 h-5 text-neutral-400 dark:text-neutral-500 animate-spin" />}
        </div>
      )}

      {isOpen && !value && (
        <div className="absolute z-50 w-full mt-1 bg-white dark:bg-primary-900 border border-neutral-200 dark:border-primary-800 rounded-lg shadow-lg max-h-96 overflow-hidden">
          {showCreateForm ? (
            <div className="p-4 space-y-4">
              <div className="flex items-center justify-between">
                <h3 className="font-semibold text-neutral-900 dark:text-neutral-50">Create New Customer</h3>
                <button type="button" onClick={() => setShowCreateForm(false)} className="p-1 hover:bg-neutral-100 dark:hover:bg-primary-800 rounded"><X className="w-5 h-5" /></button>
              </div>
              <div className="grid grid-cols-3 gap-2">
                {(['INDIVIDUAL', 'CORPORATE', 'EMPLOYEE'] as const).map(type => {
                  const config = partyTypeConfig[type];
                  const Icon = config.icon;
                  return (
                    <button key={type} type="button" onClick={() => setCreateForm({ ...createForm, partyType: type })}
                      className={cn('flex items-center gap-2 p-2 rounded-lg border text-sm',
                        createForm.partyType === type ? 'border-primary-500 bg-primary-50 dark:bg-primary-800/40' : 'border-neutral-200 dark:border-primary-800 hover:border-neutral-300 dark:hover:border-primary-700')}>
                      <Icon className={cn('w-4 h-4', config.color)} /><span>{config.label}</span>
                    </button>
                  );
                })}
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div className="col-span-2">
                  <input type="text" className="w-full px-3 py-2 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm" placeholder="Full Name *"
                    value={createForm.legalName || ''} onChange={(e) => setCreateForm({ ...createForm, legalName: e.target.value })} />
                </div>
                <input type="tel" className="w-full px-3 py-2 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm" placeholder="Mobile *"
                  value={createForm.contactPhone || ''} onChange={(e) => setCreateForm({ ...createForm, contactPhone: e.target.value })} />
                <input type="email" className="w-full px-3 py-2 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm" placeholder="Email"
                  value={createForm.contactEmail || ''} onChange={(e) => setCreateForm({ ...createForm, contactEmail: e.target.value })} />
                <input type="text" className="w-full px-3 py-2 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm" placeholder="Emirates ID"
                  value={createForm.emiratesId || ''} onChange={(e) => setCreateForm({ ...createForm, emiratesId: e.target.value })} />
                <input type="text" className="w-full px-3 py-2 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm" placeholder="City"
                  value={createForm.city || ''} onChange={(e) => setCreateForm({ ...createForm, city: e.target.value })} />
              </div>
              <div className="flex justify-end gap-2 pt-2 border-t">
                <button type="button" onClick={() => setShowCreateForm(false)} className="px-3 py-1.5 text-neutral-600 dark:text-neutral-300 hover:bg-neutral-100 dark:hover:bg-primary-800 rounded-lg text-sm">Cancel</button>
                <button type="button" onClick={handleCreateParty} disabled={createLoading || !createForm.legalName || !createForm.contactPhone}
                  className="px-3 py-1.5 bg-primary-600 text-white rounded-lg text-sm hover:bg-primary-700 disabled:opacity-50 flex items-center gap-1">
                  {createLoading && <Loader2 className="w-3 h-3 animate-spin" />}Create
                </button>
              </div>
            </div>
          ) : (
            <>
              <div className="max-h-64 overflow-y-auto">
                {searchResults.length > 0 ? (
                  <div className="p-2 space-y-1">
                    {searchResults.map(party => {
                      const config = partyTypeConfig[party.partyType] || partyTypeConfig.INDIVIDUAL;
                      const Icon = config.icon;
                      return (
                        <div key={party.id} onClick={() => handleSelect(party)}
                          className="flex items-center gap-3 p-3 rounded-lg cursor-pointer hover:bg-neutral-50 dark:hover:bg-primary-800/50">
                          <div className={cn('w-10 h-10 rounded-full flex items-center justify-center', config.bgColor)}>
                            <Icon className={cn('w-5 h-5', config.color)} />
                          </div>
                          <div className="flex-1 min-w-0">
                            <p className="font-medium text-neutral-900 dark:text-neutral-50 truncate">{party.legalName}</p>
                            <div className="flex items-center gap-2 text-sm text-neutral-500 dark:text-neutral-400">
                              {party.contactPhone && <span>{party.contactPhone}</span>}
                              {party.city && <span>• {party.city}</span>}
                            </div>
                          </div>
                          {party.kycStatus === 'VERIFIED' && <Shield className="w-4 h-4 text-success-500" />}
                        </div>
                      );
                    })}
                  </div>
                ) : searchQuery.length >= 2 ? (
                  <div className="p-4 text-center text-neutral-500 dark:text-neutral-400">
                    <User className="w-8 h-8 mx-auto mb-2 text-neutral-300 dark:text-neutral-600" />
                    <p className="text-sm">No customers found</p>
                  </div>
                ) : (
                  <div className="p-4 text-center text-neutral-500 dark:text-neutral-400">
                    <Search className="w-8 h-8 mx-auto mb-2 text-neutral-300 dark:text-neutral-600" />
                    <p className="text-sm">Type at least 2 characters</p>
                  </div>
                )}
              </div>
              {allowCreate && (
                <div className="border-t border-neutral-200 dark:border-primary-800 p-2">
                  <button type="button" onClick={() => { setShowCreateForm(true); setCreateForm({ ...createForm, legalName: searchQuery }); }}
                    className="w-full flex items-center gap-2 p-3 text-primary-600 dark:text-primary-200 hover:bg-primary-50 dark:bg-primary-800/40 rounded-lg dark:hover:bg-primary-800/40">
                    <Plus className="w-5 h-5" /><span className="font-medium">Create New Customer</span>
                  </button>
                </div>
              )}
            </>
          )}
        </div>
      )}
    </div>
  );
};

// ============================================================================
// PROGRAM CARD COMPONENT (Read-only - links to Programs page for editing)
// ============================================================================

const ProgramCard: React.FC<{
  program: WalletProgram;
  onClick: () => void;
}> = ({ program, onClick }) => {
  const statusConfig = programStatusConfig[program.status] || programStatusConfig.ACTIVE;
  
  return (
    <Card hover className="cursor-pointer" onClick={onClick}>
      <div className="flex items-start justify-between mb-4">
        <div className="flex items-center gap-3">
          <div className="w-12 h-12 rounded-xl bg-gradient-to-br from-primary-600 to-primary-900 flex items-center justify-center">
            <Wallet className="w-6 h-6 text-white" />
          </div>
          <div>
            <h3 className="section-title">{program.programName}</h3>
            <p className="text-sm text-neutral-500 dark:text-neutral-400">{program.programCode}</p>
          </div>
        </div>
        <Badge variant={statusConfig.color as any}>{statusConfig.label}</Badge>
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
// WALLET ROW COMPONENT - Enhanced with Party info display
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

  const copyToClipboard = (text: string) => navigator.clipboard.writeText(text);

  return (
    <tr className="data-table-row group">
      <td className="data-table-cell">
        <div className="flex items-center gap-3">
          <Avatar name={wallet.holderName} size="md" status={wallet.kycVerified ? 'online' : 'away'} />
          <div>
            <div className="flex items-center gap-2">
              <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{wallet.holderName}</p>
              {wallet.partyId && <span className="text-xs px-1.5 py-0.5 bg-info-100 text-info-700 rounded dark:bg-info-500/20 dark:text-info-300">Linked</span>}
            </div>
            <p className="text-xs text-neutral-500 dark:text-neutral-400">{wallet.holderMobile}</p>
          </div>
        </div>
      </td>
      <td className="data-table-cell">
        <div className="flex items-center gap-1">
          <p className="text-sm font-mono text-primary-900 dark:text-neutral-50">{wallet.walletReference}</p>
          <button onClick={() => copyToClipboard(wallet.walletReference)} className="p-1 hover:bg-neutral-100 dark:hover:bg-primary-800 rounded opacity-0 group-hover:opacity-100 transition-opacity"><Copy className="w-3 h-3 text-neutral-400 dark:text-neutral-500" /></button>
        </div>
        <p className="text-xs text-neutral-500 dark:text-neutral-400">{wallet.programCode || wallet.programName}</p>
      </td>
      <td className="data-table-cell">
        <p className="text-sm font-semibold text-primary-900 dark:text-neutral-50">{formatCurrency(wallet.currentBalance)}</p>
        <p className="text-xs text-neutral-500 dark:text-neutral-400">Avail: {formatCurrency(wallet.availableBalance)}</p>
      </td>
      <td className="data-table-cell">
        <div className="space-y-1.5 w-28">
          <div>
            <div className="flex justify-between text-xs mb-0.5"><span className="text-neutral-400 dark:text-neutral-500">Daily</span><span className="text-neutral-600 dark:text-neutral-300">{formatCurrency(wallet.dailySpent)}</span></div>
            <ProgressBar value={dailyUsage} size="sm" variant={dailyUsage > 80 ? 'warning' : 'default'} />
          </div>
          <div>
            <div className="flex justify-between text-xs mb-0.5"><span className="text-neutral-400 dark:text-neutral-500">Monthly</span><span className="text-neutral-600 dark:text-neutral-300">{formatCurrency(wallet.monthlySpent)}</span></div>
            <ProgressBar value={monthlyUsage} size="sm" variant={monthlyUsage > 80 ? 'warning' : 'default'} />
          </div>
        </div>
      </td>
      <td className="data-table-cell">
        <div className="space-y-1">
          <Badge variant={status.color as any} className="flex items-center gap-1 w-fit">{status.icon}{status.label}</Badge>
          <Badge variant={kycStatus.color as any} size="sm" className="flex items-center gap-1">{kycStatus.icon}{kycStatus.label}</Badge>
        </div>
      </td>
      <td className="data-table-cell">
        <p className="text-sm text-neutral-700 dark:text-neutral-200">{wallet.transactionCount} txns</p>
        <p className="text-xs text-neutral-400 dark:text-neutral-500">{wallet.lastTransaction ? formatDate(wallet.lastTransaction) : 'No activity'}</p>
      </td>
      <td className="data-table-cell">
        <div className="relative">
          <button onClick={(e) => { e.stopPropagation(); setShowActions(!showActions); }} className="p-2 hover:bg-neutral-100 dark:hover:bg-primary-800 rounded-lg opacity-0 group-hover:opacity-100 transition-opacity"><MoreHorizontal className="w-4 h-4 text-neutral-500 dark:text-neutral-400" /></button>
          {showActions && (
            <>
              <div className="fixed inset-0 z-10" onClick={() => setShowActions(false)} />
              <div className="absolute right-0 top-full mt-1 w-48 bg-white dark:bg-primary-900 rounded-xl shadow-lg border border-neutral-200 dark:border-primary-800 py-1 z-20">
                <button onClick={() => { onView(); setShowActions(false); }} className="w-full flex items-center gap-2 px-4 py-2 text-sm hover:bg-neutral-50 dark:hover:bg-primary-800/50"><Eye className="w-4 h-4" /> View Details</button>
                <button onClick={() => { onEdit(); setShowActions(false); }} className="w-full flex items-center gap-2 px-4 py-2 text-sm hover:bg-neutral-50 dark:hover:bg-primary-800/50"><Edit className="w-4 h-4" /> Edit Limits</button>
                <hr className="my-1 border-neutral-100 dark:border-primary-800/60" />
                <button onClick={() => { onAction('load'); setShowActions(false); }} className="w-full flex items-center gap-2 px-4 py-2 text-sm hover:bg-neutral-50 dark:hover:bg-primary-800/50"><ArrowDownRight className="w-4 h-4 text-success-600 dark:text-success-300" /> Load Funds</button>
                <button onClick={() => { onAction('withdraw'); setShowActions(false); }} className="w-full flex items-center gap-2 px-4 py-2 text-sm hover:bg-neutral-50 dark:hover:bg-primary-800/50"><ArrowUpRight className="w-4 h-4 text-error-600 dark:text-error-300" /> Withdraw</button>
                <button onClick={() => { onAction('transfer'); setShowActions(false); }} className="w-full flex items-center gap-2 px-4 py-2 text-sm hover:bg-neutral-50 dark:hover:bg-primary-800/50"><Send className="w-4 h-4" /> Transfer</button>
                <hr className="my-1 border-neutral-100 dark:border-primary-800/60" />
                {!wallet.kycVerified && <button onClick={() => { onAction('verify-kyc'); setShowActions(false); }} className="w-full flex items-center gap-2 px-4 py-2 text-sm text-success-600 dark:text-success-300 hover:bg-success-50 dark:bg-success-500/10 dark:hover:bg-success-500/10"><UserCheck className="w-4 h-4" /> Verify KYC</button>}
                {wallet.status === 'ACTIVE' ? (
                  <>
                    <button onClick={() => { onAction('suspend'); setShowActions(false); }} className="w-full flex items-center gap-2 px-4 py-2 text-sm text-warning-600 dark:text-warning-300 hover:bg-warning-50 dark:bg-warning-500/10 dark:hover:bg-warning-500/10"><Lock className="w-4 h-4" /> Suspend</button>
                    <button onClick={() => { onAction('block'); setShowActions(false); }} className="w-full flex items-center gap-2 px-4 py-2 text-sm text-error-600 dark:text-error-300 hover:bg-error-50 dark:bg-error-500/10 dark:hover:bg-error-500/10"><Ban className="w-4 h-4" /> Block</button>
                  </>
                ) : wallet.status === 'SUSPENDED' && (
                  <button onClick={() => { onAction('reactivate'); setShowActions(false); }} className="w-full flex items-center gap-2 px-4 py-2 text-sm text-success-600 dark:text-success-300 hover:bg-success-50 dark:bg-success-500/10 dark:hover:bg-success-500/10"><Unlock className="w-4 h-4" /> Reactivate</button>
                )}
              </div>
            </>
          )}
        </div>
      </td>
    </tr>
  );
};

// Mobile Wallet Card Component
const WalletMobileCard: React.FC<{
  wallet: WalletAccount;
  onView: () => void;
  onEdit: () => void;
  onAction: (action: string) => void;
}> = ({ wallet, onView, onAction }) => {
  const status = walletStatusConfig[wallet.status] || walletStatusConfig.ACTIVE;
  const kycStatus = kycStatusConfig[wallet.kycStatus] || kycStatusConfig.PENDING;

  return (
    <div className="p-4 border border-neutral-200 dark:border-primary-800 rounded-xl hover:border-primary-200 hover:shadow-sm transition-all" onClick={onView}>
      <div className="flex items-start justify-between mb-3">
        <div className="flex items-center gap-3">
          <Avatar name={wallet.holderName} size="md" status={wallet.kycVerified ? 'online' : 'away'} />
          <div>
            <p className="font-medium text-primary-900 dark:text-neutral-50">{wallet.holderName}</p>
            <p className="text-xs text-neutral-500 dark:text-neutral-400 font-mono">{wallet.walletReference}</p>
          </div>
        </div>
        <div className="flex flex-col items-end gap-1">
          <Badge variant={status.color as any} size="sm">{status.label}</Badge>
          <Badge variant={kycStatus.color as any} size="sm">{kycStatus.label}</Badge>
        </div>
      </div>

      <div className="grid grid-cols-2 gap-3 mb-3">
        <div className="bg-neutral-50 dark:bg-primary-950 rounded-lg p-2">
          <p className="text-xs text-neutral-500 dark:text-neutral-400">Balance</p>
          <p className="text-sm font-semibold text-primary-900 dark:text-neutral-50">{formatCurrency(wallet.currentBalance)}</p>
        </div>
        <div className="bg-neutral-50 dark:bg-primary-950 rounded-lg p-2">
          <p className="text-xs text-neutral-500 dark:text-neutral-400">Available</p>
          <p className="text-sm font-semibold text-success-600 dark:text-success-300">{formatCurrency(wallet.availableBalance)}</p>
        </div>
      </div>

      <div className="flex items-center justify-between pt-3 border-t border-neutral-100 dark:border-primary-800/60">
        <p className="text-xs text-neutral-500 dark:text-neutral-400">{wallet.transactionCount} transactions</p>
        <div className="flex items-center gap-1">
          <button onClick={(e) => { e.stopPropagation(); onAction('load'); }} className="p-1.5 hover:bg-success-50 dark:bg-success-500/10 rounded-lg dark:hover:bg-success-500/10" title="Load">
            <ArrowDownRight className="w-4 h-4 text-success-600 dark:text-success-300" />
          </button>
          <button onClick={(e) => { e.stopPropagation(); onAction('transfer'); }} className="p-1.5 hover:bg-primary-50 dark:bg-primary-800/40 rounded-lg dark:hover:bg-primary-800/40" title="Transfer">
            <Send className="w-4 h-4 text-primary-600 dark:text-primary-200" />
          </button>
          <button onClick={(e) => { e.stopPropagation(); onView(); }} className="p-1.5 hover:bg-neutral-100 dark:hover:bg-primary-800 rounded-lg" title="View">
            <Eye className="w-4 h-4 text-neutral-600 dark:text-neutral-300" />
          </button>
        </div>
      </div>
    </div>
  );
};

// ============================================================================
// MAIN WALLET PAGE COMPONENT - Enhanced
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
  const [showBulkLoadModal, setShowBulkLoadModal] = useState(false);
  const [showKycVerifyModal, setShowKycVerifyModal] = useState(false);
  const [showBlockModal, setShowBlockModal] = useState(false);
  
  const [selectedProgram, setSelectedProgram] = useState<WalletProgram | null>(null);
  const [selectedWallet, setSelectedWallet] = useState<WalletAccount | null>(null);
  const [walletDetail, setWalletDetail] = useState<WalletAccountDetail | null>(null);
  
  // ENHANCED: Issue form with Party and Hierarchy
  const [issueForm, setIssueForm] = useState({
    programId: '',
    holderName: '',
    holderMobile: '',
    holderEmail: '',
    emiratesId: '',
    city: '',
    walletType: 'CONSUMER',
    hierarchyNodeId: '',
    initialLoadAmount: '',
    dailyLimit: '',
    monthlyLimit: '',
    autoTriggerKyc: false,
  });
  const [selectedParty, setSelectedParty] = useState<Party | null>(null);
  const [selectedHierarchyNode, setSelectedHierarchyNode] = useState<HierarchyNode | null>(null);
  const [partyMode, setPartyMode] = useState<'existing' | 'new'>('existing');
  
  // Other form states
  const [editWalletForm, setEditWalletForm] = useState<UpdateWalletRequest>({});
  const [loadForm, setLoadForm] = useState({ amount: '', source: 'BANK_TRANSFER', description: '' });
  const [withdrawForm, setWithdrawForm] = useState({ amount: '', destination: 'BANK_TRANSFER', description: '' });
  const [transferForm, setTransferForm] = useState({ toWalletReference: '', amount: '', description: '' });
  const [bulkLoadForm, setBulkLoadForm] = useState({ programId: '', csvData: '', description: '' });
  const [kycVerifyForm, setKycVerifyForm] = useState<VerifyKycRequest>({ verificationMethod: 'DOCUMENT', documentType: 'EMIRATES_ID', documentNumber: '', documentExpiryDate: '', verifiedBy: '' });
  const [blockForm, setBlockForm] = useState({ reason: '', permanent: false });
  const [actionLoading, setActionLoading] = useState(false);

  // Tabs
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
      } catch (err: any) {
        console.warn('Stats API failed:', err.message);
        errors.push('Stats');
      }
      try {
        const programsData = await walletsApi.getPrograms(selectedPartnerId || undefined);
        if (programsData) setPrograms(programsData);
      } catch (err: any) {
        console.warn('Programs API failed:', err.message);
        errors.push('Programs');
      }
      try {
        const walletsData = await walletsApi.getWallets({
          page: currentPage,
          size: 20,
          query: searchQuery || undefined,
          status: statusFilter || undefined,
          programId: selectedPartnerId || undefined,
          kycVerified: kycFilter === 'verified' ? true : kycFilter === 'pending' ? false : undefined,
        });
        if (walletsData?.content) {
          setWallets(walletsData.content);
          setTotalWallets(walletsData.totalElements);
          setTotalPages(walletsData.totalPages);
        }
      } catch (err: any) {
        console.warn('Wallets API failed:', err.message);
        errors.push('Wallets');
      }
      if (errors.length > 0) {
        setError(`API unavailable for: ${errors.join(', ')}. Showing demo data.`);
      }
    } catch (err) {
      setError('Failed to load data. Showing demo data.');
    } finally {
      setLoading(false);
    }
  }, [currentPage, searchQuery, statusFilter, kycFilter, selectedPartnerId]);

  useEffect(() => { fetchData(); }, [fetchData]);

  const showSuccess = (message: string) => { setActionSuccess(message); setTimeout(() => setActionSuccess(null), 3000); };
  const showError = (message: string) => { setError(message); };

  // Navigate to Programs page (for wallet program management)
  const navigateToProgramsPage = () => {
    window.location.href = '/programs?type=WALLET';
  };

  // ENHANCED: Issue Wallet Handler with Party linkage
  const handleIssueWallet = async () => {
    // Validate
    if (!issueForm.programId) {
      showError('Please select a program');
      return;
    }
    if (!selectedParty && !issueForm.holderName) {
      showError('Please select a customer or enter holder name');
      return;
    }
    if (!selectedParty && !issueForm.holderMobile) {
      showError('Please enter mobile number');
      return;
    }

    // Validate partyId is a proper UUID before sending to backend
    const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    if (selectedParty?.id && !UUID_REGEX.test(selectedParty.id)) {
      showError('Invalid party reference. Please clear and re-select the customer.');
      setSelectedParty(null);
      return;
    }

    setActionLoading(true);
    try {
      const request: IssueWalletRequest = {
        programId: issueForm.programId,
        partyId: selectedParty?.id,
        createParty: !selectedParty && !!issueForm.holderName,
        partyType: !selectedParty ? 'INDIVIDUAL' : undefined,
        holderName: selectedParty?.legalName || issueForm.holderName,
        holderMobile: selectedParty?.contactPhone || issueForm.holderMobile,
        holderEmail: selectedParty?.contactEmail || issueForm.holderEmail || undefined,
        emiratesId: issueForm.emiratesId || undefined,
        city: issueForm.city || undefined,
        hierarchyNodeId: issueForm.hierarchyNodeId || undefined,
        walletType: issueForm.walletType,
        initialLoadAmount: issueForm.initialLoadAmount ? parseFloat(issueForm.initialLoadAmount) : undefined,
        dailyLimit: issueForm.dailyLimit ? parseFloat(issueForm.dailyLimit) : undefined,
        monthlyLimit: issueForm.monthlyLimit ? parseFloat(issueForm.monthlyLimit) : undefined,
        autoTriggerKyc: issueForm.autoTriggerKyc,
      };

      console.log('Issuing wallet with request:', request);
      await walletsApi.issueWallet(request);
      showSuccess('Wallet issued successfully!');
      setShowIssueModal(false);
      resetIssueForm();
      fetchData();
    } catch (err: any) {
      console.error('Failed to issue wallet:', err);
      showError(err.message || 'Failed to issue wallet');
    } finally {
      setActionLoading(false);
    }
  };

  const resetIssueForm = () => {
    setIssueForm({
      programId: '',
      holderName: '',
      holderMobile: '',
      holderEmail: '',
      emiratesId: '',
      city: '',
      walletType: 'CONSUMER',
      hierarchyNodeId: '',
      initialLoadAmount: '',
      dailyLimit: '',
      monthlyLimit: '',
      autoTriggerKyc: false,
    });
    setSelectedParty(null);
    setSelectedHierarchyNode(null);
    setPartyMode('existing');
  };

  // Other handlers (unchanged)
  const handleUpdateWallet = async () => {
    if (!selectedWallet) return;
    setActionLoading(true);
    try { await walletsApi.updateWallet(selectedWallet.id, editWalletForm); showSuccess('Wallet updated!'); setShowEditWalletModal(false); fetchData(); }
    catch (err: any) { showError(err.message || 'Failed'); } finally { setActionLoading(false); }
  };

  const handleLoadFunds = async () => {
    if (!selectedWallet || !loadForm.amount) return;
    setActionLoading(true);
    try { const result = await walletsApi.loadFunds(selectedWallet.id, { amount: parseFloat(loadForm.amount), source: loadForm.source, description: loadForm.description || undefined }); showSuccess(`Loaded! Ref: ${result.referenceNumber}`); setShowLoadModal(false); setLoadForm({ amount: '', source: 'BANK_TRANSFER', description: '' }); fetchData(); }
    catch (err: any) { showError(err.message || 'Failed'); } finally { setActionLoading(false); }
  };

  const handleWithdraw = async () => {
    if (!selectedWallet || !withdrawForm.amount) return;
    setActionLoading(true);
    try { const result = await walletsApi.withdrawFunds(selectedWallet.id, { amount: parseFloat(withdrawForm.amount), destination: withdrawForm.destination, description: withdrawForm.description || undefined }); showSuccess(`Withdrawn! Ref: ${result.referenceNumber}`); setShowWithdrawModal(false); setWithdrawForm({ amount: '', destination: 'BANK_TRANSFER', description: '' }); fetchData(); }
    catch (err: any) { showError(err.message || 'Failed'); } finally { setActionLoading(false); }
  };

  const handleTransfer = async () => {
    if (!selectedWallet || !transferForm.toWalletReference || !transferForm.amount) return;
    setActionLoading(true);
    try { const result = await walletsApi.transferFunds(selectedWallet.id, { toWalletReference: transferForm.toWalletReference, amount: parseFloat(transferForm.amount), description: transferForm.description || undefined }); showSuccess(`Transferred! Ref: ${result.referenceNumber}`); setShowTransferModal(false); setTransferForm({ toWalletReference: '', amount: '', description: '' }); fetchData(); }
    catch (err: any) { showError(err.message || 'Failed'); } finally { setActionLoading(false); }
  };

  const handleBulkLoad = async () => {
    if (!bulkLoadForm.csvData) { showError('Enter wallet data'); return; }
    const lines = bulkLoadForm.csvData.trim().split('\n');
    const loads: BulkLoadItem[] = lines.map(line => { const [walletReference, amountStr] = line.split(',').map(s => s.trim()); return { walletReference, amount: parseFloat(amountStr) }; }).filter(item => item.walletReference && !isNaN(item.amount));
    if (!loads.length) { showError('No valid entries'); return; }
    setActionLoading(true);
    try { const result = await walletsApi.bulkLoadFunds({ programId: bulkLoadForm.programId || undefined, loads, description: bulkLoadForm.description || undefined }); showSuccess(`Bulk load: ${result.successCount}/${result.totalCount} successful`); setShowBulkLoadModal(false); setBulkLoadForm({ programId: '', csvData: '', description: '' }); fetchData(); }
    catch (err: any) { showError(err.message || 'Failed'); } finally { setActionLoading(false); }
  };

  const handleVerifyKyc = async () => {
    if (!selectedWallet) return;
    setActionLoading(true);
    try { await walletsApi.verifyKyc(selectedWallet.id, kycVerifyForm); showSuccess('KYC verified!'); setShowKycVerifyModal(false); setKycVerifyForm({ verificationMethod: 'DOCUMENT', documentType: 'EMIRATES_ID', documentNumber: '', documentExpiryDate: '', verifiedBy: '' }); fetchData(); }
    catch (err: any) { showError(err.message || 'Failed'); } finally { setActionLoading(false); }
  };

  const handleBlockWallet = async () => {
    if (!selectedWallet || !blockForm.reason) { showError('Provide reason'); return; }
    setActionLoading(true);
    try { await walletsApi.blockWallet(selectedWallet.id, blockForm.reason, blockForm.permanent); showSuccess('Blocked'); setShowBlockModal(false); setBlockForm({ reason: '', permanent: false }); fetchData(); }
    catch (err: any) { showError(err.message || 'Failed'); } finally { setActionLoading(false); }
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
        try { await walletsApi.suspendWallet(wallet.id, 'Suspended'); showSuccess('Suspended'); fetchData(); } catch (err: any) { showError(err.message || 'Failed'); } finally { setActionLoading(false); }
        break;
      case 'reactivate':
        setActionLoading(true);
        try { await walletsApi.reactivateWallet(wallet.id, 'Reactivated'); showSuccess('Reactivated'); fetchData(); } catch (err: any) { showError(err.message || 'Failed'); } finally { setActionLoading(false); }
        break;
    }
  };

  const handleViewWallet = async (wallet: WalletAccount) => {
    setSelectedWallet(wallet);
    setShowDetailModal(true);
    try { const detail = await walletsApi.getWalletDetails(wallet.id); setWalletDetail(detail); }
    catch { setWalletDetail({ ...wallet, recentTransactions: [] } as WalletAccountDetail); }
  };

  const handleEditWallet = (wallet: WalletAccount) => {
    setSelectedWallet(wallet);
    setEditWalletForm({ walletName: wallet.walletName, dailyLimit: wallet.dailyLimit, monthlyLimit: wallet.monthlyLimit });
    setShowEditWalletModal(true);
  };

  const filteredWallets = wallets.filter(w => {
    const q = searchQuery.toLowerCase();
    return !searchQuery || w.holderName.toLowerCase().includes(q) || w.walletReference.toLowerCase().includes(q) || w.holderMobile.includes(searchQuery);
  });

  const partners = Array.from(new Map(programs.filter(p => p.corporateId).map(p => [p.corporateId, { id: p.corporateId, name: p.operatorName }])).values());

  const selectedProgramForIssue = programs.find(p => p.id === issueForm.programId);

  // ============================================================================
  // RENDER
  // ============================================================================
  return (
    <Page>
      {/* Alerts */}
      {actionSuccess && <Alert variant="success" className="flex items-center gap-2"><CheckCircle className="w-4 h-4" />{actionSuccess}</Alert>}
      {error && <Alert variant="error" className="flex items-center gap-2"><AlertCircle className="w-4 h-4" />{error}<button onClick={() => setError(null)} className="ml-auto underline text-sm">Dismiss</button></Alert>}

      {/* Quick Actions */}
      <div className="flex items-center justify-end gap-2 animate-fade-in" style={{ animationDelay: '0.05s' }}>
        <Button variant="outline" size="sm" leftIcon={loading ? <Loader2 className="w-4 h-4 animate-spin" /> : <RefreshCw className="w-4 h-4" />} onClick={fetchData} disabled={loading}>
          <span className="hidden sm:inline">{loading ? 'Loading...' : 'Refresh'}</span>
        </Button>
        <Button variant="outline" size="sm" leftIcon={<Upload className="w-4 h-4" />} onClick={() => setShowBulkLoadModal(true)}>
          <span className="hidden sm:inline">Bulk Load</span>
        </Button>
        <Button variant="outline" size="sm" leftIcon={<ExternalLink className="w-4 h-4" />} onClick={navigateToProgramsPage}>
          <span className="hidden sm:inline">Programs</span>
        </Button>
        <Button size="sm" leftIcon={<CreditCard className="w-4 h-4" />} onClick={() => setShowIssueModal(true)}>
          Issue Wallet
        </Button>
      </div>

      {/* Partner Filter */}
      <Card padding="sm" className="bg-gradient-to-r from-primary-50/50 via-white to-primary-50/50 border-primary-100/50 animate-fade-in dark:from-primary-900 dark:via-primary-900 dark:to-primary-900" style={{ animationDelay: '0.1s' }}>
        <div className="flex flex-col sm:flex-row sm:items-center gap-4">
          <div className="flex items-center gap-2">
            <StatusIconBadge tone="primary" icon={Building2} size="sm" rounded="lg" />
            <span className="font-medium text-primary-800 dark:text-neutral-100">Partner View</span>
          </div>
          <select className="flex-1 max-w-xs border border-neutral-200 dark:border-primary-800 rounded-lg px-3 py-2 bg-white dark:bg-primary-900 text-sm focus:ring-2 focus:ring-primary-500 focus:border-primary-500" value={selectedPartnerId} onChange={(e) => { setSelectedPartnerId(e.target.value); setCurrentPage(0); }}>
            <option value="">All Partners</option>
            {partners.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
          <div className="flex items-center gap-4 text-sm">
            <span className="text-neutral-600 dark:text-neutral-300"><span className="font-semibold text-primary-900 dark:text-neutral-50">{programs.length}</span> Programs</span>
            <span className="text-neutral-600 dark:text-neutral-300"><span className="font-semibold text-primary-900 dark:text-neutral-50">{stats.activeWallets?.toLocaleString()}</span> Wallets</span>
            <span className="text-neutral-600 dark:text-neutral-300"><span className="font-semibold text-primary-900 dark:text-neutral-50">{formatCurrency(stats.totalBalance)}</span> Float</span>
          </div>
        </div>
      </Card>

      {/* Tabs */}
      <div className="animate-fade-in" style={{ animationDelay: '0.15s' }}>
        <Tabs tabs={tabs} activeTab={activeTab} onChange={(id) => setActiveTab(id as typeof activeTab)} variant="pills" />
      </div>

      {/* Overview Tab */}
      {activeTab === 'overview' && (
        <div className="space-y-6">
          <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
            <StatCard label="Total Float" value={formatCurrency(stats.totalBalance)} subValue={`Available: ${formatCurrency(stats.totalAvailableBalance || stats.totalBalance)}`} icon={<Wallet className="w-5 h-5 text-primary-700 dark:text-neutral-200" />} iconBg="bg-primary-100 dark:bg-primary-700" loading={loading} delay="0.2s" />
            <StatCard label="Active Wallets" value={stats.activeWallets?.toLocaleString() || '0'} subValue={`${stats.suspendedWallets || 0} suspended`} icon={<CreditCard className="w-5 h-5 text-success-600 dark:text-success-300" />} iconBg="bg-success-50 dark:bg-success-500/10" trend={{ value: 12, label: 'month' }} loading={loading} delay="0.25s" />
            <StatCard label="Daily Volume" value={formatCurrency(stats.dailyVolume || 0)} subValue={`${stats.todayTransactions?.toLocaleString() || 0} transactions`} icon={<Activity className="w-5 h-5 text-info-600 dark:text-info-300" />} iconBg="bg-info-50 dark:bg-info-500/10" loading={loading} delay="0.3s" />
            <StatCard label="KYC Rate" value={`${Math.round((stats.kycVerifiedCount / (stats.kycVerifiedCount + stats.kycPendingCount || 1)) * 100)}%`} subValue={`${stats.kycPendingCount} pending verification`} icon={<Shield className="w-5 h-5 text-warning-600 dark:text-warning-300" />} iconBg="bg-warning-50 dark:bg-warning-500/10" loading={loading} delay="0.35s" />
          </div>
          <Card className="animate-fade-in" style={{ animationDelay: '0.4s' }}>
            <h3 className="text-sm font-medium text-neutral-500 dark:text-neutral-400 uppercase tracking-wider mb-4">Quick Actions</h3>
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
              <button onClick={() => setShowIssueModal(true)} className="flex flex-col items-center justify-center gap-2 p-4 rounded-xl border border-neutral-200 dark:border-primary-800 hover:border-primary-300 hover:bg-primary-50 dark:bg-primary-800/40 transition-all group dark:hover:bg-primary-800/40">
                <StatusIconBadge tone="primary" icon={CreditCard} rounded="lg" className="group-hover:bg-primary-200 transition-colors" />
                <span className="field-label">Issue Wallet</span>
              </button>
              <button onClick={() => setShowBulkLoadModal(true)} className="flex flex-col items-center justify-center gap-2 p-4 rounded-xl border border-neutral-200 dark:border-primary-800 hover:border-success-300 hover:bg-success-50 dark:bg-success-500/10 transition-all group dark:hover:bg-success-500/10">
                <StatusIconBadge tone="success" icon={Upload} rounded="lg" className="group-hover:bg-success-200 transition-colors" />
                <span className="field-label">Bulk Load</span>
              </button>
              <button onClick={navigateToProgramsPage} className="flex flex-col items-center justify-center gap-2 p-4 rounded-xl border border-neutral-200 dark:border-primary-800 hover:border-info-300 hover:bg-info-50 dark:bg-info-500/10 transition-all group dark:hover:bg-info-500/10">
                <StatusIconBadge tone="info" icon={Settings} rounded="lg" className="group-hover:bg-info-200 transition-colors" />
                <span className="field-label">Programs</span>
              </button>
              <button onClick={() => setActiveTab('wallets')} className="flex flex-col items-center justify-center gap-2 p-4 rounded-xl border border-neutral-200 dark:border-primary-800 hover:border-warning-300 hover:bg-warning-50 dark:bg-warning-500/10 transition-all group dark:hover:bg-warning-500/10">
                <StatusIconBadge tone="warning" icon={Search} rounded="lg" className="group-hover:bg-warning-200 transition-colors" />
                <span className="field-label">Search</span>
              </button>
            </div>
          </Card>
        </div>
      )}

      {/* Programs Tab */}
      {activeTab === 'programs' && (
        <div className="space-y-4">
          <div className="flex justify-between items-center">
            <p className="text-neutral-600 dark:text-neutral-300">Programs are managed in the Programs page.</p>
            <Button variant="outline" leftIcon={<ExternalLink className="w-4 h-4" />} onClick={navigateToProgramsPage}>Go to Programs</Button>
          </div>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
            {programs.map(p => <ProgramCard key={p.id} program={p} onClick={() => { setSelectedPartnerId(p.id); setActiveTab('wallets'); }} />)}
            {!programs.length && <div className="col-span-2"><EmptyState icon={<Settings className="w-8 h-8" />} title="No programs" action={<Button onClick={navigateToProgramsPage}>Go to Programs</Button>} /></div>}
          </div>
        </div>
      )}

      {/* Wallets Tab */}
      {activeTab === 'wallets' && (
        <Card padding="none" className="animate-fade-in" style={{ animationDelay: '0.2s' }}>
          {/* Filters */}
          <div className="p-4 border-b border-neutral-100 dark:border-primary-800/60">
            <div className="flex flex-col sm:flex-row gap-3">
              <div className="flex-1">
                <Input placeholder="Search by name, mobile or wallet ref..." value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} leftIcon={<Search className="w-4 h-4" />} />
              </div>
              <div className="flex flex-wrap gap-2">
                <select className="border border-neutral-200 dark:border-primary-800 rounded-lg px-3 py-2 text-sm bg-white dark:bg-primary-900 focus:ring-2 focus:ring-primary-500 focus:border-primary-500" value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)}>
                  <option value="">All Status</option>
                  <option value="ACTIVE">Active</option>
                  <option value="SUSPENDED">Suspended</option>
                  <option value="BLOCKED">Blocked</option>
                </select>
                <select className="border border-neutral-200 dark:border-primary-800 rounded-lg px-3 py-2 text-sm bg-white dark:bg-primary-900 focus:ring-2 focus:ring-primary-500 focus:border-primary-500" value={kycFilter} onChange={(e) => setKycFilter(e.target.value)}>
                  <option value="">All KYC</option>
                  <option value="verified">Verified</option>
                  <option value="pending">Pending</option>
                </select>
                <Button variant="outline" size="sm" leftIcon={<Download className="w-4 h-4" />}>
                  <span className="hidden sm:inline">Export</span>
                </Button>
              </div>
            </div>
          </div>

          {loading ? (
            <div className="p-8">
              <div className="space-y-4">
                {[1, 2, 3].map(i => (
                  <div key={i} className="flex items-center gap-4">
                    <Skeleton className="w-10 h-10 rounded-full" />
                    <div className="flex-1 space-y-2">
                      <Skeleton className="h-4 w-32" />
                      <Skeleton className="h-3 w-24" />
                    </div>
                    <Skeleton className="h-6 w-20" />
                  </div>
                ))}
              </div>
            </div>
          ) : filteredWallets.length > 0 ? (
            <>
              {/* Desktop Table */}
              <div className="hidden lg:block overflow-x-auto">
                <table className="data-table">
                  <thead className="data-table-header">
                    <tr>
                      <th className="data-table-header-cell">Holder</th>
                      <th className="data-table-header-cell">Wallet</th>
                      <th className="data-table-header-cell">Balance</th>
                      <th className="data-table-header-cell">Usage</th>
                      <th className="data-table-header-cell">Status</th>
                      <th className="data-table-header-cell">Activity</th>
                      <th className="data-table-header-cell w-12"></th>
                    </tr>
                  </thead>
                  <tbody>
                    {filteredWallets.map(w => <WalletRow key={w.id} wallet={w} onView={() => handleViewWallet(w)} onEdit={() => handleEditWallet(w)} onAction={(a) => handleWalletAction(a, w)} />)}
                  </tbody>
                </table>
              </div>

              {/* Mobile Cards */}
              <div className="lg:hidden p-4 space-y-3">
                {filteredWallets.map(w => (
                  <WalletMobileCard key={w.id} wallet={w} onView={() => handleViewWallet(w)} onEdit={() => handleEditWallet(w)} onAction={(a) => handleWalletAction(a, w)} />
                ))}
              </div>
            </>
          ) : (
            <div className="p-12">
              <EmptyState
                icon={<CreditCard className="w-12 h-12" />}
                title="No wallets found"
                description="Issue a new wallet to get started or adjust your search filters"
                action={<Button onClick={() => setShowIssueModal(true)} leftIcon={<Plus className="w-4 h-4" />}>Issue Wallet</Button>}
              />
            </div>
          )}

          {/* Pagination */}
          {totalPages > 1 && (
            <div className="p-4 border-t border-neutral-100 dark:border-primary-800/60 flex items-center justify-between">
              <p className="text-sm text-neutral-500 dark:text-neutral-400">Page {currentPage + 1} of {totalPages} <span className="hidden sm:inline">({totalWallets} total wallets)</span></p>
              <div className="flex gap-2">
                <Button variant="outline" size="sm" disabled={currentPage === 0} onClick={() => setCurrentPage(p => p - 1)} leftIcon={<ChevronLeft className="w-4 h-4" />}>
                  <span className="hidden sm:inline">Previous</span>
                </Button>
                <Button variant="outline" size="sm" disabled={currentPage >= totalPages - 1} onClick={() => setCurrentPage(p => p + 1)} rightIcon={<ChevronRight className="w-4 h-4" />}>
                  <span className="hidden sm:inline">Next</span>
                </Button>
              </div>
            </div>
          )}
        </Card>
      )}

      {/* ================================================================== */}
      {/* ISSUE WALLET MODAL - Enhanced with PartyPicker & Hierarchy */}
      {/* ================================================================== */}
      <Modal isOpen={showIssueModal} onClose={() => { setShowIssueModal(false); resetIssueForm(); }} title="Issue New Wallet" subtitle="Create a wallet" size="lg"
        footer={<><Button variant="outline" onClick={() => { setShowIssueModal(false); resetIssueForm(); }}>Cancel</Button><Button onClick={handleIssueWallet} disabled={actionLoading}>{actionLoading && <Loader2 className="w-4 h-4 animate-spin mr-2" />}Issue Wallet</Button></>}>
        <div className="space-y-6">
          {/* Step 1: Program */}
          <div className="space-y-2">
            <div className="flex items-center gap-2 text-primary-700 dark:text-neutral-200">
              <div className="w-6 h-6 rounded-full bg-primary-100 dark:bg-primary-700 flex items-center justify-center text-sm font-medium">1</div>
              <span className="font-medium">Select Program</span>
            </div>
            <select className="w-full border border-neutral-300 dark:border-primary-700 rounded-lg px-3 py-2.5" value={issueForm.programId} onChange={(e) => setIssueForm({ ...issueForm, programId: e.target.value })}>
              <option value="">Choose program...</option>
              {programs.filter(p => p.status === 'ACTIVE').map(p => <option key={p.id} value={p.id}>{p.programName} ({p.programCode})</option>)}
            </select>
            {selectedProgramForIssue && (
              <div className="flex gap-4 text-xs text-neutral-500 dark:text-neutral-400">
                <span>Daily: {formatCurrency(selectedProgramForIssue.dailySpendLimit)}</span>
                <span>Monthly: {formatCurrency(selectedProgramForIssue.monthlySpendLimit)}</span>
                {selectedProgramForIssue.kycRequired && <span className="text-warning-600 dark:text-warning-300">KYC Required</span>}
              </div>
            )}
          </div>

          {/* Step 2: Customer */}
          <div className="space-y-3">
            <div className="flex items-center gap-2 text-primary-700 dark:text-neutral-200">
              <div className="w-6 h-6 rounded-full bg-primary-100 dark:bg-primary-700 flex items-center justify-center text-sm font-medium">2</div>
              <span className="font-medium">Customer</span>
            </div>
            <PartyPicker
              value={selectedParty}
              onChange={(party) => {
                setSelectedParty(party);
                if (party) {
                  setIssueForm({ ...issueForm, holderName: party.legalName, holderMobile: party.contactPhone || '', holderEmail: party.contactEmail || '', emiratesId: party.emiratesId || '', city: party.city || '' });
                  setPartyMode('existing');
                }
              }}
              onCreateNew={(party) => {
                setSelectedParty(party);
                setIssueForm({ ...issueForm, holderName: party.legalName, holderMobile: party.contactPhone || '', holderEmail: party.contactEmail || '' });
                setPartyMode('new');
              }}
              placeholder="Search or create customer..."
            />
            {!selectedParty && (
              <Alert variant="info" className="text-sm"><User className="w-4 h-4 inline mr-2" />Search existing or create new. Linking to Party enables KYC tracking.</Alert>
            )}
          </div>

          {/* Step 3: Hierarchy */}
          <div className="space-y-3">
            <div className="flex items-center gap-2 text-primary-700 dark:text-neutral-200">
              <div className="w-6 h-6 rounded-full bg-primary-100 dark:bg-primary-700 flex items-center justify-center text-sm font-medium">3</div>
              <span className="font-medium">Hierarchy</span>
              <span className="text-xs text-neutral-400 dark:text-neutral-500">(Optional)</span>
            </div>
            {issueForm.programId ? (
              <div className="border border-neutral-200 dark:border-primary-800 rounded-lg p-3">
                <div className="flex items-center gap-2 text-sm text-neutral-600 dark:text-neutral-300 mb-2"><GitBranch className="w-4 h-4" />Place in hierarchy for reporting</div>
                <select className="w-full border border-neutral-300 dark:border-primary-700 rounded-lg px-3 py-2 text-sm" value={issueForm.hierarchyNodeId || ''} onChange={(e) => setIssueForm({ ...issueForm, hierarchyNodeId: e.target.value })}>
                  <option value="">No hierarchy (flat)</option>
                  <option value="node-uae">UAE Region</option>
                  <option value="node-dubai">├── Dubai</option>
                  <option value="node-abudhabi">├── Abu Dhabi</option>
                  <option value="node-sharjah">└── Sharjah</option>
                </select>
              </div>
            ) : (
              <p className="text-sm text-neutral-400 dark:text-neutral-500">Select program first</p>
            )}
          </div>

          {/* Step 4: Settings */}
          <div className="space-y-3">
            <div className="flex items-center gap-2 text-primary-700 dark:text-neutral-200">
              <div className="w-6 h-6 rounded-full bg-primary-100 dark:bg-primary-700 flex items-center justify-center text-sm font-medium">4</div>
              <span className="font-medium">Wallet Settings</span>
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="field-label block mb-1">Type</label>
                <select className="w-full border border-neutral-300 dark:border-primary-700 rounded-lg px-3 py-2" value={issueForm.walletType || 'CONSUMER'} onChange={(e) => setIssueForm({ ...issueForm, walletType: e.target.value })}>
                  <option value="CONSUMER">Consumer</option><option value="MERCHANT">Merchant</option><option value="AGENT">Agent</option><option value="CORPORATE">Corporate</option>
                </select>
              </div>
              <div>
                <label className="field-label block mb-1">Initial Load</label>
                <input type="number" className="w-full border border-neutral-300 dark:border-primary-700 rounded-lg px-3 py-2" placeholder="0.00" value={issueForm.initialLoadAmount || ''} onChange={(e) => setIssueForm({ ...issueForm, initialLoadAmount: e.target.value })} />
              </div>
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="field-label block mb-1">Daily Limit</label>
                <input type="number" className="w-full border border-neutral-300 dark:border-primary-700 rounded-lg px-3 py-2" placeholder={selectedProgramForIssue ? `Default: ${selectedProgramForIssue.dailySpendLimit}` : 'Default'} value={issueForm.dailyLimit || ''} onChange={(e) => setIssueForm({ ...issueForm, dailyLimit: e.target.value })} />
              </div>
              <div>
                <label className="field-label block mb-1">Monthly Limit</label>
                <input type="number" className="w-full border border-neutral-300 dark:border-primary-700 rounded-lg px-3 py-2" placeholder={selectedProgramForIssue ? `Default: ${selectedProgramForIssue.monthlySpendLimit}` : 'Default'} value={issueForm.monthlyLimit || ''} onChange={(e) => setIssueForm({ ...issueForm, monthlyLimit: e.target.value })} />
              </div>
            </div>
            <label className="flex items-center gap-2 p-3 border border-neutral-200 dark:border-primary-800 rounded-lg cursor-pointer hover:bg-neutral-50 dark:hover:bg-primary-800/50">
              <input type="checkbox" checked={issueForm.autoTriggerKyc || false} onChange={(e) => setIssueForm({ ...issueForm, autoTriggerKyc: e.target.checked })} className="rounded" />
              <div><span className="text-sm font-medium">Auto-trigger KYC</span><p className="text-xs text-neutral-500 dark:text-neutral-400">Start KYC if customer has ID</p></div>
            </label>
          </div>
        </div>
      </Modal>

      {/* Load Funds Modal */}
      <Modal isOpen={showLoadModal} onClose={() => setShowLoadModal(false)} title="Load Funds" subtitle={selectedWallet?.walletReference} size="sm"
        footer={<><Button variant="outline" onClick={() => setShowLoadModal(false)}>Cancel</Button><Button onClick={handleLoadFunds} disabled={actionLoading}>{actionLoading && <Loader2 className="w-4 h-4 animate-spin mr-2" />}Load</Button></>}>
        <div className="space-y-4">
          <Input label="Amount *" type="number" placeholder="0.00" value={loadForm.amount || ''} onChange={(e) => setLoadForm({ ...loadForm, amount: e.target.value })} />
          <div><label className="field-label block mb-1">Source</label>
            <select className="w-full border border-neutral-300 dark:border-primary-700 rounded-lg px-3 py-2" value={loadForm.source} onChange={(e) => setLoadForm({ ...loadForm, source: e.target.value })}>
              <option value="BANK_TRANSFER">Bank Transfer</option><option value="CARD">Card</option><option value="CASH">Cash</option>
            </select>
          </div>
          <Input label="Description" value={loadForm.description || ''} onChange={(e) => setLoadForm({ ...loadForm, description: e.target.value })} />
        </div>
      </Modal>

      {/* Withdraw Modal */}
      <Modal isOpen={showWithdrawModal} onClose={() => setShowWithdrawModal(false)} title="Withdraw" subtitle={selectedWallet?.walletReference} size="sm"
        footer={<><Button variant="outline" onClick={() => setShowWithdrawModal(false)}>Cancel</Button><Button onClick={handleWithdraw} disabled={actionLoading}>{actionLoading && <Loader2 className="w-4 h-4 animate-spin mr-2" />}Withdraw</Button></>}>
        <div className="space-y-4">
          <Input label="Amount *" type="number" placeholder="0.00" value={withdrawForm.amount || ''} onChange={(e) => setWithdrawForm({ ...withdrawForm, amount: e.target.value })} />
          <div><label className="field-label block mb-1">Destination</label>
            <select className="w-full border border-neutral-300 dark:border-primary-700 rounded-lg px-3 py-2" value={withdrawForm.destination} onChange={(e) => setWithdrawForm({ ...withdrawForm, destination: e.target.value })}>
              <option value="BANK_TRANSFER">Bank Transfer</option><option value="CASH">Cash</option>
            </select>
          </div>
          {selectedWallet && <p className="text-sm text-neutral-500 dark:text-neutral-400">Available: {formatCurrency(selectedWallet.availableBalance)}</p>}
        </div>
      </Modal>

      {/* Transfer Modal */}
      <Modal isOpen={showTransferModal} onClose={() => setShowTransferModal(false)} title="Transfer" subtitle={selectedWallet?.walletReference} size="sm"
        footer={<><Button variant="outline" onClick={() => setShowTransferModal(false)}>Cancel</Button><Button onClick={handleTransfer} disabled={actionLoading}>{actionLoading && <Loader2 className="w-4 h-4 animate-spin mr-2" />}Send</Button></>}>
        <div className="space-y-4">
          <Input label="To Wallet *" placeholder="WAL-XXXX-XXXXXXXX" value={transferForm.toWalletReference || ''} onChange={(e) => setTransferForm({ ...transferForm, toWalletReference: e.target.value })} />
          <Input label="Amount *" type="number" placeholder="0.00" value={transferForm.amount || ''} onChange={(e) => setTransferForm({ ...transferForm, amount: e.target.value })} />
          <Input label="Description" value={transferForm.description || ''} onChange={(e) => setTransferForm({ ...transferForm, description: e.target.value })} />
          {selectedWallet && <p className="text-sm text-neutral-500 dark:text-neutral-400">Available: {formatCurrency(selectedWallet.availableBalance)}</p>}
        </div>
      </Modal>

      {/* Bulk Load Modal */}
      <Modal isOpen={showBulkLoadModal} onClose={() => setShowBulkLoadModal(false)} title="Bulk Load" size="md"
        footer={<><Button variant="outline" onClick={() => setShowBulkLoadModal(false)}>Cancel</Button><Button onClick={handleBulkLoad} disabled={actionLoading}>{actionLoading && <Loader2 className="w-4 h-4 animate-spin mr-2" />}Process</Button></>}>
        <div className="space-y-4">
          <div><label className="field-label block mb-1">Program</label>
            <select className="w-full border border-neutral-300 dark:border-primary-700 rounded-lg px-3 py-2" value={bulkLoadForm.programId} onChange={(e) => setBulkLoadForm({ ...bulkLoadForm, programId: e.target.value })}>
              <option value="">All</option>{programs.map(p => <option key={p.id} value={p.id}>{p.programName}</option>)}
            </select>
          </div>
          <div><label className="field-label block mb-1">Data (CSV) *</label>
            <textarea className="w-full border border-neutral-300 dark:border-primary-700 rounded-lg px-3 py-2 h-32 font-mono text-sm" placeholder="walletRef,amount" value={bulkLoadForm.csvData} onChange={(e) => setBulkLoadForm({ ...bulkLoadForm, csvData: e.target.value })} />
          </div>
        </div>
      </Modal>

      {/* KYC Modal */}
      <Modal isOpen={showKycVerifyModal} onClose={() => setShowKycVerifyModal(false)} title="Verify KYC" subtitle={selectedWallet?.holderName} size="md"
        footer={<><Button variant="outline" onClick={() => setShowKycVerifyModal(false)}>Cancel</Button><Button onClick={handleVerifyKyc} disabled={actionLoading}>{actionLoading && <Loader2 className="w-4 h-4 animate-spin mr-2" />}Verify</Button></>}>
        <div className="space-y-4">
          <div><label className="field-label block mb-1">Method</label>
            <select className="w-full border border-neutral-300 dark:border-primary-700 rounded-lg px-3 py-2" value={kycVerifyForm.verificationMethod} onChange={(e) => setKycVerifyForm({ ...kycVerifyForm, verificationMethod: e.target.value })}>
              <option value="DOCUMENT">Document</option><option value="BIOMETRIC">Biometric</option><option value="MANUAL">Manual</option>
            </select>
          </div>
          <div><label className="field-label block mb-1">Document Type</label>
            <select className="w-full border border-neutral-300 dark:border-primary-700 rounded-lg px-3 py-2" value={kycVerifyForm.documentType} onChange={(e) => setKycVerifyForm({ ...kycVerifyForm, documentType: e.target.value })}>
              <option value="EMIRATES_ID">Emirates ID</option><option value="PASSPORT">Passport</option>
            </select>
          </div>
          <Input label="Document Number" value={kycVerifyForm.documentNumber || ''} onChange={(e) => setKycVerifyForm({ ...kycVerifyForm, documentNumber: e.target.value })} />
          <Input label="Expiry Date" type="date" value={kycVerifyForm.documentExpiryDate || ''} onChange={(e) => setKycVerifyForm({ ...kycVerifyForm, documentExpiryDate: e.target.value })} />
        </div>
      </Modal>

      {/* Block Modal */}
      <Modal isOpen={showBlockModal} onClose={() => setShowBlockModal(false)} title="Block Wallet" subtitle={selectedWallet?.walletReference} size="sm"
        footer={<><Button variant="outline" onClick={() => setShowBlockModal(false)}>Cancel</Button><Button variant="danger" onClick={handleBlockWallet} disabled={actionLoading}>{actionLoading && <Loader2 className="w-4 h-4 animate-spin mr-2" />}Block</Button></>}>
        <div className="space-y-4">
          <Alert variant="warning">Blocking prevents all transactions.</Alert>
          <Input label="Reason *" value={blockForm.reason || ''} onChange={(e) => setBlockForm({ ...blockForm, reason: e.target.value })} />
          <label className="flex items-center gap-2"><input type="checkbox" checked={blockForm.permanent} onChange={(e) => setBlockForm({ ...blockForm, permanent: e.target.checked })} /><span className="text-sm text-error-600 dark:text-error-300">Permanent</span></label>
        </div>
      </Modal>

      {/* Edit Wallet Modal */}
      <Modal isOpen={showEditWalletModal} onClose={() => setShowEditWalletModal(false)} title="Edit Wallet" subtitle={selectedWallet?.walletReference} size="md"
        footer={<><Button variant="outline" onClick={() => setShowEditWalletModal(false)}>Cancel</Button><Button onClick={handleUpdateWallet} disabled={actionLoading}>{actionLoading && <Loader2 className="w-4 h-4 animate-spin mr-2" />}Save</Button></>}>
        <div className="space-y-4">
          <Input label="Name" value={editWalletForm.walletName || ''} onChange={(e) => setEditWalletForm({ ...editWalletForm, walletName: e.target.value })} />
          <div className="grid grid-cols-2 gap-4">
            <Input label="Daily Limit" type="number" value={editWalletForm.dailyLimit?.toString() || ''} onChange={(e) => setEditWalletForm({ ...editWalletForm, dailyLimit: parseFloat(e.target.value) || undefined })} />
            <Input label="Monthly Limit" type="number" value={editWalletForm.monthlyLimit?.toString() || ''} onChange={(e) => setEditWalletForm({ ...editWalletForm, monthlyLimit: parseFloat(e.target.value) || undefined })} />
          </div>
        </div>
      </Modal>

      {/* Detail Modal */}
      <Modal isOpen={showDetailModal} onClose={() => { setShowDetailModal(false); setWalletDetail(null); }} title="Wallet Details" subtitle={selectedWallet?.walletReference} size="lg"
        footer={<Button variant="outline" onClick={() => { setShowDetailModal(false); setWalletDetail(null); }}>Close</Button>}>
        {walletDetail ? (
          <div className="space-y-6">
            <div className="flex items-center gap-4">
              <Avatar name={walletDetail.holderName} size="lg" status={walletDetail.kycVerified ? 'online' : 'away'} />
              <div className="flex-1">
                <h3 className="section-title">{walletDetail.holderName}</h3>
                <p className="text-sm text-neutral-500 dark:text-neutral-400">{walletDetail.holderMobile}</p>
                {walletDetail.partyId && <span className="text-xs text-primary-500">Linked to Party</span>}
                {walletDetail.hierarchyPath && <p className="text-xs text-neutral-400 dark:text-neutral-500 font-mono mt-1">{walletDetail.hierarchyPath}</p>}
              </div>
              <div className="flex flex-col gap-2">
                <Badge variant={walletStatusConfig[walletDetail.status]?.color as any}>{walletDetail.status}</Badge>
                <Badge variant={(kycStatusConfig[walletDetail.kycStatus] || kycStatusConfig.PENDING).color as any} size="sm">KYC: {walletDetail.kycStatus}</Badge>
              </div>
            </div>
            <div className="grid grid-cols-3 gap-4">
              {/* Phase 12 Task E: .stat-value-xs / .stat-value-* replace the raw `text-xl font-semibold` hand-rolls. */}
              <Card padding="sm" className="bg-primary-50 dark:bg-primary-800/40"><p className="text-xs text-neutral-500 dark:text-neutral-400">Balance</p><p className="stat-value-xs">{formatCurrency(walletDetail.currentBalance)}</p></Card>
              <Card padding="sm" className="bg-success-50 dark:bg-success-500/10"><p className="text-xs text-neutral-500 dark:text-neutral-400">Available</p><p className="stat-value-xs text-success-600 dark:text-success-300">{formatCurrency(walletDetail.availableBalance)}</p></Card>
              <Card padding="sm"><p className="text-xs text-neutral-500 dark:text-neutral-400">Monthly Spent</p><p className="stat-value-xs">{formatCurrency(walletDetail.monthlySpent || 0)}</p></Card>
            </div>
            <div>
              <h4 className="font-medium mb-3">Limits</h4>
              <div className="space-y-3">
                <div><div className="flex justify-between text-sm mb-1"><span>Daily</span><span>{formatCurrency(walletDetail.dailySpent)} / {formatCurrency(walletDetail.dailyLimit)}</span></div><ProgressBar value={(walletDetail.dailySpent / walletDetail.dailyLimit) * 100} size="sm" /></div>
                <div><div className="flex justify-between text-sm mb-1"><span>Monthly</span><span>{formatCurrency(walletDetail.monthlySpent || 0)} / {formatCurrency(walletDetail.monthlyLimit)}</span></div><ProgressBar value={((walletDetail.monthlySpent || 0) / walletDetail.monthlyLimit) * 100} size="sm" /></div>
              </div>
            </div>
            {walletDetail.recentTransactions?.length > 0 && (
              <div>
                <h4 className="font-medium mb-3">Recent Transactions</h4>
                <div className="space-y-2 max-h-48 overflow-y-auto">
                  {walletDetail.recentTransactions.map(txn => (
                    <div key={txn.id} className="flex items-center justify-between p-2 bg-neutral-50 dark:bg-primary-950 rounded">
                      <div className="flex items-center gap-2">
                        {txn.type.includes('CREDIT') || txn.type.includes('TOPUP') ? <ArrowDownRight className="w-4 h-4 text-success-600 dark:text-success-300" /> : <ArrowUpRight className="w-4 h-4 text-error-600 dark:text-error-300" />}
                        <div><p className="text-sm">{txn.description || txn.type}</p><p className="text-xs text-neutral-400 dark:text-neutral-500">{new Date(txn.transactionDate).toLocaleString()}</p></div>
                      </div>
                      <span className={cn('font-medium', txn.type.includes('CREDIT') || txn.type.includes('TOPUP') ? 'text-success-600 dark:text-success-300' : 'text-error-600 dark:text-error-300')}>
                        {txn.type.includes('CREDIT') || txn.type.includes('TOPUP') ? '+' : '-'}{formatCurrency(txn.amount)}
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        ) : (
          <div className="flex items-center justify-center py-8"><Loader2 className="w-6 h-6 animate-spin" /></div>
        )}
      </Modal>
    </Page>
  );
};

export default WalletPage;