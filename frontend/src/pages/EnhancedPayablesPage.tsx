import { Page } from '../components/layout/Page';
import { ScopeSelector } from '../components/layout/ScopeSelector';
import { Select, StatusIconBadge } from '../components/ui';
/**
 * EnhancedPayablesPage.tsx - Phase 2 Complete Implementation
 * 
 * Features:
 * - Corporate/Program filter bar (matches TreasuryHierarchyPage)
 * - Entity selector (subsidiary filtering)
 * - Real API integration (no mock data)
 * - POBO workflow with treasury approval
 * - Intercompany payable indicators
 * - Netting status and quick-add
 * - Payment route visualization
 */

import React, { useState, useEffect, useCallback, useMemo } from 'react';
import {
  Search, Filter, Plus, Send, Clock, FileText, Building, Eye,
  AlertTriangle, RefreshCw, Loader2, X, Building2, ChevronDown,
  Landmark, Calculator, ArrowRight, GitBranch, ExternalLink, Layers,
  CheckCircle, XCircle, PlayCircle, Edit, MoreVertical, Calendar,
} from 'lucide-react';
import { Card, Button, Badge } from '../components/ui';
import { Modal } from '../components/ui/enhanced';
import { formatCurrency, formatDate, cn } from '../utils';
import { useNavigation } from '../App';
import { 
  corporatesApi, 
  programsApi, 
  legalEntityApi,
  payablesApiPhase2,
  Corporate,
  Program,
  LegalEntity,
  PayablePhase2,
  PayableStatsPhase2,
  PoboPreviewResponse,
} from '../services/api';

// ============================================================================
// HELPER FUNCTIONS
// ============================================================================

const getPaymentRouteBadge = (route: string, isIntercompany: boolean, nettingStatus: string) => {
  if (nettingStatus === 'INCLUDED' || nettingStatus === 'SETTLED') {
    return <Badge variant="info" size="sm" className="gap-1"><GitBranch className="w-3 h-3" /> Netting</Badge>;
  }
  if (isIntercompany) {
    return <Badge variant="warning" size="sm" className="gap-1"><Building2 className="w-3 h-3" /> IC</Badge>;
  }
  switch (route) {
    case 'POBO':
      return <Badge variant="accent" size="sm" className="gap-1"><Landmark className="w-3 h-3" /> POBO</Badge>;
    case 'NETTING':
      return <Badge variant="info" size="sm" className="gap-1"><GitBranch className="w-3 h-3" /> Netting</Badge>;
    default:
      return <Badge variant="neutral" size="sm">Direct</Badge>;
  }
};

const getStatusBadge = (status: string) => {
  const variants: Record<string, 'success' | 'warning' | 'error' | 'info' | 'neutral'> = {
    DRAFT: 'neutral',
    PENDING_APPROVAL: 'warning',
    APPROVED: 'success',
    PENDING_POBO: 'info',
    POBO_APPROVED: 'success',
    POBO_REJECTED: 'error',
    SCHEDULED: 'info',
    PAID: 'success',
    NETTED: 'success',
    PENDING_NETTING: 'info',
    REJECTED: 'error',
    CANCELLED: 'neutral',
  };
  const labels: Record<string, string> = {
    PENDING_POBO: 'POBO Pending',
    POBO_APPROVED: 'POBO Ready',
    PENDING_NETTING: 'In Netting',
  };
  return <Badge variant={variants[status] || 'neutral'} size="sm">{labels[status] || status.replace(/_/g, ' ')}</Badge>;
};

const getPoboStatusBadge = (status: string | undefined) => {
  if (!status) return null;
  const config: Record<string, { variant: 'success' | 'warning' | 'error' | 'info'; label: string }> = {
    PENDING_TREASURY_APPROVAL: { variant: 'warning', label: 'Awaiting Treasury' },
    APPROVED: { variant: 'success', label: 'Treasury Approved' },
    REJECTED: { variant: 'error', label: 'Treasury Rejected' },
    EXECUTED: { variant: 'success', label: 'POBO Executed' },
  };
  const c = config[status];
  if (!c) return null;
  return <Badge variant={c.variant} size="sm">{c.label}</Badge>;
};

// ============================================================================
// PAYABLES FILTER BAR — wraps the shared ScopeSelector primitive with the
// page-specific Entity dropdown (3rd field) plumbed via `rightSlot`. This
// keeps the picker visually identical to every other page in the app while
// preserving the Payables-specific corporate / program / entity scope.
// ============================================================================

interface FilterBarProps {
  corporates: Corporate[];
  programs: Program[];
  entities: LegalEntity[];
  selectedCorporateId: string;
  selectedProgramId: string;
  selectedEntityId: string;
  onCorporateChange: (id: string) => void;
  onProgramChange: (id: string) => void;
  onEntityChange: (id: string) => void;
  loading?: boolean;
}

const PayablesFilterBar: React.FC<FilterBarProps> = ({
  corporates,
  programs,
  entities,
  selectedCorporateId,
  selectedProgramId,
  selectedEntityId,
  onCorporateChange,
  onProgramChange,
  onEntityChange,
  loading,
}) => {
  // Defensive: ensure arrays.
  const corporatesList = Array.isArray(corporates) ? corporates : [];
  const programsList = Array.isArray(programs) ? programs : [];
  const entitiesList = Array.isArray(entities) ? entities : [];

  // Page-specific filter: only ACTIVE programs that belong to the selected
  // corporate (or all ACTIVE programs if no corporate selected). ScopeSelector
  // doesn't apply this — Payables wants the filter at the call site.
  const activeFilteredPrograms = programsList.filter(p =>
    p.status === 'ACTIVE' && (!selectedCorporateId || (p as any).corporateId === selectedCorporateId),
  );

  // Entity options for the rightSlot dropdown.
  const entityOptions = [
    { value: '', label: `All Entities${entitiesList.length ? ` (${entitiesList.length})` : ''}` },
    ...entitiesList.map(e => ({ value: e.id, label: `${e.entityCode} – ${e.entityName}` })),
  ];

  return (
    <ScopeSelector
      mode="corporate-program"
      corporates={corporatesList}
      programs={activeFilteredPrograms}
      selectedCorporateId={selectedCorporateId}
      selectedProgramId={selectedProgramId}
      onCorporateChange={onCorporateChange}
      onProgramChange={onProgramChange}
      loading={loading}
      // Third field — Entity. Slotted into ScopeSelector's `rightSlot` so it
      // gets the same medallion + label + Select recipe as the other two.
      rightSlot={(
        <div className="flex items-center gap-2">
          <StatusIconBadge tone="success" icon={Building2} size="sm" />
          <div className="min-w-[200px]">
            <label className="label">Entity</label>
            <Select
              value={selectedEntityId}
              onChange={(e) => onEntityChange(e.target.value)}
              options={entityOptions}
              disabled={loading || entitiesList.length === 0}
              selectSize="sm"
              aria-label="Legal entity"
            />
          </div>
        </div>
      )}
    />
  );
};

