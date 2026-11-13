import React, { useState, useEffect, useCallback } from 'react';
import toast from 'react-hot-toast';
import { GitMerge, Plus, Calculator, CheckCircle, Clock, Loader2, RefreshCw, DollarSign, TrendingUp, Eye, Users, ArrowRight, Building2, Filter, Download, BarChart3, Layers, Send, XCircle, AlertTriangle } from 'lucide-react';
import { Card, Button, Badge, Input , StatusIconBadge, StatTile, Checkbox, DataTable } from '../components/ui';
import { CurrencyPicker } from '../components/ui/CurrencyPicker';
import { Modal } from '../components/ui/enhanced';
import { formatCurrency, cn } from '../utils';
import { TileAmount } from '../components/TileAmount';
import { nettingApi, NettingCycle, corporatesApi, Corporate, type NettingSettlementLeg, type NettingSettlementPreview } from '../services/api';
import { usePermissions } from '../hooks/usePermissions';
import { TreasuryOnly } from '../components/permissions';
import { usePageHeaderActions } from '../context/usePageHeader';
import { useNavigation } from '../hooks/useNavigation';
import { Page } from '../components/layout/Page';
import { PageHeader } from '../components/layout/PageHeader';
import { StatStrip } from '../components/layout/StatStrip';
import { ScopeSelector } from '../components/layout/ScopeSelector';

// ============================================================================
// TYPES
// ============================================================================

interface NettingEntry {
  id: string;
  entryReference: string;
  flowDirection: 'PAYABLE' | 'RECEIVABLE';
  payerEntityCode: string;
  payerEntityName: string;
  payeeEntityCode: string;
  payeeEntityName: string;
  grossAmount: number;
  currencyCode: string;
  baseAmount: number;
  sourceType: string;
  sourceReference?: string;
  dueDate?: string;
  status: string;
}

interface NettingPosition {
  entityId: string;
  entityCode: string;
  entityName: string;
  grossPayables: number;
  grossReceivables: number;
  netPosition: number;
  netDirection: 'PAY' | 'RECEIVE' | 'ZERO';
  entryCount: number;
  currency: string;
}

// ============================================================================
// LOADING & ERROR COMPONENTS
// ============================================================================

const LoadingSpinner: React.FC = () => (
  <div className="flex items-center justify-center py-12 animate-fade-in">
    <StatusIconBadge tone="primary" icon={Loader2} size="lg" spin />
    <span className="ml-3 text-neutral-600 font-medium dark:text-neutral-300">Loading netting cycles...</span>
  </div>
);

const ErrorMessage: React.FC<{ message: string; onRetry: () => void }> = ({ message, onRetry }) => (
  <Card className="bg-error-50 border-error-200 animate-fade-in dark:bg-error-500/10 dark:border-error-500/30">
    <div className="flex items-center gap-3 p-4">
      <StatusIconBadge tone="error" icon={XCircle} />
      <div className="flex-1">
        <p className="font-medium text-error-800 dark:text-error-300">Failed to load data</p>
        <p className="text-body-sm text-error-600 dark:text-error-300">{message}</p>
      </div>
      <Button variant="outline" size="sm" onClick={onRetry}>Retry</Button>
    </div>
  </Card>
);

// ============================================================================
// CREATE CYCLE MODAL
// ============================================================================

interface CreateCycleModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSave: (data: any) => Promise<void>;
  /** The page's corporate: a cycle nets one corporate's entities. */
  corporate?: Corporate;
}

