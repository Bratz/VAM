import React, { useState, useEffect } from 'react';
import {
  Shield, CheckCircle2, XCircle, AlertTriangle, Loader2,
  ChevronRight, ChevronDown, CreditCard, Wallet, Layers,
  ArrowRight, DollarSign, TrendingUp, Clock, Eye,
  RefreshCw, Info, AlertCircle,
} from 'lucide-react';
import { Card, Button, Badge, Input , StatusIconBadge } from '../components/ui';
import { CurrencyPicker } from '../components/ui/CurrencyPicker';
import { Modal } from '../components/ui/enhanced';
import { formatCurrency, cn } from '../utils';
import { fundsAvailabilityApi } from '../services/api';
import { Page } from '../components/layout/Page';

// ============================================================================
// TYPES
// ============================================================================

interface FundsLevelCheckResult {
  level: number;
  vaId: string;
  vaNumber: string;
  vaCurrency: string;
  accountCategory?: string;
  requestedAmountInVaCurrency: number;
  balance: number;
  externalLimitAvailable: number;
  internalLimitAvailable: number;
  totalAvailable: number;
  approved: boolean;
  rejectionReason?: string;
  shortfall?: number;
  limitUsageRequired?: number;
}

interface FundsCheckResult {
  vaId: string;
  requestedAmount: number;
  requestedCurrency: string;
  checkedAt: string;
  approved: boolean;
  rejectionLevel: number;
  rejectionReason?: string;
  rejectionVaId?: string;
  rejectionVaNumber?: string;
  levelResults: FundsLevelCheckResult[];
  levelsChecked: number;
}

// ============================================================================
// LEVEL CHECK ROW
// ============================================================================

interface LevelCheckRowProps {
  result: FundsLevelCheckResult;
  isLast: boolean;
  isRejectionLevel: boolean;
}

