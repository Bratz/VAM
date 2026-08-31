import React, { useState, useEffect, useCallback } from 'react';
import toast from 'react-hot-toast';
import {
  Layers, RefreshCw, Plus, Search, MoreVertical,
  Building2, Banknote, Clock, AlertTriangle, CheckCircle2,
  ArrowUpRight, ArrowDownRight, Upload,
  Link2, Eye, Settings, Loader2, AlertCircle,
  Globe, Activity, Zap, Database, ChevronRight,
  GitBranch, Check, Info, Users, UserPlus, Unlink,
  Shield, FileCheck, User,
} from 'lucide-react';
import { Card, Button, Badge, Input , StatusIconBadge } from '../components/ui';
import { Modal } from '../components/ui/enhanced';
import { formatCurrency, cn } from '../utils';
import { physicalAccountsApi, corporatesApi, shadowAccountApi, balanceStructureApi } from '../services/api';
import { usePageHeaderActions } from '../context/PageHeaderContext';
import axios from 'axios';
import { Page } from '../components/layout/Page';
import { PageHeader } from '../components/layout/PageHeader';
import { ScopeSelector } from '../components/layout/ScopeSelector';

const API_BASE_URL = (import.meta as any).env?.VITE_API_BASE_URL || 'http://localhost:8053/api/v1';
const apiClient = axios.create({
  baseURL: API_BASE_URL,
  headers: { 'Content-Type': 'application/json' },
  timeout: 30000,
});

// ============================================================================
// TYPES
// ============================================================================

interface ShadowAccount {
  id: string;
  vaNumber: string;
  vaName: string;
  corporateId: string;
  corporateName?: string;
  linkedPhysicalAccountId: string;
  bankAccountNumber: string;
  bankIban?: string;
  bankSwift?: string;
  bankName?: string;
  currencyCode: string;
  bankBalance: number;
  bankAvailableBalance: number;
  bankBalanceAt?: string;
  balanceDataSource: 'CORE_BANKING' | 'SWIFT_MT940' | 'SWIFT_MT942' | 'OPEN_BANKING' | 'MANUAL';
  parentAccountId?: string;
  parentVaId?: string;
  parentVaNumber?: string;
  hierarchyLevel?: number;
  hierarchyPathVa?: string;
  status: string;
  lastSyncStatus?: 'SUCCESS' | 'FAILED' | 'PENDING';
  lastSyncError?: string;
  createdAt: string;
  updatedAt?: string;
  // Entity attachment fields
  owningEntityId?: string;
  owningEntityName?: string;
  primaryOwner?: {
    entityId: string;
    entityName: string;
    entityCode: string;
  };
}

interface PhysicalAccount {
  id: string;
  accountNumber: string;
  iban?: string;
  accountName: string;
  bankName?: string;
  swiftCode?: string;
  currencyCode: string;
  currentBalance: number;
  availableBalance: number;
  status: string;
  shadowVaId?: string;
}

interface Corporate {
  id: string;
  legalName: string;
  corporateId: string;
}

interface SyncResult {
  shadowVaId: string;
  vaNumber: string;
  previousBalance: number;
  newBalance: number;
  delta: number;
  syncedAt: string;
  source: string;
}

// Hierarchy Node for AGGREGATION picker
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
  data?: {
    id: string;
    vaNumber: string;
    vaName: string;
  };
  shadowVa?: {
    id: string;
    vaNumber: string;
    vaName: string;
  };
  exceptionVaCreated?: boolean;
  currencyMirrorsCreated?: string[];
  message?: string;
}

// Legal Entity for AttachToEntityModal
interface LegalEntity {
  id: string;
  entityCode: string;
  entityName: string;
  shortName?: string;
  entityType: 'HOLDING' | 'SUBSIDIARY' | 'BRANCH' | 'REPRESENTATIVE' | 'JOINT_VENTURE' | 'ASSOCIATE' | 'SPV' | 'TREASURY_CENTER';
  functionalCurrency: string;
  countryCode?: string;
  status: string;
  hierarchyLevel?: number;
  parentEntityId?: string;
  isTreasuryCenter?: boolean;
  isBankCustomer?: boolean;
}

// Account Attachment for VA-Entity relationships
interface AccountAttachment {
  id: string;
  virtualAccountId: string;
  legalEntityId: string;
  relationshipType: 'OWNER' | 'BENEFICIARY' | 'AUTHORIZED' | 'GUARANTOR' | 'COLLATERAL';
  isPrimary: boolean;
  description?: string;
  effectiveFrom: string;
  effectiveTo?: string;
  isCurrentlyValid: boolean;
  status: 'PENDING_APPROVAL' | 'ACTIVE' | 'SUSPENDED' | 'TERMINATED';
  maxTransactionAmount?: number;
  dailyLimit?: number;
  entityName?: string;
  entityCode?: string;
  vaNumber?: string;
}

// ============================================================================
// CONFIG
// ============================================================================

const DATA_SOURCE_CONFIG: Record<string, { label: string; icon: React.FC<any>; color: string; bgColor: string }> = {
  CORE_BANKING: { label: 'Core Banking', icon: Database, color: 'text-info-600 dark:text-info-300', bgColor: 'bg-info-50 dark:bg-info-500/10' },
  SWIFT_MT940: { label: 'SWIFT MT940', icon: Globe, color: 'text-cat-2', bgColor: 'bg-cat-2-soft dark:bg-cat-2/15' },
  SWIFT_MT942: { label: 'SWIFT MT942', icon: Activity, color: 'text-cat-1', bgColor: 'bg-cat-1-soft dark:bg-cat-1/15' },
  OPEN_BANKING: { label: 'Open Banking', icon: Zap, color: 'text-success-600 dark:text-success-300', bgColor: 'bg-success-50 dark:bg-success-500/10' },
  MANUAL: { label: 'Manual', icon: Settings, color: 'text-neutral-600 dark:text-neutral-300', bgColor: 'bg-neutral-100 dark:bg-primary-800' },
};

const RELATIONSHIP_TYPE_CONFIG: Record<string, { label: string; icon: React.FC<any>; color: string; bgColor: string; description: string }> = {
  OWNER: { label: 'Owner', icon: User, color: 'text-info-600 dark:text-info-300', bgColor: 'bg-info-50 dark:bg-info-500/10', description: 'Primary owner of the account' },
  BENEFICIARY: { label: 'Beneficiary', icon: Users, color: 'text-success-600 dark:text-success-300', bgColor: 'bg-success-50 dark:bg-success-500/10', description: 'Beneficiary with read access' },
  AUTHORIZED: { label: 'Authorized', icon: Shield, color: 'text-cat-2', bgColor: 'bg-cat-2-soft dark:bg-cat-2/15', description: 'Authorized to transact with limits' },
  GUARANTOR: { label: 'Guarantor', icon: FileCheck, color: 'text-warning-600 dark:text-warning-300', bgColor: 'bg-warning-50 dark:bg-warning-500/10', description: 'Guarantor for credit facilities' },
  COLLATERAL: { label: 'Collateral', icon: Banknote, color: 'text-error-600 dark:text-error-300', bgColor: 'bg-error-50 dark:bg-error-500/10', description: 'Collateral pledge for facilities' },
};

// ============================================================================
// HELPER: Ensure Array
// ============================================================================
const ensureArray = <T,>(data: any): T[] => {
  if (Array.isArray(data)) return data;
  if (data?.data && Array.isArray(data.data)) return data.data;
  if (data?.content && Array.isArray(data.content)) return data.content;
  return [];
};

// ============================================================================
// API FUNCTIONS
// ============================================================================

const hierarchyApi = {
  getHierarchyNodes: async (corporateId: string): Promise<HierarchyNode[]> => {
    const response = await apiClient.get('/treasury/balance-structure', { 
      params: { corporateId } 
    });
    
    const flattenNodes = (node: any, result: HierarchyNode[] = []): HierarchyNode[] => {
      if (node.accountCategory === 'ROOT' || node.accountCategory === 'AGGREGATION') {
        result.push({
          id: node.id,
          vaNumber: node.vaNumber || node.accountNumber,
          vaName: node.vaName || node.name,
          accountCategory: node.accountCategory,
          currencyCode: node.currencyCode,
          hierarchyLevel: node.hierarchyLevel || node.level || 0,
          aggregatedBalance: node.aggregatedBalance || node.consolidatedBalance,
        });
      }
      if (node.children) {
        node.children.forEach((child: any) => flattenNodes(child, result));
      }
      return result;
    };
    
    const rootNode = response.data?.data || response.data;
    if (rootNode) {
      return flattenNodes(rootNode);
    }
    return [];
  },
  
  getAggregationNodes: async (corporateId: string): Promise<HierarchyNode[]> => {
    const response = await apiClient.get('/virtual-accounts', { 
      params: { 
        corporateId,
        accountCategory: 'ROOT,AGGREGATION',
        size: 100
      } 
    });
    const data = response.data?.data?.content || response.data?.data || response.data?.content || [];
    return Array.isArray(data) ? data.map((item: any) => ({
      id: item.id,
      vaNumber: item.vaNumber,
      vaName: item.vaName,
      accountCategory: item.accountCategory,
      currencyCode: item.currencyCode,
      hierarchyLevel: item.hierarchyLevel || 0,
      aggregatedBalance: item.aggregatedBalance,
    })) : [];
  },
};

