package com.bank.vam.forecast.api;

import com.bank.vam.forecast.api.dto.ForecastSummaryDto;
import com.bank.vam.forecast.api.dto.ForecastWeeklyBucketDto;
import com.bank.vam.forecast.domain.ForecastRun;
import com.bank.vam.forecast.domain.enums.ForecastDirection;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Rolls up a forecast run's native weekly aggregate into a {@link
 * ForecastSummaryDto}. Extracted verbatim from {@code ForecastController}'s
 * private {@code buildSummary}/{@code toLocalDate} methods so {@code
 * get_cash_forecast} (the MCP tool) can reuse the exact same logic instead of
 * duplicating it — {@code GET /api/v1/forecasts/latest}'s behavior is
 * unchanged, it now just delegates here.
 */
public final class ForecastSummaryAssembler {

    private ForecastSummaryAssembler() {
    }

    /**
     * Amounts are never summed across currencies. With no {@code currencyFilter}
     * and lines in several currencies, the summary covers one of them — the
     * {@code preferredCurrency} (market default) if present, else the first
     * alphabetically — and says which in {@code currency}.
     *
     * @param openingBalanceFor today's cash for the corporate in a currency
     */
    public static ForecastSummaryDto assemble(ForecastRun run, List<Object[]> rows, String currencyFilter,
                                              String preferredCurrency,
                                              Function<String, BigDecimal> openingBalanceFor) {
        String resolvedCurrency = resolveCurrency(rows, currencyFilter, preferredCurrency);

        // Bucket by week (ISO Monday). The native query returns one row per
        // (week_start, currency, direction) so a single calendar week can
        // contribute up to 2 rows (IN + OUT) per currency.
        java.util.Map<LocalDate, BigDecimal> netByWeek = new java.util.TreeMap<>();

        for (Object[] row : rows) {
            LocalDate weekStart = toLocalDate(row[0]);
            String currency = (String) row[1];
            String directionRaw = (String) row[2];
            BigDecimal sum = (BigDecimal) row[3];

            if (resolvedCurrency != null && !resolvedCurrency.equalsIgnoreCase(currency)) {
                continue;
            }

            ForecastDirection direction = ForecastDirection.valueOf(directionRaw);
            BigDecimal signed = direction == ForecastDirection.IN ? sum : sum.negate();
            netByWeek.merge(weekStart, signed, BigDecimal::add);
        }

        // No currency means nothing to anchor on: no opening, no running balance.
        BigDecimal openingBalance = resolvedCurrency == null ? null : openingBalanceFor.apply(resolvedCurrency);
        BigDecimal running = openingBalance;
        List<ForecastWeeklyBucketDto> buckets = new ArrayList<>(netByWeek.size());

        for (var entry : netByWeek.entrySet()) {
            LocalDate weekStart = entry.getKey().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            LocalDate weekEnd = weekStart.plusDays(6);
            BigDecimal net = entry.getValue();
            running = running == null ? null : running.add(net);
            buckets.add(new ForecastWeeklyBucketDto(weekStart, weekEnd, net, running));
        }

        BigDecimal closingBalance = running;
        ForecastWeeklyBucketDto trough = buckets.stream()
                .filter(b -> b.closingBalance() != null)
                .min(Comparator.comparing(ForecastWeeklyBucketDto::closingBalance))
                .orElse(null);

        return new ForecastSummaryDto(
                run.getId(),
                run.getRunAt(),
                run.getHorizonEnd(),
                resolvedCurrency,
                openingBalance,
                closingBalance,
                trough,
                buckets
        );
    }

    static String resolveCurrency(List<Object[]> rows, String currencyFilter, String preferredCurrency) {
        if (currencyFilter != null && !currencyFilter.isBlank()) {
            return currencyFilter.toUpperCase();
        }
        java.util.TreeSet<String> present = new java.util.TreeSet<>();
        for (Object[] row : rows) {
            if (row[1] != null) present.add((String) row[1]);
        }
        if (present.isEmpty()) {
            return preferredCurrency;
        }
        return preferredCurrency != null && present.contains(preferredCurrency) ? preferredCurrency : present.first();
    }

    /**
     * The native aggregate query returns {@code week_start} as the JDBC
     * driver's native temporal type — most commonly {@code java.sql.Date} or
     * {@code java.sql.Timestamp} depending on PG version + Hibernate flavour.
     * Normalise to {@link LocalDate} here so the summary layer above doesn't
     * have to care.
     */
    public static LocalDate toLocalDate(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof LocalDate ld) {
            return ld;
        }
        if (raw instanceof Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        if (raw instanceof Timestamp ts) {
            return ts.toLocalDateTime().toLocalDate();
        }
        if (raw instanceof java.time.LocalDateTime ldt) {
            return ldt.toLocalDate();
        }
        if (raw instanceof java.time.OffsetDateTime odt) {
            return odt.toLocalDate();
        }
        if (raw instanceof java.time.Instant inst) {
            // Hibernate 6 + Postgres JDBC will surface a TIMESTAMPTZ aggregate
            // (e.g. date_trunc without a ::date cast) as Instant. Anchor at UTC
            // since value_date is a zoneless DATE — the conversion can't drift.
            return inst.atZone(java.time.ZoneOffset.UTC).toLocalDate();
        }
        throw new IllegalStateException("Unexpected week_start type " + raw.getClass() + " from native query");
    }
}
