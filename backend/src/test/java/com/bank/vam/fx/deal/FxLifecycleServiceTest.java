package com.bank.vam.fx.deal;

import com.bank.vam.exception.BusinessException;
import com.bank.vam.fx.ForwardPricer;
import com.bank.vam.fx.deal.SimulatedDealer.Price;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.bank.vam.fx.deal.DealFixtures.CORP;
import static com.bank.vam.fx.deal.DealFixtures.bd;
import static com.bank.vam.fx.deal.DealFixtures.deal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FxLifecycleServiceTest {

    private static final LocalDate SPOT = LocalDate.of(2026, 11, 9);
    private static final LocalDate DUE = LocalDate.of(2027, 2, 9);

    private final FxDealRepository deals = mock(FxDealRepository.class);
    private final FxSettlementRepository settlements = mock(FxSettlementRepository.class);
    private final SimulatedDealer dealer = mock(SimulatedDealer.class);
    private final ForwardPricer forwards = mock(ForwardPricer.class);
    private final FxDealService dealService = mock(FxDealService.class);
    private final FxQuoteService quoteService = mock(FxQuoteService.class);
    private final FxEvents log = mock(FxEvents.class);
    private final FxLifecycleService service =
            new FxLifecycleService(deals, settlements, dealer, forwards, dealService, quoteService, log);

    private final List<FxSettlement> saved = new ArrayList<>();

    @BeforeEach
    void wiring() {
        when(forwards.spotDate()).thenReturn(SPOT);
        doAnswer(inv -> {
            ((FxDeal) inv.getArgument(0)).setStatus(inv.getArgument(1));
            return null;
        }).when(dealService).transition(any(), any(), anyString());
        when(settlements.save(any(FxSettlement.class))).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
    }

    private FxDeal forward(Side side) {
        FxDeal d = deal(side, Instrument.FORWARD, DealStatus.ACTIVE, DUE);
        when(deals.findById(d.getId())).thenReturn(Optional.of(d));
        return d;
    }

    private void closeAt(String rate) {
        when(dealer.price(eq("EUR/USD"), any(), any(), any(), any(), any())).thenReturn(new Price(
                bd("1.10"), bd("0"), bd(rate), bd("3.00"), bd(rate), bd("0"), DUE));
    }

    @Test
    void closingABuyAboveItsRateIsAGainAndClosingASellAboveItsRateIsALoss() {
        assertThat(FxLifecycleService.realisedPnl(Side.BUY, bd("100000"), bd("1.10"), bd("1.12"))).isEqualByComparingTo("2000.00");
        assertThat(FxLifecycleService.realisedPnl(Side.SELL, bd("100000"), bd("1.10"), bd("1.12"))).isEqualByComparingTo("-2000.00");
        assertThat(FxLifecycleService.realisedPnl(Side.SELL, bd("100000"), bd("1.10"), bd("1.08"))).isEqualByComparingTo("2000.00");
    }

    @Test
    void theDeskTakesTheOtherSideWhenClosing() {
        closeAt("1.12");
        FxDeal d = forward(Side.BUY);

        FxLifecycleService.CloseOutQuote q = service.closeOutPreview(d.getId());

        verify(dealer).price("EUR/USD", Side.SELL, Instrument.FORWARD, bd("100000.00"), DUE, null);
        assertThat(q.realisedPnl()).isEqualByComparingTo("2000.00");
        assertThat(q.pnlCurrency()).isEqualTo("USD");
        assertThat(d.getStatus()).isEqualTo(DealStatus.ACTIVE);
    }

    @Test
    void aDealInItsLastDaysIsPricedAtSpotWhenClosed() {
        closeAt("1.12");
        FxDeal d = deal(Side.BUY, Instrument.FORWARD, DealStatus.ACTIVE, SPOT);
        when(deals.findById(d.getId())).thenReturn(Optional.of(d));

        service.closeOutPreview(d.getId());

        verify(dealer).price("EUR/USD", Side.SELL, Instrument.SPOT, bd("100000.00"), SPOT, null);
    }

    @Test
    void closingOutRecordsTheCashDifferenceOnTheRightLegAndEndsTheDeal() {
        closeAt("1.12");
        FxDeal d = forward(Side.BUY);

        service.closeOut(d.getId());

        assertThat(d.getStatus()).isEqualTo(DealStatus.CLOSED_OUT);
        assertThat(d.getCloseRate()).isEqualByComparingTo("1.12");
        assertThat(d.getRealisedPnl()).isEqualByComparingTo("2000.00");
        assertThat(d.remainingBase()).isEqualByComparingTo("0");
        assertThat(saved).hasSize(1);
        FxSettlement s = saved.get(0);
        assertThat(s.getKind()).isEqualTo(SettlementKind.CLOSE_OUT);
        assertThat(s.getBuyAmount()).isEqualByComparingTo("2000.00");
        assertThat(s.getSellAmount()).isEqualByComparingTo("0");
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.SETTLED);
    }

    @Test
    void aLossIsRecordedAsWhatTheCorporatePays() {
        closeAt("1.08");
        FxDeal d = forward(Side.BUY);

        service.closeOut(d.getId());

        assertThat(saved.get(0).getBuyAmount()).isEqualByComparingTo("0");
        assertThat(saved.get(0).getSellAmount()).isEqualByComparingTo("2000.00");
    }

    @Test
    void rollingClosesTheOldDealAndBooksAReplacementLinkedToIt() {
        closeAt("1.12");
        FxDeal old = forward(Side.BUY);
        FxQuoteRequest q = new FxQuoteRequest();
        q.setId(UUID.randomUUID());
        q.setStatus(QuoteStatus.QUOTED);
        FxDeal next = deal(Side.BUY, Instrument.FORWARD, DealStatus.ACTIVE, DUE.plusMonths(3));
        when(quoteService.request(any())).thenReturn(q);
        when(quoteService.accept(q.getId())).thenReturn(next);

        old.setEntityId(UUID.randomUUID());
        FxLifecycleService.RollResult r = service.roll(old.getId(), DUE.plusMonths(3));

        assertThat(old.getStatus()).isEqualTo(DealStatus.ROLLED);
        assertThat(r.replacement()).isSameAs(next);
        assertThat(next.getRolledFromId()).isEqualTo(old.getId());
        org.mockito.ArgumentCaptor<FxQuoteService.Command> cmd = org.mockito.ArgumentCaptor.forClass(FxQuoteService.Command.class);
        verify(quoteService).request(cmd.capture());
        assertThat(cmd.getValue().side()).isEqualTo(Side.BUY);
        assertThat(cmd.getValue().instrument()).isEqualTo(Instrument.FORWARD);
        assertThat(cmd.getValue().amount()).isEqualByComparingTo("100000");
        assertThat(cmd.getValue().valueDate()).isEqualTo(DUE.plusMonths(3));
        assertThat(cmd.getValue().purpose()).startsWith("Roll of FXD-");
        assertThat(cmd.getValue().entityId()).isEqualTo(old.getEntityId()); // the replacement is for the same entity
    }

    @Test
    void ifTheReplacementIsNotQuotedTheRollFailsLoudlyWithTheReason() {
        closeAt("1.12");
        FxDeal old = forward(Side.BUY);
        FxQuoteRequest declined = new FxQuoteRequest();
        declined.setStatus(QuoteStatus.REJECTED);
        declined.setStatusReason("credit limit would be exceeded");
        when(quoteService.request(any())).thenReturn(declined);

        assertThatThrownBy(() -> service.roll(old.getId(), DUE.plusMonths(3)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("credit limit would be exceeded");
        verify(quoteService, never()).accept(any());
    }

    @Test
    void closeOutIsRefusedForSpotDisputedSettledAndPartlyDrawnWithAPendingDrawdown() {
        FxDeal spot = deal(Side.BUY, Instrument.SPOT, DealStatus.PENDING_SETTLEMENT, SPOT);
        when(deals.findById(spot.getId())).thenReturn(Optional.of(spot));
        assertThatThrownBy(() -> service.closeOut(spot.getId())).hasMessageContaining("spot deal");

        FxDeal disputed = forward(Side.BUY);
        disputed.setConfirmationStatus(ConfirmationStatus.DISPUTED);
        assertThatThrownBy(() -> service.closeOut(disputed.getId())).hasMessageContaining("disputed");

        FxDeal settled = deal(Side.BUY, Instrument.FORWARD, DealStatus.SETTLED, DUE);
        when(deals.findById(settled.getId())).thenReturn(Optional.of(settled));
        assertThatThrownBy(() -> service.closeOut(settled.getId())).hasMessageContaining("SETTLED");

        FxDeal pending = forward(Side.BUY);
        when(settlements.existsByDealIdAndStatus(pending.getId(), SettlementStatus.PENDING)).thenReturn(true);
        assertThatThrownBy(() -> service.closeOut(pending.getId())).hasMessageContaining("drawdown is waiting");

        assertThat(CORP).isNotNull();
        assertThat(saved).isEmpty();
    }
}
