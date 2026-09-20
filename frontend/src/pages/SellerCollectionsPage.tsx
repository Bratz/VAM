import React, { useState, useEffect } from 'react';
import { ShoppingBag, Search, Loader2, RefreshCw, Download, Eye, CreditCard, TrendingUp, Clock, CheckCircle, XCircle } from 'lucide-react';
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

  const filteredCollections = collections.filter(c =>
    searchQuery === '' ||
    c.reference?.toLowerCase().includes(searchQuery.toLowerCase()) ||
    (c.remitterName?.toLowerCase().includes(searchQuery.toLowerCase()) || c.vaName?.toLowerCase().includes(searchQuery.toLowerCase()) || c.reference?.toLowerCase().includes(searchQuery.toLowerCase()))
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
              <StatusIconBadge tone="primary" icon={CreditCard} />
            </div>
            <p className="stat-value-sm mt-3">{collections.length}</p>
            <p className="label">Total Collections</p>
          </div>
        </Card>
        <Card hover>
          <div className="p-4">
            <div className="flex items-center justify-between">
              <StatusIconBadge tone="success" icon={TrendingUp} />
            </div>
            <p className="stat-value-sm mt-3 text-success-600 dark:text-success-300">{formatCurrency(collections.reduce((sum, c) => sum + (c.amount || 0), 0))}</p>
            <p className="label">Total Amount</p>
          </div>
        </Card>
        <Card hover>
          <div className="p-4">
            <div className="flex items-center justify-between">
              <StatusIconBadge tone="warning" icon={Clock} />
            </div>
            <p className="stat-value-warning mt-3">{settlements.reduce((sum, s) => sum + s.accountsSwept, 0)}</p>
            <p className="label">Accounts Swept</p>
          </div>
        </Card>
        <Card hover>
          <div className="p-4">
            <div className="flex items-center justify-between">
              <StatusIconBadge tone="info" icon={CheckCircle} />
            </div>
            <p className="stat-value-sm mt-3">{formatCurrency(settlements.reduce((sum, s) => sum + (s.netAmount || 0), 0))}</p>
            <p className="label">Settled Amount</p>
          </div>
        </Card>
      </div>

      {error && (
        <Card className="bg-error-50 border-error-200 animate-fade-in dark:bg-error-500/10 dark:border-error-500/30" style={{ animationDelay: '0.15s' }}>
          <div className="flex items-center gap-4 p-4">
            <StatusIconBadge tone="error" icon={XCircle} />
            <span className="text-error-800 dark:text-error-300">{error}</span>
          </div>
        </Card>
      )}

      {/* Tabs */}
      <div className="flex gap-2 border-b border-edge animate-fade-in" style={{ animationDelay: '0.15s' }}>
        <button
          className={`px-4 py-2 text-body-sm font-medium transition-colors ${activeTab === 'collections' ? 'border-b-2 border-primary-500 text-primary-600 dark:text-primary-200' : 'text-neutral-500 hover:text-neutral-700 dark:text-neutral-400 dark:hover:text-neutral-200'} dark:text-primary-200 dark:hover:text-neutral-200`}
          onClick={() => setActiveTab('collections')}
        >
          Collections ({collections.length})
        </button>
        <button
          className={`px-4 py-2 text-body-sm font-medium transition-colors ${activeTab === 'settlements' ? 'border-b-2 border-primary-500 text-primary-600 dark:text-primary-200' : 'text-neutral-500 hover:text-neutral-700 dark:text-neutral-400 dark:hover:text-neutral-200'} dark:text-primary-200 dark:hover:text-neutral-200`}
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
              <Search className="w-4 h-4 absolute left-7 top-1/2 -translate-y-1/2 text-neutral-400" />
              <Input className="pl-9" placeholder="Search by reference or seller..." value={searchQuery} onChange={e => setSearchQuery(e.target.value)} />
            </div>
          </Card>

          <Card className="animate-fade-in" style={{ animationDelay: '0.25s' }}>
            <DataTable
              data={filteredCollections}
              keyExtractor={(c) => c.id}
              emptyIcon={<ShoppingBag className="w-12 h-12 text-neutral-300 dark:text-neutral-400" />}
              emptyTitle="No collections found"
              columns={[
                {
                  key: 'reference',
                  header: 'Reference',
                  render: (_, c) => (
                    <div className="flex items-center gap-2">
                      <ShoppingBag className="w-4 h-4 text-primary-600 dark:text-primary-200" />
                      <span className="font-mono text-body-sm">{c.reference}</span>
                    </div>
                  ),
                },
                { key: 'vaName', header: 'Collected into', render: (_, c) => <span className="body-strong">{c.vaName}</span> },
                { key: 'remitterName', header: 'Paid by', render: (_, c) => <span className="body-sm">{c.remitterName || '—'}</span> },
                { key: 'amount', header: 'Amount', align: 'right', render: (_, c) => <span className="font-medium tracking-tight">{formatCurrency(c.amount, c.currencyCode || 'AED')}</span> },
                { key: 'status', header: 'Status', render: (_, c) => getStatusBadge(c.status) },
                { key: 'transactionDate', header: 'Date', render: (_, c) => <span className="body-sm">{new Date(c.transactionDate).toLocaleString()}</span> },
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
              <h3 className="text-body-sm font-semibold text-neutral-900 uppercase tracking-wider dark:text-neutral-50">Settlement Batches</h3>
              <p className="caption">Settlement runs are produced by the sweep engine as it moves
              collected money off these accounts.</p>
            </div>
          </Card>

          <Card className="animate-fade-in" style={{ animationDelay: '0.25s' }}>
            <DataTable
              data={settlements}
              keyExtractor={(s) => s.id}
              emptyTitle="No settlements found"
              columns={[
                { key: 'settlementDate', header: 'Run date', render: (_, s) => <span className="font-mono text-body-sm">{s.settlementDate}</span> },
                { key: 'currencyCode', header: 'Currency', render: (_, s) => <span className="body-strong">{s.currencyCode}</span> },
                { key: 'grossAmount', header: 'Gross', align: 'right', render: (_, s) => <span className="font-medium tracking-tight">{formatCurrency(s.grossAmount)}</span> },
                { key: 'fees', header: 'Fees', align: 'right', render: (_, s) => <span className="text-error-600 font-medium dark:text-error-300">{s.fees > 0 ? `-${formatCurrency(s.fees)}` : '—'}</span> },
                { key: 'netAmount', header: 'Net', align: 'right', render: (_, s) => <span className="font-medium tracking-tight">{formatCurrency(s.netAmount)}</span> },
                { key: 'accountsSwept', header: 'Accounts', align: 'right', render: (_, s) => <span className="body-sm">{s.accountsSwept}</span> },
                { key: 'transactionCount', header: 'Sweeps', align: 'right', render: (_, s) => <span className="body-sm">{s.transactionCount}</span> },
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
                <p className="label mb-1">Reference</p>
                <p className="font-mono font-medium text-neutral-900 dark:text-neutral-50">{selectedItem.reference}</p>
              </div>
              <div>
                <p className="label mb-1">Collected into</p>
                <p className="font-medium text-neutral-900 dark:text-neutral-50">{selectedItem.vaName}</p>
              </div>
              <div>
                <p className="label mb-1">Amount</p>
                {/* Phase 12 Task E: .stat-value-xs replaces the raw `text-heading-sm font-bold` hand-roll (same 20px scale). */}
                <p className="stat-value-xs">{formatCurrency(selectedItem.amount, selectedItem.currencyCode || 'AED')}</p>
              </div>
              <div>
                <p className="label mb-1">Status</p>
                {getStatusBadge(selectedItem.status)}
              </div>
              <div>
                <p className="label mb-1">Channel</p>
                <Badge variant="neutral">{selectedItem.channel || "—"}</Badge>
              </div>
              <div>
                <p className="label mb-1">Date</p>
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
