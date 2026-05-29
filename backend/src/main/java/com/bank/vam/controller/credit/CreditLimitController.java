package com.bank.vam.controller.credit;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.dto.credit.CreditLimitDto;
import com.bank.vam.dto.credit.CreditLimitDto.*;
import com.bank.vam.entity.credit.CreditFacility;
import com.bank.vam.entity.credit.CreditLimit;
import com.bank.vam.entity.credit.CreditLimit.*;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.service.credit.CreditLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Credit Limit Controller - For Corporate Users.
 * 
 * UNIFIED ARCHITECTURE v5.2.0 - MVC PATTERN:
 * ==========================================
 * 
 * Controller → CreditLimitController (this file)
 * DTOs       → CreditLimitDto.java (dto/credit package)
 * Service    → CreditLimitService.java
 * Repository → CreditLimitRepository.java
 * Entity     → CreditLimit.java
 * 
 * CORPORATE USER PERSPECTIVE:
 * ===========================
 * 
 * TWO TYPES OF LIMITS:
 * 
 * 1. EXTERNAL LIMITS (Bank-Provided) - READ-ONLY
 *    - Set by bank via CBS (BANCS)
 *    - Overdraft, Revolving Credit
 *    - Corporate can VIEW but NOT modify
 * 
 * 2. INTERNAL LIMITS (CFO-Defined) - MANAGEABLE
 *    - Set by corporate treasury/CFO
 *    - Group Limit → Entity Sub-Limits → VA Limits
 *    - CANNOT exceed external limits
 * 
 * v5.2.0 ENHANCEMENTS:
 * ====================
 * - Multi-Currency Group Limits (one per currency)
 * - Multi-Currency Entity Limits (one per currency per entity)
 * - VA-Level Limits (allocated from entity limits)
 * 
 * ENDPOINT ORGANIZATION:
 * ======================
 * /api/v1/credit/limits/corporate/*    - Corporate-level views
 * /api/v1/credit/limits/external/*     - Bank-provided limits (read-only)
 * /api/v1/credit/limits/internal/*     - CFO-defined limits (full CRUD)
 * /api/v1/credit/limits/check/*        - Availability checks
 * /api/v1/credit/limits/dashboard/*    - Dashboard views
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/credit/limits")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "Credit Limits", description = "Credit Limit Management for Corporate Users")
public class CreditLimitController {

    private final CreditLimitService creditLimitService;

    // ========================================================================
    // CORPORATE-LEVEL ENDPOINTS (Called directly by CreditLimitsPage.tsx)
    // ========================================================================

    /**
     * Get all credit limits for a corporate (both external + internal).
     */
    @GetMapping("/corporate/{corporateId}")
    @Operation(summary = "Get all credit limits for corporate",
               description = "Get all credit limits (external + internal) for a corporate")
    public ResponseEntity<ApiResponse<List<CreditLimitDto.Response>>> getAllLimitsByCorporate(
            @PathVariable UUID corporateId) {
        
        log.info("GET /corporate/{} - Fetching all credit limits", corporateId);
        
        try {
            List<CreditLimit> allLimits = creditLimitService.findAllByCorporate(corporateId);
            List<CreditLimitDto.Response> responses = allLimits.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
            
            return ResponseEntity.ok(ApiResponse.success(responses));
        } catch (Exception e) {
            log.error("Error fetching credit limits for corporate {}: {}", corporateId, e.getMessage());
            return ResponseEntity.ok(ApiResponse.success(List.of(), "Error: " + e.getMessage()));
        }
    }

    /**
     * Get group (consolidated) limits for a corporate.
     */
    @GetMapping("/corporate/{corporateId}/group")
    @Operation(summary = "Get group credit limits",
               description = "Get consolidated group-level credit limits for the corporate")
    public ResponseEntity<ApiResponse<List<InternalLimitResponse>>> getGroupLimitsByCorporate(
            @PathVariable UUID corporateId) {
        
        log.info("GET /corporate/{}/group - Fetching group limits", corporateId);
        
        try {
            List<CreditLimit> groupLimits = creditLimitService.getGroupLimits(corporateId);
            List<InternalLimitResponse> responses = groupLimits.stream()
                .map(this::toInternalResponse)
                .collect(Collectors.toList());
            
            return ResponseEntity.ok(ApiResponse.success(responses));
        } catch (Exception e) {
            log.error("Error fetching group limits for corporate {}: {}", corporateId, e.getMessage());
            return ResponseEntity.ok(ApiResponse.success(List.of(), "Error: " + e.getMessage()));
        }
    }

    /**
     * Get internal (CFO-defined) limits for a corporate.
     */
    @GetMapping("/corporate/{corporateId}/internal")
    @Operation(summary = "Get internal credit limits",
               description = "Get all CFO-defined internal limits for a corporate")
    public ResponseEntity<ApiResponse<List<InternalLimitResponse>>> getInternalLimitsByCorporateDirect(
            @PathVariable UUID corporateId) {
        
        log.info("GET /corporate/{}/internal - Fetching internal limits", corporateId);
        
        try {
            List<CreditLimit> internalLimits = creditLimitService.getInternalLimitsByCorporate(corporateId);
            List<InternalLimitResponse> responses = internalLimits.stream()
                .map(this::toInternalResponse)
                .collect(Collectors.toList());
            
            return ResponseEntity.ok(ApiResponse.success(responses));
        } catch (Exception e) {
            log.error("Error fetching internal limits for corporate {}: {}", corporateId, e.getMessage());
            return ResponseEntity.ok(ApiResponse.success(List.of(), "Error: " + e.getMessage()));
        }
    }

    /**
     * Get external (bank-provided) limits for a corporate.
     */
    @GetMapping("/corporate/{corporateId}/external")
    @Operation(summary = "Get external credit limits",
               description = "Get all bank-provided external limits for a corporate (read-only)")
    public ResponseEntity<ApiResponse<List<ExternalLimitResponse>>> getExternalLimitsByCorporateDirect(
            @PathVariable UUID corporateId) {
        
        log.info("GET /corporate/{}/external - Fetching external limits", corporateId);
        
        try {
            List<CreditLimit> externalLimits = creditLimitService.getExternalLimitsByCorporate(corporateId);
            List<ExternalLimitResponse> responses = externalLimits.stream()
                .map(this::toExternalResponse)
                .collect(Collectors.toList());
            
            return ResponseEntity.ok(ApiResponse.success(responses, 
                "Bank-provided limits are read-only. Contact bank to modify."));
        } catch (Exception e) {
            log.error("Error fetching external limits for corporate {}: {}", corporateId, e.getMessage());
            return ResponseEntity.ok(ApiResponse.success(List.of(), "Error: " + e.getMessage()));
        }
    }

    /**
     * Get limit totals for corporate.
     */
    @GetMapping("/corporate/{corporateId}/total")
    @Operation(summary = "Get limit totals",
               description = "Get aggregated limit totals for corporate")
    public ResponseEntity<ApiResponse<CreditLimitService.LimitTotals>> getLimitTotals(
            @PathVariable UUID corporateId) {
        
        log.info("GET /corporate/{}/total", corporateId);
        
        try {
            CreditLimitService.LimitTotals totals = creditLimitService.getLimitTotals(corporateId);
            return ResponseEntity.ok(ApiResponse.success(totals));
        } catch (Exception e) {
            log.error("Error fetching totals for corporate {}: {}", corporateId, e.getMessage());
            return ResponseEntity.ok(ApiResponse.success(null, "Error: " + e.getMessage()));
        }
    }

    /**
     * Get limits at warning level.
     */
    @GetMapping("/corporate/{corporateId}/warnings")
    @Operation(summary = "Get limits at warning level")
    public ResponseEntity<ApiResponse<List<InternalLimitResponse>>> getWarningLimits(
            @PathVariable UUID corporateId) {
        
        try {
            List<CreditLimit> limits = creditLimitService.getLimitsAtWarningLevel(corporateId);
            return ResponseEntity.ok(ApiResponse.success(limits.stream()
                .map(this::toInternalResponse)
                .collect(Collectors.toList())));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.success(List.of(), "Error: " + e.getMessage()));
        }
    }

    /**
     * Get limits at critical level.
     */
    @GetMapping("/corporate/{corporateId}/critical")
    @Operation(summary = "Get limits at critical level")
    public ResponseEntity<ApiResponse<List<InternalLimitResponse>>> getCriticalLimits(
            @PathVariable UUID corporateId) {
        
        try {
            List<CreditLimit> limits = creditLimitService.getLimitsAtCriticalLevel(corporateId);
            return ResponseEntity.ok(ApiResponse.success(limits.stream()
                .map(this::toInternalResponse)
                .collect(Collectors.toList())));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.success(List.of(), "Error: " + e.getMessage()));
        }
    }

    /**
     * Get breached limits.
     */
    @GetMapping("/corporate/{corporateId}/breached")
    @Operation(summary = "Get breached limits")
    public ResponseEntity<ApiResponse<List<InternalLimitResponse>>> getBreachedLimits(
            @PathVariable UUID corporateId) {
        
        try {
            List<CreditLimit> limits = creditLimitService.getBreachedLimits(corporateId);
            return ResponseEntity.ok(ApiResponse.success(limits.stream()
                .map(this::toInternalResponse)
                .collect(Collectors.toList())));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.success(List.of(), "Error: " + e.getMessage()));
        }
    }

    /**
     * Get expiring limits.
     */
    @GetMapping("/corporate/{corporateId}/expiring")
    @Operation(summary = "Get expiring limits")
    public ResponseEntity<ApiResponse<List<InternalLimitResponse>>> getExpiringLimits(
            @PathVariable UUID corporateId,
            @RequestParam(defaultValue = "30") int daysAhead) {
        
        try {
            List<CreditLimit> limits = creditLimitService.getExpiringLimits(corporateId, daysAhead);
            return ResponseEntity.ok(ApiResponse.success(limits.stream()
                .map(this::toInternalResponse)
                .collect(Collectors.toList())));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.success(List.of(), "Error: " + e.getMessage()));
        }
    }

    // ========================================================================
    // TARGET (ENTITY) ENDPOINTS
    // ========================================================================

    /**
     * Get all limits for a specific target (entity).
     */
    @GetMapping("/target/{targetId}")
    @Operation(summary = "Get limits for target")
    public ResponseEntity<ApiResponse<List<CreditLimitDto.Response>>> getLimitsByTarget(
            @PathVariable UUID targetId) {
        
        try {
            List<CreditLimit> limits = creditLimitService.findByTarget(targetId);
            return ResponseEntity.ok(ApiResponse.success(limits.stream()
                .map(this::toResponse)
                .collect(Collectors.toList())));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.success(List.of(), "Error: " + e.getMessage()));
        }
    }

    /**
     * Get limit by ID.
     */
    @GetMapping("/{limitId}")
    @Operation(summary = "Get limit by ID")
    public ResponseEntity<ApiResponse<CreditLimitDto.Response>> getLimitById(
            @PathVariable UUID limitId) {
        
        try {
            CreditLimit limit = creditLimitService.getById(limitId);
            return ResponseEntity.ok(ApiResponse.success(toResponse(limit)));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.success(null, "Limit not found: " + e.getMessage()));
        }
    }

    /**
     * Update limit amount.
     */
    @PutMapping("/{limitId}/amount")
    @Operation(summary = "Update limit amount")
    public ResponseEntity<ApiResponse<CreditLimitDto.Response>> updateLimitAmount(
            @PathVariable UUID limitId,
            @RequestParam @NotNull @Positive BigDecimal newAmount,
            @RequestParam(defaultValue = "SYSTEM") String updatedBy) {
        
        try {
            CreditLimit limit = creditLimitService.updateLimitAmount(limitId, newAmount, updatedBy);
            return ResponseEntity.ok(ApiResponse.success(toResponse(limit), "Limit amount updated"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error("Failed to update: " + e.getMessage()));
        }
    }

    // ========================================================================
    // EXTERNAL LIMITS (READ-ONLY - Bank Provided)
    // ========================================================================

    /**
     * Get external (bank-provided) limits for corporate.
     */
    @GetMapping("/external/corporate/{corporateId}")
    @Operation(summary = "Get external (bank) limits",
               description = "View bank-provided credit limits (Overdraft/Revolving). READ-ONLY.")
    public ResponseEntity<ApiResponse<List<ExternalLimitResponse>>> getExternalLimits(
            @PathVariable UUID corporateId) {
        
        log.info("GET /external/corporate/{} - Fetching bank-provided limits", corporateId);
        
        List<ExternalLimitResponse> limits = creditLimitService.getExternalLimitsByCorporate(corporateId)
            .stream()
            .map(this::toExternalResponse)
            .collect(Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.success(limits, 
            "Bank-provided limits are read-only. Contact bank to modify."));
    }

    /**
     * Get external facilities for an entity.
     */
    @GetMapping("/external/entity/{entityId}/facilities")
    @Operation(summary = "Get external facilities for entity")
    public ResponseEntity<ApiResponse<List<FacilityResponse>>> getExternalFacilities(
            @PathVariable UUID entityId) {
        
        log.info("GET /external/entity/{}/facilities", entityId);
        
        try {
            List<FacilityResponse> facilities = creditLimitService.getExternalFacilitiesForEntity(entityId)
                .stream()
                .map(this::toFacilityResponse)
                .collect(Collectors.toList());
            
            return ResponseEntity.ok(ApiResponse.success(facilities));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.success(List.of(), "Error: " + e.getMessage()));
        }
    }

    /**
     * Get external limit ceiling for an entity.
     */
    @GetMapping("/external/entity/{entityId}/ceiling")
    @Operation(summary = "Get external limit ceiling")
    public ResponseEntity<ApiResponse<CeilingResponse>> getExternalCeiling(
            @PathVariable UUID entityId,
            @RequestParam(required = false) String currency) {
        
        log.info("GET /external/entity/{}/ceiling?currency={}", entityId, currency);
        
        try {
            return creditLimitService.getExternalLimitCeiling(entityId, currency)
                .map(ceiling -> ResponseEntity.ok(ApiResponse.success(
                    CeilingResponse.builder()
                        .entityId(entityId)
                        .currency(currency)
                        .externalCeiling(ceiling)
                        .message("Internal limits cannot exceed this amount")
                        .build()
                )))
                .orElse(ResponseEntity.ok(ApiResponse.success(
                    CeilingResponse.builder()
                        .entityId(entityId)
                        .currency(currency)
                        .externalCeiling(null)
                        .message("No external limit constraint")
                        .build()
                )));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.success(null, "Error: " + e.getMessage()));
        }
    }

    // ========================================================================
    // INTERNAL LIMITS - GROUP (Corporate Can Manage)
    // ========================================================================

    /**
     * Get group limit for a corporate (single currency - backward compatible).
     */
    @GetMapping("/internal/corporate/{corporateId}/group")
    @Operation(summary = "Get group limit",
               description = "Get the corporate-wide internal credit limit for a specific currency")
    public ResponseEntity<ApiResponse<InternalLimitResponse>> getGroupLimit(
            @PathVariable UUID corporateId,
            @RequestParam(required = false) String currency) {
        
        log.info("GET /internal/corporate/{}/group?currency={}", corporateId, currency);
        
        return creditLimitService.getGroupLimit(corporateId, currency)
            .map(limit -> ResponseEntity.ok(ApiResponse.success(toInternalResponse(limit))))
            .orElse(ResponseEntity.ok(ApiResponse.success(null, "No group limit set")));
    }

    /**
     * Create a group-level internal limit.
     */
    @PostMapping("/internal/group")
    @Operation(summary = "Create group limit")
    public ResponseEntity<ApiResponse<InternalLimitResponse>> createGroupLimit(
            @Valid @RequestBody CreateGroupLimitRequest request) {
        
        log.info("POST /internal/group - {} {} for corporate {}", 
                 request.getAmount(), request.getCurrency(), request.getCorporateId());
        
        try {
            CreditLimit limit = creditLimitService.createGroupLimit(
                request.getCorporateId(),
                request.getLimitName(),
                request.getAmount(),
                request.getCurrency(),
                request.getApprovedBy(),
                request.isHardLimit()
            );
            
            return ResponseEntity.ok(ApiResponse.success(toInternalResponse(limit), 
                "Group limit created. You can now allocate to entities."));
        } catch (BusinessException e) {
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            log.error("Error creating group limit: {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.error("Failed to create: " + e.getMessage()));
        }
    }

    /**
     * Update group limit amount.
     */
    @PutMapping("/internal/group/{limitId}/amount")
    @Operation(summary = "Update group limit amount")
    public ResponseEntity<ApiResponse<InternalLimitResponse>> updateGroupLimitAmount(
            @PathVariable UUID limitId,
            @RequestParam @NotNull @Positive BigDecimal newAmount,
            @RequestParam(defaultValue = "SYSTEM") String updatedBy) {
        
        log.info("PUT /internal/group/{}/amount - {}", limitId, newAmount);
        
        try {
            CreditLimit limit = creditLimitService.updateGroupLimitAmount(limitId, newAmount, updatedBy);
            return ResponseEntity.ok(ApiResponse.success(toInternalResponse(limit), "Group limit updated"));
        } catch (BusinessException e) {
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error("Failed to update: " + e.getMessage()));
        }
    }

    // ========================================================================
    // NEW v5.2.0: MULTI-CURRENCY GROUP LIMIT ENDPOINTS
    // ========================================================================

    /**
     * Get ALL group limits for a corporate (multi-currency).
     * Returns one limit per currency.
     */
    @GetMapping("/internal/corporate/{corporateId}/groups")
    @Operation(summary = "Get all group limits (multi-currency)",
               description = "Get all group-level internal limits across all currencies")
    public ResponseEntity<ApiResponse<List<InternalLimitResponse>>> getAllGroupLimits(
            @PathVariable UUID corporateId) {
        
        log.info("GET /internal/corporate/{}/groups - Fetching all group limits", corporateId);
        
        try {
            List<CreditLimit> groupLimits = creditLimitService.getAllGroupLimits(corporateId);
            List<InternalLimitResponse> responses = groupLimits.stream()
                .map(this::toInternalResponse)
                .collect(Collectors.toList());
            
            return ResponseEntity.ok(ApiResponse.success(responses, 
                String.format("Found %d group limits across currencies", responses.size())));
        } catch (Exception e) {
            log.error("Error fetching group limits: {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.success(List.of(), "Error: " + e.getMessage()));
        }
    }

    /**
     * Get group limit for a specific currency.
     */
    @GetMapping("/internal/corporate/{corporateId}/group/{currency}")
    @Operation(summary = "Get group limit by currency")
    public ResponseEntity<ApiResponse<InternalLimitResponse>> getGroupLimitByCurrency(
            @PathVariable UUID corporateId,
            @PathVariable String currency) {
        
        log.info("GET /internal/corporate/{}/group/{}", corporateId, currency);
        
        return creditLimitService.getGroupLimitByCurrency(corporateId, currency)
            .map(limit -> ResponseEntity.ok(ApiResponse.success(toInternalResponse(limit))))
            .orElse(ResponseEntity.ok(ApiResponse.success(null, 
                "No group limit set for currency: " + currency)));
    }

    /**
     * Get currencies that have group limits configured.
     */
    @GetMapping("/internal/corporate/{corporateId}/group-currencies")
    @Operation(summary = "Get currencies with group limits")
    public ResponseEntity<ApiResponse<List<String>>> getGroupLimitCurrencies(
            @PathVariable UUID corporateId) {
        
        List<String> currencies = creditLimitService.getGroupLimitCurrencies(corporateId);
        return ResponseEntity.ok(ApiResponse.success(currencies));
    }

    /**
     * Check if group limit exists for a currency.
     */
    @GetMapping("/internal/corporate/{corporateId}/group/{currency}/exists")
    @Operation(summary = "Check if group limit exists for currency")
    public ResponseEntity<ApiResponse<Boolean>> hasGroupLimitForCurrency(
            @PathVariable UUID corporateId,
            @PathVariable String currency) {
        
        boolean exists = creditLimitService.hasGroupLimitForCurrency(corporateId, currency);
        return ResponseEntity.ok(ApiResponse.success(exists));
    }

    // ========================================================================
    // INTERNAL LIMITS - ENTITY (Corporate Can Manage)
    // ========================================================================

    /**
     * Get internal limits for corporate (group + all entities).
     */
    @GetMapping("/internal/corporate/{corporateId}")
    @Operation(summary = "Get internal limits by corporate")
    public ResponseEntity<ApiResponse<List<InternalLimitResponse>>> getInternalLimits(
            @PathVariable UUID corporateId) {
        
        log.info("GET /internal/corporate/{}", corporateId);
        
        List<InternalLimitResponse> limits = creditLimitService.getInternalLimitsByCorporate(corporateId)
            .stream()
            .map(this::toInternalResponse)
            .collect(Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.success(limits));
    }

    /**
     * Get entity sub-limits under a group limit.
     */
    @GetMapping("/internal/group/{groupLimitId}/entities")
    @Operation(summary = "Get entity sub-limits under a group limit")
    public ResponseEntity<ApiResponse<List<InternalLimitResponse>>> getEntitySubLimits(
            @PathVariable UUID groupLimitId) {
        
        log.info("GET /internal/group/{}/entities", groupLimitId);
        
        try {
            List<CreditLimit> limits = creditLimitService.getEntityLimitsByGroupLimit(groupLimitId);
            List<InternalLimitResponse> responses = limits.stream()
                .map(this::toInternalResponse)
                .collect(Collectors.toList());
            return ResponseEntity.ok(ApiResponse.success(responses));
        } catch (Exception e) {
            // Fallback to old method if new one not available
            List<InternalLimitResponse> limits = creditLimitService.getEntitySubLimits(groupLimitId)
                .stream()
                .map(this::toInternalResponse)
                .collect(Collectors.toList());
            return ResponseEntity.ok(ApiResponse.success(limits));
        }
    }

    /**
     * Get internal limit for a specific entity (single - backward compatible).
     */
    @GetMapping("/internal/entity/{entityId}")
    @Operation(summary = "Get entity internal limit")
    public ResponseEntity<ApiResponse<InternalLimitResponse>> getEntityInternalLimit(
            @PathVariable UUID entityId) {
        
        log.info("GET /internal/entity/{}", entityId);
        
        return creditLimitService.getEntityInternalLimit(entityId)
            .map(limit -> ResponseEntity.ok(ApiResponse.success(toInternalResponse(limit))))
            .orElse(ResponseEntity.ok(ApiResponse.success(null, "No internal limit set for this entity")));
    }

    /**
     * Create an entity sub-limit (original - single currency validation).
     */
    @PostMapping("/internal/entity")
    @Operation(summary = "Create entity sub-limit")
    public ResponseEntity<ApiResponse<InternalLimitResponse>> createEntitySubLimit(
            @Valid @RequestBody CreateEntitySubLimitRequest request) {
        
        log.info("POST /internal/entity - {} {} for entity {}", 
                 request.getAmount(), request.getCurrency(), request.getEntityId());
        
        try {
            CreditLimit limit = creditLimitService.createEntitySubLimit(
                request.getCorporateId(),
                request.getEntityId(),
                request.getLimitName(),
                request.getAmount(),
                request.getCurrency(),
                request.getApprovedBy(),
                request.isHardLimit(),
                request.isRequiresApproval(),
                request.getApprovalThreshold()
            );
            
            return ResponseEntity.ok(ApiResponse.success(toInternalResponse(limit), 
                "Entity limit created and allocated from group limit."));
        } catch (BusinessException e) {
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            log.error("Error creating entity limit: {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.error("Failed to create: " + e.getMessage()));
        }
    }

    /**
     * Update entity sub-limit amount.
     */
    @PutMapping("/internal/entity/{limitId}/amount")
    @Operation(summary = "Update entity sub-limit amount")
    public ResponseEntity<ApiResponse<InternalLimitResponse>> updateEntitySubLimitAmount(
            @PathVariable UUID limitId,
            @RequestParam @NotNull @Positive BigDecimal newAmount,
            @RequestParam(defaultValue = "SYSTEM") String updatedBy) {
        
        log.info("PUT /internal/entity/{}/amount - {}", limitId, newAmount);
        
        try {
            CreditLimit limit = creditLimitService.updateEntitySubLimitAmount(limitId, newAmount, updatedBy);
            return ResponseEntity.ok(ApiResponse.success(toInternalResponse(limit), "Entity limit updated"));
        } catch (BusinessException e) {
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error("Failed to update: " + e.getMessage()));
        }
    }

    /**
     * Update limit control settings.
     */
    @PutMapping("/internal/{limitId}/controls")
    @Operation(summary = "Update limit controls")
    public ResponseEntity<ApiResponse<InternalLimitResponse>> updateLimitControls(
            @PathVariable UUID limitId,
            @Valid @RequestBody UpdateControlsRequest request) {
        
        log.info("PUT /internal/{}/controls", limitId);
        
        try {
            CreditLimit limit = creditLimitService.updateLimitControls(
                limitId, request.isHardLimit(), request.isRequiresApproval(), request.getApprovalThreshold());
            return ResponseEntity.ok(ApiResponse.success(toInternalResponse(limit), "Controls updated"));
        } catch (BusinessException e) {
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error("Failed to update: " + e.getMessage()));
        }
    }

    /**
     * Update thresholds.
     */
    @PutMapping("/internal/{limitId}/thresholds")
    @Operation(summary = "Update thresholds")
    public ResponseEntity<ApiResponse<InternalLimitResponse>> updateThresholds(
            @PathVariable UUID limitId,
            @Valid @RequestBody UpdateThresholdsRequest request) {
        
        log.info("PUT /internal/{}/thresholds", limitId);
        
        try {
            CreditLimit limit = creditLimitService.updateThresholds(
                limitId, request.getWarningPercent(), request.getCriticalPercent());
            return ResponseEntity.ok(ApiResponse.success(toInternalResponse(limit), "Thresholds updated"));
        } catch (BusinessException e) {
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error("Failed to update: " + e.getMessage()));
        }
    }

    /**
     * Delete entity sub-limit.
     */
    @DeleteMapping("/internal/entity/{limitId}")
    @Operation(summary = "Delete entity sub-limit")
    public ResponseEntity<ApiResponse<Void>> deleteEntitySubLimit(
            @PathVariable UUID limitId,
            @RequestParam @NotBlank String reason) {
        
        log.info("DELETE /internal/entity/{} - reason: {}", limitId, reason);
        
        try {
            creditLimitService.deleteEntitySubLimit(limitId, reason);
            return ResponseEntity.ok(ApiResponse.success(null, 
                "Entity limit deleted. Allocation returned to group limit."));
        } catch (BusinessException e) {
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error("Failed to delete: " + e.getMessage()));
        }
    }

    // ========================================================================
    // NEW v5.2.0: MULTI-CURRENCY ENTITY LIMIT ENDPOINTS
    // ========================================================================

    /**
     * Get ALL entity limits for an entity (multi-currency).
     */
    @GetMapping("/internal/entity/{entityId}/all")
    @Operation(summary = "Get all entity limits (multi-currency)",
               description = "Get all internal limits for an entity across all currencies")
    public ResponseEntity<ApiResponse<List<InternalLimitResponse>>> getAllEntityLimits(
            @PathVariable UUID entityId) {
        
        log.info("GET /internal/entity/{}/all - Fetching all entity limits", entityId);
        
        try {
            List<CreditLimit> entityLimits = creditLimitService.getAllEntityLimits(entityId);
            List<InternalLimitResponse> responses = entityLimits.stream()
                .map(this::toInternalResponse)
                .collect(Collectors.toList());
            
            return ResponseEntity.ok(ApiResponse.success(responses,
                String.format("Found %d limits for entity", responses.size())));
        } catch (Exception e) {
            log.error("Error fetching entity limits: {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.success(List.of(), "Error: " + e.getMessage()));
        }
    }

    /**
     * Get entity limit for a specific currency.
     */
    @GetMapping("/internal/entity/{entityId}/currency/{currency}")
    @Operation(summary = "Get entity limit by currency")
    public ResponseEntity<ApiResponse<InternalLimitResponse>> getEntityLimitByCurrency(
            @PathVariable UUID entityId,
            @PathVariable String currency) {
        
        log.info("GET /internal/entity/{}/currency/{}", entityId, currency);
        
        return creditLimitService.getEntityLimitByCurrency(entityId, currency)
            .map(limit -> ResponseEntity.ok(ApiResponse.success(toInternalResponse(limit))))
            .orElse(ResponseEntity.ok(ApiResponse.success(null, 
                "No limit set for currency: " + currency)));
    }

    /**
     * Get currencies that have entity limits configured.
     */
    @GetMapping("/internal/entity/{entityId}/currencies")
    @Operation(summary = "Get currencies with entity limits")
    public ResponseEntity<ApiResponse<List<String>>> getEntityLimitCurrencies(
            @PathVariable UUID entityId) {
        
        List<String> currencies = creditLimitService.getEntityLimitCurrencies(entityId);
        return ResponseEntity.ok(ApiResponse.success(currencies));
    }

    /**
     * Create entity sub-limit (multi-currency aware).
     * Uses the new multi-currency validation - allows multiple limits per entity.
     */
    @PostMapping("/internal/entity/v2")
    @Operation(summary = "Create entity sub-limit (multi-currency)",
               description = "Create entity limit with multi-currency and hierarchical support. " +
                           "If parentLimitId is provided, allocates from that parent (e.g., Regional Treasury). " +
                           "If not provided, allocates from the Group Limit for the specified currency.")
    public ResponseEntity<ApiResponse<InternalLimitResponse>> createEntitySubLimitMultiCurrency(
            @Valid @RequestBody CreateEntitySubLimitRequest request) {

        log.info("POST /internal/entity/v2 - {} {} for entity {}, parentLimitId={}",
                 request.getAmount(), request.getCurrency(), request.getEntityId(), request.getParentLimitId());

        try {
            CreditLimit limit = creditLimitService.createEntitySubLimitMultiCurrency(
                request.getCorporateId(),
                request.getEntityId(),
                request.getLimitName(),
                request.getAmount(),
                request.getCurrency(),
                request.getApprovedBy(),
                request.isHardLimit(),
                request.isRequiresApproval(),
                request.getApprovalThreshold(),
                request.getParentLimitId()  // Pass optional parent limit ID for hierarchical allocation
            );

            String source = request.getParentLimitId() != null ? "parent pool" : "group limit";
            return ResponseEntity.ok(ApiResponse.success(toInternalResponse(limit),
                String.format("Entity limit created for %s. Allocated from %s.",
                             request.getCurrency(), source)));
        } catch (BusinessException e) {
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            log.error("Error creating entity limit: {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.error("Failed to create: " + e.getMessage()));
        }
    }

    // ========================================================================
    // NEW v5.2.0: VA-LEVEL LIMIT ENDPOINTS
    // ========================================================================

    /**
     * Create VA-level limit.
     */
    @PostMapping("/internal/va")
    @Operation(summary = "Create VA limit",
               description = "Create internal credit limit for a specific VA, allocated from entity limit")
    public ResponseEntity<ApiResponse<InternalLimitResponse>> createVaLimit(
            @Valid @RequestBody CreateVaLimitRequest request) {
        
        log.info("POST /internal/va - {} for VA {}", request.getAmount(), request.getVaId());
        
        try {
            CreditLimit limit = creditLimitService.createVaLimit(
                request.getCorporateId(),
                request.getVaId(),
                request.getLimitName(),
                request.getAmount(),
                request.getApprovedBy(),
                request.isHardLimit()
            );
            
            return ResponseEntity.ok(ApiResponse.success(toInternalResponse(limit), 
                "VA limit created and allocated from entity limit."));
        } catch (BusinessException e) {
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            log.error("Error creating VA limit: {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.error("Failed to create: " + e.getMessage()));
        }
    }

    /**
     * Get VA limit.
     */
    @GetMapping("/internal/va/{vaId}")
    @Operation(summary = "Get VA limit")
    public ResponseEntity<ApiResponse<InternalLimitResponse>> getVaLimit(
            @PathVariable UUID vaId) {
        
        log.info("GET /internal/va/{}", vaId);
        
        return creditLimitService.getVaLimit(vaId)
            .map(limit -> ResponseEntity.ok(ApiResponse.success(toInternalResponse(limit))))
            .orElse(ResponseEntity.ok(ApiResponse.success(null, "No limit set for this VA")));
    }

    /**
     * Update VA limit amount.
     */
    @PutMapping("/internal/va/{limitId}/amount")
    @Operation(summary = "Update VA limit amount")
    public ResponseEntity<ApiResponse<InternalLimitResponse>> updateVaLimitAmount(
            @PathVariable UUID limitId,
            @RequestParam @NotNull @Positive BigDecimal newAmount,
            @RequestParam(defaultValue = "SYSTEM") String updatedBy) {
        
        log.info("PUT /internal/va/{}/amount - {}", limitId, newAmount);
        
        try {
            CreditLimit limit = creditLimitService.updateVaLimitAmount(limitId, newAmount, updatedBy);
            return ResponseEntity.ok(ApiResponse.success(toInternalResponse(limit), "VA limit updated"));
        } catch (BusinessException e) {
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error("Failed to update: " + e.getMessage()));
        }
    }

    /**
     * Delete VA limit.
     */
    @DeleteMapping("/internal/va/{limitId}")
    @Operation(summary = "Delete VA limit")
    public ResponseEntity<ApiResponse<Void>> deleteVaLimit(
            @PathVariable UUID limitId,
            @RequestParam @NotBlank String reason) {
        
        log.info("DELETE /internal/va/{} - reason: {}", limitId, reason);
        
        try {
            creditLimitService.deleteVaLimit(limitId, reason);
            return ResponseEntity.ok(ApiResponse.success(null, 
                "VA limit deleted. Allocation returned to entity limit."));
        } catch (BusinessException e) {
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error("Failed to delete: " + e.getMessage()));
        }
    }

    /**
     * Get all VA limits under an entity.
     */
    @GetMapping("/internal/entity/{entityId}/va-limits")
    @Operation(summary = "Get VA limits for entity",
               description = "Get all VA-level limits under a specific entity")
    public ResponseEntity<ApiResponse<List<InternalLimitResponse>>> getVaLimitsByEntity(
            @PathVariable UUID entityId) {
        
        log.info("GET /internal/entity/{}/va-limits", entityId);
        
        try {
            List<CreditLimit> limits = creditLimitService.getVaLimitsByEntity(entityId);
            return ResponseEntity.ok(ApiResponse.success(limits.stream()
                .map(this::toInternalResponse)
                .collect(Collectors.toList())));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.success(List.of(), "Error: " + e.getMessage()));
        }
    }

    /**
     * Get VA limits allocated from a specific entity limit.
     */
    @GetMapping("/internal/entity-limit/{entityLimitId}/va-limits")
    @Operation(summary = "Get VA limits by entity limit")
    public ResponseEntity<ApiResponse<List<InternalLimitResponse>>> getVaLimitsByEntityLimit(
            @PathVariable UUID entityLimitId) {
        
        log.info("GET /internal/entity-limit/{}/va-limits", entityLimitId);
        
        try {
            List<CreditLimit> limits = creditLimitService.getVaLimitsByEntityLimit(entityLimitId);
            return ResponseEntity.ok(ApiResponse.success(limits.stream()
                .map(this::toInternalResponse)
                .collect(Collectors.toList())));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.success(List.of(), "Error: " + e.getMessage()));
        }
    }

    /**
     * Count VAs with limits under an entity.
     */
    @GetMapping("/internal/entity/{entityId}/va-limits/count")
    @Operation(summary = "Count VA limits for entity")
    public ResponseEntity<ApiResponse<Long>> countVaLimitsForEntity(
            @PathVariable UUID entityId) {
        
        long count = creditLimitService.countVaLimitsForEntity(entityId);
        return ResponseEntity.ok(ApiResponse.success(count));
    }

    // ========================================================================
    // AVAILABILITY CHECK APIs
    // ========================================================================

    /**
     * Check funds availability for a transaction.
     */
    @GetMapping("/check/entity/{entityId}/availability")
    @Operation(summary = "Check funds availability")
    public ResponseEntity<ApiResponse<CreditLimitService.LimitCheckResult>> checkFundsAvailability(
            @PathVariable UUID entityId,
            @RequestParam @NotNull @Positive BigDecimal amount,
            @RequestParam(required = false) String currency) {
        
        log.info("GET /check/entity/{}/availability?amount={}&currency={}", entityId, amount, currency);
        
        CreditLimitService.LimitCheckResult result = creditLimitService.checkFundsAvailability(entityId, amount, currency);
        
        String message = result.isCanProceed() 
            ? "Transaction can proceed" 
            : "Transaction blocked - insufficient limit";
        
        return ResponseEntity.ok(ApiResponse.success(result, message));
    }

    /**
     * Check if transaction should be blocked.
     */
    @GetMapping("/check/entity/{entityId}/blocked")
    @Operation(summary = "Check if blocked")
    public ResponseEntity<ApiResponse<BlockCheckResponse>> checkIfBlocked(
            @PathVariable UUID entityId,
            @RequestParam @NotNull @Positive BigDecimal amount) {
        
        boolean blocked = creditLimitService.shouldBlockTransaction(entityId, amount);
        
        return ResponseEntity.ok(ApiResponse.success(
            BlockCheckResponse.builder()
                .entityId(entityId)
                .amount(amount)
                .blocked(blocked)
                .reason(blocked ? "Internal hard limit exceeded" : null)
                .build()
        ));
    }

    /**
     * Check if transaction needs approval.
     */
    @GetMapping("/check/entity/{entityId}/approval-required")
    @Operation(summary = "Check if approval needed")
    public ResponseEntity<ApiResponse<ApprovalCheckResponse>> checkIfApprovalNeeded(
            @PathVariable UUID entityId,
            @RequestParam @NotNull @Positive BigDecimal amount) {
        
        boolean needsApproval = creditLimitService.needsApproval(entityId, amount);
        
        return ResponseEntity.ok(ApiResponse.success(
            ApprovalCheckResponse.builder()
                .entityId(entityId)
                .amount(amount)
                .approvalRequired(needsApproval)
                .reason(needsApproval ? "Utilization would exceed approval threshold" : null)
                .build()
        ));
    }

    // ========================================================================
    // DASHBOARD & MONITORING APIs
    // ========================================================================

    /**
     * Get limit totals for corporate dashboard.
     */
    @GetMapping("/dashboard/corporate/{corporateId}/totals")
    @Operation(summary = "Get limit totals for dashboard")
    public ResponseEntity<ApiResponse<CreditLimitService.LimitTotals>> getDashboardLimitTotals(
            @PathVariable UUID corporateId) {
        
        log.info("GET /dashboard/corporate/{}/totals", corporateId);
        
        CreditLimitService.LimitTotals totals = creditLimitService.getLimitTotals(corporateId);
        return ResponseEntity.ok(ApiResponse.success(totals));
    }

    /**
     * Get limit totals grouped by currency.
     */
    @GetMapping("/dashboard/corporate/{corporateId}/totals-by-currency")
    @Operation(summary = "Get limit totals by currency",
               description = "Get aggregated limit totals for each currency")
    public ResponseEntity<ApiResponse<Map<String, CreditLimitService.CurrencyLimitTotals>>> 
            getLimitTotalsByCurrency(@PathVariable UUID corporateId) {
        
        log.info("GET /dashboard/corporate/{}/totals-by-currency", corporateId);
        
        try {
            Map<String, CreditLimitService.CurrencyLimitTotals> totals = 
                creditLimitService.getLimitTotalsByCurrency(corporateId);
            return ResponseEntity.ok(ApiResponse.success(totals));
        } catch (Exception e) {
            log.error("Error fetching totals by currency: {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.success(Map.of(), "Error: " + e.getMessage()));
        }
    }

    /**
     * Get limits at warning level (dashboard).
     */
    @GetMapping("/dashboard/corporate/{corporateId}/warnings")
    @Operation(summary = "Get limits at warning level")
    public ResponseEntity<ApiResponse<List<InternalLimitResponse>>> getLimitsAtWarningLevelDashboard(
            @PathVariable UUID corporateId) {
        
        List<InternalLimitResponse> limits = creditLimitService.getLimitsAtWarningLevel(corporateId)
            .stream()
            .map(this::toInternalResponse)
            .collect(Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.success(limits));
    }

    /**
     * Get limits at critical level (dashboard).
     */
    @GetMapping("/dashboard/corporate/{corporateId}/critical")
    @Operation(summary = "Get limits at critical level")
    public ResponseEntity<ApiResponse<List<InternalLimitResponse>>> getLimitsAtCriticalLevelDashboard(
            @PathVariable UUID corporateId) {
        
        List<InternalLimitResponse> limits = creditLimitService.getLimitsAtCriticalLevel(corporateId)
            .stream()
            .map(this::toInternalResponse)
            .collect(Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.success(limits));
    }

    /**
     * Get breached limits (dashboard).
     */
    @GetMapping("/dashboard/corporate/{corporateId}/breached")
    @Operation(summary = "Get breached limits")
    public ResponseEntity<ApiResponse<List<InternalLimitResponse>>> getBreachedLimitsDashboard(
            @PathVariable UUID corporateId) {
        
        List<InternalLimitResponse> limits = creditLimitService.getBreachedLimits(corporateId)
            .stream()
            .map(this::toInternalResponse)
            .collect(Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.success(limits));
    }

    /**
     * Get expiring limits (dashboard).
     */
    @GetMapping("/dashboard/corporate/{corporateId}/expiring")
    @Operation(summary = "Get expiring limits")
    public ResponseEntity<ApiResponse<List<InternalLimitResponse>>> getExpiringLimitsDashboard(
            @PathVariable UUID corporateId,
            @RequestParam(defaultValue = "30") int daysAhead) {
        
        List<InternalLimitResponse> limits = creditLimitService.getExpiringLimits(corporateId, daysAhead)
            .stream()
            .map(this::toInternalResponse)
            .collect(Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.success(limits));
    }

    // ========================================================================
    // UTILIZATION RECORDING (with hierarchy propagation)
    // ========================================================================

    /**
     * Record utilization on a limit (e.g., when IHB loan is drawn).
     * Propagates utilization up the hierarchy to Regional Treasury and Group Limit.
     */
    @PostMapping("/{limitId}/utilize")
    @Operation(summary = "Record utilization",
               description = "Record utilization on a limit. Propagates up to parent limits in the hierarchy.")
    public ResponseEntity<ApiResponse<InternalLimitResponse>> recordUtilization(
            @PathVariable UUID limitId,
            @RequestParam BigDecimal amount,
            @RequestParam(required = false) String transactionRef) {

        log.info("POST /{}/utilize - amount={}, txnRef={}", limitId, amount, transactionRef);

        try {
            CreditLimit updated = creditLimitService.recordUtilization(limitId, amount, transactionRef);
            return ResponseEntity.ok(ApiResponse.success(toInternalResponse(updated),
                "Utilization recorded and propagated through hierarchy"));
        } catch (BusinessException e) {
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Release utilization (e.g., when IHB loan is repaid).
     * Propagates release up the hierarchy.
     */
    @PostMapping("/{limitId}/release")
    @Operation(summary = "Release utilization",
               description = "Release utilization from a limit. Propagates up to parent limits.")
    public ResponseEntity<ApiResponse<InternalLimitResponse>> releaseUtilization(
            @PathVariable UUID limitId,
            @RequestParam BigDecimal amount,
            @RequestParam(required = false) String transactionRef) {

        log.info("POST /{}/release - amount={}, txnRef={}", limitId, amount, transactionRef);

        try {
            CreditLimit updated = creditLimitService.releaseUtilization(limitId, amount, transactionRef);
            return ResponseEntity.ok(ApiResponse.success(toInternalResponse(updated),
                "Utilization released and propagated through hierarchy"));
        } catch (BusinessException e) {
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Record utilization by entity and currency.
     * Convenience endpoint when you don't have the limit ID.
     */
    @PostMapping("/entity/{entityId}/utilize")
    @Operation(summary = "Record entity utilization",
               description = "Record utilization for an entity by currency. Finds the limit automatically.")
    public ResponseEntity<ApiResponse<InternalLimitResponse>> recordEntityUtilization(
            @PathVariable UUID entityId,
            @RequestParam String currency,
            @RequestParam BigDecimal amount,
            @RequestParam(required = false) String transactionRef) {

        log.info("POST /entity/{}/utilize - currency={}, amount={}", entityId, currency, amount);

        try {
            CreditLimit updated = creditLimitService.recordEntityUtilization(entityId, currency, amount, transactionRef);
            return ResponseEntity.ok(ApiResponse.success(toInternalResponse(updated),
                "Entity utilization recorded and propagated through hierarchy"));
        } catch (BusinessException e) {
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Check if utilization can be recorded without breaching limits.
     * Checks the entire hierarchy before committing.
     */
    @GetMapping("/{limitId}/check-capacity")
    @Operation(summary = "Check utilization capacity",
               description = "Check if the requested amount can be utilized without breaching any limit in the hierarchy.")
    public ResponseEntity<ApiResponse<CreditLimitService.UtilizationCheckResult>> checkUtilizationCapacity(
            @PathVariable UUID limitId,
            @RequestParam BigDecimal amount) {

        log.info("GET /{}/check-capacity - amount={}", limitId, amount);

        CreditLimitService.UtilizationCheckResult result = creditLimitService.checkUtilizationCapacity(limitId, amount);
        String message = result.isCanProceed()
            ? "Utilization can proceed"
            : "Utilization blocked: " + result.getBlockingReason();
        return ResponseEntity.ok(ApiResponse.success(result, message));
    }

    /**
     * Recalculate parent utilization from children.
     * Useful for reconciliation after data corrections.
     */
    @PostMapping("/{limitId}/recalculate-utilization")
    @Operation(summary = "Recalculate parent utilization",
               description = "Recalculate utilization by summing all child limits. Use for reconciliation.")
    public ResponseEntity<ApiResponse<InternalLimitResponse>> recalculateUtilization(
            @PathVariable UUID limitId) {

        log.info("POST /{}/recalculate-utilization", limitId);

        try {
            CreditLimit updated = creditLimitService.recalculateParentUtilization(limitId);
            return ResponseEntity.ok(ApiResponse.success(toInternalResponse(updated),
                "Utilization recalculated from child limits"));
        } catch (BusinessException e) {
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    // ========================================================================
    // MAPPING METHODS (Entity → DTO)
    // ========================================================================

    private CreditLimitDto.Response toResponse(CreditLimit limit) {
        return CreditLimitDto.Response.builder()
            .id(limit.getId())
            .corporateId(limit.getCorporateId())
            .limitName(limit.getLimitName())
            .limitType(limit.getLimitType())
            .targetId(limit.getTargetId())
            .targetType(limit.getTargetType())
            .creditFacilityId(limit.getCreditFacilityId())
            .externalReference(limit.getExternalReference())
            .parentLimitId(limit.getParentLimitId())
            .limitAmount(limit.getLimitAmount())
            .limitCurrency(limit.getLimitCurrency())
            .utilizedAmount(limit.getUtilizedAmount())
            .availableAmount(limit.getAvailableAmount())
            .heldAmount(limit.getHeldAmount())
            .allocatedToChildren(limit.getAllocatedToChildren())
            .unallocatedAmount(limit.getUnallocatedAmount())
            .utilizationPercent(limit.getUtilizationPercent())
            .warningThresholdPercent(limit.getWarningThresholdPercent())
            .criticalThresholdPercent(limit.getCriticalThresholdPercent())
            .isHardLimit(limit.isHard())
            .requiresApproval(limit.getRequiresApproval())
            .approvalThresholdPercent(limit.getApprovalThresholdPercent())
            .isAtWarningLevel(limit.isAtWarningLevel())
            .isAtCriticalLevel(limit.isAtCriticalLevel())
            .isBreached(limit.isBreached())
            .effectiveFrom(limit.getEffectiveFrom())
            .effectiveTo(limit.getEffectiveTo())
            .status(limit.getStatus())
            .approvedBy(limit.getApprovedBy())
            .approvedAt(limit.getApprovedAt())
            .notes(limit.getNotes())
            .sourceSystem(limit.getSourceSystem())
            .lastSyncAt(limit.getLastSyncAt())
            .createdAt(limit.getCreatedAt())
            .updatedAt(limit.getUpdatedAt())
            .build();
    }

    private ExternalLimitResponse toExternalResponse(CreditLimit limit) {
        return ExternalLimitResponse.builder()
            .id(limit.getId())
            .limitName(limit.getLimitName())
            .facilityId(limit.getCreditFacilityId())
            .externalReference(limit.getExternalReference())
            .targetId(limit.getTargetId())
            .targetType(limit.getTargetType())
            .limitAmount(limit.getLimitAmount())
            .currency(limit.getLimitCurrency())
            .utilizedAmount(limit.getUtilizedAmount())
            .availableAmount(limit.getAvailableAmount())
            .utilizationPercent(limit.getUtilizationPercent())
            .effectiveFrom(limit.getEffectiveFrom())
            .effectiveTo(limit.getEffectiveTo())
            .status(limit.getStatus())
            .sourceSystem(limit.getSourceSystem())
            .lastSyncAt(limit.getLastSyncAt())
            .readOnly(true)
            .readOnlyReason("Bank-provided limits cannot be modified. Contact bank for changes.")
            .build();
    }

    private FacilityResponse toFacilityResponse(CreditFacility facility) {
        return FacilityResponse.builder()
            .id(facility.getId())
            .facilityName(facility.getFacilityName())
            .facilityType(facility.getFacilityType().name())
            .externalReference(facility.getExternalReference())
            .sanctionedLimit(facility.getSanctionedLimit())
            .currentOutstanding(facility.getCurrentOutstanding())
            .availableLimit(facility.getAvailableLimit())
            .currency(facility.getFacilityCurrency())
            .interestRateType(facility.getInterestRateType())
            .baseRateType(facility.getBaseRateType())
            .baseRateValue(facility.getBaseRateValue())
            .spreadPercent(facility.getSpreadPercent())
            .effectiveRate(facility.getEffectiveRate())
            .expiryDate(facility.getExpiryDate())
            .status(facility.getStatus().name())
            .lastSyncAt(facility.getLastSyncAt())
            .build();
    }

    private InternalLimitResponse toInternalResponse(CreditLimit limit) {
        // Determine limit type label based on target type
        String limitTypeLabel;
        switch (limit.getTargetType()) {
            case CORPORATE:
                limitTypeLabel = "GROUP";
                break;
            case LEGAL_ENTITY:
                limitTypeLabel = "ENTITY";
                break;
            case VIRTUAL_ACCOUNT:
                limitTypeLabel = "VA";
                break;
            default:
                limitTypeLabel = limit.getTargetType().name();
        }
        
        return InternalLimitResponse.builder()
            .id(limit.getId())
            .limitName(limit.getLimitName())
            .limitType(limitTypeLabel)
            .corporateId(limit.getCorporateId())
            .targetType(limit.getTargetType())
            .targetId(limit.getTargetId())
            .parentLimitId(limit.getParentLimitId())
            .allocatedToChildren(limit.getAllocatedToChildren())
            .unallocatedAmount(limit.getUnallocatedAmount())
            .limitAmount(limit.getLimitAmount())
            .currency(limit.getLimitCurrency())
            .utilizedAmount(limit.getUtilizedAmount())
            .availableAmount(limit.getAvailableAmount())
            .heldAmount(limit.getHeldAmount())
            .utilizationPercent(limit.getUtilizationPercent())
            .warningThresholdPercent(limit.getWarningThresholdPercent())
            .criticalThresholdPercent(limit.getCriticalThresholdPercent())
            .isAtWarningLevel(limit.isAtWarningLevel())
            .isAtCriticalLevel(limit.isAtCriticalLevel())
            .isBreached(limit.isBreached())
            .isHardLimit(limit.isHard())
            .requiresApproval(Boolean.TRUE.equals(limit.getRequiresApproval()))
            .approvalThresholdPercent(limit.getApprovalThresholdPercent())
            .needsApprovalNow(limit.needsApproval())
            .effectiveFrom(limit.getEffectiveFrom())
            .effectiveTo(limit.getEffectiveTo())
            .status(limit.getStatus())
            .approvedBy(limit.getApprovedBy())
            .approvedAt(limit.getApprovedAt())
            .notes(limit.getNotes())
            .createdAt(limit.getCreatedAt())
            .updatedAt(limit.getUpdatedAt())
            .build();
    }
}