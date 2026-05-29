import React, { useState, useEffect, useCallback } from 'react';
import axios from 'axios';
import toast from 'react-hot-toast';
import {
  Plus, Search, RefreshCw, Download, Eye, Settings, ArrowUpRight, ArrowDownRight,
  Landmark, DollarSign, CreditCard, CheckCircle, AlertCircle, Clock, MoreHorizontal,
  ExternalLink, Copy, Layers, X, Building2, Globe, Link2, Shield, Wifi, WifiOff,
  Server, TrendingUp, TrendingDown, Activity, BarChart3, PieChart, Banknote,
  FileText, Upload, ChevronDown, ChevronRight, Filter, Zap, GitBranch, Unlink,
  AlertTriangle, Info, Loader2, MapPin, Check, Building, Users, Coins,
} from 'lucide-react';
import { Card, Button, Badge, Skeleton, EmptyState, StatusIconBadge } from '../components/ui';
import { CurrencyPicker } from '../components/ui/CurrencyPicker';
import { Modal } from '../components/ui/enhanced';
import { HeroMetricCard } from '../components/ui/HeroMetricCard';
import { formatCurrency, formatDate, cn } from '../utils';
import { Page } from '../components/layout/Page';
import { PageHeader } from '../components/layout/PageHeader';
import { StatStrip } from '../components/layout/StatStrip';
import { ScopeSelector } from '../components/layout/ScopeSelector';
import { usePageHeaderActions } from '../context/PageHeaderContext';
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8053/api/v1';
const apiClient = axios.create({
  baseURL: API_BASE_URL,
  headers: { 'Content-Type': 'application/json' },
  timeout: 30000,
});

// ============================================================================
// TYPES
// ============================================================================

interface PhysicalAccount {
  id: string;
  accountNumber: string;
  iban: string;
  accountName: string;
  accountType: string;
  bankName: string;
  bankCode: string;
  bankCountry: string;
  branchName?: string;
  bankRelationship: 'INTERNAL' | 'EXTERNAL';
  dataSource: string;
  isHomeBank: boolean;
  isExternalBank: boolean;
  hasRealTimeData: boolean;
  apiProvider?: string;
  consentExpiresAt?: string;
  canViewBalance: boolean;
  canViewTransactions: boolean;
  canInitiatePayments: boolean;
  canReceiveTransfers: boolean;
  canHostVirtualAccounts: boolean;
  poolingEligible: boolean;
  sweepEligible: boolean;
  corporateId: string;
  corporateName?: string;
  legalEntityId?: string;
  entityId?: string;
  entityName?: string;
  entityCode?: string;
  currency: string;
  currentBalance: number;
  availableBalance: number;
  ledgerBalance: number;
  balanceAsOf?: string;
  interestRate?: number;
  interestType?: string;
  poolingEnabled: boolean;
  poolReference?: string;
  sweepEnabled: boolean;
  sweepRole?: string;
  virtualAccountCount: number;
  shadowVaId?: string;
  shadowVaNumber?: string;
  status: string;
  lastTransactionAt?: string;
  syncStatus: string;
  lastSyncAt: string;
  syncFrequencyMinutes?: number;
  createdAt: string;
  openedDate?: string;
  relationshipManager?: string;
}

interface BankSummary {
  bankCode: string;
  bankName: string;
  bankCountry?: string;
  accountCount: number;
  totalBalance: number;
  currencies: string[];
  bankRelationship?: string;
  vaHostCount?: number;
}

interface Stats {
  total: number;
  active: number;
  dormant: number;
  poolingEnabled: number;
  sweepEnabled: number;
  /** Headline number — total in the dominant currency, NOT cross-currency. */
  totalBalance: number;
  /** Headline available — also dominant-currency only. */
  totalAvailableBalance: number;
  /** FX-honest per-currency breakdown. Render this in the hero. */
  balancesByCurrency?: Record<string, number>;
  availableByCurrency?: Record<string, number>;
  /** Currency code matching the headline `totalBalance`. */
  dominantCurrency?: string;
  bankCount: number;
  /** Currency codes present in the scoped accounts (already filtered). */
  currencies: string[];
  syncedCount: number;
  pendingCount: number;
  errorCount: number;
  homeBankCount?: number;
  externalBankCount?: number;
  /** The BIC that drives the home/external classification. */
  homeBankBic?: string;
  homeBankName?: string;
}

interface AccountListResponse {
  content: PhysicalAccount[];
  page: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
}

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
  isTreasuryCenter: boolean;
  canHoldPhysicalAccounts: boolean;
  status: string;
  parentEntityId?: string;
  hierarchyLevel?: number;
}

// Hierarchy Node for LinkToHierarchyModal
interface HierarchyNode {
  id: string;
  vaNumber: string;
  vaName: string;
  accountCategory: 'ROOT' | 'AGGREGATION' | 'CURRENCY_MIRROR' | 'PHYSICAL_MIRROR' | 'TRANSACTION' | 'SETTLEMENT' | 'EXCEPTION';
  currencyCode: string;
  hierarchyLevel: number;
  hierarchyPathVa?: string;
  aggregatedBalance?: number;
  childCount?: number;
  children?: HierarchyNode[];
}

interface CreateShadowResponse {
  success: boolean;
  shadowVa?: {
    id: string;
    vaNumber: string;
    vaName: string;
  };
  exceptionVaCreated?: boolean;
  currencyMirrorsCreated?: string[];
  message: string;
}

// ============================================================================
// API CLIENTS
// ============================================================================

const physicalAccountsApi = {
  getStats: async (corporateId?: string, legalEntityId?: string): Promise<Stats> => {
    const params: any = {};
    if (corporateId) params.corporateId = corporateId;
    if (legalEntityId) params.legalEntityId = legalEntityId;
    const response = await apiClient.get('/physical-accounts/stats', { params });
    return response.data.data;
  },
  getBankSummaries: async (corporateId?: string): Promise<BankSummary[]> => {
    const params: any = {};
    if (corporateId) params.corporateId = corporateId;
    const response = await apiClient.get('/physical-accounts/banks', { params });
    return response.data.data;
  },
  getAccounts: async (params: any): Promise<AccountListResponse> => {
    const response = await apiClient.get('/physical-accounts', { params });
    return response.data.data;
  },
  getAccountById: async (id: string): Promise<PhysicalAccount> => {
    const response = await apiClient.get(`/physical-accounts/${id}`);
    return response.data.data;
  },
  createAccount: async (data: any): Promise<PhysicalAccount> => {
    const response = await apiClient.post('/physical-accounts', data);
    return response.data.data;
  },
  syncAccount: async (id: string): Promise<PhysicalAccount> => {
    const response = await apiClient.post(`/physical-accounts/${id}/sync`);
    return response.data.data;
  },
  syncAllAccounts: async (corporateId?: string): Promise<any> => {
    const params: any = {};
    if (corporateId) params.corporateId = corporateId;
    const response = await apiClient.post('/physical-accounts/sync-all', null, { params });
    return response.data.data;
  },
  getCurrencies: async (corporateId?: string, legalEntityId?: string): Promise<string[]> => {
    // Scope by corporate/entity so the "Across N currencies" chip row
    // reflects only what the user is looking at, not the entire DB.
    const params: any = {};
    if (corporateId) params.corporateId = corporateId;
    if (legalEntityId) params.legalEntityId = legalEntityId;
    const response = await apiClient.get('/physical-accounts/currencies', { params });
    return response.data.data;
  },
};

const corporatesApi = {
  getAll: async (): Promise<Corporate[]> => {
    try {
      const response = await apiClient.get('/corporates');
      return response.data.data || [];
    } catch { return []; }
  },
};

const legalEntityApi = {
  getByCorporate: async (corporateId: string): Promise<LegalEntity[]> => {
    try {
      const response = await apiClient.get(`/legal-entities/corporate/${corporateId}`);
      return response.data.data || [];
    } catch { return []; }
  },
};

// Shadow Account API
const shadowAccountApi = {
  create: async (data: {
    physicalAccountId: string;
    corporateId: string;
    parentVaId?: string;
    legalEntityId?: string;
  }): Promise<CreateShadowResponse> => {
    const response = await apiClient.post('/treasury/shadow-accounts', data);
    return response.data;
  },
};

