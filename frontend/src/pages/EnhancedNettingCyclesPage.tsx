import React, { useState, useEffect, useCallback } from 'react';
import {
  GitMerge, Plus, Calculator, CheckCircle, Clock, AlertCircle, Loader2,
  RefreshCw, DollarSign, TrendingUp, Eye, FileText, Users, ArrowRight,
  ArrowLeftRight, Building2, X, ChevronDown, ChevronUp, Filter, Download,
  BarChart3, Layers, Target, Send,
} from 'lucide-react';
import { Card, Button, Badge, Input , StatusIconBadge, StatTile } from '../components/ui';
import { CurrencyPicker } from '../components/ui/CurrencyPicker';
import { Modal } from '../components/ui/enhanced';
import { formatCompactCurrency, formatCurrency, cn } from '../utils';
import { nettingApi, NettingCycle, ApiResponse, corporatesApi, Corporate } from '../services/api';
import { useUser } from '../context/UserContext';
import { usePermissions } from '../hooks/usePermissions';
import { TreasuryOnly, PermissionGate } from '../components/permissions';
import { usePageHeaderActions } from '../context/PageHeaderContext';
import { Page } from '../components/layout/Page';
import { PageHeader } from '../components/layout/PageHeader';
import { StatStrip } from '../components/layout/StatStrip';

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

interface SettlementInstruction {
  id: string;
  fromEntityCode: string;
  fromEntityName: string;
  toEntityCode: string;
  toEntityName: string;
  amount: number;
  currency: string;
  status: string;
}

// ============================================================================
// LOADING & ERROR COMPONENTS
// ============================================================================

const LoadingSpinner: React.FC = () => (
  <div className="flex items-center justify-center py-12 animate-fade-in">
    <div className="w-12 h-12 rounded-xl bg-primary-100 flex items-center justify-center dark:bg-primary-700">
      <Loader2 className="w-6 h-6 animate-spin text-primary-600 dark:text-primary-200" />
    </div>
    <span className="ml-3 text-neutral-600 font-medium dark:text-neutral-300">Loading netting cycles...</span>
  </div>
);

const ErrorMessage: React.FC<{ message: string; onRetry: () => void }> = ({ message, onRetry }) => (
  <Card className="bg-error-50 border-error-200 animate-fade-in dark:bg-error-500/10 dark:border-error-500/30">
    <div className="flex items-center gap-3 p-4">
      <StatusIconBadge tone="error" icon={AlertCircle} className="dark:bg-error-500/20" />
      <div className="flex-1">
        <p className="font-medium text-error-800 dark:text-error-300">Failed to load data</p>
        <p className="text-sm text-error-600 dark:text-error-300">{message}</p>
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
}

