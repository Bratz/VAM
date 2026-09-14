import { useState, useEffect, useCallback } from 'react';
import { ihbApi, IhbEntity } from '../services/api';

export const useInHouseBank = () => {
  const [entities, setEntities] = useState<IhbEntity[]>([]);
  // ponytail: `any` here, not a new IhbStats type - ihbApi.getStats() itself
  // is typed ApiResponse<any> (untyped payload), and this hook has zero
  // consumers in the app today, so a real type has no caller to serve.
  const [stats, setStats] = useState<any>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchAll = useCallback(async () => {
    try {
      setLoading(true);
      const [statsRes, entitiesRes] = await Promise.all([
        ihbApi.getStats(),
        ihbApi.getAllEntities(),
      ]);
      if (statsRes.success) setStats(statsRes.data);
      if (entitiesRes.success) setEntities(entitiesRes.data);
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

  useEffect(() => {
    fetchAll();
  }, [fetchAll]);

  return { entities, stats, loading, error, fetchAll, createEntity };
};
