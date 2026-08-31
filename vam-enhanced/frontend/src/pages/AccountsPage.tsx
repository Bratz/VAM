import React, { useState } from 'react';
import {
  Search,
  Plus,
  Filter,
  Download,
  MoreHorizontal,
  Copy,
  Eye,
  Edit,
  XCircle,
  ChevronLeft,
  ChevronRight,
  Building2,
} from 'lucide-react';
import { Card, Button, Badge, Input, EmptyState } from '../ui';
import { formatCurrency, formatDate, formatIban, getStatusVariant, cn, copyToClipboard } from '../../utils';
import { VirtualAccountSummary, AccountStatus } from '../../types';
import toast from 'react-hot-toast';

// Mock data
const mockAccounts: VirtualAccountSummary[] = [
  {
    id: '1',
    virtualAccountNumber: 1001,
    virtualIban: 'AE251323001099650000438',
    accountName: 'Operating Account - Main',
    corporateSchemeCode: 'CORP001',
    currencyCode: 'AED',
    currentBalance: 5250000.50,
    availableBalance: 5100000.00,
    status: AccountStatus.ACTIVE,
    statusDescription: 'Active',
    openedOn: '2024-01-15',
  },
  {
    id: '2',
    virtualAccountNumber: 1002,
    virtualIban: 'AE251323001099650000439',
    accountName: 'Payroll Account',
    corporateSchemeCode: 'CORP001',
    currencyCode: 'AED',
    currentBalance: 3100000.00,
    availableBalance: 3100000.00,
    status: AccountStatus.ACTIVE,
    statusDescription: 'Active',
    openedOn: '2024-01-15',
  },
  {
    id: '3',
    virtualAccountNumber: 1003,
    virtualIban: 'AE251323001099650000440',
    accountName: 'Vendor Payments',
    corporateSchemeCode: 'CORP001',
    currencyCode: 'AED',
    currentBalance: 2450000.75,
    availableBalance: 2200000.00,
    status: AccountStatus.ACTIVE,
    statusDescription: 'Active',
    openedOn: '2024-01-20',
  },
  {
    id: '4',
    virtualAccountNumber: 1004,
    virtualIban: 'AE251323001099650000441',
    accountName: 'Collections Account',
    corporateSchemeCode: 'CORP001',
    currencyCode: 'AED',
    currentBalance: 1650000.00,
    availableBalance: 1650000.00,
    status: AccountStatus.ON_HOLD,
    statusDescription: 'On Hold',
    openedOn: '2024-02-01',
  },
  {
    id: '5',
    virtualAccountNumber: 1005,
    virtualIban: 'AE251323001099650000442',
    accountName: 'Treasury Account',
    corporateSchemeCode: 'CORP002',
    currencyCode: 'AED',
    currentBalance: 8500000.00,
    availableBalance: 8500000.00,
    status: AccountStatus.ACTIVE,
    statusDescription: 'Active',
    openedOn: '2024-02-05',
  },
];

// Account Row Component
interface AccountRowProps {
  account: VirtualAccountSummary;
  onView: (account: VirtualAccountSummary) => void;
  onEdit: (account: VirtualAccountSummary) => void;
}

