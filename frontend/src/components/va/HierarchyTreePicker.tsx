// ============================================================================
// HierarchyTreePicker — the shared PLACEMENT step of the VA-creation journey.
//
// One mental model for both entry points: see the program's hierarchy, pick
// the node the new VA hangs under, or grow a new branch in place. This is
// deliberately NOT the Balance Hierarchy page's TreeNode (balances, context
// menus, net positions) — a picker shows structure and selection, nothing
// else. Data comes from GET /hierarchy/nodes?programId= (same rows the LOV
// cascade used).
//
// Selection semantics (mirrors the converged backend write path):
//   - Existing node picked  → submit with parentNodeId (any level — identical
//     to the hierarchy-tree flow).
//   - New branch grown      → the remaining aggregation levels are collected
//     inline and submitted as hierarchyDimensions (backend auto-creates the
//     chain, reusing existing nodes by dimension value).
// ============================================================================

import React, { useMemo, useState } from 'react';
import {
  ChevronRight, ChevronDown, Plus, X,
  Globe, Coins, Flag, MapPin, Building2, Building, Users, Wallet,
  BarChart3, RadioTower, Network, Target, User, Store, Folder, Landmark,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { Badge, Button, Input } from '../ui';
import { cn } from '../../utils';

export interface PickerNode {
  id: string;
  parentId?: string | null;
  levelNumber: number;
  dimensionValue?: string;
  nodeName?: string;
  nodeCode?: string;
  nodeType?: string;
}

export interface PickerLevelConfig {
  levelNumber: number;
  levelName: string;
  dimensionType: string;
  isRequired?: boolean;
  allowedValues?: string[];
}

/** What the picker hands back to the flow — exactly what the backend needs. */
export interface PlacementSelection {
  /** Set when an existing node was picked (parent-node submit). */
  parentNodeId?: string;
  /** Set when a new branch was grown (dimension submit, complete chain). */
  dimensionValues?: Record<string, string>;
  /** Human-readable chain for the placement preview card. */
  pathLabels: string[];
}

const DIMENSION_ICONS: Record<string, LucideIcon> = {
  CURRENCY: Coins, REGION: Globe, COUNTRY: Flag, STATE: MapPin, CITY: Building2,
  ENTITY: Building, DEPARTMENT: Users, COST_CENTER: Wallet, ACCOUNT_TYPE: BarChart3,
  CHANNEL: RadioTower, PLATFORM: Network, SEGMENT: Target, CUSTOMER: User,
  MERCHANT: Store,
};

/** Human-facing label — the node's NAME, not its codified dimension value. */
const nodeLabel = (n: PickerNode) => n.nodeName || n.dimensionValue || n.nodeCode || '?';
/** Chain key for dimension submits — MUST stay the codified value the
 *  backend matches/normalizes on; never the display name. */
const nodeDimension = (n: PickerNode) => n.dimensionValue || n.nodeCode || '?';

interface HierarchyTreePickerProps {
  nodes: PickerNode[];
  levelConfigs: PickerLevelConfig[];
  value: PlacementSelection | null;
  onChange: (selection: PlacementSelection | null) => void;
}

export const HierarchyTreePicker: React.FC<HierarchyTreePickerProps> = ({
  nodes, levelConfigs, value, onChange,
}) => {
  // Aggregation levels only — the VA leaf level is what's being created.
  const aggLevels = useMemo(
    () => levelConfigs.filter(l => l.dimensionType !== 'VIRTUAL_ACCOUNT'),
    [levelConfigs]
  );
  const maxAggLevel = aggLevels.length ? aggLevels[aggLevels.length - 1].levelNumber : 0;

  const structural = useMemo(
    () => nodes.filter(n => n.nodeType !== 'VIRTUAL_ACCOUNT'),
    [nodes]
  );
  const byParent = useMemo(() => {
    const m = new Map<string | null, PickerNode[]>();
    for (const n of structural) {
      const key = n.parentId ?? null;
      if (!m.has(key)) m.set(key, []);
      m.get(key)!.push(n);
    }
    return m;
  }, [structural]);
  const byId = useMemo(() => new Map(structural.map(n => [n.id, n])), [structural]);
  const roots = useMemo(
    () => structural.filter(n => !n.parentId || !byId.has(n.parentId)),
    [structural, byId]
  );

  const [expanded, setExpanded] = useState<Set<string>>(
    () => new Set(roots.map(r => r.id))
  );
  // Node the "grow a branch" panel is anchored to (null = not growing;
  // 'ROOT' sentinel = growing from nothing, virgin program).
  const [growFrom, setGrowFrom] = useState<PickerNode | 'ROOT' | null>(null);
  const [growValues, setGrowValues] = useState<Record<number, string>>({});

  const toggle = (id: string) =>
    setExpanded(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });

  const ancestorsOf = (n: PickerNode): PickerNode[] => {
    const chain: PickerNode[] = [];
    let cur: PickerNode | undefined = n;
    let guard = 0;
    while (cur && guard++ < 20) {
      chain.unshift(cur);
      cur = cur.parentId ? byId.get(cur.parentId) : undefined;
    }
    return chain;
  };

  const pickNode = (n: PickerNode) => {
    setGrowFrom(null);
    setGrowValues({});
    onChange({ parentNodeId: n.id, pathLabels: ancestorsOf(n).map(nodeLabel) });
  };

  const startGrow = (from: PickerNode | 'ROOT') => {
    setGrowFrom(from);
    setGrowValues({});
    onChange(null); // incomplete until every remaining level has a value
  };

  // Levels the grow panel must collect: everything below the anchor node,
  // down to the deepest aggregation level (the backend's dimension flow
  // requires the complete chain).
  const growLevels: PickerLevelConfig[] =
    growFrom === null ? []
    : growFrom === 'ROOT' ? aggLevels
    : aggLevels.filter(l => l.levelNumber > growFrom.levelNumber);

  const setGrowValue = (levelNumber: number, raw: string) => {
    const nextValues = { ...growValues, [levelNumber]: raw.toUpperCase() };
    setGrowValues(nextValues);
    // Complete chain? Anchor ancestors supply the upper levels.
    const anchorChain = growFrom && growFrom !== 'ROOT' ? ancestorsOf(growFrom) : [];
    const dims: Record<string, string> = {};
    for (const a of anchorChain) dims[`L${a.levelNumber}`] = nodeDimension(a);
    let complete = true;
    for (const l of growLevels) {
      const v = (nextValues[l.levelNumber] || '').trim();
      if (!v) { complete = false; break; }
      dims[`L${l.levelNumber}`] = v;
    }
    if (complete && growLevels.length > 0) {
      onChange({
        dimensionValues: dims,
        pathLabels: [
          ...anchorChain.map(nodeLabel),
          ...growLevels.map(l => nextValues[l.levelNumber]),
        ],
      });
    } else {
      onChange(null);
    }
  };

  const renderNode = (n: PickerNode, depth: number): React.ReactNode => {
    const children = byParent.get(n.id) || [];
    const isExpanded = expanded.has(n.id);
    const isSelected = value?.parentNodeId === n.id;
    const isGrowAnchor = growFrom !== null && growFrom !== 'ROOT' && growFrom.id === n.id;
    const nextLevel = aggLevels.find(l => l.levelNumber === n.levelNumber + 1);
    const canGrow = n.levelNumber < maxAggLevel;

    return (
      <div key={n.id}>
        <div
          className={cn(
            'flex items-center gap-1.5 py-1.5 px-2 rounded-lg cursor-pointer text-sm transition-colors',
            isSelected
              ? 'bg-primary-50 ring-1 ring-primary-400 dark:bg-primary-800/40'
              : 'hover:bg-neutral-50 dark:hover:bg-primary-800/40'
          )}
          style={{ marginLeft: depth * 18 }}
          onClick={() => pickNode(n)}
        >
          {children.length > 0 ? (
            <button
              onClick={(e) => { e.stopPropagation(); toggle(n.id); }}
              className="p-0.5 rounded hover:bg-neutral-200 dark:hover:bg-primary-700"
            >
              {isExpanded
                ? <ChevronDown className="w-3.5 h-3.5 text-neutral-400" />
                : <ChevronRight className="w-3.5 h-3.5 text-neutral-400" />}
            </button>
          ) : <span className="w-[18px]" />}
          {n.levelNumber === 1
            ? <Landmark className="w-4 h-4 text-primary-500 shrink-0" />
            : <Folder className="w-4 h-4 text-neutral-400 shrink-0" />}
          <span className={cn('truncate', isSelected ? 'font-medium text-primary-900 dark:text-neutral-50' : 'text-neutral-700 dark:text-neutral-200')}>
            {nodeLabel(n)}
          </span>
          {/* Codified dimension value as a secondary hint when it differs
              from the display name. */}
          {n.nodeName && n.dimensionValue && n.nodeName !== n.dimensionValue && (
            <span className="text-xs font-mono text-neutral-400 dark:text-neutral-500 truncate shrink-0 max-w-[90px]">
              {n.dimensionValue}
            </span>
          )}
          <Badge variant="neutral" size="sm">L{n.levelNumber}</Badge>
          {isSelected && <Badge variant="success" size="sm">parent</Badge>}
        </div>

        {isExpanded && children.map(c => renderNode(c, depth + 1))}

        {/* Grow affordance under an expanded (or selected) node */}
        {canGrow && (isExpanded || isSelected) && !isGrowAnchor && (
          <button
            onClick={() => startGrow(n)}
            className="flex items-center gap-1.5 py-1 px-2 text-xs text-info-600 dark:text-info-300 hover:underline"
            style={{ marginLeft: (depth + 1) * 18 }}
          >
            <Plus className="w-3 h-3" />
            New {nextLevel?.levelName || 'branch'} under {nodeLabel(n)}…
          </button>
        )}

        {/* Inline grow panel */}
        {isGrowAnchor && (
          <div
            className="my-1 p-3 rounded-lg border border-info-200 dark:border-info-500/30 bg-info-50/50 dark:bg-info-500/10 space-y-2"
            style={{ marginLeft: (depth + 1) * 18 }}
          >
            <div className="flex items-center justify-between">
              <p className="text-xs font-medium text-info-700 dark:text-info-300">
                New branch under {nodeLabel(n)} — intermediate nodes are auto-created
              </p>
              <button onClick={() => { setGrowFrom(null); setGrowValues({}); onChange(null); }}
                className="p-0.5 rounded hover:bg-info-100 dark:hover:bg-info-500/20">
                <X className="w-3.5 h-3.5 text-info-500" />
              </button>
            </div>
            {growLevels.map(l => {
              const LevelIcon = DIMENSION_ICONS[l.dimensionType] || Folder;
              return (
                <div key={l.levelNumber} className="flex items-center gap-2">
                  <LevelIcon className="w-4 h-4 text-info-500 shrink-0" />
                  <span className="text-xs w-24 shrink-0 text-neutral-600 dark:text-neutral-300">
                    L{l.levelNumber} {l.levelName}
                  </span>
                  {l.allowedValues && l.allowedValues.length > 0 ? (
                    <select
                      value={growValues[l.levelNumber] || ''}
                      onChange={(e) => setGrowValue(l.levelNumber, e.target.value)}
                      className="flex-1 px-2 py-1 text-sm border border-neutral-300 dark:border-primary-700 rounded-lg bg-white dark:bg-primary-900"
                    >
                      <option value="">Select…</option>
                      {l.allowedValues.map(v => <option key={v} value={v}>{v}</option>)}
                    </select>
                  ) : (
                    <Input
                      value={growValues[l.levelNumber] || ''}
                      onChange={(e) => setGrowValue(l.levelNumber, e.target.value)}
                      placeholder={`New ${l.levelName}…`}
                      className="text-sm h-8"
                    />
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>
    );
  };

  if (structural.length === 0) {
    // Virgin program — no tree yet; grow from scratch.
    return (
      <div className="space-y-2">
        <p className="text-sm text-neutral-600 dark:text-neutral-300">
          This program has no hierarchy yet — define the first branch:
        </p>
        {growFrom !== 'ROOT' ? (
          <Button variant="outline" size="sm" onClick={() => startGrow('ROOT')}>
            <Plus className="w-4 h-4 mr-1" />New branch
          </Button>
        ) : (
          <div className="p-3 rounded-lg border border-info-200 dark:border-info-500/30 bg-info-50/50 dark:bg-info-500/10 space-y-2">
            {growLevels.map(l => (
              <div key={l.levelNumber} className="flex items-center gap-2">
                <span className="text-xs w-24 shrink-0 text-neutral-600 dark:text-neutral-300">L{l.levelNumber} {l.levelName}</span>
                <Input
                  value={growValues[l.levelNumber] || ''}
                  onChange={(e) => setGrowValue(l.levelNumber, e.target.value)}
                  placeholder={`${l.levelName}…`}
                  className="text-sm h-8"
                />
              </div>
            ))}
          </div>
        )}
      </div>
    );
  }

  return (
    <div className="border border-neutral-200 dark:border-primary-800 rounded-lg p-2 max-h-80 overflow-y-auto">
      {roots.map(r => renderNode(r, 0))}
    </div>
  );
};
