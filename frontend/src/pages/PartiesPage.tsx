import React, { useState, useEffect, useCallback } from 'react';
import {
  Search, Download, RefreshCw, Plus, Building2, User, Users, Landmark, Briefcase,
  Eye, Edit, MoreHorizontal, CheckCircle, XCircle, AlertTriangle, Clock, Shield,
  CreditCard, Mail, MapPin, Banknote, X, Loader2, Link2, Repeat, Wallet, Building,
  ArrowRightLeft, TrendingUp, Globe, DollarSign, Settings, Save,
} from 'lucide-react';
import { Card, Badge, Button } from '../components/ui';
import { CurrencyPicker } from '../components/ui/CurrencyPicker';
import { Modal, ProgressBar } from '../components/ui/enhanced';
import { usePageHeaderActions } from '../context/PageHeaderContext';
import { formatCurrency, cn } from '../utils';

// Import API and types from main api.ts
import { 
  partiesApi,
  legalEntityApi, 
  corporatesApi, 
  Party, 
  PartyStats, 
  PartySearchRequest,
  PartyRole, 
  PartyType, 
  KycStatus, 
  RiskRating,
  IcSettlementMethod,
  LegalEntity,
  PartyBankAccount,
  Corporate,
  ApiResponse,
} from '../services/api';
import { Page } from '../components/layout/Page';

// ============================================================================
// HELPER FUNCTIONS - Consistent Response Extraction
// ============================================================================

/**
 * Safely extract array from API response (handles various response formats)
 */
const extractArray = <T,>(response: ApiResponse<T[] | { content: T[] } | { corporates: T[] }>): T[] => {
  if (!response) return [];
  
  // Handle { success: true, data: [...] }
  if (response.success && response.data) {
    if (Array.isArray(response.data)) return response.data;
    if ('content' in response.data && Array.isArray((response.data as any).content)) {
      return (response.data as any).content;
    }
    if ('corporates' in response.data && Array.isArray((response.data as any).corporates)) {
      return (response.data as any).corporates;
    }
  }
  
  // Handle direct array
  if (Array.isArray(response)) return response as unknown as T[];
  
  // Handle { content: [...] } directly
  if ('content' in response && Array.isArray((response as any).content)) {
    return (response as any).content;
  }
  
  return [];
};

/**
 * Show toast notification (could be replaced with actual toast library)
 */
const showToast = (message: string, type: 'success' | 'error' | 'info' = 'info') => {
  // Simple implementation - in production, use a toast library like react-hot-toast
  console.log(`[${type.toUpperCase()}]`, message);
  // For now, we'll use a simple approach. In real implementation:
  // toast[type](message);
};

// ============================================================================
// CONFIGURATION
// ============================================================================

const roleConfig: Record<PartyRole, { label: string; icon: React.ElementType; color: string; bgColor: string }> = {
  CUSTOMER: { label: 'Customer', icon: Users, color: 'text-info-600 dark:text-info-300', bgColor: 'bg-info-50 dark:bg-info-500/10' },
  VENDOR: { label: 'Vendor', icon: Briefcase, color: 'text-accent-600 dark:text-accent-300', bgColor: 'bg-accent-50 dark:bg-accent-500/10' },
  EMPLOYEE: { label: 'Employee', icon: User, color: 'text-success-600 dark:text-success-300', bgColor: 'bg-success-50 dark:bg-success-500/10' },
  GOVERNMENT: { label: 'Government', icon: Landmark, color: 'text-warning-600 dark:text-warning-300', bgColor: 'bg-warning-50 dark:bg-warning-500/10' },
  FINANCIAL: { label: 'Financial', icon: Banknote, color: 'text-primary-600 dark:text-primary-200', bgColor: 'bg-primary-100 dark:bg-primary-700' },
};

const typeConfig: Record<PartyType, { label: string; icon: React.ElementType }> = {
  INDIVIDUAL: { label: 'Individual', icon: User },
  COMPANY: { label: 'Company', icon: Building2 },
  GOVERNMENT: { label: 'Government', icon: Landmark },
  FINANCIAL_INSTITUTION: { label: 'Financial Institution', icon: Banknote },
};

const kycStatusConfig: Record<KycStatus, { label: string; variant: string; icon: React.ElementType }> = {
  PENDING: { label: 'Pending', variant: 'warning', icon: Clock },
  IN_PROGRESS: { label: 'In Progress', variant: 'info', icon: RefreshCw },
  VERIFIED: { label: 'Verified', variant: 'success', icon: CheckCircle },
  EXPIRED: { label: 'Expired', variant: 'error', icon: AlertTriangle },
  REJECTED: { label: 'Rejected', variant: 'error', icon: XCircle },
  EXEMPTED: { label: 'Exempted', variant: 'neutral', icon: Shield },
};

const riskConfig: Record<RiskRating, { label: string; variant: string }> = {
  LOW: { label: 'Low', variant: 'success' },
  MEDIUM: { label: 'Medium', variant: 'warning' },
  HIGH: { label: 'High', variant: 'error' },
  PROHIBITED: { label: 'Prohibited', variant: 'error' },
};

const icSettlementConfig: Record<IcSettlementMethod, { label: string; icon: React.ElementType }> = {
  NETTING: { label: 'Netting', icon: ArrowRightLeft },
  DIRECT_TRANSFER: { label: 'Direct Transfer', icon: Wallet },
  IHB: { label: 'IHB', icon: Building },
  MANUAL: { label: 'Manual', icon: Settings },
};

// ============================================================================
// BADGE COMPONENTS
// ============================================================================

const PoboBadge: React.FC<{ eligible?: boolean; defaultPayerCode?: string }> = ({ eligible, defaultPayerCode }) => {
  if (!eligible) return null;
  return (
    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium bg-gradient-to-r from-info-50 to-cat-1-soft text-cat-1 border border-cat-1/20 dark:border-cat-1/30 dark:from-info-500/15 dark:to-cat-1/15">
      <Wallet className="w-3 h-3" />
      POBO
      {defaultPayerCode && <span className="text-cat-1 font-normal">→ {defaultPayerCode}</span>}
    </span>
  );
};

const IntercompanyBadge: React.FC<{ isIntercompany?: boolean; linkedEntityCode?: string; settlementMethod?: IcSettlementMethod }> = 
  ({ isIntercompany, linkedEntityCode, settlementMethod }) => {
  if (!isIntercompany) return null;
  const SettlementIcon = settlementMethod ? icSettlementConfig[settlementMethod]?.icon : Link2;
  return (
    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium bg-gradient-to-r from-cat-2-soft to-cat-4-soft text-cat-2 border border-cat-2/20 dark:border-cat-2/30 dark:from-cat-2/15 dark:to-cat-4/15">
      <Link2 className="w-3 h-3" />
      IC
      {linkedEntityCode && <span className="text-cat-2 font-normal">↔ {linkedEntityCode}</span>}
      {settlementMethod && <SettlementIcon className="w-3 h-3 ml-1" />}
    </span>
  );
};

const NettingBadge: React.FC<{ eligible?: boolean }> = ({ eligible }) => {
  if (!eligible) return null;
  return (
    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium bg-gradient-to-r from-cat-5-soft to-cat-3-soft text-cat-3 border border-cat-3/20 dark:border-cat-3/30 dark:from-cat-5/15 dark:to-cat-3/15">
      <Repeat className="w-3 h-3" />
      Netting
    </span>
  );
};

const IcCreditBadge: React.FC<{ limit?: number; exposure?: number }> = ({ limit, exposure }) => {
  if (!limit) return null;
  const utilization = exposure ? (exposure / limit) * 100 : 0;
  const variant = utilization >= 100 ? 'error' : utilization >= 80 ? 'warning' : 'success';
  const colors = {
    success: 'bg-success-50 text-success-700 border-success-200 dark:bg-success-500/10 dark:text-success-300 dark:border-success-500/30',
    warning: 'bg-warning-50 text-warning-700 border-warning-200 dark:bg-warning-500/10 dark:text-warning-300 dark:border-warning-500/30',
    error: 'bg-error-50 text-error-700 border-error-200 dark:bg-error-500/10 dark:text-error-300 dark:border-error-500/30',
  };
  return (
    <span className={cn('inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium border', colors[variant])}>
      <TrendingUp className="w-3 h-3" />
      {utilization.toFixed(0)}% used
    </span>
  );
};

const OwningEntityBadge: React.FC<{ entityCode?: string }> = ({ entityCode }) => {
  if (!entityCode) return null;
  return (
    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs font-medium bg-neutral-100 text-neutral-600 dark:bg-primary-800 dark:text-neutral-300">
      <Building className="w-3 h-3" />
      {entityCode}
    </span>
  );
};

// ============================================================================
// ENTITY SELECTOR
// ============================================================================

const EntitySelector: React.FC<{
  value?: string;
  onChange: (entityId: string | undefined) => void;
  entities: LegalEntity[];
}> = ({ value, onChange, entities }) => (
  <select
    value={value || ''}
    onChange={(e) => onChange(e.target.value || undefined)}
    className="px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent dark:border-primary-700"
  >
    <option value="">All Entities</option>
    {entities.map((entity) => (
      <option key={entity.id} value={entity.id}>{entity.entityCode} - {entity.entityName}</option>
    ))}
  </select>
);

// ============================================================================
// CORPORATE SELECTOR
// ============================================================================

const CorporateSelector: React.FC<{
  value?: string;
  onChange: (corporateId: string | undefined) => void;
  corporates: Corporate[];
  loading?: boolean;
}> = ({ value, onChange, corporates, loading }) => (
  <select
    value={value || ''}
    onChange={(e) => onChange(e.target.value || undefined)}
    disabled={loading}
    className="px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent min-w-[200px] dark:border-primary-700"
  >
    {loading ? (
      <option value="">Loading corporates...</option>
    ) : (
      <>
        <option value="">Select Corporate</option>
        {corporates.map((corp) => (
          <option key={corp.id} value={corp.id}>{corp.corporateId} - {corp.legalName}</option>
        ))}
      </>
    )}
  </select>
);

// ============================================================================
// ADD/EDIT PARTY MODAL
// ============================================================================

interface PartyFormData {
  // Basic
  partyCode: string;
  partyType: PartyType;
  legalName: string;
  displayName: string;
  tradeName: string;
  roles: PartyRole[];
  // Registration
  taxId: string;
  registrationNumber: string;
  registrationCountry: string;
  // Contact
  contactName: string;
  contactEmail: string;
  contactPhone: string;
  // Address
  addressLine1: string;
  addressLine2: string;
  city: string;
  state: string;
  postalCode: string;
  country: string;
  // E-commerce
  ecommerceEnabled: boolean;
  ecommercePlatforms: string;
  // Employee (conditional)
  employeeId: string;
  department: string;
  // Entity & Payment Factory
  owningEntityId: string;
  poboEligible: boolean;
  isIntercompany: boolean;
  nettingEligible: boolean;
}

const defaultFormData: PartyFormData = {
  partyCode: '',
  partyType: 'COMPANY',
  legalName: '',
  displayName: '',
  tradeName: '',
  roles: [],
  taxId: '',
  registrationNumber: '',
  registrationCountry: 'AE',
  contactName: '',
  contactEmail: '',
  contactPhone: '',
  addressLine1: '',
  addressLine2: '',
  city: '',
  state: '',
  postalCode: '',
  country: 'AE',
  ecommerceEnabled: false,
  ecommercePlatforms: '',
  employeeId: '',
  department: '',
  owningEntityId: '',
  poboEligible: false,
  isIntercompany: false,
  nettingEligible: false,
};

