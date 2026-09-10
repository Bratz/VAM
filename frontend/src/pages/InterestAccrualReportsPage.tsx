import React, { useState, useEffect, useCallback } from 'react';
import {
  Percent, Clock, Download, RefreshCw,
  TrendingUp, TrendingDown, Loader2, AlertCircle,
  FileText, DollarSign, Eye, Building2,
  CheckCircle, PiggyBank, CreditCard, BarChart3,
  ArrowUpRight, Activity, Search
} from 'lucide-react';
import { Card, Button, Badge, Input, Select , StatusIconBadge, StatTile, DataTable } from '../components/ui';
import { Modal } from '../components/ui/enhanced';
import { formatCurrency, formatDate, cn } from '../utils';
import { corporatesApi } from '../services/api';
import { Page } from '../components/layout/Page';

// ============================================================================
// API CONFIGURATION
// ============================================================================

const API_BASE = '/api/v1';

interface ApiResponse<T> {
  success: boolean;
  data: T;
  message?: string;
}

// ============================================================================
// TYPES
// ============================================================================

interface Corporate {
  id: string;
  corporateId: string;
  legalName: string;
  tradeName?: string;
}

type AccrualType = 'LOAN' | 'DEPOSIT' | 'VA_CREDIT' | 'VA_DEBIT' | 'POOL_INTEREST' | 'SWEEP_INTEREST';
type AccrualStatus = 'ACCRUED' | 'POSTED' | 'SETTLED' | 'REVERSED' | 'PENDING';

interface InterestAccrual {
  id: string;
  accrualReference: string;
  accrualType: AccrualType;
  corporateId: string;
  entityId?: string;
  entityName?: string;
  virtualAccountId?: string;
  virtualAccountNumber?: string;
  loanId?: string;
  loanReference?: string;
  depositId?: string;
  depositReference?: string;
  interestConfigId?: string;
  interestConfigName?: string;
  currency: string;
  principalBalance: number;
  interestRate: number;
  dayCountConvention: string;
  accrualDate: string;
  periodStart: string;
  periodEnd: string;
  daysInPeriod: number;
  accruedAmount: number;
  cumulativeAccrued: number;
  status: AccrualStatus;
  postedAt?: string;
  settledAt?: string;
  settlementTransactionId?: string;
  notes?: string;
  createdAt: string;
}

interface AccrualSummary {
  corporateId: string;
  periodStart: string;
  periodEnd: string;
  currency: string;
  totalLoanInterestAccrued: number;
  loanAccrualCount: number;
  avgLoanRate: number;
  totalDepositInterestAccrued: number;
  depositAccrualCount: number;
  avgDepositRate: number;
  totalVaCreditInterest: number;
  vaCreditAccrualCount: number;
  avgCreditRate: number;
  totalVaDebitInterest: number;
  vaDebitAccrualCount: number;
  avgDebitRate: number;
  netInterestIncome: number;
  spreadEarned: number;
  pendingAccruals: number;
  postedAccruals: number;
  settledAccruals: number;
}

// ============================================================================
// API SERVICE
// ============================================================================

