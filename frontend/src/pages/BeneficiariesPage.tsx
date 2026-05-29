// ============================================================================
// BENEFICIARIES PAGE - PREMIUM DESIGN SYSTEM
// ============================================================================

import React, { useState, useEffect } from 'react';
import {
  Users, Plus, Search, CheckCircle, Trash2, Loader2, AlertCircle,
  RefreshCw, Building2, Globe, CreditCard, ChevronRight, Eye,
  MoreHorizontal, Clock, Ban, ChevronLeft,
} from 'lucide-react';
import { Card, Button, Badge, Input, Select, Skeleton, EmptyState } from '../components/ui';
import { Modal } from '../components/ui/enhanced';
import { beneficiariesApi, Beneficiary } from '../services/api';
import { cn } from '../utils';
import { Page } from '../components/layout/Page';

// ============================================================================
// STAT CARD COMPONENT
// ============================================================================

interface StatCardProps {
  title: string;
  value: string | number;
  icon: React.ReactNode;
  iconBg: string;
  iconColor: string;
  loading?: boolean;
  delay?: number;
}

const StatCard: React.FC<StatCardProps> = ({ title, value, icon, iconBg, iconColor, loading, delay = 0 }) => {
  if (loading) {
    return (
      <Card hover className="animate-fade-in" style={{ animationDelay: `${delay}s` }}>
        <div className="flex items-center gap-3">
          <Skeleton className="w-12 h-12 rounded-xl" />
          <div className="flex-1">
            <Skeleton className="h-3 w-20 mb-2" />
            <Skeleton className="h-7 w-16" />
          </div>
        </div>
      </Card>
    );
  }

  return (
    <Card hover className="animate-fade-in" style={{ animationDelay: `${delay}s` }}>
      <div className="flex items-center gap-3">
        <div className={cn("w-12 h-12 rounded-xl flex items-center justify-center", iconBg)}>
          <span className={iconColor}>{icon}</span>
        </div>
        <div>
          <p className="label">{title}</p>
          <p className="stat-value-sm">{value}</p>
        </div>
      </div>
    </Card>
  );
};

// ============================================================================
// BENEFICIARY ROW (Desktop Table)
// ============================================================================

interface BeneficiaryRowProps {
  beneficiary: Beneficiary;
  onView: (b: Beneficiary) => void;
  onVerify: (id: string) => void;
  onDelete: (id: string) => void;
  processing: boolean;
}

