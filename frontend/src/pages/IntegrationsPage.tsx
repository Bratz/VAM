import React, { useState, useEffect, useCallback } from 'react';
import {
  Plus,
  Settings,
  RefreshCw,
  ChevronRight,
  ExternalLink,
  Upload,
  Download,
  Play,
  Pause,
  Trash2,
  FileText,
  Key,
  ArrowRight,
  ArrowLeftRight,
  Check,
  X,
  Loader2,
  Shield,
  Search,
  Filter,
  ChevronDown,
  Clock,
  Activity,
  AlertTriangle,
  CheckCircle2,
  XCircle,
  Link2,
  Unlink,
  Building2,
  Globe,
  CreditCard,
  Database,
  Zap,
} from 'lucide-react';
import { Card, Button, Badge, Input } from '../components/ui';
import { Page } from '../components/layout/Page';
import { Modal } from '../components/ui/enhanced';
import { formatRelativeTime, cn } from '../utils';
import { getConnectorIcon } from '../components/ConnectorIcons';

// ============================================================================
// TYPES
// ============================================================================

type ConnectorCategory = 'ERP' | 'TREASURY' | 'BANKING' | 'OPEN_BANKING' | 'PAYMENTS' | 'GENERIC';
type AuthType = 'OAUTH' | 'OAUTH2_AUTHCODE' | 'API_KEY' | 'BASIC' | 'CERTIFICATE' | 'PSD2_CONSENT' | 'OPEN_BANKING_UK' | 'BERLIN_GROUP' | 'STET' | 'POLISH_API' | 'MTLS';
type ConnectorStatus = 'AVAILABLE' | 'BETA' | 'COMING_SOON' | 'DEPRECATED';
type ConnectionStatus = 'CONNECTED' | 'DISCONNECTED' | 'ERROR' | 'SYNCING';
type SyncFrequency = 'REAL_TIME' | 'HOURLY' | 'DAILY' | 'MANUAL';
type Environment = 'SANDBOX' | 'PRODUCTION';
type FlowDirection = 'INBOUND' | 'OUTBOUND' | 'BIDIRECTIONAL';
type FlowStatus = 'ACTIVE' | 'PAUSED' | 'ERROR';
type SyncStatus = 'SUCCESS' | 'FAILED' | 'PARTIAL' | 'RUNNING';

interface Connector {
  id: string;
  connectorCode: string;
  connectorName: string;
  shortName: string;
  description: string;
  category: ConnectorCategory;
  authType: AuthType;
  status: ConnectorStatus;
  supportedFeatures: string[];
  documentationUrl?: string;
  iconType: string;
  region?: string;
  complianceStandards?: string[];
  isBeta: boolean;
  isConnected: boolean;
  activeConnectionCount: number;
}

interface Connection {
  id: string;
  connectorId: string;
  connectorCode: string;
  connectorName: string;
  iconType: string;
  connectionName: string;
  environment: Environment;
  syncFrequency: SyncFrequency;
  lastSyncAt?: string;
  nextSyncAt?: string;
  status: ConnectionStatus;
  errorMessage?: string;
  dataFlowCount: number;
  createdAt: string;
  createdBy: string;
  dataFlows?: DataFlow[];
  consentInfo?: ConsentInfo;
}

interface ConsentInfo {
  consentId: string;
  consentStatus: string;
  consentCreatedAt: string;
  consentExpiresAt: string;
  permissions: string[];
  aspspName: string;
}

interface DataFlow {
  id: string;
  flowName: string;
  direction: FlowDirection;
  sourceEntity: string;
  targetEntity: string;
  scheduleEnabled: boolean;
  lastRunAt?: string;
  recordsProcessed?: number;
  status: FlowStatus;
  fieldMappings?: FieldMapping[];
}

interface FieldMapping {
  id: string;
  sourceField: string;
  targetField: string;
  transformation?: string;
  isRequired: boolean;
}

interface SyncLog {
  id: string;
  connectionId: string;
  connectionName: string;
  flowId?: string;
  flowName: string;
  direction: FlowDirection;
  startTime: string;
  endTime?: string;
  durationMs?: number;
  recordsProcessed: number;
  recordsFailed: number;
  status: SyncStatus;
  errorMessage?: string;
}

interface IntegrationStats {
  totalConnections: number;
  activeConnections: number;
  errorConnections: number;
  syncingConnections: number;
  totalDataFlows: number;
  activeDataFlows: number;
  availableConnectors: number;
  categoryStats: {
    erpConnections: number;
    treasuryConnections: number;
    bankingConnections: number;
    openBankingConnections: number;
    paymentsConnections: number;
    genericConnections: number;
  };
}

// ============================================================================
// MOCK DATA - Will be replaced with API calls
// ============================================================================

