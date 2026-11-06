package com.bank.vam.fx.deal;

import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.fx.ForwardPricer;
import com.bank.vam.fx.deal.SimulatedDealer.Price;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Early close-out and roll of a forward. Both are priced by the desk at the moment they run and the price is
 * not held firm: the preview shows what it would cost now, and running it prices again. The cash difference is
 * recorded against the deal (no balance is moved).
 *
 * <p>ponytail: a window or part-drawn deal closes or rolls for what is still undrawn, as a plain forward.
 * Partial close-out and early delivery are the next things to add.
 */
@Service
@RequiredArgsConstructor
public class FxLifecycleService {

    /** What closing the rest of a deal would cost at today's price. */
    public record CloseOutQuote(String dealRef, String pair, BigDecimal remainingBase, BigDecimal closeRate,
                                BigDecimal realisedPnl, String pnlCurrency, LocalDate pricedDate) {
    }

    /** The close-out, plus the price of the new forward that replaces it. */
    public record RollQuote(CloseOutQuote closeOut, LocalDate newValueDate, BigDecimal newRate,
                            BigDecimal newCounterAmount) {
    }

    public record RollResult(FxDeal closed, FxDeal replacement) {
    }

    private final FxDealRepository deals;
    private final FxSettlementRepository settlements;
    private final SimulatedDealer dealer;
    private final ForwardPricer forwards;
    private final FxDealService dealService;
    private final FxQuoteService quoteService;
    private final FxEvents log;

    /** Positive is in the corporate's favour: it closes a buy by selling higher, or a sell by buying lower. */
    static BigDecimal realisedPnl(Side original, BigDecimal remainingBase, BigDecimal contractedRate, BigDecimal closeRate) {
        BigDecimal gain = remainingBase.multiply(closeRate.subtract(contractedRate));
        return (original == Side.BUY ? gain : gain.negate()).setScale(2, RoundingMode.HALF_UP);
    }

    public CloseOutQuote closeOutPreview(UUID dealId) {
        FxDeal d = closeable(dealId);
        return priceClose(d);
    }

    @Transactional
    public FxDeal closeOut(UUID dealId) {
        FxDeal d = closeable(dealId);
        finish(d, priceClose(d), DealStatus.CLOSED_OUT, "Closed out");
        return d;
    }

    public RollQuote rollPreview(UUID dealId, LocalDate newValueDate) {
        FxDeal d = closeable(dealId);
        Price next = dealer.price(d.getPair(), d.getSide(), Instrument.FORWARD, d.remainingBase(), requireDate(newValueDate), null);
        return new RollQuote(priceClose(d), next.pricedDate(), next.clientRate(),
                next.counterAmount());
    }

    /** Closes the deal and books the replacement in one transaction: if the new one is refused, nothing changes. */
    @Transactional
    public RollResult roll(UUID dealId, LocalDate newValueDate) {
        FxDeal d = closeable(dealId);
        CloseOutQuote close = priceClose(d);
        BigDecimal remaining = d.remainingBase();
        finish(d, close, DealStatus.ROLLED, "Rolled");

        FxQuoteRequest q = quoteService.request(new FxQuoteService.Command(d.getCorporateId(), d.getEntityId(), d.getPair(), d.getSide(),
                Instrument.FORWARD, remaining, requireDate(newValueDate), null, "Roll of " + d.getDealRef(), d.getExposureRef()));
        if (q.getStatus() != QuoteStatus.QUOTED) {
            throw new BusinessException("FX_ROLL_DECLINED", "The replacement forward was not quoted: " + q.getStatusReason());
        }
        FxDeal replacement = quoteService.accept(q.getId());
        replacement.setRolledFromId(d.getId());
        deals.save(replacement);
        log.note(d, "ROLLED_INTO", "Replaced by " + replacement.getDealRef() + " for value " + replacement.getValueDate());
        return new RollResult(d, replacement);
    }

    private FxDeal closeable(UUID dealId) {
        FxDeal d = deals.findById(dealId).orElseThrow(() -> new ResourceNotFoundException("Deal not found: " + dealId));
        if (d.getInstrument() == Instrument.SPOT) {
            throw new BusinessException("FX_CLOSE_INVALID", "A spot deal settles in two days and cannot be closed out or rolled");
        }
        if (d.getStatus() != DealStatus.ACTIVE && d.getStatus() != DealStatus.PARTIALLY_DRAWN
                && d.getStatus() != DealStatus.SETTLEMENT_FAILED) {
            throw new BusinessException("FX_CLOSE_INVALID", "A deal that is " + d.getStatus() + " cannot be closed out or rolled");
        }
        if (d.getConfirmationStatus() == ConfirmationStatus.DISPUTED) {
            throw new BusinessException("FX_CLOSE_INVALID", "A disputed deal cannot be closed out until the dispute is resolved");
        }
        if (d.remainingBase().signum() <= 0) {
            throw new BusinessException("FX_CLOSE_INVALID", "Nothing is left undrawn on this deal");
        }
        if (settlements.existsByDealIdAndStatus(d.getId(), SettlementStatus.PENDING)) {
            throw new BusinessException("FX_CLOSE_INVALID", "A drawdown is waiting to settle; let it settle first");
        }
        return d;
    }

    /** The desk takes the other side: a bought deal is closed by selling, and the reverse. */
    private CloseOutQuote priceClose(FxDeal d) {
        BigDecimal remaining = d.remainingBase();
        Side opposite = d.getSide() == Side.BUY ? Side.SELL : Side.BUY;
        boolean atSpot = !d.getValueDate().isAfter(forwards.spotDate());
        Price p = dealer.price(d.getPair(), opposite, atSpot ? Instrument.SPOT : Instrument.FORWARD, remaining,
                d.getValueDate(), null);
        return new CloseOutQuote(d.getDealRef(), d.getPair(), remaining, p.clientRate(),
                realisedPnl(d.getSide(), remaining, d.getRate(), p.clientRate()), d.quoteCurrency(), p.pricedDate());
    }

    private void finish(FxDeal d, CloseOutQuote q, DealStatus to, String what) {
        d.setCloseRate(q.closeRate());
        d.setRealisedPnl(q.realisedPnl());
        d.setClosedAt(LocalDateTime.now());
        d.setDrawnAmount(d.baseAmount());

        FxSettlement s = new FxSettlement();
        s.setDealId(d.getId());
        s.setCorporateId(d.getCorporateId());
        s.setKind(SettlementKind.CLOSE_OUT);
        s.setValueDate(LocalDate.now());
        s.setBuyCurrency(q.pnlCurrency());
        s.setBuyAmount(q.realisedPnl().max(BigDecimal.ZERO));
        s.setSellCurrency(q.pnlCurrency());
        s.setSellAmount(q.realisedPnl().negate().max(BigDecimal.ZERO));
        s.setStatus(SettlementStatus.SETTLED);
        s.setSettledAt(LocalDateTime.now());
        settlements.save(s);

        String result = q.realisedPnl().signum() >= 0
                ? "client receives " + q.realisedPnl().toPlainString() : "client pays " + q.realisedPnl().negate().toPlainString();
        dealService.transition(d, to, what + " at " + q.closeRate().toPlainString() + " (" + result + " " + q.pnlCurrency()
                + ", recorded; no balance moved)");
    }

    private static LocalDate requireDate(LocalDate d) {
        if (d == null) {
            throw new BusinessException("FX_VALUE_DATE_INVALID", "A new value date is required");
        }
        return d;
    }
}
