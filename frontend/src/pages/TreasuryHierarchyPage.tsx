import React, { useState, useEffect, useCallback } from 'react';
import toast from 'react-hot-toast';
import {
  ChevronRight, ChevronDown, Building2, Wallet, TrendingUp, TrendingDown, Globe, MapPin,
  DollarSign, Download, RefreshCw, ArrowUpRight, ArrowDownRight, Layers,
  Percent, ArrowLeftRight, Banknote, Loader2, AlertCircle, CheckCircle2, XCircle,
  Plus, Settings, CreditCard, Coins, Check,
  Scale, AlertTriangle, Eye, MoreVertical, Building, X,
  GitBranch, FolderPlus, Crown, Power, Target, PiggyBank,
} from 'lucide-react';
import { Card, Button, Badge, Input , StatusIconBadge } from '../components/ui';
import { CurrencyPicker } from '../components/ui/CurrencyPicker';
import { Modal } from '../components/ui/enhanced';
import { formatCurrency, cn } from '../utils';
import {
  balanceStructureApi,
  BalanceHierarchyNode,
  BalanceSummary,
  BalancePhysicalAccount,
  BalanceNodeDetail,
  currencyMirrorApi,
  CurrencyMirror,
  CurrencyBreakdown,
  LevelInfo,
  hierarchyVaApi,
  HierarchyStatusResponse,
  InitializationResponse,
  programsApi,
  interestConfigurationApi,
  InterestConfiguration,
  corporatesApi,
  legalEntityApi,
  ihbUnifiedApi,
  virtualAccountsApi,
  shadowAccountApi,
  ShadowAccount,
  TreasuryRates,
  CreateAggregationRequest,
  CreateTransactionVaRequest,
} from '../services/api';
import { usePageHeaderActions } from '../context/PageHeaderContext';
import { HierarchyInitializationModal } from './HierarchyInitializationModal';
import { HierarchyLevelConfigModal } from '../components/HierarchyLevelConfigModal';
import type { HierarchyLevelConfig } from '../components/HierarchyLevelConfigModal';
import { Page } from '../components/layout/Page';
import { PageHeader } from '../components/layout/PageHeader';
import { ScopeSelector } from '../components/layout/ScopeSelector';

// ============================================================================
// TYPE DEFINITIONS
// ============================================================================

type VaSpecialType = 'REGULAR' | 'SETTLEMENT' | 'EXCEPTION' | 'CURRENCY_MIRROR';
type AccountCategory = 'ROOT' | 'AGGREGATION' | 'CURRENCY_MIRROR' | 'PHYSICAL_MIRROR' | 'SETTLEMENT' | 'EXCEPTION' | 'TRANSACTION' | 'COLLECTION' | 'DISBURSEMENT' | 'INTERCOMPANY';
type NodeCreationType = 'aggregation' | 'transaction' | 'ihb-current-account' | null;

// ENHANCED: Legal Entity Summary embedded in hierarchy node
interface LegalEntitySummary {
  id: string;
  entityCode: string;
  entityName: string;
  entityType: 'PARENT' | 'SUBSIDIARY' | 'BRANCH' | 'DIVISION' | 'JOINT_VENTURE';
  countryCode?: string;
  functionalCurrency?: string;
}

// ENHANCED: IHB Summary embedded in hierarchy node
interface IhbSummary {
  enabled: boolean;
  isTreasuryCenter: boolean;
  canLend: boolean;
  canBorrow: boolean;
  creditLimit: number;
  currentExposure: number;
  availableLimit: number;
  utilizationPercent: number;
  targetCashBalance: number;
  lendingRateSpread?: number;
  borrowingRateSpread?: number;
  sweepEnabled: boolean;
  sweepFrequency?: 'DAILY' | 'REAL_TIME';
  sweepRuleId?: string;
}

interface ExtendedHierarchyNode extends BalanceHierarchyNode {
  specialType?: VaSpecialType;
  accountCategory?: AccountCategory;
  coveredVaCount?: number;
  pendingExceptions?: number;
  todayCredits?: number;
  primaryViban?: string;
  vibanCount?: number;
  mirrorBalance?: number;
  fxRate?: number;
  fxRateAt?: string;
  fxRateSource?: string;
  balanceInBase?: number;
  baseCurrency?: string;
  // ENHANCED: Flat fields from backend API
  owningEntityId?: string;
  owningEntityCode?: string;
  owningEntityName?: string;
  owningEntityType?: string;
  // ENHANCED: Computed nested object for display (populated by transformHierarchy)
  owningEntity?: LegalEntitySummary;
  ihb?: IhbSummary;
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
  status: string;
  // ENHANCED: IHB fields
  ihbEnabled?: boolean;
  ihbCreditLimit?: number;
  canLend?: boolean;
  canBorrow?: boolean;
  ihbInterestConfigId?: string;
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
// VA SPECIAL TYPE CONFIGURATIONS
// ============================================================================

const SPECIAL_VA_CONFIG: Record<VaSpecialType, { 
  label: string; 
  icon: React.FC<{ className?: string }>; 
  color: string; 
  bgColor: string;
  borderColor: string;
  description: string;
}> = {
  REGULAR: {
    label: 'Regular',
    icon: Wallet,
    color: 'text-success-600 dark:text-success-300',
    bgColor: 'bg-success-50 dark:bg-success-500/10',
    borderColor: 'border-success-200 dark:border-success-500/30',
    description: 'Standard virtual account',
  },
  SETTLEMENT: {
    label: 'Settlement',
    icon: Scale,
    color: 'text-purple-600 dark:text-purple-300',
    bgColor: 'bg-purple-50 dark:bg-purple-500/10',
    borderColor: 'border-purple-300 dark:border-purple-500/30',
    description: 'Receives fee postings from covered VAs',
  },
  EXCEPTION: {
    label: 'Exception',
    icon: AlertTriangle,
    color: 'text-amber-600 dark:text-amber-300',
    bgColor: 'bg-amber-50 dark:bg-amber-500/10',
    borderColor: 'border-amber-300 dark:border-amber-500/30',
    description: 'Holds unmatched/exception transactions',
  },
  CURRENCY_MIRROR: {
    label: 'Currency Mirror',
    icon: Coins,
    color: 'text-cyan-600 dark:text-cyan-300',
    bgColor: 'bg-cyan-50 dark:bg-cyan-500/10',
    borderColor: 'border-cyan-400 dark:border-cyan-500/30',
    description: 'Aggregates same-currency balances (no FX conversion)',
  },
};

// ============================================================================
// ADD NODE TYPE SELECTOR COMPONENT (NEW)
// ============================================================================

interface AddNodeTypeSelectorProps {
  onSelect: (type: NodeCreationType) => void;
  onClose: () => void;
}

const AddNodeTypeSelector: React.FC<AddNodeTypeSelectorProps> = ({ onSelect, onClose }) => {
  return (
    <div className="p-4 space-y-4">
      <p className="text-sm text-neutral-600 dark:text-neutral-300">
        Select the type of node to add to the hierarchy:
      </p>

      <div className="grid grid-cols-1 gap-3">
        {/* AGGREGATION Option */}
        <button
          onClick={() => onSelect('aggregation')}
          className="flex items-start gap-4 p-4 rounded-xl border-2 border-indigo-200 dark:border-indigo-500/30 bg-indigo-50 dark:bg-indigo-500/10 hover:border-indigo-400 dark:hover:border-indigo-500/40 hover:bg-indigo-100 dark:hover:bg-indigo-500/20 transition-all text-left group"
        >
          <div className="w-12 h-12 rounded-xl bg-indigo-100 dark:bg-indigo-500/20 group-hover:bg-indigo-200 dark:group-hover:bg-indigo-500/30 flex items-center justify-center flex-shrink-0">
            <FolderPlus className="w-6 h-6 text-indigo-600 dark:text-indigo-300" />
          </div>
          <div className="flex-1">
            <div className="flex items-center gap-2">
              <h3 className="text-base font-semibold text-indigo-900 dark:text-indigo-100">Add AGGREGATION</h3>
              <Badge variant="info" size="sm">Intermediate</Badge>
            </div>
            <p className="text-sm text-indigo-700 dark:text-indigo-300 mt-1">
              Creates a grouping node that can contain other AGGREGATIONs or Transaction VAs.
              Used for organizing hierarchy by region, entity, department, etc.
            </p>
            <div className="flex items-center gap-4 mt-2 text-xs text-indigo-600 dark:text-indigo-300">
              <span className="flex items-center gap-1">
                <Layers className="w-3 h-3" /> Can have children
              </span>
              <span className="flex items-center gap-1">
                <GitBranch className="w-3 h-3" /> Aggregates balances
              </span>
            </div>
          </div>
          <ChevronRight className="w-5 h-5 text-indigo-400 dark:text-indigo-300 group-hover:text-indigo-600 dark:group-hover:text-indigo-200 mt-1" />
        </button>

        {/* TRANSACTION VA Option */}
        <button
          onClick={() => onSelect('transaction')}
          className="flex items-start gap-4 p-4 rounded-xl border-2 border-success-200 dark:border-success-500/30 bg-success-50 dark:bg-success-500/10 hover:border-success-400 dark:hover:border-success-500/40 hover:bg-success-100 dark:hover:bg-success-500/20 transition-all text-left group"
        >
          <div className="w-12 h-12 rounded-xl bg-success-100 dark:bg-success-500/20 group-hover:bg-success-200 dark:group-hover:bg-success-500/30 flex items-center justify-center flex-shrink-0">
            <Wallet className="w-6 h-6 text-success-600 dark:text-success-300" />
          </div>
          <div className="flex-1">
            <div className="flex items-center gap-2">
              <h3 className="text-base font-semibold text-success-900 dark:text-success-100">Add Transaction VA</h3>
              <Badge variant="success" size="sm">Leaf</Badge>
            </div>
            <p className="text-sm text-success-700 dark:text-success-300 mt-1">
              Creates a virtual account for actual transactions. Receives payments,
              processes collections, and holds balances.
            </p>
            <div className="flex items-center gap-4 mt-2 text-xs text-success-600 dark:text-success-300">
              <span className="flex items-center gap-1">
                <CreditCard className="w-3 h-3" /> Can have VIBANs
              </span>
              <span className="flex items-center gap-1">
                <Banknote className="w-3 h-3" /> Holds balance
              </span>
            </div>
          </div>
          <ChevronRight className="w-5 h-5 text-success-400 dark:text-success-300 group-hover:text-success-600 dark:group-hover:text-success-200 mt-1" />
        </button>

        {/* IHB CURRENT ACCOUNT Option */}
        <button
          onClick={() => onSelect('ihb-current-account')}
          className="flex items-start gap-4 p-4 rounded-xl border-2 border-purple-200 dark:border-purple-500/30 bg-purple-50 dark:bg-purple-500/10 hover:border-purple-400 dark:hover:border-purple-500/40 hover:bg-purple-100 dark:hover:bg-purple-500/20 transition-all text-left group"
        >
          <div className="w-12 h-12 rounded-xl bg-purple-100 dark:bg-purple-500/20 group-hover:bg-purple-200 dark:group-hover:bg-purple-500/30 flex items-center justify-center flex-shrink-0">
            <PiggyBank className="w-6 h-6 text-purple-600 dark:text-purple-300" />
          </div>
          <div className="flex-1">
            <div className="flex items-center gap-2">
              <h3 className="text-base font-semibold text-purple-900 dark:text-purple-100">Add IHB Current Account</h3>
              <Badge variant="default" size="sm" className="bg-purple-100 dark:bg-purple-500/20 text-purple-700 dark:text-purple-300">IHB</Badge>
            </div>
            <p className="text-sm text-purple-700 dark:text-purple-300 mt-1">
              Creates a Transaction VA with IHB participation enabled. Supports credit/debit
              interest and participates in corporate treasury sweeps.
            </p>
            <div className="flex items-center gap-4 mt-2 text-xs text-purple-600 dark:text-purple-300">
              <span className="flex items-center gap-1">
                <Percent className="w-3 h-3" /> Earns/pays interest
              </span>
              <span className="flex items-center gap-1">
                <ArrowLeftRight className="w-3 h-3" /> Auto-sweep enabled
              </span>
            </div>
          </div>
          <ChevronRight className="w-5 h-5 text-purple-400 dark:text-purple-300 group-hover:text-purple-600 dark:group-hover:text-purple-200 mt-1" />
        </button>
      </div>

      <div className="flex justify-end pt-4 border-t">
        <Button variant="outline" onClick={onClose}>Cancel</Button>
      </div>
    </div>
  );
};

// ============================================================================
// CREATE AGGREGATION MODAL (NEW)
// ============================================================================

interface CreateAggregationModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: () => void;
  programId: string;
  parentNode: ExtendedHierarchyNode | null;
  entities: LegalEntity[];
}

const CreateAggregationModal: React.FC<CreateAggregationModalProps> = ({
  isOpen, onClose, onSuccess, programId, parentNode, entities,
}) => {
  const [formData, setFormData] = useState({ 
    name: '', 
    code: '', 
    baseCurrency: 'AED', 
    owningEntityId: '', 
    description: '',
    // ENHANCED: IHB configuration
    enableIhb: false,
    ihbConfig: {
      canLend: false,
      canBorrow: true,
      creditLimit: 1000000,
      targetCashBalance: 0,
      enableSweep: true,
      sweepFrequency: 'DAILY' as 'DAILY' | 'REAL_TIME',
    }
  });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Find Treasury Center (entity with canLend = true)
  const treasuryCenter = entities.find(e => e.canLend === true && e.ihbEnabled === true);

  useEffect(() => {
    if (isOpen) {
      setFormData({ 
        name: '', 
        code: '', 
        baseCurrency: parentNode?.currencyCode || 'AED', 
        owningEntityId: '', 
        description: '',
        enableIhb: false,
        ihbConfig: {
          canLend: false,
          canBorrow: true,
          creditLimit: 1000000,
          targetCashBalance: 0,
          enableSweep: true,
          sweepFrequency: 'DAILY',
        }
      });
      setError(null);
    }
  }, [isOpen, parentNode]);

  useEffect(() => {
    if (formData.name && !formData.code) {
      const autoCode = formData.name.toUpperCase().replace(/[^A-Z0-9\s]/g, '').split(' ').map(w => w.substring(0, 3)).join('-').substring(0, 15);
      setFormData(prev => ({ ...prev, code: `AGG-${autoCode}` }));
    }
  }, [formData.name]);

  const handleSubmit = async () => {
    if (!formData.name.trim()) { setError('Name is required'); return; }
    if (!formData.code.trim()) { setError('Code is required'); return; }
    setLoading(true); setError(null);
    try {
      const request: CreateAggregationRequest = {
        name: formData.name.trim(),
        code: formData.code.trim(),
        currencyCode: formData.baseCurrency,
        parentNodeId: parentNode?.id || '',
        owningEntityId: formData.owningEntityId || undefined,
      };
      const response = await hierarchyVaApi.createAggregation(programId, request);

      // ENHANCED: If IHB enabled and entity selected, enable IHB on the entity
      if (formData.enableIhb && formData.owningEntityId) {
        try {
          await ihbUnifiedApi.enableIhb(formData.owningEntityId, {
            creditLimit: formData.ihbConfig.creditLimit,
            ihbCurrency: formData.baseCurrency,
            canLend: formData.ihbConfig.canLend,
            canBorrow: formData.ihbConfig.canBorrow,
            targetCashBalance: formData.ihbConfig.targetCashBalance,
            autoSweepEnabled: formData.ihbConfig.enableSweep,
            sweepFrequency: formData.ihbConfig.sweepFrequency,
          });
          toast.success('IHB enabled with auto-sweep rule', { icon: '🏦' });
        } catch (ihbErr) {
          console.error('Failed to enable IHB:', ihbErr);
          toast.error('Aggregation created but IHB setup failed');
        }
      }

      toast.success(`✓ Created AGGREGATION: ${response.data?.nodeName || formData.name}`, { duration: 4000, icon: '📁' });
      onSuccess(); onClose();
    } catch (err: any) {
      const errorMessage = err.response?.data?.message || err.message || 'Failed to create AGGREGATION';
      setError(errorMessage); toast.error(errorMessage);
    } finally { setLoading(false); }
  };

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="Create AGGREGATION Node" size="lg">
      <div className="p-4 space-y-4">
        {error && (
          <div className="p-3 bg-error-50 dark:bg-error-500/10 border border-error-200 dark:border-error-500/30 rounded-lg flex items-start gap-2 text-error-700 dark:text-error-300">
            <AlertCircle className="w-5 h-5 mt-0.5 flex-shrink-0" />
            <span className="text-sm">{error}</span>
          </div>
        )}
        
        <div className="flex items-start gap-3 p-3 bg-indigo-50 dark:bg-indigo-500/10 rounded-lg border border-indigo-200 dark:border-indigo-500/30">
          <Layers className="w-5 h-5 text-indigo-600 dark:text-indigo-300 mt-0.5" />
          <div>
            <p className="text-sm font-medium text-indigo-800 dark:text-indigo-300">AGGREGATION Node</p>
            <p className="text-xs text-indigo-600 dark:text-indigo-300 mt-0.5">Creates an intermediate grouping node for organizing the hierarchy.</p>
          </div>
        </div>
        
        {parentNode && (
          <div className="p-3 bg-neutral-50 dark:bg-primary-950 border border-neutral-200 dark:border-primary-800 rounded-lg">
            <div className="flex items-center gap-2 text-sm">
              <GitBranch className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
              <span className="text-neutral-600 dark:text-neutral-300">Parent:</span>
              <span className="font-medium text-primary-900 dark:text-neutral-50">{parentNode.name}</span>
              <Badge variant="neutral" size="sm">{parentNode.currencyCode}</Badge>
            </div>
          </div>
        )}
        
        <div>
          <label className="field-label block mb-1">Name <span className="text-error-500">*</span></label>
          <Input 
            placeholder="e.g., EMEA Region, UAE Operations" 
            value={formData.name} 
            onChange={(e) => setFormData(prev => ({ ...prev, name: e.target.value }))} 
          />
        </div>
        
        <div>
          <label className="field-label block mb-1">Code <span className="text-error-500">*</span></label>
          <Input 
            placeholder="e.g., AGG-EMEA" 
            value={formData.code} 
            className="font-mono" 
            onChange={(e) => setFormData(prev => ({ ...prev, code: e.target.value.toUpperCase() }))} 
          />
          <p className="text-xs text-neutral-500 dark:text-neutral-400 mt-1">Auto-generated from name. Must be unique.</p>
        </div>
        
        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className="field-label block mb-1">Base Currency</label>
            <CurrencyPicker
              value={formData.baseCurrency}
              onChange={(c) => setFormData(prev => ({ ...prev, baseCurrency: c }))}
              extra={['SAR']}
            />
          </div>
          <div>
            <label className="field-label block mb-1">Owning Entity</label>
            <select 
              value={formData.owningEntityId} 
              onChange={(e) => setFormData(prev => ({ ...prev, owningEntityId: e.target.value }))} 
              className="w-full px-3 py-2 border border-neutral-300 dark:border-primary-700 rounded-lg"
            >
              <option value="">No specific entity</option>
              {entities.filter(e => e.status === 'ACTIVE').map(entity => (
                <option key={entity.id} value={entity.id}>
                  {entity.entityCode} - {entity.entityName}
                  {entity.ihbEnabled && ' 🏦'}
                </option>
              ))}
            </select>
          </div>
        </div>

        {/* ENHANCED: IHB Configuration Section */}
        {formData.owningEntityId && (
          <div className="pt-4 border-t space-y-4">
            <div className="flex items-center justify-between">
              <div>
                <h4 className="text-sm font-semibold text-neutral-700 flex items-center gap-2 dark:text-neutral-200">
                  <PiggyBank className="w-4 h-4 text-info-600 dark:text-info-300" />
                  In-House Banking
                </h4>
                <p className="text-xs text-neutral-500 dark:text-neutral-400">Configure intercompany lending for this entity</p>
              </div>
              <label className="relative inline-flex items-center cursor-pointer">
                <input
                  type="checkbox"
                  checked={formData.enableIhb}
                  onChange={(e) => setFormData(prev => ({ ...prev, enableIhb: e.target.checked }))}
                  className="sr-only peer"
                />
                <div className="w-11 h-6 bg-neutral-200 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:bg-info-600 after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-all dark:bg-primary-800"></div>
              </label>
            </div>
            
            {formData.enableIhb && (
              <div className="p-4 bg-info-50 dark:bg-info-500/10 border border-info-200 dark:border-info-500/30 rounded-lg space-y-4">
                {/* Role Selection */}
                <div className="flex items-center gap-6">
                  <label className="flex items-center gap-2 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={formData.ihbConfig.canLend}
                      onChange={(e) => setFormData(prev => ({
                        ...prev,
                        ihbConfig: { ...prev.ihbConfig, canLend: e.target.checked }
                      }))}
                      className="rounded border-neutral-300 text-info-600 dark:border-primary-700 dark:text-info-300"
                    />
                    <span className="text-sm">Can Lend</span>
                    {formData.ihbConfig.canLend && (
                      <Badge variant="warning" size="sm" className="flex items-center gap-1">
                        <Crown className="w-3 h-3" />
                        Treasury Center
                      </Badge>
                    )}
                  </label>
                  <label className="flex items-center gap-2 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={formData.ihbConfig.canBorrow}
                      onChange={(e) => setFormData(prev => ({
                        ...prev,
                        ihbConfig: { ...prev.ihbConfig, canBorrow: e.target.checked }
                      }))}
                      className="rounded border-neutral-300 text-info-600 dark:border-primary-700 dark:text-info-300"
                    />
                    <span className="text-sm">Can Borrow</span>
                  </label>
                </div>
                
                {/* Credit Limit & Target Balance */}
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <label className="field-label block mb-1">
                      Credit Limit ({formData.baseCurrency})
                    </label>
                    <Input
                      type="number"
                      value={formData.ihbConfig.creditLimit}
                      onChange={(e) => setFormData(prev => ({
                        ...prev,
                        ihbConfig: { ...prev.ihbConfig, creditLimit: Number(e.target.value) }
                      }))}
                    />
                  </div>
                  <div>
                    <label className="field-label block mb-1">
                      Target Cash Balance
                    </label>
                    <Input
                      type="number"
                      value={formData.ihbConfig.targetCashBalance}
                      onChange={(e) => setFormData(prev => ({
                        ...prev,
                        ihbConfig: { ...prev.ihbConfig, targetCashBalance: Number(e.target.value) }
                      }))}
                    />
                    <p className="text-xs text-neutral-500 dark:text-neutral-400 mt-1">0 = sweep all surplus</p>
                  </div>
                </div>
                
                {/* EOD Sweep Configuration */}
                <div className="flex items-center justify-between p-3 bg-white dark:bg-primary-900 rounded-lg border border-info-100 dark:border-info-500/30">
                  <div className="flex items-center gap-3">
                    <RefreshCw className="w-5 h-5 text-info-600 dark:text-info-300" />
                    <div>
                      <p className="text-sm font-medium text-neutral-800 dark:text-neutral-100">EOD Auto-Sweep</p>
                      <p className="text-xs text-neutral-500 dark:text-neutral-400">Automatically balance positions</p>
                    </div>
                  </div>
                  <div className="flex items-center gap-3">
                    <select 
                      value={formData.ihbConfig.sweepFrequency}
                      onChange={(e) => setFormData(prev => ({
                        ...prev,
                        ihbConfig: { ...prev.ihbConfig, sweepFrequency: e.target.value as 'DAILY' | 'REAL_TIME' }
                      }))}
                      className="px-2 py-1 border border-neutral-300 rounded text-sm dark:border-primary-700"
                      disabled={!formData.ihbConfig.enableSweep}
                    >
                      <option value="DAILY">Daily @ 6PM</option>
                      <option value="REAL_TIME">Real-time</option>
                    </select>
                    <label className="relative inline-flex items-center cursor-pointer">
                      <input
                        type="checkbox"
                        checked={formData.ihbConfig.enableSweep}
                        onChange={(e) => setFormData(prev => ({
                          ...prev,
                          ihbConfig: { ...prev.ihbConfig, enableSweep: e.target.checked }
                        }))}
                        className="sr-only peer"
                      />
                      <div className="w-9 h-5 bg-neutral-200 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:bg-info-600 after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-4 after:w-4 after:transition-all dark:bg-primary-800"></div>
                    </label>
                  </div>
                </div>
                
                {/* Treasury Center Info */}
                {treasuryCenter && !formData.ihbConfig.canLend && (
                  <div className="flex items-center gap-2 p-2 bg-amber-50 dark:bg-amber-500/10 rounded border border-amber-200 dark:border-amber-500/30">
                    <Crown className="w-4 h-4 text-amber-600 dark:text-amber-300" />
                    <span className="text-sm text-amber-700 dark:text-amber-300">
                      Treasury Center: <strong>{treasuryCenter.entityCode}</strong> - {treasuryCenter.entityName}
                    </span>
                  </div>
                )}
                
                {formData.ihbConfig.canLend && !treasuryCenter && (
                  <div className="flex items-center gap-2 p-2 bg-success-50 dark:bg-success-500/10 rounded border border-success-200 dark:border-success-500/30">
                    <Check className="w-4 h-4 text-success-600 dark:text-success-300" />
                    <span className="text-sm text-success-700 dark:text-success-300">
                      This entity will become the <strong>Treasury Center</strong>
                    </span>
                  </div>
                )}
              </div>
            )}
          </div>
        )}
        
        <div className="flex justify-end gap-3 pt-4 border-t">
          <Button variant="outline" onClick={onClose} disabled={loading}>Cancel</Button>
          <Button onClick={handleSubmit} disabled={loading || !formData.name.trim() || !formData.code.trim()}>
            {loading ? <Loader2 className="w-4 h-4 mr-1 animate-spin" /> : <FolderPlus className="w-4 h-4 mr-1" />}
            {loading ? 'Creating...' : 'Create AGGREGATION'}
          </Button>
        </div>
      </div>
    </Modal>
  );
};

