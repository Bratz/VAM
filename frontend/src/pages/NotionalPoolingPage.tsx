import React, { useState, useEffect, useMemo } from 'react';
import {
  Layers,
  Plus,
  TrendingUp,
  Users,
  DollarSign,
  RefreshCw,
  Building2,
  PiggyBank,
  AlertCircle,
} from 'lucide-react';
import { Card, Button, Badge, Skeleton, StatusIconBadge, StatTile } from '../components/ui';
import { formatCompactCurrency } from '../utils';
import { useNotionalPooling } from '../hooks';
import { NotionalPool, corporatesApi, programsApi } from '../services/api';
import { usePageHeaderActions } from '../context/PageHeaderContext';
import { Page } from '../components/layout/Page';
import { ScopeSelector } from '../components/layout/ScopeSelector';
import toast from 'react-hot-toast';
import { CreatePoolModal } from '../components/pooling/CreatePoolModal';
import { AddMemberModal } from '../components/pooling/AddMemberModal';
import { PoolDetailModal } from '../components/pooling/PoolDetailModal';
import { PoolCard } from '../components/pooling/PoolCard';

// ============================================================================
// TYPES
// ============================================================================
interface Corporate {
  id: string;
  corporateId: string;
  legalName: string;
  tradeName?: string;
}

interface Program {
  id: string;
  programCode: string;
  programName: string;
  programType: 'COLLECTION' | 'WALLET' | 'IHB' | 'PAYABLES' | 'VIBAN' | 'ESCROW' | 'POOLING';
  corporateId?: string;
  currencyCode?: string;
}

// ============================================================================
// LOADING & ERROR COMPONENTS
// ============================================================================
const LoadingSpinner: React.FC = () => (
  <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 animate-fade-in">
    {[1, 2, 3, 4].map(i => (
      <Card key={i} padding="sm">
        <div className="flex items-center gap-3">
          <Skeleton className="w-10 h-10 rounded-xl" />
          <div className="space-y-2">
            <Skeleton className="h-6 w-16" />
            <Skeleton className="h-3 w-20" />
          </div>
        </div>
      </Card>
    ))}
  </div>
);

const ErrorMessage: React.FC<{ message: string; onRetry: () => void }> = ({ message, onRetry }) => (
  <Card padding="sm" className="bg-error-50 dark:bg-error-500/10 border-error-200 dark:border-error-500/30 animate-fade-in">
    <div className="flex items-center gap-3">
      <AlertCircle className="w-5 h-5 text-error-600 dark:text-error-300" />
      <div className="flex-1">
        <p className="font-medium text-error-800 dark:text-error-300 text-sm">Failed to load data</p>
        <p className="text-xs text-error-600 dark:text-error-300">{message}</p>
      </div>
      <Button variant="outline" size="sm" onClick={onRetry}>Retry</Button>
    </div>
  </Card>
);

// Phase 12 Task E: local StatCard clone replaced by the shared <StatTile>
// (components/ui/StatTile, layout="row") — see the metric strip below.

// Picker now uses the shared `<ScopeSelector mode="corporate-program">`
// primitive from components/layout/. The inline `SelectorBar` (Desktop +
// Mobile variants) that lived here is removed. Page-specific filter (only
// POOLING-type programs) is applied at the call site before passing the
// `programs` array into ScopeSelector.

// ============================================================================
// EMPTY STATE
// ============================================================================
const EmptyState: React.FC<{ onCreatePool: () => void }> = ({ onCreatePool }) => (
  <Card className="text-center py-12">
    <Layers className="w-12 h-12 text-neutral-300 dark:text-neutral-600 mx-auto mb-4" />
    <h3 className="text-lg font-medium text-neutral-900 dark:text-neutral-50 mb-2">No Notional Pools</h3>
    <p className="text-neutral-500 dark:text-neutral-400 mb-6 max-w-md mx-auto">
      Create your first notional pool to virtually combine account balances and optimize interest earnings.
    </p>
    <Button onClick={onCreatePool} leftIcon={<Plus className="w-4 h-4" />}>
      Create Pool
    </Button>
  </Card>
);

