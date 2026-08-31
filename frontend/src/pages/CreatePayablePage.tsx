import React, { useState, useEffect, useCallback, useMemo } from 'react';
import {
  ArrowLeft, Save, Send, Plus, Trash2, Search, Building2, FileText,
  Calculator, ArrowLeftRight, GitBranch, Paperclip, CheckCircle2,
  Calendar, DollarSign, Percent, ChevronDown, ChevronUp, AlertCircle,
  Loader2, X, Check, Info, CreditCard, Landmark, Clock, RefreshCw,
  Upload, Link as LinkIcon, StickyNote, Users, ToggleLeft, ToggleRight,
  HelpCircle, Receipt, Package, Truck, Shield, BanknoteIcon, FileCheck,
  Settings2, ChevronRight, Eye, ChevronsUpDown, Building, AlertTriangle
} from 'lucide-react';
// Tier 5 Design System Unification (2026-05-13): switched from page-local
// Card/Button/Badge definitions to the shared components/ui versions.
// Page-local re-definition of system primitives is no longer permitted —
// it strands pages outside every future token / focus-ring / dark-mode
// migration. Local Input/Select/Toggle kept for now (non-trivial API
// differences from the shared versions; deferred for a follow-up).
import { Card, Button, Badge } from '../components/ui';
import { Page } from '../components/layout/Page';
import { payablesApiPhase2, legalEntityApi, partiesApi, corporatesApi, virtualAccountsApi } from '../services/api';
import { useNavigation } from '../App';
import { formatCurrency } from '../utils';

// ============================================================================
// TYPES
// ============================================================================

interface Vendor {
  id: string;
  name: string;
  legalName?: string;
  partyCode: string;
  taxRegistration?: string;
  category?: string;
  status: string;
  bankAccounts: BankAccount[];
  address?: string;
  email?: string;
  phone?: string;
  defaultPaymentTerms?: string;
  creditLimit?: number;
  outstandingBalance?: number;
  // POBO eligibility fields
  acceptsPobo?: boolean;
  poboMinAmount?: number;
  poboMaxAmount?: number;
  poboCurrencies?: string[];
  isIntercompany?: boolean;
}

// POBO eligibility configuration
interface PoboEligibility {
  eligible: boolean;
  reasons: string[];
}

const POBO_CONFIG = {
  minAmount: 1000, // Minimum amount for POBO
  maxAmount: 10000000, // Maximum amount for POBO
  supportedCurrencies: ['AED', 'USD', 'EUR', 'GBP'], // Currencies that support POBO
  supportedChannels: ['DIRECT', 'SCHEDULED'], // Payment channels that support POBO
};

interface BankAccount {
  id: string;
  accountNumber: string;
  accountName?: string;
  bankName: string;
  bankCode?: string;
  swift?: string;
  iban?: string;
  routingNumber?: string;
  currencyCode: string;
  isPrimary: boolean;
  paymentMethods: string[];
}

interface Invoice {
  id: string;
  invoiceNumber: string;
  amount: number;
  currencyCode: string;
  invoiceDate: string;
  dueDate: string;
  status: string;
  lineItems?: InvoiceLineItem[];
}

interface InvoiceLineItem {
  description: string;
  quantity: number;
  unitPrice: number;
  amount: number;
  taxCode?: string;
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
  taxPercent: number;
  lineTotal: number;
}

interface AdditionalCharge {
  id: string;
  type: string;
  description: string;
  calculationType: 'FIXED' | 'PERCENT';
  value: number;
  amount: number;
}

interface PayableFormData {
  vendorId: string;
  vendorName: string;
  vendorCode: string;
  vendorTrn: string;
  selectedBankAccountId: string;
  sourceVirtualAccountId: string; // VA to pay from
  invoiceNumber: string;
  invoiceDate: string;
  receivedDate: string;
  dueDate: string;
  paymentTerms: string;
  currencyCode: string;
  amount: number;
  description: string;
  useLineItems: boolean;
  lineItems: LineItem[];
  taxJurisdiction: string;
  applyVat: boolean;
  vatRate: number;
  reverseCharge: boolean;
  taxExempt: boolean;
  taxExemptReason: string;
  withholdingTax: boolean;
  withholdingRate: number;
  additionalCharges: AdditionalCharge[];
  earlyPaymentDays: number;
  earlyPaymentDiscount: number;
  poboEnabled: boolean;
  payingEntityId: string;
  payingEntityName: string;
  onBehalfOfEntityId: string;
  onBehalfOfEntityName: string;
  icTreatment: string;
  rechargeMarkup: number;
  hierarchyNodeId: string;
  hierarchyNodeName: string;
  hierarchyPath: string;
  splitAllocation: boolean;
  costAllocations: any[];
  uploadedFiles: any[];
  linkedPurchaseOrder: string;
  linkedContract: string;
  linkedGrn: string;
  internalNotes: string;
  scheduleType: string;
  scheduledDate: string;
  paymentPriority: string;
  paymentChannel: string;
  notifyVendor: boolean;
}

type TabId = 'tax' | 'pobo' | 'hierarchy' | 'documents' | 'scheduling';

// ============================================================================
// CONSTANTS
// ============================================================================

const PAYMENT_TERMS = [
  { value: 'IMMEDIATE', label: 'Due on Receipt', days: 0 },
  { value: 'NET15', label: 'Net 15 Days', days: 15 },
  { value: 'NET30', label: 'Net 30 Days', days: 30 },
  { value: 'NET45', label: 'Net 45 Days', days: 45 },
  { value: 'NET60', label: 'Net 60 Days', days: 60 },
  { value: 'NET90', label: 'Net 90 Days', days: 90 },
  { value: 'EOM', label: 'End of Month', days: 0 },
];

const CURRENCIES = ['AED', 'USD', 'EUR', 'GBP', 'SAR', 'INR'];

// PaymentChannel enum values from backend: DIRECT, BATCH, SCHEDULED, IMMEDIATE
const PAYMENT_CHANNELS = [
  { value: 'DIRECT', label: 'Direct Payment', description: 'Single immediate payment', icon: Landmark },
  { value: 'SCHEDULED', label: 'Scheduled Payment', description: 'Schedule for future date', icon: Clock },
  { value: 'BATCH', label: 'Batch Payment', description: 'Include in payment batch', icon: ArrowLeftRight },
  { value: 'IMMEDIATE', label: 'Immediate/Express', description: 'Priority same-day processing', icon: RefreshCw },
];

const CHARGE_TYPES = [
  { value: 'SHIPPING', label: 'Shipping/Freight', icon: Truck },
  { value: 'HANDLING', label: 'Handling Fee', icon: Package },
  { value: 'INSURANCE', label: 'Insurance', icon: Shield },
  { value: 'BANK', label: 'Bank Charges', icon: Landmark },
  { value: 'CUSTOM', label: 'Custom Charge', icon: Receipt },
];

const IC_TREATMENTS = [
  { value: 'IC_RECEIVABLE', label: 'Create intercompany receivable', recommended: true, description: 'Auto-creates IC receivable for settlement' },
  { value: 'DIRECT_CHARGE', label: 'Direct charge to subsidiary', description: 'Charge directly to cost center' },
  { value: 'NETTING', label: 'Settlement via netting cycle', description: 'Include in next netting run' },
];

const TAB_CONFIG: { id: TabId; label: string; icon: React.ElementType; description: string }[] = [
  { id: 'tax', label: 'Tax & Charges', icon: Calculator, description: 'VAT, withholding, additional fees' },
  { id: 'pobo', label: 'POBO', icon: ArrowLeftRight, description: 'Pay on behalf of subsidiary' },
  { id: 'hierarchy', label: 'Allocation', icon: GitBranch, description: 'Cost center assignment' },
  { id: 'documents', label: 'Documents', icon: Paperclip, description: 'Attachments & references' },
  { id: 'scheduling', label: 'Scheduling', icon: Calendar, description: 'Payment timing & priority' },
];

// ============================================================================
// UTILITY FUNCTIONS
// ============================================================================
// formatCurrency now comes from the shared util (Phase 12 Task D3) — the local
// en-AE Intl clone (symbol-style, 2 dp) was deleted in favour of the shared
// currency-aware "AED 1,234.56" format.

const formatDate = (dateStr: string): string => {
  if (!dateStr) return '';
  return new Date(dateStr).toLocaleDateString('en-GB', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
  });
};

const generateId = () => Math.random().toString(36).substr(2, 9);

const calculateDueDate = (invoiceDate: string, terms: string): string => {
  if (!invoiceDate) return '';
  const date = new Date(invoiceDate);
  const termConfig = PAYMENT_TERMS.find(t => t.value === terms);
  
  if (terms === 'EOM') {
    date.setMonth(date.getMonth() + 1);
    date.setDate(0);
  } else if (termConfig) {
    date.setDate(date.getDate() + termConfig.days);
  }
  
  return date.toISOString().split('T')[0];
};

// ============================================================================
// PAGE-LOCAL FORM COMPONENTS
// ============================================================================
// Note: Card / Button / Badge moved to shared `components/ui` (Tier 5
// Design System Unification, 2026-05-13). The page-local Input / Select /
// Toggle below remain — their APIs differ enough from the shared versions
// (`prefix`/`suffix` vs `leftIcon`/`rightIcon`, value-callback shapes) that
// migrating them is its own follow-up.

const Input: React.FC<{
  label?: string;
  value: string | number;
  onChange: (value: string) => void;
  type?: string;
  placeholder?: string;
  required?: boolean;
  disabled?: boolean;
  prefix?: React.ReactNode;
  suffix?: React.ReactNode;
  error?: string;
  helpText?: string;
  className?: string;
  size?: 'sm' | 'md' | 'lg';
}> = ({ label, value, onChange, type = 'text', placeholder, required, disabled, prefix, suffix, error, helpText, className = '', size = 'md' }) => {
  const sizes = { sm: 'px-2.5 py-1.5 text-sm', md: 'px-3 py-2 text-sm', lg: 'px-4 py-3 text-base' };
  
  return (
    <div className={className}>
      {label && <label className="field-label block mb-1.5">{label} {required && <span className="text-error-500">*</span>}</label>}
      <div className="relative">
        {prefix && <span className="absolute left-3 top-1/2 -translate-y-1/2 text-neutral-500 dark:text-neutral-400">{prefix}</span>}
        <input
          type={type}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder={placeholder}
          disabled={disabled}
          className={`w-full border rounded-lg transition-colors ${sizes[size]} ${prefix ? 'pl-10' : ''} ${suffix ? 'pr-10' : ''} ${error ? 'border-error-300' : 'border-neutral-300 dark:border-primary-700'} ${disabled ? 'bg-neutral-100 dark:bg-primary-800 cursor-not-allowed' : 'bg-white dark:bg-primary-900'} focus:outline-none focus:ring-2 focus:ring-primary-200 focus:border-primary-500`}
        />
        {suffix && <span className="absolute right-3 top-1/2 -translate-y-1/2 text-neutral-500 dark:text-neutral-400">{suffix}</span>}
      </div>
      {error && <p className="mt-1 text-xs text-error-500">{error}</p>}
      {helpText && !error && <p className="mt-1 text-xs text-neutral-500 dark:text-neutral-400">{helpText}</p>}
    </div>
  );
};

