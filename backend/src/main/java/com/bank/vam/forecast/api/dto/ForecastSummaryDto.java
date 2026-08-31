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
 * <p>Sprint 1 anchors {@code openingBalance} at {@code 0}. Real opening
 * balance integration (via {@code BalanceAggregationService}) is a Sprint 2
 * follow-up; the chart shape is correct either way — the y-axis offset just
 * shifts when the real opening lands.
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
