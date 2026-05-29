/**
 * Enhanced POBO (Pay On Behalf Of) Component
 * 
 * Integrates with:
 * - In-House Bank (IHB) for intercompany loans
 * - Hierarchy for entity selection
 * - Tax/Charges for fee calculation
 */

import React, { useState, useEffect, useCallback } from 'react';
import {
  ArrowLeftRight, Building2, Landmark, CheckCircle, AlertCircle, AlertTriangle,
  ChevronDown, DollarSign, Info, Loader2, TrendingUp, TrendingDown, Percent,
  RefreshCw, Eye, Clock, CreditCard, ArrowRight, Shield, Banknote, FileText,
} from 'lucide-react';
import { Card, CardHeader, Button, Badge, Input } from '../components/ui';
import { Modal, ProgressBar } from '../components/ui/enhanced';
import { formatCurrency, formatDate, cn } from '../utils';
import { intercompanyApi, ihbIntegrationApi, HierarchyEntity, PoboRequest, PoboResult } from '../services/api';

// ============================================================================
// TYPES
// ============================================================================

interface EntityWithPosition extends HierarchyEntity {
  netPosition?: number;
  creditAvailable?: number;
  pendingPobo?: number;
  interestRate?: number;
}

interface PoboPreview {
  baseAmount: number;
  charges: { code: string; name: string; amount: number; waived: boolean }[];
  totalCharges: number;
  ihbLoanAmount: number;
  ihbInterestRate: number;
  estimatedInterest: number;
  netPayment: number;
  warnings: string[];
}

interface PoboComponentProps {
  enabled: boolean;
  onToggle: (enabled: boolean) => void;
  payingEntityId: string | null;
  behalfEntityId: string | null;
  onSelectPayingEntity: (id: string, name: string, entity: EntityWithPosition) => void;
  onSelectBehalfEntity: (id: string, name: string, entity: EntityWithPosition) => void;
  amount: number;
  currencyCode: string;
  onPreviewUpdate?: (preview: PoboPreview | null) => void;
}

// ============================================================================
// ENTITY POSITION CARD
// ============================================================================

const EntityPositionCard: React.FC<{
  entity: EntityWithPosition;
  role: 'PAYER' | 'BENEFICIARY';
  amount?: number;
}> = ({ entity, role, amount }) => {
  const isPositive = (entity.netPosition || 0) >= 0;
  const hasCapacity = (entity.creditAvailable || 0) >= (amount || 0);

  return (
    <div className="bg-white dark:bg-primary-900 rounded-lg border border-neutral-200 dark:border-primary-800 p-3 space-y-2">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          {role === 'PAYER' ? (
            <Landmark className="w-4 h-4 text-primary-600 dark:text-primary-200" />
          ) : (
            <Building2 className="w-4 h-4 text-accent-600 dark:text-accent-300" />
          )}
          <span className="text-sm font-medium text-primary-900 dark:text-neutral-50">{entity.entityName}</span>
        </div>
        <Badge variant={entity.status === 'ACTIVE' ? 'success' : 'neutral'} size="sm">
          {entity.status}
        </Badge>
      </div>

      <div className="grid grid-cols-3 gap-2 text-xs">
        <div>
          <p className="text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">Balance</p>
          <p className={cn('font-medium', isPositive ? 'text-success-600 dark:text-success-300' : 'text-error-600 dark:text-error-300')}>
            {formatCurrency(entity.currentBalance || 0, entity.currencyCode)}
          </p>
        </div>
        <div>
          <p className="text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">Net Position</p>
          <p className={cn('font-medium', isPositive ? 'text-success-600 dark:text-success-300' : 'text-error-600 dark:text-error-300')}>
            {formatCurrency(entity.netPosition || 0, entity.currencyCode)}
          </p>
        </div>
        <div>
          <p className="text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">Credit Available</p>
          <p className={cn('font-medium', hasCapacity ? 'text-info-600 dark:text-info-300' : 'text-warning-600 dark:text-warning-300')}>
            {formatCurrency(entity.creditAvailable || entity.creditLimit || 0, entity.currencyCode)}
          </p>
        </div>
      </div>

      {role === 'PAYER' && amount && !hasCapacity && (
        <div className="flex items-center gap-1 text-xs text-warning-600 dark:text-warning-300 bg-warning-50 dark:bg-warning-500/10 rounded p-2">
          <AlertTriangle className="w-3 h-3" />
          <span>Insufficient credit limit for this transaction</span>
        </div>
      )}

      {entity.pendingPobo && entity.pendingPobo > 0 && (
        <div className="flex items-center gap-1 text-xs text-info-600 dark:text-info-300">
          <Clock className="w-3 h-3" />
          <span>Pending POBO: {formatCurrency(entity.pendingPobo, entity.currencyCode)}</span>
        </div>
      )}
    </div>
  );
};

