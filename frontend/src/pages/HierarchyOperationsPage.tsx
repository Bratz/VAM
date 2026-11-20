// ============================================================================
// CORPORATE REORGANIZATION PAGE - M&A, Mergers & Divestitures
// ============================================================================
// Manage corporate restructuring: acquisitions, mergers, divestitures, and
// account relocations within the corporate hierarchy.
// ============================================================================

import React, { useState, useEffect, useCallback } from 'react';
import { Package, Folder, Building2, GitMerge, GitBranch, Check, Clock, Loader2, RefreshCw, Eye, History, BookOpen, X, CheckCircle, XCircle, Settings } from 'lucide-react';
import { Button as SharedButton, StatusIconBadge } from '../components/ui';
import { usePageHeaderActions } from '../context/usePageHeader';
import { useNavigation } from '../hooks/useNavigation';
import { cn } from '../utils';
import {
  hierarchyOperationsApi,
  OperationHistoryEntry,
  OperationRules,
  MoveOperationResult,
} from '../services/hierarchyOperationsApi';
import axios from 'axios';

// ============================================================================
// MODAL IMPORTS - ALL M&A MODALS
// ============================================================================
import { MoveVaModal, MoveType } from './MoveVaModal';
import { DivestitureModal } from './DivestitureModal';
import { Modal as SharedModal } from '../components/ui/enhanced';
import { Page } from '../components/layout/Page';
import { PageHeader } from '../components/layout/PageHeader';
import { ScopeSelector } from '../components/layout/ScopeSelector';
import { MergerWizard } from './MergerWizard';
import { AcquisitionWizard } from './AcquisitionWizard';

// ============================================================================
// CORPORATE/PROGRAM TYPE DEFINITIONS
// ============================================================================

interface Corporate {
  id: string;
  corporateId: string;
  legalName: string;
  tradeName?: string;
  shortName?: string;
  status: string;
}

// ============================================================================
// API CLIENT & FUNCTIONS
// ============================================================================

const API_BASE_URL = (import.meta as any).env?.VITE_API_BASE_URL || 'http://localhost:8053/api/v1';
const apiClient = axios.create({
  baseURL: API_BASE_URL,
  headers: { 'Content-Type': 'application/json' },
  timeout: 30000,
});

const corporatesApi = {
  getAll: async (): Promise<Corporate[]> => {
    try {
      const response = await apiClient.get('/corporates');
      return response.data.data || [];
    } catch { 
      return []; 
    }
  },
};

// ============================================================================
// HELPER COMPONENTS - Use shared UI where possible, local for specialized needs
// ============================================================================

// Local Badge with additional variants (extends shared Badge)
const Badge: React.FC<{
  variant?: 'default' | 'success' | 'warning' | 'error' | 'info';
  children: React.ReactNode;
  className?: string;
}> = ({ variant = 'default', children, className }) => {
  const variants = {
    default: 'bg-surface-muted text-neutral-700 dark:text-neutral-200',
    success: 'bg-success-100 text-success-700 dark:bg-success-500/20 dark:text-success-300',
    warning: 'bg-warning-100 text-warning-700 dark:bg-warning-500/20 dark:text-warning-300',
    error: 'bg-error-100 text-error-700 dark:bg-error-500/20 dark:text-error-300',
    info: 'bg-info-100 text-info-700 dark:bg-info-500/20 dark:text-info-300',
  };

  return (
    <span className={cn('px-2 py-0.5 text-caption font-medium rounded-full', variants[variant], className)}>
      {children}
    </span>
  );
};

// Local Card with padding options (extends shared Card)
const Card: React.FC<{
  children: React.ReactNode;
  className?: string;
  padding?: 'none' | 'sm' | 'md' | 'lg';
}> = ({ children, className, padding = 'md' }) => {
  const paddings = { none: '', sm: 'p-4', md: 'p-6', lg: 'p-8' };
  return (
    <div className={cn('bg-surface-card rounded-lg shadow-sm border border-edge', paddings[padding], className)}>
      {children}
    </div>
  );
};

