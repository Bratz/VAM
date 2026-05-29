/**
 * Enhanced COBO (Collect On Behalf Of) Component
 * 
 * Integrates with:
 * - VIBAN Management for payment collection
 * - In-House Bank (IHB) for intercompany deposits
 * - Hierarchy for entity selection
 */

import React, { useState, useEffect, useCallback } from 'react';
import {
  ArrowDownLeft, Building2, Landmark, CheckCircle, AlertCircle, AlertTriangle,
  ChevronDown, DollarSign, Info, Loader2, QrCode, Copy, ExternalLink, Hash,
  RefreshCw, Eye, Clock, CreditCard, ArrowRight, Shield, Link2, FileText,
  Download, Mail, MessageSquare,
} from 'lucide-react';
import { Card, CardHeader, Button, Badge, Input } from '../components/ui';
import { Modal, ProgressBar } from '../components/ui/enhanced';
import { formatCurrency, formatDate, cn } from '../utils';
import { 
  intercompanyApi, 
  vibanIntegrationApi, 
  ihbIntegrationApi, 
  HierarchyEntity, 
  CoboRequest, 
  CoboResult,
  VibanGenerationResult 
} from '../services/intercompanyService';

// ============================================================================
// TYPES
// ============================================================================

interface EntityWithPosition extends HierarchyEntity {
  netPosition?: number;
  pendingCobo?: number;
  depositBalance?: number;
}

interface CoboPreview {
  baseAmount: number;
  collectingEntity: EntityWithPosition;
  behalfEntity: EntityWithPosition;
  vibanGenerated: boolean;
  viban?: string;
  ihbDepositRate?: number;
  warnings: string[];
}

interface CoboComponentProps {
  enabled: boolean;
  onToggle: (enabled: boolean) => void;
  collectingEntityId: string | null;
  behalfEntityId: string | null;
  onSelectCollectingEntity: (id: string, name: string, entity: EntityWithPosition) => void;
  onSelectBehalfEntity: (id: string, name: string, entity: EntityWithPosition) => void;
  amount: number;
  currencyCode: string;
  generateViban: boolean;
  onGenerateVibanChange: (generate: boolean) => void;
  invoiceNumber?: string;
  customerName?: string;
  onVibanGenerated?: (result: VibanGenerationResult) => void;
}

// ============================================================================
// VIBAN DISPLAY COMPONENT
// ============================================================================

