// ============================================================================
// USE ISO20022 STATEMENT HOOK
// ============================================================================
// React hook for ISO20022 camt.053/054 statement operations
// Provides unified interface for statement retrieval, export, and hierarchy
// ============================================================================

import { useState, useCallback } from 'react';
import { statementsApi } from '../services/api';

// ============================================================================
// TYPES
// ============================================================================

export interface Camt053Statement {
  statementId: string;
  electronicSequenceNumber?: number;
  legalSequenceNumber?: number;
  creationDateTime: string;
  account: {
    id: string;
    iban?: string;
    currency: string;
    name?: string;
    owner?: string;
  };
  fromDate: string;
  toDate: string;
  balances: {
    type: string;
    typeDescription: string;
    amount: number;
    currency: string;
    creditDebitIndicator: 'CRDT' | 'DBIT';
    date: string;
  }[];
  openingBalance: number;
  closingBalance: number;
  totalCredits: number;
  totalDebits: number;
  creditCount: number;
  debitCount: number;
  entries: Camt053Entry[];
  additionalStatementInformation?: string;
}

export interface Camt053Entry {
  entryReference: string;
  amount: number;
  currency: string;
  creditDebitIndicator: 'CRDT' | 'DBIT';
  status: 'BOOK' | 'PDNG' | 'INFO';
  bookingDate: string;
  valueDate: string;
  balanceAfter?: number;
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
        debtor?: { name?: string; id?: string; };
        creditor?: { name?: string; id?: string; };
      };
      remittanceInformation?: {
        unstructured?: string[];
        structured?: any[];
      };
    }[];
  };
  additionalEntryInfo?: string;
}

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
  childAccountCount: number;
  childBalances?: {
    vaId: string;
    vaNumber: string;
    vaName: string;
    currencyCode: string;
    currentBalance: number;
    availableBalance: number;
  }[];
}

export type StatementFormat = 'json' | 'xml' | 'pdf' | 'csv' | 'mt940';

interface UseISO20022StatementResult {
  // State
  statement: Camt053Statement | null;
  notifications: any | null;
  hierarchy: VaHierarchyNode | null;
  aggregatedBalance: AggregatedBalance | null;
  loading: boolean;
  downloading: boolean;
  error: string | null;

  // Actions
  fetchCamt053Statement: (vaId: string, fromDate: string, toDate: string) => Promise<void>;
  fetchCamt054Notifications: (vaId: string, fromDate: string, toDate: string) => Promise<void>;
  fetchAggregatedStatement: (vaId: string, fromDate: string, toDate: string, includeChildren?: boolean) => Promise<void>;
  fetchHierarchy: (vaId: string) => Promise<void>;
  fetchAggregatedBalance: (vaId: string, asOfDate?: string) => Promise<void>;
  downloadXml: (vaId: string, fromDate: string, toDate: string, filename?: string) => Promise<void>;
  generateStatement: (vaId: string, format: StatementFormat, fromDate: string, toDate: string) => Promise<string | null>;
  clearError: () => void;
  reset: () => void;
}

// ============================================================================
// HOOK IMPLEMENTATION
// ============================================================================

