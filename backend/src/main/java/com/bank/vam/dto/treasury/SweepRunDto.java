package com.bank.vam.dto.treasury;

import com.bank.vam.entity.treasury.SweepRun;
import com.bank.vam.entity.treasury.SweepRunStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Wire shape for {@link SweepRun}. Kept as a record, same reasoning as
 * {@code ForecastRunDto}: never leak the JPA entity across the API boundary.
 */
public record SweepRunDto(
        UUID runId,
        SweepRunStatus status,
        LocalDateTime startedAt,
        int sourcesTotal,
        int sourcesProcessed,
        int successCount,
        int failedCount,
        String errorMessage
) {

    public static SweepRunDto from(SweepRun run) {
        return new SweepRunDto(
                run.getId(),
                run.getStatus(),
                run.getStartedAt(),
                run.getSourcesTotal(),
                run.getSourcesProcessed(),
                run.getSuccessCount(),
                run.getFailedCount(),
                run.getErrorMessage()
        );
    }
}
