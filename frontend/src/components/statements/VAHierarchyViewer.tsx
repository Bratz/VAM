// ============================================================================
// VA HIERARCHY VIEWER - Tree View for Aggregation Accounts
// ============================================================================
// Features:
// - Tree view showing parent-child relationships
// - Balance at each level (individual and aggregated)
// - Click to view individual VA statement
// - Expandable/collapsible nodes
// - Status indicators for each account
// ============================================================================

import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { ChevronRight, ChevronDown, Layers, CreditCard, ArrowDownRight, ArrowUpRight, FileText, RefreshCw, CheckCircle, PauseCircle, Ban, Coins, GitBranch, XCircle } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';

// Suppress unused variable warnings for props that are passed but may not be used in all code paths
/* eslint-disable @typescript-eslint/no-unused-vars */
import { Card, Button, Badge, Skeleton, StatusIconBadge } from '../ui';
import { cn, formatCurrency } from '../../utils';
import { statementsApi } from '../../services/api';
import type { VAHierarchyNode } from '../../types';
import toast from 'react-hot-toast';

// ============================================================================
// TYPES
// ============================================================================

interface VAHierarchyViewerProps {
  /** Root account ID to load hierarchy from */
  accountId: string;
  /** Callback when a VA is selected for statement viewing */
  onSelectAccount?: (node: VAHierarchyNode) => void;
  /** Callback to generate statement for an account */
  onGenerateStatement?: (accountId: string, vaNumber: string) => void;
  /** Show balance in base currency */
  showBaseCurrency?: boolean;
  /** Base currency code */
  baseCurrency?: string;
  /** Custom class name */
  className?: string;
}

// Account category configuration
const accountCategoryConfig: Record<string, {
  label: string;
  icon: LucideIcon;
  tone: React.ComponentProps<typeof StatusIconBadge>['tone'];
  color: string;
  bgColor: string;
}> = {
  TRANSACTION: { label: 'Transaction', icon: CreditCard, tone: 'primary', color: 'text-primary-600 dark:text-primary-200', bgColor: 'bg-primary-50 dark:bg-primary-800/40' },
  COLLECTION: { label: 'Collection', icon: ArrowDownRight, tone: 'success', color: 'text-success-600 dark:text-success-300', bgColor: 'bg-success-50 dark:bg-success-500/10' },
  DISBURSEMENT: { label: 'Disbursement', icon: ArrowUpRight, tone: 'warning', color: 'text-warning-600 dark:text-warning-300', bgColor: 'bg-warning-50 dark:bg-warning-500/10' },
  ROOT: { label: 'Root', icon: Layers, tone: 'cat-1', color: 'text-cat-1 dark:text-cat-1-fg', bgColor: 'bg-cat-1-soft dark:bg-cat-1/15' },
  AGGREGATION: { label: 'Aggregation', icon: Layers, tone: 'cat-2', color: 'text-cat-2 dark:text-cat-2-fg', bgColor: 'bg-cat-2-soft dark:bg-cat-2/15' },
  CURRENCY_MIRROR: { label: 'Currency', icon: Coins, tone: 'cat-5', color: 'text-cat-5 dark:text-cat-5-fg', bgColor: 'bg-cat-5-soft dark:bg-cat-5/15' },
};

// Status configuration
const statusConfig: Record<string, {
  label: string;
  variant: string;
  icon: React.ElementType;
}> = {
  ACTIVE: { label: 'Active', variant: 'success', icon: CheckCircle },
  SUSPENDED: { label: 'Suspended', variant: 'warning', icon: PauseCircle },
  BLOCKED: { label: 'Blocked', variant: 'error', icon: Ban },
  INACTIVE: { label: 'Inactive', variant: 'neutral', icon: XCircle },
  CLOSED: { label: 'Closed', variant: 'neutral', icon: XCircle },
};

// ============================================================================
// TREE NODE COMPONENT
// ============================================================================

