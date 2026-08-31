package com.bank.vam.controller.credit;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.dto.credit.InterestConfigurationDto;
import com.bank.vam.entity.credit.InterestConfiguration;
import com.bank.vam.entity.credit.InterestConfiguration.*;
import com.bank.vam.service.credit.InterestConfigurationService;
import com.bank.vam.service.credit.InterestConfigurationService.SpreadAnalysis;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * InterestConfigurationController - For Corporate Users.
 * 
 * UNIFIED ARCHITECTURE v4.2 - CORPORATE USER PERSPECTIVE:
 * =========================================================
 * 
 * This controller serves CORPORATE USERS (not bank staff).
 * 
 * TWO TYPES OF INTEREST CONFIGURATIONS:
 * 
 * 1. EXTERNAL RATES (Bank-Provided) - READ-ONLY
 *    ------------------------------------------
 *    - Set by bank via CBS (BANCS)
 *    - Represents what bank charges/pays on OD/facilities
 *    - Corporate can VIEW but NOT modify
 *    - Endpoints: GET only (no POST/PUT/DELETE)
 * 
 * 2. INTERNAL RATES (Treasury Transfer Pricing) - MANAGEABLE
 *    -------------------------------------------------------
 *    - Set by corporate treasury/CFO
 *    - Represents internal transfer pricing spreads
 *    - Corporate can CREATE, UPDATE, DELETE
 *    - Used for IHB loans, intercompany transactions
 *    - SPREAD over/under external rates
 * 
 * BUSINESS RULES:
 * ===============
 * - Credit Spread typically ≤ 0 (treasury pays less than bank)
 * - Debit Spread typically ≥ 0 (treasury charges more than bank)
 * - Treasury margin = |Spread| on each
 * 
 * ENDPOINT ORGANIZATION:
 * ======================
 * /api/v1/interest-configs/corporate/{id}/active  - All active configs (for UI)
 * /api/v1/interest-configs/corporate/{id}         - Paginated configs
 * /api/v1/interest-configs/{id}                   - Single config CRUD
 * /api/v1/interest-configs/external/*             - View bank rates (read-only)
 * /api/v1/interest-configs/internal/*             - Manage transfer pricing (full CRUD)
 * /api/v1/interest-configs/calculate/*            - Interest calculations
 * /api/v1/interest-configs/analysis/*             - Spread analysis and reporting
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/interest-configs")
@RequiredArgsConstructor
@Tag(name = "Interest Configuration", description = "Interest Rate Configuration for Corporate Users")
public class InterestConfigurationController {

    private final InterestConfigurationService service;

    // ========================================================================
    // COMBINED ACTIVE CONFIGURATIONS (For InterestConfigurationPage.tsx)
    // ========================================================================

    /**
     * Get all active interest configurations for a corporate (both external and internal).
     * This endpoint supports the InterestConfigurationPage.tsx frontend component.
     */
    @GetMapping("/corporate/{corporateId}/active")
    @Operation(summary = "Get all active interest configurations",
               description = "Get all active interest configurations (external + internal) for a corporate")
    public ResponseEntity<ApiResponse<List<InterestConfigurationDto.Response>>> getActiveConfigurations(
            @PathVariable UUID corporateId) {
        
        log.info("GET /corporate/{}/active - Fetching all active interest configs", corporateId);
        
        List<InterestConfiguration> activeConfigs = service.findActiveByCorporate(corporateId);
        
        List<InterestConfigurationDto.Response> responses = activeConfigs.stream()
            .map(this::toResponse)
            .collect(Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    /**
     * Get paginated interest configurations for a corporate.
     */
    @GetMapping("/corporate/{corporateId}")
    @Operation(summary = "Get interest configurations for corporate",
               description = "Get paginated interest configurations for a corporate")
    public ResponseEntity<ApiResponse<Page<InterestConfigurationDto.Response>>> getConfigurationsByCorporate(
            @PathVariable UUID corporateId,
            Pageable pageable) {
        
        log.info("GET /corporate/{} - Fetching interest configs, page={}", corporateId, pageable.getPageNumber());
        
        Page<InterestConfiguration> page = service.findByCorporate(corporateId, pageable);
        Page<InterestConfigurationDto.Response> responsePage = page.map(this::toResponse);
        
        return ResponseEntity.ok(ApiResponse.success(responsePage));
    }

    /**
     * Get interest configuration by ID.
     */
    @GetMapping("/{configId}")
    @Operation(summary = "Get interest configuration by ID")
    public ResponseEntity<ApiResponse<InterestConfigurationDto.Response>> getConfigurationById(
            @PathVariable UUID configId) {
        
        log.info("GET /{} - Fetching interest config", configId);
        
        InterestConfiguration config = service.getById(configId);
        return ResponseEntity.ok(ApiResponse.success(toResponse(config)));
    }

    /**
     * Create a new interest configuration.
     */
    @PostMapping
    @Operation(summary = "Create interest configuration")
    public ResponseEntity<ApiResponse<InterestConfigurationDto.Response>> createConfiguration(
            @Valid @RequestBody InterestConfigurationDto.CreateRequest request) {
        
        log.info("POST / - Creating interest config for corporate {}", request.getCorporateId());
        
        InterestConfiguration config = service.create(request);
        return ResponseEntity.ok(ApiResponse.success(toResponse(config), "Interest configuration created"));
    }

    /**
     * Update an interest configuration.
     */
    @PutMapping("/{configId}")
    @Operation(summary = "Update interest configuration")
    public ResponseEntity<ApiResponse<InterestConfigurationDto.Response>> updateConfiguration(
            @PathVariable UUID configId,
            @Valid @RequestBody InterestConfigurationDto.UpdateRequest request) {
        
        log.info("PUT /{} - Updating interest config", configId);
        
        InterestConfiguration config = service.update(configId, request);
        return ResponseEntity.ok(ApiResponse.success(toResponse(config), "Interest configuration updated"));
    }

    /**
     * Update base rates (for CBS sync).
     * Accepts body: { creditBaseRate: number, debitBaseRate: number }
     */
    @PutMapping("/{configId}/rates")
    @Operation(summary = "Update base rates")
    public ResponseEntity<ApiResponse<InterestConfigurationDto.Response>> updateRates(
            @PathVariable UUID configId,
            @RequestBody InterestConfigurationDto.RateUpdateRequest request) {
        
        log.info("PUT /{}/rates - Updating base rates", configId);
        
        InterestConfiguration config = service.updateBaseRates(configId, request.getCreditBaseRate(), request.getDebitBaseRate());
        return ResponseEntity.ok(ApiResponse.success(toResponse(config), "Base rates updated"));
    }

    /**
     * Activate a configuration.
     */
    @PutMapping("/{configId}/activate")
    @Operation(summary = "Activate interest configuration")
    public ResponseEntity<ApiResponse<InterestConfigurationDto.Response>> activateConfiguration(
            @PathVariable UUID configId) {
        
        log.info("PUT /{}/activate", configId);
        
        InterestConfiguration config = service.activate(configId);
        return ResponseEntity.ok(ApiResponse.success(toResponse(config), "Configuration activated"));
    }

    /**
     * Suspend a configuration.
     */
    @PutMapping("/{configId}/suspend")
    @Operation(summary = "Suspend interest configuration")
    public ResponseEntity<ApiResponse<InterestConfigurationDto.Response>> suspendConfiguration(
            @PathVariable UUID configId) {
        
        log.info("PUT /{}/suspend", configId);
        
        InterestConfiguration config = service.suspend(configId);
        return ResponseEntity.ok(ApiResponse.success(toResponse(config), "Configuration suspended"));
    }

    /**
     * Delete a configuration.
     */
    @DeleteMapping("/{configId}")
    @Operation(summary = "Delete interest configuration")
    public ResponseEntity<ApiResponse<Void>> deleteConfiguration(
            @PathVariable UUID configId) {
        
        log.info("DELETE /{}", configId);
        
        service.delete(configId);
        return ResponseEntity.ok(ApiResponse.success(null, "Configuration deleted"));
    }

    // ========================================================================
    // EXTERNAL RATES (READ-ONLY - Bank Provided)
    // ========================================================================

    /**
     * Get external (bank-provided) interest configurations.
     * These are READ-ONLY - set by bank via CBS.
     */
    @GetMapping("/external/corporate/{corporateId}")
    @Operation(summary = "Get external (bank) interest rates",
               description = "View bank-provided interest rates. READ-ONLY - contact bank to modify.")
    public ResponseEntity<ApiResponse<List<ExternalRateResponse>>> getExternalConfigs(
            @PathVariable UUID corporateId) {
        
        log.info("GET /external/corporate/{} - Fetching bank interest rates", corporateId);
        
        List<ExternalRateResponse> configs = service.getActiveExternalConfigs(corporateId)
            .stream()
            .map(this::toExternalResponse)
            .collect(Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.success(configs, 
            "Bank rates are read-only. Contact bank to modify."));
    }

    /**
     * Get external rate for a specific target.
     */
    @GetMapping("/external/target/{targetId}")
    @Operation(summary = "Get external rate for target",
               description = "View bank interest rate for a specific entity/account")
    public ResponseEntity<ApiResponse<ExternalRateResponse>> getExternalConfigForTarget(
            @PathVariable UUID targetId,
            @RequestParam(required = false) String currency) {
        
        log.info("GET /external/target/{} currency={}", targetId, currency);
        
        return service.getExternalConfigForTarget(targetId, currency)
            .map(config -> ResponseEntity.ok(ApiResponse.success(toExternalResponse(config))))
            .orElse(ResponseEntity.ok(ApiResponse.success(null, "No external rate found")));
    }

    // ========================================================================
    // INTERNAL RATES (Corporate Can Manage)
    // ========================================================================

    /**
     * Get internal (treasury) interest configurations.
     */
    @GetMapping("/internal/corporate/{corporateId}")
    @Operation(summary = "Get internal (treasury) interest rates",
               description = "View treasury transfer pricing configurations")
    public ResponseEntity<ApiResponse<List<InternalRateResponse>>> getInternalConfigs(
            @PathVariable UUID corporateId) {
        
        log.info("GET /internal/corporate/{}", corporateId);
        
        List<InternalRateResponse> configs = service.getActiveInternalConfigs(corporateId)
            .stream()
            .map(this::toInternalResponse)
            .collect(Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.success(configs));
    }

    /**
     * Get internal rate for a specific target.
     */
    @GetMapping("/internal/target/{targetId}")
    @Operation(summary = "Get internal rate for target",
               description = "View treasury transfer pricing for a specific entity")
    public ResponseEntity<ApiResponse<InternalRateResponse>> getInternalConfigForTarget(
            @PathVariable UUID targetId,
            @RequestParam(required = false) String currency) {
        
        log.info("GET /internal/target/{} currency={}", targetId, currency);
        
        return service.getInternalConfigForTarget(targetId, currency)
            .map(config -> ResponseEntity.ok(ApiResponse.success(toInternalResponse(config))))
            .orElse(ResponseEntity.ok(ApiResponse.success(null, "No internal rate found")));
    }

    /**
     * Create internal (treasury) interest configuration.
     */
    @PostMapping("/internal")
    @Operation(summary = "Create internal rate configuration",
               description = "Create treasury transfer pricing. Spreads are relative to external rates.")
    public ResponseEntity<ApiResponse<InternalRateResponse>> createInternalConfig(
            @Valid @RequestBody CreateInternalConfigRequest request) {
        
        log.info("POST /internal - Creating treasury rate for target {} currency {}", 
                 request.getTargetId(), request.getCurrency());
        
        InterestConfiguration config = service.createInternalConfig(
            request.getCorporateId(),
            request.getTargetId(),
            request.getTargetType(),
            request.getCurrency(),
            request.getConfigName(),
            request.getBaseRateType(),
            request.getCreditSpread(),
            request.getDebitSpread()
        );
        
        return ResponseEntity.ok(ApiResponse.success(toInternalResponse(config),
            "Internal rate created. Effective rates calculated based on external rates."));
    }

    /**
     * Create corporate default internal rate.
     */
    @PostMapping("/internal/corporate-default")
    @Operation(summary = "Create corporate default rate",
               description = "Create default transfer pricing for entire corporate")
    public ResponseEntity<ApiResponse<InternalRateResponse>> createCorporateDefault(
            @Valid @RequestBody CreateCorporateDefaultRequest request) {
        
        log.info("POST /internal/corporate-default for corporate {}", request.getCorporateId());
        
        InterestConfiguration config = service.createCorporateDefaultConfig(
            request.getCorporateId(),
            request.getCurrency(),
            request.getConfigName(),
            request.getBaseRateType(),
            request.getCreditSpread(),
            request.getDebitSpread()
        );
        
        return ResponseEntity.ok(ApiResponse.success(toInternalResponse(config),
            "Corporate default rate created"));
    }

    /**
     * Create currency default internal rate.
     */
    @PostMapping("/internal/currency-default")
    @Operation(summary = "Create currency default rate",
               description = "Create default transfer pricing for a specific currency")
    public ResponseEntity<ApiResponse<InternalRateResponse>> createCurrencyDefault(
            @Valid @RequestBody CreateCurrencyDefaultRequest request) {
        
        log.info("POST /internal/currency-default for corporate {} currency {}", 
                 request.getCorporateId(), request.getCurrency());
        
        InterestConfiguration config = service.createCurrencyDefaultConfig(
            request.getCorporateId(),
            request.getCurrency(),
            request.getConfigName(),
            request.getBaseRateType(),
            request.getCreditSpread(),
            request.getDebitSpread()
        );
        
        return ResponseEntity.ok(ApiResponse.success(toInternalResponse(config),
            "Currency default rate created"));
    }

    /**
     * Update internal spreads.
     * This is the primary operation for corporate users.
     */
    @PutMapping("/internal/{configId}/spreads")
    @Operation(summary = "Update internal spreads",
               description = "Update treasury spreads. Effective rates recalculated automatically.")
    public ResponseEntity<ApiResponse<InternalRateResponse>> updateSpreads(
            @PathVariable UUID configId,
            @Valid @RequestBody UpdateSpreadsRequest request) {
        
        log.info("PUT /internal/{}/spreads - credit: {}, debit: {}", 
                 configId, request.getCreditSpread(), request.getDebitSpread());
        
        InterestConfiguration config = service.updateInternalSpreads(
            configId, request.getCreditSpread(), request.getDebitSpread());
        
        return ResponseEntity.ok(ApiResponse.success(toInternalResponse(config),
            "Spreads updated. Effective rates recalculated."));
    }

    /**
     * Update calculation frequencies.
     */
    @PutMapping("/internal/{configId}/frequencies")
    @Operation(summary = "Update calculation frequencies",
               description = "Update interest calculation, posting, and compounding frequencies")
    public ResponseEntity<ApiResponse<InternalRateResponse>> updateFrequencies(
            @PathVariable UUID configId,
            @Valid @RequestBody UpdateFrequenciesRequest request) {
        
        log.info("PUT /internal/{}/frequencies", configId);
        
        InterestConfiguration config = service.updateFrequencies(
            configId,
            request.getCalculationFrequency(),
            request.getPostingFrequency(),
            request.getCompoundingFrequency()
        );
        
        return ResponseEntity.ok(ApiResponse.success(toInternalResponse(config), "Frequencies updated"));
    }

    /**
     * Update day count convention.
     */
    @PutMapping("/internal/{configId}/day-count")
    @Operation(summary = "Update day count convention",
               description = "Update day count basis (ACT/360, ACT/365, 30/360)")
    public ResponseEntity<ApiResponse<InternalRateResponse>> updateDayCount(
            @PathVariable UUID configId,
            @RequestParam @NotBlank String dayCountConvention) {
        
        log.info("PUT /internal/{}/day-count - {}", configId, dayCountConvention);
        
        InterestConfiguration config = service.updateDayCountConvention(configId, dayCountConvention);
        return ResponseEntity.ok(ApiResponse.success(toInternalResponse(config), "Day count updated"));
    }

    /**
     * Update minimum balance for credit interest.
     */
    @PutMapping("/internal/{configId}/min-balance")
    @Operation(summary = "Update minimum balance",
               description = "Set minimum balance required for credit interest")
    public ResponseEntity<ApiResponse<InternalRateResponse>> updateMinBalance(
            @PathVariable UUID configId,
            @RequestParam @NotNull BigDecimal minBalance) {
        
        log.info("PUT /internal/{}/min-balance - {}", configId, minBalance);
        
        InterestConfiguration config = service.updateCreditMinBalance(configId, minBalance);
        return ResponseEntity.ok(ApiResponse.success(toInternalResponse(config), "Minimum balance updated"));
    }

    /**
     * Update penalty rate.
     */
    @PutMapping("/internal/{configId}/penalty-rate")
    @Operation(summary = "Update penalty rate",
               description = "Set additional penalty rate for overdue amounts")
    public ResponseEntity<ApiResponse<InternalRateResponse>> updatePenaltyRate(
            @PathVariable UUID configId,
            @RequestParam @NotNull BigDecimal penaltyRate) {
        
        log.info("PUT /internal/{}/penalty-rate - {}", configId, penaltyRate);
        
        InterestConfiguration config = service.updatePenaltyRate(configId, penaltyRate);
        return ResponseEntity.ok(ApiResponse.success(toInternalResponse(config), "Penalty rate updated"));
    }

    /**
     * Delete (expire) internal configuration.
     */
    @DeleteMapping("/internal/{configId}")
    @Operation(summary = "Delete internal rate configuration",
               description = "Expire an internal rate configuration")
    public ResponseEntity<ApiResponse<Void>> deleteInternalConfig(
            @PathVariable UUID configId,
            @RequestParam(defaultValue = "Deleted by user") String reason) {
        
        log.info("DELETE /internal/{} - reason: {}", configId, reason);
        
        service.deleteInternalConfig(configId, reason);
        return ResponseEntity.ok(ApiResponse.success(null, "Internal rate configuration deleted"));
    }

    // ========================================================================
    // EFFECTIVE CONFIG RESOLUTION
    // ========================================================================

    /**
     * Get effective rate for a target (resolves priority).
     */
    @GetMapping("/effective")
    @Operation(summary = "Get effective rate",
               description = "Get effective interest rate (target-specific > currency default > corporate default)")
    public ResponseEntity<ApiResponse<EffectiveRateResponse>> getEffectiveConfig(
            @RequestParam UUID corporateId,
            @RequestParam UUID targetId,
            @RequestParam String currency,
            @RequestParam(defaultValue = "INTERNAL") ConfigType configType) {
        
        log.info("GET /effective corporate={} target={} currency={} type={}", 
                 corporateId, targetId, currency, configType);
        
        var configOpt = configType == ConfigType.EXTERNAL ?
            service.getEffectiveExternalConfig(corporateId, targetId, currency) :
            service.getEffectiveInternalConfig(corporateId, targetId, currency);
        
        return configOpt
            .map(config -> ResponseEntity.ok(ApiResponse.success(
                EffectiveRateResponse.builder()
                    .configId(config.getId())
                    .configName(config.getConfigName())
                    .configType(config.getConfigType())
                    .targetType(config.getTargetType())
                    .currency(config.getCurrencyCode())
                    .effectiveCreditRate(config.getEffectiveCreditRate())
                    .effectiveDebitRate(config.getEffectiveDebitRate())
                    .dayCountConvention(config.getDayCountConvention())
                    .resolutionLevel(determineResolutionLevel(config))
                    .build()
            )))
            .orElse(ResponseEntity.ok(ApiResponse.success(null, "No effective rate found")));
    }

    // ========================================================================
    // INTEREST CALCULATION
    // ========================================================================

    /**
     * Calculate interest for a balance.
     */
    @PostMapping("/calculate")
    @Operation(summary = "Calculate interest",
               description = "Calculate interest amount for a given balance and period")
    public ResponseEntity<ApiResponse<CalculationResult>> calculateInterest(
            @Valid @RequestBody CalculateInterestRequest request) {
        
        log.info("POST /calculate configId={} balance={} days={}", 
                 request.getConfigId(), request.getBalance(), request.getDays());
        
        InterestConfiguration config = service.getById(request.getConfigId());
        
        int days = request.getDays() != null ? request.getDays() : 
            (request.getFromDate() != null && request.getToDate() != null ?
                (int) java.time.temporal.ChronoUnit.DAYS.between(request.getFromDate(), request.getToDate()) : 1);
        
        BigDecimal dailyInterest = service.calculateDailyInterest(config, request.getBalance());
        BigDecimal totalInterest = service.calculateInterestForPeriod(config, request.getBalance(), days);
        
        boolean isCredit = request.getBalance() != null && 
                          request.getBalance().compareTo(BigDecimal.ZERO) > 0;
        
        CalculationResult result = CalculationResult.builder()
            .configId(config.getId())
            .configName(config.getConfigName())
            .balance(request.getBalance())
            .isCredit(isCredit)
            .effectiveRate(isCredit ? config.getEffectiveCreditRate() : config.getEffectiveDebitRate())
            .days(days)
            .dayCountConvention(config.getDayCountConvention())
            .dayCountBasis(config.getDayCountBasis())
            .dailyInterest(dailyInterest)
            .totalInterest(totalInterest)
            .calculatedAt(LocalDate.now())
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * Quick calculate using effective config.
     */
    @GetMapping("/calculate/quick")
    @Operation(summary = "Quick interest calculation",
               description = "Calculate interest using effective rate for a target")
    public ResponseEntity<ApiResponse<CalculationResult>> quickCalculate(
            @RequestParam UUID corporateId,
            @RequestParam UUID targetId,
            @RequestParam String currency,
            @RequestParam @NotNull BigDecimal balance,
            @RequestParam(defaultValue = "30") int days) {
        
        log.info("GET /calculate/quick target={} balance={} days={}", targetId, balance, days);
        
        var configOpt = service.getEffectiveInternalConfig(corporateId, targetId, currency);
        
        if (configOpt.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.success(null, 
                "No effective rate found. Create an internal rate configuration first."));
        }
        
        InterestConfiguration config = configOpt.get();
        BigDecimal dailyInterest = service.calculateDailyInterest(config, balance);
        BigDecimal totalInterest = service.calculateInterestForPeriod(config, balance, days);
        
        boolean isCredit = balance.compareTo(BigDecimal.ZERO) > 0;
        
        CalculationResult result = CalculationResult.builder()
            .configId(config.getId())
            .configName(config.getConfigName())
            .balance(balance)
            .isCredit(isCredit)
            .effectiveRate(isCredit ? config.getEffectiveCreditRate() : config.getEffectiveDebitRate())
            .days(days)
            .dayCountConvention(config.getDayCountConvention())
            .dayCountBasis(config.getDayCountBasis())
            .dailyInterest(dailyInterest)
            .totalInterest(totalInterest)
            .calculatedAt(LocalDate.now())
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ========================================================================
    // SPREAD ANALYSIS
    // ========================================================================

    /**
     * Get spread analysis (treasury margin).
     */
    @GetMapping("/analysis/spread")
    @Operation(summary = "Get spread analysis",
               description = "Analyze treasury margin (difference between external and internal rates)")
    public ResponseEntity<ApiResponse<SpreadAnalysisResponse>> getSpreadAnalysis(
            @RequestParam UUID corporateId,
            @RequestParam UUID targetId,
            @RequestParam String currency) {
        
        log.info("GET /analysis/spread corporate={} target={} currency={}", 
                 corporateId, targetId, currency);
        
        SpreadAnalysis analysis = service.calculateTreasurySpread(corporateId, targetId, currency);
        
        SpreadAnalysisResponse response = SpreadAnalysisResponse.builder()
            .targetId(analysis.getTargetId())
            .currency(analysis.getCurrency())
            .externalCreditRate(analysis.getExternalCreditRate())
            .externalDebitRate(analysis.getExternalDebitRate())
            .internalCreditRate(analysis.getInternalCreditRate())
            .internalDebitRate(analysis.getInternalDebitRate())
            .creditSpread(analysis.getCreditSpread())
            .debitSpread(analysis.getDebitSpread())
            .treasuryCreditMargin(analysis.getCreditSpread() != null ? 
                analysis.getCreditSpread().abs() : null)
            .treasuryDebitMargin(analysis.getDebitSpread() != null ? 
                analysis.getDebitSpread().abs() : null)
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Get statistics for corporate.
     */
    @GetMapping("/statistics/{corporateId}")
    @Operation(summary = "Get interest statistics",
               description = "Get summary statistics of interest configurations")
    public ResponseEntity<ApiResponse<InterestConfigurationDto.Statistics>> getStatistics(
            @PathVariable UUID corporateId) {
        
        log.info("GET /statistics/{}", corporateId);
        
        InterestConfigurationDto.Statistics stats = service.getStatistics(corporateId);
        return ResponseEntity.ok(ApiResponse.success(stats));
    }


    // ============================================================================
// ADD THESE ENDPOINTS TO InterestConfigurationController.java
// Location: backend/src/main/java/com/bank/vam/controller/credit/InterestConfigurationController.java
// ============================================================================
// 
// Add these methods to the existing controller class.
// These endpoints are called by CreditLimitsPage.tsx for entity interest rates.
//

    // ========================================================================
    // ENTITY-SPECIFIC INTEREST CONFIGURATIONS (Called by CreditLimitsPage)
    // ========================================================================

    /**
     * Get external interest configuration for a specific entity.
     * Called by CreditLimitsPage when loading entity interest rates.
     * 
     * Endpoint: GET /api/v1/interest-configs/entity/{entityId}/external
     */
    @GetMapping("/entity/{entityId}/external")
    @Operation(summary = "Get external interest config for entity",
               description = "Get bank-provided interest rate for a specific legal entity")
    public ResponseEntity<ApiResponse<InterestConfigurationDto.Response>> getExternalConfigForEntity(
            @PathVariable UUID entityId) {
        
        log.info("GET /entity/{}/external - Fetching external interest config for entity", entityId);
        
        try {
            return service.getExternalConfigForTarget(entityId, null)
                .map(config -> ResponseEntity.ok(ApiResponse.success(toResponse(config))))
                .orElse(ResponseEntity.ok(ApiResponse.success(null, "No external interest config found for entity")));
        } catch (Exception e) {
            log.error("Error fetching external interest config for entity {}: {}", entityId, e.getMessage());
            return ResponseEntity.ok(ApiResponse.success(null, "Error: " + e.getMessage()));
        }
    }

    /**
     * Get internal interest configuration for a specific entity.
     * 
     * Endpoint: GET /api/v1/interest-configs/entity/{entityId}/internal
     */
    @GetMapping("/entity/{entityId}/internal")
    @Operation(summary = "Get internal interest config for entity",
               description = "Get treasury transfer pricing for a specific legal entity")
    public ResponseEntity<ApiResponse<InterestConfigurationDto.Response>> getInternalConfigForEntity(
            @PathVariable UUID entityId) {
        
        log.info("GET /entity/{}/internal - Fetching internal interest config for entity", entityId);
        
        try {
            return service.getInternalConfigForTarget(entityId, null)
                .map(config -> ResponseEntity.ok(ApiResponse.success(toResponse(config))))
                .orElse(ResponseEntity.ok(ApiResponse.success(null, "No internal interest config found for entity")));
        } catch (Exception e) {
            log.error("Error fetching internal interest config for entity {}: {}", entityId, e.getMessage());
            return ResponseEntity.ok(ApiResponse.success(null, "Error: " + e.getMessage()));
        }
    }

    /**
     * Get all interest configurations for a specific entity (both external and internal).
     * 
     * Endpoint: GET /api/v1/interest-configs/entity/{entityId}
     */
    @GetMapping("/entity/{entityId}")
    @Operation(summary = "Get all interest configs for entity",
               description = "Get both external and internal interest configurations for a legal entity")
    public ResponseEntity<ApiResponse<List<InterestConfigurationDto.Response>>> getConfigsForEntity(
            @PathVariable UUID entityId) {
        
        log.info("GET /entity/{} - Fetching all interest configs for entity", entityId);
        
        try {
            List<InterestConfiguration> configs = service.findByTarget(entityId);
            List<InterestConfigurationDto.Response> responses = configs.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
            return ResponseEntity.ok(ApiResponse.success(responses));
        } catch (Exception e) {
            log.error("Error fetching interest configs for entity {}: {}", entityId, e.getMessage());
            return ResponseEntity.ok(ApiResponse.success(List.of(), "Error: " + e.getMessage()));
        }
    }

    /**
     * Get internal configurations for a corporate (treasury rates).
     * 
     * Endpoint: GET /api/v1/interest-configs/corporate/{corporateId}/internal
     */
    @GetMapping("/corporate/{corporateId}/internal")
    @Operation(summary = "Get internal interest configs for corporate",
               description = "Get all internal (treasury) interest configurations for a corporate")
    public ResponseEntity<ApiResponse<List<InterestConfigurationDto.Response>>> getInternalConfigsForCorporate(
            @PathVariable UUID corporateId) {
        
        log.info("GET /corporate/{}/internal - Fetching internal interest configs", corporateId);
        
        try {
            List<InterestConfiguration> configs = service.getActiveInternalConfigs(corporateId);
            List<InterestConfigurationDto.Response> responses = configs.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
            return ResponseEntity.ok(ApiResponse.success(responses));
        } catch (Exception e) {
            log.error("Error fetching internal interest configs for corporate {}: {}", corporateId, e.getMessage());
            return ResponseEntity.ok(ApiResponse.success(List.of(), "Error: " + e.getMessage()));
        }
    }

    /**
     * Get external configurations for a corporate (bank rates).
     * 
     * Endpoint: GET /api/v1/interest-configs/corporate/{corporateId}/external
     */
    @GetMapping("/corporate/{corporateId}/external")
    @Operation(summary = "Get external interest configs for corporate",
               description = "Get all external (bank) interest configurations for a corporate")
    public ResponseEntity<ApiResponse<List<InterestConfigurationDto.Response>>> getExternalConfigsForCorporate(
            @PathVariable UUID corporateId) {
        
        log.info("GET /corporate/{}/external - Fetching external interest configs", corporateId);
        
        try {
            List<InterestConfiguration> configs = service.getActiveExternalConfigs(corporateId);
            List<InterestConfigurationDto.Response> responses = configs.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
            return ResponseEntity.ok(ApiResponse.success(responses));
        } catch (Exception e) {
            log.error("Error fetching external interest configs for corporate {}: {}", corporateId, e.getMessage());
            return ResponseEntity.ok(ApiResponse.success(List.of(), "Error: " + e.getMessage()));
        }
    }
    // ========================================================================
    // MAPPING METHODS
    // ========================================================================

    private InterestConfigurationDto.Response toResponse(InterestConfiguration config) {
        return InterestConfigurationDto.Response.builder()
            .id(config.getId())
            .corporateId(config.getCorporateId())
            .configName(config.getConfigName())
            .externalReference(config.getExternalReference())
            .configType(config.getConfigType())
            .targetId(config.getTargetId())
            .targetType(config.getTargetType())
            .currencyCode(config.getCurrencyCode())
            .creditBaseRateType(config.getCreditBaseRateType())
            .creditBaseRate(config.getCreditBaseRate())
            .creditSpread(config.getCreditSpread())
            .effectiveCreditRate(config.getEffectiveCreditRate())
            .creditMinBalance(config.getCreditMinBalance())
            .debitBaseRateType(config.getDebitBaseRateType())
            .debitBaseRate(config.getDebitBaseRate())
            .debitSpread(config.getDebitSpread())
            .effectiveDebitRate(config.getEffectiveDebitRate())
            .penaltyRate(config.getPenaltyRate())
            .dayCountConvention(config.getDayCountConvention())
            .compoundingFrequency(config.getCompoundingFrequency())
            .calculationFrequency(config.getCalculationFrequency())
            .postingFrequency(config.getPostingFrequency())
            .isTiered(config.getIsTiered())
            .tierConfig(config.getTierConfig())
            .effectiveFrom(config.getEffectiveFrom())
            .effectiveTo(config.getEffectiveTo())
            .status(config.getStatus())
            .sourceSystem(config.getSourceSystem())
            .lastSyncAt(config.getLastSyncAt())
            .createdAt(config.getCreatedAt())
            .updatedAt(config.getUpdatedAt())
            .build();
    }

    private ExternalRateResponse toExternalResponse(InterestConfiguration config) {
        return ExternalRateResponse.builder()
            .id(config.getId())
            .configName(config.getConfigName())
            .externalReference(config.getExternalReference())
            .targetId(config.getTargetId())
            .targetType(config.getTargetType())
            .currency(config.getCurrencyCode())
            .baseRateType(config.getCreditBaseRateType())
            .baseRateValue(config.getCreditBaseRate())
            .bankSpread(config.getCreditSpread())
            .effectiveCreditRate(config.getEffectiveCreditRate())
            .effectiveDebitRate(config.getEffectiveDebitRate())
            .penaltyRate(config.getPenaltyRate())
            .dayCountConvention(config.getDayCountConvention())
            .effectiveFrom(config.getEffectiveFrom())
            .effectiveTo(config.getEffectiveTo())
            .status(config.getStatus())
            .sourceSystem(config.getSourceSystem())
            .lastSyncAt(config.getLastSyncAt())
            .readOnly(true)
            .readOnlyReason("Bank rates cannot be modified. Contact bank for changes.")
            .build();
    }

    private InternalRateResponse toInternalResponse(InterestConfiguration config) {
        return InternalRateResponse.builder()
            .id(config.getId())
            .configName(config.getConfigName())
            .corporateId(config.getCorporateId())
            .targetId(config.getTargetId())
            .targetType(config.getTargetType())
            .currency(config.getCurrencyCode())
            // Base rates (from external)
            .baseRateType(config.getCreditBaseRateType())
            .creditBaseRate(config.getCreditBaseRate())
            .debitBaseRate(config.getDebitBaseRate())
            // Spreads (treasury adjustments)
            .creditSpread(config.getCreditSpread())
            .debitSpread(config.getDebitSpread())
            // Effective rates (calculated)
            .effectiveCreditRate(config.getEffectiveCreditRate())
            .effectiveDebitRate(config.getEffectiveDebitRate())
            // Additional settings
            .creditMinBalance(config.getCreditMinBalance())
            .penaltyRate(config.getPenaltyRate())
            // Calculation parameters
            .dayCountConvention(config.getDayCountConvention())
            .compoundingFrequency(config.getCompoundingFrequency())
            .calculationFrequency(config.getCalculationFrequency())
            .postingFrequency(config.getPostingFrequency())
            // Validity
            .effectiveFrom(config.getEffectiveFrom())
            .effectiveTo(config.getEffectiveTo())
            .status(config.getStatus())
            .createdAt(config.getCreatedAt())
            .updatedAt(config.getUpdatedAt())
            .build();
    }

    private String determineResolutionLevel(InterestConfiguration config) {
        if (config.getTargetType() == TargetType.CORPORATE) {
            return "CORPORATE_DEFAULT";
        } else if (config.getTargetType() == TargetType.CURRENCY) {
            return "CURRENCY_DEFAULT";
        } else {
            return "TARGET_SPECIFIC";
        }
    }

    // ========================================================================
    // REQUEST DTOs
    // ========================================================================

    @Data
    public static class CreateInternalConfigRequest {
        @NotNull
        private UUID corporateId;
        private UUID targetId;
        @NotNull
        private TargetType targetType;
        @NotBlank
        private String currency;
        private String configName;
        private String baseRateType;
        @NotNull
        private BigDecimal creditSpread; // Typically ≤ 0 (treasury pays less)
        @NotNull
        private BigDecimal debitSpread;  // Typically ≥ 0 (treasury charges more)
    }

    @Data
    public static class CreateCorporateDefaultRequest {
        @NotNull
        private UUID corporateId;
        @NotBlank
        private String currency;
        private String configName;
        private String baseRateType;
        @NotNull
        private BigDecimal creditSpread;
        @NotNull
        private BigDecimal debitSpread;
    }

    @Data
    public static class CreateCurrencyDefaultRequest {
        @NotNull
        private UUID corporateId;
        @NotBlank
        private String currency;
        private String configName;
        private String baseRateType;
        @NotNull
        private BigDecimal creditSpread;
        @NotNull
        private BigDecimal debitSpread;
    }

    @Data
    public static class UpdateSpreadsRequest {
        @NotNull
        private BigDecimal creditSpread;
        @NotNull
        private BigDecimal debitSpread;
    }

    @Data
    public static class UpdateFrequenciesRequest {
        private CalculationFrequency calculationFrequency;
        private PostingFrequency postingFrequency;
        private CompoundingFrequency compoundingFrequency;
    }

    @Data
    public static class CalculateInterestRequest {
        @NotNull
        private UUID configId;
        @NotNull
        private BigDecimal balance;
        private Integer days;
        private LocalDate fromDate;
        private LocalDate toDate;
    }

    // ========================================================================
    // RESPONSE DTOs
    // ========================================================================

    @Data
    @Builder
    public static class ExternalRateResponse {
        private UUID id;
        private String configName;
        private String externalReference;
        private UUID targetId;
        private TargetType targetType;
        private String currency;
        // Rates
        private String baseRateType;
        private BigDecimal baseRateValue;
        private BigDecimal bankSpread;
        private BigDecimal effectiveCreditRate;
        private BigDecimal effectiveDebitRate;
        private BigDecimal penaltyRate;
        // Calculation
        private String dayCountConvention;
        // Validity
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
        private ConfigStatus status;
        private String sourceSystem;
        private LocalDateTime lastSyncAt;
        // Read-only indicator
        private boolean readOnly;
        private String readOnlyReason;
    }

    @Data
    @Builder
    public static class InternalRateResponse {
        private UUID id;
        private String configName;
        private UUID corporateId;
        private UUID targetId;
        private TargetType targetType;
        private String currency;
        // Base rates (from external or absolute)
        private String baseRateType;
        private BigDecimal creditBaseRate;
        private BigDecimal debitBaseRate;
        // Treasury spreads
        private BigDecimal creditSpread;
        private BigDecimal debitSpread;
        // Effective rates (calculated)
        private BigDecimal effectiveCreditRate;
        private BigDecimal effectiveDebitRate;
        // Additional settings
        private BigDecimal creditMinBalance;
        private BigDecimal penaltyRate;
        // Calculation parameters
        private String dayCountConvention;
        private CompoundingFrequency compoundingFrequency;
        private CalculationFrequency calculationFrequency;
        private PostingFrequency postingFrequency;
        // Validity
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
        private ConfigStatus status;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    @Builder
    public static class EffectiveRateResponse {
        private UUID configId;
        private String configName;
        private ConfigType configType;
        private TargetType targetType;
        private String currency;
        private BigDecimal effectiveCreditRate;
        private BigDecimal effectiveDebitRate;
        private String dayCountConvention;
        private String resolutionLevel; // TARGET_SPECIFIC, CURRENCY_DEFAULT, CORPORATE_DEFAULT
    }

    @Data
    @Builder
    public static class CalculationResult {
        private UUID configId;
        private String configName;
        private BigDecimal balance;
        private boolean isCredit;
        private BigDecimal effectiveRate;
        private int days;
        private String dayCountConvention;
        private int dayCountBasis;
        private BigDecimal dailyInterest;
        private BigDecimal totalInterest;
        private LocalDate calculatedAt;
    }

    @Data
    @Builder
    public static class SpreadAnalysisResponse {
        private UUID targetId;
        private String currency;
        // External (bank) rates
        private BigDecimal externalCreditRate;
        private BigDecimal externalDebitRate;
        // Internal (treasury) rates
        private BigDecimal internalCreditRate;
        private BigDecimal internalDebitRate;
        // Spreads
        private BigDecimal creditSpread;
        private BigDecimal debitSpread;
        // Treasury margin (absolute value)
        private BigDecimal treasuryCreditMargin;
        private BigDecimal treasuryDebitMargin;
    }
}