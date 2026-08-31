package com.bank.vam.forecast.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One week's worth of net cashflow + running closing balance.
 *
 * <p>{@code weekStart} is the ISO Monday of the bucket and {@code weekEnd} is
 * the Sunday (inclusive). {@code netCashflow} is the signed sum of inflows
 * minus outflows for the week (computed from
 * {@link com.bank.vam.forecast.domain.ForecastLine#getAmountMid()} aggregated
 * by {@link com.bank.vam.forecast.domain.enums.ForecastDirection}).
 * {@code closingBalance} is the cumulative balance at week end given the
 * summary's opening balance.
 *
 * <p>Field names match
 * {@code frontend/src/services/api.ts :: ForecastWeeklyBucket} verbatim.
 */
public record ForecastWeeklyBucketDto(
        LocalDate weekStart,
        LocalDate weekEnd,
        BigDecimal netCashflow,
        BigDecimal closingBalance
) {
}
