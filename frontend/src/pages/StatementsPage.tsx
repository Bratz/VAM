// ============================================================================
// STATEMENTS PAGE - PREMIUM DESIGN SYSTEM (ISO20022 Enhanced)
// ============================================================================
// Supports:
// - ISO20022 camt.053 (Bank to Customer Statement)
// - ISO20022 camt.054 (Bank to Customer Debit/Credit Notification)
// - Full ISO20022 field display (Group Header, Statement Header, Balances, Entries)
// - Aggregated statements for parent/child VA hierarchies
// - XML export for ERP/treasury system integration
// - Expandable entry details with transaction references
// - Color coding for credits/debits
// - Status badges (BOOK, PDNG, INFO)
// - Copy buttons for reference IDs
// ============================================================================

import React, { useState, useEffect, useCallback } from 'react';
import {
  FileText,
  Download,
  Calendar,
  Loader2,
  AlertCircle,
  RefreshCw,
  ArrowUpRight,
  ArrowDownLeft,
  TrendingUp,
  TrendingDown,
  Clock,
  CheckCircle,
  ChevronDown,
  ChevronRight,
  ChevronUp,
  Network,
  FileCode,
  Bell,
  Layers,
  Eye,
  Search,
  X,
  Building2,
  User,
  Hash,
  Copy,
  Banknote,
  Receipt,
  CreditCard,
  Info,
  RotateCcw,
} from 'lucide-react';
import { Card, Button, Badge, Input, Select, Skeleton, EmptyState , StatusIconBadge } from '../components/ui';
import { statementsApi, virtualAccountsApi, VirtualAccount } from '../services/api';
import {
  StatementDownloadPanel,
  VAHierarchyViewer,
  StatementPreviewModal,
} from '../components/statements';
import { formatCurrency, formatDate, formatDateTime, cn, copyToClipboard } from '../utils';
import { isCredit, isDebit, getAmountColorClass, getMovementBgClass } from '../utils/transactionUtils';
import type {
  StatementSummary,
  VAHierarchyNode,
  ISO20022StatementFormat,
  StatementBalance,
  StatementBalanceType,
  CreditDebitIndicator,
  StatementEntryStatus,
} from '../types';
import toast from 'react-hot-toast';
import { Page } from '../components/layout/Page';
import { PageHeader } from '../components/layout/PageHeader';
import { StatStrip } from '../components/layout/StatStrip';
import { HeroMetricCard } from '../components/ui/HeroMetricCard';
import { usePageHeaderActions } from '../context/PageHeaderContext';

// ============================================================================
// TYPES
// ============================================================================

interface VaHierarchyNode {
  id: string;
  vaNumber: string;
  viban?: string;
  vaName: string;
  accountCategory: string;
  currencyCode: string;
  currentBalance: number;
  availableBalance: number;
  hierarchyLevel: number;
  parentAccountId?: string;
  childCount: number;
  children?: VaHierarchyNode[];
}

interface AggregatedBalance {
  vaId: string;
  vaNumber: string;
  vaName: string;
  asOfDate: string;
  currencyCode: string;
  ownBalance: number;
  ownAvailableBalance: number;
  aggregatedBalance: number;
  aggregatedAvailableBalance: number;
  childAccountCount: number;
  childBalances?: {
    vaId: string;
    vaNumber: string;
    vaName: string;
    currencyCode: string;
    currentBalance: number;
    availableBalance: number;
  }[];
}

// Extended ISO20022 Entry with full details
interface ISO20022Entry {
  entryReference?: string;
  amount: number;
  creditDebitIndicator: 'CRDT' | 'DBIT';
  status: StatementEntryStatus;
  bookingDate: string;
  valueDate: string;
  accountServicerRef?: string;
  bankTransactionCode?: {
    domain?: string;
    family?: string;
    subFamily?: string;
  };
  reversalIndicator?: boolean;
  additionalEntryInfo?: string;
  balanceAfter?: number;
  entryDetails?: {
    transactionDetails?: Array<{
      references?: {
        messageId?: string;
        endToEndId?: string;
        transactionId?: string;
        instructionId?: string;
      };
      relatedParties?: {
        debtor?: { name?: string; accountIban?: string; agentBic?: string };
        creditor?: { name?: string; accountIban?: string; agentBic?: string };
      };
      remittanceInformation?: {
        unstructured?: string[];
        structured?: {
          creditorReference?: string;
          invoiceNumber?: string;
        };
      };
    }>;
  };
}

// Extended ISO20022 Statement
interface ISO20022Statement {
  // Group Header
  groupHeader?: {
    messageId: string;
    creationDateTime: string;
    messagePagination?: {
      pageNumber: number;
      lastPageIndicator: boolean;
    };
  };
  // Statement Header
  statementId?: string;
  electronicSeqNumber?: number;
  legalSeqNumber?: number;
  creationDateTime?: string;
  fromDate?: string;
  toDate?: string;
  copyDuplicateIndicator?: 'COPY' | 'DUPL';
  // Account Info
  account?: {
    id?: string;
    iban?: string;
    name?: string;
    currency?: string;
    ownerName?: string;
    servicerBic?: string;
    servicerName?: string;
    accountType?: string;
  };
  // Balances
  balances?: Array<{
    type: StatementBalanceType;
    amount: number;
    currency: string;
    date: string;
    creditDebitIndicator: CreditDebitIndicator;
  }>;
  // Summary
  totalCredits?: number;
  totalDebits?: number;
  creditCount?: number;
  debitCount?: number;
  totalEntries?: number;
  // Entries
  entries?: ISO20022Entry[];
  // Legacy fields
  vaNumber?: string;
  vaName?: string;
  viban?: string;
  currencyCode?: string;
  openingBalance?: number;
  closingBalance?: number;
  transactions?: any[];
  // Aggregated statement
  childStatements?: Array<{
    vaId: string;
    vaNumber: string;
    vaName: string;
    currencyCode: string;
    totalCredits: number;
    totalDebits: number;
    entryCount: number;
  }>;
  // Notification specific (camt.054)
  notificationInfo?: {
    notificationId?: string;
    notificationType?: 'CREDIT' | 'DEBIT';
    priority?: 'HIGH' | 'NORMAL' | 'LOW';
    realTimeTimestamp?: string;
  };
}

type StatementMode = 'standard' | 'camt053' | 'camt054' | 'aggregated';

// ============================================================================
// COPY BUTTON COMPONENT
// ============================================================================

interface CopyButtonProps {
  value: string;
  className?: string;
}

const CopyButton: React.FC<CopyButtonProps> = ({ value, className }) => {
  const [copied, setCopied] = useState(false);

  const handleCopy = async (e: React.MouseEvent) => {
    e.stopPropagation();
    await copyToClipboard(value);
    setCopied(true);
    toast.success('Copied to clipboard');
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <button
      onClick={handleCopy}
      className={cn(
        'inline-flex items-center gap-1 text-xs font-mono text-neutral-600 hover:text-primary-600 transition-colors group dark:text-neutral-300',
        className
      )}
      title="Copy to clipboard"
    >
      <span className="truncate max-w-[120px]">{value}</span>
      {copied ? (
        <CheckCircle className="w-3 h-3 text-success-500 shrink-0" />
      ) : (
        <Copy className="w-3 h-3 opacity-0 group-hover:opacity-100 transition-opacity shrink-0" />
      )}
    </button>
  );
};

// ============================================================================
// STAT CARD COMPONENT
// ============================================================================

interface StatCardProps {
  label: string;
  value: string;
  icon: React.ElementType;
  color: 'primary' | 'success' | 'danger' | 'info';
  trend?: string;
  loading?: boolean;
}

const StatCard: React.FC<StatCardProps> = ({ label, value, icon: Icon, color, trend, loading }) => {
  // Tone-aware backgrounds. Flat tints (no gradients) per the Phase 2/3
  // design-system recipe used across PhysicalAccounts, MultiBankLiquidity,
  // and the cockpit. The icon medallion keeps a denser tonal fill so the
  // card's role reads at a glance.
  const colorMap = {
    primary: { bg: 'bg-primary-50/40 dark:bg-primary-800/30',  iconBg: 'bg-primary-100 dark:bg-primary-700',         text: 'text-primary-600 dark:text-primary-200' },
    success: { bg: 'bg-success-50/40 dark:bg-success-500/10',  iconBg: 'bg-success-100 dark:bg-success-500/20',      text: 'text-success-600 dark:text-success-300' },
    danger:  { bg: 'bg-error-50/40 dark:bg-error-500/10',      iconBg: 'bg-error-100 dark:bg-error-500/20',          text: 'text-error-600 dark:text-error-300' },
    info:    { bg: 'bg-info-50/40 dark:bg-info-500/10',        iconBg: 'bg-info-100 dark:bg-info-500/20',            text: 'text-info-600 dark:text-info-300' },
  };

  const colors = colorMap[color];

  if (loading) {
    return (
      <Card className={cn(colors.bg)}>
        <div className="flex items-center gap-3">
          <Skeleton className="w-10 h-10 rounded-lg" />
          <div className="flex-1">
            <Skeleton className="h-3 w-16 mb-2" />
            <Skeleton className="h-6 w-24" />
          </div>
        </div>
      </Card>
    );
  }

  return (
    <Card hover className={cn('transition-all duration-300', colors.bg)}>
      <div className="flex items-center gap-3">
        <div className={cn('w-10 h-10 sm:w-12 sm:h-12 rounded-lg flex items-center justify-center shrink-0', colors.iconBg)}>
          <Icon className={cn('w-5 h-5 sm:w-6 sm:h-6', colors.text)} />
        </div>
        <div className="min-w-0 flex-1">
          <p className="label truncate">{label}</p>
          <p className="stat-value-sm truncate">{value}</p>
          {trend && (
            <span className={cn('body-sm', trend.startsWith('+') ? 'text-success-600 dark:text-success-300' : 'text-error-600 dark:text-error-300')}>
              {trend}
            </span>
          )}
        </div>
      </div>
    </Card>
  );
};

// ============================================================================
// BALANCE CARD COMPONENT (ISO20022 Balance Types)
// ============================================================================

