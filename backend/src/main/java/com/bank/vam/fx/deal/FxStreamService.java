package com.bank.vam.fx.deal;

import com.bank.vam.config.FxDealProperties;
import com.bank.vam.config.FxDealProperties.StreamLimit;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.fx.ForwardPricer;
import com.bank.vam.fx.deal.FxDealModels.ClientPrice;
import com.bank.vam.fx.deal.SimulatedDealer.Price;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Streaming, click-to-deal prices for small, short trades: both sides at once, refreshed by the screen, and dealt
 * on with one click within a few seconds of being shown. What streams is set by the bank per pair (amount and tenor,
 * {@link FxDealProperties#getStreaming()}); anything larger or longer goes to a request for quote, which can be
 * negotiated. A streamed deal is booked through the same quote and accept path as an RFQ, so the credit limit and
 * settlement accounts are checked the same way.
 *
 * <p>ponytail: issued prices live in this instance's memory, so a deal must reach the instance that showed the
 * price. Move them to Redis (or sticky sessions) when the backend runs more than one instance.
 */
@Service
@RequiredArgsConstructor
public class FxStreamService {

    /** One side of the stream: what the client buys or sells at, with the client spot and points for a forward. */
    public record Leg(BigDecimal rate, BigDecimal spotRate, BigDecimal forwardPoints, BigDecimal counterAmount) {
    }

    /**
     * A two-sided price, or why the trade is not streamed. {@code buy} is the client buying the base currency.
     * Never carries the mid or the margin.
     */
    public record StreamPrice(boolean streamable, String reason, String pair, BigDecimal amount, Instrument instrument,
                              LocalDate valueDate, UUID priceId, Leg buy, Leg sell, int validForSeconds,
                              BigDecimal maxAmount, int maxTenorDays) {
    }

    record Issued(FxQuoteService.Command command, Price buy, Price sell, Instant until) {
    }

    private final Map<UUID, Issued> issued = new ConcurrentHashMap<>();

    private final SimulatedDealer dealer;
    private final ForwardPricer forwards;
    private final FxQuoteService quotes;
    private final FxDealProperties props;

    /** {@code c.side()} is ignored: both sides are priced. */
    public StreamPrice price(FxQuoteService.Command c) {
        quotes.validate(c);
        StreamLimit limit = limitFor(c.pair());
        String why = notStreamed(c, limit);
        if (why != null) {
            return new StreamPrice(false, why, c.pair(), c.amount(), c.instrument(), null, null, null, null, 0,
                    limit.getMaxAmount(), limit.getMaxTenorDays());
        }
        Price buy = dealer.price(c.pair(), Side.BUY, c.instrument(), c.amount(), c.valueDate(), null);
        Price sell = dealer.price(c.pair(), Side.SELL, c.instrument(), c.amount(), c.valueDate(), null);
        Instant now = Instant.now();
        issued.values().removeIf(i -> i.until().isBefore(now));
        UUID id = UUID.randomUUID();
        issued.put(id, new Issued(c, buy, sell, now.plusSeconds(props.getStreamValiditySeconds())));
        String quoteCcy = c.pair().substring(4);
        return new StreamPrice(true, null, c.pair(), c.amount(), c.instrument(), buy.pricedDate(), id,
                leg(c.instrument(), Side.BUY, quoteCcy, buy), leg(c.instrument(), Side.SELL, quoteCcy, sell),
                props.getStreamValiditySeconds(), limit.getMaxAmount(), limit.getMaxTenorDays());
    }

    /** Deals on a price shown moments ago. A price past its few seconds, or already dealt on, is refused. */
    @Transactional
    public FxDeal deal(UUID priceId, Side side) {
        Issued i = take(priceId);
        FxQuoteService.Command c = i.command();
        FxQuoteService.Command dealt = new FxQuoteService.Command(c.corporateId(), c.entityId(), c.pair(), side, c.instrument(),
                c.amount(), c.valueDate(), null, c.purpose(), c.exposureRef());
        FxQuoteRequest q = quotes.request(dealt, side == Side.BUY ? i.buy() : i.sell());
        if (q.getStatus() != QuoteStatus.QUOTED) {
            throw new BusinessException("FX_STREAM_DECLINED", q.getStatusReason());
        }
        return quotes.accept(q.getId());
    }

    /** A price shown moments ago, removed so it is used once; a stale or unknown one is refused. */
    Issued take(UUID priceId) {
        Issued i = priceId == null ? null : issued.remove(priceId);
        if (i == null || i.until().isBefore(Instant.now())) {
            throw new BusinessException("FX_STREAM_PRICE_STALE", "That price has moved on; deal on the one showing now");
        }
        return i;
    }

    /** Why this trade goes to a request for quote instead, or null when it streams. */
    String notStreamed(FxQuoteService.Command c, StreamLimit limit) {
        String base = c.pair().substring(0, 3);
        if (c.instrument() == Instrument.WINDOW) {
            return "Window forwards are priced on request";
        }
        if (c.amount().compareTo(limit.getMaxAmount()) > 0) {
            return "Above " + String.format(java.util.Locale.ROOT, "%,d", limit.getMaxAmount().longValue()) + " " + base + " in " + c.pair()
                    + " the desk prices on request";
        }
        if (c.instrument() == Instrument.FORWARD) {
            if (limit.getMaxTenorDays() == 0) {
                return c.pair() + " forwards are priced on request";
            }
            if (c.valueDate() != null && ChronoUnit.DAYS.between(forwards.spotDate(), c.valueDate()) > limit.getMaxTenorDays()) {
                return c.pair() + " forwards beyond " + limit.getMaxTenorDays() + " days after spot are priced on request";
            }
        }
        return null;
    }

    StreamLimit limitFor(String pair) {
        StreamLimit l = props.getStreaming().get(pair);
        if (l == null && pair != null && pair.length() == 7) {
            l = props.getStreaming().get(pair.substring(4) + "/" + pair.substring(0, 3));
        }
        return l != null ? l : props.getDefaultStreaming();
    }

    private static Leg leg(Instrument instrument, Side side, String quoteCcy, Price p) {
        ClientPrice cp = ClientPrice.of(instrument, side, quoteCcy, p.clientRate(), p.spotMid(), p.spreadBps());
        return new Leg(p.clientRate(), cp.spotRate(), cp.forwardPoints(), p.counterAmount());
    }
}
