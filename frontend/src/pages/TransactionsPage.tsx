import React, { useState, useEffect, useCallback } from 'react';
import { Search, Download, Eye, ArrowUpRight, ArrowDownLeft, ArrowLeftRight, RefreshCw, Calendar, Clock, CheckCircle, XCircle, Plus, Send, FileText, Building2, Landmark, CreditCard, Hash, FileCode, Info, Layers, List, DollarSign, Users, TrendingUp, TrendingDown, Loader2 } from 'lucide-react';
import { Card, Button, Badge, Input, Select, StatusIconBadge, StatTile, DataTable } from '../components/ui';
import type { Column } from '../components/ui';
import { Modal } from '../components/ui/enhanced';
import { formatCurrency, formatDate, formatRelativeTime, getStatusVariant, cn } from '../utils';
import toast from 'react-hot-toast';
import {
  transactionsApi,
  virtualAccountsApi,
  vibanApi,
  corporatesApi,
  type Transaction,
  type TransactionStats,
  type GroupedTransaction,
} from '../services/api';
import { isCredit, isDebit, getAmountColorClass, getMovementBgClass } from '../utils/transactionUtils';
import { Page } from '../components/layout/Page';
import { ScopeSelector, type ScopeCorporate } from '../components/layout/ScopeSelector';

// ============================================================================
// DEMO DATA (Fallback)
// ============================================================================

const demoTransactions: Transaction[] = [
  {
    id: '1',
    referenceNumber: 'TXN-2024-001234',
    movementType: 'CREDIT',
    amount: 125000,
    currencyCode: 'AED',
    vaId: 'va1',
    vaNumber: 'VA001001',
    vaName: 'Operating Account',
    counterpartyName: 'Emirates Trading LLC',
    counterpartyAccount: 'AE070331234567890123456',
    description: 'Invoice Payment - INV-2024-0156',
    channel: 'SWIFT',
    status: 'COMPLETED',
    transactionDate: new Date().toISOString(),
    valueDate: new Date().toISOString().split('T')[0],
    balanceBefore: 5125000,
    balanceAfter: 5250000,
    createdAt: new Date().toISOString(),
  },
  {
    id: '2',
    referenceNumber: 'TXN-2024-001235',
    movementType: 'DEBIT',
    amount: 85000,
    currencyCode: 'AED',
    vaId: 'va2',
    vaNumber: 'VA001002',
    vaName: 'Payroll Account',
    counterpartyName: 'Staff Salaries - Feb 2024',
    description: 'Monthly Salary Disbursement',
    channel: 'INTERNAL',
    status: 'COMPLETED',
    transactionDate: new Date().toISOString(),
    valueDate: new Date().toISOString().split('T')[0],
    balanceBefore: 3185000,
    balanceAfter: 3100000,
    createdAt: new Date().toISOString(),
  },
  {
    id: '3',
    referenceNumber: 'TXN-2024-001236',
    movementType: 'TRANSFER_OUT',
    amount: 500000,
    currencyCode: 'AED',
    vaId: 'va1',
    vaNumber: 'VA001001',
    vaName: 'Operating Account',
    counterpartyName: 'Treasury Account',
    counterpartyAccount: 'VA001005',
    description: 'Internal Treasury Transfer',
    channel: 'INTERNAL',
    status: 'PENDING',
    transactionDate: new Date().toISOString(),
    valueDate: new Date().toISOString().split('T')[0],
    balanceBefore: 5750000,
    balanceAfter: 5250000,
    createdAt: new Date().toISOString(),
  },
  {
    id: '4',
    referenceNumber: 'TXN-2024-001237',
    movementType: 'FEE_CREDIT',
    amount: 250,
    currencyCode: 'AED',
    vaId: 'va-settlement',
    vaNumber: 'SETTLEMENT-001',
    vaName: 'Settlement Account',
    counterpartyName: 'Fee from VA001001',
    counterpartyAccount: 'VA001001',
    description: 'Transfer Fee Credit',
    channel: 'INTERNAL',
    status: 'COMPLETED',
    transactionDate: new Date().toISOString(),
    valueDate: new Date().toISOString().split('T')[0],
    balanceBefore: 14000,
    balanceAfter: 14250,
    createdAt: new Date().toISOString(),
  },
  {
    id: '5',
    referenceNumber: 'TXN-2024-001238',
    movementType: 'FEE',
    amount: 250,
    currencyCode: 'AED',
    vaId: 'va1',
    vaNumber: 'VA001001',
    vaName: 'Operating Account',
    counterpartyName: 'Settlement Account',
    counterpartyAccount: 'SETTLEMENT-001',
    description: 'Transfer Fee Debit',
    channel: 'INTERNAL',
    status: 'COMPLETED',
    transactionDate: new Date().toISOString(),
    valueDate: new Date().toISOString().split('T')[0],
    balanceBefore: 5250000,
    balanceAfter: 5250000,
    createdAt: new Date().toISOString(),
  },
];

const demoStats: TransactionStats = {
  totalCredits: 375000,
  totalDebits: 760000,
  netFlow: -385000,
  todayCredits: 375000,
  todayDebits: 585000,
  todayNetFlow: -210000,
  totalCount: 6,
  todayCount: 4,
  pendingCount: 2,
  failedCount: 1,
  completedCount: 3,
  swiftCount: 2,
  rtgsCount: 1,
  internalCount: 3,
  averageAmount: 197500,
  largestTransaction: 500000,
};

// ============================================================================
// COMPONENTS
// ============================================================================

// Movement Type Icon - Uses utility functions for consistent styling
const MovementIcon: React.FC<{ type: string; className?: string }> = ({ type, className }) => {
  if (isCredit(type)) {
    return <ArrowDownLeft className={cn('w-5 h-5 text-success-600 dark:text-success-300', className)} />;
  }
  if (isDebit(type)) {
    return <ArrowUpRight className={cn('w-5 h-5 text-error-600 dark:text-error-300', className)} />;
  }
  // Default/unknown movement type
  return <ArrowLeftRight className={cn('w-5 h-5 text-neutral-500 dark:text-neutral-400', className)} />;
};

// Status Icon
const StatusIcon: React.FC<{ status: string }> = ({ status }) => {
  switch (status) {
    case 'COMPLETED':
      return <CheckCircle className="w-4 h-4 text-success-500 dark:text-success-300" />;
    case 'PENDING':
    case 'PROCESSING':
      return <Clock className="w-4 h-4 text-warning-500 dark:text-warning-300" />;
    case 'FAILED':
      return <XCircle className="w-4 h-4 text-error-500 dark:text-error-300" />;
    case 'REVERSED':
    case 'CANCELLED':
      return <XCircle className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />;
    default:
      return null;
  }
};

// Phase 12 Task E: local StatCard clone (tinted-gradient card + raw
// `text-heading-sm font-bold` value) replaced by the shared <StatTile layout="row">
// (components/ui/StatTile) — see the stats strip in the page body.


// ============================================================================
// GROUPED TRANSACTION COMPONENTS (Option B - Simplified Business View)
// ============================================================================

// Operation Type Badge
const OperationTypeBadge: React.FC<{ type: string }> = ({ type }) => {
  const config = {
    COLLECTION: { variant: 'success' as const, icon: TrendingUp, label: 'Collection' },
    PAYMENT: { variant: 'error' as const, icon: TrendingDown, label: 'Payment' },
    POBO_PAYMENT: { variant: 'warning' as const, icon: Users, label: 'POBO' },
    TRANSFER: { variant: 'info' as const, icon: ArrowLeftRight, label: 'Transfer' },
    FEE: { variant: 'neutral' as const, icon: DollarSign, label: 'Fee' },
    OTHER: { variant: 'neutral' as const, icon: ArrowLeftRight, label: 'Other' },
  };

  const cfg = config[type as keyof typeof config] || config.OTHER;
  const Icon = cfg.icon;

  return (
    <Badge variant={cfg.variant} size="sm">
      <Icon className="w-3 h-3 mr-1" />
      {cfg.label}
    </Badge>
  );
};

// Transaction Details Modal
interface TransactionDetailsProps {
  transaction: Transaction | null;
  onClose: () => void;
  onReverse?: (id: string) => void;
}