const Select: React.FC<{
  label?: string;
  value: string;
  onChange: (value: string) => void;
  options: { value: string; label: string }[];
  required?: boolean;
  disabled?: boolean;
  placeholder?: string;
  className?: string;
}> = ({ label, value, onChange, options, required, disabled, placeholder, className = '' }) => (
  <div className={className}>
    {label && <label className="field-label block mb-1.5">{label} {required && <span className="text-error-500">*</span>}</label>}
    <select
      value={value}
      onChange={(e) => onChange(e.target.value)}
      disabled={disabled}
      className={`w-full border border-neutral-300 dark:border-primary-700 rounded-lg px-3 py-2 text-sm bg-white dark:bg-primary-900 focus:border-primary-500 focus:ring-2 focus:ring-primary-200 focus:outline-none ${disabled ? 'bg-neutral-100 dark:bg-primary-800 cursor-not-allowed' : ''}`}
    >
      {placeholder && <option value="">{placeholder}</option>}
      {options.map((opt) => <option key={opt.value} value={opt.value}>{opt.label}</option>)}
    </select>
  </div>
);

const Toggle: React.FC<{ label: string; description?: string; checked: boolean; onChange: (checked: boolean) => void; disabled?: boolean; size?: 'sm' | 'md'; }> = ({ label, description, checked, onChange, disabled, size = 'md' }) => (
  <div className="flex items-center justify-between">
    <div>
      <p className={`font-medium text-neutral-900 dark:text-neutral-50 ${size === 'sm' ? 'text-sm' : ''}`}>{label}</p>
      {description && <p className="text-xs text-neutral-500 dark:text-neutral-400">{description}</p>}
    </div>
    <button
      type="button"
      onClick={() => !disabled && onChange(!checked)}
      disabled={disabled}
      className={`relative inline-flex items-center rounded-full transition-colors ${size === 'sm' ? 'h-5 w-9' : 'h-6 w-11'} ${checked ? 'bg-primary-600' : 'bg-neutral-300'} ${disabled ? 'opacity-50 cursor-not-allowed' : 'cursor-pointer'}`}
    >
      <span className={`inline-block transform rounded-full bg-white dark:bg-primary-900 shadow transition-transform ${size === 'sm' ? 'h-3.5 w-3.5' : 'h-4 w-4'} ${checked ? (size === 'sm' ? 'translate-x-5' : 'translate-x-6') : 'translate-x-1'}`} />
    </button>
  </div>
);

// Card / Button / Badge previously lived here as page-local definitions.
// Moved to shared `components/ui` per Tier 5 Design System Unification
// (2026-05-13). Caller migration:
//   <Button leftIcon={X}>      → <Button leftIcon={X}>
//   <Badge variant="neutral"> → <Badge variant="neutral">
// Tonal variant 'danger' on Button is still accepted by the shared API.

// ============================================================================
// VENDOR SEARCH COMPONENT
// ============================================================================

const VendorSearch: React.FC<{ selectedVendor: Vendor | null; onSelect: (vendor: Vendor) => void; onClear: () => void; corporateId: string; }> = ({ selectedVendor, onSelect, onClear, corporateId }) => {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<Vendor[]>([]);
  const [loading, setLoading] = useState(false);
  const [showDropdown, setShowDropdown] = useState(false);

  // Map API party to Vendor format
  const mapPartyToVendor = (party: any): Vendor => ({
    id: party.id,
    name: party.displayName || party.legalName || 'Unknown',
    legalName: party.legalName || party.displayName || '',
    partyCode: party.partyCode || '',
    taxRegistration: party.taxId || '',
    category: party.partyType || 'VENDOR',
    status: party.status || 'ACTIVE',
    email: party.contactEmail || '',
    phone: party.contactPhone || '',
    defaultPaymentTerms: 'NET30',
    creditLimit: 0,
    outstandingBalance: 0,
    bankAccounts: (party.bankAccounts || []).map((ba: any) => ({
      id: ba.id,
      accountNumber: ba.accountNumber || '',
      accountName: ba.accountHolderName || '',
      bankName: ba.bankName || '',
      swift: ba.swiftBic || '',
      iban: ba.iban || '',
      currencyCode: ba.currencyCode || 'AED',
      isPrimary: ba.isPrimary || false,
      paymentMethods: ['DIRECT', 'SCHEDULED', 'BATCH', 'IMMEDIATE']
    }))
  });

  const searchVendors = useCallback(async (q: string) => {
    setLoading(true);
    try {
      const response = await partiesApi.getAll({
        corporateId: corporateId,
        role: 'VENDOR',
        searchTerm: q || undefined,
        pageSize: 20
      });
      const partiesList = response?.parties || response?.data?.parties || [];

      // Fetch bank accounts for each party
      const vendorsWithAccounts: Vendor[] = [];
      for (const party of partiesList) {
        try {
          const detailRes = await partiesApi.getDetail(party.id);
          const partyWithAccounts = { ...party, bankAccounts: detailRes?.bankAccounts || detailRes?.data?.bankAccounts || [] };
          vendorsWithAccounts.push(mapPartyToVendor(partyWithAccounts));
        } catch {
          vendorsWithAccounts.push(mapPartyToVendor(party));
        }
      }
      setResults(vendorsWithAccounts);
    } catch (error) {
      console.error('Failed to search vendors:', error);
      setResults([]);
    }
    setLoading(false);
  }, [corporateId]);

  useEffect(() => { const timer = setTimeout(() => searchVendors(query), 300); return () => clearTimeout(timer); }, [query, searchVendors]);
  useEffect(() => { if (showDropdown && !query) searchVendors(''); }, [showDropdown, query, searchVendors]);
  
  if (selectedVendor) {
    return (
      <div className="border-2 border-primary-200 bg-gradient-to-r from-primary-50 to-white rounded-xl p-5 dark:border-primary-700 dark:from-primary-500/10 dark:to-primary-900 dark:from-primary-800/40">
        <div className="flex items-start justify-between">
          <div className="flex items-start gap-4">
            <div className="w-12 h-12 bg-primary-100 dark:bg-primary-700 rounded-xl flex items-center justify-center">
              <Building2 className="w-6 h-6 text-primary-600 dark:text-primary-200" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h3 className="font-semibold text-lg text-neutral-900 dark:text-neutral-50">{selectedVendor.name}</h3>
                <Badge variant="success">Active</Badge>
              </div>
              <p className="text-sm text-neutral-500 dark:text-neutral-400 mt-0.5">{selectedVendor.partyCode}</p>
              {selectedVendor.taxRegistration && <p className="text-sm text-neutral-500 dark:text-neutral-400">TRN: {selectedVendor.taxRegistration}</p>}
              <div className="flex items-center gap-4 mt-2">
                {selectedVendor.category && <Badge variant="neutral">{selectedVendor.category}</Badge>}
                {selectedVendor.email && <span className="text-xs text-neutral-500 dark:text-neutral-400">{selectedVendor.email}</span>}
              </div>
            </div>
          </div>
          <button onClick={onClear} className="p-2 hover:bg-primary-100 dark:bg-primary-700 rounded-lg transition-colors dark:hover:bg-primary-700" title="Change vendor">
            <X className="w-5 h-5 text-neutral-500 dark:text-neutral-400" />
          </button>
        </div>
        {selectedVendor.outstandingBalance !== undefined && (
          <div className="mt-4 pt-4 border-t border-primary-100 flex items-center justify-between dark:border-primary-700/60">
            <span className="text-sm text-neutral-600 dark:text-neutral-300">Outstanding Balance</span>
            <span className="font-semibold text-neutral-900 dark:text-neutral-50">{formatCurrency(selectedVendor.outstandingBalance, 'AED')}</span>
          </div>
        )}
      </div>
    );
  }
  
  return (
    <div className="relative">
      <div className="relative">
        <Search className="absolute left-4 top-1/2 -translate-y-1/2 w-5 h-5 text-neutral-400 dark:text-neutral-500" />
        <input
          type="text"
          value={query}
          onChange={(e) => { setQuery(e.target.value); setShowDropdown(true); }}
          onFocus={() => setShowDropdown(true)}
          placeholder="Search vendors by name or code..."
          className="w-full pl-12 pr-4 py-4 border-2 border-neutral-200 dark:border-primary-800 rounded-xl text-base focus:border-primary-500 focus:ring-4 focus:ring-primary-100 focus:outline-none placeholder:text-neutral-400 dark:text-neutral-500 transition-all"
        />
        {loading && <Loader2 className="absolute right-4 top-1/2 -translate-y-1/2 w-5 h-5 animate-spin text-neutral-400 dark:text-neutral-500" />}
      </div>
      {showDropdown && (
        <>
          <div className="fixed inset-0 z-10" onClick={() => setShowDropdown(false)} />
          <div className="absolute z-20 mt-2 w-full bg-white dark:bg-primary-900 border border-neutral-200 dark:border-primary-800 rounded-xl shadow-xl max-h-80 overflow-y-auto">
            {results.length === 0 ? (
              <div className="p-4 text-center text-neutral-500 dark:text-neutral-400"><Building2 className="w-8 h-8 mx-auto mb-2 text-neutral-300 dark:text-neutral-600" /><p>No vendors found</p></div>
            ) : results.map((vendor) => (
              <button
                key={vendor.id}
                onClick={() => { onSelect(vendor); setShowDropdown(false); setQuery(''); }}
                className="w-full flex items-center gap-4 p-4 hover:bg-neutral-50 dark:hover:bg-primary-800/50 text-left border-b border-neutral-100 dark:border-primary-800/60 last:border-b-0 transition-colors"
              >
                <div className="w-10 h-10 bg-neutral-100 dark:bg-primary-800 rounded-lg flex items-center justify-center flex-shrink-0">
                  <Building2 className="w-5 h-5 text-neutral-500 dark:text-neutral-400" />
                </div>
                <div className="flex-1 min-w-0">
                  <p className="font-medium text-neutral-900 dark:text-neutral-50 truncate">{vendor.name}</p>
                  <p className="text-sm text-neutral-500 dark:text-neutral-400">{vendor.partyCode} • {vendor.category}</p>
                </div>
                <Badge variant={vendor.bankAccounts.length > 0 ? 'success' : 'warning'} size="sm">
                  {vendor.bankAccounts.length} bank {vendor.bankAccounts.length === 1 ? 'account' : 'accounts'}
                </Badge>
              </button>
            ))}
          </div>
        </>
      )}
    </div>
  );
};

