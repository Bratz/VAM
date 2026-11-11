package com.bank.vam.fx;

import com.bank.vam.config.FxMarketProperties;
import com.bank.vam.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Forward points by covered interest parity: F = S x (1 + r_quote x d/B_quote) / (1 + r_base x d/B_base),
 * with d the days from the spot date to the value date.
 *
 * <p>ponytail: weekends are the only non-business days. Per-currency holiday calendars and the
 * USD/CAD T+1 exception are not modelled; add them where the settlement calendar is needed.
 */
@Component
public class ForwardPricer {

    public static final List<String> STANDARD_TENORS = List.of("1W", "1M", "2M", "3M", "6M", "9M", "12M");

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final Pattern TENOR = Pattern.compile("(\\d{1,2})([WM])");
    private static final Set<String> TWO_DECIMAL_QUOTES = Set.of("JPY", "INR");

    /** A pip of a pair quoted in this currency: 0.01 for yen and rupee quotes, 0.0001 otherwise. */
    public static BigDecimal pip(String quoteCurrency) {
        return TWO_DECIMAL_QUOTES.contains(quoteCurrency) ? new BigDecimal("0.01") : new BigDecimal("0.0001");
    }

    /** Decimal places a forward or client rate in this quote currency is shown to: the spot's places plus one. */
    public static int rateScale(String quoteCurrency) {
        return TWO_DECIMAL_QUOTES.contains(quoteCurrency) ? 4 : 6;
    }

    private final FxMarketProperties props;
    private final RateProvider rates;
    private final Clock clock;

    @Autowired
    public ForwardPricer(FxMarketProperties props, RateProvider rates) {
        this(props, rates, Clock.systemDefaultZone());
    }

    public ForwardPricer(FxMarketProperties props, RateProvider rates, Clock clock) {
        this.props = props;
        this.rates = rates;
        this.clock = clock;
    }

    public LocalDate spotDate() {
        LocalDate d = LocalDate.now(clock);
        for (int i = 0; i < props.getSpotLagDays(); i++) {
            d = nextBusinessDay(d);
        }
        return d;
    }

    public ForwardQuote forward(String pair, LocalDate valueDate) {
        return price(pair, null, valueDate);
    }

    public ForwardQuote forward(String pair, String tenor) {
        Matcher m = TENOR.matcher(tenor == null ? "" : tenor.toUpperCase());
        if (!m.matches()) {
            throw new BusinessException("FX_TENOR_INVALID", "Tenor must look like 1W, 3M or 12M: " + tenor);
        }
        int n = Integer.parseInt(m.group(1));
        LocalDate raw = m.group(2).equals("W") ? spotDate().plusWeeks(n) : spotDate().plusMonths(n);
        return price(pair, tenor.toUpperCase(), modifiedFollowing(raw));
    }

    public List<ForwardQuote> curve(String pair) {
        return STANDARD_TENORS.stream().map(t -> forward(pair, t)).toList();
    }

    private ForwardQuote price(String pair, String tenor, LocalDate valueDate) {
        SpotTick spot = rates.spot(pair).orElseThrow(() ->
                new BusinessException("FX_PAIR_UNSUPPORTED", "No market for " + pair
                        + ": the simulated market prices only pairs with a stored FX rate"));
        LocalDate spotDate = spotDate();
        if (isWeekend(valueDate)) {
            throw new BusinessException("FX_VALUE_DATE_INVALID", "Value date " + valueDate + " falls on a weekend");
        }
        if (valueDate.isBefore(spotDate)) {
            throw new BusinessException("FX_VALUE_DATE_INVALID",
                    "Value date " + valueDate + " is before the spot date " + spotDate + "; short dates are not offered");
        }
        int days = (int) ChronoUnit.DAYS.between(spotDate, valueDate);
        if (days > props.getMaxForwardDays()) {
            throw new BusinessException("FX_VALUE_DATE_INVALID",
                    "Value date " + valueDate + " is more than " + props.getMaxForwardDays() + " days past spot");
        }

        BigDecimal base = ratePct(spot.base());
        BigDecimal quote = ratePct(spot.quote());
        BigDecimal factor = accrual(quote, days, spot.quote()).divide(accrual(base, days, spot.base()), MC);
        BigDecimal forwardRaw = spot.mid().multiply(factor, MC);
        BigDecimal pip = pip(spot.quote());
        BigDecimal points = forwardRaw.subtract(spot.mid()).divide(pip, 2, RoundingMode.HALF_UP);

        return new ForwardQuote(spot.pair(), tenor, spotDate, valueDate, days, spot.mid(), points,
                forwardRaw.setScale(spot.mid().scale() + 1, RoundingMode.HALF_UP), base, quote, spot.source());
    }

    /** 1 + r x d / basis, with r given in percent. */
    private BigDecimal accrual(BigDecimal ratePct, int days, String ccy) {
        int basis = props.getActual365Currencies().contains(ccy) ? 365 : 360;
        return BigDecimal.ONE.add(ratePct.movePointLeft(2).multiply(BigDecimal.valueOf(days), MC)
                .divide(BigDecimal.valueOf(basis), MC), MC);
    }

    private BigDecimal ratePct(String ccy) {
        BigDecimal r = props.getInterestRates().get(ccy);
        if (r == null) {
            throw new BusinessException("FX_RATE_UNAVAILABLE", "No interest rate configured for " + ccy);
        }
        return r;
    }

    private static boolean isWeekend(LocalDate d) {
        return d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private static LocalDate nextBusinessDay(LocalDate d) {
        LocalDate n = d.plusDays(1);
        while (isWeekend(n)) {
            n = n.plusDays(1);
        }
        return n;
    }

    /** Roll a weekend date forward, unless that crosses into the next month, then roll back. */
    private static LocalDate modifiedFollowing(LocalDate d) {
        if (!isWeekend(d)) {
            return d;
        }
        LocalDate fwd = d;
        while (isWeekend(fwd)) {
            fwd = fwd.plusDays(1);
        }
        if (fwd.getMonth() == d.getMonth()) {
            return fwd;
        }
        LocalDate back = d;
        while (isWeekend(back)) {
            back = back.minusDays(1);
        }
        return back;
    }
}
