package com.bank.vam.repository.treasury;

import com.bank.vam.entity.treasury.IhbInterestAccrual;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface IhbInterestAccrualRepository extends JpaRepository<IhbInterestAccrual, UUID> {

    List<IhbInterestAccrual> findByStatus(IhbInterestAccrual.AccrualStatus status);

    @Query("SELECT a FROM IhbInterestAccrual a WHERE a.entity.id = :entityId ORDER BY a.periodEnd DESC")
    List<IhbInterestAccrual> findByEntityId(@Param("entityId") UUID entityId);

    @Query("SELECT a FROM IhbInterestAccrual a WHERE a.periodStart >= :start AND a.periodEnd <= :end ORDER BY a.entity.entityName")
    List<IhbInterestAccrual> findByPeriod(@Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query("SELECT COALESCE(SUM(a.accruedAmount), 0) FROM IhbInterestAccrual a WHERE a.positionType = 'SURPLUS' AND a.status != 'REVERSED'")
    BigDecimal sumSurplusInterest();

    @Query("SELECT COALESCE(SUM(a.accruedAmount), 0) FROM IhbInterestAccrual a WHERE a.positionType = 'DEFICIT' AND a.status != 'REVERSED'")
    BigDecimal sumDeficitInterest();

    @Query("SELECT COALESCE(SUM(a.accruedAmount), 0) FROM IhbInterestAccrual a " +
           "WHERE a.periodStart >= :monthStart AND a.periodEnd <= :monthEnd AND a.status != 'REVERSED'")
    BigDecimal sumInterestForMonth(@Param("monthStart") LocalDate monthStart, @Param("monthEnd") LocalDate monthEnd);

    @Query("SELECT COALESCE(SUM(a.accruedAmount), 0) FROM IhbInterestAccrual a WHERE a.entity.id = :entityId AND a.status != 'REVERSED'")
    BigDecimal sumAccruedInterestByEntity(@Param("entityId") UUID entityId);
}
