// ============================================================================
// HIERARCHY INITIALIZATION MODAL COMPONENT - WITH CURRENCY MIRROR SUPPORT
// ============================================================================
// Path: frontend/src/pages/HierarchyInitializationModal.tsx
// Updated: Added Currency Mirror creation support
// ============================================================================

import React, { useState, useEffect } from 'react';
import {
  Globe,
  AlertCircle,
  Check,
  Loader2,
  ChevronRight,
  Wallet,
  AlertTriangle,
  Info,
  X,
  Coins,
} from 'lucide-react';
import { cn } from '../utils';
import { Modal } from '../components/ui/enhanced';
import {
  hierarchyVaApi,
  InitializeHierarchyRequest,
  InitializationResponse,
} from '../services/api';

// ============================================================================
// TYPES
// ============================================================================

interface HierarchyInitializationModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: (response: InitializationResponse) => void;
  programId: string;
  programName: string;
  programCode: string;
  corporateName: string;
  defaultCurrency?: string;
}

// ============================================================================
// CONSTANTS
// ============================================================================

const CURRENCIES = [
  { code: 'AED', name: 'UAE Dirham' },
  { code: 'USD', name: 'US Dollar' },
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

const TEMPLATES = [
  { code: 'IHB_PROGRAM', name: 'In-House Banking', description: 'Currency → Region → State → City → Entity → Account Type → VA' },
  { code: 'COLLECTION_PROGRAM', name: 'Collections', description: 'Currency → Channel → Platform → Segment → Customer → Account Type → VA' },
  { code: 'WALLET_PROGRAM', name: 'Wallet', description: 'Currency → User Type → Region → KYC Tier → Group → Wallet Type → Wallet' },
  { code: 'PAYABLES_PROGRAM', name: 'Payables', description: 'Currency → Payment Type → Entity → Cost Center → Beneficiary Type → Payable' },
  { code: 'ESCROW_PROGRAM', name: 'Escrow', description: 'Currency → Escrow Type → Transaction → Party Role → Escrow Account' },
  { code: '', name: 'Custom (No Template)', description: 'Start with empty hierarchy structure' },
];

const STEPS = [
  { id: 1, title: 'ROOT Configuration', description: 'Set up the root account' },
  { id: 2, title: 'Special Accounts', description: 'Configure mirrors & exceptions' },
  { id: 3, title: 'Review & Initialize', description: 'Confirm and create' },
];

// ============================================================================
// STEP INDICATOR COMPONENT
// ============================================================================

const StepIndicator: React.FC<{ currentStep: number }> = ({ currentStep }) => (
  <div className="flex items-center justify-center gap-2 py-4 border-b border-neutral-200 dark:border-primary-800">
    {STEPS.map((step, index) => (
      <React.Fragment key={step.id}>
        <div className="flex items-center gap-2">
          <div
            className={cn(
              'w-8 h-8 rounded-full flex items-center justify-center text-sm font-medium transition-colors',
              currentStep > step.id
                ? 'bg-success-500 text-white'
                : currentStep === step.id
                ? 'bg-primary-600 text-white'
                : 'bg-neutral-200 dark:bg-primary-800 text-neutral-500 dark:text-neutral-400'
            )}
          >
            {currentStep > step.id ? <Check className="w-4 h-4" /> : step.id}
          </div>
          <div className="hidden sm:block">
            <p
              className={cn(
                'text-sm font-medium',
                currentStep >= step.id ? 'text-primary-900 dark:text-neutral-50' : 'text-neutral-400 dark:text-neutral-500'
              )}
            >
              {step.title}
            </p>
          </div>
        </div>
        {index < STEPS.length - 1 && (
          <ChevronRight className="w-4 h-4 text-neutral-300 dark:text-neutral-600 mx-2" />
        )}
      </React.Fragment>
    ))}
  </div>
);

// ============================================================================
// MAIN COMPONENT
// ============================================================================

export const HierarchyInitializationModal: React.FC<HierarchyInitializationModalProps> = ({
  isOpen,
  onClose,
  onSuccess,
  programId,
  programName,
  programCode,
  corporateName,
  defaultCurrency = 'AED',
}) => {
  // State
  const [step, setStep] = useState(1);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Form state - Step 1
  const [rootName, setRootName] = useState(`${corporateName} Treasury`);
  const [rootCode, setRootCode] = useState('ROOT');
  const [baseCurrency, setBaseCurrency] = useState(defaultCurrency);
  const [templateType, setTemplateType] = useState('IHB_PROGRAM');

  // Form state - Step 2
  const [createExceptionVa, setCreateExceptionVa] = useState(true);
  const [createCurrencyMirror, setCreateCurrencyMirror] = useState(true);
  const [additionalCurrencies, setAdditionalCurrencies] = useState<string[]>([]);

  // Reset form when modal opens
  useEffect(() => {
    if (isOpen) {
      setStep(1);
      setError(null);
      setRootName(`${corporateName} Treasury`);
      setRootCode('ROOT');
      setBaseCurrency(defaultCurrency);
      setTemplateType('IHB_PROGRAM');
      setCreateExceptionVa(true);
      setCreateCurrencyMirror(true);
      setAdditionalCurrencies([]);
    }
  }, [isOpen, corporateName, defaultCurrency]);

  // Toggle additional currency
  const toggleCurrency = (currency: string) => {
    setAdditionalCurrencies((prev) =>
      prev.includes(currency)
        ? prev.filter((c) => c !== currency)
        : [...prev, currency]
    );
  };

  // Handle initialization
    const handleInitialize = async () => {
    setLoading(true);
    setError(null);

    try {
        const request: InitializeHierarchyRequest = {
        rootName,
        rootCode,
        baseCurrency,
        createExceptionVa,
        createCurrencyMirror,
        additionalExceptionCurrencies:
            additionalCurrencies.length > 0 ? additionalCurrencies : undefined,
        templateType: templateType || undefined,
        };

        const result = await hierarchyVaApi.initialize(programId, request);

        // Handle both wrapped (ApiResponse<InitializationResponse>) and 
        // unwrapped (InitializationResponse) response formats
        
        // Case 1: Backend returns unwrapped InitializationResponse directly
        // In this case, result IS the InitializationResponse
        if ('rootNodeId' in result || 'rootVaId' in result) {
        // This is the direct InitializationResponse
        const response = result as unknown as InitializationResponse;
        if (response.success) {
            onSuccess(response);
            onClose();
            return;
        } else {
            setError(response.message || 'Failed to initialize hierarchy');
            return;
        }
        }
        
        // Case 2: Backend returns wrapped ApiResponse<InitializationResponse>
        // In this case, result.data contains the InitializationResponse
        if (result.success && result.data) {
        const response = result.data as InitializationResponse;
        if (response.success) {
            onSuccess(response);
            onClose();
            return;
        } else {
            setError(response.message || 'Failed to initialize hierarchy');
            return;
        }
        }
        
        // Fallback error
        setError(result.message || result.error || 'Failed to initialize hierarchy');
        
    } catch (err: any) {
        console.error('Initialization error:', err);
        setError(err.message || 'An unexpected error occurred');
    } finally {
        setLoading(false);
    }
    };

  // Validation
  const canProceed = () => {
    switch (step) {
      case 1:
        return rootName.trim().length > 0 && rootCode.trim().length > 0 && baseCurrency;
      case 2:
        return true;
      case 3:
        return true;
      default:
        return false;
    }
  };

  // Available currencies for additional options
  const availableCurrencies = CURRENCIES.filter((c) => c.code !== baseCurrency);

  // Count what will be created
  const exceptionVaCount = createExceptionVa ? 1 + additionalCurrencies.length : 0;
  const currencyMirrorCount = createCurrencyMirror ? 1 + additionalCurrencies.length : 0;
  const allCurrencies = [baseCurrency, ...additionalCurrencies];

  // Phase 10 follow-up (2026-05-13): migrated from hand-rolled wrapper to
  // <Modal size="lg">. Icon medallion + title pass via Modal's title prop
  // (ReactNode). Body + footer remain inside children with -mx-6 -my-6 to
  // break out of Modal's default p-6 (the existing header had its own
  // gradient + border-b treatment we want to preserve).
  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      size="lg"
      showCloseButton={false}
      title={
        <div className="flex items-center justify-between w-full pr-12">
          <div className="flex items-center gap-3">
            <div className="p-2 bg-primary-100 dark:bg-primary-700 rounded-lg">
              <Globe className="w-5 h-5 text-primary-600 dark:text-primary-200" />
            </div>
            <div>
              <span className="block">Initialize Hierarchy</span>
              <span className="block text-sm font-normal text-neutral-500 dark:text-neutral-400 mt-0.5">
                {programName} • {corporateName}
              </span>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-2 hover:bg-neutral-100 dark:hover:bg-primary-800 rounded-lg transition-colors"
          >
            <X className="w-5 h-5 text-neutral-500 dark:text-neutral-400" />
          </button>
        </div>
      }
    >
      <div className="-mx-6 -my-6">
        {/* Step Indicator */}
        <StepIndicator currentStep={step} />

          {/* Content */}
          <div className="p-6 max-h-[60vh] overflow-y-auto">
            {/* Step 1: ROOT Configuration */}
            {step === 1 && (
              <div className="space-y-6">
                <div className="p-4 bg-info-50 dark:bg-info-500/10 border border-info-200 dark:border-info-500/30 rounded-lg">
                  <div className="flex items-start gap-2">
                    <Info className="w-5 h-5 text-info-600 dark:text-info-300 mt-0.5" />
                    <div>
                      <p className="text-sm font-medium text-info-800 dark:text-info-300">
                        About ROOT Account
                      </p>
                      <p className="text-sm text-info-700 dark:text-info-300 mt-1">
                        The ROOT account is the top-level aggregation point for your
                        entire treasury hierarchy. All balances will roll up to this
                        account.
                      </p>
                    </div>
                  </div>
                </div>

                {/* ROOT Name */}
                <div>
                  <label className="field-label block mb-1">
                    ROOT Account Name *
                  </label>
                  <input
                    type="text"
                    value={rootName}
                    onChange={(e) => setRootName(e.target.value)}
                    placeholder="e.g., Group Treasury"
                    className="w-full px-3 py-2 border border-neutral-300 dark:border-primary-700 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-primary-500"
                  />
                </div>

                {/* ROOT Code */}
                <div>
                  <label className="field-label block mb-1">
                    ROOT Code *
                  </label>
                  <input
                    type="text"
                    value={rootCode}
                    onChange={(e) => setRootCode(e.target.value.toUpperCase())}
                    placeholder="e.g., ROOT"
                    className="w-full px-3 py-2 border border-neutral-300 dark:border-primary-700 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-primary-500 font-mono"
                  />
                  <p className="text-xs text-neutral-500 dark:text-neutral-400 mt-1">
                    Short identifier used in hierarchy paths
                  </p>
                </div>

                {/* Base Currency */}
                <div>
                  <label className="field-label block mb-1">
                    Base Currency *
                  </label>
                  <select
                    value={baseCurrency}
                    onChange={(e) => setBaseCurrency(e.target.value)}
                    className="w-full px-3 py-2 border border-neutral-300 dark:border-primary-700 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-primary-500"
                  >
                    {CURRENCIES.map((c) => (
                      <option key={c.code} value={c.code}>
                        {c.code} - {c.name}
                      </option>
                    ))}
                  </select>
                  <p className="text-xs text-neutral-500 dark:text-neutral-400 mt-1">
                    Primary currency for balance consolidation
                  </p>
                </div>

                {/* Template Selection */}
                <div>
                  <label className="field-label block mb-2">
                    Hierarchy Template
                  </label>
                  <div className="space-y-2">
                    {TEMPLATES.map((template) => (
                      <label
                        key={template.code}
                        className={cn(
                          'flex items-start gap-3 p-3 border rounded-lg cursor-pointer transition-colors',
                          templateType === template.code
                            ? 'border-primary-500 bg-primary-50 dark:bg-primary-800/40'
                            : 'border-neutral-200 dark:border-primary-800 hover:bg-neutral-50 dark:hover:bg-primary-800/50'
                        )}
                      >
                        <input
                          type="radio"
                          name="template"
                          value={template.code}
                          checked={templateType === template.code}
                          onChange={(e) => setTemplateType(e.target.value)}
                          className="mt-1"
                        />
                        <div>
                          <p className="text-sm font-medium text-neutral-900 dark:text-neutral-50">
                            {template.name}
                          </p>
                          <p className="text-xs text-neutral-500 dark:text-neutral-400 mt-0.5">
                            {template.description}
                          </p>
                        </div>
                      </label>
                    ))}
                  </div>
                </div>
              </div>
            )}

            {/* Step 2: Special Accounts (Exception VAs + Currency Mirrors) */}
            {step === 2 && (
              <div className="space-y-6">
                {/* Currency Mirror Info Box */}
                <div className="p-4 bg-cyan-50 dark:bg-cyan-500/10 border border-cyan-200 dark:border-cyan-500/30 rounded-lg">
                  <div className="flex items-start gap-2">
                    <Coins className="w-5 h-5 text-cyan-600 dark:text-cyan-300 mt-0.5" />
                    <div>
                      <p className="text-sm font-medium text-cyan-800 dark:text-cyan-300">
                        About Currency Mirrors (M-Nodes)
                      </p>
                      <p className="text-sm text-cyan-700 dark:text-cyan-300 mt-1">
                        Currency Mirrors aggregate all balances of the same currency 
                        <strong> without FX conversion</strong>. They appear as siblings 
                        at the ROOT level and help track currency-wise positions accurately.
                        FX conversion only happens at the ROOT aggregation level.
                      </p>
                    </div>
                  </div>
                </div>

                {/* Exception VA Info Box */}
                <div className="p-4 bg-amber-50 dark:bg-amber-500/10 border border-amber-200 dark:border-amber-500/30 rounded-lg">
                  <div className="flex items-start gap-2">
                    <AlertTriangle className="w-5 h-5 text-amber-600 dark:text-amber-300 mt-0.5" />
                    <div>
                      <p className="text-sm font-medium text-amber-800 dark:text-amber-300">
                        About Exception Accounts
                      </p>
                      <p className="text-sm text-amber-700 dark:text-amber-300 mt-1">
                        Exception accounts receive unmatched transactions that cannot
                        be routed to a specific virtual account. These are
                        system-managed and help prevent transaction failures.
                      </p>
                    </div>
                  </div>
                </div>

                {/* Create Currency Mirror Toggle */}
                <label className="flex items-center gap-3 p-4 border border-cyan-200 dark:border-cyan-500/30 rounded-lg cursor-pointer hover:bg-cyan-50/50 dark:hover:bg-cyan-500/10 transition-colors">
                  <input
                    type="checkbox"
                    checked={createCurrencyMirror}
                    onChange={(e) => setCreateCurrencyMirror(e.target.checked)}
                    className="w-5 h-5 rounded border-neutral-300 dark:border-primary-700 text-cyan-600 focus:ring-cyan-500 dark:text-cyan-300"
                  />
                  <div className="flex-1">
                    <div className="flex items-center gap-2">
                      <Coins className="w-4 h-4 text-cyan-600 dark:text-cyan-300" />
                      <p className="text-sm font-medium text-neutral-900 dark:text-neutral-50">
                        Create Currency Mirror for {baseCurrency}
                      </p>
                    </div>
                    <p className="text-xs text-neutral-500 dark:text-neutral-400 mt-0.5">
                      Recommended for accurate multi-currency balance tracking
                    </p>
                  </div>
                </label>

                {/* Create Exception VA Toggle */}
                <label className="flex items-center gap-3 p-4 border border-amber-200 dark:border-amber-500/30 rounded-lg cursor-pointer hover:bg-amber-50/50 dark:hover:bg-amber-500/10 transition-colors">
                  <input
                    type="checkbox"
                    checked={createExceptionVa}
                    onChange={(e) => setCreateExceptionVa(e.target.checked)}
                    className="w-5 h-5 rounded border-neutral-300 dark:border-primary-700 text-amber-600 focus:ring-amber-500 dark:text-amber-300"
                  />
                  <div className="flex-1">
                    <div className="flex items-center gap-2">
                      <AlertTriangle className="w-4 h-4 text-amber-600 dark:text-amber-300" />
                      <p className="text-sm font-medium text-neutral-900 dark:text-neutral-50">
                        Create Exception VA for {baseCurrency}
                      </p>
                    </div>
                    <p className="text-xs text-neutral-500 dark:text-neutral-400 mt-0.5">
                      Recommended for catching unmatched transactions
                    </p>
                  </div>
                </label>

                {/* Additional Currencies */}
                {(createExceptionVa || createCurrencyMirror) && (
                  <div>
                    <label className="field-label block mb-2">
                      Additional Currencies
                    </label>
                    <p className="text-xs text-neutral-500 dark:text-neutral-400 mb-3">
                      Select additional currencies for{' '}
                      {createCurrencyMirror && createExceptionVa
                        ? 'Currency Mirrors and Exception VAs'
                        : createCurrencyMirror
                        ? 'Currency Mirrors'
                        : 'Exception VAs'}
                    </p>
                    <div className="flex flex-wrap gap-2">
                      {availableCurrencies.map((currency) => (
                        <button
                          key={currency.code}
                          type="button"
                          onClick={() => toggleCurrency(currency.code)}
                          className={cn(
                            'px-3 py-1.5 rounded-lg text-sm font-medium transition-colors',
                            additionalCurrencies.includes(currency.code)
                              ? 'bg-primary-600 text-white'
                              : 'bg-neutral-100 dark:bg-primary-800 text-neutral-700 dark:text-neutral-200 hover:bg-neutral-200 dark:hover:bg-primary-800'
                          )}
                        >
                          {currency.code}
                        </button>
                      ))}
                    </div>
                    {additionalCurrencies.length > 0 && (
                      <div className="mt-3 p-3 bg-neutral-50 dark:bg-primary-950 rounded-lg">
                        <p className="text-xs font-medium text-neutral-700 dark:text-neutral-200">
                          Selected: {allCurrencies.join(', ')}
                        </p>
                        <div className="flex gap-4 mt-2 text-xs text-neutral-500 dark:text-neutral-400">
                          {createCurrencyMirror && (
                            <span className="flex items-center gap-1">
                              <Coins className="w-3 h-3 text-cyan-600 dark:text-cyan-300" />
                              {currencyMirrorCount} mirror(s)
                            </span>
                          )}
                          {createExceptionVa && (
                            <span className="flex items-center gap-1">
                              <AlertTriangle className="w-3 h-3 text-amber-600 dark:text-amber-300" />
                              {exceptionVaCount} exception VA(s)
                            </span>
                          )}
                        </div>
                      </div>
                    )}
                  </div>
                )}
              </div>
            )}

            {/* Step 3: Review */}
            {step === 3 && (
              <div className="space-y-6">
                <div className="p-4 bg-neutral-50 dark:bg-primary-950 rounded-lg">
                  <h3 className="text-sm font-semibold text-neutral-900 dark:text-neutral-50 mb-4">
                    Configuration Summary
                  </h3>
                  <div className="space-y-3">
                    <div className="flex justify-between items-center py-2 border-b border-neutral-200 dark:border-primary-800">
                      <span className="text-sm text-neutral-500 dark:text-neutral-400">Program</span>
                      <span className="text-sm font-medium text-neutral-900 dark:text-neutral-50">
                        {programName} ({programCode})
                      </span>
                    </div>
                    <div className="flex justify-between items-center py-2 border-b border-neutral-200 dark:border-primary-800">
                      <span className="text-sm text-neutral-500 dark:text-neutral-400">ROOT Name</span>
                      <span className="text-sm font-medium text-neutral-900 dark:text-neutral-50">
                        {rootName}
                      </span>
                    </div>
                    <div className="flex justify-between items-center py-2 border-b border-neutral-200 dark:border-primary-800">
                      <span className="text-sm text-neutral-500 dark:text-neutral-400">ROOT Code</span>
                      <span className="text-sm font-mono font-medium text-neutral-900 dark:text-neutral-50">
                        {rootCode}
                      </span>
                    </div>
                    <div className="flex justify-between items-center py-2 border-b border-neutral-200 dark:border-primary-800">
                      <span className="text-sm text-neutral-500 dark:text-neutral-400">Base Currency</span>
                      <span className="inline-flex items-center px-2 py-0.5 rounded bg-info-100 dark:bg-info-500/20 text-info-700 dark:text-info-300 text-sm font-medium">
                        {baseCurrency}
                      </span>
                    </div>
                    <div className="flex justify-between items-center py-2 border-b border-neutral-200 dark:border-primary-800">
                      <span className="text-sm text-neutral-500 dark:text-neutral-400">Template</span>
                      <span className="text-sm font-medium text-neutral-900 dark:text-neutral-50">
                        {TEMPLATES.find((t) => t.code === templateType)?.name ||
                          'None'}
                      </span>
                    </div>
                    <div className="flex justify-between items-center py-2 border-b border-neutral-200 dark:border-primary-800">
                      <span className="text-sm text-neutral-500 dark:text-neutral-400 flex items-center gap-1">
                        <Coins className="w-3.5 h-3.5 text-cyan-600 dark:text-cyan-300" />
                        Currency Mirrors
                      </span>
                      <span className="text-sm font-medium text-neutral-900 dark:text-neutral-50">
                        {createCurrencyMirror
                          ? allCurrencies.join(', ')
                          : 'None'}
                      </span>
                    </div>
                    <div className="flex justify-between items-center py-2">
                      <span className="text-sm text-neutral-500 dark:text-neutral-400 flex items-center gap-1">
                        <AlertTriangle className="w-3.5 h-3.5 text-amber-600 dark:text-amber-300" />
                        Exception VAs
                      </span>
                      <span className="text-sm font-medium text-neutral-900 dark:text-neutral-50">
                        {createExceptionVa
                          ? allCurrencies.join(', ')
                          : 'None'}
                      </span>
                    </div>
                  </div>
                </div>

                {/* What will be created */}
                <div className="p-4 bg-success-50 dark:bg-success-500/10 border border-success-200 dark:border-success-500/30 rounded-lg">
                  <div className="flex items-start gap-2">
                    <Check className="w-5 h-5 text-success-600 dark:text-success-300 mt-0.5" />
                    <div>
                      <p className="text-sm font-medium text-success-800 dark:text-success-300">
                        This will create:
                      </p>
                      <ul className="text-sm text-success-700 dark:text-success-300 mt-2 space-y-1">
                        <li>• 1 ROOT hierarchy node at Level 1</li>
                        <li>• 1 ROOT virtual account ({baseCurrency})</li>
                        {templateType && (
                          <li>• 7-level hierarchy structure ({templateType.replace('_PROGRAM', '')})</li>
                        )}
                        {createCurrencyMirror && (
                          <li className="flex items-center gap-1">
                            <span>•</span>
                            <Coins className="w-3.5 h-3.5 text-cyan-600 dark:text-cyan-300" />
                            <span>
                              {currencyMirrorCount} Currency Mirror(s) for{' '}
                              {allCurrencies.join(', ')}
                            </span>
                          </li>
                        )}
                        {createExceptionVa && (
                          <li className="flex items-center gap-1">
                            <span>•</span>
                            <AlertTriangle className="w-3.5 h-3.5 text-amber-600 dark:text-amber-300" />
                            <span>
                              {exceptionVaCount} Exception VA(s) for{' '}
                              {allCurrencies.join(', ')}
                            </span>
                          </li>
                        )}
                      </ul>
                    </div>
                  </div>
                </div>

                {/* Warning */}
                <div className="p-4 bg-amber-50 dark:bg-amber-500/10 border border-amber-200 dark:border-amber-500/30 rounded-lg">
                  <div className="flex items-start gap-2">
                    <AlertCircle className="w-5 h-5 text-amber-600 dark:text-amber-300 mt-0.5" />
                    <p className="text-sm text-amber-700 dark:text-amber-300">
                      This action cannot be undone. Make sure the configuration is
                      correct before proceeding.
                    </p>
                  </div>
                </div>
              </div>
            )}

            {/* Error Display */}
            {error && (
              <div className="mt-4 p-3 bg-error-50 dark:bg-error-500/10 border border-error-200 dark:border-error-500/30 rounded-lg">
                <div className="flex items-start gap-2">
                  <AlertCircle className="w-5 h-5 text-error-600 dark:text-error-300 mt-0.5" />
                  <p className="text-sm text-error-700 dark:text-error-300">{error}</p>
                </div>
              </div>
            )}
          </div>

          {/* Footer */}
          <div className="flex justify-between items-center px-6 py-4 border-t border-neutral-200 dark:border-primary-800 bg-neutral-50 dark:bg-primary-950">
            <button
              onClick={() => (step > 1 ? setStep(step - 1) : onClose())}
              className="px-4 py-2 field-label hover:bg-neutral-200 dark:hover:bg-primary-800 rounded-lg transition-colors"
            >
              {step === 1 ? 'Cancel' : '← Back'}
            </button>

            {step < 3 ? (
              <button
                onClick={() => setStep(step + 1)}
                disabled={!canProceed()}
                className={cn(
                  'px-4 py-2 text-sm font-medium rounded-lg transition-colors',
                  canProceed()
                    ? 'bg-primary-600 text-white hover:bg-primary-700'
                    : 'bg-neutral-200 dark:bg-primary-800 text-neutral-400 dark:text-neutral-500 cursor-not-allowed'
                )}
              >
                Next →
              </button>
            ) : (
              <button
                onClick={handleInitialize}
                disabled={loading}
                className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-success-600 text-white rounded-lg hover:bg-success-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {loading && <Loader2 className="w-4 h-4 animate-spin" />}
                Initialize Hierarchy
              </button>
            )}
          </div>
        </div>
    </Modal>
  );
};

export default HierarchyInitializationModal;