const AccountRow: React.FC<AccountRowProps> = ({ account, onView, onEdit }) => {
  const [showActions, setShowActions] = useState(false);

  const handleCopyIban = async () => {
    const success = await copyToClipboard(account.virtualIban);
    if (success) {
      toast.success('IBAN copied to clipboard');
    }
  };

  return (
    <tr className="hover:bg-neutral-50 transition-colors group">
      <td className="px-6 py-4">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-lg bg-primary-100 flex items-center justify-center">
            <Building2 className="w-5 h-5 text-primary-700" />
          </div>
          <div>
            <p className="text-body-md font-medium text-primary-900">
              {account.accountName}
            </p>
            <div className="flex items-center gap-2 mt-0.5">
              <span className="text-body-sm text-neutral-500 font-mono">
                {formatIban(account.virtualIban)}
              </span>
              <button
                onClick={handleCopyIban}
                className="opacity-0 group-hover:opacity-100 transition-opacity p-1 hover:bg-neutral-200 rounded"
                title="Copy IBAN"
              >
                <Copy className="w-3.5 h-3.5 text-neutral-500" />
              </button>
            </div>
          </div>
        </div>
      </td>
      <td className="px-6 py-4">
        <span className="text-body-sm text-neutral-600 font-mono">
          {account.corporateSchemeCode}
        </span>
      </td>
      <td className="px-6 py-4">
        <div>
          <p className="text-body-md font-semibold text-primary-900">
            {formatCurrency(account.currentBalance, account.currencyCode)}
          </p>
          <p className="text-body-sm text-neutral-500">
            Avail: {formatCurrency(account.availableBalance, account.currencyCode)}
          </p>
        </div>
      </td>
      <td className="px-6 py-4">
        <Badge variant={getStatusVariant(account.status)}>
          {account.statusDescription}
        </Badge>
      </td>
      <td className="px-6 py-4">
        <span className="text-body-sm text-neutral-600">
          {formatDate(account.openedOn)}
        </span>
      </td>
      <td className="px-6 py-4">
        <div className="relative">
          <button
            onClick={() => setShowActions(!showActions)}
            className="p-2 hover:bg-neutral-100 rounded-lg transition-colors"
          >
            <MoreHorizontal className="w-5 h-5 text-neutral-500" />
          </button>
          {showActions && (
            <>
              <div
                className="fixed inset-0 z-10"
                onClick={() => setShowActions(false)}
              />
              <div className="absolute right-0 top-full mt-1 w-48 bg-white rounded-lg shadow-medium border border-neutral-200 py-1 z-20">
                <button
                  onClick={() => {
                    onView(account);
                    setShowActions(false);
                  }}
                  className="w-full flex items-center gap-2 px-4 py-2 text-body-sm text-primary-900 hover:bg-neutral-50"
                >
                  <Eye className="w-4 h-4" />
                  View Details
                </button>
                <button
                  onClick={() => {
                    onEdit(account);
                    setShowActions(false);
                  }}
                  className="w-full flex items-center gap-2 px-4 py-2 text-body-sm text-primary-900 hover:bg-neutral-50"
                >
                  <Edit className="w-4 h-4" />
                  Edit Account
                </button>
                <hr className="my-1 border-neutral-200" />
                <button
                  onClick={() => setShowActions(false)}
                  className="w-full flex items-center gap-2 px-4 py-2 text-body-sm text-error-600 hover:bg-error-50"
                >
                  <XCircle className="w-4 h-4" />
                  Close Account
                </button>
              </div>
            </>
          )}
        </div>
      </td>
    </tr>
  );
};

// Filter Panel Component
interface FilterPanelProps {
  isOpen: boolean;
  onClose: () => void;
}

const FilterPanel: React.FC<FilterPanelProps> = ({ isOpen, onClose }) => {
  if (!isOpen) return null;

  return (
    <div className="border border-neutral-200 rounded-lg p-4 bg-white mb-4">
      <div className="flex items-center justify-between mb-4">
        <h3 className="text-heading-sm text-primary-900">Filters</h3>
        <button onClick={onClose} className="text-neutral-500 hover:text-neutral-700">
          <XCircle className="w-5 h-5" />
        </button>
      </div>
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <div>
          <label className="block text-body-sm font-medium text-primary-900 mb-1.5">
            Status
          </label>
          <select className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-body-sm">
            <option value="">All Statuses</option>
            <option value="1">Active</option>
            <option value="2">On Hold</option>
            <option value="7">Cancelled</option>
          </select>
        </div>
        <div>
          <label className="block text-body-sm font-medium text-primary-900 mb-1.5">
            Scheme
          </label>
          <select className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-body-sm">
            <option value="">All Schemes</option>
            <option value="CORP001">CORP001</option>
            <option value="CORP002">CORP002</option>
          </select>
        </div>
        <div>
          <label className="block text-body-sm font-medium text-primary-900 mb-1.5">
            Currency
          </label>
          <select className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-body-sm">
            <option value="">All Currencies</option>
            <option value="AED">AED</option>
            <option value="USD">USD</option>
            <option value="EUR">EUR</option>
          </select>
        </div>
        <div>
          <label className="block text-body-sm font-medium text-primary-900 mb-1.5">
            Balance Range
          </label>
          <select className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-body-sm">
            <option value="">Any Balance</option>
            <option value="0-100000">Below 100K</option>
            <option value="100000-1000000">100K - 1M</option>
            <option value="1000000+">Above 1M</option>
          </select>
        </div>
      </div>
      <div className="flex justify-end gap-3 mt-4">
        <Button variant="ghost" size="sm" onClick={onClose}>
          Clear All
        </Button>
        <Button size="sm">Apply Filters</Button>
      </div>
    </div>
  );
};

