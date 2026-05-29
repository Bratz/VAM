// ============================================================================
// VIRTUAL ACCOUNT API SERVICE
// Comprehensive API client for VA operations
// ============================================================================

import {
  VaResponse,
  VaSummary,
  VaStats,
  CreateVaRequest,
  UpdateVaRequest,
  LimitsUpdateRequest,
  KycUpdateRequest,
  MccRestrictionsRequest,
  BulkStatusUpdateRequest,
  BulkLimitsUpdateRequest,
  BulkOperationResponse,
  BalanceResponse,
  LimitsInfo,
  LimitsUsage,
  MccRestrictions,
  ProgramTypeConfig,
  ApiResponse,
  PagedResponse,
  VaSearchParams,
} from './vaTypes';

// ============================================================================
// CONFIGURATION
// ============================================================================

const API_BASE = '/api/v1/virtual-accounts';

// ============================================================================
// API CLIENT
// ============================================================================

class VaApiClient {
  private baseUrl: string;

  constructor(baseUrl: string = API_BASE) {
    this.baseUrl = baseUrl;
  }

  // Generic fetch wrapper
  private async fetch<T>(
    endpoint: string,
    options?: RequestInit
  ): Promise<T> {
    const url = `${this.baseUrl}${endpoint}`;
    
    const response = await fetch(url, {
      headers: {
        'Content-Type': 'application/json',
        ...options?.headers,
      },
      ...options,
    });

    if (!response.ok) {
      const error = await response.json().catch(() => ({
        message: `Request failed with status ${response.status}`,
      }));
      throw new Error(error.message || `API error: ${response.status}`);
    }

    const result: ApiResponse<T> = await response.json();
    return result.data;
  }

  // Paged fetch wrapper
  private async fetchPaged<T>(
    endpoint: string,
    options?: RequestInit
  ): Promise<{ data: T[]; page: number; size: number; totalElements: number }> {
    const url = `${this.baseUrl}${endpoint}`;
    
    const response = await fetch(url, {
      headers: {
        'Content-Type': 'application/json',
        ...options?.headers,
      },
      ...options,
    });

    if (!response.ok) {
      const error = await response.json().catch(() => ({
        message: `Request failed with status ${response.status}`,
      }));
      throw new Error(error.message || `API error: ${response.status}`);
    }

    const result: PagedResponse<T> = await response.json();
    return {
      data: result.data,
      page: result.page,
      size: result.size,
      totalElements: result.totalElements,
    };
  }

  // ========================================================================
  // READ OPERATIONS
  // ========================================================================

  /**
   * Get all virtual accounts with pagination and filtering
   */
  async getAll(params: VaSearchParams = {}): Promise<{
    data: VaResponse[];
    page: number;
    size: number;
    totalElements: number;
  }> {
    const query = new URLSearchParams();
    if (params.page !== undefined) query.set('page', params.page.toString());
    if (params.size !== undefined) query.set('size', params.size.toString());
    if (params.status) query.set('status', params.status);
    if (params.walletType) query.set('walletType', params.walletType);
    if (params.currencyCode) query.set('currencyCode', params.currencyCode);
    if (params.sort) query.set('sort', params.sort);
    
    return this.fetchPaged<VaResponse>(`?${query.toString()}`);
  }

  /**
   * Get virtual account by ID with full details
   */
  async getById(id: string): Promise<VaResponse> {
    return this.fetch<VaResponse>(`/${id}`);
  }

  /**
   * Get virtual account summary (lightweight)
   */
  async getSummary(id: string): Promise<VaSummary> {
    return this.fetch<VaSummary>(`/${id}/summary`);
  }

  /**
   * Get virtual account by VA number
   */
  async getByVaNumber(vaNumber: string): Promise<VaResponse> {
    return this.fetch<VaResponse>(`/by-number/${vaNumber}`);
  }

  /**
   * Get virtual account by VIBAN
   */
  async getByViban(viban: string): Promise<VaResponse> {
    return this.fetch<VaResponse>(`/by-viban/${viban}`);
  }

  /**
   * Search virtual accounts
   */
  async search(query: string, page = 0, size = 10): Promise<{
    data: VaResponse[];
    page: number;
    size: number;
    totalElements: number;
  }> {
    return this.fetchPaged<VaResponse>(
      `/search?query=${encodeURIComponent(query)}&page=${page}&size=${size}`
    );
  }

  /**
   * Get virtual accounts by corporate
   */
  async getByCorporate(corporateId: string, page = 0, size = 10): Promise<{
    data: VaResponse[];
    page: number;
    size: number;
    totalElements: number;
  }> {
    return this.fetchPaged<VaResponse>(
      `/corporate/${corporateId}?page=${page}&size=${size}`
    );
  }

  /**
   * Get virtual accounts by program
   */
  async getByProgram(programId: string, page = 0, size = 10): Promise<{
    data: VaResponse[];
    page: number;
    size: number;
    totalElements: number;
  }> {
    return this.fetchPaged<VaResponse>(
      `/program/${programId}?page=${page}&size=${size}`
    );
  }

