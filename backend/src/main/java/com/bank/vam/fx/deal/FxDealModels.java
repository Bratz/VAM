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

    /**
     * A deal as the blotter shows it. {@code mtm*} is the live mark against today's market and is absent when
     * the market cannot price the deal. {@code remainingAmount} is what is still undrawn, in the pair's base currency.
     */
    public record DealView(
            UUID id, String dealRef, UUID corporateId, UUID quoteRequestId, Instrument instrument, String pair,
            Side side, String buyCurrency, BigDecimal buyAmount, String sellCurrency, BigDecimal sellAmount,
            BigDecimal rate, BigDecimal midRate, BigDecimal forwardPoints, BigDecimal spreadBps, LocalDate tradeDate,
            LocalDate valueDate, LocalDate windowStart, DealStatus status, BigDecimal limitCharge,
            String limitCurrency, String purpose, String exposureRef, LocalDateTime createdAt,
            ConfirmationStatus confirmationStatus, LocalDateTime confirmationIssuedAt, LocalDateTime confirmedAt,
            String confirmedBy, String disputeReason, boolean confirmationOverdue,
            BigDecimal baseAmount, BigDecimal drawnAmount, BigDecimal remainingAmount, UUID rolledFromId,
            BigDecimal closeRate, BigDecimal realisedPnl, LocalDateTime settledAt,
            BigDecimal mtmQuote, String mtmQuoteCurrency, BigDecimal mtmHome, String mtmHomeCurrency) {

        static DealView of(FxDeal d, java.util.Optional<FxMtmService.Mark> mark, boolean overdue) {
            return new DealView(d.getId(), d.getDealRef(), d.getCorporateId(), d.getQuoteRequestId(), d.getInstrument(),
                    d.getPair(), d.getSide(), d.getBuyCurrency(), d.getBuyAmount(), d.getSellCurrency(),
                    d.getSellAmount(), d.getRate(), d.getMidRate(), d.getForwardPoints(), d.getSpreadBps(),
                    d.getTradeDate(), d.getValueDate(), d.getWindowStart(), d.getStatus(), d.getLimitCharge(),
                    d.getLimitCurrency(), d.getPurpose(), d.getExposureRef(), d.getCreatedAt(),
                    d.getConfirmationStatus(), d.getConfirmationIssuedAt(), d.getConfirmedAt(), d.getConfirmedBy(),
                    d.getDisputeReason(), overdue, d.baseAmount(), d.getDrawnAmount(), d.remainingBase(),
                    d.getRolledFromId(), d.getCloseRate(), d.getRealisedPnl(), d.getSettledAt(),
                    mark.map(FxMtmService.Mark::mtmQuote).orElse(null), mark.map(FxMtmService.Mark::quoteCurrency).orElse(null),
                    mark.map(FxMtmService.Mark::mtmHome).orElse(null), mark.map(FxMtmService.Mark::homeCurrency).orElse(null));
        }
    }

    public record SettlementView(UUID id, SettlementKind kind, LocalDate valueDate, String buyCurrency,
                                 BigDecimal buyAmount, String sellCurrency, BigDecimal sellAmount,
                                 SettlementStatus status, String reason, LocalDateTime settledAt) {

        static SettlementView of(FxSettlement s) {
            return new SettlementView(s.getId(), s.getKind(), s.getValueDate(), s.getBuyCurrency(), s.getBuyAmount(),
                    s.getSellCurrency(), s.getSellAmount(), s.getStatus(), s.getReason(), s.getSettledAt());
        }
    }

    public record MtmPoint(LocalDate asOf, BigDecimal rateUsed, BigDecimal mtmQuote, String quoteCurrency,
                           BigDecimal mtmHome, String homeCurrency) {

        static MtmPoint of(FxMtm m) {
            return new MtmPoint(m.getAsOf(), m.getRateUsed(), m.getMtmQuote(), m.getQuoteCurrency(), m.getMtmHome(),
                    m.getHomeCurrency());
        }
    }

    /** Shaped for the shared EventTimeline component: an action, who did it, the detail, and when. */
    public record EventView(String action, String actor, String details, LocalDateTime timestamp) {

        static EventView of(FxDealEvent e) {
            String prefix = e.getEntityType().equals(FxDealEvent.QUOTE) ? "Quote " : "Deal ";
            String action = e.getFromStatus() == null ? humanise(e.getToStatus())
                    : humanise(e.getFromStatus()) + " to " + humanise(e.getToStatus());
            return new EventView(e.getFromStatus() == null && e.getToStatus().contains("_") ? action : prefix + action,
                    e.getActor(), e.getDetail(), e.getCreatedAt());
        }
    }

    private static String humanise(String code) {
        String s = code.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public record LimitView(UUID corporateId, String currency, BigDecimal limit, BigDecimal used, BigDecimal headroom) {

        static LimitView of(FxLimitService.Position p) {
            return new LimitView(p.corporateId(), p.currency(), p.limit(), p.used(), p.headroom());
        }
    }

    public record Confirmation(String dealRef, String text) {
    }
}
