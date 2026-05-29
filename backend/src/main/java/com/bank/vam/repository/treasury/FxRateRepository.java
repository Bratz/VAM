package com.bank.vam.repository.treasury;

import com.bank.vam.entity.treasury.FxRate;
import com.bank.vam.entity.treasury.FxRate.RateSource;
import com.bank.vam.entity.treasury.FxRate.RateType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for FxRate operations.
 * 
 * FIXED v5.1.0:
 * - Updated queries to match corrected entity column names
 * - RateType uses BID, ASK, MID (not SPOT, FORWARD, etc.)
 * - Uses 'rateSource' mapped to DB column 'source'
 */
@Repository
public interface FxRateRepository extends JpaRepository<FxRate, UUID> {

    // ========================================================================
    // BASIC FINDERS
    // ========================================================================

    /**
     * Find rate by currency pair and date.
     */
    Optional<FxRate> findByFromCurrencyAndToCurrencyAndRateDateAndRateType(
        String fromCurrency, String toCurrency, LocalDate rateDate, RateType rateType);

    /**
     * Find all rates for a currency pair.
     */
    List<FxRate> findByFromCurrencyAndToCurrencyOrderByRateDateDesc(
        String fromCurrency, String toCurrency);

    /**
     * Find all rates for a date.
     */
    List<FxRate> findByRateDateOrderByFromCurrencyAscToCurrencyAsc(LocalDate rateDate);

    /**
     * Find all active rates.
     */
    List<FxRate> findByIsActiveTrueOrderByFromCurrencyAscToCurrencyAsc();

    // ========================================================================
    // LATEST RATE LOOKUPS
    // ========================================================================

    /**
     * Get latest rate for currency pair.
     */
    @Query("""
        SELECT fr FROM FxRate fr 
        WHERE fr.fromCurrency = :from AND fr.toCurrency = :to 
        AND fr.rateType = :rateType 
        AND fr.isActive = true
        AND (fr.effectiveFrom IS NULL OR fr.effectiveFrom <= CURRENT_TIMESTAMP)
        AND (fr.effectiveTo IS NULL OR fr.effectiveTo >= CURRENT_TIMESTAMP)
        ORDER BY fr.rateDate DESC, fr.rateTime DESC NULLS LAST
        """)
    List<FxRate> findLatestRate(@Param("from") String fromCurrency, 
                                 @Param("to") String toCurrency,
                                 @Param("rateType") RateType rateType);

    /**
     * Get latest MID rate for currency pair (spot equivalent).
     */
    default Optional<FxRate> findLatestSpotRate(String fromCurrency, String toCurrency) {
        List<FxRate> rates = findLatestRate(fromCurrency, toCurrency, RateType.MID);
        return rates.isEmpty() ? Optional.empty() : Optional.of(rates.get(0));
    }

    // ========================================================================
    // HISTORICAL RATES
    // ========================================================================

    /**
     * Find rates for date range.
     */
    @Query("""
        SELECT fr FROM FxRate fr 
        WHERE fr.fromCurrency = :from AND fr.toCurrency = :to 
        AND fr.rateDate BETWEEN :startDate AND :endDate
        ORDER BY fr.rateDate DESC
        """)
    List<FxRate> findRatesInRange(@Param("from") String fromCurrency,
                                   @Param("to") String toCurrency,
                                   @Param("startDate") LocalDate startDate,
                                   @Param("endDate") LocalDate endDate);

    /**
     * Find historical fixing rate (MID type for specific date).
     */
    @Query("""
        SELECT fr FROM FxRate fr 
        WHERE fr.fromCurrency = :from AND fr.toCurrency = :to 
        AND fr.rateDate = :date 
        AND fr.rateType = 'MID'
        AND fr.isActive = true
        """)
    Optional<FxRate> findFixingRate(@Param("from") String fromCurrency,
                                     @Param("to") String toCurrency,
                                     @Param("date") LocalDate date);

    // ========================================================================
    // CORPORATE RATES
    // ========================================================================

    /**
     * Find corporate-specific rates.
     */
    List<FxRate> findByCorporateIdAndIsActiveTrueOrderByFromCurrency(UUID corporateId);

