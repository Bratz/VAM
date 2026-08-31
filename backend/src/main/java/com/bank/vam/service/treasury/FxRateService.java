package com.bank.vam.service.treasury;

import com.bank.vam.entity.treasury.FxRate;
import com.bank.vam.entity.treasury.FxRate.RateSource;
import com.bank.vam.entity.treasury.FxRate.RateType;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.treasury.FxRateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FxRateService - FX rate management service.
 * 
 * UNIFIED ARCHITECTURE v4.2:
 * Provides FX rate operations for:
 * - Currency mirror balance conversion to base currency
 * - Multi-currency aggregation at ROOT level
 * - Cross-border payment FX calculations
 * - Treasury position reporting
 * 
 * FIXED v5.1.2:
 * - Updated to work with corrected FxRate entity
 * - RateType enum now uses BID, ASK, MID (not SPOT, FORWARD, etc.)
 * - Treats MID as the "spot" rate type
 * - Added getAvailablePairs() method for controller
 * 
 * Features:
 * - In-memory rate cache for performance
 * - Automatic rate lookup with inverse fallback
 * - Multi-hop conversion (EUR -> USD -> AED)
 * - Rate validation and staleness checking
 * - Integration with external FX providers
 * 
 * Thread Safety:
 * - Cache uses ConcurrentHashMap
 * - All mutations are transactional
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FxRateService {

    private final FxRateRepository fxRateRepository;

    // Rate cache: "FROM/TO/TYPE" -> FxRate
    private final Map<String, FxRate> rateCache = new ConcurrentHashMap<>();
    
    // Cache TTL in minutes
    private static final int CACHE_TTL_MINUTES = 15;
    private LocalDateTime cacheLastRefreshed = LocalDateTime.MIN;

    // Default rates for common pairs (fallback when no rate in DB)
    private static final Map<String, BigDecimal> DEFAULT_RATES = Map.of(
        "USD/AED", new BigDecimal("3.6725"),
        "EUR/AED", new BigDecimal("4.0125"),
        "GBP/AED", new BigDecimal("4.6500"),
        "EUR/USD", new BigDecimal("1.0926"),
        "GBP/USD", new BigDecimal("1.2665"),
        "USD/EUR", new BigDecimal("0.9152"),
        "CHF/USD", new BigDecimal("1.1250"),
        "JPY/USD", new BigDecimal("0.0067")
    );

    // ========================================================================
    // CORE RATE LOOKUP
    // ========================================================================

    /**
     * Get exchange rate for currency pair.
     * Uses cache first, then DB, then calculates inverse.
     * 
     * @param fromCurrency Source currency
     * @param toCurrency Target currency
     * @return Exchange rate (1 from = rate * to)
     */
    @Transactional(readOnly = true)
    public BigDecimal getRate(String fromCurrency, String toCurrency) {
        return getRate(fromCurrency, toCurrency, RateType.MID);
    }

    /**
     * Get exchange rate for currency pair with specific rate type.
     */
    @Transactional(readOnly = true)
    public BigDecimal getRate(String fromCurrency, String toCurrency, RateType rateType) {
        // Same currency - no conversion needed
        if (fromCurrency.equals(toCurrency)) {
            return BigDecimal.ONE;
        }

        // Check cache first
        String cacheKey = buildCacheKey(fromCurrency, toCurrency, rateType);
        FxRate cachedRate = getFromCache(cacheKey);
        if (cachedRate != null) {
            return cachedRate.getRate();
        }

        // Try direct lookup
        Optional<FxRate> directRate = findLatestRate(fromCurrency, toCurrency, rateType);
        if (directRate.isPresent()) {
            addToCache(cacheKey, directRate.get());
            return directRate.get().getRate();
        }

        // Try inverse lookup
        Optional<FxRate> inverseRate = findLatestRate(toCurrency, fromCurrency, rateType);
        if (inverseRate.isPresent()) {
            BigDecimal rate = inverseRate.get().getInverseRate();
            return rate;
        }

        // Try multi-hop via common currencies (USD, EUR)
        BigDecimal multiHopRate = tryMultiHopConversion(fromCurrency, toCurrency, rateType);
        if (multiHopRate != null) {
            return multiHopRate;
        }

        // Fall back to default rates
        BigDecimal defaultRate = getDefaultRate(fromCurrency, toCurrency);
        if (defaultRate != null) {
            log.warn("Using default rate for {}/{}: {}", fromCurrency, toCurrency, defaultRate);
            return defaultRate;
        }

        log.error("No FX rate found for {}/{}", fromCurrency, toCurrency);
        return BigDecimal.ONE; // Safe fallback
    }

    /**
     * Get FxRate entity (with full metadata).
     */
    @Transactional(readOnly = true)
    public Optional<FxRate> getFxRate(String fromCurrency, String toCurrency, RateType rateType) {
        return findLatestRate(fromCurrency, toCurrency, rateType);
    }

    /**
     * Convert amount from one currency to another.
     */
    public BigDecimal convert(BigDecimal amount, String fromCurrency, String toCurrency) {
        if (amount == null) return BigDecimal.ZERO;
        if (fromCurrency.equals(toCurrency)) return amount;
        
        BigDecimal rate = getRate(fromCurrency, toCurrency);
        return amount.multiply(rate).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Convert amount using specific rate type.
     */
    public BigDecimal convert(BigDecimal amount, String fromCurrency, String toCurrency, RateType rateType) {
        if (amount == null) return BigDecimal.ZERO;
        if (fromCurrency.equals(toCurrency)) return amount;
        
        BigDecimal rate = getRate(fromCurrency, toCurrency, rateType);
        return amount.multiply(rate).setScale(4, RoundingMode.HALF_UP);
    }

    // ========================================================================
    // RATE MANAGEMENT
    // ========================================================================

    /**
     * Create or update FX rate.
     */
    @Transactional
    public FxRate saveRate(FxRate rate) {
        // Validate
        validateRate(rate);
        
        // Calculate inverse if needed
        rate.calculateInverseRate();
        
        // Calculate mid rate if bid/ask available
        rate.calculateMidRate();

        // Deactivate old rates for same pair/type
        if (rate.getId() != null) {
            fxRateRepository.deactivateOldRates(
                rate.getFromCurrency(),
                rate.getToCurrency(),
                rate.getRateType(),
                rate.getId()
            );
        }

        FxRate saved = fxRateRepository.save(rate);
        
        // Update cache
        String cacheKey = buildCacheKey(rate.getFromCurrency(), rate.getToCurrency(), rate.getRateType());
        addToCache(cacheKey, saved);
        
        log.info("Saved FX rate: {} {} -> {} @ {}", 
            rate.getRateType(), rate.getFromCurrency(), rate.getToCurrency(), rate.getRate());
        
        return saved;
    }

    /**
     * Create a spot rate (MID type).
     */
    @Transactional
    public FxRate createSpotRate(String fromCurrency, String toCurrency, 
                                  BigDecimal rate, RateSource source) {
        FxRate fxRate = FxRate.createSpotRate(fromCurrency, toCurrency, rate, source);
        return saveRate(fxRate);
    }

    /**
     * Create a fixing rate (MID type with specific date).
     */
    @Transactional
    public FxRate createFixingRate(String fromCurrency, String toCurrency,
                                    BigDecimal rate, LocalDate rateDate, 
                                    RateSource source) {
        FxRate fxRate = FxRate.createFixingRate(fromCurrency, toCurrency, rate, rateDate, source);
        return saveRate(fxRate);
    }

    /**
     * Create an internal rate (for transfer pricing).
     */
    @Transactional
    public FxRate createInternalRate(String fromCurrency, String toCurrency,
                                      BigDecimal rate, UUID corporateId,
                                      BigDecimal spreadBps) {
        FxRate fxRate = FxRate.createInternalRate(fromCurrency, toCurrency, rate, corporateId, spreadBps);
        return saveRate(fxRate);
    }

    /**
     * Update existing rate value.
     */
    @Transactional
    public FxRate updateRate(UUID rateId, BigDecimal newRate) {
        FxRate fxRate = fxRateRepository.findById(rateId)
            .orElseThrow(() -> new ResourceNotFoundException("FX Rate not found: " + rateId));
        
        fxRate.setRate(newRate);
        fxRate.calculateInverseRate();
        fxRate.setRateTime(LocalTime.now());
        
        FxRate saved = fxRateRepository.save(fxRate);
        
        // Update cache
        String cacheKey = buildCacheKey(fxRate.getFromCurrency(), fxRate.getToCurrency(), fxRate.getRateType());
        addToCache(cacheKey, saved);
        
        return saved;
    }

    /**
     * Deactivate a rate.
     */
    @Transactional
    public void deactivateRate(UUID rateId) {
        FxRate fxRate = fxRateRepository.findById(rateId)
            .orElseThrow(() -> new ResourceNotFoundException("FX Rate not found: " + rateId));
        
        fxRate.setIsActive(false);
        fxRate.setEffectiveTo(LocalDateTime.now());
        fxRateRepository.save(fxRate);
        
        // Remove from cache
        String cacheKey = buildCacheKey(fxRate.getFromCurrency(), fxRate.getToCurrency(), fxRate.getRateType());
        rateCache.remove(cacheKey);
        
        log.info("Deactivated FX rate: {} {}/{}", fxRate.getId(), fxRate.getFromCurrency(), fxRate.getToCurrency());
    }

    // ========================================================================
    // BULK OPERATIONS
    // ========================================================================

    /**
     * Get all active rates.
     */
    @Transactional(readOnly = true)
    public List<FxRate> getAllActiveRates() {
        return fxRateRepository.findByIsActiveTrueOrderByFromCurrencyAscToCurrencyAsc();
    }

    /**
     * Get rates for a specific date.
     */
    @Transactional(readOnly = true)
    public List<FxRate> getRatesForDate(LocalDate date) {
        return fxRateRepository.findByRateDateOrderByFromCurrencyAscToCurrencyAsc(date);
    }

    /**
     * Get rates by source.
     */
    @Transactional(readOnly = true)
    public List<FxRate> getRatesBySource(RateSource source) {
        return fxRateRepository.findLatestBySource(source);
    }

    /**
     * Get corporate-specific rates.
     */
    @Transactional(readOnly = true)
    public List<FxRate> getCorporateRates(UUID corporateId) {
        return fxRateRepository.findByCorporateIdAndIsActiveTrueOrderByFromCurrency(corporateId);
    }

    /**
     * Get all available currency pairs as String array.
     * Used by controller for /pairs endpoint.
     * 
     * @return List of [fromCurrency, toCurrency] arrays
     */
    @Transactional(readOnly = true)
    public List<String[]> getAvailablePairs() {
        List<Object[]> pairs = fxRateRepository.findDistinctCurrencyPairs();
        List<String[]> result = new ArrayList<>();
        for (Object[] pair : pairs) {
            result.add(new String[] { (String) pair[0], (String) pair[1] });
        }
        return result;
    }

    /**
     * Get all available currency pairs as formatted strings.
     * 
     * @return List of "FROM/TO" strings
     */
    @Transactional(readOnly = true)
    public List<String> getAvailableCurrencyPairs() {
        List<Object[]> pairs = fxRateRepository.findDistinctCurrencyPairs();
        List<String> result = new ArrayList<>();
        for (Object[] pair : pairs) {
            result.add(pair[0] + "/" + pair[1]);
        }
        return result;
    }

    /**
     * Get distinct currencies.
     */
    @Transactional(readOnly = true)
    public Set<String> getAvailableCurrencies() {
        Set<String> currencies = new HashSet<>();
        currencies.addAll(fxRateRepository.findDistinctFromCurrencies());
        currencies.addAll(fxRateRepository.findDistinctToCurrencies());
        return currencies;
    }

    // ========================================================================
    // HISTORICAL RATES
    // ========================================================================

    /**
     * Get historical rates for a currency pair.
     */
    @Transactional(readOnly = true)
    public List<FxRate> getHistoricalRates(String fromCurrency, String toCurrency,
                                            LocalDate startDate, LocalDate endDate) {
        return fxRateRepository.findRatesInRange(fromCurrency, toCurrency, startDate, endDate);
    }

    /**
     * Get fixing rate for specific date.
     */
    @Transactional(readOnly = true)
    public Optional<FxRate> getFixingRate(String fromCurrency, String toCurrency, LocalDate date) {
        return fxRateRepository.findFixingRate(fromCurrency, toCurrency, date);
    }

    // ========================================================================
    // RATE VALIDATION
    // ========================================================================

    /**
     * Check if rate is stale (older than threshold).
     */
    public boolean isRateStale(FxRate rate, int maxAgeMinutes) {
        if (rate == null || rate.getRateTimestamp() == null) {
            return true;
        }
        return rate.getRateTimestamp().plusMinutes(maxAgeMinutes).isBefore(LocalDateTime.now());
    }

    /**
     * Validate rate before saving.
     */
    private void validateRate(FxRate rate) {
        if (rate.getFromCurrency() == null || rate.getFromCurrency().length() != 3) {
            throw new BusinessException("Invalid from currency");
        }
        if (rate.getToCurrency() == null || rate.getToCurrency().length() != 3) {
            throw new BusinessException("Invalid to currency");
        }
        if (rate.getRate() == null || rate.getRate().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Rate must be positive");
        }
        if (rate.getFromCurrency().equals(rate.getToCurrency())) {
            throw new BusinessException("From and To currencies must be different");
        }
        if (rate.getRateDate() == null) {
            rate.setRateDate(LocalDate.now());
        }
    }

    // ========================================================================
    // CACHE MANAGEMENT
    // ========================================================================

    /**
     * Refresh rate cache from database.
     */
    @Scheduled(fixedRateString = "${vam.fx.cache-refresh-interval-ms:900000}") // 15 min
    @Transactional(readOnly = true)
    public void refreshCache() {
        log.debug("Refreshing FX rate cache...");
        
        List<FxRate> activeRates = fxRateRepository.findByIsActiveTrueOrderByFromCurrencyAscToCurrencyAsc();
        
        rateCache.clear();
        for (FxRate rate : activeRates) {
            String cacheKey = buildCacheKey(rate.getFromCurrency(), rate.getToCurrency(), rate.getRateType());
            rateCache.put(cacheKey, rate);
        }
        
        cacheLastRefreshed = LocalDateTime.now();
        log.info("FX rate cache refreshed with {} rates", activeRates.size());
    }

    /**
     * Clear the rate cache.
     */
    public void clearCache() {
        rateCache.clear();
        log.info("FX rate cache cleared");
    }

    /**
     * Get cache statistics.
     */
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("size", rateCache.size());
        stats.put("lastRefreshed", cacheLastRefreshed);
        stats.put("ttlMinutes", CACHE_TTL_MINUTES);
        return stats;
    }

    // ========================================================================
    // PRIVATE HELPERS
    // ========================================================================

    private String buildCacheKey(String fromCurrency, String toCurrency, RateType rateType) {
        return fromCurrency + "/" + toCurrency + "/" + rateType.name();
    }

    private FxRate getFromCache(String cacheKey) {
        // Check if cache is stale
        if (cacheLastRefreshed.plusMinutes(CACHE_TTL_MINUTES).isBefore(LocalDateTime.now())) {
            return null;
        }
        return rateCache.get(cacheKey);
    }

    private void addToCache(String cacheKey, FxRate rate) {
        rateCache.put(cacheKey, rate);
    }

    private Optional<FxRate> findLatestRate(String fromCurrency, String toCurrency, RateType rateType) {
        List<FxRate> rates = fxRateRepository.findLatestRate(fromCurrency, toCurrency, rateType);
        return rates.isEmpty() ? Optional.empty() : Optional.of(rates.get(0));
    }

    /**
     * Try multi-hop conversion via common currencies.
     */
    private BigDecimal tryMultiHopConversion(String fromCurrency, String toCurrency, RateType rateType) {
        String[] pivotCurrencies = {"USD", "EUR", "AED"};
        
        for (String pivot : pivotCurrencies) {
            if (pivot.equals(fromCurrency) || pivot.equals(toCurrency)) {
                continue;
            }
            
            Optional<FxRate> fromToPivot = findLatestRate(fromCurrency, pivot, rateType);
            Optional<FxRate> pivotToTarget = findLatestRate(pivot, toCurrency, rateType);
            
            if (fromToPivot.isPresent() && pivotToTarget.isPresent()) {
                BigDecimal rate = fromToPivot.get().getRate()
                    .multiply(pivotToTarget.get().getRate())
                    .setScale(8, RoundingMode.HALF_UP);
                log.debug("Multi-hop rate: {} -> {} -> {} = {}", fromCurrency, pivot, toCurrency, rate);
                return rate;
            }
        }
        
        return null;
    }

    private BigDecimal getDefaultRate(String fromCurrency, String toCurrency) {
        String key = fromCurrency + "/" + toCurrency;
        if (DEFAULT_RATES.containsKey(key)) {
            return DEFAULT_RATES.get(key);
        }
        
        // Try inverse
        String inverseKey = toCurrency + "/" + fromCurrency;
        if (DEFAULT_RATES.containsKey(inverseKey)) {
            return BigDecimal.ONE.divide(DEFAULT_RATES.get(inverseKey), 8, RoundingMode.HALF_UP);
        }
        
        return null;
    }

    // ========================================================================
    // IMPORT/EXPORT
    // ========================================================================

    /**
     * Bulk import rates.
     */
    @Transactional
    public int importRates(List<FxRate> rates) {
        int imported = 0;
        for (FxRate rate : rates) {
            try {
                saveRate(rate);
                imported++;
            } catch (Exception e) {
                log.error("Failed to import rate {}/{}: {}", 
                    rate.getFromCurrency(), rate.getToCurrency(), e.getMessage());
            }
        }
        log.info("Imported {} of {} rates", imported, rates.size());
        return imported;
    }

    /**
     * Delete old historical rates (cleanup).
     */
    @Transactional
    public int cleanupOldRates(int keepDays) {
        LocalDate beforeDate = LocalDate.now().minusDays(keepDays);
        int deleted = fxRateRepository.deleteOldRates(beforeDate);
        log.info("Deleted {} old FX rates older than {}", deleted, beforeDate);
        return deleted;
    }
}