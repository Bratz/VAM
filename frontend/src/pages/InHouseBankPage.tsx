import React, { useState, useEffect, useCallback } from 'react';
import {
  Building2, Users, ArrowLeftRight, ArrowUpRight, ArrowDownRight, TrendingUp, TrendingDown,
  Wallet, DollarSign, RefreshCw, Settings, Plus, Eye, ChevronRight, Calendar,
  AlertCircle, Layers, GitBranch, Target, Loader2, X,
  FileText, Search, Filter, ChevronLeft, Download, MoreHorizontal,
  Briefcase
} from 'lucide-react';
import { Card, Button, Badge, Input, Select, EmptyState, StatTile } from '../components/ui';
import { CurrencyPicker } from '../components/ui/CurrencyPicker';
import { Modal } from '../components/ui/enhanced';
import { formatCurrency, formatDate, cn } from '../utils';
import { TileAmount } from '../components/TileAmount';
import api, { corporatesApi, programsApi } from '../services/api';
import { usePageHeaderActions } from '../context/PageHeaderContext';
import { Page } from '../components/layout/Page';
import { ScopeSelector } from '../components/layout/ScopeSelector';
import { StatStrip } from '../components/layout/StatStrip';

// ============================================================================
// API CONFIGURATION - UNIFIED ENDPOINTS
// ============================================================================

const API_BASE = '/api/v1/ihb';

interface ApiResponse<T> { 
  success: boolean; 
  data: T; 
  message?: string; 
}

// Use the existing apiClient from services/api.ts
const apiClient = api;

// ============================================================================
// TYPES - Aligned with Backend DTOs
// ============================================================================

interface Corporate {
  id: string;
  corporateId: string;
  legalName: string;
  tradeName?: string;
}

interface Program {
  id: string;
  programCode: string;
  programName: string;
  programType: 'COLLECTION' | 'WALLET' | 'IHB' | 'PAYABLES' | 'VIBAN' | 'ESCROW';
  corporateId?: string;
  currencyCode?: string;
}

interface IhbEntity {
  id: string;
  entityCode: string;
  entityName: string;
  entityType: 'PARENT' | 'SUBSIDIARY' | 'BRANCH' | 'DIVISION' | 'JOINT_VENTURE';
  corporateId: string;
  ihbEnabled: boolean;
  ihbCreditLimit: number;
  ihbCurrentExposure: number;
  ihbAvailableLimit: number;
  lendingRateSpread: number;
  borrowingRateSpread: number;
  canLend: boolean;
  canBorrow: boolean;
  settlementVaId?: string;
  ihbCurrency: string;
  totalLentOut: number;
  totalDeposited: number;
  netIhbPosition: number;
  utilizationPercent: number;
  limitWarning: boolean;
  limitBreached: boolean;
}

interface IhbCurrentAccount {
  // Identity
  accountId: string;
  accountNumber: string;
  accountName: string;
  currencyCode: string;

  // IHB Participation Flag (NEW - TRANSACTION VA with this flag)
  ihbParticipant: boolean;
  ihbEnabledAt?: string;
  ihbEnabledBy?: string;
  accountCategory: string;  // Now TRANSACTION instead of INTERCOMPANY

  // Participant Info
  participantEntityId: string;
  participantEntityCode?: string;
  participantEntityName?: string;

  // Balances
  currentBalance: number;
  availableBalance: number;
  positionType: 'CREDIT' | 'DEBIT' | 'NEUTRAL';

  // Credit/Overdraft
  creditLimit: number;
  creditLimitUsed: number;
  creditLimitAvailable: number;

  // Accrued Interest
  accruedCreditInterest: number;
  accruedDebitInterest: number;
  netAccruedInterest: number;

  // Interest Rates
  creditRate: number;
  debitRate: number;
  penaltyRate: number;

  // Interest Config
  internalInterestConfigId?: string;

  // IHB Sweep Configuration
  ihbSweepEnabled?: boolean;
  targetCashBalance?: number;
  ihbSweepFrequency?: string;

  // Status
  status: string;

  // Treasury Link (parentAccountId is preferred, treasuryPoolVaId is deprecated)
  parentAccountId?: string;       // Links to Treasury's Settlement VA
  treasuryPoolVaId?: string;      // @deprecated - use parentAccountId
  treasuryEntityCode?: string;    // Treasury center entity code
}

interface TreasuryRates {
  treasuryCenterId?: string;
  treasuryCenterCode?: string;
  treasuryCenterName?: string;
  ihbCurrency: string;
  // Lending rates (what borrowers pay)
  lendingBaseRate: number;
  lendingBaseRateType: string;
  treasuryLendingSpread: number;
  indicativeLendingRate: number;
  // Deposit rates (what depositors earn)
  depositBaseRate: number;
  depositBaseRateType: string;
  treasuryDepositSpread: number;
  indicativeDepositRate: number;
  // Limits
  minLoanAmount: number;
  maxLoanAmount?: number;
  minDepositAmount: number;
  // Config info
  hasInterestConfig: boolean;
  dayCountConvention?: string;
  compoundingFrequency?: string;
  settlementFrequency?: string;
}

// ============================================================================
// IHB UNIFIED API SERVICE
// ============================================================================

const ihbUnifiedApi = {
  // Entity Management
  getAllEntities: () =>
    fetch(`${API_BASE}/entities`).then(r => r.json()) as Promise<ApiResponse<IhbEntity[]>>,

  getEntitiesByCorporate: (corporateId: string) =>
    fetch(`${API_BASE}/corporate/${corporateId}/entities`).then(r => r.json()) as Promise<ApiResponse<IhbEntity[]>>,

  getEntityById: (entityId: string) =>
    fetch(`${API_BASE}/entities/${entityId}`).then(r => r.json()) as Promise<ApiResponse<IhbEntity>>,

  enableIhb: (entityId: string, data: { creditLimit: number; ihbCurrency: string; canLend?: boolean; canBorrow?: boolean; lendingRateSpread?: number; borrowingRateSpread?: number }) =>
    fetch(`${API_BASE}/entities/${entityId}/enable`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data)
    }).then(r => r.json()) as Promise<ApiResponse<IhbEntity>>,

  updateSettings: (entityId: string, data: any) =>
    fetch(`${API_BASE}/entities/${entityId}/settings`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data)
    }).then(r => r.json()) as Promise<ApiResponse<IhbEntity>>,

  disableIhb: (entityId: string) =>
    fetch(`${API_BASE}/entities/${entityId}/disable`, { method: 'POST' }).then(r => r.json()) as Promise<ApiResponse<void>>,

  getEntityPosition: (entityId: string) =>
    fetch(`${API_BASE}/entities/${entityId}/position`).then(r => r.json()),

  // Treasury Rates
  getTreasuryRates: (corporateId: string) =>
    fetch(`${API_BASE}/corporate/${corporateId}/treasury-rates`).then(r => r.json()) as Promise<ApiResponse<TreasuryRates>>,

  // IHB Current Accounts (Creates TRANSACTION VA with ihbParticipant=true)
  createCurrentAccount: (data: {
    participantEntityId: string;
    currencyCode: string;
    programId?: string;
    creditLimit?: number;
    creditRate?: number;
    debitRate?: number;
    penaltyRate?: number;
    // Interest Configuration
    interestConfigId?: string;
    // IHB Sweep Configuration
    ihbSweepEnabled?: boolean;
    targetCashBalance?: number;
    ihbSweepFrequency?: string;
    // VA Name override
    vaName?: string;
  }) =>
    fetch(`${API_BASE}/current-account`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data)
    }).then(r => r.json()) as Promise<ApiResponse<any>>,

  getCurrentAccountsByCorporate: (corporateId: string) =>
    fetch(`${API_BASE}/corporate/${corporateId}/current-accounts`).then(r => r.json()) as Promise<ApiResponse<IhbCurrentAccount[]>>,

  getCurrentAccountPosition: (accountId: string) =>
    fetch(`${API_BASE}/current-account/${accountId}/position`).then(r => r.json()) as Promise<ApiResponse<IhbCurrentAccount>>,

  depositToCurrentAccount: (accountId: string, data: { amount: number; description?: string }) =>
    fetch(`${API_BASE}/current-account/${accountId}/deposit`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data)
    }).then(r => r.json()) as Promise<ApiResponse<IhbCurrentAccount>>,

  withdrawFromCurrentAccount: (accountId: string, data: { amount: number; description?: string }) =>
    fetch(`${API_BASE}/current-account/${accountId}/withdraw`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data)
    }).then(r => r.json()) as Promise<ApiResponse<IhbCurrentAccount>>,

  calculateCurrentAccountInterest: () =>
    fetch(`${API_BASE}/current-accounts/calculate-interest`, { method: 'POST' }).then(r => r.json()),

  postCurrentAccountInterest: () =>
    fetch(`${API_BASE}/current-accounts/post-interest`, { method: 'POST' }).then(r => r.json()),
};