interface BalanceCardProps {
  type: StatementBalanceType;
  amount: number;
  currency: string;
  date: string;
  creditDebit: CreditDebitIndicator;
  compact?: boolean;
}

const BalanceCard: React.FC<BalanceCardProps> = ({ type, amount, currency, date, creditDebit, compact = false }) => {
  const typeLabels: Record<StatementBalanceType, string> = {
    OPBD: 'Opening Booked',
    CLBD: 'Closing Booked',
    OPAV: 'Opening Available',
    CLAV: 'Closing Available',
    FWAV: 'Forward Available',
    INFO: 'Information',
  };

  const typeColors: Record<StatementBalanceType, string> = {
    OPBD: 'bg-primary-50 border-primary-200 dark:bg-primary-800/40',
    CLBD: 'bg-info-50 border-info-200 dark:bg-info-500/10 dark:border-info-500/30',
    OPAV: 'bg-neutral-50 border-neutral-200 dark:bg-primary-950 dark:border-primary-800',
    CLAV: 'bg-success-50 border-success-200 dark:bg-success-500/10 dark:border-success-500/30',
    FWAV: 'bg-warning-50 border-warning-200 dark:bg-warning-500/10 dark:border-warning-500/30',
    INFO: 'bg-neutral-50 border-neutral-200 dark:bg-primary-950 dark:border-primary-800',
  };

  const isDebit = creditDebit === 'DBIT';

  return (
    <div className={cn('rounded-lg border', typeColors[type], compact ? 'p-2' : 'p-3')}>
      <div className="flex items-center justify-between mb-1">
        {/* Compact mode keeps a 10px label for grid density; full mode uses
            the canonical `.label` recipe (12px uppercase, tracked). */}
        {compact ? (
          <span className="text-xs font-medium text-neutral-600 dark:text-neutral-300">
            {typeLabels[type]}
          </span>
        ) : (
          <span className="label">{typeLabels[type]}</span>
        )}
        <Badge variant="neutral" size="xs">{type}</Badge>
      </div>
      {/* Balance amount — Phase 9 display tier. `.stat-value-xs` carries
          Fraunces serif + tabular-nums automatically. Tone overlay only when
          DBIT. Compact mode falls back to `body-sm` weight to fit the grid. */}
      <p className={cn(
        compact ? 'body-sm tabular-nums font-semibold' : 'stat-value-xs',
        isDebit && 'text-error-600 dark:text-error-300',
      )}>
        {isDebit && '-'}{formatCurrency(amount, currency)}
      </p>
      <p className={cn('text-neutral-500 mt-0.5 dark:text-neutral-400', 'text-xs')}>
        {formatDate(date)}
      </p>
    </div>
  );
};

// ============================================================================
// STATUS BADGE COMPONENT
// ============================================================================

interface StatusBadgeProps {
  status: StatementEntryStatus;
}

const StatusBadge: React.FC<StatusBadgeProps> = ({ status }) => {
  const variants: Record<StatementEntryStatus, 'success' | 'warning' | 'info' | 'neutral'> = {
    BOOK: 'success',
    PDNG: 'warning',
    INFO: 'info',
    FUTR: 'neutral',
  };

  return (
    <Badge variant={variants[status]} size="sm">
      {status}
    </Badge>
  );
};

// ============================================================================
// ISO20022 TRANSACTION ROW - Expandable with full details
// ============================================================================

interface ISO20022TransactionRowProps {
  entry: ISO20022Entry;
  currencyCode: string;
  index: number;
}

const ISO20022TransactionRow: React.FC<ISO20022TransactionRowProps> = ({ entry, currencyCode, index }) => {
  const [expanded, setExpanded] = useState(false);

  const isDebitEntry = entry.creditDebitIndicator === 'DBIT';
  const isCreditEntry = entry.creditDebitIndicator === 'CRDT';

  // Extract references from nested structure
  const txDetails = entry.entryDetails?.transactionDetails?.[0];
  const references = txDetails?.references;
  const relatedParties = txDetails?.relatedParties;
  const remittanceInfo = txDetails?.remittanceInformation;

  const reference = entry.entryReference ||
    references?.endToEndId ||
    references?.instructionId ||
    '-';

  const description = remittanceInfo?.unstructured?.[0] ||
    entry.additionalEntryInfo ||
    '-';

  return (
    <>
      <tr
        className={cn(
          "data-table-row group cursor-pointer transition-colors",
          expanded && "bg-neutral-50 dark:bg-primary-950"
        )}
        onClick={() => setExpanded(!expanded)}
        style={{ animationDelay: `${index * 0.02}s` }}
      >
        {/* Expand Button */}
        <td className="data-table-cell w-10">
          <button className="p-1 hover:bg-neutral-200 rounded">
            {expanded ? (
              <ChevronUp className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
            ) : (
              <ChevronDown className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
            )}
          </button>
        </td>

        {/* Date */}
        <td className="data-table-cell">
          <div className="flex items-center gap-3">
            <div className={cn(
              "w-8 h-8 rounded-lg flex items-center justify-center relative",
              isDebitEntry ? 'bg-error-50 dark:bg-error-500/10' : 'bg-success-50 dark:bg-success-500/10'
            )}>
              {isDebitEntry ? (
                <ArrowUpRight className="w-4 h-4 text-error-600 dark:text-error-300" />
              ) : (
                <ArrowDownLeft className="w-4 h-4 text-success-600 dark:text-success-300" />
              )}
              {entry.reversalIndicator && (
                <RotateCcw className="w-3 h-3 text-warning-500 absolute -bottom-1 -right-1" />
              )}
            </div>
            <div>
              <span className="text-sm text-primary-900 block dark:text-neutral-50">
                {formatDate(entry.bookingDate)}
              </span>
              {entry.valueDate && entry.valueDate !== entry.bookingDate && (
                <span className="text-xs text-neutral-500 dark:text-neutral-400">
                  Value: {formatDate(entry.valueDate)}
                </span>
              )}
            </div>
          </div>
        </td>

        {/* Reference */}
        <td className="data-table-cell">
          <div className="flex items-center gap-1">
            <CopyButton value={reference} />
            {entry.reversalIndicator && (
              <Badge variant="warning" size="xs">REV</Badge>
            )}
          </div>
        </td>

        {/* Description */}
        <td className="data-table-cell">
          <span className="text-sm text-primary-900 truncate max-w-[200px] block dark:text-neutral-50">
            {description}
          </span>
          {entry.bankTransactionCode && (
            <span className="text-xs text-neutral-500 dark:text-neutral-400">
              {entry.bankTransactionCode.domain}/{entry.bankTransactionCode.family}/{entry.bankTransactionCode.subFamily}
            </span>
          )}
        </td>

        {/* Status */}
        <td className="data-table-cell">
          <StatusBadge status={entry.status} />
        </td>

        {/* Debit */}
        <td className="data-table-cell text-right">
          {isDebitEntry ? (
            // Movement amounts use `.stat-value-xs` so the negative cash
            // movement gets the same display tier (Fraunces + tabular-nums)
            // as the balance column, with the tone overlay carrying the
            // semantic colour.
            <span className="stat-value-xs text-error-600 dark:text-error-300">
              -{formatCurrency(entry.amount, currencyCode)}
            </span>
          ) : (
            <span className="body-sm text-neutral-400 dark:text-neutral-500">-</span>
          )}
        </td>

        {/* Credit */}
        <td className="data-table-cell text-right">
          {isCreditEntry ? (
            <span className="stat-value-xs text-success-600 dark:text-success-300">
              +{formatCurrency(entry.amount, currencyCode)}
            </span>
          ) : (
            <span className="body-sm text-neutral-400 dark:text-neutral-500">-</span>
          )}
        </td>

        {/* Balance */}
        <td className="data-table-cell text-right">
          {entry.balanceAfter !== undefined ? (
            <span className="stat-value-xs">
              {formatCurrency(entry.balanceAfter, currencyCode)}
            </span>
          ) : (
            <span className="body-sm text-neutral-400 dark:text-neutral-500">-</span>
          )}
        </td>
      </tr>

      {/* Expanded Details Row */}
      {expanded && (
        <tr className="animate-fade-in">
          <td colSpan={8} className="px-4 py-3 bg-neutral-50 border-b border-neutral-200 dark:bg-primary-950 dark:border-primary-800">
            <div className="grid grid-cols-1 md:grid-cols-3 lg:grid-cols-4 gap-4 ml-10">
              {/* Transaction References */}
              <div className="bg-white rounded-lg border border-neutral-200 p-3 dark:bg-primary-900 dark:border-primary-800">
                <h5 className="label text-neutral-700 mb-2 flex items-center gap-1.5 dark:text-neutral-200">
                  <Hash className="w-3.5 h-3.5" />
                  Transaction References
                </h5>
                <div className="space-y-1 text-xs">
                  {entry.entryReference && (
                    <div className="flex justify-between">
                      <span className="text-neutral-500 dark:text-neutral-400">Entry Ref</span>
                      <CopyButton value={entry.entryReference} />
                    </div>
                  )}
                  {entry.accountServicerRef && (
                    <div className="flex justify-between">
                      <span className="text-neutral-500 dark:text-neutral-400">Acct Svcr Ref</span>
                      <CopyButton value={entry.accountServicerRef} />
                    </div>
                  )}
                  {references?.messageId && (
                    <div className="flex justify-between">
                      <span className="text-neutral-500 dark:text-neutral-400">Message ID</span>
                      <CopyButton value={references.messageId} />
                    </div>
                  )}
                  {references?.endToEndId && (
                    <div className="flex justify-between">
                      <span className="text-neutral-500 dark:text-neutral-400">E2E ID</span>
                      <CopyButton value={references.endToEndId} />
                    </div>
                  )}
                  {references?.transactionId && (
                    <div className="flex justify-between">
                      <span className="text-neutral-500 dark:text-neutral-400">Transaction ID</span>
                      <CopyButton value={references.transactionId} />
                    </div>
                  )}
                  {references?.instructionId && (
                    <div className="flex justify-between">
                      <span className="text-neutral-500 dark:text-neutral-400">Instruction ID</span>
                      <CopyButton value={references.instructionId} />
                    </div>
                  )}
                </div>
              </div>

              {/* Bank Transaction Code */}
              {entry.bankTransactionCode && (
                <div className="bg-white rounded-lg border border-neutral-200 p-3 dark:bg-primary-900 dark:border-primary-800">
                  <h5 className="label text-neutral-700 mb-2 flex items-center gap-1.5 dark:text-neutral-200">
                    <CreditCard className="w-3.5 h-3.5" />
                    Bank Transaction Code
                  </h5>
                  <div className="space-y-1 text-xs">
                    <div className="flex justify-between">
                      <span className="text-neutral-500 dark:text-neutral-400">Domain</span>
                      <span className="font-medium">{entry.bankTransactionCode.domain || '-'}</span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-neutral-500 dark:text-neutral-400">Family</span>
                      <span className="font-medium">{entry.bankTransactionCode.family || '-'}</span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-neutral-500 dark:text-neutral-400">Sub-Family</span>
                      <span className="font-medium">{entry.bankTransactionCode.subFamily || '-'}</span>
                    </div>
                  </div>
                </div>
              )}

              {/* Debtor Info */}
              {relatedParties?.debtor && (
                <div className="bg-white rounded-lg border border-neutral-200 p-3 dark:bg-primary-900 dark:border-primary-800">
                  <h5 className="label text-neutral-700 mb-2 flex items-center gap-1.5 dark:text-neutral-200">
                    <User className="w-3.5 h-3.5" />
                    Debtor
                  </h5>
                  <div className="space-y-1 text-xs">
                    {relatedParties.debtor.name && (
                      <div className="flex justify-between">
                        <span className="text-neutral-500 dark:text-neutral-400">Name</span>
                        <span className="font-medium truncate max-w-[120px]">{relatedParties.debtor.name}</span>
                      </div>
                    )}
                    {relatedParties.debtor.accountIban && (
                      <div className="flex justify-between">
                        <span className="text-neutral-500 dark:text-neutral-400">Account</span>
                        <CopyButton value={relatedParties.debtor.accountIban} />
                      </div>
                    )}
                    {relatedParties.debtor.agentBic && (
                      <div className="flex justify-between">
                        <span className="text-neutral-500 dark:text-neutral-400">Agent BIC</span>
                        <CopyButton value={relatedParties.debtor.agentBic} />
                      </div>
                    )}
                  </div>
                </div>
              )}

              {/* Creditor Info */}
              {relatedParties?.creditor && (
                <div className="bg-white rounded-lg border border-neutral-200 p-3 dark:bg-primary-900 dark:border-primary-800">
                  <h5 className="label text-neutral-700 mb-2 flex items-center gap-1.5 dark:text-neutral-200">
                    <User className="w-3.5 h-3.5" />
                    Creditor
                  </h5>
                  <div className="space-y-1 text-xs">
                    {relatedParties.creditor.name && (
                      <div className="flex justify-between">
                        <span className="text-neutral-500 dark:text-neutral-400">Name</span>
                        <span className="font-medium truncate max-w-[120px]">{relatedParties.creditor.name}</span>
                      </div>
                    )}
                    {relatedParties.creditor.accountIban && (
                      <div className="flex justify-between">
                        <span className="text-neutral-500 dark:text-neutral-400">Account</span>
                        <CopyButton value={relatedParties.creditor.accountIban} />
                      </div>
                    )}
                    {relatedParties.creditor.agentBic && (
                      <div className="flex justify-between">
                        <span className="text-neutral-500 dark:text-neutral-400">Agent BIC</span>
                        <CopyButton value={relatedParties.creditor.agentBic} />
                      </div>
                    )}
                  </div>
                </div>
              )}

              {/* Remittance Information */}
              {remittanceInfo && (
                <div className="bg-white rounded-lg border border-neutral-200 p-3 dark:bg-primary-900 dark:border-primary-800">
                  <h5 className="label text-neutral-700 mb-2 flex items-center gap-1.5 dark:text-neutral-200">
                    <FileText className="w-3.5 h-3.5" />
                    Remittance Info
                  </h5>
                  <div className="space-y-1 text-xs">
                    {remittanceInfo.unstructured?.map((info, idx) => (
                      <p key={idx} className="text-neutral-600 break-words dark:text-neutral-300">{info}</p>
                    ))}
                    {remittanceInfo.structured?.creditorReference && (
                      <div className="flex justify-between">
                        <span className="text-neutral-500 dark:text-neutral-400">Creditor Ref</span>
                        <CopyButton value={remittanceInfo.structured.creditorReference} />
                      </div>
                    )}
                    {remittanceInfo.structured?.invoiceNumber && (
                      <div className="flex justify-between">
                        <span className="text-neutral-500 dark:text-neutral-400">Invoice No</span>
                        <CopyButton value={remittanceInfo.structured.invoiceNumber} />
                      </div>
                    )}
                  </div>
                </div>
              )}

              {/* Additional Info */}
              {entry.additionalEntryInfo && (
                <div className="bg-white rounded-lg border border-neutral-200 p-3 dark:bg-primary-900 dark:border-primary-800">
                  <h5 className="label text-neutral-700 mb-2 flex items-center gap-1.5 dark:text-neutral-200">
                    <Info className="w-3.5 h-3.5" />
                    Additional Info
                  </h5>
                  <p className="text-xs text-neutral-600 break-words dark:text-neutral-300">{entry.additionalEntryInfo}</p>
                </div>
              )}
            </div>
          </td>
        </tr>
      )}
    </>
  );
};

