import React, { useState, useEffect, useCallback } from 'react';
import {
  Receipt, Globe, Building2, Plus, Search, Edit2, Trash2, Check, DollarSign, Settings,
  Loader2, RefreshCw, Download, Upload, ArrowUpRight, ArrowDownRight, ArrowLeftRight, FileText,
  Layers, CreditCard, Zap, Send, Shield, Tag, Wallet, CreditCard as CardIcon, Store,
  Undo2, AlertTriangle, GitMerge, Landmark, PiggyBank, CircleDollarSign,
} from 'lucide-react';
import { Button, Badge, Input } from '../components/ui';
import { Modal } from '../components/ui/enhanced';
import { formatCurrency, cn } from '../utils';
import { PageHeader } from '../components/layout/PageHeader';
import toast from 'react-hot-toast';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8053/api/v1';

const apiClient = {
  async get<T>(url: string): Promise<T> {
    const response = await fetch(`${API_BASE_URL}${url}`, { headers: { 'Content-Type': 'application/json' } });
    if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);
    const result = await response.json();
    return result.data !== undefined ? result.data : result;
  },
  async post<T>(url: string, data?: any): Promise<T> {
    const response = await fetch(`${API_BASE_URL}${url}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: data ? JSON.stringify(data) : undefined });
    if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);
    const result = await response.json();
    return result.data !== undefined ? result.data : result;
  },
  async put<T>(url: string, data: any): Promise<T> {
    const response = await fetch(`${API_BASE_URL}${url}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) });
    if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);
    const result = await response.json();
    return result.data !== undefined ? result.data : result;
  },
  async delete(url: string): Promise<void> {
    const response = await fetch(`${API_BASE_URL}${url}`, { method: 'DELETE' });
    if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);
  },
};

const taxChargesApi = {
  getJurisdictions: () => apiClient.get<TaxJurisdiction[]>('/tax-charges/jurisdictions'),
  getTaxConfigs: () => apiClient.get<TaxConfiguration[]>('/tax-charges/tax-configs'),
  createTaxConfig: (data: Partial<TaxConfiguration>) => apiClient.post<TaxConfiguration>('/tax-charges/tax-configs', data),
  deleteTaxConfig: (taxCode: string) => apiClient.delete(`/tax-charges/tax-configs/${taxCode}`),
  getChargeConfigs: () => apiClient.get<ChargeConfiguration[]>('/tax-charges/charge-configs'),
  createChargeConfig: (data: Partial<ChargeConfiguration>) => apiClient.post<ChargeConfiguration>('/tax-charges/charge-configs', data),
  deleteChargeConfig: (chargeCode: string) => apiClient.delete(`/tax-charges/charge-configs/${chargeCode}`),
};

type TaxType = 'VAT' | 'GST' | 'WHT' | 'SALES_TAX' | 'EXCISE' | 'STAMP_DUTY' | 'CUSTOMS' | 'OTHER';
type TaxCategory = 'STANDARD' | 'REDUCED' | 'ZERO' | 'EXEMPT' | 'SPECIAL';
type TaxStatus = 'ACTIVE' | 'INACTIVE' | 'PENDING' | 'SUPERSEDED';
// ChargeType - Matches backend ChargeConfiguration.ChargeType enum
type ChargeType =
  | 'SWIFT_FEE'              // SWIFT transfer fees
  | 'FX_FEE'                 // Foreign exchange fees
  | 'PROCESSING_FEE'         // Payment processing fees
  | 'URGENCY_FEE'            // Priority/urgency fees
  | 'SERVICE_FEE'            // General service fees
  | 'MAINTENANCE_FEE'        // Account maintenance
  | 'TRANSACTION_FEE'        // Per-transaction fees
  | 'POBO_FEE'               // Pay On Behalf Of fee
  | 'ADMIN_FEE'              // Administrative fees
  | 'WALLET_TOPUP_FEE'       // Wallet top-up fee
  | 'WALLET_WITHDRAWAL_FEE'  // Wallet withdrawal fee
  | 'CARD_ISSUANCE_FEE'      // Virtual/physical card issuance
  | 'CARD_TRANSACTION_FEE'   // Card transaction fee
  | 'MERCHANT_FEE'           // Merchant processing fee
  | 'SETTLEMENT_FEE'         // Settlement/payout fee
  | 'REFUND_FEE'             // Refund processing fee
  | 'CHARGEBACK_FEE'         // Chargeback handling fee
  | 'NETTING_FEE'            // Netting cycle fee
  | 'POOLING_FEE'            // Notional pooling fee
  | 'IHB_FEE'                // In-house bank fee
  | 'OTHER';                 // Other charges
type ChargeCategory = 'FIXED' | 'PERCENTAGE' | 'TIERED' | 'SLIDING_SCALE';
type ChargeStatus = 'ACTIVE' | 'INACTIVE' | 'PENDING' | 'SUPERSEDED';

interface TaxJurisdiction { id: string; jurisdictionCode: string; jurisdictionName: string; countryCode: string; regionCode?: string; supportsVat: boolean; supportsGst: boolean; supportsWithholding: boolean; supportsSalesTax: boolean; taxAuthorityName?: string; reportingCurrency: string; status: 'ACTIVE' | 'INACTIVE'; effectiveFrom: string; effectiveTo?: string; }
interface TaxConfiguration { id: string; taxCode: string; taxName: string; description?: string; taxType: TaxType; jurisdictionCode: string; taxCategory: TaxCategory; ratePercentage: number; minimumAmount?: number; maximumAmount?: number; appliesToPayables: boolean; appliesToReceivables: boolean; isWithholding: boolean; isRecoverable: boolean; recoveryPercentage?: number; status: TaxStatus; effectiveFrom: string; effectiveTo?: string; createdAt: string; }
interface ChargeConfiguration { id: string; chargeCode: string; chargeName: string; description?: string; chargeType: ChargeType; chargeCategory: ChargeCategory; fixedAmount?: number; percentageRate?: number; currencyCode: string; minimumCharge?: number; maximumCharge?: number; tierConfig?: TierConfig[]; appliesToPaymentMethod?: string; appliesToPriority?: string; isCrossBorder: boolean; isDomestic: boolean; waiverThreshold?: number; waiverForVip: boolean; status: ChargeStatus; effectiveFrom: string; effectiveTo?: string; createdAt: string; }
interface TierConfig { from: number; to?: number; rate: number; }

const MOCK_JURISDICTIONS: TaxJurisdiction[] = [
  { id: '1', jurisdictionCode: 'UAE', jurisdictionName: 'United Arab Emirates', countryCode: 'AE', supportsVat: true, supportsGst: false, supportsWithholding: false, supportsSalesTax: false, taxAuthorityName: 'Federal Tax Authority', reportingCurrency: 'AED', status: 'ACTIVE', effectiveFrom: '2018-01-01' },
  { id: '2', jurisdictionCode: 'KSA', jurisdictionName: 'Kingdom of Saudi Arabia', countryCode: 'SA', supportsVat: true, supportsGst: false, supportsWithholding: true, supportsSalesTax: false, taxAuthorityName: 'ZATCA', reportingCurrency: 'SAR', status: 'ACTIVE', effectiveFrom: '2018-01-01' },
  { id: '3', jurisdictionCode: 'UK', jurisdictionName: 'United Kingdom', countryCode: 'GB', supportsVat: true, supportsGst: false, supportsWithholding: true, supportsSalesTax: false, taxAuthorityName: 'HMRC', reportingCurrency: 'GBP', status: 'ACTIVE', effectiveFrom: '2020-01-01' },
  { id: '4', jurisdictionCode: 'IND', jurisdictionName: 'India', countryCode: 'IN', supportsVat: false, supportsGst: true, supportsWithholding: true, supportsSalesTax: false, taxAuthorityName: 'GST Council', reportingCurrency: 'INR', status: 'ACTIVE', effectiveFrom: '2017-07-01' },
];