// ============================================================================
// SUB-COMPONENTS
// ============================================================================

// ============================================================================
// CORPORATE & PROGRAM SELECTOR BAR
// ============================================================================

const programTypeConfig: Record<string, { label: string; color: string }> = {
  COLLECTION: { label: 'Collection', color: 'text-success-600 dark:text-success-300' },
  WALLET: { label: 'Wallet', color: 'text-primary-600 dark:text-primary-200' },
  IHB: { label: 'In-House Bank', color: 'text-cat-2' },
  PAYABLES: { label: 'Payables', color: 'text-warning-600 dark:text-warning-300' },
  VIBAN: { label: 'VIBAN', color: 'text-info-600 dark:text-info-300' },
  ESCROW: { label: 'Escrow', color: 'text-cat-3' },
};

// Picker now uses the shared `<ScopeSelector mode="corporate-program">`
// primitive from components/layout/. The inline `SelectorBar` (Desktop +
// Mobile variants) is removed. IHB-specific filter (only IHB-type programs)
// is applied at the call site before passing into ScopeSelector.

const EntityCard: React.FC<{
  entity: IhbEntity;
  onViewPosition?: () => void;
  onCreateCurrentAccount?: () => void;
}> = ({ entity, onViewPosition, onCreateCurrentAccount }) => {
  // Safe number handling - default to 0 for null/undefined
  const netPosition = entity.netIhbPosition ?? 0;
  const totalLentOut = entity.totalLentOut ?? 0;
  const totalDeposited = entity.totalDeposited ?? 0;
  const creditLimit = entity.ihbCreditLimit ?? 0;
  const currentExposure = entity.ihbCurrentExposure ?? 0;
  const availableLimit = entity.ihbAvailableLimit ?? 0;
  const utilizationPercent = entity.utilizationPercent ?? 0;
  const lendingSpread = entity.lendingRateSpread ?? 0;
  const borrowingSpread = entity.borrowingRateSpread ?? 0;
  const currency = entity.ihbCurrency || 'AED';

  const isPositive = netPosition >= 0;
  const utilizationColor = entity.limitBreached ? 'error' : entity.limitWarning ? 'warning' : 'success';

  // Determine entity role: Treasury Center vs IHB Participant
  const isTreasuryCenter = entity.canLend && !entity.canBorrow;
  const isParticipant = entity.canBorrow;
  const isDualRole = entity.canLend && entity.canBorrow;

  return (
    <Card hover className={cn(
      isTreasuryCenter && 'ring-2 ring-warning-200 bg-gradient-to-br from-warning-50/50 to-white'
    )}>
      <div className="p-4">
        {/* Header with Role Indicator */}
        <div className="flex items-start justify-between mb-4">
          <div className="flex items-center gap-3">
            <div className={cn(
              'w-10 h-10 rounded-xl flex items-center justify-center',
              isTreasuryCenter ? 'bg-warning-100 dark:bg-warning-500/20' : isPositive ? 'bg-success-100 dark:bg-success-500/20' : 'bg-error-100 dark:bg-error-500/20'
            )}>
              {isTreasuryCenter ? (
                <Building2 className="w-5 h-5 text-warning-600 dark:text-warning-300" />
              ) : isPositive ? (
                <TrendingUp className="w-5 h-5 text-success-600 dark:text-success-300" />
              ) : (
                <TrendingDown className="w-5 h-5 text-error-600 dark:text-error-300" />
              )}
            </div>
            <div>
              <p className="text-sm font-semibold text-primary-900 tracking-tight dark:text-neutral-50">{entity.entityName}</p>
              <p className="text-xs text-neutral-500 dark:text-neutral-400">{entity.entityCode} • {entity.entityType}</p>
            </div>
          </div>
          <div className="flex flex-col items-end gap-1">
            {isTreasuryCenter && (
              <Badge variant="warning" size="sm" className="bg-warning-100 text-warning-700 border-warning-200 dark:bg-warning-500/20 dark:text-warning-300 dark:border-warning-500/30">
                Treasury Center
              </Badge>
            )}
            {isDualRole && (
              <>
                <Badge variant="success" size="sm">Lender</Badge>
                <Badge variant="info" size="sm">Borrower</Badge>
              </>
            )}
            {!isTreasuryCenter && !isDualRole && entity.canBorrow && (
              <Badge variant="info" size="sm">Borrower</Badge>
            )}
          </div>
        </div>

        <div className="space-y-3">
          {/* Treasury Center View - Focus on Lending Portfolio */}
          {isTreasuryCenter ? (
            <>
              {/* Lending Portfolio Summary */}
              <div className="bg-warning-50 rounded-lg p-3 border border-warning-100 dark:bg-warning-500/10 dark:border-warning-500/30">
                <p className="text-xs font-semibold text-warning-700 uppercase tracking-wide mb-2 dark:text-warning-300">Lending Portfolio</p>
                <div className="flex justify-between items-baseline">
                  <span className="text-xs text-warning-600 dark:text-warning-300">Total Lent Out</span>
                  <span className="text-lg font-bold text-warning-900">{formatCurrency(totalLentOut, currency)}</span>
                </div>
                <div className="flex justify-between items-baseline mt-1">
                  <span className="text-xs text-warning-600 dark:text-warning-300">Deposits Received</span>
                  <span className="text-sm font-semibold text-warning-800 dark:text-warning-300">{formatCurrency(totalDeposited, currency)}</span>
                </div>
              </div>

              {/* Net Position */}
              <div className="flex justify-between items-baseline">
                <span className="label">Net IHB Position</span>
                <span className={cn('text-lg font-bold tracking-tight', isPositive ? 'text-success-600 dark:text-success-300' : 'text-error-600 dark:text-error-300')}>
                  {formatCurrency(netPosition, currency)}
                </span>
              </div>

              {/* Lending Rate */}
              <div className="pt-3 border-t border-neutral-200 dark:border-primary-800">
                <div className="flex justify-between text-sm">
                  <span className="text-neutral-500 dark:text-neutral-400">Lending Spread (Earns)</span>
                  <span className="font-medium text-success-600 dark:text-success-300">+{lendingSpread.toFixed(2)}%</span>
                </div>
              </div>
            </>
          ) : (
            <>
              {/* Participant View - Focus on Borrowing Capacity */}
              <div className="flex justify-between items-baseline">
                <span className="label">Net Position</span>
                <span className={cn('text-lg font-bold tracking-tight', isPositive ? 'text-success-600 dark:text-success-300' : 'text-error-600 dark:text-error-300')}>
                  {formatCurrency(netPosition, currency)}
                </span>
              </div>

              <div className="grid grid-cols-2 gap-2 text-sm">
                {entity.canLend && (
                  <div className="flex justify-between">
                    <span className="text-neutral-500 dark:text-neutral-400">Lent Out</span>
                    <span className="font-medium text-primary-900 dark:text-neutral-50">{formatCurrency(totalLentOut, currency)}</span>
                  </div>
                )}
                <div className="flex justify-between">
                  <span className="text-neutral-500 dark:text-neutral-400">Deposited</span>
                  <span className="font-medium text-success-600 dark:text-success-300">{formatCurrency(totalDeposited, currency)}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-neutral-500 dark:text-neutral-400">Borrowed</span>
                  <span className="font-medium text-error-600 dark:text-error-300">{formatCurrency(currentExposure, currency)}</span>
                </div>
              </div>

              {/* Credit Limit & Utilization */}
              <div className="pt-3 border-t border-neutral-200 dark:border-primary-800">
                <div className="flex justify-between text-sm mb-2">
                  <span className="text-neutral-500 dark:text-neutral-400">Credit Limit</span>
                  <span className="font-medium text-primary-900 dark:text-neutral-50">{formatCurrency(creditLimit, currency)}</span>
                </div>
                <div className="flex justify-between text-sm mb-2">
                  <span className="text-neutral-500 dark:text-neutral-400">Available</span>
                  <span className="font-medium text-success-600 dark:text-success-300">{formatCurrency(availableLimit, currency)}</span>
                </div>
                <div className="flex justify-between text-sm mb-2">
                  <span className="text-neutral-500 dark:text-neutral-400">Utilized</span>
                  <span className={cn(
                    'font-medium',
                    utilizationColor === 'error' ? 'text-error-600 dark:text-error-300' :
                    utilizationColor === 'warning' ? 'text-warning-600 dark:text-warning-300' : 'text-neutral-600 dark:text-neutral-300'
                  )}>
                    {utilizationPercent.toFixed(1)}%
                  </span>
                </div>
                <div className="w-full bg-neutral-200 rounded-full h-2 dark:bg-primary-800">
                  <div
                    className={cn(
                      'h-2 rounded-full transition-all',
                      utilizationColor === 'error' ? 'bg-error-500' :
                      utilizationColor === 'warning' ? 'bg-warning-500' : 'bg-success-500'
                    )}
                    style={{ width: `${Math.min(utilizationPercent, 100)}%` }}
                  />
                </div>
              </div>

              {/* Rate Spreads */}
              <div className="pt-3 border-t border-neutral-200 dark:border-primary-800">
                <div className="grid grid-cols-2 gap-2 text-sm">
                  {entity.canLend && (
                    <div className="flex justify-between">
                      <span className="text-neutral-500 dark:text-neutral-400">Lending</span>
                      <span className="font-medium text-success-600 dark:text-success-300">+{lendingSpread.toFixed(2)}%</span>
                    </div>
                  )}
                  <div className="flex justify-between">
                    <span className="text-neutral-500 dark:text-neutral-400">Borrowing</span>
                    <span className="font-medium text-error-600 dark:text-error-300">+{borrowingSpread.toFixed(2)}%</span>
                  </div>
                </div>
              </div>
            </>
          )}
        </div>

        {/* Action Buttons */}
        <div className="flex flex-wrap gap-2 mt-4 pt-3 border-t border-neutral-200 dark:border-primary-800">
          {/* View Position - available for all entities */}
          {onViewPosition && (
            <Button variant="ghost" size="sm" className="flex-1" onClick={onViewPosition}>
              <Eye className="w-4 h-4 mr-1" /> Position
            </Button>
          )}
          {/* Create Current Account - for Participants (canBorrow) */}
          {isParticipant && onCreateCurrentAccount && !entity.settlementVaId && (
            <Button variant="ghost" size="sm" className="flex-1 text-cat-2 hover:bg-cat-2-soft dark:hover:bg-cat-2/15" onClick={onCreateCurrentAccount}>
              <Wallet className="w-4 h-4 mr-1" /> Open Account
            </Button>
          )}
        </div>
      </div>
    </Card>
  );
};

