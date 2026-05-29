// ============================================================================
// HIERARCHY OPERATIONS API SERVICE
// ============================================================================
// Provides API integration for corporate hierarchy operations including:
// - Move VA/Aggregation operations
// - M&A operations (Acquisition, Merger, Divestiture)
// - Validation and policy management
// - Operation history
// ============================================================================

import axios from 'axios';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8053/api/v1';

const apiClient = axios.create({
  baseURL: API_BASE_URL,
  headers: { 'Content-Type': 'application/json' },
  timeout: 30000,
});

// Request interceptor
apiClient.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('auth_token');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => Promise.reject(error)
);

// ============================================================================
// TYPES - ENUMS
// ============================================================================

export type MoveLimitPolicy = 
  | 'STRICT' 
  | 'TRANSFER_WITH_VA' 
  | 'ABSORB_INTO_TARGET' 
  | 'REQUIRE_APPROVAL';

export type MergeLimitPolicy = 
  | 'COMBINE_LIMITS' 
  | 'RESET_TARGET_LIMITS' 
  | 'PRESERVE_TARGET_STRUCTURE';

export type OperationType = 
  | 'MOVE_TRANSACTION_VA' 
  | 'MOVE_AGGREGATION' 
  | 'BATCH_MOVE' 
  | 'ACQUISITION' 
  | 'MERGER' 
  | 'DIVESTITURE'
  | 'HIERARCHY_INIT';

export type HierarchyStatus = 
  | 'NOT_INITIALIZED' 
  | 'INITIALIZED' 
  | 'PENDING_APPROVAL' 
  | 'LOCKED';

export type AccountCategory = 
  | 'ROOT' 
  | 'AGGREGATION' 
  | 'CURRENCY_MIRROR' 
  | 'SETTLEMENT' 
  | 'EXCEPTION' 
  | 'TRANSACTION';

// ============================================================================
// TYPES - API RESPONSE
// ============================================================================

export interface ApiResponse<T> {
  success: boolean;
  data: T;
  message?: string;
  error?: string;
}

// ============================================================================
// TYPES - REQUEST DTOs
// ============================================================================

export interface MoveTransactionVaRequest {
  vaId: string;
  newParentId: string;
  limitPolicy: MoveLimitPolicy;
  approvedBy?: string;
  reason?: string;
}

export interface MoveAggregationRequest {
  aggregationId: string;
  newParentId: string;
  limitPolicy: MoveLimitPolicy;
  approvedBy?: string;
  reason?: string;
}

export interface BatchMoveRequest {
  vaIds: string[];
  newParentId: string;
  limitPolicy: MoveLimitPolicy;
  approvedBy?: string;
}

export interface ValidateMoveRequest {
  vaId: string;
  newParentId: string;
  limitPolicy: MoveLimitPolicy;
}

export interface AcquisitionRequest {
  acquirerCorporateId: string;
  targetCorporateId: string;
  newAggregationName: string;
  newAggregationCode?: string;
  placeUnderNodeId?: string;
  limitPolicy: MergeLimitPolicy;
  approvedBy: string;
  boardApprovalRef?: string;
}

export interface MergerRequest {
  corporateAId: string;
  corporateBId: string;
  newCorporateId: string;
  newCorporateName: string;
  newBaseCurrency: string;
  corporateAName: string;
  corporateACode: string;
  corporateBName: string;
  corporateBCode: string;
  limitPolicy: MergeLimitPolicy;
  approvedBy: string;
  boardApprovalRef?: string;
}

export interface DivestitureRequest {
  sourceCorporateId: string;
  aggregationId: string;
  newCorporateId: string;
  newCorporateName: string;
  newBaseCurrency: string;
  approvedBy: string;
  effectiveDate?: string;
}

export interface HierarchyInitRequest {
  corporateId: string;
  rootName: string;
  rootCode: string;
  baseCurrency: string;
  createExceptionVa: boolean;
  additionalExceptionCurrencies?: string[];
}

