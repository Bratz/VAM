package com.bank.vam.forecast.orchestration;

import com.bank.vam.forecast.domain.ForecastRun;
import com.bank.vam.forecast.domain.enums.RunStatus;
import com.bank.vam.repository.CorporateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ForecastScheduler}.
 *
 * <p>Sprint-1 / T8 acceptance gate: two rapid calls to
 * {@link ForecastScheduler#triggerOnDemand(UUID)} for the same corporate must
 * result in the second call throwing {@link ForecastInProgressException} while
 * the first holds the lock — proven here with a {@link CountDownLatch} gate
 * inside the mocked orchestrator.
 *
 * <p>No Spring context — the scheduler's collaborators are all mockable.
 * Redis-absent path is exercised via an {@link ObjectProvider} whose
 * {@code getIfAvailable()} returns {@code null}; the scheduler then takes
 * the {@link java.util.concurrent.locks.ReentrantLock} fallback.
 */
class ForecastSchedulerTest {

    private static final UUID CORPORATE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_CORPORATE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    /** Builds an ObjectProvider that mimics "no StringRedisTemplate bean wired". */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> noRedis() {
        ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private static ForecastRun stubRun(UUID corporateId) {
        return ForecastRun.builder()
                .id(UUID.randomUUID())
                .corporateId(corporateId)
                .runAt(OffsetDateTime.now())
                .horizonEnd(LocalDate.now().plusDays(ForecastScheduler.FORECAST_HORIZON_DAYS))
                .status(RunStatus.COMPLETED)
                .generationMs(0)
                .build();
    }

    @Test
    @DisplayName("Second concurrent triggerOnDemand for the same corporate throws ForecastInProgressException")
    void secondConcurrentCall_throwsForecastInProgress() throws Exception {
        ForecastOrchestrator orchestrator = mock(ForecastOrchestrator.class);
        CorporateRepository corporates = mock(CorporateRepository.class);

        // Latches gate the orchestrator's first call: it signals "I have the
        // lock now" via `started`, then blocks on `release` so the second
        // caller is guaranteed to see the lock held.
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        when(orchestrator.run(CORPORATE_ID, ForecastScheduler.FORECAST_HORIZON_DAYS))
                .thenAnswer(invocation -> {
                    started.countDown();
                    boolean released = release.await(10, TimeUnit.SECONDS);
                    if (!released) {
                        throw new AssertionError("Test never released the orchestrator gate");
                    }
                    return stubRun(CORPORATE_ID);
                });

        ForecastScheduler scheduler = new ForecastScheduler(orchestrator, corporates, noRedis());

        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<ForecastRun> firstResult = new AtomicReference<>();
        try {
            Future<?> first = pool.submit(() -> firstResult.set(scheduler.triggerOnDemand(CORPORATE_ID)));

            // Wait until the orchestrator has been entered — the lock is now
            // held. Failing this await would mean a real concurrency bug.
            assertThat(started.await(5, TimeUnit.SECONDS))
                    .as("First call must reach the orchestrator within 5s")
                    .isTrue();

            // Second call — same corporate, lock is held → must throw.
            assertThatThrownBy(() -> scheduler.triggerOnDemand(CORPORATE_ID))
                    .isInstanceOf(ForecastInProgressException.class)
                    .hasMessageContaining(CORPORATE_ID.toString());

            // Release the gate; first call completes cleanly.
            release.countDown();
            first.get(5, TimeUnit.SECONDS);

            assertThat(firstResult.get()).isNotNull();
            assertThat(firstResult.get().getCorporateId()).isEqualTo(CORPORATE_ID);

            // Orchestrator should have been invoked exactly once — the second
            // attempt was rejected at the lock and never reached the engine.
            verify(orchestrator, times(1))
                    .run(CORPORATE_ID, ForecastScheduler.FORECAST_HORIZON_DAYS);
        } finally {
            release.countDown(); // belt-and-braces in case of assertion failure
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Lock is per-corporate: a second corporate can run while the first is locked")
    void differentCorporates_runConcurrently() throws Exception {
        ForecastOrchestrator orchestrator = mock(ForecastOrchestrator.class);
        CorporateRepository corporates = mock(CorporateRepository.class);

        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch firstRelease = new CountDownLatch(1);

        when(orchestrator.run(CORPORATE_ID, ForecastScheduler.FORECAST_HORIZON_DAYS))
                .thenAnswer(invocation -> {
                    firstStarted.countDown();
                    firstRelease.await(10, TimeUnit.SECONDS);
                    return stubRun(CORPORATE_ID);
                });
        when(orchestrator.run(OTHER_CORPORATE_ID, ForecastScheduler.FORECAST_HORIZON_DAYS))
                .thenReturn(stubRun(OTHER_CORPORATE_ID));

        ForecastScheduler scheduler = new ForecastScheduler(orchestrator, corporates, noRedis());

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = pool.submit(() -> scheduler.triggerOnDemand(CORPORATE_ID));
            assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();

            // Different corporate — must NOT throw, runs to completion.
            ForecastRun secondRun = scheduler.triggerOnDemand(OTHER_CORPORATE_ID);
            assertThat(secondRun).isNotNull();
            assertThat(secondRun.getCorporateId()).isEqualTo(OTHER_CORPORATE_ID);

            firstRelease.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            firstRelease.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("After the first run completes, the lock is released — same corporate can run again")
    void lockReleasedAfterRun_sameCorporateCanRunAgain() {
        ForecastOrchestrator orchestrator = mock(ForecastOrchestrator.class);
        CorporateRepository corporates = mock(CorporateRepository.class);
        when(orchestrator.run(CORPORATE_ID, ForecastScheduler.FORECAST_HORIZON_DAYS))
                .thenReturn(stubRun(CORPORATE_ID));

        ForecastScheduler scheduler = new ForecastScheduler(orchestrator, corporates, noRedis());

        scheduler.triggerOnDemand(CORPORATE_ID);
        // Second call — first already finished, lock was released in finally.
        scheduler.triggerOnDemand(CORPORATE_ID);

        verify(orchestrator, times(2)).run(CORPORATE_ID, ForecastScheduler.FORECAST_HORIZON_DAYS);
    }

    @Test
    @DisplayName("Orchestrator exception still releases the lock (lock is not leaked on failure)")
    void orchestratorThrows_lockReleased() {
        ForecastOrchestrator orchestrator = mock(ForecastOrchestrator.class);
        CorporateRepository corporates = mock(CorporateRepository.class);
        when(orchestrator.run(CORPORATE_ID, ForecastScheduler.FORECAST_HORIZON_DAYS))
                .thenThrow(new RuntimeException("boom"))
                .thenReturn(stubRun(CORPORATE_ID));

        ForecastScheduler scheduler = new ForecastScheduler(orchestrator, corporates, noRedis());

        assertThatThrownBy(() -> scheduler.triggerOnDemand(CORPORATE_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");

        // Lock must have been released in `finally` — second call should
        // succeed, not raise ForecastInProgress.
        ForecastRun run = scheduler.triggerOnDemand(CORPORATE_ID);
        assertThat(run).isNotNull();
        verify(orchestrator, times(2)).run(CORPORATE_ID, ForecastScheduler.FORECAST_HORIZON_DAYS);
    }

    @Test
    @DisplayName("runNightly: per-corporate exception is contained — surviving corporates still run")
    void nightlyBatch_containsPerCorporateFailures() {
        ForecastOrchestrator orchestrator = mock(ForecastOrchestrator.class);
        CorporateRepository corporates = mock(CorporateRepository.class);

        com.bank.vam.entity.Corporate good = new com.bank.vam.entity.Corporate();
        good.setId(CORPORATE_ID);
        good.setCorporateId("CORP-GOOD");
        com.bank.vam.entity.Corporate bad = new com.bank.vam.entity.Corporate();
        bad.setId(OTHER_CORPORATE_ID);
        bad.setCorporateId("CORP-BAD");

        when(corporates.findByStatus(com.bank.vam.entity.Corporate.CorporateStatus.ACTIVE))
                .thenReturn(java.util.List.of(bad, good));
        when(orchestrator.run(OTHER_CORPORATE_ID, ForecastScheduler.FORECAST_HORIZON_DAYS))
                .thenThrow(new RuntimeException("bad-tenant"));
        when(orchestrator.run(CORPORATE_ID, ForecastScheduler.FORECAST_HORIZON_DAYS))
                .thenReturn(stubRun(CORPORATE_ID));

        ForecastScheduler scheduler = new ForecastScheduler(orchestrator, corporates, noRedis());
        scheduler.runNightly();   // must not throw

        // Both orchestrator invocations attempted — failure of `bad` did not
        // short-circuit the batch.
        verify(orchestrator, times(1)).run(OTHER_CORPORATE_ID, ForecastScheduler.FORECAST_HORIZON_DAYS);
        verify(orchestrator, times(1)).run(CORPORATE_ID, ForecastScheduler.FORECAST_HORIZON_DAYS);
        verify(orchestrator, never()).run(CORPORATE_ID, 0);
    }
}
