/**
 * Iso20022PaymentsPage.tsx
 *
 * ISO 20022 Payment Processing Demo Page
 *
 * Features:
 * - Inward payments (pacs.008/camt.054) with VIBAN routing
 * - Outward payments (pain.001) with POBO support
 * - Bulk payments
 * - Payment status tracking
 * - Bank statement generation (camt.053)
 */

import React, { useState, useCallback } from 'react';
import {
  ArrowDownLeft, ArrowUpRight, FileText, Download, Search,
  Clock, CheckCircle, XCircle, AlertCircle, Loader2,
  Building2, CreditCard, Globe, FileCode, RefreshCw,
  Send, Layers, Eye, Copy,
} from 'lucide-react';
import { Card, Button, Badge, Input } from '../components/ui';
import { CurrencyPicker } from '../components/ui/CurrencyPicker';
import { Modal, Tabs } from '../components/ui/enhanced';
import { formatCurrency, formatDate, cn } from '../utils';
import {
  iso20022Api,
  virtualAccountsApi,
  type Iso20022InwardPaymentRequest,
  type Iso20022InwardPaymentResponse,
  type Iso20022OutwardPaymentRequest,
  type Iso20022OutwardPaymentResponse,
  type Iso20022PaymentStatusResponse,
  type Iso20022StatementResponse,
  type Iso20022BulkPaymentRequest,
  type Iso20022BulkPaymentResponse,
  type VirtualAccount,
} from '../services/api';
import toast from 'react-hot-toast';

// ============================================================================
// TYPES
// ============================================================================

type TabType = 'inward' | 'outward' | 'bulk' | 'status' | 'statement';

interface PaymentResult {
  type: 'inward' | 'outward' | 'bulk' | 'status' | 'statement';
  success: boolean;
  data: any;
  timestamp: Date;
  xml?: string;
}

// ============================================================================
// MAIN COMPONENT
// ============================================================================

