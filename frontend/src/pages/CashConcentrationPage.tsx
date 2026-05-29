import React, { useState, useEffect, useCallback } from 'react';
import {
  Play,
  Loader2,
  Plus,
  RefreshCw,
  Layers,
  TrendingUp,
  Building2,
  AlertCircle,
  Filter,
} from 'lucide-react';
import { Card, Button, Select, StatusIconBadge } from '../components/ui';
import { HeroMetricCard } from '../components/ui/HeroMetricCard';
import { formatCompactAmount, cn } from '../utils';
import { useSweeping } from '../hooks';
import { SweepRule, SweepExecution, corporatesApi, programsApi, Corporate, Program } from '../services/api';
import { usePageHeaderActions } from '../context/PageHeaderContext';
import { Page } from '../components/layout/Page';
import { StatStrip } from '../components/layout/StatStrip';
import { ScopeSelector } from '../components/layout/ScopeSelector';
import { CreateRuleModal } from '../components/concentration/CreateRuleModal';
import { EditRuleModal } from '../components/concentration/EditRuleModal';
import { ViewRuleModal } from '../components/concentration/ViewRuleModal';
import { RunSweepsModal } from '../components/concentration/RunSweepsModal';
import { RuleCard } from '../components/concentration/RuleCard';
import { ExecutionHistory } from '../components/concentration/ExecutionHistory';
import { ViewExecutionModal } from '../components/concentration/ViewExecutionModal';

// ============================================================================
// LOADING COMPONENT
// ============================================================================
const LoadingSpinner: React.FC = () => (
  <div className="flex items-center justify-center py-12">
    <Loader2 className="w-6 h-6 animate-spin text-primary-600 dark:text-primary-200" />
  </div>
);

// ============================================================================
// ERROR COMPONENT
// ============================================================================
const ErrorMessage: React.FC<{ message: string; onRetry: () => void }> = ({ message, onRetry }) => (
  <Card className="bg-error-50 dark:bg-error-500/10 border-error-200 dark:border-error-500/30 animate-fade-in" style={{ animationDelay: '0.1s' }}>
    <div className="flex items-center gap-4 p-4">
      <StatusIconBadge tone="error" icon={AlertCircle} />
      <div className="flex-1">
        <p className="font-medium text-error-800 dark:text-error-300">Failed to load data</p>
        <p className="text-sm text-error-600 dark:text-error-300">{message}</p>
      </div>
      <Button variant="outline" size="sm" onClick={onRetry}>
        Retry
      </Button>
    </div>
  </Card>
);

// ============================================================================
// EMPTY STATE
// ============================================================================
const EmptyState: React.FC<{ onCreateRule: () => void }> = ({ onCreateRule }) => (
  <Card className="animate-fade-in" style={{ animationDelay: '0.2s' }}>
    <div className="flex flex-col items-center justify-center py-16">
      <div className="w-12 h-12 rounded-xl bg-neutral-100 dark:bg-primary-800 flex items-center justify-center mb-4">
        <Layers className="w-6 h-6 text-neutral-400 dark:text-neutral-500" />
      </div>
      <p className="text-sm font-medium text-neutral-900 dark:text-neutral-50 mb-1">No Sweep Rules</p>
      <p className="text-xs text-neutral-500 dark:text-neutral-400 mb-6">Get started by creating your first sweep rule</p>
      <Button onClick={onCreateRule} leftIcon={<Plus className="w-4 h-4" />}>
        Create Sweep Rule
      </Button>
    </div>
  </Card>
);