const mockConnectors: Connector[] = [
  // ERP
  { id: '1', connectorCode: 'SAP_S4HANA', connectorName: 'SAP S/4HANA', shortName: 'SAP', description: 'Real-time ERP integration for financial data synchronization with SAP S/4HANA Cloud and On-Premise', category: 'ERP', authType: 'OAUTH', status: 'AVAILABLE', supportedFeatures: ['GL Accounts', 'Bank Accounts', 'Cash Positions', 'Journal Entries', 'Intercompany'], iconType: 'sap', region: 'GLOBAL', complianceStandards: ['SOX', 'GDPR'], isBeta: false, isConnected: true, activeConnectionCount: 1 },
  { id: '2', connectorCode: 'ORACLE_FUSION', connectorName: 'Oracle Fusion Cloud', shortName: 'Oracle', description: 'Enterprise cloud ERP integration for Oracle Fusion Cloud Financials', category: 'ERP', authType: 'OAUTH', status: 'AVAILABLE', supportedFeatures: ['GL Accounts', 'Cash Management', 'Bank Statements', 'Payments'], iconType: 'oracle', region: 'GLOBAL', complianceStandards: ['SOX', 'GDPR'], isBeta: false, isConnected: false, activeConnectionCount: 0 },
  { id: '3', connectorCode: 'MS_DYNAMICS_365', connectorName: 'Microsoft Dynamics 365', shortName: 'Dynamics', description: 'Connect to Dynamics 365 Finance & Operations for comprehensive financial management', category: 'ERP', authType: 'OAUTH', status: 'AVAILABLE', supportedFeatures: ['GL Accounts', 'Bank Accounts', 'Cash Flow', 'Vendor Payments'], iconType: 'microsoft', region: 'GLOBAL', complianceStandards: ['SOX', 'GDPR'], isBeta: false, isConnected: true, activeConnectionCount: 1 },
  { id: '4', connectorCode: 'NETSUITE', connectorName: 'Oracle NetSuite', shortName: 'NetSuite', description: 'Cloud ERP integration for mid-market companies', category: 'ERP', authType: 'OAUTH', status: 'AVAILABLE', supportedFeatures: ['GL Accounts', 'Bank Feeds', 'Cash Management', 'Subsidiaries'], iconType: 'netsuite', region: 'GLOBAL', isBeta: false, isConnected: false, activeConnectionCount: 0 },
  { id: '5', connectorCode: 'WORKDAY', connectorName: 'Workday Financial Management', shortName: 'Workday', description: 'Enterprise cloud applications for finance and planning', category: 'ERP', authType: 'OAUTH', status: 'BETA', supportedFeatures: ['GL Accounts', 'Cash Management', 'Banking', 'Settlements'], iconType: 'workday', region: 'GLOBAL', isBeta: true, isConnected: false, activeConnectionCount: 0 },
  
  // Treasury
  { id: '6', connectorCode: 'KYRIBA', connectorName: 'Kyriba Treasury', shortName: 'Kyriba', description: 'Treasury management system integration for cash visibility', category: 'TREASURY', authType: 'API_KEY', status: 'AVAILABLE', supportedFeatures: ['Cash Positions', 'Forecasts', 'Bank Connectivity', 'Payments'], iconType: 'kyriba', region: 'GLOBAL', isBeta: false, isConnected: true, activeConnectionCount: 1 },
  { id: '7', connectorCode: 'FIS_QUANTUM', connectorName: 'FIS Quantum', shortName: 'FIS', description: 'Enterprise treasury and risk management platform', category: 'TREASURY', authType: 'CERTIFICATE', status: 'AVAILABLE', supportedFeatures: ['Cash Management', 'Risk Analytics', 'Trade Finance'], iconType: 'fis', region: 'GLOBAL', isBeta: false, isConnected: false, activeConnectionCount: 0 },
  { id: '8', connectorCode: 'ION_TREASURY', connectorName: 'ION Treasury', shortName: 'ION', description: 'Treasury management and trading platform', category: 'TREASURY', authType: 'OAUTH', status: 'AVAILABLE', supportedFeatures: ['Cash Management', 'Trade Execution', 'Risk Management'], iconType: 'ion', region: 'GLOBAL', isBeta: false, isConnected: false, activeConnectionCount: 0 },

  // Open Banking
  { id: '9', connectorCode: 'PSD2_BERLIN_GROUP', connectorName: 'PSD2 Berlin Group', shortName: 'PSD2 EU', description: 'European Open Banking via Berlin Group NextGenPSD2 standard', category: 'OPEN_BANKING', authType: 'BERLIN_GROUP', status: 'AVAILABLE', supportedFeatures: ['Account Information', 'Balance Inquiry', 'Transaction History', 'Payment Initiation'], iconType: 'eu', region: 'EU', complianceStandards: ['PSD2', 'GDPR', 'SCA'], isBeta: false, isConnected: false, activeConnectionCount: 0 },
  { id: '10', connectorCode: 'OPEN_BANKING_UK', connectorName: 'Open Banking UK', shortName: 'OB UK', description: 'UK Open Banking Standard for account aggregation and payments', category: 'OPEN_BANKING', authType: 'OPEN_BANKING_UK', status: 'AVAILABLE', supportedFeatures: ['Account Information', 'Balance Inquiry', 'Transaction History', 'Payment Initiation', 'VRP'], iconType: 'uk', region: 'UK', complianceStandards: ['PSD2', 'GDPR', 'FCA'], isBeta: false, isConnected: false, activeConnectionCount: 0 },
  { id: '11', connectorCode: 'PLAID', connectorName: 'Plaid', shortName: 'Plaid', description: 'Financial data aggregation supporting 12,000+ institutions globally', category: 'OPEN_BANKING', authType: 'OAUTH', status: 'AVAILABLE', supportedFeatures: ['Account Aggregation', 'Balance Inquiry', 'Transaction History', 'Identity Verification'], iconType: 'plaid', region: 'GLOBAL', complianceStandards: ['SOC2', 'GDPR'], isBeta: false, isConnected: false, activeConnectionCount: 0 },
  { id: '12', connectorCode: 'TINK', connectorName: 'Tink', shortName: 'Tink', description: 'European open banking platform across 18 markets', category: 'OPEN_BANKING', authType: 'OAUTH', status: 'AVAILABLE', supportedFeatures: ['Account Aggregation', 'Transaction Categorization', 'Payment Initiation'], iconType: 'tink', region: 'EU', complianceStandards: ['PSD2', 'GDPR'], isBeta: false, isConnected: false, activeConnectionCount: 0 },
  { id: '13', connectorCode: 'TRUELAYER', connectorName: 'TrueLayer', shortName: 'TrueLayer', description: 'Open banking platform for data and payments across UK and Europe', category: 'OPEN_BANKING', authType: 'OAUTH2_AUTHCODE', status: 'AVAILABLE', supportedFeatures: ['Account Information', 'Payment Initiation', 'Payouts'], iconType: 'truelayer', region: 'EU', complianceStandards: ['PSD2', 'GDPR', 'FCA'], isBeta: false, isConnected: false, activeConnectionCount: 0 },
  { id: '14', connectorCode: 'YAPILY', connectorName: 'Yapily', shortName: 'Yapily', description: 'Open banking infrastructure connecting 2,000+ banks', category: 'OPEN_BANKING', authType: 'OAUTH', status: 'AVAILABLE', supportedFeatures: ['Account Information', 'Transaction History', 'Payment Initiation', 'Bulk Payments'], iconType: 'yapily', region: 'EU', complianceStandards: ['PSD2', 'GDPR'], isBeta: false, isConnected: false, activeConnectionCount: 0 },

  // Payments
  { id: '15', connectorCode: 'SWIFT_GPI', connectorName: 'SWIFT gpi', shortName: 'SWIFT gpi', description: 'SWIFT Global Payments Innovation for fast, transparent cross-border payments', category: 'PAYMENTS', authType: 'CERTIFICATE', status: 'AVAILABLE', supportedFeatures: ['Payment Tracking', 'Payment Initiation', 'Pre-Validation', 'Case Management'], iconType: 'SWIFT_GPI', region: 'GLOBAL', complianceStandards: ['ISO20022', 'SWIFT CSP'], isBeta: false, isConnected: false, activeConnectionCount: 0 },
  { id: '16', connectorCode: 'SEPA', connectorName: 'SEPA Payments', shortName: 'SEPA', description: 'Single Euro Payments Area for EUR transfers and direct debits', category: 'PAYMENTS', authType: 'CERTIFICATE', status: 'AVAILABLE', supportedFeatures: ['SCT Instant', 'SCT', 'SDD Core', 'SDD B2B'], iconType: 'sepa', region: 'EU', complianceStandards: ['PSD2', 'ISO20022'], isBeta: false, isConnected: false, activeConnectionCount: 0 },
  { id: '17', connectorCode: 'FASTER_PAYMENTS_UK', connectorName: 'Faster Payments UK', shortName: 'FPS', description: 'UK real-time payment system for GBP domestic payments', category: 'PAYMENTS', authType: 'CERTIFICATE', status: 'AVAILABLE', supportedFeatures: ['Instant Payments', 'Forward Dating', 'Standing Orders'], iconType: 'fps', region: 'UK', complianceStandards: ['ISO20022', 'FCA'], isBeta: false, isConnected: false, activeConnectionCount: 0 },
  { id: '18', connectorCode: 'ISO20022', connectorName: 'ISO 20022 Messaging', shortName: 'ISO 20022', description: 'Universal financial messaging standard', category: 'PAYMENTS', authType: 'API_KEY', status: 'AVAILABLE', supportedFeatures: ['pain.001', 'pain.002', 'camt.052', 'camt.053', 'camt.054'], iconType: 'iso', region: 'GLOBAL', complianceStandards: ['ISO20022'], isBeta: false, isConnected: false, activeConnectionCount: 0 },

  // Banking
  { id: '19', connectorCode: 'HOST_TO_HOST', connectorName: 'Host-to-Host Banking', shortName: 'H2H', description: 'Direct secure connection to bank systems', category: 'BANKING', authType: 'CERTIFICATE', status: 'AVAILABLE', supportedFeatures: ['Payment Files', 'Statement Files', 'Balance Reports'], iconType: 'HOST_TO_HOST', region: 'GLOBAL', isBeta: false, isConnected: false, activeConnectionCount: 0 },
  { id: '20', connectorCode: 'EBICS', connectorName: 'EBICS Banking', shortName: 'EBICS', description: 'Electronic Banking Internet Communication Standard', category: 'BANKING', authType: 'CERTIFICATE', status: 'AVAILABLE', supportedFeatures: ['Payment Initiation', 'Statement Download', 'Direct Debit'], iconType: 'ebics', region: 'DACH', complianceStandards: ['PSD2', 'ISO20022'], isBeta: false, isConnected: false, activeConnectionCount: 0 },
  { id: '21', connectorCode: 'MT940_MT942', connectorName: 'MT940/MT942 Statements', shortName: 'MT940', description: 'SWIFT statement messages for bank reporting', category: 'BANKING', authType: 'API_KEY', status: 'AVAILABLE', supportedFeatures: ['End of Day Statements', 'Intraday Statements', 'Transaction Details'], iconType: 'MT940_MT942', region: 'GLOBAL', isBeta: false, isConnected: true, activeConnectionCount: 1 },

  // Generic
  { id: '22', connectorCode: 'SFTP', connectorName: 'SFTP File Transfer', shortName: 'SFTP', description: 'Secure file transfer protocol for batch data exchange', category: 'GENERIC', authType: 'CERTIFICATE', status: 'AVAILABLE', supportedFeatures: ['CSV Import', 'XML Import', 'BAI2 Files', 'MT940 Statements', 'Scheduled Jobs'], iconType: 'sftp', region: 'GLOBAL', isBeta: false, isConnected: true, activeConnectionCount: 1 },
  { id: '23', connectorCode: 'REST_API', connectorName: 'REST API', shortName: 'REST', description: 'Generic REST API for custom integrations', category: 'GENERIC', authType: 'API_KEY', status: 'AVAILABLE', supportedFeatures: ['Full API Access', 'Custom Endpoints', 'JSON/XML Support'], iconType: 'api', region: 'GLOBAL', isBeta: false, isConnected: false, activeConnectionCount: 0 },
  { id: '24', connectorCode: 'WEBHOOKS', connectorName: 'Webhooks', shortName: 'Webhooks', description: 'Event-driven integration via webhooks', category: 'GENERIC', authType: 'API_KEY', status: 'AVAILABLE', supportedFeatures: ['Real-time Events', 'Custom Payloads', 'Retry Logic', 'Event Filtering'], iconType: 'webhook', region: 'GLOBAL', isBeta: false, isConnected: false, activeConnectionCount: 0 },
  { id: '25', connectorCode: 'GRAPHQL', connectorName: 'GraphQL API', shortName: 'GraphQL', description: 'GraphQL API endpoint for flexible queries', category: 'GENERIC', authType: 'API_KEY', status: 'AVAILABLE', supportedFeatures: ['Flexible Queries', 'Real-time Subscriptions', 'Batch Operations'], iconType: 'graphql', region: 'GLOBAL', isBeta: false, isConnected: false, activeConnectionCount: 0 },
];

const mockConnections: Connection[] = [
  {
    id: '1', connectorId: '1', connectorCode: 'SAP_S4HANA', connectorName: 'SAP S/4HANA', iconType: 'sap',
    connectionName: 'SAP Production', environment: 'PRODUCTION', status: 'CONNECTED',
    lastSyncAt: '2024-02-12T14:30:00Z', nextSyncAt: '2024-02-12T15:30:00Z', syncFrequency: 'HOURLY',
    createdAt: '2023-06-15T10:00:00Z', createdBy: 'admin@company.com', dataFlowCount: 3,
    dataFlows: [
      { id: '1', flowName: 'GL Accounts Sync', direction: 'INBOUND', sourceEntity: 'SAP GL Accounts', targetEntity: 'VAM Chart of Accounts', lastRunAt: '2024-02-12T14:30:00Z', recordsProcessed: 1250, status: 'ACTIVE', scheduleEnabled: true },
      { id: '2', flowName: 'Bank Balances Import', direction: 'INBOUND', sourceEntity: 'SAP Bank Accounts', targetEntity: 'VAM Account Balances', lastRunAt: '2024-02-12T14:30:00Z', recordsProcessed: 45, status: 'ACTIVE', scheduleEnabled: true },
      { id: '3', flowName: 'Sweep Results Export', direction: 'OUTBOUND', sourceEntity: 'VAM Sweep Executions', targetEntity: 'SAP Journal Entries', lastRunAt: '2024-02-12T14:35:00Z', recordsProcessed: 12, status: 'ACTIVE', scheduleEnabled: true },
    ],
  },
  {
    id: '2', connectorId: '6', connectorCode: 'KYRIBA', connectorName: 'Kyriba Treasury', iconType: 'kyriba',
    connectionName: 'Kyriba TMS', environment: 'PRODUCTION', status: 'CONNECTED',
    lastSyncAt: '2024-02-12T14:00:00Z', nextSyncAt: '2024-02-12T18:00:00Z', syncFrequency: 'DAILY',
    createdAt: '2023-09-01T09:00:00Z', createdBy: 'treasury@company.com', dataFlowCount: 2,
    dataFlows: [
      { id: '4', flowName: 'Cash Positions Sync', direction: 'INBOUND', sourceEntity: 'Kyriba Cash Positions', targetEntity: 'VAM Cash Positions', lastRunAt: '2024-02-12T14:00:00Z', recordsProcessed: 89, status: 'ACTIVE', scheduleEnabled: true },
      { id: '5', flowName: 'Forecast Data Import', direction: 'INBOUND', sourceEntity: 'Kyriba Forecasts', targetEntity: 'VAM Liquidity Forecast', lastRunAt: '2024-02-12T14:00:00Z', recordsProcessed: 365, status: 'ACTIVE', scheduleEnabled: true },
    ],
  },
  {
    id: '3', connectorId: '22', connectorCode: 'SFTP', connectorName: 'SFTP File Transfer', iconType: 'sftp',
    connectionName: 'Bank Statement SFTP', environment: 'PRODUCTION', status: 'CONNECTED',
    lastSyncAt: '2024-02-12T06:00:00Z', nextSyncAt: '2024-02-13T06:00:00Z', syncFrequency: 'DAILY',
    createdAt: '2023-03-10T11:00:00Z', createdBy: 'ops@company.com', dataFlowCount: 1,
    dataFlows: [
      { id: '6', flowName: 'MT940 Statement Import', direction: 'INBOUND', sourceEntity: 'MT940 Files', targetEntity: 'VAM Bank Statements', lastRunAt: '2024-02-12T06:00:00Z', recordsProcessed: 156, status: 'ACTIVE', scheduleEnabled: true },
    ],
  },
  {
    id: '4', connectorId: '3', connectorCode: 'MS_DYNAMICS_365', connectorName: 'Microsoft Dynamics 365', iconType: 'microsoft',
    connectionName: 'Dynamics Sandbox', environment: 'SANDBOX', status: 'ERROR',
    lastSyncAt: '2024-02-10T12:00:00Z', syncFrequency: 'DAILY',
    createdAt: '2024-01-20T14:00:00Z', createdBy: 'it@company.com', dataFlowCount: 1,
    errorMessage: 'Authentication token expired. Please re-authenticate.',
    dataFlows: [
      { id: '7', flowName: 'Entity Master Sync', direction: 'INBOUND', sourceEntity: 'D365 Legal Entities', targetEntity: 'VAM Entities', lastRunAt: '2024-02-10T12:00:00Z', recordsProcessed: 0, status: 'ERROR', scheduleEnabled: true },
    ],
  },
  {
    id: '5', connectorId: '21', connectorCode: 'MT940_MT942', connectorName: 'MT940/MT942 Statements', iconType: 'MT940_MT942',
    connectionName: 'HSBC Statements', environment: 'PRODUCTION', status: 'CONNECTED',
    lastSyncAt: '2024-02-12T08:00:00Z', nextSyncAt: '2024-02-13T08:00:00Z', syncFrequency: 'DAILY',
    createdAt: '2023-08-15T09:00:00Z', createdBy: 'ops@company.com', dataFlowCount: 1,
  },
];

