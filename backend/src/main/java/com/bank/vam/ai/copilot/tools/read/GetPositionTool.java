package com.bank.vam.ai.copilot.tools.read;

import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.config.HomeBankProperties;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.repository.VirtualAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * Read tool: aggregate cash position across all VAs in a given currency.
 *
 * <p>Mapped intents (P3): {@code GET_POSITION}, {@code GET_POSITION_BY_BANK}.
 *
 * <p>Parameters:
 * <ul>
 *   <li>{@code currency} — ISO-4217 code (defaults to {@link
 *       ToolContext#defaultCurrency()})</li>
 *   <li>{@code groupBy} — one of {@code "entity"} (default) or {@code "bank"}</li>
 * </ul>
 *
 * <p>Output shape:
 * <pre>
 *   {
 *     currency, totalCurrent, totalAvailable,
 *     accountCount,
 *     groupBy: "entity" | "bank",
 *     breakdown: [
 *       { key, count, current, available, isHomeBank? }   // isHomeBank only for groupBy=bank
 *     ]
 *   }
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetPositionTool implements CopilotTool {

    private final VirtualAccountRepository accountRepository;
    private final HomeBankProperties homeBankProperties;

    @Override
    public String name() {
        return "get_position";
    }

    @Override
    public String description() {
        return "Aggregate balance position by currency, with breakdown by entity or by bank.";
    }

    @Override
    public Map<String, Map<String, Object>> parameterSchema() {
        return Map.of(
                "currency", Map.of("type", "string",
                        "description", "ISO-4217 code; defaults to the active market currency"),
                "groupBy", Map.of("type", "string",
                        "description", "Breakdown axis: 'entity' (default) or 'bank'")
        );
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(ToolContext context, Map<String, Object> params) {
        try {
            String currency = paramString(params, "currency", context.defaultCurrency());
            if (currency == null || currency.isBlank()) {
                return ToolResult.error(name(), "No currency specified and no market default available");
            }
            currency = currency.toUpperCase();

            String groupBy = paramString(params, "groupBy", "entity").toLowerCase();
            if (!groupBy.equals("entity") && !groupBy.equals("bank")) {
                groupBy = "entity";
            }

            List<VirtualAccount> matched = fetchCandidate(context, currency);

            BigDecimal totalCurrent = BigDecimal.ZERO;
            BigDecimal totalAvailable = BigDecimal.ZERO;
            // Group accumulators keyed by entityCode or bankBic
            Map<String, BucketAccumulator> buckets = new LinkedHashMap<>();

            String homeBic = homeBankProperties.getBic();

            for (VirtualAccount va : matched) {
                BigDecimal cur = nz(va.getCurrentBalance());
                BigDecimal avl = nz(va.getAvailableBalance());
                totalCurrent = totalCurrent.add(cur);
                totalAvailable = totalAvailable.add(avl);

                String key;
                boolean isHomeBank;
                if (groupBy.equals("bank")) {
                    key = va.getBankSwift() != null ? va.getBankSwift() : "UNKNOWN";
                    isHomeBank = homeBic != null && homeBic.equalsIgnoreCase(key);
                } else {
                    key = va.getOwningEntityCode() != null ? va.getOwningEntityCode() : "UNASSIGNED";
                    isHomeBank = false;
                }
                // Both branches assign exactly once → effectively final, safe to capture below.
                final boolean isHomeBankCaptured = isHomeBank;
                BucketAccumulator b = buckets.computeIfAbsent(key,
                        k -> new BucketAccumulator(k, isHomeBankCaptured));
                b.count++;
                b.current = b.current.add(cur);
                b.available = b.available.add(avl);
            }

            List<Map<String, Object>> breakdown = new ArrayList<>(buckets.size());
            for (BucketAccumulator b : buckets.values()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("key", b.key);
                row.put("count", b.count);
                row.put("current", b.current);
                row.put("available", b.available);
                if (groupBy.equals("bank")) {
                    row.put("isHomeBank", b.isHomeBank);
                }
                breakdown.add(row);
            }
            // Stable order: home bank first when grouping by bank, else alphabetical.
            if (groupBy.equals("bank")) {
                breakdown.sort((a, b) -> {
                    boolean aHome = Boolean.TRUE.equals(a.get("isHomeBank"));
                    boolean bHome = Boolean.TRUE.equals(b.get("isHomeBank"));
                    if (aHome != bHome) return aHome ? -1 : 1;
                    return String.valueOf(a.get("key")).compareTo(String.valueOf(b.get("key")));
                });
            } else {
                breakdown.sort(Comparator.comparing(m -> String.valueOf(m.get("key"))));
            }

            String summary = String.format("%s position across %d account%s in %d %s: %s",
                    currency, matched.size(), matched.size() == 1 ? "" : "s",
                    buckets.size(), groupBy.equals("bank") ? "bank(s)" : "entity/entities",
                    totalCurrent.toPlainString());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("currency", currency);
            data.put("totalCurrent", totalCurrent);
            data.put("totalAvailable", totalAvailable);
            data.put("accountCount", matched.size());
            data.put("groupBy", groupBy);
            data.put("breakdown", breakdown);

            return ToolResult.ok(name(), summary, data);
        } catch (Exception e) {
            log.warn("get_position failed", e);
            return ToolResult.error(name(), "Failed to compute position: " + e.getMessage());
        }
    }

    private List<VirtualAccount> fetchCandidate(ToolContext context, String currency) {
        if (context.hasCorporateScope()) {
            return accountRepository.findByCorporateId(context.corporateId()).stream()
                    .filter(va -> currency.equalsIgnoreCase(va.getCurrencyCode()))
                    .toList();
        }
        return accountRepository.findByCurrencyCode(currency);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static final class BucketAccumulator {
        final String key;
        final boolean isHomeBank;
        int count = 0;
        BigDecimal current = BigDecimal.ZERO;
        BigDecimal available = BigDecimal.ZERO;
        BucketAccumulator(String key, boolean isHomeBank) {
            this.key = key;
            this.isHomeBank = isHomeBank;
        }
    }
}
