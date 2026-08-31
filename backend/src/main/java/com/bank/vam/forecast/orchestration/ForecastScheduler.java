package com.bank.vam.forecast.orchestration;

import com.bank.vam.entity.Corporate;
import com.bank.vam.entity.Corporate.CorporateStatus;
import com.bank.vam.forecast.domain.ForecastRun;
import com.bank.vam.repository.CorporateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * T8 — orchestrator entry point.
 *
 * <p>Two ways a forecast run is started:
 * <ol>
 *   <li><b>Nightly at 02:30 UTC</b> via the {@link NightlyTrigger} inner
 *       bean — iterates every ACTIVE corporate and invokes
 *       {@link #runGuarded(UUID)}. Per-corporate exceptions are caught so one
 *       bad tenant doesn't sink the rest of the batch.</li>
 *   <li><b>On demand</b> via {@link #triggerOnDemand(UUID)} (called from the
 *       T9 controller). Returns the {@link ForecastRun} the orchestrator
 *       produced.</li>
 * </ol>
 *
 * <p><b>Concurrency:</b> {@link #runGuarded(UUID)} acquires a corporate-scoped
 * mutex around the orchestrator call. When a {@link StringRedisTemplate} bean
 * is wired the lock is a Redis {@code SET NX EX} with a 5-minute TTL —
 * resilient to JVM crashes and (eventually) cluster-wide. When Redis is absent
 * or unreachable, we fall back transparently to an in-process
 * {@code ConcurrentHashMap<UUID, ReentrantLock>} — single-instance correctness
 * only, which is fine for dev where Redis is commented out (CLAUDE.md).
 * Failure to acquire raises {@link ForecastInProgressException} (mapped to
 * HTTP 409 by {@code GlobalExceptionHandler}).
 *
 * <p>The {@code @Scheduled} fire is gated by
 * {@code vam.forecast.schedule.enabled=true} (default). When that flag is
 * false the {@link NightlyTrigger} bean is never instantiated, so the
 * scheduler simply does not fire — verifiable by log absence.
 * {@link #triggerOnDemand(UUID)} remains available regardless, so the T9
 * controller works in every environment.
 */
@Slf4j
@Component
public class ForecastScheduler {

    /** Default Sprint-1 horizon — ~13 weeks. Matches {@code DefaultForecastOrchestrator.DEFAULT_HORIZON_DAYS}. */
    public static final int FORECAST_HORIZON_DAYS = 91;

    /** Five-minute TTL on the distributed lock — covers the longest plausible run + buffer. */
    private static final Duration REDIS_LOCK_TTL = Duration.ofMinutes(5);

    /** Namespace prefix so the key never collides with other Redis users. */
    private static final String LOCK_KEY_PREFIX = "vam:forecast:lock:";

    private final ForecastOrchestrator forecastOrchestrator;
    private final CorporateRepository corporateRepository;
    /** Optional — null at runtime when no Redis bean is wired. */
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;

    /**
     * In-process fallback keyed by corporate id. Sized by working set, lazily
     * populated by {@code computeIfAbsent}. Never cleared — the per-corporate
     * lock object is tiny (~40 bytes) and the set is bounded by tenant count.
     */
    private final ConcurrentHashMap<UUID, ReentrantLock> localLocks = new ConcurrentHashMap<>();

    public ForecastScheduler(
            ForecastOrchestrator forecastOrchestrator,
            CorporateRepository corporateRepository,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider
    ) {
        this.forecastOrchestrator = forecastOrchestrator;
        this.corporateRepository = corporateRepository;
        this.redisTemplateProvider = redisTemplateProvider;
    }

    // ========================================================================
    // Public entry points
    // ========================================================================

    /**
     * Nightly fan-out. Called by {@link NightlyTrigger}. Iterates every ACTIVE
     * corporate and runs guarded; one corporate's failure does not affect the
     * others.
     */
    void runNightly() {
        long t0 = System.currentTimeMillis();
        List<Corporate> active = corporateRepository.findByStatus(CorporateStatus.ACTIVE);
        log.info("Nightly forecast batch started — {} ACTIVE corporate(s)", active.size());

        int ok = 0;
        int failed = 0;
        for (Corporate corporate : active) {
            UUID corporateId = corporate.getId();
            long perStart = System.currentTimeMillis();
            try {
                ForecastRun run = runGuarded(corporateId);
                ok++;
                log.info("Nightly forecast OK — corporate {} ({}), run {} in {} ms",
                        corporateId, corporate.getCorporateId(), run.getId(),
                        System.currentTimeMillis() - perStart);
            } catch (ForecastInProgressException inProgress) {
                failed++;
                log.warn("Nightly forecast SKIPPED — corporate {} already locked: {}",
                        corporateId, inProgress.getMessage());
            } catch (RuntimeException e) {
                failed++;
                log.error("Nightly forecast FAILED — corporate {} ({}) after {} ms",
                        corporateId, corporate.getCorporateId(),
                        System.currentTimeMillis() - perStart, e);
                // Deliberate continue — one bad corporate doesn't sink the batch.
            }
        }

        log.info("Nightly forecast batch finished in {} ms — {} OK / {} failed of {} total",
                System.currentTimeMillis() - t0, ok, failed, active.size());
    }

    /**
     * Treasurer-initiated forecast for a single corporate. Used by the T9
     * controller. Propagates any orchestrator exception to the caller and the
     * {@link ForecastInProgressException} when another run is in flight.
     */
    public ForecastRun triggerOnDemand(UUID corporateId) {
        log.info("On-demand forecast requested for corporate {}", corporateId);
        return runGuarded(corporateId);
    }

    // ========================================================================
    // Locking
    // ========================================================================

    /**
     * Acquire a corporate-scoped lock, run the orchestrator, release the lock
     * in finally. Throws {@link ForecastInProgressException} if the lock is
     * already held.
     */
    private ForecastRun runGuarded(UUID corporateId) {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis != null) {
            return runWithRedisLock(redis, corporateId);
        }
        return runWithLocalLock(corporateId);
    }

    private ForecastRun runWithRedisLock(StringRedisTemplate redis, UUID corporateId) {
        String key = LOCK_KEY_PREFIX + corporateId;
        // Token lets us prove ownership at release time (avoids deleting
        // a successor's lock after a TTL expiry). Sprint-1 use is best-effort —
        // we don't enforce the token check on delete because there's no Lua
        // scripting requirement yet (out of scope per the brief).
        String token = UUID.randomUUID().toString();

        boolean acquired;
        try {
            acquired = Boolean.TRUE.equals(
                    redis.opsForValue().setIfAbsent(key, token, REDIS_LOCK_TTL));
        } catch (RedisConnectionFailureException redisDown) {
            log.warn("Redis lock unavailable for corporate {} ({}); falling back to local lock",
                    corporateId, redisDown.getMessage());
            return runWithLocalLock(corporateId);
        } catch (RuntimeException unexpected) {
            log.warn("Redis lock errored for corporate {} ({}: {}); falling back to local lock",
                    corporateId, unexpected.getClass().getSimpleName(), unexpected.getMessage());
            return runWithLocalLock(corporateId);
        }

        if (!acquired) {
            throw new ForecastInProgressException(corporateId);
        }

        try {
            return forecastOrchestrator.run(corporateId, FORECAST_HORIZON_DAYS);
        } finally {
            try {
                redis.delete(key);
            } catch (RuntimeException releaseErr) {
                // Lock will expire via TTL — log and move on.
                log.warn("Failed to release Redis lock {} for corporate {}: {}",
                        key, corporateId, releaseErr.getMessage());
            }
        }
    }

    private ForecastRun runWithLocalLock(UUID corporateId) {
        ReentrantLock lock = localLocks.computeIfAbsent(corporateId, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            throw new ForecastInProgressException(corporateId);
        }
        try {
            return forecastOrchestrator.run(corporateId, FORECAST_HORIZON_DAYS);
        } finally {
            lock.unlock();
        }
    }

    // ========================================================================
    // Nightly trigger — separate bean so @ConditionalOnProperty cleanly gates
    // only the @Scheduled fire (not triggerOnDemand).
    // ========================================================================

    /**
     * Inner bean that owns the cron-driven nightly fire. Bean is only created
     * when {@code vam.forecast.schedule.enabled=true} (default true), so
     * setting the flag to {@code false} eliminates the trigger entirely and
     * the absence-of-log smoke test passes.
     */
    @Slf4j
    @Component
    @ConditionalOnProperty(name = "vam.forecast.schedule.enabled", havingValue = "true", matchIfMissing = true)
    static class NightlyTrigger {

        private final ForecastScheduler scheduler;

        NightlyTrigger(ForecastScheduler scheduler) {
            this.scheduler = scheduler;
            log.info("ForecastScheduler nightly trigger registered (cron driven by vam.forecast.schedule.cron)");
        }

        @Scheduled(cron = "${vam.forecast.schedule.cron:0 30 2 * * *}", zone = "UTC")
        public void fire() {
            scheduler.runNightly();
        }
    }
}
