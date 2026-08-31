import { useState, useEffect, useCallback } from 'react';
import { poolingApi, NotionalPool, CreatePoolRequest, AddMemberRequest, CalculateInterestResponse } from '../services/api';

export const useNotionalPooling = () => {
  const [pools, setPools] = useState<NotionalPool[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedPool, setSelectedPool] = useState<NotionalPool | null>(null);

  const fetchPools = useCallback(async () => {
    try {
      setLoading(true);
      setError(null);
      const response = await poolingApi.getAllPools();
      if (response.success) {
        setPools(response.data || []);
      }
    } catch (err: any) {
      console.error('Failed to fetch pools:', err);
      setError(err.message || 'Failed to fetch pools');
    } finally {
      setLoading(false);
    }
  }, []);

  const fetchPoolById = useCallback(async (poolId: string) => {
    try {
      const response = await poolingApi.getPoolById(poolId);
      if (response.success) {
        setSelectedPool(response.data);
        // Also update in pools list
        setPools(prev => prev.map(p => p.id === poolId ? response.data : p));
        return response.data;
      }
      throw new Error('Failed to fetch pool');
    } catch (err: any) {
      console.error('Failed to fetch pool:', err);
      throw err;
    }
  }, []);

  const createPool = useCallback(async (data: CreatePoolRequest) => {
    const response = await poolingApi.createPool(data);
    if (response.success) {
      setPools(prev => [...prev, response.data]);
      return response.data;
    }
    throw new Error('Failed to create pool');
  }, []);

  const updatePool = useCallback(async (poolId: string, data: Partial<CreatePoolRequest>) => {
    const response = await poolingApi.updatePool(poolId, data);
    if (response.success) {
      setPools(prev => prev.map(p => p.id === poolId ? response.data : p));
      if (selectedPool?.id === poolId) {
        setSelectedPool(response.data);
      }
      return response.data;
    }
    throw new Error('Failed to update pool');
  }, [selectedPool]);

  const deletePool = useCallback(async (id: string) => {
    await poolingApi.deletePool(id);
    setPools(prev => prev.filter(p => p.id !== id));
    if (selectedPool?.id === id) {
      setSelectedPool(null);
    }
  }, [selectedPool]);

  const addMember = useCallback(async (poolId: string, member: AddMemberRequest) => {
    const response = await poolingApi.addMember(poolId, member);
    if (response.success) {
      // Response returns updated pool with all members
      setPools(prev => prev.map(p => p.id === poolId ? response.data : p));
      if (selectedPool?.id === poolId) {
        setSelectedPool(response.data);
      }
      return response.data;
    }
    throw new Error('Failed to add member');
  }, [selectedPool]);

  const removeMember = useCallback(async (poolId: string, memberId: string) => {
    await poolingApi.removeMember(poolId, memberId);
    // Refresh pool to get updated member list
    await fetchPoolById(poolId);
  }, [fetchPoolById]);

  const calculateInterest = useCallback(async (poolId: string): Promise<CalculateInterestResponse> => {
    const response = await poolingApi.calculateInterest(poolId);
    if (response.success) {
      // Refresh pools to get updated balances and savings
      await fetchPools();
      return response.data;
    }
    throw new Error('Failed to calculate interest');
  }, [fetchPools]);

  useEffect(() => {
    fetchPools();
  }, [fetchPools]);

  const stats = {
    totalPools: pools.length,
    activePools: pools.filter(p => p.status === 'ACTIVE').length,
    totalBalance: pools.reduce((s, p) => s + (p.totalBalance || 0), 0),
    totalSavings: pools.reduce((s, p) => s + (p.interestSavingsYtd || 0), 0),
    totalMembers: pools.reduce((s, p) => s + (p.memberCount || p.members?.length || 0), 0),
  };

  return {
    pools,
    loading,
    error,
    stats,
    selectedPool,
    setSelectedPool,
    fetchPools,
    fetchPoolById,
    createPool,
    updatePool,
    deletePool,
    addMember,
    removeMember,
    calculateInterest
  };
};