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

    private final FxMarketProperties props = new FxMarketProperties();
    private final AtomicLong now = new AtomicLong(1_000_000);

    private MockRateProvider provider(java.util.function.Function<String, Optional<BigDecimal>> anchors) {
        return new MockRateProvider(props, anchors, now::get, new Random(42));
    }

    private static SpotTick find(List<SpotTick> ticks, String pair) {
        return ticks.stream().filter(t -> t.pair().equals(pair)).findFirst().orElseThrow();
    }

    @Test
    void startsFromTheStoredMidAndFallsBackToConfigWhenThereIsNone() {
        MockRateProvider p = provider(pair -> pair.equals("EUR/USD") ? Optional.of(new BigDecimal("2.0")) : Optional.empty());

        List<SpotTick> first = p.spots();

        assertThat(find(first, "EUR/USD").mid()).isEqualByComparingTo("2.00000");
        assertThat(find(first, "GBP/USD").mid()).isEqualByComparingTo("1.26650");
        assertThat(find(first, "EUR/USD").changeBps()).isEqualByComparingTo("0");
    }

    @Test
    void ticksOnlyAsTimePassesAndCapsTheCatchUp() {
        MockRateProvider p = provider(pair -> Optional.empty());
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
        MockRateProvider p = provider(pair -> Optional.empty());
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
        MockRateProvider p = provider(pair -> Optional.empty());
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
        MockRateProvider p = provider(pair -> Optional.empty());
        double eurUsd = find(p.spots(), "EUR/USD").mid().doubleValue();

        assertThat(p.spot("USD/EUR").orElseThrow().mid().doubleValue()).isCloseTo(1 / eurUsd, within(1e-5));
        assertThat(p.spot("EUR/CAD")).isEmpty();
    }
}
