package com.bank.vam.forecast.orchestration;

import com.bank.vam.forecast.domain.ForecastRun;

import java.util.UUID;

/**
 * Runs a forecast for one corporate over a given horizon and persists the
 * resulting {@link ForecastRun} + {@code ForecastLine} rows.
 *
 * <p>The Sprint 1 implementation is {@link DefaultForecastOrchestrator}, which
 * discovers all {@code ForecastEngine} beans via Spring, routes categories to
 * engines, batch-persists lines, and contains per-engine failures so one bad
 * engine never sinks the run.
 *
 * <p>The single entry point most callers want is {@link ForecastScheduler}
 * (its {@code triggerOnDemand(corporateId)} and nightly cron both delegate
 * here, after acquiring a per-corporate mutex). Call this interface directly
 * only when you explicitly want to bypass that mutex.
 *
 * @return the persisted {@link ForecastRun} entity
 */
public interface ForecastOrchestrator {

    ForecastRun run(UUID corporateId, int horizonDays);
}