const MOCK_TAX_CONFIGS: TaxConfiguration[] = [
  { id: '1', taxCode: 'VAT5_UAE', taxName: 'UAE VAT Standard', description: 'Standard VAT rate for UAE', taxType: 'VAT', jurisdictionCode: 'UAE', taxCategory: 'STANDARD', ratePercentage: 5, appliesToPayables: true, appliesToReceivables: true, isWithholding: false, isRecoverable: true, recoveryPercentage: 100, status: 'ACTIVE', effectiveFrom: '2018-01-01', createdAt: '2024-01-01' },
  { id: '2', taxCode: 'VAT0_UAE', taxName: 'UAE VAT Zero-Rated', description: 'Zero-rated VAT for exports', taxType: 'VAT', jurisdictionCode: 'UAE', taxCategory: 'ZERO', ratePercentage: 0, appliesToPayables: true, appliesToReceivables: true, isWithholding: false, isRecoverable: true, recoveryPercentage: 100, status: 'ACTIVE', effectiveFrom: '2018-01-01', createdAt: '2024-01-01' },
  { id: '3', taxCode: 'VAT15_KSA', taxName: 'KSA VAT Standard', description: 'Standard VAT rate for Saudi Arabia', taxType: 'VAT', jurisdictionCode: 'KSA', taxCategory: 'STANDARD', ratePercentage: 15, appliesToPayables: true, appliesToReceivables: true, isWithholding: false, isRecoverable: true, recoveryPercentage: 100, status: 'ACTIVE', effectiveFrom: '2020-07-01', createdAt: '2024-01-01' },
  { id: '4', taxCode: 'WHT5_KSA', taxName: 'KSA Withholding Tax', description: 'Withholding tax for non-residents', taxType: 'WHT', jurisdictionCode: 'KSA', taxCategory: 'STANDARD', ratePercentage: 5, appliesToPayables: true, appliesToReceivables: false, isWithholding: true, isRecoverable: false, status: 'ACTIVE', effectiveFrom: '2020-01-01', createdAt: '2024-01-01' },
  { id: '5', taxCode: 'VAT20_UK', taxName: 'UK VAT Standard', description: 'Standard VAT rate for UK', taxType: 'VAT', jurisdictionCode: 'UK', taxCategory: 'STANDARD', ratePercentage: 20, appliesToPayables: true, appliesToReceivables: true, isWithholding: false, isRecoverable: true, recoveryPercentage: 100, status: 'ACTIVE', effectiveFrom: '2011-01-04', createdAt: '2024-01-01' },
  { id: '6', taxCode: 'GST18_IND', taxName: 'India GST Standard', description: 'Standard GST rate for services', taxType: 'GST', jurisdictionCode: 'IND', taxCategory: 'STANDARD', ratePercentage: 18, appliesToPayables: true, appliesToReceivables: true, isWithholding: false, isRecoverable: true, recoveryPercentage: 100, status: 'ACTIVE', effectiveFrom: '2017-07-01', createdAt: '2024-01-01' },
];

const MOCK_CHARGE_CONFIGS: ChargeConfiguration[] = [
  // Payment Transfer Fees
  { id: '1', chargeCode: 'SWIFT_STD', chargeName: 'SWIFT Transfer Fee', description: 'Standard SWIFT transfer fee', chargeType: 'SWIFT_FEE', chargeCategory: 'FIXED', fixedAmount: 150, currencyCode: 'AED', isCrossBorder: true, isDomestic: false, waiverForVip: true, status: 'ACTIVE', effectiveFrom: '2024-01-01', createdAt: '2024-01-01' },
  { id: '2', chargeCode: 'PROC_STD', chargeName: 'Processing Fee', description: 'Standard payment processing fee', chargeType: 'PROCESSING_FEE', chargeCategory: 'PERCENTAGE', percentageRate: 0.25, currencyCode: 'AED', minimumCharge: 10, maximumCharge: 500, isCrossBorder: false, isDomestic: true, waiverForVip: false, status: 'ACTIVE', effectiveFrom: '2024-01-01', createdAt: '2024-01-01' },
  { id: '3', chargeCode: 'FX_STD', chargeName: 'FX Conversion Fee', description: 'Foreign exchange conversion fee', chargeType: 'FX_FEE', chargeCategory: 'PERCENTAGE', percentageRate: 0.50, currencyCode: 'AED', minimumCharge: 25, maximumCharge: 2500, isCrossBorder: true, isDomestic: false, waiverForVip: true, status: 'ACTIVE', effectiveFrom: '2024-01-01', createdAt: '2024-01-01' },
  { id: '4', chargeCode: 'URG_SAME', chargeName: 'Same-Day Priority Fee', description: 'Fee for same-day payment processing', chargeType: 'URGENCY_FEE', chargeCategory: 'FIXED', fixedAmount: 250, currencyCode: 'AED', appliesToPriority: 'SAME_DAY', isCrossBorder: true, isDomestic: true, waiverForVip: false, status: 'ACTIVE', effectiveFrom: '2024-01-01', createdAt: '2024-01-01' },
  { id: '5', chargeCode: 'TXN_TIER', chargeName: 'Tiered Transaction Fee', description: 'Volume-based tiered transaction fee', chargeType: 'TRANSACTION_FEE', chargeCategory: 'TIERED', currencyCode: 'AED', tierConfig: [{ from: 0, to: 10000, rate: 0.5 }, { from: 10000, to: 100000, rate: 0.3 }, { from: 100000, rate: 0.15 }], isCrossBorder: false, isDomestic: true, waiverForVip: false, status: 'ACTIVE', effectiveFrom: '2024-01-01', createdAt: '2024-01-01' },
  // Treasury / IHB Fees
  { id: '6', chargeCode: 'SVC_POBO', chargeName: 'POBO Service Fee', description: 'Pay On Behalf Of service fee for subsidiary payments', chargeType: 'POBO_FEE', chargeCategory: 'PERCENTAGE', percentageRate: 0.50, currencyCode: 'AED', minimumCharge: 25, maximumCharge: 1000, isCrossBorder: true, isDomestic: true, waiverForVip: true, waiverThreshold: 1000000, status: 'ACTIVE', effectiveFrom: '2024-01-01', createdAt: '2024-01-01' },
  { id: '7', chargeCode: 'IHB_STD', chargeName: 'IHB Participation Fee', description: 'In-House Bank participation fee for subsidiaries', chargeType: 'IHB_FEE', chargeCategory: 'PERCENTAGE', percentageRate: 0.10, currencyCode: 'AED', minimumCharge: 50, maximumCharge: 5000, isCrossBorder: true, isDomestic: true, waiverForVip: false, status: 'ACTIVE', effectiveFrom: '2024-01-01', createdAt: '2024-01-01' },
  { id: '8', chargeCode: 'NET_CYCLE', chargeName: 'Netting Cycle Fee', description: 'Fee per netting cycle execution', chargeType: 'NETTING_FEE', chargeCategory: 'FIXED', fixedAmount: 500, currencyCode: 'AED', isCrossBorder: true, isDomestic: true, waiverForVip: true, status: 'ACTIVE', effectiveFrom: '2024-01-01', createdAt: '2024-01-01' },
  { id: '9', chargeCode: 'POOL_MTH', chargeName: 'Notional Pooling Fee', description: 'Monthly notional pooling maintenance fee', chargeType: 'POOLING_FEE', chargeCategory: 'FIXED', fixedAmount: 1000, currencyCode: 'AED', isCrossBorder: true, isDomestic: true, waiverForVip: false, status: 'ACTIVE', effectiveFrom: '2024-01-01', createdAt: '2024-01-01' },
  { id: '10', chargeCode: 'SETTLE_IC', chargeName: 'Intercompany Settlement Fee', description: 'Fee for intercompany settlement transactions', chargeType: 'SETTLEMENT_FEE', chargeCategory: 'PERCENTAGE', percentageRate: 0.05, currencyCode: 'AED', minimumCharge: 10, maximumCharge: 200, isCrossBorder: true, isDomestic: true, waiverForVip: true, status: 'ACTIVE', effectiveFrom: '2024-01-01', createdAt: '2024-01-01' },
];

