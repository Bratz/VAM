import React, { useState, useEffect, useCallback } from 'react';
import {
  Search,
  Filter,
  Download,
  Eye,
  ArrowUpRight,
  ArrowDownLeft,
  ArrowLeftRight,
  RefreshCw,
  Calendar,
  ChevronLeft,
  ChevronRight,
  ChevronDown,
  ChevronUp,
  Clock,
  CheckCircle,
  XCircle,
  AlertCircle,
  Plus,
  Send,
  FileText,
  Building2,
  Landmark,
  CreditCard,
  Hash,
  FileCode,
  Info,
  Layers,
  List,
  DollarSign,
  Users,
  TrendingUp,
  TrendingDown,
} from 'lucide-react';
import { Card, Button, Badge, Input, Select, EmptyState, Skeleton , StatusIconBadge } from '../components/ui';
import { Modal } from '../components/ui/enhanced';
import { formatCurrency, formatDate, formatRelativeTime, getStatusVariant, cn } from '../utils';
import {
  transactionsApi,
  virtualAccountsApi,
  vibanApi,
  type Transaction,
  type TransactionStats,
  type GroupedTransaction,
} from '../services/api';
import { isCredit, isDebit, getAmountColorClass, getMovementBgClass } from '../utils/transactionUtils';
import { Page } from '../components/layout/Page';

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
      return <CheckCircle className="w-4 h-4 text-success-500" />;
    case 'PENDING':
    case 'PROCESSING':
      return <Clock className="w-4 h-4 text-warning-500" />;
    case 'FAILED':
      return <XCircle className="w-4 h-4 text-error-500" />;
    case 'REVERSED':
    case 'CANCELLED':
      return <AlertCircle className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />;
    default:
      return null;
  }
};

// Stat Card for Dashboard Stats
const StatCard: React.FC<{
  title: string;
  value: string | number;
  icon: React.ReactNode;
  variant?: 'success' | 'error' | 'warning' | 'neutral';
  loading?: boolean;
}> = ({ title, value, icon, variant = 'neutral', loading }) => {
  const variants = {
    success: 'from-success-50 to-white border-success-100 dark:from-success-500/15 dark:to-primary-900 dark:border-success-500/30',
    error:   'from-error-50  to-white border-error-100  dark:from-error-500/15  dark:to-primary-900 dark:border-error-500/30',
    warning: 'from-warning-50 to-white border-warning-100 dark:from-warning-500/15 dark:to-primary-900 dark:border-warning-500/30',
    neutral: 'from-neutral-50 to-white border-neutral-100 dark:from-primary-800/40 dark:to-primary-900 dark:border-primary-800 dark:from-primary-950',
  };

  const textVariants = {
    success: 'text-success-600 dark:text-success-300',
    error: 'text-error-600 dark:text-error-300',
    warning: 'text-warning-600 dark:text-warning-300',
    neutral: 'text-neutral-900 dark:text-neutral-50',
  };

  if (loading) {
    return (
      <Card className={cn('bg-gradient-to-br border', variants[variant])}>
        <div className="flex items-center gap-3">
          <Skeleton className="w-10 h-10 rounded-xl" />
          <div className="flex-1">
            <Skeleton className="h-3 w-20 mb-2" />
            <Skeleton className="h-6 w-24" />
          </div>
        </div>
      </Card>
    );
  }

  return (
    <Card
      className={cn('bg-gradient-to-br border animate-fade-in', variants[variant])}
      hover
    >
      <div className="flex items-center gap-3">
        <div className={cn(
          'w-10 h-10 rounded-xl flex items-center justify-center',
          variant === 'success' && 'bg-success-100 dark:bg-success-500/20',
          variant === 'error' && 'bg-error-100 dark:bg-error-500/20',
          variant === 'warning' && 'bg-warning-100 dark:bg-warning-500/20',
          variant === 'neutral' && 'bg-neutral-100 dark:bg-primary-800'
        )}>
          {icon}
        </div>
        <div>
          <p className="label">{title}</p>
          <p className={cn('text-xl font-bold mt-0.5', textVariants[variant])}>{value}</p>
        </div>
      </div>
    </Card>
  );
};

// Transaction Row - Desktop
interface TransactionRowProps {
  transaction: Transaction;
  onView: (t: Transaction) => void;
}

const TransactionRow: React.FC<TransactionRowProps> = ({ transaction, onView }) => {
  // Use utility functions for consistent credit/debit detection
  const isCreditTxn = isCredit(transaction.movementType);

  return (
    <tr
      className="hover:bg-neutral-50 transition-colors cursor-pointer group dark:hover:bg-primary-800/50"
      onClick={() => onView(transaction)}
    >
      <td className="px-4 lg:px-6 py-4">
        <div className="flex items-center gap-3">
          <div className={cn(
            'w-10 h-10 rounded-xl flex items-center justify-center transition-transform duration-300 group-hover:scale-110',
            getMovementBgClass(transaction.movementType)
          )}>
            <MovementIcon type={transaction.movementType} />
          </div>
          <div className="min-w-0">
            <p className="text-sm font-mono text-neutral-900 truncate dark:text-neutral-50">{transaction.referenceNumber}</p>
            <p className="text-xs text-neutral-500 dark:text-neutral-400">{transaction.movementType.replace(/_/g, ' ')}</p>
          </div>
        </div>
      </td>
      <td className="px-4 lg:px-6 py-4 hidden md:table-cell">
        <div className="min-w-0">
          <p className="text-sm font-medium text-neutral-900 truncate dark:text-neutral-50">{transaction.vaName}</p>
          <p className="text-xs text-neutral-500 font-mono truncate dark:text-neutral-400">{transaction.vaNumber}</p>
        </div>
      </td>
      <td className="px-4 lg:px-6 py-4 hidden lg:table-cell">
        <div className="min-w-0">
          <p className="text-sm text-neutral-900 truncate dark:text-neutral-50">{transaction.counterpartyName || '-'}</p>
          {transaction.counterpartyAccount && (
            <p className="text-xs text-neutral-500 font-mono truncate max-w-48 dark:text-neutral-400">
              {transaction.counterpartyAccount}
            </p>
          )}
        </div>
      </td>
      <td className="px-4 lg:px-6 py-4">
        <p className={cn('text-sm font-semibold', getAmountColorClass(transaction.movementType))}>
          {isCreditTxn ? '+' : '-'}{formatCurrency(transaction.amount, transaction.currencyCode)}
        </p>
      </td>
      <td className="px-4 lg:px-6 py-4">
        <div className="flex items-center gap-2">
          <StatusIcon status={transaction.status} />
          <Badge variant={getStatusVariant(transaction.status)} size="sm">
            {transaction.status}
          </Badge>
        </div>
      </td>
      <td className="px-4 lg:px-6 py-4 hidden xl:table-cell">
        <div>
          <p className="text-sm text-neutral-900 dark:text-neutral-50">{formatRelativeTime(transaction.transactionDate)}</p>
          <p className="text-xs text-neutral-500 dark:text-neutral-400">Value: {transaction.valueDate ? formatDate(transaction.valueDate) : '-'}</p>
        </div>
      </td>
      <td className="px-4 lg:px-6 py-4 hidden xl:table-cell">
        <Badge variant="neutral" size="sm">{transaction.channel || 'N/A'}</Badge>
      </td>
      <td className="px-4 lg:px-6 py-4">
        <button
          className="p-2 hover:bg-neutral-100 rounded-xl transition-colors dark:hover:bg-primary-800"
          onClick={(e) => { e.stopPropagation(); onView(transaction); }}
        >
          <Eye className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
        </button>
      </td>
    </tr>
  );
};

