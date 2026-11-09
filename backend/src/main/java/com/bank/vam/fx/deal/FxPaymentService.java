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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * FX in payments. A payment in a currency other than its account's is converted by the corporate's outstanding deals
 * that fit it first, then, for whatever they leave, one of:
 * <ul>
 *   <li>a streamed price the payer saw ({@code priceId}), within the pair's streaming limits;</li>
 *   <li>a firm RFQ price requested for that amount ({@code quoteId}), above them;</li>
 *   <li>for unattended channels such as files ({@code atMarket}), the live price, within the streaming limits only.</li>
 * </ul>
 * A deal fits a payment when it is the paying entity's, confirmed, buys the payment currency and sells the account's,
 * has something left, and can deliver today: a window open today, a forward due today or later (taken early at the
 * deal rate as booked), or a spot not yet settled. Several are used soonest value date first. The screen sends the
 * deals the payer kept ({@code draws}); unattended callers get them chosen by that rule.
 *
 * <p>Without FX a payment in another currency is refused: it used to be debited one for one. The money moves through
 * the payment: the account is debited the converted amount and each deal's part is settled by the payment (a row in
 * {@code fx_settlement} carrying the payment's reference: the deal's utilisation), with nothing posted to the
 * settlement accounts; what is left of a forward or spot still settles through them on its value date. Call
 * {@link #convert} before the payment's ledger legs and {@link #settle} after, in one transaction.
 */
@Service
@RequiredArgsConstructor
public class FxPaymentService {

    /** Part of a payment taken from a deal, in the payment's currency; a null amount takes what is still needed. */
    public record Draw(UUID dealId, BigDecimal amount) {
    }

    /** How to convert: deals to draw on, then for the rest at most one of the ids, or {@code atMarket}. */
    public record Instruction(UUID priceId, UUID quoteId, List<Draw> draws, boolean atMarket) {

        public static final Instruction AT_MARKET = new Instruction(null, null, List.of(), true);

        public Instruction {
            draws = draws == null ? List.of() : List.copyOf(draws);
        }

        /** From the request fields of a payment channel; {@code dealId} is shorthand for one deal taking what it can. */
        public static Instruction of(UUID priceId, UUID quoteId, UUID dealId, List<Draw> deals, Boolean atMarket) {
            List<Draw> draws = new ArrayList<>(deals == null ? List.of() : deals);
            if (dealId != null && draws.stream().noneMatch(d -> dealId.equals(d.dealId()))) {
                draws.add(new Draw(dealId, null));
            }
            return new Instruction(priceId, quoteId, draws, Boolean.TRUE.equals(atMarket));
        }

        boolean none() {
            return priceId == null && quoteId == null && draws.isEmpty() && !atMarket;
        }
    }

    /** Where one part of the payment's FX comes from. */
    public enum Source { DEAL, LIVE, FIRM }

    /** One part of the conversion. {@code deal} is null only in a preview of a live or firm price not yet booked. */
    public record Part(Source source, FxDeal deal, BigDecimal base, BigDecimal paid, BigDecimal debit, BigDecimal rate) {
    }

    /**
     * The account is debited {@code debitAmount}; the beneficiary receives {@code instructedAmount}. {@code rate} and
     * {@code pair} are the one deal's when a single part converts it, else the blended rate, account currency per one
     * unit of the payment's.
     */
    public record Conversion(BigDecimal instructedAmount, String instructedCurrency, BigDecimal debitAmount,
                             String debitCurrency, BigDecimal rate, String pair, List<Part> parts) {

        /** The first deal, for callers that show one. */
        public FxDeal deal() {
            return parts.stream().map(Part::deal).filter(Objects::nonNull).findFirst().orElse(null);
        }

        /** "FXD-1", or "FXD-1 +2" when more deals were used (the payment's column holds one reference). */
        public String dealRefs() {
            List<String> refs = parts.stream().map(Part::deal).filter(Objects::nonNull).map(FxDeal::getDealRef).toList();
            return refs.isEmpty() ? null : refs.get(0) + (refs.size() > 1 ? " +" + (refs.size() - 1) : "");
        }

        public boolean indicative() {
            return parts.stream().anyMatch(p -> p.source() == Source.LIVE && p.deal() == null);
        }
    }

    /** A deal that fits a payment, what it can still deliver in the payment's currency, and what it would cover. */
    public record Fit(UUID dealId, String dealRef, Instrument instrument, LocalDate valueDate, String pair, BigDecimal rate,
                      BigDecimal available, BigDecimal covers, BigDecimal debit) {
    }

    /** The deals that fit, in the order they are used, and what is left for a rate or a firm price. */
    public record Plan(String currency, String accountCurrency, BigDecimal amount, List<Fit> deals, BigDecimal rest) {
    }

    /** One line of a batch: the paying account and what it pays. */
    public record BatchRow(VirtualAccount source, String currency, BigDecimal amount) {
    }

    /** How to pay one line of a batch, or why it waits for FX ({@code instruction} is then null). */
    public record BatchFx(Instruction instruction, String held) {
    }

    private static final EnumSet<DealStatus> DRAWABLE = EnumSet.of(DealStatus.ACTIVE, DealStatus.PARTIALLY_DRAWN, DealStatus.PENDING_SETTLEMENT);

    private final FxStreamService stream;
    private final FxQuoteService quotes;
    private final FxConfirmationService confirmations;
    private final FxSettlementService settlement;
    private final FxDealRepository deals;

    /**
     * The deals that fit a payment, soonest value date first, each covering what it can until the amount is met.
     * {@code use}, when given, limits them to those deals (the ones the payer kept ticked).
     */
    @Transactional(readOnly = true)
    public Plan plan(VirtualAccount source, String currency, BigDecimal amount, Collection<UUID> use) {
        String accountCcy = source.getCurrencyCode();
        String ccy = currencyOf(source, currency);
        List<Fit> fits = new ArrayList<>();
        BigDecimal left = amount;
        if (!ccy.equals(accountCcy)) {
            for (FxDeal d : fitting(source, ccy)) {
                BigDecimal available = available(d, ccy);
                boolean chosen = use == null || use.contains(d.getId());
                BigDecimal covers = chosen ? available.min(left) : BigDecimal.ZERO;
                BigDecimal debit = covers.signum() > 0 ? settlement.legsFor(d, toBase(d, ccy, covers))[1] : BigDecimal.ZERO;
                fits.add(new Fit(d.getId(), d.getDealRef(), d.getInstrument(), d.getValueDate(), d.getPair(), d.getRate(), available, covers, debit));
                left = left.subtract(covers);
            }
        }
        return new Plan(ccy, accountCcy, amount, fits, ccy.equals(accountCcy) ? BigDecimal.ZERO : left);
    }

    /**
     * Null when the payment is in the account's own currency. Otherwise draws on the deals, books or takes the FX for
     * the rest, and says what to debit.
     *
     * @param currency the payment's currency; null or blank means the account's
     */
    @Transactional
    public Conversion convert(VirtualAccount source, String currency, BigDecimal amount, Instruction fx) {
        String accountCcy = source.getCurrencyCode();
        String ccy = currencyOf(source, currency);
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
        List<Draw> draws = fx.draws();
        if (draws.isEmpty() && fx.atMarket() && fx.priceId() == null && fx.quoteId() == null) {
            draws = plan(source, ccy, amount, null).deals().stream()
                    .filter(f -> f.covers().signum() > 0).map(f -> new Draw(f.dealId(), f.covers())).toList();
        }
        List<Part> parts = new ArrayList<>();
        BigDecimal left = amount;
        for (Draw draw : draws) {
            if (left.signum() == 0) {
                break;
            }
            FxDeal d = deals.findById(draw.dealId()).orElseThrow(() -> new ResourceNotFoundException("FX deal not found: " + draw.dealId()));
            check(d, source, ccy);
            BigDecimal paid = draw.amount() == null ? left.min(available(d, ccy)) : draw.amount().min(left);
            if (paid.compareTo(available(d, ccy)) > 0) {
                throw invalid(d.getDealRef() + " has " + available(d, ccy).toPlainString() + " " + ccy + " left, less than the "
                        + paid.toPlainString() + " asked of it");
            }
            BigDecimal base = toBase(d, ccy, paid);
            parts.add(new Part(Source.DEAL, d, base, paid, settlement.legsFor(d, base)[1], d.getRate()));
            left = left.subtract(paid);
        }
        if (left.signum() > 0) {
            if (fx.priceId() == null && fx.quoteId() == null && !fx.atMarket()) {
                throw new BusinessException("FX_PAYMENT_NOT_COVERED", "The deals cover " + amount.subtract(left).toPlainString() + " of "
                        + ccy + " " + amount.toPlainString() + ": lock a rate for the remaining " + left.toPlainString());
            }
            parts.add(book(source, ccy, left, fx));
        }
        return conversion(amount, ccy, accountCcy, parts);
    }

    /**
     * What the payment would debit, booking nothing: the deals at their rates, the rest at the firm price or else the
     * live price (indicative: it moves until the payment is sent). Null in the account's own currency. Refused as
     * {@link #convert} would refuse it, so the payer sees why before sending.
     */
    @Transactional(readOnly = true)
    public Conversion preview(VirtualAccount source, String currency, BigDecimal amount, Instruction fx) {
        String accountCcy = source.getCurrencyCode();
        String ccy = currencyOf(source, currency);
        if (ccy.equals(accountCcy)) {
            return null;
        }
        List<Part> parts = new ArrayList<>();
        BigDecimal left = amount;
        for (Draw draw : fx == null ? List.<Draw>of() : fx.draws()) {
            FxDeal d = deals.findById(draw.dealId()).orElseThrow(() -> new ResourceNotFoundException("FX deal not found: " + draw.dealId()));
            check(d, source, ccy);
            BigDecimal paid = (draw.amount() == null ? left : draw.amount().min(left)).min(available(d, ccy));
            if (paid.signum() > 0) {
                BigDecimal base = toBase(d, ccy, paid);
                parts.add(new Part(Source.DEAL, d, base, paid, settlement.legsFor(d, base)[1], d.getRate()));
                left = left.subtract(paid);
            }
        }
        if (left.signum() > 0) {
            if (fx != null && fx.quoteId() != null) {
                FxQuoteRequest q = quotes.get(fx.quoteId());
                parts.add(new Part(Source.FIRM, null, null, left, q.getCounterAmount(), q.getClientRate()));
            } else {
                FxStreamService.StreamPrice p = livePrice(source, ccy, left);
                if (!p.streamable()) {
                    throw new BusinessException("FX_PAYMENT_NEEDS_QUOTE", p.reason() + ": ask for a price for this payment");
                }
                parts.add(new Part(Source.LIVE, null, null, left, p.buy().counterAmount(), p.buy().rate()));
            }
        }
        return conversion(amount, ccy, accountCcy, parts);
    }

    /** After the payment's ledger legs: settles each deal's part by the payment and notes the FX on it. */
    @Transactional
    public void settle(Conversion cv, Transaction payment, VirtualAccount source) {
        for (Part p : cv.parts()) {
            settlement.paidByPayment(p.deal(), p.base(), payment.getReferenceNumber(), payment.getId(), label(source));
        }
        payment.setInstructedAmount(cv.instructedAmount());
        payment.setInstructedCurrency(cv.instructedCurrency());
        payment.setFxRate(cv.rate());
        payment.setFxDealRef(cv.dealRefs());
    }

    /**
     * FX for a batch (a bulk payment, a file): per paying entity and currency pair, the batch's total is covered by
     * the deals that fit, soonest value date first, and ONE deal is booked at the live price for whatever they leave,
     * when that is within the streaming limit (so a large batch split into small lines cannot dodge it). The lines
     * then draw on those deals in order. A line the deals cannot cover waits for FX, with the reason. Lines in their
     * account's currency, or whose account belongs to no entity, are left to {@link #convert} at market.
     * Commit this before paying the lines: they draw on the deal it books.
     */
    @Transactional
    public List<BatchFx> prepareBatch(List<BatchRow> rows) {
        List<BatchFx> out = new ArrayList<>();
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            BatchRow r = rows.get(i);
            out.add(new BatchFx(Instruction.AT_MARKET, null));
            String ccy = currencyOf(r.source(), r.currency());
            if (!ccy.equals(r.source().getCurrencyCode()) && r.source().getOwningEntityId() != null && r.amount() != null && r.amount().signum() > 0) {
                groups.computeIfAbsent(r.source().getOwningEntityId() + "|" + ccy + "|" + r.source().getCurrencyCode(), k -> new ArrayList<>()).add(i);
            }
        }
        for (List<Integer> group : groups.values()) {
            VirtualAccount first = rows.get(group.get(0)).source();
            String ccy = currencyOf(first, rows.get(group.get(0)).currency());
            BigDecimal total = group.stream().map(i -> rows.get(i).amount()).reduce(BigDecimal.ZERO, BigDecimal::add);

            Map<UUID, BigDecimal> pool = new LinkedHashMap<>();
            BigDecimal covered = BigDecimal.ZERO;
            for (FxDeal d : fitting(first, ccy)) {
                if (covered.compareTo(total) >= 0) {
                    break;
                }
                BigDecimal a = available(d, ccy);
                pool.put(d.getId(), a);
                covered = covered.add(a);
            }
            BigDecimal rest = total.subtract(covered);
            String waiting = null;
            if (rest.signum() > 0) {
                FxStreamService.StreamPrice p = livePrice(first, ccy, rest);
                if (p.streamable()) {
                    FxDeal booked = bookAt(first, ccy, rest, stream.take(p.priceId()));
                    pool.put(booked.getId(), rest);
                } else {
                    waiting = "Waiting for FX: after the deals that fit, " + ccy + " " + rest.toPlainString() + " of this batch is left to convert, and "
                            + lower(p.reason()) + ". Book a deal buying " + ccy + " with " + first.getCurrencyCode()
                            + " for it (or get a firm price), then release these lines";
                }
            }
            for (int i : group) {
                BigDecimal need = rows.get(i).amount();
                List<Draw> draws = new ArrayList<>();
                Map<UUID, BigDecimal> after = new LinkedHashMap<>(pool);
                for (Map.Entry<UUID, BigDecimal> e : after.entrySet()) {
                    if (need.signum() == 0) {
                        break;
                    }
                    BigDecimal take = e.getValue().min(need);
                    if (take.signum() > 0) {
                        draws.add(new Draw(e.getKey(), take));
                        e.setValue(e.getValue().subtract(take));
                        need = need.subtract(take);
                    }
                }
                if (need.signum() == 0) {
                    pool = after;
                    out.set(i, new BatchFx(new Instruction(null, null, draws, false), null));
                } else {
                    out.set(i, new BatchFx(null, waiting != null ? waiting : "Waiting for FX: no deal is left to convert this line"));
                }
            }
        }
        return out;
    }

    /** The paying entity's deals that can deliver the payment currency against the account's today, soonest first. */
    private List<FxDeal> fitting(VirtualAccount source, String ccy) {
        if (source.getOwningEntityId() == null) {
            return List.of();
        }
        return deals.findByCorporateIdAndStatusIn(source.getCorporateId(), DRAWABLE).stream()
                .filter(d -> source.getOwningEntityId().equals(d.getEntityId()))
                .filter(d -> ccy.equals(d.getBuyCurrency()) && source.getCurrencyCode().equals(d.getSellCurrency()))
                .filter(d -> d.getConfirmationStatus() == ConfirmationStatus.AFFIRMED)
                .filter(d -> notToday(d) == null && available(d, ccy).signum() > 0)
                .sorted(Comparator.comparing(FxDeal::getValueDate).thenComparing(FxDeal::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /** Why a deal named for this payment cannot pay it. */
    private void check(FxDeal d, VirtualAccount source, String ccy) {
        String accountCcy = source.getCurrencyCode();
        if (!source.getCorporateId().equals(d.getCorporateId())) {
            throw invalid(d.getDealRef() + " is not a deal of this corporate");
        }
        if (d.getEntityId() != null && source.getOwningEntityId() != null && !d.getEntityId().equals(source.getOwningEntityId())) {
            throw invalid(d.getDealRef() + " is booked for another entity than the one paying");
        }
        if (!d.getBuyCurrency().equals(ccy) || !d.getSellCurrency().equals(accountCcy)) {
            throw invalid(d.getDealRef() + " buys " + d.getBuyCurrency() + " and sells " + d.getSellCurrency()
                    + "; this payment needs " + ccy + " bought with " + accountCcy);
        }
        if (!DRAWABLE.contains(d.getStatus())) {
            throw invalid(d.getDealRef() + " is " + d.getStatus().name().toLowerCase(Locale.ROOT).replace('_', ' ') + " and cannot pay");
        }
        if (d.getConfirmationStatus() != ConfirmationStatus.AFFIRMED) {
            throw invalid("Confirm " + d.getDealRef() + " before paying with it");
        }
        String why = notToday(d);
        if (why != null) {
            throw invalid(why);
        }
    }

    /** Null when the deal can deliver today: a window open today, a forward due today or later, a spot not yet settled. */
    private static String notToday(FxDeal d) {
        LocalDate today = LocalDate.now();
        return switch (d.getInstrument()) {
            case WINDOW -> !today.isBefore(d.getWindowStart()) && !today.isAfter(d.getValueDate()) ? null
                    : d.getDealRef() + " can be drawn from " + d.getWindowStart() + " to " + d.getValueDate() + ", not today";
            case FORWARD, SPOT -> !d.getValueDate().isBefore(today) ? null
                    : d.getDealRef() + " was due on " + d.getValueDate() + " and is settling through its settlement accounts";
        };
    }

    /** What the deal can still deliver, in the payment's currency. */
    private static BigDecimal available(FxDeal d, String ccy) {
        BigDecimal rem = d.remainingBase();
        return d.baseCurrency().equals(ccy) ? rem : rem.multiply(d.getRate()).setScale(2, RoundingMode.DOWN);
    }

    /**
     * The deal's base amount for {@code paid} of the payment's currency. ponytail: a deal whose base is the account's
     * currency is drawn at paid / rate, so its bought leg may differ from the payment by a cent; the payment pays
     * exactly what was asked, and a last draw never takes more than is left.
     */
    private static BigDecimal toBase(FxDeal d, String ccy, BigDecimal paid) {
        return d.baseCurrency().equals(ccy) ? paid : paid.divide(d.getRate(), 2, RoundingMode.HALF_UP).min(d.remainingBase());
    }

    /** Books a spot for {@code amount} at the price the payer saw, a firm price, or the live price. */
    private Part book(VirtualAccount source, String ccy, BigDecimal amount, Instruction fx) {
        if (source.getOwningEntityId() == null) {
            throw invalid(label(source) + " belongs to no legal entity, and an FX deal is booked for one");
        }
        String pair = ccy + "/" + source.getCurrencyCode();
        FxQuoteRequest q;
        if (fx.quoteId() != null) {
            q = quotes.get(fx.quoteId());
            if (!Boolean.TRUE.equals(q.getForPayment()) || !q.getPair().equals(pair) || q.getSide() != Side.BUY
                    || q.getAmount().compareTo(amount) != 0 || !source.getCorporateId().equals(q.getCorporateId())) {
                throw invalid("That price was for a different amount; ask for a new one for " + ccy + " " + amount.toPlainString());
            }
            FxDeal deal = accepted(q);
            return new Part(Source.FIRM, deal, deal.baseAmount(), amount, deal.getSellAmount(), deal.getRate());
        }
        FxStreamService.Issued shown;
        if (fx.priceId() != null) {
            shown = stream.take(fx.priceId());
            FxQuoteService.Command c = shown.command();
            if (!c.pair().equals(pair) || c.amount().compareTo(amount) != 0 || c.instrument() != Instrument.SPOT
                    || !source.getCorporateId().equals(c.corporateId()) || !source.getOwningEntityId().equals(c.entityId())) {
                throw invalid("That price was for a different payment; use the one showing now");
            }
        } else {
            FxStreamService.StreamPrice p = livePrice(source, ccy, amount);
            if (!p.streamable()) {
                throw new BusinessException("FX_PAYMENT_NEEDS_DEAL", p.reason() + ", so a payment sent without a rate "
                        + "cannot be converted at the live price; book an FX deal for it and pay from the screen");
            }
            shown = stream.take(p.priceId());
        }
        FxDeal deal = bookAt(source, ccy, amount, shown);
        return new Part(Source.LIVE, deal, deal.baseAmount(), amount, deal.getSellAmount(), deal.getRate());
    }

    private FxDeal bookAt(VirtualAccount source, String ccy, BigDecimal amount, FxStreamService.Issued shown) {
        return accepted(quotes.request(command(source, ccy, amount), shown.buy()));
    }

    /** A price for the payment, accepted and confirmed: submitting the payment is the corporate's confirmation. */
    private FxDeal accepted(FxQuoteRequest q) {
        if (q.getStatus() != QuoteStatus.QUOTED) {
            throw new BusinessException("FX_PAYMENT_DECLINED", q.getStatusReason() != null ? q.getStatusReason()
                    : "The price is no longer available (" + q.getStatus() + "); get a new one");
        }
        FxDeal deal = quotes.accept(q.getId(), true);
        confirmations.affirm(deal.getId());
        return deal;
    }

    private FxStreamService.StreamPrice livePrice(VirtualAccount source, String ccy, BigDecimal amount) {
        if (source.getOwningEntityId() == null) {
            throw invalid(label(source) + " belongs to no legal entity, and an FX deal is booked for one");
        }
        return stream.price(command(source, ccy, amount));
    }

    private static FxQuoteService.Command command(VirtualAccount source, String ccy, BigDecimal amount) {
        return new FxQuoteService.Command(source.getCorporateId(), source.getOwningEntityId(), ccy + "/" + source.getCurrencyCode(),
                Side.BUY, Instrument.SPOT, amount, null, null, "Payment conversion", null, true);
    }

    private static Conversion conversion(BigDecimal amount, String ccy, String accountCcy, List<Part> parts) {
        BigDecimal debit = parts.stream().map(Part::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        Part only = parts.size() == 1 ? parts.get(0) : null;
        String pair = only != null && only.deal() != null ? only.deal().getPair() : ccy + "/" + accountCcy;
        BigDecimal rate = only != null ? only.rate() : debit.divide(amount, 8, RoundingMode.HALF_UP);
        return new Conversion(amount, ccy, debit, accountCcy, rate, pair, List.copyOf(parts));
    }

    private static String currencyOf(VirtualAccount source, String currency) {
        return currency == null || currency.isBlank() ? source.getCurrencyCode() : currency.trim().toUpperCase(Locale.ROOT);
    }

    private static String lower(String s) {
        return s == null || s.isEmpty() ? "it is above the streaming limit" : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static String label(VirtualAccount v) {
        return v.getVaNumber() != null ? v.getVaNumber() : "the account";
    }

    private static BusinessException invalid(String message) {
        return new BusinessException("FX_PAYMENT_INVALID", message);
    }
}
