/**
 * Integrations API Client
 * 
 * API endpoints for managing system integrations:
 * - ERP systems (SAP, Oracle, Dynamics)
 * - Treasury systems (Kyriba, FIS)
 * - Open Banking (PSD2, UK OB, aggregators)
 * - Payment networks (SWIFT, SEPA)
 * - Generic connectors (SFTP, REST, Webhooks)
 */

import { apiClient, ApiResponse } from './api';

// ============================================================================
// TYPES
// ============================================================================

export interface IntegrationStatsResponse {
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

export interface ConnectorResponse {
  id: string;
  connectorCode: string;
  connectorName: string;
  shortName: string;
  description: string;
  category: string;
  authType: string;
  status: string;
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

export interface ConnectionResponse {
  id: string;
  connectorId: string;
  connectorCode: string;
  connectorName: string;
  iconType: string;
  connectionName: string;
  environment: string;
  syncFrequency: string;
  lastSyncAt?: string;
  nextSyncAt?: string;
  status: string;
  errorMessage?: string;
  dataFlowCount: number;
  createdAt: string;
  createdBy: string;
  dataFlows?: DataFlowResponse[];
  consentInfo?: ConsentInfoResponse;
}

export interface ConsentInfoResponse {
  consentId: string;
  consentStatus: string;
  consentCreatedAt: string;
  consentExpiresAt: string;
  permissions: string[];
  aspspName: string;
  aspspId: string;
}

export interface DataFlowResponse {
  id: string;
  flowName: string;
  direction: string;
  sourceEntity: string;
  targetEntity: string;
  scheduleEnabled: boolean;
  lastRunAt?: string;
  recordsProcessed?: number;
  status: string;
  fieldMappings?: FieldMappingResponse[];
}

export interface FieldMappingResponse {
  id: string;
  sourceField: string;
  targetField: string;
  transformation?: string;
  transformationParams?: Record<string, unknown>;
  isRequired: boolean;
  defaultValue?: string;
  sortOrder?: number;
}

export interface SyncLogResponse {
  id: string;
  connectionId: string;
  connectionName: string;
  flowId?: string;
  flowName: string;
  direction: string;
  startTime: string;
  endTime?: string;
  durationMs?: number;
  recordsProcessed: number;
  recordsFailed: number;
  status: string;
  errorMessage?: string;
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

export interface CreateConnectionRequest {
  connectorId: string;
  connectionName: string;
  environment: string;
  syncFrequency: string;
  credentials: Record<string, unknown>;
  aspspId?: string;
  redirectUri?: string;
  permissions?: string[];
}

export interface UpdateConnectionRequest {
  connectionName?: string;
  syncFrequency?: string;
  credentials?: Record<string, unknown>;
}

export interface TestConnectionRequest {
  connectorId: string;
  credentials: Record<string, unknown>;
  environment: string;
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
  environment: string;
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

export interface OpenBankingCallbackRequest {
  code: string;
  state: string;
  consentId?: string;
  error?: string;
  errorDescription?: string;
}

export interface TriggerSyncRequest {
  connectionId: string;
  flowIds?: string[];
  forceRefresh?: boolean;
}

export interface TriggerSyncResponse {
  syncId: string;
  status: string;
  message: string;
  estimatedCompletionTime?: string;
}

export interface CreateDataFlowRequest {
  connectionId: string;
  flowName: string;
  direction: string;
  sourceEntity: string;
  targetEntity: string;
  scheduleEnabled?: boolean;
}

export interface UpdateFieldMappingsRequest {
  mappings: FieldMappingRequest[];
}

export interface FieldMappingRequest {
  id?: string;
  sourceField: string;
  targetField: string;
  transformation?: string;
  transformationParams?: Record<string, unknown>;
  isRequired?: boolean;
  defaultValue?: string;
  sortOrder?: number;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

// ============================================================================
// API CLIENT
// ============================================================================

const BASE_URL = '/api/v1/integrations';

export const integrationsApi = {
  // ========================================================================
  // STATS
  // ========================================================================
  
  getStats: (): Promise<ApiResponse<IntegrationStatsResponse>> =>
    apiClient.get<ApiResponse<IntegrationStatsResponse>>(`${BASE_URL}/stats`).then(r => r.data),

  // ========================================================================
  // CONNECTORS
  // ========================================================================
  
  getAllConnectors: (queryParams?: string): Promise<ApiResponse<ConnectorResponse[]>> =>
    apiClient.get<ApiResponse<ConnectorResponse[]>>(
      `${BASE_URL}/connectors${queryParams ? `?${queryParams}` : ''}`
    ).then(r => r.data),

  getConnectorById: (id: string): Promise<ApiResponse<ConnectorResponse>> =>
    apiClient.get<ApiResponse<ConnectorResponse>>(`${BASE_URL}/connectors/${id}`).then(r => r.data),

  getConnectorByCode: (code: string): Promise<ApiResponse<ConnectorResponse>> =>
    apiClient.get<ApiResponse<ConnectorResponse>>(`${BASE_URL}/connectors/code/${code}`).then(r => r.data),

  getConnectorsByCategory: (category: string): Promise<ApiResponse<ConnectorResponse[]>> =>
    apiClient.get<ApiResponse<ConnectorResponse[]>>(`${BASE_URL}/connectors/category/${category}`).then(r => r.data),

  // ========================================================================
  // CONNECTIONS
  // ========================================================================
  
  getAllConnections: (category?: string, status?: string): Promise<ApiResponse<ConnectionResponse[]>> => {
    const params = new URLSearchParams();
    if (category) params.append('category', category);
    if (status) params.append('status', status);
    const query = params.toString();
    return apiClient.get<ApiResponse<ConnectionResponse[]>>(
      `${BASE_URL}/connections${query ? `?${query}` : ''}`
    ).then(r => r.data);
  },

  getConnectionById: (id: string): Promise<ApiResponse<ConnectionResponse>> =>
    apiClient.get<ApiResponse<ConnectionResponse>>(`${BASE_URL}/connections/${id}`).then(r => r.data),

  createConnection: (data: CreateConnectionRequest): Promise<ApiResponse<ConnectionResponse>> =>
    apiClient.post<ApiResponse<ConnectionResponse>>(`${BASE_URL}/connections`, data).then(r => r.data),

  updateConnection: (id: string, data: UpdateConnectionRequest): Promise<ApiResponse<ConnectionResponse>> =>
    apiClient.put<ApiResponse<ConnectionResponse>>(`${BASE_URL}/connections/${id}`, data).then(r => r.data),

  deleteConnection: (id: string): Promise<ApiResponse<void>> =>
    apiClient.delete<ApiResponse<void>>(`${BASE_URL}/connections/${id}`).then(r => r.data),

  testConnection: (data: TestConnectionRequest): Promise<ApiResponse<TestConnectionResponse>> =>
    apiClient.post<ApiResponse<TestConnectionResponse>>(`${BASE_URL}/connections/test`, data).then(r => r.data),

  reconnectConnection: (id: string): Promise<ApiResponse<ConnectionResponse>> =>
    apiClient.post<ApiResponse<ConnectionResponse>>(`${BASE_URL}/connections/${id}/reconnect`).then(r => r.data),

  // ========================================================================
  // OPEN BANKING
  // ========================================================================
  
  getAspsps: (country?: string, connectorCode?: string, search?: string): Promise<ApiResponse<AspspResponse[]>> => {
    const params = new URLSearchParams();
    if (country) params.append('country', country);
    if (connectorCode) params.append('connectorCode', connectorCode);
    if (search) params.append('search', search);
    const query = params.toString();
    return apiClient.get<ApiResponse<AspspResponse[]>>(
      `${BASE_URL}/open-banking/aspsps${query ? `?${query}` : ''}`
    ).then(r => r.data);
  },

  initiateOpenBankingAuth: (data: InitiateOpenBankingAuthRequest): Promise<ApiResponse<InitiateOpenBankingAuthResponse>> =>
    apiClient.post<ApiResponse<InitiateOpenBankingAuthResponse>>(`${BASE_URL}/open-banking/authorize`, data).then(r => r.data),

  handleOpenBankingCallback: (data: OpenBankingCallbackRequest): Promise<ApiResponse<ConnectionResponse>> =>
    apiClient.post<ApiResponse<ConnectionResponse>>(`${BASE_URL}/open-banking/callback`, data).then(r => r.data),

  refreshConsent: (connectionId: string): Promise<ApiResponse<InitiateOpenBankingAuthResponse>> =>
    apiClient.post<ApiResponse<InitiateOpenBankingAuthResponse>>(`${BASE_URL}/connections/${connectionId}/refresh-consent`).then(r => r.data),

  revokeConsent: (connectionId: string): Promise<ApiResponse<void>> =>
    apiClient.delete<ApiResponse<void>>(`${BASE_URL}/connections/${connectionId}/revoke-consent`).then(r => r.data),

  // ========================================================================
  // DATA FLOWS
  // ========================================================================
  
  getDataFlows: (connectionId: string): Promise<ApiResponse<DataFlowResponse[]>> =>
    apiClient.get<ApiResponse<DataFlowResponse[]>>(`${BASE_URL}/connections/${connectionId}/flows`).then(r => r.data),

  getDataFlowById: (flowId: string): Promise<ApiResponse<DataFlowResponse>> =>
    apiClient.get<ApiResponse<DataFlowResponse>>(`${BASE_URL}/flows/${flowId}`).then(r => r.data),

  createDataFlow: (connectionId: string, data: Partial<CreateDataFlowRequest>): Promise<ApiResponse<DataFlowResponse>> =>
    apiClient.post<ApiResponse<DataFlowResponse>>(`${BASE_URL}/flows`, { ...data, connectionId }).then(r => r.data),

  updateDataFlow: (flowId: string, data: Partial<CreateDataFlowRequest>): Promise<ApiResponse<DataFlowResponse>> =>
    apiClient.put<ApiResponse<DataFlowResponse>>(`${BASE_URL}/flows/${flowId}`, data).then(r => r.data),

  deleteDataFlow: (flowId: string): Promise<ApiResponse<void>> =>
    apiClient.delete<ApiResponse<void>>(`${BASE_URL}/flows/${flowId}`).then(r => r.data),

  updateFieldMappings: (flowId: string, data: UpdateFieldMappingsRequest): Promise<ApiResponse<DataFlowResponse>> =>
    apiClient.put<ApiResponse<DataFlowResponse>>(`${BASE_URL}/flows/${flowId}/mappings`, data).then(r => r.data),

  toggleFlowStatus: (flowId: string): Promise<ApiResponse<DataFlowResponse>> =>
    apiClient.post<ApiResponse<DataFlowResponse>>(`${BASE_URL}/flows/${flowId}/toggle`).then(r => r.data),

  // ========================================================================
  // SYNC
  // ========================================================================
  
  triggerSync: (data: TriggerSyncRequest): Promise<ApiResponse<TriggerSyncResponse>> =>
    apiClient.post<ApiResponse<TriggerSyncResponse>>(`${BASE_URL}/sync`, data).then(r => r.data),

  triggerConnectionSync: (connectionId: string, forceRefresh = false): Promise<ApiResponse<TriggerSyncResponse>> =>
    apiClient.post<ApiResponse<TriggerSyncResponse>>(
      `${BASE_URL}/connections/${connectionId}/sync?forceRefresh=${forceRefresh}`
    ).then(r => r.data),

  // ========================================================================
  // SYNC LOGS
  // ========================================================================
  
  getSyncLogs: (connectionId?: string, flowId?: string, page = 0, size = 20): Promise<ApiResponse<PageResponse<SyncLogResponse>>> => {
    const params = new URLSearchParams();
    if (connectionId) params.append('connectionId', connectionId);
    if (flowId) params.append('flowId', flowId);
    params.append('page', page.toString());
    params.append('size', size.toString());
    params.append('sort', 'startTime,desc');
    return apiClient.get<ApiResponse<PageResponse<SyncLogResponse>>>(
      `${BASE_URL}/logs?${params.toString()}`
    ).then(r => r.data);
  },

  getSyncLogsByConnection: (connectionId: string, page = 0, size = 20): Promise<ApiResponse<PageResponse<SyncLogResponse>>> =>
    apiClient.get<ApiResponse<PageResponse<SyncLogResponse>>>(
      `${BASE_URL}/connections/${connectionId}/logs?page=${page}&size=${size}&sort=startTime,desc`
    ).then(r => r.data),

  getSyncLogById: (logId: string): Promise<ApiResponse<SyncLogResponse>> =>
    apiClient.get<ApiResponse<SyncLogResponse>>(`${BASE_URL}/logs/${logId}`).then(r => r.data),
};

export default integrationsApi;