// ============================================================================
// STATS SECTION
// ============================================================================

interface StatsProps {
  stats: PayableStatsPhase2 | null;
  loading: boolean;
}

const PayablesStatsSection: React.FC<StatsProps> = ({ stats, loading }) => {
  if (loading) {
    return (
      <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-4">
        {[...Array(6)].map((_, i) => (
          <Card key={i} hover className="animate-fade-in" style={{ animationDelay: `${0.1 + i * 0.05}s` }}>
            <div className="h-4 bg-neutral-200 rounded w-20 mb-2 animate-pulse dark:bg-primary-800" />
            <div className="h-8 bg-neutral-200 rounded w-16 animate-pulse dark:bg-primary-800" />
          </Card>
        ))}
      </div>
    );
  }

  const statItems = [
    { label: 'Total Payables', value: stats?.totalCount || 0, color: 'text-primary-600 dark:text-primary-200', iconBg: 'bg-primary-100 dark:bg-primary-700 text-primary-600 dark:text-primary-200', icon: FileText },
    { label: 'Pending', value: stats?.pendingCount || 0, color: 'text-warning-600 dark:text-warning-300', iconBg: 'bg-warning-100 dark:bg-warning-50 dark:bg-warning-500/20 text-warning-600 dark:text-warning-300 dark:bg-warning-500/20', icon: Clock },
    { label: 'Overdue', value: stats?.overdueCount || 0, color: 'text-error-600 dark:text-error-300', iconBg: 'bg-error-100 dark:bg-error-50 dark:bg-error-500/20 text-error-600 dark:text-error-300 dark:bg-error-500/20', icon: AlertTriangle },
    { label: 'POBO', value: stats?.poboRequestedCount || 0, color: 'text-info-600 dark:text-info-300', iconBg: 'bg-info-100 dark:bg-info-50 dark:bg-info-500/20 text-info-600 dark:text-info-300 dark:bg-info-500/20', icon: Landmark },
    { label: 'Intercompany', value: stats?.intercompanyCount || 0, color: 'text-accent-600 dark:text-accent-300', iconBg: 'bg-accent-100 text-accent-600 dark:bg-accent-500/20 dark:text-accent-300', icon: Building2 },
    { label: 'In Netting', value: stats?.nettingIncludedCount || 0, color: 'text-success-600 dark:text-success-300', iconBg: 'bg-success-100 dark:bg-success-50 dark:bg-success-500/20 text-success-600 dark:text-success-300 dark:bg-success-500/20', icon: GitBranch },
  ];

  return (
    <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-4">
      {statItems.map((item, idx) => {
        const Icon = item.icon;
        return (
          <Card key={idx} hover className="animate-fade-in" style={{ animationDelay: `${0.1 + idx * 0.05}s` }}>
            <div className="flex items-start justify-between">
              <div className="flex-1">
                <p className="label">{item.label}</p>
                <p className="stat-value-sm mt-1">{item.value}</p>
              </div>
              <div className={cn("w-10 h-10 rounded-xl flex items-center justify-center", item.iconBg)}>
                <Icon className="w-5 h-5" />
              </div>
            </div>
          </Card>
        );
      })}
    </div>
  );
};

// ============================================================================
// POBO REQUEST MODAL
// ============================================================================

interface PoboModalProps {
  isOpen: boolean;
  onClose: () => void;
  payables: PayablePhase2[];
  entities: LegalEntity[];
  onSubmit: (payingEntityId: string, payableIds: string[]) => Promise<void>;
  loading?: boolean;
}

