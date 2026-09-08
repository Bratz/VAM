package com.bank.vam.ai.copilot.tools.read;

import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.config.MultiBankProperties;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.AccountCategory;
import com.bank.vam.entity.VirtualAccount.BalanceRefreshStatus;
import com.bank.vam.repository.VirtualAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read tool: sync/staleness dashboard for shadow (PHYSICAL_MIRROR) accounts —
 * "which shadow balances haven't refreshed recently and might be stale."
 *
 * <p>Output shape:
 * <pre>
 *   { totalMatched, staleCount, neverRefreshedCount, staleOnly, shadows: [
 *       { vaId, vaNumber, bankBic, bankName, bankAccountNumber, bankIban,
 *         freshnessThresholdMinutes, lastBalanceRefreshAt, lastBalanceRefreshStatus, stale }
 *   ] }
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetShadowBalanceFreshnessTool implements CopilotTool {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private final VirtualAccountRepository accountRepository;
    private final MultiBankProperties multiBankProperties;

    @Override
    public String name() {
        return "get_shadow_balance_freshness";
    }

    @Override
    public String description() {
        return "Sync/staleness dashboard for shadow (mirror) accounts — which shadow balances haven't refreshed recently.";
    }

    @Override
    public Map<String, Map<String, Object>> parameterSchema() {
        return Map.of(
                "staleOnly", Map.of("type", "boolean", "description", "Only return accounts considered stale (default false)"),
                "limit", Map.of("type", "integer", "description", "Max rows to return (default 50, cap 200)")
        );
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(ToolContext context, Map<String, Object> params) {
        try {
            boolean staleOnly = paramBoolean(params, "staleOnly", false);
            int limit = Math.min(Math.max(1, paramInt(params, "limit", DEFAULT_LIMIT)), MAX_LIMIT);

            List<VirtualAccount> shadows = context.hasCorporateScope()
                    ? accountRepository.findByCorporateIdAndAccountCategory(context.corporateId(), AccountCategory.PHYSICAL_MIRROR)
                    : accountRepository.findByAccountCategory(AccountCategory.PHYSICAL_MIRROR);

            int staleCount = 0;
            int neverCount = 0;
            List<Map<String, Object>> rows = new ArrayList<>();
            for (VirtualAccount va : shadows) {
                boolean stale = isStale(va);
                boolean never = va.getLastBalanceRefreshStatus() == null
                        || va.getLastBalanceRefreshStatus() == BalanceRefreshStatus.NEVER;
                if (stale) staleCount++;
                if (never) neverCount++;
                if (staleOnly && !stale) continue;
                rows.add(toRow(va, stale));
            }

            rows.sort(Comparator.comparing(r -> String.valueOf(r.get("vaNumber"))));
            int totalMatched = rows.size();
            List<Map<String, Object>> limited = rows.size() > limit ? rows.subList(0, limit) : rows;

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalMatched", totalMatched);
            data.put("staleCount", staleCount);
            data.put("neverRefreshedCount", neverCount);
            data.put("staleOnly", staleOnly);
            data.put("shadows", limited);

            String summary = String.format("%d shadow account(s), %d stale, %d never refreshed",
                    shadows.size(), staleCount, neverCount);
            return ToolResult.ok(name(), summary, data);
        } catch (Exception e) {
            log.warn("get_shadow_balance_freshness failed", e);
            return ToolResult.error(name(), "Failed to compute shadow balance freshness: " + e.getMessage());
        }
    }

    private Map<String, Object> toRow(VirtualAccount va, boolean stale) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("vaId", va.getId() != null ? va.getId().toString() : null);
        row.put("vaNumber", va.getVaNumber());
        row.put("bankBic", va.getBankSwift());
        row.put("bankName", va.getBankName());
        row.put("bankAccountNumber", va.getBankAccountNumber());
        row.put("bankIban", va.getBankIban());
        row.put("freshnessThresholdMinutes", va.getFreshnessThresholdMinutes());
        row.put("lastBalanceRefreshAt", va.getLastBalanceRefreshAt());
        row.put("lastBalanceRefreshStatus", va.getLastBalanceRefreshStatus() != null
                ? va.getLastBalanceRefreshStatus().name() : BalanceRefreshStatus.NEVER.name());
        row.put("stale", stale);
        return row;
    }

    private boolean isStale(VirtualAccount va) {
        if (va.getLastBalanceRefreshAt() == null) {
            return true;
        }
        int threshold = va.getFreshnessThresholdMinutes() != null
                ? va.getFreshnessThresholdMinutes()
                : multiBankProperties.getDefaultFreshnessThresholdMinutes();
        return Duration.between(va.getLastBalanceRefreshAt(), LocalDateTime.now()).toMinutes() >= threshold;
    }
}