const BeneficiaryRow: React.FC<BeneficiaryRowProps> = ({
  beneficiary,
  onView,
  onVerify,
  onDelete,
  processing,
}) => {
  const [showActions, setShowActions] = useState(false);

  return (
    <tr
      className="data-table-row group cursor-pointer"
      onClick={() => onView(beneficiary)}
    >
      {/* Beneficiary Info */}
      <td className="data-table-cell">
        <div className="flex items-center gap-3">
          <div className={cn(
            "w-10 h-10 rounded-xl flex items-center justify-center shrink-0 transition-transform group-hover:scale-105",
            beneficiary.beneficiaryType === 'CORPORATE' ? "bg-info-50 dark:bg-info-500/10" : "bg-primary-50 dark:bg-primary-800/40"
          )}>
            {beneficiary.beneficiaryType === 'CORPORATE' ? (
              <Building2 className="w-5 h-5 text-info-600 dark:text-info-300" />
            ) : (
              <Users className="w-5 h-5 text-primary-600 dark:text-primary-200" />
            )}
          </div>
          <div className="min-w-0">
            <p className="text-sm font-semibold text-primary-900 truncate group-hover:text-primary-600 transition-colors dark:text-neutral-50">
              {beneficiary.beneficiaryName}
            </p>
            <div className="flex items-center gap-2 text-xs text-neutral-500 mt-0.5 dark:text-neutral-400">
              <Globe className="w-3 h-3" />
              <span>{beneficiary.countryCode || 'N/A'}</span>
            </div>
          </div>
        </div>
      </td>

      {/* Type */}
      <td className="data-table-cell">
        <Badge
          variant="neutral"
          size="sm"
          className={cn(
            beneficiary.beneficiaryType === 'CORPORATE'
              ? "bg-info-50 text-info-700 border-info-100 dark:bg-info-500/10 dark:text-info-300 dark:border-info-500/30"
              : "bg-purple-50 text-purple-700 border-purple-100 dark:bg-purple-500/10 dark:text-purple-300 dark:border-purple-500/30"
          )}
        >
          {beneficiary.beneficiaryType || 'INDIVIDUAL'}
        </Badge>
      </td>

      {/* Bank */}
      <td className="data-table-cell">
        <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{beneficiary.bankName || '-'}</p>
        <p className="text-xs text-neutral-500 font-mono mt-0.5 dark:text-neutral-400">{beneficiary.swiftCode || '-'}</p>
      </td>

      {/* Account/IBAN */}
      <td className="data-table-cell">
        <p className="text-sm font-mono text-primary-900 truncate max-w-[200px] dark:text-neutral-50">
          {beneficiary.iban || beneficiary.accountNumber || '-'}
        </p>
        <p className="text-xs text-neutral-500 mt-0.5 dark:text-neutral-400">{beneficiary.currencyCode || 'AED'}</p>
      </td>

      {/* Status */}
      <td className="data-table-cell">
        <Badge
          variant={beneficiary.validationStatus === 'VERIFIED' ? 'success' : 'warning'}
          size="sm"
        >
          {beneficiary.validationStatus === 'VERIFIED' ? (
            <CheckCircle className="w-3 h-3 mr-1" />
          ) : (
            <Clock className="w-3 h-3 mr-1" />
          )}
          {beneficiary.validationStatus || 'PENDING'}
        </Badge>
      </td>

      {/* Actions */}
      <td className="data-table-cell" onClick={(e) => e.stopPropagation()}>
        <div className="relative">
          <button
            onClick={() => setShowActions(!showActions)}
            className="p-2 hover:bg-neutral-100 rounded-lg transition-all opacity-0 group-hover:opacity-100"
          >
            <MoreHorizontal className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
          </button>
          {showActions && (
            <>
              <div className="fixed inset-0 z-10" onClick={() => setShowActions(false)} />
              <div className="absolute right-0 top-full mt-1 w-48 bg-white rounded-xl shadow-dropdown border border-neutral-200 py-1 z-20 animate-fade-in dark:bg-primary-900 dark:border-primary-800">
                <button
                  onClick={() => { onView(beneficiary); setShowActions(false); }}
                  className="w-full flex items-center gap-2 px-4 py-2.5 text-sm text-primary-900 hover:bg-neutral-50 transition-colors dark:text-neutral-50"
                >
                  <Eye className="w-4 h-4 text-neutral-500 dark:text-neutral-400" /> View Details
                </button>
                {beneficiary.validationStatus !== 'VERIFIED' && (
                  <button
                    onClick={() => { onVerify(beneficiary.id); setShowActions(false); }}
                    disabled={processing}
                    className="w-full flex items-center gap-2 px-4 py-2.5 text-sm text-success-600 hover:bg-success-50 transition-colors disabled:opacity-50 dark:text-success-300"
                  >
                    <CheckCircle className="w-4 h-4" /> Verify Beneficiary
                  </button>
                )}
                <hr className="my-1 border-neutral-100 dark:border-primary-800/60" />
                <button
                  onClick={() => { onDelete(beneficiary.id); setShowActions(false); }}
                  disabled={processing}
                  className="w-full flex items-center gap-2 px-4 py-2.5 text-sm text-error-600 hover:bg-error-50 transition-colors disabled:opacity-50 dark:text-error-300"
                >
                  <Trash2 className="w-4 h-4" /> Delete
                </button>
              </div>
            </>
          )}
        </div>
      </td>
    </tr>
  );
};

// ============================================================================
// BENEFICIARY MOBILE CARD
// ============================================================================

interface BeneficiaryMobileCardProps {
  beneficiary: Beneficiary;
  onView: (b: Beneficiary) => void;
  index: number;
}