const TAX_TYPE_CONFIG: Record<TaxType, { label: string; icon: React.FC<{ className?: string }>; color: string; bgColor: string }> = {
  VAT: { label: 'VAT', icon: Receipt, color: 'text-blue-600 dark:text-blue-300', bgColor: 'bg-blue-50 dark:bg-blue-500/10' },
  GST: { label: 'GST', icon: Receipt, color: 'text-green-600 dark:text-green-300', bgColor: 'bg-green-50 dark:bg-green-500/10' },
  WHT: { label: 'Withholding', icon: Shield, color: 'text-warning-600 dark:text-warning-300', bgColor: 'bg-warning-50 dark:bg-warning-500/10' },
  SALES_TAX: { label: 'Sales Tax', icon: Tag, color: 'text-cat-2', bgColor: 'bg-cat-2-soft dark:bg-cat-2/15' },
  EXCISE: { label: 'Excise', icon: Layers, color: 'text-orange-600 dark:text-orange-300', bgColor: 'bg-orange-50 dark:bg-orange-500/10' },
  STAMP_DUTY: { label: 'Stamp Duty', icon: FileText, color: 'text-cat-1', bgColor: 'bg-cat-1-soft dark:bg-cat-1/15' },
  CUSTOMS: { label: 'Customs', icon: Globe, color: 'text-cat-3', bgColor: 'bg-cat-3-soft dark:bg-cat-3/15' },
  OTHER: { label: 'Other', icon: Tag, color: 'text-gray-600', bgColor: 'bg-gray-50' },
};

const CHARGE_TYPE_CONFIG: Record<ChargeType, { label: string; icon: React.FC<{ className?: string }>; color: string; bgColor: string }> = {
  // Payment Transfer Fees
  SWIFT_FEE: { label: 'SWIFT Fee', icon: Send, color: 'text-blue-600 dark:text-blue-300', bgColor: 'bg-blue-50 dark:bg-blue-500/10' },
  FX_FEE: { label: 'FX Fee', icon: ArrowUpRight, color: 'text-green-600 dark:text-green-300', bgColor: 'bg-green-50 dark:bg-green-500/10' },
  PROCESSING_FEE: { label: 'Processing', icon: Settings, color: 'text-cat-2', bgColor: 'bg-cat-2-soft dark:bg-cat-2/15' },
  URGENCY_FEE: { label: 'Urgency', icon: Zap, color: 'text-warning-600 dark:text-warning-300', bgColor: 'bg-warning-50 dark:bg-warning-500/10' },
  SERVICE_FEE: { label: 'Service', icon: CreditCard, color: 'text-cat-1', bgColor: 'bg-cat-1-soft dark:bg-cat-1/15' },
  MAINTENANCE_FEE: { label: 'Maintenance', icon: Settings, color: 'text-gray-600', bgColor: 'bg-gray-50' },
  TRANSACTION_FEE: { label: 'Transaction', icon: ArrowLeftRight, color: 'text-cat-3', bgColor: 'bg-cat-3-soft dark:bg-cat-3/15' },
  ADMIN_FEE: { label: 'Admin', icon: FileText, color: 'text-cat-4', bgColor: 'bg-cat-4-soft dark:bg-cat-4/15' },
  // Treasury / IHB Fees
  POBO_FEE: { label: 'POBO', icon: Building2, color: 'text-orange-600 dark:text-orange-300', bgColor: 'bg-orange-50 dark:bg-orange-500/10' },
  IHB_FEE: { label: 'IHB Fee', icon: Landmark, color: 'text-yellow-600 dark:text-yellow-300', bgColor: 'bg-yellow-50 dark:bg-yellow-500/10' },
  NETTING_FEE: { label: 'Netting', icon: GitMerge, color: 'text-cyan-600 dark:text-cyan-300', bgColor: 'bg-cyan-50 dark:bg-cyan-500/10' },
  POOLING_FEE: { label: 'Pooling', icon: PiggyBank, color: 'text-lime-600 dark:text-lime-300', bgColor: 'bg-lime-50 dark:bg-lime-500/10' },
  SETTLEMENT_FEE: { label: 'Settlement', icon: CircleDollarSign, color: 'text-cat-5', bgColor: 'bg-cat-5-soft dark:bg-cat-5/15' },
  // Wallet / BaaS Fees
  WALLET_TOPUP_FEE: { label: 'Wallet Top-up', icon: Wallet, color: 'text-cat-2', bgColor: 'bg-cat-2-soft dark:bg-cat-2/15' },
  WALLET_WITHDRAWAL_FEE: { label: 'Wallet Withdrawal', icon: Wallet, color: 'text-fuchsia-600 dark:text-fuchsia-300', bgColor: 'bg-fuchsia-50 dark:bg-fuchsia-500/10' },
  CARD_ISSUANCE_FEE: { label: 'Card Issuance', icon: CardIcon, color: 'text-sky-600 dark:text-sky-300', bgColor: 'bg-sky-50 dark:bg-sky-500/10' },
  CARD_TRANSACTION_FEE: { label: 'Card Transaction', icon: CardIcon, color: 'text-blue-600 dark:text-blue-300', bgColor: 'bg-blue-50 dark:bg-blue-500/10' },
  // E-commerce Fees
  MERCHANT_FEE: { label: 'Merchant', icon: Store, color: 'text-rose-600 dark:text-rose-300', bgColor: 'bg-rose-50 dark:bg-rose-500/10' },
  REFUND_FEE: { label: 'Refund', icon: Undo2, color: 'text-warning-600 dark:text-warning-300', bgColor: 'bg-warning-50 dark:bg-warning-500/10' },
  CHARGEBACK_FEE: { label: 'Chargeback', icon: AlertTriangle, color: 'text-red-600 dark:text-red-300', bgColor: 'bg-red-50 dark:bg-red-500/10' },
  // Other
  OTHER: { label: 'Other', icon: Tag, color: 'text-gray-600', bgColor: 'bg-gray-50' },
};

const CHARGE_CATEGORY_CONFIG: Record<ChargeCategory, { label: string; description: string }> = {
  FIXED: { label: 'Fixed', description: 'Flat fee amount' },
  PERCENTAGE: { label: 'Percentage', description: 'Rate-based on amount' },
  TIERED: { label: 'Tiered', description: 'Volume-based tiers' },
  SLIDING_SCALE: { label: 'Sliding Scale', description: 'Progressive rates' },
};

const StatCard: React.FC<{ label: string; value: string | number; icon: React.FC<{ className?: string }>; color: string; bgColor: string; trend?: number; loading?: boolean }> = ({ label, value, icon: Icon, color, bgColor, trend, loading }) => (
  <div className="bg-white rounded-xl p-4 shadow-sm border border-neutral-100 dark:bg-primary-900 dark:border-primary-800/60">
    <div className="flex items-center justify-between">
      <div className="flex items-center gap-3">
        <div className={cn('w-10 h-10 rounded-lg flex items-center justify-center', bgColor)}><Icon className={cn('w-5 h-5', color)} /></div>
        <div>
          <p className="text-sm text-neutral-500 dark:text-neutral-400">{label}</p>
          {/* Phase 12 Task E: .stat-value-xs replaces the raw `text-xl
              font-semibold`; the caller-supplied colour class (semantic or
              cat-N categorical) overrides the utility's default ink. */}
          {loading ? <div className="h-7 w-16 bg-neutral-200 animate-pulse rounded mt-1 dark:bg-primary-800" /> : <p className={cn('stat-value-xs', color)}>{value}</p>}
        </div>
      </div>
      {trend !== undefined && <div className={cn('flex items-center gap-1 text-sm', trend >= 0 ? 'text-green-600 dark:text-green-300' : 'text-red-600 dark:text-red-300')}>{trend >= 0 ? <ArrowUpRight className="w-4 h-4" /> : <ArrowDownRight className="w-4 h-4" />}{Math.abs(trend)}%</div>}
    </div>
  </div>
);

const TabButton: React.FC<{ active: boolean; onClick: () => void; icon: React.FC<{ className?: string }>; label: string; count?: number }> = ({ active, onClick, icon: Icon, label, count }) => (
  <button onClick={onClick} className={cn('flex items-center gap-2 px-4 py-2.5 rounded-lg font-medium transition-all', active ? 'bg-primary-100 text-primary-700 dark:bg-primary-700 dark:text-neutral-200' : 'text-neutral-600 hover:bg-neutral-100 dark:text-neutral-300 dark:hover:bg-primary-800')}>
    <Icon className="w-4 h-4" />{label}{count !== undefined && <Badge variant={active ? 'info' : 'neutral'} size="sm">{count}</Badge>}
  </button>
);

