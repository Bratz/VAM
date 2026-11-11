import { Page } from '../components/layout/Page';
import { ScopeSelector } from '../components/layout/ScopeSelector';
/**
 * EnhancedReceivablesPage.tsx
 * 
 * AR Automation with COBO (Collect On Behalf Of), VIBAN, and IHB Integration
 * 
 * PHASE 3 COMPLETE VERSION:
 * - COBO is set up by enrolment (In-House Bank) and decided when an invoice is created; no per-invoice request
 * - Intercompany Tab with visual badges
 * - Pending Netting Tab
 * - COBO History Tab: COBO invoices by subsidiary and currency (read-only), with "apply a bank credit" for money
 *   that reached the treasury outside the invoice's VIBAN
 */

import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { Search, Download, RefreshCw, Clock, ArrowDownLeft, FileText, Eye, X, AlertTriangle, Copy, Plus, QrCode, Loader2, Building2, GitMerge, Play, XCircle } from 'lucide-react';
import { Card, Button, Badge, Input, Select, StatusIconBadge, DataTable, StatTile, Checkbox } from '../components/ui';
import { Modal } from '../components/ui/enhanced';
import { NettingCyclePickerModal } from '../components/treasury/NettingCyclePickerModal';
import { MandatesTab } from '../components/collections/MandatesTab';
import { CollectionRunsTab } from '../components/collections/CollectionRunsTab';
import { OutboxTab } from '../components/collections/OutboxTab';
import { CollectionPlanModal } from '../components/collections/CollectionPlanModal';

/** Invoices that can still be collected (by COBO or a collection run): open, partly paid or overdue. */
const COLLECTABLE = ['OPEN', 'PARTIAL', 'OVERDUE'];
const SETTLED = ['PAID', 'CANCELLED', 'WRITTEN_OFF', 'NETTED', 'DRAFT']; // not owed (DRAFT: not issued yet)
const LAST_CORPORATE_KEY = 'receivables.corporateId';
// Today as a local YYYY-MM-DD: dueDate is date-only, and new Date(dueDate) < new Date() calls an invoice
// due today overdue for anyone east of UTC.
const todayIso = () => {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
};
/** Money still owed and the due date has passed, whatever the stored status says (OPEN, PARTIAL, OVERDUE, COBO_*). */
const isOverdue = (i: { outstandingAmount: number; dueDate: string; status: string }) =>
  i.outstandingAmount > 0 && !!i.dueDate && i.dueDate < todayIso() && !SETTLED.includes(i.status);
const daysOverdue = (dueDate: string) =>
  Math.round((new Date(`${todayIso()}T00:00:00`).getTime() - new Date(`${dueDate}T00:00:00`).getTime()) / 86400000);
import { formatCurrency, formatDate, cn } from '../utils';
import {
  receivablesApi,
  receivablesApiPhase3,
  corporatesApi,
  legalEntityApi,
  apiClient,
  type ApiResponse,
  type CollectionRoute,
  type CoboRequestStatus,
  type NettingStatusReceivable,
  type ReceivableStatusPhase3,
  type Corporate,
  type LegalEntity,
  type CoboCollectCredit,
} from '../services/api';
import { errorText } from './fx/fxFormat';
import { useNavigation } from '../hooks/useNavigation';
import { useReportingRates } from '../hooks/useReportingRates';
import { useMarket } from '../context/useMarket';

// ============================================================================
// TYPES
// ============================================================================

interface ReceivablesStats {
  totalReceivables: number;
  openInvoices: number;
  partialPaid: number;
  overdueAmount: number;
  collectedThisMonth: number;
  invoiceCount: number;
  openCount: number;
  partialCount: number;
  overdueCount: number;
  paidCount: number;
  averageDaysOutstanding: number;
  coboPendingCount?: number;
  coboPendingAmount?: number;
  intercompanyCount?: number;
  intercompanyAmount?: number;
  nettingEligibleCount?: number;
  nettingEligibleAmount?: number;
  nettingIncludedCount?: number;
  nettingIncludedAmount?: number;
}

interface InvoicePhase3 {
  id: string;
  invoiceNumber: string;
  customerName: string;
  customerId: string;
  customerVaNumber: string;
  invoiceDate: string;
  dueDate: string;
  invoiceAmount: number;
  paidAmount: number;
  outstandingAmount: number;
  currencyCode: string;
  status: ReceivableStatusPhase3;
  description?: string;
  createdAt: string;
  owningEntityId?: string;
  owningEntityCode?: string;
  owningEntityName?: string;
  isIntercompany: boolean;
  intercompanyEntityId?: string;
  intercompanyEntityCode?: string;
  intercompanyEntityName?: string;
  counterpartyPayableId?: string;
  isCobo: boolean;
  coboCollectorEntityId?: string;
  coboCollectorEntityCode?: string;
  coboCollectorEntityName?: string;
  coboRequestStatus: CoboRequestStatus;
  coboTransactionRef?: string;
  coboRechargeId?: string;
  coboIhbDepositId?: string;
  coboRequestedAt?: string;
  coboRequestedBy?: string;
  coboActionedAt?: string;
  coboActionedBy?: string;
  nettingEligible: boolean;
  nettingCycleId?: string;
  nettingCycleRef?: string;
  nettingEntryId?: string;
  nettingStatus: NettingStatusReceivable;
  nettingSettlementRef?: string;
  collectionRoute: CollectionRoute;
  assignedViban?: string;
  vibanId?: string;
  paymentLink?: string;
  qrCodeUrl?: string;
  subsidiaryVaId?: string;
}

interface VibanRecord {
  id: string;
  virtualIban: string;
  reference: string;
  customerName: string;
  expectedAmount: number;
  receivedAmount: number;
  currency: string;
  status: 'ACTIVE' | 'PAID' | 'EXPIRED' | 'CANCELLED';
  createdAt: string;
  expiresAt: string;
  purpose: string;
  invoiceId?: string;
}

// ============================================================================
// STATUS CONFIGURATIONS
// ============================================================================

const statusConfig: Record<string, { variant: 'success' | 'warning' | 'error' | 'neutral' | 'info'; label: string }> = {
  DRAFT: { variant: 'neutral', label: 'Draft' },
  OPEN: { variant: 'info', label: 'Open' },
  OVERDUE: { variant: 'error', label: 'Overdue' },
  PARTIAL: { variant: 'warning', label: 'Partial' },
  PAID: { variant: 'success', label: 'Paid' },
  CANCELLED: { variant: 'neutral', label: 'Cancelled' },
  WRITTEN_OFF: { variant: 'neutral', label: 'Written Off' },
  PENDING_COBO: { variant: 'warning', label: 'Pending COBO' },
  COBO_APPROVED: { variant: 'success', label: 'COBO Approved' },
  COBO_REJECTED: { variant: 'error', label: 'COBO Rejected' },
  PENDING_NETTING: { variant: 'warning', label: 'Pending Netting' },
  NETTED: { variant: 'success', label: 'Netted' },
};

const coboStatusConfig: Record<string, { variant: 'success' | 'warning' | 'error' | 'neutral' | 'info'; label: string }> = {
  NOT_REQUESTED: { variant: 'neutral', label: 'Not Requested' },
  PENDING_ENTITY_APPROVAL: { variant: 'warning', label: 'Pending Entity' },
  PENDING_TREASURY_APPROVAL: { variant: 'warning', label: 'Pending Treasury' },
  APPROVED: { variant: 'success', label: 'Approved' },
  REJECTED: { variant: 'error', label: 'Rejected' },
  COLLECTED: { variant: 'success', label: 'Collected' },
  FAILED: { variant: 'error', label: 'Failed' },
};

