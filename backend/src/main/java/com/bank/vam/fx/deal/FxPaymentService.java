package com.bank.vam.fx.deal;

import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

/**
 * FX in payments. A payment in a currency other than its account's must be converted, by one of:
 * <ul>
 *   <li>a streamed price the payer saw ({@code priceId}), within the pair's streaming limits;</li>
 *   <li>a firm RFQ price requested for the payment ({@code quoteId}), above them;</li>
 *   <li>a deal booked earlier ({@code dealId}): a window forward the payment date falls in, or a forward due that day;</li>
 *   <li>for unattended channels such as files ({@code atMarket}), the live price, within the streaming limits only.</li>
 * </ul>
 * Without one the payment is refused: it used to be debited one for one in the account's currency. The money moves
 * through the payment: the account is debited the converted amount and the deal (or its part) is settled by the
 * payment, with nothing posted to the settlement accounts. A new deal is confirmed by submitting the payment.
 * Call {@link #convert} before the payment's ledger legs and {@link #settle} after, in one transaction.
 */
@Service
@RequiredArgsConstructor
public class FxPaymentService {

    /** How to convert. At most one of the ids, or {@code atMarket}. */
    public record Instruction(UUID priceId, UUID quoteId, UUID dealId, boolean atMarket) {

        public static final Instruction AT_MARKET = new Instruction(null, null, null, true);

        boolean none() {
            return priceId == null && quoteId == null && dealId == null && !atMarket;
        }
    }

    /** The account is debited {@code debitAmount}; the beneficiary receives {@code instructedAmount}. */
    public record Conversion(BigDecimal instructedAmount, String instructedCurrency, BigDecimal debitAmount,
                             String debitCurrency, BigDecimal rate, FxDeal deal, BigDecimal baseUsed) {
    }

    private final FxStreamService stream;
    private final FxQuoteService quotes;
    private final FxConfirmationService confirmations;
    private final FxSettlementService settlement;
    private final FxDealRepository deals;

    /**
     * Null when the payment is in the account's own currency. Otherwise books or takes the FX and says what to debit.
     *
     * @param currency the payment's currency; null or blank means the account's
     */
    @Transactional
    public Conversion convert(VirtualAccount source, String currency, BigDecimal amount, Instruction fx) {
        String accountCcy = source.getCurrencyCode();
        String ccy = currency == null || currency.isBlank() ? accountCcy : currency.trim().toUpperCase(Locale.ROOT);
        if (ccy.equals(accountCcy)) {
            // "At market" is what unattended channels (bulk rows, files) send for every row: convert if needed. A
            // price, quote or deal named for a payment that needs none is a mistake, and refused.
            if (fx != null && !fx.none() && !fx.atMarket()) {
                throw invalid("The payment is in the account's own currency (" + ccy + "), so there is nothing to convert");
            }
            return null;
        }
        if (fx == null || fx.none()) {
            throw new BusinessException("FX_PAYMENT_NEEDS_RATE", "The payment is in " + ccy + " but " + label(source) + " holds "
                    + accountCcy + ": lock a rate or use a booked FX deal to convert it");
        }
        if (fx.dealId() != null) {
            return fromDeal(source, ccy, amount, fx.dealId());
        }
        if (source.getOwningEntityId() == null) {
            throw invalid(label(source) + " belongs to no legal entity, and an FX deal is booked for one");
        }
        String pair = ccy + "/" + accountCcy;
        FxQuoteService.Command wanted = new FxQuoteService.Command(source.getCorporateId(), source.getOwningEntityId(), pair,
                Side.BUY, Instrument.SPOT, amount, null, null, "Payment conversion", null, true);

        FxQuoteRequest q;
        if (fx.quoteId() != null) {
            q = quotes.get(fx.quoteId());
            if (!Boolean.TRUE.equals(q.getForPayment()) || !q.getPair().equals(pair) || q.getSide() != Side.BUY
                    || q.getAmount().compareTo(amount) != 0 || !source.getCorporateId().equals(q.getCorporateId())) {
                throw invalid("That price was for a different payment; ask for a new one");
            }
        } else {
            FxStreamService.Issued shown;
            if (fx.priceId() != null) {
                shown = stream.take(fx.priceId());
                FxQuoteService.Command c = shown.command();
                if (!c.pair().equals(pair) || c.amount().compareTo(amount) != 0 || c.instrument() != Instrument.SPOT
                        || !source.getCorporateId().equals(c.corporateId()) || !source.getOwningEntityId().equals(c.entityId())) {
                    throw invalid("That price was for a different payment; use the one showing now");
                }
            } else {
                FxStreamService.StreamPrice p = stream.price(wanted);
                if (!p.streamable()) {
                    throw new BusinessException("FX_PAYMENT_NEEDS_DEAL", p.reason() + ", so a payment sent without a rate "
                            + "cannot be converted at the live price; book an FX deal for it and pay from the screen");
                }
                shown = stream.take(p.priceId());
            }
            q = quotes.request(wanted, shown.buy());
        }
        if (q.getStatus() != QuoteStatus.QUOTED) {
            throw new BusinessException("FX_PAYMENT_DECLINED", q.getStatusReason() != null ? q.getStatusReason()
                    : "The price is no longer available (" + q.getStatus() + "); get a new one");
        }
        FxDeal deal = quotes.accept(q.getId(), true);
        confirmations.affirm(deal.getId());
        return new Conversion(amount, ccy, deal.getSellAmount(), accountCcy, deal.getRate(), deal, deal.baseAmount());
    }