// Mobile Transaction Card
const TransactionMobileCard: React.FC<TransactionRowProps> = ({ transaction, onView }) => {
  const isCreditTxn = isCredit(transaction.movementType);

  return (
    <Card
      className="animate-fade-in"
      interactive
      onClick={() => onView(transaction)}
    >
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3 min-w-0">
          <div className={cn(
            'w-11 h-11 rounded-xl flex items-center justify-center shrink-0',
            getMovementBgClass(transaction.movementType)
          )}>
            <MovementIcon type={transaction.movementType} />
          </div>
          <div className="min-w-0">
            <p className="text-sm font-medium text-neutral-900 truncate dark:text-neutral-50">{transaction.counterpartyName || transaction.vaName}</p>
            <p className="text-xs text-neutral-500 font-mono dark:text-neutral-400">{transaction.referenceNumber}</p>
          </div>
        </div>
        <div className="text-right shrink-0 ml-3">
          <p className={cn('text-sm font-semibold', getAmountColorClass(transaction.movementType))}>
            {isCreditTxn ? '+' : '-'}{formatCurrency(transaction.amount, transaction.currencyCode)}
          </p>
          <p className="text-xs text-neutral-500 dark:text-neutral-400">{formatRelativeTime(transaction.transactionDate)}</p>
        </div>
      </div>
      <div className="flex items-center justify-between mt-3 pt-3 border-t border-neutral-100 dark:border-primary-800/60">
        <div className="flex items-center gap-2">
          <StatusIcon status={transaction.status} />
          <Badge variant={getStatusVariant(transaction.status)} size="sm">
            {transaction.status}
          </Badge>
        </div>
        <Badge variant="neutral" size="sm">{transaction.channel || 'N/A'}</Badge>
      </div>
    </Card>
  );
};

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

// Grouped Transaction Row - Desktop
interface GroupedTransactionRowProps {
  transaction: GroupedTransaction;
  onView: (t: GroupedTransaction) => void;
  expanded: boolean;
  onToggleExpand: () => void;
}

