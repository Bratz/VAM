package com.bank.vam.service.treasury;

import com.bank.vam.entity.treasury.FxRate.RateType;
import com.bank.vam.repository.treasury.FxRateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * IHB FX Service - Foreign Exchange Rate Management for In-House Bank.
 *
 * UNIFIED FX ARCHITECTURE (v5.2):
 * This service delegates to FxRateService for all rate lookups, ensuring:
 * - Single source of truth for FX rates
 * - Treasury can manage rates via FX rate management UI
 * - Corporate-specific rates are supported
 * - Full audit trail for rate changes
 * - Consistent rates across Currency Mirrors and IHB operations
 *
 * IHB-SPECIFIC FEATURES:
 * - Treasury spread/markup support for intercompany rates
 * - Rate locking at position creation time
 * - Cross-currency deposit/loan support
 * - Indicative rate preview for UI
 *
 * CROSS-CURRENCY FLOW:
 * 1. Subsidiary A (EUR) deposits surplus to Treasury (AED)
 * 2. FX rate is obtained from FxRateService (with optional treasury spread)
 * 3. Rate is locked at position creation
 * 4. Settlement converts EUR → AED at locked rate
 * 5. Interest accrues in settlement currency (AED)
 *
 * Rate Sources (priority - handled by FxRateService):
 * 1. Corporate-specific rates (if configured)
 * 2. DB-managed rates (cache → DB → inverse → multi-hop)
 * 3. Default fallback rates
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IhbFxService {

    private final FxRateService fxRateService;
    private final FxRateRepository fxRateRepository;

    // ========================================================================
    // CONSTANTS
    // ========================================================================

    /**
     * Standard precision for FX rate calculations (8 decimal places).
     * Matches FxRateService and entity storage precision.
     */
    public static final int RATE_SCALE = 8;

    /**
     * Standard precision for monetary amount conversions (4 decimal places).
     * Unified with FxRateService and CurrencyMirrorService.
     */
    public static final int CONVERSION_SCALE = 4;

    /**
     * Standard rounding mode for all FX operations.
     */
    public static final RoundingMode FX_ROUNDING = RoundingMode.HALF_UP;

    // ========================================================================
    // DATA CLASSES
    // ========================================================================

    /**
     * FX Rate quote with metadata for IHB operations.
     */
    public record FxRate(
        String baseCurrency,
        String quoteCurrency,
        BigDecimal rate,
        BigDecimal inverseRate,
        LocalDate rateDate,
        String source,       // TREASURY, MARKET, DB, FALLBACK, SAME_CURRENCY
        BigDecimal spread,   // Treasury markup/spread in basis points
        LocalDateTime rateTimestamp  // When rate was captured (for staleness check)
    ) {
        public String pair() {
            return baseCurrency + "/" + quoteCurrency;
        }

        /**
         * Check if rate is stale (older than given minutes).
         */
        public boolean isStale(int maxAgeMinutes) {
            if (rateTimestamp == null) return true;
            return rateTimestamp.plusMinutes(maxAgeMinutes).isBefore(LocalDateTime.now());
        }
    }

    /**
     * Conversion result with full details for audit trail.
     */
    public record ConversionResult(
        BigDecimal originalAmount,
        String originalCurrency,
        BigDecimal convertedAmount,
        String convertedCurrency,
        FxRate rateUsed,
        LocalDate conversionDate
    ) {}

    // ========================================================================
    // RATE LOOKUP - Delegating to FxRateService
    // ========================================================================

    /**
     * Get FX rate for a currency pair.
     * Delegates to FxRateService for consistent rate lookup.
     *
     * @param baseCurrency  The currency to convert FROM
     * @param quoteCurrency The currency to convert TO
     * @return FX rate quote with metadata
     */
    public FxRate getRate(String baseCurrency, String quoteCurrency) {
        return getRate(baseCurrency, quoteCurrency, LocalDate.now());
    }

    /**
     * Get FX rate for a currency pair on a specific date.
     * Delegates to FxRateService for consistent rate lookup.
     */
    public FxRate getRate(String baseCurrency, String quoteCurrency, LocalDate rateDate) {
        if (baseCurrency == null || quoteCurrency == null) {
            log.warn("Null currency in rate lookup, returning 1.0");
            return createSameCurrencyRate("???", rateDate);
        }

        String from = baseCurrency.toUpperCase();
        String to = quoteCurrency.toUpperCase();

        if (from.equals(to)) {
            return createSameCurrencyRate(from, rateDate);
        }

        // Delegate to FxRateService - uses cache, DB, inverse, multi-hop, defaults
        BigDecimal rate = fxRateService.getRate(from, to, RateType.MID);
        String source = determineRateSource(from, to);
        LocalDateTime timestamp = getRateTimestamp(from, to);

        BigDecimal inverseRate = BigDecimal.ONE.divide(rate, RATE_SCALE, FX_ROUNDING);

        log.debug("IHB FX rate for {}/{}: {} (source: {})", from, to, rate, source);

        return new FxRate(
            from, to,
            rate, inverseRate,
            rateDate, source, BigDecimal.ZERO,
            timestamp
        );
    }

    /**
     * Create rate for same currency conversion.
     */
    private FxRate createSameCurrencyRate(String currency, LocalDate rateDate) {
        return new FxRate(
            currency, currency,
            BigDecimal.ONE, BigDecimal.ONE,
            rateDate, "SAME_CURRENCY", BigDecimal.ZERO,
            LocalDateTime.now()
        );
    }

    /**
     * Determine the source of the rate for metadata.
     */
    private String determineRateSource(String from, String to) {
        // Check if there's a DB rate
        List<com.bank.vam.entity.treasury.FxRate> dbRates =
            fxRateRepository.findLatestRate(from, to, RateType.MID);

        if (!dbRates.isEmpty()) {
            return "DB:" + dbRates.get(0).getRateSource().name();
        }

        // Check inverse
        List<com.bank.vam.entity.treasury.FxRate> inverseRates =
            fxRateRepository.findLatestRate(to, from, RateType.MID);

        if (!inverseRates.isEmpty()) {
            return "DB_INVERSE:" + inverseRates.get(0).getRateSource().name();
        }

        return "FALLBACK";
    }

    /**
     * Get the timestamp of when the rate was captured.
     */
    private LocalDateTime getRateTimestamp(String from, String to) {
        Optional<com.bank.vam.entity.treasury.FxRate> fxRate =
            fxRateService.getFxRate(from, to, RateType.MID);

        return fxRate.map(com.bank.vam.entity.treasury.FxRate::getRateTimestamp)
                     .orElse(LocalDateTime.now());
    }

    // ========================================================================
    // AMOUNT CONVERSION - Unified Precision
    // ========================================================================

    /**
     * Convert an amount from one currency to another.
     * Uses unified CONVERSION_SCALE (4 decimals) for consistency
     * with CurrencyMirrorService and FxRateService.
     *
     * @param amount       Amount to convert
     * @param fromCurrency Source currency
     * @param toCurrency   Target currency
     * @return Conversion result with full details
     */
    public ConversionResult convert(BigDecimal amount, String fromCurrency, String toCurrency) {
        return convert(amount, fromCurrency, toCurrency, LocalDate.now());
    }

    /**
     * Convert an amount using a rate from a specific date.
     */
    public ConversionResult convert(BigDecimal amount, String fromCurrency, String toCurrency, LocalDate rateDate) {
        if (amount == null) {
            amount = BigDecimal.ZERO;
        }

        FxRate rate = getRate(fromCurrency, toCurrency, rateDate);

        // Use unified CONVERSION_SCALE (4 decimals) for consistency
        BigDecimal convertedAmount = amount.multiply(rate.rate())
            .setScale(CONVERSION_SCALE, FX_ROUNDING);

        log.info("FX Conversion: {} {} -> {} {} @ {} (source: {})",
            amount, fromCurrency, convertedAmount, toCurrency, rate.rate(), rate.source());

        return new ConversionResult(
            amount, fromCurrency,
            convertedAmount, toCurrency,
            rate, rateDate
        );
    }

    /**
     * Convert an amount using a specific rate (for settlement at locked rate).
     * Used when the rate was locked at position creation time.
     */
    public BigDecimal convertAtRate(BigDecimal amount, BigDecimal rate) {
        if (amount == null || rate == null) {
            return BigDecimal.ZERO;
        }
        return amount.multiply(rate).setScale(CONVERSION_SCALE, FX_ROUNDING);
    }

    /**
     * Convert amount using FxRateService directly (simpler interface).
     */
    public BigDecimal convertSimple(BigDecimal amount, String fromCurrency, String toCurrency) {
        if (amount == null) return BigDecimal.ZERO;
        if (fromCurrency.equalsIgnoreCase(toCurrency)) return amount;

        return fxRateService.convert(amount, fromCurrency.toUpperCase(), toCurrency.toUpperCase());
    }

    // ========================================================================
    // IHB-SPECIFIC METHODS - Treasury Spread Support
    // ========================================================================

    /**
     * Get treasury-specific rate with spread for a corporate.
     * Treasury may offer different rates than market rates based on:
     * - Corporate-specific rate agreements
     * - Internal transfer pricing rules
     * - Treasury markup for risk/cost recovery
     *
     * @param corporateId   Corporate UUID for rate lookup
     * @param baseCurrency  Source currency
     * @param quoteCurrency Target currency
     * @param spreadBps     Additional spread in basis points (100 bps = 1%)
     * @return Adjusted FX rate with spread applied
     */
    public FxRate getTreasuryRate(UUID corporateId, String baseCurrency, String quoteCurrency, BigDecimal spreadBps) {
        // First check for corporate-specific rate
        Optional<com.bank.vam.entity.treasury.FxRate> corporateRate =
            findCorporateRate(corporateId, baseCurrency, quoteCurrency);

        BigDecimal baseRate;
        String source;
        LocalDateTime timestamp;

        if (corporateRate.isPresent()) {
            baseRate = corporateRate.get().getRate();
            source = "CORPORATE:" + corporateRate.get().getRateSource().name();
            timestamp = corporateRate.get().getRateTimestamp();
            log.debug("Using corporate-specific rate for {}: {}/{} = {}",
                corporateId, baseCurrency, quoteCurrency, baseRate);
        } else {
            // Fall back to market rate via FxRateService
            FxRate marketRate = getRate(baseCurrency, quoteCurrency);
            baseRate = marketRate.rate();
            source = "MARKET:" + marketRate.source();
            timestamp = marketRate.rateTimestamp();
        }

        // Apply spread (in basis points: 100 bps = 1%)
        BigDecimal adjustedRate = baseRate;
        if (spreadBps != null && spreadBps.compareTo(BigDecimal.ZERO) != 0) {
            BigDecimal spreadFactor = BigDecimal.ONE.add(
                spreadBps.divide(new BigDecimal("10000"), RATE_SCALE, FX_ROUNDING)
            );
            adjustedRate = baseRate.multiply(spreadFactor).setScale(RATE_SCALE, FX_ROUNDING);
            source = "TREASURY_SPREAD";
        }

        return new FxRate(
            baseCurrency.toUpperCase(), quoteCurrency.toUpperCase(),
            adjustedRate,
            BigDecimal.ONE.divide(adjustedRate, RATE_SCALE, FX_ROUNDING),
            LocalDate.now(),
            source,
            spreadBps != null ? spreadBps : BigDecimal.ZERO,
            timestamp
        );
    }

    /**
     * Get treasury-specific rate with spread (String corporateId for backward compatibility).
     */
    public FxRate getTreasuryRate(String corporateId, String baseCurrency, String quoteCurrency, BigDecimal spreadBps) {
        UUID corpId = null;
        if (corporateId != null && !corporateId.isEmpty()) {
            try {
                corpId = UUID.fromString(corporateId);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid corporate ID format: {}", corporateId);
            }
        }
        return getTreasuryRate(corpId, baseCurrency, quoteCurrency, spreadBps);
    }

    /**
     * Find corporate-specific rate if configured.
     */
    private Optional<com.bank.vam.entity.treasury.FxRate> findCorporateRate(
            UUID corporateId, String fromCurrency, String toCurrency) {
        if (corporateId == null) {
            return Optional.empty();
        }

        List<com.bank.vam.entity.treasury.FxRate> corporateRates =
            fxRateRepository.findByCorporateIdAndIsActiveTrueOrderByFromCurrency(corporateId);

        return corporateRates.stream()
            .filter(r -> r.getFromCurrency().equalsIgnoreCase(fromCurrency)
                      && r.getToCurrency().equalsIgnoreCase(toCurrency))
            .findFirst();
    }

    // ========================================================================
    // CURRENCY PAIR VALIDATION
    // ========================================================================

    /**
     * Validate if a currency pair is supported for IHB operations.
     * Delegates to FxRateService which has comprehensive lookup logic.
     */
    public boolean isCurrencyPairSupported(String baseCurrency, String quoteCurrency) {
        if (baseCurrency == null || quoteCurrency == null) {
            return false;
        }

        String from = baseCurrency.toUpperCase();
        String to = quoteCurrency.toUpperCase();

        if (from.equals(to)) {
            return true;
        }

        // Try to get a rate - if FxRateService returns 1.0 for non-same currencies,
        // it means no rate was found (fallback)
        BigDecimal rate = fxRateService.getRate(from, to);

        // Check if we have a real rate (not the safe fallback of 1.0)
        // A rate of 1.0 between different currencies is extremely unlikely in reality
        if (rate.compareTo(BigDecimal.ONE) == 0) {
            // Double-check: look for any DB rate
            List<com.bank.vam.entity.treasury.FxRate> directRates =
                fxRateRepository.findLatestRate(from, to, RateType.MID);
            List<com.bank.vam.entity.treasury.FxRate> inverseRates =
                fxRateRepository.findLatestRate(to, from, RateType.MID);

            return !directRates.isEmpty() || !inverseRates.isEmpty();
        }

        return true;
    }

    /**
     * Get all supported currencies from FxRateService.
     */
    public Set<String> getSupportedCurrencies() {
        return fxRateService.getAvailableCurrencies();
    }

    // ========================================================================
    // UI/PREVIEW METHODS
    // ========================================================================

    /**
     * Get indicative rate for UI display (before position creation).
     * This is the rate shown to users when previewing cross-currency operations.
     */
    public BigDecimal getIndicativeRate(String fromCurrency, String toCurrency) {
        return getRate(fromCurrency, toCurrency).rate();
    }

    /**
     * Get indicative rate with treasury spread for UI preview.
     */
    public BigDecimal getIndicativeTreasuryRate(UUID corporateId, String fromCurrency,
                                                  String toCurrency, BigDecimal spreadBps) {
        return getTreasuryRate(corporateId, fromCurrency, toCurrency, spreadBps).rate();
    }

    /**
     * Get full rate metadata for detailed UI display.
     */
    public FxRate getFullRateDetails(String fromCurrency, String toCurrency) {
        return getRate(fromCurrency, toCurrency);
    }

    /**
     * Check if a rate is stale (for UI warnings).
     */
    public boolean isRateStale(String fromCurrency, String toCurrency, int maxAgeMinutes) {
        FxRate rate = getRate(fromCurrency, toCurrency);
        return rate.isStale(maxAgeMinutes);
    }
}