const LoadingTable: React.FC = () => (<div className="space-y-3 p-4">{[1, 2, 3, 4, 5].map((i) => (<div key={i} className="flex items-center gap-4"><div className="w-8 h-8 bg-neutral-200 animate-pulse rounded-lg dark:bg-primary-800" /><div className="flex-1 space-y-2"><div className="h-4 bg-neutral-200 animate-pulse rounded w-1/3 dark:bg-primary-800" /><div className="h-3 bg-neutral-100 animate-pulse rounded w-1/4 dark:bg-primary-800" /></div><div className="h-6 w-16 bg-neutral-200 animate-pulse rounded dark:bg-primary-800" /></div>))}</div>);

const JurisdictionTable: React.FC<{ jurisdictions: TaxJurisdiction[]; onEdit: (j: TaxJurisdiction) => void; loading?: boolean }> = ({ jurisdictions, onEdit, loading }) => {
  if (loading) return <LoadingTable />;
  return (
    <div className="overflow-x-auto"><table className="w-full"><thead><tr className="border-b border-neutral-200 dark:border-primary-800"><th className="text-left py-3 px-4 text-sm font-semibold text-neutral-700 dark:text-neutral-200">Jurisdiction</th><th className="text-left py-3 px-4 text-sm font-semibold text-neutral-700 dark:text-neutral-200">Country</th><th className="text-left py-3 px-4 text-sm font-semibold text-neutral-700 dark:text-neutral-200">Tax Authority</th><th className="text-center py-3 px-4 text-sm font-semibold text-neutral-700 dark:text-neutral-200">Supported Taxes</th><th className="text-left py-3 px-4 text-sm font-semibold text-neutral-700 dark:text-neutral-200">Currency</th><th className="text-left py-3 px-4 text-sm font-semibold text-neutral-700 dark:text-neutral-200">Status</th><th className="text-right py-3 px-4 text-sm font-semibold text-neutral-700 dark:text-neutral-200">Actions</th></tr></thead><tbody>
      {jurisdictions.map((j) => (<tr key={j.id} className="border-b border-neutral-100 hover:bg-neutral-50 dark:border-primary-800/60 dark:hover:bg-primary-800/50"><td className="py-3 px-4"><div className="flex items-center gap-2"><div className="w-8 h-8 rounded-lg bg-blue-50 flex items-center justify-center dark:bg-blue-500/10"><Globe className="w-4 h-4 text-blue-600 dark:text-blue-300" /></div><div><p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{j.jurisdictionName}</p><p className="text-xs text-neutral-500 font-mono dark:text-neutral-400">{j.jurisdictionCode}</p></div></div></td><td className="py-3 px-4"><Badge variant="neutral" size="sm">{j.countryCode}</Badge></td><td className="py-3 px-4"><p className="text-sm text-neutral-600 dark:text-neutral-300">{j.taxAuthorityName || '-'}</p></td><td className="py-3 px-4"><div className="flex justify-center gap-1">{j.supportsVat && <Badge variant="info" size="sm">VAT</Badge>}{j.supportsGst && <Badge variant="success" size="sm">GST</Badge>}{j.supportsWithholding && <Badge variant="warning" size="sm">WHT</Badge>}{j.supportsSalesTax && <Badge variant="accent" size="sm">Sales</Badge>}</div></td><td className="py-3 px-4"><Badge variant="neutral" size="sm">{j.reportingCurrency}</Badge></td><td className="py-3 px-4"><Badge variant={j.status === 'ACTIVE' ? 'success' : 'neutral'} size="sm">{j.status}</Badge></td><td className="py-3 px-4 text-right"><Button variant="ghost" size="sm" onClick={() => onEdit(j)}><Edit2 className="w-4 h-4" /></Button></td></tr>))}
      </tbody></table></div>
  );
};

const TaxConfigTable: React.FC<{ configs: TaxConfiguration[]; onEdit: (c: TaxConfiguration) => void; onDelete: (id: string) => void; loading?: boolean }> = ({ configs, onEdit, onDelete, loading }) => {
  if (loading) return <LoadingTable />;
  return (
    <div className="overflow-x-auto"><table className="w-full"><thead><tr className="border-b border-neutral-200 dark:border-primary-800"><th className="text-left py-3 px-4 text-sm font-semibold text-neutral-700 dark:text-neutral-200">Tax Code</th><th className="text-left py-3 px-4 text-sm font-semibold text-neutral-700 dark:text-neutral-200">Type</th><th className="text-left py-3 px-4 text-sm font-semibold text-neutral-700 dark:text-neutral-200">Jurisdiction</th><th className="text-right py-3 px-4 text-sm font-semibold text-neutral-700 dark:text-neutral-200">Rate</th><th className="text-center py-3 px-4 text-sm font-semibold text-neutral-700 dark:text-neutral-200">Applies To</th><th className="text-center py-3 px-4 text-sm font-semibold text-neutral-700 dark:text-neutral-200">Flags</th><th className="text-left py-3 px-4 text-sm font-semibold text-neutral-700 dark:text-neutral-200">Status</th><th className="text-right py-3 px-4 text-sm font-semibold text-neutral-700 dark:text-neutral-200">Actions</th></tr></thead><tbody>
      {configs.map((c) => { const typeConfig = TAX_TYPE_CONFIG[c.taxType] || TAX_TYPE_CONFIG.OTHER; const TypeIcon = typeConfig.icon; return (<tr key={c.id} className="border-b border-neutral-100 hover:bg-neutral-50 dark:border-primary-800/60 dark:hover:bg-primary-800/50"><td className="py-3 px-4"><div className="flex items-center gap-2"><div className={cn('w-8 h-8 rounded-lg flex items-center justify-center', typeConfig.bgColor)}><TypeIcon className={cn('w-4 h-4', typeConfig.color)} /></div><div><p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{c.taxName}</p><p className="text-xs text-neutral-500 font-mono dark:text-neutral-400">{c.taxCode}</p></div></div></td><td className="py-3 px-4"><Badge variant="info" size="sm">{typeConfig.label}</Badge></td><td className="py-3 px-4"><p className="text-sm text-neutral-600 dark:text-neutral-300">{c.jurisdictionCode}</p></td><td className="py-3 px-4 text-right"><p className="text-sm font-semibold text-primary-900 dark:text-neutral-50">{c.ratePercentage}%</p>{c.minimumAmount && <p className="text-xs text-neutral-500 dark:text-neutral-400">Min: {c.minimumAmount}</p>}</td><td className="py-3 px-4"><div className="flex justify-center gap-1">{c.appliesToPayables && <Badge variant="accent" size="sm">Payables</Badge>}{c.appliesToReceivables && <Badge variant="info" size="sm">Receivables</Badge>}</div></td><td className="py-3 px-4"><div className="flex justify-center gap-1">{c.isWithholding && <Badge variant="warning" size="sm">WHT</Badge>}{c.isRecoverable && <Badge variant="success" size="sm">Recoverable</Badge>}</div></td><td className="py-3 px-4"><Badge variant={c.status === 'ACTIVE' ? 'success' : 'neutral'} size="sm">{c.status}</Badge></td><td className="py-3 px-4 text-right"><div className="flex justify-end gap-1"><Button variant="ghost" size="sm" onClick={() => onEdit(c)}><Edit2 className="w-4 h-4" /></Button><Button variant="ghost" size="sm" onClick={() => onDelete(c.taxCode)}><Trash2 className="w-4 h-4 text-red-500" /></Button></div></td></tr>); })}
      </tbody></table></div>
  );
};

