package com.bank.vam.mcp.pipeline;

import com.bank.vam.ai.copilot.tools.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@code ToolResult} → MCP {@code CallToolResult} shape: every
 * result carries {@code resultType:"complete"} (MCP 2026-07-28 requires it on
 * every result), success carries structuredContent + a JSON text block per
 * spec's backward-compatibility guidance, failure carries isError + a plain
 * text explanation and no structuredContent.
 */
class McpResponseEnveloperTest {

    private final McpResponseEnveloper enveloper = new McpResponseEnveloper(new ObjectMapper());

    @Test
    void successResult_hasStructuredContentAndJsonTextBlock() {
        Map<String, Object> data = Map.of("currency", "USD", "totalCurrent", 1000);
        ToolResult result = ToolResult.ok("get_position", "USD position: 1000", data);

        Map<String, Object> envelope = enveloper.envelope(result);

        assertThat(envelope.get("resultType")).isEqualTo("complete");
        assertThat(envelope.get("isError")).isEqualTo(false);
        assertThat(envelope.get("structuredContent")).isEqualTo(data);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) envelope.get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("type")).isEqualTo("text");
        assertThat((String) content.get(0).get("text")).contains("\"currency\":\"USD\"");
    }

    @Test
    void errorResult_hasIsErrorTrueAndNoStructuredContent() {
        ToolResult result = ToolResult.error("get_position", "Something went wrong");

        Map<String, Object> envelope = enveloper.envelope(result);

        assertThat(envelope.get("resultType")).isEqualTo("complete");
        assertThat(envelope.get("isError")).isEqualTo(true);
        assertThat(envelope).doesNotContainKey("structuredContent");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) envelope.get("content");
        assertThat(content.get(0).get("text")).isEqualTo("Something went wrong");
    }
}
