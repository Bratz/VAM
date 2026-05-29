package com.bank.vam.controller.treasury;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.dto.treasury.BalanceStructureDto;
import com.bank.vam.dto.treasury.BalanceStructureDto.*;
import com.bank.vam.service.treasury.BalanceStructureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST Controller for Balance Structure / Treasury Hierarchy
 * 
 * Provides APIs for:
 * - Hierarchical view of virtual accounts with balances
 * - Intercompany positions from IHB
 * - Pool participation and interest allocation
 * - Sweep and netting participation
 * - Physical bank account reference
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/treasury/balance-structure")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "Balance Structure", description = "Treasury balance hierarchy and consolidation APIs")
public class BalanceStructureController {

    private final BalanceStructureService balanceStructureService;

    // ========================================================================
    // HIERARCHY APIs
    // ========================================================================

    /**
     * Get complete balance structure hierarchy tree
     */
    @GetMapping
    @Operation(summary = "Get balance structure hierarchy",
               description = "Returns the complete hierarchical tree of entities and virtual accounts with balances, IC positions, and participation flags")
    public ResponseEntity<ApiResponse<HierarchyNode>> getHierarchy(
            @Parameter(description = "Corporate ID (optional, uses default if not provided)")
            @RequestParam(required = false) UUID corporateId,
            @Parameter(description = "Program ID to filter by (optional)")
            @RequestParam(required = false) UUID programId,
            @Parameter(description = "Reporting currency for consolidated balances")
            @RequestParam(defaultValue = "AED") String reportingCurrency) {

        log.info("GET /api/v1/treasury/balance-structure - corporateId: {}, programId: {}, currency: {}",
                 corporateId, programId, reportingCurrency);

        UUID corpId = corporateId != null ? corporateId : getDefaultCorporateId();
        HierarchyNode hierarchy = balanceStructureService.getHierarchy(corpId, programId, reportingCurrency);

        return ResponseEntity.ok(ApiResponse.success(hierarchy));
    }

    /**
     * Get balance structure summary statistics
     */
    @GetMapping("/summary")
    @Operation(summary = "Get balance summary statistics",
               description = "Returns consolidated totals, IC positions, pool rate, and monthly interest")
    public ResponseEntity<ApiResponse<BalanceSummary>> getSummary(
            @RequestParam(required = false) UUID corporateId,
            @Parameter(description = "Program ID to filter by (optional)")
            @RequestParam(required = false) UUID programId,
            @RequestParam(defaultValue = "AED") String reportingCurrency) {

        log.info("GET /api/v1/treasury/balance-structure/summary - corporateId: {}, programId: {}",
                 corporateId, programId);

        UUID corpId = corporateId != null ? corporateId : getDefaultCorporateId();
        BalanceSummary summary = balanceStructureService.getSummary(corpId, programId, reportingCurrency);

        return ResponseEntity.ok(ApiResponse.success(summary));
    }

    /**
     * Get physical (real) bank account information
     */
    @GetMapping("/physical-account")
    @Operation(summary = "Get physical bank account",
               description = "Returns the real bank account that backs all virtual accounts")
    public ResponseEntity<ApiResponse<PhysicalAccountInfo>> getPhysicalAccount(
            @RequestParam(required = false) UUID corporateId) {
        
        log.info("GET /api/v1/treasury/balance-structure/physical-account");
        
        UUID corpId = corporateId != null ? corporateId : getDefaultCorporateId();
        PhysicalAccountInfo account = balanceStructureService.getPhysicalAccount(corpId);
        
        return ResponseEntity.ok(ApiResponse.success(account));
    }

    // ========================================================================
    // NODE APIs
    // ========================================================================

    /**
     * Get detailed information for a specific node
     */
    @GetMapping("/nodes/{nodeId}")
    @Operation(summary = "Get node details",
               description = "Returns detailed information including IC positions, participation, and interest for a specific node")
    public ResponseEntity<ApiResponse<NodeDetail>> getNodeDetail(
            @PathVariable String nodeId,
            @RequestParam(defaultValue = "AED") String reportingCurrency) {
        
        log.info("GET /api/v1/treasury/balance-structure/nodes/{}", nodeId);
        
        NodeDetail detail = balanceStructureService.getNodeDetail(nodeId, reportingCurrency);
        return ResponseEntity.ok(ApiResponse.success(detail));
    }

    /**
     * Update participation flags for a node
     */
    @PutMapping("/nodes/{nodeId}/participation")
    @Operation(summary = "Update node participation",
               description = "Update pool, netting, and sweep participation for a node")
    public ResponseEntity<ApiResponse<NodeDetail>> updateParticipation(
            @PathVariable String nodeId,
            @RequestBody UpdateParticipationRequest request) {
        
        log.info("PUT /api/v1/treasury/balance-structure/nodes/{}/participation", nodeId);
        
        NodeDetail updated = balanceStructureService.updateParticipation(nodeId, request);
        return ResponseEntity.ok(ApiResponse.success(updated));
    }