// ============================================================================
// MAIN PAGE
// ============================================================================
const CashConcentrationPage: React.FC = () => {
  // Use the API hook
  const {
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
  } = useSweeping();

  // UI State
  const [activeTab, setActiveTab] = useState<'rules' | 'history'>('rules');
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [showRunModal, setShowRunModal] = useState(false);
  const [showEditModal, setShowEditModal] = useState(false);
  const [showViewModal, setShowViewModal] = useState(false);
  const [selectedRule, setSelectedRule] = useState<SweepRule | null>(null);
  const [showViewExecutionModal, setShowViewExecutionModal] = useState(false);
  const [selectedExecution, setSelectedExecution] = useState<SweepExecution | null>(null);

  // Filter State
  const [selectedCorporate, setSelectedCorporate] = useState('all');
  const [selectedProgram, setSelectedProgram] = useState('all');
  const [historyRuleFilter, setHistoryRuleFilter] = useState('all');

  // Corporates & Programs from API
  const [corporates, setCorporates] = useState<Corporate[]>([]);
  const [programs, setPrograms] = useState<Program[]>([]);
  const [loadingCorporates, setLoadingCorporates] = useState(false);
  const [loadingPrograms, setLoadingPrograms] = useState(false);
  const [refreshing, setRefreshing] = useState(false);

  // Fetch corporates from API
  const fetchCorporates = useCallback(async () => {
    setLoadingCorporates(true);
    try {
      const response = await corporatesApi.getAll(0, 100);
      if (response?.success && response?.data) {
        setCorporates(Array.isArray(response.data) ? response.data : []);
      } else if (Array.isArray(response)) {
        setCorporates(response);
      }
    } catch (err) {
      console.error('Failed to fetch corporates:', err);
    } finally {
      setLoadingCorporates(false);
    }
  }, []);

  // Fetch programs from API based on selected corporate
  const fetchPrograms = useCallback(async (corporateId?: string) => {
    setLoadingPrograms(true);
    try {
      const params = corporateId && corporateId !== 'all' ? { corporateId } : {};
      const response = await programsApi.getAll(params);
      if (response?.success && response?.data) {
        const programData = response.data.content || response.data;
        setPrograms(Array.isArray(programData) ? programData : []);
      } else if (Array.isArray(response)) {
        setPrograms(response);
      }
    } catch (err) {
      console.error('Failed to fetch programs:', err);
    } finally {
      setLoadingPrograms(false);
    }
  }, []);

  // Load corporates on mount
  useEffect(() => {
    fetchCorporates();
    fetchPrograms();
  }, [fetchCorporates, fetchPrograms]);

  // Reload programs when corporate changes
  useEffect(() => {
    fetchPrograms(selectedCorporate !== 'all' ? selectedCorporate : undefined);
    setSelectedProgram('all'); // Reset program when corporate changes
  }, [selectedCorporate, fetchPrograms]);

  // Handle refresh
  const handleRefresh = async () => {
    setRefreshing(true);
    try {
      await Promise.all([fetchRules(), fetchHistory(), fetchCorporates(), fetchPrograms()]);
    } finally {
      setRefreshing(false);
    }
  };

  // Toolbar actions in the Aperture Layout header — replaces the in-page row.
  usePageHeaderActions(
    () => (
      <>
        <Button variant="ghost" size="sm" leftIcon={<RefreshCw className={cn('w-4 h-4', refreshing && 'animate-spin')} />} onClick={handleRefresh} disabled={refreshing}>
          Refresh
        </Button>
        <Button variant="outline" size="sm" leftIcon={<Play className="w-4 h-4" />} onClick={() => setShowRunModal(true)}>
          Run Sweeps
        </Button>
        <Button size="sm" leftIcon={<Plus className="w-4 h-4" />} onClick={() => setShowCreateModal(true)}>
          Create Rule
        </Button>
      </>
    ),
    [refreshing]
  );

  const handleToggle = async (rule: SweepRule) => {
    try {
      await toggleRule(rule.id);
    } catch (err) {
      console.error('Failed to toggle rule:', err);
    }
  };

  const handleCreate = async (newRule: any) => {
    await createRule(newRule);
  };

  const handleEdit = async (id: string, data: any) => {
    try {
      await updateRule(id, data);
    } catch (err) {
      console.error('Failed to update rule:', err);
    }
  };

  const handleDelete = async (rule: SweepRule) => {
    if (window.confirm(`Delete rule "${rule.ruleName}"?`)) {
      try {
        await deleteRule(rule.id);
      } catch (err) {
        console.error('Failed to delete rule:', err);
      }
    }
  };

  const handleRunSweeps = async (ruleIds?: string[]) => {
    return await runSweeps(ruleIds);
  };

  // Filter rules by corporate/program
  const filteredRules = rules.filter(rule => {
    if (selectedCorporate !== 'all' && rule.corporateId !== selectedCorporate) {
      return false;
    }
    if (selectedProgram !== 'all' && rule.programId !== selectedProgram) {
      return false;
    }
    return true;
  });

  // Filter executions by rule
  const filteredExecutions = executions.filter(exec => {
    if (historyRuleFilter !== 'all' && (exec as any).ruleId !== historyRuleFilter) {
      return false;
    }
    return true;
  });

  // Calculate today's swept amount
  const today = new Date().toDateString();
  const todaySwept = executions
    .filter(exec => {
      const execDate = (exec as any).executionTime || (exec as any).executedAt;
      return execDate && new Date(execDate).toDateString() === today && exec.status === 'SUCCESS';
    })
    .reduce((sum, exec) => sum + ((exec as any).sweepAmount || (exec as any).amountSwept || 0), 0);

  const tabs = [
    { id: 'rules', label: 'Sweep Rules', count: filteredRules.length },
    { id: 'history', label: 'Execution History', count: filteredExecutions.length },
  ];

  // Loading state
  if (loading) {
    return (
      <Page>
        <LoadingSpinner />
      </Page>
    );
  }

  // Error state
  if (error) {
    return (
      <Page>
        <ErrorMessage message={error} onRetry={fetchRules} />
      </Page>
    );
  }

  return (
    <Page>
      {/* Quick Actions migrated to Layout header — see usePageHeaderActions above. */}

      {/* Corporate & Program — shared `<ScopeSelector>` primitive. The
          CashConcentration page uses `'all'` as the "all corporates" sentinel
          internally (legacy), while ScopeSelector uses `''`. Adapt at the
          interface boundary so the rest of the page logic stays unchanged. */}
      <ScopeSelector
        mode="corporate-program"
        corporates={corporates}
        programs={programs}
        selectedCorporateId={selectedCorporate === 'all' ? '' : selectedCorporate}
        selectedProgramId={selectedProgram === 'all' ? '' : selectedProgram}
        onCorporateChange={(id) => setSelectedCorporate(id || 'all')}
        onProgramChange={(id) => setSelectedProgram(id || 'all')}
        loading={loadingCorporates || loadingPrograms}
      />

      {/* Headline figures — Total Swept (cumulative concentration impact)
          alongside Today's Swept (the day's run). Treasurer's first read.
          Tier 6 Design System Unification (2026-05-13): replaces a 4-up
          equal-weight stat strip that gave no visual hierarchy. Active
          Rules + Linked Accounts demoted to the operational strip below. */}
      <HeroMetricCard
        primary={{
          label: 'Total Swept',
          value: formatCompactAmount(stats.totalSwept),
          sub: 'Cumulative funds concentrated across all rules',
        }}
        secondary={{
          label: "Today's Swept",
          value: formatCompactAmount(todaySwept),
          sub: 'Net concentration today',
        }}
        icon={<TrendingUp className="w-7 h-7 text-accent-600 dark:text-accent-300" />}
      />

      {/* Operational metrics — secondary strip below the hero. */}
      <StatStrip className="animate-fade-in [animation-delay:0.18s]">
        <Card hover>
          <div className="p-4 flex items-center gap-4">
            <StatusIconBadge tone="primary" icon={Layers} />
            <div>
              <p className="stat-value-sm">{stats.activeRules}</p>
              <p className="text-xs text-neutral-500 dark:text-neutral-400 uppercase tracking-wider">Active Rules</p>
            </div>
          </div>
        </Card>
        <Card hover>
          <div className="p-4 flex items-center gap-4">
            <StatusIconBadge tone="success" icon={Building2} />
            <div>
              <p className="stat-value-sm">{stats.totalAccounts}</p>
              <p className="text-xs text-neutral-500 dark:text-neutral-400 uppercase tracking-wider">Linked Accounts</p>
            </div>
          </div>
        </Card>
      </StatStrip>

      {/* Info Banner */}
      <Card padding="sm" className="bg-info-50/50 dark:bg-info-500/10 border-info-200/60 dark:border-info-500/30 animate-fade-in" style={{ animationDelay: '0.2s' }}>
        <div className="flex items-start gap-3 p-4">
          <StatusIconBadge tone="info" icon={Building2} className="flex-shrink-0" />
          <div>
            <p className="text-sm font-medium text-info-800 dark:text-info-300">Automated Cash Sweeping</p>
            <p className="text-sm text-info-700 dark:text-info-300 mt-1">
              Cash concentration automatically transfers funds from subsidiary accounts to a central treasury account based on predefined rules.
              Configure sweep types, thresholds, and frequencies to optimize your liquidity management.
            </p>
          </div>
        </div>
      </Card>

      {/* Tabs with Filter */}
      <div className="flex items-center justify-between border-b border-neutral-200 dark:border-primary-800 animate-fade-in" style={{ animationDelay: '0.25s' }}>
        <div className="flex gap-2">
          {tabs.map((tab) => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id as 'rules' | 'history')}
              className={cn(
                'px-4 py-2 text-sm font-medium transition-colors',
                activeTab === tab.id
                  ? 'border-b-2 border-primary-500 text-primary-600 dark:text-primary-200'
                  : 'text-neutral-500 dark:text-neutral-400 hover:text-neutral-700 dark:hover:text-neutral-200'
              )}
            >
              {tab.label} ({tab.count})
            </button>
          ))}
        </div>

        {/* History Rule Filter */}
        {activeTab === 'history' && rules.length > 0 && (
          <div className="flex items-center gap-2 pb-2">
            <Filter className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
            <div className="w-52">
              <Select
                selectSize="sm"
                value={historyRuleFilter}
                onChange={(e) => setHistoryRuleFilter(e.target.value)}
                options={[
                  { value: 'all', label: 'All Rules' },
                  ...rules.map((rule) => ({ value: rule.id, label: rule.ruleName })),
                ]}
              />
            </div>
          </div>
        )}
      </div>

      {/* Content */}
      {activeTab === 'rules' && (
        filteredRules.length === 0 ? (
          <EmptyState onCreateRule={() => setShowCreateModal(true)} />
        ) : (
          <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3 animate-fade-in" style={{ animationDelay: '0.3s' }}>
            {filteredRules.map((rule) => (
              <RuleCard
                key={rule.id}
                rule={rule}
                onToggle={() => handleToggle(rule)}
                onDelete={() => handleDelete(rule)}
                onView={() => { setSelectedRule(rule); setShowViewModal(true); }}
                onEdit={() => { setSelectedRule(rule); setShowEditModal(true); }}
              />
            ))}
          </div>
        )
      )}

      {activeTab === 'history' && (
        <ExecutionHistory
          executions={filteredExecutions}
          onViewExecution={(exec) => { setSelectedExecution(exec); setShowViewExecutionModal(true); }}
        />
      )}

      {/* Modals */}
      <CreateRuleModal
        isOpen={showCreateModal}
        onClose={() => setShowCreateModal(false)}
        onSave={handleCreate}
      />

      <EditRuleModal
        isOpen={showEditModal}
        onClose={() => { setShowEditModal(false); setSelectedRule(null); }}
        rule={selectedRule}
        onSave={handleEdit}
      />

      <ViewRuleModal
        isOpen={showViewModal}
        onClose={() => { setShowViewModal(false); setSelectedRule(null); }}
        rule={selectedRule}
      />

      <RunSweepsModal
        isOpen={showRunModal}
        onClose={() => setShowRunModal(false)}
        rules={rules}
        onRun={handleRunSweeps}
      />

      <ViewExecutionModal
        isOpen={showViewExecutionModal}
        onClose={() => { setShowViewExecutionModal(false); setSelectedExecution(null); }}
        execution={selectedExecution}
      />
    </Page>
  );
};

export default CashConcentrationPage;
