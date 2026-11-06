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
    private final FxSettlementAccounts settlementAccounts = mock(FxSettlementAccounts.class);
    private final FxDealService service = new FxDealService(deals, mock(FxDealEventRepository.class), log, corporates, mock(FxLimitService.class),
            settlementAccounts);

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
        assertThat(d.getDealRef()).startsWith("FXD-");
        // The bank issues the confirmation; the corporate has not affirmed it yet.
        assertThat(d.getConfirmationStatus()).isEqualTo(ConfirmationStatus.ISSUED);
        assertThat(d.getConfirmationIssuedAt()).isNotNull();
        assertThat(d.getConfirmedAt()).isNull();
        verify(log).deal(any(), isNull(), eq(DealStatus.BOOKED), anyString());
        verify(log).note(any(), eq("CONFIRMATION_SENT"), anyString());
        verify(log).deal(any(), eq(DealStatus.BOOKED), eq(DealStatus.ACTIVE), anyString());
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

        assertThatThrownBy(() -> service.transition(d, DealStatus.BOOKED, "undo"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("ACTIVE to BOOKED");

        FxDeal fresh = new FxDeal();
        fresh.setStatus(DealStatus.BOOKED);
        assertThatThrownBy(() -> service.transition(fresh, DealStatus.SETTLED, "skip"))
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
    void aPartDrawnWindowOnlyHedgesWhatIsStillUndrawn() {
        FxDeal d = service.book(quote(Side.BUY, Instrument.WINDOW), bd("6000.00"), "AED");
        d.setDrawnAmount(bd("40000"));

        List<FxExposureEntry> positions = d.hedgePositions();

        // 60% of the EUR 100,000 / USD 110,000 deal is left.
        assertThat(positions.get(0).getAmount()).isEqualByComparingTo("60000");
        assertThat(positions.get(1).getAmount()).isEqualByComparingTo("-66000");

        d.setDrawnAmount(bd("100000"));
        assertThat(d.hedgePositions()).isEmpty();
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
                .contains("Window forward, drawable 2026-12-09 to 2027-02-09").contains("Status         : ACTIVE")
                .contains("confirm or dispute");
    }

    /**
     * A corporate sees the all-in rate it dealt at, never how the bank built it. The mid, the forward points and the
     * margin stay on the stored rows for the bank's own use, so they must not be in the confirmation the corporate
     * reads and downloads, nor in any shape the API sends to the corporate (the MCP tools send the same shapes).
     */
    @Test
    void nothingTheBankKeepsToItselfReachesTheCorporate() {
        Corporate c = new Corporate();
        c.setLegalName("Test Multinational Corp");
        when(corporates.findById(any())).thenReturn(Optional.of(c));
        FxDeal d = service.book(quote(Side.BUY, Instrument.WINDOW), bd("6000.00"), "AED");

        String text = service.confirmation(d).toLowerCase();

        assertThat(text).doesNotContain("margin").doesNotContain("mid").doesNotContain("bps").doesNotContain("build-up");
        // What the corporate does see for a forward: a spot that already carries the margin, and the points to the all-in rate.
        FxDealModels.DealView view = FxDealModels.DealView.of(d, Optional.empty(), false);
        assertThat(view.spotRate()).isNotNull().isNotEqualByComparingTo(d.getMidRate());
        assertThat(view.spotRate().add(view.forwardPoints().multiply(new BigDecimal("0.0001"))))
                .isCloseTo(d.getRate(), org.assertj.core.data.Offset.offset(new BigDecimal("0.000001")));
        assertThat(text).contains("spot rate      : " + view.spotRate().toPlainString())
                .contains("forward points : +" + view.forwardPoints().toPlainString());
        java.util.List<Class<?>> corporateShapes = java.util.List.of(FxDealModels.QuoteView.class, FxDealModels.DealView.class,
                FxLifecycleService.CloseOutQuote.class, FxLifecycleService.RollQuote.class);
        for (Class<?> shape : corporateShapes) {
            assertThat(java.util.Arrays.stream(shape.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName))
                    .as(shape.getSimpleName()).noneMatch(n -> n.matches("(mid|.*Mid|spread|.*Spread|margin|.*Margin|floor|.*Floor)([A-Z].*)?"));
        }
    }

    @Test
    void nothingIsWrittenWhenATransitionIsRefused() {
        FxDeal d = new FxDeal();
        d.setStatus(DealStatus.ACTIVE);

        assertThatThrownBy(() -> service.transition(d, DealStatus.BOOKED, "x")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(log);
    }

    @Test
    void settlementAccountsChangeUntilTheDayBeforeValueDateAndThenOnlyWhileSettlementHasFailed() {
        FxDeal d = DealFixtures.deal(Side.BUY, Instrument.FORWARD, DealStatus.ACTIVE, LocalDate.now().plusDays(1));
        org.mockito.Mockito.when(deals.findById(d.getId())).thenReturn(Optional.of(d));
        UUID other = UUID.randomUUID();
        org.mockito.Mockito.when(settlementAccounts.require(DealFixtures.CORP, DealFixtures.ENTITY, "USD", other))
                .thenReturn(new FxSettlementAccounts.Account(other, "USD", "USD other (Home Bank)", "Home Bank", bd("0")));

        service.changeAccounts(d.getId(), null, other);

        assertThat(d.getSellAccountId()).isEqualTo(other);
        assertThat(d.getSellAccountLabel()).isEqualTo("USD other (Home Bank)");
        assertThat(d.getBuyAccountId()).isEqualTo(DealFixtures.EUR_ACCOUNT);
        verify(log).note(eq(d), eq("SETTLE_ACCOUNTS"), org.mockito.ArgumentMatchers.contains("USD debit account USD account to USD other"));

        d.setValueDate(LocalDate.now());
        assertThatThrownBy(() -> service.changeAccounts(d.getId(), null, DealFixtures.USD_ACCOUNT))
                .isInstanceOf(BusinessException.class).hasMessageContaining("can no longer be changed");

        d.setStatus(DealStatus.SETTLEMENT_FAILED);
        org.mockito.Mockito.when(settlementAccounts.require(DealFixtures.CORP, DealFixtures.ENTITY, "USD", DealFixtures.USD_ACCOUNT))
                .thenReturn(new FxSettlementAccounts.Account(DealFixtures.USD_ACCOUNT, "USD", "USD account", "Home Bank", bd("0")));
        service.changeAccounts(d.getId(), null, DealFixtures.USD_ACCOUNT);
        assertThat(d.getSellAccountId()).isEqualTo(DealFixtures.USD_ACCOUNT);
    }
}