const TransactionDetails: React.FC<TransactionDetailsProps> = ({ transaction, onClose, onReverse }) => {
  const [activeTab, setActiveTab] = useState<'details' | 'accounting'>('details');
  const [groupedData, setGroupedData] = useState<GroupedTransaction | null>(null);
  const [loadingEntries, setLoadingEntries] = useState(false);
  // Off by default: bank-internal Shadow VA / Settlement VA legs are noise for
  // the corporate's day-to-day view. On demand only, for reconciliation/audit.
  const [showInternalAccounts, setShowInternalAccounts] = useState(false);

  // Fetch grouped transaction data when modal opens, transaction has correlationId,
  // or the "show internal accounts" toggle changes.
  useEffect(() => {
    const fetchGroupedData = async () => {
      if (!transaction?.correlationId) {
        setGroupedData(null);
        return;
      }

      setLoadingEntries(true);
      try {
        const response = await transactionsApi.getGroupedByCorrelationId(
          transaction.correlationId,
          transaction.vaId,
          showInternalAccounts
        );
        if (response.data) {
          setGroupedData(response.data);
        }
      } catch (err) {
        console.error('Failed to fetch grouped transaction data:', err);
        setGroupedData(null);
      } finally {
        setLoadingEntries(false);
      }
    };

    if (transaction) {
      fetchGroupedData();
    }
  }, [transaction, showInternalAccounts]);

  if (!transaction) return null;

  const isCreditTxn = isCredit(transaction.movementType);
  const hasAccountingEntries = !!transaction.correlationId;

  // Helper to get account type badge color
  const getAccountTypeBadgeClass = (accountType: string) => {
    switch (accountType) {
      case 'SHADOW':
      case 'PHYSICAL_MIRROR':
        return 'bg-cat-2/10 text-cat-2 dark:text-cat-2-fg dark:bg-cat-2/15';
      case 'SETTLEMENT':
        return 'bg-info-100 text-info-700 dark:bg-info-500/20 dark:text-info-300';
      case 'OPERATING':
        return 'bg-success-100 text-success-700 dark:bg-success-500/20 dark:text-success-300';
      case 'EXCEPTION':
        return 'bg-warning-100 text-warning-700 dark:bg-warning-500/20 dark:text-warning-300';
      default:
        return 'bg-neutral-100 text-neutral-700 dark:bg-primary-800 dark:text-neutral-200';
    }
  };

  // Helper to get movement type badge
  const getMovementTypeBadgeClass = (movementType: string) => {
    if (movementType.includes('CREDIT') || movementType === 'TRANSFER_IN' || movementType === 'ROBO_CREDIT') {
      return 'bg-cat-5/10 text-cat-5 dark:text-cat-5-fg dark:bg-cat-5/15';
    }
    if (movementType.includes('DEBIT') || movementType === 'TRANSFER_OUT' || movementType === 'POBO_DEBIT') {
      return 'bg-rose-100 text-rose-700 dark:bg-rose-500/20 dark:text-rose-300';
    }
    return 'bg-neutral-100 text-neutral-700 dark:bg-primary-800 dark:text-neutral-200';
  };

  return (
    <Modal
      isOpen={!!transaction}
      onClose={onClose}
      title="Transaction Details"
      size="lg"
    >
      <div className="p-6 space-y-6">
        {/* Header */}
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-4">
            <div className={cn(
              'w-14 h-14 rounded-lg flex items-center justify-center',
              getMovementBgClass(transaction.movementType)
            )}>
              <MovementIcon type={transaction.movementType} className="w-6 h-6" />
            </div>
            <div>
              <p className="section-title">{transaction.referenceNumber}</p>
              <p className="body-sm">{transaction.movementType.replace(/_/g, ' ')}</p>
            </div>
          </div>
          <Badge variant={getStatusVariant(transaction.status)} size="md">{transaction.status}</Badge>
        </div>

        {/* Amount */}
        <div className="bg-gradient-to-br from-neutral-50 to-white rounded-lg p-6 text-center border border-neutral-100 dark:border-primary-800/60 dark:from-primary-950 dark:to-primary-900">
          <p className="body-sm mb-2">Amount</p>
          {/* Phase 9.1 Task B: hero amount uses .stat-value (Fraunces 36px/600).
              The conditional tone class wins by cascade order — it sits in
              the @layer utilities while .stat-value's text-primary-900 sits
              in @layer base, so the dynamic color override is preserved. */}
          <p className={cn('stat-value', getAmountColorClass(transaction.movementType))}>
            {isCreditTxn ? '+' : '-'}{formatCurrency(transaction.amount, transaction.currencyCode)}
          </p>
        </div>

        {/* Tab Navigation - only show if fund movement detail is available */}
        {hasAccountingEntries && (
          <div className="flex gap-1 p-1 bg-neutral-100 rounded-lg dark:bg-primary-800">
            <button
              onClick={() => setActiveTab('details')}
              className={cn(
                'flex-1 px-4 py-2 rounded-md text-body-sm font-medium transition-all',
                activeTab === 'details'
                  ? 'bg-white text-neutral-900 shadow-sm dark:bg-primary-900 dark:text-neutral-50'
                  : 'text-neutral-600 hover:text-neutral-900 dark:text-neutral-300 dark:hover:text-neutral-50'
              )}
            >
              <FileText className="w-4 h-4 inline mr-2" />
              Details
            </button>
            <button
              onClick={() => setActiveTab('accounting')}
              className={cn(
                'flex-1 px-4 py-2 rounded-md text-body-sm font-medium transition-all',
                activeTab === 'accounting'
                  ? 'bg-white text-neutral-900 shadow-sm dark:bg-primary-900 dark:text-neutral-50'
                  : 'text-neutral-600 hover:text-neutral-900 dark:text-neutral-300 dark:hover:text-neutral-50'
              )}
            >
              <Layers className="w-4 h-4 inline mr-2" />
              Fund Movements
              {groupedData?.entryCount && (
                <span className="ml-1.5 px-1.5 py-0.5 text-caption bg-neutral-200 rounded-full dark:bg-primary-800">
                  {groupedData.entryCount}
                </span>
              )}
            </button>
          </div>
        )}

        {/* Details Tab Content */}
        {activeTab === 'details' && (
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div className="bg-neutral-50 rounded-lg p-4 dark:bg-primary-950">
              <p className="caption mb-1.5">Virtual Account</p>
              <p className="font-medium text-neutral-900 dark:text-neutral-50">{transaction.vaName}</p>
              <p className="text-body-sm text-neutral-500 font-mono mt-0.5 dark:text-neutral-400">{transaction.vaNumber}</p>
            </div>
            <div className="bg-neutral-50 rounded-lg p-4 dark:bg-primary-950">
              <p className="caption mb-1.5">Counterparty</p>
              <p className="font-medium text-neutral-900 dark:text-neutral-50">{transaction.counterpartyName || '-'}</p>
              {transaction.counterpartyAccount && (
                <p className="text-body-sm text-neutral-500 font-mono mt-0.5 truncate dark:text-neutral-400">{transaction.counterpartyAccount}</p>
              )}
            </div>
            <div className="bg-neutral-50 rounded-lg p-4 dark:bg-primary-950">
              <p className="caption mb-1.5">Transaction Date</p>
              <p className="font-medium text-neutral-900 dark:text-neutral-50">{formatDate(transaction.transactionDate)}</p>
            </div>
            <div className="bg-neutral-50 rounded-lg p-4 dark:bg-primary-950">
              <p className="caption mb-1.5">Value Date</p>
              <p className="font-medium text-neutral-900 dark:text-neutral-50">{transaction.valueDate ? formatDate(transaction.valueDate) : '-'}</p>
            </div>
            <div className="bg-neutral-50 rounded-lg p-4 dark:bg-primary-950">
              <p className="caption mb-1.5">Balance Before</p>
              <p className="font-medium text-neutral-900 dark:text-neutral-50">{formatCurrency(transaction.balanceBefore, transaction.currencyCode)}</p>
            </div>
            <div className="bg-neutral-50 rounded-lg p-4 dark:bg-primary-950">
              <p className="caption mb-1.5">Balance After</p>
              <p className="font-medium text-neutral-900 dark:text-neutral-50">{formatCurrency(transaction.balanceAfter, transaction.currencyCode)}</p>
            </div>
            <div className="sm:col-span-2 bg-neutral-50 rounded-lg p-4 dark:bg-primary-950">
              <p className="caption mb-1.5">Description</p>
              <p className="font-medium text-neutral-900 dark:text-neutral-50">{transaction.description || '-'}</p>
            </div>
            <div className="bg-neutral-50 rounded-lg p-4 dark:bg-primary-950">
              <p className="caption mb-1.5">Channel</p>
              <Badge variant="neutral">{transaction.channel || 'N/A'}</Badge>
            </div>
            {transaction.externalReference && (
              <div className="bg-neutral-50 rounded-lg p-4 dark:bg-primary-950">
                <p className="caption mb-1.5">External Reference</p>
                <p className="font-medium text-neutral-900 font-mono text-body-sm dark:text-neutral-50">{transaction.externalReference}</p>
              </div>
            )}
            {transaction.correlationId && (
              <div className="bg-neutral-50 rounded-lg p-4 dark:bg-primary-950">
                <p className="caption mb-1.5">Correlation ID</p>
                <p className="font-medium text-neutral-900 font-mono text-caption truncate dark:text-neutral-50">{transaction.correlationId}</p>
              </div>
            )}
          </div>
        )}

        {/* Fund Movements Tab Content */}
        {activeTab === 'accounting' && (
          <div className="space-y-4">
            {/* Show internal accounts toggle — off by default; bank-internal
                Shadow VA / Settlement VA legs are noise for day-to-day use,
                but reconciliation/audit needs the full picture on demand. */}
            <div className="flex items-center justify-between p-3 bg-neutral-50 rounded-lg dark:bg-primary-950">
              <span className="body-sm">Show internal settlement accounts</span>
              <label className="relative inline-flex items-center cursor-pointer">
                <input
                  type="checkbox"
                  checked={showInternalAccounts}
                  onChange={(e) => setShowInternalAccounts(e.target.checked)}
                  className="sr-only peer"
                />
                <div className="w-9 h-5 bg-neutral-200 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:bg-info-600 after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-4 after:w-4 after:transition-all dark:bg-primary-800" />
              </label>
            </div>

            {loadingEntries ? (
              <div className="space-y-3">
                {[1, 2, 3, 4].map((i) => (
                  <div key={i} className="bg-neutral-50 rounded-lg p-4 animate-pulse dark:bg-primary-950">
                    <div className="h-4 bg-neutral-200 rounded-md w-1/4 mb-2 dark:bg-primary-800" />
                    <div className="h-3 bg-neutral-200 rounded-md w-3/4 dark:bg-primary-800" />
                  </div>
                ))}
              </div>
            ) : (
              <>
                {/* Fee Applied — shown whenever a fee exists, even when the per-leg
                    entries below are empty (e.g. a fee settled entirely through
                    internal Shadow/Settlement VAs). The fee is a real cost to the
                    corporate regardless of whether any of its operating VAs were
                    touched directly. */}
                {!!groupedData?.feeAmount && groupedData.feeAmount > 0 && (
                  <div className="bg-warning-50 rounded-lg p-4 border border-warning-100 dark:bg-warning-500/10 dark:border-warning-500/30">
                    <div className="flex items-center justify-between">
                      <span className="text-body-sm text-warning-800 dark:text-warning-300">Fee Applied</span>
                      <span className="font-bold text-warning-900">
                        {formatCurrency(groupedData.feeAmount, groupedData.currencyCode)}
                      </span>
                    </div>
                  </div>
                )}

                {groupedData?.accountingEntries && groupedData.accountingEntries.length > 0 ? (
              <>
                {/* Summary Header */}
                <div className="bg-gradient-to-r from-info-50 to-cat-1-soft rounded-lg p-4 border border-info-100 dark:border-info-500/30 dark:from-info-500/15 dark:to-cat-1/15">
                  <div className="flex items-center justify-between mb-3">
                    <div className="flex items-center gap-2">
                      <Info className="w-4 h-4 text-info-600 dark:text-info-300" />
                      <span className="text-body-sm font-medium text-info-900">
                        Fund Movement • {groupedData.operationType.replace(/_/g, ' ')}
                      </span>
                    </div>
                    <Badge variant={groupedData.direction === 'INBOUND' ? 'success' : groupedData.direction === 'OUTBOUND' ? 'error' : 'neutral'}>
                      {groupedData.direction}
                    </Badge>
                  </div>
                  <p className="text-caption text-info-700 dark:text-info-300">
                    This transaction involved {groupedData.entryCount} fund {groupedData.entryCount === 1 ? 'movement' : 'movements'} across your account structure.
                  </p>
                </div>

                {/* Fund Movements List */}
                <div className="space-y-3">
                  {groupedData.accountingEntries.map((entry) => {
                    const entryIsCredit = entry.movementType.includes('CREDIT') ||
                                         entry.movementType === 'TRANSFER_IN' ||
                                         entry.movementType === 'ROBO_CREDIT';
                    return (
                      <div
                        key={entry.transactionId}
                        className={cn(
                          'rounded-lg p-4 border transition-all',
                          entry.transactionId === transaction.id
                            ? 'bg-info-50 border-info-200 ring-2 ring-info-100 dark:bg-info-500/10 dark:border-info-500/30'
                            : 'bg-neutral-50 border-neutral-100 hover:border-neutral-200 dark:bg-primary-950 dark:border-primary-800/60'
                        )}
                      >
                        <div className="flex items-start justify-between gap-4">
                          {/* Left: Leg info */}
                          <div className="flex items-start gap-3">
                            <div className={cn(
                              'w-8 h-8 rounded-lg flex items-center justify-center text-body-sm font-bold',
                              entryIsCredit ? 'bg-cat-5/10 text-cat-5 dark:text-cat-5-fg dark:bg-cat-5/15' : 'bg-rose-100 text-rose-700 dark:bg-rose-500/20 dark:text-rose-300'
                            )}>
                              {entry.legNumber}
                            </div>
                            <div>
                              <div className="flex items-center gap-2 mb-1">
                                <span className="font-medium text-neutral-900 dark:text-neutral-50">{entry.vaName}</span>
                                {entry.transactionId === transaction.id && (
                                  <span className="text-caption px-1.5 py-0.5 bg-info-200 dark:bg-info-500/15 text-info-800 rounded-md dark:text-info-300">
                                    Current
                                  </span>
                                )}
                              </div>
                              <p className="text-body-sm text-neutral-500 font-mono dark:text-neutral-400">{entry.vaNumber}</p>
                              <div className="flex items-center gap-2 mt-2">
                                <span className={cn(
                                  'text-caption px-2 py-0.5 rounded-full font-medium',
                                  getAccountTypeBadgeClass(entry.accountType)
                                )}>
                                  {entry.accountType.replace(/_/g, ' ')}
                                </span>
                                <span className={cn(
                                  'text-caption px-2 py-0.5 rounded-full font-medium',
                                  getMovementTypeBadgeClass(entry.movementType)
                                )}>
                                  {entry.movementType.replace(/_/g, ' ')}
                                </span>
                              </div>
                            </div>
                          </div>

                          {/* Right: Amount and balance */}
                          <div className="text-right">
                            <p className={cn(
                              'text-body-lg font-bold',
                              entryIsCredit ? 'text-cat-5 dark:text-cat-5-fg' : 'text-rose-600 dark:text-rose-300'
                            )}>
                              {entryIsCredit ? '+' : '-'}{formatCurrency(entry.amount, entry.currencyCode)}
                            </p>
                            <div className="caption mt-1 space-y-0.5">
                              <div className="flex items-center justify-end gap-1">
                                <span>Before:</span>
                                <span className="amount">{formatCurrency(entry.balanceBefore, entry.currencyCode)}</span>
                              </div>
                              <div className="flex items-center justify-end gap-1">
                                <span>After:</span>
                                <span className="amount">{formatCurrency(entry.balanceAfter, entry.currencyCode)}</span>
                              </div>
                            </div>
                          </div>
                        </div>

                        {/* Description if available */}
                        {entry.description && (
                          <p className="caption mt-3 pt-3 border-t border-neutral-200 dark:border-primary-800">
                            {entry.description}
                          </p>
                        )}
                      </div>
                    );
                  })}
                </div>
              </>
            ) : (
              <div className="text-center py-8 text-neutral-500 dark:text-neutral-400">
                <Layers className="w-12 h-12 mx-auto mb-3 text-neutral-300 dark:text-neutral-400" />
                {!!groupedData?.feeAmount && groupedData.feeAmount > 0 ? (
                  <>
                    <p className="font-medium">No movements on your accounts</p>
                    <p className="text-body-sm mt-1">
                      {showInternalAccounts
                        ? 'This fee was processed entirely through internal settlement accounts.'
                        : 'This fee was processed through internal settlement accounts — switch on "Show internal settlement accounts" above to see them.'}
                    </p>
                  </>
                ) : (
                  <>
                    <p className="font-medium">No fund movements to show</p>
                    <p className="text-body-sm mt-1">
                      {showInternalAccounts
                        ? "This transaction didn't involve any other fund movements."
                        : 'This transaction only moved funds through internal settlement accounts — switch on "Show internal settlement accounts" above to see them.'}
                    </p>
                  </>
                )}
              </div>
            )}
              </>
            )}
          </div>
        )}

        {/* Actions */}
        <div className="flex flex-col sm:flex-row justify-end gap-3 pt-4 border-t border-neutral-200 dark:border-primary-800">
          {transaction.status === 'COMPLETED' && onReverse && (
            <Button
              variant="outline"
              leftIcon={<RefreshCw className="w-4 h-4" />}
              onClick={() => onReverse(transaction.id)}
            >
              Reverse
            </Button>
          )}
          <Button variant="outline" leftIcon={<Download className="w-4 h-4" />}>
            Download Receipt
          </Button>
          <Button variant="ghost" onClick={onClose}>
            Close
          </Button>
        </div>
      </div>
    </Modal>
  );
};

