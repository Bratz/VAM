package com.bank.vam.forecast.repository;

import com.bank.vam.entity.payables.Payable;
import com.bank.vam.entity.payables.Payable.PayableStatus;
import com.bank.vam.entity.payables.Payable.PayableType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Forecast-scoped read view onto {@link Payable}. Separate from
 * {@code PayableRepository} so the forecast module owns its own query shape
 * without coupling to the AP domain's churn.
 *
 * <p>Spring Data permits multiple {@code JpaRepository} beans against the
 * same entity — both this repo and {@code PayableRepository} happily coexist.
 */
@Repository
public interface PayableForecastRepository extends JpaRepository<Payable, UUID> {

    /**
     * Open scheduled outflows whose value date falls inside the horizon, used
     * by the {@code PatternEngine} to emit per-payable forecast lines.
     *
     * <p>The {@code closedStatuses} parameter is the set of
     * {@link PayableStatus} values the caller wants excluded — typically
     * PAID / CANCELLED / REJECTED / NETTED. Passing it explicitly (rather
     * than hard-coding inside the query) keeps the "what counts as closed"
     * semantic in the engine and lets the Hibernate JPQL parser validate
     * the query at startup without choking on fully-qualified enum literals.
     *
     * @param entityIds      legal entities in scope (subtree of the corporate)
     * @param types          PayableType values the engine treats as
     *                       pattern-like (SALARY, TAX, UTILITY, INTERCOMPANY,
     *                       OTHER)
     * @param closedStatuses PayableStatus values to exclude — settled rows
     *                       that must not be forecast again
     * @param from           inclusive lower bound on {@code due_date}
     * @param to             inclusive upper bound on {@code due_date}
     */
    @Query("""
        SELECT p FROM Payable p
        WHERE p.owningEntityId IN :entityIds
          AND p.payableType IN :types
          AND p.status NOT IN :closedStatuses
          AND p.dueDate IS NOT NULL
          AND p.dueDate BETWEEN :from AND :to
        """)
    List<Payable> findPatternCandidates(
        @Param("entityIds") Set<UUID> entityIds,
        @Param("types") Set<PayableType> types,
        @Param("closedStatuses") Set<PayableStatus> closedStatuses,
        @Param("from") LocalDate from,
        @Param("to") LocalDate to
    );
}
