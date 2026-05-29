// ============================================================================
// VA CREATE MODAL - HIERARCHY-AWARE VA CREATION
//
// NEW FLOW (v4.5 - Unified Hierarchy Architecture):
// 1. Corporate Selection
// 2. Program Selection (determines hierarchy structure)
// 3. Hierarchy Dimension Inputs (based on program's level config)
// 4. VA Details
//
// Key Features:
// - When program selected, loads hierarchy level configurations
// - User fills dimension values for each level (e.g., Region=NORTH, Entity=ACME)
// - Backend auto-creates intermediate aggregation nodes if they don't exist
// - VA is placed at the correct position in hierarchy
// - Shadow Account is optional (only for legacy/standalone VAs)
//
// Path: src/pages/VaCreateModal.tsx
// ============================================================================

import React, { useState, useEffect, useMemo } from 'react';
import {
  CreditCard, ChevronRight,
  AlertCircle, CheckCircle, Loader2, Building2,
  GitBranch, Globe, DollarSign, Euro, PoundSterling, Layers,
} from 'lucide-react';
import { Button, Badge, Input } from '../components/ui';
import { Modal, Alert } from '../components/ui/enhanced';
import { cn } from '../utils';
import toast from 'react-hot-toast';

// ============================================================================
// TYPES
// ============================================================================

export interface Corporate {
  id: string;
  name: string;
  legalName?: string;
  baseCurrency?: string;
}

export interface LegalEntity {
  id: string;
  entityCode: string;
  entityName: string;
  shortName?: string;
  corporateId: string;
  parentEntityId?: string;
  functionalCurrency: string;
  countryCode?: string;
  entityType: 'HOLDING' | 'SUBSIDIARY' | 'BRANCH' | 'TREASURY_CENTER' | 'SPV';
  status: 'ACTIVE' | 'INACTIVE';
}

export interface Program {
  id: string;
  programCode: string;
  programName: string;
  programType: string;
  currencyCode: string;
  corporateId?: string;
  hierarchyEnabled?: boolean;
  kycRequired?: boolean;
}

export interface HierarchyLevelConfig {
  id?: string;
  programId?: string;
  levelNumber: number;
  levelName: string;
  dimensionType: string;
  isRequired?: boolean;
  allowedValues?: string[];
  description?: string;
  icon?: string;
}

export interface ShadowAccount {
  id: string;
  vaNumber: string;
  vaName: string;
  currencyCode: string;
  bankBalance: number;
  linkedPhysicalAccountId: string;
  physicalAccountNumber?: string;
  bankName?: string;
}

export interface VaResponse {
  id: string;
  vaNumber: string;
  vaName: string;
  currencyCode: string;
  status: string;
  parentAccountId?: string;
  hierarchyPathVa?: string;
}

export interface VaCreateModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: (va?: VaResponse) => void;
  corporates: Corporate[];
  programs?: Program[];
  preSelectedCorporateId?: string;
  preSelectedProgramId?: string;
}

// ============================================================================
// API HELPERS
// ============================================================================

const API_BASE = '/api/v1';

async function fetchApi<T>(url: string): Promise<T | null> {
  try {
    const response = await fetch(API_BASE + url);
    if (!response.ok) throw new Error(`API error: ${response.status}`);
    const data = await response.json();
    return data.data || data;
  } catch (error) {
    console.error('API Error:', error);
    return null;
  }
}

