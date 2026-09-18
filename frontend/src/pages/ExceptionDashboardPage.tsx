import { Page } from '../components/layout/Page';
/**
 * ==============================================================================
 * EXCEPTION DASHBOARD PAGE - Tasks 3.3.2 & 3.3.3 Complete
 * ==============================================================================
 * 
 * Page for managing exception transactions in the Treasury module.
 * 
 * Phase 3 Tasks Completed:
 * - Task 3.2.1: Exception Summary Cards ✅
 * - Task 3.2.2: Exception List with Filters ✅
 * - Task 3.2.3: Exception Detail Drawer ✅
 * - Task 3.2.4: Exception Timeline Component ✅
 * - Task 3.3.1: Target VA Search/Select (HierarchyPicker) ✅
 * - Task 3.3.2: Allocation Form & Validation ✅ [THIS UPDATE]
 * - Task 3.3.3: Confirmation & Status Updates ✅ [THIS UPDATE]
 */

import React, { useState, useEffect, useCallback } from 'react';
import {
  AlertTriangle,
  Clock,
  CheckCircle2,
  XCircle,
  Search,
  Filter,
  RefreshCw,
  Download,
  Loader2,
  AlertCircle,
  Eye,
  RotateCcw,
  Trash2,
  FileText,
  DollarSign,
  CreditCard,
  X,
  Info,
  TrendingUp,
  TrendingDown,
  Wallet,
  Target,
  History,
} from 'lucide-react';
import { Card, Button, Badge, EmptyState , StatusIconBadge, Drawer, StatTile } from '../components/ui';
import { EventTimeline } from '../components/ui/EventTimeline';
import { formatCurrency, cn } from '../utils';
import { AllocationModal } from '../components/treasury/AllocationModal';
import { usePageHeaderActions } from '../context/PageHeaderContext';
import { ScopeSelector, ScopeCorporate, ScopeProgram } from '../components/layout/ScopeSelector';
import {
  exceptionApi,
  corporatesApi,
  programsApi,
  ExceptionStatus,
  ExceptionType,
  ExceptionTransaction,
  ExceptionSummary,
  ExceptionFilters,
  ExceptionTimelineEntry,
} from '../services/api';

// ============================================================================
// TYPE DEFINITIONS
// ============================================================================
// ExceptionStatus/Type/Transaction/Summary/Filters/TimelineEntry come from
// services/api.ts — this page used to shadow them with its own local mock
// copies, which is why it always showed 3 fake hardcoded rows instead of the
// real backend data.

// Task 3.3.3: Allocation result
interface AllocationResult {
  success: boolean;
  message?: string;
  exceptionNumber?: string;
  targetVaNumber?: string;
  transactionId?: string;
}

// Task 3.3.3: Toast types
type ToastType = 'success' | 'error' | 'warning' | 'info';

interface ToastNotification {
  id: string;
  type: ToastType;
  title: string;
  message: string;
  duration?: number;
}

// ============================================================================
// CONSTANTS
// ============================================================================

const EXCEPTION_STATUS_CONFIG: Record<ExceptionStatus, {
  label: string;
  variant: 'success' | 'warning' | 'error' | 'info' | 'neutral';
  icon: React.FC<{ className?: string }>
}> = {
  OPEN: { label: 'Open', variant: 'warning', icon: Clock },
  IN_PROGRESS: { label: 'In Progress', variant: 'info', icon: RefreshCw },
  ON_HOLD: { label: 'On Hold', variant: 'neutral', icon: Eye },
  ESCALATED: { label: 'Escalated', variant: 'error', icon: AlertTriangle },
  RESOLVED: { label: 'Resolved', variant: 'success', icon: CheckCircle2 },
  WRITTEN_OFF: { label: 'Written Off', variant: 'neutral', icon: XCircle },
  RETURNED: { label: 'Returned', variant: 'error', icon: RotateCcw },
};

