package com.bank.vam.controller.integration;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.dto.integration.IntegrationDto;
import com.bank.vam.entity.integration.IntegrationConnector;
import com.bank.vam.service.integration.IntegrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;

/**
 * Integration Controller
 * 
 * Manages system integrations including:
 * - ERP systems (SAP, Oracle, Dynamics)
 * - Treasury systems (Kyriba, FIS)
 * - Open Banking (PSD2, UK OB, aggregators)
 * - Payment networks (SWIFT, SEPA)
 * - Generic connectors (SFTP, REST, Webhooks)
 */
@RestController
@RequestMapping("/api/v1/integrations")
@RequiredArgsConstructor
@Tag(name = "Integrations", description = "ERP, Open Banking, and system integration management")
public class IntegrationController {

    private final IntegrationService integrationService;

    // ========================================================================
    // STATS
    // ========================================================================

    @GetMapping("/stats")
    @Operation(summary = "Get integration statistics", 
               description = "Returns counts of connections, flows, and connectors by category")
    public ResponseEntity<ApiResponse<IntegrationDto.IntegrationStatsResponse>> getStats() {
        IntegrationDto.IntegrationStatsResponse stats = integrationService.getStats();
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    // ========================================================================
    // CONNECTORS
    // ========================================================================

    @GetMapping("/connectors")
    @Operation(summary = "Get all available connectors",
               description = "Returns all connectors including ERP, Treasury, Open Banking, Payments, and Generic")
    public ResponseEntity<ApiResponse<List<IntegrationDto.ConnectorResponse>>> getAllConnectors(
            @RequestParam(required = false) IntegrationConnector.ConnectorCategory category,
            @RequestParam(required = false) IntegrationConnector.ConnectorStatus status,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String search) {
        
        IntegrationDto.ConnectorFilter filter = IntegrationDto.ConnectorFilter.builder()
                .category(category)
                .status(status)
                .region(region)
                .searchTerm(search)
                .build();
        
        List<IntegrationDto.ConnectorResponse> connectors = integrationService.getConnectors(filter);
        return ResponseEntity.ok(ApiResponse.success(connectors));
    }

    @GetMapping("/connectors/{id}")
    @Operation(summary = "Get connector by ID")
    public ResponseEntity<ApiResponse<IntegrationDto.ConnectorResponse>> getConnectorById(
            @PathVariable UUID id) {
        IntegrationDto.ConnectorResponse connector = integrationService.getConnectorById(id);
        return ResponseEntity.ok(ApiResponse.success(connector));
    }

    @GetMapping("/connectors/code/{code}")
    @Operation(summary = "Get connector by code")
    public ResponseEntity<ApiResponse<IntegrationDto.ConnectorResponse>> getConnectorByCode(
            @PathVariable String code) {
        IntegrationDto.ConnectorResponse connector = integrationService.getConnectorByCode(code);
        return ResponseEntity.ok(ApiResponse.success(connector));
    }

    @GetMapping("/connectors/category/{category}")
    @Operation(summary = "Get connectors by category")
    public ResponseEntity<ApiResponse<List<IntegrationDto.ConnectorResponse>>> getConnectorsByCategory(
            @PathVariable IntegrationConnector.ConnectorCategory category) {
        List<IntegrationDto.ConnectorResponse> connectors = integrationService.getConnectorsByCategory(category);
        return ResponseEntity.ok(ApiResponse.success(connectors));
    }

    // ========================================================================
    // CONNECTIONS
    // ========================================================================

    @GetMapping("/connections")
    @Operation(summary = "Get all configured connections")
    public ResponseEntity<ApiResponse<List<IntegrationDto.ConnectionResponse>>> getAllConnections(
            @RequestParam(required = false) IntegrationConnector.ConnectorCategory category,
            @RequestParam(required = false) String status) {
        List<IntegrationDto.ConnectionResponse> connections = integrationService.getAllConnections(category, status);
        return ResponseEntity.ok(ApiResponse.success(connections));
    }

    @GetMapping("/connections/{id}")
    @Operation(summary = "Get connection by ID with data flows")
    public ResponseEntity<ApiResponse<IntegrationDto.ConnectionResponse>> getConnectionById(
            @PathVariable UUID id) {
        IntegrationDto.ConnectionResponse connection = integrationService.getConnectionById(id);
        return ResponseEntity.ok(ApiResponse.success(connection));
    }

    @PostMapping("/connections")
    @Operation(summary = "Create new connection",
               description = "Creates a standard connection. For Open Banking, use /open-banking/authorize")
    public ResponseEntity<ApiResponse<IntegrationDto.ConnectionResponse>> createConnection(
            @Valid @RequestBody IntegrationDto.CreateConnectionRequest request) {
        IntegrationDto.ConnectionResponse connection = integrationService.createConnection(request);
        return ResponseEntity.ok(ApiResponse.success(connection, "Connection created successfully"));
    }

    @PutMapping("/connections/{id}")
    @Operation(summary = "Update connection settings")
    public ResponseEntity<ApiResponse<IntegrationDto.ConnectionResponse>> updateConnection(
            @PathVariable UUID id,
            @Valid @RequestBody IntegrationDto.UpdateConnectionRequest request) {
        IntegrationDto.ConnectionResponse connection = integrationService.updateConnection(id, request);
        return ResponseEntity.ok(ApiResponse.success(connection, "Connection updated successfully"));
    }

    @DeleteMapping("/connections/{id}")
    @Operation(summary = "Delete connection",
               description = "Removes connection and all associated data flows")
    public ResponseEntity<ApiResponse<Void>> deleteConnection(@PathVariable UUID id) {
        integrationService.deleteConnection(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Connection deleted successfully"));
    }

    @PostMapping("/connections/test")
    @Operation(summary = "Test connection credentials before saving")
    public ResponseEntity<ApiResponse<IntegrationDto.TestConnectionResponse>> testConnection(
            @Valid @RequestBody IntegrationDto.TestConnectionRequest request) {
        IntegrationDto.TestConnectionResponse result = integrationService.testConnection(request);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/connections/{id}/reconnect")
    @Operation(summary = "Attempt to reconnect a failed connection")
    public ResponseEntity<ApiResponse<IntegrationDto.ConnectionResponse>> reconnectConnection(
            @PathVariable UUID id) {
        IntegrationDto.ConnectionResponse connection = integrationService.reconnectConnection(id);
        return ResponseEntity.ok(ApiResponse.success(connection, "Reconnection attempt completed"));
    }

    // ========================================================================
    // OPEN BANKING - Authorization Flow
    // ========================================================================

    @GetMapping("/open-banking/aspsps")
    @Operation(summary = "Get available banks (ASPSPs) for Open Banking",
               description = "Returns list of supported banks for account aggregation and payment initiation")
    public ResponseEntity<ApiResponse<List<IntegrationDto.AspspResponse>>> getAvailableAspsps(
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String connectorCode,
            @RequestParam(required = false) String search) {
        List<IntegrationDto.AspspResponse> aspsps = integrationService.getAvailableAspsps(country, connectorCode, search);
        return ResponseEntity.ok(ApiResponse.success(aspsps));
    }

    @PostMapping("/open-banking/authorize")
    @Operation(summary = "Initiate Open Banking authorization",
               description = "Starts OAuth/consent flow and returns authorization URL for user redirect")
    public ResponseEntity<ApiResponse<IntegrationDto.InitiateOpenBankingAuthResponse>> initiateOpenBankingAuth(
            @Valid @RequestBody IntegrationDto.InitiateOpenBankingAuthRequest request) {
        IntegrationDto.InitiateOpenBankingAuthResponse response = 
                integrationService.initiateOpenBankingAuthorization(request);
        return ResponseEntity.ok(ApiResponse.success(response, "Authorization initiated"));
    }

    @PostMapping("/open-banking/callback")
    @Operation(summary = "Handle Open Banking authorization callback",
               description = "Processes authorization code and creates connection")
    public ResponseEntity<ApiResponse<IntegrationDto.ConnectionResponse>> handleOpenBankingCallback(
            @Valid @RequestBody IntegrationDto.OpenBankingCallbackRequest request) {
        IntegrationDto.ConnectionResponse connection = 
                integrationService.handleOpenBankingCallback(request);
        return ResponseEntity.ok(ApiResponse.success(connection, "Open Banking connection established"));
    }

    @GetMapping("/open-banking/callback")
    @Operation(summary = "Handle Open Banking redirect callback (GET)",
               description = "Alternative callback endpoint for redirects")
    public ResponseEntity<ApiResponse<IntegrationDto.ConnectionResponse>> handleOpenBankingCallbackGet(
            @RequestParam String code,
            @RequestParam String state,
            @RequestParam(required = false) String error,
            @RequestParam(required = false, name = "error_description") String errorDescription) {
        IntegrationDto.OpenBankingCallbackRequest request = IntegrationDto.OpenBankingCallbackRequest.builder()
                .code(code)
                .state(state)
                .error(error)
                .errorDescription(errorDescription)
                .build();
        IntegrationDto.ConnectionResponse connection = 
                integrationService.handleOpenBankingCallback(request);
        return ResponseEntity.ok(ApiResponse.success(connection, "Open Banking connection established"));
    }

    @PostMapping("/connections/{id}/refresh-consent")
    @Operation(summary = "Refresh Open Banking consent",
               description = "Re-initiates consent flow for expired or expiring consents")
    public ResponseEntity<ApiResponse<IntegrationDto.ConnectionResponse>> refreshConsent(
            @PathVariable UUID id) {
        // FIX: refreshOpenBankingConsent returns ConnectionResponse, not InitiateOpenBankingAuthResponse
        IntegrationDto.ConnectionResponse response = 
                integrationService.refreshOpenBankingConsent(id);
        return ResponseEntity.ok(ApiResponse.success(response, "Consent refresh initiated"));
    }

    @DeleteMapping("/connections/{id}/revoke-consent")
    @Operation(summary = "Revoke Open Banking consent",
               description = "Revokes consent at the ASPSP and disconnects")
    public ResponseEntity<ApiResponse<Void>> revokeConsent(@PathVariable UUID id) {
        integrationService.revokeOpenBankingConsent(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Consent revoked successfully"));
    }

    // ========================================================================
    // DATA FLOWS
    // ========================================================================

    @GetMapping("/connections/{connectionId}/flows")
    @Operation(summary = "Get data flows for a connection")
    public ResponseEntity<ApiResponse<List<IntegrationDto.DataFlowResponse>>> getDataFlows(
            @PathVariable UUID connectionId) {
        List<IntegrationDto.DataFlowResponse> flows = integrationService.getDataFlowsByConnection(connectionId);
        return ResponseEntity.ok(ApiResponse.success(flows));
    }

    @GetMapping("/flows/{flowId}")
    @Operation(summary = "Get data flow with field mappings")
    public ResponseEntity<ApiResponse<IntegrationDto.DataFlowResponse>> getDataFlowWithMappings(
            @PathVariable UUID flowId) {
        IntegrationDto.DataFlowResponse flow = integrationService.getDataFlowWithMappings(flowId);
        return ResponseEntity.ok(ApiResponse.success(flow));
    }

    @PostMapping("/flows")
    @Operation(summary = "Create new data flow")
    public ResponseEntity<ApiResponse<IntegrationDto.DataFlowResponse>> createDataFlow(
            @Valid @RequestBody IntegrationDto.CreateDataFlowRequest request) {
        IntegrationDto.DataFlowResponse flow = integrationService.createDataFlow(request);
        return ResponseEntity.ok(ApiResponse.success(flow, "Data flow created successfully"));
    }

    @PutMapping("/flows/{flowId}")
    @Operation(summary = "Update data flow configuration")
    public ResponseEntity<ApiResponse<IntegrationDto.DataFlowResponse>> updateDataFlow(
            @PathVariable UUID flowId,
            @Valid @RequestBody IntegrationDto.CreateDataFlowRequest request) {
        IntegrationDto.DataFlowResponse flow = integrationService.updateDataFlow(flowId, request);
        return ResponseEntity.ok(ApiResponse.success(flow, "Data flow updated successfully"));
    }

    @DeleteMapping("/flows/{flowId}")
    @Operation(summary = "Delete data flow")
    public ResponseEntity<ApiResponse<Void>> deleteDataFlow(@PathVariable UUID flowId) {
        integrationService.deleteDataFlow(flowId);
        return ResponseEntity.ok(ApiResponse.success(null, "Data flow deleted successfully"));
    }

    @PutMapping("/flows/{flowId}/mappings")
    @Operation(summary = "Update field mappings for a flow")
    public ResponseEntity<ApiResponse<IntegrationDto.DataFlowResponse>> updateFieldMappings(
            @PathVariable UUID flowId,
            @Valid @RequestBody IntegrationDto.UpdateFieldMappingsRequest request) {
        IntegrationDto.DataFlowResponse flow = integrationService.updateFieldMappings(flowId, request);
        return ResponseEntity.ok(ApiResponse.success(flow, "Field mappings updated successfully"));
    }

    @PostMapping("/flows/{flowId}/toggle")
    @Operation(summary = "Toggle data flow status (active/paused)")
    public ResponseEntity<ApiResponse<IntegrationDto.DataFlowResponse>> toggleFlowStatus(
            @PathVariable UUID flowId) {
        IntegrationDto.DataFlowResponse flow = integrationService.toggleFlowStatus(flowId);
        return ResponseEntity.ok(ApiResponse.success(flow, "Flow status toggled"));
    }

    // ========================================================================
    // SYNC
    // ========================================================================

    @PostMapping("/sync")
    @Operation(summary = "Trigger sync for a connection",
               description = "Initiates synchronization for all or specified data flows")
    public ResponseEntity<ApiResponse<IntegrationDto.TriggerSyncResponse>> triggerSync(
            @Valid @RequestBody IntegrationDto.TriggerSyncRequest request) {
        IntegrationDto.TriggerSyncResponse result = integrationService.triggerSync(request);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/connections/{connectionId}/sync")
    @Operation(summary = "Trigger sync for all flows of a connection")
    public ResponseEntity<ApiResponse<IntegrationDto.TriggerSyncResponse>> triggerConnectionSync(
            @PathVariable UUID connectionId,
            @RequestParam(defaultValue = "false") Boolean forceRefresh) {
        IntegrationDto.TriggerSyncRequest request = IntegrationDto.TriggerSyncRequest.builder()
                .connectionId(connectionId)
                .forceRefresh(forceRefresh)
                .build();
        IntegrationDto.TriggerSyncResponse result = integrationService.triggerSync(request);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/logs")
    @Operation(summary = "Get all sync logs with filtering")
    public ResponseEntity<ApiResponse<Page<IntegrationDto.SyncLogResponse>>> getSyncLogs(
            @RequestParam(required = false) UUID connectionId,
            @RequestParam(required = false) UUID flowId,
            @RequestParam(required = false) String status,
            @PageableDefault(size = 20, sort = "startTime") Pageable pageable) {
        
        IntegrationDto.SyncLogFilter filter = IntegrationDto.SyncLogFilter.builder()
                .connectionId(connectionId)
                .flowId(flowId)
                .build();
        
        Page<IntegrationDto.SyncLogResponse> logs = integrationService.getSyncLogs(filter, pageable);
        return ResponseEntity.ok(ApiResponse.success(logs));
    }

    @GetMapping("/connections/{connectionId}/logs")
    @Operation(summary = "Get sync logs for a specific connection")
    public ResponseEntity<ApiResponse<Page<IntegrationDto.SyncLogResponse>>> getSyncLogsByConnection(
            @PathVariable UUID connectionId,
            @PageableDefault(size = 20, sort = "startTime") Pageable pageable) {
        Page<IntegrationDto.SyncLogResponse> logs = integrationService.getSyncLogsByConnection(connectionId, pageable);
        return ResponseEntity.ok(ApiResponse.success(logs));
    }

    @GetMapping("/logs/{logId}")
    @Operation(summary = "Get detailed sync log entry")
    public ResponseEntity<ApiResponse<IntegrationDto.SyncLogResponse>> getSyncLogById(
            @PathVariable UUID logId) {
        IntegrationDto.SyncLogResponse log = integrationService.getSyncLogById(logId);
        return ResponseEntity.ok(ApiResponse.success(log));
    }
}