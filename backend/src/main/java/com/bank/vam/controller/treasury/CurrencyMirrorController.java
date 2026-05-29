package com.bank.vam.controller.treasury;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.service.treasury.CurrencyMirrorService;
import com.bank.vam.service.treasury.CurrencyMirrorService.CurrencyBreakdown;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Currency Mirror Controller - CURRENCY_MIRROR Account Management.
 * 
 * UNIFIED ARCHITECTURE v4.2:
 * Currency Mirrors aggregate balances in a specific currency
 * and convert them to base currency using FX rates.
 * 
 * Hierarchy Position:
 * Shadow VAs → Currency Mirrors → ROOT
 * 
 * Each currency mirror:
 * - Aggregates all same-currency children
 * - Applies FX rate to convert to base currency
 * - Updates balanceInBase for ROOT aggregation
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/treasury/currency-mirrors")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "Currency Mirrors", description = "Currency Mirror Account Management APIs")
public class CurrencyMirrorController {

    private final CurrencyMirrorService currencyMirrorService;

    // ========================================================================
    // CREATE APIs
    // ========================================================================

    /**
     * Create a currency mirror under a parent node.
     */
    @PostMapping
    @Operation(summary = "Create currency mirror",
               description = "Create a CURRENCY_MIRROR VA to aggregate same-currency balances")
    public ResponseEntity<ApiResponse<CurrencyMirrorResponse>> createCurrencyMirror(
            @Valid @RequestBody CreateMirrorRequest request) {
        
        log.info("POST /api/v1/treasury/currency-mirrors - {} under parent {}", 
                 request.getCurrency(), request.getParentVaId());
        
        VirtualAccount mirror = currencyMirrorService.createCurrencyMirror(
            request.getParentVaId(),
            request.getCurrency().toUpperCase(),
            request.getBaseCurrency().toUpperCase(),
            request.getCorporateId()
        );
        
        return ResponseEntity.ok(ApiResponse.success(
            toResponse(mirror), 
            "Currency mirror created for " + request.getCurrency()
        ));
    }

    // ========================================================================
    // RECALCULATE APIs
    // ========================================================================

    /**
     * Recalculate mirror balance from children.
     */
    @PostMapping("/{mirrorVaId}/recalculate")
    @Operation(summary = "Recalculate mirror balance",
               description = "Recalculate the mirror balance by summing all same-currency children")
    public ResponseEntity<ApiResponse<CurrencyMirrorResponse>> recalculateMirrorBalance(
            @PathVariable UUID mirrorVaId) {
        
        log.info("POST /api/v1/treasury/currency-mirrors/{}/recalculate", mirrorVaId);
        
        VirtualAccount mirror = currencyMirrorService.recalculateMirrorBalance(mirrorVaId);
        
        return ResponseEntity.ok(ApiResponse.success(
            toResponse(mirror), 
            "Mirror balance recalculated"
        ));
    }

