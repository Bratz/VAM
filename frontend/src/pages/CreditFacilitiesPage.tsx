import { Page } from '../components/layout/Page';
import { StatStrip } from '../components/layout/StatStrip';
/**
 * CreditFacilitiesPage - Connected to Backend
 * 
 * Uses: creditFacilitiesApi from creditApi.ts
 * Backend: CreditFacilityController.java at /api/v1/credit-facilities/*
 */
import React, { useState, useEffect, useCallback } from 'react';
import {
  Wallet, Plus, RefreshCw, Search, Loader2, Calendar, CheckCircle2, XCircle,
  Ban, Eye, TrendingUp, ArrowUpRight, ArrowDownRight, Shield, AlertTriangle,
  Link2, Landmark, CreditCard, Banknote, FileText, Briefcase, PiggyBank, Receipt, Building,
} from 'lucide-react';
import { Card, Button, Badge, Input , StatusIconBadge } from '../components/ui';
import { CurrencyPicker } from '../components/ui/CurrencyPicker';
import { Modal } from '../components/ui/enhanced';
import { formatCurrency, cn, formatDate } from '../utils';
import { creditFacilitiesApi, CreditFacility, FacilityType, ApiResponse } from '../services/api';

// ============================================================================
// CONSTANTS
// ============================================================================

const STATUS_CONFIG: Record<string, { label: string; variant: string; icon: any }> = {
  DRAFT: { label: 'Draft', variant: 'neutral', icon: FileText },
  ACTIVE: { label: 'Active', variant: 'success', icon: CheckCircle2 },
  SUSPENDED: { label: 'Suspended', variant: 'error', icon: Ban },
  EXPIRED: { label: 'Expired', variant: 'neutral', icon: XCircle },
  CLOSED: { label: 'Closed', variant: 'neutral', icon: XCircle },
};

const FACILITY_TYPE_CONFIG: Record<string, { label: string; icon: any; color: string; bgColor: string }> = {
  OVERDRAFT: { label: 'Overdraft', icon: Wallet, color: 'text-error-700 dark:text-error-300', bgColor: 'bg-error-100 dark:bg-error-500/20' },
  REVOLVING_CREDIT: { label: 'Revolving Credit', icon: RefreshCw, color: 'text-info-700 dark:text-info-300', bgColor: 'bg-info-100 dark:bg-info-500/20' },
  TERM_LOAN: { label: 'Term Loan', icon: Banknote, color: 'text-success-700 dark:text-success-300', bgColor: 'bg-success-100 dark:bg-success-500/20' },
  TRADE_FINANCE: { label: 'Trade Finance', icon: Briefcase, color: 'text-accent-700 dark:text-accent-300', bgColor: 'bg-accent-100 dark:bg-accent-500/20' },
  LETTER_OF_CREDIT: { label: 'Letter of Credit', icon: FileText, color: 'text-warning-700 dark:text-warning-300', bgColor: 'bg-warning-100 dark:bg-warning-500/20' },
  BANK_GUARANTEE: { label: 'Bank Guarantee', icon: Shield, color: 'text-primary-700 dark:text-neutral-200', bgColor: 'bg-primary-100 dark:bg-primary-700' },
  WORKING_CAPITAL: { label: 'Working Capital', icon: CreditCard, color: 'text-info-700 dark:text-info-300', bgColor: 'bg-info-100 dark:bg-info-500/20' },
  INVOICE_FINANCING: { label: 'Invoice Financing', icon: Receipt, color: 'text-accent-700 dark:text-accent-300', bgColor: 'bg-accent-100 dark:bg-accent-500/20' },
  SUPPLY_CHAIN_FINANCE: { label: 'Supply Chain', icon: Link2, color: 'text-primary-700 dark:text-neutral-200', bgColor: 'bg-primary-100 dark:bg-primary-700' },
  ASSET_BASED: { label: 'Asset Based', icon: Building, color: 'text-warning-700 dark:text-warning-300', bgColor: 'bg-warning-100 dark:bg-warning-500/20' },
  CASH_POOLING: { label: 'Cash Pooling', icon: PiggyBank, color: 'text-success-700 dark:text-success-300', bgColor: 'bg-success-100 dark:bg-success-500/20' },
  NOTIONAL_POOLING: { label: 'Notional Pooling', icon: Landmark, color: 'text-info-700 dark:text-info-300', bgColor: 'bg-info-100 dark:bg-info-500/20' },
  OTHER: { label: 'Other', icon: Wallet, color: 'text-neutral-700 dark:text-neutral-200', bgColor: 'bg-neutral-100 dark:bg-primary-800' },
};