interface TreeNodeProps {
  node: VAHierarchyNode;
  level: number;
  expanded: Set<string>;
  onToggle: (id: string) => void;
  onSelect: (node: VAHierarchyNode) => void;
  onGenerateStatement?: (accountId: string, vaNumber: string) => void;
  selectedId?: string;
}

const TreeNode: React.FC<TreeNodeProps> = ({
  node,
  level,
  expanded,
  onToggle,
  onSelect,
  onGenerateStatement,
  selectedId,
}) => {
  const isExpanded = expanded.has(node.id);
  const hasChildren = node.children && node.children.length > 0;
  const isSelected = selectedId === node.id;

  // Get category config
  const category = node.accountCategory || 'TRANSACTION';
  const categoryInfo = accountCategoryConfig[category] || accountCategoryConfig.TRANSACTION;
  const CategoryIcon = categoryInfo.icon;

  // Get status config
  const statusInfo = statusConfig[node.status] || statusConfig.INACTIVE;
  const StatusIcon = statusInfo.icon;

  // Determine effective balance (aggregated for parent nodes)
  const displayBalance = node.aggregatedBalance ?? node.currentBalance;
  const hasAggregatedBalance = node.aggregatedBalance !== undefined && node.aggregatedBalance !== node.currentBalance;

  return (
    <div className="select-none">
      {/* Node Row */}
      <div
        className={cn(
          'flex items-center gap-2 py-2.5 px-3 rounded-lg transition-all cursor-pointer group',
          isSelected
            ? 'bg-primary-50 border border-primary-200 dark:bg-primary-800/40 dark:border-primary-700'
            : 'hover:bg-neutral-50 dark:hover:bg-primary-800/50 dark:bg-primary-950 border border-transparent',
        )}
        style={{ paddingLeft: `${level * 24 + 12}px` }}
        onClick={() => onSelect(node)}
      >
        {/* Expand/Collapse Toggle */}
        <button
          onClick={(e) => {
            e.stopPropagation();
            onToggle(node.id);
          }}
          className={cn(
            'w-6 h-6 flex items-center justify-center rounded-md transition-colors',
            hasChildren ? 'hover:bg-neutral-200 dark:hover:bg-primary-700' : 'invisible'
          )}
        >
          {hasChildren && (
            isExpanded ? (
              <ChevronDown className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
            ) : (
              <ChevronRight className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
            )
          )}
        </button>

        {/* Category Icon */}
        <StatusIconBadge
          tone={categoryInfo.tone}
          icon={CategoryIcon}
          size="sm"
          subtle
          className={cn('shrink-0 transition-transform', isSelected && 'scale-105')}
        />

        {/* Account Info */}
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <span className={cn(
              'text-body-sm font-medium truncate',
              isSelected ? 'text-primary-900 dark:text-neutral-50' : 'text-neutral-900 dark:text-neutral-50'
            )}>
              {node.accountName}
            </span>
            {hasChildren && (
              <Badge variant="neutral" size="xs" className="shrink-0">
                <GitBranch className="w-2.5 h-2.5 mr-0.5" />
                {node.children.length}
              </Badge>
            )}
          </div>
          <div className="flex items-center gap-2 mt-0.5">
            <span className="text-caption text-neutral-500 dark:text-neutral-400 font-mono">{node.vaNumber}</span>
            {node.viban && (
              <span className="caption">({node.viban})</span>
            )}
          </div>
        </div>

        {/* Balance */}
        <div className="text-right shrink-0 mr-2">
          <p className={cn(
            'text-body-sm font-semibold tabular-nums',
            displayBalance >= 0 ? 'text-primary-900 dark:text-neutral-50' : 'text-error-600 dark:text-error-300'
          )}>
            {formatCurrency(displayBalance, node.currency)}
          </p>
          {hasAggregatedBalance && (
            <p className="caption">
              Own: {formatCurrency(node.currentBalance, node.currency)}
            </p>
          )}
        </div>

        {/* Status Badge */}
        <Badge variant={statusInfo.variant as any} size="sm" className="shrink-0">
          <StatusIcon className="w-3 h-3 mr-1" />
          {statusInfo.label}
        </Badge>

        {/* Statement Action */}
        {node.canGenerateStatement && onGenerateStatement && (
          <Button
            variant="ghost"
            size="xs"
            onClick={(e) => {
              e.stopPropagation();
              onGenerateStatement(node.id, node.vaNumber);
            }}
            className="opacity-0 group-hover:opacity-100 transition-opacity shrink-0"
          >
            <FileText className="w-4 h-4" />
          </Button>
        )}
      </div>

      {/* Children */}
      {isExpanded && hasChildren && (
        <div className="animate-fade-in">
          {node.children.map((child) => (
            <TreeNode
              key={child.id}
              node={child}
              level={level + 1}
              expanded={expanded}
              onToggle={onToggle}
              onSelect={onSelect}
              onGenerateStatement={onGenerateStatement}
              selectedId={selectedId}
            />
          ))}
        </div>
      )}
    </div>
  );
};