const legalEntityApi = {
  getByCorporate: async (corporateId: string): Promise<LegalEntity[]> => {
    // Use the correct endpoint: /legal-entities/corporate/{corporateId}/active
    const response = await apiClient.get(`/legal-entities/corporate/${corporateId}/active`);
    const data = response.data?.data || response.data || [];
    return ensureArray<LegalEntity>(data);
  },
};

const accountAttachmentApi = {
  getByVirtualAccount: async (vaId: string): Promise<AccountAttachment[]> => {
    const response = await apiClient.get(`/account-attachments/va/${vaId}/active`);
    return ensureArray<AccountAttachment>(response.data?.data || response.data);
  },
  
  create: async (request: {
    virtualAccountId: string;
    legalEntityId: string;
    relationshipType: string;
    isPrimary?: boolean;
    description?: string;
    effectiveFrom?: string;
    effectiveTo?: string;
    maxTransactionAmount?: number;
    dailyLimit?: number;
  }): Promise<AccountAttachment> => {
    const response = await apiClient.post('/account-attachments', request);
    return response.data?.data || response.data;
  },
  
  terminate: async (attachmentId: string): Promise<AccountAttachment> => {
    const response = await apiClient.put(`/account-attachments/${attachmentId}/terminate`);
    return response.data?.data || response.data;
  },
};

// ============================================================================
// HELPER FUNCTIONS
// ============================================================================

const getCategoryIcon = (category: string) => {
  switch (category) {
    case 'ROOT': return <Globe className="w-4 h-4 text-cat-2" />;
    case 'AGGREGATION': return <Layers className="w-4 h-4 text-cat-1" />;
    default: return <Building2 className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />;
  }
};

const getCategoryColor = (category: string) => {
  switch (category) {
    case 'ROOT': return 'bg-cat-2-soft dark:bg-cat-2/15 border-cat-2/20 dark:border-cat-2/30';
    case 'AGGREGATION': return 'bg-cat-1-soft dark:bg-cat-1/15 border-cat-1/20 dark:border-cat-1/30';
    default: return 'bg-neutral-50 dark:bg-primary-950 border-neutral-200 dark:border-primary-800';
  }
};

const getEntityTypeIcon = (type: string) => {
  switch (type) {
    case 'HOLDING': return <Building2 className="w-4 h-4 text-cat-2" />;
    case 'TREASURY_CENTER': return <Banknote className="w-4 h-4 text-warning-600 dark:text-warning-300" />;
    case 'SUBSIDIARY': return <Building2 className="w-4 h-4 text-info-600 dark:text-info-300" />;
    case 'BRANCH': return <GitBranch className="w-4 h-4 text-success-600 dark:text-success-300" />;
    default: return <Building2 className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />;
  }
};

// ============================================================================
// ATTACH TO ENTITY MODAL (Step 4)
// ============================================================================

interface AttachToEntityModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: () => void;
  shadow: ShadowAccount | null;
  corporateId: string;
}

