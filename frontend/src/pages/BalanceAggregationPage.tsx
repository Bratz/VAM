import { Page } from '../components/layout/Page';
import { PageHeader } from '../components/layout/PageHeader';
import { StatStrip } from '../components/layout/StatStrip';
/**
 * BalanceAggregationPage - Connected to Backend
 * 
 * Uses: balanceAggregationApi from api.ts
 * Backend: BalanceAggregationController.java at /api/v1/treasury/aggregation/*
 */
import React, { useState, useEffect, useCallback } from 'react';
import {
  Layers, RefreshCw, Search, Loader2, ChevronRight, ChevronDown,
  Building2, Globe, MapPin, Briefcase, Wallet, TrendingUp,
  DollarSign, PieChart, Clock, Eye, Download, Building,
} from 'lucide-react';
import { Card, Button, Badge, Input , StatusIconBadge } from '../components/ui';
import { Modal } from '../components/ui/enhanced';
import { formatCurrency, cn, formatDate } from '../utils';
import { balanceAggregationApi, programsApi, corporatesApi, MultiCurrencyPosition, ApiResponse } from '../services/api';

// ============================================================================
// TYPES
// ============================================================================

interface HierarchyBalance {
  nodeId: string;
  nodeName: string;
  nodeCode: string;
  nodeType: string;
  level: number;
  currency: string;
  ownBalance: number;
  aggregatedBalance: number;
  aggregatedBalanceBase: number;
  childCount: number;
  children?: HierarchyBalance[];
  fxRate?: number;
  lastUpdated?: string;
  status?: string;
}

interface AggregationSummary {
  corporateId: string;
  baseCurrency: string;
  totalBalanceBase: number;
  totalAccounts: number;
  currencyCount: number;
  lastAggregatedAt: string;
}

