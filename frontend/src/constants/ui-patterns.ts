// ============================================================================
// UI PATTERN CONSTANTS
// Standard UI patterns and components for consistency across pages
// ============================================================================

import React from 'react';

// ============================================================================
// 1. PAGE HEADER PATTERNS
// ============================================================================

export interface PageHeaderConfig {
  title: string;
  subtitle: string;
  primaryAction?: {
    label: string;
    icon: string;
    onClick?: () => void;
  };
  secondaryActions?: {
    label: string;
    icon: string;
    variant: 'outline' | 'ghost';
    onClick?: () => void;
  }[];
}

// Standard page headers for each section
export const PAGE_HEADERS: Record<string, PageHeaderConfig> = {
  // Account Management
  'virtual-accounts': {
    title: 'Virtual Accounts',
    subtitle: 'Manage virtual accounts for your corporate structure',
    primaryAction: { label: 'Create Account', icon: 'Plus' },
    secondaryActions: [
      { label: 'Export', icon: 'Download', variant: 'outline' },
    ],
  },
  'parties': {
    title: 'Parties',
    subtitle: 'Manage customers, vendors, employees, and other counterparties',
    primaryAction: { label: 'Add Party', icon: 'Plus' },
    secondaryActions: [
      { label: 'Export', icon: 'Download', variant: 'outline' },
    ],
  },
  'transactions': {
    title: 'Transactions',
    subtitle: 'View and manage all account transactions',
    primaryAction: { label: 'New Transfer', icon: 'ArrowUpRight' },
    secondaryActions: [
      { label: 'Export', icon: 'Download', variant: 'outline' },
      { label: 'Refresh', icon: 'RefreshCw', variant: 'outline' },
    ],
  },
  
  // Reconciliation
  'receivables': {
    title: 'Receivables',
    subtitle: 'Manage invoices, e-commerce collections, and payment matching',
    primaryAction: { label: 'Create Invoice', icon: 'Plus' },
    secondaryActions: [
      { label: 'Refresh', icon: 'RefreshCw', variant: 'outline' },
    ],
  },
  'payables': {
    title: 'Payables',
    subtitle: 'Manage vendor invoices and payment processing',
    primaryAction: { label: 'Create Payment', icon: 'Plus' },
    secondaryActions: [
      { label: 'Refresh', icon: 'RefreshCw', variant: 'outline' },
    ],
  },
  
  // Programs
  'escrow': {
    title: 'Digital Escrow',
    subtitle: 'Secure transaction holding for goods, services, and real estate',
    primaryAction: { label: 'Create Contract', icon: 'Plus' },
  },
  'wallet': {
    title: 'Wallet Programs',
    subtitle: 'Manage prepaid wallet programs and holder accounts',
    primaryAction: { label: 'Create Program', icon: 'Plus' },
  },
  
  // Treasury (Virtual Accounts)
  'balance-structure': {
    title: 'Balance Structure',
    subtitle: 'Consolidated view of group virtual accounts and positions',
    secondaryActions: [
      { label: 'Refresh', icon: 'RefreshCw', variant: 'outline' },
      { label: 'Export', icon: 'Download', variant: 'outline' },
    ],
  },
  'ihb': {
    title: 'In-House Bank',
    subtitle: 'Internal funding between group entities via virtual accounts',
    primaryAction: { label: 'Add Participant', icon: 'Plus' },
    secondaryActions: [
      { label: 'Calculate Interest', icon: 'RefreshCw', variant: 'outline' },
    ],
  },
  'netting': {
    title: 'Netting Cycles',
    subtitle: 'Intercompany obligation netting between group entities',
    primaryAction: { label: 'Create Cycle', icon: 'Plus' },
    secondaryActions: [
      { label: 'Run Netting', icon: 'Play', variant: 'outline' },
    ],
  },
  
  // Physical Accounts
  'physical-accounts': {
    title: 'Physical Bank Accounts',
    subtitle: 'Manage real bank accounts for treasury operations, pooling & cash concentration',
    primaryAction: { label: 'Add Account', icon: 'Plus' },
    secondaryActions: [
      { label: 'Sync All', icon: 'RefreshCw', variant: 'outline' },
      { label: 'Export', icon: 'Download', variant: 'outline' },
    ],
  },
  'pooling': {
    title: 'Notional Pooling',
    subtitle: 'Interest optimization on physical bank accounts at the same bank',
    primaryAction: { label: 'Create Pool', icon: 'Plus' },
    secondaryActions: [
      { label: 'Run Calculations', icon: 'RefreshCw', variant: 'outline' },
    ],
  },
  'sweeping': {
    title: 'Cash Concentration',
    subtitle: 'Automated sweeping between physical bank accounts',
    primaryAction: { label: 'Create Rule', icon: 'Plus' },
    secondaryActions: [
      { label: 'Run All Sweeps', icon: 'Play', variant: 'outline' },
    ],
  },
  
  // Reports
  'statements': {
    title: 'Statements',
    subtitle: 'Generate and download account statements',
    primaryAction: { label: 'Generate Statement', icon: 'FileText' },
  },
};