// Local Button for specialized styling
const Button: React.FC<{
  children: React.ReactNode;
  variant?: 'primary' | 'secondary' | 'ghost' | 'outline';
  size?: 'sm' | 'md' | 'lg';
  onClick?: () => void;
  disabled?: boolean;
  className?: string;
}> = ({ children, variant = 'primary', size = 'md', onClick, disabled, className }) => {
  const variants = {
    primary: 'bg-primary-600 text-white hover:bg-primary-700 disabled:bg-neutral-300',
    secondary: 'bg-surface-muted text-neutral-700 hover:bg-neutral-200 dark:hover:bg-primary-700 dark:text-neutral-200',
    ghost: 'text-neutral-600 hover:bg-neutral-100 dark:hover:bg-primary-800/50 dark:text-neutral-300',
    outline: 'border border-edge-strong text-neutral-700 hover:bg-neutral-50 dark:hover:bg-primary-800/50 dark:text-neutral-200',
  };
  const sizes = { sm: 'px-3 py-1.5 text-caption', md: 'px-4 py-2 text-body-sm', lg: 'px-6 py-3 text-body' };
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      className={cn('rounded-lg font-medium transition-colors flex items-center gap-2 disabled:cursor-not-allowed', variants[variant], sizes[size], className)}
    >
      {children}
    </button>
  );
};

// Inline `CorporateProgramFilterBar` removed — replaced by the shared
// `<ScopeSelector mode="corporate-program">` primitive (see import block).

// ============================================================================
// QUICK ACTION CARD
// ============================================================================

interface QuickAction {
  id: string;
  icon: React.FC<{ className?: string }>;
  title: string;
  description: string;
  onClick: () => void;
}

// ============================================================================
// OPERATION HISTORY ITEM
// ============================================================================

const OPERATION_LABELS: Record<string, string> = {
  MOVE_TRANSACTION_VA: 'Account relocated',
  MOVE_AGGREGATION: 'Division moved',
  ACQUISITION: 'Acquisition',
  MERGER: 'Merger',
  DIVESTITURE: 'Divestiture',
};

const OperationHistoryItem: React.FC<{ operation: OperationHistoryEntry; onViewDetails: (id: string) => void }> = ({ operation, onViewDetails }) => {
  const getOperationIcon = () => {
    switch (operation.operationType) {
      case 'MOVE_TRANSACTION_VA': return <Package className="w-4 h-4 text-info-600 dark:text-info-300" />;
      case 'MOVE_AGGREGATION': return <Folder className="w-4 h-4 text-cat-1 dark:text-cat-1-fg" />;
      case 'ACQUISITION': return <Building2 className="w-4 h-4 text-success-600 dark:text-success-300" />;
      case 'MERGER': return <GitMerge className="w-4 h-4 text-cat-2 dark:text-cat-2-fg" />;
      case 'DIVESTITURE': return <GitBranch className="w-4 h-4 text-warning-600 dark:text-warning-300" />;
      default: return <Settings className="w-4 h-4 text-neutral-600 dark:text-neutral-300" />;
    }
  };

  const getStatusBadge = () => {
    switch (operation.status) {
      case 'COMPLETED': return <Badge variant="success"><CheckCircle className="w-3 h-3 mr-1 inline" />Completed</Badge>;
      case 'PENDING': return <Badge variant="warning"><Clock className="w-3 h-3 mr-1 inline" />Pending</Badge>;
      case 'FAILED': return <Badge variant="error"><XCircle className="w-3 h-3 mr-1 inline" />Failed</Badge>;
      default: return <Badge variant="default"><RefreshCw className="w-3 h-3 mr-1 inline" />Rolled Back</Badge>;
    }
  };

  return (
    <div className="flex items-start gap-3 p-4 hover:bg-neutral-50 rounded-lg transition-colors dark:hover:bg-primary-800/50">
      <div className="p-2 bg-surface-muted rounded-lg">{getOperationIcon()}</div>
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2 flex-wrap">
          <span className="font-medium text-primary-900 dark:text-neutral-50">{OPERATION_LABELS[operation.operationType] ?? operation.operationType.replace(/_/g, ' ').toLowerCase()}</span>
          {getStatusBadge()}
        </div>
        <p className="body-sm mt-1">{operation.summary}</p>
        <div className="flex items-center gap-4 mt-2 caption">
          <span>{new Date(operation.createdAt).toLocaleTimeString()}</span>
          <span>by {operation.performedBy}</span>
        </div>
      </div>
      <button onClick={() => onViewDetails(operation.id)} className="p-2 hover:bg-neutral-100 rounded-lg text-neutral-400 hover:text-neutral-600 dark:hover:text-neutral-200 dark:hover:bg-primary-800 dark:text-neutral-400">
        <Eye className="w-4 h-4" />
      </button>
    </div>
  );
};

