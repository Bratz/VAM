// ============================================================================
// ScopePicker — single-select tree for enrollment SCOPE ("give me every
// account under this point"), not VA placement. Distinct from
// HierarchyTreePicker (which picks/grows a placement node) — this one is
// strictly single-select, no grow-branch UI, and resolves the pick into a
// live VA list via the by-scope endpoint.
//
// Two modes:
//   - 'hierarchyNode' — pick a node from GET /hierarchy/nodes?programId=
//     (same row shape/call as HierarchyTreePicker) → GET .../by-scope?hierarchyNodeId=
//   - 'legalEntity'   — pick a node from the legal-entity tree
//     (GET /hierarchy/legal-entities/corporate/{corporateId}/tree) →
//     GET .../by-scope?ownerEntityId=
// ============================================================================

import React, { useEffect, useMemo, useState } from 'react';
import { ChevronRight, ChevronDown, Landmark, Folder, Building2, Loader2 } from 'lucide-react';
import { apiClient } from '../../services/api';
import { cn } from '../../utils';

export interface ScopeNode {
  id: string;
  parentId?: string | null;
  levelNumber?: number;
  dimensionValue?: string;
  nodeName?: string;
  nodeCode?: string;
  nodeType?: string;
  /** Legal-entity tree rows use entityName/entityCode/parentEntityId instead. */
  entityName?: string;
  entityCode?: string;
  parentEntityId?: string;
}

export interface ScopedVaSummary {
  id: string;
  vaNumber: string;
  vaName: string;
  currencyCode: string;
  balance?: number;
}

interface ScopePickerProps {
  mode: 'hierarchyNode' | 'legalEntity';
  /** programId for hierarchyNode mode, corporateId for legalEntity mode. */
  contextId?: string;
  onResolved: (accounts: ScopedVaSummary[], selectedLabel: string | null) => void;
}

const nodeId = (n: ScopeNode) => n.id;
const nodeParentId = (n: ScopeNode) => (n.parentId ?? n.parentEntityId) || null;
const nodeLabel = (n: ScopeNode) => n.nodeName || n.entityName || n.dimensionValue || n.nodeCode || n.entityCode || '?';

export const ScopePicker: React.FC<ScopePickerProps> = ({ mode, contextId, onResolved }) => {
  const [nodes, setNodes] = useState<ScopeNode[]>([]);
  const [fetchingTree, setFetchingTree] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [resolving, setResolving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setSelectedId(null);
    setNodes([]);
    if (!contextId) return;
    setFetchingTree(true);
    setError(null);
    const url = mode === 'hierarchyNode'
      ? `/hierarchy/nodes?programId=${contextId}&size=500`
      : `/hierarchy/legal-entities/corporate/${contextId}/tree`;
    apiClient.get(url)
      .then(res => {
        const data = res.data?.data ?? res.data;
        const rows: ScopeNode[] = Array.isArray(data) ? data : [];
        setNodes(rows);
        setExpanded(new Set(rows.filter(n => !nodeParentId(n)).map(nodeId)));
      })
      .catch(() => setError('Failed to load tree'))
      .finally(() => setFetchingTree(false));
  }, [mode, contextId]);

  const byParent = useMemo(() => {
    const m = new Map<string | null, ScopeNode[]>();
    for (const n of nodes) {
      const key = nodeParentId(n);
      if (!m.has(key)) m.set(key, []);
      m.get(key)!.push(n);
    }
    return m;
  }, [nodes]);
  const byId = useMemo(() => new Map(nodes.map(n => [nodeId(n), n])), [nodes]);
  const roots = useMemo(
    () => nodes.filter(n => !nodeParentId(n) || !byId.has(nodeParentId(n)!)),
    [nodes, byId]
  );

  const toggle = (id: string) =>
    setExpanded(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });

  const select = async (n: ScopeNode) => {
    setSelectedId(nodeId(n));
    setResolving(true);
    setError(null);
    const label = nodeLabel(n);
    try {
      const params = mode === 'hierarchyNode' ? { hierarchyNodeId: n.id } : { ownerEntityId: n.id };
      const res = await apiClient.get('/virtual-accounts/by-scope', { params });
      // Backend confirmed: VirtualAccountDto.Summary — currentBalance/availableBalance,
      // not a generic `balance` field. Map explicitly rather than casting.
      const raw: Array<Record<string, any>> = res.data?.data ?? [];
      const accounts: ScopedVaSummary[] = raw.map(r => ({
        id: r.id, vaNumber: r.vaNumber, vaName: r.vaName, currencyCode: r.currencyCode,
        balance: r.currentBalance ?? r.availableBalance,
      }));
      onResolved(accounts, label);
    } catch {
      setError('Failed to resolve accounts for this scope');
      onResolved([], label);
    } finally {
      setResolving(false);
    }
  };

  const renderNode = (n: ScopeNode, depth: number): React.ReactNode => {
    const children = byParent.get(nodeId(n)) || [];
    const isExpanded = expanded.has(nodeId(n));
    const isSelected = selectedId === nodeId(n);

    return (
      <div key={nodeId(n)}>
        <div
          className={cn(
            'flex items-center gap-1.5 py-1.5 px-2 rounded-lg cursor-pointer text-sm transition-colors',
            isSelected
              ? 'bg-primary-50 ring-1 ring-primary-400 dark:bg-primary-800/40'
              : 'hover:bg-neutral-50 dark:hover:bg-primary-800/40'
          )}
          style={{ marginLeft: depth * 18 }}
          onClick={() => select(n)}
        >
          {children.length > 0 ? (
            <button
              onClick={(e) => { e.stopPropagation(); toggle(nodeId(n)); }}
              className="p-0.5 rounded hover:bg-neutral-200 dark:hover:bg-primary-700"
            >
              {isExpanded
                ? <ChevronDown className="w-3.5 h-3.5 text-neutral-400" />
                : <ChevronRight className="w-3.5 h-3.5 text-neutral-400" />}
            </button>
          ) : <span className="w-[18px]" />}
          {mode === 'legalEntity'
            ? <Building2 className="w-4 h-4 text-primary-500 shrink-0" />
            : depth === 0
              ? <Landmark className="w-4 h-4 text-primary-500 shrink-0" />
              : <Folder className="w-4 h-4 text-neutral-400 shrink-0" />}
          <span className={cn('truncate', isSelected ? 'font-medium text-primary-900 dark:text-neutral-50' : 'text-neutral-700 dark:text-neutral-200')}>
            {nodeLabel(n)}
          </span>
          {isSelected && resolving && <Loader2 className="w-3.5 h-3.5 animate-spin text-primary-400" />}
        </div>
        {isExpanded && children.map(c => renderNode(c, depth + 1))}
      </div>
    );
  };

  if (!contextId) {
    return <p className="text-sm text-neutral-500 dark:text-neutral-400">Select a program or corporate first.</p>;
  }

  if (fetchingTree) {
    return (
      <div className="flex items-center gap-2 text-sm text-neutral-500 dark:text-neutral-400 py-4">
        <Loader2 className="w-4 h-4 animate-spin" /> Loading hierarchy…
      </div>
    );
  }

  return (
    <div className="space-y-2">
      <div className="border border-neutral-200 dark:border-primary-800 rounded-lg p-2 max-h-80 overflow-y-auto">
        {roots.length === 0 ? (
          <p className="text-sm text-neutral-500 dark:text-neutral-400 p-2">No nodes found.</p>
        ) : (
          roots.map(r => renderNode(r, 0))
        )}
      </div>
      {error && <p className="text-xs text-error-600 dark:text-error-400">{error}</p>}
    </div>
  );
};

export default ScopePicker;
