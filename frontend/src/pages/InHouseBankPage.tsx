import React, { useState, useEffect, useCallback } from 'react';
import {
  Building2, Users, ArrowLeftRight, ArrowUpRight, ArrowDownRight, TrendingUp, TrendingDown,
  Wallet, DollarSign, RefreshCw, Settings, Plus, Eye, ChevronRight, Percent, Calendar,
  Clock, CheckCircle, AlertCircle, Layers, GitBranch, Target, PiggyBank, Banknote, Loader2, X,
  CreditCard, Activity, FileText, Search, Filter, ChevronLeft, Download, MoreHorizontal,
  Briefcase
} from 'lucide-react';
import { Card, Button, Badge, Input, Select, Skeleton, EmptyState } from '../components/ui';
import { CurrencyPicker } from '../components/ui/CurrencyPicker';
import { Modal } from '../components/ui/enhanced';
import { formatCurrency, formatDate, cn } from '../utils';
import api, { corporatesApi, programsApi } from '../services/api';
import { usePageHeaderActions } from '../context/PageHeaderContext';
import { Page } from '../components/layout/Page';
import { ScopeSelector } from '../components/layout/ScopeSelector';

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

interface IhbLoan {
  id: string;
  loanReference: string;
  lenderEntityId: string;
  lenderEntityName?: string;
  borrowerEntityId: string;
  borrowerEntityName?: string;
  corporateId: string;
  principalAmount: number;
  outstandingAmount: number;
  currency: string;
  interestRate: number;
  accruedInterest: number;
  startDate: string;
  maturityDate?: string;
  // Option B: Extended status for settlement lifecycle
  status: 'PENDING' | 'APPROVED' | 'COMMITTED' | 'SETTLED' | 'MATURED' | 'PREPAID' | 'DEFAULT' | 'REJECTED' | 'CANCELLED' | 'ACTIVE';
  createdAt: string;
  // Option B: Settlement fields
  autoCreated?: boolean;           // true if created by sweep
  sweepExecutionReference?: string;
  valueDate?: string;              // When interest starts accruing
  settlementDate?: string;         // When funds actually moved
  settlementBatchRef?: string;
  approvedBy?: string;
  approvedAt?: string;
  rejectionReason?: string;
  // Cross-currency support
  originalCurrency?: string;
  originalAmount?: number;
  fxRate?: number;
}

interface IhbDeposit {
  id: string;
  depositReference: string;
  depositorEntityId: string;
  depositorEntityName?: string;
  treasuryEntityId?: string;
  treasuryEntityName?: string;
  corporateId: string;
  principalAmount: number;
  currentBalance: number;
  currency: string;
  interestRate: number;
  accruedInterest: number;
  startDate: string;
  maturityDate?: string;
  // Option B: Extended status for settlement lifecycle
  status: 'PENDING' | 'APPROVED' | 'COMMITTED' | 'SETTLED' | 'MATURED' | 'WITHDRAWN' | 'BROKEN' | 'REJECTED' | 'CANCELLED' | 'ACTIVE';
  createdAt: string;
  // Option B: Settlement fields
  autoCreated?: boolean;           // true if created by sweep
  sweepExecutionReference?: string;
  sweepFrequency?: string;         // DAILY, WEEKLY, MONTHLY etc.
  valueDate?: string;              // When interest starts accruing
  settlementDate?: string;         // When funds actually moved
  settlementBatchRef?: string;
  approvedBy?: string;
  approvedAt?: string;
  rejectionReason?: string;
  // Cross-currency support
  originalCurrency?: string;
  originalAmount?: number;
  fxRate?: number;
}

interface IhbStats {
  totalIhbEntities: number;
  activeLoans: number;
  activeDeposits: number;
  totalLoanOutstanding: number;
  totalDepositBalance: number;
  totalAccruedLoanInterest: number;
  totalAccruedDepositInterest: number;
  netPosition: number;
  netInterestIncome: number;
}

interface CalculateInterestResponse {
  totalLoanInterest: number;
  totalDepositInterest: number;
  totalSpread: number;
  netInterest: number;
  processedLoans: number;
  processedDeposits: number;
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

  // Option B: Committed Balances (awaiting EOD settlement)
  committedOutflow?: number;       // Funds committed to IHB deposits
  committedInflow?: number;        // Funds committed from IHB loans
  effectiveAvailableBalance?: number;  // availableBalance - committedOutflow

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
// OPTION B: SETTLEMENT STATUS TYPES
// ============================================================================

interface SettlementStatus {
  committedDeposits: number;
  committedLoans: number;
  pendingApproval: number;
  approvedAwaitingSettlement: number;
  totalCommittedDeposits: number;
  totalCommittedLoans: number;
  netCommitted: number;
}

interface SettlementResult {
  batchReference: string;
  settlementDate: string;
  depositsSettled: number;
  loansSettled: number;
  positionsMatured: number;
  transfersExecuted: number;
  totalSettledAmount: number;
  totalMatureAmount: number;
  totalInterestPosted: number;
  netTreasuryMovement: number;
  errors: string[];
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

  // Loans
  getLoans: (corporateId: string) =>
    fetch(`${API_BASE}/corporate/${corporateId}/loans`).then(r => r.json()) as Promise<ApiResponse<IhbLoan[]>>,

  getActiveLoans: (corporateId: string) =>
    fetch(`${API_BASE}/corporate/${corporateId}/loans/active`).then(r => r.json()) as Promise<ApiResponse<IhbLoan[]>>,

  createLoan: (data: {
    lenderEntityId: string;
    borrowerEntityId: string;
    principalAmount: number;
    currencyCode: string;
    baseRate: number;
    maturityDate: string;
    baseRateType?: string;
    interestType?: 'FIXED' | 'FLOATING';
    disbursementDate?: string;
    repaymentFrequency?: 'MONTHLY' | 'QUARTERLY' | 'SEMI_ANNUALLY' | 'ANNUALLY' | 'BULLET';
  }) =>
    fetch(`${API_BASE}/loans`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data)
    }).then(r => r.json()) as Promise<ApiResponse<IhbLoan>>,

  repayLoan: (loanId: string, data: { amount: number; includeInterest: boolean }) =>
    fetch(`${API_BASE}/loans/${loanId}/repay`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data)
    }).then(r => r.json()) as Promise<ApiResponse<IhbLoan>>,

  // Deposits
  getDeposits: (corporateId: string) =>
    fetch(`${API_BASE}/corporate/${corporateId}/deposits`).then(r => r.json()) as Promise<ApiResponse<IhbDeposit[]>>,

  createDeposit: (data: {
    depositorEntityId: string;
    treasuryEntityId?: string;
    principalAmount: number;
    currencyCode: string;
    interestRate?: number;
    depositDate?: string;
    maturityDate?: string;
    depositType?: 'CALL' | 'FIXED';
  }) =>
    fetch(`${API_BASE}/deposits`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data)
    }).then(r => r.json()) as Promise<ApiResponse<IhbDeposit>>,

  withdrawDeposit: (depositId: string, data: { amount: number; breakDeposit: boolean }) =>
    fetch(`${API_BASE}/deposits/${depositId}/withdraw`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data)
    }).then(r => r.json()) as Promise<ApiResponse<IhbDeposit>>,

  // Interest & Stats
  calculateInterest: (corporateId: string) =>
    fetch(`${API_BASE}/corporate/${corporateId}/interest/calculate`, { method: 'POST' }).then(r => r.json()) as Promise<ApiResponse<CalculateInterestResponse>>,

  getStats: (corporateId: string) =>
    fetch(`${API_BASE}/corporate/${corporateId}/stats`).then(r => r.json()) as Promise<ApiResponse<IhbStats>>,

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

  // ========================================================================
  // OPTION B: SETTLEMENT API ENDPOINTS
  // ========================================================================

  // Get settlement status (positions awaiting settlement)
  getSettlementStatus: (corporateId: string) =>
    fetch(`${API_BASE}/corporate/${corporateId}/settlement/status`).then(r => r.json()) as Promise<ApiResponse<SettlementStatus>>,

  // Run EOD settlement (admin only)
  runSettlement: (corporateId: string, options?: { processMaturing?: boolean; dryRun?: boolean }) =>
    fetch(`${API_BASE}/corporate/${corporateId}/settlement/run`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(options || {})
    }).then(r => r.json()) as Promise<ApiResponse<SettlementResult>>,

  // Approval workflow for manual positions
  approveDeposit: (depositId: string) =>
    fetch(`${API_BASE}/deposits/${depositId}/approve`, { method: 'POST' }).then(r => r.json()) as Promise<ApiResponse<IhbDeposit>>,

  rejectDeposit: (depositId: string, reason: string) =>
    fetch(`${API_BASE}/deposits/${depositId}/reject`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ reason })
    }).then(r => r.json()) as Promise<ApiResponse<IhbDeposit>>,

  cancelDeposit: (depositId: string, reason: string) =>
    fetch(`${API_BASE}/deposits/${depositId}/cancel`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ reason })
    }).then(r => r.json()) as Promise<ApiResponse<IhbDeposit>>,

  approveLoan: (loanId: string) =>
    fetch(`${API_BASE}/loans/${loanId}/approve`, { method: 'POST' }).then(r => r.json()) as Promise<ApiResponse<IhbLoan>>,

  rejectLoan: (loanId: string, reason: string) =>
    fetch(`${API_BASE}/loans/${loanId}/reject`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ reason })
    }).then(r => r.json()) as Promise<ApiResponse<IhbLoan>>,

  cancelLoan: (loanId: string, reason: string) =>
    fetch(`${API_BASE}/loans/${loanId}/cancel`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ reason })
    }).then(r => r.json()) as Promise<ApiResponse<IhbLoan>>,

  // Get pending approvals
  getPendingDeposits: (corporateId: string) =>
    fetch(`${API_BASE}/corporate/${corporateId}/deposits/pending`).then(r => r.json()) as Promise<ApiResponse<IhbDeposit[]>>,

  getPendingLoans: (corporateId: string) =>
    fetch(`${API_BASE}/corporate/${corporateId}/loans/pending`).then(r => r.json()) as Promise<ApiResponse<IhbLoan[]>>,
};

// ============================================================================
// SUB-COMPONENTS
// ============================================================================