const VibanDisplay: React.FC<{
  viban: string;
  vibanId?: string;
  paymentLink?: string;
  qrCodeData?: string;
  amount: number;
  currencyCode: string;
  customerName?: string;
  validUntil?: string;
}> = ({ viban, vibanId, paymentLink, qrCodeData, amount, currencyCode, customerName, validUntil }) => {
  const [copied, setCopied] = useState(false);
  const [showQr, setShowQr] = useState(false);

  const copyToClipboard = () => {
    navigator.clipboard.writeText(viban);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <Card className="bg-info-50 dark:bg-info-500/10 border-info-200 dark:border-info-500/30">
      <div className="flex items-center gap-2 mb-3">
        <Hash className="w-5 h-5 text-info-600 dark:text-info-300" />
        <h4 className="text-sm font-semibold text-info-900 dark:text-info-300">Dedicated VIBAN Generated</h4>
      </div>

      <div className="space-y-3">
        {/* VIBAN Display */}
        <div className="bg-white dark:bg-primary-900 rounded-lg p-4 border border-info-200 dark:border-info-500/30">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500 mb-1">Virtual IBAN for Collection</p>
              <p className="code-display">{viban}</p>
            </div>
            <div className="flex gap-2">
              <Button
                variant="outline"
                size="sm"
                onClick={copyToClipboard}
                leftIcon={copied ? <CheckCircle className="w-4 h-4 text-success-500 dark:text-success-300" /> : <Copy className="w-4 h-4" />}
              >
                {copied ? 'Copied!' : 'Copy'}
              </Button>
              {qrCodeData && (
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => setShowQr(true)}
                  leftIcon={<QrCode className="w-4 h-4" />}
                >
                  QR Code
                </Button>
              )}
            </div>
          </div>
        </div>

        {/* Payment Details */}
        <div className="grid grid-cols-2 gap-3">
          <div className="bg-white dark:bg-primary-900 rounded-lg p-3">
            <p className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">Expected Amount</p>
            <p className="text-sm font-semibold text-primary-900 dark:text-neutral-50">{formatCurrency(amount, currencyCode)}</p>
          </div>
          {customerName && (
            <div className="bg-white dark:bg-primary-900 rounded-lg p-3">
              <p className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">Customer Reference</p>
              <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{customerName}</p>
            </div>
          )}
        </div>

        {validUntil && (
          <div className="flex items-center gap-2 text-xs text-info-700 dark:text-info-300 bg-white dark:bg-primary-900 rounded p-2">
            <Clock className="w-4 h-4" />
            <span>VIBAN valid until: {formatDate(validUntil)}</span>
          </div>
        )}

        {/* Actions */}
        <div className="flex gap-2">
          {paymentLink && (
            <Button variant="outline" size="sm" leftIcon={<ExternalLink className="w-4 h-4" />}>
              Payment Link
            </Button>
          )}
          <Button variant="outline" size="sm" leftIcon={<Mail className="w-4 h-4" />}>
            Email to Customer
          </Button>
          <Button variant="outline" size="sm" leftIcon={<Download className="w-4 h-4" />}>
            Download PDF
          </Button>
        </div>

        <div className="flex items-center gap-2 text-xs text-info-600 dark:text-info-300">
          <Info className="w-4 h-4" />
          <span>Payments to this VIBAN will be automatically matched to the invoice</span>
        </div>
      </div>

      {/* QR Code Modal */}
      {showQr && qrCodeData && (
        <Modal isOpen={showQr} onClose={() => setShowQr(false)} title="Payment QR Code" size="sm">
          <div className="text-center p-4">
            <div className="w-48 h-48 mx-auto bg-white p-4 rounded-lg border dark:bg-primary-900">
              {/* QR Code would be rendered here - using placeholder */}
              <div className="w-full h-full bg-neutral-100 dark:bg-primary-800 flex items-center justify-center rounded">
                <QrCode className="w-24 h-24 text-neutral-400 dark:text-neutral-500" />
              </div>
            </div>
            <p className="text-sm text-neutral-600 dark:text-neutral-300 mt-4">Scan to pay {formatCurrency(amount, currencyCode)}</p>
            <p className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500 mt-1 font-mono">{viban}</p>
          </div>
        </Modal>
      )}
    </Card>
  );
};

// ============================================================================
// IHB DEPOSIT PREVIEW
// ============================================================================

