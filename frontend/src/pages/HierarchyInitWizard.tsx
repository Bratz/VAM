// ============================================================================
// HIERARCHY INITIALIZATION WIZARD COMPONENT
// ============================================================================
// Multi-step wizard for initializing a corporate hierarchy
// Steps:
// 1. Select Corporate Entity
// 2. Configure ROOT Virtual Account
// 3. Exception Account Setup
// 4. Review & Initialize
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
  Globe,
  AlertCircle,
  Wallet,
  Coins,
} from 'lucide-react';
import { cn } from '../utils';
import { Modal } from '../components/ui/enhanced';
import {
  hierarchyOperationsApi,
  CorporateSummary,
  HierarchyInitRequest,
  HierarchyInitResult,
} from '../services/hierarchyOperationsApi';

// ============================================================================
// TYPES
// ============================================================================

interface HierarchyInitWizardProps {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: (result: HierarchyInitResult) => void;
  preSelectedCorporateId?: string;
}

// ============================================================================
// CURRENCY OPTIONS
// ============================================================================

const CURRENCY_OPTIONS = [
  { code: 'AED', name: 'United Arab Emirates Dirham' },
  { code: 'USD', name: 'United States Dollar' },
  { code: 'EUR', name: 'Euro' },
  { code: 'GBP', name: 'British Pound' },
  { code: 'SAR', name: 'Saudi Riyal' },
  { code: 'KWD', name: 'Kuwaiti Dinar' },
  { code: 'QAR', name: 'Qatari Riyal' },
  { code: 'BHD', name: 'Bahraini Dinar' },
  { code: 'OMR', name: 'Omani Rial' },
  { code: 'SGD', name: 'Singapore Dollar' },
  { code: 'CHF', name: 'Swiss Franc' },
  { code: 'JPY', name: 'Japanese Yen' },
  { code: 'CNY', name: 'Chinese Yuan' },
  { code: 'INR', name: 'Indian Rupee' },
];

// ============================================================================
// HELPER COMPONENTS
// ============================================================================

const Badge: React.FC<{
  variant?: 'default' | 'success' | 'warning' | 'error' | 'info';
  children: React.ReactNode;
  className?: string;
}> = ({ variant = 'default', children, className }) => {
  const variants = {
    default: 'bg-neutral-100 text-neutral-700 dark:bg-primary-800 dark:text-neutral-200',
    success: 'bg-success-100 text-success-700 dark:bg-success-500/20 dark:text-success-300',
    warning: 'bg-warning-100 text-warning-700 dark:bg-warning-500/20 dark:text-warning-300',
    error: 'bg-error-100 text-error-700 dark:bg-error-500/20 dark:text-error-300',
    info: 'bg-info-100 text-info-700 dark:bg-info-500/20 dark:text-info-300',
  };

  return (
    <span className={cn('px-2 py-0.5 text-xs font-medium rounded-full', variants[variant], className)}>
      {children}
    </span>
  );
};

const StepIndicator: React.FC<{
  currentStep: number;
  totalSteps: number;
  steps: { label: string }[];
}> = ({ currentStep, totalSteps, steps }) => (
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
                ? 'bg-primary-600 text-white'
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
        {i < totalSteps - 1 && (
          <div className={cn('w-12 h-0.5', i + 1 < currentStep ? 'bg-success-500' : 'bg-neutral-200 dark:bg-primary-800')} />
        )}
      </React.Fragment>
    ))}
  </div>
);

const InfoBox: React.FC<{
  title: string;
  children: React.ReactNode;
  variant?: 'info' | 'warning';
}> = ({ title, children, variant = 'info' }) => (
  <div
    className={cn(
      'p-4 rounded-lg border',
      variant === 'info' ? 'bg-info-50 dark:bg-info-500/10 border-info-200 dark:border-info-500/30' : 'bg-warning-50 dark:bg-warning-500/10 border-warning-200 dark:border-warning-500/30'
    )}
  >
    <div className="flex items-start gap-3">
      {variant === 'info' ? (
        <Info className="w-5 h-5 text-info-600 dark:text-info-300 mt-0.5 flex-shrink-0" />
      ) : (
        <AlertTriangle className="w-5 h-5 text-warning-600 dark:text-warning-300 mt-0.5 flex-shrink-0" />
      )}
      <div>
        <p className={cn('text-sm font-medium', variant === 'info' ? 'text-info-800 dark:text-info-300' : 'text-warning-800 dark:text-warning-300')}>
          {title}
        </p>
        <div className={cn('text-sm mt-1', variant === 'info' ? 'text-info-600 dark:text-info-300' : 'text-warning-600 dark:text-warning-300')}>
          {children}
        </div>
      </div>
    </div>
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
}

