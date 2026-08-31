package com.bank.vam.forecast.api.dto;

import com.bank.vam.forecast.domain.ForecastRun;
import com.bank.vam.forecast.domain.enums.RunStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Wire shape for {@link ForecastRun}. Kept as a record so JPA proxies and
 * lazy associations never leak through the API boundary.
 *
 * <p>Field names match {@code frontend/src/services/api.ts :: ForecastRun}
 * verbatim — keep them in sync if the shape changes.
 */
public record ForecastRunDto(
        UUID runId,
        RunStatus status,
        OffsetDateTime runAt,
        LocalDate horizonEnd,
        Integer generationMs
) {

    public static ForecastRunDto from(ForecastRun run) {
        return new ForecastRunDto(
                run.getId(),
                run.getStatus(),
                run.getRunAt(),
                run.getHorizonEnd(),
                run.getGenerationMs()
        );
    }
}
