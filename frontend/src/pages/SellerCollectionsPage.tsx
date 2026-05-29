import React, { useState, useEffect } from 'react';
import { ShoppingBag, Search, Loader2, AlertCircle, RefreshCw, Download, Eye, CreditCard, TrendingUp, Clock, CheckCircle } from 'lucide-react';
import { Card, Button, Badge, Input , StatusIconBadge } from '../components/ui';
import { Modal } from '../components/ui/enhanced';
import { ecommerceApi } from '../services/api';
import { formatCurrency } from '../utils';
import { Page } from '../components/layout/Page';

const SellerCollectionsPage: React.FC = () => {
  const [collections, setCollections] = useState<any[]>([]);
  const [settlements, setSettlements] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [activeTab, setActiveTab] = useState<'collections' | 'settlements'>('collections');
  const [showDetailModal, setShowDetailModal] = useState(false);
  const [selectedItem, setSelectedItem] = useState<any>(null);
  const [processing, setProcessing] = useState(false);

  const fetchData = async () => {
    try {
      setLoading(true);
      setError(null);
      const [collRes, settRes] = await Promise.all([
        ecommerceApi.getCollections(),
        ecommerceApi.getSettlements()
      ]);
      if (collRes.success) setCollections(collRes.data);
      if (settRes.success) setSettlements(settRes.data);
    } catch (err: any) {
      setError(err.message || 'Failed to load data');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchData(); }, []);

  const handleProcessSettlements = async () => {
    setProcessing(true);
    try {
      await ecommerceApi.processSettlements();
      await fetchData();
    } finally {
      setProcessing(false);
    }
  };

  const filteredCollections = collections.filter(c =>
    searchQuery === '' ||
    c.transactionRef?.toLowerCase().includes(searchQuery.toLowerCase()) ||
    c.merchantName?.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const getStatusBadge = (status: string) => {
    const variants: Record<string, 'success' | 'warning' | 'error' | 'neutral'> = {
      COMPLETED: 'success', PENDING: 'warning', FAILED: 'error'
    };
    return <Badge variant={variants[status] || 'neutral'}>{status}</Badge>;
  };

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

      {/* Summary Stats */}
      <div className="grid grid-cols-4 gap-4 animate-fade-in" style={{ animationDelay: '0.1s' }}>
        <Card hover>
          <div className="p-4">
            <div className="flex items-center justify-between">
              <StatusIconBadge tone="primary" icon={CreditCard} className="dark:bg-primary-700" />
            </div>
            <p className="stat-value-sm mt-3">{collections.length}</p>
            <p className="text-xs text-neutral-500 uppercase tracking-wider dark:text-neutral-400">Total Collections</p>
          </div>
        </Card>
        <Card hover>
          <div className="p-4">
            <div className="flex items-center justify-between">
              <StatusIconBadge tone="success" icon={TrendingUp} className="dark:bg-success-500/20" />
            </div>
            <p className="stat-value-sm mt-3 text-success-600 dark:text-success-300">{formatCurrency(collections.reduce((sum, c) => sum + (c.amount || 0), 0), 'AED')}</p>
            <p className="text-xs text-neutral-500 uppercase tracking-wider dark:text-neutral-400">Total Amount</p>
          </div>
        </Card>
        <Card hover>
          <div className="p-4">
            <div className="flex items-center justify-between">
              <StatusIconBadge tone="warning" icon={Clock} className="dark:bg-warning-500/20" />
            </div>
            <p className="stat-value-warning mt-3">{settlements.filter(s => s.status === 'PENDING').length}</p>
            <p className="text-xs text-neutral-500 uppercase tracking-wider dark:text-neutral-400">Pending Settlements</p>
          </div>
        </Card>
        <Card hover>
          <div className="p-4">
            <div className="flex items-center justify-between">
              <StatusIconBadge tone="info" icon={CheckCircle} className="dark:bg-info-500/20" />
            </div>
            <p className="stat-value-sm mt-3">{formatCurrency(settlements.filter(s => s.status === 'COMPLETED').reduce((sum, s) => sum + (s.netAmount || 0), 0), 'AED')}</p>
            <p className="text-xs text-neutral-500 uppercase tracking-wider dark:text-neutral-400">Settled Amount</p>
          </div>
        </Card>
      </div>

      {error && (
        <Card className="bg-error-50 border-error-200 animate-fade-in dark:bg-error-500/10 dark:border-error-500/30" style={{ animationDelay: '0.15s' }}>
          <div className="flex items-center gap-4 p-4">
            <StatusIconBadge tone="error" icon={AlertCircle} className="dark:bg-error-500/20" />
            <span className="text-error-800 dark:text-error-300">{error}</span>
          </div>
        </Card>
      )}

      {/* Tabs */}
      <div className="flex gap-2 border-b border-neutral-200 animate-fade-in dark:border-primary-800" style={{ animationDelay: '0.15s' }}>
        <button
          className={`px-4 py-2 text-sm font-medium transition-colors ${activeTab === 'collections' ? 'border-b-2 border-primary-500 text-primary-600 dark:text-primary-200' : 'text-neutral-500 hover:text-neutral-700 dark:text-neutral-400 dark:hover:text-neutral-200'} dark:text-primary-200 dark:hover:text-neutral-200`}
          onClick={() => setActiveTab('collections')}
        >
          Collections ({collections.length})
        </button>
        <button
          className={`px-4 py-2 text-sm font-medium transition-colors ${activeTab === 'settlements' ? 'border-b-2 border-primary-500 text-primary-600 dark:text-primary-200' : 'text-neutral-500 hover:text-neutral-700 dark:text-neutral-400 dark:hover:text-neutral-200'} dark:text-primary-200 dark:hover:text-neutral-200`}
          onClick={() => setActiveTab('settlements')}
        >
          Settlements ({settlements.length})
        </button>
      </div>

      {/* Collections Tab */}
      {activeTab === 'collections' && (
        <>
          <Card className="animate-fade-in" style={{ animationDelay: '0.2s' }}>
            <div className="p-4 relative">
              <Search className="w-4 h-4 absolute left-7 top-1/2 -translate-y-1/2 text-neutral-400 dark:text-neutral-500" />
              <Input className="pl-9" placeholder="Search by reference or seller..." value={searchQuery} onChange={e => setSearchQuery(e.target.value)} />
            </div>
          </Card>

          <Card className="animate-fade-in" style={{ animationDelay: '0.25s' }}>
            <div className="overflow-x-auto">
              <table className="w-full">
                <thead className="bg-neutral-50 border-b border-neutral-100 dark:bg-primary-950 dark:border-primary-800/60">
                  <tr>
                    <th className="text-left p-4 label">Reference</th>
                    <th className="text-left p-4 label">Seller</th>
                    <th className="text-left p-4 label">Payment Method</th>
                    <th className="text-right p-4 label">Amount</th>
                    <th className="text-left p-4 label">Status</th>
                    <th className="text-left p-4 label">Date</th>
                    <th className="text-right p-4 label">Actions</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-neutral-100 dark:divide-primary-800/60">
                  {filteredCollections.map(c => (
                    <tr key={c.id} className="hover:bg-neutral-50 transition-colors dark:hover:bg-primary-800/50">
                      <td className="p-4">
                        <div className="flex items-center gap-2">
                          <ShoppingBag className="w-4 h-4 text-primary-600 dark:text-primary-200" />
                          <span className="font-mono text-sm">{c.transactionRef}</span>
                        </div>
                      </td>
                      <td className="p-4 text-sm font-medium text-neutral-900 dark:text-neutral-50">{c.merchantName}</td>
                      <td className="p-4"><Badge variant="neutral">{c.paymentMethod}</Badge></td>
                      <td className="p-4 text-right font-medium tracking-tight">{formatCurrency(c.amount, c.currencyCode || 'AED')}</td>
                      <td className="p-4">{getStatusBadge(c.status)}</td>
                      <td className="p-4 text-sm text-neutral-600 dark:text-neutral-300">{new Date(c.transactionDate).toLocaleString()}</td>
                      <td className="p-4 text-right">
                        <Button size="sm" variant="ghost" onClick={() => { setSelectedItem(c); setShowDetailModal(true); }}>
                          <Eye className="w-4 h-4" />
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Card>
        </>
      )}

      {/* Settlements Tab */}
      {activeTab === 'settlements' && (
        <>
          <Card className="animate-fade-in" style={{ animationDelay: '0.2s' }}>
            <div className="p-4 flex justify-between items-center">
              <h3 className="text-sm font-semibold text-neutral-900 uppercase tracking-wider dark:text-neutral-50">Settlement Batches</h3>
              <Button onClick={handleProcessSettlements} disabled={processing}>
                {processing && <Loader2 className="w-4 h-4 animate-spin mr-2" />} Process Settlements
              </Button>
            </div>
          </Card>

          <Card className="animate-fade-in" style={{ animationDelay: '0.25s' }}>
            <div className="overflow-x-auto">
              <table className="w-full">
                <thead className="bg-neutral-50 border-b border-neutral-100 dark:bg-primary-950 dark:border-primary-800/60">
                  <tr>
                    <th className="text-left p-4 label">Settlement Ref</th>
                    <th className="text-left p-4 label">Seller</th>
                    <th className="text-right p-4 label">Gross</th>
                    <th className="text-right p-4 label">Commission</th>
                    <th className="text-right p-4 label">Net</th>
                    <th className="text-left p-4 label">Status</th>
                    <th className="text-left p-4 label">Date</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-neutral-100 dark:divide-primary-800/60">
                  {settlements.map(s => (
                    <tr key={s.id} className="hover:bg-neutral-50 transition-colors dark:hover:bg-primary-800/50">
                      <td className="p-4 font-mono text-sm">{s.settlementRef}</td>
                      <td className="p-4 text-sm font-medium text-neutral-900 dark:text-neutral-50">{s.merchantName}</td>
                      <td className="p-4 text-right font-medium tracking-tight">{formatCurrency(s.grossAmount, 'AED')}</td>
                      <td className="p-4 text-right text-error-600 font-medium dark:text-error-300">-{formatCurrency(s.commission, 'AED')}</td>
                      <td className="p-4 text-right font-medium tracking-tight">{formatCurrency(s.netAmount, 'AED')}</td>
                      <td className="p-4">{getStatusBadge(s.status)}</td>
                      <td className="p-4 text-sm text-neutral-600 dark:text-neutral-300">{s.settlementDate}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Card>
        </>
      )}

      {/* Detail Modal */}
      <Modal isOpen={showDetailModal} onClose={() => setShowDetailModal(false)} title="Collection Details">
        {selectedItem && (
          <div className="space-y-6">
            <div className="grid grid-cols-2 gap-6">
              <div>
                <p className="text-xs text-neutral-500 uppercase tracking-wider mb-1 dark:text-neutral-400">Reference</p>
                <p className="font-mono font-medium text-neutral-900 dark:text-neutral-50">{selectedItem.transactionRef}</p>
              </div>
              <div>
                <p className="text-xs text-neutral-500 uppercase tracking-wider mb-1 dark:text-neutral-400">Seller</p>
                <p className="font-medium text-neutral-900 dark:text-neutral-50">{selectedItem.merchantName}</p>
              </div>
              <div>
                <p className="text-xs text-neutral-500 uppercase tracking-wider mb-1 dark:text-neutral-400">Amount</p>
                <p className="text-xl font-bold tracking-tight text-neutral-900 dark:text-neutral-50">{formatCurrency(selectedItem.amount, selectedItem.currencyCode || 'AED')}</p>
              </div>
              <div>
                <p className="text-xs text-neutral-500 uppercase tracking-wider mb-1 dark:text-neutral-400">Status</p>
                {getStatusBadge(selectedItem.status)}
              </div>
              <div>
                <p className="text-xs text-neutral-500 uppercase tracking-wider mb-1 dark:text-neutral-400">Payment Method</p>
                <Badge variant="neutral">{selectedItem.paymentMethod}</Badge>
              </div>
              <div>
                <p className="text-xs text-neutral-500 uppercase tracking-wider mb-1 dark:text-neutral-400">Date</p>
                <p className="font-medium text-neutral-900 dark:text-neutral-50">{new Date(selectedItem.transactionDate).toLocaleString()}</p>
              </div>
            </div>
          </div>
        )}
      </Modal>
    </Page>
  );
};

export default SellerCollectionsPage;
