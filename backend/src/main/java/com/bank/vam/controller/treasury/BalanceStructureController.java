package com.bank.vam.controller.treasury;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.dto.treasury.BalanceStructureDto;
import com.bank.vam.dto.treasury.BalanceStructureDto.*;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.ProgramRepository;
import com.bank.vam.service.treasury.BalanceStructureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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
    private final ProgramRepository programRepository;

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
            @Parameter(description = "Corporate ID (required unless programId is given)")
            @RequestParam(required = false) UUID corporateId,
            @Parameter(description = "Program ID to filter by (optional)")
            @RequestParam(required = false) UUID programId,
            @Parameter(description = "Reporting currency for consolidated balances")
            @RequestParam(defaultValue = "AED") String reportingCurrency) {

        log.info("GET /api/v1/treasury/balance-structure - corporateId: {}, programId: {}, currency: {}",
                 corporateId, programId, reportingCurrency);

        UUID corpId = resolveCorporateId(corporateId, programId);
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

        UUID corpId = resolveCorporateId(corporateId, programId);
        BalanceSummary summary = balanceStructureService.getSummary(corpId, programId, reportingCurrency);

        return ResponseEntity.ok(ApiResponse.success(summary));
    }

    /**
     * Dashboard "Position breakdown" — firm-wide total per corporate.
     */
    @GetMapping("/by-corporate")
    @Operation(summary = "Get balance breakdown by corporate",
               description = "Returns each corporate's FX-converted total balance, firm-wide (not scoped to one corporate)")
    public ResponseEntity<ApiResponse<List<BalanceBreakdownItem>>> getByCorporate(
            @RequestParam(defaultValue = "AED") String reportingCurrency) {
        return ResponseEntity.ok(ApiResponse.success(balanceStructureService.getBalanceByCorporate(reportingCurrency)));
    }

    /**
     * Dashboard "Position breakdown" — total per program, optionally scoped to one corporate.
     */
    @GetMapping("/by-program")
    @Operation(summary = "Get balance breakdown by program",
               description = "Returns each program's FX-converted total balance; scoped to corporateId if provided, firm-wide otherwise")
    public ResponseEntity<ApiResponse<List<BalanceBreakdownItem>>> getByProgram(
            @RequestParam(required = false) UUID corporateId,
            @RequestParam(defaultValue = "AED") String reportingCurrency) {
        return ResponseEntity.ok(ApiResponse.success(balanceStructureService.getBalanceByProgram(corporateId, reportingCurrency)));
    }

    /**
     * Get physical (real) bank account information
     */
    @GetMapping("/physical-account")
    @Operation(summary = "Get physical bank account",
               description = "Returns the program's real (backing) bank account; NOT_CONFIGURED when it has none")
    public ResponseEntity<ApiResponse<PhysicalAccountInfo>> getPhysicalAccount(
            @RequestParam(required = false) UUID corporateId,
            @RequestParam(required = false) UUID programId) {
        
        log.info("GET /api/v1/treasury/balance-structure/physical-account programId={}", programId);
        
        PhysicalAccountInfo account = balanceStructureService.getPhysicalAccount(corporateId, programId);
        
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
        
        throw notAvailable("creating hierarchy nodes");
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
        throw notAvailable("moving hierarchy nodes");
    }

    /**
     * Delete a hierarchy node
     */
    @DeleteMapping("/nodes/{nodeId}")
    @Operation(summary = "Delete hierarchy node",
               description = "Remove a node from the hierarchy")
    public ResponseEntity<ApiResponse<Void>> deleteNode(@PathVariable String nodeId) {
        throw notAvailable("deleting hierarchy nodes");
    }

    // ========================================================================
    // EXPORT APIs
    // ========================================================================

    @PostMapping("/export")
    @Operation(summary = "Export balance structure",
               description = "Not available: no export file is generated")
    public ResponseEntity<ApiResponse<String>> exportReport(
            @RequestParam(required = false) UUID corporateId,
            @RequestBody ExportRequest request) {
        throw new BusinessException("Not available: balance structure export — no export file generator is implemented");
    }

    // ========================================================================
    // REFRESH APIs
    // ========================================================================

    /** The hierarchy is read live on every GET; there is no separate refresh job to start. */
    @PostMapping("/refresh")
    @Operation(summary = "Refresh balance data",
               description = "Not available: the hierarchy is read live on every request")
    public ResponseEntity<ApiResponse<String>> refreshData(
            @RequestParam(required = false) UUID corporateId) {
        throw new BusinessException("Not available: balance refresh job — the hierarchy is read live on every request; reload it instead");
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

    /** The tree belongs to one corporate: the one asked for, else the program's owner. Never a default. */
    private UUID resolveCorporateId(UUID corporateId, UUID programId) {
        if (corporateId != null) return corporateId;
        if (programId != null) {
            return programRepository.findById(programId)
                .orElseThrow(() -> new ResourceNotFoundException("Program not found: " + programId))
                .getCorporateId();
        }
        throw new BusinessException("corporateId is required");
    }

    private static BusinessException notAvailable(String what) {
        return new BusinessException("Not available: " + what
            + " — the hierarchy is built from legal entities and virtual accounts; change those instead");
    }

    // ========================================================================
    // INNER CLASSES
    // ========================================================================

    public record CurrencyInfo(String code, String name, String symbol) {}
}
