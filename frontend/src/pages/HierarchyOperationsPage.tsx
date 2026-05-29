// ============================================================================
// CORPORATE REORGANIZATION PAGE - M&A, Mergers & Divestitures
// ============================================================================
// Manage corporate restructuring: acquisitions, mergers, divestitures, and
// account relocations within the corporate hierarchy.
// ============================================================================

import React, { useState, useEffect, useCallback } from 'react';
import {
  Package, Folder, Building2, GitMerge, GitBranch, ChevronRight, ChevronDown,
  Check, Clock, AlertTriangle, Loader2, RefreshCw, Eye, MoreVertical, History,
  BookOpen, X, Globe, Wallet, Coins, Scale, AlertCircle, Plus, CheckCircle,
  XCircle, ArrowUpDown, Settings, Building, Layers,
} from 'lucide-react';
import { Card as SharedCard, Badge as SharedBadge, Button as SharedButton, Skeleton } from '../components/ui';
import { usePageHeaderActions } from '../context/PageHeaderContext';
import { cn } from '../utils';
import {
  hierarchyOperationsApi,
  HierarchyNode, OperationHistoryEntry, OperationRules, CorporateSummary,
  MoveOperationResult, HierarchyInitResult, AccountCategory,
} from '../services/hierarchyOperationsApi';
import { programsApi as programsApiService } from '../services/api';
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

interface LegalEntity {
  id: string;
  entityCode: string;
  entityName: string;
  shortName?: string;
  countryCode?: string;
  functionalCurrency: string;
  status: string;
}

