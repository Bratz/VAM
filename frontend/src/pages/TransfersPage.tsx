import { Page } from '../components/layout/Page';
/**
 * TransfersPage.tsx
 *
 * Fund Transfers Page - Swiss Minimalism Design
 *
 * Features:
 * - Internal VA-to-VA transfers
 * - ISO 20022 outward payments (pain.001)
 * - Bulk transfers
 * - Transfer history with real-time status
 * - POBO transfers on behalf of subsidiaries
 */

import React, { useState, useEffect } from 'react';
import {
  Send,
  ArrowRight,
  ArrowLeftRight,
  ArrowDownLeft,
  ArrowUpRight,
  Building2,
  Loader2,
  CheckCircle,
  XCircle,
  Clock,
  AlertCircle,
  AlertTriangle,
  RefreshCw,
  Download,
  Plus,
  FileCode,
  ChevronRight,
  Globe,
  Trash2,
  Copy,
  TrendingUp,
  Wallet,
  CreditCard,
  Users,
  X,
  Briefcase,
  UserCheck,
  Search,
  Eye,
  BookOpen,
  FileText,
  Hash,
  Calendar,
  DollarSign,
  ArrowRightLeft,
} from 'lucide-react';
import { Card, Button, Badge, Input, Select , StatusIconBadge } from '../components/ui';
import { Modal, Stepper } from '../components/ui/enhanced';
import { formatCurrency, formatRelativeTime, cn } from '../utils';
import {
  transactionsApi,
  iso20022Api,
  virtualAccountsApi,
  corporatesApi,
  partiesApi,
  programsApi,
  legalEntityApi,
  type Transaction,
  type TransactionDetail,
  type TransferRequest,
  type BulkTransferRequest,
  type BulkTransferResponse,
  type VirtualAccount,
  type Corporate,
  type LegalEntity,
  type Iso20022OutwardPaymentRequest,
  type Party,
  type PartyBankAccount,
  type TransferPreviewResponse,
  type PaymentPreviewResponse,
  type FeeBreakdown,
} from '../services/api';

import toast from 'react-hot-toast';

// Helper to check if account is a transaction VA (only these can send/receive transfers)
const isTransactionVa = (account: VirtualAccount): boolean => {
  // Transaction VAs are leaf-level accounts that can hold funds and transact
  // Exclude header/aggregation accounts, mirrors, and system accounts
  const category = account.accountCategory;
  if (!category) return true; // Default to allowing if not categorized (backward compatibility)
  return category === 'TRANSACTION' || category === 'COLLECTION' || category === 'DISBURSEMENT';
};

// ============================================================================
// TYPES
// ============================================================================

type TransferType = 'internal' | 'outward' | 'inward' | 'bulk';
type TabType = 'new' | 'history';

interface Program {
  id: string;
  programCode: string;
  programName: string;
  programType?: string;
  corporateId: string;
  status: string;
}

interface Beneficiary {
  party: Party;
  bankAccounts: PartyBankAccount[];
}

interface TransferFormData {
  fromVaId: string;
  toVaId: string;
  amount: string;
  currency: string;
  description: string;
  valueDate: string;
  // Outward payment fields (creditor = beneficiary)
  creditorName: string;
  creditorAccount: string;
  creditorBic: string;
  creditorBankName: string;
  remittanceInfo: string;
  isPobo: boolean;
  behalfOfEntity: string;
  behalfOfVaId: string;
  selectedBeneficiaryId: string;
  selectedBankAccountId: string;
  // Inward payment fields (debtor = payer/remitter)
  debtorName: string;
  debtorAccount: string;
  debtorBic: string;
  debtorBankName: string;
  selectedPayerId: string;
  selectedPayerBankAccountId: string;
  targetVibanOrVa: string;  // VIBAN or VA number for inward routing
}

interface BulkTransferItem {
  id: string;
  destinationVaId: string;
  destinationVaName: string;
  amount: string;
  description: string;
  valueDate: string;
}

// ============================================================================
// LOADING COMPONENT
// ============================================================================

const LoadingSpinner: React.FC = () => (
  <div className="flex items-center justify-center py-12">
    <Loader2 className="w-6 h-6 animate-spin text-primary-600 dark:text-primary-200" />
  </div>
);

// ============================================================================
// STAT CARD COMPONENT
// ============================================================================

interface StatCardProps {
  title: string;
  value: string;
  subtitle?: string;
  icon: React.ReactNode;
  variant?: 'primary' | 'success' | 'warning' | 'info';
  delay?: string;
}