// ============================================================================
// TYPES - RESPONSE DTOs
// ============================================================================

export interface MoveValidationResult {
  valid: boolean;
  errors: string[];
  warnings: string[];
  affectedVaCount: number;
  currencyMirrorWillBeCreated: boolean;
  currenciesToMirror: string[];
  settlementVaWillReResolve: boolean;
  currentSettlementVaId?: string;
  newSettlementVaId?: string;
  limitImpact?: LimitImpact;
}

export interface LimitImpact {
  sourceUtilization: number;
  sourceHeadroom: number;
  targetUtilization: number;
  targetHeadroom: number;
  amountToTransfer: number;
  currency: string;
  requiresApproval: boolean;
}

export interface MoveOperationResult {
  operationType: OperationType;
  success: boolean;
  staged: boolean;
  message: string;
  movedVaId: string;
  oldParentId: string;
  newParentId: string;
  movedVaCount: number;
  currenciesAffected: string[];
  currencyMirrorsRecalculated: number;
  settlementVaReResolved: boolean;
  limitValidation?: LimitImpact;
  startedAt: string;
  completedAt: string;
  approvalId?: string;
}

export interface MergeOperationResult {
  operationType: OperationType;
  success: boolean;
  staged: boolean;
  message: string;
  acquirerCorporateId?: string;
  targetCorporateId?: string;
  newCorporateId?: string;
  newAggregationId?: string;
  migratedVaCount: number;
  currenciesAffected: string[];
  currencyMirrorsCreated: number;
  limitCombined?: {
    originalLimit: number;
    addedLimit: number;
    newTotalLimit: number;
    currency: string;
  };
  startedAt: string;
  completedAt: string;
  approvalId?: string;
}

export interface HierarchyInitResult {
  success: boolean;
  corporateId: string;
  rootVaId: string;
  rootVaNumber: string;
  exceptionVaIds: string[];
  status: HierarchyStatus;
  message: string;
  completedAt: string;
}

export interface AcquisitionValidationResult {
  valid: boolean;
  errors: string[];
  warnings: string[];
  acquirerVaCount: number;
  targetVaCount: number;
  currenciesToMigrate: string[];
  currencyMirrorsToCreate: number;
  limitAnalysis: {
    acquirerGroupLimit: number;
    targetGroupLimit: number;
    combinedLimit: number;
    acquirerCurrency: string;
    targetCurrency: string;
    fxRateApplied?: number;
  };
}

// ============================================================================
// TYPES - HIERARCHY DATA
// ============================================================================

export interface HierarchyNode {
  id: string;
  name: string;
  code?: string;
  type: string;
  accountCategory: AccountCategory;
  currencyCode: string;
  balance: number;
  parentId?: string;
  level: number;
  childCount: number;
  children?: HierarchyNode[];
  specialType?: 'REGULAR' | 'SETTLEMENT' | 'EXCEPTION' | 'CURRENCY_MIRROR';
}

export interface CorporateSummary {
  id: string;
  name: string;
  code: string;
  baseCurrency: string;
  status: string;
  hierarchyStatus: HierarchyStatus;
  rootVaId?: string;
  rootVaName?: string;
  vaCount: number;
  totalBalance: number;
  createdAt: string;
}

export interface OperationHistoryEntry {
  id: string;
  operationType: OperationType;
  status: 'COMPLETED' | 'PENDING' | 'FAILED' | 'ROLLED_BACK';
  summary: string;
  details: OperationDetails;
  performedBy: string;
  approvedBy?: string;
  corporateId: string;
  createdAt: string;
  completedAt?: string;
}

export interface OperationDetails {
  movedVaCount?: number;
  currenciesAffected?: string[];
  currencyMirrorsCreated?: number;
  limitTransferred?: number;
  oldParentName?: string;
  newParentName?: string;
  targetCorporateName?: string;
}