export default function Iso20022PaymentsPage() {
  const [activeTab, setActiveTab] = useState<TabType>('inward');
  const [loading, setLoading] = useState(false);
  const [results, setResults] = useState<PaymentResult[]>([]);
  const [selectedResult, setSelectedResult] = useState<PaymentResult | null>(null);
  const [showXmlModal, setShowXmlModal] = useState(false);
  const [xmlContent, setXmlContent] = useState('');
  const [accounts, setAccounts] = useState<VirtualAccount[]>([]);

  // Load accounts on mount
  React.useEffect(() => {
    virtualAccountsApi.getAll().then(res => {
      if (res.data) setAccounts(res.data);
    }).catch(console.error);
  }, []);

  const addResult = useCallback((result: PaymentResult) => {
    setResults(prev => [result, ...prev].slice(0, 10));
  }, []);

  const showXml = useCallback((xml: string) => {
    setXmlContent(xml);
    setShowXmlModal(true);
  }, []);

  const copyToClipboard = useCallback((text: string) => {
    navigator.clipboard.writeText(text);
    toast.success('Copied to clipboard');
  }, []);

  const downloadXml = useCallback((xml: string, filename: string) => {
    const blob = new Blob([xml], { type: 'application/xml' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
    toast.success('XML downloaded');
  }, []);

  return (
    <div className="p-6 space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="page-title flex items-center gap-2">
            <Globe className="h-7 w-7 text-info-600 dark:text-info-300" />
            ISO 20022 Payment Processing
          </h1>
          <p className="text-neutral-500 mt-1">
            Process inward/outward payments using ISO 20022 standard messages
          </p>
        </div>
        <Badge variant="info" className="text-sm">
          <FileCode className="h-4 w-4 mr-1" />
          pacs.008 | pain.001 | camt.053
        </Badge>
      </div>

      {/* Tabs */}
      <div className="border-b border-neutral-200">
        <nav className="-mb-px flex space-x-8">
          {[
            { id: 'inward', label: 'Inward Payment', icon: ArrowDownLeft, color: 'text-success-600 dark:text-success-300' },
            { id: 'outward', label: 'Outward Payment', icon: ArrowUpRight, color: 'text-info-600 dark:text-info-300' },
            { id: 'bulk', label: 'Bulk Payment', icon: Layers, color: 'text-cat-2' },
            { id: 'status', label: 'Payment Status', icon: Search, color: 'text-warning-600 dark:text-warning-300' },
            { id: 'statement', label: 'Statement', icon: FileText, color: 'text-neutral-600' },
          ].map(tab => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id as TabType)}
              className={cn(
                'flex items-center gap-2 py-4 px-1 border-b-2 font-medium text-sm transition-colors',
                activeTab === tab.id
                  ? 'border-info-500 text-info-600 dark:text-info-300'
                  : 'border-transparent text-neutral-500 hover:text-neutral-700 hover:border-neutral-300'
              )}
            >
              <tab.icon className={cn('h-4 w-4', activeTab === tab.id ? tab.color : '')} />
              {tab.label}
            </button>
          ))}
        </nav>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Form Panel */}
        <div className="lg:col-span-2">
          <Card className="p-6">
            {activeTab === 'inward' && (
              <InwardPaymentForm
                loading={loading}
                setLoading={setLoading}
                onResult={addResult}
                onShowXml={showXml}
              />
            )}
            {activeTab === 'outward' && (
              <OutwardPaymentForm
                loading={loading}
                setLoading={setLoading}
                accounts={accounts}
                onResult={addResult}
                onShowXml={showXml}
                onDownloadXml={downloadXml}
              />
            )}
            {activeTab === 'bulk' && (
              <BulkPaymentForm
                loading={loading}
                setLoading={setLoading}
                accounts={accounts}
                onResult={addResult}
                onDownloadXml={downloadXml}
              />
            )}
            {activeTab === 'status' && (
              <PaymentStatusForm
                loading={loading}
                setLoading={setLoading}
                onResult={addResult}
                onShowXml={showXml}
              />
            )}
            {activeTab === 'statement' && (
              <StatementForm
                loading={loading}
                setLoading={setLoading}
                accounts={accounts}
                onResult={addResult}
                onDownloadXml={downloadXml}
              />
            )}
          </Card>
        </div>

        {/* Results Panel */}
        <div className="lg:col-span-1">
          <Card className="p-4">
            <h3 className="font-semibold text-neutral-900 mb-4 flex items-center gap-2">
              <Clock className="h-4 w-4" />
              Recent Results
            </h3>
            <div className="space-y-3 max-h-[600px] overflow-y-auto">
              {results.length === 0 ? (
                <p className="text-neutral-500 text-sm text-center py-8">
                  No results yet. Process a payment to see results here.
                </p>
              ) : (
                results.map((result, idx) => (
                  <div
                    key={idx}
                    onClick={() => setSelectedResult(result)}
                    className={cn(
                      'p-3 rounded-lg border cursor-pointer transition-colors',
                      result.success
                        ? 'border-success-200 bg-success-50 hover:bg-success-100 dark:border-success-500/30 dark:bg-success-500/10 dark:hover:bg-success-500/20'
                        : 'border-error-200 bg-error-50 hover:bg-error-100 dark:border-error-500/30 dark:bg-error-500/10 dark:hover:bg-error-500/20'
                    )}
                  >
                    <div className="flex items-center justify-between mb-1">
                      <Badge variant={result.success ? 'success' : 'error'} size="sm">
                        {result.success ? <CheckCircle className="h-3 w-3 mr-1" /> : <XCircle className="h-3 w-3 mr-1" />}
                        {result.type.toUpperCase()}
                      </Badge>
                      <span className="text-xs text-neutral-500">
                        {formatDate(result.timestamp.toISOString())}
                      </span>
                    </div>
                    <p className="text-sm text-neutral-700 truncate">
                      {result.data?.transactionReference || result.data?.messageId || 'Processing...'}
                    </p>
                    {result.data?.amount && (
                      <p className="text-sm font-medium text-neutral-900">
                        {formatCurrency(result.data.amount, result.data.currency || 'AED')}
                      </p>
                    )}
                  </div>
                ))
              )}
            </div>
          </Card>
        </div>
      </div>

      {/* Result Detail Modal */}
      <Modal
        isOpen={!!selectedResult}
        onClose={() => setSelectedResult(null)}
        title="Payment Result Details"
        size="lg"
      >
        {selectedResult && (
          <div className="space-y-4">
            <div className="flex items-center gap-2">
              <Badge variant={selectedResult.success ? 'success' : 'error'}>
                {selectedResult.success ? 'SUCCESS' : 'FAILED'}
              </Badge>
              <span className="text-neutral-500">
                {selectedResult.type.toUpperCase()} Payment
              </span>
            </div>
            <pre className="bg-neutral-100 p-4 rounded-lg text-xs overflow-auto max-h-96">
              {JSON.stringify(selectedResult.data, null, 2)}
            </pre>
            {selectedResult.xml && (
              <Button
                variant="outline"
                size="sm"
                onClick={() => showXml(selectedResult.xml!)}
              >
                <FileCode className="h-4 w-4 mr-1" />
                View XML
              </Button>
            )}
          </div>
        )}
      </Modal>

      {/* XML Viewer Modal */}
      <Modal
        isOpen={showXmlModal}
        onClose={() => setShowXmlModal(false)}
        title="ISO 20022 XML Message"
        size="xl"
      >
        <div className="space-y-4">
          <div className="flex gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => copyToClipboard(xmlContent)}
            >
              <Copy className="h-4 w-4 mr-1" />
              Copy
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() => downloadXml(xmlContent, 'iso20022_message.xml')}
            >
              <Download className="h-4 w-4 mr-1" />
              Download
            </Button>
          </div>
          <pre className="bg-neutral-900 text-success-400 p-4 rounded-lg text-xs overflow-auto max-h-[500px] font-mono">
            {xmlContent}
          </pre>
        </div>
      </Modal>
    </div>
  );
}