// ============================================================================
// BANK ACCOUNT SELECTOR
// ============================================================================

const BankAccountSelector: React.FC<{
  accounts: BankAccount[];
  selectedId: string;
  paymentChannel: string;
  onSelect: (accountId: string) => void;
  onChannelChange: (channel: string) => void;
}> = ({ accounts, selectedId, paymentChannel, onSelect, onChannelChange }) => {
  const compatibleAccounts = accounts.filter(acc => !paymentChannel || acc.paymentMethods.includes(paymentChannel));
  const selectedAccount = accounts.find(acc => acc.id === selectedId);
  
  return (
    <div className="space-y-4">
      <div>
        <label className="field-label block mb-2">Payment Channel</label>
        <div className="grid grid-cols-2 gap-3">
          {PAYMENT_CHANNELS.map((channel) => {
            const Icon = channel.icon;
            const isAvailable = accounts.some(acc => acc.paymentMethods.includes(channel.value));
            const isSelected = paymentChannel === channel.value;
            return (
              <button
                key={channel.value}
                onClick={() => isAvailable && onChannelChange(channel.value)}
                disabled={!isAvailable}
                className={`flex items-center gap-3 p-3 rounded-lg border-2 transition-all text-left ${isSelected ? 'border-primary-500 bg-primary-50 dark:bg-primary-800/40' : isAvailable ? 'border-neutral-200 dark:border-primary-800 hover:border-neutral-300 dark:hover:border-primary-700 bg-white dark:bg-primary-900' : 'border-neutral-100 bg-neutral-50 dark:bg-primary-950 opacity-50 cursor-not-allowed'}`}
              >
                <Icon className={`w-5 h-5 ${isSelected ? 'text-primary-600 dark:text-primary-200' : 'text-neutral-400 dark:text-neutral-500'}`} />
                <div>
                  <p className={`font-medium text-sm ${isSelected ? 'text-primary-700 dark:text-neutral-200' : 'text-neutral-700 dark:text-neutral-200'}`}>{channel.label}</p>
                  <p className="text-xs text-neutral-500 dark:text-neutral-400">{channel.description}</p>
                </div>
              </button>
            );
          })}
        </div>
      </div>
      
      {paymentChannel && (
        <div>
          <label className="field-label block mb-2">
            Bank Account {compatibleAccounts.length === 0 && <span className="text-warning-600 ml-2 font-normal dark:text-warning-300">(No accounts support {paymentChannel})</span>}
          </label>
          <div className="space-y-2">
            {compatibleAccounts.map((account) => (
              <button
                key={account.id}
                onClick={() => onSelect(account.id)}
                className={`w-full flex items-center gap-4 p-4 rounded-lg border-2 transition-all text-left ${selectedId === account.id ? 'border-primary-500 bg-primary-50 dark:bg-primary-800/40' : 'border-neutral-200 dark:border-primary-800 hover:border-neutral-300 dark:hover:border-primary-700 bg-white dark:bg-primary-900'} dark:bg-primary-800/40 dark:bg-primary-900`}
              >
                <div className={`w-10 h-10 rounded-lg flex items-center justify-center ${selectedId === account.id ? 'bg-primary-100 dark:bg-primary-700' : 'bg-neutral-100 dark:bg-primary-800'}`}>
                  <Landmark className={`w-5 h-5 ${selectedId === account.id ? 'text-primary-600 dark:text-primary-200' : 'text-neutral-500 dark:text-neutral-400'}`} />
                </div>
                <div className="flex-1">
                  <div className="flex items-center gap-2">
                    <p className="font-medium text-neutral-900 dark:text-neutral-50">{account.bankName}</p>
                    {account.isPrimary && <Badge variant="primary" size="sm">Primary</Badge>}
                    <Badge variant="neutral" size="sm">{account.currencyCode}</Badge>
                  </div>
                  <p className="text-sm text-neutral-500 dark:text-neutral-400 font-mono">{account.iban || account.accountNumber}</p>
                  {account.swift && <p className="text-xs text-neutral-400 dark:text-neutral-500">SWIFT: {account.swift}</p>}
                </div>
                {selectedId === account.id && <Check className="w-5 h-5 text-primary-600 dark:text-primary-200" />}
              </button>
            ))}
          </div>
        </div>
      )}
      
      {selectedAccount && (
        <div className="bg-success-50 border border-success-200 rounded-lg p-3 flex items-center gap-3 dark:bg-success-500/10 dark:border-success-500/30">
          <CheckCircle2 className="w-5 h-5 text-success-600 flex-shrink-0 dark:text-success-300" />
          <div className="text-sm">
            <span className="text-success-700 dark:text-success-300">Payment will be sent via </span>
            <span className="font-medium text-success-800 dark:text-success-300">{paymentChannel}</span>
            <span className="text-success-700 dark:text-success-300"> to </span>
            <span className="font-medium text-success-800 dark:text-success-300">{selectedAccount.bankName}</span>
            <span className="text-success-700 dark:text-success-300"> ({selectedAccount.currencyCode})</span>
          </div>
        </div>
      )}
    </div>
  );
};

// ============================================================================
// INVOICE LOOKUP
// ============================================================================

const InvoiceLookup: React.FC<{
  vendorId: string;
  invoiceNumber: string;
  onInvoiceSelect: (invoice: Invoice | null) => void;
  onManualEntry: (invoiceNumber: string) => void;
}> = ({ vendorId, invoiceNumber, onInvoiceSelect, onManualEntry }) => {
  const [showSuggestions, setShowSuggestions] = useState(false);
  const [matchedInvoice, setMatchedInvoice] = useState<Invoice | null>(null);
  const [vendorInvoices, setVendorInvoices] = useState<Invoice[]>([]);

  // Load invoices for vendor from API (placeholder - implement when receivables API is available)
  useEffect(() => {
    const loadVendorInvoices = async () => {
      if (!vendorId) {
        setVendorInvoices([]);
        return;
      }
      // TODO: Call receivables API to get pending invoices for this vendor
      // For now, invoice lookup is manual entry only
      setVendorInvoices([]);
    };
    loadVendorInvoices();
  }, [vendorId]);

  useEffect(() => {
    if (invoiceNumber && vendorInvoices.length > 0) {
      const match = vendorInvoices.find(inv => inv.invoiceNumber.toLowerCase() === invoiceNumber.toLowerCase());
      setMatchedInvoice(match || null);
      if (match) onInvoiceSelect(match);
    } else {
      setMatchedInvoice(null);
    }
  }, [invoiceNumber, vendorInvoices, onInvoiceSelect]);

  const filteredInvoices = vendorInvoices.filter(inv => inv.invoiceNumber.toLowerCase().includes(invoiceNumber.toLowerCase()));
  
  return (
    <div className="relative">
      <label className="field-label block mb-1.5">Invoice / Bill Number <span className="text-error-500">*</span></label>
      <div className="relative">
        <FileText className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-neutral-400 dark:text-neutral-500" />
        <input
          type="text"
          value={invoiceNumber}
          onChange={(e) => { onManualEntry(e.target.value); setShowSuggestions(true); }}
          onFocus={() => setShowSuggestions(true)}
          placeholder="Enter or select invoice number..."
          className="w-full pl-10 pr-4 py-2.5 border border-neutral-300 dark:border-primary-700 rounded-lg focus:border-primary-500 focus:ring-2 focus:ring-primary-200 focus:outline-none"
        />
        {matchedInvoice && <CheckCircle2 className="absolute right-3 top-1/2 -translate-y-1/2 w-5 h-5 text-success-500" />}
      </div>
      
      {showSuggestions && filteredInvoices.length > 0 && !matchedInvoice && (
        <>
          <div className="fixed inset-0 z-10" onClick={() => setShowSuggestions(false)} />
          <div className="absolute z-20 mt-1 w-full bg-white dark:bg-primary-900 border border-neutral-200 dark:border-primary-800 rounded-lg shadow-lg max-h-48 overflow-y-auto">
            <div className="p-2 border-b border-neutral-100 dark:border-primary-800/60 bg-neutral-50 dark:bg-primary-950">
              <p className="text-xs text-neutral-500 dark:text-neutral-400 font-medium">Pending invoices from this vendor</p>
            </div>
            {filteredInvoices.map((inv) => (
              <button
                key={inv.id}
                onClick={() => { onManualEntry(inv.invoiceNumber); onInvoiceSelect(inv); setShowSuggestions(false); }}
                className="w-full flex items-center justify-between p-3 hover:bg-neutral-50 dark:hover:bg-primary-800/50 text-left border-b border-neutral-100 dark:border-primary-800/60 last:border-b-0"
              >
                <div>
                  <p className="font-medium text-neutral-900 dark:text-neutral-50 font-mono">{inv.invoiceNumber}</p>
                  <p className="text-xs text-neutral-500 dark:text-neutral-400">Due: {formatDate(inv.dueDate)}</p>
                </div>
                <div className="text-right">
                  <p className="font-semibold text-neutral-900 dark:text-neutral-50">{formatCurrency(inv.amount, inv.currencyCode)}</p>
                  <Badge variant="warning" size="sm">{inv.status}</Badge>
                </div>
              </button>
            ))}
          </div>
        </>
      )}
      
      {matchedInvoice && (
        <div className="mt-3 bg-success-50 border border-success-200 rounded-lg p-4 dark:bg-success-500/10 dark:border-success-500/30">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2"><FileCheck className="w-5 h-5 text-success-600 dark:text-success-300" /><span className="font-medium text-success-800 dark:text-success-300">Invoice Found</span></div>
            <Badge variant="success">Auto-populated</Badge>
          </div>
          <div className="mt-3 grid grid-cols-3 gap-4">
            <div><p className="text-xs text-success-600 dark:text-success-300">Amount</p><p className="font-semibold text-success-900">{formatCurrency(matchedInvoice.amount, matchedInvoice.currencyCode)}</p></div>
            <div><p className="text-xs text-success-600 dark:text-success-300">Invoice Date</p><p className="font-medium text-success-900">{formatDate(matchedInvoice.invoiceDate)}</p></div>
            <div><p className="text-xs text-success-600 dark:text-success-300">Due Date</p><p className="font-medium text-success-900">{formatDate(matchedInvoice.dueDate)}</p></div>
          </div>
        </div>
      )}
    </div>
  );
};

