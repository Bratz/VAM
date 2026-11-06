package com.bank.vam.fx.deal;

import com.bank.vam.entity.notification.Notification.NotificationCategory;
import com.bank.vam.entity.notification.Notification.NotificationType;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.fx.RateProvider;
import com.bank.vam.fx.SpotTick;
import com.bank.vam.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * FX notifications in the header bell: rate alerts the corporate sets on the indicative market rate, a reminder
 * when a deal reaches its value date within two days, and a confirmation waiting to be checked. One scan every
 * {@code vam.fx.notify-interval-ms} (30 s); each notification is raised once per alert or deal.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FxNotifier {

    static final int MATURITY_DAYS = 2;

    private final FxRateAlertRepository alerts;
    private final FxDealRepository deals;
    private final RateProvider rates;
    private final NotificationService notifications;

    // ---- rate alerts ----

    /** The direction follows from today's rate: a target above it fires when the rate rises to it. */
    @Transactional
    public FxRateAlert create(UUID corporateId, String pair, BigDecimal targetRate, String note) {
        String p = pair == null ? "" : pair.trim().toUpperCase(Locale.ROOT);
        if (targetRate == null || targetRate.signum() <= 0) {
            throw new BusinessException("FX_ALERT_INVALID", "Enter the rate to be told about");
        }
        SpotTick now = (p.matches("[A-Z]{3}/[A-Z]{3}") ? rates.spot(p) : java.util.Optional.<SpotTick>empty())
                .orElseThrow(() -> new BusinessException("FX_ALERT_INVALID", "There is no market rate for " + pair));
        int cmp = targetRate.compareTo(now.mid());
        if (cmp == 0) {
            throw new BusinessException("FX_ALERT_INVALID", p + " is at " + now.mid().toPlainString() + " now; choose a different rate");
        }
        FxRateAlert a = new FxRateAlert();
        a.setCorporateId(corporateId);
        a.setPair(p);
        a.setTargetRate(targetRate);
        a.setDirection(cmp > 0 ? FxRateAlert.Direction.ABOVE : FxRateAlert.Direction.BELOW);
        a.setRateAtCreation(now.mid());
        a.setNote(note == null || note.isBlank() ? null : note.trim());
        return alerts.save(a);
    }

    public List<FxRateAlert> list(UUID corporateId) {
        return alerts.findByCorporateIdOrderByCreatedAtDesc(corporateId);
    }

    @Transactional
    public FxRateAlert cancel(UUID id) {
        FxRateAlert a = alerts.findById(id).orElseThrow(() -> new ResourceNotFoundException("Rate alert not found: " + id));
        if (a.getStatus() == FxRateAlert.Status.ACTIVE) {
            a.setStatus(FxRateAlert.Status.CANCELLED);
        }
        return alerts.save(a);
    }

    // ---- the scan ----

    @Scheduled(fixedDelayString = "${vam.fx.notify-interval-ms:30000}", initialDelayString = "${vam.fx.notify-initial-delay-ms:30000}")
    public void scan() {
        try {
            checkAlerts();
            remindDeals(LocalDate.now());
        } catch (Exception e) {
            log.error("FX notification scan failed; will try again", e);
        }
    }

    @Transactional
    public int checkAlerts() {
        int fired = 0;
        for (FxRateAlert a : alerts.findByStatus(FxRateAlert.Status.ACTIVE)) {
            BigDecimal mid = rates.spot(a.getPair()).map(SpotTick::mid).orElse(null);
            if (mid == null) {
                continue;
            }
            boolean reached = a.getDirection() == FxRateAlert.Direction.ABOVE
                    ? mid.compareTo(a.getTargetRate()) >= 0 : mid.compareTo(a.getTargetRate()) <= 0;
            if (!reached) {
                continue;
            }
            a.setStatus(FxRateAlert.Status.TRIGGERED);
            a.setTriggeredAt(LocalDateTime.now());
            a.setTriggeredRate(mid);
            alerts.save(a);
            notifications.notifyOnce(a.getCorporateId(), NotificationType.FX_RATE_ALERT, NotificationCategory.INFO,
                    "Rate alert: " + a.getPair() + " at " + mid.toPlainString(),
                    a.getPair() + " has " + (a.getDirection() == FxRateAlert.Direction.ABOVE ? "risen to " : "fallen to ")
                            + a.getTargetRate().stripTrailingZeros().toPlainString() + " (indicative " + mid.toPlainString() + ")."
                            + (a.getNote() == null ? "" : " " + a.getNote()),
                    "FX_RATE_ALERT", a.getId(), a.getPair(), "fx-market", "Open Market Rates");
            fired++;
        }
        return fired;
    }

    /** Value date within two days and something left to deliver; a confirmation still waiting for the client. */
    @Transactional
    public int remindDeals(LocalDate today) {
        int raised = 0;
        for (FxDeal d : deals.findByStatusIn(DealStatus.OPEN)) {
            if (d.getConfirmationStatus() == ConfirmationStatus.ISSUED && notifications.notifyOnce(d.getCorporateId(),
                    NotificationType.FX_CONFIRMATION_PENDING, NotificationCategory.WARNING, "Confirm FX deal " + d.getDealRef(),
                    "The bank's confirmation of " + d.getSide().name().toLowerCase(Locale.ROOT) + " " + d.getPair() + " "
                            + d.baseAmount().toPlainString() + " " + d.baseCurrency() + " is waiting for you to check. It cannot settle until you confirm it.",
                    "FX_DEAL", d.getId(), d.getDealRef(), "fx-deals", "Open FX Deals")) {
                raised++;
            }
            boolean soon = !d.getValueDate().isBefore(today) && !d.getValueDate().isAfter(today.plusDays(MATURITY_DAYS));
            if (soon && d.remainingBase().signum() > 0 && notifications.notifyOnce(d.getCorporateId(),
                    NotificationType.FX_MATURITY_REMINDER, NotificationCategory.INFO, "FX deal " + d.getDealRef() + " settles " + d.getValueDate(),
                    "You buy " + d.getBuyCurrency() + " " + d.getBuyAmount().toPlainString() + " and sell " + d.getSellCurrency() + " "
                            + d.getSellAmount().toPlainString() + " on " + d.getValueDate() + (d.getSellAccountLabel() == null ? ""
                            : ", debited from " + d.getSellAccountLabel()) + ". Make sure the funds are there.",
                    "FX_DEAL", d.getId(), d.getDealRef(), "fx-deals", "Open FX Deals")) {
                raised++;
            }
        }
        return raised;
    }
}
