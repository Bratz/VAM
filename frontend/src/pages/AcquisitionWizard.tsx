// ============================================================================
// ACQUISITION WIZARD COMPONENT
// ============================================================================
// Multi-step wizard for corporate acquisitions
// Steps:
// 1. Select Target Corporate
// 2. Configure New Aggregation
// 3. Select Limit Transfer Policy
// 4. Review & Execute
// ============================================================================

import React, { useState, useEffect, useCallback } from 'react';
import {
  X,
  Check,
  ChevronRight,
  AlertTriangle,
  Info,
  Loader2,
  Building2,
  Search,
  Globe,
  Folder,
  Wallet,
  ArrowRight,
} from 'lucide-react';
import { cn } from '../utils';
import { Modal } from '../components/ui/enhanced';
import {
  hierarchyOperationsApi,
  CorporateSummary,
  HierarchyNode,
  MergeLimitPolicy,
  AcquisitionRequest,
  AcquisitionValidationResult,
  MergeOperationResult,
} from '../services/hierarchyOperationsApi';

// ============================================================================
// TYPES
// ============================================================================

interface AcquisitionWizardProps {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: (result: MergeOperationResult) => void;
  acquirerCorporateId: string;
  acquirerCorporateName?: string;
}

interface MergePolicyOption {
  policy: MergeLimitPolicy;
  name: string;
  description: string;
  requiresApproval: boolean;
}

// ============================================================================
// HELPER COMPONENTS
// ============================================================================

const Badge: React.FC<{
  variant?: 'default' | 'success' | 'warning' | 'error' | 'info';
  children: React.ReactNode;
}> = ({ variant = 'default', children }) => {
  const variants = {
    default: 'bg-neutral-100 text-neutral-700 dark:bg-primary-800 dark:text-neutral-200',
    success: 'bg-success-100 text-success-700 dark:bg-success-500/20 dark:text-success-300',
    warning: 'bg-warning-100 text-warning-700 dark:bg-warning-500/20 dark:text-warning-300',
    error: 'bg-error-100 text-error-700 dark:bg-error-500/20 dark:text-error-300',
    info: 'bg-info-100 text-info-700 dark:bg-info-500/20 dark:text-info-300',
  };
  return (
    <span className={cn('px-2 py-0.5 text-xs font-medium rounded-full', variants[variant])}>
      {children}
    </span>
  );
};

/**
 * Wizard step indicator.
 *
 * Tier 5 brand polish (2026-05-13): completed + current steps were
 * previously `bg-success-500` (green check) and `bg-primary-600` (mid
 * navy). Wizards are brand moments — they're the user's first deep
 * impression of "is this product premium or generic?" — so the active
 * state should read as Aperture (navy + gold), not generic success.
 *
 * New treatment:
 *   - Completed: bg-primary-900 (dark navy) with a 1px gold edge ring
 *     to signal "this is done" without using green.
 *   - Current:   bg-primary-900 with a 2px gold accent ring — the gold
 *     halo marks "you are here" the same way the focus ring does on
 *     every other focusable element.
 *   - Pending:   bg-neutral-200 / dark:bg-primary-800 (unchanged).
 *   - Connector: bg-primary-900 once traversed (instead of green).
 */
