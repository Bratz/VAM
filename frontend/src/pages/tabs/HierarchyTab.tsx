// ============================================================================
// HIERARCHY TAB
// Hierarchy node assignment and collection channel configuration
// ============================================================================

import React, { useState, useEffect, useCallback } from 'react';
import {
  GitBranch,
  ChevronRight,
  CheckCircle,
  Building2,
  Layers,
  Search,
  RefreshCw,
  Info,
  AlertCircle,
  Landmark,
  CreditCard,
  Smartphone,
  Banknote,
  FileText,
  Wallet,
} from 'lucide-react';
import { Input, Button, Badge } from '../../components/ui';
import { Alert } from '../../components/ui/enhanced';
import { FormField, SelectField } from './FormComponents';
import { CreateVaRequest, Program, ProgramTypeConfig, HierarchyNode } from '../vaTypes';
import { cn } from '../../utils';

// ============================================================================
// TYPES
// ============================================================================

export interface HierarchyTabProps {
  formData: CreateVaRequest;
  setFormData: React.Dispatch<React.SetStateAction<CreateVaRequest>>;
  errors: Record<string, string>;
  program?: Program;
  config?: ProgramTypeConfig;
  hierarchyNodes?: HierarchyNode[];
  onLoadHierarchy?: (programId: string) => void;
  loading?: boolean;
}

// ============================================================================
// OPTIONS
// ============================================================================

const COLLECTION_CHANNELS = [
  { value: 'BANK_TRANSFER', label: 'Bank Transfer', icon: Landmark, description: 'Wire transfers and ACH' },
  { value: 'CARD', label: 'Card Payment', icon: CreditCard, description: 'Credit/Debit card payments' },
  { value: 'MOBILE', label: 'Mobile Money', icon: Smartphone, description: 'Mobile wallet payments' },
  { value: 'CASH', label: 'Cash', icon: Banknote, description: 'Cash deposits at branches/agents' },
  { value: 'CHEQUE', label: 'Cheque', icon: FileText, description: 'Cheque deposits' },
  { value: 'DIRECT_DEBIT', label: 'Direct Debit', icon: RefreshCw, description: 'Automatic collections' },
  { value: 'WALLET', label: 'Wallet Transfer', icon: Wallet, description: 'Internal wallet transfers' },
];

// ============================================================================
// HIERARCHY NODE COMPONENT
// ============================================================================

interface HierarchyNodeItemProps {
  node: HierarchyNode;
  depth: number;
  expandedIds: Set<string>;
  onToggle: (id: string) => void;
  selectedId?: string;
  onSelect: (node: HierarchyNode) => void;
  searchQuery: string;
}

const HierarchyNodeItem: React.FC<HierarchyNodeItemProps> = ({
  node,
  depth,
  expandedIds,
  onToggle,
  selectedId,
  onSelect,
  searchQuery,
}) => {
  const isExpanded = expandedIds.has(node.id);
  const hasChildren = node.children && node.children.length > 0;
  const isSelected = selectedId === node.id;

  // Filter by search
  const matchesSearch = !searchQuery || 
    node.nodeName.toLowerCase().includes(searchQuery.toLowerCase()) ||
    node.nodeCode.toLowerCase().includes(searchQuery.toLowerCase());

  // Check if any children match search
  const hasMatchingChildren = searchQuery && node.children?.some(child => 
    child.nodeName.toLowerCase().includes(searchQuery.toLowerCase()) ||
    child.nodeCode.toLowerCase().includes(searchQuery.toLowerCase()) ||
    hasMatchingDescendants(child, searchQuery)
  );

  if (!matchesSearch && !hasMatchingChildren) {
    return null;
  }

  // Auto-expand if searching and has matching children
  const shouldExpand = isExpanded || (searchQuery && hasMatchingChildren);

  // Node type icons and colors
  const nodeTypeConfig = {
    MASTER: { icon: Building2, color: 'text-primary-600', bg: 'bg-primary-100' },
    CONSOLIDATION: { icon: Layers, color: 'text-cat-2', bg: 'bg-cat-2/10' },
    VIRTUAL_ACCOUNT: { icon: GitBranch, color: 'text-success-600', bg: 'bg-success-100' },
  };

  const typeConfig = nodeTypeConfig[node.nodeType] || nodeTypeConfig.CONSOLIDATION;
  const TypeIcon = typeConfig.icon;

  return (
    <div>
      <div
        className={cn(
          'flex items-center gap-2 p-2 rounded-lg cursor-pointer transition-all',
          isSelected 
            ? 'bg-primary-100 border-2 border-primary-400' 
            : 'hover:bg-neutral-50 border-2 border-transparent',
          matchesSearch && searchQuery && 'ring-2 ring-warning-200'
        )}
        style={{ paddingLeft: `${depth * 20 + 8}px` }}
        onClick={() => onSelect(node)}
      >
        {/* Expand/Collapse Button */}
        {hasChildren ? (
          <button
            onClick={(e) => {
              e.stopPropagation();
              onToggle(node.id);
            }}
            className="p-1 hover:bg-neutral-200 rounded transition-colors"
          >
            <ChevronRight 
              className={cn(
                'w-4 h-4 transition-transform', 
                shouldExpand && 'rotate-90'
              )} 
            />
          </button>
        ) : (
          <div className="w-6" />
        )}
        
        {/* Node Icon */}
        <div className={cn('p-1 rounded', typeConfig.bg)}>
          <TypeIcon className={cn('w-4 h-4', typeConfig.color)} />
        </div>

        {/* Node Info */}
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <span className="font-medium text-sm truncate">{node.nodeName}</span>
            <Badge variant="neutral" size="sm">L{node.levelNumber}</Badge>
            {node.nodeType === 'VIRTUAL_ACCOUNT' && (
              <Badge variant="success" size="sm">VA</Badge>
            )}
          </div>
          <div className="flex items-center gap-2 text-xs text-neutral-500">
            <span>{node.nodeCode}</span>
            <span>•</span>
            <span className="truncate">{node.materializedPath}</span>
          </div>
        </div>

        {/* Selection Indicator */}
        {isSelected && (
          <CheckCircle className="w-5 h-5 text-primary-600 shrink-0" />
        )}
      </div>

      {/* Children */}
      {shouldExpand && hasChildren && (
        <div>
          {node.children!.map(child => (
            <HierarchyNodeItem
              key={child.id}
              node={child}
              depth={depth + 1}
              expandedIds={expandedIds}
              onToggle={onToggle}
              selectedId={selectedId}
              onSelect={onSelect}
              searchQuery={searchQuery}
            />
          ))}
        </div>
      )}
    </div>
  );
};

