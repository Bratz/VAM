package com.bank.vam.ai.copilot.tools.read;

import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.entity.treasury.SweepRule;
import com.bank.vam.entity.treasury.SweepRuleSource;
import com.bank.vam.repository.treasury.SweepRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Read tool: one sweep rule and its source accounts by id — distinct from
 * {@code get_sweep_status}, which only lists rule summaries with no source
 * detail.
 *
 * <p>Output shape: {@code { totalMatched, rule: { id, ruleReference,
 * ruleName, sweepType, status, targetAccountNumber, targetEntityCode,
 * currencyCode, frequency, priority, totalSwept, executionCount,
 * lastExecution, nextExecution, sourceAccounts: [{ accountId, accountNumber,
 * entityCode, entityName, currencyCode, bankName }] } }}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetSweepRuleDetailTool implements CopilotTool {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final SweepRuleRepository sweepRuleRepository;

    @Override
    public String name() {
        return "get_sweep_rule_detail";
    }

    @Override
    public String description() {
        return "Fetch one sweep rule and its source accounts by id or rule reference.";
    }

    @Override
    public Map<String, Map<String, Object>> parameterSchema() {
        return Map.of(
                "id", Map.of("type", "string", "description", "Rule UUID or ruleReference", "required", true)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(ToolContext context, Map<String, Object> params) {
        try {
            String idParam = paramString(params, "id", null);
            if (idParam == null || idParam.isBlank()) {
                return ToolResult.error(name(), "Missing required parameter: id");
            }

            Optional<SweepRule> found = UUID_PATTERN.matcher(idParam).matches()
                    ? sweepRuleRepository.findByIdWithSources(UUID.fromString(idParam))
                    : sweepRuleRepository.findByRuleReference(idParam)
                            .flatMap(r -> sweepRuleRepository.findByIdWithSources(r.getId()));

            // Not-found on a corporate-scope mismatch, not 403 — same convention as
            // GetSweepInstructionsTool.lookupById, so a scoped caller can't probe ids.
            if (found.isPresent() && context.hasCorporateScope()
                    && !context.corporateId().equals(found.get().getCorporateId())) {
                found = Optional.empty();
            }

            if (found.isEmpty()) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("totalMatched", 0);
                return ToolResult.ok(name(), "No sweep rule matched id '" + idParam + "'", data);
            }

            SweepRule rule = found.get();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalMatched", 1);
            data.put("rule", toRow(rule));
            return ToolResult.ok(name(), "Rule " + rule.getRuleReference() + " has "
                    + rule.getSourceAccounts().size() + " source account(s)", data);
        } catch (Exception e) {
            log.warn("get_sweep_rule_detail failed", e);
            return ToolResult.error(name(), "Failed to fetch sweep rule detail: " + e.getMessage());
        }
    }

    private Map<String, Object> toRow(SweepRule rule) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rule.getId() != null ? rule.getId().toString() : null);
        row.put("ruleReference", rule.getRuleReference());
        row.put("ruleName", rule.getRuleName());
        row.put("sweepType", rule.getSweepType() != null ? rule.getSweepType().name() : null);
        row.put("status", rule.getStatus() != null ? rule.getStatus().name() : null);
        row.put("targetAccountNumber", rule.getTargetAccountNumber());
        row.put("targetEntityCode", rule.getTargetEntityCode());
        row.put("currencyCode", rule.getCurrencyCode());
        row.put("frequency", rule.getFrequency() != null ? rule.getFrequency().name() : null);
        row.put("priority", rule.getPriority());
        row.put("totalSwept", rule.getTotalSwept());
        row.put("executionCount", rule.getExecutionCount());
        row.put("lastExecution", rule.getLastExecution());
        row.put("nextExecution", rule.getNextExecution());
        row.put("sourceAccounts", rule.getSourceAccounts().stream().map(this::toSourceRow).toList());
        return row;
    }

    private Map<String, Object> toSourceRow(SweepRuleSource source) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("accountId", source.getAccountId() != null ? source.getAccountId().toString() : null);
        row.put("accountNumber", source.getAccountNumber());
        row.put("entityCode", source.getEntityCode());
        row.put("entityName", source.getEntityName());
        row.put("currencyCode", source.getCurrencyCode());
        row.put("bankName", source.getBankName());
        return row;
    }
}
