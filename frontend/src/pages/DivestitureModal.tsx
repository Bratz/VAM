// ============================================================================
// DIVESTITURE MODAL COMPONENT
// ============================================================================
// Modal for spinning off an aggregation to become a new corporate entity
// ============================================================================

import React, { useState, useEffect, useCallback } from 'react';
import {
  X, Check, AlertTriangle, Info, Loader2, Building2, Globe, Folder, GitBranch,
  ChevronRight, ChevronDown,
} from 'lucide-react';
import { cn } from '../utils';
import { Modal } from '../components/ui/enhanced';
import {
  hierarchyOperationsApi, HierarchyNode, MoveLimitPolicy, DivestitureRequest, MergeOperationResult,
} from '../services/hierarchyOperationsApi';

interface DivestitureModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: (result: MergeOperationResult) => void;
  corporateId: string;
  corporateName?: string;
}

interface MovePolicyOption {
  policy: MoveLimitPolicy;
  name: string;
  description: string;
  requiresApproval: boolean;
}

const Badge: React.FC<{ variant?: 'default' | 'success' | 'warning' | 'error' | 'info' | 'orange'; children: React.ReactNode }> = ({ variant = 'default', children }) => {
  const variants = {
    default: 'bg-neutral-100 text-neutral-700 dark:bg-primary-800 dark:text-neutral-200', success: 'bg-success-100 text-success-700 dark:bg-success-500/20 dark:text-success-300',
    warning: 'bg-amber-100 text-amber-700 dark:bg-amber-500/20 dark:text-amber-300', error: 'bg-error-100 text-error-700 dark:bg-error-500/20 dark:text-error-300',
    info: 'bg-info-100 text-info-700 dark:bg-info-500/20 dark:text-info-300', orange: 'bg-warning-100 text-warning-700 dark:bg-warning-500/20 dark:text-warning-300',
  };
  return <span className={cn('px-2 py-0.5 text-xs font-medium rounded-full', variants[variant])}>{children}</span>;
};

interface AggregationNodeProps {
  node: HierarchyNode;
  selectedId: string | null;
  onSelect: (node: HierarchyNode) => void;
  expandedIds: Set<string>;
  onToggle: (id: string) => void;
  level?: number;
}

const AggregationNode: React.FC<AggregationNodeProps> = ({ node, selectedId, onSelect, expandedIds, onToggle, level = 0 }) => {
  const isExpanded = expandedIds.has(node.id);
  const isSelected = selectedId === node.id;
  const isAggregation = node.accountCategory === 'AGGREGATION';
  const aggregationChildren = node.children?.filter((c) => c.accountCategory === 'AGGREGATION') || [];

  const countVas = (n: HierarchyNode): number => {
    let count = n.accountCategory === 'TRANSACTION' ? 1 : 0;
    n.children?.forEach((child) => { count += countVas(child); });
    return count;
  };

  if (!isAggregation) return null;

  return (
    <div>
      <div
        className={cn('flex items-center gap-2 py-2 px-3 rounded-lg transition-colors cursor-pointer',
          isSelected ? 'bg-warning-50 dark:bg-warning-500/10 border-2 border-warning-400 dark:border-warning-500/30' : 'hover:bg-neutral-50 dark:hover:bg-primary-800/50 border-2 border-transparent'
        )}
        style={{ marginLeft: `${level * 20}px` }}
        onClick={() => onSelect(node)}
      >
        {aggregationChildren.length > 0 ? (
          <button onClick={(e) => { e.stopPropagation(); onToggle(node.id); }} className="p-0.5 hover:bg-neutral-200 dark:hover:bg-primary-800 rounded">
            {isExpanded ? <ChevronDown className="w-4 h-4 text-neutral-500 dark:text-neutral-400" /> : <ChevronRight className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />}
          </button>
        ) : <span className="w-5" />}
        <div className={cn('w-5 h-5 rounded-full border-2 flex items-center justify-center flex-shrink-0', isSelected ? 'border-warning-600 bg-warning-600' : 'border-neutral-300 dark:border-primary-700')}>
          {isSelected && <Check className="w-3 h-3 text-white" />}
        </div>
        <Folder className="w-4 h-4 text-info-600 dark:text-info-300" />
        <span className="flex-1 text-sm font-medium text-primary-900 dark:text-neutral-50 truncate">{node.name}</span>
        <Badge variant="info">{node.currencyCode}</Badge>
        <span className="text-xs text-neutral-500 dark:text-neutral-400">{countVas(node)} VAs</span>
      </div>
      {aggregationChildren.length > 0 && isExpanded && (
        <div>{aggregationChildren.map((child) => (
          <AggregationNode key={child.id} node={child} selectedId={selectedId} onSelect={onSelect} expandedIds={expandedIds} onToggle={onToggle} level={level + 1} />
        ))}</div>
      )}
    </div>
  );
};

