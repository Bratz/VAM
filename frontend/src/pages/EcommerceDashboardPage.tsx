import React, { useState, useEffect } from 'react';
import { ShoppingCart, TrendingUp, Users, CreditCard, Loader2, RefreshCw, Download, XCircle } from 'lucide-react';
import { Card, Button, StatusIconBadge } from '../components/ui';
import { ecommerceApi } from '../services/api';
import type { EcommerceStats, EcommerceTrendPoint } from '../services/api';
import { formatCurrency } from '../utils';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import { Page } from '../components/layout/Page';

const EcommerceDashboardPage: React.FC = () => {
  const [stats, setStats] = useState<EcommerceStats | null>(null);
  const [trends, setTrends] = useState<EcommerceTrendPoint[]>([]);
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
            <StatusIconBadge tone="error" icon={XCircle} />
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
                <StatusIconBadge tone="primary" icon={Users} />
              </div>
              <p className="stat-value-sm mt-3">{stats.collectionAccounts}</p>
              <p className="label">Collection Accounts</p>
              <p className="caption mt-1">{stats.totalTransactions.toLocaleString()} collections in total</p>
            </div>
          </Card>

          <Card hover>
            <div className="p-4">
              <div className="flex items-center justify-between">
                <StatusIconBadge tone="success" icon={CreditCard} />
              </div>
              <p className="stat-value-sm mt-3">{formatCurrency(stats.totalCollections)}</p>
              <p className="label">Total Collections</p>
              <p className="caption mt-1">Today: {formatCurrency(stats.todayCollections)}</p>
            </div>
          </Card>

          <Card hover>
            <div className="p-4">
              <div className="flex items-center justify-between">
                <StatusIconBadge tone="warning" icon={ShoppingCart} />
              </div>
              <p className="stat-value-sm mt-3">{stats.transactionsToday}</p>
              <p className="label">Transactions Today</p>
              <p className="caption mt-1">Avg: {formatCurrency(stats.averageTicketSize)}</p>
            </div>
          </Card>

          <Card hover>
            <div className="p-4">
              <div className="flex items-center justify-between">
                <StatusIconBadge tone="info" icon={TrendingUp} />
              </div>
              <p className="stat-value-sm mt-3">{stats.successRate != null ? `${stats.successRate}%` : '—'}</p>
              <p className="label">Completed Collections</p>
              <p className="caption mt-1">Held on account: {formatCurrency(stats.heldOnCollectionAccounts)}</p>
            </div>
          </Card>
        </div>
      )}

      {/* Collection Trends Chart */}
      <Card className="animate-fade-in" style={{ animationDelay: '0.15s' }}>
        <div className="p-4 border-b border-edge-subtle">
          <h3 className="text-body-sm font-semibold text-neutral-900 uppercase tracking-wider dark:text-neutral-50">Collection Trends (30 Days)</h3>
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
        <Card hover className="p-4 cursor-pointer" onClick={() => { window.location.search = '?page=merchant-onboarding'; }}>
          <h4 className="body-strong font-semibold">Collection Accounts</h4>
          <p className="caption mt-1">Accounts money is collected into</p>
        </Card>
        <Card hover className="p-4 cursor-pointer" onClick={() => { window.location.search = '?page=ecommerce-collections'; }}>
          <h4 className="body-strong font-semibold">View Collections</h4>
          <p className="caption mt-1">Monitor transaction collections</p>
        </Card>
        <Card hover className="p-4 cursor-pointer" onClick={() => { window.location.search = '?page=seller-collections'; }}>
          <h4 className="body-strong font-semibold">Settlement Runs</h4>
          <p className="caption mt-1">Sweeps off the collection accounts</p>
        </Card>
      </div>
    </Page>
  );
};

export default EcommerceDashboardPage;