const BeneficiaryMobileCard: React.FC<BeneficiaryMobileCardProps> = ({
  beneficiary,
  onView,
  index,
}) => {
  return (
    <Card
      interactive
      hover
      onClick={() => onView(beneficiary)}
      className="animate-fade-in"
      style={{ animationDelay: `${index * 0.03}s` }}
    >
      <div className="flex items-start gap-3">
        <div className={cn(
          "w-12 h-12 rounded-xl flex items-center justify-center shrink-0",
          beneficiary.beneficiaryType === 'CORPORATE' ? "bg-info-50 dark:bg-info-500/10" : "bg-primary-50 dark:bg-primary-800/40"
        )}>
          {beneficiary.beneficiaryType === 'CORPORATE' ? (
            <Building2 className="w-6 h-6 text-info-600 dark:text-info-300" />
          ) : (
            <Users className="w-6 h-6 text-primary-600 dark:text-primary-200" />
          )}
        </div>

        <div className="flex-1 min-w-0">
          <div className="flex items-start justify-between gap-2">
            <div className="min-w-0">
              <h3 className="font-semibold text-primary-900 truncate dark:text-neutral-50">{beneficiary.beneficiaryName}</h3>
              <p className="text-xs text-neutral-500 mt-0.5 dark:text-neutral-400">{beneficiary.bankName || 'No bank info'}</p>
            </div>
            <Badge
              variant={beneficiary.validationStatus === 'VERIFIED' ? 'success' : 'warning'}
              size="sm"
            >
              {beneficiary.validationStatus || 'PENDING'}
            </Badge>
          </div>

          <div className="mt-3 pt-3 border-t border-neutral-100 flex items-center justify-between dark:border-primary-800/60">
            <div>
              <p className="text-xs text-neutral-500 dark:text-neutral-400">Account</p>
              <p className="text-sm font-mono text-primary-900 truncate max-w-[150px] dark:text-neutral-50">
                {beneficiary.iban || beneficiary.accountNumber || '-'}
              </p>
            </div>
            <div className="text-right">
              <p className="text-xs text-neutral-500 dark:text-neutral-400">Currency</p>
              <p className="text-sm font-semibold text-primary-900 dark:text-neutral-50">
                {beneficiary.currencyCode || 'AED'}
              </p>
            </div>
          </div>
        </div>

        <ChevronRight className="w-5 h-5 text-neutral-400 shrink-0 mt-4" />
      </div>
    </Card>
  );
};

// ============================================================================
// CREATE BENEFICIARY FORM
// ============================================================================

interface CreateBeneficiaryFormProps {
  onSubmit: (data: any) => void;
  loading: boolean;
  onCancel: () => void;
}

