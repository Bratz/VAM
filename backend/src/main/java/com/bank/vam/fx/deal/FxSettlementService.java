package com.bank.vam.fx.deal;

import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Drawdowns and value-date settlement. Settling posts both legs: the sold currency is debited from the deal's
 * debit account and the bought currency credited to its credit account (home-bank accounts, see
 * {@link FxSettlementAccounts}). A deal settles only once its confirmation is affirmed, its accounts are usable, and
 * the corporate holds enough of the sold currency in total; the debit account itself may go below zero, which the
 * funding item reports. Otherwise it moves to SETTLEMENT_FAILED and is tried again on the next run.
 */
@Service
@RequiredArgsConstructor
public class FxSettlementService {

    /** What a run did. {@code notes} says why anything was blocked or failed. */
    public record RunResult(LocalDate asOf, int settled, int failed, int blocked, List<String> notes) {
    }

    private final FxDealRepository deals;
    private final FxSettlementRepository settlements;
    private final FxDealService dealService;
    private final HeldCash cash;
    private final FxEvents log;
    private final FxSettlementAccounts settlementAccounts;
    private final FxPosting posting;

    /** Takes part of a window forward. It settles on its own value date, which must fall inside the window. */
    @Transactional
    public FxSettlement drawdown(UUID dealId, BigDecimal amount, LocalDate valueDate) {
        FxDeal d = deals.findById(dealId).orElseThrow(() -> new ResourceNotFoundException("Deal not found: " + dealId));
        if (d.getInstrument() != Instrument.WINDOW) {
            throw invalid("Only a window forward can be drawn down");
        }
        if (d.getStatus() != DealStatus.ACTIVE && d.getStatus() != DealStatus.PARTIALLY_DRAWN) {
            throw invalid("A deal that is " + d.getStatus() + " cannot be drawn down");
        }
        if (d.getConfirmationStatus() != ConfirmationStatus.AFFIRMED) {
            throw invalid("Confirm the deal before drawing it down");
        }
        if (amount == null || amount.signum() <= 0 || amount.compareTo(d.remainingBase()) > 0) {
            throw invalid("Draw between 0.01 and " + d.remainingBase().toPlainString() + " " + d.baseCurrency());
        }
        if (valueDate == null || valueDate.isBefore(d.getWindowStart()) || valueDate.isAfter(d.getValueDate())
                || valueDate.isBefore(LocalDate.now()) || isWeekend(valueDate)) {
            throw invalid("The drawdown date must be a business day inside the window, "
                    + d.getWindowStart() + " to " + d.getValueDate() + ", and not in the past");
        }

        BigDecimal[] legs = legsFor(d, amount);
        FxSettlement s = settlement(d, SettlementKind.DRAWDOWN, valueDate, legs[0], legs[1]);
        d.setDrawnAmount(d.getDrawnAmount().add(amount));
        if (d.getStatus() == DealStatus.ACTIVE) {
            dealService.transition(d, DealStatus.PARTIALLY_DRAWN, "First drawdown taken");
        }
        log.note(d, "DRAWDOWN_SCHEDULED", "Drawdown of " + d.baseCurrency() + " " + amount.toPlainString()
                + " scheduled for " + valueDate + "; " + d.remainingBase().toPlainString() + " left in the window");
        return settlements.save(s);
    }

    /**
     * The bought and sold amounts for {@code base} of what is left of a deal. The last part takes exactly what is
     * left of each leg, so no rounding is lost across parts.
     */
    BigDecimal[] legsFor(FxDeal d, BigDecimal base) {
        List<FxSettlement> earlier = settlements.findByDealIdOrderByCreatedAtAsc(d.getId()).stream()
                .filter(s -> s.getKind() == SettlementKind.DRAWDOWN).toList();
        boolean last = base.compareTo(d.remainingBase()) == 0;
        return new BigDecimal[] {
                last ? d.getBuyAmount().subtract(sum(earlier, true)) : share(d.getBuyAmount(), base, d.baseAmount()),
                last ? d.getSellAmount().subtract(sum(earlier, false)) : share(d.getSellAmount(), base, d.baseAmount())};
    }

