// ============================================================================
// OPERATION HISTORY PAGE
// ============================================================================
// Page for viewing and filtering hierarchy operation history
// Features: Filtering, pagination, detail view, export
// ============================================================================

import React, { useState, useEffect, useCallback } from 'react';
import {
  Package, Folder, Building2, GitMerge, GitBranch, Globe, Clock,
  Loader2, RefreshCw, Eye, Search, Download, X,
  Calendar, CheckCircle, XCircle, Settings,
  ArrowLeft,
} from 'lucide-react';
import { cn } from '../utils';
import { PageHeader } from '../components/layout/PageHeader';
import { Modal } from '../components/ui/enhanced';
import { DataTable } from '../components/ui';
import {
  hierarchyOperationsApi, OperationHistoryEntry, OperationType, CorporateSummary,
} from '../services/hierarchyOperationsApi';

// ============================================================================
// TYPES
// ============================================================================

type OperationStatus = 'COMPLETED' | 'PENDING' | 'FAILED' | 'ROLLED_BACK';

interface FilterState {
  operationType: OperationType | 'ALL';
  status: OperationStatus | 'ALL';
  dateFrom: string;
  dateTo: string;
  searchQuery: string;
}

// ============================================================================
// HELPER COMPONENTS
// ============================================================================

const Badge: React.FC<{ variant?: 'default' | 'success' | 'warning' | 'error' | 'info' | 'purple' | 'orange'; children: React.ReactNode; size?: 'sm' | 'md' }> = ({ variant = 'default', children, size = 'sm' }) => {
  const variants = {
    default: 'bg-neutral-100 text-neutral-700 dark:bg-primary-800 dark:text-neutral-200', success: 'bg-success-100 text-success-700 dark:bg-success-500/20 dark:text-success-300',
    warning: 'bg-warning-100 text-warning-700 dark:bg-warning-500/20 dark:text-warning-300', error: 'bg-error-100 text-error-700 dark:bg-error-500/20 dark:text-error-300',
    info: 'bg-info-100 text-info-700 dark:bg-info-500/20 dark:text-info-300', purple: 'bg-cat-2/10 text-cat-2 dark:bg-cat-2/15',
    orange: 'bg-warning-100 text-warning-700 dark:bg-warning-500/20 dark:text-warning-300',
  };
  const sizes = { sm: 'px-2 py-0.5 text-xs', md: 'px-3 py-1 text-sm' };
  return <span className={cn('font-medium rounded-full', variants[variant], sizes[size])}>{children}</span>;
};

const Card: React.FC<{ children: React.ReactNode; className?: string; padding?: 'none' | 'sm' | 'md' }> = ({ children, className, padding = 'md' }) => {
  const paddings = { none: '', sm: 'p-4', md: 'p-6' };
  return <div className={cn('bg-white rounded-xl shadow-sm border border-neutral-200 dark:bg-primary-900 dark:border-primary-800', paddings[padding], className)}>{children}</div>;
};

const Button: React.FC<{ children: React.ReactNode; variant?: 'primary' | 'secondary' | 'ghost' | 'outline'; size?: 'sm' | 'md'; onClick?: () => void; disabled?: boolean; className?: string }> = ({ children, variant = 'primary', size = 'md', onClick, disabled, className }) => {
  const variants = {
    primary: 'bg-primary-600 text-white hover:bg-primary-700 disabled:bg-neutral-300',
    secondary: 'bg-neutral-100 text-neutral-700 hover:bg-neutral-200 dark:bg-primary-800 dark:text-neutral-200',
    ghost: 'text-neutral-600 hover:bg-neutral-100 dark:text-neutral-300',
    outline: 'border border-neutral-300 text-neutral-700 hover:bg-neutral-50 dark:border-primary-700 dark:text-neutral-200',
  };
  const sizes = { sm: 'px-3 py-1.5 text-xs', md: 'px-4 py-2 text-sm' };
  return <button onClick={onClick} disabled={disabled} className={cn('rounded-lg font-medium transition-colors flex items-center gap-2 disabled:cursor-not-allowed', variants[variant], sizes[size], className)}>{children}</button>;
};

