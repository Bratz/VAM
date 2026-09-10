import React, { useState, useEffect, useCallback } from 'react';
import {
  Link2, Plus, Search, Loader2,
  Eye, Shield, Users, Building2, Layers,
  CheckCircle2, Clock, Ban, RefreshCw, UserCheck, Lock,
  ArrowRightLeft, Key, Percent, AlertTriangle, X,
  ChevronDown, Wallet, Globe, MapPin,
} from 'lucide-react';
import { Card, Button, Badge, Input, StatTile, DataTable } from '../components/ui';
import { HeroMetricCard } from '../components/ui/HeroMetricCard';
import { Modal } from '../components/ui/enhanced';
import { formatCurrency, cn, formatDate } from '../utils';
import {
  legalEntityApi,
  accountAttachmentApi,
  balanceStructureApi,
  virtualAccountsApi,
  LegalEntity,
  AccountAttachment,
  AccountAttachmentStatistics,
  BalanceHierarchyNode,
} from '../services/api';
import { usePageHeaderActions } from '../context/PageHeaderContext';
import { Page } from '../components/layout/Page';
import { PageHeader } from '../components/layout/PageHeader';
import { StatStrip } from '../components/layout/StatStrip';
import { ScopeSelector } from '../components/layout/ScopeSelector';

// ============================================================================
// TYPES
// ============================================================================

type RelationshipType = 'OWNER' | 'BENEFICIARY' | 'AUTHORIZED' | 'GUARANTOR' | 'COLLATERAL';
type AttachmentStatus = 'ACTIVE' | 'PENDING_APPROVAL' | 'SUSPENDED' | 'EXPIRED' | 'TERMINATED';

interface VirtualAccountOption {
  id: string;
  vaNumber: string;
  vaName: string;
  currencyCode: string;
}

interface CreateAttachmentForm {
  virtualAccountId: string;
  legalEntityId: string;
  relationshipType: RelationshipType;
  isPrimary: boolean;
  description: string;
  effectiveFrom: string;
  effectiveTo: string;
  maxTransactionAmount: string;
  dailyLimit: string;
  requiresDualAuth: boolean;
  collateralPercent: string;
  securedFacilityId: string;
}

// ============================================================================
// CONSTANTS
// ============================================================================

const RELATIONSHIP_CONFIG: Record<RelationshipType, { 
  label: string; 
  icon: React.FC<any>; 
  color: string; 
  bgColor: string;
  description: string;
}> = {
  OWNER: { label: 'Owner', icon: Key, color: 'text-info-600 dark:text-info-300', bgColor: 'bg-info-50 dark:bg-info-500/10', description: 'Primary owner with full control' },
  BENEFICIARY: { label: 'Beneficiary', icon: Users, color: 'text-success-600 dark:text-success-300', bgColor: 'bg-success-50 dark:bg-success-500/10', description: 'Receives benefits from the account' },
  AUTHORIZED: { label: 'Authorized', icon: UserCheck, color: 'text-cat-2', bgColor: 'bg-cat-2-soft dark:bg-cat-2/15', description: 'Authorized to perform transactions within limits' },
  GUARANTOR: { label: 'Guarantor', icon: Shield, color: 'text-warning-600 dark:text-warning-300', bgColor: 'bg-warning-50 dark:bg-warning-500/10', description: 'Guarantees obligations on the account' },
  COLLATERAL: { label: 'Collateral', icon: Lock, color: 'text-error-600 dark:text-error-300', bgColor: 'bg-error-50 dark:bg-error-500/10', description: 'Account used as collateral for facilities' },
};

const STATUS_CONFIG: Record<AttachmentStatus, { label: string; variant: 'success' | 'warning' | 'error' | 'neutral' }> = {
  ACTIVE: { label: 'Active', variant: 'success' },
  PENDING_APPROVAL: { label: 'Pending', variant: 'warning' },
  SUSPENDED: { label: 'Suspended', variant: 'error' },
  EXPIRED: { label: 'Expired', variant: 'neutral' },
  TERMINATED: { label: 'Terminated', variant: 'error' },
};

const INITIAL_FORM: CreateAttachmentForm = {
  virtualAccountId: '',
  legalEntityId: '',
  relationshipType: 'OWNER',
  isPrimary: false,
  description: '',
  effectiveFrom: new Date().toISOString().split('T')[0],
  effectiveTo: '',
  maxTransactionAmount: '',
  dailyLimit: '',
  requiresDualAuth: false,
  collateralPercent: '',
  securedFacilityId: '',
};

// ============================================================================
// ACCOUNT/NODE PICKER COMPONENT
// ============================================================================

interface AccountNodePickerProps {
  selectedId: string;
  onSelect: (id: string) => void;
  label: string;
}