async function postApi<T>(url: string, body: any): Promise<{ success: boolean; data?: T; message?: string }> {
  try {
    const response = await fetch(API_BASE + url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    const data = await response.json();
    if (!response.ok) {
      return { success: false, message: data.message || 'Request failed' };
    }
    return { success: true, data: data.data || data };
  } catch (error: any) {
    return { success: false, message: error.message };
  }
}

// ============================================================================
// CURRENCY ICONS
// ============================================================================

const CURRENCY_ICONS: Record<string, React.FC<{ className?: string }>> = {
  USD: DollarSign,
  EUR: Euro,
  GBP: PoundSterling,
};

const CurrencyIcon: React.FC<{ currency: string; className?: string }> = ({ currency, className }) => {
  const Icon = CURRENCY_ICONS[currency] || Globe;
  return <Icon className={className} />;
};

// ============================================================================
// DIMENSION TYPE ICONS
// ============================================================================

const DIMENSION_ICONS: Record<string, string> = {
  CURRENCY: '💱',
  REGION: '🌍',
  COUNTRY: '🏳️',
  STATE: '📍',
  CITY: '🏙️',
  ENTITY: '🏢',
  DEPARTMENT: '👥',
  COST_CENTER: '💰',
  ACCOUNT_TYPE: '📊',
  CHANNEL: '📡',
  PLATFORM: '🌐',
  SEGMENT: '🎯',
  CUSTOMER: '👤',
  MERCHANT: '🏪',
  VIRTUAL_ACCOUNT: '💳',
};

// ============================================================================
// STEP 1: CORPORATE SELECTION
// ============================================================================

interface CorporateStepProps {
  corporates: Corporate[];
  selectedId: string;
  onSelect: (id: string) => void;
  error?: string;
}

const CorporateStep: React.FC<CorporateStepProps> = ({ corporates, selectedId, onSelect, error }) => (
  <div className="space-y-4">
    <div className="flex items-center gap-2 text-sm text-neutral-600 dark:text-neutral-300 mb-4">
      <Building2 className="w-4 h-4" />
      <span>Select the corporate entity that will own this virtual account</span>
    </div>

    <div className="grid grid-cols-1 gap-3 max-h-64 overflow-y-auto">
      {corporates.map(corp => (
        <button
          key={corp.id}
          onClick={() => onSelect(corp.id)}
          className={cn(
            "flex items-center gap-4 p-4 rounded-xl border-2 transition-all text-left",
            selectedId === corp.id
              ? "border-primary-500 bg-primary-50 dark:bg-primary-800/40"
              : "border-neutral-200 dark:border-primary-800 hover:border-primary-300 hover:bg-neutral-50 dark:hover:bg-primary-800/50"
          )}
        >
          <div className={cn(
            "w-12 h-12 rounded-lg flex items-center justify-center",
            selectedId === corp.id ? "bg-primary-100 dark:bg-primary-700" : "bg-neutral-100 dark:bg-primary-800"
          )}>
            <Building2 className={cn(
              "w-6 h-6",
              selectedId === corp.id ? "text-primary-600 dark:text-primary-200" : "text-neutral-500 dark:text-neutral-400 dark:text-neutral-500"
            )} />
          </div>
          <div className="flex-1">
            <p className="font-medium text-neutral-900 dark:text-neutral-50">{corp.name}</p>
            {corp.legalName && corp.legalName !== corp.name && (
              <p className="text-sm text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">{corp.legalName}</p>
            )}
            {corp.baseCurrency && (
              <Badge variant="neutral" size="sm" className="mt-1">
                Base: {corp.baseCurrency}
              </Badge>
            )}
          </div>
          {selectedId === corp.id && (
            <CheckCircle className="w-5 h-5 text-primary-600 dark:text-primary-200" />
          )}
        </button>
      ))}
    </div>

    {error && (
      <p className="text-sm text-error-600 dark:text-error-300 flex items-center gap-1">
        <AlertCircle className="w-4 h-4" />
        {error}
      </p>
    )}
  </div>
);

// ============================================================================
// STEP 2: PROGRAM SELECTION
// ============================================================================

interface ProgramStepProps {
  programs: Program[];
  selectedId: string;
  onSelect: (id: string) => void;
  loading: boolean;
  error?: string;
}

const ProgramStep: React.FC<ProgramStepProps> = ({ programs, selectedId, onSelect, loading, error }) => {
  if (loading) {
    return (
      <div className="flex items-center justify-center py-12">
        <Loader2 className="w-6 h-6 animate-spin text-primary-500" />
        <span className="ml-2 text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">Loading programs...</span>
      </div>
    );
  }

  if (programs.length === 0) {
    return (
      <Alert variant="warning" title="No Programs Found">
        <p className="text-sm">
          Please create a program for this corporate before creating virtual accounts.
        </p>
      </Alert>
    );
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2 text-sm text-neutral-600 dark:text-neutral-300 mb-2">
        <Layers className="w-4 h-4" />
        <span>Select the program for this virtual account</span>
      </div>

      <Alert variant="info" title="Program determines hierarchy structure">
        <p className="text-sm">
          Each program has configured hierarchy levels. The VA will be placed according to the dimension values you provide.
        </p>
      </Alert>

      <div className="grid grid-cols-1 gap-3 max-h-64 overflow-y-auto">
        {programs.map(program => (
          <button
            key={program.id}
            onClick={() => onSelect(program.id)}
            className={cn(
              "flex items-center gap-4 p-4 rounded-xl border-2 transition-all text-left",
              selectedId === program.id
                ? "border-primary-500 bg-primary-50 dark:bg-primary-800/40"
                : "border-neutral-200 dark:border-primary-800 hover:border-primary-300 hover:bg-neutral-50 dark:hover:bg-primary-800/50"
            )}
          >
            <div className={cn(
              "w-12 h-12 rounded-lg flex items-center justify-center",
              selectedId === program.id ? "bg-primary-100 dark:bg-primary-700" : "bg-neutral-100 dark:bg-primary-800"
            )}>
              <Layers className={cn(
                "w-6 h-6",
                selectedId === program.id ? "text-primary-600 dark:text-primary-200" : "text-neutral-500 dark:text-neutral-400 dark:text-neutral-500"
              )} />
            </div>
            <div className="flex-1">
              <div className="flex items-center gap-2">
                <p className="font-medium text-neutral-900 dark:text-neutral-50">{program.programName}</p>
                <Badge variant="neutral" size="sm">{program.programCode}</Badge>
              </div>
              <div className="flex items-center gap-2 mt-1">
                <Badge variant="info" size="sm">{program.programType}</Badge>
                <span className="text-sm text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">{program.currencyCode}</span>
                {program.hierarchyEnabled && (
                  <Badge variant="success" size="sm">Hierarchy</Badge>
                )}
              </div>
            </div>
            {selectedId === program.id && (
              <CheckCircle className="w-5 h-5 text-primary-600 dark:text-primary-200" />
            )}
          </button>
        ))}
      </div>

      {error && (
        <p className="text-sm text-error-600 dark:text-error-300 flex items-center gap-1">
          <AlertCircle className="w-4 h-4" />
          {error}
        </p>
      )}
    </div>
  );
};

// ============================================================================
// STEP 3: HIERARCHY DIMENSIONS
// ============================================================================

interface HierarchyDimensionsStepProps {
  levelConfigs: HierarchyLevelConfig[];
  dimensionValues: Record<string, string>;
  onDimensionChange: (levelNumber: number, value: string) => void;
  loading: boolean;
  programCurrency: string;
  error?: string;
}

const HierarchyDimensionsStep: React.FC<HierarchyDimensionsStepProps> = ({
  levelConfigs,
  dimensionValues,
  onDimensionChange,
  loading,
  programCurrency,
  error,
}) => {
  if (loading) {
    return (
      <div className="flex items-center justify-center py-12">
        <Loader2 className="w-6 h-6 animate-spin text-primary-500" />
        <span className="ml-2 text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">Loading hierarchy configuration...</span>
      </div>
    );
  }

  // Filter to show only levels 1-6 (L7 is the VA itself)
  const configurableLevels = levelConfigs.filter(l => l.levelNumber >= 1 && l.levelNumber <= 6);

  if (configurableLevels.length === 0) {
    return (
      <Alert variant="warning" title="No Hierarchy Levels Configured">
        <p className="text-sm">
          This program doesn't have hierarchy levels configured. Please configure hierarchy levels first.
        </p>
      </Alert>
    );
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2 text-sm text-neutral-600 dark:text-neutral-300 mb-2">
        <GitBranch className="w-4 h-4" />
        <span>Define where this VA should be placed in the hierarchy</span>
      </div>

      <Alert variant="info" title="Hierarchy Position">
        <p className="text-sm">
          Fill in the dimension values for each level. Intermediate aggregation nodes will be auto-created if they don't exist.
        </p>
      </Alert>

      <div className="space-y-3">
        {configurableLevels.map((level) => {
          const icon = DIMENSION_ICONS[level.dimensionType] || '📁';
          const hasAllowedValues = level.allowedValues && level.allowedValues.length > 0;
          const currentValue = dimensionValues[`L${level.levelNumber}`] || '';

          return (
            <div key={level.levelNumber} className="p-3 bg-neutral-50 dark:bg-primary-950 rounded-lg border border-neutral-200 dark:border-primary-800">
              <div className="flex items-center gap-2 mb-2">
                <span className="text-lg">{icon}</span>
                <span className="font-medium text-neutral-800 dark:text-neutral-100">
                  L{level.levelNumber}: {level.levelName}
                </span>
                {level.isRequired && (
                  <span className="text-error-500 text-sm">*</span>
                )}
                <Badge variant="neutral" size="sm">{level.dimensionType}</Badge>
              </div>

              {level.description && (
                <p className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500 mb-2">{level.description}</p>
              )}

              {hasAllowedValues ? (
                <select
                  value={currentValue}
                  onChange={(e) => onDimensionChange(level.levelNumber, e.target.value)}
                  className="w-full px-3 py-2 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm bg-white dark:bg-primary-900"
                >
                  <option value="">Select {level.levelName}...</option>
                  {level.allowedValues!.map(val => (
                    <option key={val} value={val}>{val}</option>
                  ))}
                </select>
              ) : (
                <Input
                  value={currentValue}
                  onChange={(e) => onDimensionChange(level.levelNumber, e.target.value.toUpperCase())}
                  placeholder={`Enter ${level.levelName}...`}
                  className="text-sm"
                />
              )}
            </div>
          );
        })}
      </div>

      {/* Hierarchy Path Preview */}
      <div className="p-3 bg-indigo-50 dark:bg-indigo-500/10 rounded-lg border border-indigo-200 dark:border-indigo-500/30">
        <h4 className="text-sm font-medium text-indigo-800 dark:text-indigo-300 mb-2 flex items-center gap-2">
          <GitBranch className="w-4 h-4" />
          Hierarchy Path Preview
        </h4>
        <div className="flex items-center gap-1 flex-wrap text-sm">
          <Badge variant="neutral" size="sm">ROOT ({programCurrency})</Badge>
          {configurableLevels.map((level) => {
            const value = dimensionValues[`L${level.levelNumber}`];
            if (!value) return null;
            return (
              <React.Fragment key={level.levelNumber}>
                <ChevronRight className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
                <Badge variant="info" size="sm">
                  {value}
                </Badge>
              </React.Fragment>
            );
          })}
          <ChevronRight className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
          <Badge variant="success" size="sm">New VA</Badge>
        </div>
      </div>

      {error && (
        <p className="text-sm text-error-600 dark:text-error-300 flex items-center gap-1">
          <AlertCircle className="w-4 h-4" />
          {error}
        </p>
      )}
    </div>
  );
};

// ============================================================================
// STEP 4: VA DETAILS
// ============================================================================

interface VaDetailsStepProps {
  vaName: string;
  onNameChange: (name: string) => void;
  accountPurpose: string;
  onPurposeChange: (purpose: string) => void;
  externalReference: string;
  onExternalRefChange: (ref: string) => void;
  currency: string;
  errors: Record<string, string>;
}

const VaDetailsStep: React.FC<VaDetailsStepProps> = ({
  vaName,
  onNameChange,
  accountPurpose,
  onPurposeChange,
  externalReference,
  onExternalRefChange,
  currency,
  errors,
}) => {
  return (
    <div className="space-y-6">
      {/* Currency Info Banner */}
      <div className="bg-primary-50 border border-primary-200 rounded-xl p-4 dark:bg-primary-800/40 dark:border-primary-700">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-lg bg-primary-100 flex items-center justify-center dark:bg-primary-700">
            <CurrencyIcon currency={currency} className="w-5 h-5 text-primary-600 dark:text-primary-200" />
          </div>
          <div>
            <p className="text-sm text-primary-700 dark:text-neutral-200">Creating Transaction VA</p>
            <p className="font-medium text-primary-900 dark:text-neutral-50">Currency: {currency}</p>
          </div>
        </div>
      </div>

      {/* VA Name */}
      <div>
        <label className="field-label block mb-1">
          Account Name <span className="text-error-500">*</span>
        </label>
        <Input
          value={vaName}
          onChange={(e) => onNameChange(e.target.value)}
          placeholder="Enter account name (e.g., Operating Account - Sales)"
          maxLength={100}
        />
        {errors.vaName && (
          <p className="text-sm text-error-500 mt-1">{errors.vaName}</p>
        )}
      </div>

      {/* Account Purpose */}
      <div>
        <label className="field-label block mb-1">
          Account Purpose
        </label>
        <select
          value={accountPurpose}
          onChange={(e) => onPurposeChange(e.target.value)}
          className="w-full px-3 py-2 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm"
        >
          <option value="OPERATING">Operating (General Purpose)</option>
          <option value="COLLECTIONS">Collections (Receivables)</option>
          <option value="PAYABLES">Payables (Disbursements)</option>
          <option value="PAYROLL">Payroll</option>
          <option value="TAXES">Taxes</option>
          <option value="ESCROW">Escrow</option>
          <option value="TREASURY">Treasury</option>
          <option value="INTERCOMPANY">Intercompany</option>
        </select>
      </div>

      {/* External Reference */}
      <div>
        <label className="field-label block mb-1">
          External Reference <span className="text-neutral-400 dark:text-neutral-500">(Optional)</span>
        </label>
        <Input
          value={externalReference}
          onChange={(e) => onExternalRefChange(e.target.value)}
          placeholder="e.g., ERP-12345 or SAP-ACC-001"
          maxLength={100}
        />
        <p className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500 mt-1">
          Reference ID from your ERP or accounting system
        </p>
      </div>
    </div>
  );
};

// ============================================================================
// MAIN COMPONENT
// ============================================================================

export const VaCreateModal: React.FC<VaCreateModalProps> = ({
  isOpen,
  onClose,
  onSuccess,
  corporates,
  programs = [],
  preSelectedCorporateId,
  preSelectedProgramId,
}) => {
  // Step management
  const [currentStep, setCurrentStep] = useState(1);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});

  // Selection state
  const [selectedCorporateId, setSelectedCorporateId] = useState(preSelectedCorporateId || '');
  const [selectedProgramId, setSelectedProgramId] = useState(preSelectedProgramId || '');

  // Loaded data
  const [filteredPrograms, setFilteredPrograms] = useState<Program[]>([]);
  const [levelConfigs, setLevelConfigs] = useState<HierarchyLevelConfig[]>([]);

  // Hierarchy dimension values: { "L1": "AED", "L2": "NORTH", ... }
  const [dimensionValues, setDimensionValues] = useState<Record<string, string>>({});

  // VA Details
  const [vaName, setVaName] = useState('');
  const [accountPurpose, setAccountPurpose] = useState('OPERATING');
  const [externalReference, setExternalReference] = useState('');

  // Get selected program details - ensure arrays before calling find
  const selectedProgram = useMemo(() => {
    const programsArray = Array.isArray(programs) ? programs : [];
    const filteredArray = Array.isArray(filteredPrograms) ? filteredPrograms : [];
    return programsArray.find(p => p.id === selectedProgramId) || filteredArray.find(p => p.id === selectedProgramId);
  }, [programs, filteredPrograms, selectedProgramId]);

  // Reset on open
  useEffect(() => {
    if (isOpen) {
      setCurrentStep(1);
      setSelectedCorporateId(preSelectedCorporateId || '');
      setSelectedProgramId(preSelectedProgramId || '');
      setDimensionValues({});
      setVaName('');
      setAccountPurpose('OPERATING');
      setExternalReference('');
      setErrors({});
      setLevelConfigs([]);
    }
  }, [isOpen, preSelectedCorporateId, preSelectedProgramId]);

  // Load programs when corporate changes
  useEffect(() => {
    if (selectedCorporateId) {
      setLoading(true);
      fetchApi<Program[]>(`/programs?corporateId=${selectedCorporateId}`)
        .then(data => {
          // Ensure data is always an array
          const programsData = Array.isArray(data) ? data : [];
          setFilteredPrograms(programsData);
          // Reset program selection if current program doesn't belong to this corporate
          if (selectedProgramId) {
            const programBelongsToCorporate = programsData.some(p => p.id === selectedProgramId);
            if (!programBelongsToCorporate) {
              setSelectedProgramId('');
            }
          }
        })
        .finally(() => setLoading(false));
    } else {
      setFilteredPrograms([]);
      setSelectedProgramId('');
    }
  }, [selectedCorporateId]);

  // Load hierarchy level configs when program changes
  useEffect(() => {
    if (selectedProgramId) {
      setLoading(true);
      fetchApi<HierarchyLevelConfig[]>(`/programs/${selectedProgramId}/hierarchy/config`)
        .then(data => {
          const configs = data || [];
          setLevelConfigs(configs);

          // Initialize dimension values with defaults (e.g., L1 = program currency)
          const initialDimensions: Record<string, string> = {};
          const programCurrency = selectedProgram?.currencyCode || 'AED';

          configs.forEach(level => {
            if (level.levelNumber === 1 && level.dimensionType === 'CURRENCY') {
              initialDimensions[`L${level.levelNumber}`] = programCurrency;
            }
          });

          setDimensionValues(initialDimensions);
        })
        .finally(() => setLoading(false));
    } else {
      setLevelConfigs([]);
      setDimensionValues({});
    }
  }, [selectedProgramId, selectedProgram?.currencyCode]);

  // Available programs (filtered or all) - ensure arrays
  const availablePrograms = useMemo(() => {
    const programsArray = Array.isArray(programs) ? programs : [];
    const filteredArray = Array.isArray(filteredPrograms) ? filteredPrograms : [];

    if (selectedCorporateId && filteredArray.length > 0) {
      return filteredArray;
    }
    return programsArray.filter(p => !selectedCorporateId || p.corporateId === selectedCorporateId);
  }, [programs, filteredPrograms, selectedCorporateId]);

  // Steps configuration
  const steps = [
    { id: 1, title: 'Corporate', icon: Building2 },
    { id: 2, title: 'Program', icon: Layers },
    { id: 3, title: 'Hierarchy', icon: GitBranch },
    { id: 4, title: 'VA Details', icon: CreditCard },
  ];

  // Validation for each step
  const canGoNext = (): boolean => {
    switch (currentStep) {
      case 1:
        return !!selectedCorporateId;
      case 2:
        return !!selectedProgramId;
      case 3:
        // Check required dimension values
        const requiredLevels = levelConfigs.filter(l => l.isRequired && l.levelNumber >= 1 && l.levelNumber <= 6);
        return requiredLevels.every(l => !!dimensionValues[`L${l.levelNumber}`]);
      case 4:
        return !!vaName.trim();
      default:
        return false;
    }
  };

  const handleNext = () => {
    if (currentStep < 4 && canGoNext()) {
      setCurrentStep(prev => prev + 1);
    }
  };

  const handleBack = () => {
    if (currentStep > 1) {
      setCurrentStep(prev => prev - 1);
    }
  };

  const handleDimensionChange = (levelNumber: number, value: string) => {
    setDimensionValues(prev => ({
      ...prev,
      [`L${levelNumber}`]: value,
    }));
  };

  // Submit
  const handleSubmit = async () => {
    // Validate
    const newErrors: Record<string, string> = {};
    if (!vaName.trim()) newErrors.vaName = 'Account name is required';
    if (!selectedCorporateId) newErrors.corporate = 'Corporate is required';
    if (!selectedProgramId) newErrors.program = 'Program is required';

    if (Object.keys(newErrors).length > 0) {
      setErrors(newErrors);
      return;
    }

    setSubmitting(true);
    try {
      // Build the request with hierarchy dimensions
      // Uses the /with-dimensions endpoint that auto-creates intermediate aggregation nodes
      const request = {
        programId: selectedProgramId,
        vaName: vaName.trim(),
        currencyCode: selectedProgram?.currencyCode || 'AED',
        hierarchyDimensions: dimensionValues,
        accountCategory: 'TRANSACTION',
        accountPurpose,
        externalReference: externalReference || undefined,
        inheritProgramDefaults: true,
      };

      console.log('[VaCreateModal] Submitting request:', request);

      // Use the with-dimensions endpoint that auto-creates intermediate nodes based on dimensions
      const result = await postApi<VaResponse>('/virtual-accounts/with-dimensions', request);

      if (result.success && result.data) {
        toast.success(`Account ${result.data.vaNumber} created successfully`);
        onSuccess(result.data);
        onClose();
      } else {
        toast.error(result.message || 'Failed to create account');
        setErrors({ submit: result.message || 'Failed to create account' });
      }
    } catch (error: any) {
      console.error('[VaCreateModal] Error:', error);
      toast.error(error.message || 'Failed to create account');
      setErrors({ submit: error.message || 'Failed to create account' });
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      size="lg"
      title="Create Virtual Account"
      subtitle="Create a new VA within the program hierarchy"
    >
      <div className="flex flex-col h-full min-h-[500px]">
        {/* Stepper */}
        <div className="flex items-center justify-between mb-6 px-2">
          {steps.map((step, index) => (
            <React.Fragment key={step.id}>
              <div
                className={cn(
                  "flex items-center gap-2 cursor-pointer",
                  currentStep >= step.id ? "text-primary-600 dark:text-primary-200" : "text-neutral-400 dark:text-neutral-500"
                )}
                onClick={() => currentStep > step.id && setCurrentStep(step.id)}
              >
                <div className={cn(
                  "w-8 h-8 rounded-full flex items-center justify-center text-sm font-medium",
                  currentStep === step.id ? "bg-primary-600 text-white" :
                  currentStep > step.id ? "bg-primary-100 text-primary-600 dark:text-primary-200 dark:bg-primary-700" :
                  "bg-neutral-100 text-neutral-400 dark:text-neutral-500 dark:bg-primary-800"
                )}>
                  {currentStep > step.id ? (
                    <CheckCircle className="w-5 h-5" />
                  ) : (
                    step.id
                  )}
                </div>
                <span className={cn(
                  "text-sm font-medium hidden sm:inline",
                  currentStep >= step.id ? "text-primary-900 dark:text-neutral-50" : "text-neutral-400 dark:text-neutral-500"
                )}>
                  {step.title}
                </span>
              </div>
              {index < steps.length - 1 && (
                <div className={cn(
                  "flex-1 h-0.5 mx-2",
                  currentStep > step.id ? "bg-primary-500" : "bg-neutral-200 dark:bg-primary-800"
                )} />
              )}
            </React.Fragment>
          ))}
        </div>

        {/* Selection Summary */}
        {currentStep > 1 && (
          <div className="flex flex-wrap items-center gap-2 mb-4 p-3 bg-neutral-50 dark:bg-primary-950 rounded-lg text-sm">
            {selectedCorporateId && (
              <Badge variant="neutral" size="sm">
                <Building2 className="w-3 h-3 mr-1" />
                {corporates.find(c => c.id === selectedCorporateId)?.name}
              </Badge>
            )}
            {selectedProgram && currentStep > 2 && (
              <>
                <ChevronRight className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
                <Badge variant="info" size="sm">
                  <Layers className="w-3 h-3 mr-1" />
                  {selectedProgram.programCode} ({selectedProgram.currencyCode})
                </Badge>
              </>
            )}
          </div>
        )}

        {/* Step Content */}
        <div className="flex-1 overflow-y-auto">
          {currentStep === 1 && (
            <CorporateStep
              corporates={corporates}
              selectedId={selectedCorporateId}
              onSelect={setSelectedCorporateId}
              error={errors.corporate}
            />
          )}
          {currentStep === 2 && (
            <ProgramStep
              programs={availablePrograms}
              selectedId={selectedProgramId}
              onSelect={setSelectedProgramId}
              loading={loading}
              error={errors.program}
            />
          )}
          {currentStep === 3 && (
            <HierarchyDimensionsStep
              levelConfigs={levelConfigs}
              dimensionValues={dimensionValues}
              onDimensionChange={handleDimensionChange}
              loading={loading}
              programCurrency={selectedProgram?.currencyCode || 'AED'}
              error={errors.hierarchy}
            />
          )}
          {currentStep === 4 && (
            <VaDetailsStep
              vaName={vaName}
              onNameChange={setVaName}
              accountPurpose={accountPurpose}
              onPurposeChange={setAccountPurpose}
              externalReference={externalReference}
              onExternalRefChange={setExternalReference}
              currency={selectedProgram?.currencyCode || 'AED'}
              errors={errors}
            />
          )}
        </div>

        {/* Error Display */}
        {errors.submit && (
          <div className="mt-4 p-3 bg-error-50 dark:bg-error-500/10 border border-error-200 dark:border-error-500/30 rounded-lg flex items-start gap-2">
            <AlertCircle className="w-5 h-5 text-error-600 dark:text-error-300 flex-shrink-0" />
            <p className="text-sm text-error-700 dark:text-error-300">{errors.submit}</p>
          </div>
        )}

        {/* Footer */}
        <div className="flex items-center justify-between pt-4 mt-4 border-t border-neutral-200 dark:border-primary-800">
          <div className="text-sm text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">
            Step {currentStep} of {steps.length}
          </div>
          <div className="flex gap-2">
            {currentStep > 1 && (
              <Button variant="outline" onClick={handleBack}>
                Back
              </Button>
            )}
            <Button variant="ghost" onClick={onClose} disabled={submitting}>
              Cancel
            </Button>
            {currentStep < 4 ? (
              <Button onClick={handleNext} disabled={!canGoNext()}>
                Next
                <ChevronRight className="w-4 h-4 ml-1" />
              </Button>
            ) : (
              <Button onClick={handleSubmit} disabled={submitting || !canGoNext()}>
                {submitting ? (
                  <>
                    <Loader2 className="w-4 h-4 mr-1 animate-spin" />
                    Creating...
                  </>
                ) : (
                  <>
                    <CreditCard className="w-4 h-4 mr-1" />
                    Create Account
                  </>
                )}
              </Button>
            )}
          </div>
        </div>
      </div>
    </Modal>
  );
};

export default VaCreateModal;
