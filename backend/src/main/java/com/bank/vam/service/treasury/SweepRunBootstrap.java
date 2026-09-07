package com.bank.vam.service.treasury;

import com.bank.vam.entity.treasury.SweepRun;
import com.bank.vam.entity.treasury.SweepRunStatus;
import com.bank.vam.repository.treasury.SweepRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Companion to {@link SweepService}'s async execution path — copies the
 * {@code ForecastRunBootstrap} pattern: a separate bean whose only purpose is
 * to carry the {@code REQUIRES_NEW} transaction boundary around state
 * transitions that must be visible to other transactions (i.e. a poller
 * hitting {@code GET /runs/{runId}}) even while the caller's own transaction
 * (or, for the async worker, its lack of one) is still open.
 *
 * <ol>
 *   <li>{@link #createRunningRow} — committed BEFORE the {@code sweepExecutor}
 *       thread starts, so a poller sees the row immediately.</li>
 *   <li>{@link #updateProgress} — committed after each source completes, so
 *       progress counters are live rather than only visible at the end.</li>
 *   <li>{@link #markCompleted} / {@link #markFailed} — the terminal
 *       transitions.</li>
 * </ol>
 *
 * <p>This MUST be a separate Spring-managed bean — Spring's default
 * proxy-based transaction interception ignores self-invocation, so
 * {@code SweepService} calling its own {@code @Transactional(REQUIRES_NEW)}
 * method would silently inherit the caller's transaction (or, on the
 * {@code @Async} worker thread, run with no transaction at all) and defeat
 * the purpose.
 */
@Component
@RequiredArgsConstructor
public class SweepRunBootstrap {

    private final SweepRunRepository runRepository;

    /**
     * Insert a fresh {@code RUNNING} row in a brand-new transaction and
     * return it. Committed immediately on method return.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SweepRun createRunningRow(String createdBy, List<UUID> ruleIds, int sourcesTotal) {
        SweepRun run = SweepRun.builder()
                .createdBy(createdBy)
                .startedAt(LocalDateTime.now())
                .status(SweepRunStatus.RUNNING)
                .ruleIds(ruleIds == null || ruleIds.isEmpty() ? null :
                        ruleIds.stream().map(UUID::toString).collect(Collectors.joining(",")))
                .sourcesTotal(sourcesTotal)
                .build();
        return runRepository.save(run);
    }

    /**
     * Persist live progress counters as sources complete. Called once per
     * source from the async worker — the run is re-read inside this
     * transaction so we never carry a detached instance across the boundary.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateProgress(UUID runId, int sourcesProcessed, int successCount, int failedCount) {
        runRepository.findById(runId).ifPresent(r -> {
            r.setSourcesProcessed(sourcesProcessed);
            r.setSuccessCount(successCount);
            r.setFailedCount(failedCount);
            runRepository.save(r);
        });
    }

    /**
     * Move a run to {@code COMPLETED} with its final counts, in a brand-new
     * transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(UUID runId, int sourcesProcessed, int successCount, int failedCount) {
        runRepository.findById(runId).ifPresent(r -> {
            r.setStatus(SweepRunStatus.COMPLETED);
            r.setSourcesProcessed(sourcesProcessed);
            r.setSuccessCount(successCount);
            r.setFailedCount(failedCount);
            runRepository.save(r);
        });
    }

    /**
     * Move a run to {@code FAILED} in a brand-new transaction so the new
     * status survives even if the caller's outer transaction is rolling
     * back (or, for the async worker, is caught inside its own catch block).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID runId, String errorMessage) {
        runRepository.findById(runId).ifPresent(r -> {
            r.setStatus(SweepRunStatus.FAILED);
            r.setErrorMessage(errorMessage);
            runRepository.save(r);
        });
    }
}