const mockSyncLogs: SyncLog[] = [
  { id: '1', connectionId: '1', connectionName: 'SAP Production', flowName: 'GL Accounts Sync', direction: 'INBOUND', startTime: '2024-02-12T14:30:00Z', endTime: '2024-02-12T14:32:15Z', durationMs: 135000, status: 'SUCCESS', recordsProcessed: 1250, recordsFailed: 0 },
  { id: '2', connectionId: '1', connectionName: 'SAP Production', flowName: 'Bank Balances Import', direction: 'INBOUND', startTime: '2024-02-12T14:30:00Z', endTime: '2024-02-12T14:30:45Z', durationMs: 45000, status: 'SUCCESS', recordsProcessed: 45, recordsFailed: 0 },
  { id: '3', connectionId: '1', connectionName: 'SAP Production', flowName: 'Sweep Results Export', direction: 'OUTBOUND', startTime: '2024-02-12T14:35:00Z', endTime: '2024-02-12T14:35:30Z', durationMs: 30000, status: 'SUCCESS', recordsProcessed: 12, recordsFailed: 0 },
  { id: '4', connectionId: '2', connectionName: 'Kyriba TMS', flowName: 'Cash Positions Sync', direction: 'INBOUND', startTime: '2024-02-12T14:00:00Z', endTime: '2024-02-12T14:02:00Z', durationMs: 120000, status: 'SUCCESS', recordsProcessed: 89, recordsFailed: 0 },
  { id: '5', connectionId: '2', connectionName: 'Kyriba TMS', flowName: 'Forecast Data Import', direction: 'INBOUND', startTime: '2024-02-12T14:00:00Z', endTime: '2024-02-12T14:05:00Z', durationMs: 300000, status: 'PARTIAL', recordsProcessed: 365, recordsFailed: 12, errorMessage: '12 records skipped due to missing currency codes' },
  { id: '6', connectionId: '3', connectionName: 'Bank Statement SFTP', flowName: 'MT940 Statement Import', direction: 'INBOUND', startTime: '2024-02-12T06:00:00Z', endTime: '2024-02-12T06:03:00Z', durationMs: 180000, status: 'SUCCESS', recordsProcessed: 156, recordsFailed: 0 },
  { id: '7', connectionId: '4', connectionName: 'Dynamics Sandbox', flowName: 'Entity Master Sync', direction: 'INBOUND', startTime: '2024-02-10T12:00:00Z', endTime: '2024-02-10T12:00:05Z', durationMs: 5000, status: 'FAILED', recordsProcessed: 0, recordsFailed: 0, errorMessage: 'Authentication token expired' },
  { id: '8', connectionId: '1', connectionName: 'SAP Production', flowName: 'GL Accounts Sync', direction: 'INBOUND', startTime: '2024-02-12T13:30:00Z', endTime: '2024-02-12T13:32:10Z', durationMs: 130000, status: 'SUCCESS', recordsProcessed: 1248, recordsFailed: 0 },
];

const mockStats: IntegrationStats = {
  totalConnections: 5,
  activeConnections: 4,
  errorConnections: 1,
  syncingConnections: 0,
  totalDataFlows: 8,
  activeDataFlows: 7,
  availableConnectors: 25,
  categoryStats: {
    erpConnections: 2,
    treasuryConnections: 1,
    bankingConnections: 1,
    openBankingConnections: 0,
    paymentsConnections: 0,
    genericConnections: 1,
  },
};

// Sample field mappings
const sampleFieldMappings: FieldMapping[] = [
  { id: '1', sourceField: 'BUKRS', targetField: 'entity_code', transformation: 'direct', isRequired: true },
  { id: '2', sourceField: 'HKONT', targetField: 'account_number', transformation: 'direct', isRequired: true },
  { id: '3', sourceField: 'WAERS', targetField: 'currency_code', transformation: 'lookup:currency_map', isRequired: true },
  { id: '4', sourceField: 'DMBTR', targetField: 'balance', transformation: 'number:2', isRequired: true },
  { id: '5', sourceField: 'BUDAT', targetField: 'value_date', transformation: 'date:YYYY-MM-DD', isRequired: true },
  { id: '6', sourceField: 'BELNR', targetField: 'reference', transformation: 'concat:DOC-{value}', isRequired: false },
  { id: '7', sourceField: 'SGTXT', targetField: 'description', transformation: 'trim', isRequired: false },
];

// ============================================================================
// CATEGORY CONFIG
// ============================================================================

const categoryConfig: Record<ConnectorCategory, { label: string; icon: React.ElementType; color: string }> = {
  ERP: { label: 'ERP', icon: Building2, color: 'text-neutral-700 dark:text-neutral-200' },
  TREASURY: { label: 'Treasury', icon: Database, color: 'text-neutral-700 dark:text-neutral-200' },
  BANKING: { label: 'Banking', icon: Building2, color: 'text-neutral-700 dark:text-neutral-200' },
  OPEN_BANKING: { label: 'Open Banking', icon: Globe, color: 'text-neutral-700 dark:text-neutral-200' },
  PAYMENTS: { label: 'Payments', icon: CreditCard, color: 'text-neutral-700 dark:text-neutral-200' },
  GENERIC: { label: 'Generic', icon: Zap, color: 'text-neutral-700 dark:text-neutral-200' },
};

// ============================================================================
// CONNECTOR CARD - Swiss Minimalism
// ============================================================================

const ConnectorCard: React.FC<{
  connector: Connector;
  onSetup: () => void;
}> = ({ connector, onSetup }) => {
  const IconComponent = getConnectorIcon(connector.iconType || connector.connectorCode);
  
  return (
    <div className="group border border-neutral-200 bg-white hover:border-neutral-400 transition-all duration-200 dark:border-primary-800 dark:bg-primary-900">
      <div className="p-6">
        {/* Header */}
        <div className="flex items-start justify-between mb-4">
          <div className="flex items-center gap-4">
            <div className="w-12 h-12 border border-neutral-200 flex items-center justify-center bg-neutral-50 dark:border-primary-800 dark:bg-primary-950">
              <IconComponent size={24} className="text-neutral-700 dark:text-neutral-200" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h3 className="text-sm font-medium text-neutral-900 tracking-tight dark:text-neutral-50">{connector.connectorName}</h3>
                {connector.isBeta && (
                  <span className="px-1.5 py-0.5 text-[10px] font-medium tracking-wide uppercase bg-neutral-100 text-neutral-600 dark:bg-primary-800 dark:text-neutral-300">Beta</span>
                )}
              </div>
              <p className="text-xs text-neutral-500 mt-0.5 dark:text-neutral-400">{categoryConfig[connector.category].label} · {connector.region || 'Global'}</p>
            </div>
          </div>
          {connector.isConnected && (
            <div className="w-2 h-2 rounded-full bg-neutral-900" title="Connected" />
          )}
        </div>

        {/* Description */}
        <p className="text-xs text-neutral-600 leading-relaxed mb-4 line-clamp-2 dark:text-neutral-300">{connector.description}</p>

        {/* Features */}
        <div className="flex flex-wrap gap-1 mb-4">
          {connector.supportedFeatures.slice(0, 3).map((feature, idx) => (
            <span key={idx} className="px-2 py-0.5 text-[10px] font-medium bg-neutral-50 text-neutral-600 border border-neutral-100 dark:bg-primary-950 dark:text-neutral-300 dark:border-primary-800/60">
              {feature}
            </span>
          ))}
          {connector.supportedFeatures.length > 3 && (
            <span className="px-2 py-0.5 text-[10px] font-medium text-neutral-400 dark:text-neutral-500">
              +{connector.supportedFeatures.length - 3}
            </span>
          )}
        </div>

        {/* Compliance Standards */}
        {connector.complianceStandards && connector.complianceStandards.length > 0 && (
          <div className="flex gap-1 mb-4">
            {connector.complianceStandards.slice(0, 3).map((std, idx) => (
              <span key={idx} className="text-[10px] text-neutral-400 dark:text-neutral-500">{std}</span>
            ))}
          </div>
        )}
      </div>

      {/* Footer */}
      <div className="px-6 py-3 border-t border-neutral-100 flex items-center justify-between bg-neutral-50/50 dark:border-primary-800/60">
        {connector.documentationUrl && (
          <button className="text-xs text-neutral-500 hover:text-neutral-700 flex items-center gap-1 transition-colors dark:text-neutral-400 dark:hover:text-neutral-200">
            Documentation <ExternalLink className="w-3 h-3" />
          </button>
        )}
        <button
          onClick={onSetup}
          disabled={connector.status === 'COMING_SOON'}
          className={cn(
            "px-4 py-1.5 text-xs font-medium transition-all",
            connector.status === 'COMING_SOON'
              ? "text-neutral-400 cursor-not-allowed dark:text-neutral-500"
              : "text-neutral-900 hover:bg-neutral-900 hover:text-white border border-neutral-900 dark:text-neutral-50"
          )}
        >
          {connector.isConnected ? 'Manage' : 'Connect'}
        </button>
      </div>
    </div>
  );
};

// ============================================================================
// CONNECTION CARD - Swiss Minimalism
// ============================================================================

