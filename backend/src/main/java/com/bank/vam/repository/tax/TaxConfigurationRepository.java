package com.bank.vam.repository.tax;

import com.bank.vam.entity.tax.TaxConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Tax Configuration entities.
 */
@Repository
public interface TaxConfigurationRepository extends JpaRepository<TaxConfiguration, UUID> {

    Optional<TaxConfiguration> findByTaxCode(String taxCode);
    
    List<TaxConfiguration> findByTaxType(TaxConfiguration.TaxType taxType);
    
    List<TaxConfiguration> findByJurisdictionCode(String jurisdictionCode);
    
    List<TaxConfiguration> findByTaxCategory(TaxConfiguration.TaxCategory category);
    
    List<TaxConfiguration> findByStatus(TaxConfiguration.TaxStatus status);
    
    @Query("SELECT t FROM TaxConfiguration t WHERE t.status = 'ACTIVE' " +
           "AND (t.effectiveTo IS NULL OR t.effectiveTo >= CURRENT_DATE)")
    List<TaxConfiguration> findAllActive();
    
    @Query("SELECT t FROM TaxConfiguration t WHERE t.taxCode = :code " +
           "AND t.status = 'ACTIVE' " +
           "AND (t.effectiveTo IS NULL OR t.effectiveTo >= CURRENT_DATE)")
    Optional<TaxConfiguration> findActiveByCode(@Param("code") String taxCode);
    
    @Query("SELECT t FROM TaxConfiguration t WHERE t.isWithholding = true " +
           "AND t.status = 'ACTIVE' " +
           "AND (t.effectiveTo IS NULL OR t.effectiveTo >= CURRENT_DATE)")
    List<TaxConfiguration> findActiveWithholdingTaxes();
    
    @Query("SELECT t FROM TaxConfiguration t WHERE t.jurisdictionCode = :jurisdiction " +
           "AND t.taxType = :type AND t.status = 'ACTIVE' " +
           "AND (t.effectiveTo IS NULL OR t.effectiveTo >= CURRENT_DATE)")
    List<TaxConfiguration> findActiveByJurisdictionAndType(
        @Param("jurisdiction") String jurisdictionCode,
        @Param("type") TaxConfiguration.TaxType taxType);
    
    @Query("SELECT t FROM TaxConfiguration t WHERE t.taxType = 'VAT' " +
           "AND t.taxCategory = 'STANDARD' AND t.jurisdictionCode = :jurisdiction " +
           "AND t.status = 'ACTIVE'")
    Optional<TaxConfiguration> findStandardVatForJurisdiction(@Param("jurisdiction") String jurisdictionCode);
    
    @Query("SELECT t FROM TaxConfiguration t WHERE t.appliesToPayables = true " +
           "AND t.status = 'ACTIVE' " +
           "AND (t.effectiveTo IS NULL OR t.effectiveTo >= CURRENT_DATE)")
    List<TaxConfiguration> findApplicableToPayables();
    
    @Query("SELECT t FROM TaxConfiguration t WHERE t.appliesToReceivables = true " +
           "AND t.status = 'ACTIVE' " +
           "AND (t.effectiveTo IS NULL OR t.effectiveTo >= CURRENT_DATE)")
    List<TaxConfiguration> findApplicableToReceivables();
}