const StatCard: React.FC<{
  title: string;
  value: string | number;
  subtitle?: string;
  icon: React.ReactNode;
  color?: 'primary' | 'success' | 'error' | 'warning' | 'info';
  loading?: boolean;
  delay?: string;
}> = ({ title, value, subtitle, icon, color = 'primary', loading, delay }) => {
  const colorClasses = {
    primary: 'bg-primary-100 text-primary-700 dark:bg-primary-700 dark:text-neutral-200',
    success: 'bg-success-100 text-success-600 dark:bg-success-500/20 dark:text-success-300',
    error: 'bg-error-100 text-error-600 dark:bg-error-500/20 dark:text-error-300',
    warning: 'bg-warning-100 text-warning-600 dark:bg-warning-500/20 dark:text-warning-300',
    info: 'bg-info-100 text-info-600 dark:bg-info-500/20 dark:text-info-300',
  };

  const valueColorClasses = {
    primary: 'text-primary-900 dark:text-neutral-50',
    success: 'text-success-600 dark:text-success-300',
    error: 'text-error-600 dark:text-error-300',
    warning: 'text-warning-600 dark:text-warning-300',
    info: 'text-info-600 dark:text-info-300',
  };

  return (
    <Card hover className="animate-fade-in" style={delay ? { animationDelay: delay } : undefined}>
      <div className="p-4">
        <div className="flex items-start justify-between">
          <div className="flex-1 min-w-0">
            <p className="label">{title}</p>
            {loading ? (
              <Skeleton className="h-8 w-24 mt-1" />
            ) : (
              <p className={cn('stat-value-sm mt-1', valueColorClasses[color])}>{value}</p>
            )}
            {loading ? (
              <Skeleton className="h-3 w-20 mt-1" />
            ) : subtitle ? (
              <p className="text-xs text-neutral-400 mt-0.5 dark:text-neutral-500">{subtitle}</p>
            ) : null}
          </div>
          <div className={cn('w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0', colorClasses[color])}>
            {icon}
          </div>
        </div>
      </div>
    </Card>
  );
};

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
  onCreateLoan?: () => void;
  onCreateDeposit?: () => void;
  onCreateCurrentAccount?: () => void;
}> = ({ entity, onViewPosition, onCreateLoan, onCreateDeposit, onCreateCurrentAccount }) => {
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
          {/* Quick Loan - for Treasury Centers (canLend) */}
          {isTreasuryCenter && onCreateLoan && (
            <Button variant="ghost" size="sm" className="flex-1 text-warning-600 hover:bg-warning-50 dark:text-warning-300 dark:hover:bg-warning-500/10" onClick={onCreateLoan}>
              <Banknote className="w-4 h-4 mr-1" /> Lend
            </Button>
          )}
          {/* Quick Deposit - for Participants (canBorrow) */}
          {isParticipant && onCreateDeposit && (
            <Button variant="ghost" size="sm" className="flex-1 text-success-600 hover:bg-success-50 dark:text-success-300 dark:hover:bg-success-500/10" onClick={onCreateDeposit}>
              <PiggyBank className="w-4 h-4 mr-1" /> Deposit
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

const LoanRow: React.FC<{ loan: IhbLoan; onRepay: () => void; onApprove?: () => void; onReject?: () => void }> = ({ loan, onRepay, onApprove, onReject }) => {
  const currency = loan.currency || 'AED';
  const interestRate = loan.interestRate ?? 0;
  const principalAmount = loan.principalAmount ?? 0;
  const outstandingAmount = loan.outstandingAmount ?? 0;
  const accruedInterest = loan.accruedInterest ?? 0;

  // Option B: Status badge variant mapping for settlement lifecycle
  const getStatusBadge = (status: string) => {
    const statusConfig: Record<string, { variant: 'success' | 'warning' | 'error' | 'info' | 'neutral'; label: string }> = {
      PENDING: { variant: 'warning', label: 'Pending Approval' },
      APPROVED: { variant: 'info', label: 'Approved' },
      COMMITTED: { variant: 'info', label: 'Committed' },
      SETTLED: { variant: 'success', label: 'Settled' },
      MATURED: { variant: 'neutral', label: 'Matured' },
      PREPAID: { variant: 'neutral', label: 'Prepaid' },
      DEFAULT: { variant: 'error', label: 'Default' },
      REJECTED: { variant: 'error', label: 'Rejected' },
      CANCELLED: { variant: 'neutral', label: 'Cancelled' },
      ACTIVE: { variant: 'success', label: 'Active' }, // Deprecated but supported
    };
    const config = statusConfig[status] || { variant: 'neutral' as const, label: status };
    return <Badge variant={config.variant} size="sm">{config.label}</Badge>;
  };

  // Determine if actions are available based on status
  const canRepay = loan.status === 'SETTLED' || loan.status === 'ACTIVE';
  const canApprove = loan.status === 'PENDING';
  const isAwaitingSettlement = loan.status === 'COMMITTED' || loan.status === 'APPROVED';

  return (
    <tr className="data-table-row group">
      <td className="data-table-cell">
        <div className="flex items-center gap-2">
          <div>
            <p className="text-sm font-mono text-primary-900 dark:text-neutral-50">{loan.loanReference || '-'}</p>
            <p className="text-xs text-neutral-500 dark:text-neutral-400">{loan.createdAt ? formatDate(loan.createdAt) : '-'}</p>
          </div>
          {loan.autoCreated && (
            <Badge variant="info" size="sm" className="text-[10px]">Sweep</Badge>
          )}
        </div>
      </td>
      <td className="data-table-cell">
        <p className="text-sm text-primary-900 dark:text-neutral-50">{loan.lenderEntityName || loan.lenderEntityId || '-'}</p>
      </td>
      <td className="data-table-cell">
        <p className="text-sm text-primary-900 dark:text-neutral-50">{loan.borrowerEntityName || loan.borrowerEntityId || '-'}</p>
      </td>
      <td className="data-table-cell text-right">
        <p className="text-sm font-semibold text-primary-900 dark:text-neutral-50">{formatCurrency(principalAmount, currency)}</p>
        {loan.originalCurrency && loan.originalCurrency !== currency && (
          <p className="text-xs text-neutral-500 dark:text-neutral-400">({formatCurrency(loan.originalAmount || 0, loan.originalCurrency)} @ {loan.fxRate})</p>
        )}
      </td>
      <td className="data-table-cell text-right">
        <p className="text-sm text-primary-900 dark:text-neutral-50">{formatCurrency(outstandingAmount, currency)}</p>
      </td>
      <td className="data-table-cell text-right">
        <p className="text-sm text-primary-900 dark:text-neutral-50">{interestRate.toFixed(2)}%</p>
      </td>
      <td className="data-table-cell text-right">
        <p className="text-sm text-success-600 dark:text-success-300">{formatCurrency(accruedInterest, currency)}</p>
      </td>
      <td className="data-table-cell text-center">
        <div className="flex flex-col items-center gap-1">
          {getStatusBadge(loan.status)}
          {isAwaitingSettlement && (
            <span className="text-[10px] text-info-600 dark:text-info-300">EOD Settlement</span>
          )}
        </div>
      </td>
      <td className="data-table-cell text-center">
        <div className="flex items-center justify-center gap-1">
          {canApprove && onApprove && onReject && (
            <>
              <Button variant="ghost" size="sm" onClick={onApprove} className="opacity-0 group-hover:opacity-100 transition-opacity text-success-600 dark:text-success-300">
                <CheckCircle className="w-4 h-4" />
              </Button>
              <Button variant="ghost" size="sm" onClick={onReject} className="opacity-0 group-hover:opacity-100 transition-opacity text-error-600 dark:text-error-300">
                <X className="w-4 h-4" />
              </Button>
            </>
          )}
          {canRepay && (
            <Button variant="ghost" size="sm" onClick={onRepay} className="opacity-0 group-hover:opacity-100 transition-opacity">Repay</Button>
          )}
        </div>
      </td>
    </tr>
  );
};

const DepositRow: React.FC<{ deposit: IhbDeposit; onWithdraw: () => void; onApprove?: () => void; onReject?: () => void }> = ({ deposit, onWithdraw, onApprove, onReject }) => {
  const currency = deposit.currency || 'AED';
  const interestRate = deposit.interestRate ?? 0;
  const principalAmount = deposit.principalAmount ?? 0;
  const currentBalance = deposit.currentBalance ?? 0;
  const accruedInterest = deposit.accruedInterest ?? 0;

  // Option B: Status badge variant mapping for settlement lifecycle
  const getStatusBadge = (status: string) => {
    const statusConfig: Record<string, { variant: 'success' | 'warning' | 'error' | 'info' | 'neutral'; label: string }> = {
      PENDING: { variant: 'warning', label: 'Pending Approval' },
      APPROVED: { variant: 'info', label: 'Approved' },
      COMMITTED: { variant: 'info', label: 'Committed' },
      SETTLED: { variant: 'success', label: 'Settled' },
      MATURED: { variant: 'neutral', label: 'Matured' },
      WITHDRAWN: { variant: 'neutral', label: 'Withdrawn' },
      BROKEN: { variant: 'warning', label: 'Broken Early' },
      REJECTED: { variant: 'error', label: 'Rejected' },
      CANCELLED: { variant: 'neutral', label: 'Cancelled' },
      ACTIVE: { variant: 'success', label: 'Active' }, // Deprecated but supported
    };
    const config = statusConfig[status] || { variant: 'neutral' as const, label: status };
    return <Badge variant={config.variant} size="sm">{config.label}</Badge>;
  };

  // Determine if actions are available based on status
  const canWithdraw = deposit.status === 'SETTLED' || deposit.status === 'ACTIVE';
  const canApprove = deposit.status === 'PENDING';
  const isAwaitingSettlement = deposit.status === 'COMMITTED' || deposit.status === 'APPROVED';

  return (
    <tr className="data-table-row group">
      <td className="data-table-cell">
        <div className="flex items-center gap-2">
          <div>
            <p className="text-sm font-mono text-primary-900 dark:text-neutral-50">{deposit.depositReference || '-'}</p>
            <p className="text-xs text-neutral-500 dark:text-neutral-400">{deposit.createdAt ? formatDate(deposit.createdAt) : '-'}</p>
          </div>
          {deposit.autoCreated && (
            <Badge variant="info" size="sm" className="text-[10px]">Sweep</Badge>
          )}
        </div>
      </td>
      <td className="data-table-cell">
        <p className="text-sm text-primary-900 dark:text-neutral-50">{deposit.depositorEntityName || deposit.depositorEntityId || '-'}</p>
      </td>
      <td className="data-table-cell">
        <p className="text-sm text-primary-900 dark:text-neutral-50">{deposit.treasuryEntityName || 'Group Treasury'}</p>
      </td>
      <td className="data-table-cell text-right">
        <p className="text-sm font-semibold text-primary-900 dark:text-neutral-50">{formatCurrency(principalAmount, currency)}</p>
        {deposit.originalCurrency && deposit.originalCurrency !== currency && (
          <p className="text-xs text-neutral-500 dark:text-neutral-400">({formatCurrency(deposit.originalAmount || 0, deposit.originalCurrency)} @ {deposit.fxRate})</p>
        )}
      </td>
      <td className="data-table-cell text-right">
        <p className="text-sm text-primary-900 dark:text-neutral-50">{formatCurrency(currentBalance, currency)}</p>
      </td>
      <td className="data-table-cell text-right">
        <p className="text-sm text-primary-900 dark:text-neutral-50">{interestRate.toFixed(2)}%</p>
      </td>
      <td className="data-table-cell text-right">
        <p className="text-sm text-success-600 dark:text-success-300">{formatCurrency(accruedInterest, currency)}</p>
      </td>
      <td className="data-table-cell text-center">
        <div className="flex flex-col items-center gap-1">
          {getStatusBadge(deposit.status)}
          {isAwaitingSettlement && (
            <span className="text-[10px] text-info-600 dark:text-info-300">EOD Settlement</span>
          )}
          {deposit.sweepFrequency && (
            <span className="label-cased">{deposit.sweepFrequency}</span>
          )}
        </div>
      </td>
      <td className="data-table-cell text-center">
        <div className="flex items-center justify-center gap-1">
          {canApprove && onApprove && onReject && (
            <>
              <Button variant="ghost" size="sm" onClick={onApprove} className="opacity-0 group-hover:opacity-100 transition-opacity text-success-600 dark:text-success-300">
                <CheckCircle className="w-4 h-4" />
              </Button>
              <Button variant="ghost" size="sm" onClick={onReject} className="opacity-0 group-hover:opacity-100 transition-opacity text-error-600 dark:text-error-300">
                <X className="w-4 h-4" />
              </Button>
            </>
          )}
          {canWithdraw && (
            <Button variant="ghost" size="sm" onClick={onWithdraw} className="opacity-0 group-hover:opacity-100 transition-opacity">Withdraw</Button>
          )}
        </div>
      </td>
    </tr>
  );
};

// ============================================================================
// MAIN COMPONENT
// ============================================================================

const InHouseBankPage: React.FC = () => {
  // State
  const [activeTab, setActiveTab] = useState<'overview' | 'entities' | 'loans' | 'deposits' | 'current-accounts'>('overview');
  const [corporates, setCorporates] = useState<Corporate[]>([]);
  const [programs, setPrograms] = useState<Program[]>([]);
  const [selectedCorporateId, setSelectedCorporateId] = useState<string>('');
  const [selectedProgramId, setSelectedProgramId] = useState<string>('');
  const [entities, setEntities] = useState<IhbEntity[]>([]);
  const [loans, setLoans] = useState<IhbLoan[]>([]);
  const [deposits, setDeposits] = useState<IhbDeposit[]>([]);
  const [currentAccounts, setCurrentAccounts] = useState<IhbCurrentAccount[]>([]);
  const [stats, setStats] = useState<IhbStats | null>(null);
  const [treasuryRates, setTreasuryRates] = useState<TreasuryRates | null>(null);
  const [settlementStatus, setSettlementStatus] = useState<SettlementStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingSelectors, setLoadingSelectors] = useState(false);
  const [calculating, setCalculating] = useState(false);
  const [runningSettlement, setRunningSettlement] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Modals
  const [showLoanModal, setShowLoanModal] = useState(false);
  const [showDepositModal, setShowDepositModal] = useState(false);
  const [showRepayModal, setShowRepayModal] = useState(false);
  const [showWithdrawModal, setShowWithdrawModal] = useState(false);
  const [showPositionModal, setShowPositionModal] = useState(false);
  const [showCurrentAccountModal, setShowCurrentAccountModal] = useState(false);
  const [selectedEntity, setSelectedEntity] = useState<IhbEntity | null>(null);
  const [selectedLoan, setSelectedLoan] = useState<IhbLoan | null>(null);
  const [selectedDeposit, setSelectedDeposit] = useState<IhbDeposit | null>(null);
  const [selectedCurrentAccount, setSelectedCurrentAccount] = useState<IhbCurrentAccount | null>(null);
  const [entityPosition, setEntityPosition] = useState<any>(null);
  const [loadingPosition, setLoadingPosition] = useState(false);

  // Form state - Enhanced for backend alignment
  const [loanForm, setLoanForm] = useState({
    lenderEntityId: '',
    borrowerEntityId: '',
    principalAmount: '',
    currencyCode: 'AED',
    baseRate: '',
    baseRateType: 'EIBOR',
    interestType: 'FIXED' as 'FIXED' | 'FLOATING',
    maturityDate: '',
    disbursementDate: '',
    repaymentFrequency: 'MONTHLY' as 'MONTHLY' | 'QUARTERLY' | 'SEMI_ANNUALLY' | 'ANNUALLY' | 'BULLET',
  });
  const [depositForm, setDepositForm] = useState({
    depositorEntityId: '',
    treasuryEntityId: '',
    principalAmount: '',
    currencyCode: 'AED',
    interestRate: '',
    depositDate: '',
    maturityDate: '',
    depositType: 'CALL' as 'CALL' | 'FIXED',
  });
  const [repayAmount, setRepayAmount] = useState('');
  const [includeInterest, setIncludeInterest] = useState(true);
  const [withdrawAmount, setWithdrawAmount] = useState('');
  const [breakDeposit, setBreakDeposit] = useState(false);
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
      setLoans([]);
      setDeposits([]);
      setCurrentAccounts([]);
      setStats(null);
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
      const [entitiesRes, loansRes, depositsRes, currentAccountsRes, statsRes, ratesRes, settlementRes] = await Promise.all([
        ihbUnifiedApi.getEntitiesByCorporate(selectedCorporateId),
        ihbUnifiedApi.getLoans(selectedCorporateId),
        ihbUnifiedApi.getDeposits(selectedCorporateId),
        ihbUnifiedApi.getCurrentAccountsByCorporate(selectedCorporateId).catch(() => ({ data: [] })),
        ihbUnifiedApi.getStats(selectedCorporateId),
        ihbUnifiedApi.getTreasuryRates(selectedCorporateId).catch(() => null),
        ihbUnifiedApi.getSettlementStatus(selectedCorporateId).catch(() => null),
      ]);

      setEntities(entitiesRes?.data || []);
      setLoans(loansRes?.data || []);
      setDeposits(depositsRes?.data || []);
      setCurrentAccounts(currentAccountsRes?.data || []);
      setStats(statsRes?.data || null);
      setTreasuryRates(ratesRes?.data || null);
      setSettlementStatus(settlementRes?.data || null);
    } catch (err) {
      console.error('Failed to load IHB data:', err);
      setError('Failed to load IHB data. The unified API may not be deployed yet.');
    } finally {
      setLoading(false);
    }
  }, [selectedCorporateId]);

  const handleCalculateInterest = async () => {
    if (!selectedCorporateId) return;

    setCalculating(true);
    try {
      const result = await ihbUnifiedApi.calculateInterest(selectedCorporateId);
      if (result?.success) {
        await loadData();
        alert(`Interest calculated successfully!\nLoan Interest: ${result.data.totalLoanInterest}\nDeposit Interest: ${result.data.totalDepositInterest}\nNet Spread: ${result.data.totalSpread}`);
      }
    } catch (err) {
      console.error('Interest calculation failed:', err);
      alert('Interest calculation failed');
    } finally {
      setCalculating(false);
    }
  };

  // IHB toolbar — 4 actions: Calculate Interest + New Loan / Deposit / Current Acct
  usePageHeaderActions(
    () => (
      <>
        <Button
          variant="outline" size="sm"
          leftIcon={calculating ? <Loader2 className="w-4 h-4 animate-spin" /> : <RefreshCw className="w-4 h-4" />}
          onClick={handleCalculateInterest}
          disabled={calculating || !selectedCorporateId}
        >
          {calculating ? 'Calculating…' : 'Calculate Interest'}
        </Button>
        <Button size="sm" leftIcon={<Plus className="w-4 h-4" />} onClick={() => setShowLoanModal(true)} disabled={!selectedCorporateId}>
          New Loan
        </Button>
        <Button variant="outline" size="sm" leftIcon={<Plus className="w-4 h-4" />} onClick={() => setShowDepositModal(true)} disabled={!selectedCorporateId}>
          New Deposit
        </Button>
        <Button
          variant="outline" size="sm"
          leftIcon={<Wallet className="w-4 h-4" />}
          onClick={() => setShowCurrentAccountModal(true)}
          disabled={!selectedCorporateId}
          className="text-cat-2 border-cat-2/20 hover:bg-cat-2-soft dark:border-cat-2/30 dark:hover:bg-cat-2/15"
        >
          Current Account
        </Button>
      </>
    ),
    [calculating, selectedCorporateId]
  );

  const handleCreateLoan = async () => {
    // Validate required fields
    if (!loanForm.lenderEntityId || !loanForm.borrowerEntityId || !loanForm.principalAmount || !loanForm.baseRate || !loanForm.maturityDate) {
      alert('Please fill in all required fields: Lender, Borrower, Principal, Base Rate, and Maturity Date');
      return;
    }

    try {
      const result = await ihbUnifiedApi.createLoan({
        lenderEntityId: loanForm.lenderEntityId,
        borrowerEntityId: loanForm.borrowerEntityId,
        principalAmount: parseFloat(loanForm.principalAmount),
        currencyCode: loanForm.currencyCode,
        baseRate: parseFloat(loanForm.baseRate),
        maturityDate: loanForm.maturityDate,
        baseRateType: loanForm.baseRateType || undefined,
        interestType: loanForm.interestType,
        disbursementDate: loanForm.disbursementDate || undefined,
        repaymentFrequency: loanForm.repaymentFrequency,
      });

      if (result?.success) {
        setShowLoanModal(false);
        setLoanForm({
          lenderEntityId: '', borrowerEntityId: '', principalAmount: '', currencyCode: 'AED',
          baseRate: '', baseRateType: 'EIBOR', interestType: 'FIXED', maturityDate: '',
          disbursementDate: '', repaymentFrequency: 'MONTHLY',
        });
        await loadData();
      } else {
        alert(result?.message || 'Failed to create loan');
      }
    } catch (err) {
      console.error('Failed to create loan:', err);
      alert('Failed to create loan');
    }
  };

  const handleCreateDeposit = async () => {
    // Validate required fields
    if (!depositForm.depositorEntityId || !depositForm.principalAmount) {
      alert('Please fill in all required fields: Depositor and Principal Amount');
      return;
    }

    try {
      const result = await ihbUnifiedApi.createDeposit({
        depositorEntityId: depositForm.depositorEntityId,
        treasuryEntityId: depositForm.treasuryEntityId || undefined,
        principalAmount: parseFloat(depositForm.principalAmount),
        currencyCode: depositForm.currencyCode,
        interestRate: depositForm.interestRate ? parseFloat(depositForm.interestRate) : undefined,
        depositDate: depositForm.depositDate || undefined,
        maturityDate: depositForm.maturityDate || undefined,
        depositType: depositForm.depositType,
      });

      if (result?.success) {
        setShowDepositModal(false);
        setDepositForm({
          depositorEntityId: '', treasuryEntityId: '', principalAmount: '', currencyCode: 'AED',
          interestRate: '', depositDate: '', maturityDate: '', depositType: 'CALL',
        });
        await loadData();
      } else {
        alert(result?.message || 'Failed to create deposit');
      }
    } catch (err) {
      console.error('Failed to create deposit:', err);
      alert('Failed to create deposit');
    }
  };

  const handleRepayLoan = async () => {
    if (!selectedLoan) return;

    try {
      const result = await ihbUnifiedApi.repayLoan(selectedLoan.id, {
        amount: parseFloat(repayAmount),
        includeInterest: includeInterest,
      });
      if (result?.success) {
        setShowRepayModal(false);
        setRepayAmount('');
        setIncludeInterest(true);
        setSelectedLoan(null);
        await loadData();
      } else {
        alert(result?.message || 'Failed to repay loan');
      }
    } catch (err) {
      console.error('Failed to repay loan:', err);
      alert('Failed to repay loan');
    }
  };

  const handleWithdrawDeposit = async () => {
    if (!selectedDeposit) return;

    try {
      const result = await ihbUnifiedApi.withdrawDeposit(selectedDeposit.id, {
        amount: parseFloat(withdrawAmount),
        breakDeposit: breakDeposit,
      });
      if (result?.success) {
        setShowWithdrawModal(false);
        setWithdrawAmount('');
        setBreakDeposit(false);
        setSelectedDeposit(null);
        await loadData();
      } else {
        alert(result?.message || 'Failed to withdraw from deposit');
      }
    } catch (err) {
      console.error('Failed to withdraw deposit:', err);
      alert('Failed to withdraw deposit');
    }
  };

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

  // ========================================================================
  // OPTION B: APPROVAL WORKFLOW HANDLERS
  // ========================================================================

  const handleApproveLoan = async (loan: IhbLoan) => {
    try {
      const result = await ihbUnifiedApi.approveLoan(loan.id);
      if (result?.success) {
        await loadData();
      } else {
        alert(result?.message || 'Failed to approve loan');
      }
    } catch (err) {
      console.error('Failed to approve loan:', err);
      alert('Failed to approve loan');
    }
  };

  const handleRejectLoan = async (loan: IhbLoan) => {
    const reason = window.prompt('Enter rejection reason:');
    if (!reason) return;

    try {
      const result = await ihbUnifiedApi.rejectLoan(loan.id, reason);
      if (result?.success) {
        await loadData();
      } else {
        alert(result?.message || 'Failed to reject loan');
      }
    } catch (err) {
      console.error('Failed to reject loan:', err);
      alert('Failed to reject loan');
    }
  };

  const handleApproveDeposit = async (deposit: IhbDeposit) => {
    try {
      const result = await ihbUnifiedApi.approveDeposit(deposit.id);
      if (result?.success) {
        await loadData();
      } else {
        alert(result?.message || 'Failed to approve deposit');
      }
    } catch (err) {
      console.error('Failed to approve deposit:', err);
      alert('Failed to approve deposit');
    }
  };

  const handleRejectDeposit = async (deposit: IhbDeposit) => {
    const reason = window.prompt('Enter rejection reason:');
    if (!reason) return;

    try {
      const result = await ihbUnifiedApi.rejectDeposit(deposit.id, reason);
      if (result?.success) {
        await loadData();
      } else {
        alert(result?.message || 'Failed to reject deposit');
      }
    } catch (err) {
      console.error('Failed to reject deposit:', err);
      alert('Failed to reject deposit');
    }
  };

  // Run EOD Settlement (admin action)
  const handleRunSettlement = async (dryRun: boolean = false) => {
    if (!selectedCorporateId) return;

    const confirmMsg = dryRun
      ? 'Run settlement in dry-run mode? This will simulate the settlement without making changes.'
      : 'Run EOD Settlement now? This will process all committed positions and execute fund transfers.';

    if (!window.confirm(confirmMsg)) return;

    setRunningSettlement(true);
    try {
      const result = await ihbUnifiedApi.runSettlement(selectedCorporateId, {
        processMaturing: true,
        dryRun,
      });

      if (result?.success) {
        const data = result.data;
        alert(`Settlement ${dryRun ? '(Dry Run) ' : ''}completed!\n\n` +
          `Batch: ${data.batchReference}\n` +
          `Deposits Settled: ${data.depositsSettled}\n` +
          `Loans Settled: ${data.loansSettled}\n` +
          `Positions Matured: ${data.positionsMatured}\n` +
          `Transfers Executed: ${data.transfersExecuted}\n` +
          `Total Amount: ${formatCurrency(data.totalSettledAmount, 'AED')}\n` +
          `Interest Posted: ${formatCurrency(data.totalInterestPosted, 'AED')}`
        );
        await loadData();
      } else {
        alert(result?.message || 'Settlement failed');
      }
    } catch (err) {
      console.error('Settlement failed:', err);
      alert('Settlement failed');
    } finally {
      setRunningSettlement(false);
    }
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

  // Helper to fill loan form with treasury rates
  const fillLoanRateFromTreasury = () => {
    if (treasuryRates) {
      setLoanForm(prev => ({
        ...prev,
        baseRate: treasuryRates.lendingBaseRate?.toString() || '5.00',
        baseRateType: treasuryRates.lendingBaseRateType || 'EIBOR',
        currencyCode: treasuryRates.ihbCurrency || prev.currencyCode,
      }));
    }
  };

  // Helper to fill deposit form with treasury rates
  const fillDepositRateFromTreasury = () => {
    if (treasuryRates) {
      setDepositForm(prev => ({
        ...prev,
        interestRate: treasuryRates.indicativeDepositRate?.toString() || '',
        currencyCode: treasuryRates.ihbCurrency || prev.currencyCode,
      }));
    }
  };

  // Calculate estimated interest for loan preview
  const calculateEstimatedInterest = () => {
    if (!loanForm.principalAmount || !loanForm.baseRate || !loanForm.maturityDate) return null;
    const principal = parseFloat(loanForm.principalAmount);
    const rate = parseFloat(loanForm.baseRate);
    const today = new Date();
    const maturity = new Date(loanForm.maturityDate);
    const days = Math.ceil((maturity.getTime() - today.getTime()) / (1000 * 60 * 60 * 24));
    if (days <= 0) return null;
    // Simple interest: P * R * T / 36000 (assuming ACT/360)
    const interest = (principal * rate * days) / 36000;
    return { interest, days, total: principal + interest };
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

  // Handle quick loan creation from entity card
  const handleQuickLoan = (entity: IhbEntity) => {
    // Pre-fill the lender as Treasury Center
    setLoanForm(prev => ({
      ...prev,
      lenderEntityId: entity.id,
      currencyCode: entity.ihbCurrency || 'AED',
      baseRate: treasuryRates?.lendingBaseRate?.toString() || '',
      baseRateType: treasuryRates?.lendingBaseRateType || 'EIBOR',
    }));
    setShowLoanModal(true);
  };

  // Handle quick deposit creation from entity card
  const handleQuickDeposit = (entity: IhbEntity) => {
    // Pre-fill the depositor as the selected entity
    setDepositForm(prev => ({
      ...prev,
      depositorEntityId: entity.id,
      currencyCode: entity.ihbCurrency || 'AED',
      interestRate: treasuryRates?.indicativeDepositRate?.toString() || '',
    }));
    setShowDepositModal(true);
  };

  // Calculated values
  const lenders = entities.filter(e => e.canLend);
  const borrowers = entities.filter(e => e.canBorrow);
  const activeLoans = loans.filter(l => l.status === 'ACTIVE');
  const activeDeposits = deposits.filter(d => d.status === 'ACTIVE');
  const totalLoanOutstanding = activeLoans.reduce((sum, l) => sum + l.outstandingAmount, 0);
  const totalDepositBalance = activeDeposits.reduce((sum, d) => sum + d.currentBalance, 0);
  const netInterest = (stats?.totalAccruedLoanInterest || 0) - (stats?.totalAccruedDepositInterest || 0);

  const tabs = [
    { id: 'overview', label: 'Overview' },
    { id: 'entities', label: 'IHB Entities', count: entities.length },
    { id: 'current-accounts', label: 'Current Accounts', count: currentAccounts.length },
    { id: 'loans', label: 'Loans', count: activeLoans.length },
    { id: 'deposits', label: 'Deposits', count: activeDeposits.length },
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
              Choose a corporate from the selector above to view and manage its In-House Bank entities, loans, and deposits.
            </p>
          </div>
        </Card>
      )}

      {/* Content when corporate is selected */}
      {selectedCorporateId && (
        <>
          {/* Treasury Rates Banner (if available) */}
          {treasuryRates && (
            <Card padding="sm" className="bg-gradient-to-r from-warning-50/50 via-white to-success-50/50 border-warning-200/60 animate-fade-in" style={{ animationDelay: '0.15s' }}>
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
          <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <StatCard
          title="IHB Entities"
          value={entities.length}
          subtitle={`${lenders.length} lenders, ${borrowers.length} borrowers`}
          icon={<Building2 className="w-5 h-5" />}
          color="primary"
          loading={loading}
          delay="0.15s"
        />
        <StatCard
          title="Loans Outstanding"
          value={formatCurrency(totalLoanOutstanding, 'AED')}
          subtitle={`${activeLoans.length} active loans`}
          icon={<CreditCard className="w-5 h-5" />}
          color="error"
          loading={loading}
          delay="0.2s"
        />
        <StatCard
          title="Total Deposits"
          value={formatCurrency(totalDepositBalance, 'AED')}
          subtitle={`${activeDeposits.length} active deposits`}
          icon={<PiggyBank className="w-5 h-5" />}
          color="success"
          loading={loading}
          delay="0.25s"
        />
        <StatCard
          title="Net Interest"
          value={formatCurrency(netInterest, 'AED')}
          subtitle="Spread earned"
          icon={<Percent className="w-5 h-5" />}
          color={netInterest >= 0 ? 'success' : 'error'}
          loading={loading}
          delay="0.3s"
        />
      </div>

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
              <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                <Card padding="sm" className="bg-success-50 border-success-200 dark:bg-success-500/10 dark:border-success-500/30">
                  <div className="flex items-center gap-2 mb-2">
                    <TrendingUp className="w-4 h-4 text-success-600 dark:text-success-300" />
                    <p className="text-sm font-semibold text-success-800 dark:text-success-300">Lender Entities</p>
                  </div>
                  <p className="stat-value-success">{lenders.length}</p>
                  <p className="text-xs text-success-600 mt-1 dark:text-success-300">Available to lend funds</p>
                </Card>
                <Card padding="sm" className="bg-error-50 border-error-200 dark:bg-error-500/10 dark:border-error-500/30">
                  <div className="flex items-center gap-2 mb-2">
                    <TrendingDown className="w-4 h-4 text-error-600 dark:text-error-300" />
                    <p className="text-sm font-semibold text-error-800 dark:text-error-300">Borrower Entities</p>
                  </div>
                  <p className="stat-value-error">{borrowers.length}</p>
                  <p className="text-xs text-error-600 mt-1 dark:text-error-300">Can borrow from IHB</p>
                </Card>
                <Card padding="sm" className="bg-info-50 border-info-200 dark:bg-info-500/10 dark:border-info-500/30">
                  <div className="flex items-center gap-2 mb-2">
                    <Activity className="w-4 h-4 text-info-600 dark:text-info-300" />
                    <p className="text-sm font-semibold text-info-800 dark:text-info-300">Net Position</p>
                  </div>
                  <p className="stat-value-sm text-info-700 dark:text-info-300">
                    {formatCurrency(totalDepositBalance - totalLoanOutstanding, 'AED')}
                  </p>
                  <p className="text-xs text-info-600 mt-1 dark:text-info-300">Deposits - Loans</p>
                </Card>
              </div>

              {/* Settlement Status Panel (Option B) */}
              {settlementStatus && (settlementStatus.committedDeposits > 0 || settlementStatus.committedLoans > 0 || settlementStatus.pendingApproval > 0) && (
                <Card className="bg-gradient-to-r from-info-50 to-cat-2-soft border-info-200 dark:border-info-500/30 dark:from-info-500/15 dark:to-cat-2/15">
                  <div className="p-4">
                    <div className="flex items-center justify-between mb-4">
                      <div className="flex items-center gap-2">
                        <Clock className="w-5 h-5 text-info-600 dark:text-info-300" />
                        <h3 className="text-sm font-semibold text-info-900">Settlement Status</h3>
                      </div>
                      <div className="flex items-center gap-2">
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => handleRunSettlement(true)}
                          disabled={runningSettlement}
                          className="text-xs"
                        >
                          Dry Run
                        </Button>
                        <Button
                          size="sm"
                          onClick={() => handleRunSettlement(false)}
                          disabled={runningSettlement}
                          leftIcon={runningSettlement ? <Loader2 className="w-3 h-3 animate-spin" /> : <CheckCircle className="w-3 h-3" />}
                          className="text-xs"
                        >
                          Run Settlement
                        </Button>
                      </div>
                    </div>

                    <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                      {settlementStatus.pendingApproval > 0 && (
                        <div className="bg-warning-100 rounded-lg p-3 dark:bg-warning-500/20">
                          <p className="text-xs font-medium text-warning-700 dark:text-warning-300">Pending Approval</p>
                          {/* Phase 12 Task E: .stat-value-xs replaces the raw
                              `text-xl font-bold`; the old `text-warning-900`
                              (no dark variant) becomes a -700/dark:-300 pair. */}
                          <p className="stat-value-xs text-warning-700 dark:text-warning-300">{settlementStatus.pendingApproval}</p>
                          <p className="text-xs text-warning-600 dark:text-warning-300">Manual positions</p>
                        </div>
                      )}
                      <div className="bg-info-100 rounded-lg p-3 dark:bg-info-500/20">
                        <p className="text-xs font-medium text-info-700 dark:text-info-300">Committed Deposits</p>
                        <p className="stat-value-xs text-info-700 dark:text-info-300">{settlementStatus.committedDeposits}</p>
                        <p className="text-xs text-info-600 dark:text-info-300">{formatCurrency(settlementStatus.totalCommittedDeposits, 'AED')}</p>
                      </div>
                      <div className="bg-cat-2/10 rounded-lg p-3 dark:bg-cat-2/15">
                        <p className="text-xs font-medium text-cat-2">Committed Loans</p>
                        <p className="stat-value-xs text-cat-2">{settlementStatus.committedLoans}</p>
                        <p className="text-xs text-cat-2">{formatCurrency(settlementStatus.totalCommittedLoans, 'AED')}</p>
                      </div>
                      <div className="bg-neutral-100 rounded-lg p-3 dark:bg-primary-800">
                        <p className="text-xs font-medium text-neutral-700 dark:text-neutral-200">Net Committed</p>
                        <p className={cn(
                          'stat-value-xs',
                          settlementStatus.netCommitted >= 0 ? 'text-success-700 dark:text-success-300' : 'text-error-700 dark:text-error-300'
                        )}>
                          {formatCurrency(settlementStatus.netCommitted, 'AED')}
                        </p>
                        <p className="text-xs text-neutral-600 dark:text-neutral-300">EOD Movement</p>
                      </div>
                    </div>

                    <p className="text-xs text-info-600 mt-3 dark:text-info-300">
                      Positions awaiting EOD settlement. Sweep-created positions auto-commit; manual positions require approval.
                    </p>
                  </div>
                </Card>
              )}

              {/* Quick Actions */}
              <div className="flex gap-3">
                <Button leftIcon={<Plus className="w-4 h-4" />} onClick={() => setShowLoanModal(true)}>
                  New Loan
                </Button>
                <Button variant="outline" leftIcon={<Plus className="w-4 h-4" />} onClick={() => setShowDepositModal(true)}>
                  New Deposit
                </Button>
              </div>

              {/* Recent Activity */}
              <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                <div>
                  <h3 className="text-sm font-semibold text-primary-900 mb-3 dark:text-neutral-50">Recent Loans</h3>
                  {activeLoans.slice(0, 5).map(loan => (
                    <div key={loan.id} className="flex items-center justify-between py-2 border-b border-neutral-100 last:border-0 dark:border-primary-800/60">
                      <div>
                        <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{loan.loanReference}</p>
                        <p className="text-xs text-neutral-500 dark:text-neutral-400">{loan.lenderEntityName} → {loan.borrowerEntityName}</p>
                      </div>
                      <p className="text-sm font-semibold text-primary-900 dark:text-neutral-50">{formatCurrency(loan.outstandingAmount, loan.currency)}</p>
                    </div>
                  ))}
                  {activeLoans.length === 0 && (
                    <p className="text-sm text-neutral-500 py-4 text-center dark:text-neutral-400">No active loans</p>
                  )}
                </div>
                <div>
                  <h3 className="text-sm font-semibold text-primary-900 mb-3 dark:text-neutral-50">Recent Deposits</h3>
                  {activeDeposits.slice(0, 5).map(deposit => (
                    <div key={deposit.id} className="flex items-center justify-between py-2 border-b border-neutral-100 last:border-0 dark:border-primary-800/60">
                      <div>
                        <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{deposit.depositReference}</p>
                        <p className="text-xs text-neutral-500 dark:text-neutral-400">{deposit.depositorEntityName} → Treasury</p>
                      </div>
                      <p className="text-sm font-semibold text-success-600 dark:text-success-300">{formatCurrency(deposit.currentBalance, deposit.currency)}</p>
                    </div>
                  ))}
                  {activeDeposits.length === 0 && (
                    <p className="text-sm text-neutral-500 py-4 text-center dark:text-neutral-400">No active deposits</p>
                  )}
                </div>
              </div>
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
                  onCreateLoan={() => handleQuickLoan(entity)}
                  onCreateDeposit={() => handleQuickDeposit(entity)}
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

          {/* Loans Tab */}
          {activeTab === 'loans' && (
            <div className="space-y-4">
              <div className="flex justify-between items-center">
                <p className="text-sm text-neutral-500 dark:text-neutral-400">{loans.length} total loans</p>
                <Button size="sm" leftIcon={<Plus className="w-4 h-4" />} onClick={() => setShowLoanModal(true)}>
                  New Loan
                </Button>
              </div>
              {loans.length > 0 ? (
                <div className="overflow-x-auto">
                  <table className="data-table">
                    <thead className="data-table-header">
                      <tr>
                        <th className="data-table-header-cell">Reference</th>
                        <th className="data-table-header-cell">Lender</th>
                        <th className="data-table-header-cell">Borrower</th>
                        <th className="data-table-header-cell text-right">Principal</th>
                        <th className="data-table-header-cell text-right">Outstanding</th>
                        <th className="data-table-header-cell text-right">Rate</th>
                        <th className="data-table-header-cell text-right">Accrued</th>
                        <th className="data-table-header-cell text-center">Status</th>
                        <th className="data-table-header-cell w-20"></th>
                      </tr>
                    </thead>
                    <tbody>
                      {loans.map(loan => (
                        <LoanRow
                          key={loan.id}
                          loan={loan}
                          onRepay={() => { setSelectedLoan(loan); setShowRepayModal(true); }}
                          onApprove={() => handleApproveLoan(loan)}
                          onReject={() => handleRejectLoan(loan)}
                        />
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <EmptyState
                  icon={<CreditCard className="w-12 h-12" />}
                  title="No loans found"
                  description="Create intercompany loans to fund entities within the group"
                  action={<Button onClick={() => setShowLoanModal(true)} leftIcon={<Plus className="w-4 h-4" />}>New Loan</Button>}
                />
              )}
            </div>
          )}

          {/* Deposits Tab */}
          {activeTab === 'deposits' && (
            <div className="space-y-4">
              <div className="flex justify-between items-center">
                <p className="text-sm text-neutral-500 dark:text-neutral-400">{deposits.length} total deposits</p>
                <Button size="sm" leftIcon={<Plus className="w-4 h-4" />} onClick={() => setShowDepositModal(true)}>
                  New Deposit
                </Button>
              </div>
              {deposits.length > 0 ? (
                <div className="overflow-x-auto">
                  <table className="data-table">
                    <thead className="data-table-header">
                      <tr>
                        <th className="data-table-header-cell">Reference</th>
                        <th className="data-table-header-cell">Depositor</th>
                        <th className="data-table-header-cell">Treasury</th>
                        <th className="data-table-header-cell text-right">Principal</th>
                        <th className="data-table-header-cell text-right">Balance</th>
                        <th className="data-table-header-cell text-right">Rate</th>
                        <th className="data-table-header-cell text-right">Accrued</th>
                        <th className="data-table-header-cell text-center">Status</th>
                        <th className="data-table-header-cell w-20"></th>
                      </tr>
                    </thead>
                    <tbody>
                      {deposits.map(deposit => (
                        <DepositRow
                          key={deposit.id}
                          deposit={deposit}
                          onWithdraw={() => { setSelectedDeposit(deposit); setShowWithdrawModal(true); }}
                          onApprove={() => handleApproveDeposit(deposit)}
                          onReject={() => handleRejectDeposit(deposit)}
                        />
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <EmptyState
                  icon={<PiggyBank className="w-12 h-12" />}
                  title="No deposits found"
                  description="Create intercompany deposits for treasury management"
                  action={<Button onClick={() => setShowDepositModal(true)} leftIcon={<Plus className="w-4 h-4" />}>New Deposit</Button>}
                />
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
                              <Badge variant="info" size="sm" className="text-[10px]">
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

                        {/* Option B: Committed Balances (Awaiting EOD Settlement) */}
                        {((account.committedOutflow && account.committedOutflow > 0) || (account.committedInflow && account.committedInflow > 0)) && (
                          <div className="mt-3 pt-3 border-t border-info-200 bg-info-50 -mx-4 px-4 py-2 dark:border-info-500/30 dark:bg-info-500/10">
                            <p className="text-xs font-medium text-info-700 uppercase tracking-wide mb-2 flex items-center gap-1 dark:text-info-300">
                              <Clock className="w-3 h-3" />
                              Committed (EOD Settlement)
                            </p>
                            <div className="space-y-1">
                              {account.committedOutflow && account.committedOutflow > 0 && (
                                <div className="flex justify-between text-sm">
                                  <span className="text-info-600 dark:text-info-300">Outflow (Deposits)</span>
                                  <span className="font-medium text-error-600 dark:text-error-300">
                                    -{formatCurrency(account.committedOutflow, account.currencyCode)}
                                  </span>
                                </div>
                              )}
                              {account.committedInflow && account.committedInflow > 0 && (
                                <div className="flex justify-between text-sm">
                                  <span className="text-info-600 dark:text-info-300">Inflow (Loans)</span>
                                  <span className="font-medium text-success-600 dark:text-success-300">
                                    +{formatCurrency(account.committedInflow, account.currencyCode)}
                                  </span>
                                </div>
                              )}
                              {account.effectiveAvailableBalance !== undefined && (
                                <div className="flex justify-between text-sm pt-1 border-t border-info-200 dark:border-info-500/30">
                                  <span className="text-info-700 font-medium dark:text-info-300">Effective Available</span>
                                  <span className={cn(
                                    'font-semibold',
                                    account.effectiveAvailableBalance >= 0 ? 'text-success-600 dark:text-success-300' : 'text-error-600 dark:text-error-300'
                                  )}>
                                    {formatCurrency(account.effectiveAvailableBalance, account.currencyCode)}
                                  </span>
                                </div>
                              )}
                            </div>
                          </div>
                        )}

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

      {/* Create Loan Modal - Enhanced */}
      <Modal
        isOpen={showLoanModal}
        onClose={() => setShowLoanModal(false)}
        title="Create Intercompany Loan"
        size="lg"
      >
        <div className="space-y-5">
          {/* Treasury Rate Preview */}
          {treasuryRates && (
            <div className="bg-warning-50 border border-warning-200 rounded-lg p-4 dark:border-warning-500/30 dark:bg-warning-500/10">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-xs font-medium text-warning-700 uppercase tracking-wide dark:text-warning-300">Treasury Indicative Lending Rate</p>
                  <p className="stat-value text-warning-900">{treasuryRates.indicativeLendingRate?.toFixed(2) || 'N/A'}%</p>
                  <p className="text-xs text-warning-600 mt-1 dark:text-warning-300">
                    {treasuryRates.lendingBaseRateType} {treasuryRates.lendingBaseRate}% + {treasuryRates.treasuryLendingSpread}% spread
                  </p>
                </div>
                <div className="text-right text-xs text-warning-600 dark:text-warning-300">
                  <p>Min: {formatCurrency(treasuryRates.minLoanAmount, treasuryRates.ihbCurrency)}</p>
                  {treasuryRates.maxLoanAmount && <p>Max: {formatCurrency(treasuryRates.maxLoanAmount, treasuryRates.ihbCurrency)}</p>}
                </div>
              </div>
            </div>
          )}

          {/* Parties Section */}
          <div>
            <p className="text-xs font-semibold text-neutral-500 uppercase tracking-wide mb-3 dark:text-neutral-400">Parties</p>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="field-label block mb-1">Lender Entity *</label>
                <Select
                  value={loanForm.lenderEntityId}
                  onChange={(e) => setLoanForm({ ...loanForm, lenderEntityId: e.target.value })}
                >
                  <option value="">Select lender...</option>
                  {lenders.map(e => (
                    <option key={e.id} value={e.id}>{e.entityName} ({e.entityCode})</option>
                  ))}
                </Select>
              </div>
              <div>
                <label className="field-label block mb-1">Borrower Entity *</label>
                <Select
                  value={loanForm.borrowerEntityId}
                  onChange={(e) => setLoanForm({ ...loanForm, borrowerEntityId: e.target.value })}
                >
                  <option value="">Select borrower...</option>
                  {borrowers.filter(e => e.id !== loanForm.lenderEntityId).map(e => (
                    <option key={e.id} value={e.id}>{e.entityName} ({e.entityCode})</option>
                  ))}
                </Select>
              </div>
            </div>
          </div>

          {/* Loan Details Section */}
          <div>
            <p className="text-xs font-semibold text-neutral-500 uppercase tracking-wide mb-3 dark:text-neutral-400">Loan Details</p>
            <div className="grid grid-cols-3 gap-4">
              <div>
                <label className="field-label block mb-1">Principal Amount *</label>
                <Input
                  type="number"
                  value={loanForm.principalAmount}
                  onChange={(e) => setLoanForm({ ...loanForm, principalAmount: e.target.value })}
                  placeholder="Enter amount"
                />
              </div>
              <div>
                <label className="field-label block mb-1">Currency</label>
                <CurrencyPicker
                  value={loanForm.currencyCode}
                  onChange={(c) => setLoanForm({ ...loanForm, currencyCode: c })}
                  extra={['SAR']}
                />
              </div>
              <div>
                <label className="field-label block mb-1">Interest Type</label>
                <Select
                  value={loanForm.interestType}
                  onChange={(e) => setLoanForm({ ...loanForm, interestType: e.target.value as 'FIXED' | 'FLOATING' })}
                >
                  <option value="FIXED">Fixed Rate</option>
                  <option value="FLOATING">Floating Rate</option>
                </Select>
              </div>
            </div>
          </div>

          {/* Interest Rate Section */}
          <div>
            <div className="flex items-center justify-between mb-3">
              <p className="text-xs font-semibold text-neutral-500 uppercase tracking-wide dark:text-neutral-400">Interest Rate</p>
              {treasuryRates && (
                <Button variant="ghost" size="sm" onClick={fillLoanRateFromTreasury} className="text-xs text-warning-600 hover:text-warning-700 dark:text-warning-300">
                  <TrendingUp className="w-3 h-3 mr-1" /> Use Treasury Rate
                </Button>
              )}
            </div>
            <div className="grid grid-cols-3 gap-4">
              <div>
                <label className="field-label block mb-1">Base Rate (%) *</label>
                <Input
                  type="number"
                  step="0.01"
                  value={loanForm.baseRate}
                  onChange={(e) => setLoanForm({ ...loanForm, baseRate: e.target.value })}
                  placeholder="e.g., 5.00"
                />
              </div>
              <div>
                <label className="field-label block mb-1">Rate Type</label>
                <Select
                  value={loanForm.baseRateType}
                  onChange={(e) => setLoanForm({ ...loanForm, baseRateType: e.target.value })}
                >
                  <option value="EIBOR">EIBOR</option>
                  <option value="SOFR">SOFR</option>
                  <option value="LIBOR">LIBOR</option>
                  <option value="FIXED">Fixed</option>
                </Select>
              </div>
              <div>
                <label className="field-label block mb-1">Repayment</label>
                <Select
                  value={loanForm.repaymentFrequency}
                  onChange={(e) => setLoanForm({ ...loanForm, repaymentFrequency: e.target.value as any })}
                >
                  <option value="MONTHLY">Monthly</option>
                  <option value="QUARTERLY">Quarterly</option>
                  <option value="SEMI_ANNUALLY">Semi-Annually</option>
                  <option value="ANNUALLY">Annually</option>
                  <option value="BULLET">Bullet (At Maturity)</option>
                </Select>
              </div>
            </div>
          </div>

          {/* Dates Section */}
          <div>
            <p className="text-xs font-semibold text-neutral-500 uppercase tracking-wide mb-3 dark:text-neutral-400">Dates</p>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="field-label block mb-1">Disbursement Date</label>
                <Input
                  type="date"
                  value={loanForm.disbursementDate}
                  onChange={(e) => setLoanForm({ ...loanForm, disbursementDate: e.target.value })}
                />
                <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">Defaults to today if empty</p>
              </div>
              <div>
                <label className="field-label block mb-1">Maturity Date *</label>
                <Input
                  type="date"
                  value={loanForm.maturityDate}
                  onChange={(e) => setLoanForm({ ...loanForm, maturityDate: e.target.value })}
                />
              </div>
            </div>
          </div>

          {/* Interest Preview */}
          {(() => {
            const preview = calculateEstimatedInterest();
            return preview && (
              <div className="bg-neutral-50 border border-neutral-200 rounded-lg p-4 dark:bg-primary-950 dark:border-primary-800">
                <p className="text-xs font-semibold text-neutral-500 uppercase tracking-wide mb-2 dark:text-neutral-400">Estimated Interest Preview</p>
                <div className="grid grid-cols-3 gap-4 text-center">
                  <div>
                    <p className="text-xs text-neutral-500 dark:text-neutral-400">Tenor</p>
                    <p className="text-sm font-semibold text-neutral-900 dark:text-neutral-50">{preview.days} days</p>
                  </div>
                  <div>
                    <p className="text-xs text-neutral-500 dark:text-neutral-400">Est. Interest</p>
                    <p className="text-sm font-semibold text-success-600 dark:text-success-300">{formatCurrency(preview.interest, loanForm.currencyCode)}</p>
                  </div>
                  <div>
                    <p className="text-xs text-neutral-500 dark:text-neutral-400">Total Repayable</p>
                    <p className="text-sm font-semibold text-primary-900 dark:text-neutral-50">{formatCurrency(preview.total, loanForm.currencyCode)}</p>
                  </div>
                </div>
              </div>
            );
          })()}

          {/* Actions */}
          <div className="flex gap-3 pt-4 border-t">
            <Button variant="outline" className="flex-1" onClick={() => setShowLoanModal(false)}>Cancel</Button>
            <Button
              className="flex-1"
              onClick={handleCreateLoan}
              disabled={!loanForm.lenderEntityId || !loanForm.borrowerEntityId || !loanForm.principalAmount || !loanForm.baseRate || !loanForm.maturityDate}
            >
              Create Loan
            </Button>
          </div>
        </div>
      </Modal>

      {/* Create Deposit Modal - Enhanced */}
      <Modal
        isOpen={showDepositModal}
        onClose={() => setShowDepositModal(false)}
        title="Create Intercompany Deposit"
        size="lg"
      >
        <div className="space-y-5">
          {/* Treasury Rate Preview */}
          {treasuryRates && (
            <div className="bg-success-50 border border-success-200 rounded-lg p-4 dark:border-success-500/30 dark:bg-success-500/10">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-xs font-medium text-success-700 uppercase tracking-wide dark:text-success-300">Treasury Indicative Deposit Rate</p>
                  <p className="stat-value text-success-900">{treasuryRates.indicativeDepositRate?.toFixed(2) || 'N/A'}%</p>
                  <p className="text-xs text-success-600 mt-1 dark:text-success-300">
                    Depositors earn this rate on their intercompany deposits
                  </p>
                </div>
                <div className="text-right text-xs text-success-600 dark:text-success-300">
                  <p>Min: {formatCurrency(treasuryRates.minDepositAmount, treasuryRates.ihbCurrency)}</p>
                </div>
              </div>
            </div>
          )}

          {/* Depositor Section */}
          <div>
            <p className="text-xs font-semibold text-neutral-500 uppercase tracking-wide mb-3 dark:text-neutral-400">Depositor</p>
            <div>
              <label className="field-label block mb-1">Depositor Entity *</label>
              <Select
                value={depositForm.depositorEntityId}
                onChange={(e) => setDepositForm({ ...depositForm, depositorEntityId: e.target.value })}
              >
                <option value="">Select depositor...</option>
                {entities.map(e => (
                  <option key={e.id} value={e.id}>{e.entityName} ({e.entityCode})</option>
                ))}
              </Select>
              <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">Treasury Center will be auto-resolved as the recipient</p>
            </div>
          </div>

          {/* Deposit Details Section */}
          <div>
            <p className="text-xs font-semibold text-neutral-500 uppercase tracking-wide mb-3 dark:text-neutral-400">Deposit Details</p>
            <div className="grid grid-cols-3 gap-4">
              <div>
                <label className="field-label block mb-1">Principal Amount *</label>
                <Input
                  type="number"
                  value={depositForm.principalAmount}
                  onChange={(e) => setDepositForm({ ...depositForm, principalAmount: e.target.value })}
                  placeholder="Enter amount"
                />
              </div>
              <div>
                <label className="field-label block mb-1">Currency</label>
                <CurrencyPicker
                  value={depositForm.currencyCode}
                  onChange={(c) => setDepositForm({ ...depositForm, currencyCode: c })}
                  extra={['SAR']}
                />
              </div>
              <div>
                <label className="field-label block mb-1">Deposit Type</label>
                <Select
                  value={depositForm.depositType}
                  onChange={(e) => setDepositForm({ ...depositForm, depositType: e.target.value as 'CALL' | 'FIXED' })}
                >
                  <option value="CALL">Call (On Demand)</option>
                  <option value="FIXED">Fixed Term</option>
                </Select>
              </div>
            </div>
          </div>

          {/* Interest Rate Section */}
          <div>
            <div className="flex items-center justify-between mb-3">
              <p className="text-xs font-semibold text-neutral-500 uppercase tracking-wide dark:text-neutral-400">Interest Rate (Optional)</p>
              {treasuryRates && (
                <Button variant="ghost" size="sm" onClick={fillDepositRateFromTreasury} className="text-xs text-success-600 hover:text-success-700 dark:text-success-300">
                  <TrendingUp className="w-3 h-3 mr-1" /> Use Treasury Rate
                </Button>
              )}
            </div>
            <div>
              <label className="field-label block mb-1">Interest Rate (%)</label>
              <Input
                type="number"
                step="0.01"
                value={depositForm.interestRate}
                onChange={(e) => setDepositForm({ ...depositForm, interestRate: e.target.value })}
                placeholder="Leave empty for treasury default"
              />
              <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">If empty, treasury will determine the rate based on entity configuration</p>
            </div>
          </div>

          {/* Dates Section */}
          <div>
            <p className="text-xs font-semibold text-neutral-500 uppercase tracking-wide mb-3 dark:text-neutral-400">Dates</p>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="field-label block mb-1">Deposit Date</label>
                <Input
                  type="date"
                  value={depositForm.depositDate}
                  onChange={(e) => setDepositForm({ ...depositForm, depositDate: e.target.value })}
                />
                <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">Defaults to today if empty</p>
              </div>
              <div>
                <label className="field-label block mb-1">Maturity Date {depositForm.depositType === 'FIXED' && '*'}</label>
                <Input
                  type="date"
                  value={depositForm.maturityDate}
                  onChange={(e) => setDepositForm({ ...depositForm, maturityDate: e.target.value })}
                  disabled={depositForm.depositType === 'CALL'}
                />
                <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">{depositForm.depositType === 'CALL' ? 'Not applicable for call deposits' : 'Required for fixed term deposits'}</p>
              </div>
            </div>
          </div>

          {/* Actions */}
          <div className="flex gap-3 pt-4 border-t">
            <Button variant="outline" className="flex-1" onClick={() => setShowDepositModal(false)}>Cancel</Button>
            <Button
              className="flex-1"
              onClick={handleCreateDeposit}
              disabled={!depositForm.depositorEntityId || !depositForm.principalAmount}
            >
              Create Deposit
            </Button>
          </div>
        </div>
      </Modal>

      {/* Repay Modal - Enhanced */}
      <Modal
        isOpen={showRepayModal}
        onClose={() => { setShowRepayModal(false); setSelectedLoan(null); setIncludeInterest(true); }}
        title="Repay Loan"
        size="md"
      >
        {selectedLoan && (() => {
          const loanCurrency = selectedLoan.currency || 'AED';
          const loanOutstanding = selectedLoan.outstandingAmount ?? 0;
          const loanAccruedInterest = selectedLoan.accruedInterest ?? 0;
          const loanInterestRate = selectedLoan.interestRate ?? 0;
          const totalDue = loanOutstanding + loanAccruedInterest;

          return (
          <div className="space-y-5">
            {/* Loan Summary */}
            <div className="bg-info-50 border border-info-200 rounded-lg p-4 dark:border-info-500/30 dark:bg-info-500/10">
              <p className="text-xs font-medium text-info-700 uppercase tracking-wide mb-3 dark:text-info-300">Loan Summary</p>
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <p className="text-xs text-info-600 dark:text-info-300">Loan Reference</p>
                  <p className="text-sm font-semibold text-info-900">{selectedLoan.loanReference || '-'}</p>
                </div>
                <div>
                  <p className="text-xs text-info-600 dark:text-info-300">Interest Rate</p>
                  <p className="text-sm font-semibold text-info-900">{loanInterestRate.toFixed(2)}%</p>
                </div>
                <div>
                  <p className="text-xs text-info-600 dark:text-info-300">Outstanding Principal</p>
                  <p className="text-sm font-semibold text-info-900">{formatCurrency(loanOutstanding, loanCurrency)}</p>
                </div>
                <div>
                  <p className="text-xs text-info-600 dark:text-info-300">Accrued Interest</p>
                  <p className="text-sm font-semibold text-success-600 dark:text-success-300">{formatCurrency(loanAccruedInterest, loanCurrency)}</p>
                </div>
              </div>
              <div className="mt-3 pt-3 border-t border-info-200 dark:border-info-500/30">
                <div className="flex justify-between">
                  <p className="text-sm font-medium text-info-700 dark:text-info-300">Total Due</p>
                  <p className="stat-value-sm text-info-900">{formatCurrency(totalDue, loanCurrency)}</p>
                </div>
              </div>
            </div>

            {/* Quick Actions */}
            <div className="flex gap-2">
              <Button
                variant="outline"
                size="sm"
                className="flex-1"
                onClick={() => setRepayAmount(loanOutstanding.toString())}
              >
                Pay Principal Only
              </Button>
              <Button
                variant="outline"
                size="sm"
                className="flex-1"
                onClick={() => setRepayAmount(totalDue.toString())}
              >
                Pay Full Balance
              </Button>
            </div>

            {/* Repayment Amount */}
            <div>
              <label className="field-label block mb-1">Repayment Amount</label>
              <Input
                type="number"
                value={repayAmount}
                onChange={(e) => setRepayAmount(e.target.value)}
                placeholder="Enter amount"
              />
            </div>

            {/* Include Interest Toggle */}
            <div className="flex items-center gap-3 p-3 bg-neutral-50 rounded-lg dark:bg-primary-950">
              <input
                type="checkbox"
                id="includeInterest"
                checked={includeInterest}
                onChange={(e) => setIncludeInterest(e.target.checked)}
                className="w-4 h-4 text-primary-600 rounded border-neutral-300 focus:ring-primary-500 dark:text-primary-200 dark:border-primary-700"
              />
              <label htmlFor="includeInterest" className="text-sm text-neutral-700 dark:text-neutral-200">
                Include accrued interest in payment
                <span className="block text-xs text-neutral-500 dark:text-neutral-400">When checked, interest is paid first from the repayment amount</span>
              </label>
            </div>

            {/* Actions */}
            <div className="flex gap-3 pt-4 border-t">
              <Button variant="outline" className="flex-1" onClick={() => setShowRepayModal(false)}>Cancel</Button>
              <Button
                className="flex-1"
                onClick={handleRepayLoan}
                disabled={!repayAmount || parseFloat(repayAmount) <= 0}
              >
                Process Repayment
              </Button>
            </div>
          </div>
        );
        })()}
      </Modal>

      {/* Withdraw Modal - Enhanced */}
      <Modal
        isOpen={showWithdrawModal}
        onClose={() => { setShowWithdrawModal(false); setSelectedDeposit(null); setBreakDeposit(false); }}
        title="Withdraw from Deposit"
        size="md"
      >
        {selectedDeposit && (() => {
          const depositCurrency = selectedDeposit.currency || 'AED';
          const depositBalance = selectedDeposit.currentBalance ?? 0;
          const depositAccruedInterest = selectedDeposit.accruedInterest ?? 0;
          const depositInterestRate = selectedDeposit.interestRate ?? 0;
          const totalValue = depositBalance + depositAccruedInterest;
          const isBeforeMaturity = selectedDeposit.maturityDate && new Date(selectedDeposit.maturityDate) > new Date();

          return (
          <div className="space-y-5">
            {/* Deposit Summary */}
            <div className="bg-success-50 border border-success-200 rounded-lg p-4 dark:border-success-500/30 dark:bg-success-500/10">
              <p className="text-xs font-medium text-success-700 uppercase tracking-wide mb-3 dark:text-success-300">Deposit Summary</p>
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <p className="text-xs text-success-600 dark:text-success-300">Deposit Reference</p>
                  <p className="text-sm font-semibold text-success-900">{selectedDeposit.depositReference || '-'}</p>
                </div>
                <div>
                  <p className="text-xs text-success-600 dark:text-success-300">Interest Rate</p>
                  <p className="text-sm font-semibold text-success-900">{depositInterestRate.toFixed(2)}%</p>
                </div>
                <div>
                  <p className="text-xs text-success-600 dark:text-success-300">Current Balance</p>
                  <p className="text-sm font-semibold text-success-900">{formatCurrency(depositBalance, depositCurrency)}</p>
                </div>
                <div>
                  <p className="text-xs text-success-600 dark:text-success-300">Accrued Interest</p>
                  <p className="text-sm font-semibold text-success-600 dark:text-success-300">{formatCurrency(depositAccruedInterest, depositCurrency)}</p>
                </div>
              </div>
              <div className="mt-3 pt-3 border-t border-success-200 dark:border-success-500/30">
                <div className="flex justify-between">
                  <p className="text-sm font-medium text-success-700 dark:text-success-300">Total Value</p>
                  <p className="stat-value-sm text-success-900">{formatCurrency(totalValue, depositCurrency)}</p>
                </div>
              </div>
              {selectedDeposit.maturityDate && (
                <div className="mt-2 text-xs text-success-600 dark:text-success-300">
                  Maturity: {formatDate(selectedDeposit.maturityDate)}
                </div>
              )}
            </div>

            {/* Quick Actions */}
            <div className="flex gap-2">
              <Button
                variant="outline"
                size="sm"
                className="flex-1"
                onClick={() => setWithdrawAmount((depositBalance / 2).toString())}
              >
                Partial (50%)
              </Button>
              <Button
                variant="outline"
                size="sm"
                className="flex-1"
                onClick={() => setWithdrawAmount(depositBalance.toString())}
              >
                Full Withdrawal
              </Button>
            </div>

            {/* Withdrawal Amount */}
            <div>
              <label className="field-label block mb-1">Withdrawal Amount</label>
              <Input
                type="number"
                value={withdrawAmount}
                onChange={(e) => setWithdrawAmount(e.target.value)}
                placeholder="Enter amount"
              />
            </div>

            {/* Break Deposit Warning (for fixed deposits) */}
            {isBeforeMaturity && (
              <div className="p-3 bg-warning-50 border border-warning-200 rounded-lg dark:bg-warning-500/10 dark:border-warning-500/30">
                <div className="flex items-start gap-3">
                  <AlertCircle className="w-5 h-5 text-warning-600 flex-shrink-0 mt-0.5 dark:text-warning-300" />
                  <div>
                    <p className="text-sm font-medium text-warning-800 dark:text-warning-300">Early Withdrawal Warning</p>
                    <p className="text-xs text-warning-700 mt-1 dark:text-warning-300">
                      This deposit has not yet matured. Early withdrawal may incur a penalty or reduced interest.
                    </p>
                    <div className="flex items-center gap-2 mt-3">
                      <input
                        type="checkbox"
                        id="breakDeposit"
                        checked={breakDeposit}
                        onChange={(e) => setBreakDeposit(e.target.checked)}
                        className="w-4 h-4 text-warning-600 rounded border-warning-300 focus:ring-warning-500 dark:text-warning-300"
                      />
                      <label htmlFor="breakDeposit" className="text-sm text-warning-700 dark:text-warning-300">
                        I understand and want to break this deposit early
                      </label>
                    </div>
                  </div>
                </div>
              </div>
            )}

            {/* Actions */}
            <div className="flex gap-3 pt-4 border-t">
              <Button variant="outline" className="flex-1" onClick={() => setShowWithdrawModal(false)}>Cancel</Button>
              <Button
                className="flex-1"
                onClick={handleWithdrawDeposit}
                disabled={!withdrawAmount || parseFloat(withdrawAmount) <= 0 || (isBeforeMaturity && !breakDeposit)}
              >
                Process Withdrawal
              </Button>
            </div>
          </div>
        );
        })()}
      </Modal>

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
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
              <div className="p-3 bg-success-50 rounded-lg border border-success-100 dark:bg-success-500/10 dark:border-success-500/30">
                <p className="text-xs text-success-600 font-medium dark:text-success-300">Total Lent Out</p>
                <p className="stat-value-sm text-success-700 dark:text-success-300">
                  {formatCurrency(selectedEntity.totalLentOut ?? 0, selectedEntity.ihbCurrency || 'AED')}
                </p>
              </div>
              <div className="p-3 bg-info-50 rounded-lg border border-info-100 dark:bg-info-500/10 dark:border-info-500/30">
                <p className="text-xs text-info-600 font-medium dark:text-info-300">Total Deposited</p>
                <p className="stat-value-sm text-info-700 dark:text-info-300">
                  {formatCurrency(selectedEntity.totalDeposited ?? 0, selectedEntity.ihbCurrency || 'AED')}
                </p>
              </div>
              <div className="p-3 bg-error-50 rounded-lg border border-error-100 dark:bg-error-500/10 dark:border-error-500/30">
                <p className="text-xs text-error-600 font-medium dark:text-error-300">Current Exposure</p>
                <p className="stat-value-sm text-error-700 dark:text-error-300">
                  {formatCurrency(selectedEntity.ihbCurrentExposure ?? 0, selectedEntity.ihbCurrency || 'AED')}
                </p>
              </div>
              <div className={cn(
                "p-3 rounded-lg border",
                (selectedEntity.netIhbPosition ?? 0) >= 0
                  ? "bg-success-50 border-success-100 dark:bg-success-500/10 dark:border-success-500/30"
                  : "bg-error-50 border-error-100 dark:bg-error-500/10 dark:border-error-500/30"
              )}>
                <p className={cn(
                  "text-xs font-medium",
                  (selectedEntity.netIhbPosition ?? 0) >= 0 ? "text-success-600 dark:text-success-300" : "text-error-600 dark:text-error-300"
                )}>Net IHB Position</p>
                <p className={cn(
                  "stat-value-sm",
                  (selectedEntity.netIhbPosition ?? 0) >= 0 ? "text-success-700 dark:text-success-300" : "text-error-700 dark:text-error-300"
                )}>
                  {formatCurrency(selectedEntity.netIhbPosition ?? 0, selectedEntity.ihbCurrency || 'AED')}
                </p>
              </div>
            </div>

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

            {/* Related Transactions from position data */}
            {entityPosition && (
              <div className="space-y-4">
                {/* Active Loans */}
                {entityPosition.loans && entityPosition.loans.length > 0 && (
                  <div>
                    <p className="text-sm font-semibold text-neutral-900 mb-2 dark:text-neutral-50">Active Loans ({entityPosition.loans.length})</p>
                    <div className="space-y-2 max-h-40 overflow-y-auto">
                      {entityPosition.loans.map((loan: any) => (
                        <div key={loan.id} className="flex items-center justify-between p-2 bg-neutral-50 rounded border dark:bg-primary-950">
                          <div>
                            <p className="text-xs font-mono text-neutral-700 dark:text-neutral-200">{loan.loanReference || loan.id}</p>
                            <p className="text-xs text-neutral-500 dark:text-neutral-400">{loan.lenderEntityName || 'Lender'} → {loan.borrowerEntityName || 'Borrower'}</p>
                          </div>
                          <p className="text-sm font-semibold text-primary-900 dark:text-neutral-50">
                            {formatCurrency(loan.outstandingAmount ?? 0, loan.currency || 'AED')}
                          </p>
                        </div>
                      ))}
                    </div>
                  </div>
                )}

                {/* Active Deposits */}
                {entityPosition.deposits && entityPosition.deposits.length > 0 && (
                  <div>
                    <p className="text-sm font-semibold text-neutral-900 mb-2 dark:text-neutral-50">Active Deposits ({entityPosition.deposits.length})</p>
                    <div className="space-y-2 max-h-40 overflow-y-auto">
                      {entityPosition.deposits.map((deposit: any) => (
                        <div key={deposit.id} className="flex items-center justify-between p-2 bg-neutral-50 rounded border dark:bg-primary-950">
                          <div>
                            <p className="text-xs font-mono text-neutral-700 dark:text-neutral-200">{deposit.depositReference || deposit.id}</p>
                            <p className="text-xs text-neutral-500 dark:text-neutral-400">{deposit.depositorEntityName || 'Depositor'}</p>
                          </div>
                          <p className="text-sm font-semibold text-success-600 dark:text-success-300">
                            {formatCurrency(deposit.currentBalance ?? 0, deposit.currency || 'AED')}
                          </p>
                        </div>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            )}

            {/* Actions */}
            <div className="flex gap-3 pt-4 border-t">
              <Button variant="outline" className="flex-1" onClick={() => setShowPositionModal(false)}>
                Close
              </Button>
              {selectedEntity.canLend && !selectedEntity.canBorrow && (
                <Button
                  className="flex-1 bg-warning-600 hover:bg-warning-700"
                  onClick={() => { setShowPositionModal(false); handleQuickLoan(selectedEntity); }}
                >
                  <Banknote className="w-4 h-4 mr-1" /> Create Loan
                </Button>
              )}
              {selectedEntity.canBorrow && (
                <Button
                  className="flex-1"
                  onClick={() => { setShowPositionModal(false); handleQuickDeposit(selectedEntity); }}
                >
                  <PiggyBank className="w-4 h-4 mr-1" /> Create Deposit
                </Button>
              )}
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