    /**
     * Recalculate all mirrors for a corporate.
     */
    @PostMapping("/recalculate/corporate/{corporateId}")
    @Operation(summary = "Recalculate all mirrors",
               description = "Recalculate all currency mirror balances for a corporate")
    public ResponseEntity<ApiResponse<BulkRecalculateResponse>> recalculateAllMirrors(
            @PathVariable UUID corporateId) {
        
        log.info("POST /api/v1/treasury/currency-mirrors/recalculate/corporate/{}", corporateId);
        
        List<VirtualAccount> mirrors = currencyMirrorService.getMirrorsByCorporate(corporateId);
        int recalculated = 0;
        
        for (VirtualAccount mirror : mirrors) {
            try {
                currencyMirrorService.recalculateMirrorBalance(mirror.getId());
                recalculated++;
            } catch (Exception e) {
                log.error("Failed to recalculate mirror {}: {}", mirror.getId(), e.getMessage());
            }
        }
        
        BulkRecalculateResponse response = BulkRecalculateResponse.builder()
            .corporateId(corporateId)
            .totalMirrors(mirrors.size())
            .recalculated(recalculated)
            .failed(mirrors.size() - recalculated)
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(response, recalculated + " mirrors recalculated"));
    }

    /**
     * Recalculate all mirrors for a specific program.
     * This is the preferred endpoint for multi-program corporates.
     */
    @PostMapping("/recalculate/program/{programId}")
    @Operation(summary = "Recalculate all mirrors for program",
               description = "Recalculate all currency mirror balances for a specific program. " +
                           "Preferred for multi-program corporates.")
    public ResponseEntity<ApiResponse<BulkRecalculateResponse>> recalculateAllMirrorsByProgram(
            @PathVariable UUID programId) {

        log.info("POST /api/v1/treasury/currency-mirrors/recalculate/program/{}", programId);

        int recalculated = currencyMirrorService.recalculateAllMirrors(programId);

        BulkRecalculateResponse response = BulkRecalculateResponse.builder()
            .programId(programId)
            .totalMirrors(recalculated)
            .recalculated(recalculated)
            .failed(0)
            .build();

        return ResponseEntity.ok(ApiResponse.success(response, recalculated + " mirrors recalculated"));
    }

    // ========================================================================
    // QUERY APIs
    // ========================================================================

    /**
     * Get all currency mirrors for a corporate.
     */
    @GetMapping("/corporate/{corporateId}")
    @Operation(summary = "Get mirrors by corporate",
               description = "Get all currency mirror VAs for a corporate")
    public ResponseEntity<ApiResponse<List<CurrencyMirrorResponse>>> getMirrorsByCorporate(
            @PathVariable UUID corporateId) {
        
        log.info("GET /api/v1/treasury/currency-mirrors/corporate/{}", corporateId);
        
        List<CurrencyMirrorResponse> mirrors = currencyMirrorService.getMirrorsByCorporate(corporateId)
            .stream()
            .map(this::toResponse)
            .collect(Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.success(mirrors));
    }

    /**
     * Get currency breakdown for a corporate.
     */
    @GetMapping("/breakdown/{corporateId}")
    @Operation(summary = "Get currency breakdown",
               description = "Get balance breakdown by currency with FX conversion details")
    public ResponseEntity<ApiResponse<Map<String, CurrencyBreakdown>>> getCurrencyBreakdown(
            @PathVariable UUID corporateId) {
        
        log.info("GET /api/v1/treasury/currency-mirrors/breakdown/{}", corporateId);
        
        Map<String, CurrencyBreakdown> breakdown = currencyMirrorService.getCurrencyBreakdown(corporateId);
        return ResponseEntity.ok(ApiResponse.success(breakdown));
    }

    /**
     * Get currency breakdown as a list (for frontend tables).
     */
    @GetMapping("/breakdown/{corporateId}/list")
    @Operation(summary = "Get currency breakdown as list",
               description = "Get balance breakdown by currency as a list for table display")
    public ResponseEntity<ApiResponse<List<CurrencyBreakdownResponse>>> getCurrencyBreakdownList(
            @PathVariable UUID corporateId) {
        
        log.info("GET /api/v1/treasury/currency-mirrors/breakdown/{}/list", corporateId);
        
        Map<String, CurrencyBreakdown> breakdown = currencyMirrorService.getCurrencyBreakdown(corporateId);
        
        List<CurrencyBreakdownResponse> list = breakdown.values().stream()
            .map(cb -> CurrencyBreakdownResponse.builder()
                .currency(cb.getCurrency())
                .baseCurrency(cb.getBaseCurrency())
                .originalBalance(cb.getOriginalBalance())
                .fxRate(cb.getFxRate())
                .fxRateAt(cb.getFxRateAt())
                .convertedBalance(cb.getConvertedBalance())
                .mirrorVaId(cb.getMirrorVaId())
                .mirrorVaNumber(cb.getMirrorVaNumber())
                .build())
            .collect(Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.success(list));
    }

    /**
     * Get total consolidated balance in base currency.
     */
    @GetMapping("/consolidated/{corporateId}")
    @Operation(summary = "Get consolidated balance",
               description = "Get total balance across all currencies converted to base currency")
    public ResponseEntity<ApiResponse<ConsolidatedBalanceResponse>> getConsolidatedBalance(
            @PathVariable UUID corporateId,
            @Parameter(description = "Base currency for consolidation")
            @RequestParam(defaultValue = "AED") String baseCurrency) {

        log.info("GET /api/v1/treasury/currency-mirrors/consolidated/{} - base: {}",
                 corporateId, baseCurrency);

        Map<String, CurrencyBreakdown> breakdown = currencyMirrorService.getCurrencyBreakdown(corporateId);

        BigDecimal totalInBase = breakdown.values().stream()
            .map(CurrencyBreakdown::getConvertedBalance)
            .filter(b -> b != null)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        ConsolidatedBalanceResponse response = ConsolidatedBalanceResponse.builder()
            .corporateId(corporateId)
            .baseCurrency(baseCurrency.toUpperCase())
            .totalBalance(totalInBase)
            .currencyCount(breakdown.size())
            .asOf(LocalDateTime.now())
            .build();

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ========================================================================
    // PROGRAM-BASED QUERY APIs (preferred for multi-program corporates)
    // ========================================================================

    /**
     * Get all currency mirrors for a specific program.
     * This is the preferred endpoint for multi-program corporates.
     */
    @GetMapping("/program/{programId}")
    @Operation(summary = "Get mirrors by program",
               description = "Get all currency mirror VAs for a specific program. " +
                           "Preferred for multi-program corporates.")
    public ResponseEntity<ApiResponse<List<CurrencyMirrorResponse>>> getMirrorsByProgram(
            @PathVariable UUID programId) {

        log.info("GET /api/v1/treasury/currency-mirrors/program/{}", programId);

        List<CurrencyMirrorResponse> mirrors = currencyMirrorService.getMirrorsByProgram(programId)
            .stream()
            .map(this::toResponse)
            .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(mirrors));
    }

    /**
     * Get currency breakdown for a specific program.
     * This is the preferred endpoint for accurate currency breakdown in multi-program corporates.
     */
    @GetMapping("/breakdown/program/{programId}")
    @Operation(summary = "Get currency breakdown by program",
               description = "Get balance breakdown by currency for a specific program. " +
                           "Preferred for accurate breakdown in multi-program corporates.")
    public ResponseEntity<ApiResponse<Map<String, CurrencyBreakdown>>> getCurrencyBreakdownByProgram(
            @PathVariable UUID programId) {

        log.info("GET /api/v1/treasury/currency-mirrors/breakdown/program/{}", programId);

        Map<String, CurrencyBreakdown> breakdown = currencyMirrorService.getCurrencyBreakdownByProgram(programId);
        return ResponseEntity.ok(ApiResponse.success(breakdown));
    }

    /**
     * Get currency breakdown as a list for a specific program.
     * This is the preferred endpoint for multi-program corporates.
     *
     * v5.7.1: Added optional level parameter for level-based breakdown.
     * - level=0: ROOT level only (recommended - shows aggregated total)
     * - level=1+: Specific AGGREGATION levels
     * - level=null: All levels (may cause double counting - use with caution)
     */
    @GetMapping("/breakdown/program/{programId}/list")
    @Operation(summary = "Get currency breakdown list by program",
               description = "Get balance breakdown by currency as a list for a specific program. " +
                           "Use level=0 for ROOT (total), or specific level for that level only. " +
                           "Omitting level returns all (may double count).")
    public ResponseEntity<ApiResponse<List<CurrencyBreakdownResponse>>> getCurrencyBreakdownListByProgram(
            @PathVariable UUID programId,
            @Parameter(description = "Hierarchy level (0=ROOT for total, 1+=AGGREGATION levels). " +
                                   "Omit to get all levels (may double count).")
            @RequestParam(required = false) Integer level) {

        log.info("GET /api/v1/treasury/currency-mirrors/breakdown/program/{}/list?level={}",
            programId, level);

        Map<String, CurrencyBreakdown> breakdown;
        if (level != null) {
            breakdown = currencyMirrorService.getCurrencyBreakdownByProgramAndLevel(programId, level);
        } else {
            // Default to ROOT level (0) to avoid double counting
            breakdown = currencyMirrorService.getCurrencyBreakdownByProgramAndLevel(programId, 0);
        }

        List<CurrencyBreakdownResponse> list = breakdown.values().stream()
            .map(cb -> CurrencyBreakdownResponse.builder()
                .currency(cb.getCurrency())
                .baseCurrency(cb.getBaseCurrency())
                .originalBalance(cb.getOriginalBalance())
                .fxRate(cb.getFxRate())
                .fxRateAt(cb.getFxRateAt())
                .convertedBalance(cb.getConvertedBalance())
                .mirrorVaId(cb.getMirrorVaId())
                .mirrorVaNumber(cb.getMirrorVaNumber())
                .level(cb.getLevel())
                .build())
            .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(list));
    }

    /**
     * Get currency breakdown for mirrors under a specific parent node.
     * This is the preferred endpoint when clicking on a specific AGGREGATION node in the tree.
     *
     * v5.7.2: Node-specific breakdown to avoid showing mirrors from sibling nodes at same level.
     *
     * @param nodeId The ID of the parent node (AGGREGATION or ROOT VA ID)
     * @return List of CurrencyBreakdown for mirrors directly under this node
     */
    @GetMapping("/breakdown/node/{nodeId}/list")
    @Operation(summary = "Get currency breakdown list by parent node",
               description = "Get balance breakdown by currency for mirrors directly under a specific node. " +
                           "Use this when clicking on a specific AGGREGATION node in the tree view. " +
                           "Returns only mirrors that are direct children of the specified node.")
    public ResponseEntity<ApiResponse<List<CurrencyBreakdownResponse>>> getCurrencyBreakdownListByNode(
            @PathVariable UUID nodeId) {

        log.info("GET /api/v1/treasury/currency-mirrors/breakdown/node/{}/list", nodeId);

        Map<String, CurrencyBreakdown> breakdown = currencyMirrorService.getCurrencyBreakdownByParentNode(nodeId);

        List<CurrencyBreakdownResponse> list = breakdown.values().stream()
            .map(cb -> CurrencyBreakdownResponse.builder()
                .currency(cb.getCurrency())
                .baseCurrency(cb.getBaseCurrency())
                .originalBalance(cb.getOriginalBalance())
                .fxRate(cb.getFxRate())
                .fxRateAt(cb.getFxRateAt())
                .convertedBalance(cb.getConvertedBalance())
                .mirrorVaId(cb.getMirrorVaId())
                .mirrorVaNumber(cb.getMirrorVaNumber())
                .level(cb.getLevel())
                .build())
            .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(list));
    }

    /**
     * Get available hierarchy levels for a program's currency mirrors.
     * Useful for populating level selector in UI.
     */
    @GetMapping("/breakdown/program/{programId}/levels")
    @Operation(summary = "Get available hierarchy levels",
               description = "Get list of hierarchy levels that have currency mirrors for this program. " +
                           "Use this to populate a level selector in the UI.")
    public ResponseEntity<ApiResponse<List<LevelInfoResponse>>> getAvailableLevels(
            @PathVariable UUID programId) {

        log.info("GET /api/v1/treasury/currency-mirrors/breakdown/program/{}/levels", programId);

        List<CurrencyMirrorService.LevelInfo> levels = currencyMirrorService.getAvailableLevels(programId);

        List<LevelInfoResponse> response = levels.stream()
            .map(l -> LevelInfoResponse.builder()
                .level(l.getLevel())
                .name(l.getName())
                .build())
            .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Get consolidated balance for a specific program.
     * This is the preferred endpoint for multi-program corporates.
     */
    @GetMapping("/consolidated/program/{programId}")
    @Operation(summary = "Get consolidated balance by program",
               description = "Get total balance across all currencies for a specific program. " +
                           "Preferred for accurate totals in multi-program corporates.")
    public ResponseEntity<ApiResponse<ConsolidatedBalanceResponse>> getConsolidatedBalanceByProgram(
            @PathVariable UUID programId,
            @Parameter(description = "Base currency for consolidation (defaults to program's base currency)")
            @RequestParam(required = false) String baseCurrency) {

        log.info("GET /api/v1/treasury/currency-mirrors/consolidated/program/{} - base: {}",
                 programId, baseCurrency);

        Map<String, CurrencyBreakdown> breakdown = currencyMirrorService.getCurrencyBreakdownByProgram(programId);

        // Determine base currency - use provided or derive from first breakdown entry
        String effectiveBaseCurrency = baseCurrency != null ? baseCurrency.toUpperCase() :
            breakdown.values().stream()
                .map(CurrencyBreakdown::getBaseCurrency)
                .filter(bc -> bc != null)
                .findFirst()
                .orElse("AED");

        BigDecimal totalInBase = breakdown.values().stream()
            .map(CurrencyBreakdown::getConvertedBalance)
            .filter(b -> b != null)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        ConsolidatedBalanceResponse response = ConsolidatedBalanceResponse.builder()
            .corporateId(null) // Not applicable for program-based query
            .programId(programId)
            .baseCurrency(effectiveBaseCurrency)
            .totalBalance(totalInBase)
            .currencyCount(breakdown.size())
            .asOf(LocalDateTime.now())
            .build();

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private CurrencyMirrorResponse toResponse(VirtualAccount mirror) {
        return CurrencyMirrorResponse.builder()
            .id(mirror.getId())
            .vaNumber(mirror.getVaNumber())
            .vaName(mirror.getVaName())
            .corporateId(mirror.getCorporateId())
            .currencyCode(mirror.getCurrencyCode())
            .baseCurrency(mirror.getBaseCurrency())
            .mirrorBalance(mirror.getMirrorBalance())
            .fxRate(mirror.getFxRate())
            .fxRateAt(mirror.getFxRateAt())
            .fxRateSource(mirror.getFxRateSource())
            .balanceInBase(mirror.getBalanceInBase())
            .parentAccountId(mirror.getParentAccountId())
            .hierarchyLevel(mirror.getHierarchyLevel())
            .hierarchyPathVa(mirror.getHierarchyPathVa())
            .status(mirror.getStatus().name())
            .createdAt(mirror.getCreatedAt())
            .updatedAt(mirror.getUpdatedAt())
            .build();
    }

    // ========================================================================
    // REQUEST/RESPONSE DTOs
    // ========================================================================

    @Data
    public static class CreateMirrorRequest {
        @NotNull
        private UUID parentVaId;
        @NotBlank
        private String currency;
        @NotBlank
        private String baseCurrency;
        @NotNull
        private UUID corporateId;
    }

    @Data
    @Builder
    public static class CurrencyMirrorResponse {
        private UUID id;
        private String vaNumber;
        private String vaName;
        private UUID corporateId;
        private String currencyCode;
        private String baseCurrency;
        private BigDecimal mirrorBalance;
        private BigDecimal fxRate;
        private LocalDateTime fxRateAt;
        private String fxRateSource;
        private BigDecimal balanceInBase;
        private UUID parentAccountId;
        private Integer hierarchyLevel;
        private String hierarchyPathVa;
        private String status;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    @Builder
    public static class BulkRecalculateResponse {
        private UUID corporateId;
        private UUID programId;
        private int totalMirrors;
        private int recalculated;
        private int failed;
    }

    @Data
    @Builder
    public static class CurrencyBreakdownResponse {
        private String currency;
        private String baseCurrency;
        private BigDecimal originalBalance;
        private BigDecimal fxRate;
        private LocalDateTime fxRateAt;
        private BigDecimal convertedBalance;
        private UUID mirrorVaId;
        private String mirrorVaNumber;
        private Integer level;  // v5.7.1: Hierarchy level for level-based breakdown
    }

    /**
     * Level info response for level selector UI.
     */
    @Data
    @Builder
    public static class LevelInfoResponse {
        private Integer level;
        private String name;
    }

    @Data
    @Builder
    public static class ConsolidatedBalanceResponse {
        private UUID corporateId;
        private UUID programId;
        private String baseCurrency;
        private BigDecimal totalBalance;
        private int currencyCount;
        private LocalDateTime asOf;
    }
}