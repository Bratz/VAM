import React, { useState, useEffect, useMemo, useCallback } from 'react';
import {
  Hash, Plus, Search, Link, Unlink, Loader2, AlertCircle, RefreshCw,
  Database, Settings, Trash2, Eye, Edit2, Copy, Check, Clock, AlertTriangle,
  Layers, Activity, BarChart3, Building2, CreditCard, FileText, ShoppingCart,
  Timer, X, TrendingUp
} from 'lucide-react';
import { Card, Button, Badge, Input , StatusIconBadge, StatTile } from '../components/ui';
import { Modal } from '../components/ui/enhanced';
import { HeroMetricCard } from '../components/ui/HeroMetricCard';
import { vibanApi, programsApi, corporatesApi, virtualAccountsApi, partiesApi } from '../services/api';
import type { BulkVibanAssignItem, BulkVibanAssignResponse, VibanAssignResponse } from '../services/api';
import { usePageHeaderActions } from '../context/PageHeaderContext';
import { Page } from '../components/layout/Page';
import { PageHeader } from '../components/layout/PageHeader';
import { StatStrip } from '../components/layout/StatStrip';
import { ScopeSelector } from '../components/layout/ScopeSelector';
import { formatCurrency } from '../utils';

// ============================================================================
// TYPES
// ============================================================================

interface ApiResponse<T> {
  success: boolean;
  data: T;
  message?: string;
  error?: string;
}

