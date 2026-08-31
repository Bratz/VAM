import React, { useState } from 'react';
import {
  Shield,
  Plus,
  Search,
  Filter,
  AlertTriangle,
  CheckCircle,
  Clock,
  XCircle,
  FileText,
  DollarSign,
  Calendar,
  MoreHorizontal,
  Eye,
  Play,
  Flag,
  Download,
} from 'lucide-react';
import { Card, Button, Badge, Input , StatusIconBadge } from '../components/ui';
import { Modal, Tabs, Stepper, ProgressBar, Alert } from '../components/ui/enhanced';
import { formatCurrency, formatDate, cn } from '../utils';
import { Page } from '../components/layout/Page';

// Types
interface EscrowContract {
  id: string;
  contractReference: string;
  contractName: string;
  sellerName: string;
  buyerName: string;
  contractAmount: number;
  currency: string;
  status: 'DRAFT' | 'PENDING_FUNDING' | 'FUNDED' | 'IN_PROGRESS' | 'DISPUTED' | 'COMPLETED' | 'CANCELLED';
  releaseType: 'FULL' | 'MILESTONE';
  milestonesCompleted: number;
  totalMilestones: number;
  escrowBalance: number;
  contractDate: string;
  expiryDate: string;
  disputeRaised: boolean;
}

// Mock data - Sellers and Buyers can be linked to Parties
const mockContracts: EscrowContract[] = [
  {
    id: '1',
    contractReference: 'ESC-2024-001234',
    contractName: 'Commercial Property Sale - Downtown Dubai',
    sellerName: 'Emirates Steel Industries',  // Party P-001 as Seller
    buyerName: 'Al Futtaim Group',  // Party P-002 as Buyer
    contractAmount: 5000000,
    currency: 'AED',
    status: 'IN_PROGRESS',
    releaseType: 'MILESTONE',
    milestonesCompleted: 2,
    totalMilestones: 4,
    escrowBalance: 3500000,
    contractDate: '2024-01-15',
    expiryDate: '2024-06-15',
    disputeRaised: false,
  },
  {
    id: '2',
    contractReference: 'ESC-2024-001235',
    contractName: 'IT Services Contract - Phase 1',
    sellerName: 'Gulf IT Services',  // Party P-011 as Seller
    buyerName: 'Majid Al Futtaim',  // Party P-003 as Buyer
    contractAmount: 850000,
    currency: 'AED',
    status: 'FUNDED',
    releaseType: 'MILESTONE',
    milestonesCompleted: 0,
    totalMilestones: 3,
    escrowBalance: 850000,
    contractDate: '2024-02-01',
    expiryDate: '2024-08-01',
    disputeRaised: false,
  },
  {
    id: '3',
    contractReference: 'ESC-2024-001236',
    contractName: 'Vehicle Purchase - Luxury Fleet',
    sellerName: 'Dubai Logistics LLC',  // Party P-010 as Seller
    buyerName: 'Emirates Steel Industries',  // Party P-001 as Buyer
    contractAmount: 2200000,
    currency: 'AED',
    status: 'DISPUTED',
    releaseType: 'FULL',
    milestonesCompleted: 0,
    totalMilestones: 1,
    escrowBalance: 2200000,
    contractDate: '2024-01-20',
    expiryDate: '2024-04-20',
    disputeRaised: true,
  },
  {
    id: '4',
    contractReference: 'ESC-2024-001237',
    contractName: 'Goods Supply Agreement',
    sellerName: 'Global Trading Corp',  // Party P-013 (suspended vendor)
    buyerName: 'Al Futtaim Group',  // Party P-002 as Buyer
    contractAmount: 450000,
    currency: 'AED',
    status: 'PENDING_FUNDING',
    releaseType: 'FULL',
    milestonesCompleted: 0,
    totalMilestones: 1,
    escrowBalance: 0,
    contractDate: '2024-02-10',
    expiryDate: '2024-05-10',
    disputeRaised: false,
  },
];