const StatCard: React.FC<StatCardProps> = ({ title, value, subtitle, icon, variant = 'primary', delay = '0s' }) => {
  // Was missing dark: entirely, so every StatCard rendered as a washed-out
  // white gradient tile in dark mode regardless of variant.
  const variants = {
    primary: 'from-primary-50/50 via-white to-primary-50/30 border-primary-200/60 dark:from-primary-900 dark:via-primary-900 dark:to-primary-900',
    success: 'from-success-50/50 via-white to-success-50/30 border-success-200/60 dark:from-primary-900 dark:via-primary-900 dark:to-primary-900',
    warning: 'from-warning-50/50 via-white to-warning-50/30 border-warning-200/60 dark:from-primary-900 dark:via-primary-900 dark:to-primary-900',
    info: 'from-info-50/50 via-white to-info-50/30 border-info-200/60 dark:from-primary-900 dark:via-primary-900 dark:to-primary-900',
  };

  const iconBg = {
    primary: 'bg-primary-100 dark:bg-primary-700',
    success: 'bg-success-100 dark:bg-success-500/20',
    warning: 'bg-warning-100 dark:bg-warning-500/20',
    info: 'bg-info-100 dark:bg-info-500/20',
  };

  const iconColor = {
    primary: 'text-primary-600 dark:text-primary-200',
    success: 'text-success-600 dark:text-success-300',
    warning: 'text-warning-600 dark:text-warning-300',
    info: 'text-info-600 dark:text-info-300',
  };

  return (
    <Card
      className={cn('bg-gradient-to-br animate-fade-in', variants[variant])}
      style={{ animationDelay: delay }}
    >
      <div className="flex items-start justify-between">
        <div>
          <p className="text-xs text-neutral-500 uppercase tracking-wider mb-1 dark:text-neutral-400">{title}</p>
          <p className="stat-value-sm">{value}</p>
          {subtitle && <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">{subtitle}</p>}
        </div>
        <div className={cn('w-10 h-10 rounded-xl flex items-center justify-center', iconBg[variant])}>
          <div className={iconColor[variant]}>{icon}</div>
        </div>
      </div>
    </Card>
  );
};

// ============================================================================
// TRANSFER TYPE CARD
// ============================================================================

interface TransferTypeCardProps {
  id: TransferType;
  label: string;
  description: string;
  icon: React.ReactNode;
  selected: boolean;
  onClick: () => void;
}

const TransferTypeCard: React.FC<TransferTypeCardProps> = ({
  label, description, icon, selected, onClick
}) => (
  <div
    onClick={onClick}
    className={cn(
      'p-5 rounded-2xl border-2 cursor-pointer transition-all duration-200',
      selected
        ? 'border-primary-500 bg-gradient-to-br from-primary-50 to-white shadow-lg shadow-primary-100/50 dark:from-primary-500/10 dark:to-primary-900 dark:from-primary-800/40'
        : 'border-neutral-200 hover:border-primary-300 hover:bg-neutral-50 dark:border-primary-800 dark:hover:bg-primary-800/50'
    )}
  >
    <div className={cn(
      'w-12 h-12 rounded-xl flex items-center justify-center mb-4 transition-colors',
      selected ? 'bg-primary-100 dark:bg-primary-700' : 'bg-neutral-100 dark:bg-primary-800'
    )}>
      <div className={selected ? 'text-primary-600 dark:text-primary-200' : 'text-neutral-500 dark:text-neutral-400'}>{icon}</div>
    </div>
    <p className="font-semibold text-primary-900 dark:text-neutral-50">{label}</p>
    <p className="text-sm text-neutral-500 mt-1 dark:text-neutral-400">{description}</p>
    {selected && (
      <div className="mt-3">
        <Badge variant="primary" size="sm" dot>Selected</Badge>
      </div>
    )}
  </div>
);

// ============================================================================
// ACCOUNT SELECTOR CARD
// ============================================================================

interface AccountSelectorProps {
  label: string;
  value: string;
  onChange: (value: string) => void;
  accounts: VirtualAccount[];
  excludeId?: string;
  variant?: 'source' | 'target';
  loading?: boolean;
}

const AccountSelector: React.FC<AccountSelectorProps> = ({
  label, value, onChange, accounts, excludeId, variant = 'source', loading
}) => {
  const selectedAccount = accounts.find(a => a.id === value);
  const filteredAccounts = excludeId ? accounts.filter(a => a.id !== excludeId) : accounts;

  const variantStyles = {
    source: {
      card: 'border-primary-200/60 bg-gradient-to-br from-primary-50/30 to-white',
      icon: 'bg-primary-100 dark:bg-primary-700',
      iconColor: 'text-primary-600 dark:text-primary-200',
    },
    target: {
      card: 'border-success-200/60 bg-gradient-to-br from-success-50/30 to-white',
      icon: 'bg-success-100 dark:bg-success-500/20',
      iconColor: 'text-success-600 dark:text-success-300',
    },
  };

  const styles = variantStyles[variant];

  return (
    <Card className={cn('animate-fade-in', styles.card)}>
      <Select
        label={label}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        disabled={loading}
        placeholder={`Select ${variant} account...`}
        options={filteredAccounts.map(acc => ({
          value: acc.id,
          label: `${acc.vaNumber} - ${acc.vaName} (${formatCurrency(acc.currentBalance, acc.currencyCode)})`
        }))}
      />
      {selectedAccount && (
        <div className="mt-4 p-4 bg-white/60 rounded-xl border border-neutral-100 dark:bg-primary-900/60 dark:border-primary-800/60">
          <div className="flex items-center gap-4">
            <div className={cn('w-12 h-12 rounded-xl flex items-center justify-center', styles.icon)}>
              <Building2 className={cn('w-6 h-6', styles.iconColor)} />
            </div>
            <div className="flex-1 min-w-0">
              <p className="font-semibold text-primary-900 truncate dark:text-neutral-50">{selectedAccount.vaName}</p>
              <p className="text-sm text-neutral-500 font-mono dark:text-neutral-400">{selectedAccount.vaNumber}</p>
            </div>
            <div className="text-right">
              <p className="text-xs text-neutral-500 dark:text-neutral-400">Balance</p>
              <p className="stat-value-xs">
                {formatCurrency(selectedAccount.currentBalance, selectedAccount.currencyCode)}
              </p>
            </div>
          </div>
        </div>
      )}
    </Card>
  );
};

// ============================================================================
// TRANSFER SUMMARY SIDEBAR (Enhanced Preview Panel)
// ============================================================================

interface TransferSummaryProps {
  transferType: TransferType;
  formData: TransferFormData;
  sourceAccount?: VirtualAccount;
  targetAccount?: VirtualAccount;
  accounts: VirtualAccount[];
  bulkItems: BulkTransferItem[];
  bulkTotal: number;
  canSubmit: boolean;
  submitting: boolean;
  onSubmit: () => void;
}

const TransferSummary: React.FC<TransferSummaryProps> = ({
  transferType,
  formData,
  sourceAccount,
  targetAccount,
  accounts,
  bulkItems,
  bulkTotal,
  canSubmit,
  submitting,
  onSubmit,
}) => {
  const amount = transferType === 'bulk' ? bulkTotal : parseFloat(formData.amount) || 0;

  // Fee preview state
  const [feePreview, setFeePreview] = useState<TransferPreviewResponse | PaymentPreviewResponse | null>(null);
  const [loadingFees, setLoadingFees] = useState(false);

  // Fetch fee preview when source, destination, and amount are filled
  useEffect(() => {
    const fetchFeePreview = async () => {
      if (!sourceAccount || amount <= 0) {
        setFeePreview(null);
        return;
      }

      setLoadingFees(true);
      try {
        if (transferType === 'internal' && targetAccount) {
          const result = await transactionsApi.previewTransfer({
            fromVaId: sourceAccount.id,
            toVaId: targetAccount.id,
            amount: amount,
          });
          setFeePreview(result.data || result);
        } else if (transferType === 'outward' && formData.creditorName) {
          const result = await transactionsApi.previewPayment({
            fromVaId: sourceAccount.id,
            amount: amount,
            channel: formData.channel || 'SWIFT',
          });
          setFeePreview(result.data || result);
        }
      } catch (error) {
        console.error('Failed to fetch fee preview:', error);
        setFeePreview(null);
      } finally {
        setLoadingFees(false);
      }
    };

    // Debounce the API call
    const timeoutId = setTimeout(fetchFeePreview, 500);
    return () => clearTimeout(timeoutId);
  }, [sourceAccount?.id, targetAccount?.id, amount, transferType, formData.creditorName, formData.channel]);

  // Get transfer type display info
  const getTypeInfo = () => {
    switch (transferType) {
      case 'internal':
        return { label: 'Internal Transfer', icon: ArrowLeftRight, color: 'info', description: 'VA-to-VA transfer within hierarchy' };
      case 'outward':
        return { label: 'Outward Payment', icon: Globe, color: 'primary', description: 'ISO 20022 pain.001 payment' };
      case 'inward':
        return { label: 'Inward Collection', icon: ArrowDownLeft, color: 'success', description: 'ROBO collection via VIBAN' };
      case 'bulk':
        return { label: 'Bulk Transfer', icon: Users, color: 'warning', description: `${bulkItems.length} recipient(s)` };
    }
  };
  const typeInfo = getTypeInfo();
  const TypeIcon = typeInfo.icon;

  // Check what's filled
  const hasSource = transferType === 'inward' ? !!formData.debtorName : !!sourceAccount;
  const hasDestination = transferType === 'internal' ? !!targetAccount :
                         transferType === 'outward' ? !!formData.creditorName :
                         transferType === 'inward' ? !!formData.targetVibanOrVa :
                         bulkItems.length > 0;
  const hasAmount = amount > 0;

  // Completion percentage
  const completionSteps = [hasSource, hasDestination, hasAmount].filter(Boolean).length;
  const completionPct = Math.round((completionSteps / 3) * 100);

  return (
    <div className="bg-white rounded-xl border border-neutral-200 shadow-sm sticky top-24 dark:bg-primary-900 dark:border-primary-800">
      {/* Header */}
      <div className="p-4 border-b border-neutral-100 bg-gradient-to-r from-neutral-50 to-white dark:border-primary-800/60 dark:from-primary-950 dark:to-primary-900">
        <div className="flex items-center gap-3">
          <div className={cn(
            'w-10 h-10 rounded-xl flex items-center justify-center',
            typeInfo.color === 'info' ? 'bg-info-100 dark:bg-info-500/20' :
            typeInfo.color === 'primary' ? 'bg-primary-100 dark:bg-primary-700' :
            typeInfo.color === 'success' ? 'bg-success-100 dark:bg-success-500/20' :
            'bg-warning-100 dark:bg-warning-500/20'
          )}>
            <TypeIcon className={cn(
              'w-5 h-5',
              typeInfo.color === 'info' ? 'text-info-600 dark:text-info-300' :
              typeInfo.color === 'primary' ? 'text-primary-600 dark:text-primary-200' :
              typeInfo.color === 'success' ? 'text-success-600 dark:text-success-300' :
              'text-warning-600 dark:text-warning-300'
            )} />
          </div>
          <div>
            <h3 className="font-semibold text-neutral-900 dark:text-neutral-50">{typeInfo.label}</h3>
            <p className="text-xs text-neutral-500 dark:text-neutral-400">{typeInfo.description}</p>
          </div>
        </div>
      </div>

      {/* Content */}
      <div className="p-4 space-y-4">
        {/* Progress Indicator */}
        <div className="space-y-2">
          <div className="flex justify-between text-xs text-neutral-500 dark:text-neutral-400">
            <span>Completion</span>
            <span>{completionPct}%</span>
          </div>
          <div className="h-2 bg-neutral-100 rounded-full overflow-hidden dark:bg-primary-800">
            <div
              className={cn(
                'h-full transition-all duration-300 rounded-full',
                completionPct === 100 ? 'bg-success-500' : 'bg-primary-500'
              )}
              style={{ width: `${completionPct}%` }}
            />
          </div>
        </div>

        {/* From Section */}
        <div className="pb-4 border-b border-neutral-100 dark:border-primary-800/60">
          <p className="text-xs text-neutral-500 uppercase tracking-wide mb-2 dark:text-neutral-400">
            {transferType === 'inward' ? 'Payer (Remitter)' : 'From'}
          </p>
          {transferType === 'inward' ? (
            formData.debtorName ? (
              <div className="flex items-center gap-3">
                <StatusIconBadge tone="success" icon={Building2} rounded="lg" className="dark:bg-success-500/20" />
                <div className="flex-1 min-w-0">
                  <p className="font-semibold text-neutral-900 truncate dark:text-neutral-50">{formData.debtorName}</p>
                  {formData.debtorAccount && (
                    <p className="text-xs text-neutral-500 font-mono truncate dark:text-neutral-400">{formData.debtorAccount}</p>
                  )}
                  {formData.debtorBic && (
                    <p className="text-xs text-neutral-400 dark:text-neutral-500">BIC: {formData.debtorBic}</p>
                  )}
                </div>
              </div>
            ) : (
              <p className="text-sm text-neutral-400 italic dark:text-neutral-500">Select payer...</p>
            )
          ) : sourceAccount ? (
            <div className="flex items-center gap-3">
              <StatusIconBadge tone="primary" icon={Wallet} rounded="lg" className="dark:bg-primary-700" />
              <div className="flex-1 min-w-0">
                <p className="font-semibold text-neutral-900 truncate dark:text-neutral-50">{sourceAccount.vaName}</p>
                <p className="text-xs text-neutral-500 font-mono dark:text-neutral-400">{sourceAccount.vaNumber}</p>
                <p className="text-xs text-success-600 font-medium dark:text-success-300">
                  Balance: {formatCurrency(sourceAccount.currentBalance, sourceAccount.currencyCode)}
                </p>
              </div>
            </div>
          ) : (
            <p className="text-sm text-neutral-400 italic dark:text-neutral-500">Select source account...</p>
          )}
        </div>

        {/* Arrow Indicator */}
        <div className="flex justify-center">
          <div className={cn(
            'w-8 h-8 rounded-full flex items-center justify-center',
            hasSource && hasDestination ? 'bg-success-100 dark:bg-success-500/20' : 'bg-neutral-100 dark:bg-primary-800'
          )}>
            {transferType === 'inward' ? (
              <ArrowDownLeft className={cn('w-4 h-4', hasSource && hasDestination ? 'text-success-600 dark:text-success-300' : 'text-neutral-400 dark:text-neutral-500')} />
            ) : (
              <ArrowRight className={cn('w-4 h-4', hasSource && hasDestination ? 'text-success-600 dark:text-success-300' : 'text-neutral-400 dark:text-neutral-500')} />
            )}
          </div>
        </div>

        {/* To Section */}
        <div className="pb-4 border-b border-neutral-100 dark:border-primary-800/60">
          <p className="text-xs text-neutral-500 uppercase tracking-wide mb-2 dark:text-neutral-400">
            {transferType === 'inward' ? 'Credit To (VIBAN/VA)' : 'To'}
          </p>
          {transferType === 'internal' && targetAccount ? (
            <div className="flex items-center gap-3">
              <StatusIconBadge tone="success" icon={Wallet} rounded="lg" className="dark:bg-success-500/20" />
              <div className="flex-1 min-w-0">
                <p className="font-semibold text-neutral-900 truncate dark:text-neutral-50">{targetAccount.vaName}</p>
                <p className="text-xs text-neutral-500 font-mono dark:text-neutral-400">{targetAccount.vaNumber}</p>
              </div>
            </div>
          ) : transferType === 'outward' && formData.creditorName ? (
            <div className="space-y-2">
              <div className="flex items-center gap-3">
                <StatusIconBadge tone="primary" icon={Globe} rounded="lg" className="dark:bg-primary-700" />
                <div className="flex-1 min-w-0">
                  <p className="font-semibold text-neutral-900 truncate dark:text-neutral-50">{formData.creditorName}</p>
                  <p className="text-xs text-neutral-500 font-mono truncate dark:text-neutral-400">{formData.creditorAccount}</p>
                </div>
              </div>
              {/* Enhanced beneficiary details */}
              <div className="bg-primary-50/50 rounded-lg p-2 space-y-1 text-xs">
                {formData.creditorBankName && (
                  <div className="flex justify-between">
                    <span className="text-neutral-500 dark:text-neutral-400">Bank</span>
                    <span className="text-neutral-700 font-medium truncate ml-2 dark:text-neutral-200">{formData.creditorBankName}</span>
                  </div>
                )}
                {formData.creditorBic && (
                  <div className="flex justify-between">
                    <span className="text-neutral-500 dark:text-neutral-400">BIC/SWIFT</span>
                    <span className="text-neutral-700 font-mono dark:text-neutral-200">{formData.creditorBic}</span>
                  </div>
                )}
                {formData.channel && (
                  <div className="flex justify-between">
                    <span className="text-neutral-500 dark:text-neutral-400">Channel</span>
                    <Badge variant="info" size="sm">{formData.channel}</Badge>
                  </div>
                )}
              </div>
            </div>
          ) : transferType === 'inward' && formData.targetVibanOrVa ? (
            <div className="flex items-center gap-3">
              <StatusIconBadge tone="success" icon={ArrowDownLeft} rounded="lg" className="dark:bg-success-500/20" />
              <div className="flex-1 min-w-0">
                <p className="font-semibold text-neutral-900 truncate dark:text-neutral-50">
                  {formData.toVaId ? accounts.find(a => a.id === formData.toVaId)?.vaName : 'VIBAN Routing'}
                </p>
                <p className="text-xs text-neutral-500 font-mono dark:text-neutral-400">{formData.targetVibanOrVa}</p>
                <Badge variant={formData.toVaId ? 'info' : 'success'} size="sm" className="mt-1">
                  {formData.toVaId ? 'Direct VA' : 'VIBAN Auto-Route'}
                </Badge>
              </div>
            </div>
          ) : transferType === 'bulk' && bulkItems.length > 0 ? (
            <div className="space-y-2">
              <div className="flex items-center gap-3">
                <StatusIconBadge tone="warning" icon={Users} rounded="lg" className="dark:bg-warning-500/20" />
                <div>
                  <p className="font-semibold text-neutral-900 dark:text-neutral-50">{bulkItems.length} Recipients</p>
                  <p className="text-xs text-neutral-500 dark:text-neutral-400">Multiple accounts</p>
                </div>
              </div>
              {bulkItems.slice(0, 3).map((item, idx) => (
                <div key={item.id} className="flex justify-between text-xs bg-neutral-50 rounded-lg p-2 dark:bg-primary-950">
                  <span className="text-neutral-600 truncate dark:text-neutral-300">
                    {accounts.find(a => a.id === item.destinationVaId)?.vaName || 'Account ' + (idx + 1)}
                  </span>
                  <span className="font-medium text-neutral-900 dark:text-neutral-50">
                    {formatCurrency(parseFloat(item.amount) || 0, formData.currency)}
                  </span>
                </div>
              ))}
              {bulkItems.length > 3 && (
                <p className="text-xs text-neutral-500 text-center dark:text-neutral-400">+{bulkItems.length - 3} more...</p>
              )}
            </div>
          ) : (
            <p className="text-sm text-neutral-400 italic dark:text-neutral-500">
              {transferType === 'inward' ? 'Enter VIBAN or select VA...' : 'Select destination...'}
            </p>
          )}
        </div>

        {/* Amount Section */}
        <div className="space-y-3">
          <div className="flex justify-between items-center">
            <span className="text-sm text-neutral-600 dark:text-neutral-300">Amount</span>
            {/* Phase 12 Task E: .stat-value-xs replaces the raw `text-xl
                font-bold` (same 20px scale, display-tier numerics); the
                empty-state grey overrides its default ink. */}
            <span className={cn(
              'stat-value-xs',
              !hasAmount && 'text-neutral-300 dark:text-neutral-600'
            )}>
              {hasAmount ? formatCurrency(amount, formData.currency) : '---'}
            </span>
          </div>

          {formData.valueDate && (
            <div className="flex justify-between text-sm">
              <span className="text-neutral-500 dark:text-neutral-400">Value Date</span>
              <span className="text-neutral-900 dark:text-neutral-50">{formData.valueDate}</span>
            </div>
          )}

          {(formData.description || formData.remittanceInfo) && (
            <div className="text-sm">
              <span className="text-neutral-500 block mb-1 dark:text-neutral-400">Reference</span>
              <p className="text-neutral-700 bg-neutral-50 rounded-lg p-2 text-xs dark:text-neutral-200 dark:bg-primary-950">
                {formData.description || formData.remittanceInfo}
              </p>
            </div>
          )}
        </div>

        {/* Fee Information */}
        {hasAmount && transferType !== 'inward' && (
          <div className="bg-neutral-50 border border-neutral-200 rounded-lg p-3 space-y-2 dark:bg-primary-950 dark:border-primary-800">
            <div className="flex items-center justify-between text-sm">
              <span className="text-neutral-600 font-medium dark:text-neutral-300">Fees & Total</span>
              {loadingFees && <Loader2 className="w-3 h-3 animate-spin text-neutral-400 dark:text-neutral-500" />}
            </div>

            {feePreview ? (
              <div className="space-y-2">
                {/* Fee breakdown for internal transfers */}
                {'feeBreakdown' in feePreview && feePreview.feeBreakdown && (
                  <>
                    {feePreview.feeBreakdown.lineItems?.map((item, idx) => (
                      <div key={idx} className="flex justify-between text-xs">
                        <span className={item.waived ? 'text-neutral-400 line-through' : 'text-neutral-600 dark:text-neutral-300'}>
                          {item.chargeName}
                          {item.waived && <span className="ml-1 text-success-600 no-underline dark:text-success-300">(Waived)</span>}
                        </span>
                        <span className={item.waived ? 'text-neutral-400 line-through' : 'text-neutral-700 font-medium dark:text-neutral-200'}>
                          {formatCurrency(item.amount, item.currency)}
                        </span>
                      </div>
                    ))}
                    <div className="flex justify-between text-sm pt-1 border-t border-neutral-200 dark:border-primary-800">
                      <span className="text-neutral-700 font-medium dark:text-neutral-200">Total Fee</span>
                      <span className="text-warning-700 font-semibold dark:text-warning-300">
                        {formatCurrency(feePreview.feeBreakdown.totalFee, feePreview.currency)}
                      </span>
                    </div>
                  </>
                )}

                {/* Fee for outward payments */}
                {'paymentFee' in feePreview && (
                  <div className="flex justify-between text-sm">
                    <span className="text-neutral-600 dark:text-neutral-300">Payment Fee</span>
                    <span className="text-warning-700 font-semibold dark:text-warning-300">
                      {formatCurrency(feePreview.paymentFee, feePreview.currency)}
                    </span>
                  </div>
                )}

                {/* Total Debit */}
                <div className="flex justify-between text-sm pt-2 border-t border-neutral-300 mt-2 dark:border-primary-700">
                  <span className="text-primary-700 font-semibold dark:text-neutral-200">Total Debit</span>
                  <span className="text-primary-700 font-bold dark:text-neutral-200">
                    {formatCurrency(feePreview.totalDebit, feePreview.currency)}
                  </span>
                </div>

                {/* Balance & Credit Limit Info */}
                <div className="pt-2 border-t border-neutral-200 mt-2 space-y-1 dark:border-primary-800">
                  <div className="flex justify-between text-xs">
                    <span className="text-neutral-500 dark:text-neutral-400">Current Balance</span>
                    <span className="text-neutral-700 dark:text-neutral-200">{formatCurrency(feePreview.fromBalance, feePreview.currency)}</span>
                  </div>
                  {feePreview.creditLimitAvailable !== undefined && feePreview.creditLimitAvailable > 0 && (
                    <div className="flex justify-between text-xs">
                      <span className="text-neutral-500 dark:text-neutral-400">Credit Limit Available</span>
                      <span className="text-success-600 dark:text-success-300">+{formatCurrency(feePreview.creditLimitAvailable, feePreview.currency)}</span>
                    </div>
                  )}
                  {feePreview.effectiveAvailableBalance !== undefined && (
                    <div className="flex justify-between text-xs font-medium">
                      <span className="text-neutral-600 dark:text-neutral-300">Effective Available</span>
                      <span className="text-primary-700 dark:text-neutral-200">{formatCurrency(feePreview.effectiveAvailableBalance, feePreview.currency)}</span>
                    </div>
                  )}
                </div>

                {/* Validation status */}
                {!feePreview.fundsAvailable && (
                  <div className="text-xs text-error-600 flex items-center gap-1 mt-1 dark:text-error-300">
                    <AlertCircle className="w-3 h-3" />
                    {feePreview.validationMessage || 'Insufficient funds'}
                  </div>
                )}
              </div>
            ) : (
              <p className="text-xs text-neutral-500 dark:text-neutral-400">
                {hasDestination ? 'Calculating fees...' : 'Select destination to see fees'}
              </p>
            )}
          </div>
        )}

        {/* POBO Indicator (for outward) */}
        {transferType === 'outward' && formData.isPobo && (
          <div className="bg-warning-50 border border-warning-200 rounded-lg p-3 dark:bg-warning-500/10 dark:border-warning-500/30">
            <div className="flex items-center gap-2 text-warning-700 dark:text-warning-300">
              <Building2 className="w-4 h-4" />
              <span className="font-medium text-sm">POBO Enabled</span>
            </div>
            {formData.behalfOfEntity && (
              <p className="text-xs text-warning-600 mt-1 dark:text-warning-300">
                On behalf of: {formData.behalfOfEntity}
              </p>
            )}
          </div>
        )}

        {/* Balance Warning - Uses effective available balance (balance + credit limit) */}
        {sourceAccount && amount > 0 && transferType !== 'inward' && (() => {
          // Use preview's effective available balance if available, otherwise use account's current balance + credit limit
          const effectiveBalance = feePreview?.effectiveAvailableBalance ??
            (sourceAccount.currentBalance + (sourceAccount.creditLimitAvailable ?? 0));
          const hasInsufficientFunds = amount > effectiveBalance;
          const hasCreditLimit = (feePreview?.creditLimitAvailable ?? sourceAccount.creditLimitAvailable ?? 0) > 0;

          return hasInsufficientFunds ? (
            <div className="bg-error-50 border border-error-200 rounded-lg p-3 dark:bg-error-500/10 dark:border-error-500/30">
              <div className="flex items-center gap-2 text-error-700 dark:text-error-300">
                <AlertCircle className="w-4 h-4" />
                <span className="font-medium text-sm">Insufficient Funds</span>
              </div>
              <div className="text-xs text-error-600 mt-1 space-y-0.5 dark:text-error-300">
                <p>Balance: {formatCurrency(sourceAccount.currentBalance, sourceAccount.currencyCode)}</p>
                {hasCreditLimit && (
                  <p>Credit Available: +{formatCurrency(feePreview?.creditLimitAvailable ?? sourceAccount.creditLimitAvailable ?? 0, sourceAccount.currencyCode)}</p>
                )}
                <p className="font-medium">Effective Available: {formatCurrency(effectiveBalance, sourceAccount.currencyCode)}</p>
              </div>
            </div>
          ) : null;
        })()}

        {/* Status Indicator */}
        <div className="flex items-center gap-2 py-2">
          <div className={cn(
            'w-2 h-2 rounded-full',
            canSubmit ? 'bg-success-500' : 'bg-warning-400'
          )} />
          <span className="text-sm text-neutral-600 dark:text-neutral-300">
            {canSubmit ? 'Ready to submit' : 'Complete all fields'}
          </span>
        </div>

        {/* Submit Button */}
        <Button
          variant="primary"
          className="w-full"
          disabled={!canSubmit || submitting}
          onClick={onSubmit}
          leftIcon={submitting ? <Loader2 className="w-4 h-4 animate-spin" /> : <Send className="w-4 h-4" />}
        >
          {submitting ? 'Processing...' :
           transferType === 'inward' ? 'Process Collection' :
           transferType === 'bulk' ? `Send ${bulkItems.length} Transfers` :
           'Submit Transfer'}
        </Button>

        {/* Validation Hints */}
        {!canSubmit && (
          <div className="text-xs text-neutral-500 space-y-1 dark:text-neutral-400">
            {!hasSource && <p>• {transferType === 'inward' ? 'Select payer' : 'Select source account'}</p>}
            {!hasDestination && <p>• {transferType === 'inward' ? 'Enter VIBAN or select VA' : 'Select destination'}</p>}
            {!hasAmount && <p>• Enter transfer amount</p>}
          </div>
        )}
      </div>
    </div>
  );
};

// ============================================================================
// RESULT MODAL
// ============================================================================

interface ResultModalProps {
  isOpen: boolean;
  onClose: () => void;
  result: any;
  onViewXml?: () => void;
  hasXml?: boolean;
}

const ResultModal: React.FC<ResultModalProps> = ({ isOpen, onClose, result, onViewXml, hasXml }) => {
  if (!result) return null;

  // Check for success: result.success for ISO20022 responses, or referenceNumber for standard transfers
  const isSuccess = result.success === true || !!result.referenceNumber || !!result.transactionReference;

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="Transfer Result" size="md">
      <div className="space-y-6 animate-fade-in">
        {/* Status Header */}
        <Card className={cn(
          'border-2 text-center',
          isSuccess
            ? 'bg-success-50/50 border-success-200 dark:border-success-500/30'
            : 'bg-error-50/50 border-error-200 dark:border-error-500/30'
        )}>
          <div className={cn(
            'w-16 h-16 rounded-2xl flex items-center justify-center mx-auto mb-4',
            isSuccess ? 'bg-success-100 dark:bg-success-500/20' : 'bg-error-100 dark:bg-error-500/20'
          )}>
            {isSuccess ? (
              <CheckCircle className="w-8 h-8 text-success-600 dark:text-success-300" />
            ) : (
              <XCircle className="w-8 h-8 text-error-600 dark:text-error-300" />
            )}
          </div>
          <h3 className="section-title">
            {isSuccess ? 'Transfer Successful' : 'Transfer Failed'}
          </h3>
          {(result.referenceNumber || result.transactionReference) && (
            <p className="text-sm text-neutral-500 font-mono mt-2 dark:text-neutral-400">{result.referenceNumber || result.transactionReference}</p>
          )}
        </Card>

        {/* Details */}
        <div className="space-y-3">
          {result.amount && (
            <div className="flex justify-between items-center p-4 bg-neutral-50 rounded-xl dark:bg-primary-950">
              <span className="text-neutral-600 dark:text-neutral-300">Amount</span>
              <span className="stat-value-xs">
                {formatCurrency(result.amount, result.currencyCode || result.currency || 'AED')}
              </span>
            </div>
          )}
          {result.balanceBefore !== undefined && (
            <div className="flex justify-between items-center p-4 bg-neutral-50 rounded-xl dark:bg-primary-950">
              <span className="text-neutral-600 dark:text-neutral-300">Balance Before</span>
              <span className="font-semibold">{formatCurrency(result.balanceBefore, 'AED')}</span>
            </div>
          )}
          {result.balanceAfter !== undefined && (
            <div className="flex justify-between items-center p-4 bg-success-50 rounded-xl border border-success-200 dark:bg-success-500/10 dark:border-success-500/30">
              <span className="text-success-700 dark:text-success-300">Balance After</span>
              <span className="font-bold text-success-700 dark:text-success-300">{formatCurrency(result.balanceAfter, 'AED')}</span>
            </div>
          )}
          {(result.feeAmount > 0 || result.totalFee > 0) && (
            <div className="p-4 bg-warning-50 rounded-xl border border-warning-200 space-y-2 dark:bg-warning-500/10 dark:border-warning-500/30">
              <div className="flex justify-between items-center">
                <span className="text-warning-700 dark:text-warning-300">Fee Applied</span>
                <span className="font-semibold text-warning-700 dark:text-warning-300">
                  {formatCurrency(result.feeAmount || result.totalFee, result.currencyCode || 'AED')}
                </span>
              </div>
              <p className="text-xs text-warning-600 dark:text-warning-300">
                Paid by: {result.vaName || 'Source Account'}
              </p>
            </div>
          )}

          {/* Beneficiary details for outward payments */}
          {(result.beneficiaryName || result.creditorName) && (
            <div className="p-4 bg-primary-50 rounded-xl border border-primary-200 space-y-2 dark:bg-primary-800/40 dark:border-primary-700">
              <div className="flex items-center gap-2 text-primary-700 mb-2 dark:text-neutral-200">
                <Globe className="w-4 h-4" />
                <span className="font-medium text-sm">Beneficiary</span>
              </div>
              <div className="text-sm space-y-1">
                <p className="font-semibold text-primary-900 dark:text-neutral-50">{result.beneficiaryName || result.creditorName}</p>
                {(result.beneficiaryAccount || result.creditorAccount) && (
                  <p className="text-xs text-neutral-500 font-mono dark:text-neutral-400">{result.beneficiaryAccount || result.creditorAccount}</p>
                )}
              </div>
            </div>
          )}
        </div>

        {/* Actions */}
        <div className="flex gap-3 pt-4 border-t border-neutral-200 dark:border-primary-800">
          {hasXml && onViewXml && (
            <Button variant="outline" className="flex-1" leftIcon={<FileCode className="w-4 h-4" />} onClick={onViewXml}>
              View XML
            </Button>
          )}
          <Button className="flex-1" onClick={onClose}>
            Close
          </Button>
        </div>
      </div>
    </Modal>
  );
};