const AttachToEntityModal: React.FC<AttachToEntityModalProps> = ({
  isOpen,
  onClose,
  onSuccess,
  shadow,
  corporateId,
}) => {
  const [entities, setEntities] = useState<LegalEntity[]>([]);
  const [existingAttachments, setExistingAttachments] = useState<AccountAttachment[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadingEntities, setLoadingEntities] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [searchTerm, setSearchTerm] = useState('');
  
  const [formData, setFormData] = useState({
    legalEntityId: '',
    relationshipType: 'OWNER' as 'OWNER' | 'BENEFICIARY' | 'AUTHORIZED' | 'GUARANTOR' | 'COLLATERAL',
    isPrimary: true,
    description: '',
    effectiveFrom: new Date().toISOString().split('T')[0],
    effectiveTo: '',
    maxTransactionAmount: '',
    dailyLimit: '',
  });

  // Load entities and existing attachments when modal opens
  useEffect(() => {
    const loadData = async () => {
      if (!isOpen || !corporateId || !shadow) return;
      
      setLoadingEntities(true);
      setError(null);
      
      try {
        // Load legal entities from API
        const entitiesData = await legalEntityApi.getByCorporate(corporateId);
        setEntities(entitiesData);
        
        if (entitiesData.length === 0) {
          console.warn('No legal entities found for corporate:', corporateId);
        }
        
        // Load existing attachments for this shadow account
        try {
          const attachments = await accountAttachmentApi.getByVirtualAccount(shadow.id);
          setExistingAttachments(attachments);
        } catch (e) {
          console.warn('Could not load existing attachments:', e);
          setExistingAttachments([]);
        }
      } catch (err: any) {
        console.error('Failed to load entities:', err);
        setError('Failed to load legal entities. Please ensure entities are created first.');
        setEntities([]);
      } finally {
        setLoadingEntities(false);
      }
    };
    
    loadData();
  }, [isOpen, corporateId, shadow]);

  // Reset form when modal closes
  useEffect(() => {
    if (!isOpen) {
      setFormData({
        legalEntityId: '',
        relationshipType: 'OWNER',
        isPrimary: true,
        description: '',
        effectiveFrom: new Date().toISOString().split('T')[0],
        effectiveTo: '',
        maxTransactionAmount: '',
        dailyLimit: '',
      });
      setSearchTerm('');
      setError(null);
    }
  }, [isOpen]);

  const handleSubmit = async () => {
    if (!formData.legalEntityId || !shadow) {
      setError('Please select a legal entity');
      return;
    }
    
    // Check if relationship already exists
    const existingRelation = existingAttachments.find(
      a => a.legalEntityId === formData.legalEntityId && a.relationshipType === formData.relationshipType
    );
    if (existingRelation) {
      setError(`This entity already has a ${formData.relationshipType} relationship with this account`);
      return;
    }
    
    setLoading(true);
    setError(null);
    
    try {
      const request = {
        virtualAccountId: shadow.id,
        legalEntityId: formData.legalEntityId,
        relationshipType: formData.relationshipType,
        isPrimary: formData.isPrimary,
        description: formData.description || undefined,
        effectiveFrom: formData.effectiveFrom,
        effectiveTo: formData.effectiveTo || undefined,
        maxTransactionAmount: formData.maxTransactionAmount ? parseFloat(formData.maxTransactionAmount) : undefined,
        dailyLimit: formData.dailyLimit ? parseFloat(formData.dailyLimit) : undefined,
      };
      
      console.log('Creating attachment:', request);
      
      await accountAttachmentApi.create(request);
      
      const selectedEntity = entities.find(e => e.id === formData.legalEntityId);
      toast.success(`Attached to ${selectedEntity?.shortName || selectedEntity?.entityName}`, {
        duration: 4000,
        icon: <Link2 className="w-5 h-5 text-primary-600" />,
      });
      
      onSuccess();
      onClose();
    } catch (err: any) {
      const errorMessage = err.response?.data?.message || err.message || 'Failed to create attachment';
      setError(errorMessage);
      toast.error(errorMessage);
    } finally {
      setLoading(false);
    }
  };

  const handleRemoveAttachment = async (attachment: AccountAttachment) => {
    if (!window.confirm(`Remove ${attachment.relationshipType} relationship with ${attachment.entityName || 'this entity'}?`)) {
      return;
    }
    
    try {
      await accountAttachmentApi.terminate(attachment.id);
      toast.success('Attachment removed');
      
      // Refresh attachments
      if (shadow) {
        const attachments = await accountAttachmentApi.getByVirtualAccount(shadow.id);
        setExistingAttachments(attachments);
      }
    } catch (err: any) {
      toast.error(err.response?.data?.message || 'Failed to remove attachment');
    }
  };

  const selectedEntity = entities.find(e => e.id === formData.legalEntityId);
  const selectedRelationType = RELATIONSHIP_TYPE_CONFIG[formData.relationshipType];
  
  // Filter entities by search term
  const filteredEntities = entities.filter(e => 
    !searchTerm || 
    e.entityName.toLowerCase().includes(searchTerm.toLowerCase()) ||
    e.entityCode.toLowerCase().includes(searchTerm.toLowerCase()) ||
    e.shortName?.toLowerCase().includes(searchTerm.toLowerCase())
  );
  
  // Check if this type requires limits
  const showLimitsFields = formData.relationshipType === 'AUTHORIZED';

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="Attach to Legal Entity" size="lg">
      <div className="space-y-5">
        {/* Error Alert */}
        {error && (
          <div className="p-3 bg-error-50 dark:bg-error-500/10 border border-error-200 dark:border-error-500/30 rounded-lg flex items-start gap-2 text-error-700 dark:text-error-300">
            <AlertCircle className="w-5 h-5 mt-0.5 flex-shrink-0" />
            <span className="text-sm">{error}</span>
          </div>
        )}

        {/* Shadow Account Info */}
        {shadow && (
          <div className="p-3 bg-neutral-50 dark:bg-primary-950 border border-neutral-200 dark:border-primary-800 rounded-lg">
            <div className="flex items-center gap-3">
              <StatusIconBadge tone="info" icon={Layers} rounded="lg" />
              <div className="flex-1">
                <p className="font-medium text-primary-900 dark:text-neutral-50">{shadow.vaName}</p>
                <p className="text-xs text-neutral-500 dark:text-neutral-400 font-mono">{shadow.vaNumber}</p>
              </div>
              <div className="text-right">
                <p className="text-xs text-neutral-500 dark:text-neutral-400">{shadow.currencyCode}</p>
                <p className="font-semibold text-primary-900 dark:text-neutral-50">
                  {formatCurrency(shadow.bankBalance, shadow.currencyCode)}
                </p>
              </div>
            </div>
          </div>
        )}

        {/* Existing Attachments */}
        {existingAttachments.length > 0 && (
          <div>
            <label className="field-label block mb-2">
              Current Attachments
            </label>
            <div className="space-y-2 max-h-32 overflow-y-auto">
              {existingAttachments.map((att) => {
                const typeConfig = RELATIONSHIP_TYPE_CONFIG[att.relationshipType];
                const TypeIcon = typeConfig?.icon || User;
                return (
                  <div key={att.id} className="flex items-center justify-between p-2 bg-neutral-50 dark:bg-primary-950 rounded-lg">
                    <div className="flex items-center gap-2">
                      <div className={cn("w-6 h-6 rounded flex items-center justify-center", typeConfig?.bgColor || 'bg-neutral-100 dark:bg-primary-800')}>
                        <TypeIcon className={cn("w-3 h-3", typeConfig?.color || 'text-neutral-600 dark:text-neutral-300')} />
                      </div>
                      <div>
                        <span className="text-sm font-medium">{att.entityName || att.legalEntityId}</span>
                        <Badge variant={att.isPrimary ? 'info' : 'neutral'} size="sm" className="ml-2">
                          {att.relationshipType}
                        </Badge>
                      </div>
                    </div>
                    <button
                      onClick={() => handleRemoveAttachment(att)}
                      className="text-neutral-400 dark:text-neutral-500 hover:text-error-500 dark:hover:text-error-300 p-1"
                      title="Remove attachment"
                    >
                      <Unlink className="w-4 h-4" />
                    </button>
                  </div>
                );
              })}
            </div>
          </div>
        )}

        {/* Relationship Type Selection */}
        <div>
          <label className="field-label block mb-2">
            Relationship Type <span className="text-error-500 dark:text-error-300">*</span>
          </label>
          <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
            {Object.entries(RELATIONSHIP_TYPE_CONFIG).map(([type, config]) => {
              const TypeIcon = config.icon;
              const isSelected = formData.relationshipType === type;
              return (
                <button
                  key={type}
                  onClick={() => setFormData(prev => ({ ...prev, relationshipType: type as any }))}
                  className={cn(
                    "p-3 rounded-lg border-2 text-left transition-all",
                    isSelected
                      ? "border-primary-500 bg-primary-50 dark:bg-primary-800/40 ring-1 ring-primary-200 dark:ring-primary-700"
                      : "border-neutral-200 dark:border-primary-800 hover:border-neutral-300 dark:hover:border-primary-700 hover:bg-neutral-50 dark:hover:bg-primary-800/50"
                  )}
                >
                  <div className="flex items-center gap-2">
                    <div className={cn("w-8 h-8 rounded-lg flex items-center justify-center", config.bgColor)}>
                      <TypeIcon className={cn("w-4 h-4", config.color)} />
                    </div>
                    <div>
                      <p className="text-sm font-medium">{config.label}</p>
                    </div>
                  </div>
                  {isSelected && (
                    <p className="text-xs text-neutral-500 dark:text-neutral-400 mt-2">{config.description}</p>
                  )}
                </button>
              );
            })}
          </div>
        </div>

        {/* Entity Selection */}
        <div>
          <label className="field-label block mb-2">
            Legal Entity <span className="text-error-500 dark:text-error-300">*</span>
          </label>

          {/* Search */}
          <div className="relative mb-2">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400 dark:text-neutral-500" />
            <Input
              placeholder="Search entities..."
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              className="pl-10"
            />
          </div>

          {loadingEntities ? (
            <div className="flex items-center justify-center py-6 bg-neutral-50 dark:bg-primary-950 rounded-lg border border-dashed">
              <Loader2 className="w-5 h-5 animate-spin text-primary-600 dark:text-primary-200 mr-2" />
              <span className="text-neutral-600 dark:text-neutral-300 text-sm">Loading entities...</span>
            </div>
          ) : filteredEntities.length === 0 ? (
            <div className="p-3 bg-warning-50 dark:bg-warning-500/10 border border-warning-200 dark:border-warning-500/30 rounded-lg">
              <div className="flex items-start gap-2">
                <AlertTriangle className="w-4 h-4 text-warning-600 dark:text-warning-300 mt-0.5" />
                <div>
                  <p className="text-sm font-medium text-warning-800 dark:text-warning-300">No Entities Found</p>
                  <p className="text-xs text-warning-700 dark:text-warning-300 mt-0.5">
                    {searchTerm ? 'Try a different search term' : 'Create legal entities in the Parties page first.'}
                  </p>
                </div>
              </div>
            </div>
          ) : (
            <div className="space-y-2 max-h-48 overflow-y-auto border rounded-lg p-2">
              {filteredEntities.map((entity) => {
                const isSelected = formData.legalEntityId === entity.id;
                const isAlreadyAttached = existingAttachments.some(
                  a => a.legalEntityId === entity.id && a.relationshipType === formData.relationshipType
                );

                return (
                  <div
                    key={entity.id}
                    onClick={() => !isAlreadyAttached && setFormData(prev => ({ ...prev, legalEntityId: entity.id }))}
                    className={cn(
                      "p-3 rounded-lg border-2 transition-all",
                      isAlreadyAttached
                        ? "border-neutral-200 dark:border-primary-800 bg-neutral-50 dark:bg-primary-950 opacity-50 cursor-not-allowed"
                        : isSelected
                        ? "border-primary-500 bg-primary-50 dark:bg-primary-800/40 ring-1 ring-primary-200 dark:ring-primary-700 cursor-pointer"
                        : "border-transparent hover:border-neutral-200 dark:hover:border-primary-800 hover:bg-neutral-50 dark:hover:bg-primary-800/50 cursor-pointer"
                    )}
                  >
                    <div className="flex items-center gap-3">
                      <div className={cn(
                        "w-8 h-8 rounded-lg flex items-center justify-center",
                        entity.entityType === 'HOLDING' ? 'bg-cat-2/10 dark:bg-cat-2/15' :
                        entity.entityType === 'TREASURY_CENTER' ? 'bg-warning-100 dark:bg-warning-500/20' :
                        'bg-info-100 dark:bg-info-500/20'
                      )}>
                        {getEntityTypeIcon(entity.entityType)}
                      </div>
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2">
                          <span className="text-sm font-medium text-primary-900 dark:text-neutral-50 truncate">
                            {entity.shortName || entity.entityName}
                          </span>
                          <Badge variant="neutral" size="sm">{entity.entityType}</Badge>
                          {entity.isTreasuryCenter && (
                            <Badge variant="warning" size="sm">Treasury</Badge>
                          )}
                        </div>
                        <div className="flex items-center gap-2 text-xs text-neutral-500 dark:text-neutral-400">
                          <span className="font-mono">{entity.entityCode}</span>
                          {entity.countryCode && (
                            <>
                              <span>•</span>
                              <span>{entity.countryCode}</span>
                            </>
                          )}
                          <span>•</span>
                          <span>{entity.functionalCurrency}</span>
                        </div>
                      </div>
                      {isAlreadyAttached && (
                        <Badge variant="success" size="sm">Already Attached</Badge>
                      )}
                      {isSelected && !isAlreadyAttached && (
                        <Check className="w-5 h-5 text-primary-600 dark:text-primary-200" />
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>

        {/* Authorization Limits (for AUTHORIZED type) */}
        {showLimitsFields && (
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="field-label block mb-1">
                Max Transaction Amount
              </label>
              <Input
                type="number"
                placeholder="e.g., 100000"
                value={formData.maxTransactionAmount}
                onChange={(e) => setFormData(prev => ({ ...prev, maxTransactionAmount: e.target.value }))}
              />
            </div>
            <div>
              <label className="field-label block mb-1">
                Daily Limit
              </label>
              <Input
                type="number"
                placeholder="e.g., 500000"
                value={formData.dailyLimit}
                onChange={(e) => setFormData(prev => ({ ...prev, dailyLimit: e.target.value }))}
              />
            </div>
          </div>
        )}

        {/* Effective Dates */}
        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className="field-label block mb-1">
              Effective From <span className="text-error-500 dark:text-error-300">*</span>
            </label>
            <Input
              type="date"
              value={formData.effectiveFrom}
              onChange={(e) => setFormData(prev => ({ ...prev, effectiveFrom: e.target.value }))}
            />
          </div>
          <div>
            <label className="field-label block mb-1">
              Effective To <span className="text-neutral-400 dark:text-neutral-500">(Optional)</span>
            </label>
            <Input
              type="date"
              value={formData.effectiveTo}
              onChange={(e) => setFormData(prev => ({ ...prev, effectiveTo: e.target.value }))}
            />
          </div>
        </div>

        {/* Primary Flag */}
        <div className="flex items-center gap-2">
          <input
            type="checkbox"
            id="isPrimary"
            checked={formData.isPrimary}
            onChange={(e) => setFormData(prev => ({ ...prev, isPrimary: e.target.checked }))}
            className="rounded text-primary-600 dark:text-primary-200"
          />
          <label htmlFor="isPrimary" className="text-sm text-neutral-700 dark:text-neutral-200">
            Set as primary {formData.relationshipType.toLowerCase()} for this account
          </label>
        </div>

        {/* Description */}
        <div>
          <label className="field-label block mb-1">
            Description <span className="text-neutral-400 dark:text-neutral-500">(Optional)</span>
          </label>
          <Input
            placeholder="e.g., Primary operating entity for EUR treasury"
            value={formData.description}
            onChange={(e) => setFormData(prev => ({ ...prev, description: e.target.value }))}
          />
        </div>

        {/* Selected Summary */}
        {selectedEntity && (
          <div className="p-3 bg-success-50 dark:bg-success-500/10 border border-success-200 dark:border-success-500/30 rounded-lg">
            <div className="flex items-center gap-2">
              <Check className="w-4 h-4 text-success-600 dark:text-success-300" />
              <span className="text-sm text-success-700 dark:text-success-300">
                Will attach as <strong>{selectedRelationType.label}</strong> to{' '}
                <strong>{selectedEntity.shortName || selectedEntity.entityName}</strong>
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
            disabled={loading || !formData.legalEntityId}
            leftIcon={loading ? <Loader2 className="w-4 h-4 animate-spin" /> : <UserPlus className="w-4 h-4" />}
          >
            {loading ? 'Attaching...' : 'Attach to Entity'}
          </Button>
        </div>
      </div>
    </Modal>
  );
};

// ============================================================================
// SHADOW ACCOUNT CARD COMPONENT
// ============================================================================

interface ShadowCardProps {
  shadow: ShadowAccount;
  onSync: (id: string) => void;
  onView: (shadow: ShadowAccount) => void;
  onEdit: (shadow: ShadowAccount) => void;
  onAttachEntity: (shadow: ShadowAccount) => void;
  syncing: boolean;
}

const ShadowCard: React.FC<ShadowCardProps> = ({ shadow, onSync, onView, onEdit, onAttachEntity, syncing }) => {
  const [showMenu, setShowMenu] = useState(false);
  const sourceConfig = DATA_SOURCE_CONFIG[shadow.balanceDataSource] || DATA_SOURCE_CONFIG.MANUAL;
  const SourceIcon = sourceConfig.icon;
  
  const isStale = shadow.bankBalanceAt 
    ? (Date.now() - new Date(shadow.bankBalanceAt).getTime()) > 60 * 60 * 1000
    : true;

  const balanceDiff = shadow.bankBalance - shadow.bankAvailableBalance;

  return (
    <div className={cn(
      "bg-white dark:bg-primary-900 rounded-xl border shadow-sm hover:shadow-md transition-all",
      isStale ? "border-warning-200 dark:border-warning-500/30" : "border-neutral-200 dark:border-primary-800"
    )}>
      <div className="p-4 border-b border-neutral-100 dark:border-primary-800/60">
        <div className="flex items-start justify-between">
          <div className="flex items-center gap-3">
            <div className={cn("w-10 h-10 rounded-lg flex items-center justify-center", sourceConfig.bgColor)}>
              <Layers className={cn("w-5 h-5", sourceConfig.color)} />
            </div>
            <div>
              <h3 className="font-semibold text-primary-900 dark:text-neutral-50">{shadow.vaName}</h3>
              <p className="text-sm text-neutral-500 dark:text-neutral-400 font-mono">{shadow.vaNumber}</p>
            </div>
          </div>
          <div className="relative">
            <button onClick={() => setShowMenu(!showMenu)} className="p-1 hover:bg-neutral-100 dark:hover:bg-primary-800 rounded">
              <MoreVertical className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
            </button>
            {showMenu && (
              <div className="absolute right-0 top-full mt-1 w-44 bg-white dark:bg-primary-900 rounded-lg shadow-lg border py-1 z-10">
                <button onClick={() => { onView(shadow); setShowMenu(false); }}
                  className="w-full px-3 py-2 text-left text-sm hover:bg-neutral-50 dark:hover:bg-primary-800/50 flex items-center gap-2">
                  <Eye className="w-4 h-4" /> View Details
                </button>
                <button onClick={() => { onEdit(shadow); setShowMenu(false); }}
                  className="w-full px-3 py-2 text-left text-sm hover:bg-neutral-50 dark:hover:bg-primary-800/50 flex items-center gap-2">
                  <Settings className="w-4 h-4" /> Configure
                </button>
                <button onClick={() => { onAttachEntity(shadow); setShowMenu(false); }}
                  className="w-full px-3 py-2 text-left text-sm hover:bg-neutral-50 dark:hover:bg-primary-800/50 flex items-center gap-2">
                  <UserPlus className="w-4 h-4" /> Attach to Entity
                </button>
                <button onClick={() => { onSync(shadow.id); setShowMenu(false); }}
                  className="w-full px-3 py-2 text-left text-sm hover:bg-neutral-50 dark:hover:bg-primary-800/50 flex items-center gap-2">
                  <RefreshCw className="w-4 h-4" /> Sync Now
                </button>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Owner Entity Info */}
      {shadow.primaryOwner && (
        <div className="px-4 py-2 bg-info-50 dark:bg-info-500/10 border-b border-info-100 dark:border-info-500/30">
          <div className="flex items-center gap-2 text-xs text-info-700 dark:text-info-300">
            <User className="w-3 h-3" />
            <span>Owner: {shadow.primaryOwner.entityName}</span>
            <span className="text-info-400 dark:text-info-300">({shadow.primaryOwner.entityCode})</span>
          </div>
        </div>
      )}

      {/* Parent Hierarchy Info */}
      {shadow.parentVaNumber && (
        <div className="px-4 py-2 bg-cat-1-soft dark:bg-cat-1/15 border-b border-cat-1/10 dark:border-cat-1/30">
          <div className="flex items-center gap-2 text-xs text-cat-1">
            <GitBranch className="w-3 h-3" />
            <span>Under: {shadow.parentVaNumber}</span>
          </div>
        </div>
      )}

      <div className="p-4 bg-neutral-50 dark:bg-primary-950 border-b border-neutral-100 dark:border-primary-800/60">
        <div className="flex items-center gap-2 mb-2">
          <Building2 className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
          <span className="field-label">{shadow.bankName || 'Unknown Bank'}</span>
        </div>
        <p className="text-xs text-neutral-500 dark:text-neutral-400 font-mono">{shadow.bankIban || shadow.bankAccountNumber}</p>
        {shadow.bankSwift && <p className="text-xs text-neutral-400 dark:text-neutral-500 mt-1">SWIFT: {shadow.bankSwift}</p>}
      </div>

      <div className="p-4 space-y-3">
        <div className="flex items-center justify-between">
          <span className="text-sm text-neutral-500 dark:text-neutral-400">Bank Balance</span>
          <span className="stat-value-xs">
            {formatCurrency(shadow.bankBalance, shadow.currencyCode)}
          </span>
        </div>
        <div className="flex items-center justify-between">
          <span className="text-sm text-neutral-500 dark:text-neutral-400">Available</span>
          <span className="text-base font-semibold text-success-600 dark:text-success-300">
            {formatCurrency(shadow.bankAvailableBalance, shadow.currencyCode)}
          </span>
        </div>
        {balanceDiff > 0 && (
          <div className="flex items-center justify-between text-sm">
            <span className="text-neutral-400 dark:text-neutral-500">On Hold</span>
            <span className="text-warning-600 dark:text-warning-300">{formatCurrency(balanceDiff, shadow.currencyCode)}</span>
          </div>
        )}
      </div>

      <div className="px-4 py-3 border-t border-neutral-100 dark:border-primary-800/60 bg-neutral-50/50 dark:bg-primary-950/50 rounded-b-xl">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <div className={cn("p-1 rounded", sourceConfig.bgColor)}>
              <SourceIcon className={cn("w-3 h-3", sourceConfig.color)} />
            </div>
            <span className="text-xs text-neutral-500 dark:text-neutral-400">{sourceConfig.label}</span>
          </div>
          <div className="flex items-center gap-2">
            {isStale && <Badge variant="warning" size="sm"><AlertTriangle className="w-3 h-3 mr-1" />Stale</Badge>}
            {shadow.lastSyncStatus === 'SUCCESS' && <Badge variant="success" size="sm"><CheckCircle2 className="w-3 h-3 mr-1" />Synced</Badge>}
            {shadow.lastSyncStatus === 'FAILED' && <Badge variant="error" size="sm"><AlertCircle className="w-3 h-3 mr-1" />Failed</Badge>}
          </div>
        </div>
        {shadow.bankBalanceAt && (
          <p className="text-xs text-neutral-400 dark:text-neutral-500 mt-2">
            <Clock className="w-3 h-3 inline mr-1" />
            Last synced: {new Date(shadow.bankBalanceAt).toLocaleString()}
          </p>
        )}
        <div className="flex gap-2 mt-3">
          <Button variant="outline" size="sm" className="flex-1" onClick={() => onSync(shadow.id)} disabled={syncing}>
            {syncing ? <Loader2 className="w-4 h-4 mr-1 animate-spin" /> : <RefreshCw className="w-4 h-4 mr-1" />}
            Sync
          </Button>
          <Button variant="outline" size="sm" className="flex-1" onClick={() => onAttachEntity(shadow)}>
            <UserPlus className="w-4 h-4 mr-1" />
            Attach
          </Button>
        </div>
      </div>
    </div>
  );
};

// ============================================================================
// CREATE SHADOW MODAL WITH AGGREGATION PICKER
// ============================================================================

interface CreateShadowModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: () => void;
  corporateId: string;
  physicalAccounts: PhysicalAccount[];
}

const CreateShadowModal: React.FC<CreateShadowModalProps> = ({
  isOpen,
  onClose,
  onSuccess,
  corporateId,
  physicalAccounts,
}) => {
  const [formData, setFormData] = useState({
    physicalAccountId: '',
    parentVaId: '',
    vaName: '',
    balanceDataSource: 'CORE_BANKING',
  });
  const [hierarchyNodes, setHierarchyNodes] = useState<HierarchyNode[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadingNodes, setLoadingNodes] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Load hierarchy nodes when modal opens
  useEffect(() => {
    const loadNodes = async () => {
      if (!isOpen || !corporateId) return;
      
      setLoadingNodes(true);
      
      try {
        let nodes = await hierarchyApi.getHierarchyNodes(corporateId);
        
        if (nodes.length === 0) {
          nodes = await hierarchyApi.getAggregationNodes(corporateId);
        }
        
        setHierarchyNodes(nodes);
        
        const defaultNode = nodes.find(n => n.accountCategory === 'AGGREGATION');
        if (defaultNode) {
          setFormData(prev => ({ ...prev, parentVaId: defaultNode.id }));
        }
      } catch (err) {
        console.error('Failed to load hierarchy nodes:', err);
        setHierarchyNodes([]);
      } finally {
        setLoadingNodes(false);
      }
    };
    
    loadNodes();
  }, [isOpen, corporateId]);

  // Reset form when modal closes
  useEffect(() => {
    if (!isOpen) {
      setFormData({
        physicalAccountId: '',
        parentVaId: '',
        vaName: '',
        balanceDataSource: 'CORE_BANKING',
      });
      setError(null);
    }
  }, [isOpen]);

  // Auto-generate name when physical account selected
  useEffect(() => {
    if (formData.physicalAccountId) {
      const selectedAccount = physicalAccounts.find(a => a.id === formData.physicalAccountId);
      if (selectedAccount && !formData.vaName) {
        setFormData(prev => ({
          ...prev,
          vaName: `${selectedAccount.currencyCode} Bank Mirror - ${selectedAccount.bankName || 'Account'}`
        }));
      }
    }
  }, [formData.physicalAccountId, physicalAccounts]);

  const handleSubmit = async () => {
    if (!formData.physicalAccountId) {
      setError('Please select a physical account');
      return;
    }
    
    setLoading(true);
    setError(null);
    
    try {
      const payload = {
        physicalAccountId: formData.physicalAccountId,
        corporateId: corporateId,
        parentVaId: formData.parentVaId || undefined,
      };
      
      console.log('Creating shadow account with payload:', payload);
      
      const response: CreateShadowResponse = await shadowAccountApi.create(payload);
      
      const shadowVaNumber = response.data?.vaNumber || response.shadowVa?.vaNumber || 'Shadow Account';
      let successMessage = `✓ Created ${shadowVaNumber}`;
      
      if (response.currencyMirrorsCreated && response.currencyMirrorsCreated.length > 0) {
        successMessage += ` + ${response.currencyMirrorsCreated.length} Currency Mirror(s)`;
      }
      if (response.exceptionVaCreated) {
        successMessage += ' + Exception VA';
      }
      
      toast.success(successMessage, {
        duration: 5000,
        icon: <Link2 className="w-5 h-5 text-primary-600" />,
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

  const selectedPhysicalAccount = physicalAccounts.find(a => a.id === formData.physicalAccountId);
  const selectedParentNode = hierarchyNodes.find(n => n.id === formData.parentVaId);
  
  const availablePhysicalAccounts = physicalAccounts.filter(a => !a.shadowVaId);

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="Create Shadow Account" size="lg">
      <div className="space-y-5">
        {error && (
          <div className="p-3 bg-error-50 dark:bg-error-500/10 border border-error-200 dark:border-error-500/30 rounded-lg flex items-start gap-2 text-error-700 dark:text-error-300">
            <AlertCircle className="w-5 h-5 mt-0.5 flex-shrink-0" />
            <span className="text-sm">{error}</span>
          </div>
        )}

        <div className="p-3 bg-info-50 dark:bg-info-500/10 border border-info-200 dark:border-info-500/30 rounded-lg">
          <div className="flex items-start gap-2">
            <Info className="w-4 h-4 text-info-600 dark:text-info-300 mt-0.5" />
            <p className="text-sm text-info-700 dark:text-info-300">
              A Shadow Account mirrors a physical bank account's balance into the VA hierarchy for liquidity visibility.
            </p>
          </div>
        </div>

        <div>
          <label className="field-label block mb-1">
            Shadow Account Name
          </label>
          <Input
            placeholder="e.g., EUR Bank Mirror"
            value={formData.vaName}
            onChange={(e) => setFormData(prev => ({ ...prev, vaName: e.target.value }))}
          />
          <p className="text-xs text-neutral-500 dark:text-neutral-400 mt-1">Auto-generated if left empty</p>
        </div>

        <div>
          <label className="field-label block mb-1">
            Physical Account <span className="text-error-500 dark:text-error-300">*</span>
          </label>
          {availablePhysicalAccounts.length > 0 ? (
            <select
              value={formData.physicalAccountId}
              onChange={(e) => setFormData(prev => ({ ...prev, physicalAccountId: e.target.value }))}
              className="w-full px-3 py-2 border border-neutral-300 dark:border-primary-700 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-primary-500"
            >
              <option value="">Select physical account...</option>
              {availablePhysicalAccounts.map(acc => (
                <option key={acc.id} value={acc.id}>
                  {acc.accountName} - {acc.accountNumber} ({acc.currencyCode})
                </option>
              ))}
            </select>
          ) : (
            <div className="p-3 bg-warning-50 dark:bg-warning-500/10 border border-warning-200 dark:border-warning-500/30 rounded-lg text-sm text-warning-700 dark:text-warning-300">
              <AlertTriangle className="w-4 h-4 inline mr-1" />
              No physical accounts available. Create physical accounts first or all accounts already have shadows.
            </div>
          )}
        </div>

        {selectedPhysicalAccount && (
          <div className="p-3 bg-info-50 dark:bg-info-500/10 border border-info-200 dark:border-info-500/30 rounded-lg">
            <div className="flex items-center gap-3">
              <StatusIconBadge tone="info" icon={Building2} rounded="lg" />
              <div className="flex-1">
                <p className="font-medium text-info-900 dark:text-info-100">{selectedPhysicalAccount.accountName}</p>
                <p className="text-xs text-info-600 dark:text-info-300 font-mono">{selectedPhysicalAccount.iban || selectedPhysicalAccount.accountNumber}</p>
              </div>
              <div className="text-right">
                <p className="text-xs text-info-600 dark:text-info-300">{selectedPhysicalAccount.currencyCode}</p>
                <p className="font-semibold text-info-900 dark:text-info-100">
                  {formatCurrency(selectedPhysicalAccount.currentBalance, selectedPhysicalAccount.currencyCode)}
                </p>
              </div>
            </div>
          </div>
        )}

        <div>
          <label className="field-label block mb-2">
            Parent Hierarchy Node (AGGREGATION) <span className="text-neutral-400 dark:text-neutral-500">- Recommended</span>
          </label>

          {loadingNodes ? (
            <div className="flex items-center justify-center py-6 bg-neutral-50 dark:bg-primary-950 rounded-lg border border-dashed">
              <Loader2 className="w-5 h-5 animate-spin text-primary-600 dark:text-primary-200 mr-2" />
              <span className="text-neutral-600 dark:text-neutral-300 text-sm">Loading hierarchy...</span>
            </div>
          ) : hierarchyNodes.length === 0 ? (
            <div className="p-3 bg-warning-50 dark:bg-warning-500/10 border border-warning-200 dark:border-warning-500/30 rounded-lg">
              <div className="flex items-start gap-2">
                <AlertTriangle className="w-4 h-4 text-warning-600 dark:text-warning-300 mt-0.5" />
                <div>
                  <p className="text-sm font-medium text-warning-800 dark:text-warning-300">No Hierarchy Found</p>
                  <p className="text-xs text-warning-700 dark:text-warning-300 mt-0.5">
                    Initialize the corporate hierarchy first, or the Shadow will be created at ROOT level.
                  </p>
                </div>
              </div>
            </div>
          ) : (
            <div className="space-y-2 max-h-48 overflow-y-auto border rounded-lg p-2">
              <div
                onClick={() => setFormData(prev => ({ ...prev, parentVaId: '' }))}
                className={cn(
                  "p-2 rounded-lg border-2 cursor-pointer transition-all",
                  formData.parentVaId === ''
                    ? "border-neutral-500 bg-neutral-50 dark:bg-primary-950"
                    : "border-transparent hover:border-neutral-200 dark:hover:border-primary-800 hover:bg-neutral-50 dark:hover:bg-primary-800/50"
                )}
              >
                <div className="flex items-center gap-2">
                  <div className="w-6 h-6 rounded bg-neutral-100 dark:bg-primary-800 flex items-center justify-center">
                    <Globe className="w-3 h-3 text-neutral-500 dark:text-neutral-400" />
                  </div>
                  <span className="text-sm text-neutral-600 dark:text-neutral-300">No parent (attach at ROOT level)</span>
                  {formData.parentVaId === '' && <Check className="w-4 h-4 text-neutral-600 dark:text-neutral-300 ml-auto" />}
                </div>
              </div>

              {hierarchyNodes.map((node) => (
                <div
                  key={node.id}
                  onClick={() => setFormData(prev => ({ ...prev, parentVaId: node.id }))}
                  className={cn(
                    "p-2 rounded-lg border-2 cursor-pointer transition-all",
                    formData.parentVaId === node.id
                      ? "border-primary-500 bg-primary-50 dark:bg-primary-800/40 ring-1 ring-primary-200 dark:ring-primary-700"
                      : "border-transparent hover:border-neutral-200 dark:hover:border-primary-800 hover:bg-neutral-50 dark:hover:bg-primary-800/50",
                    getCategoryColor(node.accountCategory)
                  )}
                >
                  <div className="flex items-center gap-2">
                    <div className={cn(
                      "w-6 h-6 rounded flex items-center justify-center",
                      node.accountCategory === 'ROOT' ? 'bg-cat-2/10 dark:bg-cat-2/15' : 'bg-cat-1/10 dark:bg-cat-1/15'
                    )}>
                      {getCategoryIcon(node.accountCategory)}
                    </div>
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2">
                        <span className="text-sm font-medium text-primary-900 dark:text-neutral-50 truncate">{node.vaName}</span>
                        <Badge
                          variant={node.accountCategory === 'ROOT' ? 'neutral' : 'info'}
                          size="sm"
                        >
                          {node.accountCategory}
                        </Badge>
                      </div>
                      <p className="text-xs text-neutral-500 dark:text-neutral-400 font-mono">{node.vaNumber}</p>
                    </div>
                    <span className="text-xs text-neutral-500 dark:text-neutral-400">{node.currencyCode}</span>
                    {formData.parentVaId === node.id && (
                      <Check className="w-4 h-4 text-primary-600 dark:text-primary-200" />
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        <div>
          <label className="field-label block mb-1">
            Data Source
          </label>
          <select
            value={formData.balanceDataSource}
            onChange={(e) => setFormData(prev => ({ ...prev, balanceDataSource: e.target.value }))}
            className="w-full px-3 py-2 border border-neutral-300 dark:border-primary-700 rounded-lg"
          >
            {Object.entries(DATA_SOURCE_CONFIG).map(([key, cfg]) => (
              <option key={key} value={key}>{cfg.label}</option>
            ))}
          </select>
        </div>

        {selectedParentNode && (
          <div className="p-3 bg-success-50 dark:bg-success-500/10 border border-success-200 dark:border-success-500/30 rounded-lg">
            <div className="flex items-center gap-2">
              <Check className="w-4 h-4 text-success-600 dark:text-success-300" />
              <span className="text-sm text-success-700 dark:text-success-300">
                Shadow will be created under: <strong>{selectedParentNode.vaName}</strong>
              </span>
            </div>
          </div>
        )}

        <div className="p-3 bg-neutral-50 dark:bg-primary-950 border border-neutral-200 dark:border-primary-800 rounded-lg">
          <div className="flex items-start gap-2">
            <Info className="w-4 h-4 text-neutral-500 dark:text-neutral-400 mt-0.5" />
            <div className="text-xs text-neutral-600 dark:text-neutral-300">
              <p className="font-medium mb-1">When you create this Shadow Account:</p>
              <ul className="space-y-0.5">
                <li>• A PHYSICAL_MIRROR VA is created under the selected parent</li>
                <li>• Currency Mirror(s) auto-created if this introduces a new currency</li>
                <li>• Exception VA auto-created at ROOT if not exists for this currency</li>
              </ul>
            </div>
          </div>
        </div>

        <div className="flex justify-end gap-3 pt-4 border-t">
          <Button variant="outline" onClick={onClose} disabled={loading}>
            Cancel
          </Button>
          <Button 
            onClick={handleSubmit} 
            disabled={loading || !formData.physicalAccountId}
            leftIcon={loading ? <Loader2 className="w-4 h-4 animate-spin" /> : <Link2 className="w-4 h-4" />}
          >
            {loading ? 'Creating...' : 'Create Shadow Account'}
          </Button>
        </div>
      </div>
    </Modal>
  );
};

// ============================================================================
// MAIN PAGE COMPONENT
// ============================================================================

const ShadowAccountsPage: React.FC = () => {
  const [shadows, setShadows] = useState<ShadowAccount[]>([]);
  const [physicalAccounts, setPhysicalAccounts] = useState<PhysicalAccount[]>([]);
  const [corporates, setCorporates] = useState<Corporate[]>([]);
  const [selectedCorporateId, setSelectedCorporateId] = useState<string>('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [searchTerm, setSearchTerm] = useState('');
  const [selectedCurrency, setSelectedCurrency] = useState<string>('');
  const [syncingIds, setSyncingIds] = useState<Set<string>>(new Set());
  const [syncingAll, setSyncingAll] = useState(false);
  
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [showDetailModal, setShowDetailModal] = useState(false);
  const [showAttachModal, setShowAttachModal] = useState(false);
  const [selectedShadow, setSelectedShadow] = useState<ShadowAccount | null>(null);
  const [syncResult, setSyncResult] = useState<SyncResult | null>(null);
  const [showSyncResultModal, setShowSyncResultModal] = useState(false);

  const [stats, setStats] = useState({
    totalShadows: 0, totalBalance: 0, staleShadows: 0, failedSyncs: 0, currencies: [] as string[],
  });

  // Load corporates on mount
  useEffect(() => {
    loadCorporates();
  }, []);

  // Load data when corporate changes
  useEffect(() => {
    if (selectedCorporateId) {
      loadData();
    }
  }, [selectedCorporateId]);

  const loadCorporates = async () => {
    try {
      const res = await corporatesApi.getAll();
      const data = ensureArray<Corporate>(res?.data || res);
      setCorporates(data);
      if (data.length > 0) {
        setSelectedCorporateId(data[0].id);
      }
    } catch (err) {
      console.error('Failed to load corporates:', err);
      setError('Failed to load corporates');
    }
  };

  const loadData = useCallback(async () => {
    if (!selectedCorporateId) return;
    
    setLoading(true);
    setError(null);

    try {
      // Load physical accounts
      const physRes = await physicalAccountsApi.getAll();
      const physData = ensureArray<PhysicalAccount>(physRes?.data || physRes);
      setPhysicalAccounts(physData);

      // Load shadow accounts
      const shadowRes = await shadowAccountApi.getByCorporate(selectedCorporateId);
      const shadowData = ensureArray<ShadowAccount>(shadowRes?.data || shadowRes);
      setShadows(shadowData);
      calculateStats(shadowData);
    } catch (err) {
      console.error('Failed to load data:', err);
      setError('Failed to load data. Please check API connection.');
      setShadows([]);
      setPhysicalAccounts([]);
    } finally {
      setLoading(false);
    }
  }, [selectedCorporateId]);

  const calculateStats = (data: ShadowAccount[]) => {
    const currencies = [...new Set(data.map(s => s.currencyCode))];
    const stale = data.filter(s => s.bankBalanceAt && (Date.now() - new Date(s.bankBalanceAt).getTime()) > 60 * 60 * 1000).length;
    const failed = data.filter(s => s.lastSyncStatus === 'FAILED').length;
    setStats({
      totalShadows: data.length,
      totalBalance: data.reduce((sum, s) => sum + s.bankBalance, 0),
      staleShadows: stale,
      failedSyncs: failed,
      currencies,
    });
  };

  const handleSync = async (shadowId: string) => {
    setSyncingIds(prev => new Set(prev).add(shadowId));
    try {
      const result = await shadowAccountApi.syncBalance(shadowId);
      if (result?.data) {
        setSyncResult(result.data);
        setShowSyncResultModal(true);
        toast.success('Balance synced successfully');
        loadData();
      }
    } catch (err: any) {
      toast.error(err.response?.data?.message || 'Sync failed');
    } finally {
      setSyncingIds(prev => { const next = new Set(prev); next.delete(shadowId); return next; });
    }
  };

  const handleSyncAll = async () => {
    setSyncingAll(true);
    try {
      await shadowAccountApi.syncAllForCorporate(selectedCorporateId);
      toast.success('All shadow accounts synced');
      loadData();
    } catch (err: any) {
      toast.error(err.response?.data?.message || 'Sync all failed');
    } finally {
      setSyncingAll(false);
    }
  };

  const handleCreateSuccess = () => {
    loadData();
  };

  const handleAttachEntity = (shadow: ShadowAccount) => {
    setSelectedShadow(shadow);
    setShowAttachModal(true);
  };

  const filteredShadows = shadows.filter(shadow => {
    const matchesSearch = !searchTerm ||
      shadow.vaName.toLowerCase().includes(searchTerm.toLowerCase()) ||
      shadow.vaNumber.toLowerCase().includes(searchTerm.toLowerCase()) ||
      shadow.bankName?.toLowerCase().includes(searchTerm.toLowerCase());
    const matchesCurrency = !selectedCurrency || shadow.currencyCode === selectedCurrency;
    return matchesSearch && matchesCurrency;
  });

  // Toolbar in Aperture header. The corporate selector stays in the page body
  // since it's a filter input, not a discrete action.
  usePageHeaderActions(
    () => (
      <>
        <Button variant="outline" size="sm" onClick={handleSyncAll} disabled={syncingAll}>
          {syncingAll ? <Loader2 className="w-4 h-4 mr-1 animate-spin" /> : <RefreshCw className="w-4 h-4 mr-1" />}
          Sync All
        </Button>
        <Button size="sm" onClick={() => setShowCreateModal(true)}>
          <Plus className="w-4 h-4 mr-1" /> Add Shadow
        </Button>
      </>
    ),
    [syncingAll, selectedCorporateId]
  );

  if (loading) {
    return (
      <div className="flex items-center justify-center h-96">
        <Loader2 className="w-8 h-8 animate-spin text-primary-600 dark:text-primary-200" />
      </div>
    );
  }

  return (
    <Page>
      {/* In-body identity (Phase 10). Title + Refresh / Sync All buttons
          live in the Aperture Layout header via usePageHeaderActions. */}
      <PageHeader
        title="Shadow Accounts"
        description="VA mirrors of physical bank accounts. Sync balances from the bank ledger and link to the entity hierarchy for liquidity visibility."
      />

      {/* Corporate-only picker — uses the shared ScopeSelector primitive
          in `corporate-only` mode. The page's data API requires a single
          corporateId (no "All" mode yet — see backlog entry in
          tasks/todo.md), so we pass `requireSelection`. */}
      <ScopeSelector
        mode="corporate-only"
        corporates={corporates}
        selectedCorporateId={selectedCorporateId}
        onCorporateChange={setSelectedCorporateId}
        loading={loading}
        requireSelection
      />

      {/* Error Banner */}
      {error && (
        <Card className="bg-error-50 dark:bg-error-500/10 border-error-200 dark:border-error-500/30 animate-fade-in">
          <div className="flex items-center gap-3 p-4">
            <StatusIconBadge tone="error" icon={AlertCircle} />
            <span className="text-error-700 dark:text-error-300 font-medium">{error}</span>
          </div>
        </Card>
      )}

      {/* Stats */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card hover className="animate-fade-in" style={{ animationDelay: '0.1s' }}>
          <div className="p-4">
            <div className="flex items-start justify-between">
              <div className="flex-1">
                <p className="text-xs font-medium text-neutral-500 dark:text-neutral-400 uppercase tracking-wider">Total Shadows</p>
                <p className="stat-value-sm mt-1">{stats.totalShadows}</p>
              </div>
              <StatusIconBadge tone="info" icon={Layers} />
            </div>
          </div>
        </Card>
        <Card hover className="animate-fade-in" style={{ animationDelay: '0.15s' }}>
          <div className="p-4">
            <div className="flex items-start justify-between">
              <div className="flex-1">
                <p className="text-xs font-medium text-neutral-500 dark:text-neutral-400 uppercase tracking-wider">Total Balance</p>
                <p className="stat-value-sm mt-1 text-success-600 dark:text-success-300">{formatCurrency(stats.totalBalance, 'AED')}</p>
              </div>
              <StatusIconBadge tone="success" icon={Banknote} />
            </div>
          </div>
        </Card>
        <Card hover className="animate-fade-in" style={{ animationDelay: '0.2s' }}>
          <div className="p-4">
            <div className="flex items-start justify-between">
              <div className="flex-1">
                <p className="text-xs font-medium text-neutral-500 dark:text-neutral-400 uppercase tracking-wider">Stale Balances</p>
                <p className="stat-value-warning mt-1">{stats.staleShadows}</p>
              </div>
              <StatusIconBadge tone="warning" icon={Clock} />
            </div>
          </div>
        </Card>
        <Card hover className="animate-fade-in" style={{ animationDelay: '0.25s' }}>
          <div className="p-4">
            <div className="flex items-start justify-between">
              <div className="flex-1">
                <p className="text-xs font-medium text-neutral-500 dark:text-neutral-400 uppercase tracking-wider">Failed Syncs</p>
                <p className="stat-value-error mt-1">{stats.failedSyncs}</p>
              </div>
              <StatusIconBadge tone="error" icon={AlertTriangle} />
            </div>
          </div>
        </Card>
      </div>

      {/* Filters */}
      <div className="flex flex-col sm:flex-row gap-4">
        <div className="relative flex-1">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400 dark:text-neutral-500" />
          <Input placeholder="Search shadow accounts..." value={searchTerm} onChange={(e) => setSearchTerm(e.target.value)} className="pl-10" />
        </div>
        <select value={selectedCurrency} onChange={(e) => setSelectedCurrency(e.target.value)}
          className="px-3 py-2 border border-neutral-300 dark:border-primary-700 rounded-lg bg-white dark:bg-primary-900">
          <option value="">All Currencies</option>
          {stats.currencies.map(c => <option key={c} value={c}>{c}</option>)}
        </select>
      </div>

      {/* Cards Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        {filteredShadows.map(shadow => (
          <ShadowCard 
            key={shadow.id} 
            shadow={shadow} 
            onSync={handleSync}
            onView={(s) => { setSelectedShadow(s); setShowDetailModal(true); }}
            onEdit={(s) => { setSelectedShadow(s); setShowDetailModal(true); }}
            onAttachEntity={handleAttachEntity}
            syncing={syncingIds.has(shadow.id)} 
          />
        ))}
      </div>

      {filteredShadows.length === 0 && !loading && (
        <div className="text-center py-12 bg-white dark:bg-primary-900 rounded-xl border">
          <Layers className="w-12 h-12 text-neutral-300 dark:text-neutral-600 mx-auto mb-4" />
          <p className="text-neutral-500 dark:text-neutral-400">No shadow accounts found</p>
          <Button className="mt-4" onClick={() => setShowCreateModal(true)}>
            <Plus className="w-4 h-4 mr-2" />Create Shadow Account
          </Button>
        </div>
      )}

      {/* Create Modal */}
      <CreateShadowModal
        isOpen={showCreateModal}
        onClose={() => setShowCreateModal(false)}
        onSuccess={handleCreateSuccess}
        corporateId={selectedCorporateId}
        physicalAccounts={physicalAccounts}
      />

      {/* Attach to Entity Modal (Step 4) */}
      <AttachToEntityModal
        isOpen={showAttachModal}
        onClose={() => { setShowAttachModal(false); setSelectedShadow(null); }}
        onSuccess={handleCreateSuccess}
        shadow={selectedShadow}
        corporateId={selectedCorporateId}
      />

      {/* Detail Modal */}
      <Modal isOpen={showDetailModal} onClose={() => setShowDetailModal(false)} title="Shadow Account Details" size="lg">
        {selectedShadow && (
          <div className="p-4 space-y-6">
            <div className="flex items-center gap-4">
              <div className="w-14 h-14 rounded-xl bg-info-100 dark:bg-info-500/20 flex items-center justify-center">
                <Layers className="w-7 h-7 text-info-600 dark:text-info-300" />
              </div>
              <div className="flex-1">
                <h3 className="text-xl font-semibold">{selectedShadow.vaName}</h3>
                <p className="text-sm text-neutral-500 dark:text-neutral-400 font-mono">{selectedShadow.vaNumber}</p>
                {selectedShadow.parentVaNumber && (
                  <div className="flex items-center gap-1 text-xs text-cat-1 mt-1">
                    <GitBranch className="w-3 h-3" />
                    <span>Under: {selectedShadow.parentVaNumber}</span>
                  </div>
                )}
              </div>
              <Badge variant={selectedShadow.status === 'ACTIVE' ? 'success' : 'neutral'}>
                {selectedShadow.status}
              </Badge>
            </div>

            {/* Owner Entity */}
            {selectedShadow.primaryOwner && (
              <div className="p-3 bg-info-50 dark:bg-info-500/10 rounded-lg">
                <div className="flex items-center gap-2">
                  <User className="w-4 h-4 text-info-600 dark:text-info-300" />
                  <span className="text-sm font-medium text-info-800 dark:text-info-300">Owner Entity</span>
                </div>
                <p className="text-sm text-info-700 dark:text-info-300 mt-1">
                  {selectedShadow.primaryOwner.entityName} ({selectedShadow.primaryOwner.entityCode})
                </p>
              </div>
            )}

            <div className="bg-neutral-50 dark:bg-primary-950 rounded-xl p-4">
              <h4 className="text-sm font-semibold mb-3 flex items-center gap-2">
                <Building2 className="w-4 h-4" />Linked Bank Account
              </h4>
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <p className="text-xs text-neutral-500 dark:text-neutral-400">Bank Name</p>
                  <p className="font-medium">{selectedShadow.bankName}</p>
                </div>
                <div>
                  <p className="text-xs text-neutral-500 dark:text-neutral-400">SWIFT/BIC</p>
                  <p className="font-mono text-sm">{selectedShadow.bankSwift}</p>
                </div>
                <div className="col-span-2">
                  <p className="text-xs text-neutral-500 dark:text-neutral-400">IBAN</p>
                  <p className="font-mono text-sm">{selectedShadow.bankIban}</p>
                </div>
              </div>
            </div>

            <div className="grid grid-cols-2 gap-4">
              <div className="bg-success-50 dark:bg-success-500/10 rounded-xl p-4">
                <p className="text-sm text-neutral-600 dark:text-neutral-300">Bank Balance</p>
                <p className="stat-value-sm text-success-600 dark:text-success-300">
                  {formatCurrency(selectedShadow.bankBalance, selectedShadow.currencyCode)}
                </p>
              </div>
              <div className="bg-info-50 dark:bg-info-500/10 rounded-xl p-4">
                <p className="text-sm text-neutral-600 dark:text-neutral-300">Available Balance</p>
                <p className="stat-value-sm text-info-600 dark:text-info-300">
                  {formatCurrency(selectedShadow.bankAvailableBalance, selectedShadow.currencyCode)}
                </p>
              </div>
            </div>
            
            <div className="flex justify-end gap-2 pt-4 border-t">
              <Button variant="ghost" onClick={() => setShowDetailModal(false)}>Close</Button>
              <Button variant="outline" onClick={() => { handleAttachEntity(selectedShadow); setShowDetailModal(false); }}>
                <UserPlus className="w-4 h-4 mr-2" />Attach to Entity
              </Button>
              <Button variant="outline" onClick={() => handleSync(selectedShadow.id)}>
                <RefreshCw className="w-4 h-4 mr-2" />Sync Now
              </Button>
            </div>
          </div>
        )}
      </Modal>

      {/* Sync Result Modal */}
      <Modal isOpen={showSyncResultModal} onClose={() => setShowSyncResultModal(false)} title="Sync Complete" size="sm">
        {syncResult && (
          <div className="p-4 space-y-4">
            <div className="text-center">
              <div className="w-16 h-16 rounded-full bg-success-100 dark:bg-success-500/20 flex items-center justify-center mx-auto mb-4">
                <CheckCircle2 className="w-8 h-8 text-success-600 dark:text-success-300" />
              </div>
              <h3 className="text-lg font-semibold">{syncResult.vaNumber}</h3>
              <p className="text-sm text-neutral-500 dark:text-neutral-400">Balance synchronized successfully</p>
            </div>
            <div className="space-y-3">
              <div className="flex justify-between p-3 bg-neutral-50 dark:bg-primary-950 rounded-lg">
                <span className="text-sm text-neutral-600 dark:text-neutral-300">Previous</span>
                <span className="font-medium">{formatCurrency(syncResult.previousBalance, 'AED')}</span>
              </div>
              <div className="flex justify-between p-3 bg-neutral-50 dark:bg-primary-950 rounded-lg">
                <span className="text-sm text-neutral-600 dark:text-neutral-300">New</span>
                <span className="font-bold">{formatCurrency(syncResult.newBalance, 'AED')}</span>
              </div>
              <div className={cn(
                "flex justify-between p-3 rounded-lg",
                syncResult.delta >= 0 ? "bg-success-50 dark:bg-success-500/10" : "bg-error-50 dark:bg-error-500/10"
              )}>
                <span className="text-sm text-neutral-600 dark:text-neutral-300">Change</span>
                <span className={cn(
                  "font-bold flex items-center gap-1",
                  syncResult.delta >= 0 ? "text-success-600 dark:text-success-300" : "text-error-600 dark:text-error-300"
                )}>
                  {syncResult.delta >= 0 ? <ArrowUpRight className="w-4 h-4" /> : <ArrowDownRight className="w-4 h-4" />}
                  {syncResult.delta >= 0 ? '+' : ''}{formatCurrency(syncResult.delta, 'AED')}
                </span>
              </div>
            </div>
            <Button className="w-full" onClick={() => setShowSyncResultModal(false)}>Done</Button>
          </div>
        )}
      </Modal>
    </Page>
  );
};

export default ShadowAccountsPage;