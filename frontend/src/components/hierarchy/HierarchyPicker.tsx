import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  ChevronRight,
  ChevronDown,
  Building2,
  Wallet,
  Globe,
  MapPin,
  Layers,
  Search,
  X,
  Check,
  Loader2,
} from 'lucide-react';
import { Card, Badge, Button, Input } from '../ui';
import { formatCurrency, cn } from '../../utils';

// ============================================================================
// TYPES
// ============================================================================

export interface HierarchyNode {
  id: string;
  nodeCode: string;
  nodeName: string;
  nodeType: 'MASTER' | 'CONSOLIDATION' | 'VIRTUAL_ACCOUNT';
  levelNumber: number;
  parentId?: string;
  currencyCode: string;
  aggregatedBalance: number;
  virtualAccountId?: string;
  primaryViban?: string;
  vibanCount?: number;
  children?: HierarchyNode[];
  materializedPath: string;
  status?: string;
}

export interface HierarchyPickerProps {
  programId: string;
  selectedNodeId?: string;
  onSelect: (node: HierarchyNode) => void;
  minLevel?: number;
  maxLevel?: number;
  showBalance?: boolean;
  showViban?: boolean;
  allowedNodeTypes?: ('MASTER' | 'CONSOLIDATION' | 'VIRTUAL_ACCOUNT')[];
  placeholder?: string;
  disabled?: boolean;
  error?: string;
  label?: string;
  required?: boolean;
  className?: string;
}

// ============================================================================
// HIERARCHY PICKER NODE (Internal)
// ============================================================================

interface PickerNodeProps {
  node: HierarchyNode;
  expandedIds: Set<string>;
  onToggle: (id: string) => void;
  selectedId?: string;
  onSelect: (node: HierarchyNode) => void;
  showBalance: boolean;
  showViban: boolean;
  allowedNodeTypes?: ('MASTER' | 'CONSOLIDATION' | 'VIRTUAL_ACCOUNT')[];
  minLevel?: number;
  maxLevel?: number;
  searchQuery: string;
}

