// ============================================================================
// VIRTUAL ACCOUNT REACT HOOKS
// Custom hooks for VA operations with state management
// ============================================================================

import { useState, useEffect, useCallback, useMemo } from 'react';
import { vaApi } from './vaApi';
import {
  VaResponse,
  VaSummary,
  VaStats,
  CreateVaRequest,
  UpdateVaRequest,
  LimitsUpdateRequest,
  KycUpdateRequest,
  MccRestrictionsRequest,
  ProgramTypeConfig,
  VaSearchParams,
  HierarchyNode,
} from './vaTypes';

// ============================================================================
// TYPES
// ============================================================================

interface UseVaListResult {
  accounts: VaResponse[];
  loading: boolean;
  error: string | null;
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  refresh: () => void;
  setPage: (page: number) => void;
  setSize: (size: number) => void;
  setFilters: (filters: VaSearchParams) => void;
}

interface UseVaDetailResult {
  account: VaResponse | null;
  loading: boolean;
  error: string | null;
  refresh: () => void;
  update: (data: UpdateVaRequest) => Promise<void>;
  updateLimits: (data: LimitsUpdateRequest) => Promise<void>;
  updateKyc: (data: KycUpdateRequest) => Promise<void>;
  updateMccRestrictions: (data: MccRestrictionsRequest) => Promise<void>;
  updateStatus: (status: string, reason?: string) => Promise<void>;
  suspend: (reason: string) => Promise<void>;
  block: (reason: string) => Promise<void>;
  reactivate: () => Promise<void>;
  close: () => Promise<void>;
  credit: (amount: number) => Promise<void>;
  debit: (amount: number) => Promise<void>;
}

interface UseVaCreateResult {
  create: (data: CreateVaRequest) => Promise<VaResponse>;
  loading: boolean;
  error: string | null;
  reset: () => void;
}

interface UseVaStatsResult {
  stats: VaStats | null;
  loading: boolean;
  error: string | null;
  refresh: () => void;
}

interface UseProgramTypeConfigResult {
  config: ProgramTypeConfig | null;
  allConfigs: ProgramTypeConfig[];
  loading: boolean;
  error: string | null;
  loadConfig: (programType: string) => void;
  loadAllConfigs: () => void;
}

// ============================================================================
// HOOKS
// ============================================================================

/**
 * Hook for listing virtual accounts with pagination and filtering
 */
export function useVaList(initialParams: VaSearchParams = {}): UseVaListResult {
  const [accounts, setAccounts] = useState<VaResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [page, setPage] = useState(initialParams.page ?? 0);
  const [size, setSize] = useState(initialParams.size ?? 10);
  const [totalElements, setTotalElements] = useState(0);
  const [filters, setFilters] = useState<VaSearchParams>(initialParams);

  const fetchAccounts = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await vaApi.getAll({ ...filters, page, size });
      setAccounts(result.data);
      setTotalElements(result.totalElements);
    } catch (err: any) {
      setError(err.message || 'Failed to load accounts');
      setAccounts([]);
    } finally {
      setLoading(false);
    }
  }, [page, size, filters]);

  useEffect(() => {
    fetchAccounts();
  }, [fetchAccounts]);

  const totalPages = useMemo(() => Math.ceil(totalElements / size), [totalElements, size]);

  return {
    accounts,
    loading,
    error,
    page,
    size,
    totalElements,
    totalPages,
    refresh: fetchAccounts,
    setPage,
    setSize,
    setFilters,
  };
}

/**
 * Hook for virtual account detail with operations
 */