export interface MoveLimitPolicyInfo {
  policy: MoveLimitPolicy;
  name: string;
  description: string;
  requiresApproval: boolean;
  recommended: boolean;
}

export interface MergeLimitPolicyInfo {
  policy: MergeLimitPolicy;
  name: string;
  description: string;
  requiresApproval: boolean;
}

export interface OperationRules {
  transactionVaRules: string[];
  aggregationRules: string[];
  notMovableTypes: AccountCategory[];
  acquisitionRules: string[];
  divestureRules: string[];
}

// ============================================================================
// API SERVICE - MOVE OPERATIONS
// ============================================================================

export const hierarchyOperationsApi = {
  // ==========================================================================
  // MOVE OPERATIONS
  // ==========================================================================

  /**
   * Move a Transaction VA to a new parent Aggregation
   */
  moveTransactionVa: async (request: MoveTransactionVaRequest): Promise<ApiResponse<MoveOperationResult>> => {
    try {
      const response = await apiClient.post('/treasury/hierarchy-operations/move/transaction-va', request);
      return response.data;
    } catch (error: any) {
      return {
        success: false,
        data: {} as MoveOperationResult,
        error: error.response?.data?.message || 'Failed to move transaction VA'
      };
    }
  },

  /**
   * Move an Aggregation node (with all children) to a new parent
   */
  moveAggregation: async (request: MoveAggregationRequest): Promise<ApiResponse<MoveOperationResult>> => {
    try {
      const response = await apiClient.post('/treasury/hierarchy-operations/move/aggregation', request);
      return response.data;
    } catch (error: any) {
      return {
        success: false,
        data: {} as MoveOperationResult,
        error: error.response?.data?.message || 'Failed to move aggregation'
      };
    }
  },

  /**
   * Batch move multiple VAs to a new parent
   */
  batchMoveVas: async (request: BatchMoveRequest): Promise<ApiResponse<MoveOperationResult>> => {
    try {
      const response = await apiClient.post('/treasury/hierarchy-operations/move/batch', request);
      return response.data;
    } catch (error: any) {
      return {
        success: false,
        data: {} as MoveOperationResult,
        error: error.response?.data?.message || 'Failed to batch move VAs'
      };
    }
  },

  // ==========================================================================
  // VALIDATION
  // ==========================================================================

  /**
   * Validate a move operation before execution
   */
  validateMove: async (request: ValidateMoveRequest): Promise<ApiResponse<MoveValidationResult>> => {
    try {
      const response = await apiClient.post('/treasury/hierarchy-operations/validate/move', request);
      return response.data;
    } catch (error: any) {
      return {
        success: false,
        data: {} as MoveValidationResult,
        error: error.response?.data?.message || 'Failed to validate move'
      };
    }
  },

  /**
   * Validate an acquisition operation
   */
  validateAcquisition: async (
    acquirerId: string, 
    targetId: string, 
    limitPolicy: MergeLimitPolicy
  ): Promise<ApiResponse<AcquisitionValidationResult>> => {
    try {
      const response = await apiClient.post('/treasury/hierarchy-operations/validate/acquisition', {
        acquirerCorporateId: acquirerId,
        targetCorporateId: targetId,
        limitPolicy
      });
      return response.data;
    } catch (error: any) {
      return {
        success: false,
        data: {} as AcquisitionValidationResult,
        error: error.response?.data?.message || 'Failed to validate acquisition'
      };
    }
  },

  // ==========================================================================
  // M&A OPERATIONS
  // ==========================================================================

  /**
   * Execute corporate acquisition
   */
  acquireCorporate: async (request: AcquisitionRequest): Promise<ApiResponse<MergeOperationResult>> => {
    try {
      const response = await apiClient.post('/treasury/hierarchy-operations/acquire', request);
      return response.data;
    } catch (error: any) {
      return {
        success: false,
        data: {} as MergeOperationResult,
        error: error.response?.data?.message || 'Failed to execute acquisition'
      };
    }
  },

  /**
   * Execute corporate merger
   */
  mergeCorporates: async (request: MergerRequest): Promise<ApiResponse<MergeOperationResult>> => {
    try {
      const response = await apiClient.post('/treasury/hierarchy-operations/merge', request);
      return response.data;
    } catch (error: any) {
      return {
        success: false,
        data: {} as MergeOperationResult,
        error: error.response?.data?.message || 'Failed to execute merger'
      };
    }
  },

  /**
   * Execute divestiture (spin-off)
   */
  divestAggregation: async (request: DivestitureRequest): Promise<ApiResponse<MergeOperationResult>> => {
    try {
      const response = await apiClient.post('/treasury/hierarchy-operations/divest', request);
      return response.data;
    } catch (error: any) {
      return {
        success: false,
        data: {} as MergeOperationResult,
        error: error.response?.data?.message || 'Failed to execute divestiture'
      };
    }
  },

  // ==========================================================================
  // HIERARCHY INITIALIZATION
  // ==========================================================================

  /**
   * Initialize hierarchy for a corporate (create ROOT)
   */
  initializeHierarchy: async (request: HierarchyInitRequest): Promise<ApiResponse<HierarchyInitResult>> => {
    try {
      const response = await apiClient.post('/treasury/hierarchy-operations/initialize', request);
      return response.data;
    } catch (error: any) {
      return {
        success: false,
        data: {} as HierarchyInitResult,
        error: error.response?.data?.message || 'Failed to initialize hierarchy'
      };
    }
  },

  /**
   * Get hierarchy initialization status
   */
  getInitializationStatus: async (corporateId: string): Promise<ApiResponse<{
    status: HierarchyStatus;
    rootVaId?: string;
    rootVaName?: string;
    exceptionVaCount: number;
    currencyMirrorCount: number;
  }>> => {
    try {
      const response = await apiClient.get(`/treasury/hierarchy-operations/status/${corporateId}`);
      return response.data;
    } catch (error: any) {
      return {
        success: false,
        data: { status: 'NOT_INITIALIZED', exceptionVaCount: 0, currencyMirrorCount: 0 },
        error: error.response?.data?.message || 'Failed to get initialization status'
      };
    }
  },

  // ==========================================================================
  // DATA RETRIEVAL
  // ==========================================================================

  /**
   * Get available corporates for operations
   */
  getCorporates: async (): Promise<ApiResponse<CorporateSummary[]>> => {
    try {
      const response = await apiClient.get('/corporates');
      return response.data;
    } catch (error: any) {
      return {
        success: false,
        data: [],
        error: error.response?.data?.message || 'Failed to fetch corporates'
      };
    }
  },

  /**
   * Get hierarchy for a corporate
   */
  getHierarchy: async (corporateId?: string): Promise<ApiResponse<HierarchyNode>> => {
    try {
      const response = await apiClient.get('/treasury/hierarchy-operations/hierarchy', {
        params: { corporateId }
      });
      return response.data;
    } catch (error: any) {
      return {
        success: false,
        data: {} as HierarchyNode,
        error: error.response?.data?.message || 'Failed to fetch hierarchy'
      };
    }
  },

  /**
   * Get available parent nodes for moving a VA
   */
  getAvailableParents: async (
    vaId: string, 
    corporateId?: string
  ): Promise<ApiResponse<HierarchyNode[]>> => {
    try {
      const response = await apiClient.get('/treasury/hierarchy-operations/available-parents', {
        params: { vaId, corporateId }
      });
      return response.data;
    } catch (error: any) {
      return {
        success: false,
        data: [],
        error: error.response?.data?.message || 'Failed to fetch available parents'
      };
    }
  },

  /**
   * Get movable VAs (TRANSACTION type only)
   */
  getMovableVas: async (corporateId?: string): Promise<ApiResponse<HierarchyNode[]>> => {
    try {
      const response = await apiClient.get('/treasury/hierarchy-operations/movable-vas', {
        params: { corporateId }
      });
      return response.data;
    } catch (error: any) {
      return {
        success: false,
        data: [],
        error: error.response?.data?.message || 'Failed to fetch movable VAs'
      };
    }
  },

  /**
   * Get movable aggregations
   */
  getMovableAggregations: async (corporateId?: string): Promise<ApiResponse<HierarchyNode[]>> => {
    try {
      const response = await apiClient.get('/treasury/hierarchy-operations/movable-aggregations', {
        params: { corporateId }
      });
      return response.data;
    } catch (error: any) {
      return {
        success: false,
        data: [],
        error: error.response?.data?.message || 'Failed to fetch movable aggregations'
      };
    }
  },

  // ==========================================================================
  // POLICY INFORMATION
  // ==========================================================================

  /**
   * Get available move limit policies
   */
  getMovePolicies: async (): Promise<ApiResponse<MoveLimitPolicyInfo[]>> => {
    try {
      const response = await apiClient.get('/treasury/hierarchy-operations/policies/move');
      return response.data;
    } catch (error: any) {
      // Return default policies if API fails
      return {
        success: true,
        data: [
          {
            policy: 'STRICT',
            name: 'Strict',
            description: 'Block if target has insufficient limit headroom',
            requiresApproval: false,
            recommended: false
          },
          {
            policy: 'TRANSFER_WITH_VA',
            name: 'Transfer with VA',
            description: 'Limit allocation moves with the VA',
            requiresApproval: false,
            recommended: true
          },
          {
            policy: 'ABSORB_INTO_TARGET',
            name: 'Absorb into Target',
            description: 'Target absorbs utilization without limit transfer',
            requiresApproval: false,
            recommended: false
          },
          {
            policy: 'REQUIRE_APPROVAL',
            name: 'Require Approval',
            description: 'Stage for CFO approval before execution',
            requiresApproval: true,
            recommended: false
          }
        ]
      };
    }
  },

  /**
   * Get available merge limit policies
   */
  getMergePolicies: async (): Promise<ApiResponse<MergeLimitPolicyInfo[]>> => {
    try {
      const response = await apiClient.get('/treasury/hierarchy-operations/policies/merge');
      return response.data;
    } catch (error: any) {
      // Return default policies if API fails
      return {
        success: true,
        data: [
          {
            policy: 'COMBINE_LIMITS',
            name: 'Combine Limits',
            description: "Add target's group limit to yours. Target's internal limit structure preserved as sub-hierarchy.",
            requiresApproval: false
          },
          {
            policy: 'RESET_TARGET_LIMITS',
            name: 'Reset Target Limits',
            description: "Cancel all target's limits. CFO must reallocate from the combined pool. Requires approval workflow.",
            requiresApproval: true
          },
          {
            policy: 'PRESERVE_TARGET_STRUCTURE',
            name: 'Preserve Target Structure',
            description: "Keep target's entire limit hierarchy intact as a nested sub-structure. No limit amounts change.",
            requiresApproval: false
          }
        ]
      };
    }
  },

  /**
   * Get operation rules and constraints
   */
  getOperationRules: async (): Promise<ApiResponse<OperationRules>> => {
    try {
      const response = await apiClient.get('/treasury/hierarchy-operations/rules');
      return response.data;
    } catch (error: any) {
      // Return default rules if API fails
      return {
        success: true,
        data: {
          transactionVaRules: [
            'Can move to any AGGREGATION within the same corporate',
            'Settlement VA will be automatically re-resolved at the new location',
            'Currency Mirrors are recalculated at both old and new parent locations',
            'Balance is subtracted from source mirror, added to target mirror',
            'Limit utilization follows the VA based on selected policy',
            'Cross-corporate moves are NOT allowed - use Acquisition or Divestiture instead'
          ],
          aggregationRules: [
            'Entire subtree moves together, preserving internal structure',
            'Currency Mirrors at old parent are recalculated (may delete if empty)',
            'Currency Mirrors at new parent are created for all currencies in subtree',
            'Settlement VAs within subtree are preserved',
            'ROOT-level Currency Mirrors are unaffected'
          ],
          notMovableTypes: ['ROOT', 'CURRENCY_MIRROR', 'EXCEPTION'],
          acquisitionRules: [
            "Target's ROOT becomes an AGGREGATION under acquirer's hierarchy",
            'All target VAs are migrated with updated corporate ID',
            'Currency mirrors are created for target currencies',
            'Limit transfer follows selected policy'
          ],
          divestureRules: [
            'Selected aggregation becomes ROOT of new corporate',
            'All child VAs are migrated to new corporate',
            'Source currency mirrors are recalculated',
            'New corporate gets independent limit structure'
          ]
        }
      };
    }
  },

  // ==========================================================================
  // OPERATION HISTORY
  // ==========================================================================

  /**
   * Get operation history
   */
  getOperationHistory: async (
    corporateId?: string,
    operationType?: OperationType,
    startDate?: string,
    endDate?: string,
    page: number = 0,
    size: number = 20
  ): Promise<ApiResponse<{
    content: OperationHistoryEntry[];
    totalElements: number;
    totalPages: number;
    page: number;
    size: number;
  }>> => {
    try {
      const response = await apiClient.get('/treasury/hierarchy-operations/history', {
        params: { corporateId, operationType, startDate, endDate, page, size }
      });
      return response.data;
    } catch (error: any) {
      return {
        success: false,
        data: { content: [], totalElements: 0, totalPages: 0, page: 0, size: 20 },
        error: error.response?.data?.message || 'Failed to fetch operation history'
      };
    }
  },

  /**
   * Get operation details by ID
   */
  getOperationDetails: async (operationId: string): Promise<ApiResponse<OperationHistoryEntry>> => {
    try {
      const response = await apiClient.get(`/treasury/hierarchy-operations/history/${operationId}`);
      return response.data;
    } catch (error: any) {
      return {
        success: false,
        data: {} as OperationHistoryEntry,
        error: error.response?.data?.message || 'Failed to fetch operation details'
      };
    }
  },

  // ==========================================================================
  // APPROVAL WORKFLOW
  // ==========================================================================

  /**
   * Get pending operations requiring approval
   */
  getPendingApprovals: async (corporateId?: string): Promise<ApiResponse<OperationHistoryEntry[]>> => {
    try {
      const response = await apiClient.get('/treasury/hierarchy-operations/pending-approvals', {
        params: { corporateId }
      });
      return response.data;
    } catch (error: any) {
      return {
        success: false,
        data: [],
        error: error.response?.data?.message || 'Failed to fetch pending approvals'
      };
    }
  },

  /**
   * Approve a pending operation
   */
  approveOperation: async (
    operationId: string, 
    approvedBy: string, 
    comment?: string
  ): Promise<ApiResponse<MoveOperationResult | MergeOperationResult>> => {
    try {
      const response = await apiClient.post(`/treasury/hierarchy-operations/approve/${operationId}`, {
        approvedBy,
        comment
      });
      return response.data;
    } catch (error: any) {
      return {
        success: false,
        data: {} as MoveOperationResult,
        error: error.response?.data?.message || 'Failed to approve operation'
      };
    }
  },

  /**
   * Reject a pending operation
   */
  rejectOperation: async (
    operationId: string, 
    rejectedBy: string, 
    reason: string
  ): Promise<ApiResponse<void>> => {
    try {
      const response = await apiClient.post(`/treasury/hierarchy-operations/reject/${operationId}`, {
        rejectedBy,
        reason
      });
      return response.data;
    } catch (error: any) {
      return {
        success: false,
        data: undefined,
        error: error.response?.data?.message || 'Failed to reject operation'
      };
    }
  }
};

export default hierarchyOperationsApi;