import React, { useState, useEffect } from 'react';
import { Store, Plus, Search, CheckCircle, Loader2, AlertCircle, RefreshCw, Eye, Users, TrendingUp, Ban } from 'lucide-react';
import { Card, Button, Badge, Input , StatusIconBadge } from '../components/ui';
import { Modal } from '../components/ui/enhanced';
import { ecommerceApi } from '../services/api';
import { formatCurrency } from '../utils';
import { Page } from '../components/layout/Page';

const MerchantOnboardingPage: React.FC = () => {
  const [merchants, setMerchants] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [showDetailModal, setShowDetailModal] = useState(false);
  const [selectedMerchant, setSelectedMerchant] = useState<any>(null);
  const [processing, setProcessing] = useState(false);

  const fetchData = async () => {
    try {
      setLoading(true);
      setError(null);
      const res = await ecommerceApi.getMerchants();
      if (res.success) setMerchants(res.data);
    } catch (err: any) {
      setError(err.message || 'Failed to load merchants');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchData(); }, []);

  const handleCreate = async (data: any) => {
    setProcessing(true);
    try {
      const res = await ecommerceApi.onboardMerchant(data);
      if (res.success) {
        await fetchData();
        setShowCreateModal(false);
      }
    } finally {
      setProcessing(false);
    }
  };

  const handleApprove = async (id: string) => {
    setProcessing(true);
    try {
      await ecommerceApi.approveMerchant(id);
      await fetchData();
    } finally {
      setProcessing(false);
    }
  };

  const viewDetails = async (id: string) => {
    try {
      const res = await ecommerceApi.getMerchantById(id);
      if (res.success) {
        setSelectedMerchant(res.data);
        setShowDetailModal(true);
      }
    } catch (err) {
      console.error(err);
    }
  };

  const filteredMerchants = merchants.filter(m =>
    searchQuery === '' ||
    m.merchantName?.toLowerCase().includes(searchQuery.toLowerCase()) ||
    m.merchantId?.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const getStatusBadge = (status: string) => {
    const variants: Record<string, 'success' | 'warning' | 'error' | 'neutral'> = {
      ACTIVE: 'success', PENDING: 'warning', PENDING_APPROVAL: 'warning', SUSPENDED: 'error'
    };
    return <Badge variant={variants[status] || 'neutral'}>{status.replace('_', ' ')}</Badge>;
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
        <Button leftIcon={<Plus className="w-4 h-4" />} onClick={() => setShowCreateModal(true)}>Onboard Merchant</Button>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-4 gap-4 animate-fade-in" style={{ animationDelay: '0.1s' }}>
        <Card hover>
          <div className="p-4">
            <div className="flex items-center justify-between">
              <StatusIconBadge tone="primary" icon={Users} className="dark:bg-primary-700" />
            </div>
            <p className="stat-value-sm mt-3">{merchants.length}</p>
            <p className="text-xs text-neutral-500 uppercase tracking-wider dark:text-neutral-400">Total Merchants</p>
          </div>
        </Card>
        <Card hover>
          <div className="p-4">
            <div className="flex items-center justify-between">
              <StatusIconBadge tone="success" icon={TrendingUp} className="dark:bg-success-500/20" />
            </div>
            <p className="stat-value-success mt-3">{merchants.filter(m => m.status === 'ACTIVE').length}</p>
            <p className="text-xs text-neutral-500 uppercase tracking-wider dark:text-neutral-400">Active</p>
          </div>
        </Card>
        <Card hover>
          <div className="p-4">
            <div className="flex items-center justify-between">
              <StatusIconBadge tone="warning" icon={AlertCircle} className="dark:bg-warning-500/20" />
            </div>
            <p className="stat-value-warning mt-3">{merchants.filter(m => m.status === 'PENDING').length}</p>
            <p className="text-xs text-neutral-500 uppercase tracking-wider dark:text-neutral-400">Pending Approval</p>
          </div>
        </Card>
        <Card hover>
          <div className="p-4">
            <div className="flex items-center justify-between">
              <StatusIconBadge tone="error" icon={Ban} className="dark:bg-error-500/20" />
            </div>
            <p className="stat-value-error mt-3">{merchants.filter(m => m.status === 'SUSPENDED').length}</p>
            <p className="text-xs text-neutral-500 uppercase tracking-wider dark:text-neutral-400">Suspended</p>
          </div>
        </Card>
      </div>

      {/* Search */}
      <Card className="animate-fade-in" style={{ animationDelay: '0.15s' }}>
        <div className="p-4 relative">
          <Search className="w-4 h-4 absolute left-7 top-1/2 -translate-y-1/2 text-neutral-400 dark:text-neutral-500" />
          <Input className="pl-9" placeholder="Search by name or ID..." value={searchQuery} onChange={e => setSearchQuery(e.target.value)} />
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

      {/* Merchants Table */}
      <Card className="animate-fade-in" style={{ animationDelay: '0.2s' }}>
        <div className="overflow-x-auto">
          <table className="w-full">
            <thead className="bg-neutral-50 border-b border-neutral-100 dark:bg-primary-950 dark:border-primary-800/60">
              <tr>
                <th className="text-left p-4 label">Merchant</th>
                <th className="text-left p-4 label">Category</th>
                <th className="text-left p-4 label">Commission</th>
                <th className="text-right p-4 label">Monthly Volume</th>
                <th className="text-left p-4 label">Status</th>
                <th className="text-left p-4 label">Onboarded</th>
                <th className="text-right p-4 label">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-neutral-100 dark:divide-primary-800/60">
              {filteredMerchants.map(m => (
                <tr key={m.id} className="hover:bg-neutral-50 transition-colors dark:hover:bg-primary-800/50">
                  <td className="p-4">
                    <div className="flex items-center gap-3">
                      <StatusIconBadge tone="primary" icon={Store} className="dark:bg-primary-700" />
                      <div>
                        <p className="font-medium text-neutral-900 dark:text-neutral-50">{m.merchantName}</p>
                        <p className="text-xs text-neutral-500 dark:text-neutral-400">{m.merchantId}</p>
                      </div>
                    </div>
                  </td>
                  <td className="p-4"><Badge variant="neutral">{m.category}</Badge></td>
                  <td className="p-4 text-sm font-medium">{m.commissionRate}%</td>
                  <td className="p-4 text-right font-medium tracking-tight">{formatCurrency(m.monthlyVolume, 'AED')}</td>
                  <td className="p-4">{getStatusBadge(m.status)}</td>
                  <td className="p-4 text-sm text-neutral-600 dark:text-neutral-300">{new Date(m.onboardedAt).toLocaleDateString()}</td>
                  <td className="p-4 text-right">
                    <div className="flex justify-end gap-2">
                      <Button size="sm" variant="ghost" onClick={() => viewDetails(m.id)}><Eye className="w-4 h-4" /></Button>
                      {m.status === 'PENDING' && (
                        <Button size="sm" variant="outline" onClick={() => handleApprove(m.id)} disabled={processing}>
                          <CheckCircle className="w-4 h-4 mr-1" /> Approve
                        </Button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {filteredMerchants.length === 0 && (
          <div className="flex flex-col items-center justify-center py-16">
            <StatusIconBadge tone="neutral" icon={Store} size="lg" className="mb-4 dark:bg-primary-800" />
            <p className="text-sm text-neutral-500 dark:text-neutral-400">No merchants found</p>
          </div>
        )}
      </Card>

      {/* Create Modal */}
      <Modal isOpen={showCreateModal} onClose={() => setShowCreateModal(false)} title="Onboard Merchant" size="lg">
        <MerchantForm onSubmit={handleCreate} loading={processing} onCancel={() => setShowCreateModal(false)} />
      </Modal>

      {/* Detail Modal */}
      <Modal isOpen={showDetailModal} onClose={() => setShowDetailModal(false)} title="Merchant Details" size="lg">
        {selectedMerchant && <MerchantDetail merchant={selectedMerchant} />}
      </Modal>
    </Page>
  );
};

const MerchantForm: React.FC<{ onSubmit: (data: any) => void; loading: boolean; onCancel: () => void }> = ({ onSubmit, loading, onCancel }) => {
  const [formData, setFormData] = useState({
    merchantName: '', tradeName: '', legalName: '', registrationNumber: '', category: 'Retail',
    mcc: '5411', contactName: '', contactEmail: '', contactPhone: '', settlementAccount: '', commissionRate: '2.0'
  });

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-4">
        <div><label className="field-label block mb-1">Merchant Name *</label>
          <Input value={formData.merchantName} onChange={e => setFormData({ ...formData, merchantName: e.target.value })} /></div>
        <div><label className="field-label block mb-1">Trade Name</label>
          <Input value={formData.tradeName} onChange={e => setFormData({ ...formData, tradeName: e.target.value })} /></div>
        <div><label className="field-label block mb-1">Legal Name</label>
          <Input value={formData.legalName} onChange={e => setFormData({ ...formData, legalName: e.target.value })} /></div>
        <div><label className="field-label block mb-1">Registration Number</label>
          <Input value={formData.registrationNumber} onChange={e => setFormData({ ...formData, registrationNumber: e.target.value })} /></div>
        <div><label className="field-label block mb-1">Category</label>
          <select className="w-full border rounded-lg px-3 py-2 dark:border-primary-800 dark:bg-primary-900" value={formData.category} onChange={e => setFormData({ ...formData, category: e.target.value })}>
            <option value="Retail">Retail</option><option value="F&B">F&B</option><option value="Electronics">Electronics</option><option value="Fashion">Fashion</option><option value="Services">Services</option>
          </select></div>
        <div><label className="field-label block mb-1">MCC</label>
          <Input value={formData.mcc} onChange={e => setFormData({ ...formData, mcc: e.target.value })} /></div>
      </div>
      <hr />
      <h4 className="font-medium">Contact Information</h4>
      <div className="grid grid-cols-3 gap-4">
        <div><label className="field-label block mb-1">Contact Name</label>
          <Input value={formData.contactName} onChange={e => setFormData({ ...formData, contactName: e.target.value })} /></div>
        <div><label className="field-label block mb-1">Email</label>
          <Input type="email" value={formData.contactEmail} onChange={e => setFormData({ ...formData, contactEmail: e.target.value })} /></div>
        <div><label className="field-label block mb-1">Phone</label>
          <Input value={formData.contactPhone} onChange={e => setFormData({ ...formData, contactPhone: e.target.value })} /></div>
      </div>
      <hr />
      <h4 className="font-medium">Settlement</h4>
      <div className="grid grid-cols-2 gap-4">
        <div><label className="field-label block mb-1">Settlement Account</label>
          <Input value={formData.settlementAccount} onChange={e => setFormData({ ...formData, settlementAccount: e.target.value })} /></div>
        <div><label className="field-label block mb-1">Commission Rate (%)</label>
          <Input type="number" step="0.1" value={formData.commissionRate} onChange={e => setFormData({ ...formData, commissionRate: e.target.value })} /></div>
      </div>
      <div className="flex justify-end gap-2 pt-4 border-t dark:border-primary-800">
        <Button variant="ghost" onClick={onCancel}>Cancel</Button>
        <Button onClick={() => onSubmit({ ...formData, commissionRate: parseFloat(formData.commissionRate) })} disabled={loading || !formData.merchantName}>
          {loading && <Loader2 className="w-4 h-4 animate-spin mr-2" />} Submit Application
        </Button>
      </div>
    </div>
  );
};

const MerchantDetail: React.FC<{ merchant: any }> = ({ merchant }) => (
  <div className="space-y-6">
    <div className="grid grid-cols-2 gap-6">
      <div>
        <p className="text-xs text-neutral-500 uppercase tracking-wider mb-1 dark:text-neutral-400">Merchant ID</p>
        <p className="font-mono font-medium text-neutral-900 dark:text-neutral-50">{merchant.merchantId}</p>
      </div>
      <div>
        <p className="text-xs text-neutral-500 uppercase tracking-wider mb-1 dark:text-neutral-400">Merchant Name</p>
        <p className="font-medium text-neutral-900 dark:text-neutral-50">{merchant.merchantName}</p>
      </div>
      <div>
        <p className="text-xs text-neutral-500 uppercase tracking-wider mb-1 dark:text-neutral-400">Legal Name</p>
        <p className="font-medium text-neutral-900 dark:text-neutral-50">{merchant.legalName || '-'}</p>
      </div>
      <div>
        <p className="text-xs text-neutral-500 uppercase tracking-wider mb-1 dark:text-neutral-400">Category</p>
        <Badge variant="neutral">{merchant.category}</Badge>
      </div>
      <div>
        <p className="text-xs text-neutral-500 uppercase tracking-wider mb-1 dark:text-neutral-400">MCC</p>
        <p className="font-mono text-neutral-900 dark:text-neutral-50">{merchant.mcc}</p>
      </div>
      <div>
        <p className="text-xs text-neutral-500 uppercase tracking-wider mb-1 dark:text-neutral-400">Commission Rate</p>
        <p className="font-medium text-neutral-900 dark:text-neutral-50">{merchant.commissionRate}%</p>
      </div>
      <div>
        <p className="text-xs text-neutral-500 uppercase tracking-wider mb-1 dark:text-neutral-400">Settlement Account</p>
        <p className="font-mono text-neutral-900 dark:text-neutral-50">{merchant.settlementAccount}</p>
      </div>
      <div>
        <p className="text-xs text-neutral-500 uppercase tracking-wider mb-1 dark:text-neutral-400">Status</p>
        <Badge variant={merchant.status === 'ACTIVE' ? 'success' : 'warning'}>{merchant.status}</Badge>
      </div>
    </div>
    {merchant.monthlyStats && (
      <>
        <hr className="border-neutral-200 dark:border-primary-800" />
        <h4 className="text-sm font-semibold text-neutral-900 uppercase tracking-wider dark:text-neutral-50">Monthly Statistics</h4>
        <div className="grid grid-cols-4 gap-4">
          <div>
            <p className="text-xs text-neutral-500 uppercase tracking-wider mb-1 dark:text-neutral-400">Volume</p>
            <p className="font-bold tracking-tight text-neutral-900 dark:text-neutral-50">{formatCurrency(merchant.monthlyStats.volume, 'AED')}</p>
          </div>
          <div>
            <p className="text-xs text-neutral-500 uppercase tracking-wider mb-1 dark:text-neutral-400">Transactions</p>
            <p className="font-bold tracking-tight text-neutral-900 dark:text-neutral-50">{merchant.monthlyStats.transactions}</p>
          </div>
          <div>
            <p className="text-xs text-neutral-500 uppercase tracking-wider mb-1 dark:text-neutral-400">Avg Ticket</p>
            <p className="font-bold tracking-tight text-neutral-900 dark:text-neutral-50">{formatCurrency(merchant.monthlyStats.avgTicket, 'AED')}</p>
          </div>
          <div>
            <p className="text-xs text-neutral-500 uppercase tracking-wider mb-1 dark:text-neutral-400">Chargebacks</p>
            <p className="font-bold tracking-tight text-neutral-900 dark:text-neutral-50">{merchant.monthlyStats.chargebacks}</p>
          </div>
        </div>
      </>
    )}
  </div>
);

export default MerchantOnboardingPage;
