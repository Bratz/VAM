import React, { useState, useEffect, useCallback } from 'react';
import {
  Wallet, Plus, Search, ArrowUpRight, ArrowDownRight, ArrowLeftRight,
  Users, TrendingUp, MoreHorizontal, Eye, RefreshCw, Send, Download,
  Loader2, AlertCircle, CheckCircle, Building2, CreditCard, Banknote,
  Smartphone, QrCode, Store, Receipt, Clock, Filter, Calendar,
  ChevronDown, ChevronUp, Copy, Printer, FileText, Upload, X,
} from 'lucide-react';
import { Card, Button, Input, Badge, EmptyState , StatusIconBadge } from '../components/ui';
import { Modal, Tabs, ProgressBar, Alert, Avatar } from '../components/ui/enhanced';
import { cn, formatCurrency, formatDate } from '../utils';
import { Page } from '../components/layout/Page';

// ============================================================================
// Types
// ============================================================================

interface CashOperation {
  id: string;
  type: 'CASH_IN' | 'CASH_OUT' | 'TOP_UP' | 'WITHDRAWAL' | 'TRANSFER' | 'AGENT_DEPOSIT' | 'AGENT_PAYOUT';
  walletId: string;
  walletReference: string;
  walletHolderName: string;
  amount: number;
  fee: number;
  netAmount: number;
  currency: string;
  status: 'PENDING' | 'COMPLETED' | 'FAILED' | 'REVERSED';
  channel: 'AGENT' | 'BANK_TRANSFER' | 'CARD' | 'VIBAN' | 'ATM' | 'POS' | 'MOBILE';
  agentId?: string;
  agentName?: string;
  reference: string;
  description?: string;
  previousBalance: number;
  newBalance: number;
  createdAt: string;
  completedAt?: string;
  createdBy: string;
}

interface Agent {
  id: string;
  agentCode: string;
  agentName: string;
  agentType: 'RETAIL' | 'CORPORATE' | 'SUPER_AGENT';
  location: string;
  status: 'ACTIVE' | 'SUSPENDED';
  floatBalance: number;
  dailyLimit: number;
  dailyUsed: number;
  transactionCount: number;
}

interface CashStats {
  todayCashIn: number;
  todayCashOut: number;
  todayTopUp: number;
  todayWithdrawal: number;
  todayNetFlow: number;
  todayTransactions: number;
  pendingApprovals: number;
  activeAgents: number;
}

// ============================================================================
// API Service
// ============================================================================

const API_BASE = '/api/v1/wallets';

const cashApi = {
  // Get cash operation stats
  getStats: async (): Promise<CashStats> => {
    // Demo data
    return {
      todayCashIn: 2500000,
      todayCashOut: 1800000,
      todayTopUp: 3200000,
      todayWithdrawal: 2100000,
      todayNetFlow: 1800000,
      todayTransactions: 4521,
      pendingApprovals: 12,
      activeAgents: 45,
    };
  },

  // Get recent operations
  getOperations: async (filters: any): Promise<{ content: CashOperation[]; totalElements: number }> => {
    // Demo data
    return {
      content: demoOperations,
      totalElements: 156,
    };
  },

  // Get agents
  getAgents: async (): Promise<Agent[]> => {
    return demoAgents;
  },

  // Process cash-in
  processCashIn: async (data: any) => {
    const response = await fetch(`${API_BASE}/${data.walletId}/load`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        amount: data.amount,
        source: data.channel,
        sourceReference: data.reference,
        description: data.description,
      }),
    });
    if (!response.ok) throw new Error('Cash-in failed');
    return response.json();
  },

  // Process cash-out
  processCashOut: async (data: any) => {
    const response = await fetch(`${API_BASE}/${data.walletId}/withdraw`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        amount: data.amount,
        destination: data.channel,
        destinationReference: data.reference,
        description: data.description,
      }),
    });
    if (!response.ok) throw new Error('Cash-out failed');
    return response.json();
  },

  // Process transfer
  processTransfer: async (data: any) => {
    const response = await fetch(`${API_BASE}/${data.fromWalletId}/transfer`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        toWalletReference: data.toWalletReference,
        amount: data.amount,
        description: data.description,
      }),
    });
    if (!response.ok) throw new Error('Transfer failed');
    return response.json();
  },

  // Lookup wallet
  lookupWallet: async (reference: string) => {
    const response = await fetch(`${API_BASE}/reference/${reference}`);
    if (!response.ok) throw new Error('Wallet not found');
    return response.json();
  },

  // Bulk load
  bulkLoad: async (data: any) => {
    const response = await fetch(`${API_BASE}/bulk-load`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data),
    });
    if (!response.ok) throw new Error('Bulk load failed');
    return response.json();
  },
};

// ============================================================================
// Demo Data
// ============================================================================

const demoOperations: CashOperation[] = [
  {
    id: '1',
    type: 'CASH_IN',
    walletId: 'w1',
    walletReference: 'WAL-FINTA-00012345',
    walletHolderName: 'Mohammed Al Rashid',
    amount: 5000,
    fee: 0,
    netAmount: 5000,
    currency: 'AED',
    status: 'COMPLETED',
    channel: 'AGENT',
    agentId: 'ag1',
    agentName: 'Mall of Emirates Kiosk',
    reference: 'CIN-2024-001234',
    previousBalance: 25000,
    newBalance: 30000,
    createdAt: '2024-02-12T14:30:00Z',
    completedAt: '2024-02-12T14:30:05Z',
    createdBy: 'Agent User',
  },
  {
    id: '2',
    type: 'CASH_OUT',
    walletId: 'w2',
    walletReference: 'WAL-FINTA-00012346',
    walletHolderName: 'Sarah Ahmed',
    amount: 2000,
    fee: 5,
    netAmount: 1995,
    currency: 'AED',
    status: 'COMPLETED',
    channel: 'AGENT',
    agentId: 'ag2',
    agentName: 'Dubai Mall Branch',
    reference: 'COT-2024-001235',
    previousBalance: 12000,
    newBalance: 10000,
    createdAt: '2024-02-12T14:25:00Z',
    completedAt: '2024-02-12T14:25:10Z',
    createdBy: 'Agent User',
  },
  {
    id: '3',
    type: 'TOP_UP',
    walletId: 'w3',
    walletReference: 'WAL-FINTA-00012347',
    walletHolderName: 'Fatima Al Ali',
    amount: 10000,
    fee: 0,
    netAmount: 10000,
    currency: 'AED',
    status: 'COMPLETED',
    channel: 'BANK_TRANSFER',
    reference: 'TOP-2024-001236',
    previousBalance: 3500,
    newBalance: 13500,
    createdAt: '2024-02-12T14:20:00Z',
    completedAt: '2024-02-12T14:20:30Z',
    createdBy: 'System',
  },
  {
    id: '4',
    type: 'WITHDRAWAL',
    walletId: 'w4',
    walletReference: 'WAL-FINTB-00005001',
    walletHolderName: 'Tech Solutions LLC',
    amount: 50000,
    fee: 25,
    netAmount: 49975,
    currency: 'AED',
    status: 'PENDING',
    channel: 'BANK_TRANSFER',
    reference: 'WTH-2024-001237',
    previousBalance: 850000,
    newBalance: 800000,
    createdAt: '2024-02-12T14:15:00Z',
    createdBy: 'Admin User',
  },
  {
    id: '5',
    type: 'TRANSFER',
    walletId: 'w1',
    walletReference: 'WAL-FINTA-00012345',
    walletHolderName: 'Mohammed Al Rashid',
    amount: 1500,
    fee: 0,
    netAmount: 1500,
    currency: 'AED',
    status: 'COMPLETED',
    channel: 'MOBILE',
    reference: 'TRF-2024-001238',
    description: 'Payment to Sarah',
    previousBalance: 30000,
    newBalance: 28500,
    createdAt: '2024-02-12T14:10:00Z',
    completedAt: '2024-02-12T14:10:02Z',
    createdBy: 'Mobile App',
  },
  {
    id: '6',
    type: 'AGENT_DEPOSIT',
    walletId: 'w5',
    walletReference: 'WAL-AGENT-001',
    walletHolderName: 'Super Agent Float',
    amount: 100000,
    fee: 0,
    netAmount: 100000,
    currency: 'AED',
    status: 'COMPLETED',
    channel: 'BANK_TRANSFER',
    agentId: 'ag3',
    agentName: 'Super Agent - Dubai',
    reference: 'AGD-2024-001239',
    previousBalance: 500000,
    newBalance: 600000,
    createdAt: '2024-02-12T14:00:00Z',
    completedAt: '2024-02-12T14:00:15Z',
    createdBy: 'Treasury',
  },
];