    /**
     * Find corporate internal rate for pair.
     */
    @Query("""
        SELECT fr FROM FxRate fr 
        WHERE fr.fromCurrency = :from AND fr.toCurrency = :to 
        AND fr.corporateId = :corporateId
        AND fr.rateSource = 'INTERNAL'
        AND fr.isActive = true
        ORDER BY fr.rateDate DESC
        """)
    List<FxRate> findInternalRate(@Param("from") String fromCurrency,
                                   @Param("to") String toCurrency,
                                   @Param("corporateId") UUID corporateId);

    // ========================================================================
    // RATE SOURCE QUERIES
    // ========================================================================

    /**
     * Find rates by source.
     */
    List<FxRate> findByRateSourceAndRateDateOrderByFromCurrency(RateSource source, LocalDate date);

    /**
     * Find latest rates by source.
     */
    @Query("""
        SELECT fr FROM FxRate fr 
        WHERE fr.rateSource = :source AND fr.isActive = true
        AND fr.rateDate = (SELECT MAX(fr2.rateDate) FROM FxRate fr2 WHERE fr2.rateSource = :source)
        ORDER BY fr.fromCurrency, fr.toCurrency
        """)
    List<FxRate> findLatestBySource(@Param("source") RateSource source);

    // ========================================================================
    // DISTINCT QUERIES
    // ========================================================================

    /**
     * Get all distinct currency pairs.
     */
    @Query("SELECT DISTINCT fr.fromCurrency, fr.toCurrency FROM FxRate fr WHERE fr.isActive = true")
    List<Object[]> findDistinctCurrencyPairs();

    /**
     * Get all distinct from currencies.
     */
    @Query("SELECT DISTINCT fr.fromCurrency FROM FxRate fr WHERE fr.isActive = true ORDER BY fr.fromCurrency")
    List<String> findDistinctFromCurrencies();

    /**
     * Get all distinct to currencies.
     */
    @Query("SELECT DISTINCT fr.toCurrency FROM FxRate fr WHERE fr.isActive = true ORDER BY fr.toCurrency")
    List<String> findDistinctToCurrencies();

    // ========================================================================
    // UPDATE OPERATIONS
    // ========================================================================

    /**
     * Deactivate old rates for a pair.
     */
    @Modifying
    @Query("""
        UPDATE FxRate fr SET fr.isActive = false, fr.updatedAt = CURRENT_TIMESTAMP 
        WHERE fr.fromCurrency = :from AND fr.toCurrency = :to 
        AND fr.rateType = :rateType
        AND fr.id != :excludeId
        AND fr.isActive = true
        """)
    int deactivateOldRates(@Param("from") String fromCurrency,
                           @Param("to") String toCurrency,
                           @Param("rateType") RateType rateType,
                           @Param("excludeId") UUID excludeId);

    /**
     * Update rate value.
     */
    @Modifying
    @Query("""
        UPDATE FxRate fr 
        SET fr.rate = :rate, 
            fr.rateTime = CURRENT_TIME,
            fr.updatedAt = CURRENT_TIMESTAMP
        WHERE fr.id = :id
        """)
    int updateRate(@Param("id") UUID id, 
                   @Param("rate") BigDecimal rate);

    // ========================================================================
    // EXISTENCE CHECKS
    // ========================================================================

    boolean existsByFromCurrencyAndToCurrencyAndRateDateAndRateType(
        String fromCurrency, String toCurrency, LocalDate rateDate, RateType rateType);

    // ========================================================================
    // CLEANUP
    // ========================================================================

    /**
     * Delete old historical rates (older than given date).
     */
    @Modifying
    @Query("DELETE FROM FxRate fr WHERE fr.rateDate < :beforeDate")
    int deleteOldRates(@Param("beforeDate") LocalDate beforeDate);

    // ========================================================================
    // COUNT QUERIES
    // ========================================================================

    /**
     * Count active rates.
     */
    long countByIsActiveTrue();

    /**
     * Count rates by source.
     */
    long countByRateSource(RateSource source);
}