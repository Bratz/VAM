import React, { useCallback, useEffect, useState } from 'react';
import {
  Shield,
  Plus,
  Search,
  Filter,
  AlertTriangle,
  CheckCircle,
  Clock,
  XCircle,
  DollarSign,
  Calendar,
  MoreHorizontal,
  Eye,
  Play,
  Flag,
  Download,
  Loader2,
  RefreshCw,
} from 'lucide-react';
import toast from 'react-hot-toast';
import { Card, Button, Badge, Input, Select, StatusIconBadge, DataTable, Skeleton } from '../components/ui';
import { Modal, Tabs, ProgressBar, Alert } from '../components/ui/enhanced';
import { formatCurrency, formatDate, cn } from '../utils';
import { Page } from '../components/layout/Page';
import { escrowApi, programsApi, physicalAccountsApi } from '../services/api';
import type { EscrowContract, EscrowStats, EscrowStatus } from '../services/api';

// Contract and stats shapes come from the API client, which mirrors the backend
// EscrowService DTOs. There is no local copy of either, and no mock data: an
// empty escrow_contracts table renders as an empty table.

// Status configurations - one entry per EscrowContract.EscrowStatus on the backend
const statusConfig: Record<EscrowStatus, { label: string; color: string; icon: React.ReactNode }> = {
  PENDING_FUNDING: { label: 'Awaiting Funds', color: 'warning', icon: <Clock className="w-4 h-4" /> },
  PARTIALLY_FUNDED: { label: 'Partly Funded', color: 'warning', icon: <Clock className="w-4 h-4" /> },
  FUNDED: { label: 'Funded', color: 'info', icon: <DollarSign className="w-4 h-4" /> },
  PARTIALLY_RELEASED: { label: 'Releasing', color: 'info', icon: <Play className="w-4 h-4" /> },
  RELEASED: { label: 'Released', color: 'success', icon: <CheckCircle className="w-4 h-4" /> },
  DISPUTED: { label: 'Disputed', color: 'error', icon: <AlertTriangle className="w-4 h-4" /> },
  CANCELLED: { label: 'Cancelled', color: 'neutral', icon: <XCircle className="w-4 h-4" /> },
  EXPIRED: { label: 'Expired', color: 'neutral', icon: <XCircle className="w-4 h-4" /> },
};

const ACTIVE_STATUSES: EscrowStatus[] = ['PARTIALLY_FUNDED', 'FUNDED', 'PARTIALLY_RELEASED'];

const TYPE_LABELS: Record<string, string> = {
  TRADE: 'Trade Escrow',
  REAL_ESTATE: 'Real Estate Escrow',
  M_AND_A: 'M&A Escrow',
  MILESTONE: 'Milestone Escrow',
  RENT: 'Rent Escrow',
};

type EscrowTypeOption = { code: string; name: string; description: string };
type ProgramOption = { id: string; programName: string; programCode: string; corporateId: string; currencyCode: string };
type AccountOption = { id: string; accountNumber: string; accountName: string; bankName?: string; corporateId: string };

interface CreateForm {
  programId: string;
  physicalAccountId: string;
  escrowType: string;
  buyerName: string;
  sellerName: string;
  contractAmount: string;
  currencyCode: string;
  expiryDate: string;
  releaseConditions: string;
}

const EMPTY_FORM: CreateForm = {
  programId: '',
  physicalAccountId: '',
  escrowType: 'TRADE',
  buyerName: '',
  sellerName: '',
  contractAmount: '',
  currencyCode: '',
  expiryDate: '',
  releaseConditions: '',
};