interface ProgramOption {
  id: string;
  programName: string;
  programCode: string;
  currencyCode: string;
  programType: string;
  status: string;
  corporateId?: string;
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

const legalEntityApi = {
  getByCorporate: async (corporateId: string): Promise<LegalEntity[]> => {
    try {
      const response = await apiClient.get(`/legal-entities/corporate/${corporateId}`);
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
    default: 'bg-neutral-100 text-neutral-700 dark:bg-primary-800 dark:text-neutral-200',
    success: 'bg-success-100 text-success-700 dark:bg-success-500/20 dark:text-success-300',
    warning: 'bg-amber-100 text-amber-700 dark:bg-amber-500/20 dark:text-amber-300',
    error: 'bg-error-100 text-error-700 dark:bg-error-500/20 dark:text-error-300',
    info: 'bg-info-100 text-info-700 dark:bg-info-500/20 dark:text-info-300',
  };

  return (
    <span className={cn('px-2 py-0.5 text-xs font-medium rounded-full', variants[variant], className)}>
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
    <div className={cn('bg-white rounded-xl shadow-sm border border-neutral-200 dark:bg-primary-900 dark:border-primary-800', paddings[padding], className)}>
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
    secondary: 'bg-neutral-100 text-neutral-700 hover:bg-neutral-200 dark:bg-primary-800 dark:text-neutral-200',
    ghost: 'text-neutral-600 hover:bg-neutral-100 dark:text-neutral-300',
    outline: 'border border-neutral-300 text-neutral-700 hover:bg-neutral-50 dark:border-primary-700 dark:text-neutral-200',
  };
  const sizes = { sm: 'px-3 py-1.5 text-xs', md: 'px-4 py-2 text-sm', lg: 'px-6 py-3 text-base' };
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
  color?: string;
}

const QuickActionCard: React.FC<{ action: QuickAction }> = ({ action }) => {
  const Icon = action.icon;
  const colorClasses = action.color || 'bg-primary-50 group-hover:bg-primary-100 text-primary-600 dark:bg-primary-800/40 dark:text-primary-200';
  
  return (
    <button
      onClick={action.onClick}
      className="flex flex-col items-center gap-3 p-6 bg-white rounded-xl border border-neutral-200 hover:border-primary-300 hover:shadow-md transition-all group dark:bg-primary-900 dark:border-primary-800"
    >
      <div className={cn('p-3 rounded-xl transition-colors', colorClasses)}>
        <Icon className="w-6 h-6" />
      </div>
      <div className="text-center">
        <p className="font-semibold text-primary-900 group-hover:text-primary-700 dark:text-neutral-50">{action.title}</p>
        <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">{action.description}</p>
      </div>
    </button>
  );
};

// ============================================================================
// TREE NODE COMPONENT
// ============================================================================

interface TreeNodeProps {
  node: HierarchyNode;
  expandedIds: Set<string>;
  onToggle: (id: string) => void;
  onAction: (node: HierarchyNode, action: string) => void;
  level?: number;
}

const TreeNode: React.FC<TreeNodeProps> = ({ node, expandedIds, onToggle, onAction, level = 0 }) => {
  const [showMenu, setShowMenu] = useState(false);
  const isExpanded = expandedIds.has(node.id);
  const hasChildren = node.children && node.children.length > 0;

  const getNodeIcon = () => {
    switch (node.accountCategory) {
      case 'ROOT': return <Globe className="w-4 h-4 text-white" />;
      case 'AGGREGATION': return <Folder className="w-4 h-4 text-info-600 dark:text-info-300" />;
      case 'CURRENCY_MIRROR': return <Coins className="w-4 h-4 text-cyan-600 dark:text-cyan-300" />;
      case 'SETTLEMENT': return <Scale className="w-4 h-4 text-purple-600 dark:text-purple-300" />;
      case 'EXCEPTION': return <AlertCircle className="w-4 h-4 text-amber-600 dark:text-amber-300" />;
      default: return <Wallet className="w-4 h-4 text-success-600 dark:text-success-300" />;
    }
  };

  const getNodeStyle = () => {
    switch (node.accountCategory) {
      case 'ROOT': return 'bg-primary-900';
      case 'AGGREGATION': return 'bg-info-100 dark:bg-info-500/20';
      case 'CURRENCY_MIRROR': return 'bg-cyan-50 border-2 border-dashed border-cyan-300 dark:bg-cyan-500/10';
      case 'SETTLEMENT': return 'bg-purple-50 border-2 border-purple-300 dark:bg-purple-500/10';
      case 'EXCEPTION': return 'bg-amber-50 border-2 border-amber-300 dark:bg-amber-500/10';
      default: return 'bg-success-50 dark:bg-success-500/10';
    }
  };

  const canMove = !['ROOT', 'CURRENCY_MIRROR', 'EXCEPTION'].includes(node.accountCategory);

  return (
    <div className="group">
      <div
        className="flex items-center gap-2 py-2 px-3 rounded-lg hover:bg-neutral-50 cursor-pointer dark:hover:bg-primary-800/50"
        style={{ marginLeft: `${level * 24}px` }}
      >
        {hasChildren ? (
          <button onClick={() => onToggle(node.id)} className="p-0.5 hover:bg-neutral-200 rounded">
            {isExpanded ? <ChevronDown className="w-4 h-4 text-neutral-500 dark:text-neutral-400" /> : <ChevronRight className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />}
          </button>
        ) : <span className="w-5" />}

        <div className={cn('p-1.5 rounded-lg', getNodeStyle())}>{getNodeIcon()}</div>
        <span className="flex-1 text-sm font-medium text-primary-900 truncate dark:text-neutral-50">{node.name}</span>
        <Badge variant="info">{node.currencyCode}</Badge>

        {canMove && (
          <div className="relative">
            <button
              onClick={() => setShowMenu(!showMenu)}
              className="p-1 hover:bg-neutral-200 rounded opacity-0 group-hover:opacity-100 transition-opacity"
            >
              <MoreVertical className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
            </button>
            {showMenu && (
              <>
                <div className="fixed inset-0 z-10" onClick={() => setShowMenu(false)} />
                <div className="absolute right-0 top-full mt-1 w-40 bg-white rounded-lg shadow-lg border border-neutral-200 py-1 z-20 dark:bg-primary-900 dark:border-primary-800">
                  <button onClick={() => { onAction(node, 'move'); setShowMenu(false); }} className="w-full px-3 py-2 text-left text-sm hover:bg-neutral-50 flex items-center gap-2 dark:hover:bg-primary-800/50">
                    <ArrowUpDown className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />Move
                  </button>
                  <button onClick={() => { onAction(node, 'view'); setShowMenu(false); }} className="w-full px-3 py-2 text-left text-sm hover:bg-neutral-50 flex items-center gap-2 dark:hover:bg-primary-800/50">
                    <Eye className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />View Details
                  </button>
                </div>
              </>
            )}
          </div>
        )}
      </div>

      {hasChildren && isExpanded && (
        <div>
          {node.children!.map((child) => (
            <TreeNode key={child.id} node={child} expandedIds={expandedIds} onToggle={onToggle} onAction={onAction} level={level + 1} />
          ))}
        </div>
      )}
    </div>
  );
};

// ============================================================================
// OPERATION HISTORY ITEM
// ============================================================================

const OperationHistoryItem: React.FC<{ operation: OperationHistoryEntry; onViewDetails: (id: string) => void }> = ({ operation, onViewDetails }) => {
  const getOperationIcon = () => {
    switch (operation.operationType) {
      case 'MOVE_TRANSACTION_VA': return <Package className="w-4 h-4 text-info-600 dark:text-info-300" />;
      case 'MOVE_AGGREGATION': return <Folder className="w-4 h-4 text-indigo-600 dark:text-indigo-300" />;
      case 'ACQUISITION': return <Building2 className="w-4 h-4 text-success-600 dark:text-success-300" />;
      case 'MERGER': return <GitMerge className="w-4 h-4 text-purple-600 dark:text-purple-300" />;
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
      <div className="p-2 bg-neutral-100 rounded-lg dark:bg-primary-800">{getOperationIcon()}</div>
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2 flex-wrap">
          <span className="font-medium text-primary-900 dark:text-neutral-50">{operation.operationType.replace(/_/g, ' ')}</span>
          {getStatusBadge()}
        </div>
        <p className="text-sm text-neutral-600 mt-1 dark:text-neutral-300">{operation.summary}</p>
        <div className="flex items-center gap-4 mt-2 text-xs text-neutral-400 dark:text-neutral-500">
          <span>{new Date(operation.createdAt).toLocaleTimeString()}</span>
          <span>by {operation.performedBy}</span>
        </div>
      </div>
      <button onClick={() => onViewDetails(operation.id)} className="p-2 hover:bg-neutral-100 rounded-lg text-neutral-400 hover:text-neutral-600 dark:hover:bg-primary-800 dark:text-neutral-500">
        <Eye className="w-4 h-4" />
      </button>
    </div>
  );
};

// ============================================================================
// PENDING APPROVAL CARD
// ============================================================================

const PendingApprovalCard: React.FC<{ operation: OperationHistoryEntry; onApprove: (id: string) => void; onReject: (id: string) => void }> = ({ operation, onApprove, onReject }) => (
  <div className="p-4 bg-amber-50 rounded-lg border border-amber-200 dark:bg-amber-500/10 dark:border-amber-500/30">
    <div className="flex items-start gap-3">
      <Clock className="w-5 h-5 text-amber-600 mt-0.5 flex-shrink-0 dark:text-amber-300" />
      <div className="flex-1">
        <p className="font-medium text-amber-900">{operation.operationType.replace(/_/g, ' ')}: {operation.summary}</p>
        <p className="text-sm text-amber-700 mt-1 dark:text-amber-300">Requested by: {operation.performedBy}</p>
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
        <div className="border-b border-neutral-200 dark:border-primary-800">
            <div className="flex gap-1 px-6">
              {[{ id: 'transaction', label: 'Transaction VAs' }, { id: 'aggregation', label: 'Aggregations' }, { id: 'mna', label: 'M&A Operations' }, { id: 'notmovable', label: 'Not Movable' }].map((tab) => (
                <button key={tab.id} onClick={() => setActiveTab(tab.id as any)} className={cn('px-4 py-3 text-sm font-medium border-b-2 transition-colors', activeTab === tab.id ? 'border-primary-600 text-primary-600 dark:text-primary-200' : 'border-transparent text-neutral-500 hover:text-neutral-700 dark:text-neutral-400 dark:hover:text-neutral-200')}>
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
                    <li key={i} className="flex items-start gap-3 text-sm"><Check className="w-4 h-4 text-success-500 mt-0.5 flex-shrink-0" /><span className="text-neutral-700 dark:text-neutral-200">{rule}</span></li>
                  ))}
                </ul>
              </div>
            )}
            {activeTab === 'aggregation' && rules && (
              <div className="space-y-4">
                <h3 className="font-semibold text-primary-900 dark:text-neutral-50">Aggregation Rules</h3>
                <ul className="space-y-3">
                  {rules.aggregationRules.map((rule, i) => (
                    <li key={i} className="flex items-start gap-3 text-sm"><Check className="w-4 h-4 text-success-500 mt-0.5 flex-shrink-0" /><span className="text-neutral-700 dark:text-neutral-200">{rule}</span></li>
                  ))}
                </ul>
              </div>
            )}
            {activeTab === 'mna' && rules && (
              <div className="space-y-6">
                <div><h3 className="font-semibold text-primary-900 mb-3 dark:text-neutral-50">Acquisition Rules</h3><ul className="space-y-2">{rules.acquisitionRules.map((rule, i) => (<li key={i} className="flex items-start gap-3 text-sm"><Check className="w-4 h-4 text-success-500 mt-0.5 flex-shrink-0" /><span className="text-neutral-700 dark:text-neutral-200">{rule}</span></li>))}</ul></div>
                <div><h3 className="font-semibold text-primary-900 mb-3 dark:text-neutral-50">Divestiture Rules</h3><ul className="space-y-2">{rules.divestureRules.map((rule, i) => (<li key={i} className="flex items-start gap-3 text-sm"><Check className="w-4 h-4 text-success-500 mt-0.5 flex-shrink-0" /><span className="text-neutral-700 dark:text-neutral-200">{rule}</span></li>))}</ul></div>
              </div>
            )}
            {activeTab === 'notmovable' && rules && (
              <div className="space-y-4">
                <h3 className="font-semibold text-primary-900 dark:text-neutral-50">Types That Cannot Be Moved</h3>
                <div className="flex flex-wrap gap-2">{rules.notMovableTypes.map((type) => (<Badge key={type} variant="error"><X className="w-3 h-3 mr-1 inline" />{type}</Badge>))}</div>
                <div className="p-4 bg-amber-50 rounded-lg dark:bg-amber-500/10"><p className="text-sm text-amber-700 dark:text-amber-300">These account types are system-managed and cannot be moved manually. Use the appropriate operations for corporate-level restructuring.</p></div>
              </div>
            )}
          </div>
          <div className="px-6 py-4 border-t border-neutral-200 bg-neutral-50 dark:border-primary-800 dark:bg-primary-950"><Button variant="secondary" onClick={onClose}>Close</Button></div>
        </div>
    </SharedModal>
  );
};

