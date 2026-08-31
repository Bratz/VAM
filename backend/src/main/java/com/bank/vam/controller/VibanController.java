package com.bank.vam.controller;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.dto.viban.VibanDto.*;
import com.bank.vam.entity.Program;
import com.bank.vam.entity.viban.Viban;
import com.bank.vam.entity.viban.VibanPool;
import com.bank.vam.repository.ProgramRepository;
import com.bank.vam.repository.viban.VibanPoolRepository;
import com.bank.vam.repository.viban.VibanRepository;
import com.bank.vam.service.viban.VibanRoutingService;
import com.bank.vam.service.viban.VibanRoutingService.RoutingRequest;
import com.bank.vam.service.viban.VibanRoutingService.RoutingResult;
import com.bank.vam.service.viban.VibanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST Controller for VIBAN management.
 * Provides endpoints for VIBAN CRUD, lookup, pool management, and routing.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "VIBAN", description = "Virtual IBAN management APIs")
public class VibanController {

    private final VibanService vibanService;
    private final VibanRoutingService vibanRoutingService;
    private final VibanRepository vibanRepository;
    private final VibanPoolRepository vibanPoolRepository;
    private final ProgramRepository programRepository;
    private final com.bank.vam.repository.VirtualAccountRepository virtualAccountRepository;

    // ========================================================================
    // VIBAN List & Stats - NEW ENDPOINTS FOR FRONTEND
    // ========================================================================

