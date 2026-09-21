// Program page building blocks, split out of ProgramsPage.tsx.
import React, { useState, useEffect } from 'react';
import { Percent, Plus, Building2, Wallet, CheckCircle, Clock, ChevronRight, Loader2, X, Hash, GitBranch, Info, Settings } from 'lucide-react';
import { Badge, Button, StatusIconBadge, Checkbox, Input, Select, TextArea } from '../../components/ui';
import { CurrencyPicker } from '../../components/ui/CurrencyPicker';
import { Modal, Alert } from '../../components/ui/enhanced';
import toast from 'react-hot-toast';
import { formatCurrency, cn } from '../../utils';
import { HIERARCHY_TEMPLATES, getRecommendedTemplate, HierarchyLevelConfig } from '../../config/templateHierarchy';

import { fetchApi, BankShadow, CHARGES_API_BASE, ChargeDetail, WalletChargesResponse, WalletChargesRequest, STANDARD_WALLET_BASE_RATES, VibanGenerationStrategy, Program, VibanPool, vibanStrategyConfig } from './shared';

// ============================================================================
// CHARGE CONFIGURATION ROW COMPONENT
// ============================================================================

interface ChargeConfigRowProps {
  charge: ChargeDetail;
  currencyCode: string;
  overridePercent?: number;
  overrideFlat?: number;
  isWaived?: boolean;
  onPercentChange: (value: number | undefined) => void;
  onFlatChange: (value: number | undefined) => void;
  onWaiverChange: (waived: boolean) => void;
}

const ChargeConfigRow: React.FC<ChargeConfigRowProps> = ({
  charge, currencyCode, overridePercent, overrideFlat, isWaived,
  onPercentChange, onFlatChange, onWaiverChange
}) => {
  const hasOverride = overridePercent !== undefined || overrideFlat !== undefined;
  
  return (
    <div className={cn(
      'flex items-center gap-4 p-3 rounded-lg transition-all',
      isWaived ? 'bg-warning-50 border border-warning-200 dark:bg-warning-500/10 dark:border-warning-500/30' : 
      hasOverride ? 'bg-primary-50 border border-primary-200 dark:bg-primary-800/40 dark:border-primary-700' : 'bg-surface-page'
    )}>
      <div className="w-36">
        <p className="body-strong">{charge.chargeName}</p>
      </div>
      
      <div className="w-24 text-center">
        <p className="caption">Base</p>
        <p className="body-sm">
          {charge.percentage > 0 ? `${charge.percentage}%` : ''}
          {charge.percentage > 0 && charge.fixed > 0 ? ' + ' : ''}
          {charge.fixed > 0 || charge.percentage === 0 ? `${currencyCode} ${charge.fixed}` : ''}
        </p>
      </div>
      
      <ChevronRight className="w-4 h-4 text-neutral-400" />
      
      <div className="flex-1 flex items-center gap-2">
        <input type="number" min={0} step="0.01" className={cn('w-16 px-2 py-1 text-body-sm border border-edge-strong rounded-md bg-surface-card text-primary-900 dark:bg-primary-900 dark:border-primary-700 dark:text-neutral-50', isWaived && 'bg-surface-muted')}
          placeholder={String(charge.percentage)} value={overridePercent ?? ''}
          onChange={e => onPercentChange(e.target.value ? parseFloat(e.target.value) : undefined)} disabled={isWaived} />
        <span className="caption">%</span>
        <span className="caption">+</span>
        <span className="caption">{currencyCode}</span>
        <input type="number" min={0} step="0.01" className={cn('w-16 px-2 py-1 text-body-sm border border-edge-strong rounded-md bg-surface-card text-primary-900 dark:bg-primary-900 dark:border-primary-700 dark:text-neutral-50', isWaived && 'bg-surface-muted')}
          placeholder={String(charge.fixed)} value={overrideFlat ?? ''}
          onChange={e => onFlatChange(e.target.value ? parseFloat(e.target.value) : undefined)} disabled={isWaived} />
      </div>
      
      <Checkbox size="sm" label="Waive" checked={isWaived} onChange={onWaiverChange} />
      
      <div className="w-16">
        {isWaived ? <Badge variant="warning" size="sm">Waived</Badge> :
         hasOverride ? <Badge variant="info" size="sm">Override</Badge> :
         <Badge variant="neutral" size="sm">Base</Badge>}
      </div>
    </div>
  );
};

// ============================================================================
// FORM MODAL
// ============================================================================

// ============================================================================
// 5-STEP PROGRAM FORM MODAL
// Step 1: Basic Info (Code, Name, Type, Corporate, Physical Account)
// Step 2: Features (VIBAN, Wallet, Escrow, IHB, Auto-Recon)
// Step 3: VIBAN Pool Config (if VIBAN enabled)
// Step 4: Settlement & Limits
// Step 5: Review & Confirm
// ============================================================================

interface ProgramFormModalProps {
  isOpen: boolean;
  program?: Program | null;
  onClose: () => void;
  /** Returns the saved program, so charges can be saved against a new program's id. */
  onSave: (data: Partial<Program>) => Promise<Program | null | void>;
  defaultCorporateId?: string; // Pre-selected corporate from page picker
  /**
   * Open an existing program on just this step, as a Program-list action.
   * These settings are optional at create time, so they need a way in after it.
   */
  onlyStep?: ProgramConfigStep;
}

export type ProgramConfigStep = 'VIBAN Pool' | 'Wallet Limits' | 'Wallet Fees';