// ============================================================================
// IHB LOAN PREVIEW
// ============================================================================

const IhbLoanPreview: React.FC<{
  payingEntity: EntityWithPosition | null;
  behalfEntity: EntityWithPosition | null;
  amount: number;
  currencyCode: string;
  interestRate: number;
}> = ({ payingEntity, behalfEntity, amount, currencyCode, interestRate }) => {
  if (!payingEntity || !behalfEntity) return null;

  const dailyInterest = (amount * interestRate) / 100 / 365;
  const monthlyInterest = dailyInterest * 30;

  return (
    <Card className="bg-primary-50 border-primary-200 dark:bg-primary-800/40 dark:border-primary-700">
      <div className="flex items-center gap-2 mb-3">
        <CreditCard className="w-5 h-5 text-primary-600 dark:text-primary-200" />
        <h4 className="text-sm font-semibold text-primary-900 dark:text-neutral-50">IHB Intercompany Loan</h4>
      </div>

      <div className="space-y-3">
        {/* Flow Diagram */}
        <div className="flex items-center justify-between bg-white dark:bg-primary-900 rounded-lg p-3">
          <div className="text-center">
            <Landmark className="w-6 h-6 text-primary-600 mx-auto mb-1 dark:text-primary-200" />
            <p className="text-xs font-medium text-primary-900 dark:text-neutral-50">{payingEntity.entityCode}</p>
            <p className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">Lender</p>
          </div>
          <div className="flex-1 flex items-center justify-center">
            <div className="flex items-center gap-2">
              <div className="w-12 h-0.5 bg-primary-300" />
              <ArrowRight className="w-5 h-5 text-primary-500" />
              <div className="flex flex-col items-center">
                <p className="text-sm font-bold text-primary-900 dark:text-neutral-50">{formatCurrency(amount, currencyCode)}</p>
                <p className="text-xs text-primary-600 dark:text-primary-200">@ {interestRate}% p.a.</p>
              </div>
              <ArrowRight className="w-5 h-5 text-primary-500" />
              <div className="w-12 h-0.5 bg-primary-300" />
            </div>
          </div>
          <div className="text-center">
            <Building2 className="w-6 h-6 text-accent-600 dark:text-accent-300 mx-auto mb-1" />
            <p className="text-xs font-medium text-primary-900 dark:text-neutral-50">{behalfEntity.entityCode}</p>
            <p className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">Borrower</p>
          </div>
        </div>

        {/* Interest Breakdown */}
        <div className="grid grid-cols-3 gap-2 text-xs">
          <div className="bg-white dark:bg-primary-900 rounded p-2">
            <p className="text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">Principal</p>
            <p className="font-semibold text-primary-900 dark:text-neutral-50">{formatCurrency(amount, currencyCode)}</p>
          </div>
          <div className="bg-white dark:bg-primary-900 rounded p-2">
            <p className="text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">Daily Interest</p>
            <p className="font-semibold text-info-600 dark:text-info-300">{formatCurrency(dailyInterest, currencyCode)}</p>
          </div>
          <div className="bg-white dark:bg-primary-900 rounded p-2">
            <p className="text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">Est. Monthly</p>
            <p className="font-semibold text-info-600 dark:text-info-300">{formatCurrency(monthlyInterest, currencyCode)}</p>
          </div>
        </div>

        <div className="flex items-center gap-2 text-xs text-primary-700 bg-white dark:bg-primary-900 rounded p-2 dark:text-neutral-200">
          <Info className="w-4 h-4" />
          <span>Loan will be automatically settled during monthly intercompany netting</span>
        </div>
      </div>
    </Card>
  );
};

// ============================================================================
// MAIN POBO COMPONENT
// ============================================================================