// ============================================================================
// INWARD PAYMENT FORM
// ============================================================================

function InwardPaymentForm({
  loading,
  setLoading,
  onResult,
  onShowXml,
}: {
  loading: boolean;
  setLoading: (v: boolean) => void;
  onResult: (r: PaymentResult) => void;
  onShowXml: (xml: string) => void;
}) {
  const [form, setForm] = useState<Iso20022InwardPaymentRequest>({
    amount: 1000,
    currency: 'AED',
    creditorAccount: '',
    debtorName: 'ABC Corporation',
    debtorAccount: 'AE080330009876543210987654',
    remittanceInfo: 'Invoice payment INV-2024-001',
    endToEndId: `E2E-${Date.now()}`,
  });

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);

    try {
      const response = await iso20022Api.processInwardPayment(form);
      const data = response.data || response;

      onResult({
        type: 'inward',
        success: data.success,
        data,
        timestamp: new Date(),
      });

      if (data.success) {
        toast.success(`Payment credited: ${data.transactionReference}`);
      } else {
        toast.error(data.errorMessage || 'Payment failed');
      }
    } catch (error: any) {
      toast.error(error.message || 'Failed to process payment');
      onResult({
        type: 'inward',
        success: false,
        data: { error: error.message },
        timestamp: new Date(),
      });
    } finally {
      setLoading(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="space-y-6">
      <div className="flex items-center gap-2 text-lg font-semibold text-success-700 dark:text-success-300">
        <ArrowDownLeft className="h-5 w-5" />
        Inward Payment (ROBO Credit)
      </div>
      <p className="text-sm text-neutral-500">
        Simulate incoming payment routed via VIBAN to credit a Virtual Account.
      </p>

      <div className="grid grid-cols-2 gap-4">
        <div>
          <label className="field-label block mb-1">
            VIBAN (Creditor Account) *
          </label>
          <Input
            value={form.creditorAccount}
            onChange={e => setForm(f => ({ ...f, creditorAccount: e.target.value }))}
            placeholder="AE070410001234567890123456"
            required
          />
          <p className="text-xs text-neutral-500 mt-1">The VIBAN to route payment to</p>
        </div>
        <div>
          <label className="field-label block mb-1">
            Amount *
          </label>
          <div className="flex gap-2">
            <Input
              type="number"
              value={form.amount}
              onChange={e => setForm(f => ({ ...f, amount: parseFloat(e.target.value) }))}
              className="flex-1"
              required
            />
            <CurrencyPicker
              value={form.currency}
              onChange={(c) => setForm(f => ({ ...f, currency: c }))}
              className="px-3 py-2"
            />
          </div>
        </div>
      </div>

      <div className="grid grid-cols-2 gap-4">
        <div>
          <label className="field-label block mb-1">
            Debtor Name (Sender)
          </label>
          <Input
            value={form.debtorName}
            onChange={e => setForm(f => ({ ...f, debtorName: e.target.value }))}
            placeholder="Sender company name"
          />
        </div>
        <div>
          <label className="field-label block mb-1">
            Debtor Account
          </label>
          <Input
            value={form.debtorAccount}
            onChange={e => setForm(f => ({ ...f, debtorAccount: e.target.value }))}
            placeholder="Sender IBAN"
          />
        </div>
      </div>

      <div>
        <label className="field-label block mb-1">
          Remittance Information
        </label>
        <Input
          value={form.remittanceInfo}
          onChange={e => setForm(f => ({ ...f, remittanceInfo: e.target.value }))}
          placeholder="Payment reference or description"
        />
      </div>

      <div>
        <label className="field-label block mb-1">
          End-to-End ID
        </label>
        <Input
          value={form.endToEndId}
          onChange={e => setForm(f => ({ ...f, endToEndId: e.target.value }))}
          placeholder="Unique tracking ID"
        />
      </div>

      <Button type="submit" disabled={loading} className="w-full">
        {loading ? (
          <Loader2 className="h-4 w-4 animate-spin mr-2" />
        ) : (
          <Send className="h-4 w-4 mr-2" />
        )}
        Process Inward Payment
      </Button>
    </form>
  );
}

