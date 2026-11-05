package com.bank.vam.fx.deal;

import com.bank.vam.config.FxDealProperties;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.fx.ForwardPricer;
import com.bank.vam.fx.deal.SimulatedDealer.Price;
import com.bank.vam.repository.CorporateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Request, price, accept. The dealer prices at once; the client then has a short window to accept.
 * Accepting re-checks everything that could have changed (validity, credit limit) under a lock on the
 * quote and on the corporate's limit, so a double click or two users cannot book the same quote or
 * spend the same headroom twice.
 */
@Service
@RequiredArgsConstructor
public class FxQuoteService {

    private static final Pattern PAIR = Pattern.compile("[A-Z]{3}/[A-Z]{3}");

    /** What the client asks for. {@code valueDate} is the contracted date, or the last drawdown date of a window. */
    public record Command(UUID corporateId, String pair, Side side, Instrument instrument, BigDecimal amount,
                          LocalDate valueDate, LocalDate windowStart, String purpose, String exposureRef) {
    }

    private final FxQuoteRequestRepository quotes;
    private final CorporateRepository corporates;
    private final SimulatedDealer dealer;
    private final FxLimitService limitService;
    private final FxDealService dealService;
    private final FxDealRepository deals;
    private final FxEvents log;
    private final FxDealProperties props;
    private final ForwardPricer forwards;

    @Transactional
    public FxQuoteRequest request(Command c) {
        if (!corporates.existsById(c.corporateId())) {
            throw new ResourceNotFoundException("Corporate not found: " + c.corporateId());
        }
        validate(c);

        FxQuoteRequest q = new FxQuoteRequest();
        q.setCorporateId(c.corporateId());
        q.setPair(c.pair());
        q.setSide(c.side());
        q.setInstrument(c.instrument());
        q.setAmount(c.amount().setScale(2, RoundingMode.HALF_UP));
        q.setWindowStart(c.windowStart());
        q.setPurpose(blankToNull(c.purpose()));
        q.setExposureRef(blankToNull(c.exposureRef()));
        q.setRequestedBy(FxEvents.actor());
        q.setValueDate(c.instrument() == Instrument.SPOT ? forwards.spotDate() : c.valueDate());
        q = quotes.save(q);
        log.quote(q, null, QuoteStatus.REQUESTED, describe(q));

        // An invalid pair or date throws here, which rolls the request back: nothing is left behind.
        Price price = dealer.price(c.pair(), c.side(), c.instrument(), q.getAmount(), c.valueDate(), c.windowStart());

        FxCreditLimit limit = limitService.limitFor(c.corporateId());
        BigDecimal charge = limitService.charge(q.baseCurrency(), q.getAmount(), LocalDate.now(), price.pricedDate(), limit.getCurrency());
        FxLimitService.Position position = limitService.position(limit);
        if (charge.compareTo(position.headroom()) > 0) {
            q.setStatus(QuoteStatus.REJECTED);
            q.setStatusReason("Not quoted: the corporate's FX credit limit would be exceeded (needs "
                    + charge + " " + limit.getCurrency() + ", " + position.headroom().max(BigDecimal.ZERO) + " left)");
            log.quote(q, QuoteStatus.REQUESTED, QuoteStatus.REJECTED, q.getStatusReason());
            return q;
        }

        q.setValueDate(price.pricedDate());
        q.setMidRate(price.spotMid());
        q.setForwardPoints(price.forwardPoints());
        q.setSpreadBps(price.spreadBps());
        q.setClientRate(price.clientRate());
        q.setCounterAmount(price.counterAmount());
        q.setDealer(SimulatedDealer.NAME);
        q.setQuotedAt(LocalDateTime.now());
        q.setValidUntil(q.getQuotedAt().plusSeconds(props.getQuoteValiditySeconds()));
        q.setLimitCharge(charge);
        q.setLimitCurrency(limit.getCurrency());
        q.setStatus(QuoteStatus.QUOTED);
        log.quote(q, QuoteStatus.REQUESTED, QuoteStatus.QUOTED,
                "Rate " + price.clientRate().toPlainString() + ", firm for " + props.getQuoteValiditySeconds() + "s");
        return q;
    }

    /** A quote past its validity turns EXPIRED the first time anything looks at it; there is no sweeper. */
    @Transactional(noRollbackFor = BusinessException.class)
    public FxQuoteRequest get(UUID id) {
        FxQuoteRequest q = quotes.findById(id).orElseThrow(() -> notFound(id));
        expireIfLapsed(q);
        return q;
    }