// ============================================================================
// MAIN COMPONENT
// ============================================================================

export const VAHierarchyViewer: React.FC<VAHierarchyViewerProps> = ({
  accountId,
  onSelectAccount,
  onGenerateStatement,
  showBaseCurrency = false,
  baseCurrency = 'AED',
  className,
}) => {
  // State
  const [hierarchy, setHierarchy] = useState<VAHierarchyNode | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [selectedId, setSelectedId] = useState<string | null>(null);

  // Load hierarchy data
  const loadHierarchy = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const response = await statementsApi.getAccountHierarchy(accountId);
      if (response.success && response.data) {
        setHierarchy(response.data);
        // Auto-expand root node
        setExpanded(new Set([response.data.id]));
      } else {
        setError(response.message || 'Failed to load hierarchy');
      }
    } catch (err) {
      console.error('Failed to load hierarchy:', err);
      setError('Failed to load account hierarchy');
      toast.error('Failed to load account hierarchy');
    } finally {
      setLoading(false);
    }
  }, [accountId]);

  useEffect(() => {
    loadHierarchy();
  }, [loadHierarchy]);

  // Toggle node expansion
  const handleToggle = useCallback((id: string) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }, []);

  // Handle node selection
  const handleSelect = useCallback((node: VAHierarchyNode) => {
    setSelectedId(node.id);
    if (onSelectAccount) {
      onSelectAccount(node);
    }
  }, [onSelectAccount]);

  // Expand all nodes
  const expandAll = useCallback(() => {
    if (!hierarchy) return;

    const getAllIds = (node: VAHierarchyNode): string[] => {
      const ids = [node.id];
      if (node.children) {
        node.children.forEach((child) => {
          ids.push(...getAllIds(child));
        });
      }
      return ids;
    };

    setExpanded(new Set(getAllIds(hierarchy)));
  }, [hierarchy]);

  // Collapse all nodes
  const collapseAll = useCallback(() => {
    setExpanded(new Set());
  }, []);

  // Calculate totals
  const totals = useMemo(() => {
    if (!hierarchy) return null;

    const calculateTotals = (node: VAHierarchyNode): { count: number; balance: number } => {
      let count = 1;
      let balance = node.currentBalance;

      if (node.children) {
        node.children.forEach((child) => {
          const childTotals = calculateTotals(child);
          count += childTotals.count;
          // Don't double count - use aggregated balance at root level
        });
      }

      return { count, balance };
    };

    const result = calculateTotals(hierarchy);
    return {
      accountCount: result.count,
      totalBalance: hierarchy.aggregatedBalance ?? hierarchy.currentBalance,
    };
  }, [hierarchy]);

  // Loading state
  if (loading) {
    return (
      <Card className={className}>
        <div className="space-y-3">
          <div className="flex items-center gap-3 mb-4">
            <Skeleton className="w-10 h-10 rounded-lg" />
            <div className="flex-1">
              <Skeleton className="h-5 w-40 mb-1" />
              <Skeleton className="h-3 w-24" />
            </div>
          </div>
          {[...Array(5)].map((_, i) => (
            <div key={i} className="flex items-center gap-3" style={{ paddingLeft: `${(i % 3) * 24}px` }}>
              <Skeleton className="w-6 h-6 rounded-md" />
              <Skeleton className="w-8 h-8 rounded-lg" />
              <div className="flex-1">
                <Skeleton className="h-4 w-32 mb-1" />
                <Skeleton className="h-3 w-20" />
              </div>
              <Skeleton className="h-4 w-24" />
            </div>
          ))}
        </div>
      </Card>
    );
  }

  // Error state
  if (error) {
    return (
      <Card className={cn('text-center py-8', className)}>
        <XCircle className="w-12 h-12 text-error-300 mx-auto mb-4" />
        <h3 className="section-title mb-2">Failed to Load Hierarchy</h3>
        <p className="text-neutral-500 dark:text-neutral-400 mb-4">{error}</p>
        <Button variant="outline" onClick={loadHierarchy} leftIcon={<RefreshCw className="w-4 h-4" />}>
          Retry
        </Button>
      </Card>
    );
  }

  // Empty state
  if (!hierarchy) {
    return (
      <Card className={cn('text-center py-8', className)}>
        <Layers className="w-12 h-12 text-neutral-300 mx-auto mb-4 dark:text-neutral-400" />
        <h3 className="section-title mb-2">No Hierarchy Found</h3>
        <p className="text-neutral-500 dark:text-neutral-400">This account does not have any child accounts.</p>
      </Card>
    );
  }

  return (
    <Card padding="none" className={className}>
      {/* Header */}
      <div className="p-4 border-b border-edge bg-gradient-to-r from-neutral-50 to-white dark:from-primary-950 dark:to-primary-900">
        <div className="flex items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <StatusIconBadge tone="cat-2" icon={Layers} subtle />
            <div>
              <h3 className="font-semibold text-primary-900 dark:text-neutral-50">Account Hierarchy</h3>
              <p className="caption">
                {totals?.accountCount} accounts
                {totals && ` | Total: ${formatCurrency(totals.totalBalance, hierarchy.currency)}`}
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <Button variant="ghost" size="sm" onClick={expandAll}>
              Expand All
            </Button>
            <Button variant="ghost" size="sm" onClick={collapseAll}>
              Collapse
            </Button>
            <Button variant="outline" size="sm" onClick={loadHierarchy} leftIcon={<RefreshCw className="w-4 h-4" />}>
              Refresh
            </Button>
          </div>
        </div>
      </div>

      {/* Tree View */}
      <div className="p-2 max-h-[500px] overflow-y-auto">
        <TreeNode
          node={hierarchy}
          level={0}
          expanded={expanded}
          onToggle={handleToggle}
          onSelect={handleSelect}
          onGenerateStatement={onGenerateStatement}
          selectedId={selectedId || undefined}
        />
      </div>

      {/* Legend */}
      <div className="p-3 border-t border-edge-subtle bg-surface-page">
        <div className="flex flex-wrap items-center gap-3 text-caption">
          <span className="font-medium text-neutral-600 dark:text-neutral-300">Types:</span>
          {Object.entries(accountCategoryConfig).slice(0, 4).map(([key, config]) => {
            const Icon = config.icon;
            return (
              <div key={key} className="flex items-center gap-1">
                <div className={cn('w-4 h-4 rounded-md flex items-center justify-center', config.bgColor)}>
                  <Icon className={cn('w-2.5 h-2.5', config.color)} />
                </div>
                <span className="text-neutral-600 dark:text-neutral-300">{config.label}</span>
              </div>
            );
          })}
        </div>
      </div>
    </Card>
  );
};

export default VAHierarchyViewer;