// ============================================================================
// PENDING APPROVAL CARD
// ============================================================================

const PendingApprovalCard: React.FC<{ operation: OperationHistoryEntry; onApprove: (id: string) => void; onReject: (id: string) => void }> = ({ operation, onApprove, onReject }) => (
  <div className="p-4 bg-warning-50 rounded-lg border border-warning-200 dark:bg-warning-500/10 dark:border-warning-500/30">
    <div className="flex items-start gap-3">
      <Clock className="w-5 h-5 text-warning-600 mt-0.5 flex-shrink-0 dark:text-warning-300" />
      <div className="flex-1">
        <p className="font-medium text-warning-900">{operation.operationType.replace(/_/g, ' ')}: {operation.summary}</p>
        <p className="text-body-sm text-warning-700 mt-1 dark:text-warning-300">Requested by: {operation.performedBy}</p>
        <div className="flex items-center gap-2 mt-3">
          <Button variant="ghost" size="sm" onClick={() => onReject(operation.id)}><X className="w-3 h-3" />Reject</Button>
          <Button variant="primary" size="sm" onClick={() => onApprove(operation.id)}><Check className="w-3 h-3" />Approve</Button>
        </div>
      </div>
    </div>
  </div>
);

// ============================================================================
// RULES MODAL
// ============================================================================

