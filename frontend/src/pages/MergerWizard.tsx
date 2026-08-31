// ============================================================================
// MERGER WIZARD COMPONENT
// ============================================================================
// Multi-step wizard for merging two corporates into a new entity
// Steps:
// 1. Select Corporates A & B
// 2. Configure New Corporate
// 3. Select Limit Policy
// 4. Review & Execute
// ============================================================================

import React, { useState, useEffect, useCallback } from 'react';
import {
  X,
  Check,
  AlertTriangle,
  Info,
  Loader2,
  Building2,
  Search,
  Globe,
  Folder,
  GitMerge,
  ArrowRight,
} from 'lucide-react';
import { cn } from '../utils';
import { Modal } from '../components/ui/enhanced';
import {
  hierarchyOperationsApi,
  CorporateSummary,
  MergeLimitPolicy,
  MergerRequest,
  MergeOperationResult,
} from '../services/hierarchyOperationsApi';



// ============================================================================
// TYPES
// ============================================================================

interface MergerWizardProps {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: (result: MergeOperationResult) => void;
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
  variant?: 'default' | 'success' | 'warning' | 'error' | 'info' | 'purple';
  children: React.ReactNode;
}> = ({ variant = 'default', children }) => {
  const variants = {
    default: 'bg-neutral-100 text-neutral-700 dark:bg-primary-800 dark:text-neutral-200',
    success: 'bg-success-100 text-success-700 dark:bg-success-500/20 dark:text-success-300',
    warning: 'bg-warning-100 text-warning-700 dark:bg-warning-500/20 dark:text-warning-300',
    error: 'bg-error-100 text-error-700 dark:bg-error-500/20 dark:text-error-300',
    info: 'bg-info-100 text-info-700 dark:bg-info-500/20 dark:text-info-300',
    purple: 'bg-cat-2/10 text-cat-2 dark:bg-cat-2/15',
  };
  return (
    <span className={cn('px-2 py-0.5 text-xs font-medium rounded-full', variants[variant])}>
      {children}
    </span>
  );
};

const StepIndicator: React.FC<{
  currentStep: number;
  steps: { label: string }[];
}> = ({ currentStep, steps }) => (
  <div className="flex items-center justify-center gap-2 py-4 px-6 bg-neutral-50 dark:bg-primary-950 border-b border-neutral-100 dark:border-primary-800/60">
    {steps.map((step, i) => (
      <React.Fragment key={i}>
        <div className="flex items-center gap-2">
          <div
            className={cn(
              'w-7 h-7 rounded-full flex items-center justify-center text-xs font-semibold',
              i + 1 < currentStep
                ? 'bg-success-500 text-white'
                : i + 1 === currentStep
                ? 'bg-cat-2 text-white'
                : 'bg-neutral-200 dark:bg-primary-800 text-neutral-500 dark:text-neutral-400'
            )}
          >
            {i + 1 < currentStep ? <Check className="w-4 h-4" /> : i + 1}
          </div>
          <span
            className={cn(
              'text-sm font-medium hidden sm:block',
              i + 1 === currentStep ? 'text-primary-900 dark:text-neutral-50' : 'text-neutral-500 dark:text-neutral-400'
            )}
          >
            {step.label}
          </span>
        </div>
        {i < steps.length - 1 && (
          <div className={cn('w-12 h-0.5', i + 1 < currentStep ? 'bg-success-500' : 'bg-neutral-200 dark:bg-primary-800')} />
        )}
      </React.Fragment>
    ))}
  </div>
);

// ============================================================================
// CORPORATE CARD
// ============================================================================

interface CorporateCardProps {
  corporate: CorporateSummary;
  selected: boolean;
  onSelect: () => void;
  disabled?: boolean;
  label?: string;
}