const PartyFormModal: React.FC<{
  isOpen: boolean;
  onClose: () => void;
  party?: Party | null;
  corporateId: string;
  entities: LegalEntity[];
  onSave: (party: Party) => void;
}> = ({ isOpen, onClose, party, corporateId, entities, onSave }) => {
  const [formData, setFormData] = useState<PartyFormData>(defaultFormData);
  const [saving, setSaving] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [activeTab, setActiveTab] = useState<string>('basic');

  // Bank Account state (for edit mode)
  const [bankAccounts, setBankAccounts] = useState<PartyBankAccount[]>([]);
  const [showBankAccountForm, setShowBankAccountForm] = useState(false);
  const [bankAccountForm, setBankAccountForm] = useState({
    label: '', holderName: '', bankName: '', bankCode: '', iban: '', accountNumber: '', routingNumber: '', currency: 'AED', isPrimary: false
  });
  const [savingBankAccount, setSavingBankAccount] = useState(false);

  // Document state (for edit mode)
  const [documents, setDocuments] = useState<any[]>([]);

  const isEditMode = !!party;

  useEffect(() => {
    if (party) {
      setFormData({
        partyCode: party.partyCode || '',
        partyType: party.partyType,
        legalName: party.legalName,
        displayName: party.displayName || '',
        tradeName: party.tradeName || '',
        roles: party.roles || [],
        taxId: party.taxId || '',
        registrationNumber: party.registrationNumber || '',
        registrationCountry: party.registrationCountry || 'AE',
        contactName: party.contactName || '',
        contactEmail: party.contactEmail || '',
        contactPhone: party.contactPhone || '',
        addressLine1: party.addressLine1 || '',
        addressLine2: party.addressLine2 || '',
        city: party.city || '',
        state: party.state || '',
        postalCode: party.postalCode || '',
        country: party.country || 'AE',
        ecommerceEnabled: party.ecommerceEnabled || false,
        ecommercePlatforms: party.ecommercePlatforms?.join(', ') || '',
        employeeId: party.employeeId || '',
        department: party.department || '',
        owningEntityId: party.owningEntityId || '',
        poboEligible: party.poboEligible || false,
        isIntercompany: party.isIntercompany || false,
        nettingEligible: party.nettingEligible || false,
      });
      // Load bank accounts and documents for edit mode
      Promise.all([
        partiesApi.getBankAccounts(party.id).catch(() => ({ success: false, data: [] })),
        partiesApi.getDocuments(party.id).catch(() => ({ success: false, data: [] }))
      ]).then(([bankRes, docRes]) => {
        if (bankRes.success && bankRes.data) {
          setBankAccounts(Array.isArray(bankRes.data) ? bankRes.data : []);
        }
        if (docRes.success && docRes.data) {
          setDocuments(Array.isArray(docRes.data) ? docRes.data : []);
        }
      });
    } else {
      setFormData(defaultFormData);
      setBankAccounts([]);
      setDocuments([]);
    }
    setActiveTab('basic');
    setErrors({});
  }, [party, isOpen]);

  const validateForm = (): boolean => {
    const newErrors: Record<string, string> = {};
    
    if (!formData.legalName.trim()) {
      newErrors.legalName = 'Legal name is required';
    }
    if (formData.roles.length === 0) {
      newErrors.roles = 'At least one role is required';
    }
    if (!formData.registrationCountry) {
      newErrors.registrationCountry = 'Country is required';
    }
    if (formData.contactEmail && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(formData.contactEmail)) {
      newErrors.contactEmail = 'Invalid email format';
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleSubmit = async () => {
    if (!validateForm()) return;

    setSaving(true);
    try {
      // Build party data matching backend CreatePartyRequest/UpdatePartyRequest
      // NOTE: POBO, Intercompany, and Netting flags are updated via separate APIs
      const partyData: Partial<Party> = {
        partyType: formData.partyType,
        legalName: formData.legalName,
        roles: formData.roles,
        registrationCountry: formData.registrationCountry,
        country: formData.country,
        // Optional fields - only include if not empty
        ...(formData.partyCode && { partyCode: formData.partyCode }),
        ...(formData.displayName && { displayName: formData.displayName }),
        ...(formData.tradeName && { tradeName: formData.tradeName }),
        ...(formData.taxId && { taxId: formData.taxId }),
        ...(formData.registrationNumber && { registrationNumber: formData.registrationNumber }),
        ...(formData.contactName && { contactName: formData.contactName }),
        ...(formData.contactEmail && { contactEmail: formData.contactEmail }),
        ...(formData.contactPhone && { contactPhone: formData.contactPhone }),
        ...(formData.addressLine1 && { addressLine1: formData.addressLine1 }),
        ...(formData.addressLine2 && { addressLine2: formData.addressLine2 }),
        ...(formData.city && { city: formData.city }),
        ...(formData.state && { state: formData.state }),
        ...(formData.postalCode && { postalCode: formData.postalCode }),
        // E-commerce
        ecommerceEnabled: formData.ecommerceEnabled,
        ...(formData.ecommercePlatforms && {
          ecommercePlatforms: formData.ecommercePlatforms.split(',').map(p => p.trim()).filter(Boolean)
        }),
        // Employee fields
        ...(formData.employeeId && { employeeId: formData.employeeId }),
        ...(formData.department && { department: formData.department }),
        // Entity context (accepted by backend in create/update)
        ...(formData.owningEntityId && { owningEntityId: formData.owningEntityId }),
      };

      let result: Party;
      if (isEditMode && party) {
        const response = await partiesApi.update(party.id, partyData);
        if (response.success && response.data) {
          result = response.data;
        } else {
          throw new Error(response.message || 'Failed to update party');
        }
      } else {
        const response = await partiesApi.create(partyData, corporateId);
        if (response.success && response.data) {
          result = response.data;
        } else {
          throw new Error(response.message || 'Failed to create party');
        }

        // In create mode, save any pending bank accounts
        if (bankAccounts.length > 0) {
          const pendingAccounts = bankAccounts.filter(acc => acc.id?.startsWith('temp-'));
          for (const account of pendingAccounts) {
            try {
              await partiesApi.addBankAccount(result.id, {
                label: account.label,
                holderName: account.holderName,
                bankName: account.bankName,
                bankCode: account.bankCode,
                iban: account.iban,
                accountNumber: account.accountNumber,
                routingNumber: account.routingNumber,
                currency: account.currency,
                isPrimary: account.isPrimary,
              });
            } catch (bankError) {
              console.error('Failed to add bank account:', bankError);
              // Continue with other accounts even if one fails
            }
          }
        }
      }

      // Update Payment Factory settings via separate APIs
      // These are stored separately and require dedicated endpoints
      const partyId = result.id;

      // Update POBO eligibility if changed
      const currentPobo = party?.poboEligible || false;
      if (formData.poboEligible !== currentPobo) {
        try {
          await partiesApi.updatePoboEligibility(partyId, {
            poboEligible: formData.poboEligible,
          });
        } catch (poboError) {
          console.error('Failed to update POBO eligibility:', poboError);
          // Don't fail the whole operation, just log
        }
      }

      // Update Intercompany config if changed
      const currentIc = party?.isIntercompany || false;
      const currentNetting = party?.nettingEligible || false;
      if (formData.isIntercompany !== currentIc || formData.nettingEligible !== currentNetting) {
        try {
          await partiesApi.updateIntercompanyConfig(partyId, {
            isIntercompany: formData.isIntercompany,
            nettingEligible: formData.nettingEligible,
          });
        } catch (icError) {
          console.error('Failed to update Intercompany config:', icError);
          // Don't fail the whole operation, just log
        }
      }

      showToast(`Party ${isEditMode ? 'updated' : 'created'} successfully`, 'success');
      onSave(result);
      onClose();
    } catch (error: any) {
      console.error('Failed to save party:', error);
      showToast(error.message || 'Failed to save party', 'error');
    } finally {
      setSaving(false);
    }
  };

  const toggleRole = (role: PartyRole) => {
    setFormData(prev => ({
      ...prev,
      roles: prev.roles.includes(role)
        ? prev.roles.filter(r => r !== role)
        : [...prev.roles, role],
    }));
  };

  // Handle adding bank account
  const handleAddBankAccount = async () => {
    if (!party || !isEditMode) return;

    setSavingBankAccount(true);
    try {
      const response = await partiesApi.addBankAccount(party.id, bankAccountForm);
      if (response.success && response.data) {
        setBankAccounts(prev => [...prev, response.data as PartyBankAccount]);
        setBankAccountForm({ label: '', holderName: '', bankName: '', bankCode: '', iban: '', accountNumber: '', routingNumber: '', currency: 'AED', isPrimary: false });
        setShowBankAccountForm(false);
        showToast('Bank account added successfully', 'success');
      } else {
        throw new Error(response.message || 'Failed to add bank account');
      }
    } catch (error: any) {
      showToast(error.message || 'Failed to add bank account', 'error');
    } finally {
      setSavingBankAccount(false);
    }
  };

  // Handle verifying bank account
  const handleVerifyBankAccount = async (accountId: string) => {
    if (!party) return;
    try {
      const response = await partiesApi.verifyBankAccount(party.id, accountId);
      if (response.success) {
        setBankAccounts(prev => prev.map(acc =>
          acc.id === accountId ? { ...acc, isVerified: true, verifiedAt: new Date().toISOString() } : acc
        ));
        showToast('Bank account verified', 'success');
      }
    } catch (error: any) {
      showToast(error.message || 'Failed to verify bank account', 'error');
    }
  };

  if (!isOpen) return null;

  // Tab configuration - Bank Accounts and Documents available in both modes
  const tabs = [
    { id: 'basic', label: 'Basic Info', icon: Building2 },
    { id: 'bank-accounts', label: 'Bank Accounts', icon: CreditCard, count: bankAccounts.length },
    { id: 'documents', label: 'Documents', icon: Shield, count: documents.length },
    { id: 'compliance', label: 'Compliance', icon: Shield, editOnly: true }, // Only in edit mode
    { id: 'entity', label: 'Entity & POBO', icon: Wallet },
  ];

  // Filter tabs based on mode
  const visibleTabs = tabs.filter(tab => !tab.editOnly || isEditMode);

  return (
    <Modal isOpen={isOpen} onClose={onClose} title={isEditMode ? 'Edit Party' : 'Add New Party'} size="xl">
      <div className="flex flex-col h-[70vh]">
        {/* Tab Navigation */}
        <div className="flex border-b border-neutral-200 px-2 bg-neutral-50 rounded-t-lg dark:border-primary-800 dark:bg-primary-950">
          {visibleTabs.map((tab) => {
            const Icon = tab.icon;
            return (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id)}
                className={cn(
                  'flex items-center gap-2 px-4 py-3 text-sm font-medium border-b-2 transition-colors',
                  activeTab === tab.id
                    ? 'border-primary-600 text-primary-600 bg-white dark:text-primary-200 dark:bg-primary-900'
                    : 'border-transparent text-neutral-500 hover:text-neutral-700 hover:border-neutral-300 dark:text-neutral-400 dark:hover:text-neutral-200 dark:hover:border-primary-700'
                )}
              >
                <Icon className="w-4 h-4" />
                {tab.label}
                {tab.count !== undefined && tab.count > 0 && (
                  <span className="ml-1 px-1.5 py-0.5 text-xs rounded-full bg-neutral-200 text-neutral-600 dark:bg-primary-800 dark:text-neutral-300">
                    {tab.count}
                  </span>
                )}
              </button>
            );
          })}
        </div>

        {/* Tab Content */}
        <div className="flex-1 overflow-y-auto p-4">
          {/* Basic Info Tab */}
          {activeTab === 'basic' && (
            <div className="space-y-6">
              {/* Basic Information */}
              <div>
                <h3 className="text-base font-semibold text-primary-900 mb-3 dark:text-neutral-50">Basic Information</h3>
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <label className="field-label block mb-1">Party Code</label>
                    <input
                      type="text"
                      value={formData.partyCode}
                      onChange={(e) => setFormData(prev => ({ ...prev, partyCode: e.target.value }))}
                      placeholder="Auto-generated if empty"
                      className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent dark:border-primary-700"
                    />
                  </div>
                  <div>
                    <label className="field-label block mb-1">Party Type *</label>
                    <select
                      value={formData.partyType}
                      onChange={(e) => setFormData(prev => ({ ...prev, partyType: e.target.value as PartyType }))}
                      className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent dark:border-primary-700"
                    >
                      {Object.entries(typeConfig).map(([key, config]) => (
                        <option key={key} value={key}>{config.label}</option>
                      ))}
                    </select>
                  </div>
                  <div className="col-span-2">
                    <label className="field-label block mb-1">Legal Name *</label>
                    <input
                      type="text"
                      value={formData.legalName}
                      onChange={(e) => setFormData(prev => ({ ...prev, legalName: e.target.value }))}
                      className={cn(
                        "w-full px-3 py-2 border rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent",
                        errors.legalName ? 'border-error-500' : 'border-neutral-300 dark:border-primary-700'
                      )}
                    />
                    {errors.legalName && <p className="text-xs text-error-600 mt-1 dark:text-error-300">{errors.legalName}</p>}
                  </div>
                  <div>
                    <label className="field-label block mb-1">Display Name</label>
                    <input
                      type="text"
                      value={formData.displayName}
                      onChange={(e) => setFormData(prev => ({ ...prev, displayName: e.target.value }))}
                      className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent dark:border-primary-700"
                    />
                  </div>
                  <div>
                    <label className="field-label block mb-1">Trade Name</label>
                    <input
                      type="text"
                      value={formData.tradeName}
                      onChange={(e) => setFormData(prev => ({ ...prev, tradeName: e.target.value }))}
                      className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent dark:border-primary-700"
                    />
                  </div>
                </div>
              </div>

              {/* Roles */}
              <div>
                <label className="field-label block mb-2">Roles *</label>
                <div className="flex flex-wrap gap-2">
                  {Object.entries(roleConfig).map(([key, config]) => {
                    const role = key as PartyRole;
                    const Icon = config.icon;
                    const isSelected = formData.roles.includes(role);
                    return (
                      <button
                        key={key}
                        type="button"
                        onClick={() => toggleRole(role)}
                        className={cn(
                          'flex items-center gap-2 px-3 py-2 rounded-lg border transition-colors',
                          isSelected
                            ? `${config.bgColor} ${config.color} border-current`
                            : 'bg-white text-neutral-600 border-neutral-300 hover:border-neutral-400 dark:bg-primary-900 dark:text-neutral-300 dark:border-primary-700'
                        )}
                      >
                        <Icon className="w-4 h-4" />
                        {config.label}
                      </button>
                    );
                  })}
                </div>
                {errors.roles && <p className="text-xs text-error-600 mt-1 dark:text-error-300">{errors.roles}</p>}
              </div>

              {/* Registration */}
              <div>
                <h3 className="text-base font-semibold text-primary-900 mb-3 dark:text-neutral-50">Registration</h3>
                <div className="grid grid-cols-3 gap-4">
                  <div>
                    <label className="field-label block mb-1">Tax ID / TRN</label>
                    <input
                      type="text"
                      value={formData.taxId}
                      onChange={(e) => setFormData(prev => ({ ...prev, taxId: e.target.value }))}
                      className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent dark:border-primary-700"
                    />
                  </div>
                  <div>
                    <label className="field-label block mb-1">Registration Number</label>
                    <input
                      type="text"
                      value={formData.registrationNumber}
                      onChange={(e) => setFormData(prev => ({ ...prev, registrationNumber: e.target.value }))}
                      className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent dark:border-primary-700"
                    />
                  </div>
                  <div>
                    <label className="field-label block mb-1">Registration Country *</label>
                    <input
                      type="text"
                      value={formData.registrationCountry}
                      onChange={(e) => setFormData(prev => ({ ...prev, registrationCountry: e.target.value }))}
                      className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent dark:border-primary-700"
                    />
                  </div>
                </div>
              </div>

              {/* Contact */}
              <div>
                <h3 className="text-base font-semibold text-primary-900 mb-3 dark:text-neutral-50">Contact Information</h3>
                <div className="grid grid-cols-3 gap-4">
                  <div>
                    <label className="field-label block mb-1">Contact Name</label>
                    <input
                      type="text"
                      value={formData.contactName}
                      onChange={(e) => setFormData(prev => ({ ...prev, contactName: e.target.value }))}
                      className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent dark:border-primary-700"
                    />
                  </div>
                  <div>
                    <label className="field-label block mb-1">Email</label>
                    <input
                      type="email"
                      value={formData.contactEmail}
                      onChange={(e) => setFormData(prev => ({ ...prev, contactEmail: e.target.value }))}
                      className={cn(
                        "w-full px-3 py-2 border rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent",
                        errors.contactEmail ? 'border-error-500' : 'border-neutral-300 dark:border-primary-700'
                      )}
                    />
                    {errors.contactEmail && <p className="text-xs text-error-600 mt-1 dark:text-error-300">{errors.contactEmail}</p>}
                  </div>
                  <div>
                    <label className="field-label block mb-1">Phone</label>
                    <input
                      type="text"
                      value={formData.contactPhone}
                      onChange={(e) => setFormData(prev => ({ ...prev, contactPhone: e.target.value }))}
                      className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent dark:border-primary-700"
                    />
                  </div>
                </div>
              </div>

              {/* Address */}
              <div>
                <h3 className="text-base font-semibold text-primary-900 mb-3 dark:text-neutral-50">Address</h3>
                <div className="grid grid-cols-2 gap-4">
                  <div className="col-span-2">
                    <label className="field-label block mb-1">Address Line 1</label>
                    <input
                      type="text"
                      value={formData.addressLine1}
                      onChange={(e) => setFormData(prev => ({ ...prev, addressLine1: e.target.value }))}
                      className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent dark:border-primary-700"
                    />
                  </div>
                  <div className="col-span-2">
                    <label className="field-label block mb-1">Address Line 2</label>
                    <input
                      type="text"
                      value={formData.addressLine2}
                      onChange={(e) => setFormData(prev => ({ ...prev, addressLine2: e.target.value }))}
                      className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent dark:border-primary-700"
                    />
                  </div>
                  <div>
                    <label className="field-label block mb-1">City</label>
                    <input
                      type="text"
                      value={formData.city}
                      onChange={(e) => setFormData(prev => ({ ...prev, city: e.target.value }))}
                      className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent dark:border-primary-700"
                    />
                  </div>
                  <div>
                    <label className="field-label block mb-1">State / Province</label>
                    <input
                      type="text"
                      value={formData.state}
                      onChange={(e) => setFormData(prev => ({ ...prev, state: e.target.value }))}
                      className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent dark:border-primary-700"
                    />
                  </div>
                  <div>
                    <label className="field-label block mb-1">Postal Code</label>
                    <input
                      type="text"
                      value={formData.postalCode}
                      onChange={(e) => setFormData(prev => ({ ...prev, postalCode: e.target.value }))}
                      className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent dark:border-primary-700"
                    />
                  </div>
                  <div>
                    <label className="field-label block mb-1">Country</label>
                    <input
                      type="text"
                      value={formData.country}
                      onChange={(e) => setFormData(prev => ({ ...prev, country: e.target.value }))}
                      placeholder="AE"
                      className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent dark:border-primary-700"
                    />
                  </div>
                </div>
              </div>

              {/* E-commerce - Show for VENDOR role */}
              {formData.roles.includes('VENDOR') && (
                <div>
                  <h3 className="text-base font-semibold text-primary-900 mb-3 dark:text-neutral-50">E-commerce</h3>
                  <div className="grid grid-cols-2 gap-4">
                    <div className="flex items-center gap-3">
                      <label className="flex items-center gap-2">
                        <input
                          type="checkbox"
                          checked={formData.ecommerceEnabled}
                          onChange={(e) => setFormData(prev => ({ ...prev, ecommerceEnabled: e.target.checked }))}
                          className="w-4 h-4 rounded border-neutral-300 text-primary-600 focus:ring-primary-500 dark:border-primary-700 dark:text-primary-200"
                        />
                        <span className="text-sm text-neutral-700 dark:text-neutral-200">E-commerce Enabled</span>
                      </label>
                    </div>
                    {formData.ecommerceEnabled && (
                      <div>
                        <label className="field-label block mb-1">Platforms</label>
                        <input
                          type="text"
                          value={formData.ecommercePlatforms}
                          onChange={(e) => setFormData(prev => ({ ...prev, ecommercePlatforms: e.target.value }))}
                          placeholder="AMAZON, NOON, SHOPIFY"
                          className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent dark:border-primary-700"
                        />
                        <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">Comma-separated list</p>
                      </div>
                    )}
                  </div>
                </div>
              )}

              {/* Employee Details - Show for EMPLOYEE role */}
              {formData.roles.includes('EMPLOYEE') && (
                <div>
                  <h3 className="text-base font-semibold text-primary-900 mb-3 dark:text-neutral-50">Employee Details</h3>
                  <div className="grid grid-cols-2 gap-4">
                    <div>
                      <label className="field-label block mb-1">Employee ID</label>
                      <input
                        type="text"
                        value={formData.employeeId}
                        onChange={(e) => setFormData(prev => ({ ...prev, employeeId: e.target.value }))}
                        className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent dark:border-primary-700"
                      />
                    </div>
                    <div>
                      <label className="field-label block mb-1">Department</label>
                      <input
                        type="text"
                        value={formData.department}
                        onChange={(e) => setFormData(prev => ({ ...prev, department: e.target.value }))}
                        className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent dark:border-primary-700"
                      />
                    </div>
                  </div>
                </div>
              )}
            </div>
          )}

          {/* Bank Accounts Tab - Available in both create and edit modes */}
          {activeTab === 'bank-accounts' && (
            <div className="space-y-4">
              <div className="flex justify-between items-center">
                <div>
                  <h3 className="text-base font-semibold text-primary-900 dark:text-neutral-50">Bank Accounts</h3>
                  {!isEditMode && bankAccounts.length > 0 && (
                    <p className="text-xs text-neutral-500 mt-0.5 dark:text-neutral-400">
                      {bankAccounts.length} account(s) will be added after creating the party
                    </p>
                  )}
                </div>
                <Button
                  size="sm"
                  onClick={() => setShowBankAccountForm(!showBankAccountForm)}
                  leftIcon={<Plus className="w-4 h-4" />}
                >
                  Add Account
                </Button>
              </div>

              {/* Bank Account Form */}
              {showBankAccountForm && (
                <div className="p-4 bg-neutral-50 rounded-lg border border-neutral-200 space-y-4 dark:bg-primary-950 dark:border-primary-800">
                  <h4 className="text-sm font-semibold text-neutral-800 dark:text-neutral-100">New Bank Account</h4>
                  <div className="grid grid-cols-2 gap-4">
                    <div>
                      <label className="block text-xs font-medium text-neutral-700 mb-1 dark:text-neutral-200">Label *</label>
                      <input
                        type="text"
                        value={bankAccountForm.label}
                        onChange={(e) => setBankAccountForm(prev => ({ ...prev, label: e.target.value }))}
                        placeholder="e.g., Primary Account"
                        className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 dark:border-primary-700"
                      />
                    </div>
                    <div>
                      <label className="block text-xs font-medium text-neutral-700 mb-1 dark:text-neutral-200">Account Holder *</label>
                      <input
                        type="text"
                        value={bankAccountForm.holderName}
                        onChange={(e) => setBankAccountForm(prev => ({ ...prev, holderName: e.target.value }))}
                        placeholder="Account holder name"
                        className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 dark:border-primary-700"
                      />
                    </div>
                    <div>
                      <label className="block text-xs font-medium text-neutral-700 mb-1 dark:text-neutral-200">Bank Name *</label>
                      <input
                        type="text"
                        value={bankAccountForm.bankName}
                        onChange={(e) => setBankAccountForm(prev => ({ ...prev, bankName: e.target.value }))}
                        placeholder="e.g., Emirates NBD"
                        className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 dark:border-primary-700"
                      />
                    </div>
                    <div>
                      <label className="block text-xs font-medium text-neutral-700 mb-1 dark:text-neutral-200">Bank Code / SWIFT</label>
                      <input
                        type="text"
                        value={bankAccountForm.bankCode}
                        onChange={(e) => setBankAccountForm(prev => ({ ...prev, bankCode: e.target.value }))}
                        placeholder="e.g., EABOROMCXXX"
                        className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 dark:border-primary-700"
                      />
                    </div>
                    <div>
                      <label className="block text-xs font-medium text-neutral-700 mb-1 dark:text-neutral-200">IBAN</label>
                      <input
                        type="text"
                        value={bankAccountForm.iban}
                        onChange={(e) => setBankAccountForm(prev => ({ ...prev, iban: e.target.value }))}
                        placeholder="e.g., AE07 0331 2345 6789 0123 456"
                        className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 dark:border-primary-700"
                      />
                    </div>
                    <div>
                      <label className="block text-xs font-medium text-neutral-700 mb-1 dark:text-neutral-200">Account Number</label>
                      <input
                        type="text"
                        value={bankAccountForm.accountNumber}
                        onChange={(e) => setBankAccountForm(prev => ({ ...prev, accountNumber: e.target.value }))}
                        placeholder="Account number"
                        className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 dark:border-primary-700"
                      />
                    </div>
                    <div>
                      <label className="block text-xs font-medium text-neutral-700 mb-1 dark:text-neutral-200">Routing Number</label>
                      <input
                        type="text"
                        value={bankAccountForm.routingNumber}
                        onChange={(e) => setBankAccountForm(prev => ({ ...prev, routingNumber: e.target.value }))}
                        placeholder="For US banks"
                        className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 dark:border-primary-700"
                      />
                    </div>
                    <div>
                      <label className="block text-xs font-medium text-neutral-700 mb-1 dark:text-neutral-200">Currency *</label>
                      <CurrencyPicker
                        value={bankAccountForm.currency}
                        onChange={(c) => setBankAccountForm(prev => ({ ...prev, currency: c }))}
                        withName
                        className="text-sm"
                        extra={['SAR', 'INR', 'SGD']}
                      />
                    </div>
                    <div className="flex items-end">
                      <label className="flex items-center gap-2 cursor-pointer">
                        <input
                          type="checkbox"
                          checked={bankAccountForm.isPrimary}
                          onChange={(e) => setBankAccountForm(prev => ({ ...prev, isPrimary: e.target.checked }))}
                          className="w-4 h-4 rounded border-neutral-300 text-primary-600 dark:border-primary-700 dark:text-primary-200"
                        />
                        <span className="field-label">Set as Primary Account</span>
                      </label>
                    </div>
                  </div>
                  <div className="flex justify-end gap-2 pt-3 border-t border-neutral-200 dark:border-primary-800">
                    <Button variant="outline" size="sm" onClick={() => setShowBankAccountForm(false)}>Cancel</Button>
                    <Button
                      size="sm"
                      onClick={isEditMode ? handleAddBankAccount : () => {
                        // In create mode, add to local state with temp ID
                        if (bankAccountForm.bankName) {
                          const tempAccount: PartyBankAccount = {
                            id: `temp-${Date.now()}`,
                            ...bankAccountForm,
                            isVerified: false,
                            status: 'PENDING',
                          };
                          setBankAccounts(prev => [...prev, tempAccount]);
                          setBankAccountForm({ label: '', holderName: '', bankName: '', bankCode: '', iban: '', accountNumber: '', routingNumber: '', currency: 'AED', isPrimary: false });
                          setShowBankAccountForm(false);
                          showToast('Bank account added (will be saved with party)', 'info');
                        }
                      }}
                      disabled={savingBankAccount || !bankAccountForm.bankName}
                      leftIcon={savingBankAccount ? <Loader2 className="w-4 h-4 animate-spin" /> : <Plus className="w-4 h-4" />}
                    >
                      {savingBankAccount ? 'Saving...' : (isEditMode ? 'Save Account' : 'Add Account')}
                    </Button>
                  </div>
                </div>
              )}

              {/* Bank Accounts List */}
              {bankAccounts.length === 0 ? (
                <div className="text-center py-12 text-neutral-500 bg-neutral-50 rounded-lg border border-dashed border-neutral-300 dark:text-neutral-400 dark:bg-primary-950 dark:border-primary-700">
                  <CreditCard className="w-12 h-12 mx-auto mb-3 text-neutral-300 dark:text-neutral-600" />
                  <p className="font-medium text-neutral-600 dark:text-neutral-300">No bank accounts added yet</p>
                  <p className="text-sm mt-1">Add a bank account to enable payments to this party</p>
                </div>
              ) : (
                <div className="space-y-3">
                  {bankAccounts.map((account, index) => (
                    <div key={account.id || index} className="p-4 bg-white border border-neutral-200 rounded-lg hover:border-primary-200 transition-colors dark:bg-primary-900 dark:border-primary-800">
                      <div className="flex items-start justify-between">
                        <div className="flex items-start gap-3">
                          <div className="p-2 bg-primary-50 rounded-lg dark:bg-primary-800/40">
                            <Banknote className="w-5 h-5 text-primary-600 dark:text-primary-200" />
                          </div>
                          <div>
                            <div className="flex items-center gap-2 flex-wrap">
                              <span className="font-semibold text-neutral-900 dark:text-neutral-50">{account.label || account.bankName}</span>
                              {account.isPrimary && (
                                <Badge variant="primary" size="sm">Primary</Badge>
                              )}
                              {!isEditMode && account.id?.startsWith('temp-') ? (
                                <Badge variant="info" size="sm">Pending Save</Badge>
                              ) : account.isVerified ? (
                                <Badge variant="success" size="sm">
                                  <CheckCircle className="w-3 h-3 mr-1" />
                                  Verified
                                </Badge>
                              ) : (
                                <Badge variant="warning" size="sm">Unverified</Badge>
                              )}
                            </div>
                            <p className="text-sm text-neutral-600 mt-1 dark:text-neutral-300">{account.bankName}</p>
                            <div className="flex flex-wrap gap-3 mt-2 text-xs text-neutral-500 dark:text-neutral-400">
                              {account.iban && <span className="font-mono">IBAN: {account.iban}</span>}
                              {account.accountNumber && <span className="font-mono">Acc: {account.accountNumber}</span>}
                              <span className="font-semibold">{account.currency}</span>
                            </div>
                          </div>
                        </div>
                        <div className="flex gap-2">
                          {!isEditMode && account.id?.startsWith('temp-') && (
                            <Button
                              size="sm"
                              variant="ghost"
                              onClick={() => setBankAccounts(prev => prev.filter(a => a.id !== account.id))}
                              className="text-error-600 hover:bg-error-50 dark:text-error-300 dark:hover:bg-error-500/10"
                            >
                              <X className="w-4 h-4" />
                            </Button>
                          )}
                          {isEditMode && !account.isVerified && (
                            <Button
                              size="sm"
                              variant="outline"
                              onClick={() => handleVerifyBankAccount(account.id)}
                            >
                              Verify
                            </Button>
                          )}
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          {/* Documents Tab - Available in both create and edit modes */}
          {activeTab === 'documents' && (
            <div className="space-y-4">
              <div className="flex justify-between items-center">
                <div>
                  <h3 className="text-base font-semibold text-primary-900 dark:text-neutral-50">Documents</h3>
                  <p className="text-xs text-neutral-500 mt-0.5 dark:text-neutral-400">
                    {isEditMode ? 'Upload compliance and verification documents' : 'Documents can be uploaded after creating the party'}
                  </p>
                </div>
                {isEditMode && (
                  <Button size="sm" leftIcon={<Plus className="w-4 h-4" />}>Upload Document</Button>
                )}
              </div>

              {documents.length === 0 ? (
                <div className="text-center py-12 text-neutral-500 bg-neutral-50 rounded-lg border border-dashed border-neutral-300 dark:text-neutral-400 dark:bg-primary-950 dark:border-primary-700">
                  <Shield className="w-12 h-12 mx-auto mb-3 text-neutral-300 dark:text-neutral-600" />
                  <p className="font-medium text-neutral-600 dark:text-neutral-300">No documents uploaded yet</p>
                  <p className="text-sm mt-1">
                    {isEditMode
                      ? 'Upload compliance documents for KYC verification'
                      : 'You can upload documents after the party is created'}
                  </p>
                </div>
              ) : (
                <div className="space-y-3">
                  {documents.map((doc) => (
                    <div key={doc.id} className="p-4 bg-white border border-neutral-200 rounded-lg hover:border-primary-200 transition-colors dark:bg-primary-900 dark:border-primary-800">
                      <div className="flex items-start justify-between">
                        <div>
                          <div className="flex items-center gap-2">
                            <span className="font-medium text-neutral-900 dark:text-neutral-50">{doc.name || doc.documentType}</span>
                            <Badge
                              variant={doc.verificationStatus === 'VERIFIED' ? 'success' : doc.verificationStatus === 'REJECTED' ? 'error' : 'warning'}
                              size="sm"
                            >
                              {doc.verificationStatus || 'Pending'}
                            </Badge>
                          </div>
                          <p className="text-sm text-neutral-600 mt-1 dark:text-neutral-300">{doc.documentType}</p>
                          {doc.expiryDate && (
                            <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">
                              Expires: {new Date(doc.expiryDate).toLocaleDateString()}
                            </p>
                          )}
                        </div>
                        <Button size="sm" variant="ghost">View</Button>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          {/* Compliance Tab (Edit Mode Only) */}
          {activeTab === 'compliance' && isEditMode && party && (
            <div className="space-y-6">
              <div>
                <h3 className="text-base font-semibold text-primary-900 mb-4 dark:text-neutral-50">KYC Status</h3>
                <div className="grid grid-cols-2 gap-4">
                  <div className="p-4 bg-neutral-50 rounded-lg dark:bg-primary-950">
                    <label className="block text-xs font-medium text-neutral-600 mb-1 dark:text-neutral-300">Current Status</label>
                    <div className="flex items-center gap-2">
                      {party.kycStatus && kycStatusConfig[party.kycStatus] && (
                        <>
                          {React.createElement(kycStatusConfig[party.kycStatus].icon, { className: 'w-4 h-4' })}
                          <Badge variant={kycStatusConfig[party.kycStatus].variant as any}>
                            {kycStatusConfig[party.kycStatus].label}
                          </Badge>
                        </>
                      )}
                    </div>
                    {party.kycExpiresAt && (
                      <p className="text-xs text-neutral-500 mt-2 dark:text-neutral-400">
                        Expires: {new Date(party.kycExpiresAt).toLocaleDateString()}
                      </p>
                    )}
                  </div>
                  <div className="p-4 bg-neutral-50 rounded-lg dark:bg-primary-950">
                    <label className="block text-xs font-medium text-neutral-600 mb-1 dark:text-neutral-300">Risk Rating</label>
                    <div className="flex items-center gap-2">
                      {party.riskRating && riskConfig[party.riskRating] && (
                        <Badge variant={riskConfig[party.riskRating].variant as any}>
                          {riskConfig[party.riskRating].label}
                        </Badge>
                      )}
                      {party.riskScore && (
                        <span className="text-sm text-neutral-600 dark:text-neutral-300">Score: {party.riskScore}</span>
                      )}
                    </div>
                  </div>
                </div>
              </div>

              <div>
                <h3 className="text-base font-semibold text-primary-900 mb-4 dark:text-neutral-50">Screening Status</h3>
                <div className="grid grid-cols-3 gap-4">
                  <div className="p-4 bg-neutral-50 rounded-lg dark:bg-primary-950">
                    <label className="block text-xs font-medium text-neutral-600 mb-1 dark:text-neutral-300">Sanctions</label>
                    <Badge variant={party.sanctionsStatus === 'CLEAR' ? 'success' : 'error'}>
                      {party.sanctionsStatus || 'Not Checked'}
                    </Badge>
                  </div>
                  <div className="p-4 bg-neutral-50 rounded-lg dark:bg-primary-950">
                    <label className="block text-xs font-medium text-neutral-600 mb-1 dark:text-neutral-300">PEP Status</label>
                    <Badge variant={party.pepStatus ? 'warning' : 'success'}>
                      {party.pepStatus ? 'PEP Identified' : 'Clear'}
                    </Badge>
                  </div>
                  <div className="p-4 bg-neutral-50 rounded-lg dark:bg-primary-950">
                    <label className="block text-xs font-medium text-neutral-600 mb-1 dark:text-neutral-300">Adverse Media</label>
                    <Badge variant={party.adverseMediaStatus ? 'warning' : 'success'}>
                      {party.adverseMediaStatus ? 'Found' : 'Clear'}
                    </Badge>
                  </div>
                </div>
              </div>

              <div className="pt-4 border-t border-neutral-200 dark:border-primary-800">
                <p className="text-xs text-neutral-500 dark:text-neutral-400">
                  KYC and compliance settings can be managed from the View Details modal after saving the party.
                </p>
              </div>
            </div>
          )}

          {/* Entity & POBO Tab */}
          {activeTab === 'entity' && (
            <div className="space-y-6">
              <div>
                <h3 className="text-base font-semibold text-primary-900 mb-3 dark:text-neutral-50">Entity Assignment</h3>
                <div className="grid grid-cols-2 gap-4">
                  <div className="col-span-2">
                    <label className="field-label block mb-1">Owning Entity</label>
                    <select
                      value={formData.owningEntityId}
                      onChange={(e) => setFormData(prev => ({ ...prev, owningEntityId: e.target.value }))}
                      className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent dark:border-primary-700"
                    >
                      <option value="">{entities.length === 0 ? 'No entities available' : 'Select Entity (optional)'}</option>
                      {entities.map(entity => (
                        <option key={entity.id} value={entity.id}>{entity.entityCode} - {entity.entityName}</option>
                      ))}
                    </select>
                    <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">
                      The legal entity that owns this party relationship
                    </p>
                  </div>
                </div>
              </div>

              <div>
                <h3 className="text-base font-semibold text-primary-900 mb-3 dark:text-neutral-50">Payment Factory Settings</h3>
                <div className="space-y-4">
                  <div className="flex items-start gap-4 p-4 bg-info-50 rounded-lg border border-info-200 dark:bg-info-500/10 dark:border-info-500/30">
                    <div className="p-2 bg-info-100 rounded-lg dark:bg-info-500/20">
                      <Wallet className="w-5 h-5 text-info-600 dark:text-info-300" />
                    </div>
                    <div className="flex-1">
                      <div className="flex items-center justify-between">
                        <div>
                          <h4 className="font-medium text-info-900">POBO Eligible</h4>
                          <p className="text-sm text-info-700 dark:text-info-300">Allow treasury to pay this vendor on behalf of subsidiaries</p>
                        </div>
                        <label className="relative inline-flex items-center cursor-pointer">
                          <input
                            type="checkbox"
                            checked={formData.poboEligible}
                            onChange={(e) => setFormData(prev => ({ ...prev, poboEligible: e.target.checked }))}
                            className="sr-only peer"
                          />
                          <div className="w-11 h-6 bg-neutral-200 peer-focus:outline-none peer-focus:ring-4 peer-focus:ring-info-300 rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-neutral-300 after:border after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-info-600 dark:bg-primary-800"></div>
                        </label>
                      </div>
                    </div>
                  </div>

                  <div className="flex items-start gap-4 p-4 bg-cat-2-soft rounded-lg border border-cat-2/20 dark:bg-cat-2/15 dark:border-cat-2/30">
                    <div className="p-2 bg-cat-2/10 rounded-lg dark:bg-cat-2/15">
                      <Link2 className="w-5 h-5 text-cat-2" />
                    </div>
                    <div className="flex-1">
                      <div className="flex items-center justify-between">
                        <div>
                          <h4 className="font-medium text-cat-2">Intercompany Party</h4>
                          <p className="text-sm text-cat-2">This party represents another legal entity within the group</p>
                        </div>
                        <label className="relative inline-flex items-center cursor-pointer">
                          <input
                            type="checkbox"
                            checked={formData.isIntercompany}
                            onChange={(e) => setFormData(prev => ({ ...prev, isIntercompany: e.target.checked }))}
                            className="sr-only peer"
                          />
                          <div className="w-11 h-6 bg-neutral-200 peer-focus:outline-none peer-focus:ring-4 peer-focus:ring-cat-2/30 rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-neutral-300 after:border after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-cat-2 dark:bg-primary-800"></div>
                        </label>
                      </div>
                    </div>
                  </div>

                  <div className="flex items-start gap-4 p-4 bg-cat-3-soft rounded-lg border border-cat-3/20 dark:bg-cat-3/15 dark:border-cat-3/30">
                    <div className="p-2 bg-cat-3/10 rounded-lg dark:bg-cat-3/15">
                      <Repeat className="w-5 h-5 text-cat-3" />
                    </div>
                    <div className="flex-1">
                      <div className="flex items-center justify-between">
                        <div>
                          <h4 className="font-medium text-cat-3">Netting Eligible</h4>
                          <p className="text-sm text-cat-3">Include transactions in netting cycles for settlement optimization</p>
                        </div>
                        <label className="relative inline-flex items-center cursor-pointer">
                          <input
                            type="checkbox"
                            checked={formData.nettingEligible}
                            onChange={(e) => setFormData(prev => ({ ...prev, nettingEligible: e.target.checked }))}
                            className="sr-only peer"
                          />
                          <div className="w-11 h-6 bg-neutral-200 peer-focus:outline-none peer-focus:ring-4 peer-focus:ring-cat-3/30 rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-neutral-300 after:border after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-cat-3 dark:bg-primary-800"></div>
                        </label>
                      </div>
                    </div>
                  </div>
                </div>
              </div>

              {isEditMode && (
                <div className="pt-4 border-t border-neutral-200 dark:border-primary-800">
                  <p className="text-xs text-neutral-500 dark:text-neutral-400">
                    Additional POBO and Intercompany configuration (linked entities, credit limits) can be managed from the View Details modal.
                  </p>
                </div>
              )}
            </div>
          )}
        </div>

        {/* Actions Footer */}
        <div className="flex justify-end gap-3 p-4 border-t border-neutral-200 bg-neutral-50 dark:border-primary-800 dark:bg-primary-950">
          <Button variant="outline" onClick={onClose} disabled={saving}>Cancel</Button>
          <Button
            onClick={handleSubmit}
            disabled={saving}
            leftIcon={saving ? <Loader2 className="w-4 h-4 animate-spin" /> : <Save className="w-4 h-4" />}
          >
            {saving ? 'Saving...' : (isEditMode ? 'Update Party' : 'Create Party')}
          </Button>
        </div>
      </div>
    </Modal>
  );
};

// ============================================================================
// PARTY DETAIL MODAL - Enhanced with Bank Account Form, Documents, Compliance
// ============================================================================

interface BankAccountFormData {
  label: string;
  holderName: string;
  bankName: string;
  bankCode: string;
  iban: string;
  accountNumber: string;
  routingNumber: string;
  currency: string;
  isPrimary: boolean;
}

const defaultBankAccountForm: BankAccountFormData = {
  label: '',
  holderName: '',
  bankName: '',
  bankCode: '',
  iban: '',
  accountNumber: '',
  routingNumber: '',
  currency: 'AED',
  isPrimary: false,
};

interface IcCreditLimitFormData {
  creditLimit: string;
  currency: string;
  approvalNotes: string;
}

const PartyDetailModal: React.FC<{
  party: Party | null;
  onClose: () => void;
  entities: LegalEntity[];
  onEdit: (party: Party) => void;
  onPartyUpdated?: () => void;
}> = ({ party, onClose, entities: _entities, onEdit, onPartyUpdated }) => {
  const [activeTab, setActiveTab] = useState<string>('overview');
  const [bankAccounts, setBankAccounts] = useState<PartyBankAccount[]>([]);
  const [documents, setDocuments] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);

  // Bank Account Form State
  const [showBankAccountForm, setShowBankAccountForm] = useState(false);
  const [bankAccountForm, setBankAccountForm] = useState<BankAccountFormData>(defaultBankAccountForm);
  const [savingBankAccount, setSavingBankAccount] = useState(false);

  // IC Credit Limit Form State
  const [showIcCreditForm, setShowIcCreditForm] = useState(false);
  const [icCreditForm, setIcCreditForm] = useState<IcCreditLimitFormData>({ creditLimit: '', currency: 'AED', approvalNotes: '' });
  const [savingIcCredit, setSavingIcCredit] = useState(false);

  // KYC/Compliance Action State
  const [showKycForm, setShowKycForm] = useState(false);
  const [kycFormData, setKycFormData] = useState({ kycStatus: '', kycExpiresAt: '' });
  const [showRiskForm, setShowRiskForm] = useState(false);
  const [riskFormData, setRiskFormData] = useState({ riskRating: '', riskScore: '' });
  const [savingCompliance, setSavingCompliance] = useState(false);
  const [runningScreening, setRunningScreening] = useState(false);

  useEffect(() => {
    if (party) {
      setLoading(true);
      setActiveTab('overview');

      // Load bank accounts and documents in parallel
      Promise.all([
        partiesApi.getBankAccounts(party.id).catch(() => ({ success: false, data: [] })),
        partiesApi.getDocuments(party.id).catch(() => ({ success: false, data: [] }))
      ]).then(([bankRes, docRes]) => {
        if (bankRes.success && bankRes.data) {
          setBankAccounts(Array.isArray(bankRes.data) ? bankRes.data : []);
        }
        if (docRes.success && docRes.data) {
          setDocuments(Array.isArray(docRes.data) ? docRes.data : []);
        }
      }).finally(() => setLoading(false));

      // Initialize form data
      setKycFormData({ kycStatus: party.kycStatus, kycExpiresAt: party.kycExpiresAt || '' });
      setRiskFormData({ riskRating: party.riskRating, riskScore: party.riskScore?.toString() || '' });
      if (party.icCreditLimit) {
        setIcCreditForm({
          creditLimit: party.icCreditLimit.toString(),
          currency: party.icCurrency || 'AED',
          approvalNotes: ''
        });
      }
    }
  }, [party]);

  if (!party) return null;

  const TypeIcon = typeConfig[party.partyType]?.icon || Building2;
  const kycConf = kycStatusConfig[party.kycStatus] || kycStatusConfig.PENDING;
  const KycIcon = kycConf.icon;
  const rolesArray = Array.isArray(party.roles) ? party.roles : [];

  // Build tabs dynamically
  const tabs = [
    { id: 'overview', label: 'Overview' },
    { id: 'accounts', label: 'Bank Accounts', count: bankAccounts.length || party.bankAccountsCount },
    { id: 'documents', label: 'Documents', count: documents.length || party.documentsCount },
    { id: 'compliance', label: 'Compliance' },
    ...(party.poboEligible ? [{ id: 'pobo', label: 'POBO' }] : []),
    ...(party.isIntercompany ? [{ id: 'intercompany', label: 'Intercompany' }] : []),
  ];

  const handleEditClick = () => {
    onEdit(party);
    onClose();
  };

  // Bank Account Handlers
  const handleAddBankAccount = async () => {
    if (!bankAccountForm.label || !bankAccountForm.bankName || (!bankAccountForm.iban && !bankAccountForm.accountNumber)) {
      showToast('Please fill in required fields', 'error');
      return;
    }

    setSavingBankAccount(true);
    try {
      const res = await partiesApi.addBankAccount(party.id, {
        ...bankAccountForm,
        holderName: bankAccountForm.holderName || party.legalName,
      });
      if (res.success && res.data) {
        setBankAccounts(prev => [...prev, res.data as PartyBankAccount]);
        setShowBankAccountForm(false);
        setBankAccountForm(defaultBankAccountForm);
        showToast('Bank account added successfully', 'success');
      }
    } catch (err) {
      console.error('Failed to add bank account:', err);
      showToast('Failed to add bank account', 'error');
    } finally {
      setSavingBankAccount(false);
    }
  };

  const handleVerifyBankAccount = async (accountId: string) => {
    try {
      const res = await partiesApi.verifyBankAccount(accountId, 'Portal User');
      if (res.success && res.data) {
        setBankAccounts(prev => prev.map(acc => acc.id === accountId ? { ...acc, isVerified: true } : acc));
        showToast('Bank account verified', 'success');
      }
    } catch (err) {
      console.error('Failed to verify bank account:', err);
      showToast('Failed to verify bank account', 'error');
    }
  };

  // IC Credit Limit Handler
  const handleUpdateIcCreditLimit = async () => {
    if (!icCreditForm.creditLimit || parseFloat(icCreditForm.creditLimit) <= 0) {
      showToast('Please enter a valid credit limit', 'error');
      return;
    }

    setSavingIcCredit(true);
    try {
      const res = await partiesApi.updateIcCreditLimit(party.id, {
        creditLimit: parseFloat(icCreditForm.creditLimit),
        currency: icCreditForm.currency,
        approvalNotes: icCreditForm.approvalNotes,
      });
      if (res.success) {
        setShowIcCreditForm(false);
        showToast('Credit limit updated successfully', 'success');
        onPartyUpdated?.();
      }
    } catch (err) {
      console.error('Failed to update IC credit limit:', err);
      showToast('Failed to update credit limit', 'error');
    } finally {
      setSavingIcCredit(false);
    }
  };

  // KYC/Compliance Handlers
  const handleUpdateKyc = async () => {
    setSavingCompliance(true);
    try {
      const res = await partiesApi.updateKycStatus(party.id, kycFormData.kycStatus as KycStatus, kycFormData.kycExpiresAt || undefined);
      if (res.success) {
        setShowKycForm(false);
        showToast('KYC status updated', 'success');
        onPartyUpdated?.();
      }
    } catch (err) {
      console.error('Failed to update KYC:', err);
      showToast('Failed to update KYC status', 'error');
    } finally {
      setSavingCompliance(false);
    }
  };

  const handleUpdateRisk = async () => {
    setSavingCompliance(true);
    try {
      const res = await partiesApi.updateRiskRating(party.id, riskFormData.riskRating as RiskRating, riskFormData.riskScore ? parseInt(riskFormData.riskScore) : undefined);
      if (res.success) {
        setShowRiskForm(false);
        showToast('Risk rating updated', 'success');
        onPartyUpdated?.();
      }
    } catch (err) {
      console.error('Failed to update risk rating:', err);
      showToast('Failed to update risk rating', 'error');
    } finally {
      setSavingCompliance(false);
    }
  };

  const handleRunScreening = async () => {
    setRunningScreening(true);
    try {
      const res = await partiesApi.runScreening(party.id, { screeningTypes: ['SANCTIONS', 'PEP', 'ADVERSE_MEDIA'] });
      if (res.success) {
        showToast('Screening completed', 'success');
        onPartyUpdated?.();
      }
    } catch (err) {
      console.error('Screening failed:', err);
      showToast('Screening failed', 'error');
    } finally {
      setRunningScreening(false);
    }
  };

  return (
    <Modal isOpen={!!party} onClose={onClose} title="" size="xl">
      <div className="space-y-6">
        {/* Header */}
        <div className="flex items-start justify-between pb-4 border-b border-neutral-200 dark:border-primary-800">
          <div className="flex items-center gap-4">
            <div className={cn('w-14 h-14 rounded-xl flex items-center justify-center',
              party.isIntercompany ? 'bg-cat-2/10 dark:bg-cat-2/15' : party.status === 'ACTIVE' ? 'bg-primary-100 dark:bg-primary-700' : 'bg-error-100 dark:bg-error-500/20')}>
              <TypeIcon className={cn('w-7 h-7', party.isIntercompany ? 'text-cat-2' : party.status === 'ACTIVE' ? 'text-primary-700 dark:text-neutral-200' : 'text-error-600 dark:text-error-300')} />
            </div>
            <div>
              <div className="flex items-center gap-2 flex-wrap">
                <h2 className="section-title">{party.legalName}</h2>
                {party.status !== 'ACTIVE' && <Badge variant="error" size="sm">{party.status}</Badge>}
              </div>
              <p className="text-sm text-neutral-500 dark:text-neutral-400">{party.partyCode} • {typeConfig[party.partyType]?.label}</p>
              <div className="flex items-center gap-2 mt-2 flex-wrap">
                {rolesArray.map((role) => {
                  const config = roleConfig[role];
                  return config ? (
                    <span key={role} className={cn('px-2 py-0.5 rounded text-xs font-medium', config.bgColor, config.color)}>{config.label}</span>
                  ) : null;
                })}
              </div>
              <div className="flex items-center gap-2 mt-2 flex-wrap">
                <OwningEntityBadge entityCode={party.owningEntityCode} />
                <PoboBadge eligible={party.poboEligible} defaultPayerCode={party.poboDefaultPayerEntityCode} />
                <IntercompanyBadge isIntercompany={party.isIntercompany} linkedEntityCode={party.linkedLegalEntityCode} settlementMethod={party.icSettlementMethod} />
                <NettingBadge eligible={party.nettingEligible || party.isIntercompany} />
                {party.isIntercompany && <IcCreditBadge limit={party.icCreditLimit} exposure={party.icCurrentExposure} />}
              </div>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <Button variant="outline" size="sm" leftIcon={<Edit className="w-4 h-4" />} onClick={handleEditClick}>Edit</Button>
            <Button variant="ghost" size="sm" onClick={onClose}><X className="w-4 h-4" /></Button>
          </div>
        </div>

        {/* Tabs */}
        <div className="flex gap-1 p-1 bg-neutral-100 rounded-lg overflow-x-auto dark:bg-primary-800">
          {tabs.map((tab) => (
            <button key={tab.id} onClick={() => setActiveTab(tab.id)}
              className={cn('flex items-center gap-2 px-4 py-2 text-sm font-medium rounded-md transition-colors whitespace-nowrap',
                activeTab === tab.id ? 'bg-white text-primary-900 shadow-sm dark:bg-primary-900 dark:text-neutral-50' : 'text-neutral-600 hover:text-primary-900 dark:text-neutral-300 dark:hover:text-neutral-50')}>
              {tab.label}
              {tab.count !== undefined && (
                <span className={cn('px-1.5 py-0.5 rounded text-xs', activeTab === tab.id ? 'bg-primary-100 dark:bg-primary-700' : 'bg-neutral-200 dark:bg-primary-800')}>{tab.count}</span>
              )}
            </button>
          ))}
        </div>

        {/* Tab Content */}
        <div className="min-h-[350px]">
          {loading && <div className="flex items-center justify-center h-40"><Loader2 className="w-6 h-6 animate-spin text-primary-600 dark:text-primary-200" /></div>}

          {/* Overview Tab */}
          {!loading && activeTab === 'overview' && (
            <div className="grid grid-cols-2 gap-6">
              <div className="space-y-4">
                <h3 className="text-sm font-semibold text-primary-900 dark:text-neutral-50">Basic Information</h3>
                <Card padding="sm" className="space-y-3">
                  {party.displayName && <div><p className="text-xs text-neutral-500 dark:text-neutral-400">Display Name</p><p className="text-sm text-primary-900 dark:text-neutral-50">{party.displayName}</p></div>}
                  {party.taxId && <div><p className="text-xs text-neutral-500 dark:text-neutral-400">Tax ID / TRN</p><p className="text-sm text-primary-900 font-mono dark:text-neutral-50">{party.taxId}</p></div>}
                  {party.owningEntityCode && <div><p className="text-xs text-neutral-500 dark:text-neutral-400">Owning Entity</p><p className="text-sm text-primary-900 dark:text-neutral-50">{party.owningEntityCode}</p></div>}
                </Card>
                <h3 className="text-sm font-semibold text-primary-900 dark:text-neutral-50">Contact</h3>
                <Card padding="sm" className="space-y-3">
                  {party.contactName && <div className="flex items-center gap-2"><User className="w-4 h-4 text-neutral-400 dark:text-neutral-500" /><p className="text-sm text-primary-900 dark:text-neutral-50">{party.contactName}</p></div>}
                  {party.contactEmail && <div className="flex items-center gap-2"><Mail className="w-4 h-4 text-neutral-400 dark:text-neutral-500" /><a href={`mailto:${party.contactEmail}`} className="text-sm text-info-600 hover:underline dark:text-info-300">{party.contactEmail}</a></div>}
                  {party.city && <div className="flex items-center gap-2"><MapPin className="w-4 h-4 text-neutral-400 dark:text-neutral-500" /><p className="text-sm text-primary-900 dark:text-neutral-50">{party.city}, {party.country}</p></div>}
                </Card>
              </div>
              <div className="space-y-4">
                <h3 className="text-sm font-semibold text-primary-900 dark:text-neutral-50">Compliance Status</h3>
                <Card padding="sm" className="space-y-3">
                  <div className="flex items-center justify-between"><span className="text-sm text-neutral-500 dark:text-neutral-400">KYC Status</span><Badge variant={kycConf.variant as any} size="sm"><KycIcon className="w-3 h-3 mr-1" />{kycConf.label}</Badge></div>
                  <div className="flex items-center justify-between"><span className="text-sm text-neutral-500 dark:text-neutral-400">Risk Rating</span><Badge variant={riskConfig[party.riskRating]?.variant as any} size="sm">{riskConfig[party.riskRating]?.label}</Badge></div>
                  <div className="flex items-center justify-between"><span className="text-sm text-neutral-500 dark:text-neutral-400">Sanctions</span><Badge variant={party.sanctionsStatus === 'CLEAR' ? 'success' : 'error'} size="sm">{party.sanctionsStatus === 'CLEAR' ? 'Clear' : 'Alert'}</Badge></div>
                </Card>
                <h3 className="text-sm font-semibold text-primary-900 dark:text-neutral-50">Payment Factory</h3>
                <Card padding="sm" className="space-y-3">
                  <div className="flex items-center justify-between"><span className="text-sm text-neutral-500 dark:text-neutral-400">POBO Eligible</span><Badge variant={party.canReceivePoboPayment ? 'success' : 'neutral'} size="sm">{party.canReceivePoboPayment ? 'Yes' : 'No'}</Badge></div>
                  <div className="flex items-center justify-between"><span className="text-sm text-neutral-500 dark:text-neutral-400">Intercompany</span><Badge variant={party.isIntercompany ? 'info' : 'neutral'} size="sm">{party.isIntercompany ? party.linkedLegalEntityCode : 'No'}</Badge></div>
                  <div className="flex items-center justify-between"><span className="text-sm text-neutral-500 dark:text-neutral-400">Netting Eligible</span><Badge variant={party.canParticipateInNetting ? 'success' : 'neutral'} size="sm">{party.canParticipateInNetting ? 'Yes' : 'No'}</Badge></div>
                </Card>
              </div>
            </div>
          )}

          {/* Bank Accounts Tab - Enhanced with Add Form */}
          {!loading && activeTab === 'accounts' && (
            <div className="space-y-4">
              <div className="flex items-center justify-between">
                <p className="text-sm text-neutral-500 dark:text-neutral-400">{bankAccounts.length} bank account{bankAccounts.length !== 1 ? 's' : ''}</p>
                <Button size="sm" leftIcon={<Plus className="w-4 h-4" />} onClick={() => setShowBankAccountForm(true)}>Add Account</Button>
              </div>

              {/* Add Bank Account Form */}
              {showBankAccountForm && (
                <Card className="border-primary-200 bg-primary-50/30 animate-fade-in dark:border-primary-700">
                  <div className="space-y-4">
                    <div className="flex items-center justify-between">
                      <h4 className="text-sm font-semibold text-primary-900 dark:text-neutral-50">New Bank Account</h4>
                      <Button variant="ghost" size="sm" onClick={() => { setShowBankAccountForm(false); setBankAccountForm(defaultBankAccountForm); }}>
                        <X className="w-4 h-4" />
                      </Button>
                    </div>
                    <div className="grid grid-cols-2 gap-4">
                      <div>
                        <label className="block text-xs font-medium text-neutral-600 mb-1 dark:text-neutral-300">Label *</label>
                        <input
                          type="text"
                          value={bankAccountForm.label}
                          onChange={(e) => setBankAccountForm(prev => ({ ...prev, label: e.target.value }))}
                          placeholder="e.g., Primary Account"
                          className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent dark:border-primary-700"
                        />
                      </div>
                      <div>
                        <label className="block text-xs font-medium text-neutral-600 mb-1 dark:text-neutral-300">Currency</label>
                        <CurrencyPicker
                          value={bankAccountForm.currency}
                          onChange={(c) => setBankAccountForm(prev => ({ ...prev, currency: c }))}
                          className="text-sm"
                          extra={['SAR']}
                        />
                      </div>
                      <div className="col-span-2">
                        <label className="block text-xs font-medium text-neutral-600 mb-1 dark:text-neutral-300">Bank Name *</label>
                        <input
                          type="text"
                          value={bankAccountForm.bankName}
                          onChange={(e) => setBankAccountForm(prev => ({ ...prev, bankName: e.target.value }))}
                          placeholder="e.g., Emirates NBD"
                          className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent dark:border-primary-700"
                        />
                      </div>
                      <div>
                        <label className="block text-xs font-medium text-neutral-600 mb-1 dark:text-neutral-300">IBAN *</label>
                        <input
                          type="text"
                          value={bankAccountForm.iban}
                          onChange={(e) => setBankAccountForm(prev => ({ ...prev, iban: e.target.value.toUpperCase() }))}
                          placeholder="AE07 0331 2345 6789 0123 456"
                          className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm font-mono focus:ring-2 focus:ring-primary-500 focus:border-transparent dark:border-primary-700"
                        />
                      </div>
                      <div>
                        <label className="block text-xs font-medium text-neutral-600 mb-1 dark:text-neutral-300">BIC / SWIFT Code</label>
                        <input
                          type="text"
                          value={bankAccountForm.bankCode}
                          onChange={(e) => setBankAccountForm(prev => ({ ...prev, bankCode: e.target.value.toUpperCase() }))}
                          placeholder="EABORAEAXXX"
                          className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm font-mono focus:ring-2 focus:ring-primary-500 focus:border-transparent dark:border-primary-700"
                        />
                      </div>
                      <div className="col-span-2 flex items-center gap-4">
                        <label className="flex items-center gap-2 cursor-pointer">
                          <input
                            type="checkbox"
                            checked={bankAccountForm.isPrimary}
                            onChange={(e) => setBankAccountForm(prev => ({ ...prev, isPrimary: e.target.checked }))}
                            className="w-4 h-4 rounded border-neutral-300 text-primary-600 focus:ring-primary-500 dark:border-primary-700 dark:text-primary-200"
                          />
                          <span className="text-sm text-neutral-700 dark:text-neutral-200">Set as primary account</span>
                        </label>
                      </div>
                    </div>
                    <div className="flex justify-end gap-2 pt-2 border-t border-neutral-200 dark:border-primary-800">
                      <Button variant="outline" size="sm" onClick={() => { setShowBankAccountForm(false); setBankAccountForm(defaultBankAccountForm); }}>Cancel</Button>
                      <Button size="sm" onClick={handleAddBankAccount} disabled={savingBankAccount}>
                        {savingBankAccount ? <Loader2 className="w-4 h-4 animate-spin mr-1" /> : <Plus className="w-4 h-4 mr-1" />}
                        Add Account
                      </Button>
                    </div>
                  </div>
                </Card>
              )}

              {bankAccounts.length > 0 ? (
                <div className="space-y-3">
                  {bankAccounts.map((account) => (
                    <Card key={account.id} padding="sm" className="hover:shadow-medium transition-shadow group">
                      <div className="flex items-start justify-between">
                        <div className="flex items-start gap-3">
                          <div className={cn('w-10 h-10 rounded-lg flex items-center justify-center', account.isPrimary ? 'bg-primary-100 dark:bg-primary-700' : 'bg-neutral-100 dark:bg-primary-800')}>
                            <CreditCard className={cn('w-5 h-5', account.isPrimary ? 'text-primary-700 dark:text-neutral-200' : 'text-neutral-500 dark:text-neutral-400')} />
                          </div>
                          <div>
                            <div className="flex items-center gap-2">
                              <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{account.label}</p>
                              {account.isPrimary && <Badge variant="info" size="sm">Primary</Badge>}
                              {account.isVerified && <Badge variant="success" size="sm"><CheckCircle className="w-3 h-3 mr-1" />Verified</Badge>}
                            </div>
                            <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">{account.bankName}</p>
                            <p className="text-xs font-mono text-primary-900 mt-1 dark:text-neutral-50">{account.iban || account.accountNumber}</p>
                          </div>
                        </div>
                        <div className="flex items-center gap-2">
                          <span className="text-xs font-medium text-neutral-600 dark:text-neutral-300">{account.currency}</span>
                          {!account.isVerified && (
                            <Button variant="ghost" size="sm" className="opacity-0 group-hover:opacity-100 transition-opacity" onClick={() => handleVerifyBankAccount(account.id)}>
                              <CheckCircle className="w-4 h-4 text-success-600 dark:text-success-300" />
                            </Button>
                          )}
                        </div>
                      </div>
                    </Card>
                  ))}
                </div>
              ) : !showBankAccountForm && (
                <div className="text-center py-12">
                  <CreditCard className="w-12 h-12 text-neutral-300 mx-auto mb-4 dark:text-neutral-600" />
                  <p className="text-sm text-neutral-500 dark:text-neutral-400">No bank accounts configured</p>
                  <Button variant="outline" size="sm" className="mt-4" onClick={() => setShowBankAccountForm(true)}>
                    <Plus className="w-4 h-4 mr-1" /> Add Bank Account
                  </Button>
                </div>
              )}
            </div>
          )}

          {/* Documents Tab */}
          {!loading && activeTab === 'documents' && (
            <div className="space-y-4">
              <div className="flex items-center justify-between">
                <p className="text-sm text-neutral-500 dark:text-neutral-400">{documents.length} document{documents.length !== 1 ? 's' : ''}</p>
                <Button size="sm" leftIcon={<Plus className="w-4 h-4" />}>Upload Document</Button>
              </div>

              {documents.length > 0 ? (
                <div className="space-y-3">
                  {documents.map((doc: any) => (
                    <Card key={doc.id} padding="sm" className="hover:shadow-medium transition-shadow">
                      <div className="flex items-start justify-between">
                        <div className="flex items-start gap-3">
                          <div className={cn('w-10 h-10 rounded-lg flex items-center justify-center',
                            doc.verificationStatus === 'VERIFIED' ? 'bg-success-100 dark:bg-success-500/20' :
                            doc.verificationStatus === 'REJECTED' ? 'bg-error-100 dark:bg-error-500/20' : 'bg-neutral-100 dark:bg-primary-800')}>
                            <Shield className={cn('w-5 h-5',
                              doc.verificationStatus === 'VERIFIED' ? 'text-success-600 dark:text-success-300' :
                              doc.verificationStatus === 'REJECTED' ? 'text-error-600 dark:text-error-300' : 'text-neutral-500 dark:text-neutral-400')} />
                          </div>
                          <div>
                            <div className="flex items-center gap-2">
                              <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{doc.name}</p>
                              <Badge variant={
                                doc.verificationStatus === 'VERIFIED' ? 'success' :
                                doc.verificationStatus === 'REJECTED' ? 'error' : 'warning'
                              } size="sm">{doc.verificationStatus}</Badge>
                            </div>
                            <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">{doc.documentType} • {doc.category}</p>
                            {doc.expiryDate && (
                              <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">Expires: {new Date(doc.expiryDate).toLocaleDateString()}</p>
                            )}
                          </div>
                        </div>
                        <span className="text-xs font-medium text-neutral-500 dark:text-neutral-400">{doc.fileType}</span>
                      </div>
                    </Card>
                  ))}
                </div>
              ) : (
                <div className="text-center py-12">
                  <Shield className="w-12 h-12 text-neutral-300 mx-auto mb-4 dark:text-neutral-600" />
                  <p className="text-sm text-neutral-500 dark:text-neutral-400">No documents uploaded</p>
                  <Button variant="outline" size="sm" className="mt-4">
                    <Plus className="w-4 h-4 mr-1" /> Upload Document
                  </Button>
                </div>
              )}
            </div>
          )}

          {/* Compliance Tab - KYC, Risk, Screening Actions */}
          {!loading && activeTab === 'compliance' && (
            <div className="grid grid-cols-2 gap-6">
              <div className="space-y-4">
                {/* KYC Section */}
                <div className="flex items-center justify-between">
                  <h3 className="text-sm font-semibold text-primary-900 dark:text-neutral-50">KYC Status</h3>
                  <Button variant="ghost" size="sm" onClick={() => setShowKycForm(!showKycForm)}>
                    <Edit className="w-4 h-4" />
                  </Button>
                </div>
                <Card padding="sm" className="space-y-3">
                  <div className="flex items-center justify-between">
                    <span className="text-sm text-neutral-500 dark:text-neutral-400">Current Status</span>
                    <Badge variant={kycConf.variant as any} size="sm"><KycIcon className="w-3 h-3 mr-1" />{kycConf.label}</Badge>
                  </div>
                  {party.kycExpiresAt && (
                    <div className="flex items-center justify-between">
                      <span className="text-sm text-neutral-500 dark:text-neutral-400">Expires</span>
                      <span className="text-sm text-primary-900 dark:text-neutral-50">{new Date(party.kycExpiresAt).toLocaleDateString()}</span>
                    </div>
                  )}
                  {party.kycVerifiedAt && (
                    <div className="flex items-center justify-between">
                      <span className="text-sm text-neutral-500 dark:text-neutral-400">Verified</span>
                      <span className="text-sm text-primary-900 dark:text-neutral-50">{new Date(party.kycVerifiedAt).toLocaleDateString()}</span>
                    </div>
                  )}
                </Card>

                {showKycForm && (
                  <Card className="border-primary-200 bg-primary-50/30 animate-fade-in dark:border-primary-700">
                    <div className="space-y-3">
                      <h4 className="text-sm font-medium text-primary-900 dark:text-neutral-50">Update KYC Status</h4>
                      <div>
                        <label className="block text-xs font-medium text-neutral-600 mb-1 dark:text-neutral-300">Status</label>
                        <select
                          value={kycFormData.kycStatus}
                          onChange={(e) => setKycFormData(prev => ({ ...prev, kycStatus: e.target.value }))}
                          className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent dark:border-primary-700"
                        >
                          <option value="PENDING">Pending</option>
                          <option value="IN_PROGRESS">In Progress</option>
                          <option value="VERIFIED">Verified</option>
                          <option value="EXPIRED">Expired</option>
                          <option value="REJECTED">Rejected</option>
                          <option value="EXEMPTED">Exempted</option>
                        </select>
                      </div>
                      <div>
                        <label className="block text-xs font-medium text-neutral-600 mb-1 dark:text-neutral-300">Expiry Date</label>
                        <input
                          type="date"
                          value={kycFormData.kycExpiresAt}
                          onChange={(e) => setKycFormData(prev => ({ ...prev, kycExpiresAt: e.target.value }))}
                          className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent dark:border-primary-700"
                        />
                      </div>
                      <div className="flex justify-end gap-2">
                        <Button variant="outline" size="sm" onClick={() => setShowKycForm(false)}>Cancel</Button>
                        <Button size="sm" onClick={handleUpdateKyc} disabled={savingCompliance}>
                          {savingCompliance ? <Loader2 className="w-4 h-4 animate-spin mr-1" /> : null}
                          Update
                        </Button>
                      </div>
                    </div>
                  </Card>
                )}

                {/* Risk Section */}
                <div className="flex items-center justify-between mt-6">
                  <h3 className="text-sm font-semibold text-primary-900 dark:text-neutral-50">Risk Rating</h3>
                  <Button variant="ghost" size="sm" onClick={() => setShowRiskForm(!showRiskForm)}>
                    <Edit className="w-4 h-4" />
                  </Button>
                </div>
                <Card padding="sm" className="space-y-3">
                  <div className="flex items-center justify-between">
                    <span className="text-sm text-neutral-500 dark:text-neutral-400">Rating</span>
                    <Badge variant={riskConfig[party.riskRating]?.variant as any} size="sm">{riskConfig[party.riskRating]?.label}</Badge>
                  </div>
                  {party.riskScore !== undefined && (
                    <div className="flex items-center justify-between">
                      <span className="text-sm text-neutral-500 dark:text-neutral-400">Score</span>
                      <span className="text-sm font-mono text-primary-900 dark:text-neutral-50">{party.riskScore}</span>
                    </div>
                  )}
                </Card>

                {showRiskForm && (
                  <Card className="border-primary-200 bg-primary-50/30 animate-fade-in dark:border-primary-700">
                    <div className="space-y-3">
                      <h4 className="text-sm font-medium text-primary-900 dark:text-neutral-50">Update Risk Rating</h4>
                      <div>
                        <label className="block text-xs font-medium text-neutral-600 mb-1 dark:text-neutral-300">Rating</label>
                        <select
                          value={riskFormData.riskRating}
                          onChange={(e) => setRiskFormData(prev => ({ ...prev, riskRating: e.target.value }))}
                          className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent dark:border-primary-700"
                        >
                          <option value="LOW">Low</option>
                          <option value="MEDIUM">Medium</option>
                          <option value="HIGH">High</option>
                          <option value="PROHIBITED">Prohibited</option>
                        </select>
                      </div>
                      <div>
                        <label className="block text-xs font-medium text-neutral-600 mb-1 dark:text-neutral-300">Score (0-100)</label>
                        <input
                          type="number"
                          min="0"
                          max="100"
                          value={riskFormData.riskScore}
                          onChange={(e) => setRiskFormData(prev => ({ ...prev, riskScore: e.target.value }))}
                          className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent dark:border-primary-700"
                        />
                      </div>
                      <div className="flex justify-end gap-2">
                        <Button variant="outline" size="sm" onClick={() => setShowRiskForm(false)}>Cancel</Button>
                        <Button size="sm" onClick={handleUpdateRisk} disabled={savingCompliance}>
                          {savingCompliance ? <Loader2 className="w-4 h-4 animate-spin mr-1" /> : null}
                          Update
                        </Button>
                      </div>
                    </div>
                  </Card>
                )}
              </div>

              <div className="space-y-4">
                {/* Screening Section */}
                <h3 className="text-sm font-semibold text-primary-900 dark:text-neutral-50">Screening</h3>
                <Card padding="sm" className="space-y-3">
                  <div className="flex items-center justify-between">
                    <span className="text-sm text-neutral-500 dark:text-neutral-400">Sanctions</span>
                    <Badge variant={party.sanctionsStatus === 'CLEAR' ? 'success' : 'error'} size="sm">
                      {party.sanctionsStatus === 'CLEAR' ? 'Clear' : party.sanctionsStatus}
                    </Badge>
                  </div>
                  <div className="flex items-center justify-between">
                    <span className="text-sm text-neutral-500 dark:text-neutral-400">PEP Status</span>
                    <Badge variant={party.pepStatus ? 'warning' : 'success'} size="sm">
                      {party.pepStatus ? 'Match' : 'Clear'}
                    </Badge>
                  </div>
                  <div className="flex items-center justify-between">
                    <span className="text-sm text-neutral-500 dark:text-neutral-400">Adverse Media</span>
                    <Badge variant={party.adverseMediaStatus ? 'warning' : 'success'} size="sm">
                      {party.adverseMediaStatus ? 'Match' : 'Clear'}
                    </Badge>
                  </div>
                </Card>

                <Button
                  variant="outline"
                  className="w-full"
                  onClick={handleRunScreening}
                  disabled={runningScreening}
                >
                  {runningScreening ? (
                    <><Loader2 className="w-4 h-4 animate-spin mr-2" /> Running Screening...</>
                  ) : (
                    <><RefreshCw className="w-4 h-4 mr-2" /> Run Screening</>
                  )}
                </Button>

                {/* Activity Summary */}
                <h3 className="text-sm font-semibold text-primary-900 mt-6 dark:text-neutral-50">Activity</h3>
                <Card padding="sm" className="space-y-3">
                  {party.onboardedAt && (
                    <div className="flex items-center justify-between">
                      <span className="text-sm text-neutral-500 dark:text-neutral-400">Onboarded</span>
                      <span className="text-sm text-primary-900 dark:text-neutral-50">{new Date(party.onboardedAt).toLocaleDateString()}</span>
                    </div>
                  )}
                  {party.lastTransactionAt && (
                    <div className="flex items-center justify-between">
                      <span className="text-sm text-neutral-500 dark:text-neutral-400">Last Transaction</span>
                      <span className="text-sm text-primary-900 dark:text-neutral-50">{new Date(party.lastTransactionAt).toLocaleDateString()}</span>
                    </div>
                  )}
                  <div className="flex items-center justify-between">
                    <span className="text-sm text-neutral-500 dark:text-neutral-400">Created</span>
                    <span className="text-sm text-primary-900 dark:text-neutral-50">{new Date(party.createdAt).toLocaleDateString()}</span>
                  </div>
                </Card>
              </div>
            </div>
          )}

          {/* POBO Tab */}
          {!loading && activeTab === 'pobo' && (
            <div className="grid grid-cols-2 gap-6">
              <div className="space-y-4">
                <h3 className="text-sm font-semibold text-primary-900 dark:text-neutral-50">POBO Configuration</h3>
                <Card padding="md" className="space-y-4">
                  <div className="flex items-center justify-between p-3 bg-cat-1-soft rounded-lg dark:bg-cat-1/15">
                    <div className="flex items-center gap-3">
                      <Wallet className="w-6 h-6 text-cat-1" />
                      <div><p className="text-sm font-medium text-cat-1">POBO Enabled</p><p className="text-xs text-cat-1">Treasury can pay on behalf of subsidiaries</p></div>
                    </div>
                    <Badge variant="success" size="sm">Active</Badge>
                  </div>
                  <div><p className="text-xs text-neutral-500 mb-1 dark:text-neutral-400">Default Payer Entity</p><div className="flex items-center gap-2 p-2 bg-neutral-50 rounded dark:bg-primary-950"><Building className="w-4 h-4 text-neutral-500 dark:text-neutral-400" /><span className="text-sm font-medium text-primary-900 dark:text-neutral-50">{party.poboDefaultPayerEntityCode || 'Not Set'}</span></div></div>
                  <div><p className="text-xs text-neutral-500 mb-1 dark:text-neutral-400">Owning Entity (Subsidiary)</p><div className="flex items-center gap-2 p-2 bg-neutral-50 rounded dark:bg-primary-950"><Globe className="w-4 h-4 text-neutral-500 dark:text-neutral-400" /><span className="text-sm font-medium text-primary-900 dark:text-neutral-50">{party.owningEntityCode || 'Not Set'}</span></div></div>
                </Card>
              </div>
              <div className="space-y-4">
                <h3 className="text-sm font-semibold text-primary-900 dark:text-neutral-50">POBO Payment Flow</h3>
                <Card padding="md">
                  <div className="space-y-3">
                    <div className="flex items-center gap-3"><div className="w-8 h-8 rounded-full bg-info-100 flex items-center justify-center text-sm font-semibold text-info-700 dark:bg-info-500/20 dark:text-info-300">1</div><div><p className="text-sm font-medium">Subsidiary creates payable</p><p className="text-xs text-neutral-500 dark:text-neutral-400">{party.owningEntityCode}</p></div></div>
                    <div className="ml-4 border-l-2 border-dashed border-neutral-200 h-6 dark:border-primary-800"></div>
                    <div className="flex items-center gap-3"><div className="w-8 h-8 rounded-full bg-cat-1/10 flex items-center justify-center text-sm font-semibold text-cat-1 dark:bg-cat-1/15">2</div><div><p className="text-sm font-medium">Treasury pays vendor</p><p className="text-xs text-neutral-500 dark:text-neutral-400">{party.poboDefaultPayerEntityCode} → {party.displayName}</p></div></div>
                    <div className="ml-4 border-l-2 border-dashed border-neutral-200 h-6 dark:border-primary-800"></div>
                    <div className="flex items-center gap-3"><div className="w-8 h-8 rounded-full bg-cat-2/10 flex items-center justify-center text-sm font-semibold text-cat-2 dark:bg-cat-2/15">3</div><div><p className="text-sm font-medium">IC Recharge created</p><p className="text-xs text-neutral-500 dark:text-neutral-400">{party.owningEntityCode} owes {party.poboDefaultPayerEntityCode}</p></div></div>
                  </div>
                </Card>
              </div>
            </div>
          )}

          {/* Intercompany Tab - Enhanced with Credit Limit Form */}
          {!loading && activeTab === 'intercompany' && (
            <div className="grid grid-cols-2 gap-6">
              <div className="space-y-4">
                <h3 className="text-sm font-semibold text-primary-900 dark:text-neutral-50">Intercompany Details</h3>
                <Card padding="md" className="space-y-4">
                  <div className="flex items-center justify-between p-3 bg-cat-2-soft rounded-lg dark:bg-cat-2/15">
                    <div className="flex items-center gap-3">
                      <Link2 className="w-6 h-6 text-cat-2" />
                      <div><p className="text-sm font-medium text-cat-2">Intercompany Party</p><p className="text-xs text-cat-2">Represents a group entity</p></div>
                    </div>
                    <Badge variant="info" size="sm">IC</Badge>
                  </div>
                  <div><p className="text-xs text-neutral-500 mb-1 dark:text-neutral-400">Linked Legal Entity</p><div className="flex items-center gap-2 p-2 bg-neutral-50 rounded dark:bg-primary-950"><Building className="w-4 h-4 text-neutral-500 dark:text-neutral-400" /><span className="text-sm font-medium text-primary-900 dark:text-neutral-50">{party.linkedLegalEntityCode}</span></div></div>
                  <div><p className="text-xs text-neutral-500 mb-1 dark:text-neutral-400">Settlement Method</p><div className="flex items-center gap-2 p-2 bg-neutral-50 rounded dark:bg-primary-950">{party.icSettlementMethod && <>{React.createElement(icSettlementConfig[party.icSettlementMethod]?.icon || Settings, { className: 'w-4 h-4 text-neutral-500 dark:text-neutral-400' })}<span className="text-sm font-medium text-primary-900 dark:text-neutral-50">{icSettlementConfig[party.icSettlementMethod]?.label}</span></>}</div></div>
                  <div className="flex items-center gap-2"><NettingBadge eligible={true} /><span className="text-xs text-neutral-500 dark:text-neutral-400">Auto-enabled for IC parties</span></div>
                </Card>
              </div>
              <div className="space-y-4">
                <div className="flex items-center justify-between">
                  <h3 className="text-sm font-semibold text-primary-900 dark:text-neutral-50">IC Credit Position</h3>
                  <Button variant="ghost" size="sm" onClick={() => setShowIcCreditForm(!showIcCreditForm)}>
                    <Edit className="w-4 h-4" />
                  </Button>
                </div>
                <Card padding="md" className="space-y-4">
                  {party.icCreditLimit ? (
                    <>
                      <div className="flex items-center justify-between"><span className="text-sm text-neutral-500 dark:text-neutral-400">Credit Limit</span><span className="section-title">{formatCurrency(party.icCreditLimit, party.icCurrency || 'AED')}</span></div>
                      <div className="flex items-center justify-between"><span className="text-sm text-neutral-500 dark:text-neutral-400">Current Exposure</span><span className="section-title">{formatCurrency(party.icCurrentExposure || 0, party.icCurrency || 'AED')}</span></div>
                      <div><div className="flex items-center justify-between mb-1"><span className="text-xs text-neutral-500 dark:text-neutral-400">Credit Utilization</span><span className="text-xs font-medium">{party.icCreditUtilizationPercent?.toFixed(1)}%</span></div><ProgressBar value={party.icCreditUtilizationPercent || 0} max={100} size="sm" variant={(party.icCreditUtilizationPercent || 0) >= 80 ? 'warning' : 'success'} /></div>
                      <div className="flex items-center justify-between pt-3 border-t border-neutral-200 dark:border-primary-800"><span className="field-label">Available Credit</span><span className="text-lg font-bold text-success-600 dark:text-success-300">{formatCurrency(party.icAvailableCredit || 0, party.icCurrency || 'AED')}</span></div>
                    </>
                  ) : (
                    <div className="text-center py-6">
                      <DollarSign className="w-10 h-10 text-neutral-300 mx-auto mb-2 dark:text-neutral-600" />
                      <p className="text-sm text-neutral-500 dark:text-neutral-400">No credit limit configured</p>
                      <Button variant="outline" size="sm" className="mt-3" onClick={() => setShowIcCreditForm(true)}>Set Credit Limit</Button>
                    </div>
                  )}
                </Card>

                {/* IC Credit Limit Form */}
                {showIcCreditForm && (
                  <Card className="border-primary-200 bg-primary-50/30 animate-fade-in dark:border-primary-700">
                    <div className="space-y-3">
                      <h4 className="text-sm font-medium text-primary-900 dark:text-neutral-50">
                        {party.icCreditLimit ? 'Update Credit Limit' : 'Set Credit Limit'}
                      </h4>
                      <div className="grid grid-cols-2 gap-3">
                        <div>
                          <label className="block text-xs font-medium text-neutral-600 mb-1 dark:text-neutral-300">Amount</label>
                          <input
                            type="number"
                            min="0"
                            step="1000"
                            value={icCreditForm.creditLimit}
                            onChange={(e) => setIcCreditForm(prev => ({ ...prev, creditLimit: e.target.value }))}
                            placeholder="1,000,000"
                            className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent dark:border-primary-700"
                          />
                        </div>
                        <div>
                          <label className="block text-xs font-medium text-neutral-600 mb-1 dark:text-neutral-300">Currency</label>
                          <CurrencyPicker
                            value={icCreditForm.currency}
                            onChange={(c) => setIcCreditForm(prev => ({ ...prev, currency: c }))}
                            className="text-sm"
                          />
                        </div>
                      </div>
                      <div>
                        <label className="block text-xs font-medium text-neutral-600 mb-1 dark:text-neutral-300">Approval Notes</label>
                        <input
                          type="text"
                          value={icCreditForm.approvalNotes}
                          onChange={(e) => setIcCreditForm(prev => ({ ...prev, approvalNotes: e.target.value }))}
                          placeholder="Reason for limit change..."
                          className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent dark:border-primary-700"
                        />
                      </div>
                      <div className="flex justify-end gap-2">
                        <Button variant="outline" size="sm" onClick={() => setShowIcCreditForm(false)}>Cancel</Button>
                        <Button size="sm" onClick={handleUpdateIcCreditLimit} disabled={savingIcCredit}>
                          {savingIcCredit ? <Loader2 className="w-4 h-4 animate-spin mr-1" /> : null}
                          Save
                        </Button>
                      </div>
                    </div>
                  </Card>
                )}
              </div>
            </div>
          )}
        </div>
      </div>
    </Modal>
  );
};

// ============================================================================
// MAIN PAGE COMPONENT
// ============================================================================

const PartiesPage: React.FC = () => {
  const [searchQuery, setSearchQuery] = useState('');
  const [roleFilter, setRoleFilter] = useState<PartyRole | 'ALL'>('ALL');
  const [kycFilter, setKycFilter] = useState<KycStatus | 'ALL'>('ALL');
  const [selectedParty, setSelectedParty] = useState<Party | null>(null);
  const [parties, setParties] = useState<Party[]>([]);
  const [stats, setStats] = useState<PartyStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [_error, setError] = useState<string | null>(null);
  const [entities, setEntities] = useState<LegalEntity[]>([]);
  
  // Corporate selection
  const [corporates, setCorporates] = useState<Corporate[]>([]);
  const [selectedCorporateId, setSelectedCorporateId] = useState<string | undefined>();
  const [corporatesLoading, setCorporatesLoading] = useState(true);
  
  // Phase 1: POBO/IC filters
  const [selectedEntityId, setSelectedEntityId] = useState<string | undefined>();
  const [poboFilter, setPoboFilter] = useState(false);
  const [icFilter, setIcFilter] = useState(false);
  const [nettingFilter, setNettingFilter] = useState(false);

  // Add/Edit Modal State
  const [isFormModalOpen, setIsFormModalOpen] = useState(false);
  const [editingParty, setEditingParty] = useState<Party | null>(null);

  // Load corporates on mount - FIX: Use extractArray helper
  useEffect(() => {
    setCorporatesLoading(true);
    corporatesApi.getAll()
      .then(res => {
        const corps = extractArray<Corporate>(res as any);
        setCorporates(corps);
        // Auto-select first corporate if available
        if (corps.length > 0 && !selectedCorporateId) {
          setSelectedCorporateId(corps[0].id);
        }
      })
      .catch(err => {
        console.error('Failed to load corporates:', err);
        showToast('Failed to load corporates', 'error');
      })
      .finally(() => setCorporatesLoading(false));
  }, []);

  // Load legal entities when corporate changes - FIX: Use extractArray helper
  useEffect(() => {
    if (selectedCorporateId) {
      legalEntityApi.getByCorporate(selectedCorporateId)
        .then(res => {
          const ents = extractArray<LegalEntity>(res as any);
          setEntities(ents);
        })
        .catch(err => {
          console.error('Failed to load entities:', err);
          setEntities([]);
        });
    } else {
      setEntities([]);
    }
    // Reset entity filter when corporate changes
    setSelectedEntityId(undefined);
  }, [selectedCorporateId]);

  // Load parties data - FIX: Pass corporateId properly via the API (uses header)
  const loadData = useCallback(async () => {
    // Don't load if no corporate selected
    if (!selectedCorporateId) {
      setParties([]);
      setStats(null);
      setLoading(false);
      return;
    }

    setLoading(true);
    setError(null);
    try {
      // Build params - corporateId is passed to partiesApi.getAll which handles the header
      const params: PartySearchRequest = {
        corporateId: selectedCorporateId, // This will be passed as X-Corporate-Id header in partiesApi.getAll
      };
      if (searchQuery) params.query = searchQuery;
      if (roleFilter !== 'ALL') params.role = roleFilter;
      if (kycFilter !== 'ALL') params.kycStatus = kycFilter;
      if (selectedEntityId) params.owningEntityId = selectedEntityId;
      if (poboFilter) params.poboEligibleOnly = true;
      if (icFilter) params.intercompanyOnly = true;
      if (nettingFilter) params.nettingEligibleOnly = true;

      const res = await partiesApi.getAll(params);

      // Backend returns PartyListResponse directly: { parties, stats, totalCount, page, pageSize }
      // Handle both direct response and wrapped ApiResponse formats for backward compatibility
      const responseData = res as any;
      const partiesList = responseData?.parties || responseData?.data?.parties || [];
      const statsData = responseData?.stats || responseData?.data?.stats || null;

      if (partiesList.length > 0 || statsData) {
        setParties(partiesList);
        setStats(statsData);
      } else {
        // Graceful handling - show empty state instead of error for most cases
        setParties([]);
        setStats(null);
      }
    } catch (err) {
      console.error('Failed to load parties:', err);
      // Set empty state rather than blocking the page
      setParties([]);
      setStats(null);
      // Only show error for critical failures
      // setError('Failed to load parties. Please try again.');
    } finally {
      setLoading(false);
    }
  }, [selectedCorporateId, searchQuery, roleFilter, kycFilter, selectedEntityId, poboFilter, icFilter, nettingFilter]);

  useEffect(() => { loadData(); }, [loadData]);

  // Handle Add Party click
  const handleAddParty = () => {
    setEditingParty(null);
    setIsFormModalOpen(true);
  };

  // Handle Edit Party from detail modal
  const handleEditParty = (party: Party) => {
    setEditingParty(party);
    setIsFormModalOpen(true);
  };

  // Handle party save (add or update)
  const handlePartySaved = (_party: Party) => {
    loadData(); // Reload the list
  };

  // Calculate display stats
  const displayStats: PartyStats = stats || {
    totalParties: parties.length,
    customers: parties.filter(p => p.roles?.includes('CUSTOMER')).length,
    vendors: parties.filter(p => p.roles?.includes('VENDOR')).length,
    employees: parties.filter(p => p.roles?.includes('EMPLOYEE')).length,
    government: parties.filter(p => p.roles?.includes('GOVERNMENT')).length,
    financial: parties.filter(p => p.roles?.includes('FINANCIAL')).length,
    kycPending: parties.filter(p => p.kycStatus === 'PENDING' || p.kycStatus === 'EXPIRED').length,
    kycExpired: 0, highRisk: 0, sanctionsAlerts: 0,
    poboEligibleVendors: parties.filter(p => p.poboEligible).length,
    intercompanyParties: parties.filter(p => p.isIntercompany).length,
    nettingEligibleParties: parties.filter(p => p.nettingEligible || p.isIntercompany).length,
  };

  const roleTabs = [
    { id: 'ALL' as const, label: 'All', count: displayStats.totalParties, icon: Users },
    { id: 'CUSTOMER' as const, label: 'Customers', count: displayStats.customers, icon: Users },
    { id: 'VENDOR' as const, label: 'Vendors', count: displayStats.vendors, icon: Briefcase },
  ];

  // Register page-level toolbar in the Layout header — same pattern as every
  // other treasury page. Refresh / Export / Add Party no longer float inside
  // the content body. Refresh stays at the front so it sits closest to the
  // page title (mirrors Bank Accounts / VIBAN / Currency Mirrors ordering).
  usePageHeaderActions(
    () => (
      <>
        <Button
          variant="outline"
          size="sm"
          leftIcon={<RefreshCw className="w-4 h-4" />}
          onClick={loadData}
          disabled={loading}
        >
          Refresh
        </Button>
        <Button variant="outline" size="sm" leftIcon={<Download className="w-4 h-4" />}>
          Export
        </Button>
        <Button
          size="sm"
          leftIcon={<Plus className="w-4 h-4" />}
          disabled={!selectedCorporateId}
          onClick={handleAddParty}
        >
          Add Party
        </Button>
      </>
    ),
    [loadData, loading, selectedCorporateId]
  );

  if (loading && !corporatesLoading && parties.length === 0) return <div className="flex items-center justify-center h-96"><Loader2 className="w-8 h-8 animate-spin text-primary-600 dark:text-primary-200" /></div>;

  // Note: We no longer block the page on error - show empty state instead
  // This ensures the page loads gracefully even if API fails

  return (
    <Page>
      {/* Refresh / Export / Add Party now live in the Layout header
          (registered above via usePageHeaderActions). No floating in-page
          button strip. */}

      {/* Filter scope — Corporate + Entity selectors combined on one row.
          Right-side "Test Multinational Corp" / "Viewing: All Entities" labels
          were removed (they duplicated the dropdown value). */}
      <Card padding="sm" className="animate-fade-in" style={{ animationDelay: '0.05s' }}>
        <div className="flex flex-wrap items-center gap-x-6 gap-y-3">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-lg bg-primary-100 flex items-center justify-center dark:bg-primary-700 shrink-0">
              <Landmark className="w-4 h-4 text-primary-600 dark:text-primary-200" />
            </div>
            <div className="flex flex-col">
              <span className="text-xs text-neutral-500 dark:text-neutral-400">Corporate</span>
              <CorporateSelector
                value={selectedCorporateId}
                onChange={setSelectedCorporateId}
                corporates={corporates}
                loading={corporatesLoading}
              />
            </div>
          </div>

          {selectedCorporateId && (
            <div className="flex items-center gap-3">
              <div className="w-9 h-9 rounded-lg bg-accent-100 flex items-center justify-center dark:bg-accent-500/20 shrink-0">
                <Building className="w-4 h-4 text-accent-600 dark:text-accent-300" />
              </div>
              <div className="flex flex-col">
                <span className="text-xs text-neutral-500 dark:text-neutral-400">Entity</span>
                <EntitySelector value={selectedEntityId} onChange={setSelectedEntityId} entities={entities} />
              </div>
            </div>
          )}
        </div>
      </Card>

      {/* Show message if no corporate selected */}
      {!selectedCorporateId && !corporatesLoading && (
        <Card className="bg-warning-50 border-warning-200 dark:bg-warning-500/10 dark:border-warning-500/30">
          <div className="flex items-center gap-3">
            <AlertTriangle className="w-5 h-5 text-warning-600 dark:text-warning-300" />
            <p className="text-sm text-warning-800 dark:text-warning-300">Please select a corporate to view parties.</p>
          </div>
        </Card>
      )}

      {/* Only show content if corporate is selected */}
      {selectedCorporateId && (
        <>
          {/* Stats Grid — harmonised:
              · All 7 cards share the same active ring (ring-primary-500 / dark:accent-400)
                so "selected" reads the same across role-type filters and capability-flag filters.
              · Neutral role tabs get a subtle dark-mode tint so they have visible card
                presence next to the colour-tinted capability cards.
              · Colour-tinted cards (POBO/IC/Netting) keep their semantic hue (icon + value text)
                but the dark backgrounds are dialled back so they don't dominate the row.
              · Grid is 6-up on lg (3 role tabs + 3 capability filters) — was 7-up
                with an empty trailing slot that left the row looking misaligned. */}
          <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-3">
            {roleTabs.map((tab, idx) => {
              const Icon = tab.icon;
              const isActive = roleFilter === tab.id;
              return (
                <div key={tab.id} onClick={() => setRoleFilter(tab.id)} className="cursor-pointer">
                  <Card
                    padding="sm"
                    hover
                    className={cn(
                      'transition-all animate-fade-in dark:bg-primary-900/40',
                      isActive && 'ring-2 ring-primary-500 dark:ring-accent-400 bg-primary-50 dark:bg-primary-800/60'
                    )}
                    style={{ animationDelay: `${0.15 + idx * 0.03}s` }}
                  >
                    <div className="flex items-center gap-2"><Icon className="w-4 h-4 text-neutral-500 dark:text-neutral-400" /><span className="text-xs text-neutral-500 dark:text-neutral-400">{tab.label}</span></div>
                    {/* Phase 12 Task E: .stat-value-xs replaces the raw
                        `text-xl font-semibold` display-tier hand-roll. */}
                    <p className="stat-value-xs mt-1">{tab.count}</p>
                  </Card>
                </div>
              );
            })}
            <div onClick={() => setPoboFilter(!poboFilter)} className="cursor-pointer">
              <Card
                padding="sm"
                hover
                className={cn(
                  'transition-all animate-fade-in bg-gradient-to-br from-cat-1-soft to-info-50 dark:from-cat-1/10 dark:to-info-500/10',
                  poboFilter && 'ring-2 ring-primary-500 dark:ring-accent-400'
                )}
                style={{ animationDelay: '0.24s' }}
              >
                <div className="flex items-center gap-2"><Wallet className="w-4 h-4 text-cat-1" /><span className="text-xs text-cat-1">POBO Ready</span></div>
                <p className="stat-value-xs text-cat-1 mt-1">{displayStats.poboEligibleVendors}</p>
              </Card>
            </div>
            <div onClick={() => setIcFilter(!icFilter)} className="cursor-pointer">
              <Card
                padding="sm"
                hover
                className={cn(
                  'transition-all animate-fade-in bg-gradient-to-br from-cat-2-soft to-cat-4-soft dark:from-cat-2/10 dark:to-cat-4/10',
                  icFilter && 'ring-2 ring-primary-500 dark:ring-accent-400'
                )}
                style={{ animationDelay: '0.27s' }}
              >
                <div className="flex items-center gap-2"><Link2 className="w-4 h-4 text-cat-2" /><span className="text-xs text-cat-2">Intercompany</span></div>
                <p className="stat-value-xs text-cat-2 mt-1">{displayStats.intercompanyParties}</p>
              </Card>
            </div>
            <div onClick={() => setNettingFilter(!nettingFilter)} className="cursor-pointer">
              <Card
                padding="sm"
                hover
                className={cn(
                  'transition-all animate-fade-in bg-gradient-to-br from-cat-3-soft to-cat-5-soft dark:from-cat-3/10 dark:to-cat-5/10',
                  nettingFilter && 'ring-2 ring-primary-500 dark:ring-accent-400'
                )}
                style={{ animationDelay: '0.3s' }}
              >
                <div className="flex items-center gap-2"><Repeat className="w-4 h-4 text-cat-3" /><span className="text-xs text-cat-3">Netting</span></div>
                <p className="stat-value-xs text-cat-3 mt-1">{displayStats.nettingEligibleParties}</p>
              </Card>
            </div>
          </div>

          {/* Active Filters */}
          {(poboFilter || icFilter || nettingFilter || selectedEntityId) && (
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-sm text-neutral-500 dark:text-neutral-400">Active Filters:</span>
              {selectedEntityId && <span className="cursor-pointer" onClick={() => setSelectedEntityId(undefined)}><Badge variant="info" size="sm">{entities.find(e => e.id === selectedEntityId)?.entityCode} ×</Badge></span>}
              {poboFilter && <span className="cursor-pointer" onClick={() => setPoboFilter(false)}><Badge variant="info" size="sm">POBO Eligible ×</Badge></span>}
              {icFilter && <span className="cursor-pointer" onClick={() => setIcFilter(false)}><Badge variant="info" size="sm">Intercompany ×</Badge></span>}
              {nettingFilter && <span className="cursor-pointer" onClick={() => setNettingFilter(false)}><Badge variant="info" size="sm">Netting Eligible ×</Badge></span>}
              <button onClick={() => { setSelectedEntityId(undefined); setPoboFilter(false); setIcFilter(false); setNettingFilter(false); }} className="text-xs text-error-600 hover:underline dark:text-error-300">Clear All</button>
            </div>
          )}

          {/* Search & KYC Filter. The inline Refresh button used to live at
              the end of this row — it now sits in the Layout header next to
              Export / Add Party, so search + KYC filter get the full row. */}
          <Card>
            <div className="flex flex-col sm:flex-row gap-4">
              <div className="flex-1 relative">
                <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-neutral-400 dark:text-neutral-500" />
                <input type="text" placeholder="Search by name, code, or tax ID..." value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} className="w-full pl-10 pr-4 py-2.5 border border-neutral-300 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent dark:border-primary-700" />
              </div>
              <select value={kycFilter} onChange={(e) => setKycFilter(e.target.value as any)} className="px-4 py-2.5 border border-neutral-300 rounded-lg dark:border-primary-700 min-w-[180px]">
                <option value="ALL">All KYC Status</option>
                <option value="VERIFIED">Verified</option>
                <option value="PENDING">Pending</option>
                <option value="EXEMPTED">Exempted</option>
              </select>
            </div>
          </Card>

          {/* Table */}
          <Card padding="none" className="animate-fade-in" style={{ animationDelay: '0.35s' }}>
            <div className="overflow-x-auto">
              <table className="data-table">
                <thead className="data-table-header">
                  <tr>
                    <th className="data-table-header-cell">Party</th>
                    <th className="data-table-header-cell">Entity</th>
                    <th className="data-table-header-cell">Roles</th>
                    <th className="data-table-header-cell text-center">Payment Factory</th>
                    <th className="data-table-header-cell text-center">KYC</th>
                    <th className="data-table-header-cell text-center">Status</th>
                    <th className="data-table-header-cell text-center w-32">Actions</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-neutral-100 dark:divide-primary-800/60">
                  {parties.map((party) => {
                    const TypeIcon = typeConfig[party.partyType]?.icon || Building2;
                    const kycConf = kycStatusConfig[party.kycStatus] || kycStatusConfig.PENDING;
                    return (
                      <tr key={party.id} className="data-table-row group cursor-pointer" onClick={() => setSelectedParty(party)}>
                        <td className="data-table-cell">
                          <div className="flex items-center gap-3">
                            <div className={cn('w-10 h-10 rounded-xl flex items-center justify-center transition-transform group-hover:scale-105', party.isIntercompany ? 'bg-cat-2/10 dark:bg-cat-2/15' : party.status === 'ACTIVE' ? 'bg-primary-100 dark:bg-primary-700' : 'bg-error-100 dark:bg-error-500/20')}>
                              <TypeIcon className={cn('w-5 h-5', party.isIntercompany ? 'text-cat-2' : party.status === 'ACTIVE' ? 'text-primary-700 dark:text-neutral-200' : 'text-error-600 dark:text-error-300')} />
                            </div>
                            <div><p className="text-sm font-semibold text-primary-900 group-hover:text-primary-600 transition-colors dark:text-neutral-50">{party.displayName || party.legalName}</p><p className="text-xs text-neutral-500 dark:text-neutral-400">{party.partyCode}</p></div>
                          </div>
                        </td>
                        <td className="data-table-cell"><OwningEntityBadge entityCode={party.owningEntityCode} /></td>
                        <td className="data-table-cell">
                          <div className="flex flex-wrap gap-1">
                            {party.roles.slice(0, 2).map((role) => {
                              const config = roleConfig[role];
                              return config ? <span key={role} className={cn('px-2 py-0.5 rounded text-xs font-medium', config.bgColor, config.color)}>{config.label}</span> : null;
                            })}
                          </div>
                        </td>
                        <td className="data-table-cell">
                          <div className="flex flex-wrap justify-center gap-1">
                            <PoboBadge eligible={party.poboEligible} />
                            <IntercompanyBadge isIntercompany={party.isIntercompany} linkedEntityCode={party.linkedLegalEntityCode} />
                            <NettingBadge eligible={party.nettingEligible || party.isIntercompany} />
                            {party.isIntercompany && party.icCreditLimit && <IcCreditBadge limit={party.icCreditLimit} exposure={party.icCurrentExposure} />}
                          </div>
                        </td>
                        <td className="data-table-cell text-center"><Badge variant={kycConf.variant as any} size="sm">{kycConf.label}</Badge></td>
                        <td className="data-table-cell text-center"><Badge variant={party.status === 'ACTIVE' ? 'success' : 'error'} size="sm">{party.status}</Badge></td>
                        <td className="data-table-cell text-center" onClick={(e) => e.stopPropagation()}>
                          <div className="flex items-center justify-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                            <Button variant="ghost" size="sm" onClick={() => setSelectedParty(party)}><Eye className="w-4 h-4" /></Button>
                            <Button variant="ghost" size="sm" onClick={() => handleEditParty(party)}><Edit className="w-4 h-4" /></Button>
                            <Button variant="ghost" size="sm"><MoreHorizontal className="w-4 h-4" /></Button>
                          </div>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            {parties.length === 0 && (
              <div className="p-12 text-center">
                <Users className="w-12 h-12 text-neutral-300 mx-auto mb-4 dark:text-neutral-600" />
                <p className="text-lg font-medium text-primary-900 dark:text-neutral-50">No parties found</p>
                <p className="text-sm text-neutral-500 mt-1 dark:text-neutral-400">Try adjusting your search or filters</p>
              </div>
            )}
          </Card>

          {/* Detail Modal */}
          <PartyDetailModal
            party={selectedParty}
            onClose={() => setSelectedParty(null)}
            entities={entities}
            onEdit={handleEditParty}
            onPartyUpdated={loadData}
          />

          {/* Add/Edit Modal */}
          <PartyFormModal
            isOpen={isFormModalOpen}
            onClose={() => {
              setIsFormModalOpen(false);
              setEditingParty(null);
            }}
            party={editingParty}
            corporateId={selectedCorporateId || ''}
            entities={entities}
            onSave={handlePartySaved}
          />
        </>
      )}
    </Page>
  );
};

export default PartiesPage;