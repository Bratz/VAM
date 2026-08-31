package com.bank.vam.forecast.repository;

import com.bank.vam.forecast.domain.ForecastScenario;
import com.bank.vam.forecast.domain.enums.ScenarioType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ForecastScenarioRepository extends JpaRepository<ForecastScenario, UUID> {

    /**
     * All scenarios attached to a run. Powers the "Scenarios" toggle on the
     * dashboard (Sprint 3) — each scenario projects a transformed view of
     * the same underlying {@code forecast_line} rows.
     */
    List<ForecastScenario> findByRunId(UUID runId);

    /**
     * Locate the base scenario for a run. Every COMPLETED run is expected to
     * carry exactly one {@link ScenarioType#BASE} row; UPSIDE / DOWNSIDE /
     * WHATIF rows are optional overlays on top of it.
     */
    Optional<ForecastScenario> findByRunIdAndType(UUID runId, ScenarioType type);
}