// ============================================================================
// XML VIEWER MODAL
// ============================================================================

interface XmlViewerModalProps {
  isOpen: boolean;
  onClose: () => void;
  xml: string;
}

const XmlViewerModal: React.FC<XmlViewerModalProps> = ({ isOpen, onClose, xml }) => {
  const copyToClipboard = () => {
    navigator.clipboard.writeText(xml);
    toast.success('Copied to clipboard');
  };

  const downloadXml = () => {
    const blob = new Blob([xml], { type: 'application/xml' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `pain001_${Date.now()}.xml`;
    a.click();
    URL.revokeObjectURL(url);
    toast.success('XML downloaded');
  };

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="ISO 20022 XML Message" size="xl">
      <div className="space-y-4">
        <Card className="bg-neutral-50 border-neutral-200 dark:bg-primary-950 dark:border-primary-800">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <StatusIconBadge tone="primary" icon={FileCode} className="dark:bg-primary-700" />
              <div>
                <p className="font-semibold text-primary-900 dark:text-neutral-50">pain.001 Message</p>
                <p className="text-xs text-neutral-500 dark:text-neutral-400">Customer Credit Transfer Initiation</p>
              </div>
            </div>
            <div className="flex gap-2">
              <Button variant="ghost" size="sm" leftIcon={<Copy className="w-4 h-4" />} onClick={copyToClipboard}>
                Copy
              </Button>
              <Button variant="outline" size="sm" leftIcon={<Download className="w-4 h-4" />} onClick={downloadXml}>
                Download
              </Button>
            </div>
          </div>
        </Card>

        <pre className="bg-neutral-900 text-success-400 p-6 rounded-xl text-xs overflow-auto max-h-[400px] font-mono">
          {xml}
        </pre>
      </div>
    </Modal>
  );
};

// ============================================================================
// TRANSFER DETAIL MODAL
// ============================================================================

type DetailTab = 'details' | 'iso' | 'accounting';

interface TransferDetailModalProps {
  isOpen: boolean;
  onClose: () => void;
  transaction: Transaction | null;
}

const TransferDetailModal: React.FC<TransferDetailModalProps> = ({ isOpen, onClose, transaction }) => {
  const [activeTab, setActiveTab] = useState<DetailTab>('details');
  const [transactionDetail, setTransactionDetail] = useState<TransactionDetail | null>(null);
  const [isoMessage, setIsoMessage] = useState<{ messageType: string; xml: string; messageId: string } | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (isOpen && transaction?.id) {
      loadDetails();
    }
  }, [isOpen, transaction?.id]);

  const loadDetails = async () => {
    if (!transaction?.id) return;
    setLoading(true);
    try {
      // Load transaction detail (includes relatedTransactions for accounting entries view)
      const detailRes = await transactionsApi.getById(transaction.id);
      if (detailRes.data) {
        setTransactionDetail(detailRes.data);
      }

      // Load ISO message if applicable (DEBIT or POBO_DEBIT)
      // Note: This endpoint may not exist yet - the XML is generated on-demand
      if (transaction.movementType === 'DEBIT' || transaction.movementType === 'POBO_DEBIT') {
        try {
          const isoRes = await transactionsApi.getIsoMessage(transaction.id);
          if (isoRes.data) {
            setIsoMessage(isoRes.data);
          }
        } catch (err) {
          // ISO message endpoint may not exist yet - show placeholder
          console.log('ISO message not available');
          setIsoMessage(null);
        }
      } else {
        setIsoMessage(null);
      }
    } catch (error) {
      console.error('Failed to load transaction details:', error);
    } finally {
      setLoading(false);
    }
  };

  const copyXmlToClipboard = () => {
    if (isoMessage?.xml) {
      navigator.clipboard.writeText(isoMessage.xml);
      toast.success('XML copied to clipboard');
    }
  };

  const downloadXml = () => {
    if (isoMessage?.xml) {
      const blob = new Blob([isoMessage.xml], { type: 'application/xml' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${isoMessage.messageType}_${transaction?.referenceNumber || 'message'}.xml`;
      a.click();
      URL.revokeObjectURL(url);
      toast.success('XML downloaded');
    }
  };

  // Get transfer type display info
  const getTransferTypeInfo = () => {
    switch (transaction?.movementType) {
      case 'TRANSFER_OUT':
        return { label: 'Internal Transfer', variant: 'info' as const, icon: ArrowLeftRight };
      case 'DEBIT':
        return { label: 'Outward Payment', variant: 'primary' as const, icon: Globe };
      case 'POBO_DEBIT':
        return { label: 'POBO Payment', variant: 'warning' as const, icon: Building2 };
      case 'ROBO_CREDIT':
        return { label: 'ROBO Collection', variant: 'success' as const, icon: ArrowDownLeft };
      case 'CREDIT':
      case 'TRANSFER_IN':
        return { label: 'Inward Payment', variant: 'success' as const, icon: ArrowDownLeft };
      case 'EXCEPTION_CREDIT':
        return { label: 'Exception', variant: 'warning' as const, icon: AlertCircle };
      default:
        return { label: transaction?.movementType || 'Transfer', variant: 'neutral' as const, icon: Send };
    }
  };

  const transferTypeInfo = getTransferTypeInfo();
  const TypeIcon = transferTypeInfo.icon;

  if (!transaction) return null;

  const tabs = [
    { id: 'details' as DetailTab, label: 'Transfer Details', icon: FileText },
    { id: 'iso' as DetailTab, label: 'ISO 20022', icon: FileCode, disabled: !['DEBIT', 'POBO_DEBIT'].includes(transaction.movementType || '') },
    { id: 'accounting' as DetailTab, label: 'Accounting Entries', icon: BookOpen },
  ];

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="Transfer Details" size="xl">
      <div className="space-y-6">
        {/* Header */}
        <Card className="bg-gradient-to-br from-neutral-50 to-white dark:from-primary-950 dark:to-primary-900">
          <div className="flex items-start justify-between">
            <div className="flex items-center gap-4">
              <div className={cn(
                'w-14 h-14 rounded-2xl flex items-center justify-center',
                transferTypeInfo.variant === 'info' ? 'bg-info-100 dark:bg-info-500/20' :
                transferTypeInfo.variant === 'primary' ? 'bg-primary-100 dark:bg-primary-700' :
                transferTypeInfo.variant === 'warning' ? 'bg-warning-100 dark:bg-warning-500/20' : 'bg-neutral-100 dark:bg-primary-800'
              )}>
                <TypeIcon className={cn(
                  'w-7 h-7',
                  transferTypeInfo.variant === 'info' ? 'text-info-600 dark:text-info-300' :
                  transferTypeInfo.variant === 'primary' ? 'text-primary-600 dark:text-primary-200' :
                  transferTypeInfo.variant === 'warning' ? 'text-warning-600 dark:text-warning-300' : 'text-neutral-600 dark:text-neutral-300'
                )} />
              </div>
              <div>
                <div className="flex items-center gap-2">
                  <h3 className="code-display">{transaction.referenceNumber}</h3>
                  <Badge variant={transferTypeInfo.variant} size="sm">{transferTypeInfo.label}</Badge>
                </div>
                <p className="text-sm text-neutral-500 mt-1 dark:text-neutral-400">{formatRelativeTime(transaction.transactionDate)}</p>
              </div>
            </div>
            <div className="text-right">
              <p className="stat-value-sm">
                {formatCurrency(transaction.amount, transaction.currencyCode)}
              </p>
              <Badge
                variant={
                  transaction.status === 'COMPLETED' ? 'success' :
                  transaction.status === 'PENDING' ? 'warning' :
                  transaction.status === 'FAILED' ? 'error' : 'neutral'
                }
                size="sm"
                dot
              >
                {transaction.status}
              </Badge>
            </div>
          </div>
        </Card>

        {/* Tabs */}
        <div className="flex gap-2 border-b border-neutral-200 pb-0 dark:border-primary-800">
          {tabs.map(tab => (
            <button
              key={tab.id}
              onClick={() => !tab.disabled && setActiveTab(tab.id)}
              disabled={tab.disabled}
              className={cn(
                'flex items-center gap-2 px-4 py-3 text-sm font-medium transition-all border-b-2 -mb-px',
                activeTab === tab.id
                  ? 'border-primary-500 text-primary-600 dark:text-primary-200'
                  : tab.disabled
                    ? 'border-transparent text-neutral-300 cursor-not-allowed dark:text-neutral-600'
                    : 'border-transparent text-neutral-500 hover:text-neutral-700 hover:border-neutral-300 dark:text-neutral-400 dark:hover:text-neutral-200 dark:hover:border-primary-700'
              )}
            >
              <tab.icon className="w-4 h-4" />
              {tab.label}
            </button>
          ))}
        </div>

        {loading ? (
          <div className="flex items-center justify-center py-12">
            <Loader2 className="w-6 h-6 animate-spin text-primary-600 dark:text-primary-200" />
          </div>
        ) : (
          <>
            {/* Details Tab */}
            {activeTab === 'details' && (
              <div className="space-y-4 animate-fade-in">
                {/* Transfer Flow */}
                <div className="grid grid-cols-2 gap-4">
                  <Card padding="sm" className="bg-primary-50/50 border-primary-200 dark:border-primary-700">
                    <div className="flex items-center gap-3">
                      <StatusIconBadge tone="primary" icon={ArrowUpRight} className="dark:bg-primary-700" />
                      <div className="flex-1 min-w-0">
                        <p className="text-xs text-primary-600 uppercase tracking-wider dark:text-primary-200">From</p>
                        <p className="font-semibold text-primary-900 truncate dark:text-neutral-50">{transaction.vaName || 'N/A'}</p>
                        <p className="text-xs text-neutral-500 font-mono dark:text-neutral-400">{transaction.vaNumber}</p>
                      </div>
                    </div>
                  </Card>
                  <Card padding="sm" className="bg-success-50/50 border-success-200 dark:border-success-500/30">
                    <div className="flex items-center gap-3">
                      <StatusIconBadge tone="success" icon={ArrowDownLeft} className="dark:bg-success-500/20" />
                      <div className="flex-1 min-w-0">
                        <p className="text-xs text-success-600 uppercase tracking-wider dark:text-success-300">To</p>
                        <p className="font-semibold text-primary-900 truncate dark:text-neutral-50">
                          {transaction.counterpartyName || transactionDetail?.counterpartyName || transactionDetail?.beneficiaryName || 'N/A'}
                        </p>
                        <p className="text-xs text-neutral-500 font-mono dark:text-neutral-400">
                          {transaction.counterpartyAccount || transactionDetail?.counterpartyAccount || transactionDetail?.beneficiaryAccount || 'N/A'}
                        </p>
                      </div>
                    </div>
                  </Card>
                </div>

                {/* Details Grid */}
                <Card>
                  <h4 className="text-sm font-semibold text-neutral-600 uppercase tracking-wider mb-4 dark:text-neutral-300">Transaction Information</h4>
                  <div className="grid grid-cols-2 gap-4">
                    <div className="flex items-center gap-3 p-3 bg-neutral-50 rounded-xl dark:bg-primary-950">
                      <Hash className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
                      <div>
                        <p className="text-xs text-neutral-500 dark:text-neutral-400">Reference Number</p>
                        <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{transaction.referenceNumber}</p>
                      </div>
                    </div>
                    <div className="flex items-center gap-3 p-3 bg-neutral-50 rounded-xl dark:bg-primary-950">
                      <Calendar className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
                      <div>
                        <p className="text-xs text-neutral-500 dark:text-neutral-400">Transaction Date</p>
                        <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">
                          {new Date(transaction.transactionDate).toLocaleDateString('en-US', {
                            year: 'numeric',
                            month: 'short',
                            day: 'numeric',
                            hour: '2-digit',
                            minute: '2-digit'
                          })}
                        </p>
                      </div>
                    </div>
                    <div className="flex items-center gap-3 p-3 bg-neutral-50 rounded-xl dark:bg-primary-950">
                      <DollarSign className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
                      <div>
                        <p className="text-xs text-neutral-500 dark:text-neutral-400">Amount</p>
                        <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">
                          {formatCurrency(transaction.amount, transaction.currencyCode)}
                        </p>
                      </div>
                    </div>
                    <div className="flex items-center gap-3 p-3 bg-neutral-50 rounded-xl dark:bg-primary-950">
                      <ArrowRightLeft className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
                      <div>
                        <p className="text-xs text-neutral-500 dark:text-neutral-400">Movement Type</p>
                        <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{transaction.movementType}</p>
                      </div>
                    </div>
                    {transaction.valueDate && (
                      <div className="flex items-center gap-3 p-3 bg-neutral-50 rounded-xl dark:bg-primary-950">
                        <Calendar className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
                        <div>
                          <p className="text-xs text-neutral-500 dark:text-neutral-400">Value Date</p>
                          <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{transaction.valueDate}</p>
                        </div>
                      </div>
                    )}
                    {transaction.externalReference && (
                      <div className="flex items-center gap-3 p-3 bg-neutral-50 rounded-xl dark:bg-primary-950">
                        <FileText className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
                        <div>
                          <p className="text-xs text-neutral-500 dark:text-neutral-400">External Reference</p>
                          <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{transaction.externalReference}</p>
                        </div>
                      </div>
                    )}
                  </div>

                  {/* Description/Narration */}
                  {transaction.description && (
                    <div className="mt-4 p-4 bg-neutral-50 rounded-xl dark:bg-primary-950">
                      <p className="text-xs text-neutral-500 mb-1 dark:text-neutral-400">Description / Remittance Info</p>
                      <p className="text-sm text-primary-900 dark:text-neutral-50">{transaction.description}</p>
                    </div>
                  )}

                  {/* POBO Info */}
                  {transaction.movementType === 'POBO_DEBIT' && (
                    <Card className="mt-4 bg-warning-50/50 border-warning-200 dark:border-warning-500/30">
                      <div className="flex items-start gap-3">
                        <StatusIconBadge tone="warning" icon={Building2} className="dark:bg-warning-500/20" />
                        <div>
                          <p className="font-semibold text-warning-800 dark:text-warning-300">POBO Payment</p>
                          <p className="text-sm text-warning-700 mt-1 dark:text-warning-300">
                            Payment made on behalf of a subsidiary entity. Treasury entity is the payer,
                            and intercompany entries are automatically generated.
                          </p>
                        </div>
                      </div>
                    </Card>
                  )}

                  {/* Related Transactions */}
                  {transactionDetail?.relatedTransactions && transactionDetail.relatedTransactions.length > 0 && (
                    <div className="mt-6">
                      <h4 className="text-sm font-semibold text-neutral-600 uppercase tracking-wider mb-3 dark:text-neutral-300">Related Transactions</h4>
                      <div className="space-y-2">
                        {transactionDetail.relatedTransactions.map((related) => (
                          <div key={related.id} className="flex items-center justify-between p-3 bg-neutral-50 rounded-xl dark:bg-primary-950">
                            <div className="flex items-center gap-3">
                              <Badge variant={related.movementType.includes('CREDIT') ? 'success' : 'info'} size="sm">
                                {related.movementType}
                              </Badge>
                              <div>
                                <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{related.vaName}</p>
                                <p className="text-xs text-neutral-500 font-mono dark:text-neutral-400">{related.referenceNumber}</p>
                              </div>
                            </div>
                            <p className="font-semibold text-primary-900 dark:text-neutral-50">
                              {formatCurrency(related.amount, transaction.currencyCode)}
                            </p>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}
                </Card>
              </div>
            )}

            {/* ISO 20022 Tab */}
            {activeTab === 'iso' && (
              <div className="space-y-4 animate-fade-in">
                {isoMessage ? (
                  <>
                    <Card className="bg-neutral-50 border-neutral-200 dark:bg-primary-950 dark:border-primary-800">
                      <div className="flex items-center justify-between">
                        <div className="flex items-center gap-3">
                          <StatusIconBadge tone="primary" icon={FileCode} className="dark:bg-primary-700" />
                          <div>
                            <p className="font-semibold text-primary-900 dark:text-neutral-50">{isoMessage.messageType}</p>
                            <p className="text-xs text-neutral-500 dark:text-neutral-400">Message ID: {isoMessage.messageId}</p>
                          </div>
                        </div>
                        <div className="flex gap-2">
                          <Button variant="ghost" size="sm" leftIcon={<Copy className="w-4 h-4" />} onClick={copyXmlToClipboard}>
                            Copy
                          </Button>
                          <Button variant="outline" size="sm" leftIcon={<Download className="w-4 h-4" />} onClick={downloadXml}>
                            Download
                          </Button>
                        </div>
                      </div>
                    </Card>

                    <pre className="bg-neutral-900 text-success-400 p-6 rounded-xl text-xs overflow-auto max-h-[400px] font-mono">
                      {isoMessage.xml}
                    </pre>
                  </>
                ) : (
                  <div className="text-center py-12">
                    <div className="w-16 h-16 rounded-2xl bg-neutral-100 flex items-center justify-center mx-auto mb-4 dark:bg-primary-800">
                      <FileCode className="w-8 h-8 text-neutral-300 dark:text-neutral-600" />
                    </div>
                    <p className="text-neutral-500 dark:text-neutral-400">No ISO 20022 message available</p>
                    <p className="text-sm text-neutral-400 mt-1 dark:text-neutral-500">
                      ISO messages are generated for outward and POBO payments
                    </p>
                  </div>
                )}
              </div>
            )}

            {/* Accounting Entries Tab - Shows related transactions as double-entry */}
            {activeTab === 'accounting' && (
              <div className="space-y-4 animate-fade-in">
                {/* Build accounting entries from current transaction + related transactions */}
                {(() => {
                  // Determine if this is an external transaction (inward/outward)
                  // External transactions involve CBS and don't balance internally
                  const isExternalTransaction = ['ROBO_CREDIT', 'ROBO_DEBIT'].includes(transaction.movementType || '') ||
                    transaction.channel === 'SWIFT' || transaction.channel === 'RTGS' ||
                    transaction.channel === 'INCOMING' || transaction.channel === 'CBS' ||
                    transactionDetail?.isRobo === true;

                  const isOutwardPayment = ['DEBIT', 'POBO_DEBIT'].includes(transaction.movementType || '') &&
                    (transactionDetail?.beneficiaryAccount || transactionDetail?.channel === 'SWIFT' || transactionDetail?.channel === 'RTGS');

                  const isInwardCollection = transaction.movementType === 'ROBO_CREDIT' ||
                    (transaction.movementType === 'CREDIT' && transaction.channel === 'INCOMING');

                  // For external transactions, show simplified CFO view instead of full accounting entries
                  if (isExternalTransaction || isOutwardPayment || isInwardCollection) {
                    return (
                      <Card>
                        <h4 className="text-sm font-semibold text-neutral-600 uppercase tracking-wider mb-4 dark:text-neutral-300">
                          Transaction Summary (CFO View)
                        </h4>
                        <p className="text-xs text-neutral-500 mb-4 dark:text-neutral-400">
                          Shows the business impact on your accounts. Internal clearing entries (Settlement VA, Shadow VA) are excluded.
                        </p>

                        {/* Single Entry View for External Transactions */}
                        <div className="overflow-x-auto">
                          <table className="w-full">
                            <thead className="bg-neutral-50 border-b border-neutral-100 dark:bg-primary-950 dark:border-primary-800/60">
                              <tr>
                                <th className="text-left p-3 text-xs font-semibold text-neutral-600 uppercase dark:text-neutral-300">Account</th>
                                <th className="text-left p-3 text-xs font-semibold text-neutral-600 uppercase dark:text-neutral-300">Type</th>
                                <th className="text-right p-3 text-xs font-semibold text-neutral-600 uppercase dark:text-neutral-300">Amount</th>
                                <th className="text-left p-3 text-xs font-semibold text-neutral-600 uppercase dark:text-neutral-300">Counterparty</th>
                              </tr>
                            </thead>
                            <tbody className="divide-y divide-neutral-100 dark:divide-primary-800/60">
                              <tr className="hover:bg-neutral-50 dark:hover:bg-primary-800/50">
                                <td className="p-3">
                                  <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{transaction.vaName || 'Your Account'}</p>
                                  <p className="text-xs text-neutral-500 font-mono dark:text-neutral-400">{transaction.vaNumber}</p>
                                </td>
                                <td className="p-3">
                                  <Badge
                                    variant={isInwardCollection ? 'success' : 'error'}
                                    size="sm"
                                  >
                                    {isInwardCollection ? 'CREDIT' : 'DEBIT'}
                                  </Badge>
                                </td>
                                <td className="p-3 text-right">
                                  <p className={cn('font-semibold', isInwardCollection ? 'text-success-600 dark:text-success-300' : 'text-error-600 dark:text-error-300')}>
                                    {isInwardCollection ? '+' : '-'}{formatCurrency(transaction.amount, transaction.currencyCode)}
                                  </p>
                                  {transaction.feeAmount && transaction.feeAmount > 0 && (
                                    <p className="text-xs text-neutral-500 dark:text-neutral-400">
                                      Fee: {formatCurrency(transaction.feeAmount, transaction.currencyCode)}
                                    </p>
                                  )}
                                </td>
                                <td className="p-3">
                                  <p className="field-label">
                                    {isInwardCollection
                                      ? (transactionDetail?.remitterName || transaction.counterpartyName || 'External Remitter')
                                      : (transactionDetail?.beneficiaryName || transaction.counterpartyName || 'External Beneficiary')
                                    }
                                  </p>
                                  <p className="text-xs text-neutral-500 font-mono dark:text-neutral-400">
                                    {isInwardCollection
                                      ? (transactionDetail?.remitterAccount || transaction.counterpartyAccount || 'External Account')
                                      : (transactionDetail?.beneficiaryAccount || transaction.counterpartyAccount || 'External Account')
                                    }
                                  </p>
                                </td>
                              </tr>
                            </tbody>
                          </table>
                        </div>

                        {/* External Transaction Explanation */}
                        <Card className="mt-4 bg-primary-50/50 border-primary-200 dark:border-primary-700">
                          <div className="flex items-start gap-3">
                            <StatusIconBadge tone="primary" icon={Building2} className="flex-shrink-0 dark:bg-primary-700" />
                            <div>
                              <p className="font-semibold text-primary-800 dark:text-neutral-100">
                                {isInwardCollection ? 'Inward Collection' : 'Outward Payment'}
                              </p>
                              <p className="text-sm text-primary-700 mt-1 dark:text-neutral-200">
                                {isInwardCollection
                                  ? 'Funds received from external party via bank (CBS). The offsetting entry exists in the external banking system, not in VAM.'
                                  : 'Payment sent to external beneficiary via bank (CBS). The offsetting entry exists in the external banking system, not in VAM.'
                                }
                              </p>
                              <div className="mt-3 p-3 bg-white/60 rounded-lg dark:bg-primary-900/60">
                                <p className="text-xs font-medium text-primary-800 mb-2 dark:text-neutral-100">
                                  {isInwardCollection ? 'Collection Flow:' : 'Payment Flow:'}
                                </p>
                                <div className="flex items-center gap-2 text-xs text-primary-700 flex-wrap dark:text-neutral-200">
                                  {isInwardCollection ? (
                                    <>
                                      <span className="font-mono bg-primary-100 px-2 py-1 rounded dark:bg-primary-700">External Sender</span>
                                      <ArrowRight className="w-3 h-3" />
                                      <span className="font-mono bg-primary-100 px-2 py-1 rounded dark:bg-primary-700">Bank (CBS)</span>
                                      <ArrowRight className="w-3 h-3" />
                                      <span className="font-mono bg-primary-100 px-2 py-1 rounded dark:bg-primary-700">Your VA</span>
                                    </>
                                  ) : (
                                    <>
                                      <span className="font-mono bg-primary-100 px-2 py-1 rounded dark:bg-primary-700">Your VA</span>
                                      <ArrowRight className="w-3 h-3" />
                                      <span className="font-mono bg-primary-100 px-2 py-1 rounded dark:bg-primary-700">Bank (CBS)</span>
                                      <ArrowRight className="w-3 h-3" />
                                      <span className="font-mono bg-primary-100 px-2 py-1 rounded dark:bg-primary-700">Beneficiary</span>
                                    </>
                                  )}
                                </div>
                              </div>
                            </div>
                          </div>
                        </Card>
                      </Card>
                    );
                  }

                  // For internal transfers (VA to VA), show full double-entry view
                  const entries: Array<{
                    id: string;
                    entryType: 'DEBIT' | 'CREDIT';
                    accountName: string;
                    accountNumber: string;
                    amount: number;
                    movementType: string;
                    status: string;
                    referenceNumber: string;
                  }> = [];

                  // Add current transaction
                  const isDebitMovement = ['DEBIT', 'TRANSFER_OUT', 'POBO_DEBIT', 'SWEEP_OUT', 'POOL_DEBIT', 'FEE'].includes(transaction.movementType || '');
                  entries.push({
                    id: transaction.id,
                    entryType: isDebitMovement ? 'DEBIT' : 'CREDIT',
                    accountName: transaction.vaName || 'Source Account',
                    accountNumber: transaction.vaNumber || '',
                    amount: transaction.amount,
                    movementType: transaction.movementType || '',
                    status: transaction.status || 'COMPLETED',
                    referenceNumber: transaction.referenceNumber,
                  });

                  // Add related transactions (counterpart entries) - filter out Settlement VA entries
                  if (transactionDetail?.relatedTransactions) {
                    transactionDetail.relatedTransactions
                      .filter(rt => !rt.vaName?.includes('SETTLEMENT') && !rt.vaName?.includes('SETTLE-') && !rt.vaNumber?.includes('SETTLEMENT'))
                      .forEach(rt => {
                        const isRtDebit = ['DEBIT', 'TRANSFER_OUT', 'POBO_DEBIT', 'SWEEP_OUT', 'POOL_DEBIT', 'FEE'].includes(rt.movementType);
                        entries.push({
                          id: rt.id,
                          entryType: isRtDebit ? 'DEBIT' : 'CREDIT',
                          accountName: rt.vaName || 'Destination Account',
                          accountNumber: rt.vaNumber || '',
                          amount: rt.amount,
                          movementType: rt.movementType,
                          status: 'COMPLETED',
                          referenceNumber: rt.referenceNumber,
                        });
                      });
                  }

                  const totalDebits = entries.filter(e => e.entryType === 'DEBIT').reduce((sum, e) => sum + e.amount, 0);
                  const totalCredits = entries.filter(e => e.entryType === 'CREDIT').reduce((sum, e) => sum + e.amount, 0);
                  const isBalanced = Math.abs(totalDebits - totalCredits) < 0.01;

                  return entries.length > 0 ? (
                    <Card>
                      <h4 className="text-sm font-semibold text-neutral-600 uppercase tracking-wider mb-4 dark:text-neutral-300">
                        Double-Entry Transaction Records
                      </h4>
                      <p className="text-xs text-neutral-500 mb-4 dark:text-neutral-400">
                        Shows all transactions generated by this transfer (debits and credits across operating accounts)
                      </p>
                      <div className="overflow-x-auto">
                        <table className="w-full">
                          <thead className="bg-neutral-50 border-b border-neutral-100 dark:bg-primary-950 dark:border-primary-800/60">
                            <tr>
                              <th className="text-left p-3 text-xs font-semibold text-neutral-600 uppercase dark:text-neutral-300">Entry Type</th>
                              <th className="text-left p-3 text-xs font-semibold text-neutral-600 uppercase dark:text-neutral-300">Account</th>
                              <th className="text-left p-3 text-xs font-semibold text-neutral-600 uppercase dark:text-neutral-300">Movement</th>
                              <th className="text-right p-3 text-xs font-semibold text-neutral-600 uppercase dark:text-neutral-300">Debit</th>
                              <th className="text-right p-3 text-xs font-semibold text-neutral-600 uppercase dark:text-neutral-300">Credit</th>
                              <th className="text-left p-3 text-xs font-semibold text-neutral-600 uppercase dark:text-neutral-300">Status</th>
                            </tr>
                          </thead>
                          <tbody className="divide-y divide-neutral-100 dark:divide-primary-800/60">
                            {entries.map((entry) => (
                              <tr key={entry.id} className="hover:bg-neutral-50 dark:hover:bg-primary-800/50">
                                <td className="p-3">
                                  <Badge
                                    variant={entry.entryType === 'DEBIT' ? 'error' : 'success'}
                                    size="sm"
                                  >
                                    {entry.entryType}
                                  </Badge>
                                </td>
                                <td className="p-3">
                                  <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{entry.accountName}</p>
                                  <p className="text-xs text-neutral-500 font-mono dark:text-neutral-400">{entry.accountNumber}</p>
                                </td>
                                <td className="p-3">
                                  <Badge variant="neutral" size="sm">{entry.movementType}</Badge>
                                </td>
                                <td className="p-3 text-right">
                                  {entry.entryType === 'DEBIT' ? (
                                    <p className="font-semibold text-error-600 dark:text-error-300">
                                      {formatCurrency(entry.amount, transaction.currencyCode)}
                                    </p>
                                  ) : '-'}
                                </td>
                                <td className="p-3 text-right">
                                  {entry.entryType === 'CREDIT' ? (
                                    <p className="font-semibold text-success-600 dark:text-success-300">
                                      {formatCurrency(entry.amount, transaction.currencyCode)}
                                    </p>
                                  ) : '-'}
                                </td>
                                <td className="p-3">
                                  <Badge
                                    variant={entry.status === 'COMPLETED' ? 'success' : 'warning'}
                                    size="sm"
                                    dot
                                  >
                                    {entry.status}
                                  </Badge>
                                </td>
                              </tr>
                            ))}
                          </tbody>
                          <tfoot className="bg-neutral-100 border-t border-neutral-200 dark:bg-primary-800 dark:border-primary-800">
                            <tr>
                              <td colSpan={3} className="p-3 text-sm font-semibold text-neutral-700 dark:text-neutral-200">Total</td>
                              <td className="p-3 text-right font-bold text-error-700 dark:text-error-300">
                                {formatCurrency(totalDebits, transaction.currencyCode)}
                              </td>
                              <td className="p-3 text-right font-bold text-success-700 dark:text-success-300">
                                {formatCurrency(totalCredits, transaction.currencyCode)}
                              </td>
                              <td className="p-3"></td>
                            </tr>
                          </tfoot>
                        </table>
                      </div>

                      {/* Balance Check */}
                      <Card className={cn(
                        'mt-4',
                        isBalanced ? 'bg-success-50/50 border-success-200 dark:border-success-500/30' : 'bg-warning-50/50 border-warning-200 dark:border-warning-500/30'
                      )}>
                        <div className="flex items-center gap-3">
                          {isBalanced ? (
                            <CheckCircle className="w-5 h-5 text-success-600 dark:text-success-300" />
                          ) : (
                            <AlertTriangle className="w-5 h-5 text-warning-600 dark:text-warning-300" />
                          )}
                          <div>
                            <p className={cn('font-semibold', isBalanced ? 'text-success-800 dark:text-success-300' : 'text-warning-800 dark:text-warning-300')}>
                              {isBalanced ? 'Entries Balanced' : 'CFO View (Operating Accounts Only)'}
                            </p>
                            <p className={cn('text-sm', isBalanced ? 'text-success-700 dark:text-success-300' : 'text-warning-700 dark:text-warning-300')}>
                              {isBalanced
                                ? 'Total debits equal total credits (double-entry accounting)'
                                : 'Internal clearing accounts (Settlement VA) are excluded. Full audit trail available via API.'
                              }
                            </p>
                          </div>
                        </div>
                      </Card>

                      {/* POBO Intercompany Note */}
                      {transaction.movementType === 'POBO_DEBIT' && (
                        <Card className="mt-4 bg-warning-50/50 border-warning-200 dark:border-warning-500/30">
                          <div className="flex items-start gap-3">
                            <StatusIconBadge tone="warning" icon={Building2} className="flex-shrink-0 dark:bg-warning-500/20" />
                            <div>
                              <p className="font-semibold text-warning-800 dark:text-warning-300">POBO Intercompany Entries</p>
                              <p className="text-sm text-warning-700 mt-1 dark:text-warning-300">
                                For POBO payments, the Treasury entity pays on behalf of the subsidiary.
                                This creates an intercompany receivable from Treasury to the Subsidiary,
                                which will be settled during the next netting cycle or via direct transfer.
                              </p>
                              <div className="mt-3 p-3 bg-white/60 rounded-lg dark:bg-primary-900/60">
                                <p className="text-xs font-medium text-warning-800 mb-2 dark:text-warning-300">Intercompany Flow:</p>
                                <div className="flex items-center gap-2 text-xs text-warning-700 dark:text-warning-300">
                                  <span className="font-mono bg-warning-100 px-2 py-1 rounded dark:bg-warning-500/20">Treasury VA</span>
                                  <ArrowRight className="w-3 h-3" />
                                  <span className="font-mono bg-warning-100 px-2 py-1 rounded dark:bg-warning-500/20">External Beneficiary</span>
                                </div>
                                <div className="flex items-center gap-2 text-xs text-warning-700 mt-2 dark:text-warning-300">
                                  <span className="font-mono bg-warning-100 px-2 py-1 rounded dark:bg-warning-500/20">Subsidiary</span>
                                  <ArrowRight className="w-3 h-3" />
                                  <span>Owes Treasury (IC Receivable)</span>
                                </div>
                              </div>
                            </div>
                          </div>
                        </Card>
                      )}
                    </Card>
                  ) : (
                    <div className="text-center py-12">
                      <div className="w-16 h-16 rounded-2xl bg-neutral-100 flex items-center justify-center mx-auto mb-4 dark:bg-primary-800">
                        <BookOpen className="w-8 h-8 text-neutral-300 dark:text-neutral-600" />
                      </div>
                      <p className="text-neutral-500 dark:text-neutral-400">No accounting entries found</p>
                      <p className="text-sm text-neutral-400 mt-1 dark:text-neutral-500">
                        Transaction entries will appear after processing
                      </p>
                    </div>
                  );
                })()}
              </div>
            )}
          </>
        )}

        {/* Close Button */}
        <div className="flex justify-end pt-4 border-t border-neutral-200 dark:border-primary-800">
          <Button onClick={onClose}>Close</Button>
        </div>
      </div>
    </Modal>
  );
};

// ============================================================================
// MAIN COMPONENT
// ============================================================================

export default function TransfersPage() {
  // State
  const [activeTab, setActiveTab] = useState<TabType>('new');
  const [transferType, setTransferType] = useState<TransferType>('internal');
  const [step, setStep] = useState(1);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [refreshing, setRefreshing] = useState(false);

  // Corporate/Program/Legal Entity selection
  const [selectedCorporateId, setSelectedCorporateId] = useState('');
  const [selectedLegalEntityId, setSelectedLegalEntityId] = useState('');
  const [selectedProgramId, setSelectedProgramId] = useState('');

  // Data
  const [allAccounts, setAllAccounts] = useState<VirtualAccount[]>([]);
  const [accounts, setAccounts] = useState<VirtualAccount[]>([]);
  const [corporates, setCorporates] = useState<Corporate[]>([]);
  const [legalEntities, setLegalEntities] = useState<LegalEntity[]>([]);
  const [programs, setPrograms] = useState<Program[]>([]);
  const [recentTransfers, setRecentTransfers] = useState<Transaction[]>([]);

  // Beneficiaries (Parties with bank accounts for outward payments)
  const [beneficiaries, setBeneficiaries] = useState<Beneficiary[]>([]);
  const [beneficiarySearch, setBeneficiarySearch] = useState('');

  // Payers (Parties with bank accounts for inward payments - reuse same structure)
  const [payers, setPayers] = useState<Beneficiary[]>([]);
  const [payerSearch, setPayerSearch] = useState('');

  // Form state
  const [formData, setFormData] = useState<TransferFormData>({
    fromVaId: '',
    toVaId: '',
    amount: '',
    currency: 'AED',
    description: '',
    valueDate: new Date().toISOString().split('T')[0], // Default to today
    creditorName: '',
    creditorAccount: '',
    creditorBic: '',
    creditorBankName: '',
    remittanceInfo: '',
    isPobo: false,
    behalfOfEntity: '',
    behalfOfVaId: '',
    selectedBeneficiaryId: '',
    selectedBankAccountId: '',
    // Inward payment fields
    debtorName: '',
    debtorAccount: '',
    debtorBic: '',
    debtorBankName: '',
    selectedPayerId: '',
    selectedPayerBankAccountId: '',
    targetVibanOrVa: '',
  });

  // Bulk transfer state
  const [bulkItems, setBulkItems] = useState<BulkTransferItem[]>([]);

  // Result state
  const [lastResult, setLastResult] = useState<any>(null);
  const [showResultModal, setShowResultModal] = useState(false);
  const [showXmlModal, setShowXmlModal] = useState(false);
  const [xmlContent, setXmlContent] = useState('');

  // Transfer detail modal state
  const [selectedTransfer, setSelectedTransfer] = useState<Transaction | null>(null);
  const [showDetailModal, setShowDetailModal] = useState(false);

  // Load initial data function
  const loadInitialData = async () => {
    setLoading(true);
    try {
      // Load accounts, corporates, programs, and multiple transfer types (outbound + inbound).
      //
      // allSettled, NOT all: with Promise.all a single failing call (e.g. one
      // transactions filter 500ing) rejected the whole batch, so even the
      // successful corporates/programs/accounts responses were discarded —
      // the visible symptom was an empty corporate picker while the
      // corporates API itself was perfectly healthy. Each response is now
      // unwrapped independently; a failed call just contributes nothing.
      const settled = await Promise.allSettled([
        virtualAccountsApi.getAll(0, 500),
        corporatesApi.getAll(0, 100),
        programsApi.getAll({ page: 0, size: 100 }),
        // Internal transfers (VA to VA)
        transactionsApi.getAll({ movementType: 'TRANSFER_OUT', pageSize: 30 }),
        // Outward payments (external beneficiaries) - uses DEBIT movement type per Iso20022MessageMapper
        transactionsApi.getAll({ movementType: 'DEBIT', pageSize: 30 }),
        // POBO payments
        transactionsApi.getAll({ movementType: 'POBO_DEBIT', pageSize: 30 }),
        // ROBO collections (inward via VIBAN)
        transactionsApi.getAll({ movementType: 'ROBO_CREDIT', pageSize: 30 }),
        // General inward credits
        transactionsApi.getAll({ movementType: 'CREDIT', pageSize: 30 }),
      ]);
      const unwrap = <T,>(r: PromiseSettledResult<T>, label: string): T | null => {
        if (r.status === 'fulfilled') return r.value;
        console.error(`[Transfers] ${label} failed:`, r.reason);
        return null;
      };
      const accountsRes    = unwrap(settled[0], 'accounts');
      const corporatesRes  = unwrap(settled[1], 'corporates');
      const programsRes    = unwrap(settled[2], 'programs');
      const transferOutRes = unwrap(settled[3], 'transfers (TRANSFER_OUT)');
      const debitRes       = unwrap(settled[4], 'transfers (DEBIT)');
      const poboRes        = unwrap(settled[5], 'transfers (POBO_DEBIT)');
      const roboRes        = unwrap(settled[6], 'transfers (ROBO_CREDIT)');
      const creditRes      = unwrap(settled[7], 'transfers (CREDIT)');

      if (accountsRes?.data) {
        // Filter to only show Transaction VAs (not header/aggregation accounts)
        const all = Array.isArray(accountsRes.data) ? accountsRes.data : [];
        const transactionVas = all.filter(isTransactionVa);
        setAllAccounts(transactionVas);
        setAccounts(transactionVas);
      }
      if (corporatesRes?.data) setCorporates(Array.isArray(corporatesRes.data) ? corporatesRes.data : []);
      if (programsRes?.data) {
        const programsList = Array.isArray(programsRes.data) ? programsRes.data : (programsRes.data as any)?.content || [];
        setPrograms(programsList);
      }

      // Combine all transfer types (outbound + inbound), filter out settlement legs, and sort by date
      // Note: VA-to-VA transfers use channel='INTERNAL' which is valid and should NOT be filtered
      const allTransfers: Transaction[] = [
        ...(transferOutRes?.data?.content || []),
        ...(debitRes?.data?.content || []),
        ...(poboRes?.data?.content || []),
        ...(roboRes?.data?.content || []),
        ...(creditRes?.data?.content || []),
      ]
       .filter(t => {
         // Filter out Settlement VA transactions by reference prefix (these are internal clearing legs)
         if (t.referenceNumber?.startsWith('SET-')) return false;
         // Filter out Fee transactions (internal accounting entries)
         if (t.referenceNumber?.startsWith('FEE-')) return false;
         // Filter out Settlement VA legs by VA number pattern (legs 2 & 3 of 4-leg flow)
         if (t.vaNumber?.includes('SETTLE') || t.vaNumber?.includes('SETTLEMENT')) return false;
         // Keep user-facing transactions: TRANSFER_OUT from source VA, TRANSFER_IN to destination VA
         return true;
       })
       .sort((a, b) => new Date(b.transactionDate).getTime() - new Date(a.transactionDate).getTime())
       .slice(0, 50); // Limit to 50 most recent

      setRecentTransfers(allTransfers);
    } catch (error) {
      console.error('Failed to load data:', error);
    } finally {
      setLoading(false);
    }
  };

  // Load data on mount
  useEffect(() => {
    loadInitialData();
  }, []);

  // Filter accounts when corporate/legal entity/program changes
  useEffect(() => {
    let filtered = allAccounts;
    if (selectedCorporateId) {
      filtered = filtered.filter(a => a.corporateId === selectedCorporateId);
    }
    if (selectedLegalEntityId) {
      // Filter by legal entity (owningEntityId)
      filtered = filtered.filter(a => a.owningEntityId === selectedLegalEntityId);
    }
    if (selectedProgramId) {
      // Filter by program if VA has programId field
      filtered = filtered.filter(a => a.programId === selectedProgramId);
    }
    setAccounts(filtered);
    // Reset form selections when filter changes
    setFormData(prev => ({ ...prev, fromVaId: '', toVaId: '' }));
  }, [selectedCorporateId, selectedLegalEntityId, selectedProgramId, allAccounts]);

  // Load legal entities when corporate changes
  useEffect(() => {
    if (selectedCorporateId) {
      loadLegalEntities();
    } else {
      setLegalEntities([]);
      setSelectedLegalEntityId('');
    }
  }, [selectedCorporateId]);

  const loadLegalEntities = async () => {
    if (!selectedCorporateId) return;
    try {
      const response = await legalEntityApi.getByCorporate(selectedCorporateId);
      if (response.data) {
        setLegalEntities(Array.isArray(response.data) ? response.data : []);
      }
    } catch (error) {
      console.error('Failed to load legal entities:', error);
      setLegalEntities([]);
    }
  };

  // Reset POBO flag when source account changes to an entity that doesn't support POBO
  // (i.e., Treasury Center or non-IHB participant)
  useEffect(() => {
    if (!formData.fromVaId || !formData.isPobo) return;

    const account = allAccounts.find(a => a.id === formData.fromVaId);
    if (!account?.owningEntityId) return;

    const entity = legalEntities.find(e => e.id === account.owningEntityId);
    if (!entity) return;

    // If the entity is a Treasury Center or not an IHB participant, reset POBO
    const isIhbParticipant = entity.ihbEnabled && entity.canBorrow && !entity.isTreasuryCenter;
    if (!isIhbParticipant) {
      setFormData(prev => ({ ...prev, isPobo: false, behalfOfEntity: '', behalfOfVaId: '' }));
    }
  }, [formData.fromVaId, legalEntities, allAccounts]);

  // Load beneficiaries when corporate changes (for outward payments)
  useEffect(() => {
    if (selectedCorporateId && transferType === 'outward') {
      loadBeneficiaries();
    }
  }, [selectedCorporateId, transferType]);

  // Load payers when corporate changes (for inward payments)
  useEffect(() => {
    if (selectedCorporateId && transferType === 'inward') {
      loadPayers();
    }
  }, [selectedCorporateId, transferType]);

  const loadBeneficiaries = async () => {
    if (!selectedCorporateId) return;
    try {
      const response = await partiesApi.getAll({
        corporateId: selectedCorporateId,
        role: 'VENDOR',
        pageSize: 100
      });

      // Handle different response structures:
      // Backend returns PartyListResponse directly (not wrapped in ApiResponse)
      const partiesList = response?.parties || response?.data?.parties || [];

      if (partiesList.length > 0) {
        // Get bank accounts for each party
        const partiesWithAccounts: Beneficiary[] = [];
        for (const party of partiesList) {
          try {
            const detailRes = await partiesApi.getDetail(party.id);
            const bankAccounts = detailRes?.bankAccounts || detailRes?.data?.bankAccounts || [];
            partiesWithAccounts.push({
              party,
              bankAccounts
            });
          } catch (err) {
            partiesWithAccounts.push({ party, bankAccounts: [] });
          }
        }
        setBeneficiaries(partiesWithAccounts);
      } else {
        setBeneficiaries([]);
      }
    } catch (error) {
      console.error('Failed to load beneficiaries:', error);
      setBeneficiaries([]);
    }
  };

  // Load payers (CUSTOMER role parties for inward payments)
  const loadPayers = async () => {
    if (!selectedCorporateId) return;
    try {
      // For inward payments, payers are typically CUSTOMER role parties
      const response = await partiesApi.getAll({
        corporateId: selectedCorporateId,
        role: 'CUSTOMER',
        pageSize: 100
      });

      const partiesList = response?.parties || response?.data?.parties || [];

      if (partiesList.length > 0) {
        const partiesWithAccounts: Beneficiary[] = [];
        for (const party of partiesList) {
          try {
            const detailRes = await partiesApi.getDetail(party.id);
            const bankAccounts = detailRes?.bankAccounts || detailRes?.data?.bankAccounts || [];
            partiesWithAccounts.push({
              party,
              bankAccounts
            });
          } catch (err) {
            partiesWithAccounts.push({ party, bankAccounts: [] });
          }
        }
        setPayers(partiesWithAccounts);
      } else {
        setPayers([]);
      }
    } catch (error) {
      console.error('Failed to load payers:', error);
      setPayers([]);
    }
  };

  const handleRefresh = async () => {
    setRefreshing(true);
    await loadInitialData();
    setRefreshing(false);
  };

  const resetForm = () => {
    setFormData({
      fromVaId: '',
      toVaId: '',
      amount: '',
      currency: 'AED',
      description: '',
      valueDate: new Date().toISOString().split('T')[0], // Reset to today
      creditorName: '',
      creditorAccount: '',
      creditorBic: '',
      creditorBankName: '',
      remittanceInfo: '',
      isPobo: false,
      behalfOfEntity: '',
      behalfOfVaId: '',
      selectedBeneficiaryId: '',
      selectedBankAccountId: '',
      // Inward payment fields
      debtorName: '',
      debtorAccount: '',
      debtorBic: '',
      debtorBankName: '',
      selectedPayerId: '',
      selectedPayerBankAccountId: '',
      targetVibanOrVa: '',
    });
    setBulkItems([]);
    setBeneficiarySearch('');
    setPayerSearch('');
    setStep(1);
  };

  // Handle beneficiary selection
  const handleBeneficiarySelect = (beneficiaryId: string, bankAccountId?: string) => {
    const beneficiary = beneficiaries.find(b => b.party.id === beneficiaryId);
    if (!beneficiary) return;

    const bankAccount = bankAccountId
      ? beneficiary.bankAccounts.find(ba => ba.id === bankAccountId)
      : beneficiary.bankAccounts.find(ba => ba.isPrimary) || beneficiary.bankAccounts[0];

    setFormData(prev => ({
      ...prev,
      selectedBeneficiaryId: beneficiaryId,
      selectedBankAccountId: bankAccount?.id || '',
      creditorName: beneficiary.party.legalName,
      creditorAccount: bankAccount?.iban || bankAccount?.accountNumber || '',
      creditorBic: bankAccount?.bankCode || '',
      creditorBankName: bankAccount?.bankName || '',
    }));
  };

  // Handle payer selection (for inward payments)
  const handlePayerSelect = (payerId: string, bankAccountId?: string) => {
    const payer = payers.find(p => p.party.id === payerId);
    if (!payer) return;

    const bankAccount = bankAccountId
      ? payer.bankAccounts.find(ba => ba.id === bankAccountId)
      : payer.bankAccounts.find(ba => ba.isPrimary) || payer.bankAccounts[0];

    setFormData(prev => ({
      ...prev,
      selectedPayerId: payerId,
      selectedPayerBankAccountId: bankAccount?.id || '',
      debtorName: payer.party.legalName,
      debtorAccount: bankAccount?.iban || bankAccount?.accountNumber || '',
      debtorBic: bankAccount?.bankCode || '',
      debtorBankName: bankAccount?.bankName || '',
    }));
  };

  // Get filtered beneficiaries based on search
  const filteredBeneficiaries = beneficiaries.filter(b =>
    !beneficiarySearch ||
    b.party.legalName.toLowerCase().includes(beneficiarySearch.toLowerCase()) ||
    b.party.partyCode.toLowerCase().includes(beneficiarySearch.toLowerCase())
  );

  // Get filtered payers based on search
  const filteredPayers = payers.filter(p =>
    !payerSearch ||
    p.party.legalName.toLowerCase().includes(payerSearch.toLowerCase()) ||
    p.party.partyCode.toLowerCase().includes(payerSearch.toLowerCase())
  );

  // Get selected accounts
  const sourceAccount = accounts.find(a => a.id === formData.fromVaId);
  const targetAccount = accounts.find(a => a.id === formData.toVaId);

  // Determine if POBO toggle should be shown based on source account's entity
  // POBO is only available for IHB participants (subsidiaries that can borrow from Treasury Center)
  // - Show POBO: If entity is IHB-enabled, canBorrow=true, and NOT a Treasury Center
  // - Hide POBO: If entity is a Treasury Center OR not an IHB participant
  const shouldShowPoboToggle = (): boolean => {
    if (!sourceAccount?.owningEntityId) return false;

    const sourceEntity = legalEntities.find(e => e.id === sourceAccount.owningEntityId);
    if (!sourceEntity) return false;

    // Treasury Centers don't need POBO - they are the treasury itself
    if (sourceEntity.isTreasuryCenter) return false;

    // Only IHB participants (subsidiaries that can borrow) can use POBO
    // They pay on behalf of themselves through the Treasury Center
    const isIhbParticipant = sourceEntity.ihbEnabled && sourceEntity.canBorrow;
    return isIhbParticipant;
  };

  // Handle internal transfer
  const handleInternalTransfer = async () => {
    if (!formData.fromVaId || !formData.toVaId || !formData.amount) {
      toast.error('Please fill in all required fields');
      return;
    }

    setSubmitting(true);
    try {
      const request: TransferRequest = {
        fromVaId: formData.fromVaId,
        toVaId: formData.toVaId,
        amount: parseFloat(formData.amount),
        description: formData.description || 'Internal Transfer',
        valueDate: formData.valueDate || undefined,
      };

      const response = await transactionsApi.transfer(request);

      if (response.success) {
        toast.success('Transfer completed successfully');
        setLastResult(response.data);
        setShowResultModal(true);
        resetForm();
        loadInitialData();
      } else {
        toast.error('Transfer failed');
      }
    } catch (error: any) {
      toast.error(error.message || 'Transfer failed');
    } finally {
      setSubmitting(false);
    }
  };

  // Handle ISO 20022 outward payment
  const handleOutwardPayment = async () => {
    if (!formData.fromVaId || !formData.amount || !formData.creditorName || !formData.creditorAccount) {
      toast.error('Please fill in all required fields');
      return;
    }

    setSubmitting(true);
    try {
      const request: Iso20022OutwardPaymentRequest = {
        sourceVaId: formData.fromVaId,
        amount: parseFloat(formData.amount),
        currency: formData.currency,
        creditorName: formData.creditorName,
        creditorAccount: formData.creditorAccount,
        creditorBic: formData.creditorBic || undefined,
        creditorBankName: formData.creditorBankName || undefined,
        remittanceInfo: formData.remittanceInfo || formData.description,
        isPobo: formData.isPobo,
        behalfOfEntity: formData.isPobo ? formData.behalfOfEntity : undefined,
        behalfOfVaId: formData.isPobo ? formData.behalfOfVaId : undefined,
        endToEndId: `E2E-${Date.now()}`,
      };

      const response = await iso20022Api.processOutwardPayment(request);
      const data = response.data || response;

      if (data.success) {
        toast.success(`Payment processed: ${data.transactionReference}`);
        setLastResult(data);
        if (data.pain001Xml) {
          setXmlContent(data.pain001Xml);
        }
        setShowResultModal(true);
        resetForm();
        loadInitialData();
      } else {
        toast.error(data.errorMessage || 'Payment failed');
      }
    } catch (error: any) {
      toast.error(error.message || 'Payment failed');
    } finally {
      setSubmitting(false);
    }
  };

  // Handle inward payment (ISO 20022 collection)
  const handleInwardPayment = async () => {
    if (!formData.targetVibanOrVa || !formData.amount || !formData.debtorName) {
      toast.error('Please fill in all required fields');
      return;
    }

    setSubmitting(true);
    try {
      const request = {
        amount: parseFloat(formData.amount),
        currency: formData.currency,
        creditorAccount: formData.targetVibanOrVa,  // VIBAN or VA number
        debtorName: formData.debtorName,
        debtorAccount: formData.debtorAccount || undefined,
        debtorBic: formData.debtorBic || undefined,
        remittanceInfo: formData.remittanceInfo || formData.description,
        endToEndId: `E2E-${Date.now()}`,
        channel: 'PORTAL',
      };

      const response = await iso20022Api.processInwardPayment(request);
      const data = response.data || response;

      if (data.success) {
        toast.success(`Collection processed: ${data.transactionReference}`);
        setLastResult(data);
        setShowResultModal(true);
        resetForm();
        loadInitialData();
      } else {
        toast.error(data.statusReason || 'Collection failed');
      }
    } catch (error: any) {
      toast.error(error.message || 'Collection failed');
    } finally {
      setSubmitting(false);
    }
  };

  // Handle bulk transfer
  const handleBulkTransfer = async () => {
    if (!formData.fromVaId || bulkItems.length === 0) {
      toast.error('Please select source account and add transfer items');
      return;
    }

    setSubmitting(true);
    try {
      const request: BulkTransferRequest = {
        sourceVaId: formData.fromVaId,
        transfers: bulkItems.map(item => ({
          destinationVaId: item.destinationVaId,
          amount: parseFloat(item.amount),
          description: item.description,
          valueDate: item.valueDate || undefined,
        })),
        description: formData.description || 'Bulk Transfer',
      };

      const response = await transactionsApi.bulkTransfer(request);

      if (response.success) {
        const data = response.data as BulkTransferResponse;
        toast.success(`Bulk transfer completed: ${data.successCount}/${data.totalCount} successful`);
        setLastResult(data);
        setShowResultModal(true);
        resetForm();
        loadInitialData();
      } else {
        toast.error('Bulk transfer failed');
      }
    } catch (error: any) {
      toast.error(error.message || 'Bulk transfer failed');
    } finally {
      setSubmitting(false);
    }
  };

  // Add bulk item
  const addBulkItem = () => {
    setBulkItems([
      ...bulkItems,
      {
        id: `item-${Date.now()}`,
        destinationVaId: '',
        destinationVaName: '',
        amount: '',
        description: '',
        valueDate: new Date().toISOString().split('T')[0], // Default to today
      },
    ]);
  };

  // Remove bulk item
  const removeBulkItem = (id: string) => {
    setBulkItems(bulkItems.filter(item => item.id !== id));
  };

  // Update bulk item
  const updateBulkItem = (id: string, field: keyof BulkTransferItem, value: string) => {
    setBulkItems(bulkItems.map(item => {
      if (item.id === id) {
        if (field === 'destinationVaId') {
          const account = accounts.find(a => a.id === value);
          return { ...item, [field]: value, destinationVaName: account?.vaName || '' };
        }
        return { ...item, [field]: value };
      }
      return item;
    }));
  };

  // Calculate bulk total
  const bulkTotal = bulkItems.reduce((sum, item) => sum + (parseFloat(item.amount) || 0), 0);

  // Calculate stats
  const todayTransfers = recentTransfers.filter(t => {
    const txnDate = new Date(t.transactionDate).toDateString();
    return txnDate === new Date().toDateString();
  });
  const todayVolume = todayTransfers.reduce((sum, t) => sum + t.amount, 0);

  // Steps for wizard
  const getSteps = () => {
    // Reduced to 3 steps since the review panel is now on the sidebar
    if (transferType === 'internal') {
      return [
        { id: 'source', title: 'Source' },
        { id: 'destination', title: 'Destination' },
        { id: 'amount', title: 'Amount & Details' },
      ];
    } else if (transferType === 'outward') {
      return [
        { id: 'source', title: 'Source' },
        { id: 'beneficiary', title: 'Beneficiary' },
        { id: 'amount', title: 'Amount & Details' },
      ];
    } else if (transferType === 'inward') {
      return [
        { id: 'payer', title: 'Payer' },
        { id: 'destination', title: 'Destination' },
        { id: 'amount', title: 'Amount & Details' },
      ];
    } else {
      return [
        { id: 'source', title: 'Source' },
        { id: 'recipients', title: 'Recipients' },
      ];
    }
  };

  // Handle submit based on type
  const handleSubmit = () => {
    if (transferType === 'internal') handleInternalTransfer();
    else if (transferType === 'outward') handleOutwardPayment();
    else if (transferType === 'inward') handleInwardPayment();
    else handleBulkTransfer();
  };

  // Validate step
  const canProceed = () => {
    if (step === 1) {
      // For inward: step 1 is payer selection
      if (transferType === 'inward') return !!formData.debtorName;
      return !!formData.fromVaId;
    }
    if (step === 2) {
      if (transferType === 'internal') return !!formData.toVaId;
      if (transferType === 'outward') return !!formData.creditorName && !!formData.creditorAccount;
      if (transferType === 'inward') return !!formData.targetVibanOrVa;  // VIBAN or VA destination
      if (transferType === 'bulk') return bulkItems.length > 0 && bulkItems.every(i => i.destinationVaId && i.amount);
    }
    if (step === 3) {
      if (transferType === 'bulk') return true;
      return !!formData.amount && parseFloat(formData.amount) > 0;
    }
    return true;
  };

  const maxSteps = transferType === 'bulk' ? 2 : 3;

  if (loading) {
    return (
      <Page>
        <LoadingSpinner />
      </Page>
    );
  }

  // Get filtered programs based on selected corporate
  const filteredPrograms = selectedCorporateId
    ? programs.filter(p => p.corporateId === selectedCorporateId)
    : programs;

  return (
    <Page>
      {/* Corporate/Legal Entity/Program Selector */}
      <Card
        className="bg-gradient-to-r from-primary-50/50 via-white to-primary-50/50 border-primary-100/50 animate-fade-in dark:from-primary-900 dark:via-primary-900 dark:to-primary-900"
      >
        {/* Desktop Layout */}
        <div className="hidden sm:flex flex-wrap items-center gap-4">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-lg bg-primary-100 flex items-center justify-center dark:bg-primary-700">
              <Building2 className="w-4 h-4 text-primary-600 dark:text-primary-200" />
            </div>
            <div className="flex flex-col">
              <span className="text-xs text-neutral-500 dark:text-neutral-400">Corporate</span>
              <Select
                value={selectedCorporateId}
                onChange={(e) => {
                  setSelectedCorporateId(e.target.value);
                  setSelectedLegalEntityId('');
                  setSelectedProgramId('');
                }}
                options={[
                  { value: '', label: `All Corporates (${corporates.length})` },
                  ...corporates.map((c) => ({
                    value: c.id,
                    label: c.legalName || c.tradeName || 'Unnamed',
                  })),
                ]}
                disabled={loading}
                className="min-w-[200px]"
              />
            </div>
          </div>

          <div className="w-px h-12 bg-neutral-200 dark:bg-primary-800" />

          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-lg bg-success-100 flex items-center justify-center dark:bg-success-500/20">
              <Users className="w-4 h-4 text-success-600 dark:text-success-300" />
            </div>
            <div className="flex flex-col">
              <span className="text-xs text-neutral-500 dark:text-neutral-400">Legal Entity</span>
              <Select
                value={selectedLegalEntityId}
                onChange={(e) => setSelectedLegalEntityId(e.target.value)}
                options={[
                  { value: '', label: `All Entities (${legalEntities.length})` },
                  ...legalEntities.map((e) => ({
                    value: e.id,
                    label: `${e.entityName} (${e.entityType})`,
                  })),
                ]}
                disabled={loading || !selectedCorporateId}
                className="min-w-[200px]"
              />
            </div>
          </div>

          <div className="w-px h-12 bg-neutral-200 dark:bg-primary-800" />

          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-lg bg-accent-100 flex items-center justify-center dark:bg-accent-500/20">
              <Briefcase className="w-4 h-4 text-accent-600 dark:text-accent-300" />
            </div>
            <div className="flex flex-col">
              <span className="text-xs text-neutral-500 dark:text-neutral-400">Program</span>
              <Select
                value={selectedProgramId}
                onChange={(e) => setSelectedProgramId(e.target.value)}
                options={[
                  { value: '', label: `All Programs (${filteredPrograms.length})` },
                  ...filteredPrograms.map((p) => ({
                    value: p.id,
                    label: `${p.programName} (${p.programType || 'General'})`,
                  })),
                ]}
                disabled={loading}
                className="min-w-[220px]"
              />
            </div>
          </div>

          {(selectedCorporateId || selectedLegalEntityId || selectedProgramId) && (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => {
                setSelectedCorporateId('');
                setSelectedLegalEntityId('');
                setSelectedProgramId('');
              }}
              className="ml-auto"
            >
              <X className="w-4 h-4 mr-1" />
              Clear
            </Button>
          )}

          <div className="ml-auto flex items-center gap-2">
            <Button
              variant="ghost"
              leftIcon={<RefreshCw className={cn("w-4 h-4", refreshing && "animate-spin")} />}
              onClick={handleRefresh}
              disabled={refreshing}
            >
              Refresh
            </Button>
          </div>
        </div>

        {/* Mobile Layout */}
        <div className="sm:hidden space-y-3">
          <Select
            value={selectedCorporateId}
            onChange={(e) => {
              setSelectedCorporateId(e.target.value);
              setSelectedLegalEntityId('');
              setSelectedProgramId('');
            }}
            options={[
              { value: '', label: `All Corporates (${corporates.length})` },
              ...corporates.map((c) => ({
                value: c.id,
                label: c.legalName || c.tradeName || 'Unnamed',
              })),
            ]}
            disabled={loading}
          />
          <Select
            value={selectedLegalEntityId}
            onChange={(e) => setSelectedLegalEntityId(e.target.value)}
            options={[
              { value: '', label: `All Legal Entities (${legalEntities.length})` },
              ...legalEntities.map((e) => ({
                value: e.id,
                label: `${e.entityName} (${e.entityType})`,
              })),
            ]}
            disabled={loading || !selectedCorporateId}
          />
          <Select
            value={selectedProgramId}
            onChange={(e) => setSelectedProgramId(e.target.value)}
            options={[
              { value: '', label: `All Programs (${filteredPrograms.length})` },
              ...filteredPrograms.map((p) => ({
                value: p.id,
                label: `${p.programName} (${p.programType || 'General'})`,
              })),
            ]}
            disabled={loading}
          />
        </div>
      </Card>

      {/* Stats */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <StatCard
          title="Today's Transfers"
          value={todayTransfers.length.toString()}
          subtitle="Completed"
          icon={<Send className="w-5 h-5" />}
          variant="primary"
          delay="0.1s"
        />
        <StatCard
          title="Volume Today"
          value={formatCurrency(todayVolume, 'AED')}
          subtitle="Total transferred"
          icon={<TrendingUp className="w-5 h-5" />}
          variant="success"
          delay="0.15s"
        />
        <StatCard
          title="Transaction VAs"
          value={accounts.length.toString()}
          subtitle="Available for transfer"
          icon={<Wallet className="w-5 h-5" />}
          variant="info"
          delay="0.2s"
        />
        <StatCard
          title="Total Balance"
          value={formatCurrency(accounts.reduce((sum, a) => sum + (a.currentBalance || 0), 0), 'AED')}
          subtitle="Transaction VAs only"
          icon={<CreditCard className="w-5 h-5" />}
          variant="warning"
          delay="0.25s"
        />
      </div>

      {/* Tabs */}
      <div className="flex gap-2 animate-fade-in" style={{ animationDelay: '0.3s' }}>
        {[
          { id: 'new', label: 'New Transfer', icon: Plus },
          { id: 'history', label: 'Transfer History', icon: Clock },
        ].map(tab => (
          <button
            key={tab.id}
            onClick={() => { setActiveTab(tab.id as TabType); if (tab.id === 'new') resetForm(); }}
            className={cn(
              'flex items-center gap-2 px-5 py-2.5 rounded-xl font-medium transition-all duration-200',
              activeTab === tab.id
                ? 'bg-primary-600 text-white shadow-lg shadow-primary-200'
                : 'bg-white text-neutral-600 hover:bg-neutral-50 border border-neutral-200 dark:bg-primary-900 dark:text-neutral-300 dark:hover:bg-primary-800/50 dark:border-primary-800'
            )}
          >
            <tab.icon className="w-4 h-4" />
            {tab.label}
          </button>
        ))}
      </div>

      {/* New Transfer Tab */}
      {activeTab === 'new' && (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          {/* Main Form */}
          <div className="lg:col-span-2 space-y-6">
            {/* Transfer Type Selection */}
            <Card className="animate-fade-in" style={{ animationDelay: '0.35s' }}>
              <h3 className="section-title mb-4">Select Transfer Type</h3>
              <div className="grid grid-cols-4 gap-4">
                <TransferTypeCard
                  id="internal"
                  label="Internal"
                  description="VA-to-VA transfer"
                  icon={<ArrowLeftRight className="w-6 h-6" />}
                  selected={transferType === 'internal'}
                  onClick={() => { setTransferType('internal'); setStep(1); resetForm(); }}
                />
                <TransferTypeCard
                  id="outward"
                  label="Outward"
                  description="ISO 20022 pain.001"
                  icon={<Globe className="w-6 h-6" />}
                  selected={transferType === 'outward'}
                  onClick={() => { setTransferType('outward'); setStep(1); resetForm(); }}
                />
                <TransferTypeCard
                  id="inward"
                  label="Inward"
                  description="Collection / ROBO"
                  icon={<ArrowDownLeft className="w-6 h-6" />}
                  selected={transferType === 'inward'}
                  onClick={() => { setTransferType('inward'); setStep(1); resetForm(); }}
                />
                <TransferTypeCard
                  id="bulk"
                  label="Bulk"
                  description="Multiple recipients"
                  icon={<Users className="w-6 h-6" />}
                  selected={transferType === 'bulk'}
                  onClick={() => { setTransferType('bulk'); setStep(1); resetForm(); }}
                />
              </div>
            </Card>

            {/* Step Indicator */}
            <div className="animate-fade-in" style={{ animationDelay: '0.4s' }}>
              <Stepper steps={getSteps()} currentStep={step - 1} size="sm" />
            </div>

            {/* Step 1: Source Account (for internal/outward/bulk) */}
            {step === 1 && transferType !== 'inward' && (
              <div className="animate-fade-in">
                <AccountSelector
                  label="Source Account (Debit From)"
                  value={formData.fromVaId}
                  onChange={(value) => setFormData({ ...formData, fromVaId: value })}
                  accounts={accounts}
                  variant="source"
                  loading={loading}
                />
              </div>
            )}

            {/* Step 1: Payer Selection (for inward) */}
            {step === 1 && transferType === 'inward' && (
              <Card className="animate-fade-in">
                <h3 className="section-title mb-6">Payer Details</h3>
                <div className="space-y-4">
                  {/* Payer Picker from Parties (CUSTOMER role) */}
                  <div className="p-4 rounded-xl bg-gradient-to-br from-success-50/50 to-white border border-success-100 dark:border-success-500/30">
                    <div className="flex items-center gap-3 mb-4">
                      <StatusIconBadge tone="success" icon={UserCheck} className="dark:bg-success-500/20" />
                      <div>
                        <p className="font-semibold text-primary-900 dark:text-neutral-50">Select Payer</p>
                        <p className="text-xs text-neutral-500 dark:text-neutral-400">Choose from registered customers ({payers.length} available)</p>
                      </div>
                    </div>

                    {/* Payer Dropdown Selector */}
                    <div className="space-y-3">
                      <div className="relative">
                        <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400 z-10 dark:text-neutral-500" />
                        <select
                          value={formData.selectedPayerId}
                          onChange={(e) => {
                            if (e.target.value) {
                              handlePayerSelect(e.target.value);
                            } else {
                              setFormData(prev => ({
                                ...prev,
                                selectedPayerId: '',
                                selectedPayerBankAccountId: '',
                                debtorName: '',
                                debtorAccount: '',
                                debtorBic: '',
                                debtorBankName: '',
                              }));
                            }
                          }}
                          className="w-full pl-10 pr-4 py-3 bg-white border border-success-200 rounded-xl text-sm font-medium focus:ring-2 focus:ring-success-500 focus:border-success-500 transition-all appearance-none cursor-pointer dark:bg-primary-900 dark:border-success-500/30"
                        >
                          <option value="">-- Select a payer --</option>
                          {filteredPayers.map((p) => (
                            <option key={p.party.id} value={p.party.id}>
                              {p.party.legalName} ({p.party.partyCode}) - {p.bankAccounts.length} account{p.bankAccounts.length !== 1 ? 's' : ''}
                            </option>
                          ))}
                        </select>
                        <ChevronRight className="absolute right-3 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400 rotate-90 dark:text-neutral-500" />
                      </div>

                      {/* Selected Payer Display */}
                      {formData.selectedPayerId && (
                        <div className="p-3 bg-success-50 rounded-xl border border-success-200 dark:bg-success-500/10 dark:border-success-500/30">
                          <div className="flex items-center gap-3">
                            <StatusIconBadge tone="success" icon={Building2} rounded="lg" className="dark:bg-success-500/20" />
                            <div className="flex-1">
                              <p className="font-semibold text-success-900">{formData.debtorName}</p>
                              <p className="text-xs text-success-600 font-mono dark:text-success-300">{formData.debtorAccount}</p>
                              {formData.debtorBic && (
                                <p className="text-xs text-neutral-500 dark:text-neutral-400">BIC: {formData.debtorBic}</p>
                              )}
                            </div>
                            <Badge variant="success" size="sm">Selected</Badge>
                          </div>
                        </div>
                      )}
                    </div>
                  </div>

                  {/* Manual Entry Option */}
                  <div className="border-t border-neutral-200 pt-4 dark:border-primary-800">
                    <p className="text-sm text-neutral-500 mb-3 dark:text-neutral-400">Or enter payer details manually:</p>
                    <div className="grid grid-cols-2 gap-4">
                      <Input
                        label="Payer Name"
                        value={formData.debtorName}
                        onChange={(e) => setFormData({ ...formData, debtorName: e.target.value, selectedPayerId: '' })}
                        placeholder="Company Name"
                      />
                      <Input
                        label="Payer Account/IBAN"
                        value={formData.debtorAccount}
                        onChange={(e) => setFormData({ ...formData, debtorAccount: e.target.value })}
                        placeholder="AE123456789012345678901"
                      />
                      <Input
                        label="BIC/SWIFT (Optional)"
                        value={formData.debtorBic}
                        onChange={(e) => setFormData({ ...formData, debtorBic: e.target.value })}
                        placeholder="ABORAEAD"
                      />
                      <Input
                        label="Bank Name (Optional)"
                        value={formData.debtorBankName}
                        onChange={(e) => setFormData({ ...formData, debtorBankName: e.target.value })}
                        placeholder="Payer's Bank"
                      />
                    </div>
                  </div>
                </div>
              </Card>
            )}

            {/* Step 2: Destination/Beneficiary/Recipients */}
            {step === 2 && transferType === 'internal' && (
              <div className="animate-fade-in">
                <AccountSelector
                  label="Destination Account (Credit To)"
                  value={formData.toVaId}
                  onChange={(value) => setFormData({ ...formData, toVaId: value })}
                  accounts={accounts}
                  excludeId={formData.fromVaId}
                  variant="target"
                  loading={loading}
                />
              </div>
            )}

            {/* Step 2: Destination VIBAN/VA (for inward) */}
            {step === 2 && transferType === 'inward' && (
              <Card className="animate-fade-in">
                <h3 className="section-title mb-6">Destination Account</h3>
                <div className="space-y-4">
                  <div className="p-4 rounded-xl bg-gradient-to-br from-success-50/50 to-white border border-success-100 dark:border-success-500/30">
                    <div className="flex items-center gap-3 mb-4">
                      <StatusIconBadge tone="success" icon={ArrowDownLeft} className="dark:bg-success-500/20" />
                      <div>
                        <p className="font-semibold text-primary-900 dark:text-neutral-50">Credit Destination</p>
                        <p className="text-xs text-neutral-500 dark:text-neutral-400">Enter VIBAN for auto-routing or select a VA directly</p>
                      </div>
                    </div>

                    <div className="space-y-4">
                      {/* VIBAN / VA Number Input */}
                      <Input
                        label="VIBAN or VA Number"
                        value={formData.targetVibanOrVa}
                        onChange={(e) => setFormData({ ...formData, targetVibanOrVa: e.target.value, toVaId: '' })}
                        placeholder="Enter VIBAN (e.g., AE12VIBAN123456) or VA Number"
                        hint="VIBAN enables automatic routing and invoice matching"
                      />

                      {/* Or select from accounts */}
                      <div className="border-t border-neutral-200 pt-4 dark:border-primary-800">
                        <p className="text-sm text-neutral-500 mb-3 dark:text-neutral-400">Or select destination account directly:</p>
                        <Select
                          label="Destination VA"
                          value={formData.toVaId}
                          onChange={(e) => {
                            const selectedVa = accounts.find(a => a.id === e.target.value);
                            setFormData({
                              ...formData,
                              toVaId: e.target.value,
                              targetVibanOrVa: selectedVa?.viban || selectedVa?.vaNumber || e.target.value
                            });
                          }}
                          placeholder="Select destination account..."
                          options={accounts.map(acc => ({
                            value: acc.id,
                            label: `${acc.vaNumber} - ${acc.vaName} (${formatCurrency(acc.currentBalance, acc.currencyCode)})`
                          }))}
                        />
                      </div>

                      {/* Selected Destination Display */}
                      {(formData.targetVibanOrVa || formData.toVaId) && (
                        <div className="p-3 bg-success-50 rounded-xl border border-success-200 dark:bg-success-500/10 dark:border-success-500/30">
                          <div className="flex items-center gap-3">
                            <StatusIconBadge tone="success" icon={Wallet} rounded="lg" className="dark:bg-success-500/20" />
                            <div className="flex-1">
                              <p className="font-semibold text-success-900">
                                {formData.toVaId
                                  ? accounts.find(a => a.id === formData.toVaId)?.vaName || 'Selected Account'
                                  : 'VIBAN Routing'}
                              </p>
                              <p className="text-xs text-success-600 font-mono dark:text-success-300">{formData.targetVibanOrVa}</p>
                            </div>
                            <Badge variant="success" size="sm">
                              {formData.toVaId ? 'Direct' : 'VIBAN'}
                            </Badge>
                          </div>
                        </div>
                      )}
                    </div>
                  </div>
                </div>
              </Card>
            )}

            {step === 2 && transferType === 'outward' && (
              <Card className="animate-fade-in">
                <h3 className="section-title mb-6">Beneficiary Details</h3>
                <div className="space-y-4">
                  {/* Beneficiary Picker from Parties - Primary Selection */}
                  <div className="p-4 rounded-xl bg-gradient-to-br from-primary-50/50 to-white border border-primary-100 dark:border-primary-700/60">
                    <div className="flex items-center gap-3 mb-4">
                      <StatusIconBadge tone="primary" icon={UserCheck} className="dark:bg-primary-700" />
                      <div>
                        <p className="font-semibold text-primary-900 dark:text-neutral-50">Select Beneficiary</p>
                        <p className="text-xs text-neutral-500 dark:text-neutral-400">Choose from registered parties ({beneficiaries.length} available)</p>
                      </div>
                    </div>

                    {/* Beneficiary Dropdown Selector */}
                    <div className="space-y-3">
                      <div className="relative">
                        <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400 z-10 dark:text-neutral-500" />
                        <select
                          value={formData.selectedBeneficiaryId}
                          onChange={(e) => {
                            if (e.target.value) {
                              handleBeneficiarySelect(e.target.value);
                            } else {
                              setFormData(prev => ({
                                ...prev,
                                selectedBeneficiaryId: '',
                                selectedBankAccountId: '',
                                creditorName: '',
                                creditorAccount: '',
                                creditorBic: '',
                                creditorBankName: '',
                              }));
                            }
                          }}
                          className="w-full pl-10 pr-4 py-3 bg-white border border-primary-200 rounded-xl text-sm font-medium focus:ring-2 focus:ring-primary-500 focus:border-primary-500 transition-all appearance-none cursor-pointer dark:bg-primary-900 dark:border-primary-700"
                        >
                          <option value="">-- Select a beneficiary --</option>
                          {filteredBeneficiaries.map((b) => (
                            <option key={b.party.id} value={b.party.id}>
                              {b.party.legalName} ({b.party.partyCode}) - {b.bankAccounts.length} account{b.bankAccounts.length !== 1 ? 's' : ''}
                            </option>
                          ))}
                        </select>
                        <ChevronRight className="absolute right-3 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400 rotate-90 dark:text-neutral-500" />
                      </div>

                      {/* Search filter for long lists */}
                      {beneficiaries.length > 5 && (
                        <Input
                          value={beneficiarySearch}
                          onChange={(e) => setBeneficiarySearch(e.target.value)}
                          placeholder="Filter beneficiaries..."
                          className="text-sm"
                        />
                      )}
                    </div>

                    {/* Selected Beneficiary Card */}
                    {formData.selectedBeneficiaryId && (() => {
                      const selectedBeneficiary = beneficiaries.find(b => b.party.id === formData.selectedBeneficiaryId);
                      if (!selectedBeneficiary) return null;
                      return (
                        <div className="mt-4 p-4 bg-white rounded-xl border border-primary-200 shadow-sm dark:bg-primary-900 dark:border-primary-700">
                          <div className="flex items-start justify-between mb-3">
                            <div className="flex items-center gap-3">
                              <StatusIconBadge tone="success" icon={Building2} className="dark:bg-success-500/20" />
                              <div>
                                <p className="font-semibold text-primary-900 dark:text-neutral-50">{selectedBeneficiary.party.legalName}</p>
                                <p className="text-xs text-neutral-500 font-mono dark:text-neutral-400">{selectedBeneficiary.party.partyCode}</p>
                              </div>
                            </div>
                            <Badge variant="success" size="sm" dot>Selected</Badge>
                          </div>

                          {/* Bank Account Selection */}
                          {selectedBeneficiary.bankAccounts.length > 0 && (
                            <div className="pt-3 border-t border-neutral-100 dark:border-primary-800/60">
                              <p className="text-xs font-medium text-neutral-600 uppercase tracking-wider mb-2 dark:text-neutral-300">
                                Bank Account {selectedBeneficiary.bankAccounts.length > 1 ? '(Select One)' : ''}
                              </p>
                              <div className="space-y-2">
                                {selectedBeneficiary.bankAccounts.map((ba) => (
                                  <div
                                    key={ba.id}
                                    onClick={() => handleBeneficiarySelect(selectedBeneficiary.party.id, ba.id)}
                                    className={cn(
                                      'p-3 rounded-lg cursor-pointer transition-all border-2',
                                      formData.selectedBankAccountId === ba.id
                                        ? 'bg-primary-50 border-primary-400 shadow-sm dark:bg-primary-800/40'
                                        : 'bg-neutral-50 border-transparent hover:bg-neutral-100 hover:border-neutral-200 dark:bg-primary-950 dark:hover:bg-primary-800'
                                    )}
                                  >
                                    <div className="flex items-center justify-between">
                                      <div className="flex items-center gap-2">
                                        <div className={cn(
                                          'w-4 h-4 rounded-full border-2 flex items-center justify-center transition-all',
                                          formData.selectedBankAccountId === ba.id
                                            ? 'border-primary-500 bg-primary-500'
                                            : 'border-neutral-300 dark:border-primary-700'
                                        )}>
                                          {formData.selectedBankAccountId === ba.id && (
                                            <div className="w-1.5 h-1.5 rounded-full bg-white dark:bg-primary-900" />
                                          )}
                                        </div>
                                        <span className="font-medium text-sm text-primary-900 dark:text-neutral-50">{ba.bankName}</span>
                                        {ba.isPrimary && <Badge variant="info" size="sm">Primary</Badge>}
                                      </div>
                                      <span className="text-xs font-medium text-neutral-500 dark:text-neutral-400">{ba.currency}</span>
                                    </div>
                                    <p className="text-xs text-neutral-600 font-mono mt-1 ml-6 dark:text-neutral-300">
                                      {ba.iban || ba.accountNumber}
                                    </p>
                                    {ba.bankCode && (
                                      <p className="text-xs text-neutral-500 mt-0.5 ml-6 dark:text-neutral-400">
                                        BIC: {ba.bankCode}
                                      </p>
                                    )}
                                  </div>
                                ))}
                              </div>
                            </div>
                          )}

                          {selectedBeneficiary.bankAccounts.length === 0 && (
                            <div className="mt-3 p-3 bg-warning-50 rounded-lg border border-warning-200 dark:bg-warning-500/10 dark:border-warning-500/30">
                              <p className="text-xs text-warning-700 dark:text-warning-300">
                                This party has no bank accounts registered. Please add bank account details below.
                              </p>
                            </div>
                          )}
                        </div>
                      );
                    })()}
                  </div>

                  {/* Manual Entry Section - Collapsed by default if beneficiary selected */}
                  <details className={cn(
                    "group rounded-xl border transition-all",
                    formData.selectedBeneficiaryId
                      ? "bg-neutral-50 border-neutral-200 dark:bg-primary-950 dark:border-primary-800"
                      : "bg-white border-primary-200 dark:bg-primary-900 dark:border-primary-700"
                  )} open={!formData.selectedBeneficiaryId}>
                    <summary className="p-4 cursor-pointer list-none flex items-center justify-between">
                      <div className="flex items-center gap-2">
                        <FileCode className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
                        <span className="field-label">
                          {formData.selectedBeneficiaryId ? 'Override with manual entry' : 'Or enter details manually'}
                        </span>
                      </div>
                      <ChevronRight className="w-4 h-4 text-neutral-400 transition-transform group-open:rotate-90 dark:text-neutral-500" />
                    </summary>
                    <div className="px-4 pb-4 space-y-4">
                      <Input
                        label="Beneficiary Name"
                        value={formData.creditorName}
                        onChange={(e) => setFormData({ ...formData, creditorName: e.target.value, selectedBeneficiaryId: '' })}
                        placeholder="Enter beneficiary name"
                      />
                      <Input
                        label="Account Number / IBAN"
                        value={formData.creditorAccount}
                        onChange={(e) => setFormData({ ...formData, creditorAccount: e.target.value, selectedBeneficiaryId: '' })}
                        placeholder="AE07 0331 2345 6789 0123 456"
                      />
                      <div className="grid grid-cols-2 gap-4">
                        <Input
                          label="BIC / SWIFT Code"
                          value={formData.creditorBic}
                          onChange={(e) => setFormData({ ...formData, creditorBic: e.target.value })}
                          placeholder="ADCBAEAA"
                          hint="Optional"
                        />
                        <Input
                          label="Bank Name"
                          value={formData.creditorBankName}
                          onChange={(e) => setFormData({ ...formData, creditorBankName: e.target.value })}
                          placeholder="Bank name"
                          hint="Optional"
                        />
                      </div>
                    </div>
                  </details>

                  {/* POBO Toggle - Only shown for IHB participants (subsidiaries that can borrow) */}
                  {/* Hidden for Treasury Centers and non-IHB entities */}
                  {shouldShowPoboToggle() && (
                    <Card className={cn(
                      'mt-6 transition-all',
                      formData.isPobo ? 'bg-warning-50/50 border-warning-200 dark:border-warning-500/30' : 'bg-neutral-50 border-neutral-200 dark:bg-primary-950 dark:border-primary-800'
                    )}>
                      <label className="flex items-start gap-4 cursor-pointer">
                        <div className={cn(
                          'w-6 h-6 rounded-lg border-2 flex items-center justify-center mt-0.5 transition-all',
                          formData.isPobo
                            ? 'bg-warning-500 border-warning-500'
                            : 'border-neutral-300 hover:border-warning-400 dark:border-primary-700'
                        )}>
                          {formData.isPobo && <CheckCircle className="w-4 h-4 text-white" />}
                        </div>
                        <div className="flex-1" onClick={() => setFormData({ ...formData, isPobo: !formData.isPobo })}>
                          <p className="font-semibold text-primary-900 dark:text-neutral-50">Pay On Behalf Of (POBO)</p>
                          <p className="text-sm text-neutral-500 dark:text-neutral-400">Make payment on behalf of a subsidiary entity through Treasury Center</p>
                        </div>
                      </label>
                      {formData.isPobo && (
                        <div className="mt-4 pt-4 border-t border-warning-200 space-y-4 animate-fade-in dark:border-warning-500/30">
                          <Input
                            label="On Behalf Of Entity"
                            value={formData.behalfOfEntity}
                            onChange={(e) => setFormData({ ...formData, behalfOfEntity: e.target.value })}
                            placeholder="Subsidiary entity name"
                          />
                          <Select
                            label="Subsidiary VA"
                            value={formData.behalfOfVaId}
                            onChange={(e) => setFormData({ ...formData, behalfOfVaId: e.target.value })}
                            options={accounts.map(acc => ({
                              value: acc.id,
                              label: `${acc.vaNumber} - ${acc.vaName}`
                            }))}
                            placeholder="Select subsidiary VA..."
                          />
                        </div>
                      )}
                    </Card>
                  )}
                </div>
              </Card>
            )}

            {step === 2 && transferType === 'bulk' && (
              <Card className="animate-fade-in">
                <div className="flex items-center justify-between mb-6">
                  <div>
                    <h3 className="section-title">Transfer Recipients</h3>
                    <p className="text-sm text-neutral-500 dark:text-neutral-400">Add multiple recipients for bulk transfer</p>
                  </div>
                  <Button size="sm" leftIcon={<Plus className="w-4 h-4" />} onClick={addBulkItem}>
                    Add Recipient
                  </Button>
                </div>

                {bulkItems.length === 0 ? (
                  <div className="text-center py-12 bg-neutral-50 rounded-xl dark:bg-primary-950">
                    <div className="w-16 h-16 rounded-2xl bg-neutral-100 flex items-center justify-center mx-auto mb-4 dark:bg-primary-800">
                      <Users className="w-8 h-8 text-neutral-300 dark:text-neutral-600" />
                    </div>
                    <p className="text-neutral-500 dark:text-neutral-400">No recipients added yet</p>
                    <p className="text-sm text-neutral-400 mt-1 dark:text-neutral-500">Click "Add Recipient" to start</p>
                  </div>
                ) : (
                  <div className="space-y-3">
                    {bulkItems.map((item, index) => (
                      <Card key={item.id} padding="sm" className="bg-neutral-50 dark:bg-primary-950">
                        <div className="flex items-center justify-between mb-3">
                          <Badge variant="neutral" size="sm">Recipient #{index + 1}</Badge>
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => removeBulkItem(item.id)}
                          >
                            <Trash2 className="w-4 h-4 text-error-500" />
                          </Button>
                        </div>
                        <div className="grid grid-cols-4 gap-3">
                          <Select
                            value={item.destinationVaId}
                            onChange={(e) => updateBulkItem(item.id, 'destinationVaId', e.target.value)}
                            options={accounts
                              .filter(acc => acc.id !== formData.fromVaId)
                              .map(acc => ({
                                value: acc.id,
                                label: `${acc.vaNumber} - ${acc.vaName}`
                              }))}
                            placeholder="Select account..."
                          />
                          <Input
                            type="number"
                            value={item.amount}
                            onChange={(e) => updateBulkItem(item.id, 'amount', e.target.value)}
                            placeholder="Amount"
                          />
                          <Input
                            type="date"
                            value={item.valueDate}
                            onChange={(e) => updateBulkItem(item.id, 'valueDate', e.target.value)}
                          />
                          <Input
                            value={item.description}
                            onChange={(e) => updateBulkItem(item.id, 'description', e.target.value)}
                            placeholder="Description"
                          />
                        </div>
                      </Card>
                    ))}

                    <Card className="bg-gradient-to-br from-primary-50 to-white border-primary-200 dark:border-primary-700 dark:from-primary-500/10 dark:to-primary-900 dark:from-primary-800/40">
                      <div className="flex items-center justify-between">
                        <div>
                          <p className="text-sm text-primary-600 dark:text-primary-200">Total Amount</p>
                          <p className="text-xs text-neutral-500 dark:text-neutral-400">{bulkItems.length} recipient(s)</p>
                        </div>
                        <p className="stat-value-sm">
                          {formatCurrency(bulkTotal, formData.currency)}
                        </p>
                      </div>
                    </Card>
                  </div>
                )}
              </Card>
            )}

            {/* Step 3: Amount (for internal/outward) */}
            {step === 3 && transferType !== 'bulk' && (
              <Card className="animate-fade-in">
                <h3 className="section-title mb-6">Transfer Amount</h3>
                <div className="grid grid-cols-3 gap-4">
                  <Input
                    label="Amount"
                    type="number"
                    value={formData.amount}
                    onChange={(e) => setFormData({ ...formData, amount: e.target.value })}
                    placeholder="0.00"
                  />
                  <Select
                    label="Currency"
                    value={formData.currency}
                    onChange={(e) => setFormData({ ...formData, currency: e.target.value })}
                    options={[
                      { value: 'AED', label: 'AED - UAE Dirham' },
                      { value: 'USD', label: 'USD - US Dollar' },
                      { value: 'EUR', label: 'EUR - Euro' },
                      { value: 'GBP', label: 'GBP - British Pound' },
                    ]}
                  />
                  <Input
                    label="Value Date"
                    type="date"
                    value={formData.valueDate}
                    onChange={(e) => setFormData({ ...formData, valueDate: e.target.value })}
                  />
                </div>
                <div className="mt-4">
                  <Input
                    label={transferType === 'outward' ? 'Remittance Information' : 'Description'}
                    value={transferType === 'outward' ? formData.remittanceInfo : formData.description}
                    onChange={(e) => setFormData({
                      ...formData,
                      [transferType === 'outward' ? 'remittanceInfo' : 'description']: e.target.value
                    })}
                    placeholder="Payment reference or description"
                    hint="Optional"
                  />
                </div>
              </Card>
            )}

            {/* Navigation - simplified since submit is in sidebar */}
            <div className="flex justify-between pt-4 border-t border-neutral-200 animate-fade-in dark:border-primary-800">
              <Button
                variant="outline"
                onClick={() => step > 1 ? setStep(step - 1) : resetForm()}
              >
                {step > 1 ? 'Back' : 'Cancel'}
              </Button>
              {step < maxSteps && (
                <Button
                  onClick={() => setStep(step + 1)}
                  disabled={!canProceed()}
                  rightIcon={<ChevronRight className="w-4 h-4" />}
                >
                  Continue
                </Button>
              )}
              {step === maxSteps && (
                <p className="text-sm text-neutral-500 flex items-center gap-2 dark:text-neutral-400">
                  <CheckCircle className="w-4 h-4 text-success-500" />
                  Review details in the sidebar and click Submit
                </p>
              )}
            </div>
          </div>

          {/* Preview Panel - Enhanced Summary Sidebar */}
          <div className="w-80 flex-shrink-0">
            <TransferSummary
              transferType={transferType}
              formData={formData}
              sourceAccount={sourceAccount}
              targetAccount={targetAccount}
              accounts={accounts}
              bulkItems={bulkItems}
              bulkTotal={bulkTotal}
              canSubmit={canProceed() && (
                transferType === 'inward' ? !!formData.amount && parseFloat(formData.amount) > 0 :
                transferType === 'bulk' ? bulkItems.length > 0 :
                !!formData.amount && parseFloat(formData.amount) > 0
              )}
              submitting={submitting}
              onSubmit={handleSubmit}
            />
          </div>
        </div>
      )}

      {/* History Tab */}
      {activeTab === 'history' && (
        <Card className="animate-fade-in" style={{ animationDelay: '0.35s' }}>
          <div className="flex items-center justify-between mb-6">
            <div>
              <h3 className="section-title">Recent Transfers</h3>
              <p className="text-sm text-neutral-500 dark:text-neutral-400">Your transfer history</p>
            </div>
            <Button variant="outline" size="sm" leftIcon={<Download className="w-4 h-4" />}>
              Export
            </Button>
          </div>

          {recentTransfers.length === 0 ? (
            <div className="text-center py-16">
              <div className="w-16 h-16 rounded-2xl bg-neutral-100 flex items-center justify-center mx-auto mb-4 dark:bg-primary-800">
                <Clock className="w-8 h-8 text-neutral-300 dark:text-neutral-600" />
              </div>
              <p className="text-neutral-500 dark:text-neutral-400">No transfer history found</p>
              <p className="text-sm text-neutral-400 mt-1 dark:text-neutral-500">Your transfers will appear here</p>
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full">
                <thead className="bg-neutral-50 border-b border-neutral-100 dark:bg-primary-950 dark:border-primary-800/60">
                  <tr>
                    <th className="text-left p-4 label">Reference</th>
                    <th className="text-left p-4 label">Type</th>
                    <th className="text-left p-4 label">From</th>
                    <th className="text-left p-4 label">To</th>
                    <th className="text-right p-4 label">Amount</th>
                    <th className="text-left p-4 label">Status</th>
                    <th className="text-left p-4 label">Date</th>
                    <th className="text-center p-4 label">Actions</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-neutral-100 dark:divide-primary-800/60">
                  {recentTransfers.map((txn) => {
                    // Determine transfer type based on movement type
                    const getTransferType = () => {
                      switch (txn.movementType) {
                        case 'TRANSFER_OUT':
                          return { label: 'Internal', variant: 'info' as const, icon: ArrowLeftRight };
                        case 'DEBIT':
                          return { label: 'Outward', variant: 'primary' as const, icon: ArrowUpRight };
                        case 'POBO_DEBIT':
                          return { label: 'POBO', variant: 'warning' as const, icon: Send };
                        case 'ROBO_CREDIT':
                          return { label: 'ROBO', variant: 'success' as const, icon: ArrowDownLeft };
                        case 'CREDIT':
                        case 'TRANSFER_IN':
                          return { label: 'Inward', variant: 'success' as const, icon: ArrowDownLeft };
                        case 'EXCEPTION_CREDIT':
                          return { label: 'Exception', variant: 'warning' as const, icon: AlertTriangle };
                        default:
                          return { label: txn.movementType || 'Transfer', variant: 'neutral' as const, icon: ArrowRight };
                      }
                    };
                    const transferType = getTransferType();

                    return (
                      <tr
                        key={txn.id}
                        className="hover:bg-neutral-50 transition-colors cursor-pointer dark:hover:bg-primary-800/50"
                        onClick={() => {
                          setSelectedTransfer(txn);
                          setShowDetailModal(true);
                        }}
                      >
                        <td className="p-4">
                          <p className="font-mono text-sm text-neutral-600 dark:text-neutral-300">{txn.referenceNumber}</p>
                        </td>
                        <td className="p-4">
                          <Badge variant={transferType.variant} size="sm">
                            <span className="inline-flex items-center gap-1">
                              <transferType.icon className="w-3 h-3" />
                              {transferType.label}
                            </span>
                          </Badge>
                        </td>
                        <td className="p-4">
                          <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{txn.vaName || txn.vaNumber}</p>
                        </td>
                        <td className="p-4">
                          <p className="text-sm text-neutral-600 dark:text-neutral-300">{txn.counterpartyName || txn.counterpartyAccount || '-'}</p>
                        </td>
                        <td className="p-4 text-right">
                          <p className="font-semibold text-primary-900 dark:text-neutral-50">
                            {formatCurrency(txn.amount, txn.currencyCode)}
                          </p>
                        </td>
                        <td className="p-4">
                          <Badge
                            variant={
                              txn.status === 'COMPLETED' ? 'success' :
                              txn.status === 'PENDING' ? 'warning' :
                              txn.status === 'FAILED' ? 'error' : 'neutral'
                            }
                            size="sm"
                            dot
                          >
                            {txn.status}
                          </Badge>
                        </td>
                        <td className="p-4">
                          <p className="text-sm text-neutral-500 dark:text-neutral-400">{formatRelativeTime(txn.transactionDate)}</p>
                        </td>
                        <td className="p-4">
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={(e) => {
                              e.stopPropagation();
                              setSelectedTransfer(txn);
                              setShowDetailModal(true);
                            }}
                          >
                            <Eye className="w-4 h-4" />
                          </Button>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      )}

      {/* Result Modal */}
      <ResultModal
        isOpen={showResultModal}
        onClose={() => setShowResultModal(false)}
        result={lastResult}
        hasXml={!!xmlContent}
        onViewXml={() => {
          setShowResultModal(false);
          setShowXmlModal(true);
        }}
      />

      {/* XML Viewer Modal */}
      <XmlViewerModal
        isOpen={showXmlModal}
        onClose={() => setShowXmlModal(false)}
        xml={xmlContent}
      />

      {/* Transfer Detail Modal */}
      <TransferDetailModal
        isOpen={showDetailModal}
        onClose={() => {
          setShowDetailModal(false);
          setSelectedTransfer(null);
        }}
        transaction={selectedTransfer}
      />
    </Page>
  );
}