// ============================================================================
// STATEMENT PREVIEW MODAL - ISO20022 Statement Viewer (Enhanced)
// ============================================================================
// Features:
// - Full ISO20022 camt.053/054 field display
// - Group Header, Statement Header, Account Info sections
// - Multiple balance types (OPBD, CLBD, CLAV, FWAV, ITBD)
// - Transaction summary with counts
// - Expandable entry details with all transaction references
// - Related parties (Debtor/Creditor) display
// - Remittance information (structured/unstructured)
// - Bank Transaction Codes (Domain/Family/SubFamily)
// - Color coding for credits/debits
// - Status badges (BOOK, PDNG, INFO)
// - Copy buttons for reference IDs
// - Mobile responsive design
// ============================================================================

import React, { useState, useEffect, useCallback, useMemo } from 'react';
import {
  FileText,
  Search,
  ChevronLeft,
  ChevronRight,
  ChevronDown,
  ChevronUp,
  ArrowUpRight,
  ArrowDownLeft,
  Loader2,
  Calendar,
  TrendingUp,
  TrendingDown,
  RefreshCw,
  FileJson,
  FileCode,
  Copy,
  CheckCircle,
  Building2,
  User,
  Hash,
  Clock,
  CreditCard,
  AlertCircle,
  Info,
  RotateCcw,
  Banknote,
  Receipt,
  ExternalLink,
  Bell,
} from 'lucide-react';
import { Button, Badge, Input, Skeleton, Card, Divider } from '../ui';
import { Modal } from '../ui/enhanced';
import { cn, formatCurrency, formatDate, formatDateTime, copyToClipboard } from '../../utils';
import { statementsApi } from '../../services/api';
import type {
  ISO20022Statement,
  StatementEntry,
  StatementSummary,
  StatementBalance,
  StatementAccountInfo,
  ISO20022StatementFormat,
  StatementRequest,
  CreditDebitIndicator,
  StatementEntryStatus,
  StatementBalanceType,
} from '../../types';
import toast from 'react-hot-toast';

// ============================================================================
// EXTENDED TYPES FOR ISO20022 FIELDS
// ============================================================================

// Extended Entry Details for camt.053/054
interface ISO20022EntryDetails {
  // Transaction References
  messageId?: string;
  endToEndId?: string;
  transactionId?: string;
  instructionId?: string;
  accountServicerRef?: string;
  paymentInfoId?: string;
  mandateId?: string;
  chequeNumber?: string;
  clearingSystemRef?: string;

  // Bank Transaction Code (Domain/Family/SubFamily)
  bankTransactionCode?: {
    domain?: string;
    family?: string;
    subFamily?: string;
    proprietaryCode?: string;
    proprietaryIssuer?: string;
  };

  // Related Parties
  debtor?: {
    name?: string;
    postalAddress?: string;
    accountIban?: string;
    accountOther?: string;
    agentBic?: string;
    agentName?: string;
  };
  creditor?: {
    name?: string;
    postalAddress?: string;
    accountIban?: string;
    accountOther?: string;
    agentBic?: string;
    agentName?: string;
  };
  ultimateDebtor?: {
    name?: string;
    identification?: string;
  };
  ultimateCreditor?: {
    name?: string;
    identification?: string;
  };

  // Remittance Information
  remittanceInfo?: {
    unstructured?: string[];
    structured?: {
      referredDocuments?: Array<{
        type?: string;
        number?: string;
        relatedDate?: string;
      }>;
      creditorReference?: string;
      invoiceNumber?: string;
      invoiceDate?: string;
      invoiceAmount?: number;
    };
  };

  // Additional Entry Info
  additionalInfo?: string;
  returnInfo?: {
    originalReference?: string;
    reason?: string;
    additionalInfo?: string;
  };

  // Charges
  charges?: Array<{
    amount: number;
    currency: string;
    bearer?: string;
    agent?: string;
  }>;

  // Exchange Rate Info
  exchangeRate?: {
    originalAmount?: number;
    originalCurrency?: string;
    rate?: number;
    contractId?: string;
  };
}

// Extended Statement Entry with full details
interface ExtendedStatementEntry extends StatementEntry {
  // Entry Reference (NtryRef)
  entryReference?: string;
  // Account Servicer Reference
  accountServicerRef?: string;
  // Reversal Indicator
  reversalIndicator?: boolean;
  // Bank Transaction Code
  bankTransactionCode?: string;
  // Additional Entry Info
  additionalEntryInfo?: string;
  // Entry Details (expandable)
  entryDetails?: ISO20022EntryDetails;
  // Batch information (for batch entries)
  batchInfo?: {
    numberOfTransactions?: number;
    totalAmount?: number;
  };
}

// Extended Statement with full ISO20022 fields
interface ExtendedISO20022Statement extends ISO20022Statement {
  // Group Header (GrpHdr)
  groupHeader?: {
    messageId: string;
    creationDateTime: string;
    messagePagination?: {
      pageNumber: number;
      lastPageIndicator: boolean;
    };
    additionalInfo?: string;
  };

  // Notification specific (camt.054)
  notificationInfo?: {
    notificationId?: string;
    notificationType?: 'CREDIT' | 'DEBIT';
    priority?: 'HIGH' | 'NORMAL' | 'LOW';
    realTimeTimestamp?: string;
  };

  // Extended entries
  entries: ExtendedStatementEntry[];
}

// ============================================================================
// PROPS INTERFACE
// ============================================================================

interface StatementPreviewModalProps {
  /** Whether the modal is open */
  isOpen: boolean;
  /** Close handler */
  onClose: () => void;
  /** Account ID */
  accountId: string;
  /** VA Number */
  vaNumber: string;
  /** Account Name */
  accountName: string;
  /** Currency */
  currency: string;
  /** From Date */
  fromDate: string;
  /** To Date */
  toDate: string;
  /** Include child accounts */
  includeChildAccounts?: boolean;
  /** Pre-loaded statement data (optional) */
  initialStatement?: ExtendedISO20022Statement;
  /** Statement type (camt.053 or camt.054) */
  statementType?: 'camt053' | 'camt054';
}

