import React, { useState, useEffect, useCallback } from 'react';
import axios from 'axios';
import toast from 'react-hot-toast';
import { ArrowLeftRight, Building2, Plus, CheckCircle, Clock, Loader2, RefreshCw, TrendingUp, Eye, ArrowRight, ArrowLeft, GitMerge, Download, Wallet, CreditCard, Scale, BarChart3, Activity, Zap, Receipt, XCircle } from 'lucide-react';
import { Card, Button, Badge, Input, StatusIconBadge, StatTile, Checkbox, DataTable } from '../components/ui';
import { TileAmount } from '../components/TileAmount';
import { HeroMetricCard } from '../components/ui/HeroMetricCard';
import { Page } from '../components/layout/Page';
import { StatStrip } from '../components/layout/StatStrip';
import { ScopeSelector } from '../components/layout/ScopeSelector';
import { CurrencyPicker } from '../components/ui/CurrencyPicker';
import { Modal } from '../components/ui/enhanced';
import { usePageHeaderActions } from '../context/usePageHeader';
import { formatCurrency, cn } from '../utils';
import { useUser } from '../context/useUser';
import { useNavigation } from '../hooks/useNavigation';
import { TreasuryOnly } from '../components/permissions';

// ============================================================================
// HELPERS
// ============================================================================

/** Safely format a date string, returning '-' if invalid or missing */
const formatDate = (dateStr: string | null | undefined): string => {
  if (!dateStr) return '-';
  try {
    const date = new Date(dateStr);
    if (isNaN(date.getTime())) return '-';
    return date.toLocaleDateString();
  } catch {
    return '-';
  }
};

// ============================================================================
// API CLIENT
// ============================================================================

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8053/api/v1';
const apiClient = axios.create({
  baseURL: API_BASE_URL,
  headers: { 'Content-Type': 'application/json' },
  timeout: 30000,
});

// ============================================================================
// TYPES
// ============================================================================

interface Corporate {
  id: string;
  corporateId: string;
  legalName: string;
  tradeName?: string;
  shortName?: string;
  status: string;
}

interface LegalEntity {
  id: string;
  entityCode: string;
  entityName: string;
  shortName?: string;
  countryCode?: string;
  functionalCurrency: string;
  isTreasuryCenter: boolean;
  canParticipateNetting: boolean;
  status: string;
}

/** One subsidiary's position with treasury in one currency, from the IC Receivable / IC Payable mirrors (the ledger). */
interface IcPosition {
  subsidiaryEntityId: string;
  subsidiaryEntityCode: string;
  subsidiaryEntityName: string;
  treasuryEntityId: string;
  treasuryEntityCode: string;
  currencyCode: string;
  receivableBalance: number; // the subsidiary owes treasury
  payableBalance: number;    // treasury owes the subsidiary
  netPosition: number;       // receivable - payable: positive = owed to treasury
}

interface IntercompanyTransaction {
  id: string;
  transactionRef: string;
  transactionType: string;
  payingEntityId: string;
  payingEntityCode: string;
  payingEntityName: string;
  behalfEntityId: string;
  behalfEntityCode: string;
  behalfEntityName: string;
  amount: number;
  currencyCode: string;
  charges: number;
  netAmount: number;
  status: string;
  viban?: string;
  settlementRef?: string;
  processedAt?: string;
  settledAt?: string;
  createdAt: string;
}

/** POBO payments and COBO collections on the IHB current accounts, one currency; principal, fees apart. */
interface FlowVolume {
  currency: string;
  poboCount: number;
  poboVolume: number;
  poboFees: number;
  coboCount: number;
  coboVolume: number;
}

interface IntercompanyStats {
  totalPoboTransactions: number;
  totalCoboTransactions: number;
  volumes: FlowVolume[];
  activeEntities: number;
}

// ============================================================================
// API SERVICES
// ============================================================================

const corporatesApi = {
  getAll: async (): Promise<Corporate[]> => {
    try {
      const response = await apiClient.get('/corporates');
      return response.data.data || [];
    } catch { return []; }
  },
};

const legalEntityApi = {
  getByCorporate: async (corporateId: string): Promise<LegalEntity[]> => {
    try {
      const response = await apiClient.get(`/legal-entities/corporate/${corporateId}`);
      return response.data.data || [];
    } catch { return []; }
  },
};

const intercompanyApi = {
  // Stats
  getStats: async (corporateId: string): Promise<IntercompanyStats> => {
    const response = await apiClient.get('/intercompany/stats', { params: { corporateId } });
    return response.data.data;
  },

  // Who owes whom, per currency, from the ledger
  getPositions: async (corporateId: string): Promise<IcPosition[]> => {
    const response = await apiClient.get('/intercompany/positions', { params: { corporateId } });
    return response.data.data || [];
  },

  // Bilateral Position
  getBilateralPosition: async (entity1Id: string, entity2Id: string) => {
    const response = await apiClient.get('/intercompany/bilateral-position', { params: { entity1Id, entity2Id } });
    return response.data.data;
  },

  // Transactions
  getTransactions: async (params: { corporateId?: string; entityId?: string; type?: string; status?: string; page?: number; size?: number }) => {
    const response = await apiClient.get('/intercompany/transactions', { params });
    return response.data.data;
  },

  getPoboTransactions: async (entityId?: string, role?: string, status?: string) => {
    const response = await apiClient.get('/intercompany/pobo', { params: { entityId, role, status } });
    return response.data.data || [];
  },

  // COBO
  setupCobo: async (data: { collectingEntityId: string; behalfEntityId: string; amount: number; currencyCode?: string; description?: string; generateViban?: boolean }) => {
    const response = await apiClient.post('/intercompany/cobo/setup', data);
    return response.data.data;
  },

  getCoboTransactions: async (entityId?: string, role?: string, status?: string) => {
    const response = await apiClient.get('/intercompany/cobo', { params: { entityId, role, status } });
    return response.data.data || [];
  },

  // Settlement
  getUnsettled: async (entityId?: string) => {
    const response = await apiClient.get('/intercompany/unsettled', { params: { entityId } });
    return response.data.data || [];
  },

  calculateNetSettlement: async (entity1Id: string, entity2Id: string) => {
    const response = await apiClient.post('/intercompany/settlement/calculate', { entity1Id, entity2Id });
    return response.data.data;
  },

  executeSettlement: async (transactionIds: string[], settlementMethod?: string) => {
    const response = await apiClient.post('/intercompany/settlement/execute', { transactionIds, settlementMethod });
    return response.data.data;
  },

  // Entities with positions
  getEntitiesWithPositions: async (corporateId: string) => {
    const response = await apiClient.get('/intercompany/entities', { params: { corporateId } });
    return response.data.data || [];
  },

  // Netting eligibility
  checkNettingEligibility: async (entity1Id: string, entity2Id: string) => {
    const response = await apiClient.get('/intercompany/netting-eligibility', { params: { entity1Id, entity2Id } });
    return response.data.data;
  },

  addToNettingCycle: async (cycleId: string, transactionIds: string[], rechargeIds: string[]) => {
    const response = await apiClient.post(`/intercompany/add-to-netting/${cycleId}`, { transactionIds, rechargeIds });
    return response.data.data;
  },
};

// ============================================================================
// LOADING & ERROR COMPONENTS
// ============================================================================