interface Corporate {
  id: string;
  corporateId: string;
  legalName: string;
  tradeName?: string;
  shortName?: string;
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

// Helper
const extractData = <T,>(response: ApiResponse<T>): T => {
  if (response && response.data !== undefined) return response.data;
  return response as unknown as T;
};

// ============================================================================
// CONSTANTS
// ============================================================================

const NODE_TYPE_CONFIG: Record<string, { label: string; icon: any; color: string; bgColor: string }> = {
  ROOT: { label: 'Root', icon: Building2, color: 'text-cat-2', bgColor: 'bg-cat-2/10 dark:bg-cat-2/15' },
  REGION: { label: 'Region', icon: Globe, color: 'text-info-700 dark:text-info-300', bgColor: 'bg-info-100 dark:bg-info-500/20' },
  COUNTRY: { label: 'Country', icon: MapPin, color: 'text-success-700 dark:text-success-300', bgColor: 'bg-success-100 dark:bg-success-500/20' },
  LEGAL_ENTITY: { label: 'Legal Entity', icon: Building2, color: 'text-warning-700 dark:text-warning-300', bgColor: 'bg-warning-100 dark:bg-warning-500/20' },
  BUSINESS_UNIT: { label: 'Business Unit', icon: Briefcase, color: 'text-cat-3', bgColor: 'bg-cat-3/10 dark:bg-cat-3/15' },
  DEPARTMENT: { label: 'Department', icon: Briefcase, color: 'text-cat-1', bgColor: 'bg-cat-1/10 dark:bg-cat-1/15' },
  VIRTUAL_ACCOUNT: { label: 'Virtual Account', icon: Wallet, color: 'text-cat-4', bgColor: 'bg-cat-4/10 dark:bg-cat-4/15' },
};

const CURRENCIES = ['AED', 'USD', 'EUR', 'GBP', 'SAR', 'SGD', 'INR'];

// ============================================================================
// TREE NODE COMPONENT
// ============================================================================

interface TreeNodeProps {
  node: HierarchyBalance;
  level: number;
  baseCurrency: string;
  onSelect: (node: HierarchyBalance) => void;
  expandedNodes: Set<string>;
  toggleExpand: (nodeId: string) => void;
}

const TreeNode: React.FC<TreeNodeProps> = ({ node, level, baseCurrency, onSelect, expandedNodes, toggleExpand }) => {
  const config = NODE_TYPE_CONFIG[node.nodeType] || NODE_TYPE_CONFIG.VIRTUAL_ACCOUNT;
  const Icon = config.icon;
  const isExpanded = expandedNodes.has(node.nodeId);
  const hasChildren = (node.children?.length || 0) > 0 || node.childCount > 0;
  const indent = level * 24;

  return (
    <div>
      <div
        className={cn(
          "flex items-center py-2 px-3 hover:bg-neutral-50 cursor-pointer border-b border-neutral-100 dark:hover:bg-primary-800/50 dark:border-primary-800/60",
          level === 0 && "bg-neutral-50 font-semibold dark:bg-primary-950"
        )}
        style={{ paddingLeft: `${indent + 12}px` }}
        onClick={() => onSelect(node)}
      >
        {hasChildren ? (
          <button
            onClick={(e) => { e.stopPropagation(); toggleExpand(node.nodeId); }}
            className="p-1 hover:bg-neutral-200 rounded mr-1"
          >
            {isExpanded ? <ChevronDown className="w-4 h-4" /> : <ChevronRight className="w-4 h-4" />}
          </button>
        ) : (
          <span className="w-6" />
        )}

        <div className={cn("w-6 h-6 rounded flex items-center justify-center mr-2", config.bgColor)}>
          <Icon className={cn("w-3 h-3", config.color)} />
        </div>

        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <span className="font-medium text-primary-900 truncate dark:text-neutral-50">{node.nodeName}</span>
            <span className="text-xs text-neutral-400 dark:text-neutral-500">{node.nodeCode}</span>
          </div>
        </div>

        <div className="flex items-center gap-4 text-sm">
          <div className="text-right">
            <div className="font-medium text-primary-900 dark:text-neutral-50">
              {formatCurrency(node.aggregatedBalanceBase || node.aggregatedBalance, baseCurrency)}
            </div>
            {node.currency !== baseCurrency && node.ownBalance > 0 && (
              <div className="text-xs text-neutral-500 dark:text-neutral-400">
                {formatCurrency(node.ownBalance, node.currency)}
              </div>
            )}
          </div>
          {node.fxRate && node.fxRate !== 1 && (
            <div className="text-xs text-neutral-400 w-16 text-right dark:text-neutral-500">
              @{node.fxRate.toFixed(4)}
            </div>
          )}
          <Badge className={cn("text-xs", config.bgColor, config.color)}>
            {node.childCount || 0}
          </Badge>
        </div>
      </div>

      {isExpanded && node.children?.map(child => (
        <TreeNode
          key={child.nodeId}
          node={child}
          level={level + 1}
          baseCurrency={baseCurrency}
          onSelect={onSelect}
          expandedNodes={expandedNodes}
          toggleExpand={toggleExpand}
        />
      ))}
    </div>
  );
};

// ============================================================================
// CURRENCY BREAKDOWN CHART
// ============================================================================

interface CurrencyBreakdownProps {
  position: MultiCurrencyPosition | null;
}

const CurrencyBreakdownChart: React.FC<CurrencyBreakdownProps> = ({ position }) => {
  if (!position || !position.positions || position.positions.length === 0) {
    return (
      <Card hover>
        <div className="p-4">
          <div className="flex items-center gap-3 mb-4">
            <StatusIconBadge tone="info" icon={PieChart} className="dark:bg-info-500/20" />
            <h3 className="section-title">Currency Breakdown</h3>
          </div>
          <p className="text-sm text-neutral-500 text-center py-8 dark:text-neutral-400">No currency data available</p>
        </div>
      </Card>
    );
  }

  const total = position.positions.reduce((sum, p) => sum + p.convertedBalance, 0);
  const colors = ['bg-primary-500', 'bg-success-500', 'bg-warning-500', 'bg-accent-500', 'bg-info-500', 'bg-error-500', 'bg-neutral-500'];

  return (
    <Card hover>
      <div className="p-4">
        <div className="flex items-center gap-3 mb-4">
          <StatusIconBadge tone="info" icon={PieChart} className="dark:bg-info-500/20" />
          <h3 className="section-title">Currency Breakdown</h3>
        </div>

        {/* Stacked Bar */}
        <div className="h-4 rounded-full overflow-hidden flex bg-neutral-200 mb-4 dark:bg-primary-800">
          {position.positions.map((p, i) => {
            const pct = total > 0 ? (p.convertedBalance / total) * 100 : 0;
            return (
              <div
                key={p.currency}
                className={cn("h-full transition-all", colors[i % colors.length])}
                style={{ width: `${pct}%` }}
                title={`${p.currency}: ${pct.toFixed(1)}%`}
              />
            );
          })}
        </div>

        {/* Legend */}
        <div className="space-y-2">
          {position.positions.map((p, i) => {
            const pct = total > 0 ? (p.convertedBalance / total) * 100 : 0;
            return (
              <div key={p.currency} className="flex items-center justify-between text-sm">
                <div className="flex items-center gap-2">
                  <div className={cn("w-3 h-3 rounded", colors[i % colors.length])} />
                  <span className="font-medium text-primary-900 dark:text-neutral-50">{p.currency}</span>
                </div>
                <div className="flex items-center gap-4">
                  <span className="text-neutral-500 dark:text-neutral-400">{formatCurrency(p.originalBalance, p.currency)}</span>
                  <span className="font-semibold text-primary-900 dark:text-neutral-50">{pct.toFixed(1)}%</span>
                </div>
              </div>
            );
          })}
        </div>

        {/* FX Rates */}
        <div className="mt-4 pt-4 border-t">
          <h4 className="label mb-2">Applied FX Rates</h4>
          <div className="grid grid-cols-2 gap-2 text-xs">
            {position.positions.filter(p => p.fxRate !== 1).map(p => (
              <div key={p.currency} className="flex justify-between">
                <span className="text-neutral-500 dark:text-neutral-400">{p.currency}/{position.baseCurrency}</span>
                <span className="font-medium text-primary-900 dark:text-neutral-50">{p.fxRate.toFixed(4)}</span>
              </div>
            ))}
          </div>
        </div>
      </div>
    </Card>
  );
};

// ============================================================================
// MAIN PAGE
// ============================================================================

const BalanceAggregationPage: React.FC = () => {
  const [hierarchy, setHierarchy] = useState<HierarchyBalance | null>(null);
  const [multiCurrencyPosition, setMultiCurrencyPosition] = useState<MultiCurrencyPosition | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [searchTerm, setSearchTerm] = useState('');
  const [baseCurrency, setBaseCurrency] = useState('AED');

  // Corporate and Program selection
  const [corporates, setCorporates] = useState<Corporate[]>([]);
  const [programs, setPrograms] = useState<ProgramOption[]>([]);
  const [selectedCorporateId, setSelectedCorporateId] = useState('');
  const [selectedProgramId, setSelectedProgramId] = useState('');
  const [loadingCorporates, setLoadingCorporates] = useState(true);
  const [loadingPrograms, setLoadingPrograms] = useState(false);

  const [expandedNodes, setExpandedNodes] = useState<Set<string>>(new Set());
  const [selectedNode, setSelectedNode] = useState<HierarchyBalance | null>(null);
  const [showDetailModal, setShowDetailModal] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Toggle expand
  const toggleExpand = useCallback((nodeId: string) => {
    setExpandedNodes(prev => {
      const next = new Set(prev);
      if (next.has(nodeId)) {
        next.delete(nodeId);
      } else {
        next.add(nodeId);
      }
      return next;
    });
  }, []);

  // Auto-expand first 2 levels
  const autoExpandLevels = useCallback((node: HierarchyBalance, currentLevel: number, expanded: Set<string>) => {
    if (currentLevel < 2) {
      expanded.add(node.nodeId);
      node.children?.forEach(child => autoExpandLevels(child, currentLevel + 1, expanded));
    }
  }, []);

  // Load corporates on mount
  useEffect(() => {
    const loadCorporates = async () => {
      try {
        setLoadingCorporates(true);
        const response = await corporatesApi.getAll();
        const corpData = Array.isArray(response) ? response : (response as any)?.data || [];
        setCorporates(corpData);
        if (corpData.length > 0) {
          setSelectedCorporateId(corpData[0].id);
        }
      } catch (err) {
        console.error('Failed to load corporates:', err);
      } finally {
        setLoadingCorporates(false);
      }
    };
    loadCorporates();
  }, []);

  // Load programs when corporate changes
  useEffect(() => {
    const loadPrograms = async () => {
      if (!selectedCorporateId) {
        setPrograms([]);
        setSelectedProgramId('');
        return;
      }

      try {
        setLoadingPrograms(true);
        const response = await programsApi.getByCorporate(selectedCorporateId);
        const programList = Array.isArray(response) ? response : (response as any)?.data || [];
        setPrograms(programList);

        // Auto-select first active program
        const activePrograms = programList.filter((p: ProgramOption) => p.status === 'ACTIVE');
        if (activePrograms.length > 0) {
          setSelectedProgramId(activePrograms[0].id);
          // Set base currency from program's currency
          if (activePrograms[0].currencyCode) {
            setBaseCurrency(activePrograms[0].currencyCode);
          }
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

  // Load data - now uses program-based API when programId is available
  const loadData = useCallback(async () => {
    if (!selectedCorporateId) {
      setLoading(false);
      return;
    }

    try {
      setLoading(true);
      setError(null);

      // Load multi-currency position - prefer program-based API for accurate breakdown
      try {
        let posResponse;
        if (selectedProgramId) {
          // Use program-based API (preferred for multi-program corporates)
          posResponse = await balanceAggregationApi.getMultiCurrencyPositionByProgram(selectedProgramId, baseCurrency);
        } else {
          // Fallback to corporate-based API
          posResponse = await balanceAggregationApi.getMultiCurrencyPosition(selectedCorporateId, baseCurrency);
        }
        const posData = extractData(posResponse);
        setMultiCurrencyPosition(posData);
      } catch (e) {
        console.warn('Failed to load multi-currency position:', e);
      }

      // Try to load balance by level for stats
      try {
        const balanceResponse = await balanceAggregationApi.getTotalBalance(selectedCorporateId);
        const totalBalance = extractData(balanceResponse);

        const selectedCorp = corporates.find(c => c.id === selectedCorporateId);
        const selectedProg = programs.find(p => p.id === selectedProgramId);

        // Build a simple hierarchy from the data we have
        const mockHierarchy: HierarchyBalance = {
          nodeId: 'root-1',
          nodeName: selectedProg?.programName || selectedCorp?.tradeName || selectedCorp?.legalName || 'Holdings',
          nodeCode: selectedProg?.programCode || selectedCorp?.shortName || 'ROOT',
          nodeType: 'ROOT',
          level: 0,
          currency: baseCurrency,
          ownBalance: 0,
          aggregatedBalance: totalBalance || 0,
          aggregatedBalanceBase: totalBalance || 0,
          childCount: 3,
          children: [],
        };

        setHierarchy(mockHierarchy);

        // Auto-expand
        const expanded = new Set<string>();
        autoExpandLevels(mockHierarchy, 0, expanded);
        setExpandedNodes(expanded);
      } catch (e) {
        console.warn('Failed to load hierarchy:', e);
      }

    } catch (err) {
      console.error('Failed to load balance aggregation:', err);
      setError('Failed to load balance aggregation data.');
    } finally {
      setLoading(false);
    }
  }, [selectedCorporateId, selectedProgramId, baseCurrency, autoExpandLevels, corporates, programs]);

  useEffect(() => {
    if (selectedCorporateId) {
      loadData();
    }
  }, [loadData, selectedCorporateId, selectedProgramId]);

  // Refresh
  const handleRefresh = async () => {
    if (!selectedCorporateId) return;
    try {
      setRefreshing(true);
      setError(null);
      await balanceAggregationApi.aggregateForCorporate(selectedCorporateId);
      await loadData();
    } catch (err) {
      console.error('Failed to refresh:', err);
      setError('Failed to refresh aggregation.');
    } finally {
      setRefreshing(false);
    }
  };

  // Handle corporate change
  const handleCorporateChange = (corpId: string) => {
    setSelectedCorporateId(corpId);
    setSelectedProgramId('');
    setHierarchy(null);
    setMultiCurrencyPosition(null);
  };

  // Handle program change
  const handleProgramChange = (programId: string) => {
    setSelectedProgramId(programId);
    setHierarchy(null);
    setMultiCurrencyPosition(null);
    // Update base currency from selected program
    const prog = programs.find(p => p.id === programId);
    if (prog?.currencyCode) {
      setBaseCurrency(prog.currencyCode);
    }
  };

  // Select node
  const handleSelectNode = (node: HierarchyBalance) => {
    setSelectedNode(node);
    setShowDetailModal(true);
  };

  // Stats
  const stats = {
    totalBalance: multiCurrencyPosition?.totalInBaseCurrency || 0,
    currencyCount: multiCurrencyPosition?.positions?.length || 0,
    baseCurrency: multiCurrencyPosition?.baseCurrency || baseCurrency,
    lastUpdated: multiCurrencyPosition?.asOf || new Date().toISOString(),
  };

  if (loadingCorporates) {
    return (
      <div className="flex items-center justify-center h-96">
        <Loader2 className="w-8 h-8 animate-spin text-primary-600 dark:text-primary-200" />
      </div>
    );
  }

  // Filter programs by selected corporate
  const filteredPrograms = programs.filter(p =>
    !selectedCorporateId || p.corporateId === selectedCorporateId
  );
  const activePrograms = filteredPrograms.filter(p => p.status === 'ACTIVE');
  const selectedProgram = programs.find(p => p.id === selectedProgramId);

  return (
    <Page>
      {/* Page Header with Quick Actions */}
      <PageHeader
        title="Balance Aggregation"
        actions={
          <>
            <div className="flex items-center gap-2 px-3 py-2 bg-neutral-100 rounded-lg dark:bg-primary-800">
              <span className="text-sm text-neutral-600 dark:text-neutral-300">Base:</span>
              <select
                value={baseCurrency}
                onChange={(e) => setBaseCurrency(e.target.value)}
                className="bg-transparent text-sm font-medium text-primary-900 outline-none cursor-pointer dark:text-neutral-50"
              >
                {CURRENCIES.map(c => (
                  <option key={c} value={c}>{c}</option>
                ))}
              </select>
            </div>
            <Button variant="outline" onClick={handleRefresh} disabled={refreshing}>
              {refreshing ? <Loader2 className="w-4 h-4 mr-2 animate-spin" /> : <RefreshCw className="w-4 h-4 mr-2" />}
              Refresh
            </Button>
            <Button variant="outline">
              <Download className="w-4 h-4 mr-2" /> Export
            </Button>
          </>
        }
      />

      {/* Corporate & Program Selector */}
      <Card padding="sm" className="bg-gradient-to-r from-primary-50/50 via-white to-info-50/50 border-primary-200/60 animate-fade-in dark:from-primary-900 dark:via-primary-900 dark:to-primary-900" style={{ animationDelay: '0.08s' }}>
        <div className="flex items-center gap-6 p-2">
          {/* Corporate Selector */}
          <div className="flex items-center gap-2">
            <StatusIconBadge tone="primary" icon={Building} className="dark:bg-primary-700" />
            <div className="min-w-[220px]">
              <label className="text-xs font-medium text-primary-700 uppercase tracking-wider dark:text-neutral-200">Corporate</label>
              <select
                value={selectedCorporateId}
                onChange={(e) => handleCorporateChange(e.target.value)}
                className="w-full mt-0.5 px-2 py-1.5 bg-white border border-primary-200 rounded-lg text-sm font-medium focus:ring-2 focus:ring-primary-500 dark:bg-primary-900 dark:border-primary-700"
                disabled={loadingCorporates}
              >
                <option value="">Select Corporate...</option>
                {corporates.map(corp => (
                  <option key={corp.id} value={corp.id}>
                    {corp.tradeName || corp.legalName}
                  </option>
                ))}
              </select>
            </div>
          </div>

          {/* Program Selector */}
          <div className="flex items-center gap-2">
            <StatusIconBadge tone="info" icon={Layers} className="dark:bg-info-500/20" />
            <div className="min-w-[220px]">
              <label className="text-xs font-medium text-info-700 uppercase tracking-wider dark:text-info-300">Program</label>
              <select
                value={selectedProgramId}
                onChange={(e) => handleProgramChange(e.target.value)}
                className="w-full mt-0.5 px-2 py-1.5 bg-white border border-info-200 rounded-lg text-sm font-medium focus:ring-2 focus:ring-info-500 dark:bg-primary-900 dark:border-info-500/30"
                disabled={loadingPrograms || activePrograms.length === 0}
              >
                <option value="">Select Program...</option>
                {activePrograms.map(prog => (
                  <option key={prog.id} value={prog.id}>
                    {prog.programName} ({prog.currencyCode})
                  </option>
                ))}
              </select>
            </div>
          </div>

          {/* Selected Program Badge */}
          {selectedProgram && (
            <Badge variant="info" className="ml-auto">
              {selectedProgram.programCode} • {selectedProgram.currencyCode}
            </Badge>
          )}

          {loadingPrograms && <Loader2 className="w-5 h-5 animate-spin text-info-600 ml-2 dark:text-info-300" />}
        </div>
      </Card>

      {/* Error Banner */}
      {error && (
        <Card className="bg-error-50 border-error-200 animate-fade-in dark:bg-error-500/10 dark:border-error-500/30">
          <div className="flex items-center justify-between p-4">
            <div className="flex items-center gap-3">
              <StatusIconBadge tone="error" icon={Layers} className="dark:bg-error-500/20" />
              <span className="text-error-700 font-medium dark:text-error-300">{error}</span>
            </div>
            <button onClick={() => setError(null)} className="text-error-500 hover:text-error-700 p-1">×</button>
          </div>
        </Card>
      )}

      {/* Stats */}
      <StatStrip>
        <Card hover className="animate-fade-in" style={{ animationDelay: '0.1s' }}>
          <div className="p-4">
            <div className="flex items-start justify-between">
              <div className="flex-1">
                <p className="label">Total Balance ({stats.baseCurrency})</p>
                <p className="stat-value-sm mt-1 text-success-600 dark:text-success-300">
                  {formatCurrency(stats.totalBalance, stats.baseCurrency)}
                </p>
              </div>
              <StatusIconBadge tone="success" icon={DollarSign} className="dark:bg-success-500/20" />
            </div>
          </div>
        </Card>
        <Card hover className="animate-fade-in" style={{ animationDelay: '0.15s' }}>
          <div className="p-4">
            <div className="flex items-start justify-between">
              <div className="flex-1">
                <p className="label">Hierarchy Nodes</p>
                <p className="stat-value-sm mt-1">{hierarchy?.childCount || 0}</p>
              </div>
              <StatusIconBadge tone="info" icon={Layers} className="dark:bg-info-500/20" />
            </div>
          </div>
        </Card>
        <Card hover className="animate-fade-in" style={{ animationDelay: '0.2s' }}>
          <div className="p-4">
            <div className="flex items-start justify-between">
              <div className="flex-1">
                <p className="label">Currencies</p>
                <p className="stat-value-sm mt-1">{stats.currencyCount}</p>
              </div>
              <StatusIconBadge tone="accent" icon={Globe} className="dark:bg-accent-500/20" />
            </div>
          </div>
        </Card>
        <Card hover className="animate-fade-in" style={{ animationDelay: '0.25s' }}>
          <div className="p-4">
            <div className="flex items-start justify-between">
              <div className="flex-1">
                <p className="label">Last Updated</p>
                <p className="text-lg font-bold mt-1 text-primary-900 tracking-tight dark:text-neutral-50">{formatDate(stats.lastUpdated)}</p>
              </div>
              <StatusIconBadge tone="warning" icon={Clock} className="dark:bg-warning-500/20" />
            </div>
          </div>
        </Card>
      </StatStrip>

      {/* Content */}
      <div className="grid grid-cols-3 gap-6 animate-fade-in" style={{ animationDelay: '0.3s' }}>
        {/* Hierarchy Tree */}
        <div className="col-span-2">
          <Card hover className="overflow-hidden">
            <div className="p-4 border-b flex items-center justify-between">
              <div className="flex items-center gap-3">
                <StatusIconBadge tone="primary" icon={Layers} className="dark:bg-primary-700" />
                <h3 className="section-title">Balance Hierarchy</h3>
              </div>
              <div className="relative">
                <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400 dark:text-neutral-500" />
                <Input
                  value={searchTerm}
                  onChange={(e) => setSearchTerm(e.target.value)}
                  placeholder="Search nodes..."
                  className="pl-9 w-64"
                />
              </div>
            </div>

            <div className="max-h-[600px] overflow-y-auto">
              {hierarchy ? (
                <TreeNode
                  node={hierarchy}
                  level={0}
                  baseCurrency={baseCurrency}
                  onSelect={handleSelectNode}
                  expandedNodes={expandedNodes}
                  toggleExpand={toggleExpand}
                />
              ) : (
                <div className="p-12 text-center">
                  <Layers className="w-12 h-12 text-neutral-300 mx-auto mb-4 dark:text-neutral-600" />
                  <p className="text-neutral-500 dark:text-neutral-400">No hierarchy data available</p>
                  <Button className="mt-4" onClick={handleRefresh}>
                    <RefreshCw className="w-4 h-4 mr-2" /> Load Data
                  </Button>
                </div>
              )}
            </div>
          </Card>
        </div>

        {/* Currency Breakdown */}
        <div className="space-y-4">
          <CurrencyBreakdownChart position={multiCurrencyPosition} />

          {/* Quick Actions */}
          <Card hover>
            <div className="p-4">
              <div className="flex items-center gap-3 mb-4">
                <StatusIconBadge tone="accent" icon={TrendingUp} className="dark:bg-accent-500/20" />
                <h3 className="section-title">Quick Actions</h3>
              </div>
              <div className="space-y-2">
                <Button variant="outline" className="w-full justify-start" onClick={handleRefresh}>
                  <RefreshCw className="w-4 h-4 mr-2" /> Recalculate All Balances
                </Button>
                <Button variant="outline" className="w-full justify-start" onClick={async () => {
                  if (!selectedCorporateId) return;
                  try {
                    await balanceAggregationApi.updateCurrencyMirrors(selectedCorporateId);
                    await loadData();
                  } catch (e) {
                    setError('Failed to update currency mirrors.');
                  }
                }}>
                  <TrendingUp className="w-4 h-4 mr-2" /> Update FX Rates
                </Button>
              </div>
            </div>
          </Card>
        </div>
      </div>

      {/* Node Detail Modal */}
      <Modal isOpen={showDetailModal} onClose={() => setShowDetailModal(false)} title="Node Details" size="lg">
        {selectedNode && (
          <div className="p-4 space-y-4">
            <div className="flex items-center gap-4">
              <div className={cn(
                "w-12 h-12 rounded-xl flex items-center justify-center",
                NODE_TYPE_CONFIG[selectedNode.nodeType]?.bgColor || 'bg-neutral-100 dark:bg-primary-800'
              )}>
                {(() => {
                  const Icon = NODE_TYPE_CONFIG[selectedNode.nodeType]?.icon || Building2;
                  return <Icon className={cn("w-6 h-6", NODE_TYPE_CONFIG[selectedNode.nodeType]?.color || 'text-neutral-600 dark:text-neutral-300')} />;
                })()}
              </div>
              <div>
                <h3 className="section-title">{selectedNode.nodeName}</h3>
                <p className="text-sm text-neutral-500 dark:text-neutral-400">{selectedNode.nodeCode} • {NODE_TYPE_CONFIG[selectedNode.nodeType]?.label}</p>
              </div>
            </div>

            <div className="grid grid-cols-2 gap-4 pt-4 border-t">
              <div>
                <p className="text-sm text-neutral-500 dark:text-neutral-400">Own Balance</p>
                <p className="text-lg font-semibold">{formatCurrency(selectedNode.ownBalance, selectedNode.currency)}</p>
              </div>
              <div>
                <p className="text-sm text-neutral-500 dark:text-neutral-400">Aggregated Balance</p>
                <p className="text-lg font-semibold">{formatCurrency(selectedNode.aggregatedBalance, selectedNode.currency)}</p>
              </div>
              <div>
                <p className="text-sm text-neutral-500 dark:text-neutral-400">In Base Currency ({baseCurrency})</p>
                <p className="text-lg font-semibold text-success-600 dark:text-success-300">
                  {formatCurrency(selectedNode.aggregatedBalanceBase, baseCurrency)}
                </p>
              </div>
              <div>
                <p className="text-sm text-neutral-500 dark:text-neutral-400">Child Nodes</p>
                <p className="text-lg font-semibold">{selectedNode.childCount}</p>
              </div>
              {selectedNode.fxRate && selectedNode.fxRate !== 1 && (
                <div>
                  <p className="text-sm text-neutral-500 dark:text-neutral-400">FX Rate Applied</p>
                  <p className="text-lg font-semibold">{selectedNode.fxRate.toFixed(4)}</p>
                </div>
              )}
              <div>
                <p className="text-sm text-neutral-500 dark:text-neutral-400">Level</p>
                <p className="text-lg font-semibold">{selectedNode.level}</p>
              </div>
            </div>

            <div className="flex justify-end pt-4 border-t">
              <Button variant="ghost" onClick={() => setShowDetailModal(false)}>Close</Button>
            </div>
          </div>
        )}
      </Modal>
    </Page>
  );
};

export default BalanceAggregationPage;