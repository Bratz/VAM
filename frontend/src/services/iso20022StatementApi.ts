// ============================================================================
// ISO20022 STATEMENT API SERVICE
// ============================================================================
// Comprehensive API client for ISO20022 camt.053/054 bank statement operations
// Supports:
// - camt.053 (Bank to Customer Statement) - end-of-day account statements
// - camt.054 (Bank to Customer Debit/Credit Notification) - real-time notifications
// - Aggregated statements for parent/child VA hierarchies
// - XML export for ERP/treasury system integration
// ============================================================================

import { apiClient, ApiResponse } from './api';

// ============================================================================
// TYPES
// ============================================================================

/**
 * ISO20022 camt.053 Statement Entry (Transaction)
 */
export interface Camt053Entry {
  entryReference: string;
  amount: number;
  currency: string;
  creditDebitIndicator: 'CRDT' | 'DBIT';
  status: 'BOOK' | 'PDNG' | 'INFO';
  bookingDate: string;
  valueDate: string;
  bankTransactionCode?: {
    domain?: string;
    family?: string;
    subFamily?: string;
  };
  entryDetails?: {
    transactionDetails?: {
      references?: {
        messageId?: string;
        accountServicerReference?: string;
        paymentInformationId?: string;
        instructionId?: string;
        endToEndId?: string;
        uetr?: string;
      };
      relatedParties?: {
        debtor?: {
          name?: string;
          id?: string;
        };
        creditor?: {
          name?: string;
          id?: string;
        };
      };
      remittanceInformation?: {
        unstructured?: string[];
        structured?: any[];
      };
    }[];
  };
}

/**
 * ISO20022 camt.053 Balance
 */
export interface Camt053Balance {
  type: 'OPBD' | 'CLBD' | 'ITBD' | 'CLAV' | 'FWAV' | 'INFO';
  typeDescription: string;
  amount: number;
  currency: string;
  creditDebitIndicator: 'CRDT' | 'DBIT';
  date: string;
}

/**
 * ISO20022 camt.053 Statement Response
 */
export interface Camt053Statement {
  // Statement identification
  statementId: string;
  electronicSequenceNumber?: number;
  legalSequenceNumber?: number;
  creationDateTime: string;

  // Account information
  account: {
    id: string;
    iban?: string;
    currency: string;
    name?: string;
    owner?: string;
  };

  // Date range
  fromDate: string;
  toDate: string;

  // Balances
  balances: Camt053Balance[];
  openingBalance: number;
  closingBalance: number;

  // Transactions summary
  totalCredits: number;
  totalDebits: number;
  creditCount: number;
  debitCount: number;

  // Transaction entries
  entries: Camt053Entry[];

  // Additional info
  additionalStatementInformation?: string;
}

/**
 * ISO20022 camt.054 Notification Entry
 */
export interface Camt054Entry {
  entryReference: string;
  amount: number;
  currency: string;
  creditDebitIndicator: 'CRDT' | 'DBIT';
  status: 'BOOK' | 'PDNG';
  bookingDate: string;
  valueDate: string;
  accountServicerReference?: string;
  bankTransactionCode?: {
    domain?: string;
    family?: string;
    subFamily?: string;
  };
  entryDetails?: {
    transactionDetails?: {
      references?: {
        endToEndId?: string;
        uetr?: string;
        instructionId?: string;
      };
      relatedParties?: {
        debtor?: { name?: string; };
        creditor?: { name?: string; };
      };
      remittanceInformation?: {
        unstructured?: string[];
      };
    }[];
  };
}

/**
 * ISO20022 camt.054 Notification Response
 */
export interface Camt054Notification {
  notificationId: string;
  creationDateTime: string;
  account: {
    id: string;
    iban?: string;
    currency: string;
    name?: string;
  };
  fromDate: string;
  toDate: string;
  entries: Camt054Entry[];
  totalCredits: number;
  totalDebits: number;
  creditCount: number;
  debitCount: number;
}