// ============================================================================
// 2. INFO BANNER CONFIGURATIONS
// ============================================================================

export interface InfoBannerConfig {
  title: string;
  description: string;
  icon: string;
  color: 'primary' | 'info' | 'success' | 'warning' | 'error' | 'accent';
}

export const INFO_BANNERS: Record<string, InfoBannerConfig> = {
  'ihb': {
    title: 'Virtual Account Based Funding',
    description: 'The In-House Bank operates on virtual accounts. Group Treasury borrows from subsidiaries with surplus (paying them interest) and lends to subsidiaries with deficit (charging them interest). Actual settlement happens periodically via physical bank accounts.',
    icon: 'PiggyBank',
    color: 'primary',
  },
  'pooling': {
    title: 'Physical Bank Account Pooling',
    description: 'Notional pooling optimizes interest by offsetting credit and debit balances across physical bank accounts at the same bank. No actual fund movement occurs. For internal funding between group entities, use the In-House Bank.',
    icon: 'Building2',
    color: 'info',
  },
  'sweeping': {
    title: 'Physical Fund Movement',
    description: 'Cash concentration involves actual fund transfers between physical bank accounts (ZBA, target balance, threshold sweeps). For internal funding between subsidiaries via virtual accounts, use the In-House Bank.',
    icon: 'Building2',
    color: 'accent',
  },
  'physical-accounts': {
    title: 'Physical Bank Accounts',
    description: 'These are real accounts held at external banks. They are used for Notional Pooling (interest optimization) and Cash Concentration (physical sweeps). Intercompany funding operates on Virtual Accounts via the In-House Bank.',
    icon: 'Landmark',
    color: 'info',
  },
  'balance-structure': {
    title: 'Group Balance Hierarchy',
    description: 'This view shows the consolidated balance structure of all group entities and their virtual accounts. Intercompany positions are tracked separately in the In-House Bank module.',
    icon: 'Layers',
    color: 'primary',
  },
  'netting': {
    title: 'Intercompany Netting',
    description: 'Netting cycles offset intercompany payables and receivables between group entities to minimize actual settlements. Only the net positions are settled, reducing transaction costs and FX exposure.',
    icon: 'ArrowLeftRight',
    color: 'info',
  },
  'escrow': {
    title: 'Secure Transaction Holding',
    description: 'Digital escrow provides secure holding of funds for transactions between buyers and sellers. Funds are released upon milestone completion or dispute resolution.',
    icon: 'Shield',
    color: 'success',
  },
  'wallet': {
    title: 'Prepaid Wallet Programs',
    description: 'Wallet programs enable prepaid accounts for employees, consumers, or partners. Each wallet is a virtual account with configurable limits and KYC requirements.',
    icon: 'Wallet',
    color: 'primary',
  },
};

// ============================================================================
// 3. STATUS CONFIGURATIONS
// ============================================================================

export const STATUS_VARIANTS = {
  // General statuses
  ACTIVE: { variant: 'success', label: 'Active' },
  INACTIVE: { variant: 'neutral', label: 'Inactive' },
  SUSPENDED: { variant: 'warning', label: 'Suspended' },
  BLOCKED: { variant: 'error', label: 'Blocked' },
  PENDING: { variant: 'warning', label: 'Pending' },
  DRAFT: { variant: 'neutral', label: 'Draft' },
  ARCHIVED: { variant: 'neutral', label: 'Archived' },
  
  // KYC statuses
  VERIFIED: { variant: 'success', label: 'Verified' },
  EXPIRED: { variant: 'error', label: 'Expired' },
  EXEMPTED: { variant: 'info', label: 'Exempted' },
  REJECTED: { variant: 'error', label: 'Rejected' },
  IN_PROGRESS: { variant: 'warning', label: 'In Progress' },
  
  // Risk ratings
  LOW: { variant: 'success', label: 'Low Risk' },
  MEDIUM: { variant: 'warning', label: 'Medium Risk' },
  HIGH: { variant: 'error', label: 'High Risk' },
  PROHIBITED: { variant: 'error', label: 'Prohibited' },
  
  // Position statuses
  SURPLUS: { variant: 'success', label: 'Surplus' },
  DEFICIT: { variant: 'error', label: 'Deficit' },
  NEUTRAL: { variant: 'neutral', label: 'Neutral' },
  
  // Transaction statuses
  COMPLETED: { variant: 'success', label: 'Completed' },
  FAILED: { variant: 'error', label: 'Failed' },
  PROCESSING: { variant: 'warning', label: 'Processing' },
  REVERSED: { variant: 'neutral', label: 'Reversed' },
  
  // Sync statuses
  SYNCED: { variant: 'success', label: 'Synced' },
  ERROR: { variant: 'error', label: 'Error' },
  
  // Payment statuses
  PAID: { variant: 'success', label: 'Paid' },
  PARTIAL: { variant: 'warning', label: 'Partial' },
  OVERDUE: { variant: 'error', label: 'Overdue' },
  DISPUTED: { variant: 'error', label: 'Disputed' },
  OPEN: { variant: 'info', label: 'Open' },
} as const;

