package com.bank.vam.fx.exposure;

import com.bank.vam.fx.exposure.FxExposureModels.Bucket;
import com.bank.vam.fx.exposure.FxExposureModels.CurrencyRow;
import com.bank.vam.fx.exposure.FxExposureModels.HedgeStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/** The ladder arithmetic, kept free of Spring and the database so it can be checked on its own. */
final class ExposureCalculator {

    /** A dated amount in a currency. Amounts are positive; the caller says which side they are on. */
    record Flow(String currency, LocalDate date, BigDecimal amount) {
    }

    private ExposureCalculator() {
    }

    private static final class Cell {
        BigDecimal receivables = BigDecimal.ZERO;
        BigDecimal payables = BigDecimal.ZERO;
        BigDecimal balances = BigDecimal.ZERO;
        BigDecimal declared = BigDecimal.ZERO;
        BigDecimal forecast = BigDecimal.ZERO;
        BigDecimal hedge = BigDecimal.ZERO;
    }

    /**
     * @param rateToBase     platform rate from a currency to the base currency, or null when none exists
     * @param forecast        Cash Forecast flows no invoice carries, signed (in positive, out negative); counted in full
     * @param materialityBase an exposure smaller than this, in base currency, is not worth a hedge
     */
    static List<CurrencyRow> build(String base, LocalDate today,
                                   List<Flow> payables, List<Flow> receivables, List<Flow> forecast, Map<String, BigDecimal> balances,
                                   List<FxExposureEntry> entries, List<FxPolicyBand> bands,
                                   Function<String, BigDecimal> rateToBase, BigDecimal materialityBase) {
        Map<String, Map<ExposureBucket, Cell>> grid = new TreeMap<>();

        for (Flow f : payables) {
            Cell c = cell(grid, f.currency(), ExposureBucket.forDate(f.date(), today));
            c.payables = c.payables.add(f.amount());
        }
        for (Flow f : receivables) {
            Cell c = cell(grid, f.currency(), ExposureBucket.forDate(f.date(), today));
            c.receivables = c.receivables.add(f.amount());
        }
        for (Flow f : forecast) {
            Cell c = cell(grid, f.currency(), ExposureBucket.forDate(f.date(), today));
            c.forecast = c.forecast.add(f.amount());
        }
        balances.forEach((ccy, amount) -> {
            Cell c = cell(grid, ccy, ExposureBucket.ON_HAND);
            c.balances = c.balances.add(amount);
        });
        for (FxExposureEntry e : entries) {
            if (e.getCurrency().equals(base)) {
                continue;
            }
            Cell c = cell(grid, e.getCurrency(), ExposureBucket.forDate(e.getValueDate(), today));
            if (e.getKind() == FxExposureEntry.Kind.HEDGE) {
                c.hedge = c.hedge.add(e.getAmount());
            } else {
                c.declared = c.declared.add(e.getAmount());
            }
        }

        List<CurrencyRow> rows = new ArrayList<>();
        grid.forEach((ccy, byBucket) -> {
            BigDecimal rate = rateToBase.apply(ccy);
            BigDecimal threshold = rate == null || rate.signum() <= 0
                    ? materialityBase : materialityBase.divide(rate, 2, RoundingMode.HALF_UP);
            List<Bucket> buckets = new ArrayList<>();
            for (ExposureBucket b : ExposureBucket.values()) {
                buckets.add(bucket(b, byBucket.getOrDefault(b, new Cell()), band(bands, ccy, b), threshold));
            }
            rows.add(new CurrencyRow(ccy, rate, buckets));
        });
        return rows;
    }

    private static Bucket bucket(ExposureBucket b, Cell c, FxPolicyBand band, BigDecimal threshold) {
        BigDecimal exposure = c.receivables.subtract(c.payables).add(c.forecast).add(c.balances).add(c.declared);
        BigDecimal open = exposure.add(c.hedge);
        BigDecimal min = band == null ? null : band.getMinHedgeRatio();
        BigDecimal max = band == null ? null : band.getMaxHedgeRatio();

        BigDecimal ratio = null;
        BigDecimal adjust = null;
        HedgeStatus status;

        if (!b.hasPolicy()) {
            status = exposure.signum() == 0 ? HedgeStatus.NONE : HedgeStatus.NOT_POLICED;
        } else if (exposure.signum() == 0) {
            // A hedge with nothing underneath it is itself an open position.
            if (c.hedge.abs().compareTo(threshold) >= 0) {
                status = HedgeStatus.OVER_HEDGED;
                adjust = c.hedge.negate().setScale(2, RoundingMode.HALF_UP);
            } else {
                status = HedgeStatus.NONE;
            }
        } else {
            ratio = c.hedge.negate().divide(exposure, 4, RoundingMode.HALF_UP);
            if (exposure.abs().compareTo(threshold) < 0 && c.hedge.signum() == 0) {
                status = HedgeStatus.NONE;
            } else if (band == null) {
                status = HedgeStatus.NO_POLICY;
            } else if (ratio.compareTo(min) < 0) {
                status = HedgeStatus.UNDER_HEDGED;
                adjust = toEdge(min, exposure, c.hedge);
            } else if (ratio.compareTo(max) > 0) {
                status = HedgeStatus.OVER_HEDGED;
                adjust = toEdge(max, exposure, c.hedge);
            } else {
                status = HedgeStatus.WITHIN_POLICY;
            }
        }
        return new Bucket(b, b.label(), c.receivables, c.payables, c.forecast, c.balances, c.declared, exposure, c.hedge,
                open, ratio, min, max, status, adjust);
    }

    /** The hedge position that would sit exactly on the edge, minus what is already there. */
    private static BigDecimal toEdge(BigDecimal edgeRatio, BigDecimal exposure, BigDecimal hedge) {
        return exposure.multiply(edgeRatio).negate().subtract(hedge).setScale(2, RoundingMode.HALF_UP);
    }

    /** A band for the exact currency wins over the corporate's ANY default. */
    private static FxPolicyBand band(List<FxPolicyBand> bands, String currency, ExposureBucket bucket) {
        FxPolicyBand fallback = null;
        for (FxPolicyBand b : bands) {
            if (b.getBucket() != bucket) {
                continue;
            }
            if (b.getCurrency().equals(currency)) {
                return b;
            }
            if (b.getCurrency().equals(FxPolicyBand.ANY)) {
                fallback = b;
            }
        }
        return fallback;
    }

    private static Cell cell(Map<String, Map<ExposureBucket, Cell>> grid, String currency, ExposureBucket bucket) {
        return grid.computeIfAbsent(currency, k -> new EnumMap<>(ExposureBucket.class))
                .computeIfAbsent(bucket, k -> new Cell());
    }
}
