import React, { useState } from 'react';
import {
  Wallet,
  Plus,
  Search,
  Filter,
  CreditCard,
  ArrowUpRight,
  ArrowDownRight,
  Users,
  TrendingUp,
  MoreHorizontal,
  Eye,
  Lock,
  Unlock,
  Ban,
  RefreshCw,
  Send,
  Download,
  Settings,
} from 'lucide-react';
import { Card, CardHeader, Button, Badge, Input, EmptyState } from '../ui';
import { Modal, Tabs, ProgressBar, Avatar, Alert } from '../ui/enhanced';
import { formatCurrency, formatDate, cn } from '../../utils';

// Types
interface WalletProgram {
  id: string;
  programCode: string;
  programName: string;
  operatorName: string;
  currency: string;
  activeWallets: number;
  totalBalance: number;
  dailySpendLimit: number;
  monthlySpendLimit: number;
  status: 'ACTIVE' | 'SUSPENDED' | 'CLOSED';
  launchDate: string;
}

interface WalletAccount {
  id: string;
  walletReference: string;
  holderName: string;
  holderMobile: string;
  programName: string;
  currentBalance: number;
  availableBalance: number;
  dailySpent: number;
  monthlySpent: number;
  dailyLimit: number;
  monthlyLimit: number;
  status: 'ACTIVE' | 'SUSPENDED' | 'BLOCKED';
  lastTransaction: string;
  transactionCount: number;
  kyccVerified: boolean;
}

// Mock data
const mockPrograms: WalletProgram[] = [
  {
    id: '1',
    programCode: 'WP-CORP-001',
    programName: 'Corporate Gift Cards',
    operatorName: 'Emirates Group',
    currency: 'AED',
    activeWallets: 1250,
    totalBalance: 4500000,
    dailySpendLimit: 5000,
    monthlySpendLimit: 25000,
    status: 'ACTIVE',
    launchDate: '2024-01-01',
  },
  {
    id: '2',
    programCode: 'WP-RETAIL-002',
    programName: 'Loyalty Rewards Program',
    operatorName: 'Mall of Emirates',
    currency: 'AED',
    activeWallets: 8500,
    totalBalance: 12500000,
    dailySpendLimit: 2000,
    monthlySpendLimit: 10000,
    status: 'ACTIVE',
    launchDate: '2023-06-15',
  },
];

const mockWallets: WalletAccount[] = [
  {
    id: '1',
    walletReference: 'W-20240001234',
    holderName: 'Mohammed Al Rashid',
    holderMobile: '+971501234567',
    programName: 'Corporate Gift Cards',
    currentBalance: 2500,
    availableBalance: 2500,
    dailySpent: 500,
    monthlySpent: 3500,
    dailyLimit: 5000,
    monthlyLimit: 25000,
    status: 'ACTIVE',
    lastTransaction: '2024-02-12T14:30:00Z',
    transactionCount: 45,
    kyccVerified: true,
  },
  {
    id: '2',
    walletReference: 'W-20240001235',
    holderName: 'Sarah Ahmed',
    holderMobile: '+971509876543',
    programName: 'Corporate Gift Cards',
    currentBalance: 1200,
    availableBalance: 1200,
    dailySpent: 0,
    monthlySpent: 1800,
    dailyLimit: 5000,
    monthlyLimit: 25000,
    status: 'ACTIVE',
    lastTransaction: '2024-02-11T09:15:00Z',
    transactionCount: 23,
    kyccVerified: true,
  },
  {
    id: '3',
    walletReference: 'W-20240001236',
    holderName: 'Omar Hassan',
    holderMobile: '+971505551234',
    programName: 'Loyalty Rewards Program',
    currentBalance: 850,
    availableBalance: 850,
    dailySpent: 150,
    monthlySpent: 950,
    dailyLimit: 2000,
    monthlyLimit: 10000,
    status: 'SUSPENDED',
    lastTransaction: '2024-02-10T16:45:00Z',
    transactionCount: 12,
    kyccVerified: true,
  },
  {
    id: '4',
    walletReference: 'W-20240001237',
    holderName: 'Fatima Al Ali',
    holderMobile: '+971507778899',
    programName: 'Loyalty Rewards Program',
    currentBalance: 3500,
    availableBalance: 3500,
    dailySpent: 0,
    monthlySpent: 500,
    dailyLimit: 2000,
    monthlyLimit: 10000,
    status: 'ACTIVE',
    lastTransaction: '2024-02-09T11:20:00Z',
    transactionCount: 8,
    kyccVerified: false,
  },
];