export type StatusKey = keyof typeof STATUS_VARIANTS;

export const getStatusConfig = (status: string) => {
  return STATUS_VARIANTS[status as StatusKey] || { variant: 'neutral', label: status };
};

// ============================================================================
// 4. TABLE ACTION PATTERNS
// ============================================================================

export const TABLE_ACTIONS = {
  // Standard row actions
  VIEW: { icon: 'Eye', label: 'View', variant: 'ghost' },
  EDIT: { icon: 'Edit', label: 'Edit', variant: 'ghost' },
  DELETE: { icon: 'Trash2', label: 'Delete', variant: 'ghost' },
  MORE: { icon: 'MoreHorizontal', label: 'More', variant: 'ghost' },
  
  // Specific actions
  APPROVE: { icon: 'Check', label: 'Approve', variant: 'ghost' },
  REJECT: { icon: 'X', label: 'Reject', variant: 'ghost' },
  DOWNLOAD: { icon: 'Download', label: 'Download', variant: 'ghost' },
  COPY: { icon: 'Copy', label: 'Copy', variant: 'ghost' },
  REFRESH: { icon: 'RefreshCw', label: 'Refresh', variant: 'ghost' },
  CONFIGURE: { icon: 'Settings', label: 'Configure', variant: 'ghost' },
};

// ============================================================================
// 5. MODAL SIZES
// ============================================================================

export const MODAL_SIZES = {
  sm: 'max-w-md',
  md: 'max-w-lg',
  lg: 'max-w-2xl',
  xl: 'max-w-4xl',
  full: 'max-w-6xl',
} as const;

// ============================================================================
// 6. CURRENCY CONFIGURATIONS
// ============================================================================

export const CURRENCIES = {
  AED: { code: 'AED', symbol: 'د.إ', name: 'UAE Dirham', decimals: 2 },
  USD: { code: 'USD', symbol: '$', name: 'US Dollar', decimals: 2 },
  EUR: { code: 'EUR', symbol: '€', name: 'Euro', decimals: 2 },
  GBP: { code: 'GBP', symbol: '£', name: 'British Pound', decimals: 2 },
  SGD: { code: 'SGD', symbol: 'S$', name: 'Singapore Dollar', decimals: 2 },
  SAR: { code: 'SAR', symbol: '﷼', name: 'Saudi Riyal', decimals: 2 },
  QAR: { code: 'QAR', symbol: 'ر.ق', name: 'Qatari Riyal', decimals: 2 },
  KWD: { code: 'KWD', symbol: 'د.ك', name: 'Kuwaiti Dinar', decimals: 3 },
  BHD: { code: 'BHD', symbol: 'د.ب', name: 'Bahraini Dinar', decimals: 3 },
  OMR: { code: 'OMR', symbol: 'ر.ع', name: 'Omani Rial', decimals: 3 },
} as const;

export type CurrencyCode = keyof typeof CURRENCIES;

// ============================================================================
// 7. DATE FORMATS
// ============================================================================

export const DATE_FORMATS = {
  display: 'DD MMM YYYY',      // 15 Jan 2024
  displayTime: 'DD MMM YYYY HH:mm', // 15 Jan 2024 14:30
  input: 'YYYY-MM-DD',         // 2024-01-15
  api: 'YYYY-MM-DDTHH:mm:ssZ', // ISO format
} as const;

// ============================================================================
// 8. ROLE ICONS
// ============================================================================

export const ROLE_ICONS = {
  CUSTOMER: 'ShoppingBag',
  VENDOR: 'Truck',
  EMPLOYEE: 'UserCircle',
  GOVERNMENT: 'Building',
  FINANCIAL: 'Landmark',
} as const;

export const ENTITY_TYPE_ICONS = {
  PARENT: 'Building2',
  SUBSIDIARY: 'GitBranch',
  BRANCH: 'MapPin',
  DIVISION: 'Layers',
} as const;