const CreateBeneficiaryForm: React.FC<CreateBeneficiaryFormProps> = ({
  onSubmit,
  loading,
  onCancel,
}) => {
  const [formData, setFormData] = useState({
    beneficiaryName: '',
    beneficiaryType: 'INDIVIDUAL',
    bankName: '',
    swiftCode: '',
    accountNumber: '',
    iban: '',
    currencyCode: 'AED',
    countryCode: 'AE',
    city: '',
    addressLine1: '',
  });

  const typeOptions = [
    { value: 'INDIVIDUAL', label: 'Individual' },
    { value: 'CORPORATE', label: 'Corporate' },
  ];

  const currencyOptions = [
    { value: 'AED', label: 'AED - UAE Dirham' },
    { value: 'USD', label: 'USD - US Dollar' },
    { value: 'EUR', label: 'EUR - Euro' },
    { value: 'GBP', label: 'GBP - British Pound' },
    { value: 'SAR', label: 'SAR - Saudi Riyal' },
  ];

  return (
    <div className="space-y-6">
      {/* Basic Info Section */}
      <div className="space-y-4">
        <h4 className="text-sm font-semibold text-primary-900 uppercase tracking-wide dark:text-neutral-50">
          Basic Information
        </h4>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div>
            <label className="form-label">Beneficiary Name *</label>
            <Input
              value={formData.beneficiaryName}
              onChange={(e) => setFormData({ ...formData, beneficiaryName: e.target.value })}
              placeholder="Enter beneficiary name"
            />
          </div>
          <div>
            <label className="form-label">Type</label>
            <Select
              value={formData.beneficiaryType}
              onChange={(e) => setFormData({ ...formData, beneficiaryType: e.target.value })}
              options={typeOptions}
            />
          </div>
        </div>
      </div>

      {/* Bank Details Section */}
      <div className="space-y-4 pt-4 border-t border-neutral-200 dark:border-primary-800">
        <h4 className="text-sm font-semibold text-primary-900 uppercase tracking-wide dark:text-neutral-50">
          Bank Details
        </h4>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div>
            <label className="form-label">Bank Name</label>
            <Input
              value={formData.bankName}
              onChange={(e) => setFormData({ ...formData, bankName: e.target.value })}
              placeholder="Enter bank name"
            />
          </div>
          <div>
            <label className="form-label">SWIFT/BIC Code</label>
            <Input
              value={formData.swiftCode}
              onChange={(e) => setFormData({ ...formData, swiftCode: e.target.value })}
              placeholder="e.g., EABORAKS"
            />
          </div>
          <div>
            <label className="form-label">Account Number</label>
            <Input
              value={formData.accountNumber}
              onChange={(e) => setFormData({ ...formData, accountNumber: e.target.value })}
              placeholder="Enter account number"
            />
          </div>
          <div>
            <label className="form-label">IBAN</label>
            <Input
              value={formData.iban}
              onChange={(e) => setFormData({ ...formData, iban: e.target.value })}
              placeholder="e.g., AE..."
            />
          </div>
          <div>
            <label className="form-label">Currency</label>
            <Select
              value={formData.currencyCode}
              onChange={(e) => setFormData({ ...formData, currencyCode: e.target.value })}
              options={currencyOptions}
            />
          </div>
          <div>
            <label className="form-label">Country Code</label>
            <Input
              value={formData.countryCode}
              onChange={(e) => setFormData({ ...formData, countryCode: e.target.value })}
              placeholder="e.g., AE"
            />
          </div>
        </div>
      </div>

      {/* Actions */}
      <div className="flex justify-end gap-3 pt-4 border-t border-neutral-200 dark:border-primary-800">
        <Button variant="outline" onClick={onCancel}>
          Cancel
        </Button>
        <Button
          onClick={() => onSubmit(formData)}
          disabled={loading || !formData.beneficiaryName}
          leftIcon={loading ? <Loader2 className="w-4 h-4 animate-spin" /> : <Plus className="w-4 h-4" />}
        >
          Add Beneficiary
        </Button>
      </div>
    </div>
  );
};

// ============================================================================
// MAIN BENEFICIARIES PAGE
// ============================================================================

