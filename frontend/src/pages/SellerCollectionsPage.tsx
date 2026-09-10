import React, { useState, useEffect } from 'react';
import { ShoppingBag, Search, Loader2, AlertCircle, RefreshCw, Download, Eye, CreditCard, TrendingUp, Clock, CheckCircle } from 'lucide-react';
import { Card, Button, Badge, Input , StatusIconBadge, DataTable } from '../components/ui';
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
            <DataTable
              data={filteredCollections}
              keyExtractor={(c) => c.id}
              emptyIcon={<ShoppingBag className="w-12 h-12 text-neutral-300 dark:text-neutral-600" />}
              emptyTitle="No collections found"
              columns={[
                {
                  key: 'transactionRef',
                  header: 'Reference',
                  render: (_, c) => (
                    <div className="flex items-center gap-2">
                      <ShoppingBag className="w-4 h-4 text-primary-600 dark:text-primary-200" />
                      <span className="font-mono text-sm">{c.transactionRef}</span>
                    </div>
                  ),
                },
                { key: 'merchantName', header: 'Seller', render: (_, c) => <span className="text-sm font-medium text-neutral-900 dark:text-neutral-50">{c.merchantName}</span> },
                { key: 'paymentMethod', header: 'Payment Method', render: (_, c) => <Badge variant="neutral">{c.paymentMethod}</Badge> },
                { key: 'amount', header: 'Amount', align: 'right', render: (_, c) => <span className="font-medium tracking-tight">{formatCurrency(c.amount, c.currencyCode || 'AED')}</span> },
                { key: 'status', header: 'Status', render: (_, c) => getStatusBadge(c.status) },
                { key: 'transactionDate', header: 'Date', render: (_, c) => <span className="text-sm text-neutral-600 dark:text-neutral-300">{new Date(c.transactionDate).toLocaleString()}</span> },
                {
                  key: 'actions',
                  header: 'Actions',
                  align: 'right',
                  render: (_, c) => (
                    <Button size="sm" variant="ghost" onClick={() => { setSelectedItem(c); setShowDetailModal(true); }}>
                      <Eye className="w-4 h-4" />
                    </Button>
                  ),
                },
              ]}
            />
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
            <DataTable
              data={settlements}
              keyExtractor={(s) => s.id}
              emptyTitle="No settlements found"
              columns={[
                { key: 'settlementRef', header: 'Settlement Ref', render: (_, s) => <span className="font-mono text-sm">{s.settlementRef}</span> },
                { key: 'merchantName', header: 'Seller', render: (_, s) => <span className="text-sm font-medium text-neutral-900 dark:text-neutral-50">{s.merchantName}</span> },
                { key: 'grossAmount', header: 'Gross', align: 'right', render: (_, s) => <span className="font-medium tracking-tight">{formatCurrency(s.grossAmount, 'AED')}</span> },
                { key: 'commission', header: 'Commission', align: 'right', render: (_, s) => <span className="text-error-600 font-medium dark:text-error-300">-{formatCurrency(s.commission, 'AED')}</span> },
                { key: 'netAmount', header: 'Net', align: 'right', render: (_, s) => <span className="font-medium tracking-tight">{formatCurrency(s.netAmount, 'AED')}</span> },
                { key: 'status', header: 'Status', render: (_, s) => getStatusBadge(s.status) },
                { key: 'settlementDate', header: 'Date', render: (_, s) => <span className="text-sm text-neutral-600 dark:text-neutral-300">{s.settlementDate}</span> },
              ]}
            />
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
                {/* Phase 12 Task E: .stat-value-xs replaces the raw `text-xl font-bold` hand-roll (same 20px scale). */}
                <p className="stat-value-xs">{formatCurrency(selectedItem.amount, selectedItem.currencyCode || 'AED')}</p>
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
