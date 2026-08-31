package com.bank.vam.ai.copilot.tools.read;

import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Read tool: transaction-line summary for a currency / VA / time window.
 *
 * <p>Lightweight statement view — does NOT emit camt.053 XML (that's
 * {@code Iso20022StatementService}'s job). Designed for "what flowed through
 * AED yesterday?" style questions.
 *
 * <p>Mapped intents (P3): {@code STATEMENT_SUMMARY}.
 *
 * <p>Parameters:
 * <ul>
 *   <li>{@code currency} — ISO-4217 (optional; if omitted uses market default)</li>
 *   <li>{@code vaNumber} — narrow to one virtual account (optional)</li>
 *   <li>{@code since} — relative duration, default {@code "24h"}</li>
 *   <li>{@code limit} — max entries to return (default 25, cap 200)</li>
 * </ul>
 *
 * <p>Output shape:
 * <pre>
 *   {
 *     currency, sinceLabel, vaNumber,
 *     entryCount, creditCount, debitCount,
 *     creditTotal, debitTotal, netMovement,
 *     entries: [
 *       { id, reference, date, amount, currency, direction,
 *         movementType, status, counterparty, description }
 *     ]
 *   }
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetStatementLinesTool implements CopilotTool {

    static final int DEFAULT_LIMIT = 25;
    static final int MAX_LIMIT = 200;
    static final String DEFAULT_SINCE = "24h";

    private static final Pattern DURATION_PATTERN =
            Pattern.compile("^\\s*(\\d+)\\s*([smhd])\\s*$");

    private final TransactionRepository transactionRepository;
    private final VirtualAccountRepository accountRepository;

    @Override
    public String name() {
        return "get_statement_lines";
    }

    @Override
    public String description() {
        return "Summarise transactions (count + inflow/outflow totals) for a currency / VA / window.";
    }

    @Override
    public Map<String, Map<String, Object>> parameterSchema() {
        return Map.of(
                "currency", Map.of("type", "string",
                        "description", "ISO-4217 code; defaults to market currency"),
                "vaNumber", Map.of("type", "string",
                        "description", "Narrow to one virtual account (optional)"),
                "since", Map.of("type", "string",
                        "description", "Relative window like '24h', '7d', '30m' (default 24h)"),
                "limit", Map.of("type", "integer",
                        "description", "Max entries to return (default 25, cap 200)")
        );
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(ToolContext context, Map<String, Object> params) {
        try {
            String currency = paramString(params, "currency", context.defaultCurrency());
            if (currency != null) currency = currency.toUpperCase();

            String vaNumber = paramString(params, "vaNumber", null);
            String since = paramString(params, "since", DEFAULT_SINCE);
            int limit = Math.min(Math.max(1, paramInt(params, "limit", DEFAULT_LIMIT)), MAX_LIMIT);

            LocalDateTime sinceCutoff = parseSince(since);
            if (sinceCutoff == null) {
                // Fall back to 24h when the duration string was invalid rather
                // than failing outright — keeps the demo question alive.
                sinceCutoff = LocalDateTime.now().minus(Duration.ofHours(24));
                since = "24h";
            }

            List<Transaction> candidate = fetchCandidate(context, vaNumber, sinceCutoff);

            // Filter currency post-fetch (small N in prototype, no compound finder).
            final String currencyFilter = currency;
            List<Transaction> matched = candidate.stream()
                    .filter(t -> currencyFilter == null
                            || currencyFilter.equalsIgnoreCase(t.getCurrencyCode()))
                    .sorted(Comparator.comparing(
                            (Transaction t) -> t.getTransactionDate() == null
                                    ? LocalDateTime.MIN : t.getTransactionDate())
                            .reversed())
                    .toList();

            // Aggregate
            BigDecimal creditTotal = BigDecimal.ZERO;
            BigDecimal debitTotal = BigDecimal.ZERO;
            int creditCount = 0;
            int debitCount = 0;
            for (Transaction t : matched) {
                if (t.isCredit()) {
                    creditCount++;
                    creditTotal = creditTotal.add(nz(t.getAmount()));
                } else if (t.isDebit()) {
                    debitCount++;
                    debitTotal = debitTotal.add(nz(t.getAmount()));
                }
            }

            List<Map<String, Object>> entries = matched.stream()
                    .limit(limit)
                    .map(this::toRow)
                    .collect(Collectors.toList());

            String summary = String.format("%d entries since %s (%s): +%s / -%s",
                    matched.size(), since,
                    currency == null ? "all currencies" : currency,
                    creditTotal.toPlainString(),
                    debitTotal.toPlainString());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("currency", currency);
            data.put("sinceLabel", since);
            data.put("vaNumber", vaNumber);
            data.put("entryCount", matched.size());
            data.put("creditCount", creditCount);
            data.put("debitCount", debitCount);
            data.put("creditTotal", creditTotal);
            data.put("debitTotal", debitTotal);
            data.put("netMovement", creditTotal.subtract(debitTotal));
            data.put("entries", entries);

            return ToolResult.ok(name(), summary, data);
        } catch (Exception e) {
            log.warn("get_statement_lines failed", e);
            return ToolResult.error(name(), "Failed to summarise transactions: " + e.getMessage());
        }
    }

    private List<Transaction> fetchCandidate(ToolContext context, String vaNumber, LocalDateTime since) {
        LocalDateTime now = LocalDateTime.now();

        if (vaNumber != null) {
            return accountRepository.findByVaNumber(vaNumber)
                    .map(va -> transactionRepository.findByVaIdAndDateRange(va.getId(), since, now))
                    .orElse(List.of());
        }
        if (context.hasCorporateScope()) {
            return transactionRepository.findByCorporateIdAndDateRange(context.corporateId(), since, now);
        }
        // No scope and no VA — pull a healthy page of recent rows and filter in memory.
        return transactionRepository.findAll(
                PageRequest.of(0, 500, Sort.by(Sort.Direction.DESC, "transactionDate"))
        ).getContent().stream()
                .filter(t -> t.getTransactionDate() != null && t.getTransactionDate().isAfter(since))
                .toList();
    }

    private LocalDateTime parseSince(String s) {
        if (s == null) return null;
        Matcher m = DURATION_PATTERN.matcher(s);
        if (!m.matches()) return null;
        long n = Long.parseLong(m.group(1));
        Duration d = switch (m.group(2)) {
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            case "h" -> Duration.ofHours(n);
            case "d" -> Duration.ofDays(n);
            default -> null;
        };
        return d == null ? null : LocalDateTime.now().minus(d);
    }

    private Map<String, Object> toRow(Transaction t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId() == null ? null : t.getId().toString());
        m.put("reference", t.getReferenceNumber());
        m.put("date", t.getTransactionDate() == null ? null : t.getTransactionDate().toString());
        m.put("amount", t.getAmount());
        m.put("currency", t.getCurrencyCode());
        m.put("direction", t.isCredit() ? "CRDT" : t.isDebit() ? "DBIT" : null);
        m.put("movementType", t.getMovementType() == null ? null : t.getMovementType().name());
        m.put("status", t.getStatus() == null ? null : t.getStatus().name());
        m.put("counterparty", t.isCredit() ? t.getRemitterName() : t.getBeneficiaryName());
        m.put("description", t.getDescription());
        return m;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
