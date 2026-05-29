package com.bank.vam.controller.treasury;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.service.treasury.FeePostingService;
import com.bank.vam.service.treasury.FeePostingService.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Fee Posting Controller - REST API for posting fees to Settlement VA.
 * 
 * Endpoints:
 * - POST /treasury/fees/post - Post a single fee
 * - POST /treasury/fees/post-charge/{chargeId} - Post a calculated charge
 * - POST /treasury/fees/post-tax/{taxId} - Post a calculated tax
 * - POST /treasury/fees/post-all/{referenceType}/{referenceId} - Post all fees for a reference
 * - POST /treasury/fees/batch - Batch post fees
 * - POST /treasury/fees/test - Test fee posting (creates test transaction)
 * 
 * VAM Compliance:
 * - All fee posting flows through SettlementVaResolverService
 * - Double-entry bookkeeping with correlation IDs
 * - Exception VA fallback when Settlement VA not configured
 */
@RestController
@RequestMapping("/api/v1/treasury/fees")
@RequiredArgsConstructor
@Slf4j
public class FeePostingController {

    private final FeePostingService feePostingService;

    // ========================================================================
    // SINGLE FEE POSTING
    // ========================================================================

    /**
     * Post a single fee from source VA to Settlement VA.
     */
    @PostMapping("/post")
    public ResponseEntity<ApiResponse<FeePostingResultDto>> postFee(
            @RequestBody PostFeeRequest request) {
        
        log.info("REST: Post fee - source={}, amount={}, type={}", 
            request.getSourceVaId(), request.getAmount(), request.getFeeType());
        
        FeePostingResult result = feePostingService.postFee(
            request.getSourceVaId(),
            request.getAmount(),
            request.getFeeType(),
            request.getReferenceId(),
            request.getDescription()
        );
        
        return ResponseEntity.ok(ApiResponse.success(toDto(result), 
            "Fee posted successfully to " + 
            (result.isExceptionFallback() ? "Exception VA (fallback)" : "Settlement VA")));
    }

    /**
     * Post a calculated charge.
     */
    @PostMapping("/post-charge/{chargeId}")
    public ResponseEntity<ApiResponse<FeePostingResultDto>> postCalculatedCharge(
            @PathVariable UUID chargeId) {
        
        log.info("REST: Post calculated charge - chargeId={}", chargeId);
        
        FeePostingResult result = feePostingService.postCalculatedCharge(chargeId);
        
        if (result == null || "SKIPPED".equals(result.getStatus())) {
            String reason = result != null ? result.getSkipReason() : "Charge is zero - no posting required";
            return ResponseEntity.ok(ApiResponse.success(toDto(result), reason));
        }
        
        return ResponseEntity.ok(ApiResponse.success(toDto(result), "Charge posted successfully"));
    }

    /**
     * Post a calculated tax.
     */
    @PostMapping("/post-tax/{taxId}")
    public ResponseEntity<ApiResponse<FeePostingResultDto>> postCalculatedTax(
            @PathVariable UUID taxId) {
        
        log.info("REST: Post calculated tax - taxId={}", taxId);
        
        FeePostingResult result = feePostingService.postCalculatedTax(taxId);
        
        if (result == null || "SKIPPED".equals(result.getStatus())) {
            String reason = result != null ? result.getSkipReason() : "Tax is zero - no posting required";
            return ResponseEntity.ok(ApiResponse.success(toDto(result), reason));
        }
        
        return ResponseEntity.ok(ApiResponse.success(toDto(result), "Tax posted successfully"));
    }

    // ========================================================================
    // BULK POSTING
    // ========================================================================