// Hierarchy API - Get available parent nodes (ROOT and AGGREGATION)
const hierarchyApi = {
  getHierarchyNodes: async (corporateId: string): Promise<HierarchyNode[]> => {
    try {
      const response = await apiClient.get('/treasury/balance-structure', { 
        params: { corporateId } 
      });
      // Flatten hierarchy to get ROOT and AGGREGATION nodes only
      const flattenNodes = (node: HierarchyNode, result: HierarchyNode[] = []): HierarchyNode[] => {
        if (node.accountCategory === 'ROOT' || node.accountCategory === 'AGGREGATION') {
          result.push(node);
        }
        if (node.children) {
          node.children.forEach(child => flattenNodes(child, result));
        }
        return result;
      };
      
      const rootNode = response.data.data;
      if (rootNode) {
        return flattenNodes(rootNode);
      }
      return [];
    } catch (err) {
      console.error('Failed to fetch hierarchy nodes:', err);
      return [];
    }
  },
  
  // Alternative: Get from virtual accounts endpoint filtered by category
  getAggregationNodes: async (corporateId: string): Promise<HierarchyNode[]> => {
    try {
      const response = await apiClient.get('/virtual-accounts', { 
        params: { 
          corporateId,
          accountCategory: 'ROOT,AGGREGATION',
          size: 100
        } 
      });
      const data = response.data.data?.content || response.data.data || [];
      return Array.isArray(data) ? data : [];
    } catch (err) {
      console.error('Failed to fetch aggregation nodes:', err);
      return [];
    }
  },
};

// ============================================================================
// HELPER FUNCTIONS
// ============================================================================

const getDataSourceLabel = (source: string) => {
  const labels: Record<string, string> = {
    'CORE_BANKING': 'Core Banking', 'OPEN_BANKING_PSD2': 'Open Banking (PSD2)',
    'OPEN_BANKING_UAE': 'Open Banking (UAE)', 'OPEN_BANKING_UK': 'Open Banking (UK)',
    'SWIFT_MT940': 'SWIFT MT940', 'TARABUT': 'Tarabut Gateway', 'LEAN': 'Lean Technologies',
  };
  return labels[source] || source;
};

const formatAccountNumber = (acc: PhysicalAccount) => acc.iban ? acc.iban.replace(/(.{4})/g, '$1 ').trim() : acc.accountNumber;

const getCategoryIcon = (category: string) => {
  switch (category) {
    case 'ROOT': return <Globe className="w-4 h-4 text-purple-600 dark:text-purple-300" />;
    case 'AGGREGATION': return <Layers className="w-4 h-4 text-indigo-600 dark:text-indigo-300" />;
    default: return <Building2 className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />;
  }
};

const getCategoryColor = (category: string) => {
  switch (category) {
    case 'ROOT': return 'bg-purple-50 border-purple-200 dark:bg-purple-500/10 dark:border-purple-500/30';
    case 'AGGREGATION': return 'bg-indigo-50 border-indigo-200 dark:bg-indigo-500/10 dark:border-indigo-500/30';
    default: return 'bg-neutral-50 border-neutral-200 dark:bg-primary-950 dark:border-primary-800';
  }
};

// ============================================================================
// STATS CARD COMPONENT
// ============================================================================

const StatsCard: React.FC<{ title: string; value: string | number; subtitle?: string; icon: React.ReactNode; iconBg?: string; loading?: boolean; delay?: number }> =
  ({ title, value, subtitle, icon, iconBg = 'bg-primary-100 dark:bg-primary-700', loading, delay = 0 }) => {
  if (loading) {
    return (
      <Card hover className="animate-fade-in" style={{ animationDelay: `${delay}s` }}>
        <div className="flex items-center gap-3">
          <Skeleton className="w-12 h-12 rounded-xl" />
          <div className="flex-1">
            <Skeleton className="h-3 w-16 mb-2" />
            <Skeleton className="h-6 w-20" />
          </div>
        </div>
      </Card>
    );
  }

  return (
    <Card hover className="animate-fade-in" style={{ animationDelay: `${delay}s` }}>
      <div className="flex items-center gap-3">
        {/* The icon medallion preserves the caller-supplied `iconBg` because
            tiles pass their own tone (`bg-info-100`, `bg-warning-100`, etc.).
            Replacing this with `<StatusIconBadge>` would force a single tone
            and lose that per-tile information density — so the wrapper stays
            local. The icon itself is rendered through with `text-*` classes
            set by the caller (kept). */}
        <div className={cn("w-12 h-12 rounded-lg flex items-center justify-center shrink-0", iconBg)}>{icon}</div>
        <div className="min-w-0">
          <p className="label truncate">{title}</p>
          <p className="stat-value-sm truncate">{value}</p>
          {subtitle && <p className="body-sm text-neutral-500 dark:text-neutral-400 mt-0.5">{subtitle}</p>}
        </div>
      </div>
    </Card>
  );
};

// ============================================================================
// BANK CARD COMPONENT
// ============================================================================

const BankCard: React.FC<{ bank: BankSummary; isSelected: boolean; onClick: () => void }> = ({ bank, isSelected, onClick }) => {
  const isHomeBank = bank.bankRelationship === 'INTERNAL';
  return (
    <Card className={cn("cursor-pointer hover:shadow-medium transition-all", isSelected && "ring-2 ring-primary-500 bg-primary-50 dark:bg-primary-800/40")} onClick={onClick}>
      <div className="flex items-center gap-4">
        <div className={cn("w-12 h-12 rounded-xl flex items-center justify-center", isHomeBank ? "bg-primary-100 dark:bg-primary-700" : "bg-neutral-100 dark:bg-primary-800")}>
          {isHomeBank ? <Building2 className="w-6 h-6 text-primary-700 dark:text-neutral-200" /> : <Globe className="w-6 h-6 text-neutral-600 dark:text-neutral-300" />}
        </div>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <h3 className="section-title truncate">{bank.bankName || 'Unknown Bank'}</h3>
            {/* Tonal pill — matches the HOME BANK pill recipe from the
                MultiBankLiquidity conformance pass: 10px uppercase, accent
                tone for home bank, neutral for external. */}
            <span className={cn(
              "text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-full",
              isHomeBank
                ? "bg-accent-100 text-accent-700 dark:bg-accent-500/20 dark:text-accent-300"
                : "bg-neutral-100 text-neutral-600 dark:bg-primary-800/60 dark:text-neutral-300",
            )}>
              {isHomeBank ? 'Home bank' : 'External'}
            </span>
          </div>
          <p className="body-sm text-neutral-500 dark:text-neutral-400">{bank.bankCode} • {bank.accountCount} accounts</p>
        </div>
        <div className="text-right">
          <p className="label">Total Balance</p>
          <p className="stat-value-xs">{formatCurrency(bank.totalBalance, bank.currencies[0] || 'AED')}</p>
        </div>
      </div>
    </Card>
  );
};

// Picker now uses the shared `<ScopeSelector mode="corporate-entity">`
// primitive from components/layout/ — see import block at the top.
// The inline `CorporateEntityFilterBar` that lived here is removed.

// ============================================================================
// LINK TO HIERARCHY MODAL - NEW COMPONENT
// ============================================================================

interface LinkToHierarchyModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: () => void;
  account: PhysicalAccount | null;
  corporateId: string;
}

