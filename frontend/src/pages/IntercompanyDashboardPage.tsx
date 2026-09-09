import React, { useState, useEffect, useCallback } from 'react';
import axios from 'axios';
import toast from 'react-hot-toast';
import {
  ArrowLeftRight, Building2, Plus, CheckCircle, CheckCircle2, Clock, AlertCircle, Loader2,
  RefreshCw, DollarSign, TrendingUp, Eye, Users, ArrowRight, ArrowLeft,
  GitMerge, Send, Download, Filter, Wallet, CreditCard, Scale,
  BarChart3, Activity, Zap, Receipt, Building, X, XCircle, Ban,
} from 'lucide-react';
import { Card, Button, Badge, Input, StatusIconBadge, StatTile } from '../components/ui';
import { TileAmount } from '../components/TileAmount';
import { HeroMetricCard } from '../components/ui/HeroMetricCard';
import { Page } from '../components/layout/Page';
import { StatStrip } from '../components/layout/StatStrip';
import { ScopeSelector } from '../components/layout/ScopeSelector';
import { CurrencyPicker } from '../components/ui/CurrencyPicker';
import { Modal } from '../components/ui/enhanced';
import { usePageHeaderActions } from '../context/PageHeaderContext';
import { formatCompactCurrency, formatCurrency, cn } from '../utils';
import { useUser } from '../context/UserContext';
import { usePermissions } from '../hooks/usePermissions';
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

interface EntityPairSummary {
  entity1Id: string;
  entity1Code: string;
  entity1Name: string;
  entity2Id: string;
  entity2Code: string;
  entity2Name: string;
  entity1OwesEntity2: number;
  entity2OwesEntity1: number;
  netPosition: number;
  netCreditorId: string;
  netDebtorId: string;
  totalTransactions: number;
  pendingTransactions: number;
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
  ihbLoanId?: string;
  ihbDepositId?: string;
  viban?: string;
  settlementRef?: string;
  processedAt?: string;
  settledAt?: string;
  createdAt: string;
}

interface IntercompanyStats {
  totalPoboTransactions: number;
  totalCoboTransactions: number;
  pendingSettlement: number;
  totalPoboVolume: number;
  totalCoboVolume: number;
  activeEntities: number;
}

interface PositionSummary {
  corporateId: string;
  totalEntities: number;
  totalOutstandingPayables: number;
  totalOutstandingReceivables: number;
  netPosition: number;
  pendingTransactions: number;
  pendingRecharges: number;
  currency: string;
  asOfDate: string;
}

interface PoboPreview {
  payingEntityId: string;
  payingEntityCode: string;
  payingEntityName: string;
  behalfEntityId: string;
  behalfEntityCode: string;
  behalfEntityName: string;
  amount: number;
  currencyCode: string;
  charges: number;
  totalAmount: number;
  ihbLoanPreview?: {
    lenderId: string;
    borrowerId: string;
    principalAmount: number;
    interestRate: number;
    estimatedInterest: number;
  };
  withinCreditLimit: boolean;
  availableLimit: number;
  warnings: string[];
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
  getStats: async (corporateId?: string): Promise<IntercompanyStats> => {
    const params: any = {};
    if (corporateId) params.corporateId = corporateId;
    const response = await apiClient.get('/intercompany/stats', { params });
    return response.data.data;
  },

  // Position Summary
  getPositionSummary: async (corporateId: string): Promise<PositionSummary> => {
    const response = await apiClient.get('/intercompany/position-summary', { params: { corporateId } });
    return response.data.data;
  },