const PoboRequestModal: React.FC<PoboModalProps> = ({ 
  isOpen, onClose, payables, entities, onSubmit, loading: submitLoading 
}) => {
  const [payingEntityId, setPayingEntityId] = useState('');
  const [preview, setPreview] = useState<PoboPreviewResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Defensive: ensure arrays
  const entitiesList = Array.isArray(entities) ? entities : [];
  const payablesList = Array.isArray(payables) ? payables : [];
  const treasuryEntities = entitiesList.filter(e => (e as any).isTreasuryCenter || (e as any).entityType === 'HOLDING');

  useEffect(() => {
    if (treasuryEntities.length > 0 && !payingEntityId) {
      setPayingEntityId(treasuryEntities[0].id);
    }
  }, [treasuryEntities, payingEntityId]);

  useEffect(() => {
    if (payablesList.length > 0 && payingEntityId && isOpen) {
      loadPreview();
    }
  }, [payablesList.length, payingEntityId, isOpen]);

  const loadPreview = async () => {
    if (!payablesList.length || !payingEntityId) return;
    
    setLoading(true);
    setError(null);
    try {
      const result = await payablesApiPhase2.previewPobo({
        payableIds: payablesList.map(p => p.id),
        payingEntityId,
        behalfEntityId: payablesList[0]?.owningEntityId || '',
      });
      setPreview(result);
    } catch (err: any) {
      console.error('Failed to load POBO preview:', err);
      setError('Failed to load preview. Using estimated values.');
      // Fallback calculation
      const total = payablesList.reduce((sum, p) => sum + (p.netAmount || 0), 0);
      const fees = total * 0.005;
      const treasury = entitiesList.find(e => e.id === payingEntityId);
      setPreview({
        payingEntityId,
        payingEntityCode: treasury?.entityCode || 'TREASURY',
        payingEntityName: treasury?.entityName || 'Treasury',
        behalfEntityId: payablesList[0]?.owningEntityId || '',
        behalfEntityCode: payablesList[0]?.owningEntityCode || 'SUB',
        behalfEntityName: payablesList[0]?.owningEntityName || 'Subsidiary',
        payableCount: payablesList.length,
        totalPaymentAmount: total,
        currencyCode: payablesList[0]?.currencyCode || 'AED',
        charges: [{ chargeCode: 'POBO-FEE', chargeName: 'POBO Service Fee', chargeType: 'PERCENTAGE', calculatedAmount: fees, waived: false }],
        totalCharges: fees,
        netRechargeAmount: total + fees,
        ihbLoanPreview: {
          lendingEntityId: payingEntityId,
          borrowingEntityId: payablesList[0]?.owningEntityId || '',
          principalAmount: total + fees,
          currencyCode: payablesList[0]?.currencyCode || 'AED',
          interestRate: 5.0,
          expectedTenorDays: 30,
          estimatedInterest: (total + fees) * 0.05 / 12,
        },
      });
    } finally {
      setLoading(false);
    }
  };

  const handleSubmit = async () => {
    if (!payingEntityId || payablesList.length === 0) return;
    await onSubmit(payingEntityId, payablesList.map(p => p.id));
    onClose();
  };

  if (!isOpen) return null;

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="Request POBO Payment" size="lg">
      <div className="space-y-6">
        {/* Selected Payables */}
        <div>
          <h4 className="field-label mb-2">Selected Payables ({payablesList.length})</h4>
          <div className="max-h-40 overflow-y-auto border border-neutral-200 dark:border-primary-800 rounded-lg">
            {payablesList.map(p => (
              <div key={p.id} className="flex justify-between items-center px-3 py-2 border-b border-neutral-100 dark:border-primary-800/60 last:border-0">
                <div>
                  <p className="font-medium text-sm">{p.invoiceNumber}</p>
                  <p className="text-xs text-neutral-500 dark:text-neutral-400">{p.vendorName}</p>
                </div>
                <p className="font-semibold">{formatCurrency(p.netAmount, p.currencyCode)}</p>
              </div>
            ))}
          </div>
        </div>

        {/* Treasury Entity Selection */}
        <div>
          <label className="field-label mb-2 block">Paying Entity (Treasury)</label>
          <select
            value={payingEntityId}
            onChange={(e) => setPayingEntityId(e.target.value)}
            className="w-full px-3 py-2 border border-neutral-200 dark:border-primary-800 rounded-lg focus:ring-2 focus:ring-primary-500"
          >
            {treasuryEntities.length === 0 ? (
              <option value="">No treasury entities available</option>
            ) : (
              treasuryEntities.map(entity => (
                <option key={entity.id} value={entity.id}>
                  {entity.entityCode} - {entity.entityName}
                </option>
              ))
            )}
          </select>
        </div>

        {/* Preview Section */}
        {loading ? (
          <div className="flex items-center justify-center py-8">
            <Loader2 className="w-8 h-8 text-primary-600 dark:text-primary-200 animate-spin" />
          </div>
        ) : preview ? (
          <div className="bg-neutral-50 dark:bg-primary-950 rounded-lg p-4 space-y-4">
            <h4 className="font-medium text-neutral-900 dark:text-neutral-50">Payment Preview</h4>
            
            <div className="grid grid-cols-2 gap-4 text-sm">
              <div>
                <p className="text-neutral-500 dark:text-neutral-400">Paying Entity</p>
                <p className="font-medium">{preview.payingEntityCode} - {preview.payingEntityName}</p>
                {preview.payingVaNumber && (
                  <p className="text-xs text-neutral-400 dark:text-neutral-500">VA: {preview.payingVaNumber}</p>
                )}
              </div>
              <div>
                <p className="text-neutral-500 dark:text-neutral-400">On Behalf Of</p>
                <p className="font-medium">{preview.behalfEntityCode} - {preview.behalfEntityName}</p>
                {preview.behalfVaNumber && (
                  <p className="text-xs text-neutral-400 dark:text-neutral-500">VA: {preview.behalfVaNumber}</p>
                )}
              </div>
            </div>

            {/* Balance & Credit Limit Info */}
            {(preview.payingVaBalance !== undefined || preview.behalfVaBalance !== undefined) && (
              <div className="grid grid-cols-2 gap-4 text-xs bg-neutral-100 dark:bg-primary-800 rounded-lg p-3 mt-2">
                {/* Paying Entity Balance */}
                <div className="space-y-1">
                  <p className="font-medium text-neutral-700 dark:text-neutral-200">Treasury Balance</p>
                  <div className="flex justify-between">
                    <span className="text-neutral-500 dark:text-neutral-400">Current</span>
                    <span>{formatCurrency(preview.payingVaBalance || 0, preview.currencyCode)}</span>
                  </div>
                  {preview.payingVaCreditLimitAvailable !== undefined && preview.payingVaCreditLimitAvailable > 0 && (
                    <div className="flex justify-between">
                      <span className="text-neutral-500 dark:text-neutral-400">Credit Available</span>
                      <span className="text-success-600 dark:text-success-300">+{formatCurrency(preview.payingVaCreditLimitAvailable, preview.currencyCode)}</span>
                    </div>
                  )}
                  {preview.payingVaEffectiveAvailableBalance !== undefined && (
                    <div className="flex justify-between font-medium">
                      <span className="text-neutral-600 dark:text-neutral-300">Effective</span>
                      <span className="text-primary-700 dark:text-neutral-200">{formatCurrency(preview.payingVaEffectiveAvailableBalance, preview.currencyCode)}</span>
                    </div>
                  )}
                </div>
                {/* Behalf Entity Balance */}
                <div className="space-y-1">
                  <p className="font-medium text-neutral-700 dark:text-neutral-200">Subsidiary Balance</p>
                  <div className="flex justify-between">
                    <span className="text-neutral-500 dark:text-neutral-400">Current</span>
                    <span>{formatCurrency(preview.behalfVaBalance || 0, preview.currencyCode)}</span>
                  </div>
                  {preview.behalfVaCreditLimitAvailable !== undefined && preview.behalfVaCreditLimitAvailable > 0 && (
                    <div className="flex justify-between">
                      <span className="text-neutral-500 dark:text-neutral-400">Credit Available</span>
                      <span className="text-success-600 dark:text-success-300">+{formatCurrency(preview.behalfVaCreditLimitAvailable, preview.currencyCode)}</span>
                    </div>
                  )}
                  {preview.behalfVaEffectiveAvailableBalance !== undefined && (
                    <div className="flex justify-between font-medium">
                      <span className="text-neutral-600 dark:text-neutral-300">Effective</span>
                      <span className="text-primary-700 dark:text-neutral-200">{formatCurrency(preview.behalfVaEffectiveAvailableBalance, preview.currencyCode)}</span>
                    </div>
                  )}
                </div>
              </div>
            )}

            <div className="border-t border-neutral-200 dark:border-primary-800 pt-4 space-y-2">
              <div className="flex justify-between text-sm">
                <span>Total Payment Amount</span>
                <span className="font-medium">{formatCurrency(preview.totalPaymentAmount, preview.currencyCode)}</span>
              </div>
              {preview.charges?.map((charge, idx) => (
                <div key={idx} className="flex justify-between text-sm text-neutral-600 dark:text-neutral-300">
                  <span>{charge.chargeName}</span>
                  <span>{charge.waived ? <span className="text-success-600 dark:text-success-300">Waived</span> : formatCurrency(charge.calculatedAmount, preview.currencyCode)}</span>
                </div>
              ))}
              <div className="flex justify-between text-sm font-semibold border-t border-neutral-200 dark:border-primary-800 pt-2">
                <span>Net Recharge Amount</span>
                <span>{formatCurrency(preview.netRechargeAmount, preview.currencyCode)}</span>
              </div>
            </div>

            {preview.ihbLoanPreview && (
              <div className="bg-info-50 dark:bg-info-500/10 rounded-lg p-3 mt-4">
                <h5 className="text-sm font-medium text-info-800 mb-2 dark:text-info-300">IHB Loan Details</h5>
                <div className="grid grid-cols-2 gap-2 text-xs">
                  <div><span className="text-info-600 dark:text-info-300">Principal:</span> {formatCurrency(preview.ihbLoanPreview.principalAmount, preview.ihbLoanPreview.currencyCode)}</div>
                  <div><span className="text-info-600 dark:text-info-300">Rate:</span> {preview.ihbLoanPreview.interestRate}%</div>
                  <div><span className="text-info-600 dark:text-info-300">Tenor:</span> {preview.ihbLoanPreview.expectedTenorDays} days</div>
                  <div><span className="text-info-600 dark:text-info-300">Est. Interest:</span> {formatCurrency(preview.ihbLoanPreview.estimatedInterest || 0, preview.ihbLoanPreview.currencyCode)}</div>
                </div>
              </div>
            )}

            {error && (
              <p className="text-xs text-warning-600 dark:text-warning-300 flex items-center gap-1">
                <AlertTriangle className="w-3 h-3" /> {error}
              </p>
            )}
          </div>
        ) : null}

        {/* Actions */}
        <div className="flex justify-end gap-3 pt-4 border-t border-neutral-200 dark:border-primary-800">
          <Button variant="secondary" onClick={onClose}>Cancel</Button>
          <Button 
            variant="primary" 
            onClick={handleSubmit}
            disabled={!payingEntityId || payablesList.length === 0 || submitLoading}
          >
            {submitLoading ? <Loader2 className="w-4 h-4 animate-spin mr-2" /> : <Send className="w-4 h-4 mr-2" />}
            Submit POBO Request
          </Button>
        </div>
      </div>
    </Modal>
  );
};