// Helper to check for matching descendants
function hasMatchingDescendants(node: HierarchyNode, query: string): boolean {
  if (!node.children) return false;
  return node.children.some(child => 
    child.nodeName.toLowerCase().includes(query.toLowerCase()) ||
    child.nodeCode.toLowerCase().includes(query.toLowerCase()) ||
    hasMatchingDescendants(child, query)
  );
}

// ============================================================================
// MAIN COMPONENT
// ============================================================================

export const HierarchyTab: React.FC<HierarchyTabProps> = ({
  formData,
  setFormData,
  errors,
  program,
  config,
  hierarchyNodes = [],
  onLoadHierarchy,
  loading = false,
}) => {
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());
  const [searchQuery, setSearchQuery] = useState('');

  // Update field helper
  const updateField = <K extends keyof CreateVaRequest>(
    field: K,
    value: CreateVaRequest[K]
  ) => {
    setFormData(prev => ({ ...prev, [field]: value }));
  };

  // Toggle expand/collapse
  const toggleExpand = useCallback((id: string) => {
    setExpandedIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }, []);

  // Expand all nodes
  const expandAll = useCallback(() => {
    const allIds = new Set<string>();
    const collectIds = (nodes: HierarchyNode[]) => {
      nodes.forEach(node => {
        allIds.add(node.id);
        if (node.children) {
          collectIds(node.children);
        }
      });
    };
    collectIds(hierarchyNodes);
    setExpandedIds(allIds);
  }, [hierarchyNodes]);

  // Collapse all nodes
  const collapseAll = useCallback(() => {
    setExpandedIds(new Set());
  }, []);

  // Select node handler
  const handleSelectNode = useCallback((node: HierarchyNode) => {
    updateField('hierarchyNodeId', node.id);
  }, []);

  // Find selected node info
  const findNode = (nodes: HierarchyNode[], id: string): HierarchyNode | null => {
    for (const node of nodes) {
      if (node.id === id) return node;
      if (node.children) {
        const found = findNode(node.children, id);
        if (found) return found;
      }
    }
    return null;
  };

  const selectedNode = formData.hierarchyNodeId 
    ? findNode(hierarchyNodes, formData.hierarchyNodeId) 
    : null;

  // Check if hierarchy is enabled
  if (!program?.hierarchyEnabled) {
    return (
      <Alert variant="info">
        <Info className="w-4 h-4" />
        <div>
          <strong>Hierarchy not enabled</strong>
          <p className="text-sm mt-1">
            Hierarchy is not enabled for this program. Select a program with hierarchy support 
            to assign this account to a hierarchy node.
          </p>
        </div>
      </Alert>
    );
  }

  return (
    <div className="space-y-6">
      {/* Collection Channel Section */}
      <div>
        <div className="flex items-center gap-2 mb-4">
          <GitBranch className="w-5 h-5 text-primary-600" />
          <h4 className="text-sm font-medium text-neutral-900">Collection Channel</h4>
        </div>
        
        <FormField 
          label="Primary Collection Channel" 
          hint="The main channel through which funds are collected into this account"
        >
          <SelectField
            value={formData.collectionChannel || ''}
            onChange={(v) => updateField('collectionChannel', v)}
            options={COLLECTION_CHANNELS.map(c => ({
              value: c.value,
              label: c.label
            }))}
            placeholder="Select channel..."
          />
          {formData.collectionChannel && (
            <p className="text-xs text-neutral-500 mt-1">
              {COLLECTION_CHANNELS.find(c => c.value === formData.collectionChannel)?.description}
            </p>
          )}
        </FormField>

        {/* Channel Quick Select */}
        <div className="mt-3 flex flex-wrap gap-2">
          {COLLECTION_CHANNELS.map((channel) => (
            <button
              key={channel.value}
              type="button"
              onClick={() => updateField('collectionChannel', channel.value)}
              className={cn(
                'inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border text-sm transition-all',
                formData.collectionChannel === channel.value
                  ? 'border-primary-500 bg-primary-50 text-primary-700'
                  : 'border-neutral-200 hover:border-primary-300 text-neutral-600'
              )}
            >
              <channel.icon className="w-4 h-4" /> {channel.label}
            </button>
          ))}
        </div>
      </div>

      {/* Hierarchy Tree Section */}
      <div className="border-t border-neutral-200 pt-6">
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-2">
            <Layers className="w-5 h-5 text-primary-600" />
            <h4 className="text-sm font-medium text-neutral-900">Hierarchy Position</h4>
          </div>
          
          <div className="flex items-center gap-2">
            <Button size="sm" variant="ghost" onClick={expandAll}>
              Expand All
            </Button>
            <Button size="sm" variant="ghost" onClick={collapseAll}>
              Collapse All
            </Button>
            {onLoadHierarchy && program && (
              <Button 
                size="sm" 
                variant="outline" 
                onClick={() => onLoadHierarchy(program.id)}
                loading={loading}
                leftIcon={<RefreshCw className="w-4 h-4" />}
              >
                Refresh
              </Button>
            )}
          </div>
        </div>

        {/* Search */}
        <div className="mb-4">
          <div className="relative">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400" />
            <Input
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Search nodes by name or code..."
              className="pl-10"
            />
          </div>
        </div>

        {/* Selected Node Display */}
        {selectedNode && (
          <div className="mb-4 p-3 bg-primary-50 border border-primary-200 rounded-lg">
            <div className="flex items-center justify-between">
              <div>
                <div className="font-medium text-primary-900">{selectedNode.nodeName}</div>
                <div className="text-sm text-primary-700">
                  {selectedNode.nodeCode} • Level {selectedNode.levelNumber} • {selectedNode.materializedPath}
                </div>
              </div>
              <Button
                size="sm"
                variant="ghost"
                onClick={() => updateField('hierarchyNodeId', undefined)}
              >
                Clear
              </Button>
            </div>
          </div>
        )}

        {/* Hierarchy Tree */}
        {hierarchyNodes.length > 0 ? (
          <div className="border border-neutral-200 rounded-lg max-h-80 overflow-y-auto">
            {hierarchyNodes.map(node => (
              <HierarchyNodeItem
                key={node.id}
                node={node}
                depth={0}
                expandedIds={expandedIds}
                onToggle={toggleExpand}
                selectedId={formData.hierarchyNodeId}
                onSelect={handleSelectNode}
                searchQuery={searchQuery}
              />
            ))}
          </div>
        ) : (
          <div className="text-center py-12 border border-dashed border-neutral-300 rounded-lg">
            <GitBranch className="w-10 h-10 mx-auto mb-3 text-neutral-400" />
            <p className="text-neutral-600 mb-2">No hierarchy nodes available</p>
            <p className="text-sm text-neutral-500 mb-4">
              Load the hierarchy to assign this account to a node
            </p>
            {onLoadHierarchy && program && (
              <Button
                size="sm"
                variant="primary"
                onClick={() => onLoadHierarchy(program.id)}
                loading={loading}
              >
                Load Hierarchy
              </Button>
            )}
          </div>
        )}

        {/* Error */}
        {errors.hierarchyNodeId && (
          <Alert variant="danger" className="mt-4">
            <AlertCircle className="w-4 h-4" />
            {errors.hierarchyNodeId}
          </Alert>
        )}
      </div>

      {/* Hierarchy Info */}
      <Alert variant="info">
        <Info className="w-4 h-4" />
        <div>
          <strong>About Hierarchy Assignment</strong>
          <p className="text-sm mt-1">
            Assigning this account to a hierarchy node enables balance aggregation, 
            consolidated reporting, and hierarchical fund sweeping. Choose a node 
            that represents the organizational position of this account.
          </p>
        </div>
      </Alert>
    </div>
  );
};

export default HierarchyTab;