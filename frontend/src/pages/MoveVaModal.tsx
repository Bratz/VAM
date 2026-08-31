// ============================================================================
// MOVE VA MODAL COMPONENT
// ============================================================================
// Modal for moving Transaction VAs and Aggregations between parent nodes
// Features:
// - VA/Aggregation selection with search
// - Target parent selection with hierarchy picker
// - Limit transfer policy selection
// - Real-time validation feedback
// - Path preview
// ============================================================================

import React, { useState, useEffect, useCallback } from 'react';
import {
  X,
  Search,
  ChevronRight,
  ChevronDown,
  Check,
  AlertTriangle,
  Info,
  Loader2,
  Wallet,
  Folder,
  Globe,
  Building2,
  ArrowRight,
  Coins,
  Scale,
  RefreshCw,
} from 'lucide-react';
import { cn, formatCurrency } from '../utils';
import { Modal } from '../components/ui/enhanced';
import {
  hierarchyOperationsApi,
  HierarchyNode,
  MoveLimitPolicy,
  MoveLimitPolicyInfo,
  MoveValidationResult,
  MoveTransactionVaRequest,
  MoveAggregationRequest,
  MoveOperationResult,
} from '../services/hierarchyOperationsApi';

// ============================================================================
// TYPES
// ============================================================================

export type MoveType = 'TRANSACTION_VA' | 'AGGREGATION';

interface MoveVaModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: (result: MoveOperationResult) => void;
  moveType: MoveType;
  corporateId?: string;
  preSelectedVaId?: string;
}

// ============================================================================
// HELPER COMPONENTS
// ============================================================================

const Badge: React.FC<{
  variant?: 'default' | 'success' | 'warning' | 'error' | 'info';
  children: React.ReactNode;
  className?: string;
}> = ({ variant = 'default', children, className }) => {
  const variants = {
    default: 'bg-neutral-100 text-neutral-700 dark:bg-primary-800 dark:text-neutral-200',
    success: 'bg-success-100 text-success-700 dark:bg-success-500/20 dark:text-success-300',
    warning: 'bg-warning-100 text-warning-700 dark:bg-warning-500/20 dark:text-warning-300',
    error: 'bg-error-100 text-error-700 dark:bg-error-500/20 dark:text-error-300',
    info: 'bg-info-100 text-info-700 dark:bg-info-500/20 dark:text-info-300',
  };

  return (
    <span className={cn('px-2 py-0.5 text-xs font-medium rounded-full', variants[variant], className)}>
      {children}
    </span>
  );
};

const RadioOption: React.FC<{
  selected: boolean;
  onSelect: () => void;
  title: string;
  description: string;
  recommended?: boolean;
  requiresApproval?: boolean;
}> = ({ selected, onSelect, title, description, recommended, requiresApproval }) => (
  <button
    type="button"
    onClick={onSelect}
    className={cn(
      'w-full text-left p-4 rounded-lg border-2 transition-all',
      selected
        ? 'border-primary-500 bg-primary-50 dark:bg-primary-800/40'
        : 'border-neutral-200 dark:border-primary-800 hover:border-neutral-300 dark:hover:border-primary-700 bg-white dark:bg-primary-900'
    )}
  >
    <div className="flex items-start gap-3">
      <div
        className={cn(
          'w-5 h-5 rounded-full border-2 flex items-center justify-center flex-shrink-0 mt-0.5',
          selected ? 'border-primary-600 bg-primary-600' : 'border-neutral-300 dark:border-primary-700'
        )}
      >
        {selected && <Check className="w-3 h-3 text-white" />}
      </div>
      <div className="flex-1">
        <div className="flex items-center gap-2">
          <span className="font-medium text-primary-900 dark:text-neutral-50">{title}</span>
          {recommended && <Badge variant="success">Recommended</Badge>}
          {requiresApproval && <Badge variant="warning">CFO Approval</Badge>}
        </div>
        <p className="text-sm text-neutral-500 dark:text-neutral-400 mt-1">{description}</p>
      </div>
    </div>
  </button>
);