const ChargeConfigTable: React.FC<{ configs: ChargeConfiguration[]; onEdit: (c: ChargeConfiguration) => void; onDelete: (id: string) => void; loading?: boolean }> = ({ configs, onEdit, onDelete, loading }) => {
  if (loading) return <LoadingTable />;
  return (
    <div className="overflow-x-auto"><table className="w-full"><thead><tr className="border-b border-neutral-200 dark:border-primary-800"><th className="text-left py-3 px-4 text-sm font-semibold text-neutral-700 dark:text-neutral-200">Charge Code</th><th className="text-left py-3 px-4 text-sm font-semibold text-neutral-700 dark:text-neutral-200">Type</th><th className="text-left py-3 px-4 text-sm font-semibold text-neutral-700 dark:text-neutral-200">Category</th><th className="text-right py-3 px-4 text-sm font-semibold text-neutral-700 dark:text-neutral-200">Rate/Amount</th><th className="text-center py-3 px-4 text-sm font-semibold text-neutral-700 dark:text-neutral-200">Scope</th><th className="text-center py-3 px-4 text-sm font-semibold text-neutral-700 dark:text-neutral-200">Waivers</th><th className="text-left py-3 px-4 text-sm font-semibold text-neutral-700 dark:text-neutral-200">Status</th><th className="text-right py-3 px-4 text-sm font-semibold text-neutral-700 dark:text-neutral-200">Actions</th></tr></thead><tbody>
      {configs.map((c) => { const typeConfig = CHARGE_TYPE_CONFIG[c.chargeType] || CHARGE_TYPE_CONFIG.OTHER; const TypeIcon = typeConfig.icon; const catConfig = CHARGE_CATEGORY_CONFIG[c.chargeCategory] || CHARGE_CATEGORY_CONFIG.FIXED; return (<tr key={c.id} className="border-b border-neutral-100 hover:bg-neutral-50 dark:border-primary-800/60 dark:hover:bg-primary-800/50"><td className="py-3 px-4"><div className="flex items-center gap-2"><div className={cn('w-8 h-8 rounded-lg flex items-center justify-center', typeConfig.bgColor)}><TypeIcon className={cn('w-4 h-4', typeConfig.color)} /></div><div><p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{c.chargeName}</p><p className="text-xs text-neutral-500 font-mono dark:text-neutral-400">{c.chargeCode}</p></div></div></td><td className="py-3 px-4"><Badge variant="info" size="sm">{typeConfig.label}</Badge></td><td className="py-3 px-4"><Badge variant="neutral" size="sm">{catConfig.label}</Badge></td><td className="py-3 px-4 text-right">{c.chargeCategory === 'FIXED' ? <p className="text-sm font-semibold text-primary-900 dark:text-neutral-50">{formatCurrency(c.fixedAmount || 0, c.currencyCode)}</p> : c.chargeCategory === 'PERCENTAGE' ? <div><p className="text-sm font-semibold text-primary-900 dark:text-neutral-50">{c.percentageRate}%</p><p className="text-xs text-neutral-500 dark:text-neutral-400">{c.minimumCharge && `Min: ${c.minimumCharge}`}{c.maximumCharge && ` / Max: ${c.maximumCharge}`}</p></div> : <p className="text-sm text-neutral-600 dark:text-neutral-300">Tiered</p>}</td><td className="py-3 px-4"><div className="flex justify-center gap-1">{c.isCrossBorder && <Badge variant="accent" size="sm">Cross-Border</Badge>}{c.isDomestic && <Badge variant="info" size="sm">Domestic</Badge>}</div></td><td className="py-3 px-4"><div className="flex justify-center gap-1">{c.waiverForVip && <Badge variant="warning" size="sm">VIP</Badge>}{c.waiverThreshold && <Badge variant="success" size="sm">Threshold</Badge>}</div></td><td className="py-3 px-4"><Badge variant={c.status === 'ACTIVE' ? 'success' : 'neutral'} size="sm">{c.status}</Badge></td><td className="py-3 px-4 text-right"><div className="flex justify-end gap-1"><Button variant="ghost" size="sm" onClick={() => onEdit(c)}><Edit2 className="w-4 h-4" /></Button><Button variant="ghost" size="sm" onClick={() => onDelete(c.chargeCode)}><Trash2 className="w-4 h-4 text-red-500" /></Button></div></td></tr>); })}
      </tbody></table></div>
  );
};

const TaxConfigModal: React.FC<{ isOpen: boolean; onClose: () => void; config?: TaxConfiguration; jurisdictions: TaxJurisdiction[]; onSave: (data: Partial<TaxConfiguration>) => void; saving?: boolean }> = ({ isOpen, onClose, config, jurisdictions, onSave, saving }) => {
  const [formData, setFormData] = useState<Partial<TaxConfiguration>>({ taxCode: '', taxName: '', description: '', taxType: 'VAT', jurisdictionCode: 'UAE', taxCategory: 'STANDARD', ratePercentage: 5, appliesToPayables: true, appliesToReceivables: true, isWithholding: false, isRecoverable: true, recoveryPercentage: 100, status: 'ACTIVE', effectiveFrom: new Date().toISOString().split('T')[0] });
  useEffect(() => { if (config) setFormData(config); else setFormData({ taxCode: '', taxName: '', description: '', taxType: 'VAT', jurisdictionCode: jurisdictions[0]?.jurisdictionCode || 'UAE', taxCategory: 'STANDARD', ratePercentage: 5, appliesToPayables: true, appliesToReceivables: true, isWithholding: false, isRecoverable: true, recoveryPercentage: 100, status: 'ACTIVE', effectiveFrom: new Date().toISOString().split('T')[0] }); }, [config, isOpen, jurisdictions]);
  const handleSubmit = () => { if (!formData.taxCode || !formData.taxName) { toast.error('Tax code and name are required'); return; } onSave(formData); };
  return (
    <Modal isOpen={isOpen} onClose={onClose} title={config ? 'Edit Tax Configuration' : 'Create Tax Configuration'} size="lg">
      <div className="p-4 space-y-4 max-h-[70vh] overflow-y-auto">
        <div className="grid grid-cols-2 gap-4"><div><label className="field-label block mb-1">Tax Code *</label><Input value={formData.taxCode || ''} onChange={(e) => setFormData({ ...formData, taxCode: e.target.value })} placeholder="e.g., VAT5_UAE" disabled={!!config} /></div><div><label className="field-label block mb-1">Tax Name *</label><Input value={formData.taxName || ''} onChange={(e) => setFormData({ ...formData, taxName: e.target.value })} placeholder="e.g., UAE VAT Standard" /></div></div>
        <div><label className="field-label block mb-1">Description</label><textarea className="w-full border border-neutral-300 rounded-lg px-3 py-2 resize-none dark:border-primary-700" rows={2} value={formData.description || ''} onChange={(e) => setFormData({ ...formData, description: e.target.value })} placeholder="Optional description..." /></div>
        <div className="grid grid-cols-3 gap-4"><div><label className="field-label block mb-1">Tax Type *</label><select className="w-full border border-neutral-300 rounded-lg px-3 py-2 dark:border-primary-700" value={formData.taxType} onChange={(e) => setFormData({ ...formData, taxType: e.target.value as TaxType })}>{Object.entries(TAX_TYPE_CONFIG).map(([key, cfg]) => (<option key={key} value={key}>{cfg.label}</option>))}</select></div><div><label className="field-label block mb-1">Jurisdiction *</label><select className="w-full border border-neutral-300 rounded-lg px-3 py-2 dark:border-primary-700" value={formData.jurisdictionCode} onChange={(e) => setFormData({ ...formData, jurisdictionCode: e.target.value })}>{jurisdictions.map((j) => (<option key={j.jurisdictionCode} value={j.jurisdictionCode}>{j.jurisdictionName}</option>))}</select></div><div><label className="field-label block mb-1">Category *</label><select className="w-full border border-neutral-300 rounded-lg px-3 py-2 dark:border-primary-700" value={formData.taxCategory} onChange={(e) => setFormData({ ...formData, taxCategory: e.target.value as TaxCategory })}><option value="STANDARD">Standard</option><option value="REDUCED">Reduced</option><option value="ZERO">Zero-Rated</option><option value="EXEMPT">Exempt</option><option value="SPECIAL">Special</option></select></div></div>
        <div className="grid grid-cols-3 gap-4"><div><label className="field-label block mb-1">Rate (%) *</label><Input type="number" step="0.01" value={formData.ratePercentage || 0} onChange={(e) => setFormData({ ...formData, ratePercentage: parseFloat(e.target.value) })} /></div><div><label className="field-label block mb-1">Minimum Amount</label><Input type="number" value={formData.minimumAmount || ''} onChange={(e) => setFormData({ ...formData, minimumAmount: e.target.value ? parseFloat(e.target.value) : undefined })} placeholder="Optional" /></div><div><label className="field-label block mb-1">Maximum Amount</label><Input type="number" value={formData.maximumAmount || ''} onChange={(e) => setFormData({ ...formData, maximumAmount: e.target.value ? parseFloat(e.target.value) : undefined })} placeholder="Optional" /></div></div>
        <div className="border-t pt-4"><h4 className="text-sm font-semibold mb-3">Applicability</h4><div className="grid grid-cols-2 gap-4"><label className="flex items-center gap-2"><input type="checkbox" checked={formData.appliesToPayables} onChange={(e) => setFormData({ ...formData, appliesToPayables: e.target.checked })} className="rounded text-primary-600 dark:text-primary-200" /><span className="text-sm">Applies to Payables</span></label><label className="flex items-center gap-2"><input type="checkbox" checked={formData.appliesToReceivables} onChange={(e) => setFormData({ ...formData, appliesToReceivables: e.target.checked })} className="rounded text-primary-600 dark:text-primary-200" /><span className="text-sm">Applies to Receivables</span></label><label className="flex items-center gap-2"><input type="checkbox" checked={formData.isWithholding} onChange={(e) => setFormData({ ...formData, isWithholding: e.target.checked })} className="rounded text-primary-600 dark:text-primary-200" /><span className="text-sm">Is Withholding Tax</span></label><label className="flex items-center gap-2"><input type="checkbox" checked={formData.isRecoverable} onChange={(e) => setFormData({ ...formData, isRecoverable: e.target.checked })} className="rounded text-primary-600 dark:text-primary-200" /><span className="text-sm">Is Recoverable</span></label></div></div>
        {formData.isRecoverable && <div><label className="field-label block mb-1">Recovery Percentage</label><Input type="number" step="0.01" max="100" value={formData.recoveryPercentage || 100} onChange={(e) => setFormData({ ...formData, recoveryPercentage: parseFloat(e.target.value) })} /></div>}
        <div className="grid grid-cols-3 gap-4"><div><label className="field-label block mb-1">Effective From *</label><Input type="date" value={formData.effectiveFrom || ''} onChange={(e) => setFormData({ ...formData, effectiveFrom: e.target.value })} /></div><div><label className="field-label block mb-1">Effective To</label><Input type="date" value={formData.effectiveTo || ''} onChange={(e) => setFormData({ ...formData, effectiveTo: e.target.value || undefined })} /></div><div><label className="field-label block mb-1">Status</label><select className="w-full border border-neutral-300 rounded-lg px-3 py-2 dark:border-primary-700" value={formData.status} onChange={(e) => setFormData({ ...formData, status: e.target.value as TaxStatus })}><option value="ACTIVE">Active</option><option value="INACTIVE">Inactive</option><option value="PENDING">Pending</option></select></div></div>
        <div className="flex justify-end gap-2 pt-4 border-t"><Button variant="ghost" onClick={onClose} disabled={saving}>Cancel</Button><Button onClick={handleSubmit} disabled={saving}>{saving ? <Loader2 className="w-4 h-4 mr-1 animate-spin" /> : <Check className="w-4 h-4 mr-1" />}{config ? 'Update' : 'Create'} Tax Config</Button></div>
      </div>
    </Modal>
  );
};

