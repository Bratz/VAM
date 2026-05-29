package com.bank.vam.repository.credit;

import com.bank.vam.entity.credit.InterestConfiguration;
import com.bank.vam.entity.credit.InterestConfiguration.ConfigStatus;
import com.bank.vam.entity.credit.InterestConfiguration.ConfigType;
import com.bank.vam.entity.credit.InterestConfiguration.TargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
 * Repository for InterestConfiguration operations.
 * 
 * UNIFIED ARCHITECTURE v4.2:
 * Enhanced with methods for effective config lookup with validity checking.
 */
@Repository
public interface InterestConfigurationRepository extends JpaRepository<InterestConfiguration, UUID> {

    // ========================================================================
    // BASIC FINDERS
    // ========================================================================

    List<InterestConfiguration> findByCorporateIdOrderByConfigName(UUID corporateId);

    Page<InterestConfiguration> findByCorporateId(UUID corporateId, Pageable pageable);

    List<InterestConfiguration> findByCorporateIdAndStatus(UUID corporateId, ConfigStatus status);

    List<InterestConfiguration> findByCorporateIdAndConfigType(UUID corporateId, ConfigType configType);

    List<InterestConfiguration> findByCorporateIdAndCurrencyCode(UUID corporateId, String currencyCode);

    List<InterestConfiguration> findByTargetId(UUID targetId);

    List<InterestConfiguration> findByTargetIdAndConfigType(UUID targetId, ConfigType configType);

    Optional<InterestConfiguration> findByExternalReference(String externalReference);

    /**
     * Find active internal config for target entity and currency.
     * Used for IHB current account interest configuration lookup.
     */
    @Query("""
        SELECT ic FROM InterestConfiguration ic
        WHERE ic.targetId = :targetId
        AND ic.currencyCode = :currency
        AND ic.configType = :configType
        AND ic.status = 'ACTIVE'
        AND (ic.effectiveFrom IS NULL OR ic.effectiveFrom <= CURRENT_DATE)
        AND (ic.effectiveTo IS NULL OR ic.effectiveTo >= CURRENT_DATE)
        ORDER BY ic.effectiveFrom DESC
        """)
    Optional<InterestConfiguration> findByTargetIdAndCurrencyCodeAndConfigType(
        @Param("targetId") UUID targetId,
        @Param("currency") String currency,
        @Param("configType") ConfigType configType);

    // ========================================================================
    // EFFECTIVE CONFIG LOOKUPS (with validity date checks)
    // ========================================================================

    /**
     * Find active config for target and type with validity check.
     */
    @Query("""
        SELECT ic FROM InterestConfiguration ic 
        WHERE ic.targetId = :targetId 
        AND ic.configType = :configType 
        AND ic.status = 'ACTIVE'
        AND (ic.effectiveFrom IS NULL OR ic.effectiveFrom <= :today)
        AND (ic.effectiveTo IS NULL OR ic.effectiveTo >= :today)
        ORDER BY ic.effectiveFrom DESC
        """)
    Optional<InterestConfiguration> findActiveByTargetAndType(
        @Param("targetId") UUID targetId,
        @Param("configType") ConfigType configType,
        @Param("today") LocalDate today);

    /**
     * Find active currency default config.
     */
    @Query("""
        SELECT ic FROM InterestConfiguration ic 
        WHERE ic.corporateId = :corporateId 
        AND ic.currencyCode = :currency
        AND ic.targetType = 'CURRENCY'
        AND ic.configType = :configType
        AND ic.status = 'ACTIVE'
        AND (ic.effectiveFrom IS NULL OR ic.effectiveFrom <= :today)
        AND (ic.effectiveTo IS NULL OR ic.effectiveTo >= :today)
        """)
    Optional<InterestConfiguration> findActiveByCurrencyDefault(
        @Param("corporateId") UUID corporateId,
        @Param("currency") String currency,
        @Param("configType") ConfigType configType,
        @Param("today") LocalDate today);

