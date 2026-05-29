import { Page } from '../components/layout/Page';
import { ScopeSelector } from '../components/layout/ScopeSelector';
/**
 * EnhancedReceivablesPage.tsx
 * 
 * AR Automation with COBO (Collect On Behalf Of), VIBAN, and IHB Integration
 * 
 * PHASE 3 COMPLETE VERSION:
 * - Modal-based COBO request (matches Payables POBO pattern)
 * - Intercompany Tab with visual badges
 * - Pending Netting Tab
 * - COBO History Tab with Treasury Approval actions (Approve/Reject/Execute)
 * - Full Treasury workflow support
 */

import React, { useState, useEffect, useCallback, useMemo } from 'react';
import {
  Search, Download, RefreshCw, AlertCircle,
  Clock, ArrowDownLeft, FileText, Eye, X,
  AlertTriangle, Copy, Plus, QrCode, Loader2, 
  Building2, Landmark, Calculator, Edit2, GitMerge, Layers,
  ThumbsUp, ThumbsDown, Play,
} from 'lucide-react';
import { Card, Button, Badge, Input, Select, StatusIconBadge } from '../components/ui';
import { Modal } from '../components/ui/enhanced';
import { formatCurrency, formatDate, cn } from '../utils';
import { 
  receivablesApiPhase3,
  nettingApi,
  corporatesApi,
  programsApi,
  legalEntityApi,
  apiClient,
  type ApiResponse,
  type CollectionRoute,
  type CoboRequestStatus,
  type NettingStatusReceivable,
  type ReceivableStatusPhase3,
  type Corporate,
  type Program,
  type LegalEntity,
} from '../services/api';
import { useNavigation } from '../App';

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
  treasuryVaId?: string;
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

interface CoboPreview {
  collectingEntity: { id: string; name: string; code: string };
  behalfEntity: { id: string; name: string; code: string };
  collectionAmount: number;
  currencyCode: string;
  charges: { code: string; name: string; amount: number }[];
  totalCharges: number;
  netAmount: number;
  ihbDepositPreview: {
    depositAmount: number;
    interestRate: number;
    estimatedMonthlyInterest: number;
  };
}

// ============================================================================
// MOCK API - vibanApi not yet available
// ============================================================================

const mockVibanApi = {
  getAll: async () => ({
    success: true,
    data: [
      { id: '1', virtualIban: 'AE07NBAD00010010123456', reference: 'INV-2024-001', customerName: 'Acme Corp', expectedAmount: 50000, receivedAmount: 0, currency: 'AED', status: 'ACTIVE' as const, createdAt: new Date().toISOString(), expiresAt: new Date(Date.now() + 30*24*60*60*1000).toISOString(), purpose: 'Invoice Payment', invoiceId: '1' },
      { id: '2', virtualIban: 'AE15NBAD00010010789012', reference: 'INV-2024-002', customerName: 'Global Ltd', expectedAmount: 75000, receivedAmount: 75000, currency: 'AED', status: 'PAID' as const, createdAt: new Date(Date.now() - 7*24*60*60*1000).toISOString(), expiresAt: new Date(Date.now() + 23*24*60*60*1000).toISOString(), purpose: 'Project Payment', invoiceId: '2' },
    ]
  }),
  generate: async (invoiceId: string) => ({
    success: true,
    data: { virtualIban: 'AE' + Math.random().toString().slice(2, 24), invoiceId, status: 'ACTIVE' }
  })
};

// ============================================================================
// STATUS CONFIGURATIONS
// ============================================================================

