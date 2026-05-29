import React, { useState, useEffect, useCallback } from 'react';
import {
  GitMerge, Plus, Calculator, CheckCircle, Clock, AlertCircle, Loader2,
  RefreshCw, DollarSign, TrendingUp,
} from 'lucide-react';
import { Card, Button, Badge, Input, Skeleton , StatusIconBadge } from '../components/ui';
import { CurrencyPicker } from '../components/ui/CurrencyPicker';
import { Modal } from '../components/ui/enhanced';
import { formatCompactCurrency, cn } from '../utils';
import { nettingApi, NettingCycle } from '../services/api';
import { useUser } from '../context/UserContext';
import { Page } from '../components/layout/Page';

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
  <Card padding="sm" className="bg-error-50 border-error-200 animate-fade-in dark:bg-error-500/10 dark:border-error-500/30">
    <div className="flex items-center gap-3">
      <AlertCircle className="w-5 h-5 text-error-600 dark:text-error-300" />
      <div className="flex-1">
        <p className="font-medium text-error-800 text-sm dark:text-error-300">Failed to load data</p>
        <p className="text-xs text-error-600 dark:text-error-300">{message}</p>
      </div>
      <Button variant="outline" size="sm" onClick={onRetry}>Retry</Button>
    </div>
  </Card>
);

// StatCard component
const StatCard: React.FC<{
  title: string;
  value: string | number;
  icon: React.ReactNode;
  iconBg: string;
  loading?: boolean;
  delay?: string;
  valueColor?: string;
}> = ({ title, value, icon, iconBg, loading, delay, valueColor = 'text-primary-900 dark:text-neutral-50' }) => (
  <Card padding="sm" className="animate-fade-in" style={delay ? { animationDelay: delay } : undefined}>
    <div className="flex items-center gap-3">
      <div className={cn('w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0', iconBg)}>
        {icon}
      </div>
      <div className="flex-1 min-w-0">
        {loading ? (
          <Skeleton className="h-7 w-20" />
        ) : (
          <p className={cn('text-xl font-semibold', valueColor)}>{value}</p>
        )}
        <p className="text-xs text-neutral-500 uppercase tracking-wider dark:text-neutral-400">{title}</p>
      </div>
    </div>
  </Card>
);

interface CreateCycleModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSave: (data: any) => Promise<void>;
}

