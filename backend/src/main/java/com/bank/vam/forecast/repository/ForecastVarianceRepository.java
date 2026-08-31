package com.bank.vam.forecast.repository;

import com.bank.vam.forecast.domain.ForecastVariance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface ForecastVarianceRepository extends JpaRepository<ForecastVariance, UUID> {

    /**
     * Variance rows for a run with value dates inside {@code [from, to]}
     * (inclusive). Drives the Sprint 2 variance heat-map view.
     */
    List<ForecastVariance> findByRunIdAndValueDateBetween(UUID runId, LocalDate from, LocalDate to);

    /**
     * Variance rows for a category within a run — for drill-down from the
     * heat-map into a single category's accuracy trend.
     */
    List<ForecastVariance> findByRunIdAndCategoryId(UUID runId, UUID categoryId);

    /**
     * Aggregate accuracy roll-up per category for a run.
     * Returns rows of {@code [categoryId, sumForecast, sumActual, sumVariance, rowCount]}.
     * The Sprint 2 variance dashboard divides {@code sumVariance / sumForecast}
     * for a weighted mean error per category.
     */
    @Query("""
            SELECT v.categoryId,
                   SUM(v.forecastAmount),
                   SUM(v.actualAmount),
                   SUM(v.variance),
                   COUNT(v)
            FROM ForecastVariance v
            WHERE v.runId = :runId
            GROUP BY v.categoryId
            """)
    List<Object[]> aggregateAccuracyByCategory(@Param("runId") UUID runId);
}
