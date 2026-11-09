package com.bank.vam.collections;

import com.bank.vam.collections.CustomerMandate.Status;
import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.entity.receivables.Receivable.ReceivableStatus;
import com.bank.vam.repository.receivables.ReceivableRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.DayOfWeek;
import java.time.LocalDate;
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

    private static final Set<ReceivableStatus> COLLECTABLE = EnumSet.of(ReceivableStatus.OPEN, ReceivableStatus.PARTIAL, ReceivableStatus.OVERDUE);

    private final ReceivableRepository receivables;
    private final CustomerMandateRepository mandates;

    public enum Method { DIRECT_DEBIT, REQUEST_TO_PAY, EXCLUDED }

    public record Line(UUID receivableId, String receivableNumber, String customerName, BigDecimal amount, String currency,
                       LocalDate dueDate, Method method, LocalDate collectionDate, UUID mandateId, String mandateReference,
                       String schemeCode, String reason) {
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
        Map<UUID, List<CustomerMandate>> byParty = partyIds.isEmpty() ? Map.of()
                : mandates.findByCorporateIdAndCustomerPartyIdIn(corporateId, partyIds).stream()
                .sorted(Comparator.comparing(CustomerMandate::getValidFrom).reversed())
                .collect(Collectors.groupingBy(CustomerMandate::getCustomerPartyId));

        List<Line> lines = new ArrayList<>();
        for (UUID id : new LinkedHashSet<>(receivableIds)) {
            Receivable r = found.get(id);
            lines.add(r == null
                    ? new Line(id, null, null, null, null, null, Method.EXCLUDED, null, null, null, null, "Invoice not found")
                    : route(r, byParty.getOrDefault(r.getCustomerPartyId(), List.of()), today));
        }
        return new Preview(lines, totals(lines));
    }

    private Line route(Receivable r, List<CustomerMandate> candidates, LocalDate today) {
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
            String problem = unusable(m, amount, currency, date);
            if (problem == null) {
                return new Line(r.getId(), r.getReceivableNumber(), r.getCustomerName(), amount, currency, r.getDueDate(),
                        Method.DIRECT_DEBIT, date, m.getId(), m.getMandateReference(), m.getSchemeCode(), null);
            }
            if (whyNot == null) whyNot = "Mandate " + m.getMandateReference() + " " + problem;
        }
        return new Line(r.getId(), r.getReceivableNumber(), r.getCustomerName(), amount, currency, r.getDueDate(),
                Method.REQUEST_TO_PAY, null, null, null, null, whyNot);
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
        // ponytail: the frequency cap is stored but not yet counted — there are no collections to count until runs
        // are submitted (build step 2), which will count this mandate's collections in the period here.
        return null;
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
    private static boolean isWeekend(LocalDate d) {
        return d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private static Line excluded(Receivable r, String reason) {
        return new Line(r.getId(), r.getReceivableNumber(), r.getCustomerName(), r.getOutstandingAmount(), r.getCurrencyCode(),
                r.getDueDate(), Method.EXCLUDED, null, null, null, null, reason);
    }

    private static List<Total> totals(List<Line> lines) {
        Map<String, Total> out = new LinkedHashMap<>();
        for (Line l : lines) {
            if (l.amount() == null) continue;
            out.merge(l.method() + "|" + l.currency(), new Total(l.method(), l.currency(), 1, l.amount()),
                    (a, b) -> new Total(a.method(), a.currency(), a.count() + 1, a.amount().add(b.amount())));
        }
        return out.values().stream().sorted(Comparator.comparing(Total::method).thenComparing(Total::currency)).toList();
    }
}