// ============================================================================
// OUTWARD PAYMENT FORM
// ============================================================================

function OutwardPaymentForm({
  loading,
  setLoading,
  accounts,
  onResult,
  onShowXml,
  onDownloadXml,
}: {
  loading: boolean;
  setLoading: (v: boolean) => void;
  accounts: VirtualAccount[];
  onResult: (r: PaymentResult) => void;
  onShowXml: (xml: string) => void;
  onDownloadXml: (xml: string, filename: string) => void;
}) {
  const [form, setForm] = useState<Iso20022OutwardPaymentRequest>({
    sourceVaId: '',
    amount: 500,
    currency: 'AED',
    creditorName: 'Vendor XYZ Ltd',
    creditorAccount: 'AE090440001122334455667788',
    creditorBic: 'ABORAEADXXX',
    remittanceInfo: 'Supplier payment PO-2024-050',
    endToEndId: `E2E-${Date.now()}`,
  });

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);

    try {
      const response = await iso20022Api.processOutwardPayment(form);
      const data = response.data || response;

      onResult({
        type: 'outward',
        success: data.success,
        data,
        timestamp: new Date(),
        xml: data.pain001Xml,
      });

      if (data.success) {
        toast.success(`Payment sent: ${data.transactionReference}`);
        if (data.pain001Xml) {
          toast((t) => (
            <div className="flex items-center gap-2">
              <span>pain.001 generated</span>
              <button
                onClick={() => {
                  onShowXml(data.pain001Xml!);
                  toast.dismiss(t.id);
                }}
                className="text-info-600 underline dark:text-info-300"
              >
                View XML
              </button>
            </div>
          ));
        }
      } else {
        toast.error(data.errorMessage || 'Payment failed');
      }
    } catch (error: any) {
      toast.error(error.message || 'Failed to process payment');
      onResult({
        type: 'outward',
        success: false,
        data: { error: error.message },
        timestamp: new Date(),
      });
    } finally {
      setLoading(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="space-y-6">
      <div className="flex items-center gap-2 text-lg font-semibold text-info-700 dark:text-info-300">
        <ArrowUpRight className="h-5 w-5" />
        Outward Payment (pain.001)
      </div>
      <p className="text-sm text-neutral-500">
        Create ISO 20022 pain.001 Customer Credit Transfer Initiation.
      </p>

      <div>
        <label className="field-label block mb-1">
          Source Virtual Account *
        </label>
        <select
          value={form.sourceVaId}
          onChange={e => setForm(f => ({ ...f, sourceVaId: e.target.value }))}
          className="w-full px-3 py-2 border rounded-lg"
          required
        >
          <option value="">Select VA to debit...</option>
          {accounts.map(acc => (
            <option key={acc.id} value={acc.id}>
              {acc.vaNumber} - {acc.vaName} ({formatCurrency(acc.currentBalance, acc.currencyCode)})
            </option>
          ))}
        </select>
      </div>

      <div className="grid grid-cols-2 gap-4">
        <div>
          <label className="field-label block mb-1">
            Amount *
          </label>
          <div className="flex gap-2">
            <Input
              type="number"
              value={form.amount}
              onChange={e => setForm(f => ({ ...f, amount: parseFloat(e.target.value) }))}
              className="flex-1"
              required
            />
            <CurrencyPicker
              value={form.currency}
              onChange={(c) => setForm(f => ({ ...f, currency: c }))}
              className="px-3 py-2"
            />
          </div>
        </div>
        <div>
          <label className="field-label block mb-1">
            Service Level
          </label>
          <select
            value={form.serviceLevel || 'SEPA'}
            onChange={e => setForm(f => ({ ...f, serviceLevel: e.target.value as any }))}
            className="w-full px-3 py-2 border rounded-lg"
          >
            <option value="SEPA">SEPA</option>
            <option value="NURG">Non-Urgent</option>
            <option value="URGP">Urgent</option>
          </select>
        </div>
      </div>

      <div className="grid grid-cols-2 gap-4">
        <div>
          <label className="field-label block mb-1">
            Beneficiary Name *
          </label>
          <Input
            value={form.creditorName}
            onChange={e => setForm(f => ({ ...f, creditorName: e.target.value }))}
            placeholder="Beneficiary company name"
            required
          />
        </div>
        <div>
          <label className="field-label block mb-1">
            Beneficiary Account *
          </label>
          <Input
            value={form.creditorAccount}
            onChange={e => setForm(f => ({ ...f, creditorAccount: e.target.value }))}
            placeholder="Beneficiary IBAN"
            required
          />
        </div>
      </div>

      <div>
        <label className="field-label block mb-1">
          Beneficiary BIC
        </label>
        <Input
          value={form.creditorBic}
          onChange={e => setForm(f => ({ ...f, creditorBic: e.target.value }))}
          placeholder="SWIFT/BIC code (e.g., ABORAEADXXX)"
        />
      </div>

      <div>
        <label className="field-label block mb-1">
          Remittance Information
        </label>
        <Input
          value={form.remittanceInfo}
          onChange={e => setForm(f => ({ ...f, remittanceInfo: e.target.value }))}
          placeholder="Payment reference"
        />
      </div>

      <div className="flex items-center gap-2">
        <input
          type="checkbox"
          id="isPobo"
          checked={form.isPobo || false}
          onChange={e => setForm(f => ({ ...f, isPobo: e.target.checked }))}
          className="rounded"
        />
        <label htmlFor="isPobo" className="text-sm text-neutral-700">
          Pay On Behalf Of (POBO)
        </label>
      </div>

      {form.isPobo && (
        <div>
          <label className="field-label block mb-1">
            On Behalf Of Entity
          </label>
          <Input
            value={form.behalfOfEntity || ''}
            onChange={e => setForm(f => ({ ...f, behalfOfEntity: e.target.value }))}
            placeholder="Entity name paying on behalf of"
          />
        </div>
      )}

      <Button type="submit" disabled={loading} className="w-full">
        {loading ? (
          <Loader2 className="h-4 w-4 animate-spin mr-2" />
        ) : (
          <Send className="h-4 w-4 mr-2" />
        )}
        Process Outward Payment & Generate pain.001
      </Button>
    </form>
  );
}

