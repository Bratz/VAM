package com.bank.vam.forecast.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Top-level forecast view used by the dashboard headline strip + chart.
 *
 * <p>Built by aggregating {@link com.bank.vam.forecast.domain.ForecastLine}
 * rows of the most-recent {@code COMPLETED} run into weekly buckets, then
 * running an opening-balance-anchored cumulative sum to produce
 * {@code closingBalance} per week.
 *
 * <p>{@code openingBalance} is today's bank balance in {@code currency}.
 *
 * <p>Field names match {@code frontend/src/services/api.ts :: ForecastSummary}
 * verbatim — keep them in sync if the shape changes.
 */
public record ForecastSummaryDto(
        UUID runId,
        OffsetDateTime runAt,
        LocalDate horizonEnd,
        String currency,
        BigDecimal openingBalance,
        BigDecimal closingBalance,
        ForecastWeeklyBucketDto troughWeek,
        List<ForecastWeeklyBucketDto> weeklyBuckets
) {
}
