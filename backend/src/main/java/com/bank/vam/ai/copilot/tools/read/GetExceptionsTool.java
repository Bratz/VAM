package com.bank.vam.ai.copilot.tools.read;

import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.entity.treasury.ExceptionTransaction;
import com.bank.vam.entity.treasury.ExceptionTransaction.ExceptionStatus;
import com.bank.vam.entity.treasury.ExceptionTransaction.ExceptionType;
import com.bank.vam.repository.treasury.ExceptionTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Read tool: exception/failed-item dashboard — parked transactions awaiting
 * resolution. Row list + total count only (no status/type rollup tiles —
 * those aggregate queries have no corporate-scoped variant, and the row list
 * with a total already answers "what needs attention").
 *
 * <p>Output shape: {@code { totalMatched, filter: { status, type, currency },
 * exceptions: [{ id, exceptionNumber, exceptionType, amount, currencyCode,
 * valueDate, status, priority, description, createdAt }] } }
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetExceptionsTool implements CopilotTool {

    static final int DEFAULT_LIMIT = 25;
    static final int MAX_LIMIT = 200;

    private final ExceptionTransactionRepository exceptionRepository;

    @Override
    public String name() {
        return "get_exceptions";
    }

    @Override
    public String description() {
        return "Exception/failed-item dashboard — parked transactions awaiting resolution.";
    }

    @Override
    public Map<String, Map<String, Object>> parameterSchema() {
        return Map.of(
                "status", Map.of("type", "string", "description", "OPEN, IN_PROGRESS, RESOLVED, WRITTEN_OFF"),
                "type", Map.of("type", "string", "description", "Exception type, e.g. UNMATCHED_CREDIT"),
                "currency", Map.of("type", "string", "description", "ISO-4217 code"),
                "limit", Map.of("type", "integer", "description", "Max rows to return (default 25, cap 200)")
        );
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(ToolContext context, Map<String, Object> params) {
        try {
            ExceptionStatus status = parseStatus(paramString(params, "status", null));
            ExceptionType type = parseType(paramString(params, "type", null));
            String currency = paramString(params, "currency", null);
            int limit = Math.min(Math.max(1, paramInt(params, "limit", DEFAULT_LIMIT)), MAX_LIMIT);
            Pageable pageable = PageRequest.of(0, limit);

            Page<ExceptionTransaction> page = context.hasCorporateScope()
                    ? exceptionRepository.findWithFiltersByCorporate(context.corporateId(), status, type, currency, pageable)
                    : exceptionRepository.findWithFilters(null, status, type, currency, pageable);

            List<Map<String, Object>> rows = page.getContent().stream().map(this::toRow).collect(Collectors.toList());

            Map<String, Object> filter = new LinkedHashMap<>();
            filter.put("status", status != null ? status.name() : null);
            filter.put("type", type != null ? type.name() : null);
            filter.put("currency", currency);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalMatched", page.getTotalElements());
            data.put("filter", filter);
            data.put("exceptions", rows);

            return ToolResult.ok(name(), page.getTotalElements() + " exception(s) found", data);
        } catch (Exception e) {
            log.warn("get_exceptions failed", e);
            return ToolResult.error(name(), "Failed to list exceptions: " + e.getMessage());
        }
    }

    private Map<String, Object> toRow(ExceptionTransaction ex) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", ex.getId() != null ? ex.getId().toString() : null);
        row.put("exceptionNumber", ex.getExceptionNumber());
        row.put("exceptionType", ex.getExceptionType() != null ? ex.getExceptionType().name() : null);
        row.put("amount", ex.getAmount());
        row.put("currencyCode", ex.getCurrencyCode());
        row.put("valueDate", ex.getValueDate());
        row.put("status", ex.getStatus() != null ? ex.getStatus().name() : null);
        row.put("priority", ex.getPriority() != null ? ex.getPriority().name() : null);
        row.put("description", ex.getDescription());
        row.put("createdAt", ex.getCreatedAt());
        return row;
    }

    private ExceptionStatus parseStatus(String s) {
        if (s == null) return null;
        try {
            return ExceptionStatus.valueOf(s.trim().toUpperCase());
        } catch (Exception e) {
            return null;
        }
    }

    private ExceptionType parseType(String s) {
        if (s == null) return null;
        try {
            return ExceptionType.valueOf(s.trim().toUpperCase());
        } catch (Exception e) {
            return null;
        }
    }
}