  /**
   * Get virtual accounts by hierarchy node
   */
  async getByHierarchy(
    nodeId: string,
    includeDescendants = false,
    page = 0,
    size = 20
  ): Promise<{
    data: VaResponse[];
    page: number;
    size: number;
    totalElements: number;
  }> {
    return this.fetchPaged<VaResponse>(
      `/by-hierarchy/${nodeId}?includeDescendants=${includeDescendants}&page=${page}&size=${size}`
    );
  }

  /**
   * Lookup accounts (for dropdowns/autocomplete)
   */
  async lookup(params: {
    query?: string;
    corporateId?: string;
    programId?: string;
    limit?: number;
  } = {}): Promise<VaSummary[]> {
    const query = new URLSearchParams();
    if (params.query) query.set('query', params.query);
    if (params.corporateId) query.set('corporateId', params.corporateId);
    if (params.programId) query.set('programId', params.programId);
    if (params.limit) query.set('limit', params.limit.toString());
    
    return this.fetch<VaSummary[]>(`/lookup?${query.toString()}`);
  }

  // ========================================================================
  // CREATE & UPDATE
  // ========================================================================

  /**
   * Create a new virtual account
   */
  async create(data: CreateVaRequest): Promise<VaResponse> {
    return this.fetch<VaResponse>('', {
      method: 'POST',
      body: JSON.stringify(data),
    });
  }