const interestAccrualApi = {
  getAccruals: (corporateId: string, params?: { 
    type?: AccrualType; 
    status?: AccrualStatus; 
    from?: string; 
    to?: string;
  }) => 
    fetch(`${API_BASE}/interest-accruals/corporate/${corporateId}?${new URLSearchParams(params as any)}`).then(r => r.json()) as Promise<ApiResponse<InterestAccrual[]>>,
  
  getSummary: (corporateId: string, from?: string, to?: string) => 
    fetch(`${API_BASE}/interest-accruals/summary/${corporateId}?${new URLSearchParams({ from: from || '', to: to || '' })}`).then(r => r.json()) as Promise<ApiResponse<AccrualSummary>>,
  
  runDailyAccrual: (corporateId: string, accrualDate?: string) => 
    fetch(`${API_BASE}/interest-accruals/run/${corporateId}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ accrualDate })
    }).then(r => r.json()),
  
  postAccruals: (corporateId: string, accrualIds: string[]) => 
    fetch(`${API_BASE}/interest-accruals/post`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ corporateId, accrualIds })
    }).then(r => r.json()),
  
  settleAccruals: (corporateId: string, accrualIds: string[]) => 
    fetch(`${API_BASE}/interest-accruals/settle`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ corporateId, accrualIds })
    }).then(r => r.json()),
  
  exportReport: (corporateId: string, from: string, to: string, format: 'CSV' | 'EXCEL' | 'PDF') => 
    fetch(`${API_BASE}/interest-accruals/export/${corporateId}?from=${from}&to=${to}&format=${format}`).then(r => r.blob()),
};

// ============================================================================
// SUB-COMPONENTS
// ============================================================================

// Phase 12 Task E: local StatCard clone replaced by the shared
// <StatTile layout="row"> (components/ui/StatTile) — see the summary strip
// in the page body. The original kept the headline number neutral with a
// toned icon medallion, hence `valueTone="neutral"` at the call sites.

const AccrualTypeIcon: React.FC<{ type: AccrualType }> = ({ type }) => {
  switch (type) {
    case 'LOAN': return <CreditCard className="w-4 h-4 text-error-600 dark:text-error-300" />;
    case 'DEPOSIT': return <PiggyBank className="w-4 h-4 text-success-600 dark:text-success-300" />;
    case 'VA_CREDIT': return <TrendingUp className="w-4 h-4 text-success-600 dark:text-success-300" />;
    case 'VA_DEBIT': return <TrendingDown className="w-4 h-4 text-error-600 dark:text-error-300" />;
    case 'POOL_INTEREST': return <Activity className="w-4 h-4 text-info-600 dark:text-info-300" />;
    case 'SWEEP_INTEREST': return <ArrowUpRight className="w-4 h-4 text-primary-600 dark:text-primary-200" />;
    default: return <DollarSign className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />;
  }
};

const getStatusVariant = (status: AccrualStatus): 'success' | 'warning' | 'error' | 'neutral' | 'info' => {
  switch (status) {
    case 'SETTLED': return 'success';
    case 'POSTED': return 'info';
    case 'ACCRUED': return 'warning';
    case 'PENDING': return 'neutral';
    case 'REVERSED': return 'error';
    default: return 'neutral';
  }
};

const getTypeLabel = (type: AccrualType): string => {
  switch (type) {
    case 'LOAN': return 'IHB Loan';
    case 'DEPOSIT': return 'IHB Deposit';
    case 'VA_CREDIT': return 'VA Credit';
    case 'VA_DEBIT': return 'VA Debit';
    case 'POOL_INTEREST': return 'Pool Interest';
    case 'SWEEP_INTEREST': return 'Sweep Interest';
    default: return type;
  }
};


// ============================================================================
// MAIN COMPONENT
// ============================================================================

const InterestAccrualReportsPage: React.FC = () => {
  const [corporates, setCorporates] = useState<Corporate[]>([]);
  const [selectedCorporateId, setSelectedCorporateId] = useState<string>('');
  const [accruals, setAccruals] = useState<InterestAccrual[]>([]);
  const [summary, setSummary] = useState<AccrualSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  
  const [filterType, setFilterType] = useState<AccrualType | 'ALL'>('ALL');
  const [filterStatus, setFilterStatus] = useState<AccrualStatus | 'ALL'>('ALL');
  const [dateFrom, setDateFrom] = useState<string>(() => {
    const d = new Date(); d.setDate(1);
    return d.toISOString().split('T')[0];
  });
  const [dateTo, setDateTo] = useState<string>(() => new Date().toISOString().split('T')[0]);
  const [searchTerm, setSearchTerm] = useState('');
  
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [showDetailModal, setShowDetailModal] = useState(false);
  const [selectedAccrual, setSelectedAccrual] = useState<InterestAccrual | null>(null);

  useEffect(() => { loadCorporates(); }, []);
  useEffect(() => { if (selectedCorporateId) loadData(); }, [selectedCorporateId, filterType, filterStatus, dateFrom, dateTo]);

  const loadCorporates = async () => {
    try {
      const response = await corporatesApi.getAll();
      const data = response?.data || response;
      const corporateList = Array.isArray(data) ? data : [];
      setCorporates(corporateList);
      if (corporateList.length > 0) setSelectedCorporateId(corporateList[0].id);
    } catch (err) {
      setError('Failed to load corporates');
    }
  };

  const loadData = useCallback(async () => {
    if (!selectedCorporateId) return;
    setLoading(true);
    setError(null);
    
    try {
      const params: any = {};
      if (filterType !== 'ALL') params.type = filterType;
      if (filterStatus !== 'ALL') params.status = filterStatus;
      if (dateFrom) params.from = dateFrom;
      if (dateTo) params.to = dateTo;
      
      const [accrualsRes, summaryRes] = await Promise.all([
        interestAccrualApi.getAccruals(selectedCorporateId, params),
        interestAccrualApi.getSummary(selectedCorporateId, dateFrom, dateTo),
      ]);
      
      setAccruals(accrualsRes?.data || []);
      setSummary(summaryRes?.data || null);
    } catch (err) {
      setError('Failed to load interest accrual data. API endpoint may not be implemented yet.');
      setAccruals([]);
      setSummary(null);
    } finally {
      setLoading(false);
    }
  }, [selectedCorporateId, filterType, filterStatus, dateFrom, dateTo]);

  const handleRunAccrual = async () => {
    setRunning(true);
    try {
      const result = await interestAccrualApi.runDailyAccrual(selectedCorporateId);
      if (result?.success) {
        await loadData();
        alert('Daily interest accrual completed!');
      }
    } catch (err) {
      alert('Failed to run interest accrual');
    } finally {
      setRunning(false);
    }
  };

  const handlePostSelected = async () => {
    if (selectedIds.size === 0) return;
    try {
      await interestAccrualApi.postAccruals(selectedCorporateId, Array.from(selectedIds));
      setSelectedIds(new Set());
      await loadData();
    } catch (err) {
      alert('Failed to post accruals');
    }
  };

  const handleSettleSelected = async () => {
    if (selectedIds.size === 0) return;
    try {
      await interestAccrualApi.settleAccruals(selectedCorporateId, Array.from(selectedIds));
      setSelectedIds(new Set());
      await loadData();
    } catch (err) {
      alert('Failed to settle accruals');
    }
  };

  const handleExport = async (format: 'CSV' | 'EXCEL' | 'PDF') => {
    try {
      const blob = await interestAccrualApi.exportReport(selectedCorporateId, dateFrom, dateTo, format);
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `interest-accruals-${dateFrom}-to-${dateTo}.${format.toLowerCase()}`;
      a.click();
    } catch (err) {
      alert('Export failed');
    }
  };

  const filteredAccruals = accruals.filter(a => {
    if (searchTerm) {
      const search = searchTerm.toLowerCase();
      if (!a.accrualReference.toLowerCase().includes(search) &&
          !a.entityName?.toLowerCase().includes(search) &&
          !a.virtualAccountNumber?.toLowerCase().includes(search)) return false;
    }
    return true;
  });

  if (loading && !accruals.length) {
    return <div className="flex items-center justify-center h-96"><Loader2 className="w-8 h-8 animate-spin text-primary-600 dark:text-primary-200" /></div>;
  }

  return (
    <Page>
      {/* Quick Actions */}
      <div className="flex items-center justify-end gap-2 animate-fade-in" style={{ animationDelay: '0.05s' }}>
        <Button variant="outline" leftIcon={running ? <Loader2 className="w-4 h-4 animate-spin" /> : <RefreshCw className="w-4 h-4" />} onClick={handleRunAccrual} disabled={running}>
          Run Accrual
        </Button>
      </div>

      {/* Corporate Selector */}
      <Card className="bg-gradient-to-r from-primary-50/50 via-white to-primary-50/50 border-primary-100/50 animate-fade-in dark:from-primary-900 dark:via-primary-900 dark:to-primary-900" style={{ animationDelay: '0.1s' }}>
        <div className="flex items-center gap-4 p-4">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-lg bg-primary-100 flex items-center justify-center dark:bg-primary-700">
              <Building2 className="w-4 h-4 text-primary-600 dark:text-primary-200" />
            </div>
            <div className="flex flex-col">
              <span className="text-xs text-neutral-500 uppercase tracking-wider dark:text-neutral-400">Corporate</span>
              <Select value={selectedCorporateId} onChange={(e) => setSelectedCorporateId(e.target.value)} className="min-w-[240px]">
                {corporates.map(corp => (
                  <option key={corp.id} value={corp.id}>{corp.legalName || corp.tradeName || corp.corporateId}</option>
                ))}
              </Select>
            </div>
          </div>
        </div>
      </Card>

      {error && (
        <Card className="bg-warning-50 border-warning-200 animate-fade-in dark:bg-warning-500/10 dark:border-warning-500/30">
          <div className="flex items-center justify-between p-4">
            <div className="flex items-center gap-3">
              <StatusIconBadge tone="warning" icon={AlertCircle} className="dark:bg-warning-500/20" />
              <span className="text-warning-700 font-medium dark:text-warning-300">{error}</span>
            </div>
            <button onClick={() => setError(null)} className="text-warning-500 hover:text-warning-700 p-1">×</button>
          </div>
        </Card>
      )}

      {summary && (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-5 gap-4 animate-fade-in" style={{ animationDelay: '0.15s' }}>
          <StatTile layout="row" tone="danger" valueTone="neutral" label="Loan Interest" value={formatCurrency(summary.totalLoanInterestAccrued, summary.currency)} sub={`${summary.loanAccrualCount} accruals`} icon={<CreditCard className="w-5 h-5" />} />
          <StatTile layout="row" tone="success" valueTone="neutral" label="Deposit Interest" value={formatCurrency(summary.totalDepositInterestAccrued, summary.currency)} sub={`${summary.depositAccrualCount} accruals`} icon={<PiggyBank className="w-5 h-5" />} />
          <StatTile layout="row" tone="success" valueTone="neutral" label="VA Credit" value={formatCurrency(summary.totalVaCreditInterest, summary.currency)} sub={`${summary.vaCreditAccrualCount} accruals`} icon={<TrendingUp className="w-5 h-5" />} />
          <StatTile layout="row" tone="danger" valueTone="neutral" label="VA Debit" value={formatCurrency(summary.totalVaDebitInterest, summary.currency)} sub={`${summary.vaDebitAccrualCount} accruals`} icon={<TrendingDown className="w-5 h-5" />} />
          <StatTile layout="row" tone={summary.netInterestIncome >= 0 ? 'success' : 'danger'} valueTone="neutral" label="Net Interest Income" value={formatCurrency(summary.netInterestIncome, summary.currency)} sub={`Spread: ${formatCurrency(summary.spreadEarned, summary.currency)}`} icon={<BarChart3 className="w-5 h-5" />} />
        </div>
      )}

      {/* Filters */}
      <Card className="p-4 animate-fade-in" style={{ animationDelay: '0.2s' }}>
        <div className="flex flex-wrap items-center gap-4">
          <div className="flex-1 min-w-[200px]">
            <div className="relative">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400 dark:text-neutral-500" />
              <Input placeholder="Search by reference, entity..." value={searchTerm} onChange={(e) => setSearchTerm(e.target.value)} className="pl-10" />
            </div>
          </div>
          <Select value={filterType} onChange={(e) => setFilterType(e.target.value as any)}>
            <option value="ALL">All Types</option>
            <option value="LOAN">IHB Loans</option>
            <option value="DEPOSIT">IHB Deposits</option>
            <option value="VA_CREDIT">VA Credit</option>
            <option value="VA_DEBIT">VA Debit</option>
          </Select>
          <Select value={filterStatus} onChange={(e) => setFilterStatus(e.target.value as any)}>
            <option value="ALL">All Statuses</option>
            <option value="ACCRUED">Accrued</option>
            <option value="POSTED">Posted</option>
            <option value="SETTLED">Settled</option>
          </Select>
          <Input type="date" value={dateFrom} onChange={(e) => setDateFrom(e.target.value)} className="w-40" />
          <span className="text-neutral-500 dark:text-neutral-400">to</span>
          <Input type="date" value={dateTo} onChange={(e) => setDateTo(e.target.value)} className="w-40" />
          <Button variant="outline" size="sm" onClick={() => handleExport('CSV')}><Download className="w-4 h-4 mr-1" /> CSV</Button>
          <Button variant="outline" size="sm" onClick={() => handleExport('EXCEL')}><Download className="w-4 h-4 mr-1" /> Excel</Button>
        </div>
      </Card>

      {selectedIds.size > 0 && (
        <Card padding="sm" className="bg-primary-50 border-primary-200 dark:bg-primary-800/40 dark:border-primary-700">
          <div className="flex items-center justify-between">
            <p className="text-sm text-primary-800 dark:text-neutral-100"><strong>{selectedIds.size}</strong> accruals selected</p>
            <div className="flex gap-2">
              <Button size="sm" variant="outline" onClick={handlePostSelected}><CheckCircle className="w-4 h-4 mr-1" /> Post</Button>
              <Button size="sm" onClick={handleSettleSelected}><DollarSign className="w-4 h-4 mr-1" /> Settle</Button>
            </div>
          </div>
        </Card>
      )}

      {summary && (
        <div className="flex gap-4 animate-fade-in" style={{ animationDelay: '0.25s' }}>
          <div className="flex items-center gap-2 px-3 py-1.5 bg-warning-50 rounded-full dark:bg-warning-500/10">
            <Clock className="w-4 h-4 text-warning-600 dark:text-warning-300" />
            <span className="text-sm text-warning-700 dark:text-warning-300">{summary.pendingAccruals} Pending</span>
          </div>
          <div className="flex items-center gap-2 px-3 py-1.5 bg-info-50 rounded-full dark:bg-info-500/10">
            <FileText className="w-4 h-4 text-info-600 dark:text-info-300" />
            <span className="text-sm text-info-700 dark:text-info-300">{summary.postedAccruals} Posted</span>
          </div>
          <div className="flex items-center gap-2 px-3 py-1.5 bg-success-50 rounded-full dark:bg-success-500/10">
            <CheckCircle className="w-4 h-4 text-success-600 dark:text-success-300" />
            <span className="text-sm text-success-700 dark:text-success-300">{summary.settledAccruals} Settled</span>
          </div>
        </div>
      )}

      <Card padding="none" className="animate-fade-in" style={{ animationDelay: '0.3s' }}>
        <DataTable
          data={filteredAccruals}
          keyExtractor={(accrual) => accrual.id}
          selectable
          selectedKeys={selectedIds}
          onSelectionChange={(keys) => setSelectedIds(keys as Set<string>)}
          emptyIcon={<Percent className="w-8 h-8 text-neutral-400 dark:text-neutral-500" />}
          emptyTitle="No interest accruals found"
          emptyDescription={accruals.length === 0 ? 'Run daily accrual to generate interest calculations' : 'Try adjusting your filters'}
          columns={[
            {
              key: 'accrualReference',
              header: 'Reference / Type',
              render: (_, accrual) => (
                <div className="flex items-center gap-2">
                  <AccrualTypeIcon type={accrual.accrualType} />
                  <div>
                    <p className="text-sm font-mono text-primary-900 dark:text-neutral-50">{accrual.accrualReference}</p>
                    <p className="text-xs text-neutral-500 dark:text-neutral-400">{getTypeLabel(accrual.accrualType)}</p>
                  </div>
                </div>
              ),
            },
            {
              key: 'entityName',
              header: 'Entity / Account',
              render: (_, accrual) => (
                <>
                  <p className="text-sm text-primary-900 dark:text-neutral-50">{accrual.entityName || '-'}</p>
                  <p className="text-xs text-neutral-500 dark:text-neutral-400">{accrual.virtualAccountNumber || accrual.loanReference || accrual.depositReference}</p>
                </>
              ),
            },
            {
              key: 'periodStart',
              header: 'Period',
              render: (_, accrual) => (
                <>
                  <p className="text-sm text-primary-900 dark:text-neutral-50">{formatDate(accrual.periodStart)}</p>
                  <p className="text-xs text-neutral-500 dark:text-neutral-400">to {formatDate(accrual.periodEnd)}</p>
                </>
              ),
            },
            { key: 'principalBalance', header: 'Principal', align: 'right', render: (_, accrual) => <span className="text-sm text-primary-900 dark:text-neutral-50">{formatCurrency(accrual.principalBalance, accrual.currency)}</span> },
            {
              key: 'interestRate',
              header: 'Rate',
              align: 'right',
              render: (_, accrual) => (
                <>
                  <p className="text-sm text-primary-900 dark:text-neutral-50">{accrual.interestRate.toFixed(3)}%</p>
                  <p className="text-xs text-neutral-500 dark:text-neutral-400">{accrual.dayCountConvention}</p>
                </>
              ),
            },
            { key: 'daysInPeriod', header: 'Days', align: 'right', render: (_, accrual) => <span className="text-sm text-neutral-500 dark:text-neutral-400">{accrual.daysInPeriod} days</span> },
            {
              key: 'accruedAmount',
              header: 'Accrued',
              align: 'right',
              render: (_, accrual) => {
                const isCredit = ['DEPOSIT', 'VA_CREDIT'].includes(accrual.accrualType);
                return (
                  <span className={cn('text-sm font-semibold', isCredit ? 'text-success-600 dark:text-success-300' : 'text-error-600 dark:text-error-300')}>
                    {isCredit ? '+' : '-'}{formatCurrency(accrual.accruedAmount, accrual.currency)}
                  </span>
                );
              },
            },
            { key: 'status', header: 'Status', align: 'center', render: (_, accrual) => <Badge variant={getStatusVariant(accrual.status)} size="sm">{accrual.status}</Badge> },
            {
              key: 'actions',
              header: 'Action',
              align: 'center',
              render: (_, accrual) => <Button variant="ghost" size="sm" onClick={() => { setSelectedAccrual(accrual); setShowDetailModal(true); }}><Eye className="w-4 h-4" /></Button>,
            },
          ]}
        />
      </Card>

      <Modal isOpen={showDetailModal} onClose={() => setShowDetailModal(false)} title="Accrual Details" size="lg">
        {selectedAccrual && (
          <div className="space-y-6">
            <div className="flex items-start justify-between">
              <div className="flex items-center gap-3">
                <div className={cn('w-12 h-12 rounded-xl flex items-center justify-center', ['DEPOSIT', 'VA_CREDIT'].includes(selectedAccrual.accrualType) ? 'bg-success-100 dark:bg-success-500/20' : 'bg-error-100 dark:bg-error-500/20')}>
                  <AccrualTypeIcon type={selectedAccrual.accrualType} />
                </div>
                <div>
                  <h3 className="section-title">{selectedAccrual.accrualReference}</h3>
                  <p className="text-sm text-neutral-500 dark:text-neutral-400">{getTypeLabel(selectedAccrual.accrualType)}</p>
                </div>
              </div>
              <Badge variant={getStatusVariant(selectedAccrual.status)} size="md">{selectedAccrual.status}</Badge>
            </div>

            <div className="grid grid-cols-2 gap-4">
              <div className="bg-neutral-50 rounded-xl p-4 dark:bg-primary-950">
                <h4 className="text-xs font-semibold text-neutral-600 mb-3 uppercase tracking-wider dark:text-neutral-300">Entity Information</h4>
                <div className="space-y-2 text-sm">
                  <div className="flex justify-between"><span className="text-neutral-500 dark:text-neutral-400">Entity</span><span className="font-medium">{selectedAccrual.entityName || '-'}</span></div>
                  {selectedAccrual.virtualAccountNumber && <div className="flex justify-between"><span className="text-neutral-500 dark:text-neutral-400">VA Number</span><span className="font-mono">{selectedAccrual.virtualAccountNumber}</span></div>}
                  {selectedAccrual.loanReference && <div className="flex justify-between"><span className="text-neutral-500 dark:text-neutral-400">Loan Ref</span><span className="font-mono">{selectedAccrual.loanReference}</span></div>}
                </div>
              </div>
              <div className="bg-neutral-50 rounded-xl p-4 dark:bg-primary-950">
                <h4 className="text-xs font-semibold text-neutral-600 mb-3 uppercase tracking-wider dark:text-neutral-300">Period Details</h4>
                <div className="space-y-2 text-sm">
                  <div className="flex justify-between"><span className="text-neutral-500 dark:text-neutral-400">Period Start</span><span className="font-medium">{formatDate(selectedAccrual.periodStart)}</span></div>
                  <div className="flex justify-between"><span className="text-neutral-500 dark:text-neutral-400">Period End</span><span className="font-medium">{formatDate(selectedAccrual.periodEnd)}</span></div>
                  <div className="flex justify-between"><span className="text-neutral-500 dark:text-neutral-400">Days</span><span className="font-medium">{selectedAccrual.daysInPeriod}</span></div>
                </div>
              </div>
            </div>

            <div className="bg-primary-50 rounded-xl p-4 dark:bg-primary-800/40">
              <h4 className="text-xs font-semibold text-primary-700 mb-3 uppercase tracking-wider dark:text-neutral-200">Interest Calculation</h4>
              <div className="grid grid-cols-3 gap-4 text-sm">
                <div><p className="text-neutral-600 text-xs uppercase tracking-wider dark:text-neutral-300">Principal Balance</p><p className="text-lg font-bold text-primary-900 mt-1 dark:text-neutral-50">{formatCurrency(selectedAccrual.principalBalance, selectedAccrual.currency)}</p></div>
                <div><p className="text-neutral-600 text-xs uppercase tracking-wider dark:text-neutral-300">Interest Rate</p><p className="text-lg font-bold text-primary-900 mt-1 dark:text-neutral-50">{selectedAccrual.interestRate.toFixed(3)}% p.a.</p></div>
                <div><p className="text-neutral-600 text-xs uppercase tracking-wider dark:text-neutral-300">Accrued Interest</p><p className={cn('text-lg font-bold mt-1', ['DEPOSIT', 'VA_CREDIT'].includes(selectedAccrual.accrualType) ? 'text-success-600 dark:text-success-300' : 'text-error-600 dark:text-error-300')}>{formatCurrency(selectedAccrual.accruedAmount, selectedAccrual.currency)}</p></div>
              </div>
            </div>

            <div className="flex gap-3 pt-4 border-t">
              <Button variant="outline" className="flex-1" onClick={() => setShowDetailModal(false)}>Close</Button>
              {selectedAccrual.status === 'ACCRUED' && <Button className="flex-1" onClick={() => { setSelectedIds(new Set([selectedAccrual.id])); handlePostSelected(); setShowDetailModal(false); }}><CheckCircle className="w-4 h-4 mr-1" /> Post</Button>}
              {selectedAccrual.status === 'POSTED' && <Button className="flex-1" onClick={() => { setSelectedIds(new Set([selectedAccrual.id])); handleSettleSelected(); setShowDetailModal(false); }}><DollarSign className="w-4 h-4 mr-1" /> Settle</Button>}
            </div>
          </div>
        )}
      </Modal>
    </Page>
  );
};

export default InterestAccrualReportsPage;