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
    private final FxPaymentService payments = new FxPaymentService(stream, quotes, confirmations, settlement, deals);

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
        // bulk rows and files send "at market" for every row; one in the account's currency just pays
        assertThat(payments.convert(usdAccount(), "USD", bd("100"), FxPaymentService.Instruction.AT_MARKET)).isNull();
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
        verify(confirmations).affirm(deal.getId());

        Transaction t = new Transaction();
        t.setReferenceNumber("PAY-1");
        payments.settle(cv, t, usdAccount());
        verify(settlement).paidByPayment(deal, deal.baseAmount(), "PAY-1", null, "VA-USD-1");
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

        FxPaymentService.Conversion cv = payments.convert(usdAccount(), "EUR", bd("1000"), FxPaymentService.Instruction.of(null, null, window.getId(), null, false));
        assertThat(cv.debitAmount()).isEqualByComparingTo("1100.00");

        FxDeal overdue = DealFixtures.deal(Side.BUY, Instrument.FORWARD, DealStatus.ACTIVE, LocalDate.now().minusDays(1));
        when(deals.findById(overdue.getId())).thenReturn(Optional.of(overdue));
        assertThatThrownBy(() -> payments.convert(usdAccount(), "EUR", bd("1000"), FxPaymentService.Instruction.of(null, null, overdue.getId(), null, false)))
                .hasMessageContaining("is settling through its settlement accounts");

        FxDeal wrongWay = DealFixtures.deal(Side.SELL, Instrument.WINDOW, DealStatus.ACTIVE, LocalDate.now().plusDays(20));
        wrongWay.setWindowStart(LocalDate.now().minusDays(5));
        when(deals.findById(wrongWay.getId())).thenReturn(Optional.of(wrongWay));
        assertThatThrownBy(() -> payments.convert(usdAccount(), "EUR", bd("1000"), FxPaymentService.Instruction.of(null, null, wrongWay.getId(), null, false)))
                .hasMessageContaining("buys USD and sells EUR");
    }

    @Test
    void thePreviewPricesTheDebitLiveAndBooksNothing() {
        FxStreamService.Leg buy = new FxStreamService.Leg(bd("1.1003"), null, null, bd("1100.30"));
        when(stream.price(argThat(c -> c.pair().equals("EUR/USD") && c.forPayment()))).thenReturn(new FxStreamService.StreamPrice(true, null,
                "EUR/USD", bd("1000"), Instrument.SPOT, LocalDate.now(), UUID.randomUUID(), buy, buy, 5, bd("1000000"), 31));

        FxPaymentService.Conversion cv = payments.preview(usdAccount(), "EUR", bd("1000"), null);

        assertThat(cv.debitAmount()).isEqualByComparingTo("1100.30");
        assertThat(cv.rate()).isEqualByComparingTo("1.1003");
        assertThat(cv.deal()).isNull();
        verify(stream, never()).take(any());
        verify(quotes, never()).request(any(), any());
        assertThat(payments.preview(usdAccount(), "USD", bd("1000"), null)).isNull();
    }

    @Test
    void aPreviewAboveTheStreamingLimitSaysToAskForAPrice() {
        when(stream.price(any())).thenReturn(new FxStreamService.StreamPrice(false, "Above 1,000,000 EUR in EUR/USD the desk prices on request",
                "EUR/USD", bd("2000000"), Instrument.SPOT, null, null, null, null, 0, bd("1000000"), 31));

        assertThatThrownBy(() -> payments.preview(usdAccount(), "EUR", bd("2000000"), null))
                .hasMessage("Above 1,000,000 EUR in EUR/USD the desk prices on request: ask for a price for this payment");
    }

    // ---- deal-first FX: the deals that fit, combined, then a rate for the rest ------------------------------------

    private static FxDeal small(Instrument instrument, LocalDate valueDate, String eur) {
        FxDeal d = DealFixtures.deal(Side.BUY, instrument, instrument == Instrument.SPOT ? DealStatus.PENDING_SETTLEMENT : DealStatus.ACTIVE, valueDate);
        d.setBuyAmount(bd(eur));
        d.setSellAmount(bd(eur).multiply(bd("1.10")).setScale(2));
        if (instrument == Instrument.WINDOW) {
            d.setWindowStart(LocalDate.now().minusDays(10));
        }
        return d;
    }

    /** A forward due in 5 days (EUR 300), a window open today (EUR 600), and two that do not fit. */
    private List<FxDeal> book() {
        FxDeal forward = small(Instrument.FORWARD, LocalDate.now().plusDays(5), "300.00");
        FxDeal window = small(Instrument.WINDOW, LocalDate.now().plusDays(20), "600.00");
        FxDeal otherEntity = small(Instrument.FORWARD, LocalDate.now().plusDays(1), "900.00");
        otherEntity.setEntityId(UUID.randomUUID());
        FxDeal unconfirmed = small(Instrument.SPOT, LocalDate.now().plusDays(2), "900.00");
        unconfirmed.setConfirmationStatus(ConfirmationStatus.ISSUED);
        List<FxDeal> all = List.of(window, otherEntity, forward, unconfirmed);
        when(deals.findByCorporateIdAndStatusIn(eq(CORP), any())).thenReturn(all);
        all.forEach(d -> when(deals.findById(d.getId())).thenReturn(Optional.of(d)));
        when(settlement.legsFor(any(), any())).thenAnswer(inv -> {
            BigDecimal base = inv.getArgument(1);
            return new BigDecimal[] {base, base.multiply(bd("1.10")).setScale(2)};
        });
        return List.of(forward, window);
    }

    @Test
    void thePlanUsesTheEntitysConfirmedDealsSoonestValueDateFirstUntilThePaymentIsCovered() {
        List<FxDeal> fit = book();

        FxPaymentService.Plan plan = payments.plan(usdAccount(), "EUR", bd("1000"), null);

        assertThat(plan.deals()).extracting(FxPaymentService.Fit::dealId).containsExactly(fit.get(0).getId(), fit.get(1).getId());
        assertThat(plan.deals()).extracting(FxPaymentService.Fit::covers).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(bd("300"), bd("600"));
        assertThat(plan.rest()).isEqualByComparingTo("100");

        FxPaymentService.Plan unticked = payments.plan(usdAccount(), "EUR", bd("1000"), List.of(fit.get(1).getId()));
        assertThat(unticked.deals().get(0).covers()).isEqualByComparingTo("0");
        assertThat(unticked.rest()).isEqualByComparingTo("400");
    }

    @Test
    void severalDealsAndALivePriceForTheRestMakeOnePaymentAtTheBlendedRate() {
        List<FxDeal> fit = book();
        UUID priceId = UUID.randomUUID();
        FxQuoteService.Command shown = new FxQuoteService.Command(CORP, ENTITY, "EUR/USD", Side.BUY, Instrument.SPOT, bd("100"),
                null, null, null, null, true);
        Price buy = new Price(bd("1.1"), bd("0"), bd("1.1"), bd("3"), bd("1.1003"), bd("110.03"), LocalDate.now());
        when(stream.take(priceId)).thenReturn(new FxStreamService.Issued(shown, buy, buy, Instant.now().plusSeconds(5)));
        FxQuoteRequest q = new FxQuoteRequest();
        q.setId(UUID.randomUUID());
        q.setStatus(QuoteStatus.QUOTED);
        when(quotes.request(any(), eq(buy))).thenReturn(q);
        FxDeal booked = small(Instrument.SPOT, LocalDate.now().plusDays(2), "100.00");
        booked.setSellAmount(bd("110.03"));
        booked.setRate(bd("1.1003"));
        when(quotes.accept(q.getId(), true)).thenReturn(booked);

        FxPaymentService.Conversion cv = payments.convert(usdAccount(), "EUR", bd("1000"), new FxPaymentService.Instruction(priceId, null,
                List.of(new FxPaymentService.Draw(fit.get(0).getId(), bd("300")), new FxPaymentService.Draw(fit.get(1).getId(), bd("600"))), false));

        assertThat(cv.parts()).hasSize(3);
        assertThat(cv.debitAmount()).isEqualByComparingTo("1100.03");
        assertThat(cv.rate()).isEqualByComparingTo("1.10003");
        assertThat(cv.dealRefs()).isEqualTo(fit.get(0).getDealRef() + " +2");

        Transaction t = new Transaction();
        t.setReferenceNumber("PAY-2");
        payments.settle(cv, t, usdAccount());
        verify(settlement).paidByPayment(fit.get(0), bd("300"), "PAY-2", null, "VA-USD-1");
        verify(settlement).paidByPayment(booked, booked.baseAmount(), "PAY-2", null, "VA-USD-1");
    }

    @Test
    void anUnattendedPaymentTakesTheDealsThatFitBeforeTheMarket() {
        book();

        FxPaymentService.Conversion cv = payments.convert(usdAccount(), "EUR", bd("800"), FxPaymentService.Instruction.AT_MARKET);

        assertThat(cv.parts()).extracting(FxPaymentService.Part::paid).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(bd("300"), bd("500"));
        verify(stream, never()).price(any());
    }

    @Test
    void dealsThatDoNotCoverThePaymentNeedARateForTheRest() {
        List<FxDeal> fit = book();

        assertThatThrownBy(() -> payments.convert(usdAccount(), "EUR", bd("1000"),
                FxPaymentService.Instruction.of(null, null, fit.get(0).getId(), null, false)))
                .hasMessageContaining("lock a rate for the remaining 700");
    }

    @Test
    void aBatchCoversItsTotalWithTheDealsThenOneLiveDealAndLinesDrawInOrder() {
        List<FxDeal> fit = book();
        when(deals.findByCorporateIdAndStatusIn(eq(CORP), any())).thenReturn(List.of(fit.get(0)));
        UUID priceId = UUID.randomUUID();
        FxStreamService.Leg leg = new FxStreamService.Leg(bd("1.1003"), null, null, bd("220.06"));
        when(stream.price(argThat(c -> c.amount().compareTo(bd("200")) == 0))).thenReturn(new FxStreamService.StreamPrice(true, null, "EUR/USD",
                bd("200"), Instrument.SPOT, LocalDate.now(), priceId, leg, leg, 5, bd("1000000"), 31));
        Price buy = new Price(bd("1.1"), bd("0"), bd("1.1"), bd("3"), bd("1.1003"), bd("220.06"), LocalDate.now());
        when(stream.take(priceId)).thenReturn(new FxStreamService.Issued(null, buy, buy, Instant.now().plusSeconds(5)));
        FxQuoteRequest q = new FxQuoteRequest();
        q.setId(UUID.randomUUID());
        q.setStatus(QuoteStatus.QUOTED);
        when(quotes.request(argThat(c -> c.amount().compareTo(bd("200")) == 0 && c.forPayment()), eq(buy))).thenReturn(q);
        FxDeal booked = small(Instrument.SPOT, LocalDate.now().plusDays(2), "200.00");
        when(quotes.accept(q.getId(), true)).thenReturn(booked);

        List<FxPaymentService.BatchFx> out = payments.prepareBatch(List.of(
                new FxPaymentService.BatchRow(usdAccount(), "EUR", bd("200")),
                new FxPaymentService.BatchRow(usdAccount(), "EUR", bd("300")),
                new FxPaymentService.BatchRow(usdAccount(), "USD", bd("50"))));

        assertThat(out.get(0).instruction().draws()).containsExactly(new FxPaymentService.Draw(fit.get(0).getId(), bd("200")));
        assertThat(out.get(1).instruction().draws()).containsExactly(new FxPaymentService.Draw(fit.get(0).getId(), bd("100.00")),
                new FxPaymentService.Draw(booked.getId(), bd("200.00")));
        assertThat(out.get(2).instruction()).isEqualTo(FxPaymentService.Instruction.AT_MARKET);
        verify(quotes).accept(q.getId(), true);
    }

    @Test
    void aBatchRemainderAboveTheStreamingLimitHoldsTheLinesTheDealsCannotCover() {
        List<FxDeal> fit = book();
        when(deals.findByCorporateIdAndStatusIn(eq(CORP), any())).thenReturn(List.of(fit.get(0)));
        when(stream.price(any())).thenReturn(new FxStreamService.StreamPrice(false, "Above 150 EUR in EUR/USD the desk prices on request",
                "EUR/USD", bd("200"), Instrument.SPOT, null, null, null, null, 0, bd("150"), 31));

        List<FxPaymentService.BatchFx> out = payments.prepareBatch(List.of(
                new FxPaymentService.BatchRow(usdAccount(), "EUR", bd("200")),
                new FxPaymentService.BatchRow(usdAccount(), "EUR", bd("300"))));

        assertThat(out.get(0).instruction().draws()).hasSize(1);
        assertThat(out.get(1).instruction()).isNull();
        assertThat(out.get(1).held()).startsWith("Waiting for FX: after the deals that fit, EUR 200.00 of this batch is left to convert, and above 150 EUR");
        verify(quotes, never()).accept(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    /** A EUR collection for a USD account: the deal selling EUR for USD first, the rest sold at the live price. */
    @Test
    void aCollectionInAnotherCurrencyIsSoldThroughDealsFirstThenAtTheLivePrice() {
        FxDeal sells = DealFixtures.deal(Side.SELL, Instrument.FORWARD, DealStatus.ACTIVE, LocalDate.now().plusDays(3));
        FxDeal buys = DealFixtures.deal(Side.BUY, Instrument.FORWARD, DealStatus.ACTIVE, LocalDate.now().plusDays(1));
        when(deals.findByCorporateIdAndStatusIn(eq(CORP), any())).thenReturn(List.of(buys, sells));
        when(settlement.legsFor(sells, bd("100000.00"))).thenReturn(new BigDecimal[] {bd("110000.00"), bd("100000.00")});

        UUID priceId = UUID.randomUUID();
        FxStreamService.Leg leg = new FxStreamService.Leg(bd("1.0990"), null, null, bd("21980.00"));
        when(stream.price(argThat(c -> c.side() == Side.SELL && c.pair().equals("EUR/USD") && c.forPayment() && c.amount().compareTo(bd("20000")) == 0)))
                .thenReturn(new FxStreamService.StreamPrice(true, null, "EUR/USD", bd("20000"), Instrument.SPOT, LocalDate.now(), priceId,
                        leg, leg, 5, bd("1000000"), 31));
        Price sellPrice = new Price(bd("1.1"), bd("0"), bd("1.1"), bd("3"), bd("1.0990"), bd("21980.00"), LocalDate.now());
        when(stream.take(priceId)).thenReturn(new FxStreamService.Issued(null, sellPrice, sellPrice, Instant.now().plusSeconds(5)));
        FxQuoteRequest q = new FxQuoteRequest();
        q.setId(UUID.randomUUID());
        q.setStatus(QuoteStatus.QUOTED);
        when(quotes.request(argThat(c -> c.side() == Side.SELL), eq(sellPrice))).thenReturn(q);
        FxDeal spot = DealFixtures.deal(Side.SELL, Instrument.SPOT, DealStatus.PENDING_SETTLEMENT, LocalDate.now());
        spot.setSellAmount(bd("20000.00"));
        spot.setBuyAmount(bd("21980.00"));
        when(quotes.accept(q.getId(), true)).thenReturn(spot);

        FxPaymentService.Conversion cv = payments.convertIncoming(usdAccount(), "EUR", bd("120000"));

        assertThat(cv.parts()).extracting(FxPaymentService.Part::deal).containsExactly(sells, spot); // not the deal buying EUR
        assertThat(cv.debitAmount()).isEqualByComparingTo("131980.00"); // credited to the USD account
        assertThat(cv.debitCurrency()).isEqualTo("USD");
        verify(confirmations).affirm(spot.getId());

        Transaction credit = new Transaction();
        credit.setReferenceNumber("COL-1");
        payments.settleIncoming(cv, credit, usdAccount());
        verify(settlement).receivedByCollection(sells, bd("100000.00"), "COL-1", null, "VA-USD-1");
        assertThat(credit.getInstructedCurrency()).isEqualTo("EUR");
        assertThat(payments.convertIncoming(usdAccount(), "USD", bd("5"))).isNull();
    }

    // ---- ihb-fx 3a: above the live-price limit, treasury negotiates a firm price (D7, D8) ---------------------------

    private void notStreamed() {
        when(stream.price(any())).thenReturn(new FxStreamService.StreamPrice(false, "Above 250,000 EUR in EUR/USD the desk prices on request",
                "EUR/USD", bd("300000"), Instrument.SPOT, null, null, null, null, 0, bd("250000"), 0));
    }

    /** A firm quote for payment, priced and not yet accepted, and the deal accepting it books. */
    private FxDeal firmQuote(Side side, String eur, String usd, QuoteStatus status) {
        FxQuoteRequest q = new FxQuoteRequest();
        q.setId(UUID.randomUUID());
        q.setCorporateId(CORP);
        q.setEntityId(ENTITY);
        q.setForPayment(true);
        q.setPair("EUR/USD");
        q.setSide(side);
        q.setAmount(bd(eur));
        q.setStatus(status);
        when(quotes.get(q.getId())).thenReturn(q);
        FxDeal deal = DealFixtures.deal(side, Instrument.SPOT, DealStatus.PENDING_SETTLEMENT, LocalDate.now());
        deal.setQuoteRequestId(q.getId());
        deal.setBuyAmount(bd(side == Side.BUY ? eur : usd));
        deal.setSellAmount(bd(side == Side.BUY ? usd : eur));
        deal.setDrawnAmount(BigDecimal.ZERO);
        when(quotes.accept(q.getId(), true)).thenReturn(deal);
        return deal;
    }

    @Test
    void whatTheDealsLeaveAboveTheStreamingLimitNeedsAQuoteAndAskingBooksNothing() {
        book(); // EUR 900 of deals buying EUR
        notStreamed();

        assertThat(payments.needsQuote(usdAccount(), "EUR", bd("300000"), Side.BUY)).isEqualTo("Above 250,000 EUR in EUR/USD the desk prices on request");
        verify(stream).price(argThat(c -> c.amount().compareTo(bd("299100")) == 0 && c.forPayment()));
        assertThat(payments.needsQuote(usdAccount(), "EUR", bd("900"), Side.BUY)).as("the deals cover it").isNull();
        assertThat(payments.needsQuote(usdAccount(), "USD", bd("300000"), Side.BUY)).as("nothing to convert").isNull();
        verify(quotes, never()).request(any(), any());
        verify(quotes, never()).accept(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void aCollectionAboveTheStreamingLimitNeedsAQuoteUnlessTheFxLineRefusesAnyDeal() {
        notStreamed();
        assertThat(payments.needsQuote(usdAccount(), "EUR", bd("300000"), Side.SELL)).startsWith("Above 250,000 EUR");
        verify(stream).price(argThat(c -> c.side() == Side.SELL && c.pair().equals("EUR/USD")));

        // D6: no sub-limit -- negotiating cannot help, so it is refused, not held
        when(quotes.lineRefusal(any())).thenReturn("No FX sub-limit is allocated to Fixture LLC");
        assertThatThrownBy(() -> payments.needsQuote(usdAccount(), "EUR", bd("300000"), Side.SELL))
                .isInstanceOf(BusinessException.class).hasMessage("No FX sub-limit is allocated to Fixture LLC");
    }

    @Test
    void aPaymentOnItsNegotiatedQuoteIsConvertedWhollyAtItAndTheDealIsNoOtherPaymentsToDraw() {
        book(); // deals that would fit an at-market payment
        FxDeal quoted = firmQuote(Side.BUY, "300000.00", "330300.00", QuoteStatus.QUOTED);
        UUID quoteId = quoted.getQuoteRequestId();

        FxPaymentService.Conversion cv = payments.convert(usdAccount(), "EUR", bd("300000.00"),
                FxPaymentService.Instruction.of(null, quoteId, null, null, null));

        assertThat(cv.parts()).singleElement().satisfies(p -> {
            assertThat(p.source()).isEqualTo(FxPaymentService.Source.FIRM);
            assertThat(p.deal()).isSameAs(quoted);
            assertThat(p.base()).as("the whole deal").isEqualByComparingTo(quoted.baseAmount());
        });
        assertThat(cv.debitAmount()).isEqualByComparingTo("330300.00");
        verify(settlement, never()).legsFor(any(), any()); // no other deal drawn

        // Settled by this payment in full, it is gone from what fits another payment in the pair.
        quoted.setDrawnAmount(quoted.baseAmount());
        when(deals.findByCorporateIdAndStatusIn(eq(CORP), any())).thenReturn(List.of(quoted));
        assertThat(payments.plan(usdAccount(), "EUR", bd("1000"), null).deals()).isEmpty();
    }

    @Test
    void anExpiredQuoteIsRefusedAndBooksNothing() {
        FxDeal quoted = firmQuote(Side.BUY, "300000.00", "330300.00", QuoteStatus.EXPIRED);

        assertThatThrownBy(() -> payments.convert(usdAccount(), "EUR", bd("300000.00"),
                FxPaymentService.Instruction.of(null, quoted.getQuoteRequestId(), null, null, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("no longer available (EXPIRED)");
        verify(quotes, never()).accept(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void aCollectionOnItsNegotiatedQuoteSellsTheWholeAmountAtItAndOnlyAQuoteForThatSale() {
        FxDeal quoted = firmQuote(Side.SELL, "300000.00", "329700.00", QuoteStatus.QUOTED);

        FxPaymentService.Conversion cv = payments.convertIncoming(usdAccount(), "EUR", bd("300000.00"), quoted.getQuoteRequestId());

        assertThat(cv.debitAmount()).as("credited to the USD account").isEqualByComparingTo("329700.00");
        assertThat(cv.parts()).singleElement().satisfies(p -> assertThat(p.deal()).isSameAs(quoted));
        verify(confirmations).affirm(quoted.getId());
        verify(deals, never()).findByCorporateIdAndStatusIn(any(), any());

        FxDeal buying = firmQuote(Side.BUY, "300000.00", "330300.00", QuoteStatus.QUOTED);
        assertThatThrownBy(() -> payments.convertIncoming(usdAccount(), "EUR", bd("300000.00"), buying.getQuoteRequestId()))
                .hasMessageContaining("ask for a new one selling EUR 300000.00");
        assertThatThrownBy(() -> payments.convertIncoming(usdAccount(), "EUR", bd("299999.00"), quoted.getQuoteRequestId()))
                .hasMessageContaining("different amount");
    }

    // ---- ihb-fx Phase 4: indicative amounts on Create Payable / Create Invoice, and X's IHB line ---------------------

    /** COBO preview: the deal selling EUR first, the rest at the live SELL price; nothing taken, booked or confirmed. */
    @Test
    void aCollectionPreviewSellsThroughTheDealsThenAtTheLiveSellPriceAndBooksNothing() {
        FxDeal sells = DealFixtures.deal(Side.SELL, Instrument.FORWARD, DealStatus.ACTIVE, LocalDate.now().plusDays(3));
        when(deals.findByCorporateIdAndStatusIn(eq(CORP), any())).thenReturn(List.of(sells));
        when(settlement.legsFor(sells, bd("100000.00"))).thenReturn(new BigDecimal[] {bd("110000.00"), bd("100000.00")});
        FxStreamService.Leg buy = new FxStreamService.Leg(bd("1.1010"), null, null, bd("22020.00"));
        FxStreamService.Leg sell = new FxStreamService.Leg(bd("1.0990"), null, null, bd("21980.00"));
        when(stream.price(argThat(c -> c.side() == Side.SELL && c.pair().equals("EUR/USD") && c.amount().compareTo(bd("20000")) == 0)))
                .thenReturn(new FxStreamService.StreamPrice(true, null, "EUR/USD", bd("20000"), Instrument.SPOT, LocalDate.now(),
                        UUID.randomUUID(), buy, sell, 5, bd("1000000"), 31));

        FxPaymentService.Conversion cv = payments.previewIncoming(usdAccount(), "EUR", bd("120000"));

        assertThat(cv.debitAmount()).isEqualByComparingTo("131980.00"); // the deal's leg + the SELL side, not the BUY
        assertThat(cv.parts()).extracting(FxPaymentService.Part::source).containsExactly(FxPaymentService.Source.DEAL, FxPaymentService.Source.LIVE);
        assertThat(cv.indicative()).isTrue();
        verify(stream, never()).take(any());
        verify(quotes, never()).request(any(), any());
        verify(settlement, never()).receivedByCollection(any(), any(), any(), any(), any());
        assertThat(payments.previewIncoming(usdAccount(), "USD", bd("5"))).isNull();
    }

    @Test
    void theIndicativeAmountSaysWhenTreasuryNegotiatesAndNeverRefusesForIt() {
        notStreamed();

        FxPaymentService.Indicative in = payments.indicative(usdAccount(), "EUR", bd("300000"), true);
        assertThat(in.settleCurrency()).isEqualTo("USD");
        assertThat(in.settleAmount()).isNull();
        assertThat(in.needsQuote()).startsWith("Above 250,000 EUR");
        assertThat(in.refusal()).isNull();
        assertThat(payments.indicative(usdAccount(), "EUR", bd("300000"), false).needsQuote()).startsWith("Above 250,000 EUR");

        // same currency: the amount itself, nothing to convert
        FxPaymentService.Indicative same = payments.indicative(usdAccount(), "USD", bd("10"), true);
        assertThat(same.settleAmount()).isEqualByComparingTo("10");
        assertThat(same.rate()).isNull();
    }

    @Test
    void aConvertedIhbLineShowsBothAmountsAndTheRate() {
        Transaction m = new Transaction();
        m.setInstructedAmount(bd("1000.00"));
        m.setInstructedCurrency("EUR");
        m.setFxRate(bd("4.01230000"));
        m.setAmount(bd("4012.30"));
        m.setCurrencyCode("AED");
        assertThat(FxPaymentService.line(m, "to Acme GmbH")).isEqualTo("EUR 1,000.00 to Acme GmbH · rate 4.0123 · AED 4,012.30");
        m.setFxRate(bd("4.012345678"));
        assertThat(FxPaymentService.line(m, "x")).contains("rate 4.012345678 ·");
    }
}
