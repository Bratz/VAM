// Program page building blocks, split out of ProgramsPage.tsx.
import React from 'react';
import { CheckCircle, XCircle, Clock, PauseCircle, TrendingUp, Hash, GitBranch } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';


// ============================================================================
// API & TYPES
// ============================================================================

export const API_BASE = '/api/v1/programs';
export const CORPORATES_API = '/api/v1/corporates';
export interface ApiResponse<T> { success: boolean; data: T; message?: string; }

export async function fetchApi<T>(url: string, options?: RequestInit, base = API_BASE): Promise<ApiResponse<T>> {
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
export const extractArray = <T,>(response: ApiResponse<T[] | { content: T[] } | { programs: T[] } | { corporates: T[] }>): T[] => {
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

// ============================================================================
// CORPORATE TYPE FOR SELECTOR
// ============================================================================

export interface Corporate {
  id: string;
  corporateId: string;
  legalName: string;
  tradeName?: string;
  status: string;
}

export const CHARGES_API_BASE = '/api/v1/program-charges';

// ============================================================================
// WALLET CHARGES TYPES (from ChargeConfiguration API)
// ============================================================================

export interface ChargeDetail {
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

export interface WalletChargesResponse {
  programId: string;
  programCode: string;
  topup: ChargeDetail;
  withdrawal: ChargeDetail;
  transfer: ChargeDetail;
  issuance: ChargeDetail;
  monthly: ChargeDetail;
  inactivity: ChargeDetail;
}

export interface WalletChargesRequest {
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
// Standard ChargeConfiguration base rates, shown only while creating a program (a program has no
// fee record until it exists). Never used as a fallback when a real program's fees fail to load.
export const STANDARD_WALLET_BASE_RATES: WalletChargesResponse = {
  programId: '',
  programCode: '',
  topup: { chargeCode: 'WALLET_TOPUP', chargeName: 'Wallet Topup Fee', percentage: 1.5, fixed: 0, minimum: 1, maximum: 100, isWaived: false, hasOverride: false, source: 'BASE' },
  withdrawal: { chargeCode: 'WALLET_WITHDRAWAL', chargeName: 'Wallet Withdrawal Fee', percentage: 2.0, fixed: 5, minimum: 5, maximum: 200, isWaived: false, hasOverride: false, source: 'BASE' },
  transfer: { chargeCode: 'WALLET_TRANSFER', chargeName: 'Wallet Transfer Fee', percentage: 0.5, fixed: 1, minimum: 1, maximum: 50, isWaived: false, hasOverride: false, source: 'BASE' },
  issuance: { chargeCode: 'WALLET_ISSUANCE', chargeName: 'Wallet Issuance Fee', percentage: 0, fixed: 10, isWaived: false, hasOverride: false, source: 'BASE' },
  monthly: { chargeCode: 'WALLET_MONTHLY', chargeName: 'Wallet Monthly Fee', percentage: 0, fixed: 5, isWaived: false, hasOverride: false, source: 'BASE' },
  inactivity: { chargeCode: 'WALLET_INACTIVITY', chargeName: 'Wallet Inactivity Fee', percentage: 0, fixed: 10, isWaived: false, hasOverride: false, source: 'BASE' },
};

export type ProgramStatus = 'ACTIVE' | 'INACTIVE' | 'SUSPENDED' | 'PENDING_APPROVAL' | 'CLOSED';
export type VibanGenerationStrategy = 'SEQUENTIAL' | 'RANDOM' | 'HIERARCHY_ENCODED';

export interface Program {
  id: string; programCode: string; programName: string;
  description?: string; corporateId: string; corporateName?: string;
  physicalAccountId: string; physicalAccountNumber?: string; currencyCode: string;
  vaPrefix?: string; vaFormat?: string; maxVirtualAccounts?: number; autoReconciliation: boolean;
  // NEW: Hierarchy Support (7-level)
  hierarchyDepth?: number;
  defaultHierarchyTemplate?: string;
  rootHierarchyNodeId?: string;
  // NEW: VIBAN Pool Settings
  defaultVibanPoolId?: string;
  vibanGenerationStrategy?: VibanGenerationStrategy;
  vibanPrefix?: string;
  vibanBankCode?: string;
  // NEW: Balance Aggregation
  // NEW: Additional Program Type Flags
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

export interface ProgramDetail {
  program: Program;
  corporate?: { id: string; corporateId: string; legalName: string; tradeName?: string; status: string };
  physicalAccount?: {
    id: string; accountNumber: string; accountName: string; bankName: string; currencyCode: string; currentBalance: number; status: string;
    /** Set when this backing account's shadow belongs to another program (payments are then refused). */
    heldByProgramCode?: string; heldByProgramName?: string;
    /** Nothing mirrors this account. */
    noShadow?: boolean;
  };
  recentVirtualAccounts: { id: string; vaNumber: string; viban?: string; vaName: string; currentBalance: number; status: string; createdAt: string }[];
  usageStats: { totalTransactions: number; todayTransactions: number; totalVolume: number; todayVolume: number; averageBalance: number; lastTransactionAt?: string };
  activityLog: { action: string; user: string; timestamp: string; type: string; details: string }[];
}

export interface ProgramStats {
  totalPrograms: number; activePrograms: number; inactivePrograms: number; pendingPrograms: number;
  totalVirtualAccounts: number; totalBalance: number;
}

export interface ProgramListResponse { programs: Program[]; totalCount: number; page: number; pageSize: number; stats: ProgramStats; }

// NEW: Settlement VA interface for hierarchy tab
export interface SettlementVa {
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

/** Shadow account of a home-bank account, as offered at program setup. programId is null while unassigned. */
export interface BankShadow {
  id: string;
  vaNumber: string;
  bankAccountNumber: string;
  bankName: string;
  currencyCode: string;
  bankBalance?: number;
  programId?: string | null;
  programName?: string | null;
}

// NEW: VIBAN Pool interface for VIBAN tab
export interface VibanPool {
  id: string;
  poolName: string;
  poolCode: string;
  bankCode: string;
  prefix: string;
  poolSize: number;
  availableCount: number;
  assignedCount: number;
  reservedCount: number;
  status: string;
}

export const programApi = {
  getAll: (params?: Record<string, string>) => {
    const query = params ? '?' + new URLSearchParams(params).toString() : '';
    return fetchApi<ProgramListResponse>(query);
  },
  getDetail: (id: string) => fetchApi<ProgramDetail>('/' + id + '/detail'),
  getStats: () => fetchApi<ProgramStats>('/stats'),
  create: (data: Partial<Program>) => fetchApi<Program>('', { method: 'POST', body: JSON.stringify(data) }),
  update: (id: string, data: Partial<Program>) => fetchApi<Program>('/' + id, { method: 'PUT', body: JSON.stringify(data) }),
  updateStatus: (id: string, status: string, reason?: string) => fetchApi<Program>('/' + id + '/status', { method: 'PATCH', body: JSON.stringify({ status, reason }) }),
  /** Close for good (DELETE on the API): releases its bank accounts; refused while customer accounts are active. */
  close: (id: string) => fetchApi<Program>('/' + id, { method: 'DELETE' }),
  clone: (id: string, newProgramCode: string, newProgramName: string) =>
    fetchApi<Program>('/' + id + '/clone', { method: 'POST', body: JSON.stringify({ newProgramCode, newProgramName }) }),
};

// NEW: Treasury API for Settlement VAs
export const TREASURY_API = '/api/v1/treasury/settlement-vas';

export const treasuryApi = {
  getSettlementVas: (programId: string) =>
    fetchApi<{ settlementVas: SettlementVa[]; exceptionVas: SettlementVa[] }>(`/program/${programId}`, {}, TREASURY_API),
  initializeHierarchy: (data: { programId: string; templateType: string }) =>
    fetchApi<any>('/initialize', { method: 'POST', body: JSON.stringify(data) }, TREASURY_API),
};

// Hierarchy Level Config API
export const HIERARCHY_CONFIG_API = '/api/v1/programs';

export interface LevelConfigPayload {
  levelNumber: number;
  levelName: string;
  dimensionType: string;
  isRequired?: boolean;
  allowedValues?: string[];
  description?: string;
  icon?: string;
}

export const hierarchyLevelApi = {
  saveLevelConfigs: (programId: string, levels: LevelConfigPayload[]) =>
    fetchApi<any[]>(`/${programId}/hierarchy/config`, {
      method: 'POST',
      body: JSON.stringify({ levels })
    }, HIERARCHY_CONFIG_API),
};

// NEW: VIBAN Pool API
export const VIBAN_POOL_API = '/api/v1/viban-pools';

export const vibanPoolApi = {
  getAll: () => fetchApi<VibanPool[]>('', {}, VIBAN_POOL_API),
  getById: (id: string) => fetchApi<VibanPool>(`/${id}`, {}, VIBAN_POOL_API),
  getByProgram: (programId: string) => fetchApi<VibanPool>(`/program/${programId}`, {}, VIBAN_POOL_API),
};

// VIBAN Generation Strategy Config
export const vibanStrategyConfig: Record<string, { label: string; description: string; icon: LucideIcon }> = {
  SEQUENTIAL: { label: 'Sequential', description: 'VIBANs are generated in sequential order (001, 002, 003...)', icon: TrendingUp },
  RANDOM: { label: 'Random', description: 'VIBANs are generated with random unique identifiers', icon: Hash },
  HIERARCHY_ENCODED: { label: 'Hierarchy Encoded', description: 'VIBAN includes encoded hierarchy path for routing', icon: GitBranch },
};

// ============================================================================
// CONFIGURATION
// ============================================================================

export type BadgeVariant = 'success' | 'error' | 'warning' | 'info' | 'neutral';
export const statusConfig: Record<string, { label: string; variant: BadgeVariant; icon: React.ElementType }> = {
  ACTIVE: { label: 'Active', variant: 'success', icon: CheckCircle },
  INACTIVE: { label: 'Inactive', variant: 'neutral', icon: XCircle },
  SUSPENDED: { label: 'Suspended', variant: 'warning', icon: PauseCircle },
  PENDING_APPROVAL: { label: 'Pending', variant: 'info', icon: Clock },
  CLOSED: { label: 'Closed', variant: 'error', icon: XCircle },
};


// ============================================================================
// PROGRAM TYPE → FEATURE FLAG MAPPING
// Auto-enables the corresponding feature flag when a program type is selected
// Based on backend design where features are orthogonal to types but have
// natural associations
// ============================================================================