// Status configurations
const walletStatusConfig = {
  ACTIVE: { label: 'Active', color: 'success', icon: <Unlock className="w-3 h-3" /> },
  SUSPENDED: { label: 'Suspended', color: 'warning', icon: <Lock className="w-3 h-3" /> },
  BLOCKED: { label: 'Blocked', color: 'error', icon: <Ban className="w-3 h-3" /> },
};

// Program Card Component
const ProgramCard: React.FC<{
  program: WalletProgram;
  onClick: () => void;
}> = ({ program, onClick }) => (
  <Card hover className="cursor-pointer" onClick={onClick}>
    <div className="flex items-start justify-between mb-4">
      <div className="flex items-center gap-3">
        <div className="w-12 h-12 rounded-xl bg-gradient-to-br from-primary-600 to-primary-900 flex items-center justify-center">
          <Wallet className="w-6 h-6 text-white" />
        </div>
        <div>
          <h3 className="text-heading-md text-primary-900">{program.programName}</h3>
          <p className="text-body-sm text-neutral-500">{program.programCode}</p>
        </div>
      </div>
      <Badge variant={program.status === 'ACTIVE' ? 'success' : 'warning'}>
        {program.status}
      </Badge>
    </div>

    <div className="grid grid-cols-2 gap-4 mb-4">
      <div>
        <p className="text-caption text-neutral-500">Active Wallets</p>
        <p className="text-heading-lg text-primary-900">{program.activeWallets.toLocaleString()}</p>
      </div>
      <div>
        <p className="text-caption text-neutral-500">Total Balance</p>
        <p className="text-heading-lg text-primary-900">{formatCurrency(program.totalBalance)}</p>
      </div>
    </div>

    <div className="flex items-center justify-between pt-4 border-t border-neutral-200">
      <div className="flex items-center gap-2 text-body-sm text-neutral-500">
        <Users className="w-4 h-4" />
        <span>{program.operatorName}</span>
      </div>
      <Button variant="ghost" size="sm" rightIcon={<ChevronRight className="w-4 h-4" />}>
        Manage
      </Button>
    </div>
  </Card>
);