const CorporateCard: React.FC<CorporateCardProps> = ({
  corporate,
  selected,
  onSelect,
  disabled,
}) => {
  const isInitialized = corporate.hierarchyStatus === 'INITIALIZED';

  return (
    <button
      type="button"
      onClick={onSelect}
      disabled={disabled || isInitialized}
      className={cn(
        'w-full text-left p-4 rounded-xl border-2 transition-all',
        selected
          ? 'border-primary-500 bg-primary-50 dark:bg-primary-800/40 ring-2 ring-primary-200'
          : isInitialized
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
              <Badge variant="success">Hierarchy Initialized</Badge>
            ) : (
              <Badge variant="warning">No hierarchy configured</Badge>
            )}
          </div>

          <p className="text-sm text-neutral-500 dark:text-neutral-400 mt-1">
            Status: {corporate.status}
          </p>

          {isInitialized && (
            <p className="mt-2 text-xs text-neutral-500 dark:text-neutral-400 flex items-center gap-1">
              <Check className="w-3 h-3 text-success-500" />
              Already has a configured hierarchy
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
  rootName: string;
  baseCurrency: string;
  exceptionCurrencies: string[];
}

const HierarchyPreview: React.FC<HierarchyPreviewProps> = ({
  rootName,
  baseCurrency,
  exceptionCurrencies,
}) => (
  <div className="p-4 bg-neutral-50 dark:bg-primary-950 rounded-lg border border-neutral-200 dark:border-primary-800 font-mono text-sm">
    <div className="flex items-center gap-2">
      <Globe className="w-4 h-4 text-primary-700 dark:text-neutral-200" />
      <span className="text-primary-900 dark:text-neutral-50 font-medium">{rootName || '[ROOT Name]'} (ROOT)</span>
    </div>
    <div className="ml-4 mt-2 border-l-2 border-dashed border-neutral-300 dark:border-primary-700 pl-4 py-2 space-y-1">
      <div className="text-neutral-500 dark:text-neutral-400">│ Currency: {baseCurrency}</div>
      {exceptionCurrencies.length > 0 && (
        <>
          <div className="text-neutral-400 dark:text-neutral-500 mt-2">│</div>
          {exceptionCurrencies.map((currency, i) => (
            <div key={currency} className="flex items-center gap-2 text-warning-700 dark:text-warning-300">
              {i === exceptionCurrencies.length - 1 ? '└─' : '├─'}
              <AlertCircle className="w-3 h-3" />
              <span>{currency} Exception Account</span>
            </div>
          ))}
        </>
      )}
    </div>
  </div>
);

// ============================================================================
// MAIN COMPONENT
// ============================================================================

export const HierarchyInitWizard: React.FC<HierarchyInitWizardProps> = ({
  isOpen,
  onClose,
  onSuccess,
  preSelectedCorporateId,
}) => {
  // State
  const [step, setStep] = useState(1);
  const [corporates, setCorporates] = useState<CorporateSummary[]>([]);

  // Form state
  const [selectedCorporate, setSelectedCorporate] = useState<CorporateSummary | null>(null);
  const [rootName, setRootName] = useState('');
  const [rootCode, setRootCode] = useState('');
  const [baseCurrency, setBaseCurrency] = useState('AED');
  const [createExceptionVa, setCreateExceptionVa] = useState(true);
  const [additionalExceptionCurrencies, setAdditionalExceptionCurrencies] = useState<string[]>([]);

  // Loading states
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const steps = [
    { label: 'Corporate' },
    { label: 'ROOT Config' },
    { label: 'Exceptions' },
    { label: 'Review' },
  ];

  // Load corporates
  const loadCorporates = useCallback(async () => {
    setLoading(true);
    setError(null);

    try {
      const res = await hierarchyOperationsApi.getCorporates();
      if (res.success && res.data) {
        setCorporates(res.data);

        // Pre-select if provided
        if (preSelectedCorporateId) {
          const preSelected = res.data.find((c) => c.id === preSelectedCorporateId);
          if (preSelected && preSelected.hierarchyStatus !== 'INITIALIZED') {
            setSelectedCorporate(preSelected);
            setBaseCurrency(preSelected.baseCurrency);
            setRootName(`${preSelected.name} - Group Treasury`);
            setRootCode(`${preSelected.code || preSelected.name.substring(0, 4).toUpperCase()}-ROOT`);
            setStep(2);
          }
        }
      }
    } catch (err) {
      setError('Failed to load corporates');
    } finally {
      setLoading(false);
    }
  }, [preSelectedCorporateId]);

  useEffect(() => {
    if (isOpen) {
      loadCorporates();
    } else {
      // Reset state
      setStep(1);
      setSelectedCorporate(null);
      setRootName('');
      setRootCode('');
      setBaseCurrency('AED');
      setCreateExceptionVa(true);
      setAdditionalExceptionCurrencies([]);
      setError(null);
    }
  }, [isOpen, loadCorporates]);

  // Update form when corporate is selected
  useEffect(() => {
    if (selectedCorporate) {
      setBaseCurrency(selectedCorporate.baseCurrency);
      if (!rootName) {
        setRootName(`${selectedCorporate.name} - Group Treasury`);
      }
      if (!rootCode) {
        const code = selectedCorporate.code || selectedCorporate.name.substring(0, 4).toUpperCase();
        setRootCode(`${code}-ROOT`);
      }
    }
  }, [selectedCorporate]);

  // Toggle exception currency
  const toggleExceptionCurrency = (currency: string) => {
    setAdditionalExceptionCurrencies((prev) =>
      prev.includes(currency) ? prev.filter((c) => c !== currency) : [...prev, currency]
    );
  };

  // Get all exception currencies
  const allExceptionCurrencies = createExceptionVa
    ? [baseCurrency, ...additionalExceptionCurrencies]
    : additionalExceptionCurrencies;

  // Submit handler
  const handleSubmit = async () => {
    if (!selectedCorporate) return;

    setSubmitting(true);
    setError(null);

    try {
      const request: HierarchyInitRequest = {
        corporateId: selectedCorporate.id,
        rootName,
        rootCode,
        baseCurrency,
        createExceptionVa,
        additionalExceptionCurrencies: additionalExceptionCurrencies.length > 0 
          ? additionalExceptionCurrencies 
          : undefined,
      };

      const result = await hierarchyOperationsApi.initializeHierarchy(request);

      if (result.success && result.data) {
        onSuccess(result.data);
        onClose();
      } else {
        setError(result.error || 'Failed to initialize hierarchy');
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
        return selectedCorporate !== null;
      case 2:
        return rootName.trim().length > 0 && rootCode.trim().length > 0;
      case 3:
        return true;
      case 4:
        return true;
      default:
        return false;
    }
  };

  // Available currencies for additional exceptions (exclude base currency)
  const availableExceptionCurrencies = CURRENCY_OPTIONS.filter((c) => c.code !== baseCurrency);

  // Corporates available for initialization
  const availableCorporates = corporates.filter((c) => c.hierarchyStatus !== 'INITIALIZED');

  // Phase 10 follow-up (2026-05-13): migrated from hand-rolled wrapper to
  // <Modal size="lg">. Icon medallion + title pass via Modal's title prop.
  // Body wrapped in -mx-6 -my-6 to allow the step indicator + footer to
  // span the full modal width (they have their own border-b / border-t).
  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      size="lg"
      title={
        <div className="flex items-center gap-3">
          <div className="p-2 bg-primary-100 dark:bg-primary-700 rounded-lg">
            <Globe className="w-5 h-5 text-primary-600 dark:text-primary-200" />
          </div>
          <span>Initialize Hierarchy</span>
        </div>
      }
      subtitle="Set up the balance hierarchy for a corporate"
    >
      <div className="-mx-6 -my-6">
        {/* Step Indicator */}
        <StepIndicator currentStep={step} totalSteps={4} steps={steps} />

          {/* Content */}
          <div className="p-6 max-h-[60vh] overflow-y-auto">
            {loading ? (
              <div className="flex items-center justify-center py-12">
                <Loader2 className="w-8 h-8 animate-spin text-primary-600 dark:text-primary-200" />
              </div>
            ) : (
              <>
                {/* Step 1: Corporate Selection */}
                {step === 1 && (
                  <div className="space-y-4">
                    <label className="field-label block">
                      Select Corporate Entity
                    </label>

                    {availableCorporates.length === 0 ? (
                      <div className="text-center py-8 bg-neutral-50 dark:bg-primary-950 rounded-lg">
                        <Building2 className="w-12 h-12 text-neutral-400 dark:text-neutral-500 mx-auto mb-3" />
                        <p className="text-neutral-600 dark:text-neutral-300">All corporates already have hierarchies</p>
                      </div>
                    ) : (
                      <div className="space-y-2 max-h-64 overflow-y-auto">
                        {corporates.map((corporate) => (
                          <CorporateCard
                            key={corporate.id}
                            corporate={corporate}
                            selected={selectedCorporate?.id === corporate.id}
                            onSelect={() => setSelectedCorporate(corporate)}
                          />
                        ))}
                      </div>
                    )}

                    <InfoBox title="What is Hierarchy Initialization?">
                      Initializing a hierarchy creates the ROOT virtual account that serves as the 
                      top-level aggregation node for all balances in your corporate structure. 
                      All other VAs will be created under this ROOT.
                    </InfoBox>
                  </div>
                )}

                {/* Step 2: ROOT Configuration */}
                {step === 2 && (
                  <div className="space-y-6">
                    <div>
                      <label className="field-label block mb-2">
                        Root Name *
                      </label>
                      <input
                        type="text"
                        value={rootName}
                        onChange={(e) => setRootName(e.target.value)}
                        placeholder="e.g., ACME Holdings - Group Treasury"
                        className="w-full px-4 py-2.5 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
                      />
                    </div>

                    <div>
                      <label className="field-label block mb-2">
                        Root Code *
                      </label>
                      <input
                        type="text"
                        value={rootCode}
                        onChange={(e) => setRootCode(e.target.value.toUpperCase())}
                        placeholder="e.g., ACME-ROOT"
                        className="w-full px-4 py-2.5 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm font-mono focus:outline-none focus:ring-2 focus:ring-primary-500"
                      />
                    </div>

                    <div>
                      <label className="field-label block mb-2">
                        Base Currency
                      </label>
                      <select
                        value={baseCurrency}
                        onChange={(e) => setBaseCurrency(e.target.value)}
                        className="w-full px-4 py-2.5 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
                      >
                        {CURRENCY_OPTIONS.map((curr) => (
                          <option key={curr.code} value={curr.code}>
                            {curr.code} - {curr.name}
                          </option>
                        ))}
                      </select>
                    </div>

                    <InfoBox title="What is a ROOT account?">
                      The ROOT is the top-level aggregation node that consolidates all balances 
                      across your corporate. All other VAs will be created under this ROOT. 
                      The base currency determines the reporting currency for consolidated views.
                    </InfoBox>
                  </div>
                )}

                {/* Step 3: Exception Account Setup */}
                {step === 3 && (
                  <div className="space-y-6">
                    <div>
                      <label className="flex items-center gap-3 p-4 bg-warning-50 dark:bg-warning-500/10 rounded-lg border border-warning-200 dark:border-warning-500/30 cursor-pointer">
                        <input
                          type="checkbox"
                          checked={createExceptionVa}
                          onChange={(e) => setCreateExceptionVa(e.target.checked)}
                          className="w-5 h-5 text-warning-600 rounded border-warning-300 dark:border-warning-500/30 focus:ring-warning-500 dark:text-warning-300"
                        />
                        <div>
                          <span className="font-medium text-warning-800 dark:text-warning-300">
                            Create {baseCurrency} Exception Account
                          </span>
                          <p className="text-sm text-warning-600 dark:text-warning-300 mt-0.5">
                            Unmatched transactions will be routed here for manual resolution
                          </p>
                        </div>
                      </label>
                    </div>

                    <div>
                      <label className="field-label block mb-3">
                        Additional Exception Currencies (Optional)
                      </label>
                      <div className="flex flex-wrap gap-2">
                        {availableExceptionCurrencies.slice(0, 8).map((curr) => (
                          <button
                            key={curr.code}
                            type="button"
                            onClick={() => toggleExceptionCurrency(curr.code)}
                            className={cn(
                              'px-3 py-2 rounded-lg text-sm font-medium border transition-colors',
                              additionalExceptionCurrencies.includes(curr.code)
                                ? 'bg-warning-100 dark:bg-warning-500/20 border-warning-300 dark:border-warning-500/30 text-warning-800 dark:text-warning-300'
                                : 'bg-white dark:bg-primary-900 border-neutral-200 dark:border-primary-800 text-neutral-600 dark:text-neutral-300 hover:border-neutral-300 dark:hover:border-primary-700'
                            )}
                          >
                            {additionalExceptionCurrencies.includes(curr.code) && (
                              <Check className="w-3 h-3 inline mr-1" />
                            )}
                            {curr.code}
                          </button>
                        ))}
                      </div>
                    </div>

                    <InfoBox title="About Exception Accounts">
                      Exception accounts collect unmatched payments for manual resolution. 
                      One exception account is created per currency at the ROOT level. 
                      You can add more currencies now or create them later as needed.
                    </InfoBox>

                    {allExceptionCurrencies.length > 0 && (
                      <div className="p-3 bg-warning-50 dark:bg-warning-500/10 rounded-lg">
                        <p className="text-sm font-medium text-warning-800 dark:text-warning-300 mb-2">
                          Exception accounts to be created:
                        </p>
                        <div className="flex flex-wrap gap-2">
                          {allExceptionCurrencies.map((curr) => (
                            <Badge key={curr} variant="warning">
                              <AlertCircle className="w-3 h-3 mr-1 inline" />
                              {curr}
                            </Badge>
                          ))}
                        </div>
                      </div>
                    )}
                  </div>
                )}

                {/* Step 4: Review & Initialize */}
                {step === 4 && (
                  <div className="space-y-6">
                    <div className="p-4 bg-neutral-50 dark:bg-primary-950 rounded-lg">
                      <h4 className="font-semibold text-primary-900 dark:text-neutral-50 mb-3">Review Your Hierarchy Setup</h4>
                      <dl className="space-y-2 text-sm">
                        <div className="flex justify-between">
                          <dt className="text-neutral-500 dark:text-neutral-400">Corporate:</dt>
                          <dd className="font-medium">{selectedCorporate?.name}</dd>
                        </div>
                        <div className="flex justify-between">
                          <dt className="text-neutral-500 dark:text-neutral-400">ROOT Name:</dt>
                          <dd className="font-medium">{rootName}</dd>
                        </div>
                        <div className="flex justify-between">
                          <dt className="text-neutral-500 dark:text-neutral-400">ROOT Code:</dt>
                          <dd className="font-mono font-medium">{rootCode}</dd>
                        </div>
                        <div className="flex justify-between">
                          <dt className="text-neutral-500 dark:text-neutral-400">Base Currency:</dt>
                          <dd>
                            <Badge variant="info">{baseCurrency}</Badge>
                          </dd>
                        </div>
                      </dl>
                    </div>

                    <HierarchyPreview
                      rootName={rootName}
                      baseCurrency={baseCurrency}
                      exceptionCurrencies={allExceptionCurrencies}
                    />

                    <div className="p-4 bg-success-50 dark:bg-success-500/10 rounded-lg border border-success-200 dark:border-success-500/30">
                      <h4 className="font-medium text-success-800 dark:text-success-300 mb-2">What will be created:</h4>
                      <ul className="space-y-1 text-sm text-success-700 dark:text-success-300">
                        <li className="flex items-center gap-2">
                          <Check className="w-4 h-4" />
                          ROOT account will be created
                        </li>
                        {allExceptionCurrencies.length > 0 && (
                          <li className="flex items-center gap-2">
                            <Check className="w-4 h-4" />
                            {allExceptionCurrencies.length} Exception account(s) will be created
                          </li>
                        )}
                        <li className="flex items-center gap-2">
                          <Check className="w-4 h-4" />
                          Hierarchy status will be set to INITIALIZED
                        </li>
                      </ul>
                    </div>

                    <InfoBox title="Important" variant="warning">
                      This action cannot be undone. The ROOT account will be the permanent top of 
                      your hierarchy. Make sure all details are correct before proceeding.
                    </InfoBox>

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
                  disabled={submitting}
                  className={cn(
                    'px-6 py-2 text-sm font-medium rounded-lg flex items-center gap-2',
                    !submitting
                      ? 'bg-primary-600 text-white hover:bg-primary-700'
                      : 'bg-neutral-200 dark:bg-primary-800 text-neutral-400 dark:text-neutral-500 cursor-not-allowed'
                  )}
                >
                  {submitting && <Loader2 className="w-4 h-4 animate-spin" />}
                  Initialize →
                </button>
              )}
            </div>
          </div>
        </div>
    </Modal>
  );
};

export default HierarchyInitWizard;