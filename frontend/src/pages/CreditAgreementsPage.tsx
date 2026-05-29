import { Page } from '../components/layout/Page';
import { StatStrip } from '../components/layout/StatStrip';
/**
 * CreditAgreementsPage - Connected to Backend
 * 
 * Uses: creditAgreementsApi from creditApi.ts
 * Backend: CreditAgreementController.java at /api/v1/credit-agreements/*
 */
import React, { useState, useEffect, useCallback } from 'react';
import {
  FileText, Plus, RefreshCw, Search, Loader2,
  Calendar, CheckCircle2, XCircle, Clock, Ban,
  Eye, TrendingUp, ArrowUpRight, ArrowDownRight,
  Shield, AlertTriangle, Building2, Landmark,
} from 'lucide-react';
import { Card, Button, Badge, Input , StatusIconBadge } from '../components/ui';
import { CurrencyPicker } from '../components/ui/CurrencyPicker';
import { Modal } from '../components/ui/enhanced';
import { formatCurrency, cn, formatDate } from '../utils';
import { creditAgreementsApi, CreditAgreement, AgreementType, AgreementStatus, ApiResponse } from '../services/api';

// ============================================================================
// CONSTANTS
// ============================================================================

const STATUS_CONFIG: Record<string, { label: string; variant: string; icon: any }> = {
  DRAFT: { label: 'Draft', variant: 'neutral', icon: FileText },
  PENDING_APPROVAL: { label: 'Pending', variant: 'warning', icon: Clock },
  ACTIVE: { label: 'Active', variant: 'success', icon: CheckCircle2 },
  SUSPENDED: { label: 'Suspended', variant: 'error', icon: Ban },
  EXPIRED: { label: 'Expired', variant: 'neutral', icon: XCircle },
  CANCELLED: { label: 'Cancelled', variant: 'neutral', icon: XCircle },
};

const AGREEMENT_TYPE_CONFIG: Record<string, { label: string; icon: any; color: string; bgColor: string }> = {
  MASTER: { label: 'Master Agreement', icon: Shield, color: 'text-purple-700 dark:text-purple-300', bgColor: 'bg-purple-100 dark:bg-purple-500/20' },
  FACILITY: { label: 'Facility Agreement', icon: Building2, color: 'text-info-700 dark:text-info-300', bgColor: 'bg-info-100 dark:bg-info-500/20' },
  BILATERAL: { label: 'Bilateral', icon: FileText, color: 'text-success-700 dark:text-success-300', bgColor: 'bg-success-100 dark:bg-success-500/20' },
  SYNDICATED: { label: 'Syndicated', icon: Landmark, color: 'text-amber-700 dark:text-amber-300', bgColor: 'bg-amber-100 dark:bg-amber-500/20' },
  REVOLVING: { label: 'Revolving', icon: RefreshCw, color: 'text-teal-700 dark:text-teal-300', bgColor: 'bg-teal-100 dark:bg-teal-500/20' },
  TERM: { label: 'Term', icon: Calendar, color: 'text-indigo-700 dark:text-indigo-300', bgColor: 'bg-indigo-100 dark:bg-indigo-500/20' },
};

// Helper to extract data
const extractData = <T,>(response: ApiResponse<T>): T => {
  if (response && response.data !== undefined) return response.data;
  return response as unknown as T;
};

// ============================================================================
// UTILIZATION BAR
// ============================================================================

const UtilizationBar: React.FC<{ utilized: number; limit: number; currency: string }> = ({ utilized, limit, currency }) => {
  const pct = limit > 0 ? (utilized / limit) * 100 : 0;
  const getColor = () => pct >= 90 ? 'bg-error-500' : pct >= 75 ? 'bg-amber-500' : 'bg-success-500';
  return (
    <div className="space-y-1">
      <div className="relative h-2 bg-neutral-200 rounded-full overflow-hidden dark:bg-primary-800">
        <div className={cn("absolute left-0 top-0 h-full transition-all rounded-full", getColor())} style={{ width: `${Math.min(pct, 100)}%` }} />
      </div>
      <div className="flex justify-between text-xs text-neutral-500 dark:text-neutral-400">
        <span>{formatCurrency(utilized, currency)} / {formatCurrency(limit, currency)}</span>
        <span>{pct.toFixed(1)}%</span>
      </div>
    </div>
  );
};