// ============================================================================
// TAB COMPONENTS (Simplified versions)
// ============================================================================

const TaxChargesTab: React.FC<{ formData: PayableFormData; updateField: (field: keyof PayableFormData, value: any) => void; subtotal: number; }> = ({ formData, updateField, subtotal }) => {
  const addCharge = (type: string) => {
    const charge: AdditionalCharge = { id: generateId(), type, description: CHARGE_TYPES.find(c => c.value === type)?.label || type, calculationType: 'FIXED', value: 0, amount: 0 };
    updateField('additionalCharges', [...formData.additionalCharges, charge]);
  };
  
  const removeCharge = (index: number) => updateField('additionalCharges', formData.additionalCharges.filter((_, i) => i !== index));
  
  return (
    <div className="space-y-6">
      <div>
        <h4 className="text-sm font-semibold text-neutral-900 dark:text-neutral-50 mb-4 flex items-center gap-2"><Calculator className="w-4 h-4" />Tax Configuration</h4>
        <div className="grid grid-cols-2 gap-4">
          <Select label="Tax Jurisdiction" value={formData.taxJurisdiction} onChange={(v) => updateField('taxJurisdiction', v)} options={[{ value: 'UAE', label: 'UAE - VAT 5%' }, { value: 'KSA', label: 'KSA - VAT 15%' }]} />
          <div className="space-y-3">
            <Toggle label="Apply VAT" checked={formData.applyVat} onChange={(v) => updateField('applyVat', v)} size="sm" />
            <Toggle label="Reverse Charge" description="Buyer accounts for VAT" checked={formData.reverseCharge} onChange={(v) => updateField('reverseCharge', v)} size="sm" />
          </div>
        </div>
      </div>
      <div className="border-t border-neutral-200 dark:border-primary-800 pt-6">
        <h4 className="text-sm font-semibold text-neutral-900 dark:text-neutral-50 mb-4">Additional Charges</h4>
        {formData.additionalCharges.map((charge, index) => (
          <div key={charge.id} className="flex items-center gap-3 mb-3 p-3 bg-neutral-50 dark:bg-primary-950 rounded-lg">
            <span className="flex-1 text-sm font-medium">{charge.description}</span>
            <span className="font-semibold">{formatCurrency(charge.amount, formData.currencyCode)}</span>
            <button onClick={() => removeCharge(index)} className="p-1 hover:bg-error-100 rounded text-error-500 dark:hover:bg-error-500/20"><X className="w-4 h-4" /></button>
          </div>
        ))}
        <div className="flex flex-wrap gap-2">
          {CHARGE_TYPES.map((type) => <Button key={type.value} variant="ghost" size="sm" onClick={() => addCharge(type.value)} leftIcon={<type.icon className="w-3 h-3" />}>{type.label}</Button>)}
        </div>
      </div>
    </div>
  );
};

interface PoboTabProps {
  formData: PayableFormData;
  updateField: (field: keyof PayableFormData, value: any) => void;
  legalEntities: Array<{ id: string; entityName: string; entityCode: string; entityType: string; }>;
  poboEligibility: PoboEligibility;
}