// ============================================================================
// APPROVAL MODAL
// ============================================================================

interface ApprovalModalProps {
  isOpen: boolean;
  onClose: () => void;
  payable: PayablePhase2 | null;
  action: 'approve' | 'reject' | 'submit';
  onConfirm: (payableId: string, action: string, reason?: string) => Promise<void>;
  loading?: boolean;
}

const ApprovalModal: React.FC<ApprovalModalProps> = ({
  isOpen, onClose, payable, action, onConfirm, loading
}) => {
  const [reason, setReason] = useState('');
  const [notes, setNotes] = useState('');

  useEffect(() => {
    if (isOpen) {
      setReason('');
      setNotes('');
    }
  }, [isOpen]);

  if (!isOpen || !payable) return null;

  const titles: Record<string, string> = {
    approve: 'Approve Payable',
    reject: 'Reject Payable',
    submit: 'Submit for Approval',
  };

  const actionLabels: Record<string, string> = {
    approve: 'Approve',
    reject: 'Reject',
    submit: 'Submit',
  };

  const actionColors: Record<string, 'success' | 'error' | 'primary'> = {
    approve: 'success',
    reject: 'error',
    submit: 'primary',
  };

  const handleConfirm = async () => {
    await onConfirm(payable.id, action, action === 'reject' ? reason : notes);
    onClose();
  };

  return (
    <Modal isOpen={isOpen} onClose={onClose} title={titles[action]} size="md">
      <div className="space-y-6">
        {/* Payable Details */}
        <div className="bg-neutral-50 dark:bg-primary-950 rounded-lg p-4">
          <div className="grid grid-cols-2 gap-4 text-sm">
            <div>
              <p className="text-neutral-500 dark:text-neutral-400">Invoice Number</p>
              <p className="font-medium">{payable.invoiceNumber || payable.payableNumber}</p>
            </div>
            <div>
              <p className="text-neutral-500 dark:text-neutral-400">Vendor</p>
              <p className="font-medium">{payable.vendorName}</p>
            </div>
            <div>
              <p className="text-neutral-500 dark:text-neutral-400">Amount</p>
              <p className="font-semibold text-primary-600 dark:text-primary-200">
                {formatCurrency(payable.netAmount, payable.currencyCode)}
              </p>
            </div>
            <div>
              <p className="text-neutral-500 dark:text-neutral-400">Due Date</p>
              <p className="font-medium">{formatDate(payable.dueDate)}</p>
            </div>
          </div>
        </div>

        {/* Reason/Notes Input */}
        {action === 'reject' ? (
          <div>
            <label className="field-label block mb-2">
              Rejection Reason <span className="text-error-500">*</span>
            </label>
            <textarea
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="Please provide a reason for rejection..."
              rows={3}
              className="w-full px-3 py-2 border border-neutral-200 dark:border-primary-800 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-primary-500"
            />
          </div>
        ) : (
          <div>
            <label className="field-label block mb-2">
              Notes (Optional)
            </label>
            <textarea
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              placeholder="Add any notes..."
              rows={2}
              className="w-full px-3 py-2 border border-neutral-200 dark:border-primary-800 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-primary-500"
            />
          </div>
        )}

        {/* Warning for rejection */}
        {action === 'reject' && (
          <div className="flex items-start gap-3 p-3 bg-error-50 border border-error-200 rounded-lg dark:bg-error-500/10 dark:border-error-500/30">
            <AlertTriangle className="w-5 h-5 text-error-600 mt-0.5 dark:text-error-300" />
            <div className="text-sm">
              <p className="font-medium text-error-800 dark:text-error-300">This action cannot be undone</p>
              <p className="text-error-600 dark:text-error-300">The payable will be marked as rejected and the vendor will need to resubmit.</p>
            </div>
          </div>
        )}

        {/* Actions */}
        <div className="flex justify-end gap-3 pt-4 border-t border-neutral-200 dark:border-primary-800">
          <Button variant="secondary" onClick={onClose}>Cancel</Button>
          <Button
            variant={action === 'reject' ? 'danger' : action === 'approve' ? 'success' : 'primary'}
            onClick={handleConfirm}
            disabled={loading || (action === 'reject' && !reason.trim())}
          >
            {loading ? (
              <Loader2 className="w-4 h-4 animate-spin mr-2" />
            ) : action === 'approve' ? (
              <CheckCircle className="w-4 h-4 mr-2" />
            ) : action === 'reject' ? (
              <XCircle className="w-4 h-4 mr-2" />
            ) : (
              <Send className="w-4 h-4 mr-2" />
            )}
            {actionLabels[action]}
          </Button>
        </div>
      </div>
    </Modal>
  );
};

// ============================================================================
// PAYABLE ROW COMPONENT
// ============================================================================

