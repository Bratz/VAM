package com.bank.vam.repository.tax;

import com.bank.vam.entity.tax.TaxJurisdiction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Tax Jurisdiction entities.
 */
@Repository
public interface TaxJurisdictionRepository extends JpaRepository<TaxJurisdiction, UUID> {

    Optional<TaxJurisdiction> findByJurisdictionCode(String code);
    
    List<TaxJurisdiction> findByCountryCode(String countryCode);
    
    List<TaxJurisdiction> findByStatus(TaxJurisdiction.JurisdictionStatus status);
    
    @Query("SELECT j FROM TaxJurisdiction j WHERE j.status = 'ACTIVE' " +
           "AND (j.effectiveTo IS NULL OR j.effectiveTo >= CURRENT_DATE)")
    List<TaxJurisdiction> findAllActive();
    
    @Query("SELECT j FROM TaxJurisdiction j WHERE j.supportsVat = true AND j.status = 'ACTIVE'")
    List<TaxJurisdiction> findVatJurisdictions();
    
    @Query("SELECT j FROM TaxJurisdiction j WHERE j.supportsGst = true AND j.status = 'ACTIVE'")
    List<TaxJurisdiction> findGstJurisdictions();
    
    @Query("SELECT j FROM TaxJurisdiction j WHERE j.supportsWithholding = true AND j.status = 'ACTIVE'")
    List<TaxJurisdiction> findWithholdingJurisdictions();
}