const GroupedTransactionRow: React.FC<GroupedTransactionRowProps> = ({
  transaction,
  onView,
  expanded,
  onToggleExpand,
}) => {
  return (
    <>
      {/* Main Row */}
      <tr
        className="hover:bg-neutral-50 transition-colors cursor-pointer group dark:hover:bg-primary-800/50"
        onClick={() => onView(transaction)}
      >
        <td className="px-4 lg:px-6 py-4">
          <div className="flex items-center gap-3">
            <div className={cn(
              'w-10 h-10 rounded-xl flex items-center justify-center transition-transform duration-300 group-hover:scale-110',
              transaction.isCredit ? 'bg-success-50 dark:bg-success-500/10' : 'bg-error-50 dark:bg-error-500/10'
            )}>
              {transaction.isCredit ? (
                <ArrowDownLeft className="w-5 h-5 text-success-600 dark:text-success-300" />
              ) : (
                <ArrowUpRight className="w-5 h-5 text-error-600 dark:text-error-300" />
              )}
            </div>
            <div className="min-w-0">
              <div className="flex items-center gap-2">
                <p className="text-sm font-mono text-neutral-900 truncate dark:text-neutral-50">{transaction.primaryReferenceNumber}</p>
                <OperationTypeBadge type={transaction.operationType} />
              </div>
              <p className="text-xs text-neutral-500 dark:text-neutral-400">
                {transaction.entryCount} accounting {transaction.entryCount === 1 ? 'entry' : 'entries'}
              </p>
            </div>
          </div>
        </td>
        <td className="px-4 lg:px-6 py-4 hidden md:table-cell">
          <div className="min-w-0">
            <p className="text-sm font-medium text-neutral-900 truncate dark:text-neutral-50">{transaction.userVaName || '-'}</p>
            <p className="text-xs text-neutral-500 font-mono truncate dark:text-neutral-400">{transaction.userVaNumber}</p>
          </div>
        </td>
        <td className="px-4 lg:px-6 py-4 hidden lg:table-cell">
          <div className="min-w-0">
            <p className="text-sm text-neutral-900 truncate dark:text-neutral-50">{transaction.counterpartyName || '-'}</p>
            {transaction.counterpartyAccount && (
              <p className="text-xs text-neutral-500 font-mono truncate max-w-48 dark:text-neutral-400">
                {transaction.counterpartyAccount}
              </p>
            )}
          </div>
        </td>
        <td className="px-4 lg:px-6 py-4">
          <div>
            <p className={cn('text-sm font-semibold', transaction.isCredit ? 'text-success-600 dark:text-success-300' : 'text-error-600 dark:text-error-300')}>
              {transaction.isCredit ? '+' : '-'}{formatCurrency(transaction.netAmount, transaction.currencyCode)}
            </p>
            {transaction.feeAmount > 0 && (
              <p className="text-xs text-neutral-500 dark:text-neutral-400">
                Gross: {formatCurrency(transaction.grossAmount, transaction.currencyCode)} | Fee: {formatCurrency(transaction.feeAmount, transaction.currencyCode)}
              </p>
            )}
          </div>
        </td>
        <td className="px-4 lg:px-6 py-4">
          <div className="flex items-center gap-2">
            <StatusIcon status={transaction.status} />
            <Badge variant={getStatusVariant(transaction.status)} size="sm">
              {transaction.status}
            </Badge>
          </div>
        </td>
        <td className="px-4 lg:px-6 py-4 hidden xl:table-cell">
          <div>
            <p className="text-sm text-neutral-900 dark:text-neutral-50">{formatRelativeTime(transaction.transactionDate)}</p>
            <p className="text-xs text-neutral-500 dark:text-neutral-400">Value: {transaction.valueDate ? formatDate(transaction.valueDate) : '-'}</p>
          </div>
        </td>
        <td className="px-4 lg:px-6 py-4 hidden xl:table-cell">
          <Badge variant="neutral" size="sm">{transaction.channel || 'N/A'}</Badge>
        </td>
        <td className="px-4 lg:px-6 py-4">
          <div className="flex items-center gap-1">
            <button
              className="p-2 hover:bg-neutral-100 rounded-xl transition-colors dark:hover:bg-primary-800"
              onClick={(e) => { e.stopPropagation(); onToggleExpand(); }}
              title={expanded ? 'Hide accounting entries' : 'Show accounting entries'}
            >
              {expanded ? (
                <ChevronUp className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
              ) : (
                <ChevronDown className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
              )}
            </button>
            <button
              className="p-2 hover:bg-neutral-100 rounded-xl transition-colors dark:hover:bg-primary-800"
              onClick={(e) => { e.stopPropagation(); onView(transaction); }}
            >
              <Eye className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
            </button>
          </div>
        </td>
      </tr>

      {/* Expanded Accounting Entries */}
      {expanded && transaction.accountingEntries && (
        <tr>
          <td colSpan={8} className="px-4 lg:px-6 py-0">
            <div className="bg-neutral-50 rounded-xl p-4 mb-4 border border-neutral-200 dark:bg-primary-950 dark:border-primary-800">
              <div className="flex items-center gap-2 mb-3">
                <Layers className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
                <span className="field-label">Accounting Entries</span>
              </div>
              <div className="overflow-x-auto">
                <table className="w-full text-xs">
                  <thead>
                    <tr className="border-b border-neutral-200 dark:border-primary-800">
                      <th className="pb-2 text-left font-medium text-neutral-500 uppercase dark:text-neutral-400">Leg</th>
                      <th className="pb-2 text-left font-medium text-neutral-500 uppercase dark:text-neutral-400">Account</th>
                      <th className="pb-2 text-left font-medium text-neutral-500 uppercase dark:text-neutral-400">Type</th>
                      <th className="pb-2 text-left font-medium text-neutral-500 uppercase dark:text-neutral-400">Movement</th>
                      <th className="pb-2 text-right font-medium text-neutral-500 uppercase dark:text-neutral-400">Amount</th>
                      <th className="pb-2 text-right font-medium text-neutral-500 uppercase dark:text-neutral-400">Balance Before</th>
                      <th className="pb-2 text-right font-medium text-neutral-500 uppercase dark:text-neutral-400">Balance After</th>
                    </tr>
                  </thead>
                  <tbody>
                    {transaction.accountingEntries.map((entry, idx) => (
                      <tr key={idx} className="border-b border-neutral-100 last:border-0 dark:border-primary-800/60">
                        <td className="py-2 text-neutral-600 dark:text-neutral-300">#{entry.legNumber}</td>
                        <td className="py-2">
                          <div>
                            <span className="font-medium text-neutral-900 dark:text-neutral-50">{entry.vaName}</span>
                            <span className="text-neutral-500 ml-1 font-mono dark:text-neutral-400">({entry.vaNumber})</span>
                          </div>
                        </td>
                        <td className="py-2">
                          <Badge variant="neutral" size="sm">{entry.accountType}</Badge>
                        </td>
                        <td className="py-2">
                          <span className={cn(
                            'font-medium',
                            entry.movementType?.includes('CREDIT') ? 'text-success-600 dark:text-success-300' : 'text-error-600 dark:text-error-300'
                          )}>
                            {entry.movementType?.replace(/_/g, ' ')}
                          </span>
                        </td>
                        <td className="py-2 text-right font-mono font-medium text-neutral-900 dark:text-neutral-50">
                          {formatCurrency(entry.amount, entry.currencyCode)}
                        </td>
                        <td className="py-2 text-right font-mono text-neutral-500 dark:text-neutral-400">
                          {formatCurrency(entry.balanceBefore, entry.currencyCode)}
                        </td>
                        <td className="py-2 text-right font-mono text-neutral-900 dark:text-neutral-50">
                          {formatCurrency(entry.balanceAfter, entry.currencyCode)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {transaction.accountingEntries[0]?.processingNotes && (
                <div className="mt-3 pt-3 border-t border-neutral-200 dark:border-primary-800">
                  <p className="text-xs text-neutral-500 dark:text-neutral-400">
                    <span className="font-medium">Notes:</span> {transaction.accountingEntries[0].processingNotes}
                  </p>
                </div>
              )}
            </div>
          </td>
        </tr>
      )}
    </>
  );
};

// Grouped Transaction Mobile Card
const GroupedTransactionMobileCard: React.FC<{
  transaction: GroupedTransaction;
  onView: (t: GroupedTransaction) => void;
  expanded: boolean;
  onToggleExpand: () => void;
}> = ({ transaction, onView, expanded, onToggleExpand }) => {
  return (
    <Card className="animate-fade-in" interactive onClick={() => onView(transaction)}>
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-3 min-w-0">
          <div className={cn(
            'w-10 h-10 rounded-xl flex items-center justify-center shrink-0',
            transaction.isCredit ? 'bg-success-50 dark:bg-success-500/10' : 'bg-error-50 dark:bg-error-500/10'
          )}>
            {transaction.isCredit ? (
              <ArrowDownLeft className="w-5 h-5 text-success-600 dark:text-success-300" />
            ) : (
              <ArrowUpRight className="w-5 h-5 text-error-600 dark:text-error-300" />
            )}
          </div>
          <div className="min-w-0">
            <p className="text-sm font-mono text-neutral-900 truncate dark:text-neutral-50">{transaction.primaryReferenceNumber}</p>
            <OperationTypeBadge type={transaction.operationType} />
          </div>
        </div>
        <div className="text-right">
          <p className={cn('text-sm font-semibold', transaction.isCredit ? 'text-success-600 dark:text-success-300' : 'text-error-600 dark:text-error-300')}>
            {transaction.isCredit ? '+' : '-'}{formatCurrency(transaction.netAmount, transaction.currencyCode)}
          </p>
          {transaction.feeAmount > 0 && (
            <p className="text-xs text-neutral-500 dark:text-neutral-400">Fee: {formatCurrency(transaction.feeAmount, transaction.currencyCode)}</p>
          )}
        </div>
      </div>

      <div className="flex items-center justify-between text-xs text-neutral-500 mb-2 dark:text-neutral-400">
        <span className="truncate">{transaction.counterpartyName || transaction.userVaName}</span>
        <span>{formatRelativeTime(transaction.transactionDate)}</span>
      </div>

      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <StatusIcon status={transaction.status} />
          <Badge variant={getStatusVariant(transaction.status)} size="sm">
            {transaction.status}
          </Badge>
        </div>
        <button
          className="flex items-center gap-1 text-xs text-primary-600 hover:text-primary-700 dark:text-primary-200 dark:hover:text-neutral-200"
          onClick={(e) => { e.stopPropagation(); onToggleExpand(); }}
        >
          <Layers className="w-3 h-3" />
          {expanded ? 'Hide' : 'Show'} {transaction.entryCount} entries
        </button>
      </div>

      {/* Expanded Entries */}
      {expanded && transaction.accountingEntries && (
        <div className="mt-4 pt-4 border-t border-neutral-200 dark:border-primary-800">
          <p className="text-xs font-medium text-neutral-700 mb-2 dark:text-neutral-200">Accounting Entries:</p>
          <div className="space-y-2">
            {transaction.accountingEntries.map((entry, idx) => (
              <div key={idx} className="flex items-center justify-between text-xs bg-neutral-50 rounded-lg p-2 dark:bg-primary-950">
                <div>
                  <span className="font-medium text-neutral-900 dark:text-neutral-50">#{entry.legNumber} {entry.vaName}</span>
                  <span className="text-neutral-500 ml-1 dark:text-neutral-400">({entry.accountType})</span>
                </div>
                <div className={cn(
                  'font-mono font-medium',
                  entry.movementType?.includes('CREDIT') ? 'text-success-600 dark:text-success-300' : 'text-error-600 dark:text-error-300'
                )}>
                  {formatCurrency(entry.amount, entry.currencyCode)}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </Card>
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

  // Fetch grouped transaction data when modal opens and transaction has correlationId
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
          transaction.vaId
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
  }, [transaction]);

  if (!transaction) return null;

  const isCreditTxn = isCredit(transaction.movementType);
  const hasAccountingEntries = !!transaction.correlationId;

  // Helper to get account type badge color
  const getAccountTypeBadgeClass = (accountType: string) => {
    switch (accountType) {
      case 'SHADOW':
      case 'PHYSICAL_MIRROR':
        return 'bg-purple-100 text-purple-700 dark:bg-purple-500/20 dark:text-purple-300';
      case 'SETTLEMENT':
        return 'bg-info-100 text-info-700 dark:bg-info-500/20 dark:text-info-300';
      case 'OPERATING':
        return 'bg-success-100 text-success-700 dark:bg-success-500/20 dark:text-success-300';
      case 'EXCEPTION':
        return 'bg-amber-100 text-amber-700 dark:bg-amber-500/20 dark:text-amber-300';
      default:
        return 'bg-neutral-100 text-neutral-700 dark:bg-primary-800 dark:text-neutral-200';
    }
  };

  // Helper to get movement type badge
  const getMovementTypeBadgeClass = (movementType: string) => {
    if (movementType.includes('CREDIT') || movementType === 'TRANSFER_IN' || movementType === 'ROBO_CREDIT') {
      return 'bg-emerald-100 text-emerald-700 dark:bg-emerald-500/20 dark:text-emerald-300';
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
              'w-14 h-14 rounded-2xl flex items-center justify-center',
              getMovementBgClass(transaction.movementType)
            )}>
              <MovementIcon type={transaction.movementType} className="w-7 h-7" />
            </div>
            <div>
              <p className="section-title">{transaction.referenceNumber}</p>
              <p className="text-sm text-neutral-500 dark:text-neutral-400">{transaction.movementType.replace(/_/g, ' ')}</p>
            </div>
          </div>
          <Badge variant={getStatusVariant(transaction.status)} size="md">{transaction.status}</Badge>
        </div>

        {/* Amount */}
        <div className="bg-gradient-to-br from-neutral-50 to-white rounded-2xl p-6 text-center border border-neutral-100 dark:border-primary-800/60 dark:from-primary-950 dark:to-primary-900">
          <p className="text-sm text-neutral-500 mb-2 dark:text-neutral-400">Amount</p>
          {/* Phase 9.1 Task B: hero amount uses .stat-value (Fraunces 36px/600).
              The conditional tone class wins by cascade order — it sits in
              the @layer utilities while .stat-value's text-primary-900 sits
              in @layer base, so the dynamic color override is preserved. */}
          <p className={cn('stat-value', getAmountColorClass(transaction.movementType))}>
            {isCreditTxn ? '+' : '-'}{formatCurrency(transaction.amount, transaction.currencyCode)}
          </p>
        </div>

        {/* Tab Navigation - only show if there are accounting entries */}
        {hasAccountingEntries && (
          <div className="flex gap-1 p-1 bg-neutral-100 rounded-lg dark:bg-primary-800">
            <button
              onClick={() => setActiveTab('details')}
              className={cn(
                'flex-1 px-4 py-2 rounded-md text-sm font-medium transition-all',
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
                'flex-1 px-4 py-2 rounded-md text-sm font-medium transition-all',
                activeTab === 'accounting'
                  ? 'bg-white text-neutral-900 shadow-sm dark:bg-primary-900 dark:text-neutral-50'
                  : 'text-neutral-600 hover:text-neutral-900 dark:text-neutral-300 dark:hover:text-neutral-50'
              )}
            >
              <Layers className="w-4 h-4 inline mr-2" />
              Accounting Entries
              {groupedData?.entryCount && (
                <span className="ml-1.5 px-1.5 py-0.5 text-xs bg-neutral-200 rounded-full dark:bg-primary-800">
                  {groupedData.entryCount}
                </span>
              )}
            </button>
          </div>
        )}

        {/* Details Tab Content */}
        {activeTab === 'details' && (
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div className="bg-neutral-50 rounded-xl p-4 dark:bg-primary-950">
              <p className="text-xs text-neutral-500 mb-1.5 dark:text-neutral-400">Virtual Account</p>
              <p className="font-medium text-neutral-900 dark:text-neutral-50">{transaction.vaName}</p>
              <p className="text-sm text-neutral-500 font-mono mt-0.5 dark:text-neutral-400">{transaction.vaNumber}</p>
            </div>
            <div className="bg-neutral-50 rounded-xl p-4 dark:bg-primary-950">
              <p className="text-xs text-neutral-500 mb-1.5 dark:text-neutral-400">Counterparty</p>
              <p className="font-medium text-neutral-900 dark:text-neutral-50">{transaction.counterpartyName || '-'}</p>
              {transaction.counterpartyAccount && (
                <p className="text-sm text-neutral-500 font-mono mt-0.5 truncate dark:text-neutral-400">{transaction.counterpartyAccount}</p>
              )}
            </div>
            <div className="bg-neutral-50 rounded-xl p-4 dark:bg-primary-950">
              <p className="text-xs text-neutral-500 mb-1.5 dark:text-neutral-400">Transaction Date</p>
              <p className="font-medium text-neutral-900 dark:text-neutral-50">{formatDate(transaction.transactionDate)}</p>
            </div>
            <div className="bg-neutral-50 rounded-xl p-4 dark:bg-primary-950">
              <p className="text-xs text-neutral-500 mb-1.5 dark:text-neutral-400">Value Date</p>
              <p className="font-medium text-neutral-900 dark:text-neutral-50">{transaction.valueDate ? formatDate(transaction.valueDate) : '-'}</p>
            </div>
            <div className="bg-neutral-50 rounded-xl p-4 dark:bg-primary-950">
              <p className="text-xs text-neutral-500 mb-1.5 dark:text-neutral-400">Balance Before</p>
              <p className="font-medium text-neutral-900 dark:text-neutral-50">{formatCurrency(transaction.balanceBefore, transaction.currencyCode)}</p>
            </div>
            <div className="bg-neutral-50 rounded-xl p-4 dark:bg-primary-950">
              <p className="text-xs text-neutral-500 mb-1.5 dark:text-neutral-400">Balance After</p>
              <p className="font-medium text-neutral-900 dark:text-neutral-50">{formatCurrency(transaction.balanceAfter, transaction.currencyCode)}</p>
            </div>
            <div className="sm:col-span-2 bg-neutral-50 rounded-xl p-4 dark:bg-primary-950">
              <p className="text-xs text-neutral-500 mb-1.5 dark:text-neutral-400">Description</p>
              <p className="font-medium text-neutral-900 dark:text-neutral-50">{transaction.description || '-'}</p>
            </div>
            <div className="bg-neutral-50 rounded-xl p-4 dark:bg-primary-950">
              <p className="text-xs text-neutral-500 mb-1.5 dark:text-neutral-400">Channel</p>
              <Badge variant="neutral">{transaction.channel || 'N/A'}</Badge>
            </div>
            {transaction.externalReference && (
              <div className="bg-neutral-50 rounded-xl p-4 dark:bg-primary-950">
                <p className="text-xs text-neutral-500 mb-1.5 dark:text-neutral-400">External Reference</p>
                <p className="font-medium text-neutral-900 font-mono text-sm dark:text-neutral-50">{transaction.externalReference}</p>
              </div>
            )}
            {transaction.correlationId && (
              <div className="bg-neutral-50 rounded-xl p-4 dark:bg-primary-950">
                <p className="text-xs text-neutral-500 mb-1.5 dark:text-neutral-400">Correlation ID</p>
                <p className="font-medium text-neutral-900 font-mono text-xs truncate dark:text-neutral-50">{transaction.correlationId}</p>
              </div>
            )}
          </div>
        )}

        {/* Accounting Entries Tab Content */}
        {activeTab === 'accounting' && (
          <div className="space-y-4">
            {loadingEntries ? (
              <div className="space-y-3">
                {[1, 2, 3, 4].map((i) => (
                  <div key={i} className="bg-neutral-50 rounded-xl p-4 animate-pulse dark:bg-primary-950">
                    <div className="h-4 bg-neutral-200 rounded w-1/4 mb-2 dark:bg-primary-800" />
                    <div className="h-3 bg-neutral-200 rounded w-3/4 dark:bg-primary-800" />
                  </div>
                ))}
              </div>
            ) : groupedData?.accountingEntries && groupedData.accountingEntries.length > 0 ? (
              <>
                {/* Summary Header */}
                <div className="bg-gradient-to-r from-info-50 to-indigo-50 rounded-xl p-4 border border-info-100 dark:border-info-500/30 dark:from-info-500/15 dark:to-indigo-500/15">
                  <div className="flex items-center justify-between mb-3">
                    <div className="flex items-center gap-2">
                      <Info className="w-4 h-4 text-info-600 dark:text-info-300" />
                      <span className="text-sm font-medium text-info-900">
                        Multi-Leg Transaction • {groupedData.operationType.replace(/_/g, ' ')}
                      </span>
                    </div>
                    <Badge variant={groupedData.direction === 'INBOUND' ? 'success' : groupedData.direction === 'OUTBOUND' ? 'error' : 'neutral'}>
                      {groupedData.direction}
                    </Badge>
                  </div>
                  <p className="text-xs text-info-700 dark:text-info-300">
                    This transaction is part of a {groupedData.entryCount}-leg accounting operation.
                    Each leg represents a balance movement in the virtual account structure.
                  </p>
                </div>

                {/* Accounting Entries List */}
                <div className="space-y-3">
                  {groupedData.accountingEntries.map((entry) => {
                    const entryIsCredit = entry.movementType.includes('CREDIT') ||
                                         entry.movementType === 'TRANSFER_IN' ||
                                         entry.movementType === 'ROBO_CREDIT';
                    return (
                      <div
                        key={entry.transactionId}
                        className={cn(
                          'rounded-xl p-4 border transition-all',
                          entry.transactionId === transaction.id
                            ? 'bg-info-50 border-info-200 ring-2 ring-info-100 dark:bg-info-500/10 dark:border-info-500/30'
                            : 'bg-neutral-50 border-neutral-100 hover:border-neutral-200 dark:bg-primary-950 dark:border-primary-800/60'
                        )}
                      >
                        <div className="flex items-start justify-between gap-4">
                          {/* Left: Leg info */}
                          <div className="flex items-start gap-3">
                            <div className={cn(
                              'w-8 h-8 rounded-lg flex items-center justify-center text-sm font-bold',
                              entryIsCredit ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-500/20 dark:text-emerald-300' : 'bg-rose-100 text-rose-700 dark:bg-rose-500/20 dark:text-rose-300'
                            )}>
                              {entry.legNumber}
                            </div>
                            <div>
                              <div className="flex items-center gap-2 mb-1">
                                <span className="font-medium text-neutral-900 dark:text-neutral-50">{entry.vaName}</span>
                                {entry.transactionId === transaction.id && (
                                  <span className="text-xs px-1.5 py-0.5 bg-info-200 text-info-800 rounded dark:text-info-300">
                                    Current
                                  </span>
                                )}
                              </div>
                              <p className="text-sm text-neutral-500 font-mono dark:text-neutral-400">{entry.vaNumber}</p>
                              <div className="flex items-center gap-2 mt-2">
                                <span className={cn(
                                  'text-xs px-2 py-0.5 rounded-full font-medium',
                                  getAccountTypeBadgeClass(entry.accountType)
                                )}>
                                  {entry.accountType.replace(/_/g, ' ')}
                                </span>
                                <span className={cn(
                                  'text-xs px-2 py-0.5 rounded-full font-medium',
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
                              'text-lg font-bold',
                              entryIsCredit ? 'text-emerald-600 dark:text-emerald-300' : 'text-rose-600 dark:text-rose-300'
                            )}>
                              {entryIsCredit ? '+' : '-'}{formatCurrency(entry.amount, entry.currencyCode)}
                            </p>
                            <div className="text-xs text-neutral-500 mt-1 space-y-0.5 dark:text-neutral-400">
                              <div className="flex items-center justify-end gap-1">
                                <span>Before:</span>
                                <span className="font-mono">{formatCurrency(entry.balanceBefore, entry.currencyCode)}</span>
                              </div>
                              <div className="flex items-center justify-end gap-1">
                                <span>After:</span>
                                <span className="font-mono font-medium">{formatCurrency(entry.balanceAfter, entry.currencyCode)}</span>
                              </div>
                            </div>
                          </div>
                        </div>

                        {/* Description if available */}
                        {entry.description && (
                          <p className="text-xs text-neutral-500 mt-3 pt-3 border-t border-neutral-200 dark:text-neutral-400 dark:border-primary-800">
                            {entry.description}
                          </p>
                        )}
                      </div>
                    );
                  })}
                </div>

                {/* Totals Summary */}
                {groupedData.feeAmount > 0 && (
                  <div className="bg-amber-50 rounded-xl p-4 border border-amber-100 dark:bg-amber-500/10 dark:border-amber-500/30">
                    <div className="flex items-center justify-between">
                      <span className="text-sm text-amber-800 dark:text-amber-300">Fee Applied</span>
                      <span className="font-bold text-amber-900">
                        {formatCurrency(groupedData.feeAmount, groupedData.currencyCode)}
                      </span>
                    </div>
                  </div>
                )}
              </>
            ) : (
              <div className="text-center py-8 text-neutral-500 dark:text-neutral-400">
                <Layers className="w-12 h-12 mx-auto mb-3 text-neutral-300 dark:text-neutral-600" />
                <p className="font-medium">No accounting entries found</p>
                <p className="text-sm mt-1">This transaction may not be part of a multi-leg operation</p>
              </div>
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
                  'px-4 py-3 rounded-xl text-sm font-medium transition-all duration-200',
                  type === t
                    ? 'bg-primary-100 text-primary-900 border-2 border-primary-500 shadow-sm dark:bg-primary-700 dark:text-neutral-50'
                    : 'bg-neutral-100 text-neutral-600 border-2 border-transparent hover:bg-neutral-200 dark:bg-primary-800 dark:text-neutral-300'
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
        <div className="bg-gradient-to-r from-info-50 to-indigo-50 rounded-2xl p-4 border border-info-100 dark:border-info-500/30 dark:from-info-500/15 dark:to-indigo-500/15">
          <div className="flex items-center gap-3 mb-4">
            <StatusIconBadge tone="info" icon={FileCode} className="dark:bg-info-500/20" />
            <div>
              <h3 className="font-semibold text-neutral-900 dark:text-neutral-50">ISO 20022 Message Simulation</h3>
              <p className="text-xs text-neutral-500 dark:text-neutral-400">Simulate incoming payment messages as if received from SWIFT/Clearing</p>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <button
              type="button"
              onClick={() => setMessageType('pacs008')}
              className={cn(
                'p-4 rounded-xl border-2 transition-all duration-200 text-left',
                messageType === 'pacs008'
                  ? 'border-info-500 bg-info-50 shadow-sm dark:bg-info-500/10'
                  : 'border-neutral-200 bg-white hover:border-neutral-300 dark:border-primary-800 dark:bg-primary-900 dark:hover:border-primary-700'
              )}
            >
              <div className="flex items-center gap-2 mb-2">
                <Badge variant={messageType === 'pacs008' ? 'info' : 'neutral'} size="sm">pacs.008</Badge>
                <span className="text-xs text-neutral-500 dark:text-neutral-400">FIToFI Customer Credit Transfer</span>
              </div>
              <p className="text-xs text-neutral-600 dark:text-neutral-300">
                Standard SWIFT payment message for incoming cross-border or domestic payments
              </p>
            </button>
            <button
              type="button"
              onClick={() => setMessageType('camt054')}
              className={cn(
                'p-4 rounded-xl border-2 transition-all duration-200 text-left',
                messageType === 'camt054'
                  ? 'border-purple-500 bg-purple-50 shadow-sm dark:bg-purple-500/10'
                  : 'border-neutral-200 bg-white hover:border-neutral-300 dark:border-primary-800 dark:bg-primary-900 dark:hover:border-primary-700'
              )}
            >
              <div className="flex items-center gap-2 mb-2">
                <Badge variant={messageType === 'camt054' ? 'info' : 'neutral'} size="sm">camt.054</Badge>
                <span className="text-xs text-neutral-500 dark:text-neutral-400">Bank to Customer Debit/Credit Notification</span>
              </div>
              <p className="text-xs text-neutral-600 dark:text-neutral-300">
                Bank statement notification message for collection/credit advice
              </p>
            </button>
          </div>
        </div>

        {/* Group Header Section */}
        <div className="border border-neutral-200 rounded-xl overflow-hidden dark:border-primary-800">
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
        <div className="border border-neutral-200 rounded-xl overflow-hidden dark:border-primary-800">
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
        <div className="border border-neutral-200 rounded-xl overflow-hidden dark:border-primary-800">
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
        <div className="border border-neutral-200 rounded-xl overflow-hidden dark:border-primary-800">
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
                  'flex-1 px-4 py-2.5 rounded-lg text-sm font-medium transition-all duration-200 flex items-center justify-center gap-2',
                  creditorMode === 'viban'
                    ? 'bg-primary-100 text-primary-900 border-2 border-primary-500 dark:bg-primary-700 dark:text-neutral-50'
                    : 'bg-neutral-100 text-neutral-600 border-2 border-transparent hover:bg-neutral-200 dark:bg-primary-800 dark:text-neutral-300'
                )}
              >
                <CreditCard className="w-4 h-4" />
                Enter VIBAN
              </button>
              <button
                type="button"
                onClick={() => setCreditorMode('select')}
                className={cn(
                  'flex-1 px-4 py-2.5 rounded-lg text-sm font-medium transition-all duration-200 flex items-center justify-center gap-2',
                  creditorMode === 'select'
                    ? 'bg-primary-100 text-primary-900 border-2 border-primary-500 dark:bg-primary-700 dark:text-neutral-50'
                    : 'bg-neutral-100 text-neutral-600 border-2 border-transparent hover:bg-neutral-200 dark:bg-primary-800 dark:text-neutral-300'
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
                  <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">
                    Enter the VIBAN as it appears on the incoming payment message
                  </p>
                </div>

                {/* VIBAN Lookup Error */}
                {vibanLookupError && (
                  <div className="flex items-start gap-2 p-3 bg-error-50 border border-error-100 rounded-lg dark:bg-error-500/10 dark:border-error-500/30">
                    <XCircle className="w-4 h-4 text-error-600 mt-0.5 dark:text-error-300" />
                    <div className="text-sm text-error-700 dark:text-error-300">{vibanLookupError}</div>
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
              <div className="mt-4 p-3 bg-success-50 border border-success-100 rounded-xl dark:bg-success-500/10 dark:border-success-500/30">
                <div className="flex items-center gap-2 mb-2">
                  <CheckCircle className="w-4 h-4 text-success-600 dark:text-success-300" />
                  <span className="text-sm font-medium text-success-800 dark:text-success-300">
                    {creditorMode === 'viban' ? 'VIBAN Resolved to Account' : 'Selected Account'}
                  </span>
                </div>
                <div className="grid grid-cols-2 gap-2 text-xs">
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
        <div className="border border-neutral-200 rounded-xl overflow-hidden dark:border-primary-800">
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
        <div className="flex items-start gap-3 p-4 bg-amber-50 border border-amber-100 rounded-xl dark:bg-amber-500/10 dark:border-amber-500/30">
          <Info className="w-5 h-5 text-amber-600 shrink-0 mt-0.5 dark:text-amber-300" />
          <div className="text-sm text-amber-800 dark:text-amber-300">
            <p className="font-medium mb-1">Simulation Mode</p>
            <p className="text-xs text-amber-700 dark:text-amber-300">
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
  const [activeTab, setActiveTab] = useState('all');
  const [currentPage, setCurrentPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
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
  const [expandedCorrelationIds, setExpandedCorrelationIds] = useState<Set<string>>(new Set());
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const [selectedGroupedTransaction, setSelectedGroupedTransaction] = useState<GroupedTransaction | null>(null);

  const pageSize = 10;

  // Fetch data
  const fetchData = useCallback(async () => {
    setLoading(true);
    try {
      // Fetch stats
      const statsRes = await transactionsApi.getStats();
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
            direction: direction,
            page: currentPage,
            pageSize,
          });

          if (groupedRes.success && groupedRes.data) {
            setGroupedTransactions(groupedRes.data.content || []);
            setTotalPages(groupedRes.data.totalPages || 1);
            setTotalElements(groupedRes.data.totalElements || 0);
          } else {
            setGroupedTransactions([]);
            setTotalPages(1);
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
          setTotalPages(txnRes.data.totalPages || 1);
          setTotalElements(txnRes.data.totalElements || 0);
        }
      } else {
        // Use demo data on failure
        setTransactions(demoTransactions);
        if (viewMode === 'individual') {
          setTotalPages(1);
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
  }, [currentPage, searchQuery, activeTab, filters, viewMode]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

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
  const handleReverse = async (id: string) => {
    if (window.confirm('Are you sure you want to reverse this transaction?')) {
      try {
        await transactionsApi.reverse(id, 'User requested reversal');
        setSelectedTransaction(null);
        fetchData();
      } catch (error) {
        console.error('Reversal failed:', error);
      }
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

  // Toggle expanded state for a grouped transaction
  const toggleExpanded = (correlationId: string) => {
    setExpandedCorrelationIds((prev) => {
      const newSet = new Set(prev);
      if (newSet.has(correlationId)) {
        newSet.delete(correlationId);
      } else {
        newSet.add(correlationId);
      }
      return newSet;
    });
  };

  // Handle view of grouped transaction (fetch full details)
  const handleViewGroupedTransaction = async (txn: GroupedTransaction) => {
    try {
      const res = await transactionsApi.getGroupedByCorrelationId(txn.correlationId);
      if (res.success && res.data) {
        setSelectedGroupedTransaction(res.data);
      }
    } catch (error) {
      console.error('Failed to fetch grouped transaction details:', error);
      // Fallback to showing what we have
      setSelectedGroupedTransaction(txn);
    }
  };

  // Tabs configuration
  const tabs = [
    { id: 'all', label: 'All', count: totalElements },
    { id: 'credit', label: 'Credits', count: stats?.completedCount || 0 },
    { id: 'debit', label: 'Debits', count: stats?.completedCount || 0 },
    { id: 'pending', label: 'Pending', count: stats?.pendingCount || 0 },
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
          leftIcon={<Download className="w-4 h-4" />}
          className="hidden sm:inline-flex"
        >
          Export
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

      {/* Stats Cards */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3 lg:gap-4">
        <StatCard
          title="Credits Today"
          value={`+${formatCurrency(stats?.todayCredits || 0)}`}
          icon={<ArrowDownLeft className="w-5 h-5 text-success-600 dark:text-success-300" />}
          variant="success"
          loading={loading}
        />
        <StatCard
          title="Debits Today"
          value={`-${formatCurrency(stats?.todayDebits || 0)}`}
          icon={<ArrowUpRight className="w-5 h-5 text-error-600 dark:text-error-300" />}
          variant="error"
          loading={loading}
        />
        <StatCard
          title="Pending"
          value={stats?.pendingCount || 0}
          icon={<Clock className="w-5 h-5 text-warning-600 dark:text-warning-300" />}
          variant="warning"
          loading={loading}
        />
        <StatCard
          title="Today's Total"
          value={stats?.todayCount || 0}
          icon={<ArrowLeftRight className="w-5 h-5 text-neutral-600 dark:text-neutral-300" />}
          variant="neutral"
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
                  'px-4 py-2.5 rounded-xl text-sm font-medium transition-all duration-200 whitespace-nowrap',
                  activeTab === tab.id
                    ? 'bg-primary-100 text-primary-900 dark:bg-primary-700 dark:text-neutral-50'
                    : 'text-neutral-600 hover:bg-neutral-100 dark:text-neutral-300 dark:hover:bg-primary-800'
                )}
              >
                {tab.label}
                <span className={cn(
                  'ml-2 px-2 py-0.5 rounded-full text-xs font-semibold',
                  activeTab === tab.id ? 'bg-primary-200 text-primary-800 dark:text-neutral-100' : 'bg-neutral-200 text-neutral-600 dark:bg-primary-800 dark:text-neutral-300'
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
              <div className="flex items-center bg-neutral-100 rounded-xl p-1 dark:bg-primary-800">
                <button
                  className={cn(
                    'px-3 py-1.5 rounded-lg text-xs font-medium transition-all duration-200 flex items-center gap-1.5',
                    viewMode === 'grouped'
                      ? 'bg-white text-primary-700 shadow-sm dark:bg-primary-900 dark:text-neutral-200'
                      : 'text-neutral-600 hover:text-neutral-900 dark:text-neutral-300 dark:hover:text-neutral-50'
                  )}
                  onClick={() => { setViewMode('grouped'); setCurrentPage(0); }}
                  title="Business View - Groups multi-leg transactions"
                >
                  <Layers className="w-3.5 h-3.5" />
                  <span className="hidden sm:inline">Business</span>
                </button>
                <button
                  className={cn(
                    'px-3 py-1.5 rounded-lg text-xs font-medium transition-all duration-200 flex items-center gap-1.5',
                    viewMode === 'individual'
                      ? 'bg-white text-primary-700 shadow-sm dark:bg-primary-900 dark:text-neutral-200'
                      : 'text-neutral-600 hover:text-neutral-900 dark:text-neutral-300 dark:hover:text-neutral-50'
                  )}
                  onClick={() => { setViewMode('individual'); setCurrentPage(0); }}
                  title="Ledger View - Shows all individual entries"
                >
                  <List className="w-3.5 h-3.5" />
                  <span className="hidden sm:inline">Ledger</span>
                </button>
              </div>
              <Button
                variant="outline"
                leftIcon={<Filter className="w-4 h-4" />}
                className="shrink-0"
              >
                <span className="hidden sm:inline">Filters</span>
              </Button>
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
          <div className="mx-4 mt-4 flex items-start gap-2 p-3 bg-info-50 border border-info-100 rounded-xl dark:bg-info-500/10 dark:border-info-500/30">
            <Info className="w-4 h-4 text-info-600 shrink-0 mt-0.5 dark:text-info-300" />
            <div className="text-xs text-info-800 dark:text-info-300">
              <span className="font-medium">Business View:</span> Multi-leg accounting entries are grouped into single business transactions.
              Click the expand button to view all accounting entries.
            </div>
          </div>
        )}

        {/* Table - Desktop */}
        {loading ? (
          <div className="p-8">
            <div className="space-y-4">
              {[1, 2, 3, 4, 5].map((i) => (
                <div key={i} className="flex items-center gap-4">
                  <Skeleton className="w-10 h-10 rounded-xl" />
                  <div className="flex-1 space-y-2">
                    <Skeleton className="h-4 w-1/3" />
                    <Skeleton className="h-3 w-1/4" />
                  </div>
                  <Skeleton className="h-6 w-20" />
                </div>
              ))}
            </div>
          </div>
        ) : viewMode === 'grouped' && groupedTransactions.length > 0 ? (
          <>
            {/* Grouped View - Desktop Table */}
            <div className="hidden md:block overflow-x-auto">
              <table className="w-full">
                <thead className="bg-neutral-50/80 border-b border-neutral-200 dark:border-primary-800">
                  <tr>
                    <th className="px-4 lg:px-6 py-3.5 text-left text-xs font-semibold text-neutral-500 uppercase tracking-wider dark:text-neutral-400">Operation</th>
                    <th className="px-4 lg:px-6 py-3.5 text-left text-xs font-semibold text-neutral-500 uppercase tracking-wider hidden md:table-cell dark:text-neutral-400">Account</th>
                    <th className="px-4 lg:px-6 py-3.5 text-left text-xs font-semibold text-neutral-500 uppercase tracking-wider hidden lg:table-cell dark:text-neutral-400">Counterparty</th>
                    <th className="px-4 lg:px-6 py-3.5 text-left text-xs font-semibold text-neutral-500 uppercase tracking-wider dark:text-neutral-400">Net Amount</th>
                    <th className="px-4 lg:px-6 py-3.5 text-left text-xs font-semibold text-neutral-500 uppercase tracking-wider dark:text-neutral-400">Status</th>
                    <th className="px-4 lg:px-6 py-3.5 text-left text-xs font-semibold text-neutral-500 uppercase tracking-wider hidden xl:table-cell dark:text-neutral-400">Date</th>
                    <th className="px-4 lg:px-6 py-3.5 text-left text-xs font-semibold text-neutral-500 uppercase tracking-wider hidden xl:table-cell dark:text-neutral-400">Channel</th>
                    <th className="px-4 lg:px-6 py-3.5 text-left text-xs font-semibold text-neutral-500 uppercase tracking-wider w-20 dark:text-neutral-400">Actions</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-neutral-100 dark:divide-primary-800/60">
                  {groupedTransactions.map((txn) => (
                    <GroupedTransactionRow
                      key={txn.correlationId}
                      transaction={txn}
                      onView={handleViewGroupedTransaction}
                      expanded={expandedCorrelationIds.has(txn.correlationId)}
                      onToggleExpand={() => toggleExpanded(txn.correlationId)}
                    />
                  ))}
                </tbody>
              </table>
            </div>

            {/* Grouped View - Mobile Cards */}
            <div className="md:hidden p-4 space-y-3">
              {groupedTransactions.map((txn) => (
                <GroupedTransactionMobileCard
                  key={txn.correlationId}
                  transaction={txn}
                  onView={handleViewGroupedTransaction}
                  expanded={expandedCorrelationIds.has(txn.correlationId)}
                  onToggleExpand={() => toggleExpanded(txn.correlationId)}
                />
              ))}
            </div>
          </>
        ) : transactions.length > 0 ? (
          <>
            {/* Individual/Ledger View - Desktop Table */}
            <div className="hidden md:block overflow-x-auto">
              <table className="w-full">
                <thead className="bg-neutral-50/80 border-b border-neutral-200 dark:border-primary-800">
                  <tr>
                    <th className="px-4 lg:px-6 py-3.5 text-left text-xs font-semibold text-neutral-500 uppercase tracking-wider dark:text-neutral-400">Reference</th>
                    <th className="px-4 lg:px-6 py-3.5 text-left text-xs font-semibold text-neutral-500 uppercase tracking-wider hidden md:table-cell dark:text-neutral-400">Account</th>
                    <th className="px-4 lg:px-6 py-3.5 text-left text-xs font-semibold text-neutral-500 uppercase tracking-wider hidden lg:table-cell dark:text-neutral-400">Counterparty</th>
                    <th className="px-4 lg:px-6 py-3.5 text-left text-xs font-semibold text-neutral-500 uppercase tracking-wider dark:text-neutral-400">Amount</th>
                    <th className="px-4 lg:px-6 py-3.5 text-left text-xs font-semibold text-neutral-500 uppercase tracking-wider dark:text-neutral-400">Status</th>
                    <th className="px-4 lg:px-6 py-3.5 text-left text-xs font-semibold text-neutral-500 uppercase tracking-wider hidden xl:table-cell dark:text-neutral-400">Date</th>
                    <th className="px-4 lg:px-6 py-3.5 text-left text-xs font-semibold text-neutral-500 uppercase tracking-wider hidden xl:table-cell dark:text-neutral-400">Channel</th>
                    <th className="px-4 lg:px-6 py-3.5 text-left text-xs font-semibold text-neutral-500 uppercase tracking-wider w-16 dark:text-neutral-400">Actions</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-neutral-100 dark:divide-primary-800/60">
                  {transactions.map((transaction) => (
                    <TransactionRow
                      key={transaction.id}
                      transaction={transaction}
                      onView={setSelectedTransaction}
                    />
                  ))}
                </tbody>
              </table>
            </div>

            {/* Individual/Ledger View - Mobile Cards */}
            <div className="md:hidden p-4 space-y-3">
              {transactions.map((transaction) => (
                <TransactionMobileCard
                  key={transaction.id}
                  transaction={transaction}
                  onView={setSelectedTransaction}
                />
              ))}
            </div>

            {/* Pagination */}
            <div className="flex flex-col sm:flex-row items-center justify-between px-4 lg:px-6 py-4 border-t border-neutral-200 gap-4 dark:border-primary-800">
              <p className="text-sm text-neutral-500 text-center sm:text-left dark:text-neutral-400">
                Showing {currentPage * pageSize + 1} to{' '}
                {Math.min((currentPage + 1) * pageSize, totalElements)} of{' '}
                {totalElements} transactions
              </p>
              <div className="flex items-center gap-1">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={currentPage === 0}
                  onClick={() => setCurrentPage(currentPage - 1)}
                >
                  <ChevronLeft className="w-4 h-4" />
                </Button>
                <div className="hidden sm:flex items-center gap-1">
                  {Array.from({ length: Math.min(totalPages, 5) }, (_, i) => i).map((page) => (
                    <Button
                      key={page}
                      variant={currentPage === page ? 'primary' : 'ghost'}
                      size="sm"
                      onClick={() => setCurrentPage(page)}
                    >
                      {page + 1}
                    </Button>
                  ))}
                </div>
                <span className="sm:hidden text-sm text-neutral-500 px-2 dark:text-neutral-400">
                  {currentPage + 1} / {totalPages}
                </span>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={currentPage >= totalPages - 1}
                  onClick={() => setCurrentPage(currentPage + 1)}
                >
                  <ChevronRight className="w-4 h-4" />
                </Button>
              </div>
            </div>
          </>
        ) : (
          <div className="p-8">
            <EmptyState
              icon={<ArrowLeftRight className="w-8 h-8" />}
              title="No transactions found"
              description="Try adjusting your search or filters."
              action={
                <Button variant="outline" onClick={() => { setSearchQuery(''); setActiveTab('all'); }}>
                  Clear Filters
                </Button>
              }
            />
          </div>
        )}
      </Card>

      {/* Transaction Details Modal */}
      <TransactionDetails
        transaction={selectedTransaction}
        onClose={() => setSelectedTransaction(null)}
        onReverse={handleReverse}
      />

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