const CreateCycleModal: React.FC<CreateCycleModalProps> = ({ isOpen, onClose, onSave }) => {
  const [saving, setSaving] = useState(false);
  const [corporates, setCorporates] = useState<Corporate[]>([]);
  const [loadingCorporates, setLoadingCorporates] = useState(true);
  const today = new Date().toISOString().split('T')[0];
  const nextMonth = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString().split('T')[0];

  const [formData, setFormData] = useState({
    cycleName: '',
    corporateId: '',
    periodStart: today,
    periodEnd: nextMonth,
    settlementDate: '',
    baseCurrency: 'AED',
    autoPopulate: true,
    includePending: false, // Include PENDING recharges for testing
  });

  // Load corporates on mount
  useEffect(() => {
    const loadCorporates = async () => {
      try {
        const response = await corporatesApi.getAll();
        if (response.success && response.data) {
          setCorporates(response.data);
          if (response.data.length > 0) {
            setFormData(prev => ({ ...prev, corporateId: response.data[0].id }));
          }
        }
      } catch (err) {
        console.error('Failed to load corporates:', err);
      } finally {
        setLoadingCorporates(false);
      }
    };
    if (isOpen) {
      loadCorporates();
    }
  }, [isOpen]);

  const handleSubmit = async () => {
    setSaving(true);
    try {
      await onSave(formData);
      onClose();
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="Create Netting Cycle" size="md">
      <div className="space-y-4">
        {/* Corporate Picker */}
        <div>
          <label className="field-label block mb-1">Corporate</label>
          {loadingCorporates ? (
            <div className="flex items-center gap-2 py-2 text-neutral-500 dark:text-neutral-400">
              <Loader2 className="w-4 h-4 animate-spin" />
              Loading corporates...
            </div>
          ) : (
            <select
              className="w-full border rounded-lg px-3 py-2 focus:ring-2 focus:ring-primary-500"
              value={formData.corporateId}
              onChange={(e) => setFormData({ ...formData, corporateId: e.target.value })}
            >
              <option value="">All Corporates</option>
              {corporates.map(corp => (
                <option key={corp.id} value={corp.id}>{corp.legalName}</option>
              ))}
            </select>
          )}
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
          <div className="flex items-center gap-2">
            <input
              type="checkbox"
              id="autoPopulate"
              checked={formData.autoPopulate}
              onChange={(e) => setFormData({ ...formData, autoPopulate: e.target.checked })}
              className="rounded text-primary-600 dark:text-primary-200"
            />
            <label htmlFor="autoPopulate" className="text-sm text-neutral-700 dark:text-neutral-200">
              Auto-populate with eligible intercompany payables, receivables, and recharges
            </label>
          </div>
          {formData.autoPopulate && (
            <div className="flex items-center gap-2 ml-6">
              <input
                type="checkbox"
                id="includePending"
                checked={formData.includePending}
                onChange={(e) => setFormData({ ...formData, includePending: e.target.checked })}
                className="rounded text-warning-600 dark:text-warning-300"
              />
              <label htmlFor="includePending" className="text-sm text-neutral-600 dark:text-neutral-300">
                Include pending (unapproved) POBO recharges
              </label>
            </div>
          )}
        </div>
      </div>
      
      <div className="flex justify-end gap-2 mt-6 pt-4 border-t">
        <Button variant="ghost" onClick={onClose}>Cancel</Button>
        <Button onClick={handleSubmit} disabled={saving || !formData.cycleName}>
          {saving && <Loader2 className="w-4 h-4 animate-spin mr-2" />}
          Create Cycle
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
  const [loading, setLoading] = useState(false);
  const [entries, setEntries] = useState<NettingEntry[]>([]);
  const [positions, setPositions] = useState<NettingPosition[]>([]);
  const [settlements, setSettlements] = useState<SettlementInstruction[]>([]);

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

              // Create settlement instructions from positions
              const payingEntities = cycleData.settlements.filter(s => s.settlementDirection === 'PAY');
              const receivingEntities = cycleData.settlements.filter(s => s.settlementDirection === 'RECEIVE');

              const instructions: SettlementInstruction[] = [];
              payingEntities.forEach(payer => {
                receivingEntities.forEach(receiver => {
                  if (payer.netPosition !== 0) {
                    instructions.push({
                      id: `${payer.entityId}-${receiver.entityId}`,
                      fromEntityCode: payer.entityCode,
                      fromEntityName: payer.entityName,
                      toEntityCode: receiver.entityCode,
                      toEntityName: receiver.entityName,
                      amount: Math.abs(payer.netPosition),
                      currency: cycle.baseCurrency || 'AED',
                      status: payer.settlementStatus === 'SETTLED' ? 'EXECUTED' : 'PENDING',
                    });
                  }
                });
              });
              setSettlements(instructions);
            } else {
              setPositions([]);
              setSettlements([]);
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
    { id: 'settlement', label: 'Settlement', icon: Send, count: settlements.length },
  ];

  return (
    <Modal isOpen={isOpen} onClose={onClose} title={cycle.cycleName} size="xl">
      {/* Premium Tabs */}
      <div className="flex gap-1 mb-6 border-b border-neutral-200 dark:border-primary-800">
        {tabs.map(tab => (
          <button
            key={tab.id}
            onClick={() => setActiveTab(tab.id as any)}
            className={cn(
              'flex items-center gap-2 px-4 py-3 text-sm font-medium border-b-2 -mb-px transition-all duration-200',
              activeTab === tab.id
                ? 'border-primary-500 text-primary-700 bg-primary-50/50 dark:text-neutral-200'
                : 'border-transparent text-neutral-500 hover:text-primary-600 hover:bg-neutral-50 dark:text-neutral-400 dark:hover:bg-primary-800/50'
            )}
          >
            <tab.icon className={cn('w-4 h-4', activeTab === tab.id ? 'text-primary-600 dark:text-primary-200' : '')} />
            {tab.label}
            {tab.count !== undefined && (
              <span className={cn(
                'px-1.5 py-0.5 text-xs rounded-full font-medium',
                activeTab === tab.id ? 'bg-primary-100 text-primary-700 dark:bg-primary-700 dark:text-neutral-200' : 'bg-neutral-100 text-neutral-600 dark:bg-primary-800 dark:text-neutral-300'
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
            cycle.status === 'PENDING_APPROVAL' ? 'bg-warning-50 dark:bg-warning-500/10' : 'bg-neutral-50 dark:bg-primary-950'
          )}>
            <div className="flex items-center gap-3">
              <GitMerge className="w-6 h-6 text-primary-600 dark:text-primary-200" />
              <div>
                <p className="font-medium text-neutral-900 dark:text-neutral-50">{cycle.cycleReference}</p>
                <p className="text-sm text-neutral-600 dark:text-neutral-300">
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
              value={formatCompactCurrency(cycle.totalGross || 0, cycle.baseCurrency)}
            />
            <StatTile
              tone="primary"
              label="Net Volume"
              value={formatCompactCurrency(cycle.totalNet || 0, cycle.baseCurrency)}
            />
            <StatTile
              tone="success"
              label="Savings"
              value={formatCompactCurrency(cycle.savingsAmount || 0, cycle.baseCurrency)}
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
                    <dt className="text-sm text-neutral-500 dark:text-neutral-400">Entries</dt>
                    <dd className="text-sm font-medium">{cycle.entryCount || 0}</dd>
                  </div>
                  <div className="flex justify-between">
                    <dt className="text-sm text-neutral-500 dark:text-neutral-400">Participants</dt>
                    <dd className="text-sm font-medium">{cycle.participantCount || 0}</dd>
                  </div>
                  <div className="flex justify-between">
                    <dt className="text-sm text-neutral-500 dark:text-neutral-400">Base Currency</dt>
                    <dd className="text-sm font-medium">{cycle.baseCurrency}</dd>
                  </div>
                </dl>
              </div>
            </Card>
            
            <Card>
              <div className="p-4">
                <h4 className="label mb-3">Netting Impact</h4>
                <div className="space-y-2">
                  <div className="flex items-center justify-between">
                    <span className="text-sm text-neutral-500 dark:text-neutral-400">Without Netting</span>
                    <span className="text-sm font-medium text-error-600 dark:text-error-300">
                      {positions.length} payments
                    </span>
                  </div>
                  <div className="flex items-center justify-between">
                    <span className="text-sm text-neutral-500 dark:text-neutral-400">With Netting</span>
                    <span className="text-sm font-medium text-success-600 dark:text-success-300">
                      {settlements.length} payments
                    </span>
                  </div>
                  <div className="flex items-center justify-between pt-2 border-t">
                    <span className="text-sm font-medium">Reduction</span>
                    <span className="text-sm font-bold text-success-600 dark:text-success-300">
                      {positions.length > 0 ? Math.round((1 - settlements.length / positions.length) * 100) : 0}%
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
            <p className="text-sm text-neutral-600 dark:text-neutral-300">{entries.length} entries in this cycle</p>
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
            <div className="text-center py-12 bg-neutral-50 rounded-xl dark:bg-primary-950">
              <StatusIconBadge tone="neutral" icon={Layers} size="lg" className="mx-auto mb-4 dark:bg-primary-800" />
              <h4 className="body-lg mb-1">No entries yet</h4>
              <p className="text-xs text-neutral-500 mb-4 dark:text-neutral-400">
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
            <div className="border rounded-xl overflow-hidden">
              <table className="data-table">
                <thead className="data-table-header">
                  <tr>
                    <th className="data-table-header-cell">Reference</th>
                    <th className="data-table-header-cell">Direction</th>
                    <th className="data-table-header-cell">Payer</th>
                    <th className="data-table-header-cell">Payee</th>
                    <th className="data-table-header-cell text-right">Amount</th>
                    <th className="data-table-header-cell">Source</th>
                    <th className="data-table-header-cell">Status</th>
                  </tr>
                </thead>
                <tbody>
                  {entries.map(entry => (
                    <tr key={entry.id} className="data-table-row">
                      <td className="data-table-cell text-sm font-medium text-primary-900 dark:text-neutral-50">{entry.entryReference}</td>
                      <td className="data-table-cell">
                        <Badge variant={entry.flowDirection === 'PAYABLE' ? 'error' : 'success'} size="sm">
                          {entry.flowDirection}
                        </Badge>
                      </td>
                      <td className="data-table-cell text-sm text-neutral-600 dark:text-neutral-300">{entry.payerEntityCode}</td>
                      <td className="data-table-cell text-sm text-neutral-600 dark:text-neutral-300">{entry.payeeEntityCode}</td>
                      <td className="data-table-cell text-sm font-semibold text-right text-primary-900 dark:text-neutral-50">
                        {formatCurrency(entry.grossAmount, entry.currencyCode)}
                      </td>
                      <td className="data-table-cell text-sm text-neutral-500 dark:text-neutral-400">
                        {entry.sourceType.replace('_', ' ')}
                      </td>
                      <td className="data-table-cell">
                        <Badge variant={entry.status === 'INCLUDED' ? 'success' : 'warning'} size="sm">
                          {entry.status}
                        </Badge>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {/* Positions Tab */}
      {activeTab === 'positions' && (
        <div className="space-y-4">
          <p className="text-sm text-neutral-600 dark:text-neutral-300">Net positions for {positions.length} participating entities</p>

          <div className="space-y-3">
            {positions.map(pos => (
              <Card key={pos.entityId} hover>
                <div className="p-4">
                  <div className="flex items-center justify-between mb-3">
                    <div className="flex items-center gap-3">
                      <StatusIconBadge tone="primary" icon={Building2} className="dark:bg-primary-700" />
                      <div>
                        <p className="font-medium text-primary-900 dark:text-neutral-50">{pos.entityName}</p>
                        <p className="text-sm text-neutral-500 dark:text-neutral-400">{pos.entityCode} • {pos.entryCount} entries</p>
                      </div>
                    </div>
                    <Badge
                      variant={pos.netDirection === 'RECEIVE' ? 'success' : pos.netDirection === 'PAY' ? 'error' : 'info'}
                    >
                      {pos.netDirection === 'RECEIVE' ? 'Net Receiver' : pos.netDirection === 'PAY' ? 'Net Payer' : 'Balanced'}
                    </Badge>
                  </div>

                  <div className="grid grid-cols-3 gap-4">
                    <div className="text-center p-3 bg-error-50 rounded-xl border border-error-100 dark:bg-error-500/10 dark:border-error-500/30">
                      <p className="label">Payables</p>
                      <p className="text-lg font-semibold text-error-700 mt-1 dark:text-error-300">
                        {formatCompactCurrency(pos.grossPayables, pos.currency)}
                      </p>
                    </div>
                    <div className="text-center p-3 bg-success-50 rounded-xl border border-success-100 dark:bg-success-500/10 dark:border-success-500/30">
                      <p className="label">Receivables</p>
                      <p className="text-lg font-semibold text-success-700 mt-1 dark:text-success-300">
                        {formatCompactCurrency(pos.grossReceivables, pos.currency)}
                      </p>
                    </div>
                    <div className={cn(
                      'text-center p-3 rounded-xl border',
                      pos.netDirection === 'RECEIVE' ? 'bg-success-100 border-success-200 dark:bg-success-500/20 dark:border-success-500/30' : 'bg-error-100 border-error-200 dark:bg-error-500/20 dark:border-error-500/30'
                    )}>
                      <p className="label">Net Position</p>
                      <p className={cn(
                        'text-lg font-bold mt-1',
                        pos.netDirection === 'RECEIVE' ? 'text-success-700 dark:text-success-300' : 'text-error-700 dark:text-error-300'
                      )}>
                        {pos.netDirection === 'PAY' ? '-' : '+'}
                        {formatCompactCurrency(pos.netPosition, pos.currency)}
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
          <div className="p-4 bg-gradient-to-r from-info-50/50 via-white to-accent-50/50 dark:from-primary-900 dark:via-primary-900 dark:to-primary-900 rounded-xl border border-info-200/60">
            <div className="flex items-center gap-3">
              <StatusIconBadge tone="info" icon={TrendingUp} className="dark:bg-info-500/20" />
              <p className="text-sm text-info-800 dark:text-info-300">
                <strong>{settlements.length}</strong> settlement payments required to clear all positions.
                This represents a <strong className="text-success-700 dark:text-success-300">{positions.length > 0 ? Math.round((1 - settlements.length / positions.length) * 100) : 0}%</strong> reduction
                from individual bilateral settlements.
              </p>
            </div>
          </div>

          <div className="space-y-3">
            {settlements.map(instruction => (
              <Card key={instruction.id} hover className="group">
                <div className="p-4 flex items-center justify-between">
                  <div className="flex items-center gap-4">
                    <div className="flex items-center gap-3">
                      <StatusIconBadge tone="error" icon={Building2} className="dark:bg-error-500/20" />
                      <div>
                        <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{instruction.fromEntityCode}</p>
                        <p className="text-xs text-neutral-500 dark:text-neutral-400">{instruction.fromEntityName}</p>
                      </div>
                    </div>
                    <ArrowRight className="w-5 h-5 text-primary-400" />
                    <div className="flex items-center gap-3">
                      <StatusIconBadge tone="success" icon={Building2} className="dark:bg-success-500/20" />
                      <div>
                        <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{instruction.toEntityCode}</p>
                        <p className="text-xs text-neutral-500 dark:text-neutral-400">{instruction.toEntityName}</p>
                      </div>
                    </div>
                  </div>
                  <div className="flex items-center gap-4">
                    <div className="text-right">
                      <p className="stat-value-xs">
                        {formatCurrency(instruction.amount, instruction.currency)}
                      </p>
                    </div>
                    <Badge variant={instruction.status === 'EXECUTED' ? 'success' : 'warning'}>
                      {instruction.status}
                    </Badge>
                  </div>
                </div>
              </Card>
            ))}
          </div>
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
    DRAFT: 'bg-neutral-100 text-neutral-700 dark:bg-primary-800 dark:text-neutral-200',
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
        <div className="absolute top-0 left-0 right-0 h-1 bg-gradient-to-r from-primary-50/50 via-white to-success-50/50 dark:from-primary-900 dark:via-primary-900 dark:to-primary-900 rounded-t-xl" />

        {/* Header */}
        <div className="flex items-start justify-between mb-4">
          <div>
            <div className="flex items-center gap-2">
              <h3 className="font-semibold text-primary-900 dark:text-neutral-50">{cycle.cycleName}</h3>
              <span className={cn(
                'px-2 py-0.5 text-xs font-medium rounded-full',
                statusColors[cycle.status] || statusColors.DRAFT
              )}>
                {cycle.status?.replace('_', ' ')}
              </span>
            </div>
            <p className="text-sm text-neutral-500 dark:text-neutral-400">{cycle.cycleReference}</p>
          </div>
          <button
            onClick={onViewDetails}
            className="p-2 hover:bg-neutral-100 rounded-xl opacity-0 group-hover:opacity-100 transition-opacity dark:hover:bg-primary-800"
          >
            <Eye className="w-5 h-5 text-neutral-400 dark:text-neutral-500" />
          </button>
        </div>

        {/* Key Metrics */}
        <div className="grid grid-cols-2 gap-3 mb-4">
          <div className="bg-neutral-50 rounded-xl p-3 dark:bg-primary-950">
            <p className="text-xs text-neutral-500 uppercase tracking-wider dark:text-neutral-400">Gross</p>
            <p className="text-lg font-bold text-primary-900 mt-1 dark:text-neutral-50">
              {formatCompactCurrency(cycle.totalGross || 0, cycle.baseCurrency)}
            </p>
          </div>
          <div className="bg-success-50 rounded-xl p-3 dark:bg-success-500/10">
            <p className="text-xs text-neutral-500 uppercase tracking-wider dark:text-neutral-400">Net</p>
            <p className="text-lg font-bold text-success-700 mt-1 dark:text-success-300">
              {formatCompactCurrency(cycle.totalNet || 0, cycle.baseCurrency)}
            </p>
          </div>
        </div>

        {/* Savings Banner */}
        {cycle.savingsAmount > 0 && (
          <div className="bg-gradient-to-r from-accent-50 to-accent-100 rounded-xl p-3 mb-4 flex justify-between items-center">
            <div className="flex items-center gap-2">
              <div className="w-8 h-8 rounded-lg bg-accent-200 flex items-center justify-center">
                <TrendingUp className="w-4 h-4 text-accent-700 dark:text-accent-300" />
              </div>
              <span className="body-sm">Settlement Savings</span>
            </div>
            <span className="font-bold text-accent-700 dark:text-accent-300">
              {formatCompactCurrency(cycle.savingsAmount, cycle.baseCurrency)}
              <span className="text-xs ml-1 text-accent-600 dark:text-accent-300">({cycle.savingsPercent?.toFixed(1)}%)</span>
            </span>
          </div>
        )}

        {/* Details Grid */}
        <div className="grid grid-cols-3 gap-2 text-sm mb-4">
          <div className="p-2 bg-neutral-50/50 rounded-lg">
            <p className="text-xs text-neutral-500 uppercase tracking-wider dark:text-neutral-400">Entries</p>
            <p className="font-semibold text-primary-900 mt-0.5 dark:text-neutral-50">{cycle.entryCount || 0}</p>
          </div>
          <div className="p-2 bg-neutral-50/50 rounded-lg">
            <p className="text-xs text-neutral-500 uppercase tracking-wider dark:text-neutral-400">Entities</p>
            <p className="font-semibold text-primary-900 mt-0.5 dark:text-neutral-50">{cycle.participantCount || 0}</p>
          </div>
          <div className="p-2 bg-neutral-50/50 rounded-lg">
            <p className="text-xs text-neutral-500 uppercase tracking-wider dark:text-neutral-400">Period</p>
            <p className="font-semibold text-primary-900 mt-0.5 text-xs dark:text-neutral-50">{cycle.periodStart?.slice(0, 10)}</p>
          </div>
        </div>

        {/* Actions */}
        <div className="flex gap-2 pt-3 border-t border-neutral-100 dark:border-primary-800/60">
          {/* DRAFT: Show Populate button */}
          {cycle.status === 'DRAFT' && canPopulate && (
            <Button size="sm" variant="outline" onClick={onPopulate} disabled={isProcessing} className="flex-1">
              {isProcessing ? <Loader2 className="w-4 h-4 animate-spin" /> : <Download className="w-4 h-4 mr-1" />}
              Populate
            </Button>
          )}
          {/* OPEN: Show Calculate button */}
          {cycle.status === 'OPEN' && (
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
              Settle
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
  // User context and permissions
  const { currentEntity } = useUser();
  const { canCreateNettingCycle, canApproveNettingCycle, canSettleNettingCycle, isTreasury } = usePermissions();
  const approverName = currentEntity?.entityCode || currentEntity?.entityName || 'System';

  const [cycles, setCycles] = useState<NettingCycle[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [selectedCycle, setSelectedCycle] = useState<NettingCycle | null>(null);
  const [showDetailModal, setShowDetailModal] = useState(false);
  const [processingCycleId, setProcessingCycleId] = useState<string | null>(null);
  const [filterStatus, setFilterStatus] = useState<string>('all');

  const fetchCycles = useCallback(async () => {
    try {
      setLoading(true);
      setError(null);
      const response = await nettingApi.getAllCycles();
      if (response.success) {
        setCycles(response.data || []);
      }
    } catch (err: any) {
      setError(err.message || 'Failed to load netting cycles');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchCycles();
  }, [fetchCycles]);

  const handleCreate = async (data: any) => {
    const response = await nettingApi.createCycle(data);
    if (response.success) {
      const createdCycle = response.data;

      // If autoPopulate is checked, populate the cycle with obligations
      if (data.autoPopulate && createdCycle.id) {
        try {
          const populateResponse = await nettingApi.populateCycle(
            createdCycle.id,
            data.corporateId || undefined,
            data.includePending || false
          );
          if (populateResponse.success) {
            console.log(`Populated cycle with ${populateResponse.data.totalAdded} entries:`,
              `${populateResponse.data.payablesAdded} payables,`,
              `${populateResponse.data.receivablesAdded} receivables,`,
              `${populateResponse.data.poboRechargesAdded || 0} POBO recharges,`,
              `${populateResponse.data.coboRechargesAdded || 0} COBO recharges`);
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

  const handleCalculate = async (cycleId: string) => {
    setProcessingCycleId(cycleId);
    try {
      await nettingApi.calculateNetting(cycleId);
      await fetchCycles();
    } finally {
      setProcessingCycleId(null);
    }
  };

  const handleApprove = async (cycleId: string) => {
    setProcessingCycleId(cycleId);
    try {
      await nettingApi.approveCycle(cycleId, approverName);
      await fetchCycles();
    } finally {
      setProcessingCycleId(null);
    }
  };

  const handleSettle = async (cycleId: string) => {
    setProcessingCycleId(cycleId);
    try {
      await nettingApi.settleCycle(cycleId);
      await fetchCycles();
    } finally {
      setProcessingCycleId(null);
    }
  };

  const handlePopulate = async (cycleId: string) => {
    setProcessingCycleId(cycleId);
    try {
      const response = await nettingApi.populateCycle(cycleId, undefined, false);
      if (response.success) {
        console.log(`Populated cycle with ${response.data.totalAdded} entries:`,
          `${response.data.payablesAdded} payables,`,
          `${response.data.receivablesAdded} receivables,`,
          `${response.data.poboRechargesAdded || 0} POBO recharges,`,
          `${response.data.coboRechargesAdded || 0} COBO recharges`);
      }
      await fetchCycles();
    } finally {
      setProcessingCycleId(null);
    }
  };

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

  if (loading) {
    return (
      <div className="space-y-4">
        <PageHeader title="Netting Cycles" description="Multilateral intercompany netting management" />
        <LoadingSpinner />
      </div>
    );
  }

  if (error) {
    return (
      <div className="space-y-4">
        <PageHeader title="Netting Cycles" description="Multilateral intercompany netting management" />
        <ErrorMessage message={error} onRetry={fetchCycles} />
      </div>
    );
  }

  return (
    <Page>
      {/* Title + Quick Actions migrated to Aperture Layout header. */}

      {/* Info Banner */}
      <Card padding="sm" className="bg-gradient-to-r from-info-50/50 via-white to-primary-50/50 border-info-200/60 animate-fade-in dark:from-primary-900 dark:via-primary-900 dark:to-primary-900" style={{ animationDelay: '0.1s' }}>
        <div className="flex items-start gap-3">
          <StatusIconBadge tone="info" icon={GitMerge} className="dark:bg-info-500/20" />
          <div>
            <p className="text-sm font-semibold text-info-800 dark:text-info-300">Multilateral Netting Engine</p>
            <p className="text-sm text-info-700 mt-1 dark:text-info-300">
              Consolidate intercompany payables, receivables, POBO recharges, and IHB obligations into
              optimized net settlement positions. Reduce payment count and settlement costs.
            </p>
          </div>
        </div>
      </Card>

      {/* Stats Cards */}
      <StatStrip>
        <StatTile tone="primary" icon={<GitMerge className="w-5 h-5" />} label="Total" value={stats.total} delay="0.15s" />
        <StatTile tone="info" icon={<Layers className="w-5 h-5" />} label="Open" value={stats.open} delay="0.2s" />
        <StatTile tone="warning" icon={<Clock className="w-5 h-5" />} label="Pending" value={stats.pending} delay="0.25s" />
        <StatTile tone="success" icon={<CheckCircle className="w-5 h-5" />} label="Settled" value={stats.settled} delay="0.3s" />
        <StatTile tone="accent" icon={<TrendingUp className="w-5 h-5" />} label="Savings" value={formatCompactCurrency(stats.totalSavings, 'AED')} delay="0.35s" />
      </StatStrip>

      {/* Filter */}
      <div className="flex items-center gap-2 animate-fade-in" style={{ animationDelay: '0.4s' }}>
        <Filter className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
        <select
          value={filterStatus}
          onChange={(e) => setFilterStatus(e.target.value)}
          className="text-sm border border-neutral-200 rounded-lg px-3 py-1.5 focus:ring-2 focus:ring-primary-500/20 focus:border-primary-300 dark:border-primary-800"
        >
          <option value="all">All Cycles</option>
          <option value="DRAFT">Draft</option>
          <option value="OPEN">Open</option>
          <option value="PENDING_APPROVAL">Pending Approval</option>
          <option value="APPROVED">Approved</option>
          <option value="SETTLED">Settled</option>
        </select>
        <span className="text-sm text-neutral-500 ml-2 dark:text-neutral-400">
          {filteredCycles.length} cycle{filteredCycles.length !== 1 ? 's' : ''}
        </span>
      </div>

      {/* Cycles Grid */}
      {filteredCycles.length === 0 ? (
        <Card className="text-center py-12 animate-fade-in" style={{ animationDelay: '0.45s' }}>
          <div className="w-16 h-16 rounded-2xl bg-neutral-100 flex items-center justify-center mx-auto mb-4 dark:bg-primary-800">
            <GitMerge className="w-8 h-8 text-neutral-400 dark:text-neutral-500" />
          </div>
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
        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
          {filteredCycles.map((cycle, i) => (
            <div key={cycle.id} className="animate-fade-in" style={{ animationDelay: `${0.45 + i * 0.05}s` }}>
              <CycleCard
                cycle={cycle}
                onViewDetails={() => handleViewDetails(cycle)}
                onPopulate={() => handlePopulate(cycle.id)}
                onCalculate={() => handleCalculate(cycle.id)}
                onApprove={() => handleApprove(cycle.id)}
                onSettle={() => handleSettle(cycle.id)}
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