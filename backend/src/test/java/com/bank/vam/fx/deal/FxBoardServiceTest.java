package com.bank.vam.fx.deal;

import com.bank.vam.fx.ForwardPricer;
import com.bank.vam.fx.ForwardQuote;
import com.bank.vam.fx.RateProvider;
import com.bank.vam.fx.SpotTick;
import com.bank.vam.fx.deal.SimulatedDealer.Price;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Market Rates shows the corporate's own two-way price at its amount, never the mid. */
class FxBoardServiceTest {

    private static final LocalDate SPOT = LocalDate.of(2026, 11, 10);
    private final RateProvider rates = mock(RateProvider.class);
    private final ForwardPricer forwards = mock(ForwardPricer.class);
    private final SimulatedDealer dealer = mock(SimulatedDealer.class);
    private final FxBoardService board = new FxBoardService(rates, forwards, dealer);

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static Price price(String rate, LocalDate date) {
        return new Price(bd("1.1000"), bd("20"), bd("1.1020"), bd("3"), bd(rate), bd("0"), date);
    }

    @Test
    void eachPairShowsTheClientsBuyAndSellPriceAtTheAmountChosen() {
        when(rates.spots()).thenReturn(List.of(new SpotTick("EUR/USD", bd("1.1000"), bd("2.5"), Instant.now(), "MOCK")));
        when(dealer.price(eq("EUR/USD"), eq(Side.BUY), eq(Instrument.SPOT), eq(bd("250000")), any(), any())).thenReturn(price("1.1004", SPOT));
        when(dealer.price(eq("EUR/USD"), eq(Side.SELL), eq(Instrument.SPOT), eq(bd("250000")), any(), any())).thenReturn(price("1.0996", SPOT));

        FxBoardService.BoardRow row = board.board(bd("250000")).get(0);

        assertThat(row.buy()).isEqualByComparingTo("1.1004");
        assertThat(row.sell()).isEqualByComparingTo("1.0996");
        assertThat(row.changeBps()).isEqualByComparingTo("2.5");
        assertThat(row.valueDate()).isEqualTo(SPOT);
    }

    @Test
    void withoutAnAmountTheBoardPricesOneHundredThousand() {
        when(rates.spots()).thenReturn(List.of(new SpotTick("EUR/USD", bd("1.1000"), bd("0"), Instant.now(), "MOCK")));
        when(dealer.price(any(), any(), any(), any(), any(), any())).thenReturn(price("1.1", SPOT));

        board.board(null);

        verify(dealer).price("EUR/USD", Side.BUY, Instrument.SPOT, bd("100000"), null, null);
        assertThatThrownBy(() -> board.board(bd("0"))).hasMessageContaining("greater than zero");
    }

    @Test
    void aForwardTenorShowsTheOutrightRateEachWayWithTheClientPoints() {
        LocalDate oneMonth = SPOT.plusMonths(1);
        when(forwards.curve("EUR/USD")).thenReturn(List.of(new ForwardQuote("EUR/USD", "1M", SPOT, oneMonth, 30,
                bd("1.1000"), bd("20"), bd("1.1020"), bd("3.0"), bd("4.0"), "MOCK")));
        when(dealer.price(eq("EUR/USD"), eq(Side.BUY), eq(Instrument.FORWARD), any(), eq(oneMonth), any())).thenReturn(price("1.10243", oneMonth));
        when(dealer.price(eq("EUR/USD"), eq(Side.SELL), eq(Instrument.FORWARD), any(), eq(oneMonth), any())).thenReturn(price("1.10157", oneMonth));

        FxBoardService.CurveRow r = board.curve("EUR/USD", null).get(0);

        assertThat(r.tenor()).isEqualTo("1M");
        assertThat(r.buy()).isEqualByComparingTo("1.10243");
        assertThat(r.sell()).isEqualByComparingTo("1.10157");
        assertThat(r.buyPoints()).isNotNull();
        assertThat(r.sellPoints()).isNotNull();
    }
}
