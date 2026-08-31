package com.bank.vam.forecast.orchestration;

import com.bank.vam.forecast.domain.ForecastAdjustment;
import com.bank.vam.forecast.domain.ForecastCategory;
import com.bank.vam.forecast.domain.ForecastLine;
import com.bank.vam.forecast.domain.ForecastRun;
import com.bank.vam.forecast.domain.enums.RunStatus;
import com.bank.vam.forecast.engine.ForecastContext;
import com.bank.vam.forecast.engine.ForecastEngine;
import com.bank.vam.forecast.repository.ForecastAdjustmentRepository;
import com.bank.vam.forecast.repository.ForecastCategoryRepository;
import com.bank.vam.forecast.repository.ForecastLineRepository;
import com.bank.vam.forecast.repository.ForecastRunRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * T7 — Real {@link ForecastOrchestrator} implementation. Discovers all
 * {@link ForecastEngine} beans via Spring's constructor injection of
 * {@code List<ForecastEngine>}, routes categories to engines, batches
 * persistence, and contains per-engine failures.
 *
 * <p>Transaction shape:
 * <ul>
 *   <li>The {@code RUNNING} row is created in a {@code REQUIRES_NEW}
 *       transaction (delegated to {@link ForecastRunBootstrap#createRunningRow})
 *       and committed before any engine runs — monitoring queries see it
 *       immediately.</li>
 *   <li>The bulk of the orchestration runs inside this method's outer
 *       {@code @Transactional} boundary: load categories + carry-forward,
 *       call each engine, batch-insert lines, mark the run COMPLETED.</li>
 *   <li>On a fatal outer exception, the FAILED state is written through
 *       {@link ForecastRunBootstrap#markFailed} (REQUIRES_NEW) so the
 *       status survives the outer rollback, then the exception is rethrown.</li>
 * </ul>
 *
 * <p>Per-engine failure handling: an exception thrown by
 * {@link ForecastEngine#generate(ForecastContext)} is caught, logged with
 * the engine type + corporate id, and the engine's would-be lines are
 * skipped. The run still completes with the other engines' output. This
 * lets a buggy or data-corrupted engine fail in isolation without sinking
 * the whole forecast.
 *
 * <p>Sprint-1 scope: locking against concurrent runs (T8), DRIVER/ML
 * engines, and variance back-test are explicitly out of scope here.
 */
@Slf4j
@Service
public class DefaultForecastOrchestrator implements ForecastOrchestrator {

    /** Default horizon (≈13 weeks) when callers don't supply one. */
    public static final int DEFAULT_HORIZON_DAYS = 91;

    /** JDBC batch-insert chunk size used when persisting forecast lines. */
    private static final int LINE_BATCH_SIZE = 500;

    /** Actor tag stamped on orchestrator-created runs. */
    private static final String SYSTEM_ACTOR = "system";

    private final ForecastRunBootstrap bootstrap;
    private final ForecastRunRepository runRepository;
    private final ForecastLineRepository lineRepository;
    private final ForecastCategoryRepository categoryRepository;
    private final ForecastAdjustmentRepository adjustmentRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final List<ForecastEngine> engines;

    public DefaultForecastOrchestrator(
        ForecastRunBootstrap bootstrap,
        ForecastRunRepository runRepository,
        ForecastLineRepository lineRepository,
        ForecastCategoryRepository categoryRepository,
        ForecastAdjustmentRepository adjustmentRepository,
        LegalEntityRepository legalEntityRepository,
        List<ForecastEngine> engines
    ) {
        this.bootstrap = bootstrap;
        this.runRepository = runRepository;
        this.lineRepository = lineRepository;
        this.categoryRepository = categoryRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.legalEntityRepository = legalEntityRepository;
        this.engines = engines;
        log.info("DefaultForecastOrchestrator initialised with {} engine(s): {}",
            engines.size(),
            engines.stream().map(e -> e.type().name()).collect(Collectors.joining(", ")));
    }

    @Override
    @Transactional
    public ForecastRun run(UUID corporateId, int horizonDays) {
        long t0 = System.currentTimeMillis();
        ForecastRun run = bootstrap.createRunningRow(corporateId, horizonDays, SYSTEM_ACTOR);
        log.info("Forecast run {} started for corporate {} ({}d horizon)",
            run.getId(), corporateId, horizonDays);
        try {
            Set<UUID> entityIds = resolveEntityIds(corporateId);
            LocalDate today = LocalDate.now();
            LocalDate horizonEnd = today.plusDays(horizonDays);

            List<ForecastCategory> allCategories = categoryRepository.findAll();
            List<ForecastAdjustment> carryForward = loadCarryForward(corporateId);

            List<ForecastLine> allLines = new ArrayList<>();
            for (ForecastEngine engine : engines) {
                List<ForecastCategory> supported = allCategories.stream()
                    .filter(engine::supports)
                    .toList();
                if (supported.isEmpty()) {
                    log.debug("Engine {} supports 0 categories; skipping", engine.type());
                    continue;
                }
                ForecastContext ctx = new ForecastContext(
                    run, corporateId, entityIds, supported, carryForward,
                    today, horizonEnd, today
                );
                try {
                    List<ForecastLine> lines = engine.generate(ctx);
                    if (lines != null && !lines.isEmpty()) {
                        allLines.addAll(lines);
                    }
                    log.debug("Engine {} emitted {} lines for corporate {}",
                        engine.type(), lines == null ? 0 : lines.size(), corporateId);
                } catch (Exception e) {
                    log.error("Engine {} failed for corporate {} — its categories produce 0 lines this run; other engines continue",
                        engine.type(), corporateId, e);
                    // Deliberate continue — per Sprint 1 spec, one bad engine
                    // does NOT sink the run.
                }
            }

            persistInBatches(allLines);

            long generationMsLong = System.currentTimeMillis() - t0;
            // Saturate to Integer.MAX_VALUE if a run somehow exceeds ~24 days —
            // the column is INTEGER per V13 and Integer per the entity.
            int generationMs = generationMsLong > Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : (int) generationMsLong;
            run.setStatus(RunStatus.COMPLETED);
            run.setGenerationMs(generationMs);
            ForecastRun persisted = runRepository.save(run);
            log.info("Forecast run {} COMPLETED in {} ms — {} lines persisted across {} engine(s)",
                persisted.getId(), generationMs, allLines.size(), engines.size());
            return persisted;
        } catch (RuntimeException fatal) {
            log.error("Forecast run {} FAILED for corporate {} — marking FAILED in REQUIRES_NEW txn and rethrowing",
                run.getId(), corporateId, fatal);
            bootstrap.markFailed(run.getId());
            throw fatal;
        }
    }

    /**
     * Resolve all legal entity ids under the corporate (entire subtree). The
     * {@code legal_entity.corporate_id} column already scopes the subtree —
     * no recursive hierarchy walk needed.
     *
     * <p>Fallback for test/edge cases where no LegalEntity rows exist for
     * the corporate yet: use {@code {corporateId}} as a single-element set
     * so a freshly-seeded test environment isn't silently a no-op.
     */
    private Set<UUID> resolveEntityIds(UUID corporateId) {
        Set<UUID> ids = legalEntityRepository.findByCorporateIdOrderByHierarchyPath(corporateId)
            .stream()
            .map(le -> le.getId())
            .collect(Collectors.toCollection(HashSet::new));
        if (ids.isEmpty()) {
            log.warn("No LegalEntity rows under corporate {}; falling back to single-element set {{corporateId}}",
                corporateId);
            ids.add(corporateId);
        }
        return ids;
    }

    /**
     * Load carry-forward adjustments from the most-recent prior COMPLETED
     * run for the same corporate. Empty when this is the corporate's first
     * run, when the prior run has no adjustments, or when the prior run
     * itself failed.
     */
    private List<ForecastAdjustment> loadCarryForward(UUID corporateId) {
        Optional<ForecastRun> priorRun = runRepository
            .findFirstByCorporateIdAndStatusOrderByRunAtDesc(corporateId, RunStatus.COMPLETED);
        if (priorRun.isEmpty()) {
            return List.of();
        }
        List<ForecastAdjustment> carry = adjustmentRepository.findByRunId(priorRun.get().getId());
        log.debug("Carry-forward: {} adjustment(s) from prior COMPLETED run {} for corporate {}",
            carry.size(), priorRun.get().getId(), corporateId);
        return carry;
    }

    /**
     * Persist lines in fixed-size chunks. Hibernate's
     * {@code spring.jpa.properties.hibernate.jdbc.batch_size} setting
     * controls the JDBC batch internally — this loop bounds the number of
     * managed entities the persistence context tracks at once, so a
     * many-thousand-line run doesn't blow heap.
     */
    private void persistInBatches(List<ForecastLine> lines) {
        if (lines.isEmpty()) {
            return;
        }
        for (int i = 0; i < lines.size(); i += LINE_BATCH_SIZE) {
            int end = Math.min(i + LINE_BATCH_SIZE, lines.size());
            lineRepository.saveAll(lines.subList(i, end));
        }
    }
}
