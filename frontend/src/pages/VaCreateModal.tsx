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
  GitBranch, Layers,
} from 'lucide-react';
import { Button, Badge, Input } from '../components/ui';
import { PurposeSelect, CurrencyFieldWithMirrorHint, CreationSideEffectsNote } from '../components/va/createShared';
import { HierarchyTreePicker, PlacementSelection } from '../components/va/HierarchyTreePicker';
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

/** Row shape from GET /hierarchy/nodes?programId= — feeds the tree picker. */
interface HierarchyNodeRow {
  id: string;
  parentId?: string | null;
  levelNumber: number;
  dimensionValue?: string;
  nodeName?: string;
  nodeCode?: string;
  nodeType?: string;
}

/**
 * STEP 3 — Placement. One journey with the hierarchy-tree flow: the tree IS
 * the placement picker. Pick an existing node (submits via parentNodeId,
 * exactly like creating from the Balance Hierarchy tree) or grow a new
 * branch inline (submits via hierarchyDimensions).
 */
interface PlacementStepProps {
  levelConfigs: HierarchyLevelConfig[];
  existingNodes: HierarchyNodeRow[];
  selection: PlacementSelection | null;
  onSelectionChange: (selection: PlacementSelection | null) => void;
  loading: boolean;
  error?: string;
}