// Status configurations
const statusConfig: Record<EscrowContract['status'], { label: string; color: string; icon: React.ReactNode }> = {
  DRAFT: { label: 'Draft', color: 'neutral', icon: <FileText className="w-4 h-4" /> },
  PENDING_FUNDING: { label: 'Awaiting Funds', color: 'warning', icon: <Clock className="w-4 h-4" /> },
  FUNDED: { label: 'Funded', color: 'info', icon: <DollarSign className="w-4 h-4" /> },
  IN_PROGRESS: { label: 'In Progress', color: 'success', icon: <Play className="w-4 h-4" /> },
  DISPUTED: { label: 'Disputed', color: 'error', icon: <AlertTriangle className="w-4 h-4" /> },
  COMPLETED: { label: 'Completed', color: 'success', icon: <CheckCircle className="w-4 h-4" /> },
  CANCELLED: { label: 'Cancelled', color: 'neutral', icon: <XCircle className="w-4 h-4" /> },
};

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
              'text-xs mt-1',
              trend.positive ? 'text-success-600 dark:text-success-300' : 'text-error-600 dark:text-error-300'
            )}>
              {trend.positive ? '↑' : '↓'} {trend.value}% vs last month
            </p>
          )}
        </div>
        <div className={cn('w-10 h-10 rounded-xl flex items-center justify-center', color)}>
          {icon}
        </div>
      </div>
    </div>
  </Card>
);

// Contract Row
const ContractRow: React.FC<{
  contract: EscrowContract;
  onView: () => void;
}> = ({ contract, onView }) => {
  const status = statusConfig[contract.status];
  const progress = contract.totalMilestones > 0
    ? (contract.milestonesCompleted / contract.totalMilestones) * 100
    : 0;

  return (
    <tr className="hover:bg-neutral-50 transition-colors group dark:hover:bg-primary-800/50">
      <td className="px-4 py-4">
        <div className="flex items-center gap-3">
          <div className={cn(
            'w-10 h-10 rounded-xl flex items-center justify-center',
            contract.disputeRaised ? 'bg-error-100 dark:bg-error-500/20' : 'bg-primary-100 dark:bg-primary-700'
          )}>
            <Shield className={cn(
              'w-5 h-5',
              contract.disputeRaised ? 'text-error-600 dark:text-error-300' : 'text-primary-600 dark:text-primary-200'
            )} />
          </div>
          <div>
            <p className="text-sm font-medium text-neutral-900 dark:text-neutral-50">
              {contract.contractName}
            </p>
            <p className="text-xs text-neutral-500 dark:text-neutral-400">{contract.contractReference}</p>
          </div>
        </div>
      </td>
      <td className="px-4 py-4">
        <div className="space-y-1">
          <div className="flex items-center gap-2">
            <span className="text-xs text-neutral-400 uppercase tracking-wider dark:text-neutral-500">Seller:</span>
            <span className="text-sm text-neutral-900 dark:text-neutral-50">{contract.sellerName}</span>
          </div>
          <div className="flex items-center gap-2">
            <span className="text-xs text-neutral-400 uppercase tracking-wider dark:text-neutral-500">Buyer:</span>
            <span className="text-sm text-neutral-900 dark:text-neutral-50">{contract.buyerName}</span>
          </div>
        </div>
      </td>
      <td className="px-4 py-4">
        <p className="text-sm font-bold tracking-tight text-neutral-900 dark:text-neutral-50">
          {formatCurrency(contract.contractAmount, contract.currency)}
        </p>
        <p className="text-xs text-neutral-500 dark:text-neutral-400">
          Balance: {formatCurrency(contract.escrowBalance, contract.currency)}
        </p>
      </td>
      <td className="px-4 py-4">
        <Badge variant={status.color as any} className="flex items-center gap-1.5 w-fit">
          {status.icon}
          {status.label}
        </Badge>
        {contract.disputeRaised && (
          <div className="flex items-center gap-1 mt-1 text-error-600 dark:text-error-300">
            <Flag className="w-3 h-3" />
            <span className="text-xs">Dispute raised</span>
          </div>
        )}
      </td>
      <td className="px-4 py-4">
        {contract.releaseType === 'MILESTONE' ? (
          <div className="space-y-1">
            <div className="flex items-center justify-between text-xs">
              <span className="text-neutral-500 dark:text-neutral-400">Milestones</span>
              <span className="text-neutral-900 font-medium dark:text-neutral-50">
                {contract.milestonesCompleted}/{contract.totalMilestones}
              </span>
            </div>
            <ProgressBar
              value={progress}
              size="sm"
              variant={progress === 100 ? 'success' : 'default'}
            />
          </div>
        ) : (
          <span className="text-sm text-neutral-500 dark:text-neutral-400">Full Release</span>
        )}
      </td>
      <td className="px-4 py-4">
        <div className="flex items-center gap-2">
          <Calendar className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
          <span className="text-sm text-neutral-600 dark:text-neutral-300">
            {formatDate(contract.expiryDate)}
          </span>
        </div>
      </td>
      <td className="px-4 py-4">
        <div className="flex items-center gap-1">
          <Button variant="ghost" size="sm" onClick={onView}>
            <Eye className="w-4 h-4" />
          </Button>
          <Button variant="ghost" size="sm">
            <MoreHorizontal className="w-4 h-4" />
          </Button>
        </div>
      </td>
    </tr>
  );
};

