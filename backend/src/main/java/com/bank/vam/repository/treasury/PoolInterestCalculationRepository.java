package com.bank.vam.repository.treasury;

import com.bank.vam.entity.treasury.PoolInterestCalculation;
import com.bank.vam.entity.treasury.PoolInterestCalculation.CalculationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PoolInterestCalculationRepository extends JpaRepository<PoolInterestCalculation, UUID> {

    /** The current run for a date: pass SUPERSEDED (at most one other, V52). */
    Optional<PoolInterestCalculation> findByPoolIdAndValueDateAndStatusNot(UUID poolId, LocalDate valueDate, CalculationStatus not);

    List<PoolInterestCalculation> findByPoolIdAndValueDateBetweenOrderByValueDateDesc(UUID poolId, LocalDate from, LocalDate to);

    /** Runs to post, locked (FOR UPDATE) so two postings of the same month can't both pick them up. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<PoolInterestCalculation> findByPoolIdAndStatusAndValueDateBetween(UUID poolId, CalculationStatus status, LocalDate from, LocalDate to);

    boolean existsByPoolId(UUID poolId);

    /** Current runs of these pools in a date range (the pools' figures, R15). */
    List<PoolInterestCalculation> findByPoolIdInAndStatusNotAndValueDateBetween(Collection<UUID> poolIds, CalculationStatus not,
                                                                              LocalDate from, LocalDate to);

    /** [currency, Σ allocated interest] of these accounts' lines in current runs of the date range. */
    @Query("SELECT r.pool.poolCurrency, SUM(l.allocatedInterest) FROM PoolInterestLine l JOIN l.calculation r"
            + " WHERE l.accountId IN :accountIds AND r.status <> :not AND r.valueDate BETWEEN :from AND :to"
            + " GROUP BY r.pool.poolCurrency")
    List<Object[]> sumAllocatedByCurrency(@Param("accountIds") Collection<UUID> accountIds, @Param("not") CalculationStatus not,
                                          @Param("from") LocalDate from, @Param("to") LocalDate to);
}
