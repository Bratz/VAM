package com.bank.vam.fx.deal;

import com.bank.vam.config.FxDealProperties;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.fx.ForwardPricer;
import com.bank.vam.fx.deal.SimulatedDealer.Price;
import com.bank.vam.repository.CorporateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FxQuoteServiceTest {

    private static final UUID CORP = UUID.randomUUID();

    private final FxQuoteRequestRepository quotes = mock(FxQuoteRequestRepository.class);
    private final CorporateRepository corporates = mock(CorporateRepository.class);
    private final SimulatedDealer dealer = mock(SimulatedDealer.class);
    private final FxLimitService limitService = mock(FxLimitService.class);
    private final FxDealService dealService = mock(FxDealService.class);
    private final FxDealRepository deals = mock(FxDealRepository.class);
    private final ForwardPricer forwards = mock(ForwardPricer.class);
    private final FxDealProperties props = new FxDealProperties();
    private final FxQuoteService service = new FxQuoteService(
            quotes, corporates, dealer, limitService, dealService, deals, mock(FxEvents.class), props, forwards);

    private final FxCreditLimit limit = limit();
    private FxDeal deal;

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static FxCreditLimit limit() {
        FxCreditLimit l = new FxCreditLimit();
        l.setCorporateId(CORP);
        l.setCurrency("AED");
        l.setLimitAmount(bd("1000000"));
        return l;
    }

    @BeforeEach
    void wiring() {
        deal = new FxDeal();
        deal.setId(UUID.randomUUID());
        deal.setDealRef("FXD-TEST");
        when(corporates.existsById(CORP)).thenReturn(true);
        when(forwards.spotDate()).thenReturn(LocalDate.of(2026, 11, 9));
        when(quotes.save(any(FxQuoteRequest.class))).thenAnswer(inv -> {
            FxQuoteRequest q = inv.getArgument(0);
            if (q.getId() == null) {
                q.setId(UUID.randomUUID());
            }
            return q;
        });
        when(dealer.price(anyString(), any(), any(), any(), any(), any())).thenReturn(new Price(
                bd("1.10"), bd("0"), bd("1.10"), bd("3.00"), bd("1.1003"), bd("110030.00"), LocalDate.of(2026, 11, 9)));
        when(limitService.limitFor(CORP)).thenReturn(limit);
        when(limitService.lockLimitFor(CORP)).thenReturn(limit);
        when(limitService.charge(anyString(), any(), any(), any(), anyString())).thenReturn(bd("1000.00"));
        when(limitService.position(limit)).thenReturn(
                new FxLimitService.Position(CORP, "AED", bd("1000000"), bd("0"), bd("1000000")));
        when(dealService.book(any(), any(), anyString())).thenReturn(deal);
        when(deals.findById(deal.getId())).thenReturn(Optional.of(deal));
    }

    private FxQuoteService.Command command() {
        return new FxQuoteService.Command(CORP, "EUR/USD", Side.BUY, Instrument.SPOT, bd("100000"), null, null, null, null);
    }

    /** A quote the dealer has priced and that is still firm. */
    private FxQuoteRequest firmQuote() {
        FxQuoteRequest q = service.request(command());
        when(quotes.lockById(q.getId())).thenReturn(Optional.of(q));
        return q;
    }

    @Test
    void aRequestWithinLimitIsQuotedFirmForTheConfiguredSeconds() {
        FxQuoteRequest q = service.request(command());

        assertThat(q.getStatus()).isEqualTo(QuoteStatus.QUOTED);
        assertThat(q.getClientRate()).isEqualByComparingTo("1.1003");
        assertThat(q.getValueDate()).isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(Duration.between(q.getQuotedAt(), q.getValidUntil()).toSeconds()).isEqualTo(30);
        assertThat(q.getLimitCharge()).isEqualByComparingTo("1000.00");
        assertThat(q.getDealer()).isEqualTo("SIMULATED");
    }

    @Test
    void aRequestOverTheLimitIsDeclinedWithTheReasonAndNoPrice() {
        when(limitService.position(limit)).thenReturn(
                new FxLimitService.Position(CORP, "AED", bd("1000000"), bd("999500"), bd("500")));

        FxQuoteRequest q = service.request(command());

        assertThat(q.getStatus()).isEqualTo(QuoteStatus.REJECTED);
        assertThat(q.getStatusReason()).contains("credit limit");
        assertThat(q.getClientRate()).isNull();
    }

    @Test
    void validatesTheRequestBeforeAnythingIsSaved() {
        assertThatThrownBy(() -> service.request(new FxQuoteService.Command(
                CORP, "EURUSD", Side.BUY, Instrument.SPOT, bd("100000"), null, null, null, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("EUR/USD");
        assertThatThrownBy(() -> service.request(new FxQuoteService.Command(
                CORP, "EUR/USD", Side.BUY, Instrument.SPOT, bd("10"), null, null, null, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("at least");
        assertThatThrownBy(() -> service.request(new FxQuoteService.Command(
                CORP, "EUR/USD", Side.BUY, Instrument.FORWARD, bd("100000"), null, null, null, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("value date");
        verify(quotes, never()).save(any());
    }

    @Test
    void acceptingTwiceBooksOneDealAndReturnsItBothTimes() {
        FxQuoteRequest q = firmQuote();

        FxDeal first = service.accept(q.getId());
        FxDeal second = service.accept(q.getId());

        assertThat(first).isSameAs(deal);
        assertThat(second).isSameAs(deal);
        assertThat(q.getStatus()).isEqualTo(QuoteStatus.ACCEPTED);
        assertThat(q.getDealId()).isEqualTo(deal.getId());
        verify(dealService, times(1)).book(any(), any(), anyString());
    }

    @Test
    void aLapsedQuoteCannotBeAcceptedAndIsMarkedExpired() {
        FxQuoteRequest q = firmQuote();
        q.setValidUntil(LocalDateTime.now().minusSeconds(1));

        assertThatThrownBy(() -> service.accept(q.getId()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("EXPIRED");
        assertThat(q.getStatus()).isEqualTo(QuoteStatus.EXPIRED);
        verify(dealService, never()).book(any(), any(), anyString());
    }

    @Test
    void theLimitIsCheckedAgainAtAcceptanceAndAFailureBooksNothing() {
        FxQuoteRequest q = firmQuote();
        doThrow(new BusinessException("FX_LIMIT_EXCEEDED", "used by another deal")).when(limitService).require(any(), any());

        assertThatThrownBy(() -> service.accept(q.getId()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("used by another deal");
        assertThat(q.getStatus()).isEqualTo(QuoteStatus.QUOTED);
        verify(dealService, never()).book(any(), any(), anyString());
    }

    @Test
    void aQuoteCanBeWithdrawnOnceButNotAfterItIsAccepted() {
        FxQuoteRequest q = firmQuote();

        assertThat(service.cancel(q.getId()).getStatus()).isEqualTo(QuoteStatus.CANCELLED);
        assertThat(service.cancel(q.getId()).getStatus()).isEqualTo(QuoteStatus.CANCELLED);
        assertThatThrownBy(() -> service.accept(q.getId())).isInstanceOf(BusinessException.class).hasMessageContaining("CANCELLED");

        FxQuoteRequest accepted = firmQuote();
        service.accept(accepted.getId());
        assertThatThrownBy(() -> service.cancel(accepted.getId()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("ACCEPTED");
    }
}
