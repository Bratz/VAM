import { useState, useEffect, useCallback } from 'react';
import { integrationsApi } from '../services/api';

// ============================================================================
// TYPES
// ============================================================================

export type ConnectorCategory = 'ERP' | 'TREASURY' | 'BANKING' | 'OPEN_BANKING' | 'PAYMENTS' | 'GENERIC';
export type AuthType = 'OAUTH' | 'OAUTH2_AUTHCODE' | 'API_KEY' | 'BASIC' | 'CERTIFICATE' | 'PSD2_CONSENT' | 'OPEN_BANKING_UK' | 'BERLIN_GROUP' | 'STET' | 'POLISH_API' | 'MTLS';
export type ConnectorStatus = 'AVAILABLE' | 'BETA' | 'COMING_SOON' | 'DEPRECATED';
export type ConnectionStatus = 'CONNECTED' | 'DISCONNECTED' | 'ERROR' | 'SYNCING';
export type SyncFrequency = 'REAL_TIME' | 'HOURLY' | 'DAILY' | 'MANUAL';
export type Environment = 'SANDBOX' | 'PRODUCTION';
export type FlowDirection = 'INBOUND' | 'OUTBOUND' | 'BIDIRECTIONAL';
export type FlowStatus = 'ACTIVE' | 'PAUSED' | 'ERROR';
export type SyncStatus = 'SUCCESS' | 'FAILED' | 'PARTIAL' | 'RUNNING';

export interface IntegrationConnector {
  id: string;
  connectorCode: string;
  connectorName: string;
  shortName: string;
  description: string;
  category: ConnectorCategory;
  authType: AuthType;
  status: ConnectorStatus;
  supportedFeatures: string[];
  requiredCredentials?: CredentialFieldConfig[];
  documentationUrl?: string;
  iconType: string;
  region?: string;
  complianceStandards?: string[];
  isBeta: boolean;
  isConnected: boolean;
  activeConnectionCount: number;
}

export interface CredentialFieldConfig {
  fieldName: string;
  fieldType: string;
  label: string;
  placeholder?: string;
  helpText?: string;
  required: boolean;
  sensitive: boolean;
  validation?: string;
  options?: string[];
  defaultValue?: string;
}