// ============================================================================
// CREATE TRANSACTION VA MODAL (NEW)
// ============================================================================

interface CreateTransactionVaModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: () => void;
  parentNode: ExtendedHierarchyNode | null;
  entities: LegalEntity[];
  programId?: string;
}

const CreateTransactionVaModal: React.FC<CreateTransactionVaModalProps> = ({
  isOpen, onClose, onSuccess, parentNode, entities, programId,
}) => {
  const [formData, setFormData] = useState({ 
    name: '', 
    currency: 'AED', 
    owningEntityId: '', 
    accountPurpose: 'OPERATING' 
  });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (isOpen) { 
      setFormData({ 
        name: '', 
        currency: parentNode?.currencyCode || 'AED', 
        owningEntityId: '', 
        accountPurpose: 'OPERATING' 
      }); 
      setError(null); 
    }
  }, [isOpen, parentNode]);

  const handleSubmit = async () => {
    if (!formData.name.trim()) { 
      setError('Account name is required'); 
      return; 
    }
    if (!parentNode?.id) { 
      setError('Parent node is required'); 
      return; 
    }
    
    setLoading(true); 
    setError(null);
    
    try {
      // Build typed request for VirtualAccountDto.CreateRequest
      // Using parentNodeId which backend supports via getEffectiveParentId() method
      const request: CreateTransactionVaRequest = {
        vaName: formData.name.trim(),
        currencyCode: formData.currency,
        parentNodeId: parentNode.id,
        programId: programId || undefined,
        owningEntityId: formData.owningEntityId || undefined,
        accountPurpose: formData.accountPurpose,
        accountCategory: 'TRANSACTION',
        accountType: 'VIRTUAL',
        inheritProgramDefaults: true,
      };

      console.log('[CreateTransactionVaModal] Submitting request:', request);

      const response = await virtualAccountsApi.create(request);

      console.log('[CreateTransactionVaModal] Success response:', response);

      const vaNumber = response?.data?.vaNumber || 'VA';
      toast.success(`✓ Created Transaction VA: ${vaNumber}`, { duration: 4000, icon: '💳' });
      onSuccess(); 
      onClose();
    } catch (err: any) {
      console.error('[CreateTransactionVaModal] Error:', err);
      const errorMessage = err.response?.data?.message || err.response?.data?.error || err.message || 'Failed to create Transaction VA';
      setError(errorMessage); 
      toast.error(errorMessage);
    } finally { 
      setLoading(false); 
    }
  };

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="Create Transaction VA" size="md">
      <div className="p-4 space-y-4">
        {error && (
          <div className="p-3 bg-error-50 dark:bg-error-500/10 border border-error-200 dark:border-error-500/30 rounded-lg flex items-start gap-2 text-error-700 dark:text-error-300">
            <AlertCircle className="w-5 h-5 mt-0.5 flex-shrink-0" />
            <span className="text-sm">{error}</span>
          </div>
        )}
        
        <div className="flex items-start gap-3 p-3 bg-success-50 dark:bg-success-500/10 rounded-lg border border-success-200 dark:border-success-500/30">
          <Wallet className="w-5 h-5 text-success-600 dark:text-success-300 mt-0.5" />
          <div>
            <p className="text-sm font-medium text-success-800 dark:text-success-300">Transaction Virtual Account</p>
            <p className="text-xs text-success-600 dark:text-success-300 mt-0.5">Creates a leaf-level VA for actual transactions.</p>
          </div>
        </div>
        
        {parentNode ? (
          <div className="p-3 bg-neutral-50 dark:bg-primary-950 border border-neutral-200 dark:border-primary-800 rounded-lg">
            <div className="flex items-center gap-2 text-sm">
              <GitBranch className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
              <span className="text-neutral-600 dark:text-neutral-300">Parent:</span>
              <span className="font-medium text-primary-900 dark:text-neutral-50">{parentNode.name}</span>
              <Badge variant="neutral" size="sm">{parentNode.currencyCode}</Badge>
            </div>
            <p className="text-xs text-neutral-400 mt-1 font-mono dark:text-neutral-500">ID: {parentNode.id}</p>
          </div>
        ) : (
          <div className="p-3 bg-amber-50 dark:bg-amber-500/10 border border-amber-200 dark:border-amber-500/30 rounded-lg">
            <div className="flex items-center gap-2 text-amber-700 dark:text-amber-300">
              <AlertTriangle className="w-4 h-4" />
              <span className="text-sm">A parent AGGREGATION node is required</span>
            </div>
          </div>
        )}
        
        <div>
          <label className="field-label block mb-1">Account Name <span className="text-error-500">*</span></label>
          <Input 
            placeholder="e.g., Main Operating Account" 
            value={formData.name} 
            onChange={(e) => setFormData(prev => ({ ...prev, name: e.target.value }))} 
          />
        </div>
        
        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className="field-label block mb-1">Currency</label>
            <CurrencyPicker
              value={formData.currency}
              onChange={(c) => setFormData(prev => ({ ...prev, currency: c }))}
              extra={['SAR']}
            />
          </div>
          <div>
            <label className="field-label block mb-1">Account Purpose</label>
            <select 
              value={formData.accountPurpose} 
              onChange={(e) => setFormData(prev => ({ ...prev, accountPurpose: e.target.value }))} 
              className="w-full px-3 py-2 border border-neutral-300 dark:border-primary-700 rounded-lg"
            >
              <option value="OPERATING">Operating</option>
              <option value="COLLECTIONS">Collection</option>
              <option value="PAYABLES">Disbursement</option>
              <option value="ESCROW">Escrow</option>
              <option value="TREASURY">Treasury</option>
              <option value="PAYROLL">Payroll</option>
            </select>
          </div>
        </div>
        
        <div>
          <label className="field-label block mb-1">Owning Entity</label>
          <select 
            value={formData.owningEntityId} 
            onChange={(e) => setFormData(prev => ({ ...prev, owningEntityId: e.target.value }))} 
            className="w-full px-3 py-2 border border-neutral-300 dark:border-primary-700 rounded-lg"
          >
            <option value="">Inherit from parent</option>
            {entities.filter(e => e.status === 'ACTIVE').map(entity => (
              <option key={entity.id} value={entity.id}>{entity.entityCode} - {entity.entityName}</option>
            ))}
          </select>
        </div>
        
        <div className="flex justify-end gap-3 pt-4 border-t">
          <Button variant="outline" onClick={onClose} disabled={loading}>Cancel</Button>
          <Button onClick={handleSubmit} disabled={loading || !formData.name.trim() || !parentNode}>
            {loading ? <Loader2 className="w-4 h-4 mr-1 animate-spin" /> : <Wallet className="w-4 h-4 mr-1" />}
            {loading ? 'Creating...' : 'Create Transaction VA'}
          </Button>
        </div>
      </div>
    </Modal>
  );
};

// ============================================================================
// CREATE IHB CURRENT ACCOUNT MODAL (NEW)
// ============================================================================

interface CreateIhbCurrentAccountModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: () => void;
  parentNode: ExtendedHierarchyNode | null;
  entities: LegalEntity[];
  programId?: string;
  corporateId?: string;
  programCurrency?: string;
  programName?: string;
}

