package com.bank.vam.repository.treasury;

import com.bank.vam.entity.treasury.PoolInterestCalculation;
import com.bank.vam.entity.treasury.PoolInterestCalculation.CalculationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.time.LocalDate;
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
}