// ============================================================================
// OPERATION ICON
// ============================================================================

const getOperationIcon = (type: OperationType) => {
  switch (type) {
    case 'MOVE_TRANSACTION_VA': return <Package className="w-5 h-5 text-info-600 dark:text-info-300" />;
    case 'MOVE_AGGREGATION': return <Folder className="w-5 h-5 text-cat-1" />;
    case 'ACQUISITION': return <Building2 className="w-5 h-5 text-success-600 dark:text-success-300" />;
    case 'MERGER': return <GitMerge className="w-5 h-5 text-cat-2" />;
    case 'DIVESTITURE': return <GitBranch className="w-5 h-5 text-warning-600 dark:text-warning-300" />;
    case 'HIERARCHY_INIT': return <Globe className="w-5 h-5 text-primary-600 dark:text-primary-200" />;
    default: return <Settings className="w-5 h-5 text-neutral-600 dark:text-neutral-300" />;
  }
};

const getStatusBadge = (status: OperationStatus) => {
  switch (status) {
    case 'COMPLETED': return <Badge variant="success"><CheckCircle className="w-3 h-3 mr-1 inline" />Completed</Badge>;
    case 'PENDING': return <Badge variant="warning"><Clock className="w-3 h-3 mr-1 inline" />Pending</Badge>;
    case 'FAILED': return <Badge variant="error"><XCircle className="w-3 h-3 mr-1 inline" />Failed</Badge>;
    case 'ROLLED_BACK': return <Badge variant="default"><RefreshCw className="w-3 h-3 mr-1 inline" />Rolled Back</Badge>;
  }
};

// ============================================================================
// OPERATION DETAIL MODAL
// ============================================================================

interface OperationDetailModalProps {
  operation: OperationHistoryEntry | null;
  onClose: () => void;
}

const OperationDetailModal: React.FC<OperationDetailModalProps> = ({ operation, onClose }) => {
  if (!operation) return null;

  // Phase 10 follow-up (2026-05-13): hand-rolled wrapper → <Modal size="md">.
  return (
    <Modal
      isOpen={!!operation}
      onClose={onClose}
      size="md"
      title={
        <div className="flex items-center gap-3">
          <div className="p-2 bg-neutral-100 rounded-lg dark:bg-primary-800">{getOperationIcon(operation.operationType)}</div>
          <span>{operation.operationType.replace(/_/g, ' ')}</span>
        </div>
      }
      subtitle="Operation Details"
      footer={<Button variant="secondary" onClick={onClose}>Close</Button>}
    >
      <div className="space-y-4">
            <div className="flex justify-between items-center">
              <span className="text-sm text-neutral-500 dark:text-neutral-400">Status</span>
              {getStatusBadge(operation.status)}
            </div>

            <div>
              <span className="text-sm text-neutral-500 dark:text-neutral-400">Summary</span>
              <p className="font-medium text-primary-900 mt-1 dark:text-neutral-50">{operation.summary}</p>
            </div>

            <div className="grid grid-cols-2 gap-4">
              <div>
                <span className="text-sm text-neutral-500 dark:text-neutral-400">Performed By</span>
                <p className="font-medium text-primary-900 mt-1 dark:text-neutral-50">{operation.performedBy}</p>
              </div>
              <div>
                <span className="text-sm text-neutral-500 dark:text-neutral-400">Date</span>
                <p className="font-medium text-primary-900 mt-1 dark:text-neutral-50">{new Date(operation.createdAt).toLocaleDateString()}</p>
              </div>
            </div>

            <div>
              <span className="text-sm text-neutral-500 dark:text-neutral-400">Time</span>
              <p className="font-medium text-primary-900 mt-1 dark:text-neutral-50">{new Date(operation.createdAt).toLocaleTimeString()}</p>
            </div>

            {operation.details && (
              <div className="p-4 bg-neutral-50 rounded-lg dark:bg-primary-950">
                <span className="field-label">Additional Details</span>
                <dl className="mt-2 space-y-2 text-sm">
                  {operation.details.sourceNode && (
                    <div className="flex justify-between"><dt className="text-neutral-500 dark:text-neutral-400">Source:</dt><dd className="font-medium">{operation.details.sourceNode}</dd></div>
                  )}
                  {operation.details.targetNode && (
                    <div className="flex justify-between"><dt className="text-neutral-500 dark:text-neutral-400">Target:</dt><dd className="font-medium">{operation.details.targetNode}</dd></div>
                  )}
                  {operation.details.vaCount !== undefined && (
                    <div className="flex justify-between"><dt className="text-neutral-500 dark:text-neutral-400">VAs Affected:</dt><dd className="font-medium">{operation.details.vaCount}</dd></div>
                  )}
                  {operation.details.limitTransferred !== undefined && (
                    <div className="flex justify-between"><dt className="text-neutral-500 dark:text-neutral-400">Limit Transferred:</dt><dd className="font-medium">{operation.details.limitTransferred.toLocaleString()}</dd></div>
                  )}
                  {operation.details.policy && (
                    <div className="flex justify-between"><dt className="text-neutral-500 dark:text-neutral-400">Policy:</dt><dd className="font-medium">{operation.details.policy}</dd></div>
                  )}
                </dl>
              </div>
            )}
      </div>
    </Modal>
  );
};

