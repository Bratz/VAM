package com.bank.vam.ai.copilot.tools.read;

import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.McpUiDescriptors;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.entity.treasury.RejectionCodeConfig;
import com.bank.vam.entity.treasury.SweepInstruction.RejectionCategory;
import com.bank.vam.repository.treasury.RejectionCodeConfigRepository;
import com.bank.vam.service.treasury.rejection.RejectionCodeRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Read tool: look up rejection codes (single or by category).
 *
 * <p>Used in conjunction with {@code GetSweepInstructionsTool} to explain
 * why a sweep failed: the instruction's {@code rejectionCode} is fed into
 * this tool to return the human-readable description and
 * recoverable/unrecoverable classification.
 *
 * <p>Mapped intents (P3): {@code EXPLAIN_REJECTION}.
 *
 * <p>Parameters:
 * <ul>
 *   <li>{@code code} — single-code lookup (e.g. {@code AC04}, {@code AM04})</li>
 *   <li>{@code category} — list all codes in a category
 *       ({@code RECOVERABLE}/{@code UNRECOVERABLE})</li>
 * </ul>
 *
 * <p>If neither parameter is supplied, returns the entire registry (small
 * lookup table; safe to dump).
 *
 * <p>Output shape (single):
 * <pre>
 *   { code, category, description, known: true|false }
 * </pre>
 * <p>Output shape (list):
 * <pre>
 *   { totalMatched, category, codes: [{ code, category, description }] }
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetRejectionCodesTool implements CopilotTool {

    private final RejectionCodeConfigRepository configRepository;
    private final RejectionCodeRegistry registry;

    @Override
    public String name() {
        return "get_rejection_codes";
    }

    @Override
    public String description() {
        return "Look up a sweep rejection code's description and recoverable/unrecoverable category.";
    }

    @Override
    public Map<String, Map<String, Object>> parameterSchema() {
        return Map.of(
                "code", Map.of("type", "string",
                        "description", "Single rejection code (e.g. AC04, AM04, MS03, CUT_OFF_MISSED)"),
                "category", Map.of("type", "string",
                        "description", "List codes in category: RECOVERABLE or UNRECOVERABLE")
        );
    }

    @Override
    public Map<String, Object> uiComponent() {
        return McpUiDescriptors.widget(McpUiDescriptors.EXCEPTION_LIST, "Looking up rejection codes…", "Rejection codes ready");
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(ToolContext context, Map<String, Object> params) {
        try {
            String code = paramString(params, "code", null);
            String categoryParam = paramString(params, "category", null);

            if (code != null) {
                return lookupSingle(code);
            }

            RejectionCategory category = parseCategory(categoryParam);
            List<RejectionCodeConfig> rows = (category == null)
                    ? configRepository.findAll()
                    : configRepository.findByCategory(category);

            List<Map<String, Object>> codeRows = rows.stream()
                    .sorted(Comparator.comparing(RejectionCodeConfig::getCode))
                    .map(this::toRow)
                    .collect(Collectors.toList());

            String summary = String.format("%d rejection code%s%s",
                    codeRows.size(), codeRows.size() == 1 ? "" : "s",
                    category != null ? " in " + category : "");

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalMatched", codeRows.size());
            data.put("category", category == null ? null : category.name());
            data.put("codes", codeRows);

            return ToolResult.ok(name(), summary, data);
        } catch (Exception e) {
            log.warn("get_rejection_codes failed", e);
            return ToolResult.error(name(), "Failed to look up rejection codes: " + e.getMessage());
        }
    }

    private ToolResult lookupSingle(String rawCode) {
        String code = rawCode.trim().toUpperCase();
        Optional<RejectionCodeConfig> found = configRepository.findByCode(code);

        Map<String, Object> data = new LinkedHashMap<>();
        if (found.isEmpty()) {
            // Registry's safe-default classification still applies even when the
            // code is unknown — surface that to keep templates consistent.
            RejectionCategory fallback = registry.classify(code);
            data.put("code", code);
            data.put("category", fallback.name());
            data.put("description", null);
            data.put("known", false);
            return ToolResult.ok(name(),
                    "Code '" + code + "' is unknown — defaulted to " + fallback,
                    data);
        }
        RejectionCodeConfig cfg = found.get();
        data.put("code", cfg.getCode());
        data.put("category", cfg.getCategory().name());
        data.put("description", cfg.getDescription());
        data.put("known", true);
        return ToolResult.ok(name(),
                cfg.getCode() + ": " + cfg.getDescription() + " (" + cfg.getCategory() + ")",
                data);
    }

    private RejectionCategory parseCategory(String s) {
        if (s == null) return null;
        try {
            return RejectionCategory.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Map<String, Object> toRow(RejectionCodeConfig cfg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", cfg.getCode());
        m.put("category", cfg.getCategory().name());
        m.put("description", cfg.getDescription());
        return m;
    }
}