// ============================================================================
// MAIN COMPONENT
// ============================================================================

const InHouseBankPage: React.FC = () => {
  // State
  const [activeTab, setActiveTab] = useState<'overview' | 'entities' | 'current-accounts'>('overview');
  const [corporates, setCorporates] = useState<Corporate[]>([]);
  const [programs, setPrograms] = useState<Program[]>([]);
  const [selectedCorporateId, setSelectedCorporateId] = useState<string>('');
  const [selectedProgramId, setSelectedProgramId] = useState<string>('');
  const [entities, setEntities] = useState<IhbEntity[]>([]);
  const [currentAccounts, setCurrentAccounts] = useState<IhbCurrentAccount[]>([]);
  const [treasuryRates, setTreasuryRates] = useState<TreasuryRates | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingSelectors, setLoadingSelectors] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Modals
  const [showPositionModal, setShowPositionModal] = useState(false);
  const [showCurrentAccountModal, setShowCurrentAccountModal] = useState(false);
  const [selectedEntity, setSelectedEntity] = useState<IhbEntity | null>(null);
  const [selectedCurrentAccount, setSelectedCurrentAccount] = useState<IhbCurrentAccount | null>(null);
  const [, setEntityPosition] = useState<any>(null);
  const [loadingPosition, setLoadingPosition] = useState(false);

  // Form state - Enhanced for backend alignment
  const [currentAccountForm, setCurrentAccountForm] = useState({
    participantEntityId: '',
    currencyCode: 'AED',
    creditLimit: '',
    creditRate: '',
    debitRate: '',
    penaltyRate: '',
    // New fields for TRANSACTION VA with ihbParticipant=true
    ihbSweepEnabled: false,
    targetCashBalance: '',
    ihbSweepFrequency: 'DAILY',
    vaName: '',
  });

  // Load corporates and programs on mount
  useEffect(() => {
    loadCorporatesAndPrograms();
  }, []);

  // Load data when corporate changes
  useEffect(() => {
    if (selectedCorporateId) {
      loadData();
    } else {
      // Clear data when no corporate selected
      setEntities([]);
      setCurrentAccounts([]);
      setTreasuryRates(null);
    }
  }, [selectedCorporateId]);

  const loadCorporatesAndPrograms = async () => {
    setLoadingSelectors(true);
    try {
      const [corporatesRes, programsRes] = await Promise.all([
        corporatesApi.getAll(),
        programsApi.getAll().catch(() => ({ data: { programs: [] } })),
      ]);

      // Extract corporates
      const corporateData = corporatesRes?.data || corporatesRes;
      const corporateList = Array.isArray(corporateData) ? corporateData : [];
      setCorporates(corporateList);

      // Extract programs - handle nested structure
      const programData = programsRes?.data;
      let programList: Program[] = [];
      if (programData?.programs && Array.isArray(programData.programs)) {
        programList = programData.programs;
      } else if (Array.isArray(programData)) {
        programList = programData;
      }
      setPrograms(programList);

      // Auto-select first corporate
      if (corporateList.length > 0) {
        setSelectedCorporateId(corporateList[0].id);
      }
    } catch (err) {
      console.error('Failed to load corporates/programs:', err);
      setError('Failed to load corporates');
    } finally {
      setLoadingSelectors(false);
    }
  };

  const loadData = useCallback(async () => {
    if (!selectedCorporateId) return;

    setLoading(true);
    setError(null);

    try {
      const [entitiesRes, currentAccountsRes, ratesRes] = await Promise.all([
        ihbUnifiedApi.getEntitiesByCorporate(selectedCorporateId),
        ihbUnifiedApi.getCurrentAccountsByCorporate(selectedCorporateId).catch(() => ({ data: [] })),
        ihbUnifiedApi.getTreasuryRates(selectedCorporateId).catch(() => null),
      ]);

      setEntities(entitiesRes?.data || []);
      setCurrentAccounts(currentAccountsRes?.data || []);
      setTreasuryRates(ratesRes?.data || null);
    } catch (err) {
      console.error('Failed to load IHB data:', err);
      setError('Failed to load IHB data. The unified API may not be deployed yet.');
    } finally {
      setLoading(false);
    }
  }, [selectedCorporateId]);

  // IHB toolbar
  usePageHeaderActions(
    () => (
      <Button
        size="sm"
        leftIcon={<Wallet className="w-4 h-4" />}
        onClick={() => setShowCurrentAccountModal(true)}
        disabled={!selectedCorporateId}
        className="text-cat-2 border-cat-2/20 hover:bg-cat-2-soft dark:border-cat-2/30 dark:hover:bg-cat-2/15"
      >
        New Current Account
      </Button>
    ),
    [selectedCorporateId]
  );

  // Handle creating IHB Current Account (TRANSACTION VA with ihbParticipant=true)
  const handleCreateCurrentAccount = async () => {
    if (!currentAccountForm.participantEntityId) {
      alert('Please select a participant entity');
      return;
    }

    try {
      const result = await ihbUnifiedApi.createCurrentAccount({
        participantEntityId: currentAccountForm.participantEntityId,
        currencyCode: currentAccountForm.currencyCode,
        creditLimit: currentAccountForm.creditLimit ? parseFloat(currentAccountForm.creditLimit) : undefined,
        creditRate: currentAccountForm.creditRate ? parseFloat(currentAccountForm.creditRate) : undefined,
        debitRate: currentAccountForm.debitRate ? parseFloat(currentAccountForm.debitRate) : undefined,
        penaltyRate: currentAccountForm.penaltyRate ? parseFloat(currentAccountForm.penaltyRate) : undefined,
        // New IHB fields
        ihbSweepEnabled: currentAccountForm.ihbSweepEnabled,
        targetCashBalance: currentAccountForm.targetCashBalance ? parseFloat(currentAccountForm.targetCashBalance) : undefined,
        ihbSweepFrequency: currentAccountForm.ihbSweepFrequency,
        vaName: currentAccountForm.vaName || undefined,
      });

      if (result?.success) {
        setShowCurrentAccountModal(false);
        setCurrentAccountForm({
          participantEntityId: '', currencyCode: 'AED', creditLimit: '',
          creditRate: '', debitRate: '', penaltyRate: '',
          ihbSweepEnabled: false, targetCashBalance: '', ihbSweepFrequency: 'DAILY', vaName: '',
        });
        await loadData();
        alert('IHB Current Account created successfully (TRANSACTION VA with ihbParticipant=true)');
      } else {
        alert(result?.message || 'Failed to create current account');
      }
    } catch (err) {
      console.error('Failed to create current account:', err);
      alert('Failed to create current account');
    }
  };

  // Handle quick current account creation from entity card
  const handleQuickCurrentAccount = (entity: IhbEntity) => {
    setCurrentAccountForm(prev => ({
      ...prev,
      participantEntityId: entity.id,
      currencyCode: entity.ihbCurrency || 'AED',
      creditLimit: entity.ihbCreditLimit?.toString() || '',
    }));
    setShowCurrentAccountModal(true);
  };

  // Helper to fill current account form with treasury rates
  const fillCurrentAccountRatesFromTreasury = () => {
    if (treasuryRates) {
      setCurrentAccountForm(prev => ({
        ...prev,
        currencyCode: treasuryRates.ihbCurrency || prev.currencyCode,
        creditRate: treasuryRates.indicativeDepositRate?.toString() || '',
        debitRate: treasuryRates.indicativeLendingRate?.toString() || '',
      }));
    }
  };

  // Handle viewing entity position
  const handleViewPosition = async (entity: IhbEntity) => {
    setSelectedEntity(entity);
    setShowPositionModal(true);
    setLoadingPosition(true);
    try {
      const result = await ihbUnifiedApi.getEntityPosition(entity.id);
      setEntityPosition(result?.data || result);
    } catch (err) {
      console.error('Failed to load entity position:', err);
      setEntityPosition(null);
    } finally {
      setLoadingPosition(false);
    }
  };

  // Calculated values
  const lenders = entities.filter(e => e.canLend);
  const borrowers = entities.filter(e => e.canBorrow);

  const tabs = [
    { id: 'overview', label: 'Overview' },
    { id: 'entities', label: 'IHB Entities', count: entities.length },
    { id: 'current-accounts', label: 'Current Accounts', count: currentAccounts.length },
  ];

  if (loadingSelectors && !corporates.length) {
    return (
      <div className="flex items-center justify-center h-96">
        <Loader2 className="w-8 h-8 animate-spin text-primary-600 dark:text-primary-200" />
      </div>
    );
  }

  return (
    <Page>
      {/* Corporate & Program Selector */}
      <ScopeSelector
        mode="corporate-program"
        corporates={corporates}
        // IHB page scopes to IHB-type programs only.
        programs={programs.filter(p => p.programType === 'IHB' && (!selectedCorporateId || p.corporateId === selectedCorporateId))}
        selectedCorporateId={selectedCorporateId}
        selectedProgramId={selectedProgramId}
        onCorporateChange={(id) => { setSelectedCorporateId(id); setSelectedProgramId(''); }}
        onProgramChange={setSelectedProgramId}
        loading={loadingSelectors}
        disableChildUntilParent
      />

      {/* Quick Actions migrated to Aperture Layout header. Context badges
          (selected Corporate / Program) stay on the page body since they're
          informational, not actionable. */}
      {(selectedCorporateId || selectedProgramId) && (
        <div className="flex items-center gap-2 animate-fade-in" style={{ animationDelay: '0.1s' }}>
          {selectedCorporateId && (
            <Badge variant="info" size="sm" className="px-3 py-1">
              <Building2 className="w-3 h-3 mr-1.5" />
              {corporates.find(c => c.id === selectedCorporateId)?.tradeName ||
               corporates.find(c => c.id === selectedCorporateId)?.legalName ||
               'Selected'}
            </Badge>
          )}
          {selectedProgramId && (
            <Badge variant="warning" size="sm" className="px-3 py-1">
              <Briefcase className="w-3 h-3 mr-1.5" />
              {programs.find(p => p.id === selectedProgramId)?.programName || 'Program'}
            </Badge>
          )}
        </div>
      )}

      {/* Error Banner */}
      {error && (
        <Card padding="sm" className="bg-error-50 border-error-200 animate-fade-in dark:bg-error-500/10 dark:border-error-500/30">
          <div className="flex items-center gap-3">
            <AlertCircle className="w-5 h-5 text-error-600 dark:text-error-300" />
            <p className="text-sm text-error-700 flex-1 dark:text-error-300">{error}</p>
            <button onClick={() => setError(null)} className="text-error-600 hover:text-error-800 dark:text-error-300">
              <X className="w-4 h-4" />
            </button>
          </div>
        </Card>
      )}

      {/* No Corporate Selected State */}
      {!selectedCorporateId && (
        <Card padding="md" className="bg-gradient-to-r from-neutral-50 via-white to-neutral-50 border-neutral-200 animate-fade-in dark:border-primary-800 dark:from-primary-900 dark:via-primary-900 dark:to-primary-900" style={{ animationDelay: '0.15s' }}>
          <div className="flex flex-col items-center justify-center py-8 text-center">
            <div className="w-16 h-16 rounded-2xl bg-warning-100 flex items-center justify-center mb-4 dark:bg-warning-500/20">
              <Building2 className="w-8 h-8 text-warning-600 dark:text-warning-300" />
            </div>
            <p className="section-title">Select a Corporate</p>
            <p className="text-sm text-neutral-500 mt-1 max-w-md dark:text-neutral-400">
              Choose a corporate from the selector above to view and manage its In-House Bank entities and current accounts.
            </p>
          </div>
        </Card>
      )}

      {/* Content when corporate is selected */}
      {selectedCorporateId && (
        <>
          {/* Treasury Rates Banner (if available) */}
          {treasuryRates && (
            <Card padding="sm" className="bg-gradient-to-r from-warning-50/50 via-white to-success-50/50 border-warning-200/60 animate-fade-in dark:from-primary-900 dark:via-primary-900 dark:to-primary-900" style={{ animationDelay: '0.15s' }}>
              <div className="flex items-center justify-between p-3">
                <div className="flex items-center gap-3">
                  <div className="w-10 h-10 rounded-xl bg-warning-100 flex items-center justify-center flex-shrink-0 dark:bg-warning-500/20">
                    <TrendingUp className="w-5 h-5 text-warning-600 dark:text-warning-300" />
                  </div>
                  <div>
                    <p className="text-xs font-medium text-warning-700 uppercase tracking-wide dark:text-warning-300">Treasury Indicative Rates</p>
                    <p className="text-sm text-neutral-700 mt-0.5 dark:text-neutral-200">
                      <span className="font-semibold text-warning-800 dark:text-warning-300">Lending: {treasuryRates.indicativeLendingRate?.toFixed(2) || 'N/A'}%</span>
                      <span className="mx-3 text-neutral-300 dark:text-neutral-600">|</span>
                      <span className="font-semibold text-success-700 dark:text-success-300">Deposit: {treasuryRates.indicativeDepositRate?.toFixed(2) || 'N/A'}%</span>
                    </p>
                  </div>
                </div>
                <div className="text-right text-xs text-neutral-500 dark:text-neutral-400">
                  <p>Base: {treasuryRates.lendingBaseRateType} {treasuryRates.lendingBaseRate}%</p>
                </div>
              </div>
            </Card>
          )}

          {/* Stats */}
          <StatStrip>
            <StatTile
              layout="row"
              tone="primary"
              icon={<Building2 className="w-5 h-5" />}
              label="IHB Entities"
              value={entities.length}
              sub={`${lenders.length} lenders, ${borrowers.length} borrowers`}
              loading={loading}
              delay="0.15s"
            />
            <StatTile
              layout="row"
              tone="info"
              icon={<Wallet className="w-5 h-5" />}
              label="Current Accounts"
              value={currentAccounts.length}
              sub="IHB participant VAs"
              loading={loading}
              delay="0.2s"
            />
          </StatStrip>

      {/* Tabs */}
      <Card padding="none" className="animate-fade-in" style={{ animationDelay: '0.35s' }}>
        <div className="border-b border-neutral-100 dark:border-primary-800/60">
          <div className="flex gap-1 p-2 overflow-x-auto">
            {tabs.map(tab => (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id as any)}
                className={cn(
                  'px-4 py-2 rounded-lg text-sm font-medium transition-all whitespace-nowrap',
                  activeTab === tab.id
                    ? 'bg-primary-100 text-primary-900 dark:bg-primary-700 dark:text-neutral-50'
                    : 'text-neutral-600 hover:bg-neutral-100 dark:text-neutral-300 dark:hover:bg-primary-800'
                )}
              >
                {tab.label}
                {tab.count !== undefined && (
                  <span className={cn(
                    'ml-2 px-2 py-0.5 rounded-full text-xs',
                    activeTab === tab.id ? 'bg-primary-200 text-primary-800 dark:text-neutral-100' : 'bg-neutral-200 text-neutral-600 dark:bg-primary-800 dark:text-neutral-300'
                  )}>
                    {tab.count}
                  </span>
                )}
              </button>
            ))}
          </div>
        </div>

        <div className="p-6">
          {/* Overview Tab */}
          {activeTab === 'overview' && (
            <div className="space-y-6">
              {/* Position Summary */}
              <StatStrip>
                <StatTile tone="success" icon={<TrendingUp className="w-5 h-5" />} label="Lender Entities" value={lenders.length} sub="Available to lend funds" />
                <StatTile tone="danger" icon={<TrendingDown className="w-5 h-5" />} label="Borrower Entities" value={borrowers.length} sub="Can borrow from IHB" />
              </StatStrip>
            </div>
          )}

          {/* Entities Tab */}
          {activeTab === 'entities' && (
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
              {entities.map(entity => (
                <EntityCard
                  key={entity.id}
                  entity={entity}
                  onViewPosition={() => handleViewPosition(entity)}
                  onCreateCurrentAccount={() => handleQuickCurrentAccount(entity)}
                />
              ))}
              {entities.length === 0 && (
                <div className="col-span-full text-center py-12">
                  <Building2 className="w-12 h-12 mx-auto mb-4 text-neutral-300 dark:text-neutral-600" />
                  <p className="text-neutral-500 dark:text-neutral-400">No IHB-enabled entities</p>
                  <p className="text-sm text-neutral-400 mt-1 dark:text-neutral-500">Enable IHB on Legal Entities to start intercompany operations</p>
                </div>
              )}
            </div>
          )}

          {/* Current Accounts Tab */}
          {activeTab === 'current-accounts' && (
            <div className="space-y-4">
              <div className="flex justify-between items-center">
                <p className="text-sm text-neutral-500 dark:text-neutral-400">{currentAccounts.length} IHB Current Accounts</p>
                <Button size="sm" leftIcon={<Plus className="w-4 h-4" />} onClick={() => setShowCurrentAccountModal(true)}>
                  New Current Account
                </Button>
              </div>
              {currentAccounts.length > 0 ? (
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                  {currentAccounts.map(account => (
                    <Card key={account.accountId} hover className="border-cat-2/10 dark:border-cat-2/30">
                      <div className="p-4">
                        {/* Account Header */}
                        <div className="flex items-start justify-between mb-3">
                          <div className="flex items-center gap-3">
                            <div className={cn(
                              'w-10 h-10 rounded-xl flex items-center justify-center',
                              account.positionType === 'CREDIT' ? 'bg-success-100 dark:bg-success-500/20' :
                              account.positionType === 'DEBIT' ? 'bg-error-100 dark:bg-error-500/20' : 'bg-neutral-100 dark:bg-primary-800'
                            )}>
                              <Wallet className={cn(
                                'w-5 h-5',
                                account.positionType === 'CREDIT' ? 'text-success-600 dark:text-success-300' :
                                account.positionType === 'DEBIT' ? 'text-error-600 dark:text-error-300' : 'text-neutral-600 dark:text-neutral-300'
                              )} />
                            </div>
                            <div>
                              <p className="text-sm font-semibold text-primary-900 dark:text-neutral-50">{account.participantEntityName || account.accountName}</p>
                              <p className="text-xs text-neutral-500 font-mono dark:text-neutral-400">{account.accountNumber}</p>
                            </div>
                          </div>
                          <div className="flex flex-col items-end gap-1">
                            <Badge
                              variant={account.positionType === 'CREDIT' ? 'success' : account.positionType === 'DEBIT' ? 'error' : 'neutral'}
                              size="sm"
                            >
                              {account.positionType}
                            </Badge>
                            {/* IHB Participant Badge */}
                            {account.ihbParticipant && (
                              <Badge variant="info" size="sm" className="text-xs">
                                IHB Participant
                              </Badge>
                            )}
                          </div>
                        </div>

                        {/* Account Category & Type */}
                        <div className="flex gap-2 mb-3">
                          <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-cat-2/10 text-cat-2 dark:bg-cat-2/15">
                            {account.accountCategory || 'TRANSACTION'}
                          </span>
                          {account.ihbSweepEnabled && (
                            <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-info-100 text-info-700 dark:bg-info-500/20 dark:text-info-300">
                              <RefreshCw className="w-3 h-3 mr-1" />
                              Sweep: {account.ihbSweepFrequency || 'DAILY'}
                            </span>
                          )}
                        </div>

                        {/* Balance Section */}
                        <div className="space-y-2">
                          <div className="flex justify-between items-baseline">
                            <span className="text-xs text-neutral-500 dark:text-neutral-400">Current Balance</span>
                            <span className={cn(
                              'text-lg font-bold',
                              account.currentBalance >= 0 ? 'text-success-600 dark:text-success-300' : 'text-error-600 dark:text-error-300'
                            )}>
                              {formatCurrency(account.currentBalance, account.currencyCode)}
                            </span>
                          </div>
                          <div className="flex justify-between text-sm">
                            <span className="text-neutral-500 dark:text-neutral-400">Available</span>
                            <span className="font-medium text-primary-900 dark:text-neutral-50">
                              {formatCurrency(account.availableBalance, account.currencyCode)}
                            </span>
                          </div>
                          <div className="flex justify-between text-sm">
                            <span className="text-neutral-500 dark:text-neutral-400">Credit Limit</span>
                            <span className="font-medium text-neutral-700 dark:text-neutral-200">
                              {formatCurrency(account.creditLimit, account.currencyCode)}
                            </span>
                          </div>
                        </div>

                        {/* Accrued Interest */}
                        {(account.accruedCreditInterest > 0 || account.accruedDebitInterest > 0) && (
                          <div className="mt-3 pt-3 border-t border-neutral-200 dark:border-primary-800">
                            <p className="label mb-2 dark:text-neutral-400">Accrued Interest</p>
                            <div className="grid grid-cols-2 gap-2 text-sm">
                              <div className="flex justify-between">
                                <span className="text-success-600 dark:text-success-300">Credit</span>
                                <span className="font-medium text-success-700 dark:text-success-300">
                                  {formatCurrency(account.accruedCreditInterest, account.currencyCode)}
                                </span>
                              </div>
                              <div className="flex justify-between">
                                <span className="text-error-600 dark:text-error-300">Debit</span>
                                <span className="font-medium text-error-700 dark:text-error-300">
                                  {formatCurrency(account.accruedDebitInterest, account.currencyCode)}
                                </span>
                              </div>
                            </div>
                            <div className="flex justify-between text-sm mt-1">
                              <span className="text-neutral-500 dark:text-neutral-400">Net</span>
                              <span className={cn(
                                'font-semibold',
                                account.netAccruedInterest >= 0 ? 'text-success-600 dark:text-success-300' : 'text-error-600 dark:text-error-300'
                              )}>
                                {formatCurrency(account.netAccruedInterest, account.currencyCode)}
                              </span>
                            </div>
                          </div>
                        )}

                        {/* Interest Rates */}
                        <div className="mt-3 pt-3 border-t border-neutral-200 dark:border-primary-800">
                          <div className="grid grid-cols-2 gap-2 text-xs">
                            <div className="flex justify-between">
                              <span className="text-neutral-500 dark:text-neutral-400">Credit Rate</span>
                              <span className="font-medium text-success-600 dark:text-success-300">{account.creditRate?.toFixed(2) || '0.00'}%</span>
                            </div>
                            <div className="flex justify-between">
                              <span className="text-neutral-500 dark:text-neutral-400">Debit Rate</span>
                              <span className="font-medium text-error-600 dark:text-error-300">{account.debitRate?.toFixed(2) || '0.00'}%</span>
                            </div>
                          </div>
                        </div>

                        {/* IHB Configuration Details */}
                        {(account.ihbSweepEnabled || account.targetCashBalance) && (
                          <div className="mt-3 pt-3 border-t border-neutral-200 dark:border-primary-800">
                            <p className="label mb-2 dark:text-neutral-400">IHB Config</p>
                            <div className="grid grid-cols-2 gap-2 text-xs">
                              {account.targetCashBalance !== undefined && account.targetCashBalance !== null && (
                                <div className="flex justify-between">
                                  <span className="text-neutral-500 dark:text-neutral-400">Target Balance</span>
                                  <span className="font-medium text-neutral-700 dark:text-neutral-200">
                                    {account.targetCashBalance === 0 ? 'Sweep All' : formatCurrency(account.targetCashBalance, account.currencyCode)}
                                  </span>
                                </div>
                              )}
                              {account.ihbEnabledAt && (
                                <div className="flex justify-between">
                                  <span className="text-neutral-500 dark:text-neutral-400">IHB Since</span>
                                  <span className="font-medium text-neutral-700 dark:text-neutral-200">
                                    {formatDate(account.ihbEnabledAt)}
                                  </span>
                                </div>
                              )}
                            </div>
                          </div>
                        )}

                        {/* Internal Interest Config Reference */}
                        {account.internalInterestConfigId && (
                          <div className="mt-2 text-xs text-neutral-400 truncate dark:text-neutral-500">
                            Config: {account.internalInterestConfigId.substring(0, 8)}...
                          </div>
                        )}
                      </div>
                    </Card>
                  ))}
                </div>
              ) : (
                <EmptyState
                  icon={<Wallet className="w-12 h-12" />}
                  title="No IHB Current Accounts"
                  description="Create current accounts for IHB participants to manage their intercompany balances"
                  action={<Button onClick={() => setShowCurrentAccountModal(true)} leftIcon={<Plus className="w-4 h-4" />}>New Current Account</Button>}
                />
              )}
            </div>
          )}
        </div>
      </Card>
        </>
      )}

      {/* Entity Position Modal */}
      <Modal
        isOpen={showPositionModal}
        onClose={() => { setShowPositionModal(false); setSelectedEntity(null); setEntityPosition(null); }}
        title={`Position: ${selectedEntity?.entityName || 'Entity'}`}
        size="lg"
      >
        {loadingPosition ? (
          <div className="flex items-center justify-center py-12">
            <Loader2 className="w-8 h-8 animate-spin text-primary-600 dark:text-primary-200" />
          </div>
        ) : selectedEntity && (
          <div className="space-y-5">
            {/* Entity Summary Header */}
            <div className={cn(
              "rounded-lg p-4 border",
              selectedEntity.canLend && !selectedEntity.canBorrow
                ? "bg-warning-50 border-warning-200 dark:border-warning-500/30 dark:bg-warning-500/10"
                : "bg-primary-50 border-primary-200 dark:border-primary-700 dark:bg-primary-500/10"
            )}>
              <div className="flex items-center gap-3 mb-3">
                <div className={cn(
                  "w-10 h-10 rounded-xl flex items-center justify-center",
                  selectedEntity.canLend && !selectedEntity.canBorrow ? "bg-warning-100 dark:bg-warning-500/20" : "bg-primary-100 dark:bg-primary-700"
                )}>
                  <Building2 className={cn(
                    "w-5 h-5",
                    selectedEntity.canLend && !selectedEntity.canBorrow ? "text-warning-600 dark:text-warning-300" : "text-primary-600 dark:text-primary-200"
                  )} />
                </div>
                <div>
                  <p className="text-sm font-semibold text-neutral-900 dark:text-neutral-50">{selectedEntity.entityName}</p>
                  <p className="text-xs text-neutral-500 dark:text-neutral-400">{selectedEntity.entityCode} • {selectedEntity.entityType}</p>
                </div>
                <div className="ml-auto flex gap-2">
                  {selectedEntity.canLend && <Badge variant="success" size="sm">Lender</Badge>}
                  {selectedEntity.canBorrow && <Badge variant="info" size="sm">Borrower</Badge>}
                </div>
              </div>
            </div>

            {/* Position Summary */}
            <StatStrip>
              <StatTile
                tone="success"
                label="Total Lent Out"
                value={<TileAmount value={selectedEntity.totalLentOut ?? 0} currency={selectedEntity.ihbCurrency || 'AED'} />}
              />
              <StatTile
                tone="info"
                label="Total Deposited"
                value={<TileAmount value={selectedEntity.totalDeposited ?? 0} currency={selectedEntity.ihbCurrency || 'AED'} />}
              />
              <StatTile
                tone="danger"
                label="Current Exposure"
                value={<TileAmount value={selectedEntity.ihbCurrentExposure ?? 0} currency={selectedEntity.ihbCurrency || 'AED'} />}
              />
              <StatTile
                tone={(selectedEntity.netIhbPosition ?? 0) >= 0 ? 'success' : 'danger'}
                label="Net IHB Position"
                value={<TileAmount value={selectedEntity.netIhbPosition ?? 0} currency={selectedEntity.ihbCurrency || 'AED'} />}
              />
            </StatStrip>

            {/* Credit Limit Section (for borrowers) */}
            {selectedEntity.canBorrow && (
              <div className="p-4 bg-neutral-50 rounded-lg border border-neutral-200 dark:bg-primary-950 dark:border-primary-800">
                <p className="text-sm font-semibold text-neutral-900 mb-3 dark:text-neutral-50">Credit Limit Status</p>
                <div className="grid grid-cols-3 gap-4 mb-3">
                  <div>
                    <p className="text-xs text-neutral-500 dark:text-neutral-400">Credit Limit</p>
                    <p className="text-sm font-semibold text-neutral-900 dark:text-neutral-50">
                      {formatCurrency(selectedEntity.ihbCreditLimit ?? 0, selectedEntity.ihbCurrency || 'AED')}
                    </p>
                  </div>
                  <div>
                    <p className="text-xs text-neutral-500 dark:text-neutral-400">Used</p>
                    <p className="text-sm font-semibold text-error-600 dark:text-error-300">
                      {formatCurrency(selectedEntity.ihbCurrentExposure ?? 0, selectedEntity.ihbCurrency || 'AED')}
                    </p>
                  </div>
                  <div>
                    <p className="text-xs text-neutral-500 dark:text-neutral-400">Available</p>
                    <p className="text-sm font-semibold text-success-600 dark:text-success-300">
                      {formatCurrency(selectedEntity.ihbAvailableLimit ?? 0, selectedEntity.ihbCurrency || 'AED')}
                    </p>
                  </div>
                </div>
                <div className="w-full bg-neutral-200 rounded-full h-3 dark:bg-primary-800">
                  <div
                    className={cn(
                      "h-3 rounded-full transition-all",
                      selectedEntity.limitBreached ? "bg-error-500" :
                      selectedEntity.limitWarning ? "bg-warning-500" : "bg-success-500"
                    )}
                    style={{ width: `${Math.min(selectedEntity.utilizationPercent ?? 0, 100)}%` }}
                  />
                </div>
                <div className="flex justify-between mt-1">
                  <p className="text-xs text-neutral-500 dark:text-neutral-400">Utilization</p>
                  <p className={cn(
                    "text-xs font-medium",
                    selectedEntity.limitBreached ? "text-error-600 dark:text-error-300" :
                    selectedEntity.limitWarning ? "text-warning-600 dark:text-warning-300" : "text-neutral-600 dark:text-neutral-300"
                  )}>
                    {(selectedEntity.utilizationPercent ?? 0).toFixed(1)}%
                  </p>
                </div>
              </div>
            )}

            {/* Rate Spreads */}
            <div className="p-4 bg-neutral-50 rounded-lg border border-neutral-200 dark:bg-primary-950 dark:border-primary-800">
              <p className="text-sm font-semibold text-neutral-900 mb-3 dark:text-neutral-50">Rate Spreads</p>
              <div className="grid grid-cols-2 gap-4">
                {selectedEntity.canLend && (
                  <div>
                    <p className="text-xs text-neutral-500 dark:text-neutral-400">Lending Spread (Earns)</p>
                    <p className="text-sm font-semibold text-success-600 dark:text-success-300">+{(selectedEntity.lendingRateSpread ?? 0).toFixed(2)}%</p>
                  </div>
                )}
                {selectedEntity.canBorrow && (
                  <div>
                    <p className="text-xs text-neutral-500 dark:text-neutral-400">Borrowing Spread (Pays)</p>
                    <p className="text-sm font-semibold text-error-600 dark:text-error-300">+{(selectedEntity.borrowingRateSpread ?? 0).toFixed(2)}%</p>
                  </div>
                )}
              </div>
            </div>

            {/* Actions */}
            <div className="flex gap-3 pt-4 border-t">
              <Button variant="outline" className="flex-1" onClick={() => setShowPositionModal(false)}>
                Close
              </Button>
            </div>
          </div>
        )}
      </Modal>

      {/* Create Current Account Modal */}
      <Modal
        isOpen={showCurrentAccountModal}
        onClose={() => setShowCurrentAccountModal(false)}
        title="Create IHB Current Account"
        size="lg"
      >
        <div className="space-y-5">
          {/* Description */}
          <div className="bg-cat-2-soft border border-cat-2/20 rounded-lg p-4 dark:border-cat-2/30 dark:bg-cat-2/15">
            <div className="flex items-start gap-3">
              <div className="w-10 h-10 rounded-xl bg-cat-2/10 flex items-center justify-center flex-shrink-0 dark:bg-cat-2/15">
                <Wallet className="w-5 h-5 text-cat-2" />
              </div>
              <div>
                <p className="text-sm font-semibold text-cat-2">IHB Current Account</p>
                <p className="text-xs text-cat-2 mt-1">
                  Creates a TRANSACTION VA with <span className="font-mono bg-cat-2/10 px-1 rounded dark:bg-cat-2/15">ihbParticipant=true</span>.
                  This account supports running balance with credit interest (positive balance) and
                  debit interest (overdraft). The IHB flag allows any operational VA to participate
                  in treasury interest schemes.
                </p>
              </div>
            </div>
          </div>

          {/* Treasury Rates Preview */}
          {treasuryRates && (
            <div className="bg-neutral-50 border border-neutral-200 rounded-lg p-4 dark:bg-primary-950 dark:border-primary-800">
              <div className="flex items-center justify-between">
                <div>
                  <p className="label">Treasury Rates</p>
                  <div className="flex gap-6 mt-2">
                    <div>
                      <p className="text-xs text-success-600 dark:text-success-300">Credit Rate (Earn)</p>
                      <p className="stat-value-sm text-success-700 dark:text-success-300">{treasuryRates.indicativeDepositRate?.toFixed(2) || 'N/A'}%</p>
                    </div>
                    <div>
                      <p className="text-xs text-error-600 dark:text-error-300">Debit Rate (Pay)</p>
                      <p className="stat-value-sm text-error-700 dark:text-error-300">{treasuryRates.indicativeLendingRate?.toFixed(2) || 'N/A'}%</p>
                    </div>
                  </div>
                </div>
                <Button variant="ghost" size="sm" onClick={fillCurrentAccountRatesFromTreasury} className="text-xs text-cat-2">
                  <TrendingUp className="w-3 h-3 mr-1" /> Use Treasury Rates
                </Button>
              </div>
            </div>
          )}

          {/* Participant Selection */}
          <div>
            <p className="text-xs font-semibold text-neutral-500 uppercase tracking-wide mb-3 dark:text-neutral-400">Participant</p>
            <div>
              <label className="field-label block mb-1">Entity *</label>
              <Select
                value={currentAccountForm.participantEntityId}
                onChange={(e) => {
                  const entity = entities.find(en => en.id === e.target.value);
                  setCurrentAccountForm(prev => ({
                    ...prev,
                    participantEntityId: e.target.value,
                    currencyCode: entity?.ihbCurrency || prev.currencyCode,
                    creditLimit: entity?.ihbCreditLimit?.toString() || prev.creditLimit,
                  }));
                }}
              >
                <option value="">Select participant entity...</option>
                {borrowers.map(e => (
                  <option key={e.id} value={e.id}>
                    {e.entityName} ({e.entityCode}) {e.settlementVaId ? '- Has Account' : ''}
                  </option>
                ))}
              </Select>
              <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">Only entities with canBorrow=true can have IHB current accounts</p>
            </div>
          </div>

          {/* Account Details */}
          <div>
            <p className="text-xs font-semibold text-neutral-500 uppercase tracking-wide mb-3 dark:text-neutral-400">Account Details</p>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="field-label block mb-1">Currency</label>
                <CurrencyPicker
                  value={currentAccountForm.currencyCode}
                  onChange={(c) => setCurrentAccountForm({ ...currentAccountForm, currencyCode: c })}
                  extra={['SAR']}
                />
              </div>
              <div>
                <label className="field-label block mb-1">Credit Limit (Overdraft)</label>
                <Input
                  type="number"
                  value={currentAccountForm.creditLimit}
                  onChange={(e) => setCurrentAccountForm({ ...currentAccountForm, creditLimit: e.target.value })}
                  placeholder="Leave empty for entity default"
                />
              </div>
            </div>
          </div>

          {/* Interest Rates (Optional Override) */}
          <div>
            <div className="flex items-center justify-between mb-3">
              <p className="text-xs font-semibold text-neutral-500 uppercase tracking-wide dark:text-neutral-400">Interest Rates (Optional Override)</p>
            </div>
            <div className="grid grid-cols-3 gap-4">
              <div>
                <label className="field-label block mb-1">Credit Rate (%)</label>
                <Input
                  type="number"
                  step="0.01"
                  value={currentAccountForm.creditRate}
                  onChange={(e) => setCurrentAccountForm({ ...currentAccountForm, creditRate: e.target.value })}
                  placeholder="Treasury default"
                />
                <p className="text-xs text-success-600 mt-1 dark:text-success-300">Earned on positive balance</p>
              </div>
              <div>
                <label className="field-label block mb-1">Debit Rate (%)</label>
                <Input
                  type="number"
                  step="0.01"
                  value={currentAccountForm.debitRate}
                  onChange={(e) => setCurrentAccountForm({ ...currentAccountForm, debitRate: e.target.value })}
                  placeholder="Treasury default"
                />
                <p className="text-xs text-error-600 mt-1 dark:text-error-300">Charged on overdraft</p>
              </div>
              <div>
                <label className="field-label block mb-1">Penalty Rate (%)</label>
                <Input
                  type="number"
                  step="0.01"
                  value={currentAccountForm.penaltyRate}
                  onChange={(e) => setCurrentAccountForm({ ...currentAccountForm, penaltyRate: e.target.value })}
                  placeholder="Optional"
                />
                <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">On limit breach</p>
              </div>
            </div>
            <p className="text-xs text-neutral-500 mt-2 dark:text-neutral-400">
              Leave rates empty to use treasury configuration. Override rates take precedence over entity spreads.
            </p>
          </div>

          {/* IHB Sweep Configuration */}
          <div>
            <p className="text-xs font-semibold text-neutral-500 uppercase tracking-wide mb-3 dark:text-neutral-400">
              IHB Sweep Configuration (Optional)
            </p>
            <div className="grid grid-cols-3 gap-4">
              <div>
                <label className="flex items-center gap-2 field-label">
                  <input
                    type="checkbox"
                    checked={currentAccountForm.ihbSweepEnabled}
                    onChange={(e) => setCurrentAccountForm({ ...currentAccountForm, ihbSweepEnabled: e.target.checked })}
                    className="rounded border-neutral-300 dark:border-primary-700"
                  />
                  Enable Auto-Sweep
                </label>
                <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">Auto-sweep surplus to treasury pool</p>
              </div>
              <div>
                <label className="field-label block mb-1">Target Balance</label>
                <Input
                  type="number"
                  value={currentAccountForm.targetCashBalance}
                  onChange={(e) => setCurrentAccountForm({ ...currentAccountForm, targetCashBalance: e.target.value })}
                  placeholder="0 = sweep all"
                  disabled={!currentAccountForm.ihbSweepEnabled}
                />
                <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">0 = sweep all surplus</p>
              </div>
              <div>
                <label className="field-label block mb-1">Sweep Frequency</label>
                <Select
                  value={currentAccountForm.ihbSweepFrequency}
                  onChange={(e) => setCurrentAccountForm({ ...currentAccountForm, ihbSweepFrequency: e.target.value })}
                  disabled={!currentAccountForm.ihbSweepEnabled}
                >
                  <option value="DAILY">Daily</option>
                  <option value="REAL_TIME">Real-Time</option>
                  <option value="WEEKLY">Weekly</option>
                </Select>
              </div>
            </div>
          </div>

          {/* Custom VA Name (Optional) */}
          <div>
            <label className="field-label block mb-1">
              Account Name (Optional)
            </label>
            <Input
              value={currentAccountForm.vaName}
              onChange={(e) => setCurrentAccountForm({ ...currentAccountForm, vaName: e.target.value })}
              placeholder="IHB Current - {Entity Name}"
            />
            <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">
              Leave empty to auto-generate: IHB Current - [Entity Name]
            </p>
          </div>

          {/* Actions */}
          <div className="flex gap-3 pt-4 border-t">
            <Button variant="outline" className="flex-1" onClick={() => setShowCurrentAccountModal(false)}>Cancel</Button>
            <Button
              className="flex-1 bg-cat-2 hover:bg-cat-2/90"
              onClick={handleCreateCurrentAccount}
              disabled={!currentAccountForm.participantEntityId}
            >
              <Wallet className="w-4 h-4 mr-1" /> Create Current Account
            </Button>
          </div>
        </div>
      </Modal>
    </Page>
  );
};

export default InHouseBankPage;