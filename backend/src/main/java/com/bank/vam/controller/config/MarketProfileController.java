package com.bank.vam.controller.config;

import com.bank.vam.config.MarketProfile;
import com.bank.vam.config.MarketProfileProperties;
import com.bank.vam.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Exposes the active {@link MarketProfile} to the frontend so the UI can
 * default currency, locale, country, etc. without a separate /config call
 * per concern.
 */
@RestController
@RequestMapping("/api/v1/config")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "Configuration", description = "Runtime configuration endpoints (market profile, etc.)")
public class MarketProfileController {

    private final MarketProfileProperties marketProfile;

    @GetMapping("/market-profile")
    @Operation(summary = "Get the active market profile",
               description = "Currency, country, locale, timezone, base rate, and suggested currency list for this deployment.")
    public ResponseEntity<ApiResponse<MarketProfileResponse>> getActiveProfile() {
        MarketProfileResponse body = MarketProfileResponse.builder()
                .code(marketProfile.getActiveProfileCode())
                .displayName(marketProfile.getActiveDisplayName())
                .defaultCurrency(marketProfile.getDefaultCurrency())
                .defaultCountryCode(marketProfile.getDefaultCountryCode())
                .defaultLocale(marketProfile.getDefaultLocale())
                .defaultTimezone(marketProfile.getDefaultTimezone())
                .defaultBaseRateType(marketProfile.getDefaultBaseRateType())
                .weekend(marketProfile.getActiveWeekend().name())
                .homeBankBic(marketProfile.getActiveHomeBankBic())
                .homeBankName(marketProfile.getActiveHomeBankName())
                .ibanCountryCode(marketProfile.getActiveIbanCountryCode())
                .suggestedCurrencies(marketProfile.getActiveSuggestedCurrencies())
                .build();
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    @Data
    @Builder
    public static class MarketProfileResponse {
        private String code;
        private String displayName;
        private String defaultCurrency;
        private String defaultCountryCode;
        private String defaultLocale;
        private String defaultTimezone;
        private String defaultBaseRateType;
        private String weekend;
        private String homeBankBic;
        private String homeBankName;
        private String ibanCountryCode;
        private List<String> suggestedCurrencies;
    }
}