interface PayableRowProps {
  payable: PayablePhase2;
  isSelected: boolean;
  onSelect: (id: string) => void;
  onViewDetails: (payable: PayablePhase2) => void;
  onRequestPobo: (payable: PayablePhase2) => void;
  onApprove: (payable: PayablePhase2) => void;
  onReject: (payable: PayablePhase2) => void;
  onSubmit: (payable: PayablePhase2) => void;
  onSchedule: (payable: PayablePhase2) => void;
  onEdit: (payable: PayablePhase2) => void;
  onPayNow: (payable: PayablePhase2) => void;
}

const PayableRow: React.FC<PayableRowProps> = ({
  payable, isSelected, onSelect, onViewDetails, onRequestPobo, onApprove, onReject, onSubmit, onSchedule, onEdit, onPayNow
}) => {
  // Determine which actions are available based on status
  const canSubmit = payable.status === 'DRAFT';
  const canApprove = payable.status === 'PENDING_APPROVAL';
  const canReject = payable.status === 'PENDING_APPROVAL';
  const canEdit = payable.status === 'DRAFT' || payable.status === 'REJECTED';
  const canSchedule = payable.status === 'APPROVED';
  const canPayNow = payable.status === 'APPROVED' || payable.status === 'SCHEDULED';

  return (
    <tr className={cn(
      "data-table-row group",
      isSelected && "bg-primary-50 dark:bg-primary-800/40"
    )}>
      <td className="data-table-cell">
        <input
          type="checkbox"
          checked={isSelected}
          onChange={() => onSelect(payable.id)}
          className="rounded border-neutral-300 dark:border-primary-700 text-primary-600 dark:text-primary-200 focus:ring-primary-500"
        />
      </td>
      <td className="data-table-cell">
        <div>
          <p className="font-medium text-primary-900 dark:text-neutral-50">{payable.invoiceNumber || payable.payableNumber}</p>
          <p className="text-xs text-neutral-500 dark:text-neutral-400">{payable.payableNumber}</p>
        </div>
      </td>
      <td className="data-table-cell">
        <div>
          <p className="font-medium text-sm text-primary-900 dark:text-neutral-50">{payable.vendorName}</p>
          {payable.owningEntityCode && (
            <p className="text-xs text-neutral-500 dark:text-neutral-400">{payable.owningEntityCode}</p>
          )}
        </div>
      </td>
      <td className="data-table-cell text-right">
        <p className="font-semibold text-primary-900 dark:text-neutral-50">{formatCurrency(payable.netAmount, payable.currencyCode)}</p>
        {payable.outstandingAmount !== payable.netAmount && (
          <p className="text-xs text-neutral-500 dark:text-neutral-400">Due: {formatCurrency(payable.outstandingAmount, payable.currencyCode)}</p>
        )}
      </td>
      <td className="data-table-cell">
        <div className="flex flex-col gap-1">
          <span className={cn(
            "text-sm",
            payable.isOverdue ? "text-error-600 dark:text-error-300 font-medium" : "text-neutral-600 dark:text-neutral-300"
          )}>
            {formatDate(payable.dueDate)}
          </span>
          {payable.isOverdue && (
            <Badge variant="error" size="sm">Overdue</Badge>
          )}
        </div>
      </td>
      <td className="data-table-cell">
        <div className="flex flex-col gap-1">
          {getStatusBadge(payable.status)}
          {getPoboStatusBadge(payable.poboRequestStatus)}
        </div>
      </td>
      <td className="data-table-cell">
        {getPaymentRouteBadge(payable.paymentRoute, payable.isIntercompany, payable.nettingStatus)}
      </td>
      <td className="data-table-cell">
        <div className="flex items-center gap-1">
          {/* Always visible: View Details */}
          <button
            onClick={() => onViewDetails(payable)}
            className="p-1.5 text-neutral-500 hover:text-primary-600 dark:text-primary-200 hover:bg-primary-50 dark:bg-primary-800/40 rounded-lg transition-colors dark:text-neutral-400 dark:hover:bg-primary-800/40"
            title="View Details"
          >
            <Eye className="w-4 h-4" />
          </button>

          {/* Draft status: Submit for approval, Edit */}
          {canSubmit && (
            <button
              onClick={() => onSubmit(payable)}
              className="p-1.5 text-neutral-500 hover:text-primary-600 dark:text-primary-200 hover:bg-primary-50 dark:bg-primary-800/40 rounded-lg transition-colors dark:text-neutral-400 dark:hover:bg-primary-800/40"
              title="Submit for Approval"
            >
              <Send className="w-4 h-4" />
            </button>
          )}
          {canEdit && (
            <button
              onClick={() => onEdit(payable)}
              className="p-1.5 text-neutral-500 hover:text-info-600 dark:text-info-300 hover:bg-info-50 dark:bg-info-500/10 rounded-lg transition-colors dark:text-neutral-400 dark:hover:bg-info-500/10"
              title="Edit"
            >
              <Edit className="w-4 h-4" />
            </button>
          )}

          {/* Pending Approval status: Approve, Reject */}
          {canApprove && (
            <button
              onClick={() => onApprove(payable)}
              className="p-1.5 text-neutral-500 hover:text-success-600 dark:text-success-300 hover:bg-success-50 dark:bg-success-500/10 rounded-lg transition-colors dark:text-neutral-400 dark:hover:bg-success-500/10"
              title="Approve"
            >
              <CheckCircle className="w-4 h-4" />
            </button>
          )}
          {canReject && (
            <button
              onClick={() => onReject(payable)}
              className="p-1.5 text-neutral-500 hover:text-error-600 dark:text-error-300 hover:bg-error-50 dark:bg-error-500/10 rounded-lg transition-colors dark:text-neutral-400 dark:hover:bg-error-500/10"
              title="Reject"
            >
              <XCircle className="w-4 h-4" />
            </button>
          )}

          {/* Approved status: Pay Now - Primary action */}
          {canPayNow && (
            <button
              onClick={() => onPayNow(payable)}
              className="p-1.5 text-neutral-500 hover:text-success-600 dark:text-success-300 hover:bg-success-50 dark:bg-success-500/10 rounded-lg transition-colors dark:text-neutral-400 dark:hover:bg-success-500/10"
              title="Pay Now"
            >
              <PlayCircle className="w-4 h-4" />
            </button>
          )}

          {/* POBO request (for approved payables) */}
          {payable.canRequestPobo && (
            <button
              onClick={() => onRequestPobo(payable)}
              className="p-1.5 text-neutral-500 hover:text-info-600 dark:text-info-300 hover:bg-info-50 dark:bg-info-500/10 rounded-lg transition-colors dark:text-neutral-400 dark:hover:bg-info-500/10"
              title="Request POBO"
            >
              <Landmark className="w-4 h-4" />
            </button>
          )}
          {payable.canAddToNetting && (
            <button
              className="p-1.5 text-neutral-500 hover:text-success-600 dark:text-success-300 hover:bg-success-50 dark:bg-success-500/10 rounded-lg transition-colors dark:text-neutral-400 dark:hover:bg-success-500/10"
              title="Add to Netting"
            >
              <GitBranch className="w-4 h-4" />
            </button>
          )}
        </div>
      </td>
    </tr>
  );
};