// New Transaction Modal
interface NewTransactionModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSubmit: (type: 'credit' | 'debit' | 'transfer', data: any) => Promise<void>;
}

const NewTransactionModal: React.FC<NewTransactionModalProps> = ({ isOpen, onClose, onSubmit }) => {
  const [type, setType] = useState<'credit' | 'debit' | 'transfer'>('transfer');
  const [loading, setLoading] = useState(false);
  const [formData, setFormData] = useState({
    vaId: '',
    fromVaId: '',
    toVaId: '',
    amount: '',
    description: '',
    channel: 'INTERNAL',
    counterpartyName: '',
    counterpartyAccount: '',
  });

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    try {
      await onSubmit(type, formData);
      onClose();
      setFormData({
        vaId: '', fromVaId: '', toVaId: '', amount: '', description: '',
        channel: 'INTERNAL', counterpartyName: '', counterpartyAccount: '',
      });
    } finally {
      setLoading(false);
    }
  };

  const channelOptions = [
    { value: 'INTERNAL', label: 'Internal' },
    { value: 'SWIFT', label: 'SWIFT' },
    { value: 'RTGS', label: 'RTGS' },
    { value: 'DIRECT_DEBIT', label: 'Direct Debit' },
  ];

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="New Transaction" size="md">
      <form onSubmit={handleSubmit} className="p-6 space-y-6">
        {/* Transaction Type */}
        <div>
          <label className="field-label block mb-3">Transaction Type</label>
          <div className="grid grid-cols-3 gap-2">
            {(['transfer', 'credit', 'debit'] as const).map((t) => (
              <button
                key={t}
                type="button"
                className={cn(
                  'px-4 py-3 rounded-lg text-body-sm font-medium transition-all duration-200',
                  type === t
                    ? 'bg-primary-100 text-primary-900 border-2 border-primary-500 shadow-sm dark:bg-primary-700 dark:text-neutral-50'
                    : 'bg-neutral-100 text-neutral-600 border-2 border-transparent hover:bg-neutral-200 dark:hover:bg-primary-700 dark:bg-primary-800 dark:text-neutral-300'
                )}
                onClick={() => setType(t)}
              >
                {t.charAt(0).toUpperCase() + t.slice(1)}
              </button>
            ))}
          </div>
        </div>

        {/* Fields based on type */}
        {type === 'transfer' ? (
          <>
            <Input
              label="From Account (VA ID)"
              value={formData.fromVaId}
              onChange={(e) => setFormData({ ...formData, fromVaId: e.target.value })}
              placeholder="Enter source VA ID"
              required
            />
            <Input
              label="To Account (VA ID)"
              value={formData.toVaId}
              onChange={(e) => setFormData({ ...formData, toVaId: e.target.value })}
              placeholder="Enter destination VA ID"
              required
            />
          </>
        ) : (
          <>
            <Input
              label="Account (VA ID)"
              value={formData.vaId}
              onChange={(e) => setFormData({ ...formData, vaId: e.target.value })}
              placeholder="Enter VA ID"
              required
            />
            <Input
              label={type === 'credit' ? 'Remitter Name' : 'Beneficiary Name'}
              value={formData.counterpartyName}
              onChange={(e) => setFormData({ ...formData, counterpartyName: e.target.value })}
              placeholder={type === 'credit' ? 'Enter remitter name' : 'Enter beneficiary name'}
              required
            />
            <Input
              label={type === 'credit' ? 'Remitter Account' : 'Beneficiary Account'}
              value={formData.counterpartyAccount}
              onChange={(e) => setFormData({ ...formData, counterpartyAccount: e.target.value })}
              placeholder="Enter account number (optional)"
            />
            <Select
              label="Channel"
              value={formData.channel}
              onChange={(e) => setFormData({ ...formData, channel: e.target.value })}
              options={channelOptions}
            />
          </>
        )}

        <Input
          label="Amount (AED)"
          type="number"
          value={formData.amount}
          onChange={(e) => setFormData({ ...formData, amount: e.target.value })}
          placeholder="Enter amount"
          required
        />

        <Input
          label="Description"
          value={formData.description}
          onChange={(e) => setFormData({ ...formData, description: e.target.value })}
          placeholder="Enter description"
          required
        />

        {/* Actions */}
        <div className="flex flex-col-reverse sm:flex-row justify-end gap-3 pt-4 border-t border-neutral-200 dark:border-primary-800">
          <Button type="button" variant="ghost" onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="primary" loading={loading} leftIcon={<Send className="w-4 h-4" />}>
            Submit
          </Button>
        </div>
      </form>
    </Modal>
  );
};