const PoboTab: React.FC<PoboTabProps> = ({ formData, updateField, legalEntities, poboEligibility }) => {
  // Map legal entities to the format expected by the UI
  const entities = legalEntities.map(le => ({
    id: le.id,
    name: le.entityName,
    code: le.entityCode,
    type: le.entityType === 'HEADQUARTERS' || le.entityType === 'HEAD_OFFICE' ? 'HEADQUARTERS' : 'SUBSIDIARY',
  }));

  // If not eligible, show why
  if (!poboEligibility.eligible) {
    return (
      <div className="space-y-4">
        <div className="border-2 border-neutral-200 dark:border-primary-800 rounded-xl p-5 bg-neutral-50 dark:bg-primary-950">
          <div className="flex items-center gap-4">
            <div className="p-3 rounded-xl bg-neutral-100 dark:bg-primary-800">
              <ArrowLeftRight className="w-6 h-6 text-neutral-400 dark:text-neutral-500" />
            </div>
            <div className="flex-1">
              <h4 className="font-semibold text-neutral-500 dark:text-neutral-400">Payment On Behalf Of (POBO)</h4>
              <p className="text-sm text-neutral-400 dark:text-neutral-500">Not available for this payment</p>
            </div>
            <div className="p-2 bg-warning-100 rounded-lg dark:bg-warning-500/20">
              <AlertTriangle className="w-5 h-5 text-warning-600 dark:text-warning-300" />
            </div>
          </div>
        </div>
        <div className="bg-warning-50 border border-warning-200 rounded-lg p-4 dark:bg-warning-500/10 dark:border-warning-500/30">
          <div className="flex items-start gap-3">
            <Info className="w-5 h-5 text-warning-600 mt-0.5 flex-shrink-0 dark:text-warning-300" />
            <div>
              <p className="text-sm font-medium text-warning-800 dark:text-warning-300">POBO is not available because:</p>
              <ul className="mt-2 space-y-1">
                {poboEligibility.reasons.map((reason, idx) => (
                  <li key={idx} className="text-sm text-warning-700 flex items-center gap-2 dark:text-warning-300">
                    <span className="w-1.5 h-1.5 rounded-full bg-warning-500" />
                    {reason}
                  </li>
                ))}
              </ul>
            </div>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className={`border-2 rounded-xl p-5 transition-all ${formData.poboEnabled ? 'border-accent-500 bg-accent-50/30' : 'border-neutral-200 dark:border-primary-800 hover:border-neutral-300 dark:hover:border-primary-700'} dark:hover:border-primary-700`}>
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-4">
            <div className={`p-3 rounded-xl ${formData.poboEnabled ? 'bg-accent-100 dark:bg-accent-500/20' : 'bg-neutral-100 dark:bg-primary-800'}`}>
              <ArrowLeftRight className={`w-6 h-6 ${formData.poboEnabled ? 'text-accent-600 dark:text-accent-300' : 'text-neutral-500 dark:text-neutral-400'}`} />
            </div>
            <div>
              <h4 className="font-semibold text-neutral-900 dark:text-neutral-50">Payment On Behalf Of (POBO)</h4>
              <p className="text-sm text-neutral-500 dark:text-neutral-400">Central treasury pays on behalf of subsidiaries</p>
            </div>
            {poboEligibility.eligible && !formData.poboEnabled && (
              <span className="px-2 py-1 bg-success-100 text-success-700 text-xs font-medium rounded-full dark:bg-success-500/20 dark:text-success-300">
                Available
              </span>
            )}
          </div>
          <button onClick={() => updateField('poboEnabled', !formData.poboEnabled)} className="transition-transform hover:scale-105">
            {formData.poboEnabled ? <ToggleRight className="w-12 h-12 text-accent-600 dark:text-accent-300" /> : <ToggleLeft className="w-12 h-12 text-neutral-400 dark:text-neutral-500" />}
          </button>
        </div>
      </div>
      {formData.poboEnabled && (
        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className="field-label block mb-2">Paying Entity (Treasury)</label>
            {entities.filter(e => e.type === 'HEADQUARTERS').map((entity) => (
              <button key={entity.id} onClick={() => { updateField('payingEntityId', entity.id); updateField('payingEntityName', entity.name); }}
                className={`w-full flex items-center gap-3 p-3 rounded-lg border-2 transition-all text-left mb-2 ${formData.payingEntityId === entity.id ? 'border-primary-500 bg-primary-50 dark:bg-primary-800/40' : 'border-neutral-200 dark:border-primary-800 hover:border-neutral-300 dark:hover:border-primary-700'} dark:bg-primary-800/40 dark:hover:border-primary-700`}>
                <Building2 className="w-5 h-5 text-neutral-500 dark:text-neutral-400" />
                <div><p className="font-medium text-neutral-900 dark:text-neutral-50">{entity.name}</p><p className="text-xs text-neutral-500 dark:text-neutral-400">{entity.code}</p></div>
              </button>
            ))}
          </div>
          <div>
            <label className="field-label block mb-2">On Behalf Of (Subsidiary)</label>
            {entities.filter(e => e.type === 'SUBSIDIARY').map((entity) => (
              <button key={entity.id} onClick={() => { updateField('onBehalfOfEntityId', entity.id); updateField('onBehalfOfEntityName', entity.name); }}
                className={`w-full flex items-center gap-3 p-3 rounded-lg border-2 transition-all text-left mb-2 ${formData.onBehalfOfEntityId === entity.id ? 'border-primary-500 bg-primary-50 dark:bg-primary-800/40' : 'border-neutral-200 dark:border-primary-800 hover:border-neutral-300 dark:hover:border-primary-700'} dark:bg-primary-800/40 dark:hover:border-primary-700`}>
                <Building2 className="w-5 h-5 text-neutral-500 dark:text-neutral-400" />
                <div><p className="font-medium text-neutral-900 dark:text-neutral-50">{entity.name}</p><p className="text-xs text-neutral-500 dark:text-neutral-400">{entity.code}</p></div>
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
};

const HierarchyTab: React.FC<{ formData: PayableFormData; updateField: (field: keyof PayableFormData, value: any) => void; }> = ({ formData, updateField }) => {
  const hierarchy = [
    { id: 'h1', name: 'Middle East & Africa', code: 'MEA', level: 1, type: 'REGION' },
    { id: 'h2', name: 'United Arab Emirates', code: 'UAE', level: 2, type: 'COUNTRY' },
    { id: 'h3', name: 'Manufacturing Division', code: 'MFG', level: 3, type: 'DIVISION' },
  ];
  
  return (
    <div className="space-y-4">
      <label className="field-label block mb-2">Allocate to Entity / Cost Center</label>
      <div className="border border-neutral-200 dark:border-primary-800 rounded-lg max-h-64 overflow-y-auto">
        {hierarchy.map((node) => (
          <button key={node.id} onClick={() => { updateField('hierarchyNodeId', node.id); updateField('hierarchyNodeName', node.name); updateField('hierarchyPath', `MEA > UAE > ${node.name}`); }}
            className={`w-full flex items-center gap-2 p-3 text-left border-b border-neutral-100 dark:border-primary-800/60 last:border-b-0 transition-colors ${formData.hierarchyNodeId === node.id ? 'bg-primary-50 dark:bg-primary-800/40' : 'hover:bg-neutral-50 dark:hover:bg-primary-800/50'}`}
            style={{ paddingLeft: `${node.level * 16 + 12}px` }}>
            <GitBranch className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
            <span className="text-xs text-neutral-400 dark:text-neutral-500 w-12">{node.code}</span>
            <span className="text-sm text-neutral-900 dark:text-neutral-50 flex-1">{node.name}</span>
            <Badge variant="neutral" size="sm">{node.type}</Badge>
            {formData.hierarchyNodeId === node.id && <Check className="w-4 h-4 text-primary-600 dark:text-primary-200" />}
          </button>
        ))}
      </div>
    </div>
  );
};

const DocumentsTab: React.FC<{ formData: PayableFormData; updateField: (field: keyof PayableFormData, value: any) => void; }> = ({ formData, updateField }) => (
  <div className="space-y-6">
    <div>
      <label className="field-label block mb-2">Attachments</label>
      <div className="border-2 border-dashed border-neutral-300 dark:border-primary-700 rounded-xl p-8 text-center hover:border-primary-400 transition-colors cursor-pointer">
        <Upload className="w-10 h-10 text-neutral-400 dark:text-neutral-500 mx-auto mb-3" />
        <p className="text-neutral-600 dark:text-neutral-300 font-medium">Drop files here or click to upload</p>
        <p className="text-sm text-neutral-400 dark:text-neutral-500 mt-1">PDF, PNG, JPG, XLSX, DOCX (Max 10MB each)</p>
      </div>
    </div>
    <div>
      <h4 className="text-sm font-semibold text-neutral-900 dark:text-neutral-50 mb-3">Reference Links</h4>
      <div className="grid grid-cols-3 gap-4">
        <Input label="Purchase Order" value={formData.linkedPurchaseOrder} onChange={(v) => updateField('linkedPurchaseOrder', v)} placeholder="PO-2024-XXXXX" prefix={<LinkIcon className="w-4 h-4" />} />
        <Input label="Contract" value={formData.linkedContract} onChange={(v) => updateField('linkedContract', v)} placeholder="CON-2024-XXXXX" prefix={<LinkIcon className="w-4 h-4" />} />
        <Input label="GRN / Receipt" value={formData.linkedGrn} onChange={(v) => updateField('linkedGrn', v)} placeholder="GRN-2024-XXXXX" prefix={<LinkIcon className="w-4 h-4" />} />
      </div>
    </div>
    <div>
      <label className="field-label block mb-2">Internal Notes</label>
      <textarea value={formData.internalNotes} onChange={(e) => updateField('internalNotes', e.target.value)} placeholder="Add internal notes..." rows={4} className="w-full border border-neutral-300 dark:border-primary-700 rounded-lg px-3 py-2 text-sm focus:border-primary-500 focus:ring-2 focus:ring-primary-200 focus:outline-none" />
    </div>
  </div>
);

const SchedulingTab: React.FC<{ formData: PayableFormData; updateField: (field: keyof PayableFormData, value: any) => void; }> = ({ formData, updateField }) => (
  <div className="space-y-6">
    <div>
      <label className="field-label block mb-3">Payment Schedule</label>
      <div className="space-y-2">
        {[
          { value: 'DUE_DATE', label: 'Pay on Due Date', description: `Scheduled for ${formData.dueDate || 'due date'}`, icon: Calendar },
          { value: 'IMMEDIATE', label: 'Pay Immediately', description: 'Process after approval', icon: Clock },
          { value: 'HOLD', label: 'Hold for Manual Release', description: 'Requires manual trigger', icon: Settings2 },
        ].map((opt) => {
          const Icon = opt.icon;
          return (
            <label key={opt.value} className={`flex items-center gap-4 p-4 rounded-lg border-2 cursor-pointer transition-all ${formData.scheduleType === opt.value ? 'border-primary-500 bg-primary-50 dark:bg-primary-800/40' : 'border-neutral-200 dark:border-primary-800 hover:border-neutral-300 dark:hover:border-primary-700'} dark:bg-primary-800/40 dark:hover:border-primary-700`}>
              <input type="radio" name="scheduleType" value={opt.value} checked={formData.scheduleType === opt.value} onChange={(e) => updateField('scheduleType', e.target.value)} className="w-4 h-4 text-primary-600 dark:text-primary-200" />
              <Icon className="w-5 h-5 text-neutral-500 dark:text-neutral-400" />
              <div className="flex-1"><p className="font-medium text-neutral-900 dark:text-neutral-50">{opt.label}</p><p className="text-sm text-neutral-500 dark:text-neutral-400">{opt.description}</p></div>
            </label>
          );
        })}
      </div>
    </div>
    <div className="grid grid-cols-2 gap-4">
      <Select label="Payment Priority" value={formData.paymentPriority} onChange={(v) => updateField('paymentPriority', v)} options={[{ value: 'LOW', label: '🟢 Low' }, { value: 'NORMAL', label: '🔵 Normal' }, { value: 'HIGH', label: '🟠 High' }, { value: 'URGENT', label: '🔴 Urgent' }]} />
      <div><label className="field-label block mb-1.5">Selected Channel</label><div className="px-3 py-2 bg-neutral-100 dark:bg-primary-800 rounded-lg text-sm text-neutral-700 dark:text-neutral-200">{PAYMENT_CHANNELS.find(c => c.value === formData.paymentChannel)?.label || 'Not selected'}</div></div>
    </div>
    <div className="border-t border-neutral-200 dark:border-primary-800 pt-4">
      <Toggle label="Notify Vendor" description="Send payment notification when processed" checked={formData.notifyVendor} onChange={(v) => updateField('notifyVendor', v)} />
    </div>
  </div>
);

// ============================================================================
// SUMMARY SIDEBAR
// ============================================================================

const SummarySidebar: React.FC<{
  formData: PayableFormData;
  totalAmount: number;
  totalTax: number;
  totalCharges: number;
  grandTotal: number;
  loading: boolean;
  canSubmit: boolean;
}> = ({ formData, totalAmount, totalTax, totalCharges, grandTotal, loading, canSubmit }) => {
  const daysUntilDue = formData.dueDate ? Math.ceil((new Date(formData.dueDate).getTime() - new Date().getTime()) / (1000 * 60 * 60 * 24)) : null;
  
  return (
    <div className="bg-white dark:bg-primary-900 rounded-xl border border-neutral-200 dark:border-primary-800 shadow-sm sticky top-24">
      <div className="p-4 border-b border-neutral-100 dark:border-primary-800/60">
        <h3 className="section-title flex items-center gap-2"><Receipt className="w-5 h-5 text-primary-600 dark:text-primary-200" />Payment Summary</h3>
      </div>
      <div className="p-4 space-y-4">
        {formData.vendorName && (
          <div className="pb-4 border-b border-neutral-100 dark:border-primary-800/60">
            <p className="text-xs text-neutral-500 dark:text-neutral-400 uppercase tracking-wide">Vendor</p>
            <p className="font-semibold text-neutral-900 dark:text-neutral-50 mt-1">{formData.vendorName}</p>
            {formData.invoiceNumber && <p className="text-sm text-neutral-500 dark:text-neutral-400 font-mono mt-1">{formData.invoiceNumber}</p>}
          </div>
        )}
        <div className="space-y-2">
          <div className="flex justify-between text-sm"><span className="text-neutral-500 dark:text-neutral-400">Amount</span><span className="text-neutral-900 dark:text-neutral-50 font-medium">{formatCurrency(totalAmount, formData.currencyCode)}</span></div>
          {formData.applyVat && totalTax > 0 && <div className="flex justify-between text-sm"><span className="text-neutral-500 dark:text-neutral-400">VAT ({formData.vatRate}%)</span><span className="text-neutral-900 dark:text-neutral-50">{formatCurrency(totalTax, formData.currencyCode)}</span></div>}
          {totalCharges > 0 && <div className="flex justify-between text-sm"><span className="text-neutral-500 dark:text-neutral-400">Additional Charges</span><span className="text-neutral-900 dark:text-neutral-50">{formatCurrency(totalCharges, formData.currencyCode)}</span></div>}
          <div className="flex justify-between pt-3 border-t border-neutral-200 dark:border-primary-800"><span className="font-semibold text-neutral-900 dark:text-neutral-50">Total Payable</span><span className="font-bold text-xl text-primary-600 dark:text-primary-200">{formatCurrency(grandTotal, formData.currencyCode)}</span></div>
        </div>
        {formData.dueDate && (
          <div className="bg-neutral-50 dark:bg-primary-950 rounded-lg p-3">
            <div className="flex justify-between items-center"><span className="text-sm text-neutral-600 dark:text-neutral-300">Due Date</span><span className="font-medium text-neutral-900 dark:text-neutral-50">{formatDate(formData.dueDate)}</span></div>
            {daysUntilDue !== null && <p className={`text-xs mt-1 ${daysUntilDue < 0 ? 'text-error-600 dark:text-error-300' : daysUntilDue < 7 ? 'text-warning-600 dark:text-warning-300' : 'text-success-600 dark:text-success-300'}`}>{daysUntilDue < 0 ? `⚠️ ${Math.abs(daysUntilDue)} days overdue` : daysUntilDue === 0 ? '⏰ Due today' : `✓ ${daysUntilDue} days remaining`}</p>}
          </div>
        )}
        {formData.poboEnabled && (
          <div className="bg-accent-50 border border-accent-200 rounded-lg p-3 dark:bg-accent-500/10 dark:border-accent-500/30">
            <div className="flex items-center gap-2 text-accent-700 dark:text-accent-300"><ArrowLeftRight className="w-4 h-4" /><span className="font-medium text-sm">POBO Enabled</span></div>
            <p className="text-xs text-accent-600 mt-1 dark:text-accent-300">{formData.payingEntityName || 'Treasury'} → {formData.onBehalfOfEntityName || 'Subsidiary'}</p>
          </div>
        )}
        {grandTotal > 100000 && (
          <div className="bg-warning-50 border border-warning-200 rounded-lg p-3 dark:bg-warning-500/10 dark:border-warning-500/30">
            <div className="flex items-center gap-2 text-warning-700 dark:text-warning-300"><AlertCircle className="w-4 h-4" /><span className="font-medium text-sm">Approval Required</span></div>
            <p className="text-xs text-warning-600 mt-1 dark:text-warning-300">Amount exceeds AED 100,000 threshold</p>
          </div>
        )}
        <div className="flex items-center gap-2 py-2"><div className="w-2 h-2 rounded-full bg-warning-400" /><span className="text-sm text-neutral-600 dark:text-neutral-300">Draft</span></div>
        {!canSubmit && <p className="text-xs text-error-500 text-center">Select vendor, enter invoice number, choose source account, and select vendor bank account</p>}
      </div>
    </div>
  );
};

// ============================================================================
// MAIN COMPONENT
// ============================================================================

type PageType = 'payables' | 'payables-create' | 'payables-edit' | string;

interface CreatePayablePageProps {
  payableId?: string;
}

const CreatePayablePage: React.FC<CreatePayablePageProps> = ({ payableId }) => {
  const isEditMode = !!payableId;
  const navigation = useNavigation();

  // Get corporateId and legalEntityId from navigation params or use defaults
  const corporateIdFromParams = navigation.params?.corporateId;
  const legalEntityIdFromParams = navigation.params?.legalEntityId;

  // Use provided values or fall back to demo defaults
  const effectiveCorporateId = corporateIdFromParams || '550e8400-e29b-41d4-a716-446655440000';
  const effectiveLegalEntityId = legalEntityIdFromParams || null;


  const [formData, setFormData] = useState<PayableFormData>({
    vendorId: '', vendorName: '', vendorCode: '', vendorTrn: '', selectedBankAccountId: '', sourceVirtualAccountId: '',
    invoiceNumber: '', invoiceDate: new Date().toISOString().split('T')[0], receivedDate: new Date().toISOString().split('T')[0],
    dueDate: '', paymentTerms: 'NET30', currencyCode: 'AED', amount: 0, description: '',
    useLineItems: false, lineItems: [], taxJurisdiction: 'UAE', applyVat: true, vatRate: 5,
    reverseCharge: false, taxExempt: false, taxExemptReason: '', withholdingTax: false, withholdingRate: 0,
    additionalCharges: [], earlyPaymentDays: 0, earlyPaymentDiscount: 0,
    poboEnabled: false, payingEntityId: '', payingEntityName: '', onBehalfOfEntityId: '', onBehalfOfEntityName: '',
    icTreatment: 'IC_RECEIVABLE', rechargeMarkup: 0, hierarchyNodeId: '', hierarchyNodeName: '', hierarchyPath: '',
    splitAllocation: false, costAllocations: [], uploadedFiles: [], linkedPurchaseOrder: '', linkedContract: '',
    linkedGrn: '', internalNotes: '', scheduleType: 'DUE_DATE', scheduledDate: '', paymentPriority: 'NORMAL',
    paymentChannel: '', notifyVendor: false,
  });
  
  const [selectedVendor, setSelectedVendor] = useState<Vendor | null>(null);
  const [activeTab, setActiveTab] = useState<TabId | null>(null);
  const [loading, setLoading] = useState(false);
  const [legalEntities, setLegalEntities] = useState<Array<{ id: string; entityName: string; entityCode: string; entityType: string; }>>([]);
  const [corporateName, setCorporateName] = useState<string>('');
  const [virtualAccounts, setVirtualAccounts] = useState<Array<{ id: string; vaNumber: string; name: string; currentBalance: number; currencyCode: string; }>>([]);

  // Compute selected legal entity from params
  const selectedLegalEntity = useMemo(() => {
    if (!effectiveLegalEntityId || legalEntities.length === 0) return null;
    return legalEntities.find(e => e.id === effectiveLegalEntityId) || null;
  }, [effectiveLegalEntityId, legalEntities]);

  // Check if the selected entity is HQ/Treasury (POBO not applicable for HQ paying itself)
  const isEntityHeadquarters = useMemo(() => {
    if (!selectedLegalEntity) return false;
    return selectedLegalEntity.entityType === 'HEADQUARTERS' || selectedLegalEntity.entityType === 'TREASURY';
  }, [selectedLegalEntity]);

  // Calculate totals - must be before poboEligibility useMemo
  const totalAmount = formData.useLineItems ? formData.lineItems.reduce((sum, item) => sum + item.lineTotal, 0) : formData.amount;
  const totalTax = formData.applyVat && !formData.taxExempt ? totalAmount * (formData.vatRate / 100) : 0;
  const totalCharges = formData.additionalCharges.reduce((sum, c) => sum + c.amount, 0);
  const grandTotal = totalAmount + totalTax + totalCharges;

  // Calculate POBO eligibility based on multiple rules
  const poboEligibility = useMemo((): PoboEligibility => {
    const reasons: string[] = [];

    // Rule 1: Must have a vendor selected
    if (!selectedVendor) {
      return { eligible: false, reasons: ['Select a vendor first'] };
    }

    // Rule 2: Vendor must accept POBO (default to true if not specified)
    if (selectedVendor.acceptsPobo === false) {
      reasons.push('Vendor does not accept POBO payments');
    }

    // Rule 3: Cannot use POBO for intercompany vendors (they should use IC settlement)
    if (selectedVendor.isIntercompany) {
      reasons.push('Intercompany vendors should use IC settlement, not POBO');
    }

    // Rule 4: Entity cannot be HQ/Treasury (HQ doesn't pay on behalf of itself)
    if (isEntityHeadquarters) {
      reasons.push('POBO not applicable for headquarters/treasury entities');
    }

    // Rule 5: Must have a legal entity selected (subsidiary)
    if (!effectiveLegalEntityId) {
      reasons.push('Select a legal entity to enable POBO');
    }

    // Rule 6: Amount must be within POBO limits
    if (grandTotal > 0) {
      if (grandTotal < POBO_CONFIG.minAmount) {
        reasons.push(`Amount below POBO minimum (${POBO_CONFIG.minAmount.toLocaleString()} ${formData.currencyCode})`);
      }
      if (grandTotal > POBO_CONFIG.maxAmount) {
        reasons.push(`Amount exceeds POBO maximum (${POBO_CONFIG.maxAmount.toLocaleString()} ${formData.currencyCode})`);
      }
    }

    // Rule 7: Currency must be supported for POBO
    if (!POBO_CONFIG.supportedCurrencies.includes(formData.currencyCode)) {
      reasons.push(`Currency ${formData.currencyCode} not supported for POBO`);
    }

    // Rule 8: Payment channel must support POBO
    if (formData.paymentChannel && !POBO_CONFIG.supportedChannels.includes(formData.paymentChannel)) {
      reasons.push(`Payment channel ${formData.paymentChannel} does not support POBO`);
    }

    // Rule 9: Vendor-specific amount limits (if defined)
    if (selectedVendor.poboMinAmount && grandTotal < selectedVendor.poboMinAmount) {
      reasons.push(`Below vendor's POBO minimum (${selectedVendor.poboMinAmount.toLocaleString()})`);
    }
    if (selectedVendor.poboMaxAmount && grandTotal > selectedVendor.poboMaxAmount) {
      reasons.push(`Exceeds vendor's POBO maximum (${selectedVendor.poboMaxAmount.toLocaleString()})`);
    }

    // Rule 10: Vendor-specific currency restrictions
    if (selectedVendor.poboCurrencies && selectedVendor.poboCurrencies.length > 0) {
      if (!selectedVendor.poboCurrencies.includes(formData.currencyCode)) {
        reasons.push(`Vendor doesn't accept POBO in ${formData.currencyCode}`);
      }
    }

    return {
      eligible: reasons.length === 0,
      reasons
    };
  }, [selectedVendor, isEntityHeadquarters, effectiveLegalEntityId, grandTotal, formData.currencyCode, formData.paymentChannel]);

  // Auto-disable POBO if not eligible
  useEffect(() => {
    if (!poboEligibility.eligible && formData.poboEnabled) {
      updateField('poboEnabled', false);
    }
  }, [poboEligibility.eligible, formData.poboEnabled]);

  // Load corporate details
  useEffect(() => {
    const loadCorporateDetails = async () => {
      try {
        const response = await corporatesApi.getById(effectiveCorporateId);
        const corp = response?.data || response;
        if (corp) {
          setCorporateName((corp as any).tradeName || (corp as any).legalName || 'Unknown Corporate');
        }
      } catch (error) {
        console.error('Failed to load corporate details:', error);
        setCorporateName('Demo Corporate');
      }
    };
    loadCorporateDetails();
  }, [effectiveCorporateId]);

  // Load legal entities on mount or when corporateId changes
  useEffect(() => {
    const loadLegalEntities = async () => {
      try {
        const response = await legalEntityApi.getByCorporate(effectiveCorporateId);
        if (response.data) {
          setLegalEntities(Array.isArray(response.data) ? response.data : []);
        }
      } catch (error) {
        console.error('Failed to load legal entities:', error);
      }
    };
    loadLegalEntities();
  }, [effectiveCorporateId]);

  // Load virtual accounts for source account selection
  useEffect(() => {
    const loadVirtualAccounts = async () => {
      try {
        const response = await virtualAccountsApi.getAll(0, 100, effectiveCorporateId);
        const vaList = response?.data || response || [];
        // Filter to only active accounts with positive balance
        const activeVAs = (Array.isArray(vaList) ? vaList : [])
          .filter((va: any) => va.status === 'ACTIVE' && va.currentBalance > 0)
          .map((va: any) => ({
            id: va.id,
            vaNumber: va.vaNumber,
            name: va.vaName,
            currentBalance: va.currentBalance,
            currencyCode: va.currencyCode,
          }));
        setVirtualAccounts(activeVAs);
      } catch (error) {
        console.error('Failed to load virtual accounts:', error);
        setVirtualAccounts([]);
      }
    };
    loadVirtualAccounts();
  }, [effectiveCorporateId]);

  const canSubmit = !!(formData.vendorId && formData.invoiceNumber && formData.selectedBankAccountId && formData.paymentChannel && formData.sourceVirtualAccountId && (formData.amount > 0 || formData.lineItems.length > 0));
  
  useEffect(() => {
    if (formData.invoiceDate && formData.paymentTerms) {
      const dueDate = calculateDueDate(formData.invoiceDate, formData.paymentTerms);
      setFormData(prev => ({ ...prev, dueDate }));
    }
  }, [formData.invoiceDate, formData.paymentTerms]);
  
  const updateField = (field: keyof PayableFormData, value: any) => setFormData(prev => ({ ...prev, [field]: value }));
  
  const handleVendorSelect = (vendor: Vendor) => {
    setSelectedVendor(vendor);
    setFormData(prev => ({ ...prev, vendorId: vendor.id, vendorName: vendor.name, vendorCode: vendor.partyCode, vendorTrn: vendor.taxRegistration || '', paymentTerms: vendor.defaultPaymentTerms || prev.paymentTerms, selectedBankAccountId: '', paymentChannel: '' }));
  };
  
  const handleInvoiceSelect = (invoice: Invoice | null) => {
    if (invoice) setFormData(prev => ({ ...prev, invoiceNumber: invoice.invoiceNumber, amount: invoice.amount, currencyCode: invoice.currencyCode, invoiceDate: invoice.invoiceDate, dueDate: invoice.dueDate }));
  };
  
  const handleBankSelect = (accountId: string) => {
    updateField('selectedBankAccountId', accountId);
    const account = selectedVendor?.bankAccounts.find(acc => acc.id === accountId);
    if (account) updateField('currencyCode', account.currencyCode);
  };
  
  const handleChannelChange = (channel: string) => {
    updateField('paymentChannel', channel);
    const account = selectedVendor?.bankAccounts.find(acc => acc.id === formData.selectedBankAccountId);
    if (account && !account.paymentMethods.includes(channel)) updateField('selectedBankAccountId', '');
  };
  
  const handleSubmit = async (isDraft: boolean = false) => {
    if (!isDraft && !canSubmit) return;
    setLoading(true);
    try {
      // Build the payable request matching backend CreatePayableRequest
      // Use effective IDs from navigation params or form data for POBO
      const request = {
        corporateId: effectiveCorporateId,
        owningEntityId: formData.poboEnabled && formData.onBehalfOfEntityId
          ? formData.onBehalfOfEntityId
          : effectiveLegalEntityId || undefined,
        partyId: formData.vendorId || undefined,
        vendorName: formData.vendorName,
        vendorCode: formData.vendorCode,
        invoiceNumber: formData.invoiceNumber,
        invoiceDate: formData.invoiceDate,
        dueDate: formData.dueDate,
        grossAmount: grandTotal,
        netAmount: grandTotal,
        currencyCode: formData.currencyCode,
        description: formData.description,
        status: isDraft ? 'DRAFT' : 'PENDING_APPROVAL',
        paymentRoute: formData.poboEnabled ? 'POBO' : 'DIRECT',
        isIntercompany: formData.icTreatment !== 'NONE' && !!formData.onBehalfOfEntityId,
        paymentChannel: formData.paymentChannel || 'DIRECT',
        taxAmount: totalTax > 0 ? totalTax : undefined,
        taxRate: formData.applyVat && !formData.taxExempt ? formData.vatRate : undefined,
        virtualAccountId: formData.sourceVirtualAccountId || undefined, // Source VA for payment execution
      };

      console.log('Submitting payable to API:', request);
      const result = await payablesApiPhase2.create(request);
      console.log('Payable created successfully:', result);
      navigation.navigate('payables');
    } catch (err: any) {
      console.error('Submit failed:', err);
      const errorMessage = err?.response?.data?.message || err?.response?.data?.error || err?.message || 'Unknown error';
      alert('Failed to save payable: ' + errorMessage);
    } finally {
      setLoading(false);
    }
  };
  
  const goBack = () => navigation.navigate('payables');
  
  return (
    // Phase 10 Design System Unification (2026-05-13): the page's content
    // is now wrapped in <Page maxWidth="narrow"> (~1024px) — forms-first
    // surfaces don't need the full 1280px width. The sticky header stays
    // OUTSIDE <Page> because <Page> applies `space-y-6` vertical rhythm
    // that would mis-flow with sticky chrome. The sticky header's inner
    // wrapper narrows to max-w-5xl to match.
    <div className="min-h-screen bg-neutral-50 dark:bg-primary-950">
      {/* Sticky Header. Inner wrapper uses max-w-5xl to match the
          <Page maxWidth="narrow"> below. Horizontal padding comes from
          the <main> shell (p-4 lg:p-8) — the inner wrapper just centers. */}
      <div className="bg-white dark:bg-primary-900 border-b border-neutral-200 dark:border-primary-800 sticky top-0 z-30">
        <div className="max-w-5xl mx-auto py-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-4">
              <button onClick={goBack} className="p-2 hover:bg-neutral-100 dark:hover:bg-primary-800 rounded-lg transition-colors"><ArrowLeft className="w-5 h-5 text-neutral-600 dark:text-neutral-300" /></button>
              <div>
                <h1 className="text-xl font-bold text-neutral-900 dark:text-neutral-50">{isEditMode ? 'Edit Payable' : 'Create Payable'}</h1>
                <p className="text-sm text-neutral-500 dark:text-neutral-400">{isEditMode ? `Editing ${payableId}` : 'Record vendor invoice for payment'}</p>
              </div>
            </div>
            <div className="flex items-center gap-3">
              <Button variant="secondary" onClick={() => handleSubmit(true)} disabled={loading} leftIcon={<Save className="w-4 h-4" />}>Save Draft</Button>
              <Button variant="primary" onClick={() => handleSubmit(false)} disabled={loading || !canSubmit} loading={loading} leftIcon={<Send className="w-4 h-4" />}>{isEditMode ? 'Update Payable' : 'Submit for Approval'}</Button>
            </div>
          </div>
        </div>
      </div>
      
      {/* Main Content */}
      <Page maxWidth="narrow" className="py-6">
        {/* Corporate/Entity Context Banner */}
        <div className="mb-6 bg-gradient-to-r from-primary-50 to-white border border-primary-200 rounded-xl p-4 dark:border-primary-700 dark:from-primary-500/10 dark:to-primary-900 dark:from-primary-800/40">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-4">
              <div className="p-2 bg-primary-100 dark:bg-primary-700 rounded-lg">
                <Building className="w-5 h-5 text-primary-600 dark:text-primary-200" />
              </div>
              <div>
                <p className="text-xs font-medium text-primary-600 dark:text-primary-200 uppercase tracking-wide">Creating payable for</p>
                <p className="font-semibold text-primary-900 dark:text-neutral-50">{corporateName || 'Loading...'}</p>
              </div>
              {selectedLegalEntity && (
                <>
                  <div className="w-px h-10 bg-primary-200" />
                  <div>
                    <p className="text-xs font-medium text-primary-600 dark:text-primary-200 uppercase tracking-wide">Legal Entity</p>
                    <p className="font-semibold text-primary-900 dark:text-neutral-50">{selectedLegalEntity.entityCode} - {selectedLegalEntity.entityName}</p>
                  </div>
                </>
              )}
            </div>
            {!corporateIdFromParams && (
              <div className="flex items-center gap-2 px-3 py-1.5 bg-warning-100 text-warning-800 rounded-lg text-sm dark:bg-warning-500/20 dark:text-warning-300">
                <AlertTriangle className="w-4 h-4" />
                <span>Using default corporate</span>
              </div>
            )}
          </div>
        </div>

        <div className="flex gap-6">
          <div className="flex-1 space-y-6">
            {/* Step 1: Vendor */}
            <Card padding="lg">
              <div className="flex items-center gap-3 mb-4">
                <div className="w-8 h-8 rounded-full bg-primary-100 dark:bg-primary-700 flex items-center justify-center"><span className="text-sm font-bold text-primary-600 dark:text-primary-200">1</span></div>
                <h2 className="section-title">Select Vendor</h2>
              </div>
              <VendorSearch selectedVendor={selectedVendor} onSelect={handleVendorSelect} onClear={() => { setSelectedVendor(null); setFormData(prev => ({ ...prev, vendorId: '', vendorName: '', vendorCode: '', vendorTrn: '', selectedBankAccountId: '', paymentChannel: '', invoiceNumber: '', amount: 0 })); }} corporateId={effectiveCorporateId} />
            </Card>
            
            {/* Step 2: Invoice Details */}
            {selectedVendor && (
              <Card padding="lg">
                <div className="flex items-center gap-3 mb-4">
                  <div className="w-8 h-8 rounded-full bg-primary-100 dark:bg-primary-700 flex items-center justify-center"><span className="text-sm font-bold text-primary-600 dark:text-primary-200">2</span></div>
                  <h2 className="section-title">Invoice Details</h2>
                </div>
                <div className="space-y-4">
                  <InvoiceLookup vendorId={formData.vendorId} invoiceNumber={formData.invoiceNumber} onInvoiceSelect={handleInvoiceSelect} onManualEntry={(inv) => updateField('invoiceNumber', inv)} />
                  <div className="grid grid-cols-4 gap-4">
                    <Input label="Amount" type="number" value={formData.amount} onChange={(v) => updateField('amount', parseFloat(v) || 0)} prefix={<DollarSign className="w-4 h-4" />} required size="lg" />
                    <Select label="Currency" value={formData.currencyCode} onChange={(v) => updateField('currencyCode', v)} options={CURRENCIES.map(c => ({ value: c, label: c }))} />
                    <Input label="Invoice Date" type="date" value={formData.invoiceDate} onChange={(v) => updateField('invoiceDate', v)} />
                    <Select label="Payment Terms" value={formData.paymentTerms} onChange={(v) => updateField('paymentTerms', v)} options={PAYMENT_TERMS.map(t => ({ value: t.value, label: t.label }))} />
                  </div>
                  <Input label="Description / Memo" value={formData.description} onChange={(v) => updateField('description', v)} placeholder="Brief description of this payable..." />
                </div>
              </Card>
            )}
            
            {/* Step 3: Payment Method */}
            {selectedVendor && formData.invoiceNumber && (
              <Card padding="lg">
                <div className="flex items-center gap-3 mb-4">
                  <div className="w-8 h-8 rounded-full bg-primary-100 dark:bg-primary-700 flex items-center justify-center"><span className="text-sm font-bold text-primary-600 dark:text-primary-200">3</span></div>
                  <h2 className="section-title">Payment Method</h2>
                </div>

                {/* Source Account Selection */}
                <div className="mb-6">
                  <label className="field-label block mb-2">
                    Source Account (Pay From) <span className="text-error-500">*</span>
                  </label>
                  {virtualAccounts.length === 0 ? (
                    <div className="bg-warning-50 border border-warning-200 rounded-lg p-4 flex items-center gap-3 dark:bg-warning-500/10 dark:border-warning-500/30">
                      <AlertTriangle className="w-5 h-5 text-warning-600 dark:text-warning-300" />
                      <div>
                        <p className="text-sm font-medium text-warning-800 dark:text-warning-300">No source accounts available</p>
                        <p className="text-xs text-warning-600 dark:text-warning-300">No virtual accounts with positive balance found</p>
                      </div>
                    </div>
                  ) : (
                    <div className="space-y-2">
                      {virtualAccounts.map((va) => {
                        const isSelected = formData.sourceVirtualAccountId === va.id;
                        const hasSufficientBalance = va.currentBalance >= grandTotal;
                        return (
                          <button
                            key={va.id}
                            onClick={() => updateField('sourceVirtualAccountId', va.id)}
                            className={`w-full flex items-center gap-4 p-4 rounded-lg border-2 transition-all text-left ${isSelected ? 'border-primary-500 bg-primary-50 dark:bg-primary-800/40' : hasSufficientBalance ? 'border-neutral-200 dark:border-primary-800 hover:border-neutral-300 dark:hover:border-primary-700 bg-white dark:bg-primary-900' : 'border-neutral-200 bg-neutral-50 dark:bg-primary-950 opacity-60'}`}
                            disabled={!hasSufficientBalance}
                          >
                            <div className={`w-10 h-10 rounded-lg flex items-center justify-center ${isSelected ? 'bg-primary-100 dark:bg-primary-700' : 'bg-neutral-100 dark:bg-primary-800'}`}>
                              <CreditCard className={`w-5 h-5 ${isSelected ? 'text-primary-600 dark:text-primary-200' : 'text-neutral-500 dark:text-neutral-400'}`} />
                            </div>
                            <div className="flex-1">
                              <div className="flex items-center gap-2">
                                <p className="font-medium text-neutral-900 dark:text-neutral-50">{va.name}</p>
                                <Badge variant="neutral" size="sm">{va.currencyCode}</Badge>
                                {!hasSufficientBalance && <Badge variant="warning" size="sm">Insufficient</Badge>}
                              </div>
                              <p className="text-sm text-neutral-500 dark:text-neutral-400 font-mono">{va.vaNumber}</p>
                            </div>
                            <div className="text-right">
                              <p className={`font-semibold ${hasSufficientBalance ? 'text-success-600 dark:text-success-300' : 'text-warning-600 dark:text-warning-300'}`}>
                                {formatCurrency(va.currentBalance, va.currencyCode)}
                              </p>
                              <p className="text-xs text-neutral-400 dark:text-neutral-500">Available</p>
                            </div>
                            {isSelected && <Check className="w-5 h-5 text-primary-600 dark:text-primary-200" />}
                          </button>
                        );
                      })}
                    </div>
                  )}
                  {formData.sourceVirtualAccountId && (
                    <div className="mt-3 bg-success-50 border border-success-200 rounded-lg p-3 flex items-center gap-3 dark:bg-success-500/10 dark:border-success-500/30">
                      <CheckCircle2 className="w-5 h-5 text-success-600 flex-shrink-0 dark:text-success-300" />
                      <p className="text-sm text-success-700 dark:text-success-300">
                        Payment will be debited from <span className="font-medium">{virtualAccounts.find(v => v.id === formData.sourceVirtualAccountId)?.name}</span>
                      </p>
                    </div>
                  )}
                </div>

                {/* Vendor Bank Account Selection */}
                <div className="pt-4 border-t border-neutral-200 dark:border-primary-800">
                  <label className="field-label block mb-2">Vendor Bank Account (Pay To)</label>
                  <BankAccountSelector accounts={selectedVendor.bankAccounts} selectedId={formData.selectedBankAccountId} paymentChannel={formData.paymentChannel} onSelect={handleBankSelect} onChannelChange={handleChannelChange} />
                </div>
              </Card>
            )}
            
            {/* Optional Tabs */}
            {selectedVendor && formData.invoiceNumber && formData.selectedBankAccountId && (
              <Card padding="none">
                <div className="border-b border-neutral-200 dark:border-primary-800 px-4 pt-4">
                  <div className="flex items-center gap-2 mb-4">
                    <Settings2 className="w-5 h-5 text-neutral-500 dark:text-neutral-400" />
                    <h2 className="font-semibold text-neutral-900 dark:text-neutral-50">Optional Configuration</h2>
                    <span className="text-sm text-neutral-400 dark:text-neutral-500 ml-2">Click to expand</span>
                  </div>
                  <div className="flex gap-1 -mb-px overflow-x-auto">
                    {TAB_CONFIG.map((tab) => {
                      const Icon = tab.icon;
                      const isActive = activeTab === tab.id;
                      const hasContent = tab.id === 'pobo' ? formData.poboEnabled : tab.id === 'tax' ? formData.additionalCharges.length > 0 : tab.id === 'hierarchy' ? !!formData.hierarchyNodeId : tab.id === 'documents' ? !!formData.linkedPurchaseOrder : tab.id === 'scheduling' ? formData.scheduleType !== 'DUE_DATE' : false;
                      return (
                        <button key={tab.id} onClick={() => setActiveTab(isActive ? null : tab.id)}
                          className={`flex items-center gap-2 px-4 py-3 text-sm font-medium border-b-2 transition-colors whitespace-nowrap ${isActive ? 'border-primary-500 text-primary-600 dark:text-primary-200 bg-primary-50 dark:bg-primary-800/40 dark:bg-primary-800/40' : 'border-transparent text-neutral-500 dark:text-neutral-400 hover:text-neutral-700 dark:text-neutral-200 hover:bg-neutral-50 dark:hover:bg-primary-800/50'} dark:bg-primary-800/40 dark:hover:text-neutral-200 dark:hover:bg-primary-800/50`}>
                          <Icon className="w-4 h-4" />
                          {tab.label}
                          {hasContent && !isActive && <span className="w-2 h-2 rounded-full bg-primary-50 dark:bg-primary-800/400" />}
                        </button>
                      );
                    })}
                  </div>
                </div>
                {activeTab && (
                  <div className="p-6">
                    {activeTab === 'tax' && <TaxChargesTab formData={formData} updateField={updateField} subtotal={totalAmount} />}
                    {activeTab === 'pobo' && <PoboTab formData={formData} updateField={updateField} legalEntities={legalEntities} poboEligibility={poboEligibility} />}
                    {activeTab === 'hierarchy' && <HierarchyTab formData={formData} updateField={updateField} />}
                    {activeTab === 'documents' && <DocumentsTab formData={formData} updateField={updateField} />}
                    {activeTab === 'scheduling' && <SchedulingTab formData={formData} updateField={updateField} />}
                  </div>
                )}
                {!activeTab && <div className="p-6 text-center text-neutral-500 dark:text-neutral-400"><p className="text-sm">Click a tab above to configure optional settings</p><p className="text-xs mt-1">Tax configuration, POBO, cost allocation, documents, and scheduling</p></div>}
              </Card>
            )}
            
            {/* Approval Info */}
            {canSubmit && grandTotal > 0 && (
              <Card padding="md">
                <div className="flex items-start gap-4">
                  <div className={`p-3 rounded-lg ${grandTotal > 100000 ? 'bg-warning-100 dark:bg-warning-500/20' : 'bg-success-100 dark:bg-success-500/20'}`}>
                    {grandTotal > 100000 ? <AlertCircle className="w-6 h-6 text-warning-600 dark:text-warning-300" /> : <CheckCircle2 className="w-6 h-6 text-success-600 dark:text-success-300" />}
                  </div>
                  <div>
                    <h3 className={`font-semibold ${grandTotal > 100000 ? 'text-warning-800 dark:text-warning-300' : 'text-success-800 dark:text-success-300'}`}>{grandTotal > 100000 ? 'Approval Required' : 'Ready for Processing'}</h3>
                    <p className={`text-sm mt-1 ${grandTotal > 100000 ? 'text-warning-600 dark:text-warning-300' : 'text-success-600 dark:text-success-300'}`}>
                      {grandTotal > 100000 ? `This payable of ${formatCurrency(grandTotal, formData.currencyCode)} requires manager approval` : `This payable of ${formatCurrency(grandTotal, formData.currencyCode)} will be auto-approved`}
                    </p>
                  </div>
                </div>
              </Card>
            )}
          </div>
          
          {/* Summary Sidebar */}
          <div className="w-80 flex-shrink-0">
            <SummarySidebar formData={formData} totalAmount={totalAmount} totalTax={totalTax} totalCharges={totalCharges} grandTotal={grandTotal} loading={loading} canSubmit={canSubmit} />
          </div>
        </div>

        {/* Bottom Action Bar. The previous inner `max-w-7xl mx-auto py-4`
            wrapper has been dropped — Page already centers content at
            max-w-5xl above; the action bar now naturally fills that width.
            Kept the border-t / bg / -mx wrapper around the buttons so the
            divider still extends beyond Page's content column. */}
        <div className="mt-8 border-t border-neutral-200 dark:border-primary-800 bg-white dark:bg-primary-900 py-4">
          <div className="flex items-center justify-between">
            <button onClick={goBack} className="px-4 py-2 text-sm font-medium text-neutral-600 dark:text-neutral-300 hover:text-neutral-900 dark:text-neutral-50 transition-colors dark:hover:text-neutral-50">
              Cancel
            </button>
            <div className="flex items-center gap-3">
              <Button variant="secondary" size="lg" onClick={() => handleSubmit(true)} disabled={loading} leftIcon={<Save className="w-4 h-4" />}>
                Save Draft
              </Button>
              <Button variant="primary" size="lg" onClick={() => handleSubmit(false)} disabled={loading || !canSubmit} loading={loading} leftIcon={<Send className="w-4 h-4" />}>
                {isEditMode ? 'Update Payable' : 'Submit for Approval'}
              </Button>
            </div>
          </div>
        </div>
      </Page>
    </div>
  );
};

export default CreatePayablePage;