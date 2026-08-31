/**
 * CreateReceivablePage.tsx
 * 
 * VAM Receivable Creation - Single Page with Optional Tabs
 * 
 * Layout:
 * - Main Form: Customer, Invoice Details, Collection Account, VIBAN
 * - Optional Tabs: Line Items, Tax & Charges, Payment Link, COBO, Hierarchy, Documents, Reminders
 * 
 * Design: Fast entry for common cases, full features available in tabs
 */

import React, { useState, useEffect, useMemo } from 'react';
import {
  ArrowLeft,
  Search,
  Building2,
  FileText,
  Calendar,
  CreditCard,
  QrCode,
  Check,
  AlertCircle,
  Loader2,
  Copy,
  ChevronDown,
  ChevronUp,
  Landmark,
  FolderTree,
  Paperclip,
  Upload,
  X,
  Plus,
  Trash2,
  ExternalLink,
  Link2,
  Mail,
  MessageSquare,
  Phone,
  Bell,
  Calculator,
  Percent,
  DollarSign,
  Building,
  ArrowDownLeft,
  ToggleLeft,
  ToggleRight,
  Share2,
  Download,
  Info,
  Package,
  Receipt,
  Clock,
  Send,
  AlertTriangle,
} from 'lucide-react';
import { partiesApi, legalEntityApi, virtualAccountsApi, receivablesApi, corporatesApi } from '../services/api';
import { useNavigation } from '../App';
import { Page } from '../components/layout/Page';
import { formatCurrency } from '../utils';

// ============================================================================
// TYPES
// ============================================================================

interface Customer {
  id: string;
  name: string;
  code: string;
  creditLimit: number;
  creditUsed: number;
  creditAvailable: number;
  currency: string;
  assignedViban?: string;
  email?: string;
  overdueAmount?: number;
}

interface CollectionAccount {
  id: string;
  accountName: string;
  accountNumber: string;
  iban: string;
  bankName: string;
  currency: string;
  isDefault: boolean;
  accountType: 'PHYSICAL' | 'VIRTUAL';
}

interface LineItem {
  id: string;
  description: string;
  itemCode: string;
  quantity: number;
  unit: string;
  unitPrice: number;
  discountPercent: number;
  taxCode: string;
  lineTotal: number;
}

interface TaxConfig {
  code: string;
  name: string;
  rate: number;
}

interface ChargeConfig {
  id: string;
  type: string;
  description: string;
  amount: number;
  isPercentage: boolean;
}

interface HierarchyNode {
  id: string;
  code: string;
  name: string;
  level: string;
  path: string;
}

interface IntercompanyEntity {
  id: string;
  code: string;
  name: string;
  type: 'HEADQUARTERS' | 'SUBSIDIARY' | 'BRANCH';
  currency: string;
  ihbBalance: number;
}

interface PaymentLinkData {
  enabled: boolean;
  linkId: string;
  fullUrl: string;
  shortUrl: string;
  expiresAt: string;
  qrCodeGenerated: boolean;
}

interface ReceivableFormData {
  // Customer
  customerId: string;
  
  // Invoice Details
  invoiceNumber: string;
  invoiceDate: string;
  paymentTerms: string;
  dueDate: string;
  amount: string;
  currency: string;
  reference: string;
  description: string;
  
  // Collection
  collectionAccountId: string;
  generateViban: boolean;
  generatedViban: string;
  
  // Line Items (Optional)
  useLineItems: boolean;
  lineItems: LineItem[];
  
  // Tax & Charges (Optional)
  taxConfig: {
    vatEnabled: boolean;
    vatRate: number;
    reverseCharge: boolean;
    taxExempt: boolean;
    exemptionReason: string;
  };
  charges: ChargeConfig[];
  earlyPaymentDiscount: {
    enabled: boolean;
    days: number;
    percent: number;
  };
  latePaymentFee: {
    enabled: boolean;
    percent: number;
  };
  
  // Payment Link (Optional)
  paymentLink: PaymentLinkData;
  
  // COBO (Optional)
  coboEnabled: boolean;
  collectingEntityId: string;
  behalfEntityId: string;
  coboRechargeMarkup: number;
  
  // Hierarchy (Optional)
  hierarchyNodeId: string;
  revenueCenter: string;
  
  // Documents (Optional)
  attachments: File[];
  externalRefs: { type: string; value: string }[];
  customerNotes: string;
  internalNotes: string;
  
  // Reminders (Optional)
  sendInvoiceEmail: boolean;
  reminderDays: number[];
  enableDunning: boolean;
  dunningMaxLevel: number;
}

// ============================================================================
// CONFIGURATION DATA (Non-Mock - These are static configs)
// ============================================================================

const TAX_CONFIGS: TaxConfig[] = [
  { code: 'VAT5', name: 'VAT 5%', rate: 5 },
  { code: 'VAT0', name: 'VAT 0%', rate: 0 },
  { code: 'EXEMPT', name: 'Exempt', rate: 0 },
];

const PAYMENT_TERMS = [
  { value: 'IMMEDIATE', label: 'Immediate', days: 0 },
  { value: 'NET7', label: 'Net 7', days: 7 },
  { value: 'NET15', label: 'Net 15', days: 15 },
  { value: 'NET30', label: 'Net 30', days: 30 },
  { value: 'NET45', label: 'Net 45', days: 45 },
  { value: 'NET60', label: 'Net 60', days: 60 },
  { value: 'NET90', label: 'Net 90', days: 90 },
  { value: 'EOM', label: 'End of Month', days: 30 },
];

const CURRENCIES = ['AED', 'USD', 'EUR', 'GBP', 'SAR', 'INR'];

const UNITS = ['Each', 'Piece', 'Box', 'Carton', 'Kg', 'Gram', 'Liter', 'Meter', 'SqM', 'Hour', 'Day', 'Week', 'Month', 'Unit', 'Set'];

const CHARGE_TYPES = [
  { value: 'SHIPPING', label: 'Shipping', icon: Package },
  { value: 'HANDLING', label: 'Handling', icon: Package },
  { value: 'INSURANCE', label: 'Insurance', icon: AlertCircle },
  { value: 'SERVICE', label: 'Service Fee', icon: Receipt },
  { value: 'CUSTOM', label: 'Custom', icon: DollarSign },
];

const REFERENCE_TYPES = [
  { value: 'PO', label: 'Purchase Order' },
  { value: 'CONTRACT', label: 'Contract' },
  { value: 'SO', label: 'Sales Order' },
  { value: 'DN', label: 'Delivery Note' },
  { value: 'OTHER', label: 'Other' },
];

const REMINDER_DAYS = [-7, -3, -1, 1, 3, 7, 14, 30];

// ============================================================================
// UTILITY FUNCTIONS
// ============================================================================

const generateId = (): string => Math.random().toString(36).substring(2, 9);

const generateInvoiceNumber = (): string => {
  const date = new Date();
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const random = String(Math.floor(Math.random() * 10000)).padStart(4, '0');
  return `INV-${year}${month}-${random}`;
};

const generateViban = (customerId: string): string => {
  const random = String(Math.floor(Math.random() * 1000000)).padStart(6, '0');
  return `AE07033VIBAN${customerId.toUpperCase().replace('-', '')}${random}`;
};

const generatePaymentLink = (): PaymentLinkData => {
  const linkId = generateId();
  return {
    enabled: true,
    linkId,
    fullUrl: `https://pay.vam.example.com/invoice/${linkId}`,
    shortUrl: `https://pay.vam.example.com/p/${linkId.substring(0, 6)}`,
    expiresAt: '',
    qrCodeGenerated: true,
  };
};

const calculateDueDate = (invoiceDate: string, paymentTerms: string): string => {
  const term = PAYMENT_TERMS.find(t => t.value === paymentTerms);
  if (!term || !invoiceDate) return '';
  const date = new Date(invoiceDate);
  if (paymentTerms === 'EOM') {
    date.setMonth(date.getMonth() + 1);
    date.setDate(0);
  } else {
    date.setDate(date.getDate() + term.days);
  }
  return date.toISOString().split('T')[0];
};

// formatCurrency now comes from the shared util (Phase 12 Task D3) — the local
// en-AE Intl clone (symbol-style, fixed 2 dp) was deleted in favour of the
// shared currency-aware "AED 1,234.56" format.

const formatDate = (dateStr: string): string => {
  if (!dateStr) return '';
  return new Date(dateStr).toLocaleDateString('en-GB', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
  });
};

const cn = (...classes: (string | boolean | undefined)[]): string => {
  return classes.filter(Boolean).join(' ');
};

// ============================================================================
// SUB-COMPONENTS
// ============================================================================

// Line Item Row Component
const LineItemRow: React.FC<{
  item: LineItem;
  index: number;
  taxConfigs: TaxConfig[];
  onChange: (index: number, field: keyof LineItem, value: any) => void;
  onRemove: (index: number) => void;
}> = ({ item, index, taxConfigs, onChange, onRemove }) => {
  const taxRate = taxConfigs.find(t => t.code === item.taxCode)?.rate || 0;
  const subtotal = item.quantity * item.unitPrice;
  const discount = subtotal * (item.discountPercent / 100);
  const taxAmount = (subtotal - discount) * (taxRate / 100);
  const lineTotal = subtotal - discount + taxAmount;

  useEffect(() => {
    onChange(index, 'lineTotal', lineTotal);
  }, [subtotal, discount, taxAmount]);

  return (
    <div className="grid grid-cols-12 gap-2 items-start p-3 bg-neutral-50 dark:bg-primary-950 rounded-lg">
      <div className="col-span-3">
        <input
          type="text"
          value={item.description}
          onChange={(e) => onChange(index, 'description', e.target.value)}
          placeholder="Description"
          className="w-full px-2 py-1.5 text-sm border border-neutral-300 dark:border-primary-700 rounded focus:border-primary-500 focus:outline-none"
        />
      </div>
      <div className="col-span-1">
        <input
          type="text"
          value={item.itemCode}
          onChange={(e) => onChange(index, 'itemCode', e.target.value)}
          placeholder="Code"
          className="w-full px-2 py-1.5 text-sm border border-neutral-300 dark:border-primary-700 rounded focus:border-primary-500 focus:outline-none"
        />
      </div>
      <div className="col-span-1">
        <input
          type="number"
          value={item.quantity}
          onChange={(e) => onChange(index, 'quantity', parseFloat(e.target.value) || 0)}
          className="w-full px-2 py-1.5 text-sm border border-neutral-300 dark:border-primary-700 rounded focus:border-primary-500 focus:outline-none"
        />
      </div>
      <div className="col-span-1">
        <select
          value={item.unit}
          onChange={(e) => onChange(index, 'unit', e.target.value)}
          className="w-full px-2 py-1.5 text-sm border border-neutral-300 dark:border-primary-700 rounded focus:border-primary-500 focus:outline-none"
        >
          {UNITS.map(u => <option key={u} value={u}>{u}</option>)}
        </select>
      </div>
      <div className="col-span-1">
        <input
          type="number"
          value={item.unitPrice}
          onChange={(e) => onChange(index, 'unitPrice', parseFloat(e.target.value) || 0)}
          placeholder="Price"
          className="w-full px-2 py-1.5 text-sm border border-neutral-300 dark:border-primary-700 rounded focus:border-primary-500 focus:outline-none"
        />
      </div>
      <div className="col-span-1">
        <input
          type="number"
          value={item.discountPercent}
          onChange={(e) => onChange(index, 'discountPercent', parseFloat(e.target.value) || 0)}
          placeholder="%"
          className="w-full px-2 py-1.5 text-sm border border-neutral-300 dark:border-primary-700 rounded focus:border-primary-500 focus:outline-none"
        />
      </div>
      <div className="col-span-1">
        <select
          value={item.taxCode}
          onChange={(e) => onChange(index, 'taxCode', e.target.value)}
          className="w-full px-2 py-1.5 text-sm border border-neutral-300 dark:border-primary-700 rounded focus:border-primary-500 focus:outline-none"
        >
          {taxConfigs.map(t => <option key={t.code} value={t.code}>{t.name}</option>)}
        </select>
      </div>
      <div className="col-span-2 flex items-center justify-between">
        <span className="text-sm font-semibold text-neutral-900 dark:text-neutral-50">
          {formatCurrency(lineTotal, 'AED')}
        </span>
        <button
          onClick={() => onRemove(index)}
          className="p-1 text-error-500 hover:bg-error-50 rounded dark:hover:bg-error-500/10"
        >
          <Trash2 className="w-4 h-4" />
        </button>
      </div>
    </div>
  );
};