    /**
     * Part or all of a deal paid out as a payment ({@link FxPaymentService}): settled now, through the payment, so
     * nothing is posted to the settlement accounts. The row carries the payment, so a deal's rows with one are its
     * utilisation. What is left of a forward or spot still settles through the accounts on its value date, and the
     * deal keeps its status; a window moves to PARTIALLY_DRAWN as with any drawdown.
     */
    @Transactional
    public FxSettlement paidByPayment(FxDeal d, BigDecimal base, String paymentRef, UUID paymentId, String debitLabel) {
        BigDecimal[] legs = legsFor(d, base);
        boolean whole = base.compareTo(d.baseAmount()) == 0;
        FxSettlement s = settlement(d, whole ? SettlementKind.MATURITY : SettlementKind.DRAWDOWN, LocalDate.now(), legs[0], legs[1]);
        s.setStatus(SettlementStatus.SETTLED);
        s.setSettledAt(LocalDateTime.now());
        s.setDebitAccountLabel(debitLabel);
        s.setCreditAccountLabel("Paid out as payment " + paymentRef);
        s.setPaymentRef(paymentRef);
        s.setPaymentId(paymentId);
        d.setDrawnAmount(d.getDrawnAmount().add(base));
        log.note(d, "PAID_BY_PAYMENT", d.getBuyCurrency() + " " + legs[0].toPlainString() + " paid out as payment " + paymentRef
                + ", " + d.getSellCurrency() + " " + legs[1].toPlainString() + " debited from " + debitLabel
                + (d.remainingBase().signum() > 0 ? "; " + d.remainingBase().toPlainString() + " " + d.baseCurrency() + " left" : ""));
        if (d.remainingBase().signum() == 0) {
            d.setSettledAt(LocalDateTime.now());
            dealService.transition(d, DealStatus.SETTLED, "Paid out as payment " + paymentRef);
        } else if (d.getInstrument() == Instrument.WINDOW && d.getStatus() == DealStatus.ACTIVE) {
            dealService.transition(d, DealStatus.PARTIALLY_DRAWN, "Part paid out as payment " + paymentRef);
        }
        return settlements.save(s);
    }

    /**
     * Part or all of a deal used by a collection: the corporate received the deal's sold currency from a payer and
     * the account was credited the bought currency. Recorded like {@link #paidByPayment}, so it counts as utilisation.
     */
    @Transactional
    public FxSettlement receivedByCollection(FxDeal d, BigDecimal base, String creditRef, UUID creditId, String accountLabel) {
        BigDecimal[] legs = legsFor(d, base);
        boolean whole = base.compareTo(d.baseAmount()) == 0;
        FxSettlement s = settlement(d, whole ? SettlementKind.MATURITY : SettlementKind.DRAWDOWN, LocalDate.now(), legs[0], legs[1]);
        s.setStatus(SettlementStatus.SETTLED);
        s.setSettledAt(LocalDateTime.now());
        s.setDebitAccountLabel("Received by collection " + creditRef);
        s.setCreditAccountLabel(accountLabel);
        s.setPaymentRef(creditRef);
        s.setPaymentId(creditId);
        d.setDrawnAmount(d.getDrawnAmount().add(base));
        log.note(d, "USED_BY_COLLECTION", d.getSellCurrency() + " " + legs[1].toPlainString() + " collected by " + creditRef
                + ", " + d.getBuyCurrency() + " " + legs[0].toPlainString() + " credited to " + accountLabel
                + (d.remainingBase().signum() > 0 ? "; " + d.remainingBase().toPlainString() + " " + d.baseCurrency() + " left" : ""));
        if (d.remainingBase().signum() == 0) {
            d.setSettledAt(LocalDateTime.now());
            dealService.transition(d, DealStatus.SETTLED, "Used by collection " + creditRef);
        } else if (d.getInstrument() == Instrument.WINDOW && d.getStatus() == DealStatus.ACTIVE) {
            dealService.transition(d, DealStatus.PARTIALLY_DRAWN, "Part used by collection " + creditRef);
        }
        return settlements.save(s);
    }

