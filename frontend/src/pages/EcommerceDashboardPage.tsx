import React, { useState, useEffect } from 'react';
import { ShoppingCart, TrendingUp, Users, CreditCard, Loader2, AlertCircle, RefreshCw, Download } from 'lucide-react';
import { Card, Button, Badge , StatusIconBadge } from '../components/ui';
import { ecommerceApi } from '../services/api';
import { formatCurrency } from '../utils';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import { Page } from '../components/layout/Page';

const EcommerceDashboardPage: React.FC = () => {
  const [stats, setStats] = useState<any>(null);
  const [trends, setTrends] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchData = async () => {
    try {
      setLoading(true);
      setError(null);
      const [statsRes, trendsRes] = await Promise.all([
        ecommerceApi.getDashboardStats(),
        ecommerceApi.getCollectionTrends(30)
      ]);
      if (statsRes.success) setStats(statsRes.data);
      if (trendsRes.success) setTrends(trendsRes.data);
    } catch (err: any) {
      setError(err.message || 'Failed to load dashboard');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchData(); }, []);

  if (loading) {
    return (
      <div className="flex items-center justify-center py-12">
        <Loader2 className="w-6 h-6 animate-spin text-primary-600 dark:text-primary-200" />
      </div>
    );
  }

  return (
    <Page>
      {/* Quick Actions */}
      <div className="flex items-center justify-end gap-2 animate-fade-in" style={{ animationDelay: '0.05s' }}>
        <Button variant="outline" leftIcon={<Download className="w-4 h-4" />}>Export</Button>
        <Button variant="outline" leftIcon={<RefreshCw className="w-4 h-4" />} onClick={fetchData}>Refresh</Button>
      </div>

      {error && (
        <Card className="bg-error-50 border-error-200 animate-fade-in dark:bg-error-500/10 dark:border-error-500/30" style={{ animationDelay: '0.1s' }}>
          <div className="flex items-center gap-4 p-4">
            <StatusIconBadge tone="error" icon={AlertCircle} className="dark:bg-error-500/20" />
            <span className="text-error-800 dark:text-error-300">{error}</span>
          </div>
        </Card>
      )}

      {/* Stats Grid */}
      {stats && (
        <div className="grid grid-cols-4 gap-4 animate-fade-in" style={{ animationDelay: '0.1s' }}>
          <Card hover>
            <div className="p-4">
              <div className="flex items-center justify-between">
                <StatusIconBadge tone="primary" icon={Users} className="dark:bg-primary-700" />
                <Badge variant="success">+12%</Badge>
              </div>
              <p className="stat-value-sm mt-3">{stats.totalMerchants}</p>
              <p className="text-xs text-neutral-500 uppercase tracking-wider dark:text-neutral-400">Total Merchants</p>
              <p className="text-xs text-success-600 mt-1 dark:text-success-300">{stats.activeMerchants} active</p>
            </div>
          </Card>

          <Card hover>
            <div className="p-4">
              <div className="flex items-center justify-between">
                <StatusIconBadge tone="success" icon={CreditCard} className="dark:bg-success-500/20" />
                <Badge variant="success">+8%</Badge>
              </div>
              <p className="stat-value-sm mt-3">{formatCurrency(stats.totalCollections, 'AED')}</p>
              <p className="text-xs text-neutral-500 uppercase tracking-wider dark:text-neutral-400">Total Collections</p>
              <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">Today: {formatCurrency(stats.todayCollections, 'AED')}</p>
            </div>
          </Card>

          <Card hover>
            <div className="p-4">
              <div className="flex items-center justify-between">
                <StatusIconBadge tone="warning" icon={ShoppingCart} className="dark:bg-warning-500/20" />
              </div>
              <p className="stat-value-sm mt-3">{stats.transactionsToday}</p>
              <p className="text-xs text-neutral-500 uppercase tracking-wider dark:text-neutral-400">Transactions Today</p>
              <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">Avg: {formatCurrency(stats.averageTicketSize, 'AED')}</p>
            </div>
          </Card>

          <Card hover>
            <div className="p-4">
              <div className="flex items-center justify-between">
                <StatusIconBadge tone="info" icon={TrendingUp} className="dark:bg-info-500/20" />
              </div>
              <p className="stat-value-sm mt-3">{stats.successRate}%</p>
              <p className="text-xs text-neutral-500 uppercase tracking-wider dark:text-neutral-400">Success Rate</p>
              <p className="text-xs text-warning-600 mt-1 dark:text-warning-300">Pending: {formatCurrency(stats.pendingSettlements, 'AED')}</p>
            </div>
          </Card>
        </div>
      )}

      {/* Collection Trends Chart */}
      <Card className="animate-fade-in" style={{ animationDelay: '0.15s' }}>
        <div className="p-4 border-b border-neutral-100 dark:border-primary-800/60">
          <h3 className="text-sm font-semibold text-neutral-900 uppercase tracking-wider dark:text-neutral-50">Collection Trends (30 Days)</h3>
        </div>
        <div className="p-4" style={{ height: 300 }}>
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={trends}>
              <CartesianGrid strokeDasharray="3 3" stroke="#e5e7eb" />
              <XAxis dataKey="date" tick={{ fontSize: 11 }} stroke="#9ca3af" />
              <YAxis tick={{ fontSize: 11 }} stroke="#9ca3af" />
              <Tooltip />
              <Line type="monotone" dataKey="collections" stroke="#0ea5e9" strokeWidth={2} dot={false} />
            </LineChart>
          </ResponsiveContainer>
        </div>
      </Card>

      {/* Navigation Cards */}
      <div className="grid grid-cols-3 gap-4 animate-fade-in" style={{ animationDelay: '0.2s' }}>
        <Card hover className="p-4 cursor-pointer" onClick={() => window.location.href = '/ecommerce/merchants'}>
          <h4 className="text-sm font-semibold text-neutral-900 dark:text-neutral-50">Manage Merchants</h4>
          <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">View and manage merchant accounts</p>
        </Card>
        <Card hover className="p-4 cursor-pointer" onClick={() => window.location.href = '/ecommerce/collections'}>
          <h4 className="text-sm font-semibold text-neutral-900 dark:text-neutral-50">View Collections</h4>
          <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">Monitor transaction collections</p>
        </Card>
        <Card hover className="p-4 cursor-pointer" onClick={() => window.location.href = '/ecommerce/settlements'}>
          <h4 className="text-sm font-semibold text-neutral-900 dark:text-neutral-50">Process Settlements</h4>
          <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">Manage merchant settlements</p>
        </Card>
      </div>
    </Page>
  );
};

export default EcommerceDashboardPage;