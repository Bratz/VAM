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

    /** The desk's answer to a request for a better price. {@code atBest} means it will not move further. */
    public record Counter(BigDecimal spreadBps, BigDecimal clientRate, BigDecimal counterAmount, boolean atBest) {
    }

    /**
     * Answers "can you do better?". The desk will never give up more than a share of the margin it opened with
     * (its floor; more on a large ticket), and each round it gives up a share of what is left above that floor.
     * If the client named a rate the desk goes no further than that rate, so a modest ask is met exactly and an
     * ambitious one is met part of the way.
     *
     * @param rateScale  decimal places the pair is quoted to
     * @param targetRate the rate the client asks for, or null to ask for "your best price"
     */
    public Counter counter(Side side, BigDecimal amount, BigDecimal forwardMid, BigDecimal openingSpread,
                           BigDecimal currentSpread, BigDecimal currentRate, int rateScale, BigDecimal targetRate) {
        if (targetRate != null && (targetRate.signum() <= 0 || (side == Side.BUY
                ? targetRate.compareTo(currentRate) >= 0 : targetRate.compareTo(currentRate) <= 0))) {
            throw new BusinessException("FX_COUNTER_INVALID", "Ask for a rate better than the current "
                    + currentRate.toPlainString() + " (" + (side == Side.BUY ? "lower, as you are buying" : "higher, as you are selling") + ")");
        }
        BigDecimal floor = openingSpread.multiply(BigDecimal.ONE.subtract(concession(amount)));
        BigDecimal room = currentSpread.subtract(floor).max(BigDecimal.ZERO);
        BigDecimal next = currentSpread.subtract(room.multiply(props.getRoundGiveFraction()));
        if (targetRate != null) {
            // The spread, against the forward mid, that the client's rate would leave the bank.
            BigDecimal asked = (side == Side.BUY ? targetRate.divide(forwardMid, 10, RoundingMode.HALF_UP).subtract(BigDecimal.ONE)
                    : BigDecimal.ONE.subtract(targetRate.divide(forwardMid, 10, RoundingMode.HALF_UP))).movePointRight(4);
            next = next.max(asked);
        }
        next = next.max(floor).min(currentSpread).setScale(2, RoundingMode.HALF_UP);

        BigDecimal factor = side == Side.BUY ? BigDecimal.ONE.add(next.movePointLeft(4)) : BigDecimal.ONE.subtract(next.movePointLeft(4));
        BigDecimal rate = forwardMid.multiply(factor).setScale(rateScale, RoundingMode.HALF_UP);
        // Rounding the rate can leave it no better than before; then there is nothing more to give.
        boolean better = side == Side.BUY ? rate.compareTo(currentRate) < 0 : rate.compareTo(currentRate) > 0;
        if (!better) {
            return new Counter(currentSpread, currentRate.setScale(rateScale, RoundingMode.HALF_UP), amount.multiply(currentRate).setScale(2, RoundingMode.HALF_UP), true);
        }
        boolean atBest = next.subtract(floor).compareTo(new BigDecimal("0.05")) < 0;
        return new Counter(next, rate, amount.multiply(rate).setScale(2, RoundingMode.HALF_UP), atBest);
    }

    private BigDecimal concession(BigDecimal amount) {
        return amount.compareTo(props.getHugeAmount()) >= 0 ? props.getHugeConcessionFraction()
                : amount.compareTo(props.getLargeAmount()) >= 0 ? props.getLargeConcessionFraction()
                : props.getConcessionFraction();
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