const ChargeConfigModal: React.FC<{ isOpen: boolean; onClose: () => void; config?: ChargeConfiguration; onSave: (data: Partial<ChargeConfiguration>) => void; saving?: boolean }> = ({ isOpen, onClose, config, onSave, saving }) => {
  const [formData, setFormData] = useState<Partial<ChargeConfiguration>>({ chargeCode: '', chargeName: '', description: '', chargeType: 'PROCESSING_FEE', chargeCategory: 'FIXED', fixedAmount: 0, percentageRate: 0, currencyCode: 'AED', isCrossBorder: false, isDomestic: true, waiverForVip: false, status: 'ACTIVE', effectiveFrom: new Date().toISOString().split('T')[0] });
  useEffect(() => { if (config) setFormData(config); else setFormData({ chargeCode: '', chargeName: '', description: '', chargeType: 'PROCESSING_FEE', chargeCategory: 'FIXED', fixedAmount: 0, percentageRate: 0, currencyCode: 'AED', isCrossBorder: false, isDomestic: true, waiverForVip: false, status: 'ACTIVE', effectiveFrom: new Date().toISOString().split('T')[0] }); }, [config, isOpen]);
  const handleSubmit = () => { if (!formData.chargeCode || !formData.chargeName) { toast.error('Charge code and name are required'); return; } onSave(formData); };
  return (
    <Modal isOpen={isOpen} onClose={onClose} title={config ? 'Edit Charge Configuration' : 'Create Charge Configuration'} size="lg">
      <div className="p-4 space-y-4 max-h-[70vh] overflow-y-auto">
        <div className="grid grid-cols-2 gap-4"><div><label className="field-label block mb-1">Charge Code *</label><Input value={formData.chargeCode || ''} onChange={(e) => setFormData({ ...formData, chargeCode: e.target.value })} placeholder="e.g., SWIFT_STD" disabled={!!config} /></div><div><label className="field-label block mb-1">Charge Name *</label><Input value={formData.chargeName || ''} onChange={(e) => setFormData({ ...formData, chargeName: e.target.value })} placeholder="e.g., SWIFT Transfer Fee" /></div></div>
        <div><label className="field-label block mb-1">Description</label><textarea className="w-full border border-neutral-300 rounded-lg px-3 py-2 resize-none dark:border-primary-700" rows={2} value={formData.description || ''} onChange={(e) => setFormData({ ...formData, description: e.target.value })} placeholder="Optional description..." /></div>
        <div className="grid grid-cols-3 gap-4"><div><label className="field-label block mb-1">Charge Type *</label><select className="w-full border border-neutral-300 rounded-lg px-3 py-2 dark:border-primary-700" value={formData.chargeType} onChange={(e) => setFormData({ ...formData, chargeType: e.target.value as ChargeType })}>{Object.entries(CHARGE_TYPE_CONFIG).map(([key, cfg]) => (<option key={key} value={key}>{cfg.label}</option>))}</select></div><div><label className="field-label block mb-1">Category *</label><select className="w-full border border-neutral-300 rounded-lg px-3 py-2 dark:border-primary-700" value={formData.chargeCategory} onChange={(e) => setFormData({ ...formData, chargeCategory: e.target.value as ChargeCategory })}>{Object.entries(CHARGE_CATEGORY_CONFIG).map(([key, cfg]) => (<option key={key} value={key}>{cfg.label} - {cfg.description}</option>))}</select></div><div><label className="field-label block mb-1">Currency</label><select className="w-full border border-neutral-300 rounded-lg px-3 py-2 dark:border-primary-700" value={formData.currencyCode} onChange={(e) => setFormData({ ...formData, currencyCode: e.target.value })}><option value="AED">AED</option><option value="USD">USD</option><option value="EUR">EUR</option><option value="GBP">GBP</option><option value="SAR">SAR</option></select></div></div>
        {formData.chargeCategory === 'FIXED' && <div><label className="field-label block mb-1">Fixed Amount *</label><Input type="number" step="0.01" value={formData.fixedAmount || 0} onChange={(e) => setFormData({ ...formData, fixedAmount: parseFloat(e.target.value) })} /></div>}
        {(formData.chargeCategory === 'PERCENTAGE' || formData.chargeCategory === 'SLIDING_SCALE') && <div className="grid grid-cols-3 gap-4"><div><label className="field-label block mb-1">Percentage Rate *</label><Input type="number" step="0.01" value={formData.percentageRate || 0} onChange={(e) => setFormData({ ...formData, percentageRate: parseFloat(e.target.value) })} /></div><div><label className="field-label block mb-1">Minimum Charge</label><Input type="number" value={formData.minimumCharge || ''} onChange={(e) => setFormData({ ...formData, minimumCharge: e.target.value ? parseFloat(e.target.value) : undefined })} placeholder="Optional" /></div><div><label className="field-label block mb-1">Maximum Charge</label><Input type="number" value={formData.maximumCharge || ''} onChange={(e) => setFormData({ ...formData, maximumCharge: e.target.value ? parseFloat(e.target.value) : undefined })} placeholder="Optional" /></div></div>}
        {formData.chargeCategory === 'TIERED' && <div className="border rounded-lg p-3 bg-neutral-50 dark:bg-primary-950"><h4 className="text-sm font-semibold mb-2">Tier Configuration</h4><p className="text-xs text-neutral-500 mb-2 dark:text-neutral-400">Define volume-based tiers with rates</p><div className="space-y-2">{(formData.tierConfig || [{ from: 0, to: 10000, rate: 0.5 }]).map((tier, idx) => (<div key={idx} className="grid grid-cols-4 gap-2 items-center"><Input type="number" placeholder="From" value={tier.from} onChange={(e) => { const newTiers = [...(formData.tierConfig || [])]; newTiers[idx] = { ...tier, from: parseFloat(e.target.value) }; setFormData({ ...formData, tierConfig: newTiers }); }} /><Input type="number" placeholder="To" value={tier.to || ''} onChange={(e) => { const newTiers = [...(formData.tierConfig || [])]; newTiers[idx] = { ...tier, to: e.target.value ? parseFloat(e.target.value) : undefined }; setFormData({ ...formData, tierConfig: newTiers }); }} /><Input type="number" step="0.01" placeholder="Rate %" value={tier.rate} onChange={(e) => { const newTiers = [...(formData.tierConfig || [])]; newTiers[idx] = { ...tier, rate: parseFloat(e.target.value) }; setFormData({ ...formData, tierConfig: newTiers }); }} /><Button variant="ghost" size="sm" onClick={() => { const newTiers = (formData.tierConfig || []).filter((_, i) => i !== idx); setFormData({ ...formData, tierConfig: newTiers }); }}><Trash2 className="w-4 h-4 text-red-500" /></Button></div>))}<Button variant="outline" size="sm" onClick={() => { const newTiers = [...(formData.tierConfig || []), { from: 0, to: undefined, rate: 0 }]; setFormData({ ...formData, tierConfig: newTiers }); }}><Plus className="w-4 h-4 mr-1" />Add Tier</Button></div></div>}
        <div className="border-t pt-4"><h4 className="text-sm font-semibold mb-3">Scope & Applicability</h4><div className="grid grid-cols-2 gap-4"><label className="flex items-center gap-2"><input type="checkbox" checked={formData.isCrossBorder} onChange={(e) => setFormData({ ...formData, isCrossBorder: e.target.checked })} className="rounded text-primary-600 dark:text-primary-200" /><span className="text-sm">Cross-Border Payments</span></label><label className="flex items-center gap-2"><input type="checkbox" checked={formData.isDomestic} onChange={(e) => setFormData({ ...formData, isDomestic: e.target.checked })} className="rounded text-primary-600 dark:text-primary-200" /><span className="text-sm">Domestic Payments</span></label></div></div>
        <div className="border-t pt-4"><h4 className="text-sm font-semibold mb-3">Waiver Rules</h4><div className="grid grid-cols-2 gap-4"><label className="flex items-center gap-2"><input type="checkbox" checked={formData.waiverForVip} onChange={(e) => setFormData({ ...formData, waiverForVip: e.target.checked })} className="rounded text-primary-600 dark:text-primary-200" /><span className="text-sm">Waive for VIP Customers</span></label><div><label className="field-label block mb-1">Waiver Threshold</label><Input type="number" value={formData.waiverThreshold || ''} onChange={(e) => setFormData({ ...formData, waiverThreshold: e.target.value ? parseFloat(e.target.value) : undefined })} placeholder="Amount above which to waive" /></div></div></div>
        <div className="grid grid-cols-3 gap-4"><div><label className="field-label block mb-1">Effective From *</label><Input type="date" value={formData.effectiveFrom || ''} onChange={(e) => setFormData({ ...formData, effectiveFrom: e.target.value })} /></div><div><label className="field-label block mb-1">Effective To</label><Input type="date" value={formData.effectiveTo || ''} onChange={(e) => setFormData({ ...formData, effectiveTo: e.target.value || undefined })} /></div><div><label className="field-label block mb-1">Status</label><select className="w-full border border-neutral-300 rounded-lg px-3 py-2 dark:border-primary-700" value={formData.status} onChange={(e) => setFormData({ ...formData, status: e.target.value as ChargeStatus })}><option value="ACTIVE">Active</option><option value="INACTIVE">Inactive</option><option value="PENDING">Pending</option></select></div></div>
        <div className="flex justify-end gap-2 pt-4 border-t"><Button variant="ghost" onClick={onClose} disabled={saving}>Cancel</Button><Button onClick={handleSubmit} disabled={saving}>{saving ? <Loader2 className="w-4 h-4 mr-1 animate-spin" /> : <Check className="w-4 h-4 mr-1" />}{config ? 'Update' : 'Create'} Charge Config</Button></div>
      </div>
    </Modal>
  );
};

