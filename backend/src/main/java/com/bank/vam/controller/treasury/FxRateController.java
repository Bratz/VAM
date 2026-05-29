package com.bank.vam.controller.treasury;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.entity.treasury.FxRate;
import com.bank.vam.entity.treasury.FxRate.RateSource;
import com.bank.vam.entity.treasury.FxRate.RateType;
import com.bank.vam.service.treasury.FxRateService;
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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * FX Rate Controller - Foreign Exchange Rate Management APIs.
 * 
 * UNIFIED ARCHITECTURE v4.2:
 * Provides APIs for:
 * - Rate lookup and conversion
 * - Rate management (create, update, deactivate)
 * - Historical rates
 * - Cache management
 * - Currency pair discovery
 * 
 * FIXED v5.1.1:
 * - Maps frontend SPOT/FORWARD types to backend BID/ASK/MID types
 * - Fixed to work with corrected FxRate entity
 * - Maintains backward compatibility with frontend
 * - Returns only LATEST rates per currency pair (not all historical)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/treasury/fx-rates")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "FX Rates", description = "Foreign Exchange Rate Management APIs")
public class FxRateController {

    private final FxRateService fxRateService;

    // ========================================================================
    // RATE TYPE MAPPING (Frontend to Backend)
    // ========================================================================
    
    /**
     * Map frontend rate type strings to backend RateType enum.
     * Frontend uses: SPOT, FORWARD, FIXING, INTERNAL, CONTRACT, INDICATIVE
     * Backend uses: BID, ASK, MID
     */
    private RateType mapToBackendRateType(String frontendType) {
        if (frontendType == null || frontendType.isBlank()) {
            return RateType.MID;
        }
        
        String upper = frontendType.toUpperCase().trim();
        return switch (upper) {
            case "BID" -> RateType.BID;
            case "ASK" -> RateType.ASK;
            case "SPOT", "FORWARD", "FIXING", "INTERNAL", "CONTRACT", "INDICATIVE", "MID" -> RateType.MID;
            default -> RateType.MID;
        };
    }
    
    /**
     * Map backend RateType to frontend string for response.
     */
    private String mapToFrontendRateType(RateType backendType) {
        if (backendType == null) {
            return "MID";
        }
        return switch (backendType) {
            case MID -> "SPOT";
            case BID -> "BID";
            case ASK -> "ASK";
        };
    }
    
    /**
     * Map frontend source strings to backend RateSource enum.
     */
    private RateSource mapToBackendSource(String frontendSource) {
        if (frontendSource == null || frontendSource.isBlank()) {
            return RateSource.MANUAL;
        }
        
        String upper = frontendSource.toUpperCase().trim();
        return switch (upper) {
            case "REUTERS" -> RateSource.REUTERS;
            case "BLOOMBERG" -> RateSource.BLOOMBERG;
            case "CENTRAL_BANK", "ECB", "FED", "BOE", "BOJ" -> RateSource.CENTRAL_BANK;
            case "INTERNAL" -> RateSource.INTERNAL;
            case "CBS", "SWIFT", "API", "MANUAL" -> RateSource.MANUAL;
            default -> RateSource.MANUAL;
        };
    }
    
    /**
     * Map backend RateSource to frontend string for response.
     */
    private String mapToFrontendSource(RateSource backendSource) {
        if (backendSource == null) {
            return "MANUAL";
        }
        return backendSource.name();
    }

    // ========================================================================
    // RATE LOOKUP APIs
    // ========================================================================