// ============================================================================
// FILTER PANEL
// ============================================================================

interface FilterPanelProps {
  filters: FilterState;
  onChange: (filters: FilterState) => void;
  onReset: () => void;
}

const FilterPanel: React.FC<FilterPanelProps> = ({ filters, onChange, onReset }) => {
  const operationTypes: (OperationType | 'ALL')[] = [
    'ALL', 'MOVE_TRANSACTION_VA', 'MOVE_AGGREGATION', 'ACQUISITION', 'MERGER', 'DIVESTITURE', 'HIERARCHY_INIT',
  ];

  const statuses: (OperationStatus | 'ALL')[] = ['ALL', 'COMPLETED', 'PENDING', 'FAILED', 'ROLLED_BACK'];

  return (
    <Card padding="sm" className="mb-6">
      <div className="flex flex-wrap items-end gap-4">
        <div className="flex-1 min-w-[200px]">
          <label className="block text-xs font-medium text-neutral-500 mb-1 dark:text-neutral-400">Search</label>
          <div className="relative">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400 dark:text-neutral-500" />
            <input
              type="text"
              value={filters.searchQuery}
              onChange={(e) => onChange({ ...filters, searchQuery: e.target.value })}
              placeholder="Search operations..."
              className="w-full pl-10 pr-4 py-2 border border-neutral-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-primary-500 dark:border-primary-700"
            />
          </div>
        </div>

        <div className="w-[180px]">
          <label className="block text-xs font-medium text-neutral-500 mb-1 dark:text-neutral-400">Operation Type</label>
          <select
            value={filters.operationType}
            onChange={(e) => onChange({ ...filters, operationType: e.target.value as OperationType | 'ALL' })}
            className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-primary-500 dark:border-primary-700"
          >
            {operationTypes.map((type) => (
              <option key={type} value={type}>{type === 'ALL' ? 'All Types' : type.replace(/_/g, ' ')}</option>
            ))}
          </select>
        </div>

        <div className="w-[140px]">
          <label className="block text-xs font-medium text-neutral-500 mb-1 dark:text-neutral-400">Status</label>
          <select
            value={filters.status}
            onChange={(e) => onChange({ ...filters, status: e.target.value as OperationStatus | 'ALL' })}
            className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-primary-500 dark:border-primary-700"
          >
            {statuses.map((status) => (
              <option key={status} value={status}>{status === 'ALL' ? 'All Statuses' : status}</option>
            ))}
          </select>
        </div>

        <div className="w-[140px]">
          <label className="block text-xs font-medium text-neutral-500 mb-1 dark:text-neutral-400">From Date</label>
          <input
            type="date"
            value={filters.dateFrom}
            onChange={(e) => onChange({ ...filters, dateFrom: e.target.value })}
            className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-primary-500 dark:border-primary-700"
          />
        </div>

        <div className="w-[140px]">
          <label className="block text-xs font-medium text-neutral-500 mb-1 dark:text-neutral-400">To Date</label>
          <input
            type="date"
            value={filters.dateTo}
            onChange={(e) => onChange({ ...filters, dateTo: e.target.value })}
            className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-primary-500 dark:border-primary-700"
          />
        </div>

        <Button variant="ghost" onClick={onReset}><X className="w-4 h-4" />Reset</Button>
      </div>
    </Card>
  );
};