const LoadingSpinner: React.FC<{ text?: string }> = ({ text = 'Loading...' }) => (
  <div className="flex items-center justify-center py-12 animate-fade-in">
    <StatusIconBadge tone="primary" icon={Loader2} size="lg" spin />
    <span className="ml-3 text-neutral-600 font-medium dark:text-neutral-300">{text}</span>
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

// Picker now uses the shared `<ScopeSelector mode="corporate-entity">`
// primitive from components/layout/ — see import block at the top.
// The inline `CorporateEntityFilterBar` that lived here is removed.

// ============================================================================
// COBO MODAL
// ============================================================================
// Ad-hoc POBO is gone: payables are paid on behalf by enrolment (In-House Bank, POBO tab).

interface CoboModalProps {
  isOpen: boolean;
  onClose: () => void;
  entities: LegalEntity[];
  corporateId: string;
  onSuccess: () => void;
}

const CoboModal: React.FC<CoboModalProps> = ({ isOpen, onClose, entities, onSuccess }) => {
  const [step, setStep] = useState<'form' | 'result'>('form');
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<any>(null);
  const [error, setError] = useState<string | null>(null);
  
  const [formData, setFormData] = useState({
    payingEntityId: '',
    behalfEntityId: '',
    amount: '',
    currencyCode: 'AED',
    description: '',
    generateViban: false,
  });

  const title = 'Collect On Behalf Of (COBO)';
  const primaryEntity = 'Treasury (Collector)';
  const secondaryEntity = 'Subsidiary (Behalf Of)';

  // Filter entities - treasury centers for primary, others for secondary
  const treasuryEntities = entities.filter(e => e.isTreasuryCenter && e.status === 'ACTIVE');
  const subsidiaryEntities = entities.filter(e => !e.isTreasuryCenter && e.status === 'ACTIVE');

  const handleExecute = async () => {
    setLoading(true);
    setError(null);
    try {
      const response = await intercompanyApi.setupCobo({
        collectingEntityId: formData.payingEntityId,
        behalfEntityId: formData.behalfEntityId,
        amount: parseFloat(formData.amount),
        currencyCode: formData.currencyCode,
        description: formData.description,
        generateViban: formData.generateViban,
      });
      setResult(response);
      setStep('result');
      toast.success('COBO transaction executed successfully');
    } catch (err: any) {
      const errorMsg = err.response?.data?.message || err.message || 'Failed to execute';
      setError(errorMsg);
      toast.error(errorMsg);
    } finally {
      setLoading(false);
    }
  };

  const resetAndClose = () => {
    setStep('form');
    setFormData({
      payingEntityId: '',
      behalfEntityId: '',
      amount: '',
      currencyCode: 'AED',
      description: '',
      generateViban: false,
    });
    setResult(null);
    setError(null);
    onClose();
    if (result) onSuccess();
  };

  return (
    <Modal isOpen={isOpen} onClose={resetAndClose} title={title} size="md">
      {/* Step Indicator */}
      <div className="flex items-center justify-center gap-2 mb-6">
        {['form', 'result'].map((s, i) => (
          <React.Fragment key={s}>
            <div className={cn(
              'w-8 h-8 rounded-full flex items-center justify-center text-body-sm font-medium',
              step === s ? 'bg-primary-600 text-white' : 
              ['form', 'result'].indexOf(step) > i ? 'bg-success-100 text-success-700 dark:bg-success-500/20 dark:text-success-300' : 
              'bg-surface-muted text-neutral-400 dark:text-neutral-400'
            )}>
              {i + 1}
            </div>
            {i < 1 && <div className={cn('w-8 h-0.5',
              ['form', 'result'].indexOf(step) > i ? 'bg-success-400' : 'bg-neutral-200 dark:bg-primary-800'
            )} />}
          </React.Fragment>
        ))}
      </div>

      {/* Error Display */}
      {error && (
        <div className="mb-4 p-3 bg-error-50 border border-error-200 rounded-lg flex items-start gap-2 dark:bg-error-500/10 dark:border-error-500/30">
          <XCircle className="w-5 h-5 text-error-600 mt-0.5 dark:text-error-300" />
          <span className="text-body-sm text-error-700 dark:text-error-300">{error}</span>
        </div>
      )}

      {/* Form Step */}
      {step === 'form' && (
        <div className="space-y-4">
          <div className="p-3 rounded-lg bg-info-50 text-body-sm text-info-800 dark:bg-info-500/10 dark:text-info-300">
            Treasury will collect payment from a customer on behalf of a subsidiary. Treasury will owe the subsidiary.
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="field-label block mb-1">{primaryEntity}</label>
              <select 
                className="w-full border rounded-lg px-3 py-2"
                value={formData.payingEntityId}
                onChange={(e) => setFormData({ ...formData, payingEntityId: e.target.value })}
              >
                <option value="">Select treasury entity...</option>
                {treasuryEntities.map(entity => (
                  <option key={entity.id} value={entity.id}>
                    {entity.entityCode} - {entity.entityName} ⭐
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="field-label block mb-1">{secondaryEntity}</label>
              <select 
                className="w-full border rounded-lg px-3 py-2"
                value={formData.behalfEntityId}
                onChange={(e) => setFormData({ ...formData, behalfEntityId: e.target.value })}
              >
                <option value="">Select subsidiary...</option>
                {subsidiaryEntities.map(entity => (
                  <option key={entity.id} value={entity.id}>
                    {entity.entityCode} - {entity.entityName}
                  </option>
                ))}
              </select>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="field-label block mb-1">Amount</label>
              <Input 
                type="number"
                placeholder="0.00"
                value={formData.amount}
                onChange={(e) => setFormData({ ...formData, amount: e.target.value })}
              />
            </div>
            <div>
              <label className="field-label block mb-1">Currency</label>
              <CurrencyPicker
                value={formData.currencyCode}
                onChange={(c) => setFormData({ ...formData, currencyCode: c })}
                extra={['SAR']}
              />
            </div>
          </div>

          <div>
            <label className="field-label block mb-1">Description</label>
            <Input 
              placeholder="e.g., Customer invoice collection"
              value={formData.description}
              onChange={(e) => setFormData({ ...formData, description: e.target.value })}
            />
          </div>

          <div className="space-y-2">
            <Checkbox size="sm" label="Generate VIBAN for collection" checked={formData.generateViban} onChange={(checked) => setFormData({ ...formData, generateViban: checked })} />
          </div>
        </div>
      )}

      {/* Result Step */}
      {step === 'result' && result && (
        <div className="text-center py-6">
          <StatusIconBadge tone="success" icon={CheckCircle} size="xl" rounded="full" className="mx-auto mb-4" />
          <h3 className="text-body-lg font-medium text-neutral-900 mb-2 dark:text-neutral-50">Transaction Successful</h3>
          <p className="text-neutral-600 mb-4 dark:text-neutral-300">COBO transaction executed successfully</p>
          <div className="p-3 bg-surface-page rounded-lg inline-block">
            <p className="body-sm">Reference</p>
            <p className="font-mono font-medium text-primary-600 dark:text-primary-200">{result.transactionRef}</p>
          </div>
          {result.viban && (
            <div className="mt-3 p-3 bg-success-50 rounded-lg inline-block dark:bg-success-500/10">
              <p className="body-sm">VIBAN Generated</p>
              <p className="font-mono font-medium text-success-600 dark:text-success-300">{result.viban}</p>
            </div>
          )}
        </div>
      )}

      {/* Footer */}
      <div className="flex justify-between mt-6 pt-4 border-t">
        {step === 'form' && (
          <>
            <Button variant="ghost" onClick={resetAndClose}>Cancel</Button>
            <Button
              onClick={handleExecute}
              disabled={loading || !formData.payingEntityId || !formData.behalfEntityId || !formData.amount}
            >
              {loading && <Loader2 className="w-4 h-4 animate-spin mr-2" />}
              Execute COBO
            </Button>
          </>
        )}
        {step === 'result' && (
          <Button onClick={resetAndClose} className="ml-auto">Done</Button>
        )}
      </div>
    </Modal>
  );
};

// ============================================================================
// LEDGER POSITIONS
// ============================================================================

/** One figure per currency, stacked; never summed across currencies. */
const Amounts: React.FC<{ items: { currency: string; value: number }[] }> = ({ items }) =>
  items.length === 0 ? <>—</> : (
    <span className="flex flex-col">
      {items.map(i => <TileAmount key={i.currency} value={i.value} currency={i.currency} />)}
    </span>
  );

/** Per currency: what subsidiaries owe treasury, what treasury owes them, and treasury's net (+ = owed to treasury). */
const totalsByCurrency = (positions: IcPosition[]) => Object.values(positions.reduce((m, p) => {
  const t = m[p.currencyCode] ?? (m[p.currencyCode] = { currency: p.currencyCode, owedToTreasury: 0, owedByTreasury: 0, net: 0 });
  t.owedToTreasury += p.receivableBalance;
  t.owedByTreasury += p.payableBalance;
  t.net += p.netPosition;
  return m;
}, {} as Record<string, { currency: string; owedToTreasury: number; owedByTreasury: number; net: number }>));

/** The debtor and creditor of a position: a positive net means the subsidiary owes treasury. */
const owes = (p: IcPosition) => {
  const treasury = p.treasuryEntityCode || 'Treasury';
  return p.netPosition > 0
    ? { debtor: p.subsidiaryEntityCode, creditor: treasury }
    : { debtor: treasury, creditor: p.subsidiaryEntityCode };
};

/** "MNC-TREASURY owes MNC-UK AED 1,100.01" */
const OwesLine: React.FC<{ position: IcPosition }> = ({ position: p }) => {
  const { debtor, creditor } = owes(p);
  return (
    <span>
      <span className="font-medium text-error-600 dark:text-error-300">{debtor}</span>
      {' owes '}
      <span className="font-medium text-success-600 dark:text-success-300">{creditor}</span>
      {' '}
      <span className="font-semibold text-primary-900 dark:text-neutral-50">{formatCurrency(Math.abs(p.netPosition), p.currencyCode)}</span>
    </span>
  );
};

// ponytail: no Settle / Details here. The settle endpoint posts without a preview or confirmation (review H4) and
// picks the IC mirrors by name, not by pair; wire it back once both are fixed.
const PositionCard: React.FC<{ position: IcPosition }> = ({ position: p }) => {
  const treasury = p.treasuryEntityCode || 'Treasury';
  return (
    <Card hover className="group">
      <div className="p-4">
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-4">
            <div className="flex items-center gap-2">
              <StatusIconBadge tone="primary" icon={Building2} />
              <p className="font-medium text-primary-900 dark:text-neutral-50">{treasury}</p>
            </div>
            <ArrowLeftRight className="w-5 h-5 text-neutral-300 dark:text-neutral-400" />
            <div className="flex items-center gap-2">
              <StatusIconBadge tone="info" icon={Building2} />
              <div>
                <p className="font-medium text-primary-900 dark:text-neutral-50">{p.subsidiaryEntityCode}</p>
                <p className="caption">{p.subsidiaryEntityName}</p>
              </div>
            </div>
          </div>
          <Badge variant="warning">{p.currencyCode} outstanding</Badge>
        </div>

        <div className="grid grid-cols-3 gap-3 mb-4">
          <div className="text-center p-3 bg-surface-page rounded-lg">
            <p className="label">{p.subsidiaryEntityCode} owes</p>
            <p className="font-semibold text-primary-900 mt-1 dark:text-neutral-50">{formatCurrency(p.receivableBalance, p.currencyCode)}</p>
          </div>
          <div className="text-center p-3 bg-surface-page rounded-lg">
            <p className="label">{treasury} owes</p>
            <p className="font-semibold text-primary-900 mt-1 dark:text-neutral-50">{formatCurrency(p.payableBalance, p.currencyCode)}</p>
          </div>
          <div className="text-center p-3 bg-primary-50 rounded-lg dark:bg-primary-800/40">
            <p className="label">Net</p>
            <p className="font-bold text-primary-700 mt-1 dark:text-neutral-200">{formatCurrency(Math.abs(p.netPosition), p.currencyCode)}</p>
          </div>
        </div>

        <div className="body-sm text-center p-2 bg-surface-page rounded-lg">
          <OwesLine position={p} />
        </div>
      </div>
    </Card>
  );
};

// ============================================================================
// TRANSACTION ROW
// ============================================================================

const TransactionRow: React.FC<{ transaction: IntercompanyTransaction; onView: () => void }> = ({ transaction, onView }) => {
  const typeColors: Record<string, string> = {
    POBO: 'bg-primary-100 text-primary-700 dark:bg-primary-700 dark:text-neutral-200',
    POBO_PAYMENT: 'bg-primary-100 text-primary-700 dark:bg-primary-700 dark:text-neutral-200',
    COBO: 'bg-info-100 text-info-700 dark:bg-info-500/20 dark:text-info-300',
    COBO_COLLECTION: 'bg-info-100 text-info-700 dark:bg-info-500/20 dark:text-info-300',
    SETTLEMENT: 'bg-success-100 text-success-700 dark:bg-success-500/20 dark:text-success-300',
  };

  const statusColors: Record<string, string> = {
    PENDING: 'bg-warning-100 text-warning-700 dark:bg-warning-500/20 dark:text-warning-300',
    ACTIVE: 'bg-info-100 text-info-700 dark:bg-info-500/20 dark:text-info-300',
    PROCESSED: 'bg-primary-100 text-primary-700 dark:bg-primary-700 dark:text-neutral-200',
    COMPLETED: 'bg-primary-100 text-primary-700 dark:bg-primary-700 dark:text-neutral-200',
    SETTLED: 'bg-success-100 text-success-700 dark:bg-success-500/20 dark:text-success-300',
    FAILED: 'bg-error-100 text-error-700 dark:bg-error-500/20 dark:text-error-300',
  };

  return (
    <tr className="data-table-row group">
      <td className="data-table-cell">
        <p className="body-strong">{transaction.transactionRef}</p>
        <p className="caption">{formatDate(transaction.createdAt)}</p>
      </td>
      <td className="data-table-cell">
        <span className={cn('px-2 py-1 text-caption font-medium rounded-full', typeColors[transaction.transactionType] || 'bg-surface-muted')}>
          {transaction.transactionType.replace('_', ' ')}
        </span>
      </td>
      <td className="data-table-cell">
        <p className="body-strong">{transaction.payingEntityCode}</p>
        <p className="caption">{transaction.payingEntityName}</p>
      </td>
      <td className="data-table-cell text-center">
        <ArrowRight className="w-4 h-4 text-neutral-400 inline dark:text-neutral-400" />
      </td>
      <td className="data-table-cell">
        <p className="body-strong">{transaction.behalfEntityCode}</p>
        <p className="caption">{transaction.behalfEntityName}</p>
      </td>
      <td className="data-table-cell text-right">
        <p className="body-strong font-semibold">{formatCurrency(transaction.amount, transaction.currencyCode)}</p>
        {transaction.charges > 0 && (
          <p className="caption">+{formatCurrency(transaction.charges, transaction.currencyCode)} fees</p>
        )}
      </td>
      <td className="data-table-cell">
        <span className={cn('px-2 py-1 text-caption font-medium rounded-full', statusColors[transaction.status] || 'bg-surface-muted')}>
          {transaction.status}
        </span>
      </td>
      <td className="data-table-cell">
        <Button size="sm" variant="ghost" onClick={onView} className="opacity-0 group-hover:opacity-100 transition-opacity">
          <Eye className="w-4 h-4" />
        </Button>
      </td>
    </tr>
  );
};

// ============================================================================
// MAIN PAGE COMPONENT
// ============================================================================

export type IntercompanyTabType = 'overview' | 'pobo' | 'cobo' | 'settlement' | 'recharges' | 'transactions' | 'entities' | 'pairs';

interface IntercompanyDashboardPageProps {
  defaultTab?: IntercompanyTabType;
}

const IntercompanyDashboardPage: React.FC<IntercompanyDashboardPageProps> = ({ defaultTab = 'overview' }) => {
  // User context and permissions
  const { currentEntity } = useUser();
  const { navigate } = useNavigation();
  const approverName = currentEntity?.entityCode || currentEntity?.entityName || 'System';

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<IntercompanyTabType>(defaultTab);

  // Corporate/Entity selection
  const [corporates, setCorporates] = useState<Corporate[]>([]);
  const [legalEntities, setLegalEntities] = useState<LegalEntity[]>([]);
  const [selectedCorporateId, setSelectedCorporateId] = useState('');
  const [selectedEntityId, setSelectedEntityId] = useState('');

  // Data states
  const [stats, setStats] = useState<IntercompanyStats | null>(null);
  const [positions, setPositions] = useState<IcPosition[]>([]);
  const [transactions, setTransactions] = useState<IntercompanyTransaction[]>([]);
  const [entitiesWithPositions, setEntitiesWithPositions] = useState<any[]>([]);

  // Recharges state
  const [pendingRecharges, setPendingRecharges] = useState<any[]>([]);
  const [rechargeProcessing, setRechargeProcessing] = useState<string | null>(null);
  
  // Modal states
  const [showCoboModal, setShowCoboModal] = useState(false);

  // Determine selected entity role for filtering
  const selectedEntity = legalEntities.find(e => e.id === selectedEntityId);
  const isTreasuryView = !selectedEntityId || selectedEntity?.isTreasuryCenter === true;
  const isSubsidiaryView = selectedEntityId && selectedEntity?.isTreasuryCenter === false;

  // Role-based transaction type filter for POBO
  // Treasury sees IC_RECEIVABLE (what subsidiaries owe them)
  // Subsidiary sees IC_PAYABLE (what they owe treasury)
  const poboTransactionFilter = (tx: IntercompanyTransaction) => {
    if (isTreasuryView) {
      return tx.transactionType === 'IC_RECEIVABLE' || tx.transactionType === 'POBO' || tx.transactionType === 'POBO_PAYMENT';
    } else if (isSubsidiaryView) {
      return tx.transactionType === 'IC_PAYABLE';
    }
    return tx.transactionType === 'POBO' || tx.transactionType === 'POBO_PAYMENT' || tx.transactionType === 'IC_RECEIVABLE' || tx.transactionType === 'IC_PAYABLE';
  };

  // Role-based transaction type filter for COBO
  // Treasury sees collections they made (COBO/COBO_COLLECTION)
  // Subsidiary sees collections made on their behalf
  const coboTransactionFilter = (tx: IntercompanyTransaction) => {
    return tx.transactionType === 'COBO' || tx.transactionType === 'COBO_COLLECTION';
  };

  // Load corporates on mount
  useEffect(() => {
    const loadCorporates = async () => {
      const corps = await corporatesApi.getAll();
      setCorporates(corps);
      if (corps.length > 0) {
        setSelectedCorporateId(corps[0].id);
      }
    };
    loadCorporates();
  }, []);

  // Load entities when corporate changes
  useEffect(() => {
    const loadEntities = async () => {
      if (selectedCorporateId) {
        const entities = await legalEntityApi.getByCorporate(selectedCorporateId);
        setLegalEntities(entities);
      } else {
        setLegalEntities([]);
      }
      setSelectedEntityId('');
    };
    loadEntities();
  }, [selectedCorporateId]);

  // Load data when corporate/entity changes
  const fetchData = useCallback(async () => {
    if (!selectedCorporateId) {
      setLoading(false);
      return;
    }
    
    try {
      setLoading(true);
      setError(null);
      
      const [statsData, positionsData, txData, entitiesData, rechargesData] = await Promise.all([
        intercompanyApi.getStats(selectedCorporateId).catch(() => null),
        intercompanyApi.getPositions(selectedCorporateId).catch(() => []),
        intercompanyApi.getTransactions({
          corporateId: selectedCorporateId,
          entityId: selectedEntityId || undefined,
          size: 50
        }).catch(() => ({ content: [] })),
        intercompanyApi.getEntitiesWithPositions(selectedCorporateId).catch(() => []),
        // Pending POBO recharges: this endpoint answers with the bare list, not the { data } envelope
        apiClient.get('/pobo/recharges/pending', { params: { corporateId: selectedCorporateId } })
          .then(r => (Array.isArray(r.data) ? r.data : []))
          .catch(() => []),
      ]);

      setStats(statsData);
      setPositions(positionsData);
      setTransactions(txData?.content || txData || []);
      setEntitiesWithPositions(entitiesData);
      setPendingRecharges(rechargesData);
      
    } catch (err: any) {
      setError(err.message || 'Failed to load intercompany data');
    } finally {
      setLoading(false);
    }
  }, [selectedCorporateId, selectedEntityId]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  const handleExport = () => {
    const csv = [
      'Reference,Type,Payer,Behalf Of,Amount,Currency,Status,Created',
      ...transactions.map(tx =>
        `${tx.transactionRef || '-'},${tx.transactionType || '-'},${tx.payingEntityCode || '-'},${tx.behalfEntityCode || '-'},${tx.amount || 0},${tx.currencyCode || '-'},${tx.status || '-'},${formatDate(tx.createdAt)}`
      )
    ].join('\n');
    const blob = new Blob([csv], { type: 'text/csv' });
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = `intercompany-transactions-${new Date().toISOString().split('T')[0]}.csv`;
    link.click();
    toast.success('Export downloaded');
  };

  // Recharge approval handlers
  const handleApproveRecharge = async (rechargeId: string) => {
    setRechargeProcessing(rechargeId);
    try {
      await apiClient.post(`/pobo/recharges/${rechargeId}/approve`, null, {
        params: { approver: approverName }
      });
      toast.success('Recharge approved successfully');
      fetchData();
    } catch (err: any) {
      toast.error(err.response?.data?.message || 'Failed to approve recharge');
    } finally {
      setRechargeProcessing(null);
    }
  };

  const handleRejectRecharge = async (rechargeId: string, reason?: string) => {
    setRechargeProcessing(rechargeId);
    try {
      await apiClient.post(`/pobo/recharges/${rechargeId}/reject`, null, {
        params: { rejector: approverName, reason: reason || `Rejected by ${approverName}` }
      });
      toast.success('Recharge rejected');
      fetchData();
    } catch (err: any) {
      toast.error(err.response?.data?.message || 'Failed to reject recharge');
    } finally {
      setRechargeProcessing(null);
    }
  };

  // Tier 6 Design System Unification (2026-05-13): Refresh / COBO / POBO
  // CTAs migrated from in-page header strip to the Layout header — same
  // pattern as every other treasury page. Removes the four duplicated
  // <h1 className="page-title"> renders that previously sat at the top of
  // each render branch (loading, error, empty, main).
  usePageHeaderActions(
    () => (
      <>
        <Button variant="outline" size="sm" leftIcon={<RefreshCw className="w-4 h-4" />} onClick={fetchData}>
          Refresh
        </Button>
        <Button variant="outline" size="sm" leftIcon={<Wallet className="w-4 h-4" />} onClick={() => setShowCoboModal(true)} disabled={!selectedCorporateId}>
          COBO
        </Button>
      </>
    ),
    [fetchData, selectedCorporateId]
  );

  if (!selectedCorporateId && !loading) {
    return (
      <div className="space-y-6">
        <ScopeSelector mode="corporate-entity" disableChildUntilParent
          corporates={corporates}
          legalEntities={legalEntities}
          selectedCorporateId={selectedCorporateId}
          selectedEntityId={selectedEntityId}
          onCorporateChange={setSelectedCorporateId}
          onEntityChange={setSelectedEntityId}
        />
        <Card className="text-center py-12">
          <Building2 className="w-12 h-12 text-neutral-300 mx-auto mb-4 dark:text-neutral-400" />
          <h3 className="text-body-lg font-medium text-neutral-900 mb-2 dark:text-neutral-50">Select a Corporate</h3>
          <p className="text-neutral-500 dark:text-neutral-400">Please select a corporate to view intercompany data</p>
        </Card>
      </div>
    );
  }

  if (loading) {
    return (
      <div className="space-y-6">
        <ScopeSelector mode="corporate-entity" disableChildUntilParent
          corporates={corporates}
          legalEntities={legalEntities}
          selectedCorporateId={selectedCorporateId}
          selectedEntityId={selectedEntityId}
          onCorporateChange={setSelectedCorporateId}
          onEntityChange={setSelectedEntityId}
          loading={true}
        />
        <LoadingSpinner text="Loading intercompany data..." />
      </div>
    );
  }

  if (error) {
    return (
      <div className="space-y-6">
        <ScopeSelector mode="corporate-entity" disableChildUntilParent
          corporates={corporates}
          legalEntities={legalEntities}
          selectedCorporateId={selectedCorporateId}
          selectedEntityId={selectedEntityId}
          onCorporateChange={setSelectedCorporateId}
          onEntityChange={setSelectedEntityId}
        />
        <ErrorMessage message={error} onRetry={fetchData} />
      </div>
    );
  }

  const tabs = [
    { id: 'overview', label: 'Overview', icon: BarChart3 },
    { id: 'pobo', label: 'POBO', icon: CreditCard },
    { id: 'cobo', label: 'COBO', icon: Wallet },
    { id: 'settlement', label: 'Settlement', icon: Scale },
    { id: 'recharges', label: 'Recharges', icon: Zap, count: pendingRecharges.length },
    { id: 'transactions', label: 'All Transactions', icon: Receipt, count: transactions.length },
    { id: 'entities', label: 'Entities', icon: Building2, count: entitiesWithPositions.length },
    { id: 'pairs', label: 'Entity Pairs', icon: ArrowLeftRight, count: positions.length },
  ];

  // Headline figures: outstanding from the ledger (IC mirrors), flows from the IHB accounts' ledger, per currency.
  const totals = totalsByCurrency(positions);
  const volumes = stats?.volumes ?? [];
  const poboVolumes = volumes.filter(v => v.poboCount !== 0).map(v => ({ currency: v.currency, value: v.poboVolume }));
  const coboVolumes = volumes.filter(v => v.coboCount !== 0).map(v => ({ currency: v.currency, value: v.coboVolume }));
  const poboFees = volumes.filter(v => v.poboFees > 0).map(v => formatCurrency(v.poboFees, v.currency)).join(', ');
  const owedToTreasury = totals.filter(t => t.owedToTreasury !== 0).map(t => ({ currency: t.currency, value: t.owedToTreasury }));
  const owedByTreasury = totals.filter(t => t.owedByTreasury !== 0).map(t => ({ currency: t.currency, value: t.owedByTreasury }));
  const treasuryNet = totals.map(t => ({ currency: t.currency, value: t.net }));
  const pendingRechargeCount = pendingRecharges.filter(r => r.status === 'PENDING').length;
  const positionList = positions.length === 0 ? (
    <div className="px-4 py-12 text-center">
      <StatusIconBadge tone="success" icon={CheckCircle} size="xl" className="mx-auto mb-4" />
      <p className="text-neutral-500 font-medium dark:text-neutral-400">All intercompany positions are settled</p>
    </div>
  ) : positions.map(p => (
    <div key={`${p.subsidiaryEntityId}-${p.currencyCode}`} className="p-4 flex items-center justify-between body-sm">
      <OwesLine position={p} />
      <span className="caption">{p.subsidiaryEntityName}</span>
    </div>
  ));

  return (
    <Page>
      {/* Refresh / COBO / POBO CTAs now live in the Layout header (registered
          via usePageHeaderActions above the loading guard). The redundant
          in-page <h1 className="page-title"> previously duplicated four times
          across render branches is removed — the Layout header already shows
          "Intercompany Dashboard" from the navigation pageTitles map. */}

      {/* Corporate/Entity Filter */}
      <ScopeSelector mode="corporate-entity" disableChildUntilParent
        corporates={corporates}
        legalEntities={legalEntities}
        selectedCorporateId={selectedCorporateId}
        selectedEntityId={selectedEntityId}
        onCorporateChange={setSelectedCorporateId}
        onEntityChange={setSelectedEntityId}
      />

      {/* Headline figures — POBO + COBO Volume. Tier 6 Design System
          Unification (2026-05-13): replaces a 6-up equal-weight stat strip
          that gave no visual hierarchy. The treasurer's first read is
          intercompany dollar throughput (volume), not transaction counts.
          Counts demoted to the operational strip below. */}
      {stats && (
        <>
          <HeroMetricCard
            primary={{
              label: 'POBO Volume',
              value: <Amounts items={poboVolumes} />,
              sub: `${stats.totalPoboTransactions} payments on behalf of subsidiaries${poboFees ? ` · fees ${poboFees} not included` : ''}`,
            }}
            secondary={{
              label: 'COBO Volume',
              value: <Amounts items={coboVolumes} />,
              sub: `${stats.totalCoboTransactions} collections on behalf`,
            }}
            icon={<TrendingUp className="w-6 h-6 text-accent-600 dark:text-accent-300" />}
          />

          {/* Operational metrics — secondary strip below the hero. */}
          <StatStrip>
            <StatTile layout="row" tone="primary" icon={<CreditCard className="w-5 h-5" />} label="POBO Count" value={stats.totalPoboTransactions} delay="0.18s" />
            <StatTile layout="row" tone="info" icon={<Wallet className="w-5 h-5" />} label="COBO Count" value={stats.totalCoboTransactions} delay="0.21s" />
            <StatTile layout="row" tone="warning" icon={<Clock className="w-5 h-5" />} label="Open Positions" value={positions.length} delay="0.24s" />
            <StatTile layout="row" tone="accent" icon={<Building2 className="w-5 h-5" />} label="Entities" value={stats.activeEntities} delay="0.27s" />
          </StatStrip>
        </>
      )}

      {/* Premium Tabs */}
      <div className="flex gap-1 border-b border-edge animate-fade-in" style={{ animationDelay: '0.45s' }}>
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
        <div className="grid grid-cols-3 gap-6 animate-fade-in" style={{ animationDelay: '0.5s' }}>
          <div className="col-span-2">
            <Card hover>
              <div className="h-1 bg-gradient-to-r from-primary-500 via-info-500 to-accent-500 rounded-t-lg" />
              <div className="p-6">
                <div className="flex items-center gap-3 mb-6">
                  <StatusIconBadge tone="primary" icon={BarChart3} />
                  <h3 className="section-title">Corporate Position Summary</h3>
                </div>
                {/* Two columns, not the auto-derived three. This strip sits in a
                    col-span-2 panel, not across the page: 568px wide, so three
                    columns give each tile 179px -- 141px of content once the
                    tile's own padding is off. A compacted currency figure runs
                    to about 156px ("AED -396.00"), so the three-up version
                    clipped. Note which values clip: TileAmount abbreviates the
                    big ones ("AED 1.2B" fits easily), so it was the SMALL
                    figures that got cut. Three across would need ~613px. */}
                <StatStrip columns={2} className="mb-6">
                  <StatTile tone="success" label="Owed to Treasury" value={<Amounts items={owedToTreasury} />} />
                  <StatTile tone="danger" label="Owed by Treasury" value={<Amounts items={owedByTreasury} />} />
                  <StatTile tone="primary" label="Treasury Net" sub="+ owed to treasury" value={<Amounts items={treasuryNet} />} />
                </StatStrip>

                <div className="divide-y divide-edge-subtle bg-surface-page rounded-lg">{positionList}</div>
              </div>
            </Card>
          </div>

          <div className="space-y-4">
            <Card hover>
              <div className="p-4">
                <h4 className="body-strong font-semibold mb-4">Quick Actions</h4>
                <div className="space-y-2">
                  <Button className="w-full justify-start" variant="outline" leftIcon={<Wallet className="w-4 h-4" />} onClick={() => setShowCoboModal(true)}>
                    Setup COBO Collection
                  </Button>
                  {/* ponytail: no bilateral-settlement flow exists yet; disabled rather than a dead button */}
                  <Button className="w-full justify-start" variant="outline" leftIcon={<Scale className="w-4 h-4" />} disabled title="Coming soon">
                    Bilateral Settlement
                  </Button>
                  <Button className="w-full justify-start" variant="outline" leftIcon={<GitMerge className="w-4 h-4" />} onClick={() => navigate('netting-enhanced')}>
                    Add to Netting Cycle
                  </Button>
                </div>
              </div>
            </Card>

            <Card hover>
              <div className="p-4">
                <h4 className="body-strong font-semibold mb-3">Settlement Options</h4>
                <div className="space-y-2 text-body-sm">
                  <div className="flex items-center gap-2 p-2.5 bg-warning-50 rounded-lg dark:bg-warning-500/10">
                    <Zap className="w-4 h-4 text-warning-600 dark:text-warning-300" />
                    <span className="font-medium text-neutral-700 dark:text-neutral-200">Direct Payment</span>
                  </div>
                  <div className="flex items-center gap-2 p-2.5 bg-primary-50 rounded-lg dark:bg-primary-800/40">
                    <GitMerge className="w-4 h-4 text-primary-600 dark:text-primary-200" />
                    <span className="font-medium text-neutral-700 dark:text-neutral-200">Multilateral Netting</span>
                  </div>
                </div>
              </div>
            </Card>
          </div>
        </div>
      )}

      {/* POBO Tab */}
      {activeTab === 'pobo' && (
        <div className="space-y-6 animate-fade-in" style={{ animationDelay: '0.5s' }}>
          {/* Was 4 hand-rolled <Card> tiles (one with a one-off gradient
              tint the other 3 didn't have) using formatCompactCurrency
              (full precision despite the name) instead of the compact
              TileAmount treatment the Overview tab's equivalent row
              already uses — same page, two different tile styles and two
              different amount formats. Matched to Overview's StatTile row. */}
          <StatStrip>
            <StatTile tone="primary" icon={<CreditCard className="w-5 h-5" />} label="Total POBO" value={stats?.totalPoboTransactions || 0} />
            <StatTile tone="success" icon={<TrendingUp className="w-5 h-5" />} label="POBO Volume" value={<Amounts items={poboVolumes} />} />
            <StatTile tone="warning" icon={<Clock className="w-5 h-5" />} label="Pending Recharges" value={pendingRechargeCount} />
            <StatTile tone="danger" icon={<XCircle className="w-5 h-5" />} label="Owed to Treasury" value={<Amounts items={owedToTreasury} />} />
          </StatStrip>

          <Card hover>
            <div className="h-1 bg-gradient-to-r from-primary-500/50 via-white to-primary-500/50 dark:from-primary-900 dark:via-primary-900 dark:to-primary-900 rounded-t-lg" />
            <div className="p-4 border-b border-edge-subtle flex items-center justify-between">
              <div className="flex items-center gap-3">
                <StatusIconBadge tone="primary" icon={CreditCard} size="sm" rounded="lg" />
                <h4 className="font-semibold text-primary-900 dark:text-neutral-50">POBO Transactions</h4>
                {isTreasuryView && (
                  <span className="ml-2 px-2 py-0.5 text-caption font-medium bg-success-100 text-success-700 rounded-full dark:bg-success-500/20 dark:text-success-300">
                    Treasury View (Receivables)
                  </span>
                )}
                {isSubsidiaryView && (
                  <span className="ml-2 px-2 py-0.5 text-caption font-medium bg-warning-100 text-warning-700 rounded-full dark:bg-warning-500/20 dark:text-warning-300">
                    Subsidiary View (Payables)
                  </span>
                )}
              </div>
              <div className="flex items-center gap-2">
                <select className="text-body-sm border rounded-lg px-3 py-1.5">
                  <option value="">All Status</option>
                  <option value="PENDING">Pending Recharge</option>
                  <option value="PROCESSED">Processed</option>
                  <option value="SETTLED">Settled</option>
                </select>
              </div>
            </div>
            <DataTable
              hairline
              data={transactions.filter(poboTransactionFilter)}
              keyExtractor={(tx) => tx.id}
              emptyTitle="No POBO transactions found"
              emptyDescription=""
              emptyIcon={<StatusIconBadge tone="neutral" icon={CreditCard} size="xl" />}
              columns={[
                { key: 'transactionRef', header: 'Reference', render: (_, tx) => (
                  <>
                    <p className="body-strong">{tx.transactionRef}</p>
                    <p className="caption">{formatDate(tx.createdAt)}</p>
                  </>
                ) },
                { key: 'payingEntityCode', header: isTreasuryView ? 'Treasury (Creditor)' : 'Subsidiary (Debtor)', minWidth: 160, dropOrder: 3, render: (_, tx) => (
                  <>
                    <p className="body-strong">{tx.payingEntityCode}</p>
                    <p className="caption">{tx.payingEntityName}</p>
                  </>
                ) },
                { key: 'direction', header: '', align: 'center', width: '3rem', render: () => (
                  <ArrowRight className="w-4 h-4 text-primary-400 inline" />
                ) },
                { key: 'behalfEntityCode', header: isTreasuryView ? 'Subsidiary (Owes)' : 'Treasury (Owed To)', minWidth: 160, dropOrder: 2, render: (_, tx) => (
                  <>
                    <p className="body-strong">{tx.behalfEntityCode}</p>
                    <p className="caption">{tx.behalfEntityName}</p>
                  </>
                ) },
                { key: 'amount', header: 'Amount', align: 'right', render: (_, tx) => (
                  <p className="body-strong font-semibold">{formatCurrency(tx.amount, tx.currencyCode)}</p>
                ) },
                { key: 'status', header: 'Status', render: (_, tx) => (
                  <Badge variant={tx.status === 'SETTLED' ? 'success' : tx.status === 'PENDING' ? 'warning' : 'primary'} size="sm">
                    {tx.status}
                  </Badge>
                ) },
                { key: 'actions', header: 'Actions', align: 'center', render: () => (
                  <Button size="sm" variant="ghost"><Eye className="w-4 h-4" /></Button>
                ) },
              ]}
            />
          </Card>
        </div>
      )}

      {/* COBO Tab */}
      {activeTab === 'cobo' && (
        <div className="space-y-6 animate-fade-in" style={{ animationDelay: '0.5s' }}>
          <div className="flex items-center justify-end">
            <Button leftIcon={<Plus className="w-4 h-4" />} onClick={() => setShowCoboModal(true)}>
              Setup COBO Collection
            </Button>
          </div>

          {/* Same StatTile/TileAmount consistency fix as the POBO tab above. */}
          <StatStrip>
            <StatTile tone="info" icon={<Wallet className="w-5 h-5" />} label="Total COBO" value={stats?.totalCoboTransactions || 0} />
            <StatTile tone="accent" icon={<Activity className="w-5 h-5" />} label="COBO Volume" value={<Amounts items={coboVolumes} />} />
            <StatTile tone="success" icon={<TrendingUp className="w-5 h-5" />} label="Owed by Treasury" value={<Amounts items={owedByTreasury} />} />
          </StatStrip>

          <Card hover>
            <div className="h-1 bg-gradient-to-r from-info-500/50 via-white to-info-500/50 dark:from-primary-900 dark:via-primary-900 dark:to-primary-900 rounded-t-lg" />
            <div className="p-4 border-b border-edge-subtle flex items-center justify-between">
              <div className="flex items-center gap-3">
                <StatusIconBadge tone="info" icon={Wallet} size="sm" rounded="lg" />
                <h4 className="font-semibold text-primary-900 dark:text-neutral-50">COBO Collections</h4>
              </div>
              <div className="flex items-center gap-2">
                <select className="text-body-sm border rounded-lg px-3 py-1.5">
                  <option value="">All Status</option>
                  <option value="ACTIVE">Active VIBAN</option>
                  <option value="COLLECTED">Collected</option>
                  <option value="DISTRIBUTED">Distributed</option>
                </select>
              </div>
            </div>
            <DataTable
              hairline
              data={transactions.filter(coboTransactionFilter)}
              keyExtractor={(tx) => tx.id}
              emptyTitle="No COBO collections found"
              emptyDescription=""
              emptyIcon={<StatusIconBadge tone="neutral" icon={Wallet} size="xl" />}
              emptyAction={
                <Button size="sm" variant="outline" onClick={() => setShowCoboModal(true)}>
                  Setup First COBO
                </Button>
              }
              columns={[
                { key: 'transactionRef', header: 'Reference', render: (_, tx) => (
                  <>
                    <p className="body-strong">{tx.transactionRef}</p>
                    <p className="caption">{formatDate(tx.createdAt)}</p>
                  </>
                ) },
                { key: 'payingEntityCode', header: 'Treasury (Collector)', minWidth: 160, dropOrder: 3, render: (_, tx) => (
                  <>
                    <p className="body-strong">{tx.payingEntityCode}</p>
                    <p className="caption">{tx.payingEntityName}</p>
                  </>
                ) },
                { key: 'direction', header: '', align: 'center', width: '3rem', render: () => (
                  <ArrowLeft className="w-4 h-4 text-info-400 inline" />
                ) },
                { key: 'behalfEntityCode', header: 'Subsidiary (Behalf Of)', minWidth: 160, dropOrder: 2, render: (_, tx) => (
                  <>
                    <p className="body-strong">{tx.behalfEntityCode}</p>
                    <p className="caption">{tx.behalfEntityName}</p>
                  </>
                ) },
                { key: 'amount', header: 'Amount', align: 'right', render: (_, tx) => (
                  <p className="body-strong font-semibold">{formatCurrency(tx.amount, tx.currencyCode)}</p>
                ) },
                { key: 'viban', header: 'VIBAN', minWidth: 150, dropOrder: 1, render: (_, tx) => (
                  tx.viban ? (
                    <code className="text-caption bg-info-50 text-info-700 px-2 py-1 rounded-lg font-medium dark:bg-info-500/10 dark:text-info-300">{tx.viban}</code>
                  ) : (
                    <span className="text-neutral-400">—</span>
                  )
                ) },
                { key: 'status', header: 'Status', render: (_, tx) => (
                  <Badge variant={tx.status === 'SETTLED' ? 'success' : tx.status === 'ACTIVE' ? 'info' : 'warning'} size="sm">
                    {tx.status}
                  </Badge>
                ) },
                { key: 'actions', header: 'Actions', align: 'center', render: () => (
                  <Button size="sm" variant="ghost"><Eye className="w-4 h-4" /></Button>
                ) },
              ]}
            />
          </Card>
        </div>
      )}

      {/* Settlement Tab */}
      {activeTab === 'settlement' && (
        <div className="space-y-6 animate-fade-in" style={{ animationDelay: '0.5s' }}>
          <div className="flex items-center justify-end gap-2">
            <Button variant="outline" leftIcon={<GitMerge className="w-4 h-4" />}>
              Add to Netting
            </Button>
            <Button leftIcon={<Scale className="w-4 h-4" />}>
              Bilateral Settlement
            </Button>
          </div>

          {/* Same StatTile/TileAmount consistency fix as the POBO/COBO tabs. */}
          <StatStrip>
            <StatTile tone="warning" icon={<Clock className="w-5 h-5" />} label="Open Positions" value={positions.length} />
            <StatTile tone="success" icon={<ArrowRight className="w-5 h-5" />} label="Owed to Treasury" value={<Amounts items={owedToTreasury} />} />
            <StatTile tone="danger" icon={<ArrowLeft className="w-5 h-5" />} label="Owed by Treasury" value={<Amounts items={owedByTreasury} />} />
            <StatTile tone="primary" icon={<Scale className="w-5 h-5" />} label="Treasury Net" sub="+ owed to treasury" value={<Amounts items={treasuryNet} />} />
          </StatStrip>

          {/* Outstanding positions, from the ledger. The "Intercompany Virtual Accounts" cards that used to follow
              showed the same IC mirror balances again as raw accounts; the positions below are those balances. */}
          <Card hover>
            <div className="h-1 bg-gradient-to-r from-success-500/50 via-white to-success-500/50 dark:from-primary-900 dark:via-primary-900 dark:to-primary-900 rounded-t-lg" />
            <div className="p-4 border-b border-edge-subtle">
              <div className="flex items-center gap-3">
                <StatusIconBadge tone="success" icon={Scale} size="sm" rounded="lg" />
                <div>
                  <h4 className="font-semibold text-primary-900 dark:text-neutral-50">Outstanding Intercompany Positions</h4>
                  <p className="body-sm">Who owes whom, per currency, from the ledger</p>
                </div>
              </div>
            </div>
            <div className="divide-y divide-edge-subtle">{positionList}</div>
          </Card>

          {/* Settlement History */}
          <Card>
            <div className="p-4 border-b">
              <h4 className="font-medium">Recent Settlements</h4>
            </div>
            <DataTable
              hairline
              data={transactions.filter(tx => tx.transactionType === 'SETTLEMENT' || tx.status === 'SETTLED').slice(0, 10)}
              keyExtractor={(tx) => tx.id}
              emptyTitle="No settlement history yet"
              emptyDescription=""
              columns={[
                { key: 'settlementRef', header: 'Reference', render: (_, tx) => (
                  <p className="text-body-sm font-medium">{tx.settlementRef || tx.transactionRef}</p>
                ) },
                { key: 'type', header: 'Type', minWidth: 110, dropOrder: 2, render: () => (
                  <Badge variant="success" size="sm">Bilateral</Badge>
                ) },
                { key: 'entities', header: 'Entities', render: (_, tx) => (
                  <p className="text-body-sm">{tx.payingEntityCode} ↔ {tx.behalfEntityCode}</p>
                ) },
                { key: 'netAmount', header: 'Net Amount', align: 'right', render: (_, tx) => (
                  <p className="text-body-sm font-medium">{formatCurrency(tx.netAmount || tx.amount, tx.currencyCode)}</p>
                ) },
                { key: 'status', header: 'Status', minWidth: 110, dropOrder: 3, render: () => (
                  <Badge variant="success" size="sm">Settled</Badge>
                ) },
                { key: 'settledAt', header: 'Settled At', minWidth: 120, dropOrder: 1, render: (_, tx) => (
                  <p className="body-sm">{formatDate(tx.settledAt)}</p>
                ) },
              ]}
            />
          </Card>
        </div>
      )}

      {/* Recharges Tab */}
      {activeTab === 'recharges' && (
        <div className="space-y-6 animate-fade-in" style={{ animationDelay: '0.1s' }}>
          {/* Stats Cards — same StatTile/TileAmount consistency fix as the
              POBO/COBO/Settlement tabs above. */}
          <StatStrip>
            <StatTile tone="neutral" label="Total Recharges" value={pendingRecharges.length} />
            <StatTile tone="warning" label="Pending Approval" value={pendingRecharges.filter(r => r.status === 'PENDING').length} />
            <StatTile tone="neutral" label="Total Amount" value={<TileAmount value={pendingRecharges.reduce((sum, r) => sum + (r.totalRecharge || 0), 0)} />} />
            <StatTile tone="success" label="Approved" value={pendingRecharges.filter(r => r.status === 'APPROVED').length} />
          </StatStrip>

          {/* Pending Recharges Table */}
          <Card hover>
            <div className="h-1 bg-gradient-to-r from-warning-50/50 via-white to-primary-50/50 dark:from-primary-900 dark:via-primary-900 dark:to-primary-900" />
            <div className="p-4 border-b border-edge-subtle flex items-center justify-between">
              <h3 className="section-title">Pending POBO Recharges</h3>
              <Button variant="outline" size="sm" leftIcon={<RefreshCw className="w-4 h-4" />} onClick={fetchData}>
                Refresh
              </Button>
            </div>
            <DataTable
              hairline
              data={pendingRecharges}
              keyExtractor={(r) => r.id}
              emptyTitle="No pending recharges found"
              emptyDescription=""
              columns={[
                { key: 'rechargeReference', header: 'Reference', render: (_, recharge) => (
                  <span className="font-medium text-primary-700 dark:text-neutral-200">{recharge.rechargeReference}</span>
                ) },
                { key: 'payerEntityCode', header: 'Payer (Treasury)', minWidth: 160, dropOrder: 3, render: (_, recharge) => (
                  <div>
                    <p className="font-medium">{recharge.payerEntityCode}</p>
                    <p className="caption">{recharge.payerEntityName}</p>
                  </div>
                ) },
                { key: 'behalfEntityCode', header: 'On Behalf Of', minWidth: 160, dropOrder: 2, render: (_, recharge) => (
                  <div>
                    <p className="font-medium">{recharge.behalfEntityCode}</p>
                    <p className="caption">{recharge.behalfEntityName}</p>
                  </div>
                ) },
                { key: 'totalRecharge', header: 'Amount', align: 'right', render: (_, recharge) => (
                  <span className="amount">{formatCurrency(recharge.totalRecharge, recharge.currencyCode)}</span>
                ) },
                { key: 'status', header: 'Status', render: (_, recharge) => (
                  <Badge
                    variant={
                      recharge.status === 'PENDING' ? 'warning' :
                      recharge.status === 'APPROVED' ? 'success' :
                      recharge.status === 'CANCELLED' ? 'error' : 'neutral'
                    }
                    size="sm"
                  >
                    {recharge.status}
                  </Badge>
                ) },
                { key: 'createdAt', header: 'Created', minWidth: 120, dropOrder: 1, render: (_, recharge) => (
                  <span className="body-sm">{formatDate(recharge.createdAt)}</span>
                ) },
                { key: 'actions', header: 'Actions', render: (_, recharge) => (
                  <>
                    {recharge.status === 'PENDING' && (
                      <TreasuryOnly>
                        <div className="flex items-center justify-center gap-1">
                          <Button
                            size="sm"
                            variant="ghost"
                            onClick={(e) => { e.stopPropagation(); handleApproveRecharge(recharge.id); }}
                            disabled={rechargeProcessing === recharge.id}
                            className="text-success-600 hover:bg-success-50 dark:text-success-300 dark:hover:bg-success-500/10"
                          >
                            {rechargeProcessing === recharge.id ? (
                              <Loader2 className="w-4 h-4 animate-spin" />
                            ) : (
                              <CheckCircle className="w-4 h-4" />
                            )}
                          </Button>
                          <Button
                            size="sm"
                            variant="ghost"
                            onClick={(e) => { e.stopPropagation(); handleRejectRecharge(recharge.id); }}
                            disabled={rechargeProcessing === recharge.id}
                            className="text-error-600 hover:bg-error-50 dark:text-error-300 dark:hover:bg-error-500/10"
                          >
                            <XCircle className="w-4 h-4" />
                          </Button>
                        </div>
                      </TreasuryOnly>
                    )}
                    {recharge.status === 'APPROVED' && (
                      <span className="caption-success">
                        Approved by {recharge.approvedBy}
                      </span>
                    )}
                  </>
                ) },
              ]}
            />
          </Card>
        </div>
      )}

      {/* Transactions Tab */}
      {activeTab === 'transactions' && (
        <Card hover className="animate-fade-in" style={{ animationDelay: '0.1s' }}>
          {/* Premium Gradient Header */}
          <div className="h-1 bg-gradient-to-r from-primary-50/50 via-white to-info-50/50 dark:from-primary-900 dark:via-primary-900 dark:to-primary-900" />
          <div className="p-4 border-b border-edge-subtle flex items-center justify-between">
            <h3 className="section-title">Recent Transactions</h3>
            <div className="flex items-center gap-2">
              <select className="text-body-sm border border-edge rounded-lg px-3 py-1.5 focus:ring-2 focus:ring-primary-500/20 focus:border-primary-300">
                <option value="">All Types</option>
                <option value="POBO">POBO</option>
                <option value="COBO">COBO</option>
                <option value="SETTLEMENT">Settlement</option>
              </select>
              <select className="text-body-sm border border-edge rounded-lg px-3 py-1.5 focus:ring-2 focus:ring-primary-500/20 focus:border-primary-300">
                <option value="">All Status</option>
                <option value="PENDING">Pending</option>
                <option value="PROCESSED">Processed</option>
                <option value="SETTLED">Settled</option>
              </select>
              <Button variant="outline" size="sm" leftIcon={<Download className="w-4 h-4" />} onClick={handleExport}>
                Export
              </Button>
            </div>
          </div>
          <div className="overflow-x-auto">
            {/* eslint-disable-next-line no-restricted-syntax -- rows come from the separate TransactionRow component */}
            <table className="data-table">
              <thead className="data-table-header">
                <tr>
                  <th className="data-table-header-cell">Reference</th>
                  <th className="data-table-header-cell">Type</th>
                  <th className="data-table-header-cell">Payer</th>
                  <th className="data-table-header-cell text-center"></th>
                  <th className="data-table-header-cell">Behalf Of</th>
                  <th className="data-table-header-cell text-right">Amount</th>
                  <th className="data-table-header-cell">Status</th>
                  <th className="data-table-header-cell text-center">Actions</th>
                </tr>
              </thead>
              <tbody>
                {transactions.length === 0 ? (
                  <tr>
                    <td colSpan={8} className="px-4 py-12 text-center">
                      <StatusIconBadge tone="neutral" icon={Receipt} size="xl" className="mx-auto mb-4" />
                      <p className="text-neutral-500 dark:text-neutral-400">No transactions found</p>
                    </td>
                  </tr>
                ) : transactions.map(tx => (
                  <TransactionRow
                    key={tx.id}
                    transaction={tx}
                    onView={
                      // eslint-disable-next-line no-console -- placeholder: per-transaction detail view is not implemented
                      () => console.log('View:', tx.id)
                    }
                  />
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {/* Entities Tab */}
      {activeTab === 'entities' && (
        <div className="grid gap-4 grid-cols-3">
          {entitiesWithPositions.length === 0 ? (
            <Card className="col-span-full text-center py-12 animate-fade-in">
              <StatusIconBadge tone="neutral" icon={Building2} size="xl" className="mx-auto mb-4" />
              <p className="text-neutral-500 dark:text-neutral-400">No entities with intercompany positions</p>
            </Card>
          ) : entitiesWithPositions.map((entity, i) => (
            <Card key={entity.entityId} hover className="animate-fade-in" style={{ animationDelay: `${0.05 + i * 0.05}s` }}>
              <div className="p-4">
                {/* Premium Gradient Header */}
                <div className="absolute top-0 left-0 right-0 h-1 bg-gradient-to-r from-primary-50/50 via-white to-accent-50/50 dark:from-primary-900 dark:via-primary-900 dark:to-primary-900 rounded-t-lg" />

                <div className="flex items-center gap-3 mb-4">
                  <StatusIconBadge tone="primary" icon={Building2} size="lg" />
                  <div className="flex-1">
                    <p className="font-semibold text-primary-900 dark:text-neutral-50">{entity.entityName}</p>
                    <p className="body-sm">{entity.entityCode}</p>
                  </div>
                  <Badge variant={entity.status === 'ACTIVE' ? 'success' : 'warning'}>
                    {entity.status}
                  </Badge>
                </div>

                <div className="space-y-3">
                  <div>
                    <div className="flex justify-between text-body-sm mb-1">
                      <span className="text-neutral-500 dark:text-neutral-400">Credit Utilization</span>
                      <span className="font-medium text-primary-900 dark:text-neutral-50">
                        {entity.creditLimit ? ((entity.currentExposure / entity.creditLimit) * 100).toFixed(0) : 0}%
                      </span>
                    </div>
                    <div className="w-full h-2 bg-surface-muted rounded-full overflow-hidden">
                      <div
                        className={cn(
                          'h-full rounded-full transition-all duration-500',
                          entity.creditLimit && (entity.currentExposure / entity.creditLimit) > 0.8 ? 'bg-error-500' :
                          entity.creditLimit && (entity.currentExposure / entity.creditLimit) > 0.5 ? 'bg-warning-500' : 'bg-success-500'
                        )}
                        style={{ width: `${Math.min(entity.creditLimit ? (entity.currentExposure / entity.creditLimit) * 100 : 0, 100)}%` }}
                      />
                    </div>
                  </div>

                  <div className="grid grid-cols-2 gap-2 text-body-sm">
                    <div className="p-3 bg-surface-page rounded-lg">
                      <p className="label">Limit</p>
                      <p className="font-semibold text-primary-900 mt-1 dark:text-neutral-50"><TileAmount value={entity.creditLimit || 0} currency={entity.currency} /></p>
                    </div>
                    <div className="p-3 bg-success-50 rounded-lg dark:bg-success-500/10">
                      <p className="label">Available</p>
                      <p className="font-semibold text-success-600 mt-1 dark:text-success-300"><TileAmount value={entity.availableLimit || 0} currency={entity.currency} /></p>
                    </div>
                  </div>
                </div>
              </div>
            </Card>
          ))}
        </div>
      )}

      {/* Entity Pairs Tab: one card per subsidiary and currency with a position open in the ledger */}
      {activeTab === 'pairs' && (
        <div className="grid gap-4 grid-cols-2">
          {positions.length === 0 ? (
            <Card className="col-span-full animate-fade-in">{positionList}</Card>
          ) : positions.map((p, i) => (
            <div key={`${p.subsidiaryEntityId}-${p.currencyCode}`} className="animate-fade-in" style={{ animationDelay: `${0.05 + i * 0.05}s` }}>
              <PositionCard position={p} />
            </div>
          ))}
        </div>
      )}

      {/* Modals */}
      <CoboModal
        isOpen={showCoboModal}
        onClose={() => setShowCoboModal(false)}
        entities={legalEntities}
        corporateId={selectedCorporateId}
        onSuccess={fetchData}
      />
    </Page>
  );
};

export default IntercompanyDashboardPage;