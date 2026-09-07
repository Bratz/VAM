import axios, { AxiosInstance } from 'axios';

// ============================================================================
// API CLIENT CONFIGURATION
// ============================================================================

// Production builds must set VITE_API_BASE_URL at build time. Falling back
// to localhost in a production bundle would ship a broken artefact whose
// failure mode (silent CORS / 404 on every request) is much harder to
// diagnose than a hard error at boot. Dev fallback preserved so
// `npm run dev` works without any extra env setup.
const API_BASE_URL: string = (() => {
  const fromEnv = import.meta.env.VITE_API_BASE_URL as string | undefined;
  if (fromEnv && fromEnv.length > 0) return fromEnv;
  if (import.meta.env.PROD) {
    throw new Error(
      'VITE_API_BASE_URL must be set at build time for production builds. ' +
      'Set it in the build environment or .env.production before `npm run build`.'
    );
  }
  return 'http://localhost:8053/api/v1';
})();

export const apiClient: AxiosInstance = axios.create({
  baseURL: API_BASE_URL,
  headers: { 'Content-Type': 'application/json' },
  timeout: 30000,
});

// Request interceptor
apiClient.interceptors.request.use(
  (config) => {
    // Existing token handling
    const token = localStorage.getItem('auth_token');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }

    // Auto-attach corporate/entity context headers
    // Only attach if not already set by the caller
    if (!config.headers['X-Corporate-Id']) {
      const corporateId = localStorage.getItem('current_corporate_id');
      if (corporateId) {
        config.headers['X-Corporate-Id'] = corporateId;
      }
    }

    if (!config.headers['X-Legal-Entity-Id']) {
      const entityId = localStorage.getItem('current_entity_id');
      if (entityId) {
        config.headers['X-Legal-Entity-Id'] = entityId;
      }
    }

    return config;
  },
  (error) => Promise.reject(error)
);

// Response interceptor
apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    console.error('API Error:', error.response?.data || error.message);
    return Promise.reject(error);
  }
);

// ============================================================================
// TYPES
// ============================================================================

export interface ApiResponse<T> {
  success: boolean;
  data: T;
  message?: string;
  error?: string;
}

export interface PaginatedResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
}

// ============================================================================
// DASHBOARD API
// ============================================================================

