package com.bank.vam.ai.copilot.tools.read;

import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.entity.credit.CreditLimit;
import com.bank.vam.entity.credit.CreditLimit.LimitStatus;
import com.bank.vam.entity.credit.CreditLimit.TargetType;
import com.bank.vam.repository.credit.CreditLimitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Read tool: credit limits for the corporate — group, entity, and VA level.
 *
 * <p>Output shape: {@code { totalMatched, groupLimits: [...], limits: [{ id,
 * limitName, limitType, targetType, limitAmount, limitCurrency,
 * utilizedAmount, availableAmount, warningThresholdPercent,
 * criticalThresholdPercent, status }] } } — {@code groupLimits} only present
 * when scoped.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetCreditLimitsTool implements CopilotTool {

    private final CreditLimitRepository creditLimitRepository;

    @Override
    public String name() {
        return "get_credit_limits";
    }

    @Override
    public String description() {
        return "Credit limits for the corporate — group, entity, and VA level.";
    }

    @Override
    public Map<String, Map<String, Object>> parameterSchema() {
        return Map.of(
                "status", Map.of("type", "string", "description", "ACTIVE, SUSPENDED, EXPIRED, BREACHED"),
                "targetType", Map.of("type", "string", "description", "GROUP, ENTITY, VIRTUAL_ACCOUNT"),
                "warningOnly", Map.of("type", "boolean", "description", "Only limits at/above their warning threshold")
        );
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(ToolContext context, Map<String, Object> params) {
        try {
            boolean warningOnly = paramBoolean(params, "warningOnly", false);
            String statusParam = paramString(params, "status", null);
            String targetTypeParam = paramString(params, "targetType", null);

            List<CreditLimit> limits;
            if (warningOnly && context.hasCorporateScope()) {
                limits = creditLimitRepository.findLimitsAtWarningLevel(context.corporateId());
            } else if (statusParam != null && context.hasCorporateScope()) {
                limits = creditLimitRepository.findByCorporateIdAndStatus(context.corporateId(), parseStatus(statusParam));
            } else if (targetTypeParam != null && parseTargetType(targetTypeParam) != null && context.hasCorporateScope()) {
                limits = creditLimitRepository.findByCorporateIdAndTargetType(context.corporateId(), parseTargetType(targetTypeParam));
            } else if (context.hasCorporateScope()) {
                limits = creditLimitRepository.findByCorporateIdOrderByLimitName(context.corporateId());
            } else if (statusParam != null) {
                limits = creditLimitRepository.findAll().stream()
                        .filter(l -> l.getStatus() == parseStatus(statusParam)).toList();
            } else {
                limits = creditLimitRepository.findAll();
            }

            List<Map<String, Object>> rows = limits.stream().map(this::toRow).collect(Collectors.toList());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalMatched", rows.size());
            if (context.hasCorporateScope()) {
                data.put("groupLimits", creditLimitRepository.findAllGroupLimitsByCorporate(context.corporateId())
                        .stream().map(this::toRow).toList());
            }
            data.put("limits", rows);

            return ToolResult.ok(name(), rows.size() + " credit limit(s) found", data);
        } catch (Exception e) {
            log.warn("get_credit_limits failed", e);
            return ToolResult.error(name(), "Failed to list credit limits: " + e.getMessage());
        }
    }

    private Map<String, Object> toRow(CreditLimit limit) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", limit.getId() != null ? limit.getId().toString() : null);
        row.put("limitName", limit.getLimitName());
        row.put("limitType", limit.getLimitType() != null ? limit.getLimitType().name() : null);
        row.put("targetType", limit.getTargetType() != null ? limit.getTargetType().name() : null);
        row.put("limitAmount", limit.getLimitAmount());
        row.put("limitCurrency", limit.getLimitCurrency());
        row.put("utilizedAmount", limit.getUtilizedAmount());
        row.put("availableAmount", limit.getAvailableAmount());
        row.put("warningThresholdPercent", limit.getWarningThresholdPercent());
        row.put("criticalThresholdPercent", limit.getCriticalThresholdPercent());
        row.put("status", limit.getStatus() != null ? limit.getStatus().name() : null);
        return row;
    }

    private LimitStatus parseStatus(String s) {
        try {
            return LimitStatus.valueOf(s.trim().toUpperCase());
        } catch (Exception e) {
            return LimitStatus.ACTIVE;
        }
    }

    private TargetType parseTargetType(String s) {
        try {
            return TargetType.valueOf(s.trim().toUpperCase());
        } catch (Exception e) {
            return null;
        }
    }
}