    /**
     * Get exchange rate for currency pair.
     */
    @GetMapping("/rate")
    @Operation(summary = "Get exchange rate",
               description = "Get the current exchange rate for a currency pair")
    public ResponseEntity<ApiResponse<RateResponse>> getRate(
            @Parameter(description = "Source currency (e.g., EUR)")
            @RequestParam @NotBlank String fromCurrency,
            @Parameter(description = "Target currency (e.g., AED)")
            @RequestParam @NotBlank String toCurrency,
            @Parameter(description = "Rate type (SPOT, BID, ASK, MID)")
            @RequestParam(defaultValue = "SPOT") String rateType) {
        
        log.info("GET /api/v1/treasury/fx-rates/rate - {}/{} ({})", fromCurrency, toCurrency, rateType);
        
        RateType backendType = mapToBackendRateType(rateType);
        BigDecimal rate = fxRateService.getRate(fromCurrency.toUpperCase(), toCurrency.toUpperCase(), backendType);
        
        RateResponse response = RateResponse.builder()
            .fromCurrency(fromCurrency.toUpperCase())
            .toCurrency(toCurrency.toUpperCase())
            .rate(rate)
            .rateType(rateType)
            .timestamp(LocalDateTime.now())
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Convert amount from one currency to another.
     */
    @GetMapping("/convert")
    @Operation(summary = "Convert currency",
               description = "Convert an amount from one currency to another using latest rates")
    public ResponseEntity<ApiResponse<ConversionResponse>> convert(
            @Parameter(description = "Amount to convert")
            @RequestParam @Positive BigDecimal amount,
            @Parameter(description = "Source currency")
            @RequestParam @NotBlank String fromCurrency,
            @Parameter(description = "Target currency")
            @RequestParam @NotBlank String toCurrency,
            @Parameter(description = "Rate type to use")
            @RequestParam(defaultValue = "SPOT") String rateType) {
        
        log.info("GET /api/v1/treasury/fx-rates/convert - {} {} -> {} ({})", 
                 amount, fromCurrency, toCurrency, rateType);
        
        RateType backendType = mapToBackendRateType(rateType);
        BigDecimal rate = fxRateService.getRate(fromCurrency.toUpperCase(), toCurrency.toUpperCase(), backendType);
        BigDecimal convertedAmount = fxRateService.convert(amount, fromCurrency.toUpperCase(), toCurrency.toUpperCase(), backendType);
        
        ConversionResponse response = ConversionResponse.builder()
            .originalAmount(amount)
            .fromCurrency(fromCurrency.toUpperCase())
            .toCurrency(toCurrency.toUpperCase())
            .rate(rate)
            .convertedAmount(convertedAmount)
            .rateType(rateType)
            .timestamp(LocalDateTime.now())
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Get rate with full details (entity info).
     */
    @GetMapping("/details")
    @Operation(summary = "Get rate with details",
               description = "Get the full FX rate entity including metadata, bid/ask spread, etc.")
    public ResponseEntity<ApiResponse<FxRateDto>> getRateDetails(
            @RequestParam @NotBlank String fromCurrency,
            @RequestParam @NotBlank String toCurrency,
            @RequestParam(defaultValue = "SPOT") String rateType) {
        
        RateType backendType = mapToBackendRateType(rateType);
        
        return fxRateService.getFxRate(fromCurrency.toUpperCase(), toCurrency.toUpperCase(), backendType)
            .map(rate -> ResponseEntity.ok(ApiResponse.success(toFxRateDto(rate))))
            .orElse(ResponseEntity.ok(ApiResponse.error("Rate not found")));
    }

    // ========================================================================
    // RATE MANAGEMENT APIs
    // ========================================================================

    /**
     * Get all active rates (LATEST per currency pair only).
     * This is the main endpoint for the frontend grid view.
     * 
     * Returns only the MOST RECENT active rate for each currency pair,
     * not all historical rates.
     */
    @GetMapping
    @Operation(summary = "Get all active rates",
               description = "Returns the latest active FX rate for each currency pair")
    public ResponseEntity<ApiResponse<List<FxRateDto>>> getAllActiveRates(
            @Parameter(description = "Optional corporate ID to include corporate-specific rates")
            @RequestParam(required = false) UUID corporateId) {
        
        log.info("GET /api/v1/treasury/fx-rates (corporateId={})", corporateId);
        
        // Get all active rates
        List<FxRate> allRates = fxRateService.getAllActiveRates();
        
        // Filter to only include:
        // 1. Global rates (corporate_id IS NULL)
        // 2. Corporate-specific rates (if corporateId provided)
        List<FxRate> filteredRates = allRates.stream()
            .filter(rate -> {
                // Include global rates
                if (rate.getCorporateId() == null) {
                    return true;
                }
                // Include corporate-specific rates if corporateId matches
                if (corporateId != null && corporateId.equals(rate.getCorporateId())) {
                    return true;
                }
                return false;
            })
            .collect(Collectors.toList());
        
        // Get only the LATEST rate per currency pair
        // Key: "FROM/TO" -> Latest rate
        Map<String, FxRate> latestRatesMap = new LinkedHashMap<>();
        
        for (FxRate rate : filteredRates) {
            String key = rate.getFromCurrency() + "/" + rate.getToCurrency();
            FxRate existing = latestRatesMap.get(key);
            
            if (existing == null) {
                latestRatesMap.put(key, rate);
            } else {
                // Keep the more recent one
                if (isMoreRecent(rate, existing)) {
                    latestRatesMap.put(key, rate);
                }
            }
        }
        
        // Convert to DTOs
        List<FxRateDto> dtos = latestRatesMap.values().stream()
            .map(this::toFxRateDto)
            .sorted(Comparator.comparing(FxRateDto::getFromCurrency)
                             .thenComparing(FxRateDto::getToCurrency))
            .collect(Collectors.toList());
        
        log.info("Returning {} latest FX rates", dtos.size());
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }
    
    /**
     * Check if rate1 is more recent than rate2.
     */
    private boolean isMoreRecent(FxRate rate1, FxRate rate2) {
        // Compare by date first
        if (rate1.getRateDate() == null) return false;
        if (rate2.getRateDate() == null) return true;
        
        int dateCompare = rate1.getRateDate().compareTo(rate2.getRateDate());
        if (dateCompare != 0) {
            return dateCompare > 0;
        }
        
        // Same date - compare by time
        if (rate1.getRateTime() == null) return false;
        if (rate2.getRateTime() == null) return true;
        
        return rate1.getRateTime().compareTo(rate2.getRateTime()) > 0;
    }

    /**
     * Get rates for a specific date.
     */
    @GetMapping("/by-date")
    @Operation(summary = "Get rates by date",
               description = "Get all rates effective on a specific date")
    public ResponseEntity<ApiResponse<List<FxRateDto>>> getRatesByDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        
        log.info("GET /api/v1/treasury/fx-rates/by-date - {}", date);
        List<FxRate> rates = fxRateService.getRatesForDate(date);
        List<FxRateDto> dtos = rates.stream().map(this::toFxRateDto).toList();
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    /**
     * Get rates by source.
     */
    @GetMapping("/by-source/{source}")
    @Operation(summary = "Get rates by source",
               description = "Get all rates from a specific source (REUTERS, BLOOMBERG, etc.)")
    public ResponseEntity<ApiResponse<List<FxRateDto>>> getRatesBySource(
            @PathVariable String source) {
        
        log.info("GET /api/v1/treasury/fx-rates/by-source/{}", source);
        RateSource backendSource = mapToBackendSource(source);
        List<FxRate> rates = fxRateService.getRatesBySource(backendSource);
        List<FxRateDto> dtos = rates.stream().map(this::toFxRateDto).toList();
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    /**
     * Create a new spot rate.
     */
    @PostMapping("/spot")
    @Operation(summary = "Create spot rate",
               description = "Create a new spot exchange rate")
    public ResponseEntity<ApiResponse<FxRateDto>> createSpotRate(
            @Valid @RequestBody CreateRateRequest request) {
        
        log.info("POST /api/v1/treasury/fx-rates/spot - {}/{} @ {}", 
                 request.getFromCurrency(), request.getToCurrency(), request.getRate());
        
        RateSource source = mapToBackendSource(request.getSource());
        
        FxRate rate = fxRateService.createSpotRate(
            request.getFromCurrency().toUpperCase(),
            request.getToCurrency().toUpperCase(),
            request.getRate(),
            source
        );
        
        return ResponseEntity.ok(ApiResponse.success(toFxRateDto(rate), "Spot rate created"));
    }

    /**
     * Create a fixing rate.
     */
    @PostMapping("/fixing")
    @Operation(summary = "Create fixing rate",
               description = "Create a daily fixing rate (official published rate)")
    public ResponseEntity<ApiResponse<FxRateDto>> createFixingRate(
            @Valid @RequestBody CreateFixingRateRequest request) {
        
        log.info("POST /api/v1/treasury/fx-rates/fixing - {}/{} @ {} for {}", 
                 request.getFromCurrency(), request.getToCurrency(), request.getRate(), request.getFixingDate());
        
        RateSource source = mapToBackendSource(request.getSource());
        
        FxRate rate = fxRateService.createFixingRate(
            request.getFromCurrency().toUpperCase(),
            request.getToCurrency().toUpperCase(),
            request.getRate(),
            request.getFixingDate(),
            source
        );
        
        return ResponseEntity.ok(ApiResponse.success(toFxRateDto(rate), "Fixing rate created"));
    }

    /**
     * Create an internal rate (for corporate transfer pricing).
     */
    @PostMapping("/internal")
    @Operation(summary = "Create internal rate",
               description = "Create an internal transfer pricing rate for a corporate")
    public ResponseEntity<ApiResponse<FxRateDto>> createInternalRate(
            @Valid @RequestBody CreateInternalRateRequest request) {
        
        log.info("POST /api/v1/treasury/fx-rates/internal - {}/{} @ {} for corporate {}", 
                 request.getFromCurrency(), request.getToCurrency(), request.getRate(), request.getCorporateId());
        
        FxRate rate = fxRateService.createInternalRate(
            request.getFromCurrency().toUpperCase(),
            request.getToCurrency().toUpperCase(),
            request.getRate(),
            request.getCorporateId(),
            request.getSpreadBps()
        );
        
        return ResponseEntity.ok(ApiResponse.success(toFxRateDto(rate), "Internal rate created"));
    }

    /**
     * Update an existing rate.
     */
    @PutMapping("/{rateId}")
    @Operation(summary = "Update rate",
               description = "Update an existing FX rate value")
    public ResponseEntity<ApiResponse<FxRateDto>> updateRate(
            @PathVariable UUID rateId,
            @Valid @RequestBody UpdateRateRequest request) {
        
        log.info("PUT /api/v1/treasury/fx-rates/{} - new rate: {}", rateId, request.getRate());
        
        FxRate rate = fxRateService.updateRate(rateId, request.getRate());
        return ResponseEntity.ok(ApiResponse.success(toFxRateDto(rate), "Rate updated"));
    }

    /**
     * Deactivate a rate.
     */
    @DeleteMapping("/{rateId}")
    @Operation(summary = "Deactivate rate",
               description = "Deactivate an FX rate (soft delete)")
    public ResponseEntity<ApiResponse<Void>> deactivateRate(@PathVariable UUID rateId) {
        log.info("DELETE /api/v1/treasury/fx-rates/{}", rateId);
        fxRateService.deactivateRate(rateId);
        return ResponseEntity.ok(ApiResponse.success(null, "Rate deactivated"));
    }

    // ========================================================================
    // CACHE MANAGEMENT
    // ========================================================================

    /**
     * Refresh rate cache.
     */
    @PostMapping("/cache/refresh")
    @Operation(summary = "Refresh cache",
               description = "Clear and refresh the FX rate cache")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refreshCache() {
        log.info("POST /api/v1/treasury/fx-rates/cache/refresh");
        fxRateService.refreshCache();
        return ResponseEntity.ok(ApiResponse.success(
            Map.of("refreshedAt", LocalDateTime.now(), "status", "Cache refreshed"),
            "Cache refreshed successfully"
        ));
    }

    // ========================================================================
    // CURRENCY DISCOVERY
    // ========================================================================

    /**
     * Get available currency pairs.
     */
    @GetMapping("/pairs")
    @Operation(summary = "Get currency pairs",
               description = "Get all available currency pairs")
    public ResponseEntity<ApiResponse<List<CurrencyPairDto>>> getCurrencyPairs() {
        log.info("GET /api/v1/treasury/fx-rates/pairs");
        List<String[]> pairs = fxRateService.getAvailablePairs();
        List<CurrencyPairDto> dtos = pairs.stream()
            .map(p -> CurrencyPairDto.builder()
                .fromCurrency(p[0])
                .toCurrency(p[1])
                .pair(p[0] + "/" + p[1])
                .build())
            .toList();
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    /**
     * Get supported currencies.
     */
    @GetMapping("/currencies")
    @Operation(summary = "Get currencies",
               description = "Get all supported currencies")
    public ResponseEntity<ApiResponse<Set<String>>> getCurrencies() {
        log.info("GET /api/v1/treasury/fx-rates/currencies");
        Set<String> currencies = fxRateService.getAvailableCurrencies();
        return ResponseEntity.ok(ApiResponse.success(currencies));
    }

    // ========================================================================
    // HISTORICAL DATA
    // ========================================================================

    /**
     * Get historical rates for a pair.
     */
    @GetMapping("/history")
    @Operation(summary = "Get historical rates",
               description = "Get historical rates for a currency pair within a date range")
    public ResponseEntity<ApiResponse<List<FxRateDto>>> getHistoricalRates(
            @RequestParam @NotBlank String fromCurrency,
            @RequestParam @NotBlank String toCurrency,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        
        log.info("GET /api/v1/treasury/fx-rates/history - {}/{} from {} to {}", 
                 fromCurrency, toCurrency, startDate, endDate);
        
        List<FxRate> rates = fxRateService.getHistoricalRates(
            fromCurrency.toUpperCase(), toCurrency.toUpperCase(), startDate, endDate);
        List<FxRateDto> dtos = rates.stream().map(this::toFxRateDto).toList();
        
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    // ========================================================================
    // DTO CONVERSION
    // ========================================================================

    private FxRateDto toFxRateDto(FxRate rate) {
        return FxRateDto.builder()
            .id(rate.getId())
            .fromCurrency(rate.getFromCurrency())
            .toCurrency(rate.getToCurrency())
            .rate(rate.getRate())
            .bidRate(rate.getBidRate())
            .askRate(rate.getAskRate())
            .inverseRate(rate.getInverseRate())
            .midRate(rate.getMidRate())
            .rateType(mapToFrontendRateType(rate.getRateType()))
            .rateSource(mapToFrontendSource(rate.getRateSource()))
            .rateDate(rate.getRateDate() != null ? rate.getRateDate().toString() : null)
            .rateTimestamp(rate.getRateTimestamp())
            .effectiveFrom(rate.getEffectiveFrom())
            .effectiveTo(rate.getEffectiveTo())
            .isActive(rate.getIsActive())
            .spreadBps(rate.getSpreadBps())
            .corporateId(rate.getCorporateId())
            .programId(rate.getProgramId())
            .createdAt(rate.getCreatedAt())
            .updatedAt(rate.getUpdatedAt())
            .build();
    }

    // ========================================================================
    // DTOs
    // ========================================================================

    @Data
    @Builder
    public static class RateResponse {
        private String fromCurrency;
        private String toCurrency;
        private BigDecimal rate;
        private String rateType;
        private LocalDateTime timestamp;
    }

    @Data
    @Builder
    public static class ConversionResponse {
        private BigDecimal originalAmount;
        private String fromCurrency;
        private String toCurrency;
        private BigDecimal rate;
        private BigDecimal convertedAmount;
        private String rateType;
        private LocalDateTime timestamp;
    }

    @Data
    @Builder
    public static class FxRateDto {
        private UUID id;
        private String fromCurrency;
        private String toCurrency;
        private BigDecimal rate;
        private BigDecimal bidRate;
        private BigDecimal askRate;
        private BigDecimal inverseRate;
        private BigDecimal midRate;
        private String rateType;
        private String rateSource;
        private String rateDate;
        private LocalDateTime rateTimestamp;
        private LocalDateTime effectiveFrom;
        private LocalDateTime effectiveTo;
        private Boolean isActive;
        private BigDecimal spreadBps;
        private UUID corporateId;
        private UUID programId;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    @Builder
    public static class CurrencyPairDto {
        private String fromCurrency;
        private String toCurrency;
        private String pair;
    }

    @Data
    public static class CreateRateRequest {
        @NotBlank
        private String fromCurrency;
        @NotBlank
        private String toCurrency;
        @NotNull
        @Positive
        private BigDecimal rate;
        private String source = "MANUAL";
    }

    @Data
    public static class CreateFixingRateRequest {
        @NotBlank
        private String fromCurrency;
        @NotBlank
        private String toCurrency;
        @NotNull
        @Positive
        private BigDecimal rate;
        @NotNull
        private LocalDate fixingDate;
        private String source = "CENTRAL_BANK";
    }

    @Data
    public static class CreateInternalRateRequest {
        @NotBlank
        private String fromCurrency;
        @NotBlank
        private String toCurrency;
        @NotNull
        @Positive
        private BigDecimal rate;
        @NotNull
        private UUID corporateId;
        private BigDecimal spreadBps;
    }

    @Data
    public static class UpdateRateRequest {
        @NotNull
        @Positive
        private BigDecimal rate;
    }
}