export function useVaDetail(id: string | null): UseVaDetailResult {
  const [account, setAccount] = useState<VaResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchAccount = useCallback(async () => {
    if (!id) {
      setAccount(null);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const result = await vaApi.getById(id);
      setAccount(result);
    } catch (err: any) {
      setError(err.message || 'Failed to load account');
      setAccount(null);
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    fetchAccount();
  }, [fetchAccount]);

  const update = useCallback(async (data: UpdateVaRequest) => {
    if (!id) throw new Error('No account ID');
    setLoading(true);
    setError(null);
    try {
      const result = await vaApi.update(id, data);
      setAccount(result);
    } catch (err: any) {
      setError(err.message);
      throw err;
    } finally {
      setLoading(false);
    }
  }, [id]);

  const updateLimits = useCallback(async (data: LimitsUpdateRequest) => {
    if (!id) throw new Error('No account ID');
    setLoading(true);
    setError(null);
    try {
      const result = await vaApi.updateLimits(id, data);
      setAccount(result);
    } catch (err: any) {
      setError(err.message);
      throw err;
    } finally {
      setLoading(false);
    }
  }, [id]);

  const updateKyc = useCallback(async (data: KycUpdateRequest) => {
    if (!id) throw new Error('No account ID');
    setLoading(true);
    setError(null);
    try {
      const result = await vaApi.updateKyc(id, data);
      setAccount(result);
    } catch (err: any) {
      setError(err.message);
      throw err;
    } finally {
      setLoading(false);
    }
  }, [id]);

  const updateMccRestrictions = useCallback(async (data: MccRestrictionsRequest) => {
    if (!id) throw new Error('No account ID');
    setLoading(true);
    setError(null);
    try {
      const result = await vaApi.updateMccRestrictions(id, data);
      setAccount(result);
    } catch (err: any) {
      setError(err.message);
      throw err;
    } finally {
      setLoading(false);
    }
  }, [id]);

  const updateStatus = useCallback(async (status: string, reason?: string) => {
    if (!id) throw new Error('No account ID');
    setLoading(true);
    setError(null);
    try {
      const result = await vaApi.updateStatus(id, status, reason);
      setAccount(result);
    } catch (err: any) {
      setError(err.message);
      throw err;
    } finally {
      setLoading(false);
    }
  }, [id]);

  const suspend = useCallback(async (reason: string) => {
    if (!id) throw new Error('No account ID');
    setLoading(true);
    setError(null);
    try {
      const result = await vaApi.suspend(id, reason);
      setAccount(result);
    } catch (err: any) {
      setError(err.message);
      throw err;
    } finally {
      setLoading(false);
    }
  }, [id]);

  const block = useCallback(async (reason: string) => {
    if (!id) throw new Error('No account ID');
    setLoading(true);
    setError(null);
    try {
      const result = await vaApi.block(id, reason);
      setAccount(result);
    } catch (err: any) {
      setError(err.message);
      throw err;
    } finally {
      setLoading(false);
    }
  }, [id]);

  const reactivate = useCallback(async () => {
    if (!id) throw new Error('No account ID');
    setLoading(true);
    setError(null);
    try {
      const result = await vaApi.reactivate(id);
      setAccount(result);
    } catch (err: any) {
      setError(err.message);
      throw err;
    } finally {
      setLoading(false);
    }
  }, [id]);

  const close = useCallback(async () => {
    if (!id) throw new Error('No account ID');
    setLoading(true);
    setError(null);
    try {
      const result = await vaApi.close(id);
      setAccount(result);
    } catch (err: any) {
      setError(err.message);
      throw err;
    } finally {
      setLoading(false);
    }
  }, [id]);

  const credit = useCallback(async (amount: number) => {
    if (!id) throw new Error('No account ID');
    setLoading(true);
    setError(null);
    try {
      const result = await vaApi.credit(id, amount);
      setAccount(result);
    } catch (err: any) {
      setError(err.message);
      throw err;
    } finally {
      setLoading(false);
    }
  }, [id]);

  const debit = useCallback(async (amount: number) => {
    if (!id) throw new Error('No account ID');
    setLoading(true);
    setError(null);
    try {
      const result = await vaApi.debit(id, amount);
      setAccount(result);
    } catch (err: any) {
      setError(err.message);
      throw err;
    } finally {
      setLoading(false);
    }
  }, [id]);

  return {
    account,
    loading,
    error,
    refresh: fetchAccount,
    update,
    updateLimits,
    updateKyc,
    updateMccRestrictions,
    updateStatus,
    suspend,
    block,
    reactivate,
    close,
    credit,
    debit,
  };
}

/**
 * Hook for creating virtual accounts
 */
export function useVaCreate(): UseVaCreateResult {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const create = useCallback(async (data: CreateVaRequest): Promise<VaResponse> => {
    setLoading(true);
    setError(null);
    try {
      const result = await vaApi.create(data);
      return result;
    } catch (err: any) {
      setError(err.message || 'Failed to create account');
      throw err;
    } finally {
      setLoading(false);
    }
  }, []);

  const reset = useCallback(() => {
    setError(null);
  }, []);

  return {
    create,
    loading,
    error,
    reset,
  };
}

/**
 * Hook for virtual account statistics
 */
export function useVaStats(corporateId?: string, programId?: string): UseVaStatsResult {
  const [stats, setStats] = useState<VaStats | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchStats = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await vaApi.getStats(corporateId, programId);
      setStats(result);
    } catch (err: any) {
      setError(err.message || 'Failed to load statistics');
      setStats(null);
    } finally {
      setLoading(false);
    }
  }, [corporateId, programId]);

  useEffect(() => {
    fetchStats();
  }, [fetchStats]);

  return {
    stats,
    loading,
    error,
    refresh: fetchStats,
  };
}

