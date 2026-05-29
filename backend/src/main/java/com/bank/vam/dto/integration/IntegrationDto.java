package com.bank.vam.dto.integration;

import com.bank.vam.entity.integration.*;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Integration DTOs
 * 
 * Supports all connector types including Open Banking (PSD2, UK OB, etc.)
 */
public class IntegrationDto {

    // ========================================================================
    // CONNECTOR DTOs
    // ========================================================================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ConnectorResponse {
        private UUID id;
        private String connectorCode;
        private String connectorName;
        private String shortName;
        private String description;
        private IntegrationConnector.ConnectorCategory category;
        private IntegrationConnector.AuthType authType;
        private IntegrationConnector.ConnectorStatus status;
        private List<String> supportedFeatures;
        private List<CredentialFieldConfig> requiredCredentials;
        private String documentationUrl;
        private String logoUrl;
        private String iconType;
        private String region;
        private List<String> complianceStandards;
        private Integer sortOrder;
        private Boolean isBeta;
        private boolean isConnected;
        private int activeConnectionCount;
    }

    /**
     * Credential field configuration for dynamic form generation
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CredentialFieldConfig {
        private String fieldName;
        private String fieldType;      // string, file, integer, boolean, array, object
        private String label;
        private String placeholder;
        private String helpText;
        private Boolean required;
        private Boolean sensitive;     // Should be masked in UI
        private String validation;     // regex or validation rule
        private List<String> options;  // for select/dropdown fields
        private String defaultValue;
    }

    // ========================================================================
    // CONNECTION DTOs
    // ========================================================================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ConnectionResponse {
        private UUID id;
        private UUID connectorId;
        private String connectorCode;
        private String connectorName;
        private String connectorLogo;
        private String iconType;
        private String connectionName;
        private IntegrationConnection.Environment environment;
        private IntegrationConnection.SyncFrequency syncFrequency;
        private LocalDateTime lastSyncAt;
        private LocalDateTime nextSyncAt;
        private IntegrationConnection.ConnectionStatus status;
        private String errorMessage;
        private int dataFlowCount;
        private LocalDateTime createdAt;
        private String createdBy;
        private List<DataFlowResponse> dataFlows;
        
        // Open Banking specific
        private ConsentInfo consentInfo;
    }

    /**
     * Open Banking consent information
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ConsentInfo {
        private String consentId;
        private String consentStatus;
        private LocalDateTime consentCreatedAt;
        private LocalDateTime consentExpiresAt;
        private List<String> permissions;
        private String aspspName;
        private String aspspId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateConnectionRequest {
        private UUID connectorId;
        private String connectionName;
        private IntegrationConnection.Environment environment;
        private IntegrationConnection.SyncFrequency syncFrequency;
        private Map<String, Object> credentials;
        
        // Open Banking specific
        private String aspspId;           // Bank identifier for Open Banking
        private String redirectUri;       // OAuth redirect URI
        private List<String> permissions; // Requested permissions/scopes
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UpdateConnectionRequest {
        private String connectionName;
        private IntegrationConnection.SyncFrequency syncFrequency;
        private Map<String, Object> credentials;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TestConnectionRequest {
        private UUID connectorId;
        private Map<String, Object> credentials;
        private IntegrationConnection.Environment environment;
        private String aspspId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TestConnectionResponse {
        private boolean success;
        private String message;
        private Map<String, Object> details;
        private Long latencyMs;
        private List<String> warnings;
    }

    // ========================================================================
    // OPEN BANKING SPECIFIC DTOs
    // ========================================================================

    /**
     * Request to initiate Open Banking authorization
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class InitiateOpenBankingAuthRequest {
        private UUID connectorId;
        private String aspspId;
        private String connectionName;
        private IntegrationConnection.Environment environment;
        private List<String> permissions;
        private String redirectUri;
        private String state;
    }

    /**
     * Response containing authorization URL for Open Banking
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class InitiateOpenBankingAuthResponse {
        private String authorizationUrl;
        private String consentId;
        private String state;
        private LocalDateTime expiresAt;
    }

    /**
     * Callback after user completes Open Banking authorization
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class OpenBankingCallbackRequest {
        private String code;
        private String state;
        private String consentId;
        private String error;
        private String errorDescription;
    }

    /**
     * Available banks/ASPSPs for Open Banking
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AspspResponse {
        private String aspspId;
        private String name;
        private String bic;
        private String country;
        private String logoUrl;
        private List<String> supportedFeatures;
        private Boolean supportsAis;  // Account Information Services
        private Boolean supportsPis;  // Payment Initiation Services
        private Boolean supportsCof;  // Confirmation of Funds
    }

    // ========================================================================
    // DATA FLOW DTOs
    // ========================================================================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DataFlowResponse {
        private UUID id;
        private String flowName;
        private IntegrationDataFlow.FlowDirection direction;
        private String sourceEntity;
        private String targetEntity;
        private Boolean scheduleEnabled;
        private LocalDateTime lastRunAt;
        private Integer recordsProcessed;
        private IntegrationDataFlow.FlowStatus status;
        private List<FieldMappingResponse> fieldMappings;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateDataFlowRequest {
        private UUID connectionId;
        private String flowName;
        private IntegrationDataFlow.FlowDirection direction;
        private String sourceEntity;
        private String targetEntity;
        private Boolean scheduleEnabled;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class FieldMappingResponse {
        private UUID id;
        private String sourceField;
        private String targetField;
        private String transformation;
        private Map<String, Object> transformationParams;
        private Boolean isRequired;
        private String defaultValue;
        private Integer sortOrder;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UpdateFieldMappingsRequest {
        private List<FieldMappingRequest> mappings;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class FieldMappingRequest {
        private UUID id; // null for new mappings
        private String sourceField;
        private String targetField;
        private String transformation;
        private Map<String, Object> transformationParams;
        private Boolean isRequired;
        private String defaultValue;
        private Integer sortOrder;
    }

    // ========================================================================
    // SYNC LOG DTOs
    // ========================================================================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SyncLogResponse {
        private UUID id;
        private UUID connectionId;
        private String connectionName;
        private UUID flowId;
        private String flowName;
        private IntegrationDataFlow.FlowDirection direction;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private Long durationMs;
        private Integer recordsProcessed;
        private Integer recordsFailed;
        private IntegrationSyncLog.SyncStatus status;
        private String errorMessage;
        private String errorDetails;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TriggerSyncRequest {
        private UUID connectionId;
        private List<UUID> flowIds; // Optional, if empty sync all flows
        private Boolean forceRefresh;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TriggerSyncResponse {
        private UUID syncId;
        private String status;
        private String message;
        private LocalDateTime estimatedCompletionTime;
    }

    // ========================================================================
    // STATS DTO
    // ========================================================================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class IntegrationStatsResponse {
        private long totalConnections;
        private long activeConnections;
        private long errorConnections;
        private long syncingConnections;
        private long totalDataFlows;
        private long activeDataFlows;
        private long availableConnectors;
        
        // By Category breakdown
        private CategoryStats categoryStats;
        
        // Recent activity
        private long todaySyncCount;
        private long todayRecordsProcessed;
        private long todayRecordsFailed;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CategoryStats {
        private long erpConnections;
        private long treasuryConnections;
        private long bankingConnections;
        private long openBankingConnections;
        private long paymentsConnections;
        private long genericConnections;
    }

    // ========================================================================
    // FILTER/SEARCH DTOs
    // ========================================================================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ConnectorFilter {
        private IntegrationConnector.ConnectorCategory category;
        private IntegrationConnector.ConnectorStatus status;
        private IntegrationConnector.AuthType authType;
        private String region;
        private String searchTerm;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SyncLogFilter {
        private UUID connectionId;
        private UUID flowId;
        private IntegrationSyncLog.SyncStatus status;
        private LocalDateTime startDate;
        private LocalDateTime endDate;
    }
}