export const EnhancedPoboPicker: React.FC<PoboComponentProps> = ({
  enabled,
  onToggle,
  payingEntityId,
  behalfEntityId,
  onSelectPayingEntity,
  onSelectBehalfEntity,
  amount,
  currencyCode,
  onPreviewUpdate,
}) => {
  const [entities, setEntities] = useState<EntityWithPosition[]>([]);
  const [loading, setLoading] = useState(false);
  const [showPayingPicker, setShowPayingPicker] = useState(false);
  const [showBehalfPicker, setShowBehalfPicker] = useState(false);
  const [preview, setPreview] = useState<PoboPreview | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [validationErrors, setValidationErrors] = useState<string[]>([]);
  const [validationWarnings, setValidationWarnings] = useState<string[]>([]);

  const payingEntity = entities.find(e => e.id === payingEntityId);
  const behalfEntity = entities.find(e => e.id === behalfEntityId);

  // Load entities with IHB positions
  useEffect(() => {
    const loadEntities = async () => {
      setLoading(true);
      try {
        const [entitiesRes] = await Promise.all([
          intercompanyApi.getEntities({ status: 'ACTIVE' }),
        ]);

        if (entitiesRes.success) {
          // Enrich with IHB positions
          const enriched = await Promise.all(
            entitiesRes.data.map(async (entity) => {
              if (entity.ihbEntityId) {
                const positionRes = await ihbIntegrationApi.getEntityPosition(entity.ihbEntityId);
                if (positionRes.success) {
                  const pos = positionRes.data;
                  return {
                    ...entity,
                    netPosition: pos.loansAsLender.reduce((sum, l) => sum + l.amount, 0) -
                                 pos.loansAsBorrower.reduce((sum, l) => sum + l.amount, 0),
                    creditAvailable: (entity.creditLimit || 0) - (pos.loansAsBorrower.reduce((sum, l) => sum + l.amount, 0)),
                  };
                }
              }
              return entity;
            })
          );
          setEntities(enriched);
        }
      } catch (error) {
        console.error('Failed to load entities:', error);
      } finally {
        setLoading(false);
      }
    };

    if (enabled) {
      loadEntities();
    }
  }, [enabled]);

  // Generate preview when entities and amount change
  useEffect(() => {
    const generatePreview = async () => {
      if (!payingEntityId || !behalfEntityId || amount <= 0) {
        setPreview(null);
        onPreviewUpdate?.(null);
        return;
      }

      setPreviewLoading(true);
      try {
        const previewRes = await intercompanyApi.previewPobo({
          payableId: '',
          payableNumber: '',
          vendorId: '',
          vendorName: '',
          vendorBankAccountId: '',
          payingEntityId,
          behalfEntityId,
          amount,
          currencyCode,
          paymentMethod: 'BANK_TRANSFER',
          priority: 'NORMAL',
        });

        if (previewRes.success) {
          const previewData: PoboPreview = {
            baseAmount: amount,
            charges: previewRes.data.estimatedCharges,
            totalCharges: previewRes.data.estimatedCharges.reduce((sum, c) => sum + (c.waived ? 0 : c.amount), 0),
            ihbLoanAmount: amount,
            ihbInterestRate: previewRes.data.ihbInterestRate,
            estimatedInterest: (amount * previewRes.data.ihbInterestRate / 100 / 12), // Monthly estimate
            netPayment: previewRes.data.netPayment,
            warnings: previewRes.data.warnings,
          };
          setPreview(previewData);
          setValidationWarnings(previewRes.data.warnings);
          onPreviewUpdate?.(previewData);
        }

        // Validate entities
        const validation = await intercompanyApi.validateEntity(payingEntityId, 'POBO', amount);
        if (!validation.data.valid) {
          setValidationErrors([validation.data.reason || 'Validation failed']);
        } else {
          setValidationErrors([]);
        }
      } catch (error) {
        console.error('Preview failed:', error);
      } finally {
        setPreviewLoading(false);
      }
    };

    if (enabled) {
      generatePreview();
    }
  }, [enabled, payingEntityId, behalfEntityId, amount, currencyCode, onPreviewUpdate]);

  return (
    <Card className={cn('transition-colors', enabled ? 'bg-accent-50 dark:bg-accent-500/10 border-accent-200 dark:border-accent-500/30' : 'bg-neutral-50 dark:bg-primary-950')}>
      {/* Header */}
      <div className="flex items-center justify-between mb-4">
        <div className="flex items-center gap-3">
          <div className={cn('w-10 h-10 rounded-lg flex items-center justify-center', enabled ? 'bg-accent-100 dark:bg-accent-500/20' : 'bg-neutral-200 dark:bg-primary-800')}>
            <ArrowLeftRight className={cn('w-5 h-5', enabled ? 'text-accent-600 dark:text-accent-300' : 'text-neutral-500 dark:text-neutral-400 dark:text-neutral-500')} />
          </div>
          <div>
            <h4 className="text-sm font-semibold text-primary-900 dark:text-neutral-50">Pay On Behalf Of (POBO)</h4>
            <p className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">Central treasury pays vendor on behalf of subsidiary</p>
          </div>
        </div>
        <button
          onClick={() => onToggle(!enabled)}
          className={cn('w-12 h-6 rounded-full transition-colors relative', enabled ? 'bg-accent-500' : 'bg-neutral-300')}
        >
          <div className={cn('absolute top-0.5 w-5 h-5 rounded-full bg-white shadow transition-transform dark:bg-primary-900', enabled ? 'translate-x-6' : 'translate-x-0.5')} />
        </button>
      </div>

      {enabled && (
        <div className="space-y-4">
          {loading ? (
            <div className="flex items-center justify-center py-8">
              <Loader2 className="w-6 h-6 animate-spin text-accent-500" />
              <span className="ml-2 text-sm text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">Loading entities...</span>
            </div>
          ) : (
            <>
              {/* Paying Entity Selector */}
              <div className="relative">
                <label className="block text-xs font-medium text-neutral-600 dark:text-neutral-300 mb-1">
                  Paying Entity (Treasury) *
                </label>
                <button
                  onClick={() => setShowPayingPicker(!showPayingPicker)}
                  className="w-full flex items-center justify-between p-3 border border-neutral-300 dark:border-primary-700 rounded-lg bg-white hover:border-accent-400 transition-colors dark:bg-primary-900"
                >
                  {payingEntity ? (
                    <div className="flex items-center gap-2">
                      <Landmark className="w-4 h-4 text-primary-600 dark:text-primary-200" />
                      <div className="text-left">
                        <span className="text-sm font-medium text-primary-900 dark:text-neutral-50">{payingEntity.entityName}</span>
                        <span className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500 ml-2">({payingEntity.entityCode})</span>
                      </div>
                    </div>
                  ) : (
                    <span className="text-sm text-neutral-400 dark:text-neutral-500">Select paying entity...</span>
                  )}
                  <ChevronDown className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
                </button>

                {showPayingPicker && (
                  <div className="absolute z-20 mt-1 w-full bg-white border border-neutral-200 dark:border-primary-800 rounded-lg shadow-lg max-h-64 overflow-y-auto dark:bg-primary-900">
                    {entities
                      .filter(e => e.entityType === 'PARENT' || e.entityType === 'SUBSIDIARY')
                      .map((entity) => (
                        <button
                          key={entity.id}
                          onClick={() => {
                            onSelectPayingEntity(entity.id, entity.entityName, entity);
                            setShowPayingPicker(false);
                          }}
                          className={cn(
                            'w-full p-3 hover:bg-neutral-50 dark:hover:bg-primary-800/50 transition-colors text-left border-b border-neutral-100 dark:border-primary-800/60 last:border-b-0',
                            payingEntityId === entity.id && 'bg-accent-50 dark:bg-accent-500/10'
                          )}
                        >
                          <div className="flex items-center justify-between">
                            <div className="flex items-center gap-2">
                              <Landmark className="w-4 h-4 text-primary-600 dark:text-primary-200" />
                              <div>
                                <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{entity.entityName}</p>
                                <p className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">{entity.entityCode} • {entity.entityType}</p>
                              </div>
                            </div>
                            <div className="text-right">
                              <p className="text-sm font-semibold text-primary-900 dark:text-neutral-50">
                                {formatCurrency(entity.currentBalance || 0, entity.currencyCode)}
                              </p>
                              <p className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">
                                Credit: {formatCurrency(entity.creditAvailable || entity.creditLimit || 0, entity.currencyCode)}
                              </p>
                            </div>
                          </div>
                        </button>
                      ))}
                  </div>
                )}
              </div>

              {payingEntity && <EntityPositionCard entity={payingEntity} role="PAYER" amount={amount} />}

              {/* Behalf Entity Selector */}
              <div className="relative">
                <label className="block text-xs font-medium text-neutral-600 dark:text-neutral-300 mb-1">
                  On Behalf Of (Subsidiary) *
                </label>
                <button
                  onClick={() => setShowBehalfPicker(!showBehalfPicker)}
                  className="w-full flex items-center justify-between p-3 border border-neutral-300 dark:border-primary-700 rounded-lg bg-white hover:border-accent-400 transition-colors dark:bg-primary-900"
                >
                  {behalfEntity ? (
                    <div className="flex items-center gap-2">
                      <Building2 className="w-4 h-4 text-accent-600 dark:text-accent-300" />
                      <div className="text-left">
                        <span className="text-sm font-medium text-primary-900 dark:text-neutral-50">{behalfEntity.entityName}</span>
                        <span className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500 ml-2">({behalfEntity.entityCode})</span>
                      </div>
                    </div>
                  ) : (
                    <span className="text-sm text-neutral-400 dark:text-neutral-500">Select subsidiary...</span>
                  )}
                  <ChevronDown className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
                </button>

                {showBehalfPicker && (
                  <div className="absolute z-20 mt-1 w-full bg-white border border-neutral-200 dark:border-primary-800 rounded-lg shadow-lg max-h-64 overflow-y-auto dark:bg-primary-900">
                    {entities
                      .filter(e => e.id !== payingEntityId)
                      .map((entity) => (
                        <button
                          key={entity.id}
                          onClick={() => {
                            onSelectBehalfEntity(entity.id, entity.entityName, entity);
                            setShowBehalfPicker(false);
                          }}
                          className={cn(
                            'w-full p-3 hover:bg-neutral-50 dark:hover:bg-primary-800/50 transition-colors text-left border-b border-neutral-100 dark:border-primary-800/60 last:border-b-0',
                            behalfEntityId === entity.id && 'bg-accent-50 dark:bg-accent-500/10'
                          )}
                        >
                          <div className="flex items-center justify-between">
                            <div className="flex items-center gap-2">
                              <Building2 className="w-4 h-4 text-accent-600 dark:text-accent-300" />
                              <div>
                                <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{entity.entityName}</p>
                                <p className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">{entity.entityCode} • {entity.entityType}</p>
                              </div>
                            </div>
                            <Badge variant="neutral" size="sm">{entity.currencyCode}</Badge>
                          </div>
                        </button>
                      ))}
                  </div>
                )}
              </div>

              {behalfEntity && <EntityPositionCard entity={behalfEntity} role="BENEFICIARY" />}

              {/* IHB Loan Preview */}
              {payingEntity && behalfEntity && amount > 0 && (
                <IhbLoanPreview
                  payingEntity={payingEntity}
                  behalfEntity={behalfEntity}
                  amount={amount}
                  currencyCode={currencyCode}
                  interestRate={preview?.ihbInterestRate || payingEntity.interestRateLend || 3.5}
                />
              )}

              {/* Validation Errors */}
              {validationErrors.length > 0 && (
                <div className="bg-error-50 dark:bg-error-500/10 border border-error-200 dark:border-error-500/30 rounded-lg p-3">
                  {validationErrors.map((error, idx) => (
                    <div key={idx} className="flex items-center gap-2 text-sm text-error-700 dark:text-error-300">
                      <AlertCircle className="w-4 h-4" />
                      <span>{error}</span>
                    </div>
                  ))}
                </div>
              )}

              {/* Warnings */}
              {validationWarnings.length > 0 && (
                <div className="bg-warning-50 dark:bg-warning-500/10 border border-warning-200 dark:border-warning-500/30 rounded-lg p-3 space-y-1">
                  {validationWarnings.map((warning, idx) => (
                    <div key={idx} className="flex items-center gap-2 text-sm text-warning-700 dark:text-warning-300">
                      <AlertTriangle className="w-4 h-4" />
                      <span>{warning}</span>
                    </div>
                  ))}
                </div>
              )}

              {/* POBO Summary */}
              {payingEntity && behalfEntity && (
                <div className="bg-white dark:bg-primary-900 rounded-lg p-3 border border-accent-200 dark:border-accent-500/30">
                  <div className="flex items-center gap-2">
                    <CheckCircle className="w-5 h-5 text-accent-600 dark:text-accent-300" />
                    <span className="text-sm font-medium text-accent-900">
                      <strong>{payingEntity.entityName}</strong> will pay <strong>{formatCurrency(amount, currencyCode)}</strong> on behalf of <strong>{behalfEntity.entityName}</strong>
                    </span>
                  </div>
                  <div className="mt-2 text-xs text-neutral-600 dark:text-neutral-300 space-y-1">
                    <p>• An intercompany loan will be created in the In-House Bank</p>
                    <p>• {behalfEntity.entityName} will have a receivable from {payingEntity.entityName}</p>
                    <p>• Settlement will occur during the monthly netting cycle</p>
                  </div>
                </div>
              )}
            </>
          )}
        </div>
      )}
    </Card>
  );
};

export default EnhancedPoboPicker;