const CorporateCard: React.FC<CorporateCardProps> = ({
  corporate,
  selected,
  onSelect,
  disabled,
  label,
}) => {
  const isInitialized = corporate.hierarchyStatus === 'INITIALIZED';

  return (
    <button
      type="button"
      onClick={onSelect}
      disabled={disabled || !isInitialized}
      className={cn(
        'w-full text-left p-4 rounded-xl border-2 transition-all',
        selected
          ? 'border-cat-2 bg-cat-2-soft dark:bg-cat-2/15 ring-2 ring-cat-2/20'
          : disabled || !isInitialized
          ? 'border-neutral-100 dark:border-primary-800/60 bg-neutral-50 dark:bg-primary-950 cursor-not-allowed opacity-60'
          : 'border-neutral-200 dark:border-primary-800 hover:border-neutral-300 dark:hover:border-primary-700 bg-white dark:bg-primary-900'
      )}
    >
      <div className="flex items-start gap-4">
        <div
          className={cn(
            'w-5 h-5 rounded-full border-2 flex items-center justify-center flex-shrink-0 mt-1',
            selected ? 'border-cat-2 bg-cat-2' : 'border-neutral-300 dark:border-primary-700'
          )}
        >
          {selected && <Check className="w-3 h-3 text-white" />}
        </div>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <span className="font-semibold text-primary-900 dark:text-neutral-50">{corporate.name}</span>
            <Badge variant="info">{corporate.baseCurrency}</Badge>
            {label && <Badge variant="purple">{label}</Badge>}
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
          {!isInitialized && (
            <p className="mt-2 text-xs text-error-500">
              Only corporates with initialized hierarchy can be merged
            </p>
          )}
        </div>
      </div>
    </button>
  );
};

// ============================================================================
// MERGER PREVIEW
// ============================================================================

interface MergerPreviewProps {
  corporateA: CorporateSummary | null;
  corporateB: CorporateSummary | null;
  newCorporateName: string;
}

const MergerPreview: React.FC<MergerPreviewProps> = ({ corporateA, corporateB, newCorporateName }) => (
  <div className="p-4 bg-cat-2-soft dark:bg-cat-2/15 rounded-lg border border-cat-2/20 dark:border-cat-2/30">
    <p className="text-sm font-medium text-cat-2 mb-3">Post-Merger Structure:</p>
    <div className="font-mono text-sm space-y-1">
      <div className="flex items-center gap-2">
        <Globe className="w-4 h-4 text-cat-2" />
        <span className="text-cat-2 font-medium">{newCorporateName || '[New Corporate Name]'} (ROOT)</span>
        <Badge variant="success">New</Badge>
      </div>
      {corporateA && (
        <div className="ml-6 flex items-center gap-2 text-info-700 dark:text-info-300">
          <span>├─</span>
          <Folder className="w-4 h-4" />
          <span>{corporateA.name}</span>
          <span className="text-neutral-500 dark:text-neutral-400">({corporateA.vaCount} VAs)</span>
        </div>
      )}
      {corporateB && (
        <div className="ml-6 flex items-center gap-2 text-success-700 dark:text-success-300">
          <span>└─</span>
          <Folder className="w-4 h-4" />
          <span>{corporateB.name}</span>
          <span className="text-neutral-500 dark:text-neutral-400">({corporateB.vaCount} VAs)</span>
        </div>
      )}
    </div>
  </div>
);

// ============================================================================
// MAIN COMPONENT
// ============================================================================

