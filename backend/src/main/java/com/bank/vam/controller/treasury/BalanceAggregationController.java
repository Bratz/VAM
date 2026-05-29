package com.bank.vam.controller.treasury;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.service.treasury.BalanceAggregationServiceEnhanced;
import com.bank.vam.service.treasury.BalanceAggregationServiceEnhanced.MultiCurrencyPosition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Balance Aggregation Controller - Real-time Balance Rollup APIs.
 * 
 * UNIFIED ARCHITECTURE v4.2:
 * Manages balance aggregation across the VA hierarchy:
 * - Leaf VAs → Currency Mirrors → Shadow Accounts → ROOT
 * - Supports multi-currency with FX conversion
 * - Real-time propagation for transactions
 * - Scheduled aggregation for hierarchy nodes
 * 
 * Aggregation Strategy:
 * - TRANSACTION VAs: Direct balance
 * - CURRENCY_MIRROR: Sum of same-currency children + FX conversion
 * - PHYSICAL_MIRROR: Bank balance from CBS
 * - AGGREGATION/ROOT: Sum of children in base currency
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/treasury/aggregation")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "Balance Aggregation", description = "Real-time Balance Rollup and Aggregation APIs")
public class BalanceAggregationController {

    private final BalanceAggregationServiceEnhanced aggregationService;

    // ========================================================================
    // BALANCE PROPAGATION APIs
    // ========================================================================