/**
 * Hook for program type configuration
 */
export function useProgramTypeConfig(): UseProgramTypeConfigResult {
  const [config, setConfig] = useState<ProgramTypeConfig | null>(null);
  const [allConfigs, setAllConfigs] = useState<ProgramTypeConfig[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadConfig = useCallback(async (programType: string) => {
    setLoading(true);
    setError(null);
    try {
      const result = await vaApi.getProgramTypeConfig(programType);
      setConfig(result);
    } catch (err: any) {
      setError(err.message || 'Failed to load config');
      setConfig(null);
    } finally {
      setLoading(false);
    }
  }, []);

  const loadAllConfigs = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await vaApi.getAllProgramTypeConfigs();
      setAllConfigs(result);
    } catch (err: any) {
      setError(err.message || 'Failed to load configs');
      setAllConfigs([]);
    } finally {
      setLoading(false);
    }
  }, []);

  return {
    config,
    allConfigs,
    loading,
    error,
    loadConfig,
    loadAllConfigs,
  };
}

/**
 * Hook for VA search/lookup (autocomplete)
 */
export function useVaLookup() {
  const [results, setResults] = useState<VaSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const search = useCallback(async (params: {
    query?: string;
    corporateId?: string;
    programId?: string;
    limit?: number;
  }) => {
    setLoading(true);
    setError(null);
    try {
      const result = await vaApi.lookup(params);
      setResults(result);
    } catch (err: any) {
      setError(err.message || 'Search failed');
      setResults([]);
    } finally {
      setLoading(false);
    }
  }, []);

  const clear = useCallback(() => {
    setResults([]);
    setError(null);
  }, []);

  return {
    results,
    loading,
    error,
    search,
    clear,
  };
}

/**
 * Hook for bulk operations
 */
export function useVaBulkOperations() {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<{
    successCount: number;
    failureCount: number;
    errors: Array<{ accountId: string; error: string }>;
  } | null>(null);

  const bulkUpdateStatus = useCallback(async (
    accountIds: string[],
    status: string,
    reason?: string
  ) => {
    setLoading(true);
    setError(null);
    try {
      const response = await vaApi.bulkUpdateStatus({ accountIds, status, reason });
      setResult({
        successCount: response.successCount,
        failureCount: response.failureCount,
        errors: response.errors || [],
      });
      return response;
    } catch (err: any) {
      setError(err.message || 'Bulk operation failed');
      throw err;
    } finally {
      setLoading(false);
    }
  }, []);

  const bulkUpdateLimits = useCallback(async (
    accountIds: string[],
    limits: Omit<import('./vaTypes').BulkLimitsUpdateRequest, 'accountIds'>
  ) => {
    setLoading(true);
    setError(null);
    try {
      const response = await vaApi.bulkUpdateLimits({ accountIds, ...limits });
      setResult({
        successCount: response.successCount,
        failureCount: response.failureCount,
        errors: response.errors || [],
      });
      return response;
    } catch (err: any) {
      setError(err.message || 'Bulk operation failed');
      throw err;
    } finally {
      setLoading(false);
    }
  }, []);

  const reset = useCallback(() => {
    setError(null);
    setResult(null);
  }, []);

  return {
    loading,
    error,
    result,
    bulkUpdateStatus,
    bulkUpdateLimits,
    reset,
  };
}

/**
 * Hook for limit reset operations
 */