// ============================================================================
// AGREEMENT CARD
// ============================================================================

interface AgreementCardProps {
  agreement: CreditAgreement;
  onView: () => void;
  onUtilize: () => void;
  onRelease: () => void;
}

const AgreementCard: React.FC<AgreementCardProps> = ({ agreement, onView, onUtilize, onRelease }) => {
  const statusConfig = STATUS_CONFIG[agreement.status] || STATUS_CONFIG.DRAFT;
  const typeConfig = AGREEMENT_TYPE_CONFIG[agreement.agreementType] || AGREEMENT_TYPE_CONFIG.BILATERAL;
  const TypeIcon = typeConfig.icon;
  const daysToExpiry = Math.ceil((new Date(agreement.expiryDate).getTime() - Date.now()) / (1000 * 60 * 60 * 24));
  const isExpiringSoon = daysToExpiry > 0 && daysToExpiry <= 90;

  return (
    <Card className={cn("p-4 hover:shadow-md transition-shadow", isExpiringSoon && agreement.status === 'ACTIVE' && "border-amber-300 bg-amber-50/30")}>
      <div className="flex items-start justify-between">
        <div className="flex items-center gap-3">
          <div className={cn("w-10 h-10 rounded-xl flex items-center justify-center", typeConfig.bgColor)}>
            <TypeIcon className={cn("w-5 h-5", typeConfig.color)} />
          </div>
          <div>
            <h3 className="font-semibold text-primary-900 line-clamp-1 dark:text-neutral-50">{agreement.agreementName}</h3>
            <p className="text-sm text-neutral-500 dark:text-neutral-400">{agreement.counterpartyBankName || 'Bank'}</p>
          </div>
        </div>
        <Badge className={cn("text-xs", typeConfig.bgColor, typeConfig.color)}>{typeConfig.label}</Badge>
      </div>

      {agreement.externalReference && (
        <p className="mt-2 text-xs text-neutral-500 dark:text-neutral-400">Ref: {agreement.externalReference}</p>
      )}

      <div className="mt-4">
        <div className="flex items-baseline justify-between mb-2">
          <span className="stat-value-sm">{formatCurrency(agreement.totalLimit, agreement.limitCurrency)}</span>
          <Badge variant={statusConfig.variant as any} size="sm">{statusConfig.label}</Badge>
        </div>
        <UtilizationBar utilized={agreement.totalUtilized} limit={agreement.totalLimit} currency={agreement.limitCurrency} />
      </div>

      <div className="mt-4 grid grid-cols-2 gap-2 text-xs">
        <div><span className="text-neutral-500 dark:text-neutral-400">Available:</span><span className={cn("ml-1 font-medium", agreement.availableLimit > 0 ? "text-success-600 dark:text-success-300" : "text-error-600 dark:text-error-300")}>{formatCurrency(agreement.availableLimit, agreement.limitCurrency)}</span></div>
        <div className="flex items-center gap-1 text-neutral-500 dark:text-neutral-400"><Calendar className="w-3 h-3" /><span>Expires: {formatDate(agreement.expiryDate)}</span></div>
        {agreement.baseRateType && <div><span className="text-neutral-500 dark:text-neutral-400">Rate:</span><span className="ml-1 font-medium">{agreement.baseRateType} + {agreement.spreadBps}bps</span></div>}
        {isExpiringSoon && <div className="flex items-center gap-1 text-amber-600 dark:text-amber-300"><AlertTriangle className="w-3 h-3" /><span>{daysToExpiry} days left</span></div>}
      </div>

      <div className="mt-4 pt-3 border-t flex gap-2">
        <Button variant="ghost" size="sm" onClick={onView} className="flex-1"><Eye className="w-3 h-3 mr-1" /> View</Button>
        {agreement.status === 'ACTIVE' && (
          <>
            <Button variant="ghost" size="sm" onClick={onUtilize} className="flex-1" disabled={agreement.availableLimit <= 0}><ArrowUpRight className="w-3 h-3 mr-1" /> Utilize</Button>
            <Button variant="ghost" size="sm" onClick={onRelease} className="flex-1" disabled={agreement.totalUtilized <= 0}><ArrowDownRight className="w-3 h-3 mr-1" /> Release</Button>
          </>
        )}
      </div>
    </Card>
  );
};