const nettingStatusConfig: Record<string, { variant: 'success' | 'warning' | 'error' | 'neutral' | 'info'; label: string }> = {
  NOT_INCLUDED: { variant: 'neutral', label: 'Not Included' },
  PENDING: { variant: 'warning', label: 'Pending' },
  INCLUDED: { variant: 'info', label: 'Included' },
  SETTLED: { variant: 'success', label: 'Settled' },
  EXCLUDED: { variant: 'neutral', label: 'Excluded' },
};

// ============================================================================
// HELPER COMPONENTS
// ============================================================================

const getStatusBadge = (status: string) => {
  const config = statusConfig[status];
  return <Badge variant={config?.variant || 'neutral'} size="sm">{config?.label || status}</Badge>;
};

const getCoboStatusBadge = (status: string) => {
  const config = coboStatusConfig[status];
  return <Badge variant={config?.variant || 'neutral'} size="sm">{config?.label || status}</Badge>;
};

const getNettingStatusBadge = (status: string) => {
  const config = nettingStatusConfig[status];
  return <Badge variant={config?.variant || 'neutral'} size="sm">{config?.label || status}</Badge>;
};

// Stats Card Component
const STAT_TONE_BY_COLOR: Record<'emerald' | 'amber' | 'rose' | 'blue' | 'purple' | 'indigo', 'success' | 'warning' | 'danger' | 'info' | 'accent' | 'primary'> = {
  emerald: 'success',
  amber: 'warning',
  rose: 'danger',
  blue: 'info',
  purple: 'accent',
  indigo: 'primary',
};

const StatsCard: React.FC<{
  title: string;
  value: string | number;
  subtitle?: string;
  icon: React.ReactNode;
  trend?: { value: number; positive: boolean };
  color?: 'emerald' | 'amber' | 'rose' | 'blue' | 'purple' | 'indigo';
  delay?: number;
}> = ({ title, value, subtitle, icon, color = 'blue', delay = 0 }) => {
  return (
    <StatTile
      tone={STAT_TONE_BY_COLOR[color]}
      icon={icon}
      label={title}
      value={value}
      sub={subtitle}
      delay={`${delay}s`}
    />
  );
};

// Apply Bank Credit Modal: money for a COBO invoice that reached the collector outside the VIBAN (spec D6)
interface ApplyCreditModalProps {
  invoice: InvoicePhase3 | null;
  onClose: () => void;
  /** The unmatched bank credit to apply. */
  onConfirm: (unmatchedPaymentId: string) => Promise<void>;
  loading: boolean;
}

const ApplyCreditModal: React.FC<ApplyCreditModalProps> = ({ invoice, onClose, onConfirm, loading }) => {
  const [credits, setCredits] = useState<CoboCollectCredit[] | null>(null);
  const [creditId, setCreditId] = useState('');
  const [creditsError, setCreditsError] = useState<string | null>(null);

  // An unmatched bank credit on the collecting entity's accounts in the invoice currency. Nothing is pre-selected;
  // the treasurer picks the one that paid.
  useEffect(() => {
    if (!invoice) return;
    let live = true;
    setCredits(null);
    setCreditId('');
    setCreditsError(null);
    receivablesApiPhase3.coboCollectCredits(invoice.id)
      .then(list => { if (live) setCredits(list); })
      .catch(err => { if (live) setCreditsError(errorText(err)); });
    return () => { live = false; };
  }, [invoice]);

  if (!invoice) return null;
  const collector = invoice.coboCollectorEntityCode ?? 'the collecting entity';

  return (
    <Modal isOpen onClose={onClose} title="Apply Bank Credit" size="md">
      <div className="space-y-4">
        <p className="body-sm">
          Choose the bank credit that paid {invoice.invoiceNumber}. It is applied to the invoice and credited to
          {' '}{invoice.owningEntityCode ?? 'the subsidiary'}&apos;s in-house account.
        </p>
        <div className="bg-surface-page rounded-lg p-4">
          <div className="grid grid-cols-2 gap-4 text-body-sm">
            <div>
              <p className="text-neutral-500 dark:text-neutral-400">Invoice</p>
              <p className="font-mono font-medium">{invoice.invoiceNumber}</p>
            </div>
            <div>
              <p className="text-neutral-500 dark:text-neutral-400">Outstanding</p>
              <p className="font-semibold">{formatCurrency(invoice.outstandingAmount, invoice.currencyCode)}</p>
            </div>
            <div>
              <p className="text-neutral-500 dark:text-neutral-400">Collector</p>
              <p className="font-medium">{invoice.coboCollectorEntityCode || '-'}</p>
            </div>
            <div>
              <p className="text-neutral-500 dark:text-neutral-400">On Behalf Of</p>
              <p className="font-medium">{invoice.owningEntityCode || '-'}</p>
            </div>
          </div>
        </div>
        <div>
          <Select
            id="cobo-collect-credit"
            label="Bank credit"
            value={creditId}
            onChange={(e) => setCreditId(e.target.value)}
            placeholder={credits === null && !creditsError ? 'Loading credits…' : 'Choose the bank credit'}
            options={(credits ?? []).map(c => ({
              value: c.id,
              label: [formatCurrency(c.amount, c.currencyCode), c.payerName, c.paymentDate ? formatDate(c.paymentDate) : null, c.vaNumber]
                .filter(Boolean).join(' · '),
            }))}
            hint={`Unmatched ${invoice.currencyCode} credits on ${collector}'s accounts`}
          />
          {credits?.length === 0 && (
            <p className="mt-2 text-body-sm text-error-700 dark:text-error-300">
              No unmatched {invoice.currencyCode} credit on {collector}&apos;s accounts. Nothing can be applied until the money arrives.
            </p>
          )}
          {creditsError && <p className="mt-2 text-body-sm text-error-700 dark:text-error-300">{creditsError}</p>}
        </div>
        <div className="flex justify-end gap-3 pt-4 border-t">
          <Button variant="outline" onClick={onClose} disabled={loading}>Cancel</Button>
          <Button
            onClick={() => onConfirm(creditId)}
            disabled={loading || !creditId}
            leftIcon={loading ? <Loader2 className="w-4 h-4 animate-spin" /> : <Play className="w-4 h-4" />}
          >
            {loading ? 'Processing...' : 'Apply Credit'}
          </Button>
        </div>
      </div>
    </Modal>
  );
};

// ============================================================================
// MAIN COMPONENT
// ============================================================================

