package com.bank.vam.controller.tax;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.dto.tax.ProgramChargeOverrideDto.*;
import com.bank.vam.service.tax.ProgramChargeOverrideService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Program Charge Override Controller - REST API for program-specific fee management.
 * 
 * Endpoints:
 * - CRUD for charge overrides
 * - Wallet fee configuration
 * - Fee calculation
 * 
 * Base URL: /api/v1/program-charges
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/program-charges")
@RequiredArgsConstructor
@Tag(name = "Program Charges", description = "Program-specific charge/fee configuration")
public class ProgramChargeOverrideController {

    private final ProgramChargeOverrideService overrideService;

    // ========================================================================
    // CRUD OPERATIONS
    // ========================================================================

    @PostMapping("/overrides")
    @Operation(summary = "Create charge override", description = "Create a program-specific charge override")
    public ResponseEntity<ApiResponse<OverrideResponse>> createOverride(
            @RequestBody CreateOverrideRequest request) {
        log.info("Creating charge override for program {} charge {}", 
            request.getProgramId(), request.getChargeCode());
        OverrideResponse response = overrideService.createOverride(request);
        return ResponseEntity.ok(ApiResponse.success(response, "Charge override created"));
    }

    @PutMapping("/overrides/{overrideId}")
    @Operation(summary = "Update charge override", description = "Update an existing charge override")
    public ResponseEntity<ApiResponse<OverrideResponse>> updateOverride(
            @PathVariable UUID overrideId,
            @RequestBody UpdateOverrideRequest request) {
        log.info("Updating charge override {}", overrideId);
        OverrideResponse response = overrideService.updateOverride(overrideId, request);
        return ResponseEntity.ok(ApiResponse.success(response, "Charge override updated"));
    }

    @DeleteMapping("/overrides/{overrideId}")
    @Operation(summary = "Delete charge override", description = "Delete a charge override")
    public ResponseEntity<ApiResponse<Void>> deleteOverride(@PathVariable UUID overrideId) {
        log.info("Deleting charge override {}", overrideId);
        overrideService.deleteOverride(overrideId);
        return ResponseEntity.ok(ApiResponse.success(null, "Charge override deleted"));
    }

    @GetMapping("/overrides/{overrideId}")
    @Operation(summary = "Get charge override", description = "Get details of a charge override")
    public ResponseEntity<ApiResponse<OverrideResponse>> getOverride(@PathVariable UUID overrideId) {
        OverrideResponse response = overrideService.getOverride(overrideId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ========================================================================
    // PROGRAM CHARGES VIEW
    // ========================================================================

    @GetMapping("/programs/{programId}")
    @Operation(summary = "Get program charges", description = "Get all charges with overrides for a program")
    public ResponseEntity<ApiResponse<ProgramChargesResponse>> getProgramCharges(
            @PathVariable UUID programId) {
        ProgramChargesResponse response = overrideService.getProgramCharges(programId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ========================================================================
    // WALLET-SPECIFIC ENDPOINTS
    // ========================================================================

    @GetMapping("/programs/{programId}/wallet")
    @Operation(summary = "Get wallet charges", description = "Get wallet-specific charges for a program")
    public ResponseEntity<ApiResponse<WalletChargesResponse>> getWalletCharges(
            @PathVariable UUID programId) {
        WalletChargesResponse response = overrideService.getWalletCharges(programId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PutMapping("/programs/{programId}/wallet")
    @Operation(summary = "Set wallet charges", description = "Configure wallet fees for a program")
    public ResponseEntity<ApiResponse<WalletChargesResponse>> setWalletCharges(
            @PathVariable UUID programId,
            @RequestBody WalletChargesRequest request) {
        request.setProgramId(programId);
        log.info("Setting wallet charges for program {}", programId);
        WalletChargesResponse response = overrideService.setWalletCharges(request);
        return ResponseEntity.ok(ApiResponse.success(response, "Wallet charges updated"));
    }

    @PostMapping("/programs/{programId}/migrate-fees")
    @Operation(summary = "Migrate legacy fees", description = "Migrate wallet fees from Program to ChargeOverrides")
    public ResponseEntity<ApiResponse<WalletChargesResponse>> migrateFees(@PathVariable UUID programId) {
        log.info("Migrating legacy wallet fees for program {}", programId);
        overrideService.migrateFromProgramFees(programId);
        WalletChargesResponse response = overrideService.getWalletCharges(programId);
        return ResponseEntity.ok(ApiResponse.success(response, "Fees migrated successfully"));
    }

    // ========================================================================
    // FEE CALCULATION
    // ========================================================================

    @PostMapping("/calculate")
    @Operation(summary = "Calculate charge", description = "Calculate a specific charge for a program")
    public ResponseEntity<ApiResponse<CalculateChargeResponse>> calculateCharge(
            @RequestBody CalculateChargeRequest request) {
        CalculateChargeResponse response = overrideService.calculateCharge(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/calculate/wallet-fees")
    @Operation(summary = "Calculate wallet fees", description = "Calculate all wallet fees for given amounts")
    public ResponseEntity<ApiResponse<CalculateWalletFeesResponse>> calculateWalletFees(
            @RequestBody CalculateWalletFeesRequest request) {
        CalculateWalletFeesResponse response = overrideService.calculateWalletFees(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ========================================================================
    // ADMIN OPERATIONS
    // ========================================================================

    @PostMapping("/admin/initialize-wallet-configs")
    @Operation(summary = "Initialize wallet configs", description = "Create default wallet charge configurations")
    public ResponseEntity<ApiResponse<String>> initializeWalletConfigs() {
        log.info("Initializing wallet charge configurations");
        overrideService.initializeWalletChargeConfigs();
        return ResponseEntity.ok(ApiResponse.success("Wallet charge configurations initialized"));
    }
}