// Wallet Row Component
const WalletRow: React.FC<{
  wallet: WalletAccount;
  onView: () => void;
  onAction: (action: string) => void;
}> = ({ wallet, onView, onAction }) => {
  const [showActions, setShowActions] = useState(false);
  const status = walletStatusConfig[wallet.status];
  const dailyUsage = (wallet.dailySpent / wallet.dailyLimit) * 100;
  const monthlyUsage = (wallet.monthlySpent / wallet.monthlyLimit) * 100;

  return (
    <tr className="hover:bg-neutral-50 transition-colors">
      <td className="px-6 py-4">
        <div className="flex items-center gap-3">
          <Avatar name={wallet.holderName} size="md" status={wallet.kyccVerified ? 'online' : 'away'} />
          <div>
            <p className="text-body-md font-medium text-primary-900">{wallet.holderName}</p>
            <p className="text-body-sm text-neutral-500">{wallet.holderMobile}</p>
          </div>
        </div>
      </td>
      <td className="px-6 py-4">
        <p className="text-body-sm font-mono text-primary-900">{wallet.walletReference}</p>
        <p className="text-caption text-neutral-500">{wallet.programName}</p>
      </td>
      <td className="px-6 py-4">
        <p className="text-body-md font-semibold text-primary-900">
          {formatCurrency(wallet.currentBalance)}
        </p>
        <p className="text-caption text-neutral-500">
          Available: {formatCurrency(wallet.availableBalance)}
        </p>
      </td>
      <td className="px-6 py-4">
        <div className="space-y-2 w-32">
          <div>
            <div className="flex justify-between text-caption mb-1">
              <span className="text-neutral-500">Daily</span>
              <span className="text-primary-900">{formatCurrency(wallet.dailySpent)}</span>
            </div>
            <ProgressBar value={dailyUsage} size="sm" variant={dailyUsage > 80 ? 'warning' : 'default'} />
          </div>
          <div>
            <div className="flex justify-between text-caption mb-1">
              <span className="text-neutral-500">Monthly</span>
              <span className="text-primary-900">{formatCurrency(wallet.monthlySpent)}</span>
            </div>
            <ProgressBar value={monthlyUsage} size="sm" variant={monthlyUsage > 80 ? 'warning' : 'default'} />
          </div>
        </div>
      </td>
      <td className="px-6 py-4">
        <Badge variant={status.color as any} className="flex items-center gap-1 w-fit">
          {status.icon}
          {status.label}
        </Badge>
        {!wallet.kyccVerified && (
          <p className="text-caption text-warning-600 mt-1">KYCC pending</p>
        )}
      </td>
      <td className="px-6 py-4">
        <p className="text-body-sm text-neutral-600">{wallet.transactionCount} txns</p>
        <p className="text-caption text-neutral-400">
          Last: {new Date(wallet.lastTransaction).toLocaleDateString()}
        </p>
      </td>
      <td className="px-6 py-4">
        <div className="relative">
          <button
            onClick={() => setShowActions(!showActions)}
            className="p-2 hover:bg-neutral-100 rounded-lg"
          >
            <MoreHorizontal className="w-5 h-5 text-neutral-500" />
          </button>
          {showActions && (
            <>
              <div className="fixed inset-0 z-10" onClick={() => setShowActions(false)} />
              <div className="absolute right-0 top-full mt-1 w-48 bg-white rounded-lg shadow-medium border py-1 z-20">
                <button
                  onClick={() => { onView(); setShowActions(false); }}
                  className="w-full flex items-center gap-2 px-4 py-2 text-body-sm hover:bg-neutral-50"
                >
                  <Eye className="w-4 h-4" /> View Details
                </button>
                <button
                  onClick={() => { onAction('load'); setShowActions(false); }}
                  className="w-full flex items-center gap-2 px-4 py-2 text-body-sm hover:bg-neutral-50"
                >
                  <ArrowDownRight className="w-4 h-4" /> Load Funds
                </button>
                <button
                  onClick={() => { onAction('send'); setShowActions(false); }}
                  className="w-full flex items-center gap-2 px-4 py-2 text-body-sm hover:bg-neutral-50"
                >
                  <Send className="w-4 h-4" /> Send / Transfer
                </button>
                <hr className="my-1" />
                {wallet.status === 'ACTIVE' ? (
                  <button
                    onClick={() => { onAction('suspend'); setShowActions(false); }}
                    className="w-full flex items-center gap-2 px-4 py-2 text-body-sm text-warning-600 hover:bg-warning-50"
                  >
                    <Lock className="w-4 h-4" /> Suspend Wallet
                  </button>
                ) : (
                  <button
                    onClick={() => { onAction('reactivate'); setShowActions(false); }}
                    className="w-full flex items-center gap-2 px-4 py-2 text-body-sm text-success-600 hover:bg-success-50"
                  >
                    <Unlock className="w-4 h-4" /> Reactivate
                  </button>
                )}
              </div>
            </>
          )}
        </div>
      </td>
    </tr>
  );
};