// ============================================================================
// MAIN COMPONENT
// ============================================================================

interface OperationHistoryPageProps {
  corporateId?: string;
  onBack?: () => void;
}

const OperationHistoryPage: React.FC<OperationHistoryPageProps> = ({ corporateId, onBack }) => {
  const [corporates, setCorporates] = useState<CorporateSummary[]>([]);
  const [selectedCorporateId, setSelectedCorporateId] = useState<string>(corporateId || '');
  const [operations, setOperations] = useState<OperationHistoryEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [selectedOperation, setSelectedOperation] = useState<OperationHistoryEntry | null>(null);

  const [filters, setFilters] = useState<FilterState>({
    operationType: 'ALL', status: 'ALL', dateFrom: '', dateTo: '', searchQuery: '',
  });

  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const pageSize = 10;

  const loadCorporates = useCallback(async () => {
    try {
      const res = await hierarchyOperationsApi.getCorporates();
      if (res.success && res.data) {
        setCorporates(res.data);
        if (!selectedCorporateId && res.data.length > 0) {
          setSelectedCorporateId(res.data[0].id);
        }
      }
    } catch (err) { console.error('Failed to load corporates:', err); }
  }, [selectedCorporateId]);

  const loadOperations = useCallback(async () => {
    if (!selectedCorporateId) return;
    setLoading(true);
    try {
      const res = await hierarchyOperationsApi.getOperationHistory(selectedCorporateId, page, pageSize);
      if (res.success && res.data) {
        let filtered = res.data.content;

        // Apply client-side filters
        if (filters.operationType !== 'ALL') {
          filtered = filtered.filter((op) => op.operationType === filters.operationType);
        }
        if (filters.status !== 'ALL') {
          filtered = filtered.filter((op) => op.status === filters.status);
        }
        if (filters.searchQuery) {
          const query = filters.searchQuery.toLowerCase();
          filtered = filtered.filter((op) =>
            op.summary.toLowerCase().includes(query) ||
            op.performedBy.toLowerCase().includes(query) ||
            op.operationType.toLowerCase().includes(query)
          );
        }
        if (filters.dateFrom) {
          filtered = filtered.filter((op) => new Date(op.createdAt) >= new Date(filters.dateFrom));
        }
        if (filters.dateTo) {
          filtered = filtered.filter((op) => new Date(op.createdAt) <= new Date(filters.dateTo + 'T23:59:59'));
        }

        setOperations(filtered);
        setTotalPages(res.data.totalPages);
      }
    } catch (err) { console.error('Failed to load operations:', err); }
    finally { setLoading(false); }
  }, [selectedCorporateId, page, filters]);

  useEffect(() => { loadCorporates(); }, [loadCorporates]);
  useEffect(() => { loadOperations(); }, [loadOperations]);

  const handleRefresh = async () => {
    setRefreshing(true);
    await loadOperations();
    setRefreshing(false);
  };

  const handleExport = () => {
    const csv = [
      ['Operation Type', 'Status', 'Summary', 'Performed By', 'Date'].join(','),
      ...operations.map((op) => [
        op.operationType, op.status, `"${op.summary}"`, op.performedBy, new Date(op.createdAt).toISOString(),
      ].join(',')),
    ].join('\n');

    const blob = new Blob([csv], { type: 'text/csv' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `operation-history-${selectedCorporateId}-${new Date().toISOString().split('T')[0]}.csv`;
    a.click();
  };

  const resetFilters = () => {
    setFilters({ operationType: 'ALL', status: 'ALL', dateFrom: '', dateTo: '', searchQuery: '' });
  };

  const selectedCorporate = corporates.find((c) => c.id === selectedCorporateId);

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div className="flex items-center gap-4">
          {onBack && (
            <button onClick={onBack} className="p-2 hover:bg-neutral-100 rounded-lg dark:hover:bg-primary-800">
              <ArrowLeft className="w-5 h-5 text-neutral-500 dark:text-neutral-400" />
            </button>
          )}
          <PageHeader title="Operation History" description="View and filter hierarchy operation history" />
        </div>

        <div className="flex items-center gap-3">
          <select
            value={selectedCorporateId}
            onChange={(e) => { setSelectedCorporateId(e.target.value); setPage(0); }}
            className="px-4 py-2 border border-neutral-300 rounded-lg text-sm bg-white focus:outline-none focus:ring-2 focus:ring-primary-500 dark:border-primary-700 dark:bg-primary-900"
          >
            {corporates.map((corp) => (<option key={corp.id} value={corp.id}>{corp.name}</option>))}
          </select>

          <Button variant="outline" onClick={handleRefresh} disabled={refreshing}>
            <RefreshCw className={cn('w-4 h-4', refreshing && 'animate-spin')} />Refresh
          </Button>

          <Button variant="outline" onClick={handleExport}>
            <Download className="w-4 h-4" />Export
          </Button>
        </div>
      </div>

      {/* Filters */}
      <FilterPanel filters={filters} onChange={setFilters} onReset={resetFilters} />

      {/* Operations Table */}
      <Card padding="none">
        {loading ? (
          <div className="flex items-center justify-center py-12">
            <Loader2 className="w-8 h-8 animate-spin text-primary-600 dark:text-primary-200" />
          </div>
        ) : operations.length === 0 ? (
          <div className="text-center py-12">
            <Calendar className="w-12 h-12 text-neutral-300 mx-auto mb-3 dark:text-neutral-600" />
            <p className="text-neutral-500 dark:text-neutral-400">No operations found</p>
            <p className="text-sm text-neutral-400 mt-1 dark:text-neutral-500">Try adjusting your filters</p>
          </div>
        ) : (
          <DataTable
            data={operations}
            keyExtractor={(op) => op.id}
            pagination
            pageSize={pageSize}
            currentPage={page + 1}
            totalCount={totalPages * pageSize}
            onPageChange={(p) => setPage(p - 1)}
            columns={[
              {
                key: 'operationType',
                header: 'Operation',
                render: (_, op) => (
                  <div className="flex items-center gap-3">
                    <div className="p-2 bg-neutral-100 rounded-lg dark:bg-primary-800">{getOperationIcon(op.operationType)}</div>
                    <div>
                      <p className="font-medium text-primary-900 dark:text-neutral-50">{op.operationType.replace(/_/g, ' ')}</p>
                      <p className="text-sm text-neutral-500 truncate max-w-[300px] dark:text-neutral-400">{op.summary}</p>
                    </div>
                  </div>
                ),
              },
              { key: 'status', header: 'Status', render: (_, op) => getStatusBadge(op.status) },
              { key: 'performedBy', header: 'Performed By', render: (_, op) => <span className="text-sm text-neutral-600 dark:text-neutral-300">{op.performedBy}</span> },
              {
                key: 'createdAt',
                header: 'Date',
                render: (_, op) => (
                  <div className="text-sm text-neutral-600 dark:text-neutral-300">
                    <div>{new Date(op.createdAt).toLocaleDateString()}</div>
                    <div className="text-xs text-neutral-400 dark:text-neutral-500">{new Date(op.createdAt).toLocaleTimeString()}</div>
                  </div>
                ),
              },
              {
                key: 'actions',
                header: 'Actions',
                render: (_, op) => <Button variant="ghost" size="sm" onClick={() => setSelectedOperation(op)}><Eye className="w-4 h-4" /></Button>,
              },
            ]}
          />
        )}
      </Card>

      {/* Detail Modal */}
      <OperationDetailModal operation={selectedOperation} onClose={() => setSelectedOperation(null)} />
    </div>
  );
};

export default OperationHistoryPage;