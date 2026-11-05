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

    // ---- negotiation ---------------------------------------------------------------------------------------

    private SimulatedDealer.Price open(Side side, String amount) {
        return dealer.price("EUR/USD", side, Instrument.SPOT, bd(amount), null, null);
    }

    private SimulatedDealer.Counter counter(Side side, String amount, SimulatedDealer.Price opening, BigDecimal spread, BigDecimal rate, String target) {
        return dealer.counter(side, bd(amount), opening.forwardMid(), opening.spreadBps(), spread, rate, opening.forwardMid().scale(), target == null ? null : bd(target));
    }

    @Test
    void askingForTheBestPriceGivesUpHalfTheRoomAboveTheFloorAndMovesTheRateTheRightWay() {
        SimulatedDealer.Price p = open(Side.BUY, "100000"); // 3.00 bps, floor 2.25 (25% of the opening margin)

        SimulatedDealer.Counter c = counter(Side.BUY, "100000", p, p.spreadBps(), p.clientRate(), null);

        assertThat(c.spreadBps()).isEqualByComparingTo("2.63");
        assertThat(c.clientRate()).isLessThan(p.clientRate()); // buying: lower is better
        assertThat(c.counterAmount()).isEqualByComparingTo(bd("100000").multiply(c.clientRate()));
        assertThat(c.atBest()).isFalse();
        SimulatedDealer.Price sellOpen = open(Side.SELL, "100000");
        SimulatedDealer.Counter sell = counter(Side.SELL, "100000", sellOpen, sellOpen.spreadBps(), sellOpen.clientRate(), null);
        assertThat(sell.clientRate()).isGreaterThan(sellOpen.clientRate()); // selling: higher is better
    }

    @Test
    void theDeskNeverGoesBelowItsFloorHoweverManyTimesItIsAsked() {
        SimulatedDealer.Price p = open(Side.BUY, "100000");
        BigDecimal spread = p.spreadBps();
        BigDecimal rate = p.clientRate();
        SimulatedDealer.Counter last = null;
        for (int i = 0; i < 12; i++) {
            last = counter(Side.BUY, "100000", p, spread, rate, null);
            spread = last.spreadBps();
            rate = last.clientRate();
        }

        assertThat(spread).isGreaterThanOrEqualTo(bd("2.25"));
        assertThat(last.atBest()).isTrue();
        // Asking again at the floor changes nothing and says so.
        SimulatedDealer.Counter again = counter(Side.BUY, "100000", p, spread, rate, null);
        assertThat(again.clientRate()).isEqualByComparingTo(rate);
        assertThat(again.atBest()).isTrue();
    }

    @Test
    void aNamedRateWithinReachIsMetExactlyAndAnAmbitiousOneIsMetPartWay() {
        SimulatedDealer.Price p = open(Side.BUY, "100000"); // client rate 1.10033

        SimulatedDealer.Counter modest = counter(Side.BUY, "100000", p, p.spreadBps(), p.clientRate(), "1.10031");
        SimulatedDealer.Counter ambitious = counter(Side.BUY, "100000", p, p.spreadBps(), p.clientRate(), "1.10000");

        assertThat(modest.clientRate()).isEqualByComparingTo("1.10031");
        assertThat(ambitious.clientRate()).isGreaterThan(bd("1.10000")).isLessThan(p.clientRate());
    }

    @Test
    void aRateThatIsNotBetterThanTheCurrentOneIsRefusedWithAReason() {
        SimulatedDealer.Price p = open(Side.BUY, "100000");

        assertThatThrownBy(() -> counter(Side.BUY, "100000", p, p.spreadBps(), p.clientRate(), "1.10040"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("better than the current");
        assertThatThrownBy(() -> counter(Side.BUY, "100000", p, p.spreadBps(), p.clientRate(), "0"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void aLargeTicketHasMoreRoomToNegotiateThanASmallOne() {
        SimulatedDealer.Price small = open(Side.BUY, "100000");
        SimulatedDealer.Price huge = open(Side.BUY, "6000000");

        BigDecimal smallGiven = small.spreadBps().subtract(floorAfterManyRounds("100000", small)).divide(small.spreadBps(), 3, java.math.RoundingMode.HALF_UP);
        BigDecimal hugeGiven = huge.spreadBps().subtract(floorAfterManyRounds("6000000", huge)).divide(huge.spreadBps(), 3, java.math.RoundingMode.HALF_UP);

        assertThat(hugeGiven).isGreaterThan(smallGiven);
    }

    private BigDecimal floorAfterManyRounds(String amount, SimulatedDealer.Price p) {
        BigDecimal spread = p.spreadBps();
        BigDecimal rate = p.clientRate();
        for (int i = 0; i < 15; i++) {
            SimulatedDealer.Counter c = counter(Side.BUY, amount, p, spread, rate, null);
            spread = c.spreadBps();
            rate = c.clientRate();
        }
        return spread;
    }
}
