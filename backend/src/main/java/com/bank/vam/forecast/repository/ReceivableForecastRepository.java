package com.bank.vam.forecast.repository;

import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.entity.receivables.Receivable.ReceivableStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Forecast-scoped read view onto {@link Receivable}. See
 * {@link PayableForecastRepository} for the rationale on a forecast-owned
 * repository alongside the AR domain's own {@code ReceivableRepository}.
 */
@Repository
public interface ReceivableForecastRepository extends JpaRepository<Receivable, UUID> {

    /**
     * Open AR balances with a positive outstanding amount, used by the
     * {@code AgingEngine} to project collection dates.
     *
     * <p>The {@code statuses} parameter is the set of {@link ReceivableStatus}
     * values the caller treats as "in-flight" — typically OPEN / PARTIAL /
     * OVERDUE. Passing it explicitly (rather than hard-coding inside the
     * query) keeps the "what counts as collectable" semantic in the engine
     * and lets the Hibernate JPQL parser validate the query at startup
     * without choking on fully-qualified enum literals.
     *
     * <p>The engine clips projected value dates to the horizon in code; this
     * query intentionally does NOT filter on {@code dueDate} because an
     * already-overdue receivable's projected collection date is
     * {@code today + agingShift}, not {@code dueDate}.
     */
    @Query("""
        SELECT r FROM Receivable r
        WHERE r.owningEntityId IN :entityIds
          AND r.status IN :statuses
          AND r.outstandingAmount IS NOT NULL
          AND r.outstandingAmount > 0
        """)
    List<Receivable> findOpenForAging(
        @Param("entityIds") Set<UUID> entityIds,
        @Param("statuses") Set<ReceivableStatus> statuses
    );
}