const demoAgents: Agent[] = [
  { id: 'ag1', agentCode: 'AGT-001', agentName: 'Mall of Emirates Kiosk', agentType: 'RETAIL', location: 'Dubai - MOE', status: 'ACTIVE', floatBalance: 75000, dailyLimit: 100000, dailyUsed: 45000, transactionCount: 156 },
  { id: 'ag2', agentCode: 'AGT-002', agentName: 'Dubai Mall Branch', agentType: 'RETAIL', location: 'Dubai - DM', status: 'ACTIVE', floatBalance: 120000, dailyLimit: 150000, dailyUsed: 78000, transactionCount: 234 },
  { id: 'ag3', agentCode: 'AGT-003', agentName: 'Super Agent - Dubai', agentType: 'SUPER_AGENT', location: 'Dubai - DIFC', status: 'ACTIVE', floatBalance: 600000, dailyLimit: 1000000, dailyUsed: 350000, transactionCount: 45 },
  { id: 'ag4', agentCode: 'AGT-004', agentName: 'Abu Dhabi Central', agentType: 'RETAIL', location: 'Abu Dhabi', status: 'ACTIVE', floatBalance: 85000, dailyLimit: 100000, dailyUsed: 62000, transactionCount: 189 },
  { id: 'ag5', agentCode: 'AGT-005', agentName: 'Sharjah City Center', agentType: 'RETAIL', location: 'Sharjah', status: 'SUSPENDED', floatBalance: 25000, dailyLimit: 75000, dailyUsed: 0, transactionCount: 0 },
];

// ============================================================================
// Operation Type Config
// ============================================================================

const operationTypeConfig: Record<string, { label: string; color: string; icon: React.ReactNode; direction: 'in' | 'out' }> = {
  CASH_IN: { label: 'Cash In', color: 'success', icon: <ArrowDownRight className="w-4 h-4" />, direction: 'in' },
  CASH_OUT: { label: 'Cash Out', color: 'error', icon: <ArrowUpRight className="w-4 h-4" />, direction: 'out' },
  TOP_UP: { label: 'Top Up', color: 'success', icon: <ArrowDownRight className="w-4 h-4" />, direction: 'in' },
  WITHDRAWAL: { label: 'Withdrawal', color: 'error', icon: <ArrowUpRight className="w-4 h-4" />, direction: 'out' },
  TRANSFER: { label: 'Transfer', color: 'info', icon: <ArrowLeftRight className="w-4 h-4" />, direction: 'out' },
  AGENT_DEPOSIT: { label: 'Agent Deposit', color: 'success', icon: <ArrowDownRight className="w-4 h-4" />, direction: 'in' },
  AGENT_PAYOUT: { label: 'Agent Payout', color: 'error', icon: <ArrowUpRight className="w-4 h-4" />, direction: 'out' },
};

const channelConfig: Record<string, { label: string; icon: React.ReactNode }> = {
  AGENT: { label: 'Agent', icon: <Store className="w-4 h-4" /> },
  BANK_TRANSFER: { label: 'Bank Transfer', icon: <Building2 className="w-4 h-4" /> },
  CARD: { label: 'Card', icon: <CreditCard className="w-4 h-4" /> },
  VIBAN: { label: 'VIBAN', icon: <QrCode className="w-4 h-4" /> },
  ATM: { label: 'ATM', icon: <Banknote className="w-4 h-4" /> },
  POS: { label: 'POS', icon: <Receipt className="w-4 h-4" /> },
  MOBILE: { label: 'Mobile', icon: <Smartphone className="w-4 h-4" /> },
};

const statusConfig: Record<string, { label: string; color: string }> = {
  PENDING: { label: 'Pending', color: 'warning' },
  COMPLETED: { label: 'Completed', color: 'success' },
  FAILED: { label: 'Failed', color: 'error' },
  REVERSED: { label: 'Reversed', color: 'neutral' },
};

// ============================================================================
// Stat Card Component
// ============================================================================

const StatCard: React.FC<{
  label: string;
  value: string;
  subValue?: string;
  icon: React.ReactNode;
  iconBg: string;
  trend?: { value: number; direction: 'up' | 'down' };
  delay?: number;
}> = ({ label, value, subValue, icon, iconBg, trend, delay = 0 }) => (
  <Card hover className="animate-fade-in" style={{ animationDelay: `${delay}s` }}>
    <div className="flex items-center justify-between">
      <div>
        <p className="text-sm text-neutral-500 dark:text-neutral-400">{label}</p>
        <p className="stat-value-sm mt-1">{value}</p>
        {subValue && <p className="text-xs text-neutral-400 dark:text-neutral-500 mt-0.5">{subValue}</p>}
        {trend && (
          <div className={cn("flex items-center gap-1 text-xs mt-1", trend.direction === 'up' ? "text-success-600 dark:text-success-300" : "text-error-600 dark:text-error-300")}>
            {trend.direction === 'up' ? <ChevronUp className="w-3 h-3" /> : <ChevronDown className="w-3 h-3" />}
            <span>{trend.value}% vs yesterday</span>
          </div>
        )}
      </div>
      <div className={cn("w-12 h-12 rounded-xl flex items-center justify-center", iconBg)}>{icon}</div>
    </div>
  </Card>
);

// ============================================================================
// Main Component
// ============================================================================

