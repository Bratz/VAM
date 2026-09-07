package com.bank.vam.mcp.pipeline;

import com.bank.vam.ai.copilot.tools.ToolResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wraps a {@link ToolResult} — the shape every {@code CopilotTool} has always
 * returned — into an MCP 2026-07-28 {@code CallToolResult}: {@code resultType},
 * a {@code content} array, {@code structuredContent}, and {@code isError}.
 *
 * <p>Per spec, "a tool that returns structured content SHOULD also return the
 * serialized JSON in a {@code TextContent} block" — for a client that only
 * reads {@code content} and ignores {@code structuredContent}. So the text
 * block carries the JSON-serialized {@code data}, matching the spec's own
 * examples; {@code ToolResult.summary()} (the friendly one-liner the in-app
 * copilot drawer shows) is intentionally not surfaced here at all.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpResponseEnveloper {

    private final ObjectMapper objectMapper;

    public Map<String, Object> envelope(ToolResult result) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("resultType", "complete");
        envelope.put("isError", !result.ok());

        if (result.ok()) {
            envelope.put("content", List.of(textContent(toJson(result.data()))));
            envelope.put("structuredContent", result.data());
        } else {
            // Tool execution error (per spec: business-logic/validation failure the
            // model can see and retry differently) — no structuredContent, a plain
            // human-readable explanation in content instead.
            envelope.put("content", List.of(textContent(result.errorMessage())));
        }
        return envelope;
    }

    private Map<String, Object> textContent(String text) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "text");
        content.put("text", text);
        return content;
    }

    private String toJson(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize tool result data to JSON: {}", e.getMessage());
            return "{}";
        }
    }
}
