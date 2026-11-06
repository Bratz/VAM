package com.bank.vam.fx.deal;

import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.fx.deal.SimulatedDealer.Price;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.bank.vam.fx.deal.DealFixtures.CORP;
import static com.bank.vam.fx.deal.DealFixtures.ENTITY;
import static com.bank.vam.fx.deal.DealFixtures.bd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FxPaymentServiceTest {

    private final FxStreamService stream = mock(FxStreamService.class);
    private final FxQuoteService quotes = mock(FxQuoteService.class);
    private final FxConfirmationService confirmations = mock(FxConfirmationService.class);
    private final FxSettlementService settlement = mock(FxSettlementService.class);
    private final FxDealRepository deals = mock(FxDealRepository.class);
    private final FxDealService dealService = mock(FxDealService.class);
    private final FxPaymentService payments = new FxPaymentService(stream, quotes, confirmations, settlement, deals, dealService);

    /** A USD account of the fixture entity. */
    private static VirtualAccount usdAccount() {
        VirtualAccount v = new VirtualAccount();
        v.setVaNumber("VA-USD-1");
        v.setCorporateId(CORP);
        v.setOwningEntityId(ENTITY);
        v.setCurrencyCode("USD");
        return v;
    }

    private static FxPaymentService.Instruction price(UUID id) {
        return new FxPaymentService.Instruction(id, null, null, false);
    }

    @Test
    void aPaymentInTheAccountsCurrencyNeedsNoFxAndRefusesOne() {
        assertThat(payments.convert(usdAccount(), "usd", bd("100"), null)).isNull();
        assertThat(payments.convert(usdAccount(), null, bd("100"), null)).isNull();
        assertThatThrownBy(() -> payments.convert(usdAccount(), "USD", bd("100"), price(UUID.randomUUID())))
                .hasMessageContaining("nothing to convert");
    }

    @Test
    void aPaymentInAnotherCurrencyWithoutARateOrDealIsRefusedInsteadOfDebitedOneForOne() {
        assertThatThrownBy(() -> payments.convert(usdAccount(), "EUR", bd("1000"), null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("The payment is in EUR but VA-USD-1 holds USD: lock a rate or use a booked FX deal to convert it");
    }

    @Test
    void aStreamedPriceForThisPaymentBooksAConfirmedSpotAndDebitsItsCounterAmount() {
        UUID priceId = UUID.randomUUID();
        FxQuoteService.Command shown = new FxQuoteService.Command(CORP, ENTITY, "EUR/USD", Side.BUY, Instrument.SPOT, bd("1000"),
                null, null, null, null);
        Price buy = new Price(bd("1.1"), bd("0"), bd("1.1"), bd("3"), bd("1.1003"), bd("1100.30"), LocalDate.now());
        when(stream.take(priceId)).thenReturn(new FxStreamService.Issued(shown, buy, buy, Instant.now().plusSeconds(5)));
        FxQuoteRequest q = new FxQuoteRequest();
        q.setId(UUID.randomUUID());
        q.setStatus(QuoteStatus.QUOTED);
        when(quotes.request(argThat(c -> c.forPayment() && c.pair().equals("EUR/USD") && c.side() == Side.BUY), eq(buy))).thenReturn(q);
        FxDeal deal = DealFixtures.deal(Side.BUY, Instrument.SPOT, DealStatus.PENDING_SETTLEMENT, LocalDate.now());
        deal.setSellAmount(bd("1100.30"));
        when(quotes.accept(q.getId(), true)).thenReturn(deal);

        FxPaymentService.Conversion cv = payments.convert(usdAccount(), "EUR", bd("1000"), price(priceId));

        assertThat(cv.debitAmount()).isEqualByComparingTo("1100.30");
        assertThat(cv.debitCurrency()).isEqualTo("USD");
        assertThat(cv.bookedForIt()).isTrue();
        verify(confirmations).affirm(deal.getId());

        Transaction t = new Transaction();
        t.setReferenceNumber("PAY-1");
        payments.settle(cv, t, usdAccount());
        verify(settlement).paidByPayment(deal, deal.baseAmount(), "PAY-1", "VA-USD-1");
        assertThat(t.getInstructedAmount()).isEqualByComparingTo("1000");
        assertThat(t.getInstructedCurrency()).isEqualTo("EUR");
        assertThat(t.getFxDealRef()).isEqualTo(deal.getDealRef());
    }

    @Test
    void aPriceShownForAnotherPaymentIsRefused() {
        UUID priceId = UUID.randomUUID();
        FxQuoteService.Command shown = new FxQuoteService.Command(CORP, ENTITY, "EUR/USD", Side.BUY, Instrument.SPOT, bd("999"),
                null, null, null, null);
        Price p = new Price(bd("1.1"), bd("0"), bd("1.1"), bd("3"), bd("1.1003"), bd("1099"), LocalDate.now());
        when(stream.take(priceId)).thenReturn(new FxStreamService.Issued(shown, p, p, Instant.now().plusSeconds(5)));

        assertThatThrownBy(() -> payments.convert(usdAccount(), "EUR", bd("1000"), price(priceId)))
                .hasMessageContaining("different payment");
    }

    @Test
    void aWindowDrawsThePaymentOnlyInsideItsWindowAndAForwardOnlyOnItsDate() {
        FxDeal window = DealFixtures.deal(Side.BUY, Instrument.WINDOW, DealStatus.ACTIVE, LocalDate.now().plusDays(20));
        window.setWindowStart(LocalDate.now().minusDays(5));
        when(deals.findById(window.getId())).thenReturn(Optional.of(window));
        when(settlement.legsFor(window, bd("1000"))).thenReturn(new BigDecimal[] {bd("1000.00"), bd("1100.00")});

        FxPaymentService.Conversion cv = payments.convert(usdAccount(), "EUR", bd("1000"), new FxPaymentService.Instruction(null, null, window.getId(), false));
        assertThat(cv.debitAmount()).isEqualByComparingTo("1100.00");
        assertThat(cv.bookedForIt()).isFalse();

        FxDeal forward = DealFixtures.deal(Side.BUY, Instrument.FORWARD, DealStatus.ACTIVE, LocalDate.now().plusDays(3));
        when(deals.findById(forward.getId())).thenReturn(Optional.of(forward));
        assertThatThrownBy(() -> payments.convert(usdAccount(), "EUR", bd("1000"), new FxPaymentService.Instruction(null, null, forward.getId(), false)))
                .hasMessageContaining("can pay on its value date");

        FxDeal wrongWay = DealFixtures.deal(Side.SELL, Instrument.WINDOW, DealStatus.ACTIVE, LocalDate.now().plusDays(20));
        wrongWay.setWindowStart(LocalDate.now().minusDays(5));
        when(deals.findById(wrongWay.getId())).thenReturn(Optional.of(wrongWay));
        assertThatThrownBy(() -> payments.convert(usdAccount(), "EUR", bd("1000"), new FxPaymentService.Instruction(null, null, wrongWay.getId(), false)))
                .hasMessageContaining("buys USD and sells EUR");
    }

    @Test
    void aFailedPaymentUnwindsTheDealBookedForItButNotADealItWasToDrawOn() {
        FxDeal booked = DealFixtures.deal(Side.BUY, Instrument.SPOT, DealStatus.PENDING_SETTLEMENT, LocalDate.now());
        payments.abandon(new FxPaymentService.Conversion(bd("1000"), "EUR", bd("1100"), "USD", bd("1.1"), booked, bd("1000"), true), "no funds");
        verify(dealService).transition(eq(booked), eq(DealStatus.CANCELLED), argThat(s -> s.contains("no funds")));

        FxDeal window = DealFixtures.deal(Side.BUY, Instrument.WINDOW, DealStatus.ACTIVE, LocalDate.now().plusDays(20));
        payments.abandon(new FxPaymentService.Conversion(bd("1000"), "EUR", bd("1100"), "USD", bd("1.1"), window, bd("1000"), false), "no funds");
        verify(dealService, never()).transition(eq(window), any(), any());
        assertThat(List.of(booked, window)).hasSize(2);
    }
}