/** Everything the backend rejects, checked before we send it. */
function validate(form: CreateForm): Partial<Record<keyof CreateForm, string>> {
  const errors: Partial<Record<keyof CreateForm, string>> = {};
  if (!form.programId) errors.programId = 'Pick the program the escrow account belongs to';
  if (!form.physicalAccountId) errors.physicalAccountId = 'Pick the account that will hold the funds';
  if (!form.escrowType) errors.escrowType = 'Pick an escrow type';
  if (!form.buyerName.trim()) errors.buyerName = 'Buyer is required';
  if (!form.sellerName.trim()) errors.sellerName = 'Seller is required';
  const amount = Number(form.contractAmount);
  if (!form.contractAmount.trim()) errors.contractAmount = 'Contract amount is required';
  else if (!Number.isFinite(amount) || amount <= 0) errors.contractAmount = 'Enter an amount greater than zero';
  if (!form.currencyCode) errors.currencyCode = 'Currency is required';
  if (form.expiryDate && form.expiryDate < new Date().toISOString().slice(0, 10)) {
    errors.expiryDate = 'Expiry cannot be in the past';
  }
  return errors;
}

// Stats Card
const StatCard: React.FC<{
  label: string;
  value: string | number;
  icon: React.ReactNode;
  color: string;
  trend?: { value: number; positive: boolean };
}> = ({ label, value, icon, color, trend }) => (
  <Card hover>
    <div className="p-4">
      <div className="flex items-start justify-between">
        <div className="flex-1">
          <p className="label">{label}</p>
          <p className="stat-value-sm mt-1">{value}</p>
          {trend && (
            <p className={cn(
              'text-caption mt-1',
              trend.positive ? 'text-success-600 dark:text-success-300' : 'text-error-600 dark:text-error-300'
            )}>
              {trend.positive ? '↑' : '↓'} {trend.value}% vs last month
            </p>
          )}
        </div>
        <div className={cn('w-10 h-10 rounded-lg flex items-center justify-center', color)}>
          {icon}
        </div>
      </div>
    </div>
  </Card>
);

