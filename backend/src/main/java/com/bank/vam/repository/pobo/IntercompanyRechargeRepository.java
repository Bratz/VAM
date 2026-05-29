package com.bank.vam.repository.pobo;

import com.bank.vam.entity.pobo.IntercompanyRecharge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Intercompany Recharge entities.
 */
@Repository
public interface IntercompanyRechargeRepository extends JpaRepository<IntercompanyRecharge, UUID> {

    Optional<IntercompanyRecharge> findByRechargeReference(String rechargeReference);
    
    List<IntercompanyRecharge> findByPayerEntityId(UUID payerEntityId);
    
    List<IntercompanyRecharge> findByBehalfEntityId(UUID behalfEntityId);
    
    List<IntercompanyRecharge> findByOriginalPayableId(UUID payableId);
    
    List<IntercompanyRecharge> findByStatus(IntercompanyRecharge.RechargeStatus status);
    
    @Query("SELECT ir FROM IntercompanyRecharge ir WHERE ir.status = 'PENDING'")
    List<IntercompanyRecharge> findPendingRecharges();
    
    @Query("SELECT ir FROM IntercompanyRecharge ir WHERE ir.status IN ('APPROVED', 'RECHARGED') " +
           "AND ir.settlementDate IS NULL")
    List<IntercompanyRecharge> findUnsettledRecharges();
    
    @Query("SELECT ir FROM IntercompanyRecharge ir WHERE ir.payerEntityId = :payerId " +
           "AND ir.behalfEntityId = :behalfId AND ir.status IN ('PENDING', 'APPROVED', 'RECHARGED')")
    List<IntercompanyRecharge> findActiveByPayerAndBehalf(
        @Param("payerId") UUID payerEntityId,
        @Param("behalfId") UUID behalfEntityId);
    
    @Query("SELECT COALESCE(SUM(ir.totalRecharge), 0) FROM IntercompanyRecharge ir " +
           "WHERE ir.behalfEntityId = :entityId AND ir.status IN ('APPROVED', 'RECHARGED')")
    BigDecimal sumOutstandingForEntity(@Param("entityId") UUID entityId);
    
    @Query("SELECT ir FROM IntercompanyRecharge ir WHERE ir.armLengthValidated = false " +
           "AND ir.status = 'PENDING'")
    List<IntercompanyRecharge> findPendingArmLengthValidation();
    
    @Query("SELECT ir FROM IntercompanyRecharge ir WHERE ir.settlementDate >= :fromDate " +
           "AND ir.settlementDate <= :toDate")
    List<IntercompanyRecharge> findBySettlementDateRange(
        @Param("fromDate") LocalDate fromDate,
        @Param("toDate") LocalDate toDate);
    
    @Query("SELECT ir.behalfEntityCode, SUM(ir.totalRecharge) FROM IntercompanyRecharge ir " +
           "WHERE ir.payerEntityId = :payerId AND ir.status != 'CANCELLED' " +
           "GROUP BY ir.behalfEntityCode")
    List<Object[]> summarizeByBehalfEntity(@Param("payerId") UUID payerEntityId);
    
    @Query("SELECT ir FROM IntercompanyRecharge ir WHERE ir.createsIntercompanyLoan = true " +
           "AND ir.ihbLoanId IS NOT NULL")
    List<IntercompanyRecharge> findWithIhbLoans();

    // ========================================================================
    // RECHARGE TYPE & FLOW DIRECTION QUERIES
    // ========================================================================

    /**
     * Find all recharges by recharge type.
     */
    List<IntercompanyRecharge> findByRechargeType(IntercompanyRecharge.RechargeType rechargeType);

    /**
     * Find all recharges by flow direction.
     */
    List<IntercompanyRecharge> findByFlowDirection(IntercompanyRecharge.FlowDirection flowDirection);

    /**
     * Find recharges by corporate (payer) entity ID and recharge type.
     */
    @Query("SELECT ir FROM IntercompanyRecharge ir WHERE ir.payerEntityId = :corporateId " +
           "AND ir.rechargeType = :rechargeType")
    List<IntercompanyRecharge> findByCorporateIdAndRechargeType(
        @Param("corporateId") UUID corporateId,
        @Param("rechargeType") IntercompanyRecharge.RechargeType rechargeType);
}