// ============================================================================
// MAIN PAGE
// ============================================================================

const CreditAgreementsPage: React.FC = () => {
  const [agreements, setAgreements] = useState<CreditAgreement[]>([]);
  const [loading, setLoading] = useState(true);
  const [syncing, setSyncing] = useState(false);
  const [searchTerm, setSearchTerm] = useState('');
  const [filterType, setFilterType] = useState<string>('');
  const [filterStatus, setFilterStatus] = useState<string>('');
  const [corporateId] = useState('corp-1'); // TODO: Get from context

  const [showCreateModal, setShowCreateModal] = useState(false);
  const [showDetailModal, setShowDetailModal] = useState(false);
  const [showUtilizeModal, setShowUtilizeModal] = useState(false);
  const [showReleaseModal, setShowReleaseModal] = useState(false);
  const [selectedAgreement, setSelectedAgreement] = useState<CreditAgreement | null>(null);
  const [actionAmount, setActionAmount] = useState(0);
  const [processing, setProcessing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [createForm, setCreateForm] = useState({
    agreementName: '',
    agreementType: 'MASTER' as AgreementType,
    counterpartyBankName: '',
    externalReference: '',
    totalLimit: '',
    limitCurrency: 'AED',
    effectiveDate: new Date().toISOString().split('T')[0],
    expiryDate: '',
    baseRateType: 'EIBOR',
    spreadBps: '',
  });

  // Load data from real API
  const loadData = useCallback(async () => {
    try {
      setLoading(true);
      setError(null);
      // Try to get by corporate first, fallback to getAll
      let response;
      try {
        response = await creditAgreementsApi.getByCorporate(corporateId);
      } catch {
        response = await creditAgreementsApi.getAll();
      }
      const data = extractData(response);
      setAgreements(Array.isArray(data) ? data : []);
    } catch (err) {
      console.error('Failed to load agreements:', err);
      setError('Failed to load credit agreements.');
      setAgreements([]);
    } finally {
      setLoading(false);
    }
  }, [corporateId]);

  useEffect(() => { loadData(); }, [loadData]);

  // Sync from CBS (placeholder - calls refresh)
  const handleSync = async () => {
    try {
      setSyncing(true);
      // In real implementation, this would trigger CBS sync
      await loadData();
    } catch (err) {
      console.error('Failed to sync:', err);
      setError('Failed to sync from CBS.');
    } finally {
      setSyncing(false);
    }
  };

  // Create agreement
  const handleCreate = async () => {
    if (!createForm.agreementName || !createForm.totalLimit) return;
    try {
      setProcessing(true);
      setError(null);
      await creditAgreementsApi.create({
        corporateId,
        agreementName: createForm.agreementName,
        agreementType: createForm.agreementType,
        counterpartyBankName: createForm.counterpartyBankName || undefined,
        externalReference: createForm.externalReference || undefined,
        totalLimit: parseFloat(createForm.totalLimit),
        limitCurrency: createForm.limitCurrency,
        effectiveDate: createForm.effectiveDate,
        expiryDate: createForm.expiryDate,
        baseRateType: createForm.baseRateType || undefined,
        spreadBps: createForm.spreadBps ? parseInt(createForm.spreadBps) : undefined,
      });
      await loadData();
      setShowCreateModal(false);
      setCreateForm({ agreementName: '', agreementType: 'MASTER', counterpartyBankName: '', externalReference: '', totalLimit: '', limitCurrency: 'AED', effectiveDate: new Date().toISOString().split('T')[0], expiryDate: '', baseRateType: 'EIBOR', spreadBps: '' });
    } catch (err) {
      console.error('Failed to create agreement:', err);
      setError('Failed to create credit agreement.');
    } finally {
      setProcessing(false);
    }
  };

  // Utilize
  const handleUtilize = async () => {
    if (!selectedAgreement || actionAmount <= 0) return;
    try {
      setProcessing(true);
      await creditAgreementsApi.utilize(selectedAgreement.id, actionAmount);
      await loadData();
      setShowUtilizeModal(false);
      setActionAmount(0);
    } catch (err) {
      console.error('Failed to utilize:', err);
      setError('Failed to utilize from agreement.');
    } finally {
      setProcessing(false);
    }
  };

  // Release
  const handleRelease = async () => {
    if (!selectedAgreement || actionAmount <= 0) return;
    try {
      setProcessing(true);
      await creditAgreementsApi.release(selectedAgreement.id, actionAmount);
      await loadData();
      setShowReleaseModal(false);
      setActionAmount(0);
    } catch (err) {
      console.error('Failed to release:', err);
      setError('Failed to release to agreement.');
    } finally {
      setProcessing(false);
    }
  };

  // Filter
  const filteredAgreements = agreements.filter(a => {
    const matchesSearch = !searchTerm || a.agreementName.toLowerCase().includes(searchTerm.toLowerCase()) || a.externalReference?.toLowerCase().includes(searchTerm.toLowerCase());
    const matchesType = !filterType || a.agreementType === filterType;
    const matchesStatus = !filterStatus || a.status === filterStatus;
    return matchesSearch && matchesType && matchesStatus;
  });

  // Stats
  const stats = {
    total: agreements.length,
    active: agreements.filter(a => a.status === 'ACTIVE').length,
    totalLimit: agreements.filter(a => a.status === 'ACTIVE').reduce((sum, a) => sum + a.totalLimit, 0),
    totalUtilized: agreements.filter(a => a.status === 'ACTIVE').reduce((sum, a) => sum + a.totalUtilized, 0),
  };

  if (loading) return <div className="flex items-center justify-center h-96"><Loader2 className="w-8 h-8 animate-spin text-primary-600 dark:text-primary-200" /></div>;

  return (
    <Page>
      {/* Quick Actions */}
      <div className="flex items-center justify-end gap-2 animate-fade-in" style={{ animationDelay: '0.05s' }}>
        <Button variant="outline" onClick={handleSync} disabled={syncing}>{syncing ? <Loader2 className="w-4 h-4 mr-2 animate-spin" /> : <RefreshCw className="w-4 h-4 mr-2" />}Sync from CBS</Button>
        <Button onClick={() => setShowCreateModal(true)}><Plus className="w-4 h-4 mr-2" /> Add Agreement</Button>
      </div>

      {error && (
        <Card className="bg-error-50 border-error-200 animate-fade-in dark:bg-error-500/10 dark:border-error-500/30">
          <div className="flex items-center justify-between p-4">
            <div className="flex items-center gap-3">
              <StatusIconBadge tone="error" icon={AlertTriangle} className="dark:bg-error-500/20" />
              <span className="text-error-700 font-medium dark:text-error-300">{error}</span>
            </div>
            <button onClick={() => setError(null)} className="text-error-500 hover:text-error-700 p-1">×</button>
          </div>
        </Card>
      )}

      {/* Phase 10 Task D + F (2026-05-13): replaced inline
          `grid grid-cols-4 gap-4` strip with <StatStrip>. Auto-derives
          4-column layout from child count; standardizes gap-4. */}
      <StatStrip className="animate-fade-in" data-animation-delay="0.1s">
        <Card hover className="p-4">
          <div className="flex items-start justify-between">
            <div className="flex-1">
              <p className="label">Total Agreements</p>
              <p className="stat-value-sm mt-1">{stats.total}</p>
            </div>
            <StatusIconBadge tone="info" icon={FileText} className="dark:bg-info-500/20" />
          </div>
        </Card>
        <Card hover className="p-4">
          <div className="flex items-start justify-between">
            <div className="flex-1">
              <p className="label">Active</p>
              <p className="stat-value-success mt-1">{stats.active}</p>
            </div>
            <StatusIconBadge tone="success" icon={CheckCircle2} className="dark:bg-success-500/20" />
          </div>
        </Card>
        <Card hover className="p-4">
          <div className="flex items-start justify-between">
            <div className="flex-1">
              <p className="label">Total Limit</p>
              <p className="stat-value-xs mt-1">{formatCurrency(stats.totalLimit, 'AED')}</p>
            </div>
            <StatusIconBadge tone="accent" icon={Shield} className="dark:bg-accent-500/20" />
          </div>
        </Card>
        <Card hover className="p-4">
          <div className="flex items-start justify-between">
            <div className="flex-1">
              <p className="label">Utilization</p>
              <p className="stat-value-warning mt-1">{stats.totalLimit > 0 ? ((stats.totalUtilized / stats.totalLimit) * 100).toFixed(1) : 0}%</p>
            </div>
            <StatusIconBadge tone="warning" icon={TrendingUp} className="dark:bg-warning-500/20" />
          </div>
        </Card>
      </StatStrip>

      <div className="flex gap-3 animate-fade-in" style={{ animationDelay: '0.15s' }}>
        <div className="relative flex-1"><Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400 dark:text-neutral-500" /><Input value={searchTerm} onChange={(e) => setSearchTerm(e.target.value)} placeholder="Search agreements..." className="pl-9" /></div>
        <select value={filterType} onChange={(e) => setFilterType(e.target.value)} className="px-3 py-2 border border-neutral-300 rounded-lg bg-white text-sm font-medium dark:border-primary-700 dark:bg-primary-900"><option value="">All Types</option>{Object.entries(AGREEMENT_TYPE_CONFIG).map(([key, val]) => <option key={key} value={key}>{val.label}</option>)}</select>
        <select value={filterStatus} onChange={(e) => setFilterStatus(e.target.value)} className="px-3 py-2 border border-neutral-300 rounded-lg bg-white text-sm font-medium dark:border-primary-700 dark:bg-primary-900"><option value="">All Status</option>{Object.entries(STATUS_CONFIG).map(([key, val]) => <option key={key} value={key}>{val.label}</option>)}</select>
      </div>

      {filteredAgreements.length > 0 ? (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4 animate-fade-in" style={{ animationDelay: '0.2s' }}>
          {filteredAgreements.map(agreement => (
            <AgreementCard key={agreement.id} agreement={agreement}
              onView={() => { setSelectedAgreement(agreement); setShowDetailModal(true); }}
              onUtilize={() => { setSelectedAgreement(agreement); setActionAmount(0); setShowUtilizeModal(true); }}
              onRelease={() => { setSelectedAgreement(agreement); setActionAmount(0); setShowReleaseModal(true); }}
            />
          ))}
        </div>
      ) : (
        <Card className="p-12 text-center animate-fade-in" style={{ animationDelay: '0.2s' }}><FileText className="w-12 h-12 text-neutral-300 mx-auto mb-4 dark:text-neutral-600" /><p className="text-neutral-500 dark:text-neutral-400">No credit agreements found</p><Button className="mt-4" onClick={() => setShowCreateModal(true)}><Plus className="w-4 h-4 mr-2" /> Add Agreement</Button></Card>
      )}

      {/* Create Modal */}
      <Modal isOpen={showCreateModal} onClose={() => setShowCreateModal(false)} title="Add Credit Agreement" size="lg">
        <div className="p-4 space-y-4">
          <div><label className="field-label block mb-1">Agreement Name *</label><Input value={createForm.agreementName} onChange={(e) => setCreateForm(prev => ({ ...prev, agreementName: e.target.value }))} placeholder="e.g., ENBD Master Credit Agreement" /></div>
          <div className="grid grid-cols-2 gap-4">
            <div><label className="field-label block mb-1">Agreement Type *</label><select value={createForm.agreementType} onChange={(e) => setCreateForm(prev => ({ ...prev, agreementType: e.target.value as any }))} className="w-full px-3 py-2 border border-neutral-300 rounded-lg dark:border-primary-700">{Object.entries(AGREEMENT_TYPE_CONFIG).map(([key, val]) => <option key={key} value={key}>{val.label}</option>)}</select></div>
            <div><label className="field-label block mb-1">Bank Name</label><Input value={createForm.counterpartyBankName} onChange={(e) => setCreateForm(prev => ({ ...prev, counterpartyBankName: e.target.value }))} placeholder="Emirates NBD" /></div>
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div><label className="field-label block mb-1">Total Limit *</label><Input type="number" value={createForm.totalLimit} onChange={(e) => setCreateForm(prev => ({ ...prev, totalLimit: e.target.value }))} placeholder="100000000" /></div>
            <div><label className="field-label block mb-1">Currency</label><CurrencyPicker value={createForm.limitCurrency} onChange={(c) => setCreateForm(prev => ({ ...prev, limitCurrency: c }))} /></div>
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div><label className="field-label block mb-1">Effective Date</label><Input type="date" value={createForm.effectiveDate} onChange={(e) => setCreateForm(prev => ({ ...prev, effectiveDate: e.target.value }))} /></div>
            <div><label className="field-label block mb-1">Expiry Date</label><Input type="date" value={createForm.expiryDate} onChange={(e) => setCreateForm(prev => ({ ...prev, expiryDate: e.target.value }))} /></div>
          </div>
          <div className="flex justify-end gap-2 pt-4 border-t"><Button variant="ghost" onClick={() => setShowCreateModal(false)}>Cancel</Button><Button onClick={handleCreate} disabled={processing || !createForm.agreementName || !createForm.totalLimit}>{processing ? <Loader2 className="w-4 h-4 mr-2 animate-spin" /> : <Plus className="w-4 h-4 mr-2" />}Create</Button></div>
        </div>
      </Modal>

      {/* Detail Modal */}
      <Modal isOpen={showDetailModal} onClose={() => setShowDetailModal(false)} title="Agreement Details" size="lg">
        {selectedAgreement && (
          <div className="p-4 space-y-4">
            <div className="grid grid-cols-2 gap-4 text-sm">
              <div><span className="text-neutral-500 dark:text-neutral-400">Name:</span> <span className="ml-2 font-medium">{selectedAgreement.agreementName}</span></div>
              <div><span className="text-neutral-500 dark:text-neutral-400">Type:</span> <span className="ml-2 font-medium">{AGREEMENT_TYPE_CONFIG[selectedAgreement.agreementType]?.label}</span></div>
              <div><span className="text-neutral-500 dark:text-neutral-400">Status:</span> <span className="ml-2 font-medium">{selectedAgreement.status}</span></div>
              <div><span className="text-neutral-500 dark:text-neutral-400">Bank:</span> <span className="ml-2 font-medium">{selectedAgreement.counterpartyBankName || 'N/A'}</span></div>
              <div><span className="text-neutral-500 dark:text-neutral-400">Total Limit:</span> <span className="ml-2 font-medium">{formatCurrency(selectedAgreement.totalLimit, selectedAgreement.limitCurrency)}</span></div>
              <div><span className="text-neutral-500 dark:text-neutral-400">Utilized:</span> <span className="ml-2 font-medium">{formatCurrency(selectedAgreement.totalUtilized, selectedAgreement.limitCurrency)}</span></div>
              <div><span className="text-neutral-500 dark:text-neutral-400">Available:</span> <span className="ml-2 font-medium text-success-600 dark:text-success-300">{formatCurrency(selectedAgreement.availableLimit, selectedAgreement.limitCurrency)}</span></div>
              <div><span className="text-neutral-500 dark:text-neutral-400">Expires:</span> <span className="ml-2 font-medium">{formatDate(selectedAgreement.expiryDate)}</span></div>
            </div>
            <UtilizationBar utilized={selectedAgreement.totalUtilized} limit={selectedAgreement.totalLimit} currency={selectedAgreement.limitCurrency} />
            <div className="flex justify-end pt-4 border-t"><Button variant="ghost" onClick={() => setShowDetailModal(false)}>Close</Button></div>
          </div>
        )}
      </Modal>

      {/* Utilize Modal */}
      <Modal isOpen={showUtilizeModal} onClose={() => setShowUtilizeModal(false)} title="Utilize Credit" size="md">
        {selectedAgreement && (
          <div className="p-4 space-y-4">
            <p className="text-sm text-neutral-600 dark:text-neutral-300">Utilize from: <strong>{selectedAgreement.agreementName}</strong></p>
            <p className="text-sm text-neutral-500 dark:text-neutral-400">Available: {formatCurrency(selectedAgreement.availableLimit, selectedAgreement.limitCurrency)}</p>
            <div><label className="field-label block mb-1">Amount to Utilize</label><Input type="number" value={actionAmount} onChange={(e) => setActionAmount(parseFloat(e.target.value) || 0)} placeholder="0.00" /></div>
            <div className="flex justify-end gap-2 pt-4 border-t"><Button variant="ghost" onClick={() => setShowUtilizeModal(false)}>Cancel</Button><Button onClick={handleUtilize} disabled={processing || actionAmount <= 0 || actionAmount > selectedAgreement.availableLimit}>{processing ? <Loader2 className="w-4 h-4 mr-2 animate-spin" /> : <ArrowUpRight className="w-4 h-4 mr-2" />}Utilize</Button></div>
          </div>
        )}
      </Modal>

      {/* Release Modal */}
      <Modal isOpen={showReleaseModal} onClose={() => setShowReleaseModal(false)} title="Release Credit" size="md">
        {selectedAgreement && (
          <div className="p-4 space-y-4">
            <p className="text-sm text-neutral-600 dark:text-neutral-300">Release to: <strong>{selectedAgreement.agreementName}</strong></p>
            <p className="text-sm text-neutral-500 dark:text-neutral-400">Utilized: {formatCurrency(selectedAgreement.totalUtilized, selectedAgreement.limitCurrency)}</p>
            <div><label className="field-label block mb-1">Amount to Release</label><Input type="number" value={actionAmount} onChange={(e) => setActionAmount(parseFloat(e.target.value) || 0)} placeholder="0.00" /></div>
            <div className="flex justify-end gap-2 pt-4 border-t"><Button variant="ghost" onClick={() => setShowReleaseModal(false)}>Cancel</Button><Button onClick={handleRelease} disabled={processing || actionAmount <= 0 || actionAmount > selectedAgreement.totalUtilized}>{processing ? <Loader2 className="w-4 h-4 mr-2 animate-spin" /> : <ArrowDownRight className="w-4 h-4 mr-2" />}Release</Button></div>
          </div>
        )}
      </Modal>
    </Page>
  );
};

export default CreditAgreementsPage;