const AccountNodePicker: React.FC<AccountNodePickerProps> = ({ selectedId, onSelect, label }) => {
  const [isOpen, setIsOpen] = useState(false);
  const [searchTerm, setSearchTerm] = useState('');
  const [loading, setLoading] = useState(false);
  const [virtualAccounts, setVirtualAccounts] = useState<VirtualAccountOption[]>([]);
  const [hierarchyNodes, setHierarchyNodes] = useState<BalanceHierarchyNode[]>([]);
  const [activeTab, setActiveTab] = useState<'va' | 'hierarchy'>('va');

  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const vaRes = await virtualAccountsApi.getAll(0, 100);
      if (vaRes.success && vaRes.data) {
        const accounts = Array.isArray(vaRes.data) ? vaRes.data : [];
        setVirtualAccounts(accounts.map((va: any) => ({
          id: va.id,
          vaNumber: va.vaNumber,
          vaName: va.vaName,
          currencyCode: va.currencyCode,
        })));
      }

      const hierarchyRes = await balanceStructureApi.getHierarchy();
      if (hierarchyRes.success && hierarchyRes.data) {
        const flattenNodes = (node: BalanceHierarchyNode, result: BalanceHierarchyNode[] = []): BalanceHierarchyNode[] => {
          if (['GROUP', 'REGION', 'ENTITY'].includes(node.type)) {
            result.push(node);
          }
          if (node.children) {
            node.children.forEach(child => flattenNodes(child, result));
          }
          return result;
        };
        setHierarchyNodes(flattenNodes(hierarchyRes.data));
      }
    } catch (err) {
      console.error('Failed to load accounts/hierarchy:', err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (isOpen) loadData();
  }, [isOpen, loadData]);

  const filteredVAs = virtualAccounts.filter(va => 
    !searchTerm || va.vaNumber.toLowerCase().includes(searchTerm.toLowerCase()) || va.vaName.toLowerCase().includes(searchTerm.toLowerCase())
  );

  const filteredNodes = hierarchyNodes.filter(node =>
    !searchTerm || node.name.toLowerCase().includes(searchTerm.toLowerCase())
  );

  const getSelectedLabel = () => {
    if (!selectedId) return 'Select Account/Node...';
    const va = virtualAccounts.find(v => v.id === selectedId);
    if (va) return `${va.vaNumber} - ${va.vaName}`;
    const node = hierarchyNodes.find(n => n.id === selectedId);
    if (node) return `${node.name} (${node.type})`;
    return selectedId;
  };

  const getNodeIcon = (type: string) => {
    switch (type) {
      case 'GROUP': return Globe;
      case 'REGION': return MapPin;
      case 'ENTITY': return Building2;
      default: return Layers;
    }
  };

  return (
    <div className="relative">
      <label className="field-label block mb-1">{label}</label>
      <button
        type="button"
        onClick={() => setIsOpen(!isOpen)}
        className="w-full flex items-center justify-between px-3 py-2 border border-neutral-300 rounded-lg bg-white hover:border-primary-400 dark:border-primary-700 dark:bg-primary-900"
      >
        <span className={cn('text-sm', selectedId ? 'text-primary-900 dark:text-neutral-50' : 'text-neutral-400 dark:text-neutral-500')}>
          {getSelectedLabel()}
        </span>
        <ChevronDown className={cn('w-4 h-4 text-neutral-400 dark:text-neutral-500', isOpen && 'rotate-180')} />
      </button>

      {isOpen && (
        <div className="absolute z-50 top-full left-0 right-0 mt-1 bg-white border border-neutral-200 rounded-lg shadow-lg max-h-80 overflow-hidden dark:bg-primary-900 dark:border-primary-800">
          <div className="p-2 border-b">
            <div className="relative">
              <Search className="absolute left-2 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400 dark:text-neutral-500" />
              <input
                type="text"
                placeholder="Search..."
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                className="w-full pl-8 pr-3 py-1.5 text-sm border border-neutral-200 rounded dark:border-primary-800"
                autoFocus
              />
            </div>
          </div>

          <div className="flex border-b">
            <button
              type="button"
              onClick={() => setActiveTab('va')}
              className={cn('flex-1 px-4 py-2 text-sm font-medium', activeTab === 'va' ? 'text-primary-600 border-b-2 border-primary-600 dark:text-primary-200' : 'text-neutral-500 dark:text-neutral-400')}
            >
              <Wallet className="w-4 h-4 inline mr-1" />Virtual Accounts
            </button>
            <button
              type="button"
              onClick={() => setActiveTab('hierarchy')}
              className={cn('flex-1 px-4 py-2 text-sm font-medium', activeTab === 'hierarchy' ? 'text-primary-600 border-b-2 border-primary-600 dark:text-primary-200' : 'text-neutral-500 dark:text-neutral-400')}
            >
              <Layers className="w-4 h-4 inline mr-1" />Aggregation Levels
            </button>
          </div>

          <div className="max-h-48 overflow-y-auto">
            {loading ? (
              <div className="flex items-center justify-center py-8">
                <Loader2 className="w-5 h-5 animate-spin text-primary-600 dark:text-primary-200" />
              </div>
            ) : activeTab === 'va' ? (
              filteredVAs.length === 0 ? (
                <div className="text-center py-4 text-sm text-neutral-500 dark:text-neutral-400">No accounts found</div>
              ) : (
                filteredVAs.map(va => (
                  <button
                    key={va.id}
                    type="button"
                    onClick={() => { onSelect(va.id); setIsOpen(false); }}
                    className={cn('w-full flex items-center gap-3 px-3 py-2 hover:bg-neutral-50 text-left dark:hover:bg-primary-800/50', selectedId === va.id && 'bg-primary-50 dark:bg-primary-800/40')}
                  >
                    <div className="p-1.5 rounded bg-success-50 dark:bg-success-500/10"><Wallet className="w-3 h-3 text-success-600 dark:text-success-300" /></div>
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-medium text-primary-900 truncate dark:text-neutral-50">{va.vaNumber}</p>
                      <p className="text-xs text-neutral-500 truncate dark:text-neutral-400">{va.vaName}</p>
                    </div>
                    <Badge variant="neutral" size="sm">{va.currencyCode}</Badge>
                  </button>
                ))
              )
            ) : (
              filteredNodes.length === 0 ? (
                <div className="text-center py-4 text-sm text-neutral-500 dark:text-neutral-400">No hierarchy nodes found</div>
              ) : (
                filteredNodes.map(node => {
                  const Icon = getNodeIcon(node.type);
                  return (
                    <button
                      key={node.id}
                      type="button"
                      onClick={() => { onSelect(node.id); setIsOpen(false); }}
                      className={cn('w-full flex items-center gap-3 px-3 py-2 hover:bg-neutral-50 text-left dark:hover:bg-primary-800/50', selectedId === node.id && 'bg-primary-50 dark:bg-primary-800/40')}
                      style={{ paddingLeft: `${(node.level || 0) * 12 + 12}px` }}
                    >
                      <div className={cn('p-1.5 rounded', node.type === 'GROUP' ? 'bg-primary-900' : 'bg-cat-1/10 dark:bg-cat-1/15')}>
                        <Icon className={cn('w-3 h-3', node.type === 'GROUP' ? 'text-white' : 'text-cat-1')} />
                      </div>
                      <div className="flex-1 min-w-0">
                        <p className="text-sm font-medium text-primary-900 truncate dark:text-neutral-50">{node.name}</p>
                        <p className="text-xs text-neutral-500 dark:text-neutral-400">{node.type} • Level {node.level}</p>
                      </div>
                      <Badge variant="neutral" size="sm">{node.currencyCode}</Badge>
                    </button>
                  );
                })
              )
            )}
          </div>
          <div className="p-2 border-t bg-neutral-50 dark:bg-primary-950">
            <button type="button" onClick={() => setIsOpen(false)} className="w-full text-center text-sm text-neutral-500 hover:text-neutral-700 dark:text-neutral-400 dark:hover:text-neutral-200">Close</button>
          </div>
        </div>
      )}
    </div>
  );
};

