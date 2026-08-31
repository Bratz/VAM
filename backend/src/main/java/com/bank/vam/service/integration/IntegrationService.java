package com.bank.vam.service.integration;

import com.bank.vam.dto.integration.IntegrationDto;
import com.bank.vam.entity.integration.*;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.integration.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrationService {

    private final IntegrationConnectorRepository connectorRepository;
    private final IntegrationConnectionRepository connectionRepository;
    private final IntegrationDataFlowRepository dataFlowRepository;
    private final IntegrationSyncLogRepository syncLogRepository;
    private final ObjectMapper objectMapper;

    // ========================================================================
    // CONNECTOR OPERATIONS
    // ========================================================================

    @Transactional(readOnly = true)
    public List<IntegrationDto.ConnectorResponse> getAllConnectors() {
        Set<UUID> connectedConnectorIds = connectionRepository.findAll().stream()
                .filter(c -> c.getStatus() == IntegrationConnection.ConnectionStatus.CONNECTED)
                .map(c -> c.getConnector().getId())
                .collect(Collectors.toSet());

        return connectorRepository.findAll().stream()
                .map(c -> toConnectorResponse(c, connectedConnectorIds.contains(c.getId())))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<IntegrationDto.ConnectorResponse> getConnectorsByCategory(IntegrationConnector.ConnectorCategory category) {
        return connectorRepository.findByCategory(category).stream()
                .map(c -> toConnectorResponse(c, false))
                .collect(Collectors.toList());
    }

    /**
     * Get connectors with filter - Used by IntegrationController line 71
     */
    @Transactional(readOnly = true)
    public List<IntegrationDto.ConnectorResponse> getConnectors(IntegrationDto.ConnectorFilter filter) {
        List<IntegrationConnector> connectors;
        
        if (filter != null && filter.getCategory() != null) {
            connectors = connectorRepository.findByCategory(filter.getCategory());
        } else if (filter != null && filter.getStatus() != null) {
            connectors = connectorRepository.findByStatus(filter.getStatus());
        } else if (filter != null && filter.getSearchTerm() != null && !filter.getSearchTerm().isEmpty()) {
            connectors = connectorRepository.searchConnectors(filter.getSearchTerm());
        } else {
            connectors = connectorRepository.findAll();
        }
        
        Set<UUID> connectedConnectorIds = connectionRepository.findAll().stream()
                .filter(c -> c.getStatus() == IntegrationConnection.ConnectionStatus.CONNECTED)
                .map(c -> c.getConnector().getId())
                .collect(Collectors.toSet());
        
        return connectors.stream()
                .map(c -> toConnectorResponse(c, connectedConnectorIds.contains(c.getId())))
                .collect(Collectors.toList());
    }

    /**
     * Get connector by ID - Used by IntegrationController line 79
     */
    @Transactional(readOnly = true)
    public IntegrationDto.ConnectorResponse getConnectorById(UUID id) {
        IntegrationConnector connector = connectorRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Connector not found: " + id));
        
        boolean isConnected = connectionRepository.hasActiveConnections(id);
        return toConnectorResponse(connector, isConnected);
    }

    /**
     * Get connector by code - Used by IntegrationController line 87
     */
    @Transactional(readOnly = true)
    public IntegrationDto.ConnectorResponse getConnectorByCode(String code) {
        IntegrationConnector connector = connectorRepository.findByConnectorCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("Connector not found: " + code));
        
        boolean isConnected = connectionRepository.hasActiveConnections(connector.getId());
        return toConnectorResponse(connector, isConnected);
    }

    // ========================================================================
    // CONNECTION OPERATIONS
    // ========================================================================

    @Transactional(readOnly = true)
    public List<IntegrationDto.ConnectionResponse> getAllConnections() {
        return connectionRepository.findAllWithConnector().stream()
                .map(this::toConnectionResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get all connections with filters - Used by IntegrationController line 108
     */
    @Transactional(readOnly = true)
    public List<IntegrationDto.ConnectionResponse> getAllConnections(
            IntegrationConnector.ConnectorCategory category, 
            String status) {
        
        List<IntegrationConnection> connections;
        
        if (category != null) {
            connections = connectionRepository.findByConnectorCategory(category);
        } else {
            connections = connectionRepository.findAllWithConnector();
        }
        
        // Filter by status if provided
        if (status != null && !status.isEmpty()) {
            try {
                IntegrationConnection.ConnectionStatus statusEnum = 
                    IntegrationConnection.ConnectionStatus.valueOf(status.toUpperCase());
                connections = connections.stream()
                        .filter(c -> c.getStatus() == statusEnum)
                        .collect(Collectors.toList());
            } catch (IllegalArgumentException e) {
                log.warn("Invalid status filter: {}", status);
            }
        }
        
        return connections.stream()
                .map(this::toConnectionResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public IntegrationDto.ConnectionResponse getConnectionById(UUID id) {
        IntegrationConnection connection = connectionRepository.findByIdWithConnector(id)
                .orElseThrow(() -> new ResourceNotFoundException("Connection not found: " + id));
        return toConnectionResponse(connection);
    }

    @Transactional
    public IntegrationDto.ConnectionResponse createConnection(IntegrationDto.CreateConnectionRequest request) {
        IntegrationConnector connector = connectorRepository.findById(request.getConnectorId())
                .orElseThrow(() -> new ResourceNotFoundException("Connector not found: " + request.getConnectorId()));

        IntegrationConnection connection = new IntegrationConnection();
        connection.setConnector(connector);
        connection.setConnectionName(request.getConnectionName());
        connection.setEnvironment(request.getEnvironment());
        connection.setSyncFrequency(request.getSyncFrequency());
        connection.setCredentials(encryptCredentials(request.getCredentials()));
        connection.setStatus(IntegrationConnection.ConnectionStatus.CONNECTED);
        connection.setCreatedBy("system"); // TODO: Get from security context

        // Create default data flows based on connector features
        createDefaultDataFlows(connection, connector);

        connection = connectionRepository.save(connection);
        log.info("Created integration connection: {} for connector: {}", 
                connection.getConnectionName(), connector.getConnectorName());
        return toConnectionResponse(connection);
    }

    @Transactional
    public IntegrationDto.ConnectionResponse updateConnection(UUID id, IntegrationDto.UpdateConnectionRequest request) {
        IntegrationConnection connection = connectionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Connection not found: " + id));

        if (request.getConnectionName() != null) {
            connection.setConnectionName(request.getConnectionName());
        }
        if (request.getSyncFrequency() != null) {
            connection.setSyncFrequency(request.getSyncFrequency());
        }
        if (request.getCredentials() != null && !request.getCredentials().isEmpty()) {
            connection.setCredentials(encryptCredentials(request.getCredentials()));
        }

        connection.setUpdatedAt(LocalDateTime.now());
        connection = connectionRepository.save(connection);
        log.info("Updated integration connection: {}", connection.getConnectionName());
        return toConnectionResponse(connection);
    }

    @Transactional
    public void deleteConnection(UUID id) {
        IntegrationConnection connection = connectionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Connection not found: " + id));
        connectionRepository.delete(connection);
        log.info("Deleted integration connection: {}", connection.getConnectionName());
    }

    /**
     * Reconnect a failed connection - Used by IntegrationController line 158
     */
    @Transactional
    public IntegrationDto.ConnectionResponse reconnectConnection(UUID id) {
        IntegrationConnection connection = connectionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Connection not found: " + id));
        
        // Reset error state and attempt reconnection
        connection.setStatus(IntegrationConnection.ConnectionStatus.CONNECTED);
        connection.setErrorMessage(null);
        connection.setUpdatedAt(LocalDateTime.now());
        
        connection = connectionRepository.save(connection);
        log.info("Reconnected integration connection: {}", connection.getConnectionName());
        return toConnectionResponse(connection);
    }

    @Transactional
    public IntegrationDto.TestConnectionResponse testConnection(IntegrationDto.TestConnectionRequest request) {
        // TODO: Implement actual connection testing based on connector type
        IntegrationDto.TestConnectionResponse response = IntegrationDto.TestConnectionResponse.builder().build();
        
        // Simulate connection test
        boolean success = Math.random() > 0.2; // 80% success rate for demo
        response.setSuccess(success);
        response.setMessage(success ? "Connection successful" : "Connection failed: Invalid credentials");
        
        Map<String, Object> details = new HashMap<>();
        details.put("testedAt", LocalDateTime.now().toString());
        details.put("environment", request.getEnvironment().name());
        response.setDetails(details);
        
        return response;
    }

    // ========================================================================
    // OPEN BANKING OPERATIONS
    // ========================================================================

    /**
     * Get available ASPSPs (banks) for Open Banking - Used by IntegrationController line 173
     */
    @Transactional(readOnly = true)
    public List<IntegrationDto.AspspResponse> getAvailableAspsps(String country, String name, String bic) {
        List<IntegrationDto.AspspResponse> aspsps = new ArrayList<>();
        
        // Demo data - filter by country if provided
        if (country == null || "AE".equalsIgnoreCase(country) || "UAE".equalsIgnoreCase(country)) {
            aspsps.add(IntegrationDto.AspspResponse.builder()
                    .aspspId("ADCB-UAE")
                    .name("Abu Dhabi Commercial Bank")
                    .country("AE")
                    .bic("ADCBAEAA")
                    .supportsAis(true)
                    .supportsPis(true)
                    .supportsCof(true)
                    .build());
            
            aspsps.add(IntegrationDto.AspspResponse.builder()
                    .aspspId("ENBD-UAE")
                    .name("Emirates NBD")
                    .country("AE")
                    .bic("EABORUAE")
                    .supportsAis(true)
                    .supportsPis(true)
                    .supportsCof(false)
                    .build());
            
            aspsps.add(IntegrationDto.AspspResponse.builder()
                    .aspspId("FAB-UAE")
                    .name("First Abu Dhabi Bank")
                    .country("AE")
                    .bic("NBADORUAE")
                    .supportsAis(true)
                    .supportsPis(true)
                    .supportsCof(true)
                    .build());
        }
        
        // Filter by name if provided
        if (name != null && !name.isEmpty()) {
            String searchName = name.toLowerCase();
            aspsps = aspsps.stream()
                    .filter(a -> a.getName().toLowerCase().contains(searchName))
                    .collect(Collectors.toList());
        }
        
        // Filter by BIC if provided
        if (bic != null && !bic.isEmpty()) {
            aspsps = aspsps.stream()
                    .filter(a -> a.getBic().equalsIgnoreCase(bic))
                    .collect(Collectors.toList());
        }
        
        return aspsps;
    }

    /**
     * Initiate Open Banking authorization - Used by IntegrationController line 183
     * Returns InitiateOpenBankingAuthResponse (not ConnectionResponse)
     */
    @Transactional
    public IntegrationDto.InitiateOpenBankingAuthResponse initiateOpenBankingAuthorization(
            IntegrationDto.InitiateOpenBankingAuthRequest request) {
        log.info("Initiating Open Banking authorization for ASPSP: {}", request.getAspspId());
        
        // Generate state for CSRF protection
        String state = request.getState() != null ? request.getState() : UUID.randomUUID().toString();
        
        String consentId = "CONSENT-" + UUID.randomUUID().toString().substring(0, 8);
        String authUrl = String.format(
                "https://openbanking.example.com/authorize?client_id=vam&consent_id=%s&state=%s&redirect_uri=%s",
                consentId, state, request.getRedirectUri());
        
        return IntegrationDto.InitiateOpenBankingAuthResponse.builder()
                .authorizationUrl(authUrl)
                .consentId(consentId)
                .state(state)
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .build();
    }

    /**
     * Handle Open Banking callback - Used by IntegrationController lines 193, 212
     */
    @Transactional
    public IntegrationDto.ConnectionResponse handleOpenBankingCallback(
            IntegrationDto.OpenBankingCallbackRequest request) {
        log.info("Handling Open Banking callback with state: {}", request.getState());
        
        if (request.getError() != null) {
            log.error("Open Banking authorization failed: {} - {}", 
                    request.getError(), request.getErrorDescription());
            throw new BusinessException("Open Banking authorization failed: " + request.getErrorDescription());
        }
        
        // TODO: Implement actual callback handling
        throw new BusinessException("Open Banking callback handling not yet fully implemented. " +
                "Received code: " + request.getCode());
    }

    /**
     * Refresh Open Banking consent - Used by IntegrationController line 222
     */
    @Transactional
    public IntegrationDto.ConnectionResponse refreshOpenBankingConsent(UUID connectionId) {
        IntegrationConnection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new ResourceNotFoundException("Connection not found: " + connectionId));
        
        log.info("Refreshing Open Banking consent for connection: {}", connection.getConnectionName());
        
        connection.setUpdatedAt(LocalDateTime.now());
        connection = connectionRepository.save(connection);
        
        return toConnectionResponse(connection);
    }

    /**
     * Revoke Open Banking consent - Used by IntegrationController line 230
     */
    @Transactional
    public void revokeOpenBankingConsent(UUID connectionId) {
        IntegrationConnection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new ResourceNotFoundException("Connection not found: " + connectionId));
        
        log.info("Revoking Open Banking consent for connection: {}", connection.getConnectionName());
        
        connection.setStatus(IntegrationConnection.ConnectionStatus.DISCONNECTED);
        connection.setUpdatedAt(LocalDateTime.now());
        connectionRepository.save(connection);
    }

    // ========================================================================
    // DATA FLOW OPERATIONS
    // ========================================================================

    @Transactional(readOnly = true)
    public List<IntegrationDto.DataFlowResponse> getDataFlowsByConnection(UUID connectionId) {
        return dataFlowRepository.findByConnectionId(connectionId).stream()
                .map(this::toDataFlowResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public IntegrationDto.DataFlowResponse getDataFlowWithMappings(UUID flowId) {
        IntegrationDataFlow flow = dataFlowRepository.findByIdWithMappings(flowId)
                .orElseThrow(() -> new ResourceNotFoundException("Data flow not found: " + flowId));
        return toDataFlowResponse(flow);
    }

    @Transactional
    public IntegrationDto.DataFlowResponse createDataFlow(IntegrationDto.CreateDataFlowRequest request) {
        IntegrationConnection connection = connectionRepository.findById(request.getConnectionId())
                .orElseThrow(() -> new ResourceNotFoundException("Connection not found: " + request.getConnectionId()));

        IntegrationDataFlow flow = new IntegrationDataFlow();
        flow.setConnection(connection);
        flow.setFlowName(request.getFlowName());
        flow.setDirection(request.getDirection());
        flow.setSourceEntity(request.getSourceEntity());
        flow.setTargetEntity(request.getTargetEntity());
        flow.setScheduleEnabled(request.getScheduleEnabled() != null ? request.getScheduleEnabled() : true);
        flow.setStatus(IntegrationDataFlow.FlowStatus.ACTIVE);

        flow = dataFlowRepository.save(flow);
        log.info("Created data flow: {} for connection: {}", flow.getFlowName(), connection.getConnectionName());
        return toDataFlowResponse(flow);
    }

    /**
     * Update an existing data flow - Used by IntegrationController line 267
     */
    @Transactional
    public IntegrationDto.DataFlowResponse updateDataFlow(UUID flowId, IntegrationDto.CreateDataFlowRequest request) {
        IntegrationDataFlow flow = dataFlowRepository.findById(flowId)
                .orElseThrow(() -> new ResourceNotFoundException("Data flow not found: " + flowId));
        
        if (request.getFlowName() != null) {
            flow.setFlowName(request.getFlowName());
        }
        if (request.getDirection() != null) {
            flow.setDirection(request.getDirection());
        }
        if (request.getSourceEntity() != null) {
            flow.setSourceEntity(request.getSourceEntity());
        }
        if (request.getTargetEntity() != null) {
            flow.setTargetEntity(request.getTargetEntity());
        }
        if (request.getScheduleEnabled() != null) {
            flow.setScheduleEnabled(request.getScheduleEnabled());
        }
        
        flow = dataFlowRepository.save(flow);
        log.info("Updated data flow: {}", flow.getFlowName());
        return toDataFlowResponse(flow);
    }

    /**
     * Delete a data flow - Used by IntegrationController line 274
     */
    @Transactional
    public void deleteDataFlow(UUID flowId) {
        IntegrationDataFlow flow = dataFlowRepository.findById(flowId)
                .orElseThrow(() -> new ResourceNotFoundException("Data flow not found: " + flowId));
        
        dataFlowRepository.delete(flow);
        log.info("Deleted data flow: {}", flow.getFlowName());
    }

    @Transactional
    public IntegrationDto.DataFlowResponse updateFieldMappings(UUID flowId, IntegrationDto.UpdateFieldMappingsRequest request) {
        IntegrationDataFlow dataFlow = dataFlowRepository.findByIdWithMappings(flowId)
                .orElseThrow(() -> new ResourceNotFoundException("Data flow not found: " + flowId));
        
        // Clear existing mappings and add new ones
        dataFlow.getFieldMappings().clear();
        
        if (request.getMappings() != null) {
            int sortOrder = 0;
            for (IntegrationDto.FieldMappingRequest mappingReq : request.getMappings()) {
                IntegrationFieldMapping mapping = new IntegrationFieldMapping();
                // Entity has 'flow' field, not 'dataFlow'
                mapping.setFlow(dataFlow);
                mapping.setSourceField(mappingReq.getSourceField());
                mapping.setTargetField(mappingReq.getTargetField());
                mapping.setTransformation(mappingReq.getTransformation());
                mapping.setIsRequired(mappingReq.getIsRequired());
                mapping.setDefaultValue(mappingReq.getDefaultValue());
                mapping.setSortOrder(mappingReq.getSortOrder() != null ? mappingReq.getSortOrder() : sortOrder++);
                
                if (mappingReq.getTransformationParams() != null) {
                    try {
                        mapping.setTransformationParams(objectMapper.writeValueAsString(mappingReq.getTransformationParams()));
                    } catch (JsonProcessingException e) {
                        log.warn("Failed to serialize transformation params", e);
                    }
                }
                
                dataFlow.getFieldMappings().add(mapping);
            }
        }
        
        dataFlow = dataFlowRepository.save(dataFlow);
        log.info("Updated field mappings for flow: {}", dataFlow.getFlowName());
        return toDataFlowResponse(dataFlow);
    }

    @Transactional
    public IntegrationDto.DataFlowResponse toggleFlowStatus(UUID flowId) {
        IntegrationDataFlow flow = dataFlowRepository.findById(flowId)
                .orElseThrow(() -> new ResourceNotFoundException("Data flow not found: " + flowId));
        
        if (flow.getStatus() == IntegrationDataFlow.FlowStatus.ACTIVE) {
            flow.setStatus(IntegrationDataFlow.FlowStatus.PAUSED);
        } else {
            flow.setStatus(IntegrationDataFlow.FlowStatus.ACTIVE);
        }
        
        flow = dataFlowRepository.save(flow);
        log.info("Toggled flow status: {} -> {}", flow.getFlowName(), flow.getStatus());
        return toDataFlowResponse(flow);
    }

    // ========================================================================
    // SYNC OPERATIONS
    // ========================================================================

    @Transactional
    public IntegrationDto.TriggerSyncResponse triggerSync(IntegrationDto.TriggerSyncRequest request) {
        IntegrationConnection connection = connectionRepository.findById(request.getConnectionId())
                .orElseThrow(() -> new ResourceNotFoundException("Connection not found: " + request.getConnectionId()));
        
        // Update connection status
        connection.setStatus(IntegrationConnection.ConnectionStatus.SYNCING);
        connection.setLastSyncAt(LocalDateTime.now());
        connectionRepository.save(connection);
        
        // Create sync log entry - use RUNNING (entity enum: RUNNING, SUCCESS, PARTIAL, FAILED)
        IntegrationSyncLog syncLog = new IntegrationSyncLog();
        syncLog.setConnection(connection);
        syncLog.setStartTime(LocalDateTime.now());
        syncLog.setStatus(IntegrationSyncLog.SyncStatus.RUNNING);
        syncLog = syncLogRepository.save(syncLog);
        
        log.info("Triggered sync for connection: {}", connection.getConnectionName());
        
        return IntegrationDto.TriggerSyncResponse.builder()
                .syncId(syncLog.getId())
                .status("STARTED")
                .message("Sync started for connection: " + connection.getConnectionName())
                .estimatedCompletionTime(LocalDateTime.now().plusMinutes(5))
                .build();
    }

    @Transactional(readOnly = true)
    public Page<IntegrationDto.SyncLogResponse> getSyncLogs(Pageable pageable) {
        return syncLogRepository.findAllOrderByStartTimeDesc(pageable)
                .map(this::toSyncLogResponse);
    }

    /**
     * Get sync logs with filter - Used by IntegrationController line 334
     */
    @Transactional(readOnly = true)
    public Page<IntegrationDto.SyncLogResponse> getSyncLogs(IntegrationDto.SyncLogFilter filter, Pageable pageable) {
        if (filter != null) {
            if (filter.getConnectionId() != null) {
                return syncLogRepository.findByConnectionId(filter.getConnectionId(), pageable)
                        .map(this::toSyncLogResponse);
            }
            if (filter.getFlowId() != null) {
                return syncLogRepository.findByFlowId(filter.getFlowId(), pageable)
                        .map(this::toSyncLogResponse);
            }
            if (filter.getStatus() != null) {
                return syncLogRepository.findByStatus(filter.getStatus(), pageable)
                        .map(this::toSyncLogResponse);
            }
        }
        
        return syncLogRepository.findAllOrderByStartTimeDesc(pageable)
                .map(this::toSyncLogResponse);
    }

    /**
     * Get sync log by ID - Used by IntegrationController line 351
     */
    @Transactional(readOnly = true)
    public IntegrationDto.SyncLogResponse getSyncLogById(UUID id) {
        IntegrationSyncLog syncLog = syncLogRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sync log not found: " + id));
        return toSyncLogResponse(syncLog);
    }

    @Transactional(readOnly = true)
    public Page<IntegrationDto.SyncLogResponse> getSyncLogsByConnection(UUID connectionId, Pageable pageable) {
        return syncLogRepository.findByConnectionId(connectionId, pageable)
                .map(this::toSyncLogResponse);
    }

    // ========================================================================
    // STATS
    // ========================================================================

    @Transactional(readOnly = true)
    public IntegrationDto.IntegrationStatsResponse getStats() {
        IntegrationDto.IntegrationStatsResponse stats = IntegrationDto.IntegrationStatsResponse.builder()
                .totalConnections(connectionRepository.count())
                .activeConnections(connectionRepository.countConnected())
                .errorConnections(connectionRepository.countErrors())
                .syncingConnections(connectionRepository.countSyncing())
                .totalDataFlows(dataFlowRepository.countAllFlows())
                .availableConnectors(connectorRepository.count())
                .build();
        
        // Category stats - use ONLY categories that exist in entity enum: ERP, TREASURY, BANKING, GENERIC
        IntegrationDto.CategoryStats categoryStats = IntegrationDto.CategoryStats.builder()
                .erpConnections(connectionRepository.countByCategory(IntegrationConnector.ConnectorCategory.ERP))
                .treasuryConnections(connectionRepository.countByCategory(IntegrationConnector.ConnectorCategory.TREASURY))
                .bankingConnections(connectionRepository.countByCategory(IntegrationConnector.ConnectorCategory.BANKING))
                .genericConnections(connectionRepository.countByCategory(IntegrationConnector.ConnectorCategory.GENERIC))
                // OPEN_BANKING and PAYMENTS don't exist in your enum - set to 0
                .openBankingConnections(0L)
                .paymentsConnections(0L)
                .build();
        stats.setCategoryStats(categoryStats);
        
        return stats;
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private void createDefaultDataFlows(IntegrationConnection connection, IntegrationConnector connector) {
        List<String> features = parseFeatures(connector.getSupportedFeatures());
        int i = 0;
        for (String feature : features) {
            if (i >= 3) break; // Create max 3 default flows
            
            IntegrationDataFlow flow = new IntegrationDataFlow();
            flow.setConnection(connection);
            flow.setFlowName(feature + " Sync");
            flow.setDirection(i % 2 == 0 ? IntegrationDataFlow.FlowDirection.INBOUND : IntegrationDataFlow.FlowDirection.OUTBOUND);
            flow.setSourceEntity(connector.getConnectorCode() + " " + feature);
            flow.setTargetEntity("VAM " + feature);
            flow.setScheduleEnabled(true);
            flow.setStatus(IntegrationDataFlow.FlowStatus.ACTIVE);
            connection.getDataFlows().add(flow);
            i++;
        }
    }

    private List<String> parseFeatures(String featuresJson) {
        if (featuresJson == null || featuresJson.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(featuresJson, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse features JSON", e);
            return Collections.emptyList();
        }
    }

    /**
     * Encrypt credentials - accepts Map<String, Object>
     */
    private String encryptCredentials(Map<String, Object> credentials) {
        if (credentials == null || credentials.isEmpty()) {
            return null;
        }
        // TODO: Implement actual encryption (AES-256, etc.)
        try {
            return objectMapper.writeValueAsString(credentials);
        } catch (JsonProcessingException e) {
            throw new BusinessException("Failed to encrypt credentials");
        }
    }

    /**
     * Convert connector entity to response DTO
     * Entity fields: id, connectorCode, connectorName, category, authType, baseUrlTemplate, 
     *                supportedFeatures, status, documentationUrl, logoUrl, createdAt, updatedAt
     * Entity does NOT have: shortName, description, iconType, region, sortOrder
     */
    private IntegrationDto.ConnectorResponse toConnectorResponse(IntegrationConnector connector, boolean isConnected) {
        long connectionCount = connectionRepository.countByConnectorId(connector.getId());
        
        return IntegrationDto.ConnectorResponse.builder()
                .id(connector.getId())
                .connectorCode(connector.getConnectorCode())
                .connectorName(connector.getConnectorName())
                // Fields not in entity - set to null/defaults
                .shortName(null)
                .description(null)
                .category(connector.getCategory())
                .authType(connector.getAuthType())
                .status(connector.getStatus())
                .supportedFeatures(parseFeatures(connector.getSupportedFeatures()))
                .documentationUrl(connector.getDocumentationUrl())
                .logoUrl(connector.getLogoUrl())
                // Fields not in entity - set to null/defaults
                .iconType(null)
                .region(null)
                .sortOrder(null)
                .isBeta(connector.getStatus() == IntegrationConnector.ConnectorStatus.COMING_SOON)
                .isConnected(isConnected)
                .activeConnectionCount((int) connectionCount)
                .build();
    }

    private IntegrationDto.ConnectionResponse toConnectionResponse(IntegrationConnection connection) {
        return IntegrationDto.ConnectionResponse.builder()
                .id(connection.getId())
                .connectorId(connection.getConnector().getId())
                .connectorCode(connection.getConnector().getConnectorCode())
                .connectorName(connection.getConnector().getConnectorName())
                .connectorLogo(connection.getConnector().getLogoUrl())
                // iconType not in connector entity
                .iconType(null)
                .connectionName(connection.getConnectionName())
                .environment(connection.getEnvironment())
                .syncFrequency(connection.getSyncFrequency())
                .lastSyncAt(connection.getLastSyncAt())
                .nextSyncAt(connection.getNextSyncAt())
                .status(connection.getStatus())
                .errorMessage(connection.getErrorMessage())
                .dataFlowCount(connection.getDataFlows() != null ? connection.getDataFlows().size() : 0)
                .createdAt(connection.getCreatedAt())
                .createdBy(connection.getCreatedBy())
                .dataFlows(connection.getDataFlows() != null 
                        ? connection.getDataFlows().stream().map(this::toDataFlowResponse).collect(Collectors.toList())
                        : null)
                .build();
    }

    private IntegrationDto.DataFlowResponse toDataFlowResponse(IntegrationDataFlow flow) {
        return IntegrationDto.DataFlowResponse.builder()
                .id(flow.getId())
                .flowName(flow.getFlowName())
                .direction(flow.getDirection())
                .sourceEntity(flow.getSourceEntity())
                .targetEntity(flow.getTargetEntity())
                .scheduleEnabled(flow.getScheduleEnabled())
                .lastRunAt(flow.getLastRunAt())
                .recordsProcessed(flow.getRecordsProcessed())
                .status(flow.getStatus())
                .fieldMappings(flow.getFieldMappings() != null 
                        ? flow.getFieldMappings().stream().map(this::toFieldMappingResponse).collect(Collectors.toList())
                        : null)
                .build();
    }

    private IntegrationDto.FieldMappingResponse toFieldMappingResponse(IntegrationFieldMapping mapping) {
        Map<String, Object> transformParams = null;
        if (mapping.getTransformationParams() != null) {
            try {
                transformParams = objectMapper.readValue(
                        mapping.getTransformationParams(), 
                        new TypeReference<Map<String, Object>>() {});
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse transformation params", e);
            }
        }
        
        return IntegrationDto.FieldMappingResponse.builder()
                .id(mapping.getId())
                .sourceField(mapping.getSourceField())
                .targetField(mapping.getTargetField())
                .transformation(mapping.getTransformation())
                .transformationParams(transformParams)
                .isRequired(mapping.getIsRequired())
                .defaultValue(mapping.getDefaultValue())
                .sortOrder(mapping.getSortOrder())
                .build();
    }

    private IntegrationDto.SyncLogResponse toSyncLogResponse(IntegrationSyncLog syncLog) {
        Long durationMs = null;
        if (syncLog.getStartTime() != null && syncLog.getEndTime() != null) {
            durationMs = java.time.Duration.between(syncLog.getStartTime(), syncLog.getEndTime()).toMillis();
        }
        
        return IntegrationDto.SyncLogResponse.builder()
                .id(syncLog.getId())
                .connectionId(syncLog.getConnection().getId())
                .connectionName(syncLog.getConnection().getConnectionName())
                .flowId(syncLog.getFlow() != null ? syncLog.getFlow().getId() : null)
                .flowName(syncLog.getFlow() != null ? syncLog.getFlow().getFlowName() : null)
                .direction(syncLog.getFlow() != null ? syncLog.getFlow().getDirection() : null)
                .startTime(syncLog.getStartTime())
                .endTime(syncLog.getEndTime())
                .durationMs(durationMs)
                .recordsProcessed(syncLog.getRecordsProcessed())
                .recordsFailed(syncLog.getRecordsFailed())
                .status(syncLog.getStatus())
                .errorMessage(syncLog.getErrorMessage())
                .build();
    }
}