const CreateCycleModal: React.FC<CreateCycleModalProps> = ({ isOpen, onClose, onSave }) => {
  const [saving, setSaving] = useState(false);
  const [formData, setFormData] = useState({
    cycleName: '',
    periodStart: new Date().toISOString().split('T')[0],
    periodEnd: new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString().split('T')[0],
    settlementDate: '',
    baseCurrency: 'AED',
  });

  const handleSubmit = async () => {
    setSaving(true);
    try {
      await onSave(formData);
      onClose();
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="Create Netting Cycle" size="md">
      <div className="space-y-4">
        <div>
          <label className="field-label block mb-1">Cycle Name</label>
          <Input placeholder="e.g., Q1 2024 Netting" value={formData.cycleName} onChange={(e) => setFormData({ ...formData, cycleName: e.target.value })} />
        </div>
        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className="field-label block mb-1">Period Start</label>
            <Input type="date" value={formData.periodStart} onChange={(e) => setFormData({ ...formData, periodStart: e.target.value })} />
          </div>
          <div>
            <label className="field-label block mb-1">Period End</label>
            <Input type="date" value={formData.periodEnd} onChange={(e) => setFormData({ ...formData, periodEnd: e.target.value })} />
          </div>
        </div>
        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className="field-label block mb-1">Settlement Date</label>
            <Input type="date" value={formData.settlementDate} onChange={(e) => setFormData({ ...formData, settlementDate: e.target.value })} />
          </div>
          <div>
            <label className="field-label block mb-1">Base Currency</label>
            <CurrencyPicker value={formData.baseCurrency} onChange={(c) => setFormData({ ...formData, baseCurrency: c })} />
          </div>
        </div>
      </div>
      <div className="flex justify-end gap-2 mt-6 pt-4 border-t">
        <Button variant="ghost" onClick={onClose}>Cancel</Button>
        <Button onClick={handleSubmit} disabled={saving || !formData.cycleName}>
          {saving && <Loader2 className="w-4 h-4 animate-spin mr-2" />}Create Cycle
        </Button>
      </div>
    </Modal>
  );
};

interface CycleCardProps {
  cycle: NettingCycle;
  onCalculate: () => void;
  onApprove: () => void;
  onSettle: () => void;
  isProcessing: boolean;
}

const CycleCard: React.FC<CycleCardProps> = ({ cycle, onCalculate, onApprove, onSettle, isProcessing }) => {
  const statusColors: Record<string, string> = {
    DRAFT: 'bg-neutral-100 text-neutral-700 dark:bg-primary-800 dark:text-neutral-200', OPEN: 'bg-info-100 text-info-700 dark:bg-info-500/20 dark:text-info-300',
    CALCULATING: 'bg-warning-100 text-warning-700 dark:bg-warning-500/20 dark:text-warning-300', PENDING_APPROVAL: 'bg-warning-100 text-warning-700 dark:bg-warning-500/20 dark:text-warning-300',
    APPROVED: 'bg-success-100 text-success-700 dark:bg-success-500/20 dark:text-success-300', SETTLED: 'bg-success-100 text-success-700 dark:bg-success-500/20 dark:text-success-300',
  };

  return (
    <Card className="hover:shadow-md hover:border-primary-200 transition-all group">
      {/* Header */}
      <div className="flex items-start justify-between mb-4">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-primary-500 to-primary-700 flex items-center justify-center">
            <GitMerge className="w-5 h-5 text-white" />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h3 className="font-medium text-primary-900 dark:text-neutral-50">{cycle.cycleName}</h3>
              <span className={cn('px-2 py-0.5 text-xs font-medium rounded-full', statusColors[cycle.status] || statusColors.DRAFT)}>
                {cycle.status?.replace('_', ' ')}
              </span>
            </div>
            <p className="text-xs text-neutral-500 font-mono dark:text-neutral-400">{cycle.cycleReference}</p>
          </div>
        </div>
      </div>

      {/* Stats Grid */}
      <div className="grid grid-cols-2 gap-3 mb-4">
        <div className="bg-neutral-50 rounded-xl p-3 dark:bg-primary-950">
          <p className="text-xs text-neutral-500 uppercase tracking-wider dark:text-neutral-400">Gross</p>
          <p className="text-lg font-semibold text-primary-900 mt-0.5 dark:text-neutral-50">{formatCompactCurrency(cycle.totalGross || 0, cycle.baseCurrency)}</p>
        </div>
        <div className="bg-success-50 rounded-xl p-3 dark:bg-success-500/10">
          <p className="text-xs text-neutral-500 uppercase tracking-wider dark:text-neutral-400">Net</p>
          <p className="text-lg font-semibold text-success-700 mt-0.5 dark:text-success-300">{formatCompactCurrency(cycle.totalNet || 0, cycle.baseCurrency)}</p>
        </div>
      </div>

      {/* Savings Banner */}
      {cycle.savingsAmount > 0 && (
        <div className="bg-gradient-to-r from-warning-50 to-warning-100/50 rounded-xl p-3 mb-4 flex justify-between items-center">
          <span className="text-sm text-warning-800 font-medium dark:text-warning-300">Savings</span>
          <span className="font-semibold text-warning-700 dark:text-warning-300">{formatCompactCurrency(cycle.savingsAmount, cycle.baseCurrency)} ({cycle.savingsPercent?.toFixed(1)}%)</span>
        </div>
      )}

      {/* Info Row */}
      <div className="grid grid-cols-3 gap-2 text-sm mb-4">
        <div>
          <p className="text-xs text-neutral-400 dark:text-neutral-500">Entries</p>
          <p className="font-medium text-neutral-700 dark:text-neutral-200">{cycle.entryCount || 0}</p>
        </div>
        <div>
          <p className="text-xs text-neutral-400 dark:text-neutral-500">Participants</p>
          <p className="font-medium text-neutral-700 dark:text-neutral-200">{cycle.participantCount || 0}</p>
        </div>
        <div>
          <p className="text-xs text-neutral-400 dark:text-neutral-500">Period</p>
          <p className="font-medium text-neutral-700 text-xs dark:text-neutral-200">{cycle.periodStart?.slice(0, 10)}</p>
        </div>
      </div>

      {/* Actions */}
      <div className="flex gap-2 pt-3 border-t border-neutral-100 dark:border-primary-800/60">
        {cycle.status === 'OPEN' && (
          <Button size="sm" variant="outline" onClick={onCalculate} disabled={isProcessing} className="flex-1">
            {isProcessing ? <Loader2 className="w-4 h-4 animate-spin" /> : <><Calculator className="w-4 h-4 mr-1" />Calculate</>}
          </Button>
        )}
        {cycle.status === 'PENDING_APPROVAL' && (
          <Button size="sm" onClick={onApprove} disabled={isProcessing} className="flex-1">
            {isProcessing ? <Loader2 className="w-4 h-4 animate-spin" /> : <><CheckCircle className="w-4 h-4 mr-1" />Approve</>}
          </Button>
        )}
        {cycle.status === 'APPROVED' && (
          <Button size="sm" onClick={onSettle} disabled={isProcessing} className="flex-1">
            {isProcessing ? <Loader2 className="w-4 h-4 animate-spin" /> : <><DollarSign className="w-4 h-4 mr-1" />Settle</>}
          </Button>
        )}
        {cycle.status === 'SETTLED' && (
          <p className="text-xs text-success-600 flex items-center gap-1 py-2 dark:text-success-300"><CheckCircle className="w-3 h-3" />Settled</p>
        )}
        {cycle.status === 'DRAFT' && (
          <p className="text-xs text-neutral-500 py-2 dark:text-neutral-400">Draft - awaiting entries</p>
        )}
      </div>
    </Card>
  );
};

const NettingCyclesPage: React.FC = () => {
  const { currentEntity } = useUser();
  const approverName = currentEntity?.entityCode || currentEntity?.entityName || 'System';

  const [cycles, setCycles] = useState<NettingCycle[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [processingCycleId, setProcessingCycleId] = useState<string | null>(null);

  const fetchCycles = useCallback(async () => {
    try {
      setLoading(true);
      const response = await nettingApi.getAllCycles();
      if (response.success) setCycles(response.data);
    } catch (err: any) {
      setError(err.message || 'Failed to load cycles');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { fetchCycles(); }, [fetchCycles]);

  const handleCreate = async (data: any) => {
    const response = await nettingApi.createCycle(data);
    if (response.success) setCycles(prev => [...prev, response.data]);
  };

  const handleCalculate = async (cycleId: string) => {
    setProcessingCycleId(cycleId);
    try { await nettingApi.calculateNetting(cycleId); await fetchCycles(); } finally { setProcessingCycleId(null); }
  };

  const handleApprove = async (cycleId: string) => {
    setProcessingCycleId(cycleId);
    try { await nettingApi.approveCycle(cycleId, approverName); await fetchCycles(); } finally { setProcessingCycleId(null); }
  };

  const handleSettle = async (cycleId: string) => {
    setProcessingCycleId(cycleId);
    try { await nettingApi.settleCycle(cycleId); await fetchCycles(); } finally { setProcessingCycleId(null); }
  };

  const stats = {
    total: cycles.length,
    pending: cycles.filter(c => c.status === 'PENDING_APPROVAL').length,
    settled: cycles.filter(c => c.status === 'SETTLED').length,
    totalSavings: cycles.filter(c => c.status === 'SETTLED').reduce((sum, c) => sum + (c.savingsAmount || 0), 0),
  };

  if (loading) return <Page><LoadingSpinner /></Page>;
  if (error) return <Page><ErrorMessage message={error} onRetry={fetchCycles} /></Page>;

  return (
    <Page>
      {/* Quick Actions */}
      <div className="flex items-center justify-end gap-2 animate-fade-in" style={{ animationDelay: '0.05s' }}>
        <Button variant="outline" size="sm" leftIcon={<RefreshCw className="w-4 h-4" />} onClick={fetchCycles}>
          <span className="hidden sm:inline">Refresh</span>
        </Button>
        <Button size="sm" leftIcon={<Plus className="w-4 h-4" />} onClick={() => setShowCreateModal(true)}>
          New Cycle
        </Button>
      </div>

      {/* Info Banner */}
      <Card padding="sm" className="bg-gradient-to-r from-info-50/50 via-white to-info-50/50 border-info-100/50 animate-fade-in dark:from-primary-900 dark:via-primary-900 dark:to-primary-900" style={{ animationDelay: '0.1s' }}>
        <div className="flex items-start gap-3">
          <StatusIconBadge tone="info" icon={GitMerge} size="sm" rounded="lg" className="flex-shrink-0 dark:bg-info-500/20" />
          <div>
            <p className="text-sm font-medium text-info-800 dark:text-info-300">Multilateral Netting</p>
            <p className="text-xs text-info-600 mt-0.5 dark:text-info-300">
              Consolidates multiple intercompany payables and receivables into single net positions.
            </p>
          </div>
        </div>
      </Card>

      {/* Stats Cards */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <StatCard
          title="Total Cycles"
          value={stats.total}
          icon={<GitMerge className="w-5 h-5 text-primary-600 dark:text-primary-200" />}
          iconBg="bg-primary-100 dark:bg-primary-700"
          loading={loading}
          delay="0.15s"
        />
        <StatCard
          title="Pending"
          value={stats.pending}
          icon={<Clock className="w-5 h-5 text-warning-600 dark:text-warning-300" />}
          iconBg="bg-warning-100 dark:bg-warning-500/20"
          loading={loading}
          delay="0.2s"
        />
        <StatCard
          title="Settled"
          value={stats.settled}
          icon={<CheckCircle className="w-5 h-5 text-success-600 dark:text-success-300" />}
          iconBg="bg-success-100 dark:bg-success-500/20"
          loading={loading}
          delay="0.25s"
        />
        <StatCard
          title="Total Savings"
          value={formatCompactCurrency(stats.totalSavings, 'AED')}
          icon={<TrendingUp className="w-5 h-5 text-info-600 dark:text-info-300" />}
          iconBg="bg-info-100 dark:bg-info-500/20"
          loading={loading}
          delay="0.3s"
          valueColor="text-success-600 dark:text-success-300"
        />
      </div>

      {/* Cycles Grid */}
      {cycles.length === 0 ? (
        <Card className="text-center py-12 animate-fade-in" style={{ animationDelay: '0.35s' }}>
          <div className="w-16 h-16 rounded-2xl bg-neutral-100 flex items-center justify-center mx-auto mb-4 dark:bg-primary-800">
            <GitMerge className="w-8 h-8 text-neutral-400 dark:text-neutral-500" />
          </div>
          <h3 className="text-lg font-medium text-neutral-900 mb-2 dark:text-neutral-50">No Netting Cycles</h3>
          <p className="text-sm text-neutral-500 mb-6 dark:text-neutral-400">Create your first netting cycle to consolidate intercompany settlements</p>
          <Button onClick={() => setShowCreateModal(true)} leftIcon={<Plus className="w-4 h-4" />}>
            Create Cycle
          </Button>
        </Card>
      ) : (
        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3 animate-fade-in" style={{ animationDelay: '0.35s' }}>
          {cycles.map(cycle => (
            <CycleCard
              key={cycle.id}
              cycle={cycle}
              onCalculate={() => handleCalculate(cycle.id)}
              onApprove={() => handleApprove(cycle.id)}
              onSettle={() => handleSettle(cycle.id)}
              isProcessing={processingCycleId === cycle.id}
            />
          ))}
        </div>
      )}

      <CreateCycleModal isOpen={showCreateModal} onClose={() => setShowCreateModal(false)} onSave={handleCreate} />
    </Page>
  );
};

export default NettingCyclesPage;