export const MergerWizard: React.FC<MergerWizardProps> = ({ isOpen, onClose, onSuccess }) => {
  // State
  const [step, setStep] = useState(1);
  const [corporates, setCorporates] = useState<CorporateSummary[]>([]);
  const [policies, setPolicies] = useState<MergePolicyOption[]>([]);

  // Form state
  const [corporateA, setCorporateA] = useState<CorporateSummary | null>(null);
  const [corporateB, setCorporateB] = useState<CorporateSummary | null>(null);
  const [newCorporateName, setNewCorporateName] = useState('');
  const [newCorporateCode, setNewCorporateCode] = useState('');
  const [baseCurrency, setBaseCurrency] = useState('AED');
  const [selectedPolicy, setSelectedPolicy] = useState<MergeLimitPolicy>('COMBINE_LIMITS');
  const [confirmBoardApproval, setConfirmBoardApproval] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');

  // Loading states
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const steps = [
    { label: 'Select Corporates' },
    { label: 'New Corporate' },
    { label: 'Limit Policy' },
    { label: 'Review' },
  ];

  const CURRENCY_OPTIONS = ['AED', 'USD', 'EUR', 'GBP', 'SAR', 'KWD', 'QAR', 'SGD', 'CHF', 'JPY'];

  // Load data
  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const [corporatesRes, policiesRes] = await Promise.all([
        hierarchyOperationsApi.getCorporates(),
        hierarchyOperationsApi.getMergePolicies(),
      ]);

      if (corporatesRes.success && corporatesRes.data) {
        setCorporates(corporatesRes.data);
      }

      if (policiesRes.success && policiesRes.data) {
        setPolicies(policiesRes.data);
      }
    } catch (err) {
      setError('Failed to load data');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (isOpen) {
      loadData();
    } else {
      // Reset state
      setStep(1);
      setCorporateA(null);
      setCorporateB(null);
      setNewCorporateName('');
      setNewCorporateCode('');
      setBaseCurrency('AED');
      setSelectedPolicy('COMBINE_LIMITS');
      setConfirmBoardApproval(false);
      setSearchQuery('');
      setError(null);
    }
  }, [isOpen, loadData]);

  // Update suggested name when corporates selected
  useEffect(() => {
    if (corporateA && corporateB && !newCorporateName) {
      setNewCorporateName(`${corporateA.name} + ${corporateB.name} Merged`);
    }
    if (corporateA && !baseCurrency) {
      setBaseCurrency(corporateA.baseCurrency);
    }
  }, [corporateA, corporateB]);

  // Filter corporates
