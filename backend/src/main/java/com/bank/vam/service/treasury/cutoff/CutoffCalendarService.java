package com.bank.vam.service.treasury.cutoff;

import com.bank.vam.config.MarketProfile;
import com.bank.vam.config.MarketProfileProperties;
import com.bank.vam.entity.treasury.CurrencyCutoffConfig;
import com.bank.vam.entity.treasury.SweepRule;
import com.bank.vam.entity.treasury.SweepRule.CutoffMode;
import com.bank.vam.entity.treasury.SweepRule.Rail;
import com.bank.vam.repository.treasury.BankHolidayRepository;
import com.bank.vam.repository.treasury.CurrencyCutoffConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.Optional;

/**
 * Service that resolves cut-off and bank-calendar rules for cross-bank sweeps.
 *
 * Two questions answered:
 *  1. {@link #checkEligibility(SweepRule, String, ZonedDateTime)} — given a rule
 *     and a candidate moment, is it within the eligible window today?
 *  2. {@link #nextEligibleExecution(SweepRule, String, ZonedDateTime)} — if not,
 *     when is the next eligible moment (rolling past cut-offs and holidays)?
 *
 * Calendar-code resolution: currency-driven (e.g. USD→USD-CHIPS, EUR→EUR-TARGET2,
 * GBP→GBP-CHAPS, AED→AED-CB). Fallbacks to "{CCY}-DEFAULT" if no row found.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CutoffCalendarService {

    private final CurrencyCutoffConfigRepository cutoffRepository;
    private final BankHolidayRepository holidayRepository;
    private final MarketProfileProperties marketProfile;

    /**
     * Snapshot of an eligibility check — both whether it passes and *why* not.
     */
    public record EligibilityResult(boolean eligible,
                                    String reason,
                                    ZonedDateTime nextEligibleAt) {
        public static EligibilityResult ok() { return new EligibilityResult(true, null, null); }
        public static EligibilityResult blocked(String reason, ZonedDateTime nextAt) {
            return new EligibilityResult(false, reason, nextAt);
        }
    }

    @Transactional(readOnly = true)
    public EligibilityResult checkEligibility(SweepRule rule, String sourceBankTimezone, ZonedDateTime nowAnyZone) {
        String currency = rule.getCurrencyCode();
        Rail rail = rule.getRail() != null ? rule.getRail() : Rail.AUTO;
        ZoneId zone = resolveZone(rule, sourceBankTimezone);
        ZonedDateTime now = nowAnyZone.withZoneSameInstant(zone);

        // 1. Bank calendar
        if (Boolean.TRUE.equals(rule.getRespectBankCalendar())) {
            String calendarCode = calendarCodeFor(currency);
            if (isHoliday(calendarCode, now.toLocalDate())) {
                ZonedDateTime nextAt = nextBusinessDayStart(now, calendarCode);
                return EligibilityResult.blocked("Bank holiday (" + calendarCode + ")", nextAt);
            }
        }

        // 2. Cut-off
        Optional<CurrencyCutoffConfig> cutoff = resolveCutoff(currency, rail);
        if (cutoff.isEmpty()) {
            // No cut-off configured for this (ccy, rail) → treat as always eligible.
            // Real adapters will refuse at instruction time if necessary.
            return EligibilityResult.ok();
        }
        LocalTime cutoffTime = cutoff.get().getCutoffTime();
        ZoneId cutoffZone = ZoneId.of(cutoff.get().getTimezone());
        ZonedDateTime cutoffMoment = now.withZoneSameInstant(cutoffZone)
                .with(cutoffTime).withSecond(0).withNano(0);
        if (now.withZoneSameInstant(cutoffZone).toLocalTime().isAfter(cutoffTime)) {
            String calendarCode = calendarCodeFor(currency);
            ZonedDateTime nextAt = nextBusinessDayStart(now.plusDays(1), calendarCode)
                    .withZoneSameInstant(cutoffZone).with(LocalTime.of(0, 0));
            return EligibilityResult.blocked(
                    "Past cut-off " + cutoffTime + " " + cutoff.get().getTimezone(),
                    nextAt);
        }
        log.debug("Cut-off ok for {}/{}: now={} cutoff={}", currency, rail, now, cutoffMoment);
        return EligibilityResult.ok();
    }

    @Transactional(readOnly = true)
    public ZonedDateTime nextEligibleExecution(SweepRule rule, String sourceBankTimezone, ZonedDateTime nowAnyZone) {
        EligibilityResult r = checkEligibility(rule, sourceBankTimezone, nowAnyZone);
        return r.eligible() ? nowAnyZone : r.nextEligibleAt();
    }

    @Transactional(readOnly = true)
    public boolean isHoliday(String calendarCode, LocalDate date) {
        if (holidayRepository.existsByCalendarCodeAndHolidayDate(calendarCode, date)) return true;
        return isWeekend(date);
    }

    /**
     * Apply the active market profile's weekend rule.
     * SAT_SUN — global default (UK/EU/US/SG/UAE post-2022).
     * FRI_SAT — legacy GCC (some KSA-aligned markets).
     * SUN_THU — explicit working-week variant; weekend = Fri+Sat.
     */
    private boolean isWeekend(LocalDate date) {
        MarketProfile.Weekend weekend = marketProfile.getActiveWeekend();
        DayOfWeek dow = date.getDayOfWeek();
        return switch (weekend != null ? weekend : MarketProfile.Weekend.SAT_SUN) {
            case SAT_SUN -> dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
            case FRI_SAT, SUN_THU -> dow == DayOfWeek.FRIDAY || dow == DayOfWeek.SATURDAY;
        };
    }

    // ------------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------------

    private Optional<CurrencyCutoffConfig> resolveCutoff(String currency, Rail rail) {
        if (rail != Rail.AUTO) {
            Optional<CurrencyCutoffConfig> direct = cutoffRepository.findByCurrencyCodeAndRail(currency, rail);
            if (direct.isPresent()) return direct;
        }
        // AUTO or no direct row — return first row for currency as a best-effort
        return cutoffRepository.findByCurrencyCode(currency).stream().findFirst();
    }

    private ZoneId resolveZone(SweepRule rule, String sourceBankTimezone) {
        if (rule.getCutoffMode() == CutoffMode.CORPORATE_HQ
                && rule.getCorporateHqTimezone() != null
                && !rule.getCorporateHqTimezone().isBlank()) {
            try {
                return ZoneId.of(rule.getCorporateHqTimezone());
            } catch (Exception e) {
                log.warn("Invalid corporateHqTimezone '{}' on rule {} — falling back to source bank zone",
                        rule.getCorporateHqTimezone(), rule.getRuleReference());
            }
        }
        if (sourceBankTimezone != null && !sourceBankTimezone.isBlank()) {
            try {
                return ZoneId.of(sourceBankTimezone);
            } catch (Exception ignored) {
                // fall through
            }
        }
        return ZoneId.of("UTC");
    }

    private ZonedDateTime nextBusinessDayStart(ZonedDateTime from, String calendarCode) {
        ZonedDateTime probe = from.plusDays(1).with(LocalTime.MIDNIGHT);
        while (isHoliday(calendarCode, probe.toLocalDate())) {
            probe = probe.plusDays(1);
        }
        return probe;
    }

    /**
     * Maps a currency to its primary clearing-calendar code.
     * Add more rows here (or in seed data) as new currencies come online.
     */
    private String calendarCodeFor(String currency) {
        if (currency == null) return "DEFAULT";
        return switch (currency.toUpperCase()) {
            case "USD" -> "USD-CHIPS";
            case "EUR" -> "EUR-TARGET2";
            case "GBP" -> "GBP-CHAPS";
            case "AED" -> "AED-CB";
            case "SAR" -> "SAR-CB";
            case "CHF" -> "CHF-SIC";
            case "JPY" -> "JPY-BOJNET";
            default -> currency.toUpperCase() + "-DEFAULT";
        };
    }
}