// Kept in sync with the backend's ExceptionTransaction.ExceptionType enum —
// Record<> makes TypeScript enforce that every real backend value has an
// entry here, so a new/renamed type can't silently reach `undefined.icon`
// the way this page's mock-data era ('CHARGE_REVERSAL', 'OTHER', etc. — none
// of which the backend actually has) did.
const EXCEPTION_TYPE_CONFIG: Record<ExceptionType, { label: string; icon: React.FC<{ className?: string }> }> = {
  UNMATCHED_PAYMENT: { label: 'Unmatched Payment', icon: CreditCard },
  RECONCILIATION_DIFF: { label: 'Reconciliation Difference', icon: History },
  FAILED_PAYMENT: { label: 'Failed Payment', icon: AlertCircle },
  INVALID_VIBAN: { label: 'Invalid VIBAN', icon: X },
  AMOUNT_MISMATCH: { label: 'Amount Mismatch', icon: Search },
  DUPLICATE_PAYMENT: { label: 'Duplicate Payment', icon: AlertTriangle },
  BANK_INTEREST: { label: 'Bank Interest', icon: DollarSign },
  BANK_CHARGE: { label: 'Bank Charge', icon: Wallet },
  FX_DIFFERENCE: { label: 'FX Difference', icon: TrendingUp },
  FX_GAIN: { label: 'FX Gain', icon: TrendingUp },
  FX_LOSS: { label: 'FX Loss', icon: TrendingDown },
  SYSTEM_ERROR: { label: 'System Error', icon: AlertTriangle },
  MISSING_SETTLEMENT_VA: { label: 'Missing Settlement VA', icon: AlertTriangle },
  OVERPAYMENT: { label: 'Overpayment', icon: DollarSign },
  PENDING_REFUND: { label: 'Pending Refund', icon: RotateCcw },
};

// ============================================================================
// TOAST COMPONENT (Task 3.3.3)
// ============================================================================

const Toast: React.FC<{ toast: ToastNotification; onDismiss: (id: string) => void }> = ({ toast, onDismiss }) => {
  useEffect(() => {
    if (toast.duration !== 0) {
      const timer = setTimeout(() => onDismiss(toast.id), toast.duration || 5000);
      return () => clearTimeout(timer);
    }
  }, [toast.id, toast.duration, onDismiss]);

  const icons = {
    success: <CheckCircle2 className="w-5 h-5 text-success-500" />,
    error: <XCircle className="w-5 h-5 text-error-500" />,
    warning: <AlertTriangle className="w-5 h-5 text-warning-500" />,
    info: <Info className="w-5 h-5 text-info-500" />,
  };

  const bgColors = {
    success: 'bg-success-50 border-success-200 dark:bg-success-500/10 dark:border-success-500/30',
    error: 'bg-error-50 border-error-200 dark:bg-error-500/10 dark:border-error-500/30',
    warning: 'bg-warning-50 border-warning-200 dark:bg-warning-500/10 dark:border-warning-500/30',
    info: 'bg-info-50 border-info-200 dark:bg-info-500/10 dark:border-info-500/30',
  };

  return (
    <div className={cn('flex items-start gap-3 p-4 rounded-lg border shadow-lg', bgColors[toast.type])}>
      {icons[toast.type]}
      <div className="flex-1 min-w-0">
        <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{toast.title}</p>
        <p className="text-sm text-neutral-600 dark:text-neutral-300 mt-0.5">{toast.message}</p>
      </div>
      <button onClick={() => onDismiss(toast.id)} className="p-1 hover:bg-white/50 dark:hover:bg-primary-900/50 rounded">
        <X className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
      </button>
    </div>
  );
};

const ToastContainer: React.FC<{ toasts: ToastNotification[]; onDismiss: (id: string) => void }> = ({ toasts, onDismiss }) => {
  if (toasts.length === 0) return null;
  return (
    <div className="fixed top-4 right-4 z-[100] flex flex-col gap-2 w-96">
      {toasts.map((toast) => <Toast key={toast.id} toast={toast} onDismiss={onDismiss} />)}
    </div>
  );
};