const statusConfig: Record<string, { variant: 'success' | 'warning' | 'error' | 'neutral' | 'info'; label: string }> = {
  OPEN: { variant: 'info', label: 'Open' },
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
const StatsCard: React.FC<{
  title: string;
  value: string | number;
  subtitle?: string;
  icon: React.ReactNode;
  trend?: { value: number; positive: boolean };
  color?: 'emerald' | 'amber' | 'rose' | 'blue' | 'purple' | 'indigo';
  delay?: number;
}> = ({ title, value, subtitle, icon, trend, color = 'blue', delay = 0 }) => {
  const colorClasses = {
    emerald: 'bg-success-100 text-success-600 dark:bg-success-500/20 dark:text-success-300',
    amber: 'bg-warning-100 text-warning-600 dark:bg-warning-500/20 dark:text-warning-300',
    rose: 'bg-error-100 text-error-600 dark:bg-error-500/20 dark:text-error-300',
    blue: 'bg-info-100 text-info-600 dark:bg-info-500/20 dark:text-info-300',
    purple: 'bg-accent-100 text-accent-600 dark:bg-accent-500/20 dark:text-accent-300',
    indigo: 'bg-primary-100 text-primary-600 dark:bg-primary-700 dark:text-primary-200',
  };

  return (
    <Card hover className="animate-fade-in" style={{ animationDelay: `${delay}s` }}>
      <div className="flex items-start justify-between">
        <div className="flex-1">
          <p className="label">{title}</p>
          <p className="stat-value-sm mt-1">{value}</p>
          {subtitle && <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">{subtitle}</p>}
          {trend && (
            <div className={cn('flex items-center gap-1 text-xs mt-1', trend.positive ? 'text-success-600 dark:text-success-300' : 'text-error-600 dark:text-error-300')}>
              <span>{trend.positive ? '↑' : '↓'} {Math.abs(trend.value)}%</span>
            </div>
          )}
        </div>
        <div className={cn('w-10 h-10 rounded-xl flex items-center justify-center', colorClasses[color])}>{icon}</div>
      </div>
    </Card>
  );
};

// Invoice Row Component
interface InvoiceRowProps {
  invoice: InvoicePhase3;
  isSelected: boolean;
  onSelect: (id: string) => void;
  onView: () => void;
  onEdit: () => void;
  onRequestCobo: () => void;
  onAddToNetting: () => void;
  onGenerateViban: () => void;
}

const InvoiceRow: React.FC<InvoiceRowProps> = ({
  invoice,
  isSelected,
  onSelect,
  onView,
  onEdit,
  onRequestCobo,
  onAddToNetting,
  onGenerateViban,
}) => {
  const isOverdue = new Date(invoice.dueDate) < new Date() && invoice.status === 'OPEN';
  const daysOverdue = isOverdue ? Math.floor((Date.now() - new Date(invoice.dueDate).getTime()) / (1000 * 60 * 60 * 24)) : 0;

  const canRequestCobo = !invoice.isCobo &&
    invoice.coboRequestStatus === 'NOT_REQUESTED' &&
    ['OPEN', 'PARTIAL'].includes(invoice.status);

  const isSelectable = ['OPEN', 'PARTIAL'].includes(invoice.status);

  return (
    <tr className={cn('data-table-row group', isOverdue && 'bg-error-50/30 dark:bg-error-500/10')}>
      <td className="data-table-cell">
        <input
          type="checkbox"
          checked={isSelected}
          onChange={() => onSelect(invoice.id)}
          disabled={!isSelectable}
          className="w-4 h-4 rounded border-neutral-300 text-primary-600 focus:ring-primary-500 disabled:opacity-50 dark:border-primary-700 dark:text-primary-200"
        />
      </td>
      <td className="data-table-cell">
        <div className="flex flex-col">
          <span className="text-sm font-mono font-medium text-primary-900 dark:text-neutral-50">{invoice.invoiceNumber}</span>
          <span className="text-xs text-neutral-500 dark:text-neutral-400">{formatDate(invoice.invoiceDate)}</span>
        </div>
      </td>
      <td className="data-table-cell">
        <div className="flex flex-col">
          <span className="text-sm font-medium text-primary-900 dark:text-neutral-50">{invoice.customerName}</span>
          <span className="text-xs text-neutral-500 font-mono dark:text-neutral-400">{invoice.customerVaNumber || '-'}</span>
          {invoice.owningEntityCode && (
            <span className="text-xs text-primary-600 dark:text-primary-200">Entity: {invoice.owningEntityCode}</span>
          )}
        </div>
      </td>
      <td className="data-table-cell text-right">
        <div className="flex flex-col items-end">
          <span className="text-sm font-semibold text-primary-900 dark:text-neutral-50">
            {formatCurrency(invoice.invoiceAmount, invoice.currencyCode)}
          </span>
          {invoice.paidAmount > 0 && (
            <span className="text-xs text-success-600 dark:text-success-300">
              Paid: {formatCurrency(invoice.paidAmount, invoice.currencyCode)}
            </span>
          )}
          <span className="text-xs text-neutral-500 dark:text-neutral-400">
            Due: {formatCurrency(invoice.outstandingAmount, invoice.currencyCode)}
          </span>
        </div>
      </td>
      <td className="data-table-cell">
        <div className="flex flex-col gap-1">
          <span className="text-sm">{formatDate(invoice.dueDate)}</span>
          {isOverdue && (
            <span className="text-xs text-error-600 font-medium dark:text-error-300">{daysOverdue} days overdue</span>
          )}
        </div>
      </td>
      <td className="data-table-cell">
        <div className="flex flex-col gap-1">
          {getStatusBadge(invoice.status)}
          <div className="flex flex-wrap gap-1 mt-1">
            {invoice.isIntercompany && (
              <Badge variant="info" size="sm" className="text-[10px]">
                <Building2 className="w-3 h-3 mr-1" />
                IC: {invoice.intercompanyEntityCode}
              </Badge>
            )}
            {invoice.nettingEligible && invoice.nettingStatus !== 'NOT_INCLUDED' && (
              <Badge variant="info" size="sm" className="text-[10px]">
                <GitMerge className="w-3 h-3 mr-1" />
                {invoice.nettingStatus}
              </Badge>
            )}
          </div>
        </div>
      </td>
      <td className="data-table-cell">
        <div className="flex flex-col gap-1">
          {invoice.assignedViban ? (
            <div className="flex items-center gap-1">
              <span className="text-xs font-mono text-neutral-600 dark:text-neutral-300">{invoice.assignedViban.slice(0, 10)}...</span>
              <button onClick={() => navigator.clipboard.writeText(invoice.assignedViban!)} className="text-neutral-400 hover:text-neutral-600 transition-colors dark:text-neutral-500 dark:hover:text-neutral-300">
                <Copy className="w-3 h-3" />
              </button>
            </div>
          ) : (
            <span className="text-xs text-neutral-400 dark:text-neutral-500">No VIBAN</span>
          )}
        </div>
      </td>
      <td className="data-table-cell">
        <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
          <Button variant="ghost" size="sm" onClick={onView} title="View Details">
            <Eye className="w-4 h-4" />
          </Button>
          <Button variant="ghost" size="sm" onClick={onEdit} title="Edit Invoice">
            <Edit2 className="w-4 h-4" />
          </Button>
          {canRequestCobo && (
            <Button
              variant="outline"
              size="sm"
              leftIcon={<ArrowDownLeft className="w-4 h-4" />}
              onClick={onRequestCobo}
              title="Request COBO"
            >
              COBO
            </Button>
          )}
          {invoice.nettingEligible && invoice.nettingStatus === 'NOT_INCLUDED' && (
            <Button
              variant="outline"
              size="sm"
              onClick={onAddToNetting}
              title="Add to Netting Cycle"
            >
              <GitMerge className="w-4 h-4" />
            </Button>
          )}
          {!invoice.assignedViban && isSelectable && (
            <Button
              variant="outline"
              size="sm"
              onClick={onGenerateViban}
              title="Generate VIBAN"
            >
              <QrCode className="w-4 h-4" />
            </Button>
          )}
        </div>
      </td>
    </tr>
  );
};

// COBO Request Modal Component
interface CoboModalProps {
  isOpen: boolean;
  onClose: () => void;
  receivables: InvoicePhase3[];
  entities: LegalEntity[];
  onSubmit: (collectingEntityId: string, receivableIds: string[]) => Promise<void>;
}

const CoboRequestModal: React.FC<CoboModalProps> = ({
  isOpen,
  onClose,
  receivables,
  entities,
  onSubmit,
}) => {
  const [collectingEntityId, setCollectingEntityId] = useState('');
  const [preview, setPreview] = useState<CoboPreview | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [generateViban, setGenerateViban] = useState(false);
  const [submitLoading, setSubmitLoading] = useState(false);
  
  // Filter for treasury/holding entities (COBO collectors)
  const treasuryEntities = useMemo(() => 
    entities.filter(e => 
      (e as any).isTreasuryCenter || 
      (e as any).entityType === 'HOLDING' || 
      (e as any).entityType === 'TREASURY'
    ), 
    [entities]
  );

  const totalAmount = useMemo(() => 
    receivables.reduce((sum, r) => sum + r.outstandingAmount, 0),
    [receivables]
  );

  const currency = receivables[0]?.currencyCode || 'AED';

  // Load preview when entity selected
  useEffect(() => {
    const loadPreview = async () => {
      if (!collectingEntityId || receivables.length === 0) {
        setPreview(null);
        return;
      }
      
      setPreviewLoading(true);
      try {
        // Calculate preview locally (0.15% COBO fee)
        const selectedEntity = entities.find(e => e.id === collectingEntityId);
        const behalfEntity = entities.find(e => e.id === receivables[0].owningEntityId);
        const fees = totalAmount * 0.0015;
        
        setPreview({
          collectingEntity: { 
            id: collectingEntityId, 
            code: selectedEntity?.entityCode || '', 
            name: selectedEntity?.entityName || '' 
          },
          behalfEntity: { 
            id: receivables[0].owningEntityId || '', 
            code: behalfEntity?.entityCode || receivables[0].owningEntityCode || '', 
            name: behalfEntity?.entityName || receivables[0].owningEntityName || '' 
          },
          collectionAmount: totalAmount,
          currencyCode: currency,
          charges: [{ code: 'COBO-FEE', name: 'COBO Service Fee (0.15%)', amount: fees }],
          totalCharges: fees,
          netAmount: totalAmount - fees,
          ihbDepositPreview: {
            depositAmount: totalAmount - fees,
            interestRate: 3.5,
            estimatedMonthlyInterest: (totalAmount - fees) * 0.035 / 12
          }
        });
      } catch (err) {
        console.error('Failed to load preview:', err);
      } finally {
        setPreviewLoading(false);
      }
    };

    loadPreview();
  }, [collectingEntityId, receivables, entities, totalAmount, currency]);

  const handleSubmit = async () => {
    if (!collectingEntityId || receivables.length === 0) return;
    
    setSubmitLoading(true);
    try {
      await onSubmit(collectingEntityId, receivables.map(r => r.id));
      onClose();
    } catch (err) {
      console.error('COBO submission failed:', err);
    } finally {
      setSubmitLoading(false);
    }
  };

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="Request COBO Collection" size="lg">
      <div className="space-y-6">
        {/* Selected Receivables Summary */}
        <div className="bg-neutral-50 rounded-lg p-4 dark:bg-primary-950">
          <h4 className="text-sm font-semibold text-neutral-700 mb-3 dark:text-neutral-200">Selected Receivables ({receivables.length})</h4>
          <div className="space-y-2 max-h-40 overflow-y-auto">
            {receivables.map(r => (
              <div key={r.id} className="flex justify-between items-center text-sm">
                <div>
                  <span className="font-mono font-medium">{r.invoiceNumber}</span>
                  <span className="text-neutral-500 ml-2 dark:text-neutral-400">{r.customerName}</span>
                </div>
                <span className="font-semibold">{formatCurrency(r.outstandingAmount, r.currencyCode)}</span>
              </div>
            ))}
          </div>
          <div className="border-t border-neutral-200 mt-3 pt-3 flex justify-between items-center dark:border-primary-800">
            <span className="font-semibold text-neutral-700 dark:text-neutral-200">Total Amount</span>
            <span className="text-lg font-bold text-indigo-600 dark:text-indigo-300">{formatCurrency(totalAmount, currency)}</span>
          </div>
        </div>

        {/* Treasury Entity Selector */}
        <div>
          <label className="field-label block mb-2">
            <Landmark className="w-4 h-4 inline mr-2" />
            Collecting Treasury Entity
          </label>
          <select
            value={collectingEntityId}
            onChange={(e) => setCollectingEntityId(e.target.value)}
            className="w-full px-3 py-2 border border-neutral-300 rounded-lg focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 dark:border-primary-700 dark:bg-primary-900"
          >
            <option value="">Select Treasury Entity...</option>
            {treasuryEntities.length > 0 ? (
              treasuryEntities.map(entity => (
                <option key={entity.id} value={entity.id}>
                  {entity.entityCode} - {entity.entityName}
                </option>
              ))
            ) : (
              entities.slice(0, 5).map(entity => (
                <option key={entity.id} value={entity.id}>
                  {entity.entityCode} - {entity.entityName}
                </option>
              ))
            )}
          </select>
          <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">
            Treasury will collect on behalf of {receivables[0]?.owningEntityCode || 'subsidiary'}
          </p>
        </div>

        {/* VIBAN Option */}
        <label className="flex items-center gap-2 cursor-pointer">
          <input
            type="checkbox"
            checked={generateViban}
            onChange={(e) => setGenerateViban(e.target.checked)}
            className="w-4 h-4 rounded border-neutral-300 text-indigo-600 focus:ring-indigo-500 dark:border-primary-700 dark:text-indigo-300"
          />
          <span className="text-sm text-neutral-700 dark:text-neutral-200">Generate VIBAN for collection</span>
        </label>

        {/* Preview Card */}
        {previewLoading ? (
          <div className="flex items-center justify-center py-8">
            <Loader2 className="w-6 h-6 animate-spin text-indigo-600 dark:text-indigo-300" />
            <span className="ml-2 text-neutral-500 dark:text-neutral-400">Loading preview...</span>
          </div>
        ) : preview && (
          <Card className="p-4 bg-gradient-to-br from-indigo-50 to-info-50 border-indigo-200 dark:border-indigo-500/30 dark:from-indigo-500/15 dark:to-info-500/15">
            <h4 className="text-sm font-semibold text-indigo-800 mb-3 flex items-center gap-2 dark:text-indigo-300">
              <Calculator className="w-4 h-4" />
              COBO Preview
            </h4>
            
            <div className="space-y-3">
              <div className="flex justify-between text-sm">
                <span className="text-neutral-600 dark:text-neutral-300">Collection Amount</span>
                <span className="font-semibold">{formatCurrency(preview.collectionAmount, preview.currencyCode)}</span>
              </div>
              
              {preview.charges.map((charge, idx) => (
                <div key={idx} className="flex justify-between text-sm">
                  <span className="text-neutral-600 dark:text-neutral-300">{charge.name}</span>
                  <span className="text-rose-600 dark:text-rose-300">-{formatCurrency(charge.amount, preview.currencyCode)}</span>
                </div>
              ))}
              
              <div className="border-t border-indigo-200 pt-2 flex justify-between dark:border-indigo-500/30">
                <span className="font-semibold text-neutral-700 dark:text-neutral-200">Net to Subsidiary</span>
                <span className="text-lg font-bold text-emerald-600 dark:text-emerald-300">{formatCurrency(preview.netAmount, preview.currencyCode)}</span>
              </div>
              
              {preview.ihbDepositPreview && (
                <div className="mt-3 p-3 bg-white/50 rounded-lg dark:bg-primary-900/50">
                  <p className="text-xs text-neutral-600 dark:text-neutral-300">
                    <strong>IHB Deposit:</strong> {formatCurrency(preview.ihbDepositPreview.depositAmount, preview.currencyCode)} 
                    @ {preview.ihbDepositPreview.interestRate}% p.a.
                  </p>
                  <p className="text-xs text-emerald-600 mt-1 dark:text-emerald-300">
                    Est. Monthly Interest: {formatCurrency(preview.ihbDepositPreview.estimatedMonthlyInterest, preview.currencyCode)}
                  </p>
                </div>
              )}
            </div>
          </Card>
        )}

        {/* Actions */}
        <div className="flex justify-end gap-3 pt-4 border-t">
          <Button variant="outline" onClick={onClose} disabled={submitLoading}>
            Cancel
          </Button>
          <Button 
            variant="primary" 
            onClick={handleSubmit}
            disabled={!collectingEntityId || receivables.length === 0 || submitLoading}
            leftIcon={submitLoading ? <Loader2 className="w-4 h-4 animate-spin" /> : <ArrowDownLeft className="w-4 h-4" />}
          >
            {submitLoading ? 'Submitting...' : 'Submit COBO Request'}
          </Button>
        </div>
      </div>
    </Modal>
  );
};

// Treasury Approval Modal Component
interface TreasuryApprovalModalProps {
  isOpen: boolean;
  onClose: () => void;
  invoice: InvoicePhase3 | null;
  action: 'approve' | 'reject' | 'execute' | null;
  onConfirm: (reason?: string) => Promise<void>;
  loading: boolean;
}

const TreasuryApprovalModal: React.FC<TreasuryApprovalModalProps> = ({
  isOpen,
  onClose,
  invoice,
  action,
  onConfirm,
  loading,
}) => {
  const [reason, setReason] = useState('');

  if (!invoice || !action) return null;

  const titles = {
    approve: 'Approve COBO Request',
    reject: 'Reject COBO Request',
    execute: 'Execute COBO Collection',
  };

  const descriptions = {
    approve: `Approve COBO collection for ${invoice.invoiceNumber} (${formatCurrency(invoice.outstandingAmount, invoice.currencyCode)})`,
    reject: `Reject COBO collection request for ${invoice.invoiceNumber}`,
    execute: `Execute collection and credit treasury VA for ${invoice.invoiceNumber}`,
  };

  const handleConfirm = async () => {
    await onConfirm(action === 'reject' ? reason : undefined);
    setReason('');
  };

  return (
    <Modal isOpen={isOpen} onClose={onClose} title={titles[action]} size="md">
      <div className="space-y-4">
        <p className="text-sm text-neutral-600 dark:text-neutral-300">{descriptions[action]}</p>
        
        <div className="bg-neutral-50 rounded-lg p-4 dark:bg-primary-950">
          <div className="grid grid-cols-2 gap-4 text-sm">
            <div>
              <p className="text-neutral-500 dark:text-neutral-400">Invoice</p>
              <p className="font-mono font-medium">{invoice.invoiceNumber}</p>
            </div>
            <div>
              <p className="text-neutral-500 dark:text-neutral-400">Amount</p>
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

        {action === 'reject' && (
          <div>
            <label className="field-label block mb-2">
              Rejection Reason <span className="text-rose-500">*</span>
            </label>
            <textarea
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              className="w-full px-3 py-2 border border-neutral-300 rounded-lg focus:ring-2 focus:ring-indigo-500 dark:border-primary-700 dark:bg-primary-900"
              rows={3}
              placeholder="Enter reason for rejection..."
            />
          </div>
        )}

        <div className="flex justify-end gap-3 pt-4 border-t">
          <Button variant="outline" onClick={onClose} disabled={loading}>
            Cancel
          </Button>
          <Button 
            variant={action === 'reject' ? 'danger' : 'primary'}
            onClick={handleConfirm}
            disabled={loading || (action === 'reject' && !reason.trim())}
            leftIcon={
              loading ? <Loader2 className="w-4 h-4 animate-spin" /> :
              action === 'approve' ? <ThumbsUp className="w-4 h-4" /> :
              action === 'reject' ? <ThumbsDown className="w-4 h-4" /> :
              <Play className="w-4 h-4" />
            }
          >
            {loading ? 'Processing...' : 
              action === 'approve' ? 'Approve' :
              action === 'reject' ? 'Reject' : 'Execute Collection'}
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
  const [programs, setPrograms] = useState<Program[]>([]);
  const [legalEntities, setLegalEntities] = useState<LegalEntity[]>([]);
  const [selectedCorporateId, setSelectedCorporateId] = useState<string>('');
  const [selectedProgramId, setSelectedProgramId] = useState<string>('');
  const [selectedEntityId, setSelectedEntityId] = useState<string>('');
  
  // Data state
  const [invoices, setInvoices] = useState<InvoicePhase3[]>([]);
  const [vibans, setVibans] = useState<VibanRecord[]>([]);
  const [stats, setStats] = useState<ReceivablesStats | null>(null);
  
  // UI state
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [activeTab, setActiveTab] = useState<'invoices' | 'intercompany' | 'pending-netting' | 'vibans' | 'cobo-history'>('invoices');
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [processing, setProcessing] = useState(false);
  
  // COBO Modal state
  const [showCoboModal, setShowCoboModal] = useState(false);
  const [coboReceivables, setCoboReceivables] = useState<InvoicePhase3[]>([]);
  
  // Treasury Approval Modal state
  const [showApprovalModal, setShowApprovalModal] = useState(false);
  const [approvalInvoice, setApprovalInvoice] = useState<InvoicePhase3 | null>(null);
  const [approvalAction, setApprovalAction] = useState<'approve' | 'reject' | 'execute' | null>(null);
  const [approvalLoading, setApprovalLoading] = useState(false);

  // Fetch initial data
  useEffect(() => {
    const loadCorporates = async () => {
      try {
        const response = await corporatesApi.getAll();
        if (response.success && response.data) {
          setCorporates(response.data);
          if (response.data.length > 0) {
            setSelectedCorporateId(response.data[0].id);
          }
        }
      } catch (err) {
        console.error('Failed to load corporates:', err);
      }
    };
    loadCorporates();
  }, []);

  // Load programs when corporate changes
  useEffect(() => {
    if (!selectedCorporateId) return;
    
    const loadPrograms = async () => {
      try {
        const response = await programsApi.getAll({ corporateId: selectedCorporateId });
        if (response.success && response.data) {
          // Handle both array and paginated response formats
          const programsList = Array.isArray(response.data) ? response.data : 
            (response.data as any).content || [];
          setPrograms(programsList);
          if (programsList.length > 0) {
            setSelectedProgramId(programsList[0].id);
          }
        }
      } catch (err) {
        console.error('Failed to load programs:', err);
      }
    };
    loadPrograms();
  }, [selectedCorporateId]);

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
    console.log('fetchData called - selectedCorporateId:', selectedCorporateId, 'selectedEntityId:', selectedEntityId);
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
        }
        console.log('Fetching invoices with headers:', headers);

        const invoicesResponse = await apiClient.get<ApiResponse<any[]>>('/receivables/invoices', {
          headers,
          params: { status: undefined }
        });
        
        if (invoicesResponse.data?.success && invoicesResponse.data?.data) {
          const invoiceData = Array.isArray(invoicesResponse.data.data) ? invoicesResponse.data.data : [];
          console.log('Received invoice data:', invoiceData);
          mappedInvoices = invoiceData.map((inv: any) => ({
            id: inv.id,
            invoiceNumber: inv.invoiceNumber || inv.receivableNumber || inv.externalReference || '',
            customerName: inv.customerName || '',
            customerId: inv.customerId || inv.customerPartyId || '',
            customerVaNumber: inv.viban || inv.customerVaNumber || '',
            invoiceDate: inv.invoiceDate || inv.issueDate || inv.createdAt || '',
            dueDate: inv.dueDate || '',
            invoiceAmount: inv.amount || inv.grossAmount || inv.netAmount || 0,
            paidAmount: inv.paidAmount || 0,
            outstandingAmount: inv.outstandingAmount || inv.amount || 0,
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
            treasuryVaId: inv.coboCollectorEntityId,
          }));
        }
      } catch (apiErr) {
        console.warn('Invoice API failed, using empty list:', apiErr);
        // Set empty invoices - UI will show "No invoices found"
        mappedInvoices = [];
      }
      
      setInvoices(mappedInvoices);
      
      // Calculate stats
      setStats({
        totalReceivables: mappedInvoices.reduce((s, i) => s + i.outstandingAmount, 0),
        openInvoices: mappedInvoices.filter(i => ['OPEN', 'PARTIAL'].includes(i.status)).reduce((s, i) => s + i.outstandingAmount, 0),
        partialPaid: mappedInvoices.filter(i => i.status === 'PARTIAL').reduce((s, i) => s + i.paidAmount, 0),
        overdueAmount: mappedInvoices.filter(i => false).reduce((s, i) => s + i.outstandingAmount, 0),
        collectedThisMonth: mappedInvoices.filter(i => i.status === 'PAID').reduce((s, i) => s + i.paidAmount, 0),
        invoiceCount: mappedInvoices.length,
        openCount: mappedInvoices.filter(i => i.status === 'OPEN').length,
        partialCount: mappedInvoices.filter(i => i.status === 'PARTIAL').length,
        overdueCount: mappedInvoices.filter(i => false).length,
        paidCount: mappedInvoices.filter(i => i.status === 'PAID').length,
        averageDaysOutstanding: 25,
        coboPendingCount: mappedInvoices.filter(i => i.coboRequestStatus === 'PENDING_TREASURY_APPROVAL').length,
        coboPendingAmount: mappedInvoices.filter(i => i.coboRequestStatus === 'PENDING_TREASURY_APPROVAL').reduce((s, i) => s + i.outstandingAmount, 0),
        intercompanyCount: mappedInvoices.filter(i => i.isIntercompany).length,
        intercompanyAmount: mappedInvoices.filter(i => i.isIntercompany).reduce((s, i) => s + i.outstandingAmount, 0),
        nettingEligibleCount: mappedInvoices.filter(i => i.nettingEligible).length,
        nettingEligibleAmount: mappedInvoices.filter(i => i.nettingEligible).reduce((s, i) => s + i.outstandingAmount, 0),
        nettingIncludedCount: mappedInvoices.filter(i => i.nettingStatus === 'INCLUDED' || i.nettingStatus === 'PENDING').length,
        nettingIncludedAmount: mappedInvoices.filter(i => i.nettingStatus === 'INCLUDED' || i.nettingStatus === 'PENDING').reduce((s, i) => s + i.outstandingAmount, 0),
      });

      // Fetch VIBANs
      const vibanResponse = await mockVibanApi.getAll();
      if (vibanResponse.success && vibanResponse.data) {
        setVibans(vibanResponse.data);
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
  const filteredInvoices = useMemo(() => {
    if (!searchQuery) return invoices;
    const query = searchQuery.toLowerCase();
    return invoices.filter(inv => 
      inv.invoiceNumber.toLowerCase().includes(query) ||
      inv.customerName.toLowerCase().includes(query) ||
      inv.description?.toLowerCase().includes(query)
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

  const coboHistoryInvoices = useMemo(() => 
    filteredInvoices.filter(inv => inv.isCobo || (inv.coboRequestStatus && inv.coboRequestStatus !== 'NOT_REQUESTED')), 
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
      .filter(inv => ['OPEN', 'PARTIAL'].includes(inv.status))
      .map(inv => inv.id);
    
    if (selectedIds.size === selectableIds.length) {
      setSelectedIds(new Set());
    } else {
      setSelectedIds(new Set(selectableIds));
    }
  };

  const handleRequestCobo = (invoice: InvoicePhase3) => {
    setCoboReceivables([invoice]);
    setShowCoboModal(true);
  };

  const handleBulkCobo = () => {
    const selected = filteredInvoices.filter(inv => selectedIds.has(inv.id));
    setCoboReceivables(selected);
    setShowCoboModal(true);
  };

  const handleSubmitCobo = async (collectingEntityId: string, receivableIds: string[]) => {
    setProcessing(true);
    try {
      for (const id of receivableIds) {
        await receivablesApiPhase3.submitForCobo({
          receivableId: id,
          collectorEntityId: collectingEntityId,
          requestedBy: 'current-user',
        });
      }
      await fetchData();
      setSelectedIds(new Set());
      setShowCoboModal(false);
      setCoboReceivables([]);
    } catch (err: any) {
      setError(err.message || 'COBO submission failed');
    } finally {
      setProcessing(false);
    }
  };

  // Treasury Approval Handlers
  const handleApproveCobo = (invoice: InvoicePhase3) => {
    setApprovalInvoice(invoice);
    setApprovalAction('approve');
    setShowApprovalModal(true);
  };

  const handleRejectCobo = (invoice: InvoicePhase3) => {
    setApprovalInvoice(invoice);
    setApprovalAction('reject');
    setShowApprovalModal(true);
  };

  const handleExecuteCobo = (invoice: InvoicePhase3) => {
    setApprovalInvoice(invoice);
    setApprovalAction('execute');
    setShowApprovalModal(true);
  };

  const handleApprovalConfirm = async (reason?: string) => {
    if (!approvalInvoice || !approvalAction) return;
    
    setApprovalLoading(true);
    try {
      if (approvalAction === 'approve') {
        await receivablesApiPhase3.approveCobo(approvalInvoice.id, 'treasury-user', 'Approved');
      } else if (approvalAction === 'reject') {
        await receivablesApiPhase3.rejectCobo(approvalInvoice.id, 'treasury-user', reason || 'Rejected');
      } else if (approvalAction === 'execute') {
        await receivablesApiPhase3.executeCoboCollection({
          receivableId: approvalInvoice.id,
          executedBy: 'treasury-user',
          paymentReference: `COBO-${Date.now()}`,
          paymentDate: new Date().toISOString().split('T')[0],
        });
      }
      
      await fetchData();
      setShowApprovalModal(false);
      setApprovalInvoice(null);
      setApprovalAction(null);
    } catch (err: any) {
      setError(err.message || 'Action failed');
    } finally {
      setApprovalLoading(false);
    }
  };

  const handleAddToNetting = async (invoice: InvoicePhase3) => {
    setProcessing(true);
    try {
      // Use addEntry with cycle id and entry data
      await nettingApi.addEntry('default-cycle', { receivableId: invoice.id, amount: invoice.outstandingAmount });
      await fetchData();
    } catch (err: any) {
      setError(err.message || 'Failed to add to netting');
    } finally {
      setProcessing(false);
    }
  };

  const handleRemoveFromNetting = async (invoice: InvoicePhase3) => {
    setProcessing(true);
    try {
      // Note: nettingApi doesn't have removeReceivable - use API client directly
      await apiClient.delete(`/receivables/${invoice.id}/netting/remove`);
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
      await mockVibanApi.generate(invoice.id);
      await fetchData();
    } catch (err: any) {
      setError(err.message || 'Failed to generate VIBAN');
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
    console.log('handleCreateInvoice - navigating with params:', params);
    console.log('selectedCorporateId:', selectedCorporateId, 'selectedEntityId:', selectedEntityId);
    navigate('receivables-create', Object.keys(params).length > 0 ? params : undefined);
  };

  const handleEditInvoice = (invoice: InvoicePhase3) => {
    console.log('Edit invoice:', invoice.id);
  };

  const handleViewInvoice = (invoice: InvoicePhase3) => {
    console.log('View invoice:', invoice.id);
  };

  const handleViewNettingCycle = (cycleId: string) => {
    console.log('View netting cycle:', cycleId);
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
  ];

  return (
    <Page>
      {/* Quick Actions */}
      <div className="flex items-center justify-end gap-2 animate-fade-in" style={{ animationDelay: '0.05s' }}>
        {selectedIds.size > 0 && (
          <Button
            variant="primary"
            onClick={handleBulkCobo}
            leftIcon={<ArrowDownLeft className="w-4 h-4" />}
          >
            Request COBO ({selectedIds.size})
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
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400 dark:text-neutral-500" />
            <Input
              placeholder="Search invoices..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="pl-10"
            />
          </div>
        </div>
      </Card>

      {/* Scope picker — Corporate + Program + Entity. Entity goes in
          ScopeSelector's `rightSlot` so it gets the same medallion / label /
          Select recipe as the other two fields. */}
      <ScopeSelector
        mode="corporate-program"
        corporates={corporates}
        programs={programs}
        selectedCorporateId={selectedCorporateId}
        selectedProgramId={selectedProgramId}
        onCorporateChange={setSelectedCorporateId}
        onProgramChange={setSelectedProgramId}
        rightSlot={(
          <div className="flex items-center gap-2">
            <StatusIconBadge tone="success" icon={Building2} size="sm" />
            <div className="min-w-[200px]">
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
        <div className="grid grid-cols-2 md:grid-cols-4 lg:grid-cols-6 gap-4">
          <StatsCard
            title="Total Receivables"
            value={formatCurrency(stats.totalReceivables, 'AED')}
            subtitle={`${stats.invoiceCount} invoices`}
            icon={<FileText className="w-5 h-5" />}
            color="indigo"
            delay={0.15}
          />
          <StatsCard
            title="Open / Partial"
            value={formatCurrency(stats.openInvoices, 'AED')}
            subtitle={`${stats.openCount + stats.partialCount} invoices`}
            icon={<Clock className="w-5 h-5" />}
            color="blue"
            delay={0.2}
          />
          <StatsCard
            title="Overdue"
            value={formatCurrency(stats.overdueAmount, 'AED')}
            subtitle={`${stats.overdueCount} invoices`}
            icon={<AlertTriangle className="w-5 h-5" />}
            color="rose"
            delay={0.25}
          />
          <StatsCard
            title="COBO Pending"
            value={formatCurrency(stats.coboPendingAmount || 0, 'AED')}
            subtitle={`${stats.coboPendingCount || 0} awaiting treasury`}
            icon={<ArrowDownLeft className="w-5 h-5" />}
            color="amber"
            delay={0.3}
          />
          <StatsCard
            title="Intercompany"
            value={formatCurrency(stats.intercompanyAmount || 0, 'AED')}
            subtitle={`${stats.intercompanyCount || 0} IC invoices`}
            icon={<Building2 className="w-5 h-5" />}
            color="purple"
            delay={0.35}
          />
          <StatsCard
            title="In Netting"
            value={formatCurrency(stats.nettingIncludedAmount || 0, 'AED')}
            subtitle={`${stats.nettingIncludedCount || 0} in cycle`}
            icon={<GitMerge className="w-5 h-5" />}
            color="emerald"
            delay={0.4}
          />
        </div>
      )}

      {/* Error Display */}
      {error && (
        <Card className="animate-fade-in border-error-200 bg-error-50 dark:border-error-500/30 dark:bg-error-500/10">
          <div className="h-1 bg-gradient-to-r from-error-100 via-error-200 to-error-100 rounded-t-xl" />
          <div className="p-4 flex items-center gap-3">
            <StatusIconBadge tone="error" icon={AlertCircle} className="dark:bg-error-500/20" />
            <span className="flex-1 text-sm text-error-700 dark:text-error-300">{error}</span>
            <Button variant="ghost" size="sm" onClick={() => setError(null)} className="text-error-600 hover:bg-error-100 dark:text-error-300 dark:hover:bg-error-500/20">
              <X className="w-4 h-4" />
            </Button>
          </div>
        </Card>
      )}

      {/* Main Content Card */}
      <Card className="animate-fade-in" style={{ animationDelay: '0.45s' }}>
        <div className="h-1 bg-gradient-to-r from-primary-50/50 via-white to-primary-50/50 rounded-t-xl dark:from-primary-900 dark:via-primary-900 dark:to-primary-900" />
        {/* Tabs */}
        <div className="border-b border-neutral-200 dark:border-primary-800">
          <div className="flex gap-1 px-4">
            {tabs.map(tab => (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id)}
                className={cn(
                  'px-4 py-3 text-sm font-medium border-b-2 transition-all duration-200',
                  activeTab === tab.id
                    ? 'border-primary-600 text-primary-600 dark:text-primary-200'
                    : 'border-transparent text-neutral-500 hover:text-neutral-700 hover:border-neutral-300 dark:text-neutral-400 dark:hover:text-neutral-200 dark:hover:border-primary-700'
                )}
              >
                {tab.label}
                <span className={cn(
                  'ml-2 px-2 py-0.5 text-xs rounded-full transition-colors',
                  activeTab === tab.id ? 'bg-primary-100 text-primary-700 dark:bg-primary-700 dark:text-neutral-200' : 'bg-neutral-100 text-neutral-600 dark:bg-primary-800 dark:text-neutral-300'
                )}>
                  {tab.count}
                </span>
              </button>
            ))}
          </div>
        </div>

        {/* Tab Content */}
        <div className="p-4">
          {loading ? (
            <div className="flex items-center justify-center py-12">
              <Loader2 className="w-8 h-8 animate-spin text-indigo-600 dark:text-indigo-300" />
              <span className="ml-3 text-neutral-500 dark:text-neutral-400">Loading receivables...</span>
            </div>
          ) : (
            <>
              {/* All Invoices Tab */}
              {activeTab === 'invoices' && (
                <div className="overflow-x-auto">
                  <table className="data-table">
                    <thead>
                      <tr className="data-table-header">
                        <th className="data-table-header-cell w-12">
                          <input
                            type="checkbox"
                            onChange={handleSelectAll}
                            checked={selectedIds.size > 0 && selectedIds.size === filteredInvoices.filter(inv => ['OPEN', 'PARTIAL'].includes(inv.status)).length}
                            className="w-4 h-4 rounded border-neutral-300 text-primary-600 focus:ring-primary-500 dark:border-primary-700 dark:text-primary-200"
                          />
                        </th>
                        <th className="data-table-header-cell">Invoice</th>
                        <th className="data-table-header-cell">Customer</th>
                        <th className="data-table-header-cell text-right">Amount</th>
                        <th className="data-table-header-cell">Due Date</th>
                        <th className="data-table-header-cell">Status</th>
                        <th className="data-table-header-cell">VIBAN</th>
                        <th className="data-table-header-cell">Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {filteredInvoices.map(invoice => (
                        <InvoiceRow
                          key={invoice.id}
                          invoice={invoice}
                          isSelected={selectedIds.has(invoice.id)}
                          onSelect={handleSelectInvoice}
                          onView={() => handleViewInvoice(invoice)}
                          onEdit={() => handleEditInvoice(invoice)}
                          onRequestCobo={() => handleRequestCobo(invoice)}
                          onAddToNetting={() => handleAddToNetting(invoice)}
                          onGenerateViban={() => handleGenerateViban(invoice)}
                        />
                      ))}
                    </tbody>
                  </table>
                  {filteredInvoices.length === 0 && (
                    <div className="text-center py-12">
                      <FileText className="w-12 h-12 text-neutral-300 mx-auto mb-4 dark:text-neutral-600" />
                      <p className="text-neutral-500 dark:text-neutral-400">No invoices found</p>
                    </div>
                  )}
                </div>
              )}

              {/* Intercompany Tab */}
              {activeTab === 'intercompany' && (
                <div className="overflow-x-auto">
                  <table className="data-table">
                    <thead>
                      <tr className="data-table-header">
                        <th className="data-table-header-cell">Invoice</th>
                        <th className="data-table-header-cell">From Entity</th>
                        <th className="data-table-header-cell">To Entity (IC)</th>
                        <th className="data-table-header-cell text-right">Amount</th>
                        <th className="data-table-header-cell text-center">Status</th>
                        <th className="data-table-header-cell text-center">Netting</th>
                        <th className="data-table-header-cell">Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {intercompanyInvoices.map((invoice) => (
                        <tr key={invoice.id} className="data-table-row group">
                          <td className="data-table-cell">
                            <p className="text-sm font-mono font-medium text-primary-900 dark:text-neutral-50">{invoice.invoiceNumber}</p>
                            <p className="text-xs text-neutral-500 dark:text-neutral-400">{formatDate(invoice.invoiceDate)}</p>
                          </td>
                          <td className="data-table-cell">
                            <Badge variant="info" size="sm">
                              {invoice.owningEntityCode || 'Unknown'}
                            </Badge>
                            <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">{invoice.owningEntityName || ''}</p>
                          </td>
                          <td className="data-table-cell">
                            <Badge variant="info" size="sm">
                              <Building2 className="w-3 h-3 mr-1" />
                              {invoice.intercompanyEntityCode || 'Unknown'}
                            </Badge>
                            <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">{invoice.intercompanyEntityName || ''}</p>
                          </td>
                          <td className="data-table-cell text-right">
                            <p className="text-sm font-semibold text-primary-900 dark:text-neutral-50">{formatCurrency(invoice.outstandingAmount, invoice.currencyCode)}</p>
                          </td>
                          <td className="data-table-cell text-center">
                            {getStatusBadge(invoice.status)}
                          </td>
                          <td className="data-table-cell text-center">
                            {getNettingStatusBadge(invoice.nettingStatus)}
                          </td>
                          <td className="data-table-cell">
                            <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                              <Button variant="ghost" size="sm" onClick={() => handleViewInvoice(invoice)}>
                                <Eye className="w-4 h-4" />
                              </Button>
                              <Button variant="ghost" size="sm" onClick={() => handleEditInvoice(invoice)}>
                                <Edit2 className="w-4 h-4" />
                              </Button>
                              {invoice.nettingEligible && invoice.nettingStatus === 'NOT_INCLUDED' && (
                                <Button variant="outline" size="sm" onClick={() => handleAddToNetting(invoice)}>
                                  <GitMerge className="w-4 h-4" />
                                </Button>
                              )}
                            </div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  {intercompanyInvoices.length === 0 && (
                    <div className="text-center py-12">
                      <Building2 className="w-12 h-12 text-neutral-300 mx-auto mb-4 dark:text-neutral-600" />
                      <p className="text-neutral-500 dark:text-neutral-400">No intercompany invoices found</p>
                    </div>
                  )}
                </div>
              )}

              {/* Pending Netting Tab */}
              {activeTab === 'pending-netting' && (
                <div className="overflow-x-auto">
                  <table className="data-table">
                    <thead>
                      <tr className="data-table-header">
                        <th className="data-table-header-cell">Invoice</th>
                        <th className="data-table-header-cell">Customer</th>
                        <th className="data-table-header-cell text-right">Amount</th>
                        <th className="data-table-header-cell text-center">Netting Status</th>
                        <th className="data-table-header-cell">Cycle</th>
                        <th className="data-table-header-cell">Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {pendingNettingInvoices.map((invoice) => (
                        <tr key={invoice.id} className="data-table-row group">
                          <td className="data-table-cell">
                            <p className="text-sm font-mono font-medium text-primary-900 dark:text-neutral-50">{invoice.invoiceNumber}</p>
                          </td>
                          <td className="data-table-cell">
                            <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{invoice.customerName}</p>
                          </td>
                          <td className="data-table-cell text-right">
                            <p className="text-sm font-semibold text-primary-900 dark:text-neutral-50">{formatCurrency(invoice.outstandingAmount, invoice.currencyCode)}</p>
                          </td>
                          <td className="data-table-cell text-center">
                            {getNettingStatusBadge(invoice.nettingStatus)}
                          </td>
                          <td className="data-table-cell">
                            <p className="text-sm font-mono">{invoice.nettingCycleRef || '-'}</p>
                          </td>
                          <td className="data-table-cell">
                            <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
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
                              {invoice.nettingCycleId && (
                                <Button
                                  variant="outline"
                                  size="sm"
                                  onClick={() => handleViewNettingCycle(invoice.nettingCycleId!)}
                                  title="View Cycle"
                                >
                                  <Layers className="w-4 h-4" />
                                </Button>
                              )}
                            </div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  {pendingNettingInvoices.length === 0 && (
                    <div className="text-center py-12">
                      <GitMerge className="w-12 h-12 text-neutral-300 mx-auto mb-4 dark:text-neutral-600" />
                      <p className="text-neutral-500 dark:text-neutral-400">No receivables pending netting</p>
                    </div>
                  )}
                </div>
              )}

              {/* VIBANs Tab */}
              {activeTab === 'vibans' && (
                <div className="overflow-x-auto">
                  <table className="data-table">
                    <thead>
                      <tr className="data-table-header">
                        <th className="data-table-header-cell">VIBAN</th>
                        <th className="data-table-header-cell">Reference</th>
                        <th className="data-table-header-cell">Customer</th>
                        <th className="data-table-header-cell text-right">Expected</th>
                        <th className="data-table-header-cell text-right">Received</th>
                        <th className="data-table-header-cell text-center">Status</th>
                        <th className="data-table-header-cell">Expires</th>
                      </tr>
                    </thead>
                    <tbody>
                      {vibans.map((viban) => (
                        <tr key={viban.id} className="data-table-row group">
                          <td className="data-table-cell">
                            <div className="flex items-center gap-2">
                              <span className="text-sm font-mono text-primary-900 dark:text-neutral-50">{viban.virtualIban}</span>
                              <button
                                onClick={() => navigator.clipboard.writeText(viban.virtualIban)}
                                className="text-neutral-400 hover:text-neutral-600 transition-colors opacity-0 group-hover:opacity-100 dark:text-neutral-500 dark:hover:text-neutral-300"
                              >
                                <Copy className="w-4 h-4" />
                              </button>
                            </div>
                          </td>
                          <td className="data-table-cell">
                            <p className="text-sm font-mono">{viban.reference}</p>
                          </td>
                          <td className="data-table-cell">
                            <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{viban.customerName}</p>
                          </td>
                          <td className="data-table-cell text-right">
                            <p className="text-sm">{formatCurrency(viban.expectedAmount, viban.currency)}</p>
                          </td>
                          <td className="data-table-cell text-right">
                            <p className="text-sm font-semibold text-success-600 dark:text-success-300">
                              {formatCurrency(viban.receivedAmount, viban.currency)}
                            </p>
                          </td>
                          <td className="data-table-cell text-center">
                            <Badge
                              variant={viban.status === 'PAID' ? 'success' : viban.status === 'ACTIVE' ? 'info' : 'neutral'}
                              size="sm"
                            >
                              {viban.status}
                            </Badge>
                          </td>
                          <td className="data-table-cell">
                            <p className="text-sm">{formatDate(viban.expiresAt)}</p>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  {vibans.length === 0 && (
                    <div className="text-center py-12">
                      <QrCode className="w-12 h-12 text-neutral-300 mx-auto mb-4 dark:text-neutral-600" />
                      <p className="text-neutral-500 dark:text-neutral-400">No VIBANs generated</p>
                    </div>
                  )}
                </div>
              )}

              {/* COBO History Tab - WITH TREASURY APPROVAL BUTTONS */}
              {activeTab === 'cobo-history' && (
                <div className="overflow-x-auto">
                  <table className="data-table">
                    <thead>
                      <tr className="data-table-header">
                        <th className="data-table-header-cell">Invoice</th>
                        <th className="data-table-header-cell">Customer</th>
                        <th className="data-table-header-cell text-right">Amount</th>
                        <th className="data-table-header-cell">Collector</th>
                        <th className="data-table-header-cell">On Behalf</th>
                        <th className="data-table-header-cell text-center">Status</th>
                        <th className="data-table-header-cell">Requested</th>
                        <th className="data-table-header-cell">Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {coboHistoryInvoices.map((invoice) => (
                        <tr key={invoice.id} className="data-table-row group">
                          <td className="data-table-cell">
                            <p className="text-sm font-mono font-medium text-primary-900 dark:text-neutral-50">{invoice.invoiceNumber}</p>
                            <p className="text-xs text-neutral-500 dark:text-neutral-400">{invoice.coboTransactionRef || '-'}</p>
                          </td>
                          <td className="data-table-cell">
                            <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{invoice.customerName}</p>
                          </td>
                          <td className="data-table-cell text-right">
                            <p className="text-sm font-semibold text-primary-900 dark:text-neutral-50">{formatCurrency(invoice.invoiceAmount, invoice.currencyCode)}</p>
                          </td>
                          <td className="data-table-cell">
                            <p className="text-sm">{invoice.coboCollectorEntityCode || '-'}</p>
                            <p className="text-xs text-neutral-500 dark:text-neutral-400">{invoice.coboCollectorEntityName || ''}</p>
                          </td>
                          <td className="data-table-cell">
                            <p className="text-sm">{invoice.owningEntityCode || '-'}</p>
                            <p className="text-xs text-neutral-500 dark:text-neutral-400">{invoice.owningEntityName || ''}</p>
                          </td>
                          <td className="data-table-cell text-center">
                            {getCoboStatusBadge(invoice.coboRequestStatus) || (<Badge variant="neutral" size="sm">Pending</Badge>)}
                          </td>
                          <td className="data-table-cell">
                            <p className="text-sm">{invoice.coboRequestedAt ? formatDate(invoice.coboRequestedAt) : '-'}</p>
                            <p className="text-xs text-neutral-500 dark:text-neutral-400">{invoice.coboRequestedBy || ''}</p>
                          </td>
                          <td className="data-table-cell">
                            <div className="flex items-center gap-1">
                              {/* Treasury Approval Actions */}
                              {invoice.coboRequestStatus === 'PENDING_TREASURY_APPROVAL' && (
                                <>
                                  <Button
                                    variant="outline"
                                    size="sm"
                                    onClick={() => handleApproveCobo(invoice)}
                                    className="text-success-600 border-success-200 hover:bg-success-50 dark:text-success-300 dark:border-success-500/30 dark:hover:bg-success-500/10"
                                    title="Approve COBO"
                                  >
                                    <ThumbsUp className="w-4 h-4" />
                                  </Button>
                                  <Button
                                    variant="outline"
                                    size="sm"
                                    onClick={() => handleRejectCobo(invoice)}
                                    className="text-error-600 border-error-200 hover:bg-error-50 dark:text-error-300 dark:border-error-500/30 dark:hover:bg-error-500/10"
                                    title="Reject COBO"
                                  >
                                    <ThumbsDown className="w-4 h-4" />
                                  </Button>
                                </>
                              )}
                              {/* Execute Collection */}
                              {invoice.coboRequestStatus === 'APPROVED' && (
                                <Button
                                  variant="primary"
                                  size="sm"
                                  onClick={() => handleExecuteCobo(invoice)}
                                  leftIcon={<Play className="w-4 h-4" />}
                                  title="Execute Collection"
                                >
                                  Execute
                                </Button>
                              )}
                              {/* View Details */}
                              <Button
                                variant="ghost"
                                size="sm"
                                onClick={() => handleViewInvoice(invoice)}
                                title="View Details"
                                className="opacity-0 group-hover:opacity-100 transition-opacity"
                              >
                                <Eye className="w-4 h-4" />
                              </Button>
                            </div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  {coboHistoryInvoices.length === 0 && (
                    <div className="text-center py-12">
                      <ArrowDownLeft className="w-12 h-12 text-neutral-300 mx-auto mb-4 dark:text-neutral-600" />
                      <p className="text-neutral-500 dark:text-neutral-400">No COBO collections found</p>
                    </div>
                  )}
                </div>
              )}
            </>
          )}
        </div>
      </Card>

      {/* COBO Request Modal */}
      <CoboRequestModal
        isOpen={showCoboModal}
        onClose={() => { setShowCoboModal(false); setCoboReceivables([]); }}
        receivables={coboReceivables}
        entities={legalEntities}
        onSubmit={handleSubmitCobo}
      />

      {/* Treasury Approval Modal */}
      <TreasuryApprovalModal
        isOpen={showApprovalModal}
        onClose={() => { setShowApprovalModal(false); setApprovalInvoice(null); setApprovalAction(null); }}
        invoice={approvalInvoice}
        action={approvalAction}
        onConfirm={handleApprovalConfirm}
        loading={approvalLoading}
      />
    </Page>
  );
};

export default EnhancedReceivablesPage;