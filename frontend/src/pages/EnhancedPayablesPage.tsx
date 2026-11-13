import { Page } from '../components/layout/Page';
import { ScopeSelector } from '../components/layout/ScopeSelector';
import { Select, StatusIconBadge, StatTile } from '../components/ui';
/**
 * EnhancedPayablesPage.tsx - Phase 2 Complete Implementation
 * 
 * Features:
 * - Corporate/Program filter bar (matches TreasuryHierarchyPage)
 * - Entity selector (subsidiary filtering)
 * - Real API integration (no mock data)
 * - POBO by enrolment (In-House Bank): Pay Now shows who pays and the IHB headroom
 * - Intercompany payable indicators
 * - Netting status and quick-add
 * - Payment route visualization
 */

import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { Search, Plus, Send, Clock, FileText, Eye, AlertTriangle, RefreshCw, Loader2, Building2, Landmark, GitBranch, CheckCircle, XCircle, PlayCircle, Pencil } from 'lucide-react';
import { Card, Button, Badge, DataTable } from '../components/ui';
import { Modal } from '../components/ui/enhanced';
import { NettingCyclePickerModal } from '../components/treasury/NettingCyclePickerModal';
import { PaymentFxPanel, PaymentFx } from '../components/payments/PaymentFxPanel';
import toast from 'react-hot-toast';
import { formatCurrency, formatDate, cn } from '../utils';
import { useNavigation } from '../hooks/useNavigation';
import { 
  corporatesApi, 
  programsApi, 
  legalEntityApi,
  payablesApiPhase2,
  virtualAccountsApi,
  transactionsApi,
  type TransactionDetail,
  VirtualAccount,
  Corporate,
  Program,
  LegalEntity,
  PayablePhase2,
  PayableStatsPhase2,
  PaymentRoutePreview,
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
          <div className="min-w-[12.5rem]">
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
  const statItems = [
    { label: 'Total Payables', value: stats?.totalCount || 0, tone: 'primary' as const, icon: FileText },
    { label: 'Pending', value: stats?.pendingCount || 0, tone: 'warning' as const, icon: Clock },
    { label: 'Overdue', value: stats?.overdueCount || 0, tone: 'danger' as const, icon: AlertTriangle },
    { label: 'POBO', value: stats?.poboRequestedCount || 0, tone: 'info' as const, icon: Landmark },
    { label: 'Intercompany', value: stats?.intercompanyCount || 0, tone: 'accent' as const, icon: Building2 },
    { label: 'In Netting', value: stats?.nettingIncludedCount || 0, tone: 'success' as const, icon: GitBranch },
  ];

  return (
    <div className="grid gap-4 grid-cols-3 2xl:grid-cols-6">
      {statItems.map((item, idx) => {
        const Icon = item.icon;
        return (
          <StatTile
            key={idx}
            tone={item.tone}
            icon={<Icon className="w-5 h-5" />}
            label={item.label}
            value={item.value}
            loading={loading}
            delay={`${0.1 + idx * 0.05}s`}
          />
        );
      })}
    </div>
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

  const handleConfirm = async () => {
    await onConfirm(payable.id, action, action === 'reject' ? reason : notes);
    onClose();
  };

  return (
    <Modal isOpen={isOpen} onClose={onClose} title={titles[action]} size="md">
      <div className="space-y-6">
        {/* Payable Details */}
        <div className="bg-surface-page rounded-lg p-4">
          <div className="grid grid-cols-2 gap-4 text-body-sm">
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
            <PoboSettlement payable={payable} />
          </div>
        </div>

        {/* Reason/Notes Input */}
        {action === 'reject' ? (
          <div>
            <label className="field-label block mb-2">
              Rejection Reason <span className="text-error-500 dark:text-error-300">*</span>
            </label>
            <textarea
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="Please provide a reason for rejection..."
              rows={3}
              className="w-full px-3 py-2 border border-edge rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-primary-500"
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
              className="w-full px-3 py-2 border border-edge rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-primary-500"
            />
          </div>
        )}

        {/* Warning for rejection */}
        {action === 'reject' && (
          <div className="flex items-start gap-3 p-3 bg-error-50 border border-error-200 rounded-lg dark:bg-error-500/10 dark:border-error-500/30">
            <AlertTriangle className="w-5 h-5 text-error-600 mt-0.5 dark:text-error-300" />
            <div className="text-body-sm">
              <p className="font-medium text-error-800 dark:text-error-300">This action cannot be undone</p>
              <p className="text-error-600 dark:text-error-300">The payable will be marked as rejected and the vendor will need to resubmit.</p>
            </div>
          </div>
        )}

        {/* Actions */}
        <div className="flex justify-end gap-3 pt-4 border-t border-edge">
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

/**
 * A POBO payable settled on an IHB account in another currency (ihb-fx R7, R10), as a grid cell: before payment the
 * indicative settle amount; after it what the posting booked on that account (instructed amount, the all-in client
 * rate, the settle amount, the deal). Never the bank's mid, margin or floor. Nothing for a same-currency payable.
 */
const PoboSettlement: React.FC<{ payable: PayablePhase2 }> = ({ payable }) => {
  const [route, setRoute] = useState<PaymentRoutePreview | null>(null);
  const [booked, setBooked] = useState<TransactionDetail | null>(null);
  const pobo = payable.paymentRoute === 'POBO';
  const paidRef = pobo ? payable.poboTransactionRef : undefined;
  useEffect(() => {
    setRoute(null);
    setBooked(null);
    if (!pobo) return;
    let live = true;
    if (paidRef) {
      transactionsApi.getByReference(paidRef).then((r) => { if (live) setBooked(r.data ?? null); }).catch(() => undefined);
    } else {
      payablesApiPhase2.previewPobo({ payableIds: [payable.id] })
        .then((l) => { if (live) setRoute(Array.isArray(l) ? l[0] ?? null : null); }).catch(() => undefined);
    }
    return () => { live = false; };
  }, [payable.id, pobo, paidRef]);

  let text: string | null = null;
  if (booked?.instructedCurrency && booked.instructedAmount != null && booked.fxRate != null) {
    text = `Paid ${formatCurrency(booked.instructedAmount, booked.instructedCurrency)} at ${booked.fxRate}: `
      + `${formatCurrency(booked.amount, booked.currencyCode)} on ${booked.vaNumber ?? 'the IHB account'}${booked.fxDealRef ? ` · ${booked.fxDealRef}` : ''}`;
  } else if (route?.route === 'POBO' && route.settleCurrency && route.settleCurrency !== route.currencyCode) {
    text = `Settled on ${route.ihbCurrentVaNumber ?? 'the IHB account'} in ${route.settleCurrency} — `
      + (route.fxNeedsQuote ? 'rate negotiated by treasury at payment'
        : route.settleAmount != null ? `indicative ${formatCurrency(route.settleAmount, route.settleCurrency)}, fixed at payment`
        : 'at the rate on the payment date');
  }
  if (!text) return null;
  return (
    <div className="col-span-2">
      <p className="text-neutral-500 dark:text-neutral-400">Settlement</p>
      <p className="font-medium">{text}</p>
    </div>
  );
};

interface PayNowConfirmModalProps {
  isOpen: boolean;
  onClose: () => void;
  payable: PayablePhase2 | null;
  onConfirm: (fx: PaymentFx | null) => Promise<void>;
  loading?: boolean;
}

const PayNowConfirmModal: React.FC<PayNowConfirmModalProps> = ({
  isOpen, onClose, payable, onConfirm, loading
}) => {
  // The paying account: a payable in another currency than it must be converted (see PaymentFxPanel).
  const [account, setAccount] = useState<VirtualAccount | null>(null);
  const [fx, setFx] = useState<PaymentFx | null>(null);
  // Who pays: the treasury centre on behalf (enrolment) or the payable's own account, and why (spec R4-R6, D7).
  const [route, setRoute] = useState<PaymentRoutePreview | null>(null);
  const [routeError, setRouteError] = useState<string | null>(null);
  useEffect(() => {
    setAccount(null);
    setFx(null);
    setRoute(null);
    setRouteError(null);
    if (!isOpen || !payable) return;
    let live = true;
    payablesApiPhase2.previewPobo({ payableIds: [payable.id] })
      .then((list) => { if (live) setRoute(Array.isArray(list) ? list[0] ?? null : null); })
      .catch((err: any) => { if (live) setRouteError(err?.response?.data?.message || err?.message || 'Could not tell how this payable is paid'); });
    if (payable.virtualAccountId) {
      virtualAccountsApi.getById(payable.virtualAccountId).then((r) => { if (live) setAccount(r.data ?? null); }).catch(() => undefined);
    }
    return () => { live = false; };
  }, [isOpen, payable]);
  if (!isOpen || !payable) return null;
  const pobo = route?.route === 'POBO';
  // POBO: nothing to choose here; in another currency the treasury converts at payment (ihb-fx D3), on its own
  // entity. So no price is asked until the route is known, and none is sent for POBO (a price would be X's).
  const direct = route?.route === 'DIRECT' || (!route && !!routeError);
  const needsFx = direct && !!account && account.currencyCode !== payable.currencyCode;

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="Execute Payment" size="md">
      <div className="space-y-6">
        <div className="bg-surface-page rounded-lg p-4">
          <div className="grid grid-cols-2 gap-4 text-body-sm">
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

        {pobo && route && (
          <div className="flex items-start gap-3 p-4 rounded-lg border border-success-200 bg-success-50 dark:bg-success-500/10 dark:border-success-500/30">
            <Landmark className="w-5 h-5 text-success-600 flex-shrink-0 mt-0.5 dark:text-success-300" />
            <div className="text-body-sm">
              <p className="body-strong">
                Paid by {route.payerEntityName ?? route.payerEntityCode} from {route.paymentVaNumber} on behalf of {payable.owningEntityName ?? payable.owningEntityCode}
              </p>
              <p className="caption">
                Booked to IHB current account {route.ihbCurrentVaNumber}
                {route.settleCurrency && route.settleCurrency !== route.currencyCode && route.settleAmount != null &&
                  <> for {formatCurrency(route.settleAmount, route.settleCurrency)} (indicative, fixed at payment)</>}
                {route.headroom != null && <>; headroom {formatCurrency(route.headroom, route.settleCurrency ?? route.currencyCode)}</>}. No fee.
              </p>
            </div>
          </div>
        )}
        {!pobo && route?.reason && (
          <p className="body-sm">{route.reason}</p>
        )}
        {pobo && route?.fxNeedsQuote && (
          <p className="flex items-start gap-2 text-body-sm text-warning-700 dark:text-warning-300">
            <Clock className="w-4 h-4 flex-shrink-0 mt-0.5" />
            {payable.fxStatus === 'AWAITING_FX' ? 'Awaiting FX (treasury). ' : ''}Above the live-price limit, so the rate is
            negotiated: executing hands it to {route.payerEntityName ?? route.payerEntityCode} to agree the rate, and it is paid
            when they accept a price.
          </p>
        )}
        {(route?.refusal || routeError) && (
          <p className="flex items-start gap-2 text-body-sm text-error-600 dark:text-error-300">
            <AlertTriangle className="w-4 h-4 flex-shrink-0 mt-0.5" />{route?.refusal ?? routeError}
          </p>
        )}

        {account && direct && (
          <PaymentFxPanel
            accountId={account.id}
            corporateId={account.corporateId}
            entityId={account.owningEntityId}
            accountCurrency={account.currencyCode}
            currency={payable.currencyCode}
            amount={payable.netAmount}
            onChange={setFx}
          />
        )}

        <p className="body-sm">
          This will execute the payment immediately. This action cannot be undone.
        </p>

        <div className="flex justify-end gap-3 pt-4 border-t border-edge">
          <Button variant="secondary" onClick={onClose}>Cancel</Button>
          <Button variant="primary" onClick={() => onConfirm(direct ? fx : null)} disabled={loading || !!route?.refusal || (needsFx && !fx)}>
            {loading ? <Loader2 className="w-4 h-4 animate-spin mr-2" /> : <Send className="w-4 h-4 mr-2" />}
            Execute Payment
          </Button>
        </div>
      </div>
    </Modal>
  );
};

// ============================================================================
// PAYABLE ACTIONS CELL (DataTable "Actions" column)
// ============================================================================

interface PayableActionsCellProps {
  payable: PayablePhase2;
  onViewDetails: (payable: PayablePhase2) => void;
  onApprove: (payable: PayablePhase2) => void;
  onReject: (payable: PayablePhase2) => void;
  onSubmit: (payable: PayablePhase2) => void;
  onEdit: (payable: PayablePhase2) => void;
  onPayNow: (payable: PayablePhase2) => void;
  onAddToNetting: (payable: PayablePhase2) => void;
}

const PayableActionsCell: React.FC<PayableActionsCellProps> = ({
  payable, onViewDetails, onApprove, onReject, onSubmit, onEdit, onPayNow, onAddToNetting
}) => {
  const canSubmit = payable.status === 'DRAFT';
  const canApprove = payable.status === 'PENDING_APPROVAL';
  const canReject = payable.status === 'PENDING_APPROVAL';
  const canEdit = payable.status === 'DRAFT' || payable.status === 'REJECTED';
  // canBePaid is server-computed and also requires a source VA to be assigned -- a payable
  // can be in the right status and still be unpayable (no VA), so re-deriving from status
  // alone here would show "Pay Now" for payables that are guaranteed to fail on click.
  const canPayNow = payable.canBePaid && (payable.status === 'APPROVED' || payable.status === 'SCHEDULED');

  return (
    <div className="flex items-center gap-1" onClick={(e) => e.stopPropagation()}>
      <button
        onClick={() => onViewDetails(payable)}
        className="p-1.5 text-neutral-500 hover:text-primary-600 dark:hover:text-primary-200 dark:text-primary-200 hover:bg-primary-50 dark:bg-primary-800/40 rounded-lg transition-colors dark:text-neutral-400 dark:hover:bg-primary-800/40"
        title="View Details"
      >
        <Eye className="w-4 h-4" />
      </button>
      {canSubmit && (
        <button
          onClick={() => onSubmit(payable)}
          className="p-1.5 text-neutral-500 hover:text-primary-600 dark:hover:text-primary-200 dark:text-primary-200 hover:bg-primary-50 dark:bg-primary-800/40 rounded-lg transition-colors dark:text-neutral-400 dark:hover:bg-primary-800/40"
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
          <Pencil className="w-4 h-4" />
        </button>
      )}
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
      {canPayNow && (
        <button
          onClick={() => onPayNow(payable)}
          className="p-1.5 text-neutral-500 hover:text-success-600 dark:text-success-300 hover:bg-success-50 dark:bg-success-500/10 rounded-lg transition-colors dark:text-neutral-400 dark:hover:bg-success-500/10"
          title="Pay Now"
        >
          <PlayCircle className="w-4 h-4" />
        </button>
      )}
      {payable.nettingEligible && payable.nettingStatus === 'NOT_INCLUDED' && payable.status === 'APPROVED' && (
        <button
          onClick={() => onAddToNetting(payable)}
          className="p-1.5 text-neutral-500 hover:text-info-600 dark:text-info-300 hover:bg-info-50 dark:bg-info-500/10 rounded-lg transition-colors dark:text-neutral-400 dark:hover:bg-info-500/10"
          title="Add to Netting Cycle"
        >
          <GitBranch className="w-4 h-4" />
        </button>
      )}
    </div>
  );
};

// ============================================================================
// MAIN PAGE COMPONENT
// ============================================================================

const EnhancedPayablesPage: React.FC = () => {
  const { navigate, params } = useNavigation();

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
  const [searchTerm, setSearchTerm] = useState('');
  const [activeTab, setActiveTab] = useState('all');
  const [currentPage, setCurrentPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [viewPayable, setViewPayable] = useState<PayablePhase2 | null>(null);

  // Netting Cycle Picker Modal state
  const [showNettingPicker, setShowNettingPicker] = useState(false);
  const [nettingPayable, setNettingPayable] = useState<PayablePhase2 | null>(null);

  // Pay Now confirmation modal state
  const [payNowTarget, setPayNowTarget] = useState<PayablePhase2 | null>(null);
  const [payNowLoading, setPayNowLoading] = useState(false);

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
      // The Pending/Intercompany/POBO/Netting tabs filter client-side (see
      // filteredPayables below) on fields the search endpoint can't express
      // as a single-value query param (e.g. "pending" is status IN
      // (PENDING_APPROVAL, PENDING_POBO), and the backend only supports
      // exact-match equality, no IN/OR - see PayableRepository.searchPayables).
      // Paginating server-side at 20/page while filtering client-side meant
      // a tab could show "No payables found" whenever none of its matches
      // happened to land on the currently-loaded page, even though the tab's
      // own badge count (sourced from /payables/stats) said otherwise.
      // ponytail: fetch the whole corpus for a filtered tab instead of
      // teaching the backend query to do IN-clauses - correct at the demo's
      // realistic volumes (dozens, not thousands); revisit with real
      // server-side status-list filtering if a corporate's payables ever
      // outgrow one page.
      const isFilteredTab = activeTab !== 'all';
      const result = await payablesApiPhase2.search({
        corporateId: selectedCorporateId,
        programId: selectedProgramId || undefined,
        owningEntityId: selectedEntityId || undefined,
        searchTerm: searchTerm || undefined,
        page: isFilteredTab ? 0 : currentPage,
        size: isFilteredTab ? 1000 : 20,
        sortBy: 'createdAt',
        sortOrder: 'desc',
      });

      // Handle response - could be array or paginated response
      // `content` is the raw Spring `Page` shape: not in PayableListResponse, but some endpoints still return it.
      const raw = result as typeof result & { content?: typeof result.payables };
      if (Array.isArray(result)) {
        setPayables(result);
        setTotalPages(1);
      } else if (result?.payables) {
        setPayables(result.payables);
        setTotalPages(isFilteredTab ? 1 : (result.totalPages || 1));
      } else if (raw?.content) {
        setPayables(raw.content);
        setTotalPages(isFilteredTab ? 1 : (result.totalPages || 1));
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
  }, [selectedCorporateId, selectedProgramId, selectedEntityId, searchTerm, currentPage, activeTab]);

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

  const handleEdit = (payable: PayablePhase2) => {
    navigate('payables-edit', { id: payable.id });
  };

  const handlePayNow = (payable: PayablePhase2) => {
    setPayNowTarget(payable);
  };

  // Deep link from the Dashboard "Payments" block: open Pay Now / Approve for that payable.
  // Fetched by id so it works regardless of which corporate/entity the list is scoped to.
  const deepLinkHandled = React.useRef<string | null>(null);
  useEffect(() => {
    const id = params?.payableId;
    if (!id || deepLinkHandled.current === id) return;
    deepLinkHandled.current = id;
    payablesApiPhase2.getById(id)
      .then((p) => (params.action === 'APPROVE' ? handleApprove(p) : handlePayNow(p)))
      .catch(() => toast.error('Could not open that payment'));
    // params.action is read at that moment; depending on it would reopen the modal if the deep
    // link's action changed for the same payable.
    // eslint-disable-next-line react-hooks/exhaustive-deps -- one shot per payableId, enforced by the deepLinkHandled ref.
  }, [params?.payableId]);

  const handleConfirmPayNow = async (fx: PaymentFx | null) => {
    if (!payNowTarget) return;
    setPayNowLoading(true);
    try {
      const currentUser = 'current-user'; // TODO: Get from auth context
      await payablesApiPhase2.executePayment(payNowTarget.id, currentUser, undefined, undefined, fx);

      // Refresh data after payment
      await loadPayables();
      await loadStats();
      toast.success('Payment executed successfully');
      setPayNowTarget(null);
    } catch (error: any) {
      console.error('Failed to execute payment:', error);
      if (error?.response?.data?.error?.code === 'FX_PAYMENT_AWAITING_FX') {
        // ihb-fx D7: not paid, handed to treasury to negotiate the rate; the list shows it Awaiting FX
        toast(error.response.data.message);
        setPayNowTarget(null);
        loadPayables();
        return;
      }
      toast.error(`Failed to execute payment: ${error?.response?.data?.message || error?.message || 'Unknown error'}`);
    } finally {
      setPayNowLoading(false);
    }
  };

  const handleAddToNetting = (payable: PayablePhase2) => {
    setNettingPayable(payable);
    setShowNettingPicker(true);
  };

  const handleConfirmAddToNetting = async (cycleId: string) => {
    if (!nettingPayable) return;
    await payablesApiPhase2.addToNetting({
      payableIds: [nettingPayable.id],
      nettingCycleId: cycleId,
      addedBy: 'current-user',
    });
    await loadPayables();
    await loadStats();
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
      toast.error(`Failed to ${action} payable: ${error?.response?.data?.message || error?.message || 'Unknown error'}`);
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
        <div className="h-1 bg-gradient-to-r from-primary-50/50 via-white to-primary-50/50 rounded-t-lg dark:from-primary-900 dark:via-primary-900 dark:to-primary-900" />

        {/* Tabs & Search */}
        <div className="flex items-center justify-between gap-4 px-4 pt-4 pb-2 border-b border-edge">
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
                onClick={() => { setActiveTab(tab.key); setCurrentPage(0); }}
                className={cn(
                  "px-4 py-2 text-body-sm font-medium border-b-2 transition-all duration-200",
                  activeTab === tab.key
                    ? "border-primary-600 text-primary-600 dark:text-primary-200"
                    : "border-transparent text-neutral-500 hover:text-neutral-700 dark:text-neutral-200 hover:border-neutral-300 dark:hover:border-primary-700 dark:text-neutral-400 dark:hover:text-neutral-200"
                )}
              >
                {tab.label}
                <span className={cn(
                  "ml-2 px-2 py-0.5 text-caption rounded-full transition-colors",
                  activeTab === tab.key ? "bg-primary-100 dark:bg-primary-700 text-primary-700 dark:text-neutral-200" : "bg-surface-muted text-neutral-600 dark:text-neutral-300"
                )}>{tab.count}</span>
              </button>
            ))}
          </div>

          <div className="flex items-center gap-3">
            <div className="relative">
              <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 w-4 h-4 text-neutral-400" />
              <input
                type="text"
                placeholder="Search invoices..."
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                className="pl-9 pr-4 py-2 border border-edge rounded-lg text-body-sm bg-surface-card focus:ring-2 focus:ring-primary-500 focus:border-primary-500 transition-all w-64"
              />
            </div>
          </div>
        </div>

        {/* Table */}
        <DataTable
          data={filteredPayables}
          keyExtractor={(payable) => payable.id}
          loading={loading}
          onRowClick={(payable) => setViewPayable(payable)}
          pagination={totalPages > 1}
          pageSize={20}
          currentPage={currentPage + 1}
          totalCount={totalPages * 20}
          onPageChange={(p) => setCurrentPage(p - 1)}
          emptyIcon={<FileText className="w-12 h-12 text-neutral-300 dark:text-neutral-400" />}
          emptyTitle="No payables found"
          emptyDescription={!selectedCorporateId ? 'Select a corporate to view payables' : 'Try adjusting your filters'}
          columns={[
            {
              key: 'invoiceNumber',
              header: 'Invoice', minWidth: 160,
              render: (_, payable) => (
                <div>
                  <p className="font-medium text-primary-900 dark:text-neutral-50">{payable.invoiceNumber || payable.payableNumber}</p>
                  <p className="caption">{payable.payableNumber}</p>
                </div>
              ),
            },
            {
              key: 'vendorName',
              header: 'Vendor / Entity', minWidth: 110,
              render: (_, payable) => (
                <div>
                  <p className="body-strong">{payable.vendorName}</p>
                  {payable.owningEntityCode && <p className="caption">{payable.owningEntityCode}</p>}
                </div>
              ),
            },
            {
              key: 'netAmount',
              header: 'Amount', minWidth: 140,
              align: 'right',
              render: (_, payable) => (
                <>
                  <p className="font-semibold text-primary-900 dark:text-neutral-50">{formatCurrency(payable.netAmount, payable.currencyCode)}</p>
                  {payable.outstandingAmount !== payable.netAmount && (
                    <p className="caption">Due: {formatCurrency(payable.outstandingAmount, payable.currencyCode)}</p>
                  )}
                </>
              ),
            },
            {
              key: 'dueDate',
              header: 'Due Date', minWidth: 100, dropOrder: 1,
              render: (_, payable) => (
                <div className="flex flex-col gap-1">
                  <span className={cn("text-body-sm", payable.isOverdue ? "text-error-600 dark:text-error-300 font-medium" : "text-neutral-600 dark:text-neutral-300")}>
                    {formatDate(payable.dueDate)}
                  </span>
                  {payable.isOverdue && <Badge variant="error" size="sm">Overdue</Badge>}
                </div>
              ),
            },
            {
              key: 'status',
              header: 'Status', minWidth: 150,
              render: (_, payable) => (
                <div className="flex flex-col gap-1">
                  {getStatusBadge(payable.status)}
                  {getPoboStatusBadge(payable.poboRequestStatus)}
                  {payable.fxStatus === 'AWAITING_FX' && <Badge variant="warning" size="sm">Awaiting FX (treasury)</Badge>}
                </div>
              ),
            },
            { key: 'paymentRoute', header: 'Route', minWidth: 100, dropOrder: 2, render: (_, payable) => getPaymentRouteBadge(payable.paymentRoute, payable.isIntercompany, payable.nettingStatus) },
            {
              key: 'actions',
              header: 'Actions', minWidth: 110,
              render: (_, payable) => (
                <PayableActionsCell
                  payable={payable}
                  onViewDetails={(p) => setViewPayable(p)}
                  onApprove={handleApprove}
                  onReject={handleReject}
                  onSubmit={handleSubmitForApproval}
                  onEdit={handleEdit}
                  onPayNow={handlePayNow}
                  onAddToNetting={handleAddToNetting}
                />
              ),
            },
          ]}
        />
      </Card>

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

      {/* Pay Now Confirmation Modal */}
      <PayNowConfirmModal
        isOpen={!!payNowTarget}
        onClose={() => setPayNowTarget(null)}
        payable={payNowTarget}
        onConfirm={handleConfirmPayNow}
        loading={payNowLoading}
      />

      {/* Netting Cycle Picker Modal */}
      <NettingCyclePickerModal
        isOpen={showNettingPicker}
        corporateId={selectedCorporateId}
        onClose={() => { setShowNettingPicker(false); setNettingPayable(null); }}
        items={nettingPayable ? [{
          id: nettingPayable.id,
          label: nettingPayable.payableNumber,
          amount: nettingPayable.outstandingAmount,
          currencyCode: nettingPayable.currencyCode,
        }] : []}
        onConfirm={handleConfirmAddToNetting}
      />

      {/* View Details Modal */}
      <Modal isOpen={!!viewPayable} onClose={() => setViewPayable(null)} title={viewPayable?.payableNumber || 'Payable'} size="md">
        {viewPayable && (
          <div className="space-y-4">
            <div className="flex items-center gap-2">
              {getStatusBadge(viewPayable.status)}
              {getPaymentRouteBadge(viewPayable.paymentRoute, viewPayable.isIntercompany, viewPayable.nettingStatus)}
              {viewPayable.fxStatus === 'AWAITING_FX' && <Badge variant="warning" size="sm">Awaiting FX (treasury)</Badge>}
            </div>
            <div className="grid grid-cols-2 gap-4 text-body-sm">
              <div>
                <p className="text-neutral-500 dark:text-neutral-400">Vendor</p>
                <p className="font-medium">{viewPayable.vendorName}</p>
              </div>
              <div>
                <p className="text-neutral-500 dark:text-neutral-400">Invoice Number</p>
                <p className="font-medium">{viewPayable.invoiceNumber}</p>
              </div>
              <div>
                <p className="text-neutral-500 dark:text-neutral-400">Invoice Date</p>
                <p className="font-medium">{formatDate(viewPayable.invoiceDate)}</p>
              </div>
              <div>
                <p className="text-neutral-500 dark:text-neutral-400">Due Date</p>
                <p className="font-medium">{formatDate(viewPayable.dueDate)}</p>
              </div>
              <div>
                <p className="text-neutral-500 dark:text-neutral-400">Gross Amount</p>
                <p className="font-medium">{formatCurrency(viewPayable.grossAmount, viewPayable.currencyCode)}</p>
              </div>
              <div>
                <p className="text-neutral-500 dark:text-neutral-400">Paid</p>
                <p className="font-medium">{formatCurrency(viewPayable.paidAmount, viewPayable.currencyCode)}</p>
              </div>
              <div>
                <p className="text-neutral-500 dark:text-neutral-400">Outstanding</p>
                <p className="font-medium">{formatCurrency(viewPayable.outstandingAmount, viewPayable.currencyCode)}</p>
              </div>
              <div>
                <p className="text-neutral-500 dark:text-neutral-400">Owning Entity</p>
                <p className="font-medium">{viewPayable.owningEntityName || viewPayable.owningEntityCode || '—'}</p>
              </div>
              {viewPayable.isIntercompany && (
                <div>
                  <p className="text-neutral-500 dark:text-neutral-400">Counterparty</p>
                  <p className="font-medium">{viewPayable.counterpartyEntityName || viewPayable.counterpartyEntityCode || '—'}</p>
                </div>
              )}
              {viewPayable.poboRequestStatus && (
                <div>
                  <p className="text-neutral-500 dark:text-neutral-400">POBO Status</p>
                  <p className="font-medium">{viewPayable.poboRequestStatus}</p>
                </div>
              )}
              <PoboSettlement payable={viewPayable} />
              {viewPayable.paymentRouteNote && (
                <div className="col-span-2">
                  <p className="text-neutral-500 dark:text-neutral-400">Payment route</p>
                  <p className="font-medium">{viewPayable.paymentRouteNote}</p>
                </div>
              )}
              {viewPayable.rejectionReason && (
                <div className="col-span-2">
                  <p className="text-neutral-500 dark:text-neutral-400">Rejection Reason</p>
                  <p className="font-medium text-error-600 dark:text-error-300">{viewPayable.rejectionReason}</p>
                </div>
              )}
            </div>
          </div>
        )}
      </Modal>
    </Page>
  );
};

export default EnhancedPayablesPage;