    @GetMapping("/vibans")
    @Operation(summary = "Get all VIBANs", description = "Get paginated list of all VIBANs with optional filters")
    public ResponseEntity<ApiResponse<List<VibanResponse>>> getAllVibans(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(required = false) String corporateId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID poolId,
            @RequestParam(required = false) UUID programId) {
        
        log.info("Getting VIBANs - page: {}, size: {}, status: {}, poolId: {}", page, size, status, poolId);
        
        try {
            PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
            
            Page<Viban> vibanPage;
            if (poolId != null) {
                vibanPage = vibanRepository.findByPoolId(poolId, pageRequest);
            } else if (status != null && !status.isEmpty()) {
                vibanPage = vibanRepository.findByStatus(status, pageRequest);
            } else if (programId != null) {
                vibanPage = vibanRepository.findByProgramId(programId, pageRequest);
            } else {
                vibanPage = vibanRepository.findAll(pageRequest);
            }
            
            List<VibanResponse> responses = vibanPage.getContent().stream()
                .map(this::toVibanResponse)
                .collect(Collectors.toList());
            
            return ResponseEntity.ok(ApiResponse.success(responses));
        } catch (Exception e) {
            log.error("Error getting VIBANs: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("Failed to get VIBANs: " + e.getMessage()));
        }
    }

    @GetMapping("/vibans/stats")
    @Operation(summary = "Get VIBAN statistics", description = "Get overall VIBAN and pool statistics")
    public ResponseEntity<ApiResponse<VibanStatsResponse>> getVibanStats() {
        log.info("Getting VIBAN statistics");
        
        try {
            // Count VIBANs by status
            long total = vibanRepository.count();
            long assigned = vibanRepository.countByStatus(Viban.STATUS_ACTIVE);
            long available = vibanRepository.countByStatus(Viban.STATUS_RETURNED); // RETURNED = available in pool
            long reserved = vibanRepository.countByStatus(Viban.STATUS_PARTIAL);   // PARTIAL = reserved/in-progress
            long expired = vibanRepository.countByStatus(Viban.STATUS_EXPIRED);
            
            // Pool statistics
            long totalPools = vibanPoolRepository.count();
            long activePools = vibanPoolRepository.countByStatus(VibanPool.STATUS_ACTIVE);
            
            // Count pools below low threshold
            List<VibanPool> allPools = vibanPoolRepository.findAll();
            long lowThresholdPools = allPools.stream()
                .filter(p -> p.getStatus().equals(VibanPool.STATUS_ACTIVE))
                .filter(p -> {
                    double utilization = (double) p.getAvailableCount() / p.getPoolSize() * 100;
                    return utilization <= p.getLowThresholdPercent();
                })
                .count();
            
            // Payment routing stats (simplified - would need transaction repo in production)
            long totalPaymentsRouted = vibanRepository.sumTimesUsed();
            BigDecimal totalAmountRouted = vibanRepository.sumTotalAmountReceived();
            
            VibanStatsResponse stats = VibanStatsResponse.builder()
                .total((int) total)
                .assigned((int) assigned)
                .available((int) available)
                .reserved((int) reserved)
                .expired((int) expired)
                .totalPools((int) totalPools)
                .activePools((int) activePools)
                .lowThresholdPools((int) lowThresholdPools)
                .totalPaymentsRouted((int) totalPaymentsRouted)
                .totalAmountRouted(totalAmountRouted != null ? totalAmountRouted : BigDecimal.ZERO)
                .build();
            
            return ResponseEntity.ok(ApiResponse.success(stats));
        } catch (Exception e) {
            log.error("Error getting VIBAN stats: {}", e.getMessage(), e);
            // Return empty stats on error
            return ResponseEntity.ok(ApiResponse.success(VibanStatsResponse.builder()
                .total(0).assigned(0).available(0).reserved(0).expired(0)
                .totalPools(0).activePools(0).lowThresholdPools(0)
                .totalPaymentsRouted(0).totalAmountRouted(BigDecimal.ZERO)
                .build()));
        }
    }

    // ========================================================================
    // VIBAN Pool Management - NEW ENDPOINTS FOR FRONTEND
    // ========================================================================

    @GetMapping("/viban-pools")
    @Operation(summary = "Get all VIBAN pools", description = "Get all VIBAN pools across all programs")
    public ResponseEntity<ApiResponse<List<PoolResponse>>> getAllPools(
            @RequestParam(required = false) String status) {
        log.info("Getting all VIBAN pools, status filter: {}", status);
        
        try {
            List<VibanPool> pools;
            if (status != null && !status.isEmpty()) {
                pools = vibanPoolRepository.findByStatus(status);
            } else {
                pools = vibanPoolRepository.findAll();
            }
            
            List<PoolResponse> responses = pools.stream()
                .map(this::toPoolResponse)
                .collect(Collectors.toList());
            
            return ResponseEntity.ok(ApiResponse.success(responses));
        } catch (Exception e) {
            log.error("Error getting pools: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("Failed to get pools: " + e.getMessage()));
        }
    }

    @PutMapping("/viban-pools/{poolId}")
    @Operation(summary = "Update VIBAN pool", description = "Update an existing VIBAN pool configuration")
    public ResponseEntity<ApiResponse<PoolResponse>> updatePool(
            @PathVariable UUID poolId,
            @RequestBody PoolUpdateRequest request) {
        log.info("Updating VIBAN pool {}", poolId);
        
        try {
            VibanPool pool = vibanPoolRepository.findById(poolId)
                .orElseThrow(() -> new RuntimeException("Pool not found: " + poolId));
            
            // Update fields
            if (request.getPoolName() != null) pool.setPoolName(request.getPoolName());
            if (request.getDescription() != null) pool.setDescription(request.getDescription());
            if (request.getAssignmentTtlMinutes() != null) pool.setAssignmentTtlMinutes(request.getAssignmentTtlMinutes());
            if (request.getAutoReturnExpired() != null) pool.setAutoReturnExpired(request.getAutoReturnExpired());
            if (request.getLowThresholdPercent() != null) pool.setLowThresholdPercent(request.getLowThresholdPercent());
            if (request.getStatus() != null) pool.setStatus(request.getStatus());
            
            VibanPool saved = vibanPoolRepository.save(pool);
            
            return ResponseEntity.ok(ApiResponse.success(toPoolResponse(saved)));
        } catch (Exception e) {
            log.error("Error updating pool: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("Failed to update pool: " + e.getMessage()));
        }
    }

    @DeleteMapping("/viban-pools/{poolId}")
    @Operation(summary = "Delete VIBAN pool", description = "Delete a VIBAN pool (only if no assigned VIBANs)")
    public ResponseEntity<ApiResponse<Void>> deletePool(@PathVariable UUID poolId) {
        log.info("Deleting VIBAN pool {}", poolId);
        
        try {
            VibanPool pool = vibanPoolRepository.findById(poolId)
                .orElseThrow(() -> new RuntimeException("Pool not found: " + poolId));
            
            // Check if any VIBANs are assigned
            if (pool.getAssignedCount() > 0) {
                return ResponseEntity.ok(ApiResponse.error("Cannot delete pool with assigned VIBANs"));
            }
            
            // Delete all VIBANs in the pool first
            vibanRepository.deleteByPoolId(poolId);
            
            // Delete the pool
            vibanPoolRepository.delete(pool);
            
            return ResponseEntity.ok(ApiResponse.success(null));
        } catch (Exception e) {
            log.error("Error deleting pool: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("Failed to delete pool: " + e.getMessage()));
        }
    }

    @PostMapping("/viban-pools/{poolId}/generate")
    @Operation(summary = "Generate VIBANs for pool", description = "Generate additional VIBANs for a pool")
    public ResponseEntity<ApiResponse<Map<String, Object>>> generateVibansForPool(
            @PathVariable UUID poolId,
            @RequestBody GenerateVibansRequest request) {
        log.info("Generating {} VIBANs for pool {}", request.getCount(), poolId);
        
        try {
            VibanPool pool = vibanPoolRepository.findById(poolId)
                .orElseThrow(() -> new RuntimeException("Pool not found: " + poolId));
            
            int count = request.getCount() != null ? request.getCount() : 100;
            int generated = 0;
            
            for (int i = 0; i < count; i++) {
                try {
                    String vibanString = generateVibanForPool(pool, pool.getPoolSize() + i + 1);
                    
                    Viban viban = Viban.builder()
                        .viban(vibanString)
                        .programId(pool.getProgramId())
                        .poolId(pool.getId())
                        .status(Viban.STATUS_RETURNED)  // RETURNED = available in pool
                        .vibanType(Viban.VibanType.TEMPORARY)  // TEMPORARY for pool VIBANs
                        .build();
                    
                    vibanRepository.save(viban);
                    generated++;
                } catch (Exception e) {
                    log.warn("Failed to generate VIBAN {}: {}", i, e.getMessage());
                }
            }
            
            // Update pool size and available count
            pool.setPoolSize(pool.getPoolSize() + generated);
            pool.setAvailableCount(pool.getAvailableCount() + generated);
            vibanPoolRepository.save(pool);
            
            Map<String, Object> result = new HashMap<>();
            result.put("generated", generated);
            result.put("newPoolSize", pool.getPoolSize());
            result.put("newAvailableCount", pool.getAvailableCount());
            
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            log.error("Error generating VIBANs: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("Failed to generate VIBANs: " + e.getMessage()));
        }
    }

    @PostMapping("/vibans/{viban}/release")
    @Operation(summary = "Release VIBAN", description = "Release a VIBAN back to available status")
    public ResponseEntity<ApiResponse<Void>> releaseViban(@PathVariable String viban) {
        log.info("Releasing VIBAN {}", viban);
        
        try {
            Viban vibanEntity = vibanRepository.findByViban(viban)
                .orElseThrow(() -> new RuntimeException("VIBAN not found: " + viban));
            
            if (vibanEntity.getPoolId() != null) {
                // Return to pool
                vibanService.returnToPool(vibanEntity.getId());
            } else {
                // Just mark as returned/available
                vibanEntity.setStatus(Viban.STATUS_RETURNED);
                vibanEntity.setVirtualAccountId(null);
                vibanRepository.save(vibanEntity);
            }
            
            return ResponseEntity.ok(ApiResponse.success(null));
        } catch (Exception e) {
            log.error("Error releasing VIBAN: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("Failed to release VIBAN: " + e.getMessage()));
        }
    }

    // ========================================================================
    // VIBAN Lookup - CRITICAL for ROBO (<5ms)
    // ========================================================================

    @GetMapping("/vibans/lookup/{viban}")
    @Operation(summary = "Lookup VIBAN", description = "Lookup VIBAN for routing - must be <5ms")
    public ResponseEntity<VibanLookupResponse> lookupViban(@PathVariable String viban) {
        VibanLookupResponse response = vibanService.lookupViban(viban);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/vibans/{viban}")
    @Operation(summary = "Get VIBAN by string", description = "Get VIBAN details by VIBAN string")
    public ResponseEntity<VibanLookupResponse> getVibanByString(@PathVariable String viban) {
        VibanLookupResponse response = vibanService.lookupViban(viban);
        return ResponseEntity.ok(response);
    }

    // ========================================================================
    // VIBAN CRUD
    // ========================================================================

    @PostMapping("/programs/{programId}/vibans")
    @Operation(summary = "Create VIBAN", description = "Create a new VIBAN for a virtual account")
    public ResponseEntity<VibanResponse> createViban(
            @PathVariable UUID programId,
            @RequestBody VibanCreateRequest request) {
        log.info("Creating VIBAN for program {} VA {}", programId, request.getVirtualAccountId());
        VibanResponse response = vibanService.createViban(programId, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/vibans/id/{vibanId}")
    @Operation(summary = "Get VIBAN by ID", description = "Get VIBAN details by ID")
    public ResponseEntity<VibanResponse> getVibanById(@PathVariable UUID vibanId) {
        VibanResponse response = vibanService.getViban(vibanId);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/vibans/{vibanId}/status")
    @Operation(summary = "Update VIBAN status", description = "Update the status of a VIBAN")
    public ResponseEntity<Void> updateVibanStatus(
            @PathVariable UUID vibanId,
            @RequestParam String status) {
        log.info("Updating VIBAN {} status to {}", vibanId, status);
        vibanService.updateStatus(vibanId, status);
        return ResponseEntity.ok().build();
    }

    // ========================================================================
    // Virtual Account VIBANs
    // ========================================================================

    @GetMapping("/virtual-accounts/{vaId}/vibans")
    @Operation(summary = "Get VIBANs for VA", description = "Get all VIBANs for a virtual account")
    public ResponseEntity<List<VibanResponse>> getVibansForVa(@PathVariable UUID vaId) {
        List<VibanResponse> vibans = vibanService.getVibansForVa(vaId);
        return ResponseEntity.ok(vibans);
    }

    @PostMapping("/virtual-accounts/{vaId}/vibans")
    @Operation(summary = "Create VIBAN for VA", description = "Create a new VIBAN for a specific virtual account")
    public ResponseEntity<VibanResponse> createVibanForVa(
            @PathVariable UUID vaId,
            @RequestBody VibanCreateRequest request) {
        request.setVirtualAccountId(vaId);
        VibanResponse response = vibanService.createViban(request.getVirtualAccountId(), request);
        return ResponseEntity.ok(response);
    }

    // ========================================================================
    // Invoice/Order VIBANs (for auto-reconciliation)
    // ========================================================================

    @PostMapping("/programs/{programId}/vibans/invoice")
    @Operation(summary = "Create Invoice VIBAN", description = "Create a VIBAN linked to an invoice for auto-reconciliation")
    public ResponseEntity<VibanResponse> createInvoiceViban(
            @PathVariable UUID programId,
            @RequestParam UUID virtualAccountId,
            @RequestParam String invoiceId,
            @RequestParam BigDecimal amount,
            @RequestParam(required = false) String validUntil) {
        log.info("Creating invoice VIBAN for invoice {}", invoiceId);
        VibanResponse response = vibanService.createInvoiceViban(
            programId, virtualAccountId, invoiceId, amount,
            validUntil != null ? java.time.LocalDateTime.parse(validUntil) : null);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/programs/{programId}/vibans/order")
    @Operation(summary = "Create Order VIBAN", description = "Create a VIBAN linked to an e-commerce order")
    public ResponseEntity<VibanResponse> createOrderViban(
            @PathVariable UUID programId,
            @RequestParam UUID virtualAccountId,
            @RequestParam String orderId,
            @RequestParam BigDecimal amount) {
        log.info("Creating order VIBAN for order {}", orderId);
        VibanResponse response = vibanService.createOrderViban(
            programId, virtualAccountId, orderId, amount);
        return ResponseEntity.ok(response);
    }

    // ========================================================================
    // VIBAN Pools
    // ========================================================================

    @PostMapping("/programs/{programId}/viban-pools")
    @Operation(summary = "Create VIBAN pool", description = "Create a new VIBAN pool for the program")
    public ResponseEntity<ApiResponse<PoolResponse>> createPool(
            @PathVariable UUID programId,
            @RequestBody PoolCreateRequest request) {
        log.info("Creating VIBAN pool {} for program {}", request.getPoolCode(), programId);
        try {
            PoolResponse response = vibanService.createPool(programId, request);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            log.error("Error creating pool: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("Failed to create pool: " + e.getMessage()));
        }
    }

    @GetMapping("/programs/{programId}/viban-pools")
    @Operation(summary = "Get VIBAN pools", description = "Get all VIBAN pools for a program")
    public ResponseEntity<ApiResponse<List<PoolResponse>>> getPools(@PathVariable UUID programId) {
        try {
            List<PoolResponse> pools = vibanService.getPoolsForProgram(programId);
            return ResponseEntity.ok(ApiResponse.success(pools));
        } catch (Exception e) {
            log.error("Error getting pools for program {}: {}", programId, e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("Failed to get pools: " + e.getMessage()));
        }
    }

    @GetMapping("/viban-pools/{poolId}")
    @Operation(summary = "Get VIBAN pool", description = "Get details of a specific VIBAN pool")
    public ResponseEntity<PoolResponse> getPool(@PathVariable UUID poolId) {
        PoolResponse pool = vibanService.getPool(poolId);
        return ResponseEntity.ok(pool);
    }

    @PostMapping("/viban-pools/{poolId}/assign")
    @Operation(summary = "Assign from pool", description = "Assign a VIBAN from the pool to a virtual account")
    public ResponseEntity<PoolAssignResponse> assignFromPool(
            @PathVariable UUID poolId,
            @RequestBody PoolAssignRequest request) {
        log.info("Assigning VIBAN from pool {} to VA {}", poolId, request.getVirtualAccountId());
        PoolAssignResponse response = vibanService.assignFromPool(poolId, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/viban-pools/{poolId}/return/{vibanId}")
    @Operation(summary = "Return to pool", description = "Return a VIBAN back to the pool")
    public ResponseEntity<Void> returnToPool(
            @PathVariable UUID poolId,
            @PathVariable UUID vibanId) {
        log.info("Returning VIBAN {} to pool {}", vibanId, poolId);
        vibanService.returnToPool(vibanId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/viban-pools/{poolId}/bulk-assign")
    @Operation(summary = "Bulk assign from pool",
        description = "Assign multiple VIBANs from the pool to multiple virtual accounts in one operation")
    public ResponseEntity<BulkAssignResponse> bulkAssignFromPool(
            @PathVariable UUID poolId,
            @RequestBody BulkAssignRequest request) {
        log.info("Bulk assigning {} VIBANs from pool {}",
            request.getAssignments() != null ? request.getAssignments().size() : 0, poolId);
        BulkAssignResponse response = vibanService.bulkAssignFromPool(poolId, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/viban-pools/{poolId}/assign-invoice")
    @Operation(summary = "Assign VIBAN for invoice",
        description = "Assign a VIBAN for an invoice (Accounts Receivable integration). "
            + "Creates a VIBAN tied to a specific invoice for auto-reconciliation.")
    public ResponseEntity<InvoiceVibanResponse> assignVibanForInvoice(
            @PathVariable UUID poolId,
            @RequestBody InvoiceVibanRequest request) {
        log.info("Assigning VIBAN for invoice {} from pool {}", request.getInvoiceNumber(), poolId);
        InvoiceVibanResponse response = vibanService.assignVibanForInvoice(poolId, request);
        return ResponseEntity.ok(response);
    }

    // ========================================================================
    // ROBO Routing
    // ========================================================================

    @PostMapping("/vibans/route")
    @Operation(summary = "Route payment via VIBAN", description = "Route an incoming payment to the correct VA via VIBAN (ROBO)")
    public ResponseEntity<RoutingResult> routePayment(@RequestBody RoutingRequest request) {
        log.info("Routing payment of {} {} via VIBAN {}", 
            request.getAmount(), request.getCurrencyCode(), request.getViban());
        RoutingResult result = vibanRoutingService.routePayment(request);
        
        if (result.isSuccess()) {
            return ResponseEntity.ok(result);
        } else {
            return ResponseEntity.badRequest().body(result);
        }
    }

    @PostMapping("/vibans/{viban}/payment")
    @Operation(summary = "Record payment on VIBAN", description = "Record a payment received on a VIBAN")
    public ResponseEntity<RoutingResult> recordPayment(
            @PathVariable String viban,
            @RequestParam BigDecimal amount,
            @RequestParam String currencyCode,
            @RequestParam(required = false) String senderName,
            @RequestParam(required = false) String senderAccount,
            @RequestParam(required = false) String paymentReference) {
        
        RoutingRequest request = RoutingRequest.builder()
            .viban(viban)
            .amount(amount)
            .currencyCode(currencyCode)
            .senderName(senderName)
            .senderAccount(senderAccount)
            .paymentReference(paymentReference)
            .build();
        
        RoutingResult result = vibanRoutingService.routePayment(request);
        return result.isSuccess() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private VibanResponse toVibanResponse(Viban viban) {
        // Lookup Virtual Account details if assigned
        String vaNumber = null;
        String vaName = null;
        if (viban.getVirtualAccountId() != null) {
            virtualAccountRepository.findById(viban.getVirtualAccountId()).ifPresent(va -> {
                // Can't reassign local vars in lambda, so we use a different approach below
            });
            var vaOpt = virtualAccountRepository.findById(viban.getVirtualAccountId());
            if (vaOpt.isPresent()) {
                vaNumber = vaOpt.get().getVaNumber();
                vaName = vaOpt.get().getVaName();
            }
        }

        // Lookup Pool code if from a pool
        String poolCode = null;
        if (viban.getPoolId() != null) {
            var poolOpt = vibanPoolRepository.findById(viban.getPoolId());
            if (poolOpt.isPresent()) {
                poolCode = poolOpt.get().getPoolCode();
            }
        }

        return VibanResponse.builder()
            .id(viban.getId())
            .viban(viban.getViban())
            .virtualAccountId(viban.getVirtualAccountId())
            .vaNumber(vaNumber)
            .vaName(vaName)
            .programId(viban.getProgramId())
            .poolId(viban.getPoolId())
            .poolCode(poolCode)
            .vibanType(viban.getVibanType())
            .isPrimary(viban.getIsPrimary())
            .referenceType(viban.getReferenceType())
            .referenceId(viban.getReferenceId())
            .status(viban.getStatus())
            .validFrom(viban.getValidFrom())
            .validUntil(viban.getValidUntil())
            .singleUse(viban.getSingleUse())
            .timesUsed(viban.getTimesUsed())
            .totalAmountReceived(viban.getTotalAmountReceived())
            .expectedAmount(viban.getExpectedAmount())
            .remainingAmount(viban.getRemainingAmount())
            .currencyCode(viban.getCurrencyCode())
            .customerName(viban.getCustomerName())
            .purpose(viban.getPurpose())
            .createdAt(viban.getCreatedAt())
            .build();
    }

    private PoolResponse toPoolResponse(VibanPool pool) {
        // Get program name
        String programName = null;
        if (pool.getProgramId() != null) {
            programName = programRepository.findById(pool.getProgramId())
                .map(Program::getProgramName)
                .orElse(null);
        }
        
        // Calculate utilization (as double)
        double utilizationPercent = pool.getPoolSize() > 0 
            ? ((double) (pool.getAssignedCount() + (pool.getReservedCount() != null ? pool.getReservedCount() : 0)) / pool.getPoolSize()) * 100
            : 0.0;
        
        return PoolResponse.builder()
            .id(pool.getId())
            .programId(pool.getProgramId())
            .programName(programName)
            .poolName(pool.getPoolName())
            .poolCode(pool.getPoolCode())
            .description(pool.getDescription())
            .countryCode(pool.getCountryCode())
            .bankCode(pool.getBankCode())
            .prefix(pool.getPrefix())
            .suffixLength(pool.getSuffixLength())
            .poolSize(pool.getPoolSize())
            .availableCount(pool.getAvailableCount())
            .reservedCount(pool.getReservedCount())
            .assignedCount(pool.getAssignedCount())
            .utilizationPercent(utilizationPercent)
            .assignmentTtlMinutes(pool.getAssignmentTtlMinutes())
            .autoReturnExpired(pool.getAutoReturnExpired())
            .lowThresholdPercent(pool.getLowThresholdPercent())
            .status(pool.getStatus())
            .createdAt(pool.getCreatedAt())
            .updatedAt(pool.getUpdatedAt())
            .build();
    }

    private String generateVibanForPool(VibanPool pool, int index) {
        String countryCode = pool.getCountryCode();
        String bankCode = pool.getBankCode();
        String prefix = pool.getPrefix();
        
        String accountNumber = prefix + String.format("%010d", index);
        String checkDigits = calculateCheckDigits(countryCode, bankCode, accountNumber);
        
        return countryCode + checkDigits + bankCode + accountNumber;
    }

    private String calculateCheckDigits(String countryCode, String bankCode, String accountNumber) {
        String bban = bankCode + accountNumber;
        String rearranged = bban + countryCode + "00";
        
        StringBuilder numeric = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            if (Character.isLetter(c)) {
                numeric.append(Character.toUpperCase(c) - 'A' + 10);
            } else {
                numeric.append(c);
            }
        }
        
        java.math.BigInteger numericValue = new java.math.BigInteger(numeric.toString());
        int remainder = numericValue.mod(java.math.BigInteger.valueOf(97)).intValue();
        int checkDigit = 98 - remainder;
        
        return String.format("%02d", checkDigit);
    }
}