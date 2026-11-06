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
    private final FxNotifier notifier = new FxNotifier(alerts, deals, rates, notifications);

    private void rate(String mid) {
        when(rates.spot("EUR/USD")).thenReturn(Optional.of(new SpotTick("EUR/USD", bd(mid), BigDecimal.ZERO, Instant.now(), "MOCK")));
    }

    @BeforeEach
    void wiring() {
        when(alerts.save(any(FxRateAlert.class))).thenAnswer(inv -> inv.getArgument(0));
        when(notifications.notifyOnce(any(), any(), any(), anyString(), anyString(), anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(true);
    }

    @Test
    void aTargetAboveTodaysRateFiresWhenTheRateRisesToItAndOnlyOnce() {
        rate("1.0900");
        FxRateAlert a = notifier.create(CORP, "eur/usd", bd("1.0950"), null);
        assertThat(a.getDirection()).isEqualTo(FxRateAlert.Direction.ABOVE);
        assertThat(a.getPair()).isEqualTo("EUR/USD");
        when(alerts.findByStatus(FxRateAlert.Status.ACTIVE)).thenReturn(List.of(a));

        rate("1.0940");
        assertThat(notifier.checkAlerts()).isZero();

        rate("1.0951");
        assertThat(notifier.checkAlerts()).isEqualTo(1);
        assertThat(a.getStatus()).isEqualTo(FxRateAlert.Status.TRIGGERED);
        assertThat(a.getTriggeredRate()).isEqualByComparingTo("1.0951");
        verify(notifications).notifyOnce(eq(CORP), eq(NotificationType.FX_RATE_ALERT), any(), eq("Rate alert: EUR/USD at 1.0951"),
                eq("EUR/USD has risen to 1.095 (indicative 1.0951)."), anyString(), eq(a.getId()), anyString(), eq("fx-market"), anyString());
    }

    @Test
    void anAlertAtTodaysRateOrForAnUnknownPairIsRefused() {
        rate("1.0900");
        assertThatThrownBy(() -> notifier.create(CORP, "EUR/USD", bd("1.09"), null)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("choose a different rate");
        when(rates.spot("XXX/YYY")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> notifier.create(CORP, "XXX/YYY", bd("1"), null)).hasMessageContaining("no market rate");
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