const CreateIhbCurrentAccountModal: React.FC<CreateIhbCurrentAccountModalProps> = ({
  isOpen, onClose, onSuccess, parentNode, entities, programId, corporateId, programCurrency, programName,
}) => {
  const [formData, setFormData] = useState({
    vaName: '',
    currencyCode: 'AED',
    participantEntityId: '',
    creditLimit: '',
    creditRate: '',
    debitRate: '',
    penaltyRate: '',
    ihbSweepEnabled: true,
    targetCashBalance: '',
    ihbSweepFrequency: 'DAILY',
  });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [treasuryRates, setTreasuryRates] = useState<TreasuryRates | null>(null);

  // Debug: Log parentNode when modal opens
  useEffect(() => {
    if (isOpen) {
      console.log('[CreateIhbCurrentAccountModal] Modal opened, parentNode:', parentNode);
      console.log('[CreateIhbCurrentAccountModal] Modal opened, parentNode?.id:', parentNode?.id);
    }
  }, [isOpen, parentNode]);

  // Load treasury rates when modal opens and auto-fill rates
  useEffect(() => {
    const loadTreasuryRates = async () => {
      if (isOpen && corporateId) {
        try {
          const response = await ihbUnifiedApi.getTreasuryRates(corporateId);
          if (response.success && response.data) {
            setTreasuryRates(response.data);
            // Auto-fill treasury rates
            setFormData(prev => ({
              ...prev,
              creditRate: response.data.indicativeDepositRate?.toString() || '',
              debitRate: response.data.indicativeLendingRate?.toString() || '',
            }));
          }
        } catch (err) {
          console.error('Failed to load treasury rates:', err);
        }
      }
    };
    loadTreasuryRates();
  }, [isOpen, corporateId]);

  // Initialize form with program/parent defaults
  useEffect(() => {
    if (isOpen) {
      // Determine default currency: parent node > program > AED
      const defaultCurrency = parentNode?.currencyCode || programCurrency || 'AED';

      // If parent has owning entity, pre-select it and generate name
      const defaultEntityId = parentNode?.owningEntityId || '';
      const defaultEntity = defaultEntityId ? entities.find(e => e.id === defaultEntityId) : null;
      const defaultName = defaultEntity
        ? `IHB Current - ${defaultEntity.entityName}`
        : '';

      setFormData({
        vaName: defaultName,
        currencyCode: defaultCurrency,
        participantEntityId: defaultEntityId,
        creditLimit: defaultEntity?.ihbCreditLimit?.toString() || '',
        creditRate: '',
        debitRate: '',
        penaltyRate: '',
        ihbSweepEnabled: true,
        targetCashBalance: '',
        ihbSweepFrequency: 'DAILY',
      });
      setError(null);
    }
  }, [isOpen, parentNode, programCurrency, entities]);

  // Filter to only IHB-enabled entities that can borrow
  const ihbParticipantEntities = entities.filter(e =>
    e.status === 'ACTIVE' && e.ihbEnabled && e.canBorrow
  );

  const fillFromTreasuryRates = () => {
    if (treasuryRates) {
      setFormData(prev => ({
        ...prev,
        creditRate: treasuryRates.indicativeDepositRate?.toString() || '',
        debitRate: treasuryRates.indicativeLendingRate?.toString() || '',
      }));
    }
  };

  const handleSubmit = async () => {
    if (!formData.participantEntityId) {
      setError('Participant entity is required');
      return;
    }

    // Warn if parentNode is missing (but allow creation anyway)
    if (!parentNode?.id) {
      console.warn('[CreateIhbCurrentAccountModal] WARNING: parentNode is null or has no id - account will be created without hierarchy placement');
    }

    setLoading(true);
    setError(null);

    try {
      // Debug: Log parentNode to verify it's being passed correctly
      console.log('[CreateIhbCurrentAccountModal] parentNode:', parentNode);
      console.log('[CreateIhbCurrentAccountModal] parentNode?.id:', parentNode?.id);
      console.log('[CreateIhbCurrentAccountModal] programId:', programId);

      // Build request for IHB Current Account creation
      const request = {
        participantEntityId: formData.participantEntityId,
        currencyCode: formData.currencyCode,
        programId: programId || undefined,
        parentNodeId: parentNode?.id || undefined,
        creditLimit: formData.creditLimit ? parseFloat(formData.creditLimit) : undefined,
        creditRate: formData.creditRate ? parseFloat(formData.creditRate) : undefined,
        debitRate: formData.debitRate ? parseFloat(formData.debitRate) : undefined,
        penaltyRate: formData.penaltyRate ? parseFloat(formData.penaltyRate) : undefined,
        ihbSweepEnabled: formData.ihbSweepEnabled,
        targetCashBalance: formData.targetCashBalance ? parseFloat(formData.targetCashBalance) : undefined,
        ihbSweepFrequency: formData.ihbSweepFrequency,
        vaName: formData.vaName || undefined,
      };

      console.log('[CreateIhbCurrentAccountModal] Submitting request:', request);

      const response = await ihbUnifiedApi.createCurrentAccount(request);

      console.log('[CreateIhbCurrentAccountModal] Success response:', response);

      const vaNumber = response?.data?.vaNumber || 'IHB Account';
      toast.success(`✓ Created IHB Current Account: ${vaNumber}`, { duration: 4000, icon: '🏦' });
      onSuccess();
      onClose();
    } catch (err: any) {
      console.error('[CreateIhbCurrentAccountModal] Error:', err);
      const errorMessage = err.response?.data?.message || err.response?.data?.error || err.message || 'Failed to create IHB Current Account';
      setError(errorMessage);
      toast.error(errorMessage);
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="Create IHB Current Account" size="lg">
      <div className="p-4 space-y-4">
        {error && (
          <div className="p-3 bg-error-50 dark:bg-error-500/10 border border-error-200 dark:border-error-500/30 rounded-lg flex items-start gap-2 text-error-700 dark:text-error-300">
            <AlertCircle className="w-5 h-5 mt-0.5 flex-shrink-0" />
            <span className="text-sm">{error}</span>
          </div>
        )}

        {/* Description Banner */}
        <div className="bg-gradient-to-r from-purple-50 to-indigo-50 border border-purple-200 rounded-lg p-4 dark:border-purple-500/30 dark:from-purple-500/15 dark:to-indigo-500/15">
          <div className="flex items-start gap-3">
            <div className="w-10 h-10 rounded-xl bg-purple-100 flex items-center justify-center flex-shrink-0 dark:bg-purple-500/20">
              <PiggyBank className="w-5 h-5 text-purple-600 dark:text-purple-300" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <p className="text-sm font-semibold text-purple-900">IHB Current Account</p>
                {programName && (
                  <Badge variant="default" size="sm" className="bg-purple-100 text-purple-700 dark:bg-purple-500/20 dark:text-purple-300">
                    {programName}
                  </Badge>
                )}
              </div>
              <p className="text-xs text-purple-700 mt-1 dark:text-purple-300">
                Creates a Transaction VA with <span className="font-mono bg-purple-100 px-1 rounded dark:bg-purple-500/20">ihbParticipant=true</span>.
                Supports credit/debit interest and participates in treasury sweeps.
              </p>
              {programCurrency && (
                <p className="text-xs text-purple-600 mt-1 dark:text-purple-300">
                  Program currency: <span className="font-semibold">{programCurrency}</span>
                </p>
              )}
            </div>
          </div>
        </div>

        {/* Parent Node Info */}
        {parentNode && (
          <div className="p-3 bg-neutral-50 dark:bg-primary-950 border border-neutral-200 dark:border-primary-800 rounded-lg">
            <div className="flex items-center gap-2 text-sm">
              <GitBranch className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
              <span className="text-neutral-600 dark:text-neutral-300">Parent:</span>
              <span className="font-medium text-primary-900 dark:text-neutral-50">{parentNode.name}</span>
              <Badge variant="neutral" size="sm">{parentNode.currencyCode}</Badge>
            </div>
          </div>
        )}

        {/* Treasury Rates Preview */}
        {treasuryRates && (
          <div className="bg-neutral-50 dark:bg-primary-950 border border-neutral-200 dark:border-primary-800 rounded-lg p-3">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-xs font-medium text-neutral-500 dark:text-neutral-400 uppercase tracking-wide">Treasury Rates</p>
                <div className="flex gap-6 mt-2">
                  <div>
                    <p className="text-xs text-success-600 dark:text-success-300">Credit Rate (Earn)</p>
                    <p className="text-base font-bold text-success-700 dark:text-success-300">{treasuryRates.indicativeDepositRate?.toFixed(2) || 'N/A'}%</p>
                  </div>
                  <div>
                    <p className="text-xs text-error-600 dark:text-error-300">Debit Rate (Pay)</p>
                    <p className="text-base font-bold text-error-700 dark:text-error-300">{treasuryRates.indicativeLendingRate?.toFixed(2) || 'N/A'}%</p>
                  </div>
                </div>
              </div>
              <Button variant="ghost" size="sm" onClick={fillFromTreasuryRates} className="text-xs text-purple-600 dark:text-purple-300">
                <TrendingUp className="w-3 h-3 mr-1" /> Use Treasury Rates
              </Button>
            </div>
          </div>
        )}

        {/* Participant Entity Selection */}
        <div>
          <label className="field-label block mb-1">
            Participant Entity <span className="text-error-500">*</span>
          </label>
          <select
            value={formData.participantEntityId}
            onChange={(e) => {
              const entity = entities.find(ent => ent.id === e.target.value);
              setFormData(prev => ({
                ...prev,
                participantEntityId: e.target.value,
                currencyCode: entity?.functionalCurrency || prev.currencyCode,
                // Auto-generate VA name from entity
                vaName: entity ? `IHB Current - ${entity.entityName}` : '',
                // Set credit limit from entity's IHB credit limit
                creditLimit: entity?.ihbCreditLimit?.toString() || prev.creditLimit,
              }));
            }}
            className="w-full px-3 py-2 border border-neutral-300 dark:border-primary-700 rounded-lg"
          >
            <option value="">Select participant entity...</option>
            {ihbParticipantEntities.map(entity => (
              <option key={entity.id} value={entity.id}>
                {entity.entityName} ({entity.entityCode})
              </option>
            ))}
          </select>
          <p className="text-xs text-neutral-500 dark:text-neutral-400 mt-1">
            Only IHB-enabled entities with canBorrow=true can have IHB current accounts
          </p>
          {ihbParticipantEntities.length === 0 && (
            <p className="text-xs text-amber-600 mt-1 dark:text-amber-300">
              No IHB-enabled participant entities found. Enable IHB for entities first.
            </p>
          )}
        </div>

        {/* Account Details */}
        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className="field-label block mb-1">Currency</label>
            <CurrencyPicker
              value={formData.currencyCode}
              onChange={(c) => setFormData(prev => ({ ...prev, currencyCode: c }))}
              extra={['SAR']}
            />
          </div>
          <div>
            <label className="field-label block mb-1">Credit Limit (Overdraft)</label>
            <Input
              type="number"
              placeholder="Leave empty for entity default"
              value={formData.creditLimit}
              onChange={(e) => setFormData(prev => ({ ...prev, creditLimit: e.target.value }))}
            />
          </div>
        </div>

        {/* Interest Rates */}
        <div>
          <p className="text-xs font-semibold text-neutral-500 dark:text-neutral-400 uppercase tracking-wide mb-2">
            Interest Rates (Optional Override)
          </p>
          <div className="grid grid-cols-3 gap-4">
            <div>
              <label className="field-label block mb-1">Credit Rate (%)</label>
              <Input
                type="number"
                step="0.01"
                placeholder="Treasury default"
                value={formData.creditRate}
                onChange={(e) => setFormData(prev => ({ ...prev, creditRate: e.target.value }))}
              />
              <p className="text-xs text-success-600 mt-1 dark:text-success-300">Earned on positive balance</p>
            </div>
            <div>
              <label className="field-label block mb-1">Debit Rate (%)</label>
              <Input
                type="number"
                step="0.01"
                placeholder="Treasury default"
                value={formData.debitRate}
                onChange={(e) => setFormData(prev => ({ ...prev, debitRate: e.target.value }))}
              />
              <p className="text-xs text-error-600 mt-1 dark:text-error-300">Paid on overdraft</p>
            </div>
            <div>
              <label className="field-label block mb-1">Penalty Rate (%)</label>
              <Input
                type="number"
                step="0.01"
                placeholder="Treasury default"
                value={formData.penaltyRate}
                onChange={(e) => setFormData(prev => ({ ...prev, penaltyRate: e.target.value }))}
              />
              <p className="text-xs text-amber-600 mt-1 dark:text-amber-300">Over limit penalty</p>
            </div>
          </div>
        </div>

        {/* Sweep Configuration */}
        <div>
          <p className="text-xs font-semibold text-neutral-500 dark:text-neutral-400 uppercase tracking-wide mb-2">
            Sweep Configuration
          </p>
          <div className="space-y-3">
            <label className="flex items-center gap-2 cursor-pointer">
              <input
                type="checkbox"
                checked={formData.ihbSweepEnabled}
                onChange={(e) => setFormData(prev => ({ ...prev, ihbSweepEnabled: e.target.checked }))}
                className="w-4 h-4 rounded border-neutral-300 text-purple-600 focus:ring-purple-500 dark:border-primary-700 dark:text-purple-300"
              />
              <span className="text-sm text-neutral-700 dark:text-neutral-200">Enable Auto-Sweep to Treasury</span>
            </label>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="field-label block mb-1">Target Cash Balance</label>
                <Input
                  type="number"
                  placeholder="0"
                  value={formData.targetCashBalance}
                  onChange={(e) => setFormData(prev => ({ ...prev, targetCashBalance: e.target.value }))}
                  disabled={!formData.ihbSweepEnabled}
                />
              </div>
              <div>
                <label className="field-label block mb-1">Sweep Frequency</label>
                <select
                  value={formData.ihbSweepFrequency}
                  onChange={(e) => setFormData(prev => ({ ...prev, ihbSweepFrequency: e.target.value }))}
                  className="w-full px-3 py-2 border border-neutral-300 dark:border-primary-700 rounded-lg"
                  disabled={!formData.ihbSweepEnabled}
                >
                  <option value="DAILY">Daily</option>
                  <option value="REAL_TIME">Real-time</option>
                </select>
              </div>
            </div>
          </div>
        </div>

        {/* Optional VA Name */}
        <div>
          <label className="field-label block mb-1">Account Name (Optional)</label>
          <Input
            placeholder="Auto-generated if empty"
            value={formData.vaName}
            onChange={(e) => setFormData(prev => ({ ...prev, vaName: e.target.value }))}
          />
        </div>

        <div className="flex justify-end gap-3 pt-4 border-t">
          <Button variant="outline" onClick={onClose} disabled={loading}>Cancel</Button>
          <Button
            onClick={handleSubmit}
            disabled={loading || !formData.participantEntityId}
            className="bg-purple-600 hover:bg-purple-700"
          >
            {loading ? <Loader2 className="w-4 h-4 mr-1 animate-spin" /> : <PiggyBank className="w-4 h-4 mr-1" />}
            {loading ? 'Creating...' : 'Create IHB Account'}
          </Button>
        </div>
      </div>
    </Modal>
  );
};

// ============================================================================
// CORPORATE/PROGRAM FILTER BAR COMPONENT
// ============================================================================

interface CorporateProgramFilterBarProps {
  corporates: Corporate[];
  programs: ProgramOption[];
  selectedCorporateId: string;
  selectedProgramId: string;
  onCorporateChange: (id: string) => void;
  onProgramChange: (id: string) => void;
  loading?: boolean;
}

// Thin wrapper that delegates to the shared `<ScopeSelector>` primitive.
// Preserves the local CorporateProgramFilterBarProps contract so the seven
// existing call sites in this file don't need to change.
const CorporateProgramFilterBar: React.FC<CorporateProgramFilterBarProps> = ({
  corporates,
  programs,
  selectedCorporateId,
  selectedProgramId,
  onCorporateChange,
  onProgramChange,
  loading,
}) => (
  <ScopeSelector
    mode="corporate-program"
    corporates={corporates}
    programs={programs.filter(p =>
      p.status === 'ACTIVE'
      && (!selectedCorporateId || p.corporateId === selectedCorporateId),
    )}
    selectedCorporateId={selectedCorporateId}
    selectedProgramId={selectedProgramId}
    onCorporateChange={onCorporateChange}
    onProgramChange={onProgramChange}
    loading={loading}
    disableChildUntilParent
  />
);

// ============================================================================
// CURRENCY BREAKDOWN POPOVER COMPONENT (v5.7.1: Level-based breakdown)
// ============================================================================

interface CurrencyBreakdownPopoverProps {
  nodeId: string;
  corporateId: string;
  programId?: string;  // Added for program-based API
  baseCurrency: string;
  onClose: () => void;
}

const CurrencyBreakdownPopover: React.FC<CurrencyBreakdownPopoverProps> = ({
  nodeId: _nodeId, corporateId, programId, baseCurrency, onClose
}) => {
  const [breakdown, setBreakdown] = useState<CurrencyBreakdown[]>([]);
  const [levels, setLevels] = useState<LevelInfo[]>([]);
  const [selectedLevel, setSelectedLevel] = useState<number>(0); // Default to ROOT (0)
  const [loading, setLoading] = useState(true);
  const [loadingLevels, setLoadingLevels] = useState(true);

  // Load available levels
  useEffect(() => {
    const loadLevels = async () => {
      if (!programId) {
        setLoadingLevels(false);
        return;
      }
      try {
        const res = await currencyMirrorApi.getLevelsByProgram(programId);
        if (res.success && res.data) {
          setLevels(res.data);
          // Default to ROOT level (0) if available
          if (res.data.length > 0) {
            const rootLevel = res.data.find(l => l.level === 0);
            if (rootLevel) {
              setSelectedLevel(0);
            } else {
              setSelectedLevel(res.data[0].level);
            }
          }
        }
      } catch (err) {
        console.error('Failed to load levels:', err);
      } finally {
        setLoadingLevels(false);
      }
    };
    loadLevels();
  }, [programId]);

  // Load breakdown for selected level
  useEffect(() => {
    const loadBreakdown = async () => {
      setLoading(true);
      try {
        let res;
        if (programId) {
          // Use level-based API for program (defaults to ROOT level 0)
          res = await currencyMirrorApi.getBreakdownListByProgram(programId, selectedLevel);
        } else {
          res = await currencyMirrorApi.getBreakdownList(corporateId);
        }
        if (res.success && res.data) {
          setBreakdown(res.data);
        }
      } catch (err) {
        console.error('Failed to load currency breakdown:', err);
      } finally {
        setLoading(false);
      }
    };
    if (!loadingLevels) {
      loadBreakdown();
    }
  }, [corporateId, programId, selectedLevel, loadingLevels]);

  if (loading || loadingLevels) {
    return (
      <div className="absolute right-0 top-full mt-2 w-72 bg-white dark:bg-primary-900 rounded-lg shadow-xl border border-neutral-200 dark:border-primary-800 p-4 z-50">
        <div className="flex items-center justify-center py-4">
          <Loader2 className="w-5 h-5 animate-spin text-cyan-600 dark:text-cyan-300" />
        </div>
      </div>
    );
  }

  return (
    <div className="absolute right-0 top-full mt-2 w-80 bg-white dark:bg-primary-900 rounded-lg shadow-xl border border-neutral-200 dark:border-primary-800 z-50" onClick={(e) => e.stopPropagation()}>
      <div className="px-4 py-3 border-b border-neutral-100 dark:border-primary-800/60 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Coins className="w-4 h-4 text-cyan-600 dark:text-cyan-300" />
          <span className="text-sm font-semibold text-neutral-900 dark:text-neutral-50">Currency Breakdown</span>
        </div>
        <button onClick={onClose} className="text-neutral-400 dark:text-neutral-500 hover:text-neutral-600 dark:hover:text-neutral-300">
          <span className="text-lg">&times;</span>
        </button>
      </div>

      {/* Level Selector - only show if multiple levels available */}
      {levels.length > 1 && (
        <div className="px-4 py-2 border-b border-neutral-100 dark:border-primary-800/60 bg-neutral-50 dark:bg-primary-950">
          <div className="flex items-center gap-2">
            <Layers className="w-3.5 h-3.5 text-neutral-500 dark:text-neutral-400" />
            <select
              value={selectedLevel}
              onChange={(e) => setSelectedLevel(Number(e.target.value))}
              className="flex-1 text-xs bg-white dark:bg-primary-900 border border-neutral-200 dark:border-primary-800 rounded px-2 py-1 focus:ring-1 focus:ring-cyan-500"
            >
              {levels.map((lvl) => (
                <option key={lvl.level} value={lvl.level}>
                  {lvl.name}
                </option>
              ))}
            </select>
          </div>
        </div>
      )}

      <div className="p-3 space-y-2 max-h-64 overflow-y-auto">
        {breakdown.length === 0 ? (
          <p className="text-sm text-neutral-500 dark:text-neutral-400 text-center py-4">No currency mirrors found at this level</p>
        ) : (
          breakdown.map((cb) => (
            <div key={cb.currency} className="flex items-center justify-between p-2 bg-neutral-50 dark:bg-primary-950 rounded-lg">
              <div className="flex items-center gap-2">
                <Badge variant="neutral" size="sm">{cb.currency}</Badge>
                <span className="text-sm font-medium">{formatCurrency(cb.originalBalance, cb.currency)}</span>
              </div>
              <div className="text-right">
                <p className="text-xs text-neutral-500 dark:text-neutral-400">
                  {cb.currency === baseCurrency ? 'Base' : `@ ${cb.fxRate?.toFixed(4)}`}
                </p>
                <p className="text-sm font-medium text-cyan-600 dark:text-cyan-300">
                  {formatCurrency(cb.convertedBalance, baseCurrency)}
                </p>
              </div>
            </div>
          ))
        )}
      </div>
      <div className="px-4 py-2 border-t border-neutral-100 dark:border-primary-800/60 bg-neutral-50 dark:bg-primary-950 rounded-b-lg">
        <div className="flex items-center justify-between">
          <span className="text-xs text-neutral-500 dark:text-neutral-400">Total in {baseCurrency}</span>
          <span className="text-sm font-bold text-cyan-700 dark:text-cyan-300">
            {formatCurrency(breakdown.reduce((sum, cb) => sum + (cb.convertedBalance || 0), 0), baseCurrency)}
          </span>
        </div>
      </div>
    </div>
  );
};

// ============================================================================
// IHB DETAIL SECTION COMPONENT (ENHANCED)
// ============================================================================

interface IhbDetailSectionProps {
  ihb: IhbSummary;
  currency?: string;
  onConfigure?: () => void;
  treasuryRates?: TreasuryRates | null;
}

const IhbDetailSection: React.FC<IhbDetailSectionProps> = ({ ihb, currency = 'AED', onConfigure: _onConfigure, treasuryRates }) => {
  return (
    <div className="space-y-3">
      {/* Role Badges */}
      <div className="flex items-center gap-2 flex-wrap">
        {ihb.isTreasuryCenter ? (
          <Badge variant="warning" className="flex items-center gap-1">
            <Crown className="w-3 h-3" />
            Treasury Center
          </Badge>
        ) : (
          <>
            {ihb.canLend && <Badge variant="success" size="sm">Can Lend</Badge>}
            {ihb.canBorrow && <Badge variant="info" size="sm">Can Borrow</Badge>}
          </>
        )}
      </div>
      
      {/* Credit Limit */}
      <div className="grid grid-cols-2 gap-2 text-sm">
        <div>
          <p className="text-neutral-500 dark:text-neutral-400">{ihb.isTreasuryCenter ? 'Lending Capacity' : 'Borrowing Limit'}</p>
          <p className="font-semibold">{formatCurrency(ihb.creditLimit, currency)}</p>
        </div>
        <div>
          <p className="text-neutral-500 dark:text-neutral-400">Available</p>
          <p className="font-semibold text-success-600 dark:text-success-300">{formatCurrency(ihb.availableLimit, currency)}</p>
        </div>
      </div>
      
      {/* Utilization Bar */}
      {!ihb.isTreasuryCenter && (
        <div>
          <div className="flex justify-between text-xs text-neutral-500 mb-1 dark:text-neutral-400">
            <span>Utilization</span>
            <span>{(ihb.utilizationPercent || 0).toFixed(1)}%</span>
          </div>
          <div className="w-full bg-neutral-200 rounded-full h-2 dark:bg-primary-800">
            <div 
              className={cn(
                'h-2 rounded-full transition-all',
                (ihb.utilizationPercent || 0) > 90 ? 'bg-error-500' :
                (ihb.utilizationPercent || 0) > 70 ? 'bg-amber-500' : 'bg-success-500'
              )}
              style={{ width: `${Math.min(ihb.utilizationPercent || 0, 100)}%` }}
            />
          </div>
        </div>
      )}

      {/* Treasury Center: Show lending rates */}
      {ihb.isTreasuryCenter && (
        <div className="p-3 bg-amber-50 dark:bg-amber-500/10 rounded-lg border border-amber-100 dark:border-amber-500/30">
          <p className="text-xs font-medium text-amber-800 mb-2 dark:text-amber-300">Offered Rates</p>
          <div className="grid grid-cols-2 gap-2 text-xs">
            <div>
              <p className="text-amber-600 dark:text-amber-300">Lending</p>
              <p className="font-semibold text-amber-900">5.25%</p>
            </div>
            <div>
              <p className="text-amber-600 dark:text-amber-300">Deposit</p>
              <p className="font-semibold text-amber-900">4.75%</p>
            </div>
          </div>
        </div>
      )}

      {/* Participant: Show Treasury Center & rates */}
      {!ihb.isTreasuryCenter && treasuryRates && (
        <div className="p-3 bg-info-50 dark:bg-info-500/10 rounded-lg border border-info-100 dark:border-info-500/30">
          <div className="flex items-center gap-2 mb-2">
            <Crown className="w-3 h-3 text-amber-600 dark:text-amber-300" />
            <p className="text-xs font-medium text-info-800 dark:text-info-300">
              Treasury: {treasuryRates.treasuryCenterCode}
            </p>
          </div>
          <div className="grid grid-cols-2 gap-2 text-xs">
            <div>
              <p className="text-info-600 dark:text-info-300">Borrow at</p>
              <p className="font-semibold text-error-600 dark:text-error-300">{treasuryRates.indicativeLendingRate?.toFixed(2)}%</p>
            </div>
            <div>
              <p className="text-info-600 dark:text-info-300">Deposit at</p>
              <p className="font-semibold text-success-600 dark:text-success-300">{treasuryRates.indicativeDepositRate?.toFixed(2)}%</p>
            </div>
          </div>
        </div>
      )}
      
      {/* Target Balance */}
      {(ihb.targetCashBalance || 0) > 0 && (
        <div className="flex items-center gap-2 text-sm">
          <Target className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
          <span className="text-neutral-500 dark:text-neutral-400">Target Balance:</span>
          <span className="font-medium">{formatCurrency(ihb.targetCashBalance, currency)}</span>
        </div>
      )}
      
      {/* Sweep Config */}
      {ihb.sweepEnabled && (
        <div className="p-2 bg-info-50 dark:bg-info-500/10 rounded-lg flex items-center justify-between">
          <div className="flex items-center gap-2">
            <RefreshCw className="w-4 h-4 text-info-600 dark:text-info-300" />
            <span className="text-sm text-info-700 dark:text-info-300">EOD Sweep</span>
          </div>
          <Badge variant="info" size="sm">
            {ihb.sweepFrequency === 'REAL_TIME' ? 'Real-time' : 'Daily @ 6PM'}
          </Badge>
        </div>
      )}
    </div>
  );
};

// ============================================================================
// TREE NODE COMPONENT (ENHANCED)
// ============================================================================

interface TreeNodeProps {
  node: ExtendedHierarchyNode;
  expandedIds: Set<string>;
  onToggle: (id: string) => void;
  selectedId: string | null;
  onSelect: (node: ExtendedHierarchyNode) => void;
  reportingCurrency: string;
  showInterest: boolean;
  showSystemVas?: boolean;  // Toggle to show/hide system VAs (Currency Mirrors, Settlement, Exception, Shadow)
  onAddChild?: (parentNode: ExtendedHierarchyNode) => void;  // CHANGED: now receives full node
  onCreateSettlementVa?: (parentId: string) => void;
  onViewExceptions?: (nodeId: string) => void;
  onConfigureIhb?: (entityId: string) => void;
  corporateId?: string;
  programId?: string;  // Added for program-based currency breakdown API
}

const TreeNode: React.FC<TreeNodeProps> = ({
  node, expandedIds, onToggle, selectedId, onSelect, reportingCurrency, showInterest,
  showSystemVas = true, onAddChild, onCreateSettlementVa, onViewExceptions, onConfigureIhb, corporateId, programId,
}) => {
  const isExpanded = expandedIds.has(node.id);
  const isSelected = selectedId === node.id;
  const [showContextMenu, setShowContextMenu] = useState(false);

  // System VA categories to filter when showSystemVas is false
  const SYSTEM_VA_CATEGORIES: AccountCategory[] = ['CURRENCY_MIRROR', 'PHYSICAL_MIRROR', 'SETTLEMENT', 'EXCEPTION'];

  // Filter children based on showSystemVas toggle
  const filteredChildren = node.children?.filter((child) => {
    if (showSystemVas) return true;
    const childNode = child as ExtendedHierarchyNode;
    return !SYSTEM_VA_CATEGORIES.includes(childNode.accountCategory as AccountCategory);
  }) || [];

  const hasChildren = filteredChildren.length > 0;

  const determineSpecialType = (): VaSpecialType => {
    if (node.accountCategory === 'CURRENCY_MIRROR') return 'CURRENCY_MIRROR';
    if (node.accountCategory === 'SETTLEMENT' || node.specialType === 'SETTLEMENT') return 'SETTLEMENT';
    if (node.accountCategory === 'EXCEPTION' || node.specialType === 'EXCEPTION') return 'EXCEPTION';
    return 'REGULAR';
  };

  const specialType = determineSpecialType();
  const specialConfig = SPECIAL_VA_CONFIG[specialType];
  const SpecialIcon = specialConfig.icon;
  const isCurrencyMirror = specialType === 'CURRENCY_MIRROR';
  const isAggregationNode = node.accountCategory === 'AGGREGATION' || node.accountCategory === 'ROOT' || node.type === 'GROUP';
  const canAddChildren = isAggregationNode || node.type === 'REGION' || node.type === 'ENTITY';

  const getNodeStyle = () => {
    if (isCurrencyMirror) {
      return { bg: 'bg-cyan-50 dark:bg-cyan-500/10', text: 'text-cyan-700 dark:text-cyan-300', icon: Coins, border: 'border-2 border-dashed border-cyan-400 dark:border-cyan-500/30' };
    }
    if (specialType === 'SETTLEMENT') {
      return { bg: 'bg-purple-50 dark:bg-purple-500/10', text: 'text-purple-600 dark:text-purple-300', icon: Scale, border: 'border-2 border-purple-300 dark:border-purple-500/30' };
    }
    if (specialType === 'EXCEPTION') {
      return { bg: 'bg-amber-50 dark:bg-amber-500/10', text: 'text-amber-600 dark:text-amber-300', icon: AlertTriangle, border: 'border-2 border-amber-300 dark:border-amber-500/30' };
    }
    switch (node.type) {
      case 'GROUP': return { bg: 'bg-primary-900', text: 'text-white', icon: Globe, border: '' };
      case 'REGION': return { bg: 'bg-info-100 dark:bg-info-500/20', text: 'text-info-800 dark:text-info-300', icon: MapPin, border: '' };
      case 'ENTITY': return { bg: 'bg-indigo-100 dark:bg-indigo-500/20', text: 'text-indigo-800 dark:text-indigo-300', icon: Building2, border: '' };
      case 'VIRTUAL_ACCOUNT': return { bg: 'bg-success-50 dark:bg-success-500/10', text: 'text-success-700 dark:text-success-300', icon: Wallet, border: '' };
      case 'SHADOW_ACCOUNT': return { bg: 'bg-amber-100 dark:bg-amber-500/20', text: 'text-amber-800 dark:text-amber-300', icon: Layers, border: 'border-2 border-dashed border-amber-400' };
      default: return { bg: 'bg-neutral-100 dark:bg-primary-800', text: 'text-neutral-700 dark:text-neutral-200', icon: Wallet, border: '' };
    }
  };

  const style = getNodeStyle();
  const Icon = style.icon;
  const displayBalance = isCurrencyMirror ? (node.mirrorBalance || node.localBalance) : node.localBalance;

  useEffect(() => {
    const handleClickOutside = () => {
      setShowContextMenu(false);
    };
    if (showContextMenu) {
      document.addEventListener('click', handleClickOutside);
      return () => document.removeEventListener('click', handleClickOutside);
    }
  }, [showContextMenu]);

  return (
    <div className="select-none group">
      <div
        className={cn(
          'flex items-center gap-2 p-3 rounded-lg cursor-pointer transition-all relative',
          isSelected ? 'ring-2 ring-primary-500 bg-primary-50 dark:bg-primary-800/40' : 'hover:bg-neutral-50 dark:hover:bg-primary-800/50',
          style.border
        )}
        style={{ marginLeft: `${node.level * 28}px` }}
        onClick={() => onSelect(node)}
      >
        {hasChildren ? (
          <button onClick={(e) => { e.stopPropagation(); onToggle(node.id); }} className="p-1 hover:bg-neutral-200 rounded">
            {isExpanded ? <ChevronDown className="w-4 h-4 text-neutral-500 dark:text-neutral-400" /> : <ChevronRight className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />}
          </button>
        ) : <span className="w-6" />}

        <div className={cn('p-2 rounded-lg', style.bg)}>
          <Icon className={cn('w-4 h-4', node.type === 'GROUP' ? 'text-white' : style.text)} />
        </div>

        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <p className="text-sm font-medium text-primary-900 truncate dark:text-neutral-50">{node.name}</p>
            
            {/* ENHANCED: Entity Badge */}
            {node.owningEntity && (
              <Badge variant="neutral" size="sm" className="text-neutral-600 dark:text-neutral-300 bg-neutral-50 dark:bg-primary-950">
                {node.owningEntity.entityCode}
              </Badge>
            )}
            
            {/* ENHANCED: IHB Badges */}
            {node.ihb?.enabled && (
              <>
                {node.ihb.isTreasuryCenter ? (
                  <Badge variant="warning" size="sm" className="flex items-center gap-1">
                    <Crown className="w-3 h-3" />
                    Treasury
                  </Badge>
                ) : (
                  <Badge variant="info" size="sm" className="flex items-center gap-1">
                    <PiggyBank className="w-3 h-3" />
                    IHB
                  </Badge>
                )}
                {node.ihb.sweepEnabled && (
                  <Badge variant="neutral" size="sm" className="flex items-center gap-1">
                    <RefreshCw className="w-3 h-3" />
                    {node.ihb.sweepFrequency === 'REAL_TIME' ? 'RT' : 'Daily'}
                  </Badge>
                )}
              </>
            )}
            
            {isCurrencyMirror && (
              <Badge variant="info" size="sm" className="bg-cyan-100 text-cyan-700 border-cyan-300 dark:bg-cyan-500/20 dark:text-cyan-300">
                <Coins className="w-3 h-3 mr-1" />M-Node
              </Badge>
            )}
            {(specialType === 'SETTLEMENT' || specialType === 'EXCEPTION') && (
              <Badge variant={specialType === 'SETTLEMENT' ? 'info' : 'warning'} size="sm">
                <SpecialIcon className="w-3 h-3 mr-1" />{specialConfig.label}
              </Badge>
            )}
            {node.participatesInSweep && <Badge variant="warning" size="sm">Sweep</Badge>}
            {node.participatesInPooling && node.type !== 'VIRTUAL_ACCOUNT' && <Badge variant="info" size="sm">Pool</Badge>}
          </div>
          <div className="flex items-center gap-2">
            {node.accountNumber && <p className="text-xs text-neutral-500 dark:text-neutral-400 font-mono">{node.accountNumber}</p>}
            {isCurrencyMirror && (
              <span className="text-xs text-cyan-600 flex items-center gap-1 dark:text-cyan-300">
                <span className="w-1.5 h-1.5 rounded-full bg-cyan-500"></span>No FX conversion
              </span>
            )}
          </div>
        </div>

        <Badge variant="neutral" size="sm">{node.currencyCode}</Badge>

        <div className="text-right min-w-[120px]">
          <p className={cn('text-sm font-semibold', isCurrencyMirror ? 'text-cyan-700 dark:text-cyan-300' : 'text-primary-900 dark:text-neutral-50')}>
            {formatCurrency(displayBalance, node.currencyCode)}
          </p>
          {!isCurrencyMirror && node.currencyCode !== reportingCurrency && (
            <p className="text-xs text-neutral-400 dark:text-neutral-500">≈ {formatCurrency(node.consolidatedBalance, reportingCurrency)}</p>
          )}
        </div>


        <div className="text-right min-w-[130px]">
          <div className="flex items-center justify-end gap-1">
            {node.netPosition >= node.consolidatedBalance 
              ? <ArrowUpRight className="w-4 h-4 text-success-500" />
              : <ArrowDownRight className="w-4 h-4 text-error-500" />}
            <p className={cn('text-sm font-bold', node.netPosition >= node.consolidatedBalance ? 'text-success-600 dark:text-success-300' : 'text-error-600 dark:text-error-300')}>
              {formatCurrency(node.netPosition, reportingCurrency)}
            </p>
          </div>
          <p className="text-xs text-neutral-400 dark:text-neutral-500">Net Position</p>
        </div>

        <div className="relative">
          <button onClick={(e) => { e.stopPropagation(); setShowContextMenu(!showContextMenu); }} className="p-1 hover:bg-neutral-200 rounded opacity-0 group-hover:opacity-100 transition-opacity">
            <MoreVertical className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
          </button>
          {showContextMenu && (
            <div className="absolute right-0 top-full mt-1 w-52 bg-white dark:bg-primary-900 rounded-lg shadow-lg border border-neutral-200 dark:border-primary-800 py-1 z-50">
              {canAddChildren && onAddChild && (
                <button onClick={(e) => { e.stopPropagation(); onAddChild(node); setShowContextMenu(false); }}
                  className="w-full px-3 py-2 text-left text-sm hover:bg-neutral-50 dark:hover:bg-primary-800/50 flex items-center gap-2">
                  <Plus className="w-4 h-4 text-indigo-500" />Add Child Node
                </button>
              )}
              {canAddChildren && onCreateSettlementVa && (
                <button onClick={(e) => { e.stopPropagation(); onCreateSettlementVa(node.id); setShowContextMenu(false); }}
                  className="w-full px-3 py-2 text-left text-sm hover:bg-neutral-50 dark:hover:bg-primary-800/50 flex items-center gap-2">
                  <Scale className="w-4 h-4 text-purple-500" />Create Settlement VA
                </button>
              )}
              
              {/* ENHANCED: IHB Context Menu Items */}
              {node.owningEntity && (
                <>
                  <div className="border-t border-neutral-100 dark:border-primary-800/60 my-1" />
                  {node.ihb?.enabled ? (
                    <button onClick={(e) => { e.stopPropagation(); node.owningEntity && onConfigureIhb?.(node.owningEntity.id); setShowContextMenu(false); }}
                      className="w-full px-3 py-2 text-left text-sm hover:bg-neutral-50 dark:hover:bg-primary-800/50 flex items-center gap-2">
                      <Settings className="w-4 h-4 text-info-500" />IHB Settings
                    </button>
                  ) : (
                    <button onClick={(e) => { e.stopPropagation(); node.owningEntity && onConfigureIhb?.(node.owningEntity.id); setShowContextMenu(false); }}
                      className="w-full px-3 py-2 text-left text-sm hover:bg-neutral-50 dark:hover:bg-primary-800/50 flex items-center gap-2">
                      <Power className="w-4 h-4 text-success-500" />Enable IHB
                    </button>
                  )}
                </>
              )}
              
              <div className="border-t border-neutral-100 dark:border-primary-800/60 my-1" />
              <button onClick={(e) => { e.stopPropagation(); onSelect(node); setShowContextMenu(false); }}
                className="w-full px-3 py-2 text-left text-sm hover:bg-neutral-50 dark:hover:bg-primary-800/50 flex items-center gap-2">
                <Eye className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />View Details
              </button>
            </div>
          )}
        </div>
      </div>

      {hasChildren && isExpanded && (
        <div className="relative">
          <div className="absolute left-0 top-0 bottom-4 border-l-2 border-dashed border-neutral-200 dark:border-primary-800" style={{ marginLeft: `${(node.level + 1) * 28 + 12}px` }} />
          {(filteredChildren as ExtendedHierarchyNode[]).map((child) => (
            <TreeNode
              key={child.id}
              node={child}
              expandedIds={expandedIds}
              onToggle={onToggle}
              selectedId={selectedId}
              onSelect={onSelect}
              reportingCurrency={reportingCurrency}
              showInterest={showInterest}
              showSystemVas={showSystemVas}
              onAddChild={onAddChild}
              onCreateSettlementVa={onCreateSettlementVa}
              onViewExceptions={onViewExceptions}
              onConfigureIhb={onConfigureIhb}
              corporateId={corporateId}
              programId={programId}
            />
          ))}
        </div>
      )}
    </div>
  );
};

// ============================================================================
// DETAIL PANEL COMPONENT
// ============================================================================

interface DetailPanelProps {
  node: ExtendedHierarchyNode | null;
  detail: BalanceNodeDetail | null;
  loading: boolean;
  reportingCurrency: string;
  programId?: string;  // v5.7.1: For currency breakdown by level
  onCreateViban?: () => void;
  onViewExceptions?: () => void;
  onRecalculateMirror?: (mirrorId: string) => void;
  onAssignEntity?: (node: ExtendedHierarchyNode) => void;
  onConfigureIhb?: (entityId: string) => void;
  treasuryRates?: TreasuryRates | null;
}

const DetailPanel: React.FC<DetailPanelProps> = ({
  node, detail, loading, reportingCurrency, programId, onCreateViban, onViewExceptions: _onViewExceptions, onRecalculateMirror,
  onAssignEntity, onConfigureIhb, treasuryRates
}) => {
  // v5.7.1: Currency breakdown state for AGGREGATION/ROOT nodes
  const [currencyBreakdown, setCurrencyBreakdown] = useState<CurrencyBreakdown[]>([]);
  const [breakdownLoading, setBreakdownLoading] = useState(false);

  // Determine if this is an aggregation node that should show currency breakdown
  const isAggregationNode = node?.accountCategory === 'AGGREGATION' || node?.accountCategory === 'ROOT';

  // Fetch currency breakdown when node changes (for aggregation nodes)
  useEffect(() => {
    const fetchBreakdown = async () => {
      // UUID validation regex
      const uuidRegex = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

      // Validate required data - need node with valid ID
      if (!node || !node.id || !uuidRegex.test(node.id)) {
        console.log('[DetailPanel] Skipping breakdown fetch - missing node or invalid ID:', {
          hasNode: !!node,
          nodeId: node?.id,
          isValidUUID: node?.id ? uuidRegex.test(node.id) : false
        });
        setCurrencyBreakdown([]);
        return;
      }

      // Only fetch for AGGREGATION/ROOT nodes
      const isAggNode = node.accountCategory === 'AGGREGATION' || node.accountCategory === 'ROOT';
      if (!isAggNode) {
        setCurrencyBreakdown([]);
        return;
      }

      console.log('[DetailPanel] Fetching currency breakdown for specific node:', {
        nodeId: node.id,
        nodeName: node.name,
        nodeLevel: node.level ?? 0,
        accountCategory: node.accountCategory
      });

      setBreakdownLoading(true);
      try {
        // v5.7.2: Use node-specific API to get only mirrors under this specific node
        // This ensures we don't show mirrors from sibling AGGREGATION nodes at the same level
        const res = await currencyMirrorApi.getBreakdownListByNode(node.id);
        console.log('[DetailPanel] Currency breakdown response:', res);
        if (res.success && res.data) {
          setCurrencyBreakdown(res.data);
        } else {
          console.warn('[DetailPanel] No data in response:', res);
          setCurrencyBreakdown([]);
        }
      } catch (err) {
        console.error('[DetailPanel] Failed to load currency breakdown:', err);
        setCurrencyBreakdown([]);
      } finally {
        setBreakdownLoading(false);
      }
    };

    fetchBreakdown();
  }, [node?.id, node?.accountCategory]);

  if (loading) return <div className="h-full flex items-center justify-center"><Loader2 className="w-6 h-6 animate-spin text-neutral-400 dark:text-neutral-500" /></div>;
  if (!node) return <div className="h-full flex items-center justify-center text-neutral-400 dark:text-neutral-500"><p>Select an entity to view details</p></div>;

  const displayData = detail || node;
  const icNet = (displayData.intercompanyReceivable || 0) - (displayData.intercompanyPayable || 0);

  const determineSpecialType = (): VaSpecialType => {
    if (node.accountCategory === 'CURRENCY_MIRROR') return 'CURRENCY_MIRROR';
    if (node.accountCategory === 'SETTLEMENT' || node.specialType === 'SETTLEMENT') return 'SETTLEMENT';
    if (node.accountCategory === 'EXCEPTION' || node.specialType === 'EXCEPTION') return 'EXCEPTION';
    return 'REGULAR';
  };

  const specialType = determineSpecialType();
  const specialConfig = SPECIAL_VA_CONFIG[specialType];
  const SpecialIcon = specialConfig.icon;
  const isCurrencyMirror = specialType === 'CURRENCY_MIRROR';

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h3 className="section-title">{displayData.name}</h3>
          {displayData.accountNumber && <p className="text-sm text-neutral-500 font-mono dark:text-neutral-400">{displayData.accountNumber}</p>}
        </div>
        <div className="flex items-center gap-2">
          {specialType !== 'REGULAR' && (
            <Badge variant={specialType === 'CURRENCY_MIRROR' ? 'info' : specialType === 'SETTLEMENT' ? 'info' : 'warning'}
                   className={isCurrencyMirror ? 'bg-cyan-100 text-cyan-700 dark:bg-cyan-500/20 dark:text-cyan-300' : ''}>
              <SpecialIcon className="w-3 h-3 mr-1" />{specialConfig.label}
            </Badge>
          )}
          <Badge variant={displayData.type === 'SHADOW_ACCOUNT' ? 'warning' : 'info'}>{displayData.type.replace('_', ' ')}</Badge>
        </div>
      </div>

      {isCurrencyMirror && (
        <div className="flex items-start gap-3 p-3 rounded-lg border bg-cyan-50 dark:bg-cyan-500/10 border-cyan-200 dark:border-cyan-500/30">
          <Coins className="w-5 h-5 text-cyan-600 mt-0.5 dark:text-cyan-300" />
          <div className="flex-1">
            <p className="text-sm font-medium text-cyan-800 dark:text-cyan-300">Currency Mirror (M-Node)</p>
            <p className="text-xs text-cyan-600 mt-0.5 dark:text-cyan-300">Aggregates all {node.currencyCode} balances without FX conversion.</p>
            {node.fxRate && node.fxRate !== 1 && (
              <div className="mt-2 flex items-center gap-4 text-xs">
                <span className="text-cyan-700 dark:text-cyan-300">FX Rate: <strong>{node.fxRate.toFixed(4)}</strong></span>
              </div>
            )}
          </div>
          {onRecalculateMirror && (
            <Button variant="ghost" size="sm" onClick={() => onRecalculateMirror(node.id)}>
              <RefreshCw className="w-4 h-4" />
            </Button>
          )}
        </div>
      )}

      <div className="grid grid-cols-2 gap-3">
        <div className={cn('rounded-lg p-3', isCurrencyMirror ? 'bg-cyan-50 dark:bg-cyan-500/10' : 'bg-neutral-50 dark:bg-primary-950')}>
          <p className="text-xs text-neutral-500 dark:text-neutral-400">{isCurrencyMirror ? 'Mirror Balance' : 'Local Balance'}</p>
          <p className={cn('text-lg font-semibold', isCurrencyMirror ? 'text-cyan-700 dark:text-cyan-300' : 'text-primary-900 dark:text-neutral-50')}>
            {formatCurrency(isCurrencyMirror ? (node.mirrorBalance || node.localBalance) : displayData.localBalance, displayData.currencyCode)}
          </p>
        </div>
        <div className="bg-neutral-50 dark:bg-primary-950 rounded-lg p-3">
          <p className="text-xs text-neutral-500 dark:text-neutral-400">{isCurrencyMirror ? 'In Base Currency' : 'Available'}</p>
          <p className="section-title">
            {isCurrencyMirror
              ? formatCurrency(node.balanceInBase || node.consolidatedBalance, reportingCurrency)
              : formatCurrency(displayData.availableBalance || displayData.localBalance, displayData.currencyCode)
            }
          </p>
        </div>
      </div>

      {/* v5.7.1: Currency Breakdown Section for AGGREGATION/ROOT nodes */}
      {isAggregationNode && (
        <div className="space-y-2 pt-4 border-t">
          <div className="flex items-center justify-between">
            <h4 className="text-sm font-semibold text-neutral-700 uppercase tracking-wide flex items-center gap-2 dark:text-neutral-200">
              <Coins className="w-4 h-4 text-cyan-600 dark:text-cyan-300" />
              Currency Breakdown
            </h4>
            <span className="text-xs text-neutral-400 dark:text-neutral-500">
              {node.accountCategory === 'ROOT' ? 'Total' : `Level ${node.level ?? 0}`}
            </span>
          </div>

          {breakdownLoading ? (
            <div className="flex items-center justify-center py-4">
              <Loader2 className="w-5 h-5 animate-spin text-cyan-600 dark:text-cyan-300" />
            </div>
          ) : currencyBreakdown.length === 0 ? (
            <div className="p-3 bg-neutral-50 dark:bg-primary-950 rounded-lg">
              <p className="text-sm text-neutral-500 text-center dark:text-neutral-400">No currency mirrors at this level</p>
            </div>
          ) : (
            <div className="space-y-2">
              {currencyBreakdown.map((cb) => (
                <div key={cb.currency} className="flex items-center justify-between p-2.5 bg-gradient-to-r from-cyan-50 to-neutral-50 rounded-lg border border-cyan-100 dark:border-cyan-500/30 dark:from-cyan-500/15 dark:to-neutral-500/15">
                  <div className="flex items-center gap-2">
                    <Badge variant="neutral" size="sm" className="bg-white dark:bg-primary-900 border border-cyan-200 dark:border-cyan-500/30 text-cyan-700 dark:text-cyan-300 font-mono">
                      {cb.currency}
                    </Badge>
                    <span className="text-sm font-semibold text-neutral-800 dark:text-neutral-100">
                      {formatCurrency(cb.originalBalance, cb.currency)}
                    </span>
                  </div>
                  <div className="text-right">
                    <p className="text-xs text-neutral-500 dark:text-neutral-400">
                      {cb.currency === reportingCurrency ? 'Base' : `@ ${cb.fxRate?.toFixed(4) || '1.0000'}`}
                    </p>
                    <p className="text-sm font-medium text-cyan-700 dark:text-cyan-300">
                      {formatCurrency(cb.convertedBalance, reportingCurrency)}
                    </p>
                  </div>
                </div>
              ))}
              {/* Total row */}
              <div className="flex items-center justify-between p-2.5 bg-cyan-100 rounded-lg border border-cyan-200 dark:bg-cyan-500/20 dark:border-cyan-500/30">
                <span className="text-sm font-semibold text-cyan-800 dark:text-cyan-300">Total in {reportingCurrency}</span>
                <span className="text-base font-bold text-cyan-900">
                  {formatCurrency(currencyBreakdown.reduce((sum, cb) => sum + (cb.convertedBalance || 0), 0), reportingCurrency)}
                </span>
              </div>
            </div>
          )}
        </div>
      )}

      {/* ENHANCED: Legal Entity Section */}
      {node.owningEntity && (
        <div className="space-y-2 pt-4 border-t">
          <div className="flex items-center justify-between">
            <h4 className="text-sm font-semibold text-neutral-700 uppercase tracking-wide flex items-center gap-2 dark:text-neutral-200">
              <Building2 className="w-4 h-4" />
              Legal Entity
            </h4>
            <Button variant="ghost" size="sm" onClick={() => onAssignEntity?.(node)}>Change</Button>
          </div>
          <div className="p-3 bg-neutral-50 dark:bg-primary-950 rounded-lg">
            <div className="flex items-center gap-2">
              <span className="font-medium text-primary-900 dark:text-neutral-50">{node.owningEntity.entityName}</span>
            </div>
            <div className="text-xs text-neutral-500 mt-1 flex items-center gap-2 dark:text-neutral-400">
              <span>{node.owningEntity.entityCode}</span>
              <span>•</span>
              <span>{node.owningEntity.entityType}</span>
              {node.owningEntity.countryCode && (
                <>
                  <span>•</span>
                  <span>{node.owningEntity.countryCode}</span>
                </>
              )}
            </div>
          </div>
        </div>
      )}

      {/* No Entity Assigned */}
      {!node.owningEntity && node.accountCategory !== 'ROOT' && (
        <div className="space-y-2 pt-4 border-t">
          <h4 className="text-sm font-semibold text-neutral-700 uppercase tracking-wide flex items-center gap-2 dark:text-neutral-200">
            <Building2 className="w-4 h-4" />
            Legal Entity
          </h4>
          <div className="p-3 bg-amber-50 dark:bg-amber-500/10 border border-amber-200 dark:border-amber-500/30 rounded-lg">
            <p className="text-sm text-amber-700 dark:text-amber-300">No entity assigned</p>
            <Button variant="outline" size="sm" className="mt-2" onClick={() => onAssignEntity?.(node)}>
              <Building2 className="w-4 h-4 mr-1" />
              Assign Entity
            </Button>
          </div>
        </div>
      )}

      {/* ENHANCED: IHB Section */}
      {node.owningEntity && (
        <div className="space-y-2 pt-4 border-t">
          <div className="flex items-center justify-between">
            <h4 className="text-sm font-semibold text-neutral-700 uppercase tracking-wide flex items-center gap-2 dark:text-neutral-200">
              <PiggyBank className="w-4 h-4" />
              In-House Banking
            </h4>
            {node.ihb?.enabled ? (
              <Button variant="ghost" size="sm" onClick={() => node.owningEntity && onConfigureIhb?.(node.owningEntity.id)}>
                <Settings className="w-3 h-3 mr-1" />
                Configure
              </Button>
            ) : (
              <Button variant="ghost" size="sm" onClick={() => node.owningEntity && onConfigureIhb?.(node.owningEntity.id)}>
                <Power className="w-3 h-3 mr-1" />
                Enable
              </Button>
            )}
          </div>
          
          {node.ihb?.enabled ? (
            <IhbDetailSection ihb={node.ihb} currency={node.currencyCode} treasuryRates={treasuryRates} />
          ) : (
            <div className="p-3 bg-neutral-50 dark:bg-primary-950 rounded-lg">
              <p className="text-sm text-neutral-500 dark:text-neutral-400">IHB not enabled for this entity</p>
              <p className="text-xs text-neutral-400 mt-1 dark:text-neutral-500">Enable to participate in intercompany loans, deposits, and EOD sweeps</p>
              <Button 
                variant="outline" 
                size="sm" 
                className="w-full mt-2"
                onClick={() => node.owningEntity && onConfigureIhb?.(node.owningEntity.id)}
              >
                <Power className="w-3 h-3 mr-1" />
                Join IHB Scheme
              </Button>
            </div>
          )}
        </div>
      )}

      {((displayData.intercompanyReceivable || 0) > 0 || (displayData.intercompanyPayable || 0) > 0) && (
        <>
          <h4 className="text-sm font-semibold text-primary-900 pt-2 dark:text-neutral-50">Intercompany Positions</h4>
          <div className="grid grid-cols-3 gap-3">
            <div className="bg-success-50 dark:bg-success-500/10 rounded-lg p-3">
              <p className="text-xs text-neutral-500 dark:text-neutral-400">Receivable</p>
              <p className="text-base font-semibold text-success-600 dark:text-success-300">+{formatCurrency(displayData.intercompanyReceivable || 0, reportingCurrency)}</p>
            </div>
            <div className="bg-error-50 dark:bg-error-500/10 rounded-lg p-3">
              <p className="text-xs text-neutral-500 dark:text-neutral-400">Payable</p>
              <p className="text-base font-semibold text-error-600 dark:text-error-300">-{formatCurrency(displayData.intercompanyPayable || 0, reportingCurrency)}</p>
            </div>
            <div className={cn('rounded-lg p-3', icNet >= 0 ? 'bg-success-50 dark:bg-success-500/10' : 'bg-error-50 dark:bg-error-500/10')}>
              <p className="text-xs text-neutral-500 dark:text-neutral-400">Net</p>
              <p className={cn('text-base font-semibold', icNet >= 0 ? 'text-success-600 dark:text-success-300' : 'text-error-600 dark:text-error-300')}>{icNet >= 0 ? '+' : ''}{formatCurrency(icNet, reportingCurrency)}</p>
            </div>
          </div>
        </>
      )}

      <h4 className="text-sm font-semibold text-primary-900 pt-2 dark:text-neutral-50">Participation</h4>
      <div className="flex flex-wrap gap-2">
        <Badge variant={displayData.participatesInPooling ? 'success' : 'neutral'} size="sm">{displayData.participatesInPooling ? '✓' : '✗'} Notional Pooling</Badge>
        <Badge variant={displayData.participatesInNetting ? 'success' : 'neutral'} size="sm">{displayData.participatesInNetting ? '✓' : '✗'} Balance Netting</Badge>
        <Badge variant={displayData.participatesInSweep ? 'success' : 'neutral'} size="sm">{displayData.participatesInSweep ? '✓' : '✗'} Cash Concentration</Badge>
      </div>

      {node.type === 'VIRTUAL_ACCOUNT' && specialType === 'REGULAR' && !isCurrencyMirror && (
        <>
          <h4 className="text-sm font-semibold text-primary-900 pt-2 flex items-center gap-2 dark:text-neutral-50">
            <CreditCard className="w-4 h-4" />VIBANs
          </h4>
          <Button variant="outline" size="sm" className="w-full" onClick={onCreateViban}><Plus className="w-4 h-4 mr-1" />Add VIBAN</Button>
        </>
      )}
    </div>
  );
};