interface VibanPool {
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

interface Viban {
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

interface VibanStats {
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

interface Program {
  id: string;
  programCode: string;
  programName: string;
  programType?: string;
  vibanEnabled?: boolean;
  status?: string;
  corporateId?: string;
}

interface Corporate {
  id: string;
  legalName: string;
  tradeName?: string;
  status?: string;
}

interface VirtualAccount {
  id: string;
  vaNumber: string;
  vaName: string;
  corporateId?: string;
  corporateName?: string;
  programId?: string;
  currencyCode: string;
  currentBalance: number;
  status: string;
  accountCategory?: string;
}

interface Party {
  id: string;
  corporateId?: string;
  partyCode: string;
  legalName: string;
  displayName?: string;
  tradeName?: string;
  partyType: string;
  roles?: string[] | string;  // Backend returns Set<String> as array
  status: string;
}

// ============================================================================
// EXTENDED API LAYER - Wraps existing vibanApi with pool management
// ============================================================================

const vibanPoolApi = {
  // Health check
  checkHealth: async (): Promise<boolean> => {
    try {
      const response = await fetch('/api/v1/health', { method: 'GET', signal: AbortSignal.timeout(5000) });
      return response.ok;
    } catch {
      return false;
    }
  },

  // Get all pools
  getPools: async (programId?: string): Promise<ApiResponse<VibanPool[]>> => {
    try {
      const response = await vibanApi.getPools(programId);
      const data = response?.data;
      return { 
        success: response?.success !== false, 
        data: Array.isArray(data) ? data : [] 
      };
    } catch (error: any) {
      console.error('Failed to fetch pools:', error);
      return { success: false, data: [], error: error.message };
    }
  },

  // Get single pool
  getPool: async (poolId: string): Promise<ApiResponse<VibanPool | null>> => {
    try {
      const response = await vibanApi.getPool(poolId);
      return response;
    } catch (error: any) {
      return { success: false, data: null, error: error.message };
    }
  },

  // Create pool
  createPool: async (programId: string, data: Partial<VibanPool>): Promise<ApiResponse<VibanPool>> => {
    const request = {
      poolName: data.poolName,
      poolCode: data.poolCode,
      bankCode: data.bankCode || '033',
      prefix: data.prefix,
      poolSize: data.poolSize,
      assignmentTtlMinutes: data.assignmentTtlMinutes || 1440
    };
    return vibanApi.createPool(programId, request);
  },

  // Update pool
  updatePool: async (poolId: string, data: Partial<VibanPool>): Promise<ApiResponse<VibanPool>> => {
    return vibanApi.updatePool(poolId, data);
  },

  // Delete pool
  deletePool: async (poolId: string): Promise<ApiResponse<void>> => {
    return vibanApi.deletePool(poolId);
  },

  // Get VIBANs
  getVibans: async (poolId?: string, status?: string): Promise<ApiResponse<Viban[]>> => {
    try {
      const response = await vibanApi.getAll(0, 100, undefined, status !== 'ALL' ? status : undefined);
      let data = Array.isArray(response?.data) ? response.data : [];
      if (poolId) {
        data = data.filter((v: any) => v.poolId === poolId);
      }
      return { success: true, data };
    } catch (error: any) {
      console.error('Failed to fetch VIBANs:', error);
      return { success: false, data: [], error: error.message };
    }
  },

  // Assign from pool (enhanced with partyId and isPrimary)
  assignFromPool: async (
    poolId: string, vaId: string, referenceType?: string,
    referenceId?: string, expectedAmount?: number, partyId?: string, isPrimary?: boolean
  ): Promise<ApiResponse<any>> => {
    return vibanApi.assignFromPool(poolId, {
      virtualAccountId: vaId,
      partyId,
      referenceType,
      referenceId,
      expectedAmount,
      isPrimary
    });
  },

  // Bulk assign from pool
  bulkAssignFromPool: async (poolId: string, assignments: BulkVibanAssignItem[]): Promise<ApiResponse<BulkVibanAssignResponse>> => {
    return vibanApi.bulkAssignFromPool(poolId, { assignments });
  },

  // Return to pool
  returnToPool: async (poolId: string, vibanId: string): Promise<ApiResponse<void>> => {
    return vibanApi.returnToPool(poolId, vibanId);
  },

  // Release VIBAN
  releaseViban: async (viban: string): Promise<ApiResponse<void>> => {
    return vibanApi.release(viban);
  },

  // Get stats - tries /vibans/stats endpoint, falls back to calculated stats
  getStats: async (pools?: VibanPool[]): Promise<ApiResponse<VibanStats>> => {
    const defaultStats: VibanStats = { 
      total: 0, assigned: 0, available: 0, reserved: 0, expired: 0,
      totalPools: 0, activePools: 0, lowThresholdPools: 0, 
      totalPaymentsRouted: 0, totalAmountRouted: 0 
    };
    
    try {
      // Try the /vibans/stats endpoint first
      const response = await fetch('/api/v1/vibans/stats');
      if (response.ok) {
        const data = await response.json();
        console.log('Stats API response:', data);
        if (data?.success && data?.data) {
          return { success: true, data: data.data };
        }
      }
    } catch (error) {
      console.log('Stats endpoint not available, calculating from pools');
    }
    
    // Fallback: Calculate stats from pools data
    if (pools && pools.length > 0) {
      const calculated: VibanStats = {
        total: pools.reduce((sum, p) => sum + (p.poolSize || 0), 0),
        assigned: pools.reduce((sum, p) => sum + (p.assignedCount || 0), 0),
        available: pools.reduce((sum, p) => sum + (p.availableCount || 0), 0),
        reserved: pools.reduce((sum, p) => sum + (p.reservedCount || 0), 0),
        expired: 0,
        totalPools: pools.length,
        activePools: pools.filter(p => p.status === 'ACTIVE').length,
        lowThresholdPools: pools.filter(p => p.status === 'ACTIVE' && p.poolSize > 0 && 
          (p.availableCount / p.poolSize) * 100 <= (p.lowThresholdPercent || 20)).length,
        totalPaymentsRouted: 0,
        totalAmountRouted: 0
      };
      return { success: true, data: calculated };
    }
    
    return { success: true, data: defaultStats };
  },

  // Get programs
  getPrograms: async (): Promise<ApiResponse<Program[]>> => {
    try {
      const response: any = await programsApi.getAll();
      console.log('Raw programs API response:', JSON.stringify(response, null, 2));

      // Backend returns: { success: true, data: { programs: [...], totalCount: N, ... } }
      // After axios .then(r => r.data), we get: { success: true, data: { programs: [...] } }
      // We need to extract the programs array from the nested structure
      let programs: Program[] = [];

      if (response) {
        // Try different possible structures
        if (Array.isArray(response)) {
          // Direct array
          programs = response;
        } else if (response.data) {
          const data = response.data;
          if (Array.isArray(data)) {
            // response.data is array
            programs = data;
          } else if (data.programs && Array.isArray(data.programs)) {
            // response.data.programs is array (expected structure)
            programs = data.programs;
          } else if (data.content && Array.isArray(data.content)) {
            // Spring paginated response
            programs = data.content;
          }
        } else if (response.programs && Array.isArray(response.programs)) {
          // response.programs directly
          programs = response.programs;
        }
      }

      console.log('Extracted programs:', programs.length, programs.map((p: Program) => p.programCode));
      return {
        success: true,
        data: programs
      };
    } catch (error: any) {
      console.error('Failed to fetch programs:', error);
      return { success: false, data: [], error: error.message };
    }
  },

  // Generate VIBANs for pool
  generateVibans: async (poolId: string, count: number): Promise<ApiResponse<{ generated: number }>> => {
    return vibanApi.generateForPool(poolId, count);
  },

  // Get corporates
  getCorporates: async (): Promise<ApiResponse<Corporate[]>> => {
    try {
      const response: any = await corporatesApi.getAll(0, 100);
      let corporates: Corporate[] = [];
      if (response) {
        if (Array.isArray(response)) {
          corporates = response;
        } else if (response.data) {
          const data = response.data;
          if (Array.isArray(data)) {
            corporates = data;
          } else if (data.corporates && Array.isArray(data.corporates)) {
            corporates = data.corporates;
          } else if (data.content && Array.isArray(data.content)) {
            corporates = data.content;
          }
        }
      }
      return { success: true, data: corporates };
    } catch (error: any) {
      console.error('Failed to fetch corporates:', error);
      return { success: false, data: [], error: error.message };
    }
  },

  // Get virtual accounts (for assignment dropdown)
  getVirtualAccounts: async (programId?: string): Promise<ApiResponse<VirtualAccount[]>> => {
    try {
      const response: any = await virtualAccountsApi.getAll(0, 200);
      let accounts: VirtualAccount[] = [];
      if (response) {
        if (Array.isArray(response)) {
          accounts = response;
        } else if (response.data) {
          const data = response.data;
          if (Array.isArray(data)) {
            accounts = data;
          } else if (data.accounts && Array.isArray(data.accounts)) {
            accounts = data.accounts;
          } else if (data.content && Array.isArray(data.content)) {
            accounts = data.content;
          }
        }
      }
      // Filter by programId if provided and filter for TRANSACTION accounts
      if (programId) {
        accounts = accounts.filter(a => a.programId === programId);
      }
      // Filter for active transaction accounts
      accounts = accounts.filter(a => a.status === 'ACTIVE' && (!a.accountCategory || a.accountCategory === 'TRANSACTION'));
      return { success: true, data: accounts };
    } catch (error: any) {
      console.error('Failed to fetch virtual accounts:', error);
      return { success: false, data: [], error: error.message };
    }
  }
};

// ============================================================================
// MAIN COMPONENT
// ============================================================================

const VibanManagementPage: React.FC = () => {
  // Connection state
  const [isConnected, setIsConnected] = useState<boolean | null>(null);

  // Data state
  const [activeTab, setActiveTab] = useState<'overview' | 'pools' | 'vibans'>('overview');
  const [pools, setPools] = useState<VibanPool[]>([]);
  const [vibans, setVibans] = useState<Viban[]>([]);
  const [stats, setStats] = useState<VibanStats | null>(null);
  const [programs, setPrograms] = useState<Program[]>([]);
  const [corporates, setCorporates] = useState<Corporate[]>([]);
  const [virtualAccounts, setVirtualAccounts] = useState<VirtualAccount[]>([]);
  const [parties, setParties] = useState<Party[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [processing, setProcessing] = useState(false);

  // Filters
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedPool, setSelectedPool] = useState<string | null>(null);
  const [selectedCorporate, setSelectedCorporate] = useState<string | null>(null);
  const [selectedProgram, setSelectedProgram] = useState<string | null>(null);
  const [statusFilter, setStatusFilter] = useState<string>('ALL');
  const [assignPoolId, setAssignPoolId] = useState<string | null>(null);

  // Notifications
  const [notification, setNotification] = useState<{ type: 'success' | 'error' | 'info'; message: string } | null>(null);

  // Auto-dismiss notification after 4 seconds
  useEffect(() => {
    if (notification) {
      const timer = setTimeout(() => setNotification(null), 4000);
      return () => clearTimeout(timer);
    }
  }, [notification]);

  const showNotification = (type: 'success' | 'error' | 'info', message: string) => {
    setNotification({ type, message });
  };

  // Modals
  const [showPoolModal, setShowPoolModal] = useState(false);
  const [showGenerateModal, setShowGenerateModal] = useState(false);
  const [showAssignModal, setShowAssignModal] = useState(false);
  const [showBulkAssignModal, setShowBulkAssignModal] = useState(false);
  const [showPoolDetailModal, setShowPoolDetailModal] = useState(false);
  const [editingPool, setEditingPool] = useState<VibanPool | null>(null);
  const [selectedViban, setSelectedViban] = useState<Viban | null>(null);
  const [generatePoolId, setGeneratePoolId] = useState<string | null>(null);
  const [bulkAssignPoolId, setBulkAssignPoolId] = useState<string | null>(null);

  // ============================================================================
  // DATA FETCHING
  // ============================================================================

  const checkConnection = useCallback(async () => {
    const connected = await vibanPoolApi.checkHealth();
    setIsConnected(connected);
    return connected;
  }, []);

  const fetchData = useCallback(async () => {
    try {
      setLoading(true);
      setError(null);

      const connected = await checkConnection();

      if (!connected) {
        setError('Backend unavailable. Please check your connection.');
        // Set empty arrays to prevent filter errors
        setPools([]);
        setVibans([]);
        setPrograms([]);
        setCorporates([]);
        setVirtualAccounts([]);
        setParties([]);
        return;
      }

      // Fetch from backend
      const [poolsRes, vibansRes, programsRes, corporatesRes, virtualAccountsRes, partiesRes] = await Promise.all([
        vibanPoolApi.getPools(selectedProgram || undefined),
        vibanPoolApi.getVibans(selectedPool || undefined, statusFilter !== 'ALL' ? statusFilter : undefined),
        vibanPoolApi.getPrograms(),
        vibanPoolApi.getCorporates(),
        vibanPoolApi.getVirtualAccounts(selectedProgram || undefined),
        // Fetch parties - pass corporateId for filtering (uses X-Corporate-Id header)
        partiesApi.getAll({
          status: 'ACTIVE',
          corporateId: selectedCorporate || undefined
        }).catch((err) => {
          console.error('Failed to fetch parties:', err);
          return { parties: [] };
        })
      ]);

      // Always ensure arrays are set (never undefined)
      const poolsData = Array.isArray(poolsRes?.data) ? poolsRes.data : [];
      const vibansData = Array.isArray(vibansRes?.data) ? vibansRes.data : [];
      const programsData = Array.isArray(programsRes?.data) ? programsRes.data : [];
      const corporatesData = Array.isArray(corporatesRes?.data) ? corporatesRes.data : [];
      const virtualAccountsData = Array.isArray(virtualAccountsRes?.data) ? virtualAccountsRes.data : [];

      // Debug: Log raw parties response
      console.log('Raw parties response:', partiesRes);

      // Extract parties - try multiple possible structures
      let partiesData: Party[] = [];
      if (partiesRes) {
        if (Array.isArray(partiesRes)) {
          partiesData = partiesRes;
        } else if (Array.isArray(partiesRes.parties)) {
          partiesData = partiesRes.parties;
        } else if (Array.isArray(partiesRes.data)) {
          partiesData = partiesRes.data;
        } else if (partiesRes.data?.parties && Array.isArray(partiesRes.data.parties)) {
          partiesData = partiesRes.data.parties;
        }
      }

      console.log('Fetched data:', {
        pools: poolsData.length,
        vibans: vibansData.length,
        programs: programsData.length,
        corporates: corporatesData.length,
        virtualAccounts: virtualAccountsData.length,
        parties: partiesData.length
      });

      // Enrich pools with calculated fields
      const enrichedPools = poolsData.map(pool => ({
        ...pool,
        assignedCount: pool.assignedCount || (pool.poolSize - pool.availableCount - (pool.reservedCount || 0)),
      }));

      setPools(enrichedPools);
      setVibans(vibansData);
      setPrograms(programsData);
      setCorporates(corporatesData);
      setVirtualAccounts(virtualAccountsData);
      setParties(partiesData);

      // Get stats (will calculate from pools if API fails)
      const statsRes = await vibanPoolApi.getStats(enrichedPools);
      if (statsRes?.data) {
        setStats(statsRes.data);
      }

    } catch (err: any) {
      console.error('Fetch error:', err);
      setError(err.message || 'Failed to load data');
      // Ensure arrays are set even on error
      setPools([]);
      setVibans([]);
      setPrograms([]);
      setCorporates([]);
      setVirtualAccounts([]);
      setParties([]);
    } finally {
      setLoading(false);
    }
  }, [checkConnection, selectedProgram, selectedPool, statusFilter, selectedCorporate]);

  useEffect(() => { fetchData(); }, []);

  // Refetch when filters change
  useEffect(() => {
    if (isConnected && !loading) {
      fetchData();
    }
  }, [selectedProgram, selectedPool, statusFilter, selectedCorporate]);

  // Toolbar actions live in the Layout header, not in the page body.
  usePageHeaderActions(
    () => (
      <>
        <Button variant="outline" size="sm" leftIcon={<RefreshCw className="w-4 h-4" />} onClick={fetchData}>
          Refresh
        </Button>
        <Button size="sm" leftIcon={<Plus className="w-4 h-4" />} onClick={() => { setEditingPool(null); setShowPoolModal(true); }}>
          Create Pool
        </Button>
      </>
    ),
    [fetchData]
  );

  // Auto-select first corporate when corporates are loaded (required for party filtering)
  useEffect(() => {
    if (!selectedCorporate && corporates.length > 0) {
      setSelectedCorporate(corporates[0].id);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [corporates.length]); // Only trigger when corporates length changes, not on selectedCorporate

  // Reset program selection when corporate changes
  useEffect(() => {
    if (selectedCorporate) {
      // Filter programs by corporate
      const filteredPrograms = programs.filter(p => (p as any).corporateId === selectedCorporate);
      if (selectedProgram && !filteredPrograms.find(p => p.id === selectedProgram)) {
        setSelectedProgram(null);
      }
    }
  }, [selectedCorporate]);

  // ============================================================================
  // HANDLERS
  // ============================================================================

  const handleCreatePool = async (data: Partial<VibanPool>) => {
    if (!data.programId) { showNotification('error', 'Program is required'); return; }
    setProcessing(true);
    try {
      await vibanPoolApi.createPool(data.programId, data);
      await fetchData();
      setShowPoolModal(false); 
      setEditingPool(null);
      showNotification('success', `Pool "${data.poolName}" created successfully with ${data.poolSize} VIBANs`);
    } catch (err: any) {
      showNotification('error', err.response?.data?.message || err.message || 'Failed to create pool');
    } finally {
      setProcessing(false);
    }
  };

  const handleUpdatePool = async (id: string, data: Partial<VibanPool>) => {
    setProcessing(true);
    try {
      await vibanPoolApi.updatePool(id, data);
      await fetchData();
      setShowPoolModal(false); 
      setEditingPool(null);
      showNotification('success', `Pool "${data.poolName}" updated successfully`);
    } catch (err: any) {
      showNotification('error', err.response?.data?.message || err.message || 'Failed to update pool');
    } finally {
      setProcessing(false);
    }
  };

  const handleDeletePool = async (id: string) => {
    const pool = pools.find(p => p.id === id);
    if (pool && pool.assignedCount > 0) { 
      showNotification('error', 'Cannot delete pool with assigned VIBANs'); 
      return; 
    }
    if (!confirm('Delete this pool? This action cannot be undone.')) return;
    setProcessing(true);
    try {
      await vibanPoolApi.deletePool(id);
      await fetchData();
      showNotification('success', `Pool "${pool?.poolName}" deleted successfully`);
    } catch (err: any) {
      showNotification('error', err.response?.data?.message || err.message || 'Failed to delete pool');
    } finally {
      setProcessing(false);
    }
  };

  const handleGenerateVibans = async (poolId: string, count: number) => {
    setProcessing(true);
    try {
      await vibanPoolApi.generateVibans(poolId, count);
      await fetchData();
      setShowGenerateModal(false); 
      setGeneratePoolId(null);
      const pool = pools.find(p => p.id === poolId);
      showNotification('success', `Generated ${count} VIBANs for pool "${pool?.poolName}"`);
    } catch (err: any) {
      showNotification('error', err.response?.data?.message || err.message || 'Failed to generate VIBANs');
    } finally {
      setProcessing(false);
    }
  };

  const handleAssignViban = async (poolId: string, vaId: string, options: {
    partyId?: string; referenceType?: string; referenceId?: string;
    expectedAmount?: number; isPrimary?: boolean;
  }) => {
    setProcessing(true);
    try {
      const result = await vibanPoolApi.assignFromPool(poolId, vaId, options.referenceType, options.referenceId, options.expectedAmount, options.partyId, options.isPrimary);
      await fetchData();
      setShowAssignModal(false);
      setSelectedViban(null);
      setAssignPoolId(null);
      const vibanNum = result?.data?.viban;
      const isPrimary = options.isPrimary;
      showNotification('success', `${isPrimary ? 'Primary ' : ''}VIBAN assigned successfully${vibanNum ? `: ${vibanNum}` : ''}`);
    } catch (err: any) {
      showNotification('error', err.response?.data?.message || err.message || 'Failed to assign VIBAN');
    } finally {
      setProcessing(false);
    }
  };

  const handleBulkAssignViban = async (poolId: string, assignments: BulkVibanAssignItem[]) => {
    setProcessing(true);
    try {
      const result = await vibanPoolApi.bulkAssignFromPool(poolId, assignments);
      await fetchData();
      setShowBulkAssignModal(false);
      setBulkAssignPoolId(null);
      const successCount = result?.data?.successCount || 0;
      const failedCount = result?.data?.failedCount || 0;
      if (failedCount > 0) {
        showNotification('info', `Bulk assignment completed: ${successCount} succeeded, ${failedCount} failed`);
      } else {
        showNotification('success', `Successfully assigned ${successCount} VIBANs`);
      }
    } catch (err: any) {
      showNotification('error', err.response?.data?.message || err.message || 'Failed to bulk assign VIBANs');
    } finally {
      setProcessing(false);
    }
  };

  const handleReleaseViban = async (viban: Viban) => {
    if (!confirm('Release this VIBAN back to the pool?')) return;
    setProcessing(true);
    try {
      if (viban.poolId) {
        await vibanPoolApi.returnToPool(viban.poolId, viban.id);
      } else {
        await vibanPoolApi.releaseViban(viban.viban);
      }
      await fetchData();
      showNotification('success', `VIBAN ${viban.viban} released successfully`);
    } catch (err: any) {
      showNotification('error', err.response?.data?.message || err.message || 'Failed to release VIBAN');
    } finally {
      setProcessing(false);
    }
  };

  // ============================================================================
  // FILTERED DATA
  // ============================================================================

  const filteredPools = useMemo(() => {
    return pools.filter(p => {
      if (selectedProgram && p.programId !== selectedProgram) return false;
      if (statusFilter !== 'ALL' && p.status !== statusFilter) return false;
      if (searchQuery) {
        const q = searchQuery.toLowerCase();
        return p.poolName.toLowerCase().includes(q) || 
               p.poolCode.toLowerCase().includes(q) || 
               p.programName?.toLowerCase().includes(q);
      }
      return true;
    });
  }, [pools, selectedProgram, searchQuery, statusFilter]);

  const filteredVibans = useMemo(() => {
    return vibans.filter(v => {
      if (selectedPool && v.poolId !== selectedPool) return false;
      if (statusFilter !== 'ALL' && v.status !== statusFilter) return false;
      if (searchQuery) {
        const q = searchQuery.toLowerCase();
        return v.viban.toLowerCase().includes(q) || 
               v.vaNumber?.toLowerCase().includes(q) || 
               v.poolCode?.toLowerCase().includes(q);
      }
      return true;
    });
  }, [vibans, selectedPool, searchQuery, statusFilter]);

  // ============================================================================
  // HELPERS
  // ============================================================================

  const getStatusBadge = (status: string) => {
    const config: Record<string, { variant: 'success' | 'warning' | 'info' | 'neutral' | 'error'; label: string }> = {
      ACTIVE: { variant: 'success', label: 'Active' },
      RETURNED: { variant: 'info', label: 'Available' },
      AVAILABLE: { variant: 'info', label: 'Available' },
      PARTIAL: { variant: 'warning', label: 'Reserved' },
      RESERVED: { variant: 'warning', label: 'Reserved' },
      EXPIRED: { variant: 'neutral', label: 'Expired' },
      EXHAUSTED: { variant: 'error', label: 'Exhausted' },
      INACTIVE: { variant: 'neutral', label: 'Inactive' }
    };
    const c = config[status] || { variant: 'neutral', label: status };
    return <Badge variant={c.variant}>{c.label}</Badge>;
  };

  const formatTtl = (minutes: number) => {
    if (minutes === 0) return 'Permanent';
    if (minutes < 60) return `${minutes} min`;
    if (minutes < 1440) return `${Math.round(minutes / 60)} hours`;
    return `${Math.round(minutes / 1440)} days`;
  };

  // formatCurrency now comes from the shared util (Phase 12 Task D3) — the
  // local AED-only en-AE clone was deleted; the shared default currency is the
  // active market (AED), so 1-arg call sites are unchanged.
  const formatNumber = (num: number) => new Intl.NumberFormat('en-AE').format(num);

  // ============================================================================
  // LOADING
  // ============================================================================

  if (loading) {
    return (
      <div className="flex items-center justify-center py-24">
        <Loader2 className="w-8 h-8 animate-spin text-primary-500" />
        <span className="ml-3 text-lg">Loading VIBAN Management...</span>
      </div>
    );
  }

  // ============================================================================
  // RENDER
  // ============================================================================

  return (
    <Page>
      {/* Toast Notification */}
      {notification && (
        <div className={`fixed top-4 right-4 z-50 max-w-md p-4 rounded-lg shadow-lg border flex items-center gap-3 animate-in slide-in-from-top-2 duration-300 ${
          notification.type === 'success' ? 'bg-success-50 border-success-300 text-success-800 dark:bg-success-500/10 dark:text-success-300' :
          notification.type === 'error' ? 'bg-error-50 border-error-300 text-error-800 dark:bg-error-500/10 dark:text-error-300' :
          'bg-info-50 border-info-300 text-info-800 dark:bg-info-500/10 dark:text-info-300'
        }`}>
          {notification.type === 'success' && <Check className="w-5 h-5 text-success-600 flex-shrink-0 dark:text-success-300" />}
          {notification.type === 'error' && <AlertCircle className="w-5 h-5 text-error-600 flex-shrink-0 dark:text-error-300" />}
          {notification.type === 'info' && <AlertCircle className="w-5 h-5 text-info-600 flex-shrink-0 dark:text-info-300" />}
          <span className="flex-1">{notification.message}</span>
          <button onClick={() => setNotification(null)} className="text-neutral-400 hover:text-neutral-600 dark:text-neutral-500">
            <X className="w-4 h-4" />
          </button>
        </div>
      )}

      {/* In-body identity (Phase 10) — aligns with Bank Accounts /
          Virtual Accounts page header treatment. Quick Actions are lifted
          into the Aperture Layout header via `usePageHeaderActions` above. */}
      <PageHeader
        title="VIBAN Management"
        description="Manage Virtual IBAN pools and assignments. Allocate VIBANs to virtual accounts, monitor pool depletion, and run bulk assignments."
      />

      {/* Corporate / Program picker — shared `<ScopeSelector>` primitive,
          matching the recipe used on every other corporate-scoped page
          (Bank Accounts, Virtual Accounts, Intercompany, etc.). The local
          `selectedCorporate` / `selectedProgram` state is `string | null`
          for legacy reasons; we adapt at the prop boundary. The page-
          specific filter (only ACTIVE programs that belong to the selected
          corporate) is applied at the call site. */}
      <ScopeSelector
        mode="corporate-program"
        corporates={corporates}
        programs={programs.filter(p =>
          p.status === 'ACTIVE'
          && (!selectedCorporate || (p as any).corporateId === selectedCorporate),
        )}
        selectedCorporateId={selectedCorporate || ''}
        selectedProgramId={selectedProgram || ''}
        onCorporateChange={(id) => { setSelectedCorporate(id || null); setSelectedProgram(null); }}
        onProgramChange={(id) => setSelectedProgram(id || null)}
        loading={loading}
      />

      {/* Error */}
      {error && (
        <Card className="bg-error-50 border-error-200 dark:bg-error-500/10 dark:border-error-500/30">
          <div className="flex items-center justify-between p-4">
            <div className="flex items-center gap-3">
              <AlertCircle className="w-5 h-5 text-error-600 dark:text-error-300" />
              <span className="text-error-800 dark:text-error-300">{error}</span>
            </div>
            <Button size="sm" variant="ghost" onClick={() => setError(null)}><X className="w-4 h-4" /></Button>
          </div>
        </Card>
      )}

      {/* Tabs */}
      <div className="flex gap-1 border-b border-neutral-200 animate-fade-in dark:border-primary-800" style={{ animationDelay: '0.15s' }}>
        {[
          { id: 'overview', label: 'Overview', icon: BarChart3 },
          { id: 'pools', label: 'Pools', icon: Database, count: pools.length },
          { id: 'vibans', label: 'VIBANs', icon: Hash, count: vibans.length }
        ].map(tab => (
          <button key={tab.id} onClick={() => { setActiveTab(tab.id as any); setStatusFilter('ALL'); setSearchQuery(''); }}
            className={`flex items-center gap-2 px-4 py-3 text-sm font-medium border-b-2 transition-colors ${
              activeTab === tab.id ? 'border-primary-600 text-primary-600 dark:border-accent-400 dark:text-primary-200' : 'border-transparent text-neutral-500 hover:text-neutral-700 dark:text-neutral-400 dark:hover:text-neutral-200'
            }`}>
            <tab.icon className="w-4 h-4" />{tab.label}
            {tab.count !== undefined && (
              // Count pill — explicit text color in both modes so dark mode
              // doesn't dim the number to invisible against the dark surface.
              <span className={`text-xs font-semibold px-1.5 py-0.5 rounded-full ${
                activeTab === tab.id
                  ? 'bg-primary-100 text-primary-700 dark:bg-accent-500/20 dark:text-accent-300'
                  : 'bg-neutral-100 text-neutral-600 dark:bg-primary-800/80 dark:text-neutral-200'
              }`}>{tab.count}</span>
            )}
          </button>
        ))}
      </div>

      {/* Tab Content */}
      {activeTab === 'overview' && <OverviewTab stats={stats} pools={pools} formatNumber={formatNumber} formatCurrency={formatCurrency} />}
      {activeTab === 'pools' && (
        <PoolsTab pools={filteredPools} programs={programs} searchQuery={searchQuery} setSearchQuery={setSearchQuery}
          selectedProgram={selectedProgram} setSelectedProgram={setSelectedProgram} statusFilter={statusFilter} setStatusFilter={setStatusFilter}
          onEdit={(p) => { setEditingPool(p); setShowPoolModal(true); }} onDelete={handleDeletePool}
          onGenerate={(id) => { setGeneratePoolId(id); setShowGenerateModal(true); }}
          onViewDetails={(p) => { setEditingPool(p); setShowPoolDetailModal(true); }}
          onAssignToVA={(poolId) => { setAssignPoolId(poolId); setShowAssignModal(true); }}
          onBulkAssign={(poolId) => { setBulkAssignPoolId(poolId); setShowBulkAssignModal(true); }}
          getStatusBadge={getStatusBadge} formatTtl={formatTtl} formatNumber={formatNumber} processing={processing} />
      )}
      {activeTab === 'vibans' && (
        <VibansTab vibans={filteredVibans} pools={pools} searchQuery={searchQuery} setSearchQuery={setSearchQuery}
          selectedPool={selectedPool} setSelectedPool={setSelectedPool} statusFilter={statusFilter} setStatusFilter={setStatusFilter}
          onAssign={(v) => { setSelectedViban(v); setShowAssignModal(true); }} onRelease={handleReleaseViban}
          getStatusBadge={getStatusBadge} formatCurrency={formatCurrency} processing={processing} />
      )}

      {/* Modals */}
      <Modal isOpen={showPoolModal} onClose={() => { setShowPoolModal(false); setEditingPool(null); }}
        title={editingPool ? 'Edit Pool' : 'Create VIBAN Pool'} size="lg">
        <PoolForm pool={editingPool} programs={programs}
          onSubmit={(data) => editingPool ? handleUpdatePool(editingPool.id, data) : handleCreatePool(data)}
          onCancel={() => { setShowPoolModal(false); setEditingPool(null); }} loading={processing} />
      </Modal>

      <Modal isOpen={showGenerateModal} onClose={() => { setShowGenerateModal(false); setGeneratePoolId(null); }} title="Generate VIBANs">
        <GenerateForm pool={pools.find(p => p.id === generatePoolId)}
          onSubmit={(count) => generatePoolId && handleGenerateVibans(generatePoolId, count)}
          onCancel={() => { setShowGenerateModal(false); setGeneratePoolId(null); }} loading={processing} />
      </Modal>

      <Modal isOpen={showAssignModal} onClose={() => { setShowAssignModal(false); setSelectedViban(null); setAssignPoolId(null); }} title="Assign VIBAN to Virtual Account">
        <AssignForm
          viban={selectedViban}
          pools={assignPoolId ? pools.filter(p => p.id === assignPoolId) : pools}
          virtualAccounts={virtualAccounts}
          parties={parties}
          onSubmit={(poolId, vaId, options) => handleAssignViban(poolId, vaId, options)}
          onCancel={() => { setShowAssignModal(false); setSelectedViban(null); setAssignPoolId(null); }}
          loading={processing}
        />
      </Modal>

      <Modal isOpen={showPoolDetailModal} onClose={() => { setShowPoolDetailModal(false); setEditingPool(null); }} title="Pool Details" size="lg">
        {editingPool && <PoolDetailView pool={editingPool} vibans={vibans.filter(v => v.poolId === editingPool.id)}
          getStatusBadge={getStatusBadge} formatTtl={formatTtl} formatNumber={formatNumber} formatCurrency={formatCurrency} />}
      </Modal>

      <Modal isOpen={showBulkAssignModal} onClose={() => { setShowBulkAssignModal(false); setBulkAssignPoolId(null); }} title="Bulk Assign VIBANs" size="lg">
        <BulkAssignForm
          pool={pools.find(p => p.id === bulkAssignPoolId) || null}
          virtualAccounts={virtualAccounts}
          parties={parties}
          onSubmit={(assignments) => bulkAssignPoolId && handleBulkAssignViban(bulkAssignPoolId, assignments)}
          onCancel={() => { setShowBulkAssignModal(false); setBulkAssignPoolId(null); }}
          loading={processing}
        />
      </Modal>
    </Page>
  );
};

// ============================================================================
// OVERVIEW TAB
// ============================================================================

const OverviewTab: React.FC<{
  stats: VibanStats | null; pools: VibanPool[]; formatNumber: (n: number) => string; formatCurrency: (n: number) => string;
}> = ({ stats, pools, formatNumber, formatCurrency }) => {
  if (!stats) return <div className="text-center py-12 text-neutral-500 dark:text-neutral-400">No statistics available</div>;

  const lowThresholdPools = pools.filter(p => p.status === 'ACTIVE' && (p.availableCount / p.poolSize) * 100 <= p.lowThresholdPercent);

  // VIBAN page tells an inventory + utilisation story. Hero leads with both;
  // the operational counts (available/reserved/pools) demote to the strip below.
  const utilPct = stats.total > 0 ? (stats.assigned / stats.total) * 100 : 0;
  // Threshold-driven trend tone (replaces always-green / always-amber decorations)
  const utilTone: 'success' | 'error' | 'neutral' =
    utilPct >= 95 ? 'error' : utilPct >= 50 ? 'success' : 'neutral';
  return (
    <div className="space-y-6">
      {/* Hero — Inventory + Utilisation. Tone of the utilisation pill reflects
          actual state (neutral when under-used, success when healthy, error when
          saturated). Replaces five equal-weight tiles competing for the eye. */}
      <HeroMetricCard
        primary={{
          label: 'VIBAN Inventory',
          value: formatNumber(stats.total),
          sub: `Allocated across ${stats.totalPools} ${stats.totalPools === 1 ? 'pool' : 'pools'}`,
        }}
        secondary={{
          label: 'Utilisation',
          value: `${utilPct.toFixed(1)}%`,
          trend: `${formatNumber(stats.assigned)} assigned`,
          trendTone: utilTone,
          sub: (
            <>
              {formatNumber(stats.available)} available · {formatNumber(stats.reserved)} reserved
            </>
          ),
        }}
        icon={<Hash className="w-7 h-7 text-accent-600 dark:text-accent-300" />}
      />

      {/* Operational strip — neutral by default; only Reserved goes amber when
          there's actually something reserved (was always-amber before, which
          read as a warning even when the value was 0). */}
      <StatStrip>
        <StatTile
          layout="row"
          tone="info"
          label="Available"
          value={formatNumber(stats.available)}
          icon={<Database className="w-5 h-5" />}
          delay="0.15s"
        />
        <StatTile
          layout="row"
          tone={stats.reserved > 0 ? 'warning' : 'neutral'}
          valueTone={stats.reserved > 0 ? 'warning' : 'neutral'}
          label="Reserved"
          value={formatNumber(stats.reserved)}
          icon={<Timer className="w-5 h-5" />}
          delay="0.2s"
        />
        <StatTile
          layout="row"
          tone="accent"
          label="Total Pools"
          value={stats.totalPools}
          icon={<Layers className="w-5 h-5" />}
          delay="0.25s"
        />
      </StatStrip>

      {/* Payment Stats */}
      <div className="grid grid-cols-2 gap-4">
        <Card hover className="animate-fade-in" style={{ animationDelay: '0.45s' }}>
          <div className="flex items-center gap-4">
            <div className="w-14 h-14 rounded-xl bg-gradient-to-br from-primary-500 to-primary-700 flex items-center justify-center">
              <Activity className="w-7 h-7 text-white" />
            </div>
            <div>
              <p className="stat-value-sm">{formatNumber(stats.totalPaymentsRouted)}</p>
              <p className="text-sm text-neutral-500 dark:text-neutral-400">Payments Routed</p>
            </div>
          </div>
        </Card>
        <Card hover className="animate-fade-in" style={{ animationDelay: '0.5s' }}>
          <div className="flex items-center gap-4">
            <div className="w-14 h-14 rounded-xl bg-gradient-to-br from-success-500 to-success-700 flex items-center justify-center">
              <CreditCard className="w-7 h-7 text-white" />
            </div>
            <div>
              <p className="stat-value-sm">{formatCurrency(stats.totalAmountRouted)}</p>
              <p className="text-sm text-neutral-500 dark:text-neutral-400">Amount Routed</p>
            </div>
          </div>
        </Card>
      </div>

      {/* Low Threshold Alert */}
      {lowThresholdPools.length > 0 && (
        <Card className="border-warning-200 bg-warning-50 animate-fade-in dark:border-warning-500/30 dark:bg-warning-500/10" style={{ animationDelay: '0.55s' }}>
          <div className="flex items-center gap-3 mb-3">
            <StatusIconBadge tone="warning" icon={AlertTriangle} className="dark:bg-warning-500/20" />
            <h3 className="font-medium text-warning-800 dark:text-warning-300">Low Availability Alert</h3>
          </div>
          <p className="text-sm text-warning-700 mb-3 dark:text-warning-300">{lowThresholdPools.length} pool(s) running low:</p>
          <div className="space-y-2">
            {lowThresholdPools.map(pool => (
              <div key={pool.id} className="flex items-center justify-between bg-white rounded-lg px-3 py-2 hover:bg-warning-50 transition-colors dark:bg-primary-900 dark:hover:bg-warning-500/10">
                <div className="flex items-center gap-2"><Database className="w-4 h-4 text-warning-600 dark:text-warning-300" /><span className="font-medium">{pool.poolName}</span></div>
                <span className="text-sm"><span className="text-warning-600 font-medium dark:text-warning-300">{pool.availableCount}</span> / {pool.poolSize}</span>
              </div>
            ))}
          </div>
        </Card>
      )}

      {/* Pool Summary */}
      <Card className="animate-fade-in" style={{ animationDelay: '0.6s' }}>
        <div className="p-4 border-b flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-primary-500 to-primary-700 flex items-center justify-center">
            <Database className="w-5 h-5 text-white" />
          </div>
          <h3 className="font-medium">Pool Summary</h3>
        </div>
        <div className="divide-y">
          {pools.map(pool => {
            const util = ((pool.assignedCount + pool.reservedCount) / pool.poolSize) * 100;
            return (
              <div key={pool.id} className="p-4 flex items-center justify-between hover:bg-neutral-50 dark:hover:bg-primary-800/50">
                <div className="flex items-center gap-4">
                  <div className={`p-2 rounded-lg ${pool.status === 'EXHAUSTED' ? 'bg-error-100 dark:bg-error-500/20' : 'bg-primary-100 dark:bg-primary-700'}`}>
                    <Database className={`w-5 h-5 ${pool.status === 'EXHAUSTED' ? 'text-error-600 dark:text-error-300' : 'text-primary-600 dark:text-primary-200'}`} />
                  </div>
                  <div><p className="font-medium">{pool.poolName}</p><p className="text-sm text-neutral-500 dark:text-neutral-400">{pool.poolCode}</p></div>
                </div>
                <div className="flex items-center gap-8">
                  <div className="text-right"><p className="text-sm font-medium">{formatNumber(pool.availableCount)} available</p><p className="text-xs text-neutral-500 dark:text-neutral-400">of {formatNumber(pool.poolSize)}</p></div>
                  <div className="w-32">
                    <div className="flex justify-between text-xs mb-1"><span>Utilization</span><span>{util.toFixed(1)}%</span></div>
                    <div className="h-2 bg-neutral-200 rounded-full overflow-hidden dark:bg-primary-800">
                      <div className={`h-full ${util > 90 ? 'bg-error-500' : util > 70 ? 'bg-warning-500' : 'bg-success-500'}`} style={{ width: `${util}%` }} />
                    </div>
                  </div>
                  <Badge variant={pool.status === 'ACTIVE' ? 'success' : pool.status === 'EXHAUSTED' ? 'error' : 'neutral'}>{pool.status}</Badge>
                </div>
              </div>
            );
          })}
          {pools.length === 0 && <div className="text-center py-12 text-neutral-500 dark:text-neutral-400">No pools created yet</div>}
        </div>
      </Card>
    </div>
  );
};

// ============================================================================
// POOLS TAB
// ============================================================================

const PoolsTab: React.FC<{
  pools: VibanPool[]; programs: Program[]; searchQuery: string; setSearchQuery: (q: string) => void;
  selectedProgram: string | null; setSelectedProgram: (id: string | null) => void;
  statusFilter: string; setStatusFilter: (s: string) => void;
  onEdit: (pool: VibanPool) => void; onDelete: (id: string) => void; onGenerate: (poolId: string) => void;
  onViewDetails: (pool: VibanPool) => void; onAssignToVA: (poolId: string) => void; onBulkAssign: (poolId: string) => void;
  getStatusBadge: (status: string) => React.ReactNode;
  formatTtl: (minutes: number) => string; formatNumber: (n: number) => string; processing: boolean;
}> = ({ pools, programs, searchQuery, setSearchQuery, selectedProgram, setSelectedProgram, statusFilter, setStatusFilter,
       onEdit, onDelete, onGenerate, onViewDetails, onAssignToVA, onBulkAssign, getStatusBadge, formatTtl, formatNumber, processing }) => {
  return (
    <div className="space-y-4">
      {/* Filters */}
      <Card><div className="p-4 flex gap-4">
        <div className="flex-1 relative">
          <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-neutral-400 dark:text-neutral-500" />
          <Input className="pl-9" placeholder="Search pools..." value={searchQuery} onChange={e => setSearchQuery(e.target.value)} />
        </div>
        <select value={selectedProgram || ''} onChange={e => setSelectedProgram(e.target.value || null)} className="px-3 py-2 border rounded-lg text-sm min-w-[180px]">
          <option value="">All Programs</option>
          {programs.map(p => <option key={p.id} value={p.id}>{p.programName}</option>)}
        </select>
        <select value={statusFilter} onChange={e => setStatusFilter(e.target.value)} className="px-3 py-2 border rounded-lg text-sm">
          <option value="ALL">All Status</option>
          <option value="ACTIVE">Active</option>
          <option value="INACTIVE">Inactive</option>
          <option value="EXHAUSTED">Exhausted</option>
        </select>
      </div></Card>

      {/* Pools Grid */}
      <div className="grid grid-cols-2 gap-4">
        {pools.map(pool => {
          const availPct = (pool.availableCount / pool.poolSize) * 100;
          const isLow = availPct <= pool.lowThresholdPercent;
          return (
            <Card key={pool.id} className={`hover:shadow-md transition-shadow ${isLow && pool.status === 'ACTIVE' ? 'border-warning-300' : ''}`}>
              <div className="p-4">
                <div className="flex items-start justify-between mb-4">
                  <div className="flex items-center gap-3">
                    <div className={`p-2 rounded-lg ${pool.status === 'EXHAUSTED' ? 'bg-error-100 dark:bg-error-500/20' : isLow ? 'bg-warning-100 dark:bg-warning-500/20' : 'bg-primary-100 dark:bg-primary-700'}`}>
                      <Database className={`w-5 h-5 ${pool.status === 'EXHAUSTED' ? 'text-error-600 dark:text-error-300' : isLow ? 'text-warning-600 dark:text-warning-300' : 'text-primary-600 dark:text-primary-200'}`} />
                    </div>
                    <div><h3 className="font-semibold">{pool.poolName}</h3><p className="text-sm text-neutral-500 dark:text-neutral-400">{pool.poolCode}</p></div>
                  </div>
                  {getStatusBadge(pool.status)}
                </div>
                <div className="flex items-center gap-2 text-sm text-neutral-600 mb-4 dark:text-neutral-300"><Building2 className="w-4 h-4" /><span>{pool.programName || 'Unknown'}</span></div>
                <div className="grid grid-cols-3 gap-3 mb-4">
                  <div className="bg-neutral-50 rounded-lg p-2 text-center dark:bg-primary-950"><p className="text-lg font-bold text-info-600 dark:text-info-300">{formatNumber(pool.availableCount)}</p><p className="text-xs text-neutral-500 dark:text-neutral-400">Available</p></div>
                  <div className="bg-neutral-50 rounded-lg p-2 text-center dark:bg-primary-950"><p className="text-lg font-bold text-success-600 dark:text-success-300">{formatNumber(pool.assignedCount)}</p><p className="text-xs text-neutral-500 dark:text-neutral-400">Assigned</p></div>
                  <div className="bg-neutral-50 rounded-lg p-2 text-center dark:bg-primary-950"><p className="text-lg font-bold text-warning-600 dark:text-warning-300">{formatNumber(pool.reservedCount)}</p><p className="text-xs text-neutral-500 dark:text-neutral-400">Reserved</p></div>
                </div>
                <div className="mb-4">
                  <div className="flex justify-between text-xs mb-1"><span className="text-neutral-500 dark:text-neutral-400">Utilization</span><span>{((pool.assignedCount + pool.reservedCount) / pool.poolSize * 100).toFixed(1)}%</span></div>
                  <div className="h-2 bg-neutral-200 rounded-full overflow-hidden flex dark:bg-primary-800">
                    <div className="h-full bg-success-500" style={{ width: `${(pool.assignedCount / pool.poolSize) * 100}%` }} />
                    <div className="h-full bg-warning-500" style={{ width: `${(pool.reservedCount / pool.poolSize) * 100}%` }} />
                  </div>
                </div>
                <div className="grid grid-cols-2 gap-2 text-sm mb-4">
                  <div className="flex items-center gap-2 text-neutral-600 dark:text-neutral-300"><Clock className="w-3 h-3" /><span>TTL: {formatTtl(pool.assignmentTtlMinutes)}</span></div>
                  <div className="flex items-center gap-2 text-neutral-600 dark:text-neutral-300"><Hash className="w-3 h-3" /><span>Prefix: {pool.prefix}</span></div>
                </div>
                {isLow && pool.status === 'ACTIVE' && (
                  <div className="flex items-center gap-2 text-xs text-warning-600 bg-warning-50 rounded-lg px-3 py-2 mb-4 dark:text-warning-300 dark:bg-warning-500/10">
                    <AlertTriangle className="w-3 h-3" /><span>Below {pool.lowThresholdPercent}% threshold</span>
                  </div>
                )}
                <div className="flex flex-col gap-2 pt-3 border-t">
                  {/* Primary actions: Assign to VA (single and bulk) */}
                  <div className="flex gap-2">
                    <Button
                      size="sm"
                      onClick={() => onAssignToVA(pool.id)}
                      disabled={pool.status !== 'ACTIVE' || pool.availableCount === 0}
                      className="flex-1"
                    >
                      <Link className="w-4 h-4 mr-1" />
                      Assign
                    </Button>
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => onBulkAssign(pool.id)}
                      disabled={pool.status !== 'ACTIVE' || pool.availableCount < 2}
                      className="flex-1"
                    >
                      <Layers className="w-4 h-4 mr-1" />
                      Bulk Assign
                    </Button>
                  </div>
                  {/* Secondary actions */}
                  <div className="flex gap-2">
                    <Button size="sm" variant="outline" onClick={() => onViewDetails(pool)} className="flex-1"><Eye className="w-4 h-4 mr-1" />View</Button>
                    <Button size="sm" variant="outline" onClick={() => onGenerate(pool.id)} disabled={pool.status !== 'ACTIVE'} className="flex-1"><Plus className="w-4 h-4 mr-1" />Generate</Button>
                    <Button size="sm" variant="ghost" onClick={() => onEdit(pool)}><Edit2 className="w-4 h-4" /></Button>
                    <Button size="sm" variant="ghost" onClick={() => onDelete(pool.id)} disabled={pool.assignedCount > 0 || processing} className="text-error-600 hover:bg-error-50 dark:text-error-300 dark:hover:bg-error-500/10"><Trash2 className="w-4 h-4" /></Button>
                  </div>
                </div>
              </div>
            </Card>
          );
        })}
      </div>
      {pools.length === 0 && <Card><div className="text-center py-12"><Database className="w-12 h-12 text-neutral-300 mx-auto mb-3 dark:text-neutral-600" /><p className="text-neutral-500 dark:text-neutral-400">No pools found</p></div></Card>}
    </div>
  );
};

// ============================================================================
// VIBANS TAB
// ============================================================================

const VibansTab: React.FC<{
  vibans: Viban[]; pools: VibanPool[]; searchQuery: string; setSearchQuery: (q: string) => void;
  selectedPool: string | null; setSelectedPool: (id: string | null) => void;
  statusFilter: string; setStatusFilter: (s: string) => void;
  onAssign: (viban: Viban) => void; onRelease: (viban: Viban) => void;
  getStatusBadge: (status: string) => React.ReactNode; formatCurrency: (n: number) => string; processing: boolean;
}> = ({ vibans, pools, searchQuery, setSearchQuery, selectedPool, setSelectedPool, statusFilter, setStatusFilter, onAssign, onRelease, getStatusBadge, formatCurrency, processing }) => {
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const copyToClipboard = (text: string, id: string) => { navigator.clipboard.writeText(text); setCopiedId(id); setTimeout(() => setCopiedId(null), 2000); };

  return (
    <div className="space-y-4">
      <Card className="animate-fade-in" style={{ animationDelay: '0.2s' }}>
        <div className="p-4 flex gap-4">
          <div className="flex-1 relative">
            <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-neutral-400 dark:text-neutral-500" />
            <Input className="pl-9" placeholder="Search VIBANs..." value={searchQuery} onChange={e => setSearchQuery(e.target.value)} />
          </div>
          <select value={selectedPool || ''} onChange={e => setSelectedPool(e.target.value || null)} className="px-3 py-2 border rounded-lg text-sm min-w-[180px]">
            <option value="">All Pools</option>{pools.map(p => <option key={p.id} value={p.id}>{p.poolName}</option>)}
          </select>
          <select value={statusFilter} onChange={e => setStatusFilter(e.target.value)} className="px-3 py-2 border rounded-lg text-sm">
            <option value="ALL">All Status</option>
            <option value="ACTIVE">Active (Assigned)</option>
            <option value="RETURNED">Available (In Pool)</option>
            <option value="PARTIAL">Reserved</option>
            <option value="EXPIRED">Expired</option>
          </select>
        </div>
      </Card>

      <Card padding="none" className="animate-fade-in" style={{ animationDelay: '0.25s' }}>
        <div className="overflow-x-auto">
          <table className="data-table">
            <thead className="data-table-header">
              <tr>
                <th className="data-table-header-cell">VIBAN</th>
                <th className="data-table-header-cell">Pool</th>
                <th className="data-table-header-cell">Assigned To</th>
                <th className="data-table-header-cell">Reference</th>
                <th className="data-table-header-cell">Status</th>
                <th className="data-table-header-cell text-right">Usage</th>
                <th className="data-table-header-cell text-right">Amount</th>
                <th className="data-table-header-cell text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-neutral-100 dark:divide-primary-800/60">
              {vibans.map(v => (
                <tr key={v.id} className="data-table-row group">
                  <td className="data-table-cell">
                    <div className="flex items-center gap-2">
                      <button onClick={() => copyToClipboard(v.viban, v.id)} className="p-1 hover:bg-neutral-100 rounded dark:hover:bg-primary-800">
                        {copiedId === v.id ? <Check className="w-3 h-3 text-success-600 dark:text-success-300" /> : <Copy className="w-3 h-3 text-neutral-400 dark:text-neutral-500" />}
                      </button>
                      <span className="font-mono text-sm">{v.viban}</span>
                    </div>
                  </td>
                  <td className="data-table-cell text-sm text-neutral-600 dark:text-neutral-300">{v.poolCode || '-'}</td>
                  <td className="data-table-cell">
                    {v.vaNumber ? (
                      <div className="bg-success-50 rounded-lg px-2 py-1 border border-success-100 dark:bg-success-500/10 dark:border-success-500/30">
                        <div className="flex items-center gap-1.5">
                          <Building2 className="w-3 h-3 text-success-600 dark:text-success-300" />
                          <p className="font-mono text-sm text-success-700 dark:text-success-300">{v.vaNumber}</p>
                        </div>
                        {v.vaName && <p className="text-xs text-success-600 mt-0.5 dark:text-success-300">{v.vaName}</p>}
                        {v.customerName && <p className="text-xs text-neutral-500 mt-0.5 dark:text-neutral-400">Customer: {v.customerName}</p>}
                      </div>
                    ) : (
                      <span className="text-neutral-400 text-sm italic dark:text-neutral-500">Not assigned</span>
                    )}
                  </td>
                  <td className="data-table-cell">
                    {v.referenceType ? (
                      <div className="flex items-center gap-2">
                        {v.referenceType === 'ORDER' && <ShoppingCart className="w-3 h-3 text-primary-500" />}
                        {v.referenceType === 'INVOICE' && <FileText className="w-3 h-3 text-info-500" />}
                        {v.referenceType === 'TERMINAL' && <CreditCard className="w-3 h-3 text-success-500" />}
                        <span className="text-sm">{v.referenceId}</span>
                      </div>
                    ) : <span className="text-neutral-400 dark:text-neutral-500">-</span>}
                  </td>
                  <td className="data-table-cell">{getStatusBadge(v.status)}</td>
                  <td className="data-table-cell text-right text-sm">{v.timesUsed || 0}</td>
                  <td className="data-table-cell text-right text-sm font-medium">{(v.totalAmountReceived || 0) > 0 ? formatCurrency(v.totalAmountReceived!) : '-'}</td>
                  <td className="data-table-cell text-right">
                    <div className="flex justify-end gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                      {(v.status === 'AVAILABLE' || v.status === 'RETURNED') && <Button size="sm" variant="outline" onClick={() => onAssign(v)}><Link className="w-4 h-4 mr-1" />Assign</Button>}
                      {v.status === 'ACTIVE' && <Button size="sm" variant="ghost" onClick={() => onRelease(v)} disabled={processing} className="text-warning-600 hover:bg-warning-50 dark:text-warning-300 dark:hover:bg-warning-500/10"><Unlink className="w-4 h-4" /></Button>}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {vibans.length === 0 && <div className="text-center py-12"><Hash className="w-12 h-12 text-neutral-300 mx-auto mb-3 dark:text-neutral-600" /><p className="text-neutral-500 dark:text-neutral-400">No VIBANs found</p></div>}
      </Card>
    </div>
  );
};

// ============================================================================
// POOL FORM
// ============================================================================

const PoolForm: React.FC<{
  pool: VibanPool | null; programs: Program[];
  onSubmit: (data: Partial<VibanPool>) => void; onCancel: () => void; loading: boolean;
}> = ({ pool, programs = [], onSubmit, onCancel, loading }) => {
  const [formData, setFormData] = useState({
    programId: pool?.programId || '', poolName: pool?.poolName || '', poolCode: pool?.poolCode || '',
    description: pool?.description || '', countryCode: pool?.countryCode || 'AE', bankCode: pool?.bankCode || '033',
    prefix: pool?.prefix || '', suffixLength: pool?.suffixLength || 10, poolSize: pool?.poolSize || 1000,
    assignmentTtlMinutes: pool?.assignmentTtlMinutes || 1440, autoReturnExpired: pool?.autoReturnExpired !== false,
    lowThresholdPercent: pool?.lowThresholdPercent || 20
  });

  // Ensure programs is always an array before filtering - triple safety
  const safePrograms = programs || [];
  const programsArray = Array.isArray(safePrograms) ? safePrograms : [];
  const vibanEnabledPrograms = programsArray.filter(p => p && p.vibanEnabled !== false);

  return (
    <form onSubmit={e => { e.preventDefault(); onSubmit(formData); }} className="space-y-6">
      <div className="space-y-4">
        <h4 className="font-medium text-neutral-700 flex items-center gap-2 dark:text-neutral-200"><Database className="w-4 h-4" />Pool Configuration</h4>
        <div className="grid grid-cols-2 gap-4">
          <div><label className="field-label block mb-1">Program *</label>
            <select value={formData.programId} onChange={e => setFormData(p => ({ ...p, programId: e.target.value }))} className="w-full px-3 py-2 border rounded-lg text-sm" required disabled={!!pool}>
              <option value="">Select Program</option>{vibanEnabledPrograms.map(p => <option key={p.id} value={p.id}>{p.programName} ({p.programCode})</option>)}
            </select></div>
          <div><label className="field-label block mb-1">Pool Code *</label>
            <Input value={formData.poolCode} onChange={e => setFormData(p => ({ ...p, poolCode: e.target.value.toUpperCase() }))} placeholder="ECOM-POOL" required disabled={!!pool} /></div>
        </div>
        <div><label className="field-label block mb-1">Pool Name *</label>
          <Input value={formData.poolName} onChange={e => setFormData(p => ({ ...p, poolName: e.target.value }))} placeholder="E-commerce Order VIBANs" required /></div>
        <div><label className="field-label block mb-1">Description</label>
          <textarea value={formData.description} onChange={e => setFormData(p => ({ ...p, description: e.target.value }))} placeholder="Purpose..." className="w-full px-3 py-2 border rounded-lg text-sm resize-none" rows={2} /></div>
      </div>

      <div className="space-y-4 pt-4 border-t">
        <h4 className="font-medium text-neutral-700 flex items-center gap-2 dark:text-neutral-200"><Hash className="w-4 h-4" />VIBAN Format</h4>
        <div className="grid grid-cols-4 gap-4">
          <div><label className="field-label block mb-1">Country</label><Input value={formData.countryCode} onChange={e => setFormData(p => ({ ...p, countryCode: e.target.value.toUpperCase() }))} maxLength={2} disabled={!!pool} /></div>
          <div><label className="field-label block mb-1">Bank Code</label><Input value={formData.bankCode} onChange={e => setFormData(p => ({ ...p, bankCode: e.target.value }))} disabled={!!pool} /></div>
          <div><label className="field-label block mb-1">Prefix *</label><Input value={formData.prefix} onChange={e => setFormData(p => ({ ...p, prefix: e.target.value.toUpperCase() }))} placeholder="ORD" required disabled={!!pool} /></div>
          <div><label className="field-label block mb-1">Suffix Length</label><Input type="number" value={formData.suffixLength} onChange={e => setFormData(p => ({ ...p, suffixLength: parseInt(e.target.value) }))} min={6} max={12} disabled={!!pool} /></div>
        </div>
        <div className="bg-neutral-50 rounded-lg p-3 dark:bg-primary-950"><p className="text-sm text-neutral-600 dark:text-neutral-300"><strong>Sample:</strong> <span className="font-mono">{formData.countryCode}00{formData.bankCode}{formData.prefix}{'0'.repeat(formData.suffixLength)}</span></p></div>
      </div>

      <div className="space-y-4 pt-4 border-t">
        <h4 className="font-medium text-neutral-700 flex items-center gap-2 dark:text-neutral-200"><Settings className="w-4 h-4" />Pool Settings</h4>
        <div className="grid grid-cols-3 gap-4">
          <div><label className="field-label block mb-1">Pool Size *</label><Input type="number" value={formData.poolSize} onChange={e => setFormData(p => ({ ...p, poolSize: parseInt(e.target.value) }))} min={1} max={100000} required disabled={!!pool} /><p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">VIBANs to pre-generate</p></div>
          <div><label className="field-label block mb-1">TTL (minutes)</label><Input type="number" value={formData.assignmentTtlMinutes} onChange={e => setFormData(p => ({ ...p, assignmentTtlMinutes: parseInt(e.target.value) }))} min={0} /><p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">0 = Permanent</p></div>
          <div><label className="field-label block mb-1">Low Threshold (%)</label><Input type="number" value={formData.lowThresholdPercent} onChange={e => setFormData(p => ({ ...p, lowThresholdPercent: parseInt(e.target.value) }))} min={5} max={50} /></div>
        </div>
        <div className="flex items-center gap-2"><input type="checkbox" id="autoReturn" checked={formData.autoReturnExpired} onChange={e => setFormData(p => ({ ...p, autoReturnExpired: e.target.checked }))} className="rounded" /><label htmlFor="autoReturn" className="text-sm">Auto-return expired VIBANs</label></div>
      </div>

      <div className="flex justify-end gap-2 pt-4 border-t">
        <Button type="button" variant="ghost" onClick={onCancel}>Cancel</Button>
        <Button type="submit" disabled={loading}>{loading && <Loader2 className="w-4 h-4 animate-spin mr-2" />}{pool ? 'Update' : 'Create'} Pool</Button>
      </div>
    </form>
  );
};

// ============================================================================
// GENERATE FORM
// ============================================================================

const GenerateForm: React.FC<{ pool?: VibanPool; onSubmit: (count: number) => void; onCancel: () => void; loading: boolean; }> = ({ pool, onSubmit, onCancel, loading }) => {
  const [count, setCount] = useState('100');
  return (
    <div className="space-y-4">
      {pool && <div className="bg-neutral-50 rounded-lg p-4 dark:bg-primary-950">
        <div className="flex items-center gap-3 mb-2"><Database className="w-5 h-5 text-primary-600 dark:text-primary-200" /><div><p className="font-medium">{pool.poolName}</p><p className="text-sm text-neutral-500 dark:text-neutral-400">{pool.poolCode}</p></div></div>
        <div className="grid grid-cols-3 gap-3 mt-3">
          <div className="text-center"><p className="text-lg font-bold text-info-600 dark:text-info-300">{pool.availableCount}</p><p className="text-xs text-neutral-500 dark:text-neutral-400">Available</p></div>
          <div className="text-center"><p className="text-lg font-bold">{pool.poolSize}</p><p className="text-xs text-neutral-500 dark:text-neutral-400">Pool Size</p></div>
          <div className="text-center"><p className="text-lg font-bold text-success-600 dark:text-success-300">{((pool.availableCount / pool.poolSize) * 100).toFixed(0)}%</p><p className="text-xs text-neutral-500 dark:text-neutral-400">Available</p></div>
        </div>
      </div>}
      <div><label className="field-label block mb-1">VIBANs to Generate *</label><Input type="number" value={count} onChange={e => setCount(e.target.value)} min={1} max={10000} /><p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">Max 10,000 per batch</p></div>
      {pool && <div className="bg-info-50 rounded-lg p-3 dark:bg-info-500/10"><p className="text-sm text-info-700 dark:text-info-300">New pool size: <strong>{pool.poolSize + parseInt(count || '0')}</strong></p></div>}
      <div className="flex justify-end gap-2 pt-4 border-t">
        <Button variant="ghost" onClick={onCancel}>Cancel</Button>
        <Button onClick={() => onSubmit(parseInt(count))} disabled={loading || !count || parseInt(count) < 1}>{loading && <Loader2 className="w-4 h-4 animate-spin mr-2" />}Generate</Button>
      </div>
    </div>
  );
};

// ============================================================================
// ASSIGN FORM
// ============================================================================

const AssignForm: React.FC<{
  viban: Viban | null; pools: VibanPool[]; virtualAccounts: VirtualAccount[];
  parties: Party[];
  onSubmit: (poolId: string, vaId: string, options: {
    partyId?: string; referenceType?: string; referenceId?: string;
    expectedAmount?: number; isPrimary?: boolean;
  }) => void;
  onCancel: () => void; loading: boolean;
}> = ({ viban, pools = [], virtualAccounts = [], parties = [], onSubmit, onCancel, loading }) => {
  // Ensure arrays are safe
  const safePools = pools || [];
  const poolsArray = Array.isArray(safePools) ? safePools : [];
  const safeVAs = virtualAccounts || [];
  const vasArray = Array.isArray(safeVAs) ? safeVAs : [];
  const safeParties = parties || [];
  const partiesArray = Array.isArray(safeParties) ? safeParties : [];

  const [poolId, setPoolId] = useState(viban?.poolId || (poolsArray.find(p => p && p.status === 'ACTIVE' && p.availableCount > 0)?.id || ''));
  const [vaId, setVaId] = useState('');
  const [partyId, setPartyId] = useState('');
  const [referenceType, setReferenceType] = useState('');
  const [referenceId, setReferenceId] = useState('');
  const [expectedAmount, setExpectedAmount] = useState('');
  const [isPrimary, setIsPrimary] = useState(false);

  const activePools = poolsArray.filter(p => p && p.status === 'ACTIVE' && p.availableCount > 0);
  const activeVAs = vasArray.filter(va => va && va.status === 'ACTIVE');

  // Get selected VA, pool details
  const selectedVA = activeVAs.find(va => va.id === vaId);
  const selectedPool = poolsArray.find(p => p.id === poolId);

  // Filter parties by selected VA's corporate
  const activeParties = partiesArray.filter(p =>
    p && p.status === 'ACTIVE' &&
    (!selectedVA?.corporateId || p.corporateId === selectedVA.corporateId)
  );
  const selectedParty = activeParties.find(p => p.id === partyId);

  // Reset party when VA changes (if party doesn't belong to new VA's corporate)
  useEffect(() => {
    if (partyId && selectedVA?.corporateId) {
      const currentParty = partiesArray.find(p => p.id === partyId);
      if (currentParty && currentParty.corporateId !== selectedVA.corporateId) {
        setPartyId('');
      }
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [vaId, selectedVA?.corporateId]);

  return (
    <div className="space-y-4">
      {viban ? (
        <div className="bg-neutral-50 rounded-lg p-3 dark:bg-primary-950">
          <p className="text-sm text-neutral-500 dark:text-neutral-400">VIBAN</p>
          <p className="font-mono font-medium">{viban.viban}</p>
          {viban.poolCode && <p className="text-sm text-neutral-500 mt-1 dark:text-neutral-400">Pool: {viban.poolCode}</p>}
        </div>
      ) : poolsArray.length === 1 ? (
        // Pre-selected pool (from "Assign to VA" button on pool card)
        <div className="bg-primary-50 rounded-lg p-4 border border-primary-100 dark:bg-primary-800/40 dark:border-primary-700/60">
          <div className="flex items-center gap-3 mb-3">
            <StatusIconBadge tone="primary" icon={Database} rounded="lg" className="dark:bg-primary-700" />
            <div>
              <p className="font-semibold text-primary-900 dark:text-neutral-50">{selectedPool?.poolName}</p>
              <p className="text-sm text-primary-600 dark:text-primary-200">{selectedPool?.poolCode}</p>
            </div>
          </div>
          <div className="grid grid-cols-3 gap-2 text-center">
            <div className="bg-white rounded-lg p-2 dark:bg-primary-900">
              <p className="text-lg font-bold text-info-600 dark:text-info-300">{selectedPool?.availableCount}</p>
              <p className="text-xs text-neutral-500 dark:text-neutral-400">Available</p>
            </div>
            <div className="bg-white rounded-lg p-2 dark:bg-primary-900">
              <p className="text-lg font-bold text-success-600 dark:text-success-300">{selectedPool?.assignedCount}</p>
              <p className="text-xs text-neutral-500 dark:text-neutral-400">Assigned</p>
            </div>
            <div className="bg-white rounded-lg p-2 dark:bg-primary-900">
              <p className="text-lg font-bold text-warning-600 dark:text-warning-300">{selectedPool?.reservedCount}</p>
              <p className="text-xs text-neutral-500 dark:text-neutral-400">Reserved</p>
            </div>
          </div>
        </div>
      ) : (
        <div>
          <label className="field-label block mb-1">Select Pool *</label>
          <select value={poolId} onChange={e => setPoolId(e.target.value)} className="w-full px-3 py-2 border rounded-lg text-sm" required>
            <option value="">Select a pool...</option>
            {activePools.map(p => <option key={p.id} value={p.id}>{p.poolName} ({p.availableCount} available)</option>)}
          </select>
          {selectedPool && (
            <div className="mt-2 p-2 bg-primary-50 rounded-lg text-xs dark:bg-primary-800/40">
              <div className="flex justify-between">
                <span className="text-primary-700 dark:text-neutral-200">Available: {selectedPool.availableCount}</span>
                <span className="text-success-700 dark:text-success-300">Assigned: {selectedPool.assignedCount}</span>
              </div>
            </div>
          )}
        </div>
      )}

      {/* Virtual Account Dropdown */}
      <div>
        <label className="field-label block mb-1">Virtual Account *</label>
        <select
          value={vaId}
          onChange={e => setVaId(e.target.value)}
          className="w-full px-3 py-2 border rounded-lg text-sm"
          required
        >
          <option value="">Select a virtual account...</option>
          {activeVAs.map(va => (
            <option key={va.id} value={va.id}>
              {va.vaNumber} - {va.vaName} ({va.currencyCode})
            </option>
          ))}
        </select>
        {selectedVA && (
          <div className="mt-2 p-2 bg-info-50 rounded-lg text-sm dark:bg-info-500/10">
            <div className="flex items-center justify-between">
              <span className="text-info-700 dark:text-info-300">{selectedVA.vaName}</span>
              <span className="font-mono text-xs">{selectedVA.vaNumber}</span>
            </div>
            {selectedVA.corporateName && (
              <p className="text-xs text-info-600 mt-1 dark:text-info-300">{selectedVA.corporateName}</p>
            )}
          </div>
        )}
      </div>

      {/* Party Selection (Customer/Payer from Party Master - filtered by VA's corporate) */}
      <div>
        <label className="field-label block mb-1">
          Customer/Party
          {selectedVA?.corporateName && (
            <span className="text-xs text-neutral-500 font-normal ml-2 dark:text-neutral-400">
              ({selectedVA.corporateName} parties only)
            </span>
          )}
        </label>
        <select
          value={partyId}
          onChange={e => setPartyId(e.target.value)}
          className="w-full px-3 py-2 border rounded-lg text-sm"
          disabled={!vaId}
        >
          <option value="">{vaId ? 'Select a party (optional)...' : 'Select VA first...'}</option>
          {activeParties.map(p => (
            <option key={p.id} value={p.id}>
              {p.partyCode} - {p.displayName || p.legalName} ({p.partyType})
            </option>
          ))}
        </select>
        {!vaId && (
          <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">Select a Virtual Account to see available parties</p>
        )}
        {vaId && activeParties.length === 0 && (
          <p className="text-xs text-warning-600 mt-1 dark:text-warning-300">No parties found for this corporate</p>
        )}
        {selectedParty && (
          <div className="mt-2 p-2 bg-accent-50 rounded-lg text-sm dark:bg-accent-500/10">
            <div className="flex items-center justify-between">
              <span className="text-accent-700 font-medium dark:text-accent-300">{selectedParty.displayName || selectedParty.legalName}</span>
              <Badge size="sm" variant="info">{selectedParty.partyType}</Badge>
            </div>
            {selectedParty.roles && (
              <p className="text-xs text-accent-600 mt-1 dark:text-accent-300">
                Roles: {Array.isArray(selectedParty.roles) ? selectedParty.roles.join(', ') : selectedParty.roles}
              </p>
            )}
          </div>
        )}
      </div>

      {/* Primary VIBAN Toggle */}
      <div className="bg-primary-50 rounded-lg p-3 border border-primary-100 dark:bg-primary-800/40 dark:border-primary-700/60">
        <label className="flex items-center gap-3 cursor-pointer">
          <input
            type="checkbox"
            checked={isPrimary}
            onChange={e => setIsPrimary(e.target.checked)}
            className="w-4 h-4 rounded text-primary-600 dark:text-primary-200"
          />
          <div>
            <span className="font-medium text-primary-900 dark:text-neutral-50">Primary VIBAN</span>
            <p className="text-xs text-primary-600 dark:text-primary-200">
              {isPrimary
                ? 'Permanent assignment - no expiry (for main collection VA)'
                : 'Temporary assignment - will expire based on pool TTL'}
            </p>
          </div>
        </label>
      </div>

      <div className="grid grid-cols-2 gap-4">
        <div>
          <label className="field-label block mb-1">Reference Type</label>
          <select value={referenceType} onChange={e => setReferenceType(e.target.value)} className="w-full px-3 py-2 border rounded-lg text-sm">
            <option value="">None</option>
            <option value="ORDER">Order</option>
            <option value="INVOICE">Invoice</option>
            <option value="TERMINAL">Terminal</option>
            <option value="OTHER">Other</option>
          </select>
        </div>
        <div>
          <label className="field-label block mb-1">Reference ID</label>
          <Input value={referenceId} onChange={e => setReferenceId(e.target.value)} placeholder="ORD-12345" disabled={!referenceType} />
        </div>
      </div>

      <div>
        <label className="field-label block mb-1">Expected Amount</label>
        <Input type="number" value={expectedAmount} onChange={e => setExpectedAmount(e.target.value)} placeholder="0.00" step="0.01" />
        <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">For auto-reconciliation</p>
      </div>

      <div className="flex justify-end gap-2 pt-4 border-t">
        <Button variant="ghost" onClick={onCancel}>Cancel</Button>
        <Button
          onClick={() => onSubmit(viban?.poolId || poolId, vaId, {
            partyId: partyId || undefined,
            referenceType: referenceType || undefined,
            referenceId: referenceId || undefined,
            expectedAmount: expectedAmount ? parseFloat(expectedAmount) : undefined,
            isPrimary
          })}
          disabled={loading || !vaId || (!viban && !poolId)}
        >
          {loading && <Loader2 className="w-4 h-4 animate-spin mr-2" />}
          {isPrimary ? 'Assign Primary VIBAN' : 'Assign VIBAN'}
        </Button>
      </div>
    </div>
  );
};

// ============================================================================
// BULK ASSIGN FORM - Assign multiple VIBANs to ONE Virtual Account
// ============================================================================

const BulkAssignForm: React.FC<{
  pool: VibanPool | null;
  virtualAccounts: VirtualAccount[];
  parties: Party[];
  onSubmit: (assignments: BulkVibanAssignItem[]) => void;
  onCancel: () => void;
  loading: boolean;
}> = ({ pool, virtualAccounts = [], parties = [], onSubmit, onCancel, loading }) => {
  const safeVAs = virtualAccounts || [];
  const vasArray = Array.isArray(safeVAs) ? safeVAs : [];
  const safeParties = parties || [];
  const partiesArray = Array.isArray(safeParties) ? safeParties : [];

  const [selectedVA, setSelectedVA] = useState('');
  const [vibanCount, setVibanCount] = useState(1);
  const [isPrimary, setIsPrimary] = useState(false);
  // For party-per-VIBAN assignment
  const [assignmentMode, setAssignmentMode] = useState<'simple' | 'with-parties'>('simple');
  const [partyAssignments, setPartyAssignments] = useState<{ partyId: string; referenceId?: string }[]>([]);

  const activeVAs = vasArray.filter(va => va && va.status === 'ACTIVE');
  const selectedVADetails = activeVAs.find(va => va.id === selectedVA);

  // Filter parties by selected VA's corporate
  const activeParties = partiesArray.filter(p =>
    p && p.status === 'ACTIVE' &&
    (!selectedVADetails?.corporateId || p.corporateId === selectedVADetails.corporateId)
  );

  // Limit to available VIBANs in the pool
  const maxAvailable = pool?.availableCount || 0;

  // Reset party assignments when VA changes (clear parties from different corporate)
  useEffect(() => {
    if (selectedVADetails?.corporateId && partyAssignments.length > 0) {
      const needsReset = partyAssignments.some(pa => {
        if (!pa.partyId) return false;
        const party = partiesArray.find(p => p.id === pa.partyId);
        return party && party.corporateId !== selectedVADetails.corporateId;
      });
      if (needsReset) {
        setPartyAssignments(partyAssignments.map(() => ({ partyId: '' })));
      }
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedVA, selectedVADetails?.corporateId]);

  // Update party assignments array when count changes
  const handleCountChange = (count: number) => {
    const newCount = Math.min(Math.max(1, count), maxAvailable);
    setVibanCount(newCount);
    // Resize party assignments array
    if (assignmentMode === 'with-parties') {
      const newAssignments = [...partyAssignments];
      while (newAssignments.length < newCount) {
        newAssignments.push({ partyId: '' });
      }
      setPartyAssignments(newAssignments.slice(0, newCount));
    }
  };

  const handleModeChange = (mode: 'simple' | 'with-parties') => {
    setAssignmentMode(mode);
    if (mode === 'with-parties') {
      // Initialize party assignments array
      const assignments = Array(vibanCount).fill(null).map(() => ({ partyId: '' }));
      setPartyAssignments(assignments);
    }
  };

  const handlePartyChange = (index: number, partyId: string, referenceId?: string) => {
    const newAssignments = [...partyAssignments];
    newAssignments[index] = { partyId, referenceId };
    setPartyAssignments(newAssignments);
  };

  const handleSubmit = () => {
    if (!selectedVA) return;

    const assignments: BulkVibanAssignItem[] = [];

    if (assignmentMode === 'with-parties') {
      // Each VIBAN gets its own party
      partyAssignments.forEach((pa) => {
        assignments.push({
          virtualAccountId: selectedVA,
          partyId: pa.partyId || undefined,
          referenceType: pa.referenceId ? 'CUSTOMER' : undefined,
          referenceId: pa.referenceId || undefined,
          isPrimary
        });
      });
    } else {
      // Simple mode: N VIBANs to same VA, no specific parties
      for (let i = 0; i < vibanCount; i++) {
        assignments.push({
          virtualAccountId: selectedVA,
          isPrimary
        });
      }
    }

    onSubmit(assignments);
  };

  if (!pool) {
    return <div className="text-center py-8 text-neutral-500 dark:text-neutral-400">No pool selected</div>;
  }

  return (
    <div className="space-y-4">
      {/* Pool Info */}
      <div className="bg-primary-50 rounded-lg p-4 border border-primary-100 dark:bg-primary-800/40 dark:border-primary-700/60">
        <div className="flex items-center gap-3 mb-3">
          <StatusIconBadge tone="primary" icon={Database} rounded="lg" className="dark:bg-primary-700" />
          <div>
            <p className="font-semibold text-primary-900 dark:text-neutral-50">{pool.poolName}</p>
            <p className="text-sm text-primary-600 dark:text-primary-200">{pool.poolCode}</p>
          </div>
        </div>
        <div className="grid grid-cols-3 gap-2 text-center">
          <div className="bg-white rounded-lg p-2 dark:bg-primary-900">
            <p className="text-lg font-bold text-info-600 dark:text-info-300">{pool.availableCount}</p>
            <p className="text-xs text-neutral-500 dark:text-neutral-400">Available</p>
          </div>
          <div className="bg-white rounded-lg p-2 dark:bg-primary-900">
            <p className="text-lg font-bold text-success-600 dark:text-success-300">{pool.assignedCount}</p>
            <p className="text-xs text-neutral-500 dark:text-neutral-400">Assigned</p>
          </div>
          <div className="bg-white rounded-lg p-2 dark:bg-primary-900">
            <p className="text-lg font-bold text-warning-600 dark:text-warning-300">{vibanCount}</p>
            <p className="text-xs text-neutral-500 dark:text-neutral-400">To Assign</p>
          </div>
        </div>
      </div>

      {/* Select Virtual Account */}
      <div>
        <label className="field-label block mb-1">Virtual Account *</label>
        <select
          value={selectedVA}
          onChange={e => setSelectedVA(e.target.value)}
          className="w-full px-3 py-2 border rounded-lg text-sm"
          required
        >
          <option value="">Select a virtual account...</option>
          {activeVAs.map(va => (
            <option key={va.id} value={va.id}>
              {va.vaNumber} - {va.vaName} ({va.currencyCode})
            </option>
          ))}
        </select>
        {selectedVADetails && (
          <div className="mt-2 p-2 bg-info-50 rounded-lg text-sm dark:bg-info-500/10">
            <div className="flex items-center justify-between">
              <span className="text-info-700 font-medium dark:text-info-300">{selectedVADetails.vaName}</span>
              <span className="font-mono text-xs">{selectedVADetails.vaNumber}</span>
            </div>
            {selectedVADetails.corporateName && (
              <p className="text-xs text-info-600 mt-1 dark:text-info-300">{selectedVADetails.corporateName}</p>
            )}
          </div>
        )}
      </div>

      {/* Number of VIBANs */}
      <div>
        <label className="field-label block mb-1">Number of VIBANs to Assign *</label>
        <div className="flex items-center gap-3">
          <input
            type="number"
            min={1}
            max={maxAvailable}
            value={vibanCount}
            onChange={e => handleCountChange(parseInt(e.target.value) || 1)}
            className="w-24 px-3 py-2 border rounded-lg text-sm"
          />
          <input
            type="range"
            min={1}
            max={Math.min(maxAvailable, 50)}
            value={vibanCount}
            onChange={e => handleCountChange(parseInt(e.target.value))}
            className="flex-1"
          />
          <span className="text-sm text-neutral-500 dark:text-neutral-400">Max: {maxAvailable}</span>
        </div>
      </div>

      {/* Assignment Mode */}
      <div className="bg-neutral-50 rounded-lg p-3 dark:bg-primary-950">
        <label className="field-label block mb-2">Assignment Mode</label>
        <div className="flex gap-4">
          <label className="flex items-center gap-2 cursor-pointer">
            <input
              type="radio"
              name="assignmentMode"
              checked={assignmentMode === 'simple'}
              onChange={() => handleModeChange('simple')}
              className="w-4 h-4 text-primary-600 dark:text-primary-200"
            />
            <div>
              <span className="text-sm font-medium">Simple</span>
              <p className="text-xs text-neutral-500 dark:text-neutral-400">Assign {vibanCount} VIBANs without specific parties</p>
            </div>
          </label>
          <label className="flex items-center gap-2 cursor-pointer">
            <input
              type="radio"
              name="assignmentMode"
              checked={assignmentMode === 'with-parties'}
              onChange={() => handleModeChange('with-parties')}
              className="w-4 h-4 text-primary-600 dark:text-primary-200"
            />
            <div>
              <span className="text-sm font-medium">With Parties</span>
              <p className="text-xs text-neutral-500 dark:text-neutral-400">Assign each VIBAN to a specific customer/party</p>
            </div>
          </label>
        </div>
      </div>

      {/* Party Assignments (when with-parties mode) */}
      {assignmentMode === 'with-parties' && (
        <div className="border rounded-lg max-h-48 overflow-y-auto">
          <div className="bg-neutral-100 px-3 py-2 text-sm font-medium border-b sticky top-0 flex justify-between items-center dark:bg-primary-800">
            <span>Party Assignment for Each VIBAN</span>
            {selectedVADetails?.corporateName && (
              <span className="text-xs text-neutral-500 font-normal dark:text-neutral-400">
                {selectedVADetails.corporateName} parties only
              </span>
            )}
          </div>
          {!selectedVA ? (
            <div className="p-4 text-center text-neutral-500 text-sm dark:text-neutral-400">
              Select a Virtual Account first to see available parties
            </div>
          ) : activeParties.length === 0 ? (
            <div className="p-4 text-center text-warning-600 text-sm dark:text-warning-300">
              No parties found for this corporate
            </div>
          ) : (
            partyAssignments.map((pa, idx) => (
              <div key={idx} className="flex items-center gap-2 p-2 border-b last:border-b-0">
                <span className="text-xs text-neutral-500 w-8 dark:text-neutral-400">#{idx + 1}</span>
                <select
                  value={pa.partyId}
                  onChange={e => handlePartyChange(idx, e.target.value, pa.referenceId)}
                  className="flex-1 px-2 py-1 border rounded text-sm"
                >
                  <option value="">No party</option>
                  {activeParties.map(p => (
                    <option key={p.id} value={p.id}>
                      {p.partyCode} - {p.displayName || p.legalName}
                    </option>
                  ))}
                </select>
                <input
                  type="text"
                  placeholder="Reference ID"
                  value={pa.referenceId || ''}
                  onChange={e => handlePartyChange(idx, pa.partyId, e.target.value)}
                  className="w-32 px-2 py-1 border rounded text-sm"
                />
              </div>
            ))
          )}
        </div>
      )}

      {/* Primary Toggle */}
      <div className="bg-primary-50 rounded-lg p-3 border border-primary-100 dark:bg-primary-800/40 dark:border-primary-700/60">
        <label className="flex items-center gap-3 cursor-pointer">
          <input
            type="checkbox"
            checked={isPrimary}
            onChange={e => setIsPrimary(e.target.checked)}
            className="w-4 h-4 rounded text-primary-600 dark:text-primary-200"
          />
          <div>
            <span className="font-medium text-primary-900 dark:text-neutral-50">Primary VIBANs</span>
            <p className="text-xs text-primary-600 dark:text-primary-200">
              {isPrimary
                ? 'Permanent assignment - no expiry (for main collection VA)'
                : 'Temporary assignment - will expire based on pool TTL'}
            </p>
          </div>
        </label>
      </div>

      {/* Actions */}
      <div className="flex justify-between items-center pt-4 border-t">
        <p className="text-sm text-neutral-500 dark:text-neutral-400">
          {selectedVA && vibanCount > 0
            ? `Will assign ${vibanCount} VIBAN${vibanCount > 1 ? 's' : ''} to ${selectedVADetails?.vaNumber || 'selected VA'}`
            : 'Select a Virtual Account and specify count'}
        </p>
        <div className="flex gap-2">
          <Button variant="ghost" onClick={onCancel}>Cancel</Button>
          <Button
            onClick={handleSubmit}
            disabled={loading || !selectedVA || vibanCount < 1}
          >
            {loading && <Loader2 className="w-4 h-4 animate-spin mr-2" />}
            Assign {vibanCount} VIBAN{vibanCount !== 1 ? 's' : ''}
          </Button>
        </div>
      </div>
    </div>
  );
};

// ============================================================================
// POOL DETAIL VIEW
// ============================================================================

const PoolDetailView: React.FC<{
  pool: VibanPool; vibans: Viban[]; getStatusBadge: (status: string) => React.ReactNode;
  formatTtl: (minutes: number) => string; formatNumber: (n: number) => string; formatCurrency: (n: number) => string;
}> = ({ pool, vibans, getStatusBadge, formatTtl, formatNumber, formatCurrency }) => {
  const totalPayments = vibans.reduce((sum, v) => sum + (v.timesUsed || 0), 0);
  const totalAmount = vibans.reduce((sum, v) => sum + (v.totalAmountReceived || 0), 0);
  const activeVibans = vibans.filter(v => v.status === 'ACTIVE');

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-4">
          <div className="p-3 bg-primary-100 rounded-lg dark:bg-primary-700"><Database className="w-6 h-6 text-primary-600 dark:text-primary-200" /></div>
          <div><h3 className="text-xl font-semibold">{pool.poolName}</h3><p className="text-neutral-500 dark:text-neutral-400">{pool.poolCode} • {pool.programName}</p></div>
        </div>
        {getStatusBadge(pool.status)}
      </div>
      {pool.description && <p className="text-neutral-600 dark:text-neutral-300">{pool.description}</p>}

      <div className="grid grid-cols-4 gap-4">
        <Card><div className="p-4 text-center"><p className="stat-value-sm">{formatNumber(pool.poolSize)}</p><p className="text-sm text-neutral-500 dark:text-neutral-400">Pool Size</p></div></Card>
        <Card><div className="p-4 text-center"><p className="stat-value-info">{formatNumber(pool.availableCount)}</p><p className="text-sm text-neutral-500 dark:text-neutral-400">Available</p></div></Card>
        <Card><div className="p-4 text-center"><p className="stat-value-success">{formatNumber(pool.assignedCount)}</p><p className="text-sm text-neutral-500 dark:text-neutral-400">Assigned</p></div></Card>
        <Card><div className="p-4 text-center"><p className="stat-value-warning">{formatNumber(pool.reservedCount)}</p><p className="text-sm text-neutral-500 dark:text-neutral-400">Reserved</p></div></Card>
      </div>

      <Card>
        <div className="p-4 border-b"><h4 className="font-medium">Configuration</h4></div>
        <div className="p-4 grid grid-cols-3 gap-4">
          <div><p className="text-sm text-neutral-500 dark:text-neutral-400">VIBAN Format</p><p className="font-mono">{pool.countryCode}00{pool.bankCode}{pool.prefix}{'X'.repeat(pool.suffixLength)}</p></div>
          <div><p className="text-sm text-neutral-500 dark:text-neutral-400">Assignment TTL</p><p className="font-medium">{formatTtl(pool.assignmentTtlMinutes)}</p></div>
          <div><p className="text-sm text-neutral-500 dark:text-neutral-400">Auto Return</p><p className="font-medium">{pool.autoReturnExpired ? 'Yes' : 'No'}</p></div>
          <div><p className="text-sm text-neutral-500 dark:text-neutral-400">Low Threshold</p><p className="font-medium">{pool.lowThresholdPercent}%</p></div>
          <div><p className="text-sm text-neutral-500 dark:text-neutral-400">Created</p><p className="font-medium">{new Date(pool.createdAt).toLocaleDateString()}</p></div>
          <div><p className="text-sm text-neutral-500 dark:text-neutral-400">Updated</p><p className="font-medium">{pool.updatedAt ? new Date(pool.updatedAt).toLocaleDateString() : '-'}</p></div>
        </div>
      </Card>

      <Card>
        <div className="p-4 border-b"><h4 className="font-medium">Payment Statistics</h4></div>
        <div className="p-4 grid grid-cols-2 gap-4">
          <div className="flex items-center gap-3"><div className="p-3 bg-primary-100 rounded-lg dark:bg-primary-700"><Activity className="w-5 h-5 text-primary-600 dark:text-primary-200" /></div><div><p className="stat-value-sm">{formatNumber(totalPayments)}</p><p className="text-sm text-neutral-500 dark:text-neutral-400">Total Payments</p></div></div>
          <div className="flex items-center gap-3"><div className="p-3 bg-success-100 rounded-lg dark:bg-success-500/20"><CreditCard className="w-5 h-5 text-success-600 dark:text-success-300" /></div><div><p className="stat-value-sm">{formatCurrency(totalAmount)}</p><p className="text-sm text-neutral-500 dark:text-neutral-400">Total Amount</p></div></div>
        </div>
      </Card>

      {activeVibans.length > 0 && <Card>
        <div className="p-4 border-b flex items-center justify-between">
          <h4 className="font-medium flex items-center gap-2">
            <Link className="w-4 h-4 text-success-600 dark:text-success-300" />
            Active VIBANs ({activeVibans.length})
          </h4>
          <Badge variant="success">{activeVibans.length} Assigned</Badge>
        </div>
        <div className="max-h-80 overflow-auto"><table className="w-full">
          <thead className="bg-neutral-50 sticky top-0 dark:bg-primary-950"><tr>
            <th className="text-left p-3 text-sm font-medium text-neutral-600 dark:text-neutral-300">VIBAN</th>
            <th className="text-left p-3 text-sm font-medium text-neutral-600 dark:text-neutral-300">Assigned Virtual Account</th>
            <th className="text-left p-3 text-sm font-medium text-neutral-600 dark:text-neutral-300">Reference</th>
            <th className="text-right p-3 text-sm font-medium text-neutral-600 dark:text-neutral-300">Payments</th>
            <th className="text-right p-3 text-sm font-medium text-neutral-600 dark:text-neutral-300">Amount</th>
          </tr></thead>
          <tbody className="divide-y">{activeVibans.slice(0, 20).map(v => (
            <tr key={v.id} className="hover:bg-neutral-50 dark:hover:bg-primary-800/50">
              <td className="p-3 font-mono text-sm">{v.viban}</td>
              <td className="p-3">
                {v.vaNumber ? (
                  <div className="bg-success-50 rounded-lg px-2 py-1.5 border border-success-100 inline-block dark:bg-success-500/10 dark:border-success-500/30">
                    <div className="flex items-center gap-1.5">
                      <Building2 className="w-3.5 h-3.5 text-success-600 dark:text-success-300" />
                      <span className="font-mono text-sm text-success-700 dark:text-success-300">{v.vaNumber}</span>
                    </div>
                    {v.vaName && <p className="text-xs text-success-600 mt-0.5 dark:text-success-300">{v.vaName}</p>}
                    {v.customerName && (
                      <p className="text-xs text-neutral-500 mt-0.5 flex items-center gap-1 dark:text-neutral-400">
                        <span className="text-neutral-400 dark:text-neutral-500">Customer:</span> {v.customerName}
                      </p>
                    )}
                  </div>
                ) : (
                  <span className="text-neutral-400 text-sm italic dark:text-neutral-500">Not assigned</span>
                )}
              </td>
              <td className="p-3">
                {v.referenceType ? (
                  <div className="flex items-center gap-2">
                    {v.referenceType === 'ORDER' && <ShoppingCart className="w-3.5 h-3.5 text-primary-500" />}
                    {v.referenceType === 'INVOICE' && <FileText className="w-3.5 h-3.5 text-info-500" />}
                    {v.referenceType === 'TERMINAL' && <CreditCard className="w-3.5 h-3.5 text-success-500" />}
                    <div>
                      <p className="text-sm font-medium">{v.referenceId}</p>
                      <p className="text-xs text-neutral-500 dark:text-neutral-400">{v.referenceType}</p>
                    </div>
                  </div>
                ) : <span className="text-neutral-400 text-sm dark:text-neutral-500">-</span>}
              </td>
              <td className="p-3 text-sm text-right font-medium">{v.timesUsed || 0}</td>
              <td className="p-3 text-sm text-right font-medium">
                {(v.totalAmountReceived || 0) > 0 ? formatCurrency(v.totalAmountReceived!) : '-'}
              </td>
            </tr>
          ))}</tbody>
        </table></div>
        {activeVibans.length > 20 && (
          <div className="p-3 text-center text-sm text-neutral-500 border-t bg-neutral-50 dark:text-neutral-400 dark:bg-primary-950">
            Showing 20 of {activeVibans.length} active VIBANs
          </div>
        )}
      </Card>}
    </div>
  );
};

export default VibanManagementPage;