const IhbDepositPreview: React.FC<{
  collectingEntity: EntityWithPosition | null;
  behalfEntity: EntityWithPosition | null;
  amount: number;
  currencyCode: string;
  interestRate: number;
}> = ({ collectingEntity, behalfEntity, amount, currencyCode, interestRate }) => {
  if (!collectingEntity || !behalfEntity) return null;

  const dailyInterest = (amount * interestRate) / 100 / 365;
  const monthlyInterest = dailyInterest * 30;

  return (
    <Card className="bg-success-50 dark:bg-success-500/10 border-success-200 dark:border-success-500/30">
      <div className="flex items-center gap-2 mb-3">
        <CreditCard className="w-5 h-5 text-success-600 dark:text-success-300" />
        <h4 className="text-sm font-semibold text-success-900 dark:text-success-300">IHB Intercompany Deposit</h4>
      </div>

      <div className="space-y-3">
        {/* Flow Diagram */}
        <div className="flex items-center justify-between bg-white dark:bg-primary-900 rounded-lg p-3">
          <div className="text-center">
            <Landmark className="w-6 h-6 text-success-600 dark:text-success-300 mx-auto mb-1" />
            <p className="text-xs font-medium text-primary-900 dark:text-neutral-50">{collectingEntity.entityCode}</p>
            <p className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">Collector</p>
          </div>
          <div className="flex-1 flex items-center justify-center">
            <div className="flex items-center gap-2">
              <div className="w-8 h-0.5 bg-success-300" />
              <ArrowRight className="w-4 h-4 text-success-500 dark:text-success-300" />
              <div className="flex flex-col items-center px-3 py-1 bg-success-100 rounded dark:bg-success-500/20">
                <p className="text-sm font-bold text-success-700 dark:text-success-300">{formatCurrency(amount, currencyCode)}</p>
                <p className="text-xs text-success-600 dark:text-success-300">@ {interestRate}% p.a.</p>
              </div>
              <ArrowRight className="w-4 h-4 text-success-500 dark:text-success-300" />
              <div className="w-8 h-0.5 bg-success-300" />
            </div>
          </div>
          <div className="text-center">
            <Building2 className="w-6 h-6 text-accent-600 dark:text-accent-300 mx-auto mb-1" />
            <p className="text-xs font-medium text-primary-900 dark:text-neutral-50">{behalfEntity.entityCode}</p>
            <p className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">Beneficiary</p>
          </div>
        </div>

        {/* Interest Breakdown */}
        <div className="grid grid-cols-3 gap-2 text-xs">
          <div className="bg-white dark:bg-primary-900 rounded p-2">
            <p className="text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">Amount</p>
            <p className="font-semibold text-primary-900 dark:text-neutral-50">{formatCurrency(amount, currencyCode)}</p>
          </div>
          <div className="bg-white dark:bg-primary-900 rounded p-2">
            <p className="text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">Daily Interest</p>
            <p className="font-semibold text-success-600 dark:text-success-300">{formatCurrency(dailyInterest, currencyCode)}</p>
          </div>
          <div className="bg-white dark:bg-primary-900 rounded p-2">
            <p className="text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">Est. Monthly</p>
            <p className="font-semibold text-success-600 dark:text-success-300">{formatCurrency(monthlyInterest, currencyCode)}</p>
          </div>
        </div>

        <div className="flex items-center gap-2 text-xs text-success-700 dark:text-success-300 bg-white dark:bg-primary-900 rounded p-2">
          <Info className="w-4 h-4" />
          <span>Funds will be deposited to {behalfEntity.entityName} during settlement</span>
        </div>
      </div>
    </Card>
  );
};

// ============================================================================
// ENTITY CARD
// ============================================================================

const EntityCard: React.FC<{
  entity: EntityWithPosition;
  role: 'COLLECTOR' | 'BENEFICIARY';
}> = ({ entity, role }) => {
  return (
    <div className="bg-white dark:bg-primary-900 rounded-lg border border-neutral-200 dark:border-primary-800 p-3 space-y-2">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          {role === 'COLLECTOR' ? (
            <Landmark className="w-4 h-4 text-success-600 dark:text-success-300" />
          ) : (
            <Building2 className="w-4 h-4 text-accent-600 dark:text-accent-300" />
          )}
          <span className="text-sm font-medium text-primary-900 dark:text-neutral-50">{entity.entityName}</span>
        </div>
        <Badge variant={entity.status === 'ACTIVE' ? 'success' : 'neutral'} size="sm">
          {entity.entityCode}
        </Badge>
      </div>

      <div className="grid grid-cols-2 gap-2 text-xs">
        <div>
          <p className="text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">Current Balance</p>
          <p className="font-medium text-primary-900 dark:text-neutral-50">
            {formatCurrency(entity.currentBalance || 0, entity.currencyCode)}
          </p>
        </div>
        <div>
          <p className="text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">Pending COBO</p>
          <p className="font-medium text-info-600 dark:text-info-300">
            {formatCurrency(entity.pendingCobo || 0, entity.currencyCode)}
          </p>
        </div>
      </div>
    </div>
  );
};

