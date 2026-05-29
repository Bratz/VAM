package com.bank.vam.ai.copilot.tools;

import java.util.Map;

/**
 * Uniform return shape for every {@link CopilotTool}. Two purposes:
 *
 * <ul>
 *   <li><b>Template input</b> — P3's {@code ResponseComposer} interpolates
 *       {@code data} into Mustache-style templates. Keys are stable, values
 *       are JSON-serialisable (primitives, strings, lists, maps).</li>
 *   <li><b>Provenance</b> — the assistant message's {@code tool_calls} jsonb
 *       column gets a list of {@code ToolResult}s so we always know which
 *       tools produced which numbers in a given reply.</li>
 * </ul>
 *
 * <p>{@code summary} is a one-line human-readable description ("Found 12
 * accounts in AED") suitable for the collapsed tool-call row in the drawer
 * UI; the expanded row shows {@code data}.
 *
 * <p>On failure, {@code ok=false} and {@code errorMessage} explains why;
 * templates can branch on this without throwing.
 */
public record ToolResult(
        String toolName,
        boolean ok,
        String summary,
        Map<String, Object> data,
        String errorMessage
) {

    public static ToolResult ok(String toolName, String summary, Map<String, Object> data) {
        return new ToolResult(toolName, true, summary, data, null);
    }

    public static ToolResult error(String toolName, String errorMessage) {
        return new ToolResult(toolName, false, errorMessage, Map.of(), errorMessage);
    }
}
