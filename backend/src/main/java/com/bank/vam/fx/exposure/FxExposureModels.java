package com.bank.vam.fx.exposure;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Response shapes for the exposure ladder. Amounts are native to their currency, never summed across currencies. */
public final class FxExposureModels {

    private FxExposureModels() {
    }

    public enum HedgeStatus {
        /** Hedge ratio is inside the policy band. */
        WITHIN_POLICY,
        UNDER_HEDGED,
        OVER_HEDGED,
        /** Material exposure but no band covers it. */
        NO_POLICY,
        /** Cash on hand: shown, but the hedge policy does not apply to it. */
        NOT_POLICED,
        /** Nothing, or too little to matter. */
        NONE
    }

    /**
     * One currency in one time bucket. {@code exposure} is receivables - payables + balances + declared
     * (positive = long the currency). {@code hedged} is the hedge deals' own position (selling forward
     * is negative), so {@code hedgeRatio} = -hedged / exposure. {@code adjustToBand} is the hedge
     * notional to add (signed like {@code hedged}) to reach the nearest band edge, or null when within.
     */
    public record Bucket(
            ExposureBucket bucket,
            String label,
            BigDecimal receivables,
            BigDecimal payables,
            BigDecimal forecast,
            BigDecimal balances,
            BigDecimal declared,
            BigDecimal exposure,
            BigDecimal hedged,
            BigDecimal open,
            BigDecimal hedgeRatio,
            BigDecimal minRatio,
            BigDecimal maxRatio,
            HedgeStatus status,
            BigDecimal adjustToBand) {
    }

    /** {@code rateToBase} is the stored platform rate; null when none exists, so no base figure is implied. */
    public record CurrencyRow(String currency, BigDecimal rateToBase, List<Bucket> buckets) {
    }

    /**
     * {@code forecastRunAt} is when the Cash Forecast run that fed the Forecast column ran, null when the corporate has
     * none (then only invoices, cash and declared entries count); {@code forecastStale} flags a run older than the
     * configured days.
     */
    public record Ladder(UUID corporateId, String corporateName, String baseCurrency, LocalDate asOf,
                         List<CurrencyRow> currencies, java.time.OffsetDateTime forecastRunAt, boolean forecastStale) {
    }

    public record Breach(
            UUID corporateId,
            String corporateName,
            String baseCurrency,
            String currency,
            ExposureBucket bucket,
            String bucketLabel,
            int horizonDays,
            BigDecimal exposure,
            BigDecimal hedged,
            BigDecimal hedgeRatio,
            BigDecimal minRatio,
            BigDecimal maxRatio,
            HedgeStatus status,
            BigDecimal adjustToBand,
            BigDecimal rateToBase,
            String severity) {
    }

    public record EntryView(UUID id, FxExposureEntry.Kind kind, String currency, BigDecimal amount,
                            LocalDate valueDate, String reference, String description, String source) {

        static EntryView of(FxExposureEntry e) {
            return new EntryView(e.getId(), e.getKind(), e.getCurrency(), e.getAmount(), e.getValueDate(),
                    e.getReference(), e.getDescription(), e.getSource());
        }
    }

    public record UploadResult(int imported, int replaced) {
    }
}