// Helper
const extractData = <T,>(response: ApiResponse<T>): T => {
  if (response && response.data !== undefined) return response.data;
  return response as unknown as T;
};

// ============================================================================
// UTILIZATION BAR
// ============================================================================

const UtilizationBar: React.FC<{ outstanding: number; limit: number; currency: string }> = ({ outstanding, limit, currency }) => {
  const pct = limit > 0 ? (outstanding / limit) * 100 : 0;
  const getColor = () => pct >= 95 ? 'bg-error-500' : pct >= 80 ? 'bg-warning-500' : 'bg-success-500';
  return (
    <div className="space-y-1">
      <div className="relative h-2 bg-neutral-200 rounded-full overflow-hidden dark:bg-primary-800">
        <div className={cn("absolute left-0 top-0 h-full transition-all rounded-full", getColor())} style={{ width: `${Math.min(pct, 100)}%` }} />
      </div>
      <div className="flex justify-between text-xs text-neutral-500 dark:text-neutral-400">
        <span>{formatCurrency(outstanding, currency)} outstanding</span>
        <span>{pct.toFixed(1)}%</span>
      </div>
    </div>
  );
};

// ============================================================================
// FACILITY CARD
// ============================================================================

interface FacilityCardProps {
  facility: CreditFacility;
  onView: () => void;
  onDrawDown: () => void;
  onRepay: () => void;
}