/**
 * VA Hierarchy Node for aggregated statements
 */
export interface VaHierarchyNode {
  id: string;
  vaNumber: string;
  viban?: string;
  vaName: string;
  accountCategory: string;
  currencyCode: string;
  currentBalance: number;
  availableBalance: number;
  hierarchyLevel: number;
  parentAccountId?: string;
  childCount: number;
  children?: VaHierarchyNode[];
}

/**
 * Aggregated Balance Response
 */
export interface AggregatedBalance {
  vaId: string;
  vaNumber: string;
  vaName: string;
  asOfDate: string;
  currencyCode: string;
  ownBalance: number;
  ownAvailableBalance: number;
  aggregatedBalance: number;
  aggregatedAvailableBalance: number;
  aggregatedInBaseCurrency?: number;
  baseCurrency?: string;
  childAccountCount: number;
  childBalances?: {
    vaId: string;
    vaNumber: string;
    vaName: string;
    currencyCode: string;
    currentBalance: number;
    availableBalance: number;
    balanceInBase?: number;
  }[];
}

/**
 * Aggregated Statement (camt.053 with child VAs)
 */
export interface AggregatedStatement extends Camt053Statement {
  isAggregated: true;
  includesChildren: boolean;
  childStatements?: {
    vaId: string;
    vaNumber: string;
    vaName: string;
    currencyCode: string;
    openingBalance: number;
    closingBalance: number;
    totalCredits: number;
    totalDebits: number;
    entryCount: number;
  }[];
  aggregatedTotals: {
    totalCredits: number;
    totalDebits: number;
    netChange: number;
  };
}

// ============================================================================
// ISO20022 STATEMENT API
// ============================================================================

export const iso20022StatementApi = {
  // ========================================================================
  // camt.053 - Bank to Customer Statement
  // ========================================================================

  /**
   * Get camt.053 statement for a virtual account (JSON format)
   * @param vaId Virtual account ID
   * @param fromDate Start date (YYYY-MM-DD)
   * @param toDate End date (YYYY-MM-DD)
   * @param format Response format (default: json)
   */
  getStatement: async (
    vaId: string,
    fromDate: string,
    toDate: string,
    format: 'json' | 'xml' = 'json'
  ): Promise<ApiResponse<Camt053Statement>> => {
    const response = await apiClient.get<ApiResponse<Camt053Statement>>(
      `/iso20022/camt053/va/${vaId}`,
      { params: { fromDate, toDate, format } }
    );
    return response.data;
  },

  /**
   * Download camt.053 statement as XML file
   * Returns a blob for file download
   * @param vaId Virtual account ID
   * @param fromDate Start date (YYYY-MM-DD)
   * @param toDate End date (YYYY-MM-DD)
   */
  downloadStatementXml: async (
    vaId: string,
    fromDate: string,
    toDate: string
  ): Promise<Blob> => {
    const response = await apiClient.get(
      `/iso20022/camt053/va/${vaId}/xml`,
      {
        params: { fromDate, toDate },
        responseType: 'blob',
        headers: { 'Accept': 'application/xml' }
      }
    );
    return response.data;
  },

  /**
   * Get aggregated camt.053 statement for parent VA including children
   * @param vaId Virtual account ID (typically an aggregation account)
   * @param fromDate Start date (YYYY-MM-DD)
   * @param toDate End date (YYYY-MM-DD)
   * @param includeChildren Whether to include child VA transactions
   */
  getAggregatedStatement: async (
    vaId: string,
    fromDate: string,
    toDate: string,
    includeChildren: boolean = true
  ): Promise<ApiResponse<AggregatedStatement>> => {
    const response = await apiClient.get<ApiResponse<AggregatedStatement>>(
      `/iso20022/camt053/va/${vaId}/aggregated`,
      { params: { fromDate, toDate, includeChildren } }
    );
    return response.data;
  },

  // ========================================================================
  // camt.054 - Bank to Customer Debit/Credit Notification
  // ========================================================================

  /**
   * Get camt.054 notifications for a virtual account
   * Real-time credit/debit notifications
   * @param vaId Virtual account ID
   * @param fromDate Start date (YYYY-MM-DD)
   * @param toDate End date (YYYY-MM-DD)
   */
  getNotifications: async (
    vaId: string,
    fromDate: string,
    toDate: string
  ): Promise<ApiResponse<Camt054Notification>> => {
    const response = await apiClient.get<ApiResponse<Camt054Notification>>(
      `/iso20022/camt054/va/${vaId}`,
      { params: { fromDate, toDate } }
    );
    return response.data;
  },

  // ========================================================================
  // Hierarchy & Balance Aggregation
  // ========================================================================

  /**
   * Get VA hierarchy tree for a given account
   * @param vaId Virtual account ID (root or any node)
   */
  getVaHierarchy: async (vaId: string): Promise<ApiResponse<VaHierarchyNode>> => {
    const response = await apiClient.get<ApiResponse<VaHierarchyNode>>(
      `/statements/va/${vaId}/hierarchy`
    );
    return response.data;
  },

  /**
   * Get aggregated balance for a VA including all children
   * @param vaId Virtual account ID
   * @param asOfDate Optional date for historical balance (default: current)
   */
  getAggregatedBalance: async (
    vaId: string,
    asOfDate?: string
  ): Promise<ApiResponse<AggregatedBalance>> => {
    const response = await apiClient.get<ApiResponse<AggregatedBalance>>(
      `/statements/va/${vaId}/balance`,
      { params: asOfDate ? { asOfDate } : {} }
    );
    return response.data;
  },
};

