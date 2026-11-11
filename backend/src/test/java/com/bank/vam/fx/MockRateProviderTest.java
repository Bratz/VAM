package com.bank.vam.fx;

import com.bank.vam.config.FxMarketProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MockRateProviderTest {

    /** What fx_rates holds in these tests: a mid for every configured walk. */
    private static final java.util.function.Function<String, Optional<BigDecimal>> STORED = pair -> Optional.ofNullable(java.util.Map.of(
            "EUR/USD", "1.0926", "GBP/USD", "1.2665", "USD/JPY", "149.50", "USD/CHF", "0.8800",
            "USD/INR", "83.20", "USD/SGD", "1.3400", "USD/AED", "3.6725", "USD/SAR", "3.7500").get(pair)).map(BigDecimal::new);

    private final FxMarketProperties props = new FxMarketProperties();
    private final AtomicLong now = new AtomicLong(1_000_000);

    private MockRateProvider provider(java.util.function.Function<String, Optional<BigDecimal>> anchors) {
        return new MockRateProvider(props, anchors, now::get, new Random(42));
    }

    private static SpotTick find(List<SpotTick> ticks, String pair) {
        return ticks.stream().filter(t -> t.pair().equals(pair)).findFirst().orElseThrow();
    }

    @Test
    void startsFromTheStoredMidAndAPairWithNoneHasNoRate() {
        java.util.Map<String, BigDecimal> stored = new java.util.HashMap<>(java.util.Map.of("EUR/USD", new BigDecimal("2.0")));
        MockRateProvider p = provider(pair -> Optional.ofNullable(stored.get(pair)));

        List<SpotTick> first = p.spots();

        assertThat(find(first, "EUR/USD").mid()).isEqualByComparingTo("2.00000");
        assertThat(find(first, "EUR/USD").changeBps()).isEqualByComparingTo("0");
        assertThat(find(first, "EUR/USD").source()).isEqualTo("SIMULATED");
        assertThat(first).extracting(SpotTick::pair).containsExactly("EUR/USD"); // no GBP leg, so no EUR/GBP cross either
        assertThat(p.spot("GBP/USD")).isEmpty();

        // Once a rate is stored the pair joins the board at the next look-up.
        stored.put("GBP/USD", new BigDecimal("1.25"));
        now.addAndGet(MockRateProvider.ANCHOR_RETRY_MS);
        assertThat(p.spot("GBP/USD").orElseThrow().mid()).isEqualByComparingTo("1.25000");
    }

    @Test
    void ticksOnlyAsTimePassesAndCapsTheCatchUp() {
        MockRateProvider p = provider(STORED);
        BigDecimal start = find(p.spots(), "EUR/USD").mid();

        now.addAndGet(500);
        assertThat(find(p.spots(), "EUR/USD").mid()).isEqualByComparingTo(start);

        now.addAndGet(500);
        assertThat(find(p.spots(), "EUR/USD").mid()).isNotEqualByComparingTo(start);

        now.addAndGet(24L * 3600 * 1000);
        p.spots();
        assertThat(find(p.spots(), "EUR/USD").asOf().toEpochMilli()).isEqualTo(now.get());
    }

    @Test
    void crossesStayTriangularWithTheirLegs() {
        MockRateProvider p = provider(STORED);
        p.spots();
        for (int i = 0; i < 40; i++) {
            now.addAndGet(1000);
            List<SpotTick> t = p.spots();
            double eurUsd = find(t, "EUR/USD").mid().doubleValue();
            double gbpUsd = find(t, "GBP/USD").mid().doubleValue();
            double usdAed = find(t, "USD/AED").mid().doubleValue();
            assertThat(find(t, "EUR/GBP").mid().doubleValue()).isCloseTo(eurUsd / gbpUsd, within(5e-5));
            assertThat(find(t, "EUR/AED").mid().doubleValue()).isCloseTo(eurUsd * usdAed, within(5e-5));
        }
    }

    @Test
    void pegsNeverMoveAndOtherPairsStayNearTheirAnchor() {
        MockRateProvider p = provider(STORED);
        p.spots();
        double worst = 0;
        for (int i = 0; i < 200; i++) {
            now.addAndGet(60_000);
            List<SpotTick> t = p.spots();
            assertThat(find(t, "USD/AED").changeBps()).isEqualByComparingTo("0");
            assertThat(find(t, "USD/SAR").changeBps()).isEqualByComparingTo("0");
            worst = Math.max(worst, Math.abs(find(t, "EUR/USD").changeBps().doubleValue()));
        }
        assertThat(worst).isGreaterThan(0).isLessThan(50);
    }

    @Test
    void invertsAQuotedPairAndKnowsNothingAboutOthers() {
        MockRateProvider p = provider(STORED);
        double eurUsd = find(p.spots(), "EUR/USD").mid().doubleValue();

        assertThat(p.spot("USD/EUR").orElseThrow().mid().doubleValue()).isCloseTo(1 / eurUsd, within(1e-5));
        assertThat(p.spot("EUR/CAD")).isEmpty();
    }
}