  // Entity Pairs
  getEntityPairs: async (corporateId: string): Promise<EntityPairSummary[]> => {
    const response = await apiClient.get('/intercompany/entity-pairs', { params: { corporateId } });
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

  // POBO
  previewPobo: async (data: { payingEntityId: string; behalfEntityId: string; amount: number; currencyCode?: string }): Promise<PoboPreview> => {
    const response = await apiClient.post('/intercompany/pobo/preview', data);
    return response.data.data;
  },

  executePobo: async (data: { payingEntityId: string; behalfEntityId: string; amount: number; currencyCode?: string; description?: string; createIhbLoan?: boolean }) => {
    const response = await apiClient.post('/intercompany/pobo/execute', data);
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

  settleBilateral: async (data: { entity1Id: string; entity2Id: string; settlementMethod?: string; settledBy?: string; notes?: string }) => {
    const response = await apiClient.post('/intercompany/settle-bilateral', data);
    return response.data.data;
  },

  // Entities with positions
  getEntitiesWithPositions: async (corporateId?: string) => {
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
    <div className="w-12 h-12 rounded-xl bg-primary-100 flex items-center justify-center dark:bg-primary-700">
      <Loader2 className="w-6 h-6 animate-spin text-primary-600 dark:text-primary-200" />
    </div>
    <span className="ml-3 text-neutral-600 font-medium dark:text-neutral-300">{text}</span>
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

// Picker now uses the shared `<ScopeSelector mode="corporate-entity">`
// primitive from components/layout/ — see import block at the top.
// The inline `CorporateEntityFilterBar` that lived here is removed.

// ============================================================================
// POBO/COBO MODAL
// ============================================================================

interface PoboCoboModalProps {
  isOpen: boolean;
  onClose: () => void;
  mode: 'POBO' | 'COBO';
  entities: LegalEntity[];
  corporateId: string;
  onSuccess: () => void;
}

const PoboCoboModal: React.FC<PoboCoboModalProps> = ({ isOpen, onClose, mode, entities, corporateId, onSuccess }) => {
  const [step, setStep] = useState<'form' | 'preview' | 'result'>('form');
  const [loading, setLoading] = useState(false);
  const [preview, setPreview] = useState<PoboPreview | null>(null);
  const [result, setResult] = useState<any>(null);
  const [error, setError] = useState<string | null>(null);
  
  const [formData, setFormData] = useState({
    payingEntityId: '',
    behalfEntityId: '',
    amount: '',
    currencyCode: 'AED',
    description: '',
    createIhbLoan: true,
    generateViban: false,
  });

  const isPOBO = mode === 'POBO';
  const title = isPOBO ? 'Pay On Behalf Of (POBO)' : 'Collect On Behalf Of (COBO)';
  const primaryEntity = isPOBO ? 'Treasury (Payer)' : 'Treasury (Collector)';
  const secondaryEntity = 'Subsidiary (Behalf Of)';

  // Filter entities - treasury centers for primary, others for secondary
  const treasuryEntities = entities.filter(e => e.isTreasuryCenter && e.status === 'ACTIVE');
  const subsidiaryEntities = entities.filter(e => !e.isTreasuryCenter && e.status === 'ACTIVE');

  const handlePreview = async () => {
    setLoading(true);
    setError(null);
    try {
      const previewData = await intercompanyApi.previewPobo({
        payingEntityId: formData.payingEntityId,
        behalfEntityId: formData.behalfEntityId,
        amount: parseFloat(formData.amount),
        currencyCode: formData.currencyCode,
      });
      setPreview(previewData);
      setStep('preview');
    } catch (err: any) {
      const errorMsg = err.response?.data?.message || err.message || 'Failed to preview';
      setError(errorMsg);
      toast.error(errorMsg);
    } finally {
      setLoading(false);
    }
  };

  const handleExecute = async () => {
    setLoading(true);
    setError(null);
    try {
      let response;
      if (isPOBO) {
        response = await intercompanyApi.executePobo({
          payingEntityId: formData.payingEntityId,
          behalfEntityId: formData.behalfEntityId,
          amount: parseFloat(formData.amount),
          currencyCode: formData.currencyCode,
          description: formData.description,
          createIhbLoan: formData.createIhbLoan,
        });
      } else {
        response = await intercompanyApi.setupCobo({
          collectingEntityId: formData.payingEntityId,
          behalfEntityId: formData.behalfEntityId,
          amount: parseFloat(formData.amount),
          currencyCode: formData.currencyCode,
          description: formData.description,
          generateViban: formData.generateViban,
        });
      }
      setResult(response);
      setStep('result');
      toast.success(`${mode} transaction executed successfully`);
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
      createIhbLoan: true,
      generateViban: false,
    });
    setPreview(null);
    setResult(null);
    setError(null);
    onClose();
    if (result) onSuccess();
  };

  return (
    <Modal isOpen={isOpen} onClose={resetAndClose} title={title} size="md">
      {/* Step Indicator */}
      <div className="flex items-center justify-center gap-2 mb-6">
        {['form', 'preview', 'result'].map((s, i) => (
          <React.Fragment key={s}>
            <div className={cn(
              'w-8 h-8 rounded-full flex items-center justify-center text-sm font-medium',
              step === s ? 'bg-primary-600 text-white' : 
              ['form', 'preview', 'result'].indexOf(step) > i ? 'bg-success-100 text-success-700 dark:bg-success-500/20 dark:text-success-300' : 
              'bg-neutral-100 text-neutral-400 dark:bg-primary-800 dark:text-neutral-500'
            )}>
              {i + 1}
            </div>
            {i < 2 && <div className={cn('w-8 h-0.5', 
              ['form', 'preview', 'result'].indexOf(step) > i ? 'bg-success-400' : 'bg-neutral-200 dark:bg-primary-800'
            )} />}
          </React.Fragment>
        ))}
      </div>

      {/* Error Display */}
      {error && (
        <div className="mb-4 p-3 bg-error-50 border border-error-200 rounded-lg flex items-start gap-2 dark:bg-error-500/10 dark:border-error-500/30">
          <AlertCircle className="w-5 h-5 text-error-600 mt-0.5 dark:text-error-300" />
          <span className="text-sm text-error-700 dark:text-error-300">{error}</span>
        </div>
      )}

      {/* Form Step */}
      {step === 'form' && (
        <div className="space-y-4">
          <div className="p-3 rounded-lg bg-info-50 text-sm text-info-800 dark:bg-info-500/10 dark:text-info-300">
            {isPOBO 
              ? 'Treasury will pay the vendor on behalf of a subsidiary. The subsidiary will owe treasury for reimbursement.'
              : 'Treasury will collect payment from a customer on behalf of a subsidiary. Treasury will owe the subsidiary.'}
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
              placeholder={isPOBO ? "e.g., Vendor payment for supplies" : "e.g., Customer invoice collection"}
              value={formData.description}
              onChange={(e) => setFormData({ ...formData, description: e.target.value })}
            />
          </div>

          <div className="space-y-2">
            {isPOBO && (
              <label className="flex items-center gap-2">
                <input 
                  type="checkbox" 
                  checked={formData.createIhbLoan}
                  onChange={(e) => setFormData({ ...formData, createIhbLoan: e.target.checked })}
                  className="rounded text-primary-600 dark:text-primary-200"
                />
                <span className="text-sm text-neutral-700 dark:text-neutral-200">Create IHB loan for subsidiary reimbursement</span>
              </label>
            )}
            {!isPOBO && (
              <label className="flex items-center gap-2">
                <input 
                  type="checkbox" 
                  checked={formData.generateViban}
                  onChange={(e) => setFormData({ ...formData, generateViban: e.target.checked })}
                  className="rounded text-primary-600 dark:text-primary-200"
                />
                <span className="text-sm text-neutral-700 dark:text-neutral-200">Generate VIBAN for collection</span>
              </label>
            )}
          </div>
        </div>
      )}

      {/* Preview Step */}
      {step === 'preview' && preview && (
        <div className="space-y-4">
          <div className="p-4 bg-neutral-50 rounded-lg dark:bg-primary-950">
            <div className="flex items-center justify-between mb-4">
              <div className="text-center">
                <p className="text-sm text-neutral-500 dark:text-neutral-400">{isPOBO ? 'Payer' : 'Collector'}</p>
                <p className="font-medium">{preview.payingEntityCode}</p>
                <p className="text-xs text-neutral-500 dark:text-neutral-400">{preview.payingEntityName}</p>
              </div>
              <div className="flex items-center gap-2">
                {isPOBO ? <ArrowRight className="w-6 h-6 text-primary-400" /> : <ArrowLeft className="w-6 h-6 text-primary-400" />}
                <div className="text-center">
                  <p className="text-lg font-bold text-primary-600 dark:text-primary-200">
                    {formatCurrency(preview.amount, formData.currencyCode)}
                  </p>
                </div>
                {isPOBO ? <ArrowRight className="w-6 h-6 text-primary-400" /> : <ArrowLeft className="w-6 h-6 text-primary-400" />}
              </div>
              <div className="text-center">
                <p className="text-sm text-neutral-500 dark:text-neutral-400">On Behalf Of</p>
                <p className="font-medium">{preview.behalfEntityCode}</p>
                <p className="text-xs text-neutral-500 dark:text-neutral-400">{preview.behalfEntityName}</p>
              </div>
            </div>
          </div>

          <div className="space-y-2">
            <div className="flex justify-between text-sm">
              <span className="text-neutral-500 dark:text-neutral-400">Principal Amount</span>
              <span>{formatCurrency(preview.amount, formData.currencyCode)}</span>
            </div>
            <div className="flex justify-between text-sm">
              <span className="text-neutral-500 dark:text-neutral-400">Service Charges</span>
              <span>{formatCurrency(preview.charges, formData.currencyCode)}</span>
            </div>
            <div className="flex justify-between font-medium border-t pt-2">
              <span>Total Amount</span>
              <span>{formatCurrency(preview.totalAmount, formData.currencyCode)}</span>
            </div>
          </div>

          {!preview.withinCreditLimit && (
            <div className="p-3 bg-error-50 rounded-lg dark:bg-error-500/10">
              <p className="text-sm text-error-700 dark:text-error-300">
                ⚠️ Amount exceeds available credit limit ({formatCurrency(preview.availableLimit, formData.currencyCode)})
              </p>
            </div>
          )}

          {preview.warnings?.length > 0 && (
            <div className="p-3 bg-warning-50 rounded-lg dark:bg-warning-500/10">
              {preview.warnings.map((w, i) => (
                <p key={i} className="text-sm text-warning-800 dark:text-warning-300">{w}</p>
              ))}
            </div>
          )}

          {preview.ihbLoanPreview && (
            <div className="p-3 bg-info-50 rounded-lg dark:bg-info-500/10">
              <p className="text-sm font-medium text-info-800 mb-2 dark:text-info-300">IHB Loan Preview</p>
              <div className="grid grid-cols-3 gap-2 text-sm">
                <div>
                  <p className="text-neutral-500 dark:text-neutral-400">Principal</p>
                  <p className="font-medium">{formatCurrency(preview.ihbLoanPreview.principalAmount, formData.currencyCode)}</p>
                </div>
                <div>
                  <p className="text-neutral-500 dark:text-neutral-400">Interest Rate</p>
                  <p className="font-medium">{preview.ihbLoanPreview.interestRate}%</p>
                </div>
                <div>
                  <p className="text-neutral-500 dark:text-neutral-400">Est. Interest (30d)</p>
                  <p className="font-medium">{formatCurrency(preview.ihbLoanPreview.estimatedInterest, formData.currencyCode)}</p>
                </div>
              </div>
            </div>
          )}
        </div>
      )}

      {/* Result Step */}
      {step === 'result' && result && (
        <div className="text-center py-6">
          <div className="w-16 h-16 bg-success-100 rounded-full flex items-center justify-center mx-auto mb-4 dark:bg-success-500/20">
            <CheckCircle className="w-8 h-8 text-success-600 dark:text-success-300" />
          </div>
          <h3 className="text-lg font-medium text-neutral-900 mb-2 dark:text-neutral-50">Transaction Successful</h3>
          <p className="text-neutral-600 mb-4 dark:text-neutral-300">{mode} transaction executed successfully</p>
          <div className="p-3 bg-neutral-50 rounded-lg inline-block dark:bg-primary-950">
            <p className="text-sm text-neutral-500 dark:text-neutral-400">Reference</p>
            <p className="font-mono font-medium text-primary-600 dark:text-primary-200">{result.transactionRef}</p>
          </div>
          {result.ihbLoanRef && (
            <div className="mt-3 p-3 bg-info-50 rounded-lg inline-block dark:bg-info-500/10">
              <p className="text-sm text-neutral-500 dark:text-neutral-400">IHB Loan Reference</p>
              <p className="font-mono font-medium text-info-600 dark:text-info-300">{result.ihbLoanRef}</p>
            </div>
          )}
          {result.viban && (
            <div className="mt-3 p-3 bg-success-50 rounded-lg inline-block dark:bg-success-500/10">
              <p className="text-sm text-neutral-500 dark:text-neutral-400">VIBAN Generated</p>
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
              onClick={handlePreview} 
              disabled={loading || !formData.payingEntityId || !formData.behalfEntityId || !formData.amount}
            >
              {loading && <Loader2 className="w-4 h-4 animate-spin mr-2" />}
              Preview
            </Button>
          </>
        )}
        {step === 'preview' && (
          <>
            <Button variant="ghost" onClick={() => setStep('form')}>Back</Button>
            <Button onClick={handleExecute} disabled={loading || (preview && !preview.withinCreditLimit)}>
              {loading && <Loader2 className="w-4 h-4 animate-spin mr-2" />}
              Execute {mode}
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
// ENTITY PAIR CARD
// ============================================================================

interface EntityPairCardProps {
  pair: EntityPairSummary;
  onViewDetails: () => void;
  onSettle: () => void;
}

const EntityPairCard: React.FC<EntityPairCardProps> = ({ pair, onViewDetails, onSettle }) => {
  const netCreditor = pair.netPosition > 0 ? pair.entity1Code : pair.entity2Code;
  const netDebtor = pair.netPosition > 0 ? pair.entity2Code : pair.entity1Code;

  return (
    <Card hover className="group">
      <div className="p-4">
        {/* Premium Gradient Header */}
        <div className="absolute top-0 left-0 right-0 h-1 bg-gradient-to-r from-primary-50/50 via-white to-info-50/50 dark:from-primary-900 dark:via-primary-900 dark:to-primary-900 rounded-t-xl" />

        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-4">
            <div className="flex items-center gap-2">
              <div className="w-10 h-10 bg-primary-100 rounded-xl flex items-center justify-center dark:bg-primary-700">
                <Building2 className="w-5 h-5 text-primary-600 dark:text-primary-200" />
              </div>
              <div>
                <p className="font-medium text-primary-900 dark:text-neutral-50">{pair.entity1Code}</p>
                <p className="text-xs text-neutral-500 dark:text-neutral-400">{pair.entity1Name}</p>
              </div>
            </div>
            <ArrowLeftRight className="w-5 h-5 text-neutral-300 dark:text-neutral-600" />
            <div className="flex items-center gap-2">
              <div className="w-10 h-10 bg-info-100 rounded-xl flex items-center justify-center dark:bg-info-500/20">
                <Building2 className="w-5 h-5 text-info-600 dark:text-info-300" />
              </div>
              <div>
                <p className="font-medium text-primary-900 dark:text-neutral-50">{pair.entity2Code}</p>
                <p className="text-xs text-neutral-500 dark:text-neutral-400">{pair.entity2Name}</p>
              </div>
            </div>
          </div>
          <Badge variant={pair.pendingTransactions > 0 ? 'warning' : 'success'}>
            {pair.pendingTransactions > 0 ? `${pair.pendingTransactions} pending` : 'Settled'}
          </Badge>
        </div>

        <div className="grid grid-cols-3 gap-3 mb-4">
          <div className="text-center p-3 bg-error-50 rounded-xl dark:bg-error-500/10">
            <p className="text-xs text-neutral-500 uppercase tracking-wider dark:text-neutral-400">{pair.entity1Code} owes</p>
            <p className="font-semibold text-error-700 mt-1 dark:text-error-300">
              {formatCompactCurrency(pair.entity1OwesEntity2, 'AED')}
            </p>
          </div>
          <div className="text-center p-3 bg-success-50 rounded-xl dark:bg-success-500/10">
            <p className="text-xs text-neutral-500 uppercase tracking-wider dark:text-neutral-400">{pair.entity2Code} owes</p>
            <p className="font-semibold text-success-700 mt-1 dark:text-success-300">
              {formatCompactCurrency(pair.entity2OwesEntity1, 'AED')}
            </p>
          </div>
          <div className={cn(
            'text-center p-3 rounded-xl',
            pair.netPosition > 0 ? 'bg-primary-50 dark:bg-primary-800/40' : 'bg-info-50 dark:bg-info-500/10'
          )}>
            <p className="text-xs text-neutral-500 uppercase tracking-wider dark:text-neutral-400">Net Position</p>
            <p className="font-bold text-primary-700 mt-1 dark:text-neutral-200">
              {formatCompactCurrency(Math.abs(pair.netPosition), 'AED')}
            </p>
          </div>
        </div>

        <div className="text-sm text-center text-neutral-600 mb-4 p-2 bg-neutral-50 rounded-lg dark:text-neutral-300 dark:bg-primary-950">
          <span className="font-medium text-success-600 dark:text-success-300">{netCreditor}</span>
          {' receives '}
          <span className="font-semibold text-primary-900 dark:text-neutral-50">{formatCompactCurrency(Math.abs(pair.netPosition), 'AED')}</span>
          {' from '}
          <span className="font-medium text-error-600 dark:text-error-300">{netDebtor}</span>
        </div>

        <div className="flex gap-2 pt-3 border-t border-neutral-100 dark:border-primary-800/60">
          <Button size="sm" variant="outline" onClick={onViewDetails} className="flex-1 opacity-70 group-hover:opacity-100 transition-opacity">
            <Eye className="w-4 h-4 mr-1" /> Details
          </Button>
          {pair.pendingTransactions > 0 && (
            <Button size="sm" onClick={onSettle} className="flex-1">
              <Scale className="w-4 h-4 mr-1" /> Settle
            </Button>
          )}
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
        <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{transaction.transactionRef}</p>
        <p className="text-xs text-neutral-500 dark:text-neutral-400">{formatDate(transaction.createdAt)}</p>
      </td>
      <td className="data-table-cell">
        <span className={cn('px-2 py-1 text-xs font-medium rounded-full', typeColors[transaction.transactionType] || 'bg-neutral-100 dark:bg-primary-800')}>
          {transaction.transactionType.replace('_', ' ')}
        </span>
      </td>
      <td className="data-table-cell">
        <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{transaction.payingEntityCode}</p>
        <p className="text-xs text-neutral-500 dark:text-neutral-400">{transaction.payingEntityName}</p>
      </td>
      <td className="data-table-cell text-center">
        <ArrowRight className="w-4 h-4 text-neutral-400 inline dark:text-neutral-500" />
      </td>
      <td className="data-table-cell">
        <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{transaction.behalfEntityCode}</p>
        <p className="text-xs text-neutral-500 dark:text-neutral-400">{transaction.behalfEntityName}</p>
      </td>
      <td className="data-table-cell text-right">
        <p className="text-sm font-semibold text-primary-900 dark:text-neutral-50">{formatCurrency(transaction.amount, transaction.currencyCode)}</p>
        {transaction.charges > 0 && (
          <p className="text-xs text-neutral-500 dark:text-neutral-400">+{formatCurrency(transaction.charges, transaction.currencyCode)} fees</p>
        )}
      </td>
      <td className="data-table-cell">
        <span className={cn('px-2 py-1 text-xs font-medium rounded-full', statusColors[transaction.status] || 'bg-neutral-100 dark:bg-primary-800')}>
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
  const { canApprovePOBORecharge, canRejectPOBORecharge } = usePermissions();
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
  const [positionSummary, setPositionSummary] = useState<PositionSummary | null>(null);
  const [entityPairs, setEntityPairs] = useState<EntityPairSummary[]>([]);
  const [transactions, setTransactions] = useState<IntercompanyTransaction[]>([]);
  const [entitiesWithPositions, setEntitiesWithPositions] = useState<any[]>([]);
  const [intercompanyVas, setIntercompanyVas] = useState<any[]>([]);

  // Recharges state
  const [pendingRecharges, setPendingRecharges] = useState<any[]>([]);
  const [rechargeProcessing, setRechargeProcessing] = useState<string | null>(null);
  
  // Modal states
  const [showPoboModal, setShowPoboModal] = useState(false);
  const [showCoboModal, setShowCoboModal] = useState(false);
  const [selectedPair, setSelectedPair] = useState<EntityPairSummary | null>(null);

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
      
      const [statsData, positionData, pairsData, txData, entitiesData, icVasData, rechargesData] = await Promise.all([
        intercompanyApi.getStats(selectedCorporateId).catch(() => null),
        intercompanyApi.getPositionSummary(selectedCorporateId).catch(() => null),
        intercompanyApi.getEntityPairs(selectedCorporateId).catch(() => []),
        intercompanyApi.getTransactions({
          corporateId: selectedCorporateId,
          entityId: selectedEntityId || undefined,
          size: 50
        }).catch(() => ({ content: [] })),
        intercompanyApi.getEntitiesWithPositions(selectedCorporateId).catch(() => []),
        // Fetch INTERCOMPANY VAs
        apiClient.get(`/virtual-accounts/corporate/${selectedCorporateId}/category/INTERCOMPANY`)
          .then(r => r.data?.data || [])
          .catch(() => []),
        // Fetch pending POBO recharges
        apiClient.get('/pobo/recharges/pending')
          .then(r => r.data?.data || [])
          .catch(() => []),
      ]);

      setStats(statsData);
      setPositionSummary(positionData);
      setEntityPairs(pairsData);
      setTransactions(txData?.content || txData || []);
      setEntitiesWithPositions(entitiesData);
      setIntercompanyVas(icVasData);
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

  const handleSettle = async (pair: EntityPairSummary) => {
    try {
      await intercompanyApi.settleBilateral({
        entity1Id: pair.entity1Id,
        entity2Id: pair.entity2Id,
        settlementMethod: 'BILATERAL',
        settledBy: approverName,
      });
      toast.success('Bilateral settlement completed');
      fetchData();
    } catch (err: any) {
      toast.error(err.response?.data?.message || 'Settlement failed');
    }
  };

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
        <Button size="sm" leftIcon={<CreditCard className="w-4 h-4" />} onClick={() => setShowPoboModal(true)} disabled={!selectedCorporateId}>
          POBO
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
          <Building2 className="w-12 h-12 text-neutral-300 mx-auto mb-4 dark:text-neutral-600" />
          <h3 className="text-lg font-medium text-neutral-900 mb-2 dark:text-neutral-50">Select a Corporate</h3>
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
    { id: 'pairs', label: 'Entity Pairs', icon: ArrowLeftRight, count: entityPairs.length },
  ];

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
              value: <TileAmount value={stats.totalPoboVolume} currency="AED" />,
              sub: `${stats.totalPoboTransactions} payments on behalf of subsidiaries`,
            }}
            secondary={{
              label: 'COBO Volume',
              value: <TileAmount value={stats.totalCoboVolume} currency="AED" />,
              sub: `${stats.totalCoboTransactions} collections on behalf`,
            }}
            icon={<TrendingUp className="w-7 h-7 text-accent-600 dark:text-accent-300" />}
          />

          {/* Operational metrics — secondary strip below the hero. */}
          <StatStrip>
            <StatTile layout="row" tone="primary" icon={<CreditCard className="w-5 h-5" />} label="POBO Count" value={stats.totalPoboTransactions} delay="0.18s" />
            <StatTile layout="row" tone="info" icon={<Wallet className="w-5 h-5" />} label="COBO Count" value={stats.totalCoboTransactions} delay="0.21s" />
            <StatTile layout="row" tone="warning" icon={<Clock className="w-5 h-5" />} label="Pending" value={stats.pendingSettlement} delay="0.24s" />
            <StatTile layout="row" tone="accent" icon={<Building2 className="w-5 h-5" />} label="Entities" value={stats.activeEntities} delay="0.27s" />
          </StatStrip>
        </>
      )}

      {/* Premium Tabs */}
      <div className="flex gap-1 border-b border-neutral-200 animate-fade-in dark:border-primary-800" style={{ animationDelay: '0.45s' }}>
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
      {activeTab === 'overview' && positionSummary && (
        <div className="grid grid-cols-3 gap-6 animate-fade-in" style={{ animationDelay: '0.5s' }}>
          <div className="col-span-2">
            <Card hover>
              <div className="h-1 bg-gradient-to-r from-primary-500 via-info-500 to-accent-500 rounded-t-xl" />
              <div className="p-6">
                <div className="flex items-center gap-3 mb-6">
                  <StatusIconBadge tone="primary" icon={BarChart3} className="dark:bg-primary-700" />
                  <h3 className="section-title">Corporate Position Summary</h3>
                </div>
                <StatStrip className="mb-6">
                  <StatTile tone="danger" label="Total Payables" value={<TileAmount value={positionSummary.totalOutstandingPayables} currency={positionSummary.currency} />} />
                  <StatTile tone="success" label="Total Receivables" value={<TileAmount value={positionSummary.totalOutstandingReceivables} currency={positionSummary.currency} />} />
                  <StatTile tone="primary" label="Net Position" value={<TileAmount value={positionSummary.netPosition} currency={positionSummary.currency} />} />
                </StatStrip>

                <div className="flex items-center justify-between p-4 bg-neutral-50 rounded-xl dark:bg-primary-950">
                  <div className="flex items-center gap-6">
                    <div>
                      <p className="label">Pending Transactions</p>
                      <p className="stat-value-xs">{positionSummary.pendingTransactions}</p>
                    </div>
                    <div>
                      <p className="label">Pending Recharges</p>
                      <p className="stat-value-xs">{positionSummary.pendingRecharges}</p>
                    </div>
                    <div>
                      <p className="label">Active Entities</p>
                      <p className="stat-value-xs">{positionSummary.totalEntities}</p>
                    </div>
                  </div>
                  <Button variant="outline" size="sm" leftIcon={<Download className="w-4 h-4" />} onClick={handleExport}>
                    Export Report
                  </Button>
                </div>
              </div>
            </Card>
          </div>

          <div className="space-y-4">
            <Card hover>
              <div className="p-4">
                <h4 className="text-sm font-semibold text-primary-900 mb-4 dark:text-neutral-50">Quick Actions</h4>
                <div className="space-y-2">
                  <Button className="w-full justify-start" variant="outline" leftIcon={<CreditCard className="w-4 h-4" />} onClick={() => setShowPoboModal(true)}>
                    New POBO Payment
                  </Button>
                  <Button className="w-full justify-start" variant="outline" leftIcon={<Wallet className="w-4 h-4" />} onClick={() => setShowCoboModal(true)}>
                    Setup COBO Collection
                  </Button>
                  <Button className="w-full justify-start" variant="outline" leftIcon={<Scale className="w-4 h-4" />}>
                    Bilateral Settlement
                  </Button>
                  <Button className="w-full justify-start" variant="outline" leftIcon={<GitMerge className="w-4 h-4" />}>
                    Add to Netting Cycle
                  </Button>
                </div>
              </div>
            </Card>

            <Card hover>
              <div className="p-4">
                <h4 className="text-sm font-semibold text-primary-900 mb-3 dark:text-neutral-50">Settlement Options</h4>
                <div className="space-y-2 text-sm">
                  <div className="flex items-center gap-2 p-2.5 bg-warning-50 rounded-xl dark:bg-warning-500/10">
                    <Zap className="w-4 h-4 text-warning-600 dark:text-warning-300" />
                    <span className="font-medium text-neutral-700 dark:text-neutral-200">Direct Payment</span>
                  </div>
                  <div className="flex items-center gap-2 p-2.5 bg-primary-50 rounded-xl dark:bg-primary-800/40">
                    <GitMerge className="w-4 h-4 text-primary-600 dark:text-primary-200" />
                    <span className="font-medium text-neutral-700 dark:text-neutral-200">Multilateral Netting</span>
                  </div>
                  <div className="flex items-center gap-2 p-2.5 bg-info-50 rounded-xl dark:bg-info-500/10">
                    <Building2 className="w-4 h-4 text-info-600 dark:text-info-300" />
                    <span className="font-medium text-neutral-700 dark:text-neutral-200">IHB Loan/Deposit</span>
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
          <div className="flex items-center justify-end">
            <Button leftIcon={<Plus className="w-4 h-4" />} onClick={() => setShowPoboModal(true)}>
              New POBO Payment
            </Button>
          </div>

          <div className="grid grid-cols-4 gap-4">
            <Card hover className="bg-gradient-to-br from-primary-50/50 via-white to-primary-50/50 border-primary-200/60 dark:from-primary-900 dark:via-primary-900 dark:to-primary-900">
              <div className="p-4">
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <p className="label">Total POBO</p>
                    <p className="stat-value-sm mt-1">{stats?.totalPoboTransactions || 0}</p>
                  </div>
                  <StatusIconBadge tone="primary" icon={CreditCard} className="dark:bg-primary-700" />
                </div>
              </div>
            </Card>
            <Card hover>
              <div className="p-4">
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <p className="label">POBO Volume</p>
                    <p className="stat-value-sm mt-1 text-success-600 dark:text-success-300">{formatCompactCurrency(stats?.totalPoboVolume || 0, 'AED')}</p>
                  </div>
                  <StatusIconBadge tone="success" icon={TrendingUp} className="dark:bg-success-500/20" />
                </div>
              </div>
            </Card>
            <Card hover>
              <div className="p-4">
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <p className="label">Pending Recharges</p>
                    <p className="stat-value-warning mt-1">{positionSummary?.pendingRecharges || 0}</p>
                  </div>
                  <StatusIconBadge tone="warning" icon={Clock} className="dark:bg-warning-500/20" />
                </div>
              </div>
            </Card>
            <Card hover>
              <div className="p-4">
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <p className="label">Outstanding</p>
                    <p className="stat-value-sm mt-1 text-error-600 dark:text-error-300">{formatCompactCurrency(positionSummary?.totalOutstandingPayables || 0, 'AED')}</p>
                  </div>
                  <StatusIconBadge tone="error" icon={AlertCircle} className="dark:bg-error-500/20" />
                </div>
              </div>
            </Card>
          </div>

          <Card hover>
            <div className="h-1 bg-gradient-to-r from-primary-500/50 via-white to-primary-500/50 dark:from-primary-900 dark:via-primary-900 dark:to-primary-900 rounded-t-xl" />
            <div className="p-4 border-b border-neutral-100 flex items-center justify-between dark:border-primary-800/60">
              <div className="flex items-center gap-3">
                <StatusIconBadge tone="primary" icon={CreditCard} size="sm" rounded="lg" className="dark:bg-primary-700" />
                <h4 className="font-semibold text-primary-900 dark:text-neutral-50">POBO Transactions</h4>
                {isTreasuryView && (
                  <span className="ml-2 px-2 py-0.5 text-xs font-medium bg-success-100 text-success-700 rounded-full dark:bg-success-500/20 dark:text-success-300">
                    Treasury View (Receivables)
                  </span>
                )}
                {isSubsidiaryView && (
                  <span className="ml-2 px-2 py-0.5 text-xs font-medium bg-warning-100 text-warning-700 rounded-full dark:bg-warning-500/20 dark:text-warning-300">
                    Subsidiary View (Payables)
                  </span>
                )}
              </div>
              <div className="flex items-center gap-2">
                <select className="text-sm border rounded-lg px-3 py-1.5">
                  <option value="">All Status</option>
                  <option value="PENDING">Pending Recharge</option>
                  <option value="PROCESSED">Processed</option>
                  <option value="SETTLED">Settled</option>
                </select>
              </div>
            </div>
            <div className="overflow-x-auto">
              <table className="data-table">
                <thead className="data-table-header">
                  <tr>
                    <th className="data-table-header-cell">Reference</th>
                    <th className="data-table-header-cell">{isTreasuryView ? 'Treasury (Creditor)' : 'Subsidiary (Debtor)'}</th>
                    <th className="data-table-header-cell text-center"></th>
                    <th className="data-table-header-cell">{isTreasuryView ? 'Subsidiary (Owes)' : 'Treasury (Owed To)'}</th>
                    <th className="data-table-header-cell text-right">Amount</th>
                    <th className="data-table-header-cell">IHB Loan</th>
                    <th className="data-table-header-cell">Status</th>
                    <th className="data-table-header-cell text-center">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {transactions.filter(poboTransactionFilter).length === 0 ? (
                    <tr>
                      <td colSpan={8} className="px-4 py-12 text-center">
                        <div className="w-16 h-16 rounded-2xl bg-neutral-100 flex items-center justify-center mx-auto mb-4 dark:bg-primary-800">
                          <CreditCard className="w-8 h-8 text-neutral-400 dark:text-neutral-500" />
                        </div>
                        <p className="text-neutral-500 font-medium dark:text-neutral-400">No POBO transactions found</p>
                        <Button size="sm" variant="outline" className="mt-4" onClick={() => setShowPoboModal(true)}>
                          Create First POBO
                        </Button>
                      </td>
                    </tr>
                  ) : transactions.filter(poboTransactionFilter).map(tx => (
                    <tr key={tx.id} className="data-table-row group">
                      <td className="data-table-cell">
                        <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{tx.transactionRef}</p>
                        <p className="text-xs text-neutral-500 dark:text-neutral-400">{formatDate(tx.createdAt)}</p>
                      </td>
                      <td className="data-table-cell">
                        <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{tx.payingEntityCode}</p>
                        <p className="text-xs text-neutral-500 dark:text-neutral-400">{tx.payingEntityName}</p>
                      </td>
                      <td className="data-table-cell text-center">
                        <ArrowRight className="w-4 h-4 text-primary-400 inline" />
                      </td>
                      <td className="data-table-cell">
                        <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{tx.behalfEntityCode}</p>
                        <p className="text-xs text-neutral-500 dark:text-neutral-400">{tx.behalfEntityName}</p>
                      </td>
                      <td className="data-table-cell text-right">
                        <p className="text-sm font-semibold text-primary-900 dark:text-neutral-50">{formatCurrency(tx.amount, tx.currencyCode)}</p>
                      </td>
                      <td className="data-table-cell">
                        {tx.ihbLoanId ? (
                          <Badge variant="info" size="sm">Loan Created</Badge>
                        ) : (
                          <span className="text-neutral-400 dark:text-neutral-500">—</span>
                        )}
                      </td>
                      <td className="data-table-cell">
                        <Badge variant={tx.status === 'SETTLED' ? 'success' : tx.status === 'PENDING' ? 'warning' : 'primary'} size="sm">
                          {tx.status}
                        </Badge>
                      </td>
                      <td className="data-table-cell text-center">
                        <Button size="sm" variant="ghost" className="opacity-0 group-hover:opacity-100 transition-opacity"><Eye className="w-4 h-4" /></Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
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

          <div className="grid grid-cols-4 gap-4">
            <Card hover className="bg-gradient-to-br from-info-50/50 via-white to-info-50/50 border-info-200/60 dark:from-primary-900 dark:via-primary-900 dark:to-primary-900">
              <div className="p-4">
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <p className="label">Total COBO</p>
                    <p className="stat-value-info mt-1">{stats?.totalCoboTransactions || 0}</p>
                  </div>
                  <StatusIconBadge tone="info" icon={Wallet} className="dark:bg-info-500/20" />
                </div>
              </div>
            </Card>
            <Card hover>
              <div className="p-4">
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <p className="label">COBO Volume</p>
                    <p className="stat-value-sm mt-1 text-accent-600 dark:text-accent-300">{formatCompactCurrency(stats?.totalCoboVolume || 0, 'AED')}</p>
                  </div>
                  <StatusIconBadge tone="accent" icon={Activity} className="dark:bg-accent-500/20" />
                </div>
              </div>
            </Card>
            <Card hover>
              <div className="p-4">
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <p className="label">Pending Distribution</p>
                    <p className="stat-value-warning mt-1">{stats?.pendingSettlement || 0}</p>
                  </div>
                  <StatusIconBadge tone="warning" icon={Clock} className="dark:bg-warning-500/20" />
                </div>
              </div>
            </Card>
            <Card hover>
              <div className="p-4">
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <p className="label">Outstanding to Subsidiaries</p>
                    <p className="stat-value-sm mt-1 text-success-600 dark:text-success-300">{formatCompactCurrency(positionSummary?.totalOutstandingReceivables || 0, 'AED')}</p>
                  </div>
                  <StatusIconBadge tone="success" icon={TrendingUp} className="dark:bg-success-500/20" />
                </div>
              </div>
            </Card>
          </div>

          <Card hover>
            <div className="h-1 bg-gradient-to-r from-info-500/50 via-white to-info-500/50 dark:from-primary-900 dark:via-primary-900 dark:to-primary-900 rounded-t-xl" />
            <div className="p-4 border-b border-neutral-100 flex items-center justify-between dark:border-primary-800/60">
              <div className="flex items-center gap-3">
                <StatusIconBadge tone="info" icon={Wallet} size="sm" rounded="lg" className="dark:bg-info-500/20" />
                <h4 className="font-semibold text-primary-900 dark:text-neutral-50">COBO Collections</h4>
              </div>
              <div className="flex items-center gap-2">
                <select className="text-sm border rounded-lg px-3 py-1.5">
                  <option value="">All Status</option>
                  <option value="ACTIVE">Active VIBAN</option>
                  <option value="COLLECTED">Collected</option>
                  <option value="DISTRIBUTED">Distributed</option>
                </select>
              </div>
            </div>
            <div className="overflow-x-auto">
              <table className="data-table">
                <thead className="data-table-header">
                  <tr>
                    <th className="data-table-header-cell">Reference</th>
                    <th className="data-table-header-cell">Treasury (Collector)</th>
                    <th className="data-table-header-cell text-center"></th>
                    <th className="data-table-header-cell">Subsidiary (Behalf Of)</th>
                    <th className="data-table-header-cell text-right">Amount</th>
                    <th className="data-table-header-cell">VIBAN</th>
                    <th className="data-table-header-cell">Status</th>
                    <th className="data-table-header-cell text-center">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {transactions.filter(coboTransactionFilter).length === 0 ? (
                    <tr>
                      <td colSpan={8} className="px-4 py-12 text-center">
                        <div className="w-16 h-16 rounded-2xl bg-neutral-100 flex items-center justify-center mx-auto mb-4 dark:bg-primary-800">
                          <Wallet className="w-8 h-8 text-neutral-400 dark:text-neutral-500" />
                        </div>
                        <p className="text-neutral-500 font-medium dark:text-neutral-400">No COBO collections found</p>
                        <Button size="sm" variant="outline" className="mt-4" onClick={() => setShowCoboModal(true)}>
                          Setup First COBO
                        </Button>
                      </td>
                    </tr>
                  ) : transactions.filter(coboTransactionFilter).map(tx => (
                    <tr key={tx.id} className="data-table-row group">
                      <td className="data-table-cell">
                        <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{tx.transactionRef}</p>
                        <p className="text-xs text-neutral-500 dark:text-neutral-400">{formatDate(tx.createdAt)}</p>
                      </td>
                      <td className="data-table-cell">
                        <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{tx.payingEntityCode}</p>
                        <p className="text-xs text-neutral-500 dark:text-neutral-400">{tx.payingEntityName}</p>
                      </td>
                      <td className="data-table-cell text-center">
                        <ArrowLeft className="w-4 h-4 text-info-400 inline" />
                      </td>
                      <td className="data-table-cell">
                        <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{tx.behalfEntityCode}</p>
                        <p className="text-xs text-neutral-500 dark:text-neutral-400">{tx.behalfEntityName}</p>
                      </td>
                      <td className="data-table-cell text-right">
                        <p className="text-sm font-semibold text-primary-900 dark:text-neutral-50">{formatCurrency(tx.amount, tx.currencyCode)}</p>
                      </td>
                      <td className="data-table-cell">
                        {tx.viban ? (
                          <code className="text-xs bg-info-50 text-info-700 px-2 py-1 rounded-lg font-medium dark:bg-info-500/10 dark:text-info-300">{tx.viban}</code>
                        ) : (
                          <span className="text-neutral-400 dark:text-neutral-500">—</span>
                        )}
                      </td>
                      <td className="data-table-cell">
                        <Badge variant={tx.status === 'SETTLED' ? 'success' : tx.status === 'ACTIVE' ? 'info' : 'warning'} size="sm">
                          {tx.status}
                        </Badge>
                      </td>
                      <td className="data-table-cell text-center">
                        <Button size="sm" variant="ghost" className="opacity-0 group-hover:opacity-100 transition-opacity"><Eye className="w-4 h-4" /></Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
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

          <div className="grid grid-cols-4 gap-4">
            <Card hover>
              <div className="p-4">
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <p className="label">Pending Settlement</p>
                    <p className="stat-value-warning mt-1">{stats?.pendingSettlement || 0}</p>
                  </div>
                  <StatusIconBadge tone="warning" icon={Clock} className="dark:bg-warning-500/20" />
                </div>
              </div>
            </Card>
            <Card hover>
              <div className="p-4">
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <p className="label">Total Payables</p>
                    <p className="stat-value-sm mt-1 text-error-600 dark:text-error-300">{formatCompactCurrency(positionSummary?.totalOutstandingPayables || 0, 'AED')}</p>
                  </div>
                  <StatusIconBadge tone="error" icon={ArrowLeft} className="dark:bg-error-500/20" />
                </div>
              </div>
            </Card>
            <Card hover>
              <div className="p-4">
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <p className="label">Total Receivables</p>
                    <p className="stat-value-sm mt-1 text-success-600 dark:text-success-300">{formatCompactCurrency(positionSummary?.totalOutstandingReceivables || 0, 'AED')}</p>
                  </div>
                  <StatusIconBadge tone="success" icon={ArrowRight} className="dark:bg-success-500/20" />
                </div>
              </div>
            </Card>
            <Card hover className="bg-gradient-to-br from-primary-50/50 via-white to-primary-50/50 border-primary-200/60 dark:from-primary-900 dark:via-primary-900 dark:to-primary-900">
              <div className="p-4">
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <p className="label">Net Position</p>
                    <p className="stat-value-sm mt-1">{formatCompactCurrency(positionSummary?.netPosition || 0, 'AED')}</p>
                  </div>
                  <StatusIconBadge tone="primary" icon={Scale} className="dark:bg-primary-700" />
                </div>
              </div>
            </Card>
          </div>

          {/* Entity Pairs for Settlement */}
          <Card hover>
            <div className="h-1 bg-gradient-to-r from-success-500/50 via-white to-success-500/50 dark:from-primary-900 dark:via-primary-900 dark:to-primary-900 rounded-t-xl" />
            <div className="p-4 border-b border-neutral-100 dark:border-primary-800/60">
              <div className="flex items-center gap-3">
                <StatusIconBadge tone="success" icon={Scale} size="sm" rounded="lg" className="dark:bg-success-500/20" />
                <div>
                  <h4 className="font-semibold text-primary-900 dark:text-neutral-50">Entity Pairs - Ready for Settlement</h4>
                  <p className="text-sm text-neutral-500 dark:text-neutral-400">Select pairs to settle bilaterally or add to netting cycle</p>
                </div>
              </div>
            </div>
            <div className="divide-y divide-neutral-100 dark:divide-primary-800/60">
              {entityPairs.filter(p => p.pendingTransactions > 0).length === 0 ? (
                <div className="px-4 py-12 text-center">
                  <div className="w-16 h-16 rounded-2xl bg-success-100 flex items-center justify-center mx-auto mb-4 dark:bg-success-500/20">
                    <CheckCircle className="w-8 h-8 text-success-500" />
                  </div>
                  <p className="text-neutral-500 font-medium dark:text-neutral-400">All intercompany positions are settled</p>
                </div>
              ) : entityPairs.filter(p => p.pendingTransactions > 0).map((pair, i) => (
                <div key={`${pair.entity1Id}-${pair.entity2Id}`} className="p-4 hover:bg-primary-50/30 transition-colors group">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-6">
                      <div className="flex items-center gap-3">
                        <div className="w-10 h-10 bg-primary-100 rounded-xl flex items-center justify-center dark:bg-primary-700">
                          <Building2 className="w-5 h-5 text-primary-600 dark:text-primary-200" />
                        </div>
                        <div>
                          <p className="font-medium text-primary-900 dark:text-neutral-50">{pair.entity1Code}</p>
                          <p className="text-xs text-neutral-500 dark:text-neutral-400">{pair.entity1Name}</p>
                        </div>
                      </div>
                      <ArrowLeftRight className="w-5 h-5 text-neutral-300 dark:text-neutral-600" />
                      <div className="flex items-center gap-3">
                        <div className="w-10 h-10 bg-info-100 rounded-xl flex items-center justify-center dark:bg-info-500/20">
                          <Building2 className="w-5 h-5 text-info-600 dark:text-info-300" />
                        </div>
                        <div>
                          <p className="font-medium text-primary-900 dark:text-neutral-50">{pair.entity2Code}</p>
                          <p className="text-xs text-neutral-500 dark:text-neutral-400">{pair.entity2Name}</p>
                        </div>
                      </div>
                    </div>
                    <div className="flex items-center gap-6">
                      <div className="text-center">
                        <p className="label">Net Position</p>
                        <p className="font-bold text-primary-600 dark:text-primary-200">{formatCompactCurrency(pair.netPosition, 'AED')}</p>
                      </div>
                      <div className="text-center">
                        <p className="label">Pending</p>
                        <Badge variant="warning">{pair.pendingTransactions}</Badge>
                      </div>
                      <div className="flex gap-2 opacity-70 group-hover:opacity-100 transition-opacity">
                        <Button size="sm" variant="outline" onClick={() => setSelectedPair(pair)}>
                          <Eye className="w-4 h-4 mr-1" /> View
                        </Button>
                        <Button size="sm" onClick={() => handleSettle(pair)}>
                          <Scale className="w-4 h-4 mr-1" /> Settle
                        </Button>
                      </div>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </Card>

          {/* Intercompany Virtual Accounts */}
          <Card hover>
            <div className="h-1 bg-gradient-to-r from-info-500/50 via-white to-info-500/50 dark:from-primary-900 dark:via-primary-900 dark:to-primary-900 rounded-t-xl" />
            <div className="p-4 border-b border-neutral-100 dark:border-primary-800/60">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-3">
                  <StatusIconBadge tone="info" icon={Wallet} size="sm" rounded="lg" className="dark:bg-info-500/20" />
                  <div>
                    <h4 className="font-semibold text-primary-900 dark:text-neutral-50">Intercompany Virtual Accounts</h4>
                    <p className="text-sm text-neutral-500 dark:text-neutral-400">Virtual accounts for intercompany positions</p>
                  </div>
                </div>
                <Badge variant="info">{intercompanyVas.length} Accounts</Badge>
              </div>
            </div>
            <div className="divide-y divide-neutral-100 dark:divide-primary-800/60">
              {intercompanyVas.length === 0 ? (
                <div className="px-4 py-12 text-center">
                  <div className="w-16 h-16 rounded-2xl bg-neutral-100 flex items-center justify-center mx-auto mb-4 dark:bg-primary-800">
                    <Wallet className="w-8 h-8 text-neutral-400 dark:text-neutral-500" />
                  </div>
                  <p className="text-neutral-500 font-medium dark:text-neutral-400">No intercompany virtual accounts configured</p>
                  <p className="text-sm text-neutral-400 mt-1 dark:text-neutral-500">Intercompany VAs will appear here when created</p>
                </div>
              ) : (
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4 p-4">
                  {intercompanyVas.map((va: any) => (
                    <div key={va.id} className="p-4 rounded-xl border border-neutral-200 hover:border-info-300 hover:shadow-sm transition-all bg-white dark:border-primary-800 dark:bg-primary-900">
                      <div className="flex items-start justify-between mb-3">
                        <div className="flex items-center gap-2">
                          <StatusIconBadge tone="info" icon={CreditCard} size="sm" rounded="lg" className="dark:bg-info-500/20" />
                          <div>
                            <p className="font-medium text-primary-900 text-sm dark:text-neutral-50">{va.vaNumber}</p>
                            <p className="text-xs text-neutral-500 dark:text-neutral-400">{va.vaName || 'Intercompany VA'}</p>
                          </div>
                        </div>
                        <Badge variant={va.status === 'ACTIVE' ? 'success' : 'neutral'} size="sm">
                          {va.status}
                        </Badge>
                      </div>
                      <div className="space-y-2">
                        <div className="flex justify-between text-sm">
                          <span className="text-neutral-500 dark:text-neutral-400">Balance</span>
                          <span className={cn(
                            "font-semibold",
                            (va.currentBalance || 0) >= 0 ? "text-success-600 dark:text-success-300" : "text-error-600 dark:text-error-300"
                          )}>
                            {formatCurrency(va.currentBalance || 0, va.currencyCode || 'AED')}
                          </span>
                        </div>
                        {va.entityCode && (
                          <div className="flex justify-between text-sm">
                            <span className="text-neutral-500 dark:text-neutral-400">Entity</span>
                            <span className="text-primary-700 font-medium dark:text-neutral-200">{va.entityCode}</span>
                          </div>
                        )}
                        {va.counterpartyEntityCode && (
                          <div className="flex justify-between text-sm">
                            <span className="text-neutral-500 dark:text-neutral-400">Counterparty</span>
                            <span className="text-info-600 font-medium dark:text-info-300">{va.counterpartyEntityCode}</span>
                          </div>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </Card>

          {/* Settlement History */}
          <Card>
            <div className="p-4 border-b">
              <h4 className="font-medium">Recent Settlements</h4>
            </div>
            <div className="overflow-x-auto">
              <table className="min-w-full divide-y divide-neutral-200 dark:divide-primary-800">
                <thead className="bg-neutral-50 dark:bg-primary-950">
                  <tr>
                    <th className="px-4 py-3 text-left text-xs font-medium text-neutral-500 uppercase dark:text-neutral-400">Reference</th>
                    <th className="px-4 py-3 text-left text-xs font-medium text-neutral-500 uppercase dark:text-neutral-400">Type</th>
                    <th className="px-4 py-3 text-left text-xs font-medium text-neutral-500 uppercase dark:text-neutral-400">Entities</th>
                    <th className="px-4 py-3 text-right text-xs font-medium text-neutral-500 uppercase dark:text-neutral-400">Net Amount</th>
                    <th className="px-4 py-3 text-left text-xs font-medium text-neutral-500 uppercase dark:text-neutral-400">Status</th>
                    <th className="px-4 py-3 text-left text-xs font-medium text-neutral-500 uppercase dark:text-neutral-400">Settled At</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-neutral-200 dark:divide-primary-800">
                  {transactions.filter(tx => tx.transactionType === 'SETTLEMENT' || tx.status === 'SETTLED').length === 0 ? (
                    <tr>
                      <td colSpan={6} className="px-4 py-8 text-center text-neutral-500 dark:text-neutral-400">
                        No settlement history yet
                      </td>
                    </tr>
                  ) : transactions.filter(tx => tx.transactionType === 'SETTLEMENT' || tx.status === 'SETTLED').slice(0, 10).map(tx => (
                    <tr key={tx.id} className="hover:bg-neutral-50 dark:hover:bg-primary-800/50">
                      <td className="px-4 py-3">
                        <p className="text-sm font-medium">{tx.settlementRef || tx.transactionRef}</p>
                      </td>
                      <td className="px-4 py-3">
                        <Badge variant="success" size="sm">Bilateral</Badge>
                      </td>
                      <td className="px-4 py-3">
                        <p className="text-sm">{tx.payingEntityCode} ↔ {tx.behalfEntityCode}</p>
                      </td>
                      <td className="px-4 py-3 text-right">
                        <p className="text-sm font-medium">{formatCurrency(tx.netAmount || tx.amount, tx.currencyCode)}</p>
                      </td>
                      <td className="px-4 py-3">
                        <Badge variant="success" size="sm">Settled</Badge>
                      </td>
                      <td className="px-4 py-3">
                        <p className="text-sm text-neutral-500 dark:text-neutral-400">
                          {formatDate(tx.settledAt)}
                        </p>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Card>
        </div>
      )}

      {/* Recharges Tab */}
      {activeTab === 'recharges' && (
        <div className="space-y-6 animate-fade-in" style={{ animationDelay: '0.1s' }}>
          {/* Stats Cards */}
          <div className="grid grid-cols-4 gap-4">
            <Card hover>
              <div className="p-4">
                <p className="label">Total Recharges</p>
                <p className="stat-value-sm mt-1">{pendingRecharges.length}</p>
              </div>
            </Card>
            <Card hover>
              <div className="p-4">
                <p className="label">Pending Approval</p>
                <p className="stat-value-warning mt-1">
                  {pendingRecharges.filter(r => r.status === 'PENDING').length}
                </p>
              </div>
            </Card>
            <Card hover>
              <div className="p-4">
                <p className="label">Total Amount</p>
                <p className="stat-value-sm mt-1">
                  {formatCompactCurrency(pendingRecharges.reduce((sum, r) => sum + (r.totalRecharge || 0), 0))}
                </p>
              </div>
            </Card>
            <Card hover>
              <div className="p-4">
                <p className="label">Approved</p>
                <p className="stat-value-success mt-1">
                  {pendingRecharges.filter(r => r.status === 'APPROVED').length}
                </p>
              </div>
            </Card>
          </div>

          {/* Pending Recharges Table */}
          <Card hover>
            <div className="h-1 bg-gradient-to-r from-warning-50/50 via-white to-primary-50/50 dark:from-primary-900 dark:via-primary-900 dark:to-primary-900" />
            <div className="p-4 border-b border-neutral-100 flex items-center justify-between dark:border-primary-800/60">
              <h3 className="section-title">Pending POBO Recharges</h3>
              <Button variant="outline" size="sm" leftIcon={<RefreshCw className="w-4 h-4" />} onClick={fetchData}>
                Refresh
              </Button>
            </div>
            <div className="overflow-x-auto">
              <table className="data-table">
                <thead className="data-table-header">
                  <tr>
                    <th className="data-table-header-cell">Reference</th>
                    <th className="data-table-header-cell">Payer (Treasury)</th>
                    <th className="data-table-header-cell">On Behalf Of</th>
                    <th className="data-table-header-cell text-right">Amount</th>
                    <th className="data-table-header-cell">Status</th>
                    <th className="data-table-header-cell">Created</th>
                    <th className="data-table-header-cell text-center">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {pendingRecharges.length === 0 ? (
                    <tr>
                      <td colSpan={7} className="data-table-cell text-center py-8 text-neutral-500 dark:text-neutral-400">
                        No pending recharges found
                      </td>
                    </tr>
                  ) : (
                    pendingRecharges.map(recharge => (
                      <tr key={recharge.id} className="data-table-row group">
                        <td className="data-table-cell font-medium text-primary-700 dark:text-neutral-200">
                          {recharge.rechargeReference}
                        </td>
                        <td className="data-table-cell">
                          <div>
                            <p className="font-medium">{recharge.payerEntityCode}</p>
                            <p className="text-xs text-neutral-500 dark:text-neutral-400">{recharge.payerEntityName}</p>
                          </div>
                        </td>
                        <td className="data-table-cell">
                          <div>
                            <p className="font-medium">{recharge.behalfEntityCode}</p>
                            <p className="text-xs text-neutral-500 dark:text-neutral-400">{recharge.behalfEntityName}</p>
                          </div>
                        </td>
                        <td className="data-table-cell text-right amount">
                          {formatCurrency(recharge.totalRecharge, recharge.currencyCode)}
                        </td>
                        <td className="data-table-cell">
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
                        </td>
                        <td className="data-table-cell text-neutral-500 text-sm dark:text-neutral-400">
                          {formatDate(recharge.createdAt)}
                        </td>
                        <td className="data-table-cell">
                          {recharge.status === 'PENDING' && (
                            <TreasuryOnly>
                              <div className="flex items-center justify-center gap-1">
                                <Button
                                  size="sm"
                                  variant="ghost"
                                  onClick={() => handleApproveRecharge(recharge.id)}
                                  disabled={rechargeProcessing === recharge.id}
                                  className="text-success-600 hover:bg-success-50 dark:text-success-300 dark:hover:bg-success-500/10"
                                >
                                  {rechargeProcessing === recharge.id ? (
                                    <Loader2 className="w-4 h-4 animate-spin" />
                                  ) : (
                                    <CheckCircle2 className="w-4 h-4" />
                                  )}
                                </Button>
                                <Button
                                  size="sm"
                                  variant="ghost"
                                  onClick={() => handleRejectRecharge(recharge.id)}
                                  disabled={rechargeProcessing === recharge.id}
                                  className="text-error-600 hover:bg-error-50 dark:text-error-300 dark:hover:bg-error-500/10"
                                >
                                  <XCircle className="w-4 h-4" />
                                </Button>
                              </div>
                            </TreasuryOnly>
                          )}
                          {recharge.status === 'APPROVED' && (
                            <span className="text-xs text-success-600 dark:text-success-300">
                              Approved by {recharge.approvedBy}
                            </span>
                          )}
                        </td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>
          </Card>
        </div>
      )}

      {/* Transactions Tab */}
      {activeTab === 'transactions' && (
        <Card hover className="animate-fade-in" style={{ animationDelay: '0.1s' }}>
          {/* Premium Gradient Header */}
          <div className="h-1 bg-gradient-to-r from-primary-50/50 via-white to-info-50/50 dark:from-primary-900 dark:via-primary-900 dark:to-primary-900" />
          <div className="p-4 border-b border-neutral-100 flex items-center justify-between dark:border-primary-800/60">
            <h3 className="section-title">Recent Transactions</h3>
            <div className="flex items-center gap-2">
              <select className="text-sm border border-neutral-200 rounded-lg px-3 py-1.5 focus:ring-2 focus:ring-primary-500/20 focus:border-primary-300 dark:border-primary-800">
                <option value="">All Types</option>
                <option value="POBO">POBO</option>
                <option value="COBO">COBO</option>
                <option value="SETTLEMENT">Settlement</option>
              </select>
              <select className="text-sm border border-neutral-200 rounded-lg px-3 py-1.5 focus:ring-2 focus:ring-primary-500/20 focus:border-primary-300 dark:border-primary-800">
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
                      <div className="w-16 h-16 rounded-2xl bg-neutral-100 flex items-center justify-center mx-auto mb-4 dark:bg-primary-800">
                        <Receipt className="w-8 h-8 text-neutral-400 dark:text-neutral-500" />
                      </div>
                      <p className="text-neutral-500 dark:text-neutral-400">No transactions found</p>
                    </td>
                  </tr>
                ) : transactions.map(tx => (
                  <TransactionRow key={tx.id} transaction={tx} onView={() => console.log('View:', tx.id)} />
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {/* Entities Tab */}
      {activeTab === 'entities' && (
        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
          {entitiesWithPositions.length === 0 ? (
            <Card className="col-span-full text-center py-12 animate-fade-in">
              <div className="w-16 h-16 rounded-2xl bg-neutral-100 flex items-center justify-center mx-auto mb-4 dark:bg-primary-800">
                <Building2 className="w-8 h-8 text-neutral-400 dark:text-neutral-500" />
              </div>
              <p className="text-neutral-500 dark:text-neutral-400">No entities with intercompany positions</p>
            </Card>
          ) : entitiesWithPositions.map((entity, i) => (
            <Card key={entity.id} hover className="animate-fade-in" style={{ animationDelay: `${0.05 + i * 0.05}s` }}>
              <div className="p-4">
                {/* Premium Gradient Header */}
                <div className="absolute top-0 left-0 right-0 h-1 bg-gradient-to-r from-primary-50/50 via-white to-accent-50/50 dark:from-primary-900 dark:via-primary-900 dark:to-primary-900 rounded-t-xl" />

                <div className="flex items-center gap-3 mb-4">
                  <div className="w-12 h-12 bg-primary-100 rounded-xl flex items-center justify-center dark:bg-primary-700">
                    <Building2 className="w-6 h-6 text-primary-600 dark:text-primary-200" />
                  </div>
                  <div className="flex-1">
                    <p className="font-semibold text-primary-900 dark:text-neutral-50">{entity.entityName}</p>
                    <p className="text-sm text-neutral-500 dark:text-neutral-400">{entity.entityCode}</p>
                  </div>
                  <Badge variant={entity.status === 'ACTIVE' ? 'success' : 'warning'}>
                    {entity.status}
                  </Badge>
                </div>

                <div className="space-y-3">
                  <div>
                    <div className="flex justify-between text-sm mb-1">
                      <span className="text-neutral-500 dark:text-neutral-400">Credit Utilization</span>
                      <span className="font-medium text-primary-900 dark:text-neutral-50">
                        {entity.creditLimit ? ((entity.currentExposure / entity.creditLimit) * 100).toFixed(0) : 0}%
                      </span>
                    </div>
                    <div className="w-full h-2 bg-neutral-100 rounded-full overflow-hidden dark:bg-primary-800">
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

                  <div className="grid grid-cols-2 gap-2 text-sm">
                    <div className="p-3 bg-neutral-50 rounded-xl dark:bg-primary-950">
                      <p className="text-xs text-neutral-500 uppercase tracking-wider dark:text-neutral-400">Limit</p>
                      <p className="font-semibold text-primary-900 mt-1 dark:text-neutral-50">{formatCompactCurrency(entity.creditLimit || 0, 'AED')}</p>
                    </div>
                    <div className="p-3 bg-success-50 rounded-xl dark:bg-success-500/10">
                      <p className="text-xs text-neutral-500 uppercase tracking-wider dark:text-neutral-400">Available</p>
                      <p className="font-semibold text-success-600 mt-1 dark:text-success-300">{formatCompactCurrency(entity.availableLimit || 0, 'AED')}</p>
                    </div>
                  </div>
                </div>
              </div>
            </Card>
          ))}
        </div>
      )}

      {/* Entity Pairs Tab */}
      {activeTab === 'pairs' && (
        <div className="grid gap-4 md:grid-cols-2">
          {entityPairs.length === 0 ? (
            <Card className="col-span-full text-center py-12 animate-fade-in">
              <div className="w-16 h-16 rounded-2xl bg-neutral-100 flex items-center justify-center mx-auto mb-4 dark:bg-primary-800">
                <ArrowLeftRight className="w-8 h-8 text-neutral-400 dark:text-neutral-500" />
              </div>
              <p className="text-neutral-500 dark:text-neutral-400">No entity pairs with intercompany positions</p>
            </Card>
          ) : entityPairs.map((pair, i) => (
            <div key={`${pair.entity1Id}-${pair.entity2Id}`} className="animate-fade-in" style={{ animationDelay: `${0.05 + i * 0.05}s` }}>
              <EntityPairCard
                pair={pair}
                onViewDetails={() => setSelectedPair(pair)}
                onSettle={() => handleSettle(pair)}
              />
            </div>
          ))}
        </div>
      )}

      {/* Modals */}
      <PoboCoboModal 
        isOpen={showPoboModal} 
        onClose={() => setShowPoboModal(false)} 
        mode="POBO"
        entities={legalEntities}
        corporateId={selectedCorporateId}
        onSuccess={fetchData}
      />
      <PoboCoboModal 
        isOpen={showCoboModal} 
        onClose={() => setShowCoboModal(false)} 
        mode="COBO"
        entities={legalEntities}
        corporateId={selectedCorporateId}
        onSuccess={fetchData}
      />
    </Page>
  );
};

export default IntercompanyDashboardPage;