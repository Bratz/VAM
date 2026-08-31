package com.bank.vam.controller.treasury;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.service.treasury.FundsAvailabilityService;
import com.bank.vam.service.treasury.FundsAvailabilityService.FundsCheckResult;
import com.bank.vam.service.treasury.FundsAvailabilityService.LevelCheckResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Funds Availability Controller - ⭐ THE CRITICAL CONTROLLER ⭐
 * 
 * UNIFIED ARCHITECTURE v4.2:
 * This controller provides the critical funds availability check that must
 * pass before any debit transaction can be executed.
 * 
 * The Two Questions at Every Level:
 * 1. BALANCE: Do you HAVE the funds?
 * 2. LIMIT: Are you ALLOWED to spend?
 * 
 * Check Flow for Debit Request:
 * ┌───────────────────────────────────────────────────────────────┐
 * │  FOR EACH LEVEL IN HIERARCHY (VA → ROOT):                    │
 * │                                                               │
 * │  Level 0: UK-PAYABLES (VA)                                   │
 * │    Balance: €200K + Internal Limit: €500K = €700K ✓          │
 * │                                                               │
 * │  Level 1: MIRROR-EUR                                         │
 * │    Balance: €18M + Limit: €0 = €18M ✓                        │
 * │                                                               │
 * │  Level 2: SHADOW-EUR                                         │
 * │    Bank: €20M + External Limit: €30M = €50M ✓                │
 * │                                                               │
 * │  Level 3: ROOT                                               │
 * │    Aggregated: €45.2M + Master: €50M = €95.2M ✓              │
 * │                                                               │
 * │  ALL LEVELS PASS → DEBIT APPROVED                            │
 * └───────────────────────────────────────────────────────────────┘
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/treasury/funds")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "Funds Availability", description = "Critical Funds Availability Check APIs")
public class FundsAvailabilityController {

    private final FundsAvailabilityService fundsAvailabilityService;

    // ========================================================================
    // PRIMARY CHECK APIs - THE CRITICAL ONES
    // ========================================================================

    /**
     * ⭐ Check funds availability for a debit - THE CRITICAL CHECK ⭐
     * 
     * This is the primary API that must be called before any debit transaction.
     * It traverses the entire VA hierarchy from the source VA to ROOT, checking
     * at each level that sufficient funds (balance + credit limits) are available.
     */
    @PostMapping("/check")
    @Operation(summary = "⭐ Check funds availability",
               description = "THE CRITICAL CHECK: Verify funds are available at all hierarchy levels before debit. " +
                           "Returns detailed breakdown showing balance, limits, and approval status at each level.")
    public ResponseEntity<ApiResponse<FundsCheckResponse>> checkFundsAvailability(
            @Valid @RequestBody FundsCheckRequest request) {
        
        log.info("POST /api/v1/treasury/funds/check - VA: {}, Amount: {} {}", 
                 request.getVaId(), request.getAmount(), request.getCurrency());
        
        FundsCheckResult result = fundsAvailabilityService.checkFundsAvailability(
            request.getVaId(),
            request.getAmount(),
            request.getCurrency()
        );
        
        FundsCheckResponse response = toResponse(result);
        
        if (result.isApproved()) {
            log.info("Funds check APPROVED for VA: {} - {} {}", 
                     request.getVaId(), request.getAmount(), request.getCurrency());
            return ResponseEntity.ok(ApiResponse.success(response, "Funds available at all levels"));
        } else {
            log.warn("Funds check REJECTED at level {} for VA: {} - {}", 
                     result.getRejectionLevel(), request.getVaId(), result.getRejectionReason());
            return ResponseEntity.ok(ApiResponse.success(response, "Insufficient funds: " + result.getRejectionReason()));
        }
    }

    /**
     * Quick check - simple GET for basic availability.
     */
    @GetMapping("/check/{vaId}")
    @Operation(summary = "Quick funds check (GET)",
               description = "Simple GET-based funds check for quick availability verification")
    public ResponseEntity<ApiResponse<FundsCheckResponse>> checkFundsQuick(
            @PathVariable UUID vaId,
            @Parameter(description = "Amount to check")
            @RequestParam @NotNull @Positive BigDecimal amount,
            @Parameter(description = "Currency code")
            @RequestParam @NotBlank String currency) {
        
        log.info("GET /api/v1/treasury/funds/check/{} - {} {}", vaId, amount, currency);
        
        FundsCheckResult result = fundsAvailabilityService.checkFundsAvailability(
            vaId, amount, currency.toUpperCase()
        );
        
        return ResponseEntity.ok(ApiResponse.success(toResponse(result)));
    }