// ============================================================================
// COPY BUTTON COMPONENT
// ============================================================================

interface CopyButtonProps {
  value: string;
  label?: string;
  className?: string;
}

const CopyButton: React.FC<CopyButtonProps> = ({ value, label, className }) => {
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    await copyToClipboard(value);
    setCopied(true);
    toast.success('Copied to clipboard');
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <button
      onClick={handleCopy}
      className={cn(
        'inline-flex items-center gap-1.5 text-xs font-mono text-neutral-600 dark:text-neutral-300 hover:text-primary-600 dark:text-primary-200 transition-colors group',
        className
      )}
      title={`Copy ${label || 'value'}`}
    >
      <span className="truncate max-w-[180px]">{value}</span>
      {copied ? (
        <CheckCircle className="w-3.5 h-3.5 text-success-500 shrink-0" />
      ) : (
        <Copy className="w-3.5 h-3.5 opacity-0 group-hover:opacity-100 transition-opacity shrink-0" />
      )}
    </button>
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
  className?: string;
}

const InfoRow: React.FC<InfoRowProps> = ({ label, value, copyable, icon, className }) => {
  if (value === undefined || value === null || value === '') return null;

  return (
    <div className={cn('flex items-start justify-between py-1.5', className)}>
      <span className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500 flex items-center gap-1.5">
        {icon}
        {label}
      </span>
      {copyable && typeof value === 'string' ? (
        <CopyButton value={value} label={label} />
      ) : (
        <span className="text-xs font-medium text-primary-900 dark:text-neutral-50 text-right max-w-[200px] truncate">
          {value}
        </span>
      )}
    </div>
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
    <div className={cn('bg-white dark:bg-primary-900 rounded-xl border border-neutral-200 dark:border-primary-800 overflow-hidden', className)}>
      <div
        className={cn(
          'flex items-center justify-between px-4 py-3 bg-neutral-50 dark:bg-primary-950',
          collapsible && 'cursor-pointer hover:bg-neutral-100 dark:hover:bg-primary-800 dark:bg-primary-800 transition-colors'
        )}
        onClick={collapsible ? () => setIsOpen(!isOpen) : undefined}
      >
        <div className="flex items-center gap-2">
          <div className="w-7 h-7 rounded-lg bg-primary-100 flex items-center justify-center dark:bg-primary-700">
            {icon}
          </div>
          <h4 className="text-sm font-semibold text-primary-900 dark:text-neutral-50">{title}</h4>
          {badge}
        </div>
        {collapsible && (
          <button className="p-1 hover:bg-neutral-200 rounded transition-colors">
            {isOpen ? (
              <ChevronUp className="w-4 h-4 text-neutral-500 dark:text-neutral-400 dark:text-neutral-500" />
            ) : (
              <ChevronDown className="w-4 h-4 text-neutral-500 dark:text-neutral-400 dark:text-neutral-500" />
            )}
          </button>
        )}
      </div>
      {isOpen && <div className="px-4 py-3 border-t border-neutral-100 dark:border-primary-800/60">{children}</div>}
    </div>
  );
};

// ============================================================================
// BALANCE CARD COMPONENT
// ============================================================================

interface BalanceCardProps {
  type: StatementBalanceType;
  amount: number;
  currency: string;
  date: string;
  creditDebit: CreditDebitIndicator;
}

const BalanceCard: React.FC<BalanceCardProps> = ({ type, amount, currency, date, creditDebit }) => {
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
    OPAV: 'bg-neutral-50 dark:bg-primary-950 border-neutral-200 dark:border-primary-800',
    CLAV: 'bg-success-50 border-success-200 dark:bg-success-500/10 dark:border-success-500/30',
    FWAV: 'bg-warning-50 border-warning-200 dark:bg-warning-500/10 dark:border-warning-500/30',
    INFO: 'bg-neutral-50 dark:bg-primary-950 border-neutral-200 dark:border-primary-800',
  };

  const isDebit = creditDebit === 'DBIT';

  return (
    <div className={cn('rounded-lg border p-3', typeColors[type])}>
      <div className="flex items-center justify-between mb-2">
        <span className="text-xs font-medium text-neutral-600 dark:text-neutral-300">{typeLabels[type]}</span>
        <Badge variant="neutral" size="xs">{type}</Badge>
      </div>
      <p className={cn(
        'text-lg font-bold tabular-nums',
        isDebit ? 'text-error-600 dark:text-error-300' : 'text-primary-900 dark:text-neutral-50'
      )}>
        {isDebit && '-'}{formatCurrency(amount, currency)}
      </p>
      <p className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500 mt-1">{formatDate(date)}</p>
    </div>
  );
};

// ============================================================================
// TRANSACTION SUMMARY CARD
// ============================================================================

interface TransactionSummaryProps {
  summary: StatementSummary;
  totalEntries: number;
}

