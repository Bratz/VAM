import React, { useState, useEffect } from 'react';
import { CreditCard, Search, Loader2, AlertCircle, RefreshCw, Eye, Download } from 'lucide-react';
import { Card, Button, Badge, Input , StatusIconBadge } from '../components/ui';
import { Modal } from '../components/ui/enhanced';
import { ecommerceApi } from '../services/api';
import { formatCurrency } from '../utils';
import { Page } from '../components/layout/Page';

const EcommerceCollectionsPage: React.FC = () => {
  const [collections, setCollections] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [filterStatus, setFilterStatus] = useState('ALL');
  const [showDetailModal, setShowDetailModal] = useState(false);
  const [selectedCollection, setSelectedCollection] = useState<any>(null);

  const fetchData = async () => {
    try {
      setLoading(true);
      setError(null);
      const res = await ecommerceApi.getCollections();
      if (res.success) setCollections(res.data);
    } catch (err: any) {
      setError(err.message || 'Failed to load collections');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchData(); }, []);

  const viewDetails = async (id: string) => {
    try {
      const res = await ecommerceApi.getCollectionById(id);
      if (res.success) {
        setSelectedCollection(res.data);
        setShowDetailModal(true);
      }
    } catch (err) {
      console.error(err);
    }
  };

  const filteredCollections = collections.filter(c => {
    const matchesSearch = searchQuery === '' ||
      c.transactionRef?.toLowerCase().includes(searchQuery.toLowerCase()) ||
      c.merchantName?.toLowerCase().includes(searchQuery.toLowerCase());
    const matchesStatus = filterStatus === 'ALL' || c.status === filterStatus;
    return matchesSearch && matchesStatus;
  });

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

      {/* Filters */}
      <Card className="animate-fade-in" style={{ animationDelay: '0.1s' }}>
        <div className="p-4 flex gap-4">
          <div className="flex-1 relative">
            <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-neutral-400 dark:text-neutral-500" />
            <Input className="pl-9" placeholder="Search by reference or merchant..." value={searchQuery} onChange={e => setSearchQuery(e.target.value)} />
          </div>
          <select className="border border-neutral-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500 dark:border-primary-800 dark:bg-primary-900" value={filterStatus} onChange={e => setFilterStatus(e.target.value)}>
            <option value="ALL">All Status</option>
            <option value="COMPLETED">Completed</option>
            <option value="PENDING">Pending</option>
            <option value="FAILED">Failed</option>
          </select>
        </div>
      </Card>

      {error && (
        <Card className="bg-error-50 border-error-200 animate-fade-in dark:bg-error-500/10 dark:border-error-500/30" style={{ animationDelay: '0.15s' }}>
          <div className="flex items-center gap-4 p-4">
            <StatusIconBadge tone="error" icon={AlertCircle} className="dark:bg-error-500/20" />
            <span className="text-error-800 dark:text-error-300">{error}</span>
          </div>
        </Card>
      )}

      {/* Collections Table */}
      <Card className="animate-fade-in" style={{ animationDelay: '0.15s' }}>
        <div className="overflow-x-auto">
          <table className="w-full">
            <thead className="bg-neutral-50 border-b border-neutral-100 dark:bg-primary-950 dark:border-primary-800/60">
              <tr>
                <th className="text-left p-4 label">Reference</th>
                <th className="text-left p-4 label">Merchant</th>
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
                      <CreditCard className="w-4 h-4 text-primary-600 dark:text-primary-200" />
                      <span className="font-mono text-sm">{c.transactionRef}</span>
                    </div>
                  </td>
                  <td className="p-4">
                    <p className="text-sm font-medium text-neutral-900 dark:text-neutral-50">{c.merchantName}</p>
                    <p className="text-xs text-neutral-500 dark:text-neutral-400">{c.merchantId}</p>
                  </td>
                  <td className="p-4"><Badge variant="neutral">{c.paymentMethod}</Badge></td>
                  <td className="p-4 text-right font-medium tracking-tight">{formatCurrency(c.amount, c.currencyCode || 'AED')}</td>
                  <td className="p-4">{getStatusBadge(c.status)}</td>
                  <td className="p-4 text-sm text-neutral-600 dark:text-neutral-300">{new Date(c.transactionDate).toLocaleString()}</td>
                  <td className="p-4 text-right">
                    <Button size="sm" variant="ghost" onClick={() => viewDetails(c.id)}>
                      <Eye className="w-4 h-4" />
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {filteredCollections.length === 0 && (
          <div className="flex flex-col items-center justify-center py-16">
            <StatusIconBadge tone="neutral" icon={CreditCard} size="lg" className="mb-4 dark:bg-primary-800" />
            <p className="text-sm text-neutral-500 dark:text-neutral-400">No collections found</p>
          </div>
        )}
      </Card>

      {/* Detail Modal */}
      <Modal isOpen={showDetailModal} onClose={() => setShowDetailModal(false)} title="Collection Details" size="lg">
        {selectedCollection && (
          <div className="space-y-6">
            <div className="grid grid-cols-2 gap-6">
              <div>
                <p className="text-xs text-neutral-500 uppercase tracking-wider mb-1 dark:text-neutral-400">Transaction Ref</p>
                <p className="font-mono font-medium text-neutral-900 dark:text-neutral-50">{selectedCollection.transactionRef}</p>
              </div>
              <div>
                <p className="text-xs text-neutral-500 uppercase tracking-wider mb-1 dark:text-neutral-400">Merchant</p>
                <p className="font-medium text-neutral-900 dark:text-neutral-50">{selectedCollection.merchantName}</p>
              </div>
              <div>
                <p className="text-xs text-neutral-500 uppercase tracking-wider mb-1 dark:text-neutral-400">Amount</p>
                <p className="text-xl font-bold tracking-tight text-neutral-900 dark:text-neutral-50">{formatCurrency(selectedCollection.amount, selectedCollection.currencyCode || 'AED')}</p>
              </div>
              <div>
                <p className="text-xs text-neutral-500 uppercase tracking-wider mb-1 dark:text-neutral-400">Status</p>
                {getStatusBadge(selectedCollection.status)}
              </div>
              <div>
                <p className="text-xs text-neutral-500 uppercase tracking-wider mb-1 dark:text-neutral-400">Payment Method</p>
                <p className="font-medium text-neutral-900 dark:text-neutral-50">{selectedCollection.paymentMethod}</p>
              </div>
              <div>
                <p className="text-xs text-neutral-500 uppercase tracking-wider mb-1 dark:text-neutral-400">Card Type</p>
                <p className="font-medium text-neutral-900 dark:text-neutral-50">{selectedCollection.cardType || '-'}</p>
              </div>
              <div>
                <p className="text-xs text-neutral-500 uppercase tracking-wider mb-1 dark:text-neutral-400">Auth Code</p>
                <p className="font-mono text-neutral-900 dark:text-neutral-50">{selectedCollection.authCode || '-'}</p>
              </div>
              <div>
                <p className="text-xs text-neutral-500 uppercase tracking-wider mb-1 dark:text-neutral-400">RRN</p>
                <p className="font-mono text-neutral-900 dark:text-neutral-50">{selectedCollection.rrn || '-'}</p>
              </div>
              <div>
                <p className="text-xs text-neutral-500 uppercase tracking-wider mb-1 dark:text-neutral-400">Settlement Status</p>
                <Badge variant="neutral">{selectedCollection.settlementStatus || 'PENDING'}</Badge>
              </div>
              <div>
                <p className="text-xs text-neutral-500 uppercase tracking-wider mb-1 dark:text-neutral-400">Transaction Date</p>
                <p className="font-medium text-neutral-900 dark:text-neutral-50">{new Date(selectedCollection.transactionDate).toLocaleString()}</p>
              </div>
            </div>
          </div>
        )}
      </Modal>
    </Page>
  );
};

export default EcommerceCollectionsPage;
