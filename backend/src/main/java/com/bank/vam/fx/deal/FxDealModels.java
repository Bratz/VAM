package com.bank.vam.fx.deal;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** Response shapes for quotes, deals, their timeline and the credit limit. */
public final class FxDealModels {

    private FxDealModels() {
    }

    /** {@code secondsRemaining} comes from the server clock so the client's countdown cannot drift from the real expiry. */
    public record QuoteView(
            UUID id, UUID corporateId, String pair, String baseCurrency, String quoteCurrency, Side side,
            Instrument instrument, BigDecimal amount, LocalDate valueDate, LocalDate windowStart, String purpose,
            String exposureRef, QuoteStatus status, String statusReason, BigDecimal midRate, BigDecimal forwardPoints,
            BigDecimal spreadBps, BigDecimal clientRate, BigDecimal counterAmount, String dealer,
            LocalDateTime quotedAt, LocalDateTime validUntil, long secondsRemaining, BigDecimal limitCharge,
            String limitCurrency, UUID dealId) {

        static QuoteView of(FxQuoteRequest q) {
            long remaining = q.getStatus() == QuoteStatus.QUOTED
                    ? Math.max(0, Duration.between(LocalDateTime.now(), q.getValidUntil()).toSeconds()) : 0;
            return new QuoteView(q.getId(), q.getCorporateId(), q.getPair(), q.baseCurrency(), q.quoteCurrency(),
                    q.getSide(), q.getInstrument(), q.getAmount(), q.getValueDate(), q.getWindowStart(), q.getPurpose(),
                    q.getExposureRef(), q.getStatus(), q.getStatusReason(), q.getMidRate(), q.getForwardPoints(),
                    q.getSpreadBps(), q.getClientRate(), q.getCounterAmount(), q.getDealer(), q.getQuotedAt(),
                    q.getValidUntil(), remaining, q.getLimitCharge(), q.getLimitCurrency(), q.getDealId());
        }
    }

    public record DealView(
            UUID id, String dealRef, UUID corporateId, UUID quoteRequestId, Instrument instrument, String pair,
            Side side, String buyCurrency, BigDecimal buyAmount, String sellCurrency, BigDecimal sellAmount,
            BigDecimal rate, BigDecimal midRate, BigDecimal forwardPoints, BigDecimal spreadBps, LocalDate tradeDate,
            LocalDate valueDate, LocalDate windowStart, DealStatus status, BigDecimal limitCharge,
            String limitCurrency, String purpose, String exposureRef, LocalDateTime createdAt) {

        static DealView of(FxDeal d) {
            return new DealView(d.getId(), d.getDealRef(), d.getCorporateId(), d.getQuoteRequestId(), d.getInstrument(),
                    d.getPair(), d.getSide(), d.getBuyCurrency(), d.getBuyAmount(), d.getSellCurrency(),
                    d.getSellAmount(), d.getRate(), d.getMidRate(), d.getForwardPoints(), d.getSpreadBps(),
                    d.getTradeDate(), d.getValueDate(), d.getWindowStart(), d.getStatus(), d.getLimitCharge(),
                    d.getLimitCurrency(), d.getPurpose(), d.getExposureRef(), d.getCreatedAt());
        }
    }

    /** Shaped for the shared EventTimeline component: an action, who did it, the detail, and when. */
    public record EventView(String action, String actor, String details, LocalDateTime timestamp) {

        static EventView of(FxDealEvent e) {
            String action = e.getFromStatus() == null ? e.getToStatus() : e.getFromStatus() + " to " + e.getToStatus();
            return new EventView((e.getEntityType().equals(FxDealEvent.QUOTE) ? "Quote " : "Deal ") + action,
                    e.getActor(), e.getDetail(), e.getCreatedAt());
        }
    }

    public record LimitView(UUID corporateId, String currency, BigDecimal limit, BigDecimal used, BigDecimal headroom) {

        static LimitView of(FxLimitService.Position p) {
            return new LimitView(p.corporateId(), p.currency(), p.limit(), p.used(), p.headroom());
        }
    }

    public record Confirmation(String dealRef, String text) {
    }
}
