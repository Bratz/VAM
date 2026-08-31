package com.bank.vam.repository.tax;

import com.bank.vam.entity.tax.CalculatedCharge;
import com.bank.vam.entity.tax.ChargeConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Repository for Calculated Charge entities.
 */
@Repository
public interface CalculatedChargeRepository extends JpaRepository<CalculatedCharge, UUID> {

    List<CalculatedCharge> findByReferenceTypeAndReferenceId(
        CalculatedCharge.ReferenceType referenceType, UUID referenceId);
    
    List<CalculatedCharge> findByChargeCode(String chargeCode);
    
    List<CalculatedCharge> findByChargeType(ChargeConfiguration.ChargeType chargeType);
    
    List<CalculatedCharge> findByStatus(CalculatedCharge.CalculationStatus status);
    
    @Query("SELECT cc FROM CalculatedCharge cc WHERE cc.referenceType = 'PAYABLE' " +
           "AND cc.referenceId = :payableId")
    List<CalculatedCharge> findByPayableId(@Param("payableId") UUID payableId);
    
    @Query("SELECT cc FROM CalculatedCharge cc WHERE cc.referenceType = 'PAYMENT_EXECUTION' " +
           "AND cc.referenceId = :executionId")
    List<CalculatedCharge> findByPaymentExecutionId(@Param("executionId") UUID executionId);
    
    @Query("SELECT COALESCE(SUM(cc.finalCharge), 0) FROM CalculatedCharge cc " +
           "WHERE cc.referenceType = :type AND cc.referenceId = :refId")
    BigDecimal sumChargesForReference(
        @Param("type") CalculatedCharge.ReferenceType referenceType,
        @Param("refId") UUID referenceId);
    
    @Query("SELECT cc FROM CalculatedCharge cc WHERE cc.waivedAmount > 0")
    List<CalculatedCharge> findWaivedCharges();
    
    @Query("SELECT cc.chargeCode, SUM(cc.finalCharge), SUM(cc.waivedAmount) " +
           "FROM CalculatedCharge cc " +
           "WHERE cc.status = 'APPLIED' AND cc.createdAt >= :fromDate " +
           "GROUP BY cc.chargeCode")
    List<Object[]> summarizeChargesByCode(@Param("fromDate") LocalDateTime fromDate);
    
    @Query("SELECT COALESCE(SUM(cc.waivedAmount), 0) FROM CalculatedCharge cc " +
           "WHERE cc.createdAt >= :fromDate")
    BigDecimal sumWaivedAmount(@Param("fromDate") LocalDateTime fromDate);
}