    /**
     * What the payment would debit, booking nothing: at the deal's rate, at the firm price's, else at the live price
     * (indicative: it moves until the payment is sent). Null in the account's own currency. Refused as {@link #convert}
     * would refuse it, so the payer sees why before sending.
     */
    @Transactional(readOnly = true)
    public Conversion preview(VirtualAccount source, String currency, BigDecimal amount, Instruction fx) {
        String accountCcy = source.getCurrencyCode();
        String ccy = currency == null || currency.isBlank() ? accountCcy : currency.trim().toUpperCase(Locale.ROOT);
        if (ccy.equals(accountCcy)) {
            return null;
        }
        if (fx != null && fx.dealId() != null) {
            return fromDeal(source, ccy, amount, fx.dealId());
        }
        if (fx != null && fx.quoteId() != null) {
            FxQuoteRequest q = quotes.get(fx.quoteId());
            return new Conversion(amount, ccy, q.getCounterAmount(), accountCcy, q.getClientRate(), null, null);
        }
        if (source.getOwningEntityId() == null) {
            throw invalid(label(source) + " belongs to no legal entity, and an FX deal is booked for one");
        }
        FxStreamService.StreamPrice p = stream.price(new FxQuoteService.Command(source.getCorporateId(), source.getOwningEntityId(),
                ccy + "/" + accountCcy, Side.BUY, Instrument.SPOT, amount, null, null, "Payment conversion", null, true));
        if (!p.streamable()) {
            throw new BusinessException("FX_PAYMENT_NEEDS_QUOTE", p.reason() + ": ask for a price for this payment");
        }
        return new Conversion(amount, ccy, p.buy().counterAmount(), accountCcy, p.buy().rate(), null, null);
    }

    /** After the payment's ledger legs: settles the deal (or its part) by the payment and notes the FX on it. */
    @Transactional
    public void settle(Conversion cv, Transaction payment, VirtualAccount source) {
        settlement.paidByPayment(cv.deal(), cv.baseUsed(), payment.getReferenceNumber(), label(source));
        payment.setInstructedAmount(cv.instructedAmount());
        payment.setInstructedCurrency(cv.instructedCurrency());
        payment.setFxRate(cv.rate());
        payment.setFxDealRef(cv.deal().getDealRef());
    }

    /** A window forward the payment date falls in, or a forward due today; the payment takes part or all of it. */
    private Conversion fromDeal(VirtualAccount source, String ccy, BigDecimal amount, UUID dealId) {
        FxDeal d = deals.findById(dealId).orElseThrow(() -> new ResourceNotFoundException("FX deal not found: " + dealId));
        String accountCcy = source.getCurrencyCode();
        LocalDate today = LocalDate.now();
        if (!source.getCorporateId().equals(d.getCorporateId())) {
            throw invalid(d.getDealRef() + " is not a deal of this corporate");
        }
        if (!d.getBuyCurrency().equals(ccy) || !d.getSellCurrency().equals(accountCcy)) {
            throw invalid(d.getDealRef() + " buys " + d.getBuyCurrency() + " and sells " + d.getSellCurrency()
                    + "; this payment needs " + ccy + " bought with " + accountCcy);
        }
        if (d.getStatus() != DealStatus.ACTIVE && d.getStatus() != DealStatus.PARTIALLY_DRAWN) {
            throw invalid(d.getDealRef() + " is " + d.getStatus().name().toLowerCase(Locale.ROOT).replace('_', ' ') + " and cannot pay");
        }
        if (d.getConfirmationStatus() != ConfirmationStatus.AFFIRMED) {
            throw invalid("Confirm " + d.getDealRef() + " before paying with it");
        }
        boolean usable = d.getInstrument() == Instrument.WINDOW
                ? !today.isBefore(d.getWindowStart()) && !today.isAfter(d.getValueDate())
                : d.getInstrument() == Instrument.FORWARD && d.getValueDate().equals(today);
        if (!usable) {
            throw invalid(d.getInstrument() == Instrument.WINDOW
                    ? d.getDealRef() + " can be drawn from " + d.getWindowStart() + " to " + d.getValueDate() + ", not today"
                    : d.getDealRef() + " can pay on its value date, " + d.getValueDate() + ", only");
        }
        // ponytail: a deal whose base is the account's currency is drawn at amount / rate, so the bought leg may
        // differ from the payment by a cent; the payment pays exactly what was asked.
        BigDecimal base = d.baseCurrency().equals(ccy) ? amount : amount.divide(d.getRate(), 2, RoundingMode.HALF_UP);
        if (base.compareTo(d.remainingBase()) > 0) {
            throw invalid(d.getDealRef() + " has " + d.remainingBase().toPlainString() + " " + d.baseCurrency() + " left, less than this payment needs");
        }
        BigDecimal[] legs = settlement.legsFor(d, base);
        return new Conversion(amount, ccy, legs[1], accountCcy, d.getRate(), d, base);
    }

    private static String label(VirtualAccount v) {
        return v.getVaNumber() != null ? v.getVaNumber() : "the account";
    }

    private static BusinessException invalid(String message) {
        return new BusinessException("FX_PAYMENT_INVALID", message);
    }
}
