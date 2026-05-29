// ============================================================================
// STATEMENT DOWNLOAD PANEL - ISO20022 Statement Generator
// ============================================================================
// Features:
// - Date range picker (from/to)
// - Format selector (JSON, XML, PDF, CAMT.053, MT940)
// - Include Child Accounts checkbox for aggregation VAs
// - Download/Generate button with loading state
// - Preview summary before download
// ============================================================================

import React, { useState, useCallback, useEffect } from 'react';
import {
  Calendar,
  Download,
  FileText,
  FileJson,
  FileCode,
  Loader2,
  ChevronDown,
  GitBranch,
  Eye,
  Info,
} from 'lucide-react';
import { Card, Button, Input, Badge, StatusIconBadge } from '../ui';
import { cn, formatCurrency } from '../../utils';
import { statementsApi } from '../../services/api';
import type { ISO20022StatementFormat, StatementSummary } from '../../types';
import toast from 'react-hot-toast';

// ============================================================================
// TYPES
// ============================================================================

interface StatementDownloadPanelProps {
  /** Virtual account ID */
  accountId: string;
  /** VA number for display */
  vaNumber: string;
  /** Account name */
  accountName: string;
  /** Currency code */
  currencyCode: string;
  /** Whether this is an aggregation/parent account */
  isAggregationAccount?: boolean;
  /** Number of child accounts (if aggregation) */
  childAccountCount?: number;
  /** Callback when statement is generated */
  onStatementGenerated?: (reference: string, format: ISO20022StatementFormat) => void;
  /** Callback to open preview modal */
  onPreview?: (summary: StatementSummary, fromDate: string, toDate: string) => void;
  /** Compact mode for embedding in detail panels */
  compact?: boolean;
  /** Custom class name */
  className?: string;
}

interface FormatOption {
  value: ISO20022StatementFormat;
  label: string;
  description: string;
  icon: React.ElementType;
  fileExtension: string;
}

// ============================================================================
// FORMAT OPTIONS
// ============================================================================

const FORMAT_OPTIONS: FormatOption[] = [
  {
    value: 'PDF',
    label: 'PDF',
    description: 'Human-readable PDF document',
    icon: FileText,
    fileExtension: '.pdf',
  },
  {
    value: 'JSON',
    label: 'JSON',
    description: 'JSON format for API integration',
    icon: FileJson,
    fileExtension: '.json',
  },
  {
    value: 'XML',
    label: 'XML',
    description: 'XML format for system integration',
    icon: FileCode,
    fileExtension: '.xml',
  },
  {
    value: 'CAMT053',
    label: 'CAMT.053',
    description: 'ISO20022 Bank to Customer Statement',
    icon: FileCode,
    fileExtension: '.xml',
  },
  {
    value: 'MT940',
    label: 'MT940',
    description: 'SWIFT legacy format',
    icon: FileCode,
    fileExtension: '.sta',
  },
];

// ============================================================================
// COMPONENT
// ============================================================================