const TransactionSummaryCard: React.FC<TransactionSummaryProps> = ({ summary, totalEntries }) => {
  const netAmount = summary.totalCredits - summary.totalDebits;
  const isNetPositive = netAmount >= 0;

  return (
    <SectionCard
      title="Transaction Summary"
      icon={<Receipt className="w-4 h-4 text-primary-600 dark:text-primary-200" />}
    >
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
        <div className="text-center p-3 bg-neutral-50 dark:bg-primary-950 rounded-lg">
          <p className="stat-value-sm">{totalEntries}</p>
          <p className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">Total Entries</p>
        </div>
        <div className="text-center p-3 bg-success-50 rounded-lg dark:bg-success-500/10">
          <p className="text-lg font-bold text-success-600 dark:text-success-300">+{formatCurrency(summary.totalCredits, summary.currency)}</p>
          <p className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">{summary.creditCount} Credits</p>
        </div>
        <div className="text-center p-3 bg-error-50 rounded-lg dark:bg-error-500/10">
          <p className="text-lg font-bold text-error-600 dark:text-error-300">-{formatCurrency(summary.totalDebits, summary.currency)}</p>
          <p className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">{summary.debitCount} Debits</p>
        </div>
        <div className={cn('text-center p-3 rounded-lg', isNetPositive ? 'bg-success-50 dark:bg-success-500/10' : 'bg-error-50 dark:bg-error-500/10')}>
          <p className={cn('text-lg font-bold', isNetPositive ? 'text-success-600 dark:text-success-300' : 'text-error-600 dark:text-error-300')}>
            {isNetPositive ? '+' : ''}{formatCurrency(netAmount, summary.currency)}
          </p>
          <p className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">Net Amount</p>
        </div>
      </div>
    </SectionCard>
  );
};

// ============================================================================
// ENTRY STATUS BADGE
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

  const labels: Record<StatementEntryStatus, string> = {
    BOOK: 'Booked',
    PDNG: 'Pending',
    INFO: 'Info',
    FUTR: 'Future',
  };

  return (
    <Badge variant={variants[status]} size="sm">
      {labels[status]}
    </Badge>
  );
};

// ============================================================================
// EXPANDABLE ENTRY ROW
// ============================================================================

interface ExpandableEntryRowProps {
  entry: ExtendedStatementEntry;
  currency: string;
  index: number;
}

