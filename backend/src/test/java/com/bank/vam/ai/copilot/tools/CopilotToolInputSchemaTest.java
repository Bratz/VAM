package com.bank.vam.ai.copilot.tools;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link CopilotTool#inputSchema()} — the MCP-shaped JSON Schema
 * derived from a tool's existing {@code parameterSchema()} map, added so the
 * dormant "required" key each tool has always been able to set finally gets
 * honoured, without any existing tool needing to change to keep compiling
 * (it's a default method on the interface).
 */
class CopilotToolInputSchemaTest {

    /** Minimal fake tool exercising both required and optional params. */
    private final CopilotTool tool = new CopilotTool() {
        @Override
        public String name() {
            return "fake_tool";
        }

        @Override
        public String description() {
            return "fake";
        }

        @Override
        public Map<String, Map<String, Object>> parameterSchema() {
            Map<String, Map<String, Object>> schema = new LinkedHashMap<>();
            schema.put("accountRef", Map.of("type", "string", "description", "Account", "required", true));
            schema.put("limit", Map.of("type", "integer", "description", "Max rows"));
            return schema;
        }

        @Override
        public ToolResult execute(ToolContext context, Map<String, Object> params) {
            return ToolResult.ok(name(), "ok", Map.of());
        }
    };

    @Test
    @SuppressWarnings("unchecked")
    void inputSchema_wrapsParameterSchemaAsJsonSchemaObject() {
        Map<String, Object> schema = tool.inputSchema();

        assertThat(schema.get("type")).isEqualTo("object");

        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).containsOnlyKeys("accountRef", "limit");
        Map<String, Object> accountRef = (Map<String, Object>) properties.get("accountRef");
        assertThat(accountRef.get("type")).isEqualTo("string");
        assertThat(accountRef.get("description")).isEqualTo("Account");
        // "required" must not leak into the property descriptor itself — it belongs
        // only in the schema-level "required" array.
        assertThat(accountRef).doesNotContainKey("required");

        assertThat((Iterable<String>) schema.get("required")).containsExactly("accountRef");
    }

    @Test
    void uiComponent_defaultsToNull() {
        assertThat(tool.uiComponent()).isNull();
    }
}
