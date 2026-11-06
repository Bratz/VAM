package com.bank.vam.fx.deal;

import com.bank.vam.config.FxDealProperties;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.fx.ForwardPricer;
import com.bank.vam.fx.deal.SimulatedDealer.Price;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FxStreamServiceTest {

    private static final LocalDate SPOT = LocalDate.of(2026, 11, 10);
    private static final UUID CORP = UUID.randomUUID();
    private static final UUID ENTITY = UUID.randomUUID();

    private final SimulatedDealer dealer = mock(SimulatedDealer.class);
    private final ForwardPricer forwards = mock(ForwardPricer.class);
    private final FxQuoteService quotes = mock(FxQuoteService.class);
    private final FxStreamService stream = new FxStreamService(dealer, forwards, quotes, new FxDealProperties());

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static Price price(String rate) {
        return new Price(bd("1.1000"), bd("0"), bd("1.1000"), bd("3"), bd(rate), bd(rate).multiply(bd("100000")), SPOT);
    }

    private static FxQuoteService.Command cmd(String pair, String amount, Instrument instrument, LocalDate valueDate) {
        return new FxQuoteService.Command(CORP, ENTITY, pair, Side.BUY, instrument, bd(amount), valueDate, null, null, null);
    }

    @BeforeEach
    void wiring() {
        when(forwards.spotDate()).thenReturn(SPOT);
        when(dealer.price(any(), eq(Side.BUY), any(), any(), any(), any())).thenReturn(price("1.1003"));
        when(dealer.price(any(), eq(Side.SELL), any(), any(), any(), any())).thenReturn(price("1.0997"));
    }

    @Test
    void aSmallMajorSpotStreamsBothSides() {
        FxStreamService.StreamPrice p = stream.price(cmd("EUR/USD", "100000", Instrument.SPOT, null));

        assertThat(p.streamable()).isTrue();
        assertThat(p.buy().rate()).isEqualByComparingTo("1.1003");
        assertThat(p.sell().rate()).isEqualByComparingTo("1.0997");
        assertThat(p.priceId()).isNotNull();
    }

    @Test
    void sizeTenorAndInstrumentDecideWhatGoesToARequestForQuote() {
        assertThat(stream.price(cmd("EUR/USD", "1500000", Instrument.SPOT, null)).reason())
                .isEqualTo("Above 1,000,000 EUR in EUR/USD the desk prices on request");
        assertThat(stream.price(cmd("EUR/USD", "100000", Instrument.FORWARD, SPOT.plusDays(31))).streamable()).isTrue();
        assertThat(stream.price(cmd("EUR/USD", "100000", Instrument.FORWARD, SPOT.plusDays(32))).reason())
                .isEqualTo("EUR/USD forwards beyond 31 days after spot are priced on request");
        assertThat(stream.price(cmd("USD/AED", "100000", Instrument.FORWARD, SPOT.plusDays(10))).reason())
                .isEqualTo("USD/AED forwards are priced on request");
        assertThat(stream.price(cmd("USD/AED", "300000", Instrument.SPOT, null)).reason()).startsWith("Above 250,000 USD");
        assertThat(stream.price(cmd("EUR/USD", "100000", Instrument.WINDOW, SPOT.plusDays(20))).reason())
                .isEqualTo("Window forwards are priced on request");
        // The inverse of a listed pair uses its row.
        assertThat(stream.price(cmd("USD/EUR", "900000", Instrument.SPOT, null)).streamable()).isTrue();
    }

    @Test
    void dealingBooksAtTheShownSideOnceAndOnlyOnce() {
        FxStreamService.StreamPrice p = stream.price(cmd("EUR/USD", "100000", Instrument.SPOT, null));
        FxQuoteRequest q = new FxQuoteRequest();
        q.setId(UUID.randomUUID());
        q.setStatus(QuoteStatus.QUOTED);
        when(quotes.request(argThat(c -> c.side() == Side.SELL), any(Price.class))).thenReturn(q);
        FxDeal deal = new FxDeal();
        when(quotes.accept(q.getId())).thenReturn(deal);

        assertThat(stream.deal(p.priceId(), Side.SELL)).isSameAs(deal);
        verify(quotes).request(argThat(c -> c.side() == Side.SELL), argThat(pr -> pr.clientRate().compareTo(bd("1.0997")) == 0));
        assertThatThrownBy(() -> stream.deal(p.priceId(), Side.SELL)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("price has moved on");
    }

    @Test
    void aDeclinedStreamedDealSaysWhy() {
        FxStreamService.StreamPrice p = stream.price(cmd("EUR/USD", "100000", Instrument.SPOT, null));
        FxQuoteRequest q = new FxQuoteRequest();
        q.setStatus(QuoteStatus.REJECTED);
        q.setStatusReason("Not quoted. The FX sub-limit of Test Ltd would be exceeded");
        when(quotes.request(any(), any(Price.class))).thenReturn(q);

        assertThatThrownBy(() -> stream.deal(p.priceId(), Side.BUY)).hasMessage("Not quoted. The FX sub-limit of Test Ltd would be exceeded");
    }
}