    /**
     * Propagate a balance change up the hierarchy.
     * Called after a transaction affects a VA balance.
     */
    @PostMapping("/propagate/{vaId}")
    @Operation(summary = "Propagate balance change",
               description = "Propagate a balance delta up the VA hierarchy from the changed VA to ROOT")
    public ResponseEntity<ApiResponse<PropagationResult>> propagateBalanceChange(
            @PathVariable UUID vaId,
            @Parameter(description = "Balance change amount (positive for credit, negative for debit)")
            @RequestParam @NotNull BigDecimal balanceDelta) {
        
        log.info("POST /api/v1/treasury/aggregation/propagate/{} - delta: {}", vaId, balanceDelta);
        
        aggregationService.propagateBalanceChange(vaId, balanceDelta);
        
        PropagationResult result = PropagationResult.builder()
            .vaId(vaId)
            .balanceDelta(balanceDelta)
            .propagated(true)
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(result, "Balance change propagated"));
    }

    // ========================================================================
    // AGGREGATION APIs
    // ========================================================================

    /**
     * Trigger full aggregation for a corporate.
     */
    @PostMapping("/aggregate/corporate/{corporateId}")
    @Operation(summary = "Aggregate for corporate",
               description = "Trigger full balance aggregation for all VAs in a corporate hierarchy")
    public ResponseEntity<ApiResponse<AggregationResult>> aggregateForCorporate(
            @PathVariable UUID corporateId) {
        
        log.info("POST /api/v1/treasury/aggregation/aggregate/corporate/{}", corporateId);
        
        int count = aggregationService.aggregateForCorporate(corporateId);
        
        AggregationResult result = AggregationResult.builder()
            .corporateId(corporateId)
            .vasAggregated(count)
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(result, count + " VAs aggregated"));
    }

    /**
     * Update all currency mirrors for a corporate.
     */
    @PostMapping("/mirrors/corporate/{corporateId}")
    @Operation(summary = "Update currency mirrors",
               description = "Recalculate all currency mirror balances and FX conversions")
    public ResponseEntity<ApiResponse<String>> updateCurrencyMirrors(
            @PathVariable UUID corporateId) {
        
        log.info("POST /api/v1/treasury/aggregation/mirrors/corporate/{}", corporateId);
        
        aggregationService.updateCurrencyMirrors(corporateId);
        return ResponseEntity.ok(ApiResponse.success("Currency mirrors updated"));
    }

    /**
     * Manual trigger for scheduled aggregation.
     */
    @PostMapping("/trigger-scheduled")
    @Operation(summary = "Trigger scheduled aggregation",
               description = "Manually trigger the scheduled aggregation job for all corporates")
    public ResponseEntity<ApiResponse<String>> triggerScheduledAggregation() {
        
        log.info("POST /api/v1/treasury/aggregation/trigger-scheduled");
        
        aggregationService.scheduledAggregation();
        return ResponseEntity.ok(ApiResponse.success("Scheduled aggregation triggered"));
    }

    // ========================================================================
    // BALANCE QUERY APIs
    // ========================================================================

    /**
     * Get total balance for a corporate (from ROOT).
     */
    @GetMapping("/total/{corporateId}")
    @Operation(summary = "Get total balance",
               description = "Get the total aggregated balance from the ROOT node in base currency")
    public ResponseEntity<ApiResponse<BigDecimal>> getTotalBalance(
            @PathVariable UUID corporateId) {
        
        log.info("GET /api/v1/treasury/aggregation/total/{}", corporateId);
        
        BigDecimal total = aggregationService.getTotalBalance(corporateId);
        return ResponseEntity.ok(ApiResponse.success(total));
    }

    /**
     * Get balance for a specific VA.
     */
    @GetMapping("/balance/{vaId}")
    @Operation(summary = "Get VA balance",
               description = "Get the balance for a specific VA (aggregated if it's a node)")
    public ResponseEntity<ApiResponse<BigDecimal>> getVaBalance(
            @PathVariable UUID vaId) {
        
        log.info("GET /api/v1/treasury/aggregation/balance/{}", vaId);
        
        BigDecimal balance = aggregationService.getVaBalance(vaId);
        return ResponseEntity.ok(ApiResponse.success(balance));
    }

    /**
     * Get subtree balance in target currency.
     */
    @GetMapping("/subtree/{vaId}")
    @Operation(summary = "Get subtree balance",
               description = "Get the sum of all leaf VA balances under a node, converted to target currency")
    public ResponseEntity<ApiResponse<SubtreeBalanceResponse>> getSubtreeBalance(
            @PathVariable UUID vaId,
            @Parameter(description = "Target currency for aggregation")
            @RequestParam(defaultValue = "AED") String targetCurrency) {
        
        log.info("GET /api/v1/treasury/aggregation/subtree/{} - currency: {}", vaId, targetCurrency);
        
        BigDecimal balance = aggregationService.getSubtreeBalance(vaId, targetCurrency.toUpperCase());
        
        SubtreeBalanceResponse response = SubtreeBalanceResponse.builder()
            .vaId(vaId)
            .targetCurrency(targetCurrency.toUpperCase())
            .aggregatedBalance(balance)
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Get balance by hierarchy level.
     */
    @GetMapping("/by-level/{corporateId}")
    @Operation(summary = "Get balance by level",
               description = "Get balances grouped by hierarchy level")
    public ResponseEntity<ApiResponse<Map<Integer, BigDecimal>>> getBalanceByLevel(
            @PathVariable UUID corporateId,
            @RequestParam(defaultValue = "AED") String currency) {
        
        log.info("GET /api/v1/treasury/aggregation/by-level/{} - currency: {}", corporateId, currency);
        
        Map<Integer, BigDecimal> balances = aggregationService.getBalanceByLevel(corporateId, currency.toUpperCase());
        return ResponseEntity.ok(ApiResponse.success(balances));
    }

    // ========================================================================
    // MULTI-CURRENCY POSITION APIs
    // ========================================================================

    /**
     * Get multi-currency position for a corporate.
     * NOTE: For multi-program corporates, use /multi-currency/program/{programId} instead.
     */
    @GetMapping("/multi-currency/{corporateId}")
    @Operation(summary = "Get multi-currency position (by corporate)",
               description = "Get breakdown of balances by currency with FX conversion to base currency. " +
                           "For multi-program corporates, use /multi-currency/program/{programId} instead.")
    public ResponseEntity<ApiResponse<MultiCurrencyPosition>> getMultiCurrencyPosition(
            @PathVariable UUID corporateId,
            @Parameter(description = "Base currency for consolidated view")
            @RequestParam(defaultValue = "AED") String baseCurrency) {

        log.info("GET /api/v1/treasury/aggregation/multi-currency/{} - base: {}", corporateId, baseCurrency);

        MultiCurrencyPosition position = aggregationService.getMultiCurrencyPosition(
            corporateId, baseCurrency.toUpperCase()
        );

        return ResponseEntity.ok(ApiResponse.success(position));
    }

    /**
     * Get multi-currency position for a specific program.
     * This is the preferred endpoint for multi-program corporates.
     */
    @GetMapping("/multi-currency/program/{programId}")
    @Operation(summary = "Get multi-currency position (by program)",
               description = "Get breakdown of balances by currency for a specific program. " +
                           "This is the preferred endpoint for accurate currency breakdown in multi-program corporates.")
    public ResponseEntity<ApiResponse<MultiCurrencyPosition>> getMultiCurrencyPositionByProgram(
            @PathVariable UUID programId,
            @Parameter(description = "Base currency for consolidated view (defaults to program's base currency)")
            @RequestParam(required = false) String baseCurrency) {

        log.info("GET /api/v1/treasury/aggregation/multi-currency/program/{} - base: {}", programId, baseCurrency);

        MultiCurrencyPosition position = aggregationService.getMultiCurrencyPositionByProgram(
            programId, baseCurrency != null ? baseCurrency.toUpperCase() : null
        );

        return ResponseEntity.ok(ApiResponse.success(position));
    }

    // ========================================================================
    // REQUEST/RESPONSE DTOs
    // ========================================================================

    @Data
    @Builder
    public static class PropagationResult {
        private UUID vaId;
        private BigDecimal balanceDelta;
        private boolean propagated;
    }

    @Data
    @Builder
    public static class AggregationResult {
        private UUID corporateId;
        private int vasAggregated;
    }

    @Data
    @Builder
    public static class SubtreeBalanceResponse {
        private UUID vaId;
        private String targetCurrency;
        private BigDecimal aggregatedBalance;
    }
}