// ============================================================================
// LEGACY TRANSACTION ROW (Desktop) - Uses utility functions
// ============================================================================

interface TransactionRowProps {
  transaction: any;
  currencyCode: string;
}

const TransactionRow: React.FC<TransactionRowProps> = ({ transaction: tx, currencyCode }) => {
  const isDebitTxn = isDebit(tx.movementType);
  const isCreditTxn = isCredit(tx.movementType);

  return (
    <tr className="data-table-row group">
      <td className="data-table-cell w-10"></td>
      <td className="data-table-cell">
        <div className="flex items-center gap-3">
          <div className={cn(
            "w-8 h-8 rounded-lg flex items-center justify-center",
            getMovementBgClass(tx.movementType)
          )}>
            {isDebitTxn ? (
              <ArrowUpRight className="w-4 h-4 text-error-600 dark:text-error-300" />
            ) : (
              <ArrowDownLeft className="w-4 h-4 text-success-600 dark:text-success-300" />
            )}
          </div>
          <span className="text-sm text-primary-900 dark:text-neutral-50">
            {new Date(tx.transactionDate).toLocaleDateString()}
          </span>
        </div>
      </td>
      <td className="data-table-cell">
        <span className="font-mono text-xs text-neutral-600 dark:text-neutral-300">{tx.referenceNumber}</span>
      </td>
      <td className="data-table-cell">
        <span className="text-sm text-primary-900 truncate max-w-[200px] block dark:text-neutral-50">
          {tx.description || '-'}
        </span>
      </td>
      <td className="data-table-cell">
        <Badge variant="success" size="sm">BOOK</Badge>
      </td>
      <td className="data-table-cell text-right">
        {isDebitTxn ? (
          <span className="stat-value-xs text-error-600 dark:text-error-300">
            -{formatCurrency(tx.amount, currencyCode)}
          </span>
        ) : (
          <span className="body-sm text-neutral-400 dark:text-neutral-500">-</span>
        )}
      </td>
      <td className="data-table-cell text-right">
        {isCreditTxn ? (
          <span className="stat-value-xs text-success-600 dark:text-success-300">
            +{formatCurrency(tx.amount, currencyCode)}
          </span>
        ) : (
          <span className="body-sm text-neutral-400 dark:text-neutral-500">-</span>
        )}
      </td>
      <td className="data-table-cell text-right">
        <span className="stat-value-xs">
          {formatCurrency(tx.balanceAfter, currencyCode)}
        </span>
      </td>
    </tr>
  );
};

// ============================================================================
// TRANSACTION MOBILE CARD
// ============================================================================

interface TransactionMobileCardProps {
  transaction: any;
  currencyCode: string;
  index: number;
  isISO20022?: boolean;
}