    /**
     * Batch check - check multiple VAs at once.
     */
    @PostMapping("/check/batch")
    @Operation(summary = "Batch funds check",
               description = "Check funds availability for multiple VAs in a single call")
    public ResponseEntity<ApiResponse<BatchCheckResponse>> checkFundsBatch(
            @Valid @RequestBody BatchCheckRequest request) {
        
        log.info("POST /api/v1/treasury/funds/check/batch - {} requests", request.getChecks().size());
        
        List<FundsCheckResponse> results = request.getChecks().stream()
            .map(check -> {
                FundsCheckResult result = fundsAvailabilityService.checkFundsAvailability(
                    check.getVaId(),
                    check.getAmount(),
                    check.getCurrency()
                );
                return toResponse(result);
            })
            .toList();
        
        long approved = results.stream().filter(FundsCheckResponse::isApproved).count();
        long rejected = results.size() - approved;
        
        BatchCheckResponse response = BatchCheckResponse.builder()
            .totalChecks(results.size())
            .approved((int) approved)
            .rejected((int) rejected)
            .results(results)
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ========================================================================
    // QUICK CHECK APIs - Simplified checks
    // ========================================================================

    /**
     * Simple balance check (single level, no hierarchy).
     */
    @GetMapping("/simple-balance/{vaId}")
    @Operation(summary = "Simple balance check",
               description = "Quick single-level balance check (no hierarchy traversal)")
    public ResponseEntity<ApiResponse<SimpleCheckResponse>> hasSimpleBalance(
            @PathVariable UUID vaId,
            @RequestParam @NotNull @Positive BigDecimal amount) {
        
        log.info("GET /api/v1/treasury/funds/simple-balance/{} - {}", vaId, amount);
        
        boolean hasBalance = fundsAvailabilityService.hasSimpleBalance(vaId, amount);
        
        SimpleCheckResponse response = SimpleCheckResponse.builder()
            .vaId(vaId)
            .requestedAmount(amount)
            .available(hasBalance)
            .checkType("SIMPLE_BALANCE")
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Balance + limit check (single level).
     */
    @GetMapping("/with-limit/{vaId}")
    @Operation(summary = "Balance + limit check",
               description = "Single-level check including credit limits (no hierarchy)")
    public ResponseEntity<ApiResponse<SimpleCheckResponse>> hasFundsWithLimit(
            @PathVariable UUID vaId,
            @RequestParam @NotNull @Positive BigDecimal amount) {
        
        log.info("GET /api/v1/treasury/funds/with-limit/{} - {}", vaId, amount);
        
        boolean hasBalance = fundsAvailabilityService.hasFundsWithLimit(vaId, amount);
        
        SimpleCheckResponse response = SimpleCheckResponse.builder()
            .vaId(vaId)
            .requestedAmount(amount)
            .available(hasBalance)
            .checkType("BALANCE_WITH_LIMIT")
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Get total available funds for a VA.
     */
    @GetMapping("/total-available/{vaId}")
    @Operation(summary = "Get total available funds",
               description = "Get total available funds (balance + all limits) for a VA")
    public ResponseEntity<ApiResponse<TotalAvailableResponse>> getTotalAvailable(
            @PathVariable UUID vaId) {
        
        log.info("GET /api/v1/treasury/funds/total-available/{}", vaId);
        
        BigDecimal total = fundsAvailabilityService.getTotalAvailableFunds(vaId);
        
        TotalAvailableResponse response = TotalAvailableResponse.builder()
            .vaId(vaId)
            .totalAvailable(total)
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ========================================================================
    // LIMIT UTILIZATION APIs - Post-transaction updates
    // ========================================================================

    /**
     * Update limit utilization after successful debit.
     * Called by TransactionService after debit is executed.
     */
    @PostMapping("/utilization/{vaId}/debit")
    @Operation(summary = "Update utilization after debit",
               description = "Update credit limit utilization after a successful debit transaction")
    public ResponseEntity<ApiResponse<Void>> updateUtilizationAfterDebit(
            @PathVariable UUID vaId,
            @RequestParam @NotNull @Positive BigDecimal amount) {
        
        log.info("POST /api/v1/treasury/funds/utilization/{}/debit - {}", vaId, amount);
        
        fundsAvailabilityService.updateLimitUtilization(vaId, amount);
        return ResponseEntity.ok(ApiResponse.success(null, "Utilization updated"));
    }

    /**
     * Release limit utilization after credit.
     * Called by TransactionService after credit is executed.
     */
    @PostMapping("/utilization/{vaId}/credit")
    @Operation(summary = "Release utilization after credit",
               description = "Release credit limit utilization after a credit transaction (LIFO)")
    public ResponseEntity<ApiResponse<Void>> releaseUtilizationAfterCredit(
            @PathVariable UUID vaId,
            @RequestParam @NotNull @Positive BigDecimal amount) {
        
        log.info("POST /api/v1/treasury/funds/utilization/{}/credit - {}", vaId, amount);
        
        fundsAvailabilityService.releaseLimitUtilization(vaId, amount);
        return ResponseEntity.ok(ApiResponse.success(null, "Utilization released"));
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private FundsCheckResponse toResponse(FundsCheckResult result) {
        return FundsCheckResponse.builder()
            .vaId(result.getVaId())
            .requestedAmount(result.getRequestedAmount())
            .requestedCurrency(result.getRequestedCurrency())
            .checkedAt(result.getCheckedAt())
            .approved(result.isApproved())
            .rejectionLevel(result.getRejectionLevel())
            .rejectionReason(result.getRejectionReason())
            .rejectionVaId(result.getRejectionVaId())
            .rejectionVaNumber(result.getRejectionVaNumber())
            .levelResults(result.getLevelResults() != null ? 
                result.getLevelResults().stream()
                    .map(this::toLevelResponse)
                    .toList() : 
                List.of())
            .levelsChecked(result.getLevelResults() != null ? result.getLevelResults().size() : 0)
            .build();
    }

    private LevelCheckResponse toLevelResponse(LevelCheckResult level) {
        return LevelCheckResponse.builder()
            .level(level.getLevel())
            .vaId(level.getVaId())
            .vaNumber(level.getVaNumber())
            .vaCurrency(level.getVaCurrency())
            .accountCategory(level.getAccountCategory() != null ? level.getAccountCategory().name() : null)
            .requestedAmountInVaCurrency(level.getRequestedAmountInVaCurrency())
            .balance(level.getBalance())
            .externalLimitAvailable(level.getExternalLimitAvailable())
            .internalLimitAvailable(level.getInternalLimitAvailable())
            .totalAvailable(level.getTotalAvailable())
            .approved(level.isApproved())
            .rejectionReason(level.getRejectionReason())
            .shortfall(level.getShortfall())
            .limitUsageRequired(level.getLimitUsageRequired())
            .build();
    }

    // ========================================================================
    // REQUEST/RESPONSE DTOs
    // ========================================================================

    @Data
    public static class FundsCheckRequest {
        @NotNull
        private UUID vaId;
        @NotNull
        @Positive
        private BigDecimal amount;
        @NotBlank
        private String currency;
    }

    @Data
    public static class BatchCheckRequest {
        private List<FundsCheckRequest> checks;
    }

    @Data
    @Builder
    public static class FundsCheckResponse {
        private UUID vaId;
        private BigDecimal requestedAmount;
        private String requestedCurrency;
        private java.time.LocalDateTime checkedAt;
        
        private boolean approved;
        private int rejectionLevel;
        private String rejectionReason;
        private UUID rejectionVaId;
        private String rejectionVaNumber;
        
        private List<LevelCheckResponse> levelResults;
        private int levelsChecked;
    }

    @Data
    @Builder
    public static class LevelCheckResponse {
        private int level;
        private UUID vaId;
        private String vaNumber;
        private String vaCurrency;
        private String accountCategory;
        
        private BigDecimal requestedAmountInVaCurrency;
        private BigDecimal balance;
        private BigDecimal externalLimitAvailable;
        private BigDecimal internalLimitAvailable;
        private BigDecimal totalAvailable;
        
        private boolean approved;
        private String rejectionReason;
        private BigDecimal shortfall;
        private BigDecimal limitUsageRequired;
    }

    @Data
    @Builder
    public static class BatchCheckResponse {
        private int totalChecks;
        private int approved;
        private int rejected;
        private List<FundsCheckResponse> results;
    }

    @Data
    @Builder
    public static class SimpleCheckResponse {
        private UUID vaId;
        private BigDecimal requestedAmount;
        private boolean available;
        private String checkType;
    }

    @Data
    @Builder
    public static class TotalAvailableResponse {
        private UUID vaId;
        private BigDecimal totalAvailable;
    }
}