    /**
     * Settles everything due on or before {@code asOf} (today when null). Passing a later date is how the
     * demo moves the clock forward; the simulated desk has no other way to reach a value date.
     */
    @Transactional
    public RunResult run(LocalDate asOf, UUID corporateId) {
        LocalDate day = asOf == null ? LocalDate.now() : asOf;
        List<FxDeal> candidates = new ArrayList<>(corporateId == null ? deals.findByStatusIn(DealStatus.OPEN)
                : deals.findByCorporateIdAndStatusIn(corporateId, DealStatus.OPEN));
        candidates.sort(Comparator.comparing(FxDeal::getValueDate).thenComparing(FxDeal::getCreatedAt));

        Map<String, BigDecimal> available = new HashMap<>();
        int[] counts = new int[3];
        List<String> notes = new ArrayList<>();

        for (FxDeal d : candidates) {
            if (d.getStatus() == DealStatus.BOOKED) {
                continue;
            }
            for (FxSettlement s : settlements.findByDealIdAndKindAndStatus(d.getId(), SettlementKind.DRAWDOWN, SettlementStatus.PENDING)) {
                if (!s.getValueDate().isAfter(day)) {
                    attempt(d, s, available, counts, notes);
                }
            }
            if (!d.getValueDate().isAfter(day) && d.remainingBase().signum() > 0) {
                BigDecimal rem = d.remainingBase();
                FxSettlement s = settlement(d, SettlementKind.MATURITY, d.getValueDate(),
                        share(d.getBuyAmount(), rem, d.baseAmount()), share(d.getSellAmount(), rem, d.baseAmount()));
                if (attempt(d, s, available, counts, notes)) {
                    d.setDrawnAmount(d.baseAmount());
                }
            }
            completeIfDone(d);
        }
        return new RunResult(day, counts[0], counts[1], counts[2], notes);
    }

    /** True when the item settled. */
    private boolean attempt(FxDeal d, FxSettlement s, Map<String, BigDecimal> available, int[] counts, List<String> notes) {
        if (d.getConfirmationStatus() != ConfirmationStatus.AFFIRMED) {
            counts[2]++;
            notes.add(d.getDealRef() + ": waiting for the client to confirm the trade (" + d.getConfirmationStatus() + ")");
            return false;
        }
        String why = accountProblem(d);
        String key = d.getCorporateId() + "|" + s.getSellCurrency();
        BigDecimal held = available.computeIfAbsent(key, k -> cash.of(d.getCorporateId(), s.getSellCurrency()));
        if (why == null && held.compareTo(s.getSellAmount()) < 0) {
            why = "Not enough " + s.getSellCurrency() + ": needs " + s.getSellAmount().toPlainString()
                    + ", holds " + held.toPlainString();
        }
        if (why == null) {
            available.put(key, held.subtract(s.getSellAmount()));
            String what = "FX " + s.getKind().name().toLowerCase(Locale.ROOT) + " " + d.getDealRef();
            BigDecimal after = posting.post(d.getSellAccountId(), s.getSellAmount().negate(), s.getValueDate(), d.getDealRef(),
                    what + ": " + s.getSellCurrency() + " sold");
            posting.post(d.getBuyAccountId(), s.getBuyAmount(), s.getValueDate(), d.getDealRef(), what + ": " + s.getBuyCurrency() + " bought");
            s.setDebitAccountId(d.getSellAccountId());
            s.setDebitAccountLabel(d.getSellAccountLabel());
            s.setCreditAccountId(d.getBuyAccountId());
            s.setCreditAccountLabel(d.getBuyAccountLabel());
            s.setStatus(SettlementStatus.SETTLED);
            s.setSettledAt(LocalDateTime.now());
            s.setReason(null);
            settlements.save(s);
            counts[0]++;
            log.note(d, "SETTLED_" + s.getKind(), s.getKind() + " settled: " + s.getSellCurrency() + " " + s.getSellAmount().toPlainString()
                    + " debited from " + d.getSellAccountLabel() + ", " + s.getBuyCurrency() + " " + s.getBuyAmount().toPlainString()
                    + " credited to " + d.getBuyAccountLabel()
                    + (after != null && after.signum() < 0 ? "; the debit account is now overdrawn at " + after.toPlainString() : ""));
            return true;
        }
        counts[1]++;
        notes.add(d.getDealRef() + ": " + why);
        s.setReason(why);
        if (d.getStatus() != DealStatus.SETTLEMENT_FAILED) {
            if (s.getKind() == SettlementKind.MATURITY) {
                s.setStatus(SettlementStatus.FAILED);
            }
            settlements.save(s);
            dealService.transition(d, DealStatus.SETTLEMENT_FAILED, why + "; will be tried again on the next run");
        } else if (s.getId() != null) {
            settlements.save(s);
        }
        return false;
    }

