import React from 'react';
import {
  Wallet,
  ArrowUpRight,
  ArrowDownRight,
  TrendingUp,
  CreditCard,
  RefreshCw,
  ChevronRight,
  Clock,
} from 'lucide-react';
import { Card, CardHeader, Badge, Button, Skeleton } from '../ui';
import { formatCurrency, formatRelativeTime, getStatusVariant, cn } from '../../utils';
import {
  AreaChart,
  Area,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
} from 'recharts';

// Mock data for demonstration
const mockStats = {
  totalBalance: 12450000.5,
  availableBalance: 11890000.25,
  totalAccounts: 24,
  activeAccounts: 22,
  todayTransactions: 156,
  todayVolume: 2340000,
  pendingTransactions: 3,
};

const mockBalanceTrend = [
  { date: 'Jan 01', balance: 10500000, available: 10200000 },
  { date: 'Jan 08', balance: 10800000, available: 10500000 },
  { date: 'Jan 15', balance: 11200000, available: 10900000 },
  { date: 'Jan 22', balance: 11000000, available: 10700000 },
  { date: 'Jan 29', balance: 11800000, available: 11500000 },
  { date: 'Feb 05', balance: 12100000, available: 11800000 },
  { date: 'Feb 12', balance: 12450000, available: 11890000 },
];

const mockRecentTransactions = [
  {
    id: '1',
    reference: 'TXN-2024-001234',
    beneficiary: 'Emirates Trading LLC',
    amount: 125000,
    currency: 'AED',
    status: 'COMPLETED',
    date: '2024-02-12T14:30:00Z',
  },
  {
    id: '2',
    reference: 'TXN-2024-001235',
    beneficiary: 'Dubai Logistics Co',
    amount: 85000,
    currency: 'AED',
    status: 'PROCESSING',
    date: '2024-02-12T13:15:00Z',
  },
  {
    id: '3',
    reference: 'TXN-2024-001236',
    beneficiary: 'Gulf Services FZE',
    amount: 250000,
    currency: 'AED',
    status: 'PENDING',
    date: '2024-02-12T11:45:00Z',
  },
  {
    id: '4',
    reference: 'TXN-2024-001237',
    beneficiary: 'Abu Dhabi Steel',
    amount: 175000,
    currency: 'AED',
    status: 'COMPLETED',
    date: '2024-02-12T10:20:00Z',
  },
];

const mockTopAccounts = [
  { iban: 'AE150410000011234567890', name: 'Operating Account', balance: 5250000 },
  { iban: 'AE150410000011234567891', name: 'Payroll Account', balance: 3100000 },
  { iban: 'AE150410000011234567892', name: 'Vendor Payments', balance: 2450000 },
  { iban: 'AE150410000011234567893', name: 'Collections', balance: 1650000 },
];

// Stat Card Component
interface StatCardProps {
  title: string;
  value: string | number;
  subtitle?: string;
  icon: React.ReactNode;
  trend?: {
    value: number;
    isPositive: boolean;
  };
  iconBg?: string;
}

const StatCard: React.FC<StatCardProps> = ({
  title,
  value,
  subtitle,
  icon,
  trend,
  iconBg = 'bg-primary-100',
}) => (
  <Card className="relative overflow-hidden">
    <div className="flex items-start justify-between">
      <div className="flex-1">
        <p className="text-body-sm text-neutral-500 mb-1">{title}</p>
        <p className="text-display-sm text-primary-900">{value}</p>
        {subtitle && (
          <p className="text-body-sm text-neutral-500 mt-1">{subtitle}</p>
        )}
        {trend && (
          <div
            className={cn(
              'flex items-center gap-1 mt-2 text-body-sm',
              trend.isPositive ? 'text-success-600' : 'text-error-600'
            )}
          >
            {trend.isPositive ? (
              <ArrowUpRight className="w-4 h-4" />
            ) : (
              <ArrowDownRight className="w-4 h-4" />
            )}
            <span>{Math.abs(trend.value)}% vs last month</span>
          </div>
        )}
      </div>
      <div className={cn('p-3 rounded-xl', iconBg)}>
        {icon}
      </div>
    </div>
  </Card>
);