// ============================================================================
// ENTITY PICKER COMPONENT
// ============================================================================

interface LegalEntityPickerProps {
  selectedId: string;
  onSelect: (id: string) => void;
  label: string;
  entities: LegalEntity[];
  loading: boolean;
}

const LegalEntityPicker: React.FC<LegalEntityPickerProps> = ({ selectedId, onSelect, label, entities, loading }) => {
  const [isOpen, setIsOpen] = useState(false);
  const [searchTerm, setSearchTerm] = useState('');

  const filteredEntities = entities.filter(e => !searchTerm || e.entityCode.toLowerCase().includes(searchTerm.toLowerCase()) || e.entityName.toLowerCase().includes(searchTerm.toLowerCase()));
  const selectedEntity = entities.find(e => e.id === selectedId);

  return (
    <div className="relative">
      <label className="field-label block mb-1">{label}</label>
      <button
        type="button"
        onClick={() => setIsOpen(!isOpen)}
        className="w-full flex items-center justify-between px-3 py-2 border border-neutral-300 rounded-lg bg-white hover:border-primary-400 dark:border-primary-700 dark:bg-primary-900"
      >
        <span className={cn('text-sm', selectedId ? 'text-primary-900 dark:text-neutral-50' : 'text-neutral-400 dark:text-neutral-500')}>
          {selectedEntity ? `${selectedEntity.entityName} (${selectedEntity.entityCode})` : 'Select Entity...'}
        </span>
        <ChevronDown className={cn('w-4 h-4 text-neutral-400 dark:text-neutral-500', isOpen && 'rotate-180')} />
      </button>

      {isOpen && (
        <div className="absolute z-50 top-full left-0 right-0 mt-1 bg-white border border-neutral-200 rounded-lg shadow-lg max-h-64 overflow-hidden dark:bg-primary-900 dark:border-primary-800">
          <div className="p-2 border-b">
            <div className="relative">
              <Search className="absolute left-2 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400 dark:text-neutral-500" />
              <input type="text" placeholder="Search entities..." value={searchTerm} onChange={(e) => setSearchTerm(e.target.value)} className="w-full pl-8 pr-3 py-1.5 text-sm border border-neutral-200 rounded dark:border-primary-800" autoFocus />
            </div>
          </div>
          <div className="max-h-48 overflow-y-auto">
            {loading ? (
              <div className="flex items-center justify-center py-8"><Loader2 className="w-5 h-5 animate-spin text-primary-600 dark:text-primary-200" /></div>
            ) : filteredEntities.length === 0 ? (
              <div className="text-center py-4 text-sm text-neutral-500 dark:text-neutral-400">No entities found</div>
            ) : (
              filteredEntities.map(entity => (
                <button key={entity.id} type="button" onClick={() => { onSelect(entity.id); setIsOpen(false); }} className={cn('w-full flex items-center gap-3 px-3 py-2 hover:bg-neutral-50 text-left dark:hover:bg-primary-800/50', selectedId === entity.id && 'bg-primary-50 dark:bg-primary-800/40')}>
                  <div className="p-1.5 rounded bg-cat-1/10 dark:bg-cat-1/15"><Building2 className="w-3 h-3 text-cat-1" /></div>
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-medium text-primary-900 truncate dark:text-neutral-50">{entity.entityName}</p>
                    <p className="text-xs text-neutral-500 dark:text-neutral-400">{entity.entityCode} • {entity.entityType}</p>
                  </div>
                  <Badge variant={entity.status === 'ACTIVE' ? 'success' : 'neutral'} size="sm">{entity.status}</Badge>
                </button>
              ))
            )}
          </div>
        </div>
      )}
    </div>
  );
};