const RulesModal: React.FC<{ isOpen: boolean; onClose: () => void; rules: OperationRules | null }> = ({ isOpen, onClose, rules }) => {
  const [activeTab, setActiveTab] = useState<'transaction' | 'aggregation' | 'mna' | 'notmovable'>('transaction');

  // Phase 10 follow-up (2026-05-13): migrated from hand-rolled wrapper to
  // shared <Modal size="lg">. The local `Card` component name conflicts
  // with the shared Modal name (`Modal` is already a local helper in this
  // file), so imported as SharedModal to disambiguate.
  return (
    <SharedModal isOpen={isOpen} onClose={onClose} size="lg" title="Hierarchy Operation Rules">
      <div className="-mx-6 -my-6">
        <div className="border-b border-edge">
            <div className="flex gap-1 px-6">
              {[{ id: 'transaction', label: 'Transaction VAs' }, { id: 'aggregation', label: 'Aggregations' }, { id: 'mna', label: 'M&A Operations' }, { id: 'notmovable', label: 'Not Movable' }].map((tab) => (
                <button key={tab.id} onClick={() => setActiveTab(tab.id as any)} className={cn('px-4 py-3 text-body-sm font-medium border-b-2 transition-colors', activeTab === tab.id ? 'border-primary-600 text-primary-600 dark:text-primary-200' : 'border-transparent text-neutral-500 hover:text-neutral-700 dark:text-neutral-400 dark:hover:text-neutral-200')}>
                  {tab.label}
                </button>
              ))}
            </div>
          </div>
          <div className="p-6 max-h-[60vh] overflow-y-auto">
            {activeTab === 'transaction' && rules && (
              <div className="space-y-4">
                <h3 className="font-semibold text-primary-900 dark:text-neutral-50">Transaction VA Rules</h3>
                <ul className="space-y-3">
                  {rules.transactionVaRules.map((rule, i) => (
                    <li key={i} className="flex items-start gap-3 text-body-sm"><Check className="w-4 h-4 text-success-500 dark:text-success-300 mt-0.5 flex-shrink-0" /><span className="text-neutral-700 dark:text-neutral-200">{rule}</span></li>
                  ))}
                </ul>
              </div>
            )}
            {activeTab === 'aggregation' && rules && (
              <div className="space-y-4">
                <h3 className="font-semibold text-primary-900 dark:text-neutral-50">Aggregation Rules</h3>
                <ul className="space-y-3">
                  {rules.aggregationRules.map((rule, i) => (
                    <li key={i} className="flex items-start gap-3 text-body-sm"><Check className="w-4 h-4 text-success-500 dark:text-success-300 mt-0.5 flex-shrink-0" /><span className="text-neutral-700 dark:text-neutral-200">{rule}</span></li>
                  ))}
                </ul>
              </div>
            )}
            {activeTab === 'mna' && rules && (
              <div className="space-y-6">
                <div><h3 className="font-semibold text-primary-900 mb-3 dark:text-neutral-50">Acquisition Rules</h3><ul className="space-y-2">{rules.acquisitionRules.map((rule, i) => (<li key={i} className="flex items-start gap-3 text-body-sm"><Check className="w-4 h-4 text-success-500 dark:text-success-300 mt-0.5 flex-shrink-0" /><span className="text-neutral-700 dark:text-neutral-200">{rule}</span></li>))}</ul></div>
                <div><h3 className="font-semibold text-primary-900 mb-3 dark:text-neutral-50">Divestiture Rules</h3><ul className="space-y-2">{rules.divestureRules.map((rule, i) => (<li key={i} className="flex items-start gap-3 text-body-sm"><Check className="w-4 h-4 text-success-500 dark:text-success-300 mt-0.5 flex-shrink-0" /><span className="text-neutral-700 dark:text-neutral-200">{rule}</span></li>))}</ul></div>
              </div>
            )}
            {activeTab === 'notmovable' && rules && (
              <div className="space-y-4">
                <h3 className="font-semibold text-primary-900 dark:text-neutral-50">Types That Cannot Be Moved</h3>
                <div className="flex flex-wrap gap-2">{rules.notMovableTypes.map((type) => (<Badge key={type} variant="error"><X className="w-3 h-3 mr-1 inline" />{type}</Badge>))}</div>
                <div className="p-4 bg-warning-50 rounded-lg dark:bg-warning-500/10"><p className="text-body-sm text-warning-700 dark:text-warning-300">These account types are system-managed and cannot be moved manually. Use the appropriate operations for corporate-level restructuring.</p></div>
              </div>
            )}
          </div>
          <div className="px-6 py-4 border-t border-edge bg-surface-page"><Button variant="secondary" onClick={onClose}>Close</Button></div>
        </div>
    </SharedModal>
  );
};

// ============================================================================
// MAIN PAGE COMPONENT
// ============================================================================

