package com.bank.vam.forecast.orchestration;

import com.bank.vam.forecast.domain.ForecastRun;
import com.bank.vam.forecast.domain.enums.RunStatus;
import com.bank.vam.forecast.repository.ForecastRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Companion to {@link DefaultForecastOrchestrator} whose only purpose is to
 * carry the {@code REQUIRES_NEW} transaction boundary around the two state
 * transitions that must be visible to other transactions even when the
 * orchestrator's outer transaction is still open or rolling back:
 *
 * <ol>
 *   <li>{@link #createRunningRow} — committed BEFORE engines start so a
 *       monitoring query immediately sees a {@code RUNNING} row.</li>
 *   <li>{@link #markFailed} — committed AFTER an outer-transaction failure
 *       so the {@code FAILED} state survives the outer rollback.</li>
 * </ol>
 *
 * <p>This MUST be a separate Spring-managed bean — Spring's default
 * proxy-based transaction interception ignores self-invocations, so the
 * orchestrator calling its own {@code @Transactional(REQUIRES_NEW)} method
 * would silently inherit the outer transaction and defeat the purpose.
 */
@Component
@RequiredArgsConstructor
public class ForecastRunBootstrap {

    private final ForecastRunRepository runRepository;

    /**
     * Insert a fresh {@code RUNNING} row in a brand-new transaction and
     * return it. Committed immediately on method return.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ForecastRun createRunningRow(UUID corporateId, int horizonDays, String createdBy) {
        ForecastRun run = ForecastRun.builder()
            .corporateId(corporateId)
            .runAt(OffsetDateTime.now())
            .horizonEnd(LocalDate.now().plusDays(horizonDays))
            .status(RunStatus.RUNNING)
            .createdBy(createdBy)
            .build();
        return runRepository.save(run);
    }

    /**
     * Move a run to {@code FAILED} in a brand-new transaction so the new
     * status survives even if the caller's outer transaction is rolling
     * back. The run is re-read inside this transaction so we never carry
     * a detached / orphaned instance across the boundary.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID runId) {
        runRepository.findById(runId).ifPresent(r -> {
            r.setStatus(RunStatus.FAILED);
            runRepository.save(r);
        });
    }
}
