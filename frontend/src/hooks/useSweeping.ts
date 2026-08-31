import { useState, useEffect, useCallback } from 'react';
import { sweepingApi, SweepRule, SweepExecution } from '../services/api';

// RunSweepsResponse type (matches backend SweepRuleDto.RunSweepsResponse)
export interface RunSweepsResponse {
  successCount: number;
  failedCount: number;
  skippedCount: number;
  totalSwept: number;
  executions: SweepExecution[];
}

export const useSweeping = () => {
  const [rules, setRules] = useState<SweepRule[]>([]);
  const [executions, setExecutions] = useState<SweepExecution[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchRules = useCallback(async () => {
    try {
      setLoading(true);
      const response = await sweepingApi.getAllRules();
      // Handle ApiResponse structure: { success, data, message }
      if (response?.success && response?.data) {
        setRules(Array.isArray(response.data) ? response.data : []);
      } else if (Array.isArray(response)) {
        setRules(response);
      }
    } catch (err: any) {
      console.error('Failed to fetch rules:', err);
      setError(err.message || 'Failed to fetch sweep rules');
    } finally {
      setLoading(false);
    }
  }, []);

  const fetchHistory = useCallback(async () => {
    try {
      const response = await sweepingApi.getHistory();
      console.log('[Sweeping] History response:', response);
      
      // Backend returns: ApiResponse<Page<ExecutionResponse>>
      // Structure: { success, data: { content: [...], totalElements, ... } }
      if (response?.success && response?.data) {
        if (response.data.content && Array.isArray(response.data.content)) {
          // Paginated response
          setExecutions(response.data.content);
        } else if (Array.isArray(response.data)) {
          // Direct array
          setExecutions(response.data);
        } else {
          setExecutions([]);
        }
      } else if (response?.content) {
        // Direct Page response
        setExecutions(response.content);
      } else if (Array.isArray(response)) {
        setExecutions(response);
      } else {
        console.warn('[Sweeping] Unexpected history response structure:', response);
        setExecutions([]);
      }
    } catch (err: any) {
      console.error('Failed to fetch history:', err);
      setExecutions([]);
    }
  }, []);

  const createRule = useCallback(async (data: any) => {
    const response = await sweepingApi.createRule(data);
    if (response?.success && response?.data) {
      setRules(prev => [...prev, response.data]);
      return response.data;
    }
    throw new Error('Failed to create rule');
  }, []);

  const deleteRule = useCallback(async (id: string) => {
    await sweepingApi.deleteRule(id);
    setRules(prev => prev.filter(r => r.id !== id));
  }, []);

  const toggleRule = useCallback(async (id: string) => {
    const response = await sweepingApi.toggleRule(id);
    if (response?.success && response?.data) {
      setRules(prev => prev.map(r => r.id === id ? response.data : r));
      return response.data;
    }
    throw new Error('Failed to toggle rule');
  }, []);

  const updateRule = useCallback(async (id: string, data: any) => {
    const response = await sweepingApi.updateRule(id, data);
    if (response?.success && response?.data) {
      setRules(prev => prev.map(r => r.id === id ? response.data : r));
      return response.data;
    }
    throw new Error('Failed to update rule');
  }, []);

  const runSweeps = useCallback(async (ruleIds?: string[]): Promise<RunSweepsResponse | null> => {
    try {
      // Use execute method (matches backend endpoint /sweeping/execute)
      const response = await sweepingApi.execute(ruleIds);
      console.log('[Sweeping] Execute response:', response);
      
      if (response?.success) {
        // Refresh data after execution
        await fetchRules();
        await fetchHistory();
        return response.data;
      }
      return null;
    } catch (err) {
      console.error('Failed to run sweeps:', err);
      return null;
    }
  }, [fetchRules, fetchHistory]);

  useEffect(() => {
    fetchRules();
    fetchHistory();
  }, [fetchRules, fetchHistory]);

  const stats = {
    activeRules: rules.filter(r => r.status === 'ACTIVE').length,
    totalAccounts: rules.reduce((s, r) => s + ((r as any).sourceAccounts?.length || 0), 0),
    totalSwept: rules.reduce((s, r) => s + ((r as any).totalSwept || 0), 0),
    totalRules: rules.length,
  };

  return {
    rules,
    executions,
    loading,
    error,
    stats,
    fetchRules,
    fetchHistory,
    createRule,
    updateRule,
    deleteRule,
    toggleRule,
    runSweeps
  };
};