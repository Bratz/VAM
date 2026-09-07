package com.bank.vam.ai.copilot.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Contract every Copilot tool implements.
 *
 * <p>A tool is a thin adapter over an existing domain service. It MUST:
 * <ol>
 *   <li>Pull data exclusively from real services / repositories (no fakes).</li>
 *   <li>Respect {@link ToolContext#corporateId()} if non-null.</li>
 *   <li>Return a {@link ToolResult} with a stable, template-friendly
 *       {@code data} shape — same keys regardless of input.</li>
 *   <li>Never throw past {@link #execute}; convert errors to
 *       {@link ToolResult#error}.</li>
 *   <li>Be idempotent and side-effect-free. Write actions live under
 *       {@code tools/write/} and use a different gating contract.</li>
 * </ol>
 */
public interface CopilotTool {

    /**
     * Stable identifier used by the IntentRouter and persisted in the
     * {@code tool_calls} jsonb. snake_case, no spaces, e.g.
     * {@code "get_position"}.
     */
    String name();

    /** One-line description shown in the {@code /tools} debug endpoint and the future LLM tool registry. */
    String description();

    /**
     * Documented parameters: name → {@code Map.of("type": ..., "description": ..., "required": ...)}.
     * Not enforced by the framework — each tool defends its own inputs in
     * {@link #execute}. The shape mirrors the Anthropic tool-use schema so
     * the v1 LLM swap can serialise this directly.
     */
    Map<String, Map<String, Object>> parameterSchema();

    /** Whether this tool mutates state. Read tools return false. */
    default boolean isMutating() {
        return false;
    }

    /**
     * MCP-shaped JSON Schema for this tool's input, derived from {@link
     * #parameterSchema()}: {@code {"type":"object","properties":{...},"required":[...]}}.
     * A tool's {@code parameterSchema()} value map may include a boolean
     * {@code "required"} entry (documented there, historically unused by any
     * tool) — this is where it finally gets honoured, so populate it on a tool
     * as it's reviewed for MCP exposure rather than changing the source shape.
     * Default method: no existing tool needs to change to keep compiling.
     */
    default Map<String, Object> inputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : parameterSchema().entrySet()) {
            Map<String, Object> descriptor = entry.getValue();
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", descriptor.getOrDefault("type", "string"));
            if (descriptor.containsKey("description")) {
                property.put("description", descriptor.get("description"));
            }
            properties.put(entry.getKey(), property);
            if (Boolean.TRUE.equals(descriptor.get("required"))) {
                required.add(entry.getKey());
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    /**
     * Optional ChatGPT Apps SDK UI component descriptor for this tool's result.
     * {@code null} (the default) means: no rich card, the client renders the
     * text summary / structuredContent generically. Only tools explicitly
     * curated for a card (see Phase 4 of {@code docs/mcp-architecture.md})
     * override this. Exact descriptor shape is Apps SDK-versioned — confirm
     * against current OpenAI docs at implementation time, don't assume.
     */
    default Map<String, Object> uiComponent() {
        return null;
    }

    /**
     * Execute the tool. Implementations MUST NOT throw; use
     * {@link ToolResult#error} for failures. The caller (CopilotService/
     * ResponseComposer) treats a thrown exception as a programming bug.
     */
    ToolResult execute(ToolContext context, Map<String, Object> params);

    // ------------------------------------------------------------------------
    // Param-reading helpers — small, defensive, shared by all tools
    // ------------------------------------------------------------------------

    default String paramString(Map<String, Object> params, String key, String defaultValue) {
        if (params == null) return defaultValue;
        Object v = params.get(key);
        if (v == null) return defaultValue;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? defaultValue : s;
    }

    default int paramInt(Map<String, Object> params, String key, int defaultValue) {
        if (params == null) return defaultValue;
        Object v = params.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    default List<String> paramList(Map<String, Object> params, String key) {
        if (params == null) return List.of();
        Object v = params.get(key);
        if (v instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (v instanceof String s && !s.isBlank()) {
            return List.of(s);
        }
        return List.of();
    }
}
