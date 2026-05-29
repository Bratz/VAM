package com.bank.vam.controller.credit;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.entity.credit.CreditAgreement;
import com.bank.vam.service.credit.CreditAgreementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST Controller for Credit Agreement management.
 * 
 * UNIFIED ARCHITECTURE v4.2:
 * Credit Agreements are master agreements from Core Banking System.
 * They provide overall credit limits for corporates.
 * 
 * Base Path: /api/v1/credit-agreements
 */
@RestController
@RequestMapping("/api/v1/credit-agreements")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Credit Agreements", description = "Master credit agreement management")
public class CreditAgreementController {

    private final CreditAgreementService agreementService;

    // ========================================================================
    // CRUD OPERATIONS
    // ========================================================================

    @PostMapping
    @Operation(summary = "Create a new credit agreement")
    public ResponseEntity<ApiResponse<CreditAgreement>> create(@RequestBody CreditAgreement agreement) {
        log.info("Creating credit agreement: {}", agreement.getAgreementName());
        CreditAgreement created = agreementService.create(agreement);
        return ResponseEntity.ok(ApiResponse.success(created, "Credit agreement created"));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get credit agreement by ID")
    public ResponseEntity<ApiResponse<CreditAgreement>> getById(@PathVariable UUID id) {
        CreditAgreement agreement = agreementService.getById(id);
        return ResponseEntity.ok(ApiResponse.success(agreement));
    }

    @GetMapping("/external/{externalReference}")
    @Operation(summary = "Get credit agreement by external reference")
    public ResponseEntity<ApiResponse<CreditAgreement>> getByExternalReference(
            @PathVariable String externalReference) {
        CreditAgreement agreement = agreementService.getByExternalReference(externalReference);
        return ResponseEntity.ok(ApiResponse.success(agreement));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update credit agreement")
    public ResponseEntity<ApiResponse<CreditAgreement>> update(
            @PathVariable UUID id,
            @RequestBody CreditAgreement updates) {
        CreditAgreement updated = agreementService.update(id, updates);
        return ResponseEntity.ok(ApiResponse.success(updated, "Credit agreement updated"));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete credit agreement (cancel)")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        agreementService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Credit agreement cancelled"));
    }

    // ========================================================================
    // QUERY OPERATIONS
    // ========================================================================

    @GetMapping
    @Operation(summary = "Get all agreements (admin)")
    public ResponseEntity<ApiResponse<List<CreditAgreement>>> getAll() {
        List<CreditAgreement> agreements = agreementService.getAll();
        return ResponseEntity.ok(ApiResponse.success(agreements));
    }

    @GetMapping("/corporate/{corporateId}")
    @Operation(summary = "Get all agreements for a corporate")
    public ResponseEntity<ApiResponse<List<CreditAgreement>>> getByCorporate(
            @PathVariable UUID corporateId) {
        List<CreditAgreement> agreements = agreementService.getByCorporate(corporateId);
        return ResponseEntity.ok(ApiResponse.success(agreements));
    }

    @GetMapping("/corporate/{corporateId}/active")
    @Operation(summary = "Get active agreements for a corporate")
    public ResponseEntity<ApiResponse<List<CreditAgreement>>> getActiveByCorporate(
            @PathVariable UUID corporateId) {
        List<CreditAgreement> agreements = agreementService.getActiveByCorporate(corporateId);
        return ResponseEntity.ok(ApiResponse.success(agreements));
    }

    @GetMapping("/expiring")
    @Operation(summary = "Get all agreements expiring within N days")
    public ResponseEntity<ApiResponse<List<CreditAgreement>>> getExpiring(
            @RequestParam(defaultValue = "30") int days) {
        List<CreditAgreement> agreements = agreementService.getExpiringWithin(days);
        return ResponseEntity.ok(ApiResponse.success(agreements));
    }

    @GetMapping("/corporate/{corporateId}/expiring")
    @Operation(summary = "Get agreements expiring within N days for a corporate")
    public ResponseEntity<ApiResponse<List<CreditAgreement>>> getExpiringByCorporate(
            @PathVariable UUID corporateId,
            @RequestParam(defaultValue = "30") int days) {
        List<CreditAgreement> agreements = agreementService.getExpiringWithin(corporateId, days);
        return ResponseEntity.ok(ApiResponse.success(agreements));
    }

    @GetMapping("/corporate/{corporateId}/for-review")
    @Operation(summary = "Get agreements due for review")
    public ResponseEntity<ApiResponse<List<CreditAgreement>>> getForReview(
            @PathVariable UUID corporateId) {
        List<CreditAgreement> agreements = agreementService.getAgreementsForReview(corporateId);
        return ResponseEntity.ok(ApiResponse.success(agreements));
    }