    /**
     * Find active corporate default config.
     */
    @Query("""
        SELECT ic FROM InterestConfiguration ic 
        WHERE ic.corporateId = :corporateId 
        AND ic.targetType = 'CORPORATE'
        AND ic.configType = :configType
        AND ic.status = 'ACTIVE'
        AND (ic.effectiveFrom IS NULL OR ic.effectiveFrom <= :today)
        AND (ic.effectiveTo IS NULL OR ic.effectiveTo >= :today)
        """)
    Optional<InterestConfiguration> findActiveCorporateDefault(
        @Param("corporateId") UUID corporateId,
        @Param("configType") ConfigType configType,
        @Param("today") LocalDate today);

    /**
     * Find active external config for target.
     */
    @Query("""
        SELECT ic FROM InterestConfiguration ic 
        WHERE ic.targetId = :targetId 
        AND ic.configType = 'EXTERNAL' 
        AND ic.status = 'ACTIVE'
        AND (ic.effectiveFrom IS NULL OR ic.effectiveFrom <= CURRENT_DATE)
        AND (ic.effectiveTo IS NULL OR ic.effectiveTo >= CURRENT_DATE)
        """)
    Optional<InterestConfiguration> findActiveExternalConfig(@Param("targetId") UUID targetId);

    /**
     * Find active internal config for target.
     */
    @Query("""
        SELECT ic FROM InterestConfiguration ic 
        WHERE ic.targetId = :targetId 
        AND ic.configType = 'INTERNAL' 
        AND ic.status = 'ACTIVE'
        AND (ic.effectiveFrom IS NULL OR ic.effectiveFrom <= CURRENT_DATE)
        AND (ic.effectiveTo IS NULL OR ic.effectiveTo >= CURRENT_DATE)
        """)
    Optional<InterestConfiguration> findActiveInternalConfig(@Param("targetId") UUID targetId);

    /**
     * Find all active configs for target.
     */
    @Query("""
        SELECT ic FROM InterestConfiguration ic 
        WHERE ic.targetId = :targetId 
        AND ic.status = 'ACTIVE'
        AND (ic.effectiveFrom IS NULL OR ic.effectiveFrom <= CURRENT_DATE)
        AND (ic.effectiveTo IS NULL OR ic.effectiveTo >= CURRENT_DATE)
        ORDER BY ic.configType
        """)
    List<InterestConfiguration> findActiveConfigsForTarget(@Param("targetId") UUID targetId);

    // ========================================================================
    // CURRENCY/DEFAULT CONFIG LOOKUPS
    // ========================================================================

    /**
     * Find default config for currency.
     */
    @Query("""
        SELECT ic FROM InterestConfiguration ic 
        WHERE ic.corporateId = :corporateId 
        AND ic.currencyCode = :currency
        AND ic.targetType = 'CURRENCY'
        AND ic.status = 'ACTIVE'
        """)
    Optional<InterestConfiguration> findCurrencyDefault(@Param("corporateId") UUID corporateId,
                                                         @Param("currency") String currency);

    /**
     * Find corporate-wide default config.
     */
    @Query("""
        SELECT ic FROM InterestConfiguration ic 
        WHERE ic.corporateId = :corporateId 
        AND ic.targetType = 'CORPORATE'
        AND ic.configType = :configType
        AND ic.status = 'ACTIVE'
        """)
    Optional<InterestConfiguration> findCorporateDefault(@Param("corporateId") UUID corporateId,
                                                          @Param("configType") ConfigType configType);

    /**
     * Find configs by currency and status.
     */
    List<InterestConfiguration> findByCorporateIdAndCurrencyCodeAndStatus(
        UUID corporateId, String currencyCode, ConfigStatus status);

    // ========================================================================
    // TARGET TYPE QUERIES
    // ========================================================================

    List<InterestConfiguration> findByCorporateIdAndTargetType(UUID corporateId, TargetType targetType);

