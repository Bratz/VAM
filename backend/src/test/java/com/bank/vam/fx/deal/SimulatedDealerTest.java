package com.bank.vam.fx.deal;

import com.bank.vam.config.FxDealProperties;
import com.bank.vam.config.FxMarketProperties;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.fx.ForwardPricer;
import com.bank.vam.fx.RateProvider;
import com.bank.vam.fx.SpotTick;
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

class SimulatedDealerTest {

    // Thursday: spot is Monday 2026-11-09.
    private static final Clock THURSDAY = Clock.fixed(Instant.parse("2026-11-05T09:00:00Z"), ZoneOffset.UTC);

    private final RateProvider market = new RateProvider() {
        public List<SpotTick> spots() {
            return List.of(new SpotTick("EUR/USD", new BigDecimal("1.10000"), BigDecimal.ZERO, Instant.EPOCH, "TEST"));
        }

        public Optional<SpotTick> spot(String pair) {
            return spots().stream().filter(t -> t.pair().equals(pair)).findFirst();
        }
    };

    private final ForwardPricer pricer = new ForwardPricer(new FxMarketProperties(), market, THURSDAY);
    private final SimulatedDealer dealer = new SimulatedDealer(pricer, new FxDealProperties());

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void spotMovesTheRateAgainstTheClientByTheBaseSpread() {
        SimulatedDealer.Price buy = dealer.price("EUR/USD", Side.BUY, Instrument.SPOT, bd("100000"), null, null);
        SimulatedDealer.Price sell = dealer.price("EUR/USD", Side.SELL, Instrument.SPOT, bd("100000"), null, null);

        assertThat(buy.spreadBps()).isEqualByComparingTo("3.00");
        assertThat(buy.clientRate()).isEqualByComparingTo("1.10033");
        assertThat(buy.counterAmount()).isEqualByComparingTo("110033.00");
        assertThat(sell.clientRate()).isEqualByComparingTo("1.09967");
        assertThat(sell.counterAmount()).isEqualByComparingTo("109967.00");
        assertThat(buy.forwardPoints()).isEqualByComparingTo("0");
        assertThat(buy.pricedDate()).isEqualTo(LocalDate.of(2026, 11, 9));
    }

    @Test
    void aForwardCarriesPointsAndALongerTenorCostsMoreSpread() {
        LocalDate threeMonths = LocalDate.of(2027, 2, 9);
        SimulatedDealer.Price p = dealer.price("EUR/USD", Side.BUY, Instrument.FORWARD, bd("100000"), threeMonths, null);

        // 92 days past spot: 3 bps base plus 0.5 bps a month.
        assertThat(p.spreadBps()).isEqualByComparingTo("4.53");
        assertThat(p.forwardPoints()).isPositive();
        assertThat(p.forwardMid()).isGreaterThan(p.spotMid());
        assertThat(p.clientRate()).isGreaterThan(p.forwardMid());
    }

    @Test
    void largeTicketsPayMore() {
        assertThat(dealer.price("EUR/USD", Side.BUY, Instrument.SPOT, bd("1000000"), null, null).spreadBps()).isEqualByComparingTo("4.00");
        assertThat(dealer.price("EUR/USD", Side.BUY, Instrument.SPOT, bd("5000000"), null, null).spreadBps()).isEqualByComparingTo("6.00");
    }

    @Test
    void aWindowIsPricedAtTheDateWorstForTheClient() {
        LocalDate start = LocalDate.of(2026, 12, 10);
        LocalDate end = LocalDate.of(2027, 2, 10);
        BigDecimal atStart = pricer.forward("EUR/USD", start).forwardMid();
        BigDecimal atEnd = pricer.forward("EUR/USD", end).forwardMid();
        assertThat(atEnd).isGreaterThan(atStart);

        // The forward rises with time: a buyer is quoted the later (dearer) date, a seller the earlier (cheaper) one.
        assertThat(dealer.price("EUR/USD", Side.BUY, Instrument.WINDOW, bd("100000"), end, start).forwardMid()).isEqualByComparingTo(atEnd);
        assertThat(dealer.price("EUR/USD", Side.SELL, Instrument.WINDOW, bd("100000"), end, start).forwardMid()).isEqualByComparingTo(atStart);
    }

    @Test
    void rejectsABackwardsWindowAndAnUnknownPair() {
        assertThatThrownBy(() -> dealer.price("EUR/USD", Side.BUY, Instrument.WINDOW, bd("100000"),
                LocalDate.of(2026, 12, 10), LocalDate.of(2027, 1, 10)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("start date before");
        assertThatThrownBy(() -> dealer.price("EUR/CAD", Side.BUY, Instrument.SPOT, bd("100000"), null, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("No market");
    }
}