// ============================================================================
// ASSIGN ENTITY MODAL (ENHANCED)
// ============================================================================

interface AssignEntityModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: () => void;
  node: ExtendedHierarchyNode | null;
  entities: LegalEntity[];
}

const AssignEntityModal: React.FC<AssignEntityModalProps> = ({
  isOpen, onClose, onSuccess, node, entities,
}) => {
  const [selectedEntityId, setSelectedEntityId] = useState('');
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (isOpen && node?.owningEntity) {
      setSelectedEntityId(node.owningEntity.id);
    } else {
      setSelectedEntityId('');
    }
  }, [isOpen, node]);

  // Find the selected entity to get its code
  const selectedEntity = entities.find(e => e.id === selectedEntityId);

  const handleSubmit = async () => {
    if (!node || !selectedEntityId) return;
    setLoading(true);
    try {
      // Use virtualAccountsApi.update to update owning entity
      await virtualAccountsApi.update(node.id, {
        owningEntityId: selectedEntityId,
        owningEntityCode: selectedEntity?.entityCode || undefined
      });
      toast.success(`Entity ${selectedEntity?.entityCode || ''} assigned successfully`);
      onSuccess();
      onClose();
    } catch (err: any) {
      toast.error(err.response?.data?.message || 'Failed to assign entity');
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="Assign Legal Entity" size="md">
      <div className="p-4 space-y-4">
        {node && (
          <div className="p-3 bg-neutral-50 dark:bg-primary-950 rounded-lg">
            <p className="text-sm text-neutral-600 dark:text-neutral-300">Account:</p>
            <p className="font-medium">{node.name}</p>
            {node.accountNumber && <p className="text-xs text-neutral-500 dark:text-neutral-400 font-mono">{node.accountNumber}</p>}
          </div>
        )}
        
        <div>
          <label className="field-label block mb-1">
            Select Legal Entity
          </label>
          <select
            value={selectedEntityId}
            onChange={(e) => setSelectedEntityId(e.target.value)}
            className="w-full px-3 py-2 border border-neutral-300 dark:border-primary-700 rounded-lg"
          >
            <option value="">-- Select Entity --</option>
            {entities.filter(e => e.status === 'ACTIVE').map(entity => (
              <option key={entity.id} value={entity.id}>
                {entity.entityCode} - {entity.entityName}
                {entity.ihbEnabled && ' 🏦'}
              </option>
            ))}
          </select>
        </div>
        
        <div className="flex justify-end gap-3 pt-4 border-t">
          <Button variant="outline" onClick={onClose}>Cancel</Button>
          <Button onClick={handleSubmit} disabled={loading || !selectedEntityId}>
            {loading ? <Loader2 className="w-4 h-4 mr-1 animate-spin" /> : <Building2 className="w-4 h-4 mr-1" />}
            Assign Entity
          </Button>
        </div>
      </div>
    </Modal>
  );
};

