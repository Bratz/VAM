package com.bank.vam.ai.copilot.tools.read;

import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.entity.treasury.SweepExecution;
import com.bank.vam.entity.treasury.SweepExecution.ExecutionStatus;
import com.bank.vam.entity.treasury.SweepRule;
import com.bank.vam.repository.treasury.SweepExecutionRepository;
import com.bank.vam.repository.treasury.SweepRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Read tool: sweep execution history — distinct from {@code get_sweep_status},
 * which only lists rule summaries, not individual runs.
 *
 * <p>Output shape: {@code { totalMatched, filter: { ruleId, status, since },
 * executions: [{ id, executionReference, ruleId, ruleName, sourceAccountNumber,
 * targetAccountNumber, sweepAmount, currencyCode, status, executionTime,
 * completedAt, settledAt }] } }
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetSweepExecutionsTool implements CopilotTool {

    private static final Pattern SINCE_PATTERN = Pattern.compile("^(\\d+)([hdm])$", Pattern.CASE_INSENSITIVE);
    static final int DEFAULT_LIMIT = 25;
    static final int MAX_LIMIT = 200;

    private final SweepExecutionRepository executionRepository;
    private final SweepRuleRepository sweepRuleRepository;

    @Override
    public String name() {
        return "get_sweep_executions";
    }

    @Override
    public String description() {
        return "Sweep execution history, optionally filtered by rule id, status, or a since window.";
    }

    @Override
    public Map<String, Map<String, Object>> parameterSchema() {
        return Map.of(
                "ruleId", Map.of("type", "string", "description", "Sweep rule UUID"),
                "status", Map.of("type", "string", "description", "COMMITTED, SETTLED, SUCCESS, FAILED, PARTIAL, PENDING, SKIPPED"),
                "since", Map.of("type", "string", "description", "Relative window like '24h', '7d' (default 7d when no ruleId/status given)"),
                "limit", Map.of("type", "integer", "description", "Max rows to return (default 25, cap 200)")
        );
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(ToolContext context, Map<String, Object> params) {
        try {
            String ruleIdParam = paramString(params, "ruleId", null);
            String statusParam = paramString(params, "status", null);
            String sinceParam = paramString(params, "since", null);
            int limit = Math.min(Math.max(1, paramInt(params, "limit", DEFAULT_LIMIT)), MAX_LIMIT);

            List<SweepExecution> candidate;
            if (ruleIdParam != null && !ruleIdParam.isBlank()) {
                candidate = executionRepository.findByRuleIdOrderByExecutionTimeDesc(UUID.fromString(ruleIdParam));
            } else if (statusParam != null) {
                ExecutionStatus status = parseStatus(statusParam);
                candidate = status != null ? executionRepository.findByStatus(status) : List.of();
            } else {
                LocalDateTime cutoff = parseSince(sinceParam, 7 * 24 * 60);
                candidate = executionRepository.findRecentExecutions(cutoff);
            }

            List<SweepExecution> corporateScoped = context.hasCorporateScope()
                    ? filterByCorporate(candidate, context.corporateId())
                    : candidate;

            List<SweepExecution> filtered = corporateScoped.stream()
                    .sorted(Comparator.comparing(
                            (SweepExecution e) -> e.getExecutionTime() == null ? LocalDateTime.MIN : e.getExecutionTime())
                            .reversed())
                    .limit(limit)
                    .toList();

            List<Map<String, Object>> rows = filtered.stream().map(this::toRow).collect(Collectors.toList());

            Map<String, Object> filter = new LinkedHashMap<>();
            filter.put("ruleId", ruleIdParam);
            filter.put("status", statusParam);
            filter.put("since", sinceParam);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalMatched", filtered.size());
            data.put("filter", filter);
            data.put("executions", rows);

            String summary = String.format("%d execution%s found", filtered.size(), filtered.size() == 1 ? "" : "s");
            return ToolResult.ok(name(), summary, data);
        } catch (Exception e) {
            log.warn("get_sweep_executions failed", e);
            return ToolResult.error(name(), "Failed to fetch sweep executions: " + e.getMessage());
        }
    }

    /**
     * {@code SweepExecution} has no {@code corporateId} column — resolve via
     * {@code execution.getRule().getId()}. Calling {@code .getId()} on a lazy
     * Hibernate proxy does NOT trigger a fetch (the FK is already known), so
     * this batch-lookup is N+1-safe: one query for all distinct rule ids, not
     * one per execution. Same shape as GetSweepInstructionsTool.filterByCorporate.
     */
    private List<SweepExecution> filterByCorporate(List<SweepExecution> executions, UUID corporateId) {
        Set<UUID> ruleIds = new HashSet<>();
        for (SweepExecution e : executions) {
            if (e.getRule() != null) ruleIds.add(e.getRule().getId());
        }
        if (ruleIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, UUID> ruleIdToCorporateId = new HashMap<>();
        for (SweepRule rule : sweepRuleRepository.findAllById(ruleIds)) {
            ruleIdToCorporateId.put(rule.getId(), rule.getCorporateId());
        }
        return executions.stream()
                .filter(e -> e.getRule() != null
                        && corporateId.equals(ruleIdToCorporateId.get(e.getRule().getId())))
                .toList();
    }

    private Map<String, Object> toRow(SweepExecution e) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", e.getId() != null ? e.getId().toString() : null);
        row.put("executionReference", e.getExecutionReference());
        row.put("ruleId", e.getRule() != null ? e.getRule().getId().toString() : null);
        row.put("ruleName", e.getRuleName());
        row.put("sourceAccountNumber", e.getSourceAccountNumber());
        row.put("targetAccountNumber", e.getTargetAccountNumber());
        row.put("sweepAmount", e.getSweepAmount());
        row.put("currencyCode", e.getCurrencyCode());
        row.put("status", e.getStatus() != null ? e.getStatus().name() : null);
        row.put("executionTime", e.getExecutionTime());
        row.put("completedAt", e.getCompletedAt());
        row.put("settledAt", e.getSettledAt());
        return row;
    }

    private ExecutionStatus parseStatus(String s) {
        try {
            return ExecutionStatus.valueOf(s.trim().toUpperCase());
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDateTime parseSince(String since, int defaultMinutes) {
        if (since == null || since.isBlank()) {
            return LocalDateTime.now().minus(defaultMinutes, ChronoUnit.MINUTES);
        }
        Matcher m = SINCE_PATTERN.matcher(since.trim());
        if (!m.matches()) {
            return LocalDateTime.now().minus(defaultMinutes, ChronoUnit.MINUTES);
        }
        long amount = Long.parseLong(m.group(1));
        ChronoUnit unit = switch (m.group(2).toLowerCase()) {
            case "h" -> ChronoUnit.HOURS;
            case "d" -> ChronoUnit.DAYS;
            default -> ChronoUnit.MINUTES;
        };
        return LocalDateTime.now().minus(amount, unit);
    }
}