const LevelCheckRow: React.FC<LevelCheckRowProps> = ({ result, isLast, isRejectionLevel }) => {
  const [expanded, setExpanded] = useState(false);

  const getCategoryIcon = () => {
    switch (result.accountCategory) {
      case 'TRANSACTION':
      case 'COLLECTION':
      case 'DISBURSEMENT':
        return Wallet;
      case 'PHYSICAL_MIRROR':
      case 'SHADOW_ACCOUNT':
        return Layers;
      case 'CURRENCY_MIRROR':
        return TrendingUp;
      case 'ROOT':
        return Shield;
      default:
        return Wallet;
    }
  };

  const CategoryIcon = getCategoryIcon();

  return (
    <div className={cn(
      "relative",
      !isLast && "pb-4"
    )}>
      {/* Connector line */}
      {!isLast && (
        <div className="absolute left-5 top-10 bottom-0 w-0.5 bg-neutral-200 dark:bg-primary-800" />
      )}

      <div 
        className={cn(
          "flex items-start gap-3 p-3 rounded-lg border cursor-pointer transition-all",
          result.approved 
            ? "bg-success-50 border-success-200 hover:bg-success-100 dark:bg-success-500/10 dark:border-success-500/30 dark:hover:bg-success-500/20" 
            : isRejectionLevel
              ? "bg-error-50 border-error-300 hover:bg-error-100 dark:bg-error-500/10 dark:hover:bg-error-500/20"
              : "bg-amber-50 border-amber-200 hover:bg-amber-100 dark:bg-amber-500/10 dark:border-amber-500/30 dark:hover:bg-amber-500/20"
        )}
        onClick={() => setExpanded(!expanded)}
      >
        {/* Status Icon */}
        <div className={cn(
          "w-10 h-10 rounded-full flex items-center justify-center flex-shrink-0",
          result.approved ? "bg-success-100 dark:bg-success-500/20" : isRejectionLevel ? "bg-error-100 dark:bg-error-500/20" : "bg-amber-100 dark:bg-amber-500/20"
        )}>
          {result.approved ? (
            <CheckCircle2 className="w-5 h-5 text-success-600 dark:text-success-300" />
          ) : isRejectionLevel ? (
            <XCircle className="w-5 h-5 text-error-600 dark:text-error-300" />
          ) : (
            <AlertTriangle className="w-5 h-5 text-amber-600 dark:text-amber-300" />
          )}
        </div>

        {/* Content */}
        <div className="flex-1 min-w-0">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <Badge variant="neutral" size="sm">Level {result.level}</Badge>
              <span className="font-medium text-primary-900 dark:text-neutral-50">{result.vaNumber}</span>
              <Badge variant="info" size="sm">
                <CategoryIcon className="w-3 h-3 mr-1" />
                {result.accountCategory?.replace('_', ' ') || 'VA'}
              </Badge>
            </div>
            <div className="flex items-center gap-2">
              <Badge variant="neutral" size="sm">{result.vaCurrency}</Badge>
              {expanded ? <ChevronDown className="w-4 h-4" /> : <ChevronRight className="w-4 h-4" />}
            </div>
          </div>

          <div className="mt-2 grid grid-cols-4 gap-2 text-sm">
            <div>
              <p className="text-neutral-500 dark:text-neutral-400">Requested</p>
              <p className="font-semibold">{formatCurrency(result.requestedAmountInVaCurrency, result.vaCurrency)}</p>
            </div>
            <div>
              <p className="text-neutral-500 dark:text-neutral-400">Balance</p>
              <p className="font-semibold text-info-600 dark:text-info-300">{formatCurrency(result.balance, result.vaCurrency)}</p>
            </div>
            <div>
              <p className="text-neutral-500 dark:text-neutral-400">Limits</p>
              <p className="font-semibold text-purple-600 dark:text-purple-300">
                {formatCurrency(result.externalLimitAvailable + result.internalLimitAvailable, result.vaCurrency)}
              </p>
            </div>
            <div>
              <p className="text-neutral-500 dark:text-neutral-400">Total Available</p>
              <p className={cn("font-semibold", result.approved ? "text-success-600 dark:text-success-300" : "text-error-600 dark:text-error-300")}>
                {formatCurrency(result.totalAvailable, result.vaCurrency)}
              </p>
            </div>
          </div>

          {!result.approved && result.rejectionReason && (
            <div className="mt-2 p-2 bg-error-100 rounded text-sm text-error-700 dark:bg-error-500/20 dark:text-error-300">
              <strong>Rejection:</strong> {result.rejectionReason}
              {result.shortfall && (
                <span className="ml-2">
                  (Shortfall: {formatCurrency(result.shortfall, result.vaCurrency)})
                </span>
              )}
            </div>
          )}

          {/* Expanded Details */}
          {expanded && (
            <div className="mt-3 pt-3 border-t border-neutral-200 grid grid-cols-2 gap-3 text-sm dark:border-primary-800">
              <div className="p-2 bg-white rounded dark:bg-primary-900">
                <p className="text-neutral-500 dark:text-neutral-400">External Limit</p>
                <p className="font-medium">{formatCurrency(result.externalLimitAvailable, result.vaCurrency)}</p>
              </div>
              <div className="p-2 bg-white rounded dark:bg-primary-900">
                <p className="text-neutral-500 dark:text-neutral-400">Internal Limit</p>
                <p className="font-medium">{formatCurrency(result.internalLimitAvailable, result.vaCurrency)}</p>
              </div>
              {result.limitUsageRequired && result.limitUsageRequired > 0 && (
                <div className="col-span-2 p-2 bg-amber-100 rounded dark:bg-amber-500/20">
                  <p className="text-amber-700 dark:text-amber-300">
                    <CreditCard className="w-4 h-4 inline mr-1" />
                    Limit usage required: {formatCurrency(result.limitUsageRequired, result.vaCurrency)}
                  </p>
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
// FUNDS CHECK RESULT DISPLAY
// ============================================================================

interface FundsCheckDisplayProps {
  result: FundsCheckResult;
  onClose?: () => void;
}

const FundsCheckDisplay: React.FC<FundsCheckDisplayProps> = ({ result, onClose }) => {
  return (
    <div className="space-y-4">
      {/* Summary Header */}
      <div className={cn(
        "p-4 rounded-xl",
        result.approved ? "bg-success-100 border-2 border-success-300 dark:bg-success-500/20" : "bg-error-100 border-2 border-error-300 dark:bg-error-500/20"
      )}>
        <div className="flex items-center gap-4">
          <div className={cn(
            "w-16 h-16 rounded-full flex items-center justify-center",
            result.approved ? "bg-success-200" : "bg-error-200"
          )}>
            {result.approved ? (
              <CheckCircle2 className="w-10 h-10 text-success-600 dark:text-success-300" />
            ) : (
              <XCircle className="w-10 h-10 text-error-600 dark:text-error-300" />
            )}
          </div>
          <div className="flex-1">
            <h3 className={cn(
              "text-xl font-bold",
              result.approved ? "text-success-800 dark:text-success-300" : "text-error-800 dark:text-error-300"
            )}>
              {result.approved ? 'Funds Available' : 'Insufficient Funds'}
            </h3>
            <p className={cn(
              "text-sm mt-1",
              result.approved ? "text-success-700 dark:text-success-300" : "text-error-700 dark:text-error-300"
            )}>
              {result.approved 
                ? `All ${result.levelsChecked} hierarchy levels approved`
                : `Rejected at level ${result.rejectionLevel}: ${result.rejectionReason}`
              }
            </p>
          </div>
          <div className="text-right">
            <p className="text-sm text-neutral-600 dark:text-neutral-300">Requested Amount</p>
            <p className="stat-value-sm">
              {formatCurrency(result.requestedAmount, result.requestedCurrency)}
            </p>
          </div>
        </div>
      </div>

      {/* Level-by-Level Results */}
      <div className="bg-white rounded-xl border p-4 dark:bg-primary-900">
        <h4 className="text-sm font-semibold text-primary-900 mb-4 flex items-center gap-2 dark:text-neutral-50">
          <Layers className="w-4 h-4" />
          Hierarchy Check ({result.levelsChecked} levels)
        </h4>
        
        <div className="space-y-0">
          {result.levelResults.map((level, idx) => (
            <LevelCheckRow
              key={level.vaId}
              result={level}
              isLast={idx === result.levelResults.length - 1}
              isRejectionLevel={!result.approved && result.rejectionLevel === level.level}
            />
          ))}
        </div>
      </div>

      {/* Rejection Details */}
      {!result.approved && (
        <div className="bg-error-50 border border-error-200 rounded-xl p-4 dark:bg-error-500/10 dark:border-error-500/30">
          <h4 className="text-sm font-semibold text-error-800 flex items-center gap-2 dark:text-error-300">
            <AlertCircle className="w-4 h-4" />
            Transaction Cannot Proceed
          </h4>
          <p className="text-sm text-error-700 mt-2 dark:text-error-300">
            The debit of <strong>{formatCurrency(result.requestedAmount, result.requestedCurrency)}</strong> was 
            rejected at <strong>{result.rejectionVaNumber}</strong> (Level {result.rejectionLevel}).
          </p>
          <p className="text-sm text-error-600 mt-1 dark:text-error-300">
            Reason: {result.rejectionReason}
          </p>
          <div className="mt-3 pt-3 border-t border-error-200 dark:border-error-500/30">
            <p className="text-xs text-error-600 dark:text-error-300">
              <Info className="w-3 h-3 inline mr-1" />
              Consider increasing credit limits or ensuring sufficient balance at the rejection level.
            </p>
          </div>
        </div>
      )}

      {/* Actions */}
      {onClose && (
        <div className="flex justify-end gap-2 pt-4 border-t">
          <Button variant="ghost" onClick={onClose}>Close</Button>
          {!result.approved && (
            <Button variant="outline">
              <Eye className="w-4 h-4 mr-2" />
              View Credit Limits
            </Button>
          )}
        </div>
      )}
    </div>
  );
};

// ============================================================================
// FUNDS CHECK WIDGET (Embeddable)
// ============================================================================

interface FundsCheckWidgetProps {
  vaId?: string;
  amount?: number;
  currency?: string;
  onResult?: (result: FundsCheckResult) => void;
  compact?: boolean;
}

export const FundsCheckWidget: React.FC<FundsCheckWidgetProps> = ({
  vaId: initialVaId,
  amount: initialAmount,
  currency: initialCurrency = 'AED',
  onResult,
  compact = false,
}) => {
  const [vaId, setVaId] = useState(initialVaId || '');
  const [amount, setAmount] = useState(initialAmount || 0);
  const [currency, setCurrency] = useState(initialCurrency);
  const [checking, setChecking] = useState(false);
  const [result, setResult] = useState<FundsCheckResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (initialVaId) setVaId(initialVaId);
    if (initialAmount) setAmount(initialAmount);
    if (initialCurrency) setCurrency(initialCurrency);
  }, [initialVaId, initialAmount, initialCurrency]);

  const handleCheck = async () => {
    if (!vaId || amount <= 0) return;

    setChecking(true);
    setError(null);
    setResult(null);

    try {
      // Simulate API call
      await new Promise(resolve => setTimeout(resolve, 1500));

      // Mock result
      const mockResult: FundsCheckResult = {
        vaId,
        requestedAmount: amount,
        requestedCurrency: currency,
        checkedAt: new Date().toISOString(),
        approved: amount <= 50000,
        rejectionLevel: amount > 50000 ? 2 : 0,
        rejectionReason: amount > 50000 ? 'Insufficient balance at Currency Mirror level' : undefined,
        rejectionVaNumber: amount > 50000 ? 'MIRROR-EUR-001' : undefined,
        levelsChecked: 4,
        levelResults: [
          {
            level: 1,
            vaId: 'va-001',
            vaNumber: 'VA-COLL-001',
            vaCurrency: currency,
            accountCategory: 'COLLECTION',
            requestedAmountInVaCurrency: amount,
            balance: 75000,
            externalLimitAvailable: 0,
            internalLimitAvailable: 25000,
            totalAvailable: 100000,
            approved: true,
          },
          {
            level: 2,
            vaId: 'mirror-eur',
            vaNumber: 'MIRROR-EUR-001',
            vaCurrency: 'EUR',
            accountCategory: 'CURRENCY_MIRROR',
            requestedAmountInVaCurrency: amount * 0.92,
            balance: 45000,
            externalLimitAvailable: 0,
            internalLimitAvailable: 0,
            totalAvailable: 45000,
            approved: amount <= 50000,
            rejectionReason: amount > 50000 ? 'Insufficient funds' : undefined,
            shortfall: amount > 50000 ? (amount * 0.92) - 45000 : undefined,
          },
          {
            level: 3,
            vaId: 'shadow-eur',
            vaNumber: 'SHADOW-EUR-001',
            vaCurrency: 'EUR',
            accountCategory: 'PHYSICAL_MIRROR',
            requestedAmountInVaCurrency: amount * 0.92,
            balance: 15500000,
            externalLimitAvailable: 30000000,
            internalLimitAvailable: 0,
            totalAvailable: 45500000,
            approved: amount <= 50000,
          },
          {
            level: 4,
            vaId: 'root',
            vaNumber: 'ROOT-ACME',
            vaCurrency: 'AED',
            accountCategory: 'ROOT',
            requestedAmountInVaCurrency: amount,
            balance: 163758500,
            externalLimitAvailable: 60000000,
            internalLimitAvailable: 50000000,
            totalAvailable: 273758500,
            approved: amount <= 50000,
          },
        ].slice(0, amount > 50000 ? 2 : 4),
      };

      setResult(mockResult);
      onResult?.(mockResult);

    } catch (err) {
      console.error('Funds check failed:', err);
      setError('Failed to check funds availability');
    } finally {
      setChecking(false);
    }
  };

  if (compact) {
    return (
      <div className="flex items-center gap-2">
        <Input
          placeholder="VA ID"
          value={vaId}
          onChange={(e) => setVaId(e.target.value)}
          className="w-32"
        />
        <Input
          type="number"
          placeholder="Amount"
          value={amount || ''}
          onChange={(e) => setAmount(parseFloat(e.target.value) || 0)}
          className="w-32"
        />
        <Button onClick={handleCheck} disabled={checking || !vaId || amount <= 0} size="sm">
          {checking ? <Loader2 className="w-4 h-4 animate-spin" /> : <Shield className="w-4 h-4" />}
        </Button>
        {result && (
          <Badge variant={result.approved ? 'success' : 'error'}>
            {result.approved ? 'OK' : 'FAIL'}
          </Badge>
        )}
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {/* Input Form */}
      <Card hover>
        <div className="p-4">
          <div className="flex items-center gap-3 mb-4">
            <StatusIconBadge tone="primary" icon={Shield} className="dark:bg-primary-700" />
            <h3 className="section-title">Funds Availability Check</h3>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
            <div className="md:col-span-2">
              <label className="block label mb-1">Virtual Account ID</label>
              <Input
                placeholder="Enter VA ID or VA Number"
                value={vaId}
                onChange={(e) => setVaId(e.target.value)}
              />
            </div>
            <div>
              <label className="block label mb-1">Amount</label>
              <Input
                type="number"
                placeholder="0.00"
                value={amount || ''}
                onChange={(e) => setAmount(parseFloat(e.target.value) || 0)}
              />
            </div>
            <div>
              <label className="block label mb-1">Currency</label>
              <CurrencyPicker
                value={currency}
                onChange={(c) => setCurrency(c)}
                className="bg-white text-sm font-medium dark:bg-primary-900"
              />
            </div>
          </div>

          <div className="mt-4 flex items-center justify-between">
            <p className="text-sm text-neutral-500 dark:text-neutral-400">
              <Info className="w-4 h-4 inline mr-1" />
              Checks balance and credit limits at every hierarchy level
            </p>
            <Button onClick={handleCheck} disabled={checking || !vaId || amount <= 0}>
              {checking ? (
                <Loader2 className="w-4 h-4 mr-2 animate-spin" />
              ) : (
                <Shield className="w-4 h-4 mr-2" />
              )}
              Check Availability
            </Button>
          </div>
        </div>
      </Card>

      {/* Error */}
      {error && (
        <Card className="bg-error-50 border-error-200 dark:bg-error-500/10 dark:border-error-500/30">
          <div className="flex items-center gap-3 p-4">
            <StatusIconBadge tone="error" icon={AlertCircle} className="dark:bg-error-500/20" />
            <p className="text-error-700 font-medium dark:text-error-300">{error}</p>
          </div>
        </Card>
      )}

      {/* Result */}
      {result && <FundsCheckDisplay result={result} />}
    </div>
  );
};

// ============================================================================
// FUNDS CHECK MODAL (For Transaction Flows)
// ============================================================================

interface FundsCheckModalProps {
  isOpen: boolean;
  onClose: () => void;
  vaId: string;
  amount: number;
  currency: string;
  onApproved?: () => void;
  onRejected?: (reason: string) => void;
}

export const FundsCheckModal: React.FC<FundsCheckModalProps> = ({
  isOpen,
  onClose,
  vaId,
  amount,
  currency,
  onApproved,
  onRejected,
}) => {
  const [checking, setChecking] = useState(false);
  const [result, setResult] = useState<FundsCheckResult | null>(null);

  useEffect(() => {
    if (isOpen && vaId && amount > 0) {
      performCheck();
    }
  }, [isOpen, vaId, amount]);

  const performCheck = async () => {
    setChecking(true);
    setResult(null);

    try {
      await new Promise(resolve => setTimeout(resolve, 2000));

      // Mock result
      const approved = amount <= 50000;
      const mockResult: FundsCheckResult = {
        vaId,
        requestedAmount: amount,
        requestedCurrency: currency,
        checkedAt: new Date().toISOString(),
        approved,
        rejectionLevel: approved ? 0 : 2,
        rejectionReason: approved ? undefined : 'Insufficient balance',
        rejectionVaNumber: approved ? undefined : 'MIRROR-EUR-001',
        levelsChecked: 4,
        levelResults: [],
      };

      setResult(mockResult);

      if (mockResult.approved) {
        onApproved?.();
      } else {
        onRejected?.(mockResult.rejectionReason || 'Unknown reason');
      }

    } catch (err) {
      console.error('Funds check failed:', err);
    } finally {
      setChecking(false);
    }
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title="Checking Funds Availability"
      size="lg"
    >
      <div className="p-4">
        {checking ? (
          <div className="py-12 text-center">
            <Loader2 className="w-12 h-12 animate-spin text-primary-600 mx-auto mb-4 dark:text-primary-200" />
            <h3 className="section-title">Verifying Funds</h3>
            <p className="text-neutral-500 mt-2 dark:text-neutral-400">
              Checking balance and credit limits across hierarchy...
            </p>
            <div className="mt-4 max-w-xs mx-auto">
              <div className="h-2 bg-neutral-200 rounded-full overflow-hidden dark:bg-primary-800">
                <div className="h-full bg-primary-600 rounded-full animate-pulse" style={{ width: '60%' }} />
              </div>
            </div>
          </div>
        ) : result ? (
          <FundsCheckDisplay result={result} onClose={onClose} />
        ) : (
          <div className="py-12 text-center">
            <AlertCircle className="w-12 h-12 text-neutral-300 mx-auto mb-4 dark:text-neutral-600" />
            <p className="text-neutral-500 dark:text-neutral-400">Ready to check funds availability</p>
          </div>
        )}
      </div>
    </Modal>
  );
};

// ============================================================================
// STANDALONE PAGE
// ============================================================================

const FundsAvailabilityPage: React.FC = () => {
  return (
    <Page>
      {/* Page Header */}
      <div className="animate-fade-in" style={{ animationDelay: '0.05s' }}>
        <h1 className="page-title">Funds Availability</h1>
      </div>

      {/* Info Banner */}
      <Card padding="sm" className="bg-gradient-to-r from-info-50/50 via-white to-primary-50/50 border-info-200/60 animate-fade-in" style={{ animationDelay: '0.1s' }}>
        <div className="flex items-start gap-3 p-4">
          <StatusIconBadge tone="info" icon={Shield} className="flex-shrink-0 dark:bg-info-500/20" />
          <div>
            <p className="text-sm font-semibold text-info-800 dark:text-info-300">Hierarchical Funds Check</p>
            <p className="text-sm text-info-700 mt-1 dark:text-info-300">
              Every debit transaction must pass funds availability checks at <strong>every level</strong> of the
              hierarchy - from the source VA up to ROOT. This ensures both balance adequacy and credit limit
              compliance across the entire corporate structure.
            </p>
          </div>
        </div>
      </Card>

      {/* Main Widget */}
      <div className="animate-fade-in" style={{ animationDelay: '0.15s' }}>
        <FundsCheckWidget />
      </div>

      {/* Quick Reference */}
      <Card hover className="animate-fade-in" style={{ animationDelay: '0.2s' }}>
        <div className="p-4">
          <div className="flex items-center gap-3 mb-4">
            <StatusIconBadge tone="accent" icon={Layers} className="dark:bg-accent-500/20" />
            <h3 className="section-title">Check Process</h3>
          </div>
          <div className="flex items-center gap-4 text-sm">
            <div className="flex items-center gap-2">
              <div className="w-8 h-8 rounded-full bg-success-100 flex items-center justify-center dark:bg-success-500/20">
                <Wallet className="w-4 h-4 text-success-600 dark:text-success-300" />
              </div>
              <span className="font-medium text-primary-900 dark:text-neutral-50">Source VA</span>
            </div>
            <ArrowRight className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
            <div className="flex items-center gap-2">
              <div className="w-8 h-8 rounded-full bg-info-100 flex items-center justify-center dark:bg-info-500/20">
                <TrendingUp className="w-4 h-4 text-info-600 dark:text-info-300" />
              </div>
              <span className="font-medium text-primary-900 dark:text-neutral-50">Currency Mirror</span>
            </div>
            <ArrowRight className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
            <div className="flex items-center gap-2">
              <div className="w-8 h-8 rounded-full bg-warning-100 flex items-center justify-center dark:bg-warning-500/20">
                <Layers className="w-4 h-4 text-warning-600 dark:text-warning-300" />
              </div>
              <span className="font-medium text-primary-900 dark:text-neutral-50">Shadow Account</span>
            </div>
            <ArrowRight className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
            <div className="flex items-center gap-2">
              <div className="w-8 h-8 rounded-full bg-accent-100 flex items-center justify-center dark:bg-accent-500/20">
                <Shield className="w-4 h-4 text-accent-600 dark:text-accent-300" />
              </div>
              <span className="font-medium text-primary-900 dark:text-neutral-50">ROOT</span>
            </div>
          </div>
          <p className="text-xs text-neutral-500 mt-3 uppercase tracking-wider dark:text-neutral-400">
            At each level: Balance + External Limit + Internal Limit ≥ Requested Amount
          </p>
        </div>
      </Card>
    </Page>
  );
};

export default FundsAvailabilityPage;