// ============================================================================
// IHB CONFIG MODAL (ENHANCED)
// ============================================================================

// ============================================================================
// ENHANCED IHB CONFIG MODAL - With Interest Configuration Support
// ============================================================================
// 
// This component handles:
// 1. Enabling/disabling IHB for entities
// 2. For Treasury Centers: Selecting/Creating InterestConfiguration for rates
// 3. For Participants: Viewing Treasury rates
// 4. Sweep configuration
//
// Place this in TreasuryHierarchyPage.tsx, replacing the existing IhbConfigModal
// ============================================================================

interface IhbConfigModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: () => void;
  entityId: string | null;
  entities: LegalEntity[];
  currency: string;
  corporateId?: string;
}

const IhbConfigModal: React.FC<IhbConfigModalProps> = ({
  isOpen, onClose, onSuccess, entityId, entities, currency, corporateId,
}) => {
  const entity = entities.find(e => e.id === entityId);
  const isEnabling = entity && !entity.ihbEnabled;
  const treasuryCenter = entities.find(e => e.canLend === true && e.ihbEnabled === true);
  
  // Main config state
  const [config, setConfig] = useState({
    creditLimit: 1000000,
    canLend: false,
    canBorrow: true,
    targetCashBalance: 0,
    autoSweepEnabled: true,
    sweepFrequency: 'DAILY',
  });
  
  // Interest Configuration state (for Treasury Centers)
  const [interestConfigs, setInterestConfigs] = useState<InterestConfiguration[]>([]);
  const [selectedConfigId, setSelectedConfigId] = useState<string>('');
  const [showCreateConfig, setShowCreateConfig] = useState(false);
  const [newConfigForm, setNewConfigForm] = useState({
    configName: '',
    creditBaseRateType: 'EIBOR',
    creditBaseRate: '5.000',
    creditSpread: '-0.250',
    debitBaseRateType: 'EIBOR', 
    debitBaseRate: '5.000',
    debitSpread: '0.500',
    dayCountConvention: 'ACT/360',
    compoundingFrequency: 'DAILY',
    postingFrequency: 'MONTHLY',
  });
  
  const [loading, setLoading] = useState(false);
  const [loadingConfigs, setLoadingConfigs] = useState(false);
  const [treasuryRates, setTreasuryRates] = useState<TreasuryRates | null>(null);
  const [loadingRates, setLoadingRates] = useState(false);

  // Load interest configurations when modal opens for Treasury Center
  useEffect(() => {
    const loadInterestConfigs = async () => {
      if (isOpen && corporateId && config.canLend) {
        setLoadingConfigs(true);
        try {
          const response = await interestConfigurationApi.getActiveByCorporate(corporateId);
          const configs = response?.data || response || [];
          // Filter for INTERNAL configs targeting this entity or CORPORATE level
          const relevantConfigs = Array.isArray(configs) ? configs.filter((c: InterestConfiguration) => 
            c.configType === 'INTERNAL' && 
            (c.targetType === 'LEGAL_ENTITY' || c.targetType === 'CORPORATE') &&
            c.currencyCode === currency
          ) : [];
          setInterestConfigs(relevantConfigs);
          
          // Auto-select if entity already has a config
          if (entity?.ihbInterestConfigId) {
            setSelectedConfigId(entity.ihbInterestConfigId);
          } else if (relevantConfigs.length > 0) {
            // Auto-select first matching config
            const entityConfig = relevantConfigs.find((c: InterestConfiguration) => c.targetId === entityId);
            if (entityConfig) {
              setSelectedConfigId(entityConfig.id);
            }
          }
        } catch (err) {
          console.error('Failed to load interest configs:', err);
        } finally {
          setLoadingConfigs(false);
        }
      }
    };
    loadInterestConfigs();
  }, [isOpen, corporateId, config.canLend, currency, entityId, entity?.ihbInterestConfigId]);

  // Load treasury rates when modal opens for participant
  useEffect(() => {
    const loadTreasuryRates = async () => {
      if (isOpen && isEnabling && corporateId && !config.canLend) {
        setLoadingRates(true);
        try {
          const response = await ihbUnifiedApi.getTreasuryRates(corporateId);
          setTreasuryRates(response.data || null);
        } catch (err) {
          console.error('Failed to load treasury rates:', err);
        } finally {
          setLoadingRates(false);
        }
      }
    };
    loadTreasuryRates();
  }, [isOpen, isEnabling, corporateId, config.canLend]);

  // Reset form when modal opens
  useEffect(() => {
    if (isOpen && entity) {
      setConfig({
        creditLimit: entity.ihbCreditLimit || 1000000,
        canLend: entity.canLend || false,
        canBorrow: entity.canBorrow !== false,
        targetCashBalance: 0,
        autoSweepEnabled: true,
        sweepFrequency: 'DAILY',
      });
      setShowCreateConfig(false);
      setNewConfigForm({
        configName: `IHB Treasury Rate - ${entity.entityCode}`,
        creditBaseRateType: 'EIBOR',
        creditBaseRate: '5.000',
        creditSpread: '-0.250',
        debitBaseRateType: 'EIBOR',
        debitBaseRate: '5.000',
        debitSpread: '0.500',
        dayCountConvention: 'ACT/360',
        compoundingFrequency: 'DAILY',
        postingFrequency: 'MONTHLY',
      });
    }
  }, [isOpen, entity]);

  // Create new interest configuration
  const handleCreateConfig = async () => {
    if (!corporateId || !entityId) return;
    
    setLoading(true);
    try {
      const payload: Partial<InterestConfiguration> = {
        corporateId,
        configName: newConfigForm.configName,
        configType: 'INTERNAL' as const,
        targetType: 'LEGAL_ENTITY' as const,
        targetId: entityId,
        currencyCode: currency,
        creditBaseRateType: newConfigForm.creditBaseRateType,
        creditBaseRate: parseFloat(newConfigForm.creditBaseRate),
        creditSpread: parseFloat(newConfigForm.creditSpread),
        debitBaseRateType: newConfigForm.debitBaseRateType,
        debitBaseRate: parseFloat(newConfigForm.debitBaseRate),
        debitSpread: parseFloat(newConfigForm.debitSpread),
        dayCountConvention: newConfigForm.dayCountConvention,
        compoundingFrequency: newConfigForm.compoundingFrequency,
        calculationFrequency: newConfigForm.compoundingFrequency, // Same as compounding by default
        postingFrequency: newConfigForm.postingFrequency,
        effectiveFrom: new Date().toISOString().split('T')[0],
        status: 'ACTIVE' as const,
      };
      
      const response = await interestConfigurationApi.create(payload);
      const newConfig = response?.data || response;
      
      toast.success('Interest configuration created');
      setInterestConfigs(prev => [...prev, newConfig]);
      setSelectedConfigId(newConfig.id);
      setShowCreateConfig(false);
    } catch (err: any) {
      toast.error(err.response?.data?.message || 'Failed to create interest configuration');
    } finally {
      setLoading(false);
    }
  };

  // Submit IHB configuration
  const handleSubmit = async () => {
    if (!entityId) return;
    setLoading(true);
    try {
      if (isEnabling) {
        await ihbUnifiedApi.enableIhb(entityId, {
          creditLimit: config.creditLimit,
          ihbCurrency: currency,
          canLend: config.canLend,
          canBorrow: config.canBorrow,
          targetCashBalance: config.targetCashBalance,
          autoSweepEnabled: config.autoSweepEnabled,
          sweepFrequency: config.sweepFrequency,
          ihbInterestConfigId: config.canLend ? selectedConfigId : undefined,
        });
        toast.success('IHB enabled successfully', { icon: '🏦' });
      } else {
        await ihbUnifiedApi.updateSettings(entityId, {
          ...config,
          ihbInterestConfigId: config.canLend ? selectedConfigId : undefined,
        });
        toast.success('IHB settings updated');
      }
      onSuccess();
      onClose();
    } catch (err: any) {
      toast.error(err.response?.data?.message || 'Failed to update IHB settings');
    } finally {
      setLoading(false);
    }
  };

  const handleDisableIhb = async () => {
    if (!entityId) return;
    if (!confirm('Are you sure you want to disable IHB for this entity? This will remove sweep rules.')) return;
    setLoading(true);
    try {
      await ihbUnifiedApi.disableIhb(entityId);
      toast.success('IHB disabled');
      onSuccess();
      onClose();
    } catch (err: any) {
      toast.error(err.response?.data?.message || 'Failed to disable IHB');
    } finally {
      setLoading(false);
    }
  };

  // Helper to get selected config details
  const selectedConfig = interestConfigs.find(c => c.id === selectedConfigId);
  const willBeTreasuryCenter = config.canLend;

  // Calculate effective rates from selected config
  const getEffectiveRate = (baseRate: number, spread: number) => (baseRate + spread).toFixed(3);

  return (
    <Modal isOpen={isOpen} onClose={onClose} title={isEnabling ? "Enable In-House Banking" : "IHB Settings"} size="lg">
      <div className="p-4 space-y-4 max-h-[80vh] overflow-y-auto">
        {/* Entity Info */}
        {entity && (
          <div className="p-3 bg-info-50 dark:bg-info-500/10 rounded-lg border border-info-200 dark:border-info-500/30">
            <p className="text-sm text-info-600 dark:text-info-300">Entity:</p>
            <p className="font-medium text-info-900">{entity.entityCode} - {entity.entityName}</p>
            {entity.ihbEnabled && (
              <Badge variant="success" size="sm" className="mt-1">
                <CheckCircle2 className="w-3 h-3 mr-1" />
                IHB Active
              </Badge>
            )}
          </div>
        )}

        {/* ================================================================ */}
        {/* TREASURY CENTER: Interest Configuration Selection               */}
        {/* ================================================================ */}
        {willBeTreasuryCenter && (
          <div className="p-4 bg-gradient-to-r from-amber-50 to-warning-50 rounded-lg border border-amber-200 dark:border-amber-500/30 dark:from-amber-500/15 dark:to-warning-500/15">
            <div className="flex items-center justify-between mb-3">
              <div className="flex items-center gap-2">
                <Crown className="w-5 h-5 text-amber-600 dark:text-amber-300" />
                <span className="font-semibold text-amber-900">Treasury Center Rate Configuration</span>
              </div>
              {!showCreateConfig && (
                <Button variant="ghost" size="sm" onClick={() => setShowCreateConfig(true)}>
                  <Plus className="w-4 h-4 mr-1" /> New Config
                </Button>
              )}
            </div>
            
            {/* Select Existing Config */}
            {!showCreateConfig && (
              <>
                <div className="mb-3">
                  <label className="block text-sm font-medium text-amber-800 mb-1 dark:text-amber-300">
                    Interest Rate Configuration
                  </label>
                  {loadingConfigs ? (
                    <div className="flex items-center gap-2 p-2 bg-white dark:bg-primary-900 rounded border">
                      <Loader2 className="w-4 h-4 animate-spin" />
                      <span className="text-sm text-neutral-500 dark:text-neutral-400">Loading configurations...</span>
                    </div>
                  ) : (
                    <select
                      value={selectedConfigId}
                      onChange={(e) => setSelectedConfigId(e.target.value)}
                      className="w-full px-3 py-2 bg-white dark:bg-primary-900 border border-amber-200 dark:border-amber-500/30 rounded-lg text-sm"
                    >
                      <option value="">-- Select Rate Configuration --</option>
                      {interestConfigs.map(cfg => (
                        <option key={cfg.id} value={cfg.id}>
                          {cfg.configName} ({cfg.currencyCode}) - 
                          Lend: {cfg.effectiveDebitRate?.toFixed(2) || 'N/A'}% / 
                          Deposit: {cfg.effectiveCreditRate?.toFixed(2) || 'N/A'}%
                        </option>
                      ))}
                    </select>
                  )}
                  {interestConfigs.length === 0 && !loadingConfigs && (
                    <p className="text-xs text-amber-600 mt-1 dark:text-amber-300">
                      No rate configurations found. Create one to define lending/deposit rates.
                    </p>
                  )}
                </div>

                {/* Show Selected Config Details */}
                {selectedConfig && (
                  <div className="grid grid-cols-2 gap-3 mt-3">
                    <div className="p-3 bg-white dark:bg-primary-900 rounded-lg border border-error-100 dark:border-error-500/30">
                      <div className="flex items-center gap-2 mb-2">
                        <TrendingDown className="w-4 h-4 text-error-500" />
                        <span className="text-sm font-medium text-error-700 dark:text-error-300">Lending Rate</span>
                      </div>
                      <p className="stat-value-error">
                        {selectedConfig.effectiveDebitRate?.toFixed(2) || 
                          getEffectiveRate(selectedConfig.debitBaseRate || 5, selectedConfig.debitSpread || 0)}%
                      </p>
                      <p className="text-xs text-neutral-500 dark:text-neutral-400 mt-1">
                        {selectedConfig.debitBaseRateType || 'EIBOR'} {selectedConfig.debitBaseRate || 5}% 
                        {selectedConfig.debitSpread && selectedConfig.debitSpread >= 0 ? ' + ' : ' '}
                        {selectedConfig.debitSpread || 0}%
                      </p>
                    </div>
                    <div className="p-3 bg-white dark:bg-primary-900 rounded-lg border border-success-100 dark:border-success-500/30">
                      <div className="flex items-center gap-2 mb-2">
                        <TrendingUp className="w-4 h-4 text-success-500" />
                        <span className="text-sm font-medium text-success-700 dark:text-success-300">Deposit Rate</span>
                      </div>
                      <p className="stat-value-success">
                        {selectedConfig.effectiveCreditRate?.toFixed(2) || 
                          getEffectiveRate(selectedConfig.creditBaseRate || 5, selectedConfig.creditSpread || 0)}%
                      </p>
                      <p className="text-xs text-neutral-500 dark:text-neutral-400 mt-1">
                        {selectedConfig.creditBaseRateType || 'EIBOR'} {selectedConfig.creditBaseRate || 5}% 
                        {selectedConfig.creditSpread && selectedConfig.creditSpread >= 0 ? ' + ' : ' '}
                        {selectedConfig.creditSpread || 0}%
                      </p>
                    </div>
                  </div>
                )}

                {/* No config selected warning */}
                {!selectedConfigId && !loadingConfigs && (
                  <div className="p-3 bg-warning-50 rounded border border-warning-200 mt-3 dark:bg-warning-500/10 dark:border-warning-500/30">
                    <div className="flex items-center gap-2 text-warning-700 dark:text-warning-300">
                      <AlertCircle className="w-4 h-4" />
                      <span className="text-sm">No rate configuration selected. Default rates (5% EIBOR) will be used.</span>
                    </div>
                  </div>
                )}
              </>
            )}

            {/* Create New Config Form */}
            {showCreateConfig && (
              <div className="space-y-4 p-4 bg-white dark:bg-primary-900 rounded-lg border border-amber-200 dark:border-amber-500/30">
                <div className="flex items-center justify-between">
                  <h4 className="font-medium text-neutral-900 dark:text-neutral-50">Create New Rate Configuration</h4>
                  <Button variant="ghost" size="sm" onClick={() => setShowCreateConfig(false)}>
                    <X className="w-4 h-4" />
                  </Button>
                </div>
                
                <div>
                  <label className="field-label block mb-1">Configuration Name</label>
                  <input
                    type="text"
                    value={newConfigForm.configName}
                    onChange={(e) => setNewConfigForm(prev => ({ ...prev, configName: e.target.value }))}
                    className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm dark:border-primary-700"
                    placeholder="e.g., IHB Treasury Rate - ACME-TC"
                  />
                </div>

                {/* Lending (Debit) Rate */}
                <div className="p-3 bg-error-50 dark:bg-error-500/10 rounded-lg">
                  <h5 className="text-sm font-medium text-error-700 mb-2 flex items-center gap-1 dark:text-error-300">
                    <TrendingDown className="w-4 h-4" /> Lending Rate (what borrowers pay)
                  </h5>
                  <div className="grid grid-cols-3 gap-3">
                    <div>
                      <label className="block text-xs mb-1">Base Rate Type</label>
                      <select
                        value={newConfigForm.debitBaseRateType}
                        onChange={(e) => setNewConfigForm(prev => ({ ...prev, debitBaseRateType: e.target.value }))}
                        className="w-full px-2 py-1.5 border rounded text-sm"
                      >
                        <option value="EIBOR">EIBOR</option>
                        <option value="SOFR">SOFR</option>
                        <option value="EURIBOR">EURIBOR</option>
                        <option value="SONIA">SONIA</option>
                        <option value="FIXED">FIXED</option>
                      </select>
                    </div>
                    <div>
                      <label className="block text-xs mb-1">Base Rate (%)</label>
                      <input
                        type="number"
                        step="0.001"
                        value={newConfigForm.debitBaseRate}
                        onChange={(e) => setNewConfigForm(prev => ({ ...prev, debitBaseRate: e.target.value }))}
                        className="w-full px-2 py-1.5 border rounded text-sm"
                      />
                    </div>
                    <div>
                      <label className="block text-xs mb-1">Spread (%)</label>
                      <input
                        type="number"
                        step="0.001"
                        value={newConfigForm.debitSpread}
                        onChange={(e) => setNewConfigForm(prev => ({ ...prev, debitSpread: e.target.value }))}
                        className="w-full px-2 py-1.5 border rounded text-sm"
                      />
                    </div>
                  </div>
                  <p className="text-xs text-error-600 mt-2 dark:text-error-300">
                    Effective Rate: {getEffectiveRate(parseFloat(newConfigForm.debitBaseRate), parseFloat(newConfigForm.debitSpread))}%
                  </p>
                </div>

                {/* Deposit (Credit) Rate */}
                <div className="p-3 bg-success-50 dark:bg-success-500/10 rounded-lg">
                  <h5 className="text-sm font-medium text-success-700 mb-2 flex items-center gap-1 dark:text-success-300">
                    <TrendingUp className="w-4 h-4" /> Deposit Rate (what depositors earn)
                  </h5>
                  <div className="grid grid-cols-3 gap-3">
                    <div>
                      <label className="block text-xs mb-1">Base Rate Type</label>
                      <select
                        value={newConfigForm.creditBaseRateType}
                        onChange={(e) => setNewConfigForm(prev => ({ ...prev, creditBaseRateType: e.target.value }))}
                        className="w-full px-2 py-1.5 border rounded text-sm"
                      >
                        <option value="EIBOR">EIBOR</option>
                        <option value="SOFR">SOFR</option>
                        <option value="EURIBOR">EURIBOR</option>
                        <option value="SONIA">SONIA</option>
                        <option value="FIXED">FIXED</option>
                      </select>
                    </div>
                    <div>
                      <label className="block text-xs mb-1">Base Rate (%)</label>
                      <input
                        type="number"
                        step="0.001"
                        value={newConfigForm.creditBaseRate}
                        onChange={(e) => setNewConfigForm(prev => ({ ...prev, creditBaseRate: e.target.value }))}
                        className="w-full px-2 py-1.5 border rounded text-sm"
                      />
                    </div>
                    <div>
                      <label className="block text-xs mb-1">Spread (%)</label>
                      <input
                        type="number"
                        step="0.001"
                        value={newConfigForm.creditSpread}
                        onChange={(e) => setNewConfigForm(prev => ({ ...prev, creditSpread: e.target.value }))}
                        className="w-full px-2 py-1.5 border rounded text-sm"
                      />
                    </div>
                  </div>
                  <p className="text-xs text-success-600 mt-2 dark:text-success-300">
                    Effective Rate: {getEffectiveRate(parseFloat(newConfigForm.creditBaseRate), parseFloat(newConfigForm.creditSpread))}%
                  </p>
                </div>

                {/* Calculation Parameters */}
                <div className="grid grid-cols-3 gap-3">
                  <div>
                    <label className="block text-xs mb-1">Day Count</label>
                    <select
                      value={newConfigForm.dayCountConvention}
                      onChange={(e) => setNewConfigForm(prev => ({ ...prev, dayCountConvention: e.target.value }))}
                      className="w-full px-2 py-1.5 border rounded text-sm"
                    >
                      <option value="ACT/360">ACT/360</option>
                      <option value="ACT/365">ACT/365</option>
                      <option value="30/360">30/360</option>
                    </select>
                  </div>
                  <div>
                    <label className="block text-xs mb-1">Compounding</label>
                    <select
                      value={newConfigForm.compoundingFrequency}
                      onChange={(e) => setNewConfigForm(prev => ({ ...prev, compoundingFrequency: e.target.value }))}
                      className="w-full px-2 py-1.5 border rounded text-sm"
                    >
                      <option value="DAILY">Daily</option>
                      <option value="MONTHLY">Monthly</option>
                      <option value="QUARTERLY">Quarterly</option>
                    </select>
                  </div>
                  <div>
                    <label className="block text-xs mb-1">Posting</label>
                    <select
                      value={newConfigForm.postingFrequency}
                      onChange={(e) => setNewConfigForm(prev => ({ ...prev, postingFrequency: e.target.value }))}
                      className="w-full px-2 py-1.5 border rounded text-sm"
                    >
                      <option value="DAILY">Daily</option>
                      <option value="MONTHLY">Monthly</option>
                      <option value="QUARTERLY">Quarterly</option>
                    </select>
                  </div>
                </div>

                <div className="flex justify-end gap-2 pt-2">
                  <Button variant="outline" size="sm" onClick={() => setShowCreateConfig(false)}>
                    Cancel
                  </Button>
                  <Button size="sm" onClick={handleCreateConfig} disabled={loading || !newConfigForm.configName}>
                    {loading ? <Loader2 className="w-4 h-4 animate-spin mr-1" /> : <Plus className="w-4 h-4 mr-1" />}
                    Create Configuration
                  </Button>
                </div>
              </div>
            )}
          </div>
        )}

        {/* ================================================================ */}
        {/* PARTICIPANT: Treasury Rates Preview                             */}
        {/* ================================================================ */}
        {isEnabling && !willBeTreasuryCenter && treasuryRates && (
          <div className="p-4 bg-gradient-to-r from-amber-50 to-warning-50 rounded-lg border border-amber-200 dark:border-amber-500/30 dark:from-amber-500/15 dark:to-warning-500/15">
            <div className="flex items-center gap-2 mb-3">
              <Crown className="w-5 h-5 text-amber-600 dark:text-amber-300" />
              <span className="font-semibold text-amber-900">Treasury Center Offered Rates</span>
              {treasuryRates.hasInterestConfig && (
                <Badge variant="success" size="sm">
                  <Settings className="w-3 h-3 mr-1" /> Configured
                </Badge>
              )}
            </div>
            <p className="text-xs text-amber-700 mb-3 dark:text-amber-300">
              {treasuryRates.treasuryCenterCode} - {treasuryRates.treasuryCenterName}
            </p>
            
            <div className="grid grid-cols-2 gap-4">
              {/* Borrowing Rates */}
              <div className="p-3 bg-white dark:bg-primary-900 rounded-lg border border-error-100 dark:border-error-500/30">
                <div className="flex items-center gap-2 mb-2">
                  <TrendingDown className="w-4 h-4 text-error-500" />
                  <span className="text-sm font-medium text-error-700 dark:text-error-300">Borrowing Rate</span>
                </div>
                <p className="stat-value-error">{treasuryRates.indicativeLendingRate?.toFixed(2)}%</p>
                <p className="text-xs text-neutral-500 dark:text-neutral-400 mt-1">
                  {treasuryRates.lendingBaseRateType} {treasuryRates.lendingBaseRate}% + {treasuryRates.treasuryLendingSpread}% spread
                </p>
                <p className="text-xs text-neutral-400 mt-1 dark:text-neutral-500">
                  Min: {formatCurrency(treasuryRates.minLoanAmount, treasuryRates.ihbCurrency)}
                </p>
              </div>
              
              {/* Deposit Rates */}
              <div className="p-3 bg-white dark:bg-primary-900 rounded-lg border border-success-100 dark:border-success-500/30">
                <div className="flex items-center gap-2 mb-2">
                  <TrendingUp className="w-4 h-4 text-success-500" />
                  <span className="text-sm font-medium text-success-700 dark:text-success-300">Deposit Rate</span>
                </div>
                <p className="stat-value-success">{treasuryRates.indicativeDepositRate?.toFixed(2)}%</p>
                <p className="text-xs text-neutral-500 dark:text-neutral-400 mt-1">
                  {treasuryRates.depositBaseRateType} {treasuryRates.depositBaseRate}% {treasuryRates.treasuryDepositSpread}% spread
                </p>
                <p className="text-xs text-neutral-400 mt-1 dark:text-neutral-500">
                  Min: {formatCurrency(treasuryRates.minDepositAmount, treasuryRates.ihbCurrency)}
                </p>
              </div>
            </div>
            
            <div className="mt-3 pt-3 border-t border-amber-200 text-xs text-amber-700 dark:border-amber-500/30 dark:text-amber-300">
              <span className="font-medium">Terms:</span> {treasuryRates.dayCountConvention} • {treasuryRates.compoundingFrequency} compounding • {treasuryRates.settlementFrequency} settlement
            </div>
          </div>
        )}

        {/* Loading rates indicator */}
        {isEnabling && !willBeTreasuryCenter && loadingRates && (
          <div className="p-4 bg-neutral-50 dark:bg-primary-950 rounded-lg border animate-pulse">
            <div className="flex items-center gap-2">
              <Loader2 className="w-4 h-4 animate-spin text-neutral-400 dark:text-neutral-500" />
              <span className="text-sm text-neutral-500 dark:text-neutral-400">Loading Treasury rates...</span>
            </div>
          </div>
        )}

        {/* No treasury center warning */}
        {isEnabling && !willBeTreasuryCenter && !treasuryRates && !loadingRates && (
          <div className="p-3 bg-warning-50 rounded-lg border border-warning-200 dark:bg-warning-500/10 dark:border-warning-500/30">
            <div className="flex items-center gap-2 text-warning-800 dark:text-warning-300">
              <AlertCircle className="w-4 h-4" />
              <span className="text-sm font-medium">No Treasury Center Found</span>
            </div>
            <p className="text-xs text-warning-700 mt-1 dark:text-warning-300">
              Enable "Can Lend" to make this entity the Treasury Center, or ensure another entity is set up as Treasury Center first.
            </p>
          </div>
        )}
        
        {/* Show Treasury Center link for non-treasury entities */}
        {!isEnabling && entity && !entity.canLend && treasuryCenter && (
          <div className="p-3 bg-amber-50 dark:bg-amber-500/10 rounded-lg border border-amber-200 dark:border-amber-500/30">
            <div className="flex items-center gap-2 text-amber-800 dark:text-amber-300">
              <Crown className="w-4 h-4" />
              <span className="text-sm font-medium">Treasury Center</span>
            </div>
            <p className="text-sm text-amber-700 mt-1 dark:text-amber-300">
              {treasuryCenter.entityCode} - {treasuryCenter.entityName}
            </p>
            <p className="text-xs text-amber-600 mt-1 dark:text-amber-300">
              Sweeps are directed to/from this treasury center
            </p>
          </div>
        )}

        {/* IHB Status Summary when already enabled */}
        {!isEnabling && entity?.ihbEnabled && (
          <div className="grid grid-cols-2 gap-3 p-3 bg-neutral-50 dark:bg-primary-950 rounded-lg">
            <div>
              <p className="text-xs text-neutral-500 dark:text-neutral-400">Credit Limit</p>
              <p className="font-medium">{formatCurrency(entity.ihbCreditLimit || 0, currency)}</p>
            </div>
            <div>
              <p className="text-xs text-neutral-500 dark:text-neutral-400">Role</p>
              <p className="font-medium">
                {entity.canLend ? (
                  <span className="text-amber-600 flex items-center gap-1 dark:text-amber-300">
                    <Crown className="w-3 h-3" /> Treasury Center
                  </span>
                ) : (
                  <span className="text-info-600 dark:text-info-300">Participant</span>
                )}
              </p>
            </div>
          </div>
        )}
        
        {/* Role Selection */}
        <div className="flex items-center gap-6">
          <label className="flex items-center gap-2 cursor-pointer">
            <input
              type="checkbox"
              checked={config.canLend}
              onChange={(e) => setConfig(prev => ({ ...prev, canLend: e.target.checked }))}
              className="rounded border-neutral-300 text-info-600 dark:border-primary-700 dark:text-info-300"
              disabled={!isEnabling && entity?.canLend}
            />
            <span className="text-sm">Can Lend</span>
            {config.canLend && (
              <Badge variant="warning" size="sm" className="flex items-center gap-1">
                <Crown className="w-3 h-3" />
                Treasury Center
              </Badge>
            )}
          </label>
          <label className="flex items-center gap-2 cursor-pointer">
            <input
              type="checkbox"
              checked={config.canBorrow}
              onChange={(e) => setConfig(prev => ({ ...prev, canBorrow: e.target.checked }))}
              className="rounded border-neutral-300 text-info-600 dark:border-primary-700 dark:text-info-300"
            />
            <span className="text-sm">Can Borrow</span>
          </label>
        </div>
        
        {/* Credit Limit */}
        <div>
          <label className="field-label block mb-1">Credit Limit ({currency})</label>
          <input
            type="number"
            value={config.creditLimit}
            onChange={(e) => setConfig(prev => ({ ...prev, creditLimit: Number(e.target.value) }))}
            className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm dark:border-primary-700"
          />
        </div>
        
        {/* Target Balance */}
        {(isEnabling || !entity?.canLend) && (
          <div>
            <label className="field-label block mb-1">Target Cash Balance</label>
            <input
              type="number"
              value={config.targetCashBalance}
              onChange={(e) => setConfig(prev => ({ ...prev, targetCashBalance: Number(e.target.value) }))}
              className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm dark:border-primary-700"
            />
            <p className="text-xs text-neutral-500 dark:text-neutral-400 mt-1">0 = sweep all surplus to/from Treasury Center</p>
          </div>
        )}
        
        {/* Sweep Toggle */}
        {isEnabling && (
          <div className="flex items-center justify-between p-3 bg-neutral-50 dark:bg-primary-950 rounded-lg">
            <div className="flex items-center gap-2">
              <RefreshCw className="w-4 h-4 text-info-600 dark:text-info-300" />
              <span className="text-sm">Enable EOD Auto-Sweep</span>
            </div>
            <label className="relative inline-flex items-center cursor-pointer">
              <input
                type="checkbox"
                checked={config.autoSweepEnabled}
                onChange={(e) => setConfig(prev => ({ ...prev, autoSweepEnabled: e.target.checked }))}
                className="sr-only peer"
              />
              <div className="w-9 h-5 bg-neutral-200 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:bg-info-600 after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-4 after:w-4 after:transition-all dark:bg-primary-800"></div>
            </label>
          </div>
        )}
        
        {/* Actions */}
        <div className="flex justify-between pt-4 border-t">
          {!isEnabling && (
            <Button variant="outline" onClick={handleDisableIhb} disabled={loading} className="text-error-600 dark:text-error-300 border-error-200 dark:border-error-500/30 hover:bg-error-50 dark:hover:bg-error-500/10">
              <XCircle className="w-4 h-4 mr-1" />
              Disable IHB
            </Button>
          )}
          <div className={`flex gap-3 ${isEnabling ? 'w-full justify-end' : ''}`}>
            <Button variant="outline" onClick={onClose}>Cancel</Button>
            <Button onClick={handleSubmit} disabled={loading}>
              {loading ? <Loader2 className="w-4 h-4 mr-1 animate-spin" /> : <PiggyBank className="w-4 h-4 mr-1" />}
              {isEnabling ? 'Enable IHB' : 'Save Settings'}
            </Button>
          </div>
        </div>
      </div>
    </Modal>
  );
};