const FacilityCard: React.FC<FacilityCardProps> = ({ facility, onView, onDrawDown, onRepay }) => {
  const statusConfig = STATUS_CONFIG[facility.status] || STATUS_CONFIG.DRAFT;
  const typeConfig = FACILITY_TYPE_CONFIG[facility.facilityType] || FACILITY_TYPE_CONFIG.OTHER;
  const TypeIcon = typeConfig.icon;
  const daysToExpiry = Math.ceil((new Date(facility.expiryDate).getTime() - Date.now()) / (1000 * 60 * 60 * 24));
  const isExpiringSoon = daysToExpiry > 0 && daysToExpiry <= 90;
  const isFullyDrawn = facility.availableLimit <= 0;

  return (
    <Card className={cn(
      "p-4 hover:shadow-md transition-shadow",
      isFullyDrawn && "border-error-300 bg-error-50/30",
      isExpiringSoon && facility.status === 'ACTIVE' && !isFullyDrawn && "border-warning-300 bg-warning-50/30"
    )}>
      <div className="flex items-start justify-between">
        <div className="flex items-center gap-3">
          <div className={cn("w-10 h-10 rounded-xl flex items-center justify-center", typeConfig.bgColor)}>
            <TypeIcon className={cn("w-5 h-5", typeConfig.color)} />
          </div>
          <div>
            <h3 className="font-semibold text-primary-900 line-clamp-1 dark:text-neutral-50">{facility.facilityName}</h3>
            <p className="text-sm text-neutral-500 line-clamp-1 dark:text-neutral-400">{facility.agreementName || 'Standalone Facility'}</p>
          </div>
        </div>
        <Badge className={cn("text-xs", typeConfig.bgColor, typeConfig.color)}>{typeConfig.label}</Badge>
      </div>

      <div className="mt-2 flex items-center gap-2 text-xs text-neutral-500 dark:text-neutral-400">
        {facility.externalReference && (
          <span className="flex items-center gap-1"><Link2 className="w-3 h-3" /> {facility.externalReference}</span>
        )}
        {facility.linkedAccountNumber && (
          <span className="flex items-center gap-1 ml-2"><Landmark className="w-3 h-3" /> {facility.linkedAccountNumber.slice(-12)}</span>
        )}
      </div>

      <div className="mt-4">
        <div className="flex items-baseline justify-between mb-2">
          <span className="stat-value-sm">{formatCurrency(facility.facilityLimit, facility.facilityCurrency)}</span>
          <Badge variant={statusConfig.variant as any} size="sm">{statusConfig.label}</Badge>
        </div>
        <UtilizationBar outstanding={facility.currentOutstanding} limit={facility.facilityLimit} currency={facility.facilityCurrency} />
      </div>

      <div className="mt-4 grid grid-cols-2 gap-2 text-xs">
        <div>
          <span className="text-neutral-500 dark:text-neutral-400">Available:</span>
          <span className={cn("ml-1 font-medium", facility.availableLimit > 0 ? "text-success-600 dark:text-success-300" : "text-error-600 dark:text-error-300")}>
            {formatCurrency(facility.availableLimit, facility.facilityCurrency)}
          </span>
        </div>
        <div>
          <span className="text-neutral-500 dark:text-neutral-400">Rate:</span>
          <span className="ml-1 font-medium">{facility.effectiveRate?.toFixed(2) || 'N/A'}%</span>
        </div>
        <div className="flex items-center gap-1 text-neutral-500 dark:text-neutral-400">
          <Calendar className="w-3 h-3" />
          <span>Expires: {formatDate(facility.expiryDate)}</span>
        </div>
        {isExpiringSoon && (
          <div className="flex items-center gap-1 text-warning-600 dark:text-warning-300">
            <AlertTriangle className="w-3 h-3" />
            <span>{daysToExpiry} days left</span>
          </div>
        )}
      </div>

      <div className="mt-4 pt-3 border-t flex gap-2">
        <Button variant="ghost" size="sm" onClick={onView} className="flex-1">
          <Eye className="w-3 h-3 mr-1" /> View
        </Button>
        {facility.status === 'ACTIVE' && (
          <>
            <Button variant="ghost" size="sm" onClick={onDrawDown} className="flex-1" disabled={facility.availableLimit <= 0}>
              <ArrowUpRight className="w-3 h-3 mr-1" /> Draw
            </Button>
            <Button variant="ghost" size="sm" onClick={onRepay} className="flex-1" disabled={facility.currentOutstanding <= 0}>
              <ArrowDownRight className="w-3 h-3 mr-1" /> Repay
            </Button>
          </>
        )}
      </div>
    </Card>
  );
};

// ============================================================================
// MAIN PAGE
// ============================================================================

