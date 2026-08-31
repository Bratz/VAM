package com.bank.vam.forecast.repository;

import com.bank.vam.forecast.domain.ForecastRun;
import com.bank.vam.forecast.domain.enums.RunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ForecastRunRepository extends JpaRepository<ForecastRun, UUID> {

    /**
     * Most recent run in the given status for a corporate — typically used
     * with {@link RunStatus#COMPLETED} to find the latest readable run.
     */
    Optional<ForecastRun> findFirstByCorporateIdAndStatusOrderByRunAtDesc(
            UUID corporateId, RunStatus status);

    /**
     * Most recent run for a corporate regardless of status (operational
     * dashboards may want to see a {@code RUNNING} or {@code FAILED} run).
     */
    @Query("SELECT r FROM ForecastRun r " +
           "WHERE r.corporateId = :corporateId " +
           "ORDER BY r.runAt DESC " +
           "LIMIT 1")
    Optional<ForecastRun> findLatestByCorporateId(@Param("corporateId") UUID corporateId);
}
