package com.bank.vam.repository.tax;

import com.bank.vam.entity.payables.Payable;
import com.bank.vam.entity.tax.ChargeConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Charge Configuration entities.
 */
@Repository
public interface ChargeConfigurationRepository extends JpaRepository<ChargeConfiguration, UUID> {

    Optional<ChargeConfiguration> findByChargeCode(String chargeCode);
    
    List<ChargeConfiguration> findByChargeType(ChargeConfiguration.ChargeType chargeType);
    
    List<ChargeConfiguration> findByChargeCategory(ChargeConfiguration.ChargeCategory category);
    
    List<ChargeConfiguration> findByStatus(ChargeConfiguration.ChargeStatus status);
    
    @Query("SELECT c FROM ChargeConfiguration c WHERE c.status = 'ACTIVE' " +
           "AND (c.effectiveTo IS NULL OR c.effectiveTo >= CURRENT_DATE)")
    List<ChargeConfiguration> findAllActive();
    
    @Query("SELECT c FROM ChargeConfiguration c WHERE c.chargeCode = :code " +
           "AND c.status = 'ACTIVE' " +
           "AND (c.effectiveTo IS NULL OR c.effectiveTo >= CURRENT_DATE)")
    Optional<ChargeConfiguration> findActiveByCode(@Param("code") String chargeCode);
    
    @Query("SELECT c FROM ChargeConfiguration c WHERE c.chargeType = :type " +
           "AND c.status = 'ACTIVE' " +
           "AND (c.effectiveTo IS NULL OR c.effectiveTo >= CURRENT_DATE)")
    List<ChargeConfiguration> findActiveByType(@Param("type") ChargeConfiguration.ChargeType chargeType);
    
    @Query("SELECT c FROM ChargeConfiguration c WHERE c.appliesToPaymentMethod = :method " +
           "AND c.status = 'ACTIVE' " +
           "AND (c.effectiveTo IS NULL OR c.effectiveTo >= CURRENT_DATE)")
    List<ChargeConfiguration> findActiveByPaymentMethod(@Param("method") Payable.PaymentMethod method);
    
    @Query("SELECT c FROM ChargeConfiguration c WHERE c.appliesToPriority = :priority " +
           "AND c.status = 'ACTIVE' " +
           "AND (c.effectiveTo IS NULL OR c.effectiveTo >= CURRENT_DATE)")
    List<ChargeConfiguration> findActiveByPriority(@Param("priority") Payable.PaymentPriority priority);
    
    @Query("SELECT c FROM ChargeConfiguration c WHERE c.isCrossBorder = true " +
           "AND c.status = 'ACTIVE' " +
           "AND (c.effectiveTo IS NULL OR c.effectiveTo >= CURRENT_DATE)")
    List<ChargeConfiguration> findCrossBorderCharges();
    
    @Query("SELECT c FROM ChargeConfiguration c WHERE c.isDomestic = true " +
           "AND c.status = 'ACTIVE' " +
           "AND (c.effectiveTo IS NULL OR c.effectiveTo >= CURRENT_DATE)")
    List<ChargeConfiguration> findDomesticCharges();
    
    @Query("SELECT c FROM ChargeConfiguration c WHERE c.chargeType = 'POBO_FEE' " +
           "AND c.status = 'ACTIVE' " +
           "AND (c.effectiveTo IS NULL OR c.effectiveTo >= CURRENT_DATE)")
    Optional<ChargeConfiguration> findActivePoboFee();
}