const LinkToHierarchyModal: React.FC<LinkToHierarchyModalProps> = ({
  isOpen,
  onClose,
  onSuccess,
  account,
  corporateId,
}) => {
  const [hierarchyNodes, setHierarchyNodes] = useState<HierarchyNode[]>([]);
  const [selectedParentId, setSelectedParentId] = useState<string>('');
  const [loading, setLoading] = useState(false);
  const [loadingNodes, setLoadingNodes] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Load hierarchy nodes when modal opens
  useEffect(() => {
    const loadNodes = async () => {
      if (!isOpen || !corporateId) return;
      
      setLoadingNodes(true);
      setError(null);
      
      try {
        // Try to get hierarchy nodes
        let nodes = await hierarchyApi.getHierarchyNodes(corporateId);
        
        // If no nodes from hierarchy, try alternative endpoint
        if (nodes.length === 0) {
          nodes = await hierarchyApi.getAggregationNodes(corporateId);
        }
        
        setHierarchyNodes(nodes);
        
        // Auto-select first AGGREGATION if available, otherwise ROOT
        const defaultNode = nodes.find(n => n.accountCategory === 'AGGREGATION') || nodes.find(n => n.accountCategory === 'ROOT');
        if (defaultNode) {
          setSelectedParentId(defaultNode.id);
        }
      } catch (err) {
        console.error('Failed to load hierarchy nodes:', err);
        setError('Failed to load hierarchy. Please ensure hierarchy is initialized.');
      } finally {
        setLoadingNodes(false);
      }
    };
    
    loadNodes();
  }, [isOpen, corporateId]);

  // Reset state when modal closes
  useEffect(() => {
    if (!isOpen) {
      setSelectedParentId('');
      setError(null);
    }
  }, [isOpen]);

  const handleSubmit = async () => {
    if (!account || !corporateId) return;
    
    setLoading(true);
    setError(null);
    
    try {
      const payload = {
        physicalAccountId: account.id,
        corporateId: corporateId,
        parentVaId: selectedParentId || undefined,
        legalEntityId: account.legalEntityId || account.entityId || undefined,
      };
      
      console.log('Creating shadow account with payload:', payload);
      
      const response = await shadowAccountApi.create(payload);
      
      // Show success notification
      const shadowVaNumber = response.shadowVa?.vaNumber || 'Shadow Account';
      let successMessage = `✓ Created ${shadowVaNumber}`;
      
      if (response.currencyMirrorsCreated && response.currencyMirrorsCreated.length > 0) {
        successMessage += ` + ${response.currencyMirrorsCreated.length} Currency Mirror(s)`;
      }
      if (response.exceptionVaCreated) {
        successMessage += ' + Exception VA';
      }
      
      toast.success(successMessage, {
        duration: 5000,
        icon: '🔗',
      });
      
      onSuccess();
      onClose();
    } catch (err: any) {
      const errorMessage = err.response?.data?.message || err.message || 'Failed to create shadow account';
      setError(errorMessage);
      toast.error(errorMessage);
    } finally {
      setLoading(false);
    }
  };

  if (!account) return null;

  const selectedNode = hierarchyNodes.find(n => n.id === selectedParentId);

  return (
    <Modal 
      isOpen={isOpen} 
      onClose={onClose} 
      title="Link Physical Account to Hierarchy" 
      size="lg"
    >
      <div className="space-y-5">
        {/* Error Alert */}
        {error && (
          <div className="p-3 bg-error-50 border border-error-200 rounded-lg flex items-start gap-2 text-error-700 dark:bg-error-500/10 dark:border-error-500/30 dark:text-error-300">
            <AlertCircle className="w-5 h-5 mt-0.5 flex-shrink-0" />
            <span className="text-sm">{error}</span>
          </div>
        )}

        {/* Physical Account Summary */}
        <div className="p-4 bg-info-50 border border-info-200 rounded-lg dark:bg-info-500/10 dark:border-info-500/30">
          <div className="flex items-start gap-4">
            <div className={cn(
              "w-12 h-12 rounded-xl flex items-center justify-center",
              account.isHomeBank ? "bg-primary-100 dark:bg-primary-700" : "bg-info-100 dark:bg-info-500/20"
            )}>
              {account.isHomeBank ? (
                <Building2 className="w-6 h-6 text-primary-700 dark:text-neutral-200" />
              ) : (
                <Globe className="w-6 h-6 text-info-600 dark:text-info-300" />
              )}
            </div>
            <div className="flex-1">
              <h3 className="font-semibold text-primary-900 dark:text-neutral-50">{account.accountName}</h3>
              <p className="text-sm text-neutral-600 dark:text-neutral-300">{account.bankName}</p>
              <p className="text-xs text-neutral-500 font-mono mt-1 dark:text-neutral-400">
                {account.iban || account.accountNumber}
              </p>
              <div className="flex items-center gap-3 mt-2">
                <Badge variant={account.isHomeBank ? 'primary' : 'info'} size="sm">
                  {account.isHomeBank ? 'Home Bank' : 'External Bank'}
                </Badge>
                <span className="stat-value-xs">
                  {formatCurrency(account.currentBalance, account.currency)}
                </span>
              </div>
            </div>
          </div>
        </div>

        {/* Hierarchy Node Selection */}
        <div>
          <label className="field-label block mb-2">
            Select Parent Node (AGGREGATION) <span className="text-error-500">*</span>
          </label>
          
          {loadingNodes ? (
            <div className="flex items-center justify-center py-8 bg-neutral-50 rounded-lg border border-dashed dark:bg-primary-950">
              <Loader2 className="w-6 h-6 animate-spin text-primary-600 mr-2 dark:text-primary-200" />
              <span className="text-neutral-600 dark:text-neutral-300">Loading hierarchy...</span>
            </div>
          ) : hierarchyNodes.length === 0 ? (
            <div className="p-4 bg-warning-50 border border-warning-200 rounded-lg dark:bg-warning-500/10 dark:border-warning-500/30">
              <div className="flex items-start gap-2">
                <AlertTriangle className="w-5 h-5 text-warning-600 mt-0.5 dark:text-warning-300" />
                <div>
                  <p className="body-sm text-warning-800 dark:text-warning-300">No Hierarchy Found</p>
                  <p className="body-sm text-warning-700 mt-1 dark:text-warning-300">
                    Please initialize the corporate hierarchy first before linking physical accounts.
                    Go to Treasury → Balance Structure to initialize.
                  </p>
                </div>
              </div>
            </div>
          ) : (
            <div className="space-y-2 max-h-64 overflow-y-auto border rounded-lg p-2">
              {hierarchyNodes.map((node) => (
                <div
                  key={node.id}
                  onClick={() => setSelectedParentId(node.id)}
                  className={cn(
                    "p-3 rounded-lg border-2 cursor-pointer transition-all",
                    selectedParentId === node.id
                      ? "border-primary-500 bg-primary-50 ring-2 ring-primary-200 dark:bg-primary-800/40"
                      : "border-transparent hover:border-neutral-300 hover:bg-neutral-50 dark:hover:border-primary-700 dark:hover:bg-primary-800/50",
                    getCategoryColor(node.accountCategory)
                  )}
                >
                  <div className="flex items-center gap-3">
                    <div className={cn(
                      "w-8 h-8 rounded-lg flex items-center justify-center",
                      node.accountCategory === 'ROOT' ? 'bg-purple-100 dark:bg-purple-500/20' : 'bg-indigo-100 dark:bg-indigo-500/20'
                    )}>
                      {getCategoryIcon(node.accountCategory)}
                    </div>
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2">
                        <span className="font-medium text-primary-900 dark:text-neutral-50">{node.vaName}</span>
                        <Badge 
                          variant={node.accountCategory === 'ROOT' ? 'neutral' : 'primary'} 
                          size="sm"
                        >
                          {node.accountCategory}
                        </Badge>
                      </div>
                      <p className="text-xs text-neutral-500 font-mono dark:text-neutral-400">{node.vaNumber}</p>
                    </div>
                    <div className="text-right">
                      <p className="text-xs text-neutral-500 dark:text-neutral-400">{node.currencyCode}</p>
                      {node.aggregatedBalance !== undefined && (
                        <p className="field-label">
                          {formatCurrency(node.aggregatedBalance, node.currencyCode)}
                        </p>
                      )}
                    </div>
                    {selectedParentId === node.id && (
                      <Check className="w-5 h-5 text-primary-600 dark:text-primary-200" />
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Info Banner */}
        <div className="p-3 bg-info-50 border border-info-200 rounded-lg dark:bg-info-500/10 dark:border-info-500/30">
          <div className="flex items-start gap-2">
            <Info className="w-4 h-4 text-info-600 mt-0.5 dark:text-info-300" />
            <div className="text-sm text-info-700 dark:text-info-300">
              <p className="font-medium">What happens when you link?</p>
              <ul className="mt-1 space-y-0.5 text-info-600 dark:text-info-300">
                <li>• A Shadow Account (PHYSICAL_MIRROR) is created under the selected node</li>
                <li>• Currency Mirror(s) are auto-created if this introduces a new currency</li>
                <li>• Exception VA is auto-created at ROOT if not exists for this currency</li>
                <li>• Bank balance will be synced for liquidity visibility</li>
              </ul>
            </div>
          </div>
        </div>

        {/* Selected Node Preview */}
        {selectedNode && (
          <div className="p-3 bg-success-50 border border-success-200 rounded-lg dark:bg-success-500/10 dark:border-success-500/30">
            <div className="flex items-center gap-2">
              <Check className="w-4 h-4 text-success-600 dark:text-success-300" />
              <span className="text-sm text-success-700 dark:text-success-300">
                Shadow Account will be created under: <strong>{selectedNode.vaName}</strong>
              </span>
            </div>
          </div>
        )}

        {/* Actions */}
        <div className="flex justify-end gap-3 pt-4 border-t">
          <Button variant="outline" onClick={onClose} disabled={loading}>
            Cancel
          </Button>
          <Button 
            onClick={handleSubmit} 
            disabled={loading || !selectedParentId || hierarchyNodes.length === 0}
            leftIcon={loading ? <Loader2 className="w-4 h-4 animate-spin" /> : <GitBranch className="w-4 h-4" />}
          >
            {loading ? 'Creating Shadow...' : 'Create Shadow Account'}
          </Button>
        </div>
      </div>
    </Modal>
  );
};

// ============================================================================
// ACCOUNT ROW COMPONENT - UPDATED WITH LINK BUTTON
// ============================================================================

const AccountRow: React.FC<{
  account: PhysicalAccount;
  onView: (a: PhysicalAccount) => void;
  onSync: (a: PhysicalAccount) => void;
  onLinkToHierarchy: (a: PhysicalAccount) => void;
}> = ({ account, onView, onSync, onLinkToHierarchy }) => {
  const entityIdValue = account.legalEntityId || account.entityId;
  const hasShadow = !!account.shadowVaId;

  return (
    <tr className="data-table-row group cursor-pointer" onClick={() => onView(account)}>
      <td className="data-table-cell">
        <div className="flex items-center gap-3">
          <div className={cn(
            'w-10 h-10 rounded-xl flex items-center justify-center shrink-0 transition-transform group-hover:scale-105',
            account.isHomeBank ? 'bg-primary-100 dark:bg-primary-700' : 'bg-info-50 dark:bg-info-500/10'
          )}>
            {account.isHomeBank ? <Building2 className="w-5 h-5 text-primary-700 dark:text-neutral-200" /> : <Globe className="w-5 h-5 text-info-600 dark:text-info-300" />}
          </div>
          <div className="min-w-0">
            <div className="flex items-center gap-2">
              {/* Account name reads as the dominant identifier in the row —
                  use body weight (`body` utility) at primary-900 tone. The
                  group-hover colour shift is preserved. */}
              <p className="body text-primary-900 truncate group-hover:text-primary-600 transition-colors dark:text-neutral-50">{account.accountName}</p>
              <span className={cn("px-1.5 py-0.5 rounded text-xs shrink-0", account.isHomeBank ? "bg-primary-50 text-primary-600 dark:bg-primary-800/40 dark:text-primary-200" : "bg-info-50 text-info-600 dark:bg-info-500/10 dark:text-info-300")}>
                {account.isHomeBank ? <Server className="w-3 h-3 inline" /> : <Wifi className="w-3 h-3 inline" />}
              </span>
            </div>
            <p className="code truncate mt-0.5">{formatAccountNumber(account)}</p>
          </div>
        </div>
      </td>
      <td className="data-table-cell">
        <p className="body-sm text-primary-900 dark:text-neutral-50">{account.entityName || '-'}</p>
        <p className="text-xs text-neutral-500 mt-0.5 dark:text-neutral-400">{account.entityCode || '-'}</p>
      </td>
      <td className="data-table-cell">
        <p className="body-sm text-primary-900 dark:text-neutral-50">{account.bankName}</p>
        {/* Bank code (SWIFT/BIC) is a tabular identifier — uses the `.code`
            typography utility (font-mono, primary text, xs). */}
        <p className="code mt-0.5">{account.bankCode}</p>
      </td>
      <td className="data-table-cell text-right">
        {/* Balance — Phase 9 display tier (Fraunces 20px + tabular-nums). */}
        <p className="stat-value-xs">{formatCurrency(account.currentBalance, account.currency)}</p>
      </td>
      <td className="data-table-cell text-center">
        {hasShadow ? (
          <div className="flex items-center justify-center gap-1">
            <Layers className="w-4 h-4 text-success-600 dark:text-success-300" />
            <span className="text-xs text-success-600 font-medium dark:text-success-300">Linked</span>
          </div>
        ) : (
          <span className="text-neutral-400 dark:text-neutral-500">—</span>
        )}
      </td>
      <td className="data-table-cell text-center">
        {entityIdValue ? <Building2 className="w-4 h-4 text-info-600 mx-auto dark:text-info-300" /> : <span className="text-neutral-400 dark:text-neutral-500">—</span>}
      </td>
      <td className="data-table-cell text-center">
        <Badge variant={account.syncStatus === 'SYNCED' ? 'success' : 'warning'} size="sm">{account.syncStatus}</Badge>
      </td>
      <td className="data-table-cell text-center" onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-center gap-1">
          {!hasShadow && (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => onLinkToHierarchy(account)}
              title="Link to Hierarchy"
              className="text-indigo-600 hover:text-indigo-700 hover:bg-indigo-50 opacity-0 group-hover:opacity-100 transition-opacity dark:text-indigo-300 dark:hover:bg-indigo-500/10"
            >
              <GitBranch className="w-4 h-4" />
            </Button>
          )}
          <Button variant="ghost" size="sm" onClick={() => onView(account)} title="View Details" className="opacity-0 group-hover:opacity-100 transition-opacity">
            <Eye className="w-4 h-4" />
          </Button>
          <Button variant="ghost" size="sm" onClick={() => onSync(account)} title="Sync Balance" className="opacity-0 group-hover:opacity-100 transition-opacity">
            <RefreshCw className="w-4 h-4" />
          </Button>
        </div>
      </td>
    </tr>
  );
};

// ============================================================================
// CREATE ACCOUNT MODAL
// ============================================================================

const CreateAccountModal: React.FC<{
  isOpen: boolean; onClose: () => void; onSuccess: () => void; isExternal?: boolean; corporateId?: string; entities?: LegalEntity[];
}> = ({ isOpen, onClose, onSuccess, isExternal = false, corporateId, entities = [] }) => {
  const [formData, setFormData] = useState({
    accountNumber: '', iban: '', accountName: '', accountType: 'CURRENT',
    bankName: isExternal ? '' : 'Emirates NBD', bankCode: isExternal ? '' : 'EABORAEDXXX',
    bankCountry: 'AE', branchName: '', legalEntityId: '', currencyCode: 'AED',
    initialBalance: '', interestRate: '', bankRelationship: isExternal ? 'EXTERNAL' : 'INTERNAL',
    dataSource: isExternal ? 'OPEN_BANKING_UAE' : 'CORE_BANKING',
  });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (isOpen) {
      setFormData({
        accountNumber: '', iban: '', accountName: '', accountType: 'CURRENT',
        bankName: isExternal ? '' : 'Emirates NBD', bankCode: isExternal ? '' : 'EABORAEDXXX',
        bankCountry: 'AE', branchName: '', legalEntityId: '', currencyCode: 'AED',
        initialBalance: '', interestRate: '', bankRelationship: isExternal ? 'EXTERNAL' : 'INTERNAL',
        dataSource: isExternal ? 'OPEN_BANKING_UAE' : 'CORE_BANKING',
      });
      setError(null);
    }
  }, [isOpen, isExternal]);

  const handleEntityChange = (entityId: string) => {
    const selectedEntity = entities.find(e => e.id === entityId);
    setFormData(prev => ({ ...prev, legalEntityId: entityId, currencyCode: selectedEntity?.functionalCurrency || prev.currencyCode }));
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError(null);
    try {
      if (!corporateId) throw new Error('Please select a Corporate first.');
      if (!formData.legalEntityId) throw new Error('Please select a Legal Entity.');
      if (!formData.accountNumber) throw new Error('Account Number is required.');
      if (!formData.accountName) throw new Error('Account Name is required.');
      if (!formData.bankName) throw new Error('Bank Name is required.');
      if (!formData.bankCode) throw new Error('Bank Code is required.');

      const payload = {
        corporateId,
        legalEntityId: formData.legalEntityId,
        accountNumber: formData.accountNumber,
        iban: formData.iban || null,
        accountName: formData.accountName,
        accountType: formData.accountType,
        bankName: formData.bankName,
        bankCode: formData.bankCode,
        bankCountry: formData.bankCountry || 'AE',
        branchName: formData.branchName || null,
        bankRelationship: formData.bankRelationship,
        dataSource: formData.dataSource,
        currencyCode: formData.currencyCode,
        initialBalance: formData.initialBalance ? parseFloat(formData.initialBalance) : 0,
        interestRate: formData.interestRate ? parseFloat(formData.interestRate) : null,
        interestType: 'CREDIT',
      };
      await physicalAccountsApi.createAccount(payload);
      toast.success('Account created successfully!');
      onSuccess();
      onClose();
    } catch (err: any) {
      const errorMessage = err.response?.data?.message || err.message || 'Failed to create account.';
      setError(errorMessage);
      toast.error(errorMessage);
    } finally { setLoading(false); }
  };

  const eligibleEntities = entities.filter(e => e.status === 'ACTIVE' && e.canHoldPhysicalAccounts);
  const selectedEntity = entities.find(e => e.id === formData.legalEntityId);

  return (
    <Modal isOpen={isOpen} onClose={onClose} title={isExternal ? "Link External Bank Account" : "Add Home Bank Account"} size="lg">
      <form onSubmit={handleSubmit} className="space-y-4">
        {error && <div className="p-3 bg-error-50 border border-error-200 rounded-lg flex items-start gap-2 text-error-700 dark:bg-error-500/10 dark:border-error-500/30 dark:text-error-300"><AlertCircle className="w-5 h-5 mt-0.5" /><span className="text-sm">{error}</span></div>}
        {!corporateId && <div className="p-3 bg-warning-50 border border-warning-200 rounded-lg flex items-start gap-2 text-warning-700 dark:bg-warning-500/10 dark:border-warning-500/30 dark:text-warning-300"><AlertTriangle className="w-5 h-5 mt-0.5" /><span className="text-sm">Please select a Corporate first.</span></div>}
        
        <div>
          <label className="field-label block mb-1">Legal Entity <span className="text-error-500">*</span></label>
          {eligibleEntities.length > 0 ? (
            <select required value={formData.legalEntityId} onChange={(e) => handleEntityChange(e.target.value)} className="w-full px-3 py-2 border border-neutral-300 rounded-lg dark:border-primary-700" disabled={!corporateId}>
              <option value="">Select Legal Entity...</option>
              {eligibleEntities.map(entity => <option key={entity.id} value={entity.id}>{entity.entityCode} - {entity.entityName}{entity.isTreasuryCenter ? ' ⭐' : ''} [{entity.functionalCurrency}]</option>)}
            </select>
          ) : <div className="p-3 bg-neutral-50 border rounded-lg text-sm text-neutral-600 dark:bg-primary-950 dark:text-neutral-300">{corporateId ? 'No eligible entities.' : 'Select a corporate first.'}</div>}
        </div>
        
        {selectedEntity && (
          <div className="p-3 bg-info-50 border border-info-200 rounded-lg dark:bg-info-500/10 dark:border-info-500/30">
            <div className="flex items-center gap-2 text-info-800 dark:text-info-300"><Building2 className="w-4 h-4" /><span className="font-medium">{selectedEntity.entityCode}</span><span>{selectedEntity.entityName}</span></div>
            <div className="mt-1 text-xs text-info-600 dark:text-info-300">Country: {selectedEntity.countryCode || 'N/A'} | Currency: {selectedEntity.functionalCurrency}</div>
          </div>
        )}

        <div className="grid grid-cols-2 gap-4">
          <div><label className="field-label block mb-1">Account Name *</label><input type="text" required value={formData.accountName} onChange={(e) => setFormData({...formData, accountName: e.target.value})} className="w-full px-3 py-2 border rounded-lg" placeholder="Main Operating Account" /></div>
          <div><label className="field-label block mb-1">Account Type</label>
            <select value={formData.accountType} onChange={(e) => setFormData({...formData, accountType: e.target.value})} className="w-full px-3 py-2 border rounded-lg">
              <option value="CURRENT">Current</option><option value="SAVINGS">Savings</option><option value="ESCROW">Escrow</option><option value="COLLECTION">Collection</option>
            </select>
          </div>
        </div>
        <div className="grid grid-cols-2 gap-4">
          <div><label className="field-label block mb-1">Account Number *</label><input type="text" required value={formData.accountNumber} onChange={(e) => setFormData({...formData, accountNumber: e.target.value})} className="w-full px-3 py-2 border rounded-lg" placeholder="1019876543210" /></div>
          <div><label className="field-label block mb-1">IBAN</label><input type="text" value={formData.iban} onChange={(e) => setFormData({...formData, iban: e.target.value.toUpperCase()})} className="w-full px-3 py-2 border rounded-lg" placeholder="AE070331019876543210001" maxLength={34} /></div>
        </div>
        <div className="border-t pt-4"><h3 className="section-title mb-3">Bank Information</h3>
          <div className="grid grid-cols-2 gap-4">
            <div><label className="field-label block mb-1">Bank Name *</label><input type="text" required value={formData.bankName} onChange={(e) => setFormData({...formData, bankName: e.target.value})} className="w-full px-3 py-2 border rounded-lg" /></div>
            <div><label className="field-label block mb-1">Bank Code (SWIFT) *</label><input type="text" required value={formData.bankCode} onChange={(e) => setFormData({...formData, bankCode: e.target.value.toUpperCase()})} className="w-full px-3 py-2 border rounded-lg" maxLength={11} /></div>
          </div>
          <div className="grid grid-cols-3 gap-4 mt-3">
            <div><label className="field-label block mb-1">Country</label><input type="text" value={formData.bankCountry} onChange={(e) => setFormData({...formData, bankCountry: e.target.value.toUpperCase()})} className="w-full px-3 py-2 border rounded-lg" maxLength={2} /></div>
            <div><label className="field-label block mb-1">Currency</label>
              <CurrencyPicker value={formData.currencyCode} onChange={(c) => setFormData({...formData, currencyCode: c})} extra={['SAR']} />
            </div>
            <div><label className="field-label block mb-1">Branch</label><input type="text" value={formData.branchName} onChange={(e) => setFormData({...formData, branchName: e.target.value})} className="w-full px-3 py-2 border rounded-lg" /></div>
          </div>
        </div>
        {!isExternal && (
          <div className="border-t pt-4"><h3 className="section-title mb-3">Balance & Interest</h3>
            <div className="grid grid-cols-2 gap-4">
              <div><label className="field-label block mb-1">Initial Balance</label><input type="number" step="0.01" value={formData.initialBalance} onChange={(e) => setFormData({...formData, initialBalance: e.target.value})} className="w-full px-3 py-2 border rounded-lg" /></div>
              <div><label className="field-label block mb-1">Interest Rate (%)</label><input type="number" step="0.01" value={formData.interestRate} onChange={(e) => setFormData({...formData, interestRate: e.target.value})} className="w-full px-3 py-2 border rounded-lg" /></div>
            </div>
          </div>
        )}
        <div className="flex justify-end gap-3 pt-4 border-t">
          <Button variant="outline" onClick={onClose} disabled={loading}>Cancel</Button>
          <Button type="submit" disabled={loading || !corporateId || eligibleEntities.length === 0} leftIcon={loading ? <Loader2 className="w-4 h-4 animate-spin" /> : <Plus className="w-4 h-4" />}>
            {loading ? 'Creating...' : isExternal ? 'Link Account' : 'Create Account'}
          </Button>
        </div>
      </form>
    </Modal>
  );
};

// ============================================================================
// MAIN PAGE COMPONENT
// ============================================================================

const PhysicalAccountsPage: React.FC = () => {
  const [stats, setStats] = useState<Stats | null>(null);
  const [bankSummaries, setBankSummaries] = useState<BankSummary[]>([]);
  const [accounts, setAccounts] = useState<PhysicalAccount[]>([]);
  const [loading, setLoading] = useState(true);
  const [syncing, setSyncing] = useState(false);
  const [corporates, setCorporates] = useState<Corporate[]>([]);
  const [legalEntities, setLegalEntities] = useState<LegalEntity[]>([]);
  const [selectedCorporateId, setSelectedCorporateId] = useState('');
  const [selectedEntityId, setSelectedEntityId] = useState('');
  const [searchQuery, setSearchQuery] = useState('');
  const [bankFilter, setBankFilter] = useState('ALL');
  const [currencyFilter, setCurrencyFilter] = useState('ALL');
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [selectedAccount, setSelectedAccount] = useState<PhysicalAccount | null>(null);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [showExternalModal, setShowExternalModal] = useState(false);
  const [currencies, setCurrencies] = useState<string[]>([]);
  
  // NEW: Link to Hierarchy Modal State
  const [showLinkModal, setShowLinkModal] = useState(false);
  const [accountToLink, setAccountToLink] = useState<PhysicalAccount | null>(null);

  useEffect(() => {
    const load = async () => {
      const corps = await corporatesApi.getAll();
      setCorporates(corps);
      if (corps.length > 0) setSelectedCorporateId(corps[0].id);
    };
    load();
  }, []);

  useEffect(() => {
    const load = async () => {
      if (selectedCorporateId) {
        const entities = await legalEntityApi.getByCorporate(selectedCorporateId);
        setLegalEntities(entities);
      } else setLegalEntities([]);
      setSelectedEntityId('');
    };
    load();
  }, [selectedCorporateId]);

  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const [s, b, c] = await Promise.all([
        physicalAccountsApi.getStats(selectedCorporateId || undefined, selectedEntityId || undefined),
        physicalAccountsApi.getBankSummaries(selectedCorporateId || undefined),
        physicalAccountsApi.getCurrencies(selectedCorporateId || undefined, selectedEntityId || undefined),
      ]);
      setStats(s); setBankSummaries(b); setCurrencies(c);
    } catch (e) { console.error(e); }
    setLoading(false);
  }, [selectedCorporateId, selectedEntityId]);

  const loadAccounts = useCallback(async () => {
    try {
      const params: any = { page, size: 20 };
      if (selectedCorporateId) params.corporateId = selectedCorporateId;
      if (selectedEntityId) params.legalEntityId = selectedEntityId;
      if (bankFilter !== 'ALL') params.bankCode = bankFilter;
      if (currencyFilter !== 'ALL') params.currency = currencyFilter;
      if (statusFilter !== 'ALL') params.status = statusFilter;
      if (searchQuery) params.query = searchQuery;
      const r = await physicalAccountsApi.getAccounts(params);
      setAccounts(r.content); setTotalPages(r.totalPages); setTotalElements(r.totalElements);
    } catch (e) { console.error(e); }
  }, [page, selectedCorporateId, selectedEntityId, bankFilter, currencyFilter, statusFilter, searchQuery]);

  useEffect(() => { loadData(); }, [loadData]);
  useEffect(() => { loadAccounts(); }, [loadAccounts]);

  const handleSyncAll = async () => {
    setSyncing(true);
    try { 
      await physicalAccountsApi.syncAllAccounts(selectedCorporateId || undefined); 
      toast.success('All accounts synced successfully');
      loadData(); 
      loadAccounts(); 
    } catch (e) { 
      console.error(e);
      toast.error('Failed to sync accounts');
    }
    setSyncing(false);
  };

  const handleSyncAccount = async (account: PhysicalAccount) => {
    try { 
      await physicalAccountsApi.syncAccount(account.id); 
      toast.success(`${account.accountName} synced`);
      loadAccounts(); 
    } catch (e) { 
      console.error(e);
      toast.error('Failed to sync account');
    }
  };

  // NEW: Handle Link to Hierarchy
  const handleLinkToHierarchy = (account: PhysicalAccount) => {
    if (!selectedCorporateId) {
      toast.error('Please select a Corporate first');
      return;
    }
    setAccountToLink(account);
    setShowLinkModal(true);
  };

  const handleLinkSuccess = () => {
    loadData();
    loadAccounts();
    setAccountToLink(null);
  };

  const handleExport = () => {
    const csv = ['Account,Number,Bank,Entity,Currency,Balance,Status,Shadow', ...accounts.map(a => `${a.accountName},${a.accountNumber},${a.bankName},${a.entityCode||''},${a.currency},${a.currentBalance},${a.status},${a.shadowVaId ? 'Yes' : 'No'}`)].join('\n');
    const blob = new Blob([csv], { type: 'text/csv' });
    const link = document.createElement('a'); link.href = URL.createObjectURL(blob); link.download = `accounts-${new Date().toISOString().split('T')[0]}.csv`; link.click();
    toast.success('Export downloaded');
  };

  const clearFilters = () => { setSearchQuery(''); setBankFilter('ALL'); setCurrencyFilter('ALL'); setStatusFilter('ALL'); setPage(0); };
  const hasActiveFilters = searchQuery || bankFilter !== 'ALL' || currencyFilter !== 'ALL' || statusFilter !== 'ALL';

  // Count accounts not yet linked
  const unlinkedCount = accounts.filter(a => !a.shadowVaId).length;

  // Lift Quick Actions into the Aperture Layout header (Phase 7) — Sync All,
  // Link External, Add Account were inline buttons at the top of the body;
  // the canonical home is the shell header. The page body now renders only
  // its content (PageHeader + filter bar + hero + stat strip + table).
  usePageHeaderActions(
    () => (
      <div className="flex items-center gap-2">
        <Button variant="outline" size="sm" leftIcon={<RefreshCw className={cn("w-4 h-4", syncing && "animate-spin")} />} onClick={handleSyncAll} disabled={syncing}>
          <span className="hidden sm:inline">{syncing ? 'Syncing...' : 'Sync All'}</span>
        </Button>
        <Button variant="outline" size="sm" leftIcon={<Link2 className="w-4 h-4" />} onClick={() => setShowExternalModal(true)}>
          <span className="hidden sm:inline">Link External</span>
        </Button>
        <Button size="sm" leftIcon={<Plus className="w-4 h-4" />} onClick={() => setShowCreateModal(true)}>
          Add Account
        </Button>
      </div>
    ),
    [syncing, handleSyncAll],
  );

  return (
    <Page>
      {/* PageHeader — in-body identity (title + descriptive subtitle) shipped
          via the layout primitive. Replaces the previous quick-actions-only
          top row. Quick Actions are now in the shell header via
          `usePageHeaderActions` above. */}
      <PageHeader
        title="Bank Accounts"
        description="Physical bank accounts at home and external banks. Link to the hierarchy to create shadow virtual accounts for liquidity visibility."
      />

      <ScopeSelector
        mode="corporate-entity"
        corporates={corporates}
        legalEntities={legalEntities}
        selectedCorporateId={selectedCorporateId}
        selectedEntityId={selectedEntityId}
        onCorporateChange={(id) => { setSelectedCorporateId(id); setSelectedEntityId(''); setPage(0); }}
        onEntityChange={(id) => { setSelectedEntityId(id); setPage(0); }}
        loading={loading}
        // PhysicalAccounts only lists entities that can hold physical accounts.
        entityFilter={(e) => e.status === 'ACTIVE' && !!e.canHoldPhysicalAccounts}
        disableChildUntilParent
      />

      {/* Hero — Total Balance, using the shared HeroMetricCard so the pattern
          is consistent with Virtual Accounts and any future page that needs
          a dominant financial figure. */}
      {/* Hero — FX-honest. The headline number is rendered in the dominant
          currency the backend reports (largest aggregate balance among the
          accounts in scope). Other currencies, if any, appear as per-currency
          chips below with their own native totals — never silently
          cross-currency-summed under a system-base label. */}
      {(() => {
        const balancesByCurrency: Record<string, number> = stats?.balancesByCurrency ?? {};
        const dominantCurrency =
          stats?.dominantCurrency
          || Object.entries(balancesByCurrency).sort((a, b) => b[1] - a[1])[0]?.[0]
          || (currencies.length > 0 ? currencies[0] : 'USD');
        const dominantTotal = balancesByCurrency[dominantCurrency] ?? stats?.totalBalance ?? 0;
        // Non-dominant currencies render as native-currency chips so the
        // viewer can see "EUR 9.86M dominant, USD 2.51M alongside" without
        // any silent FX conversion.
        const otherCurrencies = Object.entries(balancesByCurrency)
          .filter(([ccy]) => ccy !== dominantCurrency)
          .sort((a, b) => b[1] - a[1]);
        return (
          <HeroMetricCard
            primary={{
              label: 'Total Balance',
              value: formatCurrency(dominantTotal, dominantCurrency),
              sub: (
                <>
                  Across{' '}
                  <span className="font-semibold text-primary-700 dark:text-neutral-200">
                    {currencies.length}
                  </span>{' '}
                  {currencies.length === 1 ? 'currency' : 'currencies'}
                  {otherCurrencies.length > 0 && (
                    <span className="ml-2 inline-flex flex-wrap gap-1.5 align-middle">
                      {otherCurrencies.slice(0, 5).map(([ccy, amt]) => (
                        <span
                          key={ccy}
                          className="font-mono text-[10px] px-1.5 py-0.5 rounded bg-neutral-100 text-neutral-600 dark:bg-primary-800/60 dark:text-neutral-300 tracking-wide"
                          title={`${ccy} ${amt.toLocaleString()}`}
                        >
                          {ccy} {formatCurrency(amt, ccy)}
                        </span>
                      ))}
                    </span>
                  )}
                </>
              ),
            }}
            icon={<Banknote className="w-7 h-7 text-accent-600 dark:text-accent-300" />}
          />
        );
      })()}

      {/* Operational metrics — secondary strip below the hero. */}
      <StatStrip>
        <StatsCard title="Total Accounts" value={stats?.total || 0} subtitle={`${stats?.active || 0} active`} icon={<CreditCard className="w-6 h-6 text-primary-700 dark:text-neutral-200" />} loading={loading} delay={0.1} />
        {/* Home / External now derived authoritatively from the configured
            `vam.home-bank.bic` — see PhysicalAccountController.getStats.
            Subtitles align with the multi-bank cockpit vocabulary: home-bank
            accounts are pool-eligible; external-bank accounts feed the pool
            via sweeps. The home-bank BIC is surfaced in the title's `title`
            attribute (browser tooltip) so the reader can see which bank
            actually defines "home" in this deployment. */}
        <StatsCard
          title="Home Bank"
          value={stats?.homeBankCount || 0}
          subtitle={stats?.homeBankName ? `${stats.homeBankName} · Pool eligible` : 'Pool eligible'}
          icon={<Building2 className="w-6 h-6 text-primary-700 dark:text-neutral-200" />}
          loading={loading}
          delay={0.15}
        />
        <StatsCard
          title="External Banks"
          value={stats?.externalBankCount || 0}
          subtitle="Sweep sources"
          icon={<Globe className="w-6 h-6 text-info-600 dark:text-info-300" />}
          iconBg="bg-info-100 dark:bg-info-500/20"
          loading={loading}
          delay={0.2}
        />
        <StatsCard title="Pooling Enabled" value={stats?.poolingEnabled || 0} subtitle="In pools" icon={<Layers className="w-6 h-6 text-accent-600 dark:text-accent-300" />} iconBg="bg-accent-100 dark:bg-accent-500/20" loading={loading} delay={0.25} />
        <StatsCard title="Sweep Enabled" value={stats?.sweepEnabled || 0} subtitle="Concentration" icon={<Zap className="w-6 h-6 text-warning-600 dark:text-warning-300" />} iconBg="bg-warning-100 dark:bg-warning-500/20" loading={loading} delay={0.3} />
      </StatStrip>

      {/* Unlinked Accounts Alert — uses the canonical warning palette (was
          `bg-amber-*`; amber is still allowed by lint but the semantic
          warning tone is the system vocabulary). The medallion now uses the
          shared `<StatusIconBadge>` primitive instead of a hand-rolled box. */}
      {unlinkedCount > 0 && (
        <div className="p-4 bg-warning-50 border border-warning-200 rounded-lg dark:bg-warning-500/10 dark:border-warning-500/30">
          <div className="flex items-center gap-3">
            <StatusIconBadge tone="warning" icon={GitBranch} />
            <div className="flex-1">
              <p className="body-sm text-warning-800 dark:text-warning-300">
                {unlinkedCount} account{unlinkedCount > 1 ? 's' : ''} not linked to hierarchy
              </p>
              <p className="body-sm text-warning-700 mt-0.5 dark:text-warning-300">
                Link physical accounts to create Shadow Accounts for liquidity visibility
              </p>
            </div>
          </div>
        </div>
      )}

      {bankSummaries.length > 0 && (
        <div className="space-y-3">
          <h2 className="section-title">Banks Overview</h2>
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {bankSummaries.slice(0, 6).map(bank => <BankCard key={bank.bankCode} bank={bank} isSelected={bankFilter === bank.bankCode} onClick={() => { setBankFilter(bank.bankCode === bankFilter ? 'ALL' : bank.bankCode); setPage(0); }} />)}
          </div>
        </div>
      )}

      <Card>
        <div className="flex flex-col sm:flex-row gap-4">
          <div className="flex-1 relative">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400 dark:text-neutral-500" />
            <input type="text" placeholder="Search accounts..." value={searchQuery} onChange={(e) => { setSearchQuery(e.target.value); setPage(0); }} className="w-full pl-10 pr-4 py-2 border border-neutral-300 rounded-lg dark:border-primary-700" />
          </div>
          <div className="flex gap-2 flex-wrap">
            <select value={currencyFilter} onChange={(e) => { setCurrencyFilter(e.target.value); setPage(0); }} className="px-3 py-2 border rounded-lg text-sm">
              <option value="ALL">All Currencies</option>
              {currencies.map(c => <option key={c} value={c}>{c}</option>)}
            </select>
            <select value={statusFilter} onChange={(e) => { setStatusFilter(e.target.value); setPage(0); }} className="px-3 py-2 border rounded-lg text-sm">
              <option value="ALL">All Status</option><option value="ACTIVE">Active</option><option value="DORMANT">Dormant</option><option value="CLOSED">Closed</option>
            </select>
            {hasActiveFilters && <Button variant="ghost" size="sm" onClick={clearFilters}><X className="w-4 h-4 mr-1" />Clear</Button>}
            <Button variant="outline" size="sm" leftIcon={<Download className="w-4 h-4" />} onClick={handleExport}>Export</Button>
          </div>
        </div>
      </Card>

      <Card className="animate-fade-in" style={{ animationDelay: '0.35s' }}>
        {/* Desktop Table */}
        <div className="hidden md:block overflow-x-auto">
          <table className="data-table">
            <thead className="data-table-header">
              <tr>
                <th className="data-table-header-cell">Account</th>
                <th className="data-table-header-cell">Entity</th>
                <th className="data-table-header-cell">Bank</th>
                <th className="data-table-header-cell text-right">Balance</th>
                <th className="data-table-header-cell text-center">Shadow</th>
                <th className="data-table-header-cell text-center">Entity Link</th>
                <th className="data-table-header-cell text-center">Sync</th>
                <th className="data-table-header-cell text-center w-24">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-neutral-100 dark:divide-primary-800/60">
              {loading ? (
                <tr><td colSpan={8} className="px-4 py-12 text-center"><Loader2 className="w-8 h-8 animate-spin text-primary-600 mx-auto dark:text-primary-200" /><p className="mt-2 text-sm text-neutral-500 dark:text-neutral-400">Loading...</p></td></tr>
              ) : accounts.length === 0 ? (
                <tr><td colSpan={8} className="px-4 py-12 text-center"><Building2 className="w-12 h-12 text-neutral-300 mx-auto dark:text-neutral-600" /><p className="mt-2 text-sm text-neutral-500 dark:text-neutral-400">No accounts found</p></td></tr>
              ) : accounts.map(account => (
                <AccountRow
                  key={account.id}
                  account={account}
                  onView={setSelectedAccount}
                  onSync={handleSyncAccount}
                  onLinkToHierarchy={handleLinkToHierarchy}
                />
              ))}
            </tbody>
          </table>
        </div>

        {/* Mobile Card View */}
        <div className="md:hidden p-4 space-y-3">
          {loading ? (
            <div className="flex items-center justify-center py-12">
              <Loader2 className="w-8 h-8 animate-spin text-primary-600 dark:text-primary-200" />
            </div>
          ) : accounts.length === 0 ? (
            <EmptyState
              icon={<Building2 className="w-12 h-12" />}
              title="No accounts found"
              description="Try adjusting your filters or add a new account."
            />
          ) : accounts.map((account, idx) => (
            <Card
              key={account.id}
              interactive
              hover
              onClick={() => setSelectedAccount(account)}
              className="animate-fade-in"
              style={{ animationDelay: `${idx * 0.03}s` }}
            >
              <div className="flex items-start gap-3">
                <div className={cn(
                  "w-12 h-12 rounded-xl flex items-center justify-center shrink-0",
                  account.isHomeBank ? "bg-primary-100 dark:bg-primary-700" : "bg-info-50 dark:bg-info-500/10"
                )}>
                  {account.isHomeBank ? (
                    <Building2 className="w-6 h-6 text-primary-700 dark:text-neutral-200" />
                  ) : (
                    <Globe className="w-6 h-6 text-info-600 dark:text-info-300" />
                  )}
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0">
                      <h3 className="font-semibold text-primary-900 truncate dark:text-neutral-50">{account.accountName}</h3>
                      <p className="text-xs text-neutral-500 font-mono mt-0.5 truncate dark:text-neutral-400">{account.iban || account.accountNumber}</p>
                    </div>
                    <Badge variant={account.syncStatus === 'SYNCED' ? 'success' : 'warning'} size="sm">
                      {account.syncStatus}
                    </Badge>
                  </div>
                  <div className="mt-3 pt-3 border-t border-neutral-100 flex items-center justify-between dark:border-primary-800/60">
                    <div>
                      <p className="label">Balance</p>
                      <p className="stat-value-xs">
                        {formatCurrency(account.currentBalance, account.currency)}
                      </p>
                    </div>
                    <div className="text-right">
                      <p className="text-xs text-neutral-500 dark:text-neutral-400">Bank</p>
                      <p className="field-label">{account.bankName}</p>
                    </div>
                  </div>
                  {account.shadowVaId && (
                    <div className="mt-2 flex items-center gap-1 text-xs text-success-600 dark:text-success-300">
                      <Layers className="w-3 h-3" />
                      <span>Linked to hierarchy</span>
                    </div>
                  )}
                </div>
                <ChevronRight className="w-5 h-5 text-neutral-400 shrink-0 mt-4 dark:text-neutral-500" />
              </div>
            </Card>
          ))}
        </div>
        {totalPages > 1 && (
          <div className="flex items-center justify-between px-4 py-3 border-t">
            <p className="text-sm text-neutral-500 dark:text-neutral-400">Showing {page * 20 + 1} to {Math.min((page + 1) * 20, totalElements)} of {totalElements}</p>
            <div className="flex gap-2">
              <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage(p => p - 1)}>Previous</Button>
              <Button variant="outline" size="sm" disabled={page >= totalPages - 1} onClick={() => setPage(p => p + 1)}>Next</Button>
            </div>
          </div>
        )}
      </Card>

      {/* Account Details Modal */}
      <Modal isOpen={!!selectedAccount} onClose={() => setSelectedAccount(null)} title="Account Details" size="lg">
        {selectedAccount && (
          <div className="space-y-6">
            <div className="flex items-center gap-4">
              <div className={cn("w-16 h-16 rounded-xl flex items-center justify-center", selectedAccount.isHomeBank ? "bg-primary-100 dark:bg-primary-700" : "bg-info-100 dark:bg-info-500/20")}>
                {selectedAccount.isHomeBank ? <Building2 className="w-8 h-8 text-primary-700 dark:text-neutral-200" /> : <Globe className="w-8 h-8 text-info-600 dark:text-info-300" />}
              </div>
              <div className="flex-1">
                <h2 className="section-title">{selectedAccount.accountName}</h2>
                <p className="body-sm text-neutral-500 dark:text-neutral-400">{selectedAccount.bankName} • {selectedAccount.entityName || '-'}</p>
                <div className="flex gap-2 mt-2">
                  <Badge variant={selectedAccount.status === 'ACTIVE' ? 'success' : 'neutral'}>{selectedAccount.status}</Badge>
                  <Badge variant={selectedAccount.isHomeBank ? 'primary' : 'info'}>{selectedAccount.isHomeBank ? 'Home Bank' : 'External'}</Badge>
                  {selectedAccount.shadowVaId && <Badge variant="accent">Shadow Linked</Badge>}
                </div>
              </div>
            </div>
            <div className="grid grid-cols-3 gap-4">
              {/* Per-tone stat-value utilities pick up Fraunces + tabular-nums
                  + the semantic tone in one class. Replaces the `text-lg
                  font-semibold` + per-tone text-* recipe. */}
              <Card padding="sm">
                <p className="label">Current Balance</p>
                <p className="stat-value-xs">{formatCurrency(selectedAccount.currentBalance, selectedAccount.currency)}</p>
              </Card>
              <Card padding="sm">
                <p className="label">Available</p>
                <p className="stat-value-success">{formatCurrency(selectedAccount.availableBalance, selectedAccount.currency)}</p>
              </Card>
              <Card padding="sm">
                <p className="label">Ledger</p>
                <p className="stat-value-xs text-neutral-600 dark:text-neutral-300">{formatCurrency(selectedAccount.ledgerBalance || 0, selectedAccount.currency)}</p>
              </Card>
            </div>
            
            {/* Shadow Account Info */}
            {selectedAccount.shadowVaId ? (
              <div className="p-3 bg-success-50 border border-success-200 rounded-lg dark:bg-success-500/10 dark:border-success-500/30">
                <div className="flex items-center gap-2">
                  <Layers className="w-4 h-4 text-success-600 dark:text-success-300" />
                  <span className="text-sm text-success-700 dark:text-success-300">
                    Linked to Shadow: <strong>{selectedAccount.shadowVaNumber || selectedAccount.shadowVaId}</strong>
                  </span>
                </div>
              </div>
            ) : (
              <div className="p-3 bg-warning-50 border border-warning-200 rounded-lg dark:bg-warning-500/10 dark:border-warning-500/30">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <AlertTriangle className="w-4 h-4 text-warning-600 dark:text-warning-300" />
                    <span className="body-sm text-warning-700 dark:text-warning-300">Not linked to hierarchy</span>
                  </div>
                  <Button 
                    size="sm" 
                    variant="outline"
                    onClick={() => {
                      setSelectedAccount(null);
                      handleLinkToHierarchy(selectedAccount);
                    }}
                    leftIcon={<GitBranch className="w-4 h-4" />}
                  >
                    Link Now
                  </Button>
                </div>
              </div>
            )}
            
            <div className="flex gap-3 pt-4 border-t">
              <Button variant="outline" className="flex-1" leftIcon={<Settings className="w-4 h-4" />}>Configure</Button>
              <Button variant="outline" className="flex-1" leftIcon={<RefreshCw className="w-4 h-4" />} onClick={() => handleSyncAccount(selectedAccount)}>Sync Now</Button>
              {!selectedAccount.shadowVaId && (
                <Button 
                  className="flex-1" 
                  leftIcon={<GitBranch className="w-4 h-4" />}
                  onClick={() => {
                    setSelectedAccount(null);
                    handleLinkToHierarchy(selectedAccount);
                  }}
                >
                  Link to Hierarchy
                </Button>
              )}
            </div>
          </div>
        )}
      </Modal>

      {/* Create Account Modals */}
      <CreateAccountModal isOpen={showCreateModal} onClose={() => setShowCreateModal(false)} onSuccess={() => { loadData(); loadAccounts(); }} isExternal={false} corporateId={selectedCorporateId} entities={legalEntities} />
      <CreateAccountModal isOpen={showExternalModal} onClose={() => setShowExternalModal(false)} onSuccess={() => { loadData(); loadAccounts(); }} isExternal={true} corporateId={selectedCorporateId} entities={legalEntities} />
      
      {/* NEW: Link to Hierarchy Modal */}
      <LinkToHierarchyModal
        isOpen={showLinkModal}
        onClose={() => {
          setShowLinkModal(false);
          setAccountToLink(null);
        }}
        onSuccess={handleLinkSuccess}
        account={accountToLink}
        corporateId={selectedCorporateId}
      />
    </Page>
  );
};

export default PhysicalAccountsPage;