// Balance Chart Component
const BalanceChart: React.FC<{ data: typeof mockBalanceTrend }> = ({ data }) => (
  <Card>
    <CardHeader
      title="Balance Trend"
      subtitle="Last 30 days"
      action={
        <Button variant="ghost" size="sm">
          <RefreshCw className="w-4 h-4" />
        </Button>
      }
    />
    <div className="h-64">
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={data} margin={{ top: 10, right: 10, left: 0, bottom: 0 }}>
          <defs>
            <linearGradient id="colorBalance" x1="0" y1="0" x2="0" y2="1">
              <stop offset="5%" stopColor="#102a43" stopOpacity={0.1} />
              <stop offset="95%" stopColor="#102a43" stopOpacity={0} />
            </linearGradient>
            <linearGradient id="colorAvailable" x1="0" y1="0" x2="0" y2="1">
              <stop offset="5%" stopColor="#10b981" stopOpacity={0.1} />
              <stop offset="95%" stopColor="#10b981" stopOpacity={0} />
            </linearGradient>
          </defs>
          <CartesianGrid strokeDasharray="3 3" stroke="#e5e5e5" />
          <XAxis
            dataKey="date"
            axisLine={false}
            tickLine={false}
            tick={{ fontSize: 12, fill: '#737373' }}
          />
          <YAxis
            axisLine={false}
            tickLine={false}
            tick={{ fontSize: 12, fill: '#737373' }}
            tickFormatter={(value) => `${(value / 1000000).toFixed(1)}M`}
          />
          <Tooltip
            contentStyle={{
              backgroundColor: 'white',
              border: '1px solid #e5e5e5',
              borderRadius: '8px',
              boxShadow: '0 4px 12px rgba(0,0,0,0.1)',
            }}
            formatter={(value: number) => formatCurrency(value)}
          />
          <Area
            type="monotone"
            dataKey="balance"
            stroke="#102a43"
            strokeWidth={2}
            fillOpacity={1}
            fill="url(#colorBalance)"
            name="Current Balance"
          />
          <Area
            type="monotone"
            dataKey="available"
            stroke="#10b981"
            strokeWidth={2}
            fillOpacity={1}
            fill="url(#colorAvailable)"
            name="Available Balance"
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  </Card>
);

// Recent Transactions Component
const RecentTransactions: React.FC<{ transactions: typeof mockRecentTransactions }> = ({
  transactions,
}) => (
  <Card padding="none">
    <div className="p-6 border-b border-neutral-200">
      <CardHeader
        title="Recent Transactions"
        subtitle="Today's activity"
        action={
          <Button variant="ghost" size="sm" rightIcon={<ChevronRight className="w-4 h-4" />}>
            View All
          </Button>
        }
        className="mb-0"
      />
    </div>
    <div className="divide-y divide-neutral-100">
      {transactions.map((txn) => (
        <div
          key={txn.id}
          className="flex items-center justify-between p-4 hover:bg-neutral-50 transition-colors"
        >
          <div className="flex items-center gap-4">
            <div className="w-10 h-10 rounded-full bg-primary-100 flex items-center justify-center">
              <ArrowUpRight className="w-5 h-5 text-primary-700" />
            </div>
            <div>
              <p className="text-body-md font-medium text-primary-900">
                {txn.beneficiary}
              </p>
              <p className="text-body-sm text-neutral-500">{txn.reference}</p>
            </div>
          </div>
          <div className="text-right">
            <p className="text-body-md font-semibold text-primary-900">
              {formatCurrency(txn.amount, txn.currency)}
            </p>
            <div className="flex items-center gap-2 justify-end mt-1">
              <Badge variant={getStatusVariant(txn.status)} size="sm">
                {txn.status}
              </Badge>
              <span className="text-caption text-neutral-400">
                {formatRelativeTime(txn.date)}
              </span>
            </div>
          </div>
        </div>
      ))}
    </div>
  </Card>
);