const CreditFacilitiesPage: React.FC = () => {
  const [facilities, setFacilities] = useState<CreditFacility[]>([]);
  const [loading, setLoading] = useState(true);
  const [syncing, setSyncing] = useState(false);
  const [searchTerm, setSearchTerm] = useState('');
  const [filterType, setFilterType] = useState<string>('');
  const [filterStatus, setFilterStatus] = useState<string>('');
  const [corporateId] = useState('corp-1'); // TODO: Get from context

  const [showCreateModal, setShowCreateModal] = useState(false);
  const [showDetailModal, setShowDetailModal] = useState(false);
  const [showDrawDownModal, setShowDrawDownModal] = useState(false);
  const [showRepayModal, setShowRepayModal] = useState(false);
  const [selectedFacility, setSelectedFacility] = useState<CreditFacility | null>(null);
  const [actionAmount, setActionAmount] = useState(0);
  const [processing, setProcessing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [createForm, setCreateForm] = useState({
    facilityName: '',
    facilityType: 'OVERDRAFT' as FacilityType,
    externalReference: '',
    facilityLimit: '',
    facilityCurrency: 'AED',
    effectiveDate: new Date().toISOString().split('T')[0],
    expiryDate: '',
    interestRateType: 'FLOATING' as 'FIXED' | 'FLOATING',
    baseRateValue: '',
    spreadPercent: '',
  });

  // Load from real API
  const loadData = useCallback(async () => {
    try {
      setLoading(true);
      setError(null);
      let response;
      try {
        response = await creditFacilitiesApi.getByCorporate(corporateId);
      } catch {
        response = await creditFacilitiesApi.getAll();
      }
      const data = extractData(response);
      setFacilities(Array.isArray(data) ? data : []);
    } catch (err) {
      console.error('Failed to load facilities:', err);
      setError('Failed to load credit facilities.');
      setFacilities([]);
    } finally {
      setLoading(false);
    }
  }, [corporateId]);

  useEffect(() => { loadData(); }, [loadData]);

  const handleSync = async () => {
    try {
      setSyncing(true);
      await loadData();
    } catch (err) {
      setError('Failed to sync from CBS.');
    } finally {
      setSyncing(false);
    }
  };

  const handleCreate = async () => {
    if (!createForm.facilityName || !createForm.facilityLimit) return;
    try {
      setProcessing(true);
      setError(null);
      const baseRate = createForm.baseRateValue ? parseFloat(createForm.baseRateValue) : undefined;
      const spread = createForm.spreadPercent ? parseFloat(createForm.spreadPercent) : undefined;
      await creditFacilitiesApi.create({
        corporateId,
        facilityName: createForm.facilityName,
        facilityType: createForm.facilityType,
        externalReference: createForm.externalReference || undefined,
        facilityLimit: parseFloat(createForm.facilityLimit),
        facilityCurrency: createForm.facilityCurrency,
        effectiveDate: createForm.effectiveDate,
        expiryDate: createForm.expiryDate,
        interestRateType: createForm.interestRateType,
        baseRateValue: baseRate,
        spreadPercent: spread,
      });
      await loadData();
      setShowCreateModal(false);
      setCreateForm({
        facilityName: '', facilityType: 'OVERDRAFT', externalReference: '',
        facilityLimit: '', facilityCurrency: 'AED',
        effectiveDate: new Date().toISOString().split('T')[0], expiryDate: '',
        interestRateType: 'FLOATING', baseRateValue: '', spreadPercent: '',
      });
    } catch (err) {
      setError('Failed to create facility.');
    } finally {
      setProcessing(false);
    }
  };

  const handleDrawDown = async () => {
    if (!selectedFacility || actionAmount <= 0) return;
    try {
      setProcessing(true);
      await creditFacilitiesApi.drawDown(selectedFacility.id, actionAmount);
      await loadData();
      setShowDrawDownModal(false);
      setActionAmount(0);
    } catch (err) {
      setError('Failed to draw down.');
    } finally {
      setProcessing(false);
    }
  };

  const handleRepay = async () => {
    if (!selectedFacility || actionAmount <= 0) return;
    try {
      setProcessing(true);
      await creditFacilitiesApi.repay(selectedFacility.id, actionAmount);
      await loadData();
      setShowRepayModal(false);
      setActionAmount(0);
    } catch (err) {
      setError('Failed to repay.');
    } finally {
      setProcessing(false);
    }
  };

  // Filter
  const filteredFacilities = facilities.filter(f => {
    const matchesSearch = !searchTerm ||
      f.facilityName.toLowerCase().includes(searchTerm.toLowerCase()) ||
      f.externalReference?.toLowerCase().includes(searchTerm.toLowerCase());
    const matchesType = !filterType || f.facilityType === filterType;
    const matchesStatus = !filterStatus || f.status === filterStatus;
    return matchesSearch && matchesType && matchesStatus;
  });

  // Stats
  const activeFacilities = facilities.filter(f => f.status === 'ACTIVE');
  const stats = {
    total: facilities.length,
    active: activeFacilities.length,
    totalLimit: activeFacilities.reduce((sum, f) => sum + f.facilityLimit, 0),
    totalOutstanding: activeFacilities.reduce((sum, f) => sum + f.currentOutstanding, 0),
    overdrafts: facilities.filter(f => f.facilityType === 'OVERDRAFT' && f.status === 'ACTIVE').length,
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center h-96">
        <Loader2 className="w-8 h-8 animate-spin text-primary-600 dark:text-primary-200" />
      </div>
    );
  }

  return (
    <Page>
      {/* Quick Actions */}
      <div className="flex items-center justify-end gap-2 animate-fade-in" style={{ animationDelay: '0.05s' }}>
          <Button variant="outline" onClick={handleSync} disabled={syncing}>
            {syncing ? <Loader2 className="w-4 h-4 mr-2 animate-spin" /> : <RefreshCw className="w-4 h-4 mr-2" />}
            Sync from CBS
          </Button>
          <Button onClick={() => setShowCreateModal(true)}>
            <Plus className="w-4 h-4 mr-2" /> Add Facility
          </Button>
      </div>

      {/* Error Banner */}
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

      {/* Stats */}
      <StatStrip>
        <Card hover className="animate-fade-in" style={{ animationDelay: '0.1s' }}>
          <div className="p-4">
            <div className="flex items-start justify-between">
              <div className="flex-1">
                <p className="label">Total</p>
                <p className="stat-value-sm mt-1">{stats.total}</p>
              </div>
              <StatusIconBadge tone="info" icon={Wallet} className="dark:bg-info-500/20" />
            </div>
          </div>
        </Card>
        <Card hover className="animate-fade-in" style={{ animationDelay: '0.15s' }}>
          <div className="p-4">
            <div className="flex items-start justify-between">
              <div className="flex-1">
                <p className="label">Active</p>
                <p className="stat-value-success mt-1">{stats.active}</p>
              </div>
              <StatusIconBadge tone="success" icon={CheckCircle2} className="dark:bg-success-500/20" />
            </div>
          </div>
        </Card>
        <Card hover className="animate-fade-in" style={{ animationDelay: '0.2s' }}>
          <div className="p-4">
            <div className="flex items-start justify-between">
              <div className="flex-1">
                <p className="label">Total Limit</p>
                <p className="text-lg font-bold text-primary-900 mt-1 tracking-tight dark:text-neutral-50">{formatCurrency(stats.totalLimit, 'AED')}</p>
              </div>
              <StatusIconBadge tone="accent" icon={Shield} className="dark:bg-accent-500/20" />
            </div>
          </div>
        </Card>
        <Card hover className="animate-fade-in" style={{ animationDelay: '0.25s' }}>
          <div className="p-4">
            <div className="flex items-start justify-between">
              <div className="flex-1">
                <p className="label">Outstanding</p>
                <p className="text-lg font-bold text-warning-600 mt-1 tracking-tight dark:text-warning-300">{formatCurrency(stats.totalOutstanding, 'AED')}</p>
              </div>
              <StatusIconBadge tone="warning" icon={TrendingUp} className="dark:bg-warning-500/20" />
            </div>
          </div>
        </Card>
        <Card hover className="animate-fade-in" style={{ animationDelay: '0.3s' }}>
          <div className="p-4">
            <div className="flex items-start justify-between">
              <div className="flex-1">
                <p className="label">Overdrafts</p>
                <p className="stat-value-error mt-1">{stats.overdrafts}</p>
              </div>
              <StatusIconBadge tone="error" icon={Wallet} className="dark:bg-error-500/20" />
            </div>
          </div>
        </Card>
      </StatStrip>

      {/* Filters */}
      <div className="flex gap-3 animate-fade-in" style={{ animationDelay: '0.35s' }}>
        <div className="relative flex-1">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400 dark:text-neutral-500" />
          <Input
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            placeholder="Search facilities..."
            className="pl-9"
          />
        </div>
        <select
          value={filterType}
          onChange={(e) => setFilterType(e.target.value)}
          className="px-3 py-2 border border-neutral-300 rounded-lg bg-white text-sm font-medium dark:border-primary-700 dark:bg-primary-900"
        >
          <option value="">All Types</option>
          {Object.entries(FACILITY_TYPE_CONFIG).map(([key, val]) => (
            <option key={key} value={key}>{val.label}</option>
          ))}
        </select>
        <select
          value={filterStatus}
          onChange={(e) => setFilterStatus(e.target.value)}
          className="px-3 py-2 border border-neutral-300 rounded-lg bg-white text-sm font-medium dark:border-primary-700 dark:bg-primary-900"
        >
          <option value="">All Status</option>
          {Object.entries(STATUS_CONFIG).map(([key, val]) => (
            <option key={key} value={key}>{val.label}</option>
          ))}
        </select>
      </div>

      {/* Facilities Grid */}
      {filteredFacilities.length > 0 ? (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4 animate-fade-in" style={{ animationDelay: '0.4s' }}>
          {filteredFacilities.map((facility, index) => (
            <div key={facility.id} className="animate-fade-in" style={{ animationDelay: `${0.45 + index * 0.03}s` }}>
              <FacilityCard
                facility={facility}
                onView={() => { setSelectedFacility(facility); setShowDetailModal(true); }}
                onDrawDown={() => { setSelectedFacility(facility); setActionAmount(0); setShowDrawDownModal(true); }}
                onRepay={() => { setSelectedFacility(facility); setActionAmount(0); setShowRepayModal(true); }}
              />
            </div>
          ))}
        </div>
      ) : (
        <Card className="p-12 text-center animate-fade-in" style={{ animationDelay: '0.4s' }}>
          <Wallet className="w-12 h-12 text-neutral-300 mx-auto mb-4 dark:text-neutral-600" />
          <p className="text-neutral-500 dark:text-neutral-400">No credit facilities found</p>
          <Button className="mt-4" onClick={() => setShowCreateModal(true)}>
            <Plus className="w-4 h-4 mr-2" /> Add Facility
          </Button>
        </Card>
      )}

      {/* Create Modal */}
      <Modal isOpen={showCreateModal} onClose={() => setShowCreateModal(false)} title="Add Credit Facility" size="lg">
        <div className="p-4 space-y-4">
          <div>
            <label className="field-label block mb-1">Facility Name *</label>
            <Input
              value={createForm.facilityName}
              onChange={(e) => setCreateForm(prev => ({ ...prev, facilityName: e.target.value }))}
              placeholder="e.g., Working Capital Overdraft"
            />
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="field-label block mb-1">Facility Type *</label>
              <select
                value={createForm.facilityType}
                onChange={(e) => setCreateForm(prev => ({ ...prev, facilityType: e.target.value as any }))}
                className="w-full px-3 py-2 border border-neutral-300 rounded-lg dark:border-primary-700"
              >
                {Object.entries(FACILITY_TYPE_CONFIG).map(([key, val]) => (
                  <option key={key} value={key}>{val.label}</option>
                ))}
              </select>
            </div>
            <div>
              <label className="field-label block mb-1">External Reference</label>
              <Input
                value={createForm.externalReference}
                onChange={(e) => setCreateForm(prev => ({ ...prev, externalReference: e.target.value }))}
                placeholder="CBS Facility ID"
              />
            </div>
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="field-label block mb-1">Facility Limit *</label>
              <Input
                type="number"
                value={createForm.facilityLimit}
                onChange={(e) => setCreateForm(prev => ({ ...prev, facilityLimit: e.target.value }))}
                placeholder="10000000"
              />
            </div>
            <div>
              <label className="field-label block mb-1">Currency</label>
              <CurrencyPicker
                value={createForm.facilityCurrency}
                onChange={(c) => setCreateForm(prev => ({ ...prev, facilityCurrency: c }))}
              />
            </div>
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="field-label block mb-1">Effective Date</label>
              <Input
                type="date"
                value={createForm.effectiveDate}
                onChange={(e) => setCreateForm(prev => ({ ...prev, effectiveDate: e.target.value }))}
              />
            </div>
            <div>
              <label className="field-label block mb-1">Expiry Date</label>
              <Input
                type="date"
                value={createForm.expiryDate}
                onChange={(e) => setCreateForm(prev => ({ ...prev, expiryDate: e.target.value }))}
              />
            </div>
          </div>
          <div className="grid grid-cols-3 gap-4">
            <div>
              <label className="field-label block mb-1">Rate Type</label>
              <select
                value={createForm.interestRateType}
                onChange={(e) => setCreateForm(prev => ({ ...prev, interestRateType: e.target.value as any }))}
                className="w-full px-3 py-2 border border-neutral-300 rounded-lg dark:border-primary-700"
              >
                <option value="FIXED">Fixed</option>
                <option value="FLOATING">Floating</option>
              </select>
            </div>
            <div>
              <label className="field-label block mb-1">Base Rate %</label>
              <Input
                type="number"
                step="0.01"
                value={createForm.baseRateValue}
                onChange={(e) => setCreateForm(prev => ({ ...prev, baseRateValue: e.target.value }))}
                placeholder="5.25"
              />
            </div>
            <div>
              <label className="field-label block mb-1">Spread %</label>
              <Input
                type="number"
                step="0.01"
                value={createForm.spreadPercent}
                onChange={(e) => setCreateForm(prev => ({ ...prev, spreadPercent: e.target.value }))}
                placeholder="1.50"
              />
            </div>
          </div>
          <div className="flex justify-end gap-2 pt-4 border-t">
            <Button variant="ghost" onClick={() => setShowCreateModal(false)}>Cancel</Button>
            <Button onClick={handleCreate} disabled={processing || !createForm.facilityName || !createForm.facilityLimit}>
              {processing ? <Loader2 className="w-4 h-4 mr-2 animate-spin" /> : <Plus className="w-4 h-4 mr-2" />}
              Create Facility
            </Button>
          </div>
        </div>
      </Modal>

      {/* Detail Modal */}
      <Modal isOpen={showDetailModal} onClose={() => setShowDetailModal(false)} title="Facility Details" size="lg">
        {selectedFacility && (
          <div className="p-4 space-y-4">
            <div className="grid grid-cols-2 gap-4 text-sm">
              <div><span className="text-neutral-500 dark:text-neutral-400">Name:</span> <span className="ml-2 font-medium">{selectedFacility.facilityName}</span></div>
              <div><span className="text-neutral-500 dark:text-neutral-400">Type:</span> <span className="ml-2 font-medium">{FACILITY_TYPE_CONFIG[selectedFacility.facilityType]?.label}</span></div>
              <div><span className="text-neutral-500 dark:text-neutral-400">Status:</span> <span className="ml-2 font-medium">{selectedFacility.status}</span></div>
              <div><span className="text-neutral-500 dark:text-neutral-400">Currency:</span> <span className="ml-2 font-medium">{selectedFacility.facilityCurrency}</span></div>
              <div><span className="text-neutral-500 dark:text-neutral-400">Facility Limit:</span> <span className="ml-2 font-medium">{formatCurrency(selectedFacility.facilityLimit, selectedFacility.facilityCurrency)}</span></div>
              <div><span className="text-neutral-500 dark:text-neutral-400">Drawing Power:</span> <span className="ml-2 font-medium">{formatCurrency(selectedFacility.drawingPower, selectedFacility.facilityCurrency)}</span></div>
              <div><span className="text-neutral-500 dark:text-neutral-400">Outstanding:</span> <span className="ml-2 font-medium text-warning-600 dark:text-warning-300">{formatCurrency(selectedFacility.currentOutstanding, selectedFacility.facilityCurrency)}</span></div>
              <div><span className="text-neutral-500 dark:text-neutral-400">Available:</span> <span className="ml-2 font-medium text-success-600 dark:text-success-300">{formatCurrency(selectedFacility.availableLimit, selectedFacility.facilityCurrency)}</span></div>
              <div><span className="text-neutral-500 dark:text-neutral-400">Rate Type:</span> <span className="ml-2 font-medium">{selectedFacility.interestRateType}</span></div>
              <div><span className="text-neutral-500 dark:text-neutral-400">Effective Rate:</span> <span className="ml-2 font-medium">{selectedFacility.effectiveRate?.toFixed(2) || 'N/A'}%</span></div>
              <div><span className="text-neutral-500 dark:text-neutral-400">Effective Date:</span> <span className="ml-2 font-medium">{formatDate(selectedFacility.effectiveDate)}</span></div>
              <div><span className="text-neutral-500 dark:text-neutral-400">Expiry Date:</span> <span className="ml-2 font-medium">{formatDate(selectedFacility.expiryDate)}</span></div>
              {selectedFacility.linkedAccountNumber && (
                <div className="col-span-2">
                  <span className="text-neutral-500 dark:text-neutral-400">Linked Account:</span>
                  <span className="ml-2 font-medium">{selectedFacility.linkedAccountNumber}</span>
                </div>
              )}
            </div>
            <UtilizationBar
              outstanding={selectedFacility.currentOutstanding}
              limit={selectedFacility.facilityLimit}
              currency={selectedFacility.facilityCurrency}
            />
            <div className="flex justify-end pt-4 border-t">
              <Button variant="ghost" onClick={() => setShowDetailModal(false)}>Close</Button>
            </div>
          </div>
        )}
      </Modal>

      {/* Draw Down Modal */}
      <Modal isOpen={showDrawDownModal} onClose={() => setShowDrawDownModal(false)} title="Draw Down" size="md">
        {selectedFacility && (
          <div className="p-4 space-y-4">
            <p className="text-sm text-neutral-600 dark:text-neutral-300">Draw from: <strong>{selectedFacility.facilityName}</strong></p>
            <p className="text-sm text-neutral-500 dark:text-neutral-400">Available: {formatCurrency(selectedFacility.availableLimit, selectedFacility.facilityCurrency)}</p>
            <div>
              <label className="field-label block mb-1">Amount to Draw</label>
              <Input
                type="number"
                value={actionAmount}
                onChange={(e) => setActionAmount(parseFloat(e.target.value) || 0)}
                placeholder="0.00"
              />
            </div>
            <div className="flex justify-end gap-2 pt-4 border-t">
              <Button variant="ghost" onClick={() => setShowDrawDownModal(false)}>Cancel</Button>
              <Button onClick={handleDrawDown} disabled={processing || actionAmount <= 0 || actionAmount > selectedFacility.availableLimit}>
                {processing ? <Loader2 className="w-4 h-4 mr-2 animate-spin" /> : <ArrowUpRight className="w-4 h-4 mr-2" />}
                Draw Down
              </Button>
            </div>
          </div>
        )}
      </Modal>

      {/* Repay Modal */}
      <Modal isOpen={showRepayModal} onClose={() => setShowRepayModal(false)} title="Repay Facility" size="md">
        {selectedFacility && (
          <div className="p-4 space-y-4">
            <p className="text-sm text-neutral-600 dark:text-neutral-300">Repay to: <strong>{selectedFacility.facilityName}</strong></p>
            <p className="text-sm text-neutral-500 dark:text-neutral-400">Outstanding: {formatCurrency(selectedFacility.currentOutstanding, selectedFacility.facilityCurrency)}</p>
            <div>
              <label className="field-label block mb-1">Amount to Repay</label>
              <Input
                type="number"
                value={actionAmount}
                onChange={(e) => setActionAmount(parseFloat(e.target.value) || 0)}
                placeholder="0.00"
              />
            </div>
            <div className="flex justify-end gap-2 pt-4 border-t">
              <Button variant="ghost" onClick={() => setShowRepayModal(false)}>Cancel</Button>
              <Button onClick={handleRepay} disabled={processing || actionAmount <= 0 || actionAmount > selectedFacility.currentOutstanding}>
                {processing ? <Loader2 className="w-4 h-4 mr-2 animate-spin" /> : <ArrowDownRight className="w-4 h-4 mr-2" />}
                Repay
              </Button>
            </div>
          </div>
        )}
      </Modal>
    </Page>
  );
};

export default CreditFacilitiesPage;