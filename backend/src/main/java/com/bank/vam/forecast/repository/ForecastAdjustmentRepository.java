package com.bank.vam.forecast.repository;

import com.bank.vam.forecast.domain.ForecastAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ForecastAdjustmentRepository extends JpaRepository<ForecastAdjustment, UUID> {

    /**
     * All adjustments belonging to a given run. Used by the orchestrator to
     * load carry-forward overlays from the previous COMPLETED run.
     */
    List<ForecastAdjustment> findByRunId(UUID runId);
}
