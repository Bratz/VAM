package com.bank.vam.fx.deal;

import com.bank.vam.entity.notification.Notification.NotificationType;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.fx.RateProvider;
import com.bank.vam.fx.SpotTick;
import com.bank.vam.service.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.bank.vam.fx.deal.DealFixtures.CORP;
import static com.bank.vam.fx.deal.DealFixtures.bd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FxNotifierTest {

    private final FxRateAlertRepository alerts = mock(FxRateAlertRepository.class);
    private final FxDealRepository deals = mock(FxDealRepository.class);
    private final RateProvider rates = mock(RateProvider.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final FxBoardService board = mock(FxBoardService.class);
    private final FxNotifier notifier = new FxNotifier(alerts, deals, rates, notifications, board);

    /** The client's buy and sell price for EUR/USD now (the market itself is only asked whether the pair exists). */
    private void price(String buy, String sell) {
        when(rates.spot("EUR/USD")).thenReturn(Optional.of(new SpotTick("EUR/USD", bd("1.1"), BigDecimal.ZERO, Instant.now(), "MOCK")));
        when(board.spot(eq("EUR/USD"), eq(Side.BUY), any())).thenReturn(bd(buy));
        when(board.spot(eq("EUR/USD"), eq(Side.SELL), any())).thenReturn(bd(sell));
    }

    @BeforeEach
    void wiring() {
        when(alerts.save(any(FxRateAlert.class))).thenAnswer(inv -> inv.getArgument(0));
        when(notifications.notifyOnce(any(), any(), any(), anyString(), anyString(), anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(true);
    }

    @Test
    void aBuyAlertFiresWhenTheClientsBuyPriceFallsToTheTargetAndOnlyOnce() {
        price("1.0960", "1.0940");
        FxRateAlert a = notifier.create(CORP, "eur/usd", Side.BUY, null, bd("1.0950"), null);
        assertThat(a.getDirection()).isEqualTo(FxRateAlert.Direction.BELOW);
        assertThat(a.getAmount()).isEqualByComparingTo("100000");
        assertThat(a.getRateAtCreation()).isEqualByComparingTo("1.0960");
        when(alerts.findByStatus(FxRateAlert.Status.ACTIVE)).thenReturn(List.of(a));

        price("1.0951", "1.0931");
        assertThat(notifier.checkAlerts()).isZero();

        price("1.0949", "1.0929");
        assertThat(notifier.checkAlerts()).isEqualTo(1);
        assertThat(a.getStatus()).isEqualTo(FxRateAlert.Status.TRIGGERED);
        assertThat(a.getTriggeredRate()).isEqualByComparingTo("1.0949");
        verify(notifications).notifyOnce(eq(CORP), eq(NotificationType.FX_RATE_ALERT), any(), eq("Rate alert: buy EUR at 1.0949"),
                eq("You can buy EUR in EUR/USD at 1.0949 (indicative), at or below your 1.095. Request a price to deal."),
                anyString(), eq(a.getId()), anyString(), eq("fx-market"), anyString());
    }

    @Test
    void aSellAlertWatchesTheSellPriceRising() {
        price("1.0960", "1.0940");
        FxRateAlert a = notifier.create(CORP, "EUR/USD", Side.SELL, bd("250000"), bd("1.0945"), null);
        assertThat(a.getDirection()).isEqualTo(FxRateAlert.Direction.ABOVE);
        when(alerts.findByStatus(FxRateAlert.Status.ACTIVE)).thenReturn(List.of(a));

        price("1.0966", "1.0946");
        assertThat(notifier.checkAlerts()).isEqualTo(1);
        verify(board, org.mockito.Mockito.atLeastOnce()).spot("EUR/USD", Side.SELL, bd("250000"));
    }

    @Test
    void anAlertAlreadyReachableOrForAnUnknownPairIsRefused() {
        price("1.0960", "1.0940");
        assertThatThrownBy(() -> notifier.create(CORP, "EUR/USD", Side.BUY, null, bd("1.0970"), null)).isInstanceOf(BusinessException.class)
                .hasMessage("You can buy EUR at 1.0960 now; set a rate below that");
        assertThatThrownBy(() -> notifier.create(CORP, "EUR/USD", Side.SELL, null, bd("1.0930"), null))
                .hasMessage("You can sell EUR at 1.0940 now; set a rate above that");
        when(rates.spot("XXX/YYY")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> notifier.create(CORP, "XXX/YYY", Side.BUY, null, bd("1"), null)).hasMessageContaining("no market rate");
    }

    @Test
    void aDealSettlingWithinTwoDaysAndAnUnconfirmedDealAreReminded() {
        LocalDate today = LocalDate.of(2026, 11, 6);
        FxDeal soon = DealFixtures.deal(Side.BUY, Instrument.FORWARD, DealStatus.ACTIVE, today.plusDays(2));
        FxDeal later = DealFixtures.deal(Side.BUY, Instrument.FORWARD, DealStatus.ACTIVE, today.plusDays(3));
        later.setConfirmationStatus(ConfirmationStatus.ISSUED);
        when(deals.findByStatusIn(DealStatus.OPEN)).thenReturn(List.of(soon, later));

        assertThat(notifier.remindDeals(today)).isEqualTo(2);
        verify(notifications).notifyOnce(eq(CORP), eq(NotificationType.FX_MATURITY_REMINDER), any(), anyString(), anyString(),
                anyString(), eq(soon.getId()), anyString(), anyString(), anyString());
        verify(notifications).notifyOnce(eq(CORP), eq(NotificationType.FX_CONFIRMATION_PENDING), any(), anyString(), anyString(),
                anyString(), eq(later.getId()), anyString(), anyString(), anyString());
        verify(notifications, never()).notifyOnce(any(), eq(NotificationType.FX_MATURITY_REMINDER), any(), anyString(), anyString(),
                anyString(), eq(later.getId()), anyString(), anyString(), anyString());
    }
}