const PlacementStep: React.FC<PlacementStepProps> = ({
  levelConfigs, existingNodes, selection, onSelectionChange, loading, error,
}) => {
  if (loading) {
    return (
      <div className="flex items-center justify-center py-12">
        <Loader2 className="w-6 h-6 animate-spin text-primary-500" />
        <span className="ml-2 text-neutral-500 dark:text-neutral-400">Loading hierarchy...</span>
      </div>
    );
  }
  const aggLevels = levelConfigs.filter(l => l.dimensionType !== 'VIRTUAL_ACCOUNT');
  if (aggLevels.length === 0) {
    return (
      <Alert variant="warning" title="No Hierarchy Levels Configured">
        <p className="text-sm">This program doesn't have hierarchy levels configured. Please configure hierarchy levels first.</p>
      </Alert>
    );
  }
  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2 text-sm text-neutral-600 dark:text-neutral-300">
        <GitBranch className="w-4 h-4" />
        <span>Pick where the new VA lives — or grow a new branch in place</span>
      </div>

      <HierarchyTreePicker
        nodes={existingNodes}
        levelConfigs={levelConfigs}
        value={selection}
        onChange={onSelectionChange}
      />

      {selection && (
        <div className="p-3 bg-cat-1-soft dark:bg-cat-1/15 rounded-lg border border-cat-1/20 dark:border-cat-1/30">
          <div className="flex items-center gap-1 flex-wrap text-sm">
            {selection.pathLabels.map((label, i) => (
              <React.Fragment key={`${label}-${i}`}>
                {i > 0 && <ChevronRight className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />}
                <Badge variant={i === selection.pathLabels.length - 1 && selection.dimensionValues ? 'info' : 'neutral'} size="sm">{label}</Badge>
              </React.Fragment>
            ))}
            <ChevronRight className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
            <Badge variant="success" size="sm">New VA</Badge>
          </div>
        </div>
      )}

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
  onCurrencyChange: (currency: string) => void;
  programCurrency?: string;
  entities: Array<{ id: string; entityCode: string; entityName: string; status?: string }>;
  owningEntityId: string;
  onOwningEntityChange: (id: string) => void;
  /** Resolved dimension values, in level order — the placement preview. */
  placement: string[];
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
  onCurrencyChange,
  programCurrency,
  entities,
  owningEntityId,
  onOwningEntityChange,
  placement,
  errors,
}) => {
  return (
    <div className="space-y-6">
      {/* Placement card — the wizard's counterpart to the tree modal's
          "Parent:" card: the final confirmation of WHERE this VA lands. */}
      <div className="p-3 bg-neutral-50 dark:bg-primary-950 border border-neutral-200 dark:border-primary-800 rounded-lg">
        <div className="flex items-center gap-2 text-sm mb-1">
          <GitBranch className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
          <span className="text-neutral-600 dark:text-neutral-300">Will be created under:</span>
        </div>
        <div className="flex items-center gap-1 flex-wrap">
          {placement.map((value, i) => (
            <React.Fragment key={`${value}-${i}`}>
              {i > 0 && <ChevronRight className="w-3.5 h-3.5 text-neutral-400 dark:text-neutral-500" />}
              <Badge variant="neutral" size="sm">{value}</Badge>
            </React.Fragment>
          ))}
          <ChevronRight className="w-3.5 h-3.5 text-neutral-400 dark:text-neutral-500" />
          <Badge variant="success" size="sm">New VA</Badge>
        </div>
        <div className="mt-2">
          <CreationSideEffectsNote />
        </div>
      </div>

      {/* Currency + owning entity — shared vocabulary with the tree modal. */}
      <div className="grid grid-cols-2 gap-4">
        <div>
          <label className="field-label block mb-1">Currency</label>
          <CurrencyFieldWithMirrorHint value={currency} onChange={onCurrencyChange} baseCurrency={programCurrency} />
        </div>
        <div>
          <label className="field-label block mb-1">Owning Entity</label>
          <select
            value={owningEntityId}
            onChange={(e) => onOwningEntityChange(e.target.value)}
            className="w-full px-3 py-2 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm"
          >
            <option value="">Inherit from parent</option>
            {entities.filter(e => !e.status || e.status === 'ACTIVE').map(entity => (
              <option key={entity.id} value={entity.id}>{entity.entityCode} - {entity.entityName}</option>
            ))}
          </select>
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

      {/* Account Purpose — shared canonical list */}
      <div>
        <label className="field-label block mb-1">
          Account Purpose
        </label>
        <PurposeSelect value={accountPurpose} onChange={onPurposeChange} />
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
  const [existingNodes, setExistingNodes] = useState<HierarchyNodeRow[]>([]);

  // Hierarchy dimension values: { "L1": "AED", "L2": "NORTH", ... }
  const [placementSel, setPlacementSel] = useState<PlacementSelection | null>(null);

  // VA Details — currency + owning entity mirror the hierarchy-tree create
  // modal (Path 2): foreign-currency VAs are supported end-to-end (backend
  // auto-creates currency mirrors), so the currency is a choice, not a lock.
  const [vaName, setVaName] = useState('');
  const [accountPurpose, setAccountPurpose] = useState('OPERATING');
  const [externalReference, setExternalReference] = useState('');
  const [currencyCode, setCurrencyCode] = useState('');
  const [owningEntityId, setOwningEntityId] = useState('');
  const [entities, setEntities] = useState<Array<{ id: string; entityCode: string; entityName: string; status?: string }>>([]);

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
      setPlacementSel(null);
      setVaName('');
      setAccountPurpose('OPERATING');
      setExternalReference('');
      setCurrencyCode('');
      setOwningEntityId('');
      setErrors({});
      setLevelConfigs([]);
    }
  }, [isOpen, preSelectedCorporateId, preSelectedProgramId]);

  // Load legal entities for the owning-entity selector (parity with the
  // hierarchy-tree create modal).
  useEffect(() => {
    if (!selectedCorporateId) {
      setEntities([]);
      return;
    }
    fetchApi<Array<{ id: string; entityCode: string; entityName: string; status?: string }>>(
      `/hierarchy/legal-entities/corporate/${selectedCorporateId}`
    )
      .then(data => setEntities(Array.isArray(data) ? data : []))
      .catch(() => setEntities([]));
  }, [selectedCorporateId]);

  // Default the currency to the program's when a program is picked; the user
  // can override it to create a foreign-currency VA.
  useEffect(() => {
    if (selectedProgram?.currencyCode) {
      setCurrencyCode(selectedProgram.currencyCode);
    }
  }, [selectedProgram?.currencyCode]);

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
          setPlacementSel(null); // placement is program-specific
        })
        .finally(() => setLoading(false));
    } else {
      setLevelConfigs([]);
      setPlacementSel(null);
    }
  }, [selectedProgramId, selectedProgram?.currencyCode]);

  // Load the program's existing hierarchy nodes — the cascading LOVs in the
  // dimensions step list real branches so placement is picked, not typed.
  useEffect(() => {
    if (!selectedProgramId) {
      setExistingNodes([]);
      return;
    }
    fetchApi<HierarchyNodeRow[]>(`/hierarchy/nodes?programId=${selectedProgramId}&size=500`)
      .then(data => setExistingNodes(Array.isArray(data) ? data : []))
      .catch(() => setExistingNodes([]));
  }, [selectedProgramId]);

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
        // The picker only emits complete selections (existing node, or a
        // fully-specified new branch).
        return !!placementSel;
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
      // Two submit shapes, one backend write path:
      //  - existing node picked  → parentNodeId (same as the hierarchy-tree flow)
      //  - new branch grown      → hierarchyDimensions (auto-creates the chain)
      const common = {
        programId: selectedProgramId,
        vaName: vaName.trim(),
        currencyCode: currencyCode || selectedProgram?.currencyCode || 'AED',
        accountCategory: 'TRANSACTION',
        accountPurpose,
        owningEntityId: owningEntityId || undefined,
        externalReference: externalReference || undefined,
        inheritProgramDefaults: true,
      };
      const result = placementSel?.parentNodeId
        ? await postApi<VaResponse>('/virtual-accounts', { ...common, parentNodeId: placementSel.parentNodeId, accountType: 'VIRTUAL' })
        : await postApi<VaResponse>('/virtual-accounts/with-dimensions', { ...common, hierarchyDimensions: placementSel?.dimensionValues });

      if (result.success && result.data) {
        toast.success(`Created Transaction VA: ${result.data.vaNumber}`);
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
            <PlacementStep
              levelConfigs={levelConfigs}
              existingNodes={existingNodes}
              selection={placementSel}
              onSelectionChange={setPlacementSel}
              loading={loading}
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
              currency={currencyCode || selectedProgram?.currencyCode || 'AED'}
              onCurrencyChange={setCurrencyCode}
              programCurrency={selectedProgram?.currencyCode}
              entities={entities}
              owningEntityId={owningEntityId}
              onOwningEntityChange={setOwningEntityId}
              placement={placementSel?.pathLabels || []}
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
                    Create Transaction VA
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