// ============================================================================
// SIMULATE COLLECTION MODAL (ISO 20022 Style - pacs.008/camt.054)
// ============================================================================

interface SimulateCollectionModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSubmit: (data: any) => Promise<void>;
}

interface VirtualAccountOption {
  id: string;
  vaNumber: string;
  vaName: string;
  viban?: string;
  currentBalance: number;
  currencyCode: string;
}

const SimulateCollectionModal: React.FC<SimulateCollectionModalProps> = ({ isOpen, onClose, onSubmit }) => {
  const [loading, setLoading] = useState(false);
  const [virtualAccounts, setVirtualAccounts] = useState<VirtualAccountOption[]>([]);
  const [loadingVAs, setLoadingVAs] = useState(false);
  const [messageType, setMessageType] = useState<'pacs008' | 'camt054'>('pacs008');

  // Creditor selection mode: 'viban' or 'select'
  const [creditorMode, setCreditorMode] = useState<'viban' | 'select'>('viban');
  const [vibanInput, setVibanInput] = useState('');
  const [vibanLookupLoading, setVibanLookupLoading] = useState(false);
  const [vibanLookupError, setVibanLookupError] = useState<string | null>(null);
  const [resolvedVA, setResolvedVA] = useState<VirtualAccountOption | null>(null);

  // ISO 20022 message fields
  const [formData, setFormData] = useState({
    // Group Header (GrpHdr)
    msgId: `MSGID-${Date.now()}`,
    creationDateTime: new Date().toISOString().slice(0, 16),
    numberOfTransactions: '1',
    settlementMethod: 'CLRG',

    // Credit Transfer Transaction Info (CdtTrfTxInf) / Entry (Ntry)
    endToEndId: `E2E-${Date.now()}`,
    instructionId: `INSTR-${Date.now()}`,
    amount: '',
    currency: 'AED',

    // Debtor (Remitter) Info
    debtorName: '',
    debtorAccountIban: '',
    debtorBic: '',
    debtorBankName: '',
    debtorAddress: '',
    debtorCountry: 'AE',

    // Creditor (Beneficiary) Info - the Virtual Account
    creditorVaId: '',
    creditorViban: '',

    // Remittance Info
    remittanceInfo: '',
    invoiceReference: '',

    // Value Date
    valueDate: new Date().toISOString().split('T')[0],
  });

  // Load virtual accounts
  useEffect(() => {
    const loadVAs = async () => {
      setLoadingVAs(true);
      try {
        const response = await virtualAccountsApi.getAll(0, 100);
        const vaList = response?.data || response || [];
        const activeVAs = (Array.isArray(vaList) ? vaList : [])
          .filter((va: any) => va.status === 'ACTIVE')
          .map((va: any) => ({
            id: va.id,
            vaNumber: va.vaNumber,
            vaName: va.vaName,
            viban: va.viban,
            currentBalance: va.currentBalance || 0,
            currencyCode: va.currencyCode || 'AED',
          }));
        setVirtualAccounts(activeVAs);
      } catch (error) {
        console.error('Failed to load virtual accounts:', error);
      } finally {
        setLoadingVAs(false);
      }
    };
    if (isOpen) {
      loadVAs();
    }
  }, [isOpen]);

  // VIBAN lookup handler
  const handleVibanLookup = async () => {
    if (!vibanInput.trim()) return;

    setVibanLookupLoading(true);
    setVibanLookupError(null);
    setResolvedVA(null);

    try {
      const response = await vibanApi.lookup(vibanInput.trim());
      const vibanData = response?.data || response;

      // Check if VIBAN lookup returned valid data
      if (!vibanData) {
        setVibanLookupError('VIBAN not found');
        return;
      }

      // Check the isValid flag from VibanLookupResponse
      if (vibanData.isValid === false) {
        setVibanLookupError(vibanData.validationMessage || 'VIBAN is not valid');
        return;
      }

      // Check if VIBAN has an assigned virtual account
      // virtualAccountId could be null/undefined if VIBAN is not assigned to a VA
      const vaId = vibanData.virtualAccountId;
      if (!vaId) {
        setVibanLookupError('VIBAN exists but is not assigned to any virtual account');
        return;
      }

      // Lookup the VA details
      const vaResponse = await virtualAccountsApi.getById(vaId);
      const va = vaResponse?.data || vaResponse;

      if (va) {
        const resolvedAccount: VirtualAccountOption = {
          id: va.id,
          vaNumber: va.vaNumber,
          vaName: va.vaName,
          viban: vibanInput.trim(),
          currentBalance: va.currentBalance || 0,
          currencyCode: va.currencyCode || 'AED',
        };
        setResolvedVA(resolvedAccount);
        setFormData(prev => ({
          ...prev,
          creditorVaId: va.id,
          creditorViban: vibanInput.trim()
        }));
      } else {
        setVibanLookupError('Virtual account not found for this VIBAN');
      }
    } catch (error: any) {
      console.error('VIBAN lookup failed:', error);
      setVibanLookupError(error?.response?.data?.message || 'VIBAN lookup failed. Please check the VIBAN and try again.');
    } finally {
      setVibanLookupLoading(false);
    }
  };

  // Clear VIBAN resolution when switching modes
  useEffect(() => {
    if (creditorMode === 'select') {
      setResolvedVA(null);
      setVibanInput('');
      setVibanLookupError(null);
    } else {
      setFormData(prev => ({ ...prev, creditorVaId: '' }));
    }
  }, [creditorMode]);

  // Generate new message IDs
  const generateNewIds = () => {
    const timestamp = Date.now();
    setFormData(prev => ({
      ...prev,
      msgId: `MSGID-${timestamp}`,
      endToEndId: `E2E-${timestamp}`,
      instructionId: `INSTR-${timestamp}`,
      creationDateTime: new Date().toISOString().slice(0, 16),
    }));
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    try {
      await onSubmit({
        vaId: formData.creditorVaId,
        amount: parseFloat(formData.amount),
        valueDate: formData.valueDate,
        description: formData.remittanceInfo || `ISO 20022 ${messageType.toUpperCase()} - ${formData.invoiceReference || formData.endToEndId}`,
        channel: messageType === 'pacs008' ? 'SWIFT' : 'DIRECT_DEBIT',
        remitterName: formData.debtorName,
        remitterAccount: formData.debtorAccountIban,
        externalReference: formData.endToEndId,
      });
      onClose();
      // Reset form
      setFormData({
        msgId: `MSGID-${Date.now()}`,
        creationDateTime: new Date().toISOString().slice(0, 16),
        numberOfTransactions: '1',
        settlementMethod: 'CLRG',
        endToEndId: `E2E-${Date.now()}`,
        instructionId: `INSTR-${Date.now()}`,
        amount: '',
        currency: 'AED',
        debtorName: '',
        debtorAccountIban: '',
        debtorBic: '',
        debtorBankName: '',
        debtorAddress: '',
        debtorCountry: 'AE',
        creditorVaId: '',
        creditorViban: '',
        remittanceInfo: '',
        invoiceReference: '',
        valueDate: new Date().toISOString().split('T')[0],
      });
      // Reset VIBAN lookup state
      setVibanInput('');
      setResolvedVA(null);
      setVibanLookupError(null);
      setCreditorMode('viban');
    } catch (error) {
      console.error('Collection simulation failed:', error);
    } finally {
      setLoading(false);
    }
  };

  // Get the selected/resolved VA for display
  const selectedVA = creditorMode === 'viban'
    ? resolvedVA
    : virtualAccounts.find(va => va.id === formData.creditorVaId);

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="Simulate Incoming Collection" size="xl">
      <form onSubmit={handleSubmit} className="p-6 space-y-6 max-h-[80vh] overflow-y-auto">
        {/* ISO 20022 Message Type Selection */}
        <div className="bg-gradient-to-r from-info-50 to-cat-1-soft rounded-lg p-4 border border-info-100 dark:border-info-500/30 dark:from-info-500/15 dark:to-cat-1/15">
          <div className="flex items-center gap-3 mb-4">
            <StatusIconBadge tone="info" icon={FileCode} className="dark:bg-info-500/20" />
            <div>
              <h3 className="font-semibold text-neutral-900 dark:text-neutral-50">ISO 20022 Message Simulation</h3>
              <p className="caption">Simulate incoming payment messages as if received from SWIFT/Clearing</p>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <button
              type="button"
              onClick={() => setMessageType('pacs008')}
              className={cn(
                'p-4 rounded-lg border-2 transition-all duration-200 text-left',
                messageType === 'pacs008'
                  ? 'border-info-500 bg-info-50 shadow-sm dark:bg-info-500/10'
                  : 'border-neutral-200 bg-white hover:border-neutral-300 dark:border-primary-800 dark:bg-primary-900 dark:hover:border-primary-700'
              )}
            >
              <div className="flex items-center gap-2 mb-2">
                <Badge variant={messageType === 'pacs008' ? 'info' : 'neutral'} size="sm">pacs.008</Badge>
                <span className="caption">FIToFI Customer Credit Transfer</span>
              </div>
              <p className="caption">
                Standard SWIFT payment message for incoming cross-border or domestic payments
              </p>
            </button>
            <button
              type="button"
              onClick={() => setMessageType('camt054')}
              className={cn(
                'p-4 rounded-lg border-2 transition-all duration-200 text-left',
                messageType === 'camt054'
                  ? 'border-cat-2 bg-cat-2-soft shadow-sm dark:bg-cat-2/15'
                  : 'border-neutral-200 bg-white hover:border-neutral-300 dark:border-primary-800 dark:bg-primary-900 dark:hover:border-primary-700'
              )}
            >
              <div className="flex items-center gap-2 mb-2">
                <Badge variant={messageType === 'camt054' ? 'info' : 'neutral'} size="sm">camt.054</Badge>
                <span className="caption">Bank to Customer Debit/Credit Notification</span>
              </div>
              <p className="caption">
                Bank statement notification message for collection/credit advice
              </p>
            </button>
          </div>
        </div>

        {/* Group Header Section */}
        <div className="border border-neutral-200 rounded-lg overflow-hidden dark:border-primary-800">
          <div className="bg-neutral-50 px-4 py-2 border-b border-neutral-200 dark:bg-primary-950 dark:border-primary-800">
            <div className="flex items-center gap-2">
              <Hash className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
              <span className="field-label">
                {messageType === 'pacs008' ? 'GrpHdr (Group Header)' : 'GrpHdr (Group Header)'}
              </span>
            </div>
          </div>
          <div className="p-4 grid grid-cols-2 gap-4">
            <div>
              <Input
                label="Message ID (MsgId)"
                value={formData.msgId}
                onChange={(e) => setFormData({ ...formData, msgId: e.target.value })}
                placeholder="MSGID-..."
                required
              />
            </div>
            <div>
              <Input
                label="Creation Date Time (CreDtTm)"
                type="datetime-local"
                value={formData.creationDateTime}
                onChange={(e) => setFormData({ ...formData, creationDateTime: e.target.value })}
                required
              />
            </div>
            <div className="col-span-2 flex justify-end">
              <Button type="button" variant="ghost" size="sm" onClick={generateNewIds}>
                <RefreshCw className="w-3 h-3 mr-1" /> Generate New IDs
              </Button>
            </div>
          </div>
        </div>

        {/* Transaction Info Section */}
        <div className="border border-neutral-200 rounded-lg overflow-hidden dark:border-primary-800">
          <div className="bg-neutral-50 px-4 py-2 border-b border-neutral-200 dark:bg-primary-950 dark:border-primary-800">
            <div className="flex items-center gap-2">
              <CreditCard className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
              <span className="field-label">
                {messageType === 'pacs008' ? 'CdtTrfTxInf (Credit Transfer Info)' : 'Ntry (Entry)'}
              </span>
            </div>
          </div>
          <div className="p-4 grid grid-cols-2 gap-4">
            <div>
              <Input
                label="End-to-End ID (EndToEndId)"
                value={formData.endToEndId}
                onChange={(e) => setFormData({ ...formData, endToEndId: e.target.value })}
                placeholder="E2E-..."
                required
              />
            </div>
            <div>
              <Input
                label="Instruction ID (InstrId)"
                value={formData.instructionId}
                onChange={(e) => setFormData({ ...formData, instructionId: e.target.value })}
                placeholder="INSTR-..."
              />
            </div>
            <div>
              <Input
                label="Amount (Amt)"
                type="number"
                step="0.01"
                value={formData.amount}
                onChange={(e) => setFormData({ ...formData, amount: e.target.value })}
                placeholder="Enter amount"
                required
              />
            </div>
            <div>
              <Select
                label="Currency (Ccy)"
                value={formData.currency}
                onChange={(e) => setFormData({ ...formData, currency: e.target.value })}
                options={[
                  { value: 'AED', label: 'AED - UAE Dirham' },
                  { value: 'USD', label: 'USD - US Dollar' },
                  { value: 'EUR', label: 'EUR - Euro' },
                  { value: 'GBP', label: 'GBP - British Pound' },
                  { value: 'SAR', label: 'SAR - Saudi Riyal' },
                ]}
              />
            </div>
            <div className="col-span-2">
              <Input
                label="Value Date (ValDt)"
                type="date"
                value={formData.valueDate}
                onChange={(e) => setFormData({ ...formData, valueDate: e.target.value })}
                required
              />
            </div>
          </div>
        </div>

        {/* Debtor (Remitter) Section */}
        <div className="border border-neutral-200 rounded-lg overflow-hidden dark:border-primary-800">
          <div className="bg-neutral-50 px-4 py-2 border-b border-neutral-200 dark:bg-primary-950 dark:border-primary-800">
            <div className="flex items-center gap-2">
              <Building2 className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
              <span className="field-label">Dbtr (Debtor / Remitter)</span>
            </div>
          </div>
          <div className="p-4 grid grid-cols-2 gap-4">
            <div className="col-span-2">
              <Input
                label="Debtor Name (Nm)"
                value={formData.debtorName}
                onChange={(e) => setFormData({ ...formData, debtorName: e.target.value })}
                placeholder="e.g., Emirates Trading LLC"
                required
              />
            </div>
            <div>
              <Input
                label="Debtor IBAN (IBAN)"
                value={formData.debtorAccountIban}
                onChange={(e) => setFormData({ ...formData, debtorAccountIban: e.target.value })}
                placeholder="e.g., AE070331234567890123456"
              />
            </div>
            <div>
              <Input
                label="Debtor Bank BIC (BIC)"
                value={formData.debtorBic}
                onChange={(e) => setFormData({ ...formData, debtorBic: e.target.value })}
                placeholder="e.g., BOMLAEAD"
              />
            </div>
            <div>
              <Input
                label="Debtor Bank Name"
                value={formData.debtorBankName}
                onChange={(e) => setFormData({ ...formData, debtorBankName: e.target.value })}
                placeholder="e.g., Bank of Middle East"
              />
            </div>
            <div>
              <Select
                label="Debtor Country (Ctry)"
                value={formData.debtorCountry}
                onChange={(e) => setFormData({ ...formData, debtorCountry: e.target.value })}
                options={[
                  { value: 'AE', label: 'AE - United Arab Emirates' },
                  { value: 'SA', label: 'SA - Saudi Arabia' },
                  { value: 'US', label: 'US - United States' },
                  { value: 'GB', label: 'GB - United Kingdom' },
                  { value: 'DE', label: 'DE - Germany' },
                ]}
              />
            </div>
          </div>
        </div>

        {/* Creditor (Beneficiary) Section - VIBAN or Virtual Account Selection */}
        <div className="border border-neutral-200 rounded-lg overflow-hidden dark:border-primary-800">
          <div className="bg-neutral-50 px-4 py-2 border-b border-neutral-200 dark:bg-primary-950 dark:border-primary-800">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Landmark className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
                <span className="field-label">CdtrAcct (Creditor Account)</span>
              </div>
            </div>
          </div>
          <div className="p-4">
            {/* Mode Toggle Tabs */}
            <div className="flex gap-2 mb-4">
              <button
                type="button"
                onClick={() => setCreditorMode('viban')}
                className={cn(
                  'flex-1 px-4 py-2.5 rounded-lg text-body-sm font-medium transition-all duration-200 flex items-center justify-center gap-2',
                  creditorMode === 'viban'
                    ? 'bg-primary-100 text-primary-900 border-2 border-primary-500 dark:bg-primary-700 dark:text-neutral-50'
                    : 'bg-neutral-100 text-neutral-600 border-2 border-transparent hover:bg-neutral-200 dark:hover:bg-primary-700 dark:bg-primary-800 dark:text-neutral-300'
                )}
              >
                <CreditCard className="w-4 h-4" />
                Enter VIBAN
              </button>
              <button
                type="button"
                onClick={() => setCreditorMode('select')}
                className={cn(
                  'flex-1 px-4 py-2.5 rounded-lg text-body-sm font-medium transition-all duration-200 flex items-center justify-center gap-2',
                  creditorMode === 'select'
                    ? 'bg-primary-100 text-primary-900 border-2 border-primary-500 dark:bg-primary-700 dark:text-neutral-50'
                    : 'bg-neutral-100 text-neutral-600 border-2 border-transparent hover:bg-neutral-200 dark:hover:bg-primary-700 dark:bg-primary-800 dark:text-neutral-300'
                )}
              >
                <Building2 className="w-4 h-4" />
                Select Account
              </button>
            </div>

            {/* VIBAN Entry Mode */}
            {creditorMode === 'viban' && (
              <div className="space-y-4">
                <div>
                  <label className="field-label block mb-2">
                    Creditor VIBAN (IBAN)
                  </label>
                  <div className="flex gap-2">
                    <Input
                      value={vibanInput}
                      onChange={(e) => {
                        setVibanInput(e.target.value.toUpperCase());
                        setVibanLookupError(null);
                        setResolvedVA(null);
                      }}
                      placeholder="e.g., AE070331234567890123456"
                      className="flex-1 font-mono"
                    />
                    <Button
                      type="button"
                      variant="outline"
                      onClick={handleVibanLookup}
                      disabled={!vibanInput.trim() || vibanLookupLoading}
                    >
                      {vibanLookupLoading ? (
                        <RefreshCw className="w-4 h-4 animate-spin" />
                      ) : (
                        <Search className="w-4 h-4" />
                      )}
                      <span className="ml-2">Lookup</span>
                    </Button>
                  </div>
                  <p className="caption mt-1">
                    Enter the VIBAN as it appears on the incoming payment message
                  </p>
                </div>

                {/* VIBAN Lookup Error */}
                {vibanLookupError && (
                  <div className="flex items-start gap-2 p-3 bg-error-50 border border-error-100 rounded-lg dark:bg-error-500/10 dark:border-error-500/30">
                    <XCircle className="w-4 h-4 text-error-600 mt-0.5 dark:text-error-300" />
                    <div className="text-body-sm text-error-700 dark:text-error-300">{vibanLookupError}</div>
                  </div>
                )}
              </div>
            )}

            {/* Account Selection Mode */}
            {creditorMode === 'select' && (
              <>
                {loadingVAs ? (
                  <div className="flex items-center gap-2 text-neutral-500 dark:text-neutral-400">
                    <RefreshCw className="w-4 h-4 animate-spin" />
                    <span>Loading virtual accounts...</span>
                  </div>
                ) : virtualAccounts.length === 0 ? (
                  <div className="text-center py-4 text-neutral-500 dark:text-neutral-400">
                    No active virtual accounts available
                  </div>
                ) : (
                  <Select
                    label="Select Creditor Virtual Account"
                    value={formData.creditorVaId}
                    onChange={(e) => setFormData({ ...formData, creditorVaId: e.target.value })}
                    options={[
                      { value: '', label: 'Select a Virtual Account...' },
                      ...virtualAccounts.map(va => ({
                        value: va.id,
                        label: `${va.vaNumber} - ${va.vaName} ${va.viban ? `(${va.viban})` : ''} - ${formatCurrency(va.currentBalance, va.currencyCode)}`
                      }))
                    ]}
                  />
                )}
              </>
            )}

            {/* Resolved/Selected Account Display */}
            {selectedVA && (
              <div className="mt-4 p-3 bg-success-50 border border-success-100 rounded-lg dark:bg-success-500/10 dark:border-success-500/30">
                <div className="flex items-center gap-2 mb-2">
                  <CheckCircle className="w-4 h-4 text-success-600 dark:text-success-300" />
                  <span className="text-body-sm font-medium text-success-800 dark:text-success-300">
                    {creditorMode === 'viban' ? 'VIBAN Resolved to Account' : 'Selected Account'}
                  </span>
                </div>
                <div className="grid grid-cols-2 gap-2 text-caption">
                  <div>
                    <span className="text-neutral-500 dark:text-neutral-400">VA Number:</span>
                    <span className="ml-1 font-mono text-neutral-900 dark:text-neutral-50">{selectedVA.vaNumber}</span>
                  </div>
                  <div>
                    <span className="text-neutral-500 dark:text-neutral-400">Name:</span>
                    <span className="ml-1 text-neutral-900 dark:text-neutral-50">{selectedVA.vaName}</span>
                  </div>
                  {selectedVA.viban && (
                    <div className="col-span-2">
                      <span className="text-neutral-500 dark:text-neutral-400">VIBAN:</span>
                      <span className="ml-1 font-mono text-neutral-900 dark:text-neutral-50">{selectedVA.viban}</span>
                    </div>
                  )}
                  <div>
                    <span className="text-neutral-500 dark:text-neutral-400">Current Balance:</span>
                    <span className="ml-1 font-semibold text-success-700 dark:text-success-300">
                      {formatCurrency(selectedVA.currentBalance, selectedVA.currencyCode)}
                    </span>
                  </div>
                </div>
              </div>
            )}
          </div>
        </div>

        {/* Remittance Information */}
        <div className="border border-neutral-200 rounded-lg overflow-hidden dark:border-primary-800">
          <div className="bg-neutral-50 px-4 py-2 border-b border-neutral-200 dark:bg-primary-950 dark:border-primary-800">
            <div className="flex items-center gap-2">
              <FileText className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
              <span className="field-label">RmtInf (Remittance Information)</span>
            </div>
          </div>
          <div className="p-4 grid grid-cols-2 gap-4">
            <div>
              <Input
                label="Invoice Reference (Ref)"
                value={formData.invoiceReference}
                onChange={(e) => setFormData({ ...formData, invoiceReference: e.target.value })}
                placeholder="e.g., INV-2024-0001"
              />
            </div>
            <div className="col-span-2">
              <Input
                label="Unstructured Remittance Info (Ustrd)"
                value={formData.remittanceInfo}
                onChange={(e) => setFormData({ ...formData, remittanceInfo: e.target.value })}
                placeholder="e.g., Payment for Invoice INV-2024-0001 dated 15-Jan-2024"
              />
            </div>
          </div>
        </div>

        {/* Info Banner */}
        <div className="flex items-start gap-3 p-4 bg-warning-50 border border-warning-100 rounded-lg dark:bg-warning-500/10 dark:border-warning-500/30">
          <Info className="w-5 h-5 text-warning-600 shrink-0 mt-0.5 dark:text-warning-300" />
          <div className="text-body-sm text-warning-800 dark:text-warning-300">
            <p className="font-medium mb-1">Simulation Mode</p>
            <p className="text-caption text-warning-700 dark:text-warning-300">
              This will create a credit transaction on the selected virtual account, simulating an incoming
              {messageType === 'pacs008' ? ' pacs.008 SWIFT payment' : ' camt.054 bank notification'}.
              In production, these messages would be received automatically from SWIFT or the clearing system.
            </p>
          </div>
        </div>

        {/* Actions */}
        <div className="flex flex-col-reverse sm:flex-row justify-end gap-3 pt-4 border-t border-neutral-200 dark:border-primary-800">
          <Button type="button" variant="ghost" onClick={onClose}>Cancel</Button>
          <Button
            type="submit"
            variant="primary"
            loading={loading}
            leftIcon={<ArrowDownLeft className="w-4 h-4" />}
            disabled={!formData.creditorVaId || !formData.amount || !formData.debtorName}
          >
            Simulate {messageType === 'pacs008' ? 'pacs.008' : 'camt.054'} Collection
          </Button>
        </div>
      </form>
    </Modal>
  );
};