const StepIndicator: React.FC<{
  currentStep: number;
  steps: { label: string }[];
}> = ({ currentStep, steps }) => (
  <div className="flex items-center justify-center gap-2 py-4 px-6 bg-neutral-50 dark:bg-primary-950 border-b border-neutral-100 dark:border-primary-800/60">
    {steps.map((step, i) => {
      const isCompleted = i + 1 < currentStep;
      const isCurrent = i + 1 === currentStep;
      return (
        <React.Fragment key={i}>
          <div className="flex items-center gap-2">
            <div
              className={cn(
                'w-7 h-7 rounded-full flex items-center justify-center text-xs font-semibold transition-all',
                isCompleted && 'bg-primary-900 text-white ring-1 ring-accent-500/40',
                isCurrent   && 'bg-primary-900 text-white ring-2 ring-accent-500',
                !isCompleted && !isCurrent && 'bg-neutral-200 dark:bg-primary-800 text-neutral-500 dark:text-neutral-400',
              )}
            >
              {isCompleted ? <Check className="w-4 h-4" /> : i + 1}
            </div>
            <span
              className={cn(
                'text-sm font-medium hidden sm:block transition-colors',
                isCurrent ? 'text-primary-900 dark:text-neutral-50' : 'text-neutral-500 dark:text-neutral-400'
              )}
            >
              {step.label}
            </span>
          </div>
          {i < steps.length - 1 && (
            <div className={cn('w-12 h-0.5 transition-colors', isCompleted ? 'bg-primary-900 dark:bg-primary-700' : 'bg-neutral-200 dark:bg-primary-800')} />
          )}
        </React.Fragment>
      );
    })}
  </div>
);

// ============================================================================
// CORPORATE CARD
// ============================================================================

interface CorporateCardProps {
  corporate: CorporateSummary;
  selected: boolean;
  onSelect: () => void;
  type: 'acquirer' | 'target';
}

const CorporateCard: React.FC<CorporateCardProps> = ({ corporate, selected, onSelect, type }) => {
  const isInitialized = corporate.hierarchyStatus === 'INITIALIZED';
  const disabled = type === 'target' && !isInitialized;

  return (
    <button
      type="button"
      onClick={onSelect}
      disabled={disabled}
      className={cn(
        'w-full text-left p-4 rounded-xl border-2 transition-all',
        selected
          ? 'border-primary-500 bg-primary-50 dark:bg-primary-800/40 ring-2 ring-primary-200'
          : disabled
          ? 'border-neutral-100 dark:border-primary-800/60 bg-neutral-50 dark:bg-primary-950 cursor-not-allowed opacity-60'
          : 'border-neutral-200 dark:border-primary-800 hover:border-neutral-300 dark:hover:border-primary-700 bg-white dark:bg-primary-900'
      )}
    >
      <div className="flex items-start gap-4">
        <div
          className={cn(
            'w-5 h-5 rounded-full border-2 flex items-center justify-center flex-shrink-0 mt-1',
            selected ? 'border-primary-600 bg-primary-600' : 'border-neutral-300 dark:border-primary-700'
          )}
        >
          {selected && <Check className="w-3 h-3 text-white" />}
        </div>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <span className="font-semibold text-primary-900 dark:text-neutral-50">{corporate.name}</span>
            <Badge variant="info">{corporate.baseCurrency}</Badge>
            {isInitialized ? (
              <Badge variant="success">Hierarchy Ready</Badge>
            ) : (
              <Badge variant="warning">No Hierarchy</Badge>
            )}
          </div>
          <div className="flex items-center gap-4 mt-2 text-sm text-neutral-500 dark:text-neutral-400">
            <span>{corporate.vaCount} VAs</span>
            <span>{corporate.totalBalance?.toLocaleString() || '0'} {corporate.baseCurrency}</span>
          </div>
          {disabled && (
            <p className="mt-2 text-xs text-error-500">
              Target must have an initialized hierarchy
            </p>
          )}
        </div>
      </div>
    </button>
  );
};

// ============================================================================
// HIERARCHY PREVIEW
// ============================================================================

interface HierarchyPreviewProps {
  acquirerName: string;
  targetName: string;
  newAggregationName: string;
  targetVaCount: number;
}

