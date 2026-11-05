package com.bank.vam.fx.deal;

import com.bank.vam.exception.BusinessException;
import com.bank.vam.fx.ForwardPricer;
import com.bank.vam.fx.ForwardQuote;
import com.bank.vam.fx.HomeCurrency;
import com.bank.vam.service.treasury.FxRateService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.bank.vam.fx.deal.DealFixtures.CORP;
import static com.bank.vam.fx.deal.DealFixtures.bd;
import static com.bank.vam.fx.deal.DealFixtures.deal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FxMtmServiceTest {

    private static final LocalDate SPOT = LocalDate.of(2026, 11, 9);
    private static final LocalDate DUE = LocalDate.of(2027, 2, 9);

    private final ForwardPricer forwards = mock(ForwardPricer.class);
    private final FxRateService rates = mock(FxRateService.class);
    private final HomeCurrency home = mock(HomeCurrency.class);
    private final FxMtmRepository mtms = mock(FxMtmRepository.class);
    private final FxDealRepository deals = mock(FxDealRepository.class);
    private final FxLimitService limits = mock(FxLimitService.class);
    private final FxMtmService service = new FxMtmService(forwards, rates, home, mtms, deals, limits);

    private void market(String forwardMid) {
        when(forwards.spotDate()).thenReturn(SPOT);
        when(forwards.forward(anyString(), any(LocalDate.class))).thenAnswer(inv -> new ForwardQuote(
                inv.getArgument(0), null, SPOT, inv.getArgument(1), 92, bd("1.10"), bd("0"), bd(forwardMid), bd("2"), bd("4"), "TEST"));
        when(home.of(CORP)).thenReturn("AED");
        when(rates.getRate("USD", "AED")).thenReturn(bd("3.6725"));
    }

    @Test
    void aBuyerGainsWhenTheMarketIsAboveItsRateAndASellerLoses() {
        assertThat(FxMtmService.mtmQuote(Side.BUY, bd("100000"), bd("1.10"), bd("1.12"))).isEqualByComparingTo("2000.00");
        assertThat(FxMtmService.mtmQuote(Side.SELL, bd("100000"), bd("1.10"), bd("1.12"))).isEqualByComparingTo("-2000.00");
        assertThat(FxMtmService.mtmQuote(Side.BUY, bd("100000"), bd("1.10"), bd("1.09"))).isEqualByComparingTo("-1000.00");
    }

    @Test
    void marksAtTheMidForTheRemainingTermAndConvertsToTheHomeCurrency() {
        market("1.12");
        FxDeal d = deal(Side.BUY, Instrument.FORWARD, DealStatus.ACTIVE, DUE);

        FxMtmService.Mark m = service.mark(d).orElseThrow();

        assertThat(m.mtmQuote()).isEqualByComparingTo("2000.00");
        assertThat(m.quoteCurrency()).isEqualTo("USD");
        assertThat(m.mtmHome()).isEqualByComparingTo("7345.00");
        assertThat(m.homeCurrency()).isEqualTo("AED");
        assertThat(m.rateUsed()).isEqualByComparingTo("1.12");
        verify(forwards).forward("EUR/USD", DUE);
    }

    @Test
    void onlyTheUndrawnPartOfAWindowIsMarked() {
        market("1.12");
        FxDeal d = deal(Side.BUY, Instrument.WINDOW, DealStatus.PARTIALLY_DRAWN, DUE);
        d.setDrawnAmount(bd("75000"));

        assertThat(service.mark(d).orElseThrow().mtmQuote()).isEqualByComparingTo("500.00");
    }

    @Test
    void aDealAlreadyPastItsValueDateIsMarkedAtSpot() {
        market("1.12");
        FxDeal d = deal(Side.BUY, Instrument.FORWARD, DealStatus.SETTLEMENT_FAILED, SPOT.minusDays(3));

        service.mark(d);

        verify(forwards).forward("EUR/USD", SPOT);
    }

    @Test
    void withoutAPlatformRateTheHomeFigureIsLeftOutRatherThanGuessed() {
        market("1.12");
        when(rates.getRate("USD", "AED")).thenThrow(new BusinessException("FX_RATE_UNAVAILABLE", "no rate"));

        FxMtmService.Mark m = service.mark(deal(Side.BUY, Instrument.FORWARD, DealStatus.ACTIVE, DUE)).orElseThrow();

        assertThat(m.mtmQuote()).isEqualByComparingTo("2000.00");
        assertThat(m.mtmHome()).isNull();
    }

    @Test
    void aDealTheMarketCannotPriceHasNoMarkInsteadOfBreakingTheBlotter() {
        when(forwards.spotDate()).thenReturn(SPOT);
        when(forwards.forward(anyString(), any(LocalDate.class))).thenThrow(new BusinessException("FX_PAIR_UNSUPPORTED", "No market"));

        assertThat(service.mark(deal(Side.BUY, Instrument.FORWARD, DealStatus.ACTIVE, DUE))).isEmpty();
    }

    @Test
    void theSnapshotStoresOneRowPerDealPerDayAndRefreshesTheLimitCharge() {
        market("1.12");
        FxDeal d = deal(Side.BUY, Instrument.FORWARD, DealStatus.ACTIVE, DUE);
        when(deals.findByStatusIn(any())).thenReturn(List.of(d));
        when(mtms.findByDealIdAndAsOf(d.getId(), SPOT)).thenReturn(Optional.empty());
        when(limits.charge(anyString(), any(), any(), any(), anyString())).thenReturn(bd("640.00"));

        int marked = service.snapshot(SPOT, null);

        assertThat(marked).isEqualTo(1);
        verify(mtms).save(any(FxMtm.class));
        assertThat(d.getLimitCharge()).isEqualByComparingTo("640.00");

        // A rerun on the same day updates the row it already has.
        FxMtm existing = new FxMtm();
        when(mtms.findByDealIdAndAsOf(d.getId(), SPOT)).thenReturn(Optional.of(existing));
        service.snapshot(SPOT, null);
        assertThat(existing.getMtmQuote()).isEqualByComparingTo("2000.00");
        assertThat(existing.getContractedRate()).isEqualByComparingTo("1.10");
    }
}