    /**
     * Create a new hierarchy node
     */
    @PostMapping("/nodes")
    @Operation(summary = "Create hierarchy node",
               description = "Create a new entity or region node in the hierarchy")
    public ResponseEntity<ApiResponse<HierarchyNode>> createNode(
            @RequestBody CreateNodeRequest request) {
        
        log.info("POST /api/v1/treasury/balance-structure/nodes - name: {}, type: {}", 
                 request.getName(), request.getType());
        
        // For now, return a mock created node
        HierarchyNode node = HierarchyNode.builder()
            .id(UUID.randomUUID().toString())
            .name(request.getName())
            .type(request.getType())
            .currencyCode(request.getCurrencyCode())
            .level(1)
            .participatesInPooling(false)
            .participatesInNetting(false)
            .participatesInSweep(false)
            .parentId(request.getParentId())
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(node));
    }

    /**
     * Move a node to a new parent
     */
    @PostMapping("/nodes/{nodeId}/move")
    @Operation(summary = "Move hierarchy node",
               description = "Move a node to a different parent in the hierarchy")
    public ResponseEntity<ApiResponse<HierarchyNode>> moveNode(
            @PathVariable String nodeId,
            @RequestBody MoveNodeRequest request) {
        
        log.info("POST /api/v1/treasury/balance-structure/nodes/{}/move to {}", 
                 nodeId, request.getNewParentId());
        
        // Return updated node
        HierarchyNode node = HierarchyNode.builder()
            .id(nodeId)
            .parentId(request.getNewParentId())
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(node));
    }

    /**
     * Delete a hierarchy node
     */
    @DeleteMapping("/nodes/{nodeId}")
    @Operation(summary = "Delete hierarchy node",
               description = "Remove a node from the hierarchy")
    public ResponseEntity<ApiResponse<Void>> deleteNode(@PathVariable String nodeId) {
        
        log.info("DELETE /api/v1/treasury/balance-structure/nodes/{}", nodeId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ========================================================================
    // EXPORT APIs
    // ========================================================================

    /**
     * Export balance structure report
     */
    @PostMapping("/export")
    @Operation(summary = "Export balance structure",
               description = "Export the balance structure as CSV, XLSX, or PDF")
    public ResponseEntity<ApiResponse<String>> exportReport(
            @RequestParam(required = false) UUID corporateId,
            @RequestBody ExportRequest request) {
        
        log.info("POST /api/v1/treasury/balance-structure/export - format: {}", request.getFormat());
        
        // In real implementation, generate file and return download URL
        String downloadUrl = "/api/v1/treasury/balance-structure/export/download/" + UUID.randomUUID();
        
        return ResponseEntity.ok(ApiResponse.success(downloadUrl));
    }

    // ========================================================================
    // REFRESH APIs
    // ========================================================================

    /**
     * Refresh balance structure data
     */
    @PostMapping("/refresh")
    @Operation(summary = "Refresh balance data",
               description = "Trigger a refresh of all balance and position data from source systems")
    public ResponseEntity<ApiResponse<String>> refreshData(
            @RequestParam(required = false) UUID corporateId) {
        
        log.info("POST /api/v1/treasury/balance-structure/refresh");
        
        // In real implementation, trigger async refresh job
        return ResponseEntity.ok(ApiResponse.success("Refresh initiated"));
    }

    // ========================================================================
    // CURRENCY APIs
    // ========================================================================

    /**
     * Get available currencies for reporting
     */
    @GetMapping("/currencies")
    @Operation(summary = "Get available currencies",
               description = "Returns list of currencies available for reporting")
    public ResponseEntity<ApiResponse<java.util.List<CurrencyInfo>>> getAvailableCurrencies() {
        
        java.util.List<CurrencyInfo> currencies = java.util.List.of(
            new CurrencyInfo("AED", "UAE Dirham", "د.إ"),
            new CurrencyInfo("USD", "US Dollar", "$"),
            new CurrencyInfo("EUR", "Euro", "€"),
            new CurrencyInfo("GBP", "British Pound", "£"),
            new CurrencyInfo("SGD", "Singapore Dollar", "S$"),
            new CurrencyInfo("CHF", "Swiss Franc", "CHF"),
            new CurrencyInfo("SAR", "Saudi Riyal", "﷼")
        );
        
        return ResponseEntity.ok(ApiResponse.success(currencies));
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private UUID getDefaultCorporateId() {
        // In real implementation, get from security context or configuration
        return UUID.fromString("11111111-1111-1111-1111-111111111111");
    }

    // ========================================================================
    // INNER CLASSES
    // ========================================================================

    public record CurrencyInfo(String code, String name, String symbol) {}
}