  /**
   * Update a virtual account
   */
  async update(id: string, data: UpdateVaRequest): Promise<VaResponse> {
    return this.fetch<VaResponse>(`/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data),
    });
  }

  // ========================================================================
  // LIMITS MANAGEMENT
  // ========================================================================

  /**
   * Update limits
   */
  async updateLimits(id: string, data: LimitsUpdateRequest): Promise<VaResponse> {
    return this.fetch<VaResponse>(`/${id}/limits`, {
      method: 'PATCH',
      body: JSON.stringify(data),
    });
  }

  /**
   * Get limits info
   */
  async getLimits(id: string): Promise<LimitsInfo> {
    return this.fetch<LimitsInfo>(`/${id}/limits`);
  }

  /**
   * Get limits usage
   */
  async getLimitsUsage(id: string): Promise<LimitsUsage> {
    return this.fetch<LimitsUsage>(`/${id}/limits/usage`);
  }

  /**
   * Reset daily limits
   */
  async resetDailyLimits(id: string): Promise<VaResponse> {
    return this.fetch<VaResponse>(`/${id}/limits/reset/daily`, {
      method: 'POST',
    });
  }

  /**
   * Reset weekly limits
   */
  async resetWeeklyLimits(id: string): Promise<VaResponse> {
    return this.fetch<VaResponse>(`/${id}/limits/reset/weekly`, {
      method: 'POST',
    });
  }

  /**
   * Reset monthly limits
   */
  async resetMonthlyLimits(id: string): Promise<VaResponse> {
    return this.fetch<VaResponse>(`/${id}/limits/reset/monthly`, {
      method: 'POST',
    });
  }

  /**
   * Reset annual limits
   */
  async resetAnnualLimits(id: string): Promise<VaResponse> {
    return this.fetch<VaResponse>(`/${id}/limits/reset/annual`, {
      method: 'POST',
    });
  }

  // ========================================================================
  // KYC MANAGEMENT
  // ========================================================================

  /**
   * Update KYC
   */
  async updateKyc(id: string, data: KycUpdateRequest): Promise<VaResponse> {
    return this.fetch<VaResponse>(`/${id}/kyc`, {
      method: 'PATCH',
      body: JSON.stringify(data),
    });
  }

  /**
   * Verify KYC (shortcut)
   */
  async verifyKyc(id: string, level = 1): Promise<VaResponse> {
    return this.fetch<VaResponse>(`/${id}/kyc/verify?level=${level}`, {
      method: 'POST',
    });
  }

  // ========================================================================
  // MCC RESTRICTIONS
  // ========================================================================

  /**
   * Update MCC restrictions
   */
  async updateMccRestrictions(id: string, data: MccRestrictionsRequest): Promise<VaResponse> {
    return this.fetch<VaResponse>(`/${id}/mcc-restrictions`, {
      method: 'PUT',
      body: JSON.stringify(data),
    });
  }

  /**
   * Get MCC restrictions
   */
  async getMccRestrictions(id: string): Promise<MccRestrictions> {
    return this.fetch<MccRestrictions>(`/${id}/mcc-restrictions`);
  }

  // ========================================================================
  // STATUS MANAGEMENT
  // ========================================================================

  /**
   * Update status
   */
  async updateStatus(id: string, status: string, reason?: string): Promise<VaResponse> {
    const params = new URLSearchParams({ status });
    if (reason) params.set('reason', reason);
    
    return this.fetch<VaResponse>(`/${id}/status?${params.toString()}`, {
      method: 'PATCH',
    });
  }

  /**
   * Suspend account
   */
  async suspend(id: string, reason: string): Promise<VaResponse> {
    return this.fetch<VaResponse>(`/${id}/suspend?reason=${encodeURIComponent(reason)}`, {
      method: 'POST',
    });
  }

  /**
   * Block account
   */
  async block(id: string, reason: string): Promise<VaResponse> {
    return this.fetch<VaResponse>(`/${id}/block?reason=${encodeURIComponent(reason)}`, {
      method: 'POST',
    });
  }

  /**
   * Reactivate account
   */
  async reactivate(id: string): Promise<VaResponse> {
    return this.fetch<VaResponse>(`/${id}/reactivate`, {
      method: 'POST',
    });
  }

  /**
   * Close account
   */
  async close(id: string): Promise<VaResponse> {
    return this.fetch<VaResponse>(`/${id}/close`, {
      method: 'POST',
    });
  }

  // ========================================================================
  // HIERARCHY
  // ========================================================================

  /**
   * Update hierarchy assignment
   */
  async updateHierarchy(id: string, hierarchyNodeId: string): Promise<VaResponse> {
    return this.fetch<VaResponse>(`/${id}/hierarchy?hierarchyNodeId=${hierarchyNodeId}`, {
      method: 'PUT',
    });
  }

  // ========================================================================
  // BALANCE
  // ========================================================================

  /**
   * Get balance
   */
  async getBalance(id: string): Promise<BalanceResponse> {
    return this.fetch<BalanceResponse>(`/${id}/balance`);
  }

  /**
   * Update balance (admin)
   */
  async updateBalance(
    id: string,
    currentBalance: number,
    availableBalance: number
  ): Promise<VaResponse> {
    return this.fetch<VaResponse>(
      `/${id}/balance?currentBalance=${currentBalance}&availableBalance=${availableBalance}`,
      { method: 'PATCH' }
    );
  }

  /**
   * Credit account
   */
  async credit(id: string, amount: number): Promise<VaResponse> {
    return this.fetch<VaResponse>(`/${id}/credit?amount=${amount}`, {
      method: 'POST',
    });
  }

  /**
   * Debit account
   */
  async debit(id: string, amount: number): Promise<VaResponse> {
    return this.fetch<VaResponse>(`/${id}/debit?amount=${amount}`, {
      method: 'POST',
    });
  }

  // ========================================================================
  // STATISTICS
  // ========================================================================

  /**
   * Get statistics
   */
  async getStats(corporateId?: string, programId?: string): Promise<VaStats> {
    const params = new URLSearchParams();
    if (corporateId) params.set('corporateId', corporateId);
    if (programId) params.set('programId', programId);
    
    return this.fetch<VaStats>(`/stats?${params.toString()}`);
  }

  // ========================================================================
  // PROGRAM TYPE CONFIGURATION
  // ========================================================================

  /**
   * Get program type config
   */
  async getProgramTypeConfig(programType: string): Promise<ProgramTypeConfig> {
    return this.fetch<ProgramTypeConfig>(`/program-type-config/${programType}`);
  }

  /**
   * Get all program type configs
   */
  async getAllProgramTypeConfigs(): Promise<ProgramTypeConfig[]> {
    return this.fetch<ProgramTypeConfig[]>('/program-type-configs');
  }

  // ========================================================================
  // BULK OPERATIONS
  // ========================================================================

  /**
   * Bulk update status
   */
  async bulkUpdateStatus(data: BulkStatusUpdateRequest): Promise<BulkOperationResponse> {
    return this.fetch<BulkOperationResponse>('/bulk/status', {
      method: 'PATCH',
      body: JSON.stringify(data),
    });
  }

  /**
   * Bulk update limits
   */
  async bulkUpdateLimits(data: BulkLimitsUpdateRequest): Promise<BulkOperationResponse> {
    return this.fetch<BulkOperationResponse>('/bulk/limits', {
      method: 'PATCH',
      body: JSON.stringify(data),
    });
  }

  // ========================================================================
  // EXPORT
  // ========================================================================

  /**
   * Export accounts
   */
  async export(params: {
    status?: string;
    corporateId?: string;
    programId?: string;
    walletType?: string;
  } = {}): Promise<VaResponse[]> {
    const query = new URLSearchParams();
    if (params.status) query.set('status', params.status);
    if (params.corporateId) query.set('corporateId', params.corporateId);
    if (params.programId) query.set('programId', params.programId);
    if (params.walletType) query.set('walletType', params.walletType);
    
    return this.fetch<VaResponse[]>(`/export?${query.toString()}`);
  }
}

// ============================================================================
// SINGLETON INSTANCE
// ============================================================================

export const vaApi = new VaApiClient();

// ============================================================================
// DEMO MODE API (Fallback with mock data)
// ============================================================================

export const createDemoApi = (): VaApiClient => {
  // Returns a client that works with mock data when backend is unavailable
  const demoClient = new VaApiClient();
  
  // Override methods to return mock data
  // This is useful for development/demo purposes
  
  return demoClient;
};

export default vaApi;