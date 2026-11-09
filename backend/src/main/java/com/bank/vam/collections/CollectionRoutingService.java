package com.bank.vam.collections;

import com.bank.vam.collections.CustomerMandate.Period;
import com.bank.vam.collections.CustomerMandate.Status;
import com.bank.vam.collections.CustomerMandate.Type;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.entity.receivables.Receivable.ReceivableStatus;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.receivables.ReceivableRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * How a set of invoices would be collected: mandate first (an active mandate that allows the amount
 * and the date → direct debit), else Request to Pay, else left out with the reason.
 */
@Service
@RequiredArgsConstructor
public class CollectionRoutingService {

    /** ponytail: one lead time for every scheme; a per-scheme map when a scheme needs a different one. */
    static final int LEAD_BUSINESS_DAYS = 1;

    static final Set<ReceivableStatus> COLLECTABLE = EnumSet.of(ReceivableStatus.OPEN, ReceivableStatus.PARTIAL, ReceivableStatus.OVERDUE);

    private final ReceivableRepository receivables;
    private final CustomerMandateRepository mandates;
    private final CollectionLineRepository lines;
    private final VirtualAccountRepository accounts;

    public enum Method { DIRECT_DEBIT, REQUEST_TO_PAY, EXCLUDED }

    /** {@code sequenceType}: FRST, RCUR or OOFF, for a direct debit. */
    public record Line(UUID receivableId, String receivableNumber, String customerName, BigDecimal amount, String currency,
                       LocalDate dueDate, Method method, LocalDate collectionDate, UUID mandateId, String mandateReference,
                       String schemeCode, String sequenceType, String reason) {
    }

    public record Total(Method method, String currency, int count, BigDecimal amount) {
    }

    public record Preview(List<Line> lines, List<Total> totals) {
    }

    public Preview preview(UUID corporateId, List<UUID> receivableIds) {
        return preview(corporateId, receivableIds, LocalDate.now());
    }

    Preview preview(UUID corporateId, List<UUID> receivableIds, LocalDate today) {
        Map<UUID, Receivable> found = receivables.findAllById(receivableIds).stream()
                .filter(r -> corporateId.equals(r.getCorporateId()))
                .collect(Collectors.toMap(Receivable::getId, Function.identity()));
        Set<UUID> partyIds = found.values().stream().map(Receivable::getCustomerPartyId).filter(Objects::nonNull).collect(Collectors.toSet());
        List<CustomerMandate> all = partyIds.isEmpty() ? List.of() : mandates.findByCorporateIdAndCustomerPartyIdIn(corporateId, partyIds);
        Map<UUID, List<CustomerMandate>> byParty = all.stream()
                .sorted(Comparator.comparing(CustomerMandate::getValidFrom).reversed())
                .collect(Collectors.groupingBy(CustomerMandate::getCustomerPartyId));

        Batch batch = new Batch(new HashMap<>(
                all.isEmpty() ? Map.of() : lines.findByMandateIdInAndStatusIn(all.stream().map(CustomerMandate::getId).toList(), CollectionLine.USES_MANDATE)
                        .stream().collect(Collectors.groupingBy(CollectionLine::getMandateId,
                                Collectors.mapping(CollectionLine::getCollectionDate, Collectors.toCollection(ArrayList::new))))),
                all.isEmpty() ? Map.of() : accounts.findAllById(all.stream().map(CustomerMandate::getCreditorVaId).collect(Collectors.toSet()))
                        .stream().collect(Collectors.toMap(VirtualAccount::getId, VirtualAccount::getCurrencyCode)));
        Map<UUID, CollectionLine> inFlight = found.isEmpty() ? Map.of()
                : lines.findByReceivableIdInAndStatusIn(found.keySet(), CollectionLine.IN_FLIGHT).stream()
                .collect(Collectors.toMap(CollectionLine::getReceivableId, Function.identity(), (a, b) -> a));

        List<Line> out = new ArrayList<>();
        for (UUID id : new LinkedHashSet<>(receivableIds)) {
            Receivable r = found.get(id);
            if (r == null) {
                out.add(new Line(id, null, null, null, null, null, Method.EXCLUDED, null, null, null, null, null, "Invoice not found"));
            } else if (inFlight.containsKey(id)) {
                CollectionLine l = inFlight.get(id);
                out.add(excluded(r, "Already being collected by direct debit on " + l.getCollectionDate()));
            } else {
                out.add(route(r, byParty.getOrDefault(r.getCustomerPartyId(), List.of()), today, batch));
            }
        }
        return new Preview(out, totals(out));
    }

    /** What earlier collections and the lines already routed in this batch have used of each mandate. */
    private record Batch(Map<UUID, List<LocalDate>> uses, Map<UUID, String> accountCurrency) {
        List<LocalDate> usesOf(UUID mandateId) {
            return uses.getOrDefault(mandateId, List.of());
        }
    }