// ============================================================================
// BULK PAYMENT FORM
// ============================================================================

function BulkPaymentForm({
  loading,
  setLoading,
  accounts,
  onResult,
  onDownloadXml,
}: {
  loading: boolean;
  setLoading: (v: boolean) => void;
  accounts: VirtualAccount[];
  onResult: (r: PaymentResult) => void;
  onDownloadXml: (xml: string, filename: string) => void;
}) {
  const [sourceVaId, setSourceVaId] = useState('');
  const [instructions, setInstructions] = useState([
    { creditorName: 'Vendor A', creditorAccount: 'AE090440001111111111111111', amount: 1000, remittanceInfo: 'Payment 1' },
    { creditorName: 'Vendor B', creditorAccount: 'AE090440002222222222222222', amount: 2000, remittanceInfo: 'Payment 2' },
    { creditorName: 'Vendor C', creditorAccount: 'AE090440003333333333333333', amount: 1500, remittanceInfo: 'Payment 3' },
  ]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);

    try {
      const request: Iso20022BulkPaymentRequest = {
        sourceVaId,
        instructions: instructions.map((inst, idx) => ({
          ...inst,
          instructionId: `INST-${Date.now()}-${idx}`,
          endToEndId: `E2E-${Date.now()}-${idx}`,
        })),
      };

      const response = await iso20022Api.processBulkPayment(request);
      const data = response.data || response;

      onResult({
        type: 'bulk',
        success: data.success,
        data,
        timestamp: new Date(),
        xml: data.pain001Xml,
      });

      if (data.success) {
        toast.success(`Bulk payment: ${data.successCount}/${data.totalCount} successful`);
        if (data.pain001Xml) {
          onDownloadXml(data.pain001Xml, `pain001_bulk_${data.messageId}.xml`);
        }
      } else {
        toast.error(`Bulk payment partial: ${data.failedCount} failed`);
      }
    } catch (error: any) {
      toast.error(error.message || 'Failed to process bulk payment');
    } finally {
      setLoading(false);
    }
  };

  const totalAmount = instructions.reduce((sum, inst) => sum + (inst.amount || 0), 0);

  return (
    <form onSubmit={handleSubmit} className="space-y-6">
      <div className="flex items-center gap-2 text-lg font-semibold text-cat-2">
        <Layers className="h-5 w-5" />
        Bulk Payment (Multiple Beneficiaries)
      </div>
      <p className="text-sm text-neutral-500">
        Process multiple payments in a single pain.001 message.
      </p>

      <div>
        <label className="field-label block mb-1">
          Source Virtual Account *
        </label>
        <select
          value={sourceVaId}
          onChange={e => setSourceVaId(e.target.value)}
          className="w-full px-3 py-2 border rounded-lg"
          required
        >
          <option value="">Select VA to debit...</option>
          {accounts.map(acc => (
            <option key={acc.id} value={acc.id}>
              {acc.vaNumber} - {acc.vaName} ({formatCurrency(acc.currentBalance, acc.currencyCode)})
            </option>
          ))}
        </select>
      </div>

      <div className="border rounded-lg overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-neutral-50">
            <tr>
              <th className="px-4 py-2 text-left">Beneficiary</th>
              <th className="px-4 py-2 text-left">Account</th>
              <th className="px-4 py-2 text-right">Amount</th>
              <th className="px-4 py-2 text-left">Reference</th>
            </tr>
          </thead>
          <tbody>
            {instructions.map((inst, idx) => (
              <tr key={idx} className="border-t">
                <td className="px-4 py-2">
                  <Input
                    value={inst.creditorName}
                    onChange={e => {
                      const newInst = [...instructions];
                      newInst[idx].creditorName = e.target.value;
                      setInstructions(newInst);
                    }}
                    className="text-sm"
                  />
                </td>
                <td className="px-4 py-2">
                  <Input
                    value={inst.creditorAccount}
                    onChange={e => {
                      const newInst = [...instructions];
                      newInst[idx].creditorAccount = e.target.value;
                      setInstructions(newInst);
                    }}
                    className="text-sm"
                  />
                </td>
                <td className="px-4 py-2">
                  <Input
                    type="number"
                    value={inst.amount}
                    onChange={e => {
                      const newInst = [...instructions];
                      newInst[idx].amount = parseFloat(e.target.value);
                      setInstructions(newInst);
                    }}
                    className="text-sm text-right"
                  />
                </td>
                <td className="px-4 py-2">
                  <Input
                    value={inst.remittanceInfo}
                    onChange={e => {
                      const newInst = [...instructions];
                      newInst[idx].remittanceInfo = e.target.value;
                      setInstructions(newInst);
                    }}
                    className="text-sm"
                  />
                </td>
              </tr>
            ))}
          </tbody>
          <tfoot className="bg-neutral-50 font-semibold">
            <tr>
              <td colSpan={2} className="px-4 py-2">
                Total ({instructions.length} payments)
              </td>
              <td className="px-4 py-2 text-right">
                {formatCurrency(totalAmount, 'AED')}
              </td>
              <td></td>
            </tr>
          </tfoot>
        </table>
      </div>

      <Button type="submit" disabled={loading} className="w-full">
        {loading ? (
          <Loader2 className="h-4 w-4 animate-spin mr-2" />
        ) : (
          <Send className="h-4 w-4 mr-2" />
        )}
        Process Bulk Payment
      </Button>
    </form>
  );
}

