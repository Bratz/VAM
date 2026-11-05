package com.bank.vam.fx.deal;

import com.bank.vam.entity.Corporate;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.fx.exposure.FxExposureEntry;
import com.bank.vam.repository.CorporateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FxDealServiceTest {

    private final FxDealRepository deals = mock(FxDealRepository.class);
    private final FxEvents log = mock(FxEvents.class);
    private final CorporateRepository corporates = mock(CorporateRepository.class);
    private final FxDealService service = new FxDealService(deals, mock(FxDealEventRepository.class), log, corporates);

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @BeforeEach
    void saveReturnsWhatItWasGiven() {
        when(deals.save(any(FxDeal.class))).thenAnswer(inv -> {
            FxDeal d = inv.getArgument(0);
            if (d.getId() == null) {
                d.setId(UUID.randomUUID());
            }
            return d;
        });
    }

    private static FxQuoteRequest quote(Side side, Instrument instrument) {
        FxQuoteRequest q = new FxQuoteRequest();
        q.setId(UUID.randomUUID());
        q.setCorporateId(UUID.randomUUID());
        q.setPair("EUR/USD");
        q.setSide(side);
        q.setInstrument(instrument);
        q.setAmount(bd("100000.00"));
        q.setValueDate(LocalDate.of(2027, 2, 9));
        q.setWindowStart(instrument == Instrument.WINDOW ? LocalDate.of(2026, 12, 9) : null);
        q.setMidRate(bd("1.09"));
        q.setForwardPoints(bd("20.00"));
        q.setSpreadBps(bd("3.00"));
        q.setClientRate(bd("1.10"));
        q.setCounterAmount(bd("110000.00"));
        return q;
    }

    @Test
    void aBoughtForwardHasTheBaseCurrencyOnTheBuyLegAndEndsActive() {
        FxDeal d = service.book(quote(Side.BUY, Instrument.FORWARD), bd("6000.00"), "AED");

        assertThat(d.getBuyCurrency()).isEqualTo("EUR");
        assertThat(d.getBuyAmount()).isEqualByComparingTo("100000");
        assertThat(d.getSellCurrency()).isEqualTo("USD");
        assertThat(d.getSellAmount()).isEqualByComparingTo("110000");
        assertThat(d.getStatus()).isEqualTo(DealStatus.ACTIVE);
        assertThat(d.getConfirmedAt()).isNotNull();
        assertThat(d.getDealRef()).startsWith("FXD-");
        verify(log).deal(any(), isNull(), eq(DealStatus.BOOKED), anyString());
        verify(log).deal(any(), eq(DealStatus.BOOKED), eq(DealStatus.CONFIRMED), anyString());
        verify(log).deal(any(), eq(DealStatus.CONFIRMED), eq(DealStatus.ACTIVE), anyString());
    }

    @Test
    void aSoldSpotSwapsTheLegsAndWaitsForSettlement() {
        FxDeal d = service.book(quote(Side.SELL, Instrument.SPOT), bd("100.00"), "AED");

        assertThat(d.getSellCurrency()).isEqualTo("EUR");
        assertThat(d.getSellAmount()).isEqualByComparingTo("100000");
        assertThat(d.getBuyCurrency()).isEqualTo("USD");
        assertThat(d.getBuyAmount()).isEqualByComparingTo("110000");
        assertThat(d.getStatus()).isEqualTo(DealStatus.PENDING_SETTLEMENT);
    }

    @Test
    void aDealCannotSkipOrReverseStates() {
        FxDeal d = service.book(quote(Side.BUY, Instrument.FORWARD), bd("6000.00"), "AED");

        assertThatThrownBy(() -> service.transition(d, DealStatus.CONFIRMED, "undo"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("ACTIVE to CONFIRMED");

        FxDeal fresh = new FxDeal();
        fresh.setStatus(DealStatus.BOOKED);
        assertThatThrownBy(() -> service.transition(fresh, DealStatus.ACTIVE, "skip"))
                .isInstanceOf(BusinessException.class);
        assertThat(fresh.getStatus()).isEqualTo(DealStatus.BOOKED);
    }

    @Test
    void anOpenDealIsAHedgePositionInEachCurrencyDatedByItsValueDate() {
        FxDeal d = service.book(quote(Side.BUY, Instrument.FORWARD), bd("6000.00"), "AED");

        List<FxExposureEntry> positions = d.hedgePositions();

        assertThat(positions).extracting(FxExposureEntry::getKind).containsOnly(FxExposureEntry.Kind.HEDGE);
        assertThat(positions).extracting(FxExposureEntry::getCurrency).containsExactly("EUR", "USD");
        assertThat(positions.get(0).getAmount()).isEqualByComparingTo("100000");
        assertThat(positions.get(1).getAmount()).isEqualByComparingTo("-110000");
        assertThat(positions).extracting(FxExposureEntry::getValueDate).containsOnly(LocalDate.of(2027, 2, 9));
    }

    @Test
    void theConfirmationStatesTheDealAndNeverClaimsToBeReal() {
        Corporate c = new Corporate();
        c.setLegalName("Test Multinational Corp");
        when(corporates.findById(any())).thenReturn(Optional.of(c));
        FxDeal d = service.book(quote(Side.BUY, Instrument.WINDOW), bd("6000.00"), "AED");

        String text = service.confirmation(d);

        assertThat(text).contains("SIMULATED").contains(d.getDealRef()).contains("Test Multinational Corp")
                .contains("You buy        : EUR 100,000.00").contains("You sell       : USD 110,000.00")
                .contains("Window forward, drawable 2026-12-09 to 2027-02-09").contains("bank margin 3.00 bps")
                .contains("forward points 20.00");
    }

    @Test
    void nothingIsWrittenWhenATransitionIsRefused() {
        FxDeal d = new FxDeal();
        d.setStatus(DealStatus.ACTIVE);

        assertThatThrownBy(() -> service.transition(d, DealStatus.BOOKED, "x")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(log);
    }
}
