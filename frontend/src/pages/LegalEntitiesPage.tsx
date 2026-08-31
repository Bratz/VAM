import React, { useState, useEffect, useCallback, useMemo } from 'react';
import toast from 'react-hot-toast';
import {
  Building2, ChevronRight, ChevronDown, Globe, Users, Briefcase, Crown,
  Plus, Edit, Eye, Search, RefreshCw, Download, TrendingUp, AlertTriangle, CheckCircle,
  XCircle, Clock, CreditCard, Landmark, FlaskConical, Building, ArrowLeftRight,
  PiggyBank, Banknote, GitBranch, Copy, Lock, Layers,
  Info, ChevronUp, Loader2, X, Check, Wallet, Link2, Percent,
  Mail, Phone, MapPin, FileText, Calendar, Hash, DollarSign, Shield, Zap, Save,
} from 'lucide-react';
import { Card, CardHeader, Button, Badge, Input, StatTile } from '../components/ui';
import { Modal } from '../components/ui/enhanced';
import { HeroMetricCard } from '../components/ui/HeroMetricCard';
import { usePageHeaderActions } from '../context/PageHeaderContext';
import { formatCurrency, formatCompactCurrency, cn } from '../utils';
import {
  EntityAllocationModal,
  EntityLimitAllocationData,
  InternalLimitResponse,
} from '../components/credit/EntityAllocationModal';
import { Page } from '../components/layout/Page';

// ============================================================================
// TYPES
// ============================================================================

type EntityType = 'HOLDING' | 'SUBSIDIARY' | 'BRANCH' | 'REPRESENTATIVE' | 'JOINT_VENTURE' | 'ASSOCIATE' | 'SPV' | 'TREASURY_CENTER';
type EntityStatus = 'ACTIVE' | 'INACTIVE' | 'SUSPENDED' | 'PENDING_APPROVAL' | 'CLOSED';
type ConsolidationMethod = 'FULL' | 'PROPORTIONAL' | 'EQUITY' | 'NONE';
type LimitStatus = 'DRAFT' | 'PENDING_APPROVAL' | 'ACTIVE' | 'SUSPENDED' | 'EXPIRED' | 'CANCELLED' | 'BREACHED';

interface LegalEntity {
  id: string;
  // Identity - MANDATORY
  entityCode: string;
  entityName: string;
  shortName?: string;
  // Hierarchy
  parentEntityId?: string;
  hierarchyPath?: string;
  hierarchyLevel: number;
  // Ownership & Consolidation
  ownershipPercent: number;
  consolidationMethod: ConsolidationMethod;
  // Classification
  entityType: EntityType;
  legalForm?: string;
  // Jurisdiction
  countryCode?: string;
  jurisdiction?: string;
  taxId?: string;
  registrationNumber?: string;
  // Currency
  functionalCurrency: string;
  reportingCurrency?: string;
  operatingCurrencies?: string[];
  // Bank Relationship
  isBankCustomer?: boolean;
  bancsCustomerId?: string;
  // Treasury Configuration
  isTreasuryCenter: boolean;
  canHoldPhysicalAccounts: boolean;
  canParticipatePooling: boolean;
  canParticipateNetting: boolean;
  // Internal Credit
  internalCreditLimit?: number;
  internalLimitUtilized: number;
  internalLimitCurrency?: string;
  limitWarningThreshold: number;
  // IHB Configuration
  ihbEnabled?: boolean;
  ihbCreditLimit?: number;
  ihbCurrentExposure?: number;
  ihbAvailableLimit?: number;
  ihbCurrency?: string;
  canLend?: boolean;
  canBorrow?: boolean;
  lendingRateSpread?: number;
  borrowingRateSpread?: number;
  totalLentOut?: number;
  totalDeposited?: number;
  netIhbPosition?: number;
  // Status & Validity
  status: EntityStatus;
  effectiveFrom?: string;
  effectiveTo?: string;
  // Contact Information
  contactEmail?: string;
  contactPhone?: string;
  registeredAddress?: string;
  // System
  corporateId: string;
  createdAt: string;
  updatedAt?: string;
}

interface CreditLimit {
  id: string;
  corporateId: string;
  limitName?: string;
  limitType: 'EXTERNAL' | 'INTERNAL' | 'GROUP' | 'ENTITY' | 'VA';
  targetType: string;
  targetId: string;
  parentLimitId?: string;
  allocatedToChildren?: number;
  unallocatedAmount?: number;
  limitAmount: number;
  currency: string;  // Backend returns 'currency', not 'limitCurrency'
  utilizedAmount: number;
  availableAmount: number;
  utilizationPercent: number;
  warningThresholdPercent: number;
  isAtWarningLevel: boolean;
  isBreached: boolean;
  isHardLimit: boolean;
  requiresApproval: boolean;
  approvalThresholdPercent?: number;
  status: LimitStatus;
  createdAt: string;
}

interface Corporate { id: string; legalName: string; shortName?: string; }
interface EntityStats { 
  totalEntities: number; 
  activeEntities: number; 
  bankCustomers: number; 
  entitiesWithLimits: number;
  entitiesNearLimit: number; 
  treasuryCenters: number;
  ihbEnabledEntities: number;
}

// ============================================================================
// API SERVICE
// ============================================================================

const API_BASE = '/api/v1';