// ============================================================================
// PAYMENT STATUS FORM
// ============================================================================

function PaymentStatusForm({
  loading,
  setLoading,
  onResult,
  onShowXml,
}: {
  loading: boolean;
  setLoading: (v: boolean) => void;
  onResult: (r: PaymentResult) => void;
  onShowXml: (xml: string) => void;
}) {
  const [searchType, setSearchType] = useState<'reference' | 'messageId' | 'endToEndId'>('reference');
  const [searchValue, setSearchValue] = useState('');

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);

    try {
      const request: any = {};
      if (searchType === 'reference') request.transactionReference = searchValue;
      if (searchType === 'messageId') request.originalMessageId = searchValue;
      if (searchType === 'endToEndId') request.originalEndToEndId = searchValue;

      const response = await iso20022Api.getPaymentStatus(request);
      const data = response.data || response;

      onResult({
        type: 'status',
        success: true,
        data,
        timestamp: new Date(),
        xml: data.pain002Xml,
      });

      toast.success(`Status: ${data.transactionStatus}`);

      if (data.pain002Xml) {
        onShowXml(data.pain002Xml);
      }
    } catch (error: any) {
      toast.error(error.message || 'Payment not found');
      onResult({
        type: 'status',
        success: false,
        data: { error: error.message },
        timestamp: new Date(),
      });
    } finally {
      setLoading(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="space-y-6">
      <div className="flex items-center gap-2 text-lg font-semibold text-warning-700 dark:text-warning-300">
        <Search className="h-5 w-5" />
        Payment Status (pain.002)
      </div>
      <p className="text-sm text-neutral-500">
        Query payment status and generate ISO 20022 pain.002 status report.
      </p>

      <div>
        <label className="field-label block mb-1">
          Search By
        </label>
        <select
          value={searchType}
          onChange={e => setSearchType(e.target.value as any)}
          className="w-full px-3 py-2 border rounded-lg"
        >
          <option value="reference">Transaction Reference</option>
          <option value="messageId">Original Message ID</option>
          <option value="endToEndId">End-to-End ID</option>
        </select>
      </div>

      <div>
        <label className="field-label block mb-1">
          Search Value *
        </label>
        <Input
          value={searchValue}
          onChange={e => setSearchValue(e.target.value)}
          placeholder={`Enter ${searchType}...`}
          required
        />
      </div>

      <Button type="submit" disabled={loading} className="w-full">
        {loading ? (
          <Loader2 className="h-4 w-4 animate-spin mr-2" />
        ) : (
          <Search className="h-4 w-4 mr-2" />
        )}
        Get Payment Status
      </Button>
    </form>
  );
}

