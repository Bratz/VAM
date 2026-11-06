package com.bank.vam.fx.exposure;

import com.bank.vam.fx.exposure.ExposureCalculator.Flow;
import com.bank.vam.fx.exposure.FxExposureModels.Bucket;
import com.bank.vam.fx.exposure.FxExposureModels.CurrencyRow;
import com.bank.vam.fx.exposure.FxExposureModels.HedgeStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class ExposureCalculatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);
    private static final UUID CORP = UUID.randomUUID();
    private static final BigDecimal MATERIALITY = new BigDecimal("25000");
    private static final Function<String, BigDecimal> RATE = ccy -> switch (ccy) {
        case "GBP" -> new BigDecimal("4.65");
        case "EUR" -> new BigDecimal("4.01");
        case "USD" -> new BigDecimal("3.6725");
        default -> null;
    };

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static Flow flow(String ccy, int daysFromToday, String amount) {
        return new Flow(ccy, TODAY.plusDays(daysFromToday), bd(amount));
    }

    private static FxExposureEntry entry(FxExposureEntry.Kind kind, String ccy, String amount, int daysFromToday) {
        FxExposureEntry e = new FxExposureEntry();
        e.setCorporateId(CORP);
        e.setKind(kind);
        e.setCurrency(ccy);
        e.setAmount(bd(amount));
        e.setValueDate(TODAY.plusDays(daysFromToday));
        return e;
    }

    private static List<CurrencyRow> run(List<Flow> payables, List<Flow> receivables, Map<String, BigDecimal> balances,
                                         List<FxExposureEntry> entries, List<FxPolicyBand> bands) {
        return ExposureCalculator.build("AED", TODAY, payables, receivables, List.of(), balances, entries, bands, RATE, MATERIALITY);
    }

    private static List<CurrencyRow> run(List<Flow> payables, List<Flow> receivables, List<FxExposureEntry> entries) {
        return run(payables, receivables, Map.of(), entries, HedgePolicyService.defaultBands(CORP));
    }

    private static Bucket bucket(List<CurrencyRow> rows, String ccy, ExposureBucket b) {
        return rows.stream().filter(r -> r.currency().equals(ccy)).findFirst().orElseThrow()
                .buckets().stream().filter(x -> x.bucket() == b).findFirst().orElseThrow();
    }

    @Test
    void netsReceivablesAgainstPayablesInTheSameBucketAndComputesTheHedgeRatio() {
        // Short GBP 60k in 0-30 days (pay 100k, receive 40k); a bought-forward GBP 45k covers 75% of it.
        List<CurrencyRow> rows = run(List.of(flow("GBP", 10, "100000")), List.of(flow("GBP", 10, "40000")),
                List.of(entry(FxExposureEntry.Kind.HEDGE, "GBP", "45000", 20)));

        Bucket b = bucket(rows, "GBP", ExposureBucket.D0_30);
        assertThat(b.exposure()).isEqualByComparingTo("-60000");
        assertThat(b.hedged()).isEqualByComparingTo("45000");
        assertThat(b.hedgeRatio()).isEqualByComparingTo("0.75");
        assertThat(b.status()).isEqualTo(HedgeStatus.WITHIN_POLICY);
        assertThat(b.adjustToBand()).isNull();
    }

    @Test
    void anUnhedgedBucketBelowTheBandSaysHowMuchToBuyToReachIt() {
        // Pay GBP 200k in 31-60 days, nothing hedged; the band floor is 50%, so buy GBP 100k forward.
        List<CurrencyRow> rows = run(List.of(flow("GBP", 45, "200000")), List.of(), List.of());

        Bucket b = bucket(rows, "GBP", ExposureBucket.D31_60);
        assertThat(b.status()).isEqualTo(HedgeStatus.UNDER_HEDGED);
        assertThat(b.hedgeRatio()).isEqualByComparingTo("0");
        assertThat(b.adjustToBand()).isEqualByComparingTo("100000");
    }

    @Test
    void overHedgingIsFlaggedWithTheAmountToUnwind() {
        // Long EUR 100k, but EUR 150k sold forward: 150% against a 100% ceiling, so buy 50k back.
        List<CurrencyRow> rows = run(List.of(), List.of(flow("EUR", 5, "100000")),
                List.of(entry(FxExposureEntry.Kind.HEDGE, "EUR", "-150000", 15)));

        Bucket b = bucket(rows, "EUR", ExposureBucket.D0_30);
        assertThat(b.status()).isEqualTo(HedgeStatus.OVER_HEDGED);
        assertThat(b.hedgeRatio()).isEqualByComparingTo("1.5");
        assertThat(b.adjustToBand()).isEqualByComparingTo("50000");
    }

    @Test
    void aHedgeWithNoExposureUnderneathIsItselfFlagged() {
        List<CurrencyRow> rows = run(List.of(), List.of(), List.of(entry(FxExposureEntry.Kind.HEDGE, "EUR", "-80000", 100)));

        Bucket b = bucket(rows, "EUR", ExposureBucket.D91_180);
        assertThat(b.status()).isEqualTo(HedgeStatus.OVER_HEDGED);
        assertThat(b.adjustToBand()).isEqualByComparingTo("80000");
    }

    @Test
    void exposureBelowMaterialityIsIgnoredButNotWhenAHedgeIsAlreadyOnIt() {
        // 25,000 AED at 3.6725 is about 6,807 USD; USD 100 is noise.
        List<CurrencyRow> small = run(List.of(), List.of(flow("USD", 5, "100")), List.of());
        assertThat(bucket(small, "USD", ExposureBucket.D0_30).status()).isEqualTo(HedgeStatus.NONE);

        List<CurrencyRow> hedged = run(List.of(), List.of(flow("USD", 5, "100")),
                List.of(entry(FxExposureEntry.Kind.HEDGE, "USD", "-100", 5)));
        assertThat(bucket(hedged, "USD", ExposureBucket.D0_30).status()).isEqualTo(HedgeStatus.WITHIN_POLICY);
    }

    @Test
    void overdueAndUndatedItemsCountAsDueNow() {
        List<CurrencyRow> rows = run(List.of(flow("GBP", -40, "100000"), new Flow("GBP", null, bd("50000"))),
                List.of(), List.of());

        assertThat(bucket(rows, "GBP", ExposureBucket.D0_30).exposure()).isEqualByComparingTo("-150000");
    }

    @Test
    void cashOnHandIsShownButNeverPoliced() {
        List<CurrencyRow> rows = run(List.of(), List.of(), Map.of("GBP", bd("800000")), List.of(),
                HedgePolicyService.defaultBands(CORP));

        Bucket b = bucket(rows, "GBP", ExposureBucket.ON_HAND);
        assertThat(b.exposure()).isEqualByComparingTo("800000");
        assertThat(b.status()).isEqualTo(HedgeStatus.NOT_POLICED);
    }

    @Test
    void aCurrencySpecificBandOverridesTheDefault() {
        List<FxPolicyBand> bands = new ArrayList<>(HedgePolicyService.defaultBands(CORP));
        FxPolicyBand gbp = new FxPolicyBand();
        gbp.setCorporateId(CORP);
        gbp.setCurrency("GBP");
        gbp.setBucket(ExposureBucket.D31_60);
        gbp.setMinHedgeRatio(bd("0.90"));
        gbp.setMaxHedgeRatio(bd("1.00"));
        bands.add(gbp);

        List<CurrencyRow> rows = run(List.of(flow("GBP", 45, "200000")), List.of(), Map.of(), List.of(), bands);

        Bucket b = bucket(rows, "GBP", ExposureBucket.D31_60);
        assertThat(b.minRatio()).isEqualByComparingTo("0.90");
        assertThat(b.adjustToBand()).isEqualByComparingTo("180000");
    }

    @Test
    void noBandMeansNoPolicyRatherThanSilentlyFine() {
        List<CurrencyRow> rows = run(List.of(flow("GBP", 10, "100000")), List.of(), Map.of(), List.of(), List.of());

        assertThat(bucket(rows, "GBP", ExposureBucket.D0_30).status()).isEqualTo(HedgeStatus.NO_POLICY);
    }

    @Test
    void entriesInTheBaseCurrencyAreNotForeignExposure() {
        List<CurrencyRow> rows = run(List.of(), List.of(), List.of(entry(FxExposureEntry.Kind.EXPOSURE, "AED", "900000", 10)));

        assertThat(rows).isEmpty();
    }

    @Test
    void anUnknownRateLeavesTheBaseEquivalentOutInsteadOfGuessing() {
        List<CurrencyRow> rows = run(List.of(flow("XYZ", 10, "100000")), List.of(), List.of());

        CurrencyRow row = rows.get(0);
        assertThat(row.rateToBase()).isNull();
        // Without a rate the raw materiality figure is the threshold, and 100,000 still exceeds it.
        assertThat(bucket(rows, "XYZ", ExposureBucket.D0_30).status()).isEqualTo(HedgeStatus.UNDER_HEDGED);
    }

    @Test
    void forecastFlowsCountInFullBesideTheInvoicesAndShowInTheirOwnColumn() {
        // A GBP receivable of 1,000,000 in 10 days, and from the Cash Forecast a GBP payroll of 850,000 out in 15 days
        // and a GBP manual inflow of 100,000 in 40 days.
        List<CurrencyRow> rows = ExposureCalculator.build("AED", TODAY, List.of(), List.of(flow("GBP", 10, "1000000")),
                List.of(flow("GBP", 15, "-850000"), flow("GBP", 40, "100000")), Map.of(), List.of(),
                HedgePolicyService.defaultBands(CORP), RATE, MATERIALITY);

        Bucket near = bucket(rows, "GBP", ExposureBucket.D0_30);
        Bucket next = bucket(rows, "GBP", ExposureBucket.D31_60);
        assertThat(near.receivables()).isEqualByComparingTo("1000000");
        assertThat(near.forecast()).isEqualByComparingTo("-850000");
        assertThat(near.exposure()).isEqualByComparingTo("150000"); // the forecast counts in full
        assertThat(next.forecast()).isEqualByComparingTo("100000");
        assertThat(next.exposure()).isEqualByComparingTo("100000");
    }

    @Test
    void bucketBoundariesFollowTheDaysToDueDate() {
        assertThat(ExposureBucket.forDate(TODAY.plusDays(30), TODAY)).isEqualTo(ExposureBucket.D0_30);
        assertThat(ExposureBucket.forDate(TODAY.plusDays(31), TODAY)).isEqualTo(ExposureBucket.D31_60);
        assertThat(ExposureBucket.forDate(TODAY.plusDays(90), TODAY)).isEqualTo(ExposureBucket.D61_90);
        assertThat(ExposureBucket.forDate(TODAY.plusDays(180), TODAY)).isEqualTo(ExposureBucket.D91_180);
        assertThat(ExposureBucket.forDate(TODAY.plusDays(181), TODAY)).isEqualTo(ExposureBucket.D181_PLUS);
    }
}
