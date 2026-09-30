package com.bank.vam.config;

import com.bank.vam.service.fileingest.IngestRetrySweepService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs the file-ingest resume sweep on its own scheduler, deliberately outside
 * {@link SchedulingConfig} and therefore outside {@code vam.scheduling.enabled}.
 *
 * <p>That flag parks <em>treasury automation</em> — sweeps, interest accrual, limit resets,
 * shadow refresh, VIBAN cooling release. All of those are periodic housekeeping: switching
 * them off is a legitimate operational choice and nothing is left half-done.
 *
 * <p>{@code IngestRetrySweepService} is not that. It is the step that resumes a job the
 * agent worker has already produced a transform for, and it is the <em>only</em> thing that
 * notices that work finished — this app never calls the worker directly. Parked, an upload
 * still succeeds, the agent still writes and merges a transform, and the job then sits in
 * AWAITING_TRANSFORM forever: no error, no BLOCKED state, no timeline event. A user-facing
 * pipeline stops halfway with no signal that anything is wrong.
 *
 * <p>Spring's {@code @EnableScheduling} is all-or-nothing — with SchedulingConfig absent no
 * {@code @Scheduled} method runs at all — so the sweep cannot opt back in by annotation. Hence
 * this one-thread scheduler. It is a second scheduling mechanism, which is a cost, but the
 * alternative was guarding every treasury {@code @Scheduled} method individually (eight
 * classes, one of them a nested one) purely to preserve a flag those jobs already honour.
 *
 * <p>Cadence is the same {@code vam.fileingest.retry-cadence-minutes} the sweep always used.
 */
@Configuration
public class IngestSweepSchedulingConfig {

    private static final Logger log = LoggerFactory.getLogger(IngestSweepSchedulingConfig.class);

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService ingestRetrySweepScheduler(IngestRetrySweepService sweep,
                                                              FileIngestProperties properties) {
        long periodMs = Math.max(1, properties.getRetryCadenceMinutes()) * 60_000L;
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ingest-retry-sweep");
            thread.setDaemon(true);
            return thread;
        });
        // Swallow per-run failures: scheduleAtFixedRate cancels the task permanently if it ever
        // throws, which would turn one bad cycle into the exact silent stall this config exists
        // to prevent.
        scheduler.scheduleAtFixedRate(() -> {
            try {
                sweep.resumeAwaitingTransformJobs();
            } catch (Exception e) {
                log.error("Ingest retry sweep cycle failed; the schedule continues", e);
            }
        }, periodMs, periodMs, TimeUnit.MILLISECONDS);
        log.info("Ingest retry sweep scheduled every {} minute(s), independent of vam.scheduling.enabled",
                properties.getRetryCadenceMinutes());
        return scheduler;
    }
}