    /**
     * A corporate's requests in a period, newest first. Looking at them turns any that have outlived their
     * validity into EXPIRED, the same as reading one by id, so the log never shows a firm price that is not.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public List<FxQuoteRequest> search(UUID corporateId, LocalDateTime from, LocalDateTime to) {
        List<FxQuoteRequest> found = quotes.findByCorporateIdAndCreatedAtBetweenOrderByCreatedAtDesc(corporateId, from, to);
        found.forEach(this::expireIfLapsed);
        return found;
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public FxDeal accept(UUID id) {
        FxQuoteRequest q = quotes.lockById(id).orElseThrow(() -> notFound(id));
        expireIfLapsed(q);

        if (q.getStatus() == QuoteStatus.ACCEPTED) {
            return deals.findById(q.getDealId()).orElseThrow();
        }
        if (q.getStatus() != QuoteStatus.QUOTED) {
            throw new BusinessException("FX_QUOTE_NOT_ACCEPTABLE",
                    "This quote can no longer be accepted: it is " + q.getStatus()
                            + (q.getStatusReason() == null ? "" : " (" + q.getStatusReason() + ")"));
        }

        // The limit is checked again now, under a lock: another deal may have used the headroom since the quote.
        FxCreditLimit limit = limitService.lockLimitFor(q.getCorporateId());
        BigDecimal charge = limitService.charge(q.baseCurrency(), q.getAmount(), LocalDate.now(), q.getValueDate(), limit.getCurrency());
        limitService.require(limitService.position(limit), charge);

        FxDeal deal = dealService.book(q, charge, limit.getCurrency());
        q.setStatus(QuoteStatus.ACCEPTED);
        q.setAcceptedAt(LocalDateTime.now());
        q.setDealId(deal.getId());
        log.quote(q, QuoteStatus.QUOTED, QuoteStatus.ACCEPTED, "Accepted as " + deal.getDealRef());
        return deal;
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public FxQuoteRequest cancel(UUID id) {
        FxQuoteRequest q = quotes.lockById(id).orElseThrow(() -> notFound(id));
        expireIfLapsed(q);
        if (q.getStatus() == QuoteStatus.CANCELLED) {
            return q;
        }
        if (q.getStatus() != QuoteStatus.QUOTED && q.getStatus() != QuoteStatus.REQUESTED) {
            throw new BusinessException("FX_QUOTE_NOT_CANCELLABLE", "This quote is " + q.getStatus() + " and cannot be cancelled");
        }
        QuoteStatus from = q.getStatus();
        q.setStatus(QuoteStatus.CANCELLED);
        log.quote(q, from, QuoteStatus.CANCELLED, "Withdrawn by the client");
        return q;
    }

    private void expireIfLapsed(FxQuoteRequest q) {
        if (q.getStatus() == QuoteStatus.QUOTED && LocalDateTime.now().isAfter(q.getValidUntil())) {
            q.setStatus(QuoteStatus.EXPIRED);
            q.setStatusReason("The price was firm until " + q.getValidUntil().toLocalTime().withNano(0));
            log.quote(q, QuoteStatus.QUOTED, QuoteStatus.EXPIRED, q.getStatusReason());
        }
    }

    private void validate(Command c) {
        if (c.pair() == null || !PAIR.matcher(c.pair()).matches()) {
            throw new BusinessException("FX_PAIR_UNSUPPORTED", "Pair must look like EUR/USD: " + c.pair());
        }
        if (c.side() == null || c.instrument() == null) {
            throw new BusinessException("FX_REQUEST_INVALID", "Side and instrument are required");
        }
        if (c.amount() == null || c.amount().compareTo(props.getMinAmount()) < 0) {
            throw new BusinessException("FX_AMOUNT_INVALID", "Amount must be at least " + props.getMinAmount().toPlainString());
        }
        if (c.instrument() != Instrument.SPOT && c.valueDate() == null) {
            throw new BusinessException("FX_VALUE_DATE_INVALID", "A value date is required for a " + c.instrument().name().toLowerCase());
        }
    }

    private static String describe(FxQuoteRequest q) {
        return q.getSide() + " " + q.getAmount().toPlainString() + " " + q.getPair() + " " + q.getInstrument();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static ResourceNotFoundException notFound(UUID id) {
        return new ResourceNotFoundException("Quote not found: " + id);
    }
}