const ConnectionCard: React.FC<{
  connection: Connection;
  onView: () => void;
  onSync: () => void;
  onDisconnect: () => void;
}> = ({ connection, onView, onSync, onDisconnect }) => {
  const IconComponent = getConnectorIcon(connection.iconType || connection.connectorCode);

  const statusStyles: Record<ConnectionStatus, { bg: string; text: string; label: string }> = {
    CONNECTED: { bg: 'bg-neutral-900', text: 'text-white', label: 'Active' },
    DISCONNECTED: { bg: 'bg-neutral-200', text: 'text-neutral-600 dark:text-neutral-300', label: 'Inactive' },
    ERROR: { bg: 'bg-error-600', text: 'text-white', label: 'Error' },
    SYNCING: { bg: 'bg-neutral-600', text: 'text-white', label: 'Syncing' },
  };

  const status = statusStyles[connection.status];

  return (
    <div className="border border-neutral-200 bg-white hover:border-neutral-300 transition-colors dark:border-primary-800 dark:bg-primary-900 dark:hover:border-primary-700">
      {/* Header */}
      <div className="p-5 border-b border-neutral-100 dark:border-primary-800/60">
        <div className="flex items-start justify-between">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 border border-neutral-200 flex items-center justify-center bg-neutral-50 dark:border-primary-800 dark:bg-primary-950">
              <IconComponent size={20} className="text-neutral-700 dark:text-neutral-200" />
            </div>
            <div>
              <h3 className="text-sm font-medium text-neutral-900 dark:text-neutral-50">{connection.connectionName}</h3>
              <p className="text-xs text-neutral-500 dark:text-neutral-400">{connection.connectorName}</p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <span className={cn(
              "px-2 py-0.5 text-[10px] font-medium uppercase tracking-wide",
              connection.environment === 'PRODUCTION' ? 'bg-neutral-900 text-white' : 'bg-neutral-100 text-neutral-600 dark:bg-primary-800 dark:text-neutral-300'
            )}>
              {connection.environment === 'PRODUCTION' ? 'Prod' : 'Sandbox'}
            </span>
            <span className={cn("px-2 py-0.5 text-[10px] font-medium uppercase tracking-wide", status.bg, status.text)}>
              {status.label}
            </span>
          </div>
        </div>
      </div>

      {/* Error Message */}
      {connection.errorMessage && (
        <div className="px-5 py-3 bg-error-50 border-b border-error-100 dark:bg-error-500/10 dark:border-error-500/30">
          <p className="text-xs text-error-700 dark:text-error-300">{connection.errorMessage}</p>
        </div>
      )}

      {/* Stats Grid */}
      <div className="grid grid-cols-3 divide-x divide-neutral-100 border-b border-neutral-100 dark:divide-primary-800/60 dark:border-primary-800/60">
        <div className="p-4 text-center">
          <p className="text-lg font-light text-neutral-900 dark:text-neutral-50">{connection.dataFlowCount}</p>
          <p className="text-[10px] uppercase tracking-wide text-neutral-500 dark:text-neutral-400">Flows</p>
        </div>
        <div className="p-4 text-center">
          <p className="text-xs font-medium text-neutral-900 capitalize dark:text-neutral-50">{connection.syncFrequency.toLowerCase().replace('_', ' ')}</p>
          <p className="text-[10px] uppercase tracking-wide text-neutral-500 dark:text-neutral-400">Frequency</p>
        </div>
        <div className="p-4 text-center">
          <p className="text-xs font-medium text-neutral-900 dark:text-neutral-50">{connection.lastSyncAt ? formatRelativeTime(connection.lastSyncAt) : '—'}</p>
          <p className="text-[10px] uppercase tracking-wide text-neutral-500 dark:text-neutral-400">Last Sync</p>
        </div>
      </div>

      {/* Actions */}
      <div className="p-3 flex items-center justify-between bg-neutral-50/50">
        <button
          onClick={onDisconnect}
          className="p-2 text-neutral-400 hover:text-error-600 transition-colors dark:text-neutral-500"
          title="Disconnect"
        >
          <Trash2 className="w-4 h-4" />
        </button>
        <div className="flex items-center gap-2">
          <button
            onClick={onSync}
            disabled={connection.status === 'SYNCING'}
            className="p-2 text-neutral-500 hover:text-neutral-900 transition-colors disabled:opacity-50 dark:text-neutral-400 dark:hover:text-neutral-50"
            title="Sync Now"
          >
            <RefreshCw className={cn("w-4 h-4", connection.status === 'SYNCING' && 'animate-spin')} />
          </button>
          <button
            onClick={onView}
            className="px-3 py-1.5 text-xs font-medium border border-neutral-300 hover:border-neutral-900 hover:bg-neutral-900 hover:text-white transition-all dark:border-primary-700"
          >
            Manage
          </button>
        </div>
      </div>
    </div>
  );
};

// ============================================================================
// SETUP WIZARD MODAL - Swiss Minimalism
// ============================================================================