// Filter corporates with null safety
  const filteredCorporates = corporates.filter((c) => {
    if (!c || !c.name) return false;
    return c.name.toLowerCase().includes(searchQuery.toLowerCase());
  });

  // Available corporates for selection (exclude already selected)
  const availableForA = filteredCorporates.filter((c) => c.id !== corporateB?.id);
  const availableForB = filteredCorporates.filter((c) => c.id !== corporateA?.id);

  // Submit handler
  const handleSubmit = async () => {
    if (!corporateA || !corporateB) return;

    setSubmitting(true);
    setError(null);

    try {
      const request: MergerRequest = {
        corporateAId: corporateA.id,
        corporateBId: corporateB.id,
        newCorporateName,
        newCorporateCode: newCorporateCode || undefined,
        baseCurrency,
        limitPolicy: selectedPolicy,
        approvedBy: 'current-user',
      };

      const result = await hierarchyOperationsApi.mergeCorporates(request);

      if (result.success && result.data) {
        onSuccess(result.data);
        onClose();
      } else {
        setError(result.error || 'Failed to execute merger');
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
        return corporateA !== null && corporateB !== null;
      case 2:
        return newCorporateName.trim().length > 0;
      case 3:
        return selectedPolicy !== null;
      case 4:
        return confirmBoardApproval;
      default:
        return false;
    }
  };

  // Phase 10 follow-up (2026-05-13): hand-rolled wrapper → <Modal size="xl">.
  // max-w-3xl ≈ xl (56rem) per the modal-width tokens.
  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      size="xl"
      title={
        <div className="flex items-center gap-3">
          <div className="p-2 bg-cat-2/10 dark:bg-cat-2/15 rounded-lg">
            <GitMerge className="w-5 h-5 text-cat-2" />
          </div>
          <span>Merge Corporates</span>
        </div>
      }
      subtitle="Combine two corporates into a new entity"
    >
      <div className="-mx-6 -my-6">
        {/* Step Indicator */}
          <StepIndicator currentStep={step} steps={steps} />

          {/* Content */}
          <div className="p-6 max-h-[60vh] overflow-y-auto">
            {loading ? (
              <div className="flex items-center justify-center py-12">
                <Loader2 className="w-8 h-8 animate-spin text-cat-2" />
              </div>
            ) : (
              <>
                {/* Step 1: Select Corporates */}
                {step === 1 && (
                  <div className="space-y-6">
                    <div className="p-4 bg-cat-2-soft dark:bg-cat-2/15 rounded-lg border border-cat-2/20 dark:border-cat-2/30">
                      <div className="flex items-start gap-3">
                        <Info className="w-5 h-5 text-cat-2 mt-0.5" />
                        <div>
                          <p className="text-sm font-medium text-cat-2">About Mergers</p>
                          <p className="text-sm text-cat-2 mt-1">
                            Both source corporates will become aggregations under a newly created
                            corporate. All VAs and limits will be consolidated.
                          </p>
                        </div>
                      </div>
                    </div>

                    <div className="relative mb-4">
                      <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400 dark:text-neutral-500" />
                      <input
                        type="text"
                        value={searchQuery}
                        onChange={(e) => setSearchQuery(e.target.value)}
                        placeholder="Search corporates..."
                        className="w-full pl-10 pr-4 py-2.5 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-cat-2"
                      />
                    </div>

                    <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                      {/* Corporate A */}
                      <div>
                        <label className="field-label block mb-2">
                          Corporate A *
                        </label>
                        <div className="space-y-2 max-h-48 overflow-y-auto">
                          {availableForA.map((corp) => (
                            <CorporateCard
                              key={corp.id}
                              corporate={corp}
                              selected={corporateA?.id === corp.id}
                              onSelect={() => setCorporateA(corp)}
                              label={corporateA?.id === corp.id ? 'Corporate A' : undefined}
                            />
                          ))}
                        </div>
                      </div>

                      {/* Corporate B */}
                      <div>
                        <label className="field-label block mb-2">
                          Corporate B *
                        </label>
                        <div className="space-y-2 max-h-48 overflow-y-auto">
                          {availableForB.map((corp) => (
                            <CorporateCard
                              key={corp.id}
                              corporate={corp}
                              selected={corporateB?.id === corp.id}
                              onSelect={() => setCorporateB(corp)}
                              label={corporateB?.id === corp.id ? 'Corporate B' : undefined}
                            />
                          ))}
                        </div>
                      </div>
                    </div>

                    {corporateA && corporateB && (
                      <MergerPreview
                        corporateA={corporateA}
                        corporateB={corporateB}
                        newCorporateName={newCorporateName}
                      />
                    )}
                  </div>
                )}

                {/* Step 2: Configure New Corporate */}
                {step === 2 && (
                  <div className="space-y-6">
                    <div className="grid grid-cols-2 gap-4 p-4 bg-neutral-50 dark:bg-primary-950 rounded-lg">
                      <div className="text-center p-3 bg-info-50 dark:bg-info-500/10 rounded-lg">
                        <Building2 className="w-6 h-6 text-info-600 dark:text-info-300 mx-auto mb-1" />
                        <p className="font-medium text-info-900 dark:text-info-300">{corporateA?.name}</p>
                        <p className="text-sm text-info-600 dark:text-info-300">{corporateA?.vaCount} VAs</p>
                      </div>
                      <div className="text-center p-3 bg-success-50 dark:bg-success-500/10 rounded-lg">
                        <Building2 className="w-6 h-6 text-success-600 dark:text-success-300 mx-auto mb-1" />
                        <p className="font-medium text-success-900 dark:text-success-300">{corporateB?.name}</p>
                        <p className="text-sm text-success-600 dark:text-success-300">{corporateB?.vaCount} VAs</p>
                      </div>
                    </div>

                    <div>
                      <label className="field-label block mb-2">
                        New Corporate Name *
                      </label>
                      <input
                        type="text"
                        value={newCorporateName}
                        onChange={(e) => setNewCorporateName(e.target.value)}
                        placeholder="e.g., United Holdings Group"
                        className="w-full px-4 py-2.5 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-cat-2"
                      />
                    </div>

                    <div>
                      <label className="field-label block mb-2">
                        Corporate Code (Optional)
                      </label>
                      <input
                        type="text"
                        value={newCorporateCode}
                        onChange={(e) => setNewCorporateCode(e.target.value.toUpperCase())}
                        placeholder="e.g., UHG-2024"
                        className="w-full px-4 py-2.5 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm font-mono focus:outline-none focus:ring-2 focus:ring-cat-2"
                      />
                    </div>

                    <div>
                      <label className="field-label block mb-2">
                        Base Currency
                      </label>
                      <select
                        value={baseCurrency}
                        onChange={(e) => setBaseCurrency(e.target.value)}
                        className="w-full px-4 py-2.5 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-cat-2"
                      >
                        {CURRENCY_OPTIONS.map((curr) => (
                          <option key={curr} value={curr}>
                            {curr}
                          </option>
                        ))}
                      </select>
                    </div>

                    <MergerPreview
                      corporateA={corporateA}
                      corporateB={corporateB}
                      newCorporateName={newCorporateName}
                    />
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
                                ? 'border-cat-2 bg-cat-2-soft dark:bg-cat-2/15'
                                : 'border-neutral-200 dark:border-primary-800 hover:border-neutral-300 dark:hover:border-primary-700'
                            )}
                          >
                            <div className="flex items-start gap-3">
                              <div
                                className={cn(
                                  'w-5 h-5 rounded-full border-2 flex items-center justify-center mt-0.5',
                                  selectedPolicy === policy.policy
                                    ? 'border-cat-2 bg-cat-2'
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
                              </div>
                            </div>
                          </button>
                        ))}
                      </div>
                    </div>

                    {/* Limit Summary */}
                    {corporateA && corporateB && (
                      <div className="p-4 bg-cat-2-soft dark:bg-cat-2/15 rounded-lg border border-cat-2/20 dark:border-cat-2/30">
                        <h4 className="font-medium text-cat-2 mb-3">Combined Limits Preview</h4>
                        <div className="space-y-2 text-sm">
                          <div className="flex justify-between">
                            <span className="text-cat-2">{corporateA.name}:</span>
                            <span className="font-medium">
                              {corporateA.totalBalance?.toLocaleString() || '0'} {corporateA.baseCurrency}
                            </span>
                          </div>
                          <div className="flex justify-between">
                            <span className="text-cat-2">{corporateB.name}:</span>
                            <span className="font-medium">
                              {corporateB.totalBalance?.toLocaleString() || '0'} {corporateB.baseCurrency}
                            </span>
                          </div>
                          <div className="border-t border-cat-2/20 dark:border-cat-2/30 pt-2 flex justify-between">
                            <span className="font-semibold text-cat-2">Total VAs:</span>
                            <span className="font-bold text-cat-2">
                              {corporateA.vaCount + corporateB.vaCount}
                            </span>
                          </div>
                        </div>
                      </div>
                    )}
                  </div>
                )}

                {/* Step 4: Review & Execute */}
                {step === 4 && (
                  <div className="space-y-6">
                    <div className="p-4 bg-neutral-50 dark:bg-primary-950 rounded-lg">
                      <h4 className="font-semibold text-primary-900 dark:text-neutral-50 mb-4">Merger Summary</h4>
                      <dl className="space-y-3 text-sm">
                        <div className="flex justify-between">
                          <dt className="text-neutral-500 dark:text-neutral-400">Corporate A:</dt>
                          <dd className="font-medium">{corporateA?.name}</dd>
                        </div>
                        <div className="flex justify-between">
                          <dt className="text-neutral-500 dark:text-neutral-400">Corporate B:</dt>
                          <dd className="font-medium">{corporateB?.name}</dd>
                        </div>
                        <div className="flex justify-between">
                          <dt className="text-neutral-500 dark:text-neutral-400">New Corporate:</dt>
                          <dd className="font-medium">{newCorporateName}</dd>
                        </div>
                        <div className="flex justify-between">
                          <dt className="text-neutral-500 dark:text-neutral-400">Base Currency:</dt>
                          <dd><Badge variant="info">{baseCurrency}</Badge></dd>
                        </div>
                        <div className="flex justify-between">
                          <dt className="text-neutral-500 dark:text-neutral-400">Limit Policy:</dt>
                          <dd className="font-medium">
                            {policies.find((p) => p.policy === selectedPolicy)?.name}
                          </dd>
                        </div>
                      </dl>
                    </div>

                    <MergerPreview
                      corporateA={corporateA}
                      corporateB={corporateB}
                      newCorporateName={newCorporateName}
                    />

                    <div className="p-4 bg-success-50 dark:bg-success-500/10 rounded-lg border border-success-200 dark:border-success-500/30">
                      <h4 className="font-medium text-success-800 dark:text-success-300 mb-2">Changes to be made:</h4>
                      <ul className="space-y-1 text-sm text-success-700 dark:text-success-300">
                        <li className="flex items-center gap-2">
                          <Check className="w-4 h-4" />
                          New corporate entity created
                        </li>
                        <li className="flex items-center gap-2">
                          <Check className="w-4 h-4" />
                          Both source corporates become aggregations
                        </li>
                        <li className="flex items-center gap-2">
                          <Check className="w-4 h-4" />
                          {(corporateA?.vaCount || 0) + (corporateB?.vaCount || 0)} VAs consolidated
                        </li>
                        <li className="flex items-center gap-2">
                          <Check className="w-4 h-4" />
                          Currency mirrors created as needed
                        </li>
                      </ul>
                    </div>

                    <div className="p-4 bg-warning-50 dark:bg-warning-500/10 rounded-lg border border-warning-200 dark:border-warning-500/30">
                      <div className="flex items-start gap-3">
                        <AlertTriangle className="w-5 h-5 text-warning-600 dark:text-warning-300 mt-0.5" />
                        <div>
                          <p className="text-sm font-medium text-warning-800 dark:text-warning-300">This operation will:</p>
                          <ul className="text-sm text-warning-700 dark:text-warning-300 mt-1 space-y-1">
                            <li>• Create a new corporate entity</li>
                            <li>• Convert both source ROOTs to AGGREGATION</li>
                            <li>• Update all VA corporate_ids</li>
                            <li>• Recalculate all balance aggregations</li>
                          </ul>
                        </div>
                      </div>
                    </div>

                    <label className="flex items-start gap-3 p-4 bg-white dark:bg-primary-900 border border-neutral-200 dark:border-primary-800 rounded-lg cursor-pointer hover:bg-neutral-50 dark:hover:bg-primary-800/50">
                      <input
                        type="checkbox"
                        checked={confirmBoardApproval}
                        onChange={(e) => setConfirmBoardApproval(e.target.checked)}
                        className="mt-1 w-4 h-4 text-cat-2 rounded border-neutral-300 dark:border-primary-700 focus:ring-cat-2"
                      />
                      <span className="text-sm text-neutral-700 dark:text-neutral-200">
                        I confirm this merger has been approved by the boards of both corporates and
                        all necessary due diligence and regulatory approvals have been obtained.
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
                      ? 'bg-cat-2 text-white hover:bg-cat-2/90'
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
                      ? 'bg-cat-2 text-white hover:bg-cat-2/90'
                      : 'bg-neutral-200 dark:bg-primary-800 text-neutral-400 dark:text-neutral-500 cursor-not-allowed'
                  )}
                >
                  {submitting && <Loader2 className="w-4 h-4 animate-spin" />}
                  Execute Merger
                </button>
              )}
            </div>
          </div>
        </div>
    </Modal>
  );
};

export default MergerWizard;