export const DivestitureModal: React.FC<DivestitureModalProps> = ({ isOpen, onClose, onSuccess, corporateId, corporateName }) => {
  const [hierarchy, setHierarchy] = useState<HierarchyNode | null>(null);
  const [policies, setPolicies] = useState<MovePolicyOption[]>([]);
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());
  const [selectedAggregation, setSelectedAggregation] = useState<HierarchyNode | null>(null);
  const [newCorporateName, setNewCorporateName] = useState('');
  const [newCorporateCode, setNewCorporateCode] = useState('');
  const [selectedPolicy, setSelectedPolicy] = useState<MoveLimitPolicy>('TRANSFER_WITH_VA');
  const [confirmApproval, setConfirmApproval] = useState(false);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const [hierarchyRes, policiesRes] = await Promise.all([
        hierarchyOperationsApi.getHierarchy(corporateId),
        hierarchyOperationsApi.getMovePolicies(),
      ]);
      if (hierarchyRes.success && hierarchyRes.data) {
        setHierarchy(hierarchyRes.data);
        const ids = new Set<string>();
        ids.add(hierarchyRes.data.id);
        hierarchyRes.data.children?.forEach((child) => ids.add(child.id));
        setExpandedIds(ids);
      }
      if (policiesRes.success && policiesRes.data) setPolicies(policiesRes.data);
    } catch (err) { setError('Failed to load data'); }
    finally { setLoading(false); }
  }, [corporateId]);

  useEffect(() => {
    if (isOpen) loadData();
    else {
      setSelectedAggregation(null); setNewCorporateName(''); setNewCorporateCode('');
      setSelectedPolicy('TRANSFER_WITH_VA'); setConfirmApproval(false); setError(null);
    }
  }, [isOpen, loadData]);

  // Phase 10 Task E (2026-05-13): the previous Tier 5 a11y patch (a
  // hand-rolled escape-key + body-scroll-lock useEffect) is now redundant
  // — the modal wraps in <Modal> below which provides both for free.

  useEffect(() => {
    if (selectedAggregation && !newCorporateName) setNewCorporateName(`${selectedAggregation.name} (Divested)`);
  }, [selectedAggregation]);

  const handleToggle = (id: string) => {
    const newExpanded = new Set(expandedIds);
    if (newExpanded.has(id)) newExpanded.delete(id); else newExpanded.add(id);
    setExpandedIds(newExpanded);
  };

  const aggregations = hierarchy?.children?.filter((c) => c.accountCategory === 'AGGREGATION') || [];

  const countVas = (n: HierarchyNode): number => {
    let count = n.accountCategory === 'TRANSACTION' ? 1 : 0;
    n.children?.forEach((child) => { count += countVas(child); });
    return count;
  };

  const handleSubmit = async () => {
    if (!selectedAggregation) return;
    setSubmitting(true); setError(null);
    try {
      const request: DivestitureRequest = {
        sourceCorporateId: corporateId, aggregationId: selectedAggregation.id,
        newCorporateName, newCorporateCode: newCorporateCode || undefined,
        limitPolicy: selectedPolicy, approvedBy: 'current-user',
      };
      const result = await hierarchyOperationsApi.divestAggregation(request);
      if (result.success && result.data) { onSuccess(result.data); onClose(); }
      else setError(result.error || 'Failed to execute divestiture');
    } catch (err) { setError('An unexpected error occurred'); }
    finally { setSubmitting(false); }
  };

  const canSubmit = selectedAggregation !== null && newCorporateName.trim().length > 0 && confirmApproval;

  // Phase 10 Task E: hand-rolled wrapper → <Modal size="lg">. Header
  // (icon + title) moves into Modal's title prop. Content + footer remain
  // inside children.
  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      size="lg"
      title={
        <div className="flex items-center gap-3">
          <div className="p-2 bg-warning-100 dark:bg-warning-500/20 rounded-lg">
            <GitBranch className="w-5 h-5 text-warning-600 dark:text-warning-300" />
          </div>
          <span>Divest Aggregation</span>
        </div>
      }
      subtitle="Spin off an aggregation to become a new corporate"
    >
      <div className="-mx-6 -my-6 flex flex-col">
        {/* Content */}
        <div className="p-6 max-h-[70vh] overflow-y-auto space-y-6">
            {loading ? (
              <div className="flex items-center justify-center py-12"><Loader2 className="w-8 h-8 animate-spin text-warning-600 dark:text-warning-300" /></div>
            ) : (
              <>
                <div className="p-4 bg-warning-50 dark:bg-warning-500/10 rounded-lg border border-warning-200 dark:border-warning-500/30">
                  <div className="flex items-start gap-3">
                    <Info className="w-5 h-5 text-warning-600 dark:text-warning-300 mt-0.5" />
                    <div>
                      <p className="text-sm font-medium text-warning-800 dark:text-warning-300">About Divestiture</p>
                      <p className="text-sm text-warning-600 dark:text-warning-300 mt-1">The selected aggregation will become the ROOT of a new corporate entity. All child VAs will be migrated.</p>
                    </div>
                  </div>
                </div>

                <div className="p-4 bg-neutral-50 dark:bg-primary-950 rounded-lg border border-neutral-200 dark:border-primary-800">
                  <p className="field-label mb-2">Source Corporate</p>
                  <div className="flex items-center gap-3">
                    <Building2 className="w-5 h-5 text-primary-600 dark:text-primary-200" />
                    <span className="font-semibold text-primary-900 dark:text-neutral-50">{corporateName || 'Current Corporate'}</span>
                  </div>
                </div>

                <div>
                  <label className="field-label block mb-2">Select Aggregation to Divest *</label>
                  {aggregations.length === 0 ? (
                    <div className="text-center py-8 bg-neutral-50 dark:bg-primary-950 rounded-lg">
                      <Folder className="w-12 h-12 text-neutral-300 dark:text-neutral-600 mx-auto mb-3" />
                      <p className="text-neutral-500 dark:text-neutral-400">No aggregations available for divestiture</p>
                    </div>
                  ) : (
                    <div className="border border-neutral-200 dark:border-primary-800 rounded-lg p-3 max-h-48 overflow-y-auto">
                      {aggregations.map((agg) => (
                        <AggregationNode key={agg.id} node={agg} selectedId={selectedAggregation?.id || null} onSelect={setSelectedAggregation} expandedIds={expandedIds} onToggle={handleToggle} />
                      ))}
                    </div>
                  )}
                </div>

                {selectedAggregation && (
                  <>
                    <div>
                      <label className="field-label block mb-2">New Corporate Name *</label>
                      <input type="text" value={newCorporateName} onChange={(e) => setNewCorporateName(e.target.value)} placeholder="e.g., Divested Entity Holdings" className="w-full px-4 py-2.5 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-warning-500" />
                    </div>

                    <div>
                      <label className="field-label block mb-2">Corporate Code (Optional)</label>
                      <input type="text" value={newCorporateCode} onChange={(e) => setNewCorporateCode(e.target.value.toUpperCase())} placeholder="e.g., DIV-2024" className="w-full px-4 py-2.5 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm font-mono focus:outline-none focus:ring-2 focus:ring-warning-500" />
                    </div>

                    <div>
                      <label className="field-label block mb-2">Limit Transfer Policy</label>
                      <select value={selectedPolicy} onChange={(e) => setSelectedPolicy(e.target.value as MoveLimitPolicy)} className="w-full px-4 py-2.5 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-warning-500">
                        {policies.map((policy) => (<option key={policy.policy} value={policy.policy}>{policy.name}</option>))}
                      </select>
                      <p className="text-xs text-neutral-500 dark:text-neutral-400 mt-1">{policies.find((p) => p.policy === selectedPolicy)?.description}</p>
                    </div>

                    {/* Preview */}
                    <div className="p-4 bg-warning-50 dark:bg-warning-500/10 rounded-lg border border-warning-200 dark:border-warning-500/30">
                      <p className="text-sm font-medium text-warning-700 dark:text-warning-300 mb-3">Post-Divestiture Structure:</p>
                      <div className="grid grid-cols-2 gap-4">
                        <div className="p-3 bg-white dark:bg-primary-900 rounded-lg">
                          <p className="text-xs font-medium text-neutral-500 dark:text-neutral-400 mb-2">Before</p>
                          <div className="font-mono text-xs space-y-1">
                            <div className="flex items-center gap-1"><Globe className="w-3 h-3 text-primary-700 dark:text-neutral-200" /><span className="text-primary-700 dark:text-neutral-200">Parent Corporate</span></div>
                            <div className="ml-4 flex items-center gap-1 text-info-600 dark:text-info-300">└─ <Folder className="w-3 h-3" /> {selectedAggregation.name}</div>
                          </div>
                        </div>
                        <div className="p-3 bg-white dark:bg-primary-900 rounded-lg">
                          <p className="text-xs font-medium text-neutral-500 dark:text-neutral-400 mb-2">After</p>
                          <div className="font-mono text-xs space-y-1">
                            <div className="flex items-center gap-1"><Globe className="w-3 h-3 text-warning-700 dark:text-warning-300" /><span className="text-warning-700 dark:text-warning-300 font-medium">{newCorporateName || '[New Corp]'}</span><Badge variant="success">New</Badge></div>
                            <div className="ml-4 text-neutral-500 dark:text-neutral-400">└─ {countVas(selectedAggregation)} VAs</div>
                          </div>
                        </div>
                      </div>
                    </div>

                    <div className="p-4 bg-success-50 dark:bg-success-500/10 rounded-lg border border-success-200 dark:border-success-500/30">
                      <h4 className="font-medium text-success-800 dark:text-success-300 mb-2">Changes to be made:</h4>
                      <ul className="space-y-1 text-sm text-success-700 dark:text-success-300">
                        <li className="flex items-center gap-2"><Check className="w-4 h-4" />New corporate entity created</li>
                        <li className="flex items-center gap-2"><Check className="w-4 h-4" />Aggregation becomes ROOT of new corporate</li>
                        <li className="flex items-center gap-2"><Check className="w-4 h-4" />{countVas(selectedAggregation)} VAs migrated</li>
                      </ul>
                    </div>

                    <div className="p-4 bg-amber-50 dark:bg-amber-500/10 rounded-lg border border-amber-200 dark:border-amber-500/30">
                      <div className="flex items-start gap-3">
                        <AlertTriangle className="w-5 h-5 text-amber-600 dark:text-amber-300 mt-0.5" />
                        <div>
                          <p className="text-sm font-medium text-amber-800 dark:text-amber-300">This operation will:</p>
                          <ul className="text-sm text-amber-700 dark:text-amber-300 mt-1 space-y-1">
                            <li>• Remove aggregation from current hierarchy</li>
                            <li>• Create new corporate with own hierarchy</li>
                            <li>• Update corporate_id for all child VAs</li>
                          </ul>
                        </div>
                      </div>
                    </div>

                    <label className="flex items-start gap-3 p-4 bg-white dark:bg-primary-900 border border-neutral-200 dark:border-primary-800 rounded-lg cursor-pointer hover:bg-neutral-50 dark:hover:bg-primary-800/50">
                      <input type="checkbox" checked={confirmApproval} onChange={(e) => setConfirmApproval(e.target.checked)} className="mt-1 w-4 h-4 text-warning-600 rounded border-neutral-300 dark:border-primary-700 focus:ring-warning-500 dark:text-warning-300" />
                      <span className="text-sm text-neutral-700 dark:text-neutral-200">I confirm this divestiture has been approved and all requirements have been met.</span>
                    </label>

                    {error && (
                      <div className="p-4 bg-error-50 dark:bg-error-500/10 border border-error-200 dark:border-error-500/30 rounded-lg">
                        <div className="flex items-center gap-2 text-error-700 dark:text-error-300"><AlertTriangle className="w-5 h-5" /><span className="text-sm font-medium">{error}</span></div>
                      </div>
                    )}
                  </>
                )}
              </>
            )}
          </div>

          {/* Footer */}
          <div className="px-6 py-4 border-t border-neutral-200 dark:border-primary-800 bg-neutral-50 dark:bg-primary-950 flex justify-between">
            <button onClick={onClose} className="px-4 py-2 field-label hover:bg-neutral-100 dark:hover:bg-primary-800 rounded-lg">Cancel</button>
            <button onClick={handleSubmit} disabled={!canSubmit || submitting} className={cn('px-6 py-2 text-sm font-medium rounded-lg flex items-center gap-2', canSubmit && !submitting ? 'bg-warning-600 text-white hover:bg-warning-700' : 'bg-neutral-200 dark:bg-primary-800 text-neutral-400 dark:text-neutral-500 cursor-not-allowed')}>
              {submitting && <Loader2 className="w-4 h-4 animate-spin" />}Execute Divestiture
            </button>
          </div>
      </div>
    </Modal>
  );
};

export default DivestitureModal;