const HierarchyPreview: React.FC<HierarchyPreviewProps> = ({
  acquirerName,
  targetName,
  newAggregationName,
  targetVaCount,
}) => (
  <div className="p-4 bg-neutral-50 dark:bg-primary-950 rounded-lg border border-neutral-200 dark:border-primary-800">
    <p className="field-label mb-3">Post-Acquisition Structure:</p>
    <div className="font-mono text-sm space-y-1">
      <div className="flex items-center gap-2">
        <Globe className="w-4 h-4 text-primary-700 dark:text-neutral-200" />
        <span className="text-primary-900 dark:text-neutral-50 font-medium">{acquirerName} (ROOT)</span>
      </div>
      <div className="ml-6 flex items-center gap-2 text-success-700 dark:text-success-300">
        <span>└─</span>
        <Folder className="w-4 h-4" />
        <span className="font-medium">{newAggregationName || `Acquired - ${targetName}`}</span>
        <Badge variant="success">New</Badge>
      </div>
      <div className="ml-12 text-neutral-500 dark:text-neutral-400">
        <span>└─ {targetVaCount} VAs migrated</span>
      </div>
    </div>
  </div>
);

// ============================================================================
// LIMIT ANALYSIS PANEL
// ============================================================================

interface LimitAnalysisPanelProps {
  validation: AcquisitionValidationResult | null;
  loading: boolean;
}

const LimitAnalysisPanel: React.FC<LimitAnalysisPanelProps> = ({ validation, loading }) => {
  if (loading) {
    return (
      <div className="p-4 bg-neutral-50 dark:bg-primary-950 rounded-lg flex items-center justify-center">
        <Loader2 className="w-5 h-5 animate-spin text-primary-600 dark:text-primary-200 mr-2" />
        <span className="text-sm text-neutral-500 dark:text-neutral-400">Analyzing limits...</span>
      </div>
    );
  }

  if (!validation?.limitAnalysis) return null;

  const { acquirerGroupLimit, targetGroupLimit, combinedLimit, acquirerCurrency, targetCurrency } =
    validation.limitAnalysis;

  return (
    <div className="p-4 bg-info-50 dark:bg-info-500/10 rounded-lg border border-info-200 dark:border-info-500/30">
      <h4 className="font-medium text-info-900 dark:text-info-300 mb-3">Limit Analysis</h4>
      <div className="space-y-2 text-sm">
        <div className="flex justify-between">
          <span className="text-info-700 dark:text-info-300">Acquirer Group Limit:</span>
          <span className="font-medium">{acquirerGroupLimit.toLocaleString()} {acquirerCurrency}</span>
        </div>
        <div className="flex justify-between">
          <span className="text-info-700 dark:text-info-300">Target Group Limit:</span>
          <span className="font-medium">{targetGroupLimit.toLocaleString()} {targetCurrency}</span>
        </div>
        <div className="border-t border-info-200 dark:border-info-500/30 pt-2 flex justify-between">
          <span className="font-semibold text-info-800 dark:text-info-300">Combined (if policy applies):</span>
          <span className="font-bold text-info-900 dark:text-info-300">{combinedLimit.toLocaleString()} {acquirerCurrency}</span>
        </div>
      </div>
    </div>
  );
};

// ============================================================================
// MAIN COMPONENT
// ============================================================================