// ============================================================================
// STATEMENT FORM
// ============================================================================

function StatementForm({
  loading,
  setLoading,
  accounts,
  onResult,
  onDownloadXml,
}: {
  loading: boolean;
  setLoading: (v: boolean) => void;
  accounts: VirtualAccount[];
  onResult: (r: PaymentResult) => void;
  onDownloadXml: (xml: string, filename: string) => void;
}) {
  const [vaId, setVaId] = useState('');
  const [fromDate, setFromDate] = useState(
    new Date(Date.now() - 30 * 24 * 60 * 60 * 1000).toISOString().split('T')[0]
  );
  const [toDate, setToDate] = useState(new Date().toISOString().split('T')[0]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);

    try {
      const response = await iso20022Api.generateStatement({
        virtualAccountId: vaId,
        fromDate,
        toDate,
      });
      const data = response.data || response;

      onResult({
        type: 'statement',
        success: true,
        data,
        timestamp: new Date(),
        xml: data.camt053Xml,
      });

      toast.success(`Statement generated: ${data.entryCount} entries`);

      if (data.camt053Xml) {
        onDownloadXml(data.camt053Xml, `camt053_${data.statementId}.xml`);
      }
    } catch (error: any) {
      toast.error(error.message || 'Failed to generate statement');
      onResult({
        type: 'statement',
        success: false,
        data: { error: error.message },
        timestamp: new Date(),
      });
    } finally {
      setLoading(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="space-y-6">
      <div className="flex items-center gap-2 text-lg font-semibold text-neutral-700">
        <FileText className="h-5 w-5" />
        Bank Statement (camt.053)
      </div>
      <p className="text-sm text-neutral-500">
        Generate ISO 20022 camt.053 Bank-to-Customer Statement.
      </p>

      <div>
        <label className="field-label block mb-1">
          Virtual Account *
        </label>
        <select
          value={vaId}
          onChange={e => setVaId(e.target.value)}
          className="w-full px-3 py-2 border rounded-lg"
          required
        >
          <option value="">Select account...</option>
          {accounts.map(acc => (
            <option key={acc.id} value={acc.id}>
              {acc.vaNumber} - {acc.vaName}
            </option>
          ))}
        </select>
      </div>

      <div className="grid grid-cols-2 gap-4">
        <div>
          <label className="field-label block mb-1">
            From Date *
          </label>
          <Input
            type="date"
            value={fromDate}
            onChange={e => setFromDate(e.target.value)}
            required
          />
        </div>
        <div>
          <label className="field-label block mb-1">
            To Date *
          </label>
          <Input
            type="date"
            value={toDate}
            onChange={e => setToDate(e.target.value)}
            required
          />
        </div>
      </div>

      <Button type="submit" disabled={loading} className="w-full">
        {loading ? (
          <Loader2 className="h-4 w-4 animate-spin mr-2" />
        ) : (
          <Download className="h-4 w-4 mr-2" />
        )}
        Generate camt.053 Statement
      </Button>
    </form>
  );
}
