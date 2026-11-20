import React, { useState, useEffect } from 'react';
import { ShoppingBag, Search, Loader2, RefreshCw, Eye, TrendingUp, Clock, CheckCircle, XCircle } from 'lucide-react';
import { Card, Button, Badge, Input, StatusIconBadge, StatTile, DataTable } from '../components/ui';
import { Modal, Tabs } from '../components/ui/enhanced';
import { ecommerceApi } from '../services/api';
import { formatCurrency } from '../utils';
import { Page } from '../components/layout/Page';

// One page for the `seller-collections` and `ecommerce-collections` routes: both used to load the
// same ecommerceApi.getCollections() with different detail modals (round-3 merge).
const SellerCollectionsPage: React.FC = () => {
  const [collections, setCollections] = useState<any[]>([]);
  const [settlements, setSettlements] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [filterStatus, setFilterStatus] = useState('ALL');
  const [activeTab, setActiveTab] = useState<'collections' | 'settlements'>('collections');
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

  const q = searchQuery.toLowerCase();
  const filteredCollections = collections.filter(c =>
    (q === '' ||
      c.reference?.toLowerCase().includes(q) ||
      c.remitterName?.toLowerCase().includes(q) ||
      c.vaName?.toLowerCase().includes(q)) &&
    (filterStatus === 'ALL' || c.status === filterStatus)
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
        <Button variant="outline" leftIcon={<RefreshCw className="w-4 h-4" />} onClick={fetchData}>Refresh</Button>
      </div>

      {/* Summary Stats — the collection count is the tab label */}
      <div className="grid grid-cols-3 gap-4">
        <StatTile layout="row" tone="success" label="Total Amount" value={formatCurrency(collections.reduce((sum, c) => sum + (c.amount || 0), 0))} icon={<TrendingUp className="w-5 h-5" />} delay="0.1s" />
        <StatTile layout="row" tone="neutral" label="Accounts Swept" value={settlements.reduce((sum, s) => sum + s.accountsSwept, 0)} icon={<Clock className="w-5 h-5" />} delay="0.1s" />
        <StatTile layout="row" tone="info" label="Settled Amount" value={formatCurrency(settlements.reduce((sum, s) => sum + (s.netAmount || 0), 0))} icon={<CheckCircle className="w-5 h-5" />} delay="0.1s" />
      </div>

      {error && (
        <Card className="bg-error-50 border-error-200 animate-fade-in dark:bg-error-500/10 dark:border-error-500/30" style={{ animationDelay: '0.15s' }}>
          <div className="flex items-center gap-4 p-4">
            <StatusIconBadge tone="error" icon={XCircle} />
            <span className="text-error-800 dark:text-error-300">{error}</span>
          </div>
        </Card>
      )}

      {/* Collections / Settlements */}
      <div className="flex animate-fade-in" style={{ animationDelay: '0.15s' }}>
        <Tabs
          size="sm"
          activeTab={activeTab}
          onChange={(id) => setActiveTab(id as typeof activeTab)}
          tabs={[
            { id: 'collections', label: 'Collections', badge: collections.length },
            { id: 'settlements', label: 'Settlements', badge: settlements.length },
          ]}
        />
      </div>

      {/* Collections */}
      {activeTab === 'collections' && (
        <>
          <Card className="animate-fade-in" style={{ animationDelay: '0.2s' }}>
            <div className="p-4 flex gap-4">
              <div className="flex-1 relative">
                <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-neutral-400" />
                <Input className="pl-9" placeholder="Search by reference, payer or account..." value={searchQuery} onChange={e => setSearchQuery(e.target.value)} />
              </div>
              <select className="border border-edge rounded-lg px-3 py-2 text-body-sm focus:outline-none focus:ring-2 focus:ring-primary-500 dark:bg-primary-900" value={filterStatus} onChange={e => setFilterStatus(e.target.value)}>
                <option value="ALL">All Status</option>
                <option value="COMPLETED">Completed</option>
                <option value="PENDING">Pending</option>
                <option value="FAILED">Failed</option>
              </select>
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
                {
                  key: 'vaName',
                  header: 'Collected into',
                  render: (_, c) => (
                    <>
                      <p className="body-strong">{c.vaName}</p>
                      <p className="caption">{[c.vaNumber, c.programName].filter(Boolean).join(' · ')}</p>
                    </>
                  ),
                },
                { key: 'remitterName', header: 'Paid by', render: (_, c) => <span className="body-sm">{c.remitterName || '—'}</span> },
                { key: 'amount', header: 'Amount', align: 'right', render: (_, c) => <span className="font-medium tracking-tight">{formatCurrency(c.amount, c.currencyCode || 'AED')}</span> },
                { key: 'status', header: 'Status', render: (_, c) => getStatusBadge(c.status) },
                { key: 'transactionDate', header: 'Date', render: (_, c) => <span className="body-sm">{new Date(c.transactionDate).toLocaleString()}</span> },
                {
                  key: 'actions',
                  header: 'Actions',
                  align: 'right',
                  render: (_, c) => (
                    <Button size="sm" variant="ghost" aria-label="View details" onClick={() => setSelectedItem(c)}>
                      <Eye className="w-4 h-4" />
                    </Button>
                  ),
                },
              ]}
            />
          </Card>
        </>
      )}

      {/* Settlements */}
      {activeTab === 'settlements' && (
        <Card className="animate-fade-in" style={{ animationDelay: '0.25s' }}>
          <DataTable
            data={settlements}
            keyExtractor={(s) => s.id}
            emptyTitle="No settlements found"
            emptyDescription="Settlement runs appear as sweeps move collected money off these accounts."
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
      )}

      {/* Detail Modal — the list row is the same CollectionResponse that /collections/{id} returns */}
      <Modal isOpen={!!selectedItem} onClose={() => setSelectedItem(null)} title="Collection Details" size="lg">
        {selectedItem && (
          <div className="grid grid-cols-2 gap-6">
            <div>
              <p className="label mb-1">Reference</p>
              <p className="font-mono font-medium text-neutral-900 dark:text-neutral-50">{selectedItem.reference}</p>
            </div>
            <div>
              <p className="label mb-1">Collected into</p>
              <p className="font-medium text-neutral-900 dark:text-neutral-50">{selectedItem.vaName}</p>
              {selectedItem.vaNumber && <p className="caption font-mono">{selectedItem.vaNumber}</p>}
            </div>
            <div>
              <p className="label mb-1">Amount</p>
              <p className="stat-value-xs">{formatCurrency(selectedItem.amount, selectedItem.currencyCode || 'AED')}</p>
            </div>
            <div>
              <p className="label mb-1">Status</p>
              {getStatusBadge(selectedItem.status)}
            </div>
            <div>
              <p className="label mb-1">Paid by</p>
              <p className="font-medium text-neutral-900 dark:text-neutral-50">{selectedItem.remitterName || '—'}</p>
            </div>
            <div>
              <p className="label mb-1">Channel</p>
              <Badge variant="neutral">{selectedItem.channel || '—'}</Badge>
            </div>
            <div>
              <p className="label mb-1">Card Type</p>
              <p className="font-medium text-neutral-900 dark:text-neutral-50">{selectedItem.cardType || '—'}</p>
            </div>
            <div>
              <p className="label mb-1">Auth Code</p>
              <p className="font-mono text-neutral-900 dark:text-neutral-50">{selectedItem.authCode || '—'}</p>
            </div>
            <div>
              <p className="label mb-1">RRN</p>
              <p className="font-mono text-neutral-900 dark:text-neutral-50">{selectedItem.rrn || '—'}</p>
            </div>
            <div>
              <p className="label mb-1">Settlement Status</p>
              <Badge variant="neutral">{selectedItem.settlementStatus || 'PENDING'}</Badge>
            </div>
            <div>
              <p className="label mb-1">Date</p>
              <p className="font-medium text-neutral-900 dark:text-neutral-50">{new Date(selectedItem.transactionDate).toLocaleString()}</p>
            </div>
          </div>
        )}
      </Modal>
    </Page>
  );
};

export default SellerCollectionsPage;