export function useISO20022Statement(): UseISO20022StatementResult {
  const [statement, setStatement] = useState<Camt053Statement | null>(null);
  const [notifications, setNotifications] = useState<any | null>(null);
  const [hierarchy, setHierarchy] = useState<VaHierarchyNode | null>(null);
  const [aggregatedBalance, setAggregatedBalance] = useState<AggregatedBalance | null>(null);
  const [loading, setLoading] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Fetch camt.053 statement
  const fetchCamt053Statement = useCallback(async (
    vaId: string,
    fromDate: string,
    toDate: string
  ) => {
    setLoading(true);
    setError(null);
    try {
      const res = await statementsApi.getCamt053Statement(vaId, fromDate, toDate);
      if (res.success) {
        setStatement(res.data);
      } else {
        throw new Error(res.message || 'Failed to fetch statement');
      }
    } catch (err: any) {
      setError(err.message || 'Failed to fetch camt.053 statement');
      setStatement(null);
    } finally {
      setLoading(false);
    }
  }, []);

  // Fetch camt.054 notifications
  const fetchCamt054Notifications = useCallback(async (
    vaId: string,
    fromDate: string,
    toDate: string
  ) => {
    setLoading(true);
    setError(null);
    try {
      const res = await statementsApi.getCamt054Notifications(vaId, fromDate, toDate);
      if (res.success) {
        setNotifications(res.data);
      } else {
        throw new Error(res.message || 'Failed to fetch notifications');
      }
    } catch (err: any) {
      setError(err.message || 'Failed to fetch camt.054 notifications');
      setNotifications(null);
    } finally {
      setLoading(false);
    }
  }, []);

  // Fetch aggregated statement
  const fetchAggregatedStatement = useCallback(async (
    vaId: string,
    fromDate: string,
    toDate: string,
    includeChildren: boolean = true
  ) => {
    setLoading(true);
    setError(null);
    try {
      const res = await statementsApi.getAggregatedCamt053Statement(
        vaId, fromDate, toDate, includeChildren
      );
      if (res.success) {
        setStatement(res.data);
      } else {
        throw new Error(res.message || 'Failed to fetch aggregated statement');
      }
    } catch (err: any) {
      setError(err.message || 'Failed to fetch aggregated statement');
      setStatement(null);
    } finally {
      setLoading(false);
    }
  }, []);

  // Fetch VA hierarchy
  const fetchHierarchy = useCallback(async (vaId: string) => {
    setLoading(true);
    setError(null);
    try {
      const res = await statementsApi.getVaHierarchy(vaId);
      if (res.success) {
        setHierarchy(res.data);
      } else {
        throw new Error(res.message || 'Failed to fetch hierarchy');
      }
    } catch (err: any) {
      setError(err.message || 'Failed to fetch VA hierarchy');
      setHierarchy(null);
    } finally {
      setLoading(false);
    }
  }, []);

  // Fetch aggregated balance
  const fetchAggregatedBalance = useCallback(async (vaId: string, asOfDate?: string) => {
    setLoading(true);
    setError(null);
    try {
      const res = await statementsApi.getVaAggregatedBalance(vaId, asOfDate);
      if (res.success) {
        setAggregatedBalance(res.data);
      } else {
        throw new Error(res.message || 'Failed to fetch aggregated balance');
      }
    } catch (err: any) {
      setError(err.message || 'Failed to fetch aggregated balance');
      setAggregatedBalance(null);
    } finally {
      setLoading(false);
    }
  }, []);

  // Download XML statement
  const downloadXml = useCallback(async (
    vaId: string,
    fromDate: string,
    toDate: string,
    filename?: string
  ) => {
    setDownloading(true);
    setError(null);
    try {
      const blob = await statementsApi.downloadCamt053Xml(vaId, fromDate, toDate);

      // Create download link
      const url = window.URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = filename || `camt053_${vaId}_${fromDate}_${toDate}.xml`;

      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      window.URL.revokeObjectURL(url);
    } catch (err: any) {
      setError(`Failed to download XML: ${err.message}`);
    } finally {
      setDownloading(false);
    }
  }, []);

  // Generate statement in various formats
  const generateStatement = useCallback(async (
    vaId: string,
    format: StatementFormat,
    fromDate: string,
    toDate: string
  ): Promise<string | null> => {
    setLoading(true);
    setError(null);
    try {
      const res = await statementsApi.generate(vaId, format.toUpperCase(), fromDate, toDate);
      if (res.success) {
        return res.data.statementReference;
      } else {
        throw new Error(res.message || 'Failed to generate statement');
      }
    } catch (err: any) {
      setError(err.message || 'Failed to generate statement');
      return null;
    } finally {
      setLoading(false);
    }
  }, []);

  // Clear error
  const clearError = useCallback(() => {
    setError(null);
  }, []);

  // Reset all state
  const reset = useCallback(() => {
    setStatement(null);
    setNotifications(null);
    setHierarchy(null);
    setAggregatedBalance(null);
    setLoading(false);
    setDownloading(false);
    setError(null);
  }, []);

  return {
    statement,
    notifications,
    hierarchy,
    aggregatedBalance,
    loading,
    downloading,
    error,
    fetchCamt053Statement,
    fetchCamt054Notifications,
    fetchAggregatedStatement,
    fetchHierarchy,
    fetchAggregatedBalance,
    downloadXml,
    generateStatement,
    clearError,
    reset,
  };
}

