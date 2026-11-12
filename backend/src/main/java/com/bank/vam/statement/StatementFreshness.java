package com.bank.vam.statement;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.service.treasury.cutoff.CutoffCalendarService;
import com.bank.vam.statement.BankStatement.Status;
import com.bank.vam.statement.parse.ParsedStatement.Kind;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Freshness of statement-fed shadows (spec R14, D6), computed on read, nothing stored: stale when no APPLIED end-of-day
 * statement is dated on or after the last business day before today, on the currency's clearing calendar (market
 * weekend + bank holidays, {@link CutoffCalendarService}). ponytail: the currency's calendar stands in for "the
 * corporate's market calendar"; per-corporate calendars when one exists.
 */
@Service
@RequiredArgsConstructor
public class StatementFreshness {

    private final BankStatementRepository statements;
    private final CutoffCalendarService calendar;

    public record Freshness(LocalDate lastStatementDate, boolean stale) {
    }

    /** Statement-fed shadows only — any other shadow is absent from the map. One query for all of them. */
    @Transactional(readOnly = true)
    public Map<UUID, Freshness> of(Collection<VirtualAccount> shadows, LocalDate today) {
        List<VirtualAccount> fed = shadows.stream()
                .filter(s -> s.getBalanceDataSource() != null && s.getBalanceDataSource().statementFed()).toList();
        if (fed.isEmpty()) return Map.of();
        Map<UUID, LocalDate> latest = new HashMap<>();
        for (Object[] row : statements.latestDates(fed.stream().map(VirtualAccount::getId).toList(), Kind.END_OF_DAY, Status.APPLIED)) {
            latest.put((UUID) row[0], (LocalDate) row[1]);
        }
        Map<String, LocalDate> due = new HashMap<>();   // last business day, per currency
        Map<UUID, Freshness> result = new HashMap<>();
        for (VirtualAccount s : fed) {
            LocalDate last = latest.get(s.getId());
            LocalDate lastBusinessDay = due.computeIfAbsent(s.getCurrencyCode(), c -> calendar.previousBusinessDay(c, today));
            result.put(s.getId(), new Freshness(last, last == null || last.isBefore(lastBusinessDay)));
        }
        return result;
    }

    /** One shadow; null when it is not statement-fed. */
    public Freshness of(VirtualAccount shadow, LocalDate today) {
        return of(List.of(shadow), today).get(shadow.getId());
    }
}