    // ========================================================================
    // UTILIZATION OPERATIONS
    // ========================================================================

    @PostMapping("/{id}/utilize")
    @Operation(summary = "Utilize (draw down) from agreement")
    public ResponseEntity<ApiResponse<CreditAgreement>> utilize(
            @PathVariable UUID id,
            @RequestParam BigDecimal amount) {
        CreditAgreement agreement = agreementService.utilize(id, amount);
        return ResponseEntity.ok(ApiResponse.success(agreement, "Amount utilized from agreement"));
    }

    @PostMapping("/{id}/release")
    @Operation(summary = "Release utilization back to agreement")
    public ResponseEntity<ApiResponse<CreditAgreement>> release(
            @PathVariable UUID id,
            @RequestParam BigDecimal amount) {
        CreditAgreement agreement = agreementService.release(id, amount);
        return ResponseEntity.ok(ApiResponse.success(agreement, "Amount released to agreement"));
    }

    // ========================================================================
    // STATUS OPERATIONS
    // ========================================================================

    @PostMapping("/{id}/activate")
    @Operation(summary = "Activate an agreement")
    public ResponseEntity<ApiResponse<CreditAgreement>> activate(@PathVariable UUID id) {
        CreditAgreement agreement = agreementService.activate(id);
        return ResponseEntity.ok(ApiResponse.success(agreement, "Agreement activated"));
    }

    @PostMapping("/{id}/suspend")
    @Operation(summary = "Suspend an agreement")
    public ResponseEntity<ApiResponse<CreditAgreement>> suspend(@PathVariable UUID id) {
        CreditAgreement agreement = agreementService.suspend(id);
        return ResponseEntity.ok(ApiResponse.success(agreement, "Agreement suspended"));
    }

    @PostMapping("/{id}/expire")
    @Operation(summary = "Mark agreement as expired")
    public ResponseEntity<ApiResponse<CreditAgreement>> expire(@PathVariable UUID id) {
        CreditAgreement agreement = agreementService.expire(id);
        return ResponseEntity.ok(ApiResponse.success(agreement, "Agreement marked as expired"));
    }

    // ========================================================================
    // CBS SYNC OPERATIONS
    // ========================================================================

    @PostMapping("/sync")
    @Operation(summary = "Sync agreement from CBS")
    public ResponseEntity<ApiResponse<CreditAgreement>> syncFromCbs(
            @RequestParam String externalReference,
            @RequestBody Map<String, Object> cbsData) {
        CreditAgreement agreement = agreementService.syncFromCbs(externalReference, cbsData);
        return ResponseEntity.ok(ApiResponse.success(agreement, "Agreement synced from CBS"));
    }

    @GetMapping("/needing-sync")
    @Operation(summary = "Get agreements needing sync")
    public ResponseEntity<ApiResponse<List<CreditAgreement>>> getAgreementsNeedingSync(
            @RequestParam(defaultValue = "24") int hours) {
        List<CreditAgreement> agreements = agreementService.getAgreementsNeedingSync(hours);
        return ResponseEntity.ok(ApiResponse.success(agreements));
    }

    // ========================================================================
    // SUMMARY OPERATIONS
    // ========================================================================

    @GetMapping("/corporate/{corporateId}/total-limit")
    @Operation(summary = "Get total credit limit for corporate")
    public ResponseEntity<ApiResponse<BigDecimal>> getTotalLimit(@PathVariable UUID corporateId) {
        BigDecimal total = agreementService.getTotalCreditLimit(corporateId);
        return ResponseEntity.ok(ApiResponse.success(total));
    }

    @GetMapping("/corporate/{corporateId}/total-utilized")
    @Operation(summary = "Get total utilized amount for corporate")
    public ResponseEntity<ApiResponse<BigDecimal>> getTotalUtilized(@PathVariable UUID corporateId) {
        BigDecimal total = agreementService.getTotalUtilized(corporateId);
        return ResponseEntity.ok(ApiResponse.success(total));
    }

    @GetMapping("/corporate/{corporateId}/summary")
    @Operation(summary = "Get utilization summary for corporate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getUtilizationSummary(
            @PathVariable UUID corporateId) {
        Map<String, Object> summary = agreementService.getUtilizationSummary(corporateId);
        return ResponseEntity.ok(ApiResponse.success(summary));
    }

    @GetMapping("/corporate/{corporateId}/count")
    @Operation(summary = "Get active agreements count for corporate")
    public ResponseEntity<ApiResponse<Long>> getActiveCount(@PathVariable UUID corporateId) {
        long count = agreementService.getActiveCount(corporateId);
        return ResponseEntity.ok(ApiResponse.success(count));
    }
}