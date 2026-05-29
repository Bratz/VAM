import React, { useState } from 'react';
import {
  Shield,
  Plus,
  Search,
  Filter,
  ChevronRight,
  AlertTriangle,
  CheckCircle,
  Clock,
  XCircle,
  FileText,
  Users,
  DollarSign,
  Calendar,
  MoreHorizontal,
  Eye,
  Play,
  Flag,
  Download,
} from 'lucide-react';
import { Card, CardHeader, Button, Badge, Input, EmptyState } from '../ui';
import { Modal, Tabs, Stepper, ProgressBar, Avatar, Alert } from '../ui/enhanced';
import { formatCurrency, formatDate, cn } from '../../utils';

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

// Mock data
const mockContracts: EscrowContract[] = [
  {
    id: '1',
    contractReference: 'ESC-2024-001234',
    contractName: 'Commercial Property Sale - Downtown Dubai',
    sellerName: 'Emirates Real Estate LLC',
    buyerName: 'Ahmed Al Maktoum',
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
    sellerName: 'Tech Solutions FZE',
    buyerName: 'Global Corp DMCC',
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
    sellerName: 'Premium Motors LLC',
    buyerName: 'Royal Transport Co',
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
    sellerName: 'Gulf Trading Corp',
    buyerName: 'Retail Chain LLC',
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
  <Card padding="sm">
    <div className="flex items-center justify-between">
      <div>
        <p className="text-body-sm text-neutral-500">{label}</p>
        <p className="text-heading-xl text-primary-900 mt-1">{value}</p>
        {trend && (
          <p className={cn(
            'text-caption mt-1',
            trend.positive ? 'text-success-600' : 'text-error-600'
          )}>
            {trend.positive ? '↑' : '↓'} {trend.value}% vs last month
          </p>
        )}
      </div>
      <div className={cn('p-3 rounded-xl', color)}>
        {icon}
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
    <tr className="hover:bg-neutral-50 transition-colors group">
      <td className="px-6 py-4">
        <div className="flex items-center gap-3">
          <div className={cn(
            'w-10 h-10 rounded-lg flex items-center justify-center',
            contract.disputeRaised ? 'bg-error-100' : 'bg-primary-100'
          )}>
            <Shield className={cn(
              'w-5 h-5',
              contract.disputeRaised ? 'text-error-600' : 'text-primary-700'
            )} />
          </div>
          <div>
            <p className="text-body-md font-medium text-primary-900">
              {contract.contractName}
            </p>
            <p className="text-body-sm text-neutral-500">{contract.contractReference}</p>
          </div>
        </div>
      </td>
      <td className="px-6 py-4">
        <div className="space-y-1">
          <div className="flex items-center gap-2">
            <span className="text-caption text-neutral-400">Seller:</span>
            <span className="text-body-sm text-primary-900">{contract.sellerName}</span>
          </div>
          <div className="flex items-center gap-2">
            <span className="text-caption text-neutral-400">Buyer:</span>
            <span className="text-body-sm text-primary-900">{contract.buyerName}</span>
          </div>
        </div>
      </td>
      <td className="px-6 py-4">
        <p className="text-body-md font-semibold text-primary-900">
          {formatCurrency(contract.contractAmount, contract.currency)}
        </p>
        <p className="text-body-sm text-neutral-500">
          Balance: {formatCurrency(contract.escrowBalance, contract.currency)}
        </p>
      </td>
      <td className="px-6 py-4">
        <Badge variant={status.color as any} className="flex items-center gap-1.5 w-fit">
          {status.icon}
          {status.label}
        </Badge>
        {contract.disputeRaised && (
          <div className="flex items-center gap-1 mt-1 text-error-600">
            <Flag className="w-3 h-3" />
            <span className="text-caption">Dispute raised</span>
          </div>
        )}
      </td>
      <td className="px-6 py-4">
        {contract.releaseType === 'MILESTONE' ? (
          <div className="space-y-1">
            <div className="flex items-center justify-between text-caption">
              <span className="text-neutral-500">Milestones</span>
              <span className="text-primary-900 font-medium">
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
          <span className="text-body-sm text-neutral-500">Full Release</span>
        )}
      </td>
      <td className="px-6 py-4">
        <div className="flex items-center gap-2">
          <Calendar className="w-4 h-4 text-neutral-400" />
          <span className="text-body-sm text-neutral-600">
            {formatDate(contract.expiryDate)}
          </span>
        </div>
      </td>
      <td className="px-6 py-4">
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
  const [selectedContract, setSelectedContract] = useState<EscrowContract | null>(null);

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
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-display-md text-primary-900">Digital Escrow</h1>
          <p className="text-body-md text-neutral-500 mt-1">
            Manage escrow contracts and milestone releases
          </p>
        </div>
        <Button leftIcon={<Plus className="w-4 h-4" />} onClick={() => setShowCreateModal(true)}>
          New Contract
        </Button>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <StatCard
          label="Total Contract Value"
          value={formatCurrency(stats.totalValue)}
          icon={<DollarSign className="w-6 h-6 text-primary-700" />}
          color="bg-primary-100"
          trend={{ value: 15, positive: true }}
        />
        <StatCard
          label="Active Contracts"
          value={stats.activeCount}
          icon={<Play className="w-6 h-6 text-success-600" />}
          color="bg-success-50"
        />
        <StatCard
          label="Under Dispute"
          value={stats.disputedCount}
          icon={<AlertTriangle className="w-6 h-6 text-error-600" />}
          color="bg-error-50"
        />
        <StatCard
          label="Escrow Balance"
          value={formatCurrency(stats.escrowBalance)}
          icon={<Shield className="w-6 h-6 text-info-600" />}
          color="bg-info-50"
        />
      </div>

      {/* Disputed Alert */}
      {stats.disputedCount > 0 && (
        <Alert variant="warning" title="Contracts Under Dispute">
          You have {stats.disputedCount} contract(s) with active disputes requiring attention.
          <Button variant="ghost" size="sm" className="ml-2">
            View Disputes
          </Button>
        </Alert>
      )}

      {/* Contracts Table */}
      <Card padding="none">
        <div className="p-4 border-b border-neutral-200">
          <Tabs tabs={tabs} activeTab={activeTab} onChange={setActiveTab} />
        </div>
        
        <div className="p-4 border-b border-neutral-200">
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
              <thead className="bg-neutral-50 border-b border-neutral-200">
                <tr>
                  <th className="px-6 py-3 text-left text-body-sm font-semibold text-neutral-600">Contract</th>
                  <th className="px-6 py-3 text-left text-body-sm font-semibold text-neutral-600">Parties</th>
                  <th className="px-6 py-3 text-left text-body-sm font-semibold text-neutral-600">Amount</th>
                  <th className="px-6 py-3 text-left text-body-sm font-semibold text-neutral-600">Status</th>
                  <th className="px-6 py-3 text-left text-body-sm font-semibold text-neutral-600">Progress</th>
                  <th className="px-6 py-3 text-left text-body-sm font-semibold text-neutral-600">Expires</th>
                  <th className="px-6 py-3 text-left text-body-sm font-semibold text-neutral-600">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-neutral-100">
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
          <div className="p-8">
            <EmptyState
              icon={<Shield className="w-8 h-8" />}
              title="No contracts found"
              description="Create your first escrow contract to get started"
              action={
                <Button onClick={() => setShowCreateModal(true)}>
                  Create Contract
                </Button>
              }
            />
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
    </div>
  );
};

export default EscrowPage;