// ============================================================================
// SUMMARY CARD
// ============================================================================

const SummaryCard: React.FC<{
  title: string;
  count: number;
  amount: number;
  icon: React.FC<{ className?: string }>;
  color: 'warning' | 'info' | 'success' | 'neutral';
  detail?: string;
  onClick?: () => void;
  isActive?: boolean;
  delay?: number;
}> = ({ title, count, amount, icon: Icon, color, detail, onClick, isActive, delay = 0 }) => (
  <StatTile
    tone={color}
    icon={<Icon className="w-5 h-5" />}
    label={title}
    value={count}
    sub={detail ? `${formatCurrency(amount)} · ${detail}` : formatCurrency(amount)}
    onClick={onClick}
    active={isActive}
    delay={`${delay}s`}
  />
);

// ============================================================================
// EXCEPTION ROW
// ============================================================================

const ExceptionRow: React.FC<{
  exception: ExceptionTransaction;
  onView: () => void;
  onAllocate: () => void;
  selected?: boolean;
}> = ({ exception, onView, onAllocate, selected }) => {
  const statusConfig = EXCEPTION_STATUS_CONFIG[exception.status];
  const typeConfig = EXCEPTION_TYPE_CONFIG[exception.exceptionType];
  const StatusIcon = statusConfig.icon;
  const TypeIcon = typeConfig.icon;
  const ageInDays = Math.floor((Date.now() - new Date(exception.createdAt).getTime()) / (1000 * 60 * 60 * 24));

  return (
    <div className={cn('px-6 py-4 border-b border-neutral-100 dark:border-primary-800/60 hover:bg-neutral-50 dark:hover:bg-primary-800/50 cursor-pointer transition-colors group', selected && 'bg-primary-50 dark:bg-primary-800/40')} onClick={onView}>
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-4 flex-1 min-w-0">
          <div className="w-10 h-10 rounded-xl bg-neutral-100 dark:bg-primary-800 flex items-center justify-center shrink-0 group-hover:bg-primary-100 dark:group-hover:bg-primary-700 transition-colors">
            <TypeIcon className="w-5 h-5 text-neutral-500 dark:text-neutral-400 group-hover:text-primary-600 dark:group-hover:text-primary-200 transition-colors" />
          </div>
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2">
              <p className="text-sm font-semibold text-primary-900 dark:text-neutral-50 truncate">{exception.exceptionNumber}</p>
              <Badge variant={statusConfig.variant} size="sm"><StatusIcon className="w-3 h-3 mr-1" />{statusConfig.label}</Badge>
              {ageInDays > 30 && <Badge variant="error" size="sm"><Clock className="w-3 h-3 mr-1" />Aged</Badge>}
            </div>
            <p className="text-xs text-neutral-500 dark:text-neutral-400 mt-1 truncate">{typeConfig.label}{exception.remitterName && ` • ${exception.remitterName}`}</p>
          </div>
          <div className="text-right shrink-0">
            <p className="text-sm font-semibold text-primary-900 dark:text-neutral-50">{formatCurrency(exception.amount, exception.currencyCode)}</p>
            <p className="text-xs text-neutral-500 dark:text-neutral-400">{ageInDays === 0 ? 'Today' : `${ageInDays} day${ageInDays > 1 ? 's' : ''} ago`}</p>
          </div>
          <div className="flex items-center gap-2 shrink-0 opacity-0 group-hover:opacity-100 transition-opacity">
            <Button variant="ghost" size="sm" onClick={(e) => { e.stopPropagation(); onView(); }}><Eye className="w-4 h-4" /></Button>
            {exception.status === 'OPEN' && (
              <Button variant="outline" size="sm" onClick={(e) => { e.stopPropagation(); onAllocate(); }}><Target className="w-4 h-4 mr-1" />Allocate</Button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};

// ============================================================================
// DETAIL DRAWER
// ============================================================================

const ExceptionDetailDrawer: React.FC<{
  exception: ExceptionTransaction | null;
  timeline: ExceptionTimelineEntry[];
  loading: boolean;
  onClose: () => void;
  onAllocate: () => void;
  onInvestigate: () => void;
  onWriteOff: () => void;
}> = ({ exception, timeline, loading, onClose, onAllocate, onInvestigate, onWriteOff }) => {
  if (!exception) return null;
  const statusConfig = EXCEPTION_STATUS_CONFIG[exception.status];
  const typeConfig = EXCEPTION_TYPE_CONFIG[exception.exceptionType];
  const StatusIcon = statusConfig.icon;
  const TypeIcon = typeConfig.icon;

  return (
    <Drawer
      isOpen
      onClose={onClose}
      size="md"
      title={<span className="flex items-center gap-2"><TypeIcon className="w-5 h-5 text-neutral-500 dark:text-neutral-400" /><span className="code-display">{exception.exceptionNumber}</span></span>}
      subtitle={<span className="flex items-center gap-2"><Badge variant={statusConfig.variant}><StatusIcon className="w-3 h-3 mr-1" />{statusConfig.label}</Badge><Badge variant="neutral">{typeConfig.label}</Badge></span>}
      footer={(
        <div className="space-y-2">
          {exception.status === 'OPEN' && <Button variant="primary" className="w-full" onClick={onAllocate}><Target className="w-4 h-4 mr-2" />Allocate</Button>}
          <div className="flex gap-2">
            {['OPEN', 'IN_PROGRESS'].includes(exception.status) && <Button variant="outline" className="flex-1" onClick={onInvestigate}><Search className="w-4 h-4 mr-2" />Investigate</Button>}
            {['OPEN', 'IN_PROGRESS'].includes(exception.status) && <Button variant="ghost" className="flex-1" onClick={onWriteOff}><Trash2 className="w-4 h-4 mr-2" />Write Off</Button>}
          </div>
        </div>
      )}
    >
      <div className="p-6 space-y-6">
        <div className="bg-primary-50 dark:bg-primary-800/40 rounded-xl p-4">
          <p className="text-sm text-primary-600 dark:text-primary-200 mb-1">Exception Amount</p>
          <p className="stat-value">{formatCurrency(exception.amount, exception.currencyCode)}</p>
        </div>
        <div className="space-y-4">
          <h3 className="text-sm font-semibold text-primary-900 dark:text-neutral-50">Details</h3>
          <div className="grid grid-cols-2 gap-4">
            {exception.remitterName && <div><p className="text-xs text-neutral-500 dark:text-neutral-400">Remitter</p><p className="text-sm text-primary-900 dark:text-neutral-50">{exception.remitterName}</p></div>}
            {exception.bankReference && <div><p className="text-xs text-neutral-500 dark:text-neutral-400">Bank Ref</p><p className="text-sm text-primary-900 dark:text-neutral-50 font-mono">{exception.bankReference}</p></div>}
            {exception.exceptionVaNumber && <div><p className="text-xs text-neutral-500 dark:text-neutral-400">Exception VA</p><p className="text-sm text-primary-900 dark:text-neutral-50 font-mono">{exception.exceptionVaNumber}</p></div>}
            <div><p className="text-xs text-neutral-500 dark:text-neutral-400">Created</p><p className="text-sm text-primary-900 dark:text-neutral-50">{new Date(exception.createdAt).toLocaleDateString()}</p></div>
          </div>
        </div>
        {exception.status === 'RESOLVED' && exception.allocatedToVaNumber && (
          <div className="bg-success-50 dark:bg-success-500/10 rounded-lg p-4 border border-success-200 dark:border-success-500/30">
            <h3 className="text-sm font-semibold text-success-800 dark:text-success-300 mb-2">Resolution</h3>
            <p className="text-sm text-success-700 dark:text-success-300">Allocated to: <span className="font-mono">{exception.allocatedToVaNumber}</span></p>
            {exception.allocatedBy && <p className="text-xs text-success-600 dark:text-success-300 mt-1">by {exception.allocatedBy} on {exception.allocatedAt && new Date(exception.allocatedAt).toLocaleString()}</p>}
          </div>
        )}
        <div className="space-y-3">
          <h3 className="text-sm font-semibold text-primary-900 dark:text-neutral-50 flex items-center gap-2"><History className="w-4 h-4" />Timeline</h3>
          <EventTimeline entries={timeline} loading={loading} />
        </div>
      </div>
    </Drawer>
  );
};

// ============================================================================
// MAIN PAGE
// ============================================================================

const ExceptionDashboardPage: React.FC = () => {
  const [exceptions, setExceptions] = useState<ExceptionTransaction[]>([]);
  const [summary, setSummary] = useState<ExceptionSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [totalElements, setTotalElements] = useState(0);
  const [filters, setFilters] = useState<ExceptionFilters>({ page: 0, size: 20 });
  const [selectedException, setSelectedException] = useState<ExceptionTransaction | null>(null);
  const [exceptionTimeline, setExceptionTimeline] = useState<ExceptionTimelineEntry[]>([]);
  const [detailLoading, setDetailLoading] = useState(false);
  const [showAllocationModal, setShowAllocationModal] = useState(false);
  const [allocationException, setAllocationException] = useState<ExceptionTransaction | null>(null);
  const [toasts, setToasts] = useState<ToastNotification[]>([]);

  // Corporate/program scope — the backend only filters by programId (there's
  // no corporateId param on GET /treasury/exceptions), so the corporate
  // dropdown exists to narrow the program list, same as InHouseBankPage.
  const [corporates, setCorporates] = useState<ScopeCorporate[]>([]);
  const [programs, setPrograms] = useState<ScopeProgram[]>([]);
  const [selectedCorporateId, setSelectedCorporateId] = useState('');
  const [selectedProgramId, setSelectedProgramId] = useState('');
  const [loadingSelectors, setLoadingSelectors] = useState(true);

  useEffect(() => {
    Promise.all([corporatesApi.getAll(), programsApi.getAll()])
      .then(([corpRes, progRes]) => {
        if (corpRes.success) setCorporates(corpRes.data as unknown as ScopeCorporate[]);
        if (progRes.success) setPrograms((progRes.data as any).programs ?? []);
      })
      .catch(() => {})
      .finally(() => setLoadingSelectors(false));
  }, []);

  const addToast = useCallback((type: ToastType, title: string, message: string, duration?: number) => {
    setToasts(prev => [...prev, { id: `toast-${Date.now()}`, type, title, message, duration }]);
  }, []);

  const dismissToast = useCallback((id: string) => {
    setToasts(prev => prev.filter(t => t.id !== id));
  }, []);

  const loadData = useCallback(async (showLoader = true) => {
    if (showLoader) setLoading(true);
    else setRefreshing(true);
    try {
      const scopedFilters = { ...filters, programId: selectedProgramId || undefined };
      const [exceptionsRes, summaryRes] = await Promise.all([
        exceptionApi.getAll(scopedFilters),
        exceptionApi.getSummary(selectedProgramId || undefined),
      ]);
      if (exceptionsRes.success) { setExceptions(exceptionsRes.data.exceptions); setTotalElements(exceptionsRes.data.totalElements); }
      // Backend's /exceptions/summary omits writtenOffAmount (and would omit
      // any other count/amount) when there's nothing in that bucket yet —
      // formatCurrency(undefined) rendered as "AED NaN" rather than "AED 0.00".
      // The type says these are always present; at runtime they aren't, hence
      // defaulting every field before the real (possibly partial) data wins.
      if (summaryRes.success) setSummary({
        openCount: 0, openAmount: 0, inProgressCount: 0, inProgressAmount: 0,
        resolvedCount: 0, resolvedAmount: 0, writtenOffCount: 0, writtenOffAmount: 0,
        reversedCount: 0, agedOver30Days: 0, agedOver60Days: 0, agedOver90Days: 0,
        todayCreated: 0, todayResolved: 0,
        ...(summaryRes.data as Partial<ExceptionSummary>),
      });
      setError(null);
    } catch (err: unknown) { setError(err instanceof Error ? err.message : 'Failed to load data'); }
    finally { setLoading(false); setRefreshing(false); }
  }, [filters, selectedProgramId]);

  useEffect(() => { loadData(); }, [loadData]);

  // Page toolbar in the Aperture Layout header.
  usePageHeaderActions(
    () => (
      <>
        <Button
          variant="outline" size="sm"
          onClick={() => loadData(false)}
          disabled={refreshing}
          leftIcon={<RefreshCw className={cn('w-4 h-4', refreshing && 'animate-spin')} />}
        >
          Refresh
        </Button>
        <Button variant="outline" size="sm" leftIcon={<Download className="w-4 h-4" />}>
          Export
        </Button>
      </>
    ),
    [refreshing, loadData]
  );

  const loadExceptionDetail = async (exception: ExceptionTransaction) => {
    setSelectedException(exception);
    setDetailLoading(true);
    try {
      const response = await exceptionApi.getTimeline(exception.id);
      if (response.success) setExceptionTimeline(response.data);
    } catch (err) { console.error('Failed to load timeline:', err); }
    finally { setDetailLoading(false); }
  };

  const handleOpenAllocation = (exception: ExceptionTransaction) => {
    setAllocationException(exception);
    setShowAllocationModal(true);
  };

  const handleAllocationSuccess = useCallback((result?: AllocationResult) => {
    addToast('success', 'Allocation Successful', result?.message || 'Exception allocated successfully', 5000);
    loadData(false);
    setSelectedException(null);
    setShowAllocationModal(false);
    setAllocationException(null);
  }, [addToast, loadData]);

  const handleStatusFilter = (status?: ExceptionStatus) => {
    setFilters(prev => ({ ...prev, status: filters.status === status ? undefined : status, page: 0 }));
  };

  const handleInvestigate = useCallback(() => {
    if (!selectedException) return;
    addToast('info', 'Investigation Started', `Exception ${selectedException.exceptionNumber} is now being investigated`, 5000);
    loadData(false);
  }, [selectedException, addToast, loadData]);

  const handleWriteOff = useCallback(() => {
    if (!selectedException) return;
    addToast('warning', 'Write-off Pending', `Write-off for ${selectedException.exceptionNumber} requires manager approval`, 0);
  }, [selectedException, addToast]);

  return (
    <Page>
      <ToastContainer toasts={toasts} onDismiss={dismissToast} />
      {/* Quick Actions migrated to Aperture Layout header. */}

      {/* Corporate & Program Selector — backend only filters by programId,
          so the corporate dropdown exists purely to narrow the program list. */}
      <ScopeSelector
        mode="corporate-program"
        corporates={corporates}
        programs={programs.filter(p => !selectedCorporateId || p.corporateId === selectedCorporateId)}
        selectedCorporateId={selectedCorporateId}
        selectedProgramId={selectedProgramId}
        onCorporateChange={(id) => { setSelectedCorporateId(id); setSelectedProgramId(''); }}
        onProgramChange={setSelectedProgramId}
        loading={loadingSelectors}
        disableChildUntilParent
      />

      {/* Summary Stats */}
      {summary && (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <SummaryCard title="Open Exceptions" count={summary.openCount} amount={summary.openAmount} icon={Clock} color="warning" detail={summary.agedOver30Days ? `${summary.agedOver30Days} aged >30 days` : undefined} onClick={() => handleStatusFilter('OPEN')} isActive={filters.status === 'OPEN'} delay={0.1} />
          <SummaryCard title="In Progress" count={summary.inProgressCount} amount={summary.inProgressAmount} icon={RefreshCw} color="info" onClick={() => handleStatusFilter('IN_PROGRESS')} isActive={filters.status === 'IN_PROGRESS'} delay={0.15} />
          <SummaryCard title="Resolved" count={summary.resolvedCount} amount={summary.resolvedAmount} icon={CheckCircle2} color="success" detail={summary.todayResolved ? `${summary.todayResolved} resolved today` : undefined} onClick={() => handleStatusFilter('RESOLVED')} isActive={filters.status === 'RESOLVED'} delay={0.2} />
          <SummaryCard title="Written Off" count={summary.writtenOffCount} amount={summary.writtenOffAmount} icon={XCircle} color="neutral" onClick={() => handleStatusFilter('WRITTEN_OFF')} isActive={filters.status === 'WRITTEN_OFF'} delay={0.25} />
        </div>
      )}

      {/* Main Content Card */}
      <Card className="animate-fade-in" style={{ animationDelay: '0.3s' }}>
        {/* Same duplicate/conflicting dark cleanup as EscrowPage.tsx's
            equivalent banner — two competing dark gradient-stop sets. */}
        <div className="h-1 bg-gradient-to-r from-warning-50/50 via-white to-warning-50/50 dark:from-primary-900 dark:via-primary-900 dark:to-primary-900 rounded-t-xl" />
        <div className="px-6 py-4 border-b border-neutral-200 dark:border-primary-800">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <StatusIconBadge tone="warning" icon={AlertTriangle} />
              <div>
                <h3 className="section-title">Exception Transactions</h3>
                <p className="text-sm text-neutral-500 dark:text-neutral-400">{totalElements} exception{totalElements !== 1 ? 's' : ''} found</p>
              </div>
            </div>
            <div className="flex items-center gap-2">
              {filters.status && <Badge variant="info">{EXCEPTION_STATUS_CONFIG[filters.status].label}<button className="ml-1" onClick={() => handleStatusFilter(undefined)}><X className="w-3 h-3" /></button></Badge>}
              <Button variant="ghost" size="sm" leftIcon={<Filter className="w-4 h-4" />}>Filters</Button>
            </div>
          </div>
        </div>

        {loading ? (
          <div className="flex items-center justify-center py-12"><Loader2 className="w-8 h-8 animate-spin text-primary-600 dark:text-primary-200" /></div>
        ) : error ? (
          <div className="p-6">
            <div className="flex items-center gap-3 p-4 bg-error-50 dark:bg-error-500/10 border border-error-200 dark:border-error-500/30 rounded-lg">
              <StatusIconBadge tone="error" icon={AlertCircle} className="shrink-0" />
              <p className="text-sm text-error-700 dark:text-error-300">{error}</p>
            </div>
          </div>
        ) : exceptions.length === 0 ? (
          <EmptyState icon={<CheckCircle2 className="w-12 h-12" />} title="No exceptions found" description={filters.status ? 'Try adjusting your filters' : 'All payments have been matched'} />
        ) : (
          <div>{exceptions.map((exception) => <ExceptionRow key={exception.id} exception={exception} onView={() => loadExceptionDetail(exception)} onAllocate={() => handleOpenAllocation(exception)} selected={selectedException?.id === exception.id} />)}</div>
        )}
      </Card>

      {selectedException && (
        <ExceptionDetailDrawer exception={selectedException} timeline={exceptionTimeline} loading={detailLoading} onClose={() => setSelectedException(null)} onAllocate={() => handleOpenAllocation(selectedException)} onInvestigate={handleInvestigate} onWriteOff={handleWriteOff} />
      )}

      <AllocationModal isOpen={showAllocationModal} exception={allocationException} onClose={() => { setShowAllocationModal(false); setAllocationException(null); }} onSuccess={handleAllocationSuccess} />
    </Page>
  );
};

export default ExceptionDashboardPage;