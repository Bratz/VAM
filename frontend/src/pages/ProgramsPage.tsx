import React, { useState, useEffect, useCallback } from 'react';
import {
  Percent, Search, Download, RefreshCw, Plus, Building2, CreditCard, Wallet, Shield, Banknote,
  Eye, Edit, MoreHorizontal, CheckCircle, XCircle, Clock, Copy, Landmark, Trash2,
  ChevronRight, Loader2, Layers, X, PauseCircle, PlayCircle, TrendingUp, Hash,
  GitBranch, Zap, Gift, Smartphone, DollarSign, FolderTree, Info, Sparkles, Settings,
} from 'lucide-react';
import { Card, Badge, Button , StatusIconBadge, StatTile } from '../components/ui';
import { HeroMetricCard } from '../components/ui/HeroMetricCard';
import { CurrencyPicker } from '../components/ui/CurrencyPicker';
import { Modal } from '../components/ui/enhanced';
import { TileAmount } from '../components/TileAmount';
import { formatCurrency, formatDate, cn } from '../utils';
import { HIERARCHY_TEMPLATES, getTemplatesForProgramType, getRecommendedTemplate, HierarchyLevelConfig } from '../config/templateHierarchy';
import { usePageHeaderActions } from '../context/PageHeaderContext';
import { Page } from '../components/layout/Page';
import { PageHeader } from '../components/layout/PageHeader';
import { StatStrip } from '../components/layout/StatStrip';
import { ScopeSelector } from '../components/layout/ScopeSelector';

// ============================================================================
// API & TYPES
// ============================================================================

const API_BASE = '/api/v1/programs';
const CORPORATES_API = '/api/v1/corporates';
interface ApiResponse<T> { success: boolean; data: T; message?: string; }

async function fetchApi<T>(url: string, options?: RequestInit, base = API_BASE): Promise<ApiResponse<T>> {
  try {
    const response = await fetch(base + url, { headers: { 'Content-Type': 'application/json', ...options?.headers }, ...options });
    const data = await response.json();
    if (!response.ok) {
      console.error('API Error:', response.status, data);
      return { success: false, data: null as unknown as T, message: data.message || data.error || `HTTP ${response.status}` };
    }
    return data;
  } catch (error) {
    console.error('API Error:', error);
    return { success: false, data: null as unknown as T, message: error instanceof Error ? error.message : 'Network error' };
  }
}

// ============================================================================
// HELPER FUNCTIONS - Consistent Response Extraction (MVC Pattern)
// ============================================================================

/**
 * Safely extract array from API response (handles various response formats)
 * Ensures page loads even if API returns unexpected format or empty data
 */
const extractArray = <T,>(response: ApiResponse<T[] | { content: T[] } | { programs: T[] } | { corporates: T[] }>): T[] => {
  if (!response) return [];
  
  // Handle { success: true, data: [...] }
  if (response.success && response.data) {
    if (Array.isArray(response.data)) return response.data;
    if ('content' in response.data && Array.isArray((response.data as any).content)) {
      return (response.data as any).content;
    }
    if ('programs' in response.data && Array.isArray((response.data as any).programs)) {
      return (response.data as any).programs;
    }
    if ('corporates' in response.data && Array.isArray((response.data as any).corporates)) {
      return (response.data as any).corporates;
    }
  }
  
  // Handle direct array
  if (Array.isArray(response)) return response as unknown as T[];
  
  // Handle { content: [...] } directly
  if ('content' in response && Array.isArray((response as any).content)) {
    return (response as any).content;
  }
  
  return [];
};

/**
 * Safe extraction of stats from response
 */
const _extractStats = <T,>(response: ApiResponse<{ stats: T } | T>, defaultStats: T): T => {
  if (!response || !response.success) return defaultStats;

  if (response.data && typeof response.data === 'object') {
    if ('stats' in response.data) return (response.data as any).stats || defaultStats;
    return response.data as T;
  }

  return defaultStats;
};

// ============================================================================
// CORPORATE TYPE FOR SELECTOR
// ============================================================================

interface Corporate {
  id: string;
  corporateId: string;
  legalName: string;
  tradeName?: string;
  status: string;
}

const CHARGES_API_BASE = '/api/v1/program-charges';

// ============================================================================
// WALLET CHARGES TYPES (from ChargeConfiguration API)
// ============================================================================

interface ChargeDetail {
  chargeCode: string;
  chargeName: string;
  percentage: number;
  fixed: number;
  minimum?: number;
  maximum?: number;
  isWaived: boolean;
  hasOverride: boolean;
  source: 'BASE' | 'OVERRIDE' | 'NONE';
}

interface WalletChargesResponse {
  programId: string;
  programCode: string;
  topup: ChargeDetail;
  withdrawal: ChargeDetail;
  transfer: ChargeDetail;
  issuance: ChargeDetail;
  monthly: ChargeDetail;
  inactivity: ChargeDetail;
}

interface WalletChargesRequest {
  programId?: string;
  // Transaction fees
  topupFeePercent?: number;
  topupFeeFlat?: number;
  withdrawalFeePercent?: number;
  withdrawalFeeFlat?: number;
  transferFeePercent?: number;
  transferFeeFlat?: number;
  // Fixed fees
  issuanceFee?: number;
  monthlyFee?: number;
  inactivityFee?: number;
  // Waivers - ALL fees
  waiveTopup?: boolean;
  waiveWithdrawal?: boolean;
  waiveTransfer?: boolean;
  waiveIssuance?: boolean;
  waiveMonthly?: boolean;
  waiveInactivity?: boolean;
}

// Mock wallet charges for fallback
const mockWalletCharges: WalletChargesResponse = {
  programId: '',
  programCode: '',
  topup: { chargeCode: 'WALLET_TOPUP', chargeName: 'Wallet Topup Fee', percentage: 1.5, fixed: 0, minimum: 1, maximum: 100, isWaived: false, hasOverride: false, source: 'BASE' },
  withdrawal: { chargeCode: 'WALLET_WITHDRAWAL', chargeName: 'Wallet Withdrawal Fee', percentage: 2.0, fixed: 5, minimum: 5, maximum: 200, isWaived: false, hasOverride: false, source: 'BASE' },
  transfer: { chargeCode: 'WALLET_TRANSFER', chargeName: 'Wallet Transfer Fee', percentage: 0.5, fixed: 1, minimum: 1, maximum: 50, isWaived: false, hasOverride: false, source: 'BASE' },
  issuance: { chargeCode: 'WALLET_ISSUANCE', chargeName: 'Wallet Issuance Fee', percentage: 0, fixed: 10, isWaived: false, hasOverride: false, source: 'BASE' },
  monthly: { chargeCode: 'WALLET_MONTHLY', chargeName: 'Wallet Monthly Fee', percentage: 0, fixed: 5, isWaived: false, hasOverride: false, source: 'BASE' },
  inactivity: { chargeCode: 'WALLET_INACTIVITY', chargeName: 'Wallet Inactivity Fee', percentage: 0, fixed: 10, isWaived: false, hasOverride: false, source: 'BASE' },
};

type ProgramType = 'COLLECTION' | 'VIBAN' | 'ESCROW' | 'WALLET' | 'IHB' | 'PAYABLES' | 'RECEIVABLES' | 'LOYALTY' | 'GIFT_CARD' | 'CORPORATE_CARD' | 'MOBILE_MONEY';
type ProgramStatus = 'ACTIVE' | 'INACTIVE' | 'SUSPENDED' | 'PENDING_APPROVAL' | 'CLOSED';
type VibanGenerationStrategy = 'SEQUENTIAL' | 'RANDOM' | 'HIERARCHY_ENCODED';

interface Program {
  id: string; programCode: string; programName: string; programType: ProgramType;
  programTypeLabel?: string; description?: string; corporateId: string; corporateName?: string;
  physicalAccountId: string; physicalAccountNumber?: string; currencyCode: string;
  vaPrefix?: string; vaFormat?: string; maxVirtualAccounts?: number; autoReconciliation: boolean;
  settlementFrequency?: string; settlementTime?: string; minBalanceThreshold?: number;
  vibanEnabled: boolean; walletEnabled: boolean; escrowEnabled: boolean; ihbEnabled: boolean;
  // NEW: Hierarchy Support (7-level)
  hierarchyEnabled?: boolean;
  hierarchyDepth?: number;
  defaultHierarchyTemplate?: string;
  rootHierarchyNodeId?: string;
  // NEW: VIBAN Pool Settings
  defaultVibanPoolId?: string;
  vibanGenerationStrategy?: VibanGenerationStrategy;
  vibanPrefix?: string;
  vibanBankCode?: string;
  // NEW: Balance Aggregation
  balanceAggregationIntervalMinutes?: number;
  realtimeBalancePropagation?: boolean;
  // NEW: Additional Program Type Flags
  loyaltyEnabled?: boolean;
  giftCardEnabled?: boolean;
  corporateCardEnabled?: boolean;
  mobileMoneyEnabled?: boolean;
  // NEW: Wallet Config
  defaultPerTransactionLimit?: number;
  defaultDailyLimit?: number;
  defaultMonthlyLimit?: number;
  defaultMaxBalance?: number;
  kycRequired?: boolean;
  minKycLevel?: number;
  allowTopup?: boolean;
  allowWithdrawal?: boolean;
  allowTransfer?: boolean;
  allowPayment?: boolean;
  issuanceFee?: number;
  monthlyFee?: number;
  // Status
  status: ProgramStatus; statusLabel?: string; statusVariant?: string;
  effectiveFrom?: string; effectiveTo?: string;
  virtualAccountCount: number; activeVirtualAccountCount: number; totalBalance: number;
  createdAt: string; updatedAt?: string; createdBy?: string; version?: number;
}

interface ProgramDetail {
  program: Program;
  corporate?: { id: string; corporateId: string; legalName: string; tradeName?: string; status: string };
  physicalAccount?: { id: string; accountNumber: string; accountName: string; bankName: string; currencyCode: string; currentBalance: number; status: string };
  recentVirtualAccounts: { id: string; vaNumber: string; viban?: string; vaName: string; currentBalance: number; status: string; createdAt: string }[];
  usageStats: { totalTransactions: number; todayTransactions: number; totalVolume: number; todayVolume: number; averageBalance: number; lastTransactionAt?: string };
  activityLog: { action: string; user: string; timestamp: string; type: string; details: string }[];
}

interface ProgramStats {
  totalPrograms: number; activePrograms: number; inactivePrograms: number; pendingPrograms: number;
  collectionPrograms: number; vibanPrograms: number; escrowPrograms: number;
  walletPrograms: number; ihbPrograms: number; payablesPrograms: number;
  // NEW: Additional program type counts
  loyaltyPrograms?: number;
  giftCardPrograms?: number;
  corporateCardPrograms?: number;
  mobileMoneyPrograms?: number;
  // NEW: Feature counts
  hierarchyEnabledPrograms?: number;
  vibanEnabledPrograms?: number;
  totalVirtualAccounts: number; totalBalance: number;
}

interface ProgramListResponse { programs: Program[]; totalCount: number; page: number; pageSize: number; stats: ProgramStats; }

// NEW: Settlement VA interface for hierarchy tab
interface SettlementVa {
  id: string;
  vaNumber: string;
  vaName: string;
  specialType: 'SETTLEMENT' | 'EXCEPTION';
  currency: string;
  currentBalance: number;
  hierarchyPath?: string;
  hierarchyLevel?: number;
  status: string;
  createdAt?: string;
}

// NEW: VIBAN Pool interface for VIBAN tab
interface VibanPool {
  id: string;
  poolName: string;
  poolCode: string;
  bankCode: string;
  prefix: string;
  totalVibans: number;
  availableVibans: number;
  usedVibans: number;
  reservedVibans: number;
  status: string;
}

const programApi = {
  getAll: (params?: Record<string, string>) => {
    const query = params ? '?' + new URLSearchParams(params).toString() : '';
    return fetchApi<ProgramListResponse>(query);
  },
  getDetail: (id: string) => fetchApi<ProgramDetail>('/' + id + '/detail'),
  getStats: () => fetchApi<ProgramStats>('/stats'),
  create: (data: Partial<Program>) => fetchApi<Program>('', { method: 'POST', body: JSON.stringify(data) }),
  update: (id: string, data: Partial<Program>) => fetchApi<Program>('/' + id, { method: 'PUT', body: JSON.stringify(data) }),
  updateStatus: (id: string, status: string, reason?: string) => fetchApi<Program>('/' + id + '/status', { method: 'PATCH', body: JSON.stringify({ status, reason }) }),
  delete: (id: string) => fetchApi<void>('/' + id, { method: 'DELETE' }),
};

// NEW: Treasury API for Settlement VAs
const TREASURY_API = '/api/v1/treasury/settlement-vas';

const treasuryApi = {
  getSettlementVas: (programId: string) =>
    fetchApi<{ settlementVas: SettlementVa[]; exceptionVas: SettlementVa[] }>(`/program/${programId}`, {}, TREASURY_API),
  initializeHierarchy: (data: { programId: string; templateType: string }) =>
    fetchApi<any>('/initialize', { method: 'POST', body: JSON.stringify(data) }, TREASURY_API),
};

// Hierarchy Level Config API
const HIERARCHY_CONFIG_API = '/api/v1/programs';

interface LevelConfigPayload {
  levelNumber: number;
  levelName: string;
  dimensionType: string;
  isRequired?: boolean;
  allowedValues?: string[];
  description?: string;
  icon?: string;
}

const hierarchyLevelApi = {
  saveLevelConfigs: (programId: string, levels: LevelConfigPayload[]) =>
    fetchApi<any[]>(`/${programId}/hierarchy/config`, {
      method: 'POST',
      body: JSON.stringify({ levels })
    }, HIERARCHY_CONFIG_API),
};

// NEW: VIBAN Pool API
const VIBAN_POOL_API = '/api/v1/viban-pools';

const vibanPoolApi = {
  getAll: () => fetchApi<VibanPool[]>('', {}, VIBAN_POOL_API),
  getById: (id: string) => fetchApi<VibanPool>(`/${id}`, {}, VIBAN_POOL_API),
  getByProgram: (programId: string) => fetchApi<VibanPool>(`/program/${programId}`, {}, VIBAN_POOL_API),
};

// VIBAN Generation Strategy Config
const vibanStrategyConfig: Record<string, { label: string; description: string; icon: React.ElementType }> = {
  SEQUENTIAL: { label: 'Sequential', description: 'VIBANs are generated in sequential order (001, 002, 003...)', icon: TrendingUp },
  RANDOM: { label: 'Random', description: 'VIBANs are generated with random unique identifiers', icon: Hash },
  HIERARCHY_ENCODED: { label: 'Hierarchy Encoded', description: 'VIBAN includes encoded hierarchy path for routing', icon: GitBranch },
};

// ============================================================================
// CONFIGURATION
// ============================================================================

const programTypeConfig: Record<string, { label: string; icon: React.ElementType; color: string; bgColor: string; description: string }> = {
  COLLECTION: { label: 'Collection', icon: CreditCard, color: 'text-info-600 dark:text-info-300', bgColor: 'bg-info-50 dark:bg-info-500/10', description: 'Receivables collection' },
  VIBAN: { label: 'VIBAN', icon: Hash, color: 'text-accent-600 dark:text-accent-300', bgColor: 'bg-accent-50 dark:bg-accent-500/10', description: 'Virtual IBAN' },
  ESCROW: { label: 'Escrow', icon: Shield, color: 'text-success-600 dark:text-success-300', bgColor: 'bg-success-50 dark:bg-success-500/10', description: 'Digital escrow' },
  WALLET: { label: 'Wallet', icon: Wallet, color: 'text-warning-600 dark:text-warning-300', bgColor: 'bg-warning-50 dark:bg-warning-500/10', description: 'Prepaid wallet' },
  IHB: { label: 'In-House Bank', icon: Building2, color: 'text-primary-600 dark:text-primary-200', bgColor: 'bg-primary-100 dark:bg-primary-700', description: 'In-house banking' },
  PAYABLES: { label: 'Payables', icon: Banknote, color: 'text-error-600 dark:text-error-300', bgColor: 'bg-error-50 dark:bg-error-500/10', description: 'Payables management' },
  // NEW: Additional Program Types
  RECEIVABLES: { label: 'Receivables', icon: DollarSign, color: 'text-success-600 dark:text-success-300', bgColor: 'bg-success-50 dark:bg-success-500/10', description: 'Receivables management' },
  LOYALTY: { label: 'Loyalty', icon: TrendingUp, color: 'text-cat-4', bgColor: 'bg-cat-4-soft dark:bg-cat-4/15', description: 'Loyalty/rewards program' },
  GIFT_CARD: { label: 'Gift Card', icon: Gift, color: 'text-cat-2', bgColor: 'bg-cat-2-soft dark:bg-cat-2/15', description: 'Gift card program' },
  CORPORATE_CARD: { label: 'Corporate Card', icon: CreditCard, color: 'text-cat-1', bgColor: 'bg-cat-1-soft dark:bg-cat-1/15', description: 'Corporate card program' },
  MOBILE_MONEY: { label: 'Mobile Money', icon: Smartphone, color: 'text-cat-3', bgColor: 'bg-cat-3-soft dark:bg-cat-3/15', description: 'Mobile money/agent banking' },
};

type BadgeVariant = 'success' | 'error' | 'warning' | 'info' | 'neutral';
const statusConfig: Record<string, { label: string; variant: BadgeVariant; icon: React.ElementType }> = {
  ACTIVE: { label: 'Active', variant: 'success', icon: CheckCircle },
  INACTIVE: { label: 'Inactive', variant: 'neutral', icon: XCircle },
  SUSPENDED: { label: 'Suspended', variant: 'warning', icon: PauseCircle },
  PENDING_APPROVAL: { label: 'Pending', variant: 'info', icon: Clock },
  CLOSED: { label: 'Closed', variant: 'error', icon: XCircle },
};

const settlementFrequencyConfig: Record<string, { label: string; description: string }> = {
  REAL_TIME: { label: 'Real-time', description: 'Immediate' },
  HOURLY: { label: 'Hourly', description: 'Every hour' },
  DAILY: { label: 'Daily', description: 'Once per day' },
  WEEKLY: { label: 'Weekly', description: 'Once per week' },
  MONTHLY: { label: 'Monthly', description: 'Once per month' },
};

// ============================================================================
// PROGRAM TYPE → FEATURE FLAG MAPPING
// Auto-enables the corresponding feature flag when a program type is selected
// Based on backend design where features are orthogonal to types but have
// natural associations (e.g., WALLET type → walletEnabled)
// ============================================================================

type FeatureFlags = {
  vibanEnabled?: boolean;
  walletEnabled?: boolean;
  escrowEnabled?: boolean;
  ihbEnabled?: boolean;
  hierarchyEnabled?: boolean;
  loyaltyEnabled?: boolean;
  giftCardEnabled?: boolean;
  corporateCardEnabled?: boolean;
  mobileMoneyEnabled?: boolean;
};

const PROGRAM_TYPE_FEATURE_MAP: Record<ProgramType, FeatureFlags> = {
  // Core program types
  COLLECTION: { hierarchyEnabled: true },
  VIBAN: { vibanEnabled: true },
  ESCROW: { escrowEnabled: true },
  WALLET: { walletEnabled: true },
  IHB: { ihbEnabled: true, hierarchyEnabled: true },
  PAYABLES: { hierarchyEnabled: true },
  RECEIVABLES: { hierarchyEnabled: true },
  // Extended program types
  LOYALTY: { loyaltyEnabled: true, walletEnabled: true },
  GIFT_CARD: { giftCardEnabled: true, walletEnabled: true },
  CORPORATE_CARD: { corporateCardEnabled: true, walletEnabled: true },
  MOBILE_MONEY: { mobileMoneyEnabled: true, walletEnabled: true },
};

// ============================================================================
// DETAIL MODAL
// ============================================================================

interface ProgramDetailModalProps {
  program: Program | null;
  onClose: () => void;
  onEdit: (program: Program) => void;
  onStatusChange: (programId: string, status: string) => void;
}