// Main Page Component
const EscrowPage: React.FC = () => {
  const [searchQuery, setSearchQuery] = useState('');
  const [activeTab, setActiveTab] = useState('all');
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [, setSelectedContract] = useState<EscrowContract | null>(null);

  const [contracts, setContracts] = useState<EscrowContract[]>([]);
  const [stats, setStats] = useState<EscrowStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Create form. The escrow VA is opened under a program's corporate on a
  // physical account, so both have to be picked before anything can be created.
  const [types, setTypes] = useState<EscrowTypeOption[]>([]);
  const [programs, setPrograms] = useState<ProgramOption[]>([]);
  const [accounts, setAccounts] = useState<AccountOption[]>([]);
  const [form, setForm] = useState<CreateForm>(EMPTY_FORM);
  const [formErrors, setFormErrors] = useState<Partial<Record<keyof CreateForm, string>>>({});
  const [submitting, setSubmitting] = useState(false);

  const loadData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [listRes, statsRes] = await Promise.all([escrowApi.getAll(), escrowApi.getStats()]);
      setContracts(listRes.data ?? []);
      setStats(statsRes.data ?? null);
    } catch (err: any) {
      setError(err?.response?.data?.message || err?.message || 'Could not load escrow contracts');
      setContracts([]);
      setStats(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void loadData(); }, [loadData]);

  // Reference data for the create form, fetched once the modal is first opened.
  useEffect(() => {
    if (!showCreateModal || types.length > 0) return;
    void (async () => {
      try {
        const [typesRes, programsRes, accountsRes] = await Promise.all([
          escrowApi.getTypes(),
          programsApi.getAll({ size: 200 }),
          physicalAccountsApi.getAll(0, 200),
        ]);
        setTypes(typesRes.data ?? []);
        setPrograms((programsRes.data?.programs ?? []) as ProgramOption[]);
        setAccounts(((accountsRes.data as any)?.content ?? accountsRes.data ?? []) as AccountOption[]);
      } catch {
        toast.error('Could not load programs and accounts for the form');
      }
    })();
  }, [showCreateModal, types.length]);

  const selectedProgram = programs.find(p => p.id === form.programId);
  // The escrow VA is opened under the program's corporate, so only that
  // corporate's accounts can hold the funds.
  const eligibleAccounts = selectedProgram
    ? accounts.filter(a => a.corporateId === selectedProgram.corporateId)
    : accounts;

  const setField = <K extends keyof CreateForm>(key: K, value: CreateForm[K]) => {
    setForm(prev => ({ ...prev, [key]: value }));
    setFormErrors(prev => ({ ...prev, [key]: undefined }));
  };

  const closeCreateModal = () => {
    setShowCreateModal(false);
    setForm(EMPTY_FORM);
    setFormErrors({});
  };

  const handleCreate = async () => {
    const errors = validate(form);
    setFormErrors(errors);
    if (Object.keys(errors).length > 0) return;

    setSubmitting(true);
    try {
      const res = await escrowApi.create({
        corporateId: selectedProgram?.corporateId,
        programId: form.programId,
        physicalAccountId: form.physicalAccountId,
        escrowType: form.escrowType,
        buyerName: form.buyerName.trim(),
        sellerName: form.sellerName.trim(),
        contractAmount: Number(form.contractAmount),
        currencyCode: form.currencyCode,
        expiryDate: form.expiryDate || undefined,
        releaseConditions: form.releaseConditions
          .split('\n')
          .map(c => c.trim())
          .filter(Boolean),
      });
      if (res.success) {
        toast.success(`Created ${res.data?.escrowReference ?? 'escrow contract'}`);
        closeCreateModal();
        await loadData();
      } else {
        toast.error(res.message || 'Could not create the escrow contract');
      }
    } catch (err: any) {
      toast.error(err?.response?.data?.message || err?.message || 'Could not create the escrow contract');
    } finally {
      setSubmitting(false);
    }
  };

  const isActive = (c: EscrowContract) => ACTIVE_STATUSES.includes(c.status);
  const typeLabel = (c: EscrowContract) => TYPE_LABELS[c.escrowType] ?? c.escrowType;

  const tabs = [
    { id: 'all', label: 'All Contracts', badge: contracts.length },
    { id: 'active', label: 'Active', badge: contracts.filter(isActive).length },
    { id: 'disputed', label: 'Disputed', badge: contracts.filter(c => c.status === 'DISPUTED').length },
    { id: 'released', label: 'Released', badge: contracts.filter(c => c.status === 'RELEASED').length },
  ];

  const filteredContracts = contracts.filter(contract => {
    const q = searchQuery.toLowerCase();
    const matchesSearch = !q
      || contract.escrowReference.toLowerCase().includes(q)
      || typeLabel(contract).toLowerCase().includes(q)
      || (contract.buyerName ?? '').toLowerCase().includes(q)
      || (contract.sellerName ?? '').toLowerCase().includes(q);
    const matchesTab = activeTab === 'all'
      || (activeTab === 'active' && isActive(contract))
      || (activeTab === 'disputed' && contract.status === 'DISPUTED')
      || (activeTab === 'released' && contract.status === 'RELEASED');
    return matchesSearch && matchesTab;
  });

  // Money figures arrive already converted to the market reporting currency.
  const reporting = stats?.reportingCurrency;
  const money = (v: number | undefined) => formatCurrency(v ?? 0, reporting);

  return (
    <Page>
      {/* Quick Actions */}
      <div className="flex items-center justify-end gap-2 animate-fade-in" style={{ animationDelay: '0.05s' }}>
        <Button
          variant="outline"
          leftIcon={<RefreshCw className={cn('w-4 h-4', loading && 'animate-spin')} />}
          onClick={() => void loadData()}
          disabled={loading}
        >
          Refresh
        </Button>
        <Button leftIcon={<Plus className="w-4 h-4" />} onClick={() => setShowCreateModal(true)}>
          New Contract
        </Button>
      </div>

      {error && (
        <Alert variant="error" title="Escrow data unavailable">{error}</Alert>
      )}

      {stats && stats.excludedCurrencies.length > 0 && (
        <Alert variant="warning" title="Some currencies are not included">
          No exchange rate to {stats.reportingCurrency} for {stats.excludedCurrencies.join(', ')}, so
          contracts held in {stats.excludedCurrencies.length > 1 ? 'those currencies' : 'that currency'} are
          left out of the totals below.
        </Alert>
      )}

      {/* Stats */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4 animate-fade-in" style={{ animationDelay: '0.15s' }}>
        {loading && !stats ? (
          <>
            <Skeleton className="h-24" />
            <Skeleton className="h-24" />
            <Skeleton className="h-24" />
            <Skeleton className="h-24" />
          </>
        ) : (
          <>
            <StatCard
              label={reporting ? `Total Contract Value (${reporting})` : 'Total Contract Value'}
              value={money(stats?.totalValue)}
              icon={<DollarSign className="w-5 h-5 text-primary-600 dark:text-primary-200" />}
              color="bg-primary-100 dark:bg-primary-700"
            />
            <StatCard
              label="Active Contracts"
              value={stats?.activeEscrows ?? 0}
              icon={<Play className="w-5 h-5 text-success-600 dark:text-success-300" />}
              color="bg-success-100 dark:bg-success-500/20"
            />
            <StatCard
              label="Under Dispute"
              value={stats?.disputed ?? 0}
              icon={<AlertTriangle className="w-5 h-5 text-error-600 dark:text-error-300" />}
              color="bg-error-100 dark:bg-error-500/20"
            />
            <StatCard
              label={reporting ? `Escrow Balance (${reporting})` : 'Escrow Balance'}
              value={money(stats?.escrowBalance)}
              icon={<Shield className="w-5 h-5 text-info-600 dark:text-info-300" />}
              color="bg-info-100 dark:bg-info-500/20"
            />
          </>
        )}
      </div>

      {/* Disputed Alert */}
      {(stats?.disputed ?? 0) > 0 && (
        <div className="animate-fade-in" style={{ animationDelay: '0.2s' }}>
          <Alert variant="warning" title="Contracts Under Dispute">
            You have {stats?.disputed} contract(s) with active disputes requiring attention.
            <Button variant="ghost" size="sm" className="ml-2" onClick={() => setActiveTab('disputed')}>
              View Disputes
            </Button>
          </Alert>
        </div>
      )}

      {/* Contracts Table */}
      <Card padding="none" className="animate-fade-in" style={{ animationDelay: '0.2s' }}>
        <div className="p-4 border-b border-edge">
          <Tabs tabs={tabs} activeTab={activeTab} onChange={setActiveTab} />
        </div>

        <div className="p-4 border-b border-edge">
          <div className="flex flex-col sm:flex-row gap-4">
            <div className="flex-1">
              <Input
                placeholder="Search contracts..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                leftIcon={<Search className="w-4 h-4" />}
              />
            </div>
            <div className="flex gap-2">
              <Button variant="outline" leftIcon={<Filter className="w-4 h-4" />}>
                Filters
              </Button>
              <Button variant="outline" leftIcon={<Download className="w-4 h-4" />}>
                Export
              </Button>
            </div>
          </div>
        </div>

        {filteredContracts.length > 0 ? (
          <DataTable
            data={filteredContracts}
            keyExtractor={(contract) => contract.id}
            columns={[
              {
                key: 'escrowReference',
                header: 'Contract',
                render: (_, contract) => (
                  <div className="flex items-center gap-3">
                    <StatusIconBadge tone={contract.status === 'DISPUTED' ? 'error' : 'primary'} icon={Shield} />
                    <div>
                      <p className="body-strong">{typeLabel(contract)}</p>
                      <p className="caption">{contract.escrowReference}</p>
                    </div>
                  </div>
                ),
              },
              {
                key: 'parties',
                header: 'Parties',
                render: (_, contract) => (
                  <div className="space-y-1">
                    <div className="flex items-center gap-2">
                      <span className="text-caption text-neutral-400 uppercase tracking-wider dark:text-neutral-400">Seller:</span>
                      <span className="text-body-sm text-neutral-900 dark:text-neutral-50">{contract.sellerName || '—'}</span>
                    </div>
                    <div className="flex items-center gap-2">
                      <span className="text-caption text-neutral-400 uppercase tracking-wider dark:text-neutral-400">Buyer:</span>
                      <span className="text-body-sm text-neutral-900 dark:text-neutral-50">{contract.buyerName || '—'}</span>
                    </div>
                  </div>
                ),
              },
              {
                key: 'contractAmount',
                header: 'Amount',
                render: (_, contract) => (
                  <>
                    <p className="text-body-sm font-bold tracking-tight text-neutral-900 dark:text-neutral-50">{formatCurrency(contract.contractAmount, contract.currencyCode)}</p>
                    <p className="caption">Balance: {formatCurrency(contract.currentBalance, contract.currencyCode)}</p>
                  </>
                ),
              },
              {
                key: 'status',
                header: 'Status',
                render: (_, contract) => {
                  const status = statusConfig[contract.status];
                  return (
                    <>
                      <Badge variant={status.color as any} className="flex items-center gap-1.5 w-fit">
                        {status.icon}
                        {status.label}
                      </Badge>
                      {contract.status === 'DISPUTED' && (
                        <div className="flex items-center gap-1 mt-1 text-error-600 dark:text-error-300">
                          <Flag className="w-3 h-3" />
                          <span className="text-caption">Dispute raised</span>
                        </div>
                      )}
                    </>
                  );
                },
              },
              {
                key: 'progress',
                header: 'Funded',
                render: (_, contract) => {
                  // Real figures from the contract: how much of the agreed amount
                  // has been funded, and how much has already gone to the seller.
                  const funded = contract.contractAmount > 0
                    ? Math.min(100, (contract.fundedAmount / contract.contractAmount) * 100)
                    : 0;
                  return (
                    <div className="space-y-1">
                      <div className="flex items-center justify-between text-caption">
                        <span className="text-neutral-500 dark:text-neutral-400">Funded</span>
                        <span className="text-neutral-900 font-medium dark:text-neutral-50">{funded.toFixed(0)}%</span>
                      </div>
                      <ProgressBar value={funded} size="sm" variant={funded >= 100 ? 'success' : 'default'} />
                      {contract.releasedAmount > 0 && (
                        <p className="caption">Released: {formatCurrency(contract.releasedAmount, contract.currencyCode)}</p>
                      )}
                    </div>
                  );
                },
              },
              {
                key: 'expiryDate',
                header: 'Expires',
                render: (_, contract) => (
                  <div className="flex items-center gap-2">
                    <Calendar className="w-4 h-4 text-neutral-400" />
                    <span className="body-sm">{contract.expiryDate ? formatDate(contract.expiryDate) : '—'}</span>
                  </div>
                ),
              },
              {
                key: 'actions',
                header: 'Actions',
                render: (_, contract) => (
                  <div className="flex items-center gap-1">
                    <Button variant="ghost" size="sm" onClick={() => setSelectedContract(contract)}><Eye className="w-4 h-4" /></Button>
                    <Button variant="ghost" size="sm"><MoreHorizontal className="w-4 h-4" /></Button>
                  </div>
                ),
              },
            ]}
          />
        ) : loading ? (
          <div className="flex flex-col items-center justify-center py-16">
            <Loader2 className="w-6 h-6 animate-spin text-neutral-400 mb-3" />
            <p className="caption">Loading escrow contracts…</p>
          </div>
        ) : (
          <div className="flex flex-col items-center justify-center py-16">
            <StatusIconBadge tone="neutral" icon={Shield} size="lg" className="mb-4" />
            <p className="body-strong mb-1">
              {error ? 'Escrow contracts could not be loaded'
                : contracts.length === 0 ? 'No escrow contracts yet'
                : 'No contracts match this filter'}
            </p>
            <p className="caption mb-4">
              {error ? 'The escrow service did not respond.'
                : contracts.length === 0 ? 'Contracts you create will appear here.'
                : 'Try a different tab or clear the search.'}
            </p>
            {error ? (
              <Button size="sm" variant="outline" onClick={() => void loadData()}>Try again</Button>
            ) : contracts.length === 0 ? (
              <Button size="sm" onClick={() => setShowCreateModal(true)}>Create Contract</Button>
            ) : null}
          </div>
        )}
      </Card>

      {/* Create Contract Modal */}
      <Modal
        isOpen={showCreateModal}
        onClose={closeCreateModal}
        title="Create Escrow Contract"
        subtitle="Opens an escrow account to hold the funds until the conditions are met"
        size="lg"
        footer={
          <>
            <Button variant="outline" onClick={closeCreateModal} disabled={submitting}>
              Cancel
            </Button>
            <Button
              onClick={() => void handleCreate()}
              disabled={submitting}
              leftIcon={submitting ? <Loader2 className="w-4 h-4 animate-spin" /> : undefined}
            >
              {submitting ? 'Creating…' : 'Create Contract'}
            </Button>
          </>
        }
      >
        <form
          className="grid grid-cols-1 sm:grid-cols-2 gap-4"
          onSubmit={(e) => { e.preventDefault(); void handleCreate(); }}
        >
          <Select
            label="Program"
            placeholder="Select a program"
            value={form.programId}
            error={formErrors.programId}
            hint="The escrow account is opened under this program's corporate"
            options={programs.map(p => ({ value: p.id, label: `${p.programCode} — ${p.programName}` }))}
            onChange={(e) => {
              const program = programs.find(p => p.id === e.target.value);
              setForm(prev => ({
                ...prev,
                programId: e.target.value,
                // A different program can mean a different corporate, so the
                // previously chosen account may no longer be eligible.
                physicalAccountId: '',
                currencyCode: program?.currencyCode || prev.currencyCode,
              }));
              setFormErrors(prev => ({ ...prev, programId: undefined, physicalAccountId: undefined }));
            }}
          />
          <Select
            label="Funding account"
            placeholder={form.programId ? 'Select an account' : 'Select a program first'}
            value={form.physicalAccountId}
            error={formErrors.physicalAccountId}
            disabled={!form.programId}
            options={eligibleAccounts.map(a => ({
              value: a.id,
              label: `${a.accountNumber} — ${a.accountName}${a.bankName ? ` (${a.bankName})` : ''}`,
            }))}
            onChange={(e) => setField('physicalAccountId', e.target.value)}
          />
          <Select
            label="Escrow type"
            value={form.escrowType}
            error={formErrors.escrowType}
            options={types.map(t => ({ value: t.code, label: t.name }))}
            onChange={(e) => setField('escrowType', e.target.value)}
          />
          <Input
            label="Currency"
            value={form.currencyCode}
            error={formErrors.currencyCode}
            placeholder="GBP"
            maxLength={3}
            onChange={(e) => setField('currencyCode', e.target.value.toUpperCase())}
          />
          <Input
            label="Seller"
            placeholder="Who receives the funds"
            value={form.sellerName}
            error={formErrors.sellerName}
            onChange={(e) => setField('sellerName', e.target.value)}
          />
          <Input
            label="Buyer"
            placeholder="Who funds the escrow"
            value={form.buyerName}
            error={formErrors.buyerName}
            onChange={(e) => setField('buyerName', e.target.value)}
          />
          <Input
            label="Contract amount"
            type="number"
            min="0"
            step="0.01"
            placeholder="0.00"
            value={form.contractAmount}
            error={formErrors.contractAmount}
            onChange={(e) => setField('contractAmount', e.target.value)}
          />
          <Input
            label="Expiry date"
            type="date"
            value={form.expiryDate}
            error={formErrors.expiryDate}
            onChange={(e) => setField('expiryDate', e.target.value)}
          />
          <div className="sm:col-span-2">
            <label className="field-label" htmlFor="escrow-release-conditions">Release conditions</label>
            <textarea
              id="escrow-release-conditions"
              rows={3}
              className="w-full px-3 py-2 rounded-md border border-edge bg-surface-card text-body-sm text-neutral-900 dark:text-neutral-50 placeholder:text-neutral-400 focus:outline-none focus:ring-2 focus:ring-primary-500"
              placeholder={'One per line, e.g.\nGoods delivered\nInspection passed'}
              value={form.releaseConditions}
              onChange={(e) => setField('releaseConditions', e.target.value)}
            />
            <p className="caption mt-1">Optional. Recorded against the contract; funds are released manually.</p>
          </div>
        </form>
      </Modal>
    </Page>
  );
};

export default EscrowPage;