const TaxChargesSetupPage: React.FC = () => {
  const [activeTab, setActiveTab] = useState<'jurisdictions' | 'taxes' | 'charges'>('taxes');
  const [jurisdictions, setJurisdictions] = useState<TaxJurisdiction[]>([]);
  const [taxConfigs, setTaxConfigs] = useState<TaxConfiguration[]>([]);
  const [chargeConfigs, setChargeConfigs] = useState<ChargeConfiguration[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [useMockData, setUseMockData] = useState(false);
  const [showTaxModal, setShowTaxModal] = useState(false);
  const [showChargeModal, setShowChargeModal] = useState(false);
  const [editingTax, setEditingTax] = useState<TaxConfiguration | undefined>();
  const [editingCharge, setEditingCharge] = useState<ChargeConfiguration | undefined>();
  const [filterStatus, setFilterStatus] = useState<string>('ALL');
  const [filterJurisdiction, setFilterJurisdiction] = useState<string>('ALL');

  const fetchData = useCallback(async () => {
    setLoading(true);
    try {
      const [jurisdictionsData, taxConfigsData, chargeConfigsData] = await Promise.all([taxChargesApi.getJurisdictions(), taxChargesApi.getTaxConfigs(), taxChargesApi.getChargeConfigs()]);
      setJurisdictions(Array.isArray(jurisdictionsData) ? jurisdictionsData : []);
      setTaxConfigs(Array.isArray(taxConfigsData) ? taxConfigsData : []);
      setChargeConfigs(Array.isArray(chargeConfigsData) ? chargeConfigsData : []);
      setUseMockData(false);
    } catch (error) {
      console.warn('API unavailable, using mock data:', error);
      setJurisdictions(MOCK_JURISDICTIONS);
      setTaxConfigs(MOCK_TAX_CONFIGS);
      setChargeConfigs(MOCK_CHARGE_CONFIGS);
      setUseMockData(true);
      toast.error('Backend unavailable - using demo data');
    } finally { setLoading(false); }
  }, []);

  useEffect(() => { fetchData(); }, [fetchData]);

  const filteredTaxConfigs = taxConfigs.filter(c => { const matchesSearch = !searchQuery || c.taxCode.toLowerCase().includes(searchQuery.toLowerCase()) || c.taxName.toLowerCase().includes(searchQuery.toLowerCase()); const matchesStatus = filterStatus === 'ALL' || c.status === filterStatus; const matchesJurisdiction = filterJurisdiction === 'ALL' || c.jurisdictionCode === filterJurisdiction; return matchesSearch && matchesStatus && matchesJurisdiction; });
  const filteredChargeConfigs = chargeConfigs.filter(c => { const matchesSearch = !searchQuery || c.chargeCode.toLowerCase().includes(searchQuery.toLowerCase()) || c.chargeName.toLowerCase().includes(searchQuery.toLowerCase()); const matchesStatus = filterStatus === 'ALL' || c.status === filterStatus; return matchesSearch && matchesStatus; });

  const handleSaveTax = async (data: Partial<TaxConfiguration>) => {
    setSaving(true);
    try {
      if (useMockData) { if (editingTax) { setTaxConfigs(prev => prev.map(c => c.id === editingTax.id ? { ...c, ...data } as TaxConfiguration : c)); } else { const newConfig: TaxConfiguration = { ...data, id: Date.now().toString(), createdAt: new Date().toISOString() } as TaxConfiguration; setTaxConfigs(prev => [...prev, newConfig]); } toast.success(editingTax ? 'Tax configuration updated' : 'Tax configuration created'); }
      else { await taxChargesApi.createTaxConfig(data); toast.success(editingTax ? 'Tax configuration updated' : 'Tax configuration created'); await fetchData(); }
      setShowTaxModal(false); setEditingTax(undefined);
    } catch (error) { console.error('Failed to save tax config:', error); toast.error('Failed to save tax configuration'); } finally { setSaving(false); }
  };

  const handleSaveCharge = async (data: Partial<ChargeConfiguration>) => {
    setSaving(true);
    try {
      if (useMockData) { if (editingCharge) { setChargeConfigs(prev => prev.map(c => c.id === editingCharge.id ? { ...c, ...data } as ChargeConfiguration : c)); } else { const newConfig: ChargeConfiguration = { ...data, id: Date.now().toString(), createdAt: new Date().toISOString() } as ChargeConfiguration; setChargeConfigs(prev => [...prev, newConfig]); } toast.success(editingCharge ? 'Charge configuration updated' : 'Charge configuration created'); }
      else { await taxChargesApi.createChargeConfig(data); toast.success(editingCharge ? 'Charge configuration updated' : 'Charge configuration created'); await fetchData(); }
      setShowChargeModal(false); setEditingCharge(undefined);
    } catch (error) { console.error('Failed to save charge config:', error); toast.error('Failed to save charge configuration'); } finally { setSaving(false); }
  };

  const handleDeleteTax = async (taxCode: string) => { if (!confirm('Are you sure you want to delete this tax configuration?')) return; try { if (useMockData) { setTaxConfigs(prev => prev.filter(c => c.taxCode !== taxCode)); } else { await taxChargesApi.deleteTaxConfig(taxCode); await fetchData(); } toast.success('Tax configuration deleted'); } catch (error) { console.error('Failed to delete tax config:', error); toast.error('Failed to delete tax configuration'); } };
  const handleDeleteCharge = async (chargeCode: string) => { if (!confirm('Are you sure you want to delete this charge configuration?')) return; try { if (useMockData) { setChargeConfigs(prev => prev.filter(c => c.chargeCode !== chargeCode)); } else { await taxChargesApi.deleteChargeConfig(chargeCode); await fetchData(); } toast.success('Charge configuration deleted'); } catch (error) { console.error('Failed to delete charge config:', error); toast.error('Failed to delete charge configuration'); } };

  const stats = { activeTaxConfigs: taxConfigs.filter(c => c.status === 'ACTIVE').length, activeChargeConfigs: chargeConfigs.filter(c => c.status === 'ACTIVE').length, jurisdictions: jurisdictions.filter(j => j.status === 'ACTIVE').length, withholdingTaxes: taxConfigs.filter(c => c.isWithholding && c.status === 'ACTIVE').length };

  return (
    <div className="space-y-6">
      <PageHeader
        title="Tax & Charges Setup"
        description={<>Configure tax rates, jurisdictions, and fee schedules{useMockData && <Badge variant="warning" size="sm" className="ml-2">Demo Mode</Badge>}</>}
        actions={<><Button variant="outline" onClick={() => console.log('Export configs')}><Download className="w-4 h-4 mr-1" />Export</Button><Button variant="outline" onClick={() => console.log('Import configs')}><Upload className="w-4 h-4 mr-1" />Import</Button><Button variant="outline" onClick={fetchData} disabled={loading}><RefreshCw className={cn('w-4 h-4 mr-1', loading && 'animate-spin')} />Refresh</Button><Button onClick={() => { if (activeTab === 'taxes') { setEditingTax(undefined); setShowTaxModal(true); } else if (activeTab === 'charges') { setEditingCharge(undefined); setShowChargeModal(true); } }}><Plus className="w-4 h-4 mr-1" />{activeTab === 'taxes' ? 'Add Tax Config' : activeTab === 'charges' ? 'Add Charge' : 'Add Jurisdiction'}</Button></>}
      />
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4"><StatCard label="Active Tax Configs" value={stats.activeTaxConfigs} icon={Receipt} color="text-blue-600 dark:text-blue-300" bgColor="bg-blue-50 dark:bg-blue-500/10" loading={loading} /><StatCard label="Active Charges" value={stats.activeChargeConfigs} icon={DollarSign} color="text-green-600 dark:text-green-300" bgColor="bg-green-50 dark:bg-green-500/10" loading={loading} /><StatCard label="Jurisdictions" value={stats.jurisdictions} icon={Globe} color="text-cat-2" bgColor="bg-cat-2-soft dark:bg-cat-2/15" loading={loading} /><StatCard label="Withholding Taxes" value={stats.withholdingTaxes} icon={Shield} color="text-warning-600 dark:text-warning-300" bgColor="bg-warning-50 dark:bg-warning-500/10" loading={loading} /></div>
      <div className="bg-white rounded-xl shadow-sm border border-neutral-100 dark:bg-primary-900 dark:border-primary-800/60">
        <div className="flex items-center gap-2 p-4 border-b border-neutral-200 dark:border-primary-800"><TabButton active={activeTab === 'taxes'} onClick={() => setActiveTab('taxes')} icon={Receipt} label="Tax Configurations" count={taxConfigs.length} /><TabButton active={activeTab === 'charges'} onClick={() => setActiveTab('charges')} icon={DollarSign} label="Charge Configurations" count={chargeConfigs.length} /><TabButton active={activeTab === 'jurisdictions'} onClick={() => setActiveTab('jurisdictions')} icon={Globe} label="Jurisdictions" count={jurisdictions.length} /></div>
        <div className="flex items-center gap-4 p-4 bg-neutral-50 border-b border-neutral-200 dark:bg-primary-950 dark:border-primary-800"><div className="flex-1 relative"><Search className="absolute left-3 top-1/2 transform -translate-y-1/2 w-4 h-4 text-neutral-400 dark:text-neutral-500" /><Input className="pl-10" placeholder={`Search ${activeTab}...`} value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} /></div><select className="border border-neutral-300 rounded-lg px-3 py-2 text-sm dark:border-primary-700" value={filterStatus} onChange={(e) => setFilterStatus(e.target.value)}><option value="ALL">All Status</option><option value="ACTIVE">Active</option><option value="INACTIVE">Inactive</option><option value="PENDING">Pending</option></select>{activeTab === 'taxes' && <select className="border border-neutral-300 rounded-lg px-3 py-2 text-sm dark:border-primary-700" value={filterJurisdiction} onChange={(e) => setFilterJurisdiction(e.target.value)}><option value="ALL">All Jurisdictions</option>{jurisdictions.map(j => (<option key={j.jurisdictionCode} value={j.jurisdictionCode}>{j.jurisdictionName}</option>))}</select>}</div>
        <div className="p-4">{activeTab === 'jurisdictions' && <JurisdictionTable jurisdictions={jurisdictions} onEdit={() => {}} loading={loading} />}{activeTab === 'taxes' && <TaxConfigTable configs={filteredTaxConfigs} onEdit={(c) => { setEditingTax(c); setShowTaxModal(true); }} onDelete={handleDeleteTax} loading={loading} />}{activeTab === 'charges' && <ChargeConfigTable configs={filteredChargeConfigs} onEdit={(c) => { setEditingCharge(c); setShowChargeModal(true); }} onDelete={handleDeleteCharge} loading={loading} />}</div>
      </div>
      <div className="bg-white rounded-xl p-4 shadow-sm border border-neutral-100 dark:bg-primary-900 dark:border-primary-800/60"><h3 className="text-sm font-semibold text-primary-900 mb-3 dark:text-neutral-50">Quick Reference</h3><div className="grid grid-cols-2 md:grid-cols-4 gap-4"><div className="p-3 bg-blue-50 rounded-lg dark:bg-blue-500/10"><p className="text-xs text-blue-600 font-medium dark:text-blue-300">UAE VAT</p><p className="text-lg font-semibold text-blue-800 dark:text-blue-300">5%</p></div><div className="p-3 bg-green-50 rounded-lg dark:bg-green-500/10"><p className="text-xs text-green-600 font-medium dark:text-green-300">KSA VAT</p><p className="text-lg font-semibold text-green-800 dark:text-green-300">15%</p></div><div className="p-3 bg-cat-2-soft rounded-lg dark:bg-cat-2/15"><p className="text-xs text-cat-2 font-medium">UK VAT</p><p className="text-lg font-semibold text-cat-2">20%</p></div><div className="p-3 bg-warning-50 rounded-lg dark:bg-warning-500/10"><p className="text-xs text-warning-600 font-medium dark:text-warning-300">India GST</p><p className="text-lg font-semibold text-warning-800 dark:text-warning-300">18%</p></div></div></div>
      <TaxConfigModal isOpen={showTaxModal} onClose={() => { setShowTaxModal(false); setEditingTax(undefined); }} config={editingTax} jurisdictions={jurisdictions} onSave={handleSaveTax} saving={saving} />
      <ChargeConfigModal isOpen={showChargeModal} onClose={() => { setShowChargeModal(false); setEditingCharge(undefined); }} config={editingCharge} onSave={handleSaveCharge} saving={saving} />
    </div>
  );
};

export default TaxChargesSetupPage;