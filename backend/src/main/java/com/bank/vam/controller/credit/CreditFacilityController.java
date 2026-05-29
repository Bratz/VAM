package com.bank.vam.controller.credit;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.entity.credit.CreditFacility;
import com.bank.vam.entity.credit.CreditFacility.FacilityType;
import com.bank.vam.service.credit.CreditFacilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * REST Controller for Credit Facility management.
 * 
 * UNIFIED ARCHITECTURE v4.2:
 * Credit Facilities are specific credit lines under master agreements.
 * They link to physical accounts for overdraft facilities.
 * 
 * Base Path: /api/v1/credit-facilities
 */
@RestController
@RequestMapping("/api/v1/credit-facilities")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Credit Facilities", description = "Credit facility management")
public class CreditFacilityController {

    private final CreditFacilityService facilityService;

    // ========================================================================
    // CRUD OPERATIONS
    // ========================================================================

    @PostMapping
    @Operation(summary = "Create a new credit facility")
    public ResponseEntity<ApiResponse<CreditFacility>> create(@RequestBody CreditFacility facility) {
        log.info("Creating credit facility: {}", facility.getFacilityName());
        CreditFacility created = facilityService.create(facility);
        return ResponseEntity.ok(ApiResponse.success(created, "Credit facility created"));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get credit facility by ID")
    public ResponseEntity<ApiResponse<CreditFacility>> getById(@PathVariable UUID id) {
        CreditFacility facility = facilityService.getById(id);
        return ResponseEntity.ok(ApiResponse.success(facility));
    }

    @GetMapping("/external/{externalReference}")
    @Operation(summary = "Get credit facility by external reference")
    public ResponseEntity<ApiResponse<CreditFacility>> getByExternalReference(
            @PathVariable String externalReference) {
        CreditFacility facility = facilityService.getByExternalReference(externalReference);
        return ResponseEntity.ok(ApiResponse.success(facility));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update credit facility")
    public ResponseEntity<ApiResponse<CreditFacility>> update(
            @PathVariable UUID id,
            @RequestBody CreditFacility updates) {
        CreditFacility updated = facilityService.update(id, updates);
        return ResponseEntity.ok(ApiResponse.success(updated, "Credit facility updated"));
    }

    // ========================================================================
    // QUERY OPERATIONS
    // ========================================================================

    @GetMapping
    @Operation(summary = "Get all facilities (admin)")
    public ResponseEntity<ApiResponse<List<CreditFacility>>> getAll() {
        List<CreditFacility> facilities = facilityService.getAll();
        return ResponseEntity.ok(ApiResponse.success(facilities));
    }

    @GetMapping("/agreement/{agreementId}")
    @Operation(summary = "Get all facilities for an agreement")
    public ResponseEntity<ApiResponse<List<CreditFacility>>> getByAgreement(
            @PathVariable UUID agreementId) {
        List<CreditFacility> facilities = facilityService.getByAgreement(agreementId);
        return ResponseEntity.ok(ApiResponse.success(facilities));
    }

    @GetMapping("/agreement/{agreementId}/active")
    @Operation(summary = "Get active facilities for an agreement")
    public ResponseEntity<ApiResponse<List<CreditFacility>>> getActiveByAgreement(
            @PathVariable UUID agreementId) {
        List<CreditFacility> facilities = facilityService.getActiveByAgreement(agreementId);
        return ResponseEntity.ok(ApiResponse.success(facilities));
    }

    @GetMapping("/corporate/{corporateId}")
    @Operation(summary = "Get all facilities for a corporate")
    public ResponseEntity<ApiResponse<List<CreditFacility>>> getByCorporate(
            @PathVariable UUID corporateId) {
        List<CreditFacility> facilities = facilityService.getByCorporate(corporateId);
        return ResponseEntity.ok(ApiResponse.success(facilities));
    }

    @GetMapping("/corporate/{corporateId}/active")
    @Operation(summary = "Get active facilities for a corporate")
    public ResponseEntity<ApiResponse<List<CreditFacility>>> getActiveByCorporate(
            @PathVariable UUID corporateId) {
        List<CreditFacility> facilities = facilityService.getActiveByCorporate(corporateId);
        return ResponseEntity.ok(ApiResponse.success(facilities));
    }

    @GetMapping("/corporate/{corporateId}/type/{type}")
    @Operation(summary = "Get facilities by type for a corporate")
    public ResponseEntity<ApiResponse<List<CreditFacility>>> getByType(
            @PathVariable UUID corporateId,
            @PathVariable FacilityType type) {
        List<CreditFacility> facilities = facilityService.getByType(corporateId, type);
        return ResponseEntity.ok(ApiResponse.success(facilities));
    }

    @GetMapping("/corporate/{corporateId}/overdrafts")
    @Operation(summary = "Get overdraft facilities for a corporate")
    public ResponseEntity<ApiResponse<List<CreditFacility>>> getOverdrafts(
            @PathVariable UUID corporateId) {
        List<CreditFacility> facilities = facilityService.getOverdrafts(corporateId);
        return ResponseEntity.ok(ApiResponse.success(facilities));
    }

    @GetMapping("/physical-account/{physicalAccountId}")
    @Operation(summary = "Get facility linked to physical account")
    public ResponseEntity<ApiResponse<CreditFacility>> getByPhysicalAccount(
            @PathVariable UUID physicalAccountId) {
        Optional<CreditFacility> facility = facilityService.getByPhysicalAccount(physicalAccountId);
        return facility.map(f -> ResponseEntity.ok(ApiResponse.success(f)))
            .orElse(ResponseEntity.ok(ApiResponse.success(null)));
    }

    @GetMapping("/expiring")
    @Operation(summary = "Get facilities expiring within N days")
    public ResponseEntity<ApiResponse<List<CreditFacility>>> getExpiring(
            @RequestParam(defaultValue = "30") int days) {
        List<CreditFacility> facilities = facilityService.getExpiringWithin(days);
        return ResponseEntity.ok(ApiResponse.success(facilities));
    }

    @GetMapping("/corporate/{corporateId}/high-utilization")
    @Operation(summary = "Get high utilization facilities for a corporate")
    public ResponseEntity<ApiResponse<List<CreditFacility>>> getHighUtilization(
            @PathVariable UUID corporateId) {
        List<CreditFacility> facilities = facilityService.getHighUtilization(corporateId);
        return ResponseEntity.ok(ApiResponse.success(facilities));
    }

    @GetMapping("/agreement/{agreementId}/summary")
    @Operation(summary = "Get facility summary for agreement")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getFacilitySummary(
            @PathVariable UUID agreementId) {
        Map<String, Object> summary = facilityService.getFacilitySummary(agreementId);
        return ResponseEntity.ok(ApiResponse.success(summary));
    }

    @GetMapping("/corporate/{corporateId}/types")
    @Operation(summary = "Get distinct facility types for a corporate")
    public ResponseEntity<ApiResponse<List<FacilityType>>> getDistinctTypes(
            @PathVariable UUID corporateId) {
        List<FacilityType> types = facilityService.getDistinctTypes(corporateId);
        return ResponseEntity.ok(ApiResponse.success(types));
    }

    @GetMapping("/agreement/{agreementId}/count")
    @Operation(summary = "Get active facility count for agreement")
    public ResponseEntity<ApiResponse<Long>> getActiveCount(@PathVariable UUID agreementId) {
        long count = facilityService.getActiveCount(agreementId);
        return ResponseEntity.ok(ApiResponse.success(count));
    }

    // ========================================================================
    // UTILIZATION OPERATIONS
    // ========================================================================

    @PostMapping("/{id}/draw-down")
    @Operation(summary = "Draw down from facility")
    public ResponseEntity<ApiResponse<CreditFacility>> drawDown(
            @PathVariable UUID id,
            @RequestParam BigDecimal amount) {
        CreditFacility facility = facilityService.drawDown(id, amount);
        return ResponseEntity.ok(ApiResponse.success(facility, "Amount drawn from facility"));
    }

    @PostMapping("/{id}/repay")
    @Operation(summary = "Repay to facility")
    public ResponseEntity<ApiResponse<CreditFacility>> repay(
            @PathVariable UUID id,
            @RequestParam BigDecimal amount) {
        CreditFacility facility = facilityService.repay(id, amount);
        return ResponseEntity.ok(ApiResponse.success(facility, "Amount repaid to facility"));
    }

    @PutMapping("/{id}/drawing-power")
    @Operation(summary = "Update drawing power for working capital facility")
    public ResponseEntity<ApiResponse<CreditFacility>> updateDrawingPower(
            @PathVariable UUID id,
            @RequestParam BigDecimal drawingPower) {
        CreditFacility facility = facilityService.updateDrawingPower(id, drawingPower);
        return ResponseEntity.ok(ApiResponse.success(facility, "Drawing power updated"));
    }

    // ========================================================================
    // LINK OPERATIONS
    // ========================================================================

    @PostMapping("/{id}/link-physical")
    @Operation(summary = "Link facility to physical account")
    public ResponseEntity<ApiResponse<CreditFacility>> linkToPhysicalAccount(
            @PathVariable UUID id,
            @RequestParam UUID physicalAccountId) {
        CreditFacility facility = facilityService.linkToPhysicalAccount(id, physicalAccountId);
        return ResponseEntity.ok(ApiResponse.success(facility, "Facility linked to physical account"));
    }

    // ========================================================================
    // STATUS OPERATIONS
    // ========================================================================

    @PostMapping("/{id}/suspend")
    @Operation(summary = "Suspend facility")
    public ResponseEntity<ApiResponse<CreditFacility>> suspend(@PathVariable UUID id) {
        CreditFacility facility = facilityService.suspend(id);
        return ResponseEntity.ok(ApiResponse.success(facility, "Facility suspended"));
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Activate facility")
    public ResponseEntity<ApiResponse<CreditFacility>> activate(@PathVariable UUID id) {
        CreditFacility facility = facilityService.activate(id);
        return ResponseEntity.ok(ApiResponse.success(facility, "Facility activated"));
    }

    @PostMapping("/{id}/close")
    @Operation(summary = "Close facility")
    public ResponseEntity<ApiResponse<CreditFacility>> close(@PathVariable UUID id) {
        CreditFacility facility = facilityService.close(id);
        return ResponseEntity.ok(ApiResponse.success(facility, "Facility closed"));
    }

    // ========================================================================
    // CBS SYNC OPERATIONS
    // ========================================================================

    @PostMapping("/sync")
    @Operation(summary = "Sync facility from CBS")
    public ResponseEntity<ApiResponse<CreditFacility>> syncFromCbs(
            @RequestParam String externalReference,
            @RequestBody Map<String, Object> cbsData) {
        CreditFacility facility = facilityService.syncFromCbs(externalReference, cbsData);
        return ResponseEntity.ok(ApiResponse.success(facility, "Facility synced from CBS"));
    }
}