export const ProgramFormModal: React.FC<ProgramFormModalProps> = ({ isOpen, program, onClose, onSave, defaultCorporateId, onlyStep }) => {
  const [loading, setLoading] = useState(false);
  const [step, setStep] = useState(1);
  const [corporates, setCorporates] = useState<Array<{ id: string; legalName: string }>>([]);
  // Shadow accounts of the corporate's home-bank accounts: every one gets a shadow automatically,
  // and a program picks which of them it runs on.
  const [bankShadows, setBankShadows] = useState<BankShadow[]>([]);
  // Only send the selection once the list loaded: on edit an empty list means "detach all".
  const [bankShadowsLoaded, setBankShadowsLoaded] = useState(false);
  // Until the user changes a tick, the selection is what the program has now, worked out at
  // render/save time. Storing it from the fetch raced the form reset: an untouched save could
  // send an empty list, which the backend reads as "untick every account shown".
  const [bankTouched, setBankTouched] = useState(false);
  const [vibanPools, setVibanPools] = useState<VibanPool[]>([]);
  const [loadingCorporates, setLoadingCorporates] = useState(false);
  const [loadingAccounts, setLoadingAccounts] = useState(false);
  const [loadingPools, setLoadingPools] = useState(false);
    // Wallet Charges from ChargeConfiguration API
  const [walletCharges, setWalletCharges] = useState<WalletChargesResponse | null>(null);
  const [chargesLoadFailed, setChargesLoadFailed] = useState(false);
  const [loadingCharges, setLoadingCharges] = useState(false);

  
  const [formData, setFormData] = useState({
    // Step 1: Basic Info
    programCode: '',
    programName: '',
    description: '',
    corporateId: '',
    shadowAccountIds: [] as string[],
    currencyCode: 'AED',
    // Step 2: Core Features
    configureViban: false,
    configureWallet: false,
    configureHierarchy: false,
    // Step 2: Extended Program Features
    // Step 3: Hierarchy Config (when configureHierarchy)
    hierarchyDepth: 7,
    defaultHierarchyTemplate: '' as string,
    // Step 4: VIBAN Pool Config
    defaultVibanPoolId: '',
    vibanGenerationStrategy: 'SEQUENTIAL' as VibanGenerationStrategy,
    vibanPrefix: '',
    vibanBankCode: '',
    // Step 5: Wallet Limits (when configureWallet)
    defaultPerTransactionLimit: undefined as number | undefined,
    defaultDailyLimit: undefined as number | undefined,
    defaultWeeklyLimit: undefined as number | undefined,
    defaultMonthlyLimit: undefined as number | undefined,
    defaultYearlyLimit: undefined as number | undefined,
    defaultMaxBalance: undefined as number | undefined,
    minTopup: undefined as number | undefined,
    maxTopup: undefined as number | undefined,
    defaultDailyTopupLimit: undefined as number | undefined,
    defaultMonthlyTopupLimit: undefined as number | undefined,
    // Wallet KYC
    kycRequired: false,
    autoKyc: false,
    minKycLevel: 0,
    // Wallet Features
    allowTopup: true,
    allowWithdrawal: true,
    allowTransfer: true,
    // Wallet Expiry
    walletExpiryDays: undefined as number | undefined,
    defaultWalletType: 'CONSUMER' as string,
    // Step 6: Settlement & Limits
    vaPrefix: '',
    vaFormat: '',
    maxVirtualAccounts: undefined as number | undefined,
    // Dates
    effectiveFrom: '',
    effectiveTo: '',
  });

    // Charge overrides (user edits) - separate from program formData
  const [chargeOverrides, setChargeOverrides] = useState<{
    topup: { percent?: number; flat?: number; waived: boolean };
    withdrawal: { percent?: number; flat?: number; waived: boolean };
    transfer: { percent?: number; flat?: number; waived: boolean };
    issuance: { flat?: number; waived: boolean };
    monthly: { flat?: number; waived: boolean };
    inactivity: { flat?: number; waived: boolean };
  }>({
    topup: { waived: false },
    withdrawal: { waived: false },
    transfer: { waived: false },
    issuance: { waived: false },
    monthly: { waived: false },
    inactivity: { waived: false },
  });

  // Hierarchy level configurations - customize allowed values per level
  const [hierarchyLevelConfigs, setHierarchyLevelConfigs] = useState<HierarchyLevelConfig[]>([]);
  const [showLevelCustomization, setShowLevelCustomization] = useState(false);
  const [expandedLevelIndex, setExpandedLevelIndex] = useState<number | null>(null);

  const isEdit = !!program;
  const chosenShadowIds = bankTouched || !program
    ? formData.shadowAccountIds
    : bankShadows.filter(s => s.programId === program.id).map(s => s.id);
  // Fetch wallet charges when editing a wallet program or when wallet is enabled
  const needsWalletConfig = formData.configureWallet;
  const needsHierarchyConfig = formData.configureHierarchy;

  // Dynamic step calculation
  const stepLabels = (() => {
    if (onlyStep) return [onlyStep];
    const labels = ['Basic Info', 'Features'];
    if (needsHierarchyConfig) labels.push('Hierarchy');
    if (formData.configureViban) labels.push('VIBAN Pool');
    if (needsWalletConfig) labels.push('Wallet Limits');
    if (needsWalletConfig) labels.push('Wallet Fees');
    labels.push('VA Numbering', 'Review');
    return labels;
  })();

  // Edit is one screen (details + bank accounts); VIBAN, wallet limits and fees have their own actions.
  const totalSteps = onlyStep || isEdit ? 1 : stepLabels.length;
  // Step bodies render on create, or when a single step was asked for.
  const showStepBody = !isEdit || !!onlyStep;

  // Fetch corporates on modal open
  useEffect(() => {
    if (isOpen && !program) {
      setLoadingCorporates(true);
      fetch('/api/v1/corporates')
        .then(res => res.json())
        .then(data => {
          if (data.success) {
            const corps = data.data?.content || data.data || [];
            setCorporates(corps);
          }
        })
        .catch(console.error)
        .finally(() => setLoadingCorporates(false));
    }
  }, [isOpen, program]);

  // Load the corporate's home-bank shadow accounts in the program currency. On edit, the
  // ones already in this program start ticked.
  const shadowCorporateId = program?.corporateId || formData.corporateId;
  // On edit the currency is the program's (fixed); formData only catches up after its reset.
  const shadowCurrency = program?.currencyCode || formData.currencyCode;
  useEffect(() => {
    setBankShadows([]);
    setBankShadowsLoaded(false);
    setBankTouched(false);
    if (!isOpen || !shadowCorporateId || !shadowCurrency) return;
    // A superseded request must not land: an empty answer for the wrong currency arriving last
    // made an untouched save untick every account.
    let current = true;
    setLoadingAccounts(true);
    fetch(`/api/v1/treasury/shadow-accounts/corporate/${shadowCorporateId}/available?currency=${shadowCurrency}`)
      .then(res => res.json())
      .then(data => {
        if (!current || !data.success) return;
        const shadows: BankShadow[] = data.data || [];
        setBankShadows(shadows);
        setBankShadowsLoaded(true);
      })
      .catch(console.error)
      .finally(() => { if (current) setLoadingAccounts(false); });
    return () => { current = false; };
  }, [isOpen, shadowCorporateId, shadowCurrency, program]);

  // Fetch VIBAN pools when VIBAN is enabled
  useEffect(() => {
    // A pool belongs to one program, so a program being created has none to pick from yet.
    if (formData.configureViban && program) {
      setLoadingPools(true);
      fetch(`/api/v1/programs/${program.id}/viban-pools`)
        .then(res => res.json())
        .then(data => {
          if (data.success) {
            const pools = data.data?.content || data.data || [];
            setVibanPools(pools);
          }
        })
        .catch(console.error)
        .finally(() => setLoadingPools(false));
    }
  }, [formData.configureViban, program]);

 
  
  useEffect(() => {
    if (isOpen && program && needsWalletConfig) {
      setLoadingCharges(true);
      setChargesLoadFailed(false);
      fetchApi<WalletChargesResponse>(`/programs/${program.id}/wallet`, {}, CHARGES_API_BASE)
        .then(res => {
          if (res.success && res.data) {
            setWalletCharges(res.data);
            setChargeOverrides({
              topup: { 
                percent: res.data.topup.hasOverride ? res.data.topup.percentage : undefined, 
                flat: res.data.topup.hasOverride ? res.data.topup.fixed : undefined, 
                waived: res.data.topup.isWaived 
              },
              withdrawal: { 
                percent: res.data.withdrawal.hasOverride ? res.data.withdrawal.percentage : undefined, 
                flat: res.data.withdrawal.hasOverride ? res.data.withdrawal.fixed : undefined, 
                waived: res.data.withdrawal.isWaived 
              },
              transfer: { 
                percent: res.data.transfer.hasOverride ? res.data.transfer.percentage : undefined, 
                flat: res.data.transfer.hasOverride ? res.data.transfer.fixed : undefined, 
                waived: res.data.transfer.isWaived 
              },
              issuance: { 
                flat: res.data.issuance.hasOverride ? res.data.issuance.fixed : undefined, 
                waived: res.data.issuance.isWaived 
              },
              monthly: { 
                flat: res.data.monthly.hasOverride ? res.data.monthly.fixed : undefined, 
                waived: res.data.monthly.isWaived 
              },
              inactivity: { 
                flat: res.data.inactivity.hasOverride ? res.data.inactivity.fixed : undefined, 
                waived: res.data.inactivity.isWaived 
              },
            });
          } else {
            // No invented fallback when the program's real rates can't be loaded.
            setWalletCharges(null);
            setChargesLoadFailed(true);
            // Reset overrides to defaults
            setChargeOverrides({
              topup: { waived: false },
              withdrawal: { waived: false },
              transfer: { waived: false },
              issuance: { waived: false },
              monthly: { waived: false },
              inactivity: { waived: false },
            });
          }
        })
        .finally(() => setLoadingCharges(false));
    } else if (isOpen && !program && needsWalletConfig) {
      setWalletCharges(STANDARD_WALLET_BASE_RATES);
      // Reset overrides for new program
      setChargeOverrides({
        topup: { waived: false },
        withdrawal: { waived: false },
        transfer: { waived: false },
        issuance: { waived: false },
        monthly: { waived: false },
        inactivity: { waived: false },
      });
    }
  }, [isOpen, program, needsWalletConfig]);

  // Reset form when modal opens/closes
  useEffect(() => {
    if (program) {
      setFormData({
        // Step 1: Basic Info
        programCode: program.programCode,
        programName: program.programName,
        description: program.description || '',
        corporateId: program.corporateId,
        shadowAccountIds: [] as string[],
        currencyCode: program.currencyCode,
        // Step 2: Core Features
        configureViban: onlyStep === 'VIBAN Pool',
        configureWallet: onlyStep === 'Wallet Limits' || onlyStep === 'Wallet Fees',
        configureHierarchy: false,
        // Step 2: Extended Program Features
        // Step 3: Hierarchy Config
        hierarchyDepth: program.hierarchyDepth || 7,
        defaultHierarchyTemplate: program.defaultHierarchyTemplate || '',
        // Step 4: VIBAN Pool Config
        defaultVibanPoolId: program.defaultVibanPoolId || '',
        vibanGenerationStrategy: program.vibanGenerationStrategy || 'SEQUENTIAL',
        vibanPrefix: program.vibanPrefix || '',
        vibanBankCode: program.vibanBankCode || '',
        // Step 5: Wallet Limits
        defaultPerTransactionLimit: program.defaultPerTransactionLimit,
        defaultDailyLimit: program.defaultDailyLimit,
        defaultWeeklyLimit: undefined,
        defaultMonthlyLimit: program.defaultMonthlyLimit,
        defaultYearlyLimit: undefined,
        defaultMaxBalance: program.defaultMaxBalance,
        minTopup: undefined,
        maxTopup: undefined,
        defaultDailyTopupLimit: undefined,
        defaultMonthlyTopupLimit: undefined,
        // Wallet KYC
        kycRequired: program.kycRequired || false,
        autoKyc: false,
        minKycLevel: program.minKycLevel || 0,
        // Wallet Features
        allowTopup: program.allowTopup ?? true,
        allowWithdrawal: program.allowWithdrawal ?? true,
        allowTransfer: program.allowTransfer ?? true,
        // Wallet Expiry
        walletExpiryDays: undefined,
        defaultWalletType: 'CONSUMER',
        // Step 6: Settlement & Limits
        vaPrefix: program.vaPrefix || '',
        vaFormat: program.vaFormat || '',
        maxVirtualAccounts: program.maxVirtualAccounts,
        // Dates
        effectiveFrom: program.effectiveFrom || '',
        effectiveTo: program.effectiveTo || '',
      });
    } else {
      setFormData({
        // Step 1: Basic Info
        programCode: '',
        programName: '',
        description: '',
        corporateId: defaultCorporateId || '', // Use default corporate from page picker
        shadowAccountIds: [] as string[],
        currencyCode: 'AED',
        // Step 2: Core Features
        configureViban: false,
        configureWallet: false,
        configureHierarchy: false,
        // Step 2: Extended Program Features
        // Step 3: Hierarchy Config
        hierarchyDepth: 7,
        defaultHierarchyTemplate: '',
        // Step 4: VIBAN Pool Config
        defaultVibanPoolId: '',
        vibanGenerationStrategy: 'SEQUENTIAL',
        vibanPrefix: '',
        vibanBankCode: '',
        // Step 5: Wallet Limits
        defaultPerTransactionLimit: undefined,
        defaultDailyLimit: undefined,
        defaultWeeklyLimit: undefined,
        defaultMonthlyLimit: undefined,
        defaultYearlyLimit: undefined,
        defaultMaxBalance: undefined,
        minTopup: undefined,
        maxTopup: undefined,
        defaultDailyTopupLimit: undefined,
        defaultMonthlyTopupLimit: undefined,
        // Wallet KYC
        kycRequired: false,
        autoKyc: false,
        minKycLevel: 0,
        // Wallet Features
        allowTopup: true,
        allowWithdrawal: true,
        allowTransfer: true,
        // Wallet Expiry
        walletExpiryDays: undefined,
        defaultWalletType: 'CONSUMER',
        // Step 6: Settlement & Limits
        vaPrefix: '',
        vaFormat: '',
        maxVirtualAccounts: undefined,
        // Dates
        effectiveFrom: '',
        effectiveTo: '',
      });
      setStep(1);
      // Reset charge overrides for new program
      setChargeOverrides({
        topup: { waived: false },
        withdrawal: { waived: false },
        transfer: { waived: false },
        issuance: { waived: false },
        monthly: { waived: false },
        inactivity: { waived: false },
      });
    }
  }, [program, isOpen, defaultCorporateId, onlyStep]);

  // Save wallet charges via ChargeConfiguration API
  const saveWalletCharges = async (programId: string) => {
    if (!needsWalletConfig) return;
    
    const request: WalletChargesRequest = {
      programId,
      // Transaction fee overrides
      topupFeePercent: chargeOverrides.topup.percent,
      topupFeeFlat: chargeOverrides.topup.flat,
      withdrawalFeePercent: chargeOverrides.withdrawal.percent,
      withdrawalFeeFlat: chargeOverrides.withdrawal.flat,
      transferFeePercent: chargeOverrides.transfer.percent,
      transferFeeFlat: chargeOverrides.transfer.flat,
      // Fixed fee overrides
      issuanceFee: chargeOverrides.issuance.flat,
      monthlyFee: chargeOverrides.monthly.flat,
      inactivityFee: chargeOverrides.inactivity.flat,
      // ALL waivers
      waiveTopup: chargeOverrides.topup.waived,
      waiveWithdrawal: chargeOverrides.withdrawal.waived,
      waiveTransfer: chargeOverrides.transfer.waived,
      waiveIssuance: chargeOverrides.issuance.waived,
      waiveMonthly: chargeOverrides.monthly.waived,
      waiveInactivity: chargeOverrides.inactivity.waived,
    };

    try {
      const result = await fetchApi<WalletChargesResponse>(
        `/programs/${programId}/wallet`, 
        { method: 'PUT', body: JSON.stringify(request) }, 
        CHARGES_API_BASE
      );
      if (!result.success) {
        console.error('Failed to save wallet charges:', result.message);
      }
    } catch (error) {
      console.error('Error saving wallet charges:', error);
    }
  };

  const handleSubmit = async () => {
    // Resolve corporate ID - prioritize formData, fallback to defaultCorporateId
    const resolvedCorporateId = formData.corporateId || defaultCorporateId;

    // Debug logging
    console.log('handleSubmit - corporateId resolution:', {
      'formData.corporateId': formData.corporateId,
      'defaultCorporateId': defaultCorporateId,
      'resolvedCorporateId': resolvedCorporateId,
      'isEdit': isEdit
    });

    // Validate corporate ID for new programs
    if (!isEdit && !resolvedCorporateId) {
      console.error('Corporate ID is required for new programs');
      toast.error('Select a corporate before creating a program.');
      return;
    }

    const ifChanged = <T,>(loaded: T, value: T) => (isEdit && loaded === value ? undefined : value);
    setLoading(true);
    try {
      const cleanedData = {
        programCode: formData.programCode,
        programName: formData.programName,
        description: formData.description || undefined,
        corporateId: resolvedCorporateId,
        // Only the screen that shows the bank-account list sends it (not VIBAN / limits / fees).
        shadowAccountIds: !onlyStep && bankShadowsLoaded ? chosenShadowIds : undefined,
        currencyCode: formData.currencyCode,
        // Core feature flags
        // Extended program features
        // Hierarchy Configuration
        hierarchyDepth: formData.configureHierarchy ? formData.hierarchyDepth : undefined,
        defaultHierarchyTemplate: formData.configureHierarchy ? (formData.defaultHierarchyTemplate || undefined) : undefined,
        // VIBAN Pool Config
        defaultVibanPoolId: formData.configureViban ? (formData.defaultVibanPoolId || undefined) : undefined,
        vibanGenerationStrategy: formData.configureViban ? formData.vibanGenerationStrategy : undefined,
        vibanPrefix: formData.configureViban ? (formData.vibanPrefix || undefined) : undefined,
        vibanBankCode: formData.configureViban ? (formData.vibanBankCode || undefined) : undefined,
        // Wallet Limits Configuration
        defaultPerTransactionLimit: needsWalletConfig ? formData.defaultPerTransactionLimit : undefined,
        defaultDailyLimit: needsWalletConfig ? formData.defaultDailyLimit : undefined,
        defaultMonthlyLimit: needsWalletConfig ? formData.defaultMonthlyLimit : undefined,
        defaultMaxBalance: needsWalletConfig ? formData.defaultMaxBalance : undefined,
        // On edit, only send what was changed: the form shows a blank setting as its default and
        // used to save that default back, reporting settings nobody touched as changed.
        kycRequired: needsWalletConfig ? ifChanged(program?.kycRequired ?? false, formData.kycRequired) : undefined,
        minKycLevel: needsWalletConfig && formData.kycRequired ? formData.minKycLevel : undefined,
        allowTopup: needsWalletConfig ? ifChanged(program?.allowTopup ?? true, formData.allowTopup) : undefined,
        allowWithdrawal: needsWalletConfig ? ifChanged(program?.allowWithdrawal ?? true, formData.allowWithdrawal) : undefined,
        allowTransfer: needsWalletConfig ? ifChanged(program?.allowTransfer ?? true, formData.allowTransfer) : undefined,
        // Settlement & Limits
        vaPrefix: formData.vaPrefix || undefined,
        vaFormat: formData.vaFormat || undefined,
        maxVirtualAccounts: formData.maxVirtualAccounts || undefined,
        effectiveFrom: formData.effectiveFrom || undefined,
        effectiveTo: formData.effectiveTo || undefined,
        // Hierarchy Level Configurations (saved separately after program creation)
        hierarchyLevelConfigs: formData.configureHierarchy && hierarchyLevelConfigs.length > 0
          ? hierarchyLevelConfigs.slice(0, formData.hierarchyDepth).map((level, idx) => ({
              levelNumber: idx + 1,
              levelName: level.levelName,
              dimensionType: level.dimensionType,
              isRequired: level.isRequired,
              allowedValues: level.allowedValues,
              description: level.description,
              icon: level.icon,
            }))
          : undefined,
      };

      // Debug: log the cleanedData being sent (stringified to see undefined values)
      console.log('handleSubmit - cleanedData (JSON):', JSON.stringify(cleanedData, null, 2));
      console.log('handleSubmit - key fields:', {
        corporateId: cleanedData.corporateId,
        shadowAccountIds: cleanedData.shadowAccountIds,
        programCode: cleanedData.programCode,
      });

      const saved = await onSave(cleanedData);
      const programId = program?.id ?? saved?.id;

      // Save wallet charges separately via ChargeConfiguration API. On create
      // this used to be skipped ("charges saved on next edit") -- and the edit
      // wizard never showed the fees step, so fees entered at create were lost.
      // Fees only when the fee screen was part of this save (not from Wallet limits alone).
      if (needsWalletConfig && onlyStep !== 'Wallet Limits' && programId) {
        await saveWalletCharges(programId);
      }
      
      onClose();
    } finally {
      setLoading(false);
    }
  };

  // Get step index for a specific step type
  const getStepIndex = (stepType: string): number => {
    return stepLabels.indexOf(stepType) + 1;
  };

  // Check if current step matches a step type
  const isStepActive = (stepType: string): boolean => {
    return step === getStepIndex(stepType);
  };

  // Value checks (the API applies the same rules). Keyed by field; each step is blocked by its own.
  const fieldErrors: Record<string, string> = {};
  const negative = (v?: number | null) => v != null && v < 0;
  if (formData.maxVirtualAccounts != null && formData.maxVirtualAccounts < 1) fieldErrors.maxVirtualAccounts = 'At least 1, or leave blank for no limit';
  for (const k of ['defaultPerTransactionLimit', 'defaultDailyLimit', 'defaultMonthlyLimit', 'defaultMaxBalance'] as const) {
    if (negative(formData[k])) fieldErrors[k] = 'Must be 0 or more';
  }
  const { defaultPerTransactionLimit: perTxn, defaultDailyLimit: daily, defaultMonthlyLimit: monthly } = formData;
  if (!fieldErrors.defaultPerTransactionLimit && perTxn != null && daily != null && perTxn > daily) fieldErrors.defaultPerTransactionLimit = "Can't be more than the daily limit";
  if (!fieldErrors.defaultDailyLimit && daily != null && monthly != null && daily > monthly) fieldErrors.defaultDailyLimit = "Can't be more than the monthly limit";
  if (Object.values(chargeOverrides).some(c => negative((c as { percent?: number }).percent) || negative(c.flat))) fieldErrors.fees = 'Fees must be 0 or more';
  const stepFields: Record<string, string[]> = {
    'Basic Info': ['maxVirtualAccounts'],
    'Wallet Limits': ['defaultPerTransactionLimit', 'defaultDailyLimit', 'defaultMonthlyLimit', 'defaultMaxBalance'],
    'Wallet Fees': ['fees'],
  };
  const currentStepLabel = onlyStep ?? (isEdit ? 'Basic Info' : stepLabels[step - 1]);
  const stepHasErrors = (stepFields[currentStepLabel] ?? []).some(k => fieldErrors[k]);
  const hasErrors = Object.keys(fieldErrors).length > 0;

  const canProceed = () => {
    if (stepHasErrors) return false;
    switch (step) {
      case 1: return formData.programCode && formData.programName && (isEdit || formData.corporateId || defaultCorporateId);
      case 2: return true; // Features are optional
      case 3: return true;
      case 4: return true; // Settlement is optional
      default: return true;
    }
  };

  const selectedPool = vibanPools.find(p => p.id === formData.defaultVibanPoolId);

  return (
    <Modal isOpen={isOpen} onClose={onClose} size="lg" title={onlyStep ? `${onlyStep} — ${program?.programName ?? ''}` : isEdit ? 'Edit Program' : 'Create New Program'}>
      <div className="space-y-6">
        {/* Step Indicator - Show for Create and Edit (limited steps in edit mode) */}
        {(isEdit ? totalSteps > 1 : true) && (
          <div className="flex items-center justify-center gap-1 mb-6">
            {(isEdit ? ['Basic Info', 'Features'] : stepLabels).map((label, i) => {
              const stepNum = i + 1;
              const isActive = step === stepNum;
              const isComplete = step > stepNum;
              return (
                <React.Fragment key={label}>
                  <div className="flex flex-col items-center">
                    <div className={cn(
                      'w-8 h-8 rounded-full flex items-center justify-center text-body-sm font-medium transition-all',
                      isComplete ? 'bg-success-500 text-white' : isActive ? 'bg-primary-600 text-white' : 'bg-neutral-200 text-neutral-500 dark:bg-primary-800 dark:text-neutral-400'
                    )}>
                      {isComplete ? <CheckCircle className="w-4 h-4" /> : stepNum}
                    </div>
                    <span className={cn('text-caption mt-1', isActive ? 'text-primary-600 font-medium dark:text-primary-200' : 'text-neutral-400')}>
                      {label}
                    </span>
                  </div>
                  {i < (isEdit ? 1 : stepLabels.length - 1) && (
                    <div className={cn('w-12 h-1 mx-1 rounded-md', isComplete ? 'bg-success-500' : 'bg-neutral-200 dark:bg-primary-800')} />
                  )}
                </React.Fragment>
              );
            })}
          </div>
        )}

        {/* ================================================================ */}
        {/* STEP 1: Basic Information */}
        {/* ================================================================ */}
        {step === 1 && !onlyStep && (
          <div className="space-y-4">
            <h3 className="font-medium text-primary-900 flex items-center gap-2 dark:text-neutral-50">
              <Building2 className="w-4 h-4" />
              {isEdit ? 'Program details' : 'Step 1: Basic Information'}
            </h3>

            {/* Corporate Selection - First (most important context) */}
            {!isEdit && (
              <div className="p-3 bg-surface-page rounded-lg border border-edge">
                <label className="field-label block mb-1">Corporate *</label>
                {defaultCorporateId ? (
                  // Corporate is pre-selected from page picker - show as read-only
                  <div className="flex items-center gap-2">
                    <Building2 className="w-4 h-4 text-primary-600 dark:text-primary-200" />
                    <span className="font-medium text-primary-900 dark:text-neutral-50">
                      {corporates.find(c => c.id === defaultCorporateId)?.legalName || 'Loading...'}
                    </span>
                    <Badge variant="info" size="sm">Selected</Badge>
                  </div>
                ) : (
                  // No corporate pre-selected - allow selection
                  <Select
                    selectSize="sm"
                    aria-label="Corporate"
                    className={cn(!formData.corporateId && 'border-warning-300')}
                    value={formData.corporateId}
                    onChange={e => setFormData({ ...formData, corporateId: e.target.value, shadowAccountIds: [] })}
                    disabled={loadingCorporates}
                  >
                    <option value="">{loadingCorporates ? 'Loading...' : 'Select Corporate...'}</option>
                    {corporates.map(c => (
                      <option key={c.id} value={c.id}>{c.legalName}</option>
                    ))}
                  </Select>
                )}
                <p className="caption mt-1">Programs are created under a specific corporate entity.</p>
              </div>
            )}

            {/* Program Code and Name */}
            <div className="grid grid-cols-2 gap-4">
              <div>
                <Input
                  inputSize="sm"
                  label="Program Code *"
                  type="text"
                  className="font-mono"
                  placeholder="e.g., COLL-001"
                  value={formData.programCode}
                  onChange={e => setFormData({ ...formData, programCode: e.target.value.toUpperCase().replace(/[^A-Z0-9_-]/g, '') })}
                  disabled={isEdit}
                />
              </div>
              <div>
                <Input
                  inputSize="sm"
                  label="Program Name *"
                  type="text"
                  placeholder="e.g., Main Collection Program"
                  value={formData.programName}
                  onChange={e => setFormData({ ...formData, programName: e.target.value })}
                />
              </div>
            </div>

            {/* Currency and Max VAs */}
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="field-label block mb-1">Currency *</label>
                <CurrencyPicker
                  value={formData.currencyCode}
                  onChange={(c) => setFormData({ ...formData, currencyCode: c, shadowAccountIds: [] })}
                  disabled={isEdit}
                  withName
                  extra={['SAR', 'INR']}
                />
              </div>
              <div>
                <Input
                  inputSize="sm"
                  label="Max Virtual Accounts"
                  type="number"
                  placeholder="Unlimited"
                  min={1}
                  error={fieldErrors.maxVirtualAccounts}
                  value={formData.maxVirtualAccounts ?? ''}
                  onChange={e => setFormData({ ...formData, maxVirtualAccounts: e.target.value ? parseInt(e.target.value) : undefined })}
                />
              </div>
            </div>

            {/* Description */}
            <div>
              <TextArea
                textareaSize="sm"
                label="Description"
                rows={2}
                placeholder="Brief description of the program purpose..."
                value={formData.description}
                onChange={e => setFormData({ ...formData, description: e.target.value })}
              />
            </div>

            {/* Bank accounts: shadows of the corporate's home-bank accounts in this currency */}
            <div className="border-t border-edge pt-4">
              <p className="field-label mb-1">
                Bank accounts <span className="text-neutral-400 font-normal dark:text-neutral-400">(Optional)</span>
              </p>
              {!shadowCorporateId ? (
                <p className="caption">Select a corporate first.</p>
              ) : loadingAccounts ? (
                <p className="caption flex items-center gap-2"><Loader2 className="w-3 h-3 animate-spin" /> Loading bank accounts...</p>
              ) : bankShadows.length === 0 ? (
                <p className="caption">No home-bank accounts in {formData.currencyCode} for this corporate.</p>
              ) : (
                <div className="space-y-2">
                  {bankShadows.map(s => {
                    const takenElsewhere = !!s.programId && s.programId !== program?.id;
                    return (
                      <Checkbox
                        key={s.id}
                        variant="card"
                        disabled={takenElsewhere}
                        checked={chosenShadowIds.includes(s.id)}
                        onChange={checked => { setBankTouched(true); setFormData(prev => ({
                          ...prev,
                          shadowAccountIds: checked ? [...chosenShadowIds, s.id] : chosenShadowIds.filter(id => id !== s.id),
                        })); }}
                        label={<span className="font-mono">{s.bankAccountNumber} · {s.bankName}</span>}
                        description={takenElsewhere
                          ? `In use by ${s.programName ?? 'another program'}`
                          : formatCurrency(s.bankBalance ?? 0, s.currencyCode)}
                      />
                    );
                  })}
                </div>
              )}
              <p className="caption mt-1">The program's accounts sit on these bank accounts. The first one ticked is the main backing account.</p>
            </div>
          </div>
        )}

        {/* ================================================================ */}
        {/* STEP 2: Features */}
        {/* ================================================================ */}
        {step === 2 && (
          <div className="space-y-4">
            <h3 className="font-medium text-primary-900 flex items-center gap-2 dark:text-neutral-50">
              <Settings className="w-4 h-4" />
              Step 2: Configure now or later
            </h3>

            {/* Core Features - These affect wizard flow */}
            <div className="space-y-2">
              <h4 className="label">Every program can use these. Tick to configure them now; otherwise set them up later.</h4>
              <div className="grid grid-cols-2 gap-3">
                {[
                  { key: 'configureHierarchy', label: 'Configure Hierarchy now', icon: GitBranch, desc: 'Organize VAs in 7-level tree structure', color: 'text-cat-2 dark:text-cat-2-fg', step: 'Hierarchy Config' },
                  { key: 'configureViban', label: 'Configure VIBAN now', icon: Hash, desc: 'Virtual IBAN for each VA', color: 'text-accent-600 dark:text-accent-300', step: 'VIBAN Pool Config' },
                  { key: 'configureWallet', label: 'Configure Wallets now', icon: Wallet, desc: 'Prepaid wallet with limits & KYC', color: 'text-warning-600 dark:text-warning-300', step: 'Wallet Limits & Fees' },
                ].map(f => {
                  const Icon = f.icon;
                  const isChecked = formData[f.key as keyof typeof formData] as boolean;
                  // Check if this feature was auto-enabled by the selected program type
                  return (
                    <Checkbox
                      key={f.key}
                      variant="card"
                      checked={isChecked}
                      onChange={(checked) => setFormData({ ...formData, [f.key]: checked })}
                      label={
                        <span className="flex items-center gap-2 flex-wrap">
                          <Icon className={cn('w-4 h-4', f.color)} />
                          <span>{f.label}</span>
                          {f.step && isChecked && <Badge variant="info" size="sm">+Step</Badge>}
                        </span>
                      }
                      description={f.desc}
                    />
                  );
                })}
              </div>
            </div>

            {/* Info messages for enabled features */}
            {formData.configureViban && (
              <div className="bg-info-50 border border-info-200 rounded-lg p-3 flex items-start gap-2 dark:bg-info-500/10 dark:border-info-500/30">
                <Info className="w-4 h-4 text-info-600 mt-0.5 dark:text-info-300" />
                <p className="text-body-sm text-info-700 dark:text-info-300">
                  VIBAN is enabled. You'll configure the VIBAN pool settings in the next step.
                </p>
              </div>
            )}

            {formData.configureHierarchy && (
              <div className="bg-cat-2-soft border border-cat-2/20 rounded-lg p-3 flex items-start gap-2 dark:bg-cat-2/15 dark:border-cat-2/30">
                <GitBranch className="w-4 h-4 text-cat-2 dark:text-cat-2-fg mt-0.5" />
                <p className="text-body-sm text-cat-2 dark:text-cat-2-fg">
                  Hierarchy is enabled. You'll configure the hierarchy template and depth in the next step.
                </p>
              </div>
            )}
          </div>
        )}

        {/* ================================================================ */}
        {/* HIERARCHY CONFIGURATION STEP (only if hierarchy enabled) */}
        {/* ================================================================ */}
        {isStepActive('Hierarchy') && !isEdit && (
          <div className="space-y-4">
            <h3 className="font-medium text-primary-900 flex items-center gap-2 dark:text-neutral-50">
              <GitBranch className="w-4 h-4" />
              Hierarchy Configuration
            </h3>

            <p className="body-sm">Configure the hierarchy structure for this program. The hierarchy defines how virtual accounts are organized.</p>

            {/* Hierarchy Template Selection - driven by the features chosen in step 2 */}
            {(() => {
              const matchingTemplates = HIERARCHY_TEMPLATES;
              const recommendedTemplate = getRecommendedTemplate();

              return (
                <div className="space-y-4">
                  {/* Recommended Templates for this Program Type */}
                  {matchingTemplates.length > 0 && (
                    <div>
                      <label className="field-label block mb-2">
                        Templates
                      </label>
                      <div className="grid grid-cols-2 gap-3">
                        {matchingTemplates.map(template => {
                          const Icon = template.icon;
                          const isSelected = formData.defaultHierarchyTemplate === template.id;
                          const isRecommended = recommendedTemplate?.id === template.id;
                          const levelPath = template.levels.map(l => l.levelName).join(' → ');
                          return (
                            <button
                              key={template.id}
                              type="button"
                              onClick={() => {
                                setFormData({ ...formData, defaultHierarchyTemplate: template.id, hierarchyDepth: template.levels.length });
                                setHierarchyLevelConfigs([...template.levels]);
                              }}
                              className={cn(
                                'p-4 border rounded-lg text-left transition-all relative',
                                isSelected ? 'border-cat-2 bg-cat-2-soft ring-2 ring-cat-2 dark:bg-cat-2/15' : 'border-edge hover:border-neutral-300 dark:hover:border-primary-700'
                              )}
                            >
                              {isRecommended && (
                                <span className="absolute -top-2 -right-2 bg-success-500 text-white text-caption px-2 py-0.5 rounded-full">
                                  Recommended
                                </span>
                              )}
                              <div className="flex items-center gap-2 mb-2">
                                <StatusIconBadge tone={isSelected ? 'cat-2' : template.tone} icon={Icon} size="sm" />
                                <span className="font-medium text-body-sm">{template.name}</span>
                              </div>
                              <p className="caption mb-2">{template.description}</p>
                              <p className="text-caption text-neutral-400 truncate dark:text-neutral-400" title={levelPath}>{levelPath}</p>
                            </button>
                          );
                        })}
                      </div>
                    </div>
                  )}


                </div>
              );
            })()}

            {/* Hierarchy Depth */}
            <div>
              <label className="field-label block mb-2">Hierarchy Depth (1-7 levels)</label>
              <div className="flex items-center gap-4">
                <input
                  type="range"
                  min="1"
                  max="7"
                  value={formData.hierarchyDepth}
                  onChange={e => setFormData({ ...formData, hierarchyDepth: parseInt(e.target.value) })}
                  className="flex-1"
                />
                <span className="text-body-lg font-semibold text-primary-900 w-8 text-center dark:text-neutral-50">{formData.hierarchyDepth}</span>
              </div>
              <p className="caption mt-1">
                {formData.hierarchyDepth === 7 ? 'Full hierarchy (recommended)' :
                 formData.hierarchyDepth === 1 ? 'Flat structure (no hierarchy)' :
                 `${formData.hierarchyDepth} levels of organization`}
              </p>
            </div>

            {/* Hierarchy Level Configuration - Expandable UI */}
            {formData.defaultHierarchyTemplate && hierarchyLevelConfigs.length > 0 && (
              <div className="bg-surface-page rounded-lg p-4">
                <div className="flex items-center justify-between mb-3">
                  <h4 className="field-label flex items-center gap-2">
                    <Settings className="w-4 h-4" />
                    Level Configuration
                  </h4>
                  <button
                    type="button"
                    onClick={() => setShowLevelCustomization(!showLevelCustomization)}
                    className="text-caption text-cat-2 dark:text-cat-2-fg flex items-center gap-1"
                  >
                    {showLevelCustomization ? 'Collapse All' : 'Customize Levels'}
                    {showLevelCustomization ? <ChevronRight className="w-3 h-3 rotate-90" /> : <ChevronRight className="w-3 h-3" />}
                  </button>
                </div>

                <div className="space-y-2">
                  {hierarchyLevelConfigs.slice(0, formData.hierarchyDepth).map((level, idx) => {
                    const isExpanded = expandedLevelIndex === idx;
                    const isRoot = idx === 0;
                    const isLeaf = idx === formData.hierarchyDepth - 1;

                    return (
                      <div key={idx} className={cn(
                        'border rounded-lg transition-all',
                        isExpanded ? 'border-cat-2/30 bg-surface-card' : 'border-edge bg-surface-page'
                      )}>
                        {/* Level Header - Always visible */}
                        <button
                          type="button"
                          onClick={() => setExpandedLevelIndex(isExpanded ? null : idx)}
                          className="w-full px-3 py-2 flex items-center gap-2 text-left"
                        >
                          <span className={cn(
                            'w-6 h-6 rounded-full flex items-center justify-center text-caption font-medium flex-shrink-0',
                            isRoot ? 'bg-cat-2 text-white' :
                            isLeaf ? 'bg-success-500 text-white' :
                            'bg-neutral-300 text-neutral-700 dark:text-neutral-200'
                          )}>
                            {idx + 1}
                          </span>
                          <span className="text-body-sm font-medium text-neutral-800 flex-1 dark:text-neutral-100">{level.levelName}</span>
                          <span className="caption">({level.dimensionType})</span>
                          {isRoot && <Badge variant="info" size="sm">Root</Badge>}
                          {isLeaf && <Badge variant="success" size="sm">Leaf</Badge>}
                          {level.allowedValues && level.allowedValues.length > 0 && (
                            <span className="text-caption bg-cat-2/10 text-cat-2 dark:text-cat-2-fg px-2 py-0.5 rounded-full dark:bg-cat-2/15">
                              {level.allowedValues.length} values
                            </span>
                          )}
                          <ChevronRight className={cn(
                            'w-4 h-4 text-neutral-400 transition-transform dark:text-neutral-400',
                            isExpanded && 'rotate-90'
                          )} />
                        </button>

                        {/* Level Details - Expanded */}
                        {isExpanded && (
                          <div className="px-3 pb-3 border-t border-edge pt-3 space-y-3">
                            {/* Level Name Edit */}
                            <div>
                              <label className="block label-cased mb-1">Level Name</label>
                              <input
                                type="text"
                                value={level.levelName}
                                onChange={e => {
                                  const updated = [...hierarchyLevelConfigs];
                                  updated[idx] = { ...updated[idx], levelName: e.target.value };
                                  setHierarchyLevelConfigs(updated);
                                }}
                                className="w-full px-2 py-1 text-body-sm border border-edge-strong rounded-md bg-surface-card text-primary-900 dark:bg-primary-900 dark:border-primary-700 dark:text-neutral-50"
                              />
                            </div>

                            {/* Dimension Type */}
                            <div>
                              <label className="block label-cased mb-1">Dimension Type</label>
                              <select
                                value={level.dimensionType}
                                onChange={e => {
                                  const updated = [...hierarchyLevelConfigs];
                                  updated[idx] = { ...updated[idx], dimensionType: e.target.value };
                                  setHierarchyLevelConfigs(updated);
                                }}
                                className="w-full px-2 py-1 text-body-sm border border-edge-strong rounded-md bg-surface-card text-primary-900 dark:bg-primary-900 dark:border-primary-700 dark:text-neutral-50"
                              >
                                <option value="CURRENCY">💱 Currency</option>
                                <option value="REGION">🌍 Region</option>
                                <option value="COUNTRY">🏳️ Country</option>
                                <option value="STATE">📍 State/Province</option>
                                <option value="CITY">🏙️ City</option>
                                <option value="ENTITY">🏢 Legal Entity</option>
                                <option value="DEPARTMENT">👥 Department</option>
                                <option value="COST_CENTER">💰 Cost Center</option>
                                <option value="ACCOUNT_TYPE">📊 Account Type</option>
                                <option value="CHANNEL">📡 Channel</option>
                                <option value="PLATFORM">🌐 Platform</option>
                                <option value="SEGMENT">🎯 Segment</option>
                                <option value="CUSTOMER">👤 Customer</option>
                                <option value="MERCHANT">🏪 Merchant</option>
                                <option value="PARTNER">🤝 Partner</option>
                                <option value="TIER">⭐ Tier</option>
                                <option value="BUDGET_OWNER">📋 Budget Owner</option>
                                <option value="VIRTUAL_ACCOUNT">💳 Virtual Account</option>
                              </select>
                            </div>

                            {/* Allowed Values */}
                            <div>
                              <div className="flex items-center justify-between mb-1">
                                <label className="label-cased">Allowed Values</label>
                                {(() => {
                                  // Suggested values based on dimension type
                                  const suggestions: Record<string, string[]> = {
                                    CURRENCY: ['AED', 'USD', 'EUR', 'GBP', 'SAR'],
                                    REGION: ['NORTH', 'SOUTH', 'EAST', 'WEST', 'MENA', 'APAC'],
                                    CHANNEL: ['INVOICE', 'ECOMMERCE', 'POS', 'DIRECT'],
                                    SEGMENT: ['CORPORATE', 'SME', 'RETAIL', 'ENTERPRISE'],
                                    TIER: ['PLATINUM', 'GOLD', 'SILVER', 'BRONZE'],
                                    ACCOUNT_TYPE: ['PAYABLES', 'RECEIVABLES', 'TAXES', 'PAYROLL'],
                                  };
                                  const suggestedForType = suggestions[level.dimensionType];
                                  if (suggestedForType) {
                                    return (
                                      <button
                                        type="button"
                                        onClick={() => {
                                          const updated = [...hierarchyLevelConfigs];
                                          updated[idx] = { ...updated[idx], allowedValues: [...suggestedForType] };
                                          setHierarchyLevelConfigs(updated);
                                        }}
                                        className="text-caption text-cat-2 dark:text-cat-2-fg"
                                      >
                                        + Add suggested
                                      </button>
                                    );
                                  }
                                  return null;
                                })()}
                              </div>

                              {/* Current Values */}
                              <div className="flex flex-wrap gap-1 mb-2 min-h-[28px]">
                                {level.allowedValues && level.allowedValues.length > 0 ? (
                                  level.allowedValues.map((value, vIdx) => (
                                    <span
                                      key={vIdx}
                                      className="inline-flex items-center gap-1 px-2 py-0.5 bg-cat-2/10 text-cat-2 dark:text-cat-2-fg text-caption rounded-full dark:bg-cat-2/15"
                                    >
                                      {value}
                                      <button
                                        type="button"
                                        onClick={() => {
                                          const updated = [...hierarchyLevelConfigs];
                                          updated[idx] = {
                                            ...updated[idx],
                                            allowedValues: (updated[idx].allowedValues || []).filter((_, i) => i !== vIdx),
                                          };
                                          setHierarchyLevelConfigs(updated);
                                        }}
                                        className="hover:text-cat-2"
                                      >
                                        <X className="w-3 h-3" />
                                      </button>
                                    </span>
                                  ))
                                ) : (
                                  <span className="text-caption text-neutral-400 italic dark:text-neutral-400">No restrictions (any value allowed)</span>
                                )}
                              </div>

                              {/* Add New Value */}
                              <div className="flex gap-1">
                                <input
                                  type="text"
                                  placeholder="Add value..."
                                  className="flex-1 px-2 py-1 text-caption border border-edge-strong rounded-md bg-surface-card text-primary-900 dark:bg-primary-900 dark:border-primary-700 dark:text-neutral-50"
                                  onKeyDown={e => {
                                    if (e.key === 'Enter') {
                                      e.preventDefault();
                                      const input = e.target as HTMLInputElement;
                                      const value = input.value.trim().toUpperCase();
                                      if (value && !(level.allowedValues || []).includes(value)) {
                                        const updated = [...hierarchyLevelConfigs];
                                        updated[idx] = {
                                          ...updated[idx],
                                          allowedValues: [...(updated[idx].allowedValues || []), value],
                                        };
                                        setHierarchyLevelConfigs(updated);
                                        input.value = '';
                                      }
                                    }
                                  }}
                                />
                                <button
                                  type="button"
                                  onClick={e => {
                                    const input = (e.target as HTMLElement).previousElementSibling as HTMLInputElement;
                                    const value = input.value.trim().toUpperCase();
                                    if (value && !(level.allowedValues || []).includes(value)) {
                                      const updated = [...hierarchyLevelConfigs];
                                      updated[idx] = {
                                        ...updated[idx],
                                        allowedValues: [...(updated[idx].allowedValues || []), value],
                                      };
                                      setHierarchyLevelConfigs(updated);
                                      input.value = '';
                                    }
                                  }}
                                  className="px-2 py-1 text-caption bg-cat-2 text-white rounded-md hover:bg-cat-2/90"
                                >
                                  <Plus className="w-3 h-3" />
                                </button>
                              </div>
                            </div>

                            {/* Required Toggle */}
                            <Checkbox size="sm" label="This level is required (must have a value)" checked={level.isRequired ?? false} onChange={(checked) => {
                                  const updated = [...hierarchyLevelConfigs];
                                  updated[idx] = { ...updated[idx], isRequired: checked };
                                  setHierarchyLevelConfigs(updated);
                                }} className="text-caption" />
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>

                {/* Summary */}
                <div className="mt-3 pt-3 border-t border-edge flex items-center justify-between caption">
                  <span>
                    {hierarchyLevelConfigs.slice(0, formData.hierarchyDepth).filter(l => l.allowedValues && l.allowedValues.length > 0).length} of {formData.hierarchyDepth} levels have restrictions
                  </span>
                  <span className="text-neutral-400">
                    Click any level to customize
                  </span>
                </div>
              </div>
            )}

            {/* Info Box */}
            <div className="bg-info-50 border border-info-200 rounded-lg p-3 dark:bg-info-500/10 dark:border-info-500/30">
              <div className="flex items-start gap-2">
                <Info className="w-4 h-4 text-info-600 mt-0.5 dark:text-info-300" />
                <div className="text-body-sm text-info-800 dark:text-info-300">
                  <p className="font-medium">About Hierarchy</p>
                  <ul className="mt-1 space-y-1 text-info-700 text-caption dark:text-info-300">
                    <li>• Balances aggregate up the hierarchy automatically</li>
                    <li>• Virtual accounts (VAs) are always at the leaf level</li>
                    <li>• You can customize level labels and allowed values after creation</li>
                    <li>• Templates are filtered based on your selected program type</li>
                  </ul>
                </div>
              </div>
            </div>
          </div>
        )}

        {/* ================================================================ */}
        {/* VIBAN Pool Configuration (only if VIBAN enabled) */}
        {/* ================================================================ */}
        {isStepActive('VIBAN Pool') && showStepBody && (
          <div className="space-y-4">
            <h3 className="font-medium text-primary-900 flex items-center gap-2 dark:text-neutral-50">
              <Hash className="w-4 h-4" />
              VIBAN Pool Configuration
            </h3>

            <p className="body-sm">Configure how VIBANs will be generated for this program.</p>

            {/* Generation Strategy */}
            <div>
              <label className="field-label block mb-2">Generation Strategy *</label>
              <div className="grid grid-cols-3 gap-3">
                {Object.entries(vibanStrategyConfig).map(([key, config]) => {
                  const StrategyIcon = config.icon;
                  const isSelected = formData.vibanGenerationStrategy === key;
                  return (
                    <button
                      key={key}
                      type="button"
                      onClick={() => setFormData({ ...formData, vibanGenerationStrategy: key as VibanGenerationStrategy })}
                      className={cn(
                        'p-4 border rounded-lg text-left transition-all',
                        isSelected ? 'border-primary-500 bg-primary-50 ring-2 ring-primary-500 dark:bg-primary-800/40' : 'border-edge hover:border-neutral-300 dark:hover:border-primary-700'
                      )}
                    >
                      <div className="flex items-center gap-2 mb-2">
                        <StrategyIcon className={cn('w-5 h-5', isSelected ? 'text-primary-600 dark:text-primary-200' : 'text-neutral-500 dark:text-neutral-400')} />
                        <span className="font-medium text-body-sm">{config.label}</span>
                      </div>
                      <p className="caption">{config.description}</p>
                    </button>
                  );
                })}
              </div>
            </div>

            {/* VIBAN Pool Selection */}
            <div>
              <Select
                selectSize="sm"
                label="VIBAN Pool"
                value={formData.defaultVibanPoolId}
                onChange={e => setFormData({ ...formData, defaultVibanPoolId: e.target.value })}
                disabled={!program || loadingPools}
              >
                <option value="">{!program ? 'Available after the program is created' : loadingPools ? 'Loading pools...' : vibanPools.length ? 'Select VIBAN Pool (optional)...' : 'No pools for this program yet'}</option>
                {vibanPools.map(pool => (
                  <option key={pool.id} value={pool.id}>
                    {pool.poolName} ({pool.availableCount} available)
                  </option>
                ))}
              </Select>
              {selectedPool && (
                <div className="mt-2 p-3 bg-surface-page rounded-lg">
                  <div className="grid grid-cols-3 gap-4 text-center">
                    <div>
                      <p className="section-title">{selectedPool.poolSize}</p>
                      <p className="caption">Total</p>
                    </div>
                    <div>
                      <p className="text-body-lg font-semibold text-success-600 dark:text-success-300">{selectedPool.availableCount}</p>
                      <p className="caption">Available</p>
                    </div>
                    <div>
                      <p className="text-body-lg font-semibold text-warning-600 dark:text-warning-300">{selectedPool.assignedCount}</p>
                      <p className="caption">Used</p>
                    </div>
                  </div>
                </div>
              )}
            </div>

            {/* VIBAN Prefix and Bank Code */}
            <div className="grid grid-cols-2 gap-4">
              <div>
                <Input
                  inputSize="sm"
                  label="VIBAN Prefix"
                  type="text"
                  className="font-mono"
                  placeholder="e.g., AE"
                  maxLength={4}
                  value={formData.vibanPrefix}
                  onChange={e => setFormData({ ...formData, vibanPrefix: e.target.value.toUpperCase() })}
                />
              </div>
              <div>
                <Input
                  inputSize="sm"
                  label="Bank Code"
                  type="text"
                  className="font-mono"
                  placeholder="e.g., 033"
                  maxLength={4}
                  value={formData.vibanBankCode}
                  onChange={e => setFormData({ ...formData, vibanBankCode: e.target.value })}
                />
              </div>
            </div>

            {/* Sample VIBAN Preview */}
            <div className="bg-surface-muted rounded-lg p-4">
              <p className="caption mb-2 text-center">Sample VIBAN Format</p>
              <p className="font-mono text-heading-sm text-center text-primary-900 tracking-wider dark:text-neutral-50">
                {formData.vibanPrefix || 'AE'}{formData.vibanBankCode || '00'}-
                {formData.vibanGenerationStrategy === 'SEQUENTIAL' && '0000-0001'}
                {formData.vibanGenerationStrategy === 'RANDOM' && 'X7K2-9M4P'}
                {formData.vibanGenerationStrategy === 'HIERARCHY_ENCODED' && 'L1L2-0001'}
              </p>
            </div>
          </div>
        )}
        {/* ================================================================ */}
        {/* WALLET LIMITS STEP (only if wallet enabled) - Transaction & Balance Limits */}
        {/* ================================================================ */}
        {isStepActive('Wallet Limits') && showStepBody && (
          <div className="space-y-4">
            <h3 className="font-medium text-primary-900 flex items-center gap-2 dark:text-neutral-50">
              <Settings className="w-4 h-4" />
              Wallet Limits Configuration
            </h3>

            <p className="body-sm">Configure transaction limits, balance caps, and wallet feature settings for this program.</p>

            {/* Transaction Limits */}
            <div className="space-y-3">
              <h4 className="text-body-sm font-medium text-neutral-700 border-b pb-2 dark:text-neutral-200">Transaction Limits</h4>
              <div className="grid grid-cols-3 gap-4">
                <div>
                  <label className="block label-cased mb-1">Per Transaction Limit</label>
                  <div className="relative">
                    <span className="absolute left-3 top-1/2 -translate-y-1/2 text-neutral-400 text-body-sm dark:text-neutral-400">{formData.currencyCode}</span>
                    <Input
                      inputSize="sm"
                      type="number"
                      className="pl-12"
                      placeholder="50,000"
                      min={0}
                      value={formData.defaultPerTransactionLimit ?? ''}
                      onChange={e => setFormData({ ...formData, defaultPerTransactionLimit: e.target.value ? parseFloat(e.target.value) : undefined })}
                    />
                  </div>
                  {fieldErrors.defaultPerTransactionLimit && <p className="caption text-error-600 dark:text-error-300 mt-1" role="alert">{fieldErrors.defaultPerTransactionLimit}</p>}
                </div>
                <div>
                  <label className="block label-cased mb-1">Daily Limit</label>
                  <div className="relative">
                    <span className="absolute left-3 top-1/2 -translate-y-1/2 text-neutral-400 text-body-sm dark:text-neutral-400">{formData.currencyCode}</span>
                    <Input
                      inputSize="sm"
                      type="number"
                      className="pl-12"
                      placeholder="200,000"
                      min={0}
                      value={formData.defaultDailyLimit ?? ''}
                      onChange={e => setFormData({ ...formData, defaultDailyLimit: e.target.value ? parseFloat(e.target.value) : undefined })}
                    />
                  </div>
                  {fieldErrors.defaultDailyLimit && <p className="caption text-error-600 dark:text-error-300 mt-1" role="alert">{fieldErrors.defaultDailyLimit}</p>}
                </div>
                <div>
                  <label className="block label-cased mb-1">Monthly Limit</label>
                  <div className="relative">
                    <span className="absolute left-3 top-1/2 -translate-y-1/2 text-neutral-400 text-body-sm dark:text-neutral-400">{formData.currencyCode}</span>
                    <Input
                      inputSize="sm"
                      type="number"
                      className="pl-12"
                      placeholder="1,000,000"
                      min={0}
                      value={formData.defaultMonthlyLimit ?? ''}
                      onChange={e => setFormData({ ...formData, defaultMonthlyLimit: e.target.value ? parseFloat(e.target.value) : undefined })}
                    />
                  </div>
                  {fieldErrors.defaultMonthlyLimit && <p className="caption text-error-600 dark:text-error-300 mt-1" role="alert">{fieldErrors.defaultMonthlyLimit}</p>}
                </div>
              </div>
            </div>

            {/* Balance Limits */}
            <div className="space-y-3">
              <h4 className="text-body-sm font-medium text-neutral-700 border-b pb-2 dark:text-neutral-200">Balance Limits</h4>
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block label-cased mb-1">Maximum Balance</label>
                  <div className="relative">
                    <span className="absolute left-3 top-1/2 -translate-y-1/2 text-neutral-400 text-body-sm dark:text-neutral-400">{formData.currencyCode}</span>
                    <Input
                      inputSize="sm"
                      type="number"
                      className="pl-12"
                      placeholder="500,000"
                      min={0}
                      value={formData.defaultMaxBalance ?? ''}
                      onChange={e => setFormData({ ...formData, defaultMaxBalance: e.target.value ? parseFloat(e.target.value) : undefined })}
                    />
                  </div>
                  {fieldErrors.defaultMaxBalance && <p className="caption text-error-600 dark:text-error-300 mt-1" role="alert">{fieldErrors.defaultMaxBalance}</p>}
                  <p className="caption mt-1">Cap on total wallet balance</p>
                </div>
              </div>
            </div>

            {/* KYC Configuration */}
            <div className="space-y-3">
              <h4 className="text-body-sm font-medium text-neutral-700 border-b pb-2 dark:text-neutral-200">KYC Configuration</h4>
              <div className="grid grid-cols-2 gap-4">
                <Checkbox
                  variant="card"
                  checked={formData.kycRequired ?? false}
                  onChange={(checked) => setFormData({ ...formData, kycRequired: checked })}
                  label="KYC Required"
                  description="Require KYC verification for wallet holders"
                />
                <div>
                  <Select
                    selectSize="sm"
                    label="Minimum KYC Level"
                    value={formData.minKycLevel ?? 1}
                    onChange={e => setFormData({ ...formData, minKycLevel: parseInt(e.target.value) })}
                    disabled={!formData.kycRequired}
                  >
                    <option value={1}>Level 1 - Basic (Name, Phone)</option>
                    <option value={2}>Level 2 - Standard (+ ID Document)</option>
                    <option value={3}>Level 3 - Enhanced (+ Address Proof)</option>
                    <option value={4}>Level 4 - Full (+ Income Proof)</option>
                  </Select>
                </div>
              </div>
            </div>

            {/* Wallet Features/Capabilities */}
            <div className="space-y-3">
              <h4 className="text-body-sm font-medium text-neutral-700 border-b pb-2 dark:text-neutral-200">Wallet Capabilities</h4>
              <div className="grid grid-cols-2 gap-3">
                {[
                  { key: 'allowTopup', label: 'Allow Topup', desc: 'Add funds to wallet', defaultVal: true },
                  { key: 'allowWithdrawal', label: 'Allow Withdrawal', desc: 'Withdraw to bank account', defaultVal: true },
                  { key: 'allowTransfer', label: 'Allow Transfer', desc: 'Transfer between wallets', defaultVal: true },
                ].map(cap => (
                  <Checkbox
                    key={cap.key}
                    variant="card"
                    checked={(formData as any)[cap.key] ?? cap.defaultVal}
                    onChange={(checked) => setFormData({ ...formData, [cap.key]: checked })}
                    label={cap.label}
                    description={cap.desc}
                  />
                ))}
              </div>
            </div>

            {/* Info Box */}
            <div className="bg-info-50 border border-info-200 rounded-lg p-3 dark:bg-info-500/10 dark:border-info-500/30">
              <div className="flex items-start gap-2">
                <Info className="w-4 h-4 text-info-600 mt-0.5 dark:text-info-300" />
                <div className="text-body-sm text-info-800 dark:text-info-300">
                  <p className="font-medium">About Wallet Limits</p>
                  <ul className="mt-1 space-y-1 text-info-700 text-caption dark:text-info-300">
                    <li>• Limits can be overridden at the individual wallet level</li>
                    <li>• KYC levels determine maximum allowed limits</li>
                    <li>• Leave blank to use system defaults</li>
                  </ul>
                </div>
              </div>
            </div>
          </div>
        )}

        {/* ================================================================ */}
        {/* WALLET FEES STEP (only if wallet enabled) - Using ChargeConfiguration */}
        {/* ================================================================ */}
        {isStepActive('Wallet Fees') && showStepBody && (
          <div className="space-y-4">
            <h3 className="font-medium text-primary-900 flex items-center gap-2 dark:text-neutral-50">
              <Percent className="w-4 h-4" />
              Wallet Fees Configuration
            </h3>

            <p className="body-sm">Configure fee overrides for this program. Leave blank to use base rates.</p>

            {loadingCharges ? (
              <div className="flex items-center justify-center py-8">
                <Loader2 className="w-6 h-6 animate-spin text-primary-500" />
              </div>
            ) : walletCharges ? (
              <div className="space-y-2">
                <ChargeConfigRow charge={walletCharges.topup} currencyCode={formData.currencyCode}
                  overridePercent={chargeOverrides.topup.percent} overrideFlat={chargeOverrides.topup.flat} isWaived={chargeOverrides.topup.waived}
                  onPercentChange={v => setChargeOverrides({ ...chargeOverrides, topup: { ...chargeOverrides.topup, percent: v } })}
                  onFlatChange={v => setChargeOverrides({ ...chargeOverrides, topup: { ...chargeOverrides.topup, flat: v } })}
                  onWaiverChange={w => setChargeOverrides({ ...chargeOverrides, topup: { ...chargeOverrides.topup, waived: w } })} />
                <ChargeConfigRow charge={walletCharges.withdrawal} currencyCode={formData.currencyCode}
                  overridePercent={chargeOverrides.withdrawal.percent} overrideFlat={chargeOverrides.withdrawal.flat} isWaived={chargeOverrides.withdrawal.waived}
                  onPercentChange={v => setChargeOverrides({ ...chargeOverrides, withdrawal: { ...chargeOverrides.withdrawal, percent: v } })}
                  onFlatChange={v => setChargeOverrides({ ...chargeOverrides, withdrawal: { ...chargeOverrides.withdrawal, flat: v } })}
                  onWaiverChange={w => setChargeOverrides({ ...chargeOverrides, withdrawal: { ...chargeOverrides.withdrawal, waived: w } })} />
                <ChargeConfigRow charge={walletCharges.transfer} currencyCode={formData.currencyCode}
                  overridePercent={chargeOverrides.transfer.percent} overrideFlat={chargeOverrides.transfer.flat} isWaived={chargeOverrides.transfer.waived}
                  onPercentChange={v => setChargeOverrides({ ...chargeOverrides, transfer: { ...chargeOverrides.transfer, percent: v } })}
                  onFlatChange={v => setChargeOverrides({ ...chargeOverrides, transfer: { ...chargeOverrides.transfer, flat: v } })}
                  onWaiverChange={w => setChargeOverrides({ ...chargeOverrides, transfer: { ...chargeOverrides.transfer, waived: w } })} />
              </div>
            ) : chargesLoadFailed ? (
              <Alert variant="error" title="Couldn't load this program's wallet fees">
                Base rates are unavailable, so fee overrides can't be edited right now. Try again later.
              </Alert>
            ) : null}

            {/* Fixed Fees */}
            {walletCharges && (
              <div className="grid grid-cols-3 gap-4 pt-4 border-t">
                <div className="p-3 bg-surface-page rounded-lg">
                  <div className="flex items-center justify-between mb-2">
                    <span className="text-body-sm font-medium">{walletCharges.issuance.chargeName}</span>
                    <Checkbox size="sm" label="Waive" checked={chargeOverrides.issuance.waived} onChange={(checked) => setChargeOverrides({ ...chargeOverrides, issuance: { ...chargeOverrides.issuance, waived: checked } })} className="text-caption" />
                  </div>
                  <div className="flex items-center gap-2">
                    <span className="caption">Base: {formData.currencyCode} {walletCharges.issuance.fixed}</span>
                    <ChevronRight className="w-3 h-3 text-neutral-300 dark:text-neutral-400" />
                    <input type="number" min={0} className={cn('w-20 px-2 py-1 text-body-sm border border-edge-strong rounded-md bg-surface-card text-primary-900 dark:bg-primary-900 dark:border-primary-700 dark:text-neutral-50', chargeOverrides.issuance.waived && 'bg-surface-muted')}
                      placeholder={String(walletCharges.issuance.fixed)} value={chargeOverrides.issuance.flat ?? ''}
                      onChange={e => setChargeOverrides({ ...chargeOverrides, issuance: { ...chargeOverrides.issuance, flat: e.target.value ? parseFloat(e.target.value) : undefined } })}
                      disabled={chargeOverrides.issuance.waived} />
                  </div>
                </div>
                <div className="p-3 bg-surface-page rounded-lg">
                  <div className="flex items-center justify-between mb-2">
                    <span className="text-body-sm font-medium">{walletCharges.monthly.chargeName}</span>
                    <Checkbox size="sm" label="Waive" checked={chargeOverrides.monthly.waived} onChange={(checked) => setChargeOverrides({ ...chargeOverrides, monthly: { ...chargeOverrides.monthly, waived: checked } })} className="text-caption" />
                  </div>
                  <div className="flex items-center gap-2">
                    <span className="caption">Base: {formData.currencyCode} {walletCharges.monthly.fixed}</span>
                    <ChevronRight className="w-3 h-3 text-neutral-300 dark:text-neutral-400" />
                    <input type="number" min={0} className={cn('w-20 px-2 py-1 text-body-sm border border-edge-strong rounded-md bg-surface-card text-primary-900 dark:bg-primary-900 dark:border-primary-700 dark:text-neutral-50', chargeOverrides.monthly.waived && 'bg-surface-muted')}
                      placeholder={String(walletCharges.monthly.fixed)} value={chargeOverrides.monthly.flat ?? ''}
                      onChange={e => setChargeOverrides({ ...chargeOverrides, monthly: { ...chargeOverrides.monthly, flat: e.target.value ? parseFloat(e.target.value) : undefined } })}
                      disabled={chargeOverrides.monthly.waived} />
                  </div>
                </div>
                <div className="p-3 bg-surface-page rounded-lg">
                  <div className="flex items-center justify-between mb-2">
                    <span className="text-body-sm font-medium">{walletCharges.inactivity.chargeName}</span>
                    <Checkbox size="sm" label="Waive" checked={chargeOverrides.inactivity.waived} onChange={(checked) => setChargeOverrides({ ...chargeOverrides, inactivity: { ...chargeOverrides.inactivity, waived: checked } })} className="text-caption" />
                  </div>
                  <div className="flex items-center gap-2">
                    <span className="caption">Base: {formData.currencyCode} {walletCharges.inactivity.fixed}</span>
                    <ChevronRight className="w-3 h-3 text-neutral-300 dark:text-neutral-400" />
                    <input type="number" min={0} className={cn('w-20 px-2 py-1 text-body-sm border border-edge-strong rounded-md bg-surface-card text-primary-900 dark:bg-primary-900 dark:border-primary-700 dark:text-neutral-50', chargeOverrides.inactivity.waived && 'bg-surface-muted')}
                      placeholder={String(walletCharges.inactivity.fixed)} value={chargeOverrides.inactivity.flat ?? ''}
                      onChange={e => setChargeOverrides({ ...chargeOverrides, inactivity: { ...chargeOverrides.inactivity, flat: e.target.value ? parseFloat(e.target.value) : undefined } })}
                      disabled={chargeOverrides.inactivity.waived} />
                  </div>
                </div>
              </div>
            )}

            <div className="bg-info-50 border border-info-200 rounded-lg p-3 flex items-start gap-2 dark:bg-info-500/10 dark:border-info-500/30">
              <Info className="w-4 h-4 text-info-600 mt-0.5 dark:text-info-300" />
              <p className="text-body-sm text-info-700 dark:text-info-300">
                Overrides apply to this program only. Leave a fee blank to use the standard rate.
              </p>
            </div>
            {fieldErrors.fees && <p className="caption text-error-600 dark:text-error-300" role="alert">{fieldErrors.fees}</p>}
          </div>
        )}

        {/* ================================================================ */}
        {/* SETTLEMENT STEP: Settlement & Configuration */}
        {/* ================================================================ */}
        {isStepActive('VA Numbering') && !isEdit && (
          <div className="space-y-4">
            <h3 className="font-medium text-primary-900 flex items-center gap-2 dark:text-neutral-50">
              <Clock className="w-4 h-4" />
              Virtual Account Numbering
            </h3>

            <div className="grid grid-cols-2 gap-4">
              <div>
                <Input
                  inputSize="sm"
                  label="VA Prefix"
                  type="text"
                  className="font-mono"
                  placeholder="e.g., VA"
                  value={formData.vaPrefix} 
                  onChange={e => setFormData({ ...formData, vaPrefix: e.target.value.toUpperCase() })} 
                />
              </div>
              <div>
                <Input
                  inputSize="sm"
                  label="VA Format"
                  type="text"
                  className="font-mono"
                  placeholder="{PREFIX}{SEQ:8}"
                  value={formData.vaFormat} 
                  onChange={e => setFormData({ ...formData, vaFormat: e.target.value })} 
                />
              </div>
            </div>

          </div>
        )}

        {/* ================================================================ */}
        {/* STEP 5 (or 4 if no VIBAN): Review */}
        {/* ================================================================ */}
        {!isEdit && step === totalSteps && (
          <div className="space-y-4">
            <h3 className="font-medium text-primary-900 flex items-center gap-2 dark:text-neutral-50">
              <CheckCircle className="w-4 h-4" />
              Review & Confirm
            </h3>

            <div className="bg-surface-page rounded-lg p-4 space-y-4">
              {/* Basic Info */}
              <div>
                <h4 className="label mb-2">Basic Information</h4>
                <div className="grid grid-cols-2 gap-x-4 gap-y-2 text-body-sm">
                  <div className="col-span-2"><span className="text-neutral-500 dark:text-neutral-400">Corporate:</span> <span className="font-medium">{corporates.find(c => c.id === (formData.corporateId || defaultCorporateId))?.legalName ?? '—'}</span></div>
                  <div><span className="text-neutral-500 dark:text-neutral-400">Code:</span> <span className="font-mono font-medium">{formData.programCode}</span></div>
                  <div><span className="text-neutral-500 dark:text-neutral-400">Name:</span> <span className="font-medium">{formData.programName}</span></div>
                  <div><span className="text-neutral-500 dark:text-neutral-400">Currency:</span> <span className="font-medium">{formData.currencyCode}</span></div>
                  <div className="col-span-2"><span className="text-neutral-500 dark:text-neutral-400">Bank accounts:</span>{' '}
                    <span className="font-medium">{bankShadows.filter(s => chosenShadowIds.includes(s.id)).map(s => `${s.bankAccountNumber} (${s.bankName})`).join(', ') || 'None'}</span>
                  </div>
                </div>
              </div>

              {/* Hierarchy Config */}
              {formData.configureHierarchy && (
                <div className="border-t pt-4">
                  <h4 className="label mb-2">Hierarchy Configuration</h4>
                  <div className="grid grid-cols-2 gap-x-4 gap-y-2 text-body-sm">
                    <div><span className="text-neutral-500 dark:text-neutral-400">Template:</span> <span className="font-medium">{HIERARCHY_TEMPLATES.find(t => t.id === formData.defaultHierarchyTemplate)?.name ?? 'Standard'}</span></div>
                    <div><span className="text-neutral-500 dark:text-neutral-400">Depth:</span> <span className="font-medium">{formData.hierarchyDepth} levels</span></div>
                  </div>
                </div>
              )}

              {/* Wallet Limits */}
              {needsWalletConfig && (
                <div className="border-t pt-4">
                  <h4 className="label mb-2">Wallet Limits</h4>
                  <div className="grid grid-cols-3 gap-2 text-caption">
                    {formData.defaultPerTransactionLimit && (
                      <div className="p-2 bg-surface-muted rounded-md">
                        <span className="text-neutral-500 dark:text-neutral-400">Per Txn:</span>
                        <span className="font-medium ml-1">{formatCurrency(formData.defaultPerTransactionLimit, formData.currencyCode)}</span>
                      </div>
                    )}
                    {formData.defaultDailyLimit && (
                      <div className="p-2 bg-surface-muted rounded-md">
                        <span className="text-neutral-500 dark:text-neutral-400">Daily:</span>
                        <span className="font-medium ml-1">{formatCurrency(formData.defaultDailyLimit, formData.currencyCode)}</span>
                      </div>
                    )}
                    {formData.defaultMonthlyLimit && (
                      <div className="p-2 bg-surface-muted rounded-md">
                        <span className="text-neutral-500 dark:text-neutral-400">Monthly:</span>
                        <span className="font-medium ml-1">{formatCurrency(formData.defaultMonthlyLimit, formData.currencyCode)}</span>
                      </div>
                    )}
                    {formData.defaultMaxBalance && (
                      <div className="p-2 bg-surface-muted rounded-md">
                        <span className="text-neutral-500 dark:text-neutral-400">Max Balance:</span>
                        <span className="font-medium ml-1">{formatCurrency(formData.defaultMaxBalance, formData.currencyCode)}</span>
                      </div>
                    )}
                  </div>
                  <div className="flex flex-wrap gap-2 mt-2">
                    {formData.kycRequired && <Badge variant="warning" size="sm">KYC Level {formData.minKycLevel || 1}</Badge>}
                    {formData.allowTopup && <Badge variant="success" size="sm">Topup</Badge>}
                    {formData.allowWithdrawal && <Badge variant="success" size="sm">Withdrawal</Badge>}
                    {formData.allowTransfer && <Badge variant="success" size="sm">Transfer</Badge>}
                  </div>
                </div>
              )}

              {/* VIBAN Config */}
              {formData.configureViban && (
                <div className="border-t pt-4">
                  <h4 className="label mb-2">VIBAN Configuration</h4>
                  <div className="grid grid-cols-2 gap-x-4 gap-y-2 text-body-sm">
                    <div><span className="text-neutral-500 dark:text-neutral-400">Strategy:</span> <span className="font-medium">{vibanStrategyConfig[formData.vibanGenerationStrategy]?.label}</span></div>
                    <div><span className="text-neutral-500 dark:text-neutral-400">Prefix:</span> <span className="font-mono">{formData.vibanPrefix || 'Not set'}</span></div>
                    {selectedPool && <div className="col-span-2"><span className="text-neutral-500 dark:text-neutral-400">Pool:</span> <span className="font-medium">{selectedPool.poolName}</span></div>}
                  </div>
                </div>
              )}

              {/* Wallet Fees */}
              {needsWalletConfig && walletCharges && (
                <div className="border-t pt-4">
                  <h4 className="label mb-2 flex items-center gap-2">
                    Wallet Fees
                  </h4>
                  {/* Transaction Fees */}
                  <div className="grid grid-cols-3 gap-2 text-caption mb-2">
                    <div className="p-2 bg-surface-muted rounded-md">
                      <span className="text-neutral-500 dark:text-neutral-400">Topup:</span>
                      <span className="font-medium ml-1">
                        {chargeOverrides.topup.waived ? 'Waived' : 
                          `${chargeOverrides.topup.percent ?? walletCharges.topup.percentage}% + ${formData.currencyCode} ${chargeOverrides.topup.flat ?? walletCharges.topup.fixed}`}
                      </span>
                      {(chargeOverrides.topup.percent !== undefined || chargeOverrides.topup.flat !== undefined) && !chargeOverrides.topup.waived && <Badge variant="info" size="sm" className="ml-1">Override</Badge>}
                    </div>
                    <div className="p-2 bg-surface-muted rounded-md">
                      <span className="text-neutral-500 dark:text-neutral-400">Withdrawal:</span>
                      <span className="font-medium ml-1">
                        {chargeOverrides.withdrawal.waived ? 'Waived' : 
                          `${chargeOverrides.withdrawal.percent ?? walletCharges.withdrawal.percentage}% + ${formData.currencyCode} ${chargeOverrides.withdrawal.flat ?? walletCharges.withdrawal.fixed}`}
                      </span>
                      {(chargeOverrides.withdrawal.percent !== undefined || chargeOverrides.withdrawal.flat !== undefined) && !chargeOverrides.withdrawal.waived && <Badge variant="info" size="sm" className="ml-1">Override</Badge>}
                    </div>
                    <div className="p-2 bg-surface-muted rounded-md">
                      <span className="text-neutral-500 dark:text-neutral-400">Transfer:</span>
                      <span className="font-medium ml-1">
                        {chargeOverrides.transfer.waived ? 'Waived' : 
                          `${chargeOverrides.transfer.percent ?? walletCharges.transfer.percentage}% + ${formData.currencyCode} ${chargeOverrides.transfer.flat ?? walletCharges.transfer.fixed}`}
                      </span>
                      {(chargeOverrides.transfer.percent !== undefined || chargeOverrides.transfer.flat !== undefined) && !chargeOverrides.transfer.waived && <Badge variant="info" size="sm" className="ml-1">Override</Badge>}
                    </div>
                  </div>
                  {/* Fixed Fees */}
                  <div className="grid grid-cols-3 gap-2 text-caption">
                    <div className="p-2 bg-surface-muted rounded-md">
                      <span className="text-neutral-500 dark:text-neutral-400">Issuance:</span>
                      <span className="font-medium ml-1">
                        {chargeOverrides.issuance.waived ? 'Waived' : `${formData.currencyCode} ${chargeOverrides.issuance.flat ?? walletCharges.issuance.fixed}`}
                      </span>
                      {chargeOverrides.issuance.flat !== undefined && !chargeOverrides.issuance.waived && <Badge variant="info" size="sm" className="ml-1">Override</Badge>}
                    </div>
                    <div className="p-2 bg-surface-muted rounded-md">
                      <span className="text-neutral-500 dark:text-neutral-400">Monthly:</span>
                      <span className="font-medium ml-1">
                        {chargeOverrides.monthly.waived ? 'Waived' : `${formData.currencyCode} ${chargeOverrides.monthly.flat ?? walletCharges.monthly.fixed}`}
                      </span>
                      {chargeOverrides.monthly.flat !== undefined && !chargeOverrides.monthly.waived && <Badge variant="info" size="sm" className="ml-1">Override</Badge>}
                    </div>
                    <div className="p-2 bg-surface-muted rounded-md">
                      <span className="text-neutral-500 dark:text-neutral-400">Inactivity:</span>
                      <span className="font-medium ml-1">
                        {chargeOverrides.inactivity.waived ? 'Waived' : `${formData.currencyCode} ${chargeOverrides.inactivity.flat ?? walletCharges.inactivity.fixed}`}
                      </span>
                      {chargeOverrides.inactivity.flat !== undefined && !chargeOverrides.inactivity.waived && <Badge variant="info" size="sm" className="ml-1">Override</Badge>}
                    </div>
                  </div>
                </div>
              )}

              {/* Account numbering */}
              <div className="border-t pt-4">
                <h4 className="label mb-2">Account numbering</h4>
                <div className="grid grid-cols-2 gap-x-4 gap-y-2 text-body-sm">
                  <div><span className="text-neutral-500 dark:text-neutral-400">Prefix:</span> <span className="font-mono">{formData.vaPrefix || 'Automatic'}</span></div>
                  <div><span className="text-neutral-500 dark:text-neutral-400">Format:</span> <span className="font-mono">{formData.vaFormat || 'Automatic'}</span></div>
                </div>
              </div>
            </div>

            <div className="bg-success-50 border border-success-200 rounded-lg p-3 flex items-start gap-2 dark:bg-success-500/10 dark:border-success-500/30">
              <CheckCircle className="w-4 h-4 text-success-600 mt-0.5 dark:text-success-300" />
              <p className="text-body-sm text-success-700 dark:text-success-300">
                Ready to create program. Click "Create Program" to proceed.
              </p>
            </div>
          </div>
        )}

        {/* ================================================================ */}
        {/* Navigation Buttons */}
        {/* ================================================================ */}
        <div className="flex justify-between pt-4 border-t">
          {step > 1 ? (
            <Button variant="ghost" onClick={() => setStep(step - 1)}>
              Back
            </Button>
          ) : (
            <div />
          )}
          <div className="flex gap-2">
            <Button variant="ghost" onClick={onClose}>Cancel</Button>
            {step < totalSteps ? (
              <Button
                onClick={() => setStep(step + 1)}
                disabled={!canProceed()}
              >
                Next
              </Button>
            ) : (
              <Button
                onClick={handleSubmit}
                disabled={loading || !canProceed() || hasErrors}
                loading={loading}
              >
                {isEdit ? 'Save Changes' : 'Create Program'}
              </Button>
            )}
          </div>
        </div>
      </div>
    </Modal>
  );
};