    @Query("""
        SELECT ic FROM InterestConfiguration ic 
        WHERE ic.corporateId = :corporateId 
        AND ic.targetType = :targetType 
        AND ic.status = 'ACTIVE'
        """)
    List<InterestConfiguration> findActiveByTargetType(@Param("corporateId") UUID corporateId,
                                                        @Param("targetType") TargetType targetType);

    // ========================================================================
    // RATE QUERIES
    // ========================================================================

    /**
     * Find configs with credit rate above threshold.
     */
    @Query("""
        SELECT ic FROM InterestConfiguration ic 
        WHERE ic.corporateId = :corporateId 
        AND ic.status = 'ACTIVE'
        AND ic.effectiveCreditRate >= :rate
        ORDER BY ic.effectiveCreditRate DESC
        """)
    List<InterestConfiguration> findConfigsWithCreditRateAbove(@Param("corporateId") UUID corporateId,
                                                                 @Param("rate") BigDecimal rate);

    /**
     * Find configs with debit rate above threshold.
     */
    @Query("""
        SELECT ic FROM InterestConfiguration ic 
        WHERE ic.corporateId = :corporateId 
        AND ic.status = 'ACTIVE'
        AND ic.effectiveDebitRate >= :rate
        ORDER BY ic.effectiveDebitRate DESC
        """)
    List<InterestConfiguration> findConfigsWithDebitRateAbove(@Param("corporateId") UUID corporateId,
                                                                @Param("rate") BigDecimal rate);

    // ========================================================================
    // UPDATE OPERATIONS
    // ========================================================================

    /**
     * Update effective rates.
     */
    @Modifying
    @Query("""
        UPDATE InterestConfiguration ic 
        SET ic.effectiveCreditRate = :creditRate,
            ic.effectiveDebitRate = :debitRate,
            ic.updatedAt = CURRENT_TIMESTAMP
        WHERE ic.id = :id
        """)
    int updateEffectiveRates(@Param("id") UUID id,
                              @Param("creditRate") BigDecimal creditRate,
                              @Param("debitRate") BigDecimal debitRate);

    /**
     * Update base rates.
     */
    @Modifying
    @Query("""
        UPDATE InterestConfiguration ic 
        SET ic.creditBaseRate = :creditBase,
            ic.debitBaseRate = :debitBase,
            ic.effectiveCreditRate = :creditBase + COALESCE(ic.creditSpread, 0),
            ic.effectiveDebitRate = :debitBase + COALESCE(ic.debitSpread, 0),
            ic.updatedAt = CURRENT_TIMESTAMP
        WHERE ic.id = :id
        """)
    int updateBaseRates(@Param("id") UUID id,
                         @Param("creditBase") BigDecimal creditBase,
                         @Param("debitBase") BigDecimal debitBase);

    /**
     * Update status.
     */
    @Modifying
    @Query("UPDATE InterestConfiguration ic SET ic.status = :status, ic.updatedAt = CURRENT_TIMESTAMP WHERE ic.id = :id")
    int updateStatus(@Param("id") UUID id, @Param("status") ConfigStatus status);

    // ========================================================================
    // EXISTENCE CHECKS
    // ========================================================================

    boolean existsByTargetIdAndConfigTypeAndStatus(UUID targetId, ConfigType configType, ConfigStatus status);

    long countByCorporateIdAndStatus(UUID corporateId, ConfigStatus status);

    // ========================================================================
    // DISTINCT QUERIES
    // ========================================================================

    @Query("SELECT DISTINCT ic.currencyCode FROM InterestConfiguration ic WHERE ic.corporateId = :corporateId AND ic.status = 'ACTIVE'")
    List<String> findDistinctCurrencies(@Param("corporateId") UUID corporateId);

    @Query("SELECT DISTINCT ic.creditBaseRateType FROM InterestConfiguration ic WHERE ic.corporateId = :corporateId AND ic.creditBaseRateType IS NOT NULL")
    List<String> findDistinctBaseRateTypes(@Param("corporateId") UUID corporateId);
}