// Main Wallet Page
const WalletPage: React.FC = () => {
  const [activeView, setActiveView] = useState<'programs' | 'wallets'>('programs');
  const [searchQuery, setSearchQuery] = useState('');
  const [showIssueModal, setShowIssueModal] = useState(false);
  const [selectedProgram, setSelectedProgram] = useState<WalletProgram | null>(null);

  const tabs = [
    { id: 'programs', label: 'Programs', icon: <Settings className="w-4 h-4" />, badge: mockPrograms.length },
    { id: 'wallets', label: 'Wallets', icon: <CreditCard className="w-4 h-4" />, badge: mockWallets.length },
  ];

  const stats = {
    totalPrograms: mockPrograms.length,
    activeWallets: mockWallets.filter(w => w.status === 'ACTIVE').length,
    totalBalance: mockWallets.reduce((sum, w) => sum + w.currentBalance, 0),
    monthlyVolume: 2500000,
  };

  const filteredWallets = mockWallets.filter(w =>
    w.holderName.toLowerCase().includes(searchQuery.toLowerCase()) ||
    w.walletReference.toLowerCase().includes(searchQuery.toLowerCase())
  );

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-display-md text-primary-900">Wallet Programs</h1>
          <p className="text-body-md text-neutral-500 mt-1">
            Manage prepaid wallet programs and holder accounts
          </p>
        </div>
        <div className="flex gap-2">
          <Button variant="outline" leftIcon={<Plus className="w-4 h-4" />}>
            New Program
          </Button>
          <Button leftIcon={<CreditCard className="w-4 h-4" />} onClick={() => setShowIssueModal(true)}>
            Issue Wallet
          </Button>
        </div>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card padding="sm">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-body-sm text-neutral-500">Total Programs</p>
              <p className="text-heading-xl text-primary-900">{stats.totalPrograms}</p>
            </div>
            <div className="p-3 rounded-xl bg-primary-100">
              <Settings className="w-6 h-6 text-primary-700" />
            </div>
          </div>
        </Card>
        <Card padding="sm">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-body-sm text-neutral-500">Active Wallets</p>
              <p className="text-heading-xl text-success-600">{stats.activeWallets}</p>
            </div>
            <div className="p-3 rounded-xl bg-success-50">
              <CreditCard className="w-6 h-6 text-success-600" />
            </div>
          </div>
        </Card>
        <Card padding="sm">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-body-sm text-neutral-500">Total Balance</p>
              <p className="text-heading-xl text-primary-900">{formatCurrency(stats.totalBalance)}</p>
            </div>
            <div className="p-3 rounded-xl bg-info-50">
              <Wallet className="w-6 h-6 text-info-600" />
            </div>
          </div>
        </Card>
        <Card padding="sm">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-body-sm text-neutral-500">Monthly Volume</p>
              <p className="text-heading-xl text-primary-900">{formatCurrency(stats.monthlyVolume)}</p>
            </div>
            <div className="p-3 rounded-xl bg-accent-100">
              <TrendingUp className="w-6 h-6 text-accent-600" />
            </div>
          </div>
        </Card>
      </div>

      {/* Tabs */}
      <Tabs
        tabs={tabs}
        activeTab={activeView}
        onChange={(id) => setActiveView(id as 'programs' | 'wallets')}
        variant="pills"
      />

      {/* Content */}
      {activeView === 'programs' ? (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
          {mockPrograms.map(program => (
            <ProgramCard
              key={program.id}
              program={program}
              onClick={() => {
                setSelectedProgram(program);
                setActiveView('wallets');
              }}
            />
          ))}
        </div>
      ) : (
        <Card padding="none">
          <div className="p-4 border-b border-neutral-200">
            <div className="flex flex-col sm:flex-row gap-4">
              <div className="flex-1">
                <Input
                  placeholder="Search wallets..."
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

          {filteredWallets.length > 0 ? (
            <div className="overflow-x-auto">
              <table className="w-full">
                <thead className="bg-neutral-50 border-b border-neutral-200">
                  <tr>
                    <th className="px-6 py-3 text-left text-body-sm font-semibold text-neutral-600">Holder</th>
                    <th className="px-6 py-3 text-left text-body-sm font-semibold text-neutral-600">Wallet</th>
                    <th className="px-6 py-3 text-left text-body-sm font-semibold text-neutral-600">Balance</th>
                    <th className="px-6 py-3 text-left text-body-sm font-semibold text-neutral-600">Usage</th>
                    <th className="px-6 py-3 text-left text-body-sm font-semibold text-neutral-600">Status</th>
                    <th className="px-6 py-3 text-left text-body-sm font-semibold text-neutral-600">Activity</th>
                    <th className="px-6 py-3 text-left text-body-sm font-semibold text-neutral-600">Actions</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-neutral-100">
                  {filteredWallets.map(wallet => (
                    <WalletRow
                      key={wallet.id}
                      wallet={wallet}
                      onView={() => console.log('View wallet', wallet.id)}
                      onAction={(action) => console.log('Action', action, wallet.id)}
                    />
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <div className="p-8">
              <EmptyState
                icon={<CreditCard className="w-8 h-8" />}
                title="No wallets found"
                description="Issue a new wallet to get started"
                action={<Button onClick={() => setShowIssueModal(true)}>Issue Wallet</Button>}
              />
            </div>
          )}
        </Card>
      )}

      {/* Issue Wallet Modal */}
      <Modal
        isOpen={showIssueModal}
        onClose={() => setShowIssueModal(false)}
        title="Issue New Wallet"
        subtitle="Create a wallet for a beneficiary"
        size="md"
        footer={
          <>
            <Button variant="outline" onClick={() => setShowIssueModal(false)}>Cancel</Button>
            <Button>Issue Wallet</Button>
          </>
        }
      >
        <div className="space-y-4">
          <Input label="Select Program" placeholder="Choose a wallet program..." />
          <Input label="Holder Name" placeholder="Enter beneficiary name" />
          <Input label="Mobile Number" placeholder="+971 5XX XXX XXXX" />
          <Input label="Email (Optional)" placeholder="email@example.com" />
          <Input label="Initial Load Amount" placeholder="0.00" />
          
          <Alert variant="info">
            KYCC verification will be required for the wallet holder before transactions can be processed.
          </Alert>
        </div>
      </Modal>
    </div>
  );
};

export default WalletPage;

// Missing import
const ChevronRight = ({ className }: { className?: string }) => (
  <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
    <polyline points="9,18 15,12 9,6" />
  </svg>
);