const ExpandableEntryRow: React.FC<ExpandableEntryRowProps> = ({ entry, currency, index }) => {
  const [expanded, setExpanded] = useState(false);
  const [copied, setCopied] = useState<string | null>(null);
  const isCredit = entry.creditDebit === 'CRDT';

  const handleCopy = async (value: string, field: string) => {
    await copyToClipboard(value);
    setCopied(field);
    toast.success('Copied to clipboard');
    setTimeout(() => setCopied(null), 2000);
  };

  const details = entry.entryDetails;

  return (
    <div
      className={cn(
        'border-b border-neutral-100 dark:border-primary-800/60 last:border-0 animate-fade-in',
        expanded && 'bg-neutral-50 dark:bg-primary-950'
      )}
      style={{ animationDelay: `${index * 0.02}s` }}
    >
      {/* Main Row */}
      <div
        className="flex items-center gap-4 px-4 py-3 cursor-pointer hover:bg-neutral-50 dark:hover:bg-primary-800/50 dark:bg-primary-950 transition-colors"
        onClick={() => setExpanded(!expanded)}
      >
        {/* Expand Button */}
        <button className="p-1 hover:bg-neutral-200 rounded shrink-0">
          {expanded ? (
            <ChevronUp className="w-4 h-4 text-neutral-500 dark:text-neutral-400 dark:text-neutral-500" />
          ) : (
            <ChevronDown className="w-4 h-4 text-neutral-500 dark:text-neutral-400 dark:text-neutral-500" />
          )}
        </button>

        {/* Direction Icon */}
        <div className={cn(
          'w-9 h-9 rounded-lg flex items-center justify-center shrink-0',
          isCredit ? 'bg-success-50 dark:bg-success-500/10' : 'bg-error-50 dark:bg-error-500/10'
        )}>
          {isCredit ? (
            <ArrowDownLeft className="w-4 h-4 text-success-600 dark:text-success-300" />
          ) : (
            <ArrowUpRight className="w-4 h-4 text-error-600 dark:text-error-300" />
          )}
          {entry.reversalIndicator && (
            <RotateCcw className="w-3 h-3 text-warning-500 absolute -bottom-1 -right-1" />
          )}
        </div>

        {/* Entry Reference */}
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <p className="text-sm font-medium text-primary-900 dark:text-neutral-50 truncate">
              {entry.entryReference || entry.reference}
            </p>
            {entry.reversalIndicator && (
              <Badge variant="warning" size="xs">Reversal</Badge>
            )}
          </div>
          <p className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500 truncate">
            {entry.description || entry.remittanceInfo || '-'}
          </p>
        </div>

        {/* Booking Date */}
        <div className="hidden sm:block text-right shrink-0">
          <p className="text-sm text-primary-900 dark:text-neutral-50">{formatDate(entry.bookingDate)}</p>
          {entry.valueDate !== entry.bookingDate && (
            <p className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">Val: {formatDate(entry.valueDate)}</p>
          )}
        </div>

        {/* Status */}
        <div className="hidden md:block shrink-0">
          <StatusBadge status={entry.status} />
        </div>

        {/* Amount */}
        <div className="text-right shrink-0 min-w-[100px]">
          <p className={cn(
            'text-sm font-bold tabular-nums',
            isCredit ? 'text-success-600 dark:text-success-300' : 'text-error-600 dark:text-error-300'
          )}>
            {isCredit ? '+' : '-'}{formatCurrency(entry.amount, currency)}
          </p>
          {entry.balanceAfter !== undefined && (
            <p className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">
              Bal: {formatCurrency(entry.balanceAfter, currency)}
            </p>
          )}
        </div>
      </div>

      {/* Expanded Details */}
      {expanded && (
        <div className="px-4 pb-4 ml-14 animate-fade-in">
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {/* Transaction References */}
            <div className="bg-white dark:bg-primary-900 rounded-lg border border-neutral-200 dark:border-primary-800 p-3">
              <h5 className="text-xs font-semibold text-neutral-700 dark:text-neutral-200 mb-2 flex items-center gap-1.5">
                <Hash className="w-3.5 h-3.5" />
                Transaction References
              </h5>
              <div className="space-y-1">
                <InfoRow label="Entry Ref" value={entry.entryReference} copyable />
                <InfoRow label="Acct Svcr Ref" value={entry.accountServicerRef} copyable />
                {details && (
                  <>
                    <InfoRow label="Message ID" value={details.messageId} copyable />
                    <InfoRow label="End-to-End ID" value={details.endToEndId} copyable />
                    <InfoRow label="Transaction ID" value={details.transactionId} copyable />
                    <InfoRow label="Instruction ID" value={details.instructionId} copyable />
                    <InfoRow label="Payment Info ID" value={details.paymentInfoId} copyable />
                  </>
                )}
              </div>
            </div>

            {/* Bank Transaction Code */}
            {(entry.bankTransactionCode || details?.bankTransactionCode) && (
              <div className="bg-white dark:bg-primary-900 rounded-lg border border-neutral-200 dark:border-primary-800 p-3">
                <h5 className="text-xs font-semibold text-neutral-700 dark:text-neutral-200 mb-2 flex items-center gap-1.5">
                  <CreditCard className="w-3.5 h-3.5" />
                  Bank Transaction Code
                </h5>
                <div className="space-y-1">
                  {details?.bankTransactionCode ? (
                    <>
                      <InfoRow label="Domain" value={details.bankTransactionCode.domain} />
                      <InfoRow label="Family" value={details.bankTransactionCode.family} />
                      <InfoRow label="Sub-Family" value={details.bankTransactionCode.subFamily} />
                      <InfoRow label="Proprietary" value={details.bankTransactionCode.proprietaryCode} />
                    </>
                  ) : (
                    <InfoRow label="Code" value={entry.bankTransactionCode} />
                  )}
                </div>
              </div>
            )}

            {/* Related Parties - Debtor */}
            {details?.debtor && (
              <div className="bg-white dark:bg-primary-900 rounded-lg border border-neutral-200 dark:border-primary-800 p-3">
                <h5 className="text-xs font-semibold text-neutral-700 dark:text-neutral-200 mb-2 flex items-center gap-1.5">
                  <User className="w-3.5 h-3.5" />
                  Debtor
                </h5>
                <div className="space-y-1">
                  <InfoRow label="Name" value={details.debtor.name} />
                  <InfoRow label="Account" value={details.debtor.accountIban || details.debtor.accountOther} copyable />
                  <InfoRow label="Agent BIC" value={details.debtor.agentBic} copyable />
                  <InfoRow label="Agent Name" value={details.debtor.agentName} />
                </div>
              </div>
            )}

            {/* Related Parties - Creditor */}
            {details?.creditor && (
              <div className="bg-white dark:bg-primary-900 rounded-lg border border-neutral-200 dark:border-primary-800 p-3">
                <h5 className="text-xs font-semibold text-neutral-700 dark:text-neutral-200 mb-2 flex items-center gap-1.5">
                  <User className="w-3.5 h-3.5" />
                  Creditor
                </h5>
                <div className="space-y-1">
                  <InfoRow label="Name" value={details.creditor.name} />
                  <InfoRow label="Account" value={details.creditor.accountIban || details.creditor.accountOther} copyable />
                  <InfoRow label="Agent BIC" value={details.creditor.agentBic} copyable />
                  <InfoRow label="Agent Name" value={details.creditor.agentName} />
                </div>
              </div>
            )}

            {/* Remittance Information */}
            {details?.remittanceInfo && (
              <div className="bg-white dark:bg-primary-900 rounded-lg border border-neutral-200 dark:border-primary-800 p-3">
                <h5 className="text-xs font-semibold text-neutral-700 dark:text-neutral-200 mb-2 flex items-center gap-1.5">
                  <FileText className="w-3.5 h-3.5" />
                  Remittance Info
                </h5>
                <div className="space-y-1">
                  {details.remittanceInfo.unstructured?.map((info, idx) => (
                    <p key={idx} className="text-xs text-neutral-600 dark:text-neutral-300">{info}</p>
                  ))}
                  {details.remittanceInfo.structured && (
                    <>
                      <InfoRow label="Invoice No" value={details.remittanceInfo.structured.invoiceNumber} copyable />
                      <InfoRow label="Invoice Date" value={details.remittanceInfo.structured.invoiceDate} />
                      <InfoRow label="Creditor Ref" value={details.remittanceInfo.structured.creditorReference} copyable />
                    </>
                  )}
                </div>
              </div>
            )}

            {/* Additional Info / Return Info */}
            {(details?.additionalInfo || details?.returnInfo || entry.additionalEntryInfo) && (
              <div className="bg-white dark:bg-primary-900 rounded-lg border border-neutral-200 dark:border-primary-800 p-3">
                <h5 className="text-xs font-semibold text-neutral-700 dark:text-neutral-200 mb-2 flex items-center gap-1.5">
                  <Info className="w-3.5 h-3.5" />
                  Additional Info
                </h5>
                <div className="space-y-1">
                  {entry.additionalEntryInfo && (
                    <p className="text-xs text-neutral-600 dark:text-neutral-300">{entry.additionalEntryInfo}</p>
                  )}
                  {details?.additionalInfo && (
                    <p className="text-xs text-neutral-600 dark:text-neutral-300">{details.additionalInfo}</p>
                  )}
                  {details?.returnInfo && (
                    <>
                      <InfoRow label="Return Reason" value={details.returnInfo.reason} />
                      <InfoRow label="Original Ref" value={details.returnInfo.originalReference} copyable />
                    </>
                  )}
                </div>
              </div>
            )}

            {/* Charges */}
            {details?.charges && details.charges.length > 0 && (
              <div className="bg-white dark:bg-primary-900 rounded-lg border border-neutral-200 dark:border-primary-800 p-3">
                <h5 className="text-xs font-semibold text-neutral-700 dark:text-neutral-200 mb-2 flex items-center gap-1.5">
                  <Banknote className="w-3.5 h-3.5" />
                  Charges
                </h5>
                <div className="space-y-1">
                  {details.charges.map((charge, idx) => (
                    <div key={idx} className="flex justify-between text-xs">
                      <span className="text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">{charge.bearer || `Charge ${idx + 1}`}</span>
                      <span className="font-medium text-error-600 dark:text-error-300">
                        -{formatCurrency(charge.amount, charge.currency)}
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* Exchange Rate */}
            {details?.exchangeRate && (
              <div className="bg-white dark:bg-primary-900 rounded-lg border border-neutral-200 dark:border-primary-800 p-3">
                <h5 className="text-xs font-semibold text-neutral-700 dark:text-neutral-200 mb-2 flex items-center gap-1.5">
                  <TrendingUp className="w-3.5 h-3.5" />
                  Exchange Rate
                </h5>
                <div className="space-y-1">
                  <InfoRow
                    label="Original Amount"
                    value={details.exchangeRate.originalAmount && details.exchangeRate.originalCurrency ?
                      formatCurrency(details.exchangeRate.originalAmount, details.exchangeRate.originalCurrency) : undefined}
                  />
                  <InfoRow label="Rate" value={details.exchangeRate.rate?.toFixed(6)} />
                  <InfoRow label="Contract ID" value={details.exchangeRate.contractId} copyable />
                </div>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
};

// ============================================================================
// MOBILE ENTRY CARD
// ============================================================================

interface EntryMobileCardProps {
  entry: ExtendedStatementEntry;
  currency: string;
  index: number;
}

const EntryMobileCard: React.FC<EntryMobileCardProps> = ({ entry, currency, index }) => {
  const [expanded, setExpanded] = useState(false);
  const isCredit = entry.creditDebit === 'CRDT';

  return (
    <div
      className={cn(
        'border-b border-neutral-100 dark:border-primary-800/60 last:border-0 animate-fade-in',
        expanded && 'bg-neutral-50 dark:bg-primary-950'
      )}
      style={{ animationDelay: `${index * 0.02}s` }}
    >
      {/* Main Card */}
      <div
        className="p-4 cursor-pointer"
        onClick={() => setExpanded(!expanded)}
      >
        <div className="flex items-start gap-3">
          <div className={cn(
            'w-10 h-10 rounded-xl flex items-center justify-center shrink-0 relative',
            isCredit ? 'bg-success-50 dark:bg-success-500/10' : 'bg-error-50 dark:bg-error-500/10'
          )}>
            {isCredit ? (
              <ArrowDownLeft className="w-5 h-5 text-success-600 dark:text-success-300" />
            ) : (
              <ArrowUpRight className="w-5 h-5 text-error-600 dark:text-error-300" />
            )}
            {entry.reversalIndicator && (
              <RotateCcw className="w-3 h-3 text-warning-500 absolute -bottom-0.5 -right-0.5" />
            )}
          </div>

          <div className="flex-1 min-w-0">
            <div className="flex items-start justify-between gap-2">
              <div className="min-w-0">
                <p className="text-sm font-medium text-primary-900 dark:text-neutral-50 truncate">
                  {entry.description || entry.reference}
                </p>
                <p className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500 mt-0.5">
                  {formatDate(entry.bookingDate)}
                </p>
              </div>
              <div className="text-right shrink-0">
                <p className={cn(
                  'text-sm font-bold tabular-nums',
                  isCredit ? 'text-success-600 dark:text-success-300' : 'text-error-600 dark:text-error-300'
                )}>
                  {isCredit ? '+' : '-'}{formatCurrency(entry.amount, currency)}
                </p>
                {entry.balanceAfter !== undefined && (
                  <p className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500 mt-0.5">
                    Bal: {formatCurrency(entry.balanceAfter, currency)}
                  </p>
                )}
              </div>
            </div>

            <div className="flex items-center gap-2 mt-2">
              <StatusBadge status={entry.status} />
              {entry.reversalIndicator && (
                <Badge variant="warning" size="xs">Reversal</Badge>
              )}
              <span className="text-xs text-neutral-400 dark:text-neutral-500 font-mono truncate">
                {entry.entryReference || entry.reference}
              </span>
              <ChevronDown className={cn(
                'w-4 h-4 text-neutral-400 dark:text-neutral-500 ml-auto transition-transform',
                expanded && 'rotate-180'
              )} />
            </div>
          </div>
        </div>
      </div>

      {/* Expanded Details (Mobile) */}
      {expanded && (
        <div className="px-4 pb-4 space-y-3 animate-fade-in">
          {entry.entryDetails && (
            <>
              {/* Transaction References */}
              <div className="bg-white dark:bg-primary-900 rounded-lg border border-neutral-200 dark:border-primary-800 p-3">
                <h5 className="text-xs font-semibold text-neutral-700 dark:text-neutral-200 mb-2">References</h5>
                <div className="space-y-1">
                  <InfoRow label="Entry Ref" value={entry.entryReference} copyable />
                  <InfoRow label="End-to-End ID" value={entry.entryDetails.endToEndId} copyable />
                  <InfoRow label="Transaction ID" value={entry.entryDetails.transactionId} copyable />
                </div>
              </div>

              {/* Counterparty */}
              {(entry.entryDetails.debtor || entry.entryDetails.creditor) && (
                <div className="bg-white dark:bg-primary-900 rounded-lg border border-neutral-200 dark:border-primary-800 p-3">
                  <h5 className="text-xs font-semibold text-neutral-700 dark:text-neutral-200 mb-2">
                    {isCredit ? 'Debtor' : 'Creditor'}
                  </h5>
                  <div className="space-y-1">
                    {isCredit ? (
                      <>
                        <InfoRow label="Name" value={entry.entryDetails.debtor?.name} />
                        <InfoRow label="Account" value={entry.entryDetails.debtor?.accountIban} copyable />
                      </>
                    ) : (
                      <>
                        <InfoRow label="Name" value={entry.entryDetails.creditor?.name} />
                        <InfoRow label="Account" value={entry.entryDetails.creditor?.accountIban} copyable />
                      </>
                    )}
                  </div>
                </div>
              )}

              {/* Remittance Info */}
              {entry.entryDetails.remittanceInfo?.unstructured && (
                <div className="bg-white dark:bg-primary-900 rounded-lg border border-neutral-200 dark:border-primary-800 p-3">
                  <h5 className="text-xs font-semibold text-neutral-700 dark:text-neutral-200 mb-2">Remittance Info</h5>
                  {entry.entryDetails.remittanceInfo.unstructured.map((info, idx) => (
                    <p key={idx} className="text-xs text-neutral-600 dark:text-neutral-300">{info}</p>
                  ))}
                </div>
              )}
            </>
          )}
        </div>
      )}
    </div>
  );
};

// ============================================================================
// MAIN COMPONENT
// ============================================================================

export const StatementPreviewModal: React.FC<StatementPreviewModalProps> = ({
  isOpen,
  onClose,
  accountId,
  vaNumber,
  accountName,
  currency,
  fromDate,
  toDate,
  includeChildAccounts = false,
  initialStatement,
  statementType = 'camt053',
}) => {
  // State
  const [statement, setStatement] = useState<ExtendedISO20022Statement | null>(
    initialStatement || null
  );
  const [loading, setLoading] = useState(!initialStatement);
  const [exporting, setExporting] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [filterType, setFilterType] = useState<'ALL' | 'CRDT' | 'DBIT'>('ALL');
  const [filterStatus, setFilterStatus] = useState<'ALL' | StatementEntryStatus>('ALL');
  const [currentPage, setCurrentPage] = useState(0);
  const pageSize = 20;

  // Load statement data
  const loadStatement = useCallback(async () => {
    setLoading(true);
    try {
      const request: StatementRequest = {
        accountId,
        fromDate,
        toDate,
        includeChildAccounts,
        page: currentPage,
        pageSize,
      };
      const response = await statementsApi.getISO20022Statement(request);
      if (response.success && response.data) {
        setStatement(response.data as ExtendedISO20022Statement);
      } else {
        toast.error(response.message || 'Failed to load statement');
      }
    } catch (error) {
      console.error('Failed to load statement:', error);
      toast.error('Failed to load statement');
    } finally {
      setLoading(false);
    }
  }, [accountId, fromDate, toDate, includeChildAccounts, currentPage]);

  useEffect(() => {
    if (isOpen && !initialStatement) {
      loadStatement();
    }
  }, [isOpen, loadStatement, initialStatement]);

  // Filter entries
  const filteredEntries = useMemo(() => {
    if (!statement?.entries) return [];

    let entries = statement.entries as ExtendedStatementEntry[];

    // Filter by type
    if (filterType !== 'ALL') {
      entries = entries.filter((e) => e.creditDebit === filterType);
    }

    // Filter by status
    if (filterStatus !== 'ALL') {
      entries = entries.filter((e) => e.status === filterStatus);
    }

    // Search filter
    if (searchQuery.trim()) {
      const query = searchQuery.toLowerCase();
      entries = entries.filter(
        (e) =>
          e.reference.toLowerCase().includes(query) ||
          e.entryReference?.toLowerCase().includes(query) ||
          e.description?.toLowerCase().includes(query) ||
          e.counterparty?.toLowerCase().includes(query) ||
          e.endToEndId?.toLowerCase().includes(query) ||
          e.accountServicerRef?.toLowerCase().includes(query)
      );
    }

    return entries;
  }, [statement?.entries, filterType, filterStatus, searchQuery]);

  // Pagination
  const totalPages = Math.ceil((statement?.totalEntries || 0) / pageSize);

  // Export statement
  const handleExport = async (format: ISO20022StatementFormat) => {
    setExporting(true);
    try {
      const response = await statementsApi.generateISO20022Statement(
        accountId,
        format,
        fromDate,
        toDate,
        includeChildAccounts
      );

      if (response.success && response.data) {
        if (response.data.status === 'COMPLETED' && response.data.statementReference) {
          await statementsApi.downloadStatement(response.data.statementReference);
          toast.success(`${format} statement downloaded`);
        } else if (response.data.status === 'PROCESSING') {
          toast.success('Statement is being generated. Check history.');
        }
      }
    } catch (error) {
      console.error('Export failed:', error);
      toast.error('Failed to export statement');
    } finally {
      setExporting(false);
    }
  };

  // Summary from statement
  const summary = statement?.summary || null;
  const account = statement?.account || null;
  const balances = statement?.balances || [];
  const groupHeader = statement?.groupHeader;
  const notificationInfo = statement?.notificationInfo;

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title={
        <div className="flex items-center gap-3">
          <div className={cn(
            'w-10 h-10 rounded-xl flex items-center justify-center',
            statementType === 'camt054' ? 'bg-warning-100 dark:bg-warning-500/20' : 'bg-primary-100 dark:bg-primary-700'
          )}>
            {statementType === 'camt054' ? (
              <Bell className="w-5 h-5 text-warning-600 dark:text-warning-300" />
            ) : (
              <FileText className="w-5 h-5 text-primary-600 dark:text-primary-200" />
            )}
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h2 className="section-title">
                {statementType === 'camt054' ? 'camt.054 Notification' : 'camt.053 Statement'}
              </h2>
              <Badge variant="info" size="sm">ISO20022</Badge>
            </div>
            <p className="text-sm text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">
              {vaNumber} - {formatDate(fromDate)} to {formatDate(toDate)}
            </p>
          </div>
        </div>
      }
      size="full"
    >
      <div className="space-y-6">
        {/* Loading State */}
        {loading && (
          <div className="space-y-4">
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <Skeleton className="h-32 rounded-xl" />
              <Skeleton className="h-32 rounded-xl" />
            </div>
            <Skeleton className="h-24 rounded-xl" />
            <Skeleton className="h-10 rounded-xl" />
            <div className="space-y-2">
              {[...Array(5)].map((_, i) => (
                <Skeleton key={i} className="h-16 rounded-lg" />
              ))}
            </div>
          </div>
        )}

        {/* Statement Content */}
        {!loading && statement && (
          <>
            {/* Group Header (camt.053/054) */}
            {groupHeader && (
              <SectionCard
                title="Group Header"
                icon={<FileCode className="w-4 h-4 text-primary-600 dark:text-primary-200" />}
                collapsible
                defaultOpen={false}
              >
                <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                  <InfoRow label="Message ID" value={groupHeader.messageId} copyable icon={<Hash className="w-3 h-3" />} />
                  <InfoRow label="Creation Date/Time" value={formatDateTime(groupHeader.creationDateTime)} icon={<Clock className="w-3 h-3" />} />
                  {groupHeader.messagePagination && (
                    <>
                      <InfoRow label="Page Number" value={groupHeader.messagePagination.pageNumber} />
                      <InfoRow label="Last Page" value={groupHeader.messagePagination.lastPageIndicator ? 'Yes' : 'No'} />
                    </>
                  )}
                </div>
              </SectionCard>
            )}

            {/* Notification Info (camt.054 specific) */}
            {statementType === 'camt054' && notificationInfo && (
              <SectionCard
                title="Notification Info"
                icon={<Bell className="w-4 h-4 text-warning-600 dark:text-warning-300" />}
                badge={
                  <Badge
                    variant={notificationInfo.notificationType === 'CREDIT' ? 'success' : 'error'}
                    size="sm"
                  >
                    {notificationInfo.notificationType}
                  </Badge>
                }
              >
                <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                  <InfoRow label="Notification ID" value={notificationInfo.notificationId} copyable />
                  <InfoRow label="Type" value={notificationInfo.notificationType} />
                  <InfoRow label="Priority" value={notificationInfo.priority} />
                  {notificationInfo.realTimeTimestamp && (
                    <InfoRow label="Real-time Timestamp" value={formatDateTime(notificationInfo.realTimeTimestamp)} />
                  )}
                </div>
              </SectionCard>
            )}

            {/* Statement Header */}
            <SectionCard
              title="Statement Header"
              icon={<FileText className="w-4 h-4 text-primary-600 dark:text-primary-200" />}
            >
              <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                <InfoRow label="Statement ID" value={statement.statementId} copyable icon={<Hash className="w-3 h-3" />} />
                <InfoRow label="Electronic Seq No" value={statement.electronicSeqNumber} />
                <InfoRow label="Legal Seq No" value={statement.legalSeqNumber} />
                <InfoRow label="Creation Date/Time" value={formatDateTime(statement.creationDateTime)} icon={<Clock className="w-3 h-3" />} />
                <InfoRow label="From Date" value={formatDate(statement.fromDate)} icon={<Calendar className="w-3 h-3" />} />
                <InfoRow label="To Date" value={formatDate(statement.toDate)} icon={<Calendar className="w-3 h-3" />} />
                {statement.copyDuplicateIndicator && (
                  <InfoRow label="Copy/Duplicate" value={statement.copyDuplicateIndicator} />
                )}
              </div>
            </SectionCard>

            {/* Account Information */}
            {account && (
              <SectionCard
                title="Account Information"
                icon={<Building2 className="w-4 h-4 text-primary-600 dark:text-primary-200" />}
              >
                <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                  <InfoRow label="Account ID" value={account.id} copyable icon={<Hash className="w-3 h-3" />} />
                  <InfoRow label="VA Number" value={account.vaNumber} copyable />
                  <InfoRow label="VIBAN" value={account.viban} copyable />
                  <InfoRow label="Account Name" value={account.accountName} />
                  <InfoRow label="Currency" value={account.currency} icon={<Banknote className="w-3 h-3" />} />
                  <InfoRow label="Owner Name" value={account.ownerName} icon={<User className="w-3 h-3" />} />
                  <InfoRow label="Servicer BIC" value={account.servicerBic} copyable />
                  {account.isAggregationAccount && (
                    <InfoRow label="Account Type" value="Aggregation (Virtual)" />
                  )}
                  {account.childAccountCount !== undefined && account.childAccountCount > 0 && (
                    <InfoRow label="Child Accounts" value={account.childAccountCount} />
                  )}
                </div>
              </SectionCard>
            )}

            {/* Balances Section */}
            {balances.length > 0 && (
              <SectionCard
                title="Balances"
                icon={<Banknote className="w-4 h-4 text-primary-600 dark:text-primary-200" />}
              >
                <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-5 gap-4">
                  {balances.map((balance, idx) => (
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
              </SectionCard>
            )}

            {/* Transaction Summary */}
            {summary && (
              <TransactionSummaryCard summary={summary} totalEntries={statement.totalEntries} />
            )}

            {/* Toolbar */}
            <div className="flex flex-col sm:flex-row gap-3">
              {/* Search */}
              <div className="flex-1">
                <Input
                  placeholder="Search by reference, description, counterparty, E2E ID..."
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  leftIcon={<Search className="w-4 h-4" />}
                />
              </div>

              {/* Filters */}
              <div className="flex items-center gap-2 flex-wrap">
                {/* Type Filter */}
                <div className="flex bg-neutral-100 dark:bg-primary-800 rounded-lg p-1">
                  {(['ALL', 'CRDT', 'DBIT'] as const).map((type) => (
                    <button
                      key={type}
                      onClick={() => setFilterType(type)}
                      className={cn(
                        'px-3 py-1.5 text-sm font-medium rounded-md transition-colors',
                        filterType === type
                          ? 'bg-white dark:bg-primary-900 shadow-sm text-primary-900 dark:text-neutral-50'
                          : 'text-neutral-600 dark:text-neutral-300 hover:text-primary-900 dark:text-neutral-50 dark:hover:text-neutral-50'
                      )}
                    >
                      {type === 'ALL' ? 'All' : type === 'CRDT' ? 'Credits' : 'Debits'}
                    </button>
                  ))}
                </div>

                {/* Status Filter */}
                <div className="flex bg-neutral-100 dark:bg-primary-800 rounded-lg p-1">
                  {(['ALL', 'BOOK', 'PDNG', 'INFO'] as const).map((status) => (
                    <button
                      key={status}
                      onClick={() => setFilterStatus(status)}
                      className={cn(
                        'px-2 py-1.5 text-xs font-medium rounded-md transition-colors',
                        filterStatus === status
                          ? 'bg-white dark:bg-primary-900 shadow-sm text-primary-900 dark:text-neutral-50'
                          : 'text-neutral-600 dark:text-neutral-300 hover:text-primary-900 dark:text-neutral-50 dark:hover:text-neutral-50'
                      )}
                    >
                      {status}
                    </button>
                  ))}
                </div>

                <Button
                  variant="outline"
                  size="sm"
                  onClick={loadStatement}
                  leftIcon={<RefreshCw className={cn('w-4 h-4', loading && 'animate-spin')} />}
                >
                  Refresh
                </Button>
              </div>
            </div>

            {/* Entry List Header */}
            <div className="flex items-center justify-between">
              <h3 className="text-sm font-semibold text-primary-900 dark:text-neutral-50 flex items-center gap-2">
                Entry List
                <Badge variant="neutral" size="sm">{filteredEntries.length} entries</Badge>
              </h3>
              <p className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">
                Click on any entry to expand details
              </p>
            </div>

            {/* Entries Table (Desktop) */}
            <div className="hidden md:block border rounded-xl overflow-hidden">
              <div className="max-h-[500px] overflow-y-auto">
                {/* Table Header */}
                <div className="bg-neutral-50 dark:bg-primary-950 px-4 py-3 border-b border-neutral-200 dark:border-primary-800 sticky top-0 z-10">
                  <div className="flex items-center gap-4 text-xs font-medium text-neutral-500 dark:text-neutral-400 dark:text-neutral-500 uppercase">
                    <div className="w-8"></div>
                    <div className="w-9"></div>
                    <div className="flex-1">Entry Reference / Description</div>
                    <div className="w-24 text-right hidden sm:block">Date</div>
                    <div className="w-20 hidden md:block">Status</div>
                    <div className="w-28 text-right">Amount</div>
                  </div>
                </div>

                {/* Entries */}
                {filteredEntries.map((entry, idx) => (
                  <ExpandableEntryRow
                    key={entry.reference + idx}
                    entry={entry}
                    currency={currency}
                    index={idx}
                  />
                ))}

                {filteredEntries.length === 0 && (
                  <div className="text-center py-12 text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">
                    <FileText className="w-12 h-12 mx-auto mb-4 text-neutral-300 dark:text-neutral-600" />
                    <p className="font-medium">No entries found</p>
                    <p className="text-sm">Try adjusting your search or filters</p>
                  </div>
                )}
              </div>
            </div>

            {/* Entries Cards (Mobile) */}
            <div className="md:hidden border rounded-xl overflow-hidden">
              <div className="max-h-[500px] overflow-y-auto">
                {filteredEntries.map((entry, idx) => (
                  <EntryMobileCard
                    key={entry.reference + idx}
                    entry={entry}
                    currency={currency}
                    index={idx}
                  />
                ))}

                {filteredEntries.length === 0 && (
                  <div className="text-center py-12 text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">
                    <FileText className="w-12 h-12 mx-auto mb-4 text-neutral-300 dark:text-neutral-600" />
                    <p className="font-medium">No entries found</p>
                  </div>
                )}
              </div>
            </div>

            {/* Pagination */}
            {totalPages > 1 && (
              <div className="flex items-center justify-between">
                <p className="text-sm text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">
                  Showing {currentPage * pageSize + 1} to{' '}
                  {Math.min((currentPage + 1) * pageSize, statement.totalEntries)} of {statement.totalEntries} entries
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
                  <span className="px-3 text-sm text-neutral-600 dark:text-neutral-300">
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
            )}

            {/* Export Options */}
            <div className="pt-4 border-t border-neutral-200 dark:border-primary-800">
              <p className="field-label mb-3">Export Statement</p>
              <div className="flex flex-wrap gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => handleExport('PDF')}
                  disabled={exporting}
                  leftIcon={exporting ? <Loader2 className="w-4 h-4 animate-spin" /> : <FileText className="w-4 h-4" />}
                >
                  PDF
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => handleExport('JSON')}
                  disabled={exporting}
                  leftIcon={<FileJson className="w-4 h-4" />}
                >
                  JSON
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => handleExport('XML')}
                  disabled={exporting}
                  leftIcon={<FileCode className="w-4 h-4" />}
                >
                  XML
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => handleExport('CAMT053')}
                  disabled={exporting}
                  leftIcon={<FileCode className="w-4 h-4" />}
                >
                  CAMT.053
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => handleExport('MT940')}
                  disabled={exporting}
                  leftIcon={<FileCode className="w-4 h-4" />}
                >
                  MT940
                </Button>
              </div>
            </div>
          </>
        )}
      </div>
    </Modal>
  );
};

export default StatementPreviewModal;