// ============================================================================
// HELPER FUNCTIONS
// ============================================================================

/**
 * Check if an entry is a credit
 */
export const isCamt053Credit = (entry: Camt053Entry): boolean => {
  return entry.creditDebitIndicator === 'CRDT';
};

/**
 * Check if an entry is a debit
 */
export const isCamt053Debit = (entry: Camt053Entry): boolean => {
  return entry.creditDebitIndicator === 'DBIT';
};

/**
 * Get signed amount from camt.053 entry
 */
export const getCamt053SignedAmount = (entry: Camt053Entry): number => {
  return isCamt053Debit(entry) ? -entry.amount : entry.amount;
};

/**
 * Format balance type code to human-readable text
 */
export const formatBalanceType = (type: string): string => {
  const typeMap: Record<string, string> = {
    'OPBD': 'Opening Booked',
    'CLBD': 'Closing Booked',
    'ITBD': 'Interim Booked',
    'CLAV': 'Closing Available',
    'FWAV': 'Forward Available',
    'INFO': 'Information',
    'PRCD': 'Previously Closed Booked',
    'XPCD': 'Expected',
  };
  return typeMap[type] || type;
};

/**
 * Format entry status
 */
export const formatEntryStatus = (status: string): string => {
  const statusMap: Record<string, string> = {
    'BOOK': 'Booked',
    'PDNG': 'Pending',
    'INFO': 'Information',
  };
  return statusMap[status] || status;
};

/**
 * Extract remittance information from entry
 */
export const extractRemittanceInfo = (entry: Camt053Entry): string => {
  const txDetails = entry.entryDetails?.transactionDetails?.[0];
  if (txDetails?.remittanceInformation?.unstructured?.length) {
    return txDetails.remittanceInformation.unstructured.join(' ');
  }
  return entry.additionalEntryInfo || '';
};

/**
 * Extract counterparty name from entry
 */
export const extractCounterpartyName = (entry: Camt053Entry): string => {
  const txDetails = entry.entryDetails?.transactionDetails?.[0];
  if (isCamt053Credit(entry)) {
    return txDetails?.relatedParties?.debtor?.name || 'Unknown Sender';
  }
  return txDetails?.relatedParties?.creditor?.name || 'Unknown Recipient';
};

/**
 * Extract end-to-end reference from entry
 */
export const extractE2EReference = (entry: Camt053Entry): string | undefined => {
  return entry.entryDetails?.transactionDetails?.[0]?.references?.endToEndId;
};

/**
 * Flatten hierarchy to list of all nodes
 */
export const flattenHierarchy = (node: VaHierarchyNode): VaHierarchyNode[] => {
  const result: VaHierarchyNode[] = [node];
  if (node.children) {
    for (const child of node.children) {
      result.push(...flattenHierarchy(child));
    }
  }
  return result;
};

/**
 * Get total balance across hierarchy
 */
export const getHierarchyTotalBalance = (node: VaHierarchyNode): number => {
  let total = node.currentBalance;
  if (node.children) {
    for (const child of node.children) {
      total += getHierarchyTotalBalance(child);
    }
  }
  return total;
};

export default useISO20022Statement;