const legalEntityApi = {
  getAllByCorporate: async (corporateId: string): Promise<LegalEntity[]> => {
    const response = await fetch(`${API_BASE}/legal-entities/corporate/${corporateId}`);
    if (!response.ok) throw new Error('Failed to fetch entities');
    const result = await response.json();
    return result.data || [];
  },
  create: async (corporateId: string, data: LegalEntityFormData): Promise<LegalEntity> => {
    const response = await fetch(`${API_BASE}/legal-entities`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...data, corporateId }),
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.message || 'Failed to create entity');
    return result.data;
  },
  update: async (entityId: string, data: Partial<LegalEntityFormData>): Promise<LegalEntity> => {
    const response = await fetch(`${API_BASE}/legal-entities/${entityId}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data),
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.message || 'Failed to update entity');
    return result.data;
  },
};

const corporateApi = {
  getAll: async (): Promise<Corporate[]> => {
    const response = await fetch(`${API_BASE}/corporates`);
    if (!response.ok) throw new Error('Failed to fetch corporates');
    const result = await response.json();
    return result.data || [];
  },
};

const creditLimitApi = {
  // Get all group limits (multi-currency) for a corporate
  getGroupLimits: async (corporateId: string): Promise<CreditLimit[]> => {
    try {
      const response = await fetch(`${API_BASE}/credit/limits/internal/corporate/${corporateId}/groups`);
      if (!response.ok) return [];
      const result = await response.json();
      return result.data || [];
    } catch { return []; }
  },

  // Get group limit for a specific currency
  getGroupLimitByCurrency: (groupLimits: CreditLimit[], currency: string): CreditLimit | null => {
    return groupLimits.find(g => g.currency === currency) || null;
  },

  // Find the appropriate parent limit for an entity based on legal entity hierarchy AND currency
  // Walks up the entity tree until it finds a parent with a credit limit for the same currency
  findParentLimitForEntity: (
    entity: LegalEntity,
    currency: string,
    entities: LegalEntity[],
    entityLimits: Record<string, CreditLimit[]>,  // Now keyed by entityId, value is array of currency limits
    groupLimits: CreditLimit[]
  ): { parentLimit: CreditLimit | null; parentEntity: LegalEntity | null; isGroupLevel: boolean } => {
    // Walk up the entity hierarchy to find a parent with a credit limit for this currency
    let currentParentId = entity.parentEntityId;

    while (currentParentId) {
      const parentEntity = entities.find(e => e.id === currentParentId);
      if (!parentEntity) break;

      // Check if this parent has a credit limit for this currency
      const parentLimits = entityLimits[parentEntity.id] || [];
      const parentLimit = parentLimits.find(l => l.currency === currency);
      if (parentLimit && parentLimit.limitAmount > 0) {
        return { parentLimit, parentEntity, isGroupLevel: false };
      }

      // Move up the hierarchy
      currentParentId = parentEntity.parentEntityId;
    }

    // No intermediate parent with limit found - use Group Limit for this currency
    const groupLimit = groupLimits.find(g => g.currency === currency) || null;
    return { parentLimit: groupLimit, parentEntity: null, isGroupLevel: true };
  },

  createEntitySubLimit: async (data: {
    corporateId: string; entityId: string; limitName?: string; amount: number;
    currency: string; approvedBy: string; hardLimit: boolean;
    requiresApproval: boolean; approvalThreshold?: number;
    warningThreshold?: number; criticalThreshold?: number;
    effectiveFrom?: string; effectiveTo?: string; notes?: string;
    parentLimitId?: string;  // Specify which pool to allocate from
  }): Promise<CreditLimit> => {
    // Use the same v2 endpoint as CreditLimitsPage for multi-currency support
    const response = await fetch(`${API_BASE}/credit/limits/internal/entity/v2`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data),
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.message || 'Failed to create entity limit');
    return result.data;
  },

  updateEntitySubLimitAmount: async (limitId: string, newAmount: number, updatedBy: string): Promise<CreditLimit> => {
    // Use the same endpoint as CreditLimitsPage
    const response = await fetch(
      `${API_BASE}/credit/limits/internal/entity/${limitId}/amount?newAmount=${newAmount}&updatedBy=${encodeURIComponent(updatedBy)}`,
      { method: 'PUT' }
    );
    const result = await response.json();
    if (!response.ok) throw new Error(result.message || 'Failed to update limit');
    return result.data;
  },

  getInternalLimits: async (corporateId: string): Promise<CreditLimit[]> => {
    try {
      // Use the same endpoint as CreditLimitsPage
      const response = await fetch(`${API_BASE}/credit/limits/internal/corporate/${corporateId}`);
      if (!response.ok) return [];
      const result = await response.json();
      return result.data || [];
    } catch { return []; }
  },
};

// ============================================================================
// FORM DATA INTERFACE
// ============================================================================

interface LegalEntityFormData {
  entityCode: string;
  entityName: string;
  shortName: string;
  parentEntityId: string;
  ownershipPercent: number;
  consolidationMethod: ConsolidationMethod;
  entityType: EntityType;
  legalForm: string;
  countryCode: string;
  jurisdiction: string;
  taxId: string;
  registrationNumber: string;
  functionalCurrency: string;
  reportingCurrency: string;
  operatingCurrencies: string[];
  isBankCustomer: boolean;
  bancsCustomerId: string;
  isTreasuryCenter: boolean;
  canHoldPhysicalAccounts: boolean;
  canParticipatePooling: boolean;
  canParticipateNetting: boolean;
  internalCreditLimit: number | null;
  internalLimitCurrency: string;
  limitWarningThreshold: number;
  ihbEnabled: boolean;
  ihbCreditLimit: number | null;
  ihbCurrency: string;
  canLend: boolean;
  canBorrow: boolean;
  lendingRateSpread: number;
  borrowingRateSpread: number;
  status: EntityStatus;
  effectiveFrom: string;
  effectiveTo: string;
  contactEmail: string;
  contactPhone: string;
  registeredAddress: string;
}

// ============================================================================
// CONSTANTS
// ============================================================================

const LEGAL_FORMS = [
  { value: 'LLC', label: 'LLC - Limited Liability Company' },
  { value: 'CORP', label: 'Corporation' },
  { value: 'LTD', label: 'Ltd - Private Limited' },
  { value: 'PLC', label: 'PLC - Public Limited Company' },
  { value: 'GMBH', label: 'GmbH - German LLC' },
  { value: 'SA', label: 'SA - Société Anonyme' },
  { value: 'AG', label: 'AG - Aktiengesellschaft' },
  { value: 'BV', label: 'BV - Besloten Vennootschap' },
  { value: 'NV', label: 'NV - Naamloze Vennootschap' },
  { value: 'KK', label: 'KK - Kabushiki Kaisha' },
  { value: 'PTE_LTD', label: 'Pte Ltd - Private Limited' },
  { value: 'BRANCH', label: 'Branch Office' },
  { value: 'REP_OFFICE', label: 'Representative Office' },
  { value: 'LLP', label: 'LLP - Limited Liability Partnership' },
  { value: 'PARTNERSHIP', label: 'Partnership' },
  { value: 'TRUST', label: 'Trust' },
  { value: 'OTHER', label: 'Other' },
];

const CURRENCIES = ['AED', 'USD', 'EUR', 'GBP', 'SAR', 'QAR', 'KWD', 'BHD', 'OMR', 'CHF', 'JPY', 'CNY', 'SGD', 'HKD', 'AUD', 'CAD', 'INR'];

const COUNTRIES = [
  { code: 'AE', name: 'United Arab Emirates' }, { code: 'SA', name: 'Saudi Arabia' },
  { code: 'QA', name: 'Qatar' }, { code: 'KW', name: 'Kuwait' }, { code: 'BH', name: 'Bahrain' },
  { code: 'OM', name: 'Oman' }, { code: 'US', name: 'United States' }, { code: 'GB', name: 'United Kingdom' },
  { code: 'DE', name: 'Germany' }, { code: 'FR', name: 'France' }, { code: 'NL', name: 'Netherlands' },
  { code: 'CH', name: 'Switzerland' }, { code: 'SG', name: 'Singapore' }, { code: 'HK', name: 'Hong Kong' },
  { code: 'JP', name: 'Japan' }, { code: 'CN', name: 'China' }, { code: 'IN', name: 'India' },
  { code: 'AU', name: 'Australia' }, { code: 'CA', name: 'Canada' },
];

const INITIAL_FORM_DATA: LegalEntityFormData = {
  entityCode: '', entityName: '', shortName: '', parentEntityId: '',
  ownershipPercent: 100, consolidationMethod: 'FULL', entityType: 'SUBSIDIARY', legalForm: '',
  countryCode: '', jurisdiction: '', taxId: '', registrationNumber: '',
  functionalCurrency: 'AED', reportingCurrency: '', operatingCurrencies: [],
  isBankCustomer: false, bancsCustomerId: '',
  isTreasuryCenter: false, canHoldPhysicalAccounts: true, canParticipatePooling: true, canParticipateNetting: true,
  internalCreditLimit: null, internalLimitCurrency: 'AED', limitWarningThreshold: 80,
  ihbEnabled: false, ihbCreditLimit: null, ihbCurrency: '', canLend: false, canBorrow: true,
  lendingRateSpread: 0.50, borrowingRateSpread: 0.75,
  status: 'ACTIVE', effectiveFrom: new Date().toISOString().split('T')[0], effectiveTo: '',
  contactEmail: '', contactPhone: '', registeredAddress: '',
};

const consolidationConfig: Record<ConsolidationMethod, { label: string }> = {
  FULL: { label: 'Full Consolidation' }, PROPORTIONAL: { label: 'Proportional' },
  EQUITY: { label: 'Equity Method' }, NONE: { label: 'None' },
};

// ============================================================================
// CONFIGURATION
// ============================================================================

const entityTypeConfig: Record<EntityType, { label: string; icon: React.ElementType; color: string; bgColor: string }> = {
  HOLDING: { label: 'Holding Company', icon: Crown, color: 'text-primary-700 dark:text-neutral-200', bgColor: 'bg-primary-100 dark:bg-primary-700' },
  SUBSIDIARY: { label: 'Subsidiary', icon: Building2, color: 'text-info-700 dark:text-info-300', bgColor: 'bg-info-100 dark:bg-info-500/20' },
  BRANCH: { label: 'Branch', icon: Building, color: 'text-cyan-700 dark:text-cyan-300', bgColor: 'bg-cyan-100 dark:bg-cyan-500/20' },
  REPRESENTATIVE: { label: 'Representative', icon: Users, color: 'text-neutral-700 dark:text-neutral-200', bgColor: 'bg-neutral-100 dark:bg-primary-800' },
  JOINT_VENTURE: { label: 'Joint Venture', icon: ArrowLeftRight, color: 'text-warning-700 dark:text-warning-300', bgColor: 'bg-warning-100 dark:bg-warning-500/20' },
  ASSOCIATE: { label: 'Associate', icon: Briefcase, color: 'text-success-700 dark:text-success-300', bgColor: 'bg-success-100 dark:bg-success-500/20' },
  SPV: { label: 'SPV', icon: FlaskConical, color: 'text-error-700 dark:text-error-300', bgColor: 'bg-error-100 dark:bg-error-500/20' },
  TREASURY_CENTER: { label: 'Treasury Center', icon: Landmark, color: 'text-cat-2', bgColor: 'bg-cat-2/10 dark:bg-cat-2/15' },
};

const statusConfig: Record<EntityStatus, { label: string; color: string; bgColor: string; icon: React.ElementType }> = {
  ACTIVE: { label: 'Active', color: 'text-success-700 dark:text-success-300', bgColor: 'bg-success-100 dark:bg-success-500/20', icon: CheckCircle },
  INACTIVE: { label: 'Inactive', color: 'text-neutral-700 dark:text-neutral-200', bgColor: 'bg-neutral-100 dark:bg-primary-800', icon: XCircle },
  SUSPENDED: { label: 'Suspended', color: 'text-warning-700 dark:text-warning-300', bgColor: 'bg-warning-100 dark:bg-warning-500/20', icon: AlertTriangle },
  PENDING_APPROVAL: { label: 'Pending', color: 'text-info-700 dark:text-info-300', bgColor: 'bg-info-100 dark:bg-info-500/20', icon: Clock },
  CLOSED: { label: 'Closed', color: 'text-error-700 dark:text-error-300', bgColor: 'bg-error-100 dark:bg-error-500/20', icon: Lock },
};

// ============================================================================
// FORM INPUT COMPONENTS
// ============================================================================

const FormField: React.FC<{ label: string; required?: boolean; error?: string; hint?: string; children: React.ReactNode }> = ({ label, required, error, hint, children }) => (
  <div className="space-y-1">
    <label className="field-label block">{label}{required && <span className="text-error-500 ml-1">*</span>}</label>
    {children}
    {hint && !error && <p className="text-xs text-neutral-500 dark:text-neutral-400">{hint}</p>}
    {error && <p className="text-xs text-error-600 dark:text-error-300">{error}</p>}
  </div>
);

const CheckboxField: React.FC<{ checked: boolean; onChange: (v: boolean) => void; label: string; description?: string }> = ({ checked, onChange, label, description }) => (
  <label className={cn('flex items-start gap-3 p-3 rounded-lg border cursor-pointer transition-all', checked ? 'bg-primary-50 border-primary-300 dark:bg-primary-800/40' : 'bg-white border-neutral-200 hover:bg-neutral-50 dark:bg-primary-900 dark:border-primary-800 dark:hover:bg-primary-800/50')}>
    <input type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} className="mt-0.5 h-4 w-4 rounded border-neutral-300 text-primary-600 dark:border-primary-700 dark:text-primary-200" />
    <div><p className="text-sm font-medium text-neutral-900 dark:text-neutral-50">{label}</p>{description && <p className="text-xs text-neutral-500 mt-0.5 dark:text-neutral-400">{description}</p>}</div>
  </label>
);

// ============================================================================
// ENTITY FORM MODAL - CREATE/EDIT
// ============================================================================

interface EntityFormModalProps {
  isOpen: boolean;
  onClose: () => void;
  entity: LegalEntity | null;
  entities: LegalEntity[];
  corporateId: string;
  onSave: (data: LegalEntityFormData, entityId?: string) => Promise<void>;
}

const EntityFormModal: React.FC<EntityFormModalProps> = ({ isOpen, onClose, entity, entities, corporateId, onSave }) => {
  const [formData, setFormData] = useState<LegalEntityFormData>(INITIAL_FORM_DATA);
  const [saving, setSaving] = useState(false);
  const [errors, setErrors] = useState<Partial<Record<keyof LegalEntityFormData, string>>>({});
  const [activeTab, setActiveTab] = useState<'basic' | 'jurisdiction' | 'treasury' | 'contact'>('basic');
  const isEditMode = entity !== null;

  useEffect(() => {
    if (isOpen) {
      if (entity) {
        setFormData({
          entityCode: entity.entityCode, entityName: entity.entityName, shortName: entity.shortName || '',
          parentEntityId: entity.parentEntityId || '', ownershipPercent: entity.ownershipPercent,
          consolidationMethod: entity.consolidationMethod, entityType: entity.entityType, legalForm: entity.legalForm || '',
          countryCode: entity.countryCode || '', jurisdiction: entity.jurisdiction || '',
          taxId: entity.taxId || '', registrationNumber: entity.registrationNumber || '',
          functionalCurrency: entity.functionalCurrency, reportingCurrency: entity.reportingCurrency || '',
          operatingCurrencies: entity.operatingCurrencies || [],
          isBankCustomer: entity.isBankCustomer || false, bancsCustomerId: entity.bancsCustomerId || '',
          isTreasuryCenter: entity.isTreasuryCenter, canHoldPhysicalAccounts: entity.canHoldPhysicalAccounts,
          canParticipatePooling: entity.canParticipatePooling, canParticipateNetting: entity.canParticipateNetting,
          internalCreditLimit: entity.internalCreditLimit || null,
          internalLimitCurrency: entity.internalLimitCurrency || entity.functionalCurrency,
          limitWarningThreshold: entity.limitWarningThreshold,
          ihbEnabled: entity.ihbEnabled || false, ihbCreditLimit: entity.ihbCreditLimit || null,
          ihbCurrency: entity.ihbCurrency || entity.functionalCurrency,
          canLend: entity.canLend || false, canBorrow: entity.canBorrow !== false,
          lendingRateSpread: entity.lendingRateSpread || 0.50, borrowingRateSpread: entity.borrowingRateSpread || 0.75,
          status: entity.status, effectiveFrom: entity.effectiveFrom || '', effectiveTo: entity.effectiveTo || '',
          contactEmail: entity.contactEmail || '', contactPhone: entity.contactPhone || '',
          registeredAddress: entity.registeredAddress || '',
        });
      } else {
        setFormData({ ...INITIAL_FORM_DATA });
      }
      setErrors({});
      setActiveTab('basic');
    }
  }, [isOpen, entity]);

  const updateField = <K extends keyof LegalEntityFormData>(field: K, value: LegalEntityFormData[K]) => {
    setFormData(prev => ({ ...prev, [field]: value }));
    if (errors[field]) setErrors(prev => ({ ...prev, [field]: undefined }));
  };

  const validate = (): boolean => {
    const newErrors: Partial<Record<keyof LegalEntityFormData, string>> = {};
    if (!formData.entityCode.trim()) newErrors.entityCode = 'Entity code is required';
    else if (!/^[A-Z0-9_-]+$/i.test(formData.entityCode)) newErrors.entityCode = 'Invalid format';
    else if (!isEditMode && entities.some(e => e.entityCode.toUpperCase() === formData.entityCode.toUpperCase())) newErrors.entityCode = 'Entity code exists';
    if (!formData.entityName.trim()) newErrors.entityName = 'Entity name is required';
    if (!formData.functionalCurrency) newErrors.functionalCurrency = 'Currency required';
    if (formData.isBankCustomer && !formData.bancsCustomerId.trim()) newErrors.bancsCustomerId = 'BANCS ID required';
    if (formData.contactEmail && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(formData.contactEmail)) newErrors.contactEmail = 'Invalid email';
    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!validate()) return;
    setSaving(true);
    try {
      await onSave(formData, entity?.id);
      onClose();
    } catch (err: unknown) {
      setErrors({ entityCode: err instanceof Error ? err.message : 'Failed to save' });
    } finally { setSaving(false); }
  };

  if (!isOpen) return null;

  const potentialParents = entities.filter(e => e.id !== entity?.id && e.status === 'ACTIVE');
  const tabs = [
    { id: 'basic' as const, label: 'Basic Info', icon: Building2 },
    { id: 'jurisdiction' as const, label: 'Jurisdiction', icon: Globe },
    { id: 'treasury' as const, label: 'Treasury & IHB', icon: Landmark },
    { id: 'contact' as const, label: 'Contact', icon: Mail },
  ];

  // Phase 10 Task E (2026-05-13): migrated from hand-rolled
  // `<div className="fixed inset-0 ...">` wrapper to shared <Modal>.
  // Inherits escape-key handler, body-scroll-lock, standardised backdrop
  // + max-width tokens. Tab nav + form + custom footer (with "Last
  // updated" text on left + buttons on right) remain inside children
  // because Modal's `footer` slot is justify-end only.
  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      size="xl"
      title={isEditMode ? 'Edit Legal Entity' : 'Create Legal Entity'}
      subtitle={isEditMode ? `Editing ${entity?.entityCode}` : 'Add a new entity to the corporate structure'}
    >
      <div className="-mx-6 -mt-6 flex flex-col">
        <div className="border-b px-6">
          <nav className="flex gap-1 -mb-px">
            {tabs.map(tab => (
              <button key={tab.id} type="button" onClick={() => setActiveTab(tab.id)}
                className={cn('flex items-center gap-2 px-4 py-3 text-sm font-medium border-b-2 transition-colors',
                  activeTab === tab.id ? 'border-cat-1 text-cat-1' : 'border-transparent text-neutral-500 hover:text-neutral-700')}>
                <tab.icon className="w-4 h-4" />{tab.label}
              </button>
            ))}
          </nav>
        </div>

        <form onSubmit={handleSubmit} className="flex-1 overflow-y-auto p-6">
          {activeTab === 'basic' && (
            <div className="space-y-6">
              <div className="bg-warning-50 border border-warning-200 rounded-lg p-3 dark:bg-warning-500/10 dark:border-warning-500/30">
                <p className="text-sm text-warning-800 dark:text-warning-300"><AlertTriangle className="w-4 h-4 inline mr-1" />Fields marked with <span className="text-error-500">*</span> are mandatory.</p>
              </div>
              <div className="grid grid-cols-2 gap-4">
                <FormField label="Entity Code" required error={errors.entityCode} hint="Unique ID (e.g., ACME-UK)">
                  <input type="text" value={formData.entityCode} onChange={(e) => updateField('entityCode', e.target.value.toUpperCase())} disabled={isEditMode} maxLength={20} className={cn('w-full px-3 py-2 border rounded-lg text-sm', isEditMode && 'bg-neutral-100')} placeholder="ACME-UK" />
                </FormField>
                <FormField label="Entity Name" required error={errors.entityName}>
                  <input type="text" value={formData.entityName} onChange={(e) => updateField('entityName', e.target.value)} maxLength={200} className="w-full px-3 py-2 border rounded-lg text-sm" placeholder="ACME Corporation UK Ltd" />
                </FormField>
                <FormField label="Short Name" hint="Display name">
                  <input type="text" value={formData.shortName} onChange={(e) => updateField('shortName', e.target.value)} maxLength={50} className="w-full px-3 py-2 border rounded-lg text-sm" placeholder="ACME UK" />
                </FormField>
                <FormField label="Entity Type" required>
                  <select value={formData.entityType} onChange={(e) => updateField('entityType', e.target.value as EntityType)} className="w-full px-3 py-2 border rounded-lg text-sm">
                    {Object.entries(entityTypeConfig).map(([value, cfg]) => <option key={value} value={value}>{cfg.label}</option>)}
                  </select>
                </FormField>
                <FormField label="Legal Form" hint="LLC, Ltd, GmbH, etc.">
                  <select value={formData.legalForm} onChange={(e) => updateField('legalForm', e.target.value)} className="w-full px-3 py-2 border rounded-lg text-sm">
                    <option value="">Select...</option>
                    {LEGAL_FORMS.map(f => <option key={f.value} value={f.value}>{f.label}</option>)}
                  </select>
                </FormField>
                <FormField label="Parent Entity">
                  <select value={formData.parentEntityId} onChange={(e) => updateField('parentEntityId', e.target.value)} className="w-full px-3 py-2 border rounded-lg text-sm">
                    <option value="">None (Root Entity)</option>
                    {potentialParents.map(e => <option key={e.id} value={e.id}>{e.entityCode} - {e.entityName}</option>)}
                  </select>
                </FormField>
                <FormField label="Ownership %" hint="0-100">
                  <input type="number" value={formData.ownershipPercent} onChange={(e) => updateField('ownershipPercent', parseFloat(e.target.value) || 0)} min={0} max={100} step={0.01} className="w-full px-3 py-2 border rounded-lg text-sm" />
                </FormField>
                <FormField label="Consolidation">
                  <select value={formData.consolidationMethod} onChange={(e) => updateField('consolidationMethod', e.target.value as ConsolidationMethod)} className="w-full px-3 py-2 border rounded-lg text-sm">
                    {Object.entries(consolidationConfig).map(([value, cfg]) => <option key={value} value={value}>{cfg.label}</option>)}
                  </select>
                </FormField>
                <FormField label="Status">
                  <select value={formData.status} onChange={(e) => updateField('status', e.target.value as EntityStatus)} className="w-full px-3 py-2 border rounded-lg text-sm">
                    {Object.entries(statusConfig).map(([value, cfg]) => <option key={value} value={value}>{cfg.label}</option>)}
                  </select>
                </FormField>
              </div>
              <div className="border-t pt-4">
                <h4 className="text-sm font-semibold mb-3 flex items-center gap-2"><DollarSign className="w-4 h-4" />Currency</h4>
                <div className="grid grid-cols-2 gap-4">
                  <FormField label="Functional Currency" required error={errors.functionalCurrency}>
                    <select value={formData.functionalCurrency} onChange={(e) => updateField('functionalCurrency', e.target.value)} className="w-full px-3 py-2 border rounded-lg text-sm">
                      {CURRENCIES.map(c => <option key={c} value={c}>{c}</option>)}
                    </select>
                  </FormField>
                  <FormField label="Reporting Currency">
                    <select value={formData.reportingCurrency} onChange={(e) => updateField('reportingCurrency', e.target.value)} className="w-full px-3 py-2 border rounded-lg text-sm">
                      <option value="">Same as functional</option>
                      {CURRENCIES.map(c => <option key={c} value={c}>{c}</option>)}
                    </select>
                  </FormField>
                </div>
              </div>
              <div className="border-t pt-4">
                <h4 className="text-sm font-semibold mb-3 flex items-center gap-2"><Wallet className="w-4 h-4" />Bank Relationship</h4>
                <CheckboxField checked={formData.isBankCustomer} onChange={(v) => updateField('isBankCustomer', v)} label="Is Bank Customer" description="Has direct banking relationship with external limits" />
                {formData.isBankCustomer && (
                  <div className="mt-3">
                    <FormField label="BANCS Customer ID" required error={errors.bancsCustomerId}>
                      <input type="text" value={formData.bancsCustomerId} onChange={(e) => updateField('bancsCustomerId', e.target.value)} className="w-full px-3 py-2 border rounded-lg text-sm" placeholder="CIF-123456" />
                    </FormField>
                  </div>
                )}
              </div>
              <div className="grid grid-cols-2 gap-4 border-t pt-4">
                <FormField label="Effective From">
                  <input type="date" value={formData.effectiveFrom} onChange={(e) => updateField('effectiveFrom', e.target.value)} className="w-full px-3 py-2 border rounded-lg text-sm" />
                </FormField>
                <FormField label="Effective To" hint="Leave empty for no end">
                  <input type="date" value={formData.effectiveTo} onChange={(e) => updateField('effectiveTo', e.target.value)} className="w-full px-3 py-2 border rounded-lg text-sm" />
                </FormField>
              </div>
            </div>
          )}

          {activeTab === 'jurisdiction' && (
            <div className="space-y-4">
              <div className="grid grid-cols-2 gap-4">
                <FormField label="Country">
                  <select value={formData.countryCode} onChange={(e) => updateField('countryCode', e.target.value)} className="w-full px-3 py-2 border rounded-lg text-sm">
                    <option value="">Select...</option>
                    {COUNTRIES.map(c => <option key={c.code} value={c.code}>{c.code} - {c.name}</option>)}
                  </select>
                </FormField>
                <FormField label="Jurisdiction" hint="DIFC, ADGM, etc.">
                  <input type="text" value={formData.jurisdiction} onChange={(e) => updateField('jurisdiction', e.target.value)} className="w-full px-3 py-2 border rounded-lg text-sm" placeholder="e.g., DIFC, England & Wales" />
                </FormField>
                <FormField label="Tax ID">
                  <input type="text" value={formData.taxId} onChange={(e) => updateField('taxId', e.target.value)} className="w-full px-3 py-2 border rounded-lg text-sm" placeholder="Tax identification number" />
                </FormField>
                <FormField label="Registration Number">
                  <input type="text" value={formData.registrationNumber} onChange={(e) => updateField('registrationNumber', e.target.value)} className="w-full px-3 py-2 border rounded-lg text-sm" placeholder="Company registration" />
                </FormField>
              </div>
            </div>
          )}

          {activeTab === 'treasury' && (
            <div className="space-y-4">
              {/* Treasury Center Section */}
              <div className="space-y-3">
                <CheckboxField
                  checked={formData.isTreasuryCenter}
                  onChange={(v) => {
                    updateField('isTreasuryCenter', v);
                    // Auto-link: When Treasury Center is enabled, auto-enable IHB and canLend
                    if (v) {
                      updateField('ihbEnabled', true);
                      updateField('canLend', true);
                    }
                  }}
                  label="Is Treasury Center"
                  description="Central treasury for intercompany funding (auto-enables IHB & lending)"
                />
                <CheckboxField checked={formData.canHoldPhysicalAccounts} onChange={(v) => updateField('canHoldPhysicalAccounts', v)} label="Can Hold Physical Accounts" description="Can maintain physical bank accounts" />
                <CheckboxField checked={formData.canParticipatePooling} onChange={(v) => updateField('canParticipatePooling', v)} label="Can Participate in Pooling" description="Cash pooling arrangements" />
                <CheckboxField checked={formData.canParticipateNetting} onChange={(v) => updateField('canParticipateNetting', v)} label="Can Participate in Netting" description="Intercompany netting cycles" />
              </div>

              {/* Credit Limit Info - Managed via CreditLimitService */}
              <div className="border-t pt-4">
                <div className="bg-info-50 border border-info-200 rounded-lg p-3 dark:bg-info-500/10 dark:border-info-500/30">
                  <p className="text-sm text-info-800 dark:text-info-300">
                    <CreditCard className="w-4 h-4 inline mr-1" />
                    <strong>Internal Credit Limits</strong> are managed via the "Allocate Limit" button in the entity details panel (right side).
                  </p>
                  <p className="text-xs text-info-600 mt-1 dark:text-info-300">Limits are allocated from the Group Limit and tracked via CreditLimitService.</p>
                </div>
              </div>

              {/* IHB Section - Merged */}
              <div className="border-t pt-4">
                <div className="bg-cat-2-soft border border-cat-2/20 rounded-lg p-3 mb-4 dark:bg-cat-2/15 dark:border-cat-2/30">
                  <p className="text-sm text-cat-2"><Banknote className="w-4 h-4 inline mr-1" />In-House Banking for intercompany lending/borrowing.</p>
                </div>
                <CheckboxField
                  checked={formData.ihbEnabled}
                  onChange={(v) => {
                    updateField('ihbEnabled', v);
                    // If disabling IHB while Treasury Center is enabled, warn but allow
                    if (!v && formData.isTreasuryCenter) {
                      // Treasury Centers typically have IHB enabled, but user can override
                    }
                  }}
                  label="Enable In-House Banking"
                  description={formData.isTreasuryCenter ? "Auto-enabled for Treasury Centers" : "Participate in IHB transactions"}
                />
                {formData.ihbEnabled && (
                  <>
                    <div className="grid grid-cols-2 gap-4 mt-4">
                      <CheckboxField
                        checked={formData.canLend}
                        onChange={(v) => updateField('canLend', v)}
                        label="Can Lend"
                        description={formData.isTreasuryCenter ? "Auto-enabled for Treasury Centers" : "Provide intercompany loans"}
                      />
                      <CheckboxField checked={formData.canBorrow} onChange={(v) => updateField('canBorrow', v)} label="Can Borrow" description="Take intercompany loans" />
                    </div>
                    <div className="grid grid-cols-2 gap-4 mt-4">
                      <FormField label="Lending Spread %" hint="Added when lending">
                        <input type="number" value={formData.lendingRateSpread} onChange={(e) => updateField('lendingRateSpread', parseFloat(e.target.value) || 0)} min={0} max={10} step={0.01} className="w-full px-3 py-2 border rounded-lg text-sm" />
                      </FormField>
                      <FormField label="Borrowing Spread %" hint="Added when borrowing">
                        <input type="number" value={formData.borrowingRateSpread} onChange={(e) => updateField('borrowingRateSpread', parseFloat(e.target.value) || 0)} min={0} max={10} step={0.01} className="w-full px-3 py-2 border rounded-lg text-sm" />
                      </FormField>
                    </div>
                  </>
                )}
              </div>
            </div>
          )}

          {activeTab === 'contact' && (
            <div className="space-y-4">
              <div className="grid grid-cols-2 gap-4">
                <FormField label="Contact Email" error={errors.contactEmail}>
                  <input type="email" value={formData.contactEmail} onChange={(e) => updateField('contactEmail', e.target.value)} className="w-full px-3 py-2 border rounded-lg text-sm" placeholder="finance@acme.com" />
                </FormField>
                <FormField label="Contact Phone">
                  <input type="tel" value={formData.contactPhone} onChange={(e) => updateField('contactPhone', e.target.value)} className="w-full px-3 py-2 border rounded-lg text-sm" placeholder="+971 4 123 4567" />
                </FormField>
              </div>
              <FormField label="Registered Address">
                <textarea value={formData.registeredAddress} onChange={(e) => updateField('registeredAddress', e.target.value)} rows={4} maxLength={500} className="w-full px-3 py-2 border rounded-lg text-sm resize-none" placeholder="123 Business Park&#10;Dubai, UAE" />
              </FormField>
            </div>
          )}
        </form>

        <div className="border-t px-6 py-4 flex justify-between items-center bg-neutral-50 dark:bg-primary-950 dark:border-primary-800">
          <div className="text-sm text-neutral-500 dark:text-neutral-400">{isEditMode && entity && `Last updated: ${new Date(entity.updatedAt || entity.createdAt).toLocaleDateString()}`}</div>
          <div className="flex gap-3">
            <button type="button" onClick={onClose} className="px-4 py-2 border rounded-lg text-sm">Cancel</button>
            <button type="submit" onClick={handleSubmit} disabled={saving} className="px-4 py-2 bg-cat-1 text-white rounded-lg text-sm disabled:opacity-50 flex items-center gap-2">
              {saving ? <><Loader2 className="w-4 h-4 animate-spin" />Saving...</> : <><Save className="w-4 h-4" />{isEditMode ? 'Update' : 'Create'}</>}
            </button>
          </div>
        </div>
      </div>
    </Modal>
  );
};

// ============================================================================
// STATS CARDS
// ============================================================================

/**
 * Operational stat strip — the *secondary* metrics, sitting below the
 * HeroMetricCard. Total Entities + Group Limits have been promoted to the
 * hero block above; this strip carries the remaining three operational
 * indicators (bank customers, entity-level limit health, IHB participation).
 */
const StatsCards: React.FC<{ stats: EntityStats; loading: boolean }> = ({ stats, loading }) => {
  // Phase 12 Task E: hand-rolled stat-card markup replaced by the shared
  // <StatTile layout="row"> (components/ui/StatTile); tone drives both the
  // icon medallion and the .stat-value-* headline colour.
  const statItems = [
    {
      label: 'Bank Customers',
      value: stats.bankCustomers.toString(),
      subtext: 'With BANCS accounts',
      icon: Wallet,
      tone: 'success',
    },
    {
      label: 'Entity Limits',
      value: stats.entitiesWithLimits.toString(),
      subtext: stats.entitiesNearLimit > 0 ? `${stats.entitiesNearLimit} near warning` : 'All healthy',
      icon: stats.entitiesNearLimit > 0 ? AlertTriangle : TrendingUp,
      tone: stats.entitiesNearLimit > 0 ? 'warning' : 'info',
    },
    {
      label: 'IHB Enabled',
      value: stats.ihbEnabledEntities.toString(),
      subtext: `${stats.treasuryCenters} treasury centers`,
      icon: Zap,
      tone: 'info',
    },
  ];

  return (
    <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
      {statItems.map((stat, idx) => (
        <StatTile
          key={stat.label}
          layout="row"
          tone={stat.tone}
          label={stat.label}
          value={stat.value}
          sub={stat.subtext}
          icon={<stat.icon className="w-5 h-5" />}
          loading={loading}
          delay={`${0.15 + idx * 0.05}s`}
        />
      ))}
    </div>
  );
};

// ============================================================================
// HIERARCHY TREE NODE
// ============================================================================

const HierarchyTreeNode: React.FC<{
  entity: LegalEntity; entities: LegalEntity[]; entityLimits: Record<string, CreditLimit[]>;
  expandedIds: Set<string>; onToggle: (id: string) => void; selectedId: string | null;
  onSelect: (entity: LegalEntity) => void; level: number;
}> = ({ entity, entities, entityLimits, expandedIds, onToggle, selectedId, onSelect, level }) => {
  const isExpanded = expandedIds.has(entity.id);
  const children = entities.filter(e => e.parentEntityId === entity.id);
  const hasChildren = children.length > 0;
  const isSelected = selectedId === entity.id;
  const typeConfig = entityTypeConfig[entity.entityType] || entityTypeConfig.SUBSIDIARY;
  const TypeIcon = typeConfig.icon;
  const statusCfg = statusConfig[entity.status] || statusConfig.ACTIVE;

  // Multi-currency: Get all limits for this entity
  const creditLimits = entityLimits[entity.id] || [];
  // For tree display, show primary currency limit or aggregate
  const primaryLimit = creditLimits.find(l => l.currency === entity.functionalCurrency) || creditLimits[0];
  const limitAmount = primaryLimit?.limitAmount || entity.internalCreditLimit || 0;
  const limitUtilized = primaryLimit?.utilizedAmount ?? entity.internalLimitUtilized ?? 0;
  const limitCurrency = primaryLimit?.currency || entity.functionalCurrency || 'AED';
  const utilizationPercent = limitAmount > 0 ? (limitUtilized / limitAmount) * 100 : 0;
  const isNearLimit = creditLimits.some(l => l.isAtWarningLevel) || utilizationPercent >= 80;
  const isBreached = creditLimits.some(l => l.isBreached) || utilizationPercent >= 100;
  const hasMultipleCurrencies = creditLimits.length > 1;

  return (
    <div>
      <div 
        className={cn(
          'flex items-center gap-2 p-3 rounded-lg cursor-pointer transition-all',
          isSelected ? 'ring-2 ring-primary-500 bg-primary-50 dark:bg-primary-800/40' : 'hover:bg-neutral-50 dark:hover:bg-primary-800/50',
          entity.isTreasuryCenter && 'border-l-4 border-l-cat-2'
        )} 
        style={{ marginLeft: `${level * 28}px` }} 
        onClick={() => onSelect(entity)}
      >
        {hasChildren ? (
          <button 
            onClick={(e) => { e.stopPropagation(); onToggle(entity.id); }} 
            className="p-1 hover:bg-neutral-200 rounded transition-colors"
          >
            {isExpanded ? <ChevronDown className="w-4 h-4 text-neutral-600 dark:text-neutral-300" /> : <ChevronRight className="w-4 h-4 text-neutral-600 dark:text-neutral-300" />}
          </button>
        ) : (
          <span className="w-6" />
        )}
        
        <div className={cn('p-2 rounded-lg', typeConfig.bgColor)}>
          <TypeIcon className={cn('w-4 h-4', typeConfig.color)} />
        </div>
        
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <p className="text-sm font-medium text-neutral-900 truncate dark:text-neutral-50">{entity.entityName}</p>
            {entity.isBankCustomer && (
              <Badge variant="success" size="sm" className="flex items-center gap-1">
                <Wallet className="w-3 h-3" /> Bank
              </Badge>
            )}
            {entity.isTreasuryCenter && (
              <Badge variant="default" size="sm" className="bg-cat-2/10 text-cat-2 dark:bg-cat-2/15">
                <Crown className="w-3 h-3 mr-1" />Treasury
              </Badge>
            )}
            {entity.ihbEnabled && !entity.isTreasuryCenter && (
              <Badge variant="info" size="sm" className="flex items-center gap-1">
                <PiggyBank className="w-3 h-3" /> IHB
              </Badge>
            )}
            <span className={cn('px-2 py-0.5 rounded-full text-xs', statusCfg.bgColor, statusCfg.color)}>
              {statusCfg.label}
            </span>
          </div>
          <div className="flex items-center gap-3 mt-0.5">
            <span className="text-xs text-neutral-500 font-mono dark:text-neutral-400">{entity.entityCode}</span>
            {entity.bancsCustomerId && (
              <span className="text-xs text-info-600 flex items-center gap-1 dark:text-info-300">
                <Link2 className="w-3 h-3" />{entity.bancsCustomerId}
              </span>
            )}
            {entity.countryCode && (
              <span className="text-xs text-neutral-400 flex items-center gap-1 dark:text-neutral-500">
                <Globe className="w-3 h-3" />{entity.countryCode}
              </span>
            )}
          </div>
        </div>
        
        <Badge variant="neutral" size="sm">{entity.functionalCurrency}</Badge>
        
        {limitAmount > 0 ? (
          <div className="text-right min-w-[140px]">
            <div className="flex items-center justify-end gap-2">
              {(isBreached || isNearLimit) && (
                <AlertTriangle className={cn('w-4 h-4', isBreached ? 'text-error-500' : 'text-warning-500')} />
              )}
              <p className={cn(
                'text-sm font-semibold',
                isBreached ? 'text-error-600 dark:text-error-300' : isNearLimit ? 'text-warning-600 dark:text-warning-300' : 'text-neutral-900 dark:text-neutral-50'
              )}>
                {utilizationPercent.toFixed(0)}%
              </p>
            </div>
            <p className="text-xs text-neutral-500 dark:text-neutral-400">
              {formatCurrency(limitUtilized, limitCurrency)} / {formatCurrency(limitAmount, limitCurrency)}
            </p>
            {hasMultipleCurrencies && (
              <p className="text-xs text-info-500">+{creditLimits.length - 1} more</p>
            )}
          </div>
        ) : (
          <div className="text-right min-w-[100px]">
            <span className="text-xs text-neutral-400 dark:text-neutral-500">No limit</span>
          </div>
        )}
      </div>
      
      {hasChildren && isExpanded && children.sort((a, b) => a.hierarchyLevel - b.hierarchyLevel).map(child => (
        <HierarchyTreeNode 
          key={child.id} 
          entity={child} 
          entities={entities} 
          entityLimits={entityLimits} 
          expandedIds={expandedIds} 
          onToggle={onToggle} 
          selectedId={selectedId} 
          onSelect={onSelect} 
          level={level + 1} 
        />
      ))}
    </div>
  );
};

// ============================================================================
// ENTITY DETAIL PANEL
// ============================================================================

const EntityDetailPanel: React.FC<{
  entity: LegalEntity | null; creditLimits: CreditLimit[]; groupLimits: CreditLimit[];
  onClose: () => void; onEdit: (entity: LegalEntity) => void; onAllocateLimit: (entity: LegalEntity, currency?: string) => void;
}> = ({ entity, creditLimits, groupLimits, onClose, onEdit, onAllocateLimit }) => {
  if (!entity) return null;

  const typeConfig = entityTypeConfig[entity.entityType] || entityTypeConfig.SUBSIDIARY;
  const TypeIcon = typeConfig.icon;
  const statusCfg = statusConfig[entity.status] || statusConfig.ACTIVE;
  const StatusIcon = statusCfg.icon;

  // Multi-currency: Show all limits for this entity
  const hasLimits = creditLimits.length > 0;
  const canManagePool = entity.isTreasuryCenter && hasLimits;

  // Available group limit currencies without entity limits
  const existingCurrencies = new Set(creditLimits.map(l => l.currency));
  const availableGroupCurrencies = groupLimits.filter(g => !existingCurrencies.has(g.currency));

  return (
    <div className="bg-white rounded-xl shadow-sm border border-neutral-100 h-full flex flex-col dark:bg-primary-900 dark:border-primary-800/60">
      <div className="p-4 border-b border-neutral-200 dark:border-primary-800">
        <div className="flex items-start justify-between">
          <div className="flex items-center gap-3">
            <div className={cn('p-3 rounded-xl', typeConfig.bgColor)}><TypeIcon className={cn('w-6 h-6', typeConfig.color)} /></div>
            <div><h3 className="section-title">{entity.entityName}</h3><p className="text-sm text-neutral-500 font-mono dark:text-neutral-400">{entity.entityCode}</p></div>
          </div>
          <button onClick={onClose} className="p-1 hover:bg-neutral-100 rounded transition-colors dark:hover:bg-primary-800"><X className="w-5 h-5 text-neutral-400 dark:text-neutral-500" /></button>
        </div>
        <div className="flex flex-wrap gap-2 mt-3">
          <span className={cn('px-2 py-1 rounded-full text-xs', statusCfg.bgColor, statusCfg.color)}><StatusIcon className="w-3 h-3 inline mr-1" />{statusCfg.label}</span>
          <span className="px-2 py-1 rounded-full text-xs bg-neutral-100 dark:bg-primary-800">{typeConfig.label}</span>
          {entity.isBankCustomer && <span className="px-2 py-1 rounded-full text-xs bg-success-100 text-success-700 dark:bg-success-500/20 dark:text-success-300"><Wallet className="w-3 h-3 inline" /> Bank Customer</span>}
          {entity.isTreasuryCenter && <span className="px-2 py-1 rounded-full text-xs bg-cat-2/10 text-cat-2 dark:bg-cat-2/15"><Crown className="w-3 h-3 inline" /> Treasury</span>}
          {entity.ihbEnabled && <span className="px-2 py-1 rounded-full text-xs bg-info-100 text-info-700 dark:bg-info-500/20 dark:text-info-300"><PiggyBank className="w-3 h-3 inline" /> IHB</span>}
        </div>
      </div>

      <div className="flex-1 overflow-y-auto p-4 space-y-6">
        <div className={cn('rounded-xl p-4', hasLimits ? 'bg-primary-50 border border-primary-200 dark:bg-primary-800/40 dark:border-primary-700' : 'bg-neutral-50 dark:bg-primary-950')}>
          <h4 className="text-sm font-semibold flex items-center gap-2 mb-3"><CreditCard className={cn('w-4 h-4', hasLimits ? 'text-primary-600 dark:text-primary-200' : 'text-neutral-400 dark:text-neutral-500')} />Internal Credit Limits</h4>
          {hasLimits ? (
            <div className="space-y-3">
              {/* Multi-currency: Show each currency limit */}
              {creditLimits.map((limit, idx) => {
                const limitAmount = limit.limitAmount || 0;
                const limitUtilized = limit.utilizedAmount || 0;
                const limitCurrency = limit.currency;
                const availableLimit = limitAmount - limitUtilized;
                const utilizationPercent = limitAmount > 0 ? (limitUtilized / limitAmount) * 100 : 0;
                const allocatedToChildren = limit.allocatedToChildren || 0;
                const unallocatedPool = limit.unallocatedAmount || 0;
                const isLimitPoolManager = allocatedToChildren > 0;

                return (
                  <div key={limit.id} className={cn('p-3 rounded-lg', idx > 0 && 'border-t border-primary-100 pt-4 dark:border-primary-700/60')}>
                    <div className="flex items-center justify-between mb-2">
                      <Badge variant="neutral" size="sm">{limitCurrency}</Badge>
                      <Button variant="ghost" size="sm" onClick={() => onAllocateLimit(entity, limitCurrency)}>
                        <Edit className="w-3 h-3" />
                      </Button>
                    </div>
                    <div className="grid grid-cols-3 gap-2">
                      <div className="min-w-0"><p className="text-xs text-neutral-500 dark:text-neutral-400">Allocated</p><p className="text-sm font-bold text-neutral-900 truncate dark:text-neutral-50" title={formatCurrency(limitAmount, limitCurrency)}>{formatCompactCurrency(limitAmount, limitCurrency)}</p></div>
                      <div className="min-w-0"><p className="text-xs text-neutral-500 dark:text-neutral-400">Utilized</p><p className="text-sm font-bold text-warning-600 truncate dark:text-warning-300" title={formatCurrency(limitUtilized, limitCurrency)}>{formatCompactCurrency(limitUtilized, limitCurrency)}</p></div>
                      <div className="min-w-0"><p className="text-xs text-neutral-500 dark:text-neutral-400">Available</p><p className="text-sm font-bold text-success-600 truncate dark:text-success-300" title={formatCurrency(availableLimit, limitCurrency)}>{formatCompactCurrency(availableLimit, limitCurrency)}</p></div>
                    </div>
                    <div className="w-full bg-neutral-200 rounded-full h-2 mt-2 dark:bg-primary-800"><div className={cn('h-2 rounded-full', utilizationPercent > 95 ? 'bg-error-500' : utilizationPercent > 80 ? 'bg-warning-500' : 'bg-success-500')} style={{ width: `${Math.min(utilizationPercent, 100)}%` }} /></div>
                    <p className="text-xs text-neutral-500 text-right mt-1 dark:text-neutral-400">{utilizationPercent.toFixed(1)}% utilized</p>

                    {/* Pool Manager Section - for Regional Treasuries managing subsidiary limits */}
                    {(isLimitPoolManager || (canManagePool && idx === 0)) && (
                      <div className="mt-2 pt-2 border-t border-primary-100 dark:border-primary-700/60">
                        <p className="text-xs font-semibold text-cat-2 mb-1"><Landmark className="w-3 h-3 inline mr-1" />Pool Manager</p>
                        <div className="grid grid-cols-2 gap-2">
                          <div className="min-w-0">
                            <p className="text-xs text-cat-2">To Subsidiaries</p>
                            <p className="text-sm font-bold text-cat-2 truncate">{formatCompactCurrency(allocatedToChildren, limitCurrency)}</p>
                          </div>
                          <div className="min-w-0">
                            <p className="text-xs text-cat-2">Pool Available</p>
                            <p className="text-sm font-bold text-cat-2 truncate">{formatCompactCurrency(unallocatedPool, limitCurrency)}</p>
                          </div>
                        </div>
                      </div>
                    )}
                    <p className="text-xs text-primary-700 mt-1 dark:text-neutral-200"><Info className="w-3 h-3 inline" /> {limit.parentLimitId ? 'Sub-limit from parent pool' : 'From Group Limit'}</p>
                  </div>
                );
              })}

              {/* Add limit in another currency */}
              {availableGroupCurrencies.length > 0 && (
                <div className="pt-2 border-t border-primary-200 dark:border-primary-700">
                  <p className="text-xs text-neutral-500 mb-2 dark:text-neutral-400">Add limit in another currency:</p>
                  <div className="flex flex-wrap gap-2">
                    {availableGroupCurrencies.map(g => (
                      <Button key={g.currency} variant="outline" size="sm" onClick={() => onAllocateLimit(entity, g.currency)}>
                        <Plus className="w-3 h-3 mr-1" />{g.currency}
                      </Button>
                    ))}
                  </div>
                </div>
              )}
            </div>
          ) : (
            <div className="text-center py-4">
              <CreditCard className="w-10 h-10 text-neutral-300 mx-auto mb-2 dark:text-neutral-600" />
              <p className="text-sm text-neutral-500 dark:text-neutral-400">No limit allocated</p>
              {groupLimits.length > 0 && (
                <div className="mt-2">
                  <p className="text-xs text-neutral-400 mb-2 dark:text-neutral-500">Available from Group Limits:</p>
                  <div className="flex flex-wrap gap-2 justify-center">
                    {groupLimits.map(g => (
                      <Badge key={g.currency} variant="info" size="sm">
                        {g.currency}: {formatCompactCurrency(g.unallocatedAmount || 0, g.currency)}
                      </Badge>
                    ))}
                  </div>
                </div>
              )}
              <Button size="sm" className="mt-3" onClick={() => onAllocateLimit(entity)}><Plus className="w-4 h-4 mr-1" />Allocate Limit</Button>
            </div>
          )}
        </div>

        <div className={cn('rounded-xl p-4', entity.isBankCustomer ? 'bg-success-50 border border-success-200 dark:bg-success-500/10 dark:border-success-500/30' : 'bg-neutral-50 dark:bg-primary-950')}>
          <h4 className="text-sm font-semibold mb-3"><Wallet className={cn('w-4 h-4 inline mr-1', entity.isBankCustomer ? 'text-success-600 dark:text-success-300' : 'text-neutral-400 dark:text-neutral-500')} />Banking Relationship</h4>
          {entity.isBankCustomer ? (
            <div className="space-y-2">
              <div className="flex justify-between"><span className="text-sm text-neutral-600 dark:text-neutral-300">Status</span><span className="text-sm text-success-700 dark:text-success-300"><CheckCircle className="w-4 h-4 inline" /> Bank Customer</span></div>
              {entity.bancsCustomerId && <div className="flex justify-between"><span className="text-sm text-neutral-600 dark:text-neutral-300">BANCS ID</span><span className="text-sm font-mono">{entity.bancsCustomerId}<button onClick={() => { navigator.clipboard.writeText(entity.bancsCustomerId!); toast.success('Copied!'); }} className="ml-2 text-neutral-400 hover:text-primary-600 transition-colors dark:text-neutral-500"><Copy className="w-3 h-3 inline" /></button></span></div>}
            </div>
          ) : <p className="text-sm text-neutral-500 text-center dark:text-neutral-400">Not a bank customer</p>}
        </div>

        <div>
          <h4 className="text-sm font-semibold mb-3 text-neutral-800 dark:text-neutral-100">Entity Details</h4>
          <div className="space-y-2 text-sm">
            <div className="flex justify-between"><span className="text-neutral-500 dark:text-neutral-400"><Building2 className="w-4 h-4 inline mr-1" />Type</span><span className="text-neutral-900 dark:text-neutral-50">{typeConfig.label}</span></div>
            <div className="flex justify-between"><span className="text-neutral-500 dark:text-neutral-400"><Globe className="w-4 h-4 inline mr-1" />Country</span><span className="text-neutral-900 dark:text-neutral-50">{entity.countryCode || '-'}</span></div>
            <div className="flex justify-between"><span className="text-neutral-500 dark:text-neutral-400"><Banknote className="w-4 h-4 inline mr-1" />Currency</span><span className="text-neutral-900 dark:text-neutral-50">{entity.functionalCurrency}</span></div>
            <div className="flex justify-between"><span className="text-neutral-500 dark:text-neutral-400"><Percent className="w-4 h-4 inline mr-1" />Ownership</span><span className="text-neutral-900 dark:text-neutral-50">{entity.ownershipPercent || 100}%</span></div>
          </div>
        </div>

        <div>
          <h4 className="text-sm font-semibold mb-3 text-neutral-800 dark:text-neutral-100">Treasury Capabilities</h4>
          <div className="grid grid-cols-2 gap-2">
            {[
              { enabled: entity.isBankCustomer, label: 'Bank Customer', icon: Wallet },
              { enabled: entity.canHoldPhysicalAccounts, label: 'Physical Accts', icon: Banknote },
              { enabled: entity.canParticipatePooling, label: 'Pooling', icon: PiggyBank },
              { enabled: entity.canParticipateNetting, label: 'Netting', icon: ArrowLeftRight },
              { enabled: entity.isTreasuryCenter, label: 'Treasury Center', icon: Landmark },
              { enabled: entity.ihbEnabled, label: 'IHB Enabled', icon: Zap },
            ].map(cap => (
              <div key={cap.label} className={cn('flex items-center gap-2 px-3 py-2 rounded-lg text-sm transition-colors', cap.enabled ? 'bg-success-50 text-success-700 dark:bg-success-500/10 dark:text-success-300' : 'bg-neutral-100 text-neutral-400 dark:bg-primary-800 dark:text-neutral-500')}>
                <cap.icon className="w-4 h-4" /><span className="flex-1">{cap.label}</span>{cap.enabled ? <Check className="w-3 h-3" /> : <X className="w-3 h-3" />}
              </div>
            ))}
          </div>
        </div>
      </div>

      <div className="p-4 border-t border-neutral-200 flex gap-2 dark:border-primary-800">
        <Button variant="outline" className="flex-1" onClick={() => onEdit(entity)}><Edit className="w-4 h-4 mr-1" />Edit</Button>
        <Button className="flex-1" onClick={() => onAllocateLimit(entity)}><CreditCard className="w-4 h-4 mr-1" />{hasLimits ? 'Update' : 'Allocate'} Limit</Button>
      </div>
    </div>
  );
};

// ============================================================================
// MAIN PAGE COMPONENT
// ============================================================================

const LegalEntitiesPage: React.FC = () => {
  const [entities, setEntities] = useState<LegalEntity[]>([]);
  const [corporates, setCorporates] = useState<Corporate[]>([]);
  const [selectedCorporateId, setSelectedCorporateId] = useState<string>('');
  const [loading, setLoading] = useState(false);
  const [initialLoading, setInitialLoading] = useState(true);
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());
  const [selectedEntity, setSelectedEntity] = useState<LegalEntity | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [filterType, setFilterType] = useState<string>('');
  const [filterStatus, setFilterStatus] = useState<string>('');
  const [filterBankCustomer, setFilterBankCustomer] = useState<string>('');
  const [error, setError] = useState<string | null>(null);
  const [showLimitModal, setShowLimitModal] = useState(false);
  const [limitEntity, setLimitEntity] = useState<LegalEntity | null>(null);
  const [showEntityModal, setShowEntityModal] = useState(false);
  const [editEntity, setEditEntity] = useState<LegalEntity | null>(null);
  // Multi-currency support: array of group limits (one per currency)
  const [groupLimits, setGroupLimits] = useState<CreditLimit[]>([]);
  // Entity limits keyed by entityId, each entity can have multiple currency limits
  const [entityLimits, setEntityLimits] = useState<Record<string, CreditLimit[]>>({});
  // Selected currency for allocation modal
  const [selectedLimitCurrency, setSelectedLimitCurrency] = useState<string>('AED');


  useEffect(() => {
    const loadCorporates = async () => {
      try {
        const data = await corporateApi.getAll();
        setCorporates(data);
        if (data.length > 0) setSelectedCorporateId(data[0].id);
      } catch (err) {
        setError('Failed to load corporates');
      } finally {
        setInitialLoading(false);
      }
    };
    loadCorporates();
  }, []);

  const loadData = useCallback(async () => {
    if (!selectedCorporateId) return;
    setLoading(true);
    setError(null);
    try {
      const entitiesData = await legalEntityApi.getAllByCorporate(selectedCorporateId);
      setEntities(entitiesData);
      setExpandedIds(new Set(entitiesData.filter(e => !e.parentEntityId).map(e => e.id)));

      // Multi-currency: Get all group limits (one per currency)
      const groups = await creditLimitApi.getGroupLimits(selectedCorporateId);
      setGroupLimits(groups);

      // Multi-currency: Group entity limits by entityId, each entity can have limits in multiple currencies
      const limits = await creditLimitApi.getInternalLimits(selectedCorporateId);
      const entityLimitsMap: Record<string, CreditLimit[]> = {};
      limits.filter(l => l.targetType === 'LEGAL_ENTITY').forEach(l => {
        if (!entityLimitsMap[l.targetId]) entityLimitsMap[l.targetId] = [];
        entityLimitsMap[l.targetId].push(l);
      });
      setEntityLimits(entityLimitsMap);
    } catch { setError('Failed to load data'); } finally { setLoading(false); }
  }, [selectedCorporateId]);

  useEffect(() => { loadData(); }, [loadData]);

  // Multi-currency: Flatten all limits for stats
  const allEntityLimits = Object.values(entityLimits).flat();
  const stats: EntityStats = {
    totalEntities: entities.length,
    activeEntities: entities.filter(e => e.status === 'ACTIVE').length,
    bankCustomers: entities.filter(e => e.isBankCustomer).length,
    entitiesWithLimits: Object.keys(entityLimits).length,
    entitiesNearLimit: allEntityLimits.filter(l => l && l.isAtWarningLevel).length,
    treasuryCenters: entities.filter(e => e.isTreasuryCenter).length,
    ihbEnabledEntities: entities.filter(e => e.ihbEnabled).length,
  };

  // Handler for the shared EntityAllocationModal
  const handleSaveEntityLimit = async (data: EntityLimitAllocationData) => {
    // Check if we're updating an existing limit
    const existingEntityLimits = entityLimits[data.entityId] || [];
    const existingLimit = existingEntityLimits.find(l => l.currency === data.currency);

    const savedLimit = existingLimit
      ? await creditLimitApi.updateEntitySubLimitAmount(existingLimit.id, data.amount, data.approvedBy)
      : await creditLimitApi.createEntitySubLimit({
          corporateId: selectedCorporateId,
          entityId: data.entityId,
          limitName: data.limitName,
          amount: data.amount,
          currency: data.currency,
          approvedBy: data.approvedBy,
          hardLimit: data.hardLimit,
          requiresApproval: data.requiresApproval,
          approvalThreshold: data.approvalThreshold,
          warningThreshold: data.warningThreshold,
          criticalThreshold: data.criticalThreshold,
          effectiveFrom: data.effectiveFrom,
          effectiveTo: data.effectiveTo,
          notes: data.notes,
          // Find and link to the appropriate parent limit for this currency
          parentLimitId: groupLimits.find(g => g.currency === data.currency)?.id || undefined,
        });
    // Multi-currency: Update the correct currency limit for this entity
    setEntityLimits(prev => {
      const existingLimits = prev[data.entityId] || [];
      const updatedLimits = existingLimits.filter(l => l.currency !== data.currency);
      return { ...prev, [data.entityId]: [...updatedLimits, savedLimit] };
    });
    setEntities(prev => prev.map(e => e.id === data.entityId ? { ...e, internalCreditLimit: data.amount, internalLimitCurrency: data.currency } : e));
    // Reload limits to update parent pool's unallocatedAmount
    const [groups, limits] = await Promise.all([
      creditLimitApi.getGroupLimits(selectedCorporateId),
      creditLimitApi.getInternalLimits(selectedCorporateId),
    ]);
    setGroupLimits(groups);
    // Multi-currency: Rebuild entityLimits map with arrays
    const entityLimitsMap: Record<string, CreditLimit[]> = {};
    limits.filter(l => l.targetType === 'LEGAL_ENTITY').forEach(l => {
      if (!entityLimitsMap[l.targetId]) entityLimitsMap[l.targetId] = [];
      entityLimitsMap[l.targetId].push(l);
    });
    setEntityLimits(entityLimitsMap);
    if (selectedEntity?.id === data.entityId) setSelectedEntity(prev => prev ? { ...prev, internalCreditLimit: data.amount, internalLimitCurrency: data.currency } : null);
    toast.success(existingLimit ? 'Limit updated successfully' : 'Limit allocated successfully');
  };

  // Helper to open allocate limit modal
  // Takes optional currency - if not provided, uses entity's functional currency
  const handleOpenAllocateLimitModal = (entity: LegalEntity, currency?: string) => {
    const targetCurrency = currency || entity.functionalCurrency || 'AED';
    setSelectedLimitCurrency(targetCurrency);
    setLimitEntity(entity);
    setShowLimitModal(true);
  };

  const handleSaveEntity = async (data: LegalEntityFormData, entityId?: string) => {
    if (entityId) {
      const updated = await legalEntityApi.update(entityId, data);
      setEntities(prev => prev.map(e => e.id === entityId ? updated : e));
      if (selectedEntity?.id === entityId) setSelectedEntity(updated);
    } else {
      const created = await legalEntityApi.create(selectedCorporateId, data);
      setEntities(prev => [...prev, created]);
    }
  };

  const handleEditEntity = (entity: LegalEntity) => { setEditEntity(entity); setShowEntityModal(true); };
  const handleAddEntity = () => { setEditEntity(null); setShowEntityModal(true); };

  const filteredEntities = entities.filter(e => {
    if (searchQuery && !e.entityName.toLowerCase().includes(searchQuery.toLowerCase()) && !e.entityCode.toLowerCase().includes(searchQuery.toLowerCase())) return false;
    if (filterType && e.entityType !== filterType) return false;
    if (filterStatus && e.status !== filterStatus) return false;
    if (filterBankCustomer === 'bank' && !e.isBankCustomer) return false;
    if (filterBankCustomer === 'non-bank' && e.isBankCustomer) return false;
    if (filterBankCustomer === 'ihb' && !e.ihbEnabled) return false;
    return true;
  });

  const rootEntities = filteredEntities.filter(e => !e.parentEntityId);

  // Multi-currency group limits — pre-compute the hero figures + breakdown so
  // we can render them whether the corporate has 0, 1, or N currency limits.
  const hasGroupLimits = groupLimits.length > 0;
  const primaryGroupLimit = groupLimits[0];
  const primaryCurrency = primaryGroupLimit?.currency || 'AED';
  const totalAllocationPercent = primaryGroupLimit && primaryGroupLimit.limitAmount > 0
    ? ((primaryGroupLimit.allocatedToChildren || 0) / primaryGroupLimit.limitAmount) * 100
    : 0;
  const groupLimitsHeroValue = hasGroupLimits
    ? (groupLimits.length === 1
        ? formatCompactCurrency(primaryGroupLimit.limitAmount || 0, primaryCurrency)
        : `${groupLimits.length} CCY`)
    : '—';
  // Sub-line: for single-currency, show available headroom; for multi-currency,
  // show one chip per currency so AED / GBP / USD line up vertically instead of
  // truncating into "AED: AED 100B, GBP: GB…".
  const groupLimitsHeroSub = hasGroupLimits ? (
    groupLimits.length === 1 ? (
      <span>{formatCompactCurrency(primaryGroupLimit.unallocatedAmount || 0, primaryCurrency)} unallocated · {totalAllocationPercent.toFixed(1)}% allocated</span>
    ) : (
      <div className="flex flex-wrap gap-1.5">
        {groupLimits.map(g => (
          <span
            key={g.currency || 'N/A'}
            className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-xs font-medium bg-accent-50 text-accent-700 ring-1 ring-accent-200 dark:bg-accent-500/15 dark:text-accent-300 dark:ring-accent-500/30"
            title={`${g.currency}: ${formatCompactCurrency(g.limitAmount || 0, g.currency || 'AED')}`}
          >
            <span className="font-semibold">{g.currency || 'N/A'}</span>
            <span className="opacity-80">{formatCompactCurrency(g.limitAmount || 0, g.currency || 'AED')}</span>
          </span>
        ))}
      </div>
    )
  ) : (
    <span className="text-neutral-400 dark:text-neutral-500">No group limit configured</span>
  );

  // Register the page-level toolbar in the Layout header — same pattern as
  // every other treasury page. Refresh / Export / Add Entity no longer float
  // inside the content body.
  usePageHeaderActions(
    () => (
      <>
        <Button variant="outline" size="sm" onClick={loadData} disabled={loading}>
          {loading ? <Loader2 className="w-4 h-4 mr-1 animate-spin" /> : <RefreshCw className="w-4 h-4 mr-1" />}
          Refresh
        </Button>
        <Button variant="outline" size="sm">
          <Download className="w-4 h-4 mr-1" />Export
        </Button>
        <Button size="sm" onClick={handleAddEntity}>
          <Plus className="w-4 h-4 mr-1" />Add Entity
        </Button>
      </>
    ),
    [loadData, loading]
  );

  if (initialLoading) return <div className="min-h-screen bg-neutral-50 flex items-center justify-center dark:bg-primary-950"><Loader2 className="w-8 h-8 animate-spin text-primary-600 dark:text-primary-200" /></div>;

  return (
    <Page>
      {/* Refresh / Export / Add Entity now live in the Layout header
          (registered above via usePageHeaderActions). */}

      {/* Corporate Selector */}
      <Card padding="sm" className="bg-gradient-to-r from-primary-50/50 via-white to-primary-50/50 border-primary-100/50 animate-fade-in dark:from-primary-900 dark:via-primary-900 dark:to-primary-900" style={{ animationDelay: '0.05s' }}>
        <div className="flex items-center gap-4">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-lg bg-primary-100 flex items-center justify-center dark:bg-primary-700">
              <Building2 className="w-4 h-4 text-primary-600 dark:text-primary-200" />
            </div>
            <div className="flex flex-col">
              <span className="text-xs text-neutral-500 dark:text-neutral-400">Corporate</span>
              <select
                className="px-3 py-1.5 border border-neutral-200 rounded-lg text-sm bg-white min-w-[240px] focus:ring-2 focus:ring-primary-500 focus:border-primary-500 dark:border-primary-800 dark:bg-primary-900"
                value={selectedCorporateId}
                onChange={(e) => setSelectedCorporateId(e.target.value)}
              >
                <option value="">Select Corporate...</option>
                {corporates.map(c => <option key={c.id} value={c.id}>{c.legalName}</option>)}
              </select>
            </div>
          </div>
          <div className="flex-1" />
          {selectedCorporateId && (
            <span className="text-sm text-primary-700 dark:text-neutral-200">
              <span className="font-medium">{corporates.find(c => c.id === selectedCorporateId)?.legalName}</span>
            </span>
          )}
        </div>
      </Card>

      {/* Error Banner */}
      {error && (
        <div className="bg-error-50 border border-error-200 rounded-xl p-4 dark:bg-error-500/10 dark:border-error-500/30">
          <div className="flex items-start gap-3">
            <AlertTriangle className="w-5 h-5 text-error-600 mt-0.5 dark:text-error-300" />
            <p className="text-sm text-error-700 dark:text-error-300">{error}</p>
          </div>
        </div>
      )}

      {/* Info Banner */}
      <Card padding="sm" className="bg-gradient-to-r from-primary-50/50 via-white to-primary-50/50 border-primary-100/50 animate-fade-in dark:from-primary-900 dark:via-primary-900 dark:to-primary-900" style={{ animationDelay: '0.1s' }}>
        <div className="flex items-start gap-3">
          <div className="w-9 h-9 rounded-lg bg-primary-100 flex items-center justify-center shrink-0 dark:bg-primary-700">
            <Layers className="w-4 h-4 text-primary-600 dark:text-primary-200" />
          </div>
          <div>
            <p className="text-sm font-medium text-primary-800 dark:text-neutral-100">Corporate Entity Structure</p>
            <p className="text-sm text-primary-700 mt-1 dark:text-neutral-200">
              <strong className="text-cat-2">Treasury Centers</strong> provide intercompany funding.
              <strong className="text-success-700 dark:text-success-300"> Bank Customers</strong> have direct banking relationships with external limits.
              <strong className="text-info-700 dark:text-info-300"> IHB-enabled</strong> entities can participate in intercompany lending/borrowing.
            </p>
          </div>
        </div>
      </Card>

      {/* Headline figures — Total Entities + Group Limit headroom. Lifted
          out of the operational strip so the treasurer's first read lands on
          the structural shape of the group (how many entities, what aggregate
          credit envelope governs them). Operational tiles sit below. */}
      <HeroMetricCard
        primary={{
          label: 'Total Entities',
          value: stats.totalEntities.toString(),
          sub: (
            <span>
              <strong className="text-primary-700 dark:text-neutral-200">{stats.activeEntities}</strong> active
              {stats.treasuryCenters > 0 && (
                <> · <strong className="text-cat-2">{stats.treasuryCenters}</strong> treasury {stats.treasuryCenters === 1 ? 'center' : 'centers'}</>
              )}
              {stats.ihbEnabledEntities > 0 && (
                <> · <strong className="text-info-700 dark:text-info-300">{stats.ihbEnabledEntities}</strong> IHB-enabled</>
              )}
            </span>
          ),
        }}
        secondary={{
          label: 'Group Limits',
          value: groupLimitsHeroValue,
          sub: groupLimitsHeroSub,
        }}
        icon={<GitBranch className="w-7 h-7 text-accent-600 dark:text-accent-300" />}
      />

      {/* Operational metrics — secondary strip below the hero. */}
      <StatsCards stats={stats} loading={loading} />

      {/* Main Content Grid */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Hierarchy Tree */}
        <div className="lg:col-span-2">
          <Card padding="none" className="animate-fade-in" style={{ animationDelay: '0.4s' }}>
            <div className="flex items-center justify-between p-4 border-b border-neutral-200 dark:border-primary-800">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-primary-500 to-primary-700 flex items-center justify-center">
                  <GitBranch className="w-5 h-5 text-white" />
                </div>
                <div>
                  <h2 className="section-title">Entity Hierarchy</h2>
                  <p className="text-sm text-neutral-500 dark:text-neutral-400">{entities.length} entities</p>
                </div>
              </div>
              <div className="flex items-center gap-2">
                <Button variant="ghost" size="sm" onClick={() => setExpandedIds(new Set(entities.map(e => e.id)))}>
                  <ChevronDown className="w-4 h-4 mr-1" />Expand All
                </Button>
                <Button variant="ghost" size="sm" onClick={() => setExpandedIds(new Set())}>
                  <ChevronUp className="w-4 h-4 mr-1" />Collapse
                </Button>
              </div>
            </div>

            {/* Search & Filters */}
            <div className="px-4 py-3 border-b border-neutral-200 flex flex-wrap gap-4 dark:border-primary-800">
              <div className="flex-1 relative min-w-[200px]">
                <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400 dark:text-neutral-500" />
                <input 
                  type="text" 
                  placeholder="Search entities..." 
                  value={searchQuery} 
                  onChange={(e) => setSearchQuery(e.target.value)} 
                  className="w-full pl-10 pr-4 py-2 border border-neutral-300 rounded-lg text-sm dark:border-primary-700" 
                />
              </div>
              <select 
                className="px-3 py-2 border border-neutral-300 rounded-lg text-sm dark:border-primary-700" 
                value={filterType} 
                onChange={(e) => setFilterType(e.target.value)}
              >
                <option value="">All Types</option>
                {Object.entries(entityTypeConfig).map(([t, c]) => <option key={t} value={t}>{c.label}</option>)}
              </select>
              <select 
                className="px-3 py-2 border border-neutral-300 rounded-lg text-sm dark:border-primary-700" 
                value={filterStatus} 
                onChange={(e) => setFilterStatus(e.target.value)}
              >
                <option value="">All Status</option>
                {Object.entries(statusConfig).map(([s, c]) => <option key={s} value={s}>{c.label}</option>)}
              </select>
              <select 
                className="px-3 py-2 border border-neutral-300 rounded-lg text-sm dark:border-primary-700" 
                value={filterBankCustomer} 
                onChange={(e) => setFilterBankCustomer(e.target.value)}
              >
                <option value="">All</option>
                <option value="bank">Bank Customers</option>
                <option value="non-bank">Non-Bank</option>
                <option value="ihb">IHB Enabled</option>
              </select>
            </div>

            {/* Tree Content */}
            <div className="p-4 space-y-1 max-h-[600px] overflow-y-auto">
              {loading ? (
                <div className="flex justify-center py-12">
                  <Loader2 className="w-8 h-8 animate-spin text-primary-600 dark:text-primary-200" />
                </div>
              ) : rootEntities.length === 0 ? (
                <div className="text-center py-12">
                  <Building2 className="w-12 h-12 text-neutral-300 mx-auto mb-4 dark:text-neutral-600" />
                  <p className="text-neutral-500 dark:text-neutral-400">No entities found</p>
                  <p className="text-sm text-neutral-400 mt-1 dark:text-neutral-500">Create your first legal entity to get started</p>
                </div>
              ) : (
                rootEntities.map(entity => (
                  <HierarchyTreeNode 
                    key={entity.id} 
                    entity={entity} 
                    entities={filteredEntities} 
                    entityLimits={entityLimits} 
                    expandedIds={expandedIds} 
                    onToggle={id => setExpandedIds(prev => { 
                      const next = new Set(prev); 
                      next.has(id) ? next.delete(id) : next.add(id); 
                      return next; 
                    })} 
                    selectedId={selectedEntity?.id || null} 
                    onSelect={setSelectedEntity} 
                    level={0} 
                  />
                ))
              )}
            </div>
          </Card>
        </div>

        {/* Detail Panel */}
        <div>
          {selectedEntity ? (
            <EntityDetailPanel
              entity={selectedEntity}
              creditLimits={entityLimits[selectedEntity.id] || []}
              groupLimits={groupLimits}
              onClose={() => setSelectedEntity(null)}
              onEdit={handleEditEntity}
              onAllocateLimit={handleOpenAllocateLimitModal}
            />
          ) : (
            <Card className="h-full flex flex-col items-center justify-center animate-fade-in" style={{ animationDelay: '0.45s' }}>
              <Eye className="w-12 h-12 text-neutral-300 mb-4 dark:text-neutral-600" />
              <p className="text-neutral-500 font-medium dark:text-neutral-400">Select an Entity</p>
              <p className="text-sm text-neutral-400 mt-1 dark:text-neutral-500">Click on an entity to view details</p>
            </Card>
          )}
        </div>
      </div>

      {/* Legend */}
      <Card padding="sm" className="animate-fade-in" style={{ animationDelay: '0.5s' }}>
        <div className="flex flex-wrap items-center gap-6">
          <p className="text-sm font-medium text-neutral-600 dark:text-neutral-300">Legend:</p>
          <div className="flex items-center gap-2">
            <div className="p-1.5 rounded bg-primary-100 dark:bg-primary-700"><Crown className="w-3 h-3 text-primary-700 dark:text-neutral-200" /></div>
            <span className="text-xs text-neutral-600 dark:text-neutral-300">Holding</span>
          </div>
          <div className="flex items-center gap-2">
            <div className="p-1.5 rounded bg-info-100 dark:bg-info-500/20"><Building2 className="w-3 h-3 text-info-700 dark:text-info-300" /></div>
            <span className="text-xs text-neutral-600 dark:text-neutral-300">Subsidiary</span>
          </div>
          <div className="flex items-center gap-2">
            <div className="p-1.5 rounded bg-cyan-100 dark:bg-cyan-500/20"><Building className="w-3 h-3 text-cyan-700 dark:text-cyan-300" /></div>
            <span className="text-xs text-neutral-600 dark:text-neutral-300">Branch</span>
          </div>
          <div className="flex items-center gap-2">
            <div className="p-1.5 rounded bg-cat-2/10 border border-cat-2/30 dark:bg-cat-2/15"><Landmark className="w-3 h-3 text-cat-2" /></div>
            <span className="text-xs text-neutral-600 dark:text-neutral-300">Treasury Center</span>
          </div>
          <div className="flex items-center gap-2">
            <div className="p-1.5 rounded bg-success-100 dark:bg-success-500/20"><Wallet className="w-3 h-3 text-success-700 dark:text-success-300" /></div>
            <span className="text-xs text-neutral-600 dark:text-neutral-300">Bank Customer</span>
          </div>
          <div className="flex items-center gap-2">
            <div className="p-1.5 rounded bg-info-50 border border-info-300 dark:bg-info-500/10"><PiggyBank className="w-3 h-3 text-info-600 dark:text-info-300" /></div>
            <span className="text-xs text-neutral-600 dark:text-neutral-300">IHB Enabled</span>
          </div>
          <div className="flex items-center gap-2">
            <div className="p-1.5 rounded bg-warning-100 dark:bg-warning-500/20"><AlertTriangle className="w-3 h-3 text-warning-600 dark:text-warning-300" /></div>
            <span className="text-xs text-neutral-600 dark:text-neutral-300">Near Limit</span>
          </div>
        </div>
      </Card>

      {/* Modals */}
      <EntityAllocationModal
        isOpen={showLimitModal}
        onClose={() => { setShowLimitModal(false); setLimitEntity(null); }}
        entity={limitEntity}
        existingLimit={limitEntity ? (entityLimits[limitEntity.id] || []).find(l => l.currency === selectedLimitCurrency) as InternalLimitResponse | null || null : null}
        groupLimits={groupLimits as InternalLimitResponse[]}
        existingEntityCurrencies={limitEntity ? (entityLimits[limitEntity.id] || []).map(l => l.currency) : []}
        corporateId={selectedCorporateId}
        onSave={handleSaveEntityLimit}
      />
      
      <EntityFormModal 
        isOpen={showEntityModal} 
        onClose={() => { setShowEntityModal(false); setEditEntity(null); }} 
        entity={editEntity} 
        entities={entities}
        corporateId={selectedCorporateId}
        onSave={handleSaveEntity} 
      />
    </Page>
  );
};

export default LegalEntitiesPage;