// Top Accounts Component
const TopAccounts: React.FC<{ accounts: typeof mockTopAccounts }> = ({ accounts }) => (
  <Card padding="none">
    <div className="p-6 border-b border-neutral-200">
      <CardHeader
        title="Top Accounts"
        subtitle="By balance"
        action={
          <Button variant="ghost" size="sm" rightIcon={<ChevronRight className="w-4 h-4" />}>
            View All
          </Button>
        }
        className="mb-0"
      />
    </div>
    <div className="p-4 space-y-3">
      {accounts.map((account, index) => (
        <div
          key={account.iban}
          className="flex items-center justify-between p-3 rounded-lg bg-neutral-50 hover:bg-neutral-100 transition-colors cursor-pointer"
        >
          <div className="flex items-center gap-3">
            <div className="w-8 h-8 rounded-full bg-primary-900 flex items-center justify-center text-white text-body-sm font-medium">
              {index + 1}
            </div>
            <div>
              <p className="text-body-md font-medium text-primary-900">{account.name}</p>
              <p className="text-caption text-neutral-500 font-mono">
                {account.iban.replace(/(.{4})/g, '$1 ').trim()}
              </p>
            </div>
          </div>
          <p className="text-body-md font-semibold text-primary-900">
            {formatCurrency(account.balance)}
          </p>
        </div>
      ))}
    </div>
  </Card>
);

// Main Dashboard Page
const DashboardPage: React.FC = () => {
  return (
    <div className="space-y-6">
      {/* Page Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-display-md text-primary-900">Dashboard</h1>
          <p className="text-body-md text-neutral-500 mt-1">
            Welcome back. Here's your financial overview.
          </p>
        </div>
        <div className="flex items-center gap-3">
          <Button variant="outline" leftIcon={<RefreshCw className="w-4 h-4" />}>
            Refresh
          </Button>
          <Button leftIcon={<ArrowUpRight className="w-4 h-4" />}>
            New Transfer
          </Button>
        </div>
      </div>

      {/* Stats Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
        <StatCard
          title="Total Balance"
          value={formatCurrency(mockStats.totalBalance)}
          subtitle={`${mockStats.totalAccounts} accounts`}
          icon={<Wallet className="w-6 h-6 text-primary-700" />}
          iconBg="bg-primary-100"
          trend={{ value: 12.5, isPositive: true }}
        />
        <StatCard
          title="Available Balance"
          value={formatCurrency(mockStats.availableBalance)}
          subtitle="Ready to use"
          icon={<CreditCard className="w-6 h-6 text-success-600" />}
          iconBg="bg-success-50"
          trend={{ value: 8.3, isPositive: true }}
        />
        <StatCard
          title="Today's Transactions"
          value={mockStats.todayTransactions}
          subtitle={formatCurrency(mockStats.todayVolume)}
          icon={<TrendingUp className="w-6 h-6 text-info-600" />}
          iconBg="bg-info-50"
        />
        <StatCard
          title="Pending"
          value={mockStats.pendingTransactions}
          subtitle="Awaiting processing"
          icon={<Clock className="w-6 h-6 text-warning-600" />}
          iconBg="bg-warning-50"
        />
      </div>

      {/* Charts and Lists Row */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <div className="lg:col-span-2">
          <BalanceChart data={mockBalanceTrend} />
        </div>
        <div>
          <TopAccounts accounts={mockTopAccounts} />
        </div>
      </div>

      {/* Recent Activity */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <RecentTransactions transactions={mockRecentTransactions} />
        <Card>
          <CardHeader
            title="Quick Actions"
            subtitle="Frequently used operations"
          />
          <div className="grid grid-cols-2 gap-4">
            {[
              { icon: <ArrowUpRight />, label: 'New Transfer', color: 'bg-primary-100' },
              { icon: <CreditCard />, label: 'New Account', color: 'bg-success-50' },
              { icon: <RefreshCw />, label: 'Bulk Payment', color: 'bg-info-50' },
              { icon: <TrendingUp />, label: 'Statements', color: 'bg-warning-50' },
            ].map((action) => (
              <button
                key={action.label}
                className="flex items-center gap-3 p-4 rounded-xl border border-neutral-200 hover:border-primary-300 hover:bg-primary-50 transition-all group"
              >
                <div className={cn('p-2 rounded-lg', action.color)}>
                  {React.cloneElement(action.icon as React.ReactElement, {
                    className: 'w-5 h-5 text-primary-700',
                  })}
                </div>
                <span className="text-body-md font-medium text-primary-900 group-hover:text-primary-700">
                  {action.label}
                </span>
              </button>
            ))}
          </div>
        </Card>
      </div>
    </div>
  );
};

export default DashboardPage;