// ============================================================================
// MAIN PAGE COMPONENT
// ============================================================================

const EnhancedPayablesPage: React.FC = () => {
  const { navigate } = useNavigation();

  // Selection state
  const [corporates, setCorporates] = useState<Corporate[]>([]);
  const [programs, setPrograms] = useState<Program[]>([]);
  const [entities, setEntities] = useState<LegalEntity[]>([]);
  const [selectedCorporateId, setSelectedCorporateId] = useState('');
  const [selectedProgramId, setSelectedProgramId] = useState('');
  const [selectedEntityId, setSelectedEntityId] = useState('');

  // Data state
  const [payables, setPayables] = useState<PayablePhase2[]>([]);
  const [stats, setStats] = useState<PayableStatsPhase2 | null>(null);
  const [loading, setLoading] = useState(false);
  const [statsLoading, setStatsLoading] = useState(false);
  const [corporatesLoading, setCorporatesLoading] = useState(true);

  // UI state
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [searchTerm, setSearchTerm] = useState('');
  const [activeTab, setActiveTab] = useState('all');
  const [currentPage, setCurrentPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [showPoboModal, setShowPoboModal] = useState(false);
  const [poboPayables, setPoboPayables] = useState<PayablePhase2[]>([]);
  const [submitting, setSubmitting] = useState(false);

  // Approval modal state
  const [showApprovalModal, setShowApprovalModal] = useState(false);
  const [approvalPayable, setApprovalPayable] = useState<PayablePhase2 | null>(null);
  const [approvalAction, setApprovalAction] = useState<'approve' | 'reject' | 'submit'>('approve');
  const [approvalLoading, setApprovalLoading] = useState(false);

  // Load corporates on mount
  useEffect(() => {
    const loadCorporates = async () => {
      setCorporatesLoading(true);
      try {
        const result = await corporatesApi.getAll();
        const corpList = Array.isArray(result) ? result : (result as any)?.data || [];
        setCorporates(corpList);
        // Auto-select first corporate
        if (corpList.length > 0) {
          setSelectedCorporateId(corpList[0].id);
        }
      } catch (error) {
        console.error('Failed to load corporates:', error);
        setCorporates([]);
      } finally {
        setCorporatesLoading(false);
      }
    };
    loadCorporates();
  }, []);

  // Load programs when corporate changes
  useEffect(() => {
    if (!selectedCorporateId) {
      setPrograms([]);
      return;
    }
    
    const loadPrograms = async () => {
      try {
        const result = await programsApi.getAll({ corporateId: selectedCorporateId });
        const progList = Array.isArray(result) ? result : (result as any)?.content || (result as any)?.data || [];
        setPrograms(progList);
      } catch (error) {
        console.error('Failed to load programs:', error);
        setPrograms([]);
      }
    };
    loadPrograms();
  }, [selectedCorporateId]);

  // Load legal entities when corporate changes
  useEffect(() => {
    if (!selectedCorporateId) {
      setEntities([]);
      return;
    }
    
    const loadEntities = async () => {
      try {
        const result = await legalEntityApi.getByCorporate(selectedCorporateId);
        const entList = Array.isArray(result) ? result : (result as any)?.data || [];
        setEntities(entList);
      } catch (error) {
        console.error('Failed to load entities:', error);
        setEntities([]);
      }
    };
    loadEntities();
  }, [selectedCorporateId]);

  // Load payables
  const loadPayables = useCallback(async () => {
    // Corporates load asynchronously on mount (see loadCorporates above), so
    // selectedCorporateId is '' for the first render or two. Searching with
    // no corporate scope fires an unscoped "all payables" query that briefly
    // flashes the wrong data before the real corporate resolves and the
    // effect re-fires (see loadStats below for the worse version of this
    // race, where it used to shadow-swap the corporate entirely).
    if (!selectedCorporateId) {
      setPayables([]);
      setTotalPages(0);
      return;
    }
    setLoading(true);
    try {
      const result = await payablesApiPhase2.search({
        corporateId: selectedCorporateId,
        programId: selectedProgramId || undefined,
        owningEntityId: selectedEntityId || undefined,
        searchTerm: searchTerm || undefined,
        page: currentPage,
        size: 20,
        sortBy: 'createdAt',
        sortOrder: 'desc',
      });

      // Handle response - could be array or paginated response
      if (Array.isArray(result)) {
        setPayables(result);
        setTotalPages(1);
      } else if (result?.payables) {
        setPayables(result.payables);
        setTotalPages(result.totalPages || 1);
      } else if (result?.content) {
        setPayables(result.content);
        setTotalPages(result.totalPages || 1);
      } else {
        setPayables([]);
        setTotalPages(0);
      }
    } catch (error) {
      console.error('Failed to load payables:', error);
      setPayables([]);
    } finally {
      setLoading(false);
    }
  }, [selectedCorporateId, selectedProgramId, selectedEntityId, searchTerm, currentPage]);

  // Load stats
  const loadStats = useCallback(async () => {
    // Don't fall back to a hardcoded demo corporate here: selectedCorporateId
    // is briefly '' while loadCorporates() (above) is still in flight on
    // mount, and querying stats for a different, hardcoded corporate in that
    // window used to race the real query below (whichever HTTP response
    // landed last won), showing e.g. "51 total payables" for a corporate
    // that was never the one selected while the table legitimately showed
    // zero rows for the real one. Just wait for the real corporate.
    if (!selectedCorporateId) {
      setStats(null);
      return;
    }
    setStatsLoading(true);
    try {
      const result = await payablesApiPhase2.getStats(
        selectedCorporateId,
        selectedProgramId || undefined,
        selectedEntityId || undefined
      );
      setStats(result);
    } catch (error) {
      console.error('Failed to load stats:', error);
      setStats(null);
    } finally {
      setStatsLoading(false);
    }
  }, [selectedCorporateId, selectedProgramId, selectedEntityId]);

  // Trigger data load when filters change
  useEffect(() => {
    loadPayables();
    loadStats();
  }, [loadPayables, loadStats]);

  // Filter payables based on active tab
  const filteredPayables = useMemo(() => {
    const payablesList = Array.isArray(payables) ? payables : [];
    switch (activeTab) {
      case 'pending':
        return payablesList.filter(p => p.status === 'PENDING_APPROVAL' || p.status === 'PENDING_POBO');
      case 'intercompany':
        return payablesList.filter(p => p.isIntercompany);
      case 'pobo':
        return payablesList.filter(p => p.paymentRoute === 'POBO' || p.poboRequestStatus === 'PENDING_TREASURY_APPROVAL');
      case 'netting':
        return payablesList.filter(p => p.nettingStatus === 'INCLUDED' || p.nettingEligible);
      default:
        return payablesList;
    }
  }, [payables, activeTab]);

  // Handlers
  const handleSelectAll = () => {
    if (selectedIds.size === filteredPayables.length) {
      setSelectedIds(new Set());
    } else {
      setSelectedIds(new Set(filteredPayables.map(p => p.id)));
    }
  };

  const handleSelect = (id: string) => {
    const newSelected = new Set(selectedIds);
    if (newSelected.has(id)) {
      newSelected.delete(id);
    } else {
      newSelected.add(id);
    }
    setSelectedIds(newSelected);
  };

  const handleRequestPobo = (payable: PayablePhase2) => {
    setPoboPayables([payable]);
    setShowPoboModal(true);
  };

  const handleBulkPobo = () => {
    const selected = filteredPayables.filter(p => selectedIds.has(p.id) && p.canRequestPobo);
    if (selected.length > 0) {
      setPoboPayables(selected);
      setShowPoboModal(true);
    }
  };

  const handleSubmitPobo = async (payingEntityId: string, payableIds: string[]) => {
    setSubmitting(true);
    try {
      await payablesApiPhase2.requestPobo({
        payableIds,
        payingEntityId,
        requestedBy: 'current-user', // TODO: Get from auth
      });
      setShowPoboModal(false);
      setSelectedIds(new Set());
      loadPayables();
      loadStats();
    } catch (error) {
      console.error('Failed to submit POBO request:', error);
    } finally {
      setSubmitting(false);
    }
  };

  const handleCorporateChange = (id: string) => {
    setSelectedCorporateId(id);
    setSelectedProgramId('');
    setSelectedEntityId('');
    setCurrentPage(0);
  };

  const handleProgramChange = (id: string) => {
    setSelectedProgramId(id);
    setCurrentPage(0);
  };

  const handleEntityChange = (id: string) => {
    setSelectedEntityId(id);
    setCurrentPage(0);
  };

  // Approval action handlers
  const handleApprove = (payable: PayablePhase2) => {
    setApprovalPayable(payable);
    setApprovalAction('approve');
    setShowApprovalModal(true);
  };

  const handleReject = (payable: PayablePhase2) => {
    setApprovalPayable(payable);
    setApprovalAction('reject');
    setShowApprovalModal(true);
  };

  const handleSubmitForApproval = (payable: PayablePhase2) => {
    setApprovalPayable(payable);
    setApprovalAction('submit');
    setShowApprovalModal(true);
  };

  const handleSchedule = (payable: PayablePhase2) => {
    // For now, just navigate to edit page with schedule mode
    // Could also implement a schedule modal later
    navigate('payables-edit', { id: payable.id, mode: 'schedule' });
  };

  const handleEdit = (payable: PayablePhase2) => {
    navigate('payables-edit', { id: payable.id });
  };

  const handlePayNow = async (payable: PayablePhase2) => {
    if (!confirm(`Execute payment of ${payable.netAmount} ${payable.currencyCode} to ${payable.vendorName}?`)) {
      return;
    }

    try {
      const currentUser = 'current-user'; // TODO: Get from auth context
      await payablesApiPhase2.executePayment(payable.id, currentUser);

      // Refresh data after payment
      await loadPayables();
      await loadStats();
      alert('Payment executed successfully!');
    } catch (error: any) {
      console.error('Failed to execute payment:', error);
      alert(`Failed to execute payment: ${error?.response?.data?.message || error?.message || 'Unknown error'}`);
    }
  };

  const handleApprovalAction = async (payableId: string, action: string, reason?: string) => {
    setApprovalLoading(true);
    try {
      const currentUser = 'current-user'; // TODO: Get from auth context

      if (action === 'approve') {
        await payablesApiPhase2.approve(payableId, currentUser, reason);
      } else if (action === 'reject') {
        await payablesApiPhase2.reject(payableId, currentUser, reason || 'Rejected by user');
      } else if (action === 'submit') {
        await payablesApiPhase2.submitForApproval(payableId, currentUser);
      }

      // Refresh data after action
      await loadPayables();
      await loadStats();
      setShowApprovalModal(false);
      setApprovalPayable(null);
    } catch (error: any) {
      console.error(`Failed to ${action} payable:`, error);
      alert(`Failed to ${action} payable: ${error?.response?.data?.message || error?.message || 'Unknown error'}`);
    } finally {
      setApprovalLoading(false);
    }
  };

  // Tab counts
  const tabCounts = useMemo(() => {
    const payablesList = Array.isArray(payables) ? payables : [];
    return {
      all: stats?.totalCount || payablesList.length,
      pending: stats?.pendingCount || payablesList.filter(p => p.status === 'PENDING_APPROVAL' || p.status === 'PENDING_POBO').length,
      intercompany: stats?.intercompanyCount || payablesList.filter(p => p.isIntercompany).length,
      pobo: stats?.poboRequestedCount || payablesList.filter(p => p.paymentRoute === 'POBO').length,
      netting: stats?.nettingIncludedCount || payablesList.filter(p => p.nettingStatus === 'INCLUDED').length,
    };
  }, [payables, stats]);

  const selectedPayables = filteredPayables.filter(p => selectedIds.has(p.id));

  return (
    <Page>
      {/* Quick Actions */}
      <div className="flex items-center justify-end gap-2 animate-fade-in" style={{ animationDelay: '0.05s' }}>
        <Button variant="outline" onClick={() => { loadPayables(); loadStats(); }} leftIcon={<RefreshCw className="w-4 h-4" />}>
          Refresh
        </Button>
        <Button variant="primary" leftIcon={<Plus className="w-4 h-4" />} onClick={() => {
          // Pass selected corporate and entity IDs to the create page
          const params: Record<string, string> = {};
          if (selectedCorporateId) {
            params.corporateId = selectedCorporateId;
          }
          if (selectedEntityId) {
            params.legalEntityId = selectedEntityId;
          }
          console.log('Navigate to payables-create with params:', params);
          navigate('payables-create', Object.keys(params).length > 0 ? params : undefined);
        }}>
          New Payable
        </Button>
      </div>

      {/* Filter Bar (matches TreasuryHierarchyPage) */}
      <PayablesFilterBar
        corporates={corporates}
        programs={programs}
        entities={entities}
        selectedCorporateId={selectedCorporateId}
        selectedProgramId={selectedProgramId}
        selectedEntityId={selectedEntityId}
        onCorporateChange={handleCorporateChange}
        onProgramChange={handleProgramChange}
        onEntityChange={handleEntityChange}
        loading={corporatesLoading}
      />

      {/* Stats */}
      <PayablesStatsSection stats={stats} loading={statsLoading} />

      {/* Main Content Card */}
      <Card className="animate-fade-in" style={{ animationDelay: '0.4s' }}>
        <div className="h-1 bg-gradient-to-r from-primary-50/50 via-white to-primary-50/50 rounded-t-xl dark:from-primary-900 dark:via-primary-900 dark:to-primary-900" />

        {/* Tabs & Search */}
        <div className="flex items-center justify-between gap-4 px-4 pt-4 pb-2 border-b border-neutral-200 dark:border-primary-800">
          <div className="flex gap-1">
            {[
              { key: 'all', label: 'All', count: tabCounts.all },
              { key: 'pending', label: 'Pending', count: tabCounts.pending },
              { key: 'intercompany', label: 'Intercompany', count: tabCounts.intercompany },
              { key: 'pobo', label: 'POBO', count: tabCounts.pobo },
              { key: 'netting', label: 'Netting', count: tabCounts.netting },
            ].map(tab => (
              <button
                key={tab.key}
                onClick={() => setActiveTab(tab.key)}
                className={cn(
                  "px-4 py-2 text-sm font-medium border-b-2 transition-all duration-200",
                  activeTab === tab.key
                    ? "border-primary-600 text-primary-600 dark:text-primary-200"
                    : "border-transparent text-neutral-500 hover:text-neutral-700 dark:text-neutral-200 hover:border-neutral-300 dark:hover:border-primary-700 dark:text-neutral-400 dark:hover:text-neutral-200"
                )}
              >
                {tab.label}
                <span className={cn(
                  "ml-2 px-2 py-0.5 text-xs rounded-full transition-colors",
                  activeTab === tab.key ? "bg-primary-100 dark:bg-primary-700 text-primary-700 dark:text-neutral-200" : "bg-neutral-100 dark:bg-primary-800 text-neutral-600 dark:text-neutral-300"
                )}>{tab.count}</span>
              </button>
            ))}
          </div>

          <div className="flex items-center gap-3">
            <div className="relative">
              <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 w-4 h-4 text-neutral-400 dark:text-neutral-500" />
              <input
                type="text"
                placeholder="Search invoices..."
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                className="pl-9 pr-4 py-2 border border-neutral-200 dark:border-primary-800 rounded-lg text-sm bg-white dark:bg-primary-900 focus:ring-2 focus:ring-primary-500 focus:border-primary-500 transition-all w-64"
              />
            </div>
            {selectedIds.size > 0 && (
              <Button variant="outline" size="sm" onClick={handleBulkPobo} leftIcon={<Landmark className="w-4 h-4" />}>
                Request POBO ({selectedIds.size})
              </Button>
            )}
          </div>
        </div>

        {/* Table */}
        <div className="overflow-x-auto">
          <table className="data-table">
            <thead>
              <tr className="data-table-header">
                <th className="data-table-header-cell w-12">
                  <input
                    type="checkbox"
                    checked={selectedIds.size === filteredPayables.length && filteredPayables.length > 0}
                    onChange={handleSelectAll}
                    className="rounded border-neutral-300 dark:border-primary-700 text-primary-600 dark:text-primary-200 focus:ring-primary-500"
                  />
                </th>
                <th className="data-table-header-cell">Invoice</th>
                <th className="data-table-header-cell">Vendor / Entity</th>
                <th className="data-table-header-cell text-right">Amount</th>
                <th className="data-table-header-cell">Due Date</th>
                <th className="data-table-header-cell">Status</th>
                <th className="data-table-header-cell">Route</th>
                <th className="data-table-header-cell">Actions</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr>
                  <td colSpan={8} className="px-4 py-12 text-center">
                    <Loader2 className="w-8 h-8 text-primary-600 dark:text-primary-200 animate-spin mx-auto" />
                    <p className="text-neutral-500 mt-2 dark:text-neutral-400">Loading payables...</p>
                  </td>
                </tr>
              ) : filteredPayables.length === 0 ? (
                <tr>
                  <td colSpan={8} className="px-4 py-12 text-center">
                    <FileText className="w-12 h-12 text-neutral-300 mx-auto dark:text-neutral-600 mb-3" />
                    <p className="text-neutral-500 dark:text-neutral-400">No payables found</p>
                    <p className="text-neutral-400 text-sm mt-1 dark:text-neutral-500">
                      {!selectedCorporateId ? 'Select a corporate to view payables' : 'Try adjusting your filters'}
                    </p>
                  </td>
                </tr>
              ) : (
                filteredPayables.map(payable => (
                  <PayableRow
                    key={payable.id}
                    payable={payable}
                    isSelected={selectedIds.has(payable.id)}
                    onSelect={handleSelect}
                    onViewDetails={(p) => navigate(`/payables/${p.id}`)}
                    onRequestPobo={handleRequestPobo}
                    onApprove={handleApprove}
                    onReject={handleReject}
                    onSubmit={handleSubmitForApproval}
                    onSchedule={handleSchedule}
                    onEdit={handleEdit}
                    onPayNow={handlePayNow}
                  />
                ))
              )}
            </tbody>
          </table>
        </div>

        {/* Pagination */}
        {totalPages > 1 && (
          <div className="flex items-center justify-between px-4 py-3 border-t border-neutral-200 dark:border-primary-800">
            <p className="text-sm text-neutral-500 dark:text-neutral-400">
              Page {currentPage + 1} of {totalPages}
            </p>
            <div className="flex gap-2">
              <Button
                variant="outline"
                size="sm"
                disabled={currentPage === 0}
                onClick={() => setCurrentPage(p => p - 1)}
              >
                Previous
              </Button>
              <Button
                variant="outline"
                size="sm"
                disabled={currentPage >= totalPages - 1}
                onClick={() => setCurrentPage(p => p + 1)}
              >
                Next
              </Button>
            </div>
          </div>
        )}
      </Card>

      {/* POBO Modal */}
      <PoboRequestModal
        isOpen={showPoboModal}
        onClose={() => setShowPoboModal(false)}
        payables={poboPayables}
        entities={entities}
        onSubmit={handleSubmitPobo}
        loading={submitting}
      />

      {/* Approval Modal */}
      <ApprovalModal
        isOpen={showApprovalModal}
        onClose={() => {
          setShowApprovalModal(false);
          setApprovalPayable(null);
        }}
        payable={approvalPayable}
        action={approvalAction}
        onConfirm={handleApprovalAction}
        loading={approvalLoading}
      />
    </Page>
  );
};

export default EnhancedPayablesPage;