// ============================================================================
// HELPER FUNCTIONS
// ============================================================================

/**
 * Download XML statement as a file
 * @param vaId Virtual account ID
 * @param fromDate Start date
 * @param toDate End date
 * @param filename Optional custom filename
 */
export const downloadStatementAsFile = async (
  vaId: string,
  fromDate: string,
  toDate: string,
  filename?: string
): Promise<void> => {
  const blob = await iso20022StatementApi.downloadStatementXml(vaId, fromDate, toDate);

  // Create download link
  const url = window.URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename || `camt053_${vaId}_${fromDate}_${toDate}.xml`;

  // Trigger download
  document.body.appendChild(link);
  link.click();

  // Cleanup
  document.body.removeChild(link);
  window.URL.revokeObjectURL(url);
};

/**
 * Format camt.053 balance type code to readable text
 */
export const formatBalanceType = (type: string): string => {
  const typeMap: Record<string, string> = {
    'OPBD': 'Opening Booked',
    'CLBD': 'Closing Booked',
    'ITBD': 'Interim Booked',
    'CLAV': 'Closing Available',
    'FWAV': 'Forward Available',
    'INFO': 'Information',
  };
  return typeMap[type] || type;
};

/**
 * Format credit/debit indicator
 */
export const formatCreditDebit = (indicator: 'CRDT' | 'DBIT'): string => {
  return indicator === 'CRDT' ? 'Credit' : 'Debit';
};

/**
 * Check if an entry is a credit
 */
export const isCredit = (entry: Camt053Entry | Camt054Entry): boolean => {
  return entry.creditDebitIndicator === 'CRDT';
};

/**
 * Check if an entry is a debit
 */
export const isDebit = (entry: Camt053Entry | Camt054Entry): boolean => {
  return entry.creditDebitIndicator === 'DBIT';
};

/**
 * Get signed amount based on credit/debit indicator
 */
export const getSignedAmount = (entry: Camt053Entry | Camt054Entry): number => {
  return isDebit(entry) ? -entry.amount : entry.amount;
};

export default iso20022StatementApi;
