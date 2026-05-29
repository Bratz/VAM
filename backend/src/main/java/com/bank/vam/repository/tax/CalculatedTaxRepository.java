package com.bank.vam.repository.tax;

import com.bank.vam.entity.tax.CalculatedTax;
import com.bank.vam.entity.tax.TaxConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Repository for Calculated Tax entities.
 */
@Repository
public interface CalculatedTaxRepository extends JpaRepository<CalculatedTax, UUID> {

    List<CalculatedTax> findByReferenceTypeAndReferenceId(
        CalculatedTax.ReferenceType referenceType, UUID referenceId);
    
    List<CalculatedTax> findByTaxCode(String taxCode);
    
    List<CalculatedTax> findByTaxType(TaxConfiguration.TaxType taxType);
    
    List<CalculatedTax> findByStatus(CalculatedTax.CalculationStatus status);
    
    List<CalculatedTax> findByIsWithholding(Boolean isWithholding);
    
    @Query("SELECT ct FROM CalculatedTax ct WHERE ct.referenceType = 'PAYABLE' " +
           "AND ct.referenceId = :payableId")
    List<CalculatedTax> findByPayableId(@Param("payableId") UUID payableId);
    
    @Query("SELECT COALESCE(SUM(ct.finalTax), 0) FROM CalculatedTax ct " +
           "WHERE ct.referenceType = :type AND ct.referenceId = :refId")
    BigDecimal sumTaxForReference(
        @Param("type") CalculatedTax.ReferenceType referenceType,
        @Param("refId") UUID referenceId);
    
    @Query("SELECT ct FROM CalculatedTax ct WHERE ct.isWithholding = true " +
           "AND ct.status IN ('CALCULATED', 'APPLIED') " +
           "AND ct.createdAt >= :fromDate")
    List<CalculatedTax> findPendingWithholdingTaxes(@Param("fromDate") LocalDateTime fromDate);
    
    @Query("SELECT ct.taxCode, SUM(ct.finalTax) FROM CalculatedTax ct " +
           "WHERE ct.status = 'APPLIED' AND ct.createdAt >= :fromDate " +
           "GROUP BY ct.taxCode")
    List<Object[]> sumTaxByCode(@Param("fromDate") LocalDateTime fromDate);
}