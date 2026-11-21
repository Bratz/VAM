package com.bank.vam.fx.exposure;

import com.bank.vam.fx.exposure.ExposureCalculator.Flow;
import com.bank.vam.fx.exposure.ExposureCalculator.Ref;
import com.bank.vam.fx.exposure.FxExposureModels.Bucket;
import com.bank.vam.fx.exposure.FxExposureModels.CurrencyRow;
import com.bank.vam.fx.exposure.FxExposureModels.Item;
import com.bank.vam.fx.exposure.FxExposureModels.ItemKind;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A bucket's drill-down lists exactly what the ladder counted there: its items add up to the bucket's figures. */
class ExposureItemsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 21);
    private static final String BASE = "AED";

    private static Flow flow(String ccy, LocalDate date, String amount, String ref) {
        return new Flow(ccy, date, new BigDecimal(amount), new Ref(UUID.randomUUID(), ref, "Counterparty " + ref));
    }

    private static FxExposureEntry entry(FxExposureEntry.Kind kind, String ccy, LocalDate date, String amount, String ref) {
        FxExposureEntry e = new FxExposureEntry();
        e.setKind(kind);
        e.setCurrency(ccy);
        e.setValueDate(date);
        e.setAmount(new BigDecimal(amount));
        e.setReference(ref);
        e.setSource(kind == FxExposureEntry.Kind.HEDGE ? "DEAL" : FxExposureEntry.SOURCE_UPLOAD);
        return e;
    }

    private final List<Flow> payables = List.of(
            flow("USD", TODAY.minusDays(10), "40000", "INV-OVERDUE"),      // overdue: due now
            flow("USD", TODAY.plusDays(45), "15000", "INV-45"),
            flow("USD", null, "5000", "INV-UNDATED"));                    // undated: due now (base-currency invoices
                                                                           // never reach here: ExposureSources leaves them out)
    private final List<Flow> receivables = List.of(
            flow("USD", TODAY.plusDays(20), "120000", "REC-20"),
            flow("EUR", TODAY.plusDays(20), "70000", "REC-EUR"),
            flow("USD", TODAY.plusDays(200), "8000", "REC-200"));
    private final List<Flow> forecast = List.of(
            flow("USD", TODAY.plusDays(12), "-30000", "Payroll"),
            flow("USD", TODAY.plusDays(70), "25000", "Model"));
    private final List<Flow> accounts = List.of(
            flow("USD", null, "500000", "SHADOW-USD-1"),
            flow("USD", null, "-2000", "SHADOW-USD-2"));
    private final List<FxExposureEntry> entries = List.of(
            entry(FxExposureEntry.Kind.EXPOSURE, "USD", TODAY.plusDays(25), "10000", "ERP-1"),
            entry(FxExposureEntry.Kind.HEDGE, "USD", TODAY.plusDays(28), "-50000", "FXD-1"),
            entry(FxExposureEntry.Kind.HEDGE, "AED", TODAY.plusDays(28), "183625", "FXD-1"));

    @Test
    void everyBucketsItemsAddUpToItsExposureAndHedged() {
        List<CurrencyRow> ladder = ExposureCalculator.build(BASE, TODAY, payables, receivables, forecast,
                ExposureSources.balances(accounts), entries, List.of(), ccy -> new BigDecimal("3.6725"), new BigDecimal("25000"));

        for (CurrencyRow row : ladder) {
            for (Bucket b : row.buckets()) {
                List<Item> items = ExposureCalculator.items(BASE, TODAY, row.currency(), b.bucket(),
                        payables, receivables, forecast, accounts, entries);
                assertThat(sum(items, false)).as(row.currency() + " " + b.bucket() + " exposure").isEqualByComparingTo(b.exposure());
                assertThat(sum(items, true)).as(row.currency() + " " + b.bucket() + " hedged").isEqualByComparingTo(b.hedged());
            }
        }
    }

    @Test
    void theNearBucketListsTheInvoicesForecastEntryAndHedgeDueThere() {
        List<Item> items = ExposureCalculator.items(BASE, TODAY, "USD", ExposureBucket.D0_30,
                payables, receivables, forecast, accounts, entries);

        assertThat(items).extracting(Item::reference)
                .containsExactlyInAnyOrder("REC-20", "INV-OVERDUE", "INV-UNDATED", "Payroll", "ERP-1", "FXD-1");
        assertThat(items).filteredOn(i -> i.kind() == ItemKind.PAYABLE).extracting(Item::amount)
                .allSatisfy(a -> assertThat(a.signum()).isNegative());
        assertThat(items).filteredOn(i -> "REC-20".equals(i.reference())).singleElement()
                .satisfies(i -> assertThat(i.counterparty()).isEqualTo("Counterparty REC-20"));
    }

    @Test
    void cashIsOnHandAndABaseCurrencyHedgeIsNoExposure() {
        assertThat(ExposureCalculator.items(BASE, TODAY, "USD", ExposureBucket.ON_HAND,
                payables, receivables, forecast, accounts, entries))
                .extracting(Item::kind).containsOnly(ItemKind.CASH).hasSize(2);
        assertThat(ExposureCalculator.items(BASE, TODAY, "AED", ExposureBucket.D0_30,
                payables, receivables, forecast, accounts, entries)).isEmpty();
    }

    private static BigDecimal sum(List<Item> items, boolean hedges) {
        return items.stream().filter(i -> (i.kind() == ItemKind.HEDGE) == hedges).map(Item::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