    /**
     * Why the deal's accounts cannot be posted to, or null. A deal booked before settlement accounts existed takes its
     * entity's accounts now.
     */
    private String accountProblem(FxDeal d) {
        if (d.getEntityId() == null) {
            return "the deal is not booked for an entity, so it has no settlement accounts";
        }
        if (d.getBuyAccountId() == null) {
            FxSettlementAccounts.Resolved r = settlementAccounts.resolve(d.getCorporateId(), d.getEntityId(), d.getBuyCurrency());
            if (!r.found()) {
                return r.reason();
            }
            d.setBuyAccountId(r.account().id());
            d.setBuyAccountLabel(r.account().label());
        }
        if (d.getSellAccountId() == null) {
            FxSettlementAccounts.Resolved r = settlementAccounts.resolve(d.getCorporateId(), d.getEntityId(), d.getSellCurrency());
            if (!r.found()) {
                return r.reason();
            }
            d.setSellAccountId(r.account().id());
            d.setSellAccountLabel(r.account().label());
        }
        String p = posting.problem(d.getSellAccountId(), d.getSellCurrency());
        return p != null ? p : posting.problem(d.getBuyAccountId(), d.getBuyCurrency());
    }

    /** A deal is settled once nothing is left to deliver and no drawdown is waiting. */
    private void completeIfDone(FxDeal d) {
        boolean open = d.getStatus() == DealStatus.ACTIVE || d.getStatus() == DealStatus.PARTIALLY_DRAWN
                || d.getStatus() == DealStatus.PENDING_SETTLEMENT || d.getStatus() == DealStatus.SETTLEMENT_FAILED;
        if (open && d.remainingBase().signum() == 0 && !settlements.existsByDealIdAndStatus(d.getId(), SettlementStatus.PENDING)) {
            d.setSettledAt(LocalDateTime.now());
            dealService.transition(d, DealStatus.SETTLED, "All legs settled");
        }
    }

    private FxSettlement settlement(FxDeal d, SettlementKind kind, LocalDate valueDate, BigDecimal buy, BigDecimal sell) {
        FxSettlement s = new FxSettlement();
        s.setDealId(d.getId());
        s.setCorporateId(d.getCorporateId());
        s.setKind(kind);
        s.setValueDate(valueDate);
        s.setBuyCurrency(d.getBuyCurrency());
        s.setBuyAmount(buy);
        s.setSellCurrency(d.getSellCurrency());
        s.setSellAmount(sell);
        return s;
    }

    private static BigDecimal share(BigDecimal leg, BigDecimal part, BigDecimal whole) {
        return leg.multiply(part).divide(whole, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal sum(List<FxSettlement> items, boolean buy) {
        return items.stream().map(s -> buy ? s.getBuyAmount() : s.getSellAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static boolean isWeekend(LocalDate d) {
        return d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException("FX_DRAWDOWN_INVALID", message);
    }
}
