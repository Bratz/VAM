package com.bank.vam.forecast.repository;

import com.bank.vam.forecast.domain.ForecastLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface ForecastLineRepository extends JpaRepository<ForecastLine, UUID> {

    /**
     * All lines in a run with value dates inside {@code [from, to]} (inclusive).
     */
    List<ForecastLine> findByRunIdAndValueDateBetween(UUID runId, LocalDate from, LocalDate to);

    /**
     * Lines filtered to a set of entities and a currency. Used by the cockpit
     * to render the per-currency cash position for a scope selection.
     */
    List<ForecastLine> findByRunIdAndEntityIdInAndCurrency(
            UUID runId, Collection<UUID> entityIds, String currency);

    /**
     * Weekly aggregation of mid amounts grouped by week-start, currency, and
     * cash-flow direction. Returns rows of {@code [week_start, currency,
     * direction, sum_amount_mid]} ordered by week then currency.
     *
     * <p>Native query because {@code date_trunc('week', ...)} is PostgreSQL-
     * specific. {@code week_start} is the Monday of the ISO week.
     */
    @Query(value = """
            SELECT CAST(date_trunc('week', l.value_date) AS date) AS week_start,
                   l.currency                                      AS currency,
                   c.direction                                     AS direction,
                   SUM(l.amount_mid)                               AS sum_amount_mid
            FROM forecast_line l
            JOIN forecast_category c ON c.id = l.category_id
            WHERE l.run_id = :runId
              AND l.value_date BETWEEN :from AND :to
            GROUP BY week_start, l.currency, c.direction
            ORDER BY week_start, l.currency
            """, nativeQuery = true)
    List<Object[]> aggregateWeeklyByCurrencyDirection(
            @Param("runId") UUID runId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