export interface DashboardStats {
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

export interface BalanceTrendPoint {
  date: string;
  fullDate?: string;
  balance: number;
  available?: number;
  inflow?: number;
  outflow?: number;
}

export interface TopAccount {
  id: string;
  vaNumber: string;
  viban: string;
  name: string;
  balance: number;
  availableBalance: number;
  currency: string;
  status: string;
}

export interface RecentTransaction {
  id: string;
  reference: string;
  beneficiary: string;
  amount: number;
  currency: string;
  type: 'CREDIT' | 'DEBIT';
  status: string;
  date: string;
}

export interface TreasurySummary {
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

export interface DashboardAlert {
  id: string;
  type: 'WARNING' | 'INFO' | 'ERROR' | 'SUCCESS';
  severity: 'HIGH' | 'MEDIUM' | 'LOW';
  title: string;
  message: string;
  action?: string;
  timestamp: string;
}

export interface PendingApprovals {
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

// Helper to safely extract data from ApiResponse
const extractData = <T>(response: ApiResponse<T>): T => {
  if (response && response.data !== undefined) {
    return response.data;
  }
  return response as unknown as T;
};

export const dashboardApi = {
  getStats: async (): Promise<DashboardStats> => {
    try {
      const response = await apiClient.get<ApiResponse<DashboardStats>>('/dashboard/stats');
      return extractData(response.data);
    } catch (error) {
      console.error('Failed to fetch dashboard stats:', error);
      // Return default values on error
      return {
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
    }
  },

  getBalanceTrend: async (days = 30): Promise<BalanceTrendPoint[]> => {
    try {
      const response = await apiClient.get<ApiResponse<BalanceTrendPoint[]>>('/dashboard/balance-trend', { 
        params: { days } 
      });
      const data = extractData(response.data);
      return Array.isArray(data) ? data : [];
    } catch (error) {
      console.error('Failed to fetch balance trend:', error);
      return [];
    }
  },

  getTopAccounts: async (limit = 5): Promise<TopAccount[]> => {
    try {
      const response = await apiClient.get<ApiResponse<TopAccount[]>>('/dashboard/top-accounts', { 
        params: { limit } 
      });
      const data = extractData(response.data);
      return Array.isArray(data) ? data : [];
    } catch (error) {
      console.error('Failed to fetch top accounts:', error);
      return [];
    }
  },

  getRecentTransactions: async (limit = 10): Promise<RecentTransaction[]> => {
    try {
      const response = await apiClient.get<ApiResponse<RecentTransaction[]>>('/dashboard/recent-transactions', { 
        params: { limit } 
      });
      const data = extractData(response.data);
      return Array.isArray(data) ? data : [];
    } catch (error) {
      console.error('Failed to fetch recent transactions:', error);
      return [];
    }
  },

  getRecentActivity: async (limit = 10): Promise<RecentTransaction[]> => {
    // Alias for backward compatibility
    return dashboardApi.getRecentTransactions(limit);
  },

  getTreasurySummary: async (): Promise<TreasurySummary> => {
    try {
      const response = await apiClient.get<ApiResponse<TreasurySummary>>('/dashboard/treasury-summary');
      return extractData(response.data);
    } catch (error) {
      console.error('Failed to fetch treasury summary:', error);
      // Return default values on error
      return {
        pooling: { activePools: 0, totalPooledBalance: 0, interestSavingsYtd: 0, memberCount: 0 },
        sweeping: { activeRules: 0, totalSweptToday: 0, executionsToday: 0, nextExecution: '' },
        netting: { pendingCycles: 0, totalSavingsYtd: 0, averageSavingsPercent: 0, settledCyclesMtd: 0 },
        inHouseBank: { totalLoansOutstanding: 0, totalDeposits: 0, netInterestIncome: 0, activeEntities: 0 },
      };
    }
  },

  getAlerts: async (): Promise<DashboardAlert[]> => {
    try {
      const response = await apiClient.get<ApiResponse<DashboardAlert[]>>('/dashboard/alerts');
      const data = extractData(response.data);
      return Array.isArray(data) ? data : [];
    } catch (error) {
      console.error('Failed to fetch alerts:', error);
      return [];
    }
  },

  getPendingApprovals: async (): Promise<PendingApprovals> => {
    try {
      const response = await apiClient.get<ApiResponse<PendingApprovals>>('/dashboard/pending-approvals');
      return extractData(response.data);
    } catch (error) {
      console.error('Failed to fetch pending approvals:', error);
      // Return default values on error
      return {
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
    }
  },

  getCashFlow: async (days = 30): Promise<any> => {
    try {
      const response = await apiClient.get<ApiResponse<any>>('/dashboard/cash-flow', { 
        params: { days } 
      });
      return extractData(response.data);
    } catch (error) {
      console.error('Failed to fetch cash flow:', error);
      return null;
    }
  },

  getReceivablesPayables: async (): Promise<any> => {
    try {
      const response = await apiClient.get<ApiResponse<any>>('/dashboard/receivables-payables');
      return extractData(response.data);
    } catch (error) {
      console.error('Failed to fetch receivables/payables:', error);
      return null;
    }
  },

  getAccountDistribution: async (): Promise<any> => {
    try {
      const response = await apiClient.get<ApiResponse<any>>('/dashboard/account-distribution');
      return extractData(response.data);
    } catch (error) {
      console.error('Failed to fetch account distribution:', error);
      return null;
    }
  },

  getWidgetData: async (widgetType: string): Promise<any> => {
    try {
      const response = await apiClient.get<ApiResponse<any>>(`/dashboard/widget/${widgetType}`);
      return extractData(response.data);
    } catch (error) {
      console.error(`Failed to fetch widget data for ${widgetType}:`, error);
      return null;
    }
  },
};
// ============================================================================
// CORPORATES API
// ============================================================================

export interface Corporate {
  id: string;
  corporateId: string;
  legalName: string;
  tradeName?: string;
  registrationNumber?: string;
  taxId?: string;
  incorporationCountry?: string;
  industrySector?: string;
  status: string;
  kycStatus: string;
  primaryContactName?: string;
  primaryContactEmail?: string;
  primaryContactPhone?: string;
  createdAt: string;
}

export const corporatesApi = {
  getAll: (page = 0, size = 20) => apiClient.get<ApiResponse<Corporate[]>>('/corporates', { params: { page, size } }).then(r => r.data),
  getById: (id: string) => apiClient.get<ApiResponse<Corporate>>(`/corporates/${id}`).then(r => r.data),
  create: (data: Partial<Corporate>) => apiClient.post<ApiResponse<Corporate>>('/corporates', data).then(r => r.data),
  update: (id: string, data: Partial<Corporate>) => apiClient.put<ApiResponse<Corporate>>(`/corporates/${id}`, data).then(r => r.data),
  approveKyc: (id: string) => apiClient.post<ApiResponse<Corporate>>(`/corporates/${id}/kyc/approve`).then(r => r.data),
  updateStatus: (id: string, status: string) => apiClient.post<ApiResponse<Corporate>>(`/corporates/${id}/status`, null, { params: { status } }).then(r => r.data),
  getStats: () => apiClient.get<ApiResponse<any>>('/corporates/stats').then(r => r.data),
};

// ============================================================================
// VIRTUAL ACCOUNTS API
// ============================================================================

export type AccountCategory =
  | 'TRANSACTION'
  | 'COLLECTION'
  | 'DISBURSEMENT'
  | 'SETTLEMENT'
  | 'EXCEPTION'
  | 'SUSPENSE'
  | 'ROOT'
  | 'AGGREGATION'
  | 'PHYSICAL_MIRROR'
  | 'EXTERNAL_MIRROR'
  | 'CURRENCY_MIRROR'
  | 'INTERCOMPANY'
  | 'ESCROW'
  | 'NETTING';

export interface VirtualAccount {
  id: string;
  vaNumber: string;
  viban?: string;
  vaName: string;
  corporateId: string;
  corporateName?: string;
  owningEntityId?: string;  // Legal entity that owns this VA
  owningEntityName?: string;
  // Optional generic API-response aliases. Some list endpoints return
  // account data under generic names rather than the VA-specific ones
  // (vaNumber/vaName/currencyCode). Consumers read these defensively
  // (e.g. `acc.vaNumber || acc.accountNumber`); declared optional so the
  // fallbacks are type-safe without affecting any required-field usage.
  accountNumber?: string;
  accountName?: string;
  currency?: string;
  entityCode?: string;
  physicalAccountId: string;
  programId?: string;
  currencyCode: string;
  currentBalance: number;
  availableBalance: number;
  heldBalance?: number;
  // Credit Limit Fields
  effectiveCreditLimit?: number;
  creditLimitUtilized?: number;
  creditLimitAvailable?: number;
  status: string;
  walletType?: string;
  accountCategory?: AccountCategory;
  parentAccountId?: string;
  hierarchyLevel?: number;
  createdAt: string;
  updatedAt?: string;
  // Phase 1: Publish Status (Published VA = Has VIBAN for external payments)
  publishStatus?: 'UNPUBLISHED' | 'PUBLISHED' | 'SUSPENDED';
  isPublished?: boolean;
  canBePublished?: boolean;
  canReceiveExternalPayments?: boolean;
  publishedAt?: string;
  publishedBy?: string;
  // Aggregated balances (for AGGREGATION, ROOT, CURRENCY_MIRROR nodes)
  aggregatedBalance?: number;
  aggregatedBalanceBase?: number;
  mirrorBalance?: number;
  balanceInBase?: number;
  baseCurrency?: string;
}

// Publish Status Info response
export interface PublishStatusInfo {
  id: string;
  vaNumber: string;
  viban?: string;
  publishStatus: 'UNPUBLISHED' | 'PUBLISHED' | 'SUSPENDED';
  isPublished: boolean;
  canBePublished: boolean;
  canReceiveExternalPayments: boolean;
  publishedAt?: string;
  publishedBy?: string;
  unpublishedAt?: string;
  unpublishReason?: string;
}

// Publish/Unpublish request types
export interface PublishVaRequest {
  viban?: string;  // Optional - will be auto-generated if not provided
  publishedBy?: string;
}

export interface UnpublishVaRequest {
  reason: string;
}

export const virtualAccountsApi = {
  getAll: (page = 0, size = 20, corporateId?: string) =>
    apiClient.get<ApiResponse<VirtualAccount[]>>('/virtual-accounts', { params: { page, size, corporateId } }).then(r => r.data),
  getById: (id: string) => apiClient.get<ApiResponse<VirtualAccount>>(`/virtual-accounts/${id}`).then(r => r.data),
  getByVaNumber: (vaNumber: string) => apiClient.get<ApiResponse<VirtualAccount>>(`/virtual-accounts/number/${vaNumber}`).then(r => r.data),
  create: (data: any) => apiClient.post<ApiResponse<VirtualAccount>>('/virtual-accounts', data).then(r => r.data),
  update: (id: string, data: any) => apiClient.put<ApiResponse<VirtualAccount>>(`/virtual-accounts/${id}`, data).then(r => r.data),
  getStats: () => apiClient.get<ApiResponse<any>>('/virtual-accounts/stats').then(r => r.data),

  // Phase 1: Publish/Unpublish methods (Published VA = Has VIBAN for external payments)
  publish: (id: string, request?: PublishVaRequest) =>
    apiClient.post<ApiResponse<VirtualAccount>>(`/virtual-accounts/${id}/publish`, request || {}).then(r => r.data),
  unpublish: (id: string, request: UnpublishVaRequest) =>
    apiClient.post<ApiResponse<VirtualAccount>>(`/virtual-accounts/${id}/unpublish`, request).then(r => r.data),
  suspendPublish: (id: string, reason: string) =>
    apiClient.post<ApiResponse<VirtualAccount>>(`/virtual-accounts/${id}/suspend-publish`, null, { params: { reason } }).then(r => r.data),
  republish: (id: string, publishedBy = 'SYSTEM') =>
    apiClient.post<ApiResponse<VirtualAccount>>(`/virtual-accounts/${id}/republish`, null, { params: { publishedBy } }).then(r => r.data),
  getPublishStatus: (id: string) =>
    apiClient.get<ApiResponse<PublishStatusInfo>>(`/virtual-accounts/${id}/publish-status`).then(r => r.data),

  // INTERCOMPANY VAs
  getIntercompanyByProgram: (programId: string) =>
    apiClient.get<ApiResponse<VirtualAccount[]>>(`/virtual-accounts/program/${programId}/intercompany`).then(r => r.data),

  // Get VAs by category for a program
  getByProgramAndCategory: (programId: string, category: string) =>
    apiClient.get<ApiResponse<VirtualAccount[]>>(`/virtual-accounts/program/${programId}/category/${category}`).then(r => r.data),
};

// ============================================================================
// PHYSICAL ACCOUNTS API
// ============================================================================

export interface PhysicalAccount {
  id: string;
  accountNumber: string;
  iban?: string;
  accountName: string;
  corporateId: string;
  currencyCode: string;
  currentBalance: number;
  availableBalance: number;
  accountType?: string;
  status: string;
  branchCode?: string;
  createdAt: string;
}

export const physicalAccountsApi = {
  getAll: (page = 0, size = 20, corporateId?: string) => 
    apiClient.get<ApiResponse<PhysicalAccount[]>>('/physical-accounts', { params: { page, size, corporateId } }).then(r => r.data),
  getById: (id: string) => apiClient.get<ApiResponse<PhysicalAccount>>(`/physical-accounts/${id}`).then(r => r.data),
  create: (data: any) => apiClient.post<ApiResponse<PhysicalAccount>>('/physical-accounts', data).then(r => r.data),
  update: (id: string, data: any) => apiClient.put<ApiResponse<PhysicalAccount>>(`/physical-accounts/${id}`, data).then(r => r.data),
  getBalance: (id: string) => apiClient.get<ApiResponse<any>>(`/physical-accounts/${id}/balance`).then(r => r.data),
  getStats: () => apiClient.get<ApiResponse<any>>('/physical-accounts/stats').then(r => r.data),
};

// ============================================================================
// TRANSACTIONS API
// ============================================================================

export interface Transaction {
  id: string;
  referenceNumber: string;
  movementType: 'CREDIT' | 'DEBIT' | 'TRANSFER_IN' | 'TRANSFER_OUT' | 'REVERSAL' | 'SWEEP_IN' | 'SWEEP_OUT' | 'FEE' | 'FEE_CREDIT' | 'TOPUP' | 'WITHDRAWAL' | 'PAYMENT' | 'INTEREST' | 'SETTLEMENT_CREDIT' | 'EXCEPTION_PARK' | 'EXCEPTION_RELEASE' | 'EXCEPTION_CREDIT' | 'EXCEPTION_DEBIT' | 'POBO_DEBIT' | 'ROBO_CREDIT' | 'POOL_CREDIT' | 'POOL_DEBIT' | 'NETTING';
  transactionCategory?: 'INTERNAL_TRANSFER' | 'EXTERNAL_PAYMENT' | 'POBO' | 'ROBO' | 'SWEEP' | 'POOL' | 'FEE' | 'SETTLEMENT' | 'REVERSAL';
  corporateId?: string;
  vaId: string;
  vaNumber?: string;
  vaName?: string;
  amount: number;
  currencyCode: string;
  balanceBefore: number;
  balanceAfter: number;
  status: 'COMPLETED' | 'PENDING' | 'FAILED' | 'REVERSED' | 'CANCELLED';
  description?: string;
  channel?: string;
  counterpartyName?: string;
  counterpartyAccount?: string;
  counterpartyVaId?: string;
  transactionDate: string;
  valueDate?: string;
  externalReference?: string;
  bancsReference?: string;
  correlationId?: string;
  isPobo?: boolean;
  behalfOfEntity?: string;
  behalfOfVaId?: string;
  feeAmount?: number;
  createdAt: string;
  updatedAt?: string;
}

export interface TransactionDetail extends Transaction {
  corporateName?: string;
  physicalAccountId?: string;
  physicalAccountNumber?: string;
  counterpartyVaNumber?: string;
  remitterName?: string;
  remitterAccount?: string;
  beneficiaryName?: string;
  beneficiaryAccount?: string;
  relatedTransactions?: RelatedTransaction[];
  initiatedBy?: string;
  approvedBy?: string;
}

export interface RelatedTransaction {
  id: string;
  referenceNumber: string;
  movementType: string;
  amount: number;
  vaNumber: string;
  vaName: string;
}

export interface AccountingEntry {
  id: string;
  transactionId: string;
  entryType: 'DEBIT' | 'CREDIT';
  accountType: string;
  accountNumber: string;
  accountName: string;
  amount: number;
  currencyCode: string;
  balanceBefore?: number;
  balanceAfter?: number;
  glCode?: string;
  glDescription?: string;
  costCenter?: string;
  postingDate: string;
  valueDate?: string;
  narration?: string;
  status: string;
}

export interface TransactionListResponse {
  content: Transaction[];
  page: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
  summary?: TransactionSummary;
}

export interface TransactionSummary {
  totalCredits: number;
  totalDebits: number;
  netFlow: number;
  creditCount: number;
  debitCount: number;
}

export interface TransactionStats {
  totalCredits: number;
  totalDebits: number;
  netFlow: number;
  todayCredits: number;
  todayDebits: number;
  todayNetFlow: number;
  totalCount: number;
  todayCount: number;
  pendingCount: number;
  failedCount: number;
  completedCount: number;
  swiftCount: number;
  rtgsCount: number;
  internalCount: number;
  averageAmount: number;
  largestTransaction: number;
}

// ============================================================================
// GROUPED TRANSACTIONS (Option B - Simplified Business View)
// ============================================================================

/**
 * Grouped Transaction - Represents a single business operation.
 * Multi-leg accounting entries are grouped into one line item showing net effect.
 */
export interface GroupedTransaction {
  // Primary identification
  correlationId: string;
  primaryReferenceNumber: string;
  primaryTransactionId: string;

  // Business operation type
  operationType: 'COLLECTION' | 'PAYMENT' | 'POBO_PAYMENT' | 'TRANSFER' | 'FEE' | 'OTHER';
  direction: 'INBOUND' | 'OUTBOUND' | 'INTERNAL';

  // User's account info
  userVaId: string;
  userVaNumber: string;
  userVaName: string;

  // Net effect to user's account
  netAmount: number;
  grossAmount: number;
  feeAmount: number;
  currencyCode: string;
  isCredit: boolean;

  // Balance changes on user's account
  balanceBefore: number;
  balanceAfter: number;

  // Counterparty info
  counterpartyName?: string;
  counterpartyAccount?: string;
  counterpartyType?: 'EXTERNAL' | 'INTERNAL_VA' | 'CBS';

  // Additional context
  description?: string;
  channel?: string;
  status: string;

  // Dates
  transactionDate: string;
  valueDate?: string;

  // VIBAN/ROBO specific
  viban?: string;
  matchStatus?: 'AUTO' | 'PARTIAL' | 'MANUAL' | 'UNMATCHED';
  matchedReceivableId?: string;

  // POBO specific
  isPobo?: boolean;
  behalfOfEntity?: string;
  behalfOfVaId?: string;

  // External references
  externalReference?: string;
  bankReference?: string;

  // Accounting entries (expandable detail)
  entryCount: number;
  accountingEntries?: AccountingEntryDetail[];
}

/**
 * Accounting Entry Detail - Individual leg in a multi-leg transaction.
 */
export interface AccountingEntryDetail {
  legNumber: number;
  transactionId: string;
  referenceNumber: string;

  // Account affected
  vaId: string;
  vaNumber: string;
  vaName: string;
  accountType: 'SHADOW' | 'SETTLEMENT' | 'OPERATING' | 'EXCEPTION' | 'PHYSICAL_MIRROR';

  // Entry details
  movementType: string;
  amount: number;
  currencyCode: string;

  // Balance effect
  balanceBefore: number;
  balanceAfter: number;

  // Description
  description?: string;
  processingNotes?: string;

  // Timestamp
  transactionDate: string;
}

/**
 * Grouped Transaction List Response - Paginated list.
 */
export interface GroupedTransactionListResponse {
  content: GroupedTransaction[];
  page: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
  summary?: GroupedTransactionSummary;
}

/**
 * Summary stats for grouped transactions.
 */
export interface GroupedTransactionSummary {
  totalCredits: number;
  totalDebits: number;
  totalFees: number;
  netFlow: number;
  creditCount: number;
  debitCount: number;
  collectionCount: number;
  paymentCount: number;
  poboCount: number;
}

/**
 * Parameters for grouped transaction queries.
 */
export interface GroupedTransactionParams {
  vaId?: string;
  corporateId?: string;
  direction?: 'INBOUND' | 'OUTBOUND' | 'ALL';
  page?: number;
  pageSize?: number;
}

export interface CreditRequest {
  vaId: string;
  amount: number;
  valueDate?: string;
  description: string;
  channel: string;
  remitterName: string;
  remitterAccount?: string;
  externalReference?: string;
}

export interface DebitRequest {
  vaId: string;
  amount: number;
  valueDate?: string;
  description: string;
  channel: string;
  beneficiaryName: string;
  beneficiaryAccount?: string;
  externalReference?: string;
}

export interface TransferRequest {
  fromVaId: string;
  toVaId: string;
  amount: number;
  description: string;
  valueDate?: string;  // Optional: defaults to today if not provided
}

export interface BulkTransferRequest {
  sourceVaId: string;
  transfers: TransferItem[];
  description?: string;
}

export interface TransferItem {
  destinationVaId: string;
  amount: number;
  description?: string;
  valueDate?: string;  // Optional: defaults to today if not provided
}

export interface BulkTransferResponse {
  totalCount: number;
  successCount: number;
  failedCount: number;
  totalAmount: number;
  results: TransferResult[];
}

export interface TransferResult {
  destinationVaId: string;
  success: boolean;
  transactionId?: string;
  transactionReference?: string;
  errorMessage?: string;
}

// Preview response types for showing fees before execution
export interface FeeLineItem {
  chargeCode: string;
  chargeName: string;
  amount: number;
  currency: string;
  waived: boolean;
  waiverReason?: string;
  feeOwner?: string;
}

export interface FeeBreakdown {
  totalFee: number;
  lineItems: FeeLineItem[];
}

export interface TransferPreviewResponse {
  fromVaId: string;
  fromVaNumber: string;
  fromVaName: string;
  fromBalance: number;
  // Credit Limit Fields
  creditLimit?: number;
  creditLimitUtilized?: number;
  creditLimitAvailable?: number;
  effectiveAvailableBalance?: number;  // fromBalance + creditLimitAvailable
  toVaId: string;
  toVaNumber: string;
  toVaName: string;
  amount: number;
  currency: string;
  sameProgram: boolean;
  intercompany: boolean;
  crossBorder: boolean;
  requiresFx: boolean;
  feeBreakdown: FeeBreakdown;
  totalDebit: number;
  fundsAvailable: boolean;
  validationMessage?: string;
}

export interface PaymentPreviewResponse {
  fromVaId: string;
  fromVaNumber: string;
  fromVaName: string;
  fromBalance: number;
  // Credit Limit Fields
  creditLimit?: number;
  creditLimitUtilized?: number;
  creditLimitAvailable?: number;
  effectiveAvailableBalance?: number;  // fromBalance + creditLimitAvailable
  shadowVaId?: string;
  shadowVaNumber?: string;
  physicalAccountNumber?: string;
  amount: number;
  currency: string;
  channel?: string;
  paymentFee: number;
  totalDebit: number;
  fundsAvailable: boolean;
  shadowVaAvailable: boolean;
  validationMessage?: string;
}

export interface TransactionSearchParams {
  query?: string;
  movementType?: string;
  status?: string;
  channel?: string;
  vaId?: string;
  dateFrom?: string;
  dateTo?: string;
  amountMin?: number;
  amountMax?: number;
  page?: number;
  pageSize?: number;
  sortBy?: string;
  sortOrder?: 'asc' | 'desc';
}

export const transactionsApi = {
  // List & Search
  getAll: (params: TransactionSearchParams = {}) =>
    apiClient.get<ApiResponse<TransactionListResponse>>('/transactions', { params }).then(r => r.data),

  getById: (id: string) =>
    apiClient.get<ApiResponse<TransactionDetail>>(`/transactions/${id}`).then(r => r.data),

  getByReference: (ref: string) =>
    apiClient.get<ApiResponse<TransactionDetail>>(`/transactions/reference/${ref}`).then(r => r.data),

  getRecent: (limit = 10, corporateId?: string) =>
    apiClient.get<ApiResponse<Transaction[]>>('/transactions/recent', {
      params: { limit },
      headers: corporateId ? { 'X-Corporate-Id': corporateId } : undefined
    }).then(r => r.data),

  getStats: (corporateId?: string) =>
    apiClient.get<ApiResponse<TransactionStats>>('/transactions/stats', {
      headers: corporateId ? { 'X-Corporate-Id': corporateId } : undefined
    }).then(r => r.data),

  // VA-specific
  getByVaId: (vaId: string, page = 0, pageSize = 20) =>
    apiClient.get<ApiResponse<TransactionListResponse>>(`/transactions/va/${vaId}`, {
      params: { page, pageSize }
    }).then(r => r.data),

  getRecentByVaId: (vaId: string, limit = 10) =>
    apiClient.get<ApiResponse<Transaction[]>>(`/transactions/va/${vaId}/recent`, {
      params: { limit }
    }).then(r => r.data),

  // Operations
  credit: (data: CreditRequest) =>
    apiClient.post<ApiResponse<Transaction>>('/transactions/credit', data).then(r => r.data),

  debit: (data: DebitRequest) =>
    apiClient.post<ApiResponse<Transaction>>('/transactions/debit', data).then(r => r.data),

  transfer: (data: TransferRequest) =>
    apiClient.post<ApiResponse<Transaction>>('/transactions/transfer', data).then(r => r.data),

  // Preview APIs - show fees before execution
  previewTransfer: (data: { fromVaId: string; toVaId: string; amount: number }) =>
    apiClient.post<ApiResponse<TransferPreviewResponse>>('/transactions/transfer/preview', data).then(r => r.data),

  previewPayment: (data: { fromVaId: string; amount: number; channel?: string }) =>
    apiClient.post<ApiResponse<PaymentPreviewResponse>>('/transactions/payment/preview', data).then(r => r.data),

  bulkTransfer: (data: BulkTransferRequest) =>
    apiClient.post<ApiResponse<BulkTransferResponse>>('/transactions/bulk-transfer', data).then(r => r.data),

  reverse: (id: string, reason?: string) =>
    apiClient.post<ApiResponse<Transaction>>(`/transactions/${id}/reverse`, { reason }).then(r => r.data),

  // Accounting entries for a transaction
  getAccountingEntries: (id: string) =>
    apiClient.get<ApiResponse<AccountingEntry[]>>(`/transactions/${id}/accounting-entries`).then(r => r.data),

  // ISO 20022 message for a transaction (if applicable)
  getIsoMessage: (id: string) =>
    apiClient.get<ApiResponse<{ messageType: string; xml: string; messageId: string }>>(`/transactions/${id}/iso-message`).then(r => r.data),

  // ========================================================================
  // GROUPED TRANSACTIONS (Business View - Option B)
  // ========================================================================

  /**
   * Get transactions grouped by business operation.
   * Multi-leg transactions appear as single line items showing net effect.
   */
  getGrouped: (params: GroupedTransactionParams = {}) =>
    apiClient.get<ApiResponse<GroupedTransactionListResponse>>('/transactions/grouped', { params }).then(r => r.data),

  /**
   * Get grouped transactions for a specific VA.
   */
  getGroupedByVaId: (vaId: string, params: Omit<GroupedTransactionParams, 'vaId'> = {}) =>
    apiClient.get<ApiResponse<GroupedTransactionListResponse>>(`/transactions/va/${vaId}/grouped`, { params }).then(r => r.data),

  /**
   * Get a single grouped transaction with all accounting entries.
   */
  getGroupedByCorrelationId: (correlationId: string, userVaId?: string) =>
    apiClient.get<ApiResponse<GroupedTransaction>>(`/transactions/grouped/${correlationId}`, {
      params: userVaId ? { userVaId } : undefined
    }).then(r => r.data),
};

// ============================================================================
// BENEFICIARIES API
// ============================================================================

export interface Beneficiary {
  id: string;
  corporateId: string;
  beneficiaryName: string;
  beneficiaryType?: string;
  bankName?: string;
  swiftCode?: string;
  accountNumber?: string;
  iban?: string;
  currencyCode: string;
  countryCode?: string;
  status: string;
  validationStatus?: string;
  createdAt: string;
}

export const beneficiariesApi = {
  getAll: (page = 0, size = 20, corporateId?: string) => 
    apiClient.get<ApiResponse<Beneficiary[]>>('/beneficiaries', { params: { page, size, corporateId } }).then(r => r.data),
  getById: (id: string) => apiClient.get<ApiResponse<Beneficiary>>(`/beneficiaries/${id}`).then(r => r.data),
  create: (data: any) => apiClient.post<ApiResponse<Beneficiary>>('/beneficiaries', data).then(r => r.data),
  update: (id: string, data: any) => apiClient.put<ApiResponse<Beneficiary>>(`/beneficiaries/${id}`, data).then(r => r.data),
  delete: (id: string) => apiClient.delete<ApiResponse<void>>(`/beneficiaries/${id}`).then(r => r.data),
  verify: (id: string) => apiClient.post<ApiResponse<Beneficiary>>(`/beneficiaries/${id}/verify`).then(r => r.data),
  getStats: () => apiClient.get<ApiResponse<any>>('/beneficiaries/stats').then(r => r.data),
};

// ============================================================================
// STATEMENTS API (ISO20022 Enhanced)
// ============================================================================

import type {
  ISO20022Statement,
  StatementRequest,
  StatementGenerationResponse,
  StatementHistoryItem,
  ISO20022StatementFormat,
  VAHierarchyNode,
} from '../types';

export const statementsApi = {
  // Basic statement endpoints
  getAccountStatement: (accountId: string, fromDate: string, toDate: string) =>
    apiClient.get<ApiResponse<any>>(`/statements/account/${accountId}`, { params: { fromDate, toDate } }).then(r => r.data),
  getCorporateStatement: (corporateId: string, fromDate: string, toDate: string) =>
    apiClient.get<ApiResponse<any>>(`/statements/corporate/${corporateId}`, { params: { fromDate, toDate } }).then(r => r.data),
  generate: (accountId: string, format: string, fromDate: string, toDate: string) =>
    apiClient.get<ApiResponse<any>>('/statements/generate', { params: { accountId, format, fromDate, toDate } }).then(r => r.data),
  getHistory: (accountId?: string, page = 0, size = 20) =>
    apiClient.get<ApiResponse<any[]>>('/statements/history', { params: { accountId, page, size } }).then(r => r.data),
  getTypes: () => apiClient.get<ApiResponse<any[]>>('/statements/types').then(r => r.data),

  // ISO20022 Enhanced Statement Endpoints

  /**
   * Get ISO20022 compliant statement (CAMT.053 format internally)
   */
  getISO20022Statement: (request: StatementRequest) =>
    apiClient.get<ApiResponse<ISO20022Statement>>(`/statements/iso20022/${request.accountId}`, {
      params: {
        fromDate: request.fromDate,
        toDate: request.toDate,
        includeChildAccounts: request.includeChildAccounts,
        page: request.page,
        pageSize: request.pageSize,
      },
    }).then(r => r.data),

  /**
   * Generate and download statement in specified format
   */
  generateISO20022Statement: (
    accountId: string,
    format: ISO20022StatementFormat,
    fromDate: string,
    toDate: string,
    includeChildAccounts = false
  ) =>
    apiClient.post<ApiResponse<StatementGenerationResponse>>('/statements/iso20022/generate', {
      accountId,
      format,
      fromDate,
      toDate,
      includeChildAccounts,
    }).then(r => r.data),

  /**
   * Download a previously generated statement
   */
  downloadStatement: (statementReference: string) =>
    apiClient.get(`/statements/download/${statementReference}`, {
      responseType: 'blob',
    }).then(response => {
      const url = window.URL.createObjectURL(new Blob([response.data]));
      const link = document.createElement('a');
      link.href = url;
      const contentDisposition = response.headers['content-disposition'];
      const filename = contentDisposition
        ? contentDisposition.split('filename=')[1]?.replace(/"/g, '')
        : `statement_${statementReference}.pdf`;
      link.setAttribute('download', filename);
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.URL.revokeObjectURL(url);
    }),

  /**
   * Get statement history with ISO20022 metadata
   */
  getISO20022History: (params?: {
    accountId?: string;
    corporateId?: string;
    format?: ISO20022StatementFormat;
    page?: number;
    size?: number;
  }) =>
    apiClient.get<ApiResponse<{
      items: StatementHistoryItem[];
      totalCount: number;
      page: number;
      pageSize: number;
    }>>('/statements/iso20022/history', { params }).then(r => r.data),

  /**
   * Get VA hierarchy for aggregation accounts
   */
  getAccountHierarchy: (accountId: string) =>
    apiClient.get<ApiResponse<VAHierarchyNode>>(`/statements/hierarchy/${accountId}`).then(r => r.data),

  /**
   * Preview statement summary without full entries
   */
  previewStatement: (accountId: string, fromDate: string, toDate: string, includeChildAccounts = false) =>
    apiClient.get<ApiResponse<{
      summary: {
        openingBalance: number;
        closingBalance: number;
        totalCredits: number;
        totalDebits: number;
        creditCount: number;
        debitCount: number;
        currency: string;
      };
      account: {
        vaNumber: string;
        accountName: string;
        isAggregation: boolean;
        childAccountCount: number;
      };
      period: {
        fromDate: string;
        toDate: string;
      };
    }>>(`/statements/preview/${accountId}`, {
      params: { fromDate, toDate, includeChildAccounts },
    }).then(r => r.data),

  /**
   * Get available statement formats
   */
  getAvailableFormats: () =>
    apiClient.get<ApiResponse<Array<{
      format: ISO20022StatementFormat;
      label: string;
      description: string;
      fileExtension: string;
    }>>>('/statements/formats').then(r => r.data),

  // ========================================================================
  // ISO20022 camt.053 - Bank to Customer Statement (NEW ENDPOINTS)
  // ========================================================================

  /**
   * Get camt.053 statement for a virtual account (JSON format)
   * @param vaId Virtual account ID
   * @param fromDate Start date (YYYY-MM-DD)
   * @param toDate End date (YYYY-MM-DD)
   */
  getCamt053Statement: (vaId: string, fromDate: string, toDate: string) =>
    apiClient.get<ApiResponse<any>>(`/iso20022/camt053/va/${vaId}`, {
      params: { fromDate, toDate, format: 'json' }
    }).then(r => r.data),

  /**
   * Download camt.053 statement as XML file
   * @param vaId Virtual account ID
   * @param fromDate Start date (YYYY-MM-DD)
   * @param toDate End date (YYYY-MM-DD)
   */
  downloadCamt053Xml: async (vaId: string, fromDate: string, toDate: string): Promise<Blob> => {
    const response = await apiClient.get(`/iso20022/camt053/va/${vaId}/xml`, {
      params: { fromDate, toDate },
      responseType: 'blob',
      headers: { 'Accept': 'application/xml' }
    });
    return response.data;
  },

  /**
   * Get aggregated camt.053 statement for parent VA including children
   * @param vaId Virtual account ID (typically an aggregation account)
   * @param fromDate Start date (YYYY-MM-DD)
   * @param toDate End date (YYYY-MM-DD)
   * @param includeChildren Whether to include child VA transactions
   */
  getAggregatedCamt053Statement: (vaId: string, fromDate: string, toDate: string, includeChildren: boolean = true) =>
    apiClient.get<ApiResponse<any>>(`/iso20022/camt053/va/${vaId}/aggregated`, {
      params: { fromDate, toDate, includeChildren }
    }).then(r => r.data),

  // ========================================================================
  // ISO20022 camt.054 - Bank to Customer Debit/Credit Notification
  // ========================================================================

  /**
   * Get camt.054 notifications for a virtual account
   * Real-time credit/debit notifications
   * @param vaId Virtual account ID
   * @param fromDate Start date (YYYY-MM-DD)
   * @param toDate End date (YYYY-MM-DD)
   */
  getCamt054Notifications: (vaId: string, fromDate: string, toDate: string) =>
    apiClient.get<ApiResponse<any>>(`/iso20022/camt054/va/${vaId}`, {
      params: { fromDate, toDate }
    }).then(r => r.data),

  // ========================================================================
  // VA Hierarchy & Balance Aggregation (NEW ENDPOINTS)
  // ========================================================================

  /**
   * Get VA hierarchy tree for a given account
   * @param vaId Virtual account ID (root or any node)
   */
  getVaHierarchy: (vaId: string) =>
    apiClient.get<ApiResponse<any>>(`/statements/va/${vaId}/hierarchy`).then(r => r.data),

  /**
   * Get aggregated balance for a VA including all children
   * @param vaId Virtual account ID
   * @param asOfDate Optional date for historical balance (default: current)
   */
  getVaAggregatedBalance: (vaId: string, asOfDate?: string) =>
    apiClient.get<ApiResponse<any>>(`/statements/va/${vaId}/balance`, {
      params: asOfDate ? { asOfDate } : {}
    }).then(r => r.data),
};

// ============================================================================
// SWEEPING (CASH CONCENTRATION) API
// ============================================================================

export interface SweepRuleSourceAccount {
  id: string;
  accountId: string;
  accountNumber: string;
  entityCode: string;
  entityName: string;
  currencyCode: string;
  bankName?: string;
  balance?: number;
}

export interface SweepRule {
  id: string;
  ruleReference: string;
  ruleName: string;
  sweepType: string;
  frequency: string;
  executionTime?: string;
  // Target account details
  targetAccountId: string;
  targetAccountNumber?: string;
  targetEntityCode?: string;
  // Owning corporate / program (V9). Used by Cash Concentration's
  // corporate picker to filter rules; absent on legacy/seed rules.
  corporateId?: string;
  programId?: string;
  // Type-specific parameters
  targetAmount?: number;
  thresholdMin?: number;
  thresholdMax?: number;
  percentage?: number;
  // Configuration
  currencyCode?: string;
  priority?: number;
  // Status & stats
  status: string;
  totalSwept?: number;
  executionCount?: number;
  lastExecution?: string;
  nextExecution?: string;
  // Source accounts
  sourceAccounts?: SweepRuleSourceAccount[];
  // Timestamps
  createdAt: string;
  updatedAt?: string;
}

export interface SweepExecution {
  id: string;
  executionReference: string;
  ruleId: string;
  ruleName?: string;
  ruleReference?: string;
  sweepType?: string;
  // Source account details
  sourceAccountId?: string;
  sourceAccountNumber?: string;
  sourceEntityCode?: string;
  // Target account details
  targetAccountId?: string;
  targetAccountNumber?: string;
  targetEntityCode?: string;
  // Financial data
  sweepAmount: number;
  currencyCode?: string;
  balanceBefore?: number;
  balanceAfter?: number;
  // Status & tracking
  status: string;
  errorMessage?: string;
  skipReason?: string;
  executionTime: string;
  completedAt?: string;
  // BANCS integration
  bancsTransactionRef?: string;
  bancsSyncStatus?: string;
  // IHB integration
  ihbEnabled?: boolean;
  ihbDepositId?: string;
  ihbLoanId?: string;
  ihbDepositReference?: string;
  ihbInterestRate?: number;
}

export interface SweepRunStatus {
  runId: string;
  status: 'RUNNING' | 'COMPLETED' | 'FAILED';
  startedAt?: string;
  sourcesTotal: number;
  sourcesProcessed: number;
  successCount: number;
  failedCount: number;
}

export const sweepingApi = {
  getAllRules: () => apiClient.get<ApiResponse<SweepRule[]>>('/sweeping/rules').then(r => r.data),
  getActiveRules: () => apiClient.get<ApiResponse<SweepRule[]>>('/sweeping/rules/active').then(r => r.data),
  getRuleById: (id: string) => apiClient.get<ApiResponse<SweepRule>>(`/sweeping/rules/${id}`).then(r => r.data),
  createRule: (data: any) => apiClient.post<ApiResponse<SweepRule>>('/sweeping/rules', data).then(r => r.data),
  updateRule: (id: string, data: any) => apiClient.put<ApiResponse<SweepRule>>(`/sweeping/rules/${id}`, data).then(r => r.data),
  deleteRule: (id: string) => apiClient.delete<ApiResponse<void>>(`/sweeping/rules/${id}`).then(r => r.data),
  toggleRule: (id: string) => apiClient.post<ApiResponse<SweepRule>>(`/sweeping/rules/${id}/toggle`).then(r => r.data),
  execute: (ruleIds?: string[]) => apiClient.post<ApiResponse<any>>('/sweeping/execute', { ruleIds }).then(r => r.data),
  executeAsync: (ruleIds?: string[]) => apiClient.post<ApiResponse<{ runId: string }>>('/sweeping/execute-async', { ruleIds }).then(r => r.data),
  getRunStatus: (runId: string) => apiClient.get<ApiResponse<SweepRunStatus>>(`/sweeping/runs/${runId}`).then(r => r.data),
  getHistory: (ruleId?: string) => apiClient.get<ApiResponse<SweepExecution[]>>('/sweeping/history', { params: { ruleId } }).then(r => r.data),
};

// ============================================================================
// NOTIONAL POOLING API
// ============================================================================

export interface NotionalPool {
  id: string;
  poolReference: string;
  poolName: string;
  poolCurrency: string;
  // Owning corporate / program (V11). Used by the Notional Pooling
  // corporate picker to filter pools; absent on legacy pools until V12
  // backfills them from their members' accounts.
  corporateId?: string;
  programId?: string;
  targetBalance?: number;
  interestRate: number;
  interestCalculationMethod: 'DAILY_AVERAGE' | 'MONTH_END' | 'TIER_BASED';
  totalBalance: number;
  interestSavingsYtd: number;
  memberCount: number;
  status: 'ACTIVE' | 'SUSPENDED' | 'CLOSED';
  effectiveFrom?: string;
  effectiveTo?: string;
  lastCalculationDate?: string;
  members?: PoolMember[];
  createdAt: string;
  updatedAt?: string;
}

export interface PoolMember {
  id: string;
  accountId: string;
  accountNumber: string;
  entityCode: string;
  entityName: string;
  currentBalance: number;
  contributionPercent: number;
  interestAllocation: number;
  status: 'ACTIVE' | 'SUSPENDED' | 'REMOVED';
  joinedDate: string;
}

export interface CalculateInterestResponse {
  poolId: string;
  calculationDate: string;
  poolBalance: number;
  interestRate: number;
  grossInterest: number;
  netInterest: number;
  memberAllocations: MemberInterestAllocation[];
}

export interface MemberInterestAllocation {
  memberId: string;
  entityCode: string;
  balance: number;
  contributionPercent: number;
  interestAllocation: number;
}

export interface CreatePoolRequest {
  poolName: string;
  poolCurrency: string;
  targetBalance?: number;
  interestRate: number;
  interestCalculationMethod: 'DAILY_AVERAGE' | 'MONTH_END' | 'TIER_BASED';
  effectiveFrom?: string;
  effectiveTo?: string;
  members?: AddMemberRequest[];
}

export interface AddMemberRequest {
  accountId: string;
  accountNumber: string;
  entityCode: string;
  entityName: string;
}

export interface BulkAddMembersSkip {
  accountId: string;
  reason: string;
}

export interface BulkAddMembersResponse {
  added: number;
  skipped: BulkAddMembersSkip[];
}

export const poolingApi = {
  getAllPools: () => apiClient.get<ApiResponse<NotionalPool[]>>('/pooling').then(r => r.data),
  getActivePools: () => apiClient.get<ApiResponse<NotionalPool[]>>('/pooling/active').then(r => r.data),
  getPoolById: (id: string) => apiClient.get<ApiResponse<NotionalPool>>(`/pooling/${id}`).then(r => r.data),
  createPool: (data: CreatePoolRequest) => apiClient.post<ApiResponse<NotionalPool>>('/pooling', data).then(r => r.data),
  updatePool: (id: string, data: Partial<CreatePoolRequest>) => apiClient.put<ApiResponse<NotionalPool>>(`/pooling/${id}`, data).then(r => r.data),
  deletePool: (id: string) => apiClient.delete<ApiResponse<void>>(`/pooling/${id}`).then(r => r.data),
  addMember: (poolId: string, data: AddMemberRequest) => apiClient.post<ApiResponse<NotionalPool>>(`/pooling/${poolId}/members`, data).then(r => r.data),
  addMembersBulk: (poolId: string, accountIds: string[]) => apiClient.post<ApiResponse<BulkAddMembersResponse>>(`/pooling/${poolId}/members/bulk`, { accountIds }).then(r => r.data),
  removeMember: (poolId: string, memberId: string) => apiClient.delete<ApiResponse<void>>(`/pooling/${poolId}/members/${memberId}`).then(r => r.data),
  calculateInterest: (poolId: string) => apiClient.post<ApiResponse<CalculateInterestResponse>>(`/pooling/${poolId}/calculate`).then(r => r.data),
};

// ============================================================================
// NETTING API
// ============================================================================

export interface NettingEntryResponse {
  id: string;
  entryReference: string;
  flowDirection: 'PAYABLE' | 'RECEIVABLE';
  payerEntityId?: string;
  payerEntityCode: string;
  payerEntityName: string;
  payeeEntityId?: string;
  payeeEntityCode: string;
  payeeEntityName: string;
  grossAmount: number;
  currencyCode: string;
  exchangeRate?: number;
  baseAmount: number;
  originalCurrency?: string;
  originalAmount?: number;
  sourceType: string;
  sourceReference?: string;
  payableId?: string;
  receivableId?: string;
  intercompanyRechargeId?: string;
  ihbTransactionId?: string;
  sourceEntityId?: string;
  sourceEntityCode?: string;
  dueDate?: string;
  status: string;
  createdAt?: string;
}

export interface NettingSettlementResponse {
  id: string;
  entityId: string;
  entityCode: string;
  entityName: string;
  totalPayable: number;
  totalReceivable: number;
  netPosition: number;
  grossPayables?: number;
  grossReceivables?: number;
  payableEntryCount?: number;
  receivableEntryCount?: number;
  settlementDirection: 'PAY' | 'RECEIVE' | 'NEUTRAL';
  settlementAccount?: string;
  settlementVaId?: string;
  settlementTransactionId?: string;
  settlementStatus: string;
  settledAt?: string;
  settlementReference?: string;
}

export interface NettingCycle {
  id: string;
  cycleReference: string;
  cycleName: string;
  baseCurrency: string;
  periodStart: string;
  periodEnd: string;
  totalGross: number;
  totalNet: number;
  savingsAmount: number;
  savingsPercent: number;
  entryCount: number;
  participantCount: number;
  payableEntryCount?: number;
  receivableEntryCount?: number;
  totalPayables?: number;
  totalReceivables?: number;
  status: string;
  approvedBy?: string;
  approvedAt?: string;
  settledAt?: string;
  createdAt: string;
  entries?: NettingEntryResponse[];
  settlements?: NettingSettlementResponse[];
}

export interface PopulateCycleResponse {
  cycleId: string;
  cycleReference: string;
  payablesAdded: number;
  receivablesAdded: number;
  rechargesAdded: number;              // Total recharges (POBO + COBO)
  poboRechargesAdded?: number;         // POBO recharges (subsidiary owes treasury)
  coboRechargesAdded?: number;         // COBO recharges (treasury owes subsidiary)
  ihbTransactionsAdded: number;
  totalAdded: number;
  totalEntries: number;
  totalGross: number;
}

export const nettingApi = {
  getAllCycles: () => apiClient.get<ApiResponse<NettingCycle[]>>('/netting/cycles').then(r => r.data),
  getCycleById: (id: string) => apiClient.get<ApiResponse<NettingCycle>>(`/netting/cycles/${id}`).then(r => r.data),
  createCycle: (data: any) => apiClient.post<ApiResponse<NettingCycle>>('/netting/cycles', data).then(r => r.data),
  populateCycle: (cycleId: string, corporateId?: string, includePending?: boolean) => {
    const params = new URLSearchParams();
    if (corporateId) params.append('corporateId', corporateId);
    if (includePending) params.append('includePending', 'true');
    const queryString = params.toString();
    return apiClient.post<ApiResponse<PopulateCycleResponse>>(`/netting/cycles/${cycleId}/populate${queryString ? `?${queryString}` : ''}`).then(r => r.data);
  },
  addEntry: (cycleId: string, data: any) => apiClient.post<ApiResponse<any>>(`/netting/cycles/${cycleId}/entries`, data).then(r => r.data),
  calculateNetting: (cycleId: string) => apiClient.post<ApiResponse<any>>(`/netting/cycles/${cycleId}/calculate`).then(r => r.data),
  approveCycle: (cycleId: string, approver: string) => apiClient.post<ApiResponse<NettingCycle>>(`/netting/cycles/${cycleId}/approve`, { approver }).then(r => r.data),
  settleCycle: (cycleId: string) => apiClient.post<ApiResponse<NettingCycle>>(`/netting/cycles/${cycleId}/settle`).then(r => r.data),
};

// ============================================================================
// IN-HOUSE BANK API
// ============================================================================

export interface IhbEntity {
  id: string;
  entityCode: string;
  entityName: string;
  entityType: string;
  creditLimit: number;
  currentExposure: number;
  availableLimit: number;
  lendingRateSpread: number;
  borrowingRateSpread: number;
  status: string;
  createdAt: string;
}

export interface IhbLoan {
  id: string;
  loanReference: string;
  lenderId: string;
  borrowerId: string;
  principalAmount: number;
  outstandingAmount: number;
  interestRate: number;
  status: string;
  maturityDate: string;
  createdAt: string;
}

export interface IhbDeposit {
  id: string;
  depositReference: string;
  entityId: string;
  principalAmount: number;
  currentBalance: number;
  interestRate: number;
  accruedInterest: number;
  status: string;
  maturityDate?: string;
  createdAt: string;
}

export const ihbApi = {
  getStats: () => apiClient.get<ApiResponse<any>>('/ihb/stats').then(r => r.data),
  getAllEntities: () => apiClient.get<ApiResponse<IhbEntity[]>>('/ihb/entities').then(r => r.data),
  getEntityById: (id: string) => apiClient.get<ApiResponse<IhbEntity>>(`/ihb/entities/${id}`).then(r => r.data),
  createEntity: (data: any) => apiClient.post<ApiResponse<IhbEntity>>('/ihb/entities', data).then(r => r.data),
  updateEntity: (id: string, data: any) => apiClient.put<ApiResponse<IhbEntity>>(`/ihb/entities/${id}`, data).then(r => r.data),
  getAllLoans: () => apiClient.get<ApiResponse<IhbLoan[]>>('/ihb/loans').then(r => r.data),
  createLoan: (data: any) => apiClient.post<ApiResponse<IhbLoan>>('/ihb/loans', data).then(r => r.data),
  repayLoan: (id: string, amount: number) => apiClient.post<ApiResponse<any>>(`/ihb/loans/${id}/repay`, { amount }).then(r => r.data),
  getAllDeposits: () => apiClient.get<ApiResponse<IhbDeposit[]>>('/ihb/deposits').then(r => r.data),
  createDeposit: (data: any) => apiClient.post<ApiResponse<IhbDeposit>>('/ihb/deposits', data).then(r => r.data),
  withdrawDeposit: (id: string, amount: number) => apiClient.post<ApiResponse<any>>(`/ihb/deposits/${id}/withdraw`, { amount }).then(r => r.data),
};

// ============================================================================
// WALLETS API
// ============================================================================

export const walletsApi = {
  getAll: (page = 0, size = 20, corporateId?: string, walletType?: string) => 
    apiClient.get<ApiResponse<VirtualAccount[]>>('/wallets', { params: { page, size, corporateId, walletType } }).then(r => r.data),
  getById: (id: string) => apiClient.get<ApiResponse<any>>(`/wallets/${id}`).then(r => r.data),
  create: (data: any) => apiClient.post<ApiResponse<VirtualAccount>>('/wallets', data).then(r => r.data),
  topUp: (id: string, amount: number, source?: string) => apiClient.post<ApiResponse<any>>(`/wallets/${id}/topup`, { amount, source }).then(r => r.data),
  withdraw: (id: string, amount: number, destination?: string) => apiClient.post<ApiResponse<any>>(`/wallets/${id}/withdraw`, { amount, destination }).then(r => r.data),
  freeze: (id: string) => apiClient.post<ApiResponse<VirtualAccount>>(`/wallets/${id}/freeze`).then(r => r.data),
  unfreeze: (id: string) => apiClient.post<ApiResponse<VirtualAccount>>(`/wallets/${id}/unfreeze`).then(r => r.data),
  getTypes: () => apiClient.get<ApiResponse<any[]>>('/wallets/types').then(r => r.data),
  getStats: (corporateId?: string) => apiClient.get<ApiResponse<any>>('/wallets/stats', { params: { corporateId } }).then(r => r.data),
};

// ============================================================================
// ESCROW API
// ============================================================================

export const escrowApi = {
  getAll: (page = 0, size = 20, status?: string) => 
    apiClient.get<ApiResponse<any[]>>('/escrow', { params: { page, size, status } }).then(r => r.data),
  getById: (id: string) => apiClient.get<ApiResponse<any>>(`/escrow/${id}`).then(r => r.data),
  create: (data: any) => apiClient.post<ApiResponse<any>>('/escrow', data).then(r => r.data),
  fund: (id: string, amount: number, sourceAccount: string) => apiClient.post<ApiResponse<any>>(`/escrow/${id}/fund`, { amount, sourceAccount }).then(r => r.data),
  release: (id: string, amount: number, releaseTo: string, approvedBy: string) => 
    apiClient.post<ApiResponse<any>>(`/escrow/${id}/release`, { amount, releaseTo, approvedBy }).then(r => r.data),
  dispute: (id: string, reason: string, raisedBy: string) => apiClient.post<ApiResponse<any>>(`/escrow/${id}/dispute`, { reason, raisedBy }).then(r => r.data),
  getStats: () => apiClient.get<ApiResponse<any>>('/escrow/stats').then(r => r.data),
  getTypes: () => apiClient.get<ApiResponse<any[]>>('/escrow/types').then(r => r.data),
};

// ============================================================================
// ECOMMERCE API
// ============================================================================

export const ecommerceApi = {
  // Dashboard
  getDashboardStats: () => apiClient.get<ApiResponse<any>>('/ecommerce/dashboard/stats').then(r => r.data),
  getCollectionTrends: (days = 30) => apiClient.get<ApiResponse<any[]>>('/ecommerce/dashboard/trends', { params: { days } }).then(r => r.data),
  // Merchants
  getMerchants: (page = 0, size = 20, status?: string) => 
    apiClient.get<ApiResponse<any[]>>('/ecommerce/merchants', { params: { page, size, status } }).then(r => r.data),
  getMerchantById: (id: string) => apiClient.get<ApiResponse<any>>(`/ecommerce/merchants/${id}`).then(r => r.data),
  onboardMerchant: (data: any) => apiClient.post<ApiResponse<any>>('/ecommerce/merchants', data).then(r => r.data),
  approveMerchant: (id: string) => apiClient.post<ApiResponse<any>>(`/ecommerce/merchants/${id}/approve`).then(r => r.data),
  // Collections
  getCollections: (page = 0, size = 20, merchantId?: string, status?: string) => 
    apiClient.get<ApiResponse<any[]>>('/ecommerce/collections', { params: { page, size, merchantId, status } }).then(r => r.data),
  getCollectionById: (id: string) => apiClient.get<ApiResponse<any>>(`/ecommerce/collections/${id}`).then(r => r.data),
  // Settlements
  getSettlements: (page = 0, size = 20) => apiClient.get<ApiResponse<any[]>>('/ecommerce/settlements', { params: { page, size } }).then(r => r.data),
  processSettlements: () => apiClient.post<ApiResponse<any>>('/ecommerce/settlements/process').then(r => r.data),
};

// ============================================================================
// RECEIVABLES API
// ============================================================================

export const receivablesApi = {
  getAll: (page = 0, size = 20, corporateId?: string, status?: string) =>
    apiClient.get<ApiResponse<any[]>>('/receivables', { params: { page, size, corporateId, status } }).then(r => r.data),
  getById: (id: string) => apiClient.get<ApiResponse<any>>(`/receivables/${id}`).then(r => r.data),
  create: (data: any, corporateId?: string) => {
    console.log('receivablesApi.create called with corporateId:', corporateId);
    const headers: Record<string, string> = {};
    if (corporateId) {
      headers['X-Corporate-Id'] = corporateId;
    }
    console.log('Request headers:', headers);
    return apiClient.post<ApiResponse<any>>('/receivables/invoices', data, { headers }).then(r => r.data);
  },
  recordPayment: (id: string, data: any) => apiClient.post<ApiResponse<any>>(`/receivables/${id}/record-payment`, data).then(r => r.data),
  getStats: (corporateId?: string) => apiClient.get<ApiResponse<any>>('/receivables/stats', { params: { corporateId } }).then(r => r.data),
};

// ============================================================================
// RECEIVABLES PHASE 3 - COBO, INTERCOMPANY & NETTING
// ============================================================================

// Enums
export type CollectionRoute = 'DIRECT' | 'COBO' | 'INTERCOMPANY' | 'NETTING';
export type CoboRequestStatus = 'NOT_REQUESTED' | 'PENDING_ENTITY_APPROVAL' | 'PENDING_TREASURY_APPROVAL' | 'APPROVED' | 'REJECTED' | 'COLLECTED' | 'FAILED';
export type NettingStatusReceivable = 'NOT_INCLUDED' | 'PENDING' | 'INCLUDED' | 'SETTLED' | 'EXCLUDED';
export type ReceivableStatusPhase3 = 'OPEN' | 'PENDING_COBO' | 'COBO_APPROVED' | 'COBO_REJECTED' | 'PENDING_NETTING' | 'NETTED' | 'PARTIAL' | 'PAID' | 'CANCELLED' | 'WRITTEN_OFF';

// Entity Context
export interface EntityContext {
  entityId: string;
  entityCode: string;
  entityName: string;
}

// Phase 3 Receivable
export interface ReceivablePhase3 {
  id: string;
  receivableNumber: string;
  invoiceNumber?: string;
  corporateId: string;
  programId?: string;
  
  // Customer
  customerName: string;
  customerPartyId?: string;
  customerPartyBankAccountId?: string;
  
  // Entity Context
  owningEntityId?: string;
  owningEntityCode?: string;
  owningEntityName?: string;
  
  // Amounts
  currency: string;
  grossAmount: number;
  netAmount: number;
  taxAmount: number;
  paidAmount: number;
  outstandingAmount: number;
  
  // Dates
  invoiceDate: string;
  dueDate: string;
  receivedDate?: string;
  
  // Status
  status: ReceivableStatusPhase3;
  collectionRoute: CollectionRoute;
  
  // Intercompany
  isIntercompany: boolean;
  intercompanyEntityId?: string;
  intercompanyEntityCode?: string;
  intercompanyEntityName?: string;
  counterpartyPayableId?: string;
  
  // COBO
  isCobo: boolean;
  coboCollectorEntityId?: string;
  coboCollectorEntityCode?: string;
  coboCollectorEntityName?: string;
  coboRequestId?: string;
  coboRequestStatus: CoboRequestStatus;
  coboTransactionRef?: string;
  coboRechargeId?: string;
  coboIhbDepositId?: string;
  coboRequestedAt?: string;
  coboRequestedBy?: string;
  coboActionedAt?: string;
  coboActionedBy?: string;
  
  // Netting
  nettingEligible: boolean;
  nettingCycleId?: string;
  nettingCycleRef?: string;
  nettingEntryId?: string;
  nettingStatus: NettingStatusReceivable;
  nettingSettlementRef?: string;
  nettingSettledAt?: string;
  
  // Audit
  createdAt: string;
  createdBy?: string;
  updatedAt?: string;
}

// Search & List
export interface ReceivableSearchParamsPhase3 {
  corporateId?: string;
  page?: number;
  size?: number;
  status?: ReceivableStatusPhase3;
  collectionRoute?: CollectionRoute;
  owningEntityId?: string;
  customerPartyId?: string;
  isIntercompany?: boolean;
  intercompanyEntityId?: string;
  isCobo?: boolean;
  coboRequestStatus?: CoboRequestStatus;
  coboCollectorEntityId?: string;
  nettingEligible?: boolean;
  nettingStatus?: NettingStatusReceivable;
  nettingCycleId?: string;
  dueDateFrom?: string;
  dueDateTo?: string;
  minAmount?: number;
  maxAmount?: number;
  searchTerm?: string;
}

export interface ReceivableListResponsePhase3 {
  content: ReceivablePhase3[];
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
}

// COBO Types
export interface CoboSubmitRequest {
  receivableId: string;
  collectorEntityId: string;
  requestedBy: string;
  notes?: string;
}

export interface CoboApprovalRequest {
  receivableId: string;
  actionedBy: string;
  approved: boolean;
  notes?: string;
}

export interface CoboExecuteRequest {
  receivableId: string;
  executedBy: string;
  paymentReference?: string;
  paymentDate?: string;
}

export interface CoboPreviewResponse {
  receivableId: string;
  receivableNumber: string;
  grossAmount: number;
  currency: string;
  coboFeeAmount: number;
  coboFeeRate: number;
  netToSubsidiary: number;
  collectorEntity: EntityContext;
  owningEntity: EntityContext;
}

export interface CoboStatsResponse {
  pendingApprovalCount: number;
  pendingApprovalAmount: number;
  approvedCount: number;
  approvedAmount: number;
  collectedCount: number;
  collectedAmount: number;
  rejectedCount: number;
  totalFeesCollected: number;
}

// Intercompany Types
export interface CreateIntercompanyReceivableRequest {
  corporateId: string;
  owningEntityId: string;
  intercompanyEntityId: string;
  invoiceNumber?: string;
  currency: string;
  grossAmount: number;
  taxAmount?: number;
  dueDate: string;
  description?: string;
  createdBy: string;
}

export interface IntercompanyBalanceSummary {
  owningEntityId: string;
  owningEntityCode: string;
  owningEntityName: string;
  counterpartyEntityId: string;
  counterpartyEntityCode: string;
  counterpartyEntityName: string;
  currency: string;
  receivablesTotal: number;
  receivablesCount: number;
  payablesTotal: number;
  payablesCount: number;
  netPosition: number; // positive = they owe us
}

// Netting Types
export interface NettingAddRequest {
  receivableId: string;
  nettingCycleId: string;
  addedBy: string;
}

export interface NettingAddResponse {
  receivableId: string;
  nettingCycleId: string;
  nettingEntryId: string;
  success: boolean;
  message?: string;
}

export interface NettingEligibleReceivable {
  receivable: ReceivablePhase3;
  intercompanyEntityId: string;
  intercompanyEntityCode: string;
  intercompanyEntityName: string;
  matchingPayables?: number;
  potentialNetAmount?: number;
}

export interface NettingSummaryResponse {
  corporateId: string;
  eligibleCount: number;
  eligibleAmount: number;
  includedCount: number;
  includedAmount: number;
  settledCount: number;
  settledAmount: number;
  totalSavings: number;
}

// Phase 3 Stats
export interface ReceivableStatsPhase3 {
  // Basic
  totalReceivables: number;
  openCount: number;
  openAmount: number;
  overdueCount: number;
  overdueAmount: number;
  paidThisMonth: number;
  
  // COBO
  pendingCoboApproval: number;
  pendingCoboAmount: number;
  coboApproved: number;
  coboApprovedAmount: number;
  coboCollected: number;
  coboCollectedAmount: number;
  
  // Intercompany
  intercompanyCount: number;
  intercompanyAmount: number;
  
  // Netting
  nettingEligibleCount: number;
  nettingEligibleAmount: number;
  nettingIncludedCount: number;
  nettingIncludedAmount: number;
  nettingSettledCount: number;
  nettingSettledAmount: number;
}

// Helper function
const extractReceivableData = <T>(response: ApiResponse<T>): T => {
  if (response && response.data !== undefined) {
    return response.data;
  }
  return response as unknown as T;
};

// Phase 3 API Client
export const receivablesApiPhase3 = {
  // =========================================================================
  // CRUD & SEARCH
  // =========================================================================
  
  getStats: async (corporateId: string, owningEntityId?: string): Promise<ReceivableStatsPhase3> => {
    const params: Record<string, string> = {};
    if (owningEntityId) params.owningEntityId = owningEntityId;
    const response = await apiClient.get<ApiResponse<ReceivableStatsPhase3>>('/receivables/stats/phase3', {
      headers: { 'X-Corporate-Id': corporateId },
      params
    });
    return extractReceivableData(response.data);
  },
  
  search: async (params: ReceivableSearchParamsPhase3): Promise<ReceivableListResponsePhase3> => {
    const { corporateId, ...queryParams } = params;
    const headers: Record<string, string> = {};
    if (corporateId) headers['X-Corporate-Id'] = corporateId;
    const response = await apiClient.get<ApiResponse<ReceivableListResponsePhase3>>('/receivables/search', {
      headers,
      params: queryParams
    });
    return extractReceivableData(response.data);
  },
  
  getById: async (receivableId: string): Promise<ReceivablePhase3> => {
    const response = await apiClient.get<ApiResponse<ReceivablePhase3>>(`/receivables/${receivableId}`);
    return extractReceivableData(response.data);
  },
  
  getByEntity: async (owningEntityId: string, page = 0, size = 20): Promise<ReceivableListResponsePhase3> => {
    const response = await apiClient.get<ApiResponse<ReceivableListResponsePhase3>>(`/receivables/by-entity/${owningEntityId}`, {
      params: { page, size }
    });
    return extractReceivableData(response.data);
  },
  
  getByParty: async (partyId: string, page = 0, size = 20): Promise<ReceivableListResponsePhase3> => {
    const response = await apiClient.get<ApiResponse<ReceivableListResponsePhase3>>(`/receivables/by-party/${partyId}`, {
      params: { page, size }
    });
    return extractReceivableData(response.data);
  },
  
  getByRoute: async (collectionRoute: CollectionRoute, corporateId?: string, page = 0, size = 20): Promise<ReceivableListResponsePhase3> => {
    const params: Record<string, any> = { route: collectionRoute, page, size };
    const headers: Record<string, string> = {};
    if (corporateId) headers['X-Corporate-Id'] = corporateId;
    const response = await apiClient.get<ApiResponse<ReceivableListResponsePhase3>>('/receivables/by-route', {
      headers,
      params
    });
    return extractReceivableData(response.data);
  },
  
  // =========================================================================
  // COBO WORKFLOW
  // =========================================================================
  
  submitForCobo: async (request: CoboSubmitRequest): Promise<ReceivablePhase3> => {
    const response = await apiClient.post<ApiResponse<ReceivablePhase3>>(
      `/receivables/${request.receivableId}/cobo/submit`,
      request
    );
    return extractReceivableData(response.data);
  },
  
  approveCobo: async (receivableId: string, actionedBy: string, notes?: string): Promise<ReceivablePhase3> => {
    const response = await apiClient.post<ApiResponse<ReceivablePhase3>>(
      `/receivables/${receivableId}/cobo/approve`,
      { actionedBy, approved: true, notes }
    );
    return extractReceivableData(response.data);
  },
  
  rejectCobo: async (receivableId: string, actionedBy: string, reason: string): Promise<ReceivablePhase3> => {
    const response = await apiClient.post<ApiResponse<ReceivablePhase3>>(
      `/receivables/${receivableId}/cobo/reject`,
      { actionedBy, approved: false, notes: reason }
    );
    return extractReceivableData(response.data);
  },
  
  executeCoboCollection: async (request: CoboExecuteRequest): Promise<ReceivablePhase3> => {
    const response = await apiClient.post<ApiResponse<ReceivablePhase3>>(
      `/receivables/${request.receivableId}/cobo/collect`,
      request
    );
    return extractReceivableData(response.data);
  },
  
  previewCobo: async (receivableId: string): Promise<CoboPreviewResponse> => {
    const response = await apiClient.get<ApiResponse<CoboPreviewResponse>>(
      `/receivables/${receivableId}/cobo/preview`
    );
    return extractReceivableData(response.data);
  },
  
  getCoboPendingApproval: async (corporateId?: string): Promise<ReceivablePhase3[]> => {
    const headers: Record<string, string> = {};
    if (corporateId) headers['X-Corporate-Id'] = corporateId;
    const response = await apiClient.get<ApiResponse<ReceivablePhase3[]>>('/receivables/cobo/pending-approval', {
      headers
    });
    return extractReceivableData(response.data);
  },
  
  getCoboStats: async (corporateId: string): Promise<CoboStatsResponse> => {
    const response = await apiClient.get<ApiResponse<CoboStatsResponse>>('/receivables/cobo/stats', {
      headers: { 'X-Corporate-Id': corporateId }
    });
    return extractReceivableData(response.data);
  },
  
  // =========================================================================
  // INTERCOMPANY
  // =========================================================================
  
  createIntercompanyReceivable: async (request: CreateIntercompanyReceivableRequest): Promise<ReceivablePhase3> => {
    const { corporateId, ...body } = request;
    const response = await apiClient.post<ApiResponse<ReceivablePhase3>>('/receivables/intercompany', body, {
      headers: { 'X-Corporate-Id': corporateId }
    });
    return extractReceivableData(response.data);
  },
  
  getIntercompanyReceivables: async (corporateId: string, owningEntityId?: string, page = 0, size = 20): Promise<ReceivableListResponsePhase3> => {
    const params: Record<string, any> = { page, size };
    if (owningEntityId) params.owningEntityId = owningEntityId;
    const response = await apiClient.get<ApiResponse<ReceivableListResponsePhase3>>('/receivables/intercompany', {
      headers: { 'X-Corporate-Id': corporateId },
      params
    });
    return extractReceivableData(response.data);
  },
  
  getIntercompanyBalance: async (corporateId: string, owningEntityId?: string): Promise<IntercompanyBalanceSummary[]> => {
    const params: Record<string, any> = {};
    if (owningEntityId) params.owningEntityId = owningEntityId;
    const response = await apiClient.get<ApiResponse<IntercompanyBalanceSummary[]>>('/receivables/intercompany/balance', {
      headers: { 'X-Corporate-Id': corporateId },
      params
    });
    return extractReceivableData(response.data);
  },
  
  getIntercompanyBetweenEntities: async (entity1Id: string, entity2Id: string): Promise<ReceivablePhase3[]> => {
    const response = await apiClient.get<ApiResponse<ReceivablePhase3[]>>('/receivables/intercompany/between', {
      params: { entity1Id, entity2Id }
    });
    return extractReceivableData(response.data);
  },
  
  linkCounterpartyPayable: async (receivableId: string, payableId: string): Promise<ReceivablePhase3> => {
    const response = await apiClient.post<ApiResponse<ReceivablePhase3>>(
      `/receivables/${receivableId}/link-payable/${payableId}`
    );
    return extractReceivableData(response.data);
  },
  
  // =========================================================================
  // NETTING
  // =========================================================================
  
  getNettingEligible: async (corporateId: string, owningEntityId?: string): Promise<NettingEligibleReceivable[]> => {
    const params: Record<string, any> = {};
    if (owningEntityId) params.owningEntityId = owningEntityId;
    const response = await apiClient.get<ApiResponse<NettingEligibleReceivable[]>>('/receivables/netting/eligible', {
      headers: { 'X-Corporate-Id': corporateId },
      params
    });
    return extractReceivableData(response.data);
  },
  
  addToNettingCycle: async (request: NettingAddRequest): Promise<NettingAddResponse> => {
    const response = await apiClient.post<ApiResponse<NettingAddResponse>>(
      `/receivables/${request.receivableId}/netting/add`,
      request
    );
    return extractReceivableData(response.data);
  },
  
  removeFromNettingCycle: async (receivableId: string, removedBy: string, reason?: string): Promise<ReceivablePhase3> => {
    const response = await apiClient.post<ApiResponse<ReceivablePhase3>>(
      `/receivables/${receivableId}/netting/remove`,
      { removedBy, reason }
    );
    return extractReceivableData(response.data);
  },
  
  getReceivablesInCycle: async (cycleId: string, page = 0, size = 50): Promise<ReceivableListResponsePhase3> => {
    const response = await apiClient.get<ApiResponse<ReceivableListResponsePhase3>>(
      `/receivables/netting/cycle/${cycleId}`,
      { params: { page, size } }
    );
    return extractReceivableData(response.data);
  },
  
  getNettingSummary: async (corporateId: string): Promise<NettingSummaryResponse> => {
    const response = await apiClient.get<ApiResponse<NettingSummaryResponse>>('/receivables/netting/summary', {
      headers: { 'X-Corporate-Id': corporateId }
    });
    return extractReceivableData(response.data);
  },
  
  setNettingEligible: async (receivableId: string, eligible: boolean, updatedBy: string): Promise<ReceivablePhase3> => {
    const response = await apiClient.put<ApiResponse<ReceivablePhase3>>(
      `/receivables/${receivableId}/netting/eligible`,
      { eligible, updatedBy }
    );
    return extractReceivableData(response.data);
  },
  
  // =========================================================================
  // ENTITY CONTEXT
  // =========================================================================
  
  updateOwningEntity: async (receivableId: string, entityId: string, updatedBy: string): Promise<ReceivablePhase3> => {
    const response = await apiClient.put<ApiResponse<ReceivablePhase3>>(
      `/receivables/${receivableId}/entity`,
      { entityId, updatedBy }
    );
    return extractReceivableData(response.data);
  },
};


// ============================================================================
// PAYABLES API
// ============================================================================

// Types
export interface PayablesStats {
  totalPayables: number;
  pendingApproval: number;
  scheduledPayments: number;
  paidThisMonth: number;
  overdueAmount: number;
  invoiceCount: number;
  pendingCount: number;
  approvedCount: number;
  scheduledCount: number;
  overdueCount: number;
  dueSoonCount: number;
  currentAmount: number;
  overdue1to30: number;
  overdue31to60: number;
  overdue61to90: number;
  overdue90Plus: number;
  activeBatchCount: number;
  batchProcessingAmount: number;
}

export interface Payable {
  id: string;
  invoiceNumber: string;
  vendorName: string;
  vendorId: string;
  vendorVaNumber: string;
  invoiceDate: string;
  dueDate: string;
  amount: number;
  paidAmount: number;
  outstandingAmount: number;
  currencyCode: string;
  status: string;
  description?: string;
  paymentTerms?: string;
  approvedBy?: string;
  approvedAt?: string;
  scheduledPaymentDate?: string;
  paymentAccount?: string;
  paymentChannel?: string;
  paymentReference?: string;
  paidDate?: string;
  batchReference?: string;
  createdAt: string;
}

export interface PaymentBatch {
  id: string;
  batchReference: string;
  createdDate: string;
  scheduledDate: string;
  totalAmount: number;
  currencyCode: string;
  paymentCount: number;
  status: string;
  approver?: string;
  approvedDate?: string;
  successCount?: number;
  failedCount?: number;
  processedAmount?: number;
  processedAt?: string;
  items?: BatchPaymentItem[];
}

export interface BatchPaymentItem {
  payableId: string;
  invoiceNumber: string;
  vendorName: string;
  amount: number;
  status: string;
  paymentReference?: string;
  errorMessage?: string;
}

export interface ScheduledPayment {
  id: string;
  paymentReference: string;
  vendorName: string;
  vendorAccount: string;
  amount: number;
  currencyCode: string;
  scheduledDate: string;
  invoiceRef: string;
  status: string;
  paymentChannel: string;
  executedAt?: string;
  transactionReference?: string;
  failureReason?: string;
}

export interface VendorPayableSummary {
  vendorId: string;
  vendorName: string;
  vendorCode: string;
  invoiceCount: number;
  totalOutstanding: number;
  overdueAmount: number;
  daysPayableOutstanding: number;
  lastPaymentDate?: string;
  lastPaymentAmount?: number;
}

export interface PayablesAging {
  buckets: AgingBucket[];
  totalPayables: number;
  totalInvoices: number;
  asOfDate: string;
}

export interface AgingBucket {
  bucket: string;
  amount: number;
  invoiceCount: number;
  percentage: number;
}

// API Client
export const payablesApi = {
  // Stats
  getStats: (corporateId?: string) => 
    apiClient.get<ApiResponse<PayablesStats>>('/payables/stats', { params: { corporateId } }).then(r => r.data),

  // Payable Invoices
  getAll: (page = 0, size = 20, corporateId?: string, status?: string) => 
    apiClient.get<ApiResponse<Payable[]>>('/payables', { params: { page, size, corporateId, status } }).then(r => r.data),
  
  getById: (id: string) => 
    apiClient.get<ApiResponse<Payable>>(`/payables/${id}`).then(r => r.data),
  
  create: (data: any) => 
    apiClient.post<ApiResponse<Payable>>('/payables', data).then(r => r.data),
  
  update: (id: string, data: any) => 
    apiClient.put<ApiResponse<Payable>>(`/payables/${id}`, data).then(r => r.data),
  
  approve: (id: string, approvedBy: string, comments?: string) => 
    apiClient.post<ApiResponse<Payable>>(`/payables/${id}/approve`, { approvedBy, comments }).then(r => r.data),
  
  reject: (id: string, rejectedBy: string, reason: string, comments?: string) => 
    apiClient.post<ApiResponse<Payable>>(`/payables/${id}/reject`, { rejectedBy, reason, comments }).then(r => r.data),
  
  schedule: (id: string, paymentDate: string, paymentAccount: string, paymentChannel?: string) => 
    apiClient.post<ApiResponse<Payable>>(`/payables/${id}/schedule`, { paymentDate, paymentAccount, paymentChannel }).then(r => r.data),
  
  hold: (id: string, reason: string) => 
    apiClient.post<ApiResponse<Payable>>(`/payables/${id}/hold`, null, { params: { reason } }).then(r => r.data),
  
  releaseHold: (id: string) => 
    apiClient.post<ApiResponse<Payable>>(`/payables/${id}/release-hold`).then(r => r.data),
  
  cancel: (id: string, reason?: string) => 
    apiClient.delete<ApiResponse<void>>(`/payables/${id}`, { params: { reason } }).then(r => r.data),

  // Payment Batches
  getBatches: (corporateId?: string) => 
    apiClient.get<ApiResponse<PaymentBatch[]>>('/payables/batches', { params: { corporateId } }).then(r => r.data),
  
  getBatchById: (batchId: string) => 
    apiClient.get<ApiResponse<PaymentBatch>>(`/payables/batches/${batchId}`).then(r => r.data),
  
  createBatch: (payableIds: string[], paymentDate: string, paymentAccount: string, paymentChannel?: string) => 
    apiClient.post<ApiResponse<PaymentBatch>>('/payables/batches', { payableIds, paymentDate, paymentAccount, paymentChannel }).then(r => r.data),
  
  approveBatch: (batchId: string, approvedBy: string, comments?: string) => 
    apiClient.post<ApiResponse<PaymentBatch>>(`/payables/batches/${batchId}/approve`, { approvedBy, comments }).then(r => r.data),
  
  processBatch: (batchId: string) => 
    apiClient.post<ApiResponse<PaymentBatch>>(`/payables/batches/${batchId}/process`).then(r => r.data),
  
  cancelBatch: (batchId: string, reason?: string) => 
    apiClient.delete<ApiResponse<void>>(`/payables/batches/${batchId}`, { params: { reason } }).then(r => r.data),

  // Batch Pay (shortcut)
  batchPay: (payableIds: string[], paymentAccount: string, paymentDate: string, paymentChannel?: string) => 
    apiClient.post<ApiResponse<PaymentBatch>>('/payables/batch-pay', { payableIds, paymentDate, paymentAccount, paymentChannel }).then(r => r.data),

  // Scheduled Payments
  getScheduled: (corporateId?: string, status?: string) => 
    apiClient.get<ApiResponse<ScheduledPayment[]>>('/payables/scheduled', { params: { corporateId, status } }).then(r => r.data),
  
  cancelScheduledPayment: (paymentId: string, reason: string, cancelledBy: string) => 
    apiClient.post<ApiResponse<ScheduledPayment>>(`/payables/scheduled/${paymentId}/cancel`, { reason, cancelledBy }).then(r => r.data),

  // Vendor Summaries
  getVendorSummaries: (corporateId?: string) => 
    apiClient.get<ApiResponse<VendorPayableSummary[]>>('/payables/vendors/summary', { params: { corporateId } }).then(r => r.data),

  // Aging Report
  getAging: (corporateId?: string) => 
    apiClient.get<ApiResponse<PayablesAging>>('/payables/aging', { params: { corporateId } }).then(r => r.data),
};

// ============================================================================
// PHASE 2: ENHANCED PAYABLES API - POBO, INTERCOMPANY & NETTING
// ============================================================================

// Phase 2 Types
export interface PayablePhase2 {
  id: string;
  payableNumber: string;
  externalReference?: string;
  invoiceNumber: string;
  payableType: string;
  
  // Ownership
  corporateId: string;
  programId?: string;
  virtualAccountId?: string;
  
  // Entity Context
  owningEntityId?: string;
  owningEntityCode?: string;
  owningEntityName?: string;
  
  // Party Integration
  partyId?: string;
  partyCode?: string;
  partyBankAccountId?: string;
  
  // Vendor Info
  vendorId?: string;
  vendorName: string;
  vendorAccount?: string;
  vendorBank?: string;
  
  // Amounts
  currencyCode: string;
  grossAmount: number;
  discountAmount?: number;
  taxAmount?: number;
  withholdingTax?: number;
  netAmount: number;
  paidAmount: number;
  outstandingAmount: number;
  
  // Dates
  invoiceDate: string;
  receivedDate?: string;
  dueDate: string;
  paymentTermsDays?: number;
  daysUntilDue?: number;
  agingBucket?: string;
  
  // Status
  status: string;
  paymentStatus: string;
  isOverdue: boolean;
  
  // Payment Route
  paymentRoute: 'DIRECT' | 'POBO' | 'INTERCOMPANY' | 'NETTING';
  paymentViaEntityId?: string;
  paymentViaEntityCode?: string;
  paymentRouteDescription?: string;
  
  // POBO
  poboRequestId?: string;
  poboRequestStatus?: string;
  poboTransactionRef?: string;
  poboIhbLoanId?: string;
  poboRechargeId?: string;
  poboRequestedAt?: string;
  poboRequestedBy?: string;
  poboActionedAt?: string;
  poboActionedBy?: string;
  
  // Intercompany
  isIntercompany: boolean;
  counterpartyEntityId?: string;
  counterpartyEntityCode?: string;
  counterpartyEntityName?: string;
  counterpartyReceivableId?: string;
  
  // Netting
  nettingEligible: boolean;
  nettingCycleId?: string;
  nettingCycleRef?: string;
  nettingEntryId?: string;
  nettingStatus: string;
  nettingSettlementRef?: string;
  nettingSettledAt?: string;
  
  // Approval
  approvalRequired?: boolean;
  approvalLevel?: number;
  approvedBy?: string;
  approvedAt?: string;
  rejectionReason?: string;
  
  // Scheduling
  scheduledDate?: string;
  paymentPriority?: string;
  paymentMethod?: string;
  paymentChannel?: string;
  paymentBatchId?: string;
  
  // Hierarchy
  hierarchyNodeId?: string;
  hierarchyPath?: string;
  
  // Metadata
  description?: string;
  notes?: string;
  hasInvoiceDocument?: boolean;
  documentCount?: number;
  
  // Audit
  createdBy?: string;
  createdAt: string;
  updatedBy?: string;
  updatedAt?: string;
  
  // Computed flags
  canBePaid: boolean;
  canRequestPobo: boolean;
  canAddToNetting: boolean;
}

export interface PayableStatsPhase2 {
  totalCount: number;
  pendingCount: number;
  approvedCount: number;
  paidCount: number;
  overdueCount: number;
  intercompanyCount: number;
  poboRequestedCount: number;
  poboPendingApprovalCount: number;
  poboExecutedCount: number;
  nettingIncludedCount: number;
  totalAmount: number;
  outstandingAmount: number;
  paidAmount: number;
  overdueAmount: number;
  intercompanyAmount: number;
  poboPendingAmount: number;
  nettingAmount: number;
  currentAmount: number;
  overdue1to30Amount: number;
  overdue31to60Amount: number;
  overdue61to90Amount: number;
  overdue90PlusAmount: number;
  currencyCode: string;
}

export interface PayableListResponse {
  payables: PayablePhase2[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
  hasPrevious: boolean;
}

export interface PayableSearchParams {
  corporateId?: string;
  programId?: string;
  owningEntityId?: string;
  partyId?: string;
  counterpartyEntityId?: string;
  status?: string;
  paymentRoute?: string;
  poboRequestStatus?: string;
  nettingStatus?: string;
  isIntercompany?: boolean;
  nettingEligible?: boolean;
  isOverdue?: boolean;
  dueDateFrom?: string;
  dueDateTo?: string;
  amountMin?: number;
  amountMax?: number;
  currencyCode?: string;
  searchTerm?: string;
  page?: number;
  size?: number;
  sortBy?: string;
  sortOrder?: string;
}

export interface CreatePayableRequest {
  corporateId: string;
  programId?: string;
  invoiceNumber: string;
  grossAmount: number;
  currencyCode: string;
  dueDate: string;
  owningEntityId?: string;
  owningEntityCode?: string;
  owningEntityName?: string;
  partyId?: string;
  partyBankAccountId?: string;
  vendorId?: string;
  vendorName?: string;
  vendorCode?: string;
  vendorAccount?: string;
  vendorBank?: string;
  payableType?: string;
  externalReference?: string;
  discountAmount?: number;
  taxAmount?: number;
  taxRate?: number;
  withholdingTax?: number;
  invoiceDate?: string;
  paymentTermsDays?: number;
  hierarchyNodeId?: string;
  isIntercompany?: boolean;
  counterpartyEntityId?: string;
  counterpartyEntityCode?: string;
  paymentRoute?: string;
  paymentChannel?: string;
  scheduledDate?: string;
  paymentPriority?: string;
  paymentMethod?: string;
  approvalRequired?: boolean;
  description?: string;
  notes?: string;
  netAmount?: number;
  status?: string;
  createdBy?: string;
}

// POBO Types
export interface PoboRequestRequest {
  payableIds: string[];
  payingEntityId: string;
  payingEntityCode?: string;
  requestedBy: string;
  notes?: string;
  createBatch?: boolean;
}

export interface PoboRequestResponse {
  payableId: string;
  payableNumber: string;
  poboRequestStatus: string;
  requestedAt: string;
  success: boolean;
  message: string;
}

export interface PoboBatchRequestResponse {
  results: PoboRequestResponse[];
  successCount: number;
  failedCount: number;
  totalAmount: number;
  currencyCode: string;
}

export interface PoboApprovalRequest {
  payableId: string;
  approved: boolean;
  actionedBy: string;
  rejectionReason?: string;
  notes?: string;
}

export interface PoboBatchApprovalRequest {
  payableIds: string[];
  approved: boolean;
  actionedBy: string;
  rejectionReason?: string;
}

export interface PoboPreviewRequest {
  payableIds: string[];
  payingEntityId: string;
  behalfEntityId?: string;
}

export interface PoboChargeBreakdown {
  chargeCode: string;
  chargeName: string;
  chargeType: string;
  calculatedAmount: number;
  waived: boolean;
  waiverReason?: string;
}

export interface PoboIhbLoanPreview {
  principalAmount: number;
  interestRate: number;
  estimatedDailyInterest: number;
  estimatedMonthlyInterest: number;
  tenor: string;
  expectedSettlementDate?: string;
}

export interface PoboPreviewResponse {
  payingEntityId: string;
  payingEntityCode: string;
  payingEntityName: string;
  behalfEntityId: string;
  behalfEntityCode: string;
  behalfEntityName: string;
  payableCount: number;
  totalPaymentAmount: number;
  currencyCode: string;
  // Paying Entity VA Balance & Credit Limit
  payingVaId?: string;
  payingVaNumber?: string;
  payingVaBalance?: number;
  payingVaCreditLimit?: number;
  payingVaCreditLimitAvailable?: number;
  payingVaEffectiveAvailableBalance?: number;  // balance + creditLimitAvailable
  // Behalf Entity VA Balance & Credit Limit (subsidiary being charged)
  behalfVaId?: string;
  behalfVaNumber?: string;
  behalfVaBalance?: number;
  behalfVaCreditLimit?: number;
  behalfVaCreditLimitAvailable?: number;
  behalfVaEffectiveAvailableBalance?: number;
  charges: PoboChargeBreakdown[];
  totalCharges: number;
  netPaymentAmount: number;
  ihbLoanPreview: PoboIhbLoanPreview;
  warnings: string[];
  isValid: boolean;
  validationMessage?: string;
}

export interface PoboExecuteRequest {
  payableIds: string[];
  payingEntityId: string;
  executedBy: string;
  createIhbLoan?: boolean;
  createRecharge?: boolean;
  notes?: string;
}

export interface PoboExecutionResult {
  payableId: string;
  payableNumber: string;
  transactionRef: string;
  amount: number;
  success: boolean;
  errorMessage?: string;
}

export interface PoboExecuteResponse {
  batchTransactionRef: string;
  results: PoboExecutionResult[];
  successCount: number;
  failedCount: number;
  totalExecutedAmount: number;
  currencyCode: string;
  ihbLoanId?: string;
  rechargeId?: string;
  executedAt: string;
}

// Intercompany Types
export interface CreateIntercompanyPayableRequest {
  corporateId: string;
  owningEntityId: string;
  owningEntityCode?: string;
  owningEntityName?: string;
  counterpartyEntityId: string;
  counterpartyEntityCode?: string;
  counterpartyEntityName?: string;
  partyId?: string;
  amount: number;
  currencyCode: string;
  description: string;
  transactionType?: string;
  dueDate: string;
  relatedReceivableId?: string;
  externalReference?: string;
  createMatchingReceivable?: boolean;
  addToNextNetting?: boolean;
  createdBy?: string;
}

export interface IntercompanyPayableResponse {
  id: string;
  payableNumber: string;
  owningEntityId: string;
  owningEntityCode: string;
  owningEntityName: string;
  counterpartyEntityId: string;
  counterpartyEntityCode: string;
  counterpartyEntityName: string;
  amount: number;
  currencyCode: string;
  dueDate: string;
  status: string;
  description: string;
  transactionType?: string;
  counterpartyReceivableId?: string;
  counterpartyReceivableNumber?: string;
  nettingStatus: string;
  nettingCycleId?: string;
  nettingCycleRef?: string;
  createdAt: string;
}

export interface IntercompanyPositionResponse {
  entityId: string;
  entityCode: string;
  entityName: string;
  counterpartyEntityId: string;
  counterpartyEntityCode: string;
  counterpartyEntityName: string;
  payablesTotal: number;
  receivablesTotal: number;
  netPosition: number;
  currencyCode: string;
  payablesCount: number;
  receivablesCount: number;
  oldestPayableDue?: string;
  oldestReceivableDue?: string;
}

// Netting Types
export interface AddToNettingRequest {
  payableIds: string[];
  nettingCycleId: string;
  addedBy: string;
}

export interface NettingAddResult {
  payableId: string;
  payableNumber: string;
  nettingEntryId?: string;
  success: boolean;
  errorMessage?: string;
}

export interface AddToNettingResponse {
  nettingCycleId: string;
  nettingCycleRef: string;
  results: NettingAddResult[];
  successCount: number;
  failedCount: number;
  totalAmountAdded: number;
}

export interface NettingEligiblePayablesResponse {
  payables: PayablePhase2[];
  totalAmount: number;
  currencyCode: string;
  count: number;
  byOwningEntity: Record<string, number>;
  byCounterparty: Record<string, number>;
}

export interface PayableBatchOperationResponse {
  successIds: string[];
  errors: { payableId: string; payableNumber?: string; errorCode: string; errorMessage: string }[];
  successCount: number;
  failedCount: number;
}

// Helper to safely extract data
const extractPayableData = <T>(response: ApiResponse<T>): T => {
  if (response && response.data !== undefined) {
    return response.data;
  }
  return response as unknown as T;
};

// Phase 2 API Client
export const payablesApiPhase2 = {
  // =========================================================================
  // CRUD & SEARCH
  // =========================================================================
  
  getStats: async (corporateId: string, programId?: string, owningEntityId?: string): Promise<PayableStatsPhase2> => {
    const params: Record<string, string> = {};
    if (programId) params.programId = programId;
    if (owningEntityId) params.owningEntityId = owningEntityId;
    const response = await apiClient.get<ApiResponse<PayableStatsPhase2>>('/payables/stats', {
      headers: { 'X-Corporate-Id': corporateId },
      params
    });
    return extractPayableData(response.data);
  },
  
  search: async (params: PayableSearchParams): Promise<PayableListResponse> => {
    const { corporateId, owningEntityId, ...queryParams } = params;
    const headers: Record<string, string> = {};
    if (corporateId) headers['X-Corporate-Id'] = corporateId;
    if (owningEntityId) headers['X-Legal-Entity-Id'] = owningEntityId;
    const response = await apiClient.get<ApiResponse<PayableListResponse>>('/payables', {
      headers,
      params: { ...queryParams, owningEntityId }
    });
    return extractPayableData(response.data);
  },
  
  getById: async (payableId: string): Promise<PayablePhase2> => {
    const response = await apiClient.get<ApiResponse<PayablePhase2>>(`/payables/${payableId}`);
    return extractPayableData(response.data);
  },
  
  getByEntity: async (owningEntityId: string, page = 0, size = 20): Promise<PayableListResponse> => {
    const response = await apiClient.get<ApiResponse<PayableListResponse>>(`/payables/by-entity/${owningEntityId}`, {
      params: { page, size }
    });
    return extractPayableData(response.data);
  },
  
  create: async (data: CreatePayableRequest): Promise<PayablePhase2> => {
    const headers: Record<string, string> = {};
    if (data.corporateId) headers['X-Corporate-Id'] = data.corporateId;
    console.log('payablesApiPhase2.create - sending request:', data);
    const response = await apiClient.post<ApiResponse<PayablePhase2>>('/payables', data, { headers });
    return extractPayableData(response.data);
  },
  
  update: async (payableId: string, data: Partial<CreatePayableRequest>): Promise<PayablePhase2> => {
    const response = await apiClient.put<ApiResponse<PayablePhase2>>(`/payables/${payableId}`, data);
    return extractPayableData(response.data);
  },
  
  // =========================================================================
  // POBO WORKFLOW
  // =========================================================================
  
  requestPobo: async (request: PoboRequestRequest): Promise<PoboBatchRequestResponse> => {
    const response = await apiClient.post<ApiResponse<PoboBatchRequestResponse>>('/payables/pobo/request', request);
    return extractPayableData(response.data);
  },
  
  getPoboPendingApproval: async (): Promise<PayablePhase2[]> => {
    const response = await apiClient.get<ApiResponse<PayablePhase2[]>>('/payables/pobo/pending-approval');
    return extractPayableData(response.data);
  },
  
  actionPoboRequest: async (request: PoboApprovalRequest): Promise<PayablePhase2> => {
    const response = await apiClient.post<ApiResponse<PayablePhase2>>('/payables/pobo/approve', request);
    return extractPayableData(response.data);
  },
  
  batchApprovePobo: async (request: PoboBatchApprovalRequest): Promise<PayableBatchOperationResponse> => {
    const response = await apiClient.post<ApiResponse<PayableBatchOperationResponse>>('/payables/pobo/batch-approve', request);
    return extractPayableData(response.data);
  },
  
  previewPobo: async (request: PoboPreviewRequest): Promise<PoboPreviewResponse> => {
    const response = await apiClient.post<ApiResponse<PoboPreviewResponse>>('/payables/pobo/preview', request);
    return extractPayableData(response.data);
  },
  
  executePobo: async (request: PoboExecuteRequest): Promise<PoboExecuteResponse> => {
    const response = await apiClient.post<ApiResponse<PoboExecuteResponse>>('/payables/pobo/execute', request);
    return extractPayableData(response.data);
  },
  
  getPoboEligible: async (owningEntityId?: string, page = 0, size = 20): Promise<PayableListResponse> => {
    const params: Record<string, any> = { page, size };
    if (owningEntityId) params.owningEntityId = owningEntityId;
    const response = await apiClient.get<ApiResponse<PayableListResponse>>('/payables/pobo/eligible', { params });
    return extractPayableData(response.data);
  },
  
  getPoboHistory: async (owningEntityId?: string, page = 0, size = 20): Promise<PayableListResponse> => {
    const params: Record<string, any> = { page, size };
    if (owningEntityId) params.owningEntityId = owningEntityId;
    const response = await apiClient.get<ApiResponse<PayableListResponse>>('/payables/pobo/history', { params });
    return extractPayableData(response.data);
  },
  
  // =========================================================================
  // INTERCOMPANY
  // =========================================================================
  
  createIntercompany: async (data: CreateIntercompanyPayableRequest): Promise<IntercompanyPayableResponse> => {
    const { corporateId, ...body } = data;
    const response = await apiClient.post<ApiResponse<IntercompanyPayableResponse>>('/payables/intercompany', body, {
      headers: { 'X-Corporate-Id': corporateId }
    });
    return extractPayableData(response.data);
  },
  
  getIntercompanyPayables: async (corporateId: string, owningEntityId?: string, page = 0, size = 20): Promise<PayableListResponse> => {
    const params: Record<string, any> = { page, size };
    if (owningEntityId) params.owningEntityId = owningEntityId;
    const response = await apiClient.get<ApiResponse<PayableListResponse>>('/payables/intercompany', {
      headers: { 'X-Corporate-Id': corporateId },
      params
    });
    return extractPayableData(response.data);
  },
  
  getIntercompanyByEntity: async (owningEntityId: string): Promise<IntercompanyPayableResponse[]> => {
    const response = await apiClient.get<ApiResponse<IntercompanyPayableResponse[]>>(`/payables/intercompany/by-entity/${owningEntityId}`);
    return extractPayableData(response.data);
  },
  
  getIntercompanyPosition: async (entityId: string, counterpartyId: string): Promise<IntercompanyPositionResponse> => {
    const response = await apiClient.get<ApiResponse<IntercompanyPositionResponse>>('/payables/intercompany/position', {
      params: { entityId, counterpartyId }
    });
    return extractPayableData(response.data);
  },
  
  // =========================================================================
  // NETTING
  // =========================================================================
  
  getNettingEligible: async (owningEntityId?: string): Promise<NettingEligiblePayablesResponse> => {
    const params: Record<string, any> = {};
    if (owningEntityId) params.owningEntityId = owningEntityId;
    const response = await apiClient.get<ApiResponse<NettingEligiblePayablesResponse>>('/payables/netting/eligible', { params });
    return extractPayableData(response.data);
  },
  
  addToNetting: async (request: AddToNettingRequest): Promise<AddToNettingResponse> => {
    const response = await apiClient.post<ApiResponse<AddToNettingResponse>>('/payables/netting/add', request);
    return extractPayableData(response.data);
  },
  
  removeFromNetting: async (payableIds: string[], reason: string, removedBy: string): Promise<any> => {
    const response = await apiClient.post<ApiResponse<any>>('/payables/netting/remove', {
      payableIds,
      reason,
      removedBy
    });
    return extractPayableData(response.data);
  },
  
  getPayablesInCycle: async (cycleId: string, page = 0, size = 50): Promise<PayableListResponse> => {
    const response = await apiClient.get<ApiResponse<PayableListResponse>>(`/payables/netting/by-cycle/${cycleId}`, {
      params: { page, size }
    });
    return extractPayableData(response.data);
  },
  
  // =========================================================================
  // APPROVAL WORKFLOW
  // =========================================================================
  
  submitForApproval: async (payableId: string, submittedBy: string): Promise<PayablePhase2> => {
    const response = await apiClient.post<ApiResponse<PayablePhase2>>(`/payables/${payableId}/submit`, null, {
      params: { submittedBy }
    });
    return extractPayableData(response.data);
  },
  
  approve: async (payableId: string, approvedBy: string, notes?: string): Promise<PayablePhase2> => {
    const response = await apiClient.post<ApiResponse<PayablePhase2>>(`/payables/${payableId}/approve`, {
      approvedBy,
      notes
    });
    return extractPayableData(response.data);
  },
  
  reject: async (payableId: string, rejectedBy: string, rejectionReason: string): Promise<PayablePhase2> => {
    const response = await apiClient.post<ApiResponse<PayablePhase2>>(`/payables/${payableId}/reject`, {
      rejectedBy,
      rejectionReason
    });
    return extractPayableData(response.data);
  },
  
  schedule: async (payableId: string, scheduledDate: string, paymentPriority?: string, paymentMethod?: string): Promise<PayablePhase2> => {
    const response = await apiClient.post<ApiResponse<PayablePhase2>>(`/payables/${payableId}/schedule`, {
      scheduledDate,
      paymentPriority,
      paymentMethod
    });
    return extractPayableData(response.data);
  },

  // Execute payment for an approved payable
  executePayment: async (payableId: string, executedBy: string, paymentChannel?: string, paymentMethod?: string): Promise<any> => {
    const response = await apiClient.post<ApiResponse<any>>(`/payables/${payableId}/execute`, {
      payableId,
      executedBy,
      paymentChannel,
      paymentMethod
    });
    return extractPayableData(response.data);
  },

  // =========================================================================
  // BATCH OPERATIONS
  // =========================================================================
  
  batchApprove: async (payableIds: string[], approvedBy: string): Promise<PayableBatchOperationResponse> => {
    const response = await apiClient.post<ApiResponse<PayableBatchOperationResponse>>('/payables/batch/approve', {
      payableIds,
      approvedBy
    });
    return extractPayableData(response.data);
  },
  
  batchSchedule: async (payableIds: string[], scheduledDate: string, paymentPriority?: string, scheduledBy?: string): Promise<PayableBatchOperationResponse> => {
    const response = await apiClient.post<ApiResponse<PayableBatchOperationResponse>>('/payables/batch/schedule', {
      payableIds,
      scheduledDate,
      paymentPriority,
      scheduledBy
    });
    return extractPayableData(response.data);
  },
};

// ============================================================================
// KYC API
// ============================================================================

export const kycApi = {
  getPending: (page = 0, size = 20) => apiClient.get<ApiResponse<any[]>>('/kyc/pending', { params: { page, size } }).then(r => r.data),
  getById: (id: string) => apiClient.get<ApiResponse<any>>(`/kyc/${id}`).then(r => r.data),
  approve: (id: string, decidedBy: string, comments?: string) => 
    apiClient.post<ApiResponse<any>>(`/kyc/${id}/approve`, { decidedBy, comments }).then(r => r.data),
  reject: (id: string, decidedBy: string, reason: string, comments?: string) => 
    apiClient.post<ApiResponse<any>>(`/kyc/${id}/reject`, { decidedBy, reason, comments }).then(r => r.data),
  requestInfo: (id: string, requiredDocuments: string[], requestedBy: string) => 
    apiClient.post<ApiResponse<any>>(`/kyc/${id}/request-info`, { requiredDocuments, requestedBy }).then(r => r.data),
  getStats: () => apiClient.get<ApiResponse<any>>('/kyc/stats').then(r => r.data),
  getExpiring: (daysAhead = 30) => apiClient.get<ApiResponse<any[]>>('/kyc/expiring', { params: { daysAhead } }).then(r => r.data),
};

// ============================================================================
// VIBAN API
// ============================================================================

// ============================================================================
// VIBAN API
// ============================================================================

export interface VibanPool {
  id: string;
  programId: string;
  programName?: string;
  poolName: string;
  poolCode: string;
  description?: string;
  countryCode: string;
  bankCode: string;
  prefix: string;
  suffixLength: number;
  poolSize: number;
  availableCount: number;
  reservedCount: number;
  assignedCount: number;
  utilizationPercent?: number;
  assignmentTtlMinutes: number;
  autoReturnExpired: boolean;
  lowThresholdPercent: number;
  status: 'ACTIVE' | 'INACTIVE' | 'EXHAUSTED';
  createdAt: string;
  updatedAt?: string;
}

export interface Viban {
  id: string;
  viban: string;
  virtualAccountId?: string;
  vaNumber?: string;
  vaName?: string;
  programId?: string;
  poolId?: string;
  poolCode?: string;
  vibanType?: string;
  referenceType?: string;
  referenceId?: string;
  expectedAmount?: number;
  remainingAmount?: number;
  currencyCode?: string;
  status: 'ACTIVE' | 'AVAILABLE' | 'RESERVED' | 'EXPIRED' | 'DISABLED';
  isPrimary?: boolean;
  singleUse?: boolean;
  timesUsed?: number;
  totalAmountReceived?: number;
  validFrom?: string;
  validUntil?: string;
  customerName?: string;
  purpose?: string;
  paymentLink?: string;
  createdAt: string;
}

export interface VibanStats {
  total: number;
  assigned: number;
  available: number;
  reserved: number;
  expired: number;
  totalPools: number;
  activePools: number;
  lowThresholdPools: number;
  totalPaymentsRouted: number;
  totalAmountRouted: number;
}

export interface VibanAssignRequest {
  virtualAccountId: string;
  partyId?: string;           // Party Master reference (preferred over customerName)
  referenceType?: string;
  referenceId?: string;
  expectedAmount?: number;
  customerName?: string;      // Deprecated: Use partyId instead
  isPrimary?: boolean;        // If true, creates permanent VIBAN (no TTL)
}

export interface VibanAssignResponse {
  vibanId: string;
  viban: string;
  virtualAccountId: string;
  vaNumber?: string;
  partyId?: string;
  partyName?: string;
  isPrimary?: boolean;
  assignedAt: string;
  returnScheduledAt?: string;  // null for primary VIBANs
}

export interface BulkVibanAssignItem {
  virtualAccountId: string;
  partyId?: string;
  referenceType?: string;
  referenceId?: string;
  expectedAmount?: number;
  isPrimary?: boolean;
}

export interface BulkVibanAssignRequest {
  assignments: BulkVibanAssignItem[];
}

export interface BulkVibanAssignError {
  virtualAccountId: string;
  error: string;
}

export interface BulkVibanAssignResponse {
  totalRequested: number;
  successCount: number;
  failedCount: number;
  successful: VibanAssignResponse[];
  failed: BulkVibanAssignError[];
}

export interface InvoiceVibanRequest {
  invoiceId?: string;
  virtualAccountId?: string;
  partyId: string;
  invoiceNumber: string;
  invoiceAmount: number;
  currencyCode: string;
  dueDate?: string;
  paymentTerms?: string;
}

export interface InvoiceVibanResponse {
  invoiceId?: string;
  invoiceNumber: string;
  vibanId: string;
  viban: string;
  virtualAccountId: string;
  vaNumber: string;
  partyId: string;
  partyName: string;
  expectedAmount: number;
  currencyCode: string;
  validUntil: string;
  paymentLink?: string;
  qrCodeData?: string;
}

export const vibanApi = {
  // Basic VIBAN operations
  getAll: (page = 0, size = 20, corporateId?: string, status?: string) => 
    apiClient.get<ApiResponse<Viban[]>>('/vibans', { params: { page, size, corporateId, status } }).then(r => r.data),
  getAvailable: (count = 10) => 
    apiClient.get<ApiResponse<string[]>>('/vibans/available', { params: { count } }).then(r => r.data),
  generate: (count: number, poolId?: string, prefix?: string) => 
    apiClient.post<ApiResponse<string[]>>('/vibans/generate', { count, poolId, prefix }).then(r => r.data),
  assign: (viban: string, vaId: string) => 
    apiClient.post<ApiResponse<Viban>>('/vibans/assign', { viban, virtualAccountId: vaId }).then(r => r.data),
  release: (viban: string) => 
    apiClient.post<ApiResponse<void>>(`/vibans/${viban}/release`).then(r => r.data),
  getStats: () => 
    apiClient.get<ApiResponse<VibanStats>>('/vibans/stats').then(r => r.data),
  
  // VIBAN Lookup
  lookup: (viban: string) => 
    apiClient.get<ApiResponse<Viban>>(`/vibans/lookup/${viban}`).then(r => r.data),
  
  // Pool Management
  getPools: (programId?: string): Promise<ApiResponse<VibanPool[]>> => {
    const url = programId ? `/programs/${programId}/viban-pools` : '/viban-pools';
    return apiClient.get<ApiResponse<VibanPool[]>>(url).then(r => r.data);
  },
  getPool: (poolId: string) => 
    apiClient.get<ApiResponse<VibanPool>>(`/viban-pools/${poolId}`).then(r => r.data),
  createPool: (programId: string, data: Partial<VibanPool>) => 
    apiClient.post<ApiResponse<VibanPool>>(`/programs/${programId}/viban-pools`, data).then(r => r.data),
  updatePool: (poolId: string, data: Partial<VibanPool>) => 
    apiClient.put<ApiResponse<VibanPool>>(`/viban-pools/${poolId}`, data).then(r => r.data),
  deletePool: (poolId: string) => 
    apiClient.delete<ApiResponse<void>>(`/viban-pools/${poolId}`).then(r => r.data),
  generateForPool: (poolId: string, count: number) => 
    apiClient.post<ApiResponse<{ generated: number }>>(`/viban-pools/${poolId}/generate`, { count }).then(r => r.data),
  assignFromPool: (poolId: string, request: VibanAssignRequest) =>
    apiClient.post<ApiResponse<VibanAssignResponse>>(`/viban-pools/${poolId}/assign`, request).then(r => r.data),
  returnToPool: (poolId: string, vibanId: string) =>
    apiClient.post<ApiResponse<void>>(`/viban-pools/${poolId}/return/${vibanId}`).then(r => r.data),

  // Bulk Assignment
  bulkAssignFromPool: (poolId: string, request: BulkVibanAssignRequest) =>
    apiClient.post<ApiResponse<BulkVibanAssignResponse>>(`/viban-pools/${poolId}/bulk-assign`, request).then(r => r.data),

  // Invoice VIBAN (Accounts Receivable integration)
  assignVibanForInvoice: (poolId: string, request: InvoiceVibanRequest) =>
    apiClient.post<ApiResponse<InvoiceVibanResponse>>(`/viban-pools/${poolId}/assign-invoice`, request).then(r => r.data),

  // Invoice/Order VIBANs
  createInvoiceViban: (programId: string, virtualAccountId: string, invoiceId: string, amount: number, validUntil?: string) => 
    apiClient.post<ApiResponse<Viban>>(`/programs/${programId}/vibans/invoice`, null, { 
      params: { virtualAccountId, invoiceId, amount, validUntil } 
    }).then(r => r.data),
  createOrderViban: (programId: string, virtualAccountId: string, orderId: string, amount: number) => 
    apiClient.post<ApiResponse<Viban>>(`/programs/${programId}/vibans/order`, null, { 
      params: { virtualAccountId, orderId, amount } 
    }).then(r => r.data),
  
  // ROBO Routing
  routePayment: (request: { viban: string; amount: number; currencyCode: string; senderName?: string }) => 
    apiClient.post<ApiResponse<{ transactionId: string; targetVaId: string }>>('/vibans/route', request).then(r => r.data),
};
// ============================================================================
// SYNC ADMIN API
// ============================================================================

export const syncAdminApi = {
  getJobs: () => apiClient.get<ApiResponse<any[]>>('/admin/sync/jobs').then(r => r.data),
  triggerJob: (jobType: string) => apiClient.post<ApiResponse<any>>(`/admin/sync/jobs/${jobType}/trigger`).then(r => r.data),
  getQueue: () => apiClient.get<ApiResponse<any[]>>('/admin/sync/queue').then(r => r.data),
  getStats: () => apiClient.get<ApiResponse<any>>('/admin/sync/stats').then(r => r.data),
  getLogs: (page = 0, size = 50) => apiClient.get<ApiResponse<any[]>>('/admin/sync/logs', { params: { page, size } }).then(r => r.data),
};

// ============================================================================
// TREASURY HIERARCHY API
// ============================================================================

export const treasuryHierarchyApi = {
  getHierarchy: (corporateId?: string) => apiClient.get<ApiResponse<any>>('/treasury/hierarchy', { params: { corporateId } }).then(r => r.data),
  getNodeDetails: (id: string) => apiClient.get<ApiResponse<any>>(`/treasury/hierarchy/nodes/${id}`).then(r => r.data),
  createNode: (data: any) => apiClient.post<ApiResponse<any>>('/treasury/hierarchy/nodes', data).then(r => r.data),
  updateNode: (id: string, data: any) => apiClient.put<ApiResponse<any>>(`/treasury/hierarchy/nodes/${id}`, data).then(r => r.data),
  deleteNode: (id: string) => apiClient.delete<ApiResponse<void>>(`/treasury/hierarchy/nodes/${id}`).then(r => r.data),
  moveNode: (id: string, newParentId: string) => apiClient.post<ApiResponse<any>>(`/treasury/hierarchy/nodes/${id}/move`, { newParentId }).then(r => r.data),
  getSummary: () => apiClient.get<ApiResponse<any>>('/treasury/hierarchy/summary').then(r => r.data),
};

// ============================================================================
// BALANCE STRUCTURE API (Treasury Hierarchy with full features)
// ============================================================================

export type BalanceNodeType = 'GROUP' | 'REGION' | 'ENTITY' | 'VIRTUAL_ACCOUNT' | 'SHADOW_ACCOUNT';

export interface BalanceHierarchyNode {
  id: string;
  name: string;
  accountNumber?: string;
  type: BalanceNodeType;
  level: number;
  currencyCode: string;
  
  // Balance information
  localBalance: number;
  consolidatedBalance: number;
  availableBalance?: number;
  
  // Intercompany positions
  intercompanyReceivable: number;
  intercompanyPayable: number;
  netPosition: number;
  
  // Interest (for notional pooling)
  interestRate?: number;
  interestAllocation?: number;
  
  // Participation flags
  participatesInPooling: boolean;
  participatesInNetting: boolean;
  participatesInSweep: boolean;
  sweepTarget?: string;
  
  // Hierarchy
  parentId?: string;
  children?: BalanceHierarchyNode[];
}

export interface BalanceSummary {
  consolidatedBalance: number;
  netPosition: number;
  totalIntercompanyReceivable: number;
  totalIntercompanyPayable: number;
  netIntercompanyPosition: number;
  poolRate: number;
  monthlyInterestAllocation: number;
  reportingCurrency: string;
  totalEntities: number;
  totalVirtualAccounts: number;
  poolParticipants: number;
  sweepParticipants: number;
  nettingParticipants: number;
  currencies: string[];
}

export interface BalancePhysicalAccount {
  id: string;
  bankName: string;
  bankBic?: string;
  accountNumber: string;
  iban?: string;
  accountName: string;
  balance: number;
  availableBalance: number;
  currency: string;
  accountType: string;
  status: string;
}

export interface BalanceNodeDetail {
  id: string;
  name: string;
  accountNumber?: string;
  type: BalanceNodeType;
  currencyCode: string;
  localBalance: number;
  consolidatedBalance: number;
  availableBalance?: number;
  holdAmount?: number;
  intercompanyReceivable: number;
  intercompanyPayable: number;
  netIntercompanyPosition: number;
  icPositions?: Array<{
    counterpartyId: string;
    counterpartyName: string;
    positionType: 'RECEIVABLE' | 'PAYABLE';
    amount: number;
    currencyCode: string;
    loanReference?: string;
  }>;
  participatesInPooling: boolean;
  poolReference?: string;
  poolContributionPercent?: number;
  participatesInNetting: boolean;
  nettingCycleReference?: string;
  participatesInSweep: boolean;
  sweepRuleReference?: string;
  sweepTarget?: string;
  sweepFrequency?: string;
  interestRate?: number;
  monthlyInterestAllocation?: number;
  ytdInterestAllocation?: number;
  manager?: string;
  costCenter?: string;
  externalReference?: string;
}

export interface UpdateParticipationRequest {
  participatesInPooling?: boolean;
  poolReference?: string;
  participatesInNetting?: boolean;
  nettingCycleReference?: string;
  participatesInSweep?: boolean;
  sweepRuleReference?: string;
  sweepTarget?: string;
}

export interface CurrencyInfo {
  code: string;
  name: string;
  symbol: string;
}

export const balanceStructureApi = {
  // Main hierarchy
  getHierarchy: (corporateId?: string, programId?: string, reportingCurrency = 'AED') =>
    apiClient.get<ApiResponse<BalanceHierarchyNode>>('/treasury/balance-structure', {
      params: { corporateId, programId, reportingCurrency }
    }).then(r => r.data),

  // Summary stats
  getSummary: (corporateId?: string, programId?: string, reportingCurrency = 'AED') =>
    apiClient.get<ApiResponse<BalanceSummary>>('/treasury/balance-structure/summary', {
      params: { corporateId, programId, reportingCurrency }
    }).then(r => r.data),
  
  // Physical bank account
  getPhysicalAccount: (corporateId?: string) => 
    apiClient.get<ApiResponse<BalancePhysicalAccount>>('/treasury/balance-structure/physical-account', { 
      params: { corporateId } 
    }).then(r => r.data),
  
  // Node details
  getNodeDetail: (nodeId: string, reportingCurrency = 'AED') => 
    apiClient.get<ApiResponse<BalanceNodeDetail>>(`/treasury/balance-structure/nodes/${nodeId}`, { 
      params: { reportingCurrency } 
    }).then(r => r.data),
  
  // Update participation
  updateParticipation: (nodeId: string, data: UpdateParticipationRequest) => 
    apiClient.put<ApiResponse<BalanceNodeDetail>>(`/treasury/balance-structure/nodes/${nodeId}/participation`, data)
      .then(r => r.data),
  
  // Create node
  createNode: (data: { name: string; type: BalanceNodeType; parentId?: string; currencyCode: string }) => 
    apiClient.post<ApiResponse<BalanceHierarchyNode>>('/treasury/balance-structure/nodes', data).then(r => r.data),
  
  // Move node
  moveNode: (nodeId: string, newParentId: string) => 
    apiClient.post<ApiResponse<BalanceHierarchyNode>>(`/treasury/balance-structure/nodes/${nodeId}/move`, { newParentId })
      .then(r => r.data),
  
  // Delete node
  deleteNode: (nodeId: string) => 
    apiClient.delete<ApiResponse<void>>(`/treasury/balance-structure/nodes/${nodeId}`).then(r => r.data),
  
  // Export
  exportReport: (format: 'CSV' | 'XLSX' | 'PDF', reportingCurrency = 'AED', corporateId?: string) => 
    apiClient.post<ApiResponse<string>>('/treasury/balance-structure/export', { format, reportingCurrency }, {
      params: { corporateId }
    }).then(r => r.data),
  
  // Refresh
  refresh: (corporateId?: string) => 
    apiClient.post<ApiResponse<string>>('/treasury/balance-structure/refresh', null, { 
      params: { corporateId } 
    }).then(r => r.data),
  
  // Get currencies
  getCurrencies: () => 
    apiClient.get<ApiResponse<CurrencyInfo[]>>('/treasury/balance-structure/currencies').then(r => r.data),
};

// ============================================================================
// INTEGRATIONS API
// ============================================================================

export const integrationsApi = {
  getStats: () => apiClient.get<ApiResponse<any>>('/integrations/stats').then(r => r.data),
  getConnectors: () => apiClient.get<ApiResponse<any[]>>('/integrations/connectors').then(r => r.data),
  getConnectorsByCategory: (category: string) => apiClient.get<ApiResponse<any[]>>(`/integrations/connectors/category/${category}`).then(r => r.data),
  getConnections: () => apiClient.get<ApiResponse<any[]>>('/integrations/connections').then(r => r.data),
  createConnection: (data: any) => apiClient.post<ApiResponse<any>>('/integrations/connections', data).then(r => r.data),
  updateConnection: (id: string, data: any) => apiClient.put<ApiResponse<any>>(`/integrations/connections/${id}`, data).then(r => r.data),
  deleteConnection: (id: string) => apiClient.delete<ApiResponse<void>>(`/integrations/connections/${id}`).then(r => r.data),
  testConnection: (id: string) => apiClient.post<ApiResponse<any>>(`/integrations/connections/${id}/test`).then(r => r.data),
  getFlows: (connectionId: string) => apiClient.get<ApiResponse<any[]>>(`/integrations/connections/${connectionId}/flows`).then(r => r.data),
  createFlow: (connectionId: string, data: any) => apiClient.post<ApiResponse<any>>(`/integrations/connections/${connectionId}/flows`, data).then(r => r.data),
  triggerSync: (connectionId: string) => apiClient.post<ApiResponse<any>>('/integrations/sync', { connectionId }).then(r => r.data),
  getLogs: (connectionId?: string) => apiClient.get<ApiResponse<any[]>>('/integrations/logs', { params: { connectionId } }).then(r => r.data),
};

// ============================================================================
// TAX & CHARGE API
// ============================================================================

// Matches backend TaxConfiguration.TaxType (DB uses 'WHT' for withholding).
export type TaxType = 'VAT' | 'GST' | 'WHT' | 'SALES_TAX' | 'SERVICE_TAX' | 'STAMP_DUTY' | 'EXCISE' | string;

// ChargeType - The TYPE of charge (what it's for) - matches backend ChargeConfiguration.ChargeType
export type ChargeType =
  | 'SWIFT_FEE'              // SWIFT transfer fees
  | 'FX_FEE'                 // Foreign exchange fees
  | 'PROCESSING_FEE'         // Payment processing fees
  | 'URGENCY_FEE'            // Priority/urgency fees
  | 'SERVICE_FEE'            // General service fees
  | 'MAINTENANCE_FEE'        // Account maintenance
  | 'TRANSACTION_FEE'        // Per-transaction fees
  | 'POBO_FEE'               // Pay On Behalf Of fee
  | 'ADMIN_FEE'              // Administrative fees
  | 'WALLET_TOPUP_FEE'       // Wallet top-up fee
  | 'WALLET_WITHDRAWAL_FEE'  // Wallet withdrawal fee
  | 'CARD_ISSUANCE_FEE'      // Virtual/physical card issuance
  | 'CARD_TRANSACTION_FEE'   // Card transaction fee
  | 'MERCHANT_FEE'           // Merchant processing fee
  | 'SETTLEMENT_FEE'         // Settlement/payout fee
  | 'REFUND_FEE'             // Refund processing fee
  | 'CHARGEBACK_FEE'         // Chargeback handling fee
  | 'NETTING_FEE'            // Netting cycle fee
  | 'POOLING_FEE'            // Notional pooling fee
  | 'IHB_FEE'                // In-house bank fee
  | 'OTHER';                 // Other charges

// ChargeCategory - HOW the charge is calculated - matches backend ChargeConfiguration.ChargeCategory
export type ChargeCategory = 'FIXED' | 'PERCENTAGE' | 'TIERED' | 'SLIDING_SCALE';

// ChargeStatus - Status of the charge configuration
export type ChargeStatus = 'ACTIVE' | 'INACTIVE' | 'PENDING' | 'SUPERSEDED';

// Mirrors backend TaxChargeDto.JurisdictionResponse.
export interface TaxJurisdiction {
  id: string;
  jurisdictionCode: string;
  jurisdictionName: string;
  countryCode: string;
  regionCode?: string;
  supportsVat?: boolean;
  supportsGst?: boolean;
  supportsWithholding?: boolean;
  supportsSalesTax?: boolean;
  taxAuthorityName?: string;
  reportingCurrency?: string;
  status: string;
  effectiveFrom?: string;
  effectiveTo?: string;
}

// Mirrors backend TaxChargeDto.TaxConfigResponse.
export interface TaxConfiguration {
  id: string;
  taxCode: string;
  taxName: string;
  description?: string;
  taxType: TaxType;
  jurisdictionCode: string;
  taxCategory?: string;
  ratePercentage: number;
  minimumAmount?: number;
  maximumAmount?: number;
  appliesToPayables?: boolean;
  appliesToReceivables?: boolean;
  isWithholding?: boolean;
  isRecoverable?: boolean;
  recoveryPercentage?: number;
  status: string;
  effectiveFrom?: string;
  effectiveTo?: string;
  createdAt?: string;
}

// Mirrors backend TaxChargeDto.ChargeConfigResponse.
export interface ChargeConfiguration {
  id: string;
  chargeCode: string;
  chargeName: string;
  description?: string;
  chargeType: ChargeType;
  chargeCategory: ChargeCategory;
  fixedAmount?: number;
  percentageRate?: number;
  currencyCode: string;
  minimumCharge?: number;
  maximumCharge?: number;
  tierConfig?: Array<Record<string, unknown>>;
  appliesToPaymentMethod?: string;
  appliesToPriority?: string;
  isCrossBorder?: boolean;
  isDomestic?: boolean;
  waiverThreshold?: number;
  waiverForVip?: boolean;
  status: ChargeStatus;
  effectiveFrom?: string;
  effectiveTo?: string;
  createdAt?: string;
}

// Mirrors backend TaxChargeDto.CalculateTaxResponse.
export interface TaxCalculationResult {
  calculationId?: string;
  taxCode: string;
  taxName: string;
  taxType: TaxType;
  baseAmount: number;
  taxRate: number;
  calculatedTax: number;
  recoverableTax?: number;
  isWithholding?: boolean;
  currencyCode: string;
  calculatedAt?: string;
}

// Mirrors backend TaxChargeDto.ChargeLineItem.
export interface ChargeLineItem {
  calculationId?: string;
  chargeCode: string;
  chargeName: string;
  chargeType: ChargeType;
  calculatedCharge: number;
  waivedAmount?: number;
  finalCharge: number;
  waiverReason?: string;
}

// Mirrors backend TaxChargeDto.CalculateChargesResponse.
export interface ChargeCalculationResult {
  referenceId?: string;
  baseAmount: number;
  charges: ChargeLineItem[];
  totalCharges: number;
  totalWaived?: number;
  netCharges: number;
  currencyCode: string;
  calculatedAt?: string;
}

// Wired to the real backend contract: base `/tax-charges` (plural),
// `/charge-configs`, `/tax-configs`, `/tax-configs/withholding`,
// `/jurisdictions`, `/calculate-tax`, `/calculate-charges`. (The previous
// `/tax-charge/*` paths/shapes never matched the controller — see
// tasks/todo.md root-cause note.)
export const taxChargeApi = {
  getJurisdictions: () =>
    apiClient.get<ApiResponse<TaxJurisdiction[]>>('/tax-charges/jurisdictions').then(r => r.data),
  getJurisdiction: (code: string) =>
    apiClient.get<ApiResponse<TaxJurisdiction>>(`/tax-charges/jurisdictions/${code}`).then(r => r.data),
  getTaxConfigs: () =>
    apiClient.get<ApiResponse<TaxConfiguration[]>>('/tax-charges/tax-configs').then(r => r.data),
  getTaxConfig: (taxCode: string) =>
    apiClient.get<ApiResponse<TaxConfiguration>>(`/tax-charges/tax-configs/${taxCode}`).then(r => r.data),
  getTaxConfigsByJurisdiction: (jurisdictionCode: string) =>
    apiClient.get<ApiResponse<TaxConfiguration[]>>(`/tax-charges/tax-configs/jurisdiction/${jurisdictionCode}`).then(r => r.data),
  getWithholdingTaxConfigs: () =>
    apiClient.get<ApiResponse<TaxConfiguration[]>>('/tax-charges/tax-configs/withholding').then(r => r.data),
  createTaxConfig: (data: Partial<TaxConfiguration>) =>
    apiClient.post<ApiResponse<TaxConfiguration>>('/tax-charges/tax-configs', data).then(r => r.data),
  getChargeConfigs: () =>
    apiClient.get<ApiResponse<ChargeConfiguration[]>>('/tax-charges/charge-configs').then(r => r.data),
  getChargeConfig: (chargeCode: string) =>
    apiClient.get<ApiResponse<ChargeConfiguration>>(`/tax-charges/charge-configs/${chargeCode}`).then(r => r.data),
  getChargeConfigsByType: (chargeType: ChargeType) =>
    apiClient.get<ApiResponse<ChargeConfiguration[]>>(`/tax-charges/charge-configs/type/${chargeType}`).then(r => r.data),
  createChargeConfig: (data: Partial<ChargeConfiguration>) =>
    apiClient.post<ApiResponse<ChargeConfiguration>>('/tax-charges/charge-configs', data).then(r => r.data),
  calculateTax: (request: { referenceId?: string; referenceType?: string; baseAmount: number; taxCode: string; jurisdictionCode?: string; isService?: boolean; currencyCode?: string }) =>
    apiClient.post<ApiResponse<TaxCalculationResult>>('/tax-charges/calculate-tax', request).then(r => r.data),
  calculateCharges: (request: { referenceId?: string; referenceType?: string; baseAmount: number; paymentMethod?: string; priority?: string; isCrossBorder?: boolean; isVipCustomer?: boolean; isBulkPayment?: boolean; currencyCode?: string }) =>
    apiClient.post<ApiResponse<ChargeCalculationResult>>('/tax-charges/calculate-charges', request).then(r => r.data),
};

// ============================================================================
// INTERCOMPANY API
// ============================================================================

export type TransactionType = 'POBO' | 'POBO_PAYMENT' | 'COBO' | 'COBO_COLLECTION' | 'SETTLEMENT' | 'INTEREST' | 'ADJUSTMENT' | 'IC_RECEIVABLE' | 'IC_PAYABLE';
export type IntercompanyStatus = 'PENDING' | 'ACTIVE' | 'PROCESSED' | 'COMPLETED' | 'SETTLED' | 'REVERSED' | 'FAILED';

export interface IntercompanyEntity {
  id: string;
  entityCode: string;
  entityName: string;
  entityType: 'HEADQUARTERS' | 'SUBSIDIARY' | 'BRANCH' | 'JOINT_VENTURE' | 'ASSOCIATE';
  currencyCode: string;
  creditLimit: number;
  creditUsed: number;
  creditAvailable: number;
  currentBalance: number;
  netPosition: number;
  pendingPobo: number;
  pendingCobo: number;
  ihbBalance: number;
  activeLoansCount: number;
  activeDepositsCount: number;
  interestRateLend: number;
  interestRateBorrow: number;
  lastSettlementDate?: string;
  status: 'ACTIVE' | 'INACTIVE' | 'SUSPENDED';
}

export interface EntityValidationResponse {
  valid: boolean;
  reason?: string;
  availableLimit: number;
  currentExposure: number;
  proposedExposure: number;
  utilizationPercent: number;
  warnings: string[];
}

// Note: PoboPreviewResponse is defined earlier in this file (around line 2368)
// with PoboChargeBreakdown type. This is an alias for backward compatibility.
export type PoboPreviewResponseLegacy = {
  payingEntity: { id: string; name: string; code: string };
  behalfEntity: { id: string; name: string; code: string };
  paymentAmount: number;
  currencyCode: string;
  charges: PoboChargeBreakdown[];
  totalCharges: number;
  netAmount: number;
  ihbLoanPreview: PoboIhbLoanPreview;
  warnings: string[];
};

// NOTE: IntercompanyTransaction interface is defined later in this file (around line 7217)
// with the complete set of fields. Do not duplicate here.

export const intercompanyApi = {
  getEntities: (status?: string, entityType?: string) =>
    apiClient.get<ApiResponse<IntercompanyEntity[]>>('/intercompany/entities', { params: { status, entityType } }).then(r => r.data),
  getEntity: (entityId: string) =>
    apiClient.get<ApiResponse<IntercompanyEntity>>(`/intercompany/entities/${entityId}`).then(r => r.data),
  getEntityPosition: (entityId: string) =>
    apiClient.get<ApiResponse<any>>(`/intercompany/entities/${entityId}/position`).then(r => r.data),
  validateEntity: (entityId: string, request: { transactionType: string; amount: number; currencyCode?: string }) =>
    apiClient.post<ApiResponse<EntityValidationResponse>>(`/intercompany/entities/${entityId}/validate`, request).then(r => r.data),
  previewPobo: (request: any) =>
    apiClient.post<ApiResponse<PoboPreviewResponse>>('/intercompany/pobo/preview', request).then(r => r.data),
  executePobo: (request: any) =>
    apiClient.post<ApiResponse<any>>('/intercompany/pobo/execute', request).then(r => r.data),
  getPoboTransactions: (entityId?: string, role?: string, status?: string) =>
    apiClient.get<ApiResponse<IntercompanyTransaction[]>>('/intercompany/pobo', { params: { entityId, role, status } }).then(r => r.data),
  setupCobo: (request: any) =>
    apiClient.post<ApiResponse<any>>('/intercompany/cobo/setup', request).then(r => r.data),
  processCoboCollection: (transactionRef: string, request: any) =>
    apiClient.post<ApiResponse<any>>(`/intercompany/cobo/${transactionRef}/collect`, request).then(r => r.data),
  getCoboTransactions: (entityId?: string, role?: string, status?: string) =>
    apiClient.get<ApiResponse<IntercompanyTransaction[]>>('/intercompany/cobo', { params: { entityId, role, status } }).then(r => r.data),
  getUnsettledTransactions: (entityId?: string) =>
    apiClient.get<ApiResponse<IntercompanyTransaction[]>>('/intercompany/settlements/unsettled', { params: { entityId } }).then(r => r.data),
  calculateNetSettlement: (entity1Id: string, entity2Id: string) =>
    apiClient.post<ApiResponse<any>>('/intercompany/settlements/calculate-net', { entity1Id, entity2Id }).then(r => r.data),
  executeSettlement: (transactionIds: string[], settlementDate: string, netAmount: boolean) =>
    apiClient.post<ApiResponse<any>>('/intercompany/settlements/execute', { transactionIds, settlementDate, netAmount }).then(r => r.data),
  getStats: (corporateId?: string) =>
    apiClient.get<ApiResponse<any>>('/intercompany/stats', { params: { corporateId } }).then(r => r.data),
};

// ============================================================================
// PARTIES API (Vendor/Customer Search)
// ============================================================================


// ============================================================================
// PARTY TYPES - Phase 1: POBO/IC Enhanced
// ============================================================================

export type PartyRole = 'CUSTOMER' | 'VENDOR' | 'EMPLOYEE' | 'GOVERNMENT' | 'FINANCIAL';
export type PartyType = 'INDIVIDUAL' | 'COMPANY' | 'GOVERNMENT' | 'FINANCIAL_INSTITUTION';
export type KycStatus = 'PENDING' | 'IN_PROGRESS' | 'VERIFIED' | 'EXPIRED' | 'REJECTED' | 'EXEMPTED';
export type RiskRating = 'LOW' | 'MEDIUM' | 'HIGH' | 'PROHIBITED';
export type SanctionsStatus = 'CLEAR' | 'POTENTIAL_MATCH' | 'FALSE_POSITIVE' | 'CONFIRMED_MATCH';
export type PartyStatus = 'ACTIVE' | 'SUSPENDED' | 'BLOCKED' | 'INACTIVE';
export type IcSettlementMethod = 'NETTING' | 'DIRECT_TRANSFER' | 'IHB' | 'MANUAL';

export interface Party {
  id: string;
  partyCode: string;
  partyType: PartyType;
  legalName: string;
  displayName?: string;
  tradeName?: string;
  roles: PartyRole[];
  taxId?: string;
  registrationNumber?: string;
  registrationCountry: string;
  contactName?: string;
  contactEmail?: string;
  contactPhone?: string;
  addressLine1?: string;
  addressLine2?: string;
  city?: string;
  state?: string;
  postalCode?: string;
  country: string;
  ecommerceEnabled: boolean;
  ecommercePlatforms?: string[];
  employeeId?: string;
  department?: string;
  kycStatus: KycStatus;
  kycExpiresAt?: string;
  riskRating: RiskRating;
  riskScore?: number;
  sanctionsStatus: SanctionsStatus;
  pepStatus: boolean;
  adverseMediaStatus: boolean;
  status: PartyStatus;
  bankAccountsCount: number;
  documentsCount: number;
  lastTransactionAt?: string;
  createdAt: string;
  onboardedAt?: string;
  
  // Phase 1: POBO/IC Fields
  owningEntityId?: string;
  owningEntityCode?: string;
  poboEligible?: boolean;
  poboDefaultPayerEntityId?: string;
  poboDefaultPayerEntityCode?: string;
  isIntercompany?: boolean;
  linkedLegalEntityId?: string;
  linkedLegalEntityCode?: string;
  nettingEligible?: boolean;
  icSettlementMethod?: IcSettlementMethod;
  icCreditLimit?: number;
  icCurrentExposure?: number;
  icCurrency?: string;
  icAvailableCredit?: number;
  icCreditUtilizationPercent?: number;
  canReceivePoboPayment?: boolean;
  canParticipateInNetting?: boolean;
}

export interface PartyBankAccount {
  id: string;
  label: string;
  holderName: string;
  bankName: string;
  bankCode?: string;
  iban?: string;
  accountNumber?: string;
  routingNumber?: string;
  currency: string;
  isPrimary: boolean;
  isVerified: boolean;
  verifiedAt?: string;
  status: 'ACTIVE' | 'SUSPENDED';
  supportedPaymentMethods?: string[];
  poboEnabled?: boolean;
}

export interface PartyDocument {
  id: string;
  documentType: string;
  category: string;
  name: string;
  documentNumber?: string;
  issueDate?: string;
  expiryDate?: string;
  issuingAuthority?: string;
  verificationStatus: 'PENDING' | 'VERIFIED' | 'REJECTED' | 'EXPIRED';
  fileType: string;
  fileSize?: number;
  isExpired?: boolean;
}

export interface PartyStats {
  totalParties: number;
  customers: number;
  vendors: number;
  employees: number;
  government: number;
  financial: number;
  kycPending: number;
  kycExpired: number;
  highRisk: number;
  sanctionsAlerts: number;
  // Phase 1: POBO/IC stats
  poboEligibleVendors: number;
  intercompanyParties: number;
  nettingEligibleParties: number;
}

export interface PartyListResponse {
  parties: Party[];
  totalCount: number;
  page: number;
  pageSize: number;
  stats: PartyStats;
}

export interface PartyDetailResponse {
  party: Party;
  bankAccounts: PartyBankAccount[];
  documents: PartyDocument[];
  compliance: any;
  activityLog: any[];
}

export interface PartySearchRequest {
  corporateId?: string;
  query?: string;
  partyType?: PartyType;
  role?: PartyRole;
  kycStatus?: KycStatus;
  riskRating?: RiskRating;
  status?: PartyStatus;
  page?: number;
  pageSize?: number;
  sortBy?: string;
  sortOrder?: 'asc' | 'desc';
  // Phase 1: Additional filters
  owningEntityId?: string;
  poboEligibleOnly?: boolean;
  intercompanyOnly?: boolean;
  nettingEligibleOnly?: boolean;
}

export interface UpdatePoboEligibilityRequest {
  poboEligible: boolean;
  defaultPayerEntityId?: string;
  defaultPayerEntityCode?: string;
  approvalNotes?: string;
}

export interface ValidatePoboRequest {
  partyId: string;
  payingEntityId?: string;
  amount?: number;
  currencyCode?: string;
}

export interface ValidatePoboResponse {
  isValid: boolean;
  reason: string;
  partyStatus?: string;
  kycStatus?: string;
  poboEligible?: boolean;
  availableCredit?: number;
  warnings?: string[];
}

export interface UpdateIntercompanyConfigRequest {
  isIntercompany: boolean;
  linkedLegalEntityId?: string;
  linkedLegalEntityCode?: string;
  nettingEligible?: boolean;
  icSettlementMethod?: IcSettlementMethod;
}

export interface UpdateIcCreditLimitRequest {
  creditLimit: number;
  currency: string;
  approvalNotes?: string;
}

export interface CreateIntercompanyPartyRequest {
  owningEntityId: string;
  owningEntityCode: string;
  linkedLegalEntityId: string;
  linkedLegalEntityCode: string;
  linkedLegalEntityName: string;
  roles: PartyRole[];
  icSettlementMethod?: IcSettlementMethod;
  icCreditLimit?: number;
  icCurrency?: string;
}

// Legacy interface for backward compatibility
export interface PartySearchResult {
  id: string;
  partyCode: string;
  partyName: string;
  partyType: string;
  role: string;
  taxNumber?: string;
  status: string;
  bankAccounts?: Array<{
    id: string;
    bankName: string;
    accountNumber: string;
    iban?: string;
    swiftCode?: string;
    currencyCode: string;
    isPrimary: boolean;
  }>;
}

// ============================================================================
// PARTIES API - Phase 1: POBO/IC Enhanced
// ============================================================================

export const partiesApi = {
  // ==========================================================================
  // CRUD Operations
  // ==========================================================================

  /** Get all parties with filters (Phase 1 enhanced) */
  getAll: (params?: PartySearchRequest) => {
    const { corporateId, ...queryParams } = params || {};
    const headers: Record<string, string> = {};
    if (corporateId) {
      headers['X-Corporate-Id'] = corporateId;
    }
    return apiClient.get<PartyListResponse>('/parties', { params: queryParams, headers }).then(r => {
      // Backend returns PartyListResponse directly (not wrapped in ApiResponse)
      const data = r.data as any;
      // Return the data directly - it already has { parties, stats, totalCount, page, pageSize }
      return data;
    });
  },

  /** Legacy search method for backward compatibility */
  search: (query?: string, role?: string, status?: string, page = 0, size = 20, corporateId?: string) => {
    const headers: Record<string, string> = {};
    if (corporateId) {
      headers['X-Corporate-Id'] = corporateId;
    }
    return apiClient.get<PartyListResponse>('/parties', {
      params: { query, role, status, page, pageSize: size },
      headers
    }).then(r => r.data);
  },

  /** Get party by ID */
  getById: (partyId: string) =>
    apiClient.get<Party>(`/parties/${partyId}`).then(r => {
      // Backend returns PartyResponse directly
      const party = r.data as any;
      if (party && party.id) {
        return { success: true, data: party as Party };
      }
      return r.data as any;
    }),

  /** Get party detail with bank accounts, documents, compliance */
  getDetail: (partyId: string) =>
    apiClient.get<PartyDetailResponse>(`/parties/${partyId}/detail`).then(r => {
      // Backend returns PartyDetailResponse directly
      const detail = r.data as any;
      if (detail && (detail.party || detail.bankAccounts !== undefined)) {
        return detail;
      }
      return r.data;
    }),

  /** Get party statistics */
  getStats: (corporateId?: string) => {
    const headers: Record<string, string> = {};
    if (corporateId) {
      headers['X-Corporate-Id'] = corporateId;
    }
    return apiClient.get<ApiResponse<PartyStats>>('/parties/stats', { headers }).then(r => r.data);
  },

  /** Create new party */
  create: (data: Partial<Party>, corporateId?: string) => {
    const headers: Record<string, string> = {};
    if (corporateId) {
      headers['X-Corporate-Id'] = corporateId;
    }
    return apiClient.post<Party>('/parties', data, { headers }).then(r => {
      // Backend returns PartyResponse directly, wrap in ApiResponse format for consistency
      const party = r.data as any;
      if (party && party.id) {
        return { success: true, data: party as Party };
      }
      return r.data as any;
    });
  },

  /** Update party */
  update: (partyId: string, data: Partial<Party>) =>
    apiClient.put<Party>(`/parties/${partyId}`, data).then(r => {
      // Backend returns PartyResponse directly, wrap in ApiResponse format for consistency
      const party = r.data as any;
      if (party && party.id) {
        return { success: true, data: party as Party };
      }
      return r.data as any;
    }),

  /** Delete (deactivate) party */
  delete: (partyId: string) =>
    apiClient.delete<ApiResponse<void>>(`/parties/${partyId}`).then(r => r.data),

  // ==========================================================================
  // Bank Accounts
  // ==========================================================================

  getBankAccounts: (partyId: string) =>
    apiClient.get<PartyBankAccount[]>(`/parties/${partyId}/bank-accounts`).then(r => {
      // Backend returns List<BankAccountResponse> directly
      const accounts = r.data as any;
      if (Array.isArray(accounts)) {
        return { success: true, data: accounts };
      }
      return accounts;
    }),

  addBankAccount: (partyId: string, account: Partial<PartyBankAccount>) =>
    apiClient.post<PartyBankAccount>(`/parties/${partyId}/bank-accounts`, account).then(r => {
      // Backend returns BankAccountResponse directly
      const ba = r.data as any;
      if (ba && ba.id) {
        return { success: true, data: ba };
      }
      return ba;
    }),

  deleteBankAccount: (accountId: string) =>
    apiClient.delete<void>(`/parties/bank-accounts/${accountId}`).then(() => ({ success: true })),

  verifyBankAccount: (accountId: string, verifiedBy: string) =>
    apiClient.post<PartyBankAccount>(`/parties/bank-accounts/${accountId}/verify`, null, { params: { verifiedBy } }).then(r => {
      const ba = r.data as any;
      if (ba && ba.id) {
        return { success: true, data: ba };
      }
      return ba;
    }),

  // ==========================================================================
  // Documents
  // ==========================================================================

  getDocuments: (partyId: string) =>
    apiClient.get<PartyDocument[]>(`/parties/${partyId}/documents`).then(r => {
      // Backend returns List<DocumentResponse> directly
      const docs = r.data as any;
      if (Array.isArray(docs)) {
        return { success: true, data: docs };
      }
      return docs;
    }),

  uploadDocument: (partyId: string, document: Partial<PartyDocument>) =>
    apiClient.post<PartyDocument>(`/parties/${partyId}/documents`, document).then(r => {
      const doc = r.data as any;
      if (doc && doc.id) {
        return { success: true, data: doc };
      }
      return doc;
    }),

  verifyDocument: (documentId: string, status: 'VERIFIED' | 'REJECTED', rejectionReason?: string) =>
    apiClient.post<PartyDocument>(`/parties/documents/${documentId}/verify`, { status, rejectionReason }).then(r => {
      const doc = r.data as any;
      if (doc && doc.id) {
        return { success: true, data: doc };
      }
      return doc;
    }),

  deleteDocument: (documentId: string) =>
    apiClient.delete<void>(`/parties/documents/${documentId}`).then(() => ({ success: true })),

  // ==========================================================================
  // Compliance
  // ==========================================================================

  updateKycStatus: (partyId: string, kycStatus: string, kycExpiresAt?: string) =>
    apiClient.put<Party>(`/parties/${partyId}/kyc`, { kycStatus, kycExpiresAt }).then(r => {
      const party = r.data as any;
      if (party && party.id) {
        return { success: true, data: party as Party };
      }
      return party;
    }),

  updateRiskRating: (partyId: string, riskRating: string, riskScore?: number) =>
    apiClient.put<Party>(`/parties/${partyId}/risk`, { riskRating, riskScore }).then(r => {
      const party = r.data as any;
      if (party && party.id) {
        return { success: true, data: party as Party };
      }
      return party;
    }),

  runScreening: (partyId: string, options: { runSanctions?: boolean; runPep?: boolean; runAdverseMedia?: boolean }) =>
    apiClient.post<any>(`/parties/${partyId}/screening`, options).then(r => {
      const result = r.data as any;
      return { success: true, data: result };
    }),

  // ==========================================================================
  // Phase 1: POBO Eligibility
  // ==========================================================================

  /** Get all POBO-eligible vendors */
  getPoboEligibleVendors: (corporateId?: string) => {
    const headers: Record<string, string> = {};
    if (corporateId) headers['X-Corporate-Id'] = corporateId;
    return apiClient.get<ApiResponse<Party[]>>('/parties/pobo-eligible', { headers }).then(r => r.data);
  },

  /** Get POBO-eligible vendors by owning entity */
  getPoboEligibleVendorsByEntity: (owningEntityId: string) =>
    apiClient.get<ApiResponse<Party[]>>(`/parties/pobo-eligible/by-entity/${owningEntityId}`).then(r => r.data),

  /** Update POBO eligibility for a party */
  updatePoboEligibility: (partyId: string, request: UpdatePoboEligibilityRequest) =>
    apiClient.put<ApiResponse<Party>>(`/parties/${partyId}/pobo-eligibility`, request).then(r => r.data),

  /** Validate POBO eligibility for a payment */
  validatePoboEligibility: (request: ValidatePoboRequest) =>
    apiClient.post<ApiResponse<ValidatePoboResponse>>('/parties/pobo/validate', request).then(r => r.data),

  // ==========================================================================
  // Phase 1: Intercompany Parties
  // ==========================================================================

  /** Get all intercompany parties */
  getIntercompanyParties: (corporateId?: string) => {
    const headers: Record<string, string> = {};
    if (corporateId) headers['X-Corporate-Id'] = corporateId;
    return apiClient.get<ApiResponse<Party[]>>('/parties/intercompany', { headers }).then(r => r.data);
  },

  /** Get intercompany parties by owning entity */
  getIntercompanyPartiesByEntity: (owningEntityId: string) =>
    apiClient.get<ApiResponse<Party[]>>(`/parties/intercompany/by-entity/${owningEntityId}`).then(r => r.data),

  /** Create an intercompany party */
  createIntercompanyParty: (request: CreateIntercompanyPartyRequest, corporateId?: string) => {
    const headers: Record<string, string> = {};
    if (corporateId) headers['X-Corporate-Id'] = corporateId;
    return apiClient.post<ApiResponse<Party>>('/parties/intercompany', request, { headers }).then(r => r.data);
  },

  /** Update intercompany configuration */
  updateIntercompanyConfig: (partyId: string, request: UpdateIntercompanyConfigRequest) =>
    apiClient.put<Party>(`/parties/${partyId}/intercompany-config`, request).then(r => {
      const party = r.data as any;
      if (party && party.id) {
        return { success: true, data: party as Party };
      }
      return party;
    }),

  /** Update IC credit limit */
  updateIcCreditLimit: (partyId: string, request: UpdateIcCreditLimitRequest) =>
    apiClient.put<Party>(`/parties/${partyId}/ic-credit-limit`, request).then(r => {
      const party = r.data as any;
      if (party && party.id) {
        return { success: true, data: party as Party };
      }
      return party;
    }),

  // ==========================================================================
  // Phase 1: Netting Eligibility
  // ==========================================================================

  /** Get all netting-eligible parties */
  getNettingEligibleParties: (corporateId?: string) => {
    const headers: Record<string, string> = {};
    if (corporateId) headers['X-Corporate-Id'] = corporateId;
    return apiClient.get<ApiResponse<Party[]>>('/parties/netting-eligible', { headers }).then(r => r.data);
  },

  /** Get netting-eligible parties by owning entity */
  getNettingEligiblePartiesByEntity: (owningEntityId: string) =>
    apiClient.get<ApiResponse<Party[]>>(`/parties/netting-eligible/by-entity/${owningEntityId}`).then(r => r.data),

  // ==========================================================================
  // Phase 1: Entity Context
  // ==========================================================================

  /** Get parties by owning entity */
  getPartiesByEntity: (owningEntityId: string) =>
    apiClient.get<ApiResponse<Party[]>>(`/parties/by-entity/${owningEntityId}`).then(r => r.data),

  /** Get vendors by owning entity (paginated) */
  getVendorsByEntity: (owningEntityId: string, page = 0, size = 20) =>
    apiClient.get<ApiResponse<PaginatedResponse<Party>>>(`/parties/vendors/by-entity/${owningEntityId}`, { params: { page, size } }).then(r => r.data),

  /** Get customers by owning entity (paginated) */
  getCustomersByEntity: (owningEntityId: string, page = 0, size = 20) =>
    apiClient.get<ApiResponse<PaginatedResponse<Party>>>(`/parties/customers/by-entity/${owningEntityId}`, { params: { page, size } }).then(r => r.data),
};


export interface HierarchyNode {
  id: string;
  nodeCode: string;
  nodeName: string;
  nodeType: string;
  level: number;
  parentId?: string;
  path: string;
  children?: HierarchyNode[];
  accountCount: number;
  totalBalance: number;
}

export const hierarchyApi = {
  getTree: (programId?: string) =>
    apiClient.get<ApiResponse<HierarchyNode>>('/hierarchy/tree', { params: { programId } }).then(r => r.data),
  searchNodes: (query: string, nodeType?: string) =>
    apiClient.get<ApiResponse<HierarchyNode[]>>('/hierarchy/search', { params: { query, nodeType } }).then(r => r.data),
  getNode: (nodeId: string) =>
    apiClient.get<ApiResponse<HierarchyNode>>(`/hierarchy/nodes/${nodeId}`).then(r => r.data),
  getChildren: (nodeId: string) =>
    apiClient.get<ApiResponse<HierarchyNode[]>>(`/hierarchy/nodes/${nodeId}/children`).then(r => r.data),
};

// ============================================================================
// POBO API (Direct POBO Controller endpoints)
// ============================================================================

export interface PoboAuthorization {
  id: string;
  authorizationCode: string;
  payerEntityId: string;
  payerEntityCode: string;
  payerEntityName: string;
  behalfEntityId: string;
  behalfEntityCode: string;
  behalfEntityName: string;
  currencyCode: string;
  singleTransactionLimit: number;
  dailyLimit: number;
  monthlyLimit: number;
  dailyUtilized: number;
  monthlyUtilized: number;
  status: 'ACTIVE' | 'SUSPENDED' | 'EXPIRED';
  effectiveFrom: string;
  effectiveTo?: string;
}

export interface IntercompanyRecharge {
  id: string;
  rechargeReference: string;
  payerEntityId: string;
  payerEntityCode: string;
  payerEntityName: string;
  behalfEntityId: string;
  behalfEntityCode: string;
  behalfEntityName: string;
  totalRecharge: number;
  currencyCode: string;
  status: 'PENDING' | 'APPROVED' | 'RECHARGED' | 'SETTLED' | 'DISPUTED' | 'CANCELLED';
  settlementMethod?: 'IHB_LOAN' | 'NETTING' | 'DIRECT_PAYMENT' | 'OFFSET';
  settlementReference?: string;
  settlementDate?: string;
  approvedBy?: string;
  approvedAt?: string;
  rejectionReason?: string;
  createdAt: string;
}

export const poboApi = {
  getAuthorizations: () =>
    apiClient.get<ApiResponse<PoboAuthorization[]>>('/pobo/authorizations').then(r => r.data),
  getAuthorization: (id: string) =>
    apiClient.get<ApiResponse<PoboAuthorization>>(`/pobo/authorizations/${id}`).then(r => r.data),
  getAuthorizationsForPayer: (payerEntityId: string) =>
    apiClient.get<ApiResponse<PoboAuthorization[]>>(`/pobo/authorizations/payer/${payerEntityId}`).then(r => r.data),
  createAuthorization: (data: any) =>
    apiClient.post<ApiResponse<PoboAuthorization>>('/pobo/authorizations', data).then(r => r.data),
  validatePayment: (request: { payerEntityId: string; behalfEntityId: string; amount: number; currencyCode: string }) =>
    apiClient.post<ApiResponse<{ valid: boolean; reason?: string; warnings: string[] }>>('/pobo/validate', request).then(r => r.data),
  processPayment: (request: any) =>
    apiClient.post<ApiResponse<any>>('/pobo/payments', request).then(r => r.data),
  getStats: () =>
    apiClient.get<ApiResponse<any>>('/pobo/stats').then(r => r.data),
  getRecharges: () =>
    apiClient.get<ApiResponse<any[]>>('/pobo/recharges').then(r => r.data),
  getPendingRecharges: () =>
    apiClient.get<ApiResponse<any[]>>('/pobo/recharges/pending').then(r => r.data),
  createRecharge: (data: any) =>
    apiClient.post<ApiResponse<any>>('/pobo/recharges', data).then(r => r.data),
  approveRecharge: (id: string, approver: string) =>
    apiClient.post<ApiResponse<any>>(`/pobo/recharges/${id}/approve`, null, { params: { approver } }).then(r => r.data),
  rejectRecharge: (id: string, rejector: string, reason?: string) =>
    apiClient.post<ApiResponse<any>>(`/pobo/recharges/${id}/reject`, null, { params: { rejector, reason } }).then(r => r.data),
  settleViaIhbLoan: (id: string, ihbLoanId: string, loanReference: string) =>
    apiClient.post<ApiResponse<any>>(`/pobo/recharges/${id}/settle/ihb-loan`, null, { params: { ihbLoanId, loanReference } }).then(r => r.data),
};

export type VaSpecialType = 'REGULAR' | 'SETTLEMENT' | 'EXCEPTION';

export interface SettlementVa {
  id: string;
  vaNumber: string;
  vaName: string;
  specialType: VaSpecialType;
  programId: string;
  programName?: string;
  currencyCode: string;
  currentBalance: number;
  availableBalance: number;
  hierarchyNodeId?: string;
  hierarchyPath?: string;
  hierarchyLevel?: number;
  status: string;
  coveredVaCount?: number;
  todayCredits?: number;
  mtdCredits?: number;
  createdAt: string;
  updatedAt?: string;
}

export interface SettlementVaSummary {
  totalSettlementVas: number;
  totalExceptionVas: number;
  totalSettlementBalance: number;
  totalExceptionBalance: number;
}

export interface SettlementVaListResponse {
  settlementVas: SettlementVa[];
  exceptionVas: SettlementVa[];
  summary: SettlementVaSummary;
}

export interface SettlementVaDetailResponse {
  va: SettlementVa;
  recentTransactions: SettlementVaTransaction[];
  coveredVas?: CoveredVa[];
}

export interface SettlementVaTransaction {
  id: string;
  referenceNumber: string;
  movementType: string;
  amount: number;
  currency: string;
  balanceAfter: number;
  sourceVaNumber?: string;
  description?: string;
  correlationId?: string;
  transactionDate: string;
}

export interface CoveredVa {
  vaId: string;
  vaNumber: string;
  vaName: string;
  hierarchyPath?: string;
}

export interface CreateSettlementVaRequest {
  programId: string;
  parentNodeId: string;
  currency: string;
  vaName?: string;
  description?: string;
}

export interface InitializeHierarchyRequest {
  programId: string;
  currencies?: string[];
}

export interface ResolveSettlementVaRequest {
  sourceVaId: string;
}

export interface ResolveSettlementVaResponse {
  settlementVa: SettlementVa;
  resolutionPath: string;
  isExceptionFallback: boolean;
}

// ============================================================================
// EXCEPTION TRANSACTION TYPES
// ============================================================================

export type ExceptionStatus = 'OPEN' | 'IN_PROGRESS' | 'RESOLVED' | 'WRITTEN_OFF' | 'REVERSED';
export type ExceptionType = 
  | 'UNMATCHED_PAYMENT' 
  | 'MISSING_SETTLEMENT_VA' 
  | 'BANK_INTEREST' 
  | 'FX_DIFFERENCE' 
  | 'CHARGE_REVERSAL'
  | 'MANUAL_ADJUSTMENT'
  | 'OTHER';

export interface ExceptionTransaction {
  id: string;
  exceptionNumber: string;
  exceptionType: ExceptionType;
  exceptionVaId: string;
  exceptionVaNumber?: string;
  sourceVaId?: string;
  sourceVaNumber?: string;
  sourceTransactionId?: string;
  sourceTransactionRef?: string;
  amount: number;
  currencyCode: string;
  status: ExceptionStatus;
  remitterName?: string;
  remitterReference?: string;
  remitterBank?: string;
  remitterAccount?: string;
  bankReference?: string;
  valueDate?: string;
  notes?: string;
  allocatedToVaId?: string;
  allocatedToVaNumber?: string;
  allocatedAt?: string;
  allocatedBy?: string;
  allocationNotes?: string;
  writtenOffAt?: string;
  writtenOffBy?: string;
  writeOffReason?: string;
  reversedAt?: string;
  reversedBy?: string;
  reversalReason?: string;
  investigatedBy?: string;
  investigatedAt?: string;
  investigationNotes?: string;
  createdAt: string;
  updatedAt?: string;
}

export interface ExceptionSummary {
  openCount: number;
  inProgressCount: number;
  resolvedCount: number;
  writtenOffCount: number;
  reversedCount: number;
  openAmount: number;
  inProgressAmount: number;
  resolvedAmount: number;
  writtenOffAmount: number;
  agedOver30Days: number;
  agedOver60Days: number;
  agedOver90Days: number;
  todayCreated: number;
  todayResolved: number;
}

export interface ExceptionFilters {
  status?: ExceptionStatus;
  type?: ExceptionType;
  exceptionVaId?: string;
  dateFrom?: string;
  dateTo?: string;
  minAmount?: number;
  maxAmount?: number;
  page?: number;
  size?: number;
}

export interface ExceptionListResponse {
  content: ExceptionTransaction[];
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
}

export interface InvestigateExceptionRequest {
  notes: string;
  investigatedBy?: string;
}

export interface AllocateExceptionRequest {
  targetVaId: string;
  notes?: string;
  allocatedBy?: string;
}

export interface ReverseExceptionRequest {
  reason: string;
  reversedBy?: string;
}

export interface WriteOffExceptionRequest {
  reason: string;
  approvedBy: string;
  comments?: string;
}

export interface ExceptionTimelineEntry {
  timestamp: string;
  action: string;
  actor?: string;
  details?: string;
}

// ============================================================================
// SETTLEMENT VA API
// ============================================================================

export const settlementVaApi = {
  /**
   * Get all Settlement and Exception VAs for a program
   */
  getAll: async (programId: string): Promise<ApiResponse<SettlementVaListResponse>> => {
    return apiClient.get<ApiResponse<SettlementVaListResponse>>(
      '/treasury/settlement-vas',
      { params: { programId } }
    ).then(r => r.data);
  },

  /**
   * Get Settlement VA by ID with details
   */
  getById: async (vaId: string): Promise<ApiResponse<SettlementVaDetailResponse>> => {
    return apiClient.get<ApiResponse<SettlementVaDetailResponse>>(
      `/treasury/settlement-vas/${vaId}`
    ).then(r => r.data);
  },

  /**
   * Create a new Settlement VA at a hierarchy level
   */
  create: async (request: CreateSettlementVaRequest): Promise<ApiResponse<SettlementVa>> => {
    return apiClient.post<ApiResponse<SettlementVa>>(
      '/treasury/settlement-vas',
      request
    ).then(r => r.data);
  },

  /**
   * Initialize program hierarchy with Exception VA
   */
  initialize: async (request: InitializeHierarchyRequest): Promise<ApiResponse<SettlementVaListResponse>> => {
    return apiClient.post<ApiResponse<SettlementVaListResponse>>(
      '/treasury/settlement-vas/initialize',
      request
    ).then(r => r.data);
  },

  /**
   * Resolve which Settlement VA should receive fees from a source VA
   */
  resolve: async (request: ResolveSettlementVaRequest): Promise<ApiResponse<ResolveSettlementVaResponse>> => {
    return apiClient.post<ApiResponse<ResolveSettlementVaResponse>>(
      '/treasury/settlement-vas/resolve',
      request
    ).then(r => r.data);
  },

  /**
   * Get transactions for a Settlement VA
   */
  getTransactions: async (
    vaId: string, 
    page: number = 0, 
    size: number = 20
  ): Promise<ApiResponse<PaginatedResponse<SettlementVaTransaction>>> => {
    return apiClient.get<ApiResponse<PaginatedResponse<SettlementVaTransaction>>>(
      `/treasury/settlement-vas/${vaId}/transactions`,
      { params: { page, size } }
    ).then(r => r.data);
  },

  /**
   * Delete a Settlement VA (only if zero balance)
   */
  delete: async (vaId: string): Promise<ApiResponse<void>> => {
    return apiClient.delete<ApiResponse<void>>(
      `/treasury/settlement-vas/${vaId}`
    ).then(r => r.data);
  },
};

// ============================================================================
// EXCEPTION TRANSACTION API
// ============================================================================

export const exceptionApi = {
  /**
   * Get all exception transactions with filters
   */
  getAll: async (filters: ExceptionFilters = {}): Promise<ApiResponse<ExceptionListResponse>> => {
    const params = new URLSearchParams();
    if (filters.status) params.append('status', filters.status);
    if (filters.type) params.append('type', filters.type);
    if (filters.exceptionVaId) params.append('exceptionVaId', filters.exceptionVaId);
    if (filters.dateFrom) params.append('dateFrom', filters.dateFrom);
    if (filters.dateTo) params.append('dateTo', filters.dateTo);
    if (filters.minAmount !== undefined) params.append('minAmount', filters.minAmount.toString());
    if (filters.maxAmount !== undefined) params.append('maxAmount', filters.maxAmount.toString());
    if (filters.page !== undefined) params.append('page', filters.page.toString());
    if (filters.size !== undefined) params.append('size', filters.size.toString());
    
    return apiClient.get<ApiResponse<ExceptionListResponse>>(
      `/treasury/exceptions?${params.toString()}`
    ).then(r => r.data);
  },

  /**
   * Get exception transaction by ID
   */
  getById: async (exceptionId: string): Promise<ApiResponse<ExceptionTransaction>> => {
    return apiClient.get<ApiResponse<ExceptionTransaction>>(
      `/treasury/exceptions/${exceptionId}`
    ).then(r => r.data);
  },

  /**
   * Get exception summary statistics
   */
  getSummary: async (programId?: string): Promise<ApiResponse<ExceptionSummary>> => {
    return apiClient.get<ApiResponse<ExceptionSummary>>(
      '/treasury/exceptions/summary',
      { params: programId ? { programId } : {} }
    ).then(r => r.data);
  },

  /**
   * Get timeline/history for an exception
   */
  getTimeline: async (exceptionId: string): Promise<ApiResponse<ExceptionTimelineEntry[]>> => {
    return apiClient.get<ApiResponse<ExceptionTimelineEntry[]>>(
      `/treasury/exceptions/${exceptionId}/timeline`
    ).then(r => r.data);
  },

  /**
   * Start investigation on an exception
   */
  investigate: async (
    exceptionId: string, 
    request: InvestigateExceptionRequest
  ): Promise<ApiResponse<ExceptionTransaction>> => {
    return apiClient.post<ApiResponse<ExceptionTransaction>>(
      `/treasury/exceptions/${exceptionId}/investigate`,
      request
    ).then(r => r.data);
  },

  /**
   * Allocate exception to a target VA
   */
  allocate: async (
    exceptionId: string, 
    request: AllocateExceptionRequest
  ): Promise<ApiResponse<ExceptionTransaction>> => {
    return apiClient.post<ApiResponse<ExceptionTransaction>>(
      `/treasury/exceptions/${exceptionId}/allocate`,
      request
    ).then(r => r.data);
  },

  /**
   * Reverse an exception transaction
   */
  reverse: async (
    exceptionId: string, 
    request: ReverseExceptionRequest
  ): Promise<ApiResponse<ExceptionTransaction>> => {
    return apiClient.post<ApiResponse<ExceptionTransaction>>(
      `/treasury/exceptions/${exceptionId}/reverse`,
      request
    ).then(r => r.data);
  },

  /**
   * Write off an exception transaction
   */
  writeOff: async (
    exceptionId: string, 
    request: WriteOffExceptionRequest
  ): Promise<ApiResponse<ExceptionTransaction>> => {
    return apiClient.post<ApiResponse<ExceptionTransaction>>(
      `/treasury/exceptions/${exceptionId}/write-off`,
      request
    ).then(r => r.data);
  },

  /**
   * Search for suggested target VAs based on exception remitter info
   */
  getSuggestedTargets: async (exceptionId: string): Promise<ApiResponse<CoveredVa[]>> => {
    return apiClient.get<ApiResponse<CoveredVa[]>>(
      `/treasury/exceptions/${exceptionId}/suggested-targets`
    ).then(r => r.data);
  },
};

// ============================================================================
// PROGRAMS API
// ============================================================================

export interface Program {
  id: string;
  programCode: string;
  programName: string;
  programType?: 'COLLECTION' | 'PAYMENT' | 'TREASURY' | 'WALLET' | 'ESCROW';
  description?: string;
  currencyCode: string;
  status: 'ACTIVE' | 'INACTIVE' | 'SUSPENDED';
  vibanEnabled: boolean;
  hierarchyEnabled: boolean;
  createdAt: string;
  updatedAt?: string;
}

export const programsApi = {
  // FIXED: corporateId must be sent as X-Corporate-Id header per backend ProgramController
  getAll: (params?: { corporateId?: string; status?: string; page?: number; size?: number }) => {
    const { corporateId, ...queryParams } = params || {};
    const headers: Record<string, string> = {};
    if (corporateId) {
      headers['X-Corporate-Id'] = corporateId;
    }
    return apiClient.get<ApiResponse<any>>("/programs", { 
      params: { page: queryParams.page || 0, size: queryParams.size || 100, status: queryParams.status },
      headers 
    }).then(r => r.data);
  },
  getById: (programId: string) => 
    apiClient.get<ApiResponse<Program>>(`/programs/${programId}`).then(r => r.data),
  create: (data: Partial<Program>, corporateId?: string) => {
    const headers: Record<string, string> = {};
    if (corporateId) headers['X-Corporate-Id'] = corporateId;
    return apiClient.post<ApiResponse<Program>>("/programs", data, { headers }).then(r => r.data);
  },
  update: (programId: string, data: Partial<Program>) => 
    apiClient.put<ApiResponse<Program>>(`/programs/${programId}`, data).then(r => r.data),
  delete: (programId: string) => 
    apiClient.delete<ApiResponse<void>>(`/programs/${programId}`).then(r => r.data),
  getByCode: (code: string) => 
    apiClient.get<ApiResponse<Program>>(`/programs/code/${code}`).then(r => r.data),
  getByCorporate: (corporateId: string) => 
    apiClient.get<ApiResponse<any>>("/programs", { 
      headers: { 'X-Corporate-Id': corporateId } 
    }).then(r => r.data),
};


// ============================================================================
// EXPORT ALL APIs
// ============================================================================
// ============================================================================
// TREASURY SERVICES API - UNIFIED ARCHITECTURE v4.2
// Add this section before the "EXPORT ALL APIs" section in api.ts
// ============================================================================

// ============================================================================
// FX RATE API
// ============================================================================

export interface FxRate {
  id: string;
  fromCurrency: string;
  toCurrency: string;
  rate: number;
  inverseRate?: number;
  bidRate?: number;
  askRate?: number;
  midRate?: number;
  spreadBps?: number;
  rateDate: string;
  rateTimestamp: string;
  rateType: 'SPOT' | 'FORWARD' | 'FIXING' | 'INTERNAL' | 'CONTRACT' | 'INDICATIVE';
  rateSource: 'REUTERS' | 'BLOOMBERG' | 'ECB' | 'FED' | 'BOE' | 'BOJ' | 'CBS' | 'SWIFT' | 'API' | 'MANUAL';
  isActive: boolean;
  effectiveFrom?: string;
  effectiveTo?: string;
}

export interface FxConversionResult {
  originalAmount: number;
  fromCurrency: string;
  toCurrency: string;
  rate: number;
  rateType: string;
  convertedAmount: number;
}

export const fxRateApi = {
  getRate: (fromCurrency: string, toCurrency: string, rateType = 'SPOT') =>
    apiClient.get<ApiResponse<{ fromCurrency: string; toCurrency: string; rate: number; rateType: string }>>(
      '/treasury/fx-rates/rate',
      { params: { fromCurrency, toCurrency, rateType } }
    ).then(r => r.data),

  convert: (amount: number, fromCurrency: string, toCurrency: string, rateType = 'SPOT') =>
    apiClient.get<ApiResponse<FxConversionResult>>(
      '/treasury/fx-rates/convert',
      { params: { amount, fromCurrency, toCurrency, rateType } }
    ).then(r => r.data),

  getAllActiveRates: () =>
    apiClient.get<ApiResponse<FxRate[]>>('/treasury/fx-rates').then(r => r.data),

  getAvailablePairs: () =>
    apiClient.get<ApiResponse<string[]>>('/treasury/fx-rates/pairs').then(r => r.data),

  getAvailableCurrencies: () =>
    apiClient.get<ApiResponse<string[]>>('/treasury/fx-rates/currencies').then(r => r.data),

  createSpotRate: (fromCurrency: string, toCurrency: string, rate: number, source?: string) =>
    apiClient.post<ApiResponse<FxRate>>('/treasury/fx-rates/spot', {
      fromCurrency, toCurrency, rate, source
    }).then(r => r.data),

  createFixingRate: (fromCurrency: string, toCurrency: string, rate: number, fixingDate: string, source?: string) =>
    apiClient.post<ApiResponse<FxRate>>('/treasury/fx-rates/fixing', {
      fromCurrency, toCurrency, rate, fixingDate, source
    }).then(r => r.data),

  updateRate: (rateId: string, rate: number) =>
    apiClient.put<ApiResponse<FxRate>>(`/treasury/fx-rates/${rateId}`, { rate }).then(r => r.data),

  deactivateRate: (rateId: string) =>
    apiClient.delete<ApiResponse<void>>(`/treasury/fx-rates/${rateId}`).then(r => r.data),

  getHistoricalRates: (fromCurrency: string, toCurrency: string, startDate: string, endDate: string) =>
    apiClient.get<ApiResponse<FxRate[]>>('/treasury/fx-rates/history', {
      params: { fromCurrency, toCurrency, startDate, endDate }
    }).then(r => r.data),

  getFixingRate: (fromCurrency: string, toCurrency: string, date: string) =>
    apiClient.get<ApiResponse<FxRate>>('/treasury/fx-rates/fixing', {
      params: { fromCurrency, toCurrency, date }
    }).then(r => r.data),

  getCacheStats: () =>
    apiClient.get<ApiResponse<Record<string, any>>>('/treasury/fx-rates/cache/stats').then(r => r.data),

  refreshCache: () =>
    apiClient.post<ApiResponse<string>>('/treasury/fx-rates/cache/refresh').then(r => r.data),

  clearCache: () =>
    apiClient.delete<ApiResponse<string>>('/treasury/fx-rates/cache').then(r => r.data),
};

// ============================================================================
// SHADOW ACCOUNT API (PHYSICAL_MIRROR)
// ============================================================================

export interface ShadowAccount {
  id: string;
  vaNumber: string;
  vaName: string;
  corporateId: string;
  corporateName?: string;
  linkedPhysicalAccountId: string;
  bankAccountNumber: string;
  bankIban?: string;
  bankSwift?: string;
  bankName?: string;
  currencyCode: string;
  bankBalance: number;
  bankAvailableBalance: number;
  bankBalanceAt?: string;
  balanceDataSource: 'CORE_BANKING' | 'SWIFT_MT940' | 'SWIFT_MT942' | 'OPEN_BANKING' | 'MANUAL';
  parentAccountId?: string;
  hierarchyLevel?: number;
  hierarchyPathVa?: string;
  status: string;
  lastSyncStatus?: 'SUCCESS' | 'FAILED' | 'PENDING';
  lastSyncError?: string;
  createdAt: string;
  updatedAt?: string;
}

export interface ShadowSyncResult {
  shadowVaId: string;
  vaNumber: string;
  previousBalance: number;
  newBalance: number;
  delta: number;
  syncedAt: string;
  source: string;
}

export const shadowAccountApi = {
  // Get all shadow accounts
  getAll: (corporateId?: string) =>
    apiClient.get<ApiResponse<ShadowAccount[]>>('/treasury/shadow-accounts', {
      params: { corporateId }
    }).then(r => r.data),

  // Get by ID
  getById: (id: string) =>
    apiClient.get<ApiResponse<ShadowAccount>>(`/treasury/shadow-accounts/${id}`).then(r => r.data),

  // Get by corporate
  getByCorporate: (corporateId: string) =>
    apiClient.get<ApiResponse<ShadowAccount[]>>(`/treasury/shadow-accounts/corporate/${corporateId}`).then(r => r.data),

  // Get by program
  getByProgram: (programId: string) =>
    apiClient.get<ApiResponse<ShadowAccount[]>>(`/treasury/shadow-accounts/program/${programId}`).then(r => r.data),

  // Get by physical account
  getByPhysicalAccount: (physicalAccountId: string) =>
    apiClient.get<ApiResponse<ShadowAccount>>(`/treasury/shadow-accounts/physical/${physicalAccountId}`).then(r => r.data),

  // Create shadow account
  create: (data: {
    physicalAccountId: string;   // ✅ Required - matches backend
    corporateId: string;         // ✅ Required - matches backend
    parentVaId?: string;         // ✅ Optional - matches backend
    legalEntityId?: string;      // ✅ Optional - for entity linkage
  }) =>
    apiClient.post<ApiResponse<ShadowAccount>>('/treasury/shadow-accounts', data).then(r => r.data),

  // Link to physical account
  linkToPhysical: (shadowId: string, physicalAccountId: string) =>
    apiClient.post<ApiResponse<ShadowAccount>>(`/treasury/shadow-accounts/${shadowId}/link`, {
      physicalAccountId
    }).then(r => r.data),

  // Unlink from physical account
  unlink: (shadowId: string) =>
    apiClient.post<ApiResponse<ShadowAccount>>(`/treasury/shadow-accounts/${shadowId}/unlink`).then(r => r.data),

  // Sync balance from source
  syncBalance: (shadowId: string) =>
    apiClient.post<ApiResponse<ShadowSyncResult>>(`/treasury/shadow-accounts/${shadowId}/sync`).then(r => r.data),

  // Sync all for corporate
  syncAllForCorporate: (corporateId: string) =>
    apiClient.post<ApiResponse<ShadowSyncResult[]>>(`/treasury/shadow-accounts/sync-all`, {
      corporateId
    }).then(r => r.data),

  // Update balance manually
  updateBalanceManual: (shadowId: string, balance: number, availableBalance: number) =>
    apiClient.put<ApiResponse<ShadowAccount>>(`/treasury/shadow-accounts/${shadowId}/balance`, {
      balance, availableBalance
    }).then(r => r.data),

  // Update data source
  updateDataSource: (shadowId: string, dataSource: string) =>
    apiClient.put<ApiResponse<ShadowAccount>>(`/treasury/shadow-accounts/${shadowId}/data-source`, {
      dataSource
    }).then(r => r.data),

  // Get sync history
  getSyncHistory: (shadowId: string, limit = 10) =>
    apiClient.get<ApiResponse<ShadowSyncResult[]>>(`/treasury/shadow-accounts/${shadowId}/sync-history`, {
      params: { limit }
    }).then(r => r.data),

  // Delete
  delete: (shadowId: string) =>
    apiClient.delete<ApiResponse<void>>(`/treasury/shadow-accounts/${shadowId}`).then(r => r.data),
};

// ============================================================================
// LEGAL ENTITY API
// ============================================================================

export interface LegalEntity {
  id: string;
  entityCode: string;
  entityName: string;
  shortName?: string;
  parentEntityId?: string;
  hierarchyPath?: string;
  hierarchyLevel: number;
  ownershipPercent: number;
  consolidationMethod: string;
  entityType: 'HOLDING' | 'SUBSIDIARY' | 'BRANCH' | 'REPRESENTATIVE' | 'JOINT_VENTURE' | 'ASSOCIATE' | 'SPV' | 'TREASURY_CENTER';
  legalForm?: string;
  countryCode?: string;
  jurisdiction?: string;
  taxId?: string;
  registrationNumber?: string;
  functionalCurrency: string;
  reportingCurrency?: string;
  
  // ========================================================================
  // BANK RELATIONSHIP - v4.2.23
  // NAMING: bancsCustomerId (consistent with PhysicalAccount)
  // ========================================================================
  
  /**
   * Whether this entity is a customer of the bank.
   * 
   * Bank customers:
   * - Have physical bank accounts with this bank
   * - Can have EXTERNAL credit limits (overdraft, revolving credit) from BANCS
   * - Limits are synced from Core Banking System
   * 
   * Non-bank-customer entities:
   * - May bank with other banks or be purely internal entities
   * - Can ONLY have INTERNAL limits set by CFO
   */
  isBankCustomer?: boolean;
  
  /**
   * BANCS Customer ID (CIF - Customer Information File).
   * 
   * Consistent with: PhysicalAccount.bancsCustomerId
   * Column name: bancs_customer_id
   * 
   * Only applicable for bank customers (isBankCustomer = true).
   * Links the entity to BANCS for external limit synchronization.
   * 
   * Format: alphanumeric, bank-specific (e.g., "CIF001234567")
   */
  bancsCustomerId?: string;
  
  // ========================================================================
  // TREASURY CONFIGURATION
  // ========================================================================
  
  isTreasuryCenter: boolean;
  canHoldPhysicalAccounts: boolean;
  canParticipatePooling: boolean;
  canParticipateNetting: boolean;
  
  // ========================================================================
  // INTERNAL CREDIT LIMITS
  // ========================================================================

  internalCreditLimit?: number;
  internalLimitUtilized: number;
  internalLimitCurrency?: string;
  limitWarningThreshold: number;

  // ========================================================================
  // IHB CONFIGURATION
  // ========================================================================

  ihbEnabled?: boolean;
  ihbCreditLimit?: number;
  ihbCurrentExposure?: number;
  ihbAvailableLimit?: number;
  ihbCurrency?: string;
  canLend?: boolean;
  canBorrow?: boolean;
  lendingRateSpread?: number;
  borrowingRateSpread?: number;

  // ========================================================================
  // STATUS & VALIDITY
  // ========================================================================
  
  status: 'ACTIVE' | 'INACTIVE' | 'SUSPENDED' | 'PENDING_APPROVAL' | 'CLOSED';
  effectiveFrom?: string;
  effectiveTo?: string;
  
  // ========================================================================
  // CORPORATE & CONTACT
  // ========================================================================
  
  corporateId: string;
  contactEmail?: string;
  contactPhone?: string;
  registeredAddress?: string;
  
  // ========================================================================
  // AUDIT
  // ========================================================================
  
  createdAt: string;
  updatedAt?: string;
  
  // ========================================================================
  // HIERARCHY CHILDREN (for tree views)
  // ========================================================================
  
  children?: LegalEntity[];
}

// ============================================================================
// UPDATED STATISTICS INTERFACE
// ============================================================================

export interface LegalEntityStatistics {
  totalEntities: number;
  activeEntities: number;
  bankCustomers: number;        // Count of entities with isBankCustomer=true
  nonBankCustomers: number;     // Count of entities with isBankCustomer=false
  byType: Record<string, number>;
  byCountry: Record<string, number>;
  totalLimit: number;
  totalUtilized: number;
  entitiesNearLimit: number;
  treasuryCenters: number;
}

export const legalEntityApi = {
  // Get all
  getAll: (corporateId?: string) =>
    apiClient.get<ApiResponse<LegalEntity[]>>('/hierarchy/legal-entities', {
      params: { corporateId }
    }).then(r => r.data),

  // Get by ID
  getById: (id: string) =>
    apiClient.get<ApiResponse<LegalEntity>>(`/hierarchy/legal-entities/${id}`).then(r => r.data),

  // Get by corporate
  getByCorporate: (corporateId: string) =>
    apiClient.get<ApiResponse<LegalEntity[]>>(`/hierarchy/legal-entities/corporate/${corporateId}`).then(r => r.data),

  // Get hierarchy tree
  getHierarchy: (corporateId: string) =>
    apiClient.get<ApiResponse<LegalEntity[]>>(`/hierarchy/legal-entities/corporate/${corporateId}/tree`).then(r => r.data),

  // Get children
  getChildren: (parentId: string) =>
    apiClient.get<ApiResponse<LegalEntity[]>>(`/hierarchy/legal-entities/${parentId}/children`).then(r => r.data),

  // Get by type
  getByType: (corporateId: string, entityType: string) =>
    apiClient.get<ApiResponse<LegalEntity[]>>(`/hierarchy/legal-entities/corporate/${corporateId}/type/${entityType}`).then(r => r.data),

  // Get treasury centers
  getTreasuryCenters: (corporateId: string) =>
    apiClient.get<ApiResponse<LegalEntity[]>>(`/hierarchy/legal-entities/corporate/${corporateId}/treasury-centers`).then(r => r.data),

  // Create
  create: (data: Partial<LegalEntity>) =>
    apiClient.post<ApiResponse<LegalEntity>>('/hierarchy/legal-entities', data).then(r => r.data),

  // Update
  update: (id: string, data: Partial<LegalEntity>) =>
    apiClient.put<ApiResponse<LegalEntity>>(`/hierarchy/legal-entities/${id}`, data).then(r => r.data),

  // Update limit
  updateLimit: (id: string, limit: number, currency: string) =>
    apiClient.put<ApiResponse<LegalEntity>>(`/hierarchy/legal-entities/${id}/limit`, {
      limit, currency
    }).then(r => r.data),

  // Move in hierarchy
  move: (id: string, newParentId: string) =>
    apiClient.put<ApiResponse<LegalEntity>>(`/hierarchy/legal-entities/${id}/move`, {
      newParentId
    }).then(r => r.data),

  // Status operations
  activate: (id: string) =>
    apiClient.put<ApiResponse<LegalEntity>>(`/hierarchy/legal-entities/${id}/activate`).then(r => r.data),

  suspend: (id: string) =>
    apiClient.put<ApiResponse<LegalEntity>>(`/hierarchy/legal-entities/${id}/suspend`).then(r => r.data),

  close: (id: string) =>
    apiClient.put<ApiResponse<LegalEntity>>(`/hierarchy/legal-entities/${id}/close`).then(r => r.data),

  // Delete
  delete: (id: string) =>
    apiClient.delete<ApiResponse<void>>(`/hierarchy/legal-entities/${id}`).then(r => r.data),

  // Statistics
  getStatistics: (corporateId: string) =>
    apiClient.get<ApiResponse<LegalEntityStatistics>>(`/hierarchy/legal-entities/corporate/${corporateId}/statistics`).then(r => r.data),

  // Get entities near limit
  getEntitiesNearLimit: (corporateId: string, thresholdPercent = 80) =>
    apiClient.get<ApiResponse<LegalEntity[]>>(`/hierarchy/legal-entities/corporate/${corporateId}/near-limit`, {
      params: { thresholdPercent }
    }).then(r => r.data),

    // Get bank customer entities only
  getBankCustomers: (corporateId: string) =>
    apiClient.get<ApiResponse<LegalEntity[]>>(
      `/hierarchy/legal-entities/corporate/${corporateId}/bank-customers`
    ).then(r => r.data),

  // Get non-bank customer entities only
  getNonBankCustomers: (corporateId: string) =>
    apiClient.get<ApiResponse<LegalEntity[]>>(
      `/hierarchy/legal-entities/corporate/${corporateId}/non-bank-customers`
    ).then(r => r.data),

  // Link entity to BANCS (set as bank customer with BANCS Customer ID)
  linkToBancs: (entityId: string, bancsCustomerId: string) =>
    apiClient.post<ApiResponse<LegalEntity>>(
      `/hierarchy/legal-entities/${entityId}/link-bancs`,
      { bancsCustomerId }
    ).then(r => r.data),

  // Unlink entity from BANCS (remove bank customer status)
  unlinkFromBancs: (entityId: string) =>
    apiClient.post<ApiResponse<LegalEntity>>(
      `/hierarchy/legal-entities/${entityId}/unlink-bancs`
    ).then(r => r.data),

  // Validate BANCS Customer ID (check if exists in BANCS/CBS)
  validateBancsCustomerId: (bancsCustomerId: string) =>
    apiClient.get<ApiResponse<{ valid: boolean; customerName?: string; message?: string }>>(
      `/hierarchy/legal-entities/validate-bancs-customer`,
      { params: { bancsCustomerId } }
    ).then(r => r.data),


  };

// ============================================================================
// ACCOUNT ATTACHMENT API
// ============================================================================

export interface AccountAttachment {
  id: string;
  virtualAccountId: string;
  vaNumber?: string;
  legalEntityId: string;
  entityName?: string;
  relationshipType: 'OWNER' | 'BENEFICIARY' | 'AUTHORIZED' | 'GUARANTOR' | 'COLLATERAL';
  isPrimary: boolean;
  description?: string;
  effectiveFrom: string;
  effectiveTo?: string;
  isCurrentlyValid?: boolean;
  maxTransactionAmount?: number;
  dailyLimit?: number;
  authorizedTransactionTypes?: string;
  requiresDualAuth?: boolean;
  collateralPercent?: number;
  securedFacilityId?: string;
  status: 'ACTIVE' | 'PENDING_APPROVAL' | 'SUSPENDED' | 'EXPIRED' | 'TERMINATED';
  approvedBy?: string;
  approvedAt?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface AccountAttachmentStatistics {
  totalAttachments: number;
  activeAttachments: number;
  pendingApproval: number;
  ownerRelationships: number;
  authorizedRelationships: number;
  collateralRelationships: number;
  expiringIn30Days: number;
}

export interface AuthorizationCheckResult {
  authorized: boolean;
  authorizationType?: string;
  maxAllowed?: number;
  dailyRemaining?: number;
  requiresDualAuth?: boolean;
  rejectionReason?: string;
}

export const accountAttachmentApi = {
  // List all (paginated)
  getAll: (page = 0, size = 20) =>
    apiClient.get<ApiResponse<PaginatedResponse<AccountAttachment>>>('/account-attachments', {
      params: { page, size }
    }).then(r => r.data),

  // Get by ID
  getById: (id: string) =>
    apiClient.get<ApiResponse<AccountAttachment>>(`/account-attachments/${id}`).then(r => r.data),

  // Get by virtual account
  getByVirtualAccount: (vaId: string) =>
    apiClient.get<ApiResponse<AccountAttachment[]>>(`/account-attachments/va/${vaId}`).then(r => r.data),

  // Get active by virtual account
  getActiveByVirtualAccount: (vaId: string) =>
    apiClient.get<ApiResponse<AccountAttachment[]>>(`/account-attachments/va/${vaId}/active`).then(r => r.data),

  // Get by legal entity
  getByLegalEntity: (entityId: string) =>
    apiClient.get<ApiResponse<AccountAttachment[]>>(`/account-attachments/entity/${entityId}`).then(r => r.data),

  // Get active by legal entity
  getActiveByLegalEntity: (entityId: string) =>
    apiClient.get<ApiResponse<AccountAttachment[]>>(`/account-attachments/entity/${entityId}/active`).then(r => r.data),

  // Get primary owner
  getPrimaryOwner: (vaId: string) =>
    apiClient.get<ApiResponse<AccountAttachment>>(`/account-attachments/va/${vaId}/owner`).then(r => r.data),

  // Get authorized entities
  getAuthorizedEntities: (vaId: string) =>
    apiClient.get<ApiResponse<AccountAttachment[]>>(`/account-attachments/va/${vaId}/authorized`).then(r => r.data),

  // Get pending approvals
  getPendingApprovals: () =>
    apiClient.get<ApiResponse<AccountAttachment[]>>('/account-attachments/pending').then(r => r.data),

  // Get expiring soon
  getExpiringSoon: (days: number) =>
    apiClient.get<ApiResponse<AccountAttachment[]>>(`/account-attachments/expiring/${days}`).then(r => r.data),

  // Get statistics
  getStatistics: (vaId: string) =>
    apiClient.get<ApiResponse<AccountAttachmentStatistics>>(`/account-attachments/va/${vaId}/statistics`).then(r => r.data),

  // Create
  create: (data: Partial<AccountAttachment> & { requiresApproval?: boolean }) =>
    apiClient.post<ApiResponse<AccountAttachment>>('/account-attachments', data).then(r => r.data),

  // Create owner (quick)
  createOwner: (virtualAccountId: string, legalEntityId: string) =>
    apiClient.post<ApiResponse<AccountAttachment>>('/account-attachments/owner', null, {
      params: { virtualAccountId, legalEntityId }
    }).then(r => r.data),

  // Create authorized (quick)
  createAuthorized: (virtualAccountId: string, legalEntityId: string, maxTransactionAmount?: number, dailyLimit?: number) =>
    apiClient.post<ApiResponse<AccountAttachment>>('/account-attachments/authorized', null, {
      params: { virtualAccountId, legalEntityId, maxTransactionAmount, dailyLimit }
    }).then(r => r.data),

  // Update
  update: (id: string, data: Partial<AccountAttachment>) =>
    apiClient.put<ApiResponse<AccountAttachment>>(`/account-attachments/${id}`, data).then(r => r.data),

  // Approve
  approve: (id: string, approver: string) =>
    apiClient.put<ApiResponse<AccountAttachment>>(`/account-attachments/${id}/approve`, null, {
      params: { approver }
    }).then(r => r.data),

  // Suspend
  suspend: (id: string) =>
    apiClient.put<ApiResponse<AccountAttachment>>(`/account-attachments/${id}/suspend`).then(r => r.data),

  // Terminate
  terminate: (id: string) =>
    apiClient.put<ApiResponse<AccountAttachment>>(`/account-attachments/${id}/terminate`).then(r => r.data),

  // Delete
  delete: (id: string) =>
    apiClient.delete<ApiResponse<void>>(`/account-attachments/${id}`).then(r => r.data),

  // Transfer ownership
  transferOwnership: (virtualAccountId: string, newOwnerId: string, transferReason: string) =>
    apiClient.post<ApiResponse<AccountAttachment>>('/account-attachments/transfer-ownership', {
      virtualAccountId, newOwnerId, transferReason
    }).then(r => r.data),

  // Check authorization
  checkAuthorization: (virtualAccountId: string, legalEntityId: string, transactionAmount?: number) =>
    apiClient.post<ApiResponse<AuthorizationCheckResult>>('/account-attachments/check-authorization', {
      virtualAccountId, legalEntityId, transactionAmount
    }).then(r => r.data),

  // Quick authorization check
  isAuthorized: (virtualAccountId: string, legalEntityId: string) =>
    apiClient.get<ApiResponse<boolean>>('/account-attachments/check', {
      params: { virtualAccountId, legalEntityId }
    }).then(r => r.data),
};

// ============================================================================
// INTEREST CONFIGURATION API
// ============================================================================

export interface InterestConfiguration {
  id: string;
  corporateId: string;
  configName: string;
  externalReference?: string;
  configType: 'EXTERNAL' | 'INTERNAL';
  targetId?: string;
  targetType?: 'VIRTUAL_ACCOUNT' | 'PHYSICAL_ACCOUNT' | 'LEGAL_ENTITY' | 'PROGRAM' | 'CURRENCY' | 'CORPORATE';
  currencyCode: string;
  creditBaseRateType?: string;
  creditBaseRate?: number;
  creditSpread?: number;
  effectiveCreditRate?: number;
  creditMinBalance?: number;
  debitBaseRateType?: string;
  debitBaseRate?: number;
  debitSpread?: number;
  effectiveDebitRate?: number;
  penaltyRate?: number;
  dayCountConvention: string;
  compoundingFrequency: string;
  calculationFrequency: string;
  postingFrequency: string;
  isTiered?: boolean;
  tierConfig?: string;
  effectiveFrom?: string;
  effectiveTo?: string;
  status: 'DRAFT' | 'PENDING_APPROVAL' | 'ACTIVE' | 'SUSPENDED' | 'EXPIRED' | 'CANCELLED';
  lastSyncAt?: string;
  sourceSystem?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface InterestConfigurationStatistics {
  totalConfigs: number;
  externalConfigs: number;
  internalConfigs: number;
  avgCreditRate: number;
  avgDebitRate: number;
}

export const interestConfigurationApi = {
  getById: (id: string) =>
    apiClient.get<ApiResponse<InterestConfiguration>>(`/interest-configs/${id}`).then(r => r.data),

  getByCorporate: (corporateId: string, page = 0, size = 20) =>
    apiClient.get<ApiResponse<PaginatedResponse<InterestConfiguration>>>(`/interest-configs/corporate/${corporateId}`, {
      params: { page, size }
    }).then(r => r.data),

  getActiveByCorporate: (corporateId: string) =>
    apiClient.get<ApiResponse<InterestConfiguration[]>>(`/interest-configs/corporate/${corporateId}/active`).then(r => r.data),

  getByTarget: (targetId: string) =>
    apiClient.get<ApiResponse<InterestConfiguration[]>>(`/interest-configs/target/${targetId}`).then(r => r.data),

  create: (data: Partial<InterestConfiguration>) =>
    apiClient.post<ApiResponse<InterestConfiguration>>('/interest-configs', data).then(r => r.data),

  update: (id: string, data: Partial<InterestConfiguration>) =>
    apiClient.put<ApiResponse<InterestConfiguration>>(`/interest-configs/${id}`, data).then(r => r.data),

  updateRates: (id: string, creditBaseRate: number, debitBaseRate: number) =>
    apiClient.put<ApiResponse<InterestConfiguration>>(`/interest-configs/${id}/rates`, {
      creditBaseRate, debitBaseRate
    }).then(r => r.data),

  activate: (id: string) =>
    apiClient.put<ApiResponse<InterestConfiguration>>(`/interest-configs/${id}/activate`).then(r => r.data),

  suspend: (id: string) =>
    apiClient.put<ApiResponse<InterestConfiguration>>(`/interest-configs/${id}/suspend`).then(r => r.data),

  delete: (id: string) =>
    apiClient.delete<ApiResponse<void>>(`/interest-configs/${id}`).then(r => r.data),

  calculate: (configId: string, balance: number, days: number) =>
    apiClient.post<ApiResponse<any>>('/interest-configs/calculate', { configId, balance, days }).then(r => r.data),

  getStatistics: (corporateId: string) =>
    apiClient.get<ApiResponse<InterestConfigurationStatistics>>(`/interest-configs/statistics/${corporateId}`).then(r => r.data),
};


// ============================================================================
// CURRENCY MIRROR API
// ============================================================================

export interface CurrencyMirror {
  id: string;
  vaNumber: string;
  vaName: string;
  corporateId: string;
  currencyCode: string;
  baseCurrency: string;
  mirrorBalance: number;
  fxRate?: number;
  fxRateAt?: string;
  fxRateSource?: string;
  balanceInBase: number;
  parentAccountId?: string;
  hierarchyLevel?: number;
  hierarchyPathVa?: string;
  status: string;
  createdAt: string;
  updatedAt?: string;
}

export interface CurrencyBreakdown {
  currency: string;
  baseCurrency: string;
  originalBalance: number;
  fxRate: number;
  fxRateAt?: string;
  convertedBalance: number;
  mirrorVaId: string;
  mirrorVaNumber: string;
  level?: number;  // v5.7.1: Hierarchy level for level-based breakdown
}

// v5.7.1: Level info for level selector UI
export interface LevelInfo {
  level: number;
  name: string;
}

export const currencyMirrorApi = {
  create: (parentVaId: string, currency: string, baseCurrency: string, corporateId: string) =>
    apiClient.post<ApiResponse<CurrencyMirror>>('/treasury/currency-mirrors', {
      parentVaId, currency, baseCurrency, corporateId
    }).then(r => r.data),

  recalculate: (mirrorVaId: string) =>
    apiClient.post<ApiResponse<CurrencyMirror>>(`/treasury/currency-mirrors/${mirrorVaId}/recalculate`).then(r => r.data),

  recalculateAll: (corporateId: string) =>
    apiClient.post<ApiResponse<{ corporateId: string; totalMirrors: number; recalculated: number; failed: number }>>(
      `/treasury/currency-mirrors/recalculate/corporate/${corporateId}`
    ).then(r => r.data),

  // Program-based recalculate (preferred for multi-program corporates)
  recalculateAllByProgram: (programId: string) =>
    apiClient.post<ApiResponse<{ programId: string; totalMirrors: number; recalculated: number; failed: number }>>(
      `/treasury/currency-mirrors/recalculate/program/${programId}`
    ).then(r => r.data),

  getByCorporate: (corporateId: string) =>
    apiClient.get<ApiResponse<CurrencyMirror[]>>(`/treasury/currency-mirrors/corporate/${corporateId}`).then(r => r.data),

  // Program-based currency mirrors (preferred for multi-program corporates)
  getByProgram: (programId: string) =>
    apiClient.get<ApiResponse<CurrencyMirror[]>>(`/treasury/currency-mirrors/program/${programId}`).then(r => r.data),

  getBreakdown: (corporateId: string) =>
    apiClient.get<ApiResponse<Record<string, CurrencyBreakdown>>>(`/treasury/currency-mirrors/breakdown/${corporateId}`).then(r => r.data),

  getBreakdownList: (corporateId: string) =>
    apiClient.get<ApiResponse<CurrencyBreakdown[]>>(`/treasury/currency-mirrors/breakdown/${corporateId}/list`).then(r => r.data),

  // Program-based breakdown list (preferred for multi-program corporates)
  // v5.7.1: Added level parameter for level-based breakdown
  // level=0 for ROOT (total), level=1+ for specific AGGREGATION levels
  getBreakdownListByProgram: (programId: string, level?: number) =>
    apiClient.get<ApiResponse<CurrencyBreakdown[]>>(
      `/treasury/currency-mirrors/breakdown/program/${programId}/list`,
      { params: level !== undefined ? { level } : {} }
    ).then(r => r.data),

  // v5.7.2: Node-specific breakdown (preferred when clicking on a specific AGGREGATION node)
  // Returns only mirrors that are direct children of the specified node
  getBreakdownListByNode: (nodeId: string) =>
    apiClient.get<ApiResponse<CurrencyBreakdown[]>>(
      `/treasury/currency-mirrors/breakdown/node/${nodeId}/list`
    ).then(r => r.data),

  // Get available hierarchy levels for currency mirrors (for level selector UI)
  getLevelsByProgram: (programId: string) =>
    apiClient.get<ApiResponse<LevelInfo[]>>(`/treasury/currency-mirrors/breakdown/program/${programId}/levels`).then(r => r.data),

  getConsolidatedBalance: (corporateId: string, baseCurrency = 'AED') =>
    apiClient.get<ApiResponse<{ corporateId: string; baseCurrency: string; totalBalance: number; currencyCount: number; asOf: string }>>(
      `/treasury/currency-mirrors/consolidated/${corporateId}`,
      { params: { baseCurrency } }
    ).then(r => r.data),

  // Program-based consolidated balance (preferred for multi-program corporates)
  getConsolidatedBalanceByProgram: (programId: string, baseCurrency?: string) =>
    apiClient.get<ApiResponse<{ programId: string; baseCurrency: string; totalBalance: number; currencyCount: number; asOf: string }>>(
      `/treasury/currency-mirrors/consolidated/program/${programId}`,
      { params: baseCurrency ? { baseCurrency } : {} }
    ).then(r => r.data),
};

// ============================================================================
// CREDIT LIMIT API
// ============================================================================

export interface CreditLimit {
  id: string;
  corporateId: string;
  limitName: string;
  limitType: 'EXTERNAL' | 'INTERNAL';
  targetType: 'VIRTUAL_ACCOUNT' | 'LEGAL_ENTITY' | 'AGGREGATION_NODE' | 'SHADOW_ACCOUNT' | 'ROOT';
  targetId: string;
  creditFacilityId?: string;
  externalReference?: string;
  limitAmount: number;
  limitCurrency: string;
  utilizedAmount: number;
  availableAmount: number;
  heldAmount: number;
  utilizationPercent: number;
  warningThresholdPercent: number;
  criticalThresholdPercent: number;
  isAtWarningLevel: boolean;
  isAtCriticalLevel: boolean;
  isBreached: boolean;
  effectiveFrom: string;
  effectiveTo?: string;
  status: 'DRAFT' | 'PENDING_APPROVAL' | 'ACTIVE' | 'SUSPENDED' | 'EXPIRED' | 'CANCELLED' | 'BREACHED';
  approvedBy?: string;
  approvedAt?: string;
  sourceSystem?: string;
  lastSyncAt?: string;
  notes?: string;
  createdAt: string;
  updatedAt?: string;
}

export interface CreditLimitTotals {
  corporateId: string;
  externalLimitTotal: number;
  externalUtilizedTotal: number;
  externalAvailable: number;
  internalLimitTotal: number;
  internalUtilizedTotal: number;
  internalAvailable: number;
  grandTotal: number;
  grandUtilized: number;
  grandAvailable: number;
}


// ============================================================================
// FUNDS AVAILABILITY API - ⭐ THE CRITICAL ONE ⭐
// ============================================================================

export interface FundsLevelCheckResult {
  level: number;
  vaId: string;
  vaNumber: string;
  vaCurrency: string;
  accountCategory?: string;
  requestedAmountInVaCurrency: number;
  balance: number;
  externalLimitAvailable: number;
  internalLimitAvailable: number;
  totalAvailable: number;
  approved: boolean;
  rejectionReason?: string;
  shortfall?: number;
  limitUsageRequired?: number;
}

export interface FundsCheckResult {
  vaId: string;
  requestedAmount: number;
  requestedCurrency: string;
  checkedAt: string;
  approved: boolean;
  rejectionLevel: number;
  rejectionReason?: string;
  rejectionVaId?: string;
  rejectionVaNumber?: string;
  levelResults: FundsLevelCheckResult[];
  levelsChecked: number;
}

export interface FundsBatchCheckResult {
  totalChecks: number;
  approved: number;
  rejected: number;
  results: FundsCheckResult[];
}

export const fundsAvailabilityApi = {
  /**
   * ⭐ THE CRITICAL CHECK - Must pass before any debit ⭐
   */
  checkFunds: (vaId: string, amount: number, currency: string) =>
    apiClient.post<ApiResponse<FundsCheckResult>>('/treasury/funds/check', {
      vaId, amount, currency
    }).then(r => r.data),

  checkFundsQuick: (vaId: string, amount: number, currency: string) =>
    apiClient.get<ApiResponse<FundsCheckResult>>(`/treasury/funds/check/${vaId}`, {
      params: { amount, currency }
    }).then(r => r.data),

  checkFundsBatch: (checks: Array<{ vaId: string; amount: number; currency: string }>) =>
    apiClient.post<ApiResponse<FundsBatchCheckResult>>('/treasury/funds/check/batch', { checks }).then(r => r.data),

  hasSimpleBalance: (vaId: string, amount: number) =>
    apiClient.get<ApiResponse<{ vaId: string; requestedAmount: number; available: boolean; checkType: string }>>(
      `/treasury/funds/simple-balance/${vaId}`,
      { params: { amount } }
    ).then(r => r.data),

  hasFundsWithLimit: (vaId: string, amount: number) =>
    apiClient.get<ApiResponse<{ vaId: string; requestedAmount: number; available: boolean; checkType: string }>>(
      `/treasury/funds/with-limit/${vaId}`,
      { params: { amount } }
    ).then(r => r.data),

  getTotalAvailable: (vaId: string) =>
    apiClient.get<ApiResponse<{ vaId: string; totalAvailable: number }>>(`/treasury/funds/total-available/${vaId}`).then(r => r.data),

  updateUtilizationDebit: (vaId: string, amount: number) =>
    apiClient.post<ApiResponse<void>>(`/treasury/funds/utilization/${vaId}/debit`, null, {
      params: { amount }
    }).then(r => r.data),

  releaseUtilizationCredit: (vaId: string, amount: number) =>
    apiClient.post<ApiResponse<void>>(`/treasury/funds/utilization/${vaId}/credit`, null, {
      params: { amount }
    }).then(r => r.data),
};

// ============================================================================
// BALANCE AGGREGATION API
// ============================================================================

export interface MultiCurrencyPosition {
  corporateId: string;
  programId?: string;  // Added for program-specific multi-currency position
  baseCurrency: string;
  totalInBaseCurrency: number;
  positions: Array<{
    currency: string;
    originalBalance: number;
    fxRate: number;
    convertedBalance: number;
  }>;
  asOf: string;
}

export const balanceAggregationApi = {
  propagateChange: (vaId: string, balanceDelta: number) =>
    apiClient.post<ApiResponse<{ vaId: string; balanceDelta: number; propagated: boolean }>>(
      `/treasury/aggregation/propagate/${vaId}`,
      null,
      { params: { balanceDelta } }
    ).then(r => r.data),

  aggregateForCorporate: (corporateId: string) =>
    apiClient.post<ApiResponse<{ corporateId: string; vasAggregated: number }>>(
      `/treasury/aggregation/aggregate/corporate/${corporateId}`
    ).then(r => r.data),

  updateCurrencyMirrors: (corporateId: string) =>
    apiClient.post<ApiResponse<string>>(`/treasury/aggregation/mirrors/corporate/${corporateId}`).then(r => r.data),

  triggerScheduled: () =>
    apiClient.post<ApiResponse<string>>('/treasury/aggregation/trigger-scheduled').then(r => r.data),

  getTotalBalance: (corporateId: string) =>
    apiClient.get<ApiResponse<number>>(`/treasury/aggregation/total/${corporateId}`).then(r => r.data),

  getVaBalance: (vaId: string) =>
    apiClient.get<ApiResponse<number>>(`/treasury/aggregation/balance/${vaId}`).then(r => r.data),

  getSubtreeBalance: (vaId: string, targetCurrency = 'AED') =>
    apiClient.get<ApiResponse<{ vaId: string; targetCurrency: string; aggregatedBalance: number }>>(
      `/treasury/aggregation/subtree/${vaId}`,
      { params: { targetCurrency } }
    ).then(r => r.data),

  getBalanceByLevel: (corporateId: string, currency = 'AED') =>
    apiClient.get<ApiResponse<Record<number, number>>>(`/treasury/aggregation/by-level/${corporateId}`, {
      params: { currency }
    }).then(r => r.data),

  getMultiCurrencyPosition: (corporateId: string, baseCurrency = 'AED') =>
    apiClient.get<ApiResponse<MultiCurrencyPosition>>(`/treasury/aggregation/multi-currency/${corporateId}`, {
      params: { baseCurrency }
    }).then(r => r.data),

  // New program-specific multi-currency position API (preferred for multi-program corporates)
  getMultiCurrencyPositionByProgram: (programId: string, baseCurrency?: string) =>
    apiClient.get<ApiResponse<MultiCurrencyPosition>>(`/treasury/aggregation/multi-currency/program/${programId}`, {
      params: baseCurrency ? { baseCurrency } : {}
    }).then(r => r.data),
};


export type AgreementType = 'MASTER' | 'FACILITY' | 'BILATERAL' | 'SYNDICATED' | 'REVOLVING' | 'TERM';
export type AgreementStatus = 'DRAFT' | 'PENDING_APPROVAL' | 'ACTIVE' | 'SUSPENDED' | 'EXPIRED' | 'CANCELLED';

export interface CreditAgreement {
  id: string;
  corporateId: string;
  corporateName?: string;
  agreementName: string;
  agreementType: AgreementType;
  counterpartyBankId?: string;
  counterpartyBankName?: string;
  externalReference?: string;
  totalLimit: number;
  totalUtilized: number;
  availableLimit: number;
  limitCurrency: string;
  effectiveDate: string;
  expiryDate: string;
  nextReviewDate?: string;
  interestRateType?: 'FIXED' | 'FLOATING';
  baseRateType?: string;
  spreadBps?: number;
  status: AgreementStatus;
  cbsSyncedAt?: string;
  createdAt: string;
  updatedAt?: string;
}

export interface CreditAgreementCreateRequest {
  corporateId: string;
  agreementName: string;
  agreementType: AgreementType;
  counterpartyBankId?: string;
  counterpartyBankName?: string;
  externalReference?: string;
  totalLimit: number;
  limitCurrency: string;
  effectiveDate: string;
  expiryDate: string;
  nextReviewDate?: string;
  interestRateType?: 'FIXED' | 'FLOATING';
  baseRateType?: string;
  spreadBps?: number;
}

export interface AgreementSummary {
  corporateId: string;
  totalAgreements: number;
  activeAgreements: number;
  totalLimit: number;
  totalUtilized: number;
  totalAvailable: number;
  utilizationPercent: number;
}

// ============================================================================
// CREDIT AGREEMENTS API - Matches CreditAgreementController.java endpoints
// ============================================================================

export const creditAgreementsApi = {
  // CRUD Operations
  create: (data: CreditAgreementCreateRequest | Partial<CreditAgreement>) =>
    apiClient.post<ApiResponse<CreditAgreement>>('/credit-agreements', data).then(r => r.data),

  getById: (id: string) =>
    apiClient.get<ApiResponse<CreditAgreement>>(`/credit-agreements/${id}`).then(r => r.data),

  getByExternalReference: (externalReference: string) =>
    apiClient.get<ApiResponse<CreditAgreement>>(`/credit-agreements/external/${externalReference}`).then(r => r.data),

  update: (id: string, data: Partial<CreditAgreement>) =>
    apiClient.put<ApiResponse<CreditAgreement>>(`/credit-agreements/${id}`, data).then(r => r.data),

  delete: (id: string) =>
    apiClient.delete<ApiResponse<void>>(`/credit-agreements/${id}`).then(r => r.data),

  // Query Operations
  getAll: () =>
    apiClient.get<ApiResponse<CreditAgreement[]>>('/credit-agreements').then(r => r.data),

  getByCorporate: (corporateId: string) =>
    apiClient.get<ApiResponse<CreditAgreement[]>>(`/credit-agreements/corporate/${corporateId}`).then(r => r.data),

  getActiveByCorporate: (corporateId: string) =>
    apiClient.get<ApiResponse<CreditAgreement[]>>(`/credit-agreements/corporate/${corporateId}/active`).then(r => r.data),

  getExpiring: (days: number = 30) =>
    apiClient.get<ApiResponse<CreditAgreement[]>>('/credit-agreements/expiring', { params: { days } }).then(r => r.data),

  getExpiringByCorporate: (corporateId: string, days: number = 30) =>
    apiClient.get<ApiResponse<CreditAgreement[]>>(`/credit-agreements/corporate/${corporateId}/expiring`, { params: { days } }).then(r => r.data),

  getForReview: (corporateId: string) =>
    apiClient.get<ApiResponse<CreditAgreement[]>>(`/credit-agreements/corporate/${corporateId}/for-review`).then(r => r.data),

  // Utilization Operations
  utilize: (id: string, amount: number) =>
    apiClient.post<ApiResponse<CreditAgreement>>(`/credit-agreements/${id}/utilize`, null, { params: { amount } }).then(r => r.data),

  release: (id: string, amount: number) =>
    apiClient.post<ApiResponse<CreditAgreement>>(`/credit-agreements/${id}/release`, null, { params: { amount } }).then(r => r.data),

  // Status Operations
  activate: (id: string) =>
    apiClient.post<ApiResponse<CreditAgreement>>(`/credit-agreements/${id}/activate`).then(r => r.data),

  suspend: (id: string) =>
    apiClient.post<ApiResponse<CreditAgreement>>(`/credit-agreements/${id}/suspend`).then(r => r.data),

  expire: (id: string) =>
    apiClient.post<ApiResponse<CreditAgreement>>(`/credit-agreements/${id}/expire`).then(r => r.data),

  // CBS Sync Operations
  syncFromCbs: (externalReference: string, cbsData: Record<string, any>) =>
    apiClient.post<ApiResponse<CreditAgreement>>('/credit-agreements/sync', cbsData, { params: { externalReference } }).then(r => r.data),

  getAgreementsNeedingSync: (hours: number = 24) =>
    apiClient.get<ApiResponse<CreditAgreement[]>>('/credit-agreements/needing-sync', { params: { hours } }).then(r => r.data),

  // Summary Operations
  getTotalLimit: (corporateId: string) =>
    apiClient.get<ApiResponse<number>>(`/credit-agreements/corporate/${corporateId}/total-limit`).then(r => r.data),

  getTotalUtilized: (corporateId: string) =>
    apiClient.get<ApiResponse<number>>(`/credit-agreements/corporate/${corporateId}/total-utilized`).then(r => r.data),

  getSummary: (corporateId: string) =>
    apiClient.get<ApiResponse<Record<string, any>>>(`/credit-agreements/corporate/${corporateId}/summary`).then(r => r.data),

  getActiveCount: (corporateId: string) =>
    apiClient.get<ApiResponse<number>>(`/credit-agreements/corporate/${corporateId}/count`).then(r => r.data),
};

// ============================================================================
// CREDIT FACILITY TYPES (matching CreditFacility.java entity)
// ============================================================================

export type FacilityType = 
  | 'OVERDRAFT' | 'REVOLVING_CREDIT' | 'TERM_LOAN' | 'TRADE_FINANCE' 
  | 'LETTER_OF_CREDIT' | 'BANK_GUARANTEE' | 'WORKING_CAPITAL' | 'INVOICE_FINANCING' 
  | 'SUPPLY_CHAIN_FINANCE' | 'ASSET_BASED' | 'CASH_POOLING' | 'NOTIONAL_POOLING' | 'OTHER';

export type FacilityStatus = 'DRAFT' | 'ACTIVE' | 'SUSPENDED' | 'EXPIRED' | 'CLOSED';


export interface CreditFacility {
  id: string;
  creditAgreementId?: string;
  agreementName?: string;
  corporateId: string;
  corporateName?: string;
  facilityName: string;
  facilityType: FacilityType;
  externalReference?: string;
  facilityLimit: number;
  drawingPower: number;
  currentOutstanding: number;
  availableLimit: number;
  facilityCurrency: string;
  linkedPhysicalAccountId?: string;
  linkedAccountNumber?: string;
  effectiveDate: string;
  expiryDate: string;
  interestRateType: 'FIXED' | 'FLOATING';
  baseRateType?: string;
  baseRateValue?: number;
  spreadPercent?: number;
  effectiveRate?: number;
  status: FacilityStatus;
  cbsSyncedAt?: string;
  createdAt: string;
  updatedAt?: string;
}

export interface CreditFacilityCreateRequest {
  creditAgreementId?: string;
  corporateId: string;
  facilityName: string;
  facilityType: FacilityType;
  externalReference?: string;
  facilityLimit: number;
  facilityCurrency: string;
  linkedPhysicalAccountId?: string;
  effectiveDate: string;
  expiryDate: string;
  interestRateType: 'FIXED' | 'FLOATING';
  baseRateType?: string;
  baseRateValue?: number;
  spreadPercent?: number;
}

export interface FacilitySummary {
  corporateId: string;
  totalFacilities: number;
  activeFacilities: number;
  totalLimit: number;
  totalOutstanding: number;
  totalAvailable: number;
  utilizationPercent: number;
  byType: Record<FacilityType, { count: number; limit: number; outstanding: number }>;
}

// ============================================================================
// CREDIT FACILITIES API - Matches CreditFacilityController.java endpoints
// ============================================================================

export const creditFacilitiesApi = {
  // CRUD Operations
  create: (data: CreditFacilityCreateRequest | Partial<CreditFacility>) =>
    apiClient.post<ApiResponse<CreditFacility>>('/credit-facilities', data).then(r => r.data),

  getById: (id: string) =>
    apiClient.get<ApiResponse<CreditFacility>>(`/credit-facilities/${id}`).then(r => r.data),

  getByExternalReference: (externalReference: string) =>
    apiClient.get<ApiResponse<CreditFacility>>(`/credit-facilities/external/${externalReference}`).then(r => r.data),

  update: (id: string, data: Partial<CreditFacility>) =>
    apiClient.put<ApiResponse<CreditFacility>>(`/credit-facilities/${id}`, data).then(r => r.data),

  // Query Operations
  getAll: () =>
    apiClient.get<ApiResponse<CreditFacility[]>>('/credit-facilities').then(r => r.data),

  getByAgreement: (agreementId: string) =>
    apiClient.get<ApiResponse<CreditFacility[]>>(`/credit-facilities/agreement/${agreementId}`).then(r => r.data),

  getActiveByAgreement: (agreementId: string) =>
    apiClient.get<ApiResponse<CreditFacility[]>>(`/credit-facilities/agreement/${agreementId}/active`).then(r => r.data),

  getByCorporate: (corporateId: string) =>
    apiClient.get<ApiResponse<CreditFacility[]>>(`/credit-facilities/corporate/${corporateId}`).then(r => r.data),

  getActiveByCorporate: (corporateId: string) =>
    apiClient.get<ApiResponse<CreditFacility[]>>(`/credit-facilities/corporate/${corporateId}/active`).then(r => r.data),

  getByType: (corporateId: string, type: FacilityType) =>
    apiClient.get<ApiResponse<CreditFacility[]>>(`/credit-facilities/corporate/${corporateId}/type/${type}`).then(r => r.data),

  getOverdrafts: (corporateId: string) =>
    apiClient.get<ApiResponse<CreditFacility[]>>(`/credit-facilities/corporate/${corporateId}/overdrafts`).then(r => r.data),

  getByPhysicalAccount: (physicalAccountId: string) =>
    apiClient.get<ApiResponse<CreditFacility>>(`/credit-facilities/physical-account/${physicalAccountId}`).then(r => r.data),

  getExpiring: (days: number = 30) =>
    apiClient.get<ApiResponse<CreditFacility[]>>('/credit-facilities/expiring', { params: { days } }).then(r => r.data),

  getHighUtilization: (corporateId: string) =>
    apiClient.get<ApiResponse<CreditFacility[]>>(`/credit-facilities/corporate/${corporateId}/high-utilization`).then(r => r.data),

  getSummary: (agreementId: string) =>
    apiClient.get<ApiResponse<Record<string, any>>>(`/credit-facilities/agreement/${agreementId}/summary`).then(r => r.data),

  getDistinctTypes: (corporateId: string) =>
    apiClient.get<ApiResponse<FacilityType[]>>(`/credit-facilities/corporate/${corporateId}/types`).then(r => r.data),

  getActiveCount: (agreementId: string) =>
    apiClient.get<ApiResponse<number>>(`/credit-facilities/agreement/${agreementId}/count`).then(r => r.data),

  // Utilization Operations
  drawDown: (id: string, amount: number) =>
    apiClient.post<ApiResponse<CreditFacility>>(`/credit-facilities/${id}/draw-down`, null, { params: { amount } }).then(r => r.data),

  repay: (id: string, amount: number) =>
    apiClient.post<ApiResponse<CreditFacility>>(`/credit-facilities/${id}/repay`, null, { params: { amount } }).then(r => r.data),

  updateDrawingPower: (id: string, drawingPower: number) =>
    apiClient.put<ApiResponse<CreditFacility>>(`/credit-facilities/${id}/drawing-power`, null, { params: { drawingPower } }).then(r => r.data),

  // Link Operations
  linkToPhysicalAccount: (id: string, physicalAccountId: string) =>
    apiClient.post<ApiResponse<CreditFacility>>(`/credit-facilities/${id}/link-physical`, null, { params: { physicalAccountId } }).then(r => r.data),

  // Status Operations
  suspend: (id: string) =>
    apiClient.post<ApiResponse<CreditFacility>>(`/credit-facilities/${id}/suspend`).then(r => r.data),

  activate: (id: string) =>
    apiClient.post<ApiResponse<CreditFacility>>(`/credit-facilities/${id}/activate`).then(r => r.data),

  close: (id: string) =>
    apiClient.post<ApiResponse<CreditFacility>>(`/credit-facilities/${id}/close`).then(r => r.data),

  // CBS Sync Operations
  syncFromCbs: (externalReference: string, cbsData: Record<string, any>) =>
    apiClient.post<ApiResponse<CreditFacility>>('/credit-facilities/sync', cbsData, { params: { externalReference } }).then(r => r.data),
};


// ============================================================================
// CREDIT LIMIT API - ENHANCED FOR MULTI-LEVEL HIERARCHY
// Matches CreditLimitController.java endpoints
// ============================================================================


export type LimitType = 'EXTERNAL' | 'INTERNAL';
export type TargetType = 'VIRTUAL_ACCOUNT' | 'LEGAL_ENTITY' | 'AGGREGATION_NODE' | 'SHADOW_ACCOUNT' | 'CORPORATE';
export type LimitStatus = 'DRAFT' | 'PENDING_APPROVAL' | 'ACTIVE' | 'SUSPENDED' | 'EXPIRED' | 'CANCELLED' | 'BREACHED';

// External Limit Response (Bank-Provided, READ-ONLY)
export interface ExternalLimitResponse {
  id: string;
  limitName: string;
  facilityId?: string;
  externalReference?: string;
  targetId: string;
  targetType: TargetType;
  limitAmount: number;
  currency: string;
  utilizedAmount: number;
  availableAmount: number;
  utilizationPercent: number;
  effectiveFrom: string;
  effectiveTo?: string;
  status: LimitStatus;
  sourceSystem?: string;
  lastSyncAt?: string;
  readOnly: boolean;
  readOnlyReason?: string;
}

// Facility Response (External facilities for entity)
export interface FacilityResponse {
  id: string;
  facilityName: string;
  facilityType: string;
  externalReference?: string;
  sanctionedLimit: number;
  currentOutstanding: number;
  availableLimit: number;
  currency: string;
  interestRateType?: string;
  baseRateType?: string;
  baseRateValue?: number;
  spreadPercent?: number;
  effectiveRate?: number;
  expiryDate: string;
  status: string;
  lastSyncAt?: string;
}

// Internal Limit Response (CFO-Managed)
export interface InternalLimitResponse {
  id: string;
  limitName: string;
  limitType: 'GROUP' | 'ENTITY';
  corporateId: string;
  targetType: TargetType;
  targetId: string;
  // Hierarchy
  parentLimitId?: string;
  allocatedToChildren: number;
  unallocatedAmount: number;
  // Amounts
  limitAmount: number;
  currency: string;
  utilizedAmount: number;
  availableAmount: number;
  heldAmount: number;
  utilizationPercent: number;
  // Thresholds
  warningThresholdPercent: number;
  criticalThresholdPercent: number;
  isAtWarningLevel: boolean;
  isAtCriticalLevel: boolean;
  isBreached: boolean;
  // Controls
  isHardLimit: boolean;
  requiresApproval: boolean;
  approvalThresholdPercent?: number;
  needsApprovalNow: boolean;
  // Dates & Status
  effectiveFrom: string;
  effectiveTo?: string;
  status: LimitStatus;
  approvedBy?: string;
  approvedAt?: string;
  notes?: string;
  createdAt: string;
  updatedAt?: string;
}

// Ceiling Response
export interface CeilingResponse {
  entityId: string;
  currency?: string;
  externalCeiling?: number;
  message: string;
}

// Limit Check Result
export interface LimitCheckResult {
  entityId: string;
  requestedAmount: number;
  currency?: string;
  // External (bank) limit
  externalLimit?: number;
  externalUtilized?: number;
  externalAvailable?: number;
  externalCanUtilize: boolean;
  // Internal (CFO) limit
  internalLimit?: number;
  internalUtilized?: number;
  internalAvailable?: number;
  internalHardLimit: boolean;
  internalCanUtilize: boolean;
  // Effective
  effectiveAvailable?: number;
  canProceed: boolean;
}

// Limit Totals (Dashboard)
export interface LimitTotals {
  corporateId: string;
  externalLimitTotal: number;
  externalUtilizedTotal: number;
  externalAvailable: number;
  internalLimitTotal: number;
  internalAllocatedTotal: number;
  internalUtilizedTotal: number;
  internalUnallocated: number;
  internalAvailable: number;
}

// Request DTOs
export interface CreateGroupLimitRequest {
  corporateId: string;
  limitName?: string;
  amount: number;
  currency: string;
  approvedBy: string;
  hardLimit?: boolean;
}

export interface CreateEntitySubLimitRequest {
  corporateId: string;
  entityId: string;
  limitName?: string;
  amount: number;
  currency: string;
  approvedBy: string;
  hardLimit?: boolean;
  requiresApproval?: boolean;
  approvalThreshold?: number;
}

export interface UpdateControlsRequest {
  hardLimit?: boolean;
  requiresApproval?: boolean;
  approvalThreshold?: number;
}

export interface UpdateThresholdsRequest {
  warningPercent?: number;
  criticalPercent?: number;
}

// ============================================================================
// CREDIT LIMIT API - Matches CreditLimitController.java
// ============================================================================

export const creditLimitApi = {
  createExternal: (data: {
    corporateId: string;
    targetId: string;
    targetType: string;
    facilityId: string;
    amount: number;
    currency: string;
  }) => apiClient.post<ApiResponse<CreditLimit>>('/credit/limits/external', data).then(r => r.data),

  createInternal: (data: {
    corporateId: string;
    targetId: string;
    targetType: string;
    limitName?: string;
    amount: number;
    currency: string;
    approvedBy: string;
  }) => apiClient.post<ApiResponse<CreditLimit>>('/credit/limits/internal', data).then(r => r.data),

  getById: (limitId: string) =>
    apiClient.get<ApiResponse<CreditLimit>>(`/credit/limits/${limitId}`).then(r => r.data),

  getByCorporate: (corporateId: string) =>
    apiClient.get<ApiResponse<CreditLimit[]>>(`/credit/limits/corporate/${corporateId}`).then(r => r.data),

  getByTarget: (targetId: string) =>
    apiClient.get<ApiResponse<CreditLimit[]>>(`/credit/limits/target/${targetId}`).then(r => r.data),

  getAtWarningLevel: (corporateId: string) =>
    apiClient.get<ApiResponse<CreditLimit[]>>(`/credit/limits/corporate/${corporateId}/warnings`).then(r => r.data),

  getAtCriticalLevel: (corporateId: string) =>
    apiClient.get<ApiResponse<CreditLimit[]>>(`/credit/limits/corporate/${corporateId}/critical`).then(r => r.data),

  getBreached: (corporateId: string) =>
    apiClient.get<ApiResponse<CreditLimit[]>>(`/credit/limits/corporate/${corporateId}/breached`).then(r => r.data),

  getExpiring: (corporateId: string, daysAhead = 30) =>
    apiClient.get<ApiResponse<CreditLimit[]>>(`/credit/limits/corporate/${corporateId}/expiring`, {
      params: { daysAhead }
    }).then(r => r.data),

  getTotals: (corporateId: string) =>
    apiClient.get<ApiResponse<CreditLimitTotals>>(`/credit/limits/corporate/${corporateId}/total`).then(r => r.data),

  updateAmount: (limitId: string, newAmount: number, updatedBy = 'SYSTEM') =>
    apiClient.put<ApiResponse<CreditLimit>>(`/credit/limits/${limitId}/amount`, null, {
      params: { newAmount, updatedBy }
    }).then(r => r.data),

  updateThresholds: (limitId: string, warningPercent: number, criticalPercent: number) =>
    apiClient.put<ApiResponse<CreditLimit>>(`/credit/limits/${limitId}/thresholds`, {
      warningPercent, criticalPercent
    }).then(r => r.data),

  extendValidity: (limitId: string, newEffectiveTo: string) =>
    apiClient.put<ApiResponse<CreditLimit>>(`/credit/limits/${limitId}/validity`, null, {
      params: { newEffectiveTo }
    }).then(r => r.data),

  utilize: (limitId: string, amount: number) =>
    apiClient.post<ApiResponse<CreditLimit>>(`/credit/limits/${limitId}/utilize`, null, {
      params: { amount }
    }).then(r => r.data),

  release: (limitId: string, amount: number) =>
    apiClient.post<ApiResponse<CreditLimit>>(`/credit/limits/${limitId}/release`, null, {
      params: { amount }
    }).then(r => r.data),

  hold: (limitId: string, amount: number) =>
    apiClient.post<ApiResponse<CreditLimit>>(`/credit/limits/${limitId}/hold`, null, {
      params: { amount }
    }).then(r => r.data),

  releaseHold: (limitId: string, amount: number) =>
    apiClient.post<ApiResponse<CreditLimit>>(`/credit/limits/${limitId}/release-hold`, null, {
      params: { amount }
    }).then(r => r.data),

  suspend: (limitId: string, reason: string) =>
    apiClient.post<ApiResponse<CreditLimit>>(`/credit/limits/${limitId}/suspend`, null, {
      params: { reason }
    }).then(r => r.data),

  reactivate: (limitId: string) =>
    apiClient.post<ApiResponse<CreditLimit>>(`/credit/limits/${limitId}/reactivate`).then(r => r.data),

  cancel: (limitId: string, reason: string) =>
    apiClient.delete<ApiResponse<void>>(`/credit/limits/${limitId}`, {
      params: { reason }
    }).then(r => r.data),
  // ========================================================================
  // EXTERNAL LIMITS (READ-ONLY - Bank Provided)
  // ========================================================================

  /**
   * Get external (bank-provided) limits for corporate.
   * These are READ-ONLY for corporate users.
   */
  getExternalByCorporate: (corporateId: string) =>
    apiClient.get<ApiResponse<ExternalLimitResponse[]>>(
      `/credit/limits/external/corporate/${corporateId}`
    ).then(r => r.data),

  /**
   * Get external facilities for an entity.
   * Returns OD/Revolving facilities only.
   */
  getExternalFacilitiesForEntity: (entityId: string) =>
    apiClient.get<ApiResponse<FacilityResponse[]>>(
      `/credit/limits/external/entity/${entityId}/facilities`
    ).then(r => r.data),

  /**
   * Get external limit ceiling for an entity.
   * This is the maximum internal limit that can be set.
   */
  getExternalCeiling: (entityId: string, currency?: string) =>
    apiClient.get<ApiResponse<CeilingResponse>>(
      `/credit/limits/external/entity/${entityId}/ceiling`,
      { params: { currency } }
    ).then(r => r.data),

  // ========================================================================
  // INTERNAL LIMITS - GROUP (CFO Can Manage)
  // ========================================================================

  /**
   * Get group limit for a corporate.
   */
  getGroupLimit: (corporateId: string, currency?: string) =>
    apiClient.get<ApiResponse<InternalLimitResponse>>(
      `/credit/limits/internal/corporate/${corporateId}/group`,
      { params: { currency } }
    ).then(r => r.data),

  /**
   * Create a group-level internal limit.
   */
  createGroupLimit: (data: CreateGroupLimitRequest) =>
    apiClient.post<ApiResponse<InternalLimitResponse>>(
      '/credit/limits/internal/group',
      data
    ).then(r => r.data),

  /**
   * Update group limit amount.
   */
  updateGroupLimitAmount: (limitId: string, newAmount: number, updatedBy: string = 'SYSTEM') =>
    apiClient.put<ApiResponse<InternalLimitResponse>>(
      `/credit/limits/internal/group/${limitId}/amount`,
      null,
      { params: { newAmount, updatedBy } }
    ).then(r => r.data),

  // ========================================================================
  // INTERNAL LIMITS - ENTITY (CFO Can Manage)
  // ========================================================================

  /**
   * Get internal limits for corporate (group + all entities).
   */
  getInternalByCorporate: (corporateId: string) =>
    apiClient.get<ApiResponse<InternalLimitResponse[]>>(
      `/credit/limits/internal/corporate/${corporateId}`
    ).then(r => r.data),

  /**
   * Get entity sub-limits under a group limit.
   */
  getEntitySubLimits: (groupLimitId: string) =>
    apiClient.get<ApiResponse<InternalLimitResponse[]>>(
      `/credit/limits/internal/group/${groupLimitId}/entities`
    ).then(r => r.data),

  /**
   * Get internal limit for a specific entity.
   */
  getEntityInternalLimit: (entityId: string) =>
    apiClient.get<ApiResponse<InternalLimitResponse>>(
      `/credit/limits/internal/entity/${entityId}`
    ).then(r => r.data),

  /**
   * Create an entity sub-limit.
   */
  createEntitySubLimit: (data: CreateEntitySubLimitRequest) =>
    apiClient.post<ApiResponse<InternalLimitResponse>>(
      '/credit/limits/internal/entity',
      data
    ).then(r => r.data),

  /**
   * Update entity sub-limit amount.
   */
  updateEntitySubLimitAmount: (limitId: string, newAmount: number, updatedBy: string = 'SYSTEM') =>
    apiClient.put<ApiResponse<InternalLimitResponse>>(
      `/credit/limits/internal/entity/${limitId}/amount`,
      null,
      { params: { newAmount, updatedBy } }
    ).then(r => r.data),

  /**
   * Update limit control settings.
   */
  updateLimitControls: (limitId: string, data: UpdateControlsRequest) =>
    apiClient.put<ApiResponse<InternalLimitResponse>>(
      `/credit/limits/internal/${limitId}/controls`,
      data
    ).then(r => r.data),

  /**
   * Update thresholds.
   */
  // updateThresholds: (limitId: string, data: UpdateThresholdsRequest) =>
  //   apiClient.put<ApiResponse<InternalLimitResponse>>(
  //     `/credit/limits/internal/${limitId}/thresholds`,
  //     data
  //   ).then(r => r.data),

  /**
   * Delete entity sub-limit.
   */
  deleteEntitySubLimit: (limitId: string, reason: string) =>
    apiClient.delete<ApiResponse<void>>(
      `/credit/limits/internal/entity/${limitId}`,
      { params: { reason } }
    ).then(r => r.data),

  // ========================================================================
  // AVAILABILITY CHECK APIs
  // ========================================================================

  /**
   * Check funds availability for a transaction.
   */
  checkFundsAvailability: (entityId: string, amount: number, currency?: string) =>
    apiClient.get<ApiResponse<LimitCheckResult>>(
      `/credit/limits/check/entity/${entityId}/availability`,
      { params: { amount, currency } }
    ).then(r => r.data),

  /**
   * Check if transaction should be blocked.
   */
  checkIfBlocked: (entityId: string, amount: number) =>
    apiClient.get<ApiResponse<{ entityId: string; amount: number; blocked: boolean; reason?: string }>>(
      `/credit/limits/check/entity/${entityId}/blocked`,
      { params: { amount } }
    ).then(r => r.data),

  /**
   * Check if transaction needs approval.
   */
  checkIfApprovalNeeded: (entityId: string, amount: number) =>
    apiClient.get<ApiResponse<{ entityId: string; amount: number; approvalRequired: boolean; reason?: string }>>(
      `/credit/limits/check/entity/${entityId}/approval-required`,
      { params: { amount } }
    ).then(r => r.data),

  // ========================================================================
  // DASHBOARD & MONITORING APIs
  // ========================================================================

  /**
   * Get limit totals for corporate dashboard.
   */
  getDashboardTotals: (corporateId: string) =>
    apiClient.get<ApiResponse<LimitTotals>>(
      `/credit/limits/dashboard/corporate/${corporateId}/totals`
    ).then(r => r.data),

  /**
   * Get limits at warning level.
   */
  getWarningLimits: (corporateId: string) =>
    apiClient.get<ApiResponse<InternalLimitResponse[]>>(
      `/credit/limits/dashboard/corporate/${corporateId}/warnings`
    ).then(r => r.data),

  /**
   * Get limits at critical level.
   */
  getCriticalLimits: (corporateId: string) =>
    apiClient.get<ApiResponse<InternalLimitResponse[]>>(
      `/credit/limits/dashboard/corporate/${corporateId}/critical`
    ).then(r => r.data),

  /**
   * Get breached limits.
   */
  getBreachedLimits: (corporateId: string) =>
    apiClient.get<ApiResponse<InternalLimitResponse[]>>(
      `/credit/limits/dashboard/corporate/${corporateId}/breached`
    ).then(r => r.data),

  /**
   * Get expiring limits.
   */
  getExpiringLimits: (corporateId: string, daysAhead: number = 30) =>
    apiClient.get<ApiResponse<InternalLimitResponse[]>>(
      `/credit/limits/dashboard/corporate/${corporateId}/expiring`,
      { params: { daysAhead } }
    ).then(r => r.data),
};

export interface InitializeHierarchyRequest {
  /** Name for the ROOT node, e.g., "Group Treasury" */
  rootName?: string;
  /** Code for the ROOT node, e.g., "ROOT" */
  rootCode?: string;
  /** Base currency for the hierarchy, e.g., "AED" - required */
  baseCurrency: string;
  /** Whether to create Exception VA for unmatched transactions */
  createExceptionVa: boolean;
  /** Additional currencies for Exception VAs (multi-currency support) */
  additionalExceptionCurrencies?: string[];
  /** Optional: Hierarchy template to apply */
  templateType?: string;
}

export interface InitializationResponse {
  success: boolean;
  programId: string;
  programCode?: string;
  rootNodeId?: string;
  rootVaId?: string;
  rootVaNumber?: string;
  baseCurrency?: string;
  exceptionVaIds?: string[];
  exceptionCurrencies?: string[];
  status: string;  // INITIALIZED, ALREADY_INITIALIZED, FAILED
  message: string;
  initializedAt?: string;
}

export interface HierarchyStatusResponse {
  programId: string;
  programCode: string;
  programName: string;
  corporateId: string;
  corporateName?: string;
  initialized: boolean;
  rootNodeId?: string;
  rootVaId?: string;
  rootVaName?: string;
  baseCurrency?: string;
  nodeCount: number;
  vaCount: number;
  legalEntityCount: number;
  exceptionCurrencies?: string[];
  settlementVaCount: number;
  initializedAt?: string;
  templateType?: string;
}

export interface CreateAggregationRequest {
  /** Node name - required */
  name: string;
  /** Node code - required */
  code: string;
  /** Parent hierarchy node ID - required */
  parentNodeId: string;
  /** Currency code - defaults to parent's currency */
  currencyCode?: string;
  /** Optional: Legal Entity that owns this aggregation */
  owningEntityId?: string;
  /** Optional: Legal Entity code for display */
  owningEntityCode?: string;
  /** Dimension type - REGION, ENTITY, DEPARTMENT, etc. */
  dimensionType?: string;
  /** Actual dimension value */
  dimensionValue?: string;
  /** Display icon */
  icon?: string;
  /** Display color */
  color?: string;
}

export interface CreateAggregationResponse {
  success: boolean;
  nodeId?: string;
  vaId?: string;
  vaNumber?: string;
  nodeName?: string;
  nodeCode?: string;
  levelNumber?: number;
  materializedPath?: string;
  currencyCode?: string;
  owningEntityId?: string;
  message: string;
}

/**
 * Request to create a Transaction VA within a hierarchy.
 * Uses parent-based creation mode (parentNodeId).
 *
 * This is consistent with VirtualAccountDto.CreateRequest in the backend.
 */
export interface CreateTransactionVaRequest {
  /** VA name - required */
  vaName: string;
  /** Currency code - required */
  currencyCode: string;
  /** Parent hierarchy node ID - creates VA as child of this node */
  parentNodeId: string;
  /** Program ID - links VA to a specific program */
  programId?: string;
  /** Owning legal entity ID */
  owningEntityId?: string;
  /** Account purpose - OPERATING, COLLECTIONS, PAYABLES, etc. */
  accountPurpose?: string;
  /** Account category - always TRANSACTION for this request type */
  accountCategory?: 'TRANSACTION' | 'COLLECTION' | 'DISBURSEMENT';
  /** Account type - defaults to VIRTUAL */
  accountType?: 'REAL' | 'VIRTUAL';
  /** Whether to inherit limits from program - defaults to true */
  inheritProgramDefaults?: boolean;
  /** External reference for integration */
  externalReference?: string;
  /** Custom metadata JSON */
  metadata?: string;
}

// ============================================================================
// HIERARCHY VA API
// ============================================================================

export const hierarchyVaApi = {
  /**
   * Initialize hierarchy for a program.
   * Creates ROOT node, ROOT VA, and optional Exception VAs.
   */
  initialize: (programId: string, request: InitializeHierarchyRequest) =>
    apiClient.post<ApiResponse<InitializationResponse>>(
      `/programs/${programId}/hierarchy/initialize`,
      request
    ).then(r => r.data),

  /**
   * Get hierarchy initialization status.
   */
  getStatus: (programId: string) =>
    apiClient.get<ApiResponse<HierarchyStatusResponse>>(
      `/programs/${programId}/hierarchy/status`
    ).then(r => r.data),

  /**
   * Quick check if hierarchy is initialized.
   */
  isInitialized: (programId: string) =>
    apiClient.get<ApiResponse<{ programId: string; initialized: boolean; rootNodeId?: string; rootVaId?: string }>>(
      `/programs/${programId}/hierarchy/initialized`
    ).then(r => r.data),

  /**
   * Create an aggregation node under a parent.
   */
  createAggregation: (programId: string, request: CreateAggregationRequest) =>
    apiClient.post<ApiResponse<CreateAggregationResponse>>(
      `/programs/${programId}/hierarchy/aggregation`,
      request
    ).then(r => r.data),

  /**
   * Get hierarchy tree (existing endpoint).
   */
  getTree: (programId: string) =>
    apiClient.get<ApiResponse<any>>(
      `/programs/${programId}/hierarchy/tree`
    ).then(r => r.data),

  /**
   * Recalculate hierarchy balances (existing endpoint).
   */
  recalculate: (programId: string) =>
    apiClient.post<ApiResponse<void>>(
      `/programs/${programId}/hierarchy/refresh-balances`
    ).then(r => r.data),

  /**
   * Get level configurations (existing endpoint).
   */
  getLevelConfigs: (programId: string) =>
    apiClient.get<ApiResponse<any[]>>(
      `/programs/${programId}/hierarchy/config`
    ).then(r => r.data),

  /**
   * Apply hierarchy template (existing endpoint).
   */
  applyTemplate: (programId: string, templateType: string) =>
    apiClient.post<ApiResponse<any[]>>(
      `/programs/${programId}/hierarchy/config/template`,
      { templateType }
    ).then(r => r.data),

  /**
   * Save level configurations with allowed values.
   * This allows corporate users to customize hierarchy levels.
   */
  saveLevelConfigs: (programId: string, levels: Array<{
    levelNumber: number;
    levelName: string;
    dimensionType: string;
    isRequired?: boolean;
    allowedValues?: string[];
    description?: string;
    icon?: string;
  }>) =>
    apiClient.post<ApiResponse<any[]>>(
      `/programs/${programId}/hierarchy/config`,
      { levels }
    ).then(r => r.data),
}

// ============================================================================
// HIERARCHY VA API ADDITIONS
// ============================================================================
// Add these types and API methods to your existing api.ts file
// Location: frontend/src/services/api.ts
// ============================================================================

// ============================================================================
// 1. ADD THESE TYPES (around line 1040, after existing types)
// ============================================================================

export interface InitializeHierarchyRequest {
  rootName?: string;
  rootCode?: string;
  baseCurrency: string;
  createExceptionVa: boolean;
  additionalExceptionCurrencies?: string[];
  templateType?: string;
}

export interface InitializationResponse {
  success: boolean;
  programId: string;
  programCode?: string;
  rootNodeId?: string;
  rootVaId?: string;
  rootVaNumber?: string;
  baseCurrency?: string;
  exceptionVaIds?: string[];
  exceptionCurrencies?: string[];
  status: string;
  message: string;
  initializedAt?: string;
}

export interface HierarchyStatusResponse {
  programId: string;
  programCode: string;
  programName: string;
  corporateId: string;
  corporateName?: string;
  initialized: boolean;
  rootNodeId?: string;
  rootVaId?: string;
  rootVaName?: string;
  baseCurrency?: string;
  nodeCount: number;
  vaCount: number;
  legalEntityCount: number;
  exceptionCurrencies?: string[];
  settlementVaCount: number;
  initializedAt?: string;
  templateType?: string;
}

export interface CreateAggregationRequest {
  name: string;
  code: string;
  parentNodeId: string;
  currencyCode?: string;
  owningEntityId?: string;
  owningEntityCode?: string;
  dimensionType?: string;
  dimensionValue?: string;
  icon?: string;
  color?: string;
}

export interface CreateAggregationResponse {
  success: boolean;
  nodeId?: string;
  vaId?: string;
  vaNumber?: string;
  nodeName?: string;
  nodeCode?: string;
  levelNumber?: number;
  materializedPath?: string;
  currencyCode?: string;
  owningEntityId?: string;
  message: string;
}

// ============================================================================
// 2. ADD THIS API OBJECT (around line 1200, after balanceStructureApi)
// ============================================================================

// export const hierarchyVaApi = {
//   /**
//    * Initialize hierarchy for a program.
//    * Creates ROOT node, ROOT VA, and optional Exception VAs.
//    */
//   initialize: (programId: string, request: InitializeHierarchyRequest) =>
//     apiClient.post<ApiResponse<InitializationResponse>>(
//       `/programs/${programId}/hierarchy/initialize`,
//       request
//     ).then(r => r.data),

//   /**
//    * Get hierarchy initialization status.
//    */
//   getStatus: (programId: string) =>
//     apiClient.get<ApiResponse<HierarchyStatusResponse>>(
//       `/programs/${programId}/hierarchy/status`
//     ).then(r => r.data),

//   /**
//    * Quick check if hierarchy is initialized.
//    */
//   isInitialized: (programId: string) =>
//     apiClient.get<ApiResponse<{ programId: string; initialized: boolean; rootNodeId?: string; rootVaId?: string }>>(
//       `/programs/${programId}/hierarchy/initialized`
//     ).then(r => r.data),

//   /**
//    * Create an aggregation node under a parent.
//    */
//   createAggregation: (programId: string, request: CreateAggregationRequest) =>
//     apiClient.post<ApiResponse<CreateAggregationResponse>>(
//       `/programs/${programId}/hierarchy/aggregation`,
//       request
//     ).then(r => r.data),

//   /**
//    * Get hierarchy tree (uses existing endpoint).
//    */
//   getTree: (programId: string) =>
//     apiClient.get<ApiResponse<any>>(
//       `/programs/${programId}/hierarchy/tree`
//     ).then(r => r.data),

//   /**
//    * Recalculate hierarchy balances.
//    */
//   recalculate: (programId: string) =>
//     apiClient.post<ApiResponse<void>>(
//       `/programs/${programId}/hierarchy/refresh-balances`
//     ).then(r => r.data),

//   /**
//    * Get level configurations.
//    */
//   getLevelConfigs: (programId: string) =>
//     apiClient.get<ApiResponse<any[]>>(
//       `/programs/${programId}/hierarchy/config`
//     ).then(r => r.data),

//   /**
//    * Apply hierarchy template.
//    */
//   applyTemplate: (programId: string, templateType: string) =>
//     apiClient.post<ApiResponse<any[]>>(
//       `/programs/${programId}/hierarchy/config/template`,
//       { templateType }
//     ).then(r => r.data),
// };

// ============================================================================
// 3. UPDATE THE api EXPORT OBJECT (around line 1240)
// ============================================================================
// Add this line to the existing api export:
//
// export const api = {
//   ...existing entries...,
//   hierarchyVa: hierarchyVaApi,  // <-- ADD THIS LINE
// };
// // export default creditLimitApiEnhanced;
// ============================================================================
// EXPORT DEFAULT
// ============================================================================

// Add these types after existing IHB types (around line 665)

export interface IhbEntityUnified {
  id: string;
  entityCode: string;
  entityName: string;
  entityType: 'PARENT' | 'SUBSIDIARY' | 'BRANCH' | 'DIVISION' | 'JOINT_VENTURE';
  corporateId: string;
  ihbEnabled: boolean;
  ihbCreditLimit: number;
  ihbCurrentExposure: number;
  ihbAvailableLimit: number;
  lendingRateSpread: number;
  borrowingRateSpread: number;
  canLend: boolean;
  canBorrow: boolean;
  settlementVaId?: string;
  ihbCurrency: string;
  totalLentOut: number;
  totalDeposited: number;
  netIhbPosition: number;
  utilizationPercent: number;
  limitWarning: boolean;
  limitBreached: boolean;
}

export interface IhbStatsUnified {
  totalIhbEntities: number;
  activeLoans: number;
  activeDeposits: number;
  totalLoanOutstanding: number;
  totalDepositBalance: number;
  totalAccruedLoanInterest: number;
  totalAccruedDepositInterest: number;
  netPosition: number;
  netInterestIncome: number;
}

export interface EnableIhbRequest {
  creditLimit: number;
  ihbCurrency: string;
  canLend?: boolean;
  canBorrow?: boolean;
  lendingRateSpread?: number;
  borrowingRateSpread?: number;
  targetCashBalance?: number;
  autoSweepEnabled?: boolean;
  sweepFrequency?: string;
  ihbInterestConfigId?: string;
}

export interface CreateLoanUnifiedRequest {
  lenderEntityId: string;
  borrowerEntityId: string;
  principalAmount: number;
  currency: string;
  maturityDate?: string;
  notes?: string;
}

export interface CreateDepositUnifiedRequest {
  depositorEntityId: string;
  treasuryEntityId?: string;
  principalAmount: number;
  currency: string;
  maturityDate?: string;
  notes?: string;
}

export interface CalculateInterestResponse {
  totalLoanInterest: number;
  totalDepositInterest: number;
  totalSpread: number;
  netInterest: number;
  processedLoans: number;
  processedDeposits: number;
}

export interface TreasuryRates {
  treasuryCenterId: string;
  treasuryCenterCode: string;
  treasuryCenterName: string;
  ihbCurrency: string;
  lendingBaseRate: number;
  lendingBaseRateType: string;
  treasuryLendingSpread: number;
  indicativeLendingRate: number;
  depositBaseRate: number;
  depositBaseRateType: string;
  treasuryDepositSpread: number;
  indicativeDepositRate: number;
  dayCountConvention: string;
  compoundingFrequency: string;
  settlementFrequency: string;
  minLoanAmount: number;
  minDepositAmount: number;
  interestConfigId?: string;
  hasInterestConfig?: boolean;
  effectiveFrom?: string;
  effectiveTo?: string;
}

// Add this API object after existing ihbApi (around line 683)

export const ihbUnifiedApi = {
  // Entity Management (uses LegalEntity)
  getAllEntities: () =>
    apiClient.get<ApiResponse<IhbEntityUnified[]>>('/ihb/entities').then(r => r.data),

  getEntitiesByCorporate: (corporateId: string) =>
    apiClient.get<ApiResponse<IhbEntityUnified[]>>(`/ihb/corporate/${corporateId}/entities`).then(r => r.data),

  getEntityById: (entityId: string) =>
    apiClient.get<ApiResponse<IhbEntityUnified>>(`/ihb/entities/${entityId}`).then(r => r.data),

  enableIhb: (entityId: string, data: EnableIhbRequest) =>
    apiClient.post<ApiResponse<IhbEntityUnified>>(`/ihb/entities/${entityId}/enable`, data).then(r => r.data),

  updateSettings: (entityId: string, data: Partial<EnableIhbRequest>) =>
    apiClient.put<ApiResponse<IhbEntityUnified>>(`/ihb/entities/${entityId}/settings`, data).then(r => r.data),

  disableIhb: (entityId: string) =>
    apiClient.post<ApiResponse<void>>(`/ihb/entities/${entityId}/disable`).then(r => r.data),

  getEntityPosition: (entityId: string) =>
    apiClient.get<ApiResponse<any>>(`/ihb/entities/${entityId}/position`).then(r => r.data),

  // Loans
  getLoans: (corporateId: string) =>
    apiClient.get<ApiResponse<IhbLoan[]>>(`/ihb/corporate/${corporateId}/loans`).then(r => r.data),

  getActiveLoans: (corporateId: string) =>
    apiClient.get<ApiResponse<IhbLoan[]>>(`/ihb/corporate/${corporateId}/loans/active`).then(r => r.data),

  createLoan: (data: CreateLoanUnifiedRequest) =>
    apiClient.post<ApiResponse<IhbLoan>>('/ihb/loans', data).then(r => r.data),

  repayLoan: (loanId: string, amount: number) =>
    apiClient.post<ApiResponse<IhbLoan>>(`/ihb/loans/${loanId}/repay`, { amount }).then(r => r.data),

  // Deposits
  getDeposits: (corporateId: string) =>
    apiClient.get<ApiResponse<IhbDeposit[]>>(`/ihb/corporate/${corporateId}/deposits`).then(r => r.data),

  createDeposit: (data: CreateDepositUnifiedRequest) =>
    apiClient.post<ApiResponse<IhbDeposit>>('/ihb/deposits', data).then(r => r.data),

  withdrawDeposit: (depositId: string, amount: number) =>
    apiClient.post<ApiResponse<IhbDeposit>>(`/ihb/deposits/${depositId}/withdraw`, { amount }).then(r => r.data),

  // Interest & Stats
  calculateInterest: (corporateId: string) =>
    apiClient.post<ApiResponse<CalculateInterestResponse>>(`/ihb/corporate/${corporateId}/interest/calculate`).then(r => r.data),

  getStats: (corporateId: string) =>
    apiClient.get<ApiResponse<IhbStatsUnified>>(`/ihb/corporate/${corporateId}/stats`).then(r => r.data),

  // Treasury Rates
  getTreasuryRates: (corporateId: string) =>
    apiClient.get<ApiResponse<TreasuryRates>>(`/ihb/corporate/${corporateId}/treasury-rates`).then(r => r.data),

  // IHB Current Accounts (Creates TRANSACTION VA with ihbParticipant=true)
  createCurrentAccount: (data: {
    participantEntityId: string;
    currencyCode: string;
    programId?: string;
    parentNodeId?: string;
    creditLimit?: number;
    creditRate?: number;
    debitRate?: number;
    penaltyRate?: number;
    interestConfigId?: string;
    ihbSweepEnabled?: boolean;
    targetCashBalance?: number;
    ihbSweepFrequency?: string;
    vaName?: string;
  }) =>
    apiClient.post<ApiResponse<any>>('/ihb/current-account', data).then(r => r.data),

  getCurrentAccountsByCorporate: (corporateId: string) =>
    apiClient.get<ApiResponse<any[]>>(`/ihb/corporate/${corporateId}/current-accounts`).then(r => r.data),

  // Indicative Rate Calculation
  getIndicativeRate: (treasuryCenterId: string, entityId: string, request: {
    amount: number;
    currency: string;
    rateType: string;
    tenorDays?: number;
  }) =>
    apiClient.post<ApiResponse<any>>(
      `/ihb/indicative-rate?treasuryCenterId=${treasuryCenterId}&entityId=${entityId}`,
      request
    ).then(r => r.data),
};

// ============================================================================
// INTEREST ACCRUAL API
// Add this after interestConfigurationApi
// ============================================================================

export type AccrualType = 'LOAN' | 'DEPOSIT' | 'VA_CREDIT' | 'VA_DEBIT' | 'POOL_INTEREST' | 'SWEEP_INTEREST';
export type AccrualStatus = 'ACCRUED' | 'POSTED' | 'SETTLED' | 'REVERSED' | 'PENDING';

export interface InterestAccrual {
  id: string;
  accrualReference: string;
  accrualType: AccrualType;
  corporateId: string;
  entityId?: string;
  entityName?: string;
  virtualAccountId?: string;
  virtualAccountNumber?: string;
  loanId?: string;
  loanReference?: string;
  depositId?: string;
  depositReference?: string;
  interestConfigId?: string;
  interestConfigName?: string;
  currency: string;
  principalBalance: number;
  interestRate: number;
  dayCountConvention: string;
  accrualDate: string;
  periodStart: string;
  periodEnd: string;
  daysInPeriod: number;
  accruedAmount: number;
  cumulativeAccrued: number;
  status: AccrualStatus;
  postedAt?: string;
  settledAt?: string;
  settlementTransactionId?: string;
  notes?: string;
  createdAt: string;
}

export interface AccrualSummary {
  corporateId: string;
  periodStart: string;
  periodEnd: string;
  currency: string;
  totalLoanInterestAccrued: number;
  loanAccrualCount: number;
  avgLoanRate: number;
  totalDepositInterestAccrued: number;
  depositAccrualCount: number;
  avgDepositRate: number;
  totalVaCreditInterest: number;
  vaCreditAccrualCount: number;
  avgCreditRate: number;
  totalVaDebitInterest: number;
  vaDebitAccrualCount: number;
  avgDebitRate: number;
  netInterestIncome: number;
  spreadEarned: number;
  pendingAccruals: number;
  postedAccruals: number;
  settledAccruals: number;
}

export const interestAccrualApi = {
  getAccruals: (corporateId: string, params?: { 
    type?: AccrualType; 
    status?: AccrualStatus; 
    from?: string; 
    to?: string;
    page?: number;
    size?: number;
  }) => 
    apiClient.get<ApiResponse<InterestAccrual[]>>(`/interest-accruals/corporate/${corporateId}`, { params }).then(r => r.data),
  
  getSummary: (corporateId: string, from?: string, to?: string) => 
    apiClient.get<ApiResponse<AccrualSummary>>(`/interest-accruals/summary/${corporateId}`, { 
      params: { from, to } 
    }).then(r => r.data),
  
  runDailyAccrual: (corporateId: string, accrualDate?: string) => 
    apiClient.post<ApiResponse<any>>(`/interest-accruals/run/${corporateId}`, { accrualDate }).then(r => r.data),
  
  postAccruals: (corporateId: string, accrualIds: string[]) => 
    apiClient.post<ApiResponse<any>>('/interest-accruals/post', { corporateId, accrualIds }).then(r => r.data),
  
  settleAccruals: (corporateId: string, accrualIds: string[]) => 
    apiClient.post<ApiResponse<any>>('/interest-accruals/settle', { corporateId, accrualIds }).then(r => r.data),
  
  exportReport: (corporateId: string, from: string, to: string, format: 'CSV' | 'EXCEL' | 'PDF') => 
    apiClient.get(`/interest-accruals/export/${corporateId}`, { 
      params: { from, to, format },
      responseType: 'blob'
    }).then(r => r.data),
};


export interface NettingEntry {
  id: string;
  cycleId: string;
  entryReference: string;
  flowDirection: 'PAYABLE' | 'RECEIVABLE';
  payerEntityId: string;
  payerEntityCode: string;
  payerEntityName: string;
  payeeEntityId: string;
  payeeEntityCode: string;
  payeeEntityName: string;
  grossAmount: number;
  currencyCode: string;
  exchangeRate: number;
  baseAmount: number;
  originalCurrency?: string;
  originalAmount?: number;
  sourceType: 'INTERCOMPANY_PAYABLE' | 'INTERCOMPANY_RECEIVABLE' | 'POBO_RECHARGE' | 'COBO_COLLECTION' | 'IHB_LOAN' | 'IHB_DEPOSIT' | 'MANUAL';
  sourceReference?: string;
  payableId?: string;
  receivableId?: string;
  intercompanyRechargeId?: string;
  ihbTransactionId?: string;
  dueDate?: string;
  status: 'PENDING' | 'INCLUDED' | 'EXCLUDED' | 'SETTLED';
  createdAt: string;
}

export interface NettingPosition {
  entityId: string;
  entityCode: string;
  entityName: string;
  grossPayables: number;
  grossReceivables: number;
  netPosition: number;
  netDirection: 'PAY' | 'RECEIVE' | 'ZERO';
  entryCount: number;
  currency: string;
}

export interface NettingCycleDetail extends NettingCycle {
  entries: NettingEntry[];
  positions: NettingPosition[];
  settlementInstructions?: SettlementInstruction[];
}

export interface SettlementInstruction {
  id: string;
  cycleId: string;
  fromEntityId: string;
  fromEntityCode: string;
  fromEntityName: string;
  toEntityId: string;
  toEntityCode: string;
  toEntityName: string;
  amount: number;
  currency: string;
  status: 'PENDING' | 'EXECUTED' | 'FAILED';
  executedAt?: string;
}

export interface NettingStats {
  totalCycles: number;
  openCycles: number;
  pendingApproval: number;
  settledCyclesMtd: number;
  settledCyclesYtd: number;
  totalGrossVolume: number;
  totalNetVolume: number;
  totalSavings: number;
  avgSavingsPercent: number;
  activeParticipants: number;
}

export interface CreateNettingEntryRequest {
  cycleId: string;
  flowDirection: 'PAYABLE' | 'RECEIVABLE';
  payerEntityId: string;
  payeeEntityId: string;
  grossAmount: number;
  currencyCode: string;
  sourceType: string;
  sourceReference?: string;
  payableId?: string;
  receivableId?: string;
  dueDate?: string;
}

// ============================================================================
// PHASE 8: INTERCOMPANY TYPES
// ============================================================================

export interface IntercompanyTransaction {
  id: string;
  transactionRef: string;
  transactionType: 'POBO' | 'POBO_PAYMENT' | 'COBO' | 'COBO_COLLECTION' | 'SETTLEMENT' | 'INTEREST' | 'ADJUSTMENT' | 'IC_RECEIVABLE' | 'IC_PAYABLE';
  payingEntityId: string;
  payingEntityCode: string;
  payingEntityName?: string;
  behalfEntityId: string;
  behalfEntityCode: string;
  behalfEntityName?: string;
  amount: number;
  currencyCode: string;
  charges?: number;
  netAmount?: number;
  originalReference?: string;
  ihbLoanId?: string;
  ihbDepositId?: string;
  viban?: string;
  status: 'PENDING' | 'ACTIVE' | 'PROCESSED' | 'COMPLETED' | 'SETTLED' | 'REVERSED' | 'FAILED';
  statusReason?: string;
  settlementRef?: string;
  settledAt?: string;
  processedAt?: string;
  createdAt?: string;
}

export interface EntityPairSummary {
  entity1Id: string;
  entity1Code: string;
  entity2Id: string;
  entity2Code: string;
  entity1OwesEntity2: number;
  entity2OwesEntity1: number;
  netPosition: number;
  netCreditor: string;
  netDebtor: string;
  totalTransactions: number;
  pendingTransactions: number;
}

export interface BilateralPosition {
  entity1Id: string;
  entity1Code: string;
  entity1Name: string;
  entity2Id: string;
  entity2Code: string;
  entity2Name: string;
  entity1OwesEntity2: number;
  entity2OwesEntity1: number;
  netPosition: number;
  netCreditorId: string;
  netDebtorId: string;
  transactionCount: number;
  rechargeCount: number;
  currency: string;
  asOfDate: string;
}

export interface NettingEligibility {
  entity1Id: string;
  entity2Id: string;
  isEligible: boolean;
  eligibleTransactionCount: number;
  eligibleRechargeCount: number;
  totalEligibleAmount: number;
  grossPayables: number;
  grossReceivables: number;
  netAmount: number;
  netDirection: 'ENTITY1_RECEIVES' | 'ENTITY2_RECEIVES';
  activeCycleId?: string;
  activeCycleReference?: string;
  transactionIds: string[];
  rechargeIds: string[];
}

export interface IntercompanyStats {
  totalPoboTransactions: number;
  totalCoboTransactions: number;
  pendingSettlement: number;
  totalPoboVolume: number;
  totalCoboVolume: number;
  activeEntities: number;
}

export interface PoboPreview {
  payingEntityId: string;
  payingEntityCode: string;
  payingEntityName: string;
  behalfEntityId: string;
  behalfEntityCode: string;
  behalfEntityName: string;
  amount: number;
  currencyCode: string;
  charges: number;
  totalAmount: number;
  ihbLoanPreview?: {
    lenderId: string;
    borrowerId: string;
    principalAmount: number;
    interestRate: number;
    estimatedInterest: number;
  };
  withinCreditLimit: boolean;
  availableLimit: number;
  warnings: string[];
  chargeBreakdown: Array<{
    chargeType: string;
    description: string;
    amount: number;
  }>;
}

export interface PoboRequest {
  payingEntityId: string;
  behalfEntityId: string;
  amount: number;
  currencyCode?: string;
  description?: string;
  originalReference?: string;
  createIhbLoan?: boolean;
}

export interface PoboResult {
  transactionRef: string;
  status: string;
  message: string;
  payingEntityId: string;
  payingEntityCode: string;
  behalfEntityId: string;
  behalfEntityCode: string;
  amount: number;
  currencyCode: string;
  charges: number;
  totalAmount: number;
  ihbLoanId?: string;
  ihbLoanRef?: string;
  processedAt: string;
}

export interface CoboRequest {
  collectingEntityId: string;
  behalfEntityId: string;
  amount: number;
  currencyCode?: string;
  description?: string;
  generateViban?: boolean;
}

export interface CoboResult {
  transactionRef: string;
  status: string;
  message: string;
  collectingEntityId: string;
  collectingEntityCode: string;
  behalfEntityId: string;
  behalfEntityCode: string;
  amount: number;
  currencyCode: string;
  charges: number;
  totalAmount: number;
  viban?: string;
  vibanId?: string;
  createdAt: string;
}

export interface BilateralSettlementRequest {
  entity1Id: string;
  entity2Id: string;
  settlementMethod?: string;
  settledBy?: string;
  notes?: string;
}

export interface BilateralSettlementResult {
  settlementRef: string;
  entity1Id: string;
  entity2Id: string;
  netAmount: number;
  netCreditorId: string;
  netDebtorId: string;
  transactionsSettled: number;
  rechargesSettled: number;
  settlementMethod: string;
  settledAt: string;
  settledBy: string;
}

export interface IntercompanyPositionSummary {
  corporateId: string;
  totalEntities: number;
  totalOutstandingPayables: number;
  totalOutstandingReceivables: number;
  netPosition: number;
  pendingTransactions: number;
  pendingRecharges: number;
  currency: string;
  asOfDate: string;
}

export interface EntityIntercompanyReport {
  entityId: string;
  entityCode: string;
  entityName: string;
  reportPeriodStart: string;
  reportPeriodEnd: string;
  totalTransactions: number;
  totalPaidOnBehalf: number;
  totalReceivedOnBehalf: number;
  netPosition: number;
  poboTransactions: number;
  coboTransactions: number;
  currency: string;
  generatedAt: string;
}

// ============================================================================
// PHASE 7: ENHANCED NETTING API
// ============================================================================

export const nettingApiEnhanced = {
  // Existing methods
  ...nettingApi,

  // Get cycle with entries and positions
  getCycleDetails: (cycleId: string) =>
    apiClient.get<ApiResponse<NettingCycleDetail>>(`/netting/cycles/${cycleId}/details`).then(r => r.data),

  // Get entries for a cycle
  getEntries: (cycleId: string) =>
    apiClient.get<ApiResponse<NettingEntry[]>>(`/netting/cycles/${cycleId}/entries`).then(r => r.data),

  // Add entry to cycle
  addEntry: (data: CreateNettingEntryRequest) =>
    apiClient.post<ApiResponse<NettingEntry>>(`/netting/cycles/${data.cycleId}/entries`, data).then(r => r.data),

  // Remove entry from cycle
  removeEntry: (cycleId: string, entryId: string) =>
    apiClient.delete<ApiResponse<void>>(`/netting/cycles/${cycleId}/entries/${entryId}`).then(r => r.data),

  // Get positions for a cycle
  getPositions: (cycleId: string) =>
    apiClient.get<ApiResponse<NettingPosition[]>>(`/netting/cycles/${cycleId}/positions`).then(r => r.data),

  // Get settlement instructions
  getSettlementInstructions: (cycleId: string) =>
    apiClient.get<ApiResponse<SettlementInstruction[]>>(`/netting/cycles/${cycleId}/settlement-instructions`).then(r => r.data),

  // Get statistics
  getStats: (corporateId?: string) =>
    apiClient.get<ApiResponse<NettingStats>>('/netting/stats', { params: { corporateId } }).then(r => r.data),

  // Add payables to cycle
  addPayablesToCycle: (cycleId: string, payableIds: string[]) =>
    apiClient.post<ApiResponse<{ entriesAdded: number }>>(`/netting/cycles/${cycleId}/add-payables`, { payableIds }).then(r => r.data),

  // Add receivables to cycle
  addReceivablesToCycle: (cycleId: string, receivableIds: string[]) =>
    apiClient.post<ApiResponse<{ entriesAdded: number }>>(`/netting/cycles/${cycleId}/add-receivables`, { receivableIds }).then(r => r.data),

  // Add recharges to cycle
  addRechargesToCycle: (cycleId: string, rechargeIds: string[]) =>
    apiClient.post<ApiResponse<{ entriesAdded: number }>>(`/netting/cycles/${cycleId}/add-recharges`, { rechargeIds }).then(r => r.data),

  // Auto-populate eligible entries
  autoPopulate: (cycleId: string, options: { includePayables?: boolean; includeReceivables?: boolean; includeRecharges?: boolean }) =>
    apiClient.post<ApiResponse<{ entriesAdded: number }>>(`/netting/cycles/${cycleId}/auto-populate`, options).then(r => r.data),
};

// ============================================================================
// PHASE 8: INTERCOMPANY API
// ============================================================================

export const intercompanyApiEnhanced = {
  // Existing intercompanyApi methods if any...

  // Create IC transaction
  createTransaction: (data: { sourceEntityCode: string; targetEntityCode: string; amount: number; currencyCode?: string; transactionType?: string; description?: string }) =>
    apiClient.post<ApiResponse<IntercompanyTransaction>>('/intercompany/transactions', data).then(r => r.data),

  // Create from payable
  createFromPayable: (payableId: string) =>
    apiClient.post<ApiResponse<IntercompanyTransaction>>(`/intercompany/transactions/from-payable/${payableId}`).then(r => r.data),

  // Create from receivable
  createFromReceivable: (receivableId: string) =>
    apiClient.post<ApiResponse<IntercompanyTransaction>>(`/intercompany/transactions/from-receivable/${receivableId}`).then(r => r.data),

  // Get entity pairs
  getEntityPairs: (corporateId: string) =>
    apiClient.get<ApiResponse<EntityPairSummary[]>>('/intercompany/entity-pairs', { params: { corporateId } }).then(r => r.data),

  // Get bilateral position
  getBilateralPosition: (entity1Id: string, entity2Id: string) =>
    apiClient.get<ApiResponse<BilateralPosition>>('/intercompany/bilateral-position', { params: { entity1Id, entity2Id } }).then(r => r.data),

  // Check netting eligibility
  checkNettingEligibility: (entity1Id: string, entity2Id: string) =>
    apiClient.get<ApiResponse<NettingEligibility>>('/intercompany/netting-eligibility', { params: { entity1Id, entity2Id } }).then(r => r.data),

  // Add to netting cycle
  addToNettingCycle: (cycleId: string, transactionIds: string[], rechargeIds: string[]) =>
    apiClient.post<ApiResponse<{ transactionsAdded: number; rechargesAdded: number }>>(`/intercompany/add-to-netting/${cycleId}`, { transactionIds, rechargeIds }).then(r => r.data),

  // Settle bilateral
  settleBilateral: (data: BilateralSettlementRequest) =>
    apiClient.post<ApiResponse<BilateralSettlementResult>>('/intercompany/settle-bilateral', data).then(r => r.data),

  // Validate transfer pricing
  validateTransferPricing: (transactionId: string, notes?: string) =>
    apiClient.post<ApiResponse<any>>(`/intercompany/transactions/${transactionId}/validate-transfer-pricing`, null, { params: { notes } }).then(r => r.data),

  // Get position summary
  getPositionSummary: (corporateId: string) =>
    apiClient.get<ApiResponse<IntercompanyPositionSummary>>('/intercompany/position-summary', { params: { corporateId } }).then(r => r.data),

  // Get entity report
  getEntityReport: (entityId: string, startDate: string, endDate: string) =>
    apiClient.get<ApiResponse<EntityIntercompanyReport>>(`/intercompany/entity-report/${entityId}`, { params: { startDate, endDate } }).then(r => r.data),

  // Get stats
  getStats: (corporateId?: string) =>
    apiClient.get<ApiResponse<IntercompanyStats>>('/intercompany/stats', { params: { corporateId } }).then(r => r.data),

  // POBO Operations
  pobo: {
    preview: (data: PoboRequest) =>
      apiClient.post<ApiResponse<PoboPreview>>('/intercompany/pobo/preview', data).then(r => r.data),
    
    execute: (data: PoboRequest) =>
      apiClient.post<ApiResponse<PoboResult>>('/intercompany/pobo/execute', data).then(r => r.data),
    
    getTransactions: (entityId?: string, role?: string, status?: string) =>
      apiClient.get<ApiResponse<IntercompanyTransaction[]>>('/intercompany/pobo', { params: { entityId, role, status } }).then(r => r.data),
  },

  // COBO Operations
  cobo: {
    setup: (data: CoboRequest) =>
      apiClient.post<ApiResponse<CoboResult>>('/intercompany/cobo/setup', data).then(r => r.data),
    
    processCollection: (transactionRef: string, amount: number) =>
      apiClient.post<ApiResponse<any>>(`/intercompany/cobo/${transactionRef}/collect`, { amount }).then(r => r.data),
    
    getTransactions: (entityId?: string, role?: string, status?: string) =>
      apiClient.get<ApiResponse<IntercompanyTransaction[]>>('/intercompany/cobo', { params: { entityId, role, status } }).then(r => r.data),
  },

  // Settlement
  settlement: {
    getUnsettled: (entityId?: string) =>
      apiClient.get<ApiResponse<IntercompanyTransaction[]>>('/intercompany/unsettled', { params: { entityId } }).then(r => r.data),
    
    calculateNet: (entity1Id: string, entity2Id: string) =>
      apiClient.post<ApiResponse<any>>('/intercompany/settlement/calculate', { entity1Id, entity2Id }).then(r => r.data),
    
    execute: (transactionIds: string[], settlementMethod?: string) =>
      apiClient.post<ApiResponse<any>>('/intercompany/settlement/execute', { transactionIds, settlementMethod }).then(r => r.data),
    
    getHistory: (entityId?: string) =>
      apiClient.get<ApiResponse<any[]>>('/intercompany/settlement/history', { params: { entityId } }).then(r => r.data),
  },

  // Entities
  entities: {
    getAll: (status?: string) =>
      apiClient.get<ApiResponse<any[]>>('/intercompany/entities', { params: { status } }).then(r => r.data),
    
    getWithPosition: (entityId: string) =>
      apiClient.get<ApiResponse<any>>(`/intercompany/entities/${entityId}`).then(r => r.data),
    
    getPositionDetail: (entityId: string) =>
      apiClient.get<ApiResponse<any>>(`/intercompany/entities/${entityId}/position-detail`).then(r => r.data),
    
    validate: (entityId: string, amount: number) =>
      apiClient.post<ApiResponse<any>>(`/intercompany/entities/${entityId}/validate`, { amount }).then(r => r.data),
  },
};

export default {
  dashboard: dashboardApi,
  corporates: corporatesApi,
  virtualAccounts: virtualAccountsApi,
  physicalAccounts: physicalAccountsApi,
  transactions: transactionsApi,
  beneficiaries: beneficiariesApi,
  statements: statementsApi,
  sweeping: sweepingApi,
  pooling: poolingApi,
  netting: nettingApi,
  ihb: ihbApi,
  wallets: walletsApi,
  escrow: escrowApi,
  ecommerce: ecommerceApi,
  receivables: receivablesApi,
  receivablesPhase3: receivablesApiPhase3,
  payables: payablesApi,
  payablesPhase2: payablesApiPhase2,
  kyc: kycApi,
  viban: vibanApi,
  syncAdmin: syncAdminApi,
  treasuryHierarchy: treasuryHierarchyApi,
  balanceStructure: balanceStructureApi,
  integrations: integrationsApi,
  taxCharge: taxChargeApi,
  intercompany: intercompanyApi,
  parties: partiesApi,
  hierarchy: hierarchyApi,
  hierarchyVa: hierarchyVaApi,
  pobo: poboApi,
  settlementVa: settlementVaApi,
  exception: exceptionApi,
  programs: programsApi,
  fxRate: fxRateApi,
  shadowAccount: shadowAccountApi,
  currencyMirror: currencyMirrorApi,
  creditLimit: creditLimitApi,
  fundsAvailability: fundsAvailabilityApi,
  balanceAggregation: balanceAggregationApi,
  agreements: creditAgreementsApi,
  facilities: creditFacilitiesApi,
  legalEntity: legalEntityApi,
  accountAttachment: accountAttachmentApi,
  interestConfiguration: interestConfigurationApi,
  ihbUnified: ihbUnifiedApi,
  interestAccrual: interestAccrualApi,
  nettingEnhanced: nettingApiEnhanced,
  intercompanyEnhanced: intercompanyApiEnhanced,
};

// ============================================================================
// ISO 20022 PAYMENT PROCESSING API
// ============================================================================

export interface Iso20022InwardPaymentRequest {
  messageId?: string;
  instructionId?: string;
  endToEndId?: string;
  amount: number;
  currency: string;
  creditorAccount: string;  // VIBAN
  debtorName?: string;
  debtorAccount?: string;
  debtorBic?: string;
  creditorName?: string;
  remittanceInfo?: string;
  structuredRef?: string;
  channel?: string;
}

export interface Iso20022InwardPaymentResponse {
  success: boolean;
  statusCode: string;
  statusReason: string;
  transactionReference: string;
  transactionId: string;
  virtualAccountId: string;
  vaNumber?: string;
  vibanId: string;
  viban: string;
  balanceBefore: number;
  balanceAfter: number;
  autoReconciled: boolean;
  reconciledReferenceType?: string;
  reconciledReferenceId?: string;
  processingTimeMs: number;
  errorCode?: string;
  errorMessage?: string;
}

export interface Iso20022OutwardPaymentRequest {
  sourceVaId: string;
  messageId?: string;
  paymentInfoId?: string;
  instructionId?: string;
  endToEndId?: string;
  amount: number;
  currency?: string;
  debtorName?: string;
  debtorAccount?: string;
  debtorBic?: string;
  creditorName: string;
  creditorAccount: string;
  creditorBic?: string;
  creditorBankName?: string;
  remittanceInfo?: string;
  structuredRef?: string;
  requestedExecutionDate?: string;
  isPobo?: boolean;
  behalfOfEntity?: string;
  behalfOfVaId?: string;
  instructionPriority?: 'NORM' | 'HIGH';
  serviceLevel?: 'SEPA' | 'NURG' | 'URGP';
}

export interface Iso20022OutwardPaymentResponse {
  success: boolean;
  statusCode: string;
  statusReason: string;
  transactionReference: string;
  transactionId: string;
  messageId: string;
  pain001Xml?: string;
  sourceVaId: string;
  balanceBefore: number;
  balanceAfter: number;
  feeAmount: number;
  netAmount: number;
  processingTimeMs: number;
  errorCode?: string;
  errorMessage?: string;
}

export interface Iso20022PaymentStatusRequest {
  originalMessageId?: string;
  originalInstructionId?: string;
  originalEndToEndId?: string;
  transactionReference?: string;
}

export interface Iso20022PaymentStatusResponse {
  messageId: string;
  originalMessageId: string;
  originalInstructionId?: string;
  originalEndToEndId?: string;
  transactionStatus: string;
  statusReasonCode?: string;
  statusReasonDescription?: string;
  transactionId: string;
  transactionReference: string;
  amount: number;
  currency: string;
  transactionDate: string;
  valueDate?: string;
  pain002Xml?: string;
}

export interface Iso20022StatementRequest {
  virtualAccountId?: string;
  vaNumber?: string;
  fromDate?: string;
  toDate?: string;
  format?: 'CAMT053' | 'CAMT052';
}

export interface Iso20022StatementResponse {
  messageId: string;
  statementId: string;
  accountIban: string;
  accountCurrency: string;
  fromDate: string;
  toDate: string;
  openingBalance: number;
  closingBalance: number;
  creditCount: number;
  creditSum: number;
  debitCount: number;
  debitSum: number;
  entryCount: number;
  camt053Xml?: string;
}

export interface Iso20022BulkPaymentRequest {
  sourceVaId: string;
  messageId?: string;
  paymentInfoId?: string;
  debtorName?: string;
  debtorAccount?: string;
  debtorBic?: string;
  requestedExecutionDate?: string;
  serviceLevel?: string;
  instructions: Iso20022PaymentInstruction[];
  isPobo?: boolean;
  behalfOfEntity?: string;
  behalfOfVaId?: string;
}

export interface Iso20022PaymentInstruction {
  instructionId?: string;
  endToEndId?: string;
  amount: number;
  currency?: string;
  creditorName: string;
  creditorAccount: string;
  creditorBic?: string;
  remittanceInfo?: string;
  structuredRef?: string;
}

export interface Iso20022BulkPaymentResponse {
  success: boolean;
  messageId: string;
  pain001Xml?: string;
  totalCount: number;
  successCount: number;
  failedCount: number;
  totalAmount: number;
  totalFees: number;
  results: Iso20022PaymentInstructionResult[];
  processingTimeMs: number;
}

export interface Iso20022PaymentInstructionResult {
  instructionId?: string;
  endToEndId?: string;
  success: boolean;
  transactionReference?: string;
  transactionId?: string;
  errorCode?: string;
  errorMessage?: string;
}

export const iso20022Api = {
  // Inward Payments (ROBO Credits)
  processInwardPayment: (data: Iso20022InwardPaymentRequest) =>
    apiClient.post<ApiResponse<Iso20022InwardPaymentResponse>>('/iso20022/inward/payment', data).then(r => r.data),

  processPacs008: (xml: string) =>
    apiClient.post<ApiResponse<Iso20022InwardPaymentResponse>>('/iso20022/inward/pacs008', xml, {
      headers: { 'Content-Type': 'application/xml' }
    }).then(r => r.data),

  processCamt054: (xml: string) =>
    apiClient.post<ApiResponse<Iso20022InwardPaymentResponse>>('/iso20022/inward/camt054', xml, {
      headers: { 'Content-Type': 'application/xml' }
    }).then(r => r.data),

  // Outward Payments (POBO Debits)
  processOutwardPayment: (data: Iso20022OutwardPaymentRequest) =>
    apiClient.post<ApiResponse<Iso20022OutwardPaymentResponse>>('/iso20022/outward/payment', data).then(r => r.data),

  processPoboPayment: (data: Iso20022OutwardPaymentRequest) =>
    apiClient.post<ApiResponse<Iso20022OutwardPaymentResponse>>('/iso20022/outward/pobo', data).then(r => r.data),

  processBulkPayment: (data: Iso20022BulkPaymentRequest) =>
    apiClient.post<ApiResponse<Iso20022BulkPaymentResponse>>('/iso20022/outward/bulk', data).then(r => r.data),

  // Generate pain.001 XML
  generatePain001: (data: Iso20022OutwardPaymentRequest) =>
    apiClient.post('/iso20022/outward/pain001/generate', data, {
      responseType: 'text',
      headers: { 'Accept': 'application/xml' }
    }).then(r => r.data),

  generateBulkPain001: (data: Iso20022BulkPaymentRequest) =>
    apiClient.post('/iso20022/outward/bulk/pain001/generate', data, {
      responseType: 'text',
      headers: { 'Accept': 'application/xml' }
    }).then(r => r.data),

  // Payment Status
  getPaymentStatus: (data: Iso20022PaymentStatusRequest) =>
    apiClient.post<ApiResponse<Iso20022PaymentStatusResponse>>('/iso20022/status', data).then(r => r.data),

  getPaymentStatusXml: (data: Iso20022PaymentStatusRequest) =>
    apiClient.post('/iso20022/status/pain002', data, {
      responseType: 'text',
      headers: { 'Accept': 'application/xml' }
    }).then(r => r.data),

  // Bank Statements (camt.053)
  generateStatement: (data: Iso20022StatementRequest) =>
    apiClient.post<ApiResponse<Iso20022StatementResponse>>('/iso20022/statement', data).then(r => r.data),

  generateCamt053: (data: Iso20022StatementRequest) =>
    apiClient.post('/iso20022/statement/camt053', data, {
      responseType: 'text',
      headers: { 'Accept': 'application/xml' }
    }).then(r => r.data),

  getStatementByVaId: (vaId: string, fromDate?: string, toDate?: string) =>
    apiClient.get<ApiResponse<Iso20022StatementResponse>>(`/iso20022/statement/${vaId}`, {
      params: { fromDate, toDate }
    }).then(r => r.data),
};

// ============================================================================
// MULTI-BANK LIQUIDITY (v1)
// ============================================================================
export interface ShadowSummary {
  vaId: string;
  vaNumber: string;
  vaName: string;
  currencyCode: string;
  bankBic: string;
  bankName?: string;
  bankAccountNumber?: string;
  bankIban?: string;
  homeBankHeld: boolean;
  owningEntityCode?: string;
  bankBalance: number;
  bankAvailableBalance?: number;
  bankBalanceCommitted: number;
  bankBalanceEffective: number;
  balanceDataSource?: string;
  lastBalanceRefreshAt?: string;
  lastBalanceRefreshStatus: 'SUCCESS' | 'FAILED' | 'STALE' | 'NEVER';
  stale: boolean;
}

export interface MultiBankCurrencyBucket {
  currencyCode: string;
  shadowCount: number;
  totalBankBalance: number;
  totalCommitted: number;
  totalEffective: number;
  shadows: ShadowSummary[];
}

export interface MultiBankBankBucket {
  bankBic: string;
  bankName?: string;
  homeBank: boolean;
  shadowCount: number;
  currencies: MultiBankCurrencyBucket[];
}

export interface MultiBankLiquiditySummary {
  homeBankBic: string;
  homeBankName?: string;
  totalShadows: number;
  homeBankShadows: number;
  externalShadows: number;
  staleCount: number;
  neverRefreshedCount: number;
  banks: MultiBankBankBucket[];
}

export const multiBankLiquidityApi = {
  getSummary: (corporateId?: string) =>
    apiClient.get<ApiResponse<MultiBankLiquiditySummary>>('/treasury/multi-bank/summary', {
      params: corporateId ? { corporateId } : {}
    }).then(r => r.data),

  refresh: (shadowVaId: string) =>
    apiClient.post<ApiResponse<string>>(`/treasury/multi-bank/shadows/${shadowVaId}/refresh`).then(r => r.data),

  refreshIfStale: (shadowVaId: string) =>
    apiClient.post<ApiResponse<string>>(`/treasury/multi-bank/shadows/${shadowVaId}/refresh-if-stale`).then(r => r.data),
};

// ============================================================================
// MARKET PROFILE (MP1)
// ============================================================================
export interface MarketProfile {
  code: string;
  displayName: string;
  defaultCurrency: string;
  defaultCountryCode: string;
  defaultLocale: string;
  defaultTimezone: string;
  defaultBaseRateType: string;
  weekend: 'SAT_SUN' | 'FRI_SAT' | 'SUN_THU';
  homeBankBic: string;
  homeBankName: string;
  ibanCountryCode: string;
  suggestedCurrencies: string[];
}

export const marketProfileApi = {
  get: () => apiClient.get<ApiResponse<MarketProfile>>('/config/market-profile').then(r => r.data),
};

// ============================================================================
// CASH FORECASTING (T10 — Sprint 1)
// ============================================================================
export type ForecastSource = 'PATTERN' | 'AGING' | 'ML' | 'DRIVER' | 'MANUAL';
export type ForecastDirection = 'IN' | 'OUT';
export type RunStatus = 'RUNNING' | 'COMPLETED' | 'FAILED';

export interface ForecastWeeklyBucket {
  weekStart: string;
  weekEnd: string;
  netCashflow: number;
  closingBalance: number;
}

export interface ForecastSummary {
  runId: string;
  runAt: string;
  horizonEnd: string;
  currency: string;
  openingBalance: number;
  closingBalance: number;
  troughWeek: ForecastWeeklyBucket;
  weeklyBuckets: ForecastWeeklyBucket[];
}

export interface ForecastLine {
  id: string;
  valueDate: string;
  entityId: string;
  currency: string;
  categoryCode: string;
  categoryLabel: string;
  amountMid: number;
  source: ForecastSource;
  sourceRef: string | null;
  confidence: number | null;
}

export interface ForecastRun {
  runId: string;
  status: RunStatus;
  runAt: string;
  horizonEnd: string;
  generationMs: number | null;
}

export const forecastApi = {
  getLatest: (params: { entityId?: string; currency?: string; horizonDays?: number }) =>
    apiClient.get<ApiResponse<ForecastSummary>>('/forecasts/latest', { params }).then(r => r.data),

  getLines: (runId: string, params: { fromDate?: string; toDate?: string; groupBy?: 'NONE' | 'CATEGORY' | 'ENTITY' | 'COUNTERPARTY' }) =>
    apiClient.get<ApiResponse<ForecastLine[]>>(`/forecasts/${runId}/lines`, { params }).then(r => r.data),

  triggerRun: () =>
    apiClient.post<ApiResponse<ForecastRun>>('/forecasts/run').then(r => r.data),

  getRun: (runId: string) =>
    apiClient.get<ApiResponse<ForecastRun>>(`/forecasts/${runId}`).then(r => r.data),
};