// ============================================================================
// MAIN PAGE COMPONENT
// ============================================================================

const TreasuryHierarchyPage: React.FC = () => {
  // Corporate & Program selection state
  const [corporates, setCorporates] = useState<Corporate[]>([]);
  const [programs, setPrograms] = useState<ProgramOption[]>([]);
  const [selectedCorporateId, setSelectedCorporateId] = useState('');
  const [selectedProgramId, setSelectedProgramId] = useState('');
  const [loadingCorporates, setLoadingCorporates] = useState(true);
  const [loadingPrograms, setLoadingPrograms] = useState(false);
  const [legalEntities, setLegalEntities] = useState<LegalEntity[]>([]);

  // Hierarchy state
  const [hierarchy, setHierarchy] = useState<ExtendedHierarchyNode | null>(null);
  const [summary, setSummary] = useState<BalanceSummary | null>(null);
  const [physicalAccount, setPhysicalAccount] = useState<BalancePhysicalAccount | null>(null);
  const [shadowAccounts, setShadowAccounts] = useState<ShadowAccount[]>([]);
  const [selectedNodeDetail, setSelectedNodeDetail] = useState<BalanceNodeDetail | null>(null);
  const [currencyMirrors, setCurrencyMirrors] = useState<CurrencyMirror[]>([]);
  const [loading, setLoading] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());
  const [selectedNode, setSelectedNode] = useState<ExtendedHierarchyNode | null>(null);
  const [reportingCurrency, _setReportingCurrency] = useState('AED');
  const [showInterest, setShowInterest] = useState(true);
  const [showSystemVas, setShowSystemVas] = useState(true);  // Toggle for system VAs (Currency Mirrors, Settlement, Exception, Shadow)
  const [showLevelConfig, setShowLevelConfig] = useState(false);
  const [levelConfigs, setLevelConfigs] = useState<HierarchyLevelConfig[]>([]);
  const [showCreateViban, setShowCreateViban] = useState(false);
  const [showCreateSettlementVa, setShowCreateSettlementVa] = useState(false);
  const [settlementVaCurrency, setSettlementVaCurrency] = useState('AED');
  const [corporateId, setCorporateId] = useState<string | undefined>(undefined);

  // NEW: Add Node Modal States (ENHANCED)
  const [showAddNodeTypeSelector, setShowAddNodeTypeSelector] = useState(false);
  const [showCreateAggregation, setShowCreateAggregation] = useState(false);
  const [showCreateTransactionVa, setShowCreateTransactionVa] = useState(false);
  const [showCreateIhbCurrentAccount, setShowCreateIhbCurrentAccount] = useState(false);
  const [addNodeParentNode, setAddNodeParentNode] = useState<ExtendedHierarchyNode | null>(null);

  // Hierarchy initialization state
  const [hierarchyStatus, setHierarchyStatus] = useState<HierarchyStatusResponse | null>(null);
  const [checkingStatus, setCheckingStatus] = useState(false);
  const [showInitModal, setShowInitModal] = useState(false);
  // ENHANCED: Entity and IHB action states
  const [showAssignEntity, setShowAssignEntity] = useState(false);
  const [showIhbConfig, setShowIhbConfig] = useState(false);
  const [selectedNodeForAction, setSelectedNodeForAction] = useState<ExtendedHierarchyNode | null>(null);
  const [selectedEntityForAction, setSelectedEntityForAction] = useState<string | null>(null);
  const [selectedProgram, setSelectedProgram] = useState<{ programName: string; programCode: string; currencyCode: string; programType?: string } | null>(null);
  const [selectedCorporate, setSelectedCorporate] = useState<{ legalName: string; tradeName?: string } | null>(null);
  // ENHANCED: Treasury rates for IHB display
  const [treasuryRates, setTreasuryRates] = useState<TreasuryRates | null>(null);

  const collectAllIds = useCallback((node: ExtendedHierarchyNode): string[] => {
    const ids = [node.id];
    if (node.children) (node.children as ExtendedHierarchyNode[]).forEach(child => ids.push(...collectAllIds(child)));
    return ids;
  }, []);

  // Load corporates on mount
  useEffect(() => {
    const loadCorporates = async () => {
      try {
        setLoadingCorporates(true);
        const response = await corporatesApi.getAll();
        const corps = response.data || [];
        setCorporates(corps);
        // Auto-select first corporate
        if (corps.length > 0) {
          setSelectedCorporateId(corps[0].id);
          setSelectedCorporate({ legalName: corps[0].legalName, tradeName: corps[0].tradeName });
        }
      } catch (err) {
        console.error('Failed to load corporates:', err);
      } finally {
        setLoadingCorporates(false);
      }
    };
    loadCorporates();
  }, []);

  // NEW: Load legal entities when corporate changes
  useEffect(() => {
    const loadEntities = async () => {
      if (!selectedCorporateId) { setLegalEntities([]); return; }
      try {
        const response = await legalEntityApi.getByCorporate(selectedCorporateId);
        setLegalEntities(response.data || []);
      } catch (err) {
        console.error('Failed to load legal entities:', err);
        setLegalEntities([]);
      }
    };
    loadEntities();
  }, [selectedCorporateId]);

  // ENHANCED: Load treasury rates when corporate changes
  useEffect(() => {
    const loadTreasuryRates = async () => {
      if (!selectedCorporateId) { setTreasuryRates(null); return; }
      try {
        const response = await ihbUnifiedApi.getTreasuryRates(selectedCorporateId);
        setTreasuryRates(response.data || null);
      } catch (err) {
        console.error('Failed to load treasury rates:', err);
        setTreasuryRates(null);
      }
    };
    loadTreasuryRates();
  }, [selectedCorporateId]);

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
        const response = await programsApi.getAll({ corporateId: selectedCorporateId });
        
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

        // Auto-select first active program
        const activePrograms = programList.filter(p => p.status === 'ACTIVE');
        if (activePrograms.length > 0) {
          setSelectedProgramId(activePrograms[0].id);
          setSelectedProgram({
            programName: activePrograms[0].programName,
            programCode: activePrograms[0].programCode,
            currencyCode: activePrograms[0].currencyCode,
            programType: activePrograms[0].programType,
          });
        } else {
          setSelectedProgramId('');
          setSelectedProgram(null);
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

  // Handle corporate change
  const handleCorporateChange = (corpId: string) => {
    setSelectedCorporateId(corpId);
    setSelectedProgramId('');
    setHierarchy(null);
    setHierarchyStatus(null);
    
    const corp = corporates.find(c => c.id === corpId);
    if (corp) {
      setSelectedCorporate({ legalName: corp.legalName, tradeName: corp.tradeName });
      setCorporateId(corp.id);
    } else {
      setSelectedCorporate(null);
      setCorporateId(undefined);
    }
  };

  // Handle program change
  const handleProgramChange = (programId: string) => {
    setSelectedProgramId(programId);
    setHierarchy(null);
    setHierarchyStatus(null);
    
    const program = programs.find(p => p.id === programId);
    if (program) {
      setSelectedProgram({
        programName: program.programName,
        programCode: program.programCode,
        currencyCode: program.currencyCode,
        programType: program.programType,
      });
      setCheckingStatus(true);
    } else {
      setSelectedProgram(null);
    }
  };

  // Check hierarchy status when program changes
  useEffect(() => {
    const checkHierarchyStatus = async () => {
      if (!selectedProgramId) {
        setCheckingStatus(false);
        setHierarchyStatus(null);
        return;
      }

      const uuidRegex = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
      if (!uuidRegex.test(selectedProgramId)) {
        setCheckingStatus(false);
        return;
      }

      setCheckingStatus(true);
      try {
        const result = await hierarchyVaApi.getStatus(selectedProgramId);
        let statusData: HierarchyStatusResponse | null = null;
        if (result.success !== undefined && result.data) {
          statusData = result.data;
        } else {
          // Handle legacy format where result itself contains the status data
          const legacyResult = result as unknown as HierarchyStatusResponse;
          if (legacyResult.programId || legacyResult.initialized !== undefined) {
            statusData = legacyResult;
          }
        }

        if (statusData) {
          setHierarchyStatus(statusData);
          if (statusData.corporateId) {
            setCorporateId(statusData.corporateId);
          }
          if (!statusData.initialized) {
            setShowInitModal(true);
          }
        }
      } catch (err) {
        console.error('Failed to check hierarchy status:', err);
        setHierarchyStatus(null);
      } finally {
        setCheckingStatus(false);
      }
    };

    checkHierarchyStatus();
  }, [selectedProgramId]);

  // Load hierarchy data
  const loadData = useCallback(async () => {
    try {
      setLoading(true); 
      setError(null);
      
      const [hierarchyRes, summaryRes, physicalRes] = await Promise.all([
        balanceStructureApi.getHierarchy(selectedCorporateId || undefined, selectedProgramId || undefined, reportingCurrency),
        balanceStructureApi.getSummary(selectedCorporateId || undefined, selectedProgramId || undefined, reportingCurrency),
        balanceStructureApi.getPhysicalAccount(selectedCorporateId || undefined),
      ]);
      
      if (hierarchyRes.success && hierarchyRes.data) {
        // ENHANCED: Transform flat owningEntity fields to nested object
        const transformNode = (node: ExtendedHierarchyNode): ExtendedHierarchyNode => {
          const transformed = { ...node };
          
          // Build owningEntity object from flat fields if available
          if (node.owningEntityId && node.owningEntityCode) {
            transformed.owningEntity = {
              id: node.owningEntityId,
              entityCode: node.owningEntityCode,
              entityName: node.owningEntityName || node.owningEntityCode, // Fallback to code if name missing
              entityType: (node.owningEntityType as any) || 'SUBSIDIARY',
            };
          } else if (node.owningEntityId) {
            // Have ID but no code - still show something
            transformed.owningEntity = {
              id: node.owningEntityId,
              entityCode: node.owningEntityCode || 'ENTITY',
              entityName: node.owningEntityName || 'Unknown Entity',
              entityType: (node.owningEntityType as any) || 'SUBSIDIARY',
            };
          }
          
          // Recursively transform children
          if (node.children && node.children.length > 0) {
            transformed.children = node.children.map(child => transformNode(child as ExtendedHierarchyNode));
          }
          
          return transformed;
        };
        
        const transformedHierarchy = transformNode(hierarchyRes.data as ExtendedHierarchyNode);
        setHierarchy(transformedHierarchy);
        const allIds = collectAllIds(transformedHierarchy);
        setExpandedIds(new Set(allIds.slice(0, 10)));
      }
      
      if (summaryRes.success) setSummary(summaryRes.data);
      if (physicalRes.success) setPhysicalAccount(physicalRes.data);
      
      // Load currency mirrors - prefer program-based API when programId is available
      try {
        let mirrorsRes;
        if (selectedProgramId) {
          mirrorsRes = await currencyMirrorApi.getByProgram(selectedProgramId);
        } else if (corporateId) {
          mirrorsRes = await currencyMirrorApi.getByCorporate(corporateId);
        }
        if (mirrorsRes?.success && mirrorsRes.data) {
          setCurrencyMirrors(mirrorsRes.data);
        }
      } catch (err) {
        console.error('Failed to load currency mirrors:', err);
      }

      // Load shadow accounts - prefer program-based API when programId is available
      try {
        let shadowRes;
        if (selectedProgramId) {
          shadowRes = await shadowAccountApi.getByProgram(selectedProgramId);
        } else if (selectedCorporateId) {
          shadowRes = await shadowAccountApi.getByCorporate(selectedCorporateId);
        }
        if (shadowRes?.success && shadowRes.data) {
          setShadowAccounts(shadowRes.data);
        } else {
          setShadowAccounts([]);
        }
      } catch (err) {
        console.error('Failed to load shadow accounts:', err);
        setShadowAccounts([]);
      }
    } catch (err) {
      console.error('Failed to load:', err);
      setError('Failed to load balance structure data');
    }
    finally { setLoading(false); }
  }, [reportingCurrency, collectAllIds, corporateId, selectedCorporateId, selectedProgramId]);

  // ENHANCED: Refresh all data including legal entities (used after IHB changes)
  const refreshAll = useCallback(async () => {
    // Reload legal entities to get updated IHB status
    if (selectedCorporateId) {
      try {
        const response = await legalEntityApi.getByCorporate(selectedCorporateId);
        setLegalEntities(response.data || []);
      } catch (err) {
        console.error('Failed to reload legal entities:', err);
      }
    }
    // Also reload hierarchy data
    await loadData();
  }, [selectedCorporateId, loadData]);

  const loadNodeDetail = async (nodeId: string) => {
    try {
      setDetailLoading(true);
      const res = await balanceStructureApi.getNodeDetail(nodeId, reportingCurrency);
      if (res.success) setSelectedNodeDetail(res.data);
    } catch (err) {
      console.error('Failed to load node detail:', err);
    }
    finally { setDetailLoading(false); }
  };

  // Load hierarchy level configurations
  const loadLevelConfigs = useCallback(async () => {
    if (!selectedProgramId) return;
    try {
      const res = await hierarchyVaApi.getLevelConfigs(selectedProgramId);
      if (res.success && res.data) {
        setLevelConfigs(res.data);
      }
    } catch (err) {
      console.error('Failed to load level configs:', err);
    }
  }, [selectedProgramId]);

  // Save hierarchy level configurations
  const handleSaveLevelConfigs = async (levels: HierarchyLevelConfig[]) => {
    if (!selectedProgramId) throw new Error('No program selected');
    const res = await hierarchyVaApi.saveLevelConfigs(selectedProgramId, levels);
    if (!res.success) {
      throw new Error(res.message || 'Failed to save configuration');
    }
    setLevelConfigs(levels);
    toast.success('Hierarchy level configuration saved successfully');
  };

  // Load level configs when opening the modal
  useEffect(() => {
    if (showLevelConfig && selectedProgramId) {
      loadLevelConfigs();
    }
  }, [showLevelConfig, selectedProgramId, loadLevelConfigs]);

  const handleInitializationSuccess = (response: InitializationResponse) => {
    setShowInitModal(false);

    // Show success notification with toast
    const exceptionCount = response.exceptionVaIds?.length || 0;
    const currencyCount = response.exceptionCurrencies?.length || 0;
    let message = 'Hierarchy initialized successfully!';
    if (exceptionCount > 0 || currencyCount > 0) {
      const parts = [];
      if (exceptionCount > 0) parts.push(`${exceptionCount} Exception VA(s)`);
      if (currencyCount > 0) parts.push(`for ${currencyCount} currency(ies)`);
      message += ` Created ${parts.join(' ')}.`;
    }
    toast.success(message, { duration: 5000, icon: '🎉' });
    
    // Refresh hierarchy status
    if (selectedProgramId) {
      hierarchyVaApi.getStatus(selectedProgramId).then((result) => {
        let statusData: HierarchyStatusResponse | null = null;
        if (result.success !== undefined && result.data) {
          statusData = result.data;
        } else if ((result as any).programId || (result as any).initialized !== undefined) {
          statusData = result as any;
        }
        if (statusData) {
          setHierarchyStatus(statusData);
        }
      }).catch(err => {
        console.error('Failed to refresh status:', err);
      });
    }
    loadData();
  };

  const handleRecalculateMirror = async (mirrorId: string) => {
    try {
      await currencyMirrorApi.recalculate(mirrorId);
      toast.success('Currency mirror recalculated');
      await loadData();
    } catch (err) {
      console.error('Failed to recalculate mirror:', err);
      toast.error('Failed to recalculate mirror');
    }
  };

  const handleRecalculateAllMirrors = async () => {
    if (!corporateId) return;
    try {
      setRefreshing(true);
      await currencyMirrorApi.recalculateAll(corporateId);
      toast.success('All currency mirrors recalculated');
      await loadData();
    } catch (err) {
      console.error('Failed to recalculate all mirrors:', err);
      toast.error('Failed to recalculate mirrors');
    } finally {
      setRefreshing(false);
    }
  };

  // NEW: Handle Add Child - Opens type selector (ENHANCED)
  const handleAddChild = (parentNode: ExtendedHierarchyNode) => {
    setAddNodeParentNode(parentNode);
    setShowAddNodeTypeSelector(true);
  };

  // NEW: Handle node type selection (ENHANCED)
  const handleNodeTypeSelect = (type: NodeCreationType) => {
    setShowAddNodeTypeSelector(false);
    if (type === 'aggregation') setShowCreateAggregation(true);
    else if (type === 'transaction') setShowCreateTransactionVa(true);
    else if (type === 'ihb-current-account') setShowCreateIhbCurrentAccount(true);
  };

  // NEW: Handle creation success (ENHANCED)
  const handleCreationSuccess = () => {
    loadData();
    setAddNodeParentNode(null);
  };

  const handleSelectNode = (node: ExtendedHierarchyNode) => { setSelectedNode(node); loadNodeDetail(node.id); };
  
  const handleRefresh = async () => { 
    setRefreshing(true); 
    await balanceStructureApi.refresh(); 
    await loadData(); 
    toast.success('Hierarchy refreshed');
    setRefreshing(false); 
  };
  
  const handleExport = async () => { 
    const res = await balanceStructureApi.exportReport('XLSX', reportingCurrency); 
    if (res.success && res.data) {
      toast.success('Export started');
      console.log('Export URL:', res.data); 
    }
  };
  
  const handleCreateSettlementVa = (parentId: string) => { 
    const parent = hierarchy ? findNodeById(hierarchy, parentId) : null;
    setAddNodeParentNode(parent);
    setShowCreateSettlementVa(true); 
  };
  
  const handleViewExceptions = (nodeId: string) => { console.log('Navigate to exceptions:', nodeId); };

  // ENHANCED: Entity and IHB action handlers
  const handleAssignEntity = (node: ExtendedHierarchyNode) => {
    setSelectedNodeForAction(node);
    setShowAssignEntity(true);
  };

  const handleConfigureIhb = (entityId: string) => {
    setSelectedEntityForAction(entityId);
    setShowIhbConfig(true);
  };

  const findNodeById = (node: ExtendedHierarchyNode, id: string): ExtendedHierarchyNode | null => {
    if (node.id === id) return node;
    if (node.children) {
      for (const child of node.children as ExtendedHierarchyNode[]) {
        const found = findNodeById(child, id);
        if (found) return found;
      }
    }
    return null;
  };

  const toggleExpand = (id: string) => { const n = new Set(expandedIds); if (n.has(id)) n.delete(id); else n.add(id); setExpandedIds(n); };
  const expandAll = () => { if (hierarchy) setExpandedIds(new Set(collectAllIds(hierarchy))); };
  const collapseAll = () => { setExpandedIds(new Set([hierarchy?.id || 'company'])); };

  useEffect(() => {
    if (hierarchyStatus?.initialized || (selectedCorporateId && selectedProgramId)) {
      loadData();
    }
  }, [loadData, hierarchyStatus?.initialized, selectedCorporateId, selectedProgramId]);

  // Push toolbar actions (Recalc / Configure / Export / Refresh) into the
  // Aperture header. Renders only when the main hierarchy view is showing —
  // empty/loading/error branches don't have meaningful toolbar actions.
  const isMainHierarchyView =
    !!selectedCorporateId &&
    !!selectedProgramId &&
    !checkingStatus &&
    !!hierarchyStatus?.initialized &&
    !loading &&
    !error;

  usePageHeaderActions(
    () => isMainHierarchyView ? (
      <>
        {currencyMirrors.length > 0 && (
          <Button variant="outline" size="sm" onClick={handleRecalculateAllMirrors} disabled={refreshing}>
            <Coins className="w-4 h-4 mr-1" />Recalc Mirrors
          </Button>
        )}
        <Button variant="outline" size="sm" onClick={() => setShowLevelConfig(true)}>
          <Settings className="w-4 h-4 mr-1" />Configure
        </Button>
        <Button variant="outline" size="sm" onClick={handleExport}>
          <Download className="w-4 h-4 mr-1" />Export
        </Button>
        <Button variant="outline" size="sm" onClick={handleRefresh} disabled={refreshing}>
          {refreshing ? <Loader2 className="w-4 h-4 mr-1 animate-spin" /> : <RefreshCw className="w-4 h-4 mr-1" />}Refresh
        </Button>
      </>
    ) : null,
    [isMainHierarchyView, currencyMirrors.length, refreshing]
  );

  // Loading state
  if (loadingCorporates) {
    return (
      <div className="flex items-center justify-center h-96">
        <div className="text-center">
          <Loader2 className="w-8 h-8 animate-spin text-primary-600 mx-auto mb-4 dark:text-primary-200" />
          <p className="text-neutral-500 dark:text-neutral-400">Loading corporates...</p>
        </div>
      </div>
    );
  }

  // No corporate selected state
  if (!selectedCorporateId) {
    return (
      <div className="space-y-6">
        <div>
          {/* Title removed — Aperture Layout header carries it via the page-id map */}
          <p className="text-base text-neutral-500 mt-1 dark:text-neutral-400">Select a corporate to view hierarchy</p>
        </div>
        <CorporateProgramFilterBar
          corporates={corporates}
          programs={programs}
          selectedCorporateId={selectedCorporateId}
          selectedProgramId={selectedProgramId}
          onCorporateChange={handleCorporateChange}
          onProgramChange={handleProgramChange}
          loading={loadingPrograms}
        />
        <Card className="p-8 text-center">
          <div className="flex flex-col items-center">
            <div className="w-20 h-20 bg-neutral-100 dark:bg-primary-800 rounded-full flex items-center justify-center mb-4">
              <Building className="w-10 h-10 text-neutral-400 dark:text-neutral-500" />
            </div>
            <h2 className="section-title mb-2">Select a Corporate</h2>
            <p className="text-neutral-500 max-w-md dark:text-neutral-400">
              Choose a corporate from the dropdown above to view its programs and hierarchy structure.
            </p>
          </div>
        </Card>
      </div>
    );
  }

  // No program selected state
  if (!selectedProgramId) {
    return (
      <div className="space-y-6">
        <div>
          {/* Title removed — Aperture Layout header carries it via the page-id map */}
          <p className="text-base text-neutral-500 mt-1 dark:text-neutral-400">Select a program to view hierarchy</p>
        </div>
        <CorporateProgramFilterBar
          corporates={corporates}
          programs={programs}
          selectedCorporateId={selectedCorporateId}
          selectedProgramId={selectedProgramId}
          onCorporateChange={handleCorporateChange}
          onProgramChange={handleProgramChange}
          loading={loadingPrograms}
        />
        <Card className="p-8 text-center">
          <div className="flex flex-col items-center">
            <div className="w-20 h-20 bg-neutral-100 dark:bg-primary-800 rounded-full flex items-center justify-center mb-4">
              <Layers className="w-10 h-10 text-neutral-400 dark:text-neutral-500" />
            </div>
            <h2 className="section-title mb-2">Select a Program</h2>
            <p className="text-neutral-500 mb-6 max-w-md dark:text-neutral-400">
              Choose a program from the dropdown above to view its hierarchy structure and manage virtual accounts.
            </p>
            {programs.filter(p => p.status === 'ACTIVE').length === 0 && (
              <div className="mt-4">
                <p className="text-amber-600 text-sm mb-2 dark:text-amber-300">No active programs found for this corporate.</p>
                <Button variant="outline" onClick={() => window.location.href = '/programs'}>
                  <Plus className="w-4 h-4 mr-1" />Create Program
                </Button>
              </div>
            )}
          </div>
        </Card>
      </div>
    );
  }

  // Checking status state
  if (checkingStatus) {
    return (
      <div className="space-y-6">
        <div>
          {/* Title removed — Aperture Layout header carries it via the page-id map */}
          <p className="text-base text-neutral-500 mt-1 dark:text-neutral-400">{selectedProgram?.programName}</p>
        </div>
        <CorporateProgramFilterBar
          corporates={corporates}
          programs={programs}
          selectedCorporateId={selectedCorporateId}
          selectedProgramId={selectedProgramId}
          onCorporateChange={handleCorporateChange}
          onProgramChange={handleProgramChange}
          loading={true}
        />
        <div className="flex items-center justify-center h-64">
          <div className="text-center">
            <Loader2 className="w-8 h-8 animate-spin text-primary-600 mx-auto mb-4 dark:text-primary-200" />
            <p className="text-neutral-500 dark:text-neutral-400">Checking hierarchy status...</p>
          </div>
        </div>
      </div>
    );
  }

  // Not initialized state
  if (hierarchyStatus && !hierarchyStatus.initialized) {
    return (
      <div className="space-y-6">
        <div className="flex items-center justify-between">
          <div>
            {/* Title removed — Aperture Layout header carries it via the page-id map */}
            <p className="text-base text-neutral-500 mt-1 dark:text-neutral-400">{selectedProgram?.programName}</p>
          </div>
        </div>
        <CorporateProgramFilterBar
          corporates={corporates}
          programs={programs}
          selectedCorporateId={selectedCorporateId}
          selectedProgramId={selectedProgramId}
          onCorporateChange={handleCorporateChange}
          onProgramChange={handleProgramChange}
          loading={loadingPrograms}
        />
        <Card className="p-8 text-center">
          <div className="flex flex-col items-center">
            <div className="w-20 h-20 bg-neutral-100 dark:bg-primary-800 rounded-full flex items-center justify-center mb-4">
              <Globe className="w-10 h-10 text-neutral-400 dark:text-neutral-500" />
            </div>
            <h2 className="section-title mb-2">Hierarchy Not Initialized</h2>
            <p className="text-neutral-500 mb-6 max-w-md dark:text-neutral-400">
              This program doesn't have a hierarchy structure yet. Initialize it to start
              creating aggregations and managing your virtual account structure.
            </p>
            <Button onClick={() => setShowInitModal(true)} className="flex items-center gap-2">
              <Globe className="w-4 h-4" />Initialize Hierarchy
            </Button>
            <div className="mt-8 p-4 bg-info-50 dark:bg-info-500/10 border border-info-200 dark:border-info-500/30 rounded-lg max-w-lg text-left">
              <h3 className="text-sm font-semibold text-info-900 mb-2">What happens during initialization?</h3>
              <ul className="text-sm text-info-700 space-y-1 dark:text-info-300">
                <li>• A ROOT hierarchy node is created at Level 1</li>
                <li>• A ROOT virtual account is created for balance consolidation</li>
                <li>• Optional Currency Mirrors for multi-currency aggregation</li>
                <li>• Optional Exception VAs for unmatched transactions</li>
              </ul>
            </div>
          </div>
        </Card>
        {selectedProgramId && (
          <HierarchyInitializationModal
            isOpen={showInitModal}
            onClose={() => setShowInitModal(false)}
            onSuccess={handleInitializationSuccess}
            programId={selectedProgramId}
            programName={selectedProgram?.programName || 'Program'}
            programCode={selectedProgram?.programCode || 'PRG'}
            corporateName={selectedCorporate?.legalName || selectedCorporate?.tradeName || 'Corporate'}
            defaultCurrency={selectedProgram?.currencyCode || 'AED'}
          />
        )}
      </div>
    );
  }

  // Loading hierarchy data
  if (loading) {
    return (
      <div className="space-y-6">
        <div>
          {/* Title removed — Aperture Layout header carries it via the page-id map */}
          <p className="text-base text-neutral-500 mt-1 dark:text-neutral-400">{selectedProgram?.programName}</p>
        </div>
        <CorporateProgramFilterBar
          corporates={corporates}
          programs={programs}
          selectedCorporateId={selectedCorporateId}
          selectedProgramId={selectedProgramId}
          onCorporateChange={handleCorporateChange}
          onProgramChange={handleProgramChange}
        />
        <div className="flex items-center justify-center h-64">
          <Loader2 className="w-8 h-8 animate-spin text-primary-600 dark:text-primary-200" />
        </div>
      </div>
    );
  }

  // Error state
  if (error) {
    return (
      <div className="space-y-6">
        <div>
          {/* Title removed — Aperture Layout header carries it via the page-id map */}
        </div>
        <CorporateProgramFilterBar
          corporates={corporates}
          programs={programs}
          selectedCorporateId={selectedCorporateId}
          selectedProgramId={selectedProgramId}
          onCorporateChange={handleCorporateChange}
          onProgramChange={handleProgramChange}
        />
        <div className="flex flex-col items-center justify-center h-64 gap-4">
          <AlertCircle className="w-12 h-12 text-error-500" />
          <p className="text-error-600 dark:text-error-300">{error}</p>
          <Button onClick={loadData}>Retry</Button>
        </div>
      </div>
    );
  }

  const displaySummary = summary || { 
    consolidatedBalance: hierarchy?.consolidatedBalance || 0, 
    netPosition: hierarchy?.netPosition || 0, 
    totalIntercompanyReceivable: hierarchy?.intercompanyReceivable || 0, 
    poolRate: 0, 
    monthlyInterestAllocation: hierarchy?.interestAllocation || 0 
  };
  
  const displayPhysical = physicalAccount || { 
    bankName: '-', 
    accountNumber: '-', 
    accountName: 'No Physical Account Linked', 
    balance: 0, 
    currency: selectedProgram?.currencyCode || 'AED' 
  };

  return (
    <Page>
      {/* In-body identity (Phase 10) — aligns with the rest of Aperture's
          conformed pages. Action buttons (Recalc / Configure / Export /
          Refresh) registered in the Aperture Layout header via
          usePageHeaderActions above. The Initialize CTA shows only in the
          not-initialized early-return branch, which has its own page-body UI. */}
      <PageHeader
        title="Balance Hierarchy"
        description="Aggregate balances flow up the parent–child VA tree. Drill into any node to see the contributing children, FX-converted in your reporting currency."
      />

      {/* Corporate/Program Filter Bar */}
      <CorporateProgramFilterBar
        corporates={corporates}
        programs={programs}
        selectedCorporateId={selectedCorporateId}
        selectedProgramId={selectedProgramId}
        onCorporateChange={handleCorporateChange}
        onProgramChange={handleProgramChange}
        loading={loadingPrograms}
      />

      {/* Info Banner — gradient kept for light-mode visual interest; dark-mode
          flips to a flat muted panel so the legend text reads cleanly against
          the navy backdrop (the original via-white created a bright sheen). */}
      <Card
        padding="sm"
        className="bg-gradient-to-r from-primary-50/50 via-white to-info-50/50 border-primary-200/60 dark:bg-none dark:bg-primary-900/50 dark:border-primary-800 animate-fade-in"
        style={{ animationDelay: '0.15s' }}
      >
        <div className="flex items-start gap-3">
          <StatusIconBadge tone="primary" icon={Layers} className="dark:bg-primary-700" />
          <div>
            <p className="text-sm font-semibold text-primary-900 dark:text-neutral-50">Group Balance Hierarchy</p>
            <p className="text-sm text-neutral-600 dark:text-neutral-300 mt-1">
              <strong className="text-info-700 dark:text-info-300">Currency Mirrors (M-Nodes)</strong> aggregate same-currency balances.
              <strong className="text-accent-700 dark:text-accent-300"> Settlement VAs</strong> collect fees.
              <strong className="text-warning-700 dark:text-warning-300"> Exception VAs</strong> hold unmatched transactions.
            </p>
          </div>
        </div>
      </Card>

      {/* Summary Stats */}
      <div className="grid grid-cols-1 md:grid-cols-5 gap-4">
        {[
          { label: 'Consolidated', value: formatCurrency(displaySummary.consolidatedBalance, reportingCurrency), icon: DollarSign, iconBg: 'bg-primary-100 dark:bg-primary-700', iconColor: 'text-primary-600 dark:text-primary-200', valueColor: 'text-primary-900 dark:text-neutral-50', delay: 0.2 },
          { label: 'Net Position', value: formatCurrency(displaySummary.netPosition, reportingCurrency), icon: TrendingUp, iconBg: 'bg-success-100 dark:bg-success-500/20', iconColor: 'text-success-600 dark:text-success-300', valueColor: 'text-success-600 dark:text-success-300', delay: 0.25 },
          { label: 'IC Positions', value: formatCurrency(displaySummary.totalIntercompanyReceivable, reportingCurrency), icon: ArrowLeftRight, iconBg: 'bg-info-100 dark:bg-info-500/20', iconColor: 'text-info-600 dark:text-info-300', valueColor: 'text-info-600 dark:text-info-300', delay: 0.3 },
          { label: 'Pool Rate', value: `${displaySummary.poolRate}%`, icon: Percent, iconBg: 'bg-accent-100 dark:bg-accent-500/20', iconColor: 'text-accent-600 dark:text-accent-300', valueColor: 'text-accent-600 dark:text-accent-300', delay: 0.35 },
          { label: 'Monthly Interest', value: `+${formatCurrency(displaySummary.monthlyInterestAllocation, reportingCurrency)}`, icon: Banknote, iconBg: 'bg-warning-100 dark:bg-warning-500/20', iconColor: 'text-warning-600 dark:text-warning-300', valueColor: 'text-warning-600 dark:text-warning-300', delay: 0.4 },
        ].map((stat) => (
          <Card key={stat.label} hover className="animate-fade-in" style={{ animationDelay: `${stat.delay}s` }}>
            <div className="p-4">
              <div className="flex items-start justify-between">
                <div className="flex-1">
                  <p className="label">{stat.label}</p>
                  <p className={cn('text-xl font-bold mt-1 tracking-tight', stat.valueColor)}>{stat.value}</p>
                </div>
                <div className={cn('w-10 h-10 rounded-xl flex items-center justify-center', stat.iconBg)}>
                  <stat.icon className={cn('w-5 h-5', stat.iconColor)} />
                </div>
              </div>
            </div>
          </Card>
        ))}
      </div>

      {/* Physical Account Banner. Note: the gradient stays navy in BOTH light
          and dark modes (intentional — this strip wants the heavy banking-vault
          tone). That means the balance figure needs `!text-white` to override
          `.stat-value-sm`'s default `text-primary-900` — otherwise it renders
          navy-on-navy and disappears in light mode. Same fix we applied to
          the Currency Mirrors hero value. */}
      <div className="bg-gradient-to-r from-primary-900 via-primary-800 to-primary-900 text-white rounded-xl p-5 animate-fade-in" style={{ animationDelay: '0.45s' }}>
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-4">
            <div className="w-12 h-12 rounded-xl bg-white/10 backdrop-blur-sm flex items-center justify-center border border-white/20">
              <Banknote className="w-6 h-6 text-white" />
            </div>
            <div>
              <p className="text-xs font-medium text-primary-200 uppercase tracking-wider">Real Bank Account (Physical)</p>
              <p className="text-lg font-semibold mt-1">{displayPhysical.accountName}</p>
              <p className="text-sm text-primary-300 font-mono mt-0.5">{displayPhysical.accountNumber}</p>
            </div>
          </div>
          <div className="text-right">
            <p className="text-xs font-medium text-primary-200 uppercase tracking-wider">{displayPhysical.bankName}</p>
            {/* Inverse variant — replaces the `stat-value-sm !text-white`
                override. See .stat-value-inverse in index.css. */}
            <p className="stat-value-inverse mt-1">{formatCurrency(displayPhysical.balance, displayPhysical.currency)}</p>
          </div>
        </div>
      </div>

      {/* Shadow Accounts Banner */}
      {shadowAccounts.length > 0 && (
        <div className="bg-gradient-to-r from-amber-700 via-amber-600 to-amber-700 text-white rounded-xl p-5 animate-fade-in" style={{ animationDelay: '0.5s' }}>
          <div className="flex items-center justify-between mb-4">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-white/10 backdrop-blur-sm flex items-center justify-center border border-white/20">
                <Layers className="w-5 h-5 text-white" />
              </div>
              <div>
                <p className="text-xs font-medium text-amber-200 uppercase tracking-wider">Shadow Accounts (PHYSICAL_MIRROR)</p>
                <p className="text-sm text-amber-100 mt-0.5">{shadowAccounts.length} account{shadowAccounts.length > 1 ? 's' : ''} mirroring bank balances</p>
              </div>
            </div>
            <div className="text-right">
              <p className="text-xs font-medium text-amber-200 uppercase tracking-wider">Total Mirrored Balance</p>
              <p className="stat-value-sm mt-1">
                {formatCurrency(
                  shadowAccounts.reduce((sum, sa) => sum + (sa.bankBalance || 0), 0),
                  shadowAccounts[0]?.currencyCode || 'AED'
                )}
              </p>
            </div>
          </div>
          {/* Shadow Account Details Grid */}
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3 mt-3">
            {shadowAccounts.map((shadow) => (
              <div key={shadow.id} className="bg-white/10 backdrop-blur-sm rounded-lg p-3 border border-white/20">
                <div className="flex items-center justify-between">
                  <div>
                    <p className="text-xs text-amber-200 font-mono">{shadow.vaNumber}</p>
                    <p className="text-sm font-medium text-white mt-0.5">{shadow.vaName}</p>
                    {shadow.physicalAccountNumber && (
                      <p className="text-xs text-amber-300 mt-1">↔ {shadow.physicalAccountNumber}</p>
                    )}
                  </div>
                  <div className="text-right">
                    <p className="text-lg font-bold text-white">{formatCurrency(shadow.bankBalance || 0, shadow.currencyCode)}</p>
                    <p className="text-xs text-amber-200">{shadow.currencyCode}</p>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Main Content Grid */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Hierarchy Tree */}
        <div className="lg:col-span-2">
          <div className="bg-white dark:bg-primary-900 rounded-xl shadow-sm border border-neutral-100 dark:border-primary-800/60">
            <div className="flex items-center justify-between p-4 border-b border-neutral-200 dark:border-primary-800">
              <h2 className="section-title">Virtual Account Hierarchy</h2>
              <div className="flex items-center gap-2">
                <label className="flex items-center gap-2 text-sm text-neutral-600 dark:text-neutral-300">
                  <input type="checkbox" checked={showSystemVas} onChange={(e) => setShowSystemVas(e.target.checked)} className="rounded text-primary-600 dark:text-primary-200" />
                  System VAs
                </label>
                <label className="flex items-center gap-2 text-sm text-neutral-600 dark:text-neutral-300">
                  <input type="checkbox" checked={showInterest} onChange={(e) => setShowInterest(e.target.checked)} className="rounded text-primary-600 dark:text-primary-200" />
                  Interest
                </label>
                <Button variant="ghost" size="sm" onClick={expandAll}>Expand All</Button>
                <Button variant="ghost" size="sm" onClick={collapseAll}>Collapse</Button>
              </div>
            </div>
            <div className="p-4 space-y-1 max-h-[600px] overflow-y-auto">
              {hierarchy ? (
                <TreeNode
                  node={hierarchy}
                  expandedIds={expandedIds}
                  onToggle={toggleExpand}
                  selectedId={selectedNode?.id ?? null}
                  onSelect={handleSelectNode}
                  reportingCurrency={reportingCurrency}
                  showInterest={showInterest}
                  showSystemVas={showSystemVas}
                  onAddChild={handleAddChild}
                  onCreateSettlementVa={handleCreateSettlementVa}
                  onViewExceptions={handleViewExceptions}
                  onConfigureIhb={handleConfigureIhb}
                  corporateId={corporateId}
                  programId={selectedProgramId}
                />
              ) : (
                <div className="text-center py-12 text-neutral-500 dark:text-neutral-400">
                  <Globe className="w-12 h-12 mx-auto mb-3 text-neutral-300 dark:text-neutral-600" />
                  <p>No hierarchy data available</p>
                </div>
              )}
            </div>
          </div>
        </div>

        {/* Detail Panel */}
        <div>
          <div className="bg-white dark:bg-primary-900 rounded-xl shadow-sm border border-neutral-100 dark:border-primary-800/60 p-4">
            <h3 className="section-title mb-4">Entity Details</h3>
            <DetailPanel
              node={selectedNode}
              detail={selectedNodeDetail}
              loading={detailLoading}
              reportingCurrency={reportingCurrency}
              programId={selectedProgramId}
              onCreateViban={() => setShowCreateViban(true)}
              onViewExceptions={() => selectedNode && handleViewExceptions(selectedNode.id)}
              onRecalculateMirror={handleRecalculateMirror}
              onAssignEntity={handleAssignEntity}
              onConfigureIhb={handleConfigureIhb}
              treasuryRates={treasuryRates}
            />
          </div>
        </div>
      </div>

      {/* Legend */}
      <div className="bg-white dark:bg-primary-900 rounded-xl p-4 shadow-sm border border-neutral-100 dark:border-primary-800/60">
        <div className="flex flex-wrap items-center gap-6">
          <p className="text-sm font-medium text-neutral-600 dark:text-neutral-300">Legend:</p>
          <div className="flex items-center gap-2"><div className="p-1.5 rounded bg-primary-900"><Globe className="w-3 h-3 text-white" /></div><span className="text-xs text-neutral-600 dark:text-neutral-300">Group</span></div>
          <div className="flex items-center gap-2"><div className="p-1.5 rounded bg-indigo-100 dark:bg-indigo-500/20"><Building2 className="w-3 h-3 text-indigo-700 dark:text-indigo-300" /></div><span className="text-xs text-neutral-600 dark:text-neutral-300">Entity</span></div>
          <div className="flex items-center gap-2"><div className="p-1.5 rounded bg-success-50 dark:bg-success-500/10"><Wallet className="w-3 h-3 text-success-600 dark:text-success-300" /></div><span className="text-xs text-neutral-600 dark:text-neutral-300">Virtual Account</span></div>
          <div className="flex items-center gap-2"><div className="p-1.5 rounded bg-cyan-50 dark:bg-cyan-500/10 border-2 border-dashed border-cyan-400 dark:border-cyan-500/30"><Coins className="w-3 h-3 text-cyan-600 dark:text-cyan-300" /></div><span className="text-xs text-neutral-600 dark:text-neutral-300">Currency Mirror</span></div>
          <div className="flex items-center gap-2"><div className="p-1.5 rounded bg-purple-50 dark:bg-purple-500/10 border-2 border-purple-300 dark:border-purple-500/30"><Scale className="w-3 h-3 text-purple-600 dark:text-purple-300" /></div><span className="text-xs text-neutral-600 dark:text-neutral-300">Settlement VA</span></div>
          <div className="flex items-center gap-2"><div className="p-1.5 rounded bg-amber-50 dark:bg-amber-500/10 border-2 border-amber-300 dark:border-amber-500/30"><AlertTriangle className="w-3 h-3 text-amber-600 dark:text-amber-300" /></div><span className="text-xs text-neutral-600 dark:text-neutral-300">Exception VA</span></div>
          <div className="flex items-center gap-2"><div className="p-1.5 rounded bg-amber-100 border border-amber-300 dark:bg-amber-500/20"><Crown className="w-3 h-3 text-amber-600 dark:text-amber-300" /></div><span className="text-xs text-neutral-600 dark:text-neutral-300">Treasury Center</span></div>
          <div className="flex items-center gap-2"><div className="p-1.5 rounded bg-info-50 dark:bg-info-500/10 border border-info-300 dark:border-info-500/30"><PiggyBank className="w-3 h-3 text-info-600 dark:text-info-300" /></div><span className="text-xs text-neutral-600 dark:text-neutral-300">IHB Enabled</span></div>
        </div>
      </div>

      {/* Modals */}
      {/* Hierarchy Level Configuration Modal - Allows users to configure allowed values per level */}
      <HierarchyLevelConfigModal
        isOpen={showLevelConfig}
        onClose={() => setShowLevelConfig(false)}
        programId={selectedProgramId || ''}
        programName={selectedProgram?.programName}
        programType={selectedProgram?.programType}
        initialLevels={levelConfigs}
        onSave={handleSaveLevelConfigs}
        hasExistingNodes={hierarchyStatus?.initialized ?? false}
      />

      {/* Add Node Type Selector Modal */}
      <Modal isOpen={showAddNodeTypeSelector} onClose={() => setShowAddNodeTypeSelector(false)} title="Add Hierarchy Node" size="md">
        <AddNodeTypeSelector onSelect={handleNodeTypeSelect} onClose={() => setShowAddNodeTypeSelector(false)} />
      </Modal>

      {/* Create AGGREGATION Modal */}
      <CreateAggregationModal
        isOpen={showCreateAggregation}
        onClose={() => { setShowCreateAggregation(false); setAddNodeParentNode(null); }}
        onSuccess={handleCreationSuccess}
        programId={selectedProgramId}
        parentNode={addNodeParentNode}
        entities={legalEntities}
      />

      {/* Create Transaction VA Modal */}
      <CreateTransactionVaModal
        isOpen={showCreateTransactionVa}
        onClose={() => { setShowCreateTransactionVa(false); setAddNodeParentNode(null); }}
        onSuccess={handleCreationSuccess}
        parentNode={addNodeParentNode}
        entities={legalEntities}
        programId={selectedProgramId}
      />

      {/* Create IHB Current Account Modal */}
      <CreateIhbCurrentAccountModal
        isOpen={showCreateIhbCurrentAccount}
        onClose={() => { setShowCreateIhbCurrentAccount(false); setAddNodeParentNode(null); }}
        onSuccess={handleCreationSuccess}
        parentNode={addNodeParentNode}
        entities={legalEntities}
        programId={selectedProgramId}
        corporateId={selectedCorporateId}
        programCurrency={selectedProgram?.currencyCode}
        programName={selectedProgram?.programName}
      />

      <Modal isOpen={showCreateSettlementVa} onClose={() => setShowCreateSettlementVa(false)} title="Create Settlement VA" size="md">
        <div className="p-4 space-y-4">
          <div className="flex items-start gap-3 p-3 bg-purple-50 dark:bg-purple-500/10 rounded-lg border border-purple-200 dark:border-purple-500/30">
            <Scale className="w-5 h-5 text-purple-600 mt-0.5 dark:text-purple-300" />
            <div>
              <p className="text-sm font-medium text-purple-800 dark:text-purple-300">Settlement Virtual Account</p>
              <p className="text-xs text-purple-600 mt-0.5 dark:text-purple-300">Automatically receives fee postings from all VAs under this hierarchy level.</p>
            </div>
          </div>
          <div><label className="field-label block mb-1">VA Name</label><Input placeholder="e.g., GCC Settlement Account" /></div>
          <div><label className="field-label block mb-1">Currency</label>
            <CurrencyPicker value={settlementVaCurrency} onChange={setSettlementVaCurrency} />
          </div>
          <div className="flex justify-end gap-2 pt-4 border-t">
            <Button variant="ghost" onClick={() => setShowCreateSettlementVa(false)}>Cancel</Button>
            <Button onClick={() => { setShowCreateSettlementVa(false); }}>
              <Scale className="w-4 h-4 mr-1" />Create Settlement VA
            </Button>
          </div>
        </div>
      </Modal>

      <Modal isOpen={showCreateViban} onClose={() => setShowCreateViban(false)} title="Add VIBAN" size="md">
        <div className="p-4 space-y-4">
          <div><label className="field-label block mb-1">VIBAN Type</label>
            <select className="w-full border border-neutral-300 rounded-lg px-3 py-2 dark:border-primary-700">
              <option value="PRIMARY">Primary</option><option value="INVOICE">Invoice</option><option value="CUSTOMER">Customer</option>
            </select>
          </div>
          <div><label className="field-label block mb-1">Reference ID</label><Input placeholder="e.g., INV-2024-001" /></div>
          <div className="flex justify-end gap-2 pt-4 border-t">
            <Button variant="ghost" onClick={() => setShowCreateViban(false)}>Cancel</Button>
            <Button onClick={() => { setShowCreateViban(false); }}>Create VIBAN</Button>
          </div>
        </div>
      </Modal>

      {/* ENHANCED: Assign Entity Modal */}
      <AssignEntityModal
        isOpen={showAssignEntity}
        onClose={() => { setShowAssignEntity(false); setSelectedNodeForAction(null); }}
        onSuccess={refreshAll}
        node={selectedNodeForAction}
        entities={legalEntities}
      />

      {/* ENHANCED: IHB Config Modal */}
      <IhbConfigModal
        isOpen={showIhbConfig}
        onClose={() => { setShowIhbConfig(false); setSelectedEntityForAction(null); }}
        onSuccess={refreshAll}
        entityId={selectedEntityForAction}
        entities={legalEntities}
        currency={reportingCurrency}
        corporateId={selectedCorporateId || corporateId}
      />

      {selectedProgramId && (
        <HierarchyInitializationModal
          isOpen={showInitModal}
          onClose={() => setShowInitModal(false)}
          onSuccess={handleInitializationSuccess}
          programId={selectedProgramId}
          programName={selectedProgram?.programName || 'Program'}
          programCode={selectedProgram?.programCode || 'PRG'}
          corporateName={selectedCorporate?.legalName || selectedCorporate?.tradeName || 'Corporate'}
          defaultCurrency={selectedProgram?.currencyCode || 'AED'}
        />
      )}
    </Page>
  );
};

export default TreasuryHierarchyPage;