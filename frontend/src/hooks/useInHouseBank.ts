import { useState, useEffect, useCallback } from 'react';
import { ihbApi, IhbEntity, IhbLoan, IhbDeposit, IhbStats } from '../services/api';

export const useInHouseBank = () => {
  const [entities, setEntities] = useState<IhbEntity[]>([]);
  const [loans, setLoans] = useState<IhbLoan[]>([]);
  const [deposits, setDeposits] = useState<IhbDeposit[]>([]);
  const [stats, setStats] = useState<IhbStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchAll = useCallback(async () => {
    try {
      setLoading(true);
      const [statsRes, entitiesRes, loansRes, depositsRes] = await Promise.all([
        ihbApi.getStats(),
        ihbApi.getAllEntities(),
        ihbApi.getAllLoans(),
        ihbApi.getAllDeposits(),
      ]);
      if (statsRes.success) setStats(statsRes.data);
      if (entitiesRes.success) setEntities(entitiesRes.data);
      if (loansRes.success) setLoans(loansRes.data);
      if (depositsRes.success) setDeposits(depositsRes.data);
    } catch (err: any) {
      console.error('Failed to fetch IHB data:', err);
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, []);

  const createEntity = useCallback(async (data: any) => {
    const response = await ihbApi.createEntity(data);
    if (response.success) {
      setEntities(prev => [...prev, response.data]);
      return response.data;
    }
    throw new Error('Failed to create entity');
  }, []);

  const createLoan = useCallback(async (data: any) => {
    const response = await ihbApi.createLoan(data);
    if (response.success) {
      setLoans(prev => [...prev, response.data]);
      await fetchAll(); // Refresh stats
      return response.data;
    }
    throw new Error('Failed to create loan');
  }, [fetchAll]);

  const createDeposit = useCallback(async (data: any) => {
    const response = await ihbApi.createDeposit(data);
    if (response.success) {
      setDeposits(prev => [...prev, response.data]);
      await fetchAll(); // Refresh stats
      return response.data;
    }
    throw new Error('Failed to create deposit');
  }, [fetchAll]);

  const repayLoan = useCallback(async (id: string, amount: number) => {
    const response = await ihbApi.repayLoan(id, amount);
    if (response.success) {
      setLoans(prev => prev.map(l => l.id === id ? response.data : l));
      await fetchAll();
      return response.data;
    }
    throw new Error('Failed to repay loan');
  }, [fetchAll]);

  useEffect(() => {
    fetchAll();
  }, [fetchAll]);

  return { entities, loans, deposits, stats, loading, error, fetchAll, createEntity, createLoan, createDeposit, repayLoan };
};