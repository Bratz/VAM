package com.bank.vam.mcp.pipeline;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Drops any caller-supplied scope-shaped argument before a tool ever sees it.
 * {@code corporateId} (and any future {@code userId}/{@code entityId}-style
 * scope argument) must come only from the verified caller context that Phase
 * 3's gateway attaches — never from the tool call's own arguments, or a
 * caller could simply ask for someone else's corporate.
 *
 * <p>None of the 7 existing read tools currently accept these as parameters
 * (their {@code parameterSchema()} never declares them), so this is
 * defense-in-depth against a tool that adds one later without threading it
 * through {@code ToolContext} correctly — not a fix for an exploit that
 * exists today.
 */
@Component
public class McpArgumentStripper {

    private static final Set<String> SCOPE_ARGUMENT_NAMES = Set.of(
            "corporateId", "corporate_id", "userId", "user_id", "entityId", "entity_id");

    public Map<String, Object> strip(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> cleaned = new LinkedHashMap<>(arguments);
        cleaned.keySet().removeAll(SCOPE_ARGUMENT_NAMES);
        return cleaned;
    }
}