// Main Page Component
const EscrowPage: React.FC = () => {
  const [searchQuery, setSearchQuery] = useState('');
  const [activeTab, setActiveTab] = useState('all');
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [, setSelectedContract] = useState<EscrowContract | null>(null);

  const tabs = [
    { id: 'all', label: 'All Contracts', badge: mockContracts.length },
    { id: 'active', label: 'Active', badge: mockContracts.filter(c => ['FUNDED', 'IN_PROGRESS'].includes(c.status)).length },
    { id: 'disputed', label: 'Disputed', badge: mockContracts.filter(c => c.disputeRaised).length },
    { id: 'completed', label: 'Completed', badge: mockContracts.filter(c => c.status === 'COMPLETED').length },
  ];

  const filteredContracts = mockContracts.filter(contract => {
    const matchesSearch = contract.contractName.toLowerCase().includes(searchQuery.toLowerCase()) ||
                         contract.contractReference.toLowerCase().includes(searchQuery.toLowerCase());
    const matchesTab = activeTab === 'all' ||
                      (activeTab === 'active' && ['FUNDED', 'IN_PROGRESS'].includes(contract.status)) ||
                      (activeTab === 'disputed' && contract.disputeRaised) ||
                      (activeTab === 'completed' && contract.status === 'COMPLETED');
    return matchesSearch && matchesTab;
  });

  const stats = {
    totalValue: mockContracts.reduce((sum, c) => sum + c.contractAmount, 0),
    activeCount: mockContracts.filter(c => ['FUNDED', 'IN_PROGRESS'].includes(c.status)).length,
    disputedCount: mockContracts.filter(c => c.disputeRaised).length,
    escrowBalance: mockContracts.reduce((sum, c) => sum + c.escrowBalance, 0),
  };

  return (
    <Page>
      {/* Quick Actions */}
      <div className="flex items-center justify-end gap-2 animate-fade-in" style={{ animationDelay: '0.05s' }}>
        <Button leftIcon={<Plus className="w-4 h-4" />} onClick={() => setShowCreateModal(true)}>
          New Contract
        </Button>
      </div>

      {/* Info Banner */}
      <Card padding="sm" className="bg-gradient-to-r from-success-50/50 via-white to-success-50/50 border-success-200/60 animate-fade-in dark:from-success-500/10 dark:via-primary-900 dark:to-success-500/10 dark:border-success-500/30 dark:from-primary-900 dark:to-primary-900" style={{ animationDelay: '0.1s' }}>
        <div className="flex items-start gap-3 p-4">
          <StatusIconBadge tone="success" icon={Shield} className="flex-shrink-0 dark:bg-success-500/20" />
          <div>
            <p className="text-sm font-semibold text-success-800 dark:text-success-300">Secure Transaction Holding</p>
            <p className="text-sm text-success-700 mt-1 dark:text-success-300">
              Digital escrow provides secure holding of funds for transactions between buyers and sellers.
              Funds are released upon milestone completion or through dispute resolution. Both parties are
              linked to the <strong>Parties</strong> master for unified KYC compliance.
            </p>
          </div>
        </div>
      </Card>

      {/* Stats */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4 animate-fade-in" style={{ animationDelay: '0.15s' }}>
        <StatCard
          label="Total Contract Value"
          value={formatCurrency(stats.totalValue)}
          icon={<DollarSign className="w-5 h-5 text-primary-600 dark:text-primary-200" />}
          color="bg-primary-100 dark:bg-primary-700"
          trend={{ value: 15, positive: true }}
        />
        <StatCard
          label="Active Contracts"
          value={stats.activeCount}
          icon={<Play className="w-5 h-5 text-success-600 dark:text-success-300" />}
          color="bg-success-100 dark:bg-success-500/20"
        />
        <StatCard
          label="Under Dispute"
          value={stats.disputedCount}
          icon={<AlertTriangle className="w-5 h-5 text-error-600 dark:text-error-300" />}
          color="bg-error-100 dark:bg-error-500/20"
        />
        <StatCard
          label="Escrow Balance"
          value={formatCurrency(stats.escrowBalance)}
          icon={<Shield className="w-5 h-5 text-info-600 dark:text-info-300" />}
          color="bg-info-100 dark:bg-info-500/20"
        />
      </div>

      {/* Disputed Alert */}
      {stats.disputedCount > 0 && (
        <div className="animate-fade-in" style={{ animationDelay: '0.2s' }}>
          <Alert variant="warning" title="Contracts Under Dispute">
            You have {stats.disputedCount} contract(s) with active disputes requiring attention.
            <Button variant="ghost" size="sm" className="ml-2">
              View Disputes
            </Button>
          </Alert>
        </div>
      )}

      {/* Contracts Table */}
      <Card padding="none" className="animate-fade-in" style={{ animationDelay: '0.2s' }}>
        <div className="p-4 border-b border-neutral-200 dark:border-primary-800">
          <Tabs tabs={tabs} activeTab={activeTab} onChange={setActiveTab} />
        </div>

        <div className="p-4 border-b border-neutral-200 dark:border-primary-800">
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
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead className="bg-neutral-50 border-b border-neutral-100 dark:bg-primary-950 dark:border-primary-800/60">
                <tr>
                  <th className="px-4 py-3 text-left label">Contract</th>
                  <th className="px-4 py-3 text-left label">Parties</th>
                  <th className="px-4 py-3 text-left label">Amount</th>
                  <th className="px-4 py-3 text-left label">Status</th>
                  <th className="px-4 py-3 text-left label">Progress</th>
                  <th className="px-4 py-3 text-left label">Expires</th>
                  <th className="px-4 py-3 text-left label">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-neutral-100 dark:divide-primary-800/60">
                {filteredContracts.map(contract => (
                  <ContractRow
                    key={contract.id}
                    contract={contract}
                    onView={() => setSelectedContract(contract)}
                  />
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <div className="flex flex-col items-center justify-center py-16">
            <StatusIconBadge tone="neutral" icon={Shield} size="lg" className="mb-4 dark:bg-primary-800" />
            <p className="text-sm font-medium text-neutral-900 mb-1 dark:text-neutral-50">No contracts found</p>
            <p className="text-xs text-neutral-500 mb-4 dark:text-neutral-400">Create your first escrow contract to get started</p>
            <Button size="sm" onClick={() => setShowCreateModal(true)}>
              Create Contract
            </Button>
          </div>
        )}
      </Card>

      {/* Create Contract Modal */}
      <Modal
        isOpen={showCreateModal}
        onClose={() => setShowCreateModal(false)}
        title="Create Escrow Contract"
        subtitle="Set up a new digital escrow arrangement"
        size="lg"
        footer={
          <>
            <Button variant="outline" onClick={() => setShowCreateModal(false)}>
              Cancel
            </Button>
            <Button>Create Contract</Button>
          </>
        }
      >
        <div className="space-y-6">
          <Stepper
            steps={[
              { id: 'parties', title: 'Parties' },
              { id: 'terms', title: 'Terms' },
              { id: 'milestones', title: 'Milestones' },
              { id: 'review', title: 'Review' },
            ]}
            currentStep={0}
            variant="horizontal"
          />
          
          <div className="grid grid-cols-2 gap-4 mt-6">
            <Input label="Contract Name" placeholder="Enter contract name" />
            <Input label="Contract Type" placeholder="Select type" />
            <Input label="Seller (Corporate)" placeholder="Search seller..." />
            <Input label="Buyer (Beneficiary)" placeholder="Search buyer..." />
            <Input label="Contract Amount" placeholder="0.00" />
            <Input label="Currency" placeholder="AED" />
          </div>
        </div>
      </Modal>
    </Page>
  );
};

export default EscrowPage;