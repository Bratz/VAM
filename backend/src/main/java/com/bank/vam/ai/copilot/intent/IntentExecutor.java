package com.bank.vam.ai.copilot.intent;

import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolRegistry;
import com.bank.vam.ai.copilot.tools.ToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Maps a matched {@link Intent} to the set of read-tool invocations needed
 * to answer it, executes them, and returns the results.
 *
 * <p>For prototype write intents ({@link Intent#PAUSE_RULE},
 * {@link Intent#SET_ALERT}) this returns an empty list — the action-card
 * machinery lands in P5. The composer still produces a meaningful reply
 * by reading the slots directly.
 *
 * <p>The intent → tool mapping is centralised here on purpose: callers
 * (CopilotService, eventual LLM mode) shouldn't need to know which tool
 * answers which intent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentExecutor {

    private final ToolRegistry toolRegistry;

    public List<ToolResult> execute(IntentMatch match, UUID conversationId) {
        ToolContext ctx = toolRegistry.buildContext(null, "demo-user", conversationId);

        return switch (match.intent()) {
            case GET_POSITION -> List.of(
                    runTool("get_position", ctx, paramsForPosition(match, "entity")));

            case GET_POSITION_BY_BANK -> List.of(
                    runTool("get_position", ctx, paramsForPosition(match, "bank")));

            case FAILED_SWEEPS -> List.of(
                    runTool("get_sweep_instructions", ctx, paramsForFailedSweeps(match)));

            case EXPLAIN_REJECTION -> explainRejection(ctx, match);

            case IDLE_ACCOUNTS -> List.of(
                    runTool("get_accounts", ctx, paramsForIdleAccounts(match)));

            case RECENT_ACTIVITY -> List.of(
                    runTool("get_audit_trail", ctx, params("limit", 10)));

            case LIST_RULES -> List.of(
                    runTool("get_sweep_status", ctx, paramsForListRules(match)));

            case STATEMENT_SUMMARY -> List.of(
                    runTool("get_statement_lines", ctx, paramsForStatement(match)));

            // Write intents — now call their respective tools which create
            // an ActionProposal row and return action-card metadata under
            // {@code data._action}. The mutation itself runs only on Confirm
            // (POST /actions/{id}/execute → ActionExecutorService).
            case PAUSE_RULE -> List.of(
                    runTool("pause_sweep_rule", ctx, paramsForPauseRule(match)));

            case SET_ALERT -> List.of(
                    runTool("set_balance_alert", ctx, paramsForSetAlert(match)));

            case GREETING, UNKNOWN -> List.of();
        };
    }

    /** Backwards-compatible overload for callers that don't carry a conversation scope. */
    public List<ToolResult> execute(IntentMatch match) {
        return execute(match, null);
    }

    // ------------------------------------------------------------------------
    // Per-intent parameter builders — kept small + readable
    // ------------------------------------------------------------------------

    private Map<String, Object> paramsForPosition(IntentMatch match, String groupBy) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (match.hasSlot(Slot.CURRENCY)) p.put("currency", match.slotString(Slot.CURRENCY));
        p.put("groupBy", groupBy);
        return p;
    }

    private Map<String, Object> paramsForFailedSweeps(IntentMatch match) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("status", "REJECTED");
        p.put("since", match.hasSlot(Slot.SINCE) ? match.slotString(Slot.SINCE) : "24h");
        p.put("limit", 25);
        return p;
    }

    private Map<String, Object> paramsForListRules(IntentMatch match) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("limit", 25);
        return p;
    }

    private Map<String, Object> paramsForIdleAccounts(IntentMatch match) {
        Map<String, Object> p = new LinkedHashMap<>();
        // Prototype simplification: we surface accounts in the active currency
        // and let the composer narrate "potentially idle". A real "no outflow
        // in N days" cross-join would require a join with transactions —
        // tracked as a follow-up.
        if (match.hasSlot(Slot.CURRENCY)) p.put("currency", match.slotString(Slot.CURRENCY));
        p.put("status", "ACTIVE");
        p.put("limit", 25);
        return p;
    }

    private Map<String, Object> paramsForPauseRule(IntentMatch match) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (match.hasSlot(Slot.RULE_ID)) p.put("ruleId", match.slotString(Slot.RULE_ID));
        return p;
    }

    private Map<String, Object> paramsForSetAlert(IntentMatch match) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (match.hasSlot(Slot.ACCOUNT_REF)) p.put("accountRef", match.slotString(Slot.ACCOUNT_REF));
        if (match.hasSlot(Slot.DIRECTION)) p.put("direction", match.slotString(Slot.DIRECTION));
        if (match.hasSlot(Slot.AMOUNT)) {
            Object amt = match.slot(Slot.AMOUNT);
            p.put("threshold", amt instanceof BigDecimal b ? b : amt);
        }
        return p;
    }

    private Map<String, Object> paramsForStatement(IntentMatch match) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (match.hasSlot(Slot.CURRENCY)) p.put("currency", match.slotString(Slot.CURRENCY));
        p.put("since", match.hasSlot(Slot.SINCE) ? match.slotString(Slot.SINCE) : "24h");
        p.put("limit", 10);
        return p;
    }

    /**
     * Explain-rejection is the one multi-tool intent: it pulls the instruction
     * row (for context — amount, rule, when), then resolves the rejection
     * code through the registry (for the human description + category).
     */
    private List<ToolResult> explainRejection(ToolContext ctx, IntentMatch match) {
        Map<String, Object> instrParams = new LinkedHashMap<>();
        if (match.hasSlot(Slot.INSTRUCTION_ID)) {
            instrParams.put("id", match.slotString(Slot.INSTRUCTION_ID));
        }
        ToolResult instruction = match.hasSlot(Slot.INSTRUCTION_ID)
                ? runTool("get_sweep_instructions", ctx, instrParams)
                : null;

        // Prefer an explicit code slot, otherwise pull it off the first matched
        // instruction row.
        String code = match.slotString(Slot.REJECTION_CODE);
        if (code == null && instruction != null && instruction.ok()) {
            Object instructions = instruction.data().get("instructions");
            if (instructions instanceof List<?> list && !list.isEmpty()
                    && list.get(0) instanceof Map<?, ?> row) {
                Object c = row.get("rejectionCode");
                if (c != null) code = c.toString();
            }
        }

        ToolResult rejection = null;
        if (code != null) {
            rejection = runTool("get_rejection_codes", ctx, params("code", code));
        }

        if (instruction != null && rejection != null) return List.of(instruction, rejection);
        if (instruction != null) return List.of(instruction);
        if (rejection != null) return List.of(rejection);
        return List.of();
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private ToolResult runTool(String name, ToolContext ctx, Map<String, Object> params) {
        return toolRegistry.find(name)
                .map(t -> safeExecute(t, ctx, params))
                .orElseGet(() -> {
                    log.warn("Intent executor: tool '{}' not registered", name);
                    return ToolResult.error(name, "Tool not registered: " + name);
                });
    }

    private ToolResult safeExecute(CopilotTool tool, ToolContext ctx, Map<String, Object> params) {
        try {
            return tool.execute(ctx, params);
        } catch (Exception e) {
            log.warn("Tool {} threw unexpectedly", tool.name(), e);
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            return ToolResult.error(tool.name(), "Tool threw: " + msg);
        }
    }

    private static Map<String, Object> params(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }
}
