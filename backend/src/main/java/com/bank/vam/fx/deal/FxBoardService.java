package com.bank.vam.fx.deal;

import com.bank.vam.exception.BusinessException;
import com.bank.vam.fx.ForwardPricer;
import com.bank.vam.fx.ForwardQuote;
import com.bank.vam.fx.RateProvider;
import com.bank.vam.fx.SpotTick;
import com.bank.vam.fx.deal.FxDealModels.ClientPrice;
import com.bank.vam.fx.deal.SimulatedDealer.Price;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What the corporate sees on Market Rates: its own two-way price for each pair, at an amount it chooses, from the
 * same desk pricing as the deal ticket (the spread for size and tenor applied). Never the interbank mid, nor the
 * margin: put beside a deal price, the mid gives the margin away. Indicative: a price to deal on comes from the
 * ticket's stream or a request for quote.
 */
@Service
@RequiredArgsConstructor
public class FxBoardService {

    static final BigDecimal DEFAULT_AMOUNT = new BigDecimal("100000");
    private static final Pattern PAIR = Pattern.compile("[A-Z]{3}/[A-Z]{3}");

    /**
     * One pair: the client buys the base currency at {@code buy}, sells it at {@code sell}. Spot. {@code source} is
     * where the market rate came from ("SIMULATED" from the mock market).
     */
    public record BoardRow(String pair, BigDecimal buy, BigDecimal sell, BigDecimal changeBps, LocalDate valueDate, Instant asOf,
                           String source) {
    }

    /** One tenor of the forward curve: the outright rate each way, and the client points from the client spot. */
    public record CurveRow(String pair, String tenor, LocalDate valueDate, int days, BigDecimal buy, BigDecimal buyPoints,
                           BigDecimal sell, BigDecimal sellPoints, String source) {
    }

    private final RateProvider rates;
    private final ForwardPricer forwards;
    private final SimulatedDealer dealer;

    public List<BoardRow> board(BigDecimal amount) {
        BigDecimal a = amountOr(amount);
        return rates.spots().stream().map(t -> row(t, a)).toList();
    }

    public List<CurveRow> curve(String pair, BigDecimal amount) {
        requirePair(pair);
        BigDecimal a = amountOr(amount);
        return forwards.curve(pair).stream().map(q -> curveRow(pair, q.tenor(), q, a)).toList();
    }

    public CurveRow forward(String pair, BigDecimal amount, LocalDate valueDate) {
        requirePair(pair);
        return curveRow(pair, null, forwards.forward(pair, valueDate), amountOr(amount));
    }

    /** The client's spot price on one side, for alerts. */
    public BigDecimal spot(String pair, Side side, BigDecimal amount) {
        requirePair(pair);
        return dealer.price(pair, side, Instrument.SPOT, amountOr(amount), null, null).clientRate();
    }

    private BoardRow row(SpotTick t, BigDecimal amount) {
        Price buy = dealer.price(t.pair(), Side.BUY, Instrument.SPOT, amount, null, null);
        Price sell = dealer.price(t.pair(), Side.SELL, Instrument.SPOT, amount, null, null);
        return new BoardRow(t.pair(), buy.clientRate(), sell.clientRate(), t.changeBps(), buy.pricedDate(), t.asOf(), t.source());
    }

    private CurveRow curveRow(String pair, String tenor, ForwardQuote q, BigDecimal amount) {
        String quoteCcy = pair.substring(4);
        Price buy = dealer.price(pair, Side.BUY, Instrument.FORWARD, amount, q.valueDate(), null);
        Price sell = dealer.price(pair, Side.SELL, Instrument.FORWARD, amount, q.valueDate(), null);
        ClientPrice b = ClientPrice.of(Instrument.FORWARD, Side.BUY, quoteCcy, buy.clientRate(), buy.spotMid(), buy.spreadBps());
        ClientPrice s = ClientPrice.of(Instrument.FORWARD, Side.SELL, quoteCcy, sell.clientRate(), sell.spotMid(), sell.spreadBps());
        return new CurveRow(pair, tenor, q.valueDate(), q.days(), buy.clientRate(), b.forwardPoints(), sell.clientRate(), s.forwardPoints(), q.source());
    }

    private static BigDecimal amountOr(BigDecimal amount) {
        if (amount == null) {
            return DEFAULT_AMOUNT;
        }
        if (amount.signum() <= 0) {
            throw new BusinessException("FX_AMOUNT_INVALID", "Enter an amount greater than zero");
        }
        return amount;
    }

    private static void requirePair(String pair) {
        if (pair == null || !PAIR.matcher(pair).matches()) {
            throw new BusinessException("FX_PAIR_UNSUPPORTED", "Pair must look like EUR/USD: " + pair);
        }
    }
}