const ProgramDetailModal: React.FC<ProgramDetailModalProps> = ({ program, onClose, onEdit, onStatusChange }) => {
  const [activeTab, setActiveTab] = useState<'overview' | 'hierarchy' | 'viban' | 'wallet' | 'accounts' | 'config' | 'history'>('overview');
  const [detail, setDetail] = useState<ProgramDetail | null>(null);
  const [loading, setLoading] = useState(false);
  // NEW: State for hierarchy tab
  const [settlementVas, setSettlementVas] = useState<SettlementVa[]>([]);
  const [exceptionVas, setExceptionVas] = useState<SettlementVa[]>([]);
  const [hierarchyLoading, setHierarchyLoading] = useState(false);
  // NEW: State for VIBAN pool tab
  const [vibanPool, setVibanPool] = useState<VibanPool | null>(null);
  const [vibanLoading, setVibanLoading] = useState(false);

  // Wallet Charges state
  const [walletCharges, setWalletCharges] = useState<WalletChargesResponse | null>(null);
  const [walletChargesLoading, setWalletChargesLoading] = useState(false);

  useEffect(() => {
    if (program) {
      setLoading(true);
      programApi.getDetail(program.id).then(res => {
        if (res.success) setDetail(res.data);
      }).finally(() => setLoading(false));
    }
  }, [program]);

  // NEW: Fetch Settlement VAs when hierarchy tab is active
  useEffect(() => {
    if (program && activeTab === 'hierarchy') {
      setHierarchyLoading(true);
      treasuryApi.getSettlementVas(program.id).then(res => {
        if (res.success && res.data) {
          setSettlementVas(res.data.settlementVas || []);
          setExceptionVas(res.data.exceptionVas || []);
        }
      }).finally(() => setHierarchyLoading(false));
    }
  }, [program, activeTab]);

  // NEW: Fetch VIBAN Pool when viban tab is active
  useEffect(() => {
    if (program && activeTab === 'viban' && program.vibanEnabled) {
      setVibanLoading(true);
      if (program.defaultVibanPoolId) {
        vibanPoolApi.getById(program.defaultVibanPoolId).then(res => {
          if (res.success && res.data) {
            setVibanPool(res.data);
          }
        }).finally(() => setVibanLoading(false));
      } else {
        vibanPoolApi.getByProgram(program.id).then(res => {
          if (res.success && res.data) {
            setVibanPool(res.data);
          }
        }).finally(() => setVibanLoading(false));
      }
    }
  }, [program, activeTab]);

  // Fetch wallet charges when viewing a wallet program
  useEffect(() => {
    if (program && (program.walletEnabled || program.programType === 'WALLET')) {
      setWalletChargesLoading(true);
      fetchApi<WalletChargesResponse>(`/programs/${program.id}/wallet`, {}, CHARGES_API_BASE)
        .then(res => {
          if (res.success && res.data) {
            setWalletCharges(res.data);
          } else {
            // Use mock data as fallback
            setWalletCharges({ ...mockWalletCharges, programId: program.id, programCode: program.programCode });
          }
        })
        .finally(() => setWalletChargesLoading(false));
    } else {
      setWalletCharges(null);
    }
  }, [program]);

  if (!program) return null;

  const TypeIcon = programTypeConfig[program.programType]?.icon || Layers;
  // NEW: Check if hierarchy is initialized
  const hasHierarchy = program.hierarchyEnabled && program.rootHierarchyNodeId;

  const tabs = [
    { id: 'overview' as const, label: 'Overview' },
    { id: 'hierarchy' as const, label: 'Hierarchy', badge: program.hierarchyEnabled && !hasHierarchy ? 'Setup' : undefined },
    { id: 'viban' as const, label: 'VIBAN Pool', show: program.vibanEnabled },
    { id: 'wallet' as const, label: 'Wallet Fees', show: program.walletEnabled || program.programType === 'WALLET' },  // NEW
    { id: 'accounts' as const, label: 'Virtual Accounts', count: program.virtualAccountCount },
    { id: 'config' as const, label: 'Configuration' },
    { id: 'history' as const, label: 'Activity' },
  ].filter(tab => tab.show !== false);

  return (
    <Modal isOpen={!!program} onClose={onClose} size="xl" title="">
      <div className="flex flex-col h-full max-h-[85vh]">
        {/* Header */}
        <div className="flex items-start gap-4 pb-4 border-b border-neutral-200 dark:border-primary-800">
          <div className={cn('w-14 h-14 rounded-xl flex items-center justify-center', programTypeConfig[program.programType]?.bgColor || 'bg-neutral-100 dark:bg-primary-800')}>
            <TypeIcon className={cn('w-7 h-7', programTypeConfig[program.programType]?.color || 'text-neutral-600 dark:text-neutral-300')} />
          </div>
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-2">
              <h2 className="section-title truncate">{program.programName}</h2>
              <Badge variant={statusConfig[program.status]?.variant}>{statusConfig[program.status]?.label}</Badge>
              {hasHierarchy && <Badge variant="info" size="sm"><GitBranch className="w-3 h-3 mr-1" />Hierarchy</Badge>}
              {program.realtimeBalancePropagation && <Badge variant="success" size="sm"><Zap className="w-3 h-3 mr-1" />Real-time</Badge>}
            </div>
            <div className="flex items-center gap-4 mt-1 text-sm text-neutral-500 dark:text-neutral-400">
              <span className="font-mono">{program.programCode}</span>
              <span>•</span>
              <span>{programTypeConfig[program.programType]?.label}</span>
              <span>•</span>
              <span>{program.currencyCode}</span>
            </div>
            {program.corporateName && (
              <p className="mt-1 text-sm text-neutral-600 flex items-center gap-1 dark:text-neutral-300">
                <Building2 className="w-3 h-3" /> {program.corporateName}
              </p>
            )}
          </div>
          <div className="flex gap-2">
            <Button variant="outline" size="sm" onClick={() => onEdit(program)} leftIcon={<Edit className="w-4 h-4" />}>Edit</Button>
            <Button variant="outline" size="sm" onClick={onClose}><X className="w-4 h-4" /></Button>
          </div>
        </div>

        {/* Stats Row */}
        <div className="grid grid-cols-4 gap-4 py-4 border-b border-neutral-200 dark:border-primary-800">
          <div className="text-center">
            <p className="stat-value-sm">{program.virtualAccountCount}</p>
            <p className="text-xs text-neutral-500 dark:text-neutral-400">Virtual Accounts</p>
          </div>
          <div className="text-center">
            <p className="stat-value-success">{program.activeVirtualAccountCount}</p>
            <p className="text-xs text-neutral-500 dark:text-neutral-400">Active VAs</p>
          </div>
          <div className="text-center">
            <p className="stat-value-sm">{formatCurrency(program.totalBalance, program.currencyCode)}</p>
            <p className="text-xs text-neutral-500 dark:text-neutral-400">Total Balance</p>
          </div>
          <div className="text-center">
            <p className="stat-value-sm">{program.maxVirtualAccounts || '∞'}</p>
            <p className="text-xs text-neutral-500 dark:text-neutral-400">Max VAs</p>
          </div>
        </div>

        {/* Tabs */}
        <div className="flex gap-4 border-b border-neutral-200 dark:border-primary-800">
          {tabs.map(tab => (
            <button key={tab.id} onClick={() => setActiveTab(tab.id)}
              className={cn('py-3 px-1 text-sm font-medium border-b-2 -mb-px transition-colors flex items-center gap-1.5',
                activeTab === tab.id ? 'border-primary-500 text-primary-900 dark:text-neutral-50' : 'border-transparent text-neutral-500 hover:text-neutral-700 dark:text-neutral-400 dark:hover:text-neutral-200')}>
              {tab.id === 'hierarchy' && <GitBranch className="w-3.5 h-3.5" />}
              {tab.id === 'viban' && <Hash className="w-3.5 h-3.5" />}
              {tab.label}
              {tab.count !== undefined && <span className="ml-1.5 px-1.5 py-0.5 text-xs bg-neutral-100 rounded dark:bg-primary-800">{tab.count}</span>}
              {tab.badge && <Badge variant="warning" size="sm">{tab.badge}</Badge>}
            </button>
          ))}
        </div>

        {/* Content */}
        <div className="flex-1 overflow-y-auto py-4">
          {loading && <div className="flex items-center justify-center py-12"><Loader2 className="w-6 h-6 animate-spin text-primary-500" /></div>}

          {/* Overview Tab */}
          {!loading && activeTab === 'overview' && (
            <div className="grid grid-cols-2 gap-6">
              <div className="space-y-4">
                <h3 className="text-sm font-semibold text-primary-900 dark:text-neutral-50">Program Details</h3>
                <Card padding="sm" className="space-y-3">
                  <div className="flex justify-between"><span className="text-sm text-neutral-500 dark:text-neutral-400">Program Code</span><span className="text-sm font-mono text-primary-900 dark:text-neutral-50">{program.programCode}</span></div>
                  <div className="flex justify-between"><span className="text-sm text-neutral-500 dark:text-neutral-400">Type</span><Badge variant="neutral">{programTypeConfig[program.programType]?.label}</Badge></div>
                  <div className="flex justify-between"><span className="text-sm text-neutral-500 dark:text-neutral-400">Currency</span><span className="text-sm text-primary-900 dark:text-neutral-50">{program.currencyCode}</span></div>
                  <div className="flex justify-between"><span className="text-sm text-neutral-500 dark:text-neutral-400">VA Prefix</span><span className="text-sm font-mono text-primary-900 dark:text-neutral-50">{program.vaPrefix || '-'}</span></div>
                  <div className="flex justify-between"><span className="text-sm text-neutral-500 dark:text-neutral-400">Created</span><span className="text-sm text-primary-900 dark:text-neutral-50">{formatDate(program.createdAt)}</span></div>
                  {program.effectiveFrom && (
                    <div className="flex justify-between"><span className="text-sm text-neutral-500 dark:text-neutral-400">Effective From</span><span className="text-sm text-primary-900 dark:text-neutral-50">{formatDate(program.effectiveFrom)}</span></div>
                  )}
                  {program.effectiveTo && (
                    <div className="flex justify-between"><span className="text-sm text-neutral-500 dark:text-neutral-400">Effective To</span><span className="text-sm text-primary-900 dark:text-neutral-50">{formatDate(program.effectiveTo)}</span></div>
                  )}
                </Card>
                {/* Description */}
                {program.description && (
                  <>
                    <h3 className="text-sm font-semibold text-primary-900 dark:text-neutral-50">Description</h3>
                    <Card padding="sm">
                      <p className="text-sm text-neutral-700 dark:text-neutral-200">{program.description}</p>
                    </Card>
                  </>
                )}
                {detail?.physicalAccount && (
                  <>
                    <h3 className="text-sm font-semibold text-primary-900 dark:text-neutral-50">Physical Account</h3>
                    <Card padding="sm" className="space-y-3">
                      <div className="flex justify-between"><span className="text-sm text-neutral-500 dark:text-neutral-400">Account</span><span className="text-sm font-mono text-primary-900 dark:text-neutral-50">{detail.physicalAccount.accountNumber}</span></div>
                      <div className="flex justify-between"><span className="text-sm text-neutral-500 dark:text-neutral-400">Bank</span><span className="text-sm text-primary-900 dark:text-neutral-50">{detail.physicalAccount.bankName}</span></div>
                      <div className="flex justify-between"><span className="text-sm text-neutral-500 dark:text-neutral-400">Balance</span><span className="text-sm font-medium text-primary-900 dark:text-neutral-50">{formatCurrency(detail.physicalAccount.currentBalance, detail.physicalAccount.currencyCode)}</span></div>
                    </Card>
                  </>
                )}
              </div>
              <div className="space-y-4">
                <h3 className="text-sm font-semibold text-primary-900 dark:text-neutral-50">Core Features</h3>
                <Card padding="sm" className="space-y-3">
                  {[
                    { label: 'Auto Reconciliation', value: program.autoReconciliation },
                    { label: 'Hierarchy Enabled', value: program.hierarchyEnabled },
                    { label: 'VIBAN Enabled', value: program.vibanEnabled },
                    { label: 'Wallet Enabled', value: program.walletEnabled },
                    { label: 'Escrow Enabled', value: program.escrowEnabled },
                    { label: 'IHB Enabled', value: program.ihbEnabled },
                  ].map(f => (
                    <div key={f.label} className="flex items-center justify-between">
                      <span className="text-sm text-neutral-500 dark:text-neutral-400">{f.label}</span>
                      {f.value ? <CheckCircle className="w-4 h-4 text-success-500" /> : <XCircle className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />}
                    </div>
                  ))}
                </Card>
                {/* Extended Program Types */}
                {(program.loyaltyEnabled || program.giftCardEnabled || program.corporateCardEnabled || program.mobileMoneyEnabled) && (
                  <>
                    <h3 className="text-sm font-semibold text-primary-900 dark:text-neutral-50">Extended Features</h3>
                    <Card padding="sm" className="space-y-3">
                      {[
                        { label: 'Loyalty Program', value: program.loyaltyEnabled },
                        { label: 'Gift Card', value: program.giftCardEnabled },
                        { label: 'Corporate Card', value: program.corporateCardEnabled },
                        { label: 'Mobile Money', value: program.mobileMoneyEnabled },
                      ].filter(f => f.value).map(f => (
                        <div key={f.label} className="flex items-center justify-between">
                          <span className="text-sm text-neutral-500 dark:text-neutral-400">{f.label}</span>
                          <CheckCircle className="w-4 h-4 text-success-500" />
                        </div>
                      ))}
                    </Card>
                  </>
                )}
                <h3 className="text-sm font-semibold text-primary-900 dark:text-neutral-50">Actions</h3>
                <div className="flex flex-wrap gap-2">
                  {program.status === 'ACTIVE' && <Button variant="outline" size="sm" onClick={() => onStatusChange(program.id, 'SUSPENDED')} leftIcon={<PauseCircle className="w-4 h-4" />}>Suspend</Button>}
                  {program.status === 'SUSPENDED' && <Button variant="outline" size="sm" onClick={() => onStatusChange(program.id, 'ACTIVE')} leftIcon={<PlayCircle className="w-4 h-4" />}>Activate</Button>}
                  {program.status === 'PENDING_APPROVAL' && <Button size="sm" onClick={() => onStatusChange(program.id, 'ACTIVE')} leftIcon={<CheckCircle className="w-4 h-4" />}>Approve</Button>}
                  <Button variant="outline" size="sm" leftIcon={<Copy className="w-4 h-4" />}>Clone</Button>
                </div>
              </div>
            </div>
          )}

          {/* Hierarchy Tab - NEW */}
          {!loading && activeTab === 'hierarchy' && (
            <div>
              {!hasHierarchy ? (
                /* Hierarchy Not Initialized */
                <div className="text-center py-12">
                  <div className="w-16 h-16 rounded-full bg-warning-100 flex items-center justify-center mx-auto mb-4 dark:bg-warning-500/20">
                    <GitBranch className="w-8 h-8 text-warning-600 dark:text-warning-300" />
                  </div>
                  <h3 className="section-title mb-2">
                    {program.hierarchyEnabled ? 'Hierarchy Not Initialized' : 'Hierarchy Not Enabled'}
                  </h3>
                  <p className="text-neutral-500 mb-6 max-w-md mx-auto dark:text-neutral-400">
                    {program.hierarchyEnabled 
                      ? 'Initialize a hierarchy structure to enable Settlement VAs and Exception VA auto-creation.'
                      : 'Enable hierarchy in program settings to use multi-level balance aggregation.'}
                  </p>
                  {program.hierarchyEnabled && (
                    <Button leftIcon={<Sparkles className="w-4 h-4" />}>
                      Initialize Hierarchy
                    </Button>
                  )}
                </div>
              ) : (
                /* Hierarchy Initialized - Show Settlement VAs and Exception VAs */
                <div className="grid grid-cols-2 gap-6">
                  {/* Left Column: Configuration & Exception VAs */}
                  <div className="space-y-4">
                    <h3 className="text-sm font-semibold text-primary-900 dark:text-neutral-50">Hierarchy Configuration</h3>
                    <Card padding="sm" className="space-y-3">
                      <div className="flex justify-between">
                        <span className="text-sm text-neutral-500 dark:text-neutral-400">Template</span>
                        <span className="text-sm font-medium">{program.defaultHierarchyTemplate || 'Custom'}</span>
                      </div>
                      <div className="flex justify-between">
                        <span className="text-sm text-neutral-500 dark:text-neutral-400">Depth</span>
                        <span className="text-sm">{program.hierarchyDepth || 7} levels</span>
                      </div>
                      <div className="flex justify-between">
                        <span className="text-sm text-neutral-500 dark:text-neutral-400">Root Node</span>
                        <span className="text-xs font-mono text-neutral-600 dark:text-neutral-300">{program.rootHierarchyNodeId?.slice(0, 8)}...</span>
                      </div>
                    </Card>

                    <h3 className="text-sm font-semibold text-primary-900 flex items-center gap-2 dark:text-neutral-50">
                      <Zap className="w-4 h-4" />Balance Aggregation
                    </h3>
                    <Card padding="sm" className="space-y-3">
                      <div className="flex justify-between items-center">
                        <span className="text-sm text-neutral-500 dark:text-neutral-400">Real-time Propagation</span>
                        {program.realtimeBalancePropagation 
                          ? <Badge variant="success" size="sm"><Zap className="w-3 h-3 mr-1" />Enabled</Badge>
                          : <Badge variant="neutral" size="sm">Disabled</Badge>}
                      </div>
                      <div className="flex justify-between">
                        <span className="text-sm text-neutral-500 dark:text-neutral-400">Aggregation Interval</span>
                        <span className="text-sm">{program.balanceAggregationIntervalMinutes || 5} minutes</span>
                      </div>
                    </Card>

                    <h3 className="text-sm font-semibold text-primary-900 flex items-center gap-2 dark:text-neutral-50">
                      <XCircle className="w-4 h-4 text-error-500" />Exception VAs
                    </h3>
                    {hierarchyLoading ? (
                      <div className="flex items-center gap-2 p-4"><Loader2 className="w-4 h-4 animate-spin" /><span className="text-sm text-neutral-500 dark:text-neutral-400">Loading...</span></div>
                    ) : exceptionVas.length > 0 ? (
                      <div className="space-y-2">
                        {exceptionVas.map((va) => (
                          <Card key={va.id} padding="sm" className="flex justify-between items-center hover:bg-neutral-50 dark:hover:bg-primary-800/50">
                            <div>
                              <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{va.vaName}</p>
                              <p className="text-xs text-neutral-500 font-mono dark:text-neutral-400">{va.vaNumber}</p>
                            </div>
                            <div className="text-right">
                              <Badge variant="error" size="sm">Exception</Badge>
                              <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">{formatCurrency(va.currentBalance, va.currency)}</p>
                            </div>
                          </Card>
                        ))}
                      </div>
                    ) : (
                      <Card padding="sm" className="text-center text-neutral-500 text-sm py-4 dark:text-neutral-400">
                        <p>No Exception VAs created yet</p>
                      </Card>
                    )}
                  </div>

                  {/* Right Column: Settlement VAs */}
                  <div className="space-y-4">
                    <div className="flex items-center justify-between">
                      <h3 className="text-sm font-semibold text-primary-900 flex items-center gap-2 dark:text-neutral-50">
                        <FolderTree className="w-4 h-4" />Settlement VAs
                      </h3>
                      <span className="text-xs text-neutral-500 dark:text-neutral-400">{settlementVas.length} total</span>
                    </div>
                    {hierarchyLoading ? (
                      <div className="flex items-center justify-center py-12"><Loader2 className="w-6 h-6 animate-spin text-primary-500" /></div>
                    ) : settlementVas.length > 0 ? (
                      <div className="space-y-2 max-h-[400px] overflow-y-auto">
                        {settlementVas.map((va) => (
                          <Card key={va.id} padding="sm" className="flex justify-between items-center hover:bg-neutral-50 cursor-pointer dark:hover:bg-primary-800/50">
                            <div className="flex items-center gap-3">
                              <div className="w-8 h-8 rounded-lg bg-accent-100 flex items-center justify-center dark:bg-accent-500/20">
                                <span className="text-xs font-medium text-accent-700 dark:text-accent-300">L{va.hierarchyLevel || '?'}</span>
                              </div>
                              <div>
                                <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{va.vaName}</p>
                                <p className="text-xs text-neutral-500 font-mono dark:text-neutral-400">{va.vaNumber}</p>
                                {va.hierarchyPath && (
                                  <p className="text-xs text-neutral-400 mt-0.5 dark:text-neutral-500">{va.hierarchyPath}</p>
                                )}
                              </div>
                            </div>
                            <div className="text-right">
                              <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{formatCurrency(va.currentBalance, va.currency)}</p>
                              <Badge variant={va.status === 'ACTIVE' ? 'success' : 'neutral'} size="sm">{va.status}</Badge>
                            </div>
                          </Card>
                        ))}
                      </div>
                    ) : (
                      <Card padding="sm" className="text-center py-12">
                        <FolderTree className="w-10 h-10 text-neutral-300 mx-auto mb-3 dark:text-neutral-600" />
                        <p className="text-neutral-500 mb-2 dark:text-neutral-400">No Settlement VAs created yet</p>
                        <p className="text-xs text-neutral-400 dark:text-neutral-500">Settlement VAs are created when hierarchy nodes are added</p>
                      </Card>
                    )}

                    {/* Info Box */}
                    <div className="bg-info-50 border border-info-200 rounded-lg p-3 mt-4 dark:bg-info-500/10 dark:border-info-500/30">
                      <div className="flex items-start gap-2">
                        <Info className="w-4 h-4 text-info-600 mt-0.5 dark:text-info-300" />
                        <div className="text-sm text-info-800 dark:text-info-300">
                          <p className="font-medium">About Settlement VAs</p>
                          <ul className="mt-1 space-y-1 text-info-700 text-xs dark:text-info-300">
                            <li>• Each hierarchy level can have Settlement VAs</li>
                            <li>• Balances aggregate up the hierarchy tree</li>
                            <li>• Exception VA catches unmatched transactions</li>
                          </ul>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              )}
            </div>
          )}

          {/* VIBAN Pool Tab - NEW */}
          {!loading && activeTab === 'viban' && program.vibanEnabled && (
            <div className="space-y-6">
              {vibanLoading ? (
                <div className="flex items-center justify-center py-12">
                  <Loader2 className="w-6 h-6 animate-spin text-primary-500" />
                </div>
              ) : (
                <>
                  {/* VIBAN Generation Strategy */}
                  <div>
                    <h3 className="text-sm font-semibold text-primary-900 mb-3 flex items-center gap-2 dark:text-neutral-50">
                      <Settings className="w-4 h-4" />Generation Strategy
                    </h3>
                    <div className="grid grid-cols-3 gap-3">
                      {Object.entries(vibanStrategyConfig).map(([key, config]) => {
                        const StrategyIcon = config.icon;
                        const isActive = program.vibanGenerationStrategy === key;
                        return (
                          <Card 
                            key={key} 
                            padding="sm" 
                            className={cn(
                              'cursor-pointer transition-all',
                              isActive 
                                ? 'ring-2 ring-primary-500 border-primary-500 bg-primary-50 dark:bg-primary-800/40' 
                                : 'hover:border-neutral-300 dark:hover:border-primary-700'
                            )}
                          >
                            <div className="flex items-start gap-3">
                              <div className={cn(
                                'w-10 h-10 rounded-lg flex items-center justify-center',
                                isActive ? 'bg-primary-600' : 'bg-neutral-100 dark:bg-primary-800'
                              )}>
                                <StrategyIcon className={cn('w-5 h-5', isActive ? 'text-white' : 'text-neutral-600 dark:text-neutral-300')} />
                              </div>
                              <div className="flex-1">
                                <div className="flex items-center gap-2">
                                  <span className={cn('font-medium', isActive ? 'text-primary-900 dark:text-neutral-50' : 'text-neutral-700 dark:text-neutral-200')}>
                                    {config.label}
                                  </span>
                                  {isActive && <Badge variant="success" size="sm">Active</Badge>}
                                </div>
                                <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">{config.description}</p>
                              </div>
                            </div>
                          </Card>
                        );
                      })}
                    </div>
                  </div>

                  {/* VIBAN Configuration */}
                  <div className="grid grid-cols-2 gap-6">
                    <div className="space-y-4">
                      <h3 className="text-sm font-semibold text-primary-900 flex items-center gap-2 dark:text-neutral-50">
                        <Hash className="w-4 h-4" />VIBAN Settings
                      </h3>
                      <Card padding="sm" className="space-y-3">
                        <div className="flex justify-between">
                          <span className="text-sm text-neutral-500 dark:text-neutral-400">VIBAN Prefix</span>
                          <span className="text-sm font-mono font-medium text-primary-900 dark:text-neutral-50">
                            {program.vibanPrefix || 'Not set'}
                          </span>
                        </div>
                        <div className="flex justify-between">
                          <span className="text-sm text-neutral-500 dark:text-neutral-400">Bank Code</span>
                          <span className="text-sm font-mono font-medium text-primary-900 dark:text-neutral-50">
                            {program.vibanBankCode || 'Not set'}
                          </span>
                        </div>
                        <div className="flex justify-between">
                          <span className="text-sm text-neutral-500 dark:text-neutral-400">Generation Strategy</span>
                          <Badge variant="neutral">
                            {vibanStrategyConfig[program.vibanGenerationStrategy || 'SEQUENTIAL']?.label}
                          </Badge>
                        </div>
                        {program.vibanGenerationStrategy === 'HIERARCHY_ENCODED' && (
                          <div className="pt-2 border-t">
                            <div className="flex items-start gap-2 text-xs text-info-700 bg-info-50 p-2 rounded dark:text-info-300 dark:bg-info-500/10">
                              <Info className="w-3 h-3 mt-0.5" />
                              <span>VIBANs encode hierarchy path for automatic routing</span>
                            </div>
                          </div>
                        )}
                      </Card>

                      {/* Sample VIBAN Preview */}
                      <h3 className="text-sm font-semibold text-primary-900 dark:text-neutral-50">Sample VIBAN Format</h3>
                      <Card padding="sm" className="bg-neutral-50 dark:bg-primary-950">
                        <div className="font-mono text-lg text-center text-primary-900 tracking-wider dark:text-neutral-50">
                          {program.vibanPrefix || 'AE'}{program.vibanBankCode || '00'}-
                          {program.vibanGenerationStrategy === 'SEQUENTIAL' && '0000-0001'}
                          {program.vibanGenerationStrategy === 'RANDOM' && 'X7K2-9M4P'}
                          {program.vibanGenerationStrategy === 'HIERARCHY_ENCODED' && 'L1L2-0001'}
                          {!program.vibanGenerationStrategy && '0000-0001'}
                        </div>
                        <p className="text-xs text-neutral-500 text-center mt-2 dark:text-neutral-400">
                          {program.vibanGenerationStrategy === 'HIERARCHY_ENCODED' 
                            ? 'Includes encoded hierarchy levels'
                            : 'Standard VIBAN format'}
                        </p>
                      </Card>
                    </div>

                    <div className="space-y-4">
                      <h3 className="text-sm font-semibold text-primary-900 flex items-center gap-2 dark:text-neutral-50">
                        <Layers className="w-4 h-4" />VIBAN Pool
                      </h3>
                      {vibanPool ? (
                        <Card padding="sm" className="space-y-3">
                          <div className="flex justify-between">
                            <span className="text-sm text-neutral-500 dark:text-neutral-400">Pool Name</span>
                            <span className="text-sm font-medium text-primary-900 dark:text-neutral-50">{vibanPool.poolName}</span>
                          </div>
                          <div className="flex justify-between">
                            <span className="text-sm text-neutral-500 dark:text-neutral-400">Pool Code</span>
                            <span className="text-sm font-mono">{vibanPool.poolCode}</span>
                          </div>
                          <div className="flex justify-between">
                            <span className="text-sm text-neutral-500 dark:text-neutral-400">Bank Code</span>
                            <span className="text-sm font-mono">{vibanPool.bankCode}</span>
                          </div>
                          <div className="flex justify-between">
                            <span className="text-sm text-neutral-500 dark:text-neutral-400">Status</span>
                            <Badge variant={vibanPool.status === 'ACTIVE' ? 'success' : 'neutral'}>
                              {vibanPool.status}
                            </Badge>
                          </div>
                          <div className="pt-3 border-t">
                            <div className="grid grid-cols-3 gap-2 text-center">
                              <div>
                                <p className="section-title">{vibanPool.totalVibans}</p>
                                <p className="text-xs text-neutral-500 dark:text-neutral-400">Total</p>
                              </div>
                              <div>
                                <p className="text-lg font-semibold text-success-600 dark:text-success-300">{vibanPool.availableVibans}</p>
                                <p className="text-xs text-neutral-500 dark:text-neutral-400">Available</p>
                              </div>
                              <div>
                                <p className="text-lg font-semibold text-warning-600 dark:text-warning-300">{vibanPool.usedVibans}</p>
                                <p className="text-xs text-neutral-500 dark:text-neutral-400">Used</p>
                              </div>
                            </div>
                            {/* Usage Progress Bar */}
                            <div className="mt-3">
                              <div className="flex justify-between text-xs text-neutral-500 mb-1 dark:text-neutral-400">
                                <span>Pool Usage</span>
                                <span>{Math.round((vibanPool.usedVibans / vibanPool.totalVibans) * 100)}%</span>
                              </div>
                              <div className="h-2 bg-neutral-200 rounded-full overflow-hidden dark:bg-primary-800">
                                <div 
                                  className="h-full bg-primary-500 rounded-full transition-all"
                                  style={{ width: `${(vibanPool.usedVibans / vibanPool.totalVibans) * 100}%` }}
                                />
                              </div>
                            </div>
                          </div>
                        </Card>
                      ) : (
                        <Card padding="sm" className="text-center py-8">
                          <Hash className="w-10 h-10 text-neutral-300 mx-auto mb-3 dark:text-neutral-600" />
                          <p className="text-neutral-500 mb-2 dark:text-neutral-400">No VIBAN Pool Assigned</p>
                          <p className="text-xs text-neutral-400 dark:text-neutral-500">Assign a VIBAN pool to enable VIBAN generation</p>
                        </Card>
                      )}

                      {/* Quick Stats */}
                      {vibanPool && (
                        <div className="bg-accent-50 border border-accent-200 rounded-lg p-3 dark:bg-accent-500/10 dark:border-accent-500/30">
                          <div className="flex items-start gap-2">
                            <Info className="w-4 h-4 text-accent-600 mt-0.5 dark:text-accent-300" />
                            <div className="text-sm text-accent-800 dark:text-accent-300">
                              <p className="font-medium">Pool Capacity</p>
                              <p className="text-accent-700 text-xs mt-1 dark:text-accent-300">
                                {vibanPool.availableVibans} VIBANs available for new virtual accounts
                              </p>
                            </div>
                          </div>
                        </div>
                      )}
                    </div>
                  </div>

                  {/* VIBAN Features Info */}
                  <div className="bg-info-50 border border-info-200 rounded-lg p-4 dark:bg-info-500/10 dark:border-info-500/30">
                    <div className="flex items-start gap-3">
                      <Info className="w-5 h-5 text-info-600 mt-0.5 dark:text-info-300" />
                      <div>
                        <p className="font-medium text-info-900">About VIBAN Generation</p>
                        <ul className="mt-2 space-y-1 text-sm text-info-700 dark:text-info-300">
                          <li>• <strong>Sequential:</strong> Best for predictable, ordered VIBAN allocation</li>
                          <li>• <strong>Random:</strong> Enhanced security with unpredictable identifiers</li>
                          <li>• <strong>Hierarchy Encoded:</strong> Enables automatic routing based on hierarchy structure</li>
                        </ul>
                      </div>
                    </div>
                  </div>
                </>
              )}
            </div>
          )}
          {/* Wallet Fees Tab - NEW */}{/* Wallet Fees Tab - NEW */}
          {!loading && activeTab === 'wallet' && (program.walletEnabled || program.programType === 'WALLET') && (
            <div className="space-y-6">
              {walletChargesLoading ? (
                <div className="flex items-center justify-center py-12">
                  <Loader2 className="w-6 h-6 animate-spin text-primary-500" />
                </div>
              ) : walletCharges ? (
                <>
                  {/* Header */}
                  <div className="flex items-center justify-between">
                    <div>
                      <h3 className="text-sm font-semibold text-primary-900 flex items-center gap-2 dark:text-neutral-50">
                        <Wallet className="w-4 h-4" />
                        Wallet Fee Configuration
                      </h3>
                      <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">
                        Fees managed via ChargeConfiguration system
                      </p>
                    </div>
                    <Badge variant="info">ChargeConfiguration API</Badge>
                  </div>

                  {/* Transaction Fees */}
                  <div>
                    <h4 className="text-xs font-semibold text-neutral-500 uppercase tracking-wider mb-3 dark:text-neutral-400">Transaction Fees</h4>
                    <div className="space-y-2">
                      {/* Topup Fee */}
                      <div className={cn(
                        'flex items-center justify-between p-3 rounded-lg border',
                        walletCharges.topup.isWaived ? 'bg-warning-50 border-warning-200 dark:bg-warning-500/10 dark:border-warning-500/30' :
                        walletCharges.topup.hasOverride ? 'bg-primary-50 border-primary-200 dark:bg-primary-800/40 dark:border-primary-700' : 'bg-neutral-50 border-neutral-200 dark:bg-primary-950 dark:border-primary-800'
                      )}>
                        <div className="flex items-center gap-3">
                          <StatusIconBadge tone="success" icon={Plus} rounded="lg" className="dark:bg-success-500/20" />
                          <div>
                            <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{walletCharges.topup.chargeName}</p>
                            <p className="text-xs text-neutral-500 dark:text-neutral-400">{walletCharges.topup.chargeCode}</p>
                          </div>
                        </div>
                        <div className="text-right">
                          {walletCharges.topup.isWaived ? (
                            <Badge variant="warning">Waived</Badge>
                          ) : (
                            <>
                              <p className="text-sm font-semibold text-primary-900 dark:text-neutral-50">
                                {walletCharges.topup.percentage}% + {program.currencyCode} {walletCharges.topup.fixed}
                              </p>
                              <p className="text-xs text-neutral-500 dark:text-neutral-400">
                                Min: {program.currencyCode} {walletCharges.topup.minimum || 0} | Max: {program.currencyCode} {walletCharges.topup.maximum || '∞'}
                              </p>
                              {walletCharges.topup.hasOverride && <Badge variant="info" size="sm" className="mt-1">Override</Badge>}
                            </>
                          )}
                        </div>
                      </div>

                      {/* Withdrawal Fee */}
                      <div className={cn(
                        'flex items-center justify-between p-3 rounded-lg border',
                        walletCharges.withdrawal.isWaived ? 'bg-warning-50 border-warning-200 dark:bg-warning-500/10 dark:border-warning-500/30' :
                        walletCharges.withdrawal.hasOverride ? 'bg-primary-50 border-primary-200 dark:bg-primary-800/40 dark:border-primary-700' : 'bg-neutral-50 border-neutral-200 dark:bg-primary-950 dark:border-primary-800'
                      )}>
                        <div className="flex items-center gap-3">
                          <StatusIconBadge tone="error" icon={Banknote} rounded="lg" className="dark:bg-error-500/20" />
                          <div>
                            <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{walletCharges.withdrawal.chargeName}</p>
                            <p className="text-xs text-neutral-500 dark:text-neutral-400">{walletCharges.withdrawal.chargeCode}</p>
                          </div>
                        </div>
                        <div className="text-right">
                          {walletCharges.withdrawal.isWaived ? (
                            <Badge variant="warning">Waived</Badge>
                          ) : (
                            <>
                              <p className="text-sm font-semibold text-primary-900 dark:text-neutral-50">
                                {walletCharges.withdrawal.percentage}% + {program.currencyCode} {walletCharges.withdrawal.fixed}
                              </p>
                              <p className="text-xs text-neutral-500 dark:text-neutral-400">
                                Min: {program.currencyCode} {walletCharges.withdrawal.minimum || 0} | Max: {program.currencyCode} {walletCharges.withdrawal.maximum || '∞'}
                              </p>
                              {walletCharges.withdrawal.hasOverride && <Badge variant="info" size="sm" className="mt-1">Override</Badge>}
                            </>
                          )}
                        </div>
                      </div>

                      {/* Transfer Fee */}
                      <div className={cn(
                        'flex items-center justify-between p-3 rounded-lg border',
                        walletCharges.transfer.isWaived ? 'bg-warning-50 border-warning-200 dark:bg-warning-500/10 dark:border-warning-500/30' :
                        walletCharges.transfer.hasOverride ? 'bg-primary-50 border-primary-200 dark:bg-primary-800/40 dark:border-primary-700' : 'bg-neutral-50 border-neutral-200 dark:bg-primary-950 dark:border-primary-800'
                      )}>
                        <div className="flex items-center gap-3">
                          <StatusIconBadge tone="info" icon={TrendingUp} rounded="lg" className="dark:bg-info-500/20" />
                          <div>
                            <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{walletCharges.transfer.chargeName}</p>
                            <p className="text-xs text-neutral-500 dark:text-neutral-400">{walletCharges.transfer.chargeCode}</p>
                          </div>
                        </div>
                        <div className="text-right">
                          {walletCharges.transfer.isWaived ? (
                            <Badge variant="warning">Waived</Badge>
                          ) : (
                            <>
                              <p className="text-sm font-semibold text-primary-900 dark:text-neutral-50">
                                {walletCharges.transfer.percentage}% + {program.currencyCode} {walletCharges.transfer.fixed}
                              </p>
                              <p className="text-xs text-neutral-500 dark:text-neutral-400">
                                Min: {program.currencyCode} {walletCharges.transfer.minimum || 0} | Max: {program.currencyCode} {walletCharges.transfer.maximum || '∞'}
                              </p>
                              {walletCharges.transfer.hasOverride && <Badge variant="info" size="sm" className="mt-1">Override</Badge>}
                            </>
                          )}
                        </div>
                      </div>
                    </div>
                  </div>

                  {/* Fixed Fees */}
                  <div>
                    <h4 className="text-xs font-semibold text-neutral-500 uppercase tracking-wider mb-3 dark:text-neutral-400">Fixed Fees</h4>
                    <div className="grid grid-cols-3 gap-3">
                      {/* Issuance Fee */}
                      <Card padding="sm" className={cn(
                        walletCharges.issuance.isWaived ? 'bg-warning-50 border-warning-200 dark:bg-warning-500/10 dark:border-warning-500/30' :
                        walletCharges.issuance.hasOverride ? 'bg-primary-50 border-primary-200 dark:bg-primary-800/40 dark:border-primary-700' : ''
                      )}>
                        <div className="flex items-center gap-2 mb-2">
                          <CreditCard className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
                          <span className="text-xs text-neutral-500 dark:text-neutral-400">Issuance</span>
                        </div>
                        {walletCharges.issuance.isWaived ? (
                          <Badge variant="warning">Waived</Badge>
                        ) : (
                          <>
                            <p className="section-title">
                              {program.currencyCode} {walletCharges.issuance.fixed}
                            </p>
                            {walletCharges.issuance.hasOverride && <Badge variant="info" size="sm">Override</Badge>}
                          </>
                        )}
                        <p className="text-xs text-neutral-400 mt-1 dark:text-neutral-500">One-time fee</p>
                      </Card>

                      {/* Monthly Fee */}
                      <Card padding="sm" className={cn(
                        walletCharges.monthly.isWaived ? 'bg-warning-50 border-warning-200 dark:bg-warning-500/10 dark:border-warning-500/30' :
                        walletCharges.monthly.hasOverride ? 'bg-primary-50 border-primary-200 dark:bg-primary-800/40 dark:border-primary-700' : ''
                      )}>
                        <div className="flex items-center gap-2 mb-2">
                          <Clock className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
                          <span className="text-xs text-neutral-500 dark:text-neutral-400">Monthly</span>
                        </div>
                        {walletCharges.monthly.isWaived ? (
                          <Badge variant="warning">Waived</Badge>
                        ) : (
                          <>
                            <p className="section-title">
                              {program.currencyCode} {walletCharges.monthly.fixed}
                            </p>
                            {walletCharges.monthly.hasOverride && <Badge variant="info" size="sm">Override</Badge>}
                          </>
                        )}
                        <p className="text-xs text-neutral-400 mt-1 dark:text-neutral-500">Recurring monthly</p>
                      </Card>

                      {/* Inactivity Fee */}
                      <Card padding="sm" className={cn(
                        walletCharges.inactivity.isWaived ? 'bg-warning-50 border-warning-200 dark:bg-warning-500/10 dark:border-warning-500/30' :
                        walletCharges.inactivity.hasOverride ? 'bg-primary-50 border-primary-200 dark:bg-primary-800/40 dark:border-primary-700' : ''
                      )}>
                        <div className="flex items-center gap-2 mb-2">
                          <PauseCircle className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
                          <span className="text-xs text-neutral-500 dark:text-neutral-400">Inactivity</span>
                        </div>
                        {walletCharges.inactivity.isWaived ? (
                          <Badge variant="warning">Waived</Badge>
                        ) : (
                          <>
                            <p className="section-title">
                              {program.currencyCode} {walletCharges.inactivity.fixed}
                            </p>
                            {walletCharges.inactivity.hasOverride && <Badge variant="info" size="sm">Override</Badge>}
                          </>
                        )}
                        <p className="text-xs text-neutral-400 mt-1 dark:text-neutral-500">After inactivity period</p>
                      </Card>
                    </div>
                  </div>

                  {/* Fee Summary */}
                  <div className="bg-neutral-50 rounded-lg p-4 dark:bg-primary-950">
                    <h4 className="text-xs font-semibold text-neutral-500 uppercase tracking-wider mb-3 dark:text-neutral-400">Fee Status Summary</h4>
                    <div className="grid grid-cols-3 gap-4 text-center">
                      <div>
                        <p className="stat-value-sm">
                          {[walletCharges.topup, walletCharges.withdrawal, walletCharges.transfer, walletCharges.issuance, walletCharges.monthly, walletCharges.inactivity]
                            .filter(c => !c.isWaived && !c.hasOverride).length}
                        </p>
                        <p className="text-xs text-neutral-500 dark:text-neutral-400">Base Rates</p>
                      </div>
                      <div>
                        <p className="stat-value-info">
                          {[walletCharges.topup, walletCharges.withdrawal, walletCharges.transfer, walletCharges.issuance, walletCharges.monthly, walletCharges.inactivity]
                            .filter(c => c.hasOverride && !c.isWaived).length}
                        </p>
                        <p className="text-xs text-neutral-500 dark:text-neutral-400">Overrides</p>
                      </div>
                      <div>
                        <p className="stat-value-warning">
                          {[walletCharges.topup, walletCharges.withdrawal, walletCharges.transfer, walletCharges.issuance, walletCharges.monthly, walletCharges.inactivity]
                            .filter(c => c.isWaived).length}
                        </p>
                        <p className="text-xs text-neutral-500 dark:text-neutral-400">Waived</p>
                      </div>
                    </div>
                  </div>

                  {/* Info Box */}
                  <div className="bg-info-50 border border-info-200 rounded-lg p-3 dark:bg-info-500/10 dark:border-info-500/30">
                    <div className="flex items-start gap-2">
                      <Info className="w-4 h-4 text-info-600 mt-0.5 dark:text-info-300" />
                      <div className="text-sm text-info-800 dark:text-info-300">
                        <p className="font-medium">About Wallet Fees</p>
                        <ul className="mt-1 space-y-1 text-info-700 text-xs dark:text-info-300">
                          <li>• <strong>Base:</strong> Default rates from ChargeConfiguration</li>
                          <li>• <strong>Override:</strong> Program-specific customized rates</li>
                          <li>• <strong>Waived:</strong> Fee is not charged for this program</li>
                        </ul>
                      </div>
                    </div>
                  </div>
                </>
              ) : (
                <div className="text-center py-12">
                  <Wallet className="w-12 h-12 mx-auto mb-3 text-neutral-300 dark:text-neutral-600" />
                  <p className="text-neutral-500 dark:text-neutral-400">No wallet fee configuration found</p>
                </div>
              )}
            </div>
          )}

          {/* Virtual Accounts Tab */}
          {!loading && activeTab === 'accounts' && detail && (
            <div className="space-y-4">
              {detail.recentVirtualAccounts.length === 0 ? (
                <div className="text-center py-12 text-neutral-500 dark:text-neutral-400">
                  <CreditCard className="w-12 h-12 mx-auto mb-3 text-neutral-300 dark:text-neutral-600" />
                  <p>No virtual accounts in this program</p>
                </div>
              ) : (
                <div className="space-y-2">
                  {detail.recentVirtualAccounts.map(va => (
                    <Card key={va.id} padding="sm" className="flex items-center justify-between hover:bg-neutral-50 cursor-pointer dark:hover:bg-primary-800/50">
                      <div className="flex items-center gap-3">
                        <StatusIconBadge tone="primary" icon={CreditCard} rounded="lg" className="dark:bg-primary-700" />
                        <div>
                          <p className="font-medium text-primary-900 dark:text-neutral-50">{va.vaName}</p>
                          <p className="text-xs text-neutral-500 font-mono dark:text-neutral-400">{va.vaNumber}</p>
                        </div>
                      </div>
                      <div className="flex items-center gap-4">
                        <div className="text-right">
                          <p className="font-medium text-primary-900 dark:text-neutral-50">{formatCurrency(va.currentBalance, program.currencyCode)}</p>
                          <Badge variant={va.status === 'ACTIVE' ? 'success' : 'neutral'} size="sm">{va.status}</Badge>
                        </div>
                        <ChevronRight className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
                      </div>
                    </Card>
                  ))}
                </div>
              )}
            </div>
          )}

          {/* Config Tab */}
          {!loading && activeTab === 'config' && (
            <div className="space-y-6">
              <Card padding="md">
                <h4 className="font-medium text-primary-900 mb-4 dark:text-neutral-50">VA Configuration</h4>
                <div className="grid grid-cols-2 gap-4">
                  <div><p className="text-xs text-neutral-500 mb-1 dark:text-neutral-400">Prefix</p><p className="font-mono">{program.vaPrefix || 'None'}</p></div>
                  <div><p className="text-xs text-neutral-500 mb-1 dark:text-neutral-400">Format</p><p className="font-mono">{program.vaFormat || 'Auto'}</p></div>
                  <div><p className="text-xs text-neutral-500 mb-1 dark:text-neutral-400">Max Accounts</p><p>{program.maxVirtualAccounts || 'Unlimited'}</p></div>
                  <div><p className="text-xs text-neutral-500 mb-1 dark:text-neutral-400">Min Balance Threshold</p><p>{program.minBalanceThreshold ? formatCurrency(program.minBalanceThreshold, program.currencyCode) : 'Not set'}</p></div>
                </div>
              </Card>
              <Card padding="md">
                <h4 className="font-medium text-primary-900 mb-4 dark:text-neutral-50">Settlement</h4>
                <div className="grid grid-cols-2 gap-4">
                  <div><p className="text-xs text-neutral-500 mb-1 dark:text-neutral-400">Frequency</p><p>{settlementFrequencyConfig[program.settlementFrequency || '']?.label || 'Not set'}</p></div>
                  <div><p className="text-xs text-neutral-500 mb-1 dark:text-neutral-400">Time</p><p>{program.settlementTime || 'Anytime'}</p></div>
                </div>
              </Card>
              {/* Wallet Limits - Only show if wallet is enabled */}
              {(program.walletEnabled || program.programType === 'WALLET') && (
                <Card padding="md">
                  <h4 className="font-medium text-primary-900 mb-4 flex items-center gap-2 dark:text-neutral-50">
                    <Wallet className="w-4 h-4" />Wallet Limits
                  </h4>
                  <div className="grid grid-cols-3 gap-4">
                    <div>
                      <p className="text-xs text-neutral-500 mb-1 dark:text-neutral-400">Per Transaction</p>
                      <p className="font-medium">{program.defaultPerTransactionLimit ? formatCurrency(program.defaultPerTransactionLimit, program.currencyCode) : 'Unlimited'}</p>
                    </div>
                    <div>
                      <p className="text-xs text-neutral-500 mb-1 dark:text-neutral-400">Daily Limit</p>
                      <p className="font-medium">{program.defaultDailyLimit ? formatCurrency(program.defaultDailyLimit, program.currencyCode) : 'Unlimited'}</p>
                    </div>
                    <div>
                      <p className="text-xs text-neutral-500 mb-1 dark:text-neutral-400">Monthly Limit</p>
                      <p className="font-medium">{program.defaultMonthlyLimit ? formatCurrency(program.defaultMonthlyLimit, program.currencyCode) : 'Unlimited'}</p>
                    </div>
                    <div>
                      <p className="text-xs text-neutral-500 mb-1 dark:text-neutral-400">Max Balance</p>
                      <p className="font-medium">{program.defaultMaxBalance ? formatCurrency(program.defaultMaxBalance, program.currencyCode) : 'Unlimited'}</p>
                    </div>
                  </div>
                </Card>
              )}
              {/* KYC Settings - Only show if wallet is enabled */}
              {(program.walletEnabled || program.programType === 'WALLET') && (
                <Card padding="md">
                  <h4 className="font-medium text-primary-900 mb-4 flex items-center gap-2 dark:text-neutral-50">
                    <Shield className="w-4 h-4" />KYC Settings
                  </h4>
                  <div className="grid grid-cols-2 gap-4">
                    <div className="flex items-center justify-between">
                      <span className="text-sm text-neutral-500 dark:text-neutral-400">KYC Required</span>
                      {program.kycRequired ? <CheckCircle className="w-4 h-4 text-success-500" /> : <XCircle className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />}
                    </div>
                    <div>
                      <p className="text-xs text-neutral-500 mb-1 dark:text-neutral-400">Minimum KYC Level</p>
                      <p className="font-medium">{program.minKycLevel !== undefined ? `Level ${program.minKycLevel}` : 'Not set'}</p>
                    </div>
                  </div>
                </Card>
              )}
              {/* Wallet Capabilities - Only show if wallet is enabled */}
              {(program.walletEnabled || program.programType === 'WALLET') && (
                <Card padding="md">
                  <h4 className="font-medium text-primary-900 mb-4 dark:text-neutral-50">Wallet Capabilities</h4>
                  <div className="grid grid-cols-2 gap-4">
                    {[
                      { label: 'Allow Topup', value: program.allowTopup },
                      { label: 'Allow Withdrawal', value: program.allowWithdrawal },
                      { label: 'Allow Transfer', value: program.allowTransfer },
                      { label: 'Allow Payment', value: program.allowPayment },
                    ].map(cap => (
                      <div key={cap.label} className="flex items-center justify-between">
                        <span className="text-sm text-neutral-500 dark:text-neutral-400">{cap.label}</span>
                        {cap.value ? <CheckCircle className="w-4 h-4 text-success-500" /> : <XCircle className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />}
                      </div>
                    ))}
                  </div>
                </Card>
              )}
            </div>
          )}

          {/* History Tab */}
          {!loading && activeTab === 'history' && detail && (
            <div className="space-y-3">
              {detail.activityLog.length === 0 ? <p className="text-center py-8 text-neutral-500 dark:text-neutral-400">No activity</p> : detail.activityLog.map((item, i) => (
                <div key={i} className="flex items-start gap-3 p-3 bg-neutral-50 rounded-lg dark:bg-primary-950">
                  <div className="w-8 h-8 rounded-full bg-success-100 flex items-center justify-center dark:bg-success-500/20">
                    <CheckCircle className="w-4 h-4 text-success-600 dark:text-success-300" />
                  </div>
                  <div className="flex-1">
                    <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{item.action}</p>
                    <p className="text-xs text-neutral-500 dark:text-neutral-400">by {item.user} • {formatDate(item.timestamp)}</p>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </Modal>
  );
};

// ============================================================================
// CHARGE CONFIGURATION ROW COMPONENT
// ============================================================================

interface ChargeConfigRowProps {
  charge: ChargeDetail;
  currencyCode: string;
  overridePercent?: number;
  overrideFlat?: number;
  isWaived?: boolean;
  onPercentChange: (value: number | undefined) => void;
  onFlatChange: (value: number | undefined) => void;
  onWaiverChange: (waived: boolean) => void;
}

const ChargeConfigRow: React.FC<ChargeConfigRowProps> = ({
  charge, currencyCode, overridePercent, overrideFlat, isWaived,
  onPercentChange, onFlatChange, onWaiverChange
}) => {
  const hasOverride = overridePercent !== undefined || overrideFlat !== undefined;
  
  return (
    <div className={cn(
      'flex items-center gap-4 p-3 rounded-lg transition-all',
      isWaived ? 'bg-warning-50 border border-warning-200 dark:bg-warning-500/10 dark:border-warning-500/30' : 
      hasOverride ? 'bg-primary-50 border border-primary-200 dark:bg-primary-800/40 dark:border-primary-700' : 'bg-neutral-50 dark:bg-primary-950'
    )}>
      <div className="w-36">
        <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{charge.chargeName}</p>
        <p className="text-xs text-neutral-500 dark:text-neutral-400">{charge.chargeCode}</p>
      </div>
      
      <div className="w-24 text-center">
        <p className="text-xs text-neutral-400 dark:text-neutral-500">Base</p>
        <p className="text-sm text-neutral-600 dark:text-neutral-300">
          {charge.percentage > 0 ? `${charge.percentage}%` : ''}
          {charge.percentage > 0 && charge.fixed > 0 ? ' + ' : ''}
          {charge.fixed > 0 || charge.percentage === 0 ? `${currencyCode} ${charge.fixed}` : ''}
        </p>
      </div>
      
      <ChevronRight className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
      
      <div className="flex-1 flex items-center gap-2">
        <input type="number" step="0.01" className={cn('w-16 px-2 py-1 text-sm border rounded', isWaived && 'bg-neutral-100 dark:bg-primary-800')}
          placeholder={String(charge.percentage)} value={overridePercent ?? ''}
          onChange={e => onPercentChange(e.target.value ? parseFloat(e.target.value) : undefined)} disabled={isWaived} />
        <span className="text-xs text-neutral-500 dark:text-neutral-400">%</span>
        <span className="text-xs text-neutral-400 dark:text-neutral-500">+</span>
        <span className="text-xs text-neutral-400 dark:text-neutral-500">{currencyCode}</span>
        <input type="number" step="0.01" className={cn('w-16 px-2 py-1 text-sm border rounded', isWaived && 'bg-neutral-100 dark:bg-primary-800')}
          placeholder={String(charge.fixed)} value={overrideFlat ?? ''}
          onChange={e => onFlatChange(e.target.value ? parseFloat(e.target.value) : undefined)} disabled={isWaived} />
      </div>
      
      <label className="flex items-center gap-1 cursor-pointer">
        <input type="checkbox" checked={isWaived} onChange={e => onWaiverChange(e.target.checked)} className="rounded" />
        <span className="text-xs text-neutral-600 dark:text-neutral-300">Waive</span>
      </label>
      
      <div className="w-16">
        {isWaived ? <Badge variant="warning" size="sm">Waived</Badge> :
         hasOverride ? <Badge variant="info" size="sm">Override</Badge> :
         <Badge variant="neutral" size="sm">Base</Badge>}
      </div>
    </div>
  );
};

// ============================================================================
// FORM MODAL
// ============================================================================

// ============================================================================
// 5-STEP PROGRAM FORM MODAL
// Step 1: Basic Info (Code, Name, Type, Corporate, Physical Account)
// Step 2: Features (VIBAN, Wallet, Escrow, IHB, Auto-Recon)
// Step 3: VIBAN Pool Config (if VIBAN enabled)
// Step 4: Settlement & Limits
// Step 5: Review & Confirm
// ============================================================================

interface ProgramFormModalProps {
  isOpen: boolean;
  program?: Program | null;
  onClose: () => void;
  onSave: (data: Partial<Program>) => Promise<void>;
  defaultCorporateId?: string; // Pre-selected corporate from page picker
}

const ProgramFormModal: React.FC<ProgramFormModalProps> = ({ isOpen, program, onClose, onSave, defaultCorporateId }) => {
  const [loading, setLoading] = useState(false);
  const [step, setStep] = useState(1);
  const [corporates, setCorporates] = useState<Array<{ id: string; legalName: string }>>([]);
  const [physicalAccounts, setPhysicalAccounts] = useState<Array<{ id: string; accountNumber: string; bankName: string; currencyCode: string }>>([]);
  const [vibanPools, setVibanPools] = useState<VibanPool[]>([]);
  const [loadingCorporates, setLoadingCorporates] = useState(false);
  const [loadingAccounts, setLoadingAccounts] = useState(false);
  const [loadingPools, setLoadingPools] = useState(false);
    // Wallet Charges from ChargeConfiguration API
  const [walletCharges, setWalletCharges] = useState<WalletChargesResponse | null>(null);
  const [loadingCharges, setLoadingCharges] = useState(false);

  
  const [formData, setFormData] = useState({
    // Step 1: Basic Info
    programCode: '',
    programName: '',
    programType: 'COLLECTION' as ProgramType,
    description: '',
    corporateId: '',
    physicalAccountId: '',
    currencyCode: 'AED',
    // Step 2: Core Features
    vibanEnabled: false,
    walletEnabled: false,
    escrowEnabled: false,
    ihbEnabled: false,
    autoReconciliation: true,
    hierarchyEnabled: false,
    // Step 2: Extended Program Features
    loyaltyEnabled: false,
    giftCardEnabled: false,
    corporateCardEnabled: false,
    mobileMoneyEnabled: false,
    // Step 3: Hierarchy Config (when hierarchyEnabled)
    hierarchyDepth: 7,
    defaultHierarchyTemplate: '' as string,
    // Step 4: VIBAN Pool Config
    defaultVibanPoolId: '',
    vibanGenerationStrategy: 'SEQUENTIAL' as VibanGenerationStrategy,
    vibanPrefix: '',
    vibanBankCode: '',
    // Step 5: Wallet Limits (when walletEnabled)
    defaultPerTransactionLimit: undefined as number | undefined,
    defaultDailyLimit: undefined as number | undefined,
    defaultWeeklyLimit: undefined as number | undefined,
    defaultMonthlyLimit: undefined as number | undefined,
    defaultYearlyLimit: undefined as number | undefined,
    defaultMaxBalance: undefined as number | undefined,
    minTopup: undefined as number | undefined,
    maxTopup: undefined as number | undefined,
    defaultDailyTopupLimit: undefined as number | undefined,
    defaultMonthlyTopupLimit: undefined as number | undefined,
    minWithdrawal: undefined as number | undefined,
    maxWithdrawal: undefined as number | undefined,
    // Wallet KYC
    kycRequired: false,
    autoKyc: false,
    minKycLevel: 0,
    kycValidityDays: undefined as number | undefined,
    // Wallet Features
    allowTopup: true,
    allowWithdrawal: true,
    allowTransfer: true,
    allowPayment: true,
    allowBulkOperations: true,
    // Wallet Expiry
    walletExpiryDays: undefined as number | undefined,
    inactiveExpiryDays: undefined as number | undefined,
    defaultWalletType: 'CONSUMER' as string,
    // Step 6: Settlement & Limits
    vaPrefix: '',
    vaFormat: '',
    maxVirtualAccounts: undefined as number | undefined,
    settlementFrequency: 'DAILY',
    settlementTime: '',
    minBalanceThreshold: undefined as number | undefined,
    balanceAggregationIntervalMinutes: 5,
    realtimeBalancePropagation: false,
    // Dates
    effectiveFrom: '',
    effectiveTo: '',
  });

    // Charge overrides (user edits) - separate from program formData
  const [chargeOverrides, setChargeOverrides] = useState<{
    topup: { percent?: number; flat?: number; waived: boolean };
    withdrawal: { percent?: number; flat?: number; waived: boolean };
    transfer: { percent?: number; flat?: number; waived: boolean };
    issuance: { flat?: number; waived: boolean };
    monthly: { flat?: number; waived: boolean };
    inactivity: { flat?: number; waived: boolean };
  }>({
    topup: { waived: false },
    withdrawal: { waived: false },
    transfer: { waived: false },
    issuance: { waived: false },
    monthly: { waived: false },
    inactivity: { waived: false },
  });

  // Hierarchy level configurations - customize allowed values per level
  const [hierarchyLevelConfigs, setHierarchyLevelConfigs] = useState<HierarchyLevelConfig[]>([]);
  const [showLevelCustomization, setShowLevelCustomization] = useState(false);
  const [expandedLevelIndex, setExpandedLevelIndex] = useState<number | null>(null);

  const isEdit = !!program;
  // Fetch wallet charges when editing a wallet program or when wallet is enabled
  const needsWalletConfig = formData.walletEnabled || formData.programType === 'WALLET';
  const needsHierarchyConfig = formData.hierarchyEnabled;

  // Dynamic step calculation
  const stepLabels = (() => {
    const labels = ['Basic Info', 'Features'];
    if (needsHierarchyConfig) labels.push('Hierarchy');
    if (formData.vibanEnabled) labels.push('VIBAN Pool');
    if (needsWalletConfig) labels.push('Wallet Limits');
    if (needsWalletConfig) labels.push('Wallet Fees');
    labels.push('Settlement', 'Review');
    return labels;
  })();

  // In edit mode, show 2 steps: Basic Info and Features
  const totalSteps = isEdit ? 2 : stepLabels.length;

  // Fetch corporates on modal open
  useEffect(() => {
    if (isOpen && !program) {
      setLoadingCorporates(true);
      fetch('/api/v1/corporates')
        .then(res => res.json())
        .then(data => {
          if (data.success) {
            const corps = data.data?.content || data.data || [];
            setCorporates(corps);
          }
        })
        .catch(console.error)
        .finally(() => setLoadingCorporates(false));
    }
  }, [isOpen, program]);

  // Fetch physical accounts when corporate is selected
  useEffect(() => {
    if (formData.corporateId && !program) {
      setLoadingAccounts(true);
      setPhysicalAccounts([]);
      setFormData(prev => ({ ...prev, physicalAccountId: '' }));
      fetch(`/api/v1/physical-accounts?corporateId=${formData.corporateId}`)
        .then(res => res.json())
        .then(data => {
          if (data.success) {
            const accounts = data.data?.content || data.data || [];
            setPhysicalAccounts(accounts);
          }
        })
        .catch(console.error)
        .finally(() => setLoadingAccounts(false));
    }
  }, [formData.corporateId, program]);

  // Fetch VIBAN pools when VIBAN is enabled
  useEffect(() => {
    if (formData.vibanEnabled && !program) {
      setLoadingPools(true);
      fetch('/api/v1/viban-pools')
        .then(res => res.json())
        .then(data => {
          if (data.success) {
            const pools = data.data?.content || data.data || [];
            setVibanPools(pools);
          }
        })
        .catch(console.error)
        .finally(() => setLoadingPools(false));
    }
  }, [formData.vibanEnabled, program]);

 
  
  useEffect(() => {
    if (isOpen && program && needsWalletConfig) {
      setLoadingCharges(true);
      fetchApi<WalletChargesResponse>(`/programs/${program.id}/wallet`, {}, CHARGES_API_BASE)
        .then(res => {
          if (res.success && res.data) {
            setWalletCharges(res.data);
            setChargeOverrides({
              topup: { 
                percent: res.data.topup.hasOverride ? res.data.topup.percentage : undefined, 
                flat: res.data.topup.hasOverride ? res.data.topup.fixed : undefined, 
                waived: res.data.topup.isWaived 
              },
              withdrawal: { 
                percent: res.data.withdrawal.hasOverride ? res.data.withdrawal.percentage : undefined, 
                flat: res.data.withdrawal.hasOverride ? res.data.withdrawal.fixed : undefined, 
                waived: res.data.withdrawal.isWaived 
              },
              transfer: { 
                percent: res.data.transfer.hasOverride ? res.data.transfer.percentage : undefined, 
                flat: res.data.transfer.hasOverride ? res.data.transfer.fixed : undefined, 
                waived: res.data.transfer.isWaived 
              },
              issuance: { 
                flat: res.data.issuance.hasOverride ? res.data.issuance.fixed : undefined, 
                waived: res.data.issuance.isWaived 
              },
              monthly: { 
                flat: res.data.monthly.hasOverride ? res.data.monthly.fixed : undefined, 
                waived: res.data.monthly.isWaived 
              },
              inactivity: { 
                flat: res.data.inactivity.hasOverride ? res.data.inactivity.fixed : undefined, 
                waived: res.data.inactivity.isWaived 
              },
            });
          } else {
            setWalletCharges(mockWalletCharges);
            // Reset overrides to defaults
            setChargeOverrides({
              topup: { waived: false },
              withdrawal: { waived: false },
              transfer: { waived: false },
              issuance: { waived: false },
              monthly: { waived: false },
              inactivity: { waived: false },
            });
          }
        })
        .finally(() => setLoadingCharges(false));
    } else if (isOpen && !program && needsWalletConfig) {
      setWalletCharges(mockWalletCharges);
      // Reset overrides for new program
      setChargeOverrides({
        topup: { waived: false },
        withdrawal: { waived: false },
        transfer: { waived: false },
        issuance: { waived: false },
        monthly: { waived: false },
        inactivity: { waived: false },
      });
    }
  }, [isOpen, program, needsWalletConfig]);

  // Reset form when modal opens/closes
  useEffect(() => {
    if (program) {
      setFormData({
        // Step 1: Basic Info
        programCode: program.programCode,
        programName: program.programName,
        programType: program.programType,
        description: program.description || '',
        corporateId: program.corporateId,
        physicalAccountId: program.physicalAccountId,
        currencyCode: program.currencyCode,
        // Step 2: Core Features
        vibanEnabled: program.vibanEnabled,
        walletEnabled: program.walletEnabled,
        escrowEnabled: program.escrowEnabled,
        ihbEnabled: program.ihbEnabled,
        autoReconciliation: program.autoReconciliation,
        hierarchyEnabled: program.hierarchyEnabled || false,
        // Step 2: Extended Program Features
        loyaltyEnabled: program.loyaltyEnabled || false,
        giftCardEnabled: program.giftCardEnabled || false,
        corporateCardEnabled: program.corporateCardEnabled || false,
        mobileMoneyEnabled: program.mobileMoneyEnabled || false,
        // Step 3: Hierarchy Config
        hierarchyDepth: program.hierarchyDepth || 7,
        defaultHierarchyTemplate: program.defaultHierarchyTemplate || '',
        // Step 4: VIBAN Pool Config
        defaultVibanPoolId: program.defaultVibanPoolId || '',
        vibanGenerationStrategy: program.vibanGenerationStrategy || 'SEQUENTIAL',
        vibanPrefix: program.vibanPrefix || '',
        vibanBankCode: program.vibanBankCode || '',
        // Step 5: Wallet Limits
        defaultPerTransactionLimit: program.defaultPerTransactionLimit,
        defaultDailyLimit: program.defaultDailyLimit,
        defaultWeeklyLimit: undefined,
        defaultMonthlyLimit: program.defaultMonthlyLimit,
        defaultYearlyLimit: undefined,
        defaultMaxBalance: program.defaultMaxBalance,
        minTopup: undefined,
        maxTopup: undefined,
        defaultDailyTopupLimit: undefined,
        defaultMonthlyTopupLimit: undefined,
        minWithdrawal: undefined,
        maxWithdrawal: undefined,
        // Wallet KYC
        kycRequired: program.kycRequired || false,
        autoKyc: false,
        minKycLevel: program.minKycLevel || 0,
        kycValidityDays: undefined,
        // Wallet Features
        allowTopup: program.allowTopup ?? true,
        allowWithdrawal: program.allowWithdrawal ?? true,
        allowTransfer: program.allowTransfer ?? true,
        allowPayment: program.allowPayment ?? true,
        allowBulkOperations: true,
        // Wallet Expiry
        walletExpiryDays: undefined,
        inactiveExpiryDays: undefined,
        defaultWalletType: 'CONSUMER',
        // Step 6: Settlement & Limits
        vaPrefix: program.vaPrefix || '',
        vaFormat: program.vaFormat || '',
        maxVirtualAccounts: program.maxVirtualAccounts,
        settlementFrequency: program.settlementFrequency || 'DAILY',
        settlementTime: program.settlementTime || '',
        minBalanceThreshold: program.minBalanceThreshold,
        balanceAggregationIntervalMinutes: program.balanceAggregationIntervalMinutes || 5,
        realtimeBalancePropagation: program.realtimeBalancePropagation || false,
        // Dates
        effectiveFrom: program.effectiveFrom || '',
        effectiveTo: program.effectiveTo || '',
      });
    } else {
      setFormData({
        // Step 1: Basic Info
        programCode: '',
        programName: '',
        programType: 'COLLECTION',
        description: '',
        corporateId: defaultCorporateId || '', // Use default corporate from page picker
        physicalAccountId: '',
        currencyCode: 'AED',
        // Step 2: Core Features
        vibanEnabled: false,
        walletEnabled: false,
        escrowEnabled: false,
        ihbEnabled: false,
        autoReconciliation: true,
        hierarchyEnabled: false,
        // Step 2: Extended Program Features
        loyaltyEnabled: false,
        giftCardEnabled: false,
        corporateCardEnabled: false,
        mobileMoneyEnabled: false,
        // Step 3: Hierarchy Config
        hierarchyDepth: 7,
        defaultHierarchyTemplate: '',
        // Step 4: VIBAN Pool Config
        defaultVibanPoolId: '',
        vibanGenerationStrategy: 'SEQUENTIAL',
        vibanPrefix: '',
        vibanBankCode: '',
        // Step 5: Wallet Limits
        defaultPerTransactionLimit: undefined,
        defaultDailyLimit: undefined,
        defaultWeeklyLimit: undefined,
        defaultMonthlyLimit: undefined,
        defaultYearlyLimit: undefined,
        defaultMaxBalance: undefined,
        minTopup: undefined,
        maxTopup: undefined,
        defaultDailyTopupLimit: undefined,
        defaultMonthlyTopupLimit: undefined,
        minWithdrawal: undefined,
        maxWithdrawal: undefined,
        // Wallet KYC
        kycRequired: false,
        autoKyc: false,
        minKycLevel: 0,
        kycValidityDays: undefined,
        // Wallet Features
        allowTopup: true,
        allowWithdrawal: true,
        allowTransfer: true,
        allowPayment: true,
        allowBulkOperations: true,
        // Wallet Expiry
        walletExpiryDays: undefined,
        inactiveExpiryDays: undefined,
        defaultWalletType: 'CONSUMER',
        // Step 6: Settlement & Limits
        vaPrefix: '',
        vaFormat: '',
        maxVirtualAccounts: undefined,
        settlementFrequency: 'DAILY',
        settlementTime: '',
        minBalanceThreshold: undefined,
        balanceAggregationIntervalMinutes: 5,
        realtimeBalancePropagation: false,
        // Dates
        effectiveFrom: '',
        effectiveTo: '',
      });
      setStep(1);
      // Reset charge overrides for new program
      setChargeOverrides({
        topup: { waived: false },
        withdrawal: { waived: false },
        transfer: { waived: false },
        issuance: { waived: false },
        monthly: { waived: false },
        inactivity: { waived: false },
      });
    }
  }, [program, isOpen, defaultCorporateId]);

  // Save wallet charges via ChargeConfiguration API
  const saveWalletCharges = async (programId: string) => {
    if (!needsWalletConfig) return;
    
    const request: WalletChargesRequest = {
      programId,
      // Transaction fee overrides
      topupFeePercent: chargeOverrides.topup.percent,
      topupFeeFlat: chargeOverrides.topup.flat,
      withdrawalFeePercent: chargeOverrides.withdrawal.percent,
      withdrawalFeeFlat: chargeOverrides.withdrawal.flat,
      transferFeePercent: chargeOverrides.transfer.percent,
      transferFeeFlat: chargeOverrides.transfer.flat,
      // Fixed fee overrides
      issuanceFee: chargeOverrides.issuance.flat,
      monthlyFee: chargeOverrides.monthly.flat,
      inactivityFee: chargeOverrides.inactivity.flat,
      // ALL waivers
      waiveTopup: chargeOverrides.topup.waived,
      waiveWithdrawal: chargeOverrides.withdrawal.waived,
      waiveTransfer: chargeOverrides.transfer.waived,
      waiveIssuance: chargeOverrides.issuance.waived,
      waiveMonthly: chargeOverrides.monthly.waived,
      waiveInactivity: chargeOverrides.inactivity.waived,
    };

    try {
      const result = await fetchApi<WalletChargesResponse>(
        `/programs/${programId}/wallet`, 
        { method: 'PUT', body: JSON.stringify(request) }, 
        CHARGES_API_BASE
      );
      if (!result.success) {
        console.error('Failed to save wallet charges:', result.message);
      }
    } catch (error) {
      console.error('Error saving wallet charges:', error);
    }
  };

  const handleSubmit = async () => {
    // Resolve corporate ID - prioritize formData, fallback to defaultCorporateId
    const resolvedCorporateId = formData.corporateId || defaultCorporateId;

    // Debug logging
    console.log('handleSubmit - corporateId resolution:', {
      'formData.corporateId': formData.corporateId,
      'defaultCorporateId': defaultCorporateId,
      'resolvedCorporateId': resolvedCorporateId,
      'isEdit': isEdit
    });

    // Validate corporate ID for new programs
    if (!isEdit && !resolvedCorporateId) {
      console.error('Corporate ID is required for new programs');
      alert('Please select a corporate before creating a program.');
      return;
    }

    setLoading(true);
    try {
      const cleanedData = {
        programCode: formData.programCode,
        programName: formData.programName,
        programType: formData.programType,
        description: formData.description || undefined,
        corporateId: resolvedCorporateId,
        physicalAccountId: formData.physicalAccountId || undefined,
        currencyCode: formData.currencyCode,
        // Core feature flags
        vibanEnabled: formData.vibanEnabled,
        walletEnabled: formData.walletEnabled,
        escrowEnabled: formData.escrowEnabled,
        ihbEnabled: formData.ihbEnabled,
        autoReconciliation: formData.autoReconciliation,
        hierarchyEnabled: formData.hierarchyEnabled,
        // Extended program features
        loyaltyEnabled: formData.loyaltyEnabled,
        giftCardEnabled: formData.giftCardEnabled,
        corporateCardEnabled: formData.corporateCardEnabled,
        mobileMoneyEnabled: formData.mobileMoneyEnabled,
        // Hierarchy Configuration
        hierarchyDepth: formData.hierarchyEnabled ? formData.hierarchyDepth : undefined,
        defaultHierarchyTemplate: formData.hierarchyEnabled ? (formData.defaultHierarchyTemplate || undefined) : undefined,
        // VIBAN Pool Config
        defaultVibanPoolId: formData.vibanEnabled ? (formData.defaultVibanPoolId || undefined) : undefined,
        vibanGenerationStrategy: formData.vibanEnabled ? formData.vibanGenerationStrategy : undefined,
        vibanPrefix: formData.vibanEnabled ? (formData.vibanPrefix || undefined) : undefined,
        vibanBankCode: formData.vibanEnabled ? (formData.vibanBankCode || undefined) : undefined,
        // Wallet Limits Configuration
        defaultPerTransactionLimit: needsWalletConfig ? formData.defaultPerTransactionLimit : undefined,
        defaultDailyLimit: needsWalletConfig ? formData.defaultDailyLimit : undefined,
        defaultMonthlyLimit: needsWalletConfig ? formData.defaultMonthlyLimit : undefined,
        defaultMaxBalance: needsWalletConfig ? formData.defaultMaxBalance : undefined,
        kycRequired: needsWalletConfig ? formData.kycRequired : undefined,
        minKycLevel: needsWalletConfig && formData.kycRequired ? formData.minKycLevel : undefined,
        allowTopup: needsWalletConfig ? formData.allowTopup : undefined,
        allowWithdrawal: needsWalletConfig ? formData.allowWithdrawal : undefined,
        allowTransfer: needsWalletConfig ? formData.allowTransfer : undefined,
        allowPayment: needsWalletConfig ? formData.allowPayment : undefined,
        // Settlement & Limits
        vaPrefix: formData.vaPrefix || undefined,
        vaFormat: formData.vaFormat || undefined,
        maxVirtualAccounts: formData.maxVirtualAccounts || undefined,
        settlementFrequency: formData.settlementFrequency || undefined,
        settlementTime: formData.settlementTime ? formData.settlementTime + ':00' : undefined,
        minBalanceThreshold: formData.minBalanceThreshold || undefined,
        balanceAggregationIntervalMinutes: formData.balanceAggregationIntervalMinutes || undefined,
        realtimeBalancePropagation: formData.realtimeBalancePropagation,
        effectiveFrom: formData.effectiveFrom || undefined,
        effectiveTo: formData.effectiveTo || undefined,
        // Hierarchy Level Configurations (saved separately after program creation)
        hierarchyLevelConfigs: formData.hierarchyEnabled && hierarchyLevelConfigs.length > 0
          ? hierarchyLevelConfigs.slice(0, formData.hierarchyDepth).map((level, idx) => ({
              levelNumber: idx + 1,
              levelName: level.levelName,
              dimensionType: level.dimensionType,
              isRequired: level.isRequired,
              allowedValues: level.allowedValues,
              description: level.description,
              icon: level.icon,
            }))
          : undefined,
      };

      // Debug: log the cleanedData being sent (stringified to see undefined values)
      console.log('handleSubmit - cleanedData (JSON):', JSON.stringify(cleanedData, null, 2));
      console.log('handleSubmit - key fields:', {
        corporateId: cleanedData.corporateId,
        physicalAccountId: cleanedData.physicalAccountId,
        programCode: cleanedData.programCode,
        programType: cleanedData.programType
      });

      let programId: string;

      if (program) {
        // Edit existing program
        await onSave(cleanedData);
        programId = program.id;
      } else {
        // Create new program - onSave returns void but we need the ID
        await onSave(cleanedData);
        programId = 'new'; // The actual save happens in parent, charges saved on next edit
      }
      
      // Save wallet charges separately via ChargeConfiguration API
      if (needsWalletConfig && program) {
        await saveWalletCharges(programId);
      }
      
      onClose();
    } finally {
      setLoading(false);
    }
  };

  // Get step index for a specific step type
  const getStepIndex = (stepType: string): number => {
    return stepLabels.indexOf(stepType) + 1;
  };

  // Check if current step matches a step type
  const isStepActive = (stepType: string): boolean => {
    return step === getStepIndex(stepType);
  };

  const canProceed = () => {
    switch (step) {
      case 1: return formData.programCode && formData.programName && (isEdit || formData.corporateId || defaultCorporateId);
      case 2: return true; // Features are optional
      case 3: return !formData.vibanEnabled || true; // VIBAN config is optional
      case 4: return true; // Settlement is optional
      default: return true;
    }
  };

  const selectedPool = vibanPools.find(p => p.id === formData.defaultVibanPoolId);

  return (
    <Modal isOpen={isOpen} onClose={onClose} size="lg" title={isEdit ? 'Edit Program' : 'Create New Program'}>
      <div className="space-y-6">
        {/* Step Indicator - Show for Create and Edit (limited steps in edit mode) */}
        {(isEdit ? totalSteps > 1 : true) && (
          <div className="flex items-center justify-center gap-1 mb-6">
            {(isEdit ? ['Basic Info', 'Features'] : stepLabels).map((label, i) => {
              const stepNum = i + 1;
              const isActive = step === stepNum;
              const isComplete = step > stepNum;
              return (
                <React.Fragment key={label}>
                  <div className="flex flex-col items-center">
                    <div className={cn(
                      'w-8 h-8 rounded-full flex items-center justify-center text-sm font-medium transition-all',
                      isComplete ? 'bg-success-500 text-white' : isActive ? 'bg-primary-600 text-white' : 'bg-neutral-200 text-neutral-500 dark:bg-primary-800 dark:text-neutral-400'
                    )}>
                      {isComplete ? <CheckCircle className="w-4 h-4" /> : stepNum}
                    </div>
                    <span className={cn('text-xs mt-1', isActive ? 'text-primary-600 font-medium dark:text-primary-200' : 'text-neutral-400 dark:text-neutral-500')}>
                      {label}
                    </span>
                  </div>
                  {i < (isEdit ? 1 : stepLabels.length - 1) && (
                    <div className={cn('w-12 h-1 mx-1 rounded', isComplete ? 'bg-success-500' : 'bg-neutral-200 dark:bg-primary-800')} />
                  )}
                </React.Fragment>
              );
            })}
          </div>
        )}

        {/* ================================================================ */}
        {/* STEP 1: Basic Information */}
        {/* ================================================================ */}
        {step === 1 && (
          <div className="space-y-4">
            <h3 className="font-medium text-primary-900 flex items-center gap-2 dark:text-neutral-50">
              <Building2 className="w-4 h-4" />
              {isEdit ? 'Step 1: Program Details' : 'Step 1: Basic Information'}
            </h3>

            {/* Corporate Selection - First (most important context) */}
            {!isEdit && (
              <div className="p-3 bg-neutral-50 rounded-lg border border-neutral-200 dark:bg-primary-950 dark:border-primary-800">
                <label className="field-label block mb-1">Corporate *</label>
                {defaultCorporateId ? (
                  // Corporate is pre-selected from page picker - show as read-only
                  <div className="flex items-center gap-2">
                    <Building2 className="w-4 h-4 text-primary-600 dark:text-primary-200" />
                    <span className="font-medium text-primary-900 dark:text-neutral-50">
                      {corporates.find(c => c.id === defaultCorporateId)?.legalName || 'Loading...'}
                    </span>
                    <Badge variant="info" size="sm">Selected</Badge>
                  </div>
                ) : (
                  // No corporate pre-selected - allow selection
                  <select
                    className={cn('w-full px-3 py-2 border rounded-lg bg-white dark:bg-primary-900', !formData.corporateId ? 'border-warning-300' : 'border-neutral-300 dark:border-primary-700')}
                    value={formData.corporateId}
                    onChange={e => setFormData({ ...formData, corporateId: e.target.value, physicalAccountId: '' })}
                    disabled={loadingCorporates}
                  >
                    <option value="">{loadingCorporates ? 'Loading...' : 'Select Corporate...'}</option>
                    {corporates.map(c => (
                      <option key={c.id} value={c.id}>{c.legalName}</option>
                    ))}
                  </select>
                )}
                <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">Programs are created under a specific corporate entity.</p>
              </div>
            )}

            {/* Program Type Selection */}
            <div>
              <label className="field-label block mb-2">Program Type *</label>
              <div className="grid grid-cols-4 gap-2">
                {(Object.keys(programTypeConfig) as ProgramType[]).map(type => {
                  const config = programTypeConfig[type];
                  const Icon = config.icon;
                  return (
                    <button
                      key={type}
                      type="button"
                      onClick={() => {
                        // Auto-enable corresponding feature flags based on program type
                        const autoEnabledFeatures = PROGRAM_TYPE_FEATURE_MAP[type] || {};
                        setFormData({
                          ...formData,
                          programType: type,
                          ...autoEnabledFeatures
                        });
                      }}
                      disabled={isEdit}
                      className={cn(
                        'p-2.5 border rounded-lg text-left transition-all',
                        formData.programType === type ? 'border-primary-500 bg-primary-50 ring-2 ring-primary-500 dark:bg-primary-800/40' : 'border-neutral-200 hover:border-neutral-300 dark:border-primary-800 dark:hover:border-primary-700',
                        isEdit && 'opacity-60 cursor-not-allowed'
                      )}
                    >
                      <div className="flex items-center gap-2">
                        <Icon className={cn('w-4 h-4', config.color)} />
                        <span className="font-medium text-xs">{config.label}</span>
                      </div>
                    </button>
                  );
                })}
              </div>
            </div>

            {/* Program Code and Name */}
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="field-label block mb-1">Program Code *</label>
                <input
                  type="text"
                  className="w-full px-3 py-2 border border-neutral-300 rounded-lg font-mono dark:border-primary-700"
                  placeholder="e.g., COLL-001"
                  value={formData.programCode}
                  onChange={e => setFormData({ ...formData, programCode: e.target.value.toUpperCase() })}
                  disabled={isEdit}
                />
              </div>
              <div>
                <label className="field-label block mb-1">Program Name *</label>
                <input
                  type="text"
                  className="w-full px-3 py-2 border border-neutral-300 rounded-lg dark:border-primary-700"
                  placeholder="e.g., Main Collection Program"
                  value={formData.programName}
                  onChange={e => setFormData({ ...formData, programName: e.target.value })}
                />
              </div>
            </div>

            {/* Currency and Max VAs */}
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="field-label block mb-1">Currency *</label>
                <CurrencyPicker
                  value={formData.currencyCode}
                  onChange={(c) => setFormData({ ...formData, currencyCode: c })}
                  disabled={isEdit}
                  withName
                  extra={['SAR', 'INR']}
                />
              </div>
              <div>
                <label className="field-label block mb-1">Max Virtual Accounts</label>
                <input
                  type="number"
                  className="w-full px-3 py-2 border border-neutral-300 rounded-lg dark:border-primary-700"
                  placeholder="Unlimited"
                  value={formData.maxVirtualAccounts || ''}
                  onChange={e => setFormData({ ...formData, maxVirtualAccounts: e.target.value ? parseInt(e.target.value) : undefined })}
                />
              </div>
            </div>

            {/* Description */}
            <div>
              <label className="field-label block mb-1">Description</label>
              <textarea
                className="w-full px-3 py-2 border border-neutral-300 rounded-lg dark:border-primary-700"
                rows={2}
                placeholder="Brief description of the program purpose..."
                value={formData.description}
                onChange={e => setFormData({ ...formData, description: e.target.value })}
              />
            </div>

            {/* Physical Account - Optional, shown at bottom */}
            {!isEdit && (
              <div className="border-t pt-4">
                <label className="field-label block mb-1">
                  Physical Account <span className="text-neutral-400 font-normal dark:text-neutral-500">(Optional)</span>
                </label>
                <select
                  className="w-full px-3 py-2 border border-neutral-300 rounded-lg dark:border-primary-700"
                  value={formData.physicalAccountId}
                  onChange={e => setFormData({ ...formData, physicalAccountId: e.target.value })}
                  disabled={!formData.corporateId || loadingAccounts}
                >
                  <option value="">
                    {!formData.corporateId ? 'Select corporate first...' : loadingAccounts ? 'Loading...' : 'None (VA hierarchy only)'}
                  </option>
                  {physicalAccounts.map(a => (
                    <option key={a.id} value={a.id}>{a.accountNumber} - {a.bankName} ({a.currencyCode})</option>
                  ))}
                </select>
                <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">Physical account provides liquidity backing. Not required for hierarchy-only programs.</p>
              </div>
            )}
          </div>
        )}

        {/* ================================================================ */}
        {/* STEP 2: Features */}
        {/* ================================================================ */}
        {step === 2 && (
          <div className="space-y-4">
            <h3 className="font-medium text-primary-900 flex items-center gap-2 dark:text-neutral-50">
              <Settings className="w-4 h-4" />
              Step 2: Features & Capabilities
            </h3>

            {/* Program Type Summary */}
            <div className="p-3 bg-primary-50 rounded-lg border border-primary-200 dark:bg-primary-800/40 dark:border-primary-700">
              <div className="flex items-center gap-2 text-sm">
                {(() => {
                  const config = programTypeConfig[formData.programType];
                  const Icon = config?.icon || Building2;
                  return (
                    <>
                      <Icon className={cn('w-5 h-5', config?.color)} />
                      <span className="font-medium text-primary-900 dark:text-neutral-50">{config?.label} Program</span>
                      <span className="text-neutral-500 dark:text-neutral-400">— {config?.description}</span>
                    </>
                  );
                })()}
              </div>
              {/* Show auto-enabled features for this program type */}
              {(() => {
                const autoFeatures = PROGRAM_TYPE_FEATURE_MAP[formData.programType];
                const autoFeatureNames = Object.entries(autoFeatures || {})
                  .filter(([, enabled]) => enabled)
                  .map(([key]) => {
                    const featureLabels: Record<string, string> = {
                      vibanEnabled: 'VIBAN',
                      walletEnabled: 'Wallet',
                      escrowEnabled: 'Escrow',
                      ihbEnabled: 'IHB',
                      hierarchyEnabled: 'Hierarchy',
                      loyaltyEnabled: 'Loyalty',
                      giftCardEnabled: 'Gift Card',
                      corporateCardEnabled: 'Corporate Card',
                      mobileMoneyEnabled: 'Mobile Money',
                    };
                    return featureLabels[key] || key;
                  });
                if (autoFeatureNames.length > 0) {
                  return (
                    <div className="flex items-center gap-2 mt-2 flex-wrap">
                      <Sparkles className="w-3.5 h-3.5 text-success-600 dark:text-success-300" />
                      <span className="text-xs text-success-700 font-medium dark:text-success-300">Auto-enabled:</span>
                      {autoFeatureNames.map(name => (
                        <Badge key={name} variant="success" size="sm">{name}</Badge>
                      ))}
                    </div>
                  );
                }
                return null;
              })()}
              <p className="text-xs text-neutral-600 mt-2 dark:text-neutral-300">
                Features marked <Badge variant="success" size="sm">Auto</Badge> are recommended for this program type. You can still toggle them on/off.
              </p>
            </div>

            {/* Core Features - These affect wizard flow */}
            <div className="space-y-2">
              <h4 className="text-xs font-semibold text-neutral-500 uppercase tracking-wider dark:text-neutral-400">Capabilities (affects configuration steps)</h4>
              <div className="grid grid-cols-2 gap-3">
                {[
                  { key: 'hierarchyEnabled', label: 'Multi-Level Hierarchy', icon: GitBranch, desc: 'Organize VAs in 7-level tree structure', color: 'text-cat-2', step: 'Hierarchy Config' },
                  { key: 'vibanEnabled', label: 'VIBAN Support', icon: Hash, desc: 'Virtual IBAN for each VA', color: 'text-accent-600 dark:text-accent-300', step: 'VIBAN Pool Config' },
                  { key: 'walletEnabled', label: 'Wallet Features', icon: Wallet, desc: 'Prepaid wallet with limits & KYC', color: 'text-warning-600 dark:text-warning-300', step: 'Wallet Limits & Fees' },
                  { key: 'escrowEnabled', label: 'Escrow Features', icon: Shield, desc: 'Hold funds with release conditions', color: 'text-success-600 dark:text-success-300', step: null },
                  { key: 'ihbEnabled', label: 'IHB Features', icon: Building2, desc: 'In-house banking capabilities', color: 'text-primary-600 dark:text-primary-200', step: null },
                  { key: 'autoReconciliation', label: 'Auto Reconciliation', icon: Zap, desc: 'Automatic transaction matching', color: 'text-success-600 dark:text-success-300', step: null },
                ].map(f => {
                  const Icon = f.icon;
                  const isChecked = formData[f.key as keyof typeof formData] as boolean;
                  // Check if this feature was auto-enabled by the selected program type
                  const autoEnabledFeatures = PROGRAM_TYPE_FEATURE_MAP[formData.programType] || {};
                  const isAutoEnabled = autoEnabledFeatures[f.key as keyof FeatureFlags] === true;
                  return (
                    <label
                      key={f.key}
                      className={cn(
                        'flex items-start gap-3 p-3 border rounded-lg cursor-pointer transition-all',
                        isChecked ? 'border-primary-500 bg-primary-50 ring-1 ring-primary-500 dark:bg-primary-800/40' : 'border-neutral-200 hover:border-neutral-300 dark:border-primary-800 dark:hover:border-primary-700',
                        isAutoEnabled && isChecked && 'ring-2 ring-primary-400'
                      )}
                    >
                      <input
                        type="checkbox"
                        className="mt-1"
                        checked={isChecked}
                        onChange={e => setFormData({ ...formData, [f.key]: e.target.checked })}
                      />
                      <div className="flex-1">
                        <div className="flex items-center gap-2 flex-wrap">
                          <Icon className={cn('w-4 h-4', f.color)} />
                          <span className="font-medium text-sm text-primary-900 dark:text-neutral-50">{f.label}</span>
                          {isAutoEnabled && isChecked && <Badge variant="success" size="sm">Auto</Badge>}
                          {f.step && isChecked && <Badge variant="info" size="sm">+Step</Badge>}
                        </div>
                        <p className="text-xs text-neutral-500 mt-0.5 dark:text-neutral-400">{f.desc}</p>
                      </div>
                    </label>
                  );
                })}
              </div>
            </div>

            {/* Extended Program Features - Only show if relevant */}
            {['LOYALTY', 'GIFT_CARD', 'CORPORATE_CARD', 'MOBILE_MONEY'].includes(formData.programType) && (
              <div className="space-y-2">
                <h4 className="text-xs font-semibold text-neutral-500 uppercase tracking-wider dark:text-neutral-400">Extended Capabilities</h4>
                <div className="grid grid-cols-2 gap-3">
                  {[
                    { key: 'loyaltyEnabled', label: 'Loyalty Program', icon: TrendingUp, desc: 'Points, tiers, rewards', color: 'text-cat-4', forType: 'LOYALTY' },
                    { key: 'giftCardEnabled', label: 'Gift Cards', icon: Gift, desc: 'Gift card issuance', color: 'text-cat-2', forType: 'GIFT_CARD' },
                    { key: 'corporateCardEnabled', label: 'Corporate Cards', icon: CreditCard, desc: 'Expense cards, limits', color: 'text-cat-1', forType: 'CORPORATE_CARD' },
                    { key: 'mobileMoneyEnabled', label: 'Mobile Money', icon: Smartphone, desc: 'Agent banking, M-Pesa style', color: 'text-cat-3', forType: 'MOBILE_MONEY' },
                  ].filter(f => f.forType === formData.programType || formData[f.key as keyof typeof formData]).map(f => {
                    const Icon = f.icon;
                    const isChecked = formData[f.key as keyof typeof formData] as boolean;
                    // Check if this feature was auto-enabled by the selected program type
                    const autoEnabledFeatures = PROGRAM_TYPE_FEATURE_MAP[formData.programType] || {};
                    const isAutoEnabled = autoEnabledFeatures[f.key as keyof FeatureFlags] === true;
                    return (
                      <label
                        key={f.key}
                        className={cn(
                          'flex items-start gap-3 p-3 border rounded-lg cursor-pointer transition-all',
                          isChecked ? 'border-primary-500 bg-primary-50 ring-1 ring-primary-500 dark:bg-primary-800/40' : 'border-neutral-200 hover:border-neutral-300 dark:border-primary-800 dark:hover:border-primary-700',
                          isAutoEnabled && isChecked && 'ring-2 ring-primary-400'
                        )}
                      >
                        <input
                          type="checkbox"
                          className="mt-1"
                          checked={isChecked}
                          onChange={e => setFormData({ ...formData, [f.key]: e.target.checked })}
                        />
                        <div className="flex-1">
                          <div className="flex items-center gap-2 flex-wrap">
                            <Icon className={cn('w-4 h-4', f.color)} />
                            <span className="font-medium text-sm text-primary-900 dark:text-neutral-50">{f.label}</span>
                            {isAutoEnabled && isChecked && <Badge variant="success" size="sm">Auto</Badge>}
                          </div>
                          <p className="text-xs text-neutral-500 mt-0.5 dark:text-neutral-400">{f.desc}</p>
                        </div>
                      </label>
                    );
                  })}
                </div>
              </div>
            )}

            {/* Info messages for enabled features */}
            {formData.vibanEnabled && (
              <div className="bg-info-50 border border-info-200 rounded-lg p-3 flex items-start gap-2 dark:bg-info-500/10 dark:border-info-500/30">
                <Info className="w-4 h-4 text-info-600 mt-0.5 dark:text-info-300" />
                <p className="text-sm text-info-700 dark:text-info-300">
                  VIBAN is enabled. You'll configure the VIBAN pool settings in the next step.
                </p>
              </div>
            )}

            {formData.hierarchyEnabled && (
              <div className="bg-cat-2-soft border border-cat-2/20 rounded-lg p-3 flex items-start gap-2 dark:bg-cat-2/15 dark:border-cat-2/30">
                <GitBranch className="w-4 h-4 text-cat-2 mt-0.5" />
                <p className="text-sm text-cat-2">
                  Hierarchy is enabled. You'll configure the hierarchy template and depth in the next step.
                </p>
              </div>
            )}
          </div>
        )}

        {/* ================================================================ */}
        {/* HIERARCHY CONFIGURATION STEP (only if hierarchy enabled) */}
        {/* ================================================================ */}
        {isStepActive('Hierarchy') && !isEdit && (
          <div className="space-y-4">
            <h3 className="font-medium text-primary-900 flex items-center gap-2 dark:text-neutral-50">
              <GitBranch className="w-4 h-4" />
              Hierarchy Configuration
            </h3>

            <p className="text-sm text-neutral-500 dark:text-neutral-400">Configure the hierarchy structure for this program. The hierarchy defines how virtual accounts are organized.</p>

            {/* Hierarchy Template Selection - Dynamic based on Program Type */}
            {(() => {
              // Get templates filtered by program type
              const matchingTemplates = getTemplatesForProgramType(formData.programType);
              const recommendedTemplate = getRecommendedTemplate(formData.programType);
              // Get other templates that don't match but can still be used
              const otherTemplates = HIERARCHY_TEMPLATES.filter(
                t => !t.forProgramTypes.includes(formData.programType)
              );

              return (
                <div className="space-y-4">
                  {/* Recommended Templates for this Program Type */}
                  {matchingTemplates.length > 0 && (
                    <div>
                      <label className="field-label block mb-2">
                        Recommended for {programTypeConfig[formData.programType]?.label || formData.programType}
                      </label>
                      <div className="grid grid-cols-2 gap-3">
                        {matchingTemplates.map(template => {
                          const Icon = template.icon;
                          const isSelected = formData.defaultHierarchyTemplate === template.id;
                          const isRecommended = recommendedTemplate?.id === template.id;
                          const levelPath = template.levels.map(l => l.levelName).join(' → ');
                          return (
                            <button
                              key={template.id}
                              type="button"
                              onClick={() => {
                                setFormData({ ...formData, defaultHierarchyTemplate: template.id, hierarchyDepth: template.levels.length });
                                setHierarchyLevelConfigs([...template.levels]);
                              }}
                              className={cn(
                                'p-4 border rounded-lg text-left transition-all relative',
                                isSelected ? 'border-cat-2 bg-cat-2-soft ring-2 ring-cat-2 dark:bg-cat-2/15' : 'border-neutral-200 hover:border-neutral-300 dark:border-primary-800 dark:hover:border-primary-700'
                              )}
                            >
                              {isRecommended && (
                                <span className="absolute -top-2 -right-2 bg-success-500 text-white text-xs px-2 py-0.5 rounded-full">
                                  Recommended
                                </span>
                              )}
                              <div className="flex items-center gap-2 mb-2">
                                <Icon className={cn('w-5 h-5', isSelected ? 'text-cat-2' : template.color)} />
                                <span className="font-medium text-sm">{template.name}</span>
                              </div>
                              <p className="text-xs text-neutral-600 mb-2 dark:text-neutral-300">{template.description}</p>
                              <p className="text-xs text-neutral-400 truncate dark:text-neutral-500" title={levelPath}>{levelPath}</p>
                            </button>
                          );
                        })}
                      </div>
                    </div>
                  )}

                  {/* Other Available Templates */}
                  {otherTemplates.length > 0 && (
                    <div>
                      <label className="block text-sm font-medium text-neutral-500 mb-2 dark:text-neutral-400">
                        Other Templates ({otherTemplates.length} available)
                      </label>
                      <div className="grid grid-cols-3 gap-2">
                        {otherTemplates.map(template => {
                          const Icon = template.icon;
                          const isSelected = formData.defaultHierarchyTemplate === template.id;
                          return (
                            <button
                              key={template.id}
                              type="button"
                              onClick={() => {
                                setFormData({ ...formData, defaultHierarchyTemplate: template.id, hierarchyDepth: template.levels.length });
                                setHierarchyLevelConfigs([...template.levels]);
                              }}
                              className={cn(
                                'p-3 border rounded-lg text-left transition-all',
                                isSelected ? 'border-cat-2 bg-cat-2-soft ring-2 ring-cat-2 dark:bg-cat-2/15' : 'border-neutral-200 hover:border-neutral-300 dark:border-primary-800 dark:hover:border-primary-700'
                              )}
                            >
                              <div className="flex items-center gap-2">
                                <Icon className={cn('w-4 h-4', isSelected ? 'text-cat-2' : 'text-neutral-400 dark:text-neutral-500')} />
                                <span className="text-xs font-medium truncate">{template.name}</span>
                              </div>
                            </button>
                          );
                        })}
                      </div>
                    </div>
                  )}

                  {/* No matching templates fallback */}
                  {matchingTemplates.length === 0 && (
                    <div>
                      <label className="field-label block mb-2">Available Templates</label>
                      <div className="grid grid-cols-2 gap-3">
                        {HIERARCHY_TEMPLATES.map(template => {
                          const Icon = template.icon;
                          const isSelected = formData.defaultHierarchyTemplate === template.id;
                          const levelPath = template.levels.map(l => l.levelName).join(' → ');
                          return (
                            <button
                              key={template.id}
                              type="button"
                              onClick={() => {
                                setFormData({ ...formData, defaultHierarchyTemplate: template.id, hierarchyDepth: template.levels.length });
                                setHierarchyLevelConfigs([...template.levels]);
                              }}
                              className={cn(
                                'p-4 border rounded-lg text-left transition-all',
                                isSelected ? 'border-cat-2 bg-cat-2-soft ring-2 ring-cat-2 dark:bg-cat-2/15' : 'border-neutral-200 hover:border-neutral-300 dark:border-primary-800 dark:hover:border-primary-700'
                              )}
                            >
                              <div className="flex items-center gap-2 mb-2">
                                <Icon className={cn('w-5 h-5', isSelected ? 'text-cat-2' : template.color)} />
                                <span className="font-medium text-sm">{template.name}</span>
                              </div>
                              <p className="text-xs text-neutral-500 truncate dark:text-neutral-400" title={levelPath}>{levelPath}</p>
                            </button>
                          );
                        })}
                      </div>
                    </div>
                  )}
                </div>
              );
            })()}

            {/* Hierarchy Depth */}
            <div>
              <label className="field-label block mb-2">Hierarchy Depth (1-7 levels)</label>
              <div className="flex items-center gap-4">
                <input
                  type="range"
                  min="1"
                  max="7"
                  value={formData.hierarchyDepth}
                  onChange={e => setFormData({ ...formData, hierarchyDepth: parseInt(e.target.value) })}
                  className="flex-1"
                />
                <span className="text-lg font-semibold text-primary-900 w-8 text-center dark:text-neutral-50">{formData.hierarchyDepth}</span>
              </div>
              <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">
                {formData.hierarchyDepth === 7 ? 'Full hierarchy (recommended)' :
                 formData.hierarchyDepth === 1 ? 'Flat structure (no hierarchy)' :
                 `${formData.hierarchyDepth} levels of organization`}
              </p>
            </div>

            {/* Hierarchy Level Configuration - Expandable UI */}
            {formData.defaultHierarchyTemplate && hierarchyLevelConfigs.length > 0 && (
              <div className="bg-neutral-50 rounded-lg p-4 dark:bg-primary-950">
                <div className="flex items-center justify-between mb-3">
                  <h4 className="field-label flex items-center gap-2">
                    <Settings className="w-4 h-4" />
                    Level Configuration
                  </h4>
                  <button
                    type="button"
                    onClick={() => setShowLevelCustomization(!showLevelCustomization)}
                    className="text-xs text-cat-2 flex items-center gap-1"
                  >
                    {showLevelCustomization ? 'Collapse All' : 'Customize Levels'}
                    {showLevelCustomization ? <ChevronRight className="w-3 h-3 rotate-90" /> : <ChevronRight className="w-3 h-3" />}
                  </button>
                </div>

                <div className="space-y-2">
                  {hierarchyLevelConfigs.slice(0, formData.hierarchyDepth).map((level, idx) => {
                    const isExpanded = expandedLevelIndex === idx;
                    const isRoot = idx === 0;
                    const isLeaf = idx === formData.hierarchyDepth - 1;

                    return (
                      <div key={idx} className={cn(
                        'border rounded-lg transition-all',
                        isExpanded ? 'border-cat-2/30 bg-white dark:bg-primary-900' : 'border-neutral-200 bg-neutral-50 dark:border-primary-800 dark:bg-primary-950'
                      )}>
                        {/* Level Header - Always visible */}
                        <button
                          type="button"
                          onClick={() => setExpandedLevelIndex(isExpanded ? null : idx)}
                          className="w-full px-3 py-2 flex items-center gap-2 text-left"
                        >
                          <span className={cn(
                            'w-6 h-6 rounded-full flex items-center justify-center text-xs font-medium flex-shrink-0',
                            isRoot ? 'bg-cat-2 text-white' :
                            isLeaf ? 'bg-success-500 text-white' :
                            'bg-neutral-300 text-neutral-700 dark:text-neutral-200'
                          )}>
                            {idx + 1}
                          </span>
                          <span className="text-sm font-medium text-neutral-800 flex-1 dark:text-neutral-100">{level.levelName}</span>
                          <span className="text-xs text-neutral-400 dark:text-neutral-500">({level.dimensionType})</span>
                          {isRoot && <Badge variant="info" size="sm">Root</Badge>}
                          {isLeaf && <Badge variant="success" size="sm">Leaf</Badge>}
                          {level.allowedValues && level.allowedValues.length > 0 && (
                            <span className="text-xs bg-cat-2/10 text-cat-2 px-2 py-0.5 rounded-full dark:bg-cat-2/15">
                              {level.allowedValues.length} values
                            </span>
                          )}
                          <ChevronRight className={cn(
                            'w-4 h-4 text-neutral-400 transition-transform dark:text-neutral-500',
                            isExpanded && 'rotate-90'
                          )} />
                        </button>

                        {/* Level Details - Expanded */}
                        {isExpanded && (
                          <div className="px-3 pb-3 border-t border-neutral-200 pt-3 space-y-3 dark:border-primary-800">
                            {/* Level Name Edit */}
                            <div>
                              <label className="block text-xs font-medium text-neutral-600 mb-1 dark:text-neutral-300">Level Name</label>
                              <input
                                type="text"
                                value={level.levelName}
                                onChange={e => {
                                  const updated = [...hierarchyLevelConfigs];
                                  updated[idx] = { ...updated[idx], levelName: e.target.value };
                                  setHierarchyLevelConfigs(updated);
                                }}
                                className="w-full px-2 py-1 text-sm border border-neutral-300 rounded dark:border-primary-700"
                              />
                            </div>

                            {/* Dimension Type */}
                            <div>
                              <label className="block text-xs font-medium text-neutral-600 mb-1 dark:text-neutral-300">Dimension Type</label>
                              <select
                                value={level.dimensionType}
                                onChange={e => {
                                  const updated = [...hierarchyLevelConfigs];
                                  updated[idx] = { ...updated[idx], dimensionType: e.target.value };
                                  setHierarchyLevelConfigs(updated);
                                }}
                                className="w-full px-2 py-1 text-sm border border-neutral-300 rounded dark:border-primary-700"
                              >
                                <option value="CURRENCY">💱 Currency</option>
                                <option value="REGION">🌍 Region</option>
                                <option value="COUNTRY">🏳️ Country</option>
                                <option value="STATE">📍 State/Province</option>
                                <option value="CITY">🏙️ City</option>
                                <option value="ENTITY">🏢 Legal Entity</option>
                                <option value="DEPARTMENT">👥 Department</option>
                                <option value="COST_CENTER">💰 Cost Center</option>
                                <option value="ACCOUNT_TYPE">📊 Account Type</option>
                                <option value="CHANNEL">📡 Channel</option>
                                <option value="PLATFORM">🌐 Platform</option>
                                <option value="SEGMENT">🎯 Segment</option>
                                <option value="CUSTOMER">👤 Customer</option>
                                <option value="MERCHANT">🏪 Merchant</option>
                                <option value="PARTNER">🤝 Partner</option>
                                <option value="TIER">⭐ Tier</option>
                                <option value="BUDGET_OWNER">📋 Budget Owner</option>
                                <option value="VIRTUAL_ACCOUNT">💳 Virtual Account</option>
                              </select>
                            </div>

                            {/* Allowed Values */}
                            <div>
                              <div className="flex items-center justify-between mb-1">
                                <label className="text-xs font-medium text-neutral-600 dark:text-neutral-300">Allowed Values</label>
                                {(() => {
                                  // Suggested values based on dimension type
                                  const suggestions: Record<string, string[]> = {
                                    CURRENCY: ['AED', 'USD', 'EUR', 'GBP', 'SAR'],
                                    REGION: ['NORTH', 'SOUTH', 'EAST', 'WEST', 'MENA', 'APAC'],
                                    CHANNEL: ['INVOICE', 'ECOMMERCE', 'POS', 'DIRECT'],
                                    SEGMENT: ['CORPORATE', 'SME', 'RETAIL', 'ENTERPRISE'],
                                    TIER: ['PLATINUM', 'GOLD', 'SILVER', 'BRONZE'],
                                    ACCOUNT_TYPE: ['PAYABLES', 'RECEIVABLES', 'TAXES', 'PAYROLL'],
                                  };
                                  const suggestedForType = suggestions[level.dimensionType];
                                  if (suggestedForType) {
                                    return (
                                      <button
                                        type="button"
                                        onClick={() => {
                                          const updated = [...hierarchyLevelConfigs];
                                          updated[idx] = { ...updated[idx], allowedValues: [...suggestedForType] };
                                          setHierarchyLevelConfigs(updated);
                                        }}
                                        className="text-xs text-cat-2"
                                      >
                                        + Add suggested
                                      </button>
                                    );
                                  }
                                  return null;
                                })()}
                              </div>

                              {/* Current Values */}
                              <div className="flex flex-wrap gap-1 mb-2 min-h-[28px]">
                                {level.allowedValues && level.allowedValues.length > 0 ? (
                                  level.allowedValues.map((value, vIdx) => (
                                    <span
                                      key={vIdx}
                                      className="inline-flex items-center gap-1 px-2 py-0.5 bg-cat-2/10 text-cat-2 text-xs rounded-full dark:bg-cat-2/15"
                                    >
                                      {value}
                                      <button
                                        type="button"
                                        onClick={() => {
                                          const updated = [...hierarchyLevelConfigs];
                                          updated[idx] = {
                                            ...updated[idx],
                                            allowedValues: (updated[idx].allowedValues || []).filter((_, i) => i !== vIdx),
                                          };
                                          setHierarchyLevelConfigs(updated);
                                        }}
                                        className="hover:text-cat-2"
                                      >
                                        <X className="w-3 h-3" />
                                      </button>
                                    </span>
                                  ))
                                ) : (
                                  <span className="text-xs text-neutral-400 italic dark:text-neutral-500">No restrictions (any value allowed)</span>
                                )}
                              </div>

                              {/* Add New Value */}
                              <div className="flex gap-1">
                                <input
                                  type="text"
                                  placeholder="Add value..."
                                  className="flex-1 px-2 py-1 text-xs border border-neutral-300 rounded dark:border-primary-700"
                                  onKeyDown={e => {
                                    if (e.key === 'Enter') {
                                      e.preventDefault();
                                      const input = e.target as HTMLInputElement;
                                      const value = input.value.trim().toUpperCase();
                                      if (value && !(level.allowedValues || []).includes(value)) {
                                        const updated = [...hierarchyLevelConfigs];
                                        updated[idx] = {
                                          ...updated[idx],
                                          allowedValues: [...(updated[idx].allowedValues || []), value],
                                        };
                                        setHierarchyLevelConfigs(updated);
                                        input.value = '';
                                      }
                                    }
                                  }}
                                />
                                <button
                                  type="button"
                                  onClick={e => {
                                    const input = (e.target as HTMLElement).previousElementSibling as HTMLInputElement;
                                    const value = input.value.trim().toUpperCase();
                                    if (value && !(level.allowedValues || []).includes(value)) {
                                      const updated = [...hierarchyLevelConfigs];
                                      updated[idx] = {
                                        ...updated[idx],
                                        allowedValues: [...(updated[idx].allowedValues || []), value],
                                      };
                                      setHierarchyLevelConfigs(updated);
                                      input.value = '';
                                    }
                                  }}
                                  className="px-2 py-1 text-xs bg-cat-2 text-white rounded hover:bg-cat-2/90"
                                >
                                  <Plus className="w-3 h-3" />
                                </button>
                              </div>
                            </div>

                            {/* Required Toggle */}
                            <label className="flex items-center gap-2 text-xs">
                              <input
                                type="checkbox"
                                checked={level.isRequired ?? false}
                                onChange={e => {
                                  const updated = [...hierarchyLevelConfigs];
                                  updated[idx] = { ...updated[idx], isRequired: e.target.checked };
                                  setHierarchyLevelConfigs(updated);
                                }}
                                className="rounded text-cat-2"
                              />
                              <span className="text-neutral-600 dark:text-neutral-300">This level is required (must have a value)</span>
                            </label>
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>

                {/* Summary */}
                <div className="mt-3 pt-3 border-t border-neutral-200 flex items-center justify-between text-xs text-neutral-500 dark:border-primary-800 dark:text-neutral-400">
                  <span>
                    {hierarchyLevelConfigs.slice(0, formData.hierarchyDepth).filter(l => l.allowedValues && l.allowedValues.length > 0).length} of {formData.hierarchyDepth} levels have restrictions
                  </span>
                  <span className="text-neutral-400 dark:text-neutral-500">
                    Click any level to customize
                  </span>
                </div>
              </div>
            )}

            {/* Info Box */}
            <div className="bg-info-50 border border-info-200 rounded-lg p-3 dark:bg-info-500/10 dark:border-info-500/30">
              <div className="flex items-start gap-2">
                <Info className="w-4 h-4 text-info-600 mt-0.5 dark:text-info-300" />
                <div className="text-sm text-info-800 dark:text-info-300">
                  <p className="font-medium">About Hierarchy</p>
                  <ul className="mt-1 space-y-1 text-info-700 text-xs dark:text-info-300">
                    <li>• Balances aggregate up the hierarchy automatically</li>
                    <li>• Virtual accounts (VAs) are always at the leaf level</li>
                    <li>• You can customize level labels and allowed values after creation</li>
                    <li>• Templates are filtered based on your selected program type</li>
                  </ul>
                </div>
              </div>
            </div>
          </div>
        )}

        {/* ================================================================ */}
        {/* VIBAN Pool Configuration (only if VIBAN enabled) */}
        {/* ================================================================ */}
        {isStepActive('VIBAN Pool') && !isEdit && (
          <div className="space-y-4">
            <h3 className="font-medium text-primary-900 flex items-center gap-2 dark:text-neutral-50">
              <Hash className="w-4 h-4" />
              Step 3: VIBAN Pool Configuration
            </h3>

            <p className="text-sm text-neutral-500 dark:text-neutral-400">Configure how VIBANs will be generated for this program.</p>

            {/* Generation Strategy */}
            <div>
              <label className="field-label block mb-2">Generation Strategy *</label>
              <div className="grid grid-cols-3 gap-3">
                {Object.entries(vibanStrategyConfig).map(([key, config]) => {
                  const StrategyIcon = config.icon;
                  const isSelected = formData.vibanGenerationStrategy === key;
                  return (
                    <button
                      key={key}
                      type="button"
                      onClick={() => setFormData({ ...formData, vibanGenerationStrategy: key as VibanGenerationStrategy })}
                      className={cn(
                        'p-4 border rounded-lg text-left transition-all',
                        isSelected ? 'border-primary-500 bg-primary-50 ring-2 ring-primary-500 dark:bg-primary-800/40' : 'border-neutral-200 hover:border-neutral-300 dark:border-primary-800 dark:hover:border-primary-700'
                      )}
                    >
                      <div className="flex items-center gap-2 mb-2">
                        <StrategyIcon className={cn('w-5 h-5', isSelected ? 'text-primary-600 dark:text-primary-200' : 'text-neutral-500 dark:text-neutral-400')} />
                        <span className="font-medium text-sm">{config.label}</span>
                      </div>
                      <p className="text-xs text-neutral-500 dark:text-neutral-400">{config.description}</p>
                    </button>
                  );
                })}
              </div>
            </div>

            {/* VIBAN Pool Selection */}
            <div>
              <label className="field-label block mb-1">VIBAN Pool</label>
              <select
                className="w-full px-3 py-2 border border-neutral-300 rounded-lg dark:border-primary-700"
                value={formData.defaultVibanPoolId}
                onChange={e => setFormData({ ...formData, defaultVibanPoolId: e.target.value })}
                disabled={loadingPools}
              >
                <option value="">{loadingPools ? 'Loading pools...' : 'Select VIBAN Pool (optional)...'}</option>
                {vibanPools.map(pool => (
                  <option key={pool.id} value={pool.id}>
                    {pool.poolName} ({pool.availableVibans} available)
                  </option>
                ))}
              </select>
              {selectedPool && (
                <div className="mt-2 p-3 bg-neutral-50 rounded-lg dark:bg-primary-950">
                  <div className="grid grid-cols-3 gap-4 text-center">
                    <div>
                      <p className="section-title">{selectedPool.totalVibans}</p>
                      <p className="text-xs text-neutral-500 dark:text-neutral-400">Total</p>
                    </div>
                    <div>
                      <p className="text-lg font-semibold text-success-600 dark:text-success-300">{selectedPool.availableVibans}</p>
                      <p className="text-xs text-neutral-500 dark:text-neutral-400">Available</p>
                    </div>
                    <div>
                      <p className="text-lg font-semibold text-warning-600 dark:text-warning-300">{selectedPool.usedVibans}</p>
                      <p className="text-xs text-neutral-500 dark:text-neutral-400">Used</p>
                    </div>
                  </div>
                </div>
              )}
            </div>

            {/* VIBAN Prefix and Bank Code */}
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="field-label block mb-1">VIBAN Prefix</label>
                <input
                  type="text"
                  className="w-full px-3 py-2 border border-neutral-300 rounded-lg font-mono dark:border-primary-700"
                  placeholder="e.g., AE"
                  maxLength={4}
                  value={formData.vibanPrefix}
                  onChange={e => setFormData({ ...formData, vibanPrefix: e.target.value.toUpperCase() })}
                />
              </div>
              <div>
                <label className="field-label block mb-1">Bank Code</label>
                <input
                  type="text"
                  className="w-full px-3 py-2 border border-neutral-300 rounded-lg font-mono dark:border-primary-700"
                  placeholder="e.g., 033"
                  maxLength={4}
                  value={formData.vibanBankCode}
                  onChange={e => setFormData({ ...formData, vibanBankCode: e.target.value })}
                />
              </div>
            </div>

            {/* Sample VIBAN Preview */}
            <div className="bg-neutral-100 rounded-lg p-4 dark:bg-primary-800">
              <p className="text-xs text-neutral-500 mb-2 text-center dark:text-neutral-400">Sample VIBAN Format</p>
              <p className="font-mono text-xl text-center text-primary-900 tracking-wider dark:text-neutral-50">
                {formData.vibanPrefix || 'AE'}{formData.vibanBankCode || '00'}-
                {formData.vibanGenerationStrategy === 'SEQUENTIAL' && '0000-0001'}
                {formData.vibanGenerationStrategy === 'RANDOM' && 'X7K2-9M4P'}
                {formData.vibanGenerationStrategy === 'HIERARCHY_ENCODED' && 'L1L2-0001'}
              </p>
            </div>
          </div>
        )}
        {/* ================================================================ */}
        {/* WALLET LIMITS STEP (only if wallet enabled) - Transaction & Balance Limits */}
        {/* ================================================================ */}
        {isStepActive('Wallet Limits') && !isEdit && (
          <div className="space-y-4">
            <h3 className="font-medium text-primary-900 flex items-center gap-2 dark:text-neutral-50">
              <Settings className="w-4 h-4" />
              Wallet Limits Configuration
            </h3>

            <p className="text-sm text-neutral-500 dark:text-neutral-400">Configure transaction limits, balance caps, and wallet feature settings for this program.</p>

            {/* Transaction Limits */}
            <div className="space-y-3">
              <h4 className="text-sm font-medium text-neutral-700 border-b pb-2 dark:text-neutral-200">Transaction Limits</h4>
              <div className="grid grid-cols-3 gap-4">
                <div>
                  <label className="block text-xs font-medium text-neutral-600 mb-1 dark:text-neutral-300">Per Transaction Limit</label>
                  <div className="relative">
                    <span className="absolute left-3 top-1/2 -translate-y-1/2 text-neutral-400 text-sm dark:text-neutral-500">{formData.currencyCode}</span>
                    <input
                      type="number"
                      className="w-full pl-12 pr-3 py-2 border border-neutral-300 rounded-lg dark:border-primary-700"
                      placeholder="50,000"
                      value={formData.defaultPerTransactionLimit ?? ''}
                      onChange={e => setFormData({ ...formData, defaultPerTransactionLimit: e.target.value ? parseFloat(e.target.value) : undefined })}
                    />
                  </div>
                </div>
                <div>
                  <label className="block text-xs font-medium text-neutral-600 mb-1 dark:text-neutral-300">Daily Limit</label>
                  <div className="relative">
                    <span className="absolute left-3 top-1/2 -translate-y-1/2 text-neutral-400 text-sm dark:text-neutral-500">{formData.currencyCode}</span>
                    <input
                      type="number"
                      className="w-full pl-12 pr-3 py-2 border border-neutral-300 rounded-lg dark:border-primary-700"
                      placeholder="200,000"
                      value={formData.defaultDailyLimit ?? ''}
                      onChange={e => setFormData({ ...formData, defaultDailyLimit: e.target.value ? parseFloat(e.target.value) : undefined })}
                    />
                  </div>
                </div>
                <div>
                  <label className="block text-xs font-medium text-neutral-600 mb-1 dark:text-neutral-300">Monthly Limit</label>
                  <div className="relative">
                    <span className="absolute left-3 top-1/2 -translate-y-1/2 text-neutral-400 text-sm dark:text-neutral-500">{formData.currencyCode}</span>
                    <input
                      type="number"
                      className="w-full pl-12 pr-3 py-2 border border-neutral-300 rounded-lg dark:border-primary-700"
                      placeholder="1,000,000"
                      value={formData.defaultMonthlyLimit ?? ''}
                      onChange={e => setFormData({ ...formData, defaultMonthlyLimit: e.target.value ? parseFloat(e.target.value) : undefined })}
                    />
                  </div>
                </div>
              </div>
            </div>

            {/* Balance Limits */}
            <div className="space-y-3">
              <h4 className="text-sm font-medium text-neutral-700 border-b pb-2 dark:text-neutral-200">Balance Limits</h4>
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-xs font-medium text-neutral-600 mb-1 dark:text-neutral-300">Maximum Balance</label>
                  <div className="relative">
                    <span className="absolute left-3 top-1/2 -translate-y-1/2 text-neutral-400 text-sm dark:text-neutral-500">{formData.currencyCode}</span>
                    <input
                      type="number"
                      className="w-full pl-12 pr-3 py-2 border border-neutral-300 rounded-lg dark:border-primary-700"
                      placeholder="500,000"
                      value={formData.defaultMaxBalance ?? ''}
                      onChange={e => setFormData({ ...formData, defaultMaxBalance: e.target.value ? parseFloat(e.target.value) : undefined })}
                    />
                  </div>
                  <p className="text-xs text-neutral-400 mt-1 dark:text-neutral-500">Cap on total wallet balance</p>
                </div>
                <div>
                  <label className="block text-xs font-medium text-neutral-600 mb-1 dark:text-neutral-300">Minimum Balance</label>
                  <div className="relative">
                    <span className="absolute left-3 top-1/2 -translate-y-1/2 text-neutral-400 text-sm dark:text-neutral-500">{formData.currencyCode}</span>
                    <input
                      type="number"
                      className="w-full pl-12 pr-3 py-2 border border-neutral-300 rounded-lg dark:border-primary-700"
                      placeholder="0"
                      value={formData.minBalanceThreshold ?? ''}
                      onChange={e => setFormData({ ...formData, minBalanceThreshold: e.target.value ? parseFloat(e.target.value) : undefined })}
                    />
                  </div>
                  <p className="text-xs text-neutral-400 mt-1 dark:text-neutral-500">Required minimum balance to maintain</p>
                </div>
              </div>
            </div>

            {/* KYC Configuration */}
            <div className="space-y-3">
              <h4 className="text-sm font-medium text-neutral-700 border-b pb-2 dark:text-neutral-200">KYC Configuration</h4>
              <div className="grid grid-cols-2 gap-4">
                <label className="flex items-center gap-3 p-3 border border-neutral-200 rounded-lg cursor-pointer hover:bg-neutral-50 dark:border-primary-800 dark:hover:bg-primary-800/50">
                  <input
                    type="checkbox"
                    checked={formData.kycRequired ?? false}
                    onChange={e => setFormData({ ...formData, kycRequired: e.target.checked })}
                  />
                  <div>
                    <span className="text-sm font-medium text-primary-900 dark:text-neutral-50">KYC Required</span>
                    <p className="text-xs text-neutral-500 dark:text-neutral-400">Require KYC verification for wallet holders</p>
                  </div>
                </label>
                <div>
                  <label className="block text-xs font-medium text-neutral-600 mb-1 dark:text-neutral-300">Minimum KYC Level</label>
                  <select
                    className="w-full px-3 py-2 border border-neutral-300 rounded-lg dark:border-primary-700"
                    value={formData.minKycLevel ?? 1}
                    onChange={e => setFormData({ ...formData, minKycLevel: parseInt(e.target.value) })}
                    disabled={!formData.kycRequired}
                  >
                    <option value={1}>Level 1 - Basic (Name, Phone)</option>
                    <option value={2}>Level 2 - Standard (+ ID Document)</option>
                    <option value={3}>Level 3 - Enhanced (+ Address Proof)</option>
                    <option value={4}>Level 4 - Full (+ Income Proof)</option>
                  </select>
                </div>
              </div>
            </div>

            {/* Wallet Features/Capabilities */}
            <div className="space-y-3">
              <h4 className="text-sm font-medium text-neutral-700 border-b pb-2 dark:text-neutral-200">Wallet Capabilities</h4>
              <div className="grid grid-cols-2 gap-3">
                {[
                  { key: 'allowTopup', label: 'Allow Topup', desc: 'Add funds to wallet', defaultVal: true },
                  { key: 'allowWithdrawal', label: 'Allow Withdrawal', desc: 'Withdraw to bank account', defaultVal: true },
                  { key: 'allowTransfer', label: 'Allow Transfer', desc: 'Transfer between wallets', defaultVal: true },
                  { key: 'allowPayment', label: 'Allow Payment', desc: 'Pay merchants/bills', defaultVal: true },
                ].map(cap => (
                  <label key={cap.key} className="flex items-center gap-3 p-3 border border-neutral-200 rounded-lg cursor-pointer hover:bg-neutral-50 dark:border-primary-800 dark:hover:bg-primary-800/50">
                    <input
                      type="checkbox"
                      checked={(formData as any)[cap.key] ?? cap.defaultVal}
                      onChange={e => setFormData({ ...formData, [cap.key]: e.target.checked })}
                    />
                    <div>
                      <span className="text-sm font-medium text-primary-900 dark:text-neutral-50">{cap.label}</span>
                      <p className="text-xs text-neutral-500 dark:text-neutral-400">{cap.desc}</p>
                    </div>
                  </label>
                ))}
              </div>
            </div>

            {/* Info Box */}
            <div className="bg-info-50 border border-info-200 rounded-lg p-3 dark:bg-info-500/10 dark:border-info-500/30">
              <div className="flex items-start gap-2">
                <Info className="w-4 h-4 text-info-600 mt-0.5 dark:text-info-300" />
                <div className="text-sm text-info-800 dark:text-info-300">
                  <p className="font-medium">About Wallet Limits</p>
                  <ul className="mt-1 space-y-1 text-info-700 text-xs dark:text-info-300">
                    <li>• Limits can be overridden at the individual wallet level</li>
                    <li>• KYC levels determine maximum allowed limits</li>
                    <li>• Leave blank to use system defaults</li>
                  </ul>
                </div>
              </div>
            </div>
          </div>
        )}

        {/* ================================================================ */}
        {/* WALLET FEES STEP (only if wallet enabled) - Using ChargeConfiguration */}
        {/* ================================================================ */}
        {isStepActive('Wallet Fees') && !isEdit && (
          <div className="space-y-4">
            <h3 className="font-medium text-primary-900 flex items-center gap-2 dark:text-neutral-50">
              <Percent className="w-4 h-4" />
              Wallet Fees Configuration
              <Badge variant="info" size="sm">ChargeConfiguration</Badge>
            </h3>

            <p className="text-sm text-neutral-500 dark:text-neutral-400">Configure fee overrides for this program. Leave blank to use base rates.</p>

            {loadingCharges ? (
              <div className="flex items-center justify-center py-8">
                <Loader2 className="w-6 h-6 animate-spin text-primary-500" />
              </div>
            ) : walletCharges ? (
              <div className="space-y-2">
                <ChargeConfigRow charge={walletCharges.topup} currencyCode={formData.currencyCode}
                  overridePercent={chargeOverrides.topup.percent} overrideFlat={chargeOverrides.topup.flat} isWaived={chargeOverrides.topup.waived}
                  onPercentChange={v => setChargeOverrides({ ...chargeOverrides, topup: { ...chargeOverrides.topup, percent: v } })}
                  onFlatChange={v => setChargeOverrides({ ...chargeOverrides, topup: { ...chargeOverrides.topup, flat: v } })}
                  onWaiverChange={w => setChargeOverrides({ ...chargeOverrides, topup: { ...chargeOverrides.topup, waived: w } })} />
                <ChargeConfigRow charge={walletCharges.withdrawal} currencyCode={formData.currencyCode}
                  overridePercent={chargeOverrides.withdrawal.percent} overrideFlat={chargeOverrides.withdrawal.flat} isWaived={chargeOverrides.withdrawal.waived}
                  onPercentChange={v => setChargeOverrides({ ...chargeOverrides, withdrawal: { ...chargeOverrides.withdrawal, percent: v } })}
                  onFlatChange={v => setChargeOverrides({ ...chargeOverrides, withdrawal: { ...chargeOverrides.withdrawal, flat: v } })}
                  onWaiverChange={w => setChargeOverrides({ ...chargeOverrides, withdrawal: { ...chargeOverrides.withdrawal, waived: w } })} />
                <ChargeConfigRow charge={walletCharges.transfer} currencyCode={formData.currencyCode}
                  overridePercent={chargeOverrides.transfer.percent} overrideFlat={chargeOverrides.transfer.flat} isWaived={chargeOverrides.transfer.waived}
                  onPercentChange={v => setChargeOverrides({ ...chargeOverrides, transfer: { ...chargeOverrides.transfer, percent: v } })}
                  onFlatChange={v => setChargeOverrides({ ...chargeOverrides, transfer: { ...chargeOverrides.transfer, flat: v } })}
                  onWaiverChange={w => setChargeOverrides({ ...chargeOverrides, transfer: { ...chargeOverrides.transfer, waived: w } })} />
              </div>
            ) : null}

            {/* Fixed Fees */}
            {walletCharges && (
              <div className="grid grid-cols-3 gap-4 pt-4 border-t">
                <div className="p-3 bg-neutral-50 rounded-lg dark:bg-primary-950">
                  <div className="flex items-center justify-between mb-2">
                    <span className="text-sm font-medium">{walletCharges.issuance.chargeName}</span>
                    <label className="flex items-center gap-1 text-xs">
                      <input type="checkbox" checked={chargeOverrides.issuance.waived}
                        onChange={e => setChargeOverrides({ ...chargeOverrides, issuance: { ...chargeOverrides.issuance, waived: e.target.checked } })} />
                      Waive
                    </label>
                  </div>
                  <div className="flex items-center gap-2">
                    <span className="text-xs text-neutral-400 dark:text-neutral-500">Base: {formData.currencyCode} {walletCharges.issuance.fixed}</span>
                    <ChevronRight className="w-3 h-3 text-neutral-300 dark:text-neutral-600" />
                    <input type="number" className={cn('w-20 px-2 py-1 text-sm border rounded', chargeOverrides.issuance.waived && 'bg-neutral-100 dark:bg-primary-800')}
                      placeholder={String(walletCharges.issuance.fixed)} value={chargeOverrides.issuance.flat ?? ''}
                      onChange={e => setChargeOverrides({ ...chargeOverrides, issuance: { ...chargeOverrides.issuance, flat: e.target.value ? parseFloat(e.target.value) : undefined } })}
                      disabled={chargeOverrides.issuance.waived} />
                  </div>
                </div>
                <div className="p-3 bg-neutral-50 rounded-lg dark:bg-primary-950">
                  <div className="flex items-center justify-between mb-2">
                    <span className="text-sm font-medium">{walletCharges.monthly.chargeName}</span>
                    <label className="flex items-center gap-1 text-xs">
                      <input type="checkbox" checked={chargeOverrides.monthly.waived}
                        onChange={e => setChargeOverrides({ ...chargeOverrides, monthly: { ...chargeOverrides.monthly, waived: e.target.checked } })} />
                      Waive
                    </label>
                  </div>
                  <div className="flex items-center gap-2">
                    <span className="text-xs text-neutral-400 dark:text-neutral-500">Base: {formData.currencyCode} {walletCharges.monthly.fixed}</span>
                    <ChevronRight className="w-3 h-3 text-neutral-300 dark:text-neutral-600" />
                    <input type="number" className={cn('w-20 px-2 py-1 text-sm border rounded', chargeOverrides.monthly.waived && 'bg-neutral-100 dark:bg-primary-800')}
                      placeholder={String(walletCharges.monthly.fixed)} value={chargeOverrides.monthly.flat ?? ''}
                      onChange={e => setChargeOverrides({ ...chargeOverrides, monthly: { ...chargeOverrides.monthly, flat: e.target.value ? parseFloat(e.target.value) : undefined } })}
                      disabled={chargeOverrides.monthly.waived} />
                  </div>
                </div>
                <div className="p-3 bg-neutral-50 rounded-lg dark:bg-primary-950">
                  <div className="flex items-center justify-between mb-2">
                    <span className="text-sm font-medium">{walletCharges.inactivity.chargeName}</span>
                    <label className="flex items-center gap-1 text-xs">
                      <input type="checkbox" checked={chargeOverrides.inactivity.waived}
                        onChange={e => setChargeOverrides({ ...chargeOverrides, inactivity: { ...chargeOverrides.inactivity, waived: e.target.checked } })} />
                      Waive
                    </label>
                  </div>
                  <div className="flex items-center gap-2">
                    <span className="text-xs text-neutral-400 dark:text-neutral-500">Base: {formData.currencyCode} {walletCharges.inactivity.fixed}</span>
                    <ChevronRight className="w-3 h-3 text-neutral-300 dark:text-neutral-600" />
                    <input type="number" className={cn('w-20 px-2 py-1 text-sm border rounded', chargeOverrides.inactivity.waived && 'bg-neutral-100 dark:bg-primary-800')}
                      placeholder={String(walletCharges.inactivity.fixed)} value={chargeOverrides.inactivity.flat ?? ''}
                      onChange={e => setChargeOverrides({ ...chargeOverrides, inactivity: { ...chargeOverrides.inactivity, flat: e.target.value ? parseFloat(e.target.value) : undefined } })}
                      disabled={chargeOverrides.inactivity.waived} />
                  </div>
                </div>
              </div>
            )}

            <div className="bg-info-50 border border-info-200 rounded-lg p-3 flex items-start gap-2 dark:bg-info-500/10 dark:border-info-500/30">
              <Info className="w-4 h-4 text-info-600 mt-0.5 dark:text-info-300" />
              <p className="text-sm text-info-700 dark:text-info-300">
                Fees are managed via ChargeConfiguration. Override values apply to this program only. Leave blank to use base rates.
              </p>
            </div>
          </div>
        )}

        {/* ================================================================ */}
        {/* SETTLEMENT STEP: Settlement & Configuration */}
        {/* ================================================================ */}
        {isStepActive('Settlement') && !isEdit && (
          <div className="space-y-4">
            <h3 className="font-medium text-primary-900 flex items-center gap-2 dark:text-neutral-50">
              <Clock className="w-4 h-4" />
              Settlement & Configuration
            </h3>

            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="field-label block mb-1">VA Prefix</label>
                <input 
                  type="text" 
                  className="w-full px-3 py-2 border border-neutral-300 rounded-lg font-mono dark:border-primary-700" 
                  placeholder="e.g., VA"
                  value={formData.vaPrefix} 
                  onChange={e => setFormData({ ...formData, vaPrefix: e.target.value.toUpperCase() })} 
                />
              </div>
              <div>
                <label className="field-label block mb-1">VA Format</label>
                <input 
                  type="text" 
                  className="w-full px-3 py-2 border border-neutral-300 rounded-lg font-mono dark:border-primary-700" 
                  placeholder="{PREFIX}{SEQ:8}"
                  value={formData.vaFormat} 
                  onChange={e => setFormData({ ...formData, vaFormat: e.target.value })} 
                />
              </div>
            </div>

            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="field-label block mb-1">Settlement Frequency</label>
                <select 
                  className="w-full px-3 py-2 border border-neutral-300 rounded-lg dark:border-primary-700" 
                  value={formData.settlementFrequency}
                  onChange={e => setFormData({ ...formData, settlementFrequency: e.target.value })}
                >
                  {Object.entries(settlementFrequencyConfig).map(([k, v]) => (
                    <option key={k} value={k}>{v.label} - {v.description}</option>
                  ))}
                </select>
              </div>
              <div>
                <label className="field-label block mb-1">Settlement Time</label>
                <input 
                  type="time" 
                  className="w-full px-3 py-2 border border-neutral-300 rounded-lg dark:border-primary-700"
                  value={formData.settlementTime} 
                  onChange={e => setFormData({ ...formData, settlementTime: e.target.value })} 
                />
              </div>
            </div>

            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="field-label block mb-1">Min Balance Threshold</label>
                <input 
                  type="number" 
                  className="w-full px-3 py-2 border border-neutral-300 rounded-lg dark:border-primary-700" 
                  placeholder="0.00"
                  value={formData.minBalanceThreshold || ''} 
                  onChange={e => setFormData({ ...formData, minBalanceThreshold: e.target.value ? parseFloat(e.target.value) : undefined })} 
                />
              </div>
              <div>
                <label className="field-label block mb-1">Balance Aggregation Interval (min)</label>
                <input 
                  type="number" 
                  className="w-full px-3 py-2 border border-neutral-300 rounded-lg dark:border-primary-700" 
                  placeholder="5"
                  value={formData.balanceAggregationIntervalMinutes} 
                  onChange={e => setFormData({ ...formData, balanceAggregationIntervalMinutes: parseInt(e.target.value) || 5 })} 
                />
              </div>
            </div>

            <label className="flex items-center gap-3 p-3 border border-neutral-200 rounded-lg cursor-pointer hover:bg-neutral-50 dark:border-primary-800 dark:hover:bg-primary-800/50">
              <input 
                type="checkbox" 
                checked={formData.realtimeBalancePropagation}
                onChange={e => setFormData({ ...formData, realtimeBalancePropagation: e.target.checked })} 
              />
              <div>
                <span className="text-sm font-medium text-primary-900 flex items-center gap-2 dark:text-neutral-50">
                  <Zap className="w-4 h-4 text-success-500" />
                  Real-time Balance Propagation
                </span>
                <p className="text-xs text-neutral-500 dark:text-neutral-400">Instantly update parent balances when child accounts change</p>
              </div>
            </label>
          </div>
        )}

        {/* ================================================================ */}
        {/* STEP 5 (or 4 if no VIBAN): Review */}
        {/* ================================================================ */}
        {!isEdit && step === totalSteps && (
          <div className="space-y-4">
            <h3 className="font-medium text-primary-900 flex items-center gap-2 dark:text-neutral-50">
              <CheckCircle className="w-4 h-4" />
              Review & Confirm
            </h3>

            <div className="bg-neutral-50 rounded-lg p-4 space-y-4 dark:bg-primary-950">
              {/* Basic Info */}
              <div>
                <h4 className="text-xs font-semibold text-neutral-500 uppercase tracking-wider mb-2 dark:text-neutral-400">Basic Information</h4>
                <div className="grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
                  <div><span className="text-neutral-500 dark:text-neutral-400">Code:</span> <span className="font-mono font-medium">{formData.programCode}</span></div>
                  <div><span className="text-neutral-500 dark:text-neutral-400">Name:</span> <span className="font-medium">{formData.programName}</span></div>
                  <div><span className="text-neutral-500 dark:text-neutral-400">Type:</span> <Badge variant="neutral">{programTypeConfig[formData.programType]?.label}</Badge></div>
                  <div><span className="text-neutral-500 dark:text-neutral-400">Currency:</span> <span className="font-medium">{formData.currencyCode}</span></div>
                </div>
              </div>

              {/* Features */}
              <div className="border-t pt-4">
                <h4 className="text-xs font-semibold text-neutral-500 uppercase tracking-wider mb-2 dark:text-neutral-400">Features</h4>
                <div className="flex flex-wrap gap-2">
                  {formData.vibanEnabled && <Badge variant="info">VIBAN</Badge>}
                  {formData.walletEnabled && <Badge variant="warning">Wallet</Badge>}
                  {formData.escrowEnabled && <Badge variant="success">Escrow</Badge>}
                  {formData.ihbEnabled && <Badge variant="info">IHB</Badge>}
                  {formData.hierarchyEnabled && <Badge variant="neutral">Hierarchy</Badge>}
                  {formData.loyaltyEnabled && <Badge variant="info">Loyalty</Badge>}
                  {formData.giftCardEnabled && <Badge variant="info">Gift Card</Badge>}
                  {formData.corporateCardEnabled && <Badge variant="info">Corp Card</Badge>}
                  {formData.mobileMoneyEnabled && <Badge variant="info">Mobile Money</Badge>}
                  {formData.autoReconciliation && <Badge variant="neutral">Auto-Recon</Badge>}
                  {formData.realtimeBalancePropagation && <Badge variant="success">Real-time</Badge>}
                  {!formData.vibanEnabled && !formData.walletEnabled && !formData.escrowEnabled && !formData.ihbEnabled && (
                    <span className="text-sm text-neutral-400 dark:text-neutral-500">Standard features only</span>
                  )}
                </div>
              </div>

              {/* Hierarchy Config */}
              {formData.hierarchyEnabled && (
                <div className="border-t pt-4">
                  <h4 className="text-xs font-semibold text-neutral-500 uppercase tracking-wider mb-2 dark:text-neutral-400">Hierarchy Configuration</h4>
                  <div className="grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
                    <div><span className="text-neutral-500 dark:text-neutral-400">Template:</span> <span className="font-medium">{formData.defaultHierarchyTemplate || 'Custom'}</span></div>
                    <div><span className="text-neutral-500 dark:text-neutral-400">Depth:</span> <span className="font-medium">{formData.hierarchyDepth} levels</span></div>
                  </div>
                </div>
              )}

              {/* Wallet Limits */}
              {needsWalletConfig && (
                <div className="border-t pt-4">
                  <h4 className="text-xs font-semibold text-neutral-500 uppercase tracking-wider mb-2 dark:text-neutral-400">Wallet Limits</h4>
                  <div className="grid grid-cols-3 gap-2 text-xs">
                    {formData.defaultPerTransactionLimit && (
                      <div className="p-2 bg-neutral-100 rounded dark:bg-primary-800">
                        <span className="text-neutral-500 dark:text-neutral-400">Per Txn:</span>
                        <span className="font-medium ml-1">{formatCurrency(formData.defaultPerTransactionLimit, formData.currencyCode)}</span>
                      </div>
                    )}
                    {formData.defaultDailyLimit && (
                      <div className="p-2 bg-neutral-100 rounded dark:bg-primary-800">
                        <span className="text-neutral-500 dark:text-neutral-400">Daily:</span>
                        <span className="font-medium ml-1">{formatCurrency(formData.defaultDailyLimit, formData.currencyCode)}</span>
                      </div>
                    )}
                    {formData.defaultMonthlyLimit && (
                      <div className="p-2 bg-neutral-100 rounded dark:bg-primary-800">
                        <span className="text-neutral-500 dark:text-neutral-400">Monthly:</span>
                        <span className="font-medium ml-1">{formatCurrency(formData.defaultMonthlyLimit, formData.currencyCode)}</span>
                      </div>
                    )}
                    {formData.defaultMaxBalance && (
                      <div className="p-2 bg-neutral-100 rounded dark:bg-primary-800">
                        <span className="text-neutral-500 dark:text-neutral-400">Max Balance:</span>
                        <span className="font-medium ml-1">{formatCurrency(formData.defaultMaxBalance, formData.currencyCode)}</span>
                      </div>
                    )}
                  </div>
                  <div className="flex flex-wrap gap-2 mt-2">
                    {formData.kycRequired && <Badge variant="warning" size="sm">KYC Level {formData.minKycLevel || 1}</Badge>}
                    {formData.allowTopup && <Badge variant="success" size="sm">Topup</Badge>}
                    {formData.allowWithdrawal && <Badge variant="success" size="sm">Withdrawal</Badge>}
                    {formData.allowTransfer && <Badge variant="success" size="sm">Transfer</Badge>}
                    {formData.allowPayment && <Badge variant="success" size="sm">Payment</Badge>}
                  </div>
                </div>
              )}

              {/* VIBAN Config */}
              {formData.vibanEnabled && (
                <div className="border-t pt-4">
                  <h4 className="text-xs font-semibold text-neutral-500 uppercase tracking-wider mb-2 dark:text-neutral-400">VIBAN Configuration</h4>
                  <div className="grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
                    <div><span className="text-neutral-500 dark:text-neutral-400">Strategy:</span> <span className="font-medium">{vibanStrategyConfig[formData.vibanGenerationStrategy]?.label}</span></div>
                    <div><span className="text-neutral-500 dark:text-neutral-400">Prefix:</span> <span className="font-mono">{formData.vibanPrefix || 'Default'}</span></div>
                    {selectedPool && <div className="col-span-2"><span className="text-neutral-500 dark:text-neutral-400">Pool:</span> <span className="font-medium">{selectedPool.poolName}</span></div>}
                  </div>
                </div>
              )}

              {/* Wallet Fees */}
              {needsWalletConfig && walletCharges && (
                <div className="border-t pt-4">
                  <h4 className="text-xs font-semibold text-neutral-500 uppercase tracking-wider mb-2 flex items-center gap-2 dark:text-neutral-400">
                    Wallet Fees <Badge variant="info" size="sm">ChargeConfiguration</Badge>
                  </h4>
                  {/* Transaction Fees */}
                  <div className="grid grid-cols-3 gap-2 text-xs mb-2">
                    <div className="p-2 bg-neutral-100 rounded dark:bg-primary-800">
                      <span className="text-neutral-500 dark:text-neutral-400">Topup:</span>
                      <span className="font-medium ml-1">
                        {chargeOverrides.topup.waived ? 'Waived' : 
                          `${chargeOverrides.topup.percent ?? walletCharges.topup.percentage}% + ${formData.currencyCode} ${chargeOverrides.topup.flat ?? walletCharges.topup.fixed}`}
                      </span>
                      {(chargeOverrides.topup.percent !== undefined || chargeOverrides.topup.flat !== undefined) && !chargeOverrides.topup.waived && <Badge variant="info" size="sm" className="ml-1">Override</Badge>}
                    </div>
                    <div className="p-2 bg-neutral-100 rounded dark:bg-primary-800">
                      <span className="text-neutral-500 dark:text-neutral-400">Withdrawal:</span>
                      <span className="font-medium ml-1">
                        {chargeOverrides.withdrawal.waived ? 'Waived' : 
                          `${chargeOverrides.withdrawal.percent ?? walletCharges.withdrawal.percentage}% + ${formData.currencyCode} ${chargeOverrides.withdrawal.flat ?? walletCharges.withdrawal.fixed}`}
                      </span>
                      {(chargeOverrides.withdrawal.percent !== undefined || chargeOverrides.withdrawal.flat !== undefined) && !chargeOverrides.withdrawal.waived && <Badge variant="info" size="sm" className="ml-1">Override</Badge>}
                    </div>
                    <div className="p-2 bg-neutral-100 rounded dark:bg-primary-800">
                      <span className="text-neutral-500 dark:text-neutral-400">Transfer:</span>
                      <span className="font-medium ml-1">
                        {chargeOverrides.transfer.waived ? 'Waived' : 
                          `${chargeOverrides.transfer.percent ?? walletCharges.transfer.percentage}% + ${formData.currencyCode} ${chargeOverrides.transfer.flat ?? walletCharges.transfer.fixed}`}
                      </span>
                      {(chargeOverrides.transfer.percent !== undefined || chargeOverrides.transfer.flat !== undefined) && !chargeOverrides.transfer.waived && <Badge variant="info" size="sm" className="ml-1">Override</Badge>}
                    </div>
                  </div>
                  {/* Fixed Fees */}
                  <div className="grid grid-cols-3 gap-2 text-xs">
                    <div className="p-2 bg-neutral-100 rounded dark:bg-primary-800">
                      <span className="text-neutral-500 dark:text-neutral-400">Issuance:</span>
                      <span className="font-medium ml-1">
                        {chargeOverrides.issuance.waived ? 'Waived' : `${formData.currencyCode} ${chargeOverrides.issuance.flat ?? walletCharges.issuance.fixed}`}
                      </span>
                      {chargeOverrides.issuance.flat !== undefined && !chargeOverrides.issuance.waived && <Badge variant="info" size="sm" className="ml-1">Override</Badge>}
                    </div>
                    <div className="p-2 bg-neutral-100 rounded dark:bg-primary-800">
                      <span className="text-neutral-500 dark:text-neutral-400">Monthly:</span>
                      <span className="font-medium ml-1">
                        {chargeOverrides.monthly.waived ? 'Waived' : `${formData.currencyCode} ${chargeOverrides.monthly.flat ?? walletCharges.monthly.fixed}`}
                      </span>
                      {chargeOverrides.monthly.flat !== undefined && !chargeOverrides.monthly.waived && <Badge variant="info" size="sm" className="ml-1">Override</Badge>}
                    </div>
                    <div className="p-2 bg-neutral-100 rounded dark:bg-primary-800">
                      <span className="text-neutral-500 dark:text-neutral-400">Inactivity:</span>
                      <span className="font-medium ml-1">
                        {chargeOverrides.inactivity.waived ? 'Waived' : `${formData.currencyCode} ${chargeOverrides.inactivity.flat ?? walletCharges.inactivity.fixed}`}
                      </span>
                      {chargeOverrides.inactivity.flat !== undefined && !chargeOverrides.inactivity.waived && <Badge variant="info" size="sm" className="ml-1">Override</Badge>}
                    </div>
                  </div>
                </div>
              )}

              {/* Settlement */}
              <div className="border-t pt-4">
                <h4 className="text-xs font-semibold text-neutral-500 uppercase tracking-wider mb-2 dark:text-neutral-400">Settlement</h4>
                <div className="grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
                  <div><span className="text-neutral-500 dark:text-neutral-400">Frequency:</span> <span className="font-medium">{settlementFrequencyConfig[formData.settlementFrequency]?.label}</span></div>
                  <div><span className="text-neutral-500 dark:text-neutral-400">Time:</span> <span className="font-medium">{formData.settlementTime || 'Anytime'}</span></div>
                </div>
              </div>
            </div>

            <div className="bg-success-50 border border-success-200 rounded-lg p-3 flex items-start gap-2 dark:bg-success-500/10 dark:border-success-500/30">
              <CheckCircle className="w-4 h-4 text-success-600 mt-0.5 dark:text-success-300" />
              <p className="text-sm text-success-700 dark:text-success-300">
                Ready to create program. Click "Create Program" to proceed.
              </p>
            </div>
          </div>
        )}

        {/* ================================================================ */}
        {/* Navigation Buttons */}
        {/* ================================================================ */}
        <div className="flex justify-between pt-4 border-t">
          {step > 1 ? (
            <Button variant="ghost" onClick={() => setStep(step - 1)}>
              Back
            </Button>
          ) : (
            <div />
          )}
          <div className="flex gap-2">
            <Button variant="ghost" onClick={onClose}>Cancel</Button>
            {step < totalSteps ? (
              <Button
                onClick={() => setStep(step + 1)}
                disabled={!canProceed()}
              >
                Next
              </Button>
            ) : (
              <Button
                onClick={handleSubmit}
                disabled={loading || !canProceed()}
                loading={loading}
              >
                {isEdit ? 'Save Changes' : 'Create Program'}
              </Button>
            )}
          </div>
        </div>
      </div>
    </Modal>
  );
};

// ============================================================================
// MAIN PAGE
// ============================================================================

// ============================================================================
// CORPORATE SELECTOR COMPONENT
// ============================================================================

const CorporateSelector: React.FC<{
  value?: string;
  onChange: (corporateId: string | undefined) => void;
  corporates: Corporate[];
  loading?: boolean;
}> = ({ value, onChange, corporates, loading }) => (
  <select
    value={value || ''}
    onChange={(e) => onChange(e.target.value || undefined)}
    disabled={loading}
    className="px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent min-w-[200px] dark:border-primary-700"
  >
    {loading ? (
      <option value="">Loading corporates...</option>
    ) : (
      <>
        <option value="">All Corporates</option>
        {corporates.map((corp) => (
          <option key={corp.id} value={corp.id}>
            {corp.corporateId ? `${corp.corporateId} - ` : ''}{corp.legalName}
          </option>
        ))}
      </>
    )}
  </select>
);

// ============================================================================
// MAIN PAGE
// ============================================================================

const ProgramsPage: React.FC = () => {
  const [searchQuery, setSearchQuery] = useState('');
  const [typeFilter, setTypeFilter] = useState<ProgramType | 'ALL'>('ALL');
  const [statusFilter, setStatusFilter] = useState<ProgramStatus | 'ALL'>('ALL');
  const [selectedProgram, setSelectedProgram] = useState<Program | null>(null);
  const [editProgram, setEditProgram] = useState<Program | null>(null);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [programs, setPrograms] = useState<Program[]>([]);
  const [stats, setStats] = useState<ProgramStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [_error, setError] = useState<string | null>(null);
  // Action menu state
  const [actionMenuId, setActionMenuId] = useState<string | null>(null);

  // Corporate context state
  const [corporates, setCorporates] = useState<Corporate[]>([]);
  const [selectedCorporateId, setSelectedCorporateId] = useState<string | undefined>();
  const [corporatesLoading, setCorporatesLoading] = useState(true);

  // Load corporates on mount
  useEffect(() => {
    setCorporatesLoading(true);
    fetch(CORPORATES_API)
      .then(res => res.json())
      .then(result => {
        const corps = extractArray<Corporate>(result as any);
        setCorporates(corps);
      })
      .catch(err => {
        console.error('Failed to load corporates:', err);
        // Continue without corporates - page should still work
        setCorporates([]);
      })
      .finally(() => setCorporatesLoading(false));
  }, []);

  const loadData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const params: Record<string, string> = {};
      if (searchQuery) params.query = searchQuery;
      if (typeFilter !== 'ALL') params.programType = typeFilter;
      if (statusFilter !== 'ALL') params.status = statusFilter;
      if (selectedCorporateId) params.corporateId = selectedCorporateId;
      
      const res = await programApi.getAll(Object.keys(params).length > 0 ? params : undefined);
      
      if (res.success && res.data) {
        // Safe extraction - handles multiple response formats
        const responseData = res.data as any;
        const programList = responseData.programs || extractArray<Program>(res as any);
        const statsData = responseData.stats || null;
        
        setPrograms(Array.isArray(programList) ? programList : []);
        setStats(statsData);
      } else {
        // Handle failed response gracefully - show empty state, not error
        setPrograms([]);
        setStats(null);
        if (res.message && res.message !== 'Network error') {
          console.warn('Programs API returned:', res.message);
        }
      }
    } catch (err) {
      console.error('Failed to load programs:', err);
      // Set empty state rather than showing error for better UX
      setPrograms([]);
      setStats(null);
    } finally {
      setLoading(false);
    }
  }, [searchQuery, typeFilter, statusFilter, selectedCorporateId]);

  useEffect(() => { loadData(); }, [loadData]);

  const handleSaveProgram = async (data: Partial<Program> & { hierarchyLevelConfigs?: LevelConfigPayload[] }) => {
    // Extract level configs before saving (they're saved separately)
    const { hierarchyLevelConfigs, ...programData } = data;

    let savedProgram: Program | null = null;

    if (editProgram) {
      const res = await programApi.update(editProgram.id, programData);
      if (res.success) {
        savedProgram = res.data;
        setPrograms(prev => prev.map(p => p.id === editProgram.id ? res.data : p));
        setEditProgram(null);
      } else {
        console.error('Update failed:', res);
        alert('Failed to update program: ' + (res.message || 'Unknown error'));
        throw new Error(res.message || 'Update failed');
      }
    } else {
      const res = await programApi.create(programData);
      if (res.success) {
        savedProgram = res.data;
        setPrograms(prev => [res.data, ...prev]);
        setShowCreateModal(false);
      } else {
        console.error('Create failed:', res);
        alert('Failed to create program: ' + (res.message || 'Unknown error'));
        throw new Error(res.message || 'Create failed');
      }
    }

    // Save hierarchy level configs if present and program has hierarchy enabled
    if (savedProgram && hierarchyLevelConfigs && hierarchyLevelConfigs.length > 0 && programData.hierarchyEnabled) {
      try {
        const levelRes = await hierarchyLevelApi.saveLevelConfigs(savedProgram.id, hierarchyLevelConfigs);
        if (levelRes.success) {
          console.log('Hierarchy level configs saved successfully');
        } else {
          console.warn('Failed to save hierarchy level configs:', levelRes.message);
          // Don't throw - program was created successfully, level config is secondary
        }
      } catch (err) {
        console.error('Error saving hierarchy level configs:', err);
        // Don't throw - program was created successfully
      }
    }
  };

  const handleStatusChange = async (programId: string, status: string) => {
    const res = await programApi.updateStatus(programId, status);
    if (res.success) {
      setPrograms(prev => prev.map(p => p.id === programId ? res.data : p));
      if (selectedProgram?.id === programId) setSelectedProgram(res.data);
    }
  };

  // Calculate display stats with safe defaults
  const defaultStats: ProgramStats = {
    totalPrograms: 0, activePrograms: 0, inactivePrograms: 0, pendingPrograms: 0,
    collectionPrograms: 0, vibanPrograms: 0, escrowPrograms: 0, walletPrograms: 0,
    ihbPrograms: 0, payablesPrograms: 0, totalVirtualAccounts: 0, totalBalance: 0,
  };

  const displayStats: ProgramStats = stats || {
    ...defaultStats,
    totalPrograms: programs.length,
    activePrograms: programs.filter(p => p.status === 'ACTIVE').length,
    inactivePrograms: programs.filter(p => p.status === 'INACTIVE').length,
    pendingPrograms: programs.filter(p => p.status === 'PENDING_APPROVAL').length,
    collectionPrograms: programs.filter(p => p.programType === 'COLLECTION').length,
    vibanPrograms: programs.filter(p => p.programType === 'VIBAN').length,
    escrowPrograms: programs.filter(p => p.programType === 'ESCROW').length,
    walletPrograms: programs.filter(p => p.programType === 'WALLET').length,
    ihbPrograms: programs.filter(p => p.programType === 'IHB').length,
    payablesPrograms: programs.filter(p => p.programType === 'PAYABLES').length,
    totalVirtualAccounts: programs.reduce((s, p) => s + (p.virtualAccountCount || 0), 0),
    totalBalance: programs.reduce((s, p) => s + (p.totalBalance || 0), 0),
  };

  const typeTabs: { id: ProgramType | 'ALL'; label: string; count: number; icon: React.ElementType }[] = [
    { id: 'ALL', label: 'All', count: displayStats.totalPrograms, icon: Layers },
    { id: 'COLLECTION', label: 'Collection', count: displayStats.collectionPrograms, icon: CreditCard },
    { id: 'VIBAN', label: 'VIBAN', count: displayStats.vibanPrograms, icon: Hash },
    { id: 'ESCROW', label: 'Escrow', count: displayStats.escrowPrograms, icon: Shield },
    { id: 'WALLET', label: 'Wallet', count: displayStats.walletPrograms, icon: Wallet },
    { id: 'IHB', label: 'IHB', count: displayStats.ihbPrograms, icon: Building2 },
    { id: 'PAYABLES', label: 'Payables', count: displayStats.payablesPrograms, icon: Banknote },
  ];

  const filteredPrograms = programs.filter(p => {
    if (!p) return false;
    const matchesSearch = !searchQuery || 
      (p.programName?.toLowerCase().includes(searchQuery.toLowerCase())) || 
      (p.programCode?.toLowerCase().includes(searchQuery.toLowerCase()));
    return matchesSearch && (typeFilter === 'ALL' || p.programType === typeFilter) && (statusFilter === 'ALL' || p.status === statusFilter);
  });

  // Toolbar actions in the Aperture Layout header — same pattern as the
  // rest of Aperture's conformed pages (removes the floating in-page row).
  usePageHeaderActions(
    () => (
      <>
        <Button variant="outline" leftIcon={<Download className="w-4 h-4" />}>
          <span className="hidden sm:inline">Export</span>
        </Button>
        <Button leftIcon={<Plus className="w-4 h-4" />} onClick={() => setShowCreateModal(true)}>
          New Program
        </Button>
      </>
    ),
    []
  );

  // Show loading only on initial load, not on filter changes
  if (loading && programs.length === 0 && !corporatesLoading) {
    return (
      <div className="flex items-center justify-center h-96">
        <Loader2 className="w-8 h-8 animate-spin text-primary-600 dark:text-primary-200" />
      </div>
    );
  }

  return (
    <Page>
      {/* In-body identity (Phase 10) — aligns with the rest of Aperture's
          conformed pages. Export / New Program CTAs live in the Aperture
          Layout header via usePageHeaderActions above. */}
      <PageHeader
        title="Programs"
        description="Manage program definitions across pooling, in-house bank, escrow, and other product types. Each program is corporate-scoped with its own currency and lifecycle."
      />

      {/* Corporate Context Selector — uses the shared ScopeSelector
          primitive in `corporate-only` mode. Replaces the inline
          `CorporateSelector` component (still defined further up the file
          for any non-page-scope callers). */}
      <ScopeSelector
        mode="corporate-only"
        corporates={corporates}
        selectedCorporateId={selectedCorporateId || ''}
        onCorporateChange={(id) => setSelectedCorporateId(id || undefined)}
        loading={corporatesLoading}
      />

      {/* Headline figure — Total Balance across all programs. Matches the
          hero+strip hierarchy used elsewhere; these four metrics previously
          competed as an equal-weight strip with no visual hierarchy. */}
      <HeroMetricCard
        primary={{
          label: 'Total Balance',
          value: <TileAmount value={displayStats.totalBalance} currency="AED" />,
          sub: `${displayStats.totalPrograms} programs · ${displayStats.activePrograms} active`,
        }}
        icon={<TrendingUp className="w-7 h-7 text-accent-600 dark:text-accent-300" />}
      />

      {/* Operational metrics — secondary strip below the hero. */}
      <StatStrip>
        <StatTile layout="row" tone="primary" label="Total Programs" value={displayStats.totalPrograms} icon={<Layers className="w-5 h-5" />} delay="0.15s" />
        <StatTile layout="row" tone="success" label="Active" value={displayStats.activePrograms} icon={<CheckCircle className="w-5 h-5" />} delay="0.2s" />
        <StatTile layout="row" tone="info" label="Virtual Accounts" value={displayStats.totalVirtualAccounts} icon={<CreditCard className="w-5 h-5" />} delay="0.25s" />
      </StatStrip>

      {/* Type Filter Tabs */}
      <div className="grid grid-cols-4 md:grid-cols-7 gap-2">
        {typeTabs.map(tab => {
          const Icon = tab.icon;
          return (
            <div key={tab.id} onClick={() => setTypeFilter(tab.id)} className="cursor-pointer">
              <Card padding="sm" className={cn('transition-all hover:shadow-medium', typeFilter === tab.id && 'ring-2 ring-primary-500')}>
                <div className="flex items-center gap-2"><Icon className="w-4 h-4 text-neutral-500 dark:text-neutral-400" /><span className="text-xs text-neutral-500 truncate dark:text-neutral-400">{tab.label}</span></div>
                <p className="text-lg font-semibold text-primary-900 mt-1 dark:text-neutral-50">{tab.count}</p>
              </Card>
            </div>
          );
        })}
      </div>

      {/* Search and Filters */}
      <Card padding="md">
        <div className="flex flex-col sm:flex-row gap-4">
          <div className="flex-1 relative">
            <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-neutral-400 dark:text-neutral-500" />
            <input type="text" placeholder="Search programs..." className="w-full pl-10 pr-4 py-2 border border-neutral-300 rounded-lg dark:border-primary-700" value={searchQuery} onChange={e => setSearchQuery(e.target.value)} />
          </div>
          <select className="px-3 py-2 border border-neutral-300 rounded-lg dark:border-primary-700" value={statusFilter} onChange={e => setStatusFilter(e.target.value as ProgramStatus | 'ALL')}>
            <option value="ALL">All Statuses</option><option value="ACTIVE">Active</option><option value="INACTIVE">Inactive</option><option value="SUSPENDED">Suspended</option><option value="PENDING_APPROVAL">Pending</option>
          </select>
          <Button variant="outline" leftIcon={<RefreshCw className={cn("w-4 h-4", loading && "animate-spin")} />} onClick={loadData} disabled={loading}>
            {loading ? 'Loading...' : 'Refresh'}
          </Button>
        </div>
      </Card>

      {/* Programs Table */}
      <Card>
        <div className="overflow-x-auto">
          <table className="w-full">
            <thead className="bg-neutral-50 border-b dark:bg-primary-950">
              <tr>
                <th className="text-left p-4 font-medium text-neutral-600 dark:text-neutral-300">Program</th>
                <th className="text-left p-4 font-medium text-neutral-600 dark:text-neutral-300">Type</th>
                <th className="text-left p-4 font-medium text-neutral-600 dark:text-neutral-300">Corporate</th>
                <th className="text-center p-4 font-medium text-neutral-600 dark:text-neutral-300">VAs</th>
                <th className="text-right p-4 font-medium text-neutral-600 dark:text-neutral-300">Balance</th>
                <th className="text-left p-4 font-medium text-neutral-600 dark:text-neutral-300">Features</th>
                <th className="text-left p-4 font-medium text-neutral-600 dark:text-neutral-300">Status</th>
                <th className="text-right p-4 font-medium text-neutral-600 dark:text-neutral-300">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y">
              {filteredPrograms.map(program => {
                const typeConfig = programTypeConfig[program.programType];
                const TypeIcon = typeConfig?.icon || Layers;
                const stConfig = statusConfig[program.status];
                const hasHierarchy = program.hierarchyEnabled && program.rootHierarchyNodeId;
                return (
                  <tr key={program.id} className="hover:bg-neutral-50 dark:hover:bg-primary-800/50">
                    <td className="p-4">
                      <div className="flex items-center gap-3">
                        <div className={cn('w-10 h-10 rounded-lg flex items-center justify-center', typeConfig?.bgColor || 'bg-neutral-100 dark:bg-primary-800')}>
                          <TypeIcon className={cn('w-5 h-5', typeConfig?.color || 'text-neutral-600 dark:text-neutral-300')} />
                        </div>
                        <div>
                          <div className="flex items-center gap-2">
                            <p className="font-medium text-primary-900 dark:text-neutral-50">{program.programName}</p>
                            {hasHierarchy && <span title="Hierarchy Enabled"><GitBranch className="w-3 h-3 text-accent-500" /></span>}
                            {program.realtimeBalancePropagation && <span title="Real-time Balance"><Zap className="w-3 h-3 text-success-500" /></span>}
                          </div>
                          <p className="text-xs text-neutral-500 font-mono dark:text-neutral-400">{program.programCode}</p>
                        </div>
                      </div>
                    </td>
                    <td className="p-4"><Badge variant="neutral">{typeConfig?.label || program.programType}</Badge></td>
                    <td className="p-4"><p className="text-sm text-primary-900 dark:text-neutral-50">{program.corporateName || '-'}</p></td>
                    <td className="p-4 text-center"><p className="text-sm font-medium">{program.virtualAccountCount || 0}</p><p className="text-xs text-neutral-500 dark:text-neutral-400">{program.activeVirtualAccountCount || 0} active</p></td>
                    <td className="p-4 text-right"><p className="font-medium text-primary-900 dark:text-neutral-50">{formatCurrency(program.totalBalance || 0, program.currencyCode || 'AED')}</p></td>
                    <td className="p-4">
                      <div className="flex gap-1 flex-wrap">
                        {program.vibanEnabled && <Badge variant="info" size="sm">VIBAN</Badge>}
                        {program.walletEnabled && <Badge variant="warning" size="sm">Wallet</Badge>}
                        {program.escrowEnabled && <Badge variant="success" size="sm">Escrow</Badge>}
                        {program.ihbEnabled && <Badge variant="info" size="sm">IHB</Badge>}
                        {hasHierarchy && <Badge variant="neutral" size="sm">Hierarchy</Badge>}
                        {program.loyaltyEnabled && <Badge variant="neutral" size="sm">Loyalty</Badge>}
                        {program.giftCardEnabled && <Badge variant="neutral" size="sm">Gift</Badge>}
                        {!program.vibanEnabled && !program.walletEnabled && !program.escrowEnabled && !program.ihbEnabled && <span className="text-xs text-neutral-400 dark:text-neutral-500">Standard</span>}
                      </div>
                    </td>
                    <td className="p-4"><Badge variant={stConfig?.variant}>{stConfig?.label || program.status}</Badge></td>
                    <td className="p-4 text-right">
                      <div className="flex justify-end gap-1">
                        <Button size="sm" variant="ghost" onClick={() => setSelectedProgram(program)} title="View Details"><Eye className="w-4 h-4" /></Button>
                        <Button size="sm" variant="ghost" onClick={() => setEditProgram(program)} title="Edit Program"><Edit className="w-4 h-4" /></Button>
                        {/* Action Menu Dropdown */}
                        <div className="relative">
                          <Button
                            size="sm"
                            variant="ghost"
                            onClick={() => setActionMenuId(actionMenuId === program.id ? null : program.id)}
                            title="More Actions"
                          >
                            <MoreHorizontal className="w-4 h-4" />
                          </Button>
                          {actionMenuId === program.id && (
                            <>
                              {/* Backdrop to close menu when clicking outside */}
                              <div className="fixed inset-0 z-10" onClick={() => setActionMenuId(null)} />
                              {/* Dropdown Menu */}
                              <div className="absolute right-0 top-full mt-1 w-48 bg-white border border-neutral-200 rounded-lg shadow-lg z-20 py-1 dark:bg-primary-900 dark:border-primary-800">
                                {/* Status Actions */}
                                {program.status === 'ACTIVE' && (
                                  <button
                                    className="w-full px-3 py-2 text-left text-sm hover:bg-neutral-50 flex items-center gap-2 text-warning-600 dark:hover:bg-primary-800/50 dark:text-warning-300"
                                    onClick={() => { handleStatusChange(program.id, 'SUSPENDED'); setActionMenuId(null); }}
                                  >
                                    <PauseCircle className="w-4 h-4" />
                                    Suspend Program
                                  </button>
                                )}
                                {program.status === 'SUSPENDED' && (
                                  <button
                                    className="w-full px-3 py-2 text-left text-sm hover:bg-neutral-50 flex items-center gap-2 text-success-600 dark:hover:bg-primary-800/50 dark:text-success-300"
                                    onClick={() => { handleStatusChange(program.id, 'ACTIVE'); setActionMenuId(null); }}
                                  >
                                    <PlayCircle className="w-4 h-4" />
                                    Activate Program
                                  </button>
                                )}
                                {program.status === 'PENDING_APPROVAL' && (
                                  <button
                                    className="w-full px-3 py-2 text-left text-sm hover:bg-neutral-50 flex items-center gap-2 text-success-600 dark:hover:bg-primary-800/50 dark:text-success-300"
                                    onClick={() => { handleStatusChange(program.id, 'ACTIVE'); setActionMenuId(null); }}
                                  >
                                    <CheckCircle className="w-4 h-4" />
                                    Approve Program
                                  </button>
                                )}
                                {/* Clone Action */}
                                <button
                                  className="w-full px-3 py-2 text-left text-sm hover:bg-neutral-50 flex items-center gap-2 text-neutral-700 dark:hover:bg-primary-800/50 dark:text-neutral-200"
                                  onClick={() => {
                                    // Clone by opening create modal with program data pre-filled
                                    setEditProgram({ ...program, id: '', programCode: program.programCode + '-COPY', programName: program.programName + ' (Copy)' } as Program);
                                    setActionMenuId(null);
                                  }}
                                >
                                  <Copy className="w-4 h-4" />
                                  Clone Program
                                </button>
                                {/* Divider */}
                                <div className="border-t border-neutral-100 my-1 dark:border-primary-800/60" />
                                {/* Delete Action */}
                                <button
                                  className="w-full px-3 py-2 text-left text-sm hover:bg-error-50 flex items-center gap-2 text-error-600 dark:hover:bg-error-500/10 dark:text-error-300"
                                  onClick={() => {
                                    if (confirm(`Are you sure you want to delete "${program.programName}"? This action cannot be undone.`)) {
                                      programApi.delete(program.id).then(res => {
                                        if (res.success) {
                                          setPrograms(prev => prev.filter(p => p.id !== program.id));
                                        }
                                      });
                                    }
                                    setActionMenuId(null);
                                  }}
                                >
                                  <Trash2 className="w-4 h-4" />
                                  Delete Program
                                </button>
                              </div>
                            </>
                          )}
                        </div>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        
        {/* Empty State - Graceful handling when no data */}
        {filteredPrograms.length === 0 && !loading && (
          <div className="text-center py-12">
            <Layers className="w-12 h-12 mx-auto mb-3 text-neutral-300 dark:text-neutral-600" />
            <p className="text-lg font-medium text-primary-900 dark:text-neutral-50">No programs found</p>
            <p className="text-sm text-neutral-500 mt-1 dark:text-neutral-400">
              {searchQuery || typeFilter !== 'ALL' || statusFilter !== 'ALL' 
                ? 'Try adjusting your search or filters'
                : selectedCorporateId 
                  ? 'Create a new program to get started'
                  : 'Programs will appear here once created'}
            </p>
            <Button className="mt-4" size="sm" onClick={() => setShowCreateModal(true)} leftIcon={<Plus className="w-4 h-4" />}>
              Create Program
            </Button>
          </div>
        )}
        
        {/* Loading overlay for table refresh */}
        {loading && programs.length > 0 && (
          <div className="absolute inset-0 bg-white/50 flex items-center justify-center dark:bg-primary-900/50">
            <Loader2 className="w-6 h-6 animate-spin text-primary-600 dark:text-primary-200" />
          </div>
        )}
      </Card>

      {/* Modals */}
      <ProgramDetailModal program={selectedProgram} onClose={() => setSelectedProgram(null)} onEdit={p => { setSelectedProgram(null); setEditProgram(p); }} onStatusChange={handleStatusChange} />
      <ProgramFormModal
        isOpen={showCreateModal || !!editProgram}
        program={editProgram}
        onClose={() => { setShowCreateModal(false); setEditProgram(null); }}
        onSave={handleSaveProgram}
        defaultCorporateId={selectedCorporateId}
      />
    </Page>
  );
};

export default ProgramsPage;