// Toggle Switch Component
const Toggle: React.FC<{
  enabled: boolean;
  onChange: (enabled: boolean) => void;
  size?: 'sm' | 'md';
}> = ({ enabled, onChange, size = 'md' }) => (
  <button
    onClick={() => onChange(!enabled)}
    className="focus:outline-none"
  >
    {enabled ? (
      <ToggleRight className={cn(
        'text-primary-600 dark:text-primary-200 transition-transform hover:scale-105',
        size === 'sm' ? 'w-8 h-8' : 'w-10 h-10'
      )} />
    ) : (
      <ToggleLeft className={cn(
        'text-neutral-400 dark:text-neutral-500 transition-transform hover:scale-105',
        size === 'sm' ? 'w-8 h-8' : 'w-10 h-10'
      )} />
    )}
  </button>
);

// Section Header Component
const SectionHeader: React.FC<{
  icon: React.ReactNode;
  iconBg: string;
  title: string;
  subtitle?: string;
  action?: React.ReactNode;
}> = ({ icon, iconBg, title, subtitle, action }) => (
  <div className="flex items-center justify-between mb-4">
    <div className="flex items-center gap-3">
      <div className={cn('p-2 rounded-lg', iconBg)}>
        {icon}
      </div>
      <div>
        <h2 className="section-title">{title}</h2>
        {subtitle && <p className="text-sm text-neutral-500 dark:text-neutral-400">{subtitle}</p>}
      </div>
    </div>
    {action}
  </div>
);

// ============================================================================
// OPTIONAL TAB COMPONENTS
// ============================================================================