// ============================================================================
// MAIN PAGE
// ============================================================================
const NotionalPoolingPage: React.FC = () => {
  const {
    pools,
    loading,
    error,
    selectedPool,
    setSelectedPool,
    fetchPools,
    fetchPoolById,
    createPool,
    addMember,
    addMembersBulk,
    removeMember,
    calculateInterest
  } = useNotionalPooling();

  // Corporate & Program selector state
  const [corporates, setCorporates] = useState<Corporate[]>([]);
  const [programs, setPrograms] = useState<Program[]>([]);
  const [selectedCorporateId, setSelectedCorporateId] = useState<string>('');
  const [selectedProgramId, setSelectedProgramId] = useState<string>('');
  const [loadingSelectors, setLoadingSelectors] = useState(false);

  const [showCreateModal, setShowCreateModal] = useState(false);
  const [showDetailModal, setShowDetailModal] = useState(false);
  const [showAddMemberModal, setShowAddMemberModal] = useState(false);
  const [calculatingPoolId, setCalculatingPoolId] = useState<string | null>(null);

  // Load corporates and programs on mount
  useEffect(() => {
    const loadCorporatesAndPrograms = async () => {
      setLoadingSelectors(true);
      try {
        const [corporatesRes, programsRes] = await Promise.all([
          corporatesApi.getAll(),
          programsApi.getAll().catch(() => ({ data: { programs: [] } })),
        ]);

        const corporateData = corporatesRes?.data || corporatesRes;
        const corporateList = Array.isArray(corporateData) ? corporateData : [];
        setCorporates(corporateList);

        const programData = programsRes?.data;
        let programList: Program[] = [];
        if (programData?.programs && Array.isArray(programData.programs)) {
          programList = programData.programs;
        } else if (Array.isArray(programData)) {
          programList = programData;
        }
        setPrograms(programList);

        // Auto-select first corporate if available
        if (corporateList.length > 0) {
          setSelectedCorporateId(corporateList[0].id);
        }
      } catch (err) {
        console.error('Failed to load corporates/programs:', err);
      } finally {
        setLoadingSelectors(false);
      }
    };
    loadCorporatesAndPrograms();
  }, []);

  // Filter pools by the selected corporate. V11 gave pools a real
  // corporateId (V12 backfilled legacy pools from their members), so this
  // is a strict match now. The previous `!p.corporateId ||` guard made it
  // fail-open — every pool always passed and the picker did nothing.
  const filteredPools = useMemo(() => {
    if (!selectedCorporateId) return pools;
    return pools.filter((p) => p.corporateId === selectedCorporateId);
  }, [pools, selectedCorporateId]);

  // Recalculate stats for filtered pools
  const filteredStats = useMemo(() => ({
    totalPools: filteredPools.length,
    activePools: filteredPools.filter(p => p.status === 'ACTIVE').length,
    totalBalance: filteredPools.reduce((s, p) => s + (p.totalBalance || 0), 0),
    totalSavings: filteredPools.reduce((s, p) => s + (p.interestSavingsYtd || 0), 0),
    totalMembers: filteredPools.reduce((s, p) => s + (p.memberCount || p.members?.length || 0), 0),
  }), [filteredPools]);

  const handleViewPool = async (pool: NotionalPool) => {
    try {
      // Fetch full pool details with members
      const fullPool = await fetchPoolById(pool.id);
      setSelectedPool(fullPool);
      setShowDetailModal(true);
    } catch (err) {
      toast.error('Failed to load pool details');
    }
  };

  const handleAddMemberClick = (pool: NotionalPool) => {
    setSelectedPool(pool);
    setShowAddMemberModal(true);
  };

  const handleCalculateInterest = async (poolId: string) => {
    setCalculatingPoolId(poolId);
    try {
      await calculateInterest(poolId);
      toast.success('Interest calculated successfully');
    } catch (err: any) {
      toast.error(err.message || 'Failed to calculate interest');
    } finally {
      setCalculatingPoolId(null);
    }
  };

  // Toolbar actions in the Aperture Layout header.
  usePageHeaderActions(
    () => (
      <>
        <Button variant="outline" size="sm" leftIcon={<RefreshCw className="w-4 h-4" />} onClick={fetchPools}>
          Refresh
        </Button>
        <Button size="sm" leftIcon={<Plus className="w-4 h-4" />} onClick={() => setShowCreateModal(true)}>
          Create Pool
        </Button>
      </>
    ),
    [fetchPools]
  );

  if (loading) {
    return (
      <Page>
        <LoadingSpinner />
      </Page>
    );
  }

  if (error) {
    return (
      <Page>
        <ErrorMessage message={error} onRetry={fetchPools} />
      </Page>
    );
  }

  return (
    <Page>
      {/* Corporate & Program Selector */}
      <ScopeSelector
        mode="corporate-program"
        corporates={corporates}
        // NotionalPooling only operates on POOLING-type programs.
        programs={programs.filter(p => p.programType === 'POOLING' && (!selectedCorporateId || p.corporateId === selectedCorporateId))}
        selectedCorporateId={selectedCorporateId}
        selectedProgramId={selectedProgramId}
        onCorporateChange={(id) => { setSelectedCorporateId(id); setSelectedProgramId(''); }}
        onProgramChange={setSelectedProgramId}
        loading={loadingSelectors}
        disableChildUntilParent
      />

      {/* Quick Actions migrated to Aperture Layout header — see
          usePageHeaderActions registration. Selected-corporate badge stays as
          contextual info on the page body. */}
      {selectedCorporateId && (
        <div className="flex items-center gap-2 animate-fade-in" style={{ animationDelay: '0.1s' }}>
          <Badge variant="info" size="sm" className="px-3 py-1">
            <Building2 className="w-3 h-3 mr-1.5" />
            {corporates.find(c => c.id === selectedCorporateId)?.tradeName ||
             corporates.find(c => c.id === selectedCorporateId)?.legalName ||
             'Selected'}
          </Badge>
        </div>
      )}

      {/* Info Banner */}
      <Card padding="sm" className="bg-info-50/50 dark:bg-info-500/10 border-info-100/50 dark:border-info-500/30 animate-fade-in" style={{ animationDelay: '0.15s' }}>
        <div className="flex items-start gap-3">
          <StatusIconBadge tone="info" icon={Layers} size="sm" rounded="lg" className="flex-shrink-0" />
          <div>
            <p className="text-sm font-medium text-info-800 dark:text-info-300">Notional Balance Pooling</p>
            <p className="text-xs text-info-600 dark:text-info-300 mt-0.5">
              Virtually combines account balances to calculate interest on the aggregate position without physically moving funds.
              Add multiple accounts as pool members to maximize interest benefits.
            </p>
          </div>
        </div>
      </Card>

      {/* Stats Cards */}
      <div className="grid grid-cols-2 lg:grid-cols-5 gap-4">
        <StatTile
          layout="row"
          tone="primary"
          label="Total Pools"
          value={filteredStats.totalPools}
          icon={<Layers className="w-5 h-5" />}
          loading={loading}
          delay="0.2s"
        />
        <StatTile
          layout="row"
          tone="success"
          valueTone="neutral"
          label="Active Pools"
          value={filteredStats.activePools}
          icon={<TrendingUp className="w-5 h-5" />}
          loading={loading}
          delay="0.25s"
        />
        <StatTile
          layout="row"
          tone="info"
          valueTone="neutral"
          label="Total Pooled"
          value={formatCompactCurrency(filteredStats.totalBalance, 'AED')}
          icon={<DollarSign className="w-5 h-5" />}
          loading={loading}
          delay="0.3s"
        />
        <StatTile
          layout="row"
          tone="warning"
          valueTone="success"
          label="Total Savings"
          value={formatCompactCurrency(filteredStats.totalSavings, 'AED')}
          icon={<PiggyBank className="w-5 h-5" />}
          loading={loading}
          delay="0.35s"
        />
        <StatTile
          layout="row"
          tone="accent"
          label="Total Members"
          value={filteredStats.totalMembers}
          icon={<Users className="w-5 h-5" />}
          loading={loading}
          delay="0.4s"
        />
      </div>

      {/* Pools Grid */}
      {filteredPools.length === 0 ? (
        <EmptyState onCreatePool={() => setShowCreateModal(true)} />
      ) : (
        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3 animate-fade-in" style={{ animationDelay: '0.45s' }}>
          {filteredPools.map((pool) => (
            <PoolCard
              key={pool.id}
              pool={pool}
              onView={() => handleViewPool(pool)}
              onCalculateInterest={() => handleCalculateInterest(pool.id)}
              onAddMember={() => handleAddMemberClick(pool)}
              isCalculating={calculatingPoolId === pool.id}
            />
          ))}
        </div>
      )}

      {/* Modals */}
      <CreatePoolModal
        isOpen={showCreateModal}
        onClose={() => setShowCreateModal(false)}
        onSave={createPool}
        corporateId={selectedCorporateId}
      />

      {selectedPool && (
        <>
          <PoolDetailModal
            isOpen={showDetailModal}
            onClose={() => { setShowDetailModal(false); setSelectedPool(null); }}
            pool={selectedPool}
            onRemoveMember={removeMember}
            onCalculateInterest={calculateInterest}
            onAddMember={() => { setShowDetailModal(false); setShowAddMemberModal(true); }}
          />

          <AddMemberModal
            isOpen={showAddMemberModal}
            onClose={() => { setShowAddMemberModal(false); setSelectedPool(null); }}
            pool={selectedPool}
            onAddMember={addMember}
            onAddMembersBulk={addMembersBulk}
            corporateId={selectedCorporateId}
          />
        </>
      )}
    </Page>
  );
};

export default NotionalPoolingPage;