// ============================================================================
// MAIN PAGE COMPONENT
// ============================================================================

const HierarchyOperationsPage: React.FC = () => {
  // ====== CORPORATE/PROGRAM SELECTION STATE ======
  const [corporates, setCorporates] = useState<Corporate[]>([]);
  const [programs, setPrograms] = useState<ProgramOption[]>([]);
  const [selectedCorporateId, setSelectedCorporateId] = useState('');
  const [selectedProgramId, setSelectedProgramId] = useState('');
  const [legalEntities, setLegalEntities] = useState<LegalEntity[]>([]);
  const [loadingCorporates, setLoadingCorporates] = useState(true);
  const [loadingPrograms, setLoadingPrograms] = useState(false);

  // ====== HIERARCHY STATE ======
  const [hierarchy, setHierarchy] = useState<HierarchyNode | null>(null);
  const [history, setHistory] = useState<OperationHistoryEntry[]>([]);
  const [pendingApprovals, setPendingApprovals] = useState<OperationHistoryEntry[]>([]);
  const [rules, setRules] = useState<OperationRules | null>(null);
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());
  const [loading, setLoading] = useState(true);
  const [hierarchyLoading, setHierarchyLoading] = useState(false);
  const [refreshing, setRefreshing] = useState(false);

  // ====== MODAL STATES - ALL M&A MODALS ======
  const [showRulesModal, setShowRulesModal] = useState(false);
  const [showMoveModal, setShowMoveModal] = useState(false);
  const [showAcquisitionWizard, setShowAcquisitionWizard] = useState(false);
  const [showMergerWizard, setShowMergerWizard] = useState(false);
  const [showDivestitureModal, setShowDivestitureModal] = useState(false);
  const [moveType, setMoveType] = useState<MoveType>('TRANSACTION_VA');
  const [preSelectedVaId, setPreSelectedVaId] = useState<string | undefined>(undefined);

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

  // ====== LOAD LEGAL ENTITIES WHEN CORPORATE CHANGES ======
  useEffect(() => {
    const loadEntities = async () => {
      if (!selectedCorporateId) { 
        setLegalEntities([]); 
        return; 
      }
      try {
        const entities = await legalEntityApi.getByCorporate(selectedCorporateId);
        setLegalEntities(entities);
      } catch (err) { 
        console.error('Failed to load legal entities:', err); 
        setLegalEntities([]); 
      }
    };
    loadEntities();
  }, [selectedCorporateId]);

  // ====== LOAD PROGRAMS WHEN CORPORATE CHANGES ======
  useEffect(() => {
    const loadPrograms = async () => {
      if (!selectedCorporateId) {
        setPrograms([]);
        setSelectedProgramId('');
        return;
      }

      try {
        setLoadingPrograms(true);
        const response = await programsApiService.getAll({ corporateId: selectedCorporateId });
        
        let programsData: any[] = [];
        if (response?.success && response?.data) {
          if (response.data.programs && Array.isArray(response.data.programs)) {
            programsData = response.data.programs;
          } else if (response.data.content && Array.isArray(response.data.content)) {
            programsData = response.data.content;
          } else if (Array.isArray(response.data)) {
            programsData = response.data;
          }
        }

        const programList: ProgramOption[] = programsData.map((p: any) => ({
          id: p.id,
          programName: p.programName,
          programCode: p.programCode,
          currencyCode: p.currencyCode || 'AED',
          programType: p.programType,
          status: p.status,
          corporateId: p.corporateId,
        }));
        setPrograms(programList);

        const activePrograms = programList.filter(p => p.status === 'ACTIVE');
        if (activePrograms.length > 0) {
          setSelectedProgramId(activePrograms[0].id);
        } else {
          setSelectedProgramId('');
        }
      } catch (err) {
        console.error('Failed to load programs:', err);
        setPrograms([]);
      } finally {
        setLoadingPrograms(false);
      }
    };
    loadPrograms();
  }, [selectedCorporateId]);

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

  // ====== LOAD CORPORATE DATA WHEN PROGRAM CHANGES ======
  const loadCorporateData = useCallback(async () => {
    if (!selectedCorporateId) return;
    
    setHierarchyLoading(true);
    try {
      const [hierarchyRes, historyRes, pendingRes] = await Promise.all([
        hierarchyOperationsApi.getHierarchy(selectedCorporateId),
        hierarchyOperationsApi.getOperationHistory(selectedCorporateId),
        hierarchyOperationsApi.getPendingApprovals(selectedCorporateId),
      ]);
      
      if (hierarchyRes.success && hierarchyRes.data) {
        setHierarchy(hierarchyRes.data);
        const ids = new Set<string>();
        ids.add(hierarchyRes.data.id);
        hierarchyRes.data.children?.forEach((child) => { 
          ids.add(child.id); 
          child.children?.forEach((gc) => ids.add(gc.id)); 
        });
        setExpandedIds(ids);
      } else {
        setHierarchy(null);
      }
      
      if (historyRes.success && historyRes.data) setHistory(historyRes.data.content);
      if (pendingRes.success && pendingRes.data) setPendingApprovals(pendingRes.data);
    } catch (err) { 
      console.error('Failed to load corporate data:', err); 
    } finally { 
      setHierarchyLoading(false); 
    }
  }, [selectedCorporateId, selectedProgramId]);

  useEffect(() => { 
    loadCorporateData(); 
  }, [loadCorporateData]);

  // ====== HANDLERS ======
  const handleCorporateChange = (corpId: string) => {
    setSelectedCorporateId(corpId);
    setSelectedProgramId('');
    setHierarchy(null);
  };

  const handleProgramChange = (programId: string) => {
    setSelectedProgramId(programId);
    setHierarchy(null);
  };

  const handleRefresh = async () => { 
    setRefreshing(true); 
    await loadCorporateData(); 
    setRefreshing(false); 
  };
  
  const handleToggle = (id: string) => { 
    const n = new Set(expandedIds); 
    if (n.has(id)) n.delete(id); 
    else n.add(id); 
    setExpandedIds(n); 
  };
  
  const handleExpandAll = () => { 
    if (!hierarchy) return; 
    const collectIds = (node: HierarchyNode): string[] => { 
      const ids = [node.id]; 
      node.children?.forEach((c) => ids.push(...collectIds(c))); 
      return ids; 
    }; 
    setExpandedIds(new Set(collectIds(hierarchy))); 
  };
  
  const handleCollapseAll = () => { 
    setExpandedIds(new Set([hierarchy?.id || ''])); 
  };
  
  const handleNodeAction = (node: HierarchyNode, action: string) => { 
    if (action === 'move') { 
      setMoveType(node.accountCategory === 'AGGREGATION' ? 'AGGREGATION' : 'TRANSACTION_VA'); 
      setPreSelectedVaId(node.id);
      setShowMoveModal(true); 
    } 
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
  const handleMoveSuccess = (result: MoveOperationResult) => {
    console.log('Move operation successful:', result);
    loadCorporateData();
    setShowMoveModal(false);
    setPreSelectedVaId(undefined);
  };

  const handleAcquisitionSuccess = (result: any) => {
    console.log('Acquisition successful:', result);
    loadCorporateData();
    setShowAcquisitionWizard(false);
  };

  const handleMergerSuccess = (result: any) => {
    console.log('Merger successful:', result);
    loadCorporateData();
    setShowMergerWizard(false);
  };

  const handleDivestitureSuccess = (result: any) => {
    console.log('Divestiture successful:', result);
    loadCorporateData();
    setShowDivestitureModal(false);
  };

  const selectedCorporate = corporates.find((c) => c.id === selectedCorporateId);

  // Quick-action tiles. Dark-mode icon backgrounds bumped from /10 to /20 so
  // the hue actually reads on the dark navy card — at /10 the swatches were
  // too dim and the row felt monochrome. Hover bumps to /30 for press affordance.
  const quickActions: QuickAction[] = [
    {
      id: 'move-va',
      icon: Package,
      title: 'Relocate Account',
      description: 'Move account to new division',
      onClick: () => { setMoveType('TRANSACTION_VA'); setPreSelectedVaId(undefined); setShowMoveModal(true); },
      color: 'bg-info-50 group-hover:bg-info-100 text-info-600 dark:bg-info-500/20 dark:group-hover:bg-info-500/30 dark:text-info-300'
    },
    {
      id: 'move-aggregation',
      icon: Folder,
      title: 'Move Division',
      description: 'Relocate entire business unit',
      onClick: () => { setMoveType('AGGREGATION'); setPreSelectedVaId(undefined); setShowMoveModal(true); },
      color: 'bg-indigo-50 group-hover:bg-indigo-100 text-indigo-600 dark:bg-indigo-500/20 dark:group-hover:bg-indigo-500/30 dark:text-indigo-300'
    },
    {
      id: 'acquire',
      icon: Building2,
      title: 'Acquisition',
      description: 'Acquire another corporate',
      onClick: () => setShowAcquisitionWizard(true),
      color: 'bg-success-50 group-hover:bg-success-100 text-success-600 dark:bg-success-500/20 dark:group-hover:bg-success-500/30 dark:text-success-300'
    },
    {
      id: 'merge',
      icon: GitMerge,
      title: 'Merger',
      description: 'Combine two corporates',
      onClick: () => setShowMergerWizard(true),
      color: 'bg-purple-50 group-hover:bg-purple-100 text-purple-600 dark:bg-purple-500/20 dark:group-hover:bg-purple-500/30 dark:text-purple-300'
    },
    {
      id: 'divest',
      icon: GitBranch,
      title: 'Divestiture',
      description: 'Spin-off to new corporate',
      onClick: () => setShowDivestitureModal(true),
      color: 'bg-warning-50 group-hover:bg-warning-100 text-warning-600 dark:bg-warning-500/20 dark:group-hover:bg-warning-500/30 dark:text-warning-300'
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
        title="Corporate Reorganization"
        description="Run M&A, divestiture, and corporate restructuring workflows. Move VAs across hierarchy nodes; merge or split entity trees with full audit trail."
      />

      {/* Corporate/Program Filter Bar — uses the shared `<ScopeSelector>`. */}
      <ScopeSelector
        mode="corporate-program"
        corporates={corporates}
        programs={programs}
        selectedCorporateId={selectedCorporateId}
        selectedProgramId={selectedProgramId}
        onCorporateChange={handleCorporateChange}
        onProgramChange={handleProgramChange}
        loading={loadingPrograms}
        disableChildUntilParent
      />

      {/* No Selection State */}
      {(!selectedCorporateId || !selectedProgramId) && (
        <Card className="p-8 text-center">
          <div className="flex flex-col items-center">
            <div className="w-20 h-20 bg-neutral-100 rounded-full flex items-center justify-center mb-4 dark:bg-primary-800">
              <Building className="w-10 h-10 text-neutral-400 dark:text-neutral-500" />
            </div>
            <h2 className="section-title mb-2">
              {!selectedCorporateId ? 'Select a Corporate' : 'Select a Program'}
            </h2>
            <p className="text-neutral-500 max-w-md dark:text-neutral-400">
              {!selectedCorporateId 
                ? 'Choose a corporate from the dropdown above to view its programs and hierarchy operations.'
                : 'Choose a program to view its hierarchy structure and available operations.'
              }
            </p>
          </div>
        </Card>
      )}

      {/* Main Content */}
      {selectedCorporateId && selectedProgramId && (
        <>
          {/* Quick Actions */}
          <Card padding="lg">
            <h2 className="section-title mb-4">Quick Actions</h2>
            <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-5 gap-4">
              {quickActions.map((action) => (<QuickActionCard key={action.id} action={action} />))}
            </div>
          </Card>

          {/* Main Content Grid */}
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
            {/* Hierarchy Tree */}
            <div className="lg:col-span-2">
              <Card padding="none">
                <div className="flex items-center justify-between p-4 border-b border-neutral-200 dark:border-primary-800">
                  <h2 className="font-semibold text-primary-900 dark:text-neutral-50">Current Hierarchy</h2>
                  <div className="flex items-center gap-2">
                    <Button variant="ghost" size="sm" onClick={handleExpandAll}>Expand All</Button>
                    <Button variant="ghost" size="sm" onClick={handleCollapseAll}>Collapse</Button>
                    <Button variant="ghost" size="sm" onClick={handleRefresh} disabled={refreshing}>
                      <RefreshCw className={cn('w-4 h-4', refreshing && 'animate-spin')} />
                    </Button>
                  </div>
                </div>
                <div className="p-4 max-h-[500px] overflow-y-auto">
                  {hierarchyLoading ? (
                    <div className="flex items-center justify-center py-12">
                      <Loader2 className="w-6 h-6 animate-spin text-primary-600 dark:text-primary-200" />
                    </div>
                  ) : hierarchy ? (
                    <TreeNode node={hierarchy} expandedIds={expandedIds} onToggle={handleToggle} onAction={handleNodeAction} />
                  ) : (
                    <div className="text-center py-12 text-neutral-500 dark:text-neutral-400">
                      <Globe className="w-12 h-12 mx-auto mb-3 text-neutral-300 dark:text-neutral-600" />
                      <p>No hierarchy available for this program</p>
                    </div>
                  )}
                </div>
                {hierarchy && (
                  <div className="px-4 py-3 border-t border-neutral-100 bg-neutral-50 dark:border-primary-800/60 dark:bg-primary-950">
                    <div className="flex flex-wrap items-center gap-4 text-xs text-neutral-500 dark:text-neutral-400">
                      <span className="font-medium">Legend:</span>
                      <span className="flex items-center gap-1"><div className="w-3 h-3 rounded bg-primary-900" />ROOT</span>
                      <span className="flex items-center gap-1"><div className="w-3 h-3 rounded bg-info-100 dark:bg-info-500/20" />AGGREGATION</span>
                      <span className="flex items-center gap-1"><div className="w-3 h-3 rounded bg-success-50 dark:bg-success-500/10" />TRANSACTION</span>
                      <span className="flex items-center gap-1"><div className="w-3 h-3 rounded bg-cyan-50 border border-dashed border-cyan-300 dark:bg-cyan-500/10" />MIRROR</span>
                      <span className="flex items-center gap-1"><div className="w-3 h-3 rounded bg-amber-50 border border-amber-300 dark:bg-amber-500/10" />EXCEPTION</span>
                    </div>
                  </div>
                )}
              </Card>
            </div>

            {/* Sidebar */}
            <div className="space-y-6">
              {pendingApprovals.length > 0 && (
                <Card padding="none">
                  <div className="px-4 py-3 border-b border-neutral-200 flex items-center justify-between dark:border-primary-800">
                    <h3 className="font-semibold text-primary-900 dark:text-neutral-50">Pending Approvals</h3>
                    <Badge variant="warning">{pendingApprovals.length}</Badge>
                  </div>
                  <div className="p-4 space-y-3">
                    {pendingApprovals.slice(0, 3).map((op) => (
                      <PendingApprovalCard key={op.id} operation={op} onApprove={handleApprove} onReject={handleReject} />
                    ))}
                    {pendingApprovals.length > 3 && <Button variant="ghost" className="w-full">View All Pending</Button>}
                  </div>
                </Card>
              )}

              <Card padding="none">
                <div className="px-4 py-3 border-b border-neutral-200 flex items-center justify-between dark:border-primary-800">
                  <h3 className="font-semibold text-primary-900 dark:text-neutral-50">Recent Operations</h3>
                  <Button variant="ghost" size="sm">View All</Button>
                </div>
                <div className="divide-y divide-neutral-100 dark:divide-primary-800/60">
                  {history.length === 0 ? (
                    <div className="p-8 text-center text-neutral-500 dark:text-neutral-400">
                      <History className="w-8 h-8 mx-auto mb-2 text-neutral-300 dark:text-neutral-600" />
                      <p className="text-sm">No operations yet</p>
                    </div>
                  ) : (
                    history.slice(0, 5).map((op) => (
                      <OperationHistoryItem key={op.id} operation={op} onViewDetails={(id) => console.log('View details:', id)} />
                    ))
                  )}
                </div>
              </Card>
            </div>
          </div>
        </>
      )}

      {/* ====== ALL MODALS ====== */}
      <RulesModal isOpen={showRulesModal} onClose={() => setShowRulesModal(false)} rules={rules} />
      
      {selectedCorporateId && (
        <>
          <MoveVaModal
            isOpen={showMoveModal}
            onClose={() => { setShowMoveModal(false); setPreSelectedVaId(undefined); }}
            onSuccess={handleMoveSuccess}
            moveType={moveType}
            corporateId={selectedCorporateId}
            preSelectedVaId={preSelectedVaId}
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