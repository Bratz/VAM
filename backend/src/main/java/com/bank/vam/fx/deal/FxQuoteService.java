package com.bank.vam.fx.deal;

import com.bank.vam.config.FxDealProperties;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.fx.ForwardPricer;
import com.bank.vam.fx.deal.FxDealModels.QuoteView;
import com.bank.vam.fx.deal.FxDealModels.RfqLog;
import com.bank.vam.fx.deal.FxDealModels.RfqRow;
import com.bank.vam.fx.deal.FxDealModels.RfqSummary;
import com.bank.vam.fx.deal.SimulatedDealer.Price;
import com.bank.vam.repository.CorporateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
    public record Command(UUID corporateId, UUID entityId, String pair, Side side, Instrument instrument, BigDecimal amount,
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
    private final FxSettlementAccounts settlementAccounts;

    @Transactional
    public FxQuoteRequest request(Command c) {
        if (!corporates.existsById(c.corporateId())) {
            throw new ResourceNotFoundException("Corporate not found: " + c.corporateId());
        }
        validate(c);
        // Throws when the entity is not one of the corporate's active entities.
        String entityName = limitService.allocation(c.corporateId(), c.entityId()).entityName();

        FxQuoteRequest q = new FxQuoteRequest();
        q.setCorporateId(c.corporateId());
        q.setEntityId(c.entityId());
        q.setEntityName(entityName);
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

        FxLimitService.Line line = limitService.line(c.corporateId());
        BigDecimal charge = limitService.charge(c.instrument(), q.baseCurrency(), q.getAmount(), LocalDate.now(),
                price.pricedDate(), line.currency());
        String refusal = limitService.refusal(c.corporateId(), c.entityId(), charge);
        if (refusal == null) {
            // The bank must be able to post both legs: the bought currency to one account, the sold from another.
            boolean buyBase = c.side() == Side.BUY;
            FxSettlementAccounts.Resolved buy = settlementAccounts.resolve(c.corporateId(), c.entityId(), buyBase ? q.baseCurrency() : q.quoteCurrency());
            FxSettlementAccounts.Resolved sell = settlementAccounts.resolve(c.corporateId(), c.entityId(), buyBase ? q.quoteCurrency() : q.baseCurrency());
            refusal = !buy.found() ? buy.reason() + "." : !sell.found() ? sell.reason() + "." : null;
            if (refusal == null) {
                q.setBuyAccountId(buy.account().id());
                q.setBuyAccountLabel(buy.account().label());
                q.setSellAccountId(sell.account().id());
                q.setSellAccountLabel(sell.account().label());
            }
        }
        if (refusal != null) {
            q.setStatus(QuoteStatus.REJECTED);
            q.setStatusReason("Not quoted. " + refusal);
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
        q.setForwardMid(price.forwardMid());
        q.setRateScale(price.forwardMid().scale());
        q.setOpeningSpreadBps(price.spreadBps());
        q.setMaxRounds(props.getNegotiationMaxRounds());
        q.setNegotiationRounds(0);
        q.setQuotedAt(LocalDateTime.now());
        q.setValidUntil(q.getQuotedAt().plusSeconds(props.getQuoteValiditySeconds()));
        q.setLimitCharge(charge);
        q.setLimitCurrency(line.currency());
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

    /**
     * The RFQ log: a corporate's requests in the last {@code days} (1 to 365), newest first, with what became of
     * each. The summary covers every status for the pair filter; {@code status} and {@code limit} (1 to 500)
     * only trim the rows. Shared by the REST endpoint and the MCP tool so they cannot disagree.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public RfqLog rfqLog(UUID corporateId, QuoteStatus status, String pair, int days, int limit) {
        LocalDateTime now = LocalDateTime.now();
        List<FxQuoteRequest> found = search(corporateId, now.minusDays(Math.min(Math.max(days, 1), 365)), now.plusMinutes(1)).stream()
                .filter(q -> pair == null || pair.isBlank() || q.getPair().equals(pair)).toList();
        List<FxQuoteRequest> shown = found.stream().filter(q -> status == null || q.getStatus() == status)
                .limit(Math.min(Math.max(limit, 1), 500)).toList();
        Map<UUID, String> refs = deals.findAllById(shown.stream().map(FxQuoteRequest::getDealId).filter(Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(FxDeal::getId, FxDeal::getDealRef));
        return new RfqLog(RfqSummary.of(found), shown.stream().map(q -> new RfqRow(QuoteView.of(q), refs.get(q.getDealId()))).toList());
    }

    /** Blank means no filter; anything else must be a quote status, or the caller is told what is allowed. */
    public static QuoteStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return QuoteStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("FX_REQUEST_INVALID",
                    "Unknown status '" + status + "'; use one of " + Arrays.toString(QuoteStatus.values()));
        }
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

        // The line and the entity's sub-limit are checked again now, under locks: another deal may have used the
        // headroom since the price, or the facility may have been suspended.
        FxLimitService.Line line = limitService.line(q.getCorporateId());
        BigDecimal charge = limitService.charge(q.getInstrument(), q.baseCurrency(), q.getAmount(), LocalDate.now(),
                q.getValueDate(), line.currency());
        limitService.requireLocked(q.getCorporateId(), q.getEntityId(), charge);

        FxDeal deal = dealService.book(q, charge, line.currency());
        q.setStatus(QuoteStatus.ACCEPTED);
        q.setAcceptedAt(LocalDateTime.now());
        q.setDealId(deal.getId());
        log.quote(q, QuoteStatus.QUOTED, QuoteStatus.ACCEPTED, "Accepted as " + deal.getDealRef());
        return deal;
    }

    /**
     * The client asks the desk for a better price on a firm quote. The desk answers at once with a new firm price
     * (or says it is already at its best), and the validity window starts again. Only a quote that is still firm can
     * be countered, and only a few times, so this cannot be used to keep a price alive. Locked like accept, so a
     * counter and an accept cannot cross: whichever takes the lock first wins and the other is told the state.
     * The credit-limit charge does not depend on the rate, so it does not change.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public FxQuoteRequest counter(UUID id, BigDecimal targetRate, String note) {
        FxQuoteRequest q = quotes.lockById(id).orElseThrow(() -> notFound(id));
        expireIfLapsed(q);
        if (q.getStatus() != QuoteStatus.QUOTED) {
            throw new BusinessException("FX_QUOTE_NOT_NEGOTIABLE", "This price can no longer be negotiated: it is " + q.getStatus());
        }
        if (q.roundsAllowed() == 0) {
            throw new BusinessException("FX_QUOTE_NOT_NEGOTIABLE", "This price cannot be negotiated");
        }
        if (q.roundsUsed() >= q.roundsAllowed()) {
            throw new BusinessException("FX_NEGOTIATION_LIMIT", "No more rounds: accept the price or withdraw (" + q.roundsAllowed() + " allowed)");
        }

        SimulatedDealer.Counter c = dealer.counter(q.getSide(), q.getAmount(), q.getForwardMid(), q.getOpeningSpreadBps(),
                q.getSpreadBps(), q.getClientRate(), q.getRateScale() == null ? q.getForwardMid().scale() : q.getRateScale(), targetRate);
        boolean moved = c.clientRate().compareTo(q.getClientRate()) != 0;
        String before = q.getClientRate().toPlainString();
        q.setNegotiationRounds(q.roundsUsed() + 1);
        q.setSpreadBps(c.spreadBps());
        q.setClientRate(c.clientRate());
        q.setCounterAmount(c.counterAmount());
        q.setBestPrice(c.atBest());
        q.setQuotedAt(LocalDateTime.now());
        q.setValidUntil(q.getQuotedAt().plusSeconds(props.getQuoteValiditySeconds()));

        String asked = targetRate == null ? "asked for the best price" : "asked for " + targetRate.toPlainString();
        String answer = moved ? "desk offered " + c.clientRate().toPlainString() + " (was " + before + ")"
                : "desk held " + before + ": its best price";
        String detail = "Round " + q.roundsUsed() + " of " + q.roundsAllowed() + ": " + asked + "; " + answer
                + (c.atBest() && moved ? " - no further room" : "") + ", firm for " + props.getQuoteValiditySeconds() + "s"
                + (note == null || note.isBlank() ? "" : ". Note: " + note.trim());
        log.counter(q, detail.length() > 400 ? detail.substring(0, 397) + "..." : detail);
        return q;
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
        if (c.entityId() == null) {
            throw new BusinessException("FX_REQUEST_INVALID", "Choose the legal entity the deal is for");
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