const SetupWizardModal: React.FC<{
  connector: Connector | null;
  onClose: () => void;
  onComplete: (config: Record<string, unknown>) => void;
}> = ({ connector, onClose, onComplete }) => {
  const [step, setStep] = useState(1);
  const [config, setConfig] = useState({
    name: '',
    environment: 'SANDBOX',
    clientId: '',
    clientSecret: '',
    tenantId: '',
    apiKey: '',
    host: '',
    port: '22',
    username: '',
    privateKey: '',
    syncFrequency: 'DAILY',
  });
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<'success' | 'error' | null>(null);

  if (!connector) return null;

  const IconComponent = getConnectorIcon(connector.iconType || connector.connectorCode);

  const testConnection = async () => {
    setTesting(true);
    setTestResult(null);
    await new Promise(resolve => setTimeout(resolve, 2000));
    setTestResult(Math.random() > 0.2 ? 'success' : 'error');
    setTesting(false);
  };

  const handleComplete = () => {
    onComplete(config);
    onClose();
    setStep(1);
    setTestResult(null);
  };

  const renderAuthFields = () => {
    const authType = connector.authType;
    
    if (authType === 'OAUTH' || authType === 'OAUTH2_AUTHCODE') {
      return (
        <div className="space-y-4">
          <div>
            <label className="block text-xs font-medium text-neutral-600 mb-2 uppercase tracking-wide dark:text-neutral-300">Client ID</label>
            <input
              type="text"
              className="w-full px-3 py-2.5 border border-neutral-300 text-sm focus:border-neutral-900 focus:outline-none transition-colors dark:border-primary-700"
              placeholder="Enter OAuth Client ID"
              value={config.clientId}
              onChange={e => setConfig({ ...config, clientId: e.target.value })}
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-neutral-600 mb-2 uppercase tracking-wide dark:text-neutral-300">Client Secret</label>
            <input
              type="password"
              className="w-full px-3 py-2.5 border border-neutral-300 text-sm focus:border-neutral-900 focus:outline-none transition-colors dark:border-primary-700"
              placeholder="Enter OAuth Client Secret"
              value={config.clientSecret}
              onChange={e => setConfig({ ...config, clientSecret: e.target.value })}
            />
          </div>
          {(connector.connectorCode === 'MS_DYNAMICS_365') && (
            <div>
              <label className="block text-xs font-medium text-neutral-600 mb-2 uppercase tracking-wide dark:text-neutral-300">Tenant ID</label>
              <input
                type="text"
                className="w-full px-3 py-2.5 border border-neutral-300 text-sm focus:border-neutral-900 focus:outline-none transition-colors dark:border-primary-700"
                placeholder="Azure AD Tenant ID"
                value={config.tenantId}
                onChange={e => setConfig({ ...config, tenantId: e.target.value })}
              />
            </div>
          )}
        </div>
      );
    }

    if (authType === 'API_KEY') {
      return (
        <div className="space-y-4">
          <div>
            <label className="block text-xs font-medium text-neutral-600 mb-2 uppercase tracking-wide dark:text-neutral-300">API Key</label>
            <input
              type="password"
              className="w-full px-3 py-2.5 border border-neutral-300 text-sm focus:border-neutral-900 focus:outline-none transition-colors dark:border-primary-700"
              placeholder="Enter API Key"
              value={config.apiKey}
              onChange={e => setConfig({ ...config, apiKey: e.target.value })}
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-neutral-600 mb-2 uppercase tracking-wide dark:text-neutral-300">Endpoint URL</label>
            <input
              type="text"
              className="w-full px-3 py-2.5 border border-neutral-300 text-sm focus:border-neutral-900 focus:outline-none transition-colors dark:border-primary-700"
              placeholder="https://api.example.com"
              value={config.host}
              onChange={e => setConfig({ ...config, host: e.target.value })}
            />
          </div>
        </div>
      );
    }

    if (authType === 'CERTIFICATE') {
      return (
        <div className="space-y-4">
          <div>
            <label className="block text-xs font-medium text-neutral-600 mb-2 uppercase tracking-wide dark:text-neutral-300">Host</label>
            <input
              type="text"
              className="w-full px-3 py-2.5 border border-neutral-300 text-sm focus:border-neutral-900 focus:outline-none transition-colors dark:border-primary-700"
              placeholder="sftp.example.com"
              value={config.host}
              onChange={e => setConfig({ ...config, host: e.target.value })}
            />
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-xs font-medium text-neutral-600 mb-2 uppercase tracking-wide dark:text-neutral-300">Port</label>
              <input
                type="text"
                className="w-full px-3 py-2.5 border border-neutral-300 text-sm focus:border-neutral-900 focus:outline-none transition-colors dark:border-primary-700"
                placeholder="22"
                value={config.port}
                onChange={e => setConfig({ ...config, port: e.target.value })}
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-neutral-600 mb-2 uppercase tracking-wide dark:text-neutral-300">Username</label>
              <input
                type="text"
                className="w-full px-3 py-2.5 border border-neutral-300 text-sm focus:border-neutral-900 focus:outline-none transition-colors dark:border-primary-700"
                placeholder="sftp_user"
                value={config.username}
                onChange={e => setConfig({ ...config, username: e.target.value })}
              />
            </div>
          </div>
          <div>
            <label className="block text-xs font-medium text-neutral-600 mb-2 uppercase tracking-wide dark:text-neutral-300">Private Key</label>
            <textarea
              className="w-full h-24 px-3 py-2.5 border border-neutral-300 text-sm font-mono focus:border-neutral-900 focus:outline-none transition-colors resize-none dark:border-primary-700"
              placeholder="-----BEGIN RSA PRIVATE KEY-----"
              value={config.privateKey}
              onChange={e => setConfig({ ...config, privateKey: e.target.value })}
            />
          </div>
        </div>
      );
    }

    // Open Banking auth types
    if (['PSD2_CONSENT', 'OPEN_BANKING_UK', 'BERLIN_GROUP', 'STET', 'POLISH_API'].includes(authType)) {
      return (
        <div className="space-y-4">
          <div className="p-4 bg-neutral-50 border border-neutral-200 dark:bg-primary-950 dark:border-primary-800">
            <div className="flex items-start gap-3">
              <Shield className="w-5 h-5 text-neutral-600 mt-0.5 dark:text-neutral-300" />
              <div>
                <p className="text-sm font-medium text-neutral-900 dark:text-neutral-50">Bank Authorization Required</p>
                <p className="text-xs text-neutral-600 mt-1 dark:text-neutral-300">
                  You will be redirected to your bank to authorize access. This uses {connector.authType.replace('_', ' ')} authentication.
                </p>
              </div>
            </div>
          </div>
          <div>
            <label className="block text-xs font-medium text-neutral-600 mb-2 uppercase tracking-wide dark:text-neutral-300">TPP Certificate</label>
            <div className="border border-dashed border-neutral-300 p-6 text-center dark:border-primary-700">
              <Upload className="w-6 h-6 text-neutral-400 mx-auto mb-2 dark:text-neutral-500" />
              <p className="text-xs text-neutral-600 dark:text-neutral-300">Drop certificate file or click to upload</p>
            </div>
          </div>
        </div>
      );
    }

    // Default: Basic auth
    return (
      <div className="space-y-4">
        <div>
          <label className="block text-xs font-medium text-neutral-600 mb-2 uppercase tracking-wide dark:text-neutral-300">Username</label>
          <input
            type="text"
            className="w-full px-3 py-2.5 border border-neutral-300 text-sm focus:border-neutral-900 focus:outline-none transition-colors dark:border-primary-700"
            placeholder="Enter username"
            value={config.username}
            onChange={e => setConfig({ ...config, username: e.target.value })}
          />
        </div>
        <div>
          <label className="block text-xs font-medium text-neutral-600 mb-2 uppercase tracking-wide dark:text-neutral-300">Password</label>
          <input
            type="password"
            className="w-full px-3 py-2.5 border border-neutral-300 text-sm focus:border-neutral-900 focus:outline-none transition-colors dark:border-primary-700"
            placeholder="Enter password"
            value={config.clientSecret}
            onChange={e => setConfig({ ...config, clientSecret: e.target.value })}
          />
        </div>
      </div>
    );
  };

  return (
    <Modal isOpen={!!connector} onClose={onClose} title="" size="lg">
      <div className="min-h-[500px] flex flex-col">
        {/* Header */}
        <div className="pb-6 border-b border-neutral-200 mb-6 dark:border-primary-800">
          <div className="flex items-center gap-4">
            <div className="w-12 h-12 border border-neutral-200 flex items-center justify-center dark:border-primary-800">
              <IconComponent size={24} className="text-neutral-700 dark:text-neutral-200" />
            </div>
            <div>
              <h2 className="text-lg font-medium text-neutral-900 dark:text-neutral-50">Connect {connector.connectorName}</h2>
              <p className="text-sm text-neutral-500 dark:text-neutral-400">{categoryConfig[connector.category].label} Integration</p>
            </div>
          </div>
        </div>

        {/* Step Indicator */}
        <div className="flex items-center gap-1 mb-8">
          {['Details', 'Credentials', 'Settings', 'Verify'].map((label, idx) => (
            <React.Fragment key={idx}>
              <div className={cn(
                "flex items-center gap-2 px-3 py-1.5 text-xs font-medium transition-colors",
                step === idx + 1 && "bg-neutral-900 text-white",
                step > idx + 1 && "bg-neutral-100 text-neutral-900 dark:bg-primary-800 dark:text-neutral-50",
                step < idx + 1 && "text-neutral-400 dark:text-neutral-500"
              )}>
                {step > idx + 1 ? <Check className="w-3 h-3" /> : <span>{idx + 1}</span>}
                <span className="hidden sm:inline">{label}</span>
              </div>
              {idx < 3 && <div className={cn("flex-1 h-px", step > idx + 1 ? "bg-neutral-900" : "bg-neutral-200 dark:bg-primary-800")} />}
            </React.Fragment>
          ))}
        </div>

        {/* Step Content */}
        <div className="flex-1">
          {step === 1 && (
            <div className="space-y-6">
              <div>
                <label className="block text-xs font-medium text-neutral-600 mb-2 uppercase tracking-wide dark:text-neutral-300">Connection Name</label>
                <input
                  type="text"
                  className="w-full px-3 py-2.5 border border-neutral-300 text-sm focus:border-neutral-900 focus:outline-none transition-colors dark:border-primary-700"
                  placeholder={`My ${connector.shortName} Connection`}
                  value={config.name}
                  onChange={e => setConfig({ ...config, name: e.target.value })}
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-neutral-600 mb-3 uppercase tracking-wide dark:text-neutral-300">Environment</label>
                <div className="grid grid-cols-2 gap-3">
                  {['SANDBOX', 'PRODUCTION'].map(env => (
                    <button
                      key={env}
                      onClick={() => setConfig({ ...config, environment: env })}
                      className={cn(
                        "p-4 border text-left transition-all",
                        config.environment === env
                          ? "border-neutral-900 bg-neutral-900 text-white"
                          : "border-neutral-200 hover:border-neutral-400 dark:border-primary-800"
                      )}
                    >
                      <p className="text-sm font-medium">{env === 'SANDBOX' ? 'Sandbox' : 'Production'}</p>
                      <p className={cn("text-xs mt-1", config.environment === env ? "text-neutral-300 dark:text-neutral-600" : "text-neutral-500 dark:text-neutral-400")}>
                        {env === 'SANDBOX' ? 'For testing and development' : 'Live production data'}
                      </p>
                    </button>
                  ))}
                </div>
              </div>
            </div>
          )}

          {step === 2 && (
            <div className="space-y-6">
              <div className="p-4 bg-neutral-50 border border-neutral-200 mb-6 dark:bg-primary-950 dark:border-primary-800">
                <div className="flex items-center gap-2 text-xs text-neutral-600 dark:text-neutral-300">
                  <Shield className="w-4 h-4" />
                  <span>Credentials are encrypted using AES-256 and stored securely</span>
                </div>
              </div>
              {renderAuthFields()}
            </div>
          )}

          {step === 3 && (
            <div className="space-y-6">
              <div>
                <label className="block text-xs font-medium text-neutral-600 mb-3 uppercase tracking-wide dark:text-neutral-300">Sync Frequency</label>
                <div className="grid grid-cols-2 gap-3">
                  {[
                    { value: 'REAL_TIME', label: 'Real-Time', desc: 'Instant sync' },
                    { value: 'HOURLY', label: 'Hourly', desc: 'Every hour' },
                    { value: 'DAILY', label: 'Daily', desc: 'Once per day' },
                    { value: 'MANUAL', label: 'Manual', desc: 'On-demand' },
                  ].map(freq => (
                    <button
                      key={freq.value}
                      onClick={() => setConfig({ ...config, syncFrequency: freq.value })}
                      className={cn(
                        "p-3 border text-left transition-all",
                        config.syncFrequency === freq.value
                          ? "border-neutral-900 bg-neutral-900 text-white"
                          : "border-neutral-200 hover:border-neutral-400 dark:border-primary-800"
                      )}
                    >
                      <p className="text-sm font-medium">{freq.label}</p>
                      <p className={cn("text-xs", config.syncFrequency === freq.value ? "text-neutral-300 dark:text-neutral-600" : "text-neutral-500 dark:text-neutral-400")}>{freq.desc}</p>
                    </button>
                  ))}
                </div>
              </div>
              <div className="p-4 bg-neutral-50 border border-neutral-200 dark:bg-primary-950 dark:border-primary-800">
                <p className="text-xs font-medium text-neutral-700 uppercase tracking-wide mb-3 dark:text-neutral-200">Default Data Flows</p>
                <div className="space-y-2">
                  {connector.supportedFeatures.slice(0, 4).map((feature, idx) => (
                    <div key={idx} className="flex items-center justify-between py-1.5">
                      <span className="text-sm text-neutral-600 dark:text-neutral-300">{feature}</span>
                      <span className="text-xs text-neutral-400 dark:text-neutral-500">Enabled</span>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          )}

          {step === 4 && (
            <div className="space-y-6">
              <div className="p-4 bg-neutral-50 border border-neutral-200 dark:bg-primary-950 dark:border-primary-800">
                <div className="grid grid-cols-2 gap-4 text-sm">
                  <div>
                    <p className="text-xs text-neutral-500 uppercase tracking-wide dark:text-neutral-400">Name</p>
                    <p className="font-medium text-neutral-900 mt-1 dark:text-neutral-50">{config.name || `My ${connector.shortName}`}</p>
                  </div>
                  <div>
                    <p className="text-xs text-neutral-500 uppercase tracking-wide dark:text-neutral-400">Environment</p>
                    <p className="font-medium text-neutral-900 mt-1 dark:text-neutral-50">{config.environment}</p>
                  </div>
                  <div>
                    <p className="text-xs text-neutral-500 uppercase tracking-wide dark:text-neutral-400">Auth Type</p>
                    <p className="font-medium text-neutral-900 mt-1 dark:text-neutral-50">{connector.authType.replace('_', ' ')}</p>
                  </div>
                  <div>
                    <p className="text-xs text-neutral-500 uppercase tracking-wide dark:text-neutral-400">Sync</p>
                    <p className="font-medium text-neutral-900 mt-1 dark:text-neutral-50">{config.syncFrequency.replace('_', ' ')}</p>
                  </div>
                </div>
              </div>

              <div className="text-center py-8">
                {!testing && !testResult && (
                  <button
                    onClick={testConnection}
                    className="px-6 py-2.5 bg-neutral-900 text-white text-sm font-medium hover:bg-neutral-800 transition-colors"
                  >
                    Test Connection
                  </button>
                )}
                {testing && (
                  <div>
                    <Loader2 className="w-8 h-8 text-neutral-400 animate-spin mx-auto mb-3 dark:text-neutral-500" />
                    <p className="text-sm text-neutral-600 dark:text-neutral-300">Testing connection...</p>
                  </div>
                )}
                {testResult === 'success' && (
                  <div>
                    <div className="w-12 h-12 bg-neutral-900 flex items-center justify-center mx-auto mb-3">
                      <Check className="w-6 h-6 text-white" />
                    </div>
                    <p className="text-sm font-medium text-neutral-900 dark:text-neutral-50">Connection Successful</p>
                    <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">Ready to sync data</p>
                  </div>
                )}
                {testResult === 'error' && (
                  <div>
                    <div className="w-12 h-12 bg-error-600 flex items-center justify-center mx-auto mb-3">
                      <X className="w-6 h-6 text-white" />
                    </div>
                    <p className="text-sm font-medium text-neutral-900 dark:text-neutral-50">Connection Failed</p>
                    <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">Please check credentials and try again</p>
                    <button onClick={testConnection} className="mt-4 text-xs text-neutral-600 hover:text-neutral-900 underline dark:text-neutral-300 dark:hover:text-neutral-50">
                      Retry
                    </button>
                  </div>
                )}
              </div>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="flex justify-between pt-6 mt-6 border-t border-neutral-200 dark:border-primary-800">
          <button
            onClick={step === 1 ? onClose : () => setStep(step - 1)}
            className="px-4 py-2 text-sm text-neutral-600 hover:text-neutral-900 transition-colors dark:text-neutral-300 dark:hover:text-neutral-50"
          >
            {step === 1 ? 'Cancel' : 'Back'}
          </button>
          {step < 4 ? (
            <button
              onClick={() => setStep(step + 1)}
              className="px-6 py-2 bg-neutral-900 text-white text-sm font-medium hover:bg-neutral-800 transition-colors"
            >
              Continue
            </button>
          ) : (
            <button
              onClick={handleComplete}
              disabled={testResult !== 'success'}
              className="px-6 py-2 bg-neutral-900 text-white text-sm font-medium hover:bg-neutral-800 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            >
              Complete Setup
            </button>
          )}
        </div>
      </div>
    </Modal>
  );
};

// ============================================================================
// CONNECTION DETAIL MODAL
// ============================================================================

const ConnectionDetailModal: React.FC<{
  connection: Connection | null;
  onClose: () => void;
  onOpenMapping: (flow: DataFlow) => void;
}> = ({ connection, onClose, onOpenMapping }) => {
  const [activeTab, setActiveTab] = useState<'overview' | 'flows' | 'logs'>('overview');

  if (!connection) return null;

  const IconComponent = getConnectorIcon(connection.iconType || connection.connectorCode);
  const connectionLogs = mockSyncLogs.filter(log => log.connectionId === connection.id);

  return (
    <Modal isOpen={!!connection} onClose={onClose} title="" size="xl">
      {/* Header */}
      <div className="flex items-center justify-between pb-6 border-b border-neutral-200 mb-6 dark:border-primary-800">
        <div className="flex items-center gap-4">
          <div className="w-12 h-12 border border-neutral-200 flex items-center justify-center dark:border-primary-800">
            <IconComponent size={24} className="text-neutral-700 dark:text-neutral-200" />
          </div>
          <div>
            <h2 className="text-lg font-medium text-neutral-900 dark:text-neutral-50">{connection.connectionName}</h2>
            <div className="flex items-center gap-2 mt-1">
              <span className={cn(
                "px-2 py-0.5 text-[10px] font-medium uppercase tracking-wide",
                connection.environment === 'PRODUCTION' ? 'bg-neutral-900 text-white' : 'bg-neutral-100 text-neutral-600 dark:bg-primary-800 dark:text-neutral-300'
              )}>
                {connection.environment}
              </span>
              <span className={cn(
                "px-2 py-0.5 text-[10px] font-medium uppercase tracking-wide",
                connection.status === 'CONNECTED' ? 'bg-neutral-900 text-white' : 'bg-error-600 text-white'
              )}>
                {connection.status}
              </span>
            </div>
          </div>
        </div>
        <div className="text-right">
          <p className="text-[10px] uppercase tracking-wide text-neutral-500 dark:text-neutral-400">Last Sync</p>
          <p className="text-sm font-medium text-neutral-900 dark:text-neutral-50">{connection.lastSyncAt ? formatRelativeTime(connection.lastSyncAt) : '—'}</p>
        </div>
      </div>

      {/* Tabs */}
      <div className="flex gap-6 mb-6 border-b border-neutral-200 dark:border-primary-800">
        {[
          { id: 'overview', label: 'Overview' },
          { id: 'flows', label: 'Data Flows', count: connection.dataFlows?.length || 0 },
          { id: 'logs', label: 'Sync Logs', count: connectionLogs.length },
        ].map(tab => (
          <button
            key={tab.id}
            onClick={() => setActiveTab(tab.id as typeof activeTab)}
            className={cn(
              "pb-3 text-sm font-medium transition-colors border-b-2 -mb-px",
              activeTab === tab.id
                ? "border-neutral-900 text-neutral-900 dark:text-neutral-50"
                : "border-transparent text-neutral-500 hover:text-neutral-700 dark:text-neutral-400 dark:hover:text-neutral-200"
            )}
          >
            {tab.label}
            {tab.count !== undefined && (
              <span className="ml-2 px-1.5 py-0.5 text-[10px] bg-neutral-100 text-neutral-600 dark:bg-primary-800 dark:text-neutral-300">{tab.count}</span>
            )}
          </button>
        ))}
      </div>

      {/* Tab Content */}
      <div className="min-h-[300px]">
        {activeTab === 'overview' && (
          <div className="space-y-6">
            <div className="grid grid-cols-4 gap-4">
              {[
                { label: 'Frequency', value: connection.syncFrequency.replace('_', ' ') },
                { label: 'Next Sync', value: connection.nextSyncAt ? formatRelativeTime(connection.nextSyncAt) : 'Manual' },
                { label: 'Created', value: formatRelativeTime(connection.createdAt) },
                { label: 'Created By', value: connection.createdBy },
              ].map((item, idx) => (
                <div key={idx} className="p-4 bg-neutral-50 border border-neutral-100 dark:bg-primary-950 dark:border-primary-800/60">
                  <p className="text-[10px] uppercase tracking-wide text-neutral-500 dark:text-neutral-400">{item.label}</p>
                  <p className="text-sm font-medium text-neutral-900 mt-1 capitalize dark:text-neutral-50">{item.value}</p>
                </div>
              ))}
            </div>

            {connection.errorMessage && (
              <div className="p-4 bg-error-50 border border-error-200 dark:bg-error-500/10 dark:border-error-500/30">
                <div className="flex items-start gap-3">
                  <AlertTriangle className="w-5 h-5 text-error-600 mt-0.5 dark:text-error-300" />
                  <div>
                    <p className="text-sm font-medium text-error-800 dark:text-error-300">Connection Error</p>
                    <p className="text-sm text-error-700 mt-1 dark:text-error-300">{connection.errorMessage}</p>
                    <button className="mt-3 px-3 py-1.5 text-xs font-medium border border-error-300 text-error-700 hover:bg-error-100 transition-colors dark:text-error-300 dark:hover:bg-error-500/20">
                      Re-authenticate
                    </button>
                  </div>
                </div>
              </div>
            )}

            {connection.consentInfo && (
              <div className="p-4 bg-neutral-50 border border-neutral-200 dark:bg-primary-950 dark:border-primary-800">
                <p className="text-xs font-medium text-neutral-700 uppercase tracking-wide mb-3 dark:text-neutral-200">Open Banking Consent</p>
                <div className="grid grid-cols-2 gap-4 text-sm">
                  <div>
                    <p className="text-xs text-neutral-500 dark:text-neutral-400">Bank</p>
                    <p className="font-medium text-neutral-900 dark:text-neutral-50">{connection.consentInfo.aspspName}</p>
                  </div>
                  <div>
                    <p className="text-xs text-neutral-500 dark:text-neutral-400">Status</p>
                    <p className="font-medium text-neutral-900 dark:text-neutral-50">{connection.consentInfo.consentStatus}</p>
                  </div>
                  <div>
                    <p className="text-xs text-neutral-500 dark:text-neutral-400">Expires</p>
                    <p className="font-medium text-neutral-900 dark:text-neutral-50">{formatRelativeTime(connection.consentInfo.consentExpiresAt)}</p>
                  </div>
                  <div>
                    <p className="text-xs text-neutral-500 dark:text-neutral-400">Permissions</p>
                    <p className="font-medium text-neutral-900 dark:text-neutral-50">{connection.consentInfo.permissions.join(', ')}</p>
                  </div>
                </div>
              </div>
            )}

            <div className="flex gap-2">
              <button className="px-4 py-2 text-sm font-medium border border-neutral-300 hover:border-neutral-900 transition-colors flex items-center gap-2 dark:border-primary-700">
                <RefreshCw className="w-4 h-4" /> Sync Now
              </button>
              <button className="px-4 py-2 text-sm font-medium border border-neutral-300 hover:border-neutral-900 transition-colors flex items-center gap-2 dark:border-primary-700">
                <Settings className="w-4 h-4" /> Settings
              </button>
              <button className="px-4 py-2 text-sm font-medium border border-neutral-300 hover:border-neutral-900 transition-colors flex items-center gap-2 dark:border-primary-700">
                <Key className="w-4 h-4" /> Credentials
              </button>
            </div>
          </div>
        )}

        {activeTab === 'flows' && (
          <div className="space-y-3">
            {connection.dataFlows?.map(flow => (
              <div key={flow.id} className="border border-neutral-200 hover:border-neutral-300 transition-colors dark:border-primary-800 dark:hover:border-primary-700">
                <div className="p-4 flex items-center justify-between">
                  <div className="flex items-center gap-4">
                    <div className={cn(
                      "w-8 h-8 flex items-center justify-center",
                      flow.direction === 'INBOUND' ? 'bg-neutral-100 dark:bg-primary-800' : flow.direction === 'OUTBOUND' ? 'bg-neutral-900 text-white' : 'bg-neutral-200 dark:bg-primary-800'
                    )}>
                      {flow.direction === 'INBOUND' ? <Download className="w-4 h-4" /> :
                       flow.direction === 'OUTBOUND' ? <Upload className="w-4 h-4" /> :
                       <ArrowLeftRight className="w-4 h-4" />}
                    </div>
                    <div>
                      <p className="text-sm font-medium text-neutral-900 dark:text-neutral-50">{flow.flowName}</p>
                      <p className="text-xs text-neutral-500 dark:text-neutral-400">{flow.sourceEntity} → {flow.targetEntity}</p>
                    </div>
                  </div>
                  <div className="flex items-center gap-6">
                    <div className="text-right">
                      <p className="text-sm font-medium text-neutral-900 dark:text-neutral-50">{flow.recordsProcessed?.toLocaleString() || 0}</p>
                      <p className="text-[10px] uppercase tracking-wide text-neutral-500 dark:text-neutral-400">Records</p>
                    </div>
                    <span className={cn(
                      "px-2 py-0.5 text-[10px] font-medium uppercase tracking-wide",
                      flow.status === 'ACTIVE' ? 'bg-neutral-900 text-white' : flow.status === 'PAUSED' ? 'bg-neutral-200 text-neutral-600 dark:bg-primary-800 dark:text-neutral-300' : 'bg-error-600 text-white'
                    )}>
                      {flow.status}
                    </span>
                    <div className="flex gap-1">
                      <button onClick={() => onOpenMapping(flow)} className="p-2 text-neutral-400 hover:text-neutral-900 transition-colors dark:text-neutral-500 dark:hover:text-neutral-50" title="Field Mapping">
                        <FileText className="w-4 h-4" />
                      </button>
                      <button className="p-2 text-neutral-400 hover:text-neutral-900 transition-colors dark:text-neutral-500 dark:hover:text-neutral-50" title={flow.status === 'ACTIVE' ? 'Pause' : 'Resume'}>
                        {flow.status === 'ACTIVE' ? <Pause className="w-4 h-4" /> : <Play className="w-4 h-4" />}
                      </button>
                    </div>
                  </div>
                </div>
              </div>
            ))}
            <button className="w-full p-4 border border-dashed border-neutral-300 text-sm text-neutral-500 hover:border-neutral-900 hover:text-neutral-900 transition-colors flex items-center justify-center gap-2 dark:border-primary-700 dark:text-neutral-400 dark:hover:text-neutral-50">
              <Plus className="w-4 h-4" /> Add Data Flow
            </button>
          </div>
        )}

        {activeTab === 'logs' && (
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr className="border-b border-neutral-200 dark:border-primary-800">
                  <th className="pb-3 text-left overline">Flow</th>
                  <th className="pb-3 text-left overline">Direction</th>
                  <th className="pb-3 text-left overline">Time</th>
                  <th className="pb-3 text-right overline">Records</th>
                  <th className="pb-3 text-center overline">Status</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-neutral-100 dark:divide-primary-800/60">
                {connectionLogs.map(log => (
                  <tr key={log.id} className="hover:bg-neutral-50 dark:hover:bg-primary-800/50">
                    <td className="py-3 text-sm text-neutral-900 dark:text-neutral-50">{log.flowName}</td>
                    <td className="py-3">
                      <span className={cn(
                        "px-2 py-0.5 text-[10px] font-medium uppercase",
                        log.direction === 'INBOUND' ? 'bg-neutral-100 text-neutral-600 dark:bg-primary-800 dark:text-neutral-300' : 'bg-neutral-900 text-white'
                      )}>
                        {log.direction}
                      </span>
                    </td>
                    <td className="py-3 text-sm text-neutral-600 dark:text-neutral-300">{formatRelativeTime(log.startTime)}</td>
                    <td className="py-3 text-right">
                      <p className="text-sm font-medium text-neutral-900 dark:text-neutral-50">{log.recordsProcessed.toLocaleString()}</p>
                      {log.recordsFailed > 0 && <p className="text-xs text-error-600 dark:text-error-300">{log.recordsFailed} failed</p>}
                    </td>
                    <td className="py-3 text-center">
                      <span className={cn(
                        "px-2 py-0.5 text-[10px] font-medium uppercase",
                        log.status === 'SUCCESS' ? 'bg-neutral-900 text-white' :
                        log.status === 'PARTIAL' ? 'bg-warning-100 text-warning-800 dark:bg-warning-500/20 dark:text-warning-300' :
                        log.status === 'RUNNING' ? 'bg-info-100 text-info-800 dark:bg-info-500/20 dark:text-info-300' : 'bg-error-600 text-white'
                      )}>
                        {log.status}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Footer */}
      <div className="flex justify-end pt-6 mt-6 border-t border-neutral-200 dark:border-primary-800">
        <button onClick={onClose} className="px-4 py-2 text-sm text-neutral-600 hover:text-neutral-900 transition-colors dark:text-neutral-300 dark:hover:text-neutral-50">
          Close
        </button>
      </div>
    </Modal>
  );
};

// ============================================================================
// FIELD MAPPING MODAL
// ============================================================================

const FieldMappingModal: React.FC<{
  flow: DataFlow | null;
  onClose: () => void;
}> = ({ flow, onClose }) => {
  const [mappings, setMappings] = useState(sampleFieldMappings);

  if (!flow) return null;

  const addMapping = () => {
    setMappings([...mappings, { id: `new-${Date.now()}`, sourceField: '', targetField: '', isRequired: false }]);
  };

  const removeMapping = (id: string) => {
    setMappings(mappings.filter(m => m.id !== id));
  };

  return (
    <Modal isOpen={!!flow} onClose={onClose} title="" size="xl">
      {/* Header */}
      <div className="pb-6 border-b border-neutral-200 mb-6 dark:border-primary-800">
        <h2 className="text-lg font-medium text-neutral-900 dark:text-neutral-50">Field Mapping</h2>
        <p className="text-sm text-neutral-500 mt-1 dark:text-neutral-400">{flow.flowName}</p>
        <div className="flex items-center gap-2 mt-3">
          <span className="px-2 py-1 text-xs bg-neutral-100 text-neutral-700 dark:bg-primary-800 dark:text-neutral-200">{flow.sourceEntity}</span>
          <ArrowRight className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
          <span className="px-2 py-1 text-xs bg-neutral-900 text-white">{flow.targetEntity}</span>
        </div>
      </div>

      {/* Mapping Table */}
      <div className="border border-neutral-200 mb-4 dark:border-primary-800">
        <table className="w-full">
          <thead>
            <tr className="bg-neutral-50 border-b border-neutral-200 dark:bg-primary-950 dark:border-primary-800">
              <th className="px-4 py-3 text-left text-[10px] font-medium uppercase tracking-wide text-neutral-600 w-1/4 dark:text-neutral-300">Source</th>
              <th className="px-4 py-3 text-center text-[10px] font-medium uppercase tracking-wide text-neutral-600 w-12 dark:text-neutral-300"></th>
              <th className="px-4 py-3 text-left text-[10px] font-medium uppercase tracking-wide text-neutral-600 w-1/4 dark:text-neutral-300">Target</th>
              <th className="px-4 py-3 text-left text-[10px] font-medium uppercase tracking-wide text-neutral-600 dark:text-neutral-300">Transform</th>
              <th className="px-4 py-3 text-center text-[10px] font-medium uppercase tracking-wide text-neutral-600 w-20 dark:text-neutral-300">Required</th>
              <th className="px-4 py-3 text-center w-12"></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-neutral-100 dark:divide-primary-800/60">
            {mappings.map((mapping) => (
              <tr key={mapping.id} className="hover:bg-neutral-50 dark:hover:bg-primary-800/50">
                <td className="px-4 py-2">
                  <input
                    type="text"
                    value={mapping.sourceField}
                    onChange={e => setMappings(mappings.map(m => m.id === mapping.id ? { ...m, sourceField: e.target.value } : m))}
                    className="w-full px-2 py-1.5 border border-neutral-200 text-sm font-mono focus:border-neutral-900 focus:outline-none dark:border-primary-800"
                    placeholder="Source field"
                  />
                </td>
                <td className="px-4 py-2 text-center">
                  <ArrowRight className="w-4 h-4 text-neutral-300 mx-auto dark:text-neutral-600" />
                </td>
                <td className="px-4 py-2">
                  <input
                    type="text"
                    value={mapping.targetField}
                    onChange={e => setMappings(mappings.map(m => m.id === mapping.id ? { ...m, targetField: e.target.value } : m))}
                    className="w-full px-2 py-1.5 border border-neutral-200 text-sm font-mono focus:border-neutral-900 focus:outline-none dark:border-primary-800"
                    placeholder="Target field"
                  />
                </td>
                <td className="px-4 py-2">
                  <select
                    value={mapping.transformation || 'direct'}
                    onChange={e => setMappings(mappings.map(m => m.id === mapping.id ? { ...m, transformation: e.target.value } : m))}
                    className="w-full px-2 py-1.5 border border-neutral-200 text-sm focus:border-neutral-900 focus:outline-none bg-white dark:border-primary-800 dark:bg-primary-900"
                  >
                    <option value="direct">Direct</option>
                    <option value="trim">Trim</option>
                    <option value="uppercase">Uppercase</option>
                    <option value="lowercase">Lowercase</option>
                    <option value="number:2">Number (2 dec)</option>
                    <option value="date:YYYY-MM-DD">Date</option>
                    <option value="lookup:currency_map">Lookup</option>
                  </select>
                </td>
                <td className="px-4 py-2 text-center">
                  <input
                    type="checkbox"
                    checked={mapping.isRequired}
                    onChange={e => setMappings(mappings.map(m => m.id === mapping.id ? { ...m, isRequired: e.target.checked } : m))}
                    className="w-4 h-4 border-neutral-300 dark:border-primary-700"
                  />
                </td>
                <td className="px-4 py-2 text-center">
                  <button onClick={() => removeMapping(mapping.id)} className="p-1 text-neutral-400 hover:text-error-600 transition-colors dark:text-neutral-500">
                    <X className="w-4 h-4" />
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <button onClick={addMapping} className="text-sm text-neutral-600 hover:text-neutral-900 flex items-center gap-1 transition-colors dark:text-neutral-300 dark:hover:text-neutral-50">
        <Plus className="w-4 h-4" /> Add Mapping
      </button>

      {/* Footer */}
      <div className="flex justify-between pt-6 mt-6 border-t border-neutral-200 dark:border-primary-800">
        <button onClick={onClose} className="px-4 py-2 text-sm text-neutral-600 hover:text-neutral-900 transition-colors dark:text-neutral-300 dark:hover:text-neutral-50">
          Cancel
        </button>
        <div className="flex gap-2">
          <button className="px-4 py-2 text-sm font-medium border border-neutral-300 hover:border-neutral-900 transition-colors dark:border-primary-700">
            Test Mapping
          </button>
          <button onClick={onClose} className="px-6 py-2 bg-neutral-900 text-white text-sm font-medium hover:bg-neutral-800 transition-colors">
            Save
          </button>
        </div>
      </div>
    </Modal>
  );
};

// ============================================================================
// MAIN PAGE - Swiss Minimalism Design
// ============================================================================

const IntegrationsPage: React.FC = () => {
  const [activeTab, setActiveTab] = useState<'connections' | 'connectors' | 'logs'>('connections');
  const [selectedConnector, setSelectedConnector] = useState<Connector | null>(null);
  const [selectedConnection, setSelectedConnection] = useState<Connection | null>(null);
  const [selectedFlow, setSelectedFlow] = useState<DataFlow | null>(null);
  const [connections, setConnections] = useState(mockConnections);
  const [filterCategory, setFilterCategory] = useState<string>('all');
  const [searchTerm, setSearchTerm] = useState('');

  const connectedIds = new Set(connections.map(c => c.connectorId));

  const filteredConnectors = mockConnectors.filter(c => {
    const matchesCategory = filterCategory === 'all' || c.category === filterCategory;
    const matchesSearch = !searchTerm || 
      c.connectorName.toLowerCase().includes(searchTerm.toLowerCase()) ||
      c.description.toLowerCase().includes(searchTerm.toLowerCase());
    return matchesCategory && matchesSearch;
  });

  const handleSetupComplete = (config: Record<string, unknown>) => {
    if (selectedConnector) {
      const newConnection: Connection = {
        id: `new-${Date.now()}`,
        connectorId: selectedConnector.id,
        connectorCode: selectedConnector.connectorCode,
        connectorName: selectedConnector.connectorName,
        iconType: selectedConnector.iconType,
        connectionName: (config.name as string) || `My ${selectedConnector.shortName}`,
        environment: (config.environment as Environment) || 'SANDBOX',
        status: 'CONNECTED',
        syncFrequency: (config.syncFrequency as SyncFrequency) || 'DAILY',
        createdAt: new Date().toISOString(),
        createdBy: 'current@user.com',
        dataFlowCount: Math.min(selectedConnector.supportedFeatures.length, 3),
        dataFlows: selectedConnector.supportedFeatures.slice(0, 3).map((f, i) => ({
          id: `flow-${Date.now()}-${i}`,
          flowName: `${f} Sync`,
          direction: i % 2 === 0 ? 'INBOUND' : 'OUTBOUND' as FlowDirection,
          sourceEntity: `${selectedConnector.shortName} ${f}`,
          targetEntity: `VAM ${f}`,
          status: 'ACTIVE' as FlowStatus,
          scheduleEnabled: true,
        })),
      };
      setConnections([newConnection, ...connections]);
      setActiveTab('connections');
    }
  };

  return (
    <div className="min-h-screen bg-neutral-50 dark:bg-primary-950">
      {/* Phase 10 Design System Unification (2026-05-13): the banner chrome
          (header + tab nav below) keeps its full-width bg-white + border-b
          treatment but inner `max-w-7xl mx-auto` no longer adds `px-6` —
          the <main> shell already supplies horizontal padding. The content
          section below uses <Page maxWidth="default"> for max-width and
          space-y-6 rhythm. */}
      <div className="bg-white border-b border-neutral-200 dark:bg-primary-900 dark:border-primary-800">
        <div className="max-w-7xl mx-auto py-8">
          <div className="flex items-start justify-between">
            <div>
              <h1 className="text-2xl font-light text-neutral-900 tracking-tight dark:text-neutral-50">Integrations</h1>
              <p className="text-sm text-neutral-500 mt-1 dark:text-neutral-400">Connect ERP, treasury, and banking systems</p>
            </div>
            <div className="flex gap-2">
              <button className="px-4 py-2 text-sm border border-neutral-300 hover:border-neutral-900 transition-colors flex items-center gap-2 dark:border-primary-700">
                <FileText className="w-4 h-4" /> API Docs
              </button>
              <button
                onClick={() => setActiveTab('connectors')}
                className="px-4 py-2 text-sm bg-neutral-900 text-white hover:bg-neutral-800 transition-colors flex items-center gap-2"
              >
                <Plus className="w-4 h-4" /> Add Connection
              </button>
            </div>
          </div>

          {/* Stats */}
          <div className="grid grid-cols-5 gap-6 mt-8">
            {[
              { label: 'Total', value: mockStats.totalConnections, sub: 'connections' },
              { label: 'Active', value: mockStats.activeConnections, sub: 'connected' },
              { label: 'Errors', value: mockStats.errorConnections, sub: 'need attention', alert: mockStats.errorConnections > 0 },
              { label: 'Data Flows', value: mockStats.totalDataFlows, sub: 'configured' },
              { label: 'Connectors', value: mockStats.availableConnectors, sub: 'available' },
            ].map((stat, idx) => (
              <div key={idx} className="text-center">
                <p className={cn("text-3xl font-light", stat.alert ? 'text-error-600 dark:text-error-300' : 'text-neutral-900 dark:text-neutral-50')}>{stat.value}</p>
                <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">{stat.label}</p>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* Tab Navigation */}
      <div className="bg-white border-b border-neutral-200 dark:bg-primary-900 dark:border-primary-800">
        <div className="max-w-7xl mx-auto">
          <div className="flex gap-8">
            {[
              { id: 'connections', label: 'Active Connections', count: connections.length },
              { id: 'connectors', label: 'Available Connectors', count: mockConnectors.length },
              { id: 'logs', label: 'Sync History', count: mockSyncLogs.length },
            ].map(tab => (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id as typeof activeTab)}
                className={cn(
                  "py-4 text-sm font-medium border-b-2 -mb-px transition-colors",
                  activeTab === tab.id
                    ? "border-neutral-900 text-neutral-900 dark:text-neutral-50"
                    : "border-transparent text-neutral-500 hover:text-neutral-700 dark:text-neutral-400 dark:hover:text-neutral-200"
                )}
              >
                {tab.label}
                <span className="ml-2 text-xs text-neutral-400 dark:text-neutral-500">{tab.count}</span>
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* Content */}
      <Page maxWidth="default" className="py-8">
        {/* Active Connections */}
        {activeTab === 'connections' && (
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
            {connections.map(connection => (
              <ConnectionCard
                key={connection.id}
                connection={connection}
                onView={() => setSelectedConnection(connection)}
                onSync={() => {}}
                onDisconnect={() => setConnections(connections.filter(c => c.id !== connection.id))}
              />
            ))}
            {connections.length === 0 && (
              <div className="col-span-2 text-center py-16 border border-dashed border-neutral-300 dark:border-primary-700">
                <Link2 className="w-8 h-8 text-neutral-300 mx-auto mb-3 dark:text-neutral-600" />
                <p className="text-sm text-neutral-600 dark:text-neutral-300">No connections configured</p>
                <button
                  onClick={() => setActiveTab('connectors')}
                  className="mt-4 px-4 py-2 text-sm bg-neutral-900 text-white hover:bg-neutral-800 transition-colors"
                >
                  Browse Connectors
                </button>
              </div>
            )}
          </div>
        )}

        {/* Available Connectors */}
        {activeTab === 'connectors' && (
          <>
            {/* Filters */}
            <div className="flex items-center justify-between mb-6">
              <div className="flex gap-2">
                {['all', 'ERP', 'TREASURY', 'OPEN_BANKING', 'PAYMENTS', 'BANKING', 'GENERIC'].map(cat => (
                  <button
                    key={cat}
                    onClick={() => setFilterCategory(cat)}
                    className={cn(
                      "px-3 py-1.5 text-xs font-medium transition-all",
                      filterCategory === cat
                        ? "bg-neutral-900 text-white"
                        : "text-neutral-600 hover:text-neutral-900 dark:text-neutral-300 dark:hover:text-neutral-50"
                    )}
                  >
                    {cat === 'all' ? 'All' : cat === 'OPEN_BANKING' ? 'Open Banking' : cat.charAt(0) + cat.slice(1).toLowerCase()}
                  </button>
                ))}
              </div>
              <div className="relative">
                <Search className="w-4 h-4 text-neutral-400 absolute left-3 top-1/2 -translate-y-1/2 dark:text-neutral-500" />
                <input
                  type="text"
                  placeholder="Search connectors..."
                  value={searchTerm}
                  onChange={e => setSearchTerm(e.target.value)}
                  className="pl-9 pr-4 py-2 w-64 border border-neutral-300 text-sm focus:border-neutral-900 focus:outline-none transition-colors dark:border-primary-700"
                />
              </div>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
              {filteredConnectors.map(connector => (
                <ConnectorCard
                  key={connector.id}
                  connector={connector}
                  onSetup={() => setSelectedConnector(connector)}
                />
              ))}
            </div>

            {filteredConnectors.length === 0 && (
              <div className="text-center py-16">
                <p className="text-sm text-neutral-600 dark:text-neutral-300">No connectors match your filters</p>
              </div>
            )}
          </>
        )}

        {/* Sync Logs */}
        {activeTab === 'logs' && (
          <div className="bg-white border border-neutral-200 dark:bg-primary-900 dark:border-primary-800">
            <table className="w-full">
              <thead>
                <tr className="border-b border-neutral-200 bg-neutral-50 dark:border-primary-800 dark:bg-primary-950">
                  <th className="px-6 py-4 text-left overline">Connection</th>
                  <th className="px-6 py-4 text-left overline">Flow</th>
                  <th className="px-6 py-4 text-left overline">Direction</th>
                  <th className="px-6 py-4 text-left overline">Time</th>
                  <th className="px-6 py-4 text-right overline">Records</th>
                  <th className="px-6 py-4 text-center overline">Status</th>
                  <th className="px-6 py-4 text-left overline">Details</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-neutral-100 dark:divide-primary-800/60">
                {mockSyncLogs.map(log => (
                  <tr key={log.id} className="hover:bg-neutral-50 dark:hover:bg-primary-800/50">
                    <td className="px-6 py-4 text-sm font-medium text-neutral-900 dark:text-neutral-50">{log.connectionName}</td>
                    <td className="px-6 py-4 text-sm text-neutral-600 dark:text-neutral-300">{log.flowName}</td>
                    <td className="px-6 py-4">
                      <span className={cn(
                        "inline-flex items-center gap-1 px-2 py-0.5 text-[10px] font-medium uppercase",
                        log.direction === 'INBOUND' ? 'bg-neutral-100 text-neutral-600 dark:bg-primary-800 dark:text-neutral-300' : 'bg-neutral-900 text-white'
                      )}>
                        {log.direction === 'INBOUND' ? <Download className="w-3 h-3" /> : <Upload className="w-3 h-3" />}
                        {log.direction}
                      </span>
                    </td>
                    <td className="px-6 py-4 text-sm text-neutral-600 dark:text-neutral-300">{formatRelativeTime(log.startTime)}</td>
                    <td className="px-6 py-4 text-right">
                      <p className="text-sm font-medium text-neutral-900 dark:text-neutral-50">{log.recordsProcessed.toLocaleString()}</p>
                      {log.recordsFailed > 0 && <p className="text-xs text-error-600 dark:text-error-300">{log.recordsFailed} failed</p>}
                    </td>
                    <td className="px-6 py-4 text-center">
                      <span className={cn(
                        "px-2 py-0.5 text-[10px] font-medium uppercase",
                        log.status === 'SUCCESS' ? 'bg-neutral-900 text-white' :
                        log.status === 'PARTIAL' ? 'bg-warning-100 text-warning-800 dark:bg-warning-500/20 dark:text-warning-300' :
                        log.status === 'RUNNING' ? 'bg-info-100 text-info-800 dark:bg-info-500/20 dark:text-info-300' : 'bg-error-600 text-white'
                      )}>
                        {log.status}
                      </span>
                    </td>
                    <td className="px-6 py-4">
                      {log.errorMessage && (
                        <p className="text-xs text-error-600 truncate max-w-48 dark:text-error-300" title={log.errorMessage}>{log.errorMessage}</p>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Page>

      {/* Modals */}
      <SetupWizardModal
        connector={selectedConnector}
        onClose={() => setSelectedConnector(null)}
        onComplete={handleSetupComplete}
      />
      <ConnectionDetailModal
        connection={selectedConnection}
        onClose={() => setSelectedConnection(null)}
        onOpenMapping={(flow) => { setSelectedFlow(flow); }}
      />
      <FieldMappingModal
        flow={selectedFlow}
        onClose={() => setSelectedFlow(null)}
      />
    </div>
  );
};

export default IntegrationsPage;
