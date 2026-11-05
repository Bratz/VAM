package com.bank.vam.fx;

import com.bank.vam.config.FxMarketProperties;
import com.bank.vam.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ForwardPricerTest {

    // Thursday: spot is T+2 business days = Monday 2026-11-09.
    private static final Clock THURSDAY = Clock.fixed(Instant.parse("2026-11-05T09:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate SPOT = LocalDate.of(2026, 11, 9);

    private final RateProvider market = new RateProvider() {
        public List<SpotTick> spots() {
            return List.of(tick("EUR/USD", "1.10000"), tick("USD/JPY", "150.000"));
        }

        public Optional<SpotTick> spot(String pair) {
            return spots().stream().filter(t -> t.pair().equals(pair)).findFirst();
        }

        private SpotTick tick(String pair, String mid) {
            return new SpotTick(pair, new BigDecimal(mid), BigDecimal.ZERO, Instant.EPOCH, "TEST");
        }
    };

    private final ForwardPricer pricer = new ForwardPricer(new FxMarketProperties(), market, THURSDAY);

    @Test
    void spotDateSkipsTheWeekend() {
        assertThat(pricer.spotDate()).isEqualTo(SPOT);
    }

    @Test
    void forwardMatchesCoveredInterestParityComputedIndependently() {
        // EUR 2% (act/360), USD 4% (act/360). 3M from Mon 9 Nov 2026 lands Tue 9 Feb 2027 = 92 days.
        ForwardQuote q = pricer.forward("EUR/USD", "3M");

        assertThat(q.valueDate()).isEqualTo(LocalDate.of(2027, 2, 9));
        assertThat(q.days()).isEqualTo(92);
        double expected = 1.10 * (1 + 0.04 * 92 / 360) / (1 + 0.02 * 92 / 360);
        assertThat(q.forwardMid().doubleValue()).isCloseTo(expected, within(1e-5));
        assertThat(q.forwardPoints().doubleValue()).isCloseTo((expected - 1.10) / 0.0001, within(0.2));
        // The higher-yielding USD is the quote currency, so the base trades at a forward premium.
        assertThat(q.forwardPoints()).isPositive();
    }

    @Test
    void yenPairsQuotePointsInHundredthsAndAccrueOnTheirOwnBasis() {
        // USD 4% vs JPY 0.5%: USD/JPY is at a forward discount, and a pip is 0.01.
        ForwardQuote q = pricer.forward("USD/JPY", "1M");

        double expected = 150.0 * (1 + 0.005 * q.days() / 360) / (1 + 0.04 * q.days() / 360);
        assertThat(q.forwardMid().doubleValue()).isCloseTo(expected, within(1e-3));
        assertThat(q.forwardPoints().doubleValue()).isCloseTo((expected - 150.0) / 0.01, within(0.5));
        assertThat(q.forwardPoints()).isNegative();
    }

    @Test
    void valueDateOnTheSpotDateHasNoPoints() {
        ForwardQuote q = pricer.forward("EUR/USD", SPOT);

        assertThat(q.days()).isZero();
        assertThat(q.forwardPoints()).isEqualByComparingTo("0");
        assertThat(q.forwardMid().doubleValue()).isCloseTo(1.10, within(1e-9));
    }

    @Test
    void curveCoversTheStandardTenorsAndPointsGrowWithTime() {
        List<ForwardQuote> curve = pricer.curve("EUR/USD");

        assertThat(curve).extracting(ForwardQuote::tenor).containsExactlyElementsOf(ForwardPricer.STANDARD_TENORS);
        assertThat(curve).extracting(ForwardQuote::forwardPoints).isSorted();
        assertThat(curve).allSatisfy(q -> assertThat(q.valueDate().getDayOfWeek().getValue()).isLessThan(6));
    }

    @Test
    void rejectsWeekendsPastDatesTooFarOutAndUnknownInputs() {
        assertThatThrownBy(() -> pricer.forward("EUR/USD", LocalDate.of(2026, 11, 14)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("weekend");
        assertThatThrownBy(() -> pricer.forward("EUR/USD", LocalDate.of(2026, 11, 6)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("before the spot date");
        assertThatThrownBy(() -> pricer.forward("EUR/USD", LocalDate.of(2028, 1, 3)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("more than 365 days");
        assertThatThrownBy(() -> pricer.forward("EUR/USD", "3Q"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Tenor");
        assertThatThrownBy(() -> pricer.forward("EUR/CAD", "1M"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("No market");
    }
}