export const StatementDownloadPanel: React.FC<StatementDownloadPanelProps> = ({
  accountId,
  vaNumber,
  accountName,
  currencyCode,
  isAggregationAccount = false,
  childAccountCount = 0,
  onStatementGenerated,
  onPreview,
  compact = false,
  className,
}) => {
  // Date state - default to last 30 days
  const [fromDate, setFromDate] = useState(() => {
    const date = new Date();
    date.setDate(date.getDate() - 30);
    return date.toISOString().split('T')[0];
  });
  const [toDate, setToDate] = useState(() => new Date().toISOString().split('T')[0]);

  // Format and options state
  const [selectedFormat, setSelectedFormat] = useState<ISO20022StatementFormat>('PDF');
  const [includeChildAccounts, setIncludeChildAccounts] = useState(false);
  const [showFormatDropdown, setShowFormatDropdown] = useState(false);

  // Loading and preview state
  const [loading, setLoading] = useState(false);
  const [previewing, setPreviewing] = useState(false);
  const [previewSummary, setPreviewSummary] = useState<StatementSummary | null>(null);

  // Get selected format info
  const selectedFormatInfo = FORMAT_OPTIONS.find(f => f.value === selectedFormat) || FORMAT_OPTIONS[0];
  const FormatIcon = selectedFormatInfo.icon;

  // Reset preview when dates or options change
  useEffect(() => {
    setPreviewSummary(null);
  }, [fromDate, toDate, includeChildAccounts]);

  // Load preview summary
  const loadPreview = useCallback(async () => {
    setPreviewing(true);
    try {
      const response = await statementsApi.previewStatement(
        accountId,
        fromDate,
        toDate,
        includeChildAccounts
      );
      if (response.success && response.data) {
        setPreviewSummary(response.data.summary);
        if (onPreview) {
          onPreview(response.data.summary, fromDate, toDate);
        }
      }
    } catch (error) {
      console.error('Failed to load preview:', error);
      toast.error('Failed to load statement preview');
    } finally {
      setPreviewing(false);
    }
  }, [accountId, fromDate, toDate, includeChildAccounts, onPreview]);

  // Generate and download statement
  const handleGenerate = useCallback(async () => {
    setLoading(true);
    try {
      const response = await statementsApi.generateISO20022Statement(
        accountId,
        selectedFormat,
        fromDate,
        toDate,
        includeChildAccounts
      );

      if (response.success && response.data) {
        const { statementReference, status } = response.data;

        if (status === 'COMPLETED' && statementReference) {
          // Download the statement
          await statementsApi.downloadStatement(statementReference);
          toast.success(`${selectedFormat} statement downloaded`);

          if (onStatementGenerated) {
            onStatementGenerated(statementReference, selectedFormat);
          }
        } else if (status === 'PROCESSING') {
          toast.success('Statement is being generated. Check history for download.');
        } else {
          toast.error(response.data.message || 'Failed to generate statement');
        }
      }
    } catch (error) {
      console.error('Failed to generate statement:', error);
      toast.error('Failed to generate statement');
    } finally {
      setLoading(false);
    }
  }, [accountId, selectedFormat, fromDate, toDate, includeChildAccounts, onStatementGenerated]);

  // Validate date range
  const isValidDateRange = new Date(fromDate) <= new Date(toDate);

  return (
    <Card
      className={cn(
        'overflow-hidden',
        compact ? 'p-4' : '',
        className
      )}
      padding={compact ? 'none' : 'md'}
    >
      {/* Header */}
      {!compact && (
        <div className="flex items-center gap-3 mb-5">
          <StatusIconBadge tone="primary" icon={FileText} className="dark:bg-primary-700" />
          <div>
            <h3 className="font-semibold text-primary-900 dark:text-neutral-50">Download Statement</h3>
            <p className="text-xs text-neutral-500 dark:text-neutral-400">
              {vaNumber} - {accountName}
            </p>
          </div>
          {isAggregationAccount && (
            <Badge variant="info" size="sm" className="ml-auto">
              <GitBranch className="w-3 h-3 mr-1" />
              {childAccountCount} child accounts
            </Badge>
          )}
        </div>
      )}

      {/* Form */}
      <div className={cn(
        'grid gap-4',
        compact ? 'grid-cols-1' : 'grid-cols-1 sm:grid-cols-2 lg:grid-cols-4'
      )}>
        {/* From Date */}
        <div>
          <label className="field-label block mb-1.5">
            From Date
          </label>
          <Input
            type="date"
            value={fromDate}
            onChange={(e) => setFromDate(e.target.value)}
            leftIcon={<Calendar className="w-4 h-4" />}
            error={!isValidDateRange ? 'Invalid date range' : undefined}
          />
        </div>

        {/* To Date */}
        <div>
          <label className="field-label block mb-1.5">
            To Date
          </label>
          <Input
            type="date"
            value={toDate}
            onChange={(e) => setToDate(e.target.value)}
            leftIcon={<Calendar className="w-4 h-4" />}
            max={new Date().toISOString().split('T')[0]}
          />
        </div>

        {/* Format Selector */}
        <div>
          <label className="field-label block mb-1.5">
            Format
          </label>
          <div className="relative">
            <button
              type="button"
              onClick={() => setShowFormatDropdown(!showFormatDropdown)}
              className={cn(
                'w-full h-11 px-4 rounded-xl border bg-white dark:bg-primary-900',
                'flex items-center justify-between',
                'text-primary-900 text-base dark:text-neutral-50',
                'border-neutral-300 hover:border-neutral-400 dark:border-primary-700',
                'focus:outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-500/20',
                'transition-all duration-200'
              )}
            >
              <div className="flex items-center gap-2">
                <FormatIcon className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
                <span>{selectedFormatInfo.label}</span>
              </div>
              <ChevronDown className={cn(
                'w-4 h-4 text-neutral-500 transition-transform dark:text-neutral-400',
                showFormatDropdown && 'rotate-180'
              )} />
            </button>

            {showFormatDropdown && (
              <>
                <div
                  className="fixed inset-0 z-10"
                  onClick={() => setShowFormatDropdown(false)}
                />
                <div className="absolute z-20 top-full left-0 right-0 mt-1 bg-white rounded-xl shadow-lg border border-neutral-200 py-1 animate-fade-in dark:bg-primary-900 dark:border-primary-800">
                  {FORMAT_OPTIONS.map((format) => {
                    const Icon = format.icon;
                    return (
                      <button
                        key={format.value}
                        type="button"
                        onClick={() => {
                          setSelectedFormat(format.value);
                          setShowFormatDropdown(false);
                        }}
                        className={cn(
                          'w-full px-4 py-2.5 flex items-start gap-3 text-left',
                          'hover:bg-neutral-50 transition-colors dark:hover:bg-primary-800/50',
                          selectedFormat === format.value && 'bg-primary-50 dark:bg-primary-800/40'
                        )}
                      >
                        <Icon className={cn(
                          'w-4 h-4 mt-0.5',
                          selectedFormat === format.value ? 'text-primary-600 dark:text-primary-200' : 'text-neutral-500 dark:text-neutral-400'
                        )} />
                        <div>
                          <p className={cn(
                            'text-sm font-medium',
                            selectedFormat === format.value ? 'text-primary-900 dark:text-neutral-50' : 'text-neutral-900 dark:text-neutral-50'
                          )}>
                            {format.label}
                          </p>
                          <p className="text-xs text-neutral-500 dark:text-neutral-400">
                            {format.description}
                          </p>
                        </div>
                      </button>
                    );
                  })}
                </div>
              </>
            )}
          </div>
        </div>

        {/* Actions */}
        <div className="flex items-end gap-2">
          <Button
            onClick={loadPreview}
            variant="outline"
            disabled={!isValidDateRange || previewing}
            leftIcon={previewing ? <Loader2 className="w-4 h-4 animate-spin" /> : <Eye className="w-4 h-4" />}
            className="flex-1"
          >
            Preview
          </Button>
          <Button
            onClick={handleGenerate}
            disabled={!isValidDateRange || loading}
            leftIcon={loading ? <Loader2 className="w-4 h-4 animate-spin" /> : <Download className="w-4 h-4" />}
            className="flex-1"
          >
            Download
          </Button>
        </div>
      </div>

      {/* Include Child Accounts Option (for aggregation accounts) */}
      {isAggregationAccount && (
        <div className="mt-4 pt-4 border-t border-neutral-100 dark:border-primary-800/60">
          <label className="flex items-center gap-3 cursor-pointer group">
            <div className="relative">
              <input
                type="checkbox"
                checked={includeChildAccounts}
                onChange={(e) => setIncludeChildAccounts(e.target.checked)}
                className="sr-only peer"
              />
              <div className={cn(
                'w-5 h-5 rounded-lg border-2 transition-all',
                'peer-focus:ring-2 peer-focus:ring-primary-500/20',
                includeChildAccounts
                  ? 'bg-primary-600 border-primary-600'
                  : 'bg-white border-neutral-300 group-hover:border-primary-300 dark:bg-primary-900 dark:border-primary-700'
              )}>
                {includeChildAccounts && (
                  <svg className="w-full h-full text-white" viewBox="0 0 16 16" fill="none">
                    <path
                      d="M4 8l3 3 5-6"
                      stroke="currentColor"
                      strokeWidth="2"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    />
                  </svg>
                )}
              </div>
            </div>
            <div>
              <span className="text-sm font-medium text-primary-900 dark:text-neutral-50">
                Include Child Accounts
              </span>
              <p className="text-xs text-neutral-500 dark:text-neutral-400">
                Aggregate transactions from all {childAccountCount} child accounts
              </p>
            </div>
          </label>
        </div>
      )}

      {/* Preview Summary */}
      {previewSummary && (
        <div className="mt-4 pt-4 border-t border-neutral-100 animate-fade-in dark:border-primary-800/60">
          <div className="flex items-center gap-2 mb-3">
            <Info className="w-4 h-4 text-info-600 dark:text-info-300" />
            <span className="text-sm font-medium text-primary-900 dark:text-neutral-50">Statement Preview</span>
          </div>
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
            <div className="bg-neutral-50 rounded-lg p-3 dark:bg-primary-950">
              <p className="text-xs text-neutral-500 mb-1 dark:text-neutral-400">Opening Balance</p>
              <p className="text-sm font-bold text-primary-900 dark:text-neutral-50">
                {formatCurrency(previewSummary.openingBalance, currencyCode)}
              </p>
            </div>
            <div className="bg-success-50 rounded-lg p-3 dark:bg-success-500/10">
              <p className="text-xs text-success-600 mb-1 dark:text-success-300">
                Total Credits ({previewSummary.creditCount})
              </p>
              <p className="text-sm font-bold text-success-700 dark:text-success-300">
                +{formatCurrency(previewSummary.totalCredits, currencyCode)}
              </p>
            </div>
            <div className="bg-error-50 rounded-lg p-3 dark:bg-error-500/10">
              <p className="text-xs text-error-600 mb-1 dark:text-error-300">
                Total Debits ({previewSummary.debitCount})
              </p>
              <p className="text-sm font-bold text-error-700 dark:text-error-300">
                -{formatCurrency(previewSummary.totalDebits, currencyCode)}
              </p>
            </div>
            <div className="bg-primary-50 rounded-lg p-3 dark:bg-primary-800/40">
              <p className="text-xs text-primary-600 mb-1 dark:text-primary-200">Closing Balance</p>
              <p className="text-sm font-bold text-primary-900 dark:text-neutral-50">
                {formatCurrency(previewSummary.closingBalance, currencyCode)}
              </p>
            </div>
          </div>
        </div>
      )}
    </Card>
  );
};

export default StatementDownloadPanel;