export const AcquisitionWizard: React.FC<AcquisitionWizardProps> = ({
  isOpen,
  onClose,
  onSuccess,
  acquirerCorporateId,
  acquirerCorporateName,
}) => {
  // State
  const [step, setStep] = useState(1);
  const [corporates, setCorporates] = useState<CorporateSummary[]>([]);
  const [hierarchyRoot, setHierarchyRoot] = useState<HierarchyNode | null>(null);
  const [policies, setPolicies] = useState<MergePolicyOption[]>([]);

  // Form state
  const [selectedTarget, setSelectedTarget] = useState<CorporateSummary | null>(null);
  const [newAggregationName, setNewAggregationName] = useState('');
  const [newAggregationCode, setNewAggregationCode] = useState('');
  const [placeUnderNodeId, setPlaceUnderNodeId] = useState<string | null>(null);
  const [selectedPolicy, setSelectedPolicy] = useState<MergeLimitPolicy>('COMBINE_LIMITS');
  const [confirmBoardApproval, setConfirmBoardApproval] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');

  // Validation & loading states
  const [validation, setValidation] = useState<AcquisitionValidationResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [validating, setValidating] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Acquirer info
  const [acquirerInfo, setAcquirerInfo] = useState<CorporateSummary | null>(null);

  const steps = [
    { label: 'Select Target' },
    { label: 'Configure' },
    { label: 'Limit Policy' },
    { label: 'Review' },
  ];

  // Load corporates and policies
  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const [corporatesRes, policiesRes, hierarchyRes] = await Promise.all([
        hierarchyOperationsApi.getCorporates(),
        hierarchyOperationsApi.getMergePolicies(),
        hierarchyOperationsApi.getHierarchy(acquirerCorporateId),
      ]);

      if (corporatesRes.success && corporatesRes.data) {
        setCorporates(corporatesRes.data);
        const acquirer = corporatesRes.data.find((c) => c.id === acquirerCorporateId);
        if (acquirer) setAcquirerInfo(acquirer);
      }

      if (policiesRes.success && policiesRes.data) {
        setPolicies(policiesRes.data);
      }

      if (hierarchyRes.success && hierarchyRes.data) {
        setHierarchyRoot(hierarchyRes.data);
      }
    } catch (err) {
      setError('Failed to load data');
    } finally {
      setLoading(false);
    }
  }, [acquirerCorporateId]);

  useEffect(() => {
    if (isOpen) {
      loadData();
    } else {
      // Reset state
      setStep(1);
      setSelectedTarget(null);
      setNewAggregationName('');
      setNewAggregationCode('');
      setPlaceUnderNodeId(null);
      setSelectedPolicy('COMBINE_LIMITS');
      setConfirmBoardApproval(false);
      setSearchQuery('');
      setValidation(null);
      setError(null);
    }
  }, [isOpen, loadData]);

  // Phase 10 Task E (2026-05-13): the previous Tier 5 a11y patch (a hand-
  // rolled escape-key + body-scroll-lock useEffect) is now redundant —
  // the wizard wraps in <Modal> below which provides both for free.

  // Update aggregation name when target selected
  useEffect(() => {
    if (selectedTarget && !newAggregationName) {
      setNewAggregationName(`Acquired - ${selectedTarget.name}`);
    }
  }, [selectedTarget]);

  // Validate when moving to step 3
  useEffect(() => {
    if (step === 3 && selectedTarget && !validation) {
      validateAcquisition();
    }
  }, [step, selectedTarget]);

  const validateAcquisition = async () => {
    if (!selectedTarget) return;

    setValidating(true);
    try {
      const result = await hierarchyOperationsApi.validateAcquisition(
        acquirerCorporateId,
        selectedTarget.id
      );
      if (result.success && result.data) {
        setValidation(result.data);
      }
    } catch (err) {
      console.error('Validation error:', err);
    } finally {
      setValidating(false);
    }
  };

  // Filter corporates (exclude acquirer)
    const filteredCorporates = corporates.filter((c) => {
    if (!c || !c.name) return false;
    return (
        c.id !== acquirerCorporateId &&
        c.name.toLowerCase().includes(searchQuery.toLowerCase())
    );
    });


  // Submit handler
  const handleSubmit = async () => {
    if (!selectedTarget) return;

    setSubmitting(true);
    setError(null);

    try {
      const request: AcquisitionRequest = {
        acquirerCorporateId,
        targetCorporateId: selectedTarget.id,
        newAggregationName,
        newAggregationCode: newAggregationCode || undefined,
        placeUnderNodeId: placeUnderNodeId || undefined,
        limitPolicy: selectedPolicy,
        approvedBy: 'current-user', // TODO: Get from auth context
      };

      const result = await hierarchyOperationsApi.acquireCorporate(request);

      if (result.success && result.data) {
        onSuccess(result.data);
        onClose();
      } else {
        setError(result.error || 'Failed to execute acquisition');
      }
    } catch (err) {
      setError('An unexpected error occurred');
    } finally {
      setSubmitting(false);
    }
  };

  const canProceed = () => {
    switch (step) {
      case 1:
        return selectedTarget !== null;
      case 2:
        return newAggregationName.trim().length > 0;
      case 3:
        return selectedPolicy !== null;
      case 4:
        return confirmBoardApproval;
      default:
        return false;
    }
  };

  // Phase 10 Task E (2026-05-13): migrated from hand-rolled wrapper to
  // <Modal size="lg">. The custom header with the success icon medallion
  // moves into Modal's `title` prop (which accepts ReactNode). The step
  // indicator + content + footer stay inside children. We negate Modal's
  // standard p-6 children padding to keep the step indicator + footer
  // flush with their borders.
  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      size="lg"
      title={
        <div className="flex items-center gap-3">
          <div className="p-2 bg-success-100 dark:bg-success-500/20 rounded-lg">
            <Building2 className="w-5 h-5 text-success-600 dark:text-success-300" />
          </div>
          <span>Acquire Corporate</span>
        </div>
      }
      subtitle="Bring another corporate under your hierarchy"
    >
      <div className="-mx-6 -my-6 flex flex-col">
        {/* Step Indicator */}
        <StepIndicator currentStep={step} steps={steps} />

        {/* Content */}
        <div className="p-6 max-h-[60vh] overflow-y-auto">
            {loading ? (
              <div className="flex items-center justify-center py-12">
                <Loader2 className="w-8 h-8 animate-spin text-primary-600 dark:text-primary-200" />
              </div>
            ) : (
              <>
                {/* Step 1: Select Target */}
                {step === 1 && (
                  <div className="space-y-4">
                    {/* Acquirer Info */}
                    <div className="p-4 bg-primary-50 dark:bg-primary-800/40 rounded-lg border border-primary-200 dark:border-primary-700">
                      <p className="text-sm font-medium text-primary-700 dark:text-neutral-200 mb-2">Acquiring Corporate</p>
                      <div className="flex items-center gap-3">
                        <Building2 className="w-5 h-5 text-primary-600 dark:text-primary-200" />
                        <span className="font-semibold text-primary-900 dark:text-neutral-50">
                          {acquirerInfo?.name || acquirerCorporateName || 'Your Corporate'}
                        </span>
                        {acquirerInfo && (
                          <>
                            <Badge variant="info">{acquirerInfo.baseCurrency}</Badge>
                            <span className="text-sm text-primary-600 dark:text-primary-200">{acquirerInfo.vaCount} VAs</span>
                          </>
                        )}
                      </div>
                    </div>

                    {/* Target Selection */}
                    <div>
                      <label className="field-label block mb-2">
                        Target Corporate to Acquire *
                      </label>

                      <div className="relative mb-3">
                        <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400 dark:text-neutral-500" />
                        <input
                          type="text"
                          value={searchQuery}
                          onChange={(e) => setSearchQuery(e.target.value)}
                          placeholder="Search corporates..."
                          className="w-full pl-10 pr-4 py-2.5 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
                        />
                      </div>

                      <div className="space-y-2 max-h-64 overflow-y-auto">
                        {filteredCorporates.length === 0 ? (
                          <div className="text-center py-8 text-neutral-500 dark:text-neutral-400">
                            No available corporates for acquisition
                          </div>
                        ) : (
                          filteredCorporates.map((corp) => (
                            <CorporateCard
                              key={corp.id}
                              corporate={corp}
                              selected={selectedTarget?.id === corp.id}
                              onSelect={() => setSelectedTarget(corp)}
                              type="target"
                            />
                          ))
                        )}
                      </div>
                    </div>

                    {/* Preview */}
                    {selectedTarget && (
                      <HierarchyPreview
                        acquirerName={acquirerInfo?.name || 'Your Corporate'}
                        targetName={selectedTarget.name}
                        newAggregationName={newAggregationName}
                        targetVaCount={selectedTarget.vaCount}
                      />
                    )}
                  </div>
                )}

                {/* Step 2: Configure New Aggregation */}
                {step === 2 && (
                  <div className="space-y-6">
                    <div className="p-4 bg-info-50 dark:bg-info-500/10 rounded-lg border border-info-200 dark:border-info-500/30">
                      <div className="flex items-start gap-3">
                        <Info className="w-5 h-5 text-info-600 dark:text-info-300 mt-0.5" />
                        <div>
                          <p className="text-sm font-medium text-info-800 dark:text-info-300">Conversion to Aggregation</p>
                          <p className="text-sm text-info-600 dark:text-info-300 mt-1">
                            The target's ROOT will become an AGGREGATION under your ROOT.
                            All VAs will be migrated and re-parented.
                          </p>
                        </div>
                      </div>
                    </div>

                    <div>
                      <label className="field-label block mb-2">
                        New Aggregation Name *
                      </label>
                      <input
                        type="text"
                        value={newAggregationName}
                        onChange={(e) => setNewAggregationName(e.target.value)}
                        placeholder="e.g., Acquired - GlobalTrade"
                        className="w-full px-4 py-2.5 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
                      />
                    </div>

                    <div>
                      <label className="field-label block mb-2">
                        Aggregation Code (Optional)
                      </label>
                      <input
                        type="text"
                        value={newAggregationCode}
                        onChange={(e) => setNewAggregationCode(e.target.value.toUpperCase())}
                        placeholder="e.g., ACQ-GLOBAL-2024"
                        className="w-full px-4 py-2.5 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm font-mono focus:outline-none focus:ring-2 focus:ring-primary-500"
                      />
                    </div>

                    <div>
                      <label className="field-label block mb-2">
                        Place Under (Optional)
                      </label>
                      <select
                        value={placeUnderNodeId || ''}
                        onChange={(e) => setPlaceUnderNodeId(e.target.value || null)}
                        className="w-full px-4 py-2.5 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
                      >
                        <option value="">ROOT (Direct under {acquirerInfo?.name || 'Group Treasury'})</option>
                        {hierarchyRoot?.children
                          ?.filter((c) => c.accountCategory === 'AGGREGATION')
                          .map((node) => (
                            <option key={node.id} value={node.id}>
                              {node.name}
                            </option>
                          ))}
                      </select>
                    </div>

                    {selectedTarget && (
                      <HierarchyPreview
                        acquirerName={acquirerInfo?.name || 'Your Corporate'}
                        targetName={selectedTarget.name}
                        newAggregationName={newAggregationName}
                        targetVaCount={selectedTarget.vaCount}
                      />
                    )}
                  </div>
                )}

                {/* Step 3: Limit Policy */}
                {step === 3 && (
                  <div className="space-y-6">
                    <div>
                      <label className="field-label block mb-3">
                        How should credit limits be handled?
                      </label>

                      <div className="space-y-3">
                        {policies.map((policy) => (
                          <button
                            key={policy.policy}
                            type="button"
                            onClick={() => setSelectedPolicy(policy.policy)}
                            className={cn(
                              'w-full text-left p-4 rounded-xl border-2 transition-all',
                              selectedPolicy === policy.policy
                                ? 'border-primary-500 bg-primary-50 dark:bg-primary-800/40'
                                : 'border-neutral-200 dark:border-primary-800 hover:border-neutral-300 dark:hover:border-primary-700'
                            )}
                          >
                            <div className="flex items-start gap-3">
                              <div
                                className={cn(
                                  'w-5 h-5 rounded-full border-2 flex items-center justify-center mt-0.5',
                                  selectedPolicy === policy.policy
                                    ? 'border-primary-600 bg-primary-600'
                                    : 'border-neutral-300 dark:border-primary-700'
                                )}
                              >
                                {selectedPolicy === policy.policy && (
                                  <Check className="w-3 h-3 text-white" />
                                )}
                              </div>
                              <div className="flex-1">
                                <div className="flex items-center gap-2">
                                  <span className="font-medium text-primary-900 dark:text-neutral-50">{policy.name}</span>
                                  {policy.requiresApproval && (
                                    <Badge variant="warning">Requires Approval</Badge>
                                  )}
                                </div>
                                <p className="text-sm text-neutral-500 dark:text-neutral-400 mt-1">{policy.description}</p>

                                {/* Show limit calculation preview for COMBINE_LIMITS */}
                                {policy.policy === 'COMBINE_LIMITS' && validation?.limitAnalysis && (
                                  <div className="mt-3 p-3 bg-success-50 dark:bg-success-500/10 rounded-lg text-sm">
                                    <div className="flex items-center justify-between">
                                      <span className="text-success-700 dark:text-success-300">Your Limit:</span>
                                      <span className="font-medium">
                                        {validation.limitAnalysis.acquirerGroupLimit.toLocaleString()}{' '}
                                        {validation.limitAnalysis.acquirerCurrency}
                                      </span>
                                    </div>
                                    <div className="flex items-center justify-between mt-1">
                                      <span className="text-success-700 dark:text-success-300">+ Target Limit:</span>
                                      <span className="font-medium">
                                        {validation.limitAnalysis.targetGroupLimit.toLocaleString()}{' '}
                                        {validation.limitAnalysis.targetCurrency}
                                      </span>
                                    </div>
                                    <div className="border-t border-success-200 dark:border-success-500/30 mt-2 pt-2 flex items-center justify-between">
                                      <span className="font-semibold text-success-800 dark:text-success-300">Combined:</span>
                                      <span className="font-bold text-success-800 dark:text-success-300">
                                        {validation.limitAnalysis.combinedLimit.toLocaleString()}{' '}
                                        {validation.limitAnalysis.acquirerCurrency}
                                      </span>
                                    </div>
                                  </div>
                                )}
                              </div>
                            </div>
                          </button>
                        ))}
                      </div>
                    </div>

                    <LimitAnalysisPanel validation={validation} loading={validating} />
                  </div>
                )}

                {/* Step 4: Review & Execute */}
                {step === 4 && (
                  <div className="space-y-6">
                    <div className="p-4 bg-neutral-50 dark:bg-primary-950 rounded-lg">
                      <h4 className="font-semibold text-primary-900 dark:text-neutral-50 mb-4">Acquisition Summary</h4>
                      <dl className="space-y-3 text-sm">
                        <div className="flex justify-between">
                          <dt className="text-neutral-500 dark:text-neutral-400">Acquirer:</dt>
                          <dd className="font-medium">{acquirerInfo?.name}</dd>
                        </div>
                        <div className="flex justify-between">
                          <dt className="text-neutral-500 dark:text-neutral-400">Target:</dt>
                          <dd className="font-medium">{selectedTarget?.name}</dd>
                        </div>
                        <div className="flex justify-between">
                          <dt className="text-neutral-500 dark:text-neutral-400">New Aggregation:</dt>
                          <dd className="font-medium">{newAggregationName}</dd>
                        </div>
                        <div className="flex justify-between">
                          <dt className="text-neutral-500 dark:text-neutral-400">Limit Policy:</dt>
                          <dd className="font-medium">
                            {policies.find((p) => p.policy === selectedPolicy)?.name}
                          </dd>
                        </div>
                      </dl>
                    </div>

                    {validation && (
                      <div className="p-4 bg-success-50 dark:bg-success-500/10 rounded-lg border border-success-200 dark:border-success-500/30">
                        <h4 className="font-medium text-success-800 dark:text-success-300 mb-2">Changes to be made:</h4>
                        <ul className="space-y-1 text-sm text-success-700 dark:text-success-300">
                          <li className="flex items-center gap-2">
                            <Check className="w-4 h-4" />
                            {validation.targetVaCount} VAs will be migrated
                          </li>
                          <li className="flex items-center gap-2">
                            <Check className="w-4 h-4" />
                            Currency mirrors will be created ({validation.currenciesToMigrate.join(', ')})
                          </li>
                          {selectedPolicy === 'COMBINE_LIMITS' && validation.limitAnalysis && (
                            <li className="flex items-center gap-2">
                              <Check className="w-4 h-4" />
                              Group limit increased by{' '}
                              {validation.limitAnalysis.targetGroupLimit.toLocaleString()}{' '}
                              {validation.limitAnalysis.targetCurrency}
                            </li>
                          )}
                          <li className="flex items-center gap-2">
                            <Check className="w-4 h-4" />
                            Target hierarchy preserved under new aggregation
                          </li>
                        </ul>
                      </div>
                    )}

                    <div className="p-4 bg-warning-50 dark:bg-warning-500/10 rounded-lg border border-warning-200 dark:border-warning-500/30">
                      <div className="flex items-start gap-3">
                        <AlertTriangle className="w-5 h-5 text-warning-600 dark:text-warning-300 mt-0.5" />
                        <div>
                          <p className="text-sm font-medium text-warning-800 dark:text-warning-300">This operation will:</p>
                          <ul className="text-sm text-warning-700 dark:text-warning-300 mt-1 space-y-1">
                            <li>• Change corporate_id for all target VAs</li>
                            <li>• Recalculate all balance aggregations</li>
                            <li>• Update hierarchy paths</li>
                          </ul>
                        </div>
                      </div>
                    </div>

                    <label className="flex items-start gap-3 p-4 bg-white dark:bg-primary-900 border border-neutral-200 dark:border-primary-800 rounded-lg cursor-pointer hover:bg-neutral-50 dark:hover:bg-primary-800/50">
                      <input
                        type="checkbox"
                        checked={confirmBoardApproval}
                        onChange={(e) => setConfirmBoardApproval(e.target.checked)}
                        className="mt-1 w-4 h-4 text-primary-600 rounded border-neutral-300 dark:border-primary-700 focus:ring-primary-500 dark:text-primary-200"
                      />
                      <span className="text-sm text-neutral-700 dark:text-neutral-200">
                        I confirm this acquisition has been approved by the board and all necessary
                        due diligence has been completed.
                      </span>
                    </label>

                    {error && (
                      <div className="p-4 bg-error-50 dark:bg-error-500/10 border border-error-200 dark:border-error-500/30 rounded-lg">
                        <div className="flex items-center gap-2 text-error-700 dark:text-error-300">
                          <AlertTriangle className="w-5 h-5" />
                          <span className="text-sm font-medium">{error}</span>
                        </div>
                      </div>
                    )}
                  </div>
                )}
              </>
            )}
          </div>

          {/* Footer */}
          <div className="px-6 py-4 border-t border-neutral-200 dark:border-primary-800 bg-neutral-50 dark:bg-primary-950 flex justify-between">
            <button
              onClick={() => (step === 1 ? onClose() : setStep(step - 1))}
              className="px-4 py-2 field-label hover:bg-neutral-100 dark:hover:bg-primary-800 rounded-lg"
            >
              {step === 1 ? 'Cancel' : '← Back'}
            </button>

            <div className="flex gap-3">
              {step < 4 ? (
                <button
                  onClick={() => setStep(step + 1)}
                  disabled={!canProceed()}
                  className={cn(
                    'px-5 py-2 text-sm font-medium rounded-lg',
                    canProceed()
                      ? 'bg-primary-600 text-white hover:bg-primary-700'
                      : 'bg-neutral-200 dark:bg-primary-800 text-neutral-400 dark:text-neutral-500 cursor-not-allowed'
                  )}
                >
                  Next →
                </button>
              ) : (
                <button
                  onClick={handleSubmit}
                  disabled={!canProceed() || submitting}
                  className={cn(
                    'px-6 py-2 text-sm font-medium rounded-lg flex items-center gap-2',
                    canProceed() && !submitting
                      ? 'bg-primary-600 text-white hover:bg-primary-700'
                      : 'bg-neutral-200 dark:bg-primary-800 text-neutral-400 dark:text-neutral-500 cursor-not-allowed'
                  )}
                >
                  {submitting && <Loader2 className="w-4 h-4 animate-spin" />}
                  Execute Acquisition
                </button>
              )}
            </div>
          </div>
      </div>
    </Modal>
  );
};

export default AcquisitionWizard;