const CreateCycleModal: React.FC<CreateCycleModalProps> = ({ isOpen, onClose, onSave, corporate }) => {
  const [saving, setSaving] = useState(false);
  const today = new Date().toISOString().split('T')[0];
  const nextMonth = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString().split('T')[0];

  const [formData, setFormData] = useState({
    cycleName: '',
    periodStart: today,
    periodEnd: nextMonth,
    settlementDate: '',
    baseCurrency: 'AED',
    autoPopulate: true,
    includePending: false, // Include PENDING recharges for testing
  });

  const handleSubmit = async () => {
    if (!corporate) return;
    setSaving(true);
    try {
      await onSave({ ...formData, corporateId: corporate.id });
      onClose();
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="Create Netting Cycle" size="md">
      <div className="space-y-4">
        <div>
          <p className="field-label mb-1">Corporate</p>
          <p className="body-strong">{corporate?.legalName ?? 'Select a corporate first'}</p>
        </div>

        <div>
          <label className="field-label block mb-1">Cycle Name</label>
          <Input
            placeholder="e.g., Q1 2024 Intercompany Netting"
            value={formData.cycleName}
            onChange={(e) => setFormData({ ...formData, cycleName: e.target.value })}
          />
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className="field-label block mb-1">Period Start</label>
            <Input
              type="date"
              value={formData.periodStart}
              onChange={(e) => setFormData({ ...formData, periodStart: e.target.value })} 
            />
          </div>
          <div>
            <label className="field-label block mb-1">Period End</label>
            <Input 
              type="date" 
              value={formData.periodEnd} 
              onChange={(e) => setFormData({ ...formData, periodEnd: e.target.value })} 
            />
          </div>
        </div>
        
        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className="field-label block mb-1">Settlement Date</label>
            <Input 
              type="date" 
              value={formData.settlementDate} 
              onChange={(e) => setFormData({ ...formData, settlementDate: e.target.value })} 
            />
          </div>
          <div>
            <label className="field-label block mb-1">Base Currency</label>
            <CurrencyPicker
              value={formData.baseCurrency}
              onChange={(c) => setFormData({ ...formData, baseCurrency: c })}
              withName
              extra={['SAR']}
            />
          </div>
        </div>

        <div className="space-y-2 p-3 bg-info-50 rounded-lg dark:bg-info-500/10">
          <Checkbox
            size="sm"
            checked={formData.autoPopulate}
            onChange={(checked) => setFormData({ ...formData, autoPopulate: checked })}
            label={<span className="text-body-sm font-normal text-neutral-700 dark:text-neutral-200">Auto-populate with eligible intercompany payables, receivables, and recharges</span>}
          />
          {formData.autoPopulate && (
            <div className="ml-6">
              <Checkbox
                size="sm"
                checked={formData.includePending}
                onChange={(checked) => setFormData({ ...formData, includePending: checked })}
                label={<span className="body-sm font-normal">Include pending (unapproved) POBO recharges</span>}
              />
            </div>
          )}
        </div>
      </div>
      
      <div className="flex justify-end gap-2 mt-6 pt-4 border-t">
        <Button variant="ghost" onClick={onClose}>Cancel</Button>
        <Button onClick={handleSubmit} disabled={saving || !formData.cycleName || !corporate}>
          {saving && <Loader2 className="w-4 h-4 animate-spin mr-2" />}
          Create Cycle
        </Button>
      </div>
    </Modal>
  );
};

// ============================================================================
// SETTLEMENT LEGS + PREVIEW (spec R10, R11): what settling posts, from the code that posts it
// ============================================================================

const LEG_KIND: Record<NettingSettlementLeg['kind'], { label: string; variant: 'info' | 'warning' | 'error' }> = {
  LEDGER: { label: 'Ledger transfer', variant: 'info' },
  PAYMENT: { label: 'Payment', variant: 'warning' },
  REFUSED: { label: 'Refused', variant: 'error' },
};

const SettlementLegs: React.FC<{ legs: NettingSettlementLeg[] }> = ({ legs }) => {
  if (legs.length === 0) return <p className="body-sm">No money moves: every participant nets to zero.</p>;
  return (
    <div className="space-y-3">
      {legs.map((leg, i) => (
        <Card key={`${leg.payerEntityId}-${leg.receiverEntityId}-${i}`}>
          <div className="p-4 space-y-2">
            <div className="flex items-center justify-between gap-4">
              <div className="flex items-center gap-3">
                <div>
                  <p className="body-strong">{leg.payerEntityCode}</p>
                  <p className="caption">{leg.fromAccount ?? 'no account'}</p>
                </div>
                <ArrowRight className="w-5 h-5 text-primary-400" />
                <div>
                  <p className="body-strong">{leg.receiverEntityCode}</p>
                  <p className="caption">{leg.toAccount ?? 'no account'}</p>
                </div>
              </div>
              <div className="flex items-center gap-3">
                <p className="stat-value-xs">{formatCurrency(leg.amount, leg.currency)}</p>
                <Badge variant={LEG_KIND[leg.kind].variant}>{LEG_KIND[leg.kind].label}</Badge>
              </div>
            </div>
            {leg.kind === 'LEDGER' && (
              <p className="caption">Both accounts sit on the same bank account or are in-house bank accounts: the balances move in the ledger.</p>
            )}
            {leg.kind === 'PAYMENT' && (
              <p className="caption">
                Outward payment to {leg.beneficiaryAccount}{leg.beneficiaryBic ? ` (${leg.beneficiaryBic})` : ''}; {leg.receiverEntityCode} is credited when it arrives.
              </p>
            )}
            {leg.reason && <p className="body-sm text-error-600 dark:text-error-300">{leg.reason}</p>}
            {leg.transactionReference && <p className="caption">Booked as {leg.transactionReference}</p>}
          </div>
        </Card>
      ))}
    </div>
  );
};

const NET_LABEL: Record<string, string> = { PAY: 'Pays', RECEIVE: 'Receives', NEUTRAL: 'Nets to zero' };

const SettlementPreviewModal: React.FC<{
  cycle: NettingCycle | null; canSettle: boolean; onClose: () => void; onDone: () => void;
}> = ({ cycle, canSettle, onClose, onDone }) => {
  const [preview, setPreview] = useState<NettingSettlementPreview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [settling, setSettling] = useState(false);

  useEffect(() => {
    setPreview(null);
    setError(null);
    if (!cycle) return;
    let current = true;
    nettingApi.previewSettlement(cycle.id)
      .then(r => { if (current) setPreview(r.data); })
      .catch((e: any) => { if (current) setError(e.response?.data?.message || e.message || 'Failed to load the settlement preview'); });
    return () => { current = false; };
  }, [cycle]);

  const settle = async () => {
    if (!cycle) return;
    setSettling(true);
    try {
      const r = await nettingApi.settleCycle(cycle.id);
      toast.success(`${r.data.cycleReference} settled: ${r.data.legs.length} leg${r.data.legs.length === 1 ? '' : 's'}`);
    } catch (e: any) {
      // Nothing was posted; the cycle now carries the reason.
      toast.error(e.response?.data?.message || e.message || 'Settlement failed');
    } finally {
      setSettling(false);
      onDone();
      onClose();
    }
  };

  return (
    <Modal isOpen={!!cycle} onClose={onClose} title={`Settle ${cycle?.cycleReference ?? ''}`} size="lg">
      <div className="space-y-5">
        {error && <p className="body-sm text-error-600 dark:text-error-300">{error}</p>}
        {!error && !preview && (
          <div className="flex items-center gap-2 body-sm"><Loader2 className="w-4 h-4 animate-spin" /> Working out the legs…</div>
        )}
        {preview && (
          <>
            <div>
              <h4 className="label mb-2">Per entity ({preview.currency})</h4>
              <DataTable
                data={preview.settlements}
                keyExtractor={(s) => s.id}
                columns={[
                  { key: 'entityCode', header: 'Entity', minWidth: 160, render: (_v, s) => <span className="body-strong">{s.entityCode}</span> },
                  { key: 'settlementDirection', header: 'Position', minWidth: 120, render: (_v, s) => (
                    <Badge variant={s.settlementDirection === 'PAY' ? 'error' : s.settlementDirection === 'RECEIVE' ? 'success' : 'neutral'} size="sm">
                      {NET_LABEL[s.settlementDirection] ?? s.settlementDirection}
                    </Badge>
                  ) },
                  { key: 'netPosition', header: 'Net', align: 'right', minWidth: 140, render: (_v, s) => (
                    <span className="body-strong">{formatCurrency(Math.abs(s.netPosition), preview.currency)}</span>
                  ) },
                ]}
              />
            </div>
            <div>
              <h4 className="label mb-2">Legs</h4>
              <SettlementLegs legs={preview.legs} />
            </div>
            {preview.blockers.length > 0 && (
              <div className="p-3 rounded-lg bg-error-50 dark:bg-error-500/10 space-y-1">
                <p className="body-strong text-error-700 dark:text-error-300">This cycle cannot be settled:</p>
                {preview.blockers.map(b => <p key={b} className="body-sm text-error-700 dark:text-error-300">{b}</p>)}
              </div>
            )}
            {preview.settleable && (
              <p className="body-sm">Every leg books, or none does: if a payment is refused, nothing is posted and the cycle shows why.</p>
            )}
          </>
        )}
      </div>
      <div className="flex justify-end gap-2 mt-6 pt-4 border-t">
        <Button variant="ghost" onClick={onClose}>Cancel</Button>
        <Button onClick={settle} disabled={!preview?.settleable || !canSettle || settling}>
          {settling && <Loader2 className="w-4 h-4 animate-spin mr-2" />}
          Confirm and settle
        </Button>
      </div>
    </Modal>
  );
};

// ============================================================================
// CYCLE DETAIL MODAL
// ============================================================================

interface CycleDetailModalProps {
  isOpen: boolean;
  onClose: () => void;
  cycle: NettingCycle | null;
  onPopulate?: (cycleId: string) => Promise<void>;
  isProcessing?: boolean;
}

const CycleDetailModal: React.FC<CycleDetailModalProps> = ({ isOpen, onClose, cycle, onPopulate, isProcessing }) => {
  const [activeTab, setActiveTab] = useState<'overview' | 'entries' | 'positions' | 'settlement'>('overview');
  const [, setLoading] = useState(false);
  const [entries, setEntries] = useState<NettingEntry[]>([]);
  const [positions, setPositions] = useState<NettingPosition[]>([]);
  const [legs, setLegs] = useState<NettingSettlementLeg[]>([]);

  useEffect(() => {
    if (cycle && isOpen) {
      // Fetch real cycle details from API
      const fetchCycleDetails = async () => {
        setLoading(true);
        try {
          const response = await nettingApi.getCycleById(cycle.id);
          if (response.success && response.data) {
            const cycleData = response.data;

            // Map entries from API response
            if (cycleData.entries && cycleData.entries.length > 0) {
              setEntries(cycleData.entries.map(e => ({
                id: e.id,
                entryReference: e.entryReference,
                flowDirection: e.flowDirection,
                payerEntityCode: e.payerEntityCode,
                payerEntityName: e.payerEntityName,
                payeeEntityCode: e.payeeEntityCode,
                payeeEntityName: e.payeeEntityName,
                grossAmount: e.grossAmount,
                currencyCode: e.currencyCode,
                baseAmount: e.baseAmount,
                sourceType: e.sourceType,
                sourceReference: e.sourceReference,
                dueDate: e.dueDate,
                status: e.status,
              })));
            } else {
              setEntries([]);
            }

            // Map settlements to positions from API response
            if (cycleData.settlements && cycleData.settlements.length > 0) {
              setPositions(cycleData.settlements.map(s => ({
                entityId: s.entityId,
                entityCode: s.entityCode,
                entityName: s.entityName,
                grossPayables: s.grossPayables || s.totalPayable,
                grossReceivables: s.grossReceivables || s.totalReceivable,
                netPosition: Math.abs(s.netPosition),
                netDirection: s.settlementDirection === 'PAY' ? 'PAY' : s.settlementDirection === 'RECEIVE' ? 'RECEIVE' : 'ZERO',
                entryCount: (s.payableEntryCount || 0) + (s.receivableEntryCount || 0),
                currency: cycle.baseCurrency || 'AED',
              })));

              // The legs settling posts (or posted), from the same computation as the settlement itself.
              const preview = await nettingApi.previewSettlement(cycle.id);
              setLegs(preview.data?.legs ?? []);
            } else {
              setPositions([]);
              setLegs([]);
            }
          }
        } catch (err) {
          console.error('Failed to fetch cycle details:', err);
        } finally {
          setLoading(false);
        }
      };

      fetchCycleDetails();
    }
  }, [cycle, isOpen]);

  if (!cycle) return null;

  const tabs = [
    { id: 'overview', label: 'Overview', icon: BarChart3 },
    { id: 'entries', label: 'Entries', icon: Layers, count: entries.length },
    { id: 'positions', label: 'Positions', icon: Users, count: positions.length },
    { id: 'settlement', label: 'Settlement', icon: Send, count: legs.length },
  ];

  return (
    <Modal isOpen={isOpen} onClose={onClose} title={cycle.cycleName} size="xl">
      {/* Premium Tabs */}
      <div className="flex gap-1 mb-6 border-b border-edge">
        {tabs.map(tab => (
          <button
            key={tab.id}
            onClick={() => setActiveTab(tab.id as any)}
            className={cn(
              'flex items-center gap-2 px-4 py-3 text-body-sm font-medium border-b-2 -mb-px transition-all duration-200',
              activeTab === tab.id
                ? 'border-primary-500 text-primary-700 bg-primary-50/50 dark:bg-primary-800/40 dark:text-neutral-200'
                : 'border-transparent text-neutral-500 hover:text-primary-600 dark:hover:text-primary-200 hover:bg-neutral-50 dark:text-neutral-400 dark:hover:bg-primary-800/50'
            )}
          >
            <tab.icon className={cn('w-4 h-4', activeTab === tab.id ? 'text-primary-600 dark:text-primary-200' : '')} />
            {tab.label}
            {tab.count !== undefined && (
              <span className={cn(
                'px-1.5 py-0.5 text-caption rounded-full font-medium',
                activeTab === tab.id ? 'bg-primary-100 text-primary-700 dark:bg-primary-700 dark:text-neutral-200' : 'bg-surface-muted text-neutral-600 dark:text-neutral-300'
              )}>{tab.count}</span>
            )}
          </button>
        ))}
      </div>

      {/* Overview Tab */}
      {activeTab === 'overview' && (
        <div className="space-y-6">
          {/* Status Banner */}
          <div className={cn(
            'p-4 rounded-lg flex items-center justify-between',
            cycle.status === 'SETTLED' ? 'bg-success-50 dark:bg-success-500/10' : 
            cycle.status === 'APPROVED' ? 'bg-info-50 dark:bg-info-500/10' :
            cycle.status === 'PENDING_APPROVAL' ? 'bg-warning-50 dark:bg-warning-500/10' : 'bg-surface-page'
          )}>
            <div className="flex items-center gap-3">
              <GitMerge className="w-6 h-6 text-primary-600 dark:text-primary-200" />
              <div>
                <p className="font-medium text-neutral-900 dark:text-neutral-50">{cycle.cycleReference}</p>
                <p className="body-sm">
                  {cycle.periodStart?.slice(0, 10)} - {cycle.periodEnd?.slice(0, 10)}
                </p>
              </div>
            </div>
            <Badge variant={cycle.status === 'SETTLED' ? 'success' : 'warning'}>
              {cycle.status?.replace('_', ' ')}
            </Badge>
          </div>

          {/* Key Metrics — Phase 12 Task E: hand-rolled tinted tiles replaced
              by the shared <StatTile> (components/ui/StatTile); tone drives
              the .stat-value-* headline colour. */}
          <div className="grid grid-cols-4 gap-4">
            <StatTile
              tone="neutral"
              label="Gross Volume"
              value={<TileAmount value={cycle.totalGross || 0} currency={cycle.baseCurrency} />}
            />
            <StatTile
              tone="primary"
              label="Net Volume"
              value={<TileAmount value={cycle.totalNet || 0} currency={cycle.baseCurrency} />}
            />
            <StatTile
              tone="success"
              label="Savings"
              value={<TileAmount value={cycle.savingsAmount || 0} currency={cycle.baseCurrency} />}
            />
            <StatTile
              tone="info"
              label="Efficiency"
              value={`${cycle.savingsPercent?.toFixed(1) || 0}%`}
            />
          </div>

          {/* Summary */}
          <div className="grid grid-cols-2 gap-4">
            <Card>
              <div className="p-4">
                <h4 className="label mb-3">Cycle Details</h4>
                <dl className="space-y-2">
                  <div className="flex justify-between">
                    <dt className="body-sm">Entries</dt>
                    <dd className="text-body-sm font-medium">{cycle.entryCount || 0}</dd>
                  </div>
                  <div className="flex justify-between">
                    <dt className="body-sm">Participants</dt>
                    <dd className="text-body-sm font-medium">{cycle.participantCount || 0}</dd>
                  </div>
                  <div className="flex justify-between">
                    <dt className="body-sm">Base Currency</dt>
                    <dd className="text-body-sm font-medium">{cycle.baseCurrency}</dd>
                  </div>
                </dl>
              </div>
            </Card>
            
            <Card>
              <div className="p-4">
                <h4 className="label mb-3">Netting Impact</h4>
                <div className="space-y-2">
                  <div className="flex items-center justify-between">
                    <span className="body-sm">Without Netting</span>
                    <span className="text-body-sm font-medium text-error-600 dark:text-error-300">
                      {positions.length} payments
                    </span>
                  </div>
                  <div className="flex items-center justify-between">
                    <span className="body-sm">With Netting</span>
                    <span className="text-body-sm font-medium text-success-600 dark:text-success-300">
                      {legs.length} transfers
                    </span>
                  </div>
                  <div className="flex items-center justify-between pt-2 border-t">
                    <span className="text-body-sm font-medium">Reduction</span>
                    <span className="text-body-sm font-bold text-success-600 dark:text-success-300">
                      {positions.length > 0 ? Math.round((1 - legs.length / positions.length) * 100) : 0}%
                    </span>
                  </div>
                </div>
              </div>
            </Card>
          </div>
        </div>
      )}

      {/* Entries Tab */}
      {activeTab === 'entries' && (
        <div className="space-y-4">
          <div className="flex justify-between items-center">
            <p className="body-sm">{entries.length} entries in this cycle</p>
            {(cycle.status === 'DRAFT' || cycle.status === 'OPEN') && onPopulate && (
              <Button
                size="sm"
                variant="outline"
                leftIcon={isProcessing ? <Loader2 className="w-4 h-4 animate-spin" /> : <Download className="w-4 h-4" />}
                onClick={() => onPopulate(cycle.id)}
                disabled={isProcessing}
              >
                {entries.length === 0 ? 'Populate Entries' : 'Re-populate'}
              </Button>
            )}
          </div>

          {entries.length === 0 ? (
            <div className="text-center py-12 bg-surface-page rounded-lg">
              <StatusIconBadge tone="neutral" icon={Layers} size="lg" className="mx-auto mb-4" />
              <h4 className="body-lg mb-1">No entries yet</h4>
              <p className="caption mb-4">
                {(cycle.status === 'DRAFT' || cycle.status === 'OPEN')
                  ? 'Click "Populate Entries" to load eligible payables, receivables, and recharges.'
                  : 'This cycle has no entries.'}
              </p>
              {(cycle.status === 'DRAFT' || cycle.status === 'OPEN') && onPopulate && (
                <Button
                  size="sm"
                  leftIcon={isProcessing ? <Loader2 className="w-4 h-4 animate-spin" /> : <Download className="w-4 h-4" />}
                  onClick={() => onPopulate(cycle.id)}
                  disabled={isProcessing}
                >
                  Populate Entries
                </Button>
              )}
            </div>
          ) : (
            <DataTable
              data={entries}
              keyExtractor={(entry) => entry.id}
              columns={[
                { key: 'entryReference', header: 'Reference', minWidth: 150, mobileLabel: true, render: (_v, entry) => <span className="body-strong">{entry.entryReference}</span> },
                { key: 'flowDirection', header: 'Direction', minWidth: 110, dropOrder: 3, render: (_v, entry) => (
                  <Badge variant={entry.flowDirection === 'PAYABLE' ? 'error' : 'success'} size="sm">
                    {entry.flowDirection}
                  </Badge>
                ) },
                { key: 'payerEntityCode', header: 'Payer', minWidth: 110, render: (_v, entry) => <span className="body-sm">{entry.payerEntityCode}</span> },
                { key: 'payeeEntityCode', header: 'Payee', minWidth: 110, render: (_v, entry) => <span className="body-sm">{entry.payeeEntityCode}</span> },
                { key: 'grossAmount', header: 'Amount', align: 'right', minWidth: 140, mobileValue: true, render: (_v, entry) => (
                  <span className="body-strong font-semibold">{formatCurrency(entry.grossAmount, entry.currencyCode)}</span>
                ) },
                { key: 'sourceType', header: 'Source', minWidth: 130, dropOrder: 2, render: (_v, entry) => (
                  <span className="body-sm">{entry.sourceType.replace('_', ' ')}</span>
                ) },
                { key: 'status', header: 'Status', minWidth: 110, dropOrder: 1, render: (_v, entry) => (
                  <Badge variant={entry.status === 'INCLUDED' ? 'success' : 'warning'} size="sm">
                    {entry.status}
                  </Badge>
                ) },
              ]}
            />
          )}
        </div>
      )}

      {/* Positions Tab */}
      {activeTab === 'positions' && (
        <div className="space-y-4">
          <p className="body-sm">Net positions for {positions.length} participating entities</p>

          <div className="space-y-3">
            {positions.map(pos => (
              <Card key={pos.entityId} hover>
                <div className="p-4">
                  <div className="flex items-center justify-between mb-3">
                    <div className="flex items-center gap-3">
                      <StatusIconBadge tone="primary" icon={Building2} />
                      <div>
                        <p className="font-medium text-primary-900 dark:text-neutral-50">{pos.entityName}</p>
                        <p className="body-sm">{pos.entityCode} • {pos.entryCount} entries</p>
                      </div>
                    </div>
                    <Badge
                      variant={pos.netDirection === 'RECEIVE' ? 'success' : pos.netDirection === 'PAY' ? 'error' : 'info'}
                    >
                      {pos.netDirection === 'RECEIVE' ? 'Net Receiver' : pos.netDirection === 'PAY' ? 'Net Payer' : 'Balanced'}
                    </Badge>
                  </div>

                  <div className="grid grid-cols-3 gap-4">
                    <div className="text-center p-3 bg-error-50 rounded-lg border border-error-100 dark:bg-error-500/10 dark:border-error-500/30">
                      <p className="label">Payables</p>
                      <p className="text-body-lg font-semibold text-error-700 mt-1 dark:text-error-300">
                        <TileAmount value={pos.grossPayables} currency={pos.currency} />
                      </p>
                    </div>
                    <div className="text-center p-3 bg-success-50 rounded-lg border border-success-100 dark:bg-success-500/10 dark:border-success-500/30">
                      <p className="label">Receivables</p>
                      <p className="text-body-lg font-semibold text-success-700 mt-1 dark:text-success-300">
                        <TileAmount value={pos.grossReceivables} currency={pos.currency} />
                      </p>
                    </div>
                    <div className={cn(
                      'text-center p-3 rounded-lg border',
                      pos.netDirection === 'RECEIVE' ? 'bg-success-100 border-success-200 dark:bg-success-500/20 dark:border-success-500/30' : 'bg-error-100 border-error-200 dark:bg-error-500/20 dark:border-error-500/30'
                    )}>
                      <p className="label">Net Position</p>
                      <p className={cn(
                        'text-body-lg font-bold mt-1',
                        pos.netDirection === 'RECEIVE' ? 'text-success-700 dark:text-success-300' : 'text-error-700 dark:text-error-300'
                      )}>
                        {pos.netDirection === 'PAY' ? '-' : '+'}
                        <TileAmount value={pos.netPosition} currency={pos.currency} />
                      </p>
                    </div>
                  </div>
                </div>
              </Card>
            ))}
          </div>
        </div>
      )}

      {/* Settlement Tab */}
      {activeTab === 'settlement' && (
        <div className="space-y-4">
          {cycle.settlementIssue && (
            <p className="body-sm text-error-600 dark:text-error-300">Last settlement attempt: {cycle.settlementIssue}</p>
          )}
          {positions.length === 0
            ? <p className="body-sm">Calculate the cycle to see how it settles.</p>
            : <SettlementLegs legs={legs} />}
        </div>
      )}
    </Modal>
  );
};

// ============================================================================
// CYCLE CARD COMPONENT
// ============================================================================

interface CycleCardProps {
  cycle: NettingCycle;
  onViewDetails: () => void;
  onPopulate: () => void;
  onCalculate: () => void;
  onApprove: () => void;
  onSettle: () => void;
  isProcessing: boolean;
  canPopulate?: boolean;
  canApprove?: boolean;
  canSettle?: boolean;
}

const CycleCard: React.FC<CycleCardProps> = ({
  cycle, onViewDetails, onPopulate, onCalculate, onApprove, onSettle, isProcessing,
  canPopulate = false, canApprove = false, canSettle = false
}) => {
  const statusColors: Record<string, string> = {
    DRAFT: 'bg-surface-muted text-neutral-700 dark:text-neutral-200',
    OPEN: 'bg-info-100 text-info-700 dark:bg-info-500/20 dark:text-info-300',
    CALCULATING: 'bg-warning-100 text-warning-700 dark:bg-warning-500/20 dark:text-warning-300',
    PENDING_APPROVAL: 'bg-warning-100 text-warning-700 dark:bg-warning-500/20 dark:text-warning-300',
    APPROVED: 'bg-success-100 text-success-700 dark:bg-success-500/20 dark:text-success-300',
    SETTLED: 'bg-success-100 text-success-700 dark:bg-success-500/20 dark:text-success-300',
  };

  return (
    <Card hover className="group">
      <div className="p-4">
        {/* Premium Gradient Header */}
        <div className="absolute top-0 left-0 right-0 h-1 bg-gradient-to-r from-primary-50/50 via-white to-success-50/50 dark:from-primary-900 dark:via-primary-900 dark:to-primary-900 rounded-t-lg" />

        {/* Header */}
        <div className="flex items-start justify-between mb-4">
          <div>
            <div className="flex items-center gap-2">
              <h3 className="font-semibold text-primary-900 dark:text-neutral-50">{cycle.cycleName}</h3>
              <span className={cn(
                'px-2 py-0.5 text-caption font-medium rounded-full',
                statusColors[cycle.status] || statusColors.DRAFT
              )}>
                {cycle.status?.replace('_', ' ')}
              </span>
            </div>
            <p className="body-sm">{cycle.cycleReference}</p>
          </div>
          <button
            onClick={onViewDetails}
            className="p-2 hover:bg-neutral-100 rounded-lg opacity-0 group-hover:opacity-100 transition-opacity dark:hover:bg-primary-800"
          >
            <Eye className="w-5 h-5 text-neutral-400" />
          </button>
        </div>

        {/* Key Metrics */}
        <div className="grid grid-cols-2 gap-3 mb-4">
          <div className="bg-surface-page rounded-lg p-3">
            <p className="label">Gross</p>
            <p className="text-body-lg font-bold text-primary-900 mt-1 dark:text-neutral-50">
              <TileAmount value={cycle.totalGross || 0} currency={cycle.baseCurrency} />
            </p>
          </div>
          <div className="bg-success-50 rounded-lg p-3 dark:bg-success-500/10">
            <p className="label">Net</p>
            <p className="text-body-lg font-bold text-success-700 mt-1 dark:text-success-300">
              <TileAmount value={cycle.totalNet || 0} currency={cycle.baseCurrency} />
            </p>
          </div>
        </div>

        {/* Savings Banner */}
        {cycle.savingsAmount > 0 && (
          <div className="bg-gradient-to-r from-accent-50 to-accent-100 rounded-lg p-3 mb-4 flex justify-between items-center">
            <div className="flex items-center gap-2">
              <StatusIconBadge tone="accent" icon={TrendingUp} size="sm" />
              <span className="body-sm">Settlement Savings</span>
            </div>
            <span className="font-bold text-accent-700 dark:text-accent-300">
              <TileAmount value={cycle.savingsAmount} currency={cycle.baseCurrency} />
              <span className="text-caption ml-1 text-accent-600 dark:text-accent-300">({cycle.savingsPercent?.toFixed(1)}%)</span>
            </span>
          </div>
        )}

        {/* Details Grid */}
        <div className="grid grid-cols-3 gap-2 text-body-sm mb-4">
          <div className="p-2 bg-neutral-50/50 dark:bg-primary-950/50 rounded-lg">
            <p className="label">Entries</p>
            <p className="font-semibold text-primary-900 mt-0.5 dark:text-neutral-50">{cycle.entryCount || 0}</p>
          </div>
          <div className="p-2 bg-neutral-50/50 dark:bg-primary-950/50 rounded-lg">
            <p className="label">Entities</p>
            <p className="font-semibold text-primary-900 mt-0.5 dark:text-neutral-50">{cycle.participantCount || 0}</p>
          </div>
          <div className="p-2 bg-neutral-50/50 dark:bg-primary-950/50 rounded-lg">
            <p className="label">Period</p>
            <p className="font-semibold text-primary-900 mt-0.5 text-caption dark:text-neutral-50">{cycle.periodStart?.slice(0, 10)}</p>
          </div>
        </div>

        {cycle.settlementIssue && (
          <div className="mb-4 p-3 rounded-lg bg-error-50 dark:bg-error-500/10 flex gap-2 text-body-sm text-error-700 dark:text-error-300">
            <AlertTriangle className="w-4 h-4 shrink-0 mt-0.5" />
            <span>{cycle.settlementIssue}</span>
          </div>
        )}

        {/* Actions */}
        <div className="flex gap-2 pt-3 border-t border-edge-subtle">
          {/* DRAFT: Show Populate button */}
          {cycle.status === 'DRAFT' && canPopulate && (
            <Button size="sm" variant="outline" onClick={onPopulate} disabled={isProcessing} className="flex-1">
              {isProcessing ? <Loader2 className="w-4 h-4 animate-spin" /> : <Download className="w-4 h-4 mr-1" />}
              Populate
            </Button>
          )}
          {/* A cycle with entries is calculated from DRAFT (nothing ever moves one to OPEN). */}
          {(cycle.status === 'DRAFT' || cycle.status === 'OPEN') && (cycle.entryCount || 0) > 0 && (
            <Button size="sm" variant="outline" onClick={onCalculate} disabled={isProcessing} className="flex-1">
              {isProcessing ? <Loader2 className="w-4 h-4 animate-spin" /> : <Calculator className="w-4 h-4 mr-1" />}
              Calculate
            </Button>
          )}
          {cycle.status === 'PENDING_APPROVAL' && canApprove && (
            <Button size="sm" onClick={onApprove} disabled={isProcessing} className="flex-1">
              {isProcessing ? <Loader2 className="w-4 h-4 animate-spin" /> : <CheckCircle className="w-4 h-4 mr-1" />}
              Approve
            </Button>
          )}
          {cycle.status === 'APPROVED' && canSettle && (
            <Button size="sm" onClick={onSettle} disabled={isProcessing} className="flex-1">
              {isProcessing ? <Loader2 className="w-4 h-4 animate-spin" /> : <DollarSign className="w-4 h-4 mr-1" />}
              Settle…
            </Button>
          )}
          <Button size="sm" variant="ghost" onClick={onViewDetails} className="opacity-70 group-hover:opacity-100 transition-opacity">
            Details
          </Button>
        </div>
      </div>
    </Card>
  );
};

// ============================================================================
// MAIN PAGE COMPONENT
// ============================================================================

const EnhancedNettingCyclesPage: React.FC = () => {
  const { canApproveNettingCycle, canSettleNettingCycle, isTreasury } = usePermissions();

  const [corporates, setCorporates] = useState<Corporate[]>([]);
  const [selectedCorporateId, setSelectedCorporateId] = useState('');
  const [settlingCycle, setSettlingCycle] = useState<NettingCycle | null>(null);
  const [cycles, setCycles] = useState<NettingCycle[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [selectedCycle, setSelectedCycle] = useState<NettingCycle | null>(null);
  const [showDetailModal, setShowDetailModal] = useState(false);
  const [processingCycleId, setProcessingCycleId] = useState<string | null>(null);
  const [filterStatus, setFilterStatus] = useState<string>('all');

  useEffect(() => {
    corporatesApi.getAll(0, 100)
      .then(r => {
        const corps = r.data || [];
        setCorporates(corps);
        setSelectedCorporateId(prev => prev || corps[0]?.id || '');
        if (corps.length === 0) setLoading(false);
      })
      .catch(() => { setCorporates([]); setLoading(false); });
  }, []);

  // The selected corporate's cycles only (spec R7).
  const fetchCycles = useCallback(async () => {
    if (!selectedCorporateId) return;
    try {
      setLoading(true);
      setError(null);
      const response = await nettingApi.getAllCycles(selectedCorporateId);
      if (response.success) {
        setCycles(response.data || []);
      }
    } catch (err: any) {
      setError(err.response?.data?.message || err.message || 'Failed to load netting cycles');
    } finally {
      setLoading(false);
    }
  }, [selectedCorporateId]);

  useEffect(() => {
    fetchCycles();
  }, [fetchCycles]);

  // Opened on one cycle (Intercompany "Add to netting" hands off here): show that cycle's details.
  const { params } = useNavigation();
  useEffect(() => {
    if (!params.cycleId) return;
    let current = true;
    nettingApi.getCycleById(params.cycleId).then(r => {
      if (current && r.success) {
        if (r.data.corporateId) setSelectedCorporateId(r.data.corporateId);
        setSelectedCycle(r.data);
        setShowDetailModal(true);
      }
    }).catch(() => { /* the list still shows every cycle */ });
    return () => { current = false; };
  }, [params.cycleId]);

  const handleCreate = async (data: any) => {
    const response = await nettingApi.createCycle(data);
    if (response.success) {
      const createdCycle = response.data;

      // If autoPopulate is checked, populate the cycle with obligations
      if (data.autoPopulate && createdCycle.id) {
        try {
          const populateResponse = await nettingApi.populateCycle(
            createdCycle.id,
            data.corporateId,
            data.includePending || false
          );
          if (populateResponse.success) {
            // Refresh to get the updated cycle with entries
            await fetchCycles();
            return;
          }
        } catch (err) {
          console.error('Failed to populate cycle:', err);
        }
      }

      setCycles(prev => [createdCycle, ...prev]);
    }
  };

  // One wrapper for the cycle actions: a refusal from the API is shown, not swallowed.
  const act = async (cycleId: string, action: () => Promise<unknown>) => {
    setProcessingCycleId(cycleId);
    try {
      await action();
    } catch (e: any) {
      toast.error(e.response?.data?.message || e.message || 'The action failed');
    } finally {
      await fetchCycles();
      setProcessingCycleId(null);
    }
  };
  const handleCalculate = (cycleId: string) => act(cycleId, () => nettingApi.calculateNetting(cycleId));
  const handleApprove = (cycleId: string) => act(cycleId, () => nettingApi.approveCycle(cycleId));
  // The cycle's own corporate decides what populate takes.
  const handlePopulate = (cycleId: string) => act(cycleId, () => nettingApi.populateCycle(cycleId, undefined, false));

  const handleViewDetails = (cycle: NettingCycle) => {
    setSelectedCycle(cycle);
    setShowDetailModal(true);
  };

  // Filter cycles
  const filteredCycles = filterStatus === 'all' 
    ? cycles 
    : cycles.filter(c => c.status === filterStatus);

  // Stats
  const stats = {
    total: cycles.length,
    draft: cycles.filter(c => c.status === 'DRAFT').length,
    open: cycles.filter(c => c.status === 'OPEN').length,
    pending: cycles.filter(c => c.status === 'PENDING_APPROVAL').length,
    settled: cycles.filter(c => c.status === 'SETTLED').length,
    totalSavings: cycles
      .filter(c => c.status === 'SETTLED')
      .reduce((sum, c) => sum + (c.savingsAmount || 0), 0),
  };

  // Page toolbar actions in the Layout header.
  usePageHeaderActions(
    () => (
      <>
        <Button variant="outline" size="sm" leftIcon={<RefreshCw className="w-4 h-4" />} onClick={fetchCycles}>
          Refresh
        </Button>
        <TreasuryOnly>
          <Button size="sm" leftIcon={<Plus className="w-4 h-4" />} onClick={() => setShowCreateModal(true)}>
            New Cycle
          </Button>
        </TreasuryOnly>
      </>
    ),
    [fetchCycles]
  );

  const scope = (
    <ScopeSelector
      mode="corporate-only"
      requireSelection
      corporates={corporates}
      selectedCorporateId={selectedCorporateId}
      onCorporateChange={setSelectedCorporateId}
      loading={loading}
    />
  );

  if (loading) {
    return (
      <div className="space-y-4">
        <PageHeader title="Netting Cycles" description="Multilateral intercompany netting management" />
        {scope}
        <LoadingSpinner />
      </div>
    );
  }

  if (error) {
    return (
      <div className="space-y-4">
        <PageHeader title="Netting Cycles" description="Multilateral intercompany netting management" />
        {scope}
        <ErrorMessage message={error} onRetry={fetchCycles} />
      </div>
    );
  }

  return (
    <Page>
      {/* Title + Quick Actions migrated to Aperture Layout header. */}
      {scope}

      {/* Stats Cards */}
      <StatStrip>
        <StatTile tone="primary" icon={<GitMerge className="w-5 h-5" />} label="Total" value={stats.total} delay="0.15s" />
        <StatTile tone="info" icon={<Layers className="w-5 h-5" />} label="Open" value={stats.open} delay="0.2s" />
        <StatTile tone="warning" icon={<Clock className="w-5 h-5" />} label="Pending" value={stats.pending} delay="0.25s" />
        <StatTile tone="success" icon={<CheckCircle className="w-5 h-5" />} label="Settled" value={stats.settled} delay="0.3s" />
        <StatTile tone="accent" icon={<TrendingUp className="w-5 h-5" />} label="Savings" value={<TileAmount value={stats.totalSavings} currency="AED" />} delay="0.35s" />
      </StatStrip>

      {/* Filter */}
      <div className="flex items-center gap-2 animate-fade-in" style={{ animationDelay: '0.4s' }}>
        <Filter className="w-4 h-4 text-neutral-400" />
        <select
          value={filterStatus}
          onChange={(e) => setFilterStatus(e.target.value)}
          className="text-body-sm border border-edge rounded-lg px-3 py-1.5 focus:ring-2 focus:ring-primary-500/20 focus:border-primary-300"
        >
          <option value="all">All Cycles</option>
          <option value="DRAFT">Draft</option>
          <option value="OPEN">Open</option>
          <option value="PENDING_APPROVAL">Pending Approval</option>
          <option value="APPROVED">Approved</option>
          <option value="SETTLED">Settled</option>
        </select>
        <span className="body-sm ml-2">
          {filteredCycles.length} cycle{filteredCycles.length !== 1 ? 's' : ''}
        </span>
      </div>

      {/* Cycles Grid */}
      {filteredCycles.length === 0 ? (
        <Card className="text-center py-12 animate-fade-in" style={{ animationDelay: '0.45s' }}>
          <StatusIconBadge tone="neutral" icon={GitMerge} size="xl" className="mx-auto mb-4" />
          <h3 className="section-title mb-2">No Netting Cycles</h3>
          <p className="text-neutral-500 mb-6 dark:text-neutral-400">
            Create your first netting cycle to consolidate intercompany settlements
          </p>
          <TreasuryOnly>
            <Button onClick={() => setShowCreateModal(true)} leftIcon={<Plus className="w-4 h-4" />}>
              Create Cycle
            </Button>
          </TreasuryOnly>
        </Card>
      ) : (
        <div className="grid gap-4 grid-cols-3">
          {filteredCycles.map((cycle, i) => (
            <div key={cycle.id} className="animate-fade-in" style={{ animationDelay: `${0.45 + i * 0.05}s` }}>
              <CycleCard
                cycle={cycle}
                onViewDetails={() => handleViewDetails(cycle)}
                onPopulate={() => handlePopulate(cycle.id)}
                onCalculate={() => handleCalculate(cycle.id)}
                onApprove={() => handleApprove(cycle.id)}
                onSettle={() => setSettlingCycle(cycle)}
                isProcessing={processingCycleId === cycle.id}
                canPopulate={isTreasury}
                canApprove={canApproveNettingCycle}
                canSettle={canSettleNettingCycle}
              />
            </div>
          ))}
        </div>
      )}

      {/* Modals */}
      <CreateCycleModal 
        isOpen={showCreateModal} 
        onClose={() => setShowCreateModal(false)} 
        onSave={handleCreate}
        corporate={corporates.find(c => c.id === selectedCorporateId)} 
      />
      <SettlementPreviewModal
        cycle={settlingCycle}
        canSettle={canSettleNettingCycle}
        onClose={() => setSettlingCycle(null)}
        onDone={fetchCycles}
      />
      <CycleDetailModal
        isOpen={showDetailModal}
        onClose={() => setShowDetailModal(false)}
        cycle={selectedCycle}
        onPopulate={async (cycleId) => {
          await handlePopulate(cycleId);
          // Refresh the modal data by re-selecting the cycle
          const response = await nettingApi.getCycleById(cycleId);
          if (response.success) {
            setSelectedCycle(response.data);
          }
        }}
        isProcessing={!!processingCycleId}
      />
    </Page>
  );
};

export default EnhancedNettingCyclesPage;