const BeneficiariesPage: React.FC = () => {
  const [beneficiaries, setBeneficiaries] = useState<Beneficiary[]>([]);
  const [stats, setStats] = useState<any>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [selectedBeneficiary, setSelectedBeneficiary] = useState<Beneficiary | null>(null);
  const [processing, setProcessing] = useState(false);

  // Pagination
  const [currentPage, setCurrentPage] = useState(0);
  const pageSize = 10;

  const fetchData = async () => {
    try {
      setLoading(true);
      setError(null);
      const [benRes, statsRes] = await Promise.all([
        beneficiariesApi.getAll(),
        beneficiariesApi.getStats(),
      ]);
      if (benRes.success) setBeneficiaries(benRes.data);
      if (statsRes.success) setStats(statsRes.data);
    } catch (err: any) {
      setError(err.message || 'Failed to load beneficiaries');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchData();
  }, []);

  const handleCreate = async (data: any) => {
    setProcessing(true);
    try {
      const res = await beneficiariesApi.create(data);
      if (res.success) {
        setBeneficiaries((prev) => [...prev, res.data]);
        setShowCreateModal(false);
      }
    } finally {
      setProcessing(false);
    }
  };

  const handleVerify = async (id: string) => {
    setProcessing(true);
    try {
      await beneficiariesApi.verify(id);
      await fetchData();
    } finally {
      setProcessing(false);
    }
  };

  const handleDelete = async (id: string) => {
    if (!confirm('Are you sure you want to delete this beneficiary?')) return;
    setProcessing(true);
    try {
      await beneficiariesApi.delete(id);
      setBeneficiaries((prev) => prev.filter((b) => b.id !== id));
    } finally {
      setProcessing(false);
    }
  };

  const filteredBeneficiaries = beneficiaries.filter(
    (b) =>
      searchQuery === '' ||
      b.beneficiaryName?.toLowerCase().includes(searchQuery.toLowerCase()) ||
      b.accountNumber?.toLowerCase().includes(searchQuery.toLowerCase()) ||
      b.iban?.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const paginatedBeneficiaries = filteredBeneficiaries.slice(
    currentPage * pageSize,
    (currentPage + 1) * pageSize
  );

  const totalPages = Math.ceil(filteredBeneficiaries.length / pageSize);

  // Loading State
  if (loading) {
    return (
      <Page>
        <div className="flex items-center justify-end">
          <Skeleton className="h-10 w-36" />
        </div>
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
          {[0, 1, 2].map((i) => (
            <StatCard key={i} title="" value="" icon={null} iconBg="" iconColor="" loading delay={i * 0.05} />
          ))}
        </div>
        <Card>
          <div className="p-4">
            <Skeleton className="h-10 w-full" />
          </div>
        </Card>
        <Card>
          <div className="p-6 space-y-4">
            {[0, 1, 2, 3, 4].map((i) => (
              <div key={i} className="flex items-center gap-4">
                <Skeleton className="w-10 h-10 rounded-full" />
                <div className="flex-1 space-y-2">
                  <Skeleton className="h-4 w-1/3" />
                  <Skeleton className="h-3 w-1/4" />
                </div>
                <Skeleton className="h-6 w-20 rounded-full" />
              </div>
            ))}
          </div>
        </Card>
      </Page>
    );
  }

  return (
    <Page>
      {/* Quick Actions */}
      <div className="flex items-center justify-end gap-2">
        <Button
          variant="outline"
          leftIcon={<RefreshCw className={cn("w-4 h-4", loading && "animate-spin")} />}
          onClick={fetchData}
        >
          <span className="hidden sm:inline">Refresh</span>
        </Button>
        <Button
          leftIcon={<Plus className="w-4 h-4" />}
          onClick={() => setShowCreateModal(true)}
        >
          Add Beneficiary
        </Button>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
        <StatCard
          title="Total Beneficiaries"
          value={stats?.total || beneficiaries.length || 0}
          icon={<Users className="w-6 h-6" />}
          iconBg="bg-primary-100 dark:bg-primary-700"
          iconColor="text-primary-600 dark:text-primary-200"
          delay={0.05}
        />
        <StatCard
          title="Active"
          value={stats?.active || beneficiaries.filter((b) => b.status === 'ACTIVE').length || 0}
          icon={<CheckCircle className="w-6 h-6" />}
          iconBg="bg-success-100 dark:bg-success-500/20"
          iconColor="text-success-600 dark:text-success-300"
          delay={0.1}
        />
        <StatCard
          title="Verified"
          value={stats?.verified || beneficiaries.filter((b) => b.validationStatus === 'VERIFIED').length || 0}
          icon={<CreditCard className="w-6 h-6" />}
          iconBg="bg-info-100 dark:bg-info-500/20"
          iconColor="text-info-600 dark:text-info-300"
          delay={0.15}
        />
      </div>

      {/* Error Banner */}
      {error && (
        <Card className="bg-error-50 border-error-200 animate-fade-in dark:bg-error-500/10 dark:border-error-500/30">
          <div className="flex items-center gap-3 p-4">
            <AlertCircle className="w-5 h-5 text-error-600 dark:text-error-300" />
            <span className="text-error-800 font-medium dark:text-error-300">{error}</span>
            <button
              onClick={() => setError(null)}
              className="ml-auto p-1 hover:bg-error-100 rounded-lg transition-colors"
            >
              <Ban className="w-4 h-4 text-error-600 dark:text-error-300" />
            </button>
          </div>
        </Card>
      )}

      {/* Search & Filter Bar */}
      <Card className="animate-fade-in" style={{ animationDelay: '0.2s' }}>
        <div className="p-4">
          <div className="relative">
            <Search className="w-4 h-4 absolute left-4 top-1/2 -translate-y-1/2 text-neutral-400" />
            <Input
              className="pl-11"
              placeholder="Search by name, account number, or IBAN..."
              value={searchQuery}
              onChange={(e) => {
                setSearchQuery(e.target.value);
                setCurrentPage(0);
              }}
            />
          </div>
        </div>
      </Card>

      {/* Beneficiaries Table/Cards */}
      <Card className="animate-fade-in" style={{ animationDelay: '0.25s' }}>
        {paginatedBeneficiaries.length > 0 ? (
          <>
            {/* Desktop Table */}
            <div className="hidden md:block overflow-x-auto">
              <table className="data-table">
                <thead className="data-table-header">
                  <tr>
                    <th className="data-table-header-cell">Beneficiary</th>
                    <th className="data-table-header-cell">Type</th>
                    <th className="data-table-header-cell">Bank</th>
                    <th className="data-table-header-cell">Account/IBAN</th>
                    <th className="data-table-header-cell">Status</th>
                    <th className="data-table-header-cell w-16">Actions</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-neutral-100">
                  {paginatedBeneficiaries.map((ben) => (
                    <BeneficiaryRow
                      key={ben.id}
                      beneficiary={ben}
                      onView={setSelectedBeneficiary}
                      onVerify={handleVerify}
                      onDelete={handleDelete}
                      processing={processing}
                    />
                  ))}
                </tbody>
              </table>
            </div>

            {/* Mobile Cards */}
            <div className="md:hidden p-4 space-y-3">
              {paginatedBeneficiaries.map((ben, idx) => (
                <BeneficiaryMobileCard
                  key={ben.id}
                  beneficiary={ben}
                  onView={setSelectedBeneficiary}
                  index={idx}
                />
              ))}
            </div>

            {/* Pagination */}
            {totalPages > 1 && (
              <div className="flex flex-col sm:flex-row items-center justify-between gap-4 px-4 py-4 border-t border-neutral-200 dark:border-primary-800">
                <p className="text-sm text-neutral-500 order-2 sm:order-1 dark:text-neutral-400">
                  Showing{' '}
                  <span className="font-medium text-primary-900 dark:text-neutral-50">
                    {Math.min(currentPage * pageSize + 1, filteredBeneficiaries.length)}
                  </span>
                  {' '}to{' '}
                  <span className="font-medium text-primary-900 dark:text-neutral-50">
                    {Math.min((currentPage + 1) * pageSize, filteredBeneficiaries.length)}
                  </span>
                  {' '}of{' '}
                  <span className="font-medium text-primary-900 dark:text-neutral-50">{filteredBeneficiaries.length}</span>
                  {' '}beneficiaries
                </p>
                <div className="flex items-center gap-1 order-1 sm:order-2">
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={currentPage === 0}
                    onClick={() => setCurrentPage(currentPage - 1)}
                  >
                    <ChevronLeft className="w-4 h-4" />
                  </Button>
                  <div className="hidden sm:flex items-center gap-1">
                    {Array.from({ length: Math.min(totalPages, 5) }, (_, i) => {
                      const p = currentPage < 3 ? i : currentPage - 2 + i;
                      if (p >= totalPages) return null;
                      return (
                        <Button
                          key={p}
                          variant={currentPage === p ? 'primary' : 'ghost'}
                          size="sm"
                          onClick={() => setCurrentPage(p)}
                        >
                          {p + 1}
                        </Button>
                      );
                    })}
                  </div>
                  <span className="sm:hidden text-sm text-neutral-600 px-2 dark:text-neutral-300">
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
          </>
        ) : (
          <div className="p-8">
            <EmptyState
              icon={<Users className="w-12 h-12" />}
              title="No beneficiaries found"
              description={
                searchQuery
                  ? 'Try adjusting your search criteria.'
                  : 'Get started by adding your first beneficiary.'
              }
              action={
                searchQuery ? (
                  <Button variant="outline" onClick={() => setSearchQuery('')}>
                    Clear Search
                  </Button>
                ) : (
                  <Button
                    onClick={() => setShowCreateModal(true)}
                    leftIcon={<Plus className="w-4 h-4" />}
                  >
                    Add Beneficiary
                  </Button>
                )
              }
            />
          </div>
        )}
      </Card>

      {/* Create Modal */}
      <Modal
        isOpen={showCreateModal}
        onClose={() => setShowCreateModal(false)}
        title="Add New Beneficiary"
        size="lg"
      >
        <CreateBeneficiaryForm
          onSubmit={handleCreate}
          loading={processing}
          onCancel={() => setShowCreateModal(false)}
        />
      </Modal>

      {/* View/Edit Modal (placeholder) */}
      {selectedBeneficiary && (
        <Modal
          isOpen={!!selectedBeneficiary}
          onClose={() => setSelectedBeneficiary(null)}
          title="Beneficiary Details"
          size="lg"
        >
          <div className="space-y-6">
            <div className="flex items-center gap-4">
              <div className={cn(
                "w-16 h-16 rounded-2xl flex items-center justify-center",
                selectedBeneficiary.beneficiaryType === 'CORPORATE' ? "bg-info-100 dark:bg-info-500/20" : "bg-primary-100 dark:bg-primary-700"
              )}>
                {selectedBeneficiary.beneficiaryType === 'CORPORATE' ? (
                  <Building2 className="w-8 h-8 text-info-600 dark:text-info-300" />
                ) : (
                  <Users className="w-8 h-8 text-primary-600 dark:text-primary-200" />
                )}
              </div>
              <div>
                <h3 className="section-title">
                  {selectedBeneficiary.beneficiaryName}
                </h3>
                <div className="flex items-center gap-2 mt-1">
                  <Badge
                    variant={selectedBeneficiary.validationStatus === 'VERIFIED' ? 'success' : 'warning'}
                  >
                    {selectedBeneficiary.validationStatus || 'PENDING'}
                  </Badge>
                  <Badge variant="neutral">
                    {selectedBeneficiary.beneficiaryType || 'INDIVIDUAL'}
                  </Badge>
                </div>
              </div>
            </div>

            <div className="grid grid-cols-2 gap-4 pt-4 border-t border-neutral-200 dark:border-primary-800">
              <div>
                <p className="text-xs text-neutral-500 uppercase tracking-wide dark:text-neutral-400">Bank Name</p>
                <p className="text-sm font-medium text-primary-900 mt-1 dark:text-neutral-50">
                  {selectedBeneficiary.bankName || '-'}
                </p>
              </div>
              <div>
                <p className="text-xs text-neutral-500 uppercase tracking-wide dark:text-neutral-400">SWIFT Code</p>
                <p className="text-sm font-medium font-mono text-primary-900 mt-1 dark:text-neutral-50">
                  {selectedBeneficiary.swiftCode || '-'}
                </p>
              </div>
              <div>
                <p className="text-xs text-neutral-500 uppercase tracking-wide dark:text-neutral-400">Account Number</p>
                <p className="text-sm font-medium font-mono text-primary-900 mt-1 dark:text-neutral-50">
                  {selectedBeneficiary.accountNumber || '-'}
                </p>
              </div>
              <div>
                <p className="text-xs text-neutral-500 uppercase tracking-wide dark:text-neutral-400">IBAN</p>
                <p className="text-sm font-medium font-mono text-primary-900 mt-1 dark:text-neutral-50">
                  {selectedBeneficiary.iban || '-'}
                </p>
              </div>
              <div>
                <p className="text-xs text-neutral-500 uppercase tracking-wide dark:text-neutral-400">Currency</p>
                <p className="text-sm font-medium text-primary-900 mt-1 dark:text-neutral-50">
                  {selectedBeneficiary.currencyCode || 'AED'}
                </p>
              </div>
              <div>
                <p className="text-xs text-neutral-500 uppercase tracking-wide dark:text-neutral-400">Country</p>
                <p className="text-sm font-medium text-primary-900 mt-1 dark:text-neutral-50">
                  {selectedBeneficiary.countryCode || '-'}
                </p>
              </div>
            </div>

            <div className="flex justify-end gap-3 pt-4 border-t border-neutral-200 dark:border-primary-800">
              {selectedBeneficiary.validationStatus !== 'VERIFIED' && (
                <Button
                  variant="outline"
                  leftIcon={<CheckCircle className="w-4 h-4" />}
                  onClick={() => {
                    handleVerify(selectedBeneficiary.id);
                    setSelectedBeneficiary(null);
                  }}
                  disabled={processing}
                >
                  Verify
                </Button>
              )}
              <Button
                variant="danger"
                leftIcon={<Trash2 className="w-4 h-4" />}
                onClick={() => {
                  handleDelete(selectedBeneficiary.id);
                  setSelectedBeneficiary(null);
                }}
                disabled={processing}
              >
                Delete
              </Button>
            </div>
          </div>
        </Modal>
      )}
    </Page>
  );
};

export default BeneficiariesPage;