// Main Accounts Page
const AccountsPage: React.FC = () => {
  const [searchQuery, setSearchQuery] = useState('');
  const [showFilters, setShowFilters] = useState(false);
  const [currentPage, setCurrentPage] = useState(1);
  const pageSize = 10;

  const filteredAccounts = mockAccounts.filter(
    (account) =>
      account.accountName.toLowerCase().includes(searchQuery.toLowerCase()) ||
      account.virtualIban.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const totalPages = Math.ceil(filteredAccounts.length / pageSize);
  const paginatedAccounts = filteredAccounts.slice(
    (currentPage - 1) * pageSize,
    currentPage * pageSize
  );

  const handleView = (account: VirtualAccountSummary) => {
    console.log('View account:', account);
  };

  const handleEdit = (account: VirtualAccountSummary) => {
    console.log('Edit account:', account);
  };

  return (
    <div className="space-y-6">
      {/* Page Header */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-display-md text-primary-900">Virtual Accounts</h1>
          <p className="text-body-md text-neutral-500 mt-1">
            Manage your virtual accounts and monitor balances
          </p>
        </div>
        <Button leftIcon={<Plus className="w-4 h-4" />}>
          New Account
        </Button>
      </div>

      {/* Summary Cards */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card padding="sm">
          <p className="text-body-sm text-neutral-500">Total Accounts</p>
          <p className="text-heading-xl text-primary-900 mt-1">
            {mockAccounts.length}
          </p>
        </Card>
        <Card padding="sm">
          <p className="text-body-sm text-neutral-500">Active</p>
          <p className="text-heading-xl text-success-600 mt-1">
            {mockAccounts.filter((a) => a.status === AccountStatus.ACTIVE).length}
          </p>
        </Card>
        <Card padding="sm">
          <p className="text-body-sm text-neutral-500">Total Balance</p>
          <p className="text-heading-xl text-primary-900 mt-1">
            {formatCurrency(
              mockAccounts.reduce((sum, a) => sum + a.currentBalance, 0)
            )}
          </p>
        </Card>
        <Card padding="sm">
          <p className="text-body-sm text-neutral-500">Available Balance</p>
          <p className="text-heading-xl text-primary-900 mt-1">
            {formatCurrency(
              mockAccounts.reduce((sum, a) => sum + a.availableBalance, 0)
            )}
          </p>
        </Card>
      </div>

      {/* Search and Filters */}
      <Card padding="none">
        <div className="p-4 border-b border-neutral-200">
          <div className="flex flex-col sm:flex-row gap-4">
            <div className="flex-1">
              <Input
                placeholder="Search by account name or IBAN..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                leftIcon={<Search className="w-4 h-4" />}
              />
            </div>
            <div className="flex gap-2">
              <Button
                variant={showFilters ? 'secondary' : 'outline'}
                leftIcon={<Filter className="w-4 h-4" />}
                onClick={() => setShowFilters(!showFilters)}
              >
                Filters
              </Button>
              <Button
                variant="outline"
                leftIcon={<Download className="w-4 h-4" />}
              >
                Export
              </Button>
            </div>
          </div>
        </div>

        <FilterPanel isOpen={showFilters} onClose={() => setShowFilters(false)} />

        {/* Table */}
        {paginatedAccounts.length > 0 ? (
          <>
            <div className="overflow-x-auto">
              <table className="w-full">
                <thead className="bg-neutral-50 border-b border-neutral-200">
                  <tr>
                    <th className="px-6 py-3 text-left text-body-sm font-semibold text-neutral-600">
                      Account
                    </th>
                    <th className="px-6 py-3 text-left text-body-sm font-semibold text-neutral-600">
                      Scheme
                    </th>
                    <th className="px-6 py-3 text-left text-body-sm font-semibold text-neutral-600">
                      Balance
                    </th>
                    <th className="px-6 py-3 text-left text-body-sm font-semibold text-neutral-600">
                      Status
                    </th>
                    <th className="px-6 py-3 text-left text-body-sm font-semibold text-neutral-600">
                      Opened
                    </th>
                    <th className="px-6 py-3 text-left text-body-sm font-semibold text-neutral-600">
                      Actions
                    </th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-neutral-100">
                  {paginatedAccounts.map((account) => (
                    <AccountRow
                      key={account.id}
                      account={account}
                      onView={handleView}
                      onEdit={handleEdit}
                    />
                  ))}
                </tbody>
              </table>
            </div>

            {/* Pagination */}
            <div className="flex items-center justify-between px-6 py-4 border-t border-neutral-200">
              <p className="text-body-sm text-neutral-500">
                Showing {(currentPage - 1) * pageSize + 1} to{' '}
                {Math.min(currentPage * pageSize, filteredAccounts.length)} of{' '}
                {filteredAccounts.length} accounts
              </p>
              <div className="flex items-center gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={currentPage === 1}
                  onClick={() => setCurrentPage(currentPage - 1)}
                >
                  <ChevronLeft className="w-4 h-4" />
                </Button>
                {Array.from({ length: totalPages }, (_, i) => i + 1).map((page) => (
                  <Button
                    key={page}
                    variant={currentPage === page ? 'primary' : 'ghost'}
                    size="sm"
                    onClick={() => setCurrentPage(page)}
                  >
                    {page}
                  </Button>
                ))}
                <Button
                  variant="outline"
                  size="sm"
                  disabled={currentPage === totalPages}
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
              icon={<Building2 className="w-8 h-8" />}
              title="No accounts found"
              description="Try adjusting your search or filters to find what you're looking for."
              action={
                <Button variant="outline" onClick={() => setSearchQuery('')}>
                  Clear Search
                </Button>
              }
            />
          </div>
        )}
      </Card>
    </div>
  );
};

export default AccountsPage;
