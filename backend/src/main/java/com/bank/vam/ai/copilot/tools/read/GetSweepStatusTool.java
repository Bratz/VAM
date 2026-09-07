package com.bank.vam.ai.copilot.tools.read;

import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.McpUiDescriptors;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.entity.treasury.SweepRule;
import com.bank.vam.repository.treasury.SweepRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Read tool: list sweep rules, with status counts.
 *
 * <p>Mapped intents (P3): {@code LIST_RULES}.
 *
 * <p>Parameters:
 * <ul>
 *   <li>{@code status} — {@link SweepRule.SweepStatus} (optional; omit for all)</li>
 *   <li>{@code limit} — max rows (default 25, cap 200)</li>
 * </ul>
 *
 * <p>Output shape:
 * <pre>
 *   {
 *     totalRules, activeCount, pausedCount, disabledCount,
 *     filter: { status },
 *     rules: [
 *       { id, ruleReference, ruleName, status, sweepType, frequency,
 *         currencyCode, executionMode, rail, executionCount, totalSwept,
 *         lastExecution, nextExecution }
 *     ]
 *   }
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetSweepStatusTool implements CopilotTool {

    static final int DEFAULT_LIMIT = 25;
    static final int MAX_LIMIT = 200;

    private final SweepRuleRepository sweepRuleRepository;

    @Override
    public String name() {
        return "get_sweep_status";
    }

    @Override
    public String description() {
        return "List sweep rules and their current execution state.";
    }

    @Override
    public Map<String, Map<String, Object>> parameterSchema() {
        return Map.of(
                "status", Map.of("type", "string",
                        "description", "Filter by status: ACTIVE, PAUSED, DISABLED"),
                "limit", Map.of("type", "integer",
                        "description", "Max rows to return (default 25, cap 200)")
        );
    }

    @Override
    public Map<String, Object> uiComponent() {
        return McpUiDescriptors.widget(McpUiDescriptors.SWEEP_STATUS, "Checking sweep status…", "Sweep status ready");
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(ToolContext context, Map<String, Object> params) {
        try {
            String statusParam = paramString(params, "status", null);
            int limit = Math.min(Math.max(1, paramInt(params, "limit", DEFAULT_LIMIT)), MAX_LIMIT);

            SweepRule.SweepStatus statusFilter = parseStatus(statusParam);

            // Corporate-scoped whenever the caller has a scope — otherwise a linked
            // MCP account would see every corporate's sweep rules through this tool.
            List<SweepRule> all = context.hasCorporateScope()
                    ? sweepRuleRepository.findByCorporateId(context.corporateId())
                    : sweepRuleRepository.findAll();

            // Status histogram (always all rules, not filtered).
            int active = 0, paused = 0, disabled = 0;
            for (SweepRule r : all) {
                switch (r.getStatus()) {
                    case ACTIVE -> active++;
                    case PAUSED -> paused++;
                    case DISABLED -> disabled++;
                }
            }

            List<SweepRule> filtered = statusFilter == null
                    ? all
                    : all.stream().filter(r -> r.getStatus() == statusFilter).toList();

            List<Map<String, Object>> rows = filtered.stream()
                    .limit(limit)
                    .map(this::toRow)
                    .collect(Collectors.toList());

            String summary = String.format("%d rule%s%s (active=%d, paused=%d, disabled=%d)",
                    filtered.size(), filtered.size() == 1 ? "" : "s",
                    statusFilter != null ? " in " + statusFilter : "",
                    active, paused, disabled);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalRules", all.size());
            data.put("activeCount", active);
            data.put("pausedCount", paused);
            data.put("disabledCount", disabled);
            Map<String, Object> filter = new LinkedHashMap<>();
            filter.put("status", statusFilter == null ? null : statusFilter.name());
            data.put("filter", filter);
            data.put("rules", rows);

            return ToolResult.ok(name(), summary, data);
        } catch (Exception e) {
            log.warn("get_sweep_status failed", e);
            return ToolResult.error(name(), "Failed to list sweep rules: " + e.getMessage());
        }
    }

    private SweepRule.SweepStatus parseStatus(String s) {
        if (s == null) return null;
        try {
            return SweepRule.SweepStatus.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.debug("get_sweep_status: ignoring unknown status '{}'", s);
            return null;
        }
    }

    private Map<String, Object> toRow(SweepRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId() == null ? null : r.getId().toString());
        m.put("ruleReference", r.getRuleReference());
        m.put("ruleName", r.getRuleName());
        m.put("status", r.getStatus() == null ? null : r.getStatus().name());
        m.put("sweepType", r.getSweepType() == null ? null : r.getSweepType().name());
        m.put("frequency", r.getFrequency() == null ? null : r.getFrequency().name());
        m.put("currencyCode", r.getCurrencyCode());
        m.put("executionMode", r.getExecutionMode() == null ? null : r.getExecutionMode().name());
        m.put("rail", r.getRail() == null ? null : r.getRail().name());
        m.put("executionCount", r.getExecutionCount());
        m.put("totalSwept", r.getTotalSwept());
        m.put("lastExecution", r.getLastExecution() == null ? null : r.getLastExecution().toString());
        m.put("nextExecution", r.getNextExecution() == null ? null : r.getNextExecution().toString());
        return m;
    }
}