const PickerNode: React.FC<PickerNodeProps> = ({
  node,
  expandedIds,
  onToggle,
  selectedId,
  onSelect,
  showBalance,
  showViban,
  allowedNodeTypes,
  minLevel,
  maxLevel,
  searchQuery,
}) => {
  const isExpanded = expandedIds.has(node.id);
  const hasChildren = node.children && node.children.length > 0;
  const isSelected = selectedId === node.id;
  
  // Filter by level
  if (minLevel && node.levelNumber < minLevel) {
    // Only render children if they might be within range
    if (!hasChildren) return null;
    return (
      <>
        {isExpanded && node.children?.map((child) => (
          <PickerNode
            key={child.id}
            node={child}
            expandedIds={expandedIds}
            onToggle={onToggle}
            selectedId={selectedId}
            onSelect={onSelect}
            showBalance={showBalance}
            showViban={showViban}
            allowedNodeTypes={allowedNodeTypes}
            minLevel={minLevel}
            maxLevel={maxLevel}
            searchQuery={searchQuery}
          />
        ))}
      </>
    );
  }
  
  if (maxLevel && node.levelNumber > maxLevel) return null;
  
  // Check if selectable based on node type
  const isSelectable = !allowedNodeTypes || allowedNodeTypes.includes(node.nodeType);
  
  // Search filter
  const matchesSearch = !searchQuery || 
    node.nodeName.toLowerCase().includes(searchQuery.toLowerCase()) ||
    node.nodeCode.toLowerCase().includes(searchQuery.toLowerCase());
  
  // Check if any children match search
  const hasMatchingChildren = node.children?.some(child => 
    child.nodeName.toLowerCase().includes(searchQuery.toLowerCase()) ||
    child.nodeCode.toLowerCase().includes(searchQuery.toLowerCase())
  );
  
  if (searchQuery && !matchesSearch && !hasMatchingChildren) return null;

  const getNodeStyle = () => {
    switch (node.nodeType) {
      case 'MASTER':
        return { bg: 'bg-primary-900', text: 'text-white', icon: Globe };
      case 'CONSOLIDATION':
        if (node.levelNumber <= 2) return { bg: 'bg-info-100 dark:bg-info-500/20', text: 'text-info-800 dark:text-info-300', icon: MapPin };
        if (node.levelNumber <= 4) return { bg: 'bg-accent-100 dark:bg-accent-500/20', text: 'text-accent-800 dark:text-accent-300', icon: Building2 };
        return { bg: 'bg-warning-100 dark:bg-warning-500/20', text: 'text-warning-800 dark:text-warning-300', icon: Layers };
      case 'VIRTUAL_ACCOUNT':
        return { bg: 'bg-success-50 dark:bg-success-500/10', text: 'text-success-700 dark:text-success-300', icon: Wallet };
      default:
        return { bg: 'bg-neutral-100 dark:bg-primary-800', text: 'text-neutral-700 dark:text-neutral-200', icon: Wallet };
    }
  };

  const style = getNodeStyle();
  const Icon = style.icon;

  return (
    <div className="select-none">
      <div
        className={cn(
          'flex items-center gap-2 p-2 rounded-lg cursor-pointer transition-all',
          isSelected ? 'ring-2 ring-primary-500 bg-primary-50 dark:bg-primary-800/40' : 'hover:bg-neutral-50 dark:hover:bg-primary-800/50',
          !isSelectable && 'opacity-60 cursor-not-allowed'
        )}
        style={{ marginLeft: `${(node.levelNumber - (minLevel || 1)) * 20}px` }}
        onClick={() => isSelectable && onSelect(node)}
      >
        {hasChildren ? (
          <button
            onClick={(e) => {
              e.stopPropagation();
              onToggle(node.id);
            }}
            className="p-0.5 hover:bg-neutral-200 rounded"
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

        <div className={cn('p-1.5 rounded', style.bg)}>
          <Icon className={cn('w-3.5 h-3.5', node.nodeType === 'MASTER' ? 'text-white' : style.text)} />
        </div>

        <div className="flex-1 min-w-0">
          <p className="text-sm font-medium text-primary-900 truncate dark:text-neutral-50">{node.nodeName}</p>
          <p className="text-xs text-neutral-500 font-mono dark:text-neutral-400">{node.nodeCode}</p>
        </div>

        {showBalance && (
          <span className="text-xs font-medium text-neutral-600 dark:text-neutral-300">
            {formatCurrency(node.aggregatedBalance, node.currencyCode)}
          </span>
        )}

        {showViban && node.primaryViban && (
          <span className="text-xs font-mono text-primary-600 bg-primary-50 px-1.5 py-0.5 rounded dark:text-primary-200 dark:bg-primary-800/40">
            {node.primaryViban.slice(-8)}
          </span>
        )}

        {isSelected && (
          <Check className="w-4 h-4 text-primary-600 dark:text-primary-200" />
        )}
      </div>

      {hasChildren && isExpanded && (
        <div>
          {node.children!.map((child) => (
            <PickerNode
              key={child.id}
              node={child}
              expandedIds={expandedIds}
              onToggle={onToggle}
              selectedId={selectedId}
              onSelect={onSelect}
              showBalance={showBalance}
              showViban={showViban}
              allowedNodeTypes={allowedNodeTypes}
              minLevel={minLevel}
              maxLevel={maxLevel}
              searchQuery={searchQuery}
            />
          ))}
        </div>
      )}
    </div>
  );
};

// ============================================================================
// MAIN HIERARCHY PICKER COMPONENT
// ============================================================================

export const HierarchyPicker: React.FC<HierarchyPickerProps> = ({
  programId,
  selectedNodeId,
  onSelect,
  minLevel,
  maxLevel,
  showBalance = true,
  showViban = false,
  allowedNodeTypes,
  placeholder = 'Select from hierarchy...',
  disabled = false,
  error,
  label,
  required,
  className,
}) => {
  const [isOpen, setIsOpen] = useState(false);
  const [hierarchy, setHierarchy] = useState<HierarchyNode | null>(null);
  const [loading, setLoading] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());
  const [selectedNode, setSelectedNode] = useState<HierarchyNode | null>(null);
  const dropdownRef = useRef<HTMLDivElement>(null);

  // Collect all node IDs for expand all
  const collectAllIds = useCallback((node: HierarchyNode): string[] => {
    const ids = [node.id];
    if (node.children) {
      node.children.forEach(child => ids.push(...collectAllIds(child)));
    }
    return ids;
  }, []);

  // Find node by ID in tree
  const findNode = useCallback((root: HierarchyNode, id: string): HierarchyNode | null => {
    if (root.id === id) return root;
    if (root.children) {
      for (const child of root.children) {
        const found = findNode(child, id);
        if (found) return found;
      }
    }
    return null;
  }, []);

  // Load hierarchy data
  const loadHierarchy = useCallback(async () => {
    if (!programId) return;
    
    try {
      setLoading(true);
      const response = await fetch(`/api/v1/programs/${programId}/hierarchy/tree`);
      const result = await response.json();
      
      if (result.success && result.data) {
        setHierarchy(result.data);
        // Expand first few levels by default
        const allIds = collectAllIds(result.data);
        setExpandedIds(new Set(allIds.slice(0, 15)));
        
        // Set selected node if ID provided
        if (selectedNodeId) {
          const node = findNode(result.data, selectedNodeId);
          if (node) setSelectedNode(node);
        }
      }
    } catch (err) {
      console.error('Failed to load hierarchy:', err);
    } finally {
      setLoading(false);
    }
  }, [programId, selectedNodeId, collectAllIds, findNode]);

  useEffect(() => {
    if (isOpen && !hierarchy) {
      loadHierarchy();
    }
  }, [isOpen, hierarchy, loadHierarchy]);

  // Update selected node when ID changes externally
  useEffect(() => {
    if (selectedNodeId && hierarchy) {
      const node = findNode(hierarchy, selectedNodeId);
      if (node) setSelectedNode(node);
    } else if (!selectedNodeId) {
      setSelectedNode(null);
    }
  }, [selectedNodeId, hierarchy, findNode]);

  // Close dropdown on outside click
  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target as Node)) {
        setIsOpen(false);
      }
    };

    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const toggleExpand = (id: string) => {
    const newExpanded = new Set(expandedIds);
    if (newExpanded.has(id)) {
      newExpanded.delete(id);
    } else {
      newExpanded.add(id);
    }
    setExpandedIds(newExpanded);
  };

  const handleSelect = (node: HierarchyNode) => {
    setSelectedNode(node);
    onSelect(node);
    setIsOpen(false);
    setSearchQuery('');
  };

  const handleClear = (e: React.MouseEvent) => {
    e.stopPropagation();
    setSelectedNode(null);
    onSelect(null as any);
  };

  return (
    <div className={cn('relative', className)} ref={dropdownRef}>
      {label && (
        <label className="block text-sm font-medium text-primary-900 mb-1 dark:text-neutral-50">
          {label}
          {required && <span className="text-error-500 ml-1">*</span>}
        </label>
      )}
      
      {/* Trigger Button */}
      <button
        type="button"
        onClick={() => !disabled && setIsOpen(!isOpen)}
        disabled={disabled}
        className={cn(
          'w-full flex items-center justify-between gap-2 px-3 py-2.5 border rounded-lg text-left transition-all',
          'bg-white hover:bg-neutral-50 dark:bg-primary-900 dark:hover:bg-primary-800/50',
          isOpen ? 'border-primary-500 ring-2 ring-primary-100' : 'border-neutral-300 dark:border-primary-700',
          error ? 'border-error-500' : '',
          disabled ? 'opacity-50 cursor-not-allowed bg-neutral-100 dark:bg-primary-800' : 'cursor-pointer'
        )}
      >
        {selectedNode ? (
          <div className="flex items-center gap-2 flex-1 min-w-0">
            <Wallet className="w-4 h-4 text-primary-600 flex-shrink-0 dark:text-primary-200" />
            <div className="min-w-0">
              <p className="text-sm font-medium text-primary-900 truncate dark:text-neutral-50">{selectedNode.nodeName}</p>
              <p className="text-xs text-neutral-500 font-mono truncate dark:text-neutral-400">{selectedNode.nodeCode}</p>
            </div>
          </div>
        ) : (
          <span className="text-neutral-400 text-sm dark:text-neutral-500">{placeholder}</span>
        )}
        
        <div className="flex items-center gap-1">
          {selectedNode && !disabled && (
            <button
              onClick={handleClear}
              className="p-1 hover:bg-neutral-200 rounded"
            >
              <X className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
            </button>
          )}
          <ChevronDown className={cn(
            'w-4 h-4 text-neutral-400 transition-transform dark:text-neutral-500',
            isOpen && 'transform rotate-180'
          )} />
        </div>
      </button>

      {error && (
        <p className="text-sm text-error-600 mt-1 dark:text-error-300">{error}</p>
      )}

      {/* Dropdown */}
      {isOpen && (
        <Card className="absolute z-50 w-full mt-1 shadow-lg max-h-[400px] overflow-hidden" padding="none">
          {/* Search */}
          <div className="p-2 border-b border-neutral-200 dark:border-primary-800">
            <div className="relative">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400 dark:text-neutral-500" />
              <input
                type="text"
                placeholder="Search hierarchy..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                className="w-full pl-9 pr-3 py-2 text-sm border border-neutral-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-primary-500 dark:border-primary-800"
                autoFocus
              />
            </div>
          </div>

          {/* Tree */}
          <div className="max-h-[320px] overflow-y-auto p-2">
            {loading ? (
              <div className="flex items-center justify-center py-8">
                <Loader2 className="w-6 h-6 animate-spin text-primary-500" />
                <span className="ml-2 text-sm text-neutral-500 dark:text-neutral-400">Loading hierarchy...</span>
              </div>
            ) : hierarchy ? (
              <PickerNode
                node={hierarchy}
                expandedIds={expandedIds}
                onToggle={toggleExpand}
                selectedId={selectedNode?.id}
                onSelect={handleSelect}
                showBalance={showBalance}
                showViban={showViban}
                allowedNodeTypes={allowedNodeTypes}
                minLevel={minLevel}
                maxLevel={maxLevel}
                searchQuery={searchQuery}
              />
            ) : (
              <div className="text-center py-8 text-neutral-500 dark:text-neutral-400">
                No hierarchy data available
              </div>
            )}
          </div>

          {/* Footer Actions */}
          <div className="p-2 border-t border-neutral-200 flex justify-between dark:border-primary-800">
            <Button
              variant="ghost"
              size="sm"
              onClick={() => {
                if (hierarchy) {
                  setExpandedIds(new Set(collectAllIds(hierarchy)));
                }
              }}
            >
              Expand All
            </Button>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => setExpandedIds(new Set([hierarchy?.id || '']))}
            >
              Collapse All
            </Button>
          </div>
        </Card>
      )}
    </div>
  );
};

export default HierarchyPicker;