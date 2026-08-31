package com.bank.vam.ai.copilot.tools.read;

import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.repository.VirtualAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Read tool: list virtual accounts, optionally filtered by currency / status.
 *
 * <p>Mapped intents (P3): {@code LIST_ACCOUNTS}, {@code ACCOUNTS_BY_CURRENCY}.
 *
 * <p>Parameters:
 * <ul>
 *   <li>{@code currency} — ISO-4217 code (optional)</li>
 *   <li>{@code status} — one of {@link VirtualAccount.VaStatus} (optional)</li>
 *   <li>{@code limit} — max rows to return (default 25, hard cap 200)</li>
 * </ul>
 *
 * <p>Output shape:
 * <pre>
 *   {
 *     totalMatched: int,
 *     returned: int,
 *     currency: string|null,
 *     status: string|null,
 *     accounts: [
 *       { id, vaNumber, viban, vaName, currency, status,
 *         currentBalance, availableBalance, accountCategory }
 *     ]
 *   }
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetAccountsTool implements CopilotTool {

    static final int DEFAULT_LIMIT = 25;
    static final int MAX_LIMIT = 200;

    private final VirtualAccountRepository accountRepository;

    @Override
    public String name() {
        return "get_accounts";
    }

    @Override
    public String description() {
        return "List virtual accounts, optionally filtered by currency and/or status.";
    }

    @Override
    public Map<String, Map<String, Object>> parameterSchema() {
        return Map.of(
                "currency", Map.of("type", "string", "description", "ISO-4217 code (e.g. AED, GBP)"),
                "status", Map.of("type", "string", "description", "VaStatus: ACTIVE, INACTIVE, SUSPENDED, CLOSED, BLOCKED, PENDING_ACTIVATION, EXPIRED"),
                "limit", Map.of("type", "integer", "description", "Max rows to return (default 25, cap 200)")
        );
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(ToolContext context, Map<String, Object> params) {
        try {
            String currency = paramString(params, "currency", null);
            String statusParam = paramString(params, "status", null);
            int limit = Math.min(Math.max(1, paramInt(params, "limit", DEFAULT_LIMIT)), MAX_LIMIT);

            VirtualAccount.VaStatus status = parseStatus(statusParam);

            // Compose filter — corporate scope, currency, status — applied
            // post-fetch to keep query simple (prototype dataset is small).
            List<VirtualAccount> matched = fetchCandidate(context, currency).stream()
                    .filter(va -> status == null || va.getStatus() == status)
                    .toList();

            List<Map<String, Object>> rows = matched.stream()
                    .limit(limit)
                    .map(this::toRow)
                    .collect(Collectors.toList());

            String summary = String.format("Found %d account%s%s%s",
                    matched.size(),
                    matched.size() == 1 ? "" : "s",
                    currency != null ? " in " + currency.toUpperCase() : "",
                    status != null ? " (" + status + ")" : "");

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalMatched", matched.size());
            data.put("returned", rows.size());
            data.put("currency", currency == null ? null : currency.toUpperCase());
            data.put("status", status == null ? null : status.name());
            data.put("accounts", rows);

            return ToolResult.ok(name(), summary, data);
        } catch (Exception e) {
            log.warn("get_accounts failed", e);
            return ToolResult.error(name(), "Failed to list accounts: " + e.getMessage());
        }
    }

    private List<VirtualAccount> fetchCandidate(ToolContext context, String currency) {
        if (context.hasCorporateScope()) {
            // Repository has no currency-on-corporate finder; fetch corporate-scoped
            // and filter currency in memory (small N in prototype).
            List<VirtualAccount> corpScoped = accountRepository.findByCorporateId(context.corporateId());
            if (currency == null) return corpScoped;
            return corpScoped.stream()
                    .filter(va -> currency.equalsIgnoreCase(va.getCurrencyCode()))
                    .toList();
        }
        if (currency != null) {
            return accountRepository.findByCurrencyCode(currency.toUpperCase());
        }
        return accountRepository.findAll();
    }

    private VirtualAccount.VaStatus parseStatus(String s) {
        if (s == null) return null;
        try {
            return VirtualAccount.VaStatus.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.debug("get_accounts: ignoring unknown status '{}'", s);
            return null;
        }
    }

    private Map<String, Object> toRow(VirtualAccount va) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", va.getId() == null ? null : va.getId().toString());
        m.put("vaNumber", va.getVaNumber());
        m.put("viban", va.getViban());
        m.put("vaName", va.getVaName());
        m.put("currency", va.getCurrencyCode());
        m.put("status", va.getStatus() == null ? null : va.getStatus().name());
        m.put("currentBalance", va.getCurrentBalance());
        m.put("availableBalance", va.getAvailableBalance());
        m.put("accountCategory", va.getAccountCategory() == null ? null : va.getAccountCategory().name());
        return m;
    }
}