const HierarchyOperationsPage: React.FC = () => {
  // History, View All and operation details all open the full Operation History page.
  const { navigate } = useNavigation();
  const openHistory = () => navigate('operation-history');
  // ====== CORPORATE SELECTION STATE ======
  // Every operation here is corporate-scoped (the APIs and the Move picker take only a corporate).
  const [corporates, setCorporates] = useState<Corporate[]>([]);
  const [selectedCorporateId, setSelectedCorporateId] = useState('');
  const [loadingCorporates, setLoadingCorporates] = useState(true);

  // ====== OPERATIONS STATE ======
  const [history, setHistory] = useState<OperationHistoryEntry[]>([]);
  const [pendingApprovals, setPendingApprovals] = useState<OperationHistoryEntry[]>([]);
  const [rules, setRules] = useState<OperationRules | null>(null);
  const [loading, setLoading] = useState(true);
  const [opsLoading, setOpsLoading] = useState(false);
  const [refreshing, setRefreshing] = useState(false);

  // ====== MODAL STATES - ALL M&A MODALS ======
  const [showRulesModal, setShowRulesModal] = useState(false);
  const [showMoveModal, setShowMoveModal] = useState(false);
  const [showAcquisitionWizard, setShowAcquisitionWizard] = useState(false);
  const [showMergerWizard, setShowMergerWizard] = useState(false);
  const [showDivestitureModal, setShowDivestitureModal] = useState(false);
  const [moveType, setMoveType] = useState<MoveType>('TRANSACTION_VA');

  // ====== LOAD CORPORATES ON MOUNT ======
  useEffect(() => {
    const loadCorporates = async () => {
      try {
        setLoadingCorporates(true);
        const corps = await corporatesApi.getAll();
        setCorporates(corps);
        if (corps.length > 0) {
          setSelectedCorporateId(corps[0].id);
        }
      } catch (err) {
        console.error('Failed to load corporates:', err);
      } finally {
        setLoadingCorporates(false);
      }
    };
    loadCorporates();
  }, []);

  // ====== LOAD HIERARCHY RULES ON MOUNT ======
  useEffect(() => {
    const loadRules = async () => {
      try {
        const rulesRes = await hierarchyOperationsApi.getOperationRules();
        if (rulesRes.success && rulesRes.data) {
          setRules(rulesRes.data);
        }
      } catch (err) {
        console.error('Failed to load rules:', err);
      } finally {
        setLoading(false);
      }
    };
    loadRules();
  }, []);

  // ====== LOAD CORPORATE DATA WHEN CORPORATE CHANGES ======
  const loadCorporateData = useCallback(async () => {
    if (!selectedCorporateId) return;
    
    setOpsLoading(true);
    try {
      const [historyRes, pendingRes] = await Promise.all([
        hierarchyOperationsApi.getOperationHistory(selectedCorporateId),
        hierarchyOperationsApi.getPendingApprovals(selectedCorporateId),
      ]);
      
      if (historyRes.success && historyRes.data) setHistory(historyRes.data.content);
      if (pendingRes.success && pendingRes.data) setPendingApprovals(pendingRes.data);
    } catch (err) { 
      console.error('Failed to load corporate data:', err); 
    } finally { 
      setOpsLoading(false); 
    }
  }, [selectedCorporateId]);

  useEffect(() => { 
    loadCorporateData(); 
  }, [loadCorporateData]);

  // ====== HANDLERS ======
  const handleRefresh = async () => { 
    setRefreshing(true); 
    await loadCorporateData(); 
    setRefreshing(false); 
  };
  
  const handleApprove = async (operationId: string) => { 
    try { 
      await hierarchyOperationsApi.approveOperation(operationId, 'current-user'); 
      loadCorporateData(); 
    } catch (err) { 
      console.error('Failed to approve:', err); 
    } 
  };
  
  const handleReject = async (operationId: string) => { 
    try { 
      await hierarchyOperationsApi.rejectOperation(operationId, 'current-user', 'Rejected'); 
      loadCorporateData(); 
    } catch (err) { 
      console.error('Failed to reject:', err); 
    } 
  };

  // ====== MODAL SUCCESS HANDLERS ======
  const handleMoveSuccess = (_result: MoveOperationResult) => {
    loadCorporateData();
    setShowMoveModal(false);
  };

  const handleAcquisitionSuccess = (_result: any) => {
    loadCorporateData();
    setShowAcquisitionWizard(false);
  };

  const handleMergerSuccess = (_result: any) => {
    loadCorporateData();
    setShowMergerWizard(false);
  };

  const handleDivestitureSuccess = (_result: any) => {
    loadCorporateData();
    setShowDivestitureModal(false);
  };

  const selectedCorporate = corporates.find((c) => c.id === selectedCorporateId);

  const quickActions: QuickAction[] = [
    {
      id: 'move-va',
      icon: Package,
      title: 'Relocate account',
      description: 'Move account to new division',
      onClick: () => { setMoveType('TRANSACTION_VA'); setShowMoveModal(true); }
    },
    {
      id: 'move-aggregation',
      icon: Folder,
      title: 'Move division',
      description: 'Relocate entire business unit',
      onClick: () => { setMoveType('AGGREGATION'); setShowMoveModal(true); }
    },
    {
      id: 'acquire',
      icon: Building2,
      title: 'Acquisition',
      description: 'Acquire another corporate',
      onClick: () => setShowAcquisitionWizard(true)
    },
    {
      id: 'merge',
      icon: GitMerge,
      title: 'Merger',
      description: 'Combine two corporates',
      onClick: () => setShowMergerWizard(true)
    },
    {
      id: 'divest',
      icon: GitBranch,
      title: 'Divestiture',
      description: 'Spin-off to new corporate',
      onClick: () => setShowDivestitureModal(true)
    },
  ];

  // Register page-level toolbar in the Layout header — same pattern as every
  // other treasury page. View Rules + History no longer float inside the body.
  // Uses SharedButton so the visual matches the other header CTAs (Refresh,
  // Export, New Account, …) rather than the page-local Button variant.
  usePageHeaderActions(
    () => (
      <>
        <SharedButton
          variant="outline"
          size="sm"
          leftIcon={<BookOpen className="w-4 h-4" />}
          onClick={() => setShowRulesModal(true)}
        >
          View Rules
        </SharedButton>
        <SharedButton
          variant="outline"
          size="sm"
          leftIcon={<History className="w-4 h-4" />}
          onClick={openHistory}
        >
          History
        </SharedButton>
      </>
    ),
    []
  );

  if (loadingCorporates || loading) {
    return (
      <Page>
        <div className="flex items-center justify-center h-96">
          <Loader2 className="w-8 h-8 animate-spin text-primary-600 dark:text-primary-200" />
        </div>
      </Page>
    );
  }

  return (
    <Page>
      {/* In-body identity (Phase 10) — aligns with the rest of Aperture's
          conformed pages. View Rules / History live in the Aperture Layout
          header via usePageHeaderActions above. */}
      <PageHeader
        title="Reorganization"
        description="Move accounts and divisions, or run an acquisition, merger or divestiture. Every change is recorded in Operation History."
      />

      <ScopeSelector
        mode="corporate-only"
        corporates={corporates}
        selectedCorporateId={selectedCorporateId}
        onCorporateChange={setSelectedCorporateId}
        requireSelection
      />

      {/* No Selection State */}
      {!selectedCorporateId && (
        <Card className="p-8 text-center">
          <div className="flex flex-col items-center">
            <StatusIconBadge tone="neutral" icon={Building2} size="xl" rounded="full" className="mb-4" />
            <h2 className="section-title mb-2">Select a Corporate</h2>
            <p className="text-neutral-500 max-w-md dark:text-neutral-400">
              Choose a corporate from the dropdown above to view its hierarchy operations.
            </p>
          </div>
        </Card>
      )}

      {/* Main Content */}
      {selectedCorporateId && (
        <>
          {/* Reorganize actions — one row of buttons (was five large tiles). The Move flows pick
              the account or division inside the modal (search + target tree), so the page draws
              no tree of its own; browse the hierarchy on Balance Hierarchy. */}
          <div className="flex flex-wrap items-center gap-2">
            {quickActions.map(({ id, icon: Icon, title, description, onClick }) => (
              <SharedButton key={id} variant="outline" size="sm" leftIcon={<Icon className="w-4 h-4" />} onClick={onClick} title={description}>
                {title}
              </SharedButton>
            ))}
            <Button variant="ghost" size="sm" className="ml-auto" onClick={handleRefresh} disabled={refreshing}>
              <RefreshCw className={cn('w-4 h-4', refreshing && 'animate-spin')} />Refresh
            </Button>
          </div>

          <div className="space-y-6">
            {pendingApprovals.length > 0 && (
              <Card padding="none">
                <div className="px-4 py-3 border-b border-edge flex items-center justify-between">
                  <h3 className="font-semibold text-primary-900 dark:text-neutral-50">Pending Approvals</h3>
                  <Badge variant="warning">{pendingApprovals.length}</Badge>
                </div>
                <div className="p-4 space-y-3">
                  {pendingApprovals.slice(0, 3).map((op) => (
                    <PendingApprovalCard key={op.id} operation={op} onApprove={handleApprove} onReject={handleReject} />
                  ))}
                  {pendingApprovals.length > 3 && <Button variant="ghost" className="w-full" onClick={openHistory}>View All Pending</Button>}
                </div>
              </Card>
            )}

            <Card padding="none">
              <div className="px-4 py-3 border-b border-edge flex items-center justify-between">
                <h3 className="font-semibold text-primary-900 dark:text-neutral-50">Recent Operations</h3>
                <Button variant="ghost" size="sm" onClick={openHistory}>View All</Button>
              </div>
              <div className="divide-y divide-edge-subtle">
                {opsLoading && history.length === 0 ? (
                  <div className="flex items-center justify-center py-12">
                    <Loader2 className="w-6 h-6 animate-spin text-primary-600 dark:text-primary-200" />
                  </div>
                ) : history.length === 0 ? (
                  <div className="p-8 text-center text-neutral-500 dark:text-neutral-400">
                    <History className="w-8 h-8 mx-auto mb-2 text-neutral-300 dark:text-neutral-400" />
                    <p className="text-body-sm">No operations yet</p>
                  </div>
                ) : (
                  history.slice(0, 5).map((op) => (
                    <OperationHistoryItem
                      key={op.id}
                      operation={op}
                      onViewDetails={openHistory}
                    />
                  ))
                )}
              </div>
            </Card>
          </div>
        </>
      )}

      {/* ====== ALL MODALS ====== */}
      <RulesModal isOpen={showRulesModal} onClose={() => setShowRulesModal(false)} rules={rules} />
      
      {selectedCorporateId && (
        <>
          <MoveVaModal
            isOpen={showMoveModal}
            onClose={() => setShowMoveModal(false)}
            onSuccess={handleMoveSuccess}
            moveType={moveType}
            corporateId={selectedCorporateId}
          />

          <AcquisitionWizard
            isOpen={showAcquisitionWizard}
            onClose={() => setShowAcquisitionWizard(false)}
            onSuccess={handleAcquisitionSuccess}
            // targetCorporateId={selectedCorporateId}
            acquirerCorporateId={selectedCorporateId}  // ← CORRECT
            acquirerCorporateName={selectedCorporate?.legalName || selectedCorporate?.tradeName}         />

          <MergerWizard
            isOpen={showMergerWizard}
            onClose={() => setShowMergerWizard(false)}
            onSuccess={handleMergerSuccess}
          />

          <DivestitureModal
            isOpen={showDivestitureModal}
            onClose={() => setShowDivestitureModal(false)}
            onSuccess={handleDivestitureSuccess}
            corporateId={selectedCorporateId}
            corporateName={selectedCorporate?.legalName || selectedCorporate?.tradeName}
          />
        </>
      )}
    </Page>
  );
};

export default HierarchyOperationsPage;