// ============================================================================
// VA SELECTION LIST
// ============================================================================

interface VaSelectionListProps {
  nodes: HierarchyNode[];
  selectedId: string | null;
  onSelect: (node: HierarchyNode) => void;
  loading: boolean;
  searchQuery: string;
  onSearchChange: (query: string) => void;
  moveType: MoveType;
}

const VaSelectionList: React.FC<VaSelectionListProps> = ({
  nodes,
  selectedId,
  onSelect,
  loading,
  searchQuery,
  onSearchChange,
  moveType,
}) => {
const filteredNodes = nodes.filter((node) => {
  if (!node || !node.name) return false;
  return (
    node.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
    (node.code && node.code.toLowerCase().includes(searchQuery.toLowerCase()))
  );
});

  const getNodeIcon = (node: HierarchyNode) => {
    switch (node.accountCategory) {
      case 'AGGREGATION':
        return <Folder className="w-4 h-4 text-info-600 dark:text-info-300" />;
      case 'ROOT':
        return <Globe className="w-4 h-4 text-primary-700 dark:text-neutral-200" />;
      case 'TRANSACTION':
        return <Wallet className="w-4 h-4 text-success-600 dark:text-success-300" />;
      default:
        return <Wallet className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />;
    }
  };

  // formatCurrency now comes from the shared util (Phase 12 Task D3) — the
  // local 0-dp Intl clone was deleted.

  return (
    <div className="space-y-3">
      <div className="relative">
        <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 w-4 h-4 text-neutral-400 dark:text-neutral-500" />
        <input
          type="text"
          value={searchQuery}
          onChange={(e) => onSearchChange(e.target.value)}
          placeholder={`Search ${moveType === 'TRANSACTION_VA' ? 'accounts' : 'aggregations'}...`}
          className="w-full pl-10 pr-4 py-2.5 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-transparent"
        />
      </div>

      <div className="max-h-64 overflow-y-auto border border-neutral-200 dark:border-primary-800 rounded-lg">
        {loading ? (
          <div className="flex items-center justify-center py-8">
            <Loader2 className="w-5 h-5 animate-spin text-primary-600 dark:text-primary-200" />
            <span className="ml-2 text-sm text-neutral-500 dark:text-neutral-400">Loading...</span>
          </div>
        ) : filteredNodes.length === 0 ? (
          <div className="text-center py-8 text-neutral-500 dark:text-neutral-400">
            <p className="text-sm">No {moveType === 'TRANSACTION_VA' ? 'accounts' : 'aggregations'} found</p>
          </div>
        ) : (
          <div className="divide-y divide-neutral-100 dark:divide-primary-800/60">
            {filteredNodes.map((node) => (
              <button
                key={node.id}
                type="button"
                onClick={() => onSelect(node)}
                className={cn(
                  'w-full px-4 py-3 text-left hover:bg-neutral-50 dark:hover:bg-primary-800/50 transition-colors flex items-center gap-3',
                  selectedId === node.id && 'bg-primary-50 dark:bg-primary-800/40 hover:bg-primary-50 dark:hover:bg-primary-800/40'
                )}
              >
                <div
                  className={cn(
                    'w-5 h-5 rounded-full border-2 flex items-center justify-center flex-shrink-0',
                    selectedId === node.id ? 'border-primary-600 bg-primary-600' : 'border-neutral-300 dark:border-primary-700'
                  )}
                >
                  {selectedId === node.id && <Check className="w-3 h-3 text-white" />}
                </div>

                <div className="p-1.5 rounded-lg bg-neutral-100 dark:bg-primary-800">{getNodeIcon(node)}</div>

                <div className="flex-1 min-w-0">
                  <p className="font-medium text-primary-900 dark:text-neutral-50 truncate">{node.name}</p>
                  <div className="flex items-center gap-2 mt-0.5">
                    {node.code && (
                      <span className="text-xs text-neutral-500 dark:text-neutral-400 font-mono">{node.code}</span>
                    )}
                    <span className="text-xs text-neutral-400 dark:text-neutral-500">•</span>
                    <span className="text-xs text-neutral-500 dark:text-neutral-400">
                      Balance: {formatCurrency(node.balance, node.currencyCode)}
                    </span>
                  </div>
                </div>

                <Badge variant="info">{node.currencyCode}</Badge>

                {selectedId === node.id && (
                  <Check className="w-5 h-5 text-primary-600 dark:text-primary-200 flex-shrink-0" />
                )}
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

// ============================================================================
// PARENT SELECTION TREE
// ============================================================================

interface ParentSelectionTreeProps {
  node: HierarchyNode;
  selectedId: string | null;
  onSelect: (node: HierarchyNode) => void;
  excludeId?: string;
  expandedIds: Set<string>;
  onToggle: (id: string) => void;
  level?: number;
}

const ParentSelectionTree: React.FC<ParentSelectionTreeProps> = ({
  node,
  selectedId,
  onSelect,
  excludeId,
  expandedIds,
  onToggle,
  level = 0,
}) => {
  const isExpanded = expandedIds.has(node.id);
  const hasChildren = node.children && node.children.length > 0;
  const isSelected = selectedId === node.id;
  const isExcluded = node.id === excludeId;
  const canSelect = node.accountCategory === 'AGGREGATION' || node.accountCategory === 'ROOT';

  const getNodeIcon = () => {
    switch (node.accountCategory) {
      case 'ROOT':
        return <Globe className="w-4 h-4 text-white" />;
      case 'AGGREGATION':
        return <Folder className="w-4 h-4 text-info-600 dark:text-info-300" />;
      default:
        return <Wallet className="w-4 h-4 text-success-600 dark:text-success-300" />;
    }
  };

  // Don't render excluded nodes or their children
  if (isExcluded) return null;

  return (
    <div>
      <div
        style={{ paddingLeft: `${level * 20 + 8}px` }}
        className={cn(
          'flex items-center gap-2 py-2 pr-3 rounded-lg cursor-pointer transition-all',
          isSelected && canSelect
            ? 'bg-primary-100 dark:bg-primary-700 ring-2 ring-primary-500'
            : canSelect
            ? 'hover:bg-neutral-50 dark:hover:bg-primary-800/50'
            : 'opacity-50 cursor-not-allowed'
        )}
        onClick={() => canSelect && onSelect(node)}
      >
        {hasChildren ? (
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              onToggle(node.id);
            }}
            className="p-0.5 hover:bg-neutral-200 dark:hover:bg-primary-800 rounded"
          >
            {isExpanded ? (
              <ChevronDown className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
            ) : (
              <ChevronRight className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
            )}
          </button>
        ) : (
          <span className="w-5" />
        )}

        <div
          className={cn(
            'p-1.5 rounded-lg',
            node.accountCategory === 'ROOT' ? 'bg-primary-800' : 'bg-neutral-100 dark:bg-primary-800'
          )}
        >
          {getNodeIcon()}
        </div>

        <span
          className={cn(
            'flex-1 text-sm font-medium truncate',
            isSelected && canSelect ? 'text-primary-700 dark:text-neutral-200' : 'text-primary-900 dark:text-neutral-50'
          )}
        >
          {node.name}
        </span>

        {canSelect && (
          <div
            className={cn(
              'w-4 h-4 rounded-full border-2 flex items-center justify-center flex-shrink-0',
              isSelected ? 'border-primary-600 bg-primary-600' : 'border-neutral-300 dark:border-primary-700'
            )}
          >
            {isSelected && <Check className="w-2.5 h-2.5 text-white" />}
          </div>
        )}
      </div>

      {hasChildren && isExpanded && (
        <div>
          {node.children!.map((child) => (
            <ParentSelectionTree
              key={child.id}
              node={child}
              selectedId={selectedId}
              onSelect={onSelect}
              excludeId={excludeId}
              expandedIds={expandedIds}
              onToggle={onToggle}
              level={level + 1}
            />
          ))}
        </div>
      )}
    </div>
  );
};

// ============================================================================
// VALIDATION PANEL
// ============================================================================

interface ValidationPanelProps {
  validation: MoveValidationResult | null;
  loading: boolean;
}

const ValidationPanel: React.FC<ValidationPanelProps> = ({ validation, loading }) => {
  if (loading) {
    return (
      <div className="flex items-center gap-2 p-4 bg-neutral-50 dark:bg-primary-950 rounded-lg">
        <Loader2 className="w-4 h-4 animate-spin text-primary-600 dark:text-primary-200" />
        <span className="text-sm text-neutral-600 dark:text-neutral-300">Validating...</span>
      </div>
    );
  }

  if (!validation) return null;

  return (
    <div className="space-y-2 p-4 bg-neutral-50 dark:bg-primary-950 rounded-lg">
      <div className="flex items-center gap-2 mb-3">
        <span className="field-label">Validation Results</span>
        {validation.valid ? (
          <Badge variant="success">Valid</Badge>
        ) : (
          <Badge variant="error">Invalid</Badge>
        )}
      </div>

      {validation.errors.length > 0 && (
        <div className="space-y-1">
          {validation.errors.map((error, i) => (
            <div key={i} className="flex items-start gap-2 text-sm text-error-600 dark:text-error-300">
              <AlertTriangle className="w-4 h-4 mt-0.5 flex-shrink-0" />
              <span>{error}</span>
            </div>
          ))}
        </div>
      )}

      {validation.warnings.length > 0 && (
        <div className="space-y-1">
          {validation.warnings.map((warning, i) => (
            <div key={i} className="flex items-start gap-2 text-sm text-warning-600 dark:text-warning-300">
              <Info className="w-4 h-4 mt-0.5 flex-shrink-0" />
              <span>{warning}</span>
            </div>
          ))}
        </div>
      )}

      <div className="grid grid-cols-2 gap-3 pt-2 border-t border-neutral-200 dark:border-primary-800 mt-3">
        {validation.currencyMirrorWillBeCreated && (
          <div className="flex items-center gap-2 text-sm text-cyan-600 dark:text-cyan-300">
            <Coins className="w-4 h-4" />
            <span>Currency mirror will be created</span>
          </div>
        )}

        {validation.settlementVaWillReResolve && (
          <div className="flex items-center gap-2 text-sm text-cat-2">
            <Scale className="w-4 h-4" />
            <span>Settlement VA will re-resolve</span>
          </div>
        )}

        {validation.limitImpact && (
          <div className="col-span-2 flex items-center gap-2 text-sm text-primary-600 dark:text-primary-200">
            <ArrowRight className="w-4 h-4" />
            <span>
              Limit transfer: {validation.limitImpact.amountToTransfer.toLocaleString()}{' '}
              {validation.limitImpact.currency}
            </span>
          </div>
        )}
      </div>
    </div>
  );
};

// ============================================================================
// MAIN COMPONENT
// ============================================================================

export const MoveVaModal: React.FC<MoveVaModalProps> = ({
  isOpen,
  onClose,
  onSuccess,
  moveType,
  corporateId,
  preSelectedVaId,
}) => {
  // State
  const [step, setStep] = useState<1 | 2 | 3>(1);
  const [movableNodes, setMovableNodes] = useState<HierarchyNode[]>([]);
  const [hierarchyRoot, setHierarchyRoot] = useState<HierarchyNode | null>(null);
  const [selectedVa, setSelectedVa] = useState<HierarchyNode | null>(null);
  const [selectedParent, setSelectedParent] = useState<HierarchyNode | null>(null);
  const [policies, setPolicies] = useState<MoveLimitPolicyInfo[]>([]);
  const [selectedPolicy, setSelectedPolicy] = useState<MoveLimitPolicy>('TRANSFER_WITH_VA');
  const [validation, setValidation] = useState<MoveValidationResult | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());
  const [loading, setLoading] = useState(false);
  const [validating, setValidating] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Load initial data
  const loadData = useCallback(async () => {
    setLoading(true);
    setError(null);

    try {
      // Load movable nodes
      const nodesRes =
        moveType === 'TRANSACTION_VA'
          ? await hierarchyOperationsApi.getMovableVas(corporateId)
          : await hierarchyOperationsApi.getMovableAggregations(corporateId);

      if (nodesRes.success && nodesRes.data) {
        setMovableNodes(nodesRes.data);
      }

      // Load hierarchy for parent selection
      const hierarchyRes = await hierarchyOperationsApi.getHierarchy(corporateId);
      if (hierarchyRes.success && hierarchyRes.data) {
        setHierarchyRoot(hierarchyRes.data);
        // Expand first two levels
        const ids = new Set<string>();
        ids.add(hierarchyRes.data.id);
        hierarchyRes.data.children?.forEach((child) => ids.add(child.id));
        setExpandedIds(ids);
      }

      // Load policies
      const policiesRes = await hierarchyOperationsApi.getMovePolicies();
      if (policiesRes.success && policiesRes.data) {
        setPolicies(policiesRes.data);
        // Set recommended as default
        const recommended = policiesRes.data.find((p) => p.recommended);
        if (recommended) setSelectedPolicy(recommended.policy);
      }

      // Pre-select VA if provided
      if (preSelectedVaId && nodesRes.data) {
        const preSelected = nodesRes.data.find((n) => n.id === preSelectedVaId);
        if (preSelected) {
          setSelectedVa(preSelected);
          setStep(2);
        }
      }
    } catch (err) {
      setError('Failed to load data. Please try again.');
    } finally {
      setLoading(false);
    }
  }, [corporateId, moveType, preSelectedVaId]);

  useEffect(() => {
    if (isOpen) {
      loadData();
    } else {
      // Reset state when modal closes
      setStep(1);
      setSelectedVa(null);
      setSelectedParent(null);
      setValidation(null);
      setSearchQuery('');
      setError(null);
    }
  }, [isOpen, loadData]);

  // Validate move when parent is selected
  useEffect(() => {
    const validate = async () => {
      if (!selectedVa || !selectedParent) {
        setValidation(null);
        return;
      }

      setValidating(true);
      try {
        const res = await hierarchyOperationsApi.validateMove({
          vaId: selectedVa.id,
          newParentId: selectedParent.id,
          limitPolicy: selectedPolicy,
        });

        if (res.success && res.data) {
          setValidation(res.data);
        }
      } catch (err) {
        console.error('Validation failed:', err);
      } finally {
        setValidating(false);
      }
    };

    validate();
  }, [selectedVa, selectedParent, selectedPolicy]);

  // Handle toggle expand
  const handleToggle = (id: string) => {
    const newExpanded = new Set(expandedIds);
    if (newExpanded.has(id)) {
      newExpanded.delete(id);
    } else {
      newExpanded.add(id);
    }
    setExpandedIds(newExpanded);
  };

  // Handle submission
  const handleSubmit = async () => {
    if (!selectedVa || !selectedParent) return;

    setSubmitting(true);
    setError(null);

    try {
      let result;

      if (moveType === 'TRANSACTION_VA') {
        const request: MoveTransactionVaRequest = {
          vaId: selectedVa.id,
          newParentId: selectedParent.id,
          limitPolicy: selectedPolicy,
        };
        result = await hierarchyOperationsApi.moveTransactionVa(request);
      } else {
        const request: MoveAggregationRequest = {
          aggregationId: selectedVa.id,
          newParentId: selectedParent.id,
          limitPolicy: selectedPolicy,
        };
        result = await hierarchyOperationsApi.moveAggregation(request);
      }

      if (result.success && result.data) {
        onSuccess(result.data);
        onClose();
      } else {
        setError(result.error || 'Operation failed. Please try again.');
      }
    } catch (err) {
      setError('An unexpected error occurred. Please try again.');
    } finally {
      setSubmitting(false);
    }
  };

  // Get path preview
  const getPathPreview = () => {
    if (!hierarchyRoot || !selectedParent || !selectedVa) return null;

    // Build path from root to selected parent
    const buildPath = (node: HierarchyNode, targetId: string, path: string[] = []): string[] | null => {
      if (node.id === targetId) {
        return [...path, node.name];
      }

      if (node.children) {
        for (const child of node.children) {
          const result = buildPath(child, targetId, [...path, node.name]);
          if (result) return result;
        }
      }

      return null;
    };

    const parentPath = buildPath(hierarchyRoot, selectedParent.id) || [selectedParent.name];

    return [...parentPath, `[${selectedVa.name}]`].join(' → ');
  };

  // Phase 10 follow-up (2026-05-13): hand-rolled wrapper → <Modal size="lg">.
  // Title + subtitle pass via Modal props. Progress steps + content + footer
  // stay inside children with -mx-6 -my-6.
  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      size="lg"
      title={`Move ${moveType === 'TRANSACTION_VA' ? 'Virtual Account' : 'Aggregation'}`}
      subtitle={
        moveType === 'TRANSACTION_VA'
          ? 'Move a transaction VA to a new parent aggregation'
          : 'Move an aggregation with all its children'
      }
    >
      <div className="-mx-6 -my-6">
        {/* Progress Steps */}
          <div className="px-6 py-3 border-b border-neutral-100 dark:border-primary-800/60 bg-neutral-50 dark:bg-primary-950">
            <div className="flex items-center gap-3">
              {[
                { num: 1, label: `Select ${moveType === 'TRANSACTION_VA' ? 'VA' : 'Aggregation'}` },
                { num: 2, label: 'Select Target' },
                { num: 3, label: 'Review & Move' },
              ].map(({ num, label }, i) => (
                <React.Fragment key={num}>
                  <button
                    onClick={() => num < step && setStep(num as 1 | 2 | 3)}
                    disabled={num > step}
                    className={cn(
                      'flex items-center gap-2',
                      num <= step ? 'cursor-pointer' : 'cursor-not-allowed opacity-50'
                    )}
                  >
                    <div
                      className={cn(
                        'w-6 h-6 rounded-full flex items-center justify-center text-xs font-medium',
                        num === step
                          ? 'bg-primary-600 text-white'
                          : num < step
                          ? 'bg-success-500 text-white'
                          : 'bg-neutral-200 dark:bg-primary-800 text-neutral-500 dark:text-neutral-400'
                      )}
                    >
                      {num < step ? <Check className="w-3 h-3" /> : num}
                    </div>
                    <span
                      className={cn(
                        'text-sm font-medium',
                        num === step ? 'text-primary-900 dark:text-neutral-50' : 'text-neutral-500 dark:text-neutral-400'
                      )}
                    >
                      {label}
                    </span>
                  </button>
                  {i < 2 && <ChevronRight className="w-4 h-4 text-neutral-300 dark:text-neutral-600" />}
                </React.Fragment>
              ))}
            </div>
          </div>

          {/* Content */}
          <div className="p-6 max-h-[60vh] overflow-y-auto">
            {loading ? (
              <div className="flex items-center justify-center py-12">
                <Loader2 className="w-8 h-8 animate-spin text-primary-600 dark:text-primary-200" />
              </div>
            ) : error ? (
              <div className="text-center py-8">
                <AlertTriangle className="w-12 h-12 text-error-500 mx-auto mb-3" />
                <p className="text-error-600 dark:text-error-300">{error}</p>
                <button
                  onClick={loadData}
                  className="mt-4 px-4 py-2 text-sm text-primary-600 dark:text-primary-200 hover:bg-primary-50 dark:hover:bg-primary-800/40 rounded-lg"
                >
                  <RefreshCw className="w-4 h-4 inline mr-2" />
                  Retry
                </button>
              </div>
            ) : (
              <>
                {/* Step 1: Select VA/Aggregation */}
                {step === 1 && (
                  <div className="space-y-4">
                    <label className="field-label block">
                      Select {moveType === 'TRANSACTION_VA' ? 'Virtual Account' : 'Aggregation'} to Move *
                    </label>
                    <VaSelectionList
                      nodes={movableNodes}
                      selectedId={selectedVa?.id || null}
                      onSelect={(node) => setSelectedVa(node)}
                      loading={false}
                      searchQuery={searchQuery}
                      onSearchChange={setSearchQuery}
                      moveType={moveType}
                    />

                    {selectedVa && (
                      <div className="p-3 bg-primary-50 dark:bg-primary-800/40 rounded-lg border border-primary-200 dark:border-primary-700">
                        <div className="flex items-center gap-2">
                          <Check className="w-4 h-4 text-primary-600 dark:text-primary-200" />
                          <span className="text-sm font-medium text-primary-700 dark:text-neutral-200">
                            Selected: {selectedVa.name}
                          </span>
                        </div>
                        <p className="text-xs text-primary-600 dark:text-primary-200 mt-1 ml-6">
                          Currency: {selectedVa.currencyCode} • Balance:{' '}
                          {selectedVa.balance.toLocaleString()}
                        </p>
                      </div>
                    )}
                  </div>
                )}

                {/* Step 2: Select Target Parent */}
                {step === 2 && (
                  <div className="space-y-4">
                    <label className="field-label block">
                      Select New Parent *
                    </label>

                    {hierarchyRoot ? (
                      <div className="border border-neutral-200 dark:border-primary-800 rounded-lg max-h-64 overflow-y-auto">
                        <ParentSelectionTree
                          node={hierarchyRoot}
                          selectedId={selectedParent?.id || null}
                          onSelect={setSelectedParent}
                          excludeId={selectedVa?.id}
                          expandedIds={expandedIds}
                          onToggle={handleToggle}
                        />
                      </div>
                    ) : (
                      <div className="text-center py-8 text-neutral-500 dark:text-neutral-400">
                        No hierarchy available
                      </div>
                    )}

                    {selectedParent && (
                      <div className="p-3 bg-info-50 dark:bg-info-500/10 rounded-lg border border-info-200 dark:border-info-500/30">
                        <p className="text-sm font-medium text-info-700 dark:text-info-300">Path Preview:</p>
                        <p className="text-sm text-info-600 dark:text-info-300 mt-1 font-mono">{getPathPreview()}</p>
                      </div>
                    )}
                  </div>
                )}

                {/* Step 3: Review & Configure Policy */}
                {step === 3 && (
                  <div className="space-y-6">
                    {/* Summary */}
                    <div className="p-4 bg-neutral-50 dark:bg-primary-950 rounded-lg">
                      <h4 className="text-sm font-semibold text-neutral-700 dark:text-neutral-200 mb-3">Move Summary</h4>
                      <div className="space-y-2 text-sm">
                        <div className="flex justify-between">
                          <span className="text-neutral-500 dark:text-neutral-400">Moving:</span>
                          <span className="font-medium text-primary-900 dark:text-neutral-50">{selectedVa?.name}</span>
                        </div>
                        <div className="flex justify-between">
                          <span className="text-neutral-500 dark:text-neutral-400">To:</span>
                          <span className="font-medium text-primary-900 dark:text-neutral-50">{selectedParent?.name}</span>
                        </div>
                        <div className="flex justify-between">
                          <span className="text-neutral-500 dark:text-neutral-400">Currency:</span>
                          <Badge variant="info">{selectedVa?.currencyCode}</Badge>
                        </div>
                      </div>
                    </div>

                    {/* Policy Selection */}
                    <div>
                      <label className="field-label block mb-3">
                        Limit Transfer Policy *
                      </label>
                      <div className="space-y-2">
                        {policies.map((policy) => (
                          <RadioOption
                            key={policy.policy}
                            selected={selectedPolicy === policy.policy}
                            onSelect={() => setSelectedPolicy(policy.policy)}
                            title={policy.name}
                            description={policy.description}
                            recommended={policy.recommended}
                            requiresApproval={policy.requiresApproval}
                          />
                        ))}
                      </div>
                    </div>

                    {/* Validation Results */}
                    <ValidationPanel validation={validation} loading={validating} />

                    {/* Warning for invalid */}
                    {validation && !validation.valid && (
                      <div className="p-4 bg-error-50 dark:bg-error-500/10 border border-error-200 dark:border-error-500/30 rounded-lg">
                        <div className="flex items-start gap-3">
                          <AlertTriangle className="w-5 h-5 text-error-600 dark:text-error-300 mt-0.5 flex-shrink-0" />
                          <div>
                            <p className="text-sm font-medium text-error-800 dark:text-error-300">
                              Cannot proceed with this move
                            </p>
                            <p className="text-sm text-error-600 dark:text-error-300 mt-1">
                              Please resolve the validation errors above before continuing.
                            </p>
                          </div>
                        </div>
                      </div>
                    )}
                  </div>
                )}
              </>
            )}
          </div>

          {/* Footer */}
          <div className="px-6 py-4 border-t border-neutral-200 dark:border-primary-800 bg-neutral-50 dark:bg-primary-950 flex items-center justify-between">
            <button
              onClick={() => (step === 1 ? onClose() : setStep((step - 1) as 1 | 2))}
              className="px-4 py-2 field-label hover:bg-neutral-100 dark:hover:bg-primary-800 rounded-lg transition-colors"
            >
              {step === 1 ? 'Cancel' : '← Back'}
            </button>

            <div className="flex items-center gap-3">
              {step < 3 ? (
                <button
                  onClick={() => setStep((step + 1) as 2 | 3)}
                  disabled={
                    (step === 1 && !selectedVa) ||
                    (step === 2 && !selectedParent)
                  }
                  className={cn(
                    'px-4 py-2 text-sm font-medium rounded-lg transition-colors',
                    (step === 1 && !selectedVa) || (step === 2 && !selectedParent)
                      ? 'bg-neutral-200 dark:bg-primary-800 text-neutral-400 dark:text-neutral-500 cursor-not-allowed'
                      : 'bg-primary-600 text-white hover:bg-primary-700'
                  )}
                >
                  Next →
                </button>
              ) : (
                <button
                  onClick={handleSubmit}
                  disabled={submitting || validating || (validation && !validation.valid)}
                  className={cn(
                    'px-6 py-2 text-sm font-medium rounded-lg transition-colors flex items-center gap-2',
                    submitting || validating || (validation && !validation.valid)
                      ? 'bg-neutral-200 dark:bg-primary-800 text-neutral-400 dark:text-neutral-500 cursor-not-allowed'
                      : 'bg-primary-600 text-white hover:bg-primary-700'
                  )}
                >
                  {submitting && <Loader2 className="w-4 h-4 animate-spin" />}
                  Move {moveType === 'TRANSACTION_VA' ? 'VA' : 'Aggregation'}
                </button>
              )}
            </div>
          </div>
        </div>
    </Modal>
  );
};

export default MoveVaModal;