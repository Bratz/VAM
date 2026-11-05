package com.bank.vam.fx.deal;

import com.bank.vam.config.FxDealProperties;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.fx.ForwardPricer;
import com.bank.vam.fx.ForwardQuote;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Stands in for the bank's FX desk: prices instantly, firm for a short time. The client rate is the
 * market mid, plus forward points for anything past spot, moved against the client by a spread that
 * grows with ticket size and tenor.
 *
 * <p>ponytail: a plain class, not an interface. A human dealer workbench is the point to extract one.
 */
@Component
@RequiredArgsConstructor
public class SimulatedDealer {

    public static final String NAME = "SIMULATED";

    /** The price, and the pieces it is made of. */
    public record Price(BigDecimal spotMid, BigDecimal forwardPoints, BigDecimal forwardMid, BigDecimal spreadBps,
                        BigDecimal clientRate, BigDecimal counterAmount, LocalDate pricedDate) {
    }

    private final ForwardPricer forwards;
    private final FxDealProperties props;

    /**
     * @param valueDate   the contracted date (FORWARD) or the last drawdown date (WINDOW); ignored for SPOT
     * @param windowStart first drawdown date, WINDOW only
     */
    public Price price(String pair, Side side, Instrument instrument, BigDecimal amount,
                       LocalDate valueDate, LocalDate windowStart) {
        ForwardQuote basis = switch (instrument) {
            case SPOT -> forwards.forward(pair, forwards.spotDate());
            case FORWARD -> forwards.forward(pair, valueDate);
            case WINDOW -> windowBasis(pair, side, valueDate, windowStart);
        };

        BigDecimal spread = spreadBps(pair, amount, basis.days());
        BigDecimal factor = side == Side.BUY
                ? BigDecimal.ONE.add(spread.movePointLeft(4))
                : BigDecimal.ONE.subtract(spread.movePointLeft(4));
        BigDecimal rate = basis.forwardMid().multiply(factor).setScale(basis.forwardMid().scale(), RoundingMode.HALF_UP);
        BigDecimal counter = amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        return new Price(basis.spotMid(), basis.forwardPoints(), basis.forwardMid(), spread, rate, counter, basis.valueDate());
    }

    /**
     * The client may draw on any date in the window, so the bank quotes the date that is worst for the
     * client: the highest forward rate when buying, the lowest when selling. A forward rate moves in
     * one direction with time, so the two ends of the window are enough to compare.
     */
    private ForwardQuote windowBasis(String pair, Side side, LocalDate end, LocalDate start) {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new BusinessException("FX_VALUE_DATE_INVALID", "A window needs a start date before its end date");
        }
        ForwardQuote first = forwards.forward(pair, start);
        ForwardQuote last = forwards.forward(pair, end);
        boolean lastIsHigher = last.forwardMid().compareTo(first.forwardMid()) >= 0;
        return (side == Side.BUY) == lastIsHigher ? last : first;
    }

    BigDecimal spreadBps(String pair, BigDecimal amount, int daysToMaturity) {
        BigDecimal spread = props.getBaseSpreadBps().getOrDefault(pair, props.getDefaultSpreadBps());
        if (amount.compareTo(props.getHugeAmount()) >= 0) {
            spread = spread.add(props.getHugeAmountAddBps());
        } else if (amount.compareTo(props.getLargeAmount()) >= 0) {
            spread = spread.add(props.getLargeAmountAddBps());
        }
        BigDecimal months = BigDecimal.valueOf(daysToMaturity).divide(BigDecimal.valueOf(30), 4, RoundingMode.HALF_UP);
        return spread.add(props.getTenorAddBpsPerMonth().multiply(months)).setScale(2, RoundingMode.HALF_UP);
    }
}
