package com.bank.vam.repository.treasury;

import com.bank.vam.entity.treasury.PoolInterestCalculation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PoolInterestCalculationRepository extends JpaRepository<PoolInterestCalculation, UUID> {

    Optional<PoolInterestCalculation> findByPoolIdAndValueDate(UUID poolId, LocalDate valueDate);

    List<PoolInterestCalculation> findByPoolIdAndValueDateBetweenOrderByValueDateDesc(UUID poolId, LocalDate from, LocalDate to);

    boolean existsByPoolId(UUID poolId);
}