const TransactionMobileCard: React.FC<TransactionMobileCardProps> = ({
  transaction: tx,
  currencyCode,
  index,
  isISO20022 = false
}) => {
  const [expanded, setExpanded] = useState(false);

  const isDebitTxn = isISO20022
    ? tx.creditDebitIndicator === 'DBIT'
    : isDebit(tx.movementType);

  const amount = tx.amount;
  const date = isISO20022 ? tx.bookingDate : tx.transactionDate;
  const description = isISO20022
    ? (tx.entryDetails?.transactionDetails?.[0]?.remittanceInformation?.unstructured?.[0] || tx.entryReference)
    : (tx.description || tx.referenceNumber);

  return (
    <div
      className={cn(
        "p-4 border-b border-neutral-100 last:border-0 animate-fade-in dark:border-primary-800/60",
        expanded && "bg-neutral-50 dark:bg-primary-950"
      )}
      style={{ animationDelay: `${index * 0.02}s` }}
      onClick={() => setExpanded(!expanded)}
    >
      <div className="flex items-start gap-3">
        <div className={cn(
          "w-10 h-10 rounded-xl flex items-center justify-center shrink-0 relative",
          isDebitTxn ? 'bg-error-50 dark:bg-error-500/10' : 'bg-success-50 dark:bg-success-500/10'
        )}>
          {isDebitTxn ? (
            <ArrowUpRight className="w-5 h-5 text-error-600 dark:text-error-300" />
          ) : (
            <ArrowDownLeft className="w-5 h-5 text-success-600 dark:text-success-300" />
          )}
          {isISO20022 && tx.reversalIndicator && (
            <RotateCcw className="w-3 h-3 text-warning-500 absolute -bottom-0.5 -right-0.5" />
          )}
        </div>

        <div className="flex-1 min-w-0">
          <div className="flex items-start justify-between gap-2">
            <div className="min-w-0">
              <p className="body-sm text-primary-900 truncate dark:text-neutral-50">
                {description}
              </p>
              <p className="text-xs text-neutral-500 mt-0.5 dark:text-neutral-400">
                {formatDate(date)}
              </p>
            </div>
            <div className="text-right shrink-0">
              <p className={cn(
                'stat-value-xs',
                isDebitTxn ? 'text-error-600 dark:text-error-300' : 'text-success-600 dark:text-success-300',
              )}>
                {isDebitTxn ? '-' : '+'}{formatCurrency(amount, currencyCode)}
              </p>
              {tx.balanceAfter !== undefined && (
                <p className="text-xs text-neutral-500 mt-0.5 dark:text-neutral-400">
                  Bal: {formatCurrency(tx.balanceAfter, currencyCode)}
                </p>
              )}
            </div>
          </div>

          {/* Status and Reference */}
          <div className="flex items-center gap-2 mt-2">
            {isISO20022 && tx.status && (
              <StatusBadge status={tx.status} />
            )}
            {isISO20022 && tx.reversalIndicator && (
              <Badge variant="warning" size="xs">REV</Badge>
            )}
            <span className="text-xs text-neutral-400 font-mono truncate dark:text-neutral-500">
              {tx.entryReference || tx.referenceNumber}
            </span>
            <ChevronDown className={cn(
              'w-4 h-4 text-neutral-400 ml-auto transition-transform dark:text-neutral-500',
              expanded && 'rotate-180'
            )} />
          </div>

          {/* Expanded Details (Mobile) */}
          {expanded && isISO20022 && (
            <div className="mt-3 pt-3 border-t border-neutral-200 space-y-2 animate-fade-in dark:border-primary-800">
              {tx.accountServicerRef && (
                <div className="flex justify-between text-xs">
                  <span className="text-neutral-500 dark:text-neutral-400">Acct Svcr Ref</span>
                  <CopyButton value={tx.accountServicerRef} />
                </div>
              )}
              {tx.entryDetails?.transactionDetails?.[0]?.references?.endToEndId && (
                <div className="flex justify-between text-xs">
                  <span className="text-neutral-500 dark:text-neutral-400">E2E ID</span>
                  <CopyButton value={tx.entryDetails.transactionDetails[0].references.endToEndId} />
                </div>
              )}
              {tx.bankTransactionCode && (
                <div className="flex justify-between text-xs">
                  <span className="text-neutral-500 dark:text-neutral-400">BTC</span>
                  <span className="font-mono">
                    {tx.bankTransactionCode.domain}/{tx.bankTransactionCode.family}
                  </span>
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

// ============================================================================
// HIERARCHY TREE VIEW COMPONENT
// ============================================================================

interface HierarchyTreeNodeProps {
  node: VaHierarchyNode;
  level?: number;
  onSelect?: (node: VaHierarchyNode) => void;
  selectedId?: string;
}

const HierarchyTreeNode: React.FC<HierarchyTreeNodeProps> = ({
  node,
  level = 0,
  onSelect,
  selectedId
}) => {
  const [expanded, setExpanded] = useState(level < 2);
  const hasChildren = node.children && node.children.length > 0;
  const isSelected = selectedId === node.id;

  return (
    <div className="select-none">
      <div
        className={cn(
          "flex items-center gap-2 py-2 px-3 rounded-lg cursor-pointer transition-colors",
          isSelected ? "bg-primary-100 text-primary-900 dark:bg-primary-700 dark:text-neutral-50" : "hover:bg-neutral-50 dark:hover:bg-primary-800/50",
          level > 0 && "ml-4"
        )}
        style={{ marginLeft: level * 16 }}
        onClick={() => onSelect?.(node)}
      >
        {hasChildren ? (
          <button
            onClick={(e) => {
              e.stopPropagation();
              setExpanded(!expanded);
            }}
            className="p-0.5 hover:bg-neutral-200 rounded"
          >
            {expanded ? (
              <ChevronDown className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
            ) : (
              <ChevronRight className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
            )}
          </button>
        ) : (
          <div className="w-5" />
        )}

        <div className={cn(
          "w-8 h-8 rounded-lg flex items-center justify-center shrink-0",
          node.accountCategory === 'AGGREGATION' ? 'bg-info-100 dark:bg-info-500/20' :
            node.accountCategory === 'ROOT' ? 'bg-primary-100 dark:bg-primary-700' : 'bg-neutral-100 dark:bg-primary-800'
        )}>
          {node.accountCategory === 'AGGREGATION' ? (
            <Layers className="w-4 h-4 text-info-600 dark:text-info-300" />
          ) : node.accountCategory === 'ROOT' ? (
            <Network className="w-4 h-4 text-primary-600 dark:text-primary-200" />
          ) : (
            <FileText className="w-4 h-4 text-neutral-600 dark:text-neutral-300" />
          )}
        </div>

        <div className="flex-1 min-w-0">
          {/* VA name reads as the row's primary identifier — body weight at
              primary-900 tone. Code-style `.code` for the VA number. */}
          <p className="body-sm text-primary-900 truncate dark:text-neutral-50">{node.vaName}</p>
          <p className="code">{node.vaNumber}</p>
        </div>

        <div className="text-right shrink-0">
          <p className="stat-value-xs">
            {formatCurrency(node.currentBalance, node.currencyCode)}
          </p>
          {node.childCount > 0 && (
            <Badge variant="neutral" size="sm">{node.childCount} children</Badge>
          )}
        </div>
      </div>

      {hasChildren && expanded && (
        <div className="mt-1">
          {node.children!.map((child) => (
            <HierarchyTreeNode
              key={child.id}
              node={child}
              level={level + 1}
              onSelect={onSelect}
              selectedId={selectedId}
            />
          ))}
        </div>
      )}
    </div>
  );
};

// ============================================================================
// HISTORY CARD (Mobile)
// ============================================================================

interface HistoryMobileCardProps {
  statement: any;
  index: number;
  onDownload?: (stmt: any) => void;
}

const HistoryMobileCard: React.FC<HistoryMobileCardProps> = ({ statement: stmt, index, onDownload }) => {
  return (
    <Card
      interactive
      hover
      className="animate-fade-in"
      style={{ animationDelay: `${index * 0.03}s` }}
    >
      <div className="flex items-start gap-3">
        <StatusIconBadge tone="primary" icon={FileText} subtle className="shrink-0 dark:bg-primary-800/40" />

        <div className="flex-1 min-w-0">
          <div className="flex items-start justify-between gap-2">
            <div className="min-w-0">
              <p className="body-sm text-primary-900 truncate dark:text-neutral-50">
                {stmt.vaNumber}
              </p>
              <p className="text-xs text-neutral-500 mt-0.5 dark:text-neutral-400">
                {stmt.fromDate} - {stmt.toDate}
              </p>
            </div>
            <Badge variant="neutral" size="sm">{stmt.format}</Badge>
          </div>

          <div className="mt-3 pt-3 border-t border-neutral-100 flex items-center justify-between dark:border-primary-800/60">
            <div className="flex items-center gap-1 text-xs text-neutral-500 dark:text-neutral-400">
              <Clock className="w-3 h-3" />
              {new Date(stmt.generatedAt).toLocaleDateString()}
            </div>
            <Button
              size="sm"
              variant="ghost"
              leftIcon={<Download className="w-3.5 h-3.5" />}
              onClick={() => onDownload?.(stmt)}
            >
              Download
            </Button>
          </div>
        </div>
      </div>
    </Card>
  );
};

// ============================================================================
// SECTION CARD COMPONENT
// ============================================================================

interface SectionCardProps {
  title: string;
  icon: React.ReactNode;
  children: React.ReactNode;
  collapsible?: boolean;
  defaultOpen?: boolean;
  className?: string;
  badge?: React.ReactNode;
}

const SectionCard: React.FC<SectionCardProps> = ({
  title,
  icon,
  children,
  collapsible = false,
  defaultOpen = true,
  className,
  badge,
}) => {
  const [isOpen, setIsOpen] = useState(defaultOpen);

  return (
    <div className={cn('bg-white rounded-xl border border-neutral-200 overflow-hidden dark:bg-primary-900 dark:border-primary-800', className)}>
      <div
        className={cn(
          'flex items-center justify-between px-4 py-3 bg-neutral-50 dark:bg-primary-950',
          collapsible && 'cursor-pointer hover:bg-neutral-100 transition-colors dark:hover:bg-primary-800'
        )}
        onClick={collapsible ? () => setIsOpen(!isOpen) : undefined}
      >
        <div className="flex items-center gap-2">
          <div className="w-7 h-7 rounded-lg bg-primary-100 flex items-center justify-center dark:bg-primary-700">
            {icon}
          </div>
          <h4 className="section-title">{title}</h4>
          {badge}
        </div>
        {collapsible && (
          <button className="p-1 hover:bg-neutral-200 rounded transition-colors">
            {isOpen ? (
              <ChevronUp className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
            ) : (
              <ChevronDown className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
            )}
          </button>
        )}
      </div>
      {isOpen && <div className="px-4 py-3 border-t border-neutral-100 dark:border-primary-800/60">{children}</div>}
    </div>
  );
};

// ============================================================================
// INFO ROW COMPONENT
// ============================================================================

interface InfoRowProps {
  label: string;
  value?: string | number | null;
  copyable?: boolean;
  icon?: React.ReactNode;
}

const InfoRow: React.FC<InfoRowProps> = ({ label, value, copyable, icon }) => {
  if (value === undefined || value === null || value === '') return null;

  return (
    <div className="flex items-start justify-between py-1">
      <span className="text-xs text-neutral-500 flex items-center gap-1 dark:text-neutral-400">
        {icon}
        {label}
      </span>
      {copyable && typeof value === 'string' ? (
        <CopyButton value={value} />
      ) : (
        // Info-row value: small body text at primary-900 — no font-X needed.
        <span className="text-xs text-primary-900 text-right max-w-[150px] truncate dark:text-neutral-50">
          {value}
        </span>
      )}
    </div>
  );
};

// ============================================================================
// MAIN PAGE COMPONENT
// ============================================================================

const StatementsPage: React.FC = () => {
  // State
  const [accounts, setAccounts] = useState<VirtualAccount[]>([]);
  const [selectedAccount, setSelectedAccount] = useState<string>('');
  const [fromDate, setFromDate] = useState(
    new Date(Date.now() - 30 * 24 * 60 * 60 * 1000).toISOString().split('T')[0]
  );
  const [toDate, setToDate] = useState(new Date().toISOString().split('T')[0]);
  const [statement, setStatement] = useState<ISO20022Statement | null>(null);
  const [history, setHistory] = useState<any[]>([]);
  const [, setStatementTypes] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [generating, setGenerating] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // New ISO20022 specific state
  const [statementMode, setStatementMode] = useState<StatementMode>('camt053');
  const [hierarchy, setHierarchy] = useState<VaHierarchyNode | null>(null);
  const [aggregatedBalance, setAggregatedBalance] = useState<AggregatedBalance | null>(null);
  const [includeChildren, setIncludeChildren] = useState(true);
  const [showHierarchy, setShowHierarchy] = useState(false);

  // Enhanced UI state for new components
  const [showEnhancedDownloadPanel, setShowEnhancedDownloadPanel] = useState(false);
  const [showPreviewModal, setShowPreviewModal] = useState(false);
  const [previewSummary, setPreviewSummary] = useState<StatementSummary | null>(null);
  const [historySearch, setHistorySearch] = useState('');
  const [historyFormatFilter, setHistoryFormatFilter] = useState<ISO20022StatementFormat | ''>('');

  // Find selected account details
  const selectedAccountDetails = accounts.find(a => a.id === selectedAccount);
  const isAggregationAccount = selectedAccountDetails?.accountCategory === 'AGGREGATION' ||
    selectedAccountDetails?.accountCategory === 'ROOT';

  // Fetch initial data
  const fetchInitialData = useCallback(async () => {
    try {
      setLoading(true);
      const [accRes, histRes, typesRes] = await Promise.all([
        virtualAccountsApi.getAll(),
        statementsApi.getHistory(),
        statementsApi.getTypes(),
      ]);
      if (accRes.success) setAccounts(accRes.data);
      if (histRes.success) setHistory(histRes.data);
      if (typesRes.success) setStatementTypes(typesRes.data);
    } catch (err: any) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchInitialData();
  }, [fetchInitialData]);

  // Fetch hierarchy when account is selected
  useEffect(() => {
    if (selectedAccount && isAggregationAccount) {
      fetchHierarchy();
    } else {
      setHierarchy(null);
      setAggregatedBalance(null);
    }
  }, [selectedAccount, isAggregationAccount]);

  const fetchHierarchy = async () => {
    if (!selectedAccount) return;
    try {
      const [hierRes, balRes] = await Promise.all([
        statementsApi.getVaHierarchy(selectedAccount),
        statementsApi.getVaAggregatedBalance(selectedAccount),
      ]);
      if (hierRes.success) setHierarchy(hierRes.data);
      if (balRes.success) setAggregatedBalance(balRes.data);
    } catch (err) {
      console.error('Failed to fetch hierarchy:', err);
    }
  };

  // Fetch statement based on mode
  const fetchStatement = async () => {
    if (!selectedAccount) return;
    try {
      setGenerating(true);
      setError(null);

      let res;
      switch (statementMode) {
        case 'camt053':
          res = await statementsApi.getCamt053Statement(selectedAccount, fromDate, toDate);
          break;
        case 'camt054':
          res = await statementsApi.getCamt054Notifications(selectedAccount, fromDate, toDate);
          break;
        case 'aggregated':
          res = await statementsApi.getAggregatedCamt053Statement(
            selectedAccount, fromDate, toDate, includeChildren
          );
          break;
        default:
          res = await statementsApi.getAccountStatement(selectedAccount, fromDate, toDate);
      }

      if (res.success) setStatement(res.data);
    } catch (err: any) {
      setError(err.message);
    } finally {
      setGenerating(false);
    }
  };

  // Download XML statement
  const downloadXmlStatement = async () => {
    if (!selectedAccount) return;
    try {
      setDownloading(true);
      setError(null);

      const blob = await statementsApi.downloadCamt053Xml(selectedAccount, fromDate, toDate);

      // Create download link
      const url = window.URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;

      const accountNum = selectedAccountDetails?.vaNumber || selectedAccount;
      link.download = `camt053_${accountNum}_${fromDate}_${toDate}.xml`;

      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      window.URL.revokeObjectURL(url);
    } catch (err: any) {
      setError(`Failed to download XML: ${err.message}`);
    } finally {
      setDownloading(false);
    }
  };

  // Generate statement in various formats
  const generateStatement = async (format: string) => {
    if (!selectedAccount) return;
    try {
      setGenerating(true);
      const res = await statementsApi.generate(selectedAccount, format, fromDate, toDate);
      if (res.success) {
        toast.success(`Statement generated: ${res.data.statementReference}`);
        fetchInitialData();
      }
    } catch (err: any) {
      setError(err.message);
    } finally {
      setGenerating(false);
    }
  };

  // Handler for enhanced download panel
  const handleStatementGenerated = (reference: string, format: ISO20022StatementFormat) => {
    toast.success(`${format} statement generated: ${reference}`);
    fetchInitialData();
  };

  // Handler for preview summary
  const handlePreview = (summary: StatementSummary) => {
    setPreviewSummary(summary);
    setShowPreviewModal(true);
  };

  // Handler for hierarchy account selection
  const handleHierarchyAccountSelect = (node: VAHierarchyNode) => {
    setSelectedAccount(node.id);
    setShowHierarchy(false);
    toast.success(`Selected: ${node.vaNumber}`);
  };

  // Download statement from history
  const handleDownloadFromHistory = async (reference: string) => {
    try {
      await statementsApi.downloadStatement(reference);
      toast.success('Statement downloaded');
    } catch (error) {
      toast.error('Failed to download statement');
    }
  };

  // Filter history based on search and format
  const filteredHistory = history.filter(item => {
    if (historySearch) {
      const search = historySearch.toLowerCase();
      if (!item.statementReference?.toLowerCase().includes(search) &&
          !item.vaNumber?.toLowerCase().includes(search)) {
        return false;
      }
    }
    if (historyFormatFilter && item.format !== historyFormatFilter) {
      return false;
    }
    return true;
  });

  // Build account options
  const accountOptions = [
    { value: '', label: 'Select account...' },
    ...accounts.map((acc) => ({
      value: acc.id,
      label: `${acc.vaName} (${acc.vaNumber})${acc.accountCategory === 'AGGREGATION' ? ' [AGG]' : acc.accountCategory === 'ROOT' ? ' [ROOT]' : ''}`,
    })),
  ];

  // Statement mode options
  const modeOptions = [
    { value: 'camt053', label: 'camt.053 - Account Statement' },
    { value: 'camt054', label: 'camt.054 - Notifications' },
    { value: 'aggregated', label: 'Aggregated Statement' },
    { value: 'standard', label: 'Standard Statement' },
  ];

  // Get currency code for statement
  const statementCurrency = statement?.account?.currency || statement?.currencyCode || selectedAccountDetails?.currencyCode || 'AED';

  // Lift Quick Actions into the Aperture Layout header (Phase 7). The
  // refresh button + Show Hierarchy / Download Panel toggles all live in
  // the shell header; the in-body identity is rendered by `<PageHeader>`
  // below with contextual ISO20022 + Aggregation Account badges. Stays in
  // sync via the dependency array — when the account selection or panel
  // visibility changes, the header re-renders.
  usePageHeaderActions(
    () => (
      <div className="flex items-center gap-2">
        {selectedAccount && (
          <Button
            variant={showEnhancedDownloadPanel ? 'secondary' : 'outline'}
            size="sm"
            leftIcon={<Download className="w-4 h-4" />}
            onClick={() => setShowEnhancedDownloadPanel(!showEnhancedDownloadPanel)}
          >
            <span className="hidden sm:inline">{showEnhancedDownloadPanel ? 'Hide' : 'Show'} Download Panel</span>
          </Button>
        )}
        {isAggregationAccount && (
          <Button
            variant="outline"
            size="sm"
            leftIcon={<Network className="w-4 h-4" />}
            onClick={() => setShowHierarchy(!showHierarchy)}
          >
            <span className="hidden sm:inline">{showHierarchy ? 'Hide' : 'Show'} Hierarchy</span>
          </Button>
        )}
        <Button
          variant="outline"
          size="sm"
          leftIcon={<RefreshCw className={cn('w-4 h-4', loading && 'animate-spin')} />}
          onClick={fetchInitialData}
        >
          <span className="hidden sm:inline">Refresh</span>
        </Button>
      </div>
    ),
    [selectedAccount, showEnhancedDownloadPanel, isAggregationAccount, showHierarchy, loading, fetchInitialData],
  );

  // Loading State
  if (loading) {
    return (
      <Page>
        <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
          <div>
            <Skeleton className="h-8 w-48 mb-2" />
            <Skeleton className="h-4 w-64" />
          </div>
          <Skeleton className="h-10 w-24" />
        </div>

        <Card>
          <div className="p-6 space-y-4">
            <Skeleton className="h-5 w-32" />
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
              <Skeleton className="h-10" />
              <Skeleton className="h-10" />
              <Skeleton className="h-10" />
              <Skeleton className="h-10" />
            </div>
          </div>
        </Card>

        <Card padding="none">
          <div className="p-4 border-b border-neutral-200 dark:border-primary-800">
            <Skeleton className="h-5 w-32" />
          </div>
          <div className="p-4 space-y-3">
            {[...Array(3)].map((_, i) => (
              <Skeleton key={i} className="h-16" />
            ))}
          </div>
        </Card>
      </Page>
    );
  }

  return (
    <Page>
      {/* In-body identity (Phase 10). Quick Actions are lifted into the
          Aperture Layout header via usePageHeaderActions above. The
          contextual ISO20022 + Aggregation Account badges remain at the
          page-body level because they describe the *currently selected
          account*, not the page itself. */}
      <PageHeader
        title="Statements"
        description="Generate ISO 20022 camt.053/054-compliant account statements, browse historical statements, and download in XML / PDF / CSV / MT940."
      />

      {/* Contextual badges — render only when there's something to convey. */}
      <div className="flex items-center gap-2">
        <Badge variant="info" size="sm">
          <FileCode className="w-3 h-3 mr-1" />
          ISO 20022
        </Badge>
        {isAggregationAccount && (
          <Badge variant="warning" size="sm">
            <Layers className="w-3 h-3 mr-1" />
            Aggregation Account
          </Badge>
        )}
      </div>

      {/* Headline financial figure — only when a statement is loaded. The
          treasurer's first read should be the closing balance with net
          movement as the secondary support figure. Mirrors the cockpit and
          PhysicalAccounts hero pattern. */}
      {statement && (() => {
        const closingBalance = statement.closingBalance
          ?? statement.balances?.find((b) => b.type === 'CLBD')?.amount
          ?? 0;
        const netMovement = (statement.totalCredits || 0) - (statement.totalDebits || 0);
        const periodLabel = `${statement.fromDate || fromDate} → ${statement.toDate || toDate}`;
        return (
          <HeroMetricCard
            primary={{
              label: 'Closing Balance',
              value: formatCurrency(closingBalance, statementCurrency),
              sub: periodLabel,
            }}
            secondary={{
              label: 'Net Movement',
              value: `${netMovement >= 0 ? '+' : ''}${formatCurrency(netMovement, statementCurrency)}`,
              sub: `${statement.creditCount || 0} credits · ${statement.debitCount || 0} debits`,
            }}
            icon={<Banknote className="w-7 h-7 text-accent-600 dark:text-accent-300" />}
          />
        );
      })()}

      {/* Operational metrics — Statement History totals + sync state.
          Sits under the hero (or alone, on the empty state) so the page
          always has a Phase 5 strip beneath the identity. */}
      <StatStrip>
        <Card hover className="bg-primary-50/40 dark:bg-primary-800/30">
          <div className="flex items-center gap-3">
            <StatusIconBadge tone="primary" icon={FileText} />
            <div>
              <p className="stat-value-sm">{history.length}</p>
              <p className="label">Statements in History</p>
            </div>
          </div>
        </Card>
        <Card hover className="bg-info-50/40 dark:bg-info-500/10">
          <div className="flex items-center gap-3">
            <StatusIconBadge tone="info" icon={Building2} />
            <div>
              <p className="stat-value-sm">{accounts.length}</p>
              <p className="label">Accounts available</p>
            </div>
          </div>
        </Card>
        <Card hover className="bg-success-50/40 dark:bg-success-500/10">
          <div className="flex items-center gap-3">
            <StatusIconBadge tone="success" icon={CheckCircle} />
            <div>
              <p className="stat-value-sm">
                {history.filter((s: any) => s.format === 'CAMT053' || s.format === 'XML').length}
              </p>
              <p className="label">ISO 20022 ready</p>
            </div>
          </div>
        </Card>
        <Card hover className="bg-accent-50/40 dark:bg-accent-500/10">
          <div className="flex items-center gap-3">
            <StatusIconBadge tone="accent" icon={Clock} />
            <div>
              <p className="stat-value-sm">
                {history[0] ? formatDate(history[0].generatedAt) : '—'}
              </p>
              <p className="label">Latest generated</p>
            </div>
          </div>
        </Card>
      </StatStrip>

      {/* Enhanced Hierarchy View (for aggregation accounts) - Using new component */}
      {showHierarchy && selectedAccount && isAggregationAccount && (
        <div className="relative animate-fade-in">
          <Button
            variant="ghost"
            size="sm"
            className="absolute top-4 right-4 z-10"
            onClick={() => setShowHierarchy(false)}
          >
            <X className="w-4 h-4" />
          </Button>
          <VAHierarchyViewer
            accountId={selectedAccount}
            onSelectAccount={handleHierarchyAccountSelect}
            onGenerateStatement={(accountId, vaNumber) => {
              setSelectedAccount(accountId);
              toast.success(`Selected for statement: ${vaNumber}`);
            }}
          />
        </div>
      )}

      {/* Legacy Hierarchy View (fallback when hierarchy data is available) */}
      {showHierarchy && hierarchy && !selectedAccount && (
        <Card className="animate-fade-in">
          <div className="flex items-center gap-3 mb-4">
            <StatusIconBadge tone="info" icon={Network} className="dark:bg-info-500/20" />
            <div>
              <h3 className="section-title">Account Hierarchy</h3>
              <p className="text-xs text-neutral-500 dark:text-neutral-400">
                {aggregatedBalance?.childAccountCount || 0} child accounts |
                Aggregated: {aggregatedBalance ? formatCurrency(aggregatedBalance.aggregatedBalance, aggregatedBalance.currencyCode) : '-'}
              </p>
            </div>
          </div>
          <div className="border rounded-lg p-2 max-h-64 overflow-y-auto">
            <HierarchyTreeNode
              node={hierarchy}
              onSelect={(node) => setSelectedAccount(node.id)}
              selectedId={selectedAccount}
            />
          </div>
        </Card>
      )}

      {/* Enhanced Statement Download Panel (when account is selected) */}
      {selectedAccount && selectedAccountDetails && showEnhancedDownloadPanel && (
        <StatementDownloadPanel
          accountId={selectedAccount}
          vaNumber={selectedAccountDetails.vaNumber}
          accountName={selectedAccountDetails.vaName}
          currencyCode={selectedAccountDetails.currencyCode || 'AED'}
          isAggregationAccount={isAggregationAccount}
          childAccountCount={selectedAccountDetails.childAccountCount}
          onStatementGenerated={handleStatementGenerated}
          onPreview={handlePreview}
        />
      )}

      {/* Statement Generator Card */}
      <Card className="overflow-hidden">
        {/* Flat tonal header — gradients were one of the divergence vectors
            we eliminated in the cockpit + picker work. The icon medallion
            already carries the visual weight; the band only needs a soft
            primary tint to mark it as the section identity. */}
        <div className="p-4 sm:p-6 bg-primary-50/40 border-b border-neutral-100 dark:bg-primary-800/30 dark:border-primary-800/60">
          <div className="flex items-center gap-3">
            <StatusIconBadge tone="primary" icon={FileText} className="dark:bg-primary-700" />
            <div>
              <h3 className="section-title">ISO 20022 Statement Generator</h3>
              <p className="text-xs text-neutral-500 dark:text-neutral-400">Generate camt.053 / 054-compliant statements</p>
            </div>
          </div>
        </div>

        <div className="p-4 sm:p-6 space-y-4">
          {/* Statement Mode Selection */}
          <div className="flex flex-wrap gap-2 pb-4 border-b border-neutral-100 dark:border-primary-800/60">
            {modeOptions.map((mode) => (
              <Button
                key={mode.value}
                variant={statementMode === mode.value ? 'primary' : 'outline'}
                size="sm"
                onClick={() => setStatementMode(mode.value as StatementMode)}
                leftIcon={
                  mode.value === 'camt053' ? <FileText className="w-4 h-4" /> :
                    mode.value === 'camt054' ? <Bell className="w-4 h-4" /> :
                      mode.value === 'aggregated' ? <Layers className="w-4 h-4" /> :
                        <FileText className="w-4 h-4" />
                }
              >
                {mode.label}
              </Button>
            ))}
          </div>

          {/* Desktop Grid */}
          <div className="hidden sm:grid sm:grid-cols-2 lg:grid-cols-5 gap-4">
            <div>
              <label className="field-label block mb-1.5">
                Account
              </label>
              <Select
                value={selectedAccount}
                onChange={(e) => setSelectedAccount(e.target.value)}
                options={accountOptions}
              />
            </div>
            <div>
              <label className="field-label block mb-1.5">
                From Date
              </label>
              <Input
                type="date"
                value={fromDate}
                onChange={(e) => setFromDate(e.target.value)}
                leftIcon={<Calendar className="w-4 h-4" />}
              />
            </div>
            <div>
              <label className="field-label block mb-1.5">
                To Date
              </label>
              <Input
                type="date"
                value={toDate}
                onChange={(e) => setToDate(e.target.value)}
                leftIcon={<Calendar className="w-4 h-4" />}
              />
            </div>
            {statementMode === 'aggregated' && (
              <div className="flex items-end">
                <label className="flex items-center gap-2 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={includeChildren}
                    onChange={(e) => setIncludeChildren(e.target.checked)}
                    className="w-4 h-4 rounded border-neutral-300 text-primary-600 focus:ring-primary-500 dark:border-primary-700 dark:text-primary-200"
                  />
                  <span className="text-sm text-neutral-700 dark:text-neutral-200">Include Children</span>
                </label>
              </div>
            )}
            <div className="flex items-end gap-2">
              <Button
                fullWidth
                onClick={fetchStatement}
                disabled={!selectedAccount || generating}
                leftIcon={generating ? <Loader2 className="w-4 h-4 animate-spin" /> : <CheckCircle className="w-4 h-4" />}
              >
                View
              </Button>
            </div>
          </div>

          {/* Mobile Stack */}
          <div className="sm:hidden space-y-4">
            <div>
              <label className="field-label block mb-1.5">
                Account
              </label>
              <Select
                value={selectedAccount}
                onChange={(e) => setSelectedAccount(e.target.value)}
                options={accountOptions}
              />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="field-label block mb-1.5">
                  From
                </label>
                <Input
                  type="date"
                  value={fromDate}
                  onChange={(e) => setFromDate(e.target.value)}
                />
              </div>
              <div>
                <label className="field-label block mb-1.5">
                  To
                </label>
                <Input
                  type="date"
                  value={toDate}
                  onChange={(e) => setToDate(e.target.value)}
                />
              </div>
            </div>
            <Button
              fullWidth
              onClick={fetchStatement}
              disabled={!selectedAccount || generating}
              leftIcon={generating ? <Loader2 className="w-4 h-4 animate-spin" /> : <CheckCircle className="w-4 h-4" />}
            >
              View Statement
            </Button>
          </div>
        </div>
      </Card>

      {/* Error Alert */}
      {error && (
        <Card className="bg-error-50 border-error-200 animate-fade-in dark:bg-error-500/10 dark:border-error-500/30">
          <div className="flex items-center gap-3">
            <StatusIconBadge tone="error" icon={AlertCircle} className="shrink-0 dark:bg-error-500/20" />
            <div className="flex-1 min-w-0">
              <p className="font-medium text-error-800 dark:text-error-300">Error</p>
              <p className="text-sm text-error-700 truncate dark:text-error-300">{error}</p>
            </div>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => setError(null)}
              className="text-error-600 dark:text-error-300"
            >
              Dismiss
            </Button>
          </div>
        </Card>
      )}

      {/* Statement View */}
      {statement && (
        <Card padding="none" className="animate-fade-in overflow-hidden">
          {/* Statement Header — flat tonal band (was a gradient). */}
          <div className="p-4 sm:p-6 border-b border-neutral-200 bg-neutral-50/60 dark:border-primary-800 dark:bg-primary-950">
            <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
              <div>
                <div className="flex items-center gap-2 mb-1">
                  <h3 className="section-title">
                    {statementMode === 'camt053' ? 'camt.053 Statement' :
                      statementMode === 'camt054' ? 'camt.054 Notifications' :
                        statementMode === 'aggregated' ? 'Aggregated Statement' :
                          'Account Statement'}
                  </h3>
                  <Badge variant="info" size="sm">ISO20022</Badge>
                  {statement.notificationInfo?.notificationType && (
                    <Badge
                      variant={statement.notificationInfo.notificationType === 'CREDIT' ? 'success' : 'error'}
                      size="sm"
                    >
                      {statement.notificationInfo.notificationType}
                    </Badge>
                  )}
                </div>
                <p className="text-sm text-neutral-500 dark:text-neutral-400">
                  {statement.account?.name || statement.vaNumber || statement.vaName} |
                  {statement.account?.iban || statement.viban || ''}
                </p>
                <p className="text-xs text-neutral-400 mt-1 dark:text-neutral-500">
                  Period: {statement.fromDate || fromDate} to {statement.toDate || toDate}
                </p>
              </div>
              <div className="flex flex-wrap gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  leftIcon={downloading ? <Loader2 className="w-4 h-4 animate-spin" /> : <Download className="w-4 h-4" />}
                  onClick={downloadXmlStatement}
                  disabled={downloading}
                >
                  XML
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  leftIcon={<Download className="w-4 h-4" />}
                  onClick={() => generateStatement('PDF')}
                >
                  PDF
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  leftIcon={<Download className="w-4 h-4" />}
                  onClick={() => generateStatement('CSV')}
                >
                  CSV
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  leftIcon={<Download className="w-4 h-4" />}
                  onClick={() => generateStatement('MT940')}
                >
                  MT940
                </Button>
              </div>
            </div>
          </div>

          {/* Group Header Section (camt.053/054) */}
          {statement.groupHeader && (
            <SectionCard
              title="Group Header"
              icon={<FileCode className="w-4 h-4 text-primary-600 dark:text-primary-200" />}
              collapsible
              defaultOpen={false}
              className="mx-4 mt-4 sm:mx-6"
            >
              <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
                <InfoRow label="Message ID" value={statement.groupHeader.messageId} copyable icon={<Hash className="w-3 h-3" />} />
                <InfoRow label="Creation Date/Time" value={formatDateTime(statement.groupHeader.creationDateTime)} icon={<Clock className="w-3 h-3" />} />
                {statement.groupHeader.messagePagination && (
                  <>
                    <InfoRow label="Page Number" value={statement.groupHeader.messagePagination.pageNumber} />
                    <InfoRow label="Last Page" value={statement.groupHeader.messagePagination.lastPageIndicator ? 'Yes' : 'No'} />
                  </>
                )}
              </div>
            </SectionCard>
          )}

          {/* Statement Header Section */}
          {(statement.statementId || statement.electronicSeqNumber || statement.legalSeqNumber) && (
            <SectionCard
              title="Statement Header"
              icon={<FileText className="w-4 h-4 text-primary-600 dark:text-primary-200" />}
              collapsible
              defaultOpen={false}
              className="mx-4 mt-4 sm:mx-6"
            >
              <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
                <InfoRow label="Statement ID" value={statement.statementId} copyable icon={<Hash className="w-3 h-3" />} />
                <InfoRow label="Electronic Seq No" value={statement.electronicSeqNumber} />
                <InfoRow label="Legal Seq No" value={statement.legalSeqNumber} />
                <InfoRow label="Creation Date/Time" value={statement.creationDateTime ? formatDateTime(statement.creationDateTime) : undefined} icon={<Clock className="w-3 h-3" />} />
                <InfoRow label="From Date" value={statement.fromDate} icon={<Calendar className="w-3 h-3" />} />
                <InfoRow label="To Date" value={statement.toDate} icon={<Calendar className="w-3 h-3" />} />
                {statement.copyDuplicateIndicator && (
                  <InfoRow label="Copy/Duplicate" value={statement.copyDuplicateIndicator} />
                )}
              </div>
            </SectionCard>
          )}

          {/* Account Information Section */}
          {statement.account && (
            <SectionCard
              title="Account Information"
              icon={<Building2 className="w-4 h-4 text-primary-600 dark:text-primary-200" />}
              collapsible
              defaultOpen={false}
              className="mx-4 mt-4 sm:mx-6"
            >
              <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
                <InfoRow label="Account ID" value={statement.account.id} copyable icon={<Hash className="w-3 h-3" />} />
                <InfoRow label="IBAN" value={statement.account.iban} copyable />
                <InfoRow label="Account Name" value={statement.account.name} />
                <InfoRow label="Currency" value={statement.account.currency} icon={<Banknote className="w-3 h-3" />} />
                <InfoRow label="Owner Name" value={statement.account.ownerName} icon={<User className="w-3 h-3" />} />
                <InfoRow label="Servicer BIC" value={statement.account.servicerBic} copyable />
                <InfoRow label="Servicer Name" value={statement.account.servicerName} />
                <InfoRow label="Account Type" value={statement.account.accountType} />
              </div>
            </SectionCard>
          )}

          {/* Notification Info (camt.054 specific) */}
          {statement.notificationInfo && (
            <SectionCard
              title="Notification Info"
              icon={<Bell className="w-4 h-4 text-warning-600 dark:text-warning-300" />}
              className="mx-4 mt-4 sm:mx-6"
              badge={
                <Badge
                  variant={statement.notificationInfo.notificationType === 'CREDIT' ? 'success' : 'error'}
                  size="sm"
                >
                  {statement.notificationInfo.notificationType}
                </Badge>
              }
            >
              <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
                <InfoRow label="Notification ID" value={statement.notificationInfo.notificationId} copyable />
                <InfoRow label="Type" value={statement.notificationInfo.notificationType} />
                <InfoRow label="Priority" value={statement.notificationInfo.priority} />
                {statement.notificationInfo.realTimeTimestamp && (
                  <InfoRow label="Real-time Timestamp" value={formatDateTime(statement.notificationInfo.realTimeTimestamp)} />
                )}
              </div>
            </SectionCard>
          )}

          {/* Balances Section (ISO20022 Balance Types) */}
          {statement.balances && statement.balances.length > 0 && (
            <div className="px-4 py-4 sm:px-6 border-b border-neutral-200 dark:border-primary-800">
              <h4 className="section-title mb-3 flex items-center gap-2">
                <Banknote className="w-4 h-4" />
                Balances
              </h4>
              <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-5 gap-3">
                {statement.balances.map((balance, idx) => (
                  <BalanceCard
                    key={`${balance.type}-${idx}`}
                    type={balance.type}
                    amount={balance.amount}
                    currency={balance.currency}
                    date={balance.date}
                    creditDebit={balance.creditDebitIndicator}
                  />
                ))}
              </div>
            </div>
          )}

          {/* Summary Stats (Fallback for legacy format) */}
          {!statement.balances?.length && (
            <div className="p-4 sm:p-6 bg-neutral-50 border-b border-neutral-200 dark:bg-primary-950 dark:border-primary-800">
              <div className="grid grid-cols-2 lg:grid-cols-4 gap-3 sm:gap-4">
                <StatCard
                  label="Opening Balance"
                  value={formatCurrency(
                    statement.openingBalance || statement.balances?.find((b) => b.type === 'OPBD')?.amount || 0,
                    statementCurrency
                  )}
                  icon={FileText}
                  color="primary"
                />
                <StatCard
                  label="Total Credits"
                  value={`+${formatCurrency(statement.totalCredits || 0, statementCurrency)}`}
                  icon={TrendingUp}
                  color="success"
                  trend={statement.creditCount ? `${statement.creditCount} txns` : undefined}
                />
                <StatCard
                  label="Total Debits"
                  value={`-${formatCurrency(statement.totalDebits || 0, statementCurrency)}`}
                  icon={TrendingDown}
                  color="danger"
                  trend={statement.debitCount ? `${statement.debitCount} txns` : undefined}
                />
                <StatCard
                  label="Closing Balance"
                  value={formatCurrency(
                    statement.closingBalance || statement.balances?.find((b) => b.type === 'CLBD')?.amount || 0,
                    statementCurrency
                  )}
                  icon={CheckCircle}
                  color="info"
                />
              </div>
            </div>
          )}

          {/* Transaction Summary */}
          {(statement.totalEntries || statement.creditCount || statement.debitCount) && (
            <SectionCard
              title="Transaction Summary"
              icon={<Receipt className="w-4 h-4 text-primary-600 dark:text-primary-200" />}
              className="mx-4 mt-4 sm:mx-6"
            >
              {/* Transaction-summary tiles — `.stat-value-sm` carries the
                  Phase 9 display tier (Fraunces serif + tabular-nums) for
                  every figure. Tone overlays only set the colour. */}
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
                <div className="text-center p-3 bg-neutral-50 rounded-lg dark:bg-primary-950">
                  <p className="stat-value-sm">{statement.totalEntries || (statement.entries?.length || 0)}</p>
                  <p className="text-xs text-neutral-500 dark:text-neutral-400">Total Entries</p>
                </div>
                <div className="text-center p-3 bg-success-50 rounded-lg dark:bg-success-500/10">
                  <p className="stat-value-sm text-success-600 dark:text-success-300">+{formatCurrency(statement.totalCredits || 0, statementCurrency)}</p>
                  <p className="text-xs text-neutral-500 dark:text-neutral-400">{statement.creditCount || 0} Credits</p>
                </div>
                <div className="text-center p-3 bg-error-50 rounded-lg dark:bg-error-500/10">
                  <p className="stat-value-sm text-error-600 dark:text-error-300">-{formatCurrency(statement.totalDebits || 0, statementCurrency)}</p>
                  <p className="text-xs text-neutral-500 dark:text-neutral-400">{statement.debitCount || 0} Debits</p>
                </div>
                <div className={cn(
                  'text-center p-3 rounded-lg',
                  (statement.totalCredits || 0) >= (statement.totalDebits || 0) ? 'bg-success-50 dark:bg-success-500/10' : 'bg-error-50 dark:bg-error-500/10',
                )}>
                  <p className={cn(
                    'stat-value-sm',
                    (statement.totalCredits || 0) >= (statement.totalDebits || 0) ? 'text-success-600 dark:text-success-300' : 'text-error-600 dark:text-error-300',
                  )}>
                    {(statement.totalCredits || 0) >= (statement.totalDebits || 0) ? '+' : ''}
                    {formatCurrency((statement.totalCredits || 0) - (statement.totalDebits || 0), statementCurrency)}
                  </p>
                  <p className="text-xs text-neutral-500 dark:text-neutral-400">Net Amount</p>
                </div>
              </div>
            </SectionCard>
          )}

          {/* Aggregated Child Summary (for aggregated statements) */}
          {statementMode === 'aggregated' && statement.childStatements && statement.childStatements.length > 0 && (
            <div className="p-4 sm:p-6 border-b border-neutral-200 dark:border-primary-800">
              <h4 className="section-title mb-3 flex items-center gap-2">
                <Layers className="w-4 h-4" />
                Child Account Summary
              </h4>
              <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
                {statement.childStatements.map((child) => (
                  <div key={child.vaId} className="p-3 bg-neutral-50 rounded-lg dark:bg-primary-950">
                    <p className="body-sm text-primary-900 truncate dark:text-neutral-50">{child.vaName}</p>
                    <p className="code">{child.vaNumber}</p>
                    <div className="mt-2 flex justify-between text-xs">
                      <span className="text-success-600 dark:text-success-300">+{formatCurrency(child.totalCredits, child.currencyCode)}</span>
                      <span className="text-error-600 dark:text-error-300">-{formatCurrency(child.totalDebits, child.currencyCode)}</span>
                      <span className="font-medium">{child.entryCount} txns</span>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Entry List Header */}
          <div className="px-4 py-3 sm:px-6 border-b border-neutral-200 bg-neutral-50 dark:border-primary-800 dark:bg-primary-950">
            <div className="flex items-center justify-between">
              <h4 className="section-title flex items-center gap-2">
                Entry List
                <Badge variant="neutral" size="sm">
                  {statement.entries?.length || statement.transactions?.length || 0} entries
                </Badge>
              </h4>
              <p className="text-xs text-neutral-500 hidden sm:block dark:text-neutral-400">
                Click on any entry to expand details
              </p>
            </div>
          </div>

          {/* Transactions/Entries */}
          {((statement.entries && statement.entries.length > 0) ||
            (statement.transactions && statement.transactions.length > 0)) ? (
            <>
              {/* Desktop Table */}
              <div className="hidden md:block overflow-x-auto">
                <table className="data-table">
                  <thead className="data-table-header">
                    <tr>
                      <th className="data-table-header-cell w-10"></th>
                      <th className="data-table-header-cell">Date</th>
                      <th className="data-table-header-cell">Reference</th>
                      <th className="data-table-header-cell">Description</th>
                      <th className="data-table-header-cell">Status</th>
                      <th className="data-table-header-cell text-right">Debit</th>
                      <th className="data-table-header-cell text-right">Credit</th>
                      <th className="data-table-header-cell text-right">Balance</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-neutral-100 dark:divide-primary-800/60">
                    {/* ISO20022 entries (camt.053/054) */}
                    {statement.entries?.map((entry, idx) => (
                      <ISO20022TransactionRow
                        key={entry.entryReference || idx}
                        entry={entry}
                        currencyCode={statementCurrency}
                        index={idx}
                      />
                    ))}
                    {/* Legacy transactions */}
                    {statement.transactions?.map((tx, idx) => (
                      <TransactionRow
                        key={idx}
                        transaction={tx}
                        currencyCode={statement.currencyCode || 'AED'}
                      />
                    ))}
                  </tbody>
                </table>
              </div>

              {/* Mobile Cards */}
              <div className="md:hidden">
                {statement.entries?.map((entry, idx) => (
                  <TransactionMobileCard
                    key={entry.entryReference || idx}
                    transaction={entry}
                    currencyCode={statementCurrency}
                    index={idx}
                    isISO20022={true}
                  />
                ))}
                {statement.transactions?.map((tx, idx) => (
                  <TransactionMobileCard
                    key={idx}
                    transaction={tx}
                    currencyCode={statement.currencyCode || 'AED'}
                    index={idx}
                    isISO20022={false}
                  />
                ))}
              </div>
            </>
          ) : (
            <div className="p-8">
              <EmptyState
                icon={<FileText className="w-12 h-12" />}
                title="No transactions"
                description="No transactions found in this period"
              />
            </div>
          )}
        </Card>
      )}

      {/* Statement History */}
      <Card padding="none">
        <div className="p-4 sm:p-6 border-b border-neutral-200 dark:border-primary-800">
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
            <div className="flex items-center gap-3">
              <StatusIconBadge tone="neutral" icon={Clock} size="sm" rounded="lg" className="dark:bg-primary-800" />
              <h3 className="section-title">Statement History</h3>
            </div>
            <div className="flex flex-col sm:flex-row gap-2">
              <Input
                placeholder="Search..."
                value={historySearch}
                onChange={(e) => setHistorySearch(e.target.value)}
                leftIcon={<Search className="w-4 h-4" />}
                className="sm:w-48"
              />
              <Select
                value={historyFormatFilter}
                onChange={(e) => setHistoryFormatFilter(e.target.value as ISO20022StatementFormat | '')}
                options={[
                  { value: '', label: 'All Formats' },
                  { value: 'PDF', label: 'PDF' },
                  { value: 'JSON', label: 'JSON' },
                  { value: 'XML', label: 'XML' },
                  { value: 'CAMT053', label: 'CAMT.053' },
                  { value: 'MT940', label: 'MT940' },
                ]}
                className="sm:w-36"
              />
            </div>
          </div>
        </div>

        {filteredHistory.length > 0 ? (
          <>
            {/* Desktop Table */}
            <div className="hidden md:block overflow-x-auto">
              <table className="data-table">
                <thead className="data-table-header">
                  <tr>
                    <th className="data-table-header-cell">Reference</th>
                    <th className="data-table-header-cell">Account</th>
                    <th className="data-table-header-cell">Period</th>
                    <th className="data-table-header-cell">Format</th>
                    <th className="data-table-header-cell">Generated</th>
                    <th className="data-table-header-cell text-right">Actions</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-neutral-100 dark:divide-primary-800/60">
                  {filteredHistory.map((stmt: any) => (
                    <tr key={stmt.id} className="data-table-row group">
                      <td className="data-table-cell">
                        <span className="font-mono text-sm text-primary-900 dark:text-neutral-50">
                          {stmt.statementReference}
                        </span>
                      </td>
                      <td className="data-table-cell">
                        <span className="text-sm text-neutral-700 dark:text-neutral-200">{stmt.vaNumber}</span>
                      </td>
                      <td className="data-table-cell">
                        <span className="text-sm text-neutral-700 dark:text-neutral-200">
                          {stmt.fromDate} - {stmt.toDate}
                        </span>
                      </td>
                      <td className="data-table-cell">
                        <Badge
                          variant={stmt.format === 'XML' || stmt.format === 'CAMT053' ? 'info' : 'neutral'}
                          size="sm"
                        >
                          {stmt.format}
                        </Badge>
                      </td>
                      <td className="data-table-cell">
                        <span className="text-sm text-neutral-500 dark:text-neutral-400">
                          {new Date(stmt.generatedAt).toLocaleDateString()}
                        </span>
                      </td>
                      <td className="data-table-cell text-right">
                        <div className="flex items-center justify-end gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                          <Button
                            size="sm"
                            variant="ghost"
                            leftIcon={<Eye className="w-4 h-4" />}
                            onClick={() => {
                              setSelectedAccount(stmt.accountId || stmt.vaId);
                              setFromDate(stmt.fromDate);
                              setToDate(stmt.toDate);
                              setShowPreviewModal(true);
                            }}
                          >
                            View
                          </Button>
                          <Button
                            size="sm"
                            variant="ghost"
                            leftIcon={<Download className="w-4 h-4" />}
                            onClick={() => handleDownloadFromHistory(stmt.statementReference)}
                          >
                            Download
                          </Button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* Mobile Cards */}
            <div className="md:hidden p-4 space-y-3">
              {filteredHistory.map((stmt: any, idx: number) => (
                <HistoryMobileCard
                  key={stmt.id}
                  statement={stmt}
                  index={idx}
                  onDownload={(s) => handleDownloadFromHistory(s.statementReference)}
                />
              ))}
            </div>
          </>
        ) : (
          <div className="p-8">
            <EmptyState
              icon={<FileText className="w-12 h-12" />}
              title={historySearch || historyFormatFilter ? "No matching statements" : "No statement history"}
              description={historySearch || historyFormatFilter ? "Try adjusting your search or filters" : "Generated statements will appear here"}
            />
          </div>
        )}
      </Card>

      {/* Statement Preview Modal */}
      {showPreviewModal && selectedAccount && selectedAccountDetails && (
        <StatementPreviewModal
          isOpen={showPreviewModal}
          onClose={() => setShowPreviewModal(false)}
          accountId={selectedAccount}
          vaNumber={selectedAccountDetails.vaNumber}
          accountName={selectedAccountDetails.vaName}
          currency={selectedAccountDetails.currencyCode || 'AED'}
          fromDate={fromDate}
          toDate={toDate}
          includeChildAccounts={includeChildren}
          statementType={statementMode === 'camt054' ? 'camt054' : 'camt053'}
        />
      )}
    </Page>
  );
};

export default StatementsPage;
