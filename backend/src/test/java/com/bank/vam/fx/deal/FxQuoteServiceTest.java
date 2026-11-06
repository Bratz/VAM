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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    private final FxEvents events = mock(FxEvents.class);
    private final FxSettlementAccounts settlementAccounts = mock(FxSettlementAccounts.class);
    private final FxQuoteService service = new FxQuoteService(
            quotes, corporates, dealer, limitService, dealService, deals, events, props, forwards, settlementAccounts);

    private static final UUID ENTITY = UUID.randomUUID();
    private static final FxLimitService.Usage ROOM = new FxLimitService.Usage(new BigDecimal("1000000"), BigDecimal.ZERO, new BigDecimal("1000000"));
    private FxDeal deal;

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
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
        when(limitService.allocation(CORP, ENTITY)).thenReturn(new FxLimitService.Allocation(
                ENTITY, "Test Treasury Ltd", UUID.randomUUID(), "AED", true, null, ROOM));
        when(limitService.line(CORP)).thenReturn(new FxLimitService.Line(
                CORP, UUID.randomUUID(), UUID.randomUUID(), "FX treasury line", "AED", null, true, null, ROOM));
        when(limitService.charge(any(), anyString(), any(), any(), any(), anyString())).thenReturn(bd("1000.00"));
        // refusal(...) answers null by default: the deal fits.
        when(settlementAccounts.resolve(eq(CORP), eq(ENTITY), anyString())).thenAnswer(inv -> {
            String ccy = inv.getArgument(2);
            return new FxSettlementAccounts.Resolved(ccy, new FxSettlementAccounts.Account(UUID.randomUUID(), ccy,
                    ccy + " account (Home Bank)", "Home Bank", bd("0")), true, null);
        });
        when(dealService.book(any(), any(), anyString())).thenReturn(deal);
        when(deals.findById(deal.getId())).thenReturn(Optional.of(deal));
    }

    private FxQuoteService.Command command() {
        return new FxQuoteService.Command(CORP, ENTITY, "EUR/USD", Side.BUY, Instrument.SPOT, bd("100000"), null, null, null, null);
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
        when(limitService.refusal(eq(CORP), eq(ENTITY), any())).thenReturn(
                "The FX sub-limit of Test Treasury Ltd would be exceeded (needs 1,000.00 AED, 500.00 left)");

        FxQuoteRequest q = service.request(command());

        assertThat(q.getStatus()).isEqualTo(QuoteStatus.REJECTED);
        assertThat(q.getStatusReason()).startsWith("Not quoted. The FX sub-limit of Test Treasury Ltd");
        assertThat(q.getClientRate()).isNull();
    }

    @Test
    void aQuoteCarriesTheAccountsBothLegsSettleThrough() {
        FxQuoteRequest q = service.request(command());

        // Buying EUR/USD: EUR is credited, USD debited.
        assertThat(q.getBuyAccountLabel()).isEqualTo("EUR account (Home Bank)");
        assertThat(q.getSellAccountLabel()).isEqualTo("USD account (Home Bank)");
        assertThat(q.getBuyAccountId()).isNotNull();
    }

    @Test
    void aRequestWithNoAccountToSettleALegIsDeclinedWithTheReason() {
        when(settlementAccounts.resolve(CORP, ENTITY, "USD")).thenReturn(new FxSettlementAccounts.Resolved("USD", null, false,
                "Test Treasury Ltd has 2 USD accounts at the home bank; choose one under Settlement Instructions"));

        FxQuoteRequest q = service.request(command());

        assertThat(q.getStatus()).isEqualTo(QuoteStatus.REJECTED);
        assertThat(q.getStatusReason()).isEqualTo(
                "Not quoted. Test Treasury Ltd has 2 USD accounts at the home bank; choose one under Settlement Instructions.");
        assertThat(q.getClientRate()).isNull();
    }

    @Test
    void theDealIsForALegalEntityAndARequestWithoutOneIsRefusedBeforeAnythingIsSaved() {
        FxQuoteRequest q = service.request(command());
        assertThat(q.getEntityId()).isEqualTo(ENTITY);
        assertThat(q.getEntityName()).isEqualTo("Test Treasury Ltd");

        org.mockito.Mockito.clearInvocations(quotes);
        assertThatThrownBy(() -> service.request(new FxQuoteService.Command(
                CORP, null, "EUR/USD", Side.BUY, Instrument.SPOT, bd("100000"), null, null, null, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("legal entity");
        verify(quotes, never()).save(any());
    }

    @Test
    void acceptingChecksTheEntityAndTheLineUnderLocksAndBooksInTheLineCurrency() {
        FxQuoteRequest q = firmQuote();

        service.accept(q.getId());

        verify(limitService).requireLocked(CORP, ENTITY, bd("1000.00"));
        verify(dealService).book(any(), eq(bd("1000.00")), eq("AED"));
    }

    @Test
    void validatesTheRequestBeforeAnythingIsSaved() {
        assertThatThrownBy(() -> service.request(new FxQuoteService.Command(
                CORP, ENTITY, "EURUSD", Side.BUY, Instrument.SPOT, bd("100000"), null, null, null, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("EUR/USD");
        assertThatThrownBy(() -> service.request(new FxQuoteService.Command(
                CORP, ENTITY, "EUR/USD", Side.BUY, Instrument.SPOT, bd("10"), null, null, null, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("at least");
        assertThatThrownBy(() -> service.request(new FxQuoteService.Command(
                CORP, ENTITY, "EUR/USD", Side.BUY, Instrument.FORWARD, bd("100000"), null, null, null, null)))
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
        doThrow(new BusinessException("FX_LIMIT_EXCEEDED", "used by another deal")).when(limitService).requireLocked(any(), any(), any());

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

    // ---- negotiation ---------------------------------------------------------------------------------------

    private void deskOffers(String rate, String spread, boolean atBest) {
        when(dealer.counter(any(), any(), any(), any(), any(), any(), anyInt(), any())).thenReturn(
                new SimulatedDealer.Counter(bd(spread), bd(rate), bd("100000").multiply(bd(rate)), atBest));
    }

    @Test
    void aCounterGetsANewFirmPriceCountsTheRoundAndRestartsTheClock() {
        FxQuoteRequest q = firmQuote();
        q.setValidUntil(LocalDateTime.now().plusSeconds(3));
        deskOffers("1.1002", "2.60", false);

        FxQuoteRequest out = service.counter(q.getId(), bd("1.1001"), "volume to come");

        assertThat(out.getStatus()).isEqualTo(QuoteStatus.QUOTED);
        assertThat(out.getClientRate()).isEqualByComparingTo("1.1002");
        assertThat(out.getSpreadBps()).isEqualByComparingTo("2.60");
        assertThat(out.roundsUsed()).isEqualTo(1);
        assertThat(out.roundsAllowed()).isEqualTo(3);
        assertThat(out.getBestPrice()).isFalse();
        assertThat(Duration.between(out.getQuotedAt(), out.getValidUntil()).toSeconds()).isEqualTo(30);
        // The credit charge does not depend on the rate.
        assertThat(out.getLimitCharge()).isEqualByComparingTo("1000.00");
        verify(events).counter(any(), org.mockito.ArgumentMatchers.contains("Round 1 of 3"));
    }

    @Test
    void whenTheDeskCannotMoveItSaysSoAndStillUsesUpTheRound() {
        FxQuoteRequest q = firmQuote();
        deskOffers("1.1003", "3.00", true); // same rate as the opening price

        FxQuoteRequest out = service.counter(q.getId(), null, null);

        assertThat(out.getBestPrice()).isTrue();
        assertThat(out.roundsUsed()).isEqualTo(1);
        verify(events).counter(any(), org.mockito.ArgumentMatchers.contains("its best price"));
    }

    @Test
    void onlyAFewRoundsAreAllowedAndThenTheClientMustAcceptOrWithdraw() {
        FxQuoteRequest q = firmQuote();
        deskOffers("1.1002", "2.60", false);
        service.counter(q.getId(), null, null);
        service.counter(q.getId(), null, null);
        service.counter(q.getId(), null, null);

        assertThatThrownBy(() -> service.counter(q.getId(), null, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("No more rounds");
        assertThat(q.roundsUsed()).isEqualTo(3);
    }

    @Test
    void aLapsedOrWithdrawnQuoteCannotBeCountered() {
        FxQuoteRequest lapsed = firmQuote();
        lapsed.setValidUntil(LocalDateTime.now().minusSeconds(1));
        assertThatThrownBy(() -> service.counter(lapsed.getId(), null, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("EXPIRED");

        FxQuoteRequest withdrawn = firmQuote();
        service.cancel(withdrawn.getId());
        assertThatThrownBy(() -> service.counter(withdrawn.getId(), null, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("CANCELLED");
        verify(dealer, never()).counter(any(), any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void aQuoteFromBeforeNegotiationExistedCannotBeNegotiated() {
        FxQuoteRequest q = firmQuote();
        q.setForwardMid(null);

        assertThatThrownBy(() -> service.counter(q.getId(), null, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("cannot be negotiated");
    }

    @Test
    void theAcceptedPriceIsTheNegotiatedOne() {
        FxQuoteRequest q = firmQuote();
        deskOffers("1.1002", "2.60", false);
        service.counter(q.getId(), null, null);

        service.accept(q.getId());

        org.mockito.ArgumentCaptor<FxQuoteRequest> booked = org.mockito.ArgumentCaptor.forClass(FxQuoteRequest.class);
        verify(dealService).book(booked.capture(), any(), anyString());
        assertThat(booked.getValue().getClientRate()).isEqualByComparingTo("1.1002");
    }
}