export interface IntegrationConnection {
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

export interface ConsentInfo {
  consentId: string;
  consentStatus: string;
  consentCreatedAt: string;
  consentExpiresAt: string;
  permissions: string[];
  aspspName: string;
  aspspId: string;
}

export interface DataFlow {
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

export interface FieldMapping {
  id: string;
  sourceField: string;
  targetField: string;
  transformation?: string;
  transformationParams?: Record<string, unknown>;
  isRequired: boolean;
  defaultValue?: string;
  sortOrder?: number;
}

export interface SyncLog {
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

export interface IntegrationStats {
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
  todaySyncCount?: number;
  todayRecordsProcessed?: number;
  todayRecordsFailed?: number;
}

export interface CreateConnectionRequest {
  connectorId: string;
  connectionName: string;
  environment: Environment;
  syncFrequency: SyncFrequency;
  credentials: Record<string, unknown>;
  aspspId?: string;
  redirectUri?: string;
  permissions?: string[];
}

export interface TestConnectionRequest {
  connectorId: string;
  credentials: Record<string, unknown>;
  environment: Environment;
  aspspId?: string;
}

export interface TestConnectionResponse {
  success: boolean;
  message: string;
  details?: Record<string, unknown>;
  latencyMs?: number;
  warnings?: string[];
}

export interface InitiateOpenBankingAuthRequest {
  connectorId: string;
  aspspId: string;
  connectionName: string;
  environment: Environment;
  permissions: string[];
  redirectUri: string;
  state?: string;
}

export interface InitiateOpenBankingAuthResponse {
  authorizationUrl: string;
  consentId: string;
  state: string;
  expiresAt: string;
}

export interface AspspResponse {
  aspspId: string;
  name: string;
  bic?: string;
  country: string;
  logoUrl?: string;
  supportedFeatures: string[];
  supportsAis: boolean;
  supportsPis: boolean;
  supportsCof: boolean;
}

export interface ConnectorFilter {
  category?: ConnectorCategory;
  status?: ConnectorStatus;
  region?: string;
  search?: string;
}

// ============================================================================
// HOOK
// ============================================================================

export const useIntegrations = () => {
  const [connectors, setConnectors] = useState<IntegrationConnector[]>([]);
  const [connections, setConnections] = useState<IntegrationConnection[]>([]);
  const [stats, setStats] = useState<IntegrationStats | null>(null);
  const [syncLogs, setSyncLogs] = useState<SyncLog[]>([]);
  const [aspsps, setAspsps] = useState<AspspResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Fetch all data
  const fetchAll = useCallback(async () => {
    try {
      setLoading(true);
      setError(null);
      
      const [statsRes, connectorsRes, connectionsRes, logsRes] = await Promise.all([
        integrationsApi.getStats(),
        integrationsApi.getAllConnectors(),
        integrationsApi.getAllConnections(),
        integrationsApi.getSyncLogs(),
      ]);
      
      if (statsRes.success) setStats(statsRes.data);
      if (connectorsRes.success) setConnectors(connectorsRes.data);
      if (connectionsRes.success) setConnections(connectionsRes.data);
      if (logsRes.success) setSyncLogs(logsRes.data.content || logsRes.data);
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to fetch integrations';
      console.error('Failed to fetch integrations:', err);
      setError(message);
    } finally {
      setLoading(false);
    }
  }, []);

  // Fetch connectors with filter
  const fetchConnectors = useCallback(async (filter?: ConnectorFilter) => {
    try {
      const params = new URLSearchParams();
      if (filter?.category) params.append('category', filter.category);
      if (filter?.status) params.append('status', filter.status);
      if (filter?.region) params.append('region', filter.region);
      if (filter?.search) params.append('search', filter.search);
      
      const response = await integrationsApi.getAllConnectors(params.toString());
      if (response.success) {
        setConnectors(response.data);
      }
      return response.data;
    } catch (err) {
      console.error('Failed to fetch connectors:', err);
      throw err;
    }
  }, []);

  // Create connection
  const createConnection = useCallback(async (data: CreateConnectionRequest) => {
    try {
      const response = await integrationsApi.createConnection(data);
      if (response.success) {
        setConnections(prev => [response.data, ...prev]);
        return response.data;
      }
      throw new Error(response.message || 'Failed to create connection');
    } catch (err) {
      console.error('Failed to create connection:', err);
      throw err;
    }
  }, []);

  // Update connection
  const updateConnection = useCallback(async (id: string, data: Partial<CreateConnectionRequest>) => {
    try {
      const response = await integrationsApi.updateConnection(id, data);
      if (response.success) {
        setConnections(prev => prev.map(c => c.id === id ? response.data : c));
        return response.data;
      }
      throw new Error(response.message || 'Failed to update connection');
    } catch (err) {
      console.error('Failed to update connection:', err);
      throw err;
    }
  }, []);

  // Delete connection
  const deleteConnection = useCallback(async (id: string) => {
    try {
      await integrationsApi.deleteConnection(id);
      setConnections(prev => prev.filter(c => c.id !== id));
    } catch (err) {
      console.error('Failed to delete connection:', err);
      throw err;
    }
  }, []);

  // Test connection
  const testConnection = useCallback(async (data: TestConnectionRequest): Promise<TestConnectionResponse> => {
    try {
      const response = await integrationsApi.testConnection(data);
      return response.data;
    } catch (err) {
      console.error('Failed to test connection:', err);
      throw err;
    }
  }, []);

  // Trigger sync
  const triggerSync = useCallback(async (connectionId: string, flowIds?: string[]) => {
    try {
      const response = await integrationsApi.triggerSync({ connectionId, flowIds });
      if (response.success) {
        // Refresh connections to get updated status
        await fetchAll();
        return response.data;
      }
      throw new Error(response.message || 'Failed to trigger sync');
    } catch (err) {
      console.error('Failed to trigger sync:', err);
      throw err;
    }
  }, [fetchAll]);

  // Open Banking: Fetch ASPSPs
  const fetchAspsps = useCallback(async (country?: string, connectorCode?: string) => {
    try {
      const response = await integrationsApi.getAspsps(country, connectorCode);
      if (response.success) {
        setAspsps(response.data);
        return response.data;
      }
      throw new Error(response.message || 'Failed to fetch banks');
    } catch (err) {
      console.error('Failed to fetch ASPSPs:', err);
      throw err;
    }
  }, []);

  // Open Banking: Initiate authorization
  const initiateOpenBankingAuth = useCallback(async (data: InitiateOpenBankingAuthRequest): Promise<InitiateOpenBankingAuthResponse> => {
    try {
      const response = await integrationsApi.initiateOpenBankingAuth(data);
      if (response.success) {
        return response.data;
      }
      throw new Error(response.message || 'Failed to initiate authorization');
    } catch (err) {
      console.error('Failed to initiate Open Banking auth:', err);
      throw err;
    }
  }, []);

  // Open Banking: Handle callback
  const handleOpenBankingCallback = useCallback(async (code: string, state: string) => {
    try {
      const response = await integrationsApi.handleOpenBankingCallback({ code, state });
      if (response.success) {
        setConnections(prev => [response.data, ...prev]);
        return response.data;
      }
      throw new Error(response.message || 'Failed to complete authorization');
    } catch (err) {
      console.error('Failed to handle Open Banking callback:', err);
      throw err;
    }
  }, []);

  // Open Banking: Refresh consent
  const refreshConsent = useCallback(async (connectionId: string): Promise<InitiateOpenBankingAuthResponse> => {
    try {
      const response = await integrationsApi.refreshConsent(connectionId);
      if (response.success) {
        return response.data;
      }
      throw new Error(response.message || 'Failed to refresh consent');
    } catch (err) {
      console.error('Failed to refresh consent:', err);
      throw err;
    }
  }, []);

  // Open Banking: Revoke consent
  const revokeConsent = useCallback(async (connectionId: string) => {
    try {
      await integrationsApi.revokeConsent(connectionId);
      setConnections(prev => prev.filter(c => c.id !== connectionId));
    } catch (err) {
      console.error('Failed to revoke consent:', err);
      throw err;
    }
  }, []);

  // Data Flow operations
  const createDataFlow = useCallback(async (connectionId: string, data: Partial<DataFlow>) => {
    try {
      const response = await integrationsApi.createDataFlow(connectionId, data);
      if (response.success) {
        await fetchAll();
        return response.data;
      }
      throw new Error(response.message || 'Failed to create data flow');
    } catch (err) {
      console.error('Failed to create data flow:', err);
      throw err;
    }
  }, [fetchAll]);

  const updateFieldMappings = useCallback(async (flowId: string, mappings: FieldMapping[]) => {
    try {
      const response = await integrationsApi.updateFieldMappings(flowId, { mappings });
      if (response.success) {
        return response.data;
      }
      throw new Error(response.message || 'Failed to update field mappings');
    } catch (err) {
      console.error('Failed to update field mappings:', err);
      throw err;
    }
  }, []);

  const toggleFlowStatus = useCallback(async (flowId: string) => {
    try {
      const response = await integrationsApi.toggleFlowStatus(flowId);
      if (response.success) {
        await fetchAll();
        return response.data;
      }
      throw new Error(response.message || 'Failed to toggle flow status');
    } catch (err) {
      console.error('Failed to toggle flow status:', err);
      throw err;
    }
  }, [fetchAll]);

  // Fetch sync logs with filtering
  const fetchSyncLogs = useCallback(async (connectionId?: string, flowId?: string) => {
    try {
      const response = await integrationsApi.getSyncLogs(connectionId, flowId);
      if (response.success) {
        const logs = response.data.content || response.data;
        setSyncLogs(logs);
        return logs;
      }
      throw new Error(response.message || 'Failed to fetch sync logs');
    } catch (err) {
      console.error('Failed to fetch sync logs:', err);
      throw err;
    }
  }, []);

  // Initial fetch
  useEffect(() => {
    fetchAll();
  }, [fetchAll]);

  return {
    // State
    connectors,
    connections,
    stats,
    syncLogs,
    aspsps,
    loading,
    error,
    
    // Actions
    fetchAll,
    fetchConnectors,
    createConnection,
    updateConnection,
    deleteConnection,
    testConnection,
    triggerSync,
    
    // Open Banking
    fetchAspsps,
    initiateOpenBankingAuth,
    handleOpenBankingCallback,
    refreshConsent,
    revokeConsent,
    
    // Data Flows
    createDataFlow,
    updateFieldMappings,
    toggleFlowStatus,
    
    // Logs
    fetchSyncLogs,
  };
};

export default useIntegrations;