export function useVaLimitReset(id: string | null) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const resetDaily = useCallback(async () => {
    if (!id) throw new Error('No account ID');
    setLoading(true);
    setError(null);
    try {
      return await vaApi.resetDailyLimits(id);
    } catch (err: any) {
      setError(err.message);
      throw err;
    } finally {
      setLoading(false);
    }
  }, [id]);

  const resetWeekly = useCallback(async () => {
    if (!id) throw new Error('No account ID');
    setLoading(true);
    setError(null);
    try {
      return await vaApi.resetWeeklyLimits(id);
    } catch (err: any) {
      setError(err.message);
      throw err;
    } finally {
      setLoading(false);
    }
  }, [id]);

  const resetMonthly = useCallback(async () => {
    if (!id) throw new Error('No account ID');
    setLoading(true);
    setError(null);
    try {
      return await vaApi.resetMonthlyLimits(id);
    } catch (err: any) {
      setError(err.message);
      throw err;
    } finally {
      setLoading(false);
    }
  }, [id]);

  const resetAnnual = useCallback(async () => {
    if (!id) throw new Error('No account ID');
    setLoading(true);
    setError(null);
    try {
      return await vaApi.resetAnnualLimits(id);
    } catch (err: any) {
      setError(err.message);
      throw err;
    } finally {
      setLoading(false);
    }
  }, [id]);

  return {
    loading,
    error,
    resetDaily,
    resetWeekly,
    resetMonthly,
    resetAnnual,
  };
}

// ============================================================================
// UTILITY HOOKS
// ============================================================================

/**
 * Hook for debounced search
 */
export function useDebouncedSearch(delay = 300) {
  const [query, setQuery] = useState('');
  const [debouncedQuery, setDebouncedQuery] = useState('');

  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedQuery(query);
    }, delay);

    return () => clearTimeout(timer);
  }, [query, delay]);

  return {
    query,
    debouncedQuery,
    setQuery,
  };
}

/**
 * Hook for form validation
 */
export function useVaFormValidation(formData: CreateVaRequest) {
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [touched, setTouched] = useState<Record<string, boolean>>({});

  const validate = useCallback(() => {
    const newErrors: Record<string, string> = {};

    // Required fields
    if (!formData.vaName?.trim()) {
      newErrors.vaName = 'Account name is required';
    }
    if (!formData.corporateId) {
      newErrors.corporateId = 'Corporate is required';
    }
    if (!formData.physicalAccountId) {
      newErrors.physicalAccountId = 'Physical account is required';
    }
    if (!formData.currencyCode?.trim()) {
      newErrors.currencyCode = 'Currency is required';
    } else if (formData.currencyCode.length !== 3) {
      newErrors.currencyCode = 'Currency must be 3 characters';
    }

    // Limit validations
    if (formData.perTransactionLimit !== undefined && formData.perTransactionLimit < 0) {
      newErrors.perTransactionLimit = 'Must be positive';
    }
    if (formData.dailyLimit !== undefined && formData.dailyLimit < 0) {
      newErrors.dailyLimit = 'Must be positive';
    }
    if (formData.maxBalance !== undefined && formData.maxBalance < 0) {
      newErrors.maxBalance = 'Must be positive';
    }

    // Cross-field validations
    if (formData.dailyLimit && formData.perTransactionLimit && 
        formData.perTransactionLimit > formData.dailyLimit) {
      newErrors.perTransactionLimit = 'Cannot exceed daily limit';
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  }, [formData]);

  const touch = useCallback((field: string) => {
    setTouched(prev => ({ ...prev, [field]: true }));
  }, []);

  const touchAll = useCallback(() => {
    const allTouched: Record<string, boolean> = {};
    Object.keys(formData).forEach(key => {
      allTouched[key] = true;
    });
    setTouched(allTouched);
  }, [formData]);

  const reset = useCallback(() => {
    setErrors({});
    setTouched({});
  }, []);

  const isValid = useMemo(() => Object.keys(errors).length === 0, [errors]);

  return {
    errors,
    touched,
    isValid,
    validate,
    touch,
    touchAll,
    reset,
  };
}

export default {
  useVaList,
  useVaDetail,
  useVaCreate,
  useVaStats,
  useProgramTypeConfig,
  useVaLookup,
  useVaBulkOperations,
  useVaLimitReset,
  useDebouncedSearch,
  useVaFormValidation,
};