// ============================================================================
// MAIN COMPONENT
// ============================================================================

const AccountAttachmentsPage: React.FC = () => {
  const [attachments, setAttachments] = useState<AccountAttachment[]>([]);
  const [entities, setEntities] = useState<LegalEntity[]>([]);
  const [selectedEntityId, setSelectedEntityId] = useState('');
  const [loading, setLoading] = useState(true);
  const [entitiesLoading, setEntitiesLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [searchTerm, setSearchTerm] = useState('');
  const [filterType, setFilterType] = useState<RelationshipType | ''>('');
  const [filterStatus, setFilterStatus] = useState<AttachmentStatus | ''>('');
  const [statistics, setStatistics] = useState<AccountAttachmentStatistics | null>(null);

  const [showCreateModal, setShowCreateModal] = useState(false);
  const [showDetailModal, setShowDetailModal] = useState(false);
  const [showTransferModal, setShowTransferModal] = useState(false);
  const [selectedAttachment, setSelectedAttachment] = useState<AccountAttachment | null>(null);
  const [createForm, setCreateForm] = useState<CreateAttachmentForm>(INITIAL_FORM);
  const [submitting, setSubmitting] = useState(false);
  const [transferForm, setTransferForm] = useState({ newOwnerId: '', transferReason: '' });

  const loadEntities = useCallback(async () => {
    setEntitiesLoading(true);
    try {
      const res = await legalEntityApi.getAll();
      if (res.success && res.data) {
        const data = Array.isArray(res.data) ? res.data : [];
        setEntities(data);
        if (data.length > 0 && !selectedEntityId) setSelectedEntityId(data[0].id);
      }
    } catch (err) {
      console.error('Failed to load entities:', err);
      setError('Failed to load legal entities');
    } finally {
      setEntitiesLoading(false);
    }
  }, [selectedEntityId]);

  const loadAttachments = useCallback(async () => {
    if (!selectedEntityId) return;
    setLoading(true);
    setError(null);
    try {
      const res = await accountAttachmentApi.getByLegalEntity(selectedEntityId);
      if (res.success && res.data) {
        setAttachments(Array.isArray(res.data) ? res.data : []);
      } else {
        setAttachments([]);
      }
    } catch (err) {
      console.error('Failed to load attachments:', err);
      setError('Failed to load attachments. Please check API connection.');
      setAttachments([]);
    } finally {
      setLoading(false);
    }
  }, [selectedEntityId]);

  useEffect(() => { loadEntities(); }, [loadEntities]);
  useEffect(() => { if (selectedEntityId) loadAttachments(); }, [selectedEntityId, loadAttachments]);

  const handleApprove = async (id: string) => {
    setSubmitting(true);
    try {
      await accountAttachmentApi.approve(id, 'Current User');
      await loadAttachments();
    } catch (err) {
      setError('Failed to approve attachment');
    } finally {
      setSubmitting(false);
    }
  };

  const handleSuspend = async (id: string) => {
    setSubmitting(true);
    try {
      await accountAttachmentApi.suspend(id);
      await loadAttachments();
    } catch (err) {
      setError('Failed to suspend attachment');
    } finally {
      setSubmitting(false);
    }
  };

  const handleTerminate = async (id: string) => {
    if (!confirm('Are you sure you want to terminate this attachment?')) return;
    setSubmitting(true);
    try {
      await accountAttachmentApi.terminate(id);
      await loadAttachments();
      setShowDetailModal(false);
    } catch (err) {
      setError('Failed to terminate attachment');
    } finally {
      setSubmitting(false);
    }
  };

  const handleCreate = async () => {
    if (!createForm.virtualAccountId || !createForm.legalEntityId) {
      setError('Please select both a Virtual Account/Node and a Legal Entity');
      return;
    }
    setSubmitting(true);
    try {
      const payload: any = {
        virtualAccountId: createForm.virtualAccountId,
        legalEntityId: createForm.legalEntityId,
        relationshipType: createForm.relationshipType,
        isPrimary: createForm.isPrimary,
        description: createForm.description || undefined,
        effectiveFrom: createForm.effectiveFrom || undefined,
        effectiveTo: createForm.effectiveTo || undefined,
        requiresApproval: createForm.relationshipType !== 'OWNER',
      };
      if (createForm.relationshipType === 'AUTHORIZED') {
        if (createForm.maxTransactionAmount) payload.maxTransactionAmount = parseFloat(createForm.maxTransactionAmount);
        if (createForm.dailyLimit) payload.dailyLimit = parseFloat(createForm.dailyLimit);
        payload.requiresDualAuth = createForm.requiresDualAuth;
      }
      if (createForm.relationshipType === 'COLLATERAL') {
        if (createForm.collateralPercent) payload.collateralPercent = parseFloat(createForm.collateralPercent);
        payload.securedFacilityId = createForm.securedFacilityId || undefined;
      }
      await accountAttachmentApi.create(payload);
      setShowCreateModal(false);
      setCreateForm(INITIAL_FORM);
      await loadAttachments();
    } catch (err) {
      setError('Failed to create attachment');
    } finally {
      setSubmitting(false);
    }
  };

  const handleTransferOwnership = async () => {
    if (!selectedAttachment || !transferForm.newOwnerId) return;
    setSubmitting(true);
    try {
      await accountAttachmentApi.transferOwnership(selectedAttachment.virtualAccountId, transferForm.newOwnerId, transferForm.transferReason);
      setShowTransferModal(false);
      setTransferForm({ newOwnerId: '', transferReason: '' });
      await loadAttachments();
    } catch (err) {
      setError('Failed to transfer ownership');
    } finally {
      setSubmitting(false);
    }
  };

  const filteredAttachments = attachments.filter(a => {
    const matchesSearch = !searchTerm || a.vaNumber?.toLowerCase().includes(searchTerm.toLowerCase()) || a.entityName?.toLowerCase().includes(searchTerm.toLowerCase());
    return matchesSearch && (!filterType || a.relationshipType === filterType) && (!filterStatus || a.status === filterStatus);
  });

  const displayStats = statistics || {
    totalAttachments: attachments.length,
    activeAttachments: attachments.filter(a => a.status === 'ACTIVE').length,
    pendingApproval: attachments.filter(a => a.status === 'PENDING_APPROVAL').length,
    ownerRelationships: attachments.filter(a => a.relationshipType === 'OWNER').length,
    authorizedRelationships: attachments.filter(a => a.relationshipType === 'AUTHORIZED').length,
    collateralRelationships: attachments.filter(a => a.relationshipType === 'COLLATERAL').length,
    expiringIn30Days: 0,
  };

  // Toolbar actions in the Aperture Layout header — same pattern as the
  // rest of Aperture's conformed pages (removes the floating in-page row).
  usePageHeaderActions(
    () => (
      <>
        <Button variant="outline" size="sm" onClick={() => loadAttachments()} disabled={loading}>
          <RefreshCw className={cn("w-4 h-4 mr-1", loading && "animate-spin")} />Refresh
        </Button>
        <Button size="sm" onClick={() => { setCreateForm({ ...INITIAL_FORM, legalEntityId: selectedEntityId }); setShowCreateModal(true); }}>
          <Plus className="w-4 h-4 mr-1" />New Attachment
        </Button>
      </>
    ),
    [loading, selectedEntityId]
  );

  if (entitiesLoading) return <div className="flex items-center justify-center h-96"><Loader2 className="w-8 h-8 animate-spin text-primary-600 dark:text-primary-200" /></div>;

  return (
    <Page>
      {/* In-body identity (Phase 10) — aligns with the rest of Aperture's
          conformed pages. Refresh / New Attachment CTAs live in the Aperture
          Layout header via usePageHeaderActions below. */}
      <PageHeader
        title="Account Linking"
        description="Manage VA-to-legal-entity attachments. Link physical and virtual accounts to entities with relationship roles (owner / authorized / collateral)."
      />

      {/* Entity-only picker — uses the shared ScopeSelector primitive
          in `entity-only` mode. Legal-entity scope is the only filter
          this page exposes; corporate is implicit. */}
      <ScopeSelector
        mode="entity-only"
        legalEntities={entities}
        selectedEntityId={selectedEntityId}
        onEntityChange={setSelectedEntityId}
        loading={loading}
      />

      {error && (
        <div className="bg-error-50 border border-error-200 rounded-xl p-4 dark:bg-error-500/10 dark:border-error-500/30">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2 text-error-700 dark:text-error-300"><AlertTriangle className="w-5 h-5" /><span>{error}</span></div>
            <button onClick={() => setError(null)} className="text-error-400 hover:text-error-600"><X className="w-4 h-4" /></button>
          </div>
        </div>
      )}

      {/* Headline figures — Total + Active attachments. Matches the
          hero+strip hierarchy used elsewhere; these six metrics previously
          competed as an equal-weight strip with no visual hierarchy. */}
      <HeroMetricCard
        primary={{
          label: 'Total Attachments',
          value: displayStats.totalAttachments,
        }}
        secondary={{
          label: 'Active',
          value: displayStats.activeAttachments,
          sub: `${displayStats.pendingApproval} pending approval`,
        }}
        icon={<Link2 className="w-7 h-7 text-accent-600 dark:text-accent-300" />}
      />

      {/* Operational metrics — secondary strip below the hero. */}
      <StatStrip>
        {[
          { label: 'Pending', value: displayStats.pendingApproval, icon: Clock, tone: 'warning' as const },
          { label: 'Owner', value: displayStats.ownerRelationships, icon: Key, tone: 'info' as const },
          { label: 'Authorized', value: displayStats.authorizedRelationships, icon: UserCheck, tone: 'accent' as const },
          { label: 'Collateral', value: displayStats.collateralRelationships, icon: Lock, tone: 'danger' as const },
        ].map((stat, idx) => (
          <StatTile
            key={stat.label}
            layout="row"
            tone={stat.tone}
            label={stat.label}
            value={stat.value}
            icon={<stat.icon className="w-5 h-5" />}
            delay={`${0.15 + idx * 0.05}s`}
          />
        ))}
      </StatStrip>

      {/* Filters */}
      <Card padding="sm" className="animate-fade-in" style={{ animationDelay: '0.4s' }}>
        <div className="flex flex-wrap items-center gap-4">
          <div className="flex-1 min-w-[200px] relative">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400 dark:text-neutral-500" />
            <Input placeholder="Search..." value={searchTerm} onChange={(e) => setSearchTerm(e.target.value)} className="pl-10" />
          </div>
          <select value={filterType} onChange={(e) => setFilterType(e.target.value as RelationshipType | '')} className="px-3 py-2 border border-neutral-200 rounded-lg bg-white text-sm focus:ring-2 focus:ring-primary-500 dark:border-primary-800 dark:bg-primary-900">
            <option value="">All Types</option>
            {Object.entries(RELATIONSHIP_CONFIG).map(([k, v]) => <option key={k} value={k}>{v.label}</option>)}
          </select>
          <select value={filterStatus} onChange={(e) => setFilterStatus(e.target.value as AttachmentStatus | '')} className="px-3 py-2 border border-neutral-200 rounded-lg bg-white text-sm focus:ring-2 focus:ring-primary-500 dark:border-primary-800 dark:bg-primary-900">
            <option value="">All Statuses</option>
            {Object.entries(STATUS_CONFIG).map(([k, v]) => <option key={k} value={k}>{v.label}</option>)}
          </select>
          {(filterType || filterStatus) && <Button variant="ghost" size="sm" onClick={() => { setFilterType(''); setFilterStatus(''); }}><X className="w-4 h-4 mr-1" />Clear</Button>}
        </div>
      </Card>

      {/* Table */}
      <Card padding="none" className="animate-fade-in" style={{ animationDelay: '0.45s' }}>
        <DataTable
          data={filteredAttachments}
          keyExtractor={(att) => att.id}
          loading={loading}
          onRowClick={(att) => { setSelectedAttachment(att); setShowDetailModal(true); }}
          emptyIcon={<Link2 className="w-12 h-12 text-neutral-300 dark:text-neutral-600" />}
          emptyTitle="No attachments found"
          emptyDescription="Try adjusting your filters or create a new attachment."
          columns={[
            {
              key: 'vaNumber',
              header: 'Account / Entity',
              render: (_, att) => {
                const cfg = RELATIONSHIP_CONFIG[att.relationshipType as RelationshipType];
                const Icon = cfg?.icon || Link2;
                return (
                  <div className="flex items-center gap-3">
                    <div className={cn("w-10 h-10 rounded-xl flex items-center justify-center shrink-0", cfg?.bgColor)}><Icon className={cn("w-5 h-5", cfg?.color)} /></div>
                    <div className="min-w-0"><p className="text-sm font-semibold text-primary-900 truncate dark:text-neutral-50">{att.vaNumber || att.virtualAccountId}</p><p className="text-xs text-neutral-500 truncate dark:text-neutral-400">{att.entityName || 'Unknown Entity'}</p></div>
                  </div>
                );
              },
            },
            {
              key: 'relationshipType',
              header: 'Relationship',
              render: (_, att) => {
                const cfg = RELATIONSHIP_CONFIG[att.relationshipType as RelationshipType];
                return (
                  <div className="flex items-center gap-2">
                    <Badge variant="neutral" size="sm">{cfg?.label || att.relationshipType}</Badge>
                    {att.isPrimary && <Badge variant="warning" size="sm">Primary</Badge>}
                  </div>
                );
              },
            },
            { key: 'status', header: 'Status', render: (_, att) => <Badge variant={STATUS_CONFIG[att.status as AttachmentStatus]?.variant} size="sm">{STATUS_CONFIG[att.status as AttachmentStatus]?.label}</Badge> },
            {
              key: 'effectiveFrom',
              header: 'Validity',
              render: (_, att) => (
                <span className="text-sm text-neutral-600 dark:text-neutral-300">
                  {formatDate(att.effectiveFrom)}{att.effectiveTo && <span className="text-xs text-neutral-400 dark:text-neutral-500"> to {formatDate(att.effectiveTo)}</span>}
                </span>
              ),
            },
            {
              key: 'limits',
              header: 'Limits',
              render: (_, att) => (
                <>
                  {att.relationshipType === 'AUTHORIZED' && att.maxTransactionAmount && <span className="text-xs">Max: {formatCurrency(att.maxTransactionAmount, 'AED')}</span>}
                  {att.relationshipType === 'COLLATERAL' && att.collateralPercent && <span className="text-xs flex items-center gap-1"><Percent className="w-3 h-3" />{att.collateralPercent}%</span>}
                </>
              ),
            },
            {
              key: 'actions',
              header: 'Actions',
              width: '7rem',
              render: (_, att) => (
                <div className="flex items-center gap-1" onClick={(e) => e.stopPropagation()}>
                  <Button variant="ghost" size="sm" onClick={() => { setSelectedAttachment(att); setShowDetailModal(true); }}><Eye className="w-4 h-4" /></Button>
                  {att.status === 'PENDING_APPROVAL' && <Button variant="ghost" size="sm" onClick={() => handleApprove(att.id)} className="text-success-600 dark:text-success-300" disabled={submitting}><CheckCircle2 className="w-4 h-4" /></Button>}
                  {att.status === 'ACTIVE' && <Button variant="ghost" size="sm" onClick={() => handleSuspend(att.id)} className="text-warning-600 dark:text-warning-300" disabled={submitting}><Ban className="w-4 h-4" /></Button>}
                </div>
              ),
            },
          ]}
        />
      </Card>

      {/* Detail Modal */}
      <Modal isOpen={showDetailModal} onClose={() => setShowDetailModal(false)} title="Attachment Details" size="lg">
        {selectedAttachment && (
          <div className="p-4 space-y-6">
            <div className="flex items-start justify-between">
              <div className="flex items-center gap-3">
                <div className={cn("w-12 h-12 rounded-lg flex items-center justify-center", RELATIONSHIP_CONFIG[selectedAttachment.relationshipType as RelationshipType]?.bgColor)}>
                  {React.createElement(RELATIONSHIP_CONFIG[selectedAttachment.relationshipType as RelationshipType]?.icon || Link2, { className: cn("w-6 h-6", RELATIONSHIP_CONFIG[selectedAttachment.relationshipType as RelationshipType]?.color) })}
                </div>
                <div><h3 className="text-lg font-semibold">{selectedAttachment.vaNumber}</h3><p className="text-sm text-neutral-500 dark:text-neutral-400">{selectedAttachment.entityName}</p></div>
              </div>
              <Badge variant={STATUS_CONFIG[selectedAttachment.status as AttachmentStatus]?.variant}>{STATUS_CONFIG[selectedAttachment.status as AttachmentStatus]?.label}</Badge>
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div className="bg-neutral-50 rounded-lg p-3 dark:bg-primary-950"><p className="text-xs text-neutral-500 dark:text-neutral-400">Relationship</p><p className="font-medium">{RELATIONSHIP_CONFIG[selectedAttachment.relationshipType as RelationshipType]?.label}</p></div>
              <div className="bg-neutral-50 rounded-lg p-3 dark:bg-primary-950"><p className="text-xs text-neutral-500 dark:text-neutral-400">Primary</p><p className="font-medium">{selectedAttachment.isPrimary ? 'Yes' : 'No'}</p></div>
              <div className="bg-neutral-50 rounded-lg p-3 dark:bg-primary-950"><p className="text-xs text-neutral-500 dark:text-neutral-400">Effective From</p><p className="font-medium">{formatDate(selectedAttachment.effectiveFrom)}</p></div>
              <div className="bg-neutral-50 rounded-lg p-3 dark:bg-primary-950"><p className="text-xs text-neutral-500 dark:text-neutral-400">Effective To</p><p className="font-medium">{selectedAttachment.effectiveTo ? formatDate(selectedAttachment.effectiveTo) : 'Indefinite'}</p></div>
            </div>
            {selectedAttachment.relationshipType === 'AUTHORIZED' && (
              <div className="bg-cat-2-soft rounded-lg p-4 dark:bg-cat-2/15">
                <h4 className="text-sm font-medium text-cat-2 mb-3">Authorization Limits</h4>
                <div className="grid grid-cols-2 gap-4">
                  <div><p className="text-xs text-cat-2">Max Transaction</p><p className="font-semibold">{selectedAttachment.maxTransactionAmount ? formatCurrency(selectedAttachment.maxTransactionAmount, 'AED') : 'Unlimited'}</p></div>
                  <div><p className="text-xs text-cat-2">Daily Limit</p><p className="font-semibold">{selectedAttachment.dailyLimit ? formatCurrency(selectedAttachment.dailyLimit, 'AED') : 'Unlimited'}</p></div>
                </div>
              </div>
            )}
            {selectedAttachment.relationshipType === 'COLLATERAL' && selectedAttachment.collateralPercent && (
              <div className="bg-error-50 rounded-lg p-4 dark:bg-error-500/10">
                <h4 className="text-sm font-medium text-error-700 mb-3 dark:text-error-300">Collateral Details</h4>
                <div className="flex justify-between"><span className="text-sm text-error-600 dark:text-error-300">Collateral %</span><span className="font-semibold text-error-700 dark:text-error-300">{selectedAttachment.collateralPercent}%</span></div>
              </div>
            )}
            <div className="flex gap-3 pt-4 border-t">
              {selectedAttachment.status === 'PENDING_APPROVAL' && <Button className="flex-1" onClick={() => { handleApprove(selectedAttachment.id); setShowDetailModal(false); }} disabled={submitting}><CheckCircle2 className="w-4 h-4 mr-2" />Approve</Button>}
              {selectedAttachment.status === 'ACTIVE' && (
                <>
                  <Button variant="outline" className="flex-1" onClick={() => { handleSuspend(selectedAttachment.id); setShowDetailModal(false); }} disabled={submitting}><Ban className="w-4 h-4 mr-2" />Suspend</Button>
                  {selectedAttachment.relationshipType === 'OWNER' && <Button variant="outline" className="flex-1" onClick={() => { setShowDetailModal(false); setShowTransferModal(true); }}><ArrowRightLeft className="w-4 h-4 mr-2" />Transfer</Button>}
                </>
              )}
              <Button variant="ghost" onClick={() => handleTerminate(selectedAttachment.id)} disabled={submitting} className="text-error-600 dark:text-error-300">Terminate</Button>
              <Button variant="ghost" onClick={() => setShowDetailModal(false)}>Close</Button>
            </div>
          </div>
        )}
      </Modal>

      {/* Create Modal */}
      <Modal isOpen={showCreateModal} onClose={() => setShowCreateModal(false)} title="New Account Attachment" size="lg">
        <div className="p-4 space-y-4">
          <AccountNodePicker selectedId={createForm.virtualAccountId} onSelect={(id) => setCreateForm(p => ({ ...p, virtualAccountId: id }))} label="Virtual Account / Aggregation Node *" />
          <LegalEntityPicker selectedId={createForm.legalEntityId} onSelect={(id) => setCreateForm(p => ({ ...p, legalEntityId: id }))} label="Legal Entity *" entities={entities} loading={entitiesLoading} />
          
          <div>
            <label className="field-label block mb-2">Relationship Type</label>
            <div className="grid grid-cols-5 gap-2">
              {Object.entries(RELATIONSHIP_CONFIG).map(([key, config]) => {
                const Icon = config.icon;
                const isSelected = createForm.relationshipType === key;
                return (
                  <button key={key} type="button" onClick={() => setCreateForm(p => ({ ...p, relationshipType: key as RelationshipType }))} className={cn("flex flex-col items-center gap-1 p-3 rounded-lg border-2 transition-all", isSelected ? `${config.bgColor} ${config.color} border-current` : 'bg-white border-neutral-200 hover:border-neutral-300 dark:bg-primary-900 dark:border-primary-800 dark:hover:border-primary-700')}>
                    <Icon className="w-5 h-5" /><span className="text-xs font-medium">{config.label}</span>
                  </button>
                );
              })}
            </div>
          </div>

          <label className="flex items-center gap-2 cursor-pointer">
            <input type="checkbox" checked={createForm.isPrimary} onChange={(e) => setCreateForm(p => ({ ...p, isPrimary: e.target.checked }))} className="rounded text-primary-600 dark:text-primary-200" />
            <span className="text-sm">Set as Primary</span>
          </label>

          <div className="grid grid-cols-2 gap-4">
            <div><label className="field-label block mb-1">Effective From</label><Input type="date" value={createForm.effectiveFrom} onChange={(e) => setCreateForm(p => ({ ...p, effectiveFrom: e.target.value }))} /></div>
            <div><label className="field-label block mb-1">Effective To (Optional)</label><Input type="date" value={createForm.effectiveTo} onChange={(e) => setCreateForm(p => ({ ...p, effectiveTo: e.target.value }))} /></div>
          </div>

          {createForm.relationshipType === 'AUTHORIZED' && (
            <div className="p-4 bg-cat-2-soft rounded-lg space-y-4 dark:bg-cat-2/15">
              <h4 className="text-sm font-medium text-cat-2 flex items-center gap-2"><UserCheck className="w-4 h-4" />Authorization Limits</h4>
              <div className="grid grid-cols-2 gap-4">
                <div><label className="field-label block mb-1">Max Transaction Amount</label><Input type="number" placeholder="e.g., 500000" value={createForm.maxTransactionAmount} onChange={(e) => setCreateForm(p => ({ ...p, maxTransactionAmount: e.target.value }))} /></div>
                <div><label className="field-label block mb-1">Daily Limit</label><Input type="number" placeholder="e.g., 1000000" value={createForm.dailyLimit} onChange={(e) => setCreateForm(p => ({ ...p, dailyLimit: e.target.value }))} /></div>
              </div>
              <label className="flex items-center gap-2 cursor-pointer"><input type="checkbox" checked={createForm.requiresDualAuth} onChange={(e) => setCreateForm(p => ({ ...p, requiresDualAuth: e.target.checked }))} className="rounded text-cat-2" /><span className="text-sm">Require Dual Authorization</span></label>
            </div>
          )}

          {createForm.relationshipType === 'COLLATERAL' && (
            <div className="p-4 bg-error-50 rounded-lg space-y-4 dark:bg-error-500/10">
              <h4 className="text-sm font-medium text-error-700 flex items-center gap-2 dark:text-error-300"><Lock className="w-4 h-4" />Collateral Details</h4>
              <div><label className="field-label block mb-1">Collateral Percentage (%)</label><Input type="number" placeholder="e.g., 100" min="0" max="100" value={createForm.collateralPercent} onChange={(e) => setCreateForm(p => ({ ...p, collateralPercent: e.target.value }))} /></div>
            </div>
          )}

          <div><label className="field-label block mb-1">Description (Optional)</label><textarea className="w-full border border-neutral-300 rounded-lg px-3 py-2 resize-none dark:border-primary-700" rows={2} placeholder="Add notes..." value={createForm.description} onChange={(e) => setCreateForm(p => ({ ...p, description: e.target.value }))} /></div>

          <div className="flex gap-3 pt-4 border-t">
            <Button variant="outline" className="flex-1" onClick={() => setShowCreateModal(false)}>Cancel</Button>
            <Button className="flex-1" onClick={handleCreate} disabled={!createForm.virtualAccountId || !createForm.legalEntityId || submitting}>
              {submitting ? <Loader2 className="w-4 h-4 mr-2 animate-spin" /> : <Plus className="w-4 h-4 mr-2" />}Create Attachment
            </Button>
          </div>
        </div>
      </Modal>

      {/* Transfer Modal */}
      <Modal isOpen={showTransferModal} onClose={() => setShowTransferModal(false)} title="Transfer Ownership">
        <div className="p-4 space-y-4">
          <p className="text-sm text-neutral-600 dark:text-neutral-300">Transfer ownership of <strong>{selectedAttachment?.vaNumber}</strong> to another entity.</p>
          <LegalEntityPicker selectedId={transferForm.newOwnerId} onSelect={(id) => setTransferForm(p => ({ ...p, newOwnerId: id }))} label="New Owner Entity *" entities={entities.filter(e => e.id !== selectedEntityId)} loading={entitiesLoading} />
          <div><label className="field-label block mb-1">Transfer Reason</label><Input placeholder="e.g., Corporate restructuring" value={transferForm.transferReason} onChange={(e) => setTransferForm(p => ({ ...p, transferReason: e.target.value }))} /></div>
          <div className="flex gap-3 pt-4 border-t">
            <Button variant="outline" className="flex-1" onClick={() => setShowTransferModal(false)}>Cancel</Button>
            <Button className="flex-1" onClick={handleTransferOwnership} disabled={!transferForm.newOwnerId || submitting}>
              {submitting ? <Loader2 className="w-4 h-4 mr-2 animate-spin" /> : <ArrowRightLeft className="w-4 h-4 mr-2" />}Transfer
            </Button>
          </div>
        </div>
      </Modal>
    </Page>
  );
};

export default AccountAttachmentsPage;