// ============================================================================
// MAIN TRANSACTIONS PAGE
// ============================================================================

const TransactionsPage: React.FC = () => {
  // State
  const [transactions, setTransactions] = useState<Transaction[]>([]);
  const [stats, setStats] = useState<TransactionStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedTransaction, setSelectedTransaction] = useState<Transaction | null>(null);
  const [reverseTargetId, setReverseTargetId] = useState<string | null>(null);
  const [reversing, setReversing] = useState(false);
  const [activeTab, setActiveTab] = useState('all');
  const [currentPage, setCurrentPage] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [showNewModal, setShowNewModal] = useState(false);
  const [showCollectionModal, setShowCollectionModal] = useState(false);
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const [filters, _setFilters] = useState({
    status: '',
    channel: '',
    dateFrom: '',
    dateTo: '',
  });

  // Grouped View State (Option B - Simplified Business View)
  const [viewMode, setViewMode] = useState<'individual' | 'grouped'>('grouped'); // Default to grouped
  const [groupedTransactions, setGroupedTransactions] = useState<GroupedTransaction[]>([]);

  // Corporate scope. Empty = "All Corporates" (platform-wide, same as before the picker existed).
  const [corporates, setCorporates] = useState<ScopeCorporate[]>([]);
  const [selectedCorporateId, setSelectedCorporateId] = useState('');
  const [loadingCorporates, setLoadingCorporates] = useState(true);

  useEffect(() => {
    corporatesApi.getAll()
      .then((res) => { if (res.success) setCorporates(res.data as unknown as ScopeCorporate[]); })
      .catch(() => {})
      .finally(() => setLoadingCorporates(false));
  }, []);

  const pageSize = 10;

  // Fetch data
  const fetchData = useCallback(async () => {
    setLoading(true);
    try {
      const corporateId = selectedCorporateId || undefined;

      // Fetch stats
      const statsRes = await transactionsApi.getStats(corporateId);
      if (statsRes.success && statsRes.data) {
        setStats(statsRes.data);
      } else {
        setStats(demoStats);
      }

      // Determine direction filter for grouped view based on tab
      let direction: 'INBOUND' | 'OUTBOUND' | 'ALL' | undefined;
      let movementType: string | undefined;
      let status: string | undefined;
      if (activeTab === 'credit') {
        direction = 'INBOUND';
        movementType = 'CREDIT';
      } else if (activeTab === 'debit') {
        direction = 'OUTBOUND';
        movementType = 'DEBIT';
      } else if (activeTab === 'pending') {
        status = 'PENDING';
      }

      // Fetch based on view mode
      if (viewMode === 'grouped') {
        // Fetch grouped transactions
        try {
          const groupedRes = await transactionsApi.getGrouped({
            corporateId,
            direction: direction,
            page: currentPage,
            pageSize,
          });

          if (groupedRes.success && groupedRes.data) {
            setGroupedTransactions(groupedRes.data.content || []);
            setTotalElements(groupedRes.data.totalElements || 0);
          } else {
            setGroupedTransactions([]);
            setTotalElements(0);
          }
        } catch (err) {
          console.warn('Grouped transactions API not available, falling back to individual view');
          // Fall back to individual view if grouped API not available
          setViewMode('individual');
        }
      }

      // Always fetch individual transactions (for fallback and stats)
      const txnRes = await transactionsApi.getAll({
        corporateId,
        page: currentPage,
        pageSize,
        query: searchQuery || undefined,
        movementType,
        status: status || filters.status || undefined,
        channel: filters.channel || undefined,
        dateFrom: filters.dateFrom || undefined,
        dateTo: filters.dateTo || undefined,
        sortBy: 'transactionDate',
        sortOrder: 'desc',
      });

      if (txnRes.success && txnRes.data) {
        setTransactions(txnRes.data.content || []);
        if (viewMode === 'individual') {
          setTotalElements(txnRes.data.totalElements || 0);
        }
      } else {
        // Use demo data on failure
        setTransactions(demoTransactions);
        if (viewMode === 'individual') {
          setTotalElements(demoTransactions.length);
        }
      }
    } catch (error) {
      console.error('Failed to fetch data:', error);
      setStats(demoStats);
      setTransactions(demoTransactions);
    } finally {
      setLoading(false);
    }
  }, [currentPage, searchQuery, activeTab, filters, viewMode, selectedCorporateId]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  // Changing corporate scope invalidates the current page number (page 7 of "All
  // Corporates" may not exist for a single corporate).
  const handleCorporateChange = (id: string) => {
    setSelectedCorporateId(id);
    setCurrentPage(0);
  };

  // Handle new transaction
  const handleNewTransaction = async (type: 'credit' | 'debit' | 'transfer', data: any) => {
    try {
      if (type === 'transfer') {
        await transactionsApi.transfer({
          fromVaId: data.fromVaId,
          toVaId: data.toVaId,
          amount: parseFloat(data.amount),
          description: data.description,
        });
      } else if (type === 'credit') {
        await transactionsApi.credit({
          vaId: data.vaId,
          amount: parseFloat(data.amount),
          description: data.description,
          channel: data.channel,
          remitterName: data.counterpartyName,
          remitterAccount: data.counterpartyAccount,
        });
      } else {
        await transactionsApi.debit({
          vaId: data.vaId,
          amount: parseFloat(data.amount),
          description: data.description,
          channel: data.channel,
          beneficiaryName: data.counterpartyName,
          beneficiaryAccount: data.counterpartyAccount,
        });
      }
      fetchData();
    } catch (error) {
      console.error('Transaction failed:', error);
    }
  };

  // Handle reverse
  const handleReverse = (id: string) => {
    setReverseTargetId(id);
  };

  const confirmReverse = async () => {
    if (!reverseTargetId) return;
    setReversing(true);
    try {
      await transactionsApi.reverse(reverseTargetId, 'User requested reversal');
      toast.success('Transaction reversed successfully');
      setSelectedTransaction(null);
      setReverseTargetId(null);
      fetchData();
    } catch (error: any) {
      console.error('Reversal failed:', error);
      toast.error(error?.response?.data?.message || error?.message || 'Failed to reverse transaction');
    } finally {
      setReversing(false);
    }
  };

  // Handle simulate collection (ISO 20022 style)
  const handleSimulateCollection = async (data: any) => {
    try {
      await transactionsApi.credit({
        vaId: data.vaId,
        amount: data.amount,
        valueDate: data.valueDate,
        description: data.description,
        channel: data.channel,
        remitterName: data.remitterName,
        remitterAccount: data.remitterAccount,
        externalReference: data.externalReference,
      });
      fetchData();
    } catch (error) {
      console.error('Collection simulation failed:', error);
      throw error;
    }
  };

  // Handle view of grouped transaction: opens the same TransactionDetails
  // modal individual-view rows use, fetched via the group's primary leg.
  const handleViewGroupedTransaction = async (txn: GroupedTransaction) => {
    try {
      const res = await transactionsApi.getById(txn.primaryTransactionId);
      if (res.success && res.data) {
        setSelectedTransaction(res.data);
      } else {
        toast.error('Failed to load transaction details');
      }
    } catch (error: any) {
      console.error('Failed to fetch transaction details:', error);
      toast.error(error?.response?.data?.message || error?.message || 'Failed to load transaction details');
    }
  };

  // Tabs configuration
  const tabs = [
    { id: 'all', label: 'All', count: totalElements },
    { id: 'credit', label: 'Credits', count: stats?.completedCount || 0 },
    { id: 'debit', label: 'Debits', count: stats?.completedCount || 0 },
    { id: 'pending', label: 'Pending', count: stats?.pendingCount || 0 },
  ];

  // Ledger (individual) view columns
  const transactionColumns: Column<Transaction>[] = [
    {
      key: 'referenceNumber', header: 'Reference', mobileLabel: true, minWidth: 270,
      render: (_, t) => (
        <div className="flex items-center gap-3">
          <div className={cn('w-10 h-10 rounded-lg flex items-center justify-center shrink-0', getMovementBgClass(t.movementType))}>
            <MovementIcon type={t.movementType} />
          </div>
          <div className="min-w-0">
            <p className="text-body-sm font-mono text-neutral-900 truncate dark:text-neutral-50">{t.referenceNumber}</p>
            <p className="caption">{t.movementType.replace(/_/g, ' ')}</p>
          </div>
        </div>
      ),
    },
    {
      key: 'vaName', header: 'Account', mobileHidden: true, minWidth: 190, dropOrder: 1,
      render: (_, t) => (
        <div className="min-w-0">
          <p className="text-body-sm font-medium text-neutral-900 truncate dark:text-neutral-50">{t.vaName}</p>
          <p className="text-caption text-neutral-500 font-mono truncate dark:text-neutral-400">{t.vaNumber}</p>
        </div>
      ),
    },
    {
      key: 'counterpartyName', header: 'Counterparty', minWidth: 320, dropOrder: 2,
      render: (_, t) => (
        <div className="min-w-0">
          <p className="text-body-sm text-neutral-900 truncate dark:text-neutral-50">{t.counterpartyName || '-'}</p>
          {t.counterpartyAccount && (
            <p className="text-caption text-neutral-500 font-mono truncate max-w-48 dark:text-neutral-400">{t.counterpartyAccount}</p>
          )}
        </div>
      ),
    },
    {
      key: 'amount', header: 'Amount', align: 'right', mobileValue: true, minWidth: 160,
      render: (_, t) => (
        <p className={cn('text-body-sm font-semibold', getAmountColorClass(t.movementType))}>
          {isCredit(t.movementType) ? '+' : '-'}{formatCurrency(t.amount, t.currencyCode)}
        </p>
      ),
    },
    {
      key: 'status', header: 'Status', minWidth: 146,
      render: (_, t) => (
        <div className="flex items-center gap-2">
          <StatusIcon status={t.status} />
          <Badge variant={getStatusVariant(t.status)} size="sm">{t.status}</Badge>
        </div>
      ),
    },
    {
      key: 'transactionDate', header: 'Date', mobileHidden: true, minWidth: 150, dropOrder: 3,
      render: (_, t) => (
        <div>
          <p className="text-body-sm text-neutral-900 dark:text-neutral-50">{formatRelativeTime(t.transactionDate)}</p>
          <p className="caption">Value: {t.valueDate ? formatDate(t.valueDate) : '-'}</p>
        </div>
      ),
    },
    {
      key: 'channel', header: 'Channel', mobileHidden: true, minWidth: 110, dropOrder: 4,
      render: (_, t) => <Badge variant="neutral" size="sm">{t.channel || 'N/A'}</Badge>,
    },
    {
      key: 'actions', header: 'Actions', align: 'right', width: '80px', mobileHidden: true, minWidth: 90,
      render: (_, t) => (
        <div className="flex items-center justify-end gap-1" onClick={(e) => e.stopPropagation()}>
          <button
            className="p-2 hover:bg-neutral-100 rounded-lg transition-colors dark:hover:bg-primary-800"
            onClick={() => setSelectedTransaction(t)}
            title="View details"
          >
            <Eye className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
          </button>
          {t.status === 'COMPLETED' && (
            <button
              className="p-2 hover:bg-neutral-100 rounded-lg transition-colors dark:hover:bg-primary-800"
              onClick={() => handleReverse(t.id)}
              title="Reverse transaction"
            >
              <RefreshCw className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
            </button>
          )}
        </div>
      ),
    },
  ];

  // Business (grouped) view columns
  const groupedColumns: Column<GroupedTransaction>[] = [
    {
      key: 'primaryReferenceNumber', header: 'Operation', mobileLabel: true, minWidth: 335,
      render: (_, t) => (
        <div className="flex items-center gap-3">
          <div className={cn('w-10 h-10 rounded-lg flex items-center justify-center shrink-0', t.isCredit ? 'bg-success-50 dark:bg-success-500/10' : 'bg-error-50 dark:bg-error-500/10')}>
            {t.isCredit ? (
              <ArrowDownLeft className="w-5 h-5 text-success-600 dark:text-success-300" />
            ) : (
              <ArrowUpRight className="w-5 h-5 text-error-600 dark:text-error-300" />
            )}
          </div>
          <div className="min-w-0">
            <div className="flex items-center gap-2">
              <p className="text-body-sm font-mono text-neutral-900 truncate dark:text-neutral-50">{t.primaryReferenceNumber}</p>
              <OperationTypeBadge type={t.operationType} />
            </div>
            <p className="caption">
              {t.entryCount} fund {t.entryCount === 1 ? 'movement' : 'movements'}
            </p>
          </div>
        </div>
      ),
    },
    {
      key: 'userVaName', header: 'Account', mobileHidden: true, minWidth: 220, dropOrder: 1,
      render: (_, t) => (
        <div className="min-w-0">
          <p className="text-body-sm font-medium text-neutral-900 truncate dark:text-neutral-50">{t.userVaName || '-'}</p>
          <p className="text-caption text-neutral-500 font-mono truncate dark:text-neutral-400">{t.userVaNumber}</p>
        </div>
      ),
    },
    {
      key: 'counterpartyName', header: 'Counterparty', minWidth: 320, dropOrder: 2,
      render: (_, t) => (
        <div className="min-w-0">
          <p className="text-body-sm text-neutral-900 truncate dark:text-neutral-50">{t.counterpartyName || '-'}</p>
          {t.counterpartyAccount && (
            <p className="text-caption text-neutral-500 font-mono truncate max-w-48 dark:text-neutral-400">{t.counterpartyAccount}</p>
          )}
        </div>
      ),
    },
    {
      key: 'netAmount', header: 'Net Amount', align: 'right', mobileValue: true, minWidth: 130,
      render: (_, t) => (
        <div>
          <p className={cn('text-body-sm font-semibold', t.isCredit ? 'text-success-600 dark:text-success-300' : 'text-error-600 dark:text-error-300')}>
            {t.isCredit ? '+' : '-'}{formatCurrency(t.netAmount, t.currencyCode)}
          </p>
          {t.feeAmount > 0 && (
            <p className="caption">
              Gross: {formatCurrency(t.grossAmount, t.currencyCode)} | Fee: {formatCurrency(t.feeAmount, t.currencyCode)}
            </p>
          )}
        </div>
      ),
    },
    {
      key: 'status', header: 'Status', minWidth: 146,
      render: (_, t) => (
        <div className="flex items-center gap-2">
          <StatusIcon status={t.status} />
          <Badge variant={getStatusVariant(t.status)} size="sm">{t.status}</Badge>
        </div>
      ),
    },
    {
      key: 'transactionDate', header: 'Date', mobileHidden: true, minWidth: 150, dropOrder: 3,
      render: (_, t) => (
        <div>
          <p className="text-body-sm text-neutral-900 dark:text-neutral-50">{formatRelativeTime(t.transactionDate)}</p>
          <p className="caption">Value: {t.valueDate ? formatDate(t.valueDate) : '-'}</p>
        </div>
      ),
    },
    {
      key: 'channel', header: 'Channel', mobileHidden: true, minWidth: 110, dropOrder: 4,
      render: (_, t) => <Badge variant="neutral" size="sm">{t.channel || 'N/A'}</Badge>,
    },
    {
      key: 'actions', header: 'Actions', align: 'right', width: '80px', mobileHidden: true, minWidth: 100,
      render: (_, t) => (
        <div className="flex items-center justify-end gap-1" onClick={(e) => e.stopPropagation()}>
          <button
            className="p-2 hover:bg-neutral-100 rounded-lg transition-colors dark:hover:bg-primary-800"
            onClick={() => handleViewGroupedTransaction(t)}
            title="View details"
          >
            <Eye className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
          </button>
          {t.status === 'COMPLETED' && (
            <button
              className="p-2 hover:bg-neutral-100 rounded-lg transition-colors dark:hover:bg-primary-800"
              onClick={() => handleReverse(t.primaryTransactionId)}
              title="Reverse transaction"
            >
              <RefreshCw className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
            </button>
          )}
        </div>
      ),
    },
  ];

  return (
    <Page>
      {/* Quick Actions */}
      <div className="flex items-center justify-end gap-2">
        <Button
          variant="outline"
          leftIcon={<Calendar className="w-4 h-4" />}
          className="hidden sm:inline-flex"
        >
          Date Range
        </Button>
        <Button
          variant="outline"
          leftIcon={<ArrowDownLeft className="w-4 h-4" />}
          onClick={() => setShowCollectionModal(true)}
          className="hidden md:inline-flex"
        >
          Simulate Collection
        </Button>
        <Button
          variant="primary"
          leftIcon={<Plus className="w-4 h-4" />}
          onClick={() => setShowNewModal(true)}
        >
          New Transaction
        </Button>
      </div>

      {/* Corporate scope */}
      <ScopeSelector
        mode="corporate-only"
        corporates={corporates}
        selectedCorporateId={selectedCorporateId}
        onCorporateChange={handleCorporateChange}
        loading={loadingCorporates}
      />

      {/* Stats Cards */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3 lg:gap-4">
        <StatTile
          layout="row"
          tone="success"
          label="Credits Today"
          value={`+${formatCurrency(stats?.todayCredits || 0)}`}
          icon={<ArrowDownLeft className="w-5 h-5" />}
          loading={loading}
        />
        <StatTile
          layout="row"
          tone="danger"
          label="Debits Today"
          value={`-${formatCurrency(stats?.todayDebits || 0)}`}
          icon={<ArrowUpRight className="w-5 h-5" />}
          loading={loading}
        />
        <StatTile
          layout="row"
          tone="warning"
          label="Pending"
          value={stats?.pendingCount || 0}
          icon={<Clock className="w-5 h-5" />}
          loading={loading}
        />
        <StatTile
          layout="row"
          tone="neutral"
          label="Today's Total"
          value={stats?.todayCount || 0}
          icon={<ArrowLeftRight className="w-5 h-5" />}
          loading={loading}
        />
      </div>

      {/* Tabs and Table */}
      <Card padding="none">
        {/* Tabs */}
        <div className="border-b border-neutral-200 overflow-x-auto dark:border-primary-800">
          <div className="flex gap-1 p-2 min-w-max">
            {tabs.map((tab) => (
              <button
                key={tab.id}
                onClick={() => { setActiveTab(tab.id); setCurrentPage(0); }}
                className={cn(
                  'px-4 py-2.5 rounded-lg text-body-sm font-medium transition-all duration-200 whitespace-nowrap',
                  activeTab === tab.id
                    ? 'bg-primary-100 text-primary-900 dark:bg-primary-700 dark:text-neutral-50'
                    : 'text-neutral-600 hover:bg-neutral-100 dark:text-neutral-300 dark:hover:bg-primary-800'
                )}
              >
                {tab.label}
                <span className={cn(
                  'ml-2 px-2 py-0.5 rounded-full text-caption font-semibold',
                  activeTab === tab.id ? 'bg-primary-200 dark:bg-primary-700 text-primary-800 dark:text-neutral-100' : 'bg-neutral-200 text-neutral-600 dark:bg-primary-800 dark:text-neutral-300'
                )}>
                  {tab.count}
                </span>
              </button>
            ))}
          </div>
        </div>

        {/* Search & Filters */}
        <div className="p-4 border-b border-neutral-200 dark:border-primary-800">
          <div className="flex flex-col sm:flex-row gap-3">
            <div className="flex-1">
              <Input
                placeholder="Search by reference, counterparty, or account..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                leftIcon={<Search className="w-4 h-4" />}
              />
            </div>
            <div className="flex gap-2">
              {/* View Mode Toggle */}
              <div className="flex items-center bg-neutral-100 rounded-lg p-1 dark:bg-primary-800">
                <button
                  className={cn(
                    'px-3 py-1.5 rounded-lg text-caption font-medium transition-all duration-200 flex items-center gap-1.5',
                    viewMode === 'grouped'
                      ? 'bg-white text-primary-700 shadow-sm dark:bg-primary-900 dark:text-neutral-200'
                      : 'text-neutral-600 hover:text-neutral-900 dark:text-neutral-300 dark:hover:text-neutral-50'
                  )}
                  onClick={() => { setViewMode('grouped'); setCurrentPage(0); }}
                  title="Business View - Groups related fund movements into one transaction"
                >
                  <Layers className="w-4 h-4" />
                  <span className="hidden sm:inline">Business</span>
                </button>
                <button
                  className={cn(
                    'px-3 py-1.5 rounded-lg text-caption font-medium transition-all duration-200 flex items-center gap-1.5',
                    viewMode === 'individual'
                      ? 'bg-white text-primary-700 shadow-sm dark:bg-primary-900 dark:text-neutral-200'
                      : 'text-neutral-600 hover:text-neutral-900 dark:text-neutral-300 dark:hover:text-neutral-50'
                  )}
                  onClick={() => { setViewMode('individual'); setCurrentPage(0); }}
                  title="Ledger View - Shows all individual entries"
                >
                  <List className="w-4 h-4" />
                  <span className="hidden sm:inline">Ledger</span>
                </button>
              </div>
              <Button
                variant="ghost"
                leftIcon={<RefreshCw className={cn("w-4 h-4", loading && "animate-spin")} />}
                onClick={fetchData}
                disabled={loading}
                className="shrink-0"
              >
                <span className="hidden sm:inline">Refresh</span>
              </Button>
            </div>
          </div>
        </div>

        {/* View Mode Info Banner */}
        {viewMode === 'grouped' && (
          <div className="mx-4 mt-4 flex items-start gap-2 p-3 bg-info-50 border border-info-100 rounded-lg dark:bg-info-500/10 dark:border-info-500/30">
            <Info className="w-4 h-4 text-info-600 shrink-0 mt-0.5 dark:text-info-300" />
            <div className="text-caption text-info-800 dark:text-info-300">
              <span className="font-medium">Business View:</span> Related fund movements are grouped into a single business transaction.
              Click a row to see the full breakdown.
            </div>
          </div>
        )}

        {/* Table */}
        <div className="p-4 lg:p-6">
          {viewMode === 'grouped' ? (
            <DataTable
              data={groupedTransactions}
              columns={groupedColumns}
              keyExtractor={(t) => t.correlationId}
              onRowClick={handleViewGroupedTransaction}
              loading={loading}
              emptyIcon={<ArrowLeftRight className="w-8 h-8" />}
              emptyTitle="No transactions found"
              emptyDescription="Try adjusting your search or filters."
              emptyAction={
                <Button variant="outline" onClick={() => { setSearchQuery(''); setActiveTab('all'); }}>
                  Clear Filters
                </Button>
              }
            />
          ) : (
            <DataTable
              data={transactions}
              columns={transactionColumns}
              keyExtractor={(t) => t.id}
              onRowClick={setSelectedTransaction}
              loading={loading}
              pagination
              pageSize={pageSize}
              currentPage={currentPage + 1}
              totalCount={totalElements}
              onPageChange={(page) => setCurrentPage(page - 1)}
              emptyIcon={<ArrowLeftRight className="w-8 h-8" />}
              emptyTitle="No transactions found"
              emptyDescription="Try adjusting your search or filters."
              emptyAction={
                <Button variant="outline" onClick={() => { setSearchQuery(''); setActiveTab('all'); }}>
                  Clear Filters
                </Button>
              }
            />
          )}
        </div>
      </Card>

      {/* Transaction Details Modal */}
      <TransactionDetails
        transaction={selectedTransaction}
        onClose={() => setSelectedTransaction(null)}
        onReverse={handleReverse}
      />

      {/* Reverse Confirmation Modal */}
      <Modal
        isOpen={!!reverseTargetId}
        onClose={() => setReverseTargetId(null)}
        title="Reverse Transaction"
        size="sm"
      >
        <div className="space-y-6">
          <p className="body-sm">
            Are you sure you want to reverse this transaction? This action cannot be undone.
          </p>
          <div className="flex justify-end gap-3 pt-4 border-t border-neutral-200 dark:border-primary-800">
            <Button variant="secondary" onClick={() => setReverseTargetId(null)}>Cancel</Button>
            <Button variant="danger" onClick={confirmReverse} disabled={reversing}>
              {reversing ? <Loader2 className="w-4 h-4 animate-spin mr-2" /> : <RefreshCw className="w-4 h-4 mr-2" />}
              Reverse
            </Button>
          </div>
        </div>
      </Modal>

      {/* New Transaction Modal */}
      <NewTransactionModal
        isOpen={showNewModal}
        onClose={() => setShowNewModal(false)}
        onSubmit={handleNewTransaction}
      />

      {/* Simulate Collection Modal (ISO 20022 Style) */}
      <SimulateCollectionModal
        isOpen={showCollectionModal}
        onClose={() => setShowCollectionModal(false)}
        onSubmit={handleSimulateCollection}
      />
    </Page>
  );
};

export default TransactionsPage;