    /**
     * Post all charges and taxes for a reference (e.g., Payable).
     * Uses the 2-parameter backward-compatible method.
     */
    @PostMapping("/post-all/{referenceType}/{referenceId}")
    public ResponseEntity<ApiResponse<BulkPostingResultDto>> postAllForReference(
            @PathVariable String referenceType,
            @PathVariable UUID referenceId) {
        
        log.info("REST: Post all fees for reference - type={}, id={}", referenceType, referenceId);
        
        // Use 2-parameter backward-compatible methods
        List<FeePostingResult> chargeResults = feePostingService.postAllChargesForReference(
            referenceType, referenceId);
        List<FeePostingResult> taxResults = feePostingService.postAllTaxesForReference(
            referenceType, referenceId);
        
        int totalPosted = chargeResults.size() + taxResults.size();
        int exceptionCount = (int) chargeResults.stream().filter(FeePostingResult::isExceptionFallback).count()
                           + (int) taxResults.stream().filter(FeePostingResult::isExceptionFallback).count();
        
        // Calculate total amount with null-safe access
        BigDecimal totalAmount = calculateTotalAmount(chargeResults)
            .add(calculateTotalAmount(taxResults));
        
        BulkPostingResultDto result = BulkPostingResultDto.builder()
            .chargesPosted(chargeResults.size())
            .taxesPosted(taxResults.size())
            .totalPosted(totalPosted)
            .exceptionFallbackCount(exceptionCount)
            .totalAmount(totalAmount)
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(result, 
            "Posted " + totalPosted + " fees/taxes for " + referenceType + " " + referenceId));
    }

    /**
     * Post all charges and taxes for a reference with explicit source VA.
     * Uses the 3-parameter method when source VA is known.
     */
    @PostMapping("/post-all/{referenceType}/{referenceId}/source/{sourceVaId}")
    public ResponseEntity<ApiResponse<BulkPostingResultDto>> postAllForReferenceWithSource(
            @PathVariable String referenceType,
            @PathVariable UUID referenceId,
            @PathVariable UUID sourceVaId) {
        
        log.info("REST: Post all fees for reference - type={}, id={}, sourceVa={}", 
            referenceType, referenceId, sourceVaId);
        
        // Use 3-parameter methods with explicit source VA
        List<FeePostingResult> chargeResults = feePostingService.postAllChargesForReference(
            referenceType, referenceId, sourceVaId);
        List<FeePostingResult> taxResults = feePostingService.postAllTaxesForReference(
            referenceType, referenceId, sourceVaId);
        
        int totalPosted = chargeResults.size() + taxResults.size();
        int exceptionCount = (int) chargeResults.stream().filter(FeePostingResult::isExceptionFallback).count()
                           + (int) taxResults.stream().filter(FeePostingResult::isExceptionFallback).count();
        
        BigDecimal totalAmount = calculateTotalAmount(chargeResults)
            .add(calculateTotalAmount(taxResults));
        
        BulkPostingResultDto result = BulkPostingResultDto.builder()
            .chargesPosted(chargeResults.size())
            .taxesPosted(taxResults.size())
            .totalPosted(totalPosted)
            .exceptionFallbackCount(exceptionCount)
            .totalAmount(totalAmount)
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(result, 
            "Posted " + totalPosted + " fees/taxes for " + referenceType + " " + referenceId));
    }

    /**
     * Batch post multiple fees.
     */
    @PostMapping("/batch")
    public ResponseEntity<ApiResponse<BatchPostingResult>> batchPostFees(
            @RequestBody List<FeePostingRequest> requests) {
        
        log.info("REST: Batch post {} fees", requests.size());
        
        BatchPostingResult result = feePostingService.batchPostFees(requests);
        
        return ResponseEntity.ok(ApiResponse.success(result, 
            "Batch posting complete: " + result.getSuccessCount() + " success, " + 
            result.getFailureCount() + " failures"));
    }

    // ========================================================================
    // TEST ENDPOINT
    // ========================================================================

    /**
     * Test fee posting - creates a test fee transaction.
     * For development/testing purposes only.
     */
    @PostMapping("/test")
    public ResponseEntity<ApiResponse<FeePostingResultDto>> testFeePosting(
            @RequestParam UUID sourceVaId,
            @RequestParam(defaultValue = "10.00") BigDecimal amount,
            @RequestParam(defaultValue = "TEST_FEE") String feeType) {
        
        log.info("REST: Test fee posting - source={}, amount={}", sourceVaId, amount);
        
        FeePostingResult result = feePostingService.postFee(
            sourceVaId,
            amount,
            feeType,
            null,
            "Test fee posting"
        );
        
        String settlementVaNumber = result.getSettlementVa() != null 
            ? result.getSettlementVa().getVaNumber() 
            : result.getSettlementVaNumber();
        
        return ResponseEntity.ok(ApiResponse.success(toDto(result), 
            "Test fee posted to " + settlementVaNumber));
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    /**
     * Calculate total amount from a list of results with null-safe access.
     */
    private BigDecimal calculateTotalAmount(List<FeePostingResult> results) {
        return results.stream()
            .filter(r -> r != null && "POSTED".equals(r.getStatus()))
            .map(r -> {
                // Prefer creditTransaction.getAmount(), fallback to result.getAmount()
                if (r.getCreditTransaction() != null && r.getCreditTransaction().getAmount() != null) {
                    return r.getCreditTransaction().getAmount();
                }
                return r.getAmount() != null ? r.getAmount() : BigDecimal.ZERO;
            })
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ========================================================================
    // DTOs
    // ========================================================================

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PostFeeRequest {
        private UUID sourceVaId;
        private BigDecimal amount;
        private String feeType;
        private UUID referenceId;
        private String description;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class FeePostingResultDto {
        private UUID chargeId;
        private UUID debitTransactionId;
        private String debitReference;
        private UUID creditTransactionId;
        private String creditReference;
        private UUID settlementVaId;
        private String settlementVaNumber;
        private boolean isExceptionFallback;
        private String exceptionNumber;
        private String correlationId;
        private BigDecimal amount;
        private String status;
        private String skipReason;
        private String errorMessage;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class BulkPostingResultDto {
        private int chargesPosted;
        private int taxesPosted;
        private int totalPosted;
        private int exceptionFallbackCount;
        private BigDecimal totalAmount;
    }

    // ========================================================================
    // MAPPERS
    // ========================================================================

    /**
     * Convert FeePostingResult to DTO with null-safe access to entity fields.
     */
    private FeePostingResultDto toDto(FeePostingResult result) {
        if (result == null) {
            return null;
        }
        
        FeePostingResultDto.FeePostingResultDtoBuilder builder = FeePostingResultDto.builder()
            .chargeId(result.getChargeId())
            .debitTransactionId(result.getDebitTransactionId())
            .creditTransactionId(result.getCreditTransactionId())
            .settlementVaId(result.getSettlementVaId())
            .settlementVaNumber(result.getSettlementVaNumber())
            .isExceptionFallback(result.isExceptionFallback())
            .correlationId(result.getCorrelationId())
            .amount(result.getAmount())
            .status(result.getStatus())
            .skipReason(result.getSkipReason())
            .errorMessage(result.getErrorMessage());
        
        // Null-safe access to Transaction entities for reference numbers
        if (result.getDebitTransaction() != null) {
            builder.debitReference(result.getDebitTransaction().getReferenceNumber());
        }
        
        if (result.getCreditTransaction() != null) {
            builder.creditReference(result.getCreditTransaction().getReferenceNumber());
            // Override amount from actual transaction if available
            if (result.getCreditTransaction().getAmount() != null) {
                builder.amount(result.getCreditTransaction().getAmount());
            }
        }
        
        // Null-safe access to Settlement VA for vaNumber (fallback to settlementVaNumber field)
        if (result.getSettlementVa() != null) {
            builder.settlementVaNumber(result.getSettlementVa().getVaNumber());
        }
        
        // Null-safe access to ExceptionTransaction
        if (result.getExceptionTransaction() != null) {
            builder.exceptionNumber(result.getExceptionTransaction().getExceptionNumber());
        }
        
        return builder.build();
    }
}