const BaaSCashOperationsPage: React.FC = () => {
  // State
  const [activeTab, setActiveTab] = useState<'operations' | 'agents' | 'bulk'>('operations');
  const [stats, setStats] = useState<CashStats | null>(null);
  const [operations, setOperations] = useState<CashOperation[]>([]);
  const [agents, setAgents] = useState<Agent[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  // Filters
  const [typeFilter, setTypeFilter] = useState<string>('');
  const [statusFilter, setStatusFilter] = useState<string>('');
  const [channelFilter, setChannelFilter] = useState<string>('');
  const [searchQuery, setSearchQuery] = useState('');
  const [dateRange, setDateRange] = useState<string>('today');

  // Modals
  const [showCashInModal, setShowCashInModal] = useState(false);
  const [showCashOutModal, setShowCashOutModal] = useState(false);
  const [showTransferModal, setShowTransferModal] = useState(false);
  const [showBulkLoadModal, setShowBulkLoadModal] = useState(false);
  const [showDetailModal, setShowDetailModal] = useState(false);
  const [selectedOperation, setSelectedOperation] = useState<CashOperation | null>(null);

  // Forms
  const [cashInForm, setCashInForm] = useState({
    walletReference: '',
    amount: '',
    channel: 'AGENT',
    agentId: '',
    reference: '',
    description: '',
  });
  const [cashOutForm, setCashOutForm] = useState({
    walletReference: '',
    amount: '',
    channel: 'AGENT',
    agentId: '',
    reference: '',
    description: '',
  });
  const [transferForm, setTransferForm] = useState({
    fromWalletReference: '',
    toWalletReference: '',
    amount: '',
    description: '',
  });
  const [bulkLoadForm, setBulkLoadForm] = useState({
    programId: '',
    csvData: '',
    description: '',
  });
  const [walletLookup, setWalletLookup] = useState<any>(null);
  const [lookupLoading, setLookupLoading] = useState(false);
  const [actionLoading, setActionLoading] = useState(false);

  // Tabs
  const tabs = [
    { id: 'operations', label: 'Cash Operations', icon: <Banknote className="w-4 h-4" />, badge: operations.length },
    { id: 'agents', label: 'Agent Network', icon: <Store className="w-4 h-4" />, badge: agents.filter(a => a.status === 'ACTIVE').length },
    { id: 'bulk', label: 'Bulk Operations', icon: <Upload className="w-4 h-4" /> },
  ];

  // Load data
  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const [statsData, opsData, agentsData] = await Promise.all([
        cashApi.getStats(),
        cashApi.getOperations({ type: typeFilter, status: statusFilter, channel: channelFilter }),
        cashApi.getAgents(),
      ]);
      setStats(statsData);
      setOperations(opsData.content);
      setAgents(agentsData);
    } catch (err) {
      setError('Failed to load data');
    } finally {
      setLoading(false);
    }
  }, [typeFilter, statusFilter, channelFilter]);

  useEffect(() => { loadData(); }, [loadData]);

  // Helpers
  const showSuccess = (msg: string) => { setSuccess(msg); setTimeout(() => setSuccess(null), 3000); };
  const showError = (msg: string) => { setError(msg); };

  // Wallet lookup
  const handleWalletLookup = async (reference: string, formType: 'cashIn' | 'cashOut' | 'transfer') => {
    if (!reference || reference.length < 10) return;
    setLookupLoading(true);
    try {
      const result = await cashApi.lookupWallet(reference);
      setWalletLookup(result.data);
    } catch (err) {
      setWalletLookup(null);
      showError('Wallet not found');
    } finally {
      setLookupLoading(false);
    }
  };

  // Process Cash In
  const handleCashIn = async () => {
    if (!cashInForm.walletReference || !cashInForm.amount) {
      showError('Please fill required fields');
      return;
    }
    setActionLoading(true);
    try {
      await cashApi.processCashIn({
        walletId: walletLookup?.id,
        walletReference: cashInForm.walletReference,
        amount: parseFloat(cashInForm.amount),
        channel: cashInForm.channel,
        reference: cashInForm.reference,
        description: cashInForm.description,
      });
      showSuccess('Cash-in completed successfully!');
      setShowCashInModal(false);
      setCashInForm({ walletReference: '', amount: '', channel: 'AGENT', agentId: '', reference: '', description: '' });
      setWalletLookup(null);
      loadData();
    } catch (err: any) {
      showError(err.message || 'Cash-in failed');
    } finally {
      setActionLoading(false);
    }
  };

  // Process Cash Out
  const handleCashOut = async () => {
    if (!cashOutForm.walletReference || !cashOutForm.amount) {
      showError('Please fill required fields');
      return;
    }
    setActionLoading(true);
    try {
      await cashApi.processCashOut({
        walletId: walletLookup?.id,
        walletReference: cashOutForm.walletReference,
        amount: parseFloat(cashOutForm.amount),
        channel: cashOutForm.channel,
        reference: cashOutForm.reference,
        description: cashOutForm.description,
      });
      showSuccess('Cash-out completed successfully!');
      setShowCashOutModal(false);
      setCashOutForm({ walletReference: '', amount: '', channel: 'AGENT', agentId: '', reference: '', description: '' });
      setWalletLookup(null);
      loadData();
    } catch (err: any) {
      showError(err.message || 'Cash-out failed');
    } finally {
      setActionLoading(false);
    }
  };

  // Process Transfer
  const handleTransfer = async () => {
    if (!transferForm.fromWalletReference || !transferForm.toWalletReference || !transferForm.amount) {
      showError('Please fill required fields');
      return;
    }
    setActionLoading(true);
    try {
      await cashApi.processTransfer({
        fromWalletId: walletLookup?.id,
        toWalletReference: transferForm.toWalletReference,
        amount: parseFloat(transferForm.amount),
        description: transferForm.description,
      });
      showSuccess('Transfer completed successfully!');
      setShowTransferModal(false);
      setTransferForm({ fromWalletReference: '', toWalletReference: '', amount: '', description: '' });
      setWalletLookup(null);
      loadData();
    } catch (err: any) {
      showError(err.message || 'Transfer failed');
    } finally {
      setActionLoading(false);
    }
  };

  // Process Bulk Load
  const handleBulkLoad = async () => {
    if (!bulkLoadForm.csvData) {
      showError('Please enter wallet data');
      return;
    }
    const lines = bulkLoadForm.csvData.trim().split('\n');
    const loads = lines.map(line => {
      const [walletReference, amountStr] = line.split(',').map(s => s.trim());
      return { walletReference, amount: parseFloat(amountStr) };
    }).filter(item => item.walletReference && !isNaN(item.amount));

    if (loads.length === 0) {
      showError('No valid entries found');
      return;
    }

    setActionLoading(true);
    try {
      const result = await cashApi.bulkLoad({
        programId: bulkLoadForm.programId || undefined,
        loads,
        description: bulkLoadForm.description,
      });
      showSuccess(`Bulk load: ${result.data?.successCount || loads.length}/${loads.length} successful`);
      setShowBulkLoadModal(false);
      setBulkLoadForm({ programId: '', csvData: '', description: '' });
      loadData();
    } catch (err: any) {
      showError(err.message || 'Bulk load failed');
    } finally {
      setActionLoading(false);
    }
  };

  // Filter operations
  const filteredOperations = operations.filter(op => {
    if (typeFilter && op.type !== typeFilter) return false;
    if (statusFilter && op.status !== statusFilter) return false;
    if (channelFilter && op.channel !== channelFilter) return false;
    if (searchQuery) {
      const q = searchQuery.toLowerCase();
      return op.walletReference.toLowerCase().includes(q) ||
        op.walletHolderName.toLowerCase().includes(q) ||
        op.reference.toLowerCase().includes(q);
    }
    return true;
  });

  return (
    <Page>
      {/* Success/Error Messages */}
      {success && (
        <Alert variant="success" className="flex items-center gap-2">
          <CheckCircle className="w-4 h-4" />{success}
        </Alert>
      )}
      {error && (
        <Alert variant="error" className="flex items-center gap-2">
          <AlertCircle className="w-4 h-4" />{error}
          <button onClick={() => setError(null)} className="ml-auto text-sm underline">Dismiss</button>
        </Alert>
      )}

      {/* Quick Actions */}
      <div className="flex items-center justify-end gap-2 animate-fade-in" style={{ animationDelay: '0.05s' }}>
        <Button variant="outline" leftIcon={<RefreshCw className="w-4 h-4" />} onClick={loadData} disabled={loading}>
          Refresh
        </Button>
        <Button variant="outline" leftIcon={<Download className="w-4 h-4" />}>
          Export
        </Button>
        <Button variant="success" leftIcon={<ArrowDownRight className="w-4 h-4" />} onClick={() => setShowCashInModal(true)}>
          Cash In / Top-up
        </Button>
        <Button variant="danger" leftIcon={<ArrowUpRight className="w-4 h-4" />} onClick={() => setShowCashOutModal(true)}>
          Cash Out
        </Button>
        <Button leftIcon={<ArrowLeftRight className="w-4 h-4" />} onClick={() => setShowTransferModal(true)}>
          Transfer
        </Button>
      </div>

      {/* Stats Cards */}
      {stats && (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
          <StatCard
            label="Today's Cash In"
            value={formatCurrency(stats.todayCashIn)}
            subValue="Deposits & Top-ups"
            icon={<ArrowDownRight className="w-6 h-6 text-success-600 dark:text-success-300" />}
            iconBg="bg-success-100 dark:bg-success-500/20"
            trend={{ value: 15, direction: 'up' }}
            delay={0.1}
          />
          <StatCard
            label="Today's Cash Out"
            value={formatCurrency(stats.todayCashOut)}
            subValue="Withdrawals & Payouts"
            icon={<ArrowUpRight className="w-6 h-6 text-error-600 dark:text-error-300" />}
            iconBg="bg-error-100 dark:bg-error-500/20"
            trend={{ value: 8, direction: 'up' }}
            delay={0.15}
          />
          <StatCard
            label="Net Flow"
            value={formatCurrency(stats.todayNetFlow)}
            subValue={`${stats.todayTransactions.toLocaleString()} transactions`}
            icon={<TrendingUp className="w-6 h-6 text-primary-600 dark:text-primary-200" />}
            iconBg="bg-primary-100 dark:bg-primary-700"
            delay={0.2}
          />
          <StatCard
            label="Active Agents"
            value={stats.activeAgents.toString()}
            subValue={`${stats.pendingApprovals} pending approvals`}
            icon={<Store className="w-6 h-6 text-accent-600 dark:text-accent-300" />}
            iconBg="bg-accent-100 dark:bg-accent-500/20"
            delay={0.25}
          />
        </div>
      )}

      {/* Quick Action Cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <button
          onClick={() => setShowCashInModal(true)}
          className="p-4 bg-success-50 dark:bg-success-500/10 hover:bg-success-100 border border-success-200 rounded-xl transition-all hover:shadow-md text-left animate-fade-in dark:hover:bg-success-500/20 dark:border-success-500/30"
          style={{ animationDelay: '0.3s' }}
        >
          <StatusIconBadge tone="success" icon={ArrowDownRight} size="lg" className="mb-3" />
          <p className="font-semibold text-success-900">Cash In / Top-up</p>
          <p className="text-sm text-success-600 dark:text-success-300">Add funds to wallet</p>
        </button>
        <button
          onClick={() => setShowCashOutModal(true)}
          className="p-4 bg-error-50 dark:bg-error-500/10 hover:bg-error-100 border border-error-200 rounded-xl transition-all hover:shadow-md text-left animate-fade-in dark:hover:bg-error-500/20 dark:border-error-500/30"
          style={{ animationDelay: '0.35s' }}
        >
          <StatusIconBadge tone="error" icon={ArrowUpRight} size="lg" className="mb-3" />
          <p className="font-semibold text-error-900">Cash Out</p>
          <p className="text-sm text-error-600 dark:text-error-300">Withdraw from wallet</p>
        </button>
        <button
          onClick={() => setShowTransferModal(true)}
          className="p-4 bg-info-50 dark:bg-info-500/10 hover:bg-info-100 dark:bg-info-50 dark:bg-info-500/20 border border-info-200 rounded-xl transition-all hover:shadow-md text-left animate-fade-in dark:hover:bg-info-500/20 dark:border-info-500/30"
          style={{ animationDelay: '0.4s' }}
        >
          <div className="w-12 h-12 rounded-xl bg-info-100 dark:bg-info-50 dark:bg-info-500/20 flex items-center justify-center mb-3 dark:bg-info-500/20">
            <ArrowLeftRight className="w-6 h-6 text-info-600 dark:text-info-300" />
          </div>
          <p className="font-semibold text-info-900">Transfer</p>
          <p className="text-sm text-info-600 dark:text-info-300">Wallet to wallet</p>
        </button>
        <button
          onClick={() => setShowBulkLoadModal(true)}
          className="p-4 bg-accent-50 hover:bg-accent-100 border border-accent-200 rounded-xl transition-all hover:shadow-md text-left animate-fade-in dark:bg-accent-500/10 dark:hover:bg-accent-500/20 dark:border-accent-500/30"
          style={{ animationDelay: '0.45s' }}
        >
          <StatusIconBadge tone="accent" icon={Upload} size="lg" className="mb-3 dark:bg-accent-500/20" />
          <p className="font-semibold text-accent-900">Bulk Load</p>
          <p className="text-sm text-accent-600 dark:text-accent-300">Multiple wallets</p>
        </button>
      </div>

      {/* Tabs */}
      <Tabs tabs={tabs} activeTab={activeTab} onChange={(id) => setActiveTab(id as typeof activeTab)} variant="pills" />

      {/* Operations Tab */}
      {activeTab === 'operations' && (
        <Card padding="none" className="animate-fade-in" style={{ animationDelay: '0.5s' }}>
          {/* Filters */}
          <div className="p-4 border-b border-neutral-200 dark:border-primary-800">
            <div className="flex flex-col md:flex-row gap-4">
              <div className="flex-1">
                <Input
                  placeholder="Search by wallet, holder, or reference..."
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  leftIcon={<Search className="w-4 h-4" />}
                />
              </div>
              <div className="flex gap-2 flex-wrap">
                <select
                  className="border border-neutral-300 dark:border-primary-700 rounded-lg px-3 py-2 text-sm"
                  value={typeFilter}
                  onChange={(e) => setTypeFilter(e.target.value)}
                >
                  <option value="">All Types</option>
                  <option value="CASH_IN">Cash In</option>
                  <option value="CASH_OUT">Cash Out</option>
                  <option value="TOP_UP">Top Up</option>
                  <option value="WITHDRAWAL">Withdrawal</option>
                  <option value="TRANSFER">Transfer</option>
                </select>
                <select
                  className="border border-neutral-300 dark:border-primary-700 rounded-lg px-3 py-2 text-sm"
                  value={statusFilter}
                  onChange={(e) => setStatusFilter(e.target.value)}
                >
                  <option value="">All Status</option>
                  <option value="COMPLETED">Completed</option>
                  <option value="PENDING">Pending</option>
                  <option value="FAILED">Failed</option>
                </select>
                <select
                  className="border border-neutral-300 dark:border-primary-700 rounded-lg px-3 py-2 text-sm"
                  value={channelFilter}
                  onChange={(e) => setChannelFilter(e.target.value)}
                >
                  <option value="">All Channels</option>
                  <option value="AGENT">Agent</option>
                  <option value="BANK_TRANSFER">Bank Transfer</option>
                  <option value="CARD">Card</option>
                  <option value="MOBILE">Mobile</option>
                </select>
                <select
                  className="border border-neutral-300 dark:border-primary-700 rounded-lg px-3 py-2 text-sm"
                  value={dateRange}
                  onChange={(e) => setDateRange(e.target.value)}
                >
                  <option value="today">Today</option>
                  <option value="week">This Week</option>
                  <option value="month">This Month</option>
                  <option value="all">All Time</option>
                </select>
              </div>
            </div>
          </div>

          {/* Operations Table */}
          {loading ? (
            <div className="flex items-center justify-center py-12">
              <Loader2 className="w-8 h-8 animate-spin text-primary-600 dark:text-primary-200" />
            </div>
          ) : filteredOperations.length > 0 ? (
            <div className="overflow-x-auto">
              <table className="data-table">
                <thead className="data-table-header">
                  <tr>
                    <th className="data-table-header-cell">Type</th>
                    <th className="data-table-header-cell">Wallet</th>
                    <th className="data-table-header-cell">Amount</th>
                    <th className="data-table-header-cell">Channel</th>
                    <th className="data-table-header-cell">Status</th>
                    <th className="data-table-header-cell">Reference</th>
                    <th className="data-table-header-cell">Time</th>
                    <th className="data-table-header-cell">Actions</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-neutral-100 dark:divide-primary-800/60">
                  {filteredOperations.map((op) => {
                    const typeConf = operationTypeConfig[op.type];
                    const channelConf = channelConfig[op.channel];
                    const statusConf = statusConfig[op.status];
                    return (
                      <tr key={op.id} className="data-table-row group">
                        <td className="data-table-cell">
                          <div className="flex items-center gap-2">
                            <span className={cn(
                              "p-1.5 rounded-lg",
                              typeConf.direction === 'in' ? "bg-success-100 dark:bg-success-500/20 text-success-600 dark:text-success-300" : "bg-error-100 dark:bg-error-500/20 text-error-600 dark:text-error-300"
                            )}>
                              {typeConf.icon}
                            </span>
                            <span className="font-medium text-primary-900 dark:text-neutral-50">{typeConf.label}</span>
                          </div>
                        </td>
                        <td className="data-table-cell">
                          <p className="font-medium text-primary-900 dark:text-neutral-50">{op.walletHolderName}</p>
                          <p className="text-sm text-neutral-500 dark:text-neutral-400 font-mono">{op.walletReference}</p>
                        </td>
                        <td className="data-table-cell">
                          <p className={cn(
                            "font-semibold",
                            typeConf.direction === 'in' ? "text-success-600 dark:text-success-300" : "text-error-600 dark:text-error-300"
                          )}>
                            {typeConf.direction === 'in' ? '+' : '-'}{formatCurrency(op.amount)}
                          </p>
                          {op.fee > 0 && <p className="text-xs text-neutral-400 dark:text-neutral-500">Fee: {formatCurrency(op.fee)}</p>}
                        </td>
                        <td className="data-table-cell">
                          <div className="flex items-center gap-2 text-neutral-600 dark:text-neutral-300">
                            {channelConf?.icon}
                            <span className="text-sm">{channelConf?.label}</span>
                          </div>
                          {op.agentName && <p className="text-xs text-neutral-400 dark:text-neutral-500">{op.agentName}</p>}
                        </td>
                        <td className="data-table-cell">
                          <Badge variant={statusConf.color as any}>{statusConf.label}</Badge>
                        </td>
                        <td className="data-table-cell">
                          <p className="text-sm font-mono text-neutral-600 dark:text-neutral-300">{op.reference}</p>
                        </td>
                        <td className="data-table-cell">
                          <p className="text-sm text-neutral-600 dark:text-neutral-300">{new Date(op.createdAt).toLocaleTimeString()}</p>
                          <p className="text-xs text-neutral-400 dark:text-neutral-500">{new Date(op.createdAt).toLocaleDateString()}</p>
                        </td>
                        <td className="data-table-cell">
                          <button
                            onClick={() => { setSelectedOperation(op); setShowDetailModal(true); }}
                            className="text-primary-600 dark:text-primary-200 hover:text-primary-700 dark:text-neutral-200 text-sm font-medium opacity-0 group-hover:opacity-100 transition-opacity dark:hover:text-neutral-200"
                          >
                            View
                          </button>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          ) : (
            <EmptyState
              icon={<Banknote className="w-8 h-8" />}
              title="No operations found"
              description="Adjust your filters or process a new transaction"
            />
          )}
        </Card>
      )}

      {/* Agents Tab */}
      {activeTab === 'agents' && (
        <Card padding="none" className="animate-fade-in" style={{ animationDelay: '0.5s' }}>
          <div className="p-4 border-b border-neutral-200 dark:border-primary-800 flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-accent-500 to-accent-700 flex items-center justify-center">
                <Store className="w-5 h-5 text-white" />
              </div>
              <h3 className="font-semibold text-primary-900 dark:text-neutral-50">Agent Network</h3>
            </div>
            <Button size="sm">+ Add Agent</Button>
          </div>
          <div className="overflow-x-auto">
            <table className="data-table">
              <thead className="data-table-header">
                <tr>
                  <th className="data-table-header-cell">Agent</th>
                  <th className="data-table-header-cell">Type</th>
                  <th className="data-table-header-cell">Location</th>
                  <th className="data-table-header-cell">Float Balance</th>
                  <th className="data-table-header-cell">Daily Usage</th>
                  <th className="data-table-header-cell">Status</th>
                  <th className="data-table-header-cell">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-neutral-100 dark:divide-primary-800/60">
                {agents.map((agent) => (
                  <tr key={agent.id} className="data-table-row group">
                    <td className="data-table-cell">
                      <p className="font-medium text-primary-900 dark:text-neutral-50">{agent.agentName}</p>
                      <p className="text-sm text-neutral-500 dark:text-neutral-400 font-mono">{agent.agentCode}</p>
                    </td>
                    <td className="data-table-cell">
                      <Badge variant={agent.agentType === 'SUPER_AGENT' ? 'info' : 'neutral'}>
                        {agent.agentType}
                      </Badge>
                    </td>
                    <td className="data-table-cell text-neutral-600 dark:text-neutral-300">{agent.location}</td>
                    <td className="data-table-cell font-semibold text-primary-900 dark:text-neutral-50">{formatCurrency(agent.floatBalance)}</td>
                    <td className="data-table-cell">
                      <div className="w-32">
                        <div className="flex justify-between text-xs mb-1">
                          <span>{formatCurrency(agent.dailyUsed)}</span>
                          <span className="text-neutral-400 dark:text-neutral-500">{formatCurrency(agent.dailyLimit)}</span>
                        </div>
                        <ProgressBar
                          value={(agent.dailyUsed / agent.dailyLimit) * 100}
                          size="sm"
                          variant={agent.dailyUsed / agent.dailyLimit > 0.8 ? 'warning' : 'default'}
                        />
                      </div>
                    </td>
                    <td className="data-table-cell">
                      <Badge variant={agent.status === 'ACTIVE' ? 'success' : 'error'}>{agent.status}</Badge>
                    </td>
                    <td className="data-table-cell">
                      <button className="text-primary-600 dark:text-primary-200 hover:text-primary-700 dark:text-neutral-200 text-sm font-medium opacity-0 group-hover:opacity-100 transition-opacity dark:hover:text-neutral-200">Manage</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {/* Bulk Operations Tab */}
      {activeTab === 'bulk' && (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          <Card className="animate-fade-in" style={{ animationDelay: '0.5s' }}>
            <div className="flex items-center gap-3 mb-4">
              <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-primary-500 to-primary-700 flex items-center justify-center">
                <Upload className="w-5 h-5 text-white" />
              </div>
              <h3 className="font-semibold text-primary-900 dark:text-neutral-50">Bulk Top-up / Load</h3>
            </div>
            <p className="text-sm text-neutral-500 dark:text-neutral-400 mb-4">
              Upload a CSV file or paste data to load funds to multiple wallets at once.
            </p>
            <div className="space-y-4">
              <div>
                <label className="field-label block mb-1">Program (Optional)</label>
                <select className="w-full border border-neutral-300 dark:border-primary-700 rounded-lg px-3 py-2">
                  <option value="">All Programs</option>
                  <option value="1">Fintech Partner A</option>
                  <option value="2">Fintech Partner B</option>
                </select>
              </div>
              <div>
                <label className="field-label block mb-1">
                  Wallet Data (CSV Format)
                </label>
                <textarea
                  className="w-full border border-neutral-300 dark:border-primary-700 rounded-lg px-3 py-2 h-40 font-mono text-sm"
                  placeholder="walletReference,amount&#10;WAL-FINTA-00012345,100&#10;WAL-FINTA-00012346,250"
                  value={bulkLoadForm.csvData}
                  onChange={(e) => setBulkLoadForm({ ...bulkLoadForm, csvData: e.target.value })}
                />
                <p className="text-xs text-neutral-500 dark:text-neutral-400 mt-1">Format: walletReference,amount (one per line)</p>
              </div>
              <Input
                label="Description"
                placeholder="Bulk load description"
                value={bulkLoadForm.description}
                onChange={(e) => setBulkLoadForm({ ...bulkLoadForm, description: e.target.value })}
              />
              <div className="flex gap-2">
                <Button variant="outline" leftIcon={<Upload className="w-4 h-4" />}>
                  Upload CSV
                </Button>
                <Button onClick={handleBulkLoad} disabled={actionLoading}>
                  {actionLoading ? <Loader2 className="w-4 h-4 animate-spin mr-2" /> : null}
                  Process Bulk Load
                </Button>
              </div>
            </div>
          </Card>

          <Card className="animate-fade-in" style={{ animationDelay: '0.55s' }}>
            <div className="flex items-center gap-3 mb-4">
              <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-info-500 to-info-700 flex items-center justify-center">
                <Clock className="w-5 h-5 text-white" />
              </div>
              <h3 className="font-semibold text-primary-900 dark:text-neutral-50">Recent Bulk Operations</h3>
            </div>
            <div className="space-y-4">
              {[
                { id: 1, date: '2024-02-12', count: 150, total: 75000, status: 'Completed', success: 148 },
                { id: 2, date: '2024-02-11', count: 200, total: 100000, status: 'Completed', success: 200 },
                { id: 3, date: '2024-02-10', count: 50, total: 25000, status: 'Partial', success: 47 },
              ].map((op) => (
                <div key={op.id} className="p-4 bg-neutral-50 dark:bg-primary-950 rounded-xl hover:bg-neutral-100 dark:hover:bg-primary-800 transition-colors">
                  <div className="flex items-center justify-between mb-2">
                    <span className="text-sm text-neutral-500 dark:text-neutral-400">{op.date}</span>
                    <Badge variant={op.status === 'Completed' ? 'success' : 'warning'}>{op.status}</Badge>
                  </div>
                  <div className="flex items-center justify-between">
                    <div>
                      <p className="font-semibold text-primary-900 dark:text-neutral-50">{formatCurrency(op.total)}</p>
                      <p className="text-sm text-neutral-500 dark:text-neutral-400">{op.success}/{op.count} wallets</p>
                    </div>
                    <button className="text-primary-600 dark:text-primary-200 text-sm hover:text-primary-700 dark:text-neutral-200 dark:hover:text-neutral-200">View Details</button>
                  </div>
                </div>
              ))}
            </div>
          </Card>
        </div>
      )}

      {/* ================================================================ */}
      {/* MODALS */}
      {/* ================================================================ */}

      {/* Cash In Modal */}
      <Modal
        isOpen={showCashInModal}
        onClose={() => { setShowCashInModal(false); setWalletLookup(null); }}
        title="Cash In / Top-up"
        subtitle="Add funds to a wallet"
        size="md"
        footer={
          <>
            <Button variant="outline" onClick={() => setShowCashInModal(false)}>Cancel</Button>
            <Button variant="success" onClick={handleCashIn} disabled={actionLoading || !walletLookup}>
              {actionLoading ? <Loader2 className="w-4 h-4 animate-spin mr-2" /> : <ArrowDownRight className="w-4 h-4 mr-2" />}
              Process Cash In
            </Button>
          </>
        }
      >
        <div className="space-y-4">
          {/* Wallet Lookup */}
          <div>
            <label className="field-label block mb-1">Wallet Reference *</label>
            <div className="flex gap-2">
              <Input
                placeholder="WAL-XXXX-XXXXXXXX"
                value={cashInForm.walletReference}
                onChange={(e) => setCashInForm({ ...cashInForm, walletReference: e.target.value })}
                className="flex-1"
              />
              <Button
                variant="outline"
                onClick={() => handleWalletLookup(cashInForm.walletReference, 'cashIn')}
                disabled={lookupLoading}
              >
                {lookupLoading ? <Loader2 className="w-4 h-4 animate-spin" /> : <Search className="w-4 h-4" />}
              </Button>
            </div>
          </div>

          {/* Wallet Info */}
          {walletLookup && (
            <div className="p-4 bg-success-50 dark:bg-success-500/10 border border-success-200 rounded-xl dark:border-success-500/30">
              <div className="flex items-center gap-3">
                <Avatar name={walletLookup.holderName} size="md" />
                <div className="flex-1">
                  <p className="font-medium text-primary-900 dark:text-neutral-50">{walletLookup.holderName}</p>
                  <p className="text-sm text-neutral-500 dark:text-neutral-400">{walletLookup.holderMobile}</p>
                </div>
                <div className="text-right">
                  <p className="text-sm text-neutral-500 dark:text-neutral-400">Current Balance</p>
                  <p className="font-semibold text-primary-900 dark:text-neutral-50">{formatCurrency(walletLookup.currentBalance || 0)}</p>
                </div>
              </div>
            </div>
          )}

          <Input
            label="Amount *"
            placeholder="0.00"
            type="number"
            value={cashInForm.amount}
            onChange={(e) => setCashInForm({ ...cashInForm, amount: e.target.value })}
          />

          <div>
            <label className="field-label block mb-1">Channel</label>
            <div className="grid grid-cols-3 gap-2">
              {['AGENT', 'BANK_TRANSFER', 'CARD'].map((ch) => (
                <button
                  key={ch}
                  onClick={() => setCashInForm({ ...cashInForm, channel: ch })}
                  className={cn(
                    "p-3 border rounded-xl text-center transition-colors",
                    cashInForm.channel === ch
                      ? "border-primary-500 bg-primary-50 dark:bg-primary-800/40 text-primary-700 dark:text-neutral-200"
                      : "border-neutral-300 dark:border-primary-700 hover:border-neutral-400"
                  )}
                >
                  {channelConfig[ch]?.icon}
                  <p className="text-xs mt-1">{channelConfig[ch]?.label}</p>
                </button>
              ))}
            </div>
          </div>

          {cashInForm.channel === 'AGENT' && (
            <div>
              <label className="field-label block mb-1">Select Agent</label>
              <select
                className="w-full border border-neutral-300 dark:border-primary-700 rounded-lg px-3 py-2"
                value={cashInForm.agentId}
                onChange={(e) => setCashInForm({ ...cashInForm, agentId: e.target.value })}
              >
                <option value="">Select agent...</option>
                {agents.filter(a => a.status === 'ACTIVE').map(a => (
                  <option key={a.id} value={a.id}>{a.agentName} - {a.location}</option>
                ))}
              </select>
            </div>
          )}

          <Input
            label="Reference (Optional)"
            placeholder="External reference number"
            value={cashInForm.reference}
            onChange={(e) => setCashInForm({ ...cashInForm, reference: e.target.value })}
          />

          <Input
            label="Description (Optional)"
            placeholder="Add a note"
            value={cashInForm.description}
            onChange={(e) => setCashInForm({ ...cashInForm, description: e.target.value })}
          />
        </div>
      </Modal>

      {/* Cash Out Modal */}
      <Modal
        isOpen={showCashOutModal}
        onClose={() => { setShowCashOutModal(false); setWalletLookup(null); }}
        title="Cash Out / Withdrawal"
        subtitle="Withdraw funds from a wallet"
        size="md"
        footer={
          <>
            <Button variant="outline" onClick={() => setShowCashOutModal(false)}>Cancel</Button>
            <Button variant="danger" onClick={handleCashOut} disabled={actionLoading || !walletLookup}>
              {actionLoading ? <Loader2 className="w-4 h-4 animate-spin mr-2" /> : <ArrowUpRight className="w-4 h-4 mr-2" />}
              Process Cash Out
            </Button>
          </>
        }
      >
        <div className="space-y-4">
          {/* Wallet Lookup */}
          <div>
            <label className="field-label block mb-1">Wallet Reference *</label>
            <div className="flex gap-2">
              <Input
                placeholder="WAL-XXXX-XXXXXXXX"
                value={cashOutForm.walletReference}
                onChange={(e) => setCashOutForm({ ...cashOutForm, walletReference: e.target.value })}
                className="flex-1"
              />
              <Button
                variant="outline"
                onClick={() => handleWalletLookup(cashOutForm.walletReference, 'cashOut')}
                disabled={lookupLoading}
              >
                {lookupLoading ? <Loader2 className="w-4 h-4 animate-spin" /> : <Search className="w-4 h-4" />}
              </Button>
            </div>
          </div>

          {/* Wallet Info */}
          {walletLookup && (
            <div className="p-4 bg-neutral-50 dark:bg-primary-950 border border-neutral-200 dark:border-primary-800 rounded-xl">
              <div className="flex items-center gap-3">
                <Avatar name={walletLookup.holderName} size="md" />
                <div className="flex-1">
                  <p className="font-medium text-primary-900 dark:text-neutral-50">{walletLookup.holderName}</p>
                  <p className="text-sm text-neutral-500 dark:text-neutral-400">{walletLookup.holderMobile}</p>
                </div>
                <div className="text-right">
                  <p className="text-sm text-neutral-500 dark:text-neutral-400">Available Balance</p>
                  <p className="font-semibold text-success-600 dark:text-success-300">{formatCurrency(walletLookup.availableBalance || 0)}</p>
                </div>
              </div>
            </div>
          )}

          <Input
            label="Amount *"
            placeholder="0.00"
            type="number"
            value={cashOutForm.amount}
            onChange={(e) => setCashOutForm({ ...cashOutForm, amount: e.target.value })}
          />

          {walletLookup && parseFloat(cashOutForm.amount) > (walletLookup.availableBalance || 0) && (
            <Alert variant="error">Insufficient balance</Alert>
          )}

          <div>
            <label className="field-label block mb-1">Channel</label>
            <div className="grid grid-cols-3 gap-2">
              {['AGENT', 'BANK_TRANSFER', 'ATM'].map((ch) => (
                <button
                  key={ch}
                  onClick={() => setCashOutForm({ ...cashOutForm, channel: ch })}
                  className={cn(
                    "p-3 border rounded-xl text-center transition-colors",
                    cashOutForm.channel === ch
                      ? "border-primary-500 bg-primary-50 dark:bg-primary-800/40 text-primary-700 dark:text-neutral-200"
                      : "border-neutral-300 dark:border-primary-700 hover:border-neutral-400"
                  )}
                >
                  {channelConfig[ch]?.icon}
                  <p className="text-xs mt-1">{channelConfig[ch]?.label}</p>
                </button>
              ))}
            </div>
          </div>

          {cashOutForm.channel === 'AGENT' && (
            <div>
              <label className="field-label block mb-1">Select Agent</label>
              <select
                className="w-full border border-neutral-300 dark:border-primary-700 rounded-lg px-3 py-2"
                value={cashOutForm.agentId}
                onChange={(e) => setCashOutForm({ ...cashOutForm, agentId: e.target.value })}
              >
                <option value="">Select agent...</option>
                {agents.filter(a => a.status === 'ACTIVE').map(a => (
                  <option key={a.id} value={a.id}>{a.agentName} - {a.location}</option>
                ))}
              </select>
            </div>
          )}

          <Input
            label="Description (Optional)"
            placeholder="Add a note"
            value={cashOutForm.description}
            onChange={(e) => setCashOutForm({ ...cashOutForm, description: e.target.value })}
          />
        </div>
      </Modal>

      {/* Transfer Modal */}
      <Modal
        isOpen={showTransferModal}
        onClose={() => { setShowTransferModal(false); setWalletLookup(null); }}
        title="Wallet Transfer"
        subtitle="Transfer funds between wallets"
        size="md"
        footer={
          <>
            <Button variant="outline" onClick={() => setShowTransferModal(false)}>Cancel</Button>
            <Button onClick={handleTransfer} disabled={actionLoading || !walletLookup}>
              {actionLoading ? <Loader2 className="w-4 h-4 animate-spin mr-2" /> : <Send className="w-4 h-4 mr-2" />}
              Send Transfer
            </Button>
          </>
        }
      >
        <div className="space-y-4">
          {/* From Wallet */}
          <div>
            <label className="field-label block mb-1">From Wallet *</label>
            <div className="flex gap-2">
              <Input
                placeholder="WAL-XXXX-XXXXXXXX"
                value={transferForm.fromWalletReference}
                onChange={(e) => setTransferForm({ ...transferForm, fromWalletReference: e.target.value })}
                className="flex-1"
              />
              <Button
                variant="outline"
                onClick={() => handleWalletLookup(transferForm.fromWalletReference, 'transfer')}
                disabled={lookupLoading}
              >
                {lookupLoading ? <Loader2 className="w-4 h-4 animate-spin" /> : <Search className="w-4 h-4" />}
              </Button>
            </div>
          </div>

          {walletLookup && (
            <div className="p-4 bg-neutral-50 dark:bg-primary-950 border border-neutral-200 dark:border-primary-800 rounded-xl">
              <div className="flex items-center gap-3">
                <Avatar name={walletLookup.holderName} size="md" />
                <div className="flex-1">
                  <p className="font-medium text-primary-900 dark:text-neutral-50">{walletLookup.holderName}</p>
                </div>
                <div className="text-right">
                  <p className="text-sm text-neutral-500 dark:text-neutral-400">Available</p>
                  <p className="font-semibold text-success-600 dark:text-success-300">{formatCurrency(walletLookup.availableBalance || 0)}</p>
                </div>
              </div>
            </div>
          )}

          <Input
            label="To Wallet *"
            placeholder="WAL-XXXX-XXXXXXXX"
            value={transferForm.toWalletReference}
            onChange={(e) => setTransferForm({ ...transferForm, toWalletReference: e.target.value })}
          />

          <Input
            label="Amount *"
            placeholder="0.00"
            type="number"
            value={transferForm.amount}
            onChange={(e) => setTransferForm({ ...transferForm, amount: e.target.value })}
          />

          <Input
            label="Description (Optional)"
            placeholder="Payment for..."
            value={transferForm.description}
            onChange={(e) => setTransferForm({ ...transferForm, description: e.target.value })}
          />
        </div>
      </Modal>

      {/* Operation Detail Modal */}
      <Modal
        isOpen={showDetailModal}
        onClose={() => setShowDetailModal(false)}
        title="Transaction Details"
        subtitle={selectedOperation?.reference}
        size="md"
      >
        {selectedOperation && (
          <div className="space-y-6">
            <div className="flex items-center justify-between p-4 bg-neutral-50 dark:bg-primary-950 rounded-xl">
              <div className="flex items-center gap-3">
                <span className={cn(
                  "p-3 rounded-xl",
                  operationTypeConfig[selectedOperation.type].direction === 'in' 
                    ? "bg-success-100 dark:bg-success-500/20 text-success-600 dark:text-success-300" 
                    : "bg-error-100 dark:bg-error-500/20 text-error-600 dark:text-error-300"
                )}>
                  {operationTypeConfig[selectedOperation.type].icon}
                </span>
                <div>
                  <p className="font-semibold text-primary-900 dark:text-neutral-50">{operationTypeConfig[selectedOperation.type].label}</p>
                  <Badge variant={statusConfig[selectedOperation.status].color as any}>
                    {statusConfig[selectedOperation.status].label}
                  </Badge>
                </div>
              </div>
              <div className="text-right">
                <p className={cn(
                  "text-2xl font-bold",
                  operationTypeConfig[selectedOperation.type].direction === 'in' ? "text-success-600 dark:text-success-300" : "text-error-600 dark:text-error-300"
                )}>
                  {operationTypeConfig[selectedOperation.type].direction === 'in' ? '+' : '-'}
                  {formatCurrency(selectedOperation.amount)}
                </p>
                {selectedOperation.fee > 0 && (
                  <p className="text-sm text-neutral-500 dark:text-neutral-400">Fee: {formatCurrency(selectedOperation.fee)}</p>
                )}
              </div>
            </div>

            <div className="grid grid-cols-2 gap-4 text-sm">
              <div>
                <p className="text-neutral-500 dark:text-neutral-400">Wallet</p>
                <p className="font-medium text-primary-900 dark:text-neutral-50">{selectedOperation.walletHolderName}</p>
                <p className="font-mono text-neutral-600 dark:text-neutral-300">{selectedOperation.walletReference}</p>
              </div>
              <div>
                <p className="text-neutral-500 dark:text-neutral-400">Channel</p>
                <div className="flex items-center gap-2">
                  {channelConfig[selectedOperation.channel]?.icon}
                  <span className="font-medium">{channelConfig[selectedOperation.channel]?.label}</span>
                </div>
                {selectedOperation.agentName && (
                  <p className="text-neutral-600 dark:text-neutral-300">{selectedOperation.agentName}</p>
                )}
              </div>
              <div>
                <p className="text-neutral-500 dark:text-neutral-400">Previous Balance</p>
                <p className="font-medium text-primary-900 dark:text-neutral-50">{formatCurrency(selectedOperation.previousBalance)}</p>
              </div>
              <div>
                <p className="text-neutral-500 dark:text-neutral-400">New Balance</p>
                <p className="font-medium text-primary-900 dark:text-neutral-50">{formatCurrency(selectedOperation.newBalance)}</p>
              </div>
              <div>
                <p className="text-neutral-500 dark:text-neutral-400">Reference</p>
                <p className="font-mono text-primary-900 dark:text-neutral-50">{selectedOperation.reference}</p>
              </div>
              <div>
                <p className="text-neutral-500 dark:text-neutral-400">Timestamp</p>
                <p className="font-medium text-primary-900 dark:text-neutral-50">{new Date(selectedOperation.createdAt).toLocaleString()}</p>
              </div>
            </div>

            {selectedOperation.description && (
              <div>
                <p className="text-sm text-neutral-500 dark:text-neutral-400">Description</p>
                <p className="text-primary-900 dark:text-neutral-50">{selectedOperation.description}</p>
              </div>
            )}

            <div className="flex gap-2 pt-4 border-t border-neutral-200 dark:border-primary-800">
              <Button variant="outline" leftIcon={<Printer className="w-4 h-4" />} className="flex-1">
                Print Receipt
              </Button>
              <Button variant="outline" leftIcon={<Copy className="w-4 h-4" />} className="flex-1">
                Copy Details
              </Button>
            </div>
          </div>
        )}
      </Modal>
    </Page>
  );
};

export default BaaSCashOperationsPage;