// Line Items Tab
const LineItemsTab: React.FC<{
  formData: ReceivableFormData;
  setFormData: React.Dispatch<React.SetStateAction<ReceivableFormData>>;
  currency: string;
}> = ({ formData, setFormData, currency }) => {
  const addLineItem = () => {
    const newItem: LineItem = {
      id: generateId(),
      description: '',
      itemCode: '',
      quantity: 1,
      unit: 'Each',
      unitPrice: 0,
      discountPercent: 0,
      taxCode: 'VAT5',
      lineTotal: 0,
    };
    setFormData(prev => ({
      ...prev,
      lineItems: [...prev.lineItems, newItem],
    }));
  };

  const updateLineItem = (index: number, field: keyof LineItem, value: any) => {
    setFormData(prev => ({
      ...prev,
      lineItems: prev.lineItems.map((item, i) =>
        i === index ? { ...item, [field]: value } : item
      ),
    }));
  };

  const removeLineItem = (index: number) => {
    setFormData(prev => ({
      ...prev,
      lineItems: prev.lineItems.filter((_, i) => i !== index),
    }));
  };

  const totals = useMemo(() => {
    const subtotal = formData.lineItems.reduce((sum, item) => {
      return sum + (item.quantity * item.unitPrice);
    }, 0);
    const discount = formData.lineItems.reduce((sum, item) => {
      return sum + (item.quantity * item.unitPrice * item.discountPercent / 100);
    }, 0);
    const tax = formData.lineItems.reduce((sum, item) => {
      const taxRate = TAX_CONFIGS.find(t => t.code === item.taxCode)?.rate || 0;
      const itemSubtotal = item.quantity * item.unitPrice;
      const itemDiscount = itemSubtotal * item.discountPercent / 100;
      return sum + ((itemSubtotal - itemDiscount) * taxRate / 100);
    }, 0);
    return { subtotal, discount, tax, total: subtotal - discount + tax };
  }, [formData.lineItems]);

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <Toggle
            enabled={formData.useLineItems}
            onChange={(enabled) => setFormData(prev => ({ ...prev, useLineItems: enabled }))}
          />
          <div>
            <p className="text-sm font-medium text-neutral-900 dark:text-neutral-50">Use Line Items</p>
            <p className="text-xs text-neutral-500 dark:text-neutral-400">Break down invoice into individual items</p>
          </div>
        </div>
      </div>

      {formData.useLineItems && (
        <>
          {/* Header */}
          <div className="grid grid-cols-12 gap-2 px-3 text-xs font-medium text-neutral-500 dark:text-neutral-400 uppercase">
            <div className="col-span-3">Description</div>
            <div className="col-span-1">Code</div>
            <div className="col-span-1">Qty</div>
            <div className="col-span-1">Unit</div>
            <div className="col-span-1">Price</div>
            <div className="col-span-1">Disc %</div>
            <div className="col-span-1">Tax</div>
            <div className="col-span-2">Total</div>
          </div>

          {/* Line Items */}
          <div className="space-y-2">
            {formData.lineItems.map((item, index) => (
              <LineItemRow
                key={item.id}
                item={item}
                index={index}
                taxConfigs={TAX_CONFIGS}
                onChange={updateLineItem}
                onRemove={removeLineItem}
              />
            ))}
          </div>

          {/* Add Button */}
          <button
            onClick={addLineItem}
            className="w-full py-2 border-2 border-dashed border-neutral-300 dark:border-primary-700 rounded-lg text-sm text-neutral-600 dark:text-neutral-300 hover:border-primary-400 hover:text-primary-600 dark:text-primary-200 transition-colors flex items-center justify-center gap-2"
          >
            <Plus className="w-4 h-4" />
            Add Line Item
          </button>

          {/* Totals */}
          {formData.lineItems.length > 0 && (
            <div className="border-t pt-4 space-y-2">
              <div className="flex justify-between text-sm">
                <span className="text-neutral-500 dark:text-neutral-400">Subtotal</span>
                <span className="font-medium">{formatCurrency(totals.subtotal, currency)}</span>
              </div>
              {totals.discount > 0 && (
                <div className="flex justify-between text-sm">
                  <span className="text-neutral-500 dark:text-neutral-400">Discount</span>
                  <span className="font-medium text-error-600 dark:text-error-300">-{formatCurrency(totals.discount, currency)}</span>
                </div>
              )}
              <div className="flex justify-between text-sm">
                <span className="text-neutral-500 dark:text-neutral-400">Tax</span>
                <span className="font-medium">{formatCurrency(totals.tax, currency)}</span>
              </div>
              <div className="flex justify-between text-base font-semibold border-t pt-2">
                <span>Total</span>
                <span className="text-primary-600 dark:text-primary-200">{formatCurrency(totals.total, currency)}</span>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
};

// Tax & Charges Tab
const TaxChargesTab: React.FC<{
  formData: ReceivableFormData;
  setFormData: React.Dispatch<React.SetStateAction<ReceivableFormData>>;
  currency: string;
}> = ({ formData, setFormData, currency }) => {
  const addCharge = () => {
    setFormData(prev => ({
      ...prev,
      charges: [...prev.charges, {
        id: generateId(),
        type: 'CUSTOM',
        description: '',
        amount: 0,
        isPercentage: false,
      }],
    }));
  };

  const removeCharge = (index: number) => {
    setFormData(prev => ({
      ...prev,
      charges: prev.charges.filter((_, i) => i !== index),
    }));
  };

  const updateCharge = (index: number, field: keyof ChargeConfig, value: any) => {
    setFormData(prev => ({
      ...prev,
      charges: prev.charges.map((c, i) => i === index ? { ...c, [field]: value } : c),
    }));
  };

  return (
    <div className="space-y-6">
      {/* VAT Configuration */}
      <div className="p-4 bg-neutral-50 dark:bg-primary-950 rounded-lg space-y-4">
        <h4 className="text-sm font-medium text-neutral-900 dark:text-neutral-50">VAT Configuration</h4>
        
        <div className="flex items-center justify-between">
          <div>
            <p className="text-sm text-neutral-700 dark:text-neutral-200">Apply VAT</p>
            <p className="text-xs text-neutral-500 dark:text-neutral-400">Standard VAT rate for invoice</p>
          </div>
          <div className="flex items-center gap-3">
            <select
              value={formData.taxConfig.vatRate}
              onChange={(e) => setFormData(prev => ({
                ...prev,
                taxConfig: { ...prev.taxConfig, vatRate: parseFloat(e.target.value), vatEnabled: true }
              }))}
              className="px-3 py-1.5 text-sm border border-neutral-300 dark:border-primary-700 rounded-lg"
              disabled={formData.taxConfig.taxExempt}
            >
              <option value="0">0%</option>
              <option value="5">5%</option>
              <option value="15">15%</option>
            </select>
            <Toggle
              enabled={formData.taxConfig.vatEnabled && !formData.taxConfig.taxExempt}
              onChange={(enabled) => setFormData(prev => ({
                ...prev,
                taxConfig: { ...prev.taxConfig, vatEnabled: enabled }
              }))}
              size="sm"
            />
          </div>
        </div>

        <div className="flex items-center justify-between">
          <div>
            <p className="text-sm text-neutral-700 dark:text-neutral-200">Reverse Charge</p>
            <p className="text-xs text-neutral-500 dark:text-neutral-400">Customer accounts for VAT</p>
          </div>
          <Toggle
            enabled={formData.taxConfig.reverseCharge}
            onChange={(enabled) => setFormData(prev => ({
              ...prev,
              taxConfig: { ...prev.taxConfig, reverseCharge: enabled }
            }))}
            size="sm"
          />
        </div>

        <div className="flex items-center justify-between">
          <div>
            <p className="text-sm text-neutral-700 dark:text-neutral-200">Tax Exempt</p>
            <p className="text-xs text-neutral-500 dark:text-neutral-400">Invoice is exempt from tax</p>
          </div>
          <Toggle
            enabled={formData.taxConfig.taxExempt}
            onChange={(enabled) => setFormData(prev => ({
              ...prev,
              taxConfig: { ...prev.taxConfig, taxExempt: enabled, vatEnabled: !enabled }
            }))}
            size="sm"
          />
        </div>

        {formData.taxConfig.taxExempt && (
          <input
            type="text"
            value={formData.taxConfig.exemptionReason}
            onChange={(e) => setFormData(prev => ({
              ...prev,
              taxConfig: { ...prev.taxConfig, exemptionReason: e.target.value }
            }))}
            placeholder="Exemption reason..."
            className="w-full px-3 py-2 text-sm border border-neutral-300 dark:border-primary-700 rounded-lg"
          />
        )}
      </div>

      {/* Additional Charges */}
      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <h4 className="text-sm font-medium text-neutral-900 dark:text-neutral-50">Additional Charges</h4>
          <button
            onClick={addCharge}
            className="text-sm text-primary-600 dark:text-primary-200 hover:text-primary-700 dark:text-neutral-200 flex items-center gap-1 dark:hover:text-neutral-200"
          >
            <Plus className="w-4 h-4" />
            Add Charge
          </button>
        </div>

        {formData.charges.map((charge, index) => (
          <div key={charge.id} className="flex items-center gap-2 p-3 bg-neutral-50 dark:bg-primary-950 rounded-lg">
            <select
              value={charge.type}
              onChange={(e) => updateCharge(index, 'type', e.target.value)}
              className="px-3 py-1.5 text-sm border border-neutral-300 dark:border-primary-700 rounded-lg"
            >
              {CHARGE_TYPES.map(t => <option key={t.value} value={t.value}>{t.label}</option>)}
            </select>
            <input
              type="text"
              value={charge.description}
              onChange={(e) => updateCharge(index, 'description', e.target.value)}
              placeholder="Description"
              className="flex-1 px-3 py-1.5 text-sm border border-neutral-300 dark:border-primary-700 rounded-lg"
            />
            <input
              type="number"
              value={charge.amount}
              onChange={(e) => updateCharge(index, 'amount', parseFloat(e.target.value) || 0)}
              className="w-24 px-3 py-1.5 text-sm border border-neutral-300 dark:border-primary-700 rounded-lg"
            />
            <select
              value={charge.isPercentage ? 'percent' : 'fixed'}
              onChange={(e) => updateCharge(index, 'isPercentage', e.target.value === 'percent')}
              className="px-3 py-1.5 text-sm border border-neutral-300 dark:border-primary-700 rounded-lg"
            >
              <option value="fixed">{currency}</option>
              <option value="percent">%</option>
            </select>
            <button onClick={() => removeCharge(index)} className="p-1 text-error-500 hover:bg-error-50 rounded dark:hover:bg-error-500/10">
              <Trash2 className="w-4 h-4" />
            </button>
          </div>
        ))}
      </div>

      {/* Early Payment Discount */}
      <div className="p-4 bg-success-50 rounded-lg space-y-3 dark:bg-success-500/10">
        <div className="flex items-center justify-between">
          <div>
            <p className="text-sm font-medium text-neutral-900 dark:text-neutral-50">Early Payment Discount</p>
            <p className="text-xs text-neutral-500 dark:text-neutral-400">Offer discount for early payment</p>
          </div>
          <Toggle
            enabled={formData.earlyPaymentDiscount.enabled}
            onChange={(enabled) => setFormData(prev => ({
              ...prev,
              earlyPaymentDiscount: { ...prev.earlyPaymentDiscount, enabled }
            }))}
            size="sm"
          />
        </div>
        {formData.earlyPaymentDiscount.enabled && (
          <div className="flex items-center gap-3">
            <div className="flex items-center gap-2">
              <input
                type="number"
                value={formData.earlyPaymentDiscount.percent}
                onChange={(e) => setFormData(prev => ({
                  ...prev,
                  earlyPaymentDiscount: { ...prev.earlyPaymentDiscount, percent: parseFloat(e.target.value) || 0 }
                }))}
                className="w-16 px-2 py-1.5 text-sm border border-neutral-300 dark:border-primary-700 rounded"
              />
              <span className="text-sm text-neutral-600 dark:text-neutral-300">% off if paid within</span>
              <input
                type="number"
                value={formData.earlyPaymentDiscount.days}
                onChange={(e) => setFormData(prev => ({
                  ...prev,
                  earlyPaymentDiscount: { ...prev.earlyPaymentDiscount, days: parseInt(e.target.value) || 0 }
                }))}
                className="w-16 px-2 py-1.5 text-sm border border-neutral-300 dark:border-primary-700 rounded"
              />
              <span className="text-sm text-neutral-600 dark:text-neutral-300">days</span>
            </div>
          </div>
        )}
      </div>

      {/* Late Payment Fee */}
      <div className="p-4 bg-error-50 rounded-lg space-y-3 dark:bg-error-500/10">
        <div className="flex items-center justify-between">
          <div>
            <p className="text-sm font-medium text-neutral-900 dark:text-neutral-50">Late Payment Fee</p>
            <p className="text-xs text-neutral-500 dark:text-neutral-400">Charge fee for overdue payments</p>
          </div>
          <Toggle
            enabled={formData.latePaymentFee.enabled}
            onChange={(enabled) => setFormData(prev => ({
              ...prev,
              latePaymentFee: { ...prev.latePaymentFee, enabled }
            }))}
            size="sm"
          />
        </div>
        {formData.latePaymentFee.enabled && (
          <div className="flex items-center gap-2">
            <input
              type="number"
              value={formData.latePaymentFee.percent}
              onChange={(e) => setFormData(prev => ({
                ...prev,
                latePaymentFee: { ...prev.latePaymentFee, percent: parseFloat(e.target.value) || 0 }
              }))}
              className="w-16 px-2 py-1.5 text-sm border border-neutral-300 dark:border-primary-700 rounded"
            />
            <span className="text-sm text-neutral-600 dark:text-neutral-300">% per month after due date</span>
          </div>
        )}
      </div>
    </div>
  );
};

// Payment Link Tab
// TODO(categorical): decorative one-off — migrate or bless in a later pass.
// The payment-link panel's indigo theming is feature decoration, not
// categorical data; Phase 12 Task C left it on raw indigo by design.
/* eslint-disable no-restricted-syntax -- decorative one-off, TODO(categorical) */
const PaymentLinkTab: React.FC<{
  formData: ReceivableFormData;
  setFormData: React.Dispatch<React.SetStateAction<ReceivableFormData>>;
  dueDate: string;
}> = ({ formData, setFormData, dueDate }) => {
  const handleGenerateLink = () => {
    const linkData = generatePaymentLink();
    linkData.expiresAt = dueDate;
    setFormData(prev => ({ ...prev, paymentLink: linkData }));
  };

  const copyToClipboard = (text: string) => {
    navigator.clipboard.writeText(text);
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="p-2 bg-indigo-100 rounded-lg dark:bg-indigo-500/20">
            <Link2 className="w-5 h-5 text-indigo-600 dark:text-indigo-300" />
          </div>
          <div>
            <p className="text-sm font-medium text-neutral-900 dark:text-neutral-50">Payment Link & QR Code</p>
            <p className="text-xs text-neutral-500 dark:text-neutral-400">Generate shareable payment link for customer</p>
          </div>
        </div>
        {!formData.paymentLink.enabled ? (
          <button
            onClick={handleGenerateLink}
            className="px-4 py-2 text-sm font-medium bg-indigo-600 text-white rounded-lg hover:bg-indigo-700"
          >
            Generate Link
          </button>
        ) : (
          <span className="flex items-center gap-1 text-success-600 text-sm font-medium dark:text-success-300">
            <Check className="w-4 h-4" />
            Generated
          </span>
        )}
      </div>

      {formData.paymentLink.enabled && (
        <div className="space-y-4 p-4 bg-indigo-50 rounded-lg dark:bg-indigo-500/10">
          {/* Full URL */}
          <div>
            <p className="text-xs text-indigo-600 font-medium mb-1 dark:text-indigo-300">Payment Link</p>
            <div className="flex items-center gap-2">
              <code className="flex-1 text-sm bg-white dark:bg-primary-900 px-3 py-2 rounded border border-indigo-200 font-mono truncate dark:border-indigo-500/30">
                {formData.paymentLink.fullUrl}
              </code>
              <button
                onClick={() => copyToClipboard(formData.paymentLink.fullUrl)}
                className="p-2 hover:bg-indigo-100 rounded dark:hover:bg-indigo-500/20"
              >
                <Copy className="w-4 h-4 text-indigo-600 dark:text-indigo-300" />
              </button>
            </div>
          </div>

          {/* Short URL */}
          <div>
            <p className="text-xs text-indigo-600 font-medium mb-1 dark:text-indigo-300">Short Link</p>
            <div className="flex items-center gap-2">
              <code className="flex-1 text-sm bg-white dark:bg-primary-900 px-3 py-2 rounded border border-indigo-200 font-mono dark:border-indigo-500/30">
                {formData.paymentLink.shortUrl}
              </code>
              <button
                onClick={() => copyToClipboard(formData.paymentLink.shortUrl)}
                className="p-2 hover:bg-indigo-100 rounded dark:hover:bg-indigo-500/20"
              >
                <Copy className="w-4 h-4 text-indigo-600 dark:text-indigo-300" />
              </button>
            </div>
          </div>

          {/* QR Code Placeholder */}
          <div className="flex items-center justify-between p-4 bg-white dark:bg-primary-900 rounded-lg border border-indigo-200 dark:border-indigo-500/30">
            <div className="flex items-center gap-3">
              <div className="w-16 h-16 bg-neutral-100 dark:bg-primary-800 rounded-lg flex items-center justify-center border-2 border-dashed border-neutral-300 dark:border-primary-700">
                <QrCode className="w-8 h-8 text-neutral-400 dark:text-neutral-500" />
              </div>
              <div>
                <p className="text-sm font-medium text-neutral-900 dark:text-neutral-50">QR Code Ready</p>
                <p className="text-xs text-neutral-500 dark:text-neutral-400">Scan to pay directly</p>
              </div>
            </div>
            <div className="flex gap-2">
              <button className="p-2 hover:bg-neutral-100 dark:hover:bg-primary-800 rounded-lg" title="Download QR">
                <Download className="w-4 h-4 text-neutral-600 dark:text-neutral-300" />
              </button>
              <button className="p-2 hover:bg-neutral-100 dark:hover:bg-primary-800 rounded-lg" title="View Full">
                <ExternalLink className="w-4 h-4 text-neutral-600 dark:text-neutral-300" />
              </button>
            </div>
          </div>

          {/* Share Options */}
          <div>
            <p className="text-xs text-indigo-600 font-medium mb-2 dark:text-indigo-300">Share via</p>
            <div className="flex gap-2">
              <button className="flex items-center gap-2 px-3 py-2 bg-white dark:bg-primary-900 border border-indigo-200 rounded-lg text-sm hover:bg-indigo-100 dark:border-indigo-500/30 dark:hover:bg-indigo-500/20">
                <Mail className="w-4 h-4 text-indigo-600 dark:text-indigo-300" />
                Email
              </button>
              <button className="flex items-center gap-2 px-3 py-2 bg-white dark:bg-primary-900 border border-indigo-200 rounded-lg text-sm hover:bg-indigo-100 dark:border-indigo-500/30 dark:hover:bg-indigo-500/20">
                <MessageSquare className="w-4 h-4 text-success-600 dark:text-success-300" />
                WhatsApp
              </button>
              <button className="flex items-center gap-2 px-3 py-2 bg-white dark:bg-primary-900 border border-indigo-200 rounded-lg text-sm hover:bg-indigo-100 dark:border-indigo-500/30 dark:hover:bg-indigo-500/20">
                <Phone className="w-4 h-4 text-info-600 dark:text-info-300" />
                SMS
              </button>
            </div>
          </div>

          {/* Accepted Methods */}
          <div className="pt-3 border-t border-indigo-200 dark:border-indigo-500/30">
            <p className="text-xs text-neutral-500 dark:text-neutral-400 mb-2">Accepted payment methods</p>
            <div className="flex gap-2">
              <span className="px-2 py-1 bg-white dark:bg-primary-900 text-xs rounded border">Credit Card</span>
              <span className="px-2 py-1 bg-white dark:bg-primary-900 text-xs rounded border">Bank Transfer</span>
              <span className="px-2 py-1 bg-white dark:bg-primary-900 text-xs rounded border">Apple Pay</span>
              <span className="px-2 py-1 bg-white dark:bg-primary-900 text-xs rounded border">Google Pay</span>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
/* eslint-enable no-restricted-syntax */

// COBO Tab
const CoboTab: React.FC<{
  formData: ReceivableFormData;
  setFormData: React.Dispatch<React.SetStateAction<ReceivableFormData>>;
  entities: IntercompanyEntity[];
}> = ({ formData, setFormData, entities }) => {
  const collectingEntity = entities.find(e => e.id === formData.collectingEntityId);
  const behalfEntity = entities.find(e => e.id === formData.behalfEntityId);

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className={cn('p-2 rounded-lg', formData.coboEnabled ? 'bg-success-100 dark:bg-success-500/20' : 'bg-neutral-100 dark:bg-primary-800')}>
            <ArrowDownLeft className={cn('w-5 h-5', formData.coboEnabled ? 'text-success-600 dark:text-success-300' : 'text-neutral-500 dark:text-neutral-400')} />
          </div>
          <div>
            <p className="text-sm font-medium text-neutral-900 dark:text-neutral-50">Collect On Behalf Of (COBO)</p>
            <p className="text-xs text-neutral-500 dark:text-neutral-400">Treasury collects for subsidiary, creates IHB deposit</p>
          </div>
        </div>
        <Toggle
          enabled={formData.coboEnabled}
          onChange={(enabled) => setFormData(prev => ({ ...prev, coboEnabled: enabled }))}
        />
      </div>

      {formData.coboEnabled && (
        <div className="space-y-4 p-4 bg-success-50 rounded-lg dark:bg-success-500/10">
          {/* Collecting Entity */}
          <div>
            <label className="field-label block mb-2">
              <Landmark className="w-4 h-4 inline mr-1" />
              Collecting Entity (Treasury)
            </label>
            <select
              value={formData.collectingEntityId}
              onChange={(e) => setFormData(prev => ({ ...prev, collectingEntityId: e.target.value }))}
              className="w-full px-3 py-2 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm"
            >
              <option value="">Select treasury entity...</option>
              {entities.filter(e => e.type === 'HEADQUARTERS').map(e => (
                <option key={e.id} value={e.id}>{e.name} ({e.code})</option>
              ))}
            </select>
          </div>

          {/* On Behalf Entity */}
          <div>
            <label className="field-label block mb-2">
              <Building className="w-4 h-4 inline mr-1" />
              On Behalf Of (Subsidiary)
            </label>
            <select
              value={formData.behalfEntityId}
              onChange={(e) => setFormData(prev => ({ ...prev, behalfEntityId: e.target.value }))}
              className="w-full px-3 py-2 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm"
              disabled={!formData.collectingEntityId}
            >
              <option value="">Select subsidiary...</option>
              {entities.filter(e => e.type !== 'HEADQUARTERS' && e.id !== formData.collectingEntityId).map(e => (
                <option key={e.id} value={e.id}>{e.name} ({e.code})</option>
              ))}
            </select>
          </div>

          {/* Recharge Markup */}
          <div>
            <label className="field-label block mb-2">
              Recharge Markup %
            </label>
            <input
              type="number"
              value={formData.coboRechargeMarkup}
              onChange={(e) => setFormData(prev => ({ ...prev, coboRechargeMarkup: parseFloat(e.target.value) || 0 }))}
              className="w-24 px-3 py-2 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm"
              step="0.1"
            />
          </div>

          {/* Preview */}
          {collectingEntity && behalfEntity && (
            <div className="p-3 bg-white dark:bg-primary-900 rounded-lg border border-success-200 dark:border-success-500/30">
              <p className="text-xs text-neutral-500 dark:text-neutral-400 mb-2">COBO Flow</p>
              <div className="flex items-center justify-between text-sm">
                <span className="font-medium">Customer pays →</span>
                <span className="text-primary-600 dark:text-primary-200">{collectingEntity.code}</span>
                <span>→</span>
                <span className="text-success-600 dark:text-success-300">IHB Deposit for {behalfEntity.code}</span>
              </div>
            </div>
          )}

          {/* Info Note */}
          <div className="flex items-start gap-2 p-3 bg-white dark:bg-primary-900 rounded-lg border border-success-200 dark:border-success-500/30">
            <Info className="w-4 h-4 text-success-600 flex-shrink-0 mt-0.5 dark:text-success-300" />
            <p className="text-xs text-neutral-600 dark:text-neutral-300">
              When payment is received, treasury will create an IHB deposit for the subsidiary. Interest accrues until settlement.
            </p>
          </div>
        </div>
      )}
    </div>
  );
};

// Hierarchy Tab
const HierarchyTab: React.FC<{
  formData: ReceivableFormData;
  setFormData: React.Dispatch<React.SetStateAction<ReceivableFormData>>;
  hierarchyNodes: HierarchyNode[];
}> = ({ formData, setFormData, hierarchyNodes }) => {
  return (
    <div className="space-y-4">
      <p className="text-sm text-neutral-600 dark:text-neutral-300">
        Assign this receivable to a hierarchy node for reporting and allocation.
      </p>

      <div className="space-y-2">
        {hierarchyNodes.map((node) => (
          <button
            key={node.id}
            onClick={() => setFormData(prev => ({
              ...prev,
              hierarchyNodeId: prev.hierarchyNodeId === node.id ? '' : node.id
            }))}
            className={cn(
              'w-full flex items-center justify-between p-3 border rounded-lg transition-all text-left',
              formData.hierarchyNodeId === node.id
                ? 'border-primary-500 bg-primary-50 dark:bg-primary-800/40'
                : 'border-neutral-200 dark:border-primary-800 hover:border-primary-300'
            )}
          >
            <div>
              <p className="text-sm font-medium text-neutral-900 dark:text-neutral-50">{node.name}</p>
              <p className="text-xs text-neutral-500 dark:text-neutral-400">{node.path}</p>
            </div>
            <span className="text-xs px-2 py-1 bg-neutral-100 dark:bg-primary-800 text-neutral-600 dark:text-neutral-300 rounded">
              {node.level}
            </span>
          </button>
        ))}
      </div>

      {/* Revenue Center */}
      <div className="pt-4 border-t">
        <label className="field-label block mb-2">Revenue Center</label>
        <input
          type="text"
          value={formData.revenueCenter}
          onChange={(e) => setFormData(prev => ({ ...prev, revenueCenter: e.target.value }))}
          placeholder="e.g., RC-001, Sales-Dubai"
          className="w-full px-3 py-2 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm"
        />
      </div>
    </div>
  );
};

// Documents Tab
const DocumentsTab: React.FC<{
  formData: ReceivableFormData;
  setFormData: React.Dispatch<React.SetStateAction<ReceivableFormData>>;
}> = ({ formData, setFormData }) => {
  const handleFileUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files || []);
    setFormData(prev => ({ ...prev, attachments: [...prev.attachments, ...files] }));
  };

  const handleRemoveFile = (index: number) => {
    setFormData(prev => ({
      ...prev,
      attachments: prev.attachments.filter((_, i) => i !== index),
    }));
  };

  const handleAddRef = () => {
    setFormData(prev => ({
      ...prev,
      externalRefs: [...prev.externalRefs, { type: 'PO', value: '' }],
    }));
  };

  const handleRemoveRef = (index: number) => {
    setFormData(prev => ({
      ...prev,
      externalRefs: prev.externalRefs.filter((_, i) => i !== index),
    }));
  };

  const handleUpdateRef = (index: number, field: 'type' | 'value', value: string) => {
    setFormData(prev => ({
      ...prev,
      externalRefs: prev.externalRefs.map((ref, i) =>
        i === index ? { ...ref, [field]: value } : ref
      ),
    }));
  };

  return (
    <div className="space-y-6">
      {/* File Upload */}
      <div>
        <p className="field-label mb-2">Attachments</p>
        <div className="border-2 border-dashed border-neutral-200 dark:border-primary-800 rounded-lg p-6 text-center hover:border-primary-300 transition-colors">
          <input
            type="file"
            multiple
            onChange={handleFileUpload}
            className="hidden"
            id="file-upload"
          />
          <label htmlFor="file-upload" className="cursor-pointer">
            <Upload className="w-8 h-8 text-neutral-400 dark:text-neutral-500 mx-auto mb-2" />
            <p className="text-sm text-neutral-600 dark:text-neutral-300">Click to upload or drag and drop</p>
            <p className="text-xs text-neutral-400 dark:text-neutral-500 mt-1">PDF, DOC, XLS up to 10MB</p>
          </label>
        </div>

        {formData.attachments.length > 0 && (
          <div className="mt-3 space-y-2">
            {formData.attachments.map((file, index) => (
              <div key={index} className="flex items-center justify-between p-2 bg-neutral-50 dark:bg-primary-950 rounded-lg">
                <div className="flex items-center gap-2">
                  <FileText className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
                  <span className="text-sm text-neutral-700 dark:text-neutral-200">{file.name}</span>
                  <span className="text-xs text-neutral-400 dark:text-neutral-500">({(file.size / 1024).toFixed(1)} KB)</span>
                </div>
                <button onClick={() => handleRemoveFile(index)} className="p-1 hover:bg-neutral-200 rounded">
                  <X className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
                </button>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* External References */}
      <div>
        <div className="flex items-center justify-between mb-2">
          <p className="field-label">External References</p>
          <button onClick={handleAddRef} className="text-sm text-primary-600 dark:text-primary-200 hover:text-primary-700 dark:text-neutral-200 flex items-center gap-1 dark:hover:text-neutral-200">
            <Plus className="w-4 h-4" />
            Add Reference
          </button>
        </div>
        {formData.externalRefs.length > 0 ? (
          <div className="space-y-2">
            {formData.externalRefs.map((ref, index) => (
              <div key={index} className="flex items-center gap-2">
                <select
                  value={ref.type}
                  onChange={(e) => handleUpdateRef(index, 'type', e.target.value)}
                  className="px-3 py-2 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm"
                >
                  {REFERENCE_TYPES.map(t => <option key={t.value} value={t.value}>{t.label}</option>)}
                </select>
                <input
                  type="text"
                  value={ref.value}
                  onChange={(e) => handleUpdateRef(index, 'value', e.target.value)}
                  placeholder="Reference number..."
                  className="flex-1 px-3 py-2 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm"
                />
                <button onClick={() => handleRemoveRef(index)} className="p-2 hover:bg-neutral-100 dark:hover:bg-primary-800 rounded-lg">
                  <X className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
                </button>
              </div>
            ))}
          </div>
        ) : (
          <p className="text-sm text-neutral-400 dark:text-neutral-500 italic">No external references added</p>
        )}
      </div>

      {/* Notes */}
      <div className="grid grid-cols-2 gap-4">
        <div>
          <label className="field-label block mb-2">Customer Notes</label>
          <textarea
            value={formData.customerNotes}
            onChange={(e) => setFormData(prev => ({ ...prev, customerNotes: e.target.value }))}
            rows={3}
            placeholder="Printed on invoice..."
            className="w-full px-3 py-2 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm resize-none"
          />
        </div>
        <div>
          <label className="field-label block mb-2">Internal Notes</label>
          <textarea
            value={formData.internalNotes}
            onChange={(e) => setFormData(prev => ({ ...prev, internalNotes: e.target.value }))}
            rows={3}
            placeholder="Not visible to customer..."
            className="w-full px-3 py-2 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm resize-none"
          />
        </div>
      </div>
    </div>
  );
};

// Reminders Tab
const RemindersTab: React.FC<{
  formData: ReceivableFormData;
  setFormData: React.Dispatch<React.SetStateAction<ReceivableFormData>>;
}> = ({ formData, setFormData }) => {
  const toggleReminderDay = (day: number) => {
    setFormData(prev => ({
      ...prev,
      reminderDays: prev.reminderDays.includes(day)
        ? prev.reminderDays.filter(d => d !== day)
        : [...prev.reminderDays, day].sort((a, b) => a - b),
    }));
  };

  const dunningLevels = [
    { level: 1, name: 'Friendly Reminder', description: 'Gentle payment reminder' },
    { level: 2, name: 'Formal Notice', description: 'Official payment request' },
    { level: 3, name: 'Final Notice', description: 'Last warning before action' },
    { level: 4, name: 'Collection Action', description: 'Escalate to collections' },
  ];

  return (
    <div className="space-y-6">
      {/* Send Invoice Email */}
      <div className="flex items-center justify-between p-4 bg-info-50 rounded-lg dark:bg-info-500/10">
        <div className="flex items-center gap-3">
          <Mail className="w-5 h-5 text-info-600 dark:text-info-300" />
          <div>
            <p className="text-sm font-medium text-neutral-900 dark:text-neutral-50">Send Invoice on Create</p>
            <p className="text-xs text-neutral-500 dark:text-neutral-400">Email invoice to customer when created</p>
          </div>
        </div>
        <Toggle
          enabled={formData.sendInvoiceEmail}
          onChange={(enabled) => setFormData(prev => ({ ...prev, sendInvoiceEmail: enabled }))}
          size="sm"
        />
      </div>

      {/* Payment Reminders */}
      <div className="space-y-3">
        <h4 className="text-sm font-medium text-neutral-900 dark:text-neutral-50">Payment Reminder Schedule</h4>
        <p className="text-xs text-neutral-500 dark:text-neutral-400">Select days relative to due date (negative = before, positive = after)</p>
        <div className="flex flex-wrap gap-2">
          {REMINDER_DAYS.map((day) => (
            <button
              key={day}
              onClick={() => toggleReminderDay(day)}
              className={cn(
                'px-3 py-2 text-sm rounded-lg border transition-colors',
                formData.reminderDays.includes(day)
                  ? 'bg-primary-100 dark:bg-primary-700 border-primary-500 text-primary-700 dark:text-neutral-200'
                  : 'bg-white dark:bg-primary-900 border-neutral-300 dark:border-primary-700 text-neutral-600 dark:text-neutral-300 hover:border-primary-300'
              )}
            >
              {day > 0 ? `+${day}` : day} days
            </button>
          ))}
        </div>
        {formData.reminderDays.length > 0 && (
          <p className="text-xs text-neutral-500 dark:text-neutral-400">
            Reminders will be sent: {formData.reminderDays.map(d => d > 0 ? `${d} days after` : d === 0 ? 'on due date' : `${Math.abs(d)} days before`).join(', ')}
          </p>
        )}
      </div>

      {/* Dunning Process */}
      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <div>
            <h4 className="text-sm font-medium text-neutral-900 dark:text-neutral-50">Dunning Process</h4>
            <p className="text-xs text-neutral-500 dark:text-neutral-400">Automated escalation for overdue invoices</p>
          </div>
          <Toggle
            enabled={formData.enableDunning}
            onChange={(enabled) => setFormData(prev => ({ ...prev, enableDunning: enabled }))}
            size="sm"
          />
        </div>

        {formData.enableDunning && (
          <div className="space-y-2">
            {dunningLevels.map((level) => (
              <div
                key={level.level}
                className={cn(
                  'flex items-center justify-between p-3 rounded-lg border',
                  level.level <= formData.dunningMaxLevel
                    ? 'bg-warning-50 border-warning-200 dark:bg-warning-500/10 dark:border-warning-500/30'
                    : 'bg-neutral-50 dark:bg-primary-950 border-neutral-200 dark:border-primary-800 opacity-50'
                )}
              >
                <div className="flex items-center gap-3">
                  <div className={cn(
                    'w-8 h-8 rounded-full flex items-center justify-center text-sm font-bold',
                    level.level <= formData.dunningMaxLevel ? 'bg-warning-200 text-warning-700 dark:text-warning-300' : 'bg-neutral-200 text-neutral-500 dark:text-neutral-400 dark:bg-primary-800'
                  )}>
                    {level.level}
                  </div>
                  <div>
                    <p className="text-sm font-medium text-neutral-900 dark:text-neutral-50">{level.name}</p>
                    <p className="text-xs text-neutral-500 dark:text-neutral-400">{level.description}</p>
                  </div>
                </div>
                {level.level <= formData.dunningMaxLevel && (
                  <Check className="w-4 h-4 text-warning-600 dark:text-warning-300" />
                )}
              </div>
            ))}
            <div className="flex items-center gap-2 mt-3">
              <span className="text-sm text-neutral-600 dark:text-neutral-300">Enable up to level:</span>
              <select
                value={formData.dunningMaxLevel}
                onChange={(e) => setFormData(prev => ({ ...prev, dunningMaxLevel: parseInt(e.target.value) }))}
                className="px-3 py-1.5 text-sm border border-neutral-300 dark:border-primary-700 rounded-lg"
              >
                {[1, 2, 3, 4].map(l => <option key={l} value={l}>{l}</option>)}
              </select>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

// ============================================================================
// MAIN COMPONENT
// ============================================================================

type PageType = 'receivables' | 'receivables-create' | 'receivables-edit' | string;

interface CreateReceivablePageProps {
  receivableId?: string;
}

const CreateReceivablePage: React.FC<CreateReceivablePageProps> = ({ receivableId }) => {
  const isEditMode = !!receivableId;
  const navigation = useNavigation();

  // Get corporateId and legalEntityId from navigation params or use defaults
  const corporateIdFromParams = navigation.params?.corporateId;

  // Debug: log navigation params
  console.log('CreateReceivablePage navigation.params:', navigation.params);
  const legalEntityIdFromParams = navigation.params?.legalEntityId;

  // Use provided values or fall back to demo defaults
  const effectiveCorporateId = corporateIdFromParams || '550e8400-e29b-41d4-a716-446655440000';
  const effectiveLegalEntityId = legalEntityIdFromParams || null;

  console.log('CreateReceivablePage: corporateId from params:', corporateIdFromParams, 'effective:', effectiveCorporateId);
  console.log('CreateReceivablePage: legalEntityId from params:', legalEntityIdFromParams, 'effective:', effectiveLegalEntityId);

  // Form state
  const [formData, setFormData] = useState<ReceivableFormData>({
    customerId: '',
    invoiceNumber: generateInvoiceNumber(),
    invoiceDate: new Date().toISOString().split('T')[0],
    paymentTerms: 'NET30',
    dueDate: '',
    amount: '',
    currency: 'AED',
    reference: '',
    description: '',
    collectionAccountId: '',
    generateViban: false,
    generatedViban: '',
    useLineItems: false,
    lineItems: [],
    taxConfig: {
      vatEnabled: true,
      vatRate: 5,
      reverseCharge: false,
      taxExempt: false,
      exemptionReason: '',
    },
    charges: [],
    earlyPaymentDiscount: { enabled: false, days: 10, percent: 2 },
    latePaymentFee: { enabled: false, percent: 1.5 },
    paymentLink: { enabled: false, linkId: '', fullUrl: '', shortUrl: '', expiresAt: '', qrCodeGenerated: false },
    coboEnabled: false,
    collectingEntityId: '',
    behalfEntityId: '',
    coboRechargeMarkup: 0,
    hierarchyNodeId: '',
    revenueCenter: '',
    attachments: [],
    externalRefs: [],
    customerNotes: '',
    internalNotes: '',
    sendInvoiceEmail: true,
    reminderDays: [-3, 1, 7],
    enableDunning: false,
    dunningMaxLevel: 2,
  });

  // UI state
  const [selectedCustomer, setSelectedCustomer] = useState<Customer | null>(null);
  const [selectedAccount, setSelectedAccount] = useState<CollectionAccount | null>(null);
  const [customerSearch, setCustomerSearch] = useState('');
  const [showCustomerDropdown, setShowCustomerDropdown] = useState(false);
  const [activeTab, setActiveTab] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});

  // Data from APIs
  const [customers, setCustomers] = useState<Customer[]>([]);
  const [accounts, setAccounts] = useState<CollectionAccount[]>([]);
  const [entities, setEntities] = useState<IntercompanyEntity[]>([]);
  const [hierarchyNodes, setHierarchyNodes] = useState<HierarchyNode[]>([]);
  const [dataLoading, setDataLoading] = useState(true);
  const [corporateName, setCorporateName] = useState<string>('');

  // Derived: Get the selected legal entity details
  const selectedLegalEntity = useMemo(() => {
    if (!effectiveLegalEntityId || entities.length === 0) return null;
    return entities.find(e => e.id === effectiveLegalEntityId) || null;
  }, [effectiveLegalEntityId, entities]);

  // Load data from APIs on mount or when corporateId changes
  useEffect(() => {
    const loadData = async () => {
      setDataLoading(true);
      console.log('Loading data for corporateId:', effectiveCorporateId, 'legalEntityId:', effectiveLegalEntityId);

      // Load corporate details
      try {
        const corporateResponse = await corporatesApi.getById(effectiveCorporateId);
        const corporate = corporateResponse?.data || corporateResponse;
        setCorporateName(corporate?.legalName || corporate?.tradeName || corporate?.corporateId || 'Unknown Corporate');
      } catch (error) {
        console.error('Failed to load corporate:', error);
        setCorporateName('Corporate ID: ' + effectiveCorporateId.substring(0, 8) + '...');
      }

      try {
        // Load customers (parties with CUSTOMER role)
        const customersResponse = await partiesApi.getAll({
          corporateId: effectiveCorporateId,
          role: 'CUSTOMER',
          pageSize: 100
        });
        const customersList = customersResponse?.parties || customersResponse?.data?.parties || [];
        setCustomers(customersList.map((p: any) => ({
          id: p.id,
          name: p.displayName || p.legalName || 'Unknown',
          code: p.partyCode || '',
          creditLimit: 0,
          creditUsed: 0,
          creditAvailable: 0,
          currency: 'AED',
          email: p.contactEmail || '',
          overdueAmount: 0
        })));
      } catch (error) {
        console.error('Failed to load customers:', error);
        setCustomers([]);
      }

      try {
        // Load collection accounts (virtual accounts)
        const accountsResponse = await virtualAccountsApi.getAll({ corporateId: effectiveCorporateId, pageSize: 50 });
        const accountsList = accountsResponse?.virtualAccounts || accountsResponse?.data?.virtualAccounts || accountsResponse?.data || [];
        setAccounts(accountsList.map((va: any) => ({
          id: va.id,
          accountName: va.vaName || 'Collection Account',
          accountNumber: va.vaNumber || '',
          iban: va.iban || '',
          bankName: 'Bank',
          currency: va.currencyCode || 'AED',
          isDefault: va.isDefault || false,
          accountType: va.vaType || 'VIRTUAL'
        })));
      } catch (error) {
        console.error('Failed to load accounts:', error);
        setAccounts([]);
      }

      try {
        // Load legal entities for COBO
        const entitiesResponse = await legalEntityApi.getByCorporate(effectiveCorporateId);
        const entitiesList = Array.isArray(entitiesResponse?.data) ? entitiesResponse.data : [];
        setEntities(entitiesList.map((e: any) => ({
          id: e.id,
          code: e.entityCode || '',
          name: e.entityName || '',
          type: e.entityType === 'HEAD_OFFICE' ? 'HEADQUARTERS' : 'SUBSIDIARY',
          currency: e.baseCurrency || 'AED',
          ihbBalance: 0
        })));
      } catch (error) {
        console.error('Failed to load entities:', error);
        setEntities([]);
      }

      // Hierarchy nodes would come from a hierarchy API if available
      setHierarchyNodes([]);

      setDataLoading(false);
    };

    loadData();
  }, [effectiveCorporateId, effectiveLegalEntityId]);

  // Tab definitions
  const tabs = [
    { id: 'line-items', label: 'Line Items', icon: Package, badge: formData.useLineItems ? formData.lineItems.length : 0 },
    { id: 'tax-charges', label: 'Tax & Charges', icon: Calculator, badge: formData.charges.length },
    { id: 'payment-link', label: 'Payment Link', icon: Link2, badge: formData.paymentLink.enabled ? 1 : 0 },
    { id: 'cobo', label: 'COBO', icon: ArrowDownLeft, badge: formData.coboEnabled ? 1 : 0 },
    { id: 'hierarchy', label: 'Hierarchy', icon: FolderTree, badge: formData.hierarchyNodeId ? 1 : 0 },
    { id: 'documents', label: 'Documents', icon: Paperclip, badge: formData.attachments.length + formData.externalRefs.length },
    { id: 'reminders', label: 'Reminders', icon: Bell, badge: formData.enableDunning || formData.reminderDays.length > 0 ? 1 : 0 },
  ];

  // Initialize due date
  useEffect(() => {
    const dueDate = calculateDueDate(formData.invoiceDate, formData.paymentTerms);
    setFormData(prev => ({ ...prev, dueDate }));
  }, [formData.invoiceDate, formData.paymentTerms]);

  // Set default account
  useEffect(() => {
    const defaultAccount = accounts.find(a => a.isDefault && a.currency === formData.currency);
    if (defaultAccount && !formData.collectionAccountId) {
      setFormData(prev => ({ ...prev, collectionAccountId: defaultAccount.id }));
      setSelectedAccount(defaultAccount);
    }
  }, [formData.currency, accounts]);

  // Filter customers
  const filteredCustomers = customers.filter(c =>
    c.name.toLowerCase().includes(customerSearch.toLowerCase()) ||
    c.code.toLowerCase().includes(customerSearch.toLowerCase())
  );

  // Filter accounts
  const availableAccounts = accounts.filter(a => a.currency === formData.currency);

  // Handlers
  const handleSelectCustomer = (customer: Customer) => {
    setSelectedCustomer(customer);
    setFormData(prev => ({ ...prev, customerId: customer.id, currency: customer.currency }));
    setCustomerSearch(customer.name);
    setShowCustomerDropdown(false);
  };

  const handleSelectAccount = (account: CollectionAccount) => {
    setSelectedAccount(account);
    setFormData(prev => ({ ...prev, collectionAccountId: account.id }));
  };

  const handleGenerateViban = () => {
    if (!selectedCustomer) return;
    const viban = generateViban(selectedCustomer.id);
    setFormData(prev => ({ ...prev, generateViban: true, generatedViban: viban }));
  };

  const validate = (): boolean => {
    const newErrors: Record<string, string> = {};
    // Customer and collection account are optional - can be added later
    if (!formData.invoiceNumber) newErrors.invoiceNumber = 'Invoice number is required';
    if (!formData.amount || parseFloat(formData.amount) <= 0) newErrors.amount = 'Valid amount is required';
    if (selectedCustomer && selectedCustomer.creditAvailable > 0 && parseFloat(formData.amount) > selectedCustomer.creditAvailable) {
      newErrors.amount = `Amount exceeds available credit (${formatCurrency(selectedCustomer.creditAvailable, selectedCustomer.currency)})`;
    }
    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleSubmit = async (isDraft: boolean = false) => {
    console.log('handleSubmit called, isDraft:', isDraft);
    setErrors({}); // Clear previous errors
    if (!isDraft && !validate()) {
      console.log('Validation failed');
      return;
    }
    setLoading(true);
    try {
      // Determine owning entity: priority is:
      // 1. If legalEntityId was passed from parent page, use it
      // 2. If COBO enabled, use behalfEntity (subsidiary on whose behalf we're collecting)
      // 3. Otherwise, use first available entity (typically HQ)
      let owningEntityId: string | undefined = effectiveLegalEntityId || undefined;
      let owningEntityCode: string | undefined;

      if (owningEntityId) {
        // Use entity passed from parent page
        const selectedEntity = entities.find(e => e.id === owningEntityId);
        owningEntityCode = selectedEntity?.code;
        console.log('Using owningEntityId from params:', owningEntityId, 'code:', owningEntityCode);
      } else if (formData.coboEnabled && formData.behalfEntityId) {
        // COBO: owning entity is the subsidiary on whose behalf we're collecting
        const behalfEntity = entities.find(e => e.id === formData.behalfEntityId);
        owningEntityId = formData.behalfEntityId;
        owningEntityCode = behalfEntity?.code;
        console.log('Using owningEntityId from COBO behalfEntity:', owningEntityId);
      } else if (entities.length > 0) {
        // Non-COBO: use the first available entity (typically HQ)
        const defaultEntity = entities.find(e => e.type === 'HEADQUARTERS') || entities[0];
        owningEntityId = defaultEntity.id;
        owningEntityCode = defaultEntity.code;
        console.log('Using default owningEntityId:', owningEntityId);
      }

      console.log('Final owning entity:', { owningEntityId, owningEntityCode, coboEnabled: formData.coboEnabled });

      // Build the request payload matching CreateInvoiceRequest DTO
      const createRequest = {
        // Corporate and Entity context - pass in body for proper backend handling
        corporateId: effectiveCorporateId,
        owningEntityId: owningEntityId,
        owningEntityCode: owningEntityCode,
        customerId: formData.customerId,
        customerName: selectedCustomer?.name || '',
        customerVaNumber: selectedCustomer?.assignedViban || '',
        amount: formData.useLineItems && formData.lineItems.length > 0
          ? calculatedTotal
          : parseFloat(formData.amount) || 0,
        currencyCode: formData.currency,
        dueDate: formData.dueDate,
        description: formData.description || `Invoice ${formData.invoiceNumber}`,
        // Target VA (collection account)
        targetVaId: formData.collectionAccountId,
        generateViban: formData.generateViban,
        // VIBAN options
        createViban: formData.generateViban,
        // Auto-reconciliation settings
        autoReconcile: true,
        allowPartialPayment: true,
        allowOverpayment: false,
        // Hierarchy context
        hierarchyNodeId: formData.hierarchyNodeId || undefined,
        // Line items if using itemized invoice
        lineItems: formData.useLineItems ? formData.lineItems.map(item => ({
          description: item.description,
          itemCode: item.itemCode,
          quantity: item.quantity,
          unit: item.unit,
          unitPrice: item.unitPrice,
          discountPercent: item.discountPercent,
          taxCode: item.taxCode,
          lineTotal: item.lineTotal
        })) : undefined,
      };

      console.log('Creating receivable with request:', JSON.stringify(createRequest, null, 2));
      console.log('corporateId:', createRequest.corporateId, 'owningEntityId:', createRequest.owningEntityId, 'owningEntityCode:', createRequest.owningEntityCode);

      // Call the backend API with corporateId header (also sent in body for redundancy)
      const response = await receivablesApi.create(createRequest, effectiveCorporateId);

      console.log('Receivable created:', response);
      navigation.navigate('receivables');
    } catch (error: any) {
      console.error('Submit error:', error);
      setErrors({ submit: error.message || 'Failed to create receivable' });
    } finally {
      setLoading(false);
    }
  };

  const goBack = () => navigation.navigate('receivables');

  const copyToClipboard = (text: string) => {
    navigator.clipboard.writeText(text);
  };

  // Relaxed validation - collection account is optional for now
  const canSubmit = formData.invoiceNumber && formData.amount && parseFloat(formData.amount) > 0;

  // Calculate totals from line items if used
  const calculatedTotal = useMemo(() => {
    if (formData.useLineItems && formData.lineItems.length > 0) {
      return formData.lineItems.reduce((sum, item) => sum + item.lineTotal, 0);
    }
    return parseFloat(formData.amount) || 0;
  }, [formData.useLineItems, formData.lineItems, formData.amount]);

  return (
    // Phase 10 Design System Unification (2026-05-13): page body wrapped
    // in <Page maxWidth="narrow"> (~1024px). Sticky header inner wrapper
    // narrowed to max-w-5xl to align. Horizontal padding comes from <main>.
    <div className="min-h-screen bg-neutral-50 dark:bg-primary-950">
      {/* Header */}
      <div className="sticky top-0 z-20 bg-white dark:bg-primary-900 border-b border-neutral-200 dark:border-primary-800 shadow-sm">
        <div className="max-w-5xl mx-auto py-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-4">
              <button onClick={goBack} className="p-2 hover:bg-neutral-100 dark:hover:bg-primary-800 rounded-lg transition-colors">
                <ArrowLeft className="w-5 h-5 text-neutral-600 dark:text-neutral-300" />
              </button>
              <div>
                <h1 className="text-xl font-semibold text-neutral-900 dark:text-neutral-50">
                  {isEditMode ? 'Edit Receivable' : 'Create Receivable'}
                </h1>
                <p className="text-sm text-neutral-500 dark:text-neutral-400">{formData.invoiceNumber}</p>
              </div>
            </div>
            <div className="flex items-center gap-3">
              <button
                onClick={() => handleSubmit(true)}
                disabled={loading}
                className="px-4 py-2 field-label bg-white dark:bg-primary-900 border border-neutral-300 dark:border-primary-700 rounded-lg hover:bg-neutral-50 dark:hover:bg-primary-800/50 transition-colors disabled:opacity-50"
              >
                Save Draft
              </button>
              <button
                onClick={() => handleSubmit(false)}
                disabled={loading || !canSubmit}
                className={cn(
                  'px-4 py-2 text-sm font-medium text-white rounded-lg transition-colors flex items-center gap-2',
                  canSubmit ? 'bg-primary-600 hover:bg-primary-700' : 'bg-neutral-300 cursor-not-allowed'
                )}
              >
                {loading && <Loader2 className="w-4 h-4 animate-spin" />}
                {isEditMode ? 'Update' : 'Create Invoice'}
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* Main Content */}
      <Page maxWidth="narrow" className="py-8">
        {/* Corporate & Entity Context Banner */}
        <div className="mb-6 p-4 bg-gradient-to-r from-info-50 to-cat-1-soft border border-info-200 rounded-xl dark:border-info-500/30 dark:from-info-500/15 dark:to-cat-1/15">
          <div className="flex items-center gap-6">
            <div className="flex items-center gap-3">
              <div className="p-2 bg-info-100 rounded-lg dark:bg-info-500/20">
                <Building className="w-5 h-5 text-info-600 dark:text-info-300" />
              </div>
              <div>
                <p className="text-xs font-medium text-info-600 uppercase tracking-wide dark:text-info-300">Corporate</p>
                <p className="text-sm font-semibold text-neutral-900 dark:text-neutral-50">
                  {dataLoading ? 'Loading...' : corporateName}
                </p>
              </div>
            </div>
            <div className="w-px h-10 bg-info-200" />
            <div className="flex items-center gap-3">
              <div className="p-2 bg-cat-1/10 rounded-lg dark:bg-cat-1/15">
                <Landmark className="w-5 h-5 text-cat-1" />
              </div>
              <div>
                <p className="text-xs font-medium text-cat-1 uppercase tracking-wide">Legal Entity</p>
                <p className="text-sm font-semibold text-neutral-900 dark:text-neutral-50">
                  {dataLoading ? 'Loading...' : (
                    selectedLegalEntity
                      ? `${selectedLegalEntity.name} (${selectedLegalEntity.code})`
                      : effectiveLegalEntityId
                        ? 'Entity ID: ' + effectiveLegalEntityId.substring(0, 8) + '...'
                        : 'All Entities'
                  )}
                </p>
              </div>
            </div>
            {!corporateIdFromParams && (
              <>
                <div className="w-px h-10 bg-warning-200" />
                <div className="flex items-center gap-2 px-3 py-1.5 bg-warning-100 border border-warning-300 rounded-lg dark:bg-warning-500/20">
                  <AlertTriangle className="w-4 h-4 text-warning-600 dark:text-warning-300" />
                  <span className="text-xs font-medium text-warning-700 dark:text-warning-300">Using default corporate</span>
                </div>
              </>
            )}
          </div>
        </div>

        {/* Error Display */}
        {errors.submit && (
          <div className="mb-6 p-4 bg-error-50 border border-error-200 rounded-lg flex items-center gap-3 dark:bg-error-500/10 dark:border-error-500/30">
            <AlertTriangle className="w-5 h-5 text-error-600 dark:text-error-300" />
            <span className="text-error-700 dark:text-error-300">{errors.submit}</span>
          </div>
        )}

        <div className="grid grid-cols-3 gap-8">
          {/* Left Column - Main Form */}
          <div className="col-span-2 space-y-6">
            {/* Customer Selection */}
            <div className="bg-white dark:bg-primary-900 rounded-xl border border-neutral-200 dark:border-primary-800 p-6">
              <SectionHeader
                icon={<Building2 className="w-5 h-5 text-info-600 dark:text-info-300" />}
                iconBg="bg-info-50 dark:bg-info-500/10"
                title="Customer"
              />

              <div className="relative">
                <div className="relative">
                  <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400 dark:text-neutral-500" />
                  <input
                    type="text"
                    value={customerSearch}
                    onChange={(e) => { setCustomerSearch(e.target.value); setShowCustomerDropdown(true); }}
                    onFocus={() => setShowCustomerDropdown(true)}
                    placeholder="Search customer by name or code..."
                    className={cn(
                      'w-full pl-10 pr-4 py-3 border rounded-lg text-sm transition-colors',
                      errors.customer ? 'border-error-300' : 'border-neutral-300 dark:border-primary-700',
                      'focus:outline-none focus:ring-2 focus:ring-primary-200 focus:border-primary-500'
                    )}
                  />
                </div>
                {errors.customer && <p className="mt-1 text-sm text-error-600 dark:text-error-300">{errors.customer}</p>}

                {showCustomerDropdown && customerSearch && (
                  <div className="absolute z-20 mt-1 w-full bg-white dark:bg-primary-900 border border-neutral-200 dark:border-primary-800 rounded-lg shadow-lg max-h-64 overflow-y-auto">
                    {filteredCustomers.length > 0 ? filteredCustomers.map((customer) => (
                      <button
                        key={customer.id}
                        onClick={() => handleSelectCustomer(customer)}
                        className="w-full flex items-center justify-between p-3 hover:bg-neutral-50 dark:hover:bg-primary-800/50 transition-colors border-b border-neutral-100 dark:border-primary-800/60 last:border-b-0"
                      >
                        <div className="text-left">
                          <p className="text-sm font-medium text-neutral-900 dark:text-neutral-50">{customer.name}</p>
                          <p className="text-xs text-neutral-500 dark:text-neutral-400">{customer.code}</p>
                        </div>
                        <div className="text-right">
                          <p className="text-xs text-neutral-500 dark:text-neutral-400">Credit Available</p>
                          <p className="text-sm font-semibold text-success-600 dark:text-success-300">
                            {formatCurrency(customer.creditAvailable, customer.currency)}
                          </p>
                          {customer.overdueAmount && customer.overdueAmount > 0 && (
                            <p className="text-xs text-error-500">Overdue: {formatCurrency(customer.overdueAmount, customer.currency)}</p>
                          )}
                        </div>
                      </button>
                    )) : (
                      <div className="p-4 text-center text-neutral-500 dark:text-neutral-400 text-sm">No customers found</div>
                    )}
                  </div>
                )}
              </div>

              {selectedCustomer && (
                <div className="mt-4 p-4 bg-info-50 rounded-lg border border-info-100 dark:bg-info-500/10 dark:border-info-500/30">
                  <div className="flex items-start justify-between">
                    <div>
                      <p className="font-semibold text-neutral-900 dark:text-neutral-50">{selectedCustomer.name}</p>
                      <p className="text-sm text-neutral-500 dark:text-neutral-400">{selectedCustomer.code}</p>
                      {selectedCustomer.email && <p className="text-sm text-neutral-500 dark:text-neutral-400 mt-1">{selectedCustomer.email}</p>}
                    </div>
                    <button onClick={() => { setSelectedCustomer(null); setCustomerSearch(''); setFormData(prev => ({ ...prev, customerId: '' })); }} className="p-1 hover:bg-info-100 rounded dark:hover:bg-info-500/20">
                      <X className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
                    </button>
                  </div>
                  <div className="grid grid-cols-3 gap-4 mt-4">
                    <div>
                      <p className="text-xs text-neutral-500 dark:text-neutral-400">Credit Limit</p>
                      <p className="text-sm font-semibold text-neutral-900 dark:text-neutral-50">{formatCurrency(selectedCustomer.creditLimit, selectedCustomer.currency)}</p>
                    </div>
                    <div>
                      <p className="text-xs text-neutral-500 dark:text-neutral-400">Used</p>
                      <p className="text-sm font-semibold text-warning-600 dark:text-warning-300">{formatCurrency(selectedCustomer.creditUsed, selectedCustomer.currency)}</p>
                    </div>
                    <div>
                      <p className="text-xs text-neutral-500 dark:text-neutral-400">Available</p>
                      <p className="text-sm font-semibold text-success-600 dark:text-success-300">{formatCurrency(selectedCustomer.creditAvailable, selectedCustomer.currency)}</p>
                    </div>
                  </div>
                  {selectedCustomer.assignedViban && (
                    <div className="mt-3 pt-3 border-t border-info-200 dark:border-info-500/30">
                      <p className="text-xs text-neutral-500 dark:text-neutral-400">Assigned VIBAN</p>
                      <div className="flex items-center gap-2 mt-1">
                        <code className="text-sm font-mono text-info-700 dark:text-info-300">{selectedCustomer.assignedViban}</code>
                        <button onClick={() => copyToClipboard(selectedCustomer.assignedViban!)} className="p-1 hover:bg-info-100 rounded dark:hover:bg-info-500/20">
                          <Copy className="w-3 h-3 text-info-600 dark:text-info-300" />
                        </button>
                      </div>
                    </div>
                  )}
                </div>
              )}
            </div>

            {/* Invoice Details */}
            <div className="bg-white dark:bg-primary-900 rounded-xl border border-neutral-200 dark:border-primary-800 p-6">
              <SectionHeader
                icon={<FileText className="w-5 h-5 text-cat-2" />}
                iconBg="bg-cat-2-soft dark:bg-cat-2/15"
                title="Invoice Details"
              />

              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="field-label block mb-1">Invoice Number *</label>
                  <input
                    type="text"
                    value={formData.invoiceNumber}
                    onChange={(e) => setFormData(prev => ({ ...prev, invoiceNumber: e.target.value }))}
                    className={cn('w-full px-3 py-2 border rounded-lg text-sm font-mono', errors.invoiceNumber ? 'border-error-300' : 'border-neutral-300 dark:border-primary-700', 'focus:outline-none focus:ring-2 focus:ring-primary-200')}
                  />
                </div>
                <div>
                  <label className="field-label block mb-1">Invoice Date</label>
                  <input type="date" value={formData.invoiceDate} onChange={(e) => setFormData(prev => ({ ...prev, invoiceDate: e.target.value }))} className="w-full px-3 py-2 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-primary-200" />
                </div>
                <div>
                  <label className="field-label block mb-1">Amount *</label>
                  <div className="flex">
                    <input
                      type="number"
                      value={formData.amount}
                      onChange={(e) => setFormData(prev => ({ ...prev, amount: e.target.value }))}
                      placeholder="0.00"
                      className={cn('flex-1 px-3 py-2 border rounded-l-lg text-sm', errors.amount ? 'border-error-300' : 'border-neutral-300 dark:border-primary-700', 'focus:outline-none focus:ring-2 focus:ring-primary-200')}
                      disabled={formData.useLineItems}
                    />
                    <select value={formData.currency} onChange={(e) => setFormData(prev => ({ ...prev, currency: e.target.value }))} className="px-3 py-2 border border-l-0 border-neutral-300 dark:border-primary-700 rounded-r-lg text-sm bg-neutral-50 dark:bg-primary-950">
                      {CURRENCIES.map(c => <option key={c} value={c}>{c}</option>)}
                    </select>
                  </div>
                  {formData.useLineItems && <p className="text-xs text-neutral-500 dark:text-neutral-400 mt-1">Amount calculated from line items</p>}
                  {errors.amount && <p className="mt-1 text-sm text-error-600 dark:text-error-300">{errors.amount}</p>}
                </div>
                <div>
                  <label className="field-label block mb-1">Payment Terms</label>
                  <select value={formData.paymentTerms} onChange={(e) => setFormData(prev => ({ ...prev, paymentTerms: e.target.value }))} className="w-full px-3 py-2 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm">
                    {PAYMENT_TERMS.map(term => <option key={term.value} value={term.value}>{term.label}</option>)}
                  </select>
                </div>
                <div>
                  <label className="field-label block mb-1">Due Date</label>
                  <div className="flex items-center gap-2 px-3 py-2 bg-neutral-50 dark:bg-primary-950 border border-neutral-200 dark:border-primary-800 rounded-lg">
                    <Calendar className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
                    <span className="text-sm text-neutral-900 dark:text-neutral-50">{formData.dueDate ? formatDate(formData.dueDate) : '-'}</span>
                  </div>
                </div>
                <div>
                  <label className="field-label block mb-1">Reference (PO/Contract)</label>
                  <input type="text" value={formData.reference} onChange={(e) => setFormData(prev => ({ ...prev, reference: e.target.value }))} placeholder="e.g., PO-2024-001" className="w-full px-3 py-2 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm" />
                </div>
                <div className="col-span-2">
                  <label className="field-label block mb-1">Description</label>
                  <textarea value={formData.description} onChange={(e) => setFormData(prev => ({ ...prev, description: e.target.value }))} rows={2} placeholder="Invoice description..." className="w-full px-3 py-2 border border-neutral-300 dark:border-primary-700 rounded-lg text-sm resize-none" />
                </div>
              </div>
            </div>

            {/* Collection Account & VIBAN */}
            <div className="bg-white dark:bg-primary-900 rounded-xl border border-neutral-200 dark:border-primary-800 p-6">
              <SectionHeader
                icon={<Landmark className="w-5 h-5 text-success-600 dark:text-success-300" />}
                iconBg="bg-success-50 dark:bg-success-500/10"
                title="Collection Account"
              />

              <div className="space-y-3">
                {availableAccounts.map((account) => (
                  <button
                    key={account.id}
                    onClick={() => handleSelectAccount(account)}
                    className={cn(
                      'w-full flex items-center justify-between p-4 border rounded-lg transition-all text-left',
                      selectedAccount?.id === account.id ? 'border-success-500 bg-success-50 ring-2 ring-success-200 dark:bg-success-500/10' : 'border-neutral-200 dark:border-primary-800 hover:border-success-300'
                    )}
                  >
                    <div className="flex items-center gap-3">
                      <div className={cn('w-10 h-10 rounded-full flex items-center justify-center', selectedAccount?.id === account.id ? 'bg-success-100 dark:bg-success-500/20' : 'bg-neutral-100 dark:bg-primary-800')}>
                        <CreditCard className={cn('w-5 h-5', selectedAccount?.id === account.id ? 'text-success-600 dark:text-success-300' : 'text-neutral-500 dark:text-neutral-400')} />
                      </div>
                      <div>
                        <p className="text-sm font-medium text-neutral-900 dark:text-neutral-50">{account.accountName}</p>
                        <p className="text-xs text-neutral-500 dark:text-neutral-400">{account.bankName} • {account.accountType}</p>
                      </div>
                    </div>
                    <div className="text-right">
                      <p className="text-xs text-neutral-500 dark:text-neutral-400 font-mono">{account.iban}</p>
                      <div className="flex items-center gap-2 mt-1 justify-end">
                        <span className="text-xs font-medium text-neutral-600 dark:text-neutral-300">{account.currency}</span>
                        {account.isDefault && <span className="px-2 py-0.5 bg-info-100 text-info-700 text-xs font-medium rounded dark:bg-info-500/20 dark:text-info-300">Default</span>}
                      </div>
                    </div>
                  </button>
                ))}
              </div>

              {/* VIBAN Generation */}
              <div className="mt-6 pt-6 border-t border-neutral-200 dark:border-primary-800">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-3">
                    <div className="p-2 bg-cat-1-soft rounded-lg dark:bg-cat-1/15"><QrCode className="w-5 h-5 text-cat-1" /></div>
                    <div>
                      <p className="text-sm font-medium text-neutral-900 dark:text-neutral-50">Generate VIBAN</p>
                      <p className="text-xs text-neutral-500 dark:text-neutral-400">Create unique virtual IBAN for auto-reconciliation</p>
                    </div>
                  </div>
                  {!formData.generatedViban ? (
                    <button onClick={handleGenerateViban} disabled={!selectedCustomer} className={cn('px-4 py-2 text-sm font-medium rounded-lg transition-colors', selectedCustomer ? 'bg-cat-1 text-white hover:bg-cat-1/90' : 'bg-neutral-200 text-neutral-400 dark:text-neutral-500 cursor-not-allowed dark:bg-primary-800')}>
                      Generate
                    </button>
                  ) : (
                    <span className="flex items-center gap-1 text-success-600 text-sm font-medium dark:text-success-300"><Check className="w-4 h-4" />Generated</span>
                  )}
                </div>
                {formData.generatedViban && (
                  <div className="mt-4 p-4 bg-cat-1-soft rounded-lg border border-cat-1/10 dark:bg-cat-1/15 dark:border-cat-1/30">
                    <div className="flex items-center justify-between">
                      <div>
                        <p className="text-xs text-cat-1 font-medium">Virtual IBAN</p>
                        <code className="text-sm font-mono text-cat-1">{formData.generatedViban}</code>
                      </div>
                      <button onClick={() => copyToClipboard(formData.generatedViban)} className="p-2 hover:bg-cat-1/10 rounded-lg dark:hover:bg-cat-1/25">
                        <Copy className="w-4 h-4 text-cat-1" />
                      </button>
                    </div>
                  </div>
                )}
              </div>
            </div>

            {/* Optional Tabs */}
            <div className="bg-white dark:bg-primary-900 rounded-xl border border-neutral-200 dark:border-primary-800 overflow-hidden">
              <div className="flex flex-wrap border-b border-neutral-200 dark:border-primary-800 bg-neutral-50 dark:bg-primary-950">
                {tabs.map((tab) => {
                  const Icon = tab.icon;
                  return (
                    <button
                      key={tab.id}
                      onClick={() => setActiveTab(activeTab === tab.id ? null : tab.id)}
                      className={cn(
                        'flex items-center gap-2 px-4 py-3 text-sm font-medium transition-colors border-b-2 -mb-px',
                        activeTab === tab.id
                          ? 'text-primary-600 dark:text-primary-200 border-primary-600 bg-white dark:bg-primary-900'
                          : 'text-neutral-600 dark:text-neutral-300 border-transparent hover:text-neutral-900 dark:text-neutral-50 hover:bg-white dark:bg-primary-900 dark:hover:text-neutral-50'
                      )}
                    >
                      <Icon className="w-4 h-4" />
                      {tab.label}
                      {tab.badge > 0 && (
                        <span className={cn(
                          'px-1.5 py-0.5 text-xs rounded-full',
                          activeTab === tab.id ? 'bg-primary-100 dark:bg-primary-700 text-primary-700 dark:text-neutral-200' : 'bg-neutral-200 text-neutral-600 dark:text-neutral-300 dark:bg-primary-800'
                        )}>
                          {tab.badge}
                        </span>
                      )}
                    </button>
                  );
                })}
              </div>

              <div className="p-6">
                {activeTab === 'line-items' && <LineItemsTab formData={formData} setFormData={setFormData} currency={formData.currency} />}
                {activeTab === 'tax-charges' && <TaxChargesTab formData={formData} setFormData={setFormData} currency={formData.currency} />}
                {activeTab === 'payment-link' && <PaymentLinkTab formData={formData} setFormData={setFormData} dueDate={formData.dueDate} />}
                {activeTab === 'cobo' && <CoboTab formData={formData} setFormData={setFormData} entities={entities} />}
                {activeTab === 'hierarchy' && <HierarchyTab formData={formData} setFormData={setFormData} hierarchyNodes={hierarchyNodes} />}
                {activeTab === 'documents' && <DocumentsTab formData={formData} setFormData={setFormData} />}
                {activeTab === 'reminders' && <RemindersTab formData={formData} setFormData={setFormData} />}
                {!activeTab && (
                  <div className="text-center py-8 text-neutral-500 dark:text-neutral-400">
                    <p className="text-sm">Click a tab above to configure optional settings</p>
                    <p className="text-xs mt-1">Line items, tax configuration, payment links, COBO, and more</p>
                  </div>
                )}
              </div>
            </div>
          </div>

          {/* Right Column - Summary */}
          <div className="col-span-1">
            <div className="sticky top-24 space-y-4">
              <div className="bg-white dark:bg-primary-900 rounded-xl border border-neutral-200 dark:border-primary-800 p-6">
                <h3 className="text-sm font-semibold text-neutral-900 dark:text-neutral-50 mb-4">Summary</h3>
                <div className="space-y-4">
                  <div>
                    <p className="text-xs text-neutral-500 dark:text-neutral-400">Customer</p>
                    <p className="text-sm font-medium text-neutral-900 dark:text-neutral-50">{selectedCustomer?.name || '-'}</p>
                  </div>
                  <div>
                    <p className="text-xs text-neutral-500 dark:text-neutral-400">Invoice</p>
                    <p className="text-sm font-mono text-neutral-900 dark:text-neutral-50">{formData.invoiceNumber}</p>
                  </div>
                  <div className="pt-3 border-t border-neutral-100 dark:border-primary-800/60">
                    <p className="text-xs text-neutral-500 dark:text-neutral-400">Amount</p>
                    <p className="stat-value-sm">
                      {formData.useLineItems && formData.lineItems.length > 0
                        ? formatCurrency(calculatedTotal, formData.currency)
                        : formData.amount ? formatCurrency(parseFloat(formData.amount), formData.currency) : '-'
                      }
                    </p>
                    {formData.useLineItems && <p className="text-xs text-neutral-500 dark:text-neutral-400 mt-1">{formData.lineItems.length} line item(s)</p>}
                  </div>
                  <div>
                    <p className="text-xs text-neutral-500 dark:text-neutral-400">Due Date</p>
                    <p className="text-sm font-medium text-neutral-900 dark:text-neutral-50">{formData.dueDate ? formatDate(formData.dueDate) : '-'}</p>
                  </div>
                  <div className="pt-3 border-t border-neutral-100 dark:border-primary-800/60">
                    <p className="text-xs text-neutral-500 dark:text-neutral-400">Collection Account</p>
                    <p className="text-sm font-medium text-neutral-900 dark:text-neutral-50">{selectedAccount?.accountName || '-'}</p>
                  </div>
                  {formData.generatedViban && (
                    <div>
                      <p className="text-xs text-neutral-500 dark:text-neutral-400">VIBAN</p>
                      <p className="text-xs font-mono text-cat-1">{formData.generatedViban}</p>
                    </div>
                  )}

                  {/* Features Summary */}
                  {(formData.useLineItems || formData.charges.length > 0 || formData.paymentLink.enabled || formData.coboEnabled || formData.hierarchyNodeId || formData.attachments.length > 0 || formData.enableDunning) && (
                    <div className="pt-3 border-t border-neutral-100 dark:border-primary-800/60">
                      <p className="text-xs text-neutral-500 dark:text-neutral-400 mb-2">Includes</p>
                      <div className="flex flex-wrap gap-1">
                        {formData.useLineItems && <span className="px-2 py-1 bg-info-50 text-info-700 text-xs rounded dark:bg-info-500/10 dark:text-info-300">{formData.lineItems.length} items</span>}
                        {formData.charges.length > 0 && <span className="px-2 py-1 bg-warning-50 text-warning-700 text-xs rounded dark:bg-warning-500/10 dark:text-warning-300">{formData.charges.length} charges</span>}
                        {formData.paymentLink.enabled && <span className="px-2 py-1 bg-cat-1-soft text-cat-1 text-xs rounded dark:bg-cat-1/15">Pay Link</span>}
                        {formData.coboEnabled && <span className="px-2 py-1 bg-success-50 text-success-700 text-xs rounded dark:bg-success-500/10 dark:text-success-300">COBO</span>}
                        {formData.hierarchyNodeId && <span className="px-2 py-1 bg-cat-2-soft text-cat-2 text-xs rounded dark:bg-cat-2/15">Hierarchy</span>}
                        {formData.attachments.length > 0 && <span className="px-2 py-1 bg-neutral-100 dark:bg-primary-800 text-neutral-700 dark:text-neutral-200 text-xs rounded">{formData.attachments.length} files</span>}
                        {formData.enableDunning && <span className="px-2 py-1 bg-error-50 text-error-700 text-xs rounded dark:bg-error-500/10 dark:text-error-300">Dunning</span>}
                      </div>
                    </div>
                  )}
                </div>
              </div>

              {!canSubmit && (
                <div className="bg-warning-50 border border-warning-200 rounded-xl p-4 dark:bg-warning-500/10 dark:border-warning-500/30">
                  <div className="flex items-start gap-2">
                    <AlertCircle className="w-4 h-4 text-warning-600 flex-shrink-0 mt-0.5 dark:text-warning-300" />
                    <div>
                      <p className="text-sm font-medium text-warning-800 dark:text-warning-300">Complete required fields</p>
                      <ul className="text-xs text-warning-700 mt-1 space-y-1 dark:text-warning-300">
                        {!formData.customerId && <li>• Select a customer</li>}
                        {(!formData.amount || parseFloat(formData.amount) <= 0) && !formData.useLineItems && <li>• Enter an amount</li>}
                        {formData.useLineItems && formData.lineItems.length === 0 && <li>• Add at least one line item</li>}
                        {!formData.collectionAccountId && <li>• Select collection account</li>}
                      </ul>
                    </div>
                  </div>
                </div>
              )}

              {canSubmit && (
                <div className="bg-success-50 border border-success-200 rounded-xl p-4 dark:bg-success-500/10 dark:border-success-500/30">
                  <div className="flex items-start gap-2">
                    <Check className="w-4 h-4 text-success-600 flex-shrink-0 mt-0.5 dark:text-success-300" />
                    <div>
                      <p className="text-sm font-medium text-success-800 dark:text-success-300">Ready to create</p>
                      <p className="text-xs text-success-700 mt-1 dark:text-success-300">
                        {formData.sendInvoiceEmail ? 'Invoice will be emailed to customer' : 'Invoice will be created as draft'}
                      </p>
                    </div>
                  </div>
                </div>
              )}
            </div>
          </div>
        </div>

        {/* Bottom Action Bar — inner max-w wrapper dropped; Page above
            already centers at max-w-5xl. Phase 10 Design System Unification. */}
        <div className="mt-8 border-t border-neutral-200 dark:border-primary-800 bg-white dark:bg-primary-900">
          <div className="py-4">
            <div className="flex items-center justify-between">
              <button
                onClick={goBack}
                className="px-4 py-2 text-sm font-medium text-neutral-600 dark:text-neutral-300 hover:text-neutral-900 dark:text-neutral-50 transition-colors dark:hover:text-neutral-50"
              >
                Cancel
              </button>
              <div className="flex items-center gap-3">
                <button
                  onClick={() => handleSubmit(true)}
                  disabled={loading}
                  className="px-6 py-2.5 field-label bg-white dark:bg-primary-900 border border-neutral-300 dark:border-primary-700 rounded-lg hover:bg-neutral-50 dark:hover:bg-primary-800/50 transition-colors disabled:opacity-50"
                >
                  Save Draft
                </button>
                <button
                  onClick={() => handleSubmit(false)}
                  disabled={loading || !canSubmit}
                  className={cn(
                    'px-6 py-2.5 text-sm font-medium text-white rounded-lg transition-colors flex items-center gap-2',
                    canSubmit ? 'bg-primary-600 hover:bg-primary-700' : 'bg-neutral-300 cursor-not-allowed'
                  )}
                >
                  {loading && <Loader2 className="w-4 h-4 animate-spin" />}
                  {isEditMode ? 'Update Invoice' : 'Create Invoice'}
                </button>
              </div>
            </div>
          </div>
        </div>
      </Page>
    </div>
  );
};

export default CreateReceivablePage;