    private Line route(Receivable r, List<CustomerMandate> candidates, LocalDate today, Batch batch) {
        BigDecimal amount = r.getOutstandingAmount();
        String currency = r.getCurrencyCode();
        if (!COLLECTABLE.contains(r.getStatus())) return excluded(r, "Invoice is " + r.getStatus());
        if (amount == null || amount.signum() <= 0) return excluded(r, "Nothing outstanding");
        if (r.getCustomerPartyId() == null) return excluded(r, "Invoice has no customer to collect from");

        LocalDate earliest = addBusinessDays(today, LEAD_BUSINESS_DAYS);
        LocalDate date = r.getDueDate() == null || r.getDueDate().isBefore(earliest) ? earliest : r.getDueDate();
        while (isWeekend(date)) date = date.plusDays(1);

        String whyNot = candidates.isEmpty() ? "No mandate" : null;
        for (CustomerMandate m : candidates) {
            List<LocalDate> used = batch.usesOf(m.getId());
            String problem = unusable(m, amount, currency, date);
            if (problem == null) problem = unusableNow(m, used, date, batch.accountCurrency().get(m.getCreditorVaId()), currency);
            if (problem == null) {
                String sequence = m.getMandateType() == Type.ONE_OFF ? "OOFF" : used.isEmpty() ? "FRST" : "RCUR";
                batch.uses().computeIfAbsent(m.getId(), k -> new ArrayList<>()).add(date);
                return new Line(r.getId(), r.getReceivableNumber(), r.getCustomerName(), amount, currency, r.getDueDate(),
                        Method.DIRECT_DEBIT, date, m.getId(), m.getMandateReference(), m.getSchemeCode(), sequence, null);
            }
            if (whyNot == null) whyNot = "Mandate " + m.getMandateReference() + " " + problem;
        }
        return new Line(r.getId(), r.getReceivableNumber(), r.getCustomerName(), amount, currency, r.getDueDate(),
                Method.REQUEST_TO_PAY, null, null, null, null, null, whyNot);
    }

    /** Why this mandate cannot collect this amount on this date, or null when it can. */
    static String unusable(CustomerMandate m, BigDecimal amount, String currency, LocalDate date) {
        if (m.getStatus() != Status.ACTIVE) return "is " + m.getStatus().name().toLowerCase(Locale.ROOT);
        if (!m.getCurrency().equals(currency)) return "is in " + m.getCurrency() + ", the invoice in " + currency;
        if (date.isBefore(m.getValidFrom())) return "starts on " + m.getValidFrom();
        if (m.getValidTo() != null && date.isAfter(m.getValidTo())) return "ended on " + m.getValidTo();
        if (m.getMaxPerCollection() != null && amount.compareTo(m.getMaxPerCollection()) > 0) {
            return "allows up to " + new DecimalFormat("#,##0.00").format(m.getMaxPerCollection()) + " a collection";
        }
        return null;
    }

    /** What its collections so far (including this batch's) and its receiving account allow, or null. */
    static String unusableNow(CustomerMandate m, List<LocalDate> used, LocalDate date, String accountCurrency, String currency) {
        if (m.getMandateType() == Type.ONE_OFF && !used.isEmpty()) return "is one-off and has been used";
        if (m.getFrequencyCount() != null && m.getFrequencyPeriod() != null) {
            LocalDate start = periodStart(m.getFrequencyPeriod(), date);
            LocalDate end = periodStart(m.getFrequencyPeriod(), periodNext(m.getFrequencyPeriod(), start));
            long inPeriod = used.stream().filter(d -> d != null && !d.isBefore(start) && d.isBefore(end)).count();
            if (inPeriod >= m.getFrequencyCount()) {
                return "allows " + m.getFrequencyCount() + " a " + m.getFrequencyPeriod().name().toLowerCase(Locale.ROOT)
                        + " and has " + inPeriod + " in the one starting " + start;
            }
        }
        // ponytail: converting on credit is build step 5; until then the money must arrive in the account's own currency
        if (accountCurrency != null && !accountCurrency.equals(currency)) {
            return "pays into a " + accountCurrency + " account and the invoice is in " + currency;
        }
        return null;
    }

    static LocalDate periodStart(Period p, LocalDate d) {
        return switch (p) {
            case WEEK -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> d.withDayOfMonth(1);
            case QUARTER -> d.with(IsoFields.DAY_OF_QUARTER, 1);
            case YEAR -> d.withDayOfYear(1);
        };
    }

    private static LocalDate periodNext(Period p, LocalDate start) {
        return switch (p) {
            case WEEK -> start.plusWeeks(1);
            case MONTH -> start.plusMonths(1);
            case QUARTER -> start.plusMonths(3);
            case YEAR -> start.plusYears(1);
        };
    }

    static LocalDate addBusinessDays(LocalDate from, int days) {
        LocalDate d = from;
        for (int added = 0; added < days; ) {
            d = d.plusDays(1);
            if (!isWeekend(d)) added++;
        }
        return d;
    }

    /** ponytail: Saturday/Sunday only (the UAE weekend since 2022); scheme holiday calendars when a scheme needs one. */
    static boolean isWeekend(LocalDate d) {
        return d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private static Line excluded(Receivable r, String reason) {
        return new Line(r.getId(), r.getReceivableNumber(), r.getCustomerName(), r.getOutstandingAmount(), r.getCurrencyCode(),
                r.getDueDate(), Method.EXCLUDED, null, null, null, null, null, reason);
    }

    static List<Total> totals(List<Line> lines) {
        Map<String, Total> out = new LinkedHashMap<>();
        for (Line l : lines) {
            if (l.amount() == null) continue;
            out.merge(l.method() + "|" + l.currency(), new Total(l.method(), l.currency(), 1, l.amount()),
                    (a, b) -> new Total(a.method(), a.currency(), a.count() + 1, a.amount().add(b.amount())));
        }
        return out.values().stream().sorted(Comparator.comparing(Total::method).thenComparing(Total::currency)).toList();
    }
}