// ============================================================================
// MAIN COBO COMPONENT
// ============================================================================

export const EnhancedCoboPicker: React.FC<CoboComponentProps> = ({
  enabled,
  onToggle,
  collectingEntityId,
  behalfEntityId,
  onSelectCollectingEntity,
  onSelectBehalfEntity,
  amount,
  currencyCode,
  generateViban,
  onGenerateVibanChange,
  invoiceNumber,
  customerName,
  onVibanGenerated,
}) => {
  const [entities, setEntities] = useState<EntityWithPosition[]>([]);
  const [loading, setLoading] = useState(false);
  const [showCollectorPicker, setShowCollectorPicker] = useState(false);
  const [showBehalfPicker, setShowBehalfPicker] = useState(false);
  const [generatedViban, setGeneratedViban] = useState<VibanGenerationResult | null>(null);
  const [vibanGenerating, setVibanGenerating] = useState(false);
  const [validationWarnings, setValidationWarnings] = useState<string[]>([]);

  const collectingEntity = entities.find(e => e.id === collectingEntityId);
  const behalfEntity = entities.find(e => e.id === behalfEntityId);

  // Load entities
  useEffect(() => {
    const loadEntities = async () => {
      setLoading(true);
      try {
        const entitiesRes = await intercompanyApi.getEntities({ status: 'ACTIVE' });
        if (entitiesRes.success) {
          setEntities(entitiesRes.data);
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

  // Generate VIBAN when requested
  const handleGenerateViban = useCallback(async () => {
    if (!collectingEntityId || !behalfEntityId || amount <= 0) return;

    setVibanGenerating(true);
    try {
      const result = await vibanIntegrationApi.generateForInvoice({
        programId: '', // Would come from context
        virtualAccountId: collectingEntity?.virtualAccountId || '',
        referenceType: 'INVOICE',
        referenceId: invoiceNumber || '',
        expectedAmount: amount,
        currencyCode,
        customerName,
        customerReference: invoiceNumber,
        purpose: `COBO collection for ${behalfEntity?.entityName}`,
      });

      if (result.success) {
        setGeneratedViban(result.data);
        onVibanGenerated?.(result.data);
      }
    } catch (error) {
      console.error('VIBAN generation failed:', error);
    } finally {
      setVibanGenerating(false);
    }
  }, [collectingEntityId, behalfEntityId, amount, currencyCode, invoiceNumber, customerName, collectingEntity, behalfEntity, onVibanGenerated]);

  // Auto-generate VIBAN when enabled and entities selected
  useEffect(() => {
    if (enabled && generateViban && collectingEntityId && behalfEntityId && amount > 0 && !generatedViban) {
      handleGenerateViban();
    }
  }, [enabled, generateViban, collectingEntityId, behalfEntityId, amount, generatedViban, handleGenerateViban]);

  return (
    <Card className={cn('transition-colors', enabled ? 'bg-info-50 dark:bg-info-500/10 border-info-200 dark:border-info-500/30' : 'bg-neutral-50 dark:bg-primary-950')}>
      {/* Header */}
      <div className="flex items-center justify-between mb-4">
        <div className="flex items-center gap-3">
          <div className={cn('w-10 h-10 rounded-lg flex items-center justify-center', enabled ? 'bg-info-100 dark:bg-info-500/20' : 'bg-neutral-200 dark:bg-primary-800')}>
            <ArrowDownLeft className={cn('w-5 h-5', enabled ? 'text-info-600 dark:text-info-300' : 'text-neutral-500 dark:text-neutral-400 dark:text-neutral-500')} />
          </div>
          <div>
            <h4 className="text-sm font-semibold text-primary-900 dark:text-neutral-50">Collect On Behalf Of (COBO)</h4>
            <p className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">Central treasury collects payment on behalf of subsidiary</p>
          </div>
        </div>
        <button
          onClick={() => onToggle(!enabled)}
          className={cn('w-12 h-6 rounded-full transition-colors relative', enabled ? 'bg-info-500' : 'bg-neutral-300')}
        >
          <div className={cn('absolute top-0.5 w-5 h-5 rounded-full bg-white shadow transition-transform dark:bg-primary-900', enabled ? 'translate-x-6' : 'translate-x-0.5')} />
        </button>
      </div>

      {enabled && (
        <div className="space-y-4">
          {loading ? (
            <div className="flex items-center justify-center py-8">
              <Loader2 className="w-6 h-6 animate-spin text-info-500 dark:text-info-300" />
              <span className="ml-2 text-sm text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">Loading entities...</span>
            </div>
          ) : (
            <>
              {/* Collecting Entity Selector */}
              <div className="relative">
                <label className="block text-xs font-medium text-neutral-600 dark:text-neutral-300 mb-1">
                  Collecting Entity (Treasury) *
                </label>
                <button
                  onClick={() => setShowCollectorPicker(!showCollectorPicker)}
                  className="w-full flex items-center justify-between p-3 border border-neutral-300 dark:border-primary-700 rounded-lg bg-white hover:border-info-400 transition-colors dark:bg-primary-900"
                >
                  {collectingEntity ? (
                    <div className="flex items-center gap-2">
                      <Landmark className="w-4 h-4 text-success-600 dark:text-success-300" />
                      <div className="text-left">
                        <span className="text-sm font-medium text-primary-900 dark:text-neutral-50">{collectingEntity.entityName}</span>
                        <span className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500 ml-2">({collectingEntity.entityCode})</span>
                      </div>
                    </div>
                  ) : (
                    <span className="text-sm text-neutral-400 dark:text-neutral-500">Select collecting entity...</span>
                  )}
                  <ChevronDown className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
                </button>

                {showCollectorPicker && (
                  <div className="absolute z-20 mt-1 w-full bg-white dark:bg-primary-900 border border-neutral-200 dark:border-primary-800 rounded-lg shadow-lg max-h-64 overflow-y-auto">
                    {entities
                      .filter(e => e.entityType === 'PARENT' || e.entityType === 'SUBSIDIARY')
                      .map((entity) => (
                        <button
                          key={entity.id}
                          onClick={() => {
                            onSelectCollectingEntity(entity.id, entity.entityName, entity);
                            setShowCollectorPicker(false);
                            setGeneratedViban(null); // Reset VIBAN when entity changes
                          }}
                          className={cn(
                            'w-full p-3 hover:bg-neutral-50 dark:hover:bg-primary-800/50 transition-colors text-left border-b border-neutral-100 dark:border-primary-800/60 last:border-b-0',
                            collectingEntityId === entity.id && 'bg-info-50 dark:bg-info-500/10'
                          )}
                        >
                          <div className="flex items-center justify-between">
                            <div className="flex items-center gap-2">
                              <Landmark className="w-4 h-4 text-success-600 dark:text-success-300" />
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

              {collectingEntity && <EntityCard entity={collectingEntity} role="COLLECTOR" />}

              {/* Behalf Entity Selector */}
              <div className="relative">
                <label className="block text-xs font-medium text-neutral-600 dark:text-neutral-300 mb-1">
                  On Behalf Of (Subsidiary) *
                </label>
                <button
                  onClick={() => setShowBehalfPicker(!showBehalfPicker)}
                  className="w-full flex items-center justify-between p-3 border border-neutral-300 dark:border-primary-700 rounded-lg bg-white hover:border-info-400 transition-colors dark:bg-primary-900"
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
                  <div className="absolute z-20 mt-1 w-full bg-white dark:bg-primary-900 border border-neutral-200 dark:border-primary-800 rounded-lg shadow-lg max-h-64 overflow-y-auto">
                    {entities
                      .filter(e => e.id !== collectingEntityId)
                      .map((entity) => (
                        <button
                          key={entity.id}
                          onClick={() => {
                            onSelectBehalfEntity(entity.id, entity.entityName, entity);
                            setShowBehalfPicker(false);
                            setGeneratedViban(null);
                          }}
                          className={cn(
                            'w-full p-3 hover:bg-neutral-50 dark:hover:bg-primary-800/50 transition-colors text-left border-b border-neutral-100 dark:border-primary-800/60 last:border-b-0',
                            behalfEntityId === entity.id && 'bg-info-50 dark:bg-info-500/10'
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

              {behalfEntity && <EntityCard entity={behalfEntity} role="BENEFICIARY" />}

              {/* VIBAN Generation Option */}
              <label className="flex items-center gap-3 p-3 border border-neutral-200 dark:border-primary-800 rounded-lg bg-white hover:bg-neutral-50 dark:hover:bg-primary-800/50 cursor-pointer dark:bg-primary-900">
                <input
                  type="checkbox"
                  checked={generateViban}
                  onChange={(e) => {
                    onGenerateVibanChange(e.target.checked);
                    if (!e.target.checked) setGeneratedViban(null);
                  }}
                  className="w-4 h-4 rounded border-neutral-300 dark:border-primary-700 text-info-600 dark:text-info-300"
                />
                <div className="flex-1">
                  <div className="flex items-center gap-2">
                    <Hash className="w-4 h-4 text-info-600 dark:text-info-300" />
                    <span className="body-sm">Generate Dedicated VIBAN</span>
                  </div>
                  <p className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500 mt-0.5">Create a unique VIBAN for automatic payment reconciliation</p>
                </div>
              </label>

              {/* Generated VIBAN Display */}
              {vibanGenerating ? (
                <div className="flex items-center justify-center py-4 bg-info-50 rounded-lg dark:bg-info-500/10">
                  <Loader2 className="w-5 h-5 animate-spin text-info-500 dark:text-info-300" />
                  <span className="ml-2 text-sm text-info-600 dark:text-info-300">Generating VIBAN...</span>
                </div>
              ) : generatedViban && (
                <VibanDisplay
                  viban={generatedViban.viban}
                  vibanId={generatedViban.vibanId}
                  paymentLink={generatedViban.paymentLink}
                  qrCodeData={generatedViban.qrCodeData}
                  amount={amount}
                  currencyCode={currencyCode}
                  customerName={customerName}
                  validUntil={generatedViban.validUntil}
                />
              )}

              {/* IHB Deposit Preview */}
              {collectingEntity && behalfEntity && amount > 0 && (
                <IhbDepositPreview
                  collectingEntity={collectingEntity}
                  behalfEntity={behalfEntity}
                  amount={amount}
                  currencyCode={currencyCode}
                  interestRate={2.5}
                />
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

              {/* COBO Summary */}
              {collectingEntity && behalfEntity && (
                <div className="bg-white dark:bg-primary-900 rounded-lg p-3 border border-info-200 dark:border-info-500/30">
                  <div className="flex items-center gap-2">
                    <CheckCircle className="w-5 h-5 text-info-600 dark:text-info-300" />
                    <span className="text-sm font-medium text-info-900 dark:text-info-300">
                      <strong>{collectingEntity.entityName}</strong> will collect <strong>{formatCurrency(amount, currencyCode)}</strong> on behalf of <strong>{behalfEntity.entityName}</strong>
                    </span>
                  </div>
                  <div className="mt-2 text-xs text-neutral-600 dark:text-neutral-300 space-y-1">
                    <p>• Payment will be received into {collectingEntity.entityName}'s collection account</p>
                    <p>• An intercompany payable will be created for {collectingEntity.entityName}</p>
                    <p>• Funds will be transferred to {behalfEntity.entityName} during settlement</p>
                    {generatedViban && <p>• VIBAN <span className="font-mono">{generatedViban.viban}</span> will auto-reconcile the payment</p>}
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

export default EnhancedCoboPicker;