const EnhancedReceivablesPage: React.FC = () => {
  const { navigate } = useNavigation();
  
  // Filter state
  const [corporates, setCorporates] = useState<Corporate[]>([]);
  const [legalEntities, setLegalEntities] = useState<LegalEntity[]>([]);
  const [selectedCorporateId, setSelectedCorporateId] = useState<string>('');
  const [selectedEntityId, setSelectedEntityId] = useState<string>('');
  
  // Data state
  const [invoices, setInvoices] = useState<InvoicePhase3[]>([]);
  const [vibans, setVibans] = useState<VibanRecord[]>([]);
  // stats is derived, not stored: it is a pure function of the invoices and the FX
  // rates, and keeping it in state is how it came to be computed in one place and
  // read in another.
  const { profile } = useMarket();
  const reportingCurrency = profile.defaultCurrency || 'AED';
  
  // UI state
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [activeTab, setActiveTab] = useState<'invoices' | 'intercompany' | 'pending-netting' | 'vibans' | 'cobo-history' | 'mandates' | 'collection-runs' | 'outbox'>('invoices');
  const [planIds, setPlanIds] = useState<string[] | null>(null);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [, setProcessing] = useState(false);
  

  // Netting Cycle Picker Modal state
  const [showNettingPicker, setShowNettingPicker] = useState(false);
  const [nettingInvoice, setNettingInvoice] = useState<InvoicePhase3 | null>(null);
  
  // Apply Bank Credit (COBO) modal state
  const [creditInvoice, setCreditInvoice] = useState<InvoicePhase3 | null>(null);
  const [viewInvoice, setViewInvoice] = useState<InvoicePhase3 | null>(null);
  const [creditLoading, setCreditLoading] = useState(false);

  // Fetch initial data
  useEffect(() => {
    const loadCorporates = async () => {
      try {
        const response = await corporatesApi.getAll();
        if (response.success && response.data) {
          setCorporates(response.data);
          let last: string | null = null;
          try { last = localStorage.getItem(LAST_CORPORATE_KEY); } catch { /* storage unavailable */ }
          const remembered = response.data.find((c) => c.id === last);
          if (remembered || response.data.length > 0) {
            setSelectedCorporateId((remembered ?? response.data[0]).id);
          }
        }
      } catch (err) {
        console.error('Failed to load corporates:', err);
      }
    };
    loadCorporates();
  }, []);

  // Load entities when corporate changes (entities are at corporate level)
  useEffect(() => {
    if (!selectedCorporateId) return;
    
    const loadEntities = async () => {
      try {
        const response = await legalEntityApi.getByCorporate(selectedCorporateId);
        if (response.success && response.data) {
          setLegalEntities(Array.isArray(response.data) ? response.data : []);
        }
      } catch (err) {
        console.error('Failed to load entities:', err);
      }
    };
    loadEntities();
  }, [selectedCorporateId]);

  // Fetch invoices and VIBANs
  const fetchData = useCallback(async () => {
    setLoading(true);
    setError(null);

    try {
      // Fetch invoices using /receivables/invoices endpoint
      let mappedInvoices: InvoicePhase3[] = [];

      try {
        // Use the /invoices endpoint which exists in the backend
        const headers: Record<string, string> = {};
        if (selectedCorporateId) {
          headers['X-Corporate-Id'] = selectedCorporateId;
        }
        if (selectedEntityId) {
          headers['X-Legal-Entity-Id'] = selectedEntityId;
        } else {
          // This page has its own "All Entities" selector -- without this
          // marker, the global axios interceptor silently backfills
          // X-Legal-Entity-Id from whatever entity another page's picker
          // last cached in localStorage, re-scoping "All Entities" down to
          // one entity's invoices (same bug the marker already fixes for
          // payablesApiPhase2.search()/getStats() in services/api.ts).
          headers['X-No-Entity-Scope'] = '1';
        }

        const invoicesResponse = await apiClient.get<ApiResponse<any[]>>('/receivables/invoices', {
          headers,
          params: { status: undefined }
        });
        
        if (invoicesResponse.data?.success && invoicesResponse.data?.data) {
          const invoiceData = Array.isArray(invoicesResponse.data.data) ? invoicesResponse.data.data : [];
          mappedInvoices = invoiceData.map((inv: any) => ({
            id: inv.id,
            invoiceNumber: inv.invoiceNumber || inv.receivableNumber || inv.externalReference || '',
            customerName: inv.customerName || '',
            customerId: inv.customerId || inv.customerPartyId || '',
            customerVaNumber: inv.viban || inv.customerVaNumber || '',
            invoiceDate: inv.invoiceDate || inv.issueDate || inv.createdAt || '',
            dueDate: inv.dueDate || '',
            invoiceAmount: inv.amount ?? inv.grossAmount ?? inv.netAmount ?? 0,
            paidAmount: inv.paidAmount || 0,
            // ?? not ||: a settled invoice has outstandingAmount 0, and `||` fell through
            // to the full invoice amount, adding every paid invoice back into the totals.
            outstandingAmount: inv.outstandingAmount ?? inv.amount ?? 0,
            currencyCode: inv.currencyCode || 'AED',
            status: inv.status || 'OPEN',
            description: inv.description || '',
            createdAt: inv.createdAt || '',
            owningEntityId: inv.owningEntityId,
            owningEntityCode: inv.owningEntityCode,
            owningEntityName: inv.owningEntityName,
            isIntercompany: inv.isIntercompany || false,
            intercompanyEntityId: inv.intercompanyEntityId,
            intercompanyEntityCode: inv.intercompanyEntityCode,
            intercompanyEntityName: inv.intercompanyEntityName,
            counterpartyPayableId: inv.counterpartyPayableId,
            isCobo: inv.isCobo || false,
            coboCollectorEntityId: inv.coboCollectorEntityId,
            coboCollectorEntityCode: inv.coboCollectorEntityCode,
            coboCollectorEntityName: inv.coboCollectorEntityName,
            coboRequestStatus: inv.coboRequestStatus || 'NOT_REQUESTED',
            coboTransactionRef: inv.coboTransactionRef,
            coboRechargeId: inv.coboRechargeId,
            coboIhbDepositId: inv.coboIhbDepositId,
            coboRequestedAt: inv.coboRequestedAt,
            coboRequestedBy: inv.coboRequestedBy,
            coboActionedAt: inv.coboActionedAt,
            coboActionedBy: inv.coboActionedBy,
            nettingEligible: inv.nettingEligible || false,
            nettingCycleId: inv.nettingCycleId,
            nettingCycleRef: inv.nettingCycleRef,
            nettingEntryId: inv.nettingEntryId,
            nettingStatus: inv.nettingStatus || 'NOT_INCLUDED',
            nettingSettlementRef: inv.nettingSettlementRef,
            collectionRoute: inv.collectionRoute || 'DIRECT',
            assignedViban: inv.viban,
            vibanId: inv.primaryVibanId,
            paymentLink: inv.paymentLink,
            qrCodeUrl: inv.qrCodeUrl,
            subsidiaryVaId: inv.virtualAccountId,
          }));
        }
      } catch (apiErr) {
        console.warn('Invoice API failed, using empty list:', apiErr);
        // Set empty invoices - UI will show "No invoices found"
        mappedInvoices = [];
      }
      
      setInvoices(mappedInvoices);


      // Fetch VIBANs
      const vibanResponse = await receivablesApi.getPaymentVibans(selectedCorporateId || undefined);
      if (vibanResponse.success && vibanResponse.data) {
        setVibans(vibanResponse.data.map((v: any) => ({
          id: v.id,
          virtualIban: v.virtualIban,
          reference: v.reference,
          customerName: v.customerName,
          expectedAmount: v.expectedAmount,
          receivedAmount: v.receivedAmount,
          currency: v.currency,
          status: v.status as VibanRecord['status'],
          createdAt: v.createdAt,
          expiresAt: v.expiresAt,
          purpose: v.purpose,
          invoiceId: v.linkedReceivableId,
        })));
      }
    } catch (err: any) {
      setError(err.message || 'Failed to load receivables');
    } finally {
      setLoading(false);
    }
  }, [selectedCorporateId, selectedEntityId]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  // Filtered invoices based on search
  // Unique currencies across the loaded invoices, and one rate each into the reporting
  // currency. Memoised because useReportingRates keys its fetch on this array's identity.
  const invoiceCurrencies = useMemo(
    () => Array.from(new Set(invoices.map((i) => i.currencyCode).filter(Boolean))),
    [invoices]
  );
  const { rates, excluded: unconvertedCurrencies } = useReportingRates(invoiceCurrencies, reportingCurrency);

  // Every amount below is converted before it is summed. These used to be raw .reduce()
  // calls straight across the invoice list, so a total added AED to GBP and the tile
  // labelled the result with whatever formatCurrency defaulted to -- on this environment's
  // data, AED 7,987,147 + GBP 2,026,231 shown as one GBP figure.
  //
  // An invoice whose currency has no rate is left out of the amounts rather than folded in
  // at 1:1, and its currency is named under the tiles. Counts stay over every invoice: a
  // count needs no conversion, and hiding rows from it would misreport the book.
  const stats = useMemo<ReceivablesStats | null>(() => {
    if (loading) return null;

    // An invoice is overdue when money is still owed on it and its due date has passed.
    // ReceivablesDto declares isOverdue/daysOverdue but only ever sets them in a hardcoded
    // demo list, and ReceivableStatusPhase3 has no OVERDUE member, so it is derived here.
    // ISO strings against the local date: dueDate is date-only, so new Date(dueDate) < new
    // Date() would call an invoice due today overdue for anyone east of UTC.
    const now = new Date();
    const settled = SETTLED;

    const sum = (list: InvoicePhase3[], pick: (i: InvoicePhase3) => number) =>
      list.reduce((total, i) => {
        const rate = rates.get(i.currencyCode);
        return rate === undefined ? total : total + pick(i) * rate;
      }, 0);
    const outstanding = (i: InvoicePhase3) => i.outstandingAmount;
    const paid = (i: InvoicePhase3) => i.paidAmount;

    // Mean age of the invoices that still have money owed, from invoice date to today.
    // Was hardcoded to 25 and read by nothing; it is a duration rather than a money figure,
    // so no conversion applies. Invoices with no invoice date are left out of the average
    // rather than counted as zero days old, and an empty book reports 0 rather than NaN.
    const unsettled = invoices.filter(
      (i) => i.outstandingAmount > 0 && !settled.includes(i.status) && !!i.invoiceDate
    );
    const daysSince = (iso: string) =>
      Math.max(0, Math.floor((now.getTime() - new Date(`${iso}T00:00:00`).getTime()) / 86400000));
    const averageDaysOutstanding = unsettled.length
      ? Math.round(unsettled.reduce((total, i) => total + daysSince(i.invoiceDate), 0) / unsettled.length)
      : 0;

    const open = invoices.filter((i) => i.outstandingAmount > 0 && !settled.includes(i.status) && !isOverdue(i));
    const partial = invoices.filter((i) => i.status === 'PARTIAL');
    const paidInvoices = invoices.filter((i) => i.status === 'PAID');
    const overdue = invoices.filter(isOverdue);
    // Collected by treasury on a subsidiary's behalf and still owed
    const coboOutstanding = invoices.filter((i) => i.isCobo && i.outstandingAmount > 0 && !settled.includes(i.status));
    const intercompany = invoices.filter((i) => i.isIntercompany);
    const nettingEligible = invoices.filter((i) => i.nettingEligible);
    const nettingIncluded = invoices.filter((i) => i.nettingStatus === 'INCLUDED' || i.nettingStatus === 'PENDING');

    return {
      // What is owed: not drafts, nor cancelled or written off. Equals Not yet due + Overdue.
      totalReceivables: sum(invoices.filter((i) => !settled.includes(i.status)), outstanding),
      openInvoices: sum(open, outstanding),
      partialPaid: sum(partial, paid),
      overdueAmount: sum(overdue, outstanding),
      collectedThisMonth: sum(paidInvoices, paid),
      invoiceCount: invoices.length,
      openCount: open.length,
      partialCount: partial.length,
      overdueCount: overdue.length,
      paidCount: paidInvoices.length,
      averageDaysOutstanding,
      coboPendingCount: coboOutstanding.length,
      coboPendingAmount: sum(coboOutstanding, outstanding),
      intercompanyCount: intercompany.length,
      intercompanyAmount: sum(intercompany, outstanding),
      nettingEligibleCount: nettingEligible.length,
      nettingEligibleAmount: sum(nettingEligible, outstanding),
      nettingIncludedCount: nettingIncluded.length,
      nettingIncludedAmount: sum(nettingIncluded, outstanding),
    };
  }, [invoices, rates, loading]);

  const filteredInvoices = useMemo(() => {
    if (!searchQuery) return invoices;
    const query = searchQuery.toLowerCase();
    return invoices.filter(inv => 
      inv.invoiceNumber.toLowerCase().includes(query) ||
      inv.customerName.toLowerCase().includes(query) ||
      inv.description?.toLowerCase().includes(query) ||
      inv.customerVaNumber?.toLowerCase().replace(/\s/g, '').includes(query.replace(/\s/g, '')) ||
      inv.owningEntityCode?.toLowerCase().includes(query)
    );
  }, [invoices, searchQuery]);

  // Tab-specific filters
  const intercompanyInvoices = useMemo(() => 
    filteredInvoices.filter(inv => inv.isIntercompany), 
    [filteredInvoices]
  );

  const pendingNettingInvoices = useMemo(() => 
    filteredInvoices.filter(inv => inv.nettingStatus === 'PENDING' || inv.nettingStatus === 'INCLUDED'), 
    [filteredInvoices]
  );

  // COBO invoices by subsidiary and currency (R19)
  const coboHistoryInvoices = useMemo(() =>
    filteredInvoices.filter(inv => inv.isCobo || (inv.coboRequestStatus && inv.coboRequestStatus !== 'NOT_REQUESTED'))
      .sort((a, b) => (a.owningEntityCode ?? '').localeCompare(b.owningEntityCode ?? '')
        || a.currencyCode.localeCompare(b.currencyCode) || (b.invoiceDate ?? '').localeCompare(a.invoiceDate ?? '')),
    [filteredInvoices]
  );

  // Handlers
  const handleSelectInvoice = (id: string) => {
    setSelectedIds(prev => {
      const newSet = new Set(prev);
      if (newSet.has(id)) {
        newSet.delete(id);
      } else {
        newSet.add(id);
      }
      return newSet;
    });
  };

  const handleSelectAll = () => {
    const selectableIds = filteredInvoices
      .filter(inv => COLLECTABLE.includes(inv.status))
      .map(inv => inv.id);
    
    if (selectedIds.size === selectableIds.length) {
      setSelectedIds(new Set());
    } else {
      setSelectedIds(new Set(selectableIds));
    }
  };

  // Money for a COBO invoice that reached the collector outside the VIBAN: apply an unmatched bank credit (D6)
  const handleApplyCredit = async (unmatchedPaymentId: string) => {
    if (!creditInvoice) return;
    setCreditLoading(true);
    try {
      await receivablesApiPhase3.executeCoboCollection({ receivableId: creditInvoice.id, unmatchedPaymentId });
      await fetchData();
      setCreditInvoice(null);
    } catch (err: any) {
      setError(errorText(err));
    } finally {
      setCreditLoading(false);
    }
  };

  const handleAddToNetting = (invoice: InvoicePhase3) => {
    setNettingInvoice(invoice);
    setShowNettingPicker(true);
  };

  const handleConfirmAddToNetting = async (cycleId: string) => {
    if (!nettingInvoice) return;
    await receivablesApiPhase3.addToNettingCycle({
      receivableId: nettingInvoice.id,
      nettingCycleId: cycleId,
      addedBy: 'current-user',
    });
    await fetchData();
  };

  const handleRemoveFromNetting = async (invoice: InvoicePhase3) => {
    setProcessing(true);
    try {
      await receivablesApiPhase3.removeFromNettingCycle(invoice.id, 'current-user');
      await fetchData();
    } catch (err: any) {
      setError(err.message || 'Failed to remove from netting');
    } finally {
      setProcessing(false);
    }
  };

  const handleGenerateViban = async (invoice: InvoicePhase3) => {
    setProcessing(true);
    try {
      await receivablesApi.createPaymentViban({
        receivableId: invoice.id,
        customerName: invoice.customerName,
        expectedAmount: invoice.outstandingAmount,
        currency: invoice.currencyCode,
        purpose: 'Invoice Payment',
      }, selectedCorporateId || undefined);
      await fetchData();
    } catch (err: any) {
      // Unlike the mock this replaced, this call can genuinely fail (e.g. no collection VA
      // on the receivable) -- err.message alone is just Axios's generic "Request failed with
      // status code 400", not the real reason the backend gave.
      setError(err?.response?.data?.message || err.message || 'Failed to generate VIBAN');
    } finally {
      setProcessing(false);
    }
  };

  const handleCreateInvoice = () => {
    // Pass selected corporate and entity IDs to the create page
    const params: Record<string, string> = {};
    if (selectedCorporateId) {
      params.corporateId = selectedCorporateId;
    }
    if (selectedEntityId) {
      params.legalEntityId = selectedEntityId;
    }
    navigate('receivables-create', Object.keys(params).length > 0 ? params : undefined);
  };

  const handleIssueDraft = async (invoice: InvoicePhase3) => {
    try {
      await apiClient.post(`/receivables/invoices/${invoice.id}/issue`);
      await fetchData();
    } catch (err) {
      setError(err instanceof Error ? err.message : `Could not issue ${invoice.invoiceNumber}`);
    }
  };

  const handleViewInvoice = (invoice: InvoicePhase3) => {
    setViewInvoice(invoice);
  };

  const handleExport = () => {
    // Export functionality
    const csvContent = [
      ['Invoice', 'Customer', 'Amount', 'Currency', 'Due Date', 'Status', 'COBO Status', 'Netting Status'].join(','),
      ...filteredInvoices.map(inv => [
        inv.invoiceNumber,
        inv.customerName,
        inv.outstandingAmount,
        inv.currencyCode,
        inv.dueDate,
        inv.status,
        inv.coboRequestStatus,
        inv.nettingStatus,
      ].join(','))
    ].join('\n');
    
    const blob = new Blob([csvContent], { type: 'text/csv' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `receivables-${new Date().toISOString().split('T')[0]}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  };

  // Tabs configuration
  const tabs = [
    { id: 'invoices' as const, label: 'All Invoices', count: filteredInvoices.length },
    { id: 'intercompany' as const, label: 'Intercompany', count: intercompanyInvoices.length },
    { id: 'pending-netting' as const, label: 'Pending Netting', count: pendingNettingInvoices.length },
    { id: 'vibans' as const, label: 'VIBANs', count: vibans.length },
    { id: 'cobo-history' as const, label: 'COBO History', count: coboHistoryInvoices.length },
    { id: 'collection-runs' as const, label: 'Collection runs', count: undefined },
    { id: 'mandates' as const, label: 'Mandates', count: undefined },
    { id: 'outbox' as const, label: 'Outbox', count: undefined },
  ];

  return (
    <Page>
      {/* Quick Actions */}
      <div className="flex items-center justify-end gap-2 animate-fade-in" style={{ animationDelay: '0.05s' }}>
        {activeTab === 'invoices' && (
          <Button variant="outline" size="sm" onClick={handleSelectAll}>
            {selectedIds.size > 0 && selectedIds.size === filteredInvoices.filter(inv => COLLECTABLE.includes(inv.status)).length
              ? 'Clear selection'
              : 'Select all eligible'}
          </Button>
        )}
        {selectedIds.size > 0 && selectedCorporateId && (
          <Button variant="outline" onClick={() => setPlanIds(Array.from(selectedIds))}>
            Plan collection ({selectedIds.size})
          </Button>
        )}
        <Button variant="outline" onClick={fetchData} leftIcon={<RefreshCw className="w-4 h-4" />}>
          Refresh
        </Button>
        <Button variant="outline" onClick={handleExport} leftIcon={<Download className="w-4 h-4" />}>
          Export
        </Button>
        <Button variant="primary" onClick={handleCreateInvoice} leftIcon={<Plus className="w-4 h-4" />}>
          Create Invoice
        </Button>
      </div>

      {/* Search input — separated from the scope picker so each owns its
          own role and visual recipe. The previous mixed row had the search
          input flexing alongside three raw `<select>` boxes; with the scope
          picker now living in its own ScopeSelector Card, search gets its
          own narrow Card and reads cleaner. */}
      <Card className="animate-fade-in" style={{ animationDelay: '0.1s' }}>
        <div className="p-4">
          <div className="relative">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400" />
            <Input
              placeholder="Search by invoice, customer, VIBAN or entity..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="pl-10"
            />
          </div>
        </div>
      </Card>

      {/* Scope picker — Corporate + Entity. Entity goes in
          ScopeSelector's `rightSlot` so it gets the same medallion / label /
          Select recipe as the other two fields. */}
      <ScopeSelector
        mode="corporate-only"
        requireSelection
        corporates={corporates}
        selectedCorporateId={selectedCorporateId}
        onCorporateChange={(id) => {
          setSelectedCorporateId(id);
          setSelectedEntityId('');
          try { localStorage.setItem(LAST_CORPORATE_KEY, id); } catch { /* storage unavailable */ }
        }}
        rightSlot={(
          <div className="flex items-center gap-2">
            <StatusIconBadge tone="success" icon={Building2} size="sm" />
            <div className="min-w-[12.5rem]">
              <label className="label">Entity</label>
              <Select
                value={selectedEntityId}
                onChange={(e) => setSelectedEntityId(e.target.value)}
                options={[
                  { value: '', label: `All Entities${legalEntities.length ? ` (${legalEntities.length})` : ''}` },
                  ...legalEntities.map(e => ({ value: e.id, label: `${e.entityCode} – ${e.entityName}` })),
                ]}
                selectSize="sm"
                aria-label="Legal entity"
              />
            </div>
          </div>
        )}
      />

      {/* Stats Cards */}
      {stats && (
        <div className="grid gap-4 grid-cols-3 2xl:grid-cols-6">
          <StatsCard
            title="Total Receivables"
            value={formatCurrency(stats.totalReceivables, reportingCurrency)}
            subtitle={`${stats.invoiceCount} invoices · unpaid avg ${stats.averageDaysOutstanding}d old`}
            icon={<FileText className="w-5 h-5" />}
            color="indigo"
            delay={0.15}
          />
          <StatsCard
            title="Not yet due"
            value={formatCurrency(stats.openInvoices, reportingCurrency)}
            subtitle={`${stats.openCount} invoices`}
            icon={<Clock className="w-5 h-5" />}
            color="blue"
            delay={0.2}
          />
          <StatsCard
            title="Overdue"
            value={formatCurrency(stats.overdueAmount, reportingCurrency)}
            subtitle={`${stats.overdueCount} invoices`}
            icon={<AlertTriangle className="w-5 h-5" />}
            color="rose"
            delay={0.25}
          />
          <StatsCard
            title="COBO Outstanding"
            value={formatCurrency(stats.coboPendingAmount || 0, reportingCurrency)}
            subtitle={`${stats.coboPendingCount || 0} collected by treasury, unpaid`}
            icon={<ArrowDownLeft className="w-5 h-5" />}
            color="amber"
            delay={0.3}
          />
          <StatsCard
            title="Intercompany"
            value={formatCurrency(stats.intercompanyAmount || 0, reportingCurrency)}
            subtitle={`${stats.intercompanyCount || 0} IC invoices`}
            icon={<Building2 className="w-5 h-5" />}
            color="purple"
            delay={0.35}
          />
          <StatsCard
            title="In Netting"
            value={formatCurrency(stats.nettingIncludedAmount || 0, reportingCurrency)}
            subtitle={`${stats.nettingIncludedCount || 0} in cycle`}
            icon={<GitMerge className="w-5 h-5" />}
            color="emerald"
            delay={0.4}
          />
        </div>
      )}
      {stats && unconvertedCurrencies.length > 0 && (
        <p className="caption mt-2">
          Amounts are shown in {reportingCurrency} and leave out invoices in{' '}
          {unconvertedCurrencies.join(', ')} — no rate was available today. Counts include them.
        </p>
      )}

      {/* Error Display */}
      {error && (
        <Card className="animate-fade-in border-error-200 bg-error-50 dark:border-error-500/30 dark:bg-error-500/10">
          <div className="h-1 bg-gradient-to-r from-error-100 via-error-200 to-error-100 rounded-t-lg" />
          <div className="p-4 flex items-center gap-3">
            <StatusIconBadge tone="error" icon={XCircle} />
            <span className="flex-1 text-body-sm text-error-700 dark:text-error-300">{error}</span>
            <Button variant="ghost" size="sm" onClick={() => setError(null)} className="text-error-600 hover:bg-error-100 dark:text-error-300 dark:hover:bg-error-500/20">
              <X className="w-4 h-4" />
            </Button>
          </div>
        </Card>
      )}

      {/* Main Content Card */}
      <Card className="animate-fade-in" style={{ animationDelay: '0.45s' }}>
        <div className="h-1 bg-gradient-to-r from-primary-50/50 via-white to-primary-50/50 rounded-t-lg dark:from-primary-900 dark:via-primary-900 dark:to-primary-900" />
        {/* Tabs */}
        <div className="border-b border-edge">
          <div className="flex gap-1 px-4">
            {tabs.map(tab => (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id)}
                className={cn(
                  'px-4 py-3 text-body-sm font-medium border-b-2 transition-all duration-200',
                  activeTab === tab.id
                    ? 'border-primary-600 text-primary-600 dark:text-primary-200'
                    : 'border-transparent text-neutral-500 hover:text-neutral-700 hover:border-neutral-300 dark:text-neutral-400 dark:hover:text-neutral-200 dark:hover:border-primary-700'
                )}
              >
                {tab.label}
                {tab.count !== undefined && (
                <span className={cn(
                  'ml-2 px-2 py-0.5 text-caption rounded-full transition-colors',
                  activeTab === tab.id ? 'bg-primary-100 text-primary-700 dark:bg-primary-700 dark:text-neutral-200' : 'bg-surface-muted text-neutral-600 dark:text-neutral-300'
                )}>
                  {tab.count}
                </span>
                )}
              </button>
            ))}
          </div>
        </div>

        {/* Tab Content */}
        <div className="p-4">
          {loading ? (
            <div className="flex items-center justify-center py-12">
              <Loader2 className="w-8 h-8 animate-spin text-cat-1 dark:text-cat-1-fg" />
              <span className="ml-3 text-neutral-500 dark:text-neutral-400">Loading receivables...</span>
            </div>
          ) : (
            <>
              {/* All Invoices Tab */}
              {activeTab === 'invoices' && (
                <DataTable
                  data={filteredInvoices}
                  keyExtractor={(invoice) => invoice.id}
                  emptyIcon={<FileText className="w-12 h-12 text-neutral-300 dark:text-neutral-400" />}
                  emptyTitle="No invoices found"
                  columns={[
                    {
                      key: 'select',
                      header: '', minWidth: 48,
                      width: '3rem',
                      render: (_, invoice) => {
                        const isSelectable = COLLECTABLE.includes(invoice.status);
                        return (
                          <Checkbox
                            size="sm"
                            aria-label={`Select invoice ${invoice.invoiceNumber}`}
                            checked={selectedIds.has(invoice.id)}
                            onChange={() => handleSelectInvoice(invoice.id)}
                            disabled={!isSelectable}
                          />
                        );
                      },
                    },
                    {
                      key: 'invoiceNumber',
                      header: 'Invoice', minWidth: 105,
                      render: (_, invoice) => (
                        <div className="flex flex-col">
                          <span className="text-body-sm font-mono font-medium text-primary-900 dark:text-neutral-50">{invoice.invoiceNumber}</span>
                          <span className="caption">{formatDate(invoice.invoiceDate)}</span>
                        </div>
                      ),
                    },
                    {
                      key: 'customerName',
                      header: 'Customer', minWidth: 220,
                      render: (_, invoice) => (
                        <div className="flex flex-col">
                          <span className="body-strong">{invoice.customerName}</span>
                          <span className="text-caption text-neutral-500 font-mono dark:text-neutral-400">{invoice.customerVaNumber || '-'}</span>
                          {invoice.owningEntityCode && (
                            <span className="text-caption text-primary-600 dark:text-primary-200">Entity: {invoice.owningEntityCode}</span>
                          )}
                        </div>
                      ),
                    },
                    {
                      key: 'invoiceAmount',
                      header: 'Amount', minWidth: 115,
                      align: 'right',
                      render: (_, invoice) => (
                        <div className="flex flex-col items-end">
                          <span className="body-strong font-semibold">
                            {formatCurrency(invoice.invoiceAmount, invoice.currencyCode)}
                          </span>
                          {invoice.paidAmount > 0 && (
                            <span className="caption-success">
                              Paid: {formatCurrency(invoice.paidAmount, invoice.currencyCode)}
                            </span>
                          )}
                          <span className="caption">
                            Due: {formatCurrency(invoice.outstandingAmount, invoice.currencyCode)}
                          </span>
                        </div>
                      ),
                    },
                    {
                      key: 'dueDate',
                      header: 'Due Date', minWidth: 100, dropOrder: 1,
                      render: (_, invoice) => {
                        const late = isOverdue(invoice);
                        return (
                          <div className="flex flex-col gap-1">
                            <span className="text-body-sm">{formatDate(invoice.dueDate)}</span>
                            {late && (
                              <span className="text-caption text-error-600 font-medium dark:text-error-300">{daysOverdue(invoice.dueDate)} days overdue</span>
                            )}
                          </div>
                        );
                      },
                    },
                    {
                      key: 'status',
                      header: 'Status', minWidth: 210,
                      render: (_, invoice) => (
                        <div className="flex flex-col gap-1">
                          {getStatusBadge(invoice.status)}
                          <div className="flex flex-wrap gap-1 mt-1">
                            {invoice.isIntercompany && (
                              <Badge variant="info" size="sm" className="text-caption">
                                <Building2 className="w-3 h-3 mr-1" />
                                IC: {invoice.intercompanyEntityCode}
                              </Badge>
                            )}
                            {invoice.nettingEligible && invoice.nettingStatus !== 'NOT_INCLUDED' && (
                              <Badge variant="info" size="sm" className="text-caption">
                                <GitMerge className="w-3 h-3 mr-1" />
                                {invoice.nettingStatus}
                              </Badge>
                            )}
                          </div>
                        </div>
                      ),
                    },
                    {
                      key: 'assignedViban',
                      header: 'VIBAN', minWidth: 145, dropOrder: 2,
                      render: (_, invoice) => (
                        <div className="flex flex-col gap-1">
                          {invoice.assignedViban ? (
                            <div className="flex items-center gap-1">
                              <span className="text-caption font-mono text-neutral-600 dark:text-neutral-300">{invoice.assignedViban.slice(0, 10)}...</span>
                              <button onClick={() => navigator.clipboard.writeText(invoice.assignedViban!)} className="text-neutral-400 hover:text-neutral-600 transition-colors dark:text-neutral-400 dark:hover:text-neutral-300">
                                <Copy className="w-3 h-3" />
                              </button>
                            </div>
                          ) : (
                            <span className="caption">No VIBAN</span>
                          )}
                        </div>
                      ),
                    },
                    {
                      key: 'actions',
                      header: 'Actions', minWidth: 210,
                      render: (_, invoice) => {
                        const isSelectable = COLLECTABLE.includes(invoice.status);
                        return (
                          <div className="flex items-center gap-1">
                            <Button variant="ghost" size="sm" onClick={() => handleViewInvoice(invoice)} title="View Details">
                              <Eye className="w-4 h-4" />
                            </Button>
                            {(invoice.status as string) === 'DRAFT' && (
                              <Button variant="outline" size="sm" onClick={() => handleIssueDraft(invoice)} title="Issue invoice">
                                Issue
                              </Button>
                            )}
                            {!invoice.assignedViban && isSelectable && (
                              <Button
                                variant="outline"
                                size="sm"
                                onClick={() => handleGenerateViban(invoice)}
                                title="Generate VIBAN"
                              >
                                <QrCode className="w-4 h-4" />
                              </Button>
                            )}
                            {invoice.nettingEligible && invoice.nettingStatus === 'NOT_INCLUDED' && (
                              <Button
                                variant="outline"
                                size="sm"
                                onClick={() => handleAddToNetting(invoice)}
                                title="Add to Netting Cycle"
                              >
                                <GitMerge className="w-4 h-4" />
                              </Button>
                            )}
                          </div>
                        );
                      },
                    },
                  ]}
                />
              )}

              {/* Intercompany Tab */}
              {activeTab === 'intercompany' && (
                <DataTable
                  data={intercompanyInvoices}
                  keyExtractor={(invoice) => invoice.id}
                  emptyIcon={<Building2 className="w-12 h-12 text-neutral-300 dark:text-neutral-400" />}
                  emptyTitle="No intercompany invoices found"
                  columns={[
                    {
                      key: 'invoiceNumber',
                      header: 'Invoice',
                      render: (_, invoice) => (
                        <div className="flex flex-col">
                          <span className="text-body-sm font-mono font-medium text-primary-900 dark:text-neutral-50">{invoice.invoiceNumber}</span>
                          <span className="caption">{formatDate(invoice.invoiceDate)}</span>
                        </div>
                      ),
                    },
                    {
                      key: 'owningEntityCode',
                      header: 'From Entity',
                      render: (_, invoice) => (
                        <div className="flex flex-col">
                          <Badge variant="info" size="sm">{invoice.owningEntityCode || 'Unknown'}</Badge>
                          <span className="caption mt-1">{invoice.owningEntityName || ''}</span>
                        </div>
                      ),
                    },
                    {
                      key: 'intercompanyEntityCode',
                      header: 'To Entity (IC)',
                      render: (_, invoice) => (
                        <div className="flex flex-col">
                          <Badge variant="info" size="sm">
                            <Building2 className="w-3 h-3 mr-1" />
                            {invoice.intercompanyEntityCode || 'Unknown'}
                          </Badge>
                          <span className="caption mt-1">{invoice.intercompanyEntityName || ''}</span>
                        </div>
                      ),
                    },
                    {
                      key: 'outstandingAmount',
                      header: 'Amount',
                      align: 'right',
                      render: (_, invoice) => (
                        <span className="body-strong font-semibold">{formatCurrency(invoice.outstandingAmount, invoice.currencyCode)}</span>
                      ),
                    },
                    {
                      key: 'status',
                      header: 'Status',
                      align: 'center',
                      render: (_, invoice) => getStatusBadge(invoice.status),
                    },
                    {
                      key: 'nettingStatus',
                      header: 'Netting',
                      align: 'center',
                      render: (_, invoice) => getNettingStatusBadge(invoice.nettingStatus),
                    },
                    {
                      key: 'actions',
                      header: 'Actions',
                      render: (_, invoice) => (
                        <div className="flex items-center gap-1">
                          <Button variant="ghost" size="sm" onClick={() => handleViewInvoice(invoice)}>
                            <Eye className="w-4 h-4" />
                          </Button>
                          {invoice.nettingEligible && invoice.nettingStatus === 'NOT_INCLUDED' && (
                            <Button variant="outline" size="sm" onClick={() => handleAddToNetting(invoice)} title="Add to Netting Cycle">
                              <GitMerge className="w-4 h-4" />
                            </Button>
                          )}
                        </div>
                      ),
                    },
                  ]}
                />
              )}

              {/* Pending Netting Tab */}
              {activeTab === 'pending-netting' && (
                <DataTable
                  data={pendingNettingInvoices}
                  keyExtractor={(invoice) => invoice.id}
                  emptyIcon={<GitMerge className="w-12 h-12 text-neutral-300 dark:text-neutral-400" />}
                  emptyTitle="No receivables pending netting"
                  columns={[
                    {
                      key: 'invoiceNumber',
                      header: 'Invoice',
                      render: (_, invoice) => (
                        <span className="text-body-sm font-mono font-medium text-primary-900 dark:text-neutral-50">{invoice.invoiceNumber}</span>
                      ),
                    },
                    {
                      key: 'customerName',
                      header: 'Customer',
                      render: (_, invoice) => (
                        <span className="body-strong">{invoice.customerName}</span>
                      ),
                    },
                    {
                      key: 'outstandingAmount',
                      header: 'Amount',
                      align: 'right',
                      render: (_, invoice) => (
                        <span className="body-strong font-semibold">{formatCurrency(invoice.outstandingAmount, invoice.currencyCode)}</span>
                      ),
                    },
                    {
                      key: 'nettingStatus',
                      header: 'Netting Status',
                      align: 'center',
                      render: (_, invoice) => getNettingStatusBadge(invoice.nettingStatus),
                    },
                    {
                      key: 'nettingCycleRef',
                      header: 'Cycle',
                      render: (_, invoice) => (
                        <span className="text-body-sm font-mono">{invoice.nettingCycleRef || '-'}</span>
                      ),
                    },
                    {
                      key: 'actions',
                      header: 'Actions',
                      render: (_, invoice) => (
                        <div className="flex items-center gap-1">
                          <Button variant="ghost" size="sm" onClick={() => handleViewInvoice(invoice)}>
                            <Eye className="w-4 h-4" />
                          </Button>
                          <Button
                            variant="outline"
                            size="sm"
                            onClick={() => handleRemoveFromNetting(invoice)}
                            title="Remove from Netting"
                          >
                            <X className="w-4 h-4" />
                          </Button>
                        </div>
                      ),
                    },
                  ]}
                />
              )}

              {/* VIBANs Tab */}
              {activeTab === 'vibans' && (
                <DataTable
                  data={vibans}
                  keyExtractor={(viban) => viban.id}
                  emptyIcon={<QrCode className="w-12 h-12 text-neutral-300 dark:text-neutral-400" />}
                  emptyTitle="No VIBANs generated"
                  columns={[
                    {
                      key: 'virtualIban',
                      header: 'VIBAN',
                      render: (_, viban) => (
                        <div className="flex items-center gap-2">
                          <span className="text-body-sm font-mono text-primary-900 dark:text-neutral-50">{viban.virtualIban}</span>
                          <button
                            onClick={() => navigator.clipboard.writeText(viban.virtualIban)}
                            className="text-neutral-400 hover:text-neutral-600 transition-colors dark:text-neutral-400 dark:hover:text-neutral-300"
                          >
                            <Copy className="w-4 h-4" />
                          </button>
                        </div>
                      ),
                    },
                    {
                      key: 'reference',
                      header: 'Reference',
                      render: (_, viban) => <span className="text-body-sm font-mono">{viban.reference}</span>,
                    },
                    {
                      key: 'customerName',
                      header: 'Customer',
                      render: (_, viban) => (
                        <span className="body-strong">{viban.customerName || '—'}</span>
                      ),
                    },
                    {
                      key: 'expectedAmount',
                      header: 'Expected',
                      align: 'right',
                      render: (_, viban) => <span className="text-body-sm">{formatCurrency(viban.expectedAmount, viban.currency)}</span>,
                    },
                    {
                      key: 'receivedAmount',
                      header: 'Received',
                      align: 'right',
                      render: (_, viban) => (
                        <span className="text-body-sm font-semibold text-success-600 dark:text-success-300">
                          {formatCurrency(viban.receivedAmount, viban.currency)}
                        </span>
                      ),
                    },
                    {
                      key: 'status',
                      header: 'Status',
                      align: 'center',
                      render: (_, viban) => (
                        <Badge
                          variant={viban.status === 'PAID' ? 'success' : viban.status === 'ACTIVE' ? 'info' : 'neutral'}
                          size="sm"
                        >
                          {viban.status}
                        </Badge>
                      ),
                    },
                    {
                      key: 'expiresAt',
                      header: 'Expires',
                      render: (_, viban) => (
                        <span className="text-body-sm">
                          {viban.expiresAt ? formatDate(viban.expiresAt) : <span className="text-neutral-400">No expiry</span>}
                        </span>
                      ),
                    },
                  ]}
                />
              )}

              {activeTab === 'collection-runs' && <CollectionRunsTab corporateId={selectedCorporateId} />}
              {activeTab === 'mandates' && <MandatesTab corporateId={selectedCorporateId} />}
              {activeTab === 'outbox' && <OutboxTab corporateId={selectedCorporateId} />}

              {/* COBO History Tab: read-only, by subsidiary and currency; apply a bank credit for money outside the VIBAN */}
              {activeTab === 'cobo-history' && (
                <DataTable
                  data={coboHistoryInvoices}
                  keyExtractor={(invoice) => invoice.id}
                  emptyIcon={<ArrowDownLeft className="w-12 h-12 text-neutral-300 dark:text-neutral-400" />}
                  emptyTitle="No COBO collections found"
                  emptyDescription="Enrol a subsidiary under In-House Bank, Collections on behalf; its new invoices are collected by treasury"
                  columns={[
                    {
                      key: 'invoiceNumber',
                      header: 'Invoice',
                      render: (_, invoice) => (
                        <div className="flex flex-col">
                          <span className="text-body-sm font-mono font-medium text-primary-900 dark:text-neutral-50">{invoice.invoiceNumber}</span>
                          <span className="caption">{invoice.coboTransactionRef || '-'}</span>
                        </div>
                      ),
                    },
                    {
                      key: 'customerName',
                      header: 'Customer',
                      render: (_, invoice) => (
                        <span className="body-strong">{invoice.customerName}</span>
                      ),
                    },
                    {
                      key: 'invoiceAmount',
                      header: 'Amount',
                      align: 'right',
                      render: (_, invoice) => (
                        <span className="body-strong font-semibold">{formatCurrency(invoice.invoiceAmount, invoice.currencyCode)}</span>
                      ),
                    },
                    {
                      key: 'coboCollectorEntityCode',
                      header: 'Collector',
                      render: (_, invoice) => (
                        <div className="flex flex-col">
                          <span className="text-body-sm">{invoice.coboCollectorEntityCode || '-'}</span>
                          <span className="caption">{invoice.coboCollectorEntityName || ''}</span>
                        </div>
                      ),
                    },
                    {
                      key: 'owningEntityCode',
                      header: 'On Behalf',
                      render: (_, invoice) => (
                        <div className="flex flex-col">
                          <span className="text-body-sm">{invoice.owningEntityCode || '-'}</span>
                          <span className="caption">{invoice.owningEntityName || ''}</span>
                        </div>
                      ),
                    },
                    {
                      key: 'currencyCode',
                      header: 'Currency',
                      minWidth: 90,
                    },
                    {
                      key: 'coboRequestStatus',
                      header: 'Status',
                      align: 'center',
                      render: (_, invoice) => invoice.coboRequestStatus === 'COLLECTED' || (invoice.isCobo && invoice.outstandingAmount <= 0)
                        ? <Badge variant="success" size="sm">Collected</Badge>
                        : invoice.coboRequestStatus === 'APPROVED'
                          ? <Badge variant="info" size="sm">{invoice.paidAmount > 0 ? 'Part collected' : 'Outstanding'}</Badge>
                          : getCoboStatusBadge(invoice.coboRequestStatus),
                    },
                    {
                      key: 'outstandingAmount',
                      header: 'Outstanding',
                      align: 'right',
                      render: (_, invoice) => formatCurrency(invoice.outstandingAmount, invoice.currencyCode),
                    },
                    {
                      key: 'actions',
                      header: 'Actions',
                      render: (_, invoice) => (
                        <div className="flex items-center gap-1">
                          {invoice.coboRequestStatus === 'APPROVED' && invoice.outstandingAmount > 0 && (
                            <Button
                              variant="outline"
                              size="sm"
                              onClick={() => setCreditInvoice(invoice)}
                              leftIcon={<Play className="w-4 h-4" />}
                              title="Money arrived outside the invoice's VIBAN: apply that bank credit"
                            >
                              Apply credit
                            </Button>
                          )}
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => handleViewInvoice(invoice)}
                            title="View Details"
                          >
                            <Eye className="w-4 h-4" />
                          </Button>
                        </div>
                      ),
                    },
                  ]}
                />
              )}
            </>
          )}
        </div>
      </Card>

      <CollectionPlanModal
        isOpen={planIds !== null}
        onClose={() => setPlanIds(null)}
        corporateId={selectedCorporateId}
        receivableIds={planIds ?? []}
        onSent={() => { setSelectedIds(new Set()); fetchData(); }}
      />

      <ApplyCreditModal
        invoice={creditInvoice}
        onClose={() => setCreditInvoice(null)}
        onConfirm={handleApplyCredit}
        loading={creditLoading}
      />

      {/* Netting Cycle Picker Modal */}
      <NettingCyclePickerModal
        isOpen={showNettingPicker}
        onClose={() => { setShowNettingPicker(false); setNettingInvoice(null); }}
        items={nettingInvoice ? [{
          id: nettingInvoice.id,
          label: nettingInvoice.invoiceNumber,
          amount: nettingInvoice.outstandingAmount,
          currencyCode: nettingInvoice.currencyCode,
        }] : []}
        onConfirm={handleConfirmAddToNetting}
      />

      {/* Invoice Detail Modal */}
      <Modal isOpen={!!viewInvoice} onClose={() => setViewInvoice(null)} title={viewInvoice?.invoiceNumber || 'Invoice'} size="md">
        {viewInvoice && (
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-4 text-body-sm">
              <div>
                <p className="text-neutral-500 dark:text-neutral-400">Customer</p>
                <p className="font-medium">{viewInvoice.customerName}</p>
              </div>
              <div>
                <p className="text-neutral-500 dark:text-neutral-400">Status</p>
                <p className="font-medium">{viewInvoice.status}</p>
              </div>
              <div>
                <p className="text-neutral-500 dark:text-neutral-400">Invoice Date</p>
                <p className="font-medium">{formatDate(viewInvoice.invoiceDate)}</p>
              </div>
              <div>
                <p className="text-neutral-500 dark:text-neutral-400">Due Date</p>
                <p className="font-medium">{formatDate(viewInvoice.dueDate)}</p>
              </div>
              <div>
                <p className="text-neutral-500 dark:text-neutral-400">Invoice Amount</p>
                <p className="font-medium">{formatCurrency(viewInvoice.invoiceAmount, viewInvoice.currencyCode)}</p>
              </div>
              <div>
                <p className="text-neutral-500 dark:text-neutral-400">Paid</p>
                <p className="font-medium">{formatCurrency(viewInvoice.paidAmount, viewInvoice.currencyCode)}</p>
              </div>
              <div>
                <p className="text-neutral-500 dark:text-neutral-400">Outstanding</p>
                <p className="font-medium">{formatCurrency(viewInvoice.outstandingAmount, viewInvoice.currencyCode)}</p>
              </div>
              <div>
                <p className="text-neutral-500 dark:text-neutral-400">Owning Entity</p>
                <p className="font-medium">{viewInvoice.owningEntityName || viewInvoice.owningEntityCode || '—'}</p>
              </div>
              {viewInvoice.assignedViban && (
                <div>
                  <p className="text-neutral-500 dark:text-neutral-400">VIBAN</p>
                  <p className="font-mono font-medium">{viewInvoice.assignedViban}</p>
                </div>
              )}
              {viewInvoice.isCobo && (
                <div>
                  <p className="text-neutral-500 dark:text-neutral-400">COBO Status</p>
                  <p className="font-medium">{viewInvoice.coboRequestStatus}</p>
                </div>
              )}
              {viewInvoice.nettingEligible && (
                <div>
                  <p className="text-neutral-500 dark:text-neutral-400">Netting Status</p>
                  <p className="font-medium">{viewInvoice.nettingStatus}</p>
                </div>
              )}
            </div>
            {viewInvoice.description && (
              <div>
                <p className="body-sm">Description</p>
                <p className="text-body-sm">{viewInvoice.description}</p>
              </div>
            )}
          </